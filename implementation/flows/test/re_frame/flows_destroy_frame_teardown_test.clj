(ns re-frame.flows-destroy-frame-teardown-test
  "Per rf2-wbtjn — `destroy-frame!` must clean up the per-frame flow
  state (registry slot, `last-inputs` rows, owning-frame registrar
  entries). Symmetric with the machines `:machines/teardown-on-frame-destroy!`
  hook (rf2-vsigt).

  Pre-rf2-wbtjn the implementation tore down sub-cache, machine
  cascade, SSR side-channels, schemas and epoch state on
  `destroy-frame!` but left flows untouched — `flows[frame-id]`,
  `last-inputs[flow-id][frame-id]` and any `:flow` registrar slots
  whose last owning frame was destroyed all retained references.
  Memory leak class: long-running SSR JVM (per-request frame churn),
  pair-tool time-travel, `make-frame` ephemeral usage.

  These JVM-side tests run on the plain-atom substrate against the
  late-bound `:flows/teardown-on-frame-destroy!` hook the flows
  artefact publishes for `frame/destroy-frame!`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            ;; Loading `re-frame.flows` registers the late-bind hook
            ;; (`:flows/teardown-on-frame-destroy!`) the tests exercise —
            ;; keep the require even when the test ns doesn't reach
            ;; `flows/...` directly through a public fn.
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---- per-test reset ------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! flows/last-inputs {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- hook publication ----------------------------------------------------

(deftest flows-publishes-teardown-hook
  (testing ":flows/teardown-on-frame-destroy! is published when re-frame.flows is loaded"
    (is (fn? (late-bind/get-fn :flows/teardown-on-frame-destroy!))
        "the hook is callable after the flows artefact ns-loads")))

;; ---- per-frame registry slot cleared on destroy --------------------------

(deftest destroy-frame-clears-per-frame-flow-registry-slot
  (testing "destroying a frame drops its slot from re-frame.flows.registry/flows"
    (rf/reg-frame :fc/scratch {:doc "scratch frame for destroy teardown test"})
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]}
                 {:frame :fc/scratch})
    (is (contains? @flows/flows :fc/scratch)
        "precondition: the flow registered under the scratch frame's slot")
    (frame/destroy-frame! :fc/scratch)
    (is (not (contains? @flows/flows :fc/scratch))
        "post-destroy: the destroyed frame's slot is gone")))

;; ---- last-inputs rows cleared on destroy --------------------------------

(deftest destroy-frame-clears-last-inputs-rows-for-destroyed-frame
  (testing "destroying a frame removes the destroyed-frame entry from each flow's last-inputs row"
    (rf/reg-frame :fc/scratch {:doc "scratch frame for last-inputs teardown test"})
    (rf/reg-event-db :fc/seed (fn [_ _] {:w 3 :h 4}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]}
                 {:frame :fc/scratch})
    ;; Drive a drain on the scratch frame so the dirty-check populates
    ;; `last-inputs[:area][:fc/scratch]`.
    (rf/dispatch-sync [:fc/seed] {:frame :fc/scratch})
    (is (= [3 4]
           (get-in @flows/last-inputs [:area :fc/scratch]))
        "precondition: last-inputs recorded the scratch frame's inputs")
    (frame/destroy-frame! :fc/scratch)
    (is (not (contains? (get @flows/last-inputs :area) :fc/scratch))
        "post-destroy: the destroyed frame's last-inputs entry is gone")
    (is (not (contains? @flows/last-inputs :area))
        "and the whole flow-id row is dropped (no other frame still held an entry)")))

;; ---- last-inputs rows from sibling frames are preserved -----------------

(deftest destroy-frame-preserves-sibling-frames-last-inputs
  (testing "destroying frame A leaves frame B's last-inputs row for the same flow id intact"
    (rf/reg-frame :fc/a {:doc "frame A"})
    (rf/reg-frame :fc/b {:doc "frame B"})
    (rf/reg-event-db :fc/seed-a (fn [_ _] {:w 2 :h 5}))
    (rf/reg-event-db :fc/seed-b (fn [_ _] {:w 7 :h 9}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]}
                 {:frame :fc/a})
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]}
                 {:frame :fc/b})
    (rf/dispatch-sync [:fc/seed-a] {:frame :fc/a})
    (rf/dispatch-sync [:fc/seed-b] {:frame :fc/b})
    (is (= [2 5] (get-in @flows/last-inputs [:area :fc/a])))
    (is (= [7 9] (get-in @flows/last-inputs [:area :fc/b])))
    (frame/destroy-frame! :fc/a)
    (is (not (contains? (get @flows/last-inputs :area) :fc/a))
        "destroyed-frame A's last-inputs row is gone")
    (is (= [7 9] (get-in @flows/last-inputs [:area :fc/b]))
        "sibling frame B's last-inputs row is preserved")))

;; ---- registrar :flow slot pruned when destroyed frame was last owner ----

(deftest destroy-frame-prunes-registrar-slot-when-last-owner
  (testing "destroying the only frame that owned a flow id drops the :flow registrar slot"
    (rf/reg-frame :fc/scratch {:doc "scratch frame"})
    (rf/reg-flow {:id     :sole-area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]}
                 {:frame :fc/scratch})
    (is (some? (registrar/lookup :flow :sole-area))
        "precondition: registrar carries the flow slot")
    (frame/destroy-frame! :fc/scratch)
    (is (nil? (registrar/lookup :flow :sole-area))
        "post-destroy: registrar slot is gone — no leaked entry")))

;; ---- registrar :flow slot preserved when sibling frame still owns it ----

(deftest destroy-frame-preserves-registrar-slot-when-sibling-still-owns
  (testing "destroying frame A leaves :flow registrar slot intact if frame B still registers the id"
    (rf/reg-frame :fc/a {:doc "frame A"})
    (rf/reg-frame :fc/b {:doc "frame B"})
    (rf/reg-flow {:id     :shared
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]}
                 {:frame :fc/a})
    (rf/reg-flow {:id     :shared
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]}
                 {:frame :fc/b})
    (frame/destroy-frame! :fc/a)
    (is (some? (registrar/lookup :flow :shared))
        "registrar slot survives because frame B still registers :shared")))

;; ---- SSR-style per-request frame churn stays bounded --------------------

(deftest ssr-style-frame-churn-stays-bounded
  (testing "creating + destroying N ephemeral frames each with a flow leaves the registry empty"
    (let [N 20]
      (dotimes [i N]
        (let [frame-id (keyword "fc" (str "ephemeral-" i))]
          (rf/reg-frame frame-id {:doc (str "ephemeral frame " i)})
          (rf/reg-flow {:id     :churn
                        :inputs [[:n]]
                        :output (fn [n] (or n 0))
                        :path   [:result]}
                       {:frame frame-id})
          (rf/reg-event-db :fc/seed-churn (fn [_ [_ v]] {:n v}))
          (rf/dispatch-sync [:fc/seed-churn i] {:frame frame-id})
          (frame/destroy-frame! frame-id)))
      (is (empty? @flows/flows)
          "per-frame flow registry is empty after N destroy cycles")
      (is (empty? @flows/last-inputs)
          "last-inputs is empty after N destroy cycles")
      (is (nil? (registrar/lookup :flow :churn))
          "registrar :flow slot is gone after the last owning frame was destroyed"))))

;; ---- frame-id reuse: new reg-frame starts clean -------------------------

(deftest reg-frame-after-destroy-starts-clean
  (testing "registering a frame under a reused id after destroy starts with no leftover flow state"
    (rf/reg-frame :fc/scratch {:doc "first incarnation"})
    (rf/reg-event-db :fc/seed (fn [_ _] {:w 3 :h 4}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]}
                 {:frame :fc/scratch})
    (rf/dispatch-sync [:fc/seed] {:frame :fc/scratch})
    (frame/destroy-frame! :fc/scratch)
    (rf/reg-frame :fc/scratch {:doc "second incarnation"})
    (is (not (contains? @flows/flows :fc/scratch))
        "the new frame has no inherited flow-registry slot")
    (is (not (contains? (get @flows/last-inputs :area) :fc/scratch))
        "the new frame has no inherited last-inputs row")
    (is (nil? (registrar/lookup :flow :area))
        "the new frame has no inherited :flow registrar slot")))

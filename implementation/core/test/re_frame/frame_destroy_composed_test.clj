(ns re-frame.frame-destroy-composed-test
  "Composed teardown interleavings, the cases most likely to leave a sub-cache,
  epoch buffer, flow or schema row, or frames-store record behind: a reaction
  whose `dispose!` throws mid-walk, a layered sub whose input release cascades
  during the walk, an `:on-destroy` that dispatch-syncs into a sibling, and a
  frame touching every per-frame subsystem at once. The teardown recipe is
  host-agnostic, so the JVM covers it.

  What was torn down is always-on; the lifecycle emits narrating it are dev
  trace and read in `(when rf.interop/debug-enabled? ...)` arms. The epoch ring
  is fed from the dev trace, so the leak audit's epoch precondition and
  postcondition are guarded together: either alone would be vacuous under the
  prod gate."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; publishes the machines teardown hooks
            [re-frame.machines]))

;; ---- fixture --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.flows/reset-last-inputs!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; restore the ns-load registrations and late-bind hooks clear-all! wiped
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.flows :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest destroy-with-throwing-reaction-dispose-still-completes
  ;; one bad dispose! must not strand the rest of the sub-cache walk
  (rf/make-frame {:id :composed/sub-throw :doc "sub-throw"})
  (rf/reg-event :composed/seed (fn [{:keys [db]} _] {:db {:a 1 :b 2}}))
  (rf/reg-sub :composed/a (fn [db _] (:a db)))
  (rf/reg-sub :composed/b (fn [db _] (:b db)))
  (rf/dispatch-sync [:composed/seed] {:frame :composed/sub-throw})
  (let [r1                 (rf/subscribe [:composed/a] {:frame :composed/sub-throw})
        r2                 (rf/subscribe [:composed/b] {:frame :composed/sub-throw})
        throwing-disposed  (atom 0)
        surviving-disposed (atom 0)]
    (rf.interop/add-on-dispose! r1
                                (fn []
                                  (swap! throwing-disposed inc)
                                  (throw (ex-info "dispose blew" {}))))
    (rf.interop/add-on-dispose! r2 (fn [] (swap! surviving-disposed inc)))
    (is (= 2 (count @(:sub-cache (rf.frame/frame :composed/sub-throw))))
        "precondition: both subscriptions are cached")
    (is (= [nil 1 1 nil]
           [(rf.frame/destroy-frame! :composed/sub-throw)
            @throwing-disposed
            @surviving-disposed
            (rf.frame/frame :composed/sub-throw)])
        "destroy completes, both dispose hooks fire, and the frame is gone")))

(deftest destroy-emits-exactly-one-dispose-per-slot-for-layered-sub
  ;; A layer-2 sub's on-dispose releases its inputs. Unless the walk evicts the
  ;; whole cache first, an input still cached would be disposed a second time
  ;; by that cascade (as :no-more-derefers), racing the walk's :frame-destroy.
  (rf/make-frame {:id :composed/layered-destroy :doc "layered destroy"})
  (rf/reg-event :composed/seed-layered (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
  (rf/reg-sub :composed/layered-a (fn [db _] (:a db)))
  (rf/reg-sub :composed/layered-b (fn [db _] (:b db)))
  (rf/reg-sub :composed/layered-sum
    {:inputs [[:composed/layered-a] [:composed/layered-b]]}
    (fn [[a b] _] (+ a b)))
  (rf/dispatch-sync [:composed/seed-layered] {:frame :composed/layered-destroy})
  (let [disposes (atom [])]
    (rf/register-listener! :trace ::layered-destroy
                           (fn [ev]
                             (when (= :rf.sub/dispose (:operation ev))
                               (swap! disposes conj ev))))
    (try
      (let [r        (rf/subscribe [:composed/layered-sum] {:frame :composed/layered-destroy})
            cache    (:sub-cache (rf.frame/frame :composed/layered-destroy))
            disposed (atom [])]
        (is (= [5 3] [@r (count @cache)]) "precondition: the sum and both inputs are cached")
        ;; counts the disposals themselves, in both postures
        (doseq [[q-v node] @cache]
          (rf.interop/add-on-dispose! (:reaction node)
                                      (fn [] (swap! disposed conj q-v))))
        (rf.frame/destroy-frame! :composed/layered-destroy)
        (is (= {[:composed/layered-sum] 1 [:composed/layered-a] 1 [:composed/layered-b] 1}
               (frequencies @disposed))
            "every cached slot was disposed exactly once")
        (when rf.interop/debug-enabled?
          (is (= {[[:composed/layered-sum] :frame-destroy :composed/layered-destroy] 1
                  [[:composed/layered-a] :frame-destroy :composed/layered-destroy]   1
                  [[:composed/layered-b] :frame-destroy :composed/layered-destroy]   1}
                 (frequencies (map (juxt #(-> % :tags :rf.sub/query-v)
                                         #(-> % :tags :rf.sub/reason)
                                         #(-> % :tags :frame))
                                   @disposes)))
              "one :frame-destroy dispose emit per slot, attributed to the frame")))
      (finally
        (rf/unregister-listener! :trace ::layered-destroy)))))

(deftest cross-frame-dispatch-from-on-destroy-warns-and-commits
  ;; an :on-destroy that dispatch-syncs into a sibling mid-drain (Spec 002)
  (rf/make-frame {:id :composed/parent :doc "parent"})
  (rf/reg-event :composed/notify-parent
    (fn [{:keys [db]} [_ payload]]
      {:db (assoc db :last-notification payload)}))
  (rf/reg-event :composed/teardown
    (fn [_ _]
      (rf/dispatch-sync [:composed/notify-parent :child-gone]
                        {:frame :composed/parent})
      {}))
  (rf/make-frame {:id :composed/child :doc        "child with cross-frame :on-destroy"
                  :on-destroy [:composed/teardown]})
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::xfx (fn [ev] (swap! recorded conj ev)))
    (rf/destroy-frame! :composed/child)
    (rf/unregister-listener! :trace ::xfx)
    (is (= [:child-gone nil true]
           [(:last-notification (rf/app-db-value :composed/parent))
            (rf.frame/frame :composed/child)
            (some? (rf.frame/frame :composed/parent))])
        "the sibling committed, the child tore down, and the parent stays live")
    (when rf.interop/debug-enabled?
      (is (some #(and (= :warning (:op-type %))
                      (= :rf.warning/cross-frame-dispatch-sync-during-drain (:operation %)))
                @recorded)))))

(deftest composed-destroy-leak-audit
  ;; every per-frame subsystem with a teardown hook, cleared by one destroy
  (rf/make-frame {:id :composed/leak-audit :doc "leak-audit"})
  (rf/reg-app-schema [:n] {:frame :composed/leak-audit} [:int])
  (rf/reg-event :composed/seed-leak (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
  (rf/reg-flow :composed/area {:frame :composed/leak-audit :inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* (or w 0) (or h 0))))
  ;; registered before the cascade, so the drain records the frame against it
  (rf/register-listener! :epoch ::composed-observer (fn [_r] nil))
  (rf/dispatch-sync [:composed/seed-leak] {:frame :composed/leak-audit})
  ;; written directly: a sibling's flows reload can gate the walker's hook
  (rf.flows.registry/set-frame-flow-last-inputs! :composed/leak-audit :composed/area [3 4])
  (rf/reg-sub :composed/leak-rect (fn [db _] (:rect db)))
  (rf/subscribe [:composed/leak-rect] {:frame :composed/leak-audit})
  (let [epoch-observed? #(contains? (get @(deref #'rf.epoch.state/observed-frames-by-cb)
                                         ::composed-observer)
                                    :composed/leak-audit)
        rows            (fn []
                          [(contains? (rf.schemas/snapshot-schemas-by-frame) :composed/leak-audit)
                           (contains? (rf.flows/flows-snapshot) :composed/leak-audit)
                           (contains? (get (rf.flows/last-inputs-snapshot) :composed/area)
                                      :composed/leak-audit)
                           (some? (get @rf.frame/frames :composed/leak-audit))])]
    (is (= [true true true true] (rows)) "precondition: schema, flow, last-inputs and frame rows")
    (is (pos? (count @(:sub-cache (rf.frame/frame :composed/leak-audit)))))
    (when rf.interop/debug-enabled?
      (is (= [true true]
             [(epoch-observed?) (pos? (count (rf/epoch-history :composed/leak-audit)))])
          "precondition: the epoch ring and observer saw the frame"))
    (rf.frame/destroy-frame! :composed/leak-audit)
    (is (= [false false false false] (rows)))
    (when rf.interop/debug-enabled?
      (is (= [false []] [(epoch-observed?) (rf/epoch-history :composed/leak-audit)]))))
  (rf/unregister-listener! :epoch ::composed-observer))

(ns re-frame.adapter.with-resource-lease-dom-cljs-test
  "Reagent DOM/browser coverage for `with-resource-lease` (rf2-cxozh4,
  EP-0020 §Open Issue #1) — the mount-lifecycle resource-lease component,
  the Reagent (Form-3) counterpart of the UIx / Helix `use-resource-lease`
  hook.

  The acceptance property: MOUNT dispatches `:rf.resource/ensure` with an
  app-minted `[:lease …]` owner + the descriptor; UNMOUNT dispatches
  `:rf.resource/release-owner` for that SAME lease. Spy event handlers under
  the two public resource event ids record the payloads.

  ASYNC (queued-dispatch drain) + MAP-form fixture, per the UIx twin.

  Browser-only — the lifecycle methods only fire under a real React mount.
  `-dom-cljs-test$` opts it into `:browser-test`; `:node-test` loads it too
  and the DOM branch gates on `(browser?)`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [reagent.dom.client :as rdc]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]))

;; ---- MAP-form fixture (async-safe); pins the React-context tier ------------
;; No ambient `*current-frame*` scope — the with-resource-lease under test
;; must resolve the frame-provider's frame via the class React-context tier,
;; which an ambient :rf/default would shadow.

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! reagent-adapter/adapter)
  (frame/ensure-default-frame!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {}))

(use-fixtures :each {:before before! :after after!})

;; ---- spy channels ----------------------------------------------------------

(def ^:private ensures  (atom []))
(def ^:private releases (atom []))

(def ^:private lease-frame :rf.reagent-resource-lease/frame)
(def ^:private descriptor
  {:resource :rf.reagent-resource-lease/feed
   :scope    :rf.scope/global
   :params   {:page 0}})

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (or (when (exists? (.-act React)) (.-act React))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- install-spies! []
  (reset! ensures [])
  (reset! releases [])
  (rf/reg-frame lease-frame {:doc "with-resource-lease spy frame"})
  (rf/reg-event :rf.resource/ensure
                (fn [_cofx [_ payload]] (swap! ensures conj payload) {}))
  (rf/reg-event :rf.resource/release-owner
                (fn [_cofx [_ payload]] (swap! releases conj payload) {})))

;; Wait two macrotasks so the router's next-tick drain has run.
(defn- after-drain [k] (js/setTimeout (fn [] (js/setTimeout k 0)) 0))

(deftest with-resource-lease-mount-acquires-unmount-releases
  (testing "Reagent — mount ensures with a [:lease …] owner; unmount releases it"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (async done
            (binding [frame/*current-frame* nil]
              (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
              (install-spies!)
              (let [mount-node (.createElement js/document "div")
                    root       (rdc/create-root mount-node)]
                (act-fn
                  (fn []
                    (rdc/render root
                      [rf/frame-provider {:frame lease-frame}
                       [reagent-adapter/with-resource-lease
                        descriptor
                        (fn [] [:div "leased"])]])))
                (after-drain
                  (fn []
                    (is (= 1 (count @ensures)) "exactly one ensure on mount")
                    (let [p (first @ensures)]
                      (is (= (:resource descriptor) (:resource p)) "ensure carried the resource")
                      (is (= (:scope descriptor)    (:scope p))    "ensure carried the scope")
                      (is (= (:params descriptor)   (:params p))   "ensure carried the params")
                      (is (vector? (:owner p)) "owner is a vector")
                      (is (= :lease (first (:owner p))) "owner is an app-minted [:lease …]")
                      (is (some? (:cause p)) "ensure carried a cause"))
                    (is (empty? @releases) "no release before unmount")
                    (act-fn (fn [] (rdc/unmount root)))
                    (after-drain
                      (fn []
                        (is (= 1 (count @releases)) "exactly one release on unmount")
                        (is (= (:owner (first @ensures)) (:owner (first @releases)))
                            "release drops the SAME lease that was acquired")
                        (done)))))))))))))

(deftest with-resource-lease-explicit-frame-and-cause
  (testing "Reagent — :frame opt pins the lease + :cause is recorded (no provider needed)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (async done
            (binding [frame/*current-frame* nil]
              (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
              (install-spies!)
              (let [mount-node (.createElement js/document "div")
                    root       (rdc/create-root mount-node)]
                ;; No frame-provider — the :frame opt supplies the target.
                (act-fn
                  (fn []
                    (rdc/render root
                      [reagent-adapter/with-resource-lease
                       descriptor
                       {:frame lease-frame :cause :dashboard}
                       (fn [] [:div "leased"])])))
                (after-drain
                  (fn []
                    (is (= 1 (count @ensures)) "ensure fired via the :frame opt")
                    (is (= :dashboard (:cause (first @ensures))) "explicit :cause recorded")
                    (act-fn (fn [] (rdc/unmount root)))
                    (after-drain
                      (fn []
                        (is (= 1 (count @releases)) "release fired on unmount")
                        (done)))))))))))))

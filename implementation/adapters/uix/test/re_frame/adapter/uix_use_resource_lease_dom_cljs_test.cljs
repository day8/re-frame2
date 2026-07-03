(ns re-frame.adapter.uix-use-resource-lease-dom-cljs-test
  "UIx DOM/browser coverage for `use-resource-lease` (rf2-cxozh4, EP-0020
  §Open Issue #1) — the mount-lifecycle resource-lease hook.

  The acceptance property: MOUNT dispatches `:rf.resource/ensure` with an
  app-minted `[:lease …]` owner + the descriptor; UNMOUNT dispatches
  `:rf.resource/release-owner` for that SAME lease. We do not load the
  resources runtime's real handlers' behaviour here — instead we register
  SPY event handlers under the two public resource event ids and assert on
  the payloads they record. The shared substrate-agnostic hook lives in
  `re-frame.adapter.resource-lease`; the Helix twin
  (`helix_use_resource_lease_dom_cljs_test`) exercises the same fn through
  the Helix re-export, so a gap on one substrate is a gap on both.

  ASYNC: the hook dispatches through the QUEUED path (`dispatch*` — the
  correct choice for a lifecycle effect), which drains on the router's
  next-tick. Each test mounts / unmounts under `act`, then awaits a
  macrotask so the enqueued ensure / release has drained before asserting.
  A MAP-form fixture (not the fn-form `make-reset-runtime-fixture`) is
  required because cljs.test fn-form fixtures do not suspend across an async
  body — mirrors the frame-provider-context async-DOM idiom.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers it;
  `:node-test`'s `cljs-test$` regex also matches, where each test self-gates
  on `(browser?)` and no-ops cleanly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]))

;; ---- MAP-form fixture (async-safe) -----------------------------------------

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! uix-adapter/adapter)
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

(def ^:private lease-frame :rf.uix-resource-lease/frame)
(def ^:private descriptor
  {:resource :rf.uix-resource-lease/feed
   :scope    :rf.scope/global
   :params   {:page 0}})

(defui ProbeLease []
  (uix-adapter/use-resource-lease descriptor)
  ($ :div "leased"))

(defui ProbeLeaseExplicitFrame []
  ;; No surrounding frame-provider — the :frame opt supplies the target.
  (uix-adapter/use-resource-lease descriptor {:frame lease-frame :cause :dashboard})
  ($ :div "leased"))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (or (when (exists? (.-act React)) (.-act React))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- install-spies! []
  (reset! ensures [])
  (reset! releases [])
  (rf/reg-frame lease-frame {:doc "use-resource-lease spy frame"})
  (rf/reg-event :rf.resource/ensure
                (fn [_cofx [_ payload]] (swap! ensures conj payload) {}))
  (rf/reg-event :rf.resource/release-owner
                (fn [_cofx [_ payload]] (swap! releases conj payload) {})))

;; Wait two macrotasks so the router's next-tick drain has run.
(defn- after-drain [k] (js/setTimeout (fn [] (js/setTimeout k 0)) 0))

(deftest use-resource-lease-mount-acquires-unmount-releases
  (testing "UIx — mount ensures with a [:lease …] owner; unmount releases it"
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
                    root       (react-dom-client/createRoot mount-node)]
                (act-fn
                  (fn []
                    (.render root
                      ($ uix-adapter/frame-provider {:frame lease-frame}
                         ($ ProbeLease)))))
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
                    (act-fn (fn [] (.unmount root)))
                    (after-drain
                      (fn []
                        (is (= 1 (count @releases)) "exactly one release on unmount")
                        (is (= (:owner (first @ensures)) (:owner (first @releases)))
                            "release drops the SAME lease that was acquired")
                        (done)))))))))))))

(deftest use-resource-lease-explicit-frame-pins-lease
  (testing "UIx — :frame opt pins the lease into an explicit frame (no provider needed)"
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
                    root       (react-dom-client/createRoot mount-node)]
                ;; No frame-provider — the :frame opt supplies the target.
                (act-fn (fn [] (.render root ($ ProbeLeaseExplicitFrame))))
                (after-drain
                  (fn []
                    (is (= 1 (count @ensures)) "ensure fired via the :frame opt")
                    (is (= :dashboard (:cause (first @ensures))) "explicit :cause recorded")
                    (act-fn (fn [] (.unmount root)))
                    (after-drain
                      (fn []
                        (is (= 1 (count @releases)) "release fired on unmount")
                        (done)))))))))))))

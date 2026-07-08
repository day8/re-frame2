(ns re-frame.adapter.helix-use-resource-lease-dom-cljs-test
  "Helix DOM/browser coverage for `use-resource-lease` (rf2-cxozh4, EP-0020
  §Open Issue #1) — the mount-lifecycle resource-lease hook. Twin of the UIx
  suite (`uix_use_resource_lease_dom_cljs_test`); both drive the ONE shared
  hook in `re-frame.adapter.resource-lease` through their substrate's
  re-export, so a gap on one substrate is a gap on both.

  The acceptance property: MOUNT dispatches `:rf.resource/ensure` with an
  app-minted `[:lease …]` owner + the descriptor; UNMOUNT dispatches
  `:rf.resource/release-owner` for that SAME lease. Spy event handlers under
  the two public resource event ids record the payloads.

  ASYNC (queued-dispatch drain) + MAP-form fixture, per the UIx twin's note.

  ns ends in `-dom-cljs-test` for `:browser-test` discovery; `:node-test`
  matches too, where each test self-gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [helix.core :refer-macros [$ defnc]]
            [helix.dom  :as d]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]))

;; ---- MAP-form fixture (async-safe) -----------------------------------------

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! helix-adapter/adapter)
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

(def ^:private lease-frame :rf.helix-resource-lease/frame)
(def ^:private descriptor
  {:resource :rf.helix-resource-lease/feed
   :scope    :rf.scope/global
   :params   {:page 0}})

(defnc ProbeLease []
  (helix-adapter/use-resource-lease descriptor)
  (d/div "leased"))

(defnc ProbeLeaseExplicitFrame []
  ;; No surrounding frame-provider — the :frame opt supplies the target.
  (helix-adapter/use-resource-lease descriptor {:frame lease-frame :cause :dashboard})
  (d/div "leased"))

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
  (testing "Helix — mount ensures with a [:lease …] owner; unmount releases it"
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
                      ($ helix-adapter/frame-provider {:frame lease-frame}
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
  (testing "Helix — :frame opt pins the lease into an explicit frame (no provider needed)"
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

;; ---- StrictMode double-mount (rf2-ui5ahy) ---------------------------------
;;
;; use-resource-lease is an EFFECT-driven mount-lifecycle hook (ensure on
;; setup, release on cleanup) — the seam React's dev double-invoke stresses
;; most. React 18/19's default dev scaffold wraps the tree in
;; `React.StrictMode`, which double-invokes the mount effect
;; (setup → cleanup → setup). The lease token is minted ONCE per instance via
;; `useRef` (re-frame.adapter.resource-lease), so it must be STABLE across the
;; double-invoke: the mount cycle emits ensure(T) → release(T) → ensure(T)
;; (net one held lease T), and unmount emits release(T) (net zero) — ALL under
;; the SAME lease token. A per-invoke token (the regression this pins) would
;; leak a second, never-released lease: two distinct tokens and an unbalanced
;; ledger. Mirrors the use-subscribe StrictMode refcount-balance gate
;; (rf2-nymuy); use-resource-lease is at least as effect-centric.

(deftest use-resource-lease-strictmode-double-mount-balances
  (testing "Helix — use-resource-lease under React.StrictMode: ONE stable lease token across the dev double-invoke; balanced ensure/release ledger (rf2-ui5ahy)"
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
                ;; Mount ProbeLease wrapped in React.StrictMode — the mount
                ;; effect double-invokes setup → cleanup → setup.
                (act-fn
                  (fn []
                    (.render root
                      (React/createElement
                        (.-StrictMode React) nil
                        ($ helix-adapter/frame-provider {:frame lease-frame}
                           ($ ProbeLease))))))
                (after-drain
                  (fn []
                    ;; StrictMode double-invoked the mount effect: the setup
                    ;; ran on mount AND again after the dev cleanup.
                    (is (<= 2 (count @ensures))
                        (str "StrictMode double-invoked the mount effect (ensure on mount + remount) — got "
                             (count @ensures) " ensures"))
                    ;; The crux: the useRef-minted lease token is STABLE across
                    ;; the double-invoke — exactly ONE distinct lease owner.
                    ;; A per-invoke token would show two distinct owners here.
                    (is (= 1 (count (distinct (map :owner @ensures))))
                        (str "exactly ONE distinct lease token across all ensures — the useRef token survived StrictMode's mount→cleanup→mount; owners="
                             (pr-str (distinct (map :owner @ensures)))))
                    ;; net one held lease after the mount cycle settles
                    ;; (ensure → release → ensure).
                    (is (= 1 (- (count @ensures) (count @releases)))
                        (str "net one held lease after the mount double-invoke settles — ensures="
                             (count @ensures) " releases=" (count @releases)))
                    (act-fn (fn [] (.unmount root)))
                    (after-drain
                      (fn []
                        ;; Fully balanced after unmount — no leaked lease.
                        (is (= (count @ensures) (count @releases))
                            (str "ensure/release ledger balanced after unmount (no leaked lease) — ensures="
                                 (count @ensures) " releases=" (count @releases)))
                        (is (= (set (map :owner @ensures))
                               (set (map :owner @releases)))
                            "every ensured lease token was released — the SAME stable token acquired was dropped")
                        (done)))))))))))))

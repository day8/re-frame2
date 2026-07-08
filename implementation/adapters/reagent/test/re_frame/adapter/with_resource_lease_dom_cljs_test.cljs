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
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.router :as router]
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

;; ---- adversarial: re-lease on descriptor change (rf2-04fky9) ---------------
;;
;; These tests capture dispatches at the `router/dispatch!` seam (with-redefs)
;; so the lease's ensure/release ORDER, the reused owner token, and the
;; resolved frame opt are observed directly and synchronously — no queued-
;; dispatch drain needed. Resource-event dispatches are isolated by ns so any
;; frame-provider plumbing dispatch is filtered out.

(def ^:private lease-descriptor-p0
  {:resource :rf.reagent-resource-lease/feed :scope :rf.scope/global :params {:page 0}})

(defn- resource-event? [event]
  (= "rf.resource" (namespace (first event))))

(defn- ev-id      [[event _opts]] (first event))
(defn- ev-payload [[event _opts]] (second event))
(defn- ev-frame   [[_event opts]] (:frame opts))

(defn- capturing-dispatch!
  "A `router/dispatch!` stand-in whose arities MATCH the real Var so the
  adapter's compiler-optimized arity-2 call site resolves against the redef —
  a plain single-body `(fn [e o] …)` compiles to a bare JS function with no
  `cljs$core$IFn$_invoke$arity$2` method and the optimized call throws. Records
  only resource-event dispatches as `[event opts]`."
  [dispatches]
  (fn capture
    ([event] (capture event nil))
    ([event opts]
     (when (resource-event? event)
       (swap! dispatches conj [event opts]))
     nil)))

(deftest with-resource-lease-re-leases-on-descriptor-change
  (testing "Reagent — a changing descriptor RELEASES the old lease then ENSURES
            the new one (same token) without an unmount (rf2-04fky9)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (binding [frame/*current-frame* nil]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (rf/reg-frame lease-frame {:doc "re-lease spy frame"})
            (let [dispatches (atom [])
                  page       (r/atom 0)
                  tick       (r/atom 0)
                  descriptor-for (fn [p] {:resource :rf.reagent-resource-lease/feed
                                          :scope    :rf.scope/global
                                          :params   {:page p}})
                  ;; Reactive PARENT: mounted once, derefs `page`/`tick` and feeds a
                  ;; fresh descriptor to a STABLE with-resource-lease child instance.
                  ;; A `page` change is thus an in-place UPDATE (componentDidUpdate),
                  ;; NOT a remount — the real outer/inner Form-3 scenario
                  ;; (adapters/reagent/README.md), so the re-lease runs with the
                  ;; component still mounted (the bead's 'no unmount needed').
                  parent     (fn parent []
                               @tick ;; re-render lever that leaves the descriptor untouched
                               [reagent-adapter/with-resource-lease
                                (descriptor-for @page) (fn [] [:div "leased"])])
                  mount-node (.createElement js/document "div")
                  root       (rdc/create-root mount-node)]
              (with-redefs [router/dispatch! (capturing-dispatch! dispatches)]
                ;; Mount at page 0.
                (act-fn (fn [] (rdc/render root
                                 [rf/frame-provider {:frame lease-frame} [parent]])))
                (is (= 1 (count @dispatches)) "exactly one ensure on mount")
                (is (= :rf.resource/ensure (ev-id (nth @dispatches 0))))
                (is (= {:page 0} (:params (ev-payload (nth @dispatches 0))))
                    "mount ensured page 0")
                (let [owner0 (:owner (ev-payload (nth @dispatches 0)))]
                  ;; Change page ⇒ the parent re-renders and hands the SAME child
                  ;; instance a new descriptor ⇒ componentDidUpdate re-leases.
                  (reagent-adapter/flush-views! (fn [] (reset! page 1)))
                  (is (= 3 (count @dispatches))
                      "mount ensure + update release + update ensure (no unmount)")
                  (is (= :rf.resource/release-owner (ev-id (nth @dispatches 1)))
                      "the update RELEASES before it ensures")
                  (is (= owner0 (:owner (ev-payload (nth @dispatches 1))))
                      "release drops the ORIGINAL page-0 lease owner")
                  (is (= :rf.resource/ensure (ev-id (nth @dispatches 2)))
                      "then ensures the new descriptor")
                  (is (= {:page 1} (:params (ev-payload (nth @dispatches 2))))
                      "re-ensure carries page 1's params")
                  (is (= owner0 (:owner (ev-payload (nth @dispatches 2))))
                      "re-ensure REUSES the same lease token (twin parity)"))
                ;; A re-render that leaves the descriptor value-equal must NOT churn.
                (reagent-adapter/flush-views! (fn [] (swap! tick inc)))
                (is (= 3 (count @dispatches))
                    "a value-equal descriptor re-render holds one lease, no churn")
                (act-fn (fn [] (rdc/unmount root)))
                (is (= 4 (count @dispatches)) "unmount releases the live lease")
                (is (= :rf.resource/release-owner (ev-id (nth @dispatches 3))))))))))))

(deftest with-resource-lease-dynamic-var-frame-wins-over-provider
  (testing "Reagent — a with-frame-scoped dynamic-var frame WINS over a
            conflicting surrounding frame-provider (EP-0002 tier precedence,
            rf2-5bo24q)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (let [provider-frame :rf.reagent-lease-prec/provider
                dynamic-frame  :rf.reagent-lease-prec/dynamic
                dispatches     (atom [])
                mount-node     (.createElement js/document "div")
                root           (rdc/create-root mount-node)]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (rf/reg-frame provider-frame {:doc "surrounding provider"})
            (rf/reg-frame dynamic-frame  {:doc "dynamic-var scope"})
            (with-redefs [router/dispatch! (capturing-dispatch! dispatches)]
              ;; Pin *current-frame* to the dynamic frame (exactly what
              ;; `with-frame` does) around a SYNCHRONOUS act() mount under a
              ;; CONFLICTING provider. Old code read the React-context tier
              ;; first ⇒ targeted the provider; the EP-0002 fix resolves the
              ;; dynamic var first at render time ⇒ targets the dynamic frame.
              (binding [frame/*current-frame* dynamic-frame]
                (act-fn
                  (fn []
                    (rdc/render root
                      [rf/frame-provider {:frame provider-frame}
                       [reagent-adapter/with-resource-lease
                        lease-descriptor-p0 (fn [] [:div "leased"])]]))))
              (is (= 1 (count @dispatches)) "one ensure on mount")
              (is (= dynamic-frame (ev-frame (nth @dispatches 0)))
                  "lease targets the DYNAMIC-VAR frame (dynamic var wins)")
              (is (not= provider-frame (ev-frame (nth @dispatches 0)))
                  "the surrounding provider's frame did NOT win")
              (act-fn (fn [] (rdc/unmount root))))))))))

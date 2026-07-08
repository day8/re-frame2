(ns re-frame.adapter.reagent-slim-with-resource-lease-dom-cljs-test
  "reagent-slim DOM/browser coverage for `with-resource-lease` (rf2-zxxc9f,
  EP-0020 §Open Issue #1) — the slim counterpart of the Reagent-bridge
  `re-frame.adapter.with-resource-lease-dom-cljs-test`. The slim adapter was
  the only browser adapter missing the leasing surface; this file proves it
  now exists AND was authored correct from the start: it re-leases on a
  descriptor change (rf2-04fky9) and honours EP-0002 tier precedence with the
  dynamic var winning over the React-context tier (rf2-5bo24q).

  The acceptance property: MOUNT dispatches `:rf.resource/ensure` with an
  app-minted `[:lease …]` owner; a descriptor CHANGE releases the old owner
  then ensures the new one (same token); UNMOUNT releases the live owner.
  Dispatches are captured at the `router/dispatch!` seam so the ordering,
  reused token, and resolved frame opt are observed directly + synchronously.

  Browser-only — the lifecycle methods only fire under a real React mount.
  `-dom-cljs-test$` opts it into `:browser-test`; `:node-test` loads it too
  and the DOM branch gates on `(browser?)`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.router :as router]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views]))

;; `:ambient-frame nil` opts out of the fixture's default ambient
;; `*current-frame*` :rf/default scope so the mount-under-provider case
;; resolves the lease frame via the React-context tier (an ambient scope
;; would shadow it), and the precedence case can pin the dynamic var itself.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter
     :ambient-frame nil}))

;; ---- helpers ---------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(def ^:private lease-frame :rf.reagent-slim-resource-lease/frame)
(def ^:private descriptor-p0
  {:resource :rf.reagent-slim-resource-lease/feed :scope :rf.scope/global :params {:page 0}})
(def ^:private descriptor-p1
  {:resource :rf.reagent-slim-resource-lease/feed :scope :rf.scope/global :params {:page 1}})

(defn- resource-event? [event] (= "rf.resource" (namespace (first event))))
(defn- ev-id      [[event _opts]] (first event))
(defn- ev-payload [[event _opts]] (second event))
(defn- ev-frame   [[_event opts]] (:frame opts))

(defn- capturing-dispatch!
  "A `router/dispatch!` stand-in whose arities MATCH the real Var so the
  adapter's compiler-optimized arity-2 call site resolves against the redef
  (a plain single-body `(fn [e o] …)` has no `cljs$core$IFn$_invoke$arity$2`
  method). Records only resource-event dispatches as `[event opts]`."
  [dispatches]
  (fn capture
    ([event] (capture event nil))
    ([event opts]
     (when (resource-event? event)
       (swap! dispatches conj [event opts]))
     nil)))

;; Mount / re-render the same root synchronously (flushSync commits React 19's
;; otherwise-async root.render, firing componentDidMount / componentDidUpdate
;; before the call returns) so the captured dispatches are ready to assert.
(defn- render-sync! [root tree]
  (react-dom/flushSync (fn [] (rdc/render root tree))))

;; Unmount synchronously so componentWillUnmount (the release) fires before the
;; assertion on the next line.
(defn- unmount-sync! [root]
  (react-dom/flushSync (fn [] (rdc/unmount root))))

;; ---- acquire on mount / release on unmount --------------------------------

(deftest slim-with-resource-lease-mount-acquires-unmount-releases
  (testing "reagent-slim — mount ensures with a [:lease …] owner; unmount releases it"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (do
        (rf/reg-frame lease-frame {:doc "slim lease spy frame"})
        (let [dispatches (atom [])
              mount-node (.createElement js/document "div")
              root       (rdc/create-root mount-node)]
          (with-redefs [router/dispatch! (capturing-dispatch! dispatches)]
            (try
              (render-sync! root
                [rf/frame-provider {:frame lease-frame}
                 [reagent-slim-adapter/with-resource-lease
                  descriptor-p0 (fn [] [:div "leased"])]])
              (is (= 1 (count @dispatches)) "exactly one ensure on mount")
              (let [p (ev-payload (nth @dispatches 0))]
                (is (= :rf.resource/ensure (ev-id (nth @dispatches 0))))
                (is (= (:resource descriptor-p0) (:resource p)) "ensure carried the resource")
                (is (= (:params descriptor-p0)   (:params p))   "ensure carried the params")
                (is (vector? (:owner p)) "owner is a vector")
                (is (= :lease (first (:owner p))) "owner is an app-minted [:lease …]")
                (is (= lease-frame (ev-frame (nth @dispatches 0)))
                    "ensure targeted the provider frame via the React-context tier"))
              (is (= 1 (count @dispatches)) "no release before unmount")
              (unmount-sync! root)
              (is (= 2 (count @dispatches)) "exactly one release on unmount")
              (is (= :rf.resource/release-owner (ev-id (nth @dispatches 1))))
              (is (= (:owner (ev-payload (nth @dispatches 0)))
                     (:owner (ev-payload (nth @dispatches 1))))
                  "release drops the SAME lease that was acquired")
              (finally
                (try (.unmount root) (catch :default _ nil))))))))))

;; ---- adversarial: re-lease on descriptor change (rf2-04fky9 carried into slim) --

(deftest slim-with-resource-lease-re-leases-on-descriptor-change
  (testing "reagent-slim — a changing descriptor RELEASES the old lease then
            ENSURES the new one (same token) without an unmount"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (do
        (rf/reg-frame lease-frame {:doc "slim re-lease spy frame"})
        (let [dispatches (atom [])
              mount-node (.createElement js/document "div")
              root       (rdc/create-root mount-node)
              render-at  (fn [descriptor label]
                           (render-sync! root
                             [rf/frame-provider {:frame lease-frame}
                              [reagent-slim-adapter/with-resource-lease
                               descriptor (fn [] [:div label])]]))]
          (with-redefs [router/dispatch! (capturing-dispatch! dispatches)]
            (try
              (render-at descriptor-p0 "p0")
              (is (= 1 (count @dispatches)) "one ensure on mount")
              (is (= {:page 0} (:params (ev-payload (nth @dispatches 0)))) "mount ensured page 0")
              (let [owner0 (:owner (ev-payload (nth @dispatches 0)))]
                (render-at descriptor-p1 "p1")
                (is (= 3 (count @dispatches))
                    "mount ensure + update release + update ensure (no unmount)")
                (is (= :rf.resource/release-owner (ev-id (nth @dispatches 1)))
                    "the update RELEASES before it ensures")
                (is (= owner0 (:owner (ev-payload (nth @dispatches 1))))
                    "release drops the ORIGINAL page-0 lease owner")
                (is (= :rf.resource/ensure (ev-id (nth @dispatches 2))))
                (is (= {:page 1} (:params (ev-payload (nth @dispatches 2))))
                    "re-ensure carries page 1's params")
                (is (= owner0 (:owner (ev-payload (nth @dispatches 2))))
                    "re-ensure REUSES the same lease token (twin parity)"))
              ;; value-equal re-render ⇒ no churn.
              (render-at descriptor-p1 "p1-again")
              (is (= 3 (count @dispatches))
                  "a value-equal descriptor re-render holds one lease, no churn")
              (finally
                (try (.unmount root) (catch :default _ nil))))))))))

;; ---- adversarial: EP-0002 tier precedence (rf2-5bo24q carried into slim) ----

(deftest slim-with-resource-lease-dynamic-var-frame-wins-over-provider
  (testing "reagent-slim — a with-frame-scoped dynamic-var frame WINS over a
            conflicting surrounding frame-provider (EP-0002 tier precedence)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [provider-frame :rf.reagent-slim-lease-prec/provider
            dynamic-frame  :rf.reagent-slim-lease-prec/dynamic
            dispatches     (atom [])
            mount-node     (.createElement js/document "div")
            root           (rdc/create-root mount-node)]
        (rf/reg-frame provider-frame {:doc "surrounding provider"})
        (rf/reg-frame dynamic-frame  {:doc "dynamic-var scope"})
        (with-redefs [router/dispatch! (capturing-dispatch! dispatches)]
          (try
            ;; Pin *current-frame* to the dynamic frame (what `with-frame`
            ;; does) around a SYNCHRONOUS mount under a CONFLICTING provider.
            ;; EP-0002: the render-time resolver reads the dynamic var first.
            (binding [frame/*current-frame* dynamic-frame]
              (render-sync! root
                [rf/frame-provider {:frame provider-frame}
                 [reagent-slim-adapter/with-resource-lease
                  descriptor-p0 (fn [] [:div "leased"])]]))
            (is (= 1 (count @dispatches)) "one ensure on mount")
            (is (= dynamic-frame (ev-frame (nth @dispatches 0)))
                "lease targets the DYNAMIC-VAR frame (dynamic var wins)")
            (is (not= provider-frame (ev-frame (nth @dispatches 0)))
                "the surrounding provider's frame did NOT win")
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))

;; ---- opts-map arm: explicit :frame pin + custom :cause (rf2-pdjy4g) --------
;;
;; The three tests above all call `[with-resource-lease descriptor (fn [] …)]`
;; — a body thunk directly after the descriptor — which always takes
;; `lease-args`' `(fn? (first args))` → `[nil body]` arm, so the opts-map arm
;; `[(first args) (second args)]` and `resolve-lease-frame`'s
;; `(or explicit-frame …)` truthy branch are NEVER exercised. This mounts with
;; an opts MAP between the descriptor and the body thunk, pinning `:frame`
;; explicitly and overriding `:cause`, then asserts both flow through.

(deftest slim-with-resource-lease-explicit-frame-and-cause-opts
  (testing "reagent-slim — an opts map {:frame :cause} between descriptor and
            body pins the lease to the EXPLICIT :frame (bypassing the
            surrounding provider / ambient resolution) and records the custom
            :cause on the ensure"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [provider-frame :rf.reagent-slim-lease-opts/provider
            explicit-frame :rf.reagent-slim-lease-opts/explicit
            custom-cause   :dashboard-widget
            dispatches     (atom [])
            mount-node     (.createElement js/document "div")
            root           (rdc/create-root mount-node)]
        (rf/reg-frame provider-frame {:doc "surrounding provider (should be bypassed)"})
        (rf/reg-frame explicit-frame {:doc "explicitly pinned lease frame"})
        (with-redefs [router/dispatch! (capturing-dispatch! dispatches)]
          (try
            (render-sync! root
              [rf/frame-provider {:frame provider-frame}
               [reagent-slim-adapter/with-resource-lease
                descriptor-p0
                {:frame explicit-frame :cause custom-cause}
                (fn [] [:div "leased"])]])
            (is (= 1 (count @dispatches)) "one ensure on mount")
            (is (= :rf.resource/ensure (ev-id (nth @dispatches 0))))
            (is (= explicit-frame (ev-frame (nth @dispatches 0)))
                "ensure targeted the EXPLICIT :frame opt, not the surrounding provider")
            (is (not= provider-frame (ev-frame (nth @dispatches 0)))
                "the surrounding provider frame did NOT win over the explicit :frame")
            (is (= custom-cause (:cause (ev-payload (nth @dispatches 0))))
                "ensure carried the custom :cause opt (not the default [:lease :mount])")
            (is (= (:resource descriptor-p0) (:resource (ev-payload (nth @dispatches 0))))
                "the descriptor still flowed through alongside the opts map")
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))

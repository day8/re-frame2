(ns re-frame.adapter.uix-use-subscribe-cljs-test
  "Unit coverage for the UIx adapter's `use-subscribe` hook (rf2-518sp).

  Pre-rf2-518sp, `use-subscribe` was published as the canonical UIx
  subscribe surface (rf2-3yij Decision 1) and exercised only through
  the Playwright `counter_uix` smoke; no node-runtime headless test
  asserted that the React.useSyncExternalStore wiring sees post-
  dispatch values. A regression in `subscribe-fn`'s add-watch keying,
  `get-snap`'s reaction deref, the use-memo deps vec, or the
  unsubscribe-on-unmount cleanup (rf2-7g959) would slip past
  `test:cljs` / `test:browser` and only surface in the smoke.

  Pattern: mirror the Reagent adapter's
  `scenario-7-concurrent-renders-survive-act-flush` (in
  `frame_provider_context_cljs_test.cljs`) — gate on `(browser?)`
  and exit early under `:node-test` where there is no DOM. The
  browser-test runner exercises the real assertions.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require ["react"            :as React]
            ["react-dom/client" :as react-dom-client]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

;; ---- side-channel atoms ----------------------------------------------------
;; Per-test atoms read by the UIx probe components below. The probes
;; are defui top-levels (uix `defui` defines a Var; it cannot sit
;; inside a `let`) and close over these atoms; each deftest `reset!`s
;; the atom it cares about at the top of its body.

(def ^:private probe-observed       (atom []))
(def ^:private probe-fp-observed    (atom []))
(def ^:private refcount-target      (atom nil))

(defui Probe []
  (let [target @refcount-target
        v (uix-adapter/use-subscribe target
                                      [:rf.uix-use-subscribe-test/n])]
    (swap! probe-observed conj v)
    ($ :div (str "n=" v))))

(defui ProbeFp []
  (let [v (uix-adapter/use-subscribe
            [:rf.uix-use-subscribe-test/k])]
    (swap! probe-fp-observed conj v)
    ($ :div (str "k=" v))))

(defui ProbeRc []
  (let [target @refcount-target
        v (uix-adapter/use-subscribe target
                                      [:rf.uix-use-subscribe-test/m])]
    ($ :div (str "m=" v))))

;; ---- browser gate ---------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- get-act
  "Return React's act() if available, else nil. React 18 ships act in
  react-dom/test-utils; React 19 promotes it to the React namespace
  proper."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [test-utils (js/require "react-dom/test-utils")]
          (.-act test-utils))
        (catch :default _ nil))))

(defn- enable-react-act-env!
  "React's act() helper warns / behaves as a no-op unless the runner
  opts in by setting the global `IS_REACT_ACT_ENVIRONMENT` flag. The
  Playwright browser runner doesn't set this by default; set it
  inside each test that needs act() so concurrent-renderer pending
  work commits synchronously."
  []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

;; ---- assertions ------------------------------------------------------------
;;
;; The probe component reads the sub via `use-subscribe` and pushes the
;; observed value into a side-channel atom. After a dispatch we
;; re-render under `act` and assert the side-channel reflects the new
;; value. The 2-arg form pins the frame explicitly; the 1-arg form
;; resolves through the surrounding `frame-provider`.

(deftest use-subscribe-tracks-app-db-changes
  (testing "UIx use-subscribe sees post-dispatch values via useSyncExternalStore"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the assertions")
      (let [target :rf.uix-use-subscribe-test/probe-frame
            act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (reset! probe-observed [])
            (reset! refcount-target target)
            (rf/reg-frame target {:doc "use-subscribe probe frame"})
            (rf/reg-event-db :seed-uix-us (fn [_ _] {:n 1}))
            (rf/reg-event-db :inc-uix-us  (fn [db _] (update db :n inc)))
            (rf/dispatch-sync [:seed-uix-us] {:frame target})
            (rf/reg-sub :rf.uix-use-subscribe-test/n
                        (fn [db _] (:n db)))
            (let [mount-node (make-mount-node!)
                  root       (react-dom-client/createRoot mount-node)]
              (try
                (act-fn (fn [] (.render root (uix/$ Probe))))
                (is (some #{1} @probe-observed)
                    "first render observed the seeded value n=1")
                ;; Wrap dispatch in act so React commits the forceUpdate
                ;; the spine's add-watch → on-change path schedules.
                ;; Plain `dispatch-sync` outside act emits the
                ;; "update was not wrapped in act" warning AND fails to
                ;; flush the resulting render in the test environment.
                (act-fn (fn [] (rf/dispatch-sync [:inc-uix-us] {:frame target})))
                (is (some #{2} @probe-observed)
                    "post-dispatch re-render observed the incremented value n=2")
                (finally
                  (try (.unmount root) (catch :default _ nil)))))))))))

(deftest use-subscribe-frame-provider-resolution
  (testing "UIx use-subscribe 1-arg form resolves through the surrounding frame-provider"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the assertions")
      (let [target :rf.uix-use-subscribe-test/fp-frame
            act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (reset! probe-fp-observed [])
            (rf/reg-frame target {:doc "use-subscribe fp probe frame"})
            (rf/reg-event-db :seed-uix-us-fp (fn [_ _] {:k :wrapped}))
            (rf/dispatch-sync [:seed-uix-us-fp] {:frame target})
            (rf/reg-sub :rf.uix-use-subscribe-test/k
                        (fn [db _] (:k db)))
            (let [mount-node (make-mount-node!)
                  root       (react-dom-client/createRoot mount-node)]
              (try
                (act-fn
                  (fn []
                    ;; frame-provider is a plain CLJS fn returning a
                    ;; React element (NOT a React-component head), so
                    ;; invoke it directly rather than via `(uix/$ ...)`.
                    (.render root
                      (uix-adapter/frame-provider
                        {:frame target :children [(uix/$ ProbeFp)]}))))
                (is (some #{:wrapped} @probe-fp-observed)
                    "use-subscribe 1-arg form read from the wrapped frame, not :rf/default")
                (finally
                  (try (.unmount root) (catch :default _ nil)))))))))))

;; ---- sub-cache refcount cleanup (rf2-7g959) -------------------------------
;;
;; Pre-rf2-7g959, `use-subscribe` only removed the add-watch on
;; unmount; the sub-cache ref-count for the (frame, query) pair was
;; never decremented, so the entry stayed pinned at 1 for the process
;; lifetime. With the rf2-7g959 cleanup in the spine, an explicit
;; `subs/unsubscribe` fires from a useEffect cleanup so the cache
;; entry's ref-count returns to 0 after the deferred-dispose grace
;; period.

(deftest use-subscribe-cleanup-decrements-sub-cache-refcount
  (testing "UIx use-subscribe pairs subscribe with subs/unsubscribe on unmount (rf2-7g959)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the assertions")
      (let [target :rf.uix-use-subscribe-test/refcount-frame
            act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (reset! refcount-target target)
            (rf/reg-frame target {:doc "refcount probe frame"})
            (rf/reg-event-db :seed-uix-us-rc (fn [_ _] {:m 0}))
            (rf/dispatch-sync [:seed-uix-us-rc] {:frame target})
            (rf/reg-sub :rf.uix-use-subscribe-test/m
                        (fn [db _] (:m db)))
            (let [cache-key-v [:rf.uix-use-subscribe-test/m]
                  cache       (:sub-cache (frame/frame target))
                  mount-node  (make-mount-node!)
                  root        (react-dom-client/createRoot mount-node)]
              (try
                (act-fn (fn [] (.render root (uix/$ ProbeRc))))
                (is (pos? (or (get-in @cache [cache-key-v :ref-count])
                              0))
                    "mounted probe pinned a cache entry with ref-count > 0")
                (act-fn (fn [] (.unmount root)))
                ;; After unmount the useEffect cleanup fires
                ;; subs/unsubscribe; the entry's deferred-dispose
                ;; either races a 0 ref-count or schedules grace-period
                ;; teardown. Either way the ref-count is no longer
                ;; pinned at >0 — the regression rf2-7g959 named.
                (is (or (nil? (get @cache cache-key-v))
                        (zero? (or (get-in @cache [cache-key-v :ref-count])
                                   0)))
                    "post-unmount ref-count is zero (or entry already dropped) — rf2-7g959 cleanup fired")
                (finally
                  (try (.unmount root) (catch :default _ nil)))))))))))

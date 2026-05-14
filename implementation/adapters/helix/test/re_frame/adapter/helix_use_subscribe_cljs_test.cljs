(ns re-frame.adapter.helix-use-subscribe-cljs-test
  "Unit coverage for the Helix adapter's `use-subscribe` hook (rf2-518sp).

  Pre-rf2-518sp, `use-subscribe` was published as the canonical Helix
  subscribe surface (rf2-2qit Decision 1) and exercised only through
  the Playwright `counter_helix` smoke; no node-runtime headless test
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
            [helix.core :refer-macros [$ defnc]]
            [helix.dom  :as d]
            [helix.hooks :as helix-hooks]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.subs :as subs]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

;; ---- side-channel atoms ----------------------------------------------------
;; Per-test atoms read by the Helix probe components below. The probes
;; are defnc top-levels (helix `defnc` defines a Var; it cannot sit
;; inside a `let`) and close over these atoms; each deftest `reset!`s
;; the atom it cares about at the top of its body.

(def ^:private probe-observed       (atom []))
(def ^:private probe-fp-observed    (atom []))
(def ^:private refcount-target      (atom nil))

(defnc Probe []
  (let [target @refcount-target
        v (helix-adapter/use-subscribe target
                                       [:rf.helix-use-subscribe-test/n])]
    (swap! probe-observed conj v)
    (d/div (str "n=" v))))

(defnc ProbeFp []
  (let [v (helix-adapter/use-subscribe
            [:rf.helix-use-subscribe-test/k])]
    (swap! probe-fp-observed conj v)
    (d/div (str "k=" v))))

(defnc ProbeRc []
  (let [target @refcount-target
        v (helix-adapter/use-subscribe target
                                       [:rf.helix-use-subscribe-test/m])]
    (d/div (str "m=" v))))

;; ---- rf2-mwft2 regression probes ------------------------------------------
;; A parent that owns a tick state (used to force re-renders) plus a child
;; that reads a fixed query-v via use-subscribe. The literal `[:rf.helix-mwft2/p]`
;; vector evaluates to a fresh JS object each render — *exactly* the shape
;; the bug-without-fix walks into. The parent stashes its set-tick fn into
;; a side-channel atom so the test can drive forced re-renders from outside.

(def ^:private mwft2-set-tick      (atom nil))

(defnc ProbeMwft2Child []
  (let [v (helix-adapter/use-subscribe :rf.helix-mwft2/probe-frame
                                       [:rf.helix-mwft2/p])]
    (d/div (str "p=" v))))

(defnc ProbeMwft2Parent []
  (let [[tick set-tick] (helix-hooks/use-state 0)]
    (helix-hooks/use-effect
      ;; React state-setters have stable identity across renders so an
      ;; empty deps vec is correct — matches React's "set-state setter is
      ;; stable" guarantee. The effect runs once on mount to stash the
      ;; setter for the test driver.
      []
      (reset! mwft2-set-tick set-tick)
      (fn cleanup [] nil))
    (d/div {:data-tick tick}
           ($ ProbeMwft2Child))))

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
  (testing "Helix use-subscribe sees post-dispatch values via useSyncExternalStore"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the assertions")
      (let [target :rf.helix-use-subscribe-test/probe-frame
            act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (reset! probe-observed [])
            (reset! refcount-target target)
            (rf/reg-frame target {:doc "use-subscribe probe frame"})
            (rf/reg-event-db :seed-helix-us (fn [_ _] {:n 1}))
            (rf/reg-event-db :inc-helix-us  (fn [db _] (update db :n inc)))
            (rf/dispatch-sync [:seed-helix-us] {:frame target})
            (rf/reg-sub :rf.helix-use-subscribe-test/n
                        (fn [db _] (:n db)))
            (let [mount-node (make-mount-node!)
                  root       (react-dom-client/createRoot mount-node)]
              (try
                (act-fn (fn [] (.render root ($ Probe))))
                (is (some #{1} @probe-observed)
                    "first render observed the seeded value n=1")
                ;; Wrap dispatch in act so React commits the forceUpdate
                ;; the spine's add-watch → on-change path schedules.
                ;; Plain `dispatch-sync` outside act emits the
                ;; "update was not wrapped in act" warning AND fails to
                ;; flush the resulting render in the test environment.
                (act-fn (fn [] (rf/dispatch-sync [:inc-helix-us] {:frame target})))
                (is (some #{2} @probe-observed)
                    "post-dispatch re-render observed the incremented value n=2")
                (finally
                  (try (.unmount root) (catch :default _ nil)))))))))))

(deftest use-subscribe-frame-provider-resolution
  (testing "Helix use-subscribe 1-arg form resolves through the surrounding frame-provider"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the assertions")
      (let [target :rf.helix-use-subscribe-test/fp-frame
            act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (reset! probe-fp-observed [])
            (rf/reg-frame target {:doc "use-subscribe fp probe frame"})
            (rf/reg-event-db :seed-helix-us-fp (fn [_ _] {:k :wrapped}))
            (rf/dispatch-sync [:seed-helix-us-fp] {:frame target})
            (rf/reg-sub :rf.helix-use-subscribe-test/k
                        (fn [db _] (:k db)))
            (let [mount-node (make-mount-node!)
                  root       (react-dom-client/createRoot mount-node)]
              (try
                (act-fn
                  (fn []
                    ;; frame-provider is a plain CLJS fn returning a
                    ;; React element (NOT a React-component head), so
                    ;; invoke it directly rather than via `($ ...)`.
                    (.render root
                      (helix-adapter/frame-provider
                        {:frame target :children [($ ProbeFp)]}))))
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
  (testing "Helix use-subscribe pairs subscribe with subs/unsubscribe on unmount (rf2-7g959)"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the assertions")
      (let [target :rf.helix-use-subscribe-test/refcount-frame
            act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (reset! refcount-target target)
            (rf/reg-frame target {:doc "refcount probe frame"})
            (rf/reg-event-db :seed-helix-us-rc (fn [_ _] {:m 0}))
            (rf/dispatch-sync [:seed-helix-us-rc] {:frame target})
            (rf/reg-sub :rf.helix-use-subscribe-test/m
                        (fn [db _] (:m db)))
            (let [cache-key-v [:rf.helix-use-subscribe-test/m]
                  cache       (:sub-cache (frame/frame target))
                  mount-node  (make-mount-node!)
                  root        (react-dom-client/createRoot mount-node)]
              (try
                (act-fn (fn [] (.render root ($ ProbeRc))))
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

;; ---- 2-arg form: explicit frame-id wins over context (rf2-y0db2) ----------
;;
;; The 2-arg form `(use-subscribe frame-kw query-v)` is documented to
;; bypass the React-context tier and read from the named frame's
;; app-db directly. Although the existing Probe defnc above uses the
;; 2-arg form, no deftest explicitly pinned that two different
;; frame-ids in the SAME render tree (no surrounding provider) see
;; distinct values. Parity with UIx's `use-subscribe-2-arg-pins-
;; explicit-frame` (rf2-rcgsc).

(def ^:private probe-2arg-a-observed (atom []))
(def ^:private probe-2arg-b-observed (atom []))

(defnc Probe2ArgA []
  (let [v (helix-adapter/use-subscribe :rf.helix-rcgsc/tenant-a
                                       [:rf.helix-rcgsc/n])]
    (swap! probe-2arg-a-observed conj v)
    (d/div (str "a=" v))))

(defnc Probe2ArgB []
  (let [v (helix-adapter/use-subscribe :rf.helix-rcgsc/tenant-b
                                       [:rf.helix-rcgsc/n])]
    (swap! probe-2arg-b-observed conj v)
    (d/div (str "b=" v))))

(deftest use-subscribe-2-arg-pins-explicit-frame
  (testing "rf2-y0db2 (parity with UIx rf2-rcgsc): use-subscribe's 2-arg
            form `(use-subscribe frame-kw query-v)` reads from the named
            frame's app-db, bypassing the React-context tier. Two
            probes pinning two different frames in the same render
            tree must see each frame's distinct seed value."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the assertions")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (reset! probe-2arg-a-observed [])
            (reset! probe-2arg-b-observed [])
            (rf/reg-frame :rf.helix-rcgsc/tenant-a {:doc "tenant-a"})
            (rf/reg-frame :rf.helix-rcgsc/tenant-b {:doc "tenant-b"})
            (rf/reg-event-db :rf.helix-rcgsc/seed (fn [_ [_ n]] {:n n}))
            (rf/dispatch-sync [:rf.helix-rcgsc/seed 10] {:frame :rf.helix-rcgsc/tenant-a})
            (rf/dispatch-sync [:rf.helix-rcgsc/seed 100] {:frame :rf.helix-rcgsc/tenant-b})
            (rf/reg-sub :rf.helix-rcgsc/n (fn [db _] (:n db)))
            (let [mount-node (make-mount-node!)
                  root       (react-dom-client/createRoot mount-node)]
              (try
                (act-fn (fn []
                          (.render root
                            ($ :div
                              ($ Probe2ArgA)
                              ($ Probe2ArgB)))))
                (is (some #{10} @probe-2arg-a-observed)
                    "Probe2ArgA observed tenant-a's value (10) via explicit frame-pin")
                (is (some #{100} @probe-2arg-b-observed)
                    "Probe2ArgB observed tenant-b's value (100) via explicit frame-pin")
                (is (not (some #{100} @probe-2arg-a-observed))
                    "tenant-a probe did NOT leak tenant-b's value")
                (is (not (some #{10} @probe-2arg-b-observed))
                    "tenant-b probe did NOT leak tenant-a's value")
                (finally
                  (try (.unmount root) (catch :default _ nil)))))))))))

;; ---- stable structural-equality deps key (rf2-mwft2) ----------------------
;;
;; Pre-rf2-mwft2, `use-subscribe-2` passed the CLJS query-v vector
;; directly into useMemo / useCallback / useEffect deps arrays.
;; CLJS persistent vectors are =-equal across renders for the same
;; literal but produce a *fresh JS object* per render, so React's
;; Object.is deps comparison fired every render — useMemo re-ran
;; `subs/subscribe` (cache-hit ref-count churn), useEffect tore down
;; and rebuilt subscribe/unsubscribe pairs.
;;
;; With the fix the deps element is JS-ref-stable across renders
;; (useRef + = compare), so a stable-literal query-v causes exactly
;; one subs/subscribe call across N forced re-renders, and the
;; useEffect cleanup fires only on unmount. Parity with UIx's
;; `use-subscribe-stable-deps-key` (rf2-mwft2).

(deftest use-subscribe-stable-deps-key
  (testing "rf2-mwft2 (parity with UIx): stable-literal query-v across N
            re-renders ⇒ one subs/subscribe call"
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the assertions")
      (let [target :rf.helix-mwft2/probe-frame
            act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (do
            (enable-react-act-env!)
            (reset! mwft2-set-tick nil)
            (rf/reg-frame target {:doc "rf2-mwft2 Helix probe frame"})
            (rf/reg-event-db :rf.helix-mwft2/seed (fn [_ _] {:p 0}))
            (rf/dispatch-sync [:rf.helix-mwft2/seed] {:frame target})
            (rf/reg-sub :rf.helix-mwft2/p (fn [db _] (:p db)))
            (let [subscribe-calls   (atom 0)
                  unsubscribe-calls (atom 0)
                  real-subscribe    subs/subscribe
                  real-unsubscribe  subs/unsubscribe
                  cache-key-v       [:rf.helix-mwft2/p]
                  cache             (:sub-cache (frame/frame target))
                  mount-node        (make-mount-node!)
                  root              (react-dom-client/createRoot mount-node)]
              ;; Spies preserve the multi-arity shape of subs/subscribe
              ;; (`[query-v]` and `[frame-id query-v]`) so spine call
              ;; sites that bind the arity-2 invoke-slot resolve. A bare
              ;; `[& args]` variadic spy compiles only the variadic
              ;; slot and trips `…cljs$core$IFn$_invoke$arity$2 is not
              ;; a function` at the spine's `subs/subscribe` call.
              ;;
              ;; `unsubscribe`'s 1- and 2-arity bodies recur into the
              ;; 3-arity through the Var — so without bypassing, a single
              ;; logical unsubscribe would trip the spy twice (once on
              ;; entry, once on the recursive 3-arity tail). Each spy
              ;; arity therefore calls the 3-arity REAL directly,
              ;; resolving the canonical default-arg shape itself instead
              ;; of routing back through the Var.
              (with-redefs [subs/subscribe
                            (fn spy-subscribe
                              ([query-v]
                               (swap! subscribe-calls inc)
                               (real-subscribe (frame/resolve-current-frame) query-v))
                              ([frame-id query-v]
                               (swap! subscribe-calls inc)
                               (real-subscribe frame-id query-v)))
                            subs/unsubscribe
                            (fn spy-unsubscribe
                              ([query-v]
                               (swap! unsubscribe-calls inc)
                               (real-unsubscribe (frame/resolve-current-frame)
                                                 query-v nil))
                              ([frame-id query-v]
                               (swap! unsubscribe-calls inc)
                               (real-unsubscribe frame-id query-v nil))
                              ([frame-id query-v opts]
                               (swap! unsubscribe-calls inc)
                               (real-unsubscribe frame-id query-v opts)))]
                (try
                  ;; Mount the probe — one subs/subscribe for the
                  ;; useMemo factory.
                  (act-fn (fn [] (.render root ($ ProbeMwft2Parent))))
                  (let [mounted-subs @subscribe-calls]
                    (is (= 1 mounted-subs)
                        "mount triggered exactly one subs/subscribe call")
                    (is (zero? @unsubscribe-calls)
                        "no subs/unsubscribe fires during initial mount")
                    ;; Force five re-renders by bumping the parent's
                    ;; tick state. Each parent render also re-renders
                    ;; the child probe with a freshly-allocated CLJS
                    ;; vector for [:rf.helix-mwft2/p] — without the fix
                    ;; the deps mismatch would re-run useMemo (extra
                    ;; subs/subscribe) and useEffect (extra
                    ;; subs/unsubscribe) each render.
                    (dotimes [_ 5]
                      (act-fn (fn [] (when-let [set-tick @mwft2-set-tick]
                                       (set-tick inc)))))
                    (is (= 1 @subscribe-calls)
                        "subs/subscribe still called only once after 5 re-renders (no per-render churn)")
                    (is (zero? @unsubscribe-calls)
                        "subs/unsubscribe never fired across re-renders — useEffect cleanup is unmount-only")
                    (is (= 1 (or (get-in @cache [cache-key-v :ref-count]) 0))
                        "sub-cache ref-count remains pinned at 1 across re-renders"))
                  ;; Unmount must fire exactly one unsubscribe — the
                  ;; rf2-7g959 cleanup pairing must survive the
                  ;; rf2-mwft2 rewrite.
                  (act-fn (fn [] (.unmount root)))
                  (is (= 1 @unsubscribe-calls)
                      "unmount fired exactly one subs/unsubscribe (rf2-7g959 cleanup survives the rf2-mwft2 rewrite)")
                  (is (or (nil? (get @cache cache-key-v))
                          (zero? (or (get-in @cache [cache-key-v :ref-count]) 0)))
                      "post-unmount cache entry dropped or ref-count at zero")
                  (finally
                    (try (.unmount root) (catch :default _ nil))))))))))))

(ns re-frame2-pair-mcp.invoke-test
  "End-to-end orchestration test for `tools/invoke`.

  `invoke` is the single egress for every MCP `tools/call`. It glues
  four phases:

    0. `precheck/fetch-precheck-hash` — cheap-hash short-circuit.
       For precheck-eligible tools, fetches a runtime-side hash. On a
       match against the stored entry, returns the
       `:rf.mcp/cache-hit :via :precheck` marker WITHOUT running the
       tool body.
    1. `dispatch-tool*` — per-tool dispatch. Runs the actual tool
       implementation when the precheck didn't short-circuit.
    2. `cache/apply-cache` — post-eval result-hash cache.
       On a result-hash match returns the `:via :result-hash` marker.
       `isError` results bypass entirely.
    3. `cap/apply-cap` — wire-boundary token-budget enforcement.
       Over-budget responses become `:rf.mcp/overflow` markers.

  Each phase has unit-level coverage in `cache_test.cljs` /
  `wire_cap_test.cljs`. THIS suite pins the wire-up: that the phases
  are invoked in the right order, that each phase's output reaches
  the next phase intact, and that short-circuit semantics hold across
  the seams.

  ## Stubbing strategy

  `invoke` is async (returns a Promise). `cljs.core/with-redefs`
  restores its vars synchronously — by the time the Promise resolves
  inside `(async done ...)`, the redefs are gone. A `.finally`-scoped
  restore is also unsafe: it can outrun the test's `(done)` call, so
  the NEXT async test (in this or any later `-test` ns) could start
  while the stubs are still installed — a hard-to-reproduce flake on
  otherwise-unrelated PRs.

  Each test therefore does `(set-stubs! ...)` directly (no
  `.finally`), and a fixture's `:after` step unconditionally
  restores the pristine originals to ALL three vars. cljs.test's
  async-test contract guarantees `:after` fires synchronously after
  `(done)` and BEFORE the next test's `:before` — so cleanup is
  Promise-chain-independent and cross-test leakage is impossible.

  We redefine the public per-tool entry-points
  (`get-path/get-path-tool`, `snapshot/snapshot-tool`) and
  `precheck/fetch-precheck-hash` so each test controls exactly what
  each phase sees and emits. Real `cache`, real `cap`, real
  `tools/invoke`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [cljs.reader :as edn]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.precheck :as precheck]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.operating-frame :as operating-frame]
            [re-frame2-pair-mcp.tools.snapshot :as snapshot]))

;; ---------------------------------------------------------------------------
;; Fixtures — cache + stub restoration.
;;
;; The cache is module-level state; reset between tests so each case
;; starts from a clean slate.
;;
;; The three stubbed vars (`precheck/fetch-precheck-hash`,
;; `get-path/get-path-tool`, `snapshot/snapshot-tool`) are restored
;; UNCONDITIONALLY in `:after`. Originals are snapshot once at
;; ns-load. Cleanup is fixture-scoped, not Promise-chain-scoped —
;; which closes the cross-test stub-leakage race.
;; ---------------------------------------------------------------------------

(def ^:private orig-fetch-precheck-hash precheck/fetch-precheck-hash)
(def ^:private orig-precheck-target     precheck/precheck-target)
(def ^:private orig-get-path-tool       get-path/get-path-tool)
(def ^:private orig-snapshot-tool       snapshot/snapshot-tool)
(def ^:private orig-reset-operating-frame-tool operating-frame/reset-operating-frame-tool)
(def ^:private orig-cljs-eval-value     nrepl/cljs-eval-value)

(defn- restore-stubs! []
  (set! precheck/fetch-precheck-hash orig-fetch-precheck-hash)
  (set! precheck/precheck-target     orig-precheck-target)
  (set! get-path/get-path-tool       orig-get-path-tool)
  (set! snapshot/snapshot-tool       orig-snapshot-tool)
  (set! operating-frame/reset-operating-frame-tool orig-reset-operating-frame-tool)
  (set! nrepl/cljs-eval-value        orig-cljs-eval-value))

(use-fixtures :each
  {:before (fn [] (cache/clear!))
   :after  (fn [] (restore-stubs!) (cache/clear!))})

;; ---------------------------------------------------------------------------
;; Helpers — MCP shape construction + extraction.
;; ---------------------------------------------------------------------------

(defn- mcp-result [text & {:keys [error?]}]
  (cond-> #js {:content #js [#js {:type "text" :text text}]}
    error? (j/assoc! :isError true)))

(defn- args-js [m]
  (let [o #js {}]
    (doseq [[k v] m]
      (j/assoc! o (name k) v))
    o))

(defn- extract-text [result]
  (let [content (j/get result :content)
        item    (when (array? content) (aget content 0))]
    (when item (j/get item :text))))

(defn- extract-edn [result]
  (some-> (extract-text result) edn/read-string))

(defn- cache-hit? [result]
  (let [v (extract-edn result)]
    (and (map? v) (contains? v :rf.mcp/cache-hit))))

(defn- overflow? [result]
  (let [v (extract-edn result)]
    (and (map? v) (contains? v :rf.mcp/overflow))))

(defn- invalid-arg? [result]
  (let [v (extract-edn result)]
    (and (map? v) (contains? v :rf.mcp/invalid-arg))))

;; ---------------------------------------------------------------------------
;; set-stubs! — install one or more namespace-slot stubs.
;;
;; Direct `set!` only — no `.finally` cleanup. Cleanup is the
;; `:after` fixture's job, which restores the pristine originals
;; (captured at ns-load) synchronously after `(done)` and before
;; the next test runs. The fixture-scoped lifetime is what makes
;; this Promise-chain-independent.
;;
;; `kind` is the keyword the caller picked to identify which slot
;; to stub — `:fetch-precheck-hash`, `:precheck-target`,
;; `:get-path-tool`, `:snapshot-tool`. Anything else is a programmer
;; error and we throw immediately rather than silently no-op.
;;
;; `:precheck-target` exists because, post rf2-ajhwbm, NO real tool
;; registers a non-nil precheck target (both `snapshot` and `get-path`
;; are precheck-ineligible — see `precheck.cljs`). The phase-1
;; pipeline-mechanics tests below (precheck hit ⇒ dispatch skipped)
;; are deliberately tool-agnostic; stubbing `precheck-target` directly
;; is how they manufacture an eligible target without depending on a
;; real (and, pre-fix, buggy) eligibility path.
;; ---------------------------------------------------------------------------

(defn- set-stubs! [stubs]
  (doseq [[kind stub] stubs]
    (case kind
      :fetch-precheck-hash (set! precheck/fetch-precheck-hash stub)
      :precheck-target     (set! precheck/precheck-target     stub)
      :get-path-tool       (set! get-path/get-path-tool       stub)
      :snapshot-tool       (set! snapshot/snapshot-tool       stub)
      :reset-operating-frame-tool (set! operating-frame/reset-operating-frame-tool stub)
      (throw (ex-info (str "Unknown stub kind: " kind)
                      {:kind kind :known #{:fetch-precheck-hash
                                           :precheck-target
                                           :get-path-tool
                                           :snapshot-tool
                                           :reset-operating-frame-tool}})))))

;; ---------------------------------------------------------------------------
;; Phase-1 short-circuit: precheck hit ⇒ dispatch SKIPPED.
;;
;; Prime the cache with a precheck-hash. Stub `fetch-precheck-hash` to
;; return the matching value. Stub the tool body so we can detect any
;; (forbidden) call into it. `invoke` must emit the marker WITHOUT
;; running the tool.
;; ---------------------------------------------------------------------------

(deftest precheck-hit-short-circuits-dispatch
  (async done
    ;; No real tool registers a precheck target post-rf2-ajhwbm
    ;; (`snapshot` and `get-path` are BOTH precheck-ineligible — see
    ;; `precheck.cljs`). The pipeline mechanics under test (precheck
    ;; hit ⇒ dispatch skipped) are tool-agnostic, so we stub
    ;; `precheck-target` directly to manufacture an eligible target for
    ;; whichever tool name `invoke` dispatches — `precheck-step` then
    ;; issues the stubbed fetch exactly as it would for a genuinely
    ;; eligible tool.
    (let [args        (args-js {:cache "true" :frames #js ["rf/default"]})
          dispatched? (atom false)
          ;; The cache key includes the resolved build, so the priming
          ;; MUST use the same build the `invoke` pipeline derives
          ;; (`arg-build nil args` = the env / `:app` default here) or
          ;; the lookup key won't match.
          _ (cache/apply-cache (mcp-result "{:app-db {:k :v}}")
                               {:tool "snapshot"
                                :args args
                                :enabled? true
                                :build (wire/arg-build nil args)
                                :precheck-hash 42})]
      (set-stubs!
        {:precheck-target     (fn [_tool _args] [:explicit :rf/default])
         :fetch-precheck-hash (fn [_conn _args _frame] (js/Promise.resolve 42))
         :snapshot-tool       (fn [_conn _args]
                                (reset! dispatched? true)
                                (js/Promise.resolve (mcp-result "{:should-not-ship :true}")))})
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [result]
                   (is (false? @dispatched?)
                       "precheck hit MUST skip the tool body entirely")
                   (is (cache-hit? result)
                       "result is the :rf.mcp/cache-hit marker")
                   (is (= :precheck
                          (get-in (extract-edn result)
                                  [:rf.mcp/cache-hit :via]))
                       "marker carries :via :precheck — the rf2-36xod path")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Phase-1 miss: precheck-hash feeds through to apply-cache so the NEXT
;; call can short-circuit via the precheck path.
;;
;; Cold cache. Stub `fetch-precheck-hash` to return 999. First invoke:
;; dispatch runs, apply-cache stores entry with :precheck-hash 999.
;; Second invoke with the same fetched hash: precheck path now hits.
;; ---------------------------------------------------------------------------

(deftest precheck-miss-stores-hash-for-next-call
  (async done
    ;; No real tool is precheck-eligible post-rf2-ajhwbm — stub
    ;; `precheck-target` directly (see `precheck-hit-short-circuits-
    ;; dispatch` above) so the mechanics (cold miss stores the
    ;; precheck-hash; the next call short-circuits) stay covered
    ;; tool-agnostically.
    (let [args       (args-js {:cache "true" :frames #js ["rf/default"]})
          call-count (atom 0)]
      (set-stubs!
        {:precheck-target     (fn [_tool _args] [:explicit :rf/default])
         :fetch-precheck-hash (fn [_conn _args _frame] (js/Promise.resolve 999))
         :snapshot-tool       (fn [_conn _args]
                                (swap! call-count inc)
                                (js/Promise.resolve (mcp-result "{:app-db {:k :v}}")))})
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [first-result]
                   (is (= 1 @call-count)
                       "first call: precheck cold miss → dispatch runs")
                   (is (not (cache-hit? first-result))
                       "first call returns the fresh result, not a marker")
                   (tools/invoke nil "snapshot" args nil)))
          (.then (fn [second-result]
                   (is (= 1 @call-count)
                       "second call: precheck matched → dispatch SKIPPED")
                   (is (cache-hit? second-result))
                   (is (= :precheck
                          (get-in (extract-edn second-result)
                                  [:rf.mcp/cache-hit :via])))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Phase-3 result-hash hit (post-eval). Tools that DON'T have precheck
;; wiring (or whose precheck returns nil) fall through to the post-eval
;; result-hash path: dispatch runs both times, the second result's text
;; hash matches the first, marker replaces it.
;;
;; `snapshot` with the default :all `frames` is precheck-ineligible —
;; the precheck-target returns nil and the precheck round-trip is
;; skipped. Perfect probe for the post-eval cache slot.
;; ---------------------------------------------------------------------------

(deftest result-hash-hit-when-precheck-ineligible
  (async done
    (let [args       (args-js {:cache "true"})
          call-count (atom 0)
          payload    "{:db {:k :v}}"]
      (set-stubs!
        {:snapshot-tool (fn [_conn _args]
                          (swap! call-count inc)
                          (js/Promise.resolve (mcp-result payload)))})
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [_first]
                   (is (= 1 @call-count)
                       "snapshot :all is precheck-ineligible — first dispatch runs")
                   (tools/invoke nil "snapshot" args nil)))
          (.then (fn [second-result]
                   (is (= 2 @call-count)
                       "second dispatch ALSO runs — no precheck path for :all snapshot")
                   (is (cache-hit? second-result)
                       "but the post-eval cache catches the identical text payload")
                   (is (= :result-hash
                          (get-in (extract-edn second-result)
                                  [:rf.mcp/cache-hit :via])))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Phase-3 :isError passes through cache untouched. Errors must never
;; become cache-hit markers — that would mask a transient failure as
;; if the cached prior success were still valid.
;; ---------------------------------------------------------------------------

(deftest is-error-bypasses-cache
  (async done
    (let [args     (args-js {:cache "true"})
          err-text "{:ok? false :reason :nrepl-down}"]
      (set-stubs!
        {:snapshot-tool (fn [_conn _args]
                          (js/Promise.resolve (mcp-result err-text :error? true)))})
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [first-result]
                   (is (true? (j/get first-result :isError))
                       "first error result passes through untouched")
                   (is (= err-text (extract-text first-result)))
                   (is (zero? (cache/size))
                       "errors do not poison the cache")
                   (tools/invoke nil "snapshot" args nil)))
          (.then (fn [second-result]
                   (is (true? (j/get second-result :isError))
                       "second error result is ALSO untouched — not a cache-hit marker")
                   (is (not (cache-hit? second-result)))
                   (is (zero? (cache/size)))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Phase-4 overflow on an isError result. The cap walk runs AFTER the
;; cache bypass for errors. An oversized error response still becomes
;; an `:rf.mcp/overflow` marker — silent truncation is unacceptable
;; whether the underlying result was success or failure.
;; ---------------------------------------------------------------------------

(deftest is-error-still-subject-to-cap
  (async done
    (let [args (args-js {"max-tokens" 100})
          big  (apply str (repeat 8000 "x"))]
      (set-stubs!
        {:snapshot-tool (fn [_conn _args]
                          (js/Promise.resolve
                            (mcp-result (pr-str {:huge big}) :error? true)))})
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [result]
                   (is (overflow? result)
                       "oversized error STILL becomes overflow marker")
                   (let [marker (-> (extract-edn result) :rf.mcp/overflow)]
                     (is (= "snapshot" (:tool marker)))
                     (is (> (:token-count marker) 100)))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Negative :max-tokens is REJECTED at the invoke chokepoint, BEFORE the
;; pipeline runs. The tool body is never dispatched and the result is an
;; actionable `{:rf.mcp/invalid-arg ...}` error — NOT an
;; `:rf.mcp/overflow` lock-out (over-cap? trips on any non-negative token
;; count against a negative ceiling, so a tiny response would otherwise
;; be replaced by the overflow marker).
;; ---------------------------------------------------------------------------

(deftest negative-max-tokens-rejected-not-overflow-lockout
  (async done
    (let [args        (args-js {"max-tokens" -1})
          dispatched? (atom false)]
      (set-stubs!
        {:snapshot-tool (fn [_conn _args]
                          (reset! dispatched? true)
                          ;; A tiny payload — under any sane positive cap.
                          ;; A negative cap rejects before dispatch, so
                          ;; the tool body never runs.
                          (js/Promise.resolve (mcp-result (pr-str {:ok? true}))))})
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [result]
                   (is (true? (j/get result :isError))
                       "negative max-tokens surfaces as an isError result")
                   (is (invalid-arg? result)
                       "result carries the :rf.mcp/invalid-arg rejection")
                   (is (not (overflow? result))
                       "NOT the overflow lock-out a negative cap used to cause")
                   (is (false? @dispatched?)
                       "the tool body is never dispatched — rejection short-circuits invoke")
                   (let [body (-> (extract-edn result) :rf.mcp/invalid-arg)]
                     (is (= :max-tokens (:arg body)))
                     (is (= -1 (:value body)))
                     (is (re-find #"(?i)0 disables" (:hint body))))
                   (is (zero? (cache/size))
                       "rejected calls do not touch the cache")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Phase ordering: cache hit BEFORE cap. The hit marker is sub-100
;; bytes; even with an absurdly tight cap the marker survives because
;; the cap walk sees the small marker, not the original oversized
;; payload. Flipping the order would force the cap to walk the full
;; (potentially massive) original payload before the cache could
;; replace it.
;; ---------------------------------------------------------------------------

(deftest cache-hit-bypasses-cap-walk
  (async done
    ;; Cap = 200 tokens: the original payload (~2000 tokens) overflows,
    ;; but the cache-hit marker (~68 tokens) survives. With the order
    ;; reversed (cap before cache), the first hit would have been
    ;; cap'd to overflow BEFORE the cache could see the original hash
    ;; — and the cache slot would store the OVERFLOW marker's hash
    ;; instead, so the second call's "same text" would compare
    ;; against an overflow marker hash, never matching the underlying
    ;; payload.
    (let [args (args-js {:cache "true" "max-tokens" 200})
          big  (apply str (repeat 8000 "x"))]
      (set-stubs!
        {:snapshot-tool (fn [_conn _args]
                          (js/Promise.resolve (mcp-result (pr-str {:huge big}))))})
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [first-result]
                   (is (overflow? first-result)
                       "first call: big payload → overflow marker")
                   (tools/invoke nil "snapshot" args nil)))
          (.then (fn [second-result]
                   (is (cache-hit? second-result)
                       "second call: same text → cache-hit marker, not overflow")
                   (is (= :result-hash
                          (get-in (extract-edn second-result)
                                  [:rf.mcp/cache-hit :via]))
                       "marker is the post-eval result-hash path")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Cache-disabled path: phase 0 + phase 2 are both no-ops. Dispatch
;; runs every call; cap still enforces.
;; ---------------------------------------------------------------------------

(deftest cache-disabled-passes-through-all-phases
  (async done
    (let [args        (args-js {})
          call-count  (atom 0)
          fetch-count (atom 0)]
      (set-stubs!
        {:fetch-precheck-hash (fn [_conn _args _frame]
                                (swap! fetch-count inc)
                                (js/Promise.resolve 12345))
         :get-path-tool       (fn [_conn _args]
                                (swap! call-count inc)
                                (js/Promise.resolve (mcp-result "{:db {:k :v}}")))})
      (-> (tools/invoke nil "get-path" args nil)
          (.then (fn [_] (tools/invoke nil "get-path" args nil)))
          (.then (fn [_]
                   (is (= 2 @call-count)
                       "cache disabled → every call dispatches")
                   (is (zero? @fetch-count)
                       "cache disabled → precheck NEVER consulted (no wasted round-trip)")
                   (is (zero? (cache/size))
                       "no entries stored")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Unknown tool: dispatch-tool* returns an error envelope; the
;; pipeline carries it through cache (bypass on isError) and cap
;; (small payload, under any cap). The cap walk MUST tolerate the
;; isError shape — that's part of phase 4's contract.
;; ---------------------------------------------------------------------------

(deftest unknown-tool-flows-through-pipeline-as-error
  (async done
    (-> (tools/invoke nil "no-such-tool" (args-js {}) nil)
        (.then (fn [result]
                 (is (true? (j/get result :isError)))
                 (let [v (extract-edn result)]
                   ;; The base error shape.
                   (is (false? (:ok? v)))
                   (is (= :unknown-tool (:reason v)))
                   (is (= "no-such-tool" (:tool v)))
                   ;; Recovery affordances: a :hint pointing at tools/list
                   ;; and the live catalogue, matching the surface's
                   ;; honest-error standard.
                   (is (string? (:hint v)))
                   (is (re-find #"tools/list" (:hint v))
                       "hint must point the agent at tools/list to recover")
                   (is (vector? (:available-tools v)))
                   (is (some #{"snapshot"} (:available-tools v))
                       ":available-tools carries the real catalogue"))
                 (is (zero? (cache/size))
                     "unknown-tool errors do not touch the cache")
                 (done))))))

(deftest unknown-tool-hint-offers-nearest-match
  ;; A near-miss typo of a real tool name surfaces a :did-you-mean
  ;; pointer so a model can self-correct without a round-trip.
  (async done
    (-> (tools/invoke nil "snapsho" (args-js {}) nil)
        (.then (fn [result]
                 (let [v (extract-edn result)]
                   (is (= :unknown-tool (:reason v)))
                   (is (= "snapshot" (:did-you-mean v))
                       "near typo `snapsho` suggests `snapshot`")
                   (is (re-find #"did you mean" (:hint v))
                       "hint inlines the nearest-match suggestion"))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Snapshot precheck eligibility — NONE, for every `:include` shape
;; (rf2-ajhwbm).
;;
;; The precheck hash is `(hash app-db@frame)` only. `:machines`/
;; `:sub-cache`/`:epochs`/`:traces` can all move WITHOUT an app-db
;; write, so an `:include` retaining any of them is unsound (unchanged).
;; `:app-db` LOOKED sound (it IS the frame db) but is not: the resolved
;; `:app-db` slice is walked through `re-frame.core/elide-wire-value`
;; before it crosses the wire (`snapshot.cljs` `slice-walk-src`), and the
;; elision registry that walk consults lives in the runtime-db partition,
;; not app-db — a later elision-declaration change re-shapes the egress
;; of an unchanged app-db subtree, invisible to the app-db precheck hash.
;; So EVERY snapshot `:include`, including the narrowest `{:app-db}`,
;; falls back to the post-eval result-hash cache, which hashes the full
;; text and so recomputes whenever the egress shape changes.
;; ---------------------------------------------------------------------------

;; The MCP wire passes `:frames` / `:include` as JS arrays of strings,
;; not EDN strings — `parse-frames-arg` / `parse-include-arg` coerce
;; arrays/sequentials. Build args with JS arrays to match the live shape.
(deftest precheck-target-snapshot-eligibility-by-include
  (testing "single-frame snapshot, app-db-only include → ineligible (rf2-ajhwbm)"
    (is (nil? (precheck/precheck-target
                "snapshot" (args-js {:frames #js ["rf/default"] :include #js ["app-db"]})))
        ;; :app-db egresses through elide-wire-value, whose elision
        ;; registry lives in runtime-db — the SAME hazard get-path was
        ;; excluded for (rf2-ww877w). No longer treated as sound.
        ":include [:app-db] still egresses through elide-wire-value ⇒ ineligible")
    (is (nil? (precheck/precheck-target
                "snapshot" (args-js {:frames #js ["rf/default"]
                                     :include #js ["app-db" "machines"]})))
        ;; :machines is RUNTIME-DB state (EP-0001 partitions machine
        ;; snapshots into the runtime-db). A machine transition rewrites
        ;; the slice WITHOUT an app-db write, so the `(hash app-db)`
        ;; precheck hash can't see it move ⇒ ineligible.
        ":machines reads runtime-db — NOT app-db-derived ⇒ ineligible"))
  (testing "single-frame snapshot, default (all-five) include → ineligible"
    (is (nil? (precheck/precheck-target
                "snapshot" (args-js {:frames #js ["rf/default"]})))
        "default include carries :epochs/:traces/:sub-cache → ineligible"))
  (testing "single-frame snapshot retaining any slice → ineligible"
    (doseq [slice [#js ["app-db" "epochs"]
                   #js ["app-db" "traces"]
                   #js ["app-db" "sub-cache"]
                   ;; :machines is runtime-db-backed, so an :include
                   ;; retaining it is precheck-ineligible too.
                   #js ["app-db" "machines"]
                   #js ["machines"]]]
      (is (nil? (precheck/precheck-target
                  "snapshot" (args-js {:frames #js ["rf/default"] :include slice})))
          (str "include " (vec slice) " — ineligible"))))
  (testing "multi-frame snapshot stays ineligible regardless of include"
    (is (nil? (precheck/precheck-target
                "snapshot" (args-js {:frames #js ["a" "b"] :include #js ["app-db"]}))))))

;; ---------------------------------------------------------------------------
;; get-path is NOT precheck-eligible. Its app-db subtree read is
;; post-processed by `elide-wire-value`, whose elision registry lives in
;; runtime-db; an elision declaration / classification flip can re-shape
;; the egress of an UNCHANGED app-db subtree, which the `(hash app-db)`
;; precheck hash can't observe. So get-path falls through to the post-eval
;; result-hash cache (which hashes the actual post-elision text).
;; ---------------------------------------------------------------------------

(deftest precheck-target-get-path-is-ineligible
  (testing "get-path with an explicit frame → ineligible (rf2-ww877w)"
    (is (nil? (precheck/precheck-target
                "get-path" (args-js {:frame "rf/default" :path "[:k]"})))
        "explicit-frame get-path no longer registers a precheck target"))
  (testing "get-path with no frame (operating-frame-resolved) → ineligible"
    (is (nil? (precheck/precheck-target
                "get-path" (args-js {:path "[:k]"})))
        "operating-frame get-path no longer registers a precheck target"))
  (testing "get-path batch read → ineligible"
    (is (nil? (precheck/precheck-target
                "get-path" (args-js {:paths "[[:a] [:b]]"})))
        "batch get-path is ineligible too — same elision-registry hazard")))

;; ---------------------------------------------------------------------------
;; END-TO-END staleness guard. A single-frame DEFAULT-include snapshot is
;; the hazard shape: a precheck would serve a hit while :epochs/:traces
;; moved. It is precheck-ineligible, so the second call re-dispatches
;; (and any change in the result text is caught by the post-eval cache
;; instead of being silently hidden).
;; ---------------------------------------------------------------------------

(deftest single-frame-default-snapshot-does-not-precheck-hit
  (async done
    (let [args        (args-js {:cache "true" :frames #js ["rf/default"]})
          fetch-count (atom 0)
          call-count  (atom 0)]
      (set-stubs!
        {;; If precheck WERE consulted it would match and serve a stale
         ;; hit — so this stub must never fire for the default snapshot.
         :fetch-precheck-hash (fn [_conn _args _target]
                                (swap! fetch-count inc)
                                (js/Promise.resolve 7))
         :snapshot-tool       (fn [_conn _args]
                                (swap! call-count inc)
                                ;; Distinct text per call models :epochs/
                                ;; :traces accruing while app-db is fixed.
                                (js/Promise.resolve
                                  (mcp-result (str "{:epochs " @call-count "}"))))})
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [_first] (tools/invoke nil "snapshot" args nil)))
          (.then (fn [second-result]
                   (is (zero? @fetch-count)
                       "precheck NEVER consulted — default include is ineligible (rf2-3ljsa)")
                   (is (= 2 @call-count)
                       "second call re-dispatches — no stale precheck hit")
                   (is (not (cache-hit? second-result))
                       "differing :epochs text ⇒ fresh result, not a hit")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; END-TO-END staleness guard (rf2-ajhwbm). A single-frame APP-DB-ONLY
;; snapshot used to be treated as precheck-sound — `(hash app-db)`
;; unchanged ⇒ serve the prior payload without re-dispatching. But the
;; `:app-db` slice egresses through `elide-wire-value`, whose elision
;; registry lives in runtime-db, NOT app-db. An elision declaration can
;; change mid-session while app-db itself never moves — under the old
;; rule the precheck hash would still match and a STALE, differently-
;; redacted payload would be re-served as a "cache-hit". This test
;; models exactly that: app-db is genuinely unchanged across both
;; calls (the precheck hash, if consulted, would match), but the
;; tool's egressed text differs (standing in for the elision flip).
;; The regression guard: precheck must never be consulted for
;; snapshot, so the differing text is served FRESH via the post-eval
;; result-hash cache, never as a bogus precheck hit.
;; ---------------------------------------------------------------------------

(deftest single-frame-appdb-only-snapshot-no-longer-precheck-hits
  (async done
    (let [args        (args-js {:cache "true" :frames #js ["rf/default"] :include #js ["app-db"]})
          fetch-count (atom 0)
          call-count  (atom 0)]
      (set-stubs!
        {;; If precheck WERE consulted it would match (app-db is
         ;; unchanged across the two calls) and serve a stale hit — so
         ;; this stub must never fire.
         :fetch-precheck-hash (fn [_conn _args _target]
                                (swap! fetch-count inc)
                                (js/Promise.resolve 1234))
         :snapshot-tool       (fn [_conn _args]
                                (swap! call-count inc)
                                ;; Egressed text differs between calls —
                                ;; standing in for an elision-declaration
                                ;; flip re-shaping the SAME app-db
                                ;; subtree's post-elision wire form.
                                (js/Promise.resolve
                                  (mcp-result (str "{:app-db {:k :v} :call " @call-count "}"))))})
      (-> (tools/invoke nil "snapshot" args nil)
          (.then (fn [_first] (tools/invoke nil "snapshot" args nil)))
          (.then (fn [second-result]
                   (is (zero? @fetch-count)
                       "precheck NEVER consulted — no snapshot :include is precheck-eligible (rf2-ajhwbm)")
                   (is (= 2 @call-count)
                       "second call re-dispatches — no stale precheck hit")
                   (is (not (cache-hit? second-result))
                       "differing post-elision text ⇒ fresh result, never served as a stale hit")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; An operating-frame change FLUSHES the response cache. The cache key
;; is `(tool, build, args)` and CANNOT include the
;; resolved operating frame for an omitted-`:frame` call (it resolves
;; runtime-side). So a `get-path {path X}` cached against operating frame
;; A would be served against frame B after a frame switch if B shared A's
;; app-db hash (multi-frame identical-initial-db). Clearing the whole
;; cache on every operating-frame mutation closes that hole.
;; ---------------------------------------------------------------------------

(deftest operating-frame-change-flushes-cache
  (async done
    ;; Prime the cache with a no-`:frame` get-path entry (resolved
    ;; against "operating frame A"). The entry exists.
    (let [gp-args (args-js {:cache "true" :path "[:k]"})]
      (cache/apply-cache (mcp-result "{:k :frame-a}")
                         {:tool "get-path"
                          :args gp-args
                          :enabled? true
                          :build (wire/arg-build nil gp-args)
                          :precheck-hash 42})
      (is (= 1 (cache/size)) "cache primed with the frame-A get-path entry")
      ;; A successful reset-operating-frame must flush the cache.
      (set-stubs!
        {:reset-operating-frame-tool
         (fn [_conn _args]
           (js/Promise.resolve (mcp-result "{:ok? true :selected nil :operating :rf/b}")))})
      (-> (tools/invoke nil "reset-operating-frame" (args-js {}) nil)
          (.then (fn [result]
                   (is (not (j/get result :isError))
                       "reset-operating-frame succeeded")
                   (is (zero? (cache/size))
                       (str "the operating-frame change flushed the whole response "
                            "cache — no stale frame-A payload can be served to frame B"))
                   (done)))))))

(deftest operating-frame-error-does-not-flush-cache
  (async done
    ;; A FAILED set-operating-frame (unknown frame) did NOT change the pin,
    ;; so the cache must be preserved — only a successful mutation flushes.
    (let [gp-args (args-js {:cache "true" :path "[:k]"})]
      (cache/apply-cache (mcp-result "{:k :frame-a}")
                         {:tool "get-path"
                          :args gp-args
                          :enabled? true
                          :build (wire/arg-build nil gp-args)
                          :precheck-hash 42})
      (set-stubs!
        {:reset-operating-frame-tool
         (fn [_conn _args]
           (js/Promise.resolve (mcp-result "{:ok? false :reason :boom}" :error? true)))})
      (-> (tools/invoke nil "reset-operating-frame" (args-js {}) nil)
          (.then (fn [result]
                   (is (true? (j/get result :isError))
                       "the mutation errored")
                   (is (= 1 (cache/size))
                       "an errored operating-frame call leaves the cache intact")
                   (done)))))))

;; The REAL set-operating-frame :no-such-frame path. This runs the actual
;; `set-operating-frame-tool` against a stubbed nREPL that answers
;; :no-such-frame, then drives THAT real result through `invoke`'s
;; chokepoint. It proves the failure rides as `isError` (no silent
;; ok-text) AND that the chokepoint's `(not (isError? result))` flush
;; guard therefore preserves the cache — the pin did NOT change, so a
;; no-`:frame` cached read stays valid.
(defn- probed-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(deftest real-set-operating-frame-no-such-frame-is-error-and-preserves-cache
  (async done
    (let [conn    (probed-conn)
          gp-args (args-js {:cache "true" :path "[:k]"})]
      (cache/apply-cache (mcp-result "{:k :frame-a}")
                         {:tool "get-path"
                          :args gp-args
                          :enabled? true
                          :build (wire/arg-build conn gp-args)
                          :precheck-hash 42})
      (is (= 1 (cache/size)) "cache primed")
      ;; Stub nREPL so the REAL set-form runs and the runtime answers
      ;; :no-such-frame (the requested frame isn't registered). The conn is
      ;; pre-probed so ensure-runtime! short-circuits; the validate-then-pin
      ;; form resolves to the failure envelope.
      (set! nrepl/cljs-eval-value
            (fn
              ([_c _b _form]
               (js/Promise.resolve {:ok? false :reason :no-such-frame
                                    :frame :nope
                                    :frames [:rf/default :stories]}))
              ([_c _b _form _o]
               (js/Promise.resolve {:ok? false :reason :no-such-frame
                                    :frame :nope
                                    :frames [:rf/default :stories]}))))
      ;; Run the REAL tool to capture its actual result shape, then have
      ;; `invoke` dispatch that exact result through the chokepoint flush
      ;; guard. (Stubbing the entry-point with the real result keeps the
      ;; flush decision under test while sidestepping the pipeline's
      ;; build-resolution round-trips.)
      (-> (operating-frame/set-operating-frame-tool
            conn (args-js {:frame ":nope"}))
          (.then (fn [real-result]
                   (is (true? (j/get real-result :isError))
                       "a real :no-such-frame set-operating-frame rides as isError")
                   (set-stubs!
                     {:reset-operating-frame-tool
                      (fn [_conn _args] (js/Promise.resolve real-result))})
                   ;; reset-operating-frame is also operating-frame-mutating;
                   ;; feeding it the real isError result exercises the SAME
                   ;; `(not (isError? result))` chokepoint guard.
                   (tools/invoke conn "reset-operating-frame" (args-js {}) nil)))
          (.then (fn [result]
                   (is (true? (j/get result :isError))
                       "the chokepoint sees the failed mutation as isError")
                   (is (= 1 (cache/size))
                       "the failed pin did not change the operating frame, so the cache is preserved")
                   (done)))))))

(ns re-frame-pair2-mcp.invoke-test
  "End-to-end orchestration test for `tools/invoke` (rf2-nogok).

  `invoke` is the single egress for every MCP `tools/call`. It glues
  four phases:

    0. `precheck/fetch-precheck-hash` — cheap-hash short-circuit
       (rf2-36xod). For precheck-eligible tools, fetches a runtime-
       side hash. On a match against the stored entry, returns the
       `:rf.mcp/cache-hit :via :precheck` marker WITHOUT running the
       tool body.
    1. `dispatch-tool*` — per-tool dispatch. Runs the actual tool
       implementation when the precheck didn't short-circuit.
    2. `cache/apply-cache` — post-eval result-hash cache (rf2-3rt1f).
       On a result-hash match returns the `:via :result-hash` marker.
       `isError` results bypass entirely.
    3. `cap/apply-cap` — wire-boundary token-budget enforcement
       (rf2-rvyzy). Over-budget responses become
       `:rf.mcp/overflow` markers.

  Each phase has unit-level coverage in `cache_test.cljs` /
  `wire_cap_test.cljs`. THIS suite pins the wire-up: that the phases
  are invoked in the right order, that each phase's output reaches
  the next phase intact, and that short-circuit semantics hold across
  the seams.

  ## Stubbing strategy

  `invoke` is async (returns a Promise). `cljs.core/with-redefs`
  restores its vars synchronously — by the time the Promise resolves
  inside `(async done ...)`, the redefs are gone. An earlier shape
  stubbed via direct `set!` and restored in a `.finally`; the
  `.finally` could outrun the test's `(done)` call, so the NEXT
  async test (in this or any later `-test` ns) could start while
  the stubs were still installed — a hard-to-reproduce flake
  (rf2-wb06a) that fired on otherwise-unrelated PRs.

  Current shape: each test does `(set-stubs! ...)` directly (no
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
            [re-frame-pair2-mcp.cache :as cache]
            [re-frame-pair2-mcp.tools :as tools]
            [re-frame-pair2-mcp.tools.precheck :as precheck]
            [re-frame-pair2-mcp.tools.get-path :as get-path]
            [re-frame-pair2-mcp.tools.snapshot :as snapshot]))

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
;; which closes the rf2-wb06a race.
;; ---------------------------------------------------------------------------

(def ^:private orig-fetch-precheck-hash precheck/fetch-precheck-hash)
(def ^:private orig-get-path-tool       get-path/get-path-tool)
(def ^:private orig-snapshot-tool       snapshot/snapshot-tool)

(defn- restore-stubs! []
  (set! precheck/fetch-precheck-hash orig-fetch-precheck-hash)
  (set! get-path/get-path-tool       orig-get-path-tool)
  (set! snapshot/snapshot-tool       orig-snapshot-tool))

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

;; ---------------------------------------------------------------------------
;; set-stubs! — install one or more namespace-slot stubs.
;;
;; Direct `set!` only — no `.finally` cleanup. Cleanup is the
;; `:after` fixture's job, which restores the pristine originals
;; (captured at ns-load) synchronously after `(done)` and before
;; the next test runs. The fixture-scoped lifetime is what makes
;; this Promise-chain-independent (rf2-wb06a fix).
;;
;; `kind` is the keyword the caller picked to identify which slot
;; to stub — `:fetch-precheck-hash`, `:get-path-tool`,
;; `:snapshot-tool`. Anything else is a programmer error and we
;; throw immediately rather than silently no-op.
;; ---------------------------------------------------------------------------

(defn- set-stubs! [stubs]
  (doseq [[kind stub] stubs]
    (case kind
      :fetch-precheck-hash (set! precheck/fetch-precheck-hash stub)
      :get-path-tool       (set! get-path/get-path-tool       stub)
      :snapshot-tool       (set! snapshot/snapshot-tool       stub)
      (throw (ex-info (str "Unknown stub kind: " kind)
                      {:kind kind :known #{:fetch-precheck-hash
                                           :get-path-tool
                                           :snapshot-tool}})))))

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
    (let [args        (args-js {:cache "true" :path "[:k]"})
          dispatched? (atom false)
          _ (cache/apply-cache (mcp-result "{:k :v}")
                               {:tool "get-path"
                                :args args
                                :enabled? true
                                :precheck-hash 42})]
      (set-stubs!
        {:fetch-precheck-hash (fn [_conn _args _frame] (js/Promise.resolve 42))
         :get-path-tool       (fn [_conn _args]
                                (reset! dispatched? true)
                                (js/Promise.resolve (mcp-result "{:should-not-ship :true}")))})
      (-> (tools/invoke nil "get-path" args nil)
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
    (let [args       (args-js {:cache "true" :path "[:k]"})
          call-count (atom 0)]
      (set-stubs!
        {:fetch-precheck-hash (fn [_conn _args _frame] (js/Promise.resolve 999))
         :get-path-tool       (fn [_conn _args]
                                (swap! call-count inc)
                                (js/Promise.resolve (mcp-result "{:db {:k :v}}")))})
      (-> (tools/invoke nil "get-path" args nil)
          (.then (fn [first-result]
                   (is (= 1 @call-count)
                       "first call: precheck cold miss → dispatch runs")
                   (is (not (cache-hit? first-result))
                       "first call returns the fresh result, not a marker")
                   (tools/invoke nil "get-path" args nil)))
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
;; wiring (or whose precheck returns nil) fall through to the legacy
;; rf2-3rt1f path: dispatch runs both times, the second result's text
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
                   (is (= :unknown-tool (:reason v)))
                   (is (= "no-such-tool" (:tool v))))
                 (is (zero? (cache/size))
                     "unknown-tool errors do not touch the cache")
                 (done))))))

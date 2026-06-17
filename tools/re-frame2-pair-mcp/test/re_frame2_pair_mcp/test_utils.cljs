(ns re-frame2-pair-mcp.test-utils
  "Shared test helpers (rf2-ambfv, rf2-wnrpi).

  Home for fns that only the test corpus uses but that conceptually
  sit alongside the production code.

  ## Wire-envelope extractors (rf2-wnrpi)

  The MCP tools return a `#js {:content #js [#js {:text \"...edn...\"}]}`
  envelope (with an optional `:isError true`). Four trivial extractors
  used to walk that shape were copy-pasted privately across at least
  five suites (`conformance_test`, `handler_meta_test`, `invoke_test`,
  `cache_test`, `dispatch_test`). They are hoisted here so a wire-shape
  change touches one definition rather than five drifting copies:

    - `args->js`     — CLJS arg map → the `#js {}` object tools read.
    - `extract-text` — pull the first content item's `:text` string.
    - `extract-edn`  — `extract-text` then `edn/read-string`.
    - `error?`       — truthy `:isError` slot.

  ## Stub installer (rf2-wnrpi)

  `with-stubbed-eval!` installs a Promise-returning stub over
  `nrepl/cljs-eval-value` and restores it in `.finally` so cleanup
  outlives async resolution. Two suites (`probe_test`, `dispatch_test`)
  hand-rolled near-identical copies; the corpus driver in
  `conformance_test` keeps its own richer `eval-script`/`forms-seen`
  variant (different contract), and `invoke_test` keeps its
  fixture-scoped restore (rf2-wb06a race) deliberately — neither is
  collapsed here.

  ## `dedup-expand`

  The inverse of `tools.dedup/dedup-value`, useful to assert round-trip
  exactness against an MCP-shaped wire payload without growing the
  production surface. The agent host reconstructs locally via
  `de-dupe.core/expand`; the MCP server never calls the inverse, so the
  helper lives here, signalling \"test-only\" by location.

  ## Test-only base re-exports (rf2-ttspi7)

  `token-estimate`, `overflow-hint-fallback`, and `truncate-preview`
  used to be re-exported from the production `tools.cap` /
  `tools.result-envelope` namespaces SOLELY so the test corpus could
  reach them — production code calls `base-overflow` / `base-cap`
  directly and never used the local aliases. Hosting them here (the
  test-only home) keeps the production surface lean while still pinning
  the cross-MCP token rule + overflow-fallback contract + preview cap in
  one shared test-side place."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [de-dupe.core :as dedup]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.freshness :as freshness]
            [re-frame2-pair-mcp.tools.result-envelope :as renv]
            [re-frame.mcp-base.overflow :as base-overflow]
            [re-frame.mcp-base.vocab :as base-vocab]))

;; ---------------------------------------------------------------------------
;; Wire-envelope extractors — one definition, shared across suites.
;; ---------------------------------------------------------------------------

(defn args->js
  "Coerce a CLJS arg map into the `#js {}` object the tools read via
  `wire/arg`. Keyword keys are `name`d; string keys pass through."
  [m]
  (let [o #js {}]
    (doseq [[k v] m]
      (j/assoc! o (if (keyword? k) (name k) k) v))
    o))

(defn extract-text
  "Pull the first content item's `:text` string from an MCP result
  envelope, or nil if the shape isn't present."
  [^js result]
  (let [content (j/get result :content)
        item    (when (array? content) (aget content 0))]
    (when item (j/get item :text))))

(defn extract-edn
  "`extract-text` then EDN-read the string back to CLJS data."
  [^js result]
  (some-> (extract-text result) edn/read-string))

(defn error?
  "True when the result envelope carries `:isError true`."
  [^js result]
  (true? (j/get result :isError)))

;; ---------------------------------------------------------------------------
;; Stub installer — async-safe `cljs-eval-value` override.
;; ---------------------------------------------------------------------------

(defn with-stubbed-eval!
  "Install a stub `nrepl/cljs-eval-value` that resolves to `canned-value`
  on every call (both the 3- and 4-arity), then run `body-fn` (which
  returns a Promise) and restore the original in `.finally` so cleanup
  outlives async resolution.

  This is the simple, value-returning variant used by suites that don't
  need to inspect the emitted form. Suites that need to capture / match
  the form string keep their own richer installer."
  [canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id _form-str]
                (js/Promise.resolve canned-value))
               ([_conn _build-id _form-str _opts]
                (js/Promise.resolve canned-value)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

(defn with-stubbed-freshness!
  "Stub `freshness/jvm-build-freshness` to resolve to `jvm-half` (a map
  or nil) for the duration of `body-fn`, restoring in `.finally`
  (rf2-ertqw). `discover-app` now assembles a freshness token, which
  reads the JVM-side shadow worker state via `jvm-eval`; in a unit test
  with no live nREPL that probe would attempt a real socket. Stub it so
  discover-app tests stay hermetic and deterministic.

  Pass `nil` to simulate an unreadable JVM half (the `:liveness
  :unknown` degrade path); pass a map like
  `{:compile-cycle 1 :build-flushed-at 100 :runtime-count 1
    :heartbeat-age-ms 50}` to drive a specific verdict.

  Composes with `with-stubbed-eval!` — nest them when a test needs both
  the CLJS health read AND the JVM freshness half stubbed."
  [jvm-half body-fn]
  (let [orig freshness/jvm-build-freshness]
    (set! freshness/jvm-build-freshness (fn [_conn _bid] (js/Promise.resolve jvm-half)))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! freshness/jvm-build-freshness orig))))))

(defn dedup-expand
  "Reverse `tools.dedup/dedup-value`. Given a value possibly wrapped in
  the `:rf.mcp/dedup-table` marker, reconstruct the original structure
  via `de-dupe.core/expand`. Idempotent on already-expanded values
  (returns the input unchanged when the wrapper isn't present)."
  [v]
  (if (and (map? v) (contains? v base-vocab/dedup-table-key))
    (dedup/expand (get v base-vocab/dedup-table-key))
    v))

;; ---------------------------------------------------------------------------
;; Test-only base re-exports (rf2-ttspi7).
;;
;; Production code calls `base-overflow` / `base-cap` directly; these
;; aliases existed only so the test corpus could reach the cross-MCP
;; token rule, the overflow-fallback hint, and the preview cap. Hosting
;; them here keeps the production namespaces lean.
;; ---------------------------------------------------------------------------

(defn token-estimate
  "Delegates to `re-frame.mcp-base.overflow/token-estimate` — the
  cross-MCP `(quot (count s) 4)` token approximation. Test-only home for
  what `tools.cap` used to re-export (rf2-ttspi7)."
  [s]
  (base-overflow/token-estimate s))

(def overflow-hint-fallback
  "The cross-MCP overflow-marker fallback hint string, sourced from
  `re-frame.mcp-base.overflow/overflow-hint-fallback`. Test-only home for
  what `tools.cap` used to re-export (rf2-ttspi7)."
  base-overflow/overflow-hint-fallback)

(defn truncate-preview
  "Truncate `text` to `result-envelope/preview-cap` chars with a
  char-count suffix — the same shape the runtime wrap emits. Test-only
  home for what `tools.result-envelope` used to re-export (rf2-ttspi7);
  pins the preview contract against the single-sourced production cap."
  [text]
  (let [n (count text)]
    (if (> n renv/preview-cap)
      (str (subs text 0 renv/preview-cap) " …(" n " chars)")
      text)))

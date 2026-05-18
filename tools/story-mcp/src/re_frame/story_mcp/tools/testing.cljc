(ns re-frame.story-mcp.tools.testing
  "Testing-category tool handlers — `run-variant`, `snapshot-identity`,
  `run-a11y`, `read-failures`. Per IMPL-SPEC §7.2 these execute (or
  inspect the post-execution state of) variants.

  Wire-egress posture: `run-variant` and `read-failures` route their
  `:app-db` / `:assertions` slots through the helpers in
  `re-frame.story-mcp.tools.helpers` (rf2-73wuj)."
  (:require [re-frame.mcp-base.args :as args]
            [re-frame.story :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async :as async]
            [re-frame.story-mcp.tools.helpers :as h]
            [re-frame.story-mcp.tools.schemas :as s]))

(def ^:const max-timeout-ms
  "Hard ceiling on `:timeout-ms` for `run-variant` (rf2-g9fje, fix 3/3).
  The MCP server's request loop is single-threaded — a `run-variant`
  call with an unbounded `:timeout-ms` parks the loop and starves
  unrelated tool calls. 30 s matches the `:rf.http/timeout-ms`
  baseline rf2-it1cd pinned for the project's outbound HTTP fx, so an
  agent that learns one ceiling sees the same one everywhere.
  Caller-supplied values above this clamp DOWN to the ceiling rather
  than reject — a legitimate slow variant should still run, just
  capped."
  30000)

(def ^:const default-timeout-ms
  "Default `:timeout-ms` for `run-variant` when the caller omits the
  slot. 10 s — well under the 30 s ceiling, enough for the
  vast majority of variants. Kept as a separate const from
  `max-timeout-ms` so the descriptor schema can advertise both
  without re-spelling the literal."
  10000)

(defn tool-run-variant
  "Testing: execute a variant, return the run-variant result map.

  Per IMPL-SPEC §3.2 + §3.5 the result shape is
  `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms ...}`.

  Args:
    :variant-id     required
    :substrate      optional — keyword or string
    :active-modes   optional — coll of mode ids
    :cell-overrides optional — map of arg overrides
    :timeout-ms     optional — JVM blocking timeout; default 10000;
                               clamped to `max-timeout-ms` (30000) per
                               rf2-g9fje so one slow request can't park
                               the single-threaded stdio loop.
    :include-sensitive optional — opt out of wire-egress redaction
                                  (default false; rf2-73wuj)"
  [args]
  (h/with-variant args
    (fn [vk _body]
      (let [opts     (h/read-run-opts args)
            timeout  (min max-timeout-ms
                          (args/parse-positive-int (:timeout-ms args) default-timeout-ms))
            result   (try
                       (async/deref-blocking (story/run-variant vk opts) timeout)
                       (catch Throwable e
                         {:lifecycle :error
                          :variant-id vk
                          :assertions [{:assertion :rf.error/run-failed
                                        :passed? false
                                        :reason (ex-message e)}]}))
            incl?    (h/include-sensitive? args)
            payload  {:frame           (:frame result vk)
                      :app-db          (h/elide-app-db (:app-db result) vk incl?)
                      :assertions      (h/scrub-assertions (:assertions result) incl?)
                      :rendered-hiccup (:rendered-hiccup result)
                      :elapsed-ms      (:elapsed-ms result)
                      :snapshot        (:snapshot result)
                      :lifecycle       (:lifecycle result)
                      :passing?        (story/assertions-passing? result)}]
        (h/text-result (h/pr-edn payload) payload)))))

(defn tool-snapshot-identity
  "Testing: content-hash of the canonicalised variant (for external
  visual-regression). Returns
  `{:variant-id :active-modes :substrate :content-hash}`."
  [args]
  (h/with-variant args
    (fn [vk _body]
      (let [payload (story/snapshot-identity vk (h/read-run-opts args))]
        (h/text-result (h/pr-edn payload) payload)))))

;; `re-frame.story.ui.a11y/violations-by-frame` is the CLJS-side panel
;; atom — resolved once at ns-load via `helpers/resolve-cljs-var`. JVM-
;; standalone deploys read nil and return an empty violations vec; the
;; shared-process (nREPL-attached CLJS) deploy reads the live atom.
(defonce ^:private violations-by-frame-var
  (h/resolve-cljs-var 're-frame.story.ui.a11y/violations-by-frame))

(defn tool-run-a11y
  "Testing: run axe-core against a variant, return violations.

  Per IMPL-SPEC §7.2 the implementation delegates to a11y panel data
  (`a11y/violations-by-frame` per Stage 6's hot-zone-hooks report).
  The actual axe-core run is CLJS-only (it loads an in-browser
  `<script>`); from the JVM-side MCP server we can only READ the
  violations atom that the CLJS canvas has accumulated.

  The canonical agent workflow is:
    1. Open the Story shell in the browser; navigate to the variant.
    2. Click the a11y panel's 'Run' button (or the panel auto-runs on
       canvas mount per Stage 6).
    3. Call this MCP tool to read the violations the panel stored.

  When the server is JVM-standalone (no co-hosted CLJS runtime) this
  returns an empty result with a hint."
  [args]
  (h/with-variant-id args
    (fn [vk]
      (let [atomv (try
                    (when violations-by-frame-var
                      (deref @violations-by-frame-var))
                    (catch Throwable _ nil))
            violations (when atomv (get atomv vk))
            payload {:variant-id vk
                     :violations (vec (or violations []))
                     :note       (when (nil? atomv)
                                   "a11y is CLJS-only; this JVM-standalone deploy can't run axe-core. Run the panel in-browser; the violations atom is read by this tool.")}]
        (h/text-result (h/pr-edn payload) payload)))))

(defn tool-read-failures
  "Testing: accumulated assertion failures across recent `run-variant`
  calls. Reads the variant frame's `:rf.story/assertions` accumulator
  via `re-frame.story/read-assertions`.

  Useful for an agent that ran a variant a moment ago and wants to
  inspect failures without re-running.

  Wire-egress posture (rf2-73wuj): assertion records carrying the
  top-level `:sensitive? true` stamp are dropped via
  `strip-sensitive`. The `:passing?` predicate runs against the
  scrubbed vec so the agent's view of green/red is consistent with
  the records it actually sees — a dropped sensitive failure doesn't
  quietly flip `:passing?` to true. Default off; opt out with
  `:include-sensitive true`."
  [args]
  (h/with-variant-id args
    (fn [vk]
      (let [incl?      (h/include-sensitive? args)
            raw        (assertions/read-assertions vk)
            all        (h/scrub-assertions raw incl?)
            failures   (filterv (complement :passed?) all)
            payload    {:variant-id vk
                        :total      (count all)
                        :failures   (vec failures)
                        :passing?   (story/assertions-passing? all)}]
        (h/text-result (h/pr-edn payload) payload)))))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Testing-category descriptors, in IMPL-SPEC §7.2 order."
  [{:name           "run-variant"
    :category       :testing
    :description    "Execute a variant's four-phase lifecycle (loaders → events → render → play); return the result map (`:frame :app-db :assertions :rendered-hiccup :elapsed-ms :passing?`). The `:app-db` slot is routed through `re-frame.core/elide-wire-value` against the variant frame's `[:rf/elision]` registry — declared-sensitive paths return `:rf/redacted` and oversize slots return the `:rf.size/large-elided` marker by default. Pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture)."
    :typicalTokens  2000
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-include-sensitive
                                  {:variant-id s/kw-or-string
                                   :substrate s/kw-or-string
                                   :active-modes {:type "array" :items s/kw-or-string}
                                   :cell-overrides {:type "object"}
                                   :timeout-ms {:type "integer" :minimum 1 :maximum max-timeout-ms
                                                :description (str "JVM blocking timeout. Default "
                                                                  default-timeout-ms "ms. Hard ceiling "
                                                                  max-timeout-ms "ms — values above clamp DOWN "
                                                                  "rather than reject; the MCP server's request "
                                                                  "loop is single-threaded so an unbounded "
                                                                  "timeout would park unrelated calls (rf2-g9fje).")}}))
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-run-variant}

   {:name           "snapshot-identity"
    :category       :testing
    :description    "Content-hash of (variant × resolved args × decorators × loaders × substrate × modes). Stable across hosts; key for visual-regression."
    :typicalTokens  100
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                {:variant-id s/kw-or-string
                                 :substrate s/kw-or-string
                                 :active-modes {:type "array" :items s/kw-or-string}})
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-snapshot-identity}

   {:name           "run-a11y"
    :category       :testing
    :description    "Read axe-core violations for a variant from `re-frame.story.ui.a11y/violations-by-frame`. The actual axe-core run is CLJS-only; this tool returns whatever the in-browser panel has accumulated."
    :typicalTokens  500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:variant-id s/kw-or-string})
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-run-a11y}

   {:name           "read-failures"
    :category       :testing
    :description    "Accumulated assertion failures for a variant frame (since the most recent `run-variant`). Returns `{:total :failures :passing?}`. Assertion records carrying `:sensitive? true` are dropped at egress by default; pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture)."
    :typicalTokens  500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-include-sensitive
                                  {:variant-id s/kw-or-string}))
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-read-failures}])

(ns re-frame.story-mcp.tools.testing
  "Testing-category tool handlers — `run-variant`, `snapshot-identity`,
  `read-a11y-violations`, `read-failures`. Per IMPL-SPEC §7.2 these
  execute (or inspect the post-execution state of) variants.

  ## Unified run-result mirror

  `run-variant` and `read-failures` speak the SAME unified
  `re-frame.story.result/run-result` model the human Story UI reads
  (spec/017 §Run result): the top-level `:status` ∈
  `#{:pass :fail :cannot-run :error}` verdict, the unified assertion
  records (each carrying a derived `:status`), the `:checks` groups, the
  `:consumed-selectors` agreement-floor set, and the `.4` evidence-slot
  projections (`:schema-violations` / `:warnings` / `:effects` /
  `:sub-runs` / `:renders` / `:narrative`). There is NO agent-only result
  vocabulary — the agent reads off the same verdict a human reads.

  `:status` is the single verdict — it expresses the distinct
  `:cannot-run` third status that a flat green/red `:passing?` bit
  cannot, which is why the result vocabulary carries no such bit.
  `story/run-variant` already assembles the unified shape through
  `result/run-result`; these handlers project its slots rather than
  re-deriving a parallel verdict.

  Wire-egress posture: `run-variant` and `read-failures` route their
  `:app-db` / `:assertions` slots through
  `re-frame.story-mcp.tools.egress`."
  (:require [re-frame.story :as story]
            [re-frame.story.async :as async]
            [re-frame.story-mcp.tools.args :as targs]
            [re-frame.story-mcp.tools.cljs-resolve :as cljs-resolve]
            [re-frame.story-mcp.tools.egress :as egress]
            [re-frame.story-mcp.tools.result :as result]
            [re-frame.story-mcp.tools.schemas :as s]))

(def failure-statuses
  "The unified assertion `:status` values `read-failures` filters to —
  the records an agent should localise + heal (spec/017 §Run result). A
  `:cannot-run` record is NOT a failure (the runner proved nothing, not a
  mismatch); it is surfaced via the run-level `:status` instead, never
  conflated with a real assertion failure."
  #{:fail :error})

(defn tool-run-variant
  "Testing: execute a variant, return the UNIFIED run-result
  (spec/017 §Run result — the same shape the human Story UI reads).

  `story/run-variant` already assembles the unified result through
  `re-frame.story.result/run-result`; this handler projects its slots
  (scrubbing the value-bearing ones at egress) — it does NOT re-derive a
  parallel verdict. The headline an agent reads is the top-level
  `:status` ∈ `#{:pass :fail :cannot-run :error}` — the one verdict, and
  only it can express the distinct `:cannot-run` third outcome an agent
  must handle as 'the runner could not attempt this', NOT as a fail.

  Payload slots:
    :status             #{:pass :fail :cannot-run :error} — the verdict
    :frame              the variant frame id
    :assertions         unified assertion records (each with a derived
                        :status); scrubbed of :sensitive? records
    :checks             named check records grouping their assertions
    :consumed-selectors the agreement-floor's exactly-consumed
                        schema-violation selectors (single source of truth)
    :schema-violations / :warnings / :effects / :sub-runs / :renders /
    :narrative          the .4 evidence-slot projections (one tape, one
                        projection); ALL value-bearing — each is
                        PATH-projected at egress (EP-0025 fail-open: a value
                        AT a classified path redacts; a value re-keyed into
                        :narrative beats / :warnings / :sub-runs at a
                        non-app-db position ships RAW — classify the app-db
                        PATH to redact it at the source)
    :app-db             post-run app-db, elided at egress
    :rendered-hiccup / :snapshot  PATH-projected derived trees (EP-0025
                                  fail-open: value at a classified path ->
                                  :rf/redacted / :rf.size/large-elided; a
                                  re-keyed copy ships raw)
    :elapsed-ms         wall-clock run time
    :cannot-run         present iff the run carried :cannot-run refusals

  Args:
    :variant-id     required
    :substrate      optional — keyword or string
    :active-modes   optional — coll of mode ids
    :cell-overrides optional — map of arg overrides
    :timeout-ms     optional — JVM blocking timeout; default 10000;
                               clamped to `max-timeout-ms` (30000) so one
                               slow request can't park the single-threaded
                               stdio loop.
    :include-sensitive optional — opt out of wire-egress redaction
                                  (default false)"
  [arguments]
  (targs/with-variant arguments
    (fn [vk _body]
      (let [opts     (targs/read-run-opts vk arguments)
            timeout  (targs/resolve-timeout-ms arguments)
            outcome  (try
                       (async/deref-blocking (story/run-variant vk opts) timeout)
                       (catch Throwable e
                         ;; A throw / timeout never produced a unified
                         ;; result; mint the :error verdict directly so the
                         ;; wire shape is the SAME unified shape a settled run
                         ;; emits (one vocabulary, no special-case branch for
                         ;; agents to learn).
                         {:status     :error
                          :frame      vk
                          :assertions [(story/assertion-record
                                         {:assertion :rf.error/run-failed
                                          :passed?   false
                                          :error     true
                                          :reason    (ex-message e)})]
                          :checks     []}))
            incl?    (targs/include-sensitive? arguments)
            raw-db   (:app-db outcome)
            [assertions dropped] (egress/scrub-assertions+count (:assertions outcome) incl?)
            payload  (cond-> {:status             (:status outcome)
                              :frame              (:frame outcome vk)
                              :app-db             (egress/elide-app-db raw-db vk incl?)
                              :assertions         assertions
                              :checks             (vec (:checks outcome))
                              :consumed-selectors (:consumed-selectors outcome #{})
                              ;; Evidence-slot projections (.4 — one tape, one
                              ;; projection). Every value-bearing slot is
                              ;; PATH-projected against the frame's
                              ;; classification, same as :rendered-hiccup.
                              ;; EP-0025 FAIL-OPEN: :narrative beats carry
                              ;; :db-before/:db-after FULL app-db snapshots,
                              ;; :warnings are trace-event records, :sub-runs
                              ;; carry subscription :value — a secret re-keyed
                              ;; into any of these non-app-db positions ships
                              ;; RAW (value-match removed; classify the app-db
                              ;; PATH to redact at the source). scrub-rendered
                              ;; recurses the nested trees and the gate stays
                              ;; symmetric (incl? true forwards raw).
                              :schema-violations  (egress/scrub-rendered (:schema-violations outcome) raw-db vk incl?)
                              :warnings           (egress/scrub-rendered (vec (:warnings outcome)) raw-db vk incl?)
                              :effects            (egress/scrub-rendered (:effects outcome) raw-db vk incl?)
                              :sub-runs           (egress/scrub-rendered (vec (:sub-runs outcome)) raw-db vk incl?)
                              :renders            (egress/scrub-rendered (:renders outcome) raw-db vk incl?)
                              :narrative          (egress/scrub-rendered (:narrative outcome) raw-db vk incl?)
                              ;; Derived trees: PATH-projected. A value AT a
                              ;; classified path redacts; a re-keyed copy ships
                              ;; raw (EP-0025 fail-open).
                              :rendered-hiccup    (egress/scrub-rendered (:rendered-hiccup outcome) raw-db vk incl?)
                              :elapsed-ms         (:elapsed-ms outcome)
                              :snapshot           (egress/scrub-rendered (:snapshot outcome) raw-db vk incl?)}
                       ;; Surface the :cannot-run refusals only when present —
                       ;; the run-result carries them iff the runner could not
                       ;; attempt some expectation.
                       (contains? outcome :cannot-run)
                       (assoc :cannot-run (:cannot-run outcome)))]
        ;; Surface the MUST-level egress indicator counts:
        ;; dropped sensitive assertion records + elided over-threshold
        ;; leaves across every value-bearing slot. Omitted when zero
        ;; (Conventions §Cross-MCP indicator-field vocabulary).
        (egress/result-with-indicators payload dropped)))))

(defn tool-snapshot-identity
  "Testing: content-hash of the canonicalised variant (for external
  visual-regression). Returns
  `{:variant-id :active-modes :substrate :content-hash}`."
  [arguments]
  (targs/with-variant arguments
    (fn [vk _body]
      (result/edn-result (story/snapshot-identity vk (targs/read-run-opts vk arguments))))))

;; `re-frame.story.ui.a11y/violations-by-frame` is the CLJS-side panel
;; atom — resolved once at ns-load via `cljs-resolve/resolve-cljs-var`. JVM-
;; standalone deploys read nil and return an empty violations vec; the
;; shared-process (nREPL-attached CLJS) deploy reads the live atom.
(defonce ^:private violations-by-frame-var
  (cljs-resolve/resolve-cljs-var 're-frame.story.ui.a11y/violations-by-frame))

(defn tool-read-a11y-violations
  "Testing: READ the axe-core violations a variant's in-browser a11y
  panel has accumulated. This tool does NOT execute axe-core — it is a
  diagnostic re-read of already-computed panel state (the `read-`
  vocabulary per tools/mcp-conformance/NAMING.md), the sibling of
  `read-failures`. Calling it neither runs a fresh accessibility check
  nor proves the variant accessible; it reflects whatever the panel
  last stored (which may be stale or empty).

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
  returns an empty result with a hint.

  ## Wire-egress posture

  The `:violations` vec is LIVE RUNTIME observed state — the rendered DOM
  of the variant frame, normalised from axe-core's JS violation objects.
  Each axe-core violation NODE carries `:html` (the violating element's
  outerHTML), `:target` (CSS selectors) and `:failureSummary`; a sensitive
  value rendered into the DOM (`<input value=\"<token>\">`, a `data-*`
  attribute, a PII text node) lands verbatim in node `:html`. axe DOM nodes
  are an inherently RE-KEYED runtime payload class (the secret rides node
  `:html`, a non-app-db position), so `:violations` route through the NAMED
  `egress/scrub-re-keyed-runtime` exception (rf2-jwggld) — the SAME projection
  `record-as-variant`'s event vectors take. Under a LIVE variant frame
  EP-0025 FAIL-OPEN holds: a value rendered into a node `:html` is a RE-KEYED
  DOM position the classification path cannot reach, so it ships RAW
  (value-match removed; classify the app-db PATH to redact a value before it
  reaches the DOM). Under a NON-LIVE frame (common in the JVM tool process,
  where the variant frame may not be allocated) the nodes ship raw under the
  documented carve-out — path-scrub is a no-op even live, so fail-closing
  would destroy the tool with zero leak-delta. Pass `:include-sensitive true`
  to opt out (gated by `--allow-sensitive-reads`, per spec/Tool-Pair.md
  §Direct-read privacy posture). `read-a11y-violations` is `:readOnlyHint true`
  (agent hosts auto-approve it)."
  [arguments]
  (targs/with-variant-id arguments
    (fn [vk]
      (let [incl?    (targs/include-sensitive? arguments)
            by-frame (try
                       (when violations-by-frame-var
                         (deref @violations-by-frame-var))
                       (catch Throwable _ nil))
            violations (when by-frame (get by-frame vk))
            payload {:variant-id vk
                     :violations (egress/scrub-re-keyed-runtime
                                   (vec (or violations [])) vk incl?)
                     :note       (when (nil? by-frame)
                                   "a11y is CLJS-only; this JVM-standalone deploy can't run axe-core. Run the panel in-browser; the violations atom is read by this tool.")}]
        (result/edn-result payload)))))

(defn tool-read-failures
  "Testing: the variant frame's current accumulated assertion records
  (as of the most recent `run-variant`), as UNIFIED assertion records.
  Reads the frame's `:rf.story/assertions` accumulator once via
  `re-frame.story/read-assertions` — no re-run, no cross-call history; a
  later `run-variant` overwrites the accumulator.

  Useful for an agent that ran a variant a moment ago and wants to
  inspect failures without re-running.

  The records ride the SAME unified shape `run-variant` emits
  (spec/017 §Run result): each record is normalized through
  `re-frame.story/assertion-records` so it carries a derived
  `:status`, and the headline `:status` is the aggregate verdict over the
  records (`re-frame.story/aggregate-verdict` — the ONE rule:
  `:error` > `:fail` > `:cannot-run` > `:pass`). `:status` is the one
  verdict, read off the records rather than recomputed as a green/red bit.
  `:failures` is filtered to the genuine failure statuses (`:fail` /
  `:error`) — a
  `:cannot-run` record is not a failure (the runner proved nothing) and
  surfaces via the run-level `:status`, not the failures list.

  NOTE this is a re-READ of the accumulator, not a re-run: it has no
  epoch tape, so it cannot apply the agreement floor or the
  runner-refusal `:cannot-run` fold a fresh `run-variant` does. The
  status is the assertion-record aggregate only; for the full run verdict
  (tape floor + refusals) re-run via `run-variant`.

  Wire-egress posture: assertion records carrying the
  top-level `:sensitive? true` stamp are dropped via `strip-sensitive`.
  The `:status` aggregate runs against the scrubbed vec so the agent's
  view of the verdict is consistent with the records it actually sees — a
  dropped sensitive failure doesn't quietly flip the verdict to `:pass`.
  Default off; opt out with `:include-sensitive true`."
  [arguments]
  (targs/with-variant-id arguments
    (fn [vk]
      (let [incl?      (targs/include-sensitive? arguments)
            raw        (story/read-assertions vk)
            [scrubbed dropped] (egress/scrub-assertions+count raw incl?)
            ;; Stamp the derived :status on every record so the agent reads
            ;; the SAME unified record shape `run-variant` emits.
            records    (story/assertion-records scrubbed)
            failures   (filterv #(contains? failure-statuses (:status %)) records)
            payload    {:variant-id vk
                        :status     (story/aggregate-verdict records nil)
                        :total      (count records)
                        :failures   failures
                        :assertions records}]
        ;; Surface the MUST-level egress indicator counts:
        ;; how many sensitive assertion records were dropped at egress
        ;; (+ any elided leaves). Omitted when zero (Conventions
        ;; §Cross-MCP indicator-field vocabulary).
        (egress/result-with-indicators payload dropped)))))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Testing-category descriptors, in IMPL-SPEC §7.2 order."
  [{:name           "run-variant"
    :category       :testing
    :description    (str "Execute a variant's four-phase lifecycle (loaders → setup → render → script); return the UNIFIED run-result — the same shape the human Story UI reads. The headline is `:status` ∈ {:pass :fail :cannot-run :error}; the result also carries unified `:assertions` records (each with a derived `:status`), `:checks` groups, `:consumed-selectors`, the evidence-slot projections (`:schema-violations :warnings :effects :sub-runs :renders :narrative`), `:app-db`, `:rendered-hiccup`, `:snapshot`, and `:elapsed-ms`. `:cannot-run` means the runner could not even attempt the plan — handle it as 'not runnable here', NOT as a fail. The `:app-db` slot is routed through `re-frame.core/elide-wire-value` against the variant frame's `[:rf.runtime/elision]` runtime-db registry — declared-sensitive paths return `:rf/redacted` and oversize slots return the `:rf.size/large-elided` marker by default. The derived `:rendered-hiccup` / `:snapshot` and ALL evidence value-slots (`:schema-violations :warnings :effects :sub-runs :renders :narrative`) are PATH-projected on BOTH egress axes against the same frame classification. EP-0025 FAIL-OPEN: a value AT a classified path redacts, but a value RE-KEYED to a non-matching position (a token at hiccup `[1 :value]`, a `:narrative` beat's `:db-before` snapshot, a `:sub-runs` `:value`) ships RAW — value-match was removed; classify the app-db PATH to redact a value before a view renders it. Pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture). "
                         "Examples: "
                         "1. Green run: {:variant-id \":story.cart/full\"} -> {:status :pass :frame :story.cart/full :app-db {...} :assertions [{:assertion :rf.assert/path-equals :passed? true :status :pass}] :checks [] :elapsed-ms 42}. "
                         "2. Red run: {:variant-id \":story.cart/bad\"} -> {:status :fail :assertions [{:assertion :rf.assert/sub-equals :passed? false :status :fail :actual nil :expected 3}]}. "
                         "3. Cannot-run (a causal assertion under a non-reactive runner): {:variant-id \":story.cart/caused\"} -> {:status :cannot-run :cannot-run [...] :assertions [{:status :cannot-run :cannot-run? true ...}]}. "
                         "4. Clamped timeout / error: {:variant-id \":story.slow/loader\" :timeout-ms 60000} -> runs with timeout clamped to 30000ms (max-timeout-ms ceiling); on overrun returns {:status :error :assertions [{:assertion :rf.error/run-failed :status :error ...}]}.")
    :typicalTokens  2000
    ;; `run-variant` ships the variant's `:app-db` re-keyed
    ;; into `:rendered-hiccup` and `:snapshot`; structural dedup
    ;; collapses those three references into one cache slot at the wire
    ;; boundary. Mirrors pair-mcp's selective `:dedup` knob on
    ;; `snapshot` / `trace-window` (descriptors_data.cljs).
    :dedup-eligible? true
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-dedup
                                  (s/with-include-sensitive
                                    (s/with-timeout-ms
                                      {:variant-id s/kw-or-string
                                       :substrate s/kw-or-string
                                       :active-modes {:type "array" :items s/kw-or-string}
                                       :cell-overrides {:type "object"}}))))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/run-variant-annotations
    :handler     tool-run-variant}

   {:name           "snapshot-identity"
    :category       :testing
    :description    (str "Content-hash of (variant × resolved args × decorators × loaders × substrate × modes × cell-overrides). Stable across hosts; key for visual-regression. `:cell-overrides` perturbs the hash via the resolved `:effective-args` (Story's `resolve-args` merges them after mode args), so two cells differing only by an override get distinct `:content-hash` values. "
                         "Examples: "
                         "1. Bare: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :active-modes [] :substrate nil :content-hash \"sha256:abcd...\"}. "
                         "2. With substrate + mode: {:variant-id \":story.cart/full\" :substrate \":uix\" :active-modes [\":mode/dark\"]} -> different :content-hash from #1 because the tuple inputs differ. "
                         "3. With cell-overrides: {:variant-id \":story.cart/full\" :cell-overrides {:qty 9}} -> different :content-hash from #1 because the override changes the resolved args. "
                         "4. Use for cache key: build a visual-regression key as `(str variant-id \"@\" content-hash)`.")
    :typicalTokens  100
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                {:variant-id s/kw-or-string
                                 :substrate s/kw-or-string
                                 :active-modes {:type "array" :items s/kw-or-string}
                                 :cell-overrides {:type "object"}})
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-snapshot-identity}

   {:name           "read-a11y-violations"
    :category       :testing
    :description    (str "READ the axe-core violations a variant's in-browser a11y panel has accumulated, from `re-frame.story.ui.a11y/violations-by-frame`. This tool does NOT execute axe-core — it is a diagnostic re-read of already-computed panel state (the sibling of `read-failures`), so calling it neither runs a fresh accessibility check nor proves the variant accessible; it returns whatever the in-browser panel last stored (possibly stale or empty). The `:violations` vec is LIVE RUNTIME DOM state — each axe-core node carries `:html` (the violating element's outerHTML), `:target` (CSS selectors) and `:failureSummary`, so a sensitive value rendered into the DOM lands verbatim in node `:html`. axe DOM nodes are an inherently RE-KEYED runtime payload class, scrubbed via the named `scrub-re-keyed-runtime` egress exception (rf2-jwggld): a live variant frame PATH-projects against its classification (EP-0025 FAIL-OPEN — a value rendered into a node `:html` is a RE-KEYED DOM position the classification path cannot reach, so it ships RAW; classify the app-db PATH to redact a value before it reaches the DOM), and a non-live frame ships the nodes raw under the documented carve-out (path-scrub is a no-op even live, so fail-closing would destroy the tool with zero leak-delta). Pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture). "
                         "Examples: "
                         "1. Clean variant in shared-process deploy: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :violations [] :note nil}. "
                         "2. Variant with axe-core findings: {:variant-id \":story.form/checkout\"} -> {:variant-id :story.form/checkout :violations [{:id \"label\" :impact \"critical\" :nodes [...]}]}. "
                         "3. JVM-standalone deploy: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :violations [] :note \"a11y is CLJS-only; this JVM-standalone deploy can't run axe-core...\"}.")
    :typicalTokens  500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-include-sensitive {:variant-id s/kw-or-string}))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-read-a11y-violations}

   {:name           "read-failures"
    :category       :testing
    :description    (str "Accumulated assertion records for a variant frame (since the most recent `run-variant`), as UNIFIED records. Returns `{:status :total :failures :assertions}` — the same record shape `run-variant` emits: each record carries a derived `:status`, `:status` is the aggregate verdict over the records, and `:failures` is filtered to the genuine failure statuses (`:fail` / `:error`). A re-read of the accumulator, not a re-run — no epoch tape, so the status is the assertion-record aggregate only (re-run via `run-variant` for the full run verdict incl. the agreement floor). Assertion records carrying `:sensitive? true` are dropped at egress by default; pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture). "
                         "Examples: "
                         "1. Clean run: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :status :pass :total 3 :failures [] :assertions [{:assertion :rf.assert/path-equals :passed? true :status :pass} ...]}. "
                         "2. Mixed pass/fail: {:variant-id \":story.cart/bad\"} -> {:variant-id :story.cart/bad :status :fail :total 5 :failures [{:assertion :rf.assert/sub-equals :passed? false :status :fail :reason \"...\"}] :assertions [...]}. "
                         "3. Never-run variant: {:variant-id \":story.never/run\"} -> {:variant-id :story.never/run :status :pass :total 0 :failures [] :assertions []} (vacuously green).")
    :typicalTokens  500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                (s/with-include-sensitive
                                  {:variant-id s/kw-or-string}))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema s/default-output-schema
    :annotations  s/read-only-annotations
    :handler     tool-read-failures}])

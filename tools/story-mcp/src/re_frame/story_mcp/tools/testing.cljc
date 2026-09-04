(ns re-frame.story-mcp.tools.testing
  "Testing-category tool handlers — `run-variant`, `snapshot-identity`,
  `read-a11y-violations`, `read-failures`. These execute or inspect the
  post-execution state of variants as described in
  spec/002-Tool-Registry.md.

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
  `rf.story/run-variant` already assembles the unified shape through
  `result/run-result`; these handlers project its slots rather than
  re-deriving a parallel verdict.

  Wire-egress posture: `run-variant` and `read-failures` route their
  `:app-db` / `:assertions` slots through
  `re-frame.story-mcp.tools.egress`."
  (:require [re-frame.story :as rf.story]
            [re-frame.story-mcp.tools.args :as rf.story-mcp.tools.args]
            [re-frame.story-mcp.tools.cljs-resolve :as rf.story-mcp.tools.cljs-resolve]
            [re-frame.story-mcp.tools.egress :as rf.story-mcp.tools.egress]
            [re-frame.story-mcp.tools.lifecycle :as rf.story-mcp.tools.lifecycle]
            [re-frame.story-mcp.tools.result :as rf.story-mcp.tools.result]
            [re-frame.story-mcp.tools.schemas :as rf.story-mcp.tools.schemas]))

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

  `rf.story/run-variant` already assembles the unified result through
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
    :snapshot           the PATH-projected derived tree (EP-0025 fail-open:
                        value at a classified path -> :rf/redacted /
                        :rf.size/large-elided; a re-keyed copy ships raw).
                        Rendered output is NOT a run-variant slot — it is
                        `rf.story/render-variant`'s `:rendered`.
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
  (rf.story-mcp.tools.args/with-variant arguments
    (fn [vk _body]
      (or (rf.story-mcp.tools.args/run-opts-shape-error arguments)
          (rf.story-mcp.tools.args/substrate-arg-error arguments "run-variant")
          ;; SEMANTIC run-option guard (rf2-sw1d), after the shape +
          ;; substrate guards and before any lifecycle work: an unknown
          ;; `:active-modes` id or `:cell-overrides` key is REFUSED
          ;; rather than silently dropped by `read-run-opts`'s
          ;; bounded-allowlist coercion. A dropped identifier would
          ;; settle an ordinary `:status :pass` over a DIFFERENT
          ;; scenario — false test evidence, not a near miss.
          (rf.story-mcp.tools.args/run-opts-semantic-error arguments vk)
          ;; Host PREREQUISITE, checked last among the guards and before any
          ;; lifecycle work: with no adapter installed the frame never obtains
          ;; its state substrate, and an inert run settles the ordinary
          ;; `:status :pass` over `{}` / `[]` — a success-shaped non-run
          ;; (rf2-c9t52). Story's vacuous-`:pass` rule for an EXECUTED
          ;; assertion-free variant is untouched; this refuses the state where
          ;; nothing executed at all.
          (rf.story-mcp.tools.lifecycle/no-adapter-error "run-variant")
          (let [opts     (rf.story-mcp.tools.args/read-run-opts vk arguments)
                ;; Blocking invocation + timeout + canonical exception
                ;; normalization are owned by `tools.lifecycle` — the ONE
                ;; execution owner `preview-variant` shares, so their catch
                ;; paths cannot drift (the error outcome always routes through
                ;; `rf.story/run-result`, filling the six `.4` evidence slots to
                ;; `[]` so the wire projection never ships a bare nil for an
                ;; absent slot, rf2-5r6j96). Everything AFTER the outcome —
                ;; projection, egress, indicator counts, wire shaping — stays
                ;; run-variant-specific, below.
                outcome  (rf.story-mcp.tools.lifecycle/run-variant-blocking
                           vk opts (rf.story-mcp.tools.args/resolve-timeout-ms arguments))
                incl?    (rf.story-mcp.tools.args/include-sensitive? arguments)
                raw-db   (:app-db outcome)
                [assertions dropped] (rf.story-mcp.tools.egress/scrub-assertions+count (:assertions outcome) incl?)
                payload  (cond-> {:status             (:status outcome)
                                  :frame              (:frame outcome vk)
                                  :app-db             (rf.story-mcp.tools.egress/elide-app-db raw-db vk incl?)
                                  :assertions         assertions
                                  :checks             (vec (:checks outcome))
                                  :consumed-selectors (:consumed-selectors outcome #{})
                                  ;; Evidence-slot projections (.4 — one tape, one
                                  ;; projection). Every value-bearing slot is
                                  ;; PATH-projected against the frame's
                                  ;; classification, same as :snapshot.
                                  ;; EP-0025 FAIL-OPEN: :narrative beats carry
                                  ;; :db-before/:db-after FULL app-db snapshots,
                                  ;; :warnings are trace-event records, :sub-runs
                                  ;; carry subscription :value — a secret re-keyed
                                  ;; into any of these non-app-db positions ships
                                  ;; RAW (value-match removed; classify the app-db
                                  ;; PATH to redact at the source). scrub-rendered
                                  ;; recurses the nested trees and the gate stays
                                  ;; symmetric (incl? true forwards raw).
                                  ;; Every slot is `(vec ...)`-wrapped BEFORE the
                                  ;; projection (not just the two that used to be)
                                  ;; — `scrub-rendered` has no nil short-circuit of
                                  ;; its own reaching `project-egress`, so a bare
                                  ;; absent-key nil would walk through as nil
                                  ;; (live frame) rather than the `[]` the frozen
                                  ;; `[:sequential :any]` schema requires
                                  ;; (rf2-5r6j96). `run-result` above already
                                  ;; fills every slot with `[]`; the `vec` here is
                                  ;; the belt-and-suspenders guard at the
                                  ;; projection call site itself, independent of
                                  ;; how `outcome` was assembled.
                                  :schema-violations  (rf.story-mcp.tools.egress/scrub-rendered (vec (:schema-violations outcome)) raw-db vk incl?)
                                  :warnings           (rf.story-mcp.tools.egress/scrub-rendered (vec (:warnings outcome)) raw-db vk incl?)
                                  :effects            (rf.story-mcp.tools.egress/scrub-rendered (vec (:effects outcome)) raw-db vk incl?)
                                  :sub-runs           (rf.story-mcp.tools.egress/scrub-rendered (vec (:sub-runs outcome)) raw-db vk incl?)
                                  :renders            (rf.story-mcp.tools.egress/scrub-rendered (vec (:renders outcome)) raw-db vk incl?)
                                  :narrative          (rf.story-mcp.tools.egress/scrub-rendered (vec (:narrative outcome)) raw-db vk incl?)
                                  ;; Derived tree: PATH-projected. A value AT a
                                  ;; classified path redacts; a re-keyed copy ships
                                  ;; raw (EP-0025 fail-open). `run-variant` produces
                                  ;; no rendered output — rendering is
                                  ;; `rf.story/render-variant`'s (rf2-6r9j.13).
                                  :elapsed-ms         (:elapsed-ms outcome)
                                  :snapshot           (rf.story-mcp.tools.egress/scrub-rendered (:snapshot outcome) raw-db vk incl?)}
                           ;; Surface the :cannot-run refusals only when present —
                           ;; the run-result carries them iff the runner could not
                           ;; attempt some expectation.
                           (contains? outcome :cannot-run)
                           (assoc :cannot-run (:cannot-run outcome)))]
            ;; Surface the MUST-level egress indicator counts:
            ;; dropped sensitive assertion records + elided over-threshold
            ;; leaves across every value-bearing slot. Omitted when zero
            ;; (Conventions §Cross-MCP indicator-field vocabulary).
            (rf.story-mcp.tools.egress/result-with-indicators payload dropped))))))

(defn tool-snapshot-identity
  "Testing: content-hash of the canonicalised variant (for external
  visual-regression). Returns
  `{:variant-id :active-modes :substrate :content-hash}`."
  [arguments]
  (rf.story-mcp.tools.args/with-variant arguments
    (fn [vk _body]
      (or (rf.story-mcp.tools.args/run-opts-shape-error arguments)
          (rf.story-mcp.tools.args/substrate-arg-error arguments "snapshot-identity")
          ;; SEMANTIC run-option guard (rf2-sw1d), after the shape +
          ;; substrate guards and before the hash: an unknown
          ;; `:active-modes` id or `:cell-overrides` key is REFUSED
          ;; rather than silently dropped by `read-run-opts`'s
          ;; bounded-allowlist coercion. Both slots are identity-BEARING
          ;; here — they perturb the `:content-hash` via the resolved
          ;; `:effective-args` — so a dropped identifier would hand the
          ;; agent a visual-regression key for a different tuple.
          (rf.story-mcp.tools.args/run-opts-semantic-error arguments vk)
          (rf.story-mcp.tools.result/edn-result (rf.story/snapshot-identity vk (rf.story-mcp.tools.args/read-run-opts vk arguments)))))))

(defn tool-read-a11y-violations
  "Testing: READ the axe-core violations a variant's in-browser a11y
  panel has accumulated. This tool does NOT execute axe-core — it is a
  diagnostic re-read of already-computed panel state (the `read-`
  vocabulary per tools/mcp-conformance/NAMING.md), the sibling of
  `read-failures`. Calling it neither runs a fresh accessibility check
  nor proves the variant accessible; it reflects whatever the panel
  last stored (which may be stale or empty).

  The actual axe-core run and `violations-by-frame` atom are CLJS-only.
  When the a11y-panel-state provider is UNREACHABLE (the JVM stdio server
  cannot read that browser atom), this returns a machine-readable
  capability-unavailable error via the `cljs-resolve` host-capability
  boundary — NOT a false-empty `{:violations []}` success an agent could
  mistake for 'zero accessibility violations' (rf2-3fc89f.21). Ordinary
  success with a (possibly empty) `:violations` vec is reserved for a
  REACHED provider (a browser-local consumer of this `.cljc` helper whose
  panel actually answered).

  ## Wire-egress posture

  The `:violations` vec is LIVE RUNTIME observed state — the rendered DOM
  of the variant frame, normalised from axe-core's JS violation objects.
  Each axe-core violation NODE carries `:html` (the violating element's
  outerHTML), `:target` (CSS selectors) and `:failureSummary`; a sensitive
  value rendered into the DOM (`<input value=\"<token>\">`, a `data-*`
  attribute, a PII text node) lands verbatim in node `:html`. axe DOM nodes
  are an inherently RE-KEYED runtime payload class (the secret rides node
  `:html`, a non-app-db position), so `:violations` route through the NAMED
  `rf.story-mcp.tools.egress/scrub-re-keyed-runtime` exception (rf2-jwggld). Under a LIVE variant frame
  EP-0025 FAIL-OPEN holds: a value rendered into a node `:html` is a RE-KEYED
  DOM position the classification path cannot reach, so it ships RAW
  (value-match removed; classify the app-db PATH to redact a value before it
  reaches the DOM). Under a NON-LIVE frame the nodes ship raw under the
  documented carve-out — path-scrub is a no-op even live, so fail-closing
  would destroy the tool with zero leak-delta. Pass `:include-sensitive true`
  to opt out (gated by `--allow-sensitive-reads`, per spec/Tool-Pair.md
  §Direct-read privacy posture). `read-a11y-violations` is `:readOnlyHint true`
  (agent hosts auto-approve it)."
  [arguments]
  (rf.story-mcp.tools.args/with-variant-id arguments
    (fn [vk]
      (if-not (rf.story-mcp.tools.cljs-resolve/a11y-provider-available?)
        (rf.story-mcp.tools.result/capability-unavailable-result
          {:tool       "read-a11y-violations"
           :capability "a11y-panel-state"
           :detail     (str "The axe-core run + `violations-by-frame` atom are "
                            "CLJS-in-browser only; a reached provider is required "
                            "before an empty result can mean 'zero violations'.")})
        (let [incl?      (rf.story-mcp.tools.args/include-sensitive? arguments)
              by-frame   (rf.story-mcp.tools.cljs-resolve/a11y-violations-by-frame)
              violations (get by-frame vk)
              payload    {:variant-id vk
                          :violations (rf.story-mcp.tools.egress/scrub-re-keyed-runtime
                                        (vec (or violations [])) vk incl?)}]
          (rf.story-mcp.tools.result/edn-result payload))))))

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
  (rf.story-mcp.tools.args/with-variant-id arguments
    (fn [vk]
      (let [incl?      (rf.story-mcp.tools.args/include-sensitive? arguments)
            raw        (rf.story/read-assertions vk)
            [scrubbed dropped] (rf.story-mcp.tools.egress/scrub-assertions+count raw incl?)
            ;; Stamp the derived :status on every record so the agent reads
            ;; the SAME unified record shape `run-variant` emits.
            records    (rf.story/assertion-records scrubbed)
            failures   (filterv #(contains? failure-statuses (:status %)) records)
            payload    {:variant-id vk
                        :status     (rf.story/aggregate-verdict records nil)
                        :total      (count records)
                        :failures   failures
                        :assertions records}]
        ;; Surface the MUST-level egress indicator counts:
        ;; how many sensitive assertion records were dropped at egress
        ;; (+ any elided leaves). Omitted when zero (Conventions
        ;; §Cross-MCP indicator-field vocabulary).
        (rf.story-mcp.tools.egress/result-with-indicators payload dropped)))))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Testing-category descriptors, in spec/002-Tool-Registry.md order."
  [{:name           "run-variant"
    :category       :testing
    :description    (str "Execute a variant's four-phase lifecycle (loaders → setup → render → script); return the UNIFIED run-result — the same shape the human Story UI reads. Host prerequisite: running a variant needs an installed re-frame adapter (the frame's state substrate). With none, this REFUSES up front — `isError true`, `:rf.error :rf.error/no-adapter-installed` — rather than settling a success-shaped non-run; install one in the namespace your launch alias preloads (`(rf/init! plain-atom/adapter)` is the renderer-free headless choice). Catalogue reads need no adapter. An explicit `:substrate` is validated, not silently dropped: it requires a REACHED substrate registry (unreachable on the JVM stdio host → `:rf.error/story-mcp-capability-unavailable`; reached-but-unknown id → `:rf.error/story-mcp-unknown-substrate`). Unknown `:active-modes` ids and `:cell-overrides` keys are refused the same way rather than silently dropped (`:rf.error/story-mcp-unknown-active-mode` / `:rf.error/story-mcp-unknown-cell-override-key`): the diagnostic names the offending RAW identifier and enumerates the accepted set, and a mixed known+unknown mode list rejects ATOMICALLY rather than running the known subset. An ABSENT run option still defaults — only a PRESENT-but-unknown identifier fails, because a dropped one would settle an ordinary `:status :pass` over a DIFFERENT scenario than the one requested. The headline is `:status` ∈ {:pass :fail :cannot-run :error}; the result also carries unified `:assertions` records (each with a derived `:status`), `:checks` groups, `:consumed-selectors`, the evidence-slot projections (`:schema-violations :warnings :effects :sub-runs :renders :narrative`), `:app-db`, `:snapshot`, and `:elapsed-ms`. Rendered output is NOT part of this result — `rf.story/render-variant` owns rendering and returns it as `:rendered`. `:cannot-run` means the runner could not even attempt the plan — handle it as 'not runnable here', NOT as a fail. The `:app-db` slot is routed through `re-frame.core/elide-wire-value` against the variant frame's `[:rf.runtime/elision]` runtime-db registry — declared-sensitive paths return `:rf/redacted` and oversize slots return the `:rf.size/large-elided` marker by default. The derived `:snapshot` and ALL evidence value-slots (`:schema-violations :warnings :effects :sub-runs :renders :narrative`) are PATH-projected on BOTH egress axes against the same frame classification. EP-0025 FAIL-OPEN: a value AT a classified path redacts, but a value RE-KEYED to a non-matching position (a `:snapshot` nested under `:db`, a `:narrative` beat's `:db-before` snapshot, a `:sub-runs` `:value`) ships RAW — value-match was removed; classify the app-db PATH to redact a value before a derived tree re-surfaces it. Pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture). "
                         "Examples: "
                         "1. Green run: {:variant-id \":story.cart/full\"} -> {:status :pass :frame :story.cart/full :app-db {...} :assertions [{:assertion :rf.assert/path-equals :passed? true :status :pass}] :checks [] :elapsed-ms 42}. "
                         "2. Red run: {:variant-id \":story.cart/bad\"} -> {:status :fail :assertions [{:assertion :rf.assert/sub-equals :passed? false :status :fail :actual nil :expected 3}]}. "
                         "3. Cannot-run (a causal assertion under a non-reactive runner): {:variant-id \":story.cart/caused\"} -> {:status :cannot-run :cannot-run [...] :assertions [{:status :cannot-run :cannot-run? true ...}]}. "
                         "4. Clamped timeout / error: {:variant-id \":story.slow/loader\" :timeout-ms 60000} -> runs with timeout clamped to 30000ms (max-timeout-ms ceiling); on overrun returns {:status :error :assertions [{:assertion :rf.error/run-failed :status :error ...}]}.")
    :typicalTokens  2000
    ;; `run-variant` ships the variant's `:app-db` re-keyed
    ;; into `:snapshot` and the evidence slots; structural dedup
    ;; collapses those three references into one cache slot at the wire
    ;; boundary. Mirrors pair-mcp's selective `:dedup` knob on
    ;; `snapshot` / `trace-window` (descriptors_data.cljs).
    :dedup-eligible? true
    :inputSchema {:type "object"
                  :properties (rf.story-mcp.tools.schemas/with-max-tokens
                                (rf.story-mcp.tools.schemas/with-dedup
                                  (rf.story-mcp.tools.schemas/with-include-sensitive
                                    (rf.story-mcp.tools.schemas/with-timeout-ms
                                      {:variant-id rf.story-mcp.tools.schemas/kw-or-string
                                       :substrate rf.story-mcp.tools.schemas/kw-or-string
                                       :active-modes {:type "array" :items rf.story-mcp.tools.schemas/kw-or-string}
                                       :cell-overrides {:type "object"}}))))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema rf.story-mcp.tools.schemas/default-output-schema
    :annotations  rf.story-mcp.tools.schemas/run-variant-annotations
    :handler     tool-run-variant}

   {:name           "snapshot-identity"
    :category       :testing
    :description    (str "Content-hash of (variant × resolved args × decorators × loaders × substrate × modes × cell-overrides). Stable across hosts; key for visual-regression. An explicit `:substrate` is validated, not silently dropped: it requires a REACHED substrate registry (unreachable on the JVM stdio host → `:rf.error/story-mcp-capability-unavailable`; reached-but-unknown id → `:rf.error/story-mcp-unknown-substrate`). Unknown `:active-modes` ids and `:cell-overrides` keys are refused the same way rather than silently dropped (`:rf.error/story-mcp-unknown-active-mode` / `:rf.error/story-mcp-unknown-cell-override-key`): the diagnostic names the offending RAW identifier and enumerates the accepted set, and a mixed known+unknown mode list rejects ATOMICALLY rather than hashing the known subset. An ABSENT run option still defaults — only a PRESENT-but-unknown identifier fails, because both slots are identity-BEARING here and a dropped one would return a visual-regression key for a DIFFERENT tuple. `:cell-overrides` perturbs the hash via the resolved `:effective-args` (Story's `resolve-args` merges them after mode args), so two cells differing only by an override get distinct `:content-hash` values. "
                         "Examples: "
                         "1. Bare: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :active-modes [] :substrate nil :content-hash \"sha256:abcd...\"}. "
                         "2. With substrate + mode: {:variant-id \":story.cart/full\" :substrate \":uix\" :active-modes [\":mode/dark\"]} -> different :content-hash from #1 because the tuple inputs differ. "
                         "3. With cell-overrides: {:variant-id \":story.cart/full\" :cell-overrides {:qty 9}} -> different :content-hash from #1 because the override changes the resolved args. "
                         "4. Use for cache key: build a visual-regression key as `(str variant-id \"@\" content-hash)`.")
    :typicalTokens  100
    :inputSchema {:type "object"
                  :properties (rf.story-mcp.tools.schemas/with-max-tokens
                                {:variant-id rf.story-mcp.tools.schemas/kw-or-string
                                 :substrate rf.story-mcp.tools.schemas/kw-or-string
                                 :active-modes {:type "array" :items rf.story-mcp.tools.schemas/kw-or-string}
                                 :cell-overrides {:type "object"}})
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema rf.story-mcp.tools.schemas/default-output-schema
    :annotations  rf.story-mcp.tools.schemas/read-only-annotations
    :handler     tool-snapshot-identity}

   {:name           "read-a11y-violations"
    :category       :testing
    :description    (str "READ the axe-core violations a variant's in-browser a11y panel has accumulated, from `re-frame.story.ui.a11y/violations-by-frame`. This tool does NOT execute axe-core — it is a diagnostic re-read of already-computed panel state (the sibling of `read-failures`), so calling it neither runs a fresh accessibility check nor proves the variant accessible; it returns whatever the in-browser panel last stored (possibly stale or empty). The `:violations` vec is LIVE RUNTIME DOM state — each axe-core node carries `:html` (the violating element's outerHTML), `:target` (CSS selectors) and `:failureSummary`, so a sensitive value rendered into the DOM lands verbatim in node `:html`. axe DOM nodes are an inherently RE-KEYED runtime payload class, scrubbed via the named `scrub-re-keyed-runtime` egress exception (rf2-jwggld): a live variant frame PATH-projects against its classification (EP-0025 FAIL-OPEN — a value rendered into a node `:html` is a RE-KEYED DOM position the classification path cannot reach, so it ships RAW; classify the app-db PATH to redact a value before it reaches the DOM), and a non-live frame ships the nodes raw under the documented carve-out (path-scrub is a no-op even live, so fail-closing would destroy the tool with zero leak-delta). Pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture). "
                         "Host boundary: the shipped JVM stdio server cannot read the CLJS panel atom, so it returns a machine-readable capability-unavailable error (`isError true`, `:rf.error :rf.error/story-mcp-capability-unavailable`) rather than a false-empty `{:violations []}` — an empty vec is reserved for a REACHED provider that reported no findings. "
                         "Examples: "
                         "1. JVM stdio server (no browser bridge): {:variant-id \":story.cart/full\"} -> {:isError true :content [{:text \"Capability unavailable: `read-a11y-violations` needs the a11y-panel-state provider...\"}] :structuredContent {:rf.error :rf.error/story-mcp-capability-unavailable :capability \"a11y-panel-state\" :tool \"read-a11y-violations\" :recovery :read-from-a-browser-local-story-host}}. "
                         "2. Browser-local state with findings: {:variant-id \":story.form/checkout\"} -> {:variant-id :story.form/checkout :violations [{:id \"label\" :impact \"critical\" :nodes [...]}]}. "
                         "3. Browser-local state, clean frame: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :violations []} — a reached provider that genuinely reported no violations.")
    :typicalTokens  500
    :inputSchema {:type "object"
                  :properties (rf.story-mcp.tools.schemas/with-max-tokens
                                (rf.story-mcp.tools.schemas/with-include-sensitive {:variant-id rf.story-mcp.tools.schemas/kw-or-string}))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema rf.story-mcp.tools.schemas/default-output-schema
    :annotations  rf.story-mcp.tools.schemas/read-only-annotations
    :handler     tool-read-a11y-violations}

   {:name           "read-failures"
    :category       :testing
    :description    (str "Accumulated assertion records for a variant frame (since the most recent `run-variant`), as UNIFIED records. Returns `{:status :total :failures :assertions}` — the same record shape `run-variant` emits: each record carries a derived `:status`, `:status` is the aggregate verdict over the records, and `:failures` is filtered to the genuine failure statuses (`:fail` / `:error`). A re-read of the accumulator, not a re-run — no epoch tape, so the status is the assertion-record aggregate only (re-run via `run-variant` for the full run verdict incl. the agreement floor). Assertion records carrying `:sensitive? true` are dropped at egress by default; pass `:include-sensitive true` to opt out (per spec/Tool-Pair.md §Direct-read privacy posture). "
                         "Examples: "
                         "1. Clean run: {:variant-id \":story.cart/full\"} -> {:variant-id :story.cart/full :status :pass :total 3 :failures [] :assertions [{:assertion :rf.assert/path-equals :passed? true :status :pass} ...]}. "
                         "2. Mixed pass/fail: {:variant-id \":story.cart/bad\"} -> {:variant-id :story.cart/bad :status :fail :total 5 :failures [{:assertion :rf.assert/sub-equals :passed? false :status :fail :reason \"...\"}] :assertions [...]}. "
                         "3. Registered but never-run variant: {:variant-id \":story.never/run\"} -> {:variant-id :story.never/run :status :pass :total 0 :failures [] :assertions []} (vacuously green — the id must be a REGISTERED variant that simply has no run yet; a genuinely unregistered id returns a `Variant not found` error, not an empty accumulator).")
    :typicalTokens  500
    :inputSchema {:type "object"
                  :properties (rf.story-mcp.tools.schemas/with-max-tokens
                                (rf.story-mcp.tools.schemas/with-include-sensitive
                                  {:variant-id rf.story-mcp.tools.schemas/kw-or-string}))
                  :required ["variant-id"]
                  :additionalProperties false}
    :outputSchema rf.story-mcp.tools.schemas/default-output-schema
    :annotations  rf.story-mcp.tools.schemas/read-only-annotations
    :handler     tool-read-failures}])

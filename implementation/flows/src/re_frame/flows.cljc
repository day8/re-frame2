(ns re-frame.flows
  "Flows — registered, runtime-toggleable computed-state declarations.
  Per Spec 013.

  A flow says: 'when these app-db paths change, run this pure function
  and write the result to that app-db path.' Flows evaluate on every
  event — as the runtime's OUTERMOST `:after` interceptor, so they fire
  LAST (after the handler and the rest of the `:after` chain, which
  reshapes the `:db` effect) — transforming the handler's pending `:db`
  effect, in topological order over their static dependency graph
  (per Spec 013 §Drain integration; rf2-u0zz5).

  Flows are deliberately a NICHE convenience — not a sub replacement,
  not a new dataflow paradigm. Use a sub if the value is consumed by
  views; use a flow only if it must live in app-db for SSR / time-travel
  / inspector reasons.

  Ships in `day8/re-frame2-flows`; entry points are published through
  `re-frame.late-bind` so the core artefact's `re-frame.core` re-exports
  reach them. Apps that don't register any flows don't pull the per-
  frame flow registry, the topological-sort engine, the dirty-check
  `last-inputs` map, or the outermost-`:after` `run-flows-on-db` walker.

  Public façade over `re-frame.flows.topo` (pure Kahn's + cycle-path
  extraction) and `re-frame.flows.registry` (per-frame `flows` +
  `last-inputs` atoms, `reg-flow` / `clear-flow`)."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.flows.registry :as registry]
            [re-frame.flows.topo :as topo]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]
            ;; EP-0014 slice-3 (rf2-s8w3nw): the JVM-only require of the
            ;; flows tooling sibling that backs the `flow-algebra-view`
            ;; alias at the foot of this ns. CLJS deliberately OMITS this
            ;; require so a CLJS app that loads the flows artefact but never
            ;; attaches a tool DCEs the tooling body wholesale — the facade
            ;; never reaches it. JVM has no bundle to protect; the alias
            ;; gives JVM tools / conformance fixtures the ergonomic
            ;; `re-frame.flows/flow-algebra-view` shape. Mirrors the
            ;; `re-frame.subs` → `re-frame.subs.tooling` JVM-only require
            ;; (rf2-bmzq0 / rf2-gge6mf slice-2).
            #?@(:clj [[re-frame.flows.tooling :as flows-tooling]])))

;; ---- public-surface re-exports -------------------------------------------
;;
;; Per rf2-4gvb4 — the registry atoms are private. External consumers
;; (test fixtures, conformance harnesses, cross-artefact integration tests)
;; reach the registry shape through the read accessors
;; (`flows-snapshot` / `last-inputs-snapshot`) and the existing reset fns
;; (`reset-flows!` / `reset-last-inputs!`). The facade re-exports both
;; pairs.
;;
;; EP-0015: `last-inputs-snapshot` returns the RAW cached input values
;; (owner-local app-db / runtime-db slices, possibly sensitive / large). It
;; is an internal / test / rollback seam — `^:no-doc`, NOT an egress
;; boundary. The raw dirty-check cache is never shipped off-box; tools and
;; direct reads that cross a trust boundary read the ELIDED trace path
;; (`:rf.flow/computed` / `:rf.flow/failed`, which ride `elide-inputs`).

(def flows-snapshot       registry/flows-snapshot)
(def ^:no-doc last-inputs-snapshot registry/last-inputs-snapshot)

(def reg-flow           registry/reg-flow)
(def clear-flow         registry/clear-flow)
(def reset-flows!       registry/reset-flows!)
(def reset-last-inputs! registry/reset-last-inputs!)

;; EP-0014 slice-3 (rf2-s8w3nw): the derivation/process algebra view of
;; registered flows. JVM-runnable (the flow registry is partition-agnostic
;; registration metadata), so a JVM convenience alias lets tools /
;; conformance fixtures reach it as `re-frame.flows/flow-algebra-view`
;; without naming the sibling. The body lives in `re-frame.flows.tooling`
;; so a CLJS app that loads the flows artefact but attaches no tool DCEs it
;; (the CLJS facade never `:require`s the tooling sibling — the require
;; above is `#?@(:clj ...)`-gated). CLJS consumers (Xray + conformance)
;; call `re-frame.flows.tooling/flow-algebra-view` directly. No
;; `re-frame.core` facade export (EP-0014 issue-1 disposition). Mirrors the
;; `re-frame.subs/sub-algebra-view` JVM alias (slice-2).
#?(:clj
   (def flow-algebra-view flows-tooling/flow-algebra-view))

;; ---- evaluation ---------------------------------------------------------
;;
;; Called from the router's OUTERMOST `:after` interceptor — fires LAST
;; (after the handler and the rest of the `:after` chain) — transforming
;; the pending `:db` effect before install and before :fx (per Spec 013
;; §Drain integration).

;; ---- partition-qualified input resolution (EP-0001 §535-551, rf2-4eisfr) --
;;
;; Mike RULED (b) 2026-06-09: flows read runtime-db via EXPLICIT partition-
;; qualified inputs — this IS EP-0001 normative text (§535-551), not a fresh
;; design call. The binary syntax (mayor refinement (i) — NO redundant
;; `[:rf.db/app …]` explicit-app form):
;;
;;   bare path            → app-db    e.g. [:user :first]
;;   [:rf.db/runtime …]   → runtime-db e.g. [:rf.db/runtime :rf.runtime/routing :current :route-id]
;;
;; ANY flow may read runtime-db this way (mayor refinement (ii) — user flows
;; AND framework flows); only the WRITE side is reserved (flow outputs land
;; in app-db only — see `evaluate-flow!`'s `assoc-in db (:output-path flow)`). The
;; resolver reads the SETTLED post-transition / post-macrostep PENDING frame-
;; state the flow transform was handed (app-db = the pending `:db` effect /
;; current app-db; runtime-db = the pending `:rf.db/runtime` effect / current
;; runtime-db) — so a runtime-only event (e.g. a pure `:rf.route/transitioned`
;; with no app-db change) still presents the changed route value here.
;;
;; A qualified runtime input never creates a spurious topo dependency edge:
;; `topo/depends-on?` compares a flow's `:inputs` against another flow's
;; OUTPUT `:output-path`, and outputs are always app-db paths (writes are reserved),
;; so a `[:rf.db/runtime …]`-rooted input can never prefix-match an output
;; `:output-path`. The dirty-check `:input-values` vector includes the resolved
;; runtime value, so the trigger keys on BOTH partitions by construction
;; (EP-0001 §542-544): a runtime-db change makes `new-inputs` differ from
;; the cached row, forcing recompute — it cannot be hidden merely because the
;; app-db partition was value-identical.

;; The partition-syntax primitives (`runtime-partition-key` / `runtime-input?`
;; / the strip via `input-resolve-path`) live ONCE in `re-frame.flows.registry`
;; (rf2-4vreu8): `registry` is required by both this facade and
;; `re-frame.flows.tooling` and requires neither back (no cycle), so it is the
;; natural shared home for the rule all three consumers key on.

(defn- resolve-input
  "Resolve one flow `:inputs` path against the pending frame-state's two
  partitions. A `[:rf.db/runtime …rest]` path reads `runtime-db` at `…rest`
  (the partition key stripped via the shared `registry/input-resolve-path`);
  a bare path reads `db` (app-db) verbatim."
  [db runtime-db path]
  (if (registry/runtime-input? path)
    (get-in runtime-db (registry/input-resolve-path path))
    (get-in db path)))

(defn- read-inputs
  "Read a flow's `:inputs` against the pending frame-state — `db` is the
  pending app-db partition, `runtime-db` the pending runtime-db partition.
  Bare paths read app-db; `[:rf.db/runtime …]` paths read runtime-db
  (EP-0001 §535-551, rf2-4eisfr)."
  [db runtime-db flow]
  (mapv (fn [path] (resolve-input db runtime-db path)) (:inputs flow)))

(defn- elide-inputs
  "Walk a flow's just-read input values through `elision/elide-wire-value`,
  each under its own declared input db-path so per-path `:sensitive` /
  `:large` declarations apply (Spec 009 §Size elision in traces). The
  single home for the input-value elision the `:rf.flow/computed` success
  payload and the `:rf.flow/failed` failure payload both ride — the trace
  bus is the wire boundary on both paths, so a flow reading a large or
  sensitive input must not surface it raw on either.

  rf2-p44r3u: the `:path` opt seeds which declaration `elide-wire-value`
  matches the value against, so it MUST be the DECLARATION-COORDINATE path,
  not the raw `:inputs` path. For a runtime-db-qualified input
  `[:rf.db/runtime …rest]` (rf2-4eisfr) the value is read from runtime-db at
  `…rest` and the elision registry keys its declaration at that STRIPPED
  `…rest` path (the registry is partition-blind — `add-marks` / schema store
  bare path vectors). Seeding the walk with the raw `[:rf.db/runtime …]`
  path therefore never matched the declaration and surfaced the input value
  RAW even though the same slot's derived output was correctly redacted. We
  normalize each input path through the SAME `registry/input-resolve-path`
  the flow-output propagation path already uses (`input-overlaps-declaration?`),
  so success and failure trace input values elide against the stripped
  runtime declaration path. A bare app-db input is unchanged by the
  resolver, so this is a no-op for the common (app-db) case.

  Callers gate this behind their own outer `interop/debug-enabled?` so the
  walk is DCE'd in CLJS production (rf2-drr4z); this fn does not re-gate."
  [frame-id flow input-values]
  (mapv (fn [input-path v]
          (elision/elide-wire-value
            v {:frame frame-id :path (registry/input-resolve-path input-path)}))
        (:inputs flow)
        input-values))

(defn- validate-output!
  "Dev-only validation of a flow's computed `:derive`-output value against its
  optional `:schema` (Spec 013 §The registration shape — \"Malli schema
  for the output value (dynamic-host validation in dev)\"). Per
  rf2-ee38b.9 this is what makes the `:schema` flow-map key load-bearing
  rather than inert metadata.

  Routes through the schemas artefact's `:schemas/validate-with-registered-fn`
  / `:schemas/explain-with-registered-fn` late-bind seam — the SAME seam
  the routing artefact validates `:params` / `:query` through. Flows
  never statically `:require` re-frame.schemas (it is optional per Spec
  Conventions §Feature modularity), so the validation soft-passes when:
    - the flow declares no `:schema`;
    - the schemas artefact is not loaded (hook absent);
    - no validator is registered (the registered validator soft-passes).

  Observational, NOT a rollback (Spec 013 §\"Observational, not a
  rollback\"). A flow's output is materialised state that downstream
  flows in the same drain may already have read as their input by the
  time a violation could be observed, so retroactively unwinding one
  flow's write mid-walk would leave an inconsistent pending `:db`
  effect. So — like `validate-app-schema!`, which is also
  dev-only — the value IS still written; the failure surfaces as a
  diagnostic `:rf.error/schema-validation-failure :where :flow-output`
  trace (`:recovery :no-recovery`, matching the category's documented
  recovery) on the error path, and the flow proceeds. This is the
  masterpiece choice over dropping the key: the examples Flows exemplar
  and the schemas artefact both exist, so an inert spec'd key would be a
  gap, not restraint.

  Gated by the caller's outer `interop/debug-enabled?` so the whole
  surface DCEs in CLJS production (Spec 009 §Production builds)."
  [frame-id flow new-output]
  (when-let [schema (:schema flow)]
    (when-let [validate (late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
      (when-not (validate schema new-output)
        (let [explain     (late-bind/get-fn-cached :schemas/explain-with-registered-fn)
              explanation (when explain (explain schema new-output))
              ;; rf2-o69h5 — the SHARED schema-aware redaction seam. When the
              ;; flow's output schema marks any slot `:sensitive?` this scrubs
              ;; the value-bearing slots (`:value` / `:explain` /
              ;; `:explain-humanized`) to `:rf/redacted` and stamps
              ;; `:sensitive? true`. Closes the a5kzs#1 class on `:where
              ;; :flow-output`: the prior code path-elided `:value` (handling
              ;; `:large?` / per-path sensitive declarations) but left
              ;; `:explain` carrying the failing value verbatim under Malli's
              ;; path-shaped output — a re-leak for a `:sensitive?`-marked
              ;; output schema. The `:value` elision-walk stays the BASE
              ;; (`:large?` handling, per-path declarations); the seam scrubs
              ;; the rest when the schema declares sensitivity.
              redact      (late-bind/get-fn-cached :schemas/redact-validation-tags)
              tags        {:category   :rf.error/schema-validation-failure
                           :where      :flow-output
                           :rf.flow/id (:id flow)
                           :failing-id (:id flow)
                           :schema-id  (:id flow)
                           :path       (:output-path flow)
                           ;; Wire-bearing — ride through the elision
                           ;; walker so a large / sensitive output value
                           ;; does not surface raw on the trace bus
                           ;; (symmetric with the `:rf.flow/computed`
                           ;; `:result` slot).
                           :value      (elision/elide-wire-value
                                         new-output
                                         {:frame frame-id :path (:output-path flow)})
                           :explain    explanation
                           :reason     (str "Flow " (:id flow)
                                            " output failed schema "
                                            (pr-str schema) ".")
                           :recovery   :no-recovery
                           :frame      frame-id}]
          (trace/emit-error! :rf.error/schema-validation-failure
                             (cond-> tags
                               redact (->> (redact schema)))))))))

(defn- evaluate-flow!
  "Evaluate one flow against the pending frame-state. `db` is the pending
  app-db partition (the cascade accumulator threaded through prior flows'
  writes); `runtime-db` is the pending runtime-db partition (read-only — flow
  WRITES are app-db only, EP-0001 §535-540). Returns `[new-db dirty?]` on
  successful evaluation (skip or recompute); on the failure path the
  exception re-throws after the `:rf.flow/failed` trace fires.

  Failed-flow contract (Spec 013 §Failure semantics — atomicity
  contract, Mike 2026-05-24): the failing flow's own output is NOT
  written (the throw happened during `:derive`; there is no usable
  new-output). This fn re-throws an ex-info carrying `:rf.flow/failed-id`
  so the router can attribute the cascade-level
  `:rf.error/flow-eval-exception` (Spec 009 §Error contract) to this
  flow; the throw propagates straight out of `run-flows-on-db`. A flow
  throw is a PRE-INSTALL throw: the router's flows-after-interceptor
  DISCARDS the pending `:db` effect, so app-db is left UNCHANGED — no
  partial commit, no prior-flow writes installed, no `:rf.event/db-changed`.
  The cascade halts at the failing flow — downstream flows scheduled
  later in topo order do NOT run on this drain; they re-attempt naturally
  on the next drain.

  Hot path — runs as part of the flow-transform `:after` at the outermost
  position of every event's interceptor chain, before the deferred `:db`
  install. Invoked once per registered flow per event.
  Trace payload construction sits inside an `interop/debug-enabled?`
  outer gate so the elision walker (`elide-wire-value`) is not invoked
  in CLJS production builds (per Spec 009 §Production builds, rf2-drr4z
  perf slice). Future editors: keep the gate OUTERMOST on each emit
  site and keep wire-value walks INSIDE it — Closure DCE folds the
  whole branch under `:advanced` + `goog.DEBUG=false`."
  [frame-id db runtime-db flow]
  (let [flow-id    (:id flow)
        new-inputs (read-inputs db runtime-db flow)
        ;; Per rf2-94ol5 dirty-check storage is PER-FRAME: read this
        ;; flow's last-seen inputs from THIS frame's own `last-inputs`
        ;; container. Per-frame dirty-check windows stay independent by
        ;; construction — the read can't observe a sibling frame's row.
        old-inputs (registry/get-frame-flow-last-inputs frame-id flow-id)]
    (if (= new-inputs old-inputs)
      (do
        ;; Per Spec 009 §:op-type vocabulary: `:rf.flow/skip` records a
        ;; value-equal recompute suppression. Tools use this to surface
        ;; "flow ran but inputs were stable" — distinct from "flow
        ;; didn't fire at all". Outer `debug-enabled?` gate elides the
        ;; tag-map construction in prod.
        (when interop/debug-enabled?
          ;; `:input-paths-unchanged` (rf2-931pm) names every input db-path
          ;; whose value was `=` to the previous run — the cascade DAG
          ;; consumer reads this to render the "considered, no recompute"
          ;; branch dimmed. For a value-equal skip every input is by
          ;; definition unchanged; we ship the full input-path vector.
          (trace/emit! :flow :rf.flow/skip
                       {:flow-id                flow-id
                        :reason                 :inputs-value-equal
                        :input-paths-unchanged  (:inputs flow)
                        :frame                  frame-id}))
        [db false])
      (try
        ;; rf2-hhh92: wall-clock the flow's `:derive` recompute (dev-only)
        ;; so `:rf.flow/computed` carries `:elapsed-ms` — the per-op
        ;; duration the Trace panel's DURATION column reads. The `now-ms`
        ;; brackets ride `interop/debug-enabled?` (nil t0 in prod) so
        ;; Closure DCEs them under :advanced + `goog.DEBUG=false`.
        (let [t0         (when interop/debug-enabled? (interop/now-ms))
              new-output (apply (:derive flow) new-inputs)
              flow-elapsed-ms (when interop/debug-enabled?
                                (- (interop/now-ms) t0))
              ;; Per rf2-qlzh4: capture the pre-write value at the
              ;; flow's `:output-path` BEFORE we assoc-in the new output.
              ;; This becomes the `:before` slot on the
              ;; `:rf.flow/computed` trace below — consumers (Xray
              ;; Event Detail, re-frame-10x flow panel) no longer
              ;; need to walk the epoch's `:db-before` snapshot to
              ;; render "wrote [:cart :total] 47.50 -> 52.50". The
              ;; read happens against `db` (the loop accumulator that
              ;; includes prior flows' writes in this drain), so a
              ;; flow that reads (via `:inputs`) a slot an UPSTREAM
              ;; flow wrote sees that upstream write — the correct
              ;; cascade-local semantics. The `:before` at the flow's
              ;; OWN `:output-path`, however, can only ever reflect the
              ;; handler's / pre-existing value, NEVER another flow's
              ;; write: the rf2-um6d9/#3086 invariant
              ;; (`detect-output-path-overlap!`, wired into `reg-flow`)
              ;; rejects any two same-frame flows whose output `:output-path`s
              ;; stand in a prefix relationship, so no upstream flow can
              ;; have written this flow's `:output-path` earlier in the drain
              ;; (per Spec 013 §Disjoint output paths). Gated to dev-only
              ;; so the read is DCE'd under `:advanced` + `goog.DEBUG=false`.
              old-output (when interop/debug-enabled?
                           (get-in db (:output-path flow)))
              new-db     (assoc-in db (:output-path flow) new-output)]
          ;; Advance the dirty-check row in THIS frame's own `last-inputs`
          ;; container (rf2-94ol5) — frame-local, can't touch a sibling.
          (registry/set-frame-flow-last-inputs! frame-id flow-id new-inputs)
          ;; Per Spec 009 §:op-type vocabulary: `:rf.flow/computed`
          ;; records a successful recompute. The dirty-check is
          ;; `=`-equality so this only fires when inputs actually
          ;; changed.
          ;;
          ;; Wire-bearing payloads (`:input-values`, `:result`,
          ;; `:before`) ride through `elision/elide-wire-value` per
          ;; Spec 009 §Size elision in traces — the walker is the
          ;; single normative emission site for `:rf.size/large-
          ;; elided` (and the `:rf/redacted` privacy sentinel).
          ;; Without this the flow trace bypassed the elision
          ;; contract that every other wire-emitting surface honours;
          ;; a flow reading or producing a large or sensitive value
          ;; would surface raw on the trace bus. Off-box defaults
          ;; match `event-emit` / `error-emit`.
          ;;
          ;; The `:path` opt on each walker call names where in the
          ;; slice's root the wrapped value lives — `:result` and
          ;; `:before` BOTH live at the flow's output path; each
          ;; `:input-values` entry is the value at the matching input
          ;; path. The walker reads `[:rf.runtime/elision
          ;; :declarations <path>]` and emits the marker for
          ;; frame-declared large slots.
          ;;
          ;; Outer `interop/debug-enabled?` gate keeps the elision
          ;; walker out of CLJS prod builds (rf2-drr4z) — Closure
          ;; constant-folds the whole branch under `:advanced` +
          ;; `goog.DEBUG=false`.
          (when interop/debug-enabled?
            (trace/emit! :flow :rf.flow/computed
                         {:flow-id      flow-id
                          :input-values (elide-inputs frame-id flow new-inputs)
                          :before       (elision/elide-wire-value
                                          old-output
                                          {:frame frame-id :path (:output-path flow)})
                          :result       (elision/elide-wire-value
                                          new-output
                                          {:frame frame-id :path (:output-path flow)})
                          :path         (:output-path flow)
                          :elapsed-ms   flow-elapsed-ms
                          :frame        frame-id})
            ;; Per rf2-ee38b.9 — dev-only output-schema validation. Runs
            ;; AFTER the `:rf.flow/computed` emit so the computed value is
            ;; already on the trace stream when a violation surfaces;
            ;; observational (the write at `new-db` stands). Sits inside
            ;; this outer `debug-enabled?` gate so the whole surface DCEs
            ;; in CLJS prod alongside the trace emit.
            (validate-output! frame-id flow new-output))
          [new-db true])
        (catch #?(:clj Throwable :cljs :default) e
          ;; Per Spec 009 §:op-type vocabulary: :rf.flow/failed fires
          ;; when the flow's :derive fn throws. last-inputs is NOT
          ;; advanced — so the flow will retry on the next drain rather
          ;; than silently caching a stale-or-missing output. We re-throw
          ;; an ex-info carrying `:rf.flow/failed-id` (Spec 013 §Failure
          ;; semantics — atomicity contract); it propagates through
          ;; `run-flows-on-db` to the router's flows-after-interceptor,
          ;; which DISCARDS the pending `:db` effect (no partial commit —
          ;; app-db unchanged) and emits the cascade-level
          ;; :rf.error/flow-eval-exception per Spec 009 §Error contract.
          ;; The per-flow `:rf.flow/failed` trace emitted here adds the
          ;; flow-attributed detail tools (10x flow panel) consume.
          ;;
          ;; The failure-path `:inputs` payload rides through the
          ;; elision walker (`elide-inputs`) for the same reason as the
          ;; success path — the value that triggered the throw may itself
          ;; be a large or sensitive blob, and the trace bus is the wire
          ;; boundary. Outer debug-enabled? gate elides the walk in CLJS
          ;; prod (rf2-drr4z).
          ;; rf2-iqh5yf: emit a STRUCTURED, EDN-safe exception summary —
          ;; `:exception-message` (the plain message string) + `:exception-data`
          ;; (the ex-info ex-data map, or nil) — NEVER the raw Throwable. A bare
          ;; Throwable is not EDN-serializable and the central trace projection
          ;; switch (`re-frame.marks/project-trace-event`) has no branch for a
          ;; bare `:ex` slot, so before this fix the raw object reached trace
          ;; delivery, epoch capture, and tooling listeners unprojected — and a
          ;; flow `:derive` throwing `ex-info` with app secrets in `ex-data`
          ;; (or an interpolated message) leaked them off-box. The summary
          ;; shape matches every other `:rf.error/*` category in the Spec 009
          ;; catalogue (`:exception-message`; machine adds `:exception-data`);
          ;; `project-trace-event` redacts the `:exception-data` slot
          ;; fail-closed when the flow's frame declares any sensitivity (the
          ;; flows analogue of `project-machine-error-tags`). The cascade-level
          ;; `:rf.error/flow-eval-exception` (router) retains the live
          ;; `:exception` for stack-trace introspection on its OWN error row.
          (when interop/debug-enabled?
            (trace/emit! :flow :rf.flow/failed
                         {:flow-id           flow-id
                          :exception-message #?(:clj (.getMessage ^Throwable e)
                                                :cljs (.-message e))
                          :exception-data    (ex-data e)
                          :inputs            (elide-inputs frame-id flow new-inputs)
                          :frame             frame-id}))
          ;; Per rf2-je5p8: wrap the throw in an ex-info carrying the
          ;; flow-attribution slot `:rf.flow/failed-id`.
          ;; The router's `flows-after-interceptor` catch stashes the
          ;; throw under `:rf/flow-error`, then `emit-flow-eval-exception!`
          ;; reads `:rf.flow/failed-id` off the ex-data and stamps
          ;; `:flow-id` into the substrate record's `:tags` so ops in
          ;; CLJS production (where `:rf.flow/failed` DCEs) can attribute
          ;; the cascade-level `:rf.error/flow-eval-exception` to a
          ;; specific flow. Attribution is `:flow-id`-only: there is no
          ;; real flow VALUE to carry, so the contract carries the id and
          ;; nothing more (per Spec 013 §Failure semantics / §Resolved
          ;; decisions). The failing frame is already in scope at the
          ;; router boundary (it is the frame being drained), so it is not
          ;; duplicated here. Symmetric with Spec 013 §Failure semantics
          ;; rule 4: the per-flow trace fires first with flow attribution;
          ;; the cascade-level error preserves the same attribution at the
          ;; substrate boundary.
          ;;
          ;; rf2-cjr635: route through the canonical thrown-error builder
          ;; (`error/throw-error!`, Spec 009 §The thrown-error shape) like
          ;; every OTHER flows throw (`topo.cljc` cycle / overlap,
          ;; `registry.cljc` frame-not-live). The hand-rolled re-throw
          ;; carried ONLY `:rf.flow/failed-id` — no `:rf.error/id`, no
          ;; `:where`, no `:recovery` — so a tool reading
          ;; `(:rf.error/id (ex-data e))` got nil (a Machine-readable-errors
          ;; violation), and its `(or <native-msg> ":rf.error/…")` message
          ;; form dodged `check_thrown_error_messages.py` so it could
          ;; silently regress to the bare-keyword shape. The builder DERIVES
          ;; the human message from `:reason` + the `[:rf.error/<id>]` token
          ;; and stamps the canonical `{:rf.error/id :where :recovery
          ;; :reason}` ex-data. The flow-attribution slot rides in `:extra`;
          ;; the original exception rides in `:extra :cause` for stack-trace
          ;; introspection (the router surfaces the rethrown ex-info ITSELF
          ;; as the `:exception` slot of `:rf.error/flow-eval-exception`).
          (error/throw-error!
            :rf.error/flow-eval-exception
            'rf/run-flows-on-db
            (str "a flow's :derive fn threw while recomputing flow "
                 (pr-str flow-id)
                 " during the drain; the event aborts before the :db install "
                 "(no partial commit, app-db unchanged) and the flow "
                 "re-attempts on the next drain. Fix the :derive fn so it "
                 "does not throw on the inputs it is given.")
            {:recovery :no-recovery
             :extra    {:rf.flow/failed-id flow-id
                        :cause             e}}))))))

(defn run-flows-on-db
  "Per Spec 013 §Drain integration (rf2-u0zz5) + EP-0001 §535-551
  (rf2-4eisfr): walk THIS FRAME'S registered flows in topological order over
  the pending frame-state, dirty-check each one, recompute and assoc-in the
  result into a transformed APP-DB. Returns the flow-augmented APP-DB value.

  Signature `[frame-id db runtime-db]` — the full pending frame-state. `db`
  is the pending app-db partition; `runtime-db` the pending runtime-db
  partition (pass `nil` to resolve only bare app-db inputs). The router's
  flows-after-interceptor always supplies both partitions.

  EP-0001 §535-551 (Mike RULED (b) 2026-06-09): a flow reads app-db by
  default (bare `:inputs` paths) and runtime-db only through EXPLICIT
  partition-qualified inputs `[:rf.db/runtime …]` (binary syntax — there is
  NO redundant `[:rf.db/app …]` form). ANY flow — user or framework — may
  read runtime-db this way; only the WRITE side is reserved. Flow outputs
  write ONLY app-db (the cascade threads and returns the APP-DB partition;
  runtime-db is read-only for the whole pass). The trigger / dirty-check
  keys on BOTH partitions: a runtime-db change shows up in the resolved
  `:input-values`, so a runtime-only event (e.g. a pure
  `:rf.route/transitioned`) recomputes a route-reading flow even when app-db
  is value-identical (EP-0001 §542-544 — a runtime write cannot be hidden
  from a flow merely because the app-db partition did not change).

  This is the **outermost-`:after` flow transform**. The router installs
  it as the OUTERMOST `:after` interceptor (re-frame.router/flows-after-
  interceptor), so it fires LAST — after the event handler AND the rest
  of the `:after` chain (which reshapes the `:db` effect, e.g. the `path`
  std-interceptor splicing the handler's slice back into the full db) —
  against the chain's PENDING `:db` effect (or the current app-db value
  when the handler returned no `:db`), and BEFORE the `:db` install — NOT
  against the already-installed app-db. The caller writes the returned
  value back into the chain context's `:effects :db` slot so the eventual
  `:db` install observes the flow-augmented db.

  Flows are frame-scoped — only flows registered against frame-id run
  here, leaving sibling frames' flows untouched.

  Failed-flow contract (Spec 013 §Failure semantics — atomicity
  contract, Mike 2026-05-24): a flow throw is a PRE-INSTALL throw, so it
  aborts the WHOLE event. There is NO partial commit — the pending `:db`
  effect (the handler's write plus any prior successful flows' writes) is
  DISCARDED by the router's `flows-after-interceptor` catch, so app-db is
  left UNCHANGED. This fn therefore does NOT carry a partial-db on the
  re-thrown ex-info: it would have no consumer. The cascade halts (flows
  scheduled later in topo order do not run on this drain) and the router
  surfaces the cascade-level `:rf.error/flow-eval-exception`.

  Atomicity extends to the dirty-check bookkeeping: `evaluate-flow!`
  advances THIS frame's `last-inputs` container for each flow it computes,
  but on a throw NOTHING is installed — so a prior flow's advanced
  `last-inputs` would (wrongly) suppress its recompute next drain even
  though its output never reached app-db, silently losing the write
  forever. So we SNAPSHOT this frame's `last-inputs` before the walk and
  RESTORE it on a throw: every flow — prior-successful and failing alike —
  re-attempts cleanly on the next drain, matching the all-or-nothing `:db`
  install. The `:rf.flow/failed-id` slot (stamped by `evaluate-flow!`) is
  preserved on the re-thrown ex-info so the router can attribute the
  cascade-level error to the failing flow.

  Per rf2-94ol5 the snapshot / restore is scoped to the DRAINING frame's
  OWN `last-inputs` container — `frame-id`'s atom, not a global. A sibling
  frame draining concurrently on another JVM thread holds a different atom,
  so this rollback cannot clobber its just-advanced dirty-check rows. The
  per-frame-independence invariant (Spec 002 rule 1 / Spec 013
  §Frame-scoping) holds by construction, not by careful keying."
  [frame-id db runtime-db]
  (let [flow-map (get (registry/flows-snapshot) frame-id)]
    (if-not (seq flow-map)
      db
      (let [ordered (topo/topo-sort flow-map)
            ;; Snapshot ONLY the draining frame's dirty-check container so a
            ;; flow throw can roll back the frame's own advances — the event
            ;; aborts, so prior flows' `last-inputs` advances must NOT
            ;; survive (their outputs were never installed). Scoped to
            ;; `frame-id` (rf2-94ol5) so a concurrently-draining sibling
            ;; frame is structurally untouched. Restored in the catch below.
            last-inputs-before (registry/frame-last-inputs-snapshot frame-id)
            ;; rf2-gdzv6o: snapshot the frame's flow output-declaration elision
            ;; slots BEFORE the refresh below mutates them. The refresh writes
            ;; runtime-db IMMEDIATELY (`swap-elision-slot!`), but a flow throw
            ;; is a PRE-INSTALL throw that aborts the WHOLE event — so the
            ;; refresh's runtime-db write must be rolled back too, or app-db
            ;; rolls back while runtime-db elision declarations survive,
            ;; violating the all-or-nothing two-partition contract (Spec
            ;; 013:284,288 — a pre-install flow throw leaves BOTH partitions
            ;; unchanged). Frame-scoped (rf2-94ol5); restored in the catch.
            decls-before       (registry/flow-output-declarations-snapshot frame-id)
            ;; rf2-z980k8: DRAIN (read-and-clear) this frame's pending abandoned
            ;; output paths — recorded by an IN-DRAIN same-frame `reg-flow`
            ;; `:output-path` move (a direct app-db write would be clobbered by the
            ;; deferred commit). `abandoned-before` is the snapshot the catch /
            ;; post-commit rollback re-records (the move re-attempts next drain
            ;; if the event aborts); `abandoned-paths` is the SAME set, consumed
            ;; here exactly once. They are dissoc'd from the pending `:db` below,
            ;; BEFORE the flow walk, so the vacate rides the value the deferred
            ;; commit publishes (the handler's returned `:db` cannot resurrect
            ;; the old path) and the new flow's recompute lands on a clean slot.
            ;; Frame-scoped (rf2-94ol5).
            abandoned-before   (registry/abandoned-output-paths-snapshot frame-id)
            abandoned-paths    (registry/drain-abandoned-output-paths! frame-id)]
        ;; rf2-ihfz9o: refresh each flow's output data-classification
        ;; declarations BEFORE the flow walk so a propagated (input-
        ;; inherited) sensitive mark is in the frame's elision registry
        ;; when the t2 `:rf.event/db-pending-post-flow` trace and the
        ;; `:rf.flow/computed` `:result` slot project (Spec 015:568).
        ;; This is the MARK-MUTATION-aware refresh flows need — a flow
        ;; does not recompute on a mark-only `add-marks` / `set-marks` /
        ;; schema change, so without a drain-time refresh a sensitive
        ;; mark added AFTER reg-flow would never reach the flow output.
        ;; Topo-ordered inside the helper so a flow reading an upstream
        ;; flow's `:output-path` inherits the upstream's refreshed mark.
        ;;
        ;; NOT gated on `interop/debug-enabled?`: the elision
        ;; declaration registry feeds production-survivor OFF-BOX egress
        ;; (the epoch projection `:epoch/projected-record` ships records
        ;; off-box even under `:advanced`), so the privacy mark must
        ;; exist regardless of dev/prod. The cost is bounded — a pure-
        ;; data topo-fold plus two map reads, with NO runtime-db write
        ;; on the steady state (the helper compares first and writes
        ;; only when the resolved declarations actually changed). It
        ;; touches ONLY the runtime-db elision registry, never app-db,
        ;; so it cannot perturb the cascade value or app-db subs.
        ;;
        ;; rf2-gdzv6o: this write is INSIDE the `try` so the catch arm can
        ;; roll it back. It is staged-before-walk for the trace/projection
        ;; reasons above, but a downstream flow throw must unwind it — the
        ;; refresh is part of the event's two-partition transaction, not a
        ;; commit-before-the-walk side effect.
        (try
          (registry/refresh-flow-output-declarations! frame-id)
          ;; The drain threads ONLY the transformed APP-DB `db` — the loop is
          ;; a pure left-fold over the topo-ordered flows. `runtime-db` is the
          ;; pending runtime-db partition, read-only for the WHOLE pass: flow
          ;; outputs write app-db only (EP-0001 §539 — runtime writes
          ;; reserved), and a flow's qualified `[:rf.db/runtime …]` inputs all
          ;; resolve against the SAME pending runtime-db snapshot (no cascade-
          ;; local runtime mutation to thread). `evaluate-flow!` returns a
          ;; `[new-db dirty?]` tuple but the cascade has no consumer for the
          ;; dirty flag: the router writes the returned db value straight into
          ;; the `:db` effect slot regardless. So discard the second slot here
          ;; (the per-flow `:rf.flow/skip` / `:rf.flow/computed` traces carry
          ;; the dirty/clean signal tools actually read).
          (loop [remaining ordered
                 ;; rf2-z980k8: vacate the IN-DRAIN abandoned output paths from
                 ;; the pending `:db` FIRST — before any flow computes — so the
                 ;; old path is gone from the value the deferred commit
                 ;; publishes (the handler's returned `:db` carried it, but this
                 ;; transform is applied to that SAME value, so it cannot
                 ;; resurrect it) and a flow newly owning the slot recomputes
                 ;; onto a clean base. Each `vacate-path-in-db` is a pure
                 ;; `db → db` LEAF dissoc (no-op-safe). No-op set ⇒ `db`
                 ;; unchanged. (`abandoned-paths` was read-and-cleared above.)
                 db        (reduce registry/vacate-path-in-db db abandoned-paths)]
            (if (empty? remaining)
              db
              (let [flow         (flow-map (first remaining))
                    [new-db _]   (evaluate-flow! frame-id db runtime-db flow)]
                (recur (rest remaining) new-db))))
          (catch #?(:clj Throwable :cljs :default) e
            ;; Atomicity contract: discard ALL flow side-effects of this
            ;; aborted drain. The pending `:db` effect is dropped by the
            ;; router; here we restore THIS frame's pre-drain `last-inputs`
            ;; (its own container only — rf2-94ol5) so every flow on this
            ;; frame re-attempts next drain while sibling frames are
            ;; untouched. rf2-gdzv6o: ALSO restore the frame's flow output-
            ;; declaration elision slots — the pre-walk
            ;; `refresh-flow-output-declarations!` wrote runtime-db directly,
            ;; and a pre-install flow throw must leave runtime-db (not just
            ;; app-db) EXACTLY unchanged (Spec 013:284,288). Both restores are
            ;; frame-scoped (rf2-94ol5): sibling frames untouched. The throw
            ;; (carrying `:rf.flow/failed-id` from `evaluate-flow!`) propagates
            ;; unchanged for router attribution.
            (registry/reset-frame-last-inputs-to! frame-id last-inputs-before)
            (registry/restore-flow-output-declarations! frame-id decls-before)
            ;; rf2-z980k8: re-record the IN-DRAIN abandoned output paths drained
            ;; above. A flow throw aborts the event — the pending `:db` (which
            ;; carried the vacated state) is discarded — so the path move must
            ;; re-attempt next drain rather than be silently lost. Frame-scoped
            ;; (rf2-94ol5). The post-COMMIT rollback mirror lives in
            ;; `commit-frame-effects!` via `:flows/restore-abandoned-paths!`.
            (registry/restore-abandoned-output-paths! frame-id abandoned-before)
            (throw e)))))))

;; ---- late-bind hook registration ----------------------------------------
;;
;; re-frame.core, re-frame.fx, re-frame.router and re-frame.test-support
;; need to call into flows but ship in the core artefact — they cannot
;; `:require` this namespace because the flows artefact is optional
;; (apps that don't register flows don't carry it). Publish entry
;; points through the late-bind hook registry; consumers look the fns
;; up at call time.
;;
;; Calls are written as literal `set-fn!` invocations with a literal
;; keyword (one per line) — the late-bind drift gate
;; (`re-frame.late-bind-drift-test`) detects each publication via regex
;; over `implementation/**/src/**`, matching every other artefact's
;; publication block (schemas / machines / routing / http / ssr).

(late-bind/set-fn! :flows/reg-flow           reg-flow)
(late-bind/set-fn! :flows/clear-flow         clear-flow)
(late-bind/set-fn! :flows/run-flows-on-db    run-flows-on-db)
(late-bind/set-fn! :flows/reset-last-inputs! reset-last-inputs!)
(late-bind/set-fn! :flows/reset-flows!       reset-flows!)
;; Per rf2-4wqu6 finding 1 — the dirty-check-rollback pair the router uses
;; to keep this frame's `last-inputs` bookkeeping aligned with the DURABLE
;; app-db across a POST-commit schema/machine-data rollback. The flow
;; transform runs inside the chain and eagerly advances `last-inputs` for
;; each computed flow; but the rollback decision lands AFTER the chain, in
;; `commit-db-effect!`, OUTSIDE `run-flows-on-db`'s own throw-path
;; snapshot/restore. Without these hooks a rolled-back commit restores
;; app-db to `db-before` but leaves the advanced `last-inputs` rows, so the
;; next clean drain sees `=`-equal inputs, SKIPS the flow, and the flow
;; output never re-materialises — a deterministic dev/test failure path can
;; permanently suppress a flow. The router snapshots the draining frame's
;; rows BEFORE the flow transform and, on a rollback, restores them — the
;; exact mirror of the in-`run-flows-on-db` throw-path rollback, just at the
;; post-commit boundary the flows artefact cannot see. Both delegate to the
;; SAME frame-scoped registry primitives the throw path uses (rf2-94ol5):
;; structurally incapable of touching a sibling frame's container.
(late-bind/set-fn! :flows/snapshot-last-inputs registry/frame-last-inputs-snapshot)
(late-bind/set-fn! :flows/restore-last-inputs!  registry/reset-frame-last-inputs-to!)
;; rf2-z980k8 — the abandoned-output-paths rollback pair, the exact mirror of
;; the `last-inputs` pair above but for the IN-DRAIN `reg-flow` `:output-path`-move
;; vacate. `run-flows-on-db` DRAINS (read-and-clears) the pending abandoned
;; paths and dissocs them from the pending `:db`; its OWN throw arm re-records
;; them. But a POST-commit schema / machine-data rollback lands AFTER the
;; chain, in `commit-frame-effects!`, OUTSIDE `run-flows-on-db` — and discards
;; the pending `:db` (which carried the vacated state). Without these hooks the
;; abandoned path would be drained-and-cleared yet never durably vacated, so
;; the move is silently lost. The router snapshots the pending set BEFORE the
;; flow transform and, on a rollback, re-records it — the same boundary the
;; `last-inputs` mirror covers. Frame-scoped (rf2-94ol5).
(late-bind/set-fn! :flows/snapshot-abandoned-paths registry/abandoned-output-paths-snapshot)
(late-bind/set-fn! :flows/restore-abandoned-paths!  registry/restore-abandoned-output-paths!)
;; Per rf2-wbtjn — frame-destroy teardown hook (symmetric with the
;; machines `:machines/teardown-on-frame-destroy!` hook landed by
;; rf2-vsigt). `frame/destroy-frame!` invokes this hook so per-frame
;; flow-registry entries, the matching `last-inputs` rows, and any
;; `:flow` registrar slots whose last owning frame was destroyed all
;; clear in one step. Without the hook a long-running SSR JVM (per-
;; request frame churn) leaks flow state indefinitely.
(late-bind/set-fn! :flows/teardown-on-frame-destroy!
                   registry/teardown-on-frame-destroy!)

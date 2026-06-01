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
            [re-frame.flows.registry :as registry]
            [re-frame.flows.topo :as topo]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]))

;; ---- public-surface re-exports -------------------------------------------
;;
;; Per rf2-4gvb4 — the registry atoms are private. External consumers
;; (test fixtures, conformance harnesses, cross-artefact integration tests)
;; reach the registry shape through the read accessors
;; (`flows-snapshot` / `last-inputs-snapshot`) and the existing reset fns
;; (`reset-flows!` / `reset-last-inputs!`). The facade re-exports both
;; pairs.

(def flows-snapshot       registry/flows-snapshot)
(def last-inputs-snapshot registry/last-inputs-snapshot)

(def reg-flow           registry/reg-flow)
(def clear-flow         registry/clear-flow)
(def reset-flows!       registry/reset-flows!)
(def reset-last-inputs! registry/reset-last-inputs!)

;; ---- evaluation ---------------------------------------------------------
;;
;; Called from the router's OUTERMOST `:after` interceptor — fires LAST
;; (after the handler and the rest of the `:after` chain) — transforming
;; the pending `:db` effect before install and before :fx (per Spec 013
;; §Drain integration).

(defn- read-inputs [db flow]
  (mapv (fn [path] (get-in db path)) (:inputs flow)))

(defn- elide-inputs
  "Walk a flow's just-read input values through `elision/elide-wire-value`,
  each under its own declared input db-path so per-path `:sensitive` /
  `:large` declarations apply (Spec 009 §Size elision in traces). The
  single home for the input-value elision the `:rf.flow/computed` success
  payload and the `:rf.flow/failed` failure payload both ride — the trace
  bus is the wire boundary on both paths, so a flow reading a large or
  sensitive input must not surface it raw on either.

  Callers gate this behind their own outer `interop/debug-enabled?` so the
  walk is DCE'd in CLJS production (rf2-drr4z); this fn does not re-gate."
  [frame-id flow input-values]
  (mapv (fn [input-path v]
          (elision/elide-wire-value v {:frame frame-id :path input-path}))
        (:inputs flow)
        input-values))

(defn- validate-output!
  "Dev-only validation of a flow's computed `:output` value against its
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

  Observational, NOT a rollback. A flow's output is materialised state
  that downstream handlers / flows / subs already read by the time a
  failure could be observed, and the prior-writes-preserved failure
  contract (§Failure semantics rule 1) forbids retroactively unwinding a
  flow write mid-cascade. So — like `validate-app-schema!`, which is also
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
    (when-let [validate (late-bind/get-fn :schemas/validate-with-registered-fn)]
      (when-not (validate schema new-output)
        (let [explain     (late-bind/get-fn :schemas/explain-with-registered-fn)
              explanation (when explain (explain schema new-output))]
          (trace/emit-error! :rf.error/schema-validation-failure
                             {:category   :rf.error/schema-validation-failure
                              :where      :flow-output
                              :rf.flow/id (:id flow)
                              :failing-id (:id flow)
                              :schema-id  (:id flow)
                              :path       (:path flow)
                              ;; Wire-bearing — ride through the elision
                              ;; walker so a large / sensitive output value
                              ;; does not surface raw on the trace bus
                              ;; (symmetric with the `:rf.flow/computed`
                              ;; `:result` slot).
                              :value      (elision/elide-wire-value
                                            new-output
                                            {:frame frame-id :path (:path flow)})
                              :explain    explanation
                              :reason     (str "Flow " (:id flow)
                                               " output failed schema "
                                               (pr-str schema) ".")
                              :recovery   :no-recovery
                              :frame      frame-id}))))))

(defn- evaluate-flow!
  "Evaluate one flow against the given db. Returns `[new-db dirty?]` on
  successful evaluation (skip or recompute); on the failure path the
  exception re-throws after the `:rf.flow/failed` trace fires.

  Failed-flow contract (Spec 013 §Failure semantics — atomicity
  contract, Mike 2026-05-24): the failing flow's own output is NOT
  written (the throw happened during `:output`; there is no usable
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
  [frame-id db flow]
  (let [flow-id    (:id flow)
        new-inputs (read-inputs db flow)
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
        ;; rf2-hhh92: wall-clock the flow's `:output` recompute (dev-only)
        ;; so `:rf.flow/computed` carries `:elapsed-ms` — the per-op
        ;; duration the Trace panel's DURATION column reads. The `now-ms`
        ;; brackets ride `interop/debug-enabled?` (nil t0 in prod) so
        ;; Closure DCEs them under :advanced + `goog.DEBUG=false`.
        (let [t0         (when interop/debug-enabled? (interop/now-ms))
              new-output (apply (:output flow) new-inputs)
              flow-elapsed-ms (when interop/debug-enabled?
                                (- (interop/now-ms) t0))
              ;; Per rf2-qlzh4: capture the pre-write value at the
              ;; flow's `:path` BEFORE we assoc-in the new output.
              ;; This becomes the `:before` slot on the
              ;; `:rf.flow/computed` trace below — consumers (Xray
              ;; Event Detail, re-frame-10x flow panel) no longer
              ;; need to walk the epoch's `:db-before` snapshot to
              ;; render "wrote [:cart :total] 47.50 -> 52.50". The
              ;; read happens against `db` (the loop accumulator
              ;; that includes prior flows' writes in this drain), so
              ;; a downstream flow whose `:path` overlaps an upstream
              ;; flow's `:path` sees the UPSTREAM's just-written
              ;; value as its `:before` — the correct cascade-local
              ;; semantics. Gated to dev-only so the read is DCE'd
              ;; under `:advanced` + `goog.DEBUG=false`.
              old-output (when interop/debug-enabled?
                           (get-in db (:path flow)))
              new-db     (assoc-in db (:path flow) new-output)]
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
          ;; path. The walker reads `[:rf/runtime :elision
          ;; :declarations <path>]` and emits the marker for
          ;; schema-declared large slots.
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
                                          {:frame frame-id :path (:path flow)})
                          :result       (elision/elide-wire-value
                                          new-output
                                          {:frame frame-id :path (:path flow)})
                          :path         (:path flow)
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
          ;; when the flow's :output fn throws. last-inputs is NOT
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
          (when interop/debug-enabled?
            (trace/emit! :flow :rf.flow/failed
                         {:flow-id flow-id
                          :ex      e
                          :inputs  (elide-inputs frame-id flow new-inputs)
                          :frame   frame-id}))
          ;; Per rf2-je5p8: wrap the throw in an ex-info carrying the
          ;; flow-attribution slot `:rf.flow/failed-id`.
          ;; The router's `run-flows!` catch reads it and stamps
          ;; `:flow-id` into the substrate record's `:tags` so ops in
          ;; CLJS production (where `:rf.flow/failed` DCEs) can attribute
          ;; the cascade-level `:rf.error/flow-eval-exception` to a
          ;; specific flow. Attribution is `:flow-id`-only: there is no
          ;; real flow VALUE to carry, so the contract carries the id and
          ;; nothing more (per Spec 013 §Failure semantics / §Resolved
          ;; decisions). The failing frame is already in scope at the
          ;; router boundary (it is the frame being drained), so it is not
          ;; duplicated here. The original exception remains the `:cause`
          ;; for stack-trace introspection. Symmetric with Spec 013
          ;; §Failure semantics rule 4: the per-flow trace fires first
          ;; with flow attribution; the cascade-level error preserves the
          ;; same attribution at the substrate boundary.
          (throw (ex-info (or #?(:clj (.getMessage ^Throwable e)
                                 :cljs (.-message e))
                              ":rf.error/flow-eval-exception")
                          {:rf.flow/failed-id flow-id}
                          e)))))))

(defn run-flows-on-db
  "Per Spec 013 §Drain integration (rf2-u0zz5): walk THIS FRAME'S
  registered flows in topological order over the given `db` VALUE,
  dirty-check each one, recompute and assoc-in the result into a
  transformed db. Returns the flow-augmented db value.

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
  [frame-id db]
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
            last-inputs-before (registry/frame-last-inputs-snapshot frame-id)]
        (try
          (loop [remaining  ordered
                 db         db
                 any-dirty? false]
            (if (empty? remaining)
              db
              (let [flow            (flow-map (first remaining))
                    [new-db dirty?] (evaluate-flow! frame-id db flow)]
                (recur (rest remaining) new-db (or any-dirty? dirty?)))))
          (catch #?(:clj Throwable :cljs :default) e
            ;; Atomicity contract: discard ALL flow side-effects of this
            ;; aborted drain. The pending `:db` effect is dropped by the
            ;; router; here we restore THIS frame's pre-drain `last-inputs`
            ;; (its own container only — rf2-94ol5) so every flow on this
            ;; frame re-attempts next drain while sibling frames are
            ;; untouched. The throw (carrying `:rf.flow/failed-id` from
            ;; `evaluate-flow!`) propagates unchanged for router attribution.
            (registry/reset-frame-last-inputs-to! frame-id last-inputs-before)
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
;; Per rf2-wbtjn — frame-destroy teardown hook (symmetric with the
;; machines `:machines/teardown-on-frame-destroy!` hook landed by
;; rf2-vsigt). `frame/destroy-frame!` invokes this hook so per-frame
;; flow-registry entries, the matching `last-inputs` rows, and any
;; `:flow` registrar slots whose last owning frame was destroyed all
;; clear in one step. Without the hook a long-running SSR JVM (per-
;; request frame churn) leaks flow state indefinitely.
(late-bind/set-fn! :flows/teardown-on-frame-destroy!
                   registry/teardown-on-frame-destroy!)

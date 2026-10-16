# Spec 013 — Flows

> Status: Drafting. **v1-required.** Builds on the registration grammar in [001-Registration](001-Registration.md), the drain in [002-Frames §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics), and the reserved-namespace policy in [Conventions](Conventions.md).
>
> **The minimum claim:** flows are *registered, frame-activated computed-state declarations that materialise their output into `app-db`*. They are the v2 incarnation of v1's `on-changes` interceptor — same compute-on-input-change semantics — but registered once at the top level (not on individual events) and run by each frame that names them in its `:flows` config.
>
> **Restraint.** Flows are a **convenience** for a small number of small use-cases. They are not a replacement for subscriptions, not a new dataflow paradigm, not a substitute for state machines, and not the default for derived state. The architecture's main load-bearing pieces are events, subs, machines, and effects; flows sit alongside them as a focused tool for a narrow set of problems. **When in doubt, use a subscription** — flows pay an `app-db` write per recomputation and add a small piece of registered runtime; that cost is only worthwhile when the [reasons in §When (and when not) to use a flow](#when-and-when-not-to-use-a-flow) below apply.
>
> `:rf.flow/*` is the **internal-effect cousin** of the managed external effects — per [Managed-Effects](Managed-Effects.md), the surface MUST satisfy the eight properties applied to derived computation (effect-as-data via the registration map, framework-owned scheduler, structured failure taxonomy under `:rf.flow/*`, trace-bus observability, `:sensitive?` / `:large?` composition on flow outputs, retry / abort / teardown through the atomic event abort and `destroy-frame!`, the registrar as the flow registry, per-frame activation).

## Abstract

A **flow** is a registered rule that says: "when these input paths change, run this pure function and write the result to that `app-db` path." Inputs read the pending frame-state — a bare path reads **app-db** (the common case), a `[:rf.db/runtime …]` path reads **runtime-db** (per [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)); the output is always written to **app-db** (flows never write runtime-db). Flows are evaluated automatically on every event, **immediately after the handler's interceptor chain** (as the outermost `:after` interceptor — after the rest of the `:after` chain has reshaped the db effect, and before the `:db` effect installs), in topological order over their static input/output dependency graph. The flow walk transforms the handler's *pending* `:db` effect; see [§Drain integration](#drain-integration).

Flows differ from subscriptions in *where the value lives*. A sub's value lives in the per-frame sub-cache and is consumed by views. A flow's value lives in `app-db` at a known path, where it survives SSR / hydration / time-travel revert, is visible in the app-db inspector, can be read by downstream event handlers and other flows, and is covered by registered schemas. When the derived value is part of the application's *state* (as opposed to part of a view's render input), use a flow.

**Flows are registered once and activated per frame.** `reg-flow` registers a definition under the registrar's `:flow` kind at the top level, naming no frame. A frame runs exactly the flows its `:flows` config names, resolved through its own view of the registrations; evaluation, the output write, and the undo / time-travel boundary all belong to that frame. A frame that names no flows runs none. See [§Activation](#activation).

## When (and when not) to use a flow

Flows are the right tool when **all** of the following apply:

- The derived value is **part of the application's state**, not just a view-render input.
- Other event handlers or schemas need to read the value as plain `app-db` data. (A machine callback never reads it ambiently — a machine that needs the value takes it by **payload threading** or a **declared recordable coeffect** on `:rf.cofx`, per [005 §Causal host facts](005-StateMachines.md#causal-host-facts--rfcofx-ep-0017); an in-callback ambient read is unrecorded and breaks replay.)
- The value should **survive** SSR hydration, time-travel revert, or app-db serialisation.
- The derivation is **stable enough to be worth registering** — it isn't a one-off computation inside a single handler.

Flows are the **wrong** tool when:

- The derived value is consumed only by views → use a **subscription** (lighter, sub-cache native, no `app-db` write).
- The derivation has discrete states or lifecycle (entry/exit, transitions) → use a **state machine** (per [005](005-StateMachines.md)).
- The value is only relevant inside one event handler → just compute it inline; no registration needed.
- "I want a reactive value somewhere" → almost always a sub.

The expected v1 deployment volume is **small** — a typical app has dozens of subscriptions and one to perhaps a handful of flows. If a codebase grows tens of flows, that is a smell that subscriptions or machines are being misused.

## Why flows

Three use cases motivate flows:

- **Materialised computed state.** `:area` from `:width × :height`. `:total` from `:items`. `:can-submit?` from form validity, network state, and feature flags. The derived value is part of the app's state and downstream code reads it as plain `app-db`.
- **State that survives the wire.** SSR hydration carries `app-db`; computed values written by flows arrive on the client without re-computation. Sub-cache contents do not survive hydration.
- **Gated derivation.** A wizard step, a feature gate, an "advanced mode" — a derivation whose output depends on whether a feature is engaged. The gate is ordinary `app-db` data the flow reads as one of its `:inputs`, so it rewinds with `app-db` under an epoch restore and arrives with it over SSR (see [§Activation](#activation)).

## The registration shape

Per the canonical [Spec 001 §Registration grammar](001-Registration.md#registration-grammar) 3-slot shape, `reg-flow` is `(reg-flow flow-id metadata derive-fn)`: the pure `:derive` fn — the flow's HANDLER — is the third VALUE slot, and the middle slot is the reflection-config metadata map.

```clojure
(rf/reg-flow :rectangle/area
  {:inputs      [[:width] [:height]]         ;; vector of frame-state paths (bare = app-db)
   :output-path [:area]                       ;; where the result is written
   :doc         "Rectangle area computed from :width and :height."}
  (fn [w h] (* w h)))                          ;; pure: (in-1, in-2, ...) → output
```

Slot 1 — `flow-id`: the unique flow identifier. Per [Conventions §Feature-modularity prefix convention](Conventions.md#feature-modularity-prefix-convention), namespace by feature.

Slot 3 — `derive-fn`: the pure derivation. Receives input values positionally. Must be deterministic (same inputs → same output).

Slot 2 — `metadata`, the reflection-config map. Required keys:

| Key | Meaning |
|---|---|
| `:inputs` | Vector of frame-state paths read against the pending frame-state's two partitions (binary syntax — a bare path reads app-db; a `[:rf.db/runtime …]` path reads runtime-db; see [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)). The order matches positional args to the `derive-fn`. |
| `:output-path` | App-db path to write the output to. Writes are **app-db only** — a flow never writes runtime-db. |

Optional metadata keys (per the [001-Registration §Registration grammar](001-Registration.md#registration-grammar) standard):

| Key | Meaning |
|---|---|
| `:doc` | One-sentence what-and-why; surfaces in tooling. |
| `:schema` | Malli schema for the output value, validated on every recompute in dev (see [§Flow output validation](#flow-output-validation)). |
| `:sensitive` / `:large` / `:large?` | EP-0025 output data-classification declarations (see [§Flow output data classification](#flow-output-data-classification-ep-0025) and [015-Data-Classification](015-Data-Classification.md)). |
| `:ns`, `:line`, `:file` | Source coordinates (auto-captured by the registration macro per [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference)). |

The `derive-fn` sits in the third (value) slot and the middle slot is a pure metadata map — documentation-DCE stays clean, and flows align with `reg-resource` / `reg-mutation` / `reg-route`. A `:derive` left INSIDE the metadata map is rejected loudly as a mislocated key (`:rf.error/invalid-flow-metadata`) — the third slot is `:derive`'s one home. Likewise a non-map metadata slot throws the same error before any reconstruction runs.

`:inputs` is a positional vector matching `on-changes`. The vector form is short for the common 2–4-input case and the destructure-by-position is straightforward. Each path is read against the pending frame-state, whose two partitions select on the path's leading element — see [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db). (For the map-keyed alternative, see [§Open questions](#open-questions).)

## Input partition — bare = app-db, `[:rf.db/runtime …]` = runtime-db

An `:inputs` path is read against the pending **frame-state**, which has two partitions (per [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract) — the app-db / runtime-db split). The syntax is **binary** — a bare path reads app-db; a `[:rf.db/runtime …]`-rooted path reads runtime-db. There is no third explicit-app form:

```clojure
[:cart :items]                                    ;; bare path → app-db (the common case)
[:rf.db/runtime :rf.runtime/routing :current :route-id] ;; :rf.db/runtime-rooted → runtime-db
```

- A **bare** path (any leading element other than `:rf.db/runtime`) reads the pending **app-db** partition, verbatim.
- A path whose **first element is `:rf.db/runtime`** reads the pending **runtime-db** partition — the partition key is stripped before the `get-in`. There is **no** `[:rf.db/app …]` explicit-app form: bare *is* app-db.

**Any** flow — user or framework — may read runtime-db this way, so a flow can derive a materialised value from route or machine state (`[:rf.db/runtime :rf.runtime/routing :current :route-id]`, `[:rf.db/runtime :rf.runtime/machines :snapshots :app/boot :state]`). But the **write side is reserved**: a flow's `:output-path` and its `:derive` always write **app-db only** — a flow never writes runtime-db. Because flow outputs are always app-db paths, a `[:rf.db/runtime …]` input can never prefix-match another flow's output `:output-path`, so a qualified runtime input never creates a spurious topological dependency edge ([§Topological sort and cycle detection](#topological-sort-and-cycle-detection)). The dirty-check keys on **both** partitions: a runtime-only event (e.g. a pure route transition with no app-db change) still re-fires a flow that reads the changed runtime-db value, because the resolved runtime value is part of the cached input vector ([§Dirty-check semantics](#dirty-check-semantics)).

**There is no `[:rf.runtime/…]` *app-db* path — runtime state is not an app-db root.** Read runtime state through the `[:rf.db/runtime …]` partition-qualified input above.

`reg-flow` returns its `flow-id` — the primary id under which the flow registers — per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention). Under the 3-slot grammar the id is the first positional argument, exactly like the rest of the `reg-*` family.

`reg-flow` is a **top-level registration**, like `reg-event`: it writes the registrar's `:flow` kind and reads no frame — not a surrounding `with-frame`, and not an enclosing `frame-provider` (SCOPE) or a `frame-root` (ENSURE) — so it needs no scope and no live frame, and registering at namespace load is the normal case. A `:frame` key in the metadata map is rejected loudly with `:rf.error/invalid-flow-metadata`, as a mislocated `:derive` is: which frames run a flow is each frame's own `:flows` config ([§Activation](#activation)), never the registration's.

```clojure
(rf/reg-flow :rectangle/area
  {:inputs [[:width] [:height]] :output-path [:area]}   ;; registers; no frame involved
  (fn [w h] (* w h)))

(rf/make-frame {:id :scratch :flows [:rectangle/area]}) ;; :scratch runs it
```

The inverse is the ordinary registrar clear, `(rf/clear :flow :rectangle/area)`. It removes the registration, and each frame whose `:flows` names the id vacates the flow's output on its next event ([§Re-registration](#re-registration)).

<a id="frame-scoping"></a>

## Activation

A frame runs the flows its config names, and no others. `:flows` is a vector of flow ids on the frame config that `make-frame` and `frame-root` take (per [002 §`make-frame`](002-Frames.md#make-frame--atomic-create-and-register-and-the-canonical-config-grammar)); omitting it, or passing `[]`, runs no flows. Its order carries no meaning: evaluation order is the topological sort's ([§Topological sort and cycle detection](#topological-sort-and-cycle-detection)).

```clojure
(rf/reg-flow :cart/subtotal
  {:inputs [[:cart :items]] :output-path [:cart :subtotal]}
  (fn [items] (reduce + (map :price items))))

(rf/reg-flow :cart/total
  {:inputs [[:cart :subtotal] [:cart :discount-rate]] :output-path [:cart :total]}
  (fn [subtotal rate] (Math/round (* subtotal (- 1 (or rate 0))))))

(rf/make-frame {:id :rf/default :flows [:cart/subtotal :cart/total]})
;; or mounted: [rf/frame-root {:id :rf/default :flows [:cart/subtotal :cart/total]} [cart-view]]
```

1. **Activation is explicit, and the default is none.** A flow runs on every event in each frame that runs it. Were flows active everywhere by default, `:cart/total` would write into a Story frame's or a second tenant's `app-db`, and a `:derive` that is not total on `nil` would abort every event in a frame that never held its inputs — and a `:derive` is not required to be total on `nil` ([§The registration shape](#the-registration-shape)).
2. **Ids resolve through the frame's own view**, as every other kind does ([002 §Image resolution and composition](002-Frames.md#image-resolution-and-composition)): live from the registration store for a frame made without `:images`, through its sealed generation for a frame made with them. Two image compositions can therefore select different definitions of one id; that is image semantics, not a flows mechanism. In one live process, two derivations are two ids.
3. **Checks run at activation.** A frame whose `:flows` names an id its view does not resolve fails construction with `:rf.error/unregistered-flow`, the way an unregistered interceptor reference does ([002 §Validation and resolution timing](002-Frames.md#validation-and-resolution-timing)); an id two namespaces register is ambiguous, and fails with the `:rf.error/ambiguous-registration` a frame raises for any kind. The cycle check and the output-overlap check ([§Topological sort and cycle detection](#topological-sort-and-cycle-detection)) run over the frame's active set at construction, and again when a re-registration reaches the frame ([§Re-registration](#re-registration)). Both checks are per frame, so two flows may write the same path provided no frame runs both. A non-empty `:flows` on a frame built without the flows artefact fails construction with `:rf.error/flows-artefact-missing`.
4. **Introspection is the ordinary registrar query.** `(rf/registrations {:source :store :kind :flow})` lists every definition, `(rf/handler-meta {:source :store :kind :flow :id id})` returns one, and the `{:frame f}` forms resolve them through a frame's own view ([001 §The query API](001-Registration.md#the-query-api)). `(:flows (rf/frame-meta f))` names the ids a frame runs. There is no flows-specific read API.
5. **Per-frame state is the evaluation memo.** A frame holds, per active flow, the memo row its dirty check reads — the last inputs and output, and the definition identity and output path they were computed with ([§Dirty-check semantics](#dirty-check-semantics)). Definitions live only in the registrar.
6. **A feature gate is data.** A flow whose output depends on whether a feature is engaged reads the gate as an input — `[:cart :discount-rate]` above, written by the event that applies the discount and removed by the one that withdraws it. Because the gate is `app-db`, it rewinds with `app-db` under an epoch restore and arrives with it over SSR.
7. **SSR.** The server's per-request frame and the client's frame carry the same `:flows`. Flow outputs arrive in the hydrated `app-db`; the client's memo starts empty, so its first event evaluates every active flow, and a deterministic `:derive` writes the value the server wrote.

## Frame-destroy teardown

`destroy-frame!` drops the destroyed frame's flow evaluation memo — every memo row it holds, its pending path vacations included — in lockstep with the frame. This is a **normative requirement**: the frame-isolation contract from [002 §Destroy](002-Frames.md#destroy) is only honoured if the memo releases with the frame, and without it a long-running JVM SSR host (per-request frame churn), a pair-tool time-travel cycle, or any ephemeral `make-frame` usage leaks cached input vectors indefinitely.

Definitions live in the registrar, not in the frame, so teardown touches no definition and no other frame's memo: a sibling frame running the same flow keeps its own rows, and a frame later made under the same id starts with an empty memo and whatever `:flows` its own config names. Teardown is idempotent: destroying a frame that never ran a flow changes nothing.

The teardown contract is symmetric with the machines artefact's `:machines/teardown-on-frame-destroy!` hook — every per-feature artefact that holds frame-scoped state hangs its cleanup off the single normative `destroy-frame!` teardown boundary documented at [002 §Destroy](002-Frames.md#destroy). A new feature artefact MUST add its hook to the destroy cascade; a feature that holds frame-scoped state without one leaks on every `destroy-frame!`.

## Drain integration

Flow evaluation happens **immediately after the event handler's interceptor chain, transforming the pending `:db` effect — before the `:db` effect installs into `app-db` and before `:fx` walks**. The flow walk runs the registered flows over the chain's final `:db` effect *value* and rewrites that pending effect; it does NOT mutate the already-installed `app-db`. Per [002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode), the runtime realises this as the **outermost `:after` interceptor** — the flow transform fires after the rest of the `:after` chain has finished reshaping the `:db` effect into the complete `app-db` form:

```
process-event! (with flows as the outermost :after):
  1. Resolve handler.
  2. Run interceptor chain — :before steps in order, then handler,
     then :after steps in REVERSE. The framework's flow-transform
     :after is the OUTERMOST :after, so it fires LAST — after every
     other :after (incl. a `(path :slice)` interceptor's :after that
     splices the handler's slice back into the FULL db):

       run-flows-on-db ctx                                   ← OUTERMOST :after
         ;; (the reference fn is `run-flows-on-db`, which takes the pending
         ;;  db VALUE; shown here against `ctx` for the surrounding flow)
         pending-db ← (:db (:effects ctx))   ;; the FULL, fully-reshaped
                                             ;; :db effect, or the current
                                             ;; app-db value when no :db
                                             ;; effect was produced
         Walk THIS FRAME'S active flows — its :flows ids resolved
         through its own view — in topologically-sorted order;
         other frames' activations are not consulted.
         For each active flow:
           new-inputs ← read input paths from pending-db
           if new-inputs ≠ last-inputs[[frame-id flow-id]], or that
              row was computed with a different definition:
             new-output ← (apply :derive new-inputs)   ;; MAY throw
             pending-db ← (assoc-in pending-db (:output-path flow) new-output)
             last-inputs[[frame-id flow-id]] ← new-inputs
         (:effects ctx) ← assoc :db pending-db   ;; flow-augmented :db effect

         ;; FAILURE — a flow's :derive threw (atomicity contract):
         ;; DISCARD the pending :db effect (drop it from (:effects ctx))
         ;; and stash the throw under :rf/flow-error. The event ABORTS:
         ;; the single deferred install at step 3 installs nothing, so
         ;; no :rf.event/db-changed fires and :fx is skipped. NO partial
         ;; commit — neither the handler's :db nor any prior flow's write
         ;; lands. (See §Failure semantics.)

  3. Install :db (the flow-augmented value) — ONLY when a :db effect is
     present. Sub-cache invalidates. `:rf.event/db-changed` fires HERE —
     after flows (per [009 §Canonical per-event trace sequence](009-Instrumentation.md#canonical-per-event-trace-sequence)).
     On any pre-install throw (handler / interceptor :after / flow), no
     :db effect is present (the flow-throw path discarded it; a handler /
     interceptor throw never produced one), so this step installs nothing
     and emits no :rf.event/db-changed.
  4. Walk :fx in source order — every `:fx` entry reads the
     flow-augmented app-db. SKIPPED when the event aborted (any
     pre-install throw). :fx is the only post-install stage; an fx throw
     does NOT wind back the installed :db.
```

Five properties this gives:

1. **Flows run on the FULL, fully-reshaped db.** Because the flow transform is the outermost `:after`, it runs after every db-reshaping `:after` in the chain — in particular after a `(path :slice)` interceptor splices the handler's slice back into the complete `app-db`. A flow's bare (app-db) `:inputs` paths address the full `app-db`, so they MUST see the reshaped full db; running them outermost is what guarantees that. (Running flows innermost would expose them to the unspliced path slice and mis-read their inputs.) Qualified `[:rf.db/runtime …]` inputs read the pending runtime-db partition, which the `path` interceptor never touches (per [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)).
2. **`:fx` entries see flow outputs.** An `:fx` entry that reads `app-db` after install sees flow-computed values (flows transform the effect at step 2; install at step 3; `:fx` at step 4). This is what makes `[:dispatch [:react-to-area-change]]` work cleanly.
3. **Single pass per event.** Each flow runs at most once per drain. The topological order ensures multi-layer flows settle in one walk.
4. **Run-to-completion is preserved, with exactly one `app-db` write.** The flow transform rewrites the *pending* `:db` effect; the single `:db` install at step 3 is the only `app-db` write the run makes — the flow output is part of that one install, not a second mutation after it. Views never observe an intermediate state.
5. **Frame isolation.** An event dispatched on frame `:left` only walks the flows `:left` runs. A flow that `:right` also runs is evaluated for `:right` only when `:right`'s drain runs its own flow transform, against `:right`'s `app-db` and memo. This is what makes multi-tenant frames safe to colocate without cross-talk in derived state.

**Why a pending-`:db`-effect transform and not a post-install drain step.** A post-commit flow step — the full interceptor chain runs, `:db` commits to `app-db`, a separate pass mutates the live `app-db`, then `:fx` walks — would split the `app-db` write into two mutations (handler commit, then a separate flow mutation), make the run install the db twice, and fire the `:rf.event/db-changed` trace *before* flows, so the trace would not reflect the flow output and tools could not place flows on the trace timeline correctly. Transforming the pending `:db` effect (as the outermost `:after`) makes (a) the run perform exactly one `app-db` install — of the flow-augmented value, (b) the `:rf.event/db-changed` trace reflect the flow-augmented db and fire *after* `:rf.flow/computed`, and (c) the whole flow position observable on the trace stream (per [009 §Canonical per-event trace sequence](009-Instrumentation.md#canonical-per-event-trace-sequence)). `:fx` sees flow output either way.

**The (t1, t2) pending-`:db` snapshot pair — dev-only observability hook.** The same outermost flows-after-interceptor that runs the flow transform also stamps two trace events bracketing the walk: `:rf.event/db-pending` (t1) BEFORE the walk and `:rf.event/db-pending-post-flow` (t2) AFTER (only when flows changed the value). Both carry the FULL `:db` value under `:tags :rf.event/db` — same posture as `:rf.event/fx` on `:rf.fx/do-fx`, no diff, no DEBUG gate; PDS structural sharing keeps the cost pointer-sized and the `day8/de-dupe` wire layer collapses repeated subtrees on egress. The pair lets the Xray Handler panel render the handler-returned `:db` value AND the t1→t2 flow reshape without a framework-precomputed diff. **No production cost**: both emits sit inside the shared `interop/debug-enabled?` gate so CLJS `:advanced` + `goog.DEBUG=false` DCEs the whole surface. See [009 §Canonical per-event trace sequence](009-Instrumentation.md#canonical-per-event-trace-sequence) for the position in the trace stream.

**Position note — outermost `:after`, not innermost.** Conceptually flows run "right after the handler"; mechanically they run as the *outermost* `:after` so they observe the fully-reshaped `:db` effect. The distinction matters only when the chain contains a db-reshaping `:after` (the `path` std-interceptor): flows must run after that reshape. A consequence is that **user `:after` interceptors run before the flow transform and therefore see the handler's pre-flow `:db` effect, not the flow-augmented one** — observational `:after` interceptors that need flow outputs should read them from `app-db` (post-install) via a sub or a follow-up event, exactly as `:fx` does. (Reshaping the db effect from within an arbitrary user `:after` and then re-running flows would require interleaving the flow walk through the whole `:after` chain, which is neither necessary for any real use case nor compatible with the single-pass guarantee.)

### Trace stream ordering on a flow throw

When a flow's evaluation throws during the flow-transform `:after` (step 2 of the drain integration pseudocode above) — whether the authored `:derive` fn threw (`:phase :derive`) or it returned and the framework's own install of that value threw (`:phase :output-write`; see [§Failure semantics](#failure-semantics)) — the runtime emits a strict trace sequence — observable contract for off-box monitors, Xray, Story, and any consumer that lifts a flow failure off the trace stream. A flow throw is a **pre-install throw**: the event ABORTS before the `:db` install, so the failure stream carries **NO `:rf.event/db-changed`** (per [§Failure semantics](#failure-semantics) — the atomicity contract). A conformant port MUST emit these events in this order:

1. **`:rf.flow/computed`** for each flow that successfully computed before the throwing one — fires per prior flow as it rewrites the pending `:db` effect. (See §Failure semantics for the per-flow detail; these may be absent when the first flow throws.) Note: these traces fire even though the prior flows' writes are ultimately **discarded** by the abort — the trace records that the `:derive` ran, not that the write was committed.
2. **`:rf.flow/failed`** — the per-flow failure trace for the throwing flow, carrying `:flow-id`, `:phase` (`:derive` or `:output-write`), `:path` (the flow's declared `:output-path` — the same tag spelling `:rf.flow/computed` uses), a **structured, EDN-safe** exception summary (`:exception-message` + `:exception-data` — NOT a raw `Throwable`), and the elided `:inputs` read just before the throw. `:exception-message` and `:exception-data` are redacted to `:rf/redacted` when the flow's frame declares any `:sensitive` app-db classification, or fail-closed when the row names no `:frame` (per [009 §Flow trace events](009-Instrumentation.md#flow-trace-events)). Rides the dev-only trace surface; DCEs in CLJS production.
3. **`:rf.error/flow-eval-exception`** — the run-level error, emitted onto the **always-on production error-emit substrate** per [§Failure semantics](#failure-semantics). Carries `:where :flow-eval` (distinguishing this path from `:rf.error/handler-exception`), the originating event under `:rf.event/v`, and both `:flow-id` and `:phase` attribution stamped from the throwing flow's wrapped `ex-data` (where they are spelled `:rf.flow/failed-id` and `:rf.flow/failed-phase`, alongside `:rf.flow/output-path`). Attribution is the flow id and the failing phase — there is no real flow *value* to carry, so the contract carries those two and nothing more. The dev-only trace surface emits the same op concurrently; the production-substrate path survives CLJS `:advanced` + `goog.DEBUG=false` elision.
4. **NO `:rf.event/db-changed`.** The event aborted before the install — neither the handler's `:db` nor any prior flow's write committed (no partial commit). `app-db` is unchanged, and the trace stream carries no `:rf.event/db-changed` for this event. This is the same abort signature every other pre-install throw (cofx / handler / interceptor `:after`) produces.
5. **`:fx` is skipped — no `:rf.fx/handled` from this drain.** The event aborted (per [§Failure semantics](#failure-semantics)); no `:fx` entry runs, no `:dispatch`-issued child events queue. The drain proceeds to the **next** event in the router queue on its normal cycle.

The contract is the ordering AND the gap — consumers can rely on "the flow failure (`:rf.flow/failed` → `:rf.error/flow-eval-exception`) fires, and NO `:rf.event/db-changed` follows: the event aborted, `app-db` is unchanged, and no `:fx` of this drain reached the outside world." Cascading work that *would* have run via `:fx` re-attempts naturally on a later drain — once a drain completes without the flow throwing.

## Topological sort and cycle detection

Flows form a static dependency graph derivable from their `:output-path` and `:inputs` declarations. The graph is **per-frame**: it spans the frame's active set, and flows in different frames cannot depend on each other (their inputs read different `app-db`s). Each frame's topsort is computed independently over its active set.

**Dependency rule.** Flow B depends on flow A iff A's `:output-path` and any of B's `:inputs` share a path prefix in either direction:

- Exact match: `A.path = [:foo]`, `B.inputs = [[:foo]]` — B reads exactly what A writes.
- A's path is a prefix of B's input: `A.path = [:foo]`, `B.inputs = [[:foo :bar]]` — B reads inside A's value.
- B's input is a prefix of A's path: `A.path = [:foo :bar]`, `B.inputs = [[:foo]]` — A's write is part of B's input map.

The runtime topologically sorts the active set by this dependency relation. The sort is **not memoised** in v1 — the per-frame active set is tiny (a handful of nodes) and Kahn's algorithm over it is cheaper than the bookkeeping a memo would need — a memoised topsort needs explicit invalidation on every re-registration and every change to a frame's `:flows`, and at the per-frame node counts v1 targets the unmemoised call is the cheapest correct option. Implementations that observe a real bottleneck in topsort cost MAY add a `core.memoize`-style cache keyed on the active set's resolved definitions, but the contract is just: deterministic order over the dependency graph each drain.

**Cycle detection.** If A depends on B and B depends on A (any indirection) and a frame runs both, activation refuses the frame: construction throws `:rf.error/flow-cycle`. The thrown `ex-info`'s `ex-data` carries `:cycle` — an ordered vector of flow ids with a **closing repeat** that names the offending chain (e.g. `[:a :b :a]` for the two-flow cycle `:a → :b → :a`). The error fires before the frame runs any event. Registering the two flows is not an error: a definition names no frame, and a cycle exists only within a frame that runs every flow in it.

```clojure
(rf/reg-flow :a {:inputs [[:b]] :output-path [:a]} identity)
(rf/reg-flow :b {:inputs [[:a]] :output-path [:b]} identity)   ;; both register
;; This throws at activation:
(rf/make-frame {:id :f :flows [:a :b]})
;; → ex-info ":rf.error/flow-cycle" {:cycle [:a :b :a]}
```

The closing repeat is the contract: tools rendering the cycle (e.g. Xray) display the path verbatim. For an n-flow cycle the `:cycle` vector has `(inc n)` elements, with `(first cycle) = (last cycle)`. The starting node is implementation-defined (deterministic but unspecified) — multiple cycles can yield any one of them as the reported chain.

A re-registration can complete a cycle in a frame that already runs the flows it joins; the check runs again when the re-registration reaches the frame ([§Re-registration](#re-registration)).

**Disjoint output paths.** The flows a frame runs **MUST** write to pairwise-disjoint output `:output-path`s — no two active flows' `:output-path`s may stand in a prefix relationship (identical paths included). Activation throws `:rf.error/flow-path-overlap` when two flows in a frame's active set overlap. The check runs with cycle detection, before the frame runs any event, so a refused construction leaves nothing behind. Two flows writing one path is not itself an error — only a frame running both is.

The reason this is an error and not merely a topological edge: the dependency rule above compares one flow's `:output-path` against another's **`:inputs`**, never `:output-path` against `:output-path`. Two flows whose *outputs* overlap but whose *inputs* are disjoint therefore get **no edge** between them — both are ready at topsort start, and their relative order falls out of map-iteration order, which is not a contract. The flow that runs second silently wins the shared slot under last-write-wins, with no detection and no documented ordering. Rejecting at activation removes the footgun rather than papering over it with a tie-break that would leave the collision silent.

```clojure
;; Each of these frames is refused at activation — its two flows'
;; :output-paths overlap (one is a prefix of the other, identical included):
(rf/reg-flow :a {:inputs [[:w]] :output-path [:x]} identity)
(rf/reg-flow :b {:inputs [[:h]] :output-path [:x]} identity)
(rf/make-frame {:id :f1 :flows [:a :b]})
;; → ex-info ":rf.error/flow-path-overlap"  (identical paths)

(rf/reg-flow :c {:inputs [[:w]] :output-path [:x]}    identity)
(rf/reg-flow :d {:inputs [[:h]] :output-path [:x :y]} identity)
(rf/make-frame {:id :f2 :flows [:c :d]})
;; → ex-info ":rf.error/flow-path-overlap"  (:x is a prefix of [:x :y])

;; :a and :b both write [:x], which is fine while no frame runs both:
(rf/make-frame {:id :f3 :flows [:a]})
(rf/make-frame {:id :f4 :flows [:b]})
```

The thrown `ex-info`'s `ex-data` carries `:overlap` — `{:flow-ids [id-a id-b] :paths [path-a path-b]}` naming the colliding pair (deterministically ordered so the report is stable across runs) — alongside the canonical `:rf.error/id` / `:where` / `:recovery :fix-registration` slots ([009 §The thrown-error shape](009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract)). The fix is to give one of the two flows a disjoint `:output-path`, or to stop the frame running both. Sibling paths that merely share a non-prefix element are **not** an overlap: `[:x :y]` and `[:x :z]` are disjoint (each writes its own leaf under a shared parent), and only the prefix relationship — where one write would clobber or be subsumed by the other — is rejected.

## Dirty-check semantics

A flow recomputes only when its inputs change by **`=`-equality** since its last evaluation:

```
new-inputs ← (mapv #(get-in app-db %) (:inputs flow))
if new-inputs ≠ last-inputs[[frame-id flow-id]]:
  recompute and write
```

The `last-inputs` table is keyed by `[frame-id flow-id]`, so one flow that two frames run keeps two independent dirty-check windows. Each row also records the definition identity and output path it was computed with ([§Re-registration](#re-registration)).

Three implications:

1. **No-op `app-db` writes don't trigger.** A handler that writes the same value back to `:width` does not re-fire flows that depend on `:width`.
2. **Path-overlap is sufficient, not necessary, for re-firing.** A flow whose inputs sit at `[:user :profile :name]` does not re-fire when an unrelated path like `[:cart :items]` changes. The dirty-check is per-flow, not per-app-db-change.
3. **First evaluation always fires.** A flow newly active in a frame — at construction, after a frame re-registration adds it, or after a re-registration replaces its definition — has no row computed with its current definition, so its first walk recomputes unconditionally and produces the initial output value.

A flow owns its `:output-path`: on an equal-input visit the runtime writes the output the flow last computed back into the pending `app-db` wherever that path no longer holds it (a handler replaced the db, rebuilt the map holding the output, or wrote the slot itself), without calling `:derive`, so to override a flow's value you change its inputs or its definition.

## Failure semantics

**The event-handling pipeline is atomic up to and including the frame-state install.** The install is the single, deferred, all-or-nothing commit boundary. ANY throw *before* it — in cofx, the handler body, an interceptor `:after`, or the flow transform — aborts the **entire** event across **both partitions**: the pending `:db` (app-db) effect AND the pending `:rf.db/runtime` (runtime-db) effect are both discarded, so neither partition installs, **app-db and runtime-db are left unchanged**, **no `:rf.event/db-changed`** (and no `:rf.event/frame-state-changed`) is emitted, and **no `:fx`** run. A flow throw is just one of these pre-install throws, and behaves identically to every other — even though a flow may *read* runtime-db via a `[:rf.db/runtime …]` input ([§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)), it writes app-db only, so the runtime-db partition is discarded on abort purely as part of the whole-event roll-back, not because a flow wrote it. `:fx` is the only post-install stage; an fx throw surfaces an error but does NOT wind back the installed frame-state (its side effects — http / nav / dispatch — may already have fired and are irreversible).

**An app-db schema REJECTION at the commit boundary aborts the same way.** After the flow transform completes cleanly, the flow-augmented CANDIDATE `:db` is schema-validated BEFORE the install ([010 §Per-step recovery row 4](010-Schemas.md#per-step-recovery)). A rejection discards the whole candidate — the handler's write and every flow's write ride out together, nothing installs, no change trace fires — and the flow dirty-check bookkeeping (`last-inputs` + the in-drain abandoned-path vacations) is restored exactly as rule 2 below requires, so every flow re-attempts on the next clean drain. The two failure signatures differ only in their error op: a flow THROW surfaces `:rf.error/flow-eval-exception` (the flow itself failed), a schema rejection surfaces `:rf.error/schema-validation-failure` (the flow computed cleanly; the VALUE failed its declared shape); both emit ZERO `db-changed`.

**A flow evaluation fails in one of two PHASES, and the runtime discriminates them.** `:phase :derive` is the authored `:derive` fn itself throwing. `:phase :output-write` is that fn RETURNING normally and the framework's own install of the returned value at the flow's declared `:output-path` throwing — most often because the pending `app-db` holds a container at that path which cannot accept the path's final segment (a keyword segment over a vector, say). A conformant port MUST discriminate the two **structurally**, by which of the evaluator's two separate `try` forms caught the throw, and MUST NOT infer the phase by reading the exception's message: the authored callback and the framework's install sit in separate `try` forms precisely so an install failure is structurally unreachable from the derive handler and can never be reported as the application's code failing. The phase rides three surfaces — the `:rf.flow/failed` trace's `:phase` tag, the thrown ex-data's `:rf.flow/failed-phase`, and the always-on error record's top-level `:phase` (rule 4 below) — so it survives into production, where a mis-attributed output-write failure would otherwise send the reader into a `:derive` fn that did not fail.

When a flow's evaluation throws during the flow-transform `:after`, in EITHER phase, the runtime applies these rules atomically:

1. **No install — both partitions are unchanged.** There is **no partial commit**. The pending `:db` effect (the handler's write plus any prior successful flows' writes) is **discarded**: the flow-transform `:after` drops the `:db` effect from the chain context, so the single deferred install installs nothing — and the pending `:rf.db/runtime` effect is discarded in the same abort, so **runtime-db is left unchanged too**. Neither the handler's `:db`/`:rf.db/runtime` nor any earlier flow's output lands. The atomicity is **free**: because install is deferred to one write, "wind back on a pre-install throw" is just "don't perform the one write" — no rollback machinery.
2. **The failing flow's own output is not written, and `last-inputs` is rolled back.** Either `:derive` threw and there is no new-output at all, or it returned a value the framework could not install — in both phases nothing reaches the flow's `:output-path`. The whole drain's dirty-check bookkeeping rolls back too: the `last-inputs` snapshot taken before the walk is restored, so **every** flow — prior-successful and failing alike — re-attempts on the next drain. (Without this, a prior flow whose `last-inputs` advanced would wrongly suppress its recompute next drain even though its output never reached `app-db` — silently losing the write.) **The rollback is scoped to the draining frame's own dirty-check bookkeeping.** Only `last-inputs` rows for `[frame-id …]` (the frame being drained) are snapshotted and restored — a sibling frame draining concurrently on another thread (frames have independent drain-locks per [002 §Rules rule 1](002-Frames.md#rules); there is no global cross-frame serialization) has its dirty-check rows left untouched. A throwing-flow rollback can no more revert a sibling frame's just-advanced `last-inputs` than it can revert a sibling frame's `app-db`; the per-frame dirty-check window from [§Dirty-check semantics](#dirty-check-semantics) holds under concurrent drains by construction.
3. **The drain halts.** Downstream flows scheduled later in topo order do NOT run on this drain. They re-attempt naturally on a later drain — one that completes without the flow throwing. The `:db` install and the `:fx` walk do NOT run for this drain.
4. **The exception surfaces at the router as** `:rf.error/flow-eval-exception` (per [009 §Error contract](009-Instrumentation.md#error-contract)). The run-level error is emitted onto the **always-on production error-emit substrate** ([009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)), which routes the record to the `:observability :errors` sinks named by the owning frame's policy or the process default ([015 §Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy)). The substrate is NOT gated by `re-frame.interop/debug-enabled?`, so `:rf.error/flow-eval-exception` survives CLJS `:advanced` + `goog.DEBUG=false` elision: a flow-eval failure in a production build reaches every registered off-box error monitor (Sentry / Honeybadger / Rollbar / hosted observability). The tight listener record carries the failing event-id and frame, plus the top-level `:where :flow-eval`, `:flow-id` and `:phase` slots lifted out of the thrown ex-data (`:rf.flow/failed-id` / `:rf.flow/failed-phase`; each lift is nil-safe, so a throw that is not per-flow stamps `:where` alone). `:phase` MUST ride this axis, not the trace alone: the trace DCEs in production, and without the phase on the always-on record a production monitor reads an output-write failure as the application's `:derive` fn throwing. The per-flow `:rf.flow/failed` trace event fires first with the full flow-attributed detail (including `:flow-id`, `:phase` and `:path`), but that trace rides the dev-only trace surface and DCEs in production.

Worked example. Three flows in topo order — `:A`, `:B`, `:C`. Inputs change for all three. `:B` throws. After the drain:

- `app-db` is **unchanged** — `:A`'s output is NOT written (rule 1, no partial commit), the handler's `:db` did NOT land, `:B`'s `:output-path` is unchanged, `:C` did not run.
- **All** `last-inputs` are unchanged from before the drain (rule 2): `:A`'s advance was rolled back, `:B`'s never advanced (it threw), `:C` never ran. All three re-attempt next drain.
- Two flow traces fired in order: `:rf.flow/computed` for `:A` (it *ran* — the trace records the `:derive` call, not a committed write), then `:rf.flow/failed` for `:B`. Then the router emitted `:rf.error/flow-eval-exception` (rule 4). **No `:rf.event/db-changed` fired** (rule 1 — the event aborted before install; per [§Trace stream ordering on a flow throw](#trace-stream-ordering-on-a-flow-throw)).

**Rationale.** The `:db` install is the atomic commit boundary, and atomicity is uniform: a flow throw can no more leave a half-committed `app-db` than a handler throw or an interceptor-`:after` throw can. Discarding the whole pending write keeps one invariant true everywhere: **an event either commits in full or not at all.** Work that *would* have completed re-attempts on a later, clean drain; nothing half-done is ever observable in `app-db`.

### Why this asymmetry? — `:db` is atomic, `:fx` is best-effort

The atomicity contract is **asymmetric across the commit boundary**: a *pre*-install throw aborts cleanly (no `:db` install, no `:fx`, `app-db` unchanged), but a *post*-install throw inside `:fx` does **not** wind `app-db` back — the install already happened, and any fx that already fired (an HTTP POST, a `:dispatch`, a `:local-storage-set`, a navigation) is **not** rolled back. A natural question: shouldn't the commit boundary sit *past* `:fx`, so the whole event is all-or-nothing? The answer rests on three load-bearing constraints.

1. **Most `:fx` are irreversible by construction.** `:http-xhrio` POST mutates a server. `:dispatch` of a downstream event enters the router queue and may already have settled by the time a sibling fx throws. `:local-storage-set` writes through to disk. DOM mutations have been observed. Navigation has fired. The set of side effects that motivates putting work in `:fx` in the first place IS the set that cannot be undone after the fact. If the runtime ran the fx walk and then *skipped* the `:db` install on a later throw, the world's state (the server, the URL bar, local storage, the dispatched-children queue) and `app-db` would **diverge** — strictly worse than committing `app-db` first, which leaves both reflecting what actually happened, even when one fx blew up. "All-or-nothing for `:fx`" is unimplementable for fx whose effects, by design, escape the runtime.

2. **Knowing "all `:fx` succeeded" requires going async — which breaks composition.** Most `:fx` return immediately and report success/failure asynchronously (`:http-xhrio` fires the request and resolves later via `:on-success` / `:on-failure`; `:dispatch` queues an event whose drain hasn't run yet; a `:dispatch-later` timer hasn't fired). To gate the `:db` install on "every fx succeeded," the event itself would have to become asynchronous — which breaks `dispatch-sync`, breaks the run-to-completion event-queue composition (per [002 §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics)), and would force *every* fx-id to declare sync-vs-async success semantics so the runtime knows when to commit. The whole programming model collapses into a distributed-transaction coordinator.

3. **The model is optimistic local commit, not a transaction coordinator.** This is the classic distributed-transaction problem: a local commit (`app-db`) needs to compose with N external commits (`:fx`) that the local node doesn't fully control. Of the three available answers — (a) **optimistic local commit + compensating-action sagas** (commit `app-db` immediately; on async failure, dispatch a compensating event that reverts the slice and surfaces the failure to the user); (b) **two-phase commit** (every external fx exposes synchronous `prepare` / `commit` phases; the framework runs `prepare` on all of them, then `commit` on all, rolling back on any `prepare` failure — feasible only where the external system supports it, which most HTTP / DOM / navigation surfaces do not); (c) **accept the asymmetry** (commit `app-db` at the boundary; `:fx` is best-effort; surface failures as ordinary error events; let application code compose compensating sagas where rollback semantics matter) — re-frame2's model is **(c)**: `app-db` commits at the boundary, `:fx` is best-effort, failures surface as ordinary error events on the trace bus + the always-on error-emit substrate per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code), and there is no transaction coordinator.

**Escape valve — application-level sagas.** Apps that *do* need rollback semantics across an `:fx` boundary get them at the application layer, not from the framework. The pattern: an event handler writes its optimistic state into `app-db` (the local commit), dispatches the external fx with `:on-failure` pointing at a **compensating event** that reverts the slice and surfaces the failure to the UI, and (optionally) tracks the in-flight epoch in a state machine ([Spec 005](005-StateMachines.md)) so the UI can render "saving…" / "saved" / "save failed — reverting" states explicitly. Two pieces compose: re-frame2's atomic `:db` write gives the local commit; the framework's `:on-failure` reply addressing on managed fxs (per [Managed-Effects §3 Structured failure taxonomy](Managed-Effects.md#3-structured-failure-taxonomy-under-rf), [014 §Failure taxonomy](014-HTTPRequests.md)) gives the compensating-event seam. The framework provides the primitives; the saga IS the application.

**Worked example.** The favorite-toggle in [`examples/real-apps/realworld_http/favorites.cljs`](../examples/real-apps/realworld_http/favorites.cljs) — `:article/toggle-favorite` optimistically flips `:favorited` and bumps `:favoritesCount` in `app-db`, dispatches `:rf.http/managed` with `:on-failure [:article/favorite-rollback slug prior]`, and the rollback handler restores the prior slice if the POST/DELETE fails. The `examples/real-apps/realworld_http/` README catalogues three further compensating-event patterns (favorite toggle, comment delete, follow/unfollow). See [README §Optimistic updates](../examples/real-apps/realworld_http/README.md) and the `realworld-favorites` deftest in [`realworld_cljs_test.cljs`](../implementation/adapters/reagent/test/re_frame/realworld_cljs_test.cljs) for the headless test that exercises the rollback path.

## Flow tracing

Every flow lifecycle event emits a structured trace event under op-type `:flow`. The full taxonomy lives in [009 §Flow trace events](009-Instrumentation.md#flow-trace-events); the summary:

| `:operation` | Fires when |
|---|---|
| `:rf.flow/registered` | `reg-flow` registers a flow definition in the registrar for the first time (a re-registration rides `:rf.registry/handler-replaced`). |
| `:rf.flow/computed` | A flow's `:derive` fn ran and the result was written to its `:output-path` (dirty-check observed input value-difference). Carries `:before` (the value at `:path` immediately before this drain's write — `nil` when the slot had never been written) alongside `:result`, so consumers render the wrote-line "wrote `[:path]` `<before>` → `<after>`" without walking the surrounding epoch's `:db-before` snapshot. Both ride through `elide-wire-value` against the flow's `:path`. |
| `:rf.flow/skip` | The dirty-check found inputs `=`-equal to the previous run; the recompute was suppressed (§[Dirty-check semantics](#dirty-check-semantics) above; value-equal recompute suppression). Carries `:repaired? true` when that visit wrote the flow's remembered output back; a plain skip carries no `:repaired?` key. |
| `:rf.flow/cleared` | `(rf/clear :flow id)` removed the flow's registration. Each frame that runs the flow vacates its output path on its next event. |
| `:rf.flow/failed` | A flow's evaluation threw during recompute — the authored `:derive` fn (`:phase :derive`), or the framework's install of its returned value at the declared `:output-path` (`:phase :output-write`). Carries `:phase` and `:path` alongside the exception summary. The exception is re-thrown after the trace fires; see [§Failure semantics](#failure-semantics) for the atomicity contract (the event aborts — no install, `app-db` unchanged, no `:rf.event/db-changed`, `:fx` skipped; `last-inputs` rolled back so every flow re-attempts; router emits `:rf.error/flow-eval-exception` per [009 §Error contract](009-Instrumentation.md#error-contract)). |

The evaluation events (`:rf.flow/computed`, `:rf.flow/skip`, `:rf.flow/failed`) carry `:flow-id` and `:frame` under `:tags`; the two registration events carry `:flow-id` and no `:frame`, because a registration names none. Pair-shaped tools, Xray's flow panel, and custom dashboards filter `op-type :flow` to subscribe to the whole flow stream — see [Tool-Pair §How AI tools attach](Tool-Pair.md#how-ai-tools-attach) and [009 §Flow trace events](009-Instrumentation.md#flow-trace-events) for the consumer-side pattern.

<a id="flow-output-data-classification-ep-0025"></a>

**Flow classification.** A flow's trace events inherit no `:sensitive?` stamp from the handler whose event triggered the drain: nothing in a handler's scope marks the run (per [009 §The `:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key)). A flow classifies its own output instead:

- **Registration-owned output classification (per output path; NO propagation).** A flow classifies its **own output** sub-paths with registration-layer `:sensitive` / `:large` keys (each a vector of `:rf/path` vectors into the output shape; `[[]]` classifies the whole output) — redacted at the `:output-path` write and on the `:rf.flow/computed` `:result` slot. **Sensitivity does not propagate**: a flow reading a sensitive `app-db` (or runtime-db-qualified `[:rf.db/runtime …]`, per [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)) input does **not** auto-classify its output — classification does not flow input → output. A derived secret is just a new path; the author classifies it directly with the flow's own `:sensitive` / `:large`. There is **no** `:rf.egress/output-sensitivity` declassification claim (and no `:rf.egress/inherit` / `:rf.egress/sensitive` / `:rf.egress/public` value set) — the key is absent and silently ignored if present, per [015 §No propagation, no taint](015-Data-Classification.md#no-propagation-no-taint). A malformed `:sensitive` / `:large` declaration is rejected fail-closed at registration with `:rf.error/flow-bad-marks` (e.g. a non-vector axis or a non-path subpath entry, or the boolean `:sensitive?` spelling — `:sensitive` is "a collection of sensitive paths" at the registration layer, per the [015 §`:sensitive` / `:sensitive?` cross-layer distinction](015-Data-Classification.md#the-sensitive--sensitive-cross-layer-distinction-ep-0007-rule-3)).

Apps that need finer-grained per-flow privacy classify the surrounding handler's event payload with registration-owned `:sensitive` (on `reg-event` — per [015 §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification), applied at the event and error emit sites), declare the flow's own output classification as above, or scrub the `:derive` fn's return value at the source.

The whole flow trace surface, like the rest of trace, is compile-time eliminated in production builds (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)).

## Flow output validation

A flow's optional `:schema` key (per [§The registration shape](#the-registration-shape)) declares a Malli schema for the **output value**. When present, the runtime validates the flow's computed `:derive` output against it on every recompute, during the flow-transform `:after` (after the handler body, before the `:db` install — per [§Drain integration](#drain-integration)). This is the same dev-time, pluggable-validator mechanism the rest of [010 §Schemas](010-Schemas.md) uses; the flows artefact reaches the registered validator/explainer through the `:schemas/validate-with-registered-fn` / `:schemas/explain-with-registered-fn` late-bind hooks, so an app that omits the schemas artefact (or registers no validator) pays nothing and the check soft-passes.

**Observational, not a rollback.** A flow `:schema` violation does **not** throw and does **not** unwind the write. This is distinct from a flow `:derive` *throw* (which aborts the whole event — [§Failure semantics](#failure-semantics)): a schema violation is a soft, dev-time diagnostic. The flow computed a value successfully; the value simply fails its declared shape. By the time a violation could be observed, downstream flows in the same drain may already have read the value as their input, so retroactively unwinding one flow's write mid-walk would leave an inconsistent pending `:db` effect. So the output **is** written into the pending effect and the run proceeds normally (the event still commits if no flow throws); the failure surfaces as a diagnostic `:rf.error/schema-validation-failure` error event with `:where :flow-output` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)), carrying the failing `:rf.flow/id`, the flow's `:output-path`, the failing `:value` (size/sensitivity-elided like every wire-bearing trace slot), and the registered explainer's `:explain` output — redacted whole to `:rf/redacted`, with the trace stamped `:sensitive? true`, when the frame's elision registry classifies anything in the output sensitive, since an explanation re-ships the checked value and is not path-anchored. Otherwise, when the registry classifies anything in the output large, `:explain` is replaced whole by a `:rf.size/large-elided` marker for the output at its `:output-path` and the trace is stamped `:large? true` — the [010 size-safety arm](010-Schemas.md#large--schema-driven-size-elision-nomination) with the registry as the classifier. `:recovery` is `:no-recovery`, matching the category's documented disposition — the check exists to surface a producer bug early, not to repair state.

```clojure
(rf/reg-flow :cart/total
  {:inputs      [[:cart :subtotal] [:cart :discount-rate]]
   :output-path [:cart :total]
   :schema      [:int {:min 0}]}     ;; output must be a non-negative integer
  (fn [subtotal rate] (Math/round (* subtotal (- 1 (or rate 0))))))
```

Flow output validation is dev-only: it sits behind `re-frame.interop/debug-enabled?` and is compile-time eliminated in production builds. That is a fact about this surface, not about the validation surface at large. [C-000.35](000-Vision.md#contract--pattern-obligations) settles what may be elided by what the check is for rather than by who declared the schema it reads, and a `reg-flow` `:schema` is an ordinary registration diagnostic over the programmer's own flow — so it goes, while the checks the framework relies on to keep its own promises stay ([010 §Production builds](010-Schemas.md#production-builds) names them).

## Sub integration

Flows write to `app-db`; subs read `app-db`. **Flows therefore publish zero framework subscriptions.** This is a deliberate posture, not an oversight. The contract is: a flow's output value lives at its `:output-path` in the dispatching frame's `app-db`, and consumers read it through whatever sub registration they prefer — either a user-registered `(rf/reg-sub :my-app/area (fn [db _] (get-in db [:my-app/area])))` over the path, or a derived sub that closes over it. Because the flow transform rewrites the *pending* `:db` effect (per [§Drain integration](#drain-integration)), the flow-derived value reaches `app-db` through the run's single `:db` install — the one `replace-container!` + sub-cache invalidation the drain already performs — so reactivity is automatic and the run performs exactly one `app-db` write per event regardless of how many flows fired; there is no separate flow-output cache the substrate needs to track.

### What this means

A flow named `:my-app/derived-area` with `:output-path [:my-app/area]` is **observable through any of these patterns**, none of them special-cased for flows:

```clojure
;; (a) plain app-db read inside another handler
(rf/reg-event :event/use-area
  (fn [{:keys [db]} _]
    (let [area (get-in db [:my-app/area])] ...)))

;; (b) user-registered sub over the flow's :output-path
(rf/reg-sub :my-app/area (fn [db _] (get-in db [:my-app/area])))
@(rf/subscribe [:my-app/area])

;; (c) derived sub that closes over the path implicitly
(rf/reg-sub :my-app/area-doubled
  {:inputs [[:my-app/area]]}
  (fn [[area] _] (* 2 area)))
```

The flow's `:output-path` IS the contract surface. Consumers depend on the path, not on a `:rf.flow/<flow-id>` sub-id. This is the same shape as any other `app-db` value: a flow's output is "ordinary application state with a known producer" — exactly the framing at [§When (and when not) to use a flow](#when-and-when-not-to-use-a-flow).

### Asymmetry with routing

Routing publishes **nine framework subs** over its `:rf/route` slice (`:rf/route`, `:rf.route/id`, `:rf.route/params`, `:rf.route/query`, `:rf.route/fragment`, `:rf.route/transition`, `:rf.route/error`, `:rf.route/chain`, `:rf/pending-navigation` — per [012 §Subscriptions](012-Routing.md#subscriptions)). Flows publish zero. The asymmetry is real but principled:

| Surface | Where output lives | Framework subs | Why |
|---|---|---|---|
| Routing | `:rf/route` slice in `app-db` | nine (`:rf/route` + eight derived) | The slice is **a single named map with a fixed shape** — every consumer wants `:id`, `:params`, `:query`, `:transition` as common destructures. Publishing the per-key subs once means every consumer reads the same canonical sub-id (`:rf.route/id`) rather than re-registering eight identically-shaped getters. |
| Flows | An arbitrary path in `app-db` per flow | zero | Each flow's `:output-path` is **user-chosen** and **shape-arbitrary** (could be a number, a vector, a map of any shape, …). There is no canonical "every flow has these eight derived views" to publish. A consumer wanting `(:items @(subscribe [:my-app/cart]))` writes one sub over their cart's `:path`; the framework cannot do this for the user without knowing every flow's output shape. |

The asymmetry follows from the **shape-uniformity** difference: routing's slice has a fixed shape locked by [§The `:rf/route` slice](012-Routing.md#the-rfroute-slice); a flow's output shape is whatever the `:derive` fn returns. Routing's shape uniformity makes framework subs cheap (one registration table, every app sees the same sub-ids); flows' shape arbitrariness makes them impossible (the framework would need a sub-id per flow with a layer-1 fn parameterised on each flow's `:output-path`, doubling the per-flow sub-cache footprint and polluting the registered-sub namespace).

### Observability consequence

A tool wanting to enumerate "which subs depend on this route's id" reads the sub-graph: `re-frame.subs.tooling/sub-topology` gives every sub's declared `:inputs`, so the subs built on `[:rf.route/id]` read straight off it, and `sub-cache-snapshot`'s `:realized-inputs` adds the edges parametric subs realise at runtime. A tool wanting to enumerate "which subs depend on this flow's output" is asking about **`app-db` path consumers**, not flow-output consumers: a consumer is a sub whose layer-1 fn reads the flow's `:output-path`, not a sub that names "the flow with this id". A layer-1 fn is opaque, so no framework surface maps an `app-db` path to the subs that read it.

This is an **observability asymmetry** the doc names but does not paper over. A tool rendering a "flow consumer" panel matches the flow's `:output-path` against the `app-db` paths its consumers read, not against a framework sub-id. Which flows exist comes from the registrar — `(rf/registrations {:source :store :kind :flow})` — and which a frame runs from `(:flows (rf/frame-meta f))` ([§Activation](#activation)). Neither surface is a framework sub family.

There is **no** framework sub family `:rf.flow/<flow-id>` whose layer-1 fn is `(fn [db _] (get-in db (:output-path flow)))`. Flows write app-db and subs read app-db; the two surfaces stay separate (per the [§When (and when not) to use a flow](#when-and-when-not-to-use-a-flow) framing). The zero-framework-sub posture is the v1 contract.

## Sequencing — settling on the dispatching frame

> **NORMATIVE.** Whatever an `:fx` walk changes in frame state — `app-db` or runtime-db, through any effect — every flow the frame runs reflects before any continuation the walk queued runs, and by the time the dispatch returns. No application-authored follow-up event is required, and none may be required by any future revision of this section.

The `:fx` walk is the *last* drain stage, **after** the flow transform has evaluated for the current event (per [§Drain integration](#drain-integration), step 4 runs after step 2), so an effect that writes frame state during the walk lands after the pass that would have read it. The machine lifecycle effects are the motivating writers: `:rf.machine/destroy` and `:rf.machine/spawn` write the actor's snapshot straight into runtime-db there, so a flow over a machine snapshot — [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db)'s own example input — would keep publishing the pre-write value, and a continuation the same handler queued would read it and could persist that wrong decision. An ordinary machine transition is not such a writer: it commits through the event's pending runtime-db, which the flow pass reads.

**How it settles.** When a completed `:fx` walk has changed the frame's state on a frame that runs at least one flow, the runtime enqueues **one** framework-private settling event, `[:rf/settle-flows]`, at the head of the dispatching frame's queue. Run-to-completion drains it within the same pass, ahead of every continuation the walk queued, so it has settled before the originating dispatch returns. The settling event runs the **ordinary** interceptor chain and therefore the ordinary flow transform — the same topological sort and dirty check every other event gets. It contributes no `:db` effect of its own, so a settle over an already-settled frame recomputes nothing, installs nothing, and emits no `:rf.event/db-changed`.

The trigger keys on the frame's state, not on a list of writers, so any present or future effect that writes frame state is covered without enrolling it. It is guarded by the frame running a flow: a walk that writes state on a frame that runs none — or in an application without the flows artefact — enqueues nothing, and so does a walk that writes no frame state (a `:dispatch`, a timer, a request).

Four properties follow, and they are why the settle is a separate event rather than an inline re-run of the transform:

1. **Exactly one `app-db` install per event holds.** The settle is a separately dequeued event with its own single install; nothing writes `app-db` twice inside the event whose walk wrote the state. [§Drain integration](#drain-integration) property 4, the pending-effect-transform model ([§Resolved decisions §Flows transform the pending `:db` effect](#flows-transform-the-pending-db-effect-as-the-outermost-after-resolved)) and the atomic-commit contract ([§Failure semantics](#failure-semantics)) all hold.
2. **The failure boundary stays where it belongs.** A `:derive` that throws on the newly written state aborts the **settle**, not the event whose walk wrote it: that event's own `:db` write has already committed. The throw is reported through the ordinary `:rf.error/flow-eval-exception` path with the usual `:phase` discriminator (`:derive` vs `:output-write`) — see [§Failure semantics](#failure-semantics). There is no second failure path.
3. **It is frame-scoped like any other event.** The settle is dispatched on the frame the effects ran against, so it can no more reach a sibling frame than any other event can ([§Activation](#activation)).
4. **One settle per walk, whatever the walk did.** A single `:fx` vector that spawns two actors and writes a resource settles all of it in one pass, in topological order, with one install — because a settle observes the frame state as the *whole* walk left it. A walk nested inside another — a machine exit cascade runs its own — may add a settle of its own; that is harmless, because settling is idempotent under the dirty check.

**The settling event is framework-private and is not API.** It is not registered — neither in the registrar nor in the EP-0023 image standard registry — so it adds no row to an application's event catalogue, tooling projection, or generated code; it is materialised through the router's unresolved-handler seam. Its id is nonetheless reserved (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)) so an application registration cannot shadow it. Applications must not depend on its id, its presence in a trace stream, or its position in the queue. **The observable contract is the settle boundary stated above, not the transport that achieves it.**

**No follow-up event is needed.** A follow-up no-op event dispatched from the same handler is redundant work: settling is idempotent under the dirty check, so it changes nothing.

## Re-registration

`reg-flow` with an already-registered id is an ordinary registrar re-registration — a **surgical update**, the same semantics as every other `reg-*` per [001-Registration §Hot-reload semantics](001-Registration.md#hot-reload-semantics). The new definition replaces the old in the registrar, and a frame that resolves the live store runs it from its next event; a frame running a sealed image generation keeps the definition its generation sealed. In-flight events finish against the definition they entered the drain with. The replacement emits the registrar's ordinary `:rf.registry/handler-replaced`.

Each frame's memo rows record the definition identity and output path they were computed with, so a frame reconciles a changed registration on its next event, with no reload hook:

- **A changed definition recomputes.** A row computed with a different definition does not suppress the walk, so the new `:derive` runs whatever the inputs, and the topsort observes the new dependency edges ([§Topological sort and cycle detection](#topological-sort-and-cycle-detection); v1 does not memoise the sort). A `:derive` fix saved during development therefore takes effect on the next event, including one that clears a `:derive` that was aborting every event ([§Failure semantics](#failure-semantics)).
- **A moved output path is vacated.** When the new definition declares a different `:output-path`, the next pass `dissoc-in`s the old path from the frame's `app-db` within the same pending `:db` effect that writes the new one, so no stale value lingers at a slot no live flow maintains. A replacement that keeps the path vacates nothing; the recompute overwrites it in place.
- **A removed registration vacates its output.** When `(rf/clear :flow id)` or the hot-reload sweep removes a registration that a frame's `:flows` names, the frame's next pass vacates the output path its memo row recorded and warns `:rf.warning/unregistered-flow`, naming the frame and the id. The frame keeps running its other flows, which read the vacated slot like any absent input, and re-registering the id resumes it.
- **The activation checks run again.** A re-registration that completes a cycle or an output overlap in a frame's active set refuses that frame's next pass: the event aborts as any pre-install throw does ([§Failure semantics](#failure-semantics)), raising `:rf.error/flow-cycle` or `:rf.error/flow-path-overlap`, and each later event on the frame does the same until a further registration repairs the set.

A frame's `:flows` changes only by re-registering the frame ([002 §Re-registration — surgical update](002-Frames.md#re-registration--surgical-update)): a flow the new vector adds runs from the next event, and a flow it drops stops running, its output path vacated on that event. The activation checks run on the new vector as part of the re-registration. Editing `:flows` on a mounted `frame-root` raises `:rf.error/frame-root-reconfigured`, as editing `:interceptors` does ([002 §`frame-root`](002-Frames.md#frame-root--the-ensure-component-cljs-reference)).

## What flows are NOT

Three near-neighbours flows are *not*:

| Concept | Difference |
|---|---|
| **Subscription** ([006](006-ReactiveSubstrate.md)) | Subs live in the sub-cache; consumed by views. Flows live in `app-db`; consumed by everything (handlers, other flows, schemas, SSR payload). When the value is part of the application's *state*, use a flow; when it's part of view rendering only, use a sub. |
| **State machine** ([005](005-StateMachines.md)) | Machines have transitions, hierarchical states, `:always`/`:after`/`:spawn`, snapshots at `[:rf.runtime/machines :snapshots <id>]` in runtime-db. Flows have one pure function and one output path. Use a machine when there are discrete states; use a flow when the value is a pure function of inputs. |
| **`on-changes` interceptor** (v1) | `on-changes` is wired into specific events' interceptor chains. A flow is registered once at the top level and runs on every event of each frame whose `:flows` names it. The compute-on-change semantics are identical; the registration shape and lifecycle are different. |

Flows are also explicitly *not*:

- **A second runtime.** Flows participate in the standard event drain via one after-interceptor implicit on every event; there is no parallel scheduler, and no counterpart to v1 alpha flows' bardo state machine, lifecycle policies, or per-flow `:fx` mechanism.
- **A side-effect mechanism.** Flows compute values; they don't fire fx. If a derived value's change should trigger an effect, dispatch a follow-up event whose handler reads the flow's output and emits the effect.
- **A subscription replacement.** Most derived values belong in subs. Flows pay an `app-db` write per recomputation; the value is more visible but slightly more expensive than a sub-cache hit.

## Migration from v1 alpha flows

| v1 alpha | v2 |
|---|---|
| `:id` | The **first positional slot** — the `flow-id` of `(reg-flow flow-id metadata derive-fn)`. Same name, but a positional slot rather than a map key. |
| `:inputs` (map of keyword → path-or-`flow<-`) | `:inputs` (vector of paths). Map-keyed inputs that referenced other flows via `flow<-` collapse to plain paths — the topological sort handles dependency ordering automatically. |
| `:output` (function of resolved-inputs map) | The **third positional slot** — the `derive-fn` value of `(reg-flow flow-id metadata derive-fn)`, taking its inputs positionally rather than destructuring a resolved-inputs map. It is **not** a metadata key: a `:derive` left inside the metadata map is rejected with `:rf.error/invalid-flow-metadata`. |
| `:path` | `:output-path` |
| `:live?`, `:live-inputs` | Dropped. A gate is data: the events that flip the predicate write its value into `app-db`, and the flow reads it as one of its `:inputs` (see [§Activation](#activation)). |
| `:cleanup` | Dropped. Default is `dissoc-in` on `:output-path`; opt-out is not provided. |
| Per-flow `:fx` | Dropped. Dispatch an event from a handler if you need fx on flow output change. |
| Lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) | Not applicable. Lifecycle policies are a sub-cache concern; flows have one cache state (registered-or-not). |
| `flow<-` reified flow-to-flow input | Dropped. Flow B reads flow A by listing A's `:output-path` in its `:inputs`. |
| `:reg-flow` / `:clear-flow` (unprefixed fx-ids) | Dropped, with no fx form: a flow is registered at the top level and activated by frame config, and a gate is data (the `:live?` row above). The prefixed spellings `:rf.fx/reg-flow` / `:rf.fx/clear-flow` are reserved and never recycled ([Conventions §Reserved fx-ids](Conventions.md#reserved-fx-ids)). |

The migration agent rewrites mechanically; a flow definition that used `:live?` gains its gate as an input instead, and the events that flipped the predicate write the gate into `app-db`.

## Conformance coverage

The behaviours below are exercised by **realized** `spec/conformance/fixtures/flow-*.edn` fixtures (driven through the live flows runtime by `implementation/flows/test/re_frame/flows_conformance_test.clj` — the flows artefact's own conformance gate) and, for the cases a fixture cannot express ergonomically, by the flows artefact's unit tests under `implementation/flows/test/re_frame/`. The materialized fixture set is broader than this list; the rows here record where each behaviour lands:

| Behaviour | Realized coverage |
|---|---|
| Single flow, multiple inputs, one output; dirty-check re-fires on input change | `flow-recompute-on-input-change.edn` |
| A feature gate held as `app-db` data turns a flow's output on and off | `flow-toggle-via-fx.edn` |
| Registration and clear lifecycle traces | `flow-lifecycle-emits-traces.edn` |
| Multi-layer flows — A runs before B when B depends on A's output (topological order) | `flow-multi-input-topo.edn`; topo-sort unit tests `flows_topo_test.clj` |
| Activating a cycle throws `:rf.error/flow-cycle` | unit tests `flows_topo_test.clj` / `flows_test.clj` (an activation-time throw is asserted directly, not via a corpus outcome) |
| An `app-db` write producing a `=`-equal value does not re-fire dependent flows | `flow-noop-on-value-equal-input.edn` |
| Two frames running different flows stay isolated, and two derivations of one quantity are two ids | `flow-frame-scoped.edn` |
| Destroying a frame drops its memo and leaves a sibling frame's flows running | `flow-frame-destroy-teardown.edn` |
| A re-registration recomputes on the next event, and a moved output path is vacated | `flow-hot-reload-preserves-output.edn` |

## Open questions

> **SA-4 classification.** The item below is a **post-v1, untracked note** per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md) — deferred design work the corpus tolerates shipping without resolving (the v1 design is settled — vector `:inputs`), marking a candidate enhancement rather than a blocking gap. It has no tracking bead filed yet, so it **does not qualify as `:post-v1 tracked`** (which requires a `rf2-<id>`); it records the concrete reconsideration trigger that files its bead when it fires. Settling after a state-writing effect is resolved, in [§Resolved decisions](#state-writes-in-the-fx-walk-settle-on-the-dispatching-frame-resolved).

### Map-keyed `:inputs` instead of vector

The vector form (`:inputs [[:width] [:height]] :derive (fn [w h] ...)`) matches `on-changes` and is short. A map-keyed alternative (`:inputs {:w [:width] :h [:height]} :derive (fn [{:keys [w h]}] ...)`) matches [Principles §Name over place](Principles.md#name-over-place). The vector is the v1 default; the map is the principled default. v2 ships the vector form for migration ergonomics. (This is purely a keying question — orthogonal to the per-path partition rule of [§Input partition](#input-partition--bare--app-db-rfdbruntime---runtime-db): either form's individual paths are still bare-for-app-db / `[:rf.db/runtime …]`-for-runtime-db.)

- **Reconsideration trigger (falsifiable).** Not "the map form proves preferable in practice" (not checkable) — instead: **N ≥ 3 real flows across the corpus / consumer apps carry 4-or-more `:inputs` and get bitten by positional-destructure fragility** (a `:derive` arg reordered or dropped without the `:inputs` vector kept in lockstep, or a review comment flagging the positional binding as unreadable at that arity). At that point the map form's name-over-place ergonomics have a measured cost to weigh against the migration break. Until then the vector form stands.
- **Status.** Post-v1, **untracked note** — no bead filed yet; the trigger above files one when it fires.

## Resolved decisions

### Topological sort over registration order (RESOLVED)

Topological sort, not registration order or `:flows` order: registration order is namespace load order, which decides nothing, and `:flows` names a set, so neither is a contract. The dependency graph is statically derivable from each flow's `:output-path` and `:inputs`; recomputing the sort once per drain is cheap at v1's per-frame node counts (a handful of flows, Kahn's algorithm over them is cheaper than memo bookkeeping). The sort is not memoised — per [§Topological sort and cycle detection](#topological-sort-and-cycle-detection).

### One-pass evaluation, not fixed-point iteration (RESOLVED)

Topological sort lets every flow settle in one walk. Fixed-point iteration would only be needed where flows form mutual dependencies — but mutual dependencies are exactly cycles, which the topsort rejects at registration. With cycles forbidden, one pass suffices.

### Vector `:inputs`, not map (RESOLVED for v1; revisit later)

Per [§Open questions §Map-keyed `:inputs`](#map-keyed-inputs-instead-of-vector), the vector form ships in v1 for migration ergonomics. The map-keyed alternative is a design option for a future iteration.

### A flow leaving a frame vacates its output path (RESOLVED)

No opt-out. When a frame stops running a flow — its registration cleared or swept, or the frame re-registered without it — or a re-registration moves its `:output-path`, the frame's next event vacates the slot. A value no live flow maintains is stale derived state that downstream readers cannot tell from a live one. Apps that want to preserve the value copy it elsewhere first.

### Frame-destroy teardown is mandatory (RESOLVED)

`destroy-frame!` MUST release the destroyed frame's flow evaluation memo — every memo row it holds. Definitions live in the registrar, so every definition, and a sibling frame's rows, are untouched. Per [§Frame-destroy teardown](#frame-destroy-teardown). Without this, long-running SSR JVM hosts (per-request frame churn), pair-tool time-travel, and `make-frame` ephemeral usage leak cached input vectors indefinitely. Symmetric with the machines and SSR teardown hooks the per-feature artefacts publish off the single normative `destroy-frame!` boundary at [002 §Destroy](002-Frames.md#destroy).

### Flows transform the pending `:db` effect, as the outermost `:after` (RESOLVED)

The flow walk runs **immediately after the handler's interceptor chain — as the outermost `:after` interceptor — and transforms the handler's pending `:db` effect in the chain context**, before the single `:db` install and before `:fx`. Per [§Drain integration](#drain-integration) and [002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode).

Transforming the pending effect makes three things true that a post-install flow step (full interceptor chain → `:db` commits to `app-db` → a separate pass mutates the live `app-db` → `:fx` walks) could not: (a) the run performs exactly one `app-db` install — of the flow-augmented value — rather than a handler commit followed by a separate flow mutation; (b) the `:rf.event/db-changed` trace fires AFTER flows, so it reflects the flow-augmented db, and `:rf.flow/computed` precedes `:rf.event/db-changed` on the trace stream (per [009 §Canonical per-event trace sequence](009-Instrumentation.md#canonical-per-event-trace-sequence)) — making the flow position observable for Xray's Trace panel; (c) flows transform the *pending effect* rather than the live container, so the write is part of the run's single install. `:fx` **sees flow output** — it walks after the install, so it reads the flow-derived `app-db`.

**Outermost, not innermost.** Flows run as the *outermost* `:after` (fired last) — NOT the innermost — because the `path` std-interceptor's `:after` reshapes the `:db` effect (splicing a slice back into the full db), and a flow's bare (app-db) `:inputs` paths address the full `app-db`, so they must run after that reshape. (Qualified `[:rf.db/runtime …]` inputs read the pending runtime-db partition, which `path` never touches.) The consequence is that user `:after` interceptors run before the flow transform and see the handler's pre-flow `:db` effect; observational interceptors that need flow output read it from `app-db` post-install (sub / follow-up event), as `:fx` does. Correctness (flows read the full db) is the load-bearing constraint that fixes the placement.

### A flow throw aborts the event — atomic commit boundary (RESOLVED, Mike 2026-05-24)

The `:db` install is the single, deferred, **all-or-nothing** commit boundary. ANY throw before it — cofx, handler, interceptor `:after`, or the flow transform — aborts the entire event: no install, `app-db` unchanged, no `:rf.event/db-changed`, no `:fx`. A flow throw is just one such pre-install throw and MUST behave identically to a handler / interceptor-`:after` throw. `:fx` is the only post-install stage; an fx throw does NOT wind back the installed `:db` (side effects may already have fired).

Letting prior-flow writes commit on a flow throw would commit a partial `app-db` — making flow throws behave differently from every other pre-install throw, and committing state from an event the runtime simultaneously reports as failed. The atomicity rule is also **free**: because the install is deferred to a single write, winding back on a pre-install throw is just *not performing that write* (the flow-transform `:after` discards the pending `:db` effect) — there is no rollback machinery, no partial-commit special-case. The dirty-check bookkeeping rolls back in lockstep: the `last-inputs` snapshot is restored on a throw so every flow re-attempts cleanly on a later, clean drain. The invariant the whole design upholds: **an event either commits in full or not at all.** Per [§Failure semantics](#failure-semantics).

### `:rf.error/flow-eval-exception` rides the always-on error substrate (RESOLVED)

Flow evaluation failures MUST surface on the always-on production error-emit substrate (per [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)), NOT on the dev-only trace surface alone. The error-emit substrate survives CLJS `:advanced` + `goog.DEBUG=false` and routes the tight record (failing event-id + frame) to the `:observability :errors` sinks ([015 §Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy)). The per-flow `:rf.flow/failed` trace fires first with full flow-attributed detail (including `:flow-id`), but it rides the dev-only trace surface and DCEs in production. Without this routing, a production-build flow-eval failure would be silently dropped — no off-box monitor record. Per [§Failure semantics](#failure-semantics) rule 4.

### State writes in the `:fx` walk settle on the dispatching frame (RESOLVED)

The `:fx` walk runs after the event's flow pass, so state an effect writes during the walk would otherwise reach the frame's flows a drain late. There are two ways to close that lag. A *second* mid-event `app-db` install is one, and it is the disallowed one; a second **event** is the other, and it costs none of the invariants. A walk that changes frame state on a frame that runs a flow enqueues one framework-private settling event on that frame, which drains inside the same run-to-completion pass. Each event installs `app-db` exactly once, the atomic-commit contract holds, and a settle-time `:derive` throw reports through the ordinary `:rf.error/flow-eval-exception` path — see [§Sequencing](#sequencing--settling-on-the-dispatching-frame).

Settling is the runtime's job because the alternative is an application-authored no-op event dispatched after every state-writing effect — ceremony in every consumer's event catalogue, trace stream, tests and generated code to spare the runtime one follow-up drain — and that no-op event is **exactly the mechanism the settle uses**. The trigger keys on the frame's state rather than on a list of writers, so no writer has to be enrolled and none can be missed.

Automatic settling is the only behaviour. There is deliberately no `:settle?` flag, no synchronous variant, no alternate effect id, and no switch that leaves the lag in place: two spellings of one contract is the cost the single spelling exists to avoid. A follow-up no-op event does no harm — settling is idempotent under the dirty check — but it is redundant, not supported.

## Cross-references

- [001-Registration](001-Registration.md) — registration grammar (`reg-flow` is a kind under `:flow`).
- [002-Frames §`make-frame`](002-Frames.md#make-frame--atomic-create-and-register-and-the-canonical-config-grammar) — the `:flows` frame-config key a frame activates its flows with ([§Activation](#activation)).
- [002-Frames §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode) — where the flow after-interceptor sits.
- [002-Frames §Destroy](002-Frames.md#destroy) — the normative teardown boundary `:rf.flow/*` state hangs off; cross-referenced from [§Frame-destroy teardown](#frame-destroy-teardown).
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — sub-cache invalidation; flows trigger sub-cache invalidation when they write.
- [Derivations](Derivations.md) — the derivation/process algebra: a flow is a **materialized `:after-event` derivation** (`:storage :app-db`, `:lifecycle :frame`) — the same whole-value function as the equivalent subscription, differing only in storage/evaluation/lifecycle policy (see [Derivations §Worked equivalence](Derivations.md#worked-equivalence--one-function-two-policies)). The whole-value law every derivation obeys is owned there; the `:after-event` sequencing it summarizes is owned here ([§Drain integration](#drain-integration)).
- [009-Instrumentation §Error contract](009-Instrumentation.md#error-contract) — `:rf.error/flow-cycle` and `:rf.error/flow-eval-exception` namespaces.
- [009-Instrumentation §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code) — the always-on error-emit substrate `:rf.error/flow-eval-exception` rides; cross-referenced from [§Failure semantics](#failure-semantics) rule 4.
- [009-Instrumentation §Flow trace events](009-Instrumentation.md#flow-trace-events) — full taxonomy and payloads for the `:rf.flow/*` event vocabulary; cross-referenced from [§Flow tracing](#flow-tracing) above.
- [009-Instrumentation §The `:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key) — `:rf.flow/*` trace events inherit `:sensitive?` from the in-scope handler at drain time; cross-referenced from [§Flow tracing](#flow-tracing) above.
- [Conventions](Conventions.md) — the reserved settle event id `:rf/settle-flows`, and the retired fx-ids `:rf.fx/reg-flow` / `:rf.fx/clear-flow`, reserved and never recycled.
- [MIGRATION §M-19](../migration/from-re-frame-v1/README.md) — generic call-shape migration; `:inputs` is positional vector matching the v1 `on-changes` form.

# Story — Runtime

> Per-variant frame allocation; args precedence resolution; decorator
> composition; the four-phase loader lifecycle; the
> `:loaders-complete-when` predicate; the programmatic runtime —
> `run-variant`, `reset-variant`, `watch-variant`, `snapshot-identity`,
> `destroy-variant!`. The engineering contract Stage 3 implements.

## Per-variant frame allocation

Per
[spec/007 §Relationship-with-frames](../../../spec/007-Stories.md)
each variant *is* a frame. At variant-mount time the runtime:

1. Calls `(rf/reg-frame variant-id {:doc ... :app-db {} :substrate :reagent ...})`
   (per
   [spec/002 atomic create-and-register](../../../spec/002-Frames.md)).
2. Records side-table metadata (view id, decorators, play, tags,
   modes, substrates) in `tools.story.registry/*variants*`.
3. Runs the four-phase lifecycle (below).

At variant-unmount the runtime calls `(rf/destroy-frame variant-id)`.
Hot-reload preserves the side-table; a re-registration of the same
variant calls `reset-frame` and re-runs the lifecycle.

## Args resolution precedence

When the rendering layer asks "what args is this variant rendered
with?", the runtime composes them in this strict order (later wins):

1. **Global args** — `tools.story.config/*global-args*` (from
   `re-frame.story/configure!` at boot — theme, locale defaults).
2. **Story args** — `:args` on the parent story.
3. **Mode args** — the active `:mode`'s `:args` (deep-merge, not
   replace).
4. **Variant args** — `:args` on the variant.
5. **Cell-local args** — runtime overrides from controls
   (`:story/set-arg`).

`(get-effective-args variant-id {:mode ... :overrides ...})` is the
public lookup; Stage 3 owns the helper.

Deep-merge (per Storybook's convention) for nested maps;
override-by-replacement for vectors. This convention matches Phase 1
§1.1 cited behaviour.

## Decorator composition order

For a render against variant V belonging to story S:

1. Collect decorators: `(concat global-decorators story-decorators
   variant-decorators)`.
2. Apply in order — **outermost wraps innermost** for `:hiccup` kind,
   so the global decorator's wrap is the outermost element in the
   rendered tree.
3. `:frame-setup` decorators fire at frame creation (before phase 1
   loaders), in the same order.
4. `:fx-override` decorators register their stubs at frame creation,
   before loaders.

Decorator composition is deterministic; Stage 3 unit-tests against a
golden trace.

## Four-phase lifecycle with `:loaders-complete-when`

Strict order, per spec/007:

1. **Phase 1 — Loaders.** For each event in `:loaders` (in order):
   - `dispatch-sync` the event into the variant's frame.
   - Wait for the drain to settle (no in-flight events; no pending fx
     dispatching follow-ups).
   - Evaluate `:loaders-complete-when` if provided. If truthy,
     proceed. Otherwise wait for the next non-loader event;
     re-evaluate.
2. **Phase 2 — Events.** For each event in
   `(concat story-events variant-events)`:
   - `dispatch-sync` in order. Drain to completion between events.
3. **Phase 3 — Render.** The view renders against the post-events
   `app-db`, with the effective args (above) and decorator stack
   (above) applied.
4. **Phase 4 — Play.** For each event in `:play`:
   - `dispatch-sync` in order. Drain to completion.
   - `:rf.assert/*` events record into `:assertions` (no throw — see
     [`004-Assertions.md`](004-Assertions.md)).

Phase 1 and 4 are async-safe; phases 2 and 3 are sync (per re-frame's
run-to-completion drain).

### `:loaders-complete-when` default behaviour

Variant body may include an optional `:loaders-complete-when` event
predicate. Default behaviour:

- HTTP-flavoured fx (request → success/failure dispatch) is
  "complete" when the response event has been dispatch-synced.
- Long-lived fx (`:websocket`, `:interval`, `:firestore`, etc.) is
  "complete" when the **first message arrives** (i.e. the first event
  the fx dispatches into the frame is received). After that the loader
  is considered complete and `:events` proceeds.
- Authors override either default via `:loaders-complete-when` — a
  vector-of-event-vectors or a registered predicate-event id; the
  predicate is invoked after each event drain settles; truthy result
  means loaders-complete.

The default predicate is "first non-loader event seen by the frame, or
loader's drain settles with no in-flight fx, whichever comes first."
Authors override via the variant body. Stage 2 macro validates that
`:loaders-complete-when` resolves to a registered event id or is a
literal data form (vector of event vectors). See
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)
§loaders-complete-when-predicate.

## Error projection

A render error in phase 3, or an unexpected exception in phases 1, 2,
or 4, projects into the variant's `:assertions` as a special failure
record:

```clojure
{:assertion :rf.error/exception
 :phase     :phase-3-render
 :substrate :reagent
 :error     {:message "..." :stack "..." :data {...}}
 :passed?   false}
```

The variant pane shows the error inline (see
[`003-Render-Shell.md`](003-Render-Shell.md) for substrate-error
layout; same shape for non-substrate errors). The play sequence
continues past phase-1 or phase-2 errors so the full picture is
captured (see
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §record-not-throw).

## Snapshot-identity computation

Per
[spec/007 §Variant snapshot identity](../../../spec/007-Stories.md),
the hash includes:

- Variant id
- `:events`, `:play`, `:loaders` (in order; canonicalised)
- Effective `:args` (post-merge with story + mode)
- Decorator id sequence and their args
- Tag set
- Parent story `:component` id
- Parent story decorators
- Registered schema digest of `:component` (per
  [spec/011 §`:rf/schema-digest`](../../../spec/011-SSR.md))
- Active substrate (when computing per-substrate identity)
- Active mode (when computing per-mode identity)

The hash is `sha-256` of a transit-serialised canonical form (keys
sorted, vectors stable). The identity changes iff any input changes;
otherwise visual-regression services skip the cell.

`tools.story.runtime.snapshot-id/compute` implements this. The
canonical form is keyed by `:rf/snapshot-canonical-v1` to allow future
revisions without breaking baselines.

## Programmatic API

```clojure
(run-variant variant-id)
(run-variant variant-id {:render? true :mode :Mode.app/dark :substrate :reagent})
;; => {:frame           <variant-id>
;;     :app-db          {...}
;;     :assertions      [{:assertion :rf.assert/path-equals :passed? true ...} ...]
;;     :rendered-hiccup [...]                    ; only if :render? true
;;     :elapsed-ms      <number>
;;     :snapshot        {:variant-id ..., :mode ..., :substrate ..., :content-hash "..."}}
```

`run-variant` runs the four-phase lifecycle:

1. Allocate (or `reset-frame`) the variant's frame.
2. Run `:loaders` (phase 1), wait for `:loaders-complete-when`
   predicate.
3. Run `:events` (phase 2).
4. Optionally render (phase 3) and run `:play` (phase 4).
5. Tear down or persist per opts.

`run-variant` returns synchronously when no loaders are present and
all fx in `:events` are synchronous; otherwise returns a promise-like
object the host can await. The exact async return-shape is Stage 3's
call; candidates: a Promise (CLJS), or a manifold.deferred (CLJ-side
bridge if needed). Stage 3 picks one and locks it.

```clojure
(reset-variant variant-id)                       ; tear down + re-run :loaders + :events
(watch-variant variant-id)                       ; re-run on dep re-registration
(unwatch-variant variant-id)
(variants-with-tags tags)                        ; query — returns coll of variant ids
(variant->edn variant-id)                        ; canonical-form serialised body
(workspace->edn workspace-id)                    ; same, for workspace layouts
(snapshot-identity variant-id)
(snapshot-identity variant-id {:mode ... :substrate ...})
;; => {:variant-id ..., :mode ..., :substrate ..., :content-hash "..."}
```

## Effects (fx) registered by Story

| Fx id | Payload | Notes |
|---|---|---|
| `:story/set-arg` | `{:variant <id> :key <k> :value <v>}` | Dispatched by control widgets when args change. |
| `:story/run-play` | `{:variant <id>}` | Run the play sequence (used by play-stepper). |
| `:story/reset` | `{:variant <id>}` | Reset variant to post-events baseline. |
| `:story/save-layout-as` | `{:workspace <id> :body <transit>}` | Persist the active layout as a registered workspace. |

## Coeffects (cofx) registered by Story

| Cofx id | Shape | Notes |
|---|---|---|
| `:story/mode` | `<mode-id>` | The active mode for the variant; useful in mode-aware events. |
| `:story/substrate` | `:reagent`, `:uix`, ... | The active substrate. |

## Substrate hooks

```clojure
(rf/variant-substrates variant-id)
;; => #{:reagent :uix :helix :reagent-slim}  (or a subset, per :substrates on variant/story)
```

The story tool's multi-substrate pane iterates this set, rendering
each; substrate-specific failures show inline (see
[`003-Render-Shell.md`](003-Render-Shell.md) §Multi-substrate).

## Tag-vocabulary queries

```clojure
(rf/registrations :tag)                               ; all registered tags
(rf/registrations :tag #(contains? (:tags %) :auth))  ; filtered
```

Already framework-supplied via the
[spec/001 registrar query API](../../../spec/001-Registration.md);
Story registers the seven canonical tags at load.

## Open items (Stage 3 picks)

These were named in the original IMPL-SPEC §13.2 as deliberate punts;
they remain Stage 3 / Stage 5 calls and are documented here for
auditability.

- **Async-result shape for `run-variant`.** Promise vs.
  `manifold.deferred`; Stage 3 picks based on how
  `:loaders-complete-when` interacts with re-frame's synchronous drain.
- **Mode × Variant × Substrate snapshot-identity matrix.** Three
  options: nested hash (substrate is leaf); composite key
  (`[variant-id mode-id substrate]`); or substrate as a separate axis
  with its own hash slot. Stage 3 picks.
- **Hot-reload semantics for `reg-decorator` re-registration.** If a
  `:hiccup` decorator's `:wrap` closure changes, do all variants using
  it re-render automatically? Reagent's reactive graph handles this
  for subscription changes; decorator changes need explicit
  propagation (mark variants stale, re-mount). Stage 3 / Stage 4
  jointly handle.
- **`:rf.assert/effect-emitted` semantics under `force-fx-stub`.** If a
  variant stubs `:http` and then asserts
  `:rf.assert/effect-emitted :http`, does the assertion pass? The fx
  *is* emitted; the stub just intercepts. Stage 5 clarifies.

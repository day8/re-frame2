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

At variant-unmount the runtime calls `(rf/destroy-frame! variant-id)`.
Hot-reload preserves the side-table; a re-registration of the same
variant calls `reset-frame!` and re-runs the lifecycle.

## Coexistence with hosting application state

Story installs runtime slots into every variant frame's `app-db` under
the reserved `:rf.story/*` namespace (per
[spec/Conventions.md](../../../spec/Conventions.md) reserved-namespace
rules). The slots Stage 3 installs today:

- `:rf.story/lifecycle` — discrete state of the variant's four-phase
  lifecycle machine (mirrored by `loaders.cljc` after each transition).
- `:rf.story/loaders-complete?` — boolean signal read by the
  `:loaders-complete-when` predicate path.
- `:rf.story/assertions` — vector of assertion records appended by the
  `:rf.assert/*` handlers during phase 4.

These slots are not optional. The lifecycle machine, the loader-
completion predicate, and the play-phase assertion bus all read them
back during the four-phase run; clearing any of them mid-lifecycle
corrupts the variant.

**Rule.** A host application's `reg-event-db` handlers — and any
other code path that writes `app-db` — MUST preserve the `:rf.story/*`
namespace when seeding or resetting `db`. The hazard is the
"replace-the-whole-db" idiom:

```clojure
;; WRONG — wipes :rf.story/lifecycle, :rf.story/loaders-complete?,
;; :rf.story/assertions, and any future :rf.story/* slot. Safe in a
;; standalone app; corrupts every Story variant that runs this event.
(rf/reg-event-db :app/initialise
  (fn [_db _event]
    {:app/items [] :app/discount nil}))

;; RIGHT — preserves all reserved slots Story (or any other framework
;; tool) installs into the frame's app-db.
(rf/reg-event-db :app/initialise
  (fn [db _event]
    (assoc db :app/items [] :app/discount nil)))
```

Equivalently, `(merge db {:app/items [] :app/discount nil})` is fine;
the rule is "thread `db` through, do not throw it away". Domain-key
clearing is the host's business; the reserved `:rf.story/*` namespace
is Story's.

The originating evidence is commit `c2accadf` (rf2-2uwp1, cart-total
Story slice): `:cart/initialise` was a whole-`db` replacement and wiped
the variant frame's lifecycle slots the moment the variant ran the
event. The fix swapped `(fn [_db _event] {...})` for
`(fn [db _event] (assoc db ...))`. Host apps integrating Story SHOULD
audit their init/reset events for the same anti-pattern.

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

### Privacy

The `:rf.error/exception` assertion record is an emission site like any
other and obeys the framework's path-level data-classification contract
([spec/015-Data-Classification.md](../../../spec/015-Data-Classification.md)).
Two rules, both inherited from the spec/015 §Propagation rules and
spec/Security.md §Author guidance for exceptions under path-level
`:sensitive?` (rf2-dv79m):

1. **Event-level `:sensitive?` honoured.** If the event that triggered
   the exception was registered with `{:sensitive [paths]}` (per
   [spec/015 §1. Event-args → app-db](../../../spec/015-Data-Classification.md#1-event-args--app-db)),
   the assertion-recorder treats the `:rf.error/exception` record as it
   does any other trace-bus emission: marked paths in the captured
   event-args resolve to `:rf/redacted` before landing in
   `:assertions`. The runtime does NOT bypass elision because the
   record is shaped as an error.
2. **`ex-data` and exception `:message` are NOT auto-walked.** Per
   spec/015 §Out of scope and spec/Security.md §Author guidance for
   exceptions under path-level `:sensitive?`, the framework cannot
   redact values an author concatenated into the message string or
   assigned to author-chosen `ex-data` map keys — those slots are not
   reachable from the path-marked declarations the walker consults.
   The `:error {:message ... :data ...}` slot inside the
   `:rf.error/exception` record reproduces what the author threw; the
   variant pane and Causa Event Detail render it verbatim.

**Author responsibility.** When throwing inside a handler that reads
sensitive-path values:

- Name the *category* of failure in the exception message
  (e.g. `"Invalid credentials"`), not the value
  (`(str "User " email " failed login")`).
- Substitute `:rf/redacted` at sensitive `ex-data` keys at assembly
  time, or omit the keys entirely. The per-app `safe-throw` helper
  convention (per
  [spec/015 §Author guidance for the exception-path residual](../../../spec/015-Data-Classification.md#author-guidance-for-the-exception-path-residual))
  is the recommended pattern.

**ex-data passes through `re-frame.elision/elide-wire-value` before
landing in `:assertions`.** The wire-elision walker (per
[spec/API.md §elide-wire-value](../../../spec/API.md))
substitutes sentinels at any path the elision registry resolves on the
`ex-data` map — so an author who DID populate `ex-data` with values
sourced from path-marked app-db slots gets transitive redaction at
record-build time. The residual leak surface is therefore narrowed to
exactly the case spec/Security.md flags: an author who interpolated a
sensitive value into the message string, or assigned it to an
author-keyed `ex-data` slot that does not correspond to a marked path.
That gap is closed by author discipline, not framework taint
tracking — see [spec/015 §Propagation rules](../../../spec/015-Data-Classification.md#propagation-rules)
for the boundary the framework does enforce.

The same redaction posture applies whether the exception lands in
phase 1 (loaders), phase 2 (events), phase 3 (render), or phase 4
(play); the error-projection code path is shape-uniform across phases.

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

1. Allocate (or `reset-frame!`) the variant's frame.
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
;; => #{:reagent :uix :helix}  (or a subset, per :substrates on variant/story)
```

The story tool's multi-substrate pane iterates this set, rendering
each; substrate-specific failures show inline (see
[`003-Render-Shell.md`](003-Render-Shell.md) §Multi-substrate).

## Tag-vocabulary queries

```clojure
(story/registrations :tag)                               ; all registered tags
(story/registrations :tag #(contains? (:tags %) :auth))  ; filtered
```

`story/registrations` is the Public-query parity bridge over the
tool-owned side-table at `tools.story.registry/*` (see
[spec/007 §Story-tool extension hook](../../../spec/007-Stories.md));
it mirrors the framework's
[spec/001 registrar query API](../../../spec/001-Registration.md)
shape over Story's own kinds. Story registers the seven canonical
tags at load.

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

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

### Machine lifecycle on variant unmount

When `destroy-variant!` fires, any
[spec/005 state machines](../../../spec/005-StateMachines.md) the
variant's lifecycle spawned receive their `:rf.machine/destroy` event
as part of frame teardown (per
[spec/005 §`:rf.machine/destroy`](../../../spec/005-StateMachines.md#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx),
rf2-rkedz). The destroy event runs the actor's `:exit` action,
dissociates its snapshot at `[:rf/machines <actor-id>]`, and clears
its event handler from the frame-local registry — symmetric with the
`:rf.machine/spawn` that brought the actor into being. Story is a
passive consumer of this contract; the variant frame's own
`destroy-frame!` walk drives the destroy emissions, and Story does not
add a parallel cleanup path. Authors writing `:exit` actions that
need teardown semantics (close a websocket, cancel a timer, persist
state) should rely on this contract rather than the variant's own
unmount hook.

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

### Loader failure modes

Phase 1 can fail in exactly three named ways. Each case is captured
deterministically — the runtime records an assertion into
`:rf.story/assertions`, parks the lifecycle machine, and the canvas
projects the failure into the variant pane (per
[`003-Render-Shell.md`](003-Render-Shell.md) §Canvas + skeleton). The
play sequence never runs; `(run-variant)` resolves with
`assertions-passing?` false and the assertion vector populated.

| Failure mode | Trigger | Lifecycle state | Recorded assertion | Worked example (counter testbed) |
|---|---|---|---|---|
| **Throw** — loader handler raises | A `:loaders` event's registered handler throws or rejects synchronously. | Machine transitions to `:error` via `event-errored`. | `{:assertion :rf.error/exception :phase :phase-1-loaders :error {:message ... :stack ... :data ...} :passed? false}` | `:story.counter-diagnostics/loader-throws` (handler `(throw (js/Error. ...))` in `:counter/throw-deterministic`) |
| **Reject** — loader emits a typed rejection | A `:loaders` event handler throws an `ex-info` whose `ex-data` carries a `:kind :loader-rejection` (or equivalent author-chosen marker). Same record-and-park path as Throw; the `:data` slot preserves the rejection's `ex-data` so test diagnostics can assert on it. | Machine transitions to `:error`. | Same shape as Throw, with `:error {:data {:kind :loader-rejection ...}}` round-tripped through `re-frame.elision/elide-wire-value` (per §Privacy above). | `:story.counter-matrix/loader-rejects` (handler `(throw (ex-info ... {:kind :loader-rejection}))`) |
| **Never-complete** — loader drain settles but predicate stays false | `:loaders-complete-when` is a predicate event-id whose handler keeps `[:rf.story/loaders-complete?]` `false` indefinitely (or returns a vector-of-event-vectors that the runtime never observes drain). | Machine parks at `:loading`. The runtime records a deterministic assertion when the loader cascade has no further events to dispatch and the predicate is still false. | `{:assertion :rf.error/loader-incomplete :phase :phase-1-loaders :passed? false}` | `:story.counter-matrix/loader-never-completes` (predicate `:counter/loader-never-ready?` assoc's `:rf.story/loaders-complete? false`) |

**Async timeout.** There is no built-in wall-clock timeout for phase 1.
A variant whose `:loaders-complete-when` predicate genuinely awaits an
async event (websocket first message, HTTP response) waits as long as
the underlying fx takes. Authors who want a deterministic deadline
should write a `:loaders-complete-when` that combines their async
predicate with a timeout event (e.g. `[[:my.fixture/ready?] [:my.fixture/timeout-after 5000]]`)
and emit `:rf.error/loader-incomplete` from the timeout handler.
**Story does not own this knob** — wall-clock is the host's call;
deterministic test surfaces use the Never-complete path above instead.

**Cancellation.** Cancelling a variant mid-load (sidebar navigation,
hot-reload, `destroy-variant!`) tears down the variant's frame; the
loader's in-flight fx — and any long-lived fx the loader opened —
**are NOT auto-cancelled by the framework**. See §Loader teardown
contract below for the recommended pattern.

The diagnostic variants under
`tools/story/testbeds/counter_with_stories/stories.cljs` are the
canonical worked examples — every CI run exercises the three failure
paths against `run-variant` (see
`tools/story/testbeds/counter_with_stories/stories_cljs_test.cljs`
`diagnostic-loader-exception-records-failure` +
`matrix-variants-registered`). Tutorial authors writing a Chapter 8
"Loaders + async" walkthrough should cite these variants as the
contract demonstration.

### Loader error states

This section expands the table above into the per-mode mechanics —
what is observable, how the record lands, how the lifecycle settles,
and how `:loaders-complete-when` interacts with each mode. The
failures land via the same `:rf.error/exception` /
`:rf.error/loader-incomplete` record shape as every other Story
emission site, but the *route* an exception takes from the failing
handler to `[:rf.story/assertions]` differs by failure mode and by
how the exception was raised. Authors writing loader code should be
able to predict which route they'll trip and what the resulting
assertion record will contain.

**Throw — synchronous exception from a loader handler.** A `:loaders`
event handler that throws (any `Throwable` on JVM, any thrown value on
CLJS — typically a `js/Error` or `ExceptionInfo`) is caught by the
runtime's phase-1 driver in two complementary places:

1. **Direct `try/catch` around `dispatch-sync`** inside
   `runtime/run-loaders!`. Catches synchronous throws that escape
   re-frame's interceptor chain (rare — most handler throws are
   caught one level deeper).
2. **Per-phase trace listener (rf2-z2dq8)** registered around the
   loader walk. re-frame's interceptor chain catches handler-internal
   throws and emits `:rf.error/handler-exception` trace events rather
   than re-throwing. The listener collects these events into a
   per-frame `pending-exceptions` atom; the driver's `finally` block
   then calls `play/drain-pending-exceptions! variant-id
   :phase-1-loaders` to project each captured event onto
   `[:rf.story/assertions]` as a `:rf.error/exception` record.

The record carries `:phase :phase-1-loaders` (distinguishing the
loader phase from `:phase-2-events`, `:phase-3-render`, and
`:phase-4-play` — every other re-frame phase records exceptions with
the same shape but a different `:phase` tag). The `:error` map
captures `:message`, `:stack`, and `:data` (the latter populated from
`ex-data` for `ExceptionInfo`, nil otherwise). The lifecycle machine
transitions to `:error` via `event-errored`; phases 2, 3, and 4 do
NOT run; `run-variant` resolves with the assertion vector populated
and `:lifecycle :error`. See §Error projection §Privacy below for the
elision contract on `:error :data`.

**Reject — typed rejection from a loader handler.** Story does not
distinguish "throw" from "reject" at the runtime level — both routes
produce the same `:rf.error/exception` record shape via the same
two-route capture path above. The convention is purely author-side:
when a loader emits a typed rejection it throws an `ex-info` whose
`ex-data` carries a marker key (`:kind :loader-rejection`,
`:kind :http/400`, or any author-chosen taxonomy). The marker
round-trips through `re-frame.elision/elide-wire-value` into the
record's `:error :data` slot so downstream test assertions and
diagnostic panes can pattern-match on the rejection kind. The
lifecycle settlement, phase-2/3/4 skip, and `run-variant` return
shape are identical to Throw. Authors who want different recovery
semantics per rejection-kind implement that in their own loader
handler (e.g. catch the rejection, dispatch a recovery event, do not
re-throw); the runtime records only what escapes the handler.

JS-side note. A `:loaders` handler that returns a rejected
`js/Promise` (rather than throwing synchronously) does not raise an
exception the runtime can capture — re-frame's event loop does not
await handler return values. Authors writing async loader work
should:

1. Use `:loaders-complete-when` (a predicate-event or vector-of-events)
   to hold the lifecycle in `:loading` until the async work settles, and
2. Dispatch a follow-up event from the promise's `.catch` handler that
   either re-raises (taking the Throw route above) or records its own
   assertion via `:rf.assert/*`.

The runtime's role is to record exceptions that escape, not to
discover async failures the host code chose not to surface.

**Never-complete — drain settles, predicate stays false.** This is the
non-exception failure mode. The runtime's phase-1 driver dispatches
every `:loaders` event, drains re-frame's run-to-completion queue,
then evaluates `:loaders-complete-when`. When the predicate returns
falsy after the drain settles, the runtime records a
`:rf.error/loader-incomplete` assertion (note: NOT
`:rf.error/exception` — never-complete is a contract failure, not an
exception) onto `[:rf.story/assertions]` with `:phase :phase-1-loaders`,
the predicate value verbatim in `:predicate`, and a human-readable
`:reason` string. The lifecycle machine PARKS at `:loading` — it does
NOT transition to `:error`. Phases 2, 3, and 4 do NOT run; the
canvas's loading skeleton (rf2-0s4p1) stays engaged; `run-variant`
resolves with `:lifecycle :loading` and the assertion vector
populated.

No built-in wall-clock timeout. The runtime evaluates the predicate
exactly once per loader-cascade settlement; it does NOT poll. Authors
who want a deterministic deadline write a `:loaders-complete-when`
that combines the async predicate with a timeout event
(e.g. `[[:my.fixture/ready?] [:my.fixture/timeout-after 5000]]`) and
have their timeout handler assoc `:rf.story/loaders-complete? false`
into the variant frame's app-db. Story does not own wall-clock; the
host's timeout fx + a custom predicate-event is the supported pattern.

**`:loaders-complete-when` interaction with errors.** When a loader
THROWS (or REJECTS), `:loaders-complete-when` is NOT evaluated — the
machine transitions to `:error` from inside the loader walk, before
the predicate-evaluation step runs. So an author whose predicate
asserts on app-db state will not see it reached on a throw; the
`:rf.error/exception` record stands alone. Conversely a predicate
that returns falsy via a soft branch (no exception thrown, just
"not ready yet") routes to Never-complete; the
`:rf.error/loader-incomplete` record stands alone. The two failure
modes are mutually exclusive on a single `run-variant` invocation.

**Phase-1 vs phase-4 exception records.** The `:phase` tag on the
record is the primary disambiguator. Both phases use the same
`:rf.error/exception` shape and the same `play/drain-pending-
exceptions!` capture path (the function takes a `phase` argument
specifically so callers stamp the originating phase). Tools reading
`[:rf.story/assertions]` filter on `:phase :phase-1-loaders` to
isolate loader failures from event / render / play failures. Failures
in phase 2 (`:events`) record with `:phase :phase-2-events`; render
errors in phase 3 record with `:phase :phase-3-render`; play errors
in phase 4 record with `:phase :phase-4-play`. The shape uniformity
makes assertion-list consumers shape-agnostic; the `:phase` tag is
the only per-phase distinction.

**`run-variant` return shape under each mode.** The result map's
`:lifecycle` slot reflects the parked discrete state:
`:error` (Throw / Reject) or `:loading` (Never-complete). The
`:assertions` slot carries the record. `:app-db` reflects whatever
state the frame held at the failure boundary (loader phase may have
written some intermediate state before throwing; the runtime does not
roll back). `:rendered-hiccup` is nil under failure (phase 3 is
skipped). Consumers querying `passed?` aggregate over `:assertions`;
any `:rf.error/*` record drops the aggregate to false.

### Loader teardown contract

A `:loaders` event handler that opens a long-lived fx — a websocket
subscription, a polling interval, a Firestore listener, a geolocation
watcher — owns the cleanup of that fx when the variant goes away.
Frame teardown (`destroy-variant!` → `destroy-frame!`) tears down the
variant's app-db and clears the frame-local handler registry, but it
does **not** reach into externally-owned resources the loader opened.
Without an explicit teardown path, those resources leak past variant
destroy: the user clicks the next sidebar entry and the previous
variant's websocket keeps dispatching events (which now land into a
torn-down frame and may either no-op or surface as
`:rf.error/dispatched-into-destroyed-frame` warnings).

**Three recommended patterns**, in preferred order:

1. **Spawn a state machine in the loader; rely on `:rf.machine/destroy`.**
   The state-machine path described in §Machine lifecycle on variant
   unmount above is the canonical answer for any
   resource-with-a-lifetime. The loader event spawns an actor with an
   `:exit` action that closes the resource; when `destroy-variant!`
   tears down the frame, spec/005's destroy walk fires the actor's
   `:exit` action, and the resource closes deterministically. No new
   Story-side surface is needed — this works today.

   ```clojure
   (rf/reg-event-fx :feed/subscribe
     (fn [{:keys [db]} _]
       {:fx [[:rf.machine/spawn
              {:id        :feed.subscription
               :machine   :feed-subscription/machine
               :exit      [[:feed/close-socket]]}]]}))

   (story/reg-variant :story.feed/live
     {:loaders [[:feed/subscribe]]
      :loaders-complete-when [[:feed/first-tick-received]]
      :play    [[:rf.assert/path-equals [:feed :latest] :some/expected]]})
   ```

   Reuses the framework's existing destroy contract; no new variant
   slot to learn. Recommended for any production-shape loader.

2. **`:teardown` slot on `:frame-setup` decorators** — symmetric with
   `:init`. When a `:frame-setup` decorator's `:init` opened the
   resource, the matching `:teardown` events vector closes it at
   destroy. Best fit when the resource is owned by a decorator stack
   the variant references (the resource scope matches the decorator
   scope). See [`001-Authoring.md`](001-Authoring.md) §reg-decorator
   `:teardown`.

3. **`:loaders-teardown` slot on the variant body** (rf2-lqs0b) —
   symmetric with `:loaders` on the variant body itself. Best fit when
   the resource is opened by a variant-level `:loaders` event and the
   cleanup is too small to justify spawning a machine actor (single
   `dispatch-sync` of a `:foo/cancel` event).

   ```clojure
   (rf/reg-event-fx :ws/subscribe
     (fn [{:keys [db]} _]
       {:fx [[:rf.host/open-socket {:url "wss://..."}]]
        :db (assoc db :ws/subscribed? true)}))

   (rf/reg-event-fx :ws/unsubscribe
     (fn [{:keys [db]} _]
       {:fx [[:rf.host/close-socket]]
        :db (dissoc db :ws/subscribed?)}))

   (story/reg-variant :story.feed/live
     {:loaders          [[:ws/subscribe]]
      :loaders-teardown [[:ws/unsubscribe]]
      :loaders-complete-when [[:ws/first-tick-received]]})
   ```

   `:loaders-teardown` events fire BEFORE the decorator `:teardown`
   walk — see §What the runtime guarantees below for the ordering
   rule. The slot is open-ended: a variant may declare any number of
   teardown events, dispatched in declared order (symmetric with
   `:loaders`). Exceptions are recorded, not aborted (same shape as
   decorator `:teardown`).

**Anti-pattern: a bare `:loaders` event that opens a websocket without
any of the above.** A reader who lands on Chapter 8 of the tutorial
and writes `:loaders [[:ws/subscribe]]` without spawning a machine,
using a teardown-bearing decorator, or declaring `:loaders-teardown`
will leak the subscription past variant destroy. The diagnostic is a
console flood when navigating between variants. The fix is one of the
three patterns above; the cheapest is pattern #3 (one extra slot on
the variant body).

**What the runtime guarantees.** On `destroy-variant!`:

1. Per-variant assertion accumulators are dropped
   (`frames/clear-stub-call-log!`).
2. Lifecycle watchers are cleared
   (`loaders/clear-watchers!`).
3. **The variant body's `:loaders-teardown` events dispatch-sync into
   the variant frame in declared order.** Symmetric counterpart of
   `:loaders` (rf2-lqs0b).
4. **The variant's `:frame-setup` decorator `:teardown` events
   dispatch-sync into the variant frame** in reverse-declaration order
   (innermost decorator's teardown runs first, outermost last).
   Symmetric counterpart of decorator `:init`.
5. Spec/005 machines spawned into the variant frame receive
   `:rf.machine/destroy` (existing contract).
6. `rf/destroy-frame!` runs the frame's own teardown walk.

Steps 3, 4, and 5 are author-observable; steps 1, 2, and 6 are
framework internals.

**Ordering rule.** Step 3 (variant body `:loaders-teardown`) runs
BEFORE step 4 (decorator `:teardown`). Intuition: a variant's loaders
open the narrowest, most-recently-installed resources; the decorator
stack opens wider, longer-lived resources. Cleanup walks innermost-
first, mirroring function-scope cleanup. Within step 4 the
reverse-declaration walk (variant-level decorators before story-level
decorators) extends the same rule across the decorator stack.

**Exception handling.** Events that throw in steps 3 or 4 are caught
and projected onto the variant frame's `[:rf.story/assertions]` as
`:rf.error/exception` records — `:phase :phase-loaders-teardown` for
step 3, `:phase :phase-teardown` for step 4. The walk continues; the
next event in the vector still runs; subsequent teardown phases still
run; `rf/destroy-frame!` still completes. Teardown never aborts
destroy.

**What the runtime does NOT guarantee.** Wall-clock timeout for
teardown; cancellation of in-flight async fx (HTTP request, websocket
message in transit) that were dispatched before teardown but resolve
after; deterministic ordering when more than one of patterns #1, #2,
or #3 target the same resource (don't do this — pick one pattern per
resource).

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

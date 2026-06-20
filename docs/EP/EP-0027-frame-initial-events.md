# EP-0027: Frame Initial Events

Status: proposal
Type: standards-track

> **Design note.** This draft folds in the adversarial review that previously
> lived at the end of this document. The review found the central direction
> sound, but identified four missing decisions: Story `:setup` has a tagged
> grammar, frame construction has different top-level and mid-cascade regimes,
> reset must deliberately re-apply recorded construction state, and
> `frame-provider` must run setup once per frame lifetime rather than once per
> mount. Those decisions are now part of the specification below.

## Abstract

This EP replaces `:on-create` with `:initial-events`, an ordered event script
recorded on a frame and run as part of frame construction after `:initial-db`.

Frame initialization has two orthogonal data forms:

- `:initial-db` directly seeds app-db.
- `:initial-events` runs ordinary events through the event pipeline to produce
  additional initial state.

One boot event is represented as a one-step script:

```clojure
{:initial-events [[:app/boot]]}
```

Multi-step setup uses the same shape:

```clojure
{:initial-events [[:checkout/open]
                  [:checkout/add-item "SKU-1"]
                  [:checkout/select-shipping :express]]}
```

The frame setup runner is shared with Story only at the normalized dispatch-step
layer. Frame `:initial-events` and Story `:setup` keep different authoring
grammars because they live at different lifecycle layers.

## Motivation

Frame construction already has a direct data seed:

```clojure
(rf/make-frame
  {:id :counter/story
   :images [counter-image]
   :initial-db {:counter/value 0}})
```

It also has the older single-event lifecycle hook:

```clojure
(rf/make-frame
  {:id :counter/story
   :images [counter-image]
   :on-create [:counter/initialise]})
```

That hook is too small for the way tests, Story variants, and examples actually
prepare a useful frame. They commonly do this immediately after construction:

```clojure
(let [frame (rf/make-frame {:id :checkout/story
                            :images [checkout-story-image]
                            :initial-db {}})]
  (rf/dispatch-sync [:checkout/open] {:frame :checkout/story})
  (rf/dispatch-sync [:checkout/add-item "SKU-1"] {:frame :checkout/story})
  (rf/dispatch-sync [:checkout/add-item "SKU-2"] {:frame :checkout/story})
  (rf/dispatch-sync [:checkout/select-shipping :express] {:frame :checkout/story})
  frame)
```

That event sequence is not incidental glue. It is part of the constructed
runtime instance:

- tests need it to be deterministic;
- Story variants need it to be inspectable;
- tools need to know how the frame reached this state;
- failures should point to a specific setup event;
- reset should be able to return to the full constructed initial state.

Hiding the sequence in ad hoc post-construction dispatches makes the frame
declaration incomplete. Hiding it inside one large boot event preserves event
semantics, but it turns an inspectable scenario into handler-private code.

There is also a safety motivation. Previous examples have accidentally run a
boot event twice: once through a frame hook and once through an explicit
post-construction dispatch. Boot handlers are often not harmless; they can start
machines, emit effects, increment counters, or stamp durable state. A frame
should have exactly one declarative event-based initialization surface, and it
should state when that surface runs.

## Goals

- Add `:initial-events` to `reg-frame`, `make-frame`, and owned
  `frame-provider` construction maps.
- Retire `:on-create` rather than widening it.
- Make one boot event and multi-event setup use one unambiguous script shape.
- Allow per-step dispatch opts, especially `:rf.cofx`, so setup is replayable
  and deterministic.
- Define top-level construction and mid-cascade construction separately.
- Make `reset-frame!` restore the recorded `:initial-db` and replay the recorded
  `:initial-events`.
- Make idempotent re-registration and `frame-provider` re-mount record new setup
  data without replaying it into live durable state.
- Align Story `:setup` with frame setup through a shared normalized dispatch-step
  runner while keeping Story's tagged authoring grammar.

## Non-Goals

- Do not replace `:initial-db` with events. Direct app-db seeding and event
  setup are separate tools.
- Do not make frame construction own Story `:script`, DOM interaction, waits,
  assertions, reporting, or post-render behavior.
- Do not let setup steps target a sibling frame.
- Do not make asynchronous effects complete before `make-frame` returns.
- Do not make the event-script runner public in the first design.
- Do not add setup-step labels in the first grammar.
- Do not provide a compatibility shim for `:on-create`; re-frame2 is pre-alpha.

## Terminology

**Initial db** is the value supplied through `:initial-db`. It is direct app-db
data, not an event.

**Initial events** are the ordered setup steps supplied through
`:initial-events`.

**Setup script** means the whole `:initial-events` vector.

**Setup step** means one member of the setup script.

**Normalized dispatch step** means:

```clojure
{:event event-vector
 :opts  dispatch-opts}
```

The shared runner consumes normalized dispatch steps. Each authoring surface
owns its own normalization into that shape.

**Top-level construction** means frame construction performed when no event
cascade is currently running.

**Mid-cascade construction** means frame construction performed from inside an
event handler or another event-cascade context.

## Specification

### Construction Keys

Frame construction accepts:

```clojure
:initial-db
:initial-events
```

`:on-create` is retired. Supplying `:on-create` to a public frame construction
map MUST fail loudly.

`:initial-db` directly seeds app-db for a newly created frame. This EP changes
`:initial-db` from a first-create-only implementation detail into recorded
construction state: the latest registered value is the value `reset-frame!`
uses when it returns the frame to its constructed initial state.

`:initial-events` is an ordered vector of setup steps. Omitting
`:initial-events` and supplying `[]` both mean "no setup events."

A bare event vector is not a valid top-level `:initial-events` value. This is
deliberate:

```clojure
;; valid: one-step script
{:initial-events [[:app/boot]]}

;; invalid: ambiguous top-level shape
{:initial-events [:app/boot]}
```

The common single-event case pays one extra bracket. The trade-off is worth it:
accepting "one event or a vector of events" would recreate the overloaded shape
this EP removes from `:on-create`.

### Setup Step Grammar

A setup step is either a bare event vector:

```clojure
[:checkout/open]
```

or a map:

```clojure
{:event [:todo/add "milk"]
 :opts  {:rf.cofx {:rf/time-ms 1781078400123}}}
```

The normalized shape is:

```clojure
{:event event-vector
 :opts  dispatch-opts}
```

Map form has these rules:

- `:event` is required.
- `:opts` defaults to `{}`.
- `:event` MUST be a non-empty event vector.
- `:opts` MUST be a map.
- Unknown keys fail loudly.
- A user-supplied `:frame` inside `:opts` fails loudly.

The target frame is always the frame being constructed or reset. A setup script
must not mutate a sibling frame by smuggling `{:frame ...}` through dispatch
opts.

Per-step opts are ordinary dispatch opts after the `:frame` restriction. They
are useful for causal facts:

```clojure
(rf/make-frame
  {:id :todo/story
   :images [todo-image]
   :initial-events
   [{:event [:todo/add "milk"]
     :opts  {:rf.cofx {:rf/time-ms 1781078400123}}}]})
```

Reset replays recorded setup steps verbatim, including recorded `:opts` and
recorded `:rf.cofx`. It does not re-stamp a fresh `:rf/time-ms`.

### Construction Order

For a new frame, construction proceeds in this order:

```text
1. validate the construction map
2. resolve images and the frame's image generation
3. create and register the live frame value, when an id is supplied
4. install frame configuration, classification, interceptors, and overrides
5. seed app-db with :initial-db, or {} when none is supplied
6. run or schedule :initial-events
7. return the frame value
```

Initial events run through the ordinary event pipeline. They are not a direct
write into app-db. They produce normal event/cascade/trace records, see the
frame's resolved image generation, and observe the frame configuration already
installed in steps 3 and 4.

### Top-Level Construction

During top-level construction, setup steps run synchronously in vector order
before `make-frame` or `reg-frame` returns.

The next setup step starts only after the previous step's synchronous event
cascade has completed. Child dispatches emitted by a setup event follow the
normal queue/drain rules for that event. By the time construction returns, all
synchronous setup cascades have reached a fixed point.

Asynchronous effects started by setup events are not awaited. The guarantee is
run-to-completion of the synchronous cascade for each setup step, not completion
of future continuation events.

### Mid-Cascade Construction

Frame construction is legal inside an event handler. In that regime, the
implementation MUST NOT call `dispatch-sync` for setup steps, because
`dispatch-sync` inside a handler is already an error.

Instead, the setup script is enqueued as one ordered batch on the new frame's
queue. `make-frame` returns after the frame has been created and the setup batch
has been scheduled, not after setup has completed.

The queued setup batch preserves step order and drains before unrelated events
can interleave on that fresh frame. Events emitted by setup steps still follow
normal child-dispatch semantics.

This mirrors the existing `:on-create` two-regime contract, but makes the
multi-step ordering explicit. Top-level construction returns a fully initialized
frame. Mid-cascade construction returns a frame whose setup has been scheduled.

### Failure Semantics

Invalid construction maps, invalid setup scripts, invalid step maps,
non-event-vector steps, and step opts containing `:frame` fail loudly before the
affected setup step runs.

If a setup step throws or otherwise fails during first creation:

- no later setup steps run;
- construction fails loudly;
- the error data MUST identify the setup step index and event;
- the implementation MUST tear down or unregister the partially created frame so
  failed construction does not leave a live half-created frame.

On the owned `frame-provider` path, cleanup follows the provider's
StrictMode-safe deferred-destroy discipline. A failed first mount must not leave
a pending deferred destroy or a registered-but-dead id.

A setup script is replayable as a causal sequence. It is not a transaction.
External effects fired by successful earlier setup steps are not rolled back
when a later step fails. Keep irreversible effects out of setup steps, or make
them idempotent.

Diagnostic error ids belong in the implementation slice, but they MUST live in
the `:rf.error/*` family and carry enough data for tooling to show the failed
step.

### Re-Registration And Hot Reload

Calling `reg-frame` or `make-frame` again with an existing live frame id performs
EP-0024 idempotent replacement:

- frame configuration and image generation are updated;
- the recorded `:initial-db` is replaced by the new value, or cleared when the
  key is absent;
- the recorded `:initial-events` script is replaced by the new value, or cleared
  when the key is absent;
- live app-db and runtime-db are preserved;
- `:initial-events` are not replayed.

This is the same hot-reload trade-off frames already make. Editing setup data in
source records the new setup for the next explicit reset; it does not silently
replay setup into live durable state.

### Reset

`reset-frame!` returns a frame to its full constructed initial state:

```text
1. destroy the current frame state and runtime partitions
2. recreate the frame from the latest recorded construction config
3. seed app-db with the latest recorded :initial-db, or {} when none is recorded
4. run or schedule the latest recorded :initial-events
```

This is a deliberate behavior change from the prior `:on-create` model, where
reset cleared app-db to `{}` and re-fired one creation event. Under this EP,
`:initial-db` is part of the recorded reset baseline.

Tests and Story reset buttons should use `reset-frame!` when they want to return
to the scenario's constructed initial state. For an app-db-only reset that
preserves runtime-db, use the app-db-specific reset surface instead.

### Frame Provider

Owned `rf/frame-provider` accepts `:initial-db` and `:initial-events` in the same
construction map as `:id` and `:images`:

```clojure
[rf/frame-provider {:id :quickstart/counter
                    :images [counter-image]
                    :initial-events [[:counter/initialise]]}
 [counter]]
```

The provider runs `:initial-events` on the first mount of a given frame id only.
A re-mount under the same id, including React StrictMode dev double-invoke, hot
reload, or Story re-evaluation, uses idempotent replacement: setup data is
re-recorded, but the script is not replayed into the live frame.

Setup fires once per frame lifetime, not once per mount. An explicit
`reset-frame!` starts a new constructed lifetime and replays the recorded setup.

### Story Setup

Story has a larger lifecycle:

```text
loaders -> setup -> render -> script
```

Core frame `:initial-events` aligns with Story `:setup`, not Story `:script`.
The two surfaces do not share an authoring grammar.

Frame `:initial-events` steps are bare events or maps:

```clojure
:initial-events [[:auth/initialise]
                 {:event [:auth/email-changed "alice@example.com"]
                  :opts  {:rf.cofx {:rf/time-ms 1781078400123}}}]
```

Story `:setup` remains a tagged-step grammar:

```clojure
:setup [[:dispatch [:auth/initialise]]
        [:dispatch [:auth/email-changed "alice@example.com"]]]
```

The shared primitive is below both grammars. Frame construction normalizes its
steps to `{:event ... :opts ...}`. Story normalizes dispatch-flavored setup
steps to the same shape, after its loaders complete. Non-dispatch Story setup
tags, if any are added by Story, remain Story-owned.

Story `:script` is post-render behavior under test. It may contain assertions,
DOM interaction, waits, reporting, and runner-owned concerns. A frame
constructor does not own that phase.

When a Story has async loaders, its setup cannot be encoded entirely in
`make-frame :initial-events`, because setup must run after loaders complete.
Story should create or acquire the variant frame, wait for loaders, then feed the
normalized setup dispatch steps to the shared runner.

## Rationale

### Why Not Widen `:on-create`

Widening `:on-create` to mean "one event or many events" keeps the old name but
creates a worse shape. `:on-create [:app/boot]` is one event; `:on-create
[[:a] [:b]]` is a script; `:on-create [:a :b]` becomes ambiguous to teach.

`:initial-events` says what it contains: events, plural, in order. One event is
a one-step script.

### Why Keep `:initial-db`

Direct data is still the simplest way to say "this is the starting app-db."
Events are the right way to exercise the event pipeline, derive state, stamp
coeffects, and prove a scenario through public behavior. The two mechanisms are
orthogonal.

The useful mental model is:

```text
constructed app-db = initial-db, then initial-events
```

### Why Reset Re-Applies `:initial-db`

The phrase "reset to initial state" should mean the full constructed initial
state, not `{}` plus a boot event. Since `:initial-db` is part of frame
construction, reset must restore it before replaying setup events.

This makes `:initial-db` recorded construction state. Re-registration may update
it for a future reset, but does not apply it to live app-db immediately.

### Why Top-Level And Mid-Cascade Differ

Top-level construction can run setup synchronously because no event cascade is
already in progress. Mid-cascade construction cannot do that without violating
the existing "no `dispatch-sync` inside a handler" rule.

The two-regime contract is less magical than pretending every construction site
is synchronous. It also preserves the run-to-completion rule for the creating
event.

### Why Story Shares The Runner But Not The Grammar

Story setup is a tagged-step language because Story has more lifecycle concepts
than a frame constructor: loaders, setup, render, script, assertions, and
runner-owned reporting. Frame setup is just event initialization.

Forcing both surfaces into one authoring grammar would either make frame setup
too ceremonial or make Story setup too small. Normalizing both into a shared
dispatch-step runner gives the implementation reuse without muddling the public
vocabulary.

## Backwards Compatibility And Migration

re-frame2 is pre-alpha. No compatibility shim is required.

`:on-create` must be removed from public specs, examples, tools, guide text, and
skills. A public frame construction map that still supplies `:on-create` should
fail loudly.

Migration is mechanical:

```clojure
;; old
{:on-create [:app/boot]}

;; new
{:initial-events [[:app/boot]]}
```

Ad hoc post-construction setup:

```clojure
(let [frame (rf/make-frame frame-spec)]
  (rf/dispatch-sync [:checkout/open] {:frame :checkout/story})
  (rf/dispatch-sync [:checkout/add-item "SKU-1"] {:frame :checkout/story})
  frame)
```

becomes construction data:

```clojure
(rf/make-frame
  (assoc frame-spec
         :initial-events [[:checkout/open]
                          [:checkout/add-item "SKU-1"]]))
```

Spec 002 currently contains older text saying frames always start with `{}` and
initialization happens through `:on-create`. Graduation of this EP must update
that text: frames start from recorded `:initial-db` when supplied, then run or
schedule `:initial-events`.

## Reference Implementation Plan

Expected implementation slices:

1. Add an internal setup-step normalizer for frame `:initial-events`.
2. Add an internal normalized dispatch-step runner over
   `{:event event-vector :opts dispatch-opts}`.
3. Add `:initial-events` to `reg-frame`, `make-frame`, and owned
   `frame-provider` construction specs.
4. Store recorded `:initial-db` and `:initial-events` as reset baseline state.
5. Retire `:on-create` from implementation, specs, examples, tools, guide text,
   and skills.
6. Reject bad top-level shapes, bad step maps, non-event-vector steps, unknown
   step keys, and step opts containing `:frame`.
7. Implement top-level synchronous setup and mid-cascade queued-batch setup.
8. Make setup failure clean up first-create frames, including the owned
   `frame-provider` path.
9. Make idempotent re-registration and provider re-mount record setup data
   without replaying it.
10. Make `reset-frame!` restore recorded `:initial-db` and replay recorded
    `:initial-events`.
11. Normalize Story `:setup` dispatch steps into the shared dispatch-step runner
    after loaders complete, while leaving Story `:script` post-render.
12. Add diagnostics carrying setup phase, step index, event vector, and frame id.

## Acceptance Criteria

A complete implementation satisfies all of:

- `:initial-events` works for `reg-frame`, `make-frame`, and owned
  `frame-provider`.
- `:on-create` is rejected in public frame construction maps.
- One setup event is written as `[[:event/id ...]]`; bare top-level event vectors
  are rejected.
- Map steps support `{:event ... :opts ...}` and reject unknown keys.
- Step opts containing `:frame` fail loudly.
- Per-step `:rf.cofx` reaches the event handler and is replayed verbatim on
  reset.
- Top-level construction runs setup synchronously before returning.
- Mid-cascade construction queues setup as one ordered batch on the created
  frame.
- Setup failure identifies the failing step and leaves no half-created live
  frame.
- External effects from earlier successful setup steps are not claimed to roll
  back.
- Idempotent re-registration records new `:initial-db` and `:initial-events`
  without applying or replaying them to live state.
- Owned `frame-provider` runs setup once per frame lifetime, not once per
  StrictMode/hot-reload re-mount.
- `reset-frame!` recreates the frame from recorded construction state, seeding
  `:initial-db` before replaying `:initial-events`.
- Story `:setup` dispatch steps and frame `:initial-events` both lower to the
  shared normalized dispatch-step runner, while Story `:script` remains
  post-render and Story-owned.
- Specs, API docs, guide text, examples, skills, and conformance no longer teach
  `:on-create`.

## Rejected Alternatives

### Keep `:on-create`

Rejected. `:on-create` names a lifecycle callback, not an ordered event script.
Keeping it would either preserve the too-small single-event surface or widen it
into an overloaded shape.

### Accept A Bare Event Vector As `:initial-events`

Rejected. `:initial-events [:app/boot]` for one event and `:initial-events
[[:a] [:b]]` for many events looks convenient, but it leaves `[:a :b]`
ambiguous to readers: one event with one argument, or two event ids? The
one-script-shape rule is noisier but clearer.

### Share Story And Frame Authoring Grammars

Rejected. Story setup is a tagged-step language; frame setup is an event script.
The shared implementation unit is the normalized dispatch step, not the authoring
surface.

### Replay Setup On Every Provider Mount

Rejected. Replaying setup on every mount would double-fire boot effects under
React StrictMode, hot reload, and Story re-evaluation. Setup runs once per frame
lifetime and again only on explicit reset.

### Await Async Effects During Frame Construction

Rejected. Frame construction guarantees synchronous cascade completion. Async
continuations are ordinary future events. Waiting for arbitrary async effects
would make frame construction host-dependent and would blur the event queue
model.

### Make The Runner Public Immediately

Rejected for the first design. The runner is an internal/tool-tier primitive
until there is a concrete second public use outside frame construction and Story.

### Add Step Labels Now

Rejected for the first grammar. Diagnostics already carry step indexes and event
vectors. Labels can be added later if tooling demonstrates a real need.

## Recommendation

Accept `:initial-events` as the single event-based frame initialization surface
and retire `:on-create`.

The final design keeps the good part of the original proposal -- setup as
ordered, replayable construction data -- while making the lifecycle precise:
top-level setup is synchronous, mid-cascade setup is queued, reset restores the
full recorded construction baseline, provider re-mount does not replay setup,
and Story shares the normalized dispatch-step runner without adopting the frame
grammar.

# EP-0027: Frame Initial Events

Status: proposal
Type: standards-track

> This EP proposes `:initial-events` as the single event-based frame
> initialization surface, replacing `:on-create`. It treats a frame's setup
> script as construction data: deterministic, replayable, inspectable, and
> shared with Story setup where appropriate. If accepted, the normative homes
> are `spec/API.md`, `spec/002-Frames.md`, and the Story/tooling specs that
> define variant setup behavior.

## Abstract

Frame construction currently has direct state seeding (`:initial-db`) and a
single-event lifecycle hook (`:on-create`). Tests and Stories often need
something slightly richer: after creating a frame, run a deterministic sequence
of events to put it into a useful state.

This EP proposes `:initial-events`, an ordered event script run during frame
construction after `:initial-db` is installed. One boot event is represented as a
one-element vector. Multi-event scenarios use the same shape. `:on-create` is
retired rather than widened.

## Motivation

The current simple shapes are useful:

```clojure
(rf/make-frame
  {:id :counter/story
   :images [counter-image]
   :initial-db {:counter/value 0}})
```

and:

```clojure
(rf/make-frame
  {:id :counter/story
   :images [counter-image]
   :on-create [:counter/initialise]})
```

But tests and Stories commonly do this immediately after construction:

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

That event sequence is not random imperative glue. It is part of the constructed
runtime instance:

- tests need it to be deterministic;
- Stories need it to be inspectable;
- tools want to know how the frame reached this state;
- failures should point to a specific setup event;
- reset should know whether to replay setup.

Hiding the sequence in ad hoc `dispatch-sync` calls after `make-frame` means the
frame declaration is incomplete. Hiding it inside one boot event also loses the
scenario as data.

## Goals / Non-Goals

Goals:

- add `:initial-events` to frame construction;
- retire `:on-create`;
- allow one boot event and multi-event setup to use the same shape;
- allow per-event dispatch options for causal facts such as `:rf.cofx`;
- make reset replay the recorded initial event script after restoring
  `:initial-db`;
- align core frame setup with Story `:setup`, while keeping Story `:script`
  post-render and Story-owned;
- define one small event-script runner primitive that both frame construction
  and Story setup can reuse.

Non-goals:

- do not make frame construction own post-render Story scripts, DOM steps,
  assertions, waits, or reporter behavior;
- do not replace `:initial-db`;
- do not overload `:on-create` with "one event or a vector of events";
- do not let a frame setup script dispatch into another frame by supplying its
  own `:frame` target;
- do not make asynchronous effects complete before `make-frame` returns.

## Relationships

- **EP-0024** defines the unified frame construction and lifecycle surface. This
  EP adds a construction option to that surface and applies equally to
  `rf/make-frame` and UI-owned `rf/frame-provider` creation specs.
- **EP-0023** defines image-loaded frames. Initial events run against the
  resolved image generation of the newly created frame.
- **EP-0017** defines `:rf.cofx`, which is the dispatch option most commonly
  needed in deterministic setup scripts.
- **EP-0018** defines event handlers as coeffects-in, effects-out. Initial
  events use the same event pipeline; they are not a back door into app-db.

## Specification

This section is proposed normative text. It binds only if the EP is accepted.

### Frame construction keys

Frame construction supports:

```clojure
:initial-db
:initial-events
```

`:on-create` is retired.

`:initial-db` directly seeds the frame's app-db. `:initial-events` is an
ordered vector of setup steps dispatched synchronously to the newly created
frame.

One boot event is a one-step script:

```clojure
(rf/make-frame
  {:id :app
   :images [app-image]
   :initial-events [[:app/boot]]})
```

A scenario setup is the same shape with more events:

```clojure
(rf/make-frame
  {:id :checkout/story
   :images [checkout-image]
   :initial-events [[:checkout/open]
                    [:checkout/add-item "SKU-1"]
                    [:checkout/select-shipping :express]]})
```

### Execution order

Frame construction proceeds in this order:

```text
1. resolve images and create the live frame
2. register the frame id, if supplied
3. seed app-db with :initial-db, if supplied
4. run :initial-events, if supplied
5. return the frame value
```

Initial events are dispatched to the frame being constructed. The caller does
not pass `{:frame ...}` for each step.

Each setup step is an ordinary synchronous event dispatch into that frame. It
uses the frame's resolved image generation, runs the normal event pipeline, opens
the normal cascade/epoch boundary for that event, and records the event in the
frame's event stream. Child dispatches emitted by a setup event follow the same
queue/drain semantics as child dispatches emitted by any other event.

If a synchronous setup step fails, construction fails loud. If the frame was
registered before the failure, the implementation must tear it down or unregister
it so failed construction does not leave a live half-created frame.

Asynchronous effects started by initial events are not awaited by frame
construction. The guarantee is only run-to-completion of the synchronous event
cascade for each setup event.

### Setup step grammar

An initial event step is either a bare event vector:

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
 :opts dispatch-opts}
```

`:event` is required in map form. `:opts` defaults to `{}`.

User-supplied `:frame` inside step opts fails loud. The frame target is implicit
and is always the frame under construction. This prevents setup scripts from
accidentally mutating a sibling frame.

Per-event opts are ordinary dispatch opts, subject to the accepted dispatch
grammar. They are still per-cascade data: they do not mutate the frame's
recorded construction script, resolved image generation, or durable state except
through effects returned by the event pipeline. That includes causal facts:

```clojure
(rf/make-frame
  {:id :todo/story
   :images [todo-image]
   :initial-events
   [{:event [:todo/add "milk"]
     :opts  {:rf.cofx {:rf/time-ms 1781078400123}}}]})
```

### Reset and hot reload

`reset-frame!` restores the frame's recorded `:initial-db` and then reruns the
recorded `:initial-events`.

When `make-frame` is re-evaluated for an existing live frame id under the
EP-0024 idempotent replacement policy, the new `:initial-events` value is
recorded as the frame's construction script, but it is not automatically replayed
into existing app-db. Replaying setup into a live frame can duplicate work and
should happen only through an explicit reset or explicit event dispatch.

Hot reload may update the resolved image generation and the recorded setup
script. It must not silently replay the setup script into durable state.

### Relationship to `rf/frame-provider`

`rf/frame-provider` owns a frame lifecycle. Its frame creation map accepts
`:initial-events` with the same semantics as `rf/make-frame`:

```clojure
[rf/frame-provider {:id :quickstart/counter
                    :images [counter-image]
                    :initial-events [[:counter/initialise]]}
 [counter]]
```

The provider creates the frame, runs initial events, provides the frame id to
descendants, and destroys the frame on unmount according to EP-0024.

### Relationship to Story setup and script

Story has a higher-level lifecycle. The target Story vocabulary is:

```clojure
:setup   ;; pre-render preconditions
:script  ;; post-render behavior under test
```

The older names `:events` and `:play-script` are transitional names that lower
to `:setup` and `:script` respectively.

Core frame `:initial-events` aligns with Story `:setup`, not Story `:script`.

```text
rf/make-frame :initial-events
  = construct the frame's initial state before render

story/reg-variant :setup
  = Story-level preconditions before render; may lower to the same
    event-script runner as frame :initial-events

story/reg-variant :script
  = post-render play/test script; remains Story-owned
```

Story `:script` may contain assertions, DOM interaction, waits, reporting, and
other runner-owned concerns. A frame constructor has no business owning that
phase.

If Story loaders are asynchronous, Story may create the frame first, run
loaders, and then run setup through the shared event-script runner rather than
encoding setup solely in `make-frame :initial-events`. The semantics still
align; Story remains the owner of the full variant lifecycle.

## Rationale

`:initial-events` is boring in the right way. It says exactly what the key holds:
events that initialize the frame.

Keeping `:on-create` would produce two event-based initialization surfaces, and
widening it from one event to one event or many events would make the shape
harder to teach. One event is already expressible as a one-element
`:initial-events` vector.

This preserves the re-frame ethos: state changes go through events when you want
the event pipeline, but data stays data. A setup script is not a callback; it is
the first explicit segment of the frame's event stream.

## Backwards Compatibility

re-frame2 is pre-alpha. No compatibility shim is required.

Migration is direct:

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

## Bead Plan / Reference Implementation

Expected implementation slices:

1. Add an event-script normalizer and runner used by frame construction.
2. Add `:initial-events` to `rf/make-frame` and `rf/frame-provider` creation
   specs.
3. Retire `:on-create` from specs, implementation, examples, tools, and guide
   text.
4. Reject `:frame` inside per-step opts.
5. Make setup failure tear down or unregister the partially created frame.
6. Make `reset-frame!` restore `:initial-db` and then replay
   `:initial-events`.
7. Make hot re-`make-frame` record new initial events without replaying them.
8. Align Story `:setup` with the shared event-script runner while leaving Story
   `:script` post-render.
9. Add conformance tests for construction order, per-event `:rf.cofx`, failure
   cleanup, reset replay, hot reload non-replay, provider creation, and Story
   setup alignment.

Guide-impact assessment:

- `docs/guide/quickstart.md` should use `:initial-events` instead of
  `:on-create`.
- testing and Story docs can show deterministic setup as frame construction
  data rather than loose dispatch calls.
- provider examples should show `:initial-events` in the same map as `:id` and
  `:images`.

## Open Issues

1. **Should the event-script runner be public?** The recommended first step is
   internal or tool-tier. Make it public only if examples, Story, and tests need
   a reusable name outside frame construction.

2. **What should the exact failure value be when a setup step fails?** The
   contract should fail loud and identify the step index and event. The final
   error id belongs in the implementation bead.

3. **Should setup steps support labels?** A future map shape could allow
   `:label` for tooling, but this EP keeps the initial grammar minimal.

## Recommendation

Accept `:initial-events` and retire `:on-create`. The frame has two clean
initialization paths: `:initial-db` for direct data and `:initial-events` for an
ordered event setup script. Story `:setup` can share the same runner, and Story
`:script` remains the post-render test/play layer.

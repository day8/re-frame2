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
The open issues at the end are graduation blockers unless the operator records
that a question is intentionally deferred from the first `:initial-events`
contract.

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

Omitting `:initial-events` and supplying an empty vector both mean "no setup
events." A bare event vector is not a valid top-level `:initial-events` value;
one boot event is represented as a one-step script.

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

Setup steps run in vector order. The next step starts only after the previous
step's synchronous event cascade has completed. If a step fails, no later setup
steps run. Effects already committed by earlier successful synchronous steps
are not rolled back; the cleanup guarantee applies to the partially constructed
frame registration/lifecycle, not to arbitrary external effects.

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

The event value must be a non-empty event vector. The `:opts` value must be a
map. Unknown step-map keys are not part of the public grammar and must fail
loudly unless a later EP extends the setup step shape.

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
must happen only through an explicit reset or explicit event dispatch.

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

re-frame2 is pre-alpha. No compatibility shim is required, but this is a
breaking source migration for any existing examples or callers using
`:on-create`. Implementations must reject `:on-create` in public frame specs
rather than allowing two event-based initialization spellings to coexist.

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
10. Update diagnostics so invalid top-level shapes, bad step maps, user-supplied
    `:frame` opts, and setup failures identify the setup step index and event.

Guide-impact assessment:

- `docs/guide/quickstart.md` should use `:initial-events` instead of
  `:on-create`.
- testing and Story docs can show deterministic setup as frame construction
  data rather than loose dispatch calls.
- provider examples should show `:initial-events` in the same map as `:id` and
  `:images`.

## Open Issues

These issues require recorded dispositions before this standards-track EP can
graduate. If any answer is deferred, the final EP should narrow the first
`:initial-events` contract and name the follow-on EP or bead that owns the
deferred question.

1. **Should the event-script runner be public?** The recommended first step is
   internal or tool-tier. Make it public only if examples, Story, and tests need
   a reusable name outside frame construction.

2. **What should the exact failure value be when a setup step fails?** The
   contract must fail loud and identify the step index and event. The final
   error id belongs in the implementation bead.

3. **Should setup steps support labels?** A future map shape could allow
   `:label` for tooling, but this EP keeps the initial grammar minimal.

## Recommendation

Accept `:initial-events` and retire `:on-create`. The frame has two clean
initialization paths: `:initial-db` for direct data and `:initial-events` for an
ordered event setup script. Story `:setup` can share the same runner, and Story
`:script` remains the post-render test/play layer.

(See the Design Review below for the disposition that gates this recommendation:
the central direction is sound, but four problems are graduation-blocking as
written.)

## Design Review

This section is an adversarial review of the EP as written. The design session
tried to BREAK `:initial-events` — to find the place where "setup is just
construction data" stops being true. The good news first: the central move is
correct. The bad news: the EP states several semantics that the live runtime
already contradicts, and the headline "shared runner with Story `:setup`" claim
does not survive contact with the actual Story `:setup` grammar. Three problems
are graduation-blocking; one is a hard correctness gap; the rest are clarity and
ergonomics. The review names the pressure points, scores severity, and proposes
fixes — and where an alternative was considered and rejected, says why.

Severity scale: **BLOCKER** (graduation cannot proceed without a recorded
disposition), **MAJOR** (a real correctness or design defect that should be
fixed before graduation but does not by itself rewrite the EP), **MINOR**
(clarity / ergonomics — fix in passing).

### What is sound (keep)

Before the attacks, the parts that are right and should not be touched:

- **Setup-as-data is the correct frame.** An ordered, inspectable, replayable
  event script IS construction data, and hiding it in ad-hoc post-`make-frame`
  `dispatch-sync` calls genuinely does leave the frame declaration incomplete.
  This is the re-frame2 ethos applied cleanly: program vocabulary as data, not as
  imperative glue. The motivation example (lines 47-56) is real — every Story and
  multi-step test does exactly this today.
- **Retiring `:on-create` rather than widening it is the right call.** Widening
  `:on-create` to "one event OR a vector of events" would be exactly the
  too-magical overload the EP rejects (Goals non-goal 3). One event-based
  initialization surface, not two, is correct Clojure ethos: small orthogonal
  ops.
- **Forbidding a step-level `:frame` opt** (lines 213-215) is exactly right and
  is the kind of fail-loud, isolation-preserving rule re-frame2 should have. A
  setup script that can reach into a sibling frame breaks the isolated-execution
  invariant; closing that door at the grammar is the correct severity.
- **The per-event `:opts` carrying `:rf.cofx`** (lines 226-233) is the feature
  that makes the script genuinely replayable — a setup step that stamps
  `:rf/time-ms` is deterministic in a way a bare `dispatch-sync` never is. This
  is the strongest argument for the map step shape and should be kept.
- **No `realm` vocabulary anywhere.** The EP is clean — it never reaches for the
  retired realm / app-value / migration terms (deleted in #4811). The
  realm-addressing dimension was collapsed (rf2-upgtq4); the EP correctly speaks
  only of frames, ids, and image generations. Nothing to flag here, which is the
  right outcome.

### Finding 1 — "Shared runner with Story `:setup`" does not survive the actual grammar (BLOCKER)

**Attack.** Goal 7 and the §"Relationship to Story setup and script" section claim
frame `:initial-events` and Story `:setup` "share the same event-script runner."
Read the two grammars side by side.

Frame `:initial-events` step (this EP, §Setup step grammar) is a **bare event
vector** or a `{:event … :opts …}` map:

```clojure
:initial-events [[:checkout/open]
                 {:event [:todo/add "milk"] :opts {:rf.cofx {…}}}]
```

Story `:setup` step (`spec/007-Stories.md` line 107, line 316) is a **tagged
step** whose first element is a step-kind keyword:

```clojure
:setup [[:dispatch [:auth/initialise]]
        [:dispatch [:auth/email-changed "alice@example.com"]]]
```

These are not the same shape. Story `:setup` wraps each event in a `[:dispatch
…]` tag because `:setup` is one member of a tagged-step family that also includes
`:loaders` and, in `:script`, `[:assert …]`. The frame grammar has no tag — the
step IS the event. So a single "event-script runner" cannot consume both without
one of them changing, and the EP does not say which.

**Finding.** **The headline alignment claim is currently false at the grammar
level.** A worker handed this EP and `spec/007-Stories.md` cannot build "the
shared runner" — the two callers disagree on what a step is. This is a BLOCKER
because Goal 7 and the §Relationship section are load-bearing normative claims,
not asides, and a graduation that ships them as written hands an implementer a
contradiction.

**Fix (pick one; the EP must record which).**

- *(a) Lower, don't share.* The shared primitive is a **normalize-then-dispatch
  function over `{:event … :opts …}`**. Frame `:initial-events` normalizes a bare
  vector → `{:event v :opts {}}` and feeds the primitive. Story `:setup`
  normalizes its `[:dispatch v]` tag → `{:event v :opts {}}` (and its other
  tags, `:loaders` etc., are Story's own concern) and feeds the SAME primitive.
  The runner is shared; the *grammars* stay distinct and each caller owns its
  own normalizer. This is the honest version of the claim and is the recommended
  fix — it is also what the impl will naturally fall into, since Story already
  has tag-dispatching machinery the frame must not inherit.
- *(b) Drop the claim to "analogous, not shared."* If the runner is not actually
  reused, say so. Goal 7 becomes "frame `:initial-events` and Story `:setup` have
  analogous semantics (ordered pre-render event scripts) but distinct grammars;
  no code is shared." This is weaker but at least true.

Recommend (a), stated precisely: *the shared unit is a normalized step
`{:event … :opts …}` and a dispatch-one-step-into-this-frame primitive; each
caller owns the normalizer from its own surface grammar to that unit.* That
sentence is what the EP is missing.

### Finding 2 — "Dispatched synchronously" is contradicted by the live mid-cascade rule (BLOCKER)

**Attack.** §Execution order line 128 and §Specification say initial events are
"dispatched **synchronously** to the newly created frame," and the order list
(lines 158-164) runs `:initial-events` inline before `make-frame` returns. Now
read the live runtime. In `frame.cljc` `reg-frame` (lines 1388-1407), `:on-create`
is dispatched **synchronously only at top level**; when a handler creates a child
frame mid-cascade (`trace/*handler-scope*` bound), `:on-create` is
**async-queued**, not dispatch-sync'd:

```clojure
(if trace/*handler-scope*
  ;; handler-created child frame: async-queue on the child
  (dispatch on-create init-opts)
  ;; top-level: synchronous
  (dispatch-sync on-create init-opts))
```

This is not an implementation detail — it is a stated contract
(`spec/002-Frames.md` line 569: "`reg-frame` / `make-frame` called from inside a
handler"), and it exists for a hard reason: `dispatch-sync` inside a handler is
itself an error, and two synchronous cascades in different frames would
interleave, which the no-cross-frame-drain rule forbids.

**Finding.** **The EP's "synchronous" guarantee is only true at top level.** A
multi-step `:initial-events` script created mid-cascade cannot run synchronously
— `dispatch-sync` from inside a handler is forbidden. The EP is silent on the
mid-cascade case, so as written it either (i) contradicts the live rule, or (ii)
silently requires `make-frame`-with-`:initial-events` to be a top-level-only
operation, which no text states. Either way an implementer is stuck. BLOCKER.

This is worse than `:on-create`'s version of the problem, because `:on-create`
is a *single* event: async-queuing one event is clean. `:initial-events` is an
*ordered multi-step script with "next step waits for previous step's cascade"*
(lines 175-176). Async-queuing an ordered script mid-cascade means the steps land
on the child frame's queue and drain on its own next tick — but the EP's
"return the fully-constructed frame" promise (step 5) is then a lie mid-cascade:
the frame value returns before its setup has run.

**Fix.** The EP must state the two-regime contract explicitly, mirroring the
`:on-create` rule it is replacing:

- **Top-level construction** (no cascade in flight): `:initial-events` run
  synchronously, step by step, before `make-frame` returns. The returned frame is
  fully constructed. (This is the common case — boot, tests, top-level Story
  setup.)
- **Mid-cascade construction** (a handler creates a child frame): the script is
  **enqueued in order onto the child frame's queue** and drains on the child's
  own next tick. `make-frame` returns a frame whose setup has been *scheduled*,
  not *completed*. Document that "fully constructed on return" holds only at top
  level.

There is a subtle ordering question the EP must also settle: with an ordered
multi-step script enqueued mid-cascade, are the steps enqueued as N separate
queue entries (so an unrelated event already queued on the child could
interleave between setup steps), or as one atomic ordered batch? `:on-create`
never had to answer this (one event). The safe answer is **one ordered batch,
drained before any other event on that fresh child** — a freshly created frame
has an empty queue, so the only events that could interleave are ones the setup
steps themselves dispatch, which is the normal child-dispatch case. State it.

### Finding 3 — `reset-frame!` re-applying `:initial-db` is an undocumented behavior change (MAJOR, possibly BLOCKER)

**Attack.** §Reset and hot reload line 237-238: "`reset-frame!` restores the
frame's recorded `:initial-db` and then reruns the recorded `:initial-events`."
Read the live `reset-frame!` (`frame.cljc` lines 2128-2138): it does
`destroy-frame!` then `reg-frame` with the **stored config** — and
`:rf.frame/initial-db` is explicitly **stripped from the stored config** (line
1257, line 1427) and is NOT re-applied. The live contract (`live_frame.cljc`
lines 583-587) is emphatic: "`:initial-db` … construction-only; seeded into the
fresh frame-state on first create, **NOT re-applied** on an idempotent re-mount —
durable state is preserved."

So today `:initial-db` is a *first-create-only seed*. The EP proposes that
`reset-frame!` re-apply it. That is a real semantic change to `reset-frame!` and
to the meaning of `:initial-db`, and the EP presents it as if it were the status
quo.

**Finding.** **This is a behavior change dressed as a restatement.** It may even
be the *right* change — "reset means back to initial state, and the initial state
is `:initial-db` + the setup script" is a coherent and arguably better contract
than today's "reset clears to `{}` then re-fires `:on-create`." But the EP must
(i) acknowledge it IS a change, (ii) reconcile with `spec/002-Frames.md`
§`reset-frame!` (line 658: today reset re-fires `:on-create`; under this EP it
must re-seed `:initial-db` first then run `:initial-events`), and (iii) decide
whether `:initial-db` stops being construction-only. If `reset-frame!` re-applies
`:initial-db`, then `:initial-db` is no longer construction-only — it is
*recorded reset state*, which is a different and larger commitment. Right now the
EP wants both ("do not replace `:initial-db`", non-goal 2; but "reset restores
`:initial-db`") without noticing they pull in different directions on whether
`:initial-db` is durable-config or construction-seed.

Severity is MAJOR by default and rises to BLOCKER if graduation would land the
new `reset-frame!` semantics without a recorded disposition, because
`reset-frame!` is the documented "back to initial state" tool that tests and
Story reset buttons rely on (`spec/002-Frames.md` line 660) — silently changing
what it restores is a trap.

**Fix.** State the new `reset-frame!` contract as a deliberate change:
"`reset-frame!` destroys the frame, recreates it, re-seeds `:initial-db`, then
re-runs `:initial-events` — i.e. reset returns the frame to its full constructed
initial state, not merely to `{}` + boot event." Add a one-line note that this
supersedes the prior "reset clears to `{}` then re-fires `:on-create`" contract,
and add a bead-plan slice to update `spec/002-Frames.md` §`reset-frame!`.
Resolve whether `:initial-db` is now recorded-reset-state (it must be, for reset
to re-apply it) and reword non-goal 2 to "do not *replace* `:initial-db` *with*
`:initial-events`" so it stops reading as "`:initial-db` semantics are
unchanged."

### Finding 4 — Failure cleanup vs idempotent re-mount is underspecified, and is the rf2-1ak1jy hazard's sibling (MAJOR)

**Attack.** §Execution order lines 181-183: "If a synchronous setup step fails,
construction fails loud. If the frame was registered before the failure, the
implementation must tear it down or unregister it so failed construction does
not leave a live half-created frame." Now collide this with EP-0024's idempotent
replacement (the whole point of which is that re-`make-frame` under an existing
id MUST NOT destroy durable state — `live_frame.cljc` lines 583-587,
`owned_frame.cljs` lines 27-51). Two cases the EP does not separate:

1. **First create fails on step 3 of 5.** Tear down the half-created frame — fine,
   the EP says this. But the frame is registered (step 2) before `:initial-events`
   (step 4), and the deferred-destroy / StrictMode dance in `owned_frame.cljs`
   means a `frame-provider` mount that fails its setup must not leave a pending
   deferred-destroy or a registered-but-dead id. The EP's "tear it down" is
   correct in spirit but says nothing about the provider path, where teardown is
   *deferred and cancellable*, not synchronous.
2. **Re-mount (idempotent) where `:initial-events` would re-run.** The EP says
   re-`make-frame` records the new script but does NOT replay it (lines 240-244)
   — good. But a `frame-provider` re-mount (StrictMode double-invoke, hot reload,
   Story re-eval) goes through `make-frame` → idempotent path. So on a provider
   the setup script runs on **first** mount and NOT on re-mount. That is the
   correct answer, but it depends on the provider's first-create vs re-acquire
   distinction (`owned_frame.cljs` `acquire-owned-frame!`), which the EP's
   §"Relationship to `rf/frame-provider`" (lines 249-262) does not mention. As
   written, line 261 says the provider "creates the frame, runs initial events"
   with no first-create-only qualifier — a reader will expect initial events on
   every mount.

And the deeper hazard: this is exactly the **rf2-1ak1jy double-dispatch class of
bug**. That bug was a boot example that ran `{:on-create [:initialize-db]}` AND a
separate `dispatch-sync [:initialize-db]` — double-firing boot. `:initial-events`
removes the *ad-hoc* second dispatch (good — it folds it into construction data),
but it introduces a NEW double-fire surface: a provider that re-mounts, or a
`make-frame` re-eval that a reader *thinks* re-runs setup. The EP should cite
rf2-1ak1jy as the motivating hazard and state precisely where setup fires exactly
once.

**Finding.** **The once-and-only-once guarantee is the load-bearing safety
property and the EP states it only for the `make-frame` re-eval case, not for the
provider mount/re-mount/StrictMode case.** Boot handlers that "emit effects,
increment counters, start machines, or stamp durable state" (rf2-1ak1jy's exact
words) will double-fire if a reader assumes setup runs on every provider mount.
MAJOR.

**Fix.** Two sentences in §"Relationship to `rf/frame-provider`":

> The provider runs `:initial-events` on the **first** mount of a given id only.
> A re-mount under the same id (StrictMode double-invoke, hot reload, Story
> re-evaluation) hits idempotent replacement: the script is re-recorded but NOT
> replayed, exactly as for a re-evaluated `make-frame`. Setup fires once per
> frame lifetime, not once per mount.

And in §Execution order failure cleanup, add: "On the owned `frame-provider`
path, teardown of a setup-failed frame follows the provider's deferred-destroy
discipline (a failed first-mount must not leave a pending deferred-destroy or a
registered-but-dead id)." Cite rf2-1ak1jy as the motivating double-fire hazard
in the Motivation or Rationale.

### Finding 5 — `:initial-db` vs spec 002's "frames always start with `{}`" is inherited drift (MAJOR — pre-existing, but the EP touches it)

**Attack.** `spec/002-Frames.md` line 549 is normative and unambiguous: "**Frames
always start with `app-db = {}`.** There is no `:db` config key — initialisation
happens via the `:on-create` event." But `:initial-db` is a real, shipped
construction key (`live_frame.cljc`, `provider.cljs`, the EP's own examples). The
spec text and the implementation already disagree. The EP builds *on top of*
`:initial-db` (step 3 of execution order: "seed app-db with `:initial-db`") and
even makes it the thing reset restores — so it is now relying on a key the
governing spec says does not exist.

**Finding.** **The EP inherits and entrenches a spec/impl drift without noting
it.** This is not the EP's bug to fix, but the EP cannot graduate normative text
that says "seed app-db with `:initial-db`" while `spec/002-Frames.md` line 549
says there is no such key. MAJOR, because it makes the EP internally consistent
with the impl but inconsistent with the spec it claims to graduate into.

**Fix.** Add a bead-plan slice: "Reconcile `spec/002-Frames.md` §Frame
initialisation (line 549) — it states frames always start with `{}` and there is
no `:db` config key; `:initial-db` has shipped and this EP makes it
reset-restored, so the spec must acknowledge `:initial-db` as the direct-seed
construction key alongside `:initial-events`." One sentence in §Backwards
Compatibility noting the drift would also discharge the obligation.

### Finding 6 — The single-event double-bracket is a teaching footgun (MINOR)

**Attack.** The EP makes a virtue of "one boot event is a one-element vector":
`:initial-events [[:app/boot]]`. Compare:

```clojure
{:on-create      [:app/boot]}      ;; the retired form — one bracket
{:initial-events [[:app/boot]]}    ;; the new form — two brackets
```

The most common case (a single boot event) gets *noisier*, and the failure mode
is silent-looking: a developer who writes `:initial-events [:app/boot]`
(forgetting the inner bracket) has written "a script whose first step is the
keyword `:app/boot` and whose second step is nothing" — which the EP correctly
makes fail loud (line 131-132: "a bare event vector is not a valid top-level
`:initial-events` value"). Good that it fails loud. But the single most common
authoring action now has a bracket-counting trap, and the EP's own §Motivation
opens with `:on-create [:counter/initialise]` (one bracket) as the thing being
replaced.

**Finding.** **The ergonomics regress for the common case to buy uniformity for
the rare case.** This is a real tradeoff, not a defect — the EP made it
deliberately (Rationale: "One event is already expressible as a one-element
`:initial-events` vector"). But it is under-argued. The double-bracket is the
price of "one boot event and multi-event setup use the same shape" (Goal 3), and
the EP should say plainly that it accepts a noisier common case to avoid a
"one-event-or-many" overload.

**Fix (the EP's current choice is defensible; record the reasoning, or take the alternative).**

- *Keep the current design* but add one sentence to the Rationale: "The common
  single-event case pays one extra bracket; this is deliberate — a
  `:initial-events`-or-single-event overload would re-introduce the exact
  two-shapes ambiguity the EP rejects in `:on-create`. The fail-loud guard on a
  bare top-level vector (§Setup step grammar) catches the bracket mistake."
- *Rejected alternative — accept a bare event OR a script.* `:initial-events
  [:app/boot]` (bare) meaning one event, `:initial-events [[:a] [:b]]` meaning a
  script. Rejected for the same reason `:on-create` widening is rejected: it is
  the "one event or a vector of events" overload under a new name. The ambiguity
  cost (is `[:a :b]` one event with arg `:b`, or two single-keyword events?) is
  exactly what makes it un-teachable. The double-bracket, though noisier, is
  unambiguous. **The EP's choice is correct; it just needs to say why out loud.**

### Finding 7 — "Failure does not roll back committed effects" needs a sharper teaching boundary (MINOR)

**Attack.** Lines 176-179 and 184-187 draw the right line: a failed setup step
fails loud, no later steps run, but effects already committed by *earlier
successful* steps are not rolled back, and async effects are not awaited. This is
consistent with the FX atomicity asymmetry (pre-commit transactional / post-commit
best-effort — a settled Mike ruling). Good. But a developer reading "setup is
construction data, deterministic, replayable" will reasonably expect "if
construction fails, I get nothing" — and instead gets a frame torn down *but*
whatever external effects steps 1..k-1 fired (an HTTP POST, a localStorage write,
an analytics ping) already happened.

**Finding.** **The cleanup guarantee's scope is correct but easy to over-read.**
The EP says it (line 178-179: "the cleanup guarantee applies to the partially
constructed frame registration/lifecycle, not to arbitrary external effects"),
which is good — but it is buried mid-paragraph and the contrast with the
"deterministic / replayable" framing in §Motivation invites the wrong mental
model.

**Fix.** Promote the boundary to its own short paragraph with the teaching: "A
setup script is replayable as a *causal* sequence (same events, same order, same
recorded cofx → same app-db). It is NOT a transaction: external effects fired by
successful earlier steps are not undone if a later step fails, exactly as for any
other event cascade. Keep irreversible side effects out of setup steps, or make
them idempotent." This aligns the teaching with the settled FX-atomicity
asymmetry and stops a reader expecting all-or-nothing construction.

### Finding 8 — Naming and minor clarity (MINOR)

- **`:initial-events` vs `:initial-db` parallelism is good** — the names are a
  matched pair (direct data / event script), which is exactly the right Clojure
  ethos (two small orthogonal ops with parallel names). Keep. No change.
- **"event-script runner" is named but never named-as-a-thing.** Goal 7 and the
  bead plan refer to "one small event-script runner primitive" but no spelling is
  proposed (Open Issue 1 defers whether it is public). Fine to defer publicness,
  but the EP should give the *internal* concept a stable name (e.g.
  `run-initial-events!` over a normalized step seq) so the bead plan slices 1 and
  8 refer to the same thing. MINOR.
- **Open Issue 2 (failure value) is correctly deferred** to the implementation
  bead, but the EP should at least name the diagnostic shape family
  (`:rf.error/…`, carrying step index + event), consistent with the rest of the
  error vocabulary (e.g. the existing `:rf.error/bad-frame-classification`,
  `:rf.error/make-frame-bad-opts`). The EP says "identify the step index and
  event" (Open Issue 2, bead-plan slice 10) — good; just anchor it to the
  `:rf.error/*` namespace so the implementer does not invent a new shape.

### New problems found (not in the EP's Open Issues)

- **NP-1 (the mid-cascade regime — Finding 2).** The EP's Open Issues do not
  list the synchronous-vs-async-queued regime at all. This is the single largest
  gap: the live runtime has a two-regime contract for `:on-create` that the EP's
  successor must inherit, and the EP is silent. Promote to an Open Issue with a
  recorded disposition, OR specify it inline per Finding 2's fix (preferred).
- **NP-2 (Story `:loaders` ordering — interaction with Finding 1).** Story
  `:setup` runs *after* `:loaders` complete (`spec/007-Stories.md` line 107, line
  113). The EP's §"Relationship to Story setup and script" gestures at this
  (lines 294-297: "Story may create the frame first, run loaders, then run
  setup") but does not reconcile it with the frame-construction order, where
  `:initial-events` run *during* `make-frame`. So for an async-loader Story,
  setup canNOT be encoded in `make-frame :initial-events` (loaders must finish
  first), and the EP says so — but this means the "shared runner" is used by
  Story *outside* `make-frame`, reinforcing Finding 1's fix (a): the runner is a
  step-dispatch primitive both call, not a thing `make-frame` owns and Story
  borrows.
- **NP-3 (reset replay + `:rf.cofx` staleness).** §Reset reruns the recorded
  `:initial-events`, including their recorded `:opts` `:rf.cofx` (e.g. a frozen
  `:rf/time-ms`). On reset, replaying the *original* frozen time is the
  replayable/deterministic choice and is almost certainly what is wanted — but
  the EP should state it: "reset replays the recorded steps verbatim, including
  recorded `:rf.cofx`; it does not re-stamp a fresh `:rf/time-ms`." Otherwise an
  implementer might re-derive cofx at reset time and break replay determinism.
  MINOR but a real fork in the road.

### Verdict

**The core direction — setup as ordered, replayable construction data, with
`:on-create` retired — is sound and should be accepted.** The re-frame2 ethos
fit is strong (program-vocabulary-as-data, isolated execution via the forbidden
step-level `:frame`, replayability via per-step `:rf.cofx`), the Clojure ethos
fit is strong (two small orthogonal named ops, no compat ceremony, fail-loud
grammar), and no retired realm vocabulary intrudes.

But the EP as written ships three claims the live runtime already contradicts and
one safety property it under-states:

1. **Finding 1 (BLOCKER):** "shared runner with Story `:setup`" is false at the
   grammar level — fix by stating the shared unit is a normalized
   `{:event … :opts …}` step + a one-step dispatch primitive, each caller owning
   its normalizer.
2. **Finding 2 (BLOCKER):** "dispatched synchronously" holds only at top level —
   the mid-cascade regime (async-queue, as `:on-create` already does) must be
   specified.
3. **Finding 3 (MAJOR→BLOCKER):** `reset-frame!` re-applying `:initial-db` is an
   undocumented behavior change to a relied-on tool — state it as a deliberate
   change and reconcile `spec/002-Frames.md` §`reset-frame!`.
4. **Finding 4 (MAJOR):** the once-per-frame-lifetime setup guarantee is the
   safety property (the rf2-1ak1jy double-fire hazard's sibling) and is stated
   only for `make-frame` re-eval, not for the provider mount/re-mount/StrictMode
   path — close it explicitly.

Findings 5-8 and NP-1..3 are reconcile-the-spec / clarity / ergonomics fixes that
should land with the same edit but do not by themselves rewrite the design.

**Recommendation to the operator: graduate-with-fixes.** The design is right; the
normative text is not yet implementable without contradicting the running frame
lifecycle. Land Findings 1-4 (the BLOCKER/MAJOR set) into the EP before
graduation; sweep Findings 5-8 and NP-1..3 in the same pass. None of the findings
recommends rework of the central mechanism — every fix is "say precisely what the
runtime already does, or state the deliberate change you are making."

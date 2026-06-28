# Coming from XState

If you've built statecharts with [XState](https://stately.ai/docs) — v5, or the v6 alpha — most of your mental model ports straight across. re-frame2's [machines](concepts.md) borrow XState's grammar on purpose: transition tables as data, guards, actions, tags, delayed transitions, final states, run-to-completion, internal-by-default self-transitions. You can read a re-frame2 machine spec on day one and a re-frame2 author can read yours. The *behaviour* is the contract re-frame2 tracks; what changes is the *expression* and one piece of *plumbing*.

So let's be honest about the split up front. Two things transfer almost untouched:

- **The statechart itself.** States, nested states, parallel regions, guards on transitions, entry/exit actions, `after` timers, final states, history — same concepts, idiomatic spelling. If you can draw it, you can write it.
- **The semantics you rely on.** Run-to-completion, internal-vs-external self-transitions, eventless (`always`) transitions firing on guard flips, an unknown event being a no-op rather than a crash. re-frame2 matches XState here deliberately, including the v5/v6 drop of strict mode.

And one thing genuinely shifts:

- **A machine is not an actor.** This is the load-bearing difference and the rest of this page keeps coming back to it. In XState you `createActor(machine)`, `.start()` it, and `.send()` events to a living object that owns its own state. In re-frame2 a machine is an [event handler](../core/glossary.md#event-handler) — registered like any other, fed by the same [`dispatch`](../core/glossary.md#dispatch), with its state living in the [frame](../core/glossary.md#frame) rather than in an object you hold a reference to. There's no `send`, no mailbox, no actor ref. There's one queue, and machines ride it like everything else.

If you internalise just that last bullet, the table below is mostly vocabulary.

## The mapping

| XState (v5 / v6) | re-frame2 | Notes |
|---|---|---|
| `createMachine({ ... })` / `setup().createMachine()` | a transition-table map passed to [`reg-machine`](concepts.md#registering-and-running-it) | Plain Clojure data. No builder, no fluent API. |
| `context` (extended state) | [`:data`](concepts.md#the-same-flow-as-a-transition-table) | Same idea. "Context" was already taken (interceptor context, React context). |
| `states: { idle: { on: { ... } } }` | `:states {:idle {:on {...}}}` | Near-identical shape. `on` → `:on`, target strings → state keywords. |
| `guard: 'canRetry'` | `:guard :under-retry-limit` | Named; defined once in the spec's [`:guards`](concepts.md#guards-actions-tags-and-after--the-recognition-kit) map. Idiomatic Clojure name (`?`-suffixed predicate), not a JS boolean string. |
| `actions: assign({...})` / effectful actions | `:action :record-error` returning `{:data ... :fx ...}` | Defined once in [`:actions`](concepts.md#guards-actions-tags-and-after--the-recognition-kit). An action **returns** an [effect map](../core/glossary.md#effect-map) — it doesn't mutate. More below. |
| `tags: ['busy']` + `state.hasTag('busy')` | `:tags #{:auth/busy}` + `[:rf/machine-has-tag? id :auth/busy]` | A Clojure set; the membership question is a [subscription](../core/glossary.md#subscription). See [state tag](glossary.md#state-tag). |
| `after: { 5000: 'next' }` | `:after {5000 {:target :next}}` | Same declarative timer; same auto-cancel on exit. ISO-8601 strings (`"PT5S"`) accepted; XState's `"5s"` shorthand is not. |
| `always: [{ guard, target }]` | `:always [{:guard … :target …}]` | Eventless transitions, same firing rule. |
| `reenter: true` | `:reenter? true` | External self-transition. Same semantics; Clojure `?`. |
| `final: true` + `output` | [`:final? true`](concepts.md#final-states-when-a-machine-is-done) + `:output-key` | A finishing leaf reports a value to its parent's `:on-done`. |
| `invoke: { src: childMachine }` | [`:spawn {:machine-id …}`](glossary.md#spawn) | Start a child on entry, tear down on exit. Renamed; see below. |
| `raise({ type: ... })` | `:fx [[:raise [:event …]]]` | Loop an event back into *this* machine, atomically, pre-commit. |
| `setup({ guards, actions })` registry | per-spec `:guards` / `:actions` maps | Each machine carries its own. Cross-machine reuse is an ordinary Clojure var, not a string registry. |
| TypeScript `types` / v6 `schemas` | [`:schemas {:data …}`](concepts.md#validating-a-machines-data) | A [Malli schema](../core/glossary.md#schema) — but one that **actually runs in dev** and rolls a bad transition back, not erased-at-compile types. |
| **`createActor(m).start()` → `actor.send(ev)`** | **the machine is an event handler → `(rf/dispatch [machine-id [event]])`** | **The big one.** No actor object; one [`dispatch`](../core/glossary.md#dispatch), one [cascade](../core/glossary.md#event-cascade). |
| `actor.getSnapshot()` | `@(rf/subscribe [:rf/machine id])` | The [snapshot](glossary.md#snapshot) is a value in [runtime-db](../core/glossary.md#runtime-db), read like any other derived state. |

A condensed five-row version of this lives at the foot of the [machines concepts page](concepts.md#coming-from-xstate-the-five-row-delta); this one is the long form, and the section below is the part worth your attention.

## Where it diverges — and why

The renames are noise. These four are the real differences, each made on purpose, and knowing the *why* is what lets you stop fighting the framework.

### 1. A machine is an event handler, not an actor

In XState the unit of life is the actor. You instantiate a machine, start it, hold the reference, and `send` it messages; it owns its state and you ask it for a snapshot. That's a clean model, and it's a *second* messaging system living alongside whatever else your app uses to move data around.

re-frame2 already has exactly one of those — the [event cascade](../core/glossary.md#event-cascade). Every state change in the entire app is a [`dispatch`](../core/glossary.md#dispatch) flowing through one router queue. So a machine doesn't get to be special:

```clojure
(rf/reg-machine :auth.login/flow login-flow)            ;; register, like any handler
(rf/dispatch [:auth.login/flow [:auth.login/submit creds]])  ;; send, like any event
```

[`reg-machine`](../core/glossary.md#registration) is sugar over `reg-event`: the registered handler interprets the table — read the current [snapshot](glossary.md#snapshot), compute the transition, write the new snapshot, return the [action](glossary.md#action)'s effects. The outer `:auth.login/flow` routes to the machine; the inner `[:auth.login/submit creds]` is the event the machine sees. No actor object to thread through your components, no parallel `send` API, no question of "where do I keep the running machine?" — the answer is the same place every other piece of state lives.

**Why:** one mechanism is cheaper than two. An XState actor is a thing you must wire into your component tree, keep alive, and bridge to your data layer. A re-frame2 machine is reachable from anywhere `dispatch` is, traceable on the same [trace stream](../core/glossary.md#trace-stream), and composes with every other handler for free. The cost — and it's real — is that you give up `actor.send(...)` ergonomics and the sense of a machine as a tangible object. In exchange the machine stops being a special case.

### 2. The snapshot is a value in the frame, not state owned by an object

`actor.getSnapshot()` reaches into an object. In re-frame2 the snapshot — `{:state :submitting :data {...}}` — is a plain value living in the frame's [runtime-db](../core/glossary.md#runtime-db), the framework's half of [the two partitions](../core/glossary.md#the-two-partitions). You read it with an ordinary subscription:

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 1 :error nil}}   (nil before the first event)
```

`[:rf/machine <id>]` is a normal [query vector](../core/glossary.md#query-vector). You can chain named projections off it, and it shows up in your [derivation graph](../core/glossary.md#the-derivation-graph) like any sub.

**Why:** because the machine's entire state is *just a value riding the frame*, everything the frame can do, the machine gets for nothing. [Time-travel](../core/glossary.md#time-travel), undo, persistence, and SSR hydration all work on machines with zero extra code — rewind the frame and the machine rewinds with it. An actor that owns its own mutable state can't be rolled back by an outside force without a bespoke serialization story; a value in a database rolls back by definition. This is the quiet dividend of refusing to make the machine an object: it can't hide state where replay can't reach.

That same refusal is why an action **sees only `{:state :data :event :meta}` and never app-db** — and why returning `:db` from an action is a hard error. State that escaped into app-db wouldn't ride the snapshot, and the free rollback would silently develop a hole. To touch a sibling slice you [`dispatch`](../core/glossary.md#dispatch) a named event; the reach becomes a traced, reusable event instead of a quiet cross-write.

### 3. Actions return effects; they don't perform them

XState's `assign(...)` is an action *creator* that imperatively updates context, and an effectful action runs its side effect when invoked. re-frame2 inverts this. An action is a pure function that **returns** the same data-shaped [effect map](../core/glossary.md#effect-map) a `reg-event` handler returns:

```clojure
:record-error
(fn [{data :data [_ {:keys [failure]}] :event}]
  {:data (-> data (update :attempts inc)
                  (assoc :error (or (:message failure) "Login failed.")))})

:issue-request
(fn [{[_ creds] :event}]
  {:fx [[:rf.http/managed {:request {:method :post :url "/api/login" :body creds}
                           :on-success [:auth.login/flow [:auth.login/success]]
                           :on-failure [:auth.login/flow [:auth.login/failure]]}]]})
```

The returned `:data` is merged into the snapshot; the returned `:fx` is a *description* of work, handed to the effects machinery to actually run. No `assign` helper — re-frame2 never needed one, because returning `{:data ...}` is the assignment. ([XState v6 is removing `assign`'s special status](https://stately.ai/docs) and leaning toward plainer functions, which is roughly the shape re-frame2 started from.)

**Why:** ["effects are data"](../core/glossary.md#effects-are-data) is the spine of re-frame2, not a machines feature. An action that *returns* a description of its side effects is pure, trivially testable, and replayable; an action that *performs* them is none of those. And the payoff compounds: because an effect is just data, a machine and an async surface compose with no glue. Note `:on-success [:auth.login/flow [:auth.login/success]]` above — the HTTP reply lands back *inside the machine* as an ordinary event ([the uniform reply](../core/glossary.md#the-uniform-reply) appends the payload to the inner vector). No `invoke`-promise bridge, no callback adapter — the reply is just the next event the machine handles.

### 4. The transition topology stays data — functions are confined to guards and actions

This is the most opinionated divergence, and the most easily missed. In XState you can put functions in a lot of places — and v6 adds a `choice` function for dynamic target selection. re-frame2 draws a hard line: **guards and actions are functions, but the transition topology is not.** Targets, `:always`, `:after`, `:choice` candidates — the *shape of the graph* — must be declarative data. So re-frame2 rejects function-valued transitions, and its [`:type :choice`](concepts.md#when-the-machine-grows) takes a declarative candidate array, not v6's `choice` function. There's also deliberately no `{:and ...}` guard-combinator DSL: compound conditions go in one *named* function.

**Why:** the spec is the artefact. A machine whose topology is data can be pretty-printed, rendered as a live diagram, diffed in review, and handed whole to an AI with "add a two-factor state" — and the tool sees the entire graph in one form. The moment a target is computed by an opaque function, the diagram has a hole the visualiser can only draw as a shrug, and the AI loses the thread. Names are the same bet: a guard called `:under-retry-limit` renders legibly on the arrow; an inline `{:and [...]}` blob renders as noise. re-frame2 spends a little expressive flexibility to keep the graph *readable by machines other than itself* — which, given that re-frame2 is built AI-first, is the whole point.

### Two smaller deliberate differences

- **`invoke` → `:spawn`.** Same job — start a child machine on state entry, tear it down on exit, collect its result via `:on-done`. Renamed because "invoke" reads like a synchronous call, whereas the thing actually being created is a spawned child actor's worth of lifecycle. See [spawn](glossary.md#spawn).
- **Completion is event-shaped.** A finishing child's [`:final? true`](concepts.md#final-states-when-a-machine-is-done) leaf selects a value with `:output-key`, and that value flows to the parent's `:on-done` as `result` — at the moment of completion. There's no long-lived `snapshot.output` slot to read later, the way you'd read XState's `output`. Completion is a thing that *happens* (an event), not a thing that *sits there* (state), which keeps it on the same event rails as everything else.

## What stays loud, what stays quiet

One last alignment, because it trips people who expect re-frame2 to be stricter than it is. An **unknown event is a no-op** — exactly as in XState v5/v6 after strict mode was dropped. Dispatch an event the current state doesn't handle and nothing throws; the snapshot doesn't move; a benign `:rf.machine.event/unhandled-no-op` trace records that it arrived and was dropped.

Almost everything *else* that's wrong [fails loud](../core/glossary.md#fail-loud-not-silent) — a guard referencing an undefined name, a target naming a missing state, a `:schemas` sub-key that isn't real — but at **registration** time, not on the unlucky dispatch that first reaches the bad arrow. And a runaway `:always`/`:raise` cycle (which XState *throws* on) is a bounded, atomically-aborted [error record](../core/glossary.md#error-record), not a hang — deliberately distinct from the silent no-op, because a non-converging loop is a bug you want surfaced.

For the full grammar — hierarchical states, parallel regions, history, `:timeout`/`:on-timeout`, private `:internal-events` — head back to [the machines guide](concepts.md#when-the-machine-grows), or the normative [Spec 005](../../spec/005-StateMachines.md) and the divergence ledger in the [machine construction guide](../../spec/CP-5-MachineGuide.md).

# Coming from XState

If you've built statecharts with [XState](https://stately.ai/docs) — v5, or the v6 alpha — most of your mental model ports straight across. re-frame2's [machines](concepts.md) borrow XState's grammar on purpose: transition tables as data, guards, actions, tags, delayed transitions, final states, run-to-completion, internal-by-default self-transitions. You can read a re-frame2 machine spec on day one and a re-frame2 author can read yours. The *behaviour* is the contract re-frame2 tracks; what changes is the *expression* and one piece of *plumbing*.

So let's be honest about the split up front. Two things transfer almost untouched:

- **The statechart itself.** States, nested states, parallel regions, guards on transitions, entry/exit actions, `after` timers, final states, history — same concepts, idiomatic spelling. If you can draw it, you can write it.
- **The semantics you rely on.** Run-to-completion, internal-vs-external self-transitions, eventless (`always`) transitions firing on guard flips, an unknown event being a no-op rather than a crash. re-frame2 matches XState here deliberately, including the v5/v6 drop of strict mode.

And one thing genuinely shifts:

- **A machine is not an actor.** This is the load-bearing difference and the rest of this page keeps coming back to it. In XState you `createActor(machine)`, `.start()` it, and `.send()` events to a living object that owns its own state. In re-frame2 a machine is an [event handler](../core/glossary.md#event-handler) — registered like any other, fed by the same [`dispatch`](../core/glossary.md#dispatch), with its state living in the [frame](../core/glossary.md#frame) rather than in an object you hold a reference to. There's no `send`, no mailbox, no actor ref. There's one queue, and machines ride it like everything else.

If you internalise just that last bullet, the table below is mostly vocabulary.

> **Parity reference: the XState v6 direction.** Where this page says "XState," the behavioural baseline is the **v6** design direction (plain guard/action functions over a declarative topology, broader optional schemas, explicit timeouts, choice states, private internal events, event-shaped completion) — not v5's helper-creator API. The posture is *behavioural parity, not API mimicry*: re-frame2 aligns semantics and records each intentional divergence rather than cloning JavaScript runtime shapes. When your training recalls a v5 helper (`assign`, `sendTo`, `raise`, `and`/`or`/`not`/`stateIn`, `setup()`), reach for the data-first re-frame2 form below.

## The mapping

Skim this, then read the worked build-up in [The grammar, concept by concept](#the-grammar-concept-by-concept). The exhaustive API contract is the [`re-frame.machines` reference](../api/re-frame.machines.md) — this page teaches; that page enumerates.

| XState (v6 direction) | re-frame2 | Notes |
|---|---|---|
| `createMachine({ ... })` / `setup().createMachine()` | a transition-table map passed to [`reg-machine`](concepts.md#registering-and-running-it) | Plain Clojure data. No builder, no fluent API. |
| `context` (extended state) | [`:data`](#context-becomes-data) | Same idea. "Context" was already taken (interceptor context, React context); `:data` tracks FSM / `gen_statem` "state data". |
| `state.value` | the snapshot's `:state` | Flat → keyword; compound → vector path; parallel → region-name→keyword map. |
| `states: { idle: { on: { ... } } }` | `:states {:idle {:on {...}}}` | Near-identical shape. `on` → `:on`, target strings → state keywords. |
| nested states (`initial` + `states`) | [`:initial` + `:states` on a state node](#compound-nested-states) | Deepest-wins resolution, parent fall-through. Keyword target = sibling; vector target = absolute path. |
| `guard: 'canRetry'` | `:guard :under-retry-limit` | Named; defined in the spec's [`:guards`](#guards) map. Idiomatic Clojure name (`?`-suffixed predicate), not a JS boolean string. |
| `actions: assign({...})` / effectful actions | [`:action :record-error`](#actions-assign-and-the-effect-map) returning `{:data ... :fx ...}` | An action **returns** an [effect map](../core/glossary.md#effect-map) — it doesn't mutate. More below. |
| `setup({ guards, actions })` registry | per-spec `:guards` / `:actions` maps | Each machine carries its own. Cross-machine reuse is an ordinary Clojure var, not a string registry. |
| `tags: ['busy']` + `state.hasTag('busy')` | [`:tags #{:auth/busy}`](#tags) + `[:rf/machine-has-tag? id :auth/busy]` | A Clojure set; the membership question is a [subscription](../core/glossary.md#subscription). See [state tag](glossary.md#state-tag). |
| `after: { 5000: 'next' }` | [`:after {5000 {:target :next}}`](#delayed-transitions-after-and-timeout) | Same declarative timer; same auto-cancel on exit. ISO-8601 strings (`"PT5S"`) accepted; XState's `"5s"` shorthand is **not**. |
| `always: [{ guard, target }]` | [`:always [{:guard … :target …}]`](#eventless-transitions-always) | Eventless transitions, same firing rule. |
| state/actor `timeout` / `onTimeout` | [`:timeout`/`:on-timeout`](#delayed-transitions-after-and-timeout) | A named deadline that lowers onto `:after`. ms or ISO-8601; `"5s"` shorthand rejected. |
| `type: 'choice'` / a `choice` **function** | [`:type :choice` + `:choice [...]`](#choice-states) | A declarative candidate **array**, never a function. See divergence #4. |
| v6 `internalEvents: [...]` | [`:internal-events #{...}`](#internal-events) | A Clojure **set**, not an array. Private events the machine raises and handles itself. |
| `reenter: true` | `:reenter? true` | External self-transition. Same semantics; Clojure `?`. |
| `type: 'parallel'` | [`:type :parallel` + `:regions {...}`](#parallel-states) | Regions, not `states`. `:data` shared, `:tags` unioned, `:state` becomes a region→state map. |
| `type: 'history'` (`history: 'deep'`) | [`:type :history` (`:deep? true`)](#history-states) | A pseudo-state under a compound's `:states`. Shallow by default. |
| `type: 'final'` + `output` | [`:final? true`](#final-states-and-output) + `:output-key` | A finishing leaf reports a value to its parent's `:on-done`. `output` is a *key*, not a fn. |
| `invoke: { src: childMachine }` | [`:spawn {:machine-id …}`](#invoke-becomes-spawn) | Start a child on entry, tear down on exit. Renamed; see below. |
| `invoke … onError` (a transition) | `:spawn`'s `:on-error` transition + `:error? true` leaf | First-class control flow, not observability-only. |
| multiple `invoke` per state / fan-out | `:spawn-all` (named children + join + cancel policy) | One `:spawn` per state; fan-out is its own surface. |
| `raise({ type: ... })` | `:fx [[:raise [:event …]]]` | Loop an event back into *this* machine, atomically, pre-commit. |
| `sendTo` / `sender` (reply to a request) | the reply event carried in the request vector | No new API; the request names its own reply target. |
| TypeScript `types` / v6 `schemas` | [`:schemas {:data … :output …}`](#schemas-types-that-actually-run) | A [Malli schema](../core/glossary.md#schema) — but one that **actually runs in dev** and rolls a bad transition back, not erased-at-compile types. |
| three creation modes (`createActor` / `invoke` / `spawn`) | one mechanism: singleton via `reg-machine`, dynamic via `:spawn` / `[:rf.machine/spawn …]` | Lifetime is the snapshot's presence in [runtime-db](../core/glossary.md#runtime-db), not which constructor you called. |
| **`createActor(m).start()` → `actor.send(ev)`** | **the machine is an event handler → `(rf/dispatch [machine-id [event]])`** | **The big one.** No actor object; one [`dispatch`](../core/glossary.md#dispatch), one [cascade](../core/glossary.md#event-cascade). |
| `actor.getSnapshot()` | [`@(rf/subscribe [:rf/machine id])`](#reading-the-snapshot) | The [snapshot](glossary.md#snapshot) is a value in [runtime-db](../core/glossary.md#runtime-db), read like any other derived state. |

A condensed five-row version of this lives at the foot of the [machines concepts page](concepts.md#coming-from-xstate-the-five-row-delta); this one is the long form, and the two sections below — the worked build-up and the *why* essays — are the part worth your attention.

## The grammar, concept by concept

The table is the map; this section is the territory. Each piece below shows the XState shape you know, then the re-frame2 shape it becomes, built from one running example — the login flow from the [machines guide](concepts.md). Every snippet is real API; nothing here is invented.

### The machine definition

In XState you build a machine with `createMachine` — and in v6 a `setup()` registry holds the guards/actions and types:

```js
import { setup, assign } from 'xstate';

const loginMachine = setup({
  guards:  { underRetryLimit: ({ context }) => context.attempts < 3 },
  actions: { /* … */ },
}).createMachine({
  initial: 'idle',
  context: { attempts: 0, error: null },
  states:  { idle: { on: { SUBMIT: 'submitting' } } /* … */ },
});
```

In re-frame2 the definition is *just data* — one map, with its `:guards` and `:actions` tables carried inline, registered with [`reg-machine`](concepts.md#registering-and-running-it):

```clojure
(rf/reg-machine :auth.login/flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:under-retry-limit (fn [{:keys [data]}] (< (:attempts data) 3))}

   :actions
   {:clear-error  (fn [_] {:data {:error nil}})
    :issue-request (fn [{[_ creds] :event}] {:fx [[:rf.http/managed { … }]]})
    :record-error  (fn [{data :data [_ {:keys [failure]}] :event}]
                     {:data (-> data (update :attempts inc)
                                     (assoc :error (or (:message failure) "Login failed.")))})
    :store-session (fn [{[_ {:keys [value]}] :event}]
                     {:fx [[:auth.session/store {:token (:token value)}]]})}

   :states
   {:idle        {:on {:auth.login/submit {:target :submitting :action :clear-error}}}
    :submitting  {:tags  #{:auth/busy}
                  :entry :issue-request
                  :on    {:auth.login/success {:target :authed :action :store-session}
                          :auth.login/failure [{:target :error-shown
                                                :guard  :under-retry-limit
                                                :action :record-error}
                                               {:target :locked-out}]}}
    :error-shown {:on {:auth.login/dismiss :idle
                       :auth.login/submit  :submitting}}
    :authed      {}
    :locked-out  {}}})
```

In XState the definition is built by a function call and a `setup()` registry; in re-frame2 it's a literal map with no builder and no global registry — `reg-machine` is sugar over `reg-event`, so the machine *is* an [event handler](../core/glossary.md#event-handler). That single fact (divergence [#1](#1-a-machine-is-an-event-handler-not-an-actor) below) is why you drive it with `dispatch`, not `actor.send`, and why the whole thing being *data* means it [tests on the JVM](#reading-the-snapshot) as pure function calls. Source-coord stamping for tooling is why you reach for the `reg-machine` macro over the plain `reg-machine*` fn — see the [API reference](../api/re-frame.machines.md).

### Context becomes :data

A snapshot is the live value of a machine — the XState `{ value, context }` pair:

```js
// state.value   === 'submitting'
// state.context === { attempts: 1, error: null }
```

```clojure
;; the re-frame2 snapshot:
{:state :submitting :data {:attempts 1 :error nil}}
```

Same split — a discrete `:state` (XState's `state.value`) plus extended memory `:data` (XState's `context`). In XState this slot is `context`; in re-frame2 it is `:data`, because re-frame already spends the word "context" on the interceptor context and React context, and `:data` tracks the FSM / Erlang `gen_statem` "state data" tradition. The divergence is name-only.

### Actions, assign, and the effect map

This is the deepest behavioural shift, so it's worth slowing down. XState's `assign(...)` is an action *creator* that imperatively updates `context`, and an effectful action *runs* its side effect when invoked:

```js
actions: assign({ attempts: ({ context }) => context.attempts + 1 }),  // the "assign"
// and, separately, an effectful action that performs a fetch when invoked.
```

re-frame2 inverts this. An action is a pure function that **returns** the same data-shaped [effect map](../core/glossary.md#effect-map) a `reg-event` handler returns — `:data` (merged into the snapshot) and/or `:fx` (a *description* of work the effects machinery runs):

```clojure
:record-error
(fn [{data :data}] {:data (update data :attempts inc)})       ;; this IS the "assign"

:issue-request
(fn [{[_ creds] :event}]
  {:fx [[:rf.http/managed {:request    {:method :post :url "/api/login" :body creds}
                           :on-success [:auth.login/flow [:auth.login/success]]
                           :on-failure [:auth.login/flow [:auth.login/failure]]}]]})
```

In XState you reach for the `assign` helper-creator; in re-frame2 returning `{:data ...}` *is* the assignment — there is no `assign`, and no `[:assign {...}]` form (full *why* in divergence [#3](#3-actions-return-effects-they-dont-perform-them) below). XState v6 is itself moving away from `assign`'s special status toward plainer functions, which is roughly where re-frame2 started. Note `:on-success [:auth.login/flow [:auth.login/success]]` above: the HTTP reply lands back *inside the machine* as an ordinary event ([the uniform reply](../core/glossary.md#the-uniform-reply) appends the payload), so a machine and an async surface compose with no `invoke`-promise bridge.

### Guards

A guard is a yes/no test gating a transition. In XState v5 you name it and define it in `setup()`:

```js
on: { FAILURE: [{ guard: 'underRetryLimit', target: 'errorShown' }, { target: 'lockedOut' }] }
```

```clojure
:guards {:under-retry-limit (fn [{:keys [data]}] (< (:attempts data) 3))}
;; …on the :submitting node:
:on {:auth.login/failure [{:guard :under-retry-limit :target :error-shown :action :record-error}
                          {:target :locked-out}]}
```

Same guarded candidate vector, first-pass-wins — identical to XState's transition array. A guard (like an action) receives **one context map** and destructures what it needs: `(fn [{:keys [data event state meta]}] …)`. **Divergence:** there is no `{and: [...]}` / `stateIn` data combinator (XState v6 dropped the v5 `and`/`or`/`not`/`stateIn` creators, which re-frame2 never had) — a compound condition goes in one *named* function, so the *name* is what a diagram arrow and an AI read; cross-region coordination uses [tags](#tags). A guard or action that needs a host fact (the clock, a random draw) [declares it as a coeffect](concepts.md#guards-and-actions) rather than calling `(js/Date.now)`, so the decision replays identically under time-travel.

### Reading the snapshot

In XState you reach into the actor object; in re-frame2 you subscribe, because the snapshot is a plain value in the frame's [runtime-db](../core/glossary.md#runtime-db):

```js
const snap = actor.getSnapshot();   // { value, context, tags, … }
```

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 1 :error nil} :tags #{:auth/busy}}
;;    (nil before the first event; :tags present only when active states declare tags)
```

`[:rf/machine <id>]` is an ordinary [query vector](../core/glossary.md#query-vector) — chain named projections off it and it joins your [derivation graph](../core/glossary.md#the-derivation-graph) like any sub (divergence [#2](#2-the-snapshot-is-a-value-in-the-frame-not-state-owned-by-an-object) below explains the dividend). Because the whole definition and snapshot are data, the pure transition fn `(machines/machine-transition definition snapshot event)` returns `[next-snapshot effects]` with no frame, DOM, or network — the same value that renders in a browser unit-tests on the JVM ([machines API §`machine-transition`](../api/re-frame.machines.md), worked example at [`examples/.../state_machine_walkthrough/`](../../examples/capabilities/machines/state_machine_walkthrough/)).

### Eventless transitions: always

An eventless (`always`) transition fires the instant its guard becomes true — no event needed:

```js
resolving: { always: [{ guard: 'isEmpty', target: 'empty' }, { target: 'some' }] }
```

```clojure
:resolving {:always [{:guard :empty? :target :empty}
                     {:target :some}]}
```

Same firing rule, same microstep semantics: the whole cascade (the seed transition, every `:always` microstep, every raised event) commits **once, atomically** — XState/SCXML macrostep semantics, which re-frame2 matches including the "settle `:always` before dequeuing the next `:raise`" ordering. `:always` is a state-node slot (beside `:on`, `:entry`, `:after`), not a mid-transition key.

### Delayed transitions: after and timeout

A delayed transition fires after a wall-clock delay; entering the state arms the timer, leaving it cancels (a stale timer from a prior visit is detected by epoch and ignored):

```js
loading: { after: { 5000: 'timeout', 30000: { guard: 'stillLoading', target: 'hardError' } } }
```

```clojure
:loading {:after {5000  :timeout
                  30000 {:guard :still-loading? :target :hard-error}}
          :on    {:loaded :ready :failed :error}}
```

`:after`'s value uses the same transition grammar as an `:on` clause, and the delay can be a literal `pos-int?` ms, a subscription vector (app-state-derived), or `(fn [{:keys [snapshot]}] ms)` (computed from this machine's `:data`). XState's named/delayed-actor `timeout` maps to `:timeout` + `:on-timeout`, a named deadline that lowers onto the same `:after` machinery. **Divergence:** durations are integer ms **or** an ISO-8601 string (`"PT5S"`); XState's readable `"5s"` / `"10ms"` shorthand is **rejected** at registration. (Recurring timers and pause/resume are out of scope for v1.)

### Compound (nested) states

A state with its own `:initial` + `:states` is a compound state, exactly as in XState:

```js
playing: { initial: 'atStart', states: { atStart: { on: { SEEK: 'midTrack' } }, midTrack: {} } }
```

```clojure
:playing {:initial :at-start
          :states  {:at-start  {:on {:seek :mid-track}}
                    :mid-track {}}}
```

Same semantics: deepest-wins transition resolution with parent fall-through (an event the leaf doesn't handle bubbles to the compound), and `state.value` for a compound becomes a vector path — the snapshot's `:state` reads `[:playing :mid-track]`. A keyword `:target` names a **sibling**; a vector target is an **absolute path** from the (region) root, for cross-level jumps.

### Parallel states

For orthogonal axes of one domain, XState uses `type: 'parallel'` with sub-`states`; re-frame2 uses `:type :parallel` with `:regions`:

```clojure
(rf/reg-machine :ui/nine-states
  {:type    :parallel
   :data    {:items [] :error nil}                 ;; shared across all regions
   :guards  {:empty? (fn [{d :data}] (zero? (count (:items d))))}
   :regions
   {:data {:initial :nothing
           :states  {:nothing   {:tags #{:data/idle} :on {:fetch :loading}}
                     :loading   {:tags #{:data/loading} :on {:loaded :resolving :failed :error}}
                     :resolving {:always [{:guard :empty? :target :empty} {:target :some}]}
                     :empty     {:tags #{:data/empty}}
                     :some      {:tags #{:data/some}}
                     :error     {:tags #{:data/error}}}}
    :form {:initial :neutral
           :states  {:neutral   {:tags #{:form/neutral} :on {:submit-valid :correct}}
                     :correct   {:tags #{:form/success} :on {:edit :neutral}}}}}})
```

In XState the parallel node's children sit under `states`; in re-frame2 they sit under `:regions`, each a `{:initial … :states …}` body. The snapshot's `:state` becomes a **region-name → state** map, `:tags` is the **union** across active regions, and `:data` is **shared** (there is no per-region `:data` — if your axes need encapsulated data, that's the substrate telling you to register N separate machines):

```clojure
@(rf/subscribe [:rf/machine :ui/nine-states])
;; => {:state {:data :nothing :form :neutral} :data {:items [] :error nil} :tags #{:data/idle :form/neutral}}
```

Cross-region coordination — one region guarding on another region's active state (XState v5's `stateIn` / SCXML `In()`) — is expressed by predicating on a sibling region's [tag](#tags), not a combinator. (**Deferred:** a region whose own tree is itself `:type :parallel` is rejected in v1.) Worked example: [`examples/patterns/nine_states/`](../../examples/patterns/nine_states/).

### History states

To re-enter a compound at the substate it last occupied, XState declares a `type: 'history'` child; re-frame2 declares a `:type :history` pseudo-state under the compound's `:states`:

```clojure
{:player
 {:initial :stopped
  :states {:stopped {:on {:play [:player :playing :hist]}}   ;; target the pseudo-state to restore
           :playing {:initial :at-start
                     :on      {:stop [:player :stopped]}
                     :states {:hist      {:type :history
                                          :deep? true              ;; omit => SHALLOW
                                          :default-target :at-start} ;; omit => compound's :initial
                              :at-start  {:on {:seek :mid-track}}
                              :mid-track {}}}}}}
```

Same concept, idiomatic spelling: XState's `history: 'deep'` becomes `:deep? true` (shallow is the default — a missing `:deep?` reads as shallow), and `:default-target` is the never-yet-entered fallback. The pseudo-state is never an occupiable state — a transition *to* `:hist` resolves to the recorded (or default) leaf, which is what the snapshot records. The recording rides the snapshot's framework-owned `:rf/history` slot, so undo, time-travel, and SSR get history for free — no hand-rolled snapshot-as-value substitute.

### Final states and output

A leaf marked `:final?` terminates the machine; if it was spawned, the parent is notified — the XState `done` / `onDone` pattern:

```clojure
;; Child — designates its terminal state and what it reports out.
(rf/reg-machine :auth-flow
  {:initial :running
   :states  {:running {:on {:server-ok {:target :done
                                         :action (fn [{data :data ev :event}]
                                                   {:data (assoc data :token (second ev))})}}}
             :done    {:final?     true
                       :output-key :token}}})       ;; ← this :data value flows to the parent

;; Parent — :on-done reads the child's reported result.
(rf/reg-machine :login
  {:initial :idle
   :states  {:idle           {:on {:submit :authenticating}}
             :authenticating {:spawn {:machine-id :auth-flow
                                      :on-done    (fn [{:keys [data result]}] (assoc data :token result))
                                      :on-error   :idle}}}})    ;; child failed → transition
```

In XState `output` is a *function* of context; in re-frame2 `:output-key` names a **key** into the child's `:data` (compute it with an `:action` into the final state if you need to — there is no `:output-fn`). **Divergence:** completion is *event-shaped*, not a long-lived `snapshot.output` slot you read later — `:output-key`'s value flows to the parent's `:on-done` as `result` *at the moment of completion*, then the child auto-destroys. A `:final?` leaf may add `:error? true` to be the designated error terminal that drives the parent's `:on-error` **transition** (XState's `invoke onError` as control flow, not observability). And note: a **singleton** machine reaching `:final?` also auto-destroys — "final means final"; omit `:final?` for a persistent terminal state (`:authed` / `:locked-out` above are plain leaves).

### Invoke becomes spawn

Starting a child actor on state entry and tearing it down on exit is XState's `invoke`; in re-frame2 it's `:spawn`:

```clojure
:authenticating
{:spawn {:machine-id :http/post
         :data       (fn [{snap :snapshot}] {:url "/api/login" :body (-> snap :data :credentials)})
         :system-id  :auth-actor                ;; address the child by role, not gensym'd id
         :on-done    (fn [{:keys [data result]}] (assoc data :result result))
         :on-error   :idle}
 :on    {:auth/cancelled :idle}}
```

The job is identical; the **name diverges on purpose** — "invoke" reads like a synchronous call, whereas the thing created is a spawned child actor's worth of lifecycle, so the declarative key aligns with the imperative fx `[:rf.machine/spawn …]`. **Divergences to know:** one `:spawn` per state (fan-out is the separate `:spawn-all`, with named children, a `:join` condition, and a `:cancel-on-decision?` policy); no `:onSnapshot` (subscribe to `[:rf/machine <child-id>]` instead); no per-actor mailbox (events route through the one queue); no `autoForward` (forward explicitly via `:fx [[:dispatch [child-id ev]]]`). To address a spawned child from a machine action, emit `[:rf.machine/dispatch-to-system [:auth-actor [:event]]]` — see the [API reference](../api/re-frame.machines.md).

### Choice states

A choice (transient) state routes immediately on entry to the first guard that passes:

```clojure
:checking {:type   :choice
           :choice [{:guard :valid? :target :accepted}
                    {:target :rejected}]}      ;; unguarded final = the default / else branch
```

In XState v6 a `choice` state's routing can be a `choice`-**function**. re-frame2 **rejects** that: `:choice` is a declarative guarded-candidate **array** — a function may never be the edge (the *why* is divergence [#4](#4-the-transition-topology-stays-data--functions-are-confined-to-guards-and-actions) below; a fn-valued `:choice` fails loud at registration). The array must include an unguarded default so the node always resolves, and a choice state *only routes* (no `:entry` / `:on` / `:after`). It desugars onto `:always` — distinct authoring intent, one mechanism — so external observers never see `:checking`.

### Internal events

A private event the machine raises and handles itself — never part of its public surface:

```clojure
{:initial :waiting
 :internal-events #{:tick}                            ;; a SET, not an array
 :actions {:arm (fn [_] {:fx [[:raise [:tick]]]})}    ;; raised internally…
 :states  {:waiting {:on {:go {:target :armed :action :arm}}}
           :armed   {:on {:tick {:target :checking}}} ;; …and handled by an ordinary :on clause
           :checking {}}}
```

XState v6's `internalEvents` is an **array**; re-frame2 declares them as a Clojure **set** — membership is the natural shape, order is irrelevant, duplicates are impossible. The `:on {:tick …}` clause is *how* the machine handles the raised event (expected, not a collision); what `:internal-events` adds is the boundary — an *external* `(rf/dispatch [:my/gate [:tick]])` from a view or test is **refused** at the machine dispatch boundary (a benign no-op with an error trace), while the internal `:raise` is handled normally.

### Tags

Once a machine has several "loading-ish" states, views should ask a predicate (*is it busy?*) rather than match exact state names. XState tags a state and asks `state.hasTag('busy')`; re-frame2 tags with a set and asks a subscription:

```clojure
:submitting {:tags #{:auth/busy} :entry :issue-request :on { … }}
```
```clojure
(when @(rf/machine-has-tag? :auth.login/flow :auth/busy)
  [spinner])
```

At every transition the runtime stamps the union of active states' tags onto the snapshot's `:tags`. The framework ships `[:rf/machine-has-tag? <id> <tag>]` — a derived predicate sub that re-renders only when *this* tag's bit flips — and the `rf/machine-has-tag?` sugar over it. Add a fifth busy state later and it's one `:tags` entry, zero view changes (*ask, don't tell*).

### Schemas: types that actually run

XState v6 declares shapes via `setup({ types })` / `schemas`; re-frame2 declares a machine-level `:schemas` map:

```clojure
(rf/reg-machine :session/auth
  {:initial :anon
   :data    {:retries 0 :token nil}
   :schemas {:data   [:map [:retries :int] [:token [:maybe :string]]]   ;; XState's typed context
             :output :string}                                            ;; the :output-key payload
   :states  { … }})
```

XState's typed context maps to `[:schemas :data]` (re-frame2 calls the slot `:data`, so the schema names exactly what it validates), and `output` maps to `[:schemas :output]`. **Divergence in enforcement:** XState's typed context is compile-time-only and erased at runtime; re-frame2's `[:schemas :data]` is an *actually-running* [Malli](../core/glossary.md#schema) validation in dev — it checks at every macrostep-commit, bootstrap, and spawn, and a violation **rolls the whole cascade back** (`:output` is best-effort fail-loud, since the machine has already finished). Validation is optional and library-agnostic, and DCE's to nothing in production. A visualiser renders the context shape authoritatively from a declared schema instead of inferring it from one sample.

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

This is the most opinionated divergence, and the most easily missed. In XState you can put functions in a lot of places — and v6 adds a `choice` function for dynamic target selection. re-frame2 draws a hard line: **guards and actions are functions, but the transition topology is not.** Targets, `:always`, `:after`, `:choice` candidates — the *shape of the graph* — must be declarative data. So re-frame2 rejects function-valued transitions, and its [`:type :choice`](#choice-states) takes a declarative candidate array, not v6's `choice` function. There's also deliberately no `{:and ...}` guard-combinator DSL: compound conditions go in one *named* function.

**Why:** the spec is the artefact. A machine whose topology is data can be pretty-printed, rendered as a live diagram, diffed in review, and handed whole to an AI with "add a two-factor state" — and the tool sees the entire graph in one form. The moment a target is computed by an opaque function, the diagram has a hole the visualiser can only draw as a shrug, and the AI loses the thread. Names are the same bet: a guard called `:under-retry-limit` renders legibly on the arrow; an inline `{:and [...]}` blob renders as noise. re-frame2 spends a little expressive flexibility to keep the graph *readable by machines other than itself* — which, given that re-frame2 is built AI-first, is the whole point.

### Two smaller deliberate differences

- **`invoke` → `:spawn`.** Same job — start a child machine on state entry, tear it down on exit, collect its result via `:on-done`. Renamed because "invoke" reads like a synchronous call, whereas the thing actually being created is a spawned child actor's worth of lifecycle. See [invoke becomes spawn](#invoke-becomes-spawn) and [spawn](glossary.md#spawn).
- **Completion is event-shaped.** A finishing child's [`:final? true`](#final-states-and-output) leaf selects a value with `:output-key`, and that value flows to the parent's `:on-done` as `result` — at the moment of completion. There's no long-lived `snapshot.output` slot to read later, the way you'd read XState's `output`. Completion is a thing that *happens* (an event), not a thing that *sits there* (state), which keeps it on the same event rails as everything else.

## What stays loud, what stays quiet

One last alignment, because it trips people who expect re-frame2 to be stricter than it is. An **unknown event is a no-op** — exactly as in XState v5/v6 after strict mode was dropped. Dispatch an event the current state doesn't handle and nothing throws; the snapshot doesn't move; a benign `:rf.machine.event/unhandled-no-op` trace records that it arrived and was dropped.

Almost everything *else* that's wrong [fails loud](../core/glossary.md#fail-loud-not-silent) — a guard referencing an undefined name, a target naming a missing state, a `:schemas` sub-key that isn't real, a function where a `:choice` array belongs, an `:internal-events` vector instead of a set — but at **registration** time, not on the unlucky dispatch that first reaches the bad arrow. And a runaway `:always`/`:raise` cycle (which XState *throws* on) is a bounded, atomically-aborted [error record](../core/glossary.md#error-record), not a hang — deliberately distinct from the silent no-op, because a non-converging loop is a bug you want surfaced.

## Further reading

- **The narrative guide.** [Machines concepts](concepts.md) builds the same grammar from scratch with a live, editable [turnstile](concepts.md#see-one-run) and the [recognition kit](concepts.md#guards-and-actions). The [machines glossary](glossary.md) is the one-page vocabulary.
- **The API contract.** [`re-frame.machines`](../api/re-frame.machines.md) — every key, fx, sub, and helper, enumerated (this page deliberately does not restate it).
- **Worked examples.** [`examples/core/login/`](../../examples/core/login/) (the login flow, wired into a live view), [`examples/.../state_machine_walkthrough/`](../../examples/capabilities/machines/state_machine_walkthrough/) (the same machine tested as pure data), [`examples/patterns/nine_states/`](../../examples/patterns/nine_states/) (parallel regions + tags), and the [boot](../../examples/patterns/boot/) / [websocket](../../examples/patterns/websocket/) lifecycle machines.
- **The normative spec.** The exhaustive contract and the full divergence ledger live in [Spec 005 §Lessons from xstate](../../spec/005-StateMachines.md#lessons-from-xstate-deliberate-divergences).
- **The statechart tradition this realises.** re-frame2's machines implement the broader statechart model — see the [XState statecharts docs](https://stately.ai/docs/state-machines-and-statecharts) (the JS reference this page bridges from) and [Fulcro statecharts](https://fulcrologic.github.io/statecharts/) (the same SCXML lineage in another Clojure framework).

# Machines glossary

re-frame2's optional state-machine capability — modelling a feature's lifecycle as an explicit statechart rather than a scatter of boolean flags. Every term below lives within a [machine](#machine), and each entry links to the page that teaches it in depth — usually [the Machines guide](concepts.md). Where a term maps from XState (the behavioural parity reference — re-frame2 matches the *behaviour*, not the API), the entry flags it inline (*XState: …*) and calls out any deliberate divergence; [Coming from XState](coming-from-xstate.md) is the full delta.

Grouped by role: [the core loop](#the-core-loop), [state structure](#state-structure), [transitions and timing](#transitions-and-timing), [actors and composition](#actors-and-composition), [tags](#tags), and [the runtime model](#the-runtime-model).

## The core loop

### **machine**

A statechart-capable state machine, registered as an [event handler](../core/glossary.md#event-handler) with `reg-machine`. It models a feature's lifecycle as explicit [states](#state) and [transitions](#transition) — driven by dispatched [events](../core/glossary.md#event), with [guards](#guard), [actions](#action), timeouts, and child machines (see [spawn](#spawn)) — instead of a scatter of boolean flags in [app-db](../core/glossary.md#app-db).

Its live value is a [snapshot](#snapshot) (the current state plus its `:data`), held in [runtime-db](../core/glossary.md#runtime-db) and read like any other derived state.

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

Related: [Machines](concepts.md); the [`reg-machine` API](../api/re-frame.machines.md#reg-machine).

### **transition table**

The data a machine *is*: a map with `:initial`, the starting [`:data`](#data), the machine-local [`:guards`](#guard) / [`:actions`](#action) maps, an optional `:schemas`, and the `:states` tree. The event keys under each state's `:on` map are what move it. [`reg-machine`](../api/re-frame.machines.md#reg-machine) compiles this literal map into an ordinary [event handler](../core/glossary.md#event-handler) at registration, so the table is the whole specification — pretty-print it, diff it in review, render it as a diagram, or hand it to an AI in one piece.

```clojure
{:initial :idle
 :data    {:attempts 0 :error nil}
 :guards  {:under-retry-limit (fn [{d :data}] (< (:attempts d) 3))}
 :actions {:clear-error (fn [_] {:data {:error nil}})}
 :states  {:idle       {:on {:submit {:target :submitting :action :clear-error}}}
           :submitting {...}}}
```

*XState:* the object you pass to `createMachine` — re-frame2 keeps it as plain Clojure data, with no builder or fluent API. See [The same flow as a transition table](concepts.md#the-same-flow-as-a-transition-table).

### **snapshot**

A [machine](#machine)'s live value at any moment — which state it's in, plus its `:data`. It lives in [runtime-db](../core/glossary.md#runtime-db), and you read it through a [subscription](../core/glossary.md#subscription) addressed by the machine's id.

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])   ;; {:state :authed :data {...}}
```

The snapshot is a plain, printable value (no functions or atoms), so [undo, time-travel](../core/glossary.md#time-travel), persistence, and SSR hydration all work on machines for free. Its `:state` takes one of three forms: a single keyword (flat machine), a vector path (`[:authenticated :cart :browsing]`, a [compound](#compound-state) machine), or a region → state map (a [parallel](#parallel-state) machine).

Related: [Machines](concepts.md); the [`[:rf/machine machine-id]` sub](../api/re-frame.machines.md#rfmachine-machine-id).

### **:data**

A machine's private working memory — the *extended state* that rides alongside the named [state](#state) in every [snapshot](#snapshot). Counters, the in-flight error string, captured credentials: anything that isn't itself a named mode. [Guards](#guard) and [actions](#action) read it from their context map (`{:data …}`); an action *updates* it by returning `{:data …}` (see [action effect map](#action-effect-map)). It must be a printable value so the snapshot round-trips through persistence and time-travel, and a machine sees **only its own** `:data` — never [app-db](../core/glossary.md#app-db).

*XState:* this is XState's **`context`**. re-frame2 renames it because "context" is already overloaded (interceptor context, React context). Taught in [The same flow as a transition table](concepts.md#the-same-flow-as-a-transition-table).

### **state**

One of a [machine](#machine)'s fixed, named, mutually-exclusive modes — `:idle`, `:submitting`, `:authed`. A machine is always in exactly one (or, with [parallel regions](#parallel-state), one per region). A state with no nested `:states` is a *leaf* (or *atomic*) state; one that nests its own `:states` is a [compound state](#compound-state).

### **transition**

The move from one [state](#state) to another in response to a dispatched [event](../core/glossary.md#event) — optionally gated by a [guard](#guard) and running an [action](#action) on the way. In the transition table it's an entry under a state's `:on` map. Transitions can also be *eventless* ([`:always`](#eventless-transition-always), taken on entry when its guard passes) or *timed* ([`:after`](#delayed-transition-after), taken after a delay that auto-cancels when the state exits).

### **guard**

A pure yes/no predicate that decides whether a [transition](#transition) fires. Referenced by name from the table and defined once in the machine's `:guards`, so a visualiser can read the condition right off the arrow. It receives the context map `{:data :event :state :meta}` (note: **no** app-db) and returns a boolean. There's no `{:and …}` combinator DSL — compound logic goes in one named function, so the *name* is what tools and AI read off the arrow.

### **action**

The side work a [transition](#transition) performs: it returns the same `{:data … :fx …}` shape an [event handler](../core/glossary.md#event-handler) returns — updating the machine's own `:data` or firing [effects](../core/glossary.md#effect) — and never writes [app-db](../core/glossary.md#app-db) directly. Defined once in the machine's `:actions`, named from the arrow. The three action slots a transition can run are the source state's `:exit`, the transition's own `:action`, and the target's `:entry`.

### **action effect map**

What an [action](#action) returns: the same `{:data … :fx …}` map a [`reg-event`](../core/glossary.md#event-handler) handler returns. `:data` is **merged** into the snapshot key-by-key (last write wins; a key returned as explicit `nil` *sets* it to `nil` rather than removing it); `:fx` is a vector of `[fx-id args]` pairs the runtime performs for you. Returning `nil` does nothing. Because the action *describes* effects rather than performing them, it stays pure, testable, and replayable.

```clojure
:record-error
(fn [{data :data [_ {:keys [failure]}] :event}]
  {:data (-> data (update :attempts inc)
                  (assoc :error (:message failure)))})
```

*XState:* replaces both `assign({...})` (now the `:data` return) and effectful actions (now the `:fx` return). v6 is removing `assign`'s special status, moving toward the plain-function shape re-frame2 started from. See [Guards, actions, tags, and `:after`](concepts.md#guards-and-actions).

## State structure

### **compound state**

A [state](#state) that nests its own `:states` map plus a required `:initial` substate — a parent mode with child modes (`:authenticated` over `:browsing` / `:checkout`). Entering it cascades down the `:initial` chain to a leaf, and the snapshot's `:state` becomes a **vector path** like `[:authenticated :cart :browsing]`. The runtime resolves an event by walking from the active leaf up to the root — **deepest-wins with parent fallthrough** — so a parent can factor out a transition (`:logout`) that every descendant inherits, while a child can [opt out](#forbidden-transition) or override.

*XState:* a hierarchical / nested state; same `initial`-cascade and bubbling. Listed under [When the machine grows](concepts.md#when-the-machine-grows).

### **parallel state**

A machine declared `:type :parallel` at its root, holding a **`:regions`** map (not `:states`) — the [regions](#region) are **all active at once** rather than one-at-a-time. Three orthogonal axes of three states each is three regions, not twenty-seven cross-product states. The snapshot's `:state` becomes a **map** of region-name → that region's state (`{:data :loading :form :neutral :mode :active}`); all regions share one [`:data`](#data) and their [tags](#state-tag) union across regions. When the axes *don't* share data, prefer N separate machines instead.

*XState:* `type: 'parallel'`. See the [nine_states example](../../examples/patterns/nine_states/) and [When the machine grows](concepts.md#when-the-machine-grows).

### **region**

One orthogonal axis of a [parallel state](#parallel-state) — its own independent sub-state-tree, running concurrently with its sibling regions and sharing the machine's single [`:data`](#data). A dispatched [event](../core/glossary.md#event) broadcasts to every region, and each resolves it independently; a region's slice of the snapshot is keyed by its region-name. Cross-region coordination reads another region's [tags](#state-tag) — never reaching into its state directly.

*XState:* the child states of a `parallel` node.

### **final state**

A leaf marked **`:final? true`**: entering it **terminates the machine** — the runtime auto-destroys it, *even a top-level singleton*. It's for "this run is over," not "this is the last screen" — for a state the machine rests in indefinitely (like `:authed`), use an ordinary leaf and **omit** `:final?`. A final leaf can name an [`:output-key`](#on-done-and-output-key) to report a result, and can be flagged `:error? true` as a designated failure terminal.

*XState:* `final: true` (+ `output`). See [Final states](concepts.md#final-states-when-a-machine-is-done).

### **history state**

A `:type :history` **pseudo-state** you target to re-enter a [compound state](#compound-state) at whichever substate was active when you last left it — a paused player resuming mid-track. **Shallow** restores the immediate child; **deep** restores the full nested path; a `:default-target` covers the never-visited-yet case. The recording rides the [snapshot](#snapshot), so it survives undo and SSR hydration for free.

*XState:* `type: 'history'` (`history: 'shallow' | 'deep'`). Listed under [When the machine grows](concepts.md#when-the-machine-grows).

## Transitions and timing

### **self-transition**

A [transition](#transition) back into the state you're already in. re-frame2 follows **internal-by-default**, with three distinct shapes:

- **Targetless** (omit `:target`) — a true internal no-op: the `:action` runs, but `:exit` / `:entry` don't, `:after` timers aren't restarted, [`:spawn`](#spawn) children aren't torn down. The way to mutate `:data` without disturbing anything.
- **Explicit self-target, no `:reenter?`** — your own `:exit` / `:entry` still don't fire, but a compound **re-resolves its descendants** to `:initial`.
- **External (`:reenter? true`)** — genuinely exits and re-enters: `:exit` → `:action` → `:entry`, and on a compound the whole subtree restarts (`:after` timers reset, `:spawn` children respawn).

*XState:* `:reenter? true` is XState's `reenter: true`. Watch the divergence from XState v4 / SCXML, where a *targeted* self-transition re-enters by default — re-frame2 does not. See [Self-transitions](concepts.md#self-transitions-internal-by-default-external-on-demand).

### **wildcard transition**

An `:on` entry that matches a *class* of events instead of one id. `:on` resolves **three tiers**, most-specific first: the **exact** id (`:mouse/down`), then the **namespace wildcard** `:ns/*` (`:mouse/*` catches every `mouse`-namespaced event and only those), then the **total wildcard** `:*` (anything). A guard-blocked exact match falls through to the coarser tiers.

```clojure
:tracking {:on {:mouse/down {:action :begin-drag}    ;; exact wins for :mouse/down
                :mouse/*    {:action :note-move}      ;; any other :mouse/… event
                :*          {:action :log-unknown}}}  ;; anything else
```

*XState:* `:ns/*` is re-frame2's idiom for v5's prefix descriptor (`mouse.*`), expressed on the keyword's `/` boundary. See [Wildcard transitions](concepts.md#wildcard-transitions-handle-a-whole-class-of-events).

### **forbidden transition**

A present `:on` key whose value is the **empty map** (`{:on {:E {}}}`) or **`nil`** — a handler that *consumes* an event and stops the deepest-wins search without changing state. It's how a child [compound state](#compound-state) **opts out** of a transition its parent would otherwise inherit to it. Distinct from an *unhandled* event (which falls through to coarser tiers and up to ancestors): a forbidden block is itself a match, so the search stops there.

*XState:* `on: { LOGOUT: undefined }`. Covered with [wildcards](concepts.md#wildcard-transitions-handle-a-whole-class-of-events).

### **eventless transition (`:always`)**

A state-node key holding a vector of guarded transitions that fire **with no event** — checked on entry (and after any transition landing in the state), first-passing-guard wins. The way to say "the snapshot just changed; if X is now true, move immediately." An `:always` may **not** target its own state (rejected at registration); the fixed-point pattern is a *targetless* guarded `:always` whose `:action` flips the guard.

```clojure
:checking-form {:always [{:guard :form-valid?   :target :submitting}
                         {:guard :form-invalid? :target :show-errors}]}
```

*XState:* `always` (transient transitions); same firing rule. Settled inside the [microstep](#microstep) loop. See [When the machine grows](concepts.md#when-the-machine-grows).

### **delayed transition (`:after`)**

The declarative timer: a state-node key mapping a delay to a transition. Enter the state and the timer arms; leave it and the timer cancels — no `setTimeout`-plus-cancel-flag to wire. A late-firing timer from an earlier visit is ignored (each visit is epoch-tagged), so you never get a ghost transition. The delay is a positive-integer millisecond count, a [subscription](../core/glossary.md#subscription) vector, or a `(fn [{:keys [snapshot]}] ms)`.

```clojure
:reconnecting {:after {5000 {:target :connecting}}   ;; retry in 5s
               :on    {:net/give-up :failed}}
```

*XState:* `after: { 5000: 'next' }`; same auto-cancel-on-exit. (ISO-8601 durations and the `"5s"`-shorthand rejection belong to [`:timeout`](#timeout), not `:after` — an `:after` key is an ms int, a sub vector, or a fn.) See [Guards, actions, tags, and `:after`](concepts.md#guards-and-actions).

### **timeout**

The named-intent spelling of "this state — or its spawned child — must finish before this deadline": a `:timeout` duration paired with an `:on-timeout` transition. It *lowers onto* the same [`:after`](#delayed-transition-after) timer machinery; it just reads as a deadline rather than a general delay. A duration is integer-ms (`5000`) or an ISO-8601 string (`"PT5S"`, `"PT1H30M"`) — `"5s"` is rejected, failing loud at registration.

*XState:* the deadline idiom you'd build by hand from `after`; re-frame2 gives it a name. Listed under [When the machine grows](concepts.md#when-the-machine-grows).

### **choice state**

A `:type :choice` **transient** routing node: enter it and it resolves *immediately* — same step, no event — to the first candidate whose `:guard` passes. It's [`:always`](#eventless-transition-always)'s decision pattern with a name, so a diagram renders an explicit fork. The candidate list is a **declarative array** that must include an unguarded default and must not declare ordinary state keys.

*XState:* a `choice` node — but re-frame2 keeps the routing as **data**, deliberately rejecting v6's `choice`-*function* form so the graph stays readable by tools. See [When the machine grows](concepts.md#when-the-machine-grows).

### **run-to-completion**

The guarantee that a machine processes one event **to a settled, stable configuration before the next event is seen** — every [`:always`](#eventless-transition-always) microstep and every [`:raise`](#raise)d event drains, then the snapshot [commits](#commit) once. External observers (subs, other machines, tools) only ever see the settled state, never a mid-cascade intermediate. A non-converging `:always` / `:raise` loop can't hang the runtime: it's bounded (default depth 16) and aborts atomically with the snapshot unchanged.

*XState:* RTC / SCXML macrostep semantics — matched deliberately. See [microstep](#microstep) and [macrostep](#macrostep).

## Actors and composition

### **spawn**

A declarative key that starts a *child* machine on entering a [state](#state) and tears it down on leaving; the child reports a result back through [`:on-done`](#on-done-and-output-key). (`:spawn-all` starts several in parallel and joins on completion — re-frame2's spelling of XState's `invoke`.) Under the hood it's the reserved [`[:rf.machine/spawn …]`](../api/re-frame.machines.md#rfmachinespawn-spawn-spec) [effect](../core/glossary.md#effect); emit that directly from any handler when you need a dynamic count rather than one-child-per-state. The running instance it creates is an [actor](#actor).

*XState:* `invoke: { src: childMachine }`, renamed because the thing created is a spawned child's worth of lifecycle, not a synchronous call. See [When the machine grows](concepts.md#when-the-machine-grows).

### **actor**

A *live machine instance* — a [snapshot](#snapshot) sitting at `[:rf.runtime/machines :snapshots <id>]` in [runtime-db](../core/glossary.md#runtime-db). Two kinds: a long-lived **singleton** (one per `reg-machine` id) and a dynamically **[spawned](#spawn)** child (a per-request protocol machine, a wizard's per-step subprocess). An actor's *liveness IS its snapshot's presence* — there's no parallel registry; destroying it removes the snapshot. Address a spawned actor by its gensym'd id, or by role via a [system-id](#system-id).

*XState:* an actor — but re-frame2 has no `createActor` / actor-ref object; the instance is just a value in the frame, which is why time-travel and SSR extend to it for free. See [Coming from XState](coming-from-xstate.md).

### **spawn-all**

The fan-out sibling of [`:spawn`](#spawn): on entering a state it starts **N children in parallel** and **joins** on their completion, resolving when they've all finished (or on the first failure, with a cooperative cancellation cascade). Used for "spawn a worker per chunk and continue when all return."

*XState:* invoking multiple actors and awaiting them. See the [long_running_work example](../../examples/patterns/long_running_work/) and [When the machine grows](concepts.md#when-the-machine-grows).

### **:on-done and :output-key**

How a finishing child reports back. A [final state](#final-state) names an **`:output-key`** — the slot of its [`:data`](#data) to surface as its result. The parent's [`:spawn`](#spawn) declares **`:on-done`**, a `(fn [{:keys [data result]}] new-data)` that fires the instant the child enters its final state, receiving that value as `result`; the runtime then tears the child down. Completion is **event-shaped** — it *happens* and flows, rather than sitting in a readable `output` slot.

```clojure
:done {:final? true :output-key :token}             ;; child reports :data's :token
;; parent:
:authenticating {:spawn {:machine-id :auth-flow
                         :on-done (fn [{:keys [data result]}]
                                    (assoc data :token result))}}
```

*XState:* `final` `output` + `onDone`; re-frame2 keeps it event-shaped, not a long-lived `snapshot.output`. See [Final states](concepts.md#final-states-when-a-machine-is-done).

### **system-id**

A stable **role name** (`:logger`, `:websocket`, `:retry-coordinator`) bound to a spawned [actor](#actor), so a parent can address its child by *role* instead of by gensym'd id. The canonical action-side surface is the reserved `[:rf.machine/dispatch-to-system [system-id event]]` [effect](../core/glossary.md#effect) — a machine [action](#action) can't read app-db, so the fx tuple is how it messages a named child.

```clojure
{:fx [[:rf.machine/dispatch-to-system [:logger [:logger/flush]]]]}
```

See the [`machine-by-system-id`](../api/re-frame.machines.md#re-framemachinesmachine-by-system-id) / [`dispatch-to-system`](../api/re-frame.machines.md#re-framemachinesdispatch-to-system) lookup helpers.

### **:raise**

A reserved, **machine-only** fx-id. Inside an [action](#action)'s `:fx`, `[:raise [:some-event …]]` loops an event back into *this same machine* — **atomically, pre-commit**, drained FIFO through a local queue inside the one handler invocation (it never touches the router queue). Use it when one transition's outcome should immediately drive another, all inside one [macrostep](#macrostep). Contrast `:fx [[:dispatch [self-id …]]]`, which is a *separate*, post-commit [event](../core/glossary.md#event) (its own [epoch](../core/glossary.md#epoch)).

```clojure
{:actions {:kick (fn [_] {:fx [[:raise [:tick]]]})}}
```

*XState:* `raise({type: …})`. See [`[:raise event-vec]`](../api/re-frame.machines.md#raise-event-vec) and [When the machine grows](concepts.md#when-the-machine-grows).

### **:internal-events**

A top-level **set** of event ids that are machine *plumbing* — events the machine [`:raise`](#raise)s at itself, not for a view or test to dispatch. An internal `:raise` of one is handled normally; an *external* dispatch is **refused** at the machine's boundary (no transition; a `:rf.error/machine-internal-event-external-dispatch` trace fires).

```clojure
{:internal-events #{:check-complete}
 :states {...}}
```

*XState:* v6's `internalEvents` — spelled as a Clojure **set** (membership is what you're declaring, not order). See [When the machine grows](concepts.md#when-the-machine-grows).

## Tags

### **state tag**

A label like `:auth/busy` attached to several [machine](#machine) [states](#state); the active state's tags ride on the [snapshot](#snapshot), so a [view](../core/glossary.md#view) can ask "is it busy?" ([`machine-has-tag?`](../api/re-frame.machines.md#machine-has-tag)) instead of enumerating exact state names — *ask, don't tell*. Add a sixth busy state and no view changes. Across [parallel regions](#region), every active state's tags union onto the one snapshot.

```clojure
(when @(rf/subscribe [:rf/machine-has-tag? :auth.login/flow :auth/busy])
  [spinner])
```

*XState:* `tags: ['busy']` + `state.hasTag('busy')`; the membership question is a [subscription](../core/glossary.md#subscription). See [Guards, actions, tags, and `:after`](concepts.md#guards-and-actions).

## The runtime model

These terms describe *how* a transition actually runs. You can build machines without them, but they're the vocabulary for reasoning about ordering and atomicity — and what the [trace stream](../core/glossary.md#trace-stream) shows you.

### **drain**

The deterministic ordering rules by which a machine's effects compose at four nested levels: within one action's `:fx` (vector order; `:data` lands before `:fx`); across the `:exit` → `:action` → `:entry` slots of one [transition](#transition); within one machine event (the [microstep](#microstep) loop over [`:always`](#eventless-transition-always) and [`:raise`](#raise)); and across the runtime's per-frame queue (FIFO, except machine-originated continuation events leap-frog to the front so a machine settles its [macrostep](#macrostep) before the next external event). The order in the source is the order at runtime — no reordering for "optimisation."

### **microstep**

One iteration of the settle loop *inside* a single machine event: after the resolving transition, the runtime **prefers an enabled [`:always`](#eventless-transition-always)** transition; only when none is enabled does it **dequeue one [`:raise`](#raise)d event** (FIFO). It loops until no `:always` is enabled *and* the raise-queue is empty — the fixed point. Microsteps are **not** separately observable; they're the internal steps that compose one [macrostep](#macrostep). Bounded at depth 16 (`:always-depth-limit` / `:raise-depth-limit`).

*XState:* SCXML §3.13 microstep / `selectEventlessTransitions` — matched deliberately.

### **macrostep**

The whole machine event — the resolving transition plus every [`:always`](#eventless-transition-always) [microstep](#microstep) and every [`:raise`](#raise)d sub-event — seen as **one logical step** from outside: one [commit](#commit), one trace row, one [epoch](../core/glossary.md#epoch). External observers only ever see the post-commit settled [snapshot](#snapshot), never an intermediate. This is what makes a machine [run-to-completion](#run-to-completion).

*XState:* the SCXML macrostep / run-to-completion boundary.

### **commit**

The single, deferred [runtime-db](../core/glossary.md#runtime-db) write that lands a [macrostep](#macrostep)'s settled [snapshot](#snapshot) at `[:rf.runtime/machines :snapshots <id>]` — **once per transition**, no matter how many actions or microsteps fired. It's the boundary at which the [`:schemas :data`](concepts.md#validating-a-machines-data) check runs (a violation rolls the whole transition back) and the point a reading [subscription](../core/glossary.md#subscription) re-fires. (See the general framework [commit](../core/glossary.md#commit).)

### **LCCA (least common compound ancestor)**

Also written **LCA**. For a [transition](#transition) from source path A to target path B inside a [compound state](#compound-state), the LCCA is the deepest state that stays active across the move — it neither exits nor enters. The exit cascade runs `:exit` from A's leaf *up to (not including)* the LCCA (deepest-first); the entry cascade runs `:entry` from just below the LCCA *down to* B's leaf (shallowest-first); the transition `:action` fires once at the boundary. For the common case it's just the longest common prefix of A and B — *except* when B is on A's active path or a descendant the declaring compound names, where it pulls up to a **proper** ancestor so the targeted subtree actually re-resolves. For a flat machine the LCCA is the root, and the rule collapses to plain `:exit` → `:action` → `:entry`.

*XState:* the same LCCA the SCXML algorithm uses to compute exit/entry sets.

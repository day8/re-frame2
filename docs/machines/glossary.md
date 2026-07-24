# Machines glossary

re-frame2's optional state-machine capability. One term, definition first; short
code when the spelling matters; *See* / Related points at the teaching page.

Grouped by role: [the core loop](#the-core-loop), [state structure](#state-structure),
[transitions and timing](#transitions-and-timing), [actors and composition](#actors-and-composition),
[tags](#tags), and [the runtime model](#the-runtime-model).

## The core loop

### **machine**

A statechart registered as an [event handler](../core/glossary.md#event-handler) with
`reg-machine`. Models a feature's lifecycle as explicit [states](#state) and
[transitions](#transition) — driven by `dispatch`, with [guards](#guard),
[actions](#action), timers, and optional child machines ([spawn](#spawn)) — instead
of boolean flags in [app-db](../core/glossary.md#app-db).

Live value is a [snapshot](#snapshot) in [runtime-db](../core/glossary.md#runtime-db).

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

Related: [Machines](concepts.md); [`reg-machine`](../api/re-frame.machines.md#reg-machine).

### **transition table**

The data a machine *is*: `:initial`, starting [`:data`](#data), machine-local
[`:guards`](#guard) / [`:actions`](#action), optional `:schemas`, and the `:states`
tree. Event keys under each state's `:on` move it. [`reg-machine`](../api/re-frame.machines.md#reg-machine)
compiles the map into an ordinary event handler at registration.

```clojure
{:initial :idle
 :data    {:attempts 0 :error nil}
 :guards  {:under-retry-limit (fn [{d :data}] (< (:attempts d) 2))}
 :actions {:clear-error (fn [_] {:data {:error nil}})}
 :states  {:idle       {:on {:submit {:target :submitting :action :clear-error}}}
           :submitting {...}}}
```

See [The idea](concepts.md#the-idea).

### **snapshot**

A machine's live value — current [state](#state) plus `:data` (and optional `:tags`).
Lives in [runtime-db](../core/glossary.md#runtime-db); read via subscription.

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])   ;; {:state :authed :data {...}}
```

Plain printable value (no functions or atoms), so undo, [time-travel](../core/glossary.md#time-travel),
persistence, and SSR hydration work without extra wiring. `:state` is a keyword
(flat), a path vector (compound), or a region → state map (parallel).

Related: [Machines](concepts.md); [`[:rf/machine machine-id]`](../api/re-frame.machines.md#rfmachine-machine-id).

### **:data**

Machine-private working memory — counters, error strings, captured credentials —
riding beside the named [state](#state) in every [snapshot](#snapshot). Guards and
actions read it from their context map; an action updates it by returning
`{:data …}` (see [action effect map](#action-effect-map)). Must be printable. A
machine sees **only its own** `:data` — never [app-db](../core/glossary.md#app-db).

Taught in [The idea](concepts.md#the-idea).

### **state**

One fixed, named, mutually exclusive mode — `:idle`, `:submitting`, `:authed`.
With [parallel regions](#parallel-state), one per region. Leaf = no nested
`:states`; [compound](#compound-state) = nests its own `:states`.

### **transition**

Move from one [state](#state) to another on a dispatched [event](../core/glossary.md#event),
optionally gated by a [guard](#guard) and running an [action](#action). Written under
a state's `:on`. Also *eventless* ([`:always`](#eventless-transition-always)) or
*timed* ([`:after`](#delayed-transition-after)).

### **guard**

Pure yes/no predicate on a [transition](#transition). Named in `:guards`, referenced
from the table. Receives `{:data :event :state :meta}` (**no** app-db); returns a
boolean. No `{:and …}` combinator — compound logic is one named function.

Runs *before* the transition's [action](#action), so it sees the pre-action snapshot
— `(< (:attempts d) 2)` for a three-attempt policy. See [Guards](concepts.md#guards).

### **action**

Side work on a [transition](#transition): returns `{:data … :fx …}` like an
[event handler](../core/glossary.md#event-handler); never writes [app-db](../core/glossary.md#app-db)
directly. Named in `:actions`. Slots: source `:exit`, transition `:action`, target
`:entry`.

### **action effect map**

What an [action](#action) returns. `:data` is **merged** into the snapshot (explicit
`nil` sets a key to nil; does not remove keys); `:fx` is a vector of `[fx-id args]`.
`nil` / `{}` means no effects. Describes work rather than performing it.

```clojure
:record-error
(fn [{data :data [_ {:keys [error]}] :event}]
  {:data (-> data (update :attempts inc)
                  (assoc :error (:message error)))})
```

See [The effect map](concepts.md#the-effect-map-data-fx).

## State structure

### **compound state**

A [state](#state) with its own `:states` map and required `:initial` — parent mode
with child modes. Snapshot `:state` becomes a **vector path**
(`[:authenticated :cart :browsing]`). Event resolution walks leaf → root
(**deepest-wins with parent fallthrough**). Child can [opt out](#forbidden-transition)
or override.

See [Hierarchical states](hierarchical-states.md).

### **parallel state**

Root `:type :parallel` with a **`:regions`** map — all [regions](#region) active at
once. Snapshot `:state` is a region-name → state map; one shared [`:data`](#data);
[tags](#state-tag) union across regions. If axes don't share data, use N machines.

See [Parallel states](parallel-states.md); [nine_states](../../examples/patterns/nine_states/).

### **region**

One orthogonal axis of a [parallel state](#parallel-state) — independent
sub-state-tree, concurrent with siblings, sharing the machine's [`:data`](#data).
Events broadcast to every region; each resolves independently. Cross-region
coordination reads sibling [tags](#state-tag).

### **final state**

Leaf marked **`:final? true`**: entering it **terminates the machine** (auto-destroy,
including top-level singletons). For a resting end-screen (`:authed`), omit
`:final?`. May name [`:output-key`](#on-done-and-output-key); may set `:error? true`.
A final *nested in a compound* ends the sub-flow, not the machine.

See [Actors → When a child finishes](actors.md#when-a-child-finishes);
[Hierarchical states → nested finals](hierarchical-states.md#when-a-sub-flow-finishes-nested-final-states).

### **history state**

`:type :history` **pseudo-state** targeted to re-enter a [compound](#compound-state)
at the last active substate. Shallow = immediate child; deep = full path;
`:default-target` for never-visited. Recording rides the [snapshot](#snapshot).

See [History states](history.md).

## Transitions and timing

### **self-transition**

Transition back into the current state. re-frame2 is **internal-by-default**:

- **Targetless** (omit `:target`) — action only; no exit/entry; timers and spawns undisturbed.
- **Self-target, no `:reenter?`** — own exit/entry still skip; compounds re-resolve descendants to `:initial`.
- **`:reenter? true`** — full exit → action → entry (timers reset, spawns restart).

See [Self-transitions and wildcards](concepts.md#self-transitions-and-wildcards).

### **wildcard transition**

`:on` key matching a class of events. Three tiers, most-specific first: exact id →
`:ns/*` → `:*`. Guard-blocked exact falls through to coarser tiers.

```clojure
:tracking {:on {:mouse/down {:action :begin-drag}
                :mouse/*    {:action :note-move}
                :*          {:action :log-unknown}}}
```

See [Self-transitions and wildcards](concepts.md#self-transitions-and-wildcards).

### **forbidden transition**

Present `:on` key with value `{}` or `nil` — consumes the event and stops
deepest-wins search without changing state. How a child opts out of a parent
transition. Distinct from unhandled (missing key), which falls through.

Covered with [wildcards](concepts.md#self-transitions-and-wildcards).

### **eventless transition (`:always`)**

State-node key: vector of guarded transitions that fire **with no event** —
checked on entry and after any landing transition; first-passing-guard wins. Must
not target its own state (registration reject). Fixed-point form: targetless
guarded `:always` whose action flips the guard.

```clojure
:checking-form {:always [{:guard :form-valid?   :target :submitting}
                         {:guard :form-invalid? :target :show-errors}]}
```

Settled inside the [microstep](#microstep) loop. See [Automatic transitions](automatic-transitions.md).

### **delayed transition (`:after`)**

Declarative timer: delay → transition. Enter arms; leave cancels. Epoch-tagged so
late firings from earlier visits are ignored. Delay: positive-int ms, subscription
vector, or `(fn [{:keys [snapshot]}] ms)`.

```clojure
:reconnecting {:after {5000 {:target :connecting}}
               :on    {:net/give-up :failed}}
```

ISO-8601 / `"5s"` shorthand belong to [`:timeout`](#timeout), not `:after`. See
[Automatic transitions](automatic-transitions.md).

### **timeout**

Named deadline: `:timeout` + `:on-timeout`. Lowers onto [`:after`](#delayed-transition-after).
Duration: integer-ms or ISO-8601 (`"PT5S"`); `"5s"` rejected at registration.

See [Automatic transitions](automatic-transitions.md).

### **choice state**

`:type :choice` transient routing node: resolves immediately on entry to the first
candidate whose guard passes. Declarative candidate **array** (must include unguarded
default); no ordinary state keys. Desugars to [`:always`](#eventless-transition-always).

See [Automatic transitions](automatic-transitions.md).

### **run-to-completion**

One event processes to a settled configuration before the next is seen — every
[`:always`](#eventless-transition-always) microstep and [`:raise`](#raise) drains,
then the snapshot [commits](#commit) once. External observers never see mid-cascade
intermediates. Non-converging loops are depth-bounded (default 16) and abort with
the snapshot unchanged.

See [microstep](#microstep), [macrostep](#macrostep).

## Actors and composition

### **spawn**

Declarative key that starts a child machine on state entry and tears it down on
exit; result returns via [`:on-done`](#on-done-and-output-key). (`:spawn-all` starts
several in parallel and joins.) Under the hood: reserved
[`[:rf.machine/spawn …]`](../api/re-frame.machines.md#rfmachinespawn-spawn-spec)
effect. Running instance is an [actor](#actor).

See [Actors](actors.md).

### **actor**

Live machine instance — a [snapshot](#snapshot) at
`[:rf.runtime/machines :snapshots <id>]` in [runtime-db](../core/glossary.md#runtime-db).
Two kinds: long-lived **singleton** (`reg-machine` id) and dynamically
**[spawned](#spawn)** child. Liveness *is* snapshot presence. Address by allocated
id (`<prefix>#<n>`, never `gensym`) or [system-id](#system-id).

### **spawn-all**

Fan-out sibling of [`:spawn`](#spawn): starts **N children in parallel** and
**joins** on completion (or first failure with cooperative cancel).

See [long_running_work](../../examples/patterns/long_running_work/);
[Actors → Fan-out and join](actors.md#fan-out-and-join-with-spawn-all).

### **:on-done and :output-key**

How a finishing child reports back. Final state names **`:output-key`** (slot of
[`:data`](#data) to surface). Parent's [`:spawn`](#spawn) declares **`:on-done`**
`(fn [{:keys [data result]}] new-data)`; runtime then tears the child down.
Completion is event-shaped — it *happens*, not a long-lived `output` slot.

```clojure
:done {:final? true :output-key :token}
:authenticating {:spawn {:machine-id :auth-flow
                         :on-done (fn [{:keys [data result]}]
                                    (assoc data :token result))}}
```

See [Actors → When a child finishes](actors.md#when-a-child-finishes).

### **system-id**

Stable role name (`:logger`, `:websocket`) bound to a spawned [actor](#actor).
Action-side: `[:rf.machine/dispatch-to-system [system-id event]]` — actions can't
read app-db, so the fx is how they message a named child.

```clojure
{:fx [[:rf.machine/dispatch-to-system [:logger [:logger/flush]]]]}
```

Parked XState v6 parity escape (`systemId`); everyday send is plain `dispatch` to
the id you hold. See [`machine-by-system-id`](../api/re-frame.machines.md#re-framemachinesmachine-by-system-id).

### **:raise**

Machine-only fx-id. Inside an action's `:fx`, `[:raise [:some-event …]]` loops an
event into *this* machine — **atomically, pre-commit**, FIFO inside one handler
invocation (never the router queue). Contrast `:fx [[:dispatch [self-id …]]]`
(separate post-commit event / [epoch](../core/glossary.md#epoch)).

```clojure
{:actions {:kick (fn [_] {:fx [[:raise [:tick]]]})}}
```

See [`[:raise event-vec]`](../api/re-frame.machines.md#raise-event-vec);
[When the machine grows](concepts.md#when-the-machine-grows).

### **:internal-events**

Top-level **set** of event ids that are machine plumbing — raised at itself, not
for external dispatch. External dispatch is refused
(`:rf.error/machine-internal-event-external-dispatch` trace).

```clojure
{:internal-events #{:check-complete}
 :states {...}}
```

See [When the machine grows](concepts.md#when-the-machine-grows).

## Tags

### **state tag**

Label like `:auth/busy` on several [states](#state); active tags ride the
[snapshot](#snapshot). Views ask
[`[:rf.machine/has-tag? …]`](../api/re-frame.machines.md#rfmachinehas-tag-machine-id-tag)
instead of enumerating names. Across [parallel regions](#region), tags union onto
one snapshot.

```clojure
(when @(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])
  [spinner])
```

See [Tags](tags.md).

## The runtime model

Vocabulary for ordering and atomicity — and what the [trace stream](../core/glossary.md#trace-stream)
shows. You can ship machines without memorising these.

### **drain**

Deterministic ordering of effects at four levels: within one action's `:fx`
(`:data` before `:fx`); across `:exit` → `:action` → `:entry`; within one machine
event ([microstep](#microstep) over [`:always`](#eventless-transition-always) and
[`:raise`](#raise)); across the per-frame queue (FIFO, with machine continuations
leap-frogging so a [macrostep](#macrostep) settles first). Source order is runtime
order.

### **microstep**

One settle-loop iteration inside a machine event: prefer an enabled
[`:always`](#eventless-transition-always); else dequeue one [`:raise`](#raise)
(FIFO). Loops to fixed point. Not separately observable; composes one
[macrostep](#macrostep). Bounded at depth 16.

### **macrostep**

Whole machine event — resolving transition plus every microstep and raise — as
**one** logical step outside: one [commit](#commit), one trace row, one
[epoch](../core/glossary.md#epoch). External observers see only the post-commit
snapshot. This is [run-to-completion](#run-to-completion).

### **commit**

Single deferred [runtime-db](../core/glossary.md#runtime-db) write of a
[macrostep](#macrostep)'s settled [snapshot](#snapshot) at
`[:rf.runtime/machines :snapshots <id>]` — once per transition. Boundary for
[`:schemas :data`](concepts.md#validating-a-machines-data) (violation rolls back)
and for subscription re-fire. (Framework [commit](../core/glossary.md#commit).)

### **LCCA (least common compound ancestor)**

Also **LCA**. For a [transition](#transition) from path A to B inside a
[compound](#compound-state), the deepest state that stays active — neither exits
nor enters. Exit cascade: leaf up to (not including) LCCA, deepest-first. Entry:
just below LCCA down to B's leaf, shallowest-first. Transition `:action` once at
the boundary. Flat machine: LCCA is the root → plain exit → action → entry.

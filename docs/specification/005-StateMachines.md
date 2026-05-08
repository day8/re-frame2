# Spec 005 — State Machines

> Status: Drafting. Post-v1 per [000 §Scope and roadmap](000-Vision.md#scope-and-roadmap). Builds on the foundation hooks in [002-Frames.md §State machines are just event handlers](002-Frames.md).
>
> **Why this Spec exists:** state machines and actors are in the pattern because **constrained execution models are easier to reason about than Turing-complete control flow.** A finite state machine has an enumerable state space and a discrete transition relation; an actor system bounds concurrency to message-passing across well-defined boundaries with run-to-completion semantics. This is disproportionately valuable for AI use — an AI can fully simulate a finite machine; it cannot fully simulate a free-form imperative program. The cost is some expressiveness; the benefit is an execution model that survives mechanical reasoning.
>
> For where Levels 1–4 sit in relation to the rest of the runtime (registrar, frame container, sub-cache, substrate adapter, trace bus), see [Runtime-Architecture](Runtime-Architecture.md).

## Abstract

A state machine in re-frame2 is **an event handler whose body interprets a transition table**. Machines are registered as event handlers via `reg-event-fx + create-machine-handler`; the registered handler is the entire surface. The framework's machine-specific hooks live in 002 — drain semantics, the snapshot shape, the inspection trace surface, and the `:raise` / `:spawn` reserved fx-ids that the machine handler routes locally.

For readers familiar with xstate, [§Lessons from xstate](#lessons-from-xstate-deliberate-divergences) at the end of this spec lays out the points of contrast.

## Why machines

Machines serve two distinct use cases:

1. **High-level workflow.** Multi-step user flows (signup → verify → onboard → home), modal dismissal logic, wizard navigation. Without machines these get smeared across many event handlers and an `:app/screen` keyword in `app-db`; the smearing is the pain.
2. **Low-level protocols.** Async resource lifecycles (HTTP request: `idle → loading → success/error/retry`), websocket connection states, animation transitions. Without machines these live as ad-hoc keywords in some sub-tree of `app-db`, with handlers that have to remember "if state is `:loading`, ignore another `:fetch`."

Both want the same primitive but the ergonomics matter differently — workflow machines are few and named (one per major subsystem); protocol machines may have many concurrent instances (one per active resource). The same `create-machine-handler` factory covers both: a singleton machine is registered at boot via `reg-event-fx`; a dynamic instance is registered at run time via `spawn-machine`.

## Naming — `:state` and `:data`

The snapshot is `{:state :data}`:

- `:state` — the FSM-keyword (`:idle`, `:editing`, `:loading`, ...). Discrete; enumerable; what xstate calls `state.value`.
- `:data` — extended state, the machine's own private memory: a plain map distinct from `app-db`. The term tracks FSM literature and Erlang `gen_statem`'s "state data"; xstate calls the same slot "context".

The pair `{:state :data}` reads as the natural English idiom and matches a vocabulary that's well-represented in AI training corpora. We use `:data` to avoid the existing "context" overloading in re-frame's interceptor pipeline and React-context affordances.

> **`:data` is the parameter name passed to guards and actions, not a destructure key.** Per [§Guards](#guards) / [§Actions](#actions), guards and actions receive `(fn [data event] ...)` — `data` is the snapshot's `:data` slot directly, a plain map. Bodies that read individual fields write `(:circle-id data)`, not `(get-in snapshot [:data :circle-id])`. The 3-arity opt-in `(fn [data event {:state :meta}] ...)` adds the introspection slot when needed; users that don't declare it never see the snapshot wrapper.

## Snapshot shape

```clojure
{:state <fsm-keyword-or-path>   ;; :idle, :editing, ... — OR [:checkout :payment :credit-card]
 :data  <map>                    ;; the machine's private memory
 :meta  {<optional> ...}}        ;; reserved for :rf/snapshot-version etc.
```

`:state` is **dual**:

- **Flat machines** — `:state` is a single FSM-keyword (`:idle`, `:editing`, `:loading`, ...). Equivalent to xstate's `state.value` for a non-compound machine. The flat-machine grammar in [§Transition table grammar](#transition-table-grammar) and the Circle Drawer worked example use this form.
- **Compound machines** — `:state` is a **vector path** from the root state-node to the active leaf (`[:authenticated :cart :browsing]`). The vector form is required when any state in the machine is a compound state (declares its own nested `:states`); it disambiguates "which `:browsing`?" when the same leaf-keyword could appear under multiple parents. See [§Hierarchical compound states](#hierarchical-compound-states).

Implementations accept both forms on read (a single keyword `:K` is treated as the path `[:K]` whenever a path is needed); tools may normalise to vector internally for uniform manipulation. Parallel-region forms (`:state` as a nested map of region → keyword) remain post-v1; the substitute is one machine per region per [§Substitutes for skipped features](#substitutes-for-skipped-features).

Stability invariants — every conformant implementation upholds these so snapshots survive the wire (SSR hydration per [011](011-SSR.md)) and the time-axis (Tool-Pair epoch replay):

1. **Print/read round-trip.** `(read-string (pr-str snapshot))` returns an `=`-equal value. No functions, atoms, JS objects, or other unprintable values may appear in `:data`. Implementations that need such things keep them at a sibling path in `app-db`, not inside the snapshot.
2. **No in-flight microstate.** Snapshots represent *committed* state only. A machine mid-transition is not snapshotted; the runtime takes snapshots at the boundaries of `machine-transition`.
3. **Stable shape across re-registration.** Hot-reloading a machine handler does not invalidate existing snapshots whose `:state` is still a member of the new definition's `:states`. Snapshots whose `:state` is no longer present transition to the new `:initial` and emit `:rf.warning/machine-state-not-in-definition`.
4. **Versioned via `:meta`.** When a definition's transition shape changes incompatibly, bump `:meta :rf/snapshot-version` on the *definition*. Restore compares the snapshot's `:rf/snapshot-version` to the definition's; mismatch emits `:rf.warning/machine-snapshot-version-mismatch`.

**Restore semantics.** The contract is "replace `app-db` at `[:rf/machines <id>]` with the snapshot value." There is no separate restore-callback, no init event firing again — the snapshot *is* the state. SSR hydration ([011](011-SSR.md)) and Tool-Pair epoch restore both use this contract.

See [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot).

## Where snapshots live

Every machine snapshot lives at a fixed reserved path: **`[:rf/machines <machine-id>]`** in the frame's `app-db`. The runtime owns this path; users do not pick a path per machine and `create-machine-handler` does not accept a `:path` key.

For the registration `(rf/reg-event-fx :drawer/editor (rf/create-machine-handler {...}))`:

```clojure
;; in the frame's app-db, after initialisation:
{:rf/machines {:drawer/editor {:state :idle :data {:circle-id nil ...}}}}
```

For a spawned actor whose gensym'd id is `:request/protocol#42`:

```clojure
{:rf/machines {:request/protocol#42 {:state :loading :data {:url "/foo"}}}}
```

`:rf/machines` is a **reserved app-db key** (alongside the existing `:rf/*` reserved-keyword convention; see [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). Its value is a `[:map-of :keyword :rf/machine-snapshot]` — keyed by the machine's registered id. User app-db code MUST NOT write under `:rf/machines`.

Why the locked path — the load-bearing reason is [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility): co-locating snapshots in `app-db` is the named mechanism by which machine state inherits revertibility. When a frame's value reverts, every machine snapshot reverts with it. A parallel ActorRef registry or a per-machine atom would put machine state outside the frame's value and break the goal. The five concrete consequences below all flow from that:

1. **Encapsulation.** A machine's snapshot is its private state; the rest of `app-db` is the rest of the app. Co-locating all snapshots under one reserved key keeps the boundary visible at a glance.
2. **No path collisions.** Two features that both want a `[:foo :flow]` machine cannot accidentally share a snapshot location. Ids are already unique within a frame; reusing them as the in-`app-db` key inherits that uniqueness for free.
3. **Tooling.** `(get-in @(get-frame-db frame-id) [:rf/machines])` enumerates every live machine snapshot in one read. Pair tools, 10x, and conformance harnesses use this directly.
4. **Per-frame isolation is automatic.** Each frame has its own `app-db`, and thus its own `:rf/machines` map. The same machine id can exist in multiple frames; their snapshots are isolated by virtue of living in different frames' app-dbs (per [002 §Frames](002-Frames.md)). Inside one frame, the id is unique.
5. **AI-amenability.** "Where is the snapshot?" has one answer at all times. AIs do not need to consult per-machine metadata to find state.

> **Cost: feature-locality.** Putting machine snapshots under a reserved global subtree means they don't sit alongside their feature's own app-db slice. A user inspecting `[:auth ...]` won't see the auth flow's machine snapshot there — it lives at `[:rf/machines :auth/login-flow]`. This is a real cost. We accept it because the wins (uniform tool support, atomic revertibility per [Goal 2 of 000-Vision](000-Vision.md#frame-state-revertibility), host-independent storage scheme, automatic SSR hydration / persistence / undo) require *one* structural location for machine state. Tooling can present a feature-local view (10x's app-db panel can render a "Machines" section adjacent to the feature's data) without compromising the storage scheme.

The runtime composes `:rf/machines`'s schema automatically from the registered machines' `:data` schemas — `(rf/app-schema-at [:rf/machines])` returns `[:map-of :keyword :rf/machine-snapshot]`, and per-machine entries refine `:rf/machine-snapshot` against the registered machine's declared `:data` shape.

### What the Single Store gives us for free

Because every machine's snapshot lives in `app-db` at `[:rf/machines <id>]` — not in a parallel ActorRef registry, not in a per-machine atom — every facility re-frame already provides for `app-db` automatically extends to machines:

- **Undo / redo.** An undo interceptor that snapshots `app-db` before/after a handler captures machine state along with everything else.
- **Time-travel debugging.** Tool-Pair's epoch buffer records `:db-before` / `:db-after` on each drain; rewinding restores machines to their prior snapshot at no extra cost.
- **SSR hydration.** The `:rf/hydrate` event replaces `app-db` with the server-supplied payload (per [011](011-SSR.md)); machine snapshots ride along with the rest of the state — no separate hydration channel.
- **Persistence.** Writing `app-db` to localStorage / IndexedDB / a server endpoint serialises machines too. Reloading deserialises them back into `[:rf/machines <id>]` and the next event sees them.
- **Conformance fixtures.** A fixture's `:fixture/expect :final-app-db` covers machine state without needing a machine-specific assertion.
- **Schema validation.** `(rf/reg-app-schema [:rf/machines] ...)` validates the whole machine map; per-machine `:data` schemas refine it (per [§Where snapshots live](#where-snapshots-live)).
- **Trace replay.** Tool-Pair epochs replay events against `:db-before` to reproduce a session; machine transitions replay along with everything else because their state is in the db.
- **`make-restore-fn`.** The standard restore fn captures `app-db` and reapplies it; machines come back with the rest of state.

The argument: **the Single Store invariant pays off again.** Every `app-db` capability re-frame already ships extends to machines without a single line of machine-specific implementation. This is why machine snapshots live in the db rather than in a parallel substrate. The undo / redo, time-travel, persistence, and snapshot-restore items above are not coincidences — they are the concrete consequences of the named [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility) when machine state lives inside the frame's persistent value.

## Transition table grammar

A transition table is pure data. Top-level shape:

```clojure
{:initial <fsm-keyword>                     ;; required — initial state
 :data    {<initial data>}                  ;; optional — initial data map
 :guards  {<keyword> <fn>, ...}             ;; optional — machine-local named guard impls
 :actions {<keyword> <fn>, ...}             ;; optional — machine-local named action impls
 :states  {<fsm-keyword> <state-node>, ...} ;; required
 :on      {<event-id> <transition>, ...}    ;; optional — top-level fallback
 :meta    {<user-keys> ...}}                ;; optional — e.g. :rf/snapshot-version
```

The snapshot's location in `app-db` is `[:rf/machines <id>]` — runtime-managed and not part of the transition-table grammar. See [§Where snapshots live](#where-snapshots-live).

> **The transition-table spec map MUST NOT carry `:id`.** A machine's id is the surrounding registration's event-id (the first arg to `reg-event-fx` or `spawn-machine`), not a field on the spec map. The runtime derives the id at handler-call time from the dispatched event vector's first element. Keeping `:id` out of the spec map keeps it a pure description of behaviour and lets the same spec value register against multiple ids if the application wants two independent machines with the same body.

#### Transition table top-level keys

| Key | Where | Notes |
|---|---|---|
| `:initial` | top-level | required — the initial FSM-keyword |
| `:data` | top-level | optional — initial data map |
| `:guards` | top-level | optional — `{<keyword> <fn>}` map of machine-local named guards; referenced by keyword from `:guard` slots |
| `:actions` | top-level | optional — `{<keyword> <fn>}` map of machine-local named actions; referenced by keyword from `:action` / `:entry` / `:exit` slots |
| `:meta` | top-level | optional — e.g. `:rf/snapshot-version` |
| `:states` | top-level | required — map of FSM-keyword → state node |
| `:on` | per-state and top-level | event-driven transition map |
| `:on` keys | event keyword or `:*` wildcard | wildcard matches any unhandled event |
| `:entry`, `:exit` | per-state | one fn or one keyword reference into the machine's `:actions` map |
| `:invoke` | per-state | declarative spawn-on-entry / destroy-on-exit child actor — sugar that desugars at registration time per [§Declarative `:invoke`](#declarative-invoke-sugar-over-spawn) |
| transition shape | per-event | `{:target :guard :action :meta}` |
| multiple-candidate transitions | per-event | vector of guarded specs, first-match-wins |
| self-transitions | per-event | `:target :same-state` (external) or omit `:target` (internal) |
| per-state `:meta` | per-state | tooling-visible, e.g. `{:terminal? true}` |

### State nodes

Every state in `:states` is a map. The complete state-node grammar — every key the v1 CLJS reference recognises:

```clojure
{:on      {<event-id> <transition>, ...}    ;; event-driven transitions
 :always  [<guarded-transition>, ...]        ;; eventless transitions (see §Eventless `:always`)
 :after   {<delay> <transition>, ...}        ;; delayed transitions (see §Delayed `:after`)
 :entry   <fn-or-keyword>                    ;; ran on entering this state; keyword resolves into :actions map
 :exit    <fn-or-keyword>                    ;; ran on exiting this state; keyword resolves into :actions map
 :invoke  <invoke-spec>                      ;; spawn child on entry; destroy on exit (see §Declarative :invoke)
 :initial <fsm-keyword>                      ;; required IFF the state is itself compound (declares :states)
 :states  {<fsm-keyword> <state-node>, ...}  ;; nested substates — makes this a compound state
 :meta    {<user-keys> ...}}                 ;; e.g. {:terminal? true}
```

All keys are optional except `:initial` (which is required when `:states` is present — see [§Hierarchical compound states](#hierarchical-compound-states)). Capability-gating: `:always`, `:after`, `:invoke`, and `:states` / `:initial` are claimed-capability features per [§Capability matrix](#capability-matrix) — a port that doesn't claim a capability may reject the corresponding keys at registration time with `:rf.error/machine-grammar-not-in-v1`.

`:entry` and `:exit` are **single fns or single keyword references into the machine's `:actions` map** — never vectors. To run multiple actions on entry, write a fn that calls them in order (or name a compound entry in the machine's `:actions` map; the named id is richer for tooling).

`:invoke` is **declarative sugar** that `create-machine-handler` desugars into entry/exit `:spawn` / `:destroy-machine` fx at registration time; per-state at most one `:invoke`. See [§Declarative `:invoke` (sugar over spawn)](#declarative-invoke-sugar-over-spawn) for the spec-spec keys, desugaring rules, composition with explicit `:entry` / `:exit`, and the deliberate omissions vs xstate.

### Transitions

A transition spec for `:on` may be:

```clojure
;; minimal — just a target
{:on {:right-click-circle :editing}}

;; with guard and action
{:on {:right-click-circle
      {:target :editing
       :guard  :circle-exists?
       :action (fn [_ [_ id radius]]
                 {:data {:circle-id id :initial-radius radius :preview-radius radius}})}}}

;; multiple candidates with guards (first matching wins)
{:on {:submit
      [{:target :rate-limited :guard :over-limit?}
       {:target :validating   :guard :email-valid?}
       {:target :rejected}]}}                        ;; fallthrough
```

Transition slots:

| slot | shape | when it runs |
|---|---|---|
| `:target` | FSM-keyword (or `:same-state` for an external self-transition; omit for internal) | discriminates the next state |
| `:guard` | one fn or one keyword reference (resolves into the machine's `:guards` map) | predicate; transition fires only if truthy |
| `:action` | one fn or one keyword reference (resolves into the machine's `:actions` map) | between exit and entry |
| `:meta` | map | tooling-visible, no runtime effect |

`:action` is **singular** — one fn or one keyword reference. Multiple steps compose inside the fn body or as a named compound entry in the machine's `:actions` map.

### Wildcard transitions

`:on` accepts the wildcard key `:*`, matching any event the state does not otherwise handle:

```clojure
{:idle      {:on {:start :running
                  :*     :error}}        ;; any other event drops to :error

 :listening {:on {:msg/data {:action (fn [_ ev] {:data {:last ev}})}
                  :*        {:action (fn [_ ev] {:fx [[:log/unknown ev]]})}}}}
```

Precedence inside the standard transition lookup:

1. State-local explicit event match.
2. State-local `:*` wildcard.
3. Top-level (machine root) explicit match.
4. Top-level `:*` wildcard.

The wildcard fires after specific matches at the same level. If no transition matches at any level, the snapshot is unchanged and the runtime emits a single `:rf.warning/machine-unhandled-event` trace (see [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough) for the canonical name; consistent with the other `:rf.warning/machine-*` advisory categories).

### Self-transitions (external vs internal)

- `:target :same-state` — **external** self-transition. `:exit` of source and `:entry` of target both fire.
- Omit `:target` entirely — **internal** self-transition. The transition's `:action` runs; `:exit` and `:entry` do *not*.

Internal transitions are how to update `:data` without re-running entry/exit machinery.

### Guards

A guard is **`(fn [data event] boolean)`** — 2-arity is canonical. **One inline fn or one keyword reference into the machine's `:guards` map** — never a compound data form.

`data` is the snapshot's `:data` slot directly (a map). The fn never sees the snapshot wrapper; pulling `:data` from a snapshot is the runtime's job, not the user's. This keeps the 99% case monomorphic and stops `:keys [data]` boilerplate from spreading through the corpus.

```clojure
;; inline fn — data is the snapshot's :data slot, passed directly
:guard (fn [data [_ id]]
         (some? (:circle-id data)))

;; keyword reference — resolves to (get-in spec [:guards :circle-exists?])
:guard :circle-exists?

;; compound logic — write the fn
:guard (fn [data ev] (and (active? data ev) (under-quota? data ev)))

;; even better — declare a named compound in the machine's :guards map
(rf/create-machine-handler
  {:guards {:active-and-under-quota?
            (fn [data ev] (and (active? data ev) (under-quota? data ev)))}
   :states {... :on {... {:guard :active-and-under-quota?}}}})
;; the name carries semantic meaning that visualisers / AIs read.
```

#### 3-arity escape hatch — `:state` / `:meta` introspection

The 3-arity overload is **opt-in** by declaring a third parameter:

```clojure
:guard (fn [data event {:keys [state meta]}]
         ...)
```

`{:state :meta}` is the snapshot's introspection slot — the discrete state and any user `:meta`. Use it for the rare guard or action that needs to branch on the current state name (e.g. dispatch on `:state` itself rather than `:data`). The vast majority of guards and actions are state-blind and don't need the third arg; declaring it is the explicit signal to the runtime that introspection is wanted.

Implementations arity-detect the fn at call time: a fn that declares a fixed 3-arg invocation is called with `[data event {:state :meta}]`; everything else (the canonical 2-arity, plus variadic helpers like `(constantly true)`) is called with `[data event]`. The detection is structural — no metadata needed on the fn — so inline `(fn [data ev ctx] ...)` vs `(fn [data ev] ...)` is the only declaration the user makes.

Compound logic is expressed via function composition or as a named entry in the machine's `:guards` map — the name carries semantic content visualisers and AIs read. Resolution is machine-scoped per [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler); unresolved references fail registration with `:rf.error/machine-unresolved-guard`.

### Actions

An action is **`(fn [data event] effects)`** returning the `{:data :fx}` shape (or `nil`). 2-arity is canonical; 3-arity opt-in is the same `[data event {:state :meta}]` escape hatch as for guards. **One inline fn or one keyword reference into the machine's `:actions` map** — never a vector.

```clojure
;; inline — data is the snapshot's :data slot, passed directly
:action (fn [_ [_ id radius]]
          {:data {:circle-id id :initial-radius radius :preview-radius radius}})

;; keyword reference — resolves to (get-in spec [:actions :clear-form])
:action :clear-form

;; the body lives in the machine's :actions map:
(rf/create-machine-handler
  {:actions {:clear-form
             (fn [_ _]
               {:data {:circle-id nil :initial-radius nil :preview-radius nil}})}
   :states {... :on {... {:action :clear-form}}}})
```

Multiple steps in one action are **fn composition**, not a vector:

```clojure
:action (fn [data ev]
          (let [a (action-clear data ev)
                b (action-record-attempt data ev)]
            {:data (merge (:data a) (:data b))
             :fx   (into (:fx a []) (:fx b []))}))
```

If the composition is reused, name it in the machine's `:actions` map:

```clojure
(rf/create-machine-handler
  {:actions {:clear-and-record (fn [data ev] ...)}
   :states {... :on {... {:action :clear-and-record}}}})
```

This is the design rule from above: imperative composition is fns, not data DSLs; named entries in the machine's `:actions` map add semantic content visualisers and AIs can read. Resolution is machine-scoped per [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler); unresolved references fail registration with `:rf.error/machine-unresolved-action`. Cross-machine reuse: define a Clojure var and reference it from each machine's `:actions` map.

## Action effect map — `{:data :fx}`

Actions return:

```clojure
{:data {<merged data updates>}        ;; the machine's own slice
 :fx   [[<fx-id> <args>] ...]}        ;; standard re-frame fx vector
```

Two keys. **Symmetric with `reg-event-fx`'s `{:db :fx}`** — same shape, different scope. Both keys are optional. Returning `nil` means "no effects."

`:data` semantics: **merge with the existing data map** (last write wins on key collision). Explicit `nil` clears a key:

```clojure
{:data {:circle-id nil :initial-radius nil :preview-radius nil}}
```

When N action slots fire in one transition (`:exit` → `:action` → `:entry`), `:data` updates merge in slot order; `:fx` vectors concatenate left-to-right.

### `:raise`, `:spawn`, and `:destroy-machine` are reserved fx-ids inside `:fx`

Not separate top-level keys. The machine handler walks `:fx` left-to-right and routes by fx-id:

```clojure
{:fx [[:raise           [:event-1]]                                          ;; back into THIS machine, atomic, pre-commit
      [:raise           [:event-2]]
      [:spawn           {:machine-id :request/protocol
                         :on-spawn   (fn [data id] (assoc data :child id)) ;; how the parent records the new id
                         :start      [:begin]}]                              ;; child actor (see §Spawning)
      [:destroy-machine actor-id]                                            ;; tear down a spawned actor
      [:dispatch        [:other-machine [:notify]]]                          ;; standard re-frame :dispatch
      [:http            {...}]]}                                             ;; any other registered fx
```

Routing rules (per [§Drain semantics](#drain-semantics)):

- `[:raise <event-vec>]` — appended to the machine's local pre-commit raise-queue.
- `[:spawn <spawn-spec>]` — registers a new handler immediately; the new id is fed through the spec's `:on-spawn` to update `:data`; if `:start` is present, an event is queued to the new actor.
- `[:destroy-machine <actor-id>]` — runs the actor's `:exit` action, dissociates its snapshot at `[:rf/machines <actor-id>]`, and clears its event handler from the frame-local registry. Symmetric counterpart to `:spawn`. Used directly by user actions and emitted by the desugaring of `:invoke` on state exit.
- Any other `[fx-id args]` — forwarded to the standard `do-fx` for runtime processing.

Reserving `:raise`, `:spawn`, and `:destroy-machine` as machine-only fx-ids matches re-frame's existing reserved unqualified fx names (`:dispatch`, `:dispatch-later`). They are listed in [Conventions.md §Reserved fx-ids](Conventions.md#reserved-fx-ids).

## Strict encapsulation — actions only see their own data

A machine *almost never* needs to write `app-db` directly; it acts on its own state, raises to itself, spawns/messages other actors, or emits fx. The locked rule is **strict encapsulation**: actions and guards cannot see `app-db` at all — only `{:state :data}` plus the event vector.

> **Why this is locked.** Strict encapsulation is one of the named consequences of [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility). If actions could read or write `app-db` outside `[:rf/machines <id>]`, machine logic would create state changes that don't show up in any machine snapshot and don't roll back when the surrounding machine snapshot does. The whole machine's state has to live inside the frame's persistent value to revert with it; encapsulation is what stops machines from leaking state into parts of the value that aren't theirs.

- **Action signature:** `(fn [data event] effects)` — 2-arity canonical; 3-arity opt-in is `(fn [data event {:state :meta}] effects)`.
- **Guard signature:** `(fn [data event] boolean)` — 2-arity canonical; 3-arity opt-in is `(fn [data event {:state :meta}] boolean)`.
- **What the fn sees:** the snapshot's `:data` slot directly (a map). The full `{:state :data :meta}` snapshot is reachable only via the 3-arity opt-in. Never `app-db`; never cofx.

The impure plumbing (reading the snapshot from `app-db` at `[:rf/machines <id>]`, writing `:data` back as a `:db` write, lowering `:fx` / `:raise` / `:spawn` into standard re-frame effects) lives in the *handler boundary* — the fn returned by `create-machine-handler`. **Inside the boundary: pure. Outside: standard re-frame.**

**Cross-cutting reads via the event payload.** A view that needs to pass the circle's current radius into the editor includes it in the dispatch:

```clojure
(rf/dispatch [:drawer/editor [:right-click-circle id radius]])
```

Whoever fires the event has the data; they pass it. The machine never reaches outside its own `:data`.

**Cross-cutting writes via `:fx [[:dispatch ...]]`.** To touch a sibling slice in `app-db`, an action dispatches a named event:

```clojure
:close-dialog
{:target :idle
 :action (fn [data _]
           {:fx   [[:dispatch [:drawer/apply-radius
                               (:circle-id data)
                               (:preview-radius data)]]]
            :data {:circle-id nil :initial-radius nil :preview-radius nil}})}
```

This forces every cross-encapsulation write to be a *named, traced, reusable event* rather than a quiet reach into someone else's data. Tracing shows the apply-radius event by name.

**Hard-disallow `:db`.** An action's effect map cannot contain `:db`. If one is present, the runtime emits the structured error `:rf.error/machine-action-wrote-db` and drops the `:db` key (the rest of the action's effects flow through). The error is registered as a category in [009 §Error contract](009-Instrumentation.md#error-contract).

**State-keyword is not in the action's return shape either** — only the transition's `:target` decides the next state. Actions cannot bypass the FSM.

## Path conventions in machine bodies

Every callback the user supplies inside a machine body — guards, actions, `:on-spawn` — operates on the snapshot's **`:data`** map directly, not on the wrapping snapshot:

| Slot | Signature | What it sees | What it returns |
|---|---|---|---|
| `:guard` | `(fn [data event] boolean)` | the `:data` map | a boolean |
| `:action` | `(fn [data event] effects)` | the `:data` map | `{:data ... :fx ...}` (or `nil`) |
| `:on-spawn` | `(fn [data spawned-id] new-data)` | the `:data` map | the new `:data` map |

The runtime is responsible for unwrapping the snapshot before calling these fns and for patching the result back into the snapshot. **User code never names `[:data ...]` paths inside the body**; if a callback needs to read or write a field, it does so on `data` directly (e.g. `(:pending data)`, `(assoc data :pending id)`).

The same principle holds for any data DSL the conformance corpus or a tooling layer interprets on top of the surface: a `:set` step inside a body operates on `:data`, so its path is data-relative. `[:set [:pending] x]` writes `data.:pending = x`. `[:set [:data :pending] x]` would write `data.:data.:pending = x`, which is virtually never what's wanted.

### 3-arity escape hatch — snapshot introspection

When a callback truly needs the discrete `:state` or any user `:meta` (rare), declare the third parameter:

- `:guard (fn [data event {:keys [state meta]}] ...)`
- `:action (fn [data event {:keys [state meta]}] ...)`

`:on-spawn` doesn't currently take an introspection slot — the snapshot's `:state` at spawn time is the entry-bearing leaf state by definition, so the slot would carry no information beyond the lexical position of the `:invoke`. If a future use case needs it, the same 3-arity opt-in pattern applies.

The 3-arity overload is **structurally detected** — implementations distinguish a fn that declares three fixed parameters from variadics like `(constantly true)`. No metadata is required on the fn.

## Registration — the machine IS the event handler

A machine is registered as **one event handler** via `reg-event-fx` whose body comes from `create-machine-handler`.

```clojure
(rf/reg-event-fx :drawer/editor
  {:doc "Modal-edit flow." :interceptors [undoable]}
  (rf/create-machine-handler
    {:initial :idle                                       ;; initial FSM-keyword
     :data    {:circle-id nil :initial-radius nil :preview-radius nil}
     :guards  {:circle-exists? (fn [data _] (some? (:circle-id data)))}
     :actions {:clear-error    (fn [_ _] {:data {:error nil}})}
     :states  { ... }}))
```

The `:guards` and `:actions` maps declare the machine's named guard / action implementations. Inside `:states`, a transition's `:guard :circle-exists?` resolves against this machine's `:guards` map; `:action :clear-error` resolves against `:actions`. **Each machine has its own guards/actions namespace** — there is no global `:machine-guard` / `:machine-action` registry. Inline fns remain first-class (`:guard (fn [...] ...)` skips the lookup).

Reference resolution:

- `:guard :form-valid?` → `(get-in spec [:guards :form-valid?])`.
- `:guard (fn [...] ...)` → inline fn, called directly.
- `:action :clear-error` → `(get-in spec [:actions :clear-error])`.
- `:action (fn [...] ...)` → inline fn, called directly.

`create-machine-handler` walks the transition table at construction time and verifies every keyword reference under a `:guard` or `:action` slot (in `:on`, `:always`, `:entry`, `:exit`) resolves to a key in the spec's `:guards` / `:actions` map. A miss fails registration with `:rf.error/machine-unresolved-guard` or `:rf.error/machine-unresolved-action` carrying `:tags {:guard-id <id> :machine-id <id>}` (or `:action-id`). This catches typos and undeclared references at registration time, not at runtime.

**Cross-machine reuse via Clojure vars.** When a guard or action is shared across machines, define it as a Clojure var and reference the var from each machine's `:guards` / `:actions` map:

```clojure
(defn user-authenticated? [data _]
  (some? (:user-id data)))

(rf/reg-event-fx :auth/login {}
  (rf/create-machine-handler
    {:guards {:authenticated? user-authenticated?}
     ...}))

(rf/reg-event-fx :settings/page {}
  (rf/create-machine-handler
    {:guards {:authenticated? user-authenticated?}
     ...}))
```

No framework support beyond ordinary Clojure var resolution. Each machine names the shared fn locally; the id is the meaning at the call site.

**Hot-reload.** Re-evaluating `(reg-event-fx :machine-id (create-machine-handler {:guards {...} :actions {...} ...}))` re-registers the handler with new `:guards` / `:actions` impls. Mounted snapshots survive (only the handler changes); the next dispatch uses the new bodies. Standard hot-reload story.

What this gives:

- **One id, one registration.** Reuses the `:event` registry kind. `(handlers :event)` enumerates every machine alongside every other event handler; `(handler-meta :event :drawer/editor)` carries `:rf/machine? true` so tooling can identify it. The snapshot's location in `app-db` is fixed and runtime-managed (see [§Where snapshots live](#where-snapshots-live)).
- **Standard dispatch.** `dispatch` and `dispatch-sync` route to a machine the same way they route to any handler.
- **Hot-reload.** Re-eval of the registration replaces the table; live snapshots pick up the new interpretation on their next event.
- **Reading the snapshot.** Views read the snapshot via the framework-shipped `:rf/machine` sub or its `sub-machine` wrapper — `@(rf/sub-machine :drawer/editor)` yields `{:state ... :data ...}` (or `nil` if not yet initialised). See [§Subscribing to machines via `sub-machine`](#subscribing-to-machines-via-sub-machine).

Sub-events are how the machine receives its inputs:

```clojure
(rf/dispatch [:drawer/editor [:right-click-circle id radius]])
(rf/dispatch [:drawer/editor [:close-dialog]])
```

The handler dispatches on the second-position keyword (`:right-click-circle`, `:close-dialog`); the rest of the inner vector is the event payload visible to actions and guards.

#### Extra-args fold

The dispatched outer vector MAY carry **additional elements after the inner event**:

```clojure
[:machine-id [:inner-event-id & inner-args] & extra-args]
```

When the runtime drains an event with this shape, **the extras are appended (folded) onto the inner event** before it's interpreted, producing:

```clojure
inner-event = [:inner-event-id <inner-args>... <extra-args>...]
```

This makes the standard fx-callback convention work without ceremony. Idiomatic uses:

- **HTTP callbacks.** A handler emits `:on-success [:machine-id [:inner-id]]` — a 2-element template. The fx implementation does `(rf/dispatch (conj on-success response))`, which conj-onto-the-outer produces `[:machine-id [:inner-id] response]`. The runtime folds the trailing `response` into the inner event, so the machine handler sees `[:inner-id response]` and the action's `[_ result]` destructure receives `result`.

- **Promise / future resolution patterns.** Same shape: the surrounding async layer captures a 2-element template, conj's the resolved value, dispatches.

- **Chained dispatches that carry payload.** Any callsite that wants to "ship a value into the machine" can use the `[:machine-id [:event-id] payload]` form rather than constructing the inner vector manually.

The fold only applies when the outer event has length ≥ 3 AND the second element is itself a vector. Length-2 dispatches (`[:machine-id [:inner-id]]`) and the legacy single-arg form (`[:machine-id]`) are unaffected. The runtime resolves the outer-shape ambiguity by inspecting the second element's type — a vector second element means "sub-event, fold extras"; anything else means "use the whole vector as the inner event" (compatibility fallback).

### `create-machine-handler` is a pure factory

The fn `create-machine-handler` returns is the event handler. Crucially, the factory itself:

- **Registers nothing.** No `reg-*` side effects at construction time.
- **Closes over no global state.** No `(get-machine-by-id ...)` lookups bound at construction.
- **Does not know its own event id.** The handler's id is bound by the surrounding `reg-event-fx` (or by `spawn-machine` for dynamic instances).

This is a real constraint on the implementation, not just a testing affordance — it's what makes the singleton vs spawned symmetry clean (the registration happens *outside* the factory in both cases) and what makes Level-2 testing (per [§Testing](#testing)) possible without a test frame.

## Design rule — data DSLs vs functions

> **Use data DSLs** for *deferred function calls* (`:fx [[fx-id args]]`), *named effects* (`:on-match [event]`), *declarative shape descriptions* (schemas, hiccup), and *static dependency declarations* (`:<-`, `:platforms`).
>
> **Use functions** for *imperative composition* and *predicate composition*.

Applied to machines: the transition table is a data DSL because it describes named transitions, target states, and references to registered fns — those are deferred function calls and static dependency declarations. Composing two actions ("clear the error *and* record the attempt") is imperative composition, so the action slot holds **one fn** (which can compose freely in code). Composing two predicates ("email valid *and* under retry limit") is predicate composition, so the guard slot holds **one fn or one registered id** — and a registered compound guard with a meaningful name (`:active-and-under-quota?`) carries more semantic information at the call site.

## Inspectability bias

> **Inspectability bias.** Machine tables should prefer **named guards and actions declared in the machine's `:guards` / `:actions` maps** over inline fns. The id (`:under-quota?`) carries semantic meaning that visualisers, AIs, and humans all read; an inline `(fn [data ev] ...)` is opaque to inspection. Inline fns are escape hatches for trivial logic (one-liners with no branching), not the default form.

The id is the meaning at the call site; the inline fn is opaque to readers. The machine-scoped resolution mechanics — how keyword references in transition slots resolve against the machine's `:guards` / `:actions` maps, and how cross-machine reuse via Clojure vars works — are specified once in [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler).

This is a normative rule on top of the [data-DSL-vs-fn](#design-rule--data-dsls-vs-functions) rule: *both* forms are first-class at the grammar level (`:guard` and `:action` accept a fn or a keyword reference), but the **default form is the named keyword reference**. When a transition's logic is more than a single non-branching expression, name it in the machine's `:guards` / `:actions` map.

Why the bias:

- **Visualisers read ids, not fn bodies.** A diagram exporter that renders the transition table can label an arrow with `:under-quota?` and have it mean something. An inline fn becomes "[fn]" — a hole in the rendered diagram.
- **AIs read ids, not fn bodies.** When an AI reasons about a machine — generating tests, proposing changes, explaining behaviour — a keyword reference is a stable name it can resolve against the machine's `:guards` / `:actions` map (visible via `(machine-meta <id>)`). An inline fn is a closure with no public name.
- **Humans read ids, not fn bodies.** A reviewer scanning a transition table sees `:guard :under-quota?` and knows what gates the transition; with `:guard (fn [data ev] ...)` they have to read the body to find out.
- **Tests read ids.** Level-1 (`machine-transition`) and Level-2 tests can stub or assert against named guards/actions by id — re-define the spec's `:guards` / `:actions` entry with a deterministic stand-in. Inline fns can only be replaced by re-writing the entire transition table.
- **Conformance fixtures read ids.** A fixture's expected `:fx` vector can name `[:dispatch [:audit/login-ok]]` against the action `:record-success` declared in the machine's `:actions` map; inline-fn equivalents are not addressable.

Inline fns remain acceptable for **trivial bodies that don't add meaning by being named** — e.g. `:guard (fn [data _] (some? (:circle-id data)))` is fine; naming it as `:has-circle?` may add no information beyond what the body already shows. The test is whether the fn body is a single non-branching expression: yes → inline is OK; no → name it in `:guards` / `:actions`.

Cross-references: [Construction-Prompts.md](Construction-Prompts.md) covers scaffolding guidance.

## What 002 already gives us (recap)

[002 §State machines are just event handlers](002-Frames.md) commits the following at the foundation level:

- **The machine IS the event handler.** A registered event handler whose body comes from `create-machine-handler` is the machine; machines register under the `:event` registry kind.
- **Three-way conceptual split:** definition (data), instance (snapshot at the runtime-managed `[:rf/machines <id>]`), frame (actor-system boundary).
- **Snapshot shape:** `{:state <fsm-keyword> :data <map>}`. `:state` is the discrete FSM keyword (`:idle`, `:editing`, ...); `:data` is the extended state — the machine's own private memory.
- **Pure transition contract:** `(machine-transition definition snapshot event) → [next-snapshot effects]`.
- **Pure factory:** `(create-machine-handler spec) → fn`. Returns a re-frame event-handler fn whose construction is a pure value transform of `spec` — its identity (the surrounding `reg-event-fx` id, or the `spawn-machine`-supplied id) is bound by the caller.
- **Definition shape:** transition table is pure data; guards/actions referenced by id or supplied as fns; both forms are first-class.
- **Inspection:** lifecycle/transition events emitted on the existing trace surface with `:source :machine`.
- **Composition:** ordinary `dispatch` between machines, made deterministic by drain semantics.
- **Discipline:** machines reuse the existing event registry, dispatch pipeline, and effect substrate; machine snapshots live as values in `app-db`.

This Spec describes everything else.

## Drain semantics

The two mechanisms (`:raise` and `:fx [:dispatch ...]`) compose at four nested levels. Each level has a single deterministic rule. Implementations must produce these orderings exactly.

### Level 1 — within a single action's effect map

An action returns `{:data ... :fx [...]}`. The two keys are independent:

- **`:data`** — a single map; merged into the current data map (last write wins on key collision).
- **`:fx`** — a vector of `[fx-id args]` pairs; processed **in vector order**. The machine handler's effect-map composer walks `:fx` and routes by fx-id:
  - `[:raise <event-vec>]` → appended to the local pre-commit raise-queue.
  - `[:spawn <spawn-spec>]` → registers a new handler immediately (each spawn happens before the next `:fx` entry is processed; the spawned id is fed through the spec's `:on-spawn` callback to update `:data`; if `:start` is present, an event is queued to the new actor).
  - any other `[fx-id args]` → forwarded to the standard `do-fx` for runtime processing.

The relative order of `:raise` entries in `:fx` is the order they enter the local raise-queue. The relative order of non-raise fx entries is the order they reach `do-fx`.

This Level-1 walk is the machine-layer instance of the runtime-wide [`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees) — `:db` (here, the lowered `:data` write) commits before any `:fx` entry, `:fx` entries process in source order, each entry's fx-handler returns before the next begins, and an fx-handler exception traces independently without halting subsequent entries. `:raise` and `:spawn` are reserved fx-ids routed locally before the rest reach `do-fx` (see [§`:raise` and `:spawn` are reserved fx-ids inside `:fx`](#raise-and-spawn-are-reserved-fx-ids-inside-fx)).

### Level 2 — across the action slots in one transition

A transition that fires runs an ordered sequence of action slots. **Flat machines** see at most three slots, in this fixed order:

1. **`:exit`** action of the source state (one fn or registered id).
2. **`:action`** on the transition itself (one fn or registered id).
3. **`:entry`** action of the target state (one fn or registered id).

Each slot is optional; absent slots are skipped.

**Compound machines** generalise the slot list along the LCA-cascade described in [§Hierarchical compound states §Entry/exit cascading](#entryexit-cascading-along-the-lca). Given source path A and target path B, with LCA L (longest common prefix):

1. **Exit cascade** — `:exit` actions of A's states from leaf back to (but not including) L. **Deepest-first.**
2. **Transition `:action`** — fires once at the LCA boundary.
3. **Entry cascade** — `:entry` actions of B's states from (the level just below) L down to leaf. **Shallowest-first.**
4. **Initial cascade** — if B's leaf is itself a compound state, descend its `:initial` chain; each cascaded state's `:entry` action fires shallowest-first as the path lengthens.

For a flat machine, A and B are length-1 paths, L is the root, and the four-step generalisation collapses to the three-slot exit → action → entry order above.

Self-transitions: if `:target` names the same state as the source, the transition is **external** — exit and entry fire. Omit `:target` entirely for **internal** — the transition's `:action` runs; no exit/entry, no cascade.

Composition across all action slots' returned effect maps:

- `:data` updates merge in slot order — exit-cascade (deepest-first) → action → entry-cascade (shallowest-first) → initial-cascade. Last write wins on key collision.
- `:fx` entries concatenate in slot order. Within the concatenated `:fx`, the Level-1 walk (`:raise` → local queue, `:spawn` → register, rest → `do-fx`) preserves order.

### Level 3 — within a single machine event

When a machine receives an event:

1. Resolve which transition fires (guards evaluated left-to-right; first match wins).
2. Run the action group (Level 2).
3. **Drain the raise-queue**: pop the front, dispatch it through the same machinery (Level 3 recursion), accumulate its `:fx` / `:spawn` into the same outer accumulator. Continue until the raise-queue is empty.
4. **Microstep loop — check `:always`**: inspect the current state node's (and, for hierarchical compounds, every entered ancestor's deepest-first) `:always` vector. If a guarded entry matches (first-match-wins), apply that transition (run its `:action`, update the in-flight snapshot, accumulate its `:fx`) — then **loop back to step 3** to drain any new `:raise` queue, then re-check `:always`. Continue until a fixed point is reached (no `:always` matches in the current state). See [§Eventless `:always` transitions](#eventless-always-transitions) for the full microstep semantics.
5. Commit the snapshot (state-keyword + merged data) to `app-db` at `[:rf/machines <id>]`, in a single `:db` write.
6. Emit the accumulated `:fx` as the event handler's return value, which the standard re-frame interceptor pipeline's `do-fx` then processes.

The whole machine event — including all raised sub-events **and all `:always` microsteps** — appears as **one logical step** (one **macrostep**) to the outside. The trace shows it as one `:rf.machine/transition` with the raise-cascade and microstep count in its `:tags`. xstate/SCXML macrostep semantics: external observers (subs, other machines, tools) see only the post-commit settled snapshot.

Bounded by `:raise-depth-limit` (default 16, exceeding emits `:rf.error/machine-raise-depth-exceeded`) and `:always-depth-limit` (default 16, exceeding emits `:rf.error/machine-always-depth-exceeded`). Both limits halt the cascade with the snapshot uncommitted; the recovery is `:no-recovery`.

### Level 4 — across the runtime

Standard re-frame. The router maintains a single FIFO queue:

- **Dispatched events go to the back.** Whether dispatched from user code, from `:fx [[:dispatch ...]]`, or from any other source.
- **The router drains in queue order.** No reordering, no priority lanes, no front-of-queue insertion.
- **Each dequeue runs to completion before the next.** A machine event's full Level-3 cascade (including raised sub-events and snapshot commit) finishes before the next runtime-queue event is processed.
- **`do-fx` runs after the handler returns and before the next dequeue** — so `:fx [[:dispatch :ev-X]]` emitted during event Y goes to the queue *after* anything Y queued earlier in its run, *before* the next dequeue.

### Worked walkthrough

```
;; user code:
(rf/dispatch [:M [:start]])
(rf/dispatch [:other-thing])

;; runtime queue: [[:M [:start]] [:other-thing]]

;; --- dequeue [:M [:start]] -----------------------------------
;; suppose M's :start transition has:
;;   :action (fn [_ _] {:fx [[:raise [:input1]]
;;                           [:raise [:input2]]
;;                           [:dispatch :ev-A]]})
;; and :input1's transition has :action (fn [_ _] {:fx [[:dispatch :ev-B]]})
;; and :input2's transition has :action (fn [_ _] {:data {:n 1}})

;;   1. apply :start's action; walk :fx left-to-right:
;;        [:raise [:input1]]  → local raise-queue: [[:input1]]
;;        [:raise [:input2]]  → local raise-queue: [[:input1] [:input2]]
;;        [:dispatch :ev-A]   → outgoing fx: [[:dispatch :ev-A]]
;;
;;   2. drain local raise-queue:
;;      pop :input1 → run its transition; its :fx [[:dispatch :ev-B]]
;;        outgoing fx: [[:dispatch :ev-A] [:dispatch :ev-B]]
;;      pop :input2 → run its transition; data merges in
;;
;;   3. commit snapshot to app-db (one :db write at [:rf/machines <id>])
;;
;;   4. emit outgoing fx → do-fx appends :ev-A, :ev-B to runtime queue
;;
;; runtime queue: [[:other-thing] [:ev-A] [:ev-B]]

;; --- dequeue [:other-thing] ----------------------------------
;;   ... runs, possibly dispatches more ...

;; --- dequeue [:ev-A] -----------------------------------------
;;   suppose its handler dispatches [:ev-C]
;;   runtime queue after: [[:ev-B] [:ev-C]]

;; --- dequeue [:ev-B] BEFORE :ev-C ----------------------------
;;   FIFO. :ev-B was queued before :ev-C.

;; --- dequeue [:ev-C] -----------------------------------------
;;   ...
```

### Why these rules

- **FIFO at the runtime layer** — matches actor-mailbox semantics across the whole literature; matches re-frame's existing drain; gives a single global event-order that's identical to the trace's `:dispatched-at` ordering. No reordering, no surprises.
- **Depth-first for `:raise`** — within a machine, transition-chaining (`a → b → c`) is the natural unit of work; collapsing it into one externally-observable step matches how authors think about FSMs.
- **Action / transition / event composition is left-to-right, in-spec-order** — readers of the transition table can compute the effect order by eye. No "actions can be reordered for optimisation"; the order in the source is the order at runtime.
- **Snapshot commit is atomic per machine event** — sub-events raised within a machine see the *evolving* data through the local raise-cascade, but external observers (subs, other machines, tools) only see the post-commit snapshot. This prevents partial-snapshot observation.
- **Bounded raise-depth** — protects against infinite `:raise` loops; emits a structured error.

These rules belong in the conformance corpus as fixtures that exercise each rule.

### Drain semantics gotchas

The four-level drain has a small number of recurring implementation mistakes. Each is observable as a deviation from the rules above; each has a single fix.

- **Implementing `:raise` as a runtime-FIFO append rather than a local pre-commit queue.** *What goes wrong:* the raised event lands at the *back* of the global router queue, behind other events queued in this turn — so external observers can interleave between the raise and its handling. *Instead:* keep a per-machine-event raise-queue inside the handler invocation; drain it depth-first before committing the snapshot, never via the runtime router.
- **Committing the snapshot before draining the raise queue.** *What goes wrong:* sub-events in the cascade observe their own *partial* snapshot (the post-action commit), not the evolving in-flight one — so a chained raise can re-fire a transition mid-cascade. *Instead:* the snapshot is committed *after* the raise queue is drained (Level 3 step 4), exactly once, atomically.
- **Conflating `:fx [:dispatch <self-id>]` with `:raise`.** They have different ordering semantics: `:dispatch` to self goes to the *back of the runtime FIFO*, runs after every other already-queued event, and runs against the *post-commit* snapshot. `:raise` runs *before* commit, depth-first, in the same logical step. *Instead:* use `:raise` for transition-chaining intended to settle inside one externally-observable step; use `[:dispatch [<self-id> ...]]` only when the round-trip through the runtime queue is what you actually want.
- **Not bounding raise-depth.** *What goes wrong:* a buggy `a → raise b → raise a → ...` cycle hangs the runtime. *Instead:* enforce the default depth-16 limit and emit `:rf.error/machine-raise-depth-exceeded` when it's hit; halt the cascade and surface the path.
- **Treating "self-transition with `:target`" as internal.** It is **external** — `:exit` of source and `:entry` of target both fire (because the transition crosses the state-node boundary, even though source and target are the same keyword). *Instead:* use `:target :same-state` only when you want exit/entry to fire.
- **Treating "transition without `:target`" as external.** It is **internal** — neither `:exit` nor `:entry` fires; only the transition's `:action` runs. *Instead:* omit `:target` only when you want a pure data update with no exit/entry machinery; if you want exit/entry, name the target.
- **Forgetting to cascade `:initial` when entering a compound state via vector target.** *What goes wrong:* a transition with `:target [:authenticated]` (a compound state) lands the snapshot at the compound itself instead of descending — the snapshot's `:state` is `[:authenticated]` rather than `[:authenticated :dashboard]`, and downstream code that walks to a leaf gets confused. *Instead:* every vector target whose final segment names a compound state continues to cascade the compound's `:initial` chain until it hits a leaf; the snapshot's `:state` is always a leaf path. Each cascaded state's `:entry` fires shallowest-first.
- **Resolving keyword `:target` against the runtime's current path instead of the declaring state.** *What goes wrong:* a transition `{:target :browsing}` declared on a parent state is resolved as "the current state's sibling" rather than "this declaration's parent's child." Across deeply-nested machines the two diverge. *Instead:* keyword targets are **statically resolved** against the parent of the state-node that owns the `:on` map; the resolution is a property of the transition table, not of the snapshot. When in doubt, use the vector form — it is unambiguous.
- **Referencing an undeclared guard or action id.** *What goes wrong:* a transition slot `:guard :form-valid?` (or `:action :clear-error`) names a keyword that does not appear in the machine's `:guards` (or `:actions`) map — a typo, a stale reference left over after a rename, or a copy-paste from another machine's spec. *Instead:* `create-machine-handler` walks every `:guard` / `:action` slot at construction time (in `:on`, `:always`, `:entry`, `:exit`) and verifies each keyword reference resolves against the machine-local `:guards` / `:actions` map. Misses surface as `:rf.error/machine-unresolved-guard` or `:rf.error/machine-unresolved-action` at registration time, with `:tags {:guard-id <id> :machine-id <id>}` (or `:action-id`). The error fires before any snapshot is created — caught at registration, not at runtime.
- **Unbounded `:always` cycle.** *What goes wrong:* `:a` has `:always {:guard :p? :target :b}` and `:b` has `:always {:guard :q? :target :a}`; both guards remain true and the microstep loop never reaches a fixed point. The handler hangs the runtime. *Instead:* enforce the default depth-16 limit on the microstep loop and emit `:rf.error/machine-always-depth-exceeded` when it's hit; halt the cascade with the snapshot uncommitted, and surface the visited path. Gotcha is the same shape as the `:raise` cycle gotcha above — a separate counter, the same recovery pattern.
- **`:always` self-loop accepted at registration.** *What goes wrong:* a state declares `{:always [{:guard :ok? :target :same-state}]}` (or `{:target <itself>}` with the same guard reference). The first microstep matches; the next microstep matches again on the same state and same guard; the loop runs to depth-limit and aborts. The author meant a no-op or got the topology wrong. *Instead:* `create-machine-handler` rejects any `:always` entry whose `:target` resolves to the declaring state itself **with the same `:guard` reference** (or no guard) at registration time, surfacing `:rf.error/machine-always-self-loop` with `:tags {:state <state-keyword> :machine-id <id>}`. The error fires before any snapshot is created — caught at registration, not at runtime. (A self-targeting `:always` with a *different* guard, used as a re-entry on a changed condition, is permitted; only the same-guard case is rejected.)
- **Forgetting to advance the `:after` epoch on state entry.** *What goes wrong:* a machine handler schedules a fresh `:after` timer at state entry but reuses the *prior* visit's epoch counter — so an in-flight timer from the previous visit fires with a matching epoch and triggers an unintended transition long after the user moved on. Symptom: "a timer fired that I thought was cancelled." *Instead:* `:rf/after-epoch` advances **on every state entry** (per [§Delayed `:after` transitions §Epoch-based stale detection](#epoch-based-stale-detection)); each scheduled timer carries the post-increment epoch; the receiving handler validates the epoch before firing. There is no cancellation — staleness is the cancellation mechanism.
- **Scheduling `:after` timers under SSR.** *What goes wrong:* a machine entered server-side schedules a `:dispatch-later` for a 5-second timeout; the SSR render captures the timer-handle but the request-frame is destroyed before it fires; the timer leaks (or fires against a destroyed frame). Symptom: stray events on the server, or hydration mismatches because the server's snapshot includes "scheduled timer" state the client doesn't share. *Instead:* `:after` no-ops in SSR mode (per [§SSR mode](#ssr-mode) and [011-SSR §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr)); the entry action skips timer scheduling on the server, and the client schedules them after hydration.

These gotchas are also worth fixturising in the conformance corpus — each one is a single-fixture assertion against a specific deviation.

## Hierarchical compound states

A state node may itself contain a `:states` map — making it a **compound state** with its own substates. The grammar recurses: a substate may be a leaf (no `:states`) or another compound (has `:states`, must declare `:initial`). This extends the flat grammar additively; flat machines stay flat.

> **Why compound states exist.** They factor common transitions out to a parent, so every authenticated descendant inherits `:logout` without restating it. The runtime walks from the active leaf up to root looking for a matching transition — this is the **deepest-wins with parent fallthrough** rule documented below.

### Snapshot shape with hierarchy

For a compound machine, the snapshot's `:state` is a **vector path** from root to the active leaf:

```clojure
{:state [:authenticated :cart :browsing]
 :data  {...}}
```

A flat machine's `:state` remains a single keyword (`:idle`); see [§Snapshot shape](#snapshot-shape) for the dual form. A single-keyword `:K` read against a hierarchical definition is treated as the path `[:K]` (which must name a leaf at the root level).

### Initial-state cascading

Every compound state-node MUST declare `:initial` — the substate to enter when control reaches the compound state without a deeper target. Entering a compound state **cascades down its `:initial` chain** until it reaches a leaf:

```clojure
{:initial :authenticated
 :states
 {:authenticated
  {:initial :dashboard            ;; required because :authenticated is compound
   :states {:dashboard {...}
            :cart      {:initial  :browsing
                        :states {:browsing  {...}
                                 :paying    {...}
                                 :confirmed {...}}}}}}}
```

Targeting `[:authenticated]` lands the snapshot at `[:authenticated :dashboard]`; targeting `[:authenticated :cart]` lands at `[:authenticated :cart :browsing]`. Each cascaded state's `:entry` action fires in shallowest-first order as the path lengthens (see [§Entry/exit cascading](#entryexit-cascading-along-the-lca)).

A compound state without `:initial` is a registration error — emits `:rf.error/machine-compound-state-missing-initial` at registration time and, in the CLJS reference with schema validation enabled, fails the registration.

### Target resolution — vector vs keyword

A transition's `:target` admits **two forms**:

- **Vector form — absolute path from root.** `:target [:authenticated :cart]` always means the named root-level state's named substate, regardless of where the transition is declared. Vector targets are unambiguous and the recommended form for cross-level transitions.
- **Keyword form — relative to the state where the transition is declared.** `:target :review` resolves to a sibling of the current declaring state. The runtime resolves the keyword against the declaring state's *parent's* `:states` map. **Note:** "declaring state" is the state-node owning the `:on` map — not the state the machine happens to be in at runtime. This is a static resolution rule, evaluable from the transition table alone without consulting the snapshot.

Existing flat-machine targets (`:target :editing`) are keyword form, root-relative — unchanged from before, because in a flat machine the declaring state's parent is the root.

A target naming a compound state implicitly cascades through its `:initial` chain (see above). To target a specific leaf inside a compound, use the vector form: `:target [:cart :paying]`.

### Entry/exit cascading along the LCA

When the snapshot transitions from path A to path B, the runtime walks both paths and computes the **LCA** (longest common prefix). Three boundaries fire, in this order:

1. **Exit cascade.** Walk A from leaf back toward LCA, firing each state's `:exit` action — **deepest-first**. Stop at LCA exclusive (LCA itself does not exit; we are not leaving it).
2. **Transition `:action`.** Runs once at the LCA boundary, between exit and entry.
3. **Entry cascade.** Walk B from (the level just below) LCA down to leaf, firing each state's `:entry` action — **shallowest-first**. If B's leaf is itself a compound state, continue cascading via its `:initial` chain; the cascaded states' `:entry` actions fire as the path extends.

This is a generalisation of the flat exit → action → entry rule (where path length is 1 and LCA is the root).

### Transition resolution — deepest-wins with parent fallthrough

To resolve an event, the runtime walks the active path from **leaf up to root**, looking for the first state-node whose `:on` map handles the event. The first match wins:

1. Leaf state's `:on` — explicit match.
2. Leaf state's `:on` — `:*` wildcard.
3. Parent state's `:on` — explicit match.
4. Parent state's `:on` — `:*` wildcard.
5. ... continue walking up ...
6. Top-level (root) `:on` — explicit match.
7. Top-level `:on` — `:*` wildcard.

If no level matches, the snapshot is unchanged and the runtime emits `:rf.warning/machine-unhandled-event` (see [009](009-Instrumentation.md)). This is the canonical name; older drafts used `:rf.machine.event/unhandled` — that form is superseded.

The deepest-wins rule means a child state can **override** a parent's transition for the same event by declaring its own. Combined with parent fallthrough, this is how hierarchy factors common behaviour to the parent (every authenticated descendant inherits `:logout`) while still allowing local override.

### Worked example — auth flow

```clojure
{:initial :unauthenticated
 :states
 {:unauthenticated
  {:on {:login [:authenticated]}}        ;; vector :target — absolute from root

  :authenticated
  {:initial :dashboard
   :on      {:logout [:unauthenticated]} ;; common — every authenticated descendant inherits
   :states
   {:dashboard
    {:on {:open-settings :settings        ;; keyword :target — sibling of :dashboard
          :open-cart     :cart}}
    :settings
    {:on {:close :dashboard}}
    :cart
    {:initial :browsing
     :on      {:close :dashboard}
     :states
     {:browsing  {:on {:checkout :paying}}
      :paying    {:on {:success   :confirmed
                       :failure   :browsing}}
      :confirmed {}}}}}}}
```

| Event | Source path | Target path | Notes |
|---|---|---|---|
| `:login` | `[:unauthenticated]` | `[:authenticated :dashboard]` | Target `[:authenticated]` cascades `:initial :dashboard`. |
| `:open-cart` | `[:authenticated :dashboard]` | `[:authenticated :cart :browsing]` | Keyword `:cart` resolves as sibling of `:dashboard`; cascades `:initial :browsing`. |
| `:checkout` | `[:authenticated :cart :browsing]` | `[:authenticated :cart :paying]` | Keyword `:paying` is sibling of `:browsing` inside `:cart`. |
| `:logout` | `[:authenticated :cart :paying]` | `[:unauthenticated]` | Deepest-wins walks `:paying` (no match), `:cart` (no match), `:authenticated` (match). Vector target is absolute. Exit cascade: `:paying` → `:cart` → `:authenticated`. |

For the `:logout` row, the LCA of `[:authenticated :cart :paying]` and `[:unauthenticated]` is the root, so the exit cascade runs every level of the source path; the entry cascade enters just `:unauthenticated`.

### Capability scope

Hierarchical compound states are claimed by the v1 CLJS reference per [§Capability matrix](#capability-matrix). What hierarchy gives you here:

- Nested `:states` and `:initial` cascading.
- Vector and keyword `:target` forms.
- LCA-based entry/exit cascading.
- Deepest-wins transition resolution with parent fallthrough.

Out of scope (see [§Substitutes for skipped features](#substitutes-for-skipped-features)):

- **Parallel regions** — substitute: separate machines per region.
- **History pseudo-states** — substitute: snapshot-as-value capture.
- **`onDone` final-state notification** — substitute: explicit `[:raise ...]` from the leaf state's `:entry`.

`:always`, `:after`, and `:invoke` are all specified independently of the hierarchy work above (see [§Eventless `:always` transitions](#eventless-always-transitions), [§Delayed `:after` transitions](#delayed-after-transitions), and [§Declarative `:invoke` (sugar over spawn)](#declarative-invoke-sugar-over-spawn)). All three are state-node keys whose semantics compose with the hierarchical entry/exit cascade described above.

## Eventless `:always` transitions

An `:always` transition fires automatically when its guard becomes true — no event needed. xstate/SCXML term: **transient** or **eventless** transition. The pattern handles "the snapshot just changed; if condition X is now true, immediately move to state Y" without the author having to manually `:raise` a synthetic event from every action that could enable the condition.

`:always` is a **state-node key** (alongside `:on`, `:entry`, `:exit`, `:invoke`) holding a vector of guarded transition specs. Checked **after entry** (or after any transition that lands in this state). First matching guard wins; subsequent entries in the vector are not evaluated.

```clojure
{:checking-form
 {:always [{:guard :form-valid?   :target :submitting}
           {:guard :form-invalid? :target :show-errors}]
  :on     {...}}}
```

### Microstep loop within drain

`:always` extends Level 3 of [§Drain semantics](#drain-semantics) with a microstep cascade. Within a single machine event:

1. Apply the resolving transition (action + target).
2. Drain the `:raise` queue (depth-first, as before).
3. **Check `:always` of the current state.** If a guarded entry matches (first-match-wins), apply that transition (action + target), accumulating its `:fx`; loop back to step 2 to drain any new `:raise` queue, then re-check `:always`.
4. **Fixed point reached** when no `:always` entry in the current state matches. Commit the snapshot.
5. Emit accumulated `:fx`.

The whole cascade — initial transition, raise drain, every microstep, every microstep's raise drain — commits **once, atomically**. External observers see only the final settled state. This is xstate/SCXML **macrostep** semantics: the externally-observable transition is the fixed point of the microstep loop.

### Order with `:raise`

Within a single microstep, **drain `:raise` first, then check `:always`**. The combined macrostep is the fixed point of `(:raise drain + :always check)`. Rationale: `:raise` is **explicit** transition-chaining the author wrote; `:always` is an **implicit** consequence of the resulting state. Authors expect the explicit chain to settle before the implicit check fires.

`:raise` semantics within a single transition are unchanged — only the macrostep envelope grows.

### Bounded depth

Default microstep depth limit: **16** (matching `:raise`-depth's default). User-configurable at frame-config level (`:always-depth-limit`). Exceeding the limit:

- Emits `:rf.error/machine-always-depth-exceeded` with `:tags {:machine-id <id> :depth <limit> :path [<state> <state> ...]}`.
- Halts the cascade with the snapshot **uncommitted** — external observers do not see the partial path.
- Recovery: `:no-recovery` (the runtime cannot guess the author's intent for a non-converging cycle).

The depth counter is **separate** from the `:raise` depth counter — a microstep that itself raises events does not double-count. The two limits compose: each microstep can raise up to 16 events, and the macrostep can include up to 16 microsteps.

### Hierarchy interaction

When the cascade enters a **compound state**, `:always` is checked at every entered level, **deepest-first**. This matches xstate/SCXML and the existing entry-cascade order ([§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca)) — the leaf has the most specific knowledge of its own validity, so it gets first chance to redirect.

A match at any level resolves the microstep and the loop returns to step 2.

### Self-loop forbidden at registration

A state whose `:always` targets itself with the same `:guard` reference (or no guard) is **rejected at registration time**:

```clojure
{:checking
 {:always [{:guard :ready? :target :checking}]}}    ;; rejected
```

`create-machine-handler` walks every `:always` entry at construction time and surfaces `:rf.error/machine-always-self-loop` with `:tags {:state <state-keyword> :machine-id <id>}` for any same-state same-guard entry. Rationale: the loop either fires repeatedly to depth-exceeded (if the guard remains true) or is a no-op (if the guard flips on the first hit) — in both cases the author intended something else. Catch the typo at registration; surface the topology bug.

A self-targeting `:always` with a **different** guard — used as a re-entry on a changed condition — is permitted. Only the same-guard same-target case is rejected.

### Trace events

The runtime emits trace events at **two levels**, so tools can subscribe at the granularity they need:

- **Per-microstep** `:rf.machine.microstep/transition` — one event per microstep with `:tags {:machine-id <id> :from <state> :to <state> :microstep-index <n>}`. Tools that want to see the inner cascade (visualisers, debuggers) consume these.
- **Outer macrostep** `:rf.machine/transition` — the existing event, augmented with `:tags { ... :microsteps <count>}` carrying the total number of microsteps in the macrostep. Tools that want only externally-observable transitions (UI inspectors, replay panels) consume this and ignore the per-microstep stream.

Both levels are emitted unconditionally; consumers filter.

### Guard references

Guards in `:always` resolve against the **machine's `:guards` map** (per [§Registration](#registration--the-machine-is-the-event-handler) and the machine-scoped lock per [§Resolved decisions](#globally-registered-guardsactions-vs-machine-scoped-resolved)). There is no separate registry, no global lookup. `create-machine-handler` walks every `:always` entry's `:guard` slot at registration time and verifies the keyword resolves; misses surface as `:rf.error/machine-unresolved-guard` exactly as for `:on` transitions.

### Worked example — quiz

```clojure
{:initial :asking
 :guards  {:enough-correct? (fn [data _] (>= (:correct-count data) 10))}
 :actions {:count-correct   (fn [_ _] {:data {:correct-count inc}})
           :count-wrong     (fn [_ _] {:data {:wrong-count inc}})}
 :states
 {:asking
  {:always [{:guard :enough-correct? :target :winner}]
   :on    {:answer-correct  {:action :count-correct}
           :answer-wrong    {:action :count-wrong :target :loser}}}
  :winner {...}
  :loser  {...}}}
```

Walkthrough: when the user dispatches `[:quiz [:answer-correct]]`, the machine's macrostep runs:

1. `:asking`'s `:answer-correct` transition fires; `:count-correct` increments `:correct-count` (no `:target`, internal transition — the snapshot stays at `:asking`).
2. Microstep check: `:asking`'s `:always` evaluates `:enough-correct?`. If `:correct-count` is now ≥ 10, the guard is true; the microstep transitions to `:winner`.
3. Fixed point: `:winner`'s `:always` (if any) is checked; assume it has none. Commit.
4. Trace surface: one outer `:rf.machine/transition` (`:asking` → `:winner`, `:microsteps 1`) plus one per-microstep `:rf.machine.microstep/transition`.

External observers see `:asking` → `:winner`. The "answer counted, still asking" intermediate state is invisible — exactly the property `:always` exists to provide.

### What `:always` is *not*

- **Not a mid-transition slot.** `:always` lives only on a state node (alongside `:on`, `:entry`, `:exit`); it is not a key inside a transition spec. The microstep loop is the cascade mechanism — there is no "always after this action."
- **Not on the root machine.** `:always` is a state-node key; the root has `:initial` as its cascade entry-point. (A root-level "fire as soon as the machine starts" need is met by `:initial` cascading into a leaf whose `:always` fires.)
- **Not allowed as a same-state same-guard self-loop** (see above) — registration error.
- **Not a substitute for `:after`.** `:after` is for **time-delayed** transitions; `:always` fires immediately on guard truth. They are independent capabilities; see [§Delayed `:after` transitions](#delayed-after-transitions) for the full delayed-transition semantics. Both can co-exist on the same state node — they are independent slots.

## Delayed `:after` transitions

An `:after` transition fires after a specified time delay, no event needed. xstate/SCXML term: **delayed transition**. The pattern handles "after N milliseconds in this state, time out" without the author having to wire a `:dispatch-later` from `:entry` and a matching `:cancel-dispatch-later` on every other transition out of the state.

`:after` is a **state-node key** (alongside `:on`, `:entry`, `:exit`, `:always`, `:invoke`) holding a map of `ms → transition-spec`. Each entry runs an **independent** timer; on expiry, the corresponding transition fires (subject to its `:guard`). Entering the state schedules every entry's timer; exiting the state advances an epoch counter so in-flight timers from the prior visit are detected as stale and silently ignored.

```clojure
{:loading
 {:after {5000  :timeout
          30000 {:guard :still-loading? :target :hard-error}}
  :on    {:loaded :ready
          :failed :error}}}
```

### Value shape

Each `:after` map entry is `<delay> → <transition-spec>`. Both halves admit two forms:

**Delay (the key):**

- `pos-int?` — literal milliseconds, computed at registration time.
- `(fn [snapshot] ms)` — fn-valued delay, called once at state entry against the entering snapshot. Useful for parameterised timing: `{(fn [snap] (* 1000 (:retry-count (:data snap)))) :retry}`.

**Transition spec (the value):**

- `:keyword` — sugar for `{:target <keyword>}`. The simple "fire after N ms; transition to state X" case.
- `:rf/transition` map — full transition spec with the same shape as an `:on` slot: `{:guard <guard-ref> :target <target> :action <action-ref> :meta <map>}`. Guards resolve **machine-locally** against the spec's `:guards` map, exactly as for `:on` and `:always`. If `:guard` is present and evaluates false at timer expiry, the transition is suppressed.

Sugar normalises at registration time: `{5000 :timeout}` is equivalent to `{5000 {:target :timeout}}`. The runtime sees the desugared form.

### Epoch-based stale detection

> **Cross-cutting pattern.** This is one instance of the **stale-detection pattern** re-frame2 uses for any async-shaped feature where the receiving state's identity matters. See [Pattern-StaleDetection.md](Pattern-StaleDetection.md) for the meta-pattern; the same idiom is used by [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression) and is the recommended default for future async-shaped substrates. Trace events follow the `<feature>/stale-<reason>` convention.

Re-frame2 does **not** introduce a `:cancel-dispatch-later` fx. Cancellation is unnecessary because every scheduled timer carries an **epoch** captured at scheduling time, and the receiving handler validates the epoch before firing.

The mechanism:

1. The machine handler maintains a per-machine **epoch counter** in `:data` under the reserved key `:rf/after-epoch` (an int, initialised to 0 on first state entry). The `:rf/`-namespace inside `:data` is reserved for runtime-managed keys; user code does not write under it.
2. **On state entry**, the handler increments the epoch and, for each `:after` entry, schedules a `:dispatch-later` carrying the synthetic event `[<machine-id> [::after-elapsed <delay-ms> <epoch>]]`. The exact event shape is implementation-internal; what's contractual is that the epoch travels with the timer.
3. **On timer expiry**, the machine handler receives the synthetic event and compares the carried epoch against the current `:rf/after-epoch` in `:data`.
   - **Match** — the timer is "live"; the handler resolves the transition (subject to `:guard`) and fires it through the normal Level 3 drain.
   - **Mismatch** — the timer is "stale"; the handler silently ignores it and emits a `:rf.machine.timer/stale-after` trace event with `:tags {:machine-id <id> :state <state> :delay <ms> :scheduled-epoch <e1> :current-epoch <e2>}`.
4. **On state exit** (any transition that lands the snapshot in a different state, including cross-level transitions per [§Hierarchical compound states](#hierarchical-compound-states)), the epoch counter advances. In-flight timers from the prior visit will all see a mismatch.

The epoch is **per-machine**, not per-state. Any state exit advances it once; multiple `:after` timers from the just-exited state all see the same mismatch on the same advanced counter. Re-entering the same state increments the counter again — timers from this *new* visit have a fresh epoch.

### Drain semantics interaction

`:after` does **not** introduce a new microstep loop. The synthetic timer-elapsed event lands in the standard runtime FIFO via `:dispatch-later`'s normal path, and when it dequeues, the machine handler treats it as an ordinary event:

1. Resolve the synthetic event to its declared transition (via the state's `:after` map, indexed by the carried delay).
2. Validate the epoch (above). On mismatch, emit `:rf.machine.timer/stale-after` and stop — no transition runs.
3. On match, evaluate `:guard` (if any). On false, the transition is suppressed and a `:rf.machine.timer/fired` trace is still emitted (with `:fired? false`); the snapshot is unchanged.
4. On match-and-guard-pass, run the transition through the standard Level 2 (exit / action / entry) and Level 3 (drain `:raise`, check `:always`) cascade. The transition exit advances the epoch; sibling `:after` timers from the just-exited state will all be stale by the time they fire.

`:after` is a **deferred event source**, not a new layer in the drain hierarchy. Per [§Drain semantics](#drain-semantics), it composes with `:raise` (which queues *before* commit) and `:dispatch` (which queues at the runtime layer) without changing their orderings: the timer-elapsed event arrives at the back of the runtime FIFO, no different from any other `:dispatch-later`.

### Hierarchy interaction

`:after` on a parent state remains active while the snapshot is in any child of that parent. Multiple `:after` timers can be in flight simultaneously across hierarchy levels — a parent's 30-second hard-timeout ticks alongside a child's 5-second progress timeout.

Per [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca), the epoch counter advances on **any** state exit, whether the exit is a leaf-only transition or a multi-level cascade. A leaf-to-sibling transition under the same parent does not exit the parent, so the parent's `:after` timers stay live; a transition that exits the parent advances the epoch and all of the parent's pending `:after` timers go stale on next firing.

**Implementation note:** the epoch is per-machine, not per-level. A leaf-only sibling-transition advances the epoch even though the parent's state is unchanged — but that's fine: the parent's `:after` was scheduled *before* the leaf transition, and re-entry of the leaf doesn't re-schedule the parent's timers. To keep parent timers live across leaf transitions, implementations track which `:after` entries belong to which level on the path and only re-schedule the level(s) that the cascade newly enters. The contract is *external* — "parent `:after` outlives sibling-leaf transitions" — and `create-machine-handler` is responsible for upholding it.

### Multiple `:after` per state

All entries in an `:after` map run **independently**. Whichever timer fires first (and matches its `:guard`) triggers its transition; the resulting state exit advances the epoch and the remaining timers all go stale. Order between simultaneously-firing timers is implementation-defined — authors should not rely on tie-break behaviour for two timers with the same delay.

```clojure
:loading
{:after {5000  :timeout                                    ;; first checkpoint
         30000 {:guard :still-loading? :target :hard-error}} ;; final checkpoint
 :on    {:loaded :ready
         :failed :error}}
```

If `:loaded` or `:failed` arrives before 5s, the machine transitions out of `:loading`; both timers go stale. If neither arrives by 5s, the 5000ms timer fires; the machine transitions to `:timeout`; the 30000ms timer's eventual firing is stale.

### SSR mode

`:after` **no-ops in SSR mode** — entry actions do not schedule timers, and the synthetic timer-elapsed event is never emitted. The server renders the current `:state` statically and the client hydrates that state without timer artefacts. See [011-SSR §`:after` is no-op under SSR](011-SSR.md#after-is-no-op-under-ssr) for the SSR-side rule.

This is consistent with `:platforms` gating on `reg-fx` (per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server)): timer scheduling is conceptually a `:client`-only concern. The first client render after hydration can re-fire entry actions to begin scheduling, depending on the implementation's hydration policy — the spec leaves the hydration-handoff timing to the host so long as the snapshot value is preserved.

### Clock abstraction

The clock primitives live in **`re-frame.interop`** — the existing clj/cljs-split interop layer that already houses platform-dependent atoms, `next-tick`, etc. Three primitives:

- `re-frame.interop/now-ms` — host-clock current time in milliseconds (a long).
- `re-frame.interop/schedule-after!` — host-clock `setTimeout`-equivalent. Returns an opaque handle.
- `re-frame.interop/cancel-scheduled!` — best-effort cancellation given the handle. Optional; epoch-based stale-detection makes cancellation an optimisation, not a correctness requirement.

The CLJS realisation uses `js/Date.now` and `js/setTimeout` / `js/clearTimeout`. The JVM realisation uses `System/currentTimeMillis` and a `ScheduledExecutorService`. Tests swap the interop layer using existing fixture patterns — there is **no new framework-level clock-configuration API**; the substitution happens at the namespace level (`with-redefs` in tests, alternative interop ns alias in conformance harnesses). If `:after` is exercised on a host whose interop layer hasn't been wired with a clock, the runtime emits `:rf.warning/no-clock-configured` (an advisory; the host falls back to a host-native clock if available).

### Trace events

The runtime emits three trace events around every `:after`:

- **`:rf.machine.timer/scheduled`** — emitted when a timer is scheduled at state entry. `:tags {:machine-id <id> :state <state> :delay <ms> :epoch <e>}`. One event per `:after` entry per state entry.
- **`:rf.machine.timer/fired`** — emitted when a live (epoch-matching) timer's transition resolves. `:tags {:machine-id <id> :state <state> :delay <ms> :epoch <e> :fired? <bool>}`. `:fired? false` indicates the guard was checked and returned false; the transition was suppressed.
- **`:rf.machine.timer/stale-after`** — emitted when a stale (epoch-mismatched) timer fires. `:tags {:machine-id <id> :state <state> :delay <ms> :scheduled-epoch <e1> :current-epoch <e2>}`. The transition does not fire.

Tools subscribe to whichever granularity they need: `:scheduled` for timeline visualisation, `:fired` for the externally-observable transition, `:stale-after` for diagnosing "a timer should have fired but didn't" symptoms.

### Worked example

```clojure
{:initial :idle
 :guards  {:still-loading? (fn [data _] (:loading? data))}
 :states
 {:idle    {:on {:fetch :loading}}

  :loading
  {:after {5000  :timeout
           30000 {:guard :still-loading? :target :hard-error}}
   :on    {:loaded :ready
           :failed :error}}

  :timeout    {:on {:retry :loading}}
  :hard-error {:on {:reset :idle}}
  :ready      {:on {:reset :idle}}
  :error      {:on {:reset :idle}}}}
```

Walkthrough. The user dispatches `[:fetch]`. The machine transitions `:idle` → `:loading`; `:rf/after-epoch` advances from 0 to 1; both `:after` timers schedule with epoch 1 (`:rf.machine.timer/scheduled` × 2).

- **Path 1 — `:loaded` arrives at t=2s.** The machine transitions `:loading` → `:ready`; `:rf/after-epoch` advances to 2. At t=5s the 5000ms timer fires; epoch carried = 1, current = 2; `:rf.machine.timer/stale-after` emits; ignored. At t=30s the 30000ms timer fires; same story.
- **Path 2 — neither arrives by t=5s.** The 5000ms timer fires; epoch matches; the transition resolves; `:rf.machine.timer/fired` emits with `:fired? true`; machine transitions `:loading` → `:timeout`; `:rf/after-epoch` advances to 2. At t=30s the 30000ms timer fires with epoch 1; `:rf.machine.timer/stale-after` emits; ignored.
- **Path 3 — `:loaded` doesn't arrive but `:loading?` is still true at t=30s.** The 5000ms timer fired at t=5s and (suppose) the user dispatched `[:retry]` from `:timeout` at t=10s; the machine re-entered `:loading`; `:rf/after-epoch` advanced to 3; both timers re-scheduled. The original 30000ms timer (epoch 1, scheduled at t=0) eventually fires; stale; ignored. The newly-scheduled 30000ms timer's guard `:still-loading?` is consulted at fire time.

External observers see one machine event per externally-visible transition; the timer scheduling and stale-suppression noise stays inside the trace stream.

### What `:after` does *not* include

- **Recurring timers.** `:after` fires once per state entry. For polling, the user re-enters the state (e.g., `:fetching → :waiting → :fetching` with `:waiting` carrying an `:after` that loops back).
- **Wall-clock delays.** `:after` is relative to entry time, not "fire at 9:00 AM tomorrow." Calendar-scheduled events are an application-level concern; the machine can react to a user-emitted `:dispatch-later` from outside.
- **Pause / resume.** No built-in pause; users pause by transitioning the snapshot out of the state (which makes the timers stale) and back in (which re-schedules with a fresh epoch). The `:rf/after-epoch` mechanism makes the round-trip idempotent.
- **A `:cancel-dispatch-later` fx.** The epoch mechanism replaces explicit cancellation; the runtime never needs to forget a scheduled timer, only to reject stale ones at expiry.

## Spawning — dynamic actors

If machines are event handlers and actors are machines, then **each spawned actor gets a dynamically-registered event handler whose id is the actor's address.** The mailbox / addressing semantics fall out of `dispatch` — no new primitive.

> **Frame-local registration is load-bearing.** A spawn registers its handler in the **frame-local** tier of the two-tier registry, not in the central boot-time tier. This is what makes spawning compatible with [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility): when a frame's value is reverted to a prior point, every actor spawned since that point disappears with it (its frame-local handler entry rolls back along with its `[:rf/machines <id>]` snapshot). If spawn instead added entries to the central registry, undo would leave dangling handlers behind, and the AI / undo / time-travel guarantees in [000 §Frame state revertibility](000-Vision.md#frame-state-revertibility) would not hold.

Symmetry between singleton and spawned:

| | id form | snapshot location | handler |
|---|---|---|---|
| Singleton | `:drawer/editor` (explicit) | `[:rf/machines :drawer/editor]` | registered at boot via `reg-event-fx` |
| Spawned actor | `:request/protocol#42` (gensym'd) | `[:rf/machines :request/protocol#42]` | registered dynamically by `spawn-machine` |

Both are event handlers. Both addressable by `dispatch`. Both visible to `(handlers :event)`. Both readable through the framework-registered `:rf/machine` sub (per [§Subscribing to machines via `sub-machine`](#subscribing-to-machines-via-sub-machine)) — the actor-id is just the argument: `@(rf/sub-machine actor-id)`.

### Spawning from inside an action (the common case)

```clojure
:action (fn [_ [_ url]]
          {:fx [[:spawn {:machine-id :request/protocol
                         :id-prefix  :request/protocol
                         :data       {:url url}
                         :on-spawn   (fn [data id] (assoc data :pending-request id))
                         :start      [:begin]}]]})
```

After this action, `(:pending-request data)` *is* the actor's id. Subsequent transitions can `[:fx [[:dispatch [(:pending-request data) [:retry]]]]]`.

### Spawn-spec keys

| key | purpose | required? |
|---|---|---|
| `:machine-id` *or* `:definition` | which machine to instantiate (registered id, or inline spec map) | one of these |
| `:id-prefix` | base for the gensym'd actor id (`:request/protocol#42`) | optional; defaults to `:machine-id` |
| `:data` | initial data for the new machine (overrides definition's default) | optional |
| `:on-spawn` | `(fn [data id] new-data)` — how the parent records the new id | required for from-action spawns; ignored for top-level `spawn-machine` calls |
| `:start` | event vector dispatched to the new actor immediately after spawn | optional |

The spawned actor's snapshot lives at `[:rf/machines <gensym'd-id>]` — the runtime owns the location, the spawn-spec only declares the id-prefix. See [§Where snapshots live](#where-snapshots-live) and [Spec-Schemas §`:rf.fx/spawn-args`](Spec-Schemas.md#rffxspawn-args).

### Top-level `spawn-machine` (rare)

```clojure
(def actor-id
  (rf/spawn-machine
    {:definition request-protocol           ;; or :machine-id if reusing a registered definition
     :id-prefix  :request/protocol           ;; → :request/protocol#42
     :data       {:url "/foo" :attempt 0}}))

;; snapshot lives at [:rf/machines :request/protocol#42] in the active frame's app-db.

;; address it
(rf/dispatch [actor-id [:retry]])
(rf/dispatch [actor-id [:cancel]])

;; destroy
(rf/destroy-machine actor-id)
;; Internally: run :exit action, dissoc the snapshot at [:rf/machines actor-id],
;; clear-event actor-id. (No per-machine sub to clear — reads go through the
;; framework-registered :rf/machine sub, parameterised on actor-id.)
```

### Spawning multiple, dynamic counts

Multiple `[:spawn ...]` entries in `:fx` work independently; each runs its `:on-spawn` against the current data (post-previous-spawn). For dynamic-count spawning, build the `:fx` vector with `mapv`:

```clojure
:action (fn [_ [_ jobs]]
          {:fx (mapv (fn [job]
                       [:spawn {:machine-id :worker
                                :data       job
                                :on-spawn   (fn [data id]
                                              (update data :workers (fnil conj []) id))}])
                     jobs)})
;; → after action: (:workers data) is [<id-0> <id-1> <id-2> ...]
```

The `:on-spawn` shape is general enough to subsume binding-as-key (`(assoc d :k id)`), append-to-vector (`(update d :ks conj id)`), assoc-into-map (`(assoc-in d [:by-key k] id)`), and any custom shape. One primitive; user picks the merge.

### What spawning gives for free

- **Inspection.** `(handlers :event)` lists every live actor. Filter by `:rf/machine?` metadata.
- **Tracing.** Every message to an actor is a normal `:event` trace. Lifecycle is `:registry/handler-{registered,cleared}`.
- **Errors.** Sending to a destroyed actor → `:rf.error/no-such-handler`. Already categorised, already recoverable.
- **Hot-reload.** Live spawned instances pick up new table interpretations on next event.
- **Cross-machine messaging.** Parent → child is `[:fx [[:dispatch [child-id [:event]]]]]`. Child → parent is the same. No `sendTo` / `sendParent` distinction — `dispatch` already addresses any id.
- **`:raise` lowers to self-dispatch with atomic semantics.** `[:raise [:event]]` ≡ `[:fx [[:dispatch [<self-id> [:event]]]]]` *with* "processed before commit." The former is sugar.

## Declarative `:invoke` (sugar over spawn)

`:invoke` on a state node is **declarative sugar** for "spawn this child actor on entry; destroy it on exit." The child's lifetime is bound to the state's lifetime: while the machine is in this state, the child runs; when the machine leaves the state (by any transition, including a parent-level cascade), the child is destroyed.

`:invoke` is **registration-time sugar.** `create-machine-handler` walks the spec at construction time and rewrites every `:invoke` slot into entry/exit actions emitting `:spawn` and `:destroy-machine` fx. The runtime sees only the desugared form — no new mechanics, no new lifecycle event, no new error category.

### The pattern

```clojure
{:loading
 {:invoke {:machine-id :request/protocol
           :data       {:url "/api/foo"}
           :on-spawn   (fn [data id] (assoc data :pending id))
           :start      [:begin]}
  :on     {:succeeded {:target :loaded}
           :failed    {:target :error}}}}
```

While in `:loading`, an actor of `:request/protocol` exists at `[:rf/machines <gensym'd-id>]`, addressable by the id stored in `(:pending data)`. On any transition out of `:loading`, the actor is destroyed and its snapshot disappears.

### Spec-spec keys

The map under `:invoke` accepts the following keys:

| key | purpose | required? |
|---|---|---|
| `:machine-id` *or* `:definition` | which machine to spawn (registered id, or inline transition table) | exactly one of these |
| `:data` | initial data for the child — literal map or `(fn [snapshot event] data)` | optional |
| `:id-prefix` | base for the gensym'd actor id (`:request/protocol#42`) | optional; defaults to `:machine-id` |
| `:on-spawn` | `(fn [data spawned-id] new-data)` — how the parent records the child id in its own `:data` | optional but typically wanted |
| `:start` | event vector dispatched to the newborn after spawn | optional |
| `:invoke-id` | explicit id instead of gensym (useful for tests / per-state singleton actors) | optional |

The keys mirror [§Spawn-spec keys](#spawn-spec-keys), with two additions:

- `:data` admits a function form `(fn [snap ev] data)` so the initial data can depend on the snapshot at the moment of entry — the snapshot is the *post-action* value (the transition's `:action` has already run, so any `:data` writes the action made are visible).
- `:invoke-id` is an explicit alternative to `:id-prefix` + gensym — useful when a state should host exactly one actor with a known id (no need to record the id in the parent's `:data` because it's already a known constant).

**Path convention.** The `:on-spawn` callback receives the snapshot's `:data` directly and returns a new `:data` map. The runtime patches the result back into the snapshot. Per [§Path conventions in machine bodies](#path-conventions-in-machine-bodies), this is uniform with `:guard` and `:action`: the body operates on `:data`, never on the wrapping snapshot. A typical body is `(assoc data :pending id)` or `(update data :workers (fnil conj []) id)` — *not* `(assoc-in snap [:data :pending] id)`.

### Desugaring rules

`create-machine-handler` walks every state node at construction time. For each `:invoke`-bearing state, it:

1. **Composes** an `:rf.invoke/spawn-<state>` registered action that emits a `:spawn` fx whose args are the `:invoke` spec, with `:data` materialised (call the fn if `:data` is a fn, else use the literal).
2. **Composes** an `:rf.invoke/destroy-<state>` registered action that emits a `:destroy-machine` fx for the actor id (resolved either from the parent's `:data` via the `:on-spawn` key — the runtime tracks which key the user's `:on-spawn` wrote — or from the `:invoke-id` literal).
3. **Wires** the composed actions into the state's `:entry` and `:exit` slots, after any user-supplied `:entry` / `:exit` (see [§Composition with explicit `:entry` / `:exit`](#composition-with-explicit-entry--exit)).

Before / after:

```clojure
;; user writes (declarative :invoke):
{:loading
 {:invoke {:machine-id :request/protocol
           :data       (fn [snap _] {:url (-> snap :data :endpoint)})
           :on-spawn   (fn [data id] (assoc data :pending id))
           :start      [:begin]}
  :on     {:succeeded :loaded
           :failed    :error}}}

;; create-machine-handler rewrites to (runtime sees this):
{:loading
 {:entry (fn [data _ev]
           {:fx [[:spawn {:machine-id :request/protocol
                          :id-prefix  :request/protocol
                          :data       {:url (:endpoint data)}
                          :on-spawn   (fn [d id] (assoc d :pending id))
                          :start      [:begin]}]]})
  :exit  (fn [data _]
           {:fx [[:destroy-machine (:pending data)]]})
  :on    {:succeeded :loaded
          :failed    :error}}}
```

From outside, an `:invoke`-using machine is indistinguishable from one that wrote the entry/exit by hand. The pure-factory invariant on `create-machine-handler` is preserved — no global state, no new registry, no new lifecycle hook.

### Composition with explicit `:entry` / `:exit`

A state may declare both `:invoke` AND user-supplied `:entry` / `:exit`. The user-supplied actions run **first** in each slot:

- **On enter:** the user's `:entry` action runs, then the auto-spawn fx is emitted.
- **On exit:** the user's `:exit` action runs, then the auto-destroy fx is emitted.

Rationale: the user's `:entry` is for setup work that must happen before the child starts (e.g., normalising data, recording a start timestamp). The spawn happens after that setup completes, so the child sees the post-setup snapshot. On exit, the user's `:exit` action gets to read the actor's final snapshot via the parent's recorded id (`(get-in db [:rf/machines (:pending data)])`) before the auto-destroy clears it — useful for capturing the child's last reported value.

The composition is **wire-level concatenation, not nesting** — the action ordering is `[user-entry, auto-spawn]` for entry and `[user-exit, auto-destroy]` for exit. Each runs as a normal action, returning its own `{:data :fx}` effect map; the runtime drains them in order per [§Drain semantics — Level 2](#level-2--across-the-action-slots-in-one-transition).

`:entry` and `:exit` remain **singular slots** ([§State nodes](#state-nodes)) — the user writes one fn or one registered id, and the desugaring of `:invoke` adds exactly one more action to each slot. There is no user-visible vector form.

### Composition with hierarchical states

A `:invoke`-bearing state can sit at any level of a compound hierarchy. The `:invoke` slot produces ordinary `:entry` / `:exit` actions — the existing entry/exit cascading machinery from [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca) handles them naturally.

Concretely:

- A child state's `:invoke` fires its spawn when the child is *entered* (which may happen as part of a deeper cascade — e.g., entering a compound parent for the first time also enters the parent's `:initial` cascade target).
- A parent state's `:invoke` fires its destroy when the parent is *exited* — and the parent is exited only when a transition's LCA is above it. Sibling-leaf transitions inside the parent do NOT destroy a parent-level `:invoke` actor.
- Multiple `:invoke`-bearing ancestors along a deep path each contribute their own spawn/destroy pair, ordered by the cascade direction (entry: outermost first; exit: innermost first).

The desugaring is uniform — no special-casing for hierarchy. Whatever cascading rules the existing entry/exit machinery applies are exactly what `:invoke` inherits.

### Errors

`:invoke` introduces **no new error categories**. Failures route through the existing `:rf.error/*` machinery:

- If `:data` is a function and it throws, the error surfaces as `:rf.error/machine-action-exception` (the standard category for any user-supplied fn that throws during a machine action — see [Cross-Spec-Interactions §11](Cross-Spec-Interactions.md#11-machine-action-throws)). The transition halts; the snapshot does not commit.
- If `:machine-id` references an unregistered machine, the spawn fx itself errors per existing spawn semantics — no `:invoke`-specific category.
- If the user supplies neither `:machine-id` nor `:definition`, `create-machine-handler` rejects the spec at registration time as a malformed transition table — the schema makes "exactly one of `:machine-id` or `:definition`" a registration-time constraint per [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rftransition-table).

### Deliberate omissions vs xstate

xstate's `invoke` admits several features re-frame2 deliberately omits. Each has a substitute that fits re-frame2's existing primitives:

| xstate feature | re-frame2 substitute |
|---|---|
| **`onDone`** — fire a callback when the child reaches a final state | The leaf state explicitly `:raise`s an event back to the parent (or `:fx [[:dispatch [parent-id [:done ...]]]]`). re-frame2 does not ship final-state-with-onDone runtime machinery; "done" is just an event the leaf chooses to emit. |
| **`onError`** — child error callback | Errors flow through the standard `:rf.error/*` machinery and are visible in trace events. The parent observes via the existing error envelope, not an `:invoke`-specific hook. |
| **Multiple `:invoke` per state** (xstate admits a vector) | One `:invoke` per state. Multiple actors per state suggests refactoring into a compound state where each substate invokes one of the actors. |
| **`autoForward`** — forward all parent events to the child | Users forward explicitly via `:fx [[:dispatch [child-id ev]]]` from the relevant transitions. Implicit forwarding is invisible at the call site; explicit forwarding is what visualisers and AIs read. |

Each omission is consistent with the spec's broader bias: **prefer one explicit primitive over many implicit conveniences.** The substitutes use mechanisms already required for spawn / destroy / `dispatch` / `:raise`; `:invoke` is the *only* new sugar in this area, and even it is desugared at construction time.

### Worked example — declarative login flow

```clojure
{:initial :idle
 :states
 {:idle  {:on {:submit :authenticating}}

  :authenticating
  {:invoke {:machine-id :http/post
            :data       (fn [snap _] {:url  "/api/login"
                                      :body (-> snap :data :credentials)})
            :on-spawn   (fn [data id] (assoc data :auth-actor id))}
   :on     {:auth/succeeded :authenticated
            :auth/failed    :idle}}

  :authenticated {...}}}
```

The walk-through:

1. User submits → state moves `:idle` → `:authenticating`.
2. Entering `:authenticating` triggers the desugared entry: spawn an `:http/post` actor with the credentials from `:data`; the `:on-spawn` fn binds the actor's id under `:auth-actor`.
3. The HTTP child runs; on success, it dispatches `[:login [:auth/succeeded ...]]` (where `:login` is the parent machine's id).
4. The login machine handles `:auth/succeeded`; transitions to `:authenticated`.
5. Leaving `:authenticating` triggers the desugared exit: destroy the actor stored at `:auth-actor`. The HTTP child's snapshot is removed from `[:rf/machines]` automatically.
6. If the user abandons mid-flight (a different transition fires `:authenticating` → `:idle`), the exit cascade still runs; the in-flight HTTP child is destroyed; no actor leaks.

The key property: the parent does not have to *remember* to destroy the child. The lifecycle binding is declared once at the state level, and the exit cascade enforces it on every code path out of the state — including ones the author hasn't yet thought of.

Cross-references: [§Spawning](#spawning--dynamic-actors) for the imperative-spawn surface that `:invoke` desugars to; [§Composition with explicit `:entry` / `:exit`](#composition-with-explicit-entry--exit) for the auto+manual ordering rule; [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rftransition-table) for the `:invoke` schema. [Pattern-WebSocket](Pattern-WebSocket.md) is the canonical worked example exercising hierarchical states, `:after`, `:always`, machine-scoped `:guards` / `:actions`, and `:invoke` together — the connection-lifecycle state machine for long-lived sockets. [Pattern-LongRunningWork](Pattern-LongRunningWork.md) is the canonical worked example for chunked CPU-intensive work — `:always` for batch progression, `:after 0` for browser yielding between chunks, machine-scoped guards for completion / cancellation.

## Reply patterns

xstate's `sendTo` + `sender` lets a child reply to a specific request. In re-frame, no new API: include the reply event in the request:

```clojure
(rf/dispatch [:request/get-data
              {:url   "/data"
               :reply [:got-data <correlation-id>]}])
```

The handler dispatches `:got-data` (with the correlation id) when the response arrives. The drain cascade keeps the request and reply in the same atomic unit. This is just convention; document it.

## Querying machines

A machine *is* an event handler — that's the architectural commitment. But callers (tooling, AIs, conformance harnesses, post-v1 visualisers) routinely ask "what machines are registered?" and "what is machine `<id>`'s definition / metadata?" Forcing every caller to reimplement "scan `(handlers :event)`, filter by `:rf/machine? true`" is a tax with no upside.

The framework therefore ships two thin lookup fns — **derived views over the existing event registry**, not a new registry kind:

```clojure
(rf/machines)
;; → seq of machine-ids
;; Implementation: every event handler whose registration metadata
;; carries :rf/machine? true.

(rf/machine-meta :drawer/editor)
;; → registration-metadata map (transition table, doc, schemas, ...)
;; Implementation: (handler-meta :event :drawer/editor), with the
;; standard metadata-map shape; machine-specific keys (e.g.
;; :rf/transition-table) are present iff :rf/machine? is true.
```

Both are pure functions over the registry. Both are JVM-runnable (they touch only the central registry). Both are stable across hot-reload because they re-read on each call.

Why a lens, not a registry kind:

- **Architectural commitment preserved.** Machines remain *event handlers*. There is no `:machine` registry kind, no parallel substrate, no per-machine auto-registration. `(rf/machines)` is a `filter` call, not a separate index.
- **`:rf/machine? true` metadata is the discriminator.** `create-machine-handler` carries this metadata onto the registration; `reg-event-fx` records it as part of the standard metadata map (per [001 §Metadata-map shape](001-Registration.md)). User-written event handlers do not set this key.
- **One-line implementation.** `(rf/machines)` is `(handlers :event #(:rf/machine? %))`-shaped; `(rf/machine-meta id)` is `(handler-meta :event id)`. Both reuse the public registrar query API ([API.md §Public registrar query API](API.md#public-registrar-query-api)).
- **Discovery is a first-class operation.** Visualisers can iterate every live machine without knowing where else to look; conformance harnesses can enumerate the suite under test; AI agents can answer "show me the machines in this app."

User-facing call sites:

```clojure
(rf/machines)
;; → (:auth.login/flow :checkout/flow :request/protocol#42 ...)

(for [id (rf/machines)]
  [id (-> (rf/machine-meta id) :doc)])
;; → ([:auth.login/flow "Login flow: idle → submitting → ..."]
;;    [:checkout/flow "Checkout wizard."]
;;    ...)
```

See also [API.md §Machines](API.md#machines) and [Conventions.md §Reading machines](Conventions.md#reading-machines).

## Subscribing to machines via `sub-machine`

Machines are read like any other `app-db` slice — through a registered subscription. The framework ships **`:rf/machine`** as standard infrastructure (alongside `:dispatch` fx, the `path` interceptor, and the rest of the framework-supplied registry entries):

```clojure
(rf/reg-sub :rf/machine
  (fn [db [_ machine-id]]
    (get-in db [:rf/machines machine-id])))
```

Returns the whole snapshot `{:state <kw> :data <map>}` for the named machine, or `nil` if the machine is not yet initialised. The argument is **just the machine-id** — no varargs, no path-drilling. Granularity is the user's job via derived subs.

### Two equivalent surfaces

The framework exposes two surfaces, both equivalent:

- **`(rf/sub-machine :drawer/editor)`** — the canonical user-facing call site. Lives in `re-frame.core` alongside `subscribe`, `dispatch`, `reg-event-fx`. Single-arg; returns a Reagent reaction over the snapshot. The verb-noun name reads as "subscribe to a machine."

  ```clojure
  (defn sub-machine [machine-id]
    (rf/subscribe [:rf/machine machine-id]))
  ```

- **`(rf/subscribe [:rf/machine :drawer/editor])`** — explicit registry use. The `:rf/machine` sub is in `(handlers :sub)`, traceable, introspectable. Power-users and tools use this form.

`sub-machine` is sugar over the registered sub. Both surfaces resolve on the surrounding frame; `@(rf/sub-machine :drawer/editor)` reads from that frame's `[:rf/machines :drawer/editor]`.

```clojure
;; usage in a view:
@(rf/sub-machine :drawer/editor)
;; → {:state :idle :data {:circle-id nil ...}}      (or nil before initialisation)

;; equivalent explicit form:
@(rf/subscribe [:rf/machine :drawer/editor])
```

### Granularity is via derived subs

The framework provides the entry point — `:rf/machine` returns the whole snapshot. Users write Layer-3 (signal-graph chained) subs for fine-grained reactivity, multi-source combinations, or computed projections:

```clojure
;; project just :state
(rf/reg-sub :drawer/editor-state
  :<- [:rf/machine :drawer/editor]
  (fn [{:keys [state]} _] state))

;; a boolean over :state
(rf/reg-sub :drawer/editing?
  :<- [:rf/machine :drawer/editor]
  (fn [{:keys [state]} _] (= state :editing)))

;; combine with other subs
(rf/reg-sub :drawer/editor-and-circles
  :<- [:rf/machine :drawer/editor]
  :<- [:drawer/circles]
  (fn [[ed circles] _] {:editor ed :circles circles}))
```

The framework provides the entry point; users write the derivations. Same pattern as every other `:<-` chain in re-frame.

### Pure-factory invariant preserved

`create-machine-handler` registers nothing — it returns a pure handler fn. Registration of the *machine's event handler* happens at the `reg-event-fx` call site; reading the snapshot happens through the framework-registered `:rf/machine` sub. There is no auto-registration tied to the machine's id, no self-id capture, no registration side effects in the factory.

## Testing

Three test levels fall out naturally from the pure-factory contract on `create-machine-handler`. No new primitive needed.

### Level 1 — pure `machine-transition`

```clojure
(rf/machine-transition definition snapshot event)
;; → [next-snapshot effects]
```

No `:db`, no `[:rf/machines]` plumbing, no fx interpretation — just the FSM. Best for property-based testing, table-driven assertions, fastest tests. Definition + snapshot in, `[snapshot, effects]` out.

### Level 2 — unregistered handler fn

```clojure
(def handler (rf/create-machine-handler {:initial :idle :states {...}}))

;; The runtime stores snapshots at [:rf/machines <id>], where <id> is the
;; surrounding registration's id. A Level-2 test calls the handler against the
;; canonical `app-db` shape directly:
(handler {:db {:rf/machines {:drawer/editor {:state :idle :data {}}}}}
         [:drawer/editor [:right-click-circle some-id 30]])
;; → {:db ... :fx ...}
```

Tests handler-level integration (snapshot read/write at `[:rf/machines <id>]`, `:data`-to-`:db` lowering, fx composition) without going near the dispatch pipeline. **Possible only because `create-machine-handler` is a pure factory** — no registration, no test frame.

The handler resolves its id from the inbound event vector's first element (`:drawer/editor`), reads `(get-in db [:rf/machines :drawer/editor])` for the current snapshot, and writes the next snapshot back at the same location.

### Level 3 — registered in a test frame

```clojure
(rf/with-frame [f (rf/make-frame {:on-create [:my/init]})]
  (rf/reg-event-fx :my/editor {} (rf/create-machine-handler {...}))
  (rf/dispatch-sync [:my/editor [:event]] {:frame f})
  (assert ...))
```

Full integration — error categories, trace events, drain semantics. **Required for spawned-actor patterns**, because the whole point of spawn is "a new handler gets registered dynamically and the parent can `dispatch` to it" — bypassing the registry tests something else.

### The pyramid

| level | what you test | speed | can't test |
|---|---|---|---|
| 1 — `machine-transition` | FSM logic, guards, action effect shapes | fastest | snapshot/db plumbing, fx integration |
| 2 — unregistered handler fn | handler-level wiring, `:data` lowering | fast | dispatch pipeline, spawn lifecycle |
| 3 — registered in test frame | full integration, spawn/destroy, cross-actor messaging | slowest | nothing |

## Worked example — Circle Drawer

The 7GUIs circle-drawer in this style. The modal-edit flow is a registered machine; canvas-add and undo/redo stay as ordinary handlers (orthogonal concerns).

```clojure
(ns seven-guis.circle-drawer-machine
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]))

;; ----------------------------------------------------------------------------
;; SCHEMA + UNDO INTERCEPTOR
;; ----------------------------------------------------------------------------

(def Circle [:map [:id :uuid] [:x :double] [:y :double] [:radius pos-int?]])
;; The :drawer/editor machine's snapshot lives at [:rf/machines :drawer/editor]
;; — runtime-managed; not part of the :drawer schema. The runtime composes
;; [:rf/machines]'s schema from registered machines' :data shapes; this slice
;; describes only the :drawer-owned domain state.
(def DrawerState
  [:map [:circles [:vector Circle]]
        [:undo [:vector :any]] [:redo [:vector :any]]])
(rf/reg-app-schema [:drawer] DrawerState)

(def undoable
  {:id     :undoable
   :before (fn [ctx]
             (assoc-in ctx [:coeffects :prior-circles]
                       (get-in ctx [:coeffects :db :drawer :circles])))
   :after  (fn [ctx]
             (let [prior    (get-in ctx [:coeffects :prior-circles])
                   db-after (get-in ctx [:effects :db])]
               (if (and db-after (not= prior (get-in db-after [:drawer :circles])))
                 (-> ctx
                     (update-in [:effects :db :drawer :undo] (fnil conj []) prior)
                     (assoc-in  [:effects :db :drawer :redo] []))
                 ctx)))})

;; ----------------------------------------------------------------------------
;; DOMAIN EVENT — the actual mutation lives here, not in the machine
;; ----------------------------------------------------------------------------

(rf/reg-event-db :drawer/apply-radius
  {:doc "Persist a circle's new radius. Called by the editor machine on commit."
   :interceptors [undoable]}
  (fn [db [_ circle-id new-radius]]
    (update-in db [:drawer :circles]
               (fn [cs]
                 (mapv #(if (= circle-id (:id %)) (assoc % :radius new-radius) %)
                       cs)))))

;; ----------------------------------------------------------------------------
;; MACHINE — event handler IS the machine
;;
;; Inspectability bias (§Inspectability bias): non-trivial actions are named
;; in the machine's :actions map. The right-click action seeds three keys
;; derived from the event — compound enough to deserve a name. The
;; close-dialog action both emits an :fx and clears :data — also compound.
;; The drag-slider and cancel-dialog actions are single-expression :data
;; updates, so they stay inline (the escape hatch).
;; ----------------------------------------------------------------------------

(rf/reg-event-fx :drawer/editor
  {:doc "Modal-edit flow."}
  (rf/create-machine-handler
    {:initial :idle
     :data    {:circle-id nil :initial-radius nil :preview-radius nil}
     :actions
     {:begin-edit
      ;; Seed circle-id, initial-radius, and preview-radius from the right-click event.
      (fn [_ [_ id radius]]
        {:data {:circle-id      id
                :initial-radius radius
                :preview-radius radius}})

      :commit
      ;; Persist the previewed radius via :drawer/apply-radius and clear :data.
      (fn [data _]
        {:fx   [[:dispatch [:drawer/apply-radius
                            (:circle-id data)
                            (:preview-radius data)]]]
         :data {:circle-id      nil
                :initial-radius nil
                :preview-radius nil}})}
     :states
     {:idle
      {:on
       ;; Note the event shape — the view passes the radius in the payload
       ;; rather than the machine reaching into app-db. Strict encapsulation:
       ;; cross-cutting data flows via the event vector, not via :db.
       {:right-click-circle
        {:target :editing
         :action :begin-edit}}}                              ;; resolves to :actions :begin-edit

      :editing
      {:on
       {:drag-slider
        ;; internal self-transition — no :target, so no exit/entry.
        ;; Single-key :data update, single non-branching expression — inline OK
        ;; per the inspectability-bias escape hatch.
        {:action (fn [_ [_ new-r]]
                   {:data {:preview-radius new-r}})}

        :close-dialog
        {:target :idle
         :action :commit}                                    ;; resolves to :actions :commit

        :cancel-dialog
        ;; Single :data clear, single non-branching expression — inline OK.
        ;; Nothing to apply — preview was never persisted.
        {:target :idle
         :action (fn [_ _]
                   {:data {:circle-id      nil
                           :initial-radius nil
                           :preview-radius nil}})}}}}}))

;; ----------------------------------------------------------------------------
;; DOMAIN EVENTS (orthogonal to the machine)
;; ----------------------------------------------------------------------------

(rf/reg-event-fx :drawer/initialise
  (fn [_ _]
    ;; Domain state under :drawer; the editor machine's snapshot lives at
    ;; [:rf/machines :drawer/editor] — runtime-managed; not seeded here.
    {:db {:drawer {:circles [] :undo [] :redo []}}}))

(rf/reg-event-db :drawer/add-circle
  {:interceptors [undoable]}
  (fn [db [_ x y]]
    (update-in db [:drawer :circles] conj
               {:id (random-uuid) :x x :y y :radius 30})))

(rf/reg-event-db :drawer/undo
  (fn [db _]
    (let [{:keys [undo circles]} (:drawer db)]
      (if (empty? undo) db
          (-> db (assoc-in  [:drawer :circles] (peek undo))
                 (update-in [:drawer :undo]    pop)
                 (update-in [:drawer :redo]    (fnil conj []) circles))))))

(rf/reg-event-db :drawer/redo
  (fn [db _]
    (let [{:keys [redo circles]} (:drawer db)]
      (if (empty? redo) db
          (-> db (assoc-in  [:drawer :circles] (peek redo))
                 (update-in [:drawer :redo]    pop)
                 (update-in [:drawer :undo]    (fnil conj []) circles))))))

;; ----------------------------------------------------------------------------
;; SUBS — preview state is *display* state, not domain state
;; ----------------------------------------------------------------------------

(rf/reg-sub :drawer/circles      (fn [db _] (get-in db [:drawer :circles])))
;; The framework-registered :rf/machine sub returns the snapshot {:state :data}
;; for any machine — we parameterise it on :drawer/editor and compose against
;; it via :<-. (Equivalently: @(rf/sub-machine :drawer/editor).)
(rf/reg-sub :drawer/editor-state :<- [:rf/machine :drawer/editor] (fn [snap _] (:state snap)))
(rf/reg-sub :drawer/editor-data  :<- [:rf/machine :drawer/editor] (fn [snap _] (:data snap)))
(rf/reg-sub :drawer/editing? :<- [:drawer/editor-state] (fn [s _] (= s :editing)))
(rf/reg-sub :drawer/can-undo? (fn [db _] (seq (get-in db [:drawer :undo]))))
(rf/reg-sub :drawer/can-redo? (fn [db _] (seq (get-in db [:drawer :redo]))))

(rf/reg-sub :drawer/circles-with-preview
  :<- [:drawer/circles]
  :<- [:drawer/editor-data]
  :<- [:drawer/editing?]
  (fn [[circles ed editing?] _]
    (if editing?
      (mapv #(if (= (:id %) (:circle-id ed))
               (assoc % :radius (:preview-radius ed)) %)
            circles)
      circles)))

;; ----------------------------------------------------------------------------
;; VIEW
;; ----------------------------------------------------------------------------

(rf/reg-view main []
  (let [circles                @(rf/subscribe [:drawer/circles-with-preview])
        ;; sub-machine returns the whole snapshot; inline-destructure it.
        {state :state ed :data} @(rf/sub-machine :drawer/editor)
        editing?               (= state :editing)
        can-undo?              @(rf/subscribe [:drawer/can-undo?])
        can-redo?              @(rf/subscribe [:drawer/can-redo?])]
    [:div.drawer
     [:div.row
      [:button {:on-click #(rf/dispatch [:drawer/undo]) :disabled (not can-undo?)} "Undo"]
      [:button {:on-click #(rf/dispatch [:drawer/redo]) :disabled (not can-redo?)} "Redo"]]
     [:svg {:width 600 :height 400 :style {:border "1px solid #999"}
            :on-click (fn [e]
                        (when-not editing?
                          (let [r (.. e -currentTarget getBoundingClientRect)
                                x (- (.. e -clientX) (.-left r))
                                y (- (.. e -clientY) (.-top r))]
                            (rf/dispatch [:drawer/add-circle x y]))))}
      (for [{:keys [id x y radius]} circles]
        ^{:key id}
        [:circle {:cx x :cy y :r radius :fill "transparent" :stroke "black"
                  :on-context-menu (fn [e] (.preventDefault e)
                                     ;; pass radius in the event payload — machine cannot read :db
                                     (rf/dispatch [:drawer/editor [:right-click-circle id radius]]))}])]
     (when editing?
       [:div.dialog {:style {:border "1px solid #999" :padding "10px" :margin-top "5px"}}
        [:p (str "Adjust diameter of circle " (:circle-id ed))]
        [:input {:type "range" :min 5 :max 100 :step 1
                 :value (:preview-radius ed)
                 :on-change #(rf/dispatch [:drawer/editor [:drag-slider
                                                           (js/parseInt (.. % -target -value))]])}]
        [:div.row
         [:button {:on-click #(rf/dispatch [:drawer/editor [:close-dialog]])}  "Commit"]
         [:button {:on-click #(rf/dispatch [:drawer/editor [:cancel-dialog]])} "Cancel"]]])]))
```

**Modeling rule the example illustrates:** preview is *display* state, not domain state. The drag never persists into `:circles`; instead the `:drawer/circles-with-preview` sub merges `:preview-radius` from the editor's `:data` into the rendered circles at read time. Cancel is therefore a no-op on domain state — there is nothing to revert because nothing was persisted.

## Capability matrix

Per [000-Vision §Hierarchical FSM substrate](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities), implementations declare which capabilities they support; **conformance is graded against the claimed capability list** rather than an all-or-nothing pass/fail. The matrix names each capability, what coverage it requires (prose / schema / fixture), and the v1 CLJS reference's claim for each.

### FSM-richness axis

| Capability | Coverage required | v1 CLJS reference | Notes |
|---|---|---|---|
| **Flat FSM** — states, transitions, guards, actions, `:entry` / `:exit`, wildcard `:*` | Prose: §Transition table grammar, §Action effect map; Schema: `:rf/transition-table` (flat); Fixtures: `machine-transition.edn` and the flat-FSM family | ✓ claimed | Already specced; the foundation. |
| **Hierarchical compound states** — nested `:states` in a state node; entry/exit cascading along the LCA path; vector / keyword target resolution; deepest-wins transition resolution with parent fallthrough | Prose: [§Hierarchical compound states](#hierarchical-compound-states); Schema: `:rf/state-node` (recursive) + `:rf/transition-target`; Fixtures: `hierarchical-compound-transition`, `hierarchical-cross-level-transition`, `hierarchical-parent-fallthrough` | ✓ claimed (specified) | Snapshot dual-form, LCA-based cascading, and deepest-wins resolution are locked. |
| **Eventless `:always` transitions** — fire as soon as a guard becomes true | Prose: [§Eventless `:always` transitions](#eventless-always-transitions); Schema: `:rf/state-node` extended for `:always` (see [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `always-single-microstep`, `always-depth-exceeded` | ✓ claimed (specified) | Microstep loop inside drain Level 3; bounded depth (default 16); self-loop forbidden at registration; trace events at both per-microstep and macrostep granularity. |
| **Delayed `:after` transitions** — fire after a time delay | Prose: [§Delayed `:after` transitions](#delayed-after-transitions); Schema: `:rf/state-node` extended for `:after` (see [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `after-single-delay`, `after-stale-detection`, `after-hierarchy` | ✓ claimed (specified) | Epoch-based stale detection — no `:cancel-dispatch-later` fx; clock primitives live in `re-frame.interop` (`now-ms`, `schedule-after!`, `cancel-scheduled!`); SSR-mode no-ops timer scheduling; trace events at `:scheduled` / `:fired` / `:stale-after` granularity. |
| **Parallel regions** — `:type :parallel` with multiple concurrent regions | Out of pattern scope; substitute documented in [§Substitutes for skipped features](#substitutes-for-skipped-features) | ✗ not claimed | Substitute: separate machines per region, coordinated via cross-actor dispatch. |
| **History states** — `:type :history` re-entering a compound's last-active substate | Out of pattern scope; substitute documented in [§Substitutes for skipped features](#substitutes-for-skipped-features) | ✗ not claimed | Substitute: snapshot-as-value capture using the existing `[:rf/machines <id>]` snapshot. |
| **Final states with `onDone` parent notification** | Prose: brief; Schema: extended for `:meta {:terminal? true :on-done <event-vec>}`; Fixtures: final-state-emits-on-done | Post-v1 | User code can dispatch on entry to a terminal state in v1. |

### Actor-model axis

| Capability | Coverage required | v1 CLJS reference | Notes |
|---|---|---|---|
| **Own state + message ports** — actor identity is the registered event id; the state lives at `[:rf/machines <id>]` | Prose: §Where snapshots live, §Strict encapsulation; Schema: `:rf/machine-snapshot`, `:rf/machines`; Fixtures: machine-transition, machine-actor-isolation | ✓ claimed | Already specced. |
| **Imperative spawn / destroy** — `spawn-machine`, `destroy-machine`, `[:spawn ...]` fx-id | Prose: §Spawning; Schema: `:rf.fx/spawn-args`; Fixtures: spawn-from-action, destroy-clears-snapshot, spawn-on-spawn-callback | ✓ claimed | Already specced. |
| **Cross-actor send via `:fx`** — `[:dispatch [other-actor-id [:event]]]` | Prose: §Spawning §What spawning gives for free; Fixtures: cross-actor-send | ✓ claimed | Falls out of standard `:dispatch` fx; no new mechanism. |
| **Declarative `:invoke`** (sugar over spawn) — a state's `:invoke` translates to entry/exit actions that spawn / destroy a child actor | Prose: [§Declarative `:invoke` (sugar over spawn)](#declarative-invoke-sugar-over-spawn); Schema: `:rf/state-node` extended for `:invoke` (per [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `invoke-spawn-on-entry-destroy-on-exit` | ✓ claimed (specified) | No new mechanics; pure sugar. `create-machine-handler` translates `:invoke` to entry/exit `:spawn` / `:destroy-machine` at registration time. Composes with user-supplied `:entry` / `:exit` (user runs first). |
| **SCXML compatibility** — full bidirectional schema parity with SCXML/Stately | Out of v1 scope (possibly never) | ✗ not claimed | Visualisation-compatibility (paste-and-render) is a smaller post-v1 ambition; see [§Stately.ai compatibility — exact or approximate?](#statelyai-compatibility--exact-or-approximate). |

### How conformance is graded

A re-frame2 port declares its capability list in its conformance harness manifest:

```clojure
{:port-id    :re-frame-cljs
 :capabilities #{:fsm/flat
                 :fsm/hierarchical
                 :fsm/eventless-always
                 :fsm/delayed-after
                 :actor/own-state
                 :actor/spawn-destroy
                 :actor/cross-actor-fx
                 :actor/invoke}}
```

The harness runs every fixture whose `:fixture/capabilities` is a subset of the port's claimed list; fixtures requiring un-claimed capabilities are skipped (and reported as "not exercised"). The aggregate score is "passes / claimed-applicable" rather than "passes / total." A port that only claims `:fsm/flat` + `:actor/own-state` + `:actor/spawn-destroy` is fully conformant for that subset — there is no penalty for not claiming hierarchical-states, just an honest accounting of what works.

**Error category for unclaimed grammar:** when `create-machine-handler` encounters a key whose capability is not in the host's claimed capability list, it emits a single structured error trace event:

```clojure
{:operation :rf.error/machine-grammar-not-in-v1
 :op-type   :error
 :tags      {:category   :rf.error/machine-grammar-not-in-v1
             :failing-id <machine-id>
             :feature    <unsupported-key>             ;; e.g. :after
             :reason     "Transition-table feature `<X>` is not in this implementation's claimed capability list. See [§Capability matrix](#capability-matrix)."}
 :recovery  :replaced-with-default}                    ;; the unsupported key is ignored
```

The error is registered as a category in [009 §Error contract](009-Instrumentation.md#error-contract). Surfaced once per `(machine-id, feature)` pair per process to avoid log spam.

Cross-references: [000 §Hierarchical FSM substrate](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) for the goal text; [conformance/README.md](conformance/README.md) for the fixture-tagging convention.

## Substitutes for skipped features

Two features the matrix names as out of pattern scope — **parallel regions** and **history states** — have well-defined substitutes that exploit re-frame2's existing primitives. The substitutes are not workarounds: they are *better* fits for the substrate, given the snapshot-as-value foundation per [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility).

The substitutes — *parallel regions → separate machines per region (with worked example)*, and *history states → snapshot-as-value capture (with worked example)* — and the rationale for why xstate needs them but re-frame2 doesn't, live in [CP-5-MachineGuide §Substitutes for parallel regions and history states](CP-5-MachineGuide.md#substitutes-for-parallel-regions-and-history-states). Out-of-scope features (`:type :parallel`, `:history`) emit `:rf.error/machine-grammar-not-in-v1` against v1; the runtime points users at the substitute patterns rather than promising future support.

## Open questions

### Library packaging — in-tree or separate?

The pure-transition + factory + spawn APIs are small. They could ship inside `re-frame` itself (alongside the existing handlers) or as a separate `re-frame.machines` library. Arguments either way:

- **In-tree:** discoverable; any re-frame app can use machines with no extra dep.
- **Separate:** keeps re-frame core small; lets machines evolve faster than re-frame.

Recommendation: prototype as separate, promote to in-tree once the API stabilises.

### Stately.ai compatibility — exact or approximate?

Aim for *paste-and-render* compatibility (a re-frame machine definition pastes into stately.ai and renders correctly), accepting some superficial vocabulary differences (e.g. our action ids vs stately's `actions: {...}` map). Or aim for *full bidirectional* compatibility (exact JSON shape parity)?

Recommendation: paste-and-render is the realistic target; full bidirectional is overinvestment unless someone wants to write a stately-driven authoring tool.

### Globally-registered guards/actions vs machine-scoped (RESOLVED)

Resolved: machine-scoped. Guards and actions live in the machine's `:guards` / `:actions` maps inside the `create-machine-handler` spec; transition-table keyword references resolve **machine-locally** at registration time. There is no `reg-machine-guard` / `reg-machine-action` API and no `:machine-guard` / `:machine-action` registry kind. Cross-machine reuse is via Clojure vars (define a var; reference it from each machine's `:guards` / `:actions` map) — no framework support needed beyond ordinary var resolution. See [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler) and [§Inspectability bias](#inspectability-bias).

### Spawn id format

`:request/protocol#42` (separator) vs `:request.protocol/42` (slash with numeric tail). Lean: `:request/protocol#42` — keeps it a keyword, the `#` is unambiguously instance-id.

### Auto-cleanup of orphaned actors

When a view spawns an actor and unmounts, what stops the leak? Lean: explicit `destroy-machine` for v1 (matches `make-frame`); opt-in `:owned-by` for post-v1.

## Resolved decisions

### Eventless `:always` transitions — microstep loop inside drain (RESOLVED)

Resolved: `:always` is a state-node key holding a vector of guarded transitions; the drain cascade extends Level 3 with a microstep loop (drain `:raise` → check `:always` → loop) that settles to a fixed point before commit. Default depth limit 16, error category `:rf.error/machine-always-depth-exceeded`. Same-state same-guard self-loops rejected at registration with `:rf.error/machine-always-self-loop`. Trace events emitted at both per-microstep and outer-macrostep granularity. See [§Eventless `:always` transitions](#eventless-always-transitions).

### Sub-event call-site shape (RESOLVED)

Resolved: the dispatch shape for events targeting a machine is the sub-event form `[:machine-id [:inner-event-keyword & payload]]` — the machine handler resolves the second-position inner keyword as the FSM event. The flat form (`[:machine-id/inner-event payload]` with one `reg-event-fx` registration per event) is **not** how machines are addressed. Why: fewer registry entries (one per machine, not one per event); call-site labels show "this is going to the editor machine"; works uniformly for spawned actors whose ids are gensym'd. See the worked examples in [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler) and the Circle Drawer.

### Multiple machine instances at one path

Snapshots live at the runtime-managed path `[:rf/machines <id>]`, keyed by the registered id. Two registrations sharing an id collide at the registry layer (last-write-wins per the standard registration semantics, with a re-registration trace event); a single id never has two snapshot locations. The earlier "two machines at one `:path`" scenario cannot arise because users no longer pick a path. Per-frame isolation falls out of each frame having its own `app-db` and thus its own `:rf/machines` map. See [§Where snapshots live](#where-snapshots-live).

## Lessons from xstate (deliberate divergences)

For readers familiar with xstate, the explicit list of where re-frame2 chose differently and why — `ActorRef` vs snapshots, mailboxes vs the per-frame router, `raise` vs `:raise`, three-creation-modes vs one, hierarchy as data, `:context` vs `:data`, compound guards, action vectors, `setup({...})` vs machine-scoped `:guards` / `:actions`, `[:assign {...}]` vs `:data` returns — lives in [CP-5-MachineGuide §Lessons from xstate](CP-5-MachineGuide.md#lessons-from-xstate-deliberate-divergences).

Convergences: machines-as-actors, run-to-completion, encapsulated state, snapshots, definition/implementation split, transition tables as data.

## Future

Post-v1 work that is in scope conceptually but does not ship in v1.

### Diagram export from transition tables

The transition table is data; rendering it as a diagram is straightforward. v1 ships no exporter; post-v1 candidates:

- `(rf/machine->mermaid definition)` — emit Mermaid `stateDiagram-v2`. Renders inline in GitHub markdown, VS Code preview, AI-agent prompts.
- `(rf/machine->d2 definition)` — emit D2.
- `(rf/machine->xstate-json definition)` — paste-and-render compatibility with Stately Studio (per [§Stately.ai compatibility — exact or approximate?](#statelyai-compatibility--exact-or-approximate)).

Mermaid/D2 are AI-fluent — LLMs read and write them confidently — which makes diagram export the cheapest way to extend AI-amenability of machine code.

### Inspector wire-format

Stately Inspector is a documented event protocol that any tool can subscribe to. re-frame2's machine traces (`:source :machine`, `:op-type :rf.machine/transition`, `:tags` carrying state/event/snapshot) are already very close in shape. A post-v1 mapping document — *re-frame2 trace ↔ Stately Inspector event* — lets external xstate-aware tools watch a re-frame2 app for free, and lets AIs reuse vocabulary they already know.

See [Tool-Pair.md](Tool-Pair.md) for the tooling story; the Stately mapping is one consumer.

### Model-based testing harness — `re-frame.machines.test`

A post-v1 library, planned as `re-frame.machines.test`, treats the transition table as a graph and generates test cases automatically. xstate's `@xstate/test` is the reference; re-frame2's pure `machine-transition` and machine-scoped `:guards` make the analogue cheap.

The substrate guarantees needed by the harness — all already locked in v1:

- **Pure transition function.** `(machine-transition definition snapshot event)` is deterministic; the harness can simulate any path without running the full runtime.
- **Data-only transition tables.** `:states` / `:on` / `:always` / `:after` / `:invoke` are all readable as data; no instrumentation, reflection, or special build steps required.
- **Machine-scoped guards as functions.** The harness can call `:guards` directly with synthesised snapshots to find inputs that make each guard `true` and `false` — generating test data, not just paths.
- **Machine-scoped actions as functions.** Same property; the harness can compose action effects without runtime side effects.
- **Conformance corpus shape.** Generated test cases land as EDN fixtures in the existing corpus format; same fixture exercises both the user's machine logic and any conformant implementation's machine substrate.

The harness's locked design (per the model-based-testing follow-up):

- **Default coverage model:** transition coverage (every transition fires at least once). State coverage and guard coverage are opt-in; path coverage (n-step combinations) for advanced cases.
- **`:after` timers** are included with explicit time-advance steps using the test-clock pattern from [`re-frame.interop`](002-Frames.md#interop-layer--clock-primitives).
- **Action / spawn stubbing** is auto-installed by the harness (registered fxs become no-ops in test mode); recursive coverage of spawned children is opt-in.
- **Output:** EDN fixtures in the corpus shape so generated tests are trace-comparable across implementations.

The harness is post-v1 because v1's substrate is sufficient — the harness builds on top without runtime changes. Two consumers will benefit:

1. **AI-implementability story** — when an AI implements re-frame in a new language (per [Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) and [Implementor-Checklist](Implementor-Checklist.md)), the harness produces a coverage corpus the implementation must pass.
2. **AI scaffolding of new machines** — an AI scaffolding a new application's machine generates its test corpus before writing any test by hand; reduces missed-edge-case bugs.

See [008-Testing.md §Future](008-Testing.md) for the testing-side forward-pointer.

### Declarative state-scoped child machines

The post-v1 `re-frame.machines` library may surface a `:child-machine` slot on a state node that desugars to entry/exit actions which spawn / destroy a child via the standard `:spawn` / `destroy-machine` mechanism. No new substrate; pure sugar over the v1 surface.

## Disposition

Post-v1 per [000 §Scope and roadmap](000-Vision.md#scope-and-roadmap). The split is on **what's a foundation** vs **what's scaffolding on top of the foundation**.

The v1 ship-list and the post-v1 follow-up are itemised below.

### v1 ships the machine-as-event-handler foundation

- `(create-machine-handler spec)` — pure factory returning an `reg-event-fx`-compatible handler fn that reads/writes the snapshot at `[:rf/machines <id>]`, calls `machine-transition`, lowers `:data` / `:fx` / `:raise` / `:spawn` into a standard effect map. Registers nothing, closes over no global state, does not know its own id. Spec keys: `:initial`, `:data`, `:guards`, `:actions`, `:states`, `:on`, `:meta` — no `:path` (the location is runtime-managed; see [§Where snapshots live](#where-snapshots-live)). The `:guards` and `:actions` maps declare the machine's named guard / action implementations; transition-table keyword references resolve **machine-locally**, validated at registration time.
- `(machine-transition definition snapshot event)` → `[next-snapshot effects]` — pure function. JVM-runnable. No re-frame dependencies; guard/action references resolve against the definition's own `:guards` / `:actions` maps.
- `(spawn-machine spec)` and `(destroy-machine actor-id)` — dynamic actor lifecycle.
- The `:raise` and `:spawn` reserved fx-ids inside `:fx`.
- `[:rf/machines <id>]` as the reserved app-db storage scheme; `:rf/machine?` registration-metadata flag.
- `(rf/machines)` and `(rf/machine-meta id)` — discovery lens over the event registry per [§Querying machines](#querying-machines).
- The framework-registered `:rf/machine` parametric sub and its `sub-machine` wrapper.
- Four-level drain semantics per [§Drain semantics](#drain-semantics) — including the gotchas listed in [§Drain semantics gotchas](#drain-semantics-gotchas).
- The v1 transition-table grammar subset per [§Capability matrix](#capability-matrix) and [§Transition table grammar](#transition-table-grammar).
- The snapshot shape (`{:state :data :meta?}`) and the persist/restore stability invariants per [§Snapshot shape](#snapshot-shape).
- Inspection trace events (`:rf.machine.lifecycle/created`, `:rf.machine/transition`, `:rf.machine/raised`, etc.).
- The `:rf.error/machine-grammar-not-in-v1`, `:rf.error/machine-action-exception`, `:rf.error/machine-action-wrote-db`, `:rf.error/machine-raise-depth-exceeded`, `:rf.error/machine-always-depth-exceeded`, `:rf.error/machine-always-self-loop`, `:rf.error/machine-unresolved-guard`, and `:rf.error/machine-unresolved-action` error categories.
- The `:rf.warning/no-clock-configured` warning category (advisory; emitted when `:after` is exercised on a host whose `re-frame.interop` clock layer hasn't been wired).
- The eventless `:always` capability per [§Eventless `:always` transitions](#eventless-always-transitions): state-node `:always` slot, microstep loop within Level 3 drain, default depth-16 limit, self-loop guard at registration time, dual-granularity trace events.
- The delayed `:after` capability per [§Delayed `:after` transitions](#delayed-after-transitions): state-node `:after` slot accepting `{ms-or-fn → transition-spec}`, epoch-based stale detection (no `:cancel-dispatch-later` fx), SSR no-op rule, clock primitives in `re-frame.interop` (`now-ms`, `schedule-after!`, `cancel-scheduled!`), and the `:rf.machine.timer/scheduled` / `:rf.machine.timer/fired` / `:rf.machine.timer/stale-after` trace events.

### Post-v1 — the `re-frame.machines` library

Richer scaffolding on top of the v1 foundation. None of the items below add a new substrate — each desugars into the v1 surface:

- **Advanced grammar:** parallel state nodes, history states, final states with `onDone`. (Hierarchical state nodes, `:always`, `:after`, and `:invoke` are v1; see the v1 ship list above.)
- **Sugar in transition tables:** `:child-machine` declarative state-scoped child binding (desugars to entry/exit `:spawn` / `destroy-machine`).
- **Stately.ai compatibility:** `(machine->xstate-json definition)` converter, paste-and-render parity, Stately-Inspector wire-format mapping.
- **Visualisation tooling:** `machine->mermaid`, `machine->d2`, `machine->xstate-json` exporters.
- **Model-based testing harness:** `@xstate/test`-style graph traversal over the transition table.
- **Declarative `:history` grammar** (history pseudo-states; substitute is snapshot-as-value capture per [§Substitutes for skipped features](#substitutes-for-skipped-features)).
- **Recurring timers, wall-clock delays, pause/resume on `:after`** — explicitly out of scope for v1; see [§What `:after` does *not* include](#what-after-does-not-include).

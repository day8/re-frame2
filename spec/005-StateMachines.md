# Spec 005 — State Machines

> Status: Drafting. Post-v1 per [000 §Scope and roadmap](000-Vision.md#scope-and-roadmap). Builds on the foundation hooks in [002-Frames.md §State machines are just event handlers](002-Frames.md).
>
> **Why this Spec exists:** state machines and actors are in the pattern because **constrained execution models are easier to reason about than Turing-complete control flow.** A finite state machine has an enumerable state space and a discrete transition relation; an actor system bounds concurrency to message-passing across well-defined boundaries with run-to-completion semantics. This is disproportionately valuable for AI use — an AI can fully simulate a finite machine; it cannot fully simulate a free-form imperative program. The cost is some expressiveness; the benefit is an execution model that survives mechanical reasoning.
>
> For where Levels 1–4 sit in relation to the rest of the runtime (registrar, frame container, sub-cache, substrate adapter, trace bus), see [Runtime-Architecture](Runtime-Architecture.md).
>
> `:invoke` and `:invoke-all` (state-machine actors) are **managed external effects** — per [Managed-Effects](Managed-Effects.md), the surface MUST satisfy the eight properties (effect-as-data, framework-owned actor lifecycle, structured failure taxonomy under `:rf.machine/*`, trace-bus observability, `:sensitive?` / `:large?` composition, built-in retry / abort / teardown via `:after` / `:always` / state-exit, in-flight actor registry, per-frame interceptor scoping).

## Abstract

A state machine in re-frame2 is **an event handler whose body interprets a transition table**. Machines are registered as event handlers via `reg-event-fx + create-machine-handler`; the registered handler is the entire surface. The framework's machine-specific hooks live in 002 — drain semantics, the snapshot shape, the inspection trace surface, the `:raise` reserved fx-id (machine-internal) that the machine handler routes locally, and the `:rf.machine/spawn` / `:rf.machine/destroy` fx-ids (canonical actor-lifecycle).

For readers familiar with xstate, [§Lessons from xstate](#lessons-from-xstate-deliberate-divergences) at the end of this spec lists the divergences inline and forward-points to [CP-5-MachineGuide §Lessons from xstate](CP-5-MachineGuide.md#lessons-from-xstate-deliberate-divergences) for the full divergence table.

## Why machines

Machines serve two distinct use cases:

1. **High-level workflow.** Multi-step user flows (signup → verify → onboard → home), modal dismissal logic, wizard navigation. Without machines these get smeared across many event handlers and an `:app/screen` keyword in `app-db`; the smearing is the pain.
2. **Low-level protocols.** Async resource lifecycles (HTTP request: `idle → loading → success/error/retry`), websocket connection states, animation transitions. Without machines these live as ad-hoc keywords in some sub-tree of `app-db`, with handlers that have to remember "if state is `:loading`, ignore another `:fetch`."

Both want the same primitive but the ergonomics matter differently — workflow machines are few and named (one per major subsystem); protocol machines may have many concurrent instances (one per active resource). The same `create-machine-handler` factory covers both: a singleton machine is registered at boot via `reg-event-fx`; a dynamic instance is registered at run time via the `[:rf.machine/spawn ...]` fx (per [§Spawning — dynamic actors](#spawning--dynamic-actors)).

## Naming — `:state` and `:data`

The snapshot is `{:state :data}`:

- `:state` — the FSM-keyword (`:idle`, `:editing`, `:loading`, ...). Discrete; enumerable; what xstate calls `state.value`.
- `:data` — extended state, the machine's own private memory: a plain map distinct from `app-db`. The term tracks FSM literature and Erlang `gen_statem`'s "state data"; xstate calls the same slot "context".

The pair `{:state :data}` reads as the natural English idiom and matches a vocabulary that's well-represented in AI training corpora. We use `:data` to avoid the existing "context" overloading in re-frame's interceptor pipeline and React-context affordances.

> **`:data` is the parameter name passed to guards and actions, not a destructure key.** Per [§Guards](#guards) / [§Actions](#actions), guards and actions receive `(fn [data event] ...)` — `data` is the snapshot's `:data` slot directly, a plain map. Bodies that read individual fields write `(:circle-id data)`, not `(get-in snapshot [:data :circle-id])`. The 3-arity opt-in `^:rf.machine/wants-ctx (fn [data event {:state :meta}] ...)` adds the introspection slot when needed; users that don't opt in never see the snapshot wrapper.

## Snapshot shape

```clojure
{:state <fsm-keyword-or-path-or-region-map>   ;; :idle | [:checkout :payment :credit-card] | {:data :loading :form :neutral}
 :data  <map>                                  ;; the machine's private memory
 :tags  #{<keyword>, ...}                      ;; OPTIONAL — runtime-projected union of every active state's :tags; see §State tags
 :meta  {<optional> ...}}                      ;; reserved for :rf/snapshot-version etc.
```

The runtime ALSO stamps a closed set of `:rf/*` slots inside the snapshot (some at the snapshot root, some inside `:data`) — the spawn-id counter, the `:after`-epoch counter, the bootstrap flag, the spawned-actor address keys, etc. These are framework-owned and catalogued in [Conventions §Reserved snapshot-internal keys](Conventions.md#reserved-snapshot-internal-keys-machine-runtime); user code MUST NOT write under them.

`:state` has **three arms**, disambiguated by the machine's declared shape:

- **Flat machines** — `:state` is a single FSM-keyword (`:idle`, `:editing`, `:loading`, ...). Equivalent to xstate's `state.value` for a non-compound machine. The flat-machine grammar in [§Transition table grammar](#transition-table-grammar) and the Circle Drawer worked example use this form.
- **Compound machines** — `:state` is a **vector path** from the root state-node to the active leaf (`[:authenticated :cart :browsing]`). The vector form is required when any state in the machine is a compound state (declares its own nested `:states`); it disambiguates "which `:browsing`?" when the same leaf-keyword could appear under multiple parents. See [§Hierarchical compound states](#hierarchical-compound-states).
- **Parallel-region machines** — `:state` is a **map of region-name → that region's keyword-or-vector-path** (`{:data :loading :form :neutral :mode :active}`). The map form is required when the machine declares `:type :parallel`; every region is active simultaneously, and each region's value follows the flat-or-compound arms above. See [§Parallel regions](#parallel-regions).

Implementations accept all three forms on read. The flat / compound arms normalise to a vector path internally for uniform manipulation; the parallel arm is preserved as a map (each region runs its own state-tree). Per rf2-l67o (Nine States Stage 2), the parallel-region arm became first-class.

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

> **Snapshot is lazily initialised.** Registration creates the *handler*, not the snapshot. The first time the machine handler runs (the first dispatched event addressed to this id), the runtime resolves the snapshot via `(or (get-in db [:rf/machines <id>]) <initial-from-spec>)` — so before the first event, `(get-in app-db [:rf/machines :drawer/editor])` returns `nil` and `@(rf/sub-machine :drawer/editor)` returns `nil`. The lifecycle trace `:rf.machine.lifecycle/created` (per [009](009-Instrumentation.md)) is emitted at registration to mark the handler's appearance in the registry — it does NOT imply the snapshot exists in `app-db` yet. Views that need to render before any event reaches the machine should treat `nil` as "not yet initialised" and tolerate it (or seed a fixed initial state via `:on-create` if appearance-without-event is required).

For a spawned actor whose gensym'd id is `:request/protocol#42`:

```clojure
{:rf/machines {:request/protocol#42 {:state :loading :data {:url "/foo"}}}}
```

`:rf/machines` is a **reserved app-db key** (alongside the existing `:rf/*` reserved-keyword convention; see [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). Its value is a `[:map-of :keyword :rf/machine-snapshot]` — keyed by the machine's registered id. User app-db code MUST NOT write under `:rf/machines`.

Why the locked path — the load-bearing reason is [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility): co-locating snapshots in `app-db` is the named mechanism by which machine state inherits revertibility. When a frame's value reverts, every machine snapshot reverts with it. A parallel ActorRef registry or a per-machine atom would put machine state outside the frame's value and break the goal. The five concrete consequences below all flow from that:

1. **Encapsulation.** A machine's snapshot is its private state; the rest of `app-db` is the rest of the app. Co-locating all snapshots under one reserved key keeps the boundary visible at a glance.
2. **No path collisions.** Two features that both want a `[:foo :flow]` machine cannot accidentally share a snapshot location. Ids are already unique within a frame; reusing them as the in-`app-db` key inherits that uniqueness for free.
3. **Tooling.** `(get-in (get-frame-db frame-id) [:rf/machines])` enumerates every live machine snapshot in one read. Pair tools, 10x, and conformance harnesses use this directly.
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

> **The transition-table spec map MUST NOT carry `:id`.** A machine's id is the surrounding registration's event-id (the first arg to `reg-event-fx` or, for dynamic instances, the gensym'd id allocated by the `[:rf.machine/spawn ...]` fx), not a field on the spec map. The runtime derives the id at handler-call time from the dispatched event vector's first element. Keeping `:id` out of the spec map keeps it a pure description of behaviour and lets the same spec value register against multiple ids if the application wants two independent machines with the same body.

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
| `:invoke-all` | per-state | declarative **spawn-and-join** of N parallel child actors (sugar over N `:invoke`s plus a join condition) — see [§Spawn-and-join via `:invoke-all`](#spawn-and-join-via-invoke-all) |
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
 :tags    #{<keyword>, ...}                  ;; runtime-projected onto snapshot's :tags (see §State tags)
 :entry   <fn-or-keyword>                    ;; ran on entering this state; keyword resolves into :actions map
 :exit    <fn-or-keyword>                    ;; ran on exiting this state; keyword resolves into :actions map
 :invoke  <invoke-spec>                      ;; spawn child on entry; destroy on exit (see §Declarative :invoke)
 :invoke-all <invoke-all-spec>               ;; spawn N children in parallel and join (see §Spawn-and-join via :invoke-all)
 :final?  true                               ;; leaf-only — entering this state terminates the machine (see §Final states)
 :output-key <keyword>                       ;; iff `:final?` — designate which `:data` key is reported back via parent's `:on-done`
 :initial <fsm-keyword>                      ;; required IFF the state is itself compound (declares :states)
 :states  {<fsm-keyword> <state-node>, ...}  ;; nested substates — makes this a compound state
 :meta    {<user-keys> ...}}                 ;; user-defined meta (NOT used for terminal marking — see §Final states)
```

All keys are optional except `:initial` (which is required when `:states` is present — see [§Hierarchical compound states](#hierarchical-compound-states)). Capability-gating: `:always`, `:after`, `:tags`, `:invoke`, `:invoke-all`, and `:states` / `:initial` are claimed-capability features per [§Capability matrix](#capability-matrix) — a port that doesn't claim a capability may reject the corresponding keys at registration time with `:rf.error/machine-grammar-not-in-v1`.

A state node MUST NOT declare both `:invoke` and `:invoke-all` — they are mutually exclusive at the same node (a node spawning a single child uses `:invoke`; a node spawning N parallel children uses `:invoke-all`). `create-machine-handler` rejects the combination at registration time as a malformed transition table.

`:entry` and `:exit` are **single fns or single keyword references into the machine's `:actions` map** — never vectors. To run multiple actions on entry, write a fn that calls them in order (or name a compound entry in the machine's `:actions` map; the named id is richer for tooling).

`:invoke` is **declarative sugar** that `create-machine-handler` desugars into entry/exit `:rf.machine/spawn` / `:rf.machine/destroy` fx at registration time; per-state at most one `:invoke`. See [§Declarative `:invoke` (sugar over spawn)](#declarative-invoke-sugar-over-spawn) for the spec-spec keys, desugaring rules, composition with explicit `:entry` / `:exit`, and the deliberate omissions vs xstate.

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

Precedence inside the standard transition lookup, **at each level**:

1. Explicit event match at this level.
2. `:*` wildcard at this level.

The wildcard fires after specific matches **at the same level**. Only if neither matches does the runtime walk up to the next level and try again — so `:*` at the leaf shadows an explicit match on the parent for the same event. The full leaf-up-to-root walk is canonically specified at [§Transition resolution — deepest-wins with parent fallthrough](#transition-resolution--deepest-wins-with-parent-fallthrough); for a flat machine the path is one level deep and the two-step rule above is the whole story. If no level matches, the snapshot is unchanged and the runtime emits a single `:rf.warning/machine-unhandled-event` trace (see [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough) for the canonical name; consistent with the other `:rf.warning/machine-*` advisory categories).

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

The 3-arity overload is **opt-in** via the `^:rf.machine/wants-ctx` metadata flag on the fn:

```clojure
:guard ^:rf.machine/wants-ctx
       (fn [data event {:keys [state meta]}]
         ...)

;; or, for a named guard in the machine's :guards map:
(defn my-guard
  {:rf.machine/wants-ctx true}
  [data event {:keys [state meta]}]
  ...)
```

`{:state :meta}` is the snapshot's introspection slot — the discrete state and any user `:meta`. Use it for the rare guard or action that needs to branch on the current state name (e.g. dispatch on `:state` itself rather than `:data`). The vast majority of guards and actions are state-blind and don't need the third arg; the metadata flag is the explicit signal to the runtime that introspection is wanted.

**Why opt-in.** The 99% case stays monomorphic on `[data event]`, so most fns avoid `:keys [data]` destructure boilerplate at the call site (see the rationale at [§Naming — `:state` and `:data`](#naming--state-and-data)). The 3-arity overload exists precisely so the introspection slot does not bleed into bodies that don't need it.

**Why metadata, not structural arity-detection.** The opt-in is declarative — the user's intent is on the fn itself, not inferred from its arglist shape. Per rf2-2yupx this replaces an earlier structural rule (Java reflection on JVM, compiled-fn-surface introspection on CLJS) that was fragile (a CLJS bug rf2-l04j misclassified 2-plus-rest variadics) and per-call expensive (~80–200ns of reflection per guard or action invocation on JVM). The metadata-driven rule is a single map lookup, platform-uniform, and immune to the variadic-fn footgun: a `(fn [d e & rest] ...)` that wants the ctx flags itself explicitly and the runtime delivers it as the first element of `rest` — the user's intent governs dispatch, not the arglist shape.

**Helper form.** For cases where the reader-macro form is awkward (anonymous fns built by combinators, dynamically-wrapped fns), `re-frame.machines/wants-ctx` attaches the flag programmatically:

```clojure
:guard (machines/wants-ctx (fn [data event ctx] ...))
```

Equivalent to the `^:rf.machine/wants-ctx` form.

**Plain 3-arity without the flag.** A `(fn [data event ctx] ...)` *without* the metadata flag routes through the 2-arity path and the runtime calls `(fn data event)` — which raises an `ArityException` at call time. The arity throw is the deliberate signal: a fn whose body requires three positionals must opt in explicitly. No silent misdispatch.

Compound logic is expressed via function composition or as a named entry in the machine's `:guards` map — the name carries semantic content visualisers and AIs read. Resolution is machine-scoped per [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler); unresolved references fail registration with `:rf.error/machine-unresolved-guard`.

### Actions

An action is **`(fn [data event] effects)`** returning the `{:data :fx}` shape (or `nil`). 2-arity is canonical; 3-arity opt-in is the same `^:rf.machine/wants-ctx (fn [data event {:state :meta}] ...)` escape hatch as for guards. **One inline fn or one keyword reference into the machine's `:actions` map** — never a vector.

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

### `:raise`, `:rf.machine/spawn`, and `:rf.machine/destroy` are reserved fx-ids inside `:fx`

Not separate top-level keys. The machine handler walks `:fx` left-to-right and routes by fx-id:

```clojure
{:fx [[:raise              [:event-1]]                                          ;; back into THIS machine, atomic, pre-commit
      [:raise              [:event-2]]
      [:rf.machine/spawn   {:machine-id :request/protocol
                            :on-spawn   (fn [data id] (assoc data :child id)) ;; how the parent records the new id
                            :start      [:begin]}]                              ;; child actor (see §Spawning)
      [:rf.machine/destroy actor-id]                                            ;; tear down a spawned actor
      [:dispatch           [:other-machine [:notify]]]                          ;; standard re-frame :dispatch
      [:http               {...}]]}                                             ;; any other registered fx
```

Routing rules (per [§Drain semantics](#drain-semantics)):

- `[:raise <event-vec>]` — appended to the machine's local pre-commit raise-queue.
- `[:rf.machine/spawn <spawn-spec>]` — registers a new handler immediately; the new id is fed through the spec's `:on-spawn` to update `:data`; if `:start` is present, an event is queued to the new actor.
- `[:rf.machine/destroy <actor-id>]` — runs the actor's `:exit` action, dissociates its snapshot at `[:rf/machines <actor-id>]`, and clears its event handler from the frame-local registry. Symmetric counterpart to `:rf.machine/spawn`. Used directly by user actions and emitted by the desugaring of `:invoke` on state exit.
- Any other `[fx-id args]` — forwarded to the standard `do-fx` for runtime processing.

`:raise` is machine-internal and unqualified, matching re-frame's existing reserved unqualified fx names (`:dispatch`, `:dispatch-later`). `:rf.machine/spawn` and `:rf.machine/destroy` are namespaced under the framework's `:rf.<feature>/...` convention so user code can register them globally as canonical actor-lifecycle fxs (per [§Top-level boot-time spawn](#top-level-boot-time-spawn-rare)). They are listed in [Conventions.md §Reserved fx-ids](Conventions.md#reserved-fx-ids).

## Strict encapsulation — actions only see their own data

A machine *almost never* needs to write `app-db` directly; it acts on its own state, raises to itself, spawns/messages other actors, or emits fx. The locked rule is **strict encapsulation**: actions and guards cannot see `app-db` at all — only `{:state :data}` plus the event vector.

> **Why this is locked.** Strict encapsulation is one of the named consequences of [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility). If actions could read or write `app-db` outside `[:rf/machines <id>]`, machine logic would create state changes that don't show up in any machine snapshot and don't roll back when the surrounding machine snapshot does. The whole machine's state has to live inside the frame's persistent value to revert with it; encapsulation is what stops machines from leaking state into parts of the value that aren't theirs.

- **Action signature:** `(fn [data event] effects)` — 2-arity canonical; 3-arity opt-in is `^:rf.machine/wants-ctx (fn [data event {:state :meta}] effects)`.
- **Guard signature:** `(fn [data event] boolean)` — 2-arity canonical; 3-arity opt-in is `^:rf.machine/wants-ctx (fn [data event {:state :meta}] boolean)`.
- **What the fn sees:** the snapshot's `:data` slot directly (a map). The full `{:state :data :meta}` snapshot is reachable only via the 3-arity opt-in. Never `app-db`; never cofx.

The impure plumbing (reading the snapshot from `app-db` at `[:rf/machines <id>]`, writing `:data` back as a `:db` write, lowering `:fx` / `:raise` / `:rf.machine/spawn` into standard re-frame effects) lives in the *handler boundary* — the fn returned by `create-machine-handler`. **Inside the boundary: pure. Outside: standard re-frame.**

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
| `:after` delay-fn | `(fn [snapshot] ms)` | the **whole snapshot** (`{:state :data :meta?}`) | a positive-int millisecond delay |
| `:invoke :data` fn | `(fn [snapshot event] data)` | the **whole snapshot** plus the entering event vector | the child's initial data map |

The runtime is responsible for unwrapping the snapshot before calling these fns and for patching the result back into the snapshot. **User code never names `[:data ...]` paths inside the body**; if a callback needs to read or write a field, it does so on `data` directly (e.g. `(:pending data)`, `(assoc data :pending id)`).

> **Asymmetry note — the last two rows take the whole snapshot, not `:data`.** `:after` delay-fns and `:invoke :data` fns receive the wrapping snapshot because they need access to `:state` (the entering leaf path) for parameterising delay or child-data on hierarchical position; the 3-arity escape hatch on `:guard` / `:action` exists for the same reason but as opt-in. The deliberate asymmetry is documented here so port authors implement it explicitly. Bodies that only need `:data` should pull it via `(:data snapshot)` at the call site.

The same principle holds for any data DSL the conformance corpus or a tooling layer interprets on top of the surface: a `:set` step inside a body operates on `:data`, so its path is data-relative. `[:set [:pending] x]` writes `data.:pending = x`. `[:set [:data :pending] x]` would write `data.:data.:pending = x`, which is virtually never what's wanted.

### 3-arity escape hatch — snapshot introspection

When a callback truly needs the discrete `:state` or any user `:meta` (rare), opt in via the `^:rf.machine/wants-ctx` metadata flag and declare the third parameter:

- `:guard ^:rf.machine/wants-ctx (fn [data event {:keys [state meta]}] ...)`
- `:action ^:rf.machine/wants-ctx (fn [data event {:keys [state meta]}] ...)`

`:on-spawn` doesn't currently take an introspection slot — the snapshot's `:state` at spawn time is the entry-bearing leaf state by definition, so the slot would carry no information beyond the lexical position of the `:invoke`. If a future use case needs it, the same metadata-driven opt-in pattern applies.

#### Dispatch rule — metadata opt-in (`:rf.machine/wants-ctx`)

The 3-arity overload is **explicitly opted-in via metadata** on the guard / action fn itself. The runtime's dispatch rule is one line:

> **A guard or action fn is called with the 3-arity `(data event ctx)` signature iff `(:rf.machine/wants-ctx (meta f))` is truthy. Otherwise it is called with the 2-arity `(data event)` signature.**

`ctx` is the introspection map `{:state <snapshot's-:state> :meta <snapshot's-:meta>}` — a thin projection of the wrapping snapshot, never the snapshot itself. (`:data` is already the first positional parameter; passing it again under `ctx` would invite the footgun of two divergent copies.)

Three value-equivalent ways to attach the flag:

```clojure
;; (1) inline metadata on the fn literal
:guard ^:rf.machine/wants-ctx (fn [data event ctx] ...)

;; (2) defn attr-map for a named guard / action
(defn my-guard {:rf.machine/wants-ctx true}
  [data event ctx] ...)

;; (3) the wrapper helper — for combinators and anonymous fns where
;;     attaching reader-macro metadata is awkward
:guard (machines/wants-ctx (fn [data event ctx] ...))
```

`re-frame.machines/wants-ctx` is the public helper that sugar-attaches the metadata flag via `vary-meta`; it is equivalent to the reader-macro form on a fn-literal and exists for cases where the literal form does not parse (anonymous fns built by reduce, combinator fns, etc.).

The opt-in is metadata-driven (not structurally arity-detected). Consequences:

- **Explicit user intent.** The fn's signature alone never decides; the user states "I want ctx" via the flag. A `(fn [data event _] ...)` without the flag is called with 2 args and the third parameter is unbound — same as any other 2-arity call against a 3-arg fn under Clojure's per-arity dispatch.
- **No platform reflection.** The dispatch check is a `(boolean (:rf.machine/wants-ctx (meta f)))` map lookup — no `(.getDeclaredMethods f)` on the JVM, no `(unchecked-get f "cljs$lang$maxFixedArity")` on CLJS. The rule is platform-uniform.
- **Variadic fns are unambiguous.** A `(fn [data event & rest] ...)` that wants ctx attaches the flag; the same fn without the flag stays in the 2-arity camp. The previous structural rule had to special-case variadics; the metadata rule does not.
- **Metadata survives `chase-ref`.** When a guard / action slot carries a keyword reference into the machine's `:guards` / `:actions` map, the runtime's reference-chase returns the fn value with its metadata intact — the opt-in attached at definition site carries through to the call site.

Per rf2-2yupx this replaces the earlier structural arity-detection rule (which inspected `.getDeclaredMethods` on JVM / `cljs$lang$maxFixedArity` on CLJS and routed variadics through the 2-arity path). The metadata rule is the normative dispatch contract — conformant implementations MUST consult the `:rf.machine/wants-ctx` metadata flag and MUST NOT introspect the fn's arglist shape.

The `ctx` projection's shape — `{:state :meta}`, no other keys — is closed. Future runtime introspection slots (if any) extend the projection by Spec change, not by user contribution.

## Registration — the machine IS the event handler

A machine is registered as **one event handler** via `reg-event-fx` whose body comes from `create-machine-handler`.

```clojure
(rf/reg-event-fx :drawer/editor
  {:doc "Modal-edit flow."}
  [undoable]
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
- `:on-spawn :record-pending` (when `:on-spawn` appears as a keyword reference, e.g. inside an `:invoke` slot) → resolved against an optional `:on-spawn-actions` map at the spec root if present, then falling back to `:actions`. Inline fns work as for `:action`. The `:on-spawn-actions` map is intended for spawn-callbacks whose role is the parent's id-recording side, distinct from transition-time `:actions`; declaring it is optional and the fallback to `:actions` keeps single-map machines simple.

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
- **Does not know its own event id.** The handler's id is bound by the surrounding `reg-event-fx` (or by the `[:rf.machine/spawn ...]` fx for dynamic instances).

This is a real constraint on the implementation, not just a testing affordance — it's what makes the singleton vs spawned symmetry clean (the registration happens *outside* the factory in both cases) and what makes Level-2 testing (per [§Testing](#testing)) possible without a test frame.

### `reg-machine` — public registration surface

Alongside the underlying `reg-event-fx + create-machine-handler` form (per [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler)), the framework ships **`reg-machine`** as the standard public registration entry point for machines. Both forms register the same thing — an event handler whose body interprets the transition table — and they reach the same registry slot. `reg-machine` is the surface that tools, examples, and CP-5-generated scaffolds default to.

```clojure
(rf/reg-machine :auth.login/flow
  {:initial :idle
   :data    {:attempts 0 :error nil}
   :guards  {:under-retry-limit (fn [data _] (< (:attempts data) 3))}
   :actions {:begin-submit       (fn [_ [_ creds]] {:fx [[:http {...}]]})
             :record-failure     (fn [data _]      {:data {:attempts (inc (:attempts data))}})}
   :states  { ... }})
```

**Surface signature.** Two arities of two forms:

- `(rf/reg-machine machine-id machine)` — **macro**. Walks the literal spec form at expansion time and stamps a per-element source-coord index onto the spec's `:rf.machine/source-coords` key (per [§Source-coord stamping](#source-coord-stamping-rf2-8bp3)). The macro emits `(reg-machine* …)` after stamping; the runtime call site is the plain-fn surface.
- `(rf/reg-machine* machine-id machine)` — **plain fn**. Equivalent to `(reg-event-fx machine-id (create-machine-handler machine))` plus the registration-metadata stamp. No source-coord walking — the spec is opaque data at the call site.

Both forms live in `re-frame.machines` (the `day8/re-frame2-machines` artefact, per [Conventions.md](Conventions.md)) and are re-exported under `re-frame.core` for both JVM and CLJS callers. See [API.md §Machines](API.md#machines) for the canonical API table.

Both forms return `machine-id` per the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

**Registration-metadata stamp.** Both forms record two keys on the registry slot's metadata map (per [001 §Metadata-map shape](001-Registration.md)):

- `:rf/machine? true` — the discriminator. `(rf/machines)` filters `(handlers :event)` by this flag (per [§Querying machines](#querying-machines)). User-written event handlers do not set this key.
- `:rf/machine <spec>` — the spec map passed to `reg-machine`. `(rf/machine-meta id)` reads this back; tools that walk the transition table (visualisers, conformance harnesses, CP-5-time scaffolders) consume the spec via this key. When the macro path stamps source coords, the `:rf.machine/source-coords` index lives inside this spec map.

Source-coord stamping on the call site (`:ns` / `:line` / `:column` / `:file`) follows the standard rules from [001 §Source-coord stamping](001-Registration.md): the macro stamps; programmatic registration via `reg-machine*` does not. See [§Source-coord stamping](#source-coord-stamping-rf2-8bp3) for the per-element index.

`reg-machine` is itself **late-bound** — `re-frame.core` carries the macro and a stub fn that resolves the producer through the late-bind hook table at registration time. Apps that use `reg-machine` MUST add `day8/re-frame2-machines` to their deps and require `re-frame.machines` at app boot; without it, the lookup throws `:rf.error/machines-artefact-missing`.

### `reg-machine` vs `reg-machine*`

The `reg-machine` convenience surface splits along Clojure's `let` / `let*`, `fn` / `fn*` idiom:

| Form | Shape | Source-coord stamping | Use case |
|---|---|---|---|
| `(rf/reg-machine machine-id machine-spec)` | **macro** | Yes — call-site coords on the registry slot, AND per-element coord index walked from the literal spec form (per [§Source-coord stamping](#source-coord-stamping-rf2-8bp3)) | Standard form. The literal-spec contract enables the macro to walk and stamp at expansion time. |
| `(rf/reg-machine* machine-id machine-spec)` | plain fn | None — the call-site predates the registration; the spec is opaque data | Code-gen pipelines that produce specs at runtime, REPL exploration, conformance harnesses that synthesise machines from EDN fixtures. |

The macro lives at the `re-frame.core` boundary; the plain-fn surface lives in `re-frame.machines/reg-machine*` and is exposed publicly under `re-frame.core/reg-machine*` for both JVM and CLJS programmatic callers. The macro emits `(reg-machine* …)` after stamping; the runtime never reaches both surfaces independently.

### Source-coord stamping (rf2-8bp3)

When the `reg-machine` macro receives a literal-map spec form, it walks the form at expansion time and attaches a flat coord index under the spec's `:rf.machine/source-coords` key. Tools (re-frame-pair, re-frame-10x, IDE jump-to-source) read the index back via `(:rf.machine/source-coords (rf/machine-meta machine-id))`.

#### What gets stamped

The index is a flat map of **path-tuple → coord-map**:

```clojure
{[:guards :form-valid?]
 {:ns sym :file "path/login.cljs" :line 47 :column 13}

 [:actions :commit]
 {:ns sym :file "path/login.cljs" :line 52 :column 13}

 [:states :idle :on :submit]
 {:ns sym :file "path/login.cljs" :line 80 :column 23}

 [:states :idle :on :submit :guard]
 {:ns sym :file "path/login.cljs" :line 80 :column 35}}    ; only when slot is an inline-fn literal
```

Two axes are stamped:

1. **Definition sites** — each fn literal under `:guards` / `:actions` / `:on-spawn-actions` is keyed by `[:guards <id>]` / `[:actions <id>]` / `[:on-spawn-actions <id>]`. This is the coord tools navigate to for "jump to definition."

2. **Reference sites** — each transition map / state-node / inline-fn slot inside `:states` is keyed by its full spec-path tuple, e.g. `[:states :idle :on :submit]`. This is the coord tools navigate to for "jump to call site."

#### Keyword reference rule (the exemption case)

For a keyword reference like `:guard :form-valid?` inside a transition, the **definition-site is stamped, the reference-site slot is not**. Rationale: a keyword (`:form-valid?`) is a name, not a source form — it carries no reader metadata of its own. The closest meaningful coord is the **enclosing transition map's** coord, which IS stamped under the transition's path. Synthesising a duplicate slot entry at the same coord adds no information for tools — they walk the path tree to find the closest ancestor coord.

For an **inline-fn reference** like `:guard (fn [_ _] ...)`, the fn-form carries its own reader meta, so the reference-site slot IS stamped at the full path with a distinct coord.

Concretely for `{:on {:submit {:target :done :guard :form-valid? :action (fn [_ _] {})}}}`:

| Path | Stamped? | Why |
|---|---|---|
| `[:guards :form-valid?]` | ✓ (when defined) | fn literal carries reader meta |
| `[:states :idle :on :submit]` | ✓ | transition map carries reader meta |
| `[:states :idle :on :submit :guard]` | — | `:form-valid?` is a keyword — no meta |
| `[:states :idle :on :submit :action]` | ✓ | inline-fn literal carries reader meta |

Tools walking the index pick the deepest stamped path-tuple matching their UI gesture; for a "jump to call site" click on a state's `:guard`, they fall back to the enclosing transition's coord (which is the same source line).

#### Reading the index back

```clojure
(:rf.machine/source-coords (rf/machine-meta :auth/login))
;; {[:guards :form-valid?]                {:ns ... :line ... :column ... :file ...}
;;  [:actions :commit]                    {...}
;;  [:states :form :on :submit]           {...}
;;  [:states :form :on :submit :action]   {...}}
```

The top-level call-site coords (the position of the `(rf/reg-machine ...)` form itself) live on the registry slot as `:ns` / `:line` / `:column` / `:file`, queryable via `(rf/handler-meta :event machine-id)`. The two surfaces are independent: a tool that wants to highlight the `reg-machine` declaration uses `handler-meta`; a tool that wants to highlight a transition's source line uses `machine-meta`'s coord index.

#### Production elision

The macro emits an `(if interop/debug-enabled? (assoc spec :rf.machine/source-coords {...}) spec)` branch. Under `:advanced` + `goog.DEBUG=false` the closure compiler constant-folds the gate to `false` and DCEs the entire literal coord index. The keyword `:rf.machine/source-coords` and every spec-element string fragment (the `:ns` symbol values, the `:file` strings) are absent from the production bundle. Verified by the `npm run test:elision` sentinel grep.

#### JVM caveat

Clojure's `LispReader` only attaches `:line` / `:column` metadata to *list* forms (function calls, `(fn …)` bodies). Map and vector literals do NOT carry reader meta on JVM. So on JVM the walker stamps definition-site fn literals reliably (under `:guards` / `:actions` / `:on-spawn-actions`) but state-node and transition-map coords are unavailable — those need the CLJS reader (cljs.tools.reader, which DOES decorate maps/vectors). Per Goal 1 (CLJS reference) the tooling-facing path is CLJS-side; the JVM caveat affects only JVM-side tooling that walks the index directly.

#### Programmatic registration

`reg-machine*` (the plain-fn surface) and any `reg-machine` macro call where the spec arg is a non-literal (a symbol, a let-bound expression) skip the per-element walk: there's no literal tree to walk at expansion time. The registered spec carries no `:rf.machine/source-coords` key in those cases — tools fall back to the call-site coords on `handler-meta`.

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
- **Pure factory:** `(create-machine-handler spec) → fn`. Returns a re-frame event-handler fn whose construction is a pure value transform of `spec` — its identity (the surrounding `reg-event-fx` id, or the `[:rf.machine/spawn ...]`-supplied id) is bound by the caller.
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
  - `[:rf.machine/spawn <spawn-spec>]` → registers a new handler immediately (each spawn happens before the next `:fx` entry is processed; the spawned id is fed through the spec's `:on-spawn` callback to update `:data`; if `:start` is present, an event is queued to the new actor).
  - any other `[fx-id args]` → forwarded to the standard `do-fx` for runtime processing.

The relative order of `:raise` entries in `:fx` is the order they enter the local raise-queue. The relative order of non-raise fx entries is the order they reach `do-fx`.

This Level-1 walk is the machine-layer instance of the runtime-wide [`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees) — `:db` (here, the lowered `:data` write) commits before any `:fx` entry, `:fx` entries process in source order, each entry's fx-handler returns before the next begins, and an fx-handler exception traces independently without halting subsequent entries. `:raise` is routed locally to the machine's pre-commit queue; `:rf.machine/spawn` and `:rf.machine/destroy` reach `do-fx` like any other registered fx (see [§`:raise`, `:rf.machine/spawn`, and `:rf.machine/destroy` are reserved fx-ids inside `:fx`](#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx)).

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
- `:fx` entries concatenate in slot order. Within the concatenated `:fx`, the Level-1 walk (`:raise` → local queue, `:rf.machine/spawn` / `:rf.machine/destroy` and the rest → `do-fx`) preserves order.

### Level 3 — within a single machine event

When a machine receives an event:

1. Resolve which transition fires (guards evaluated left-to-right; first match wins).
2. Run the action group (Level 2).
3. **Drain the raise-queue**: pop the front, dispatch it through the same machinery (Level 3 recursion), accumulate its `:fx` (including any `:rf.machine/spawn` / `:rf.machine/destroy` entries) into the same outer accumulator. Continue until the raise-queue is empty.
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

#### Initial-state `:entry` fires on machine bootstrap (rf2-0z73)

When a machine **first comes into existence** — a singleton on its first dispatched event, or a spawned actor on `:rf.machine/spawn` — the initial-state cascade's `:entry` actions fire **once, shallowest-first**, as part of bringing the machine to life. For a flat machine that means the single initial state's `:entry` runs; for a compound machine with a multi-level `:initial` chain, **every** state along the chain runs its `:entry` in shallowest-first order.

```clojure
{:initial :outer
 :states  {:outer {:entry   :enter-outer        ;; fires on bootstrap
                   :initial :mid
                   :states  {:mid  {:entry   :enter-mid     ;; fires on bootstrap
                                    :initial :leaf
                                    :states  {:leaf {:entry :enter-leaf}}}}}}}
;; bootstrap log: [:enter-outer :enter-mid :enter-leaf]
```

The bootstrap cascade composes with **all** the slots the entry cascade carries — `:invoke`, `:invoke-all`, `:after` on any node along the initial chain emit their corresponding fx (`:rf.machine/spawn`, `:rf.machine/invoke-all-init`, `:after-schedule`) at bootstrap time. So a `:requesting` initial state that declares `:entry :fire-request` AND `:invoke {:machine-id :rf.http/managed ...}` has the entry action run AND the child machine spawned, before the actor's first user-routed event arrives.

For singleton machines the bootstrap fx flow out as part of the **first event's** handler return value (the bootstrap cascade and the first event's transition cascade share the same `:fx` accumulator). For spawned actors the bootstrap fires when the runtime dispatches the actor's first event — the synthetic `[:rf.machine/spawned]` per [§Synthetic `[:rf.machine/spawned]` on spawn (rf2-ijm7)](#synthetic-rfmachinespawned-on-spawn-rf2-ijm7), or the user-supplied `:start` per [§Spawn-spec keys](#spawn-spec-keys).

**Error semantics.** A throw inside any initial-`:entry` action halts the bootstrap identically to a throw inside any other entry cascade: the snapshot does NOT commit, no `:fx` flow, and a single `:rf.error/machine-action-exception` trace fires (per [§Errors](#errors)). The pre-bootstrap state — no snapshot at `[:rf/machines <id>]` — is preserved.

**Migration note.** Pre-rf2-0z73, initial-state `:entry` actions did NOT fire on bootstrap. Generic child machines that needed to do work on spawn worked around it by declaring `:on :rf.machine/spawned :action :fire-request` on the initial state (the synthetic event the runtime dispatches per rf2-ijm7). Post-rf2-0z73 the canonical shape is `:entry :fire-request` — the `:rf.machine/spawned` workaround still works (it just resolves as a no-op transition through the standard `:on` lookup), but new code should prefer `:entry`.

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

Out of scope of *this section* — see the cross-reference for each:

- **Parallel regions** — first-class capability per [§Parallel regions](#parallel-regions); the N-machines-per-region substitute in [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) remains the right answer when regions are independent features.
- **History pseudo-states** — substitute: snapshot-as-value capture per [§Substitutes for skipped features](#substitutes-for-skipped-features).
- **`onDone` final-state notification** — substitute: explicit `[:raise ...]` from the leaf state's `:entry`.

`:always`, `:after`, and `:invoke` are all specified independently of the hierarchy work above (see [§Eventless `:always` transitions](#eventless-always-transitions), [§Delayed `:after` transitions](#delayed-after-transitions), and [§Declarative `:invoke` (sugar over spawn)](#declarative-invoke-sugar-over-spawn)). All three are state-node keys whose semantics compose with the hierarchical entry/exit cascade described above.

## Parallel regions

A machine may declare `:type :parallel` at the root and a `:regions` map keyed by region name. Each region is a **full state-tree** (its own `:initial`, `:states`, optional `:on` / `:tags` / `:after` / `:invoke` / `:always` on each state node). All regions are active simultaneously when the machine is active; the snapshot's `:state` is a **map** of region-name → that region's keyword-or-vector-path; transitions are **broadcast** across regions (every region's active state-node independently decides whether the event matches one of its `:on` keys); the run-to-completion macrostep drain settles every region before the snapshot commits.

xstate/SCXML term: **parallel state** / `<parallel>`. The motivating use case is **orthogonal axes of one feature** — one form with three independent axes (data cardinality / form validity / display mode), one widget with display + interaction state, one page whose render-mode is a function of three independent inputs. Parallel regions avoid the AND-state combinatorial explosion: three axes of three states each shrink from `3^3 = 27` flat states to nine states across three regions.

```clojure
(rf/reg-machine :ui/nine-states
  {:type    :parallel
   :data    {:items [] :error nil}                            ;; shared across all regions
   :guards  {:empty? (fn [d _] (zero? (count (:items d))))}
   :actions {:bump   (fn [d _] {:data (update d :count inc)})}
   :regions
   {:data
    {:initial :nothing
     :states  {:nothing  {:tags #{:data/idle} :on {:fetch :loading}}
               :loading  {:tags #{:data/loading :data/transient}
                          :on   {:loaded :resolving :failed :error}}
               :resolving {:always [{:guard :empty? :target :empty} {:target :some}]}
               :empty    {:tags #{:data/empty}}
               :some     {:tags #{:data/some}}
               :error    {:tags #{:data/error}}}}
    :form
    {:initial :neutral
     :states  {:neutral   {:tags #{:form/neutral}   :on {:submit-invalid :incorrect
                                                          :submit-valid   :correct}}
               :incorrect {:tags #{:form/invalid}   :on {:edit :neutral}}
               :correct   {:tags #{:form/success}   :on {:edit :neutral}}}}
    :mode
    {:initial :active
     :states  {:active {:tags #{:mode/active}   :on {:archive :done}}
               :done   {:tags #{:mode/done :mode/terminal}}}}}})
```

After the machine has settled at every region's `:initial`, the snapshot is:

```clojure
{:state {:data :nothing :form :neutral :mode :active}
 :data  {:items [] :error nil :count 0}
 :tags  #{:data/idle :form/neutral :mode/active}}
```

### When to reach for parallel regions

Parallel regions are for the **multi-axis-of-one-domain** case: one form with three orthogonal axes (data / form / mode), one connection with auth + lifecycle + request-queue, one widget with display + interaction. They share a single `:data` blob because the axes share a domain — the data the regions read and write is the *same data*, just sliced differently by each region's state.

If your axes are conceptually separate features (multiple tabs each with their own state, boot phases plus diagnostics, an audio/video player whose two regions share nothing but the play/pause event), you don't want parallel regions — you want **N separate machines** colocated in app-db. See [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) for the N-machine pattern and worked example. Per rf2-l67o §9.4 (Shared `:data` lock), per-region `:data` is **not** supported; if your axes need encapsulated `:data`, that's the substrate telling you to register N machines, not retrofit per-region data into one parallel machine.

### Snapshot shape

The snapshot's `:state` becomes the **third arm** described in [§Snapshot shape](#snapshot-shape) — a map of region-name → that region's keyword-or-vector-path:

```clojure
;; flat region — the value is a keyword
{:state {:data :loading :form :neutral :mode :active} ...}

;; compound region — the value is a vector path INSIDE that region
{:state {:auth [:authenticated :dashboard] :lifecycle :idle} ...}
```

Nested parallel regions (a region whose own state-tree declares `:type :parallel`) are not supported in v1. The validator rejects them at registration with `:rf.error/machine-parallel-nested-not-supported`. Two-level nesting can be modelled as a flatter cross-product or, more idiomatically, as multiple top-level parallel-region machines.

The `:data` slot is **shared** across every region — there is no `:data` slot on a region body, and there is no per-region `:data` slot inside the snapshot. Region states see and write the same `:data` map; the action-effect contract is unchanged (`(fn [data event] {:data {...}})`).

### Initial state

The initial snapshot's `:state` is the map of region-name → that region's initial cascade. Each region's `:initial` is required (just like a top-level flat machine's `:initial`); a region body whose own root is a compound state cascades through that region's `:initial` chain (per [§Initial-state cascading](#initial-state-cascading)). Each region's `:entry` cascade runs once at machine boot.

```clojure
;; given the :ui/nine-states example above:
(@(rf/sub-machine :ui/nine-states))
;; => {:state {:data :nothing :form :neutral :mode :active}
;;     :data  {:items [] :error nil}
;;     :tags  #{:data/idle :form/neutral :mode/active}}
```

### Transition broadcast

Every event delivered to a parallel-region machine is **broadcast** to every region. Each region resolves the event through its own active state's deepest-wins lookup (per [§Transition resolution](#transition-resolution--deepest-wins-with-parent-fallthrough)) — region A's active state checks its `:on`, region B's active state checks its `:on`, and so on, independently. The runtime collects each region's resolved transition, applies them in region-declaration order against the shared `:data` (so each region's action sees the prior region's `:data` writes), and commits the merged result.

Three outcomes per region:

- **Region's state has a matching `:on` entry whose guard passes.** That region transitions: exit cascade → action → entry cascade. `:fx` accumulated by the region's actions joins the macrostep's `:fx` vector.
- **Region's state has no matching `:on` entry.** That region's `:state` is unchanged. No `:rf.warning/machine-unhandled-event` fires *unless every region declines the event* (see below).
- **Region's matching `:on` entry has a guard that returns false.** Same as "no match" — region stays put, no warning fires for that region alone.

If **every region** declines the event (no region matched a transition), the machine as a whole emits `:rf.warning/machine-unhandled-event` exactly once, matching the flat-machine semantics. If **any** region handled the event, the snapshot commits with that region's transition applied and the warning is suppressed.

The post-broadcast snapshot's `:state` is the map of region-name → that region's new state value. Regions that didn't transition keep their prior value in place.

### Per-region `:always` / `:after` / `:invoke` scoping

Each region's state-node keys (`:always`, `:after`, `:invoke`, `:entry`, `:exit`) operate **scoped to that region**:

- **`:always`** — the macrostep's microstep loop runs **per region**. After a region's event-driven transition, that region's new state's `:always` entries are checked; matching guards fire transitions in that region. Other regions are not re-evaluated for `:always` on a sibling region's microstep; their own `:always` checks fire when that region itself transitions. Each region's microstep cascade settles to its own fixed point before commit.
- **`:after`** — an `:after` timer is scheduled / cancelled when **its region's state** entry / exit fires. One region's timer firing dispatches `[:rf.machine.timer/after-elapsed delay-key epoch]` back into the parent; the broadcast routes the synthetic event to every region; the bearing region picks it up via `pick-after-transition` (per [§Delayed `:after` transitions](#delayed-after-transitions)); sibling regions decline the synthetic event and stay put.
- **`:invoke`** — a region's `:invoke`-bearing state spawns / destroys actors **bound to that region's state**. The runtime-owned tracking slot at `[:rf/spawned <parent-id> <invoke-id>]` (per [§Declarative `:invoke`](#declarative-invoke-sugar-over-spawn)) uses an `:invoke-id` that prefixes the region name onto the in-region prefix-path. Sibling regions never see the spawn / destroy cascade.
- **`:entry` / `:exit`** — fire on the region's own transitions, never on a sibling region's transitions.

### Tags compose across regions

A parallel-region machine's `:tags` slot on the snapshot is the **union of every active state's `:tags`** across every active region. Tag union (per [§State tags](#state-tags)) extends naturally:

- For each region, walk the region's active configuration (root → leaf for compound regions; the single state for flat regions); union every active state-node's `:tags`.
- Across regions, union the per-region results.

```clojure
;; given the example above, after settling the initial state:
;; - region :data is at :nothing, which carries #{:data/idle}
;; - region :form is at :neutral, which carries #{:form/neutral}
;; - region :mode is at :active, which carries #{:mode/active}
;; → snapshot's :tags is #{:data/idle :form/neutral :mode/active}
```

The framework sub `:rf/machine-has-tag?` (per [§Querying tags](#querying-tags--the-rfmachine-has-tag-sub)) works unchanged — it asks "does the union contain this tag?" and the answer is yes iff any active state-node across any region declared the tag.

### Worked example — broadcast, shared `:data`, tags compose

```clojure
;; Both regions react to :reset; the action lives in the parent's :actions
;; map and is referenced from each region's :reset transition.
(rf/reg-machine :ui/example
  {:type    :parallel
   :data    {:count 0}
   :actions {:bump-count (fn [d _] {:data (update d :count inc)})}
   :regions
   {:left
    {:initial :a
     :states  {:a {:tags #{:left/a} :on {:reset {:target :a :action :bump-count}}}}}
    :right
    {:initial :x
     :states  {:x {:tags #{:right/x} :on {:reset {:target :x :action :bump-count}}}}}}})

;; Initial snapshot:
;; {:state {:left :a :right :x} :data {:count 0} :tags #{:left/a :right/x}}

(rf/dispatch-sync [:ui/example [:reset]])
;; Both regions handle :reset (self-transition + :bump-count). The action
;; runs ONCE PER REGION against the shared :data — :count goes 0 → 1 → 2.
;; Snapshot after the macrostep:
;; {:state {:left :a :right :x} :data {:count 2} :tags #{:left/a :right/x}}
```

The action is run by each region that handles the event; shared `:data` flows through each region's actions sequentially. If you want an event to count once, register a coordinating action at the parent-machine level rather than per-region, or set up the regions so only one handles the event.

### Capability gating

Parallel regions are claimed as `:fsm/parallel-regions` in the v1 CLJS reference per [§Capability matrix](#capability-matrix). Ports that don't claim it raise `:rf.error/machine-grammar-not-in-v1` on `:type :parallel` at registration time. The schema extension (`:rf/state-node` gaining `:type` + `:regions`; `:rf/machine-snapshot`'s `:state` widened to the third arm) is documented in [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) and [§`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot).

### Substitutes — when to use N machines instead

As noted in [§When to reach for parallel regions](#when-to-reach-for-parallel-regions), parallel regions are the right answer when the regions are orthogonal axes of one feature with one shared `:data`. The N-machines-per-region substitute documented in [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) — separate `[:rf/machines <id>]` entries coordinated via cross-actor dispatch — is the right answer when the regions are conceptually independent features that don't share data. Both patterns ship together; choose by domain shape.

### Trace events

Parallel-region transitions emit one `:rf.machine/transition` macrostep trace per dispatched event (matching flat / compound machines). The trace's `:before` and `:after` payloads carry the full snapshot (including the `:state` map shape). The internal per-region transitions and their microsteps surface through the per-region `:rf.machine.microstep/transition` events (per [§Eventless `:always` transitions §Trace events](#trace-events_1)); each carries a `:region` tag identifying which region produced the microstep so consumers can subscribe to per-region streams.

### Cross-references

- [§Snapshot shape](#snapshot-shape) — the three-arm `:state` form.
- [§State tags](#state-tags) — tag union extends across regions.
- [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) — the N-machines-per-region pattern for the independent-features case.
- [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) — `:type` + `:regions` schema.
- [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) — `:state` widened.
- [Pattern-NineStates](Pattern-NineStates.md) — the motivating pattern (rewritten in Stage 3 / rf2-c7wl).
- [conformance/fixtures/parallel-flat-two-regions.edn](conformance/fixtures/parallel-flat-two-regions.edn),
  [parallel-compound-region.edn](conformance/fixtures/parallel-compound-region.edn),
  [parallel-tags-union-across-regions.edn](conformance/fixtures/parallel-tags-union-across-regions.edn),
  [parallel-broadcast-event-both-regions.edn](conformance/fixtures/parallel-broadcast-event-both-regions.edn),
  [parallel-invoke-scoped-to-region.edn](conformance/fixtures/parallel-invoke-scoped-to-region.edn),
  [parallel-after-scoped-to-region.edn](conformance/fixtures/parallel-after-scoped-to-region.edn),
  [parallel-always-cascade-per-region.edn](conformance/fixtures/parallel-always-cascade-per-region.edn),
  [parallel-initial-state-per-region.edn](conformance/fixtures/parallel-initial-state-per-region.edn),
  [parallel-snapshot-round-trip.edn](conformance/fixtures/parallel-snapshot-round-trip.edn),
  [parallel-ssr-hydration.edn](conformance/fixtures/parallel-ssr-hydration.edn) — conformance fixtures.

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

## State tags

A state node may declare `:tags <set-of-keywords>`. At every transition, the runtime walks the active configuration, unions every active state-node's tag set, and stamps the result onto the snapshot at `:tags`. A framework sub asks the predicate question — "does this machine's snapshot carry this tag?" — without enumerating every nested path that contributes to the answer.

The motivating use case is the **Nine States pattern** ([Pattern-NineStates.md](Pattern-NineStates.md)): a page-level convention whose render decisions slice across three orthogonal axes (data cardinality, form validity, mode). Tags carry the per-axis intent (`:data/loading`, `:form/invalid`, `:mode/done`) so view-level subs can ask query-shaped questions without inventing N boolean discriminator subs per state.

```clojure
{:initial :editing
 :states
 {:editing  {:tags #{:active :editable}
             :on   {:archive :archived}}
  :archived {:tags #{:done :read-only :terminal}}}}
```

After entering `:archived`, the snapshot looks like:

```clojure
{:state :archived
 :data  {<...>}
 :tags  #{:done :read-only :terminal}}
```

### Semantic contract

- `:tags` is a **set of keywords** on a state node. Order doesn't matter; duplicates collapse. The implementation tolerates the obvious alternative shapes (a vector or single keyword) by coercing to a set; the canonical form is `[:set :keyword]` per [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rfstate-node).
- The runtime computes `(apply set/union (map :tags active-state-nodes))` at every transition commit (after `:always` microsteps reach fixed point, before the macrostep's `:rf.machine/transition` trace fires — so traces carry the new tag set) and stores the result at `[:rf/machines <id> :tags]` on the snapshot.
  - For a **flat** machine the active set is the single named state.
  - For a **compound** machine it's every state along the active path (root → every compound ancestor → leaf). This matches XState/SCXML's "any state in the active configuration carrying the tag is enough."
- `:tags` is **read-only** for users. Actions cannot return `:tags` in their effect map; the runtime owns the slot. It is a pure projection of `:state` — set the state, the tags follow.
- The framework reserves the `:rf/*` and `:rf.*/*` keyword namespaces (per [Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)); user-declared `:tags` must not use those prefixes. Any unreserved namespace is fair game, including dotted forms like `:ui.state/loading`.

### Snapshot shape change

Strictly additive — the `:rf/machine-snapshot` schema (see [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)) gains one **optional** key:

```clojure
{:state <fsm-keyword-or-path>
 :data  <map>
 :tags  #{<keyword>, ...}            ;; NEW — derived at commit; optional
 :meta  {<...>}}
```

When the active configuration's tag union is **empty** (no active state declares `:tags`), the runtime **elides** the `:tags` key entirely. Pre-tag machines are byte-identical to post-tag machines that declare no tags — no snapshot grew, no print/read round-trip changed shape, no downstream reader has to special-case anything.

Implementations may also legally carry `:tags #{}` instead of eliding; both shapes are conformant. The CLJS reference elides — that's the optimisation [conformance fixture `tags-empty-when-no-declaration`](conformance/fixtures/tags-empty-when-no-declaration.edn) asserts.

### Querying tags — the `:rf/machine-has-tag?` sub

The framework ships one sub:

```clojure
;; framework-shipped — registered alongside :rf/machine in the machines ns
(reg-sub :rf/machine-has-tag?
  (fn [db [_ machine-id tag]]
    (contains? (get-in db [:rf/machines machine-id :tags]) tag)))
```

User call sites:

```clojure
;; predicate
@(rf/subscribe [:rf/machine-has-tag? :ui/nine-states :data/loading])
;; => true | false

;; sugar matching sub-machine's pattern
(rf/has-tag? :ui/nine-states :data/loading)
;; => reaction wrapping the registered sub
```

Reading the whole tag set is the normal snapshot read:

```clojure
@(rf/sub-machine :ui/nine-states)
;; => {:state ... :data ... :tags #{:data/loading :form/neutral :mode/active}}
```

The sub is **derived** — it reads the snapshot via `get-in` rather than chaining off `:rf/machine` — so a view that only cares about whether a specific tag is present re-renders only when the containment-bit flips, not on every tag-set change. Reagent's built-in equality dedup gates the boolean return.

### Compatibility

Strictly additive. Machines that declare no `:tags` keys produce snapshots without a `:tags` slot; existing views, subs, and traces don't care. The `:rf/machine-snapshot` schema's `{:optional true}` covers the migration. No existing public name collides — `:tags` was previously unused in the state-node grammar (the `:meta` slot was the only carrier of state-level tooling-visible metadata, and per-state `:meta` is still independently allowed and not synonymous with `:tags`).

Print/read survives: `:tags` is `#{<keyword>}` — a set of keywords; both halves are EDN-printable and EDN-readable. The Tool-Pair epoch buffer and SSR hydration paths handle `:tags` automatically because they round-trip the snapshot as opaque data.

### Tags on states only — not transitions

Per the locked design decision (rf2-ee0d §9.3): `:tags` is a state-node slot, not a transition-spec slot. Transitions don't carry tags — the question "is this transition tagged" is already answered by the existing trace-event vocabulary (`:source`, `:op-type`). Adding transition tags later is non-breaking; today's design says no.

### What tags are *not*

- **Not a transition-driver.** Guards' inputs are `:data` + the event, not the tag set. A transition can't react to a tag flipping on; if you need that, the right answer is to change the state directly (an `:always` transition guarded on `:data` is the canonical mechanism).
- **Not a `:meta` synonym.** Per-state `:meta` (the long-standing tooling-visible slot, e.g. `{:terminal? true}`) lives alongside `:tags` and is independently queryable via `(machine-meta id)`. Tags are about **runtime active-configuration projection**; `:meta` is about **static state-node metadata**.
- **Not user-writable on the snapshot.** Actions can't return `:tags` in their `{:data :fx}` effect map; the slot is runtime-owned.
- **Not a substitute for `:rf/machine`.** Views that need the whole snapshot still subscribe to `:rf/machine`; `:rf/machine-has-tag?` is for the predicate-shaped query. Both are first-class.

### Worked example — read the page's render state in one tag-query

A view that wants "render the loading spinner whenever any data-loading state is active" today writes:

```clojure
(rf/reg-sub :ui.state/loading?
  :<- [:todos/status]
  (fn [status _] (= status :loading)))

(when @(rf/subscribe [:ui.state/loading?])
  [view-loading])
```

That works for the flat-status case; it doesn't scale to "loading, OR validating, OR retrying" without adding three more subs. With tags:

```clojure
{:loading     {:tags #{:data/loading} :on {...}}
 :validating  {:tags #{:data/loading} :on {...}}
 :retrying    {:tags #{:data/loading :data/retry} :on {...}}}

(when @(rf/has-tag? :todos/editor :data/loading)
  [view-loading])
```

The view doesn't enumerate the three states. New `:loading`-flavoured states added later carry the tag automatically; the view picks them up at no cost.

### Trace events

`:tags` is recomputed on every `:rf.machine/transition` and fires under the same trace event — there's no separate `:rf.machine.tag/changed` trace. The committed snapshot's `:tags` slot is visible in the existing trace's `:after` payload; observers that care about tag changes compare `(:tags (:before tr))` against `(:tags (:after tr))`.

If a future use case wants per-tag granular tracing, a `:rf.machine.tag/changed` trace event can be added additively without breaking the read pattern above.

### Capability gating

`:tags` is **`:fsm/tags`** in the capability matrix (per [§Capability matrix](#capability-matrix)) — claimed by the v1 CLJS reference. Ports that don't claim it raise `:rf.error/machine-grammar-not-in-v1` on `:tags` at registration time.

### Cross-references

- [Pattern-NineStates.md](Pattern-NineStates.md) — the motivating pattern.
- [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) — snapshot-schema extension.
- [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rfstate-node) — state-node-schema extension.
- [§Capability matrix](#capability-matrix) — `:fsm/tags` row.
- [conformance/fixtures/tags-flat-machine.edn](conformance/fixtures/tags-flat-machine.edn),
  [tags-compound-active-path-union.edn](conformance/fixtures/tags-compound-active-path-union.edn),
  [tags-empty-when-no-declaration.edn](conformance/fixtures/tags-empty-when-no-declaration.edn),
  [tags-round-trip-pr-str.edn](conformance/fixtures/tags-round-trip-pr-str.edn) — conformance fixtures.

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

Each `:after` map entry is `<delay> → <transition-spec>`. Both halves admit multiple forms.

**Delay (the key) — three forms:**

- **`pos-int?`** — literal milliseconds, computed at registration time. The default form for fixed timeouts (`{30000 :timeout}` — fire after 30 seconds).
- **Subscription vector** — `[<sub-id> & <args>]` resolved through the same machinery as `subscribe`. Canonical for **app-state-derived delays**: the delay reads from a flow / sub whose value reflects user preferences, feature-flag config, or any other `app-db`-derived setting. Re-resolves on subscription change (see [§Dynamic delay re-resolution](#dynamic-delay-re-resolution)). Example: `{[:sub :timeout-config :auth] :timeout}` reads the auth-phase timeout from a registered sub.
- **`(fn [snapshot] ms)`** — fn-valued delay, called once at state entry against the entering snapshot. Returns a `pos-int?` ms value. The escape valve for delays computed from local machine `:data` (the snapshot is `{:state :data :meta?}`); `:data` is the only source of dynamic input that the subscription form cannot reach without a subscription wrapper. Example: `{(fn [snap] (* 1000 (:retry-count (:data snap)))) :retry}`.

The subscription form is the **canonical** answer for "the delay should track an app-level configuration"; the fn form is the **local** answer for "the delay depends on this machine's own `:data`." Literal `pos-int?` covers the common case where the delay is a constant.

**Transition spec (the value) — two forms:**

- **`:keyword`** — sugar for `{:target <keyword>}`. The simple "fire after N ms; transition to state X" case.
- **`:rf/transition` map** — full transition spec with the same shape as an `:on` slot: `{:guard <guard-ref> :target <target> :action <action-ref> :meta <map>}`. Guards resolve **machine-locally** against the spec's `:guards` map, exactly as for `:on` and `:always`. If `:guard` is present and evaluates false at timer expiry, the transition is **suppressed** (the timer is treated as "fired and discarded; no transition") and the runtime emits a `:rf.machine.timer/fired` trace with `:fired? false`; the snapshot is unchanged and **other in-flight `:after` timers continue running** (per [§Multi-stage interaction with `:guard`](#multi-stage-interaction-with-guard)). The slot shape — `:guard`, `:target`, `:action`, `:meta` — matches the canonical `:rf/transition` shape used by `:on` (per [§Transitions](#transitions)) and `:always`.

Sugar normalises at registration time: `{5000 :timeout}` is equivalent to `{5000 {:target :timeout}}`. The runtime sees the desugared form.

```clojure
;; Three delay forms in one state node:
{:loading
 {:after {30000                        {:target :timeout :guard :no-progress?}     ;; literal ms
          [:sub :timeout-config :slow] {:target :warn :action :log-slow}            ;; subscription
          (fn [snap] (* 1000 (-> snap :data :retry-count)))
                                       :retry}                                      ;; local fn
  :on    {:loaded :ready
          :failed :error}}}
```

### Wall-clock from state entry

Each `:after` timer counts from the moment the machine **enters the state** (the `:entry`-cascade-final timestamp captured by the runtime at commit time). If the state has multiple `:after` entries, **each timer counts independently from the same entry-time** — a state with `{:after {5000 :warn 30000 :timeout}}` schedules both timers concurrently at entry; the 5000 ms timer is not chained off the 30000 ms timer.

Re-entering the same state (a transition whose `:target` lands back in the same state, or a parent-cascade that re-enters the leaf) restarts every `:after` timer from the new entry-time — the prior visit's in-flight timers go stale via the epoch advance ([§Epoch-based stale detection](#epoch-based-stale-detection)); the new visit's timers are scheduled fresh. There is no preserved "elapsed-so-far" across state re-entry — by design (`:after` is **per-state-entry** semantics, not per-state-occupancy).

### Whichever fires first wins

A state may have multiple in-flight transition triggers concurrently:

- **Multiple `:after` timers** — every entry in the `:after` map is its own independent timer.
- **`:on <event>` transitions** — any user-dispatched event the state's `:on` map handles.
- **`:always` transitions** — guards that may newly become true after an action commits.
- **`:invoke`'s child completion** — the spawned child dispatching back into the parent.

**Whichever fires first causes the transition; the others are cancelled** as part of the standard exit cascade. The mechanism:

1. The first trigger to dequeue at the parent's handler (timer expiry, user dispatch, child dispatch, `:always` microstep) drives the transition.
2. The transition's exit cascade runs (per [§Entry/exit cascading along the LCA](#entryexit-cascading-along-the-lca)).
3. As part of the exit cascade, the runtime advances `:rf/after-epoch` — every other in-flight `:after` timer from the just-exited state goes stale on its eventual firing.
4. Any `:invoke`-spawned child is destroyed via `:rf.machine/destroy` (the desugared `:exit` action). Per the [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts) contract (rf2-wvkn), in-flight `:rf.http/managed` requests inside the destroyed child cascade to abort — `:after` firing is one trigger of the same cancellation cascade as a parent-destroys-child shutdown.
5. User-dispatched events queued for the just-exited state but not yet drained are processed by the now-current state's `:on` map (which may handle them, route to `:*` wildcard, or emit `:rf.warning/machine-unhandled-event`).

The cancellation cascade is **uniform across triggers** — the runtime does not distinguish "the timer fired" from "the user dispatched" from "the child completed" at the cascade level; each is just an event at the parent's handler boundary that resolves to a transition out of the state. The `:rf.machine.timer/stale-after` traces ([§Trace events](#trace-events)) are how observers see "this `:after` was racing and lost."

### Dynamic delay re-resolution

A subscription-vector delay (`[:sub-id & args]`) is **re-resolved** when its underlying subscription value changes:

1. **At state entry**, the runtime resolves the subscription, captures the current ms value, and schedules a timer for that delay.
2. **While the timer is in flight**, the runtime watches the subscription. If its value changes (a new `app-db` value flows through the sub) the runtime:
   - **Cancels the in-flight timer** (best-effort via `re-frame.interop/cancel-scheduled!`; epoch-based stale detection backstops cancellation per [§Epoch-based stale detection](#epoch-based-stale-detection)).
   - **Restarts the timer from the current moment** with the newly-resolved ms value. The window does **not** carry over elapsed-so-far; the replacement timer counts from the re-resolution time.
3. **When the timer expires**, the runtime fires the transition (subject to `:guard`).

**Why restart from the current moment, not extend/shorten the existing timer:** restart semantics is the simplest mental model and the easiest to reason about — at any moment, the timer's countdown reflects the *current* subscription value. Extending or shortening an existing timer requires the user to track elapsed-so-far, makes the wall-clock interaction non-monotonic (a timer set for 30 s could fire at 15 s if shortened, or never fire if perpetually extended), and complicates the `:rf.machine.timer/scheduled` trace stream (does the trace fire on each shortening?). Restart-from-now keeps the contract: every `:rf.machine.timer/scheduled` trace marks a fresh wall-clock window; every `:rf.machine.timer/fired` measures from the most-recent `:scheduled`.

**Stale-detection composes.** Each restart advances the per-machine `:rf/after-epoch` (or per-`:after`-entry sub-counter; implementation choice — the contract is "the prior in-flight timer is stale on firing"); the cancelled prior timer fires stale and emits `:rf.machine.timer/stale-after`.

**Trace.** A subscription-driven restart emits a paired `:rf.machine.timer/cancelled-on-resolution` (the prior timer cancelled by re-resolution; `:tags {:machine-id <id> :state <state> :delay <prior-ms> :reason :sub-changed :sub-id <sub-id>}`) followed by a fresh `:rf.machine.timer/scheduled` (the new timer). Tools that distinguish "the subscription changed" from "the state exited" filter on `:reason :sub-changed` vs the standard exit-cascade-driven `:rf.machine.timer/stale-after`.

**Function-form delays do NOT re-resolve.** A `(fn [snapshot] ms)` delay is called **once** at state entry; the snapshot's `:data` may change later but the timer does not re-evaluate. Authors who want a `:data`-derived delay that re-resolves on `:data` change use the subscription form (`[:sub :machine-data-derived-delay <machine-id>]` whose body reads from `[:rf/machines <machine-id> :data ...]`) and pay the subscription cost; the fn form is the cheap "compute once at entry" escape valve.

**Subscription form under SSR.** Resolved at server render time (the runtime materialises the value), but **scheduling is suppressed** per [§SSR mode](#ssr-mode); the resolved ms value flows into the hydration payload as part of the snapshot's trace state but no timer fires server-side.

### Multi-stage interaction with `:guard`

When multiple `:after` entries declare `:guard`s, each timer's guard is checked **independently** at that timer's expiry:

- **Guard returns true (transition fires)** — the transition runs through the standard cascade; the exit advances the epoch; remaining in-flight `:after` timers from the just-exited state go stale on firing.
- **Guard returns false (transition suppressed)** — the runtime emits `:rf.machine.timer/fired` with `:fired? false`; the snapshot is unchanged; the state does NOT exit; **other in-flight `:after` timers from the same state continue running unchanged**. The expired-with-false-guard timer is **not re-scheduled** — the contract is "fired and discarded." If the author wants a timer that polls a guard until true, the surface is to fire a transition (sugar `{30000 :recheck-state}`) into a state whose `:always` evaluates the same guard and re-routes — `:after` itself is fire-once-per-state-entry.

Concretely: a state declaring `{:after {5000 {:guard :slow? :target :warn} 30000 {:target :timeout}}}` runs both timers concurrently from entry. At t=5s the 5000 ms timer fires; if `:slow?` returns false, the transition is suppressed; the 30000 ms timer is still in flight. At t=30s the 30000 ms timer fires unconditionally and the machine transitions to `:timeout` regardless of `:slow?`'s eventual truth. The author who writes the 5000 ms-with-guard form is opting for "if the condition is true at the 5 s checkpoint, escalate; otherwise let the longer timeout decide" — exactly the multi-stage timeout pattern.

### No-invoke variant

A state with `:after` but no `:invoke` is a **pure timed-transition state** — the canonical shape for splash screens, animation gates, and user-prompt countdown timers. No child machine is spawned; the state's only behaviour is the timer (plus any user `:on` events).

```clojure
{:initial :splash
 :states
 {:splash {:after {3000 :main}             ;; show splash for 3 seconds
           :on    {:skip :main}}            ;; or user clicks 'skip'
  :main   {...}}}
```

The `:after` slot is **independent** of the `:invoke` slot — neither requires the other; both are state-node-level keys per [§State nodes](#state-nodes). Pure timed-transition states are the simplest `:after` use case and are exercised by the conformance fixture `after-no-invoke-splash` per [§Capability matrix](#capability-matrix).

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

**Normative rule (external contract).** A parent state's `:after` timer is suspended-but-not-stale while the snapshot is in any child of the parent: leaf-only sibling transitions inside the same parent MUST NOT cause that parent's pending `:after` timer to fire as stale on its next match. Conversely, a transition whose LCA is at-or-above the parent MUST advance the epoch such that any of the parent's pending `:after` timers (from the just-exited visit) all observe a mismatch and silently drop. Implementations that cannot satisfy both clauses with a single per-machine epoch (because a leaf transition advances it) MUST track which `:after` entries belong to which level on the active path and selectively re-schedule only the levels the cascade newly enters. The per-level re-scheduling sketch above is the recommended implementation; the contract is the observable behaviour, not the implementation strategy.

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

**Spawn under SSR.** `:rf.machine/spawn` and `:invoke`-driven spawns are also SSR-conditional in the v1 reference: the canonical guidance is that long-lived child actors which exist primarily to drive client-side async work (`:http/post`, websocket protocols, polling) should be gated on the surrounding event handler running client-side, exactly as with `reg-fx :platforms`. Server-rendered machine snapshots that happen to land in a state whose `:invoke` would spawn such an actor should rely on the standard `:platforms`-style suppression at the spawn-fx layer rather than expecting the runtime to silently no-op the spawn. The hydration payload covers the snapshot value itself; child-actor handlers are not part of the wire shape and re-establish on the client side via the post-hydration entry replay (per [011-SSR](011-SSR.md)).

### Clock abstraction

The clock primitives live in **`re-frame.interop`** — the existing clj/cljs-split interop layer that already houses platform-dependent atoms, `next-tick`, etc. Three primitives:

- `re-frame.interop/now-ms` — host-clock current time in milliseconds (a long).
- `re-frame.interop/schedule-after!` — host-clock `setTimeout`-equivalent. Returns an opaque handle.
- `re-frame.interop/cancel-scheduled!` — best-effort cancellation given the handle. Optional; epoch-based stale-detection makes cancellation an optimisation, not a correctness requirement.

The CLJS realisation uses `js/Date.now` and `js/setTimeout` / `js/clearTimeout`. The JVM realisation uses `System/currentTimeMillis` and a `ScheduledExecutorService`. Tests swap the interop layer using existing fixture patterns — there is **no new framework-level clock-configuration API**; the substitution happens at the namespace level (`with-redefs` in tests, alternative interop ns alias in conformance harnesses). If `:after` is exercised on a host whose interop layer hasn't been wired with a clock, the runtime emits `:rf.warning/no-clock-configured` (an advisory; the host falls back to a host-native clock if available).

### Trace events

The runtime emits five trace events around every `:after`:

- **`:rf.machine.timer/scheduled`** — emitted when a timer is scheduled at state entry (or re-scheduled after a subscription-driven re-resolution per [§Dynamic delay re-resolution](#dynamic-delay-re-resolution)). `:tags {:machine-id <id> :state <state> :delay <ms> :epoch <e> :delay-source <:literal | :sub | :fn> :sub-id <sub-id, when :delay-source = :sub>}`. One event per `:after` entry per scheduling.
- **`:rf.machine.timer/fired`** — emitted when a live (epoch-matching) timer's transition resolves. `:tags {:machine-id <id> :state <state> :delay <ms> :epoch <e> :fired? <bool>}`. `:fired? false` indicates the guard was checked and returned false; the transition was suppressed and other in-flight timers continue (per [§Multi-stage interaction with `:guard`](#multi-stage-interaction-with-guard)).
- **`:rf.machine.timer/stale-after`** — emitted when a stale (epoch-mismatched) timer fires. `:tags {:machine-id <id> :state <state> :delay <ms> :scheduled-epoch <e1> :current-epoch <e2>}`. The transition does not fire.
- **`:rf.machine.timer/cancelled-on-resolution`** — emitted when a subscription-vector delay re-resolves and the prior in-flight timer is cancelled in favour of a fresh timer (per [§Dynamic delay re-resolution](#dynamic-delay-re-resolution)). `:tags {:machine-id <id> :state <state> :delay <prior-ms> :reason :sub-changed :sub-id <sub-id>}`. Pairs with a fresh `:rf.machine.timer/scheduled` for the new resolution.
- **`:rf.machine.timer/skipped-on-server`** — emitted in SSR mode when a state's `:after` entry is reached but timer scheduling is suppressed (per [§SSR mode](#ssr-mode)). `:tags {:machine-id <id> :state <state> :delay <ms>}`. Diagnostic: lets server-side tooling see which timers a real client run would have scheduled.

Tools subscribe to whichever granularity they need: `:scheduled` for timeline visualisation, `:fired` for the externally-observable transition, `:stale-after` for diagnosing "a timer should have fired but didn't" symptoms, `:cancelled-on-resolution` for confirming subscription-driven re-resolution behaviour, `:skipped-on-server` for confirming SSR no-op behaviour.

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
>
> **v1 status — partial.** The snapshot side of the contract holds: a frame revert restores `[:rf/machines <id>]` atomically with the rest of `app-db`. The handler-registration side currently relaxes to the **global registrar** in the v1 CLJS reference (a frame revert does not yet clear the actor's event-handler entry); a separate tracking bead covers the migration to the frame-local tier. Reads of the spec that conclude "frame revert wipes spawned actor handlers entirely" should treat that as the post-v1 target shape, not the v1 behaviour. Snapshot-side revert is unaffected.

Symmetry between singleton and spawned:

| | id form | snapshot location | handler |
|---|---|---|---|
| Singleton | `:drawer/editor` (explicit) | `[:rf/machines :drawer/editor]` | registered at boot via `reg-event-fx` |
| Spawned actor | `:request/protocol#42` (gensym'd) | `[:rf/machines :request/protocol#42]` | registered dynamically by `[:rf.machine/spawn ...]` fx |

Both are event handlers. Both addressable by `dispatch`. Both visible to `(handlers :event)`. Both readable through the framework-registered `:rf/machine` sub (per [§Subscribing to machines via `sub-machine`](#subscribing-to-machines-via-sub-machine)) — the actor-id is just the argument: `@(rf/sub-machine actor-id)`.

### Spawning from inside an action (the common case)

```clojure
:action (fn [_ [_ url]]
          {:fx [[:rf.machine/spawn {:machine-id :request/protocol
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
| `:on-spawn` | `(fn [data id] new-data)` — how the parent records the new id | required for from-action spawns; ignored for top-level boot-time spawns |
| `:start` | event vector dispatched to the new actor immediately after spawn | optional |
| `:system-id` | bind the spawned actor to a per-frame name in the `[:rf/system-ids]` reverse index; lookup with `(rf/machine-by-system-id sid)`. See [§Named addressing via `:system-id`](#named-addressing-via-system-id). | optional |

The spawned actor's snapshot lives at `[:rf/machines <gensym'd-id>]` — the runtime owns the location, the spawn-spec only declares the id-prefix. See [§Where snapshots live](#where-snapshots-live) and [Spec-Schemas §`:rf.fx/spawn-args`](Spec-Schemas.md#standard-fx-args-schemas).

### Runtime stamps on the spawned actor's `:data` (rf2-ijm7)

Per [rf2-ijm7](#) the runtime stamps three framework-reserved keys into every spawned actor's initial `:data` map so the actor can address its parent and itself at action-call time without the parent having to thread that information through manually:

| key | value | when present |
|---|---|---|
| `:rf/self-id` | the spawned actor's own address (e.g. `:request/protocol#42`) | always |
| `:rf/parent-id` | the parent machine's registration-id | when the spawn carries `:rf/parent-id` (the declarative `:invoke` / `:invoke-all` desugar path) |
| `:rf/invoke-id` | the absolute prefix-path of the parent's `:invoke`-bearing state node | same as `:rf/parent-id` |

Per [§Path conventions in machine bodies](#path-conventions-in-machine-bodies), the `:rf/*` namespace inside `:data` is reserved for runtime-managed keys; user code does not write under it. The actor reads these as ordinary `:data` lookups inside its actions:

```clojure
:dispatch-done (fn [data _]
                 (when-let [parent-id (:rf/parent-id data)]
                   {:fx [[:dispatch [parent-id [:done (:result data)]]]]}))
```

Imperative `:rf.machine/spawn` from a user's `:fx` (the rare boot-time form per [§Top-level boot-time spawn](#top-level-boot-time-spawn-rare)) doesn't carry `:rf/parent-id` / `:rf/invoke-id`, so only `:rf/self-id` is stamped. That's the right shape — there's no parent in that case.

### Synthetic `[:rf.machine/spawned]` on spawn (rf2-ijm7)

Per [rf2-ijm7](#) — when `[:rf.machine/spawn ...]` does NOT carry an explicit `:start` event, the runtime dispatches a synthetic `[<spawned-id> [:rf.machine/spawned]]` to the new actor as its first event.

> **Note (rf2-0z73).** This synthetic event was originally introduced (rf2-ijm7) so generic child machines could declare a leaf-level `:on :rf.machine/spawned :action ...` to fire their first work on spawn — covering the gap that initial-state `:entry` actions did not fire on bootstrap. Per [§Initial-state `:entry` fires on machine bootstrap (rf2-0z73)](#initial-state-entry-fires-on-machine-bootstrap-rf2-0z73), `:entry` now does fire on bootstrap, so `:entry :fire-request` is the canonical shape. The synthetic `[:rf.machine/spawned]` event still flows (preserving back-compat for any machine that declares `:on :rf.machine/spawned ...`), but new code should prefer the `:entry` form.

```clojure
;; Canonical post-rf2-0z73 shape:
:requesting {:entry :fire-request}

;; Pre-rf2-0z73 workaround (still supported, but no longer the canonical shape):
:requesting {:on {:rf.machine/spawned {:action :fire-request}}}
```

Machines that don't handle `:rf.machine/spawned` see the event as a benign no-op — it walks the leaf→root resolution chain, finds no match, and the snapshot is unchanged (per [§Transition resolution — deepest-wins with parent fallthrough](#transition-resolution--deepest-wins-with-parent-fallthrough)).

When the spawn DOES carry `:start`, the runtime dispatches `[<spawned-id> <start>]` instead — the existing behaviour, unchanged. The two paths are mutually exclusive; an actor receives one of `:rf.machine/spawned` OR the user's `:start`, never both. In both cases the initial-state `:entry` cascade runs BEFORE the first event's `:on` lookup, so `:entry` actions on the initial state fire regardless of which kick-off mode the spawn used.

### Top-level boot-time spawn (rare)

The canonical surface is the `[:rf.machine/spawn ...]` fx — used inside an event handler's `:fx`. From outside a handler (e.g. boot-time), wrap the spawn in a one-shot bootstrap event:

```clojure
(rf/reg-event-fx
  :app/spawn-request-protocol
  (fn [_ [_ url]]
    {:fx [[:rf.machine/spawn
           {:definition request-protocol           ;; or :machine-id if reusing a registered definition
            :id-prefix  :request/protocol           ;; → :request/protocol#42
            :data       {:url url :attempt 0}
            :on-spawn   (fn [data id] (assoc data :request-id id))}]]}))

(rf/dispatch-sync [:app/spawn-request-protocol "/foo"])

;; snapshot lives at [:rf/machines :request/protocol#42] in the active frame's app-db.

;; address it
(rf/dispatch [actor-id [:retry]])
(rf/dispatch [actor-id [:cancel]])

;; destroy — emit the canonical destroy fx from a handler
(rf/reg-event-fx
  :app/destroy-request-protocol
  (fn [_ [_ actor-id]]
    {:fx [[:rf.machine/destroy actor-id]]}))
;; Internally: run :exit action, dissoc the snapshot at [:rf/machines actor-id],
;; clear-event actor-id. (No per-machine sub to clear — reads go through the
;; framework-registered :rf/machine sub, parameterised on actor-id.)
```

(The v1 public fns `spawn-machine` / `destroy-machine` are dropped — see [MIGRATION.md §M-26](MIGRATION.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).)

### Spawning multiple, dynamic counts

Multiple `[:rf.machine/spawn ...]` entries in `:fx` work independently; each runs its `:on-spawn` against the current data (post-previous-spawn). For dynamic-count spawning, build the `:fx` vector with `mapv`:

```clojure
:action (fn [_ [_ jobs]]
          {:fx (mapv (fn [job]
                       [:rf.machine/spawn {:machine-id :worker
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

### Named addressing via `:system-id`

A spawn whose `:system-id` key is supplied **also** binds a name in the per-frame `[:rf/system-ids]` reverse index. Users (and other machines) can then look up the spawned actor by that name, without having to thread the gensym'd id through their own `:data`. The mechanism is opt-in and orthogonal to gensym'd ids — it sits *alongside* the existing addressing-by-id, never replaces it.

```clojure
;; Imperative spawn (action :fx) with a :system-id binding.
:action (fn [_ _]
          {:fx [[:rf.machine/spawn {:machine-id :request/protocol
                                    :system-id  :primary-request    ;; bind the name
                                    :data       {:url "/api/foo"}
                                    :on-spawn   (fn [d id] (assoc d :pending id))
                                    :start      [:begin]}]]})

;; The same :system-id key works on declarative :invoke:
{:loading
 {:invoke {:machine-id :request/protocol
           :system-id  :primary-request
           :data       (fn [snap _] {:url (-> snap :data :endpoint)})
           :on-spawn   (fn [d id] (assoc d :pending id))}}}

;; Anywhere in the same frame:
(rf/machine-by-system-id :primary-request)
;; → :request/protocol#42 (the gensym'd id)
```

The mapping lives at `[:rf/system-ids <name>]` in the spawning frame's `app-db` — same place the snapshot lives, so the reverse index inherits frame revertibility for free (the index walks back along with the rest of `app-db`).

**Lifecycle.**

- On spawn, the runtime writes `[:rf/system-ids <name>] = <gensym'd-id>` and emits `:rf.machine/system-id-bound`.
- On destroy (whether by `:invoke` exit cascade or hand-emitted `[:rf.machine/destroy actor-id]`), the runtime clears the slot AND emits `:rf.machine/system-id-released`.
- A spawn under an already-bound name **rebinds** (last-write-wins) and emits `:rf.error/system-id-collision` so observers can see the displacement. The previously-bound machine's snapshot is NOT auto-destroyed by the rebind; it stays at its `[:rf/machines <id>]` slot, just unnamed. (Symmetric with `reg-event-fx` re-registration: replacing a handler doesn't cancel any in-flight work that addressed the previous fn; it just means the next *named* dispatch routes to the new one.)

**`:system-id` is orthogonal to `:invoke-id`.**

- `:invoke-id` is a per-state singleton actor id — the *machine-id* of the spawned actor is fixed by name (no gensym).
- `:system-id` is a *frame-level reverse index* that resolves to whichever spawned actor currently owns the name.

A spawn may declare both: `:invoke-id` fixes the actor-id (no gensym), and `:system-id` registers a separate name in the frame's reverse index. Most uses pick one or the other.

### Cross-machine messaging by name

The standard cross-machine pattern remains `[:fx [[:dispatch [<other-id> [:event]]]]]` — `dispatch` already addresses any registered id. With `:system-id` bound, the addressing call site becomes a name lookup:

```clojure
;; Inside a machine action's :fx — dispatch by name
:action (fn [_ _]
          {:fx [[:dispatch [(rf/machine-by-system-id :primary-request)
                            [:cancel]]]]})

;; Sugar — dispatches via the lookup, no-ops when the name is unbound:
:action (fn [_ _]
          {:fx [[:dispatch-to-system :primary-request [:cancel]]]})
```

The sender doesn't have to capture the gensym'd id at the spawn site, doesn't have to carry it through `:data`, doesn't even have to be the spawning machine — anything in the frame that knows the name can address the actor.

The pattern composes naturally with the standard reply convention ([§Reply patterns](#reply-patterns)): include the reply event in the request, addressed by name on the request side, by id on the reply side (so the reply lands in a specific spawned correlator, not whichever machine currently owns the name).

## Declarative `:invoke` (sugar over spawn)

`:invoke` on a state node is **declarative sugar** for "spawn this child actor on entry; destroy it on exit." The child's lifetime is bound to the state's lifetime: while the machine is in this state, the child runs; when the machine leaves the state (by any transition, including a parent-level cascade), the child is destroyed.

`:invoke` is **registration-time sugar.** `create-machine-handler` walks the spec at construction time and rewrites every `:invoke` slot into entry/exit actions emitting `:rf.machine/spawn` and `:rf.machine/destroy` fx. The runtime sees only the desugared form — no new mechanics, no new lifecycle event, no new error category.

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

While in `:loading`, an actor of `:request/protocol` exists at `[:rf/machines <gensym'd-id>]`, addressable through the id the user's `:on-spawn` recorded in `(:pending data)` AND through the runtime-owned registry at `[:rf/spawned <parent-machine-id> [:loading]]`. On any transition out of `:loading`, the actor is destroyed and its snapshot disappears — the runtime locates it via the registry slot, no longer requires the user to have written the id under any specific `:data` key.

### Spec-spec keys

The map under `:invoke` accepts the following keys:

| key | purpose | required? |
|---|---|---|
| `:machine-id` *or* `:definition` | which machine to spawn (registered id, or inline transition table) | exactly one of these |
| `:data` | initial data for the child — literal map or `(fn [snapshot event] data)` | optional |
| `:id-prefix` | base for the gensym'd actor id (`:request/protocol#42`) | optional; defaults to `:machine-id` |
| `:on-spawn` | `(fn [data spawned-id] new-data)` — how the parent records the child id in its own `:data` | optional but typically wanted |
| `:on-done` | `(fn [data result] new-data)` — fires when the child enters a `:final?` state; `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil` if no `:output-key` declared) — see [§Final states](#final-states-final--on-done--output-key) | optional |
| `:start` | event vector dispatched to the newborn after spawn | optional |
| `:invoke-id` | explicit id instead of gensym (useful for tests / per-state singleton actors) | optional |

The keys mirror [§Spawn-spec keys](#spawn-spec-keys), with two additions:

- `:data` admits a function form `(fn [snap ev] data)` so the initial data can depend on the snapshot at the moment of entry — the snapshot is the *post-action* value (the transition's `:action` has already run, so any `:data` writes the action made are visible).
- `:invoke-id` is an explicit alternative to `:id-prefix` + gensym — useful when a state should host exactly one actor with a known id (no need to record the id in the parent's `:data` because it's already a known constant).

> **Wall-clock timeouts: use the parent state's `:after` slot.** Earlier drafts of this spec carried a `:timeout-ms` slot on `:invoke` / `:invoke-all` for "the whole spawned actor must terminate within N ms (spanning retries)." That slot is **dropped** in favour of the canonical `:after` primitive on the parent state — `:after` is one mechanism, not two. Per [§Whichever fires first wins](#whichever-fires-first-wins), an `:after` firing on the parent state exits the state and the standard exit cascade destroys the in-flight `:invoke`d child. The migration recipe is mechanical: lift the `:timeout-ms` value into the `:invoke`-bearing state's `:after` map, with a transition that exits the state to a "timeout" target. See [MIGRATION §M-44](MIGRATION.md#m-44-timeout-ms-removed-from-invoke--invoke-all--use-parent-states-after).

**Path convention.** The `:on-spawn` callback receives the snapshot's `:data` directly and returns a new `:data` map. The runtime patches the result back into the snapshot. Per [§Path conventions in machine bodies](#path-conventions-in-machine-bodies), this is uniform with `:guard` and `:action`: the body operates on `:data`, never on the wrapping snapshot. A typical body is `(assoc data :pending id)` or `(update data :workers (fnil conj []) id)` — *not* `(assoc-in snap [:data :pending] id)`.

**`:on-spawn` is purely advisory.** Per rf2-t07u (Option A revised), the runtime tracks each declarative-`:invoke` spawn-id at the reserved app-db slot `[:rf/spawned <parent-machine-id> <invoke-id>]` (where `<invoke-id>` is the absolute prefix-path of the `:invoke`-bearing state node). The `:on-spawn` callback runs because most apps want a user-side handle on the id (so other transitions can address the child by name in their own bookkeeping) — but the runtime no longer depends on it for the destroy-side resolution. Apps can omit `:on-spawn` entirely when no user-side bookkeeping is needed; the parent's `:exit` cascade still tears down the spawned child via the runtime registry.

### Desugaring rules

`create-machine-handler` walks every state node at construction time. For each `:invoke`-bearing state, it:

1. **Composes** an `:rf.invoke/spawn-<state>` registered action that emits a `:rf.machine/spawn` fx whose args are the `:invoke` spec, with `:data` materialised (call the fn if `:data` is a fn, else use the literal). The runtime stamps `:rf/parent-id` (the parent machine's registration-id) and `:rf/invoke-id` (the absolute prefix-path of the `:invoke`-bearing state node) onto the spawn args; the `:rf.machine/spawn` fx handler binds the spawned id at `[:rf/spawned <parent-id> <invoke-id>]` in the frame's app-db.
2. **Composes** an `:rf.invoke/destroy-<state>` registered action that emits a `:rf.machine/destroy` fx whose args carry the same `{:rf/parent-id ... :rf/invoke-id ...}`. The fx handler reads the spawned id back from `[:rf/spawned <parent-id> <invoke-id>]` at call time and tears down whatever id is currently bound there. (For `:invoke-id` literals — the explicit-id case — the runtime uses that id directly; the registry slot still binds it for symmetry.)
3. **Wires** the composed actions into the state's `:entry` and `:exit` slots, after any user-supplied `:entry` / `:exit` (see [§Composition with explicit `:entry` / `:exit`](#composition-with-explicit-entry--exit)).

The runtime-owned spawn registry at `[:rf/spawned ...]` is sibling to `[:rf/system-ids]` (per [§Named addressing via `:system-id`](#named-addressing-via-system-id)) — same lazy-allocation invariant (absent until the first declarative-`:invoke` spawn), same per-frame isolation (each frame's `app-db` carries its own slot), same revertibility (the slot walks back atomically with `app-db` on a frame revert).

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
           {:fx [[:rf.machine/spawn {:machine-id   :request/protocol
                                     :id-prefix    :request/protocol
                                     :data         {:url (:endpoint data)}
                                     :on-spawn     (fn [d id] (assoc d :pending id))
                                     :start        [:begin]
                                     ;; Stamped by the runtime — addresses the
                                     ;; runtime-owned spawn registry slot at
                                     ;; [:rf/spawned <parent-id> <invoke-id>].
                                     :rf/parent-id <parent-machine-id>
                                     :rf/invoke-id [:loading]}]]})
  :exit  (fn [_data _]
           ;; Per rf2-t07u (Option A revised) — the destroy fx no longer
           ;; reads the actor id from `:data`. The fx handler resolves
           ;; the id from [:rf/spawned <parent-id> <invoke-id>] in the
           ;; frame's app-db at call time.
           {:fx [[:rf.machine/destroy {:rf/parent-id <parent-machine-id>
                                       :rf/invoke-id [:loading]}]]})
  :on    {:succeeded :loaded
          :failed    :error}}}
```

From outside, an `:invoke`-using machine is indistinguishable from one that wrote the entry/exit by hand — except that the runtime no longer requires the user's `:on-spawn` callback to write the spawned id under any particular `:data` slot. The pure-factory invariant on `create-machine-handler` is preserved — no global state, no new registry kind, no new lifecycle hook (the `[:rf/spawned ...]` slot lives inside `app-db` per [Conventions §Reserved app-db keys](Conventions.md#reserved-app-db-keys); not a separate registry).

### Composition with explicit `:entry` / `:exit`

A state may declare both `:invoke` AND user-supplied `:entry` / `:exit`. The user-supplied actions run **first** in each slot:

- **On enter:** the user's `:entry` action runs, then the auto-spawn fx is emitted.
- **On exit:** the user's `:exit` action runs, then the auto-destroy fx is emitted.

Rationale: the user's `:entry` is for setup work that must happen before the child starts (e.g., normalising data, recording a start timestamp). The spawn happens after that setup completes, so the child sees the post-setup snapshot. On exit, the user's `:exit` action gets to read the actor's final snapshot before the auto-destroy clears it — useful for capturing the child's last reported value. Address the child either through whatever id the user's `:on-spawn` recorded in `:data`, or via the runtime registry: `(get-in db [:rf/spawned <parent-machine-id> <invoke-id>])` resolves to the gensym'd id, and `(get-in db [:rf/machines <id>])` reads the snapshot from there.

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
| **`onDone`** — fire a callback when the child reaches a final state | re-frame2 ships first-class final states with parent notification — see [§Final states (`:final?` / `:on-done` / `:output-key`)](#final-states-final--on-done--output-key). A leaf state declares `:final? true` (and optionally `:output-key`); the parent's `:invoke` declares `:on-done (fn [data result] new-data)`. The runtime invokes `:on-done` synchronously when the child enters its final state, then auto-destroys the child. Per rf2-gn80. |
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
2. Entering `:authenticating` triggers the desugared entry: spawn an `:http/post` actor with the credentials from `:data`; the runtime binds the spawned id at `[:rf/spawned :login [:authenticating]]` in the frame's app-db, and the `:on-spawn` fn (advisory; per rf2-t07u) records the id under `:auth-actor` so other transitions in the parent can address the child by name.
3. The HTTP child runs; on success, it dispatches `[:login [:auth/succeeded ...]]` (where `:login` is the parent machine's id).
4. The login machine handles `:auth/succeeded`; transitions to `:authenticated`.
5. Leaving `:authenticating` triggers the desugared exit: the runtime reads the actor id back from `[:rf/spawned :login [:authenticating]]`, destroys it, clears the slot. The HTTP child's snapshot is removed from `[:rf/machines]` automatically. (The `:auth-actor` value left in the parent's `:data` is now stale; user code may clear it in a subsequent action if it cares — the runtime does not.)
6. If the user abandons mid-flight (a different transition fires `:authenticating` → `:idle`), the exit cascade still runs; the in-flight HTTP child is destroyed; no actor leaks.

The key property: the parent does not have to *remember* to destroy the child. The lifecycle binding is declared once at the state level, and the exit cascade enforces it on every code path out of the state — including ones the author hasn't yet thought of.

Per [rf2-ijm7](#), the framework's `:rf.http/managed` ships as **both** an fx AND a child-invokable machine for exactly this pattern — apps no longer hand-roll the HTTP-child wrapper:

```clojure
{:authenticating
 {:invoke {:machine-id :rf.http/managed
           :data       {:request {:method :post :url "/api/login"
                                  :body credentials}}}
  :on     {:succeeded :authenticated
           :failed    :idle}}}
```

See [Spec 014 §Machine-shape wrapper](014-HTTPRequests.md#machine-shape-wrapper) for the wrapper's contract; the wrapper's terminal `:succeeded` / `:failed` events arrive at the parent exactly as the hand-rolled HTTP child machine would emit them.

Cross-references: [§Spawning](#spawning--dynamic-actors) for the imperative-spawn surface that `:invoke` desugars to; [§Composition with explicit `:entry` / `:exit`](#composition-with-explicit-entry--exit) for the auto+manual ordering rule; [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rftransition-table) for the `:invoke` schema. [Pattern-WebSocket](Pattern-WebSocket.md) is the canonical worked example exercising hierarchical states, `:after`, `:always`, machine-scoped `:guards` / `:actions`, and `:invoke` together — the connection-lifecycle state machine for long-lived sockets. [Pattern-LongRunningWork](Pattern-LongRunningWork.md) is the canonical worked example for chunked CPU-intensive work — `:always` for batch progression, `:after 0` for browser yielding between chunks, machine-scoped guards for completion / cancellation.

## Final states (`:final?` / `:on-done` / `:output-key`)

Per rf2-gn80, re-frame2 ships first-class **final states** with parent notification — the xstate-style "child reaches `done`; parent sees `onDone`" pattern. The earlier post-v1 note ("user code can dispatch on entry to a terminal state in v1") is **superseded**.

### The grammar

A leaf state may declare `:final? true`. Entering that state **terminates the machine**:

- If the machine was spawned by a parent's `:invoke`, the parent's `:invoke :on-done (fn [data result] new-data)` fires (with `result` = the child's `:data` slot named by the final state's `:output-key`, or `nil` when `:output-key` is absent). The child is then **auto-destroyed**.
- If the machine is a **singleton** (registered top-level, no parent `:invoke`), the machine still auto-destroys on entry to `:final?` — "final means final" (D7 below). Apps wanting a persistent terminal state simply **omit `:final?`** and use an ordinary leaf state.

```clojure
;; Child machine — declares its terminal state with :final? + :output-key.
(rf/reg-machine :auth-flow
  {:initial :running
   :data    {}
   :states
   {:running {:on {:server-ok {:target :done
                               :action (fn [data ev]
                                         {:data (assoc data :token (second ev))})}}}
    :done    {:final?     true
              :output-key :token}}})

;; Parent machine — :on-done reads the child's reported result.
(rf/reg-machine :login
  {:initial :idle
   :states
   {:idle
    {:on {:submit :authenticating}}

    :authenticating
    {:invoke {:machine-id :auth-flow
              :on-done    (fn [data result] (assoc data :token result))}
     :on    {:auth/cancelled :idle}}}})
```

When `:auth-flow` enters `:done`, the runtime:

1. Reads the child's `:data` at `:output-key :token` — call it `result`.
2. Looks up the parent's `:invoke` at the `:rf/invoke-id` recorded on the child's `:data` (stamped at spawn time per rf2-ijm7).
3. Runs the parent's `:on-done` against the parent's `:data` with `result` — the returned map replaces the parent's `:data` slot.
4. Emits `:rf.machine/done` (per [§Trace events](#trace-events)) with `:machine-id` (the child), `:output result`, `:parent-id`.
5. Tears down the child via the existing destroy path with `:reason :rf.machine/finished` enriched onto the `:rf.machine/destroyed` trace.
6. Clears the child's `[:rf/system-ids <sid>]` reverse-index entry (if it had one) **after** step 3 — so `:on-done` can still read the binding.

### Sub-decisions (locked per rf2-gn80)

| # | Decision |
|---|---|
| D1 | **`:final?` is a first-class key on the state node**, not stashed under `:meta`. Visibility wins — `:final?` is a strong runtime signal and authors / AI agents see it at the state level. |
| D2 | The parent-notification hook is **`:on-done` on the parent's `:invoke` map** (mirrors `:on-spawn`). Signature `(fn [data result] new-data)` — uniform with other machine callbacks (operates on `:data`, returns the new `:data` map). |
| D3 | Output is sourced via **`:output-key` on the child's final state** — a designated key into the child's `:data`. There is **no `:output-fn` escape hatch**; one explicit primitive, not two. Apps wanting computed output write a `:action` on the transition INTO the final state that stashes the computed value at `:output-key`. |
| D4 | **Auto-destroy is synchronous** and happens on the same tick the machine entered `:final?`. The standard destroy path runs (in-flight HTTP aborts, registrar unregister, `[:rf/spawned ...]` slot clear, `[:rf/machines <id>]` snapshot dissoc). |
| D5 | A dispatch arriving at the now-destroyed actor address is handled by the **existing destroyed-frame trace path** — `:rf.error/no-such-handler` (or the per-runtime equivalent). No new `:rf.machine/dispatched-while-done` half-state is introduced. |
| D6 | **New trace event `:rf.machine/done`** carries `:machine-id`, `:output`, `:parent-id` (the parent's registration id, or `nil` for singletons). The existing `:rf.machine/destroyed` trace is **enriched** with a `:reason` tag — one of `:rf.machine/finished`, `:explicit`, or `:parent-unmount-cascade`. |
| D7 | **Singleton symmetry** — a singleton (non-spawned, non-invoked) machine reaching `:final?` ALSO auto-destroys. Footgun note for skill docs: *if you want a persistent terminal state, omit `:final?`*. |
| D8 | **`:system-id` interaction** — the runtime auto-clears `[:rf/system-ids <system-id>]` reverse-index entry on done. The clear runs **after `:on-done` fires** so the hook can still read the binding. |
| D9 | Specified and implemented in one delivery (no post-v1 deferral). |
| D10 | Capability-matrix axis: **`:fsm/final-states`** (naming consistent with `:fsm/parallel-regions`, `:fsm/tags`). Conformance fixtures `final-state-singleton-auto-destroys` and `final-state-child-fires-on-done` exercise the contract. |

### `:final?` constraints

- **Leaf-only.** A state declaring `:final? true` MUST NOT declare `:states` (or `:initial`). Compound states cannot themselves be final — their finality is expressed by a leaf inside them. `create-machine-handler` rejects compound `:final?` declarations at registration with `:rf.error/machine-final-state-compound`.
- **No `:on`, `:always`, `:after`, `:invoke`, `:invoke-all` on a `:final?` state.** Final means final — no further transitions. `create-machine-handler` rejects these combinations at registration with `:rf.error/machine-final-state-has-transitions`. `:entry` and `:exit` actions ARE permitted (the final-state's `:entry` runs as part of the entering cascade; `:exit` runs from the auto-destroy teardown).
- **`:output-key` requires `:final?`.** A non-final state declaring `:output-key` is a registration error (`:rf.error/machine-output-key-without-final`). On a final state, `:output-key` is optional — when absent, the `result` passed to `:on-done` is `nil`.
- **Parallel regions and `:final?`.** A leaf inside one region of a parallel-region machine may declare `:final? true`; the meaning is "**this region** has reached its final state." That region halts (no further transitions accepted for it; sibling regions continue). The parent machine as a whole is `:final?` only when EVERY region's active state is `:final?` — at which point the auto-destroy and `:on-done` cascade fires as usual. (This composability uses the existing parallel-region routing; no new primitive.)

### Composition with `:entry` / `:exit`

A final state's `:entry` action runs as part of the entering cascade (before the auto-destroy fires). A final state's `:exit` action runs from the auto-destroy teardown — same ordering convention as the user-supplied `:exit` running before the auto-destroy for ordinary `:invoke`-bearing states. The user's `:exit` therefore gets to read the final snapshot (including `:data`'s `:output-key`-designated slot) before auto-destroy clears it.

### Trace events fired on done

Synchronous ordering (per D4):

1. `:rf.machine/done` — emitted with `:machine-id` (the finishing actor), `:output` (the value read at `:output-key`, or `nil`), `:parent-id` (the parent's registration id, or `nil` for singletons).
2. `:rf.machine/destroyed` — enriched with `:reason :rf.machine/finished` (the discriminator that distinguishes "the actor finished naturally" from "the parent cascade destroyed it").
3. `:rf.machine/system-id-released` — when the actor was `:system-id`-bound. Fires AFTER `:on-done` ran (so `:on-done` could still look up the binding).

Existing observers that filter `:rf.machine/destroyed` on `:tags` see the new `:reason` tag additively — no breaking change.

### Cross-references

- [§Spec-spec keys](#spec-spec-keys) — `:on-done` is listed alongside `:on-spawn` on the parent's `:invoke` map.
- [§Deliberate omissions vs xstate](#deliberate-omissions-vs-xstate) — the `onDone` row now records that re-frame2 DOES ship final-state-with-on-done.
- [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rftransition-table) — schema for `:final?` and `:output-key`.
- [Spec 009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary) — `:rf.machine/done` registration.
- Conformance fixtures: `final-state-singleton-auto-destroys.edn`, `final-state-child-fires-on-done.edn`.
- rf2-gn80 — the bead that locked these decisions.

## Wall-clock timeouts on `:invoke` — use parent state's `:after`

`:invoke` and `:invoke-all` do **not** carry their own `:timeout-ms` slot. Wall-clock timeouts on a state hosting an `:invoke` are expressed by adding an `:after` entry on the **parent state**: when the timer fires, the standard exit cascade tears down the in-flight child via `:rf.machine/destroy` and the parent transitions to whichever target the `:after` entry names. `:after` is the **single canonical primitive** for "after N ms in this state, do X"; no second mechanism is needed for the `:invoke`-bearing case.

### Why one primitive, not two

An earlier draft of this spec carried a `:timeout-ms` slot on `:invoke` / `:invoke-all` for "the whole spawned actor must terminate within N ms (spanning retries)." That slot is **dropped**. The motivating use case — a boot machine wanting "the auth phase completes in 30 s total, including retries" — is fully served by the parent state's `:after` map (per [§Whichever fires first wins](#whichever-fires-first-wins) and the cancellation cascade). Maintaining two timeout mechanisms (state-level `:after` + invoke-level `:timeout-ms`) created a learnability tax with no expressive benefit. Per the boot-as-state-machine §M3 follow-up (rf2-1lop), the M3 finding's resolution is now "use the parent state's `:after`."

```clojure
{:authenticating
 {:invoke {:machine-id :auth-flow
           :on-spawn   :record-auth}
  :after  {30000 :auth-failed}                 ;; wall-clock guard — spans retries inside the child
  :on     {:auth/succeeded :authenticated}}}
```

When the 30000 ms `:after` timer fires, the parent's exit cascade destroys the `:auth-flow` child (which itself cascades any in-flight `:rf.http/managed` aborts per the [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts) contract, rf2-wvkn), and the machine transitions to `:auth-failed`. The wall-clock spans the child's retries because the timer is anchored to **state entry** of `:authenticating`, not to any individual HTTP attempt; the child's internal retry behaviour does not affect the parent's `:after` countdown.

Symmetric for `:invoke-all`:

```clojure
{:hydrating
 {:invoke-all
  {:children         [{:id :cfg  :machine-id :load-config}
                      {:id :flag :machine-id :load-feature-flags}
                      {:id :user :machine-id :load-user-profile}
                      {:id :dash :machine-id :load-dashboards}]
   :join             :all
   :on-child-done    :asset/loaded
   :on-child-error   :asset/failed
   :on-all-complete  [:hydrate/done]
   :on-any-failed    [:hydrate/failed]}
  :after {60000 :hydrate/timed-out}             ;; whole-join wall-clock guard
  :on   {:hydrate/done       :ready
         :hydrate/failed     :error
         :hydrate/timed-out  :degraded}}}
```

The 60000 ms `:after` fires if the join hasn't resolved by the deadline; the standard exit cascade cancels every surviving child (the `:invoke-all` desugared `:exit` action handles per-child cleanup, same as cancel-on-decision per [§Cancel-on-decision](#cancel-on-decision-default-true)), and the parent transitions to `:degraded`. No `:timeout-ms` slot, no `:on-timeout` slot, no `:rf.machine.invoke/timed-out` trace — the standard `:after` machinery covers everything the dropped `:timeout-ms` slot used to.

### Partial-progress is not preserved

A `:after`-driven cascade out of the `:invoke`-bearing state destroys any spawned child and clears the runtime spawn-registry slots; the parent's transition handler may not assume any of the child's partial state has flushed back into the parent's `:data`. For `:invoke-all`, the join state at `[:rf/spawned <parent> <invoke-id> :join]` is destroyed alongside the children — the parent cannot read which children had completed at the moment of timeout. Apps that need "take whatever loaded by the deadline" semantics declare a separate `:always` on the parent state that fires `:on-some-complete` when a partial-success guard becomes true, per the `:after` + partial-success idiom documented under [§Spawn-and-join via `:invoke-all` §Composition with hierarchy and `:after`](#composition-with-hierarchy-and-after).

### Cross-references

- [§Whichever fires first wins](#whichever-fires-first-wins) — the cancellation cascade that an `:after` firing triggers is the same cascade as a parent-destroys-child shutdown.
- [§Delayed `:after` transitions](#delayed-after-transitions) — the canonical primitive's full grammar and semantics.
- Boot-as-state-machine §M3 (rf2-1lop) — the boot-machine use case that originally motivated `:timeout-ms`; the M3 finding's resolution is now "use the parent state's `:after`."
- [MIGRATION §M-44](MIGRATION.md#m-44-timeout-ms-removed-from-invoke--invoke-all--use-parent-states-after) — pre-1.0 spec lock; the dropped-slot record.

## Cancellation cascade — in-flight `:rf.http/managed` aborts

> **Resolves boot-as-state-machine §M2 (rf2-wvkn).** The pre-resolution gap was: when a parent state machine cancels a spawned child mid-execution (parent state exit, parent destroy, `:after` firing, `:invoke-all` cancel-on-decision), what happens to in-flight `:rf.http/managed` requests the child kicked off? Spec 005 + Spec 014 didn't explicitly cover the cross-feature contract. This section is the contract.

### The contract

When the runtime destroys a spawned actor — by **any** trigger — every in-flight `:rf.http/managed` request that was issued from inside that actor's event handlers is aborted. Triggers include:

1. **Parent state exit.** The standard exit cascade emits `:rf.machine/destroy` for the `:invoke`d child (per [§Declarative `:invoke` §Desugaring rules](#desugaring-rules)). The destroy handler aborts the child's in-flight HTTP.
2. **Parent's `:after` firing.** `:after` exit is a state exit; the cascade above runs unchanged (per [§Whichever fires first wins](#whichever-fires-first-wins)).
3. **`:invoke-all` cancel-on-decision.** When the join resolves and `:cancel-on-decision?` is `true` (the default), the runtime emits `:rf.machine/destroy` per surviving sibling (per [§Cancel-on-decision](#cancel-on-decision-default-true)). Each siblings' in-flight HTTP aborts.
4. **`:invoke-all` parent state exit.** Symmetric to (1), but the per-child teardown loop (per [§Spawn-id tracking](#spawn-id-tracking)) cascades the abort to every child the `:children` map tracks.
5. **Imperative `[:rf.machine/destroy <actor-id>]`.** A user-authored destroy action emitting the legacy keyword form (per the spawn-fx 5-arity destroy) ALSO aborts that actor's in-flight HTTP. The contract is uniform across triggers — **wherever an actor is destroyed, its HTTP cascades to abort.**
6. **Frame destroy.** `frame.cljc`'s frame-exit walk over surviving machine instances destroys each in turn (per [Spec 002 §Lifecycle](002-Frames.md#frame-lifecycle)); each destroy fires the same abort-on-actor-destroy hook.

The abort surfaces as a normal `:rf.http/aborted` failure on the request's reply path — the `:on-failure` callback (or the merged-reply default) sees `{:kind :rf.http/aborted :reason :actor-destroyed}` per [Spec 014 §Aborts](014-HTTPRequests.md#abort-on-actor-destroy). For most calling code there is no observable difference from a manual `:rf.http/managed-abort`; the `:reason :actor-destroyed` discriminates for callers that care.

### What is "in-flight inside an actor"

A request is "in-flight inside actor `<spawned-id>`" if and only if its originating event vector's first element was `<spawned-id>`. The originating event vector flows to the `:rf.http/managed` fx through the standard fx 5-arity (`:event` on the fx ctx, per [Spec 002 §Routing the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope)), and the http fx records the (`request-id`, `actor-id`) tuple in its in-flight registry alongside the abort-handle.

The actor-id is **the spawned actor's own machine address** (e.g., `:http/post#1`), not the parent's address. A request that the parent (`:auth/main`) issued directly is NOT in-flight inside any spawned actor — it is in-flight inside the parent's event-handler context, which has no spawn-registry slot. The parent's request is unaffected by any child-actor destroy. See [§Open question — direct dispatches from event handlers](#open-question--direct-dispatches-from-event-handlers).

### What about the request's own `:request-id`?

The `:request-id` (per [Spec 014 §Aborts](014-HTTPRequests.md#aborts)) is **orthogonal** to the actor-id. A request can carry both (a stable `:request-id` for app-level abort/supersede AND an actor-id stamped by the runtime); the in-flight registry indexes both ways. A `:rf.http/managed-abort` fx with the request-id aborts the one request; the actor-destroy hook walks every request whose actor-id matches and aborts each. Neither indexing supersedes the other; they coexist.

If a request was issued without `:request-id` from inside a spawned actor, it is still tracked by actor-id and is still aborted on actor-destroy. The `:request-id` is for app-level addressability; actor-id tracking is for runtime cleanup.

### Open question — direct dispatches from event handlers

Events dispatched directly from ordinary `reg-event-fx` handlers — i.e. the originating event vector is for an event-id that is NOT a spawned actor's address — issue `:rf.http/managed` requests that are NOT subject to the actor-destroy cancellation cascade. There is no actor-id to correlate against.

This is **deliberate.** Cancellation tied to actor lifetime is semantically the right scope: the child actor exists to run until the parent says "we no longer care"; the parent saying so kills the actor and the actor's outstanding work. An ordinary event handler has no analogous lifecycle peg — its work is launched as a side effect and outlives the handler that fired it; the only way to abort it is via an explicit `:rf.http/managed-abort` keyed on the user-supplied `:request-id`, exactly as before this contract.

If an app wants HTTP requests that are tied to a state's lifetime, the answer is to spawn a child machine that issues them — the `:invoke` or `:invoke-all` declaration is the explicit way to bind HTTP-request lifetime to state-occupancy lifetime. There is no ambient "abort on every state transition out" sugar for direct-dispatch HTTP.

### The hook

The destroy-side abort fires through a late-bind hook (per [`re-frame.late-bind`](../implementation/core/src/re_frame/late_bind.cljc)) — `re-frame.machines` does NOT statically `:require` `re-frame.http-managed`. The hook key is `:http/abort-on-actor-destroy`; the http artefact registers a fn `(fn [actor-id])` at ns-load time; the machines artefact's destroy path looks the fn up at call time and invokes it once per destroyed actor. When the http artefact is not on the classpath the hook resolves to nil and the destroy proceeds without any abort cascade — apps that don't issue managed-HTTP requests pay nothing.

Symmetric to how `re-frame.machines` already publishes `:machines/spawn-fx` / `:machines/destroy-machine-fx` (per [`re-frame.late-bind` hook table](../implementation/core/src/re_frame/late_bind.cljc)) and how `re-frame.flows` and `re-frame.routing` flow up their own seams.

### Trace event

Each individual abort emits `:rf.http/aborted-on-actor-destroy` (registered in [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)). One trace per cancelled request. The trace's `:tags` carry `:request-id` (when set on the request), `:actor-id` (the destroyed spawned-actor address), and `:url` (the request's URL).

The reply payload itself is a standard `:rf.http/aborted` failure; tools that subscribe to the http-failure-category trace stream see this category alongside the user-initiated aborts. The `:reason :actor-destroyed` tag is the discriminator.

### Why one mechanism, not two

The same hook fires across every destroy trigger — `:invoke` exit, `:invoke-all` exit, cancel-on-decision, `:after` cascade, frame destroy. There is no per-trigger HTTP-abort code path. This means:

- Authors writing a `:invoke`-based child whose body fires `:rf.http/managed` get cleanup automatically — no `:exit` action threading `:rf.http/managed-abort` calls per known `:request-id`.
- The "parent reloads mid-flight" case (boot-as-state-machine §M2, rf2-wvkn) is covered by the frame-destroy walk firing the same hook against every surviving machine.
- The exit cascade from `:after` (per [§Whichever fires first wins](#whichever-fires-first-wins)) reuses the destroy path, so the wall-clock-timeout case is identical to the parent-decides-to-cancel case.

### Cross-references

- [Spec 014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy) — the http side of the contract; trace event registration; envelope details.
- [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) — `:rf.http/aborted-on-actor-destroy` category registration.
- [§Declarative `:invoke` §Desugaring rules](#desugaring-rules) — the `:rf.machine/destroy` fx that fires the hook.
- [§Cancel-on-decision](#cancel-on-decision-default-true) — `:invoke-all`'s sibling-cancel cascade routes through the same destroy fx.
- [§Whichever fires first wins](#whichever-fires-first-wins) — the `:after` cascade routes through the same destroy fx.
- Boot-as-state-machine §M2 (rf2-wvkn) — the original gap analysis.

## Spawn-and-join via `:invoke-all`

`:invoke-all` is **declarative sugar** for "spawn N children in parallel, fire one of three parent events when the join condition resolves." It is the answer to the boot-as-state-machine pattern: hydrate phases that fan out N requests and join on a `:seen-all-of?` predicate (per boot-as-state-machine §M1, rf2-6vmw).

`:invoke-all` is **registration-time sugar.** `create-machine-handler` walks the spec at construction time and rewrites every `:invoke-all` slot into entry/exit actions emitting N parallel `:rf.machine/spawn` fx (on entry) and per-child `:rf.machine/destroy` fx (on exit), plus an internal join-state hook that watches the parent's events for child-completion signals and fires the parent-level join event when the join condition resolves.

### The pattern

```clojure
{:hydrating
 {:invoke-all
  {:children         [{:id :cfg  :machine-id :load-config       :on-spawn :record-cfg}
                      {:id :flag :machine-id :load-feature-flags :on-spawn :record-flag}
                      {:id :user :machine-id :load-user-profile  :on-spawn :record-user}
                      {:id :dash :machine-id :load-dashboards    :on-spawn :record-dash}]
   :join             :all                      ;; or :any, {:n N}, or {:fn pred}
   :on-child-done    :child/done               ;; child → parent event keyword for success
   :on-child-error   :child/error              ;; child → parent event keyword for failure
   :on-all-complete  [:assets-loaded]          ;; fires when join condition is met by completions
   :on-any-failed    [:asset-load-failed]      ;; fires when any child fails (default — see §Join semantics)
   :on-some-complete [:partial-load]}          ;; fires when {:n N} or :any is met
  :on    {:assets-loaded     :ready
          :asset-load-failed :error
          :partial-load      :degraded}}}
```

While in `:hydrating`, four child actors are alive at `[:rf/machines <gensym'd-id>]`. Each child reaches a terminal state and dispatches `[<parent-id> [:child/done :cfg & extra]]` (or `[:child/error :cfg & extra]`) back. The runtime intercepts these events at the parent's machine boundary, updates the join state at `[:rf/spawned <parent-id> <invoke-id>]`, evaluates the join condition, and on resolution fires `[:on-all-complete-or-friend ...]` into the parent (an ordinary FSM event the parent's `:on` handles) AND cancels any siblings still in flight.

### Spec-spec keys

The map under `:invoke-all` accepts the following keys:

| key | purpose | required? |
|---|---|---|
| `:children` | a vector of **invoke-spec** maps — same shape as `:invoke` (see [§Spec-spec keys](#spec-spec-keys)) plus a required `:id` keyword for join-state addressing | required, vector of ≥ 1 |
| `:join` | join-condition discriminator: `:all` (default), `:any`, `{:n N}`, `{:fn (fn [{:keys [done failed]}] ...)}` | optional; default `:all` |
| `:on-child-done` | event keyword the parent's children dispatch back on success — runtime intercepts and updates join state | required |
| `:on-child-error` | event keyword the parent's children dispatch back on failure — runtime intercepts and updates join state | required |
| `:on-all-complete` | event vector the runtime dispatches into the parent when `:join :all` resolves with all-complete | required iff `:join :all` |
| `:on-some-complete` | event vector the runtime dispatches into the parent when `:join :any` / `{:n N}` / `{:fn ...}` resolves on the success-side | required iff `:join` ≠ `:all` |
| `:on-any-failed` | event vector the runtime dispatches into the parent when any child fails (default cancel-on-decision applies) | optional; if absent, child failures are tracked but do not short-circuit the join |
| `:cancel-on-decision?` | `true` (default) cancels siblings still in flight when the join resolves; `false` lets siblings run to completion (their results land in the join-state but trigger no further parent events) | optional; default `true` |

Each child invoke-spec under `:children` accepts the same keys as a single `:invoke` (`:machine-id` xor `:definition`, `:data`, `:id-prefix`, `:on-spawn`, `:start`, `:invoke-id`, `:system-id`) **plus** a required `:id` keyword that names the child for join-state addressing. The `:id` keyword is the user-supplied name the parent's `:on-child-done` / `:on-child-error` events carry as the second-position payload arg (see [§Child completion protocol](#child-completion-protocol)).

> **Wall-clock timeouts: use the parent state's `:after` slot.** `:invoke-all` does not carry a `:timeout-ms` slot; phase-level wall-clock guards on the join are expressed via `:after` on the `:invoke-all`-bearing state. Per [§Wall-clock timeouts on `:invoke` — use parent state's `:after`](#wall-clock-timeouts-on-invoke--use-parent-states-after), an `:after` firing exits the state and the desugared `:exit` action cancels every surviving child via the standard exit cascade.

### Join semantics

The runtime tracks per-state join state at `[:rf/spawned <parent-id> <invoke-id> :join]` — a map of:

```clojure
{:children {:cfg :load-config#1 :flag :load-feature-flags#2 :user :load-user-profile#3 :dash :load-dashboards#4}
 :done     #{:cfg :flag}     ;; ids of children whose :on-child-done fired
 :failed   #{}                ;; ids of children whose :on-child-error fired
 :resolved? false}            ;; flips to true when the join condition resolves; subsequent events ignored
```

Where `:children` is the per-`:id` map of user-supplied id → spawned actor id. `:done` and `:failed` are sets of user-supplied ids that have signalled completion. `:resolved?` is the latch that prevents a second join-event firing once the condition has been met.

Join condition discriminators:

- **`:all` (default)** — fires `:on-all-complete` once `:done` covers every `:id`. If `:on-any-failed` is present and any child errors, it fires immediately and the join short-circuits. If `:on-any-failed` is absent, child failures are tracked but the join waits for `:done` to cover every `:id` (failed children never join the `:done` set, so the join never resolves on success — equivalent to the failure tearing down the parent's surrounding state via a separate transition).
- **`:any`** — fires `:on-some-complete` after the first `:on-child-done`. If `:on-any-failed` is present, the first child error fires it instead.
- **`{:n N}`** — fires `:on-some-complete` after the Nth `:on-child-done`. Failures handled per `:on-any-failed` as above.
- **`{:fn (fn [{:keys [done failed]}] truthy)}`** — user-supplied predicate; fires `:on-some-complete` when truthy.

### Cancel-on-decision (default `true`)

When the join condition resolves — `:on-all-complete`, `:on-some-complete`, or `:on-any-failed` fires — siblings still in flight are cancelled by default. Each cancelled sibling has its `:rf.machine/destroy` fx fired (the same one the exit-cascade would fire), and the runtime emits a `:rf.machine.invoke/cancelled-on-join-resolution` trace event per cancelled actor. **Cancellation is the right default for boot-as-state-machine**: when "all assets loaded" fires, no value is added by letting an in-flight sibling continue to consume bandwidth and dispatch into a parent that has already moved on.

Apps that want non-cancelling joins (e.g. analytics fan-out where each child is independently valuable) declare `:cancel-on-decision? false`. In that case siblings run to completion; their `:on-child-done` / `:on-child-error` events still update the join state (the `:resolved?` latch already flipped, so no further parent event fires) and tools observing the join-state see the full late-completion record. The `:on-some-complete` / `:on-all-complete` semantic remains "fired exactly once when the condition was first met."

The implicit assumption: the parent's surrounding state is exited by the join-event's transition before the cancellation fx runs, so the exit cascade's standard auto-destroy machinery handles the cancellation as part of the same exit (sibling teardown is just "the exit cascade runs while N children are still alive"). This means cancel-on-decision is **not a separate cancellation primitive** — it composes with the existing `:invoke` exit-cascade behaviour.

### Child completion protocol

Each child decides when it is "done" or "failed" and dispatches a 2- or 3-element event vector back to the parent:

```clojure
;; In the child machine (e.g. :load-config), at a terminal state's :entry:
{:done
 {:meta  {:terminal? true}
  :entry (fn [data _]
           {:fx [[:dispatch [:hydrate-flow [:child/done :cfg (:result data)]]]]})}}
```

The dispatched event has shape `[<parent-id> [<event-keyword> <child-id> & extra]]` where:

- `<parent-id>` is the parent machine's id (the `:rf.machine/spawn` site stamps `:rf/parent-id` so the child can pick it up if dynamic addressing is needed; for static `:invoke-all` declarations the parent-id is a literal in the parent's source, so the child simply hard-codes it).
- `<event-keyword>` is `:on-child-done` or `:on-child-error` per the parent's spec.
- `<child-id>` is the user-supplied `:id` from the parent's invoke-all entry.
- `& extra` is whatever the child wants to forward to the parent — typically the child's final `:data` slice or an error reason; the parent's join-resolution event handler can read this from the event vector that fired the join.

The runtime intercepts these events at the parent's `create-machine-handler` boundary. Specifically, the parent's handler checks `event[1][0]` (the inner event keyword) against `:on-child-done` / `:on-child-error` declared on the currently-active state's `:invoke-all` entry. On match, the handler:

1. Updates the join state at `[:rf/spawned <parent-id> <invoke-id> :join]` (adds `<child-id>` to `:done` or `:failed`).
2. Evaluates the join condition.
3. If the condition resolves AND `:resolved?` is false: flips `:resolved?` true; if `:cancel-on-decision?` is true, emits `:rf.machine/destroy` fx for each in-flight sibling; dispatches the join event into the parent (`:on-all-complete` / `:on-some-complete` / `:on-any-failed` per the resolution kind).
4. If the condition does not resolve: the event is treated as handled (no further parent state transition).

The intercepted event is **not** fed into the parent's normal `:on` lookup — the runtime consumes it for join bookkeeping only. Parents that need to see per-child completion separately from the join-event can declare additional `:on` entries for `:child/done` / `:child/error`; those entries fire only on events whose state does NOT have an `:invoke-all` declaring those keywords. This sounds delicate but in practice the ergonomic shape is: the user picks distinct event keywords for `:invoke-all` interception (commonly `:invoke-all/child-done` / `:invoke-all/child-error`) so they don't collide with the parent's own per-child observers.

### Per-child terminal events still fire

The runtime intercepts `:on-child-done` / `:on-child-error` at the **parent's** boundary; the events still fire **inside the child** as ordinary terminal-state events first (the dispatch-back is the child's last act). So per-child traces (`:rf.machine/transition` for the child's terminal transition; `:rf.machine.lifecycle/destroyed` for the child's actor going away) are unchanged. The join layer is *additional*, not a replacement.

### Spawn-id tracking

The runtime spawn registry (per [§Declarative `:invoke` (sugar over spawn)](#declarative-invoke-sugar-over-spawn) and rf2-t07u) extends naturally for `:invoke-all`: `[:rf/spawned <parent-id> <invoke-id>]` becomes a map with two slot kinds — the per-child id-map under `:children`, and the join-state metadata (`:done`, `:failed`, `:resolved?`) for the live `:invoke-all` instance. Pre-`:invoke-all` declarative-`:invoke` spawns continue to write the keyword `<spawned-id>` directly under that key (their `:invoke-id` resolves a leaf actor address); `:invoke-all` instances write the map shape. Both forms are read-disambiguated by the value type (`map?` vs `keyword?`) at the existing destroy-resolution call site.

The `[:rf/spawned <parent-id> <invoke-id> :children <child-id>]` slot resolves to the gensym'd actor id for that child. The `[:rf/spawned <parent-id> <invoke-id> :join]` slot holds the per-state join state map. On state-exit (whether by normal transition, cancellation, or any other code path), the auto-destroy cascade tears down every entry under `:children` and clears the slot per the lazy-allocation invariant.

### Trace events

The runtime emits four `:invoke-all`-specific trace events:

- `:rf.machine.invoke-all/started` — fires on entry to an `:invoke-all`-bearing state, after all N children have been spawned. `:tags {:machine-id <id> :state <state> :invoke-id <prefix-path> :child-ids #{:cfg :flag :user :dash} :children {:cfg :load-config#1 ...}}`.
- `:rf.machine.invoke-all/all-completed` — fires when `:on-all-complete` resolves. `:tags {:machine-id <id> :invoke-id <prefix-path> :done #{...}}`.
- `:rf.machine.invoke-all/any-failed` — fires when `:on-any-failed` resolves. `:tags {:machine-id <id> :invoke-id <prefix-path> :failed-id <id> :reason <event-payload>}`.
- `:rf.machine.invoke/cancelled-on-join-resolution` — fires once per sibling cancelled by `:cancel-on-decision? true`. `:tags {:machine-id <parent-id> :invoke-id <prefix-path> :child-id <user-id> :spawned-id <gensym'd-id> :join-event <:on-all-complete | :on-some-complete | :on-any-failed>}`.

A `:rf.machine.invoke-all/some-completed` trace fires for the `:any` / `{:n N}` / `{:fn ...}` resolution kinds — symmetric to `:all-completed` but for partial-success join semantics.

### Worked example — auth flow with parallel asset hydration

```clojure
{:initial :authenticating
 :states
 {:authenticating
  {:invoke {:machine-id :http/post
            :data       (fn [snap _] {:url "/api/login"
                                      :body (-> snap :data :credentials)})}
   :on     {:auth/succeeded :hydrating
            :auth/failed    :idle}}

  :hydrating
  {:invoke-all
   {:children         [{:id :cfg  :machine-id :load-config}
                       {:id :flag :machine-id :load-feature-flags}
                       {:id :user :machine-id :load-user-profile}
                       {:id :dash :machine-id :load-dashboards}]
    :join             :all
    :on-child-done    :asset/loaded
    :on-child-error   :asset/failed
    :on-all-complete  [:hydrate/done]
    :on-any-failed    [:hydrate/failed]}
   :on    {:hydrate/done   :ready
           :hydrate/failed :error}}

  :ready {}
  :error {}
  :idle  {:on {:submit :authenticating}}}}
```

The walk-through:

1. User submits → `:authenticating` spawns one `:http/post` child.
2. The HTTP child posts; on success dispatches `[<parent-id> [:auth/succeeded ...]]` → state moves to `:hydrating`.
3. Entering `:hydrating` triggers `:invoke-all`'s desugared entry: spawn four children in parallel. Each child is a registered machine that fetches its own asset and dispatches `[<parent-id> [:asset/loaded :cfg ...]]` (or `[:asset/failed :cfg <reason>]`) on completion.
4. As each `:asset/loaded` arrives, the runtime intercepts at the parent boundary, updates `[:rf/spawned :auth-flow [:hydrating] :join :done]`, and evaluates `:all`. Once all four `:done`, the runtime fires `[:hydrate/done ...]` into the parent → state moves to `:ready`.
5. If any child fails first, `[:hydrate/failed ...]` fires; the runtime cancels the surviving siblings (their `:rf.machine/destroy` fx is emitted; `:rf.machine.invoke/cancelled-on-join-resolution` traces fire); state moves to `:error`.
6. If the user reloads the page mid-hydration, the standard frame-destroy cascade tears down every actor (the `:hydrating` state's exit fires every `:children` destroy). The `:invoke-all` declaration is correct-on-every-code-path.

The key property: the parent has no per-child bookkeeping in `:data`. The `:done` / `:failed` sets, the `:children` id map, the resolution latch — all runtime-owned at `[:rf/spawned :auth-flow [:hydrating] :join]`. The author writes the four child-specs and the three event hooks and the runtime handles everything else.

### Composition with hierarchy and `:after`

`:invoke-all`'s entry/exit actions compose with the standard hierarchical entry/exit cascading machinery just like `:invoke`'s do — the desugar produces ordinary `:entry` / `:exit` actions that the cascade machinery picks up. `:after` on the same state node is the **canonical** way to set a wall-clock timeout on the whole join — `{60000 :hydrate/timed-out}` fires `:hydrate/timed-out` if the join hasn't resolved in 60 s; the parent's `:on` for `:hydrate/timed-out` transitions out, which exits the state and tears down all surviving children via the desugared exit cascade. Per [§Wall-clock timeouts on `:invoke` — use parent state's `:after`](#wall-clock-timeouts-on-invoke--use-parent-states-after), this is the **single** wall-clock-timeout mechanism on `:invoke-all`-bearing states; there is no second `:timeout-ms` surface.

A common partial-success idiom is to declare `:after` for the phase-level timeout and let the timeout transition land in a state whose `:always` checks `[:rf/spawned <parent> <invoke-id> :join :done]` against a partial-success guard — the parent reads which children completed before the deadline and decides whether to proceed with degraded data or to fail outright. The cleanest expression is a separate transition out of the `:invoke-all`-bearing state, which the existing `:after` machinery delivers without any `:invoke-all`-specific extension.

### Capability gating

`:invoke-all` is gated under the `:actor/spawn-and-join` capability per [§Capability matrix](#capability-matrix). A port that doesn't claim it rejects `:invoke-all` at registration time with `:rf.error/machine-grammar-not-in-v1`. The v1 CLJS reference claims it.

### Errors

`:invoke-all` introduces three new registration-time error categories on top of the existing `:rf.error/machine-*`:

- `:rf.error/machine-invoke-all-bad-shape` — a child invoke-spec is missing `:id` or both `:machine-id` and `:definition`; or `:invoke-all` is not a vector; or the join-event slots are missing per the required-iff rules above.
- `:rf.error/machine-invoke-all-duplicate-id` — two child invoke-specs share an `:id` keyword. Each `:id` must be unique inside the same `:invoke-all` block.
- `:rf.error/machine-invoke-all-with-invoke` — a state node declares both `:invoke` and `:invoke-all`; the combination is rejected.

Cross-references: [§Spawning](#spawning--dynamic-actors) for the imperative-spawn surface; [§Declarative `:invoke` (sugar over spawn)](#declarative-invoke-sugar-over-spawn) for the per-child sugar that `:invoke-all` extends; [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rftransition-table) for the schema; [Pattern-Boot](Pattern-Boot.md) for boot-flow worked examples leveraging `:invoke-all` for hydrate-as-spawn-and-join.

## Cross-spec interactions

### Retry-ownership boundary with `:rf.http/managed`

State machines own **semantic retry**; `:rf.http/managed` owns **transport-level retry**. Per [Spec 014 §Boundary — transport vs semantic retry](014-HTTPRequests.md#boundary--transport-vs-semantic-retry), the test for which owner applies is whether the retry decision is a function of attempt count + failure category alone (transport — owned by `:retry`) or depends on response body / app state / another request's outcome (semantic — owned by the machine). A state spec's `:invoke` of `:rf.http/managed` configures transport retry on the request itself; the machine's transition on the resulting `:succeeded` / `:failed` reply expresses the semantic retry — re-target to a refresh state, delay via `:after` before re-issuing, route to a different state on a different failure category. The two layers compose without overlap. See [Pattern-Boot §Worked example — auth-machine and the retry-ownership boundary](Pattern-Boot.md#worked-example--auth-machine-and-the-retry-ownership-boundary) for the canonical illustration.

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

(rf/machine-by-system-id :primary-request)
;; → :request/protocol#42 (the gensym'd id), or nil if no spawn
;;   under the active frame is currently bound to that :system-id.
;;   Implementation: (get-in app-db [:rf/system-ids :primary-request])
;;   in the active frame's app-db. See [§Named addressing via
;;   :system-id](#named-addressing-via-system-id).
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

See also [API.md §Machines](API.md#machines).

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

### The `:rf/machine-has-tag?` predicate sub

Alongside `:rf/machine` the framework ships **`:rf/machine-has-tag?`** — a predicate sub that answers the containment question for one tag without forcing the view to read (and depend on) the whole snapshot:

```clojure
(rf/reg-sub :rf/machine-has-tag?
  (fn [db [_ machine-id tag]]
    (contains? (get-in db [:rf/machines machine-id :tags]) tag)))
```

**Arguments.** Two: the `machine-id` keyword and the `tag` keyword. Both are required; neither varies — there is no varargs form, no path-drilling, no default. The sub vector is `[:rf/machine-has-tag? <machine-id> <tag>]`.

**Return contract.** Strictly `true` | `false`. Returns `true` iff the named machine's snapshot's `:tags` set contains `tag`. Returns `false` for every other case — `tag` absent, snapshot present but `:tags` elided (no active state declares tags), or no snapshot at all (unknown or not-yet-initialised machine). Never returns `nil`; the predicate shape is total over `(machine-id, tag)` pairs.

**Re-render granularity.** The sub is **derived** — it reads the snapshot via `get-in` rather than chaining off `:rf/machine` — so the reaction emits only when *this tag's containment-bit* flips. A view that asks `(rf/has-tag? :ui/nine-states :data/loading)` does not re-render when `:state`, `:data`, `:meta`, or *other* tags change; only when `:data/loading` is added to or removed from `:tags`. Reagent's built-in equality dedup gates the boolean return.

```clojure
;; canonical sugar — single call site
@(rf/has-tag? :ui/nine-states :data/loading)
;; => true | false

;; equivalent explicit form
@(rf/subscribe [:rf/machine-has-tag? :ui/nine-states :data/loading])
```

For the full tag-set narrative — what `:tags` is, how the runtime computes it at every transition, what the user-vs-runtime ownership boundary looks like — see [§State tags](#state-tags). This section catalogues only the subscription surface.

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
(ns circle-drawer.machine
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
  {:doc "Persist a circle's new radius. Called by the editor machine on commit."}
  [undoable]
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
  [undoable]
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
| **State tags** — `:tags <set-of-keywords>` on a state node; snapshot carries the active-configuration tag union | Prose: [§State tags](#state-tags); Schema: `:rf/state-node` extended for `:tags`, `:rf/machine-snapshot` extended for `:tags` (see [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rfstate-node) and [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)); Fixtures: `tags-flat-machine`, `tags-compound-active-path-union`, `tags-empty-when-no-declaration`, `tags-round-trip-pr-str` | ✓ claimed (specified) | Strictly additive — the snapshot's `:tags` slot is elided when the union is empty. Framework sub `:rf/machine-has-tag?` plus the `(rf/has-tag? id tag)` sugar covers the predicate query. Composes with hierarchical compound states (union along the active path) and — per Stage 2 (rf2-l67o) — will compose with parallel regions (union across every active region). Per rf2-ee0d (Nine States Stage 1). |
| **Parallel regions** — `:type :parallel` with multiple concurrent regions | Prose: [§Parallel regions](#parallel-regions); Schema: `:rf/transition-table` extended for `:type` + `:regions`, `:rf/state-node` extended for the parallel-region body, `:rf/machine-snapshot`'s `:state` widened to the third arm (see [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) and [§`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot)); Fixtures: `parallel-flat-two-regions`, `parallel-compound-region`, `parallel-tags-union-across-regions`, `parallel-broadcast-event-both-regions`, `parallel-invoke-scoped-to-region`, `parallel-after-scoped-to-region`, `parallel-always-cascade-per-region`, `parallel-initial-state-per-region`, `parallel-snapshot-round-trip`, `parallel-ssr-hydration` | ✓ claimed (specified) | The third `:state` arm — a map of region-name → keyword-or-vector-path. Shared `:data` across regions per rf2-l67o §9.4 (per-region encapsulation is a signal to use the N-machine substitute pattern from [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features)). Composes with `:fsm/tags` (union across every active state in every region) and with `:fsm/eventless-always` / `:fsm/delayed-after` / `:actor/invoke` (per-region scoping; one region's `:after` timer doesn't fire transitions in sibling regions). Per rf2-l67o (Nine States Stage 2). |
| **History states** — `:type :history` re-entering a compound's last-active substate | Out of pattern scope; substitute documented in [§Substitutes for skipped features](#substitutes-for-skipped-features) | ✗ not claimed | Substitute: snapshot-as-value capture using the existing `[:rf/machines <id>]` snapshot. |
| **Final states** — `:final?` on a leaf state terminates the machine; an `:invoke`d child's `:final?` fires the parent's `:on-done` with the child's `:output-key`-designated `:data` slot, then auto-destroys the child | Prose: [§Final states (`:final?` / `:on-done` / `:output-key`)](#final-states-final--on-done--output-key); Schema: `:rf/state-node` extended for `:final?` + `:output-key`; `:rf/invoke-spec` extended for `:on-done`; Fixtures: `final-state-singleton-auto-destroys`, `final-state-child-fires-on-done` | ✓ claimed (specified) | First-class `:final?` flag (loud, not `:meta`-buried). Auto-destroy is synchronous on entry to the final state. Singleton symmetry: a standalone machine reaching `:final?` also auto-destroys ("final means final"). Per rf2-gn80. |

### Actor-model axis

| Capability | Coverage required | v1 CLJS reference | Notes |
|---|---|---|---|
| **Own state + message ports** — actor identity is the registered event id; the state lives at `[:rf/machines <id>]` | Prose: §Where snapshots live, §Strict encapsulation; Schema: `:rf/machine-snapshot`, `:rf/machines`; Fixtures: machine-transition, machine-actor-isolation | ✓ claimed | Already specced. |
| **Imperative spawn / destroy** — `[:rf.machine/spawn ...]` and `[:rf.machine/destroy ...]` fx (the canonical actor-lifecycle fx-ids; emitted by `:invoke` desugar and authored by hand inside a machine action's `:fx` or any user event handler's `:fx`) | Prose: §Spawning; Schema: `:rf.fx/spawn-args`; Fixtures: spawn-from-action, destroy-clears-snapshot, spawn-on-spawn-callback | ✓ claimed | Already specced. |
| **Cross-actor send via `:fx`** — `[:dispatch [other-actor-id [:event]]]` | Prose: §Spawning §What spawning gives for free; Fixtures: cross-actor-send | ✓ claimed | Falls out of standard `:dispatch` fx; no new mechanism. |
| **Declarative `:invoke`** (sugar over spawn) — a state's `:invoke` translates to entry/exit actions that spawn / destroy a child actor | Prose: [§Declarative `:invoke` (sugar over spawn)](#declarative-invoke-sugar-over-spawn); Schema: `:rf/state-node` extended for `:invoke` (per [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `invoke-spawn-on-entry-destroy-on-exit`, `spawn-tracked-without-data-pending` (rf2-t07u runtime registry coverage) | ✓ claimed (specified) | No new mechanics; pure sugar. `create-machine-handler` translates `:invoke` to entry/exit `:rf.machine/spawn` / `:rf.machine/destroy` at registration time. Composes with user-supplied `:entry` / `:exit` (user runs first). Per rf2-t07u (Option A revised): the runtime tracks spawned ids at `[:rf/spawned <parent-id> <invoke-id>]` so `:on-spawn` is purely advisory user-side bookkeeping — the destroy cascade no longer reads the user's `:data`. |
| **Spawn-and-join via `:invoke-all`** — first-class parallel-region state-machines: a state node declares N child actors and a join condition (`:all` / `:any` / `{:n N}` / `{:fn ...}`), the runtime fires one of three parent events when the join resolves and (by default) cancels surviving siblings | Prose: [§Spawn-and-join via `:invoke-all`](#spawn-and-join-via-invoke-all); Schema: `:rf/state-node` extended for `:invoke-all` (per [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table)); Fixtures: `invoke-all-join-all-completes`, `invoke-all-join-any-fails-cancels`, `invoke-all-n-of-cancels-extras` | ✓ claimed (specified) | Sugar over N parallel `:invoke`s plus a runtime-owned join-state at `[:rf/spawned <parent> <invoke-id> :join]`. Cancel-on-decision is the default (matches Dash8/rf8 boot-page-reload semantics). Per rf2-6vmw. |
| **`:system-id` named-machine addressing** — a `:rf.machine/spawn` whose args carry `:system-id` binds the actor in the per-frame `[:rf/system-ids]` reverse index; `(rf/machine-by-system-id sid)` resolves the binding | Prose: [§Named addressing via `:system-id`](#named-addressing-via-system-id), [§Cross-machine messaging by name](#cross-machine-messaging-by-name); Schema: `:rf.fx/spawn-args` extended for `:system-id`; Fixtures: `spawn-with-system-id-then-lookup-resolves`, `spawn-without-system-id-leaves-index-empty`, `destroy-machine-clears-system-id-index`, `system-id-collision-warns-and-rebinds` | ✓ claimed (specified) | Opt-in. The reverse index lives in `app-db` so it inherits frame revertibility. Collisions emit `:rf.error/system-id-collision` and rebind (last-write-wins). Per rf2-suue / rf2-ecv4. |
| ~~**Wall-clock `:timeout-ms` on `:invoke` / `:invoke-all`**~~ | DROPPED in favour of state-level `:after`. See [§Wall-clock timeouts on `:invoke` — use parent state's `:after`](#wall-clock-timeouts-on-invoke--use-parent-states-after) and [MIGRATION §M-44](MIGRATION.md#m-44-timeout-ms-removed-from-invoke--invoke-all--use-parent-states-after). | n/a | The `:after` capability subsumes this; one canonical primitive, not two. The `:fsm/delayed-after` capability above covers wall-clock-on-state semantics for both pure timed-transition states and `:invoke`-bearing states. Per rf2-3y3y. |
| **SCXML compatibility** — full bidirectional schema parity with SCXML/Stately | Out of v1 scope (possibly never) | ✗ not claimed | Visualisation-compatibility (paste-and-render) is a smaller post-v1 ambition; see [§Stately.ai compatibility — exact or approximate?](#statelyai-compatibility--exact-or-approximate). |

### How conformance is graded

A re-frame2 port declares its capability list in its conformance harness manifest:

```clojure
{:port-id    :re-frame-cljs
 :capabilities #{:fsm/flat
                 :fsm/hierarchical
                 :fsm/eventless-always
                 :fsm/delayed-after
                 :fsm/tags
                 :fsm/parallel-regions
                 :fsm/final-states                    ;; rf2-gn80 — :final? + :on-done + :output-key
                 :actor/own-state
                 :actor/spawn-destroy
                 :actor/cross-actor-fx
                 :actor/invoke
                 :actor/spawn-and-join
                 :actor/system-id}}    ;; :actor/timeout retired per rf2-3y3y — :fsm/delayed-after subsumes it
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

Per rf2-l67o (Nine States Stage 2), **parallel regions** are now a first-class capability — see [§Parallel regions](#parallel-regions). The N-machines-per-region substitute documented in [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) remains valid and is the right answer when the regions are conceptually independent features (multiple tabs with their own state, boot phases plus diagnostics, an audio/video player whose two regions share nothing but the play/pause event). Parallel regions are the right answer when the regions are orthogonal axes of *one* feature that share a single `:data` blob (one form with three orthogonal axes, one widget with display + interaction, one page's render-mode predicates).

**History states** remain post-v1. The substitute — snapshot-as-value capture exploiting [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility) — is documented in [CP-5-MachineGuide §History states → snapshot-as-value capture](CP-5-MachineGuide.md#history-states--snapshot-as-value-capture). The runtime emits `:rf.error/machine-grammar-not-in-v1` against `:history`; the substitute pattern is the documented forward path.

## Open questions

### Stately.ai compatibility — exact or approximate?

Aim for *paste-and-render* compatibility (a re-frame machine definition pastes into stately.ai and renders correctly), accepting some superficial vocabulary differences (e.g. our action ids vs stately's `actions: {...}` map). Or aim for *full bidirectional* compatibility (exact JSON shape parity)?

Recommendation: paste-and-render is the realistic target; full bidirectional is overinvestment unless someone wants to write a stately-driven authoring tool.

### Globally-registered guards/actions vs machine-scoped (RESOLVED)

Resolved: machine-scoped. Guards and actions live in the machine's `:guards` / `:actions` maps inside the `create-machine-handler` spec; transition-table keyword references resolve **machine-locally** at registration time. There is no `reg-machine-guard` / `reg-machine-action` API and no `:machine-guard` / `:machine-action` registry kind. Cross-machine reuse is via Clojure vars (define a var; reference it from each machine's `:guards` / `:actions` map) — no framework support needed beyond ordinary var resolution. See [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler) and [§Inspectability bias](#inspectability-bias).

### Auto-cleanup of orphaned actors

When a view spawns an actor and unmounts, what stops the leak? Lean: explicit `[:rf.machine/destroy actor-id]` fx for v1 (matches `make-frame`); opt-in `:owned-by` for post-v1.

## Resolved decisions

### Library packaging — in-tree or separate? (RESOLVED)

Resolved: **separate artefact**, `day8/re-frame-2-machines`. Per rf2-xbtj (executing rf2-5vjj Strategy B), the machine substrate ships as a per-feature artefact split out of core — `implementation/machines/src/re_frame/machines*` with its own `deps.edn` and shadow-cljs build target, and core-side cross-references late-bound via the hook registry so apps not using machines pay zero bundle cost. The earlier "prototype as separate, promote to in-tree once API stabilises" recommendation is superseded: the per-feature artefact pattern (machines, schemas, routing, flows, http, ssr, epoch) is now the standard packaging shape — see [Conventions.md §Packaging conventions](Conventions.md#packaging-conventions) for the catalogued split.

### Eventless `:always` transitions — microstep loop inside drain (RESOLVED)

Resolved: `:always` is a state-node key holding a vector of guarded transitions; the drain cascade extends Level 3 with a microstep loop (drain `:raise` → check `:always` → loop) that settles to a fixed point before commit. Default depth limit 16, error category `:rf.error/machine-always-depth-exceeded`. Same-state same-guard self-loops rejected at registration with `:rf.error/machine-always-self-loop`. Trace events emitted at both per-microstep and outer-macrostep granularity. See [§Eventless `:always` transitions](#eventless-always-transitions).

### Sub-event call-site shape (RESOLVED)

Resolved: the dispatch shape for events targeting a machine is the sub-event form `[:machine-id [:inner-event-keyword & payload]]` — the machine handler resolves the second-position inner keyword as the FSM event. The flat form (`[:machine-id/inner-event payload]` with one `reg-event-fx` registration per event) is **not** how machines are addressed. Why: fewer registry entries (one per machine, not one per event); call-site labels show "this is going to the editor machine"; works uniformly for spawned actors whose ids are gensym'd. See the worked examples in [§Registration — the machine IS the event handler](#registration--the-machine-is-the-event-handler) and the Circle Drawer.

### Multiple machine instances at one path

Snapshots live at the runtime-managed path `[:rf/machines <id>]`, keyed by the registered id. Two registrations sharing an id collide at the registry layer (last-write-wins per the standard registration semantics, with a re-registration trace event); a single id never has two snapshot locations. The earlier "two machines at one `:path`" scenario cannot arise because users no longer pick a path. Per-frame isolation falls out of each frame having its own `app-db` and thus its own `:rf/machines` map. See [§Where snapshots live](#where-snapshots-live).

### Spawn id format — `<id-prefix>#<n>` keyword (RESOLVED)

Resolved: a declarative-`:invoke` spawn allocates a keyword id of the form `<id-prefix>#<n>`, preserving any namespace on the prefix — e.g. an `:id-prefix :request/protocol` produces `:request/protocol#1`, `:request/protocol#2`, … The `#` separator is the instance-id marker and is unambiguous (Clojure keyword readers tolerate `#` in the name part, and no user-facing keyword convention uses it). `<n>` is a per-`<id-prefix>` monotonic integer starting at 1; the counter lives in the snapshot at `[:rf/spawn-counter <id-prefix>]` so allocation is deterministic from `(definition, snapshot, event)` (per rf2-gr8q — `machine-transition` is a pure function). `:id-prefix` defaults to the parent's `:machine-id`; an explicit `:invoke-id` bypasses allocation entirely (the actor is bound under that literal). The slash-with-numeric-tail alternative (`:request.protocol/42`) is rejected — it collides with the namespace/name convention every other re-frame2 keyword follows, and a trailing numeric segment is not idiomatic Clojure. The format is shared by the imperative `[:rf.machine/spawn ...]` fx-id allocator (whose counter lives at `[:rf/spawn-counter <machine-id>]` in the spawning frame's `app-db`) and the declarative-`:invoke` allocator (whose counter lives in-snapshot); both produce identically-shaped ids. See [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot) for the `:rf/spawn-counter` slot schema and [§Declarative `:invoke` (sugar over spawn)](#declarative-invoke-sugar-over-spawn) for the allocation call sites.

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
- **Data-only transition tables.** `:states` / `:on` / `:always` / `:after` / `:invoke` / `:invoke-all` are all readable as data; no instrumentation, reflection, or special build steps required.
- **Machine-scoped guards as functions.** The harness can call `:guards` directly with synthesised snapshots to find inputs that make each guard `true` and `false` — generating test data, not just paths.
- **Machine-scoped actions as functions.** Same property; the harness can compose action effects without runtime side effects.
- **Conformance corpus shape.** Generated test cases land as EDN fixtures in the existing corpus format; same fixture exercises both the user's machine logic and any conformant implementation's machine substrate.

The harness's locked design (per the model-based-testing follow-up):

- **Default coverage model:** transition coverage (every transition fires at least once). State coverage and guard coverage are opt-in; path coverage (n-step combinations) for advanced cases.
- **`:after` timers** are included with explicit time-advance steps using the test-clock pattern from [`re-frame.interop`](002-Frames.md#interop-layer--clock-primitives--see-spec-005).
- **Action / spawn stubbing** is auto-installed by the harness (registered fxs become no-ops in test mode); recursive coverage of spawned children is opt-in.
- **Output:** EDN fixtures in the corpus shape so generated tests are trace-comparable across implementations.

The harness is post-v1 because v1's substrate is sufficient — the harness builds on top without runtime changes. Two consumers will benefit:

1. **AI-implementability story** — when an AI implements re-frame in a new language (per [Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) and [Implementor-Checklist](Implementor-Checklist.md)), the harness produces a coverage corpus the implementation must pass.
2. **AI scaffolding of new machines** — an AI scaffolding a new application's machine generates its test corpus before writing any test by hand; reduces missed-edge-case bugs.

See [008-Testing.md §Future](008-Testing.md) for the testing-side forward-pointer.

### Declarative state-scoped child machines

The post-v1 `re-frame.machines` library may surface a `:child-machine` slot on a state node that desugars to entry/exit actions which spawn / destroy a child via the standard `:rf.machine/spawn` / `:rf.machine/destroy` mechanism. No new substrate; pure sugar over the v1 surface.

## Disposition

Post-v1 per [000 §Scope and roadmap](000-Vision.md#scope-and-roadmap). The split is on **what's a foundation** vs **what's scaffolding on top of the foundation**.

The v1 ship-list and the post-v1 follow-up are itemised below.

### v1 ships the machine-as-event-handler foundation

- `(create-machine-handler spec)` — pure factory returning an `reg-event-fx`-compatible handler fn that reads/writes the snapshot at `[:rf/machines <id>]`, calls `machine-transition`, lowers `:data` / `:fx` / `:raise` / `:rf.machine/spawn` into a standard effect map. Registers nothing, closes over no global state, does not know its own id. Spec keys: `:initial`, `:data`, `:guards`, `:actions`, `:states`, `:on`, `:meta` — no `:path` (the location is runtime-managed; see [§Where snapshots live](#where-snapshots-live)). The `:guards` and `:actions` maps declare the machine's named guard / action implementations; transition-table keyword references resolve **machine-locally**, validated at registration time.
- `(machine-transition definition snapshot event)` → `[next-snapshot effects]` — pure function. JVM-runnable. No re-frame dependencies; guard/action references resolve against the definition's own `:guards` / `:actions` maps.
- The `[:rf.machine/spawn ...]` and `[:rf.machine/destroy ...]` fx for dynamic actor lifecycle (canonical surface; the v1 public fns `spawn-machine` / `destroy-machine` are dropped per [MIGRATION.md §M-26](MIGRATION.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces)).
- The `:raise` reserved fx-id inside `:fx` (machine-internal); the `:rf.machine/spawn` and `:rf.machine/destroy` fx-ids registered globally for actor lifecycle.
- `[:rf/machines <id>]` as the reserved app-db storage scheme; `:rf/machine?` registration-metadata flag.
- `(rf/machines)` and `(rf/machine-meta id)` — discovery lens over the event registry per [§Querying machines](#querying-machines).
- The framework-registered `:rf/machine` parametric sub and its `sub-machine` wrapper.
- Four-level drain semantics per [§Drain semantics](#drain-semantics) — including the gotchas listed in [§Drain semantics gotchas](#drain-semantics-gotchas).
- The v1 transition-table grammar subset per [§Capability matrix](#capability-matrix) and [§Transition table grammar](#transition-table-grammar).
- The snapshot shape (`{:state :data :meta?}`) and the persist/restore stability invariants per [§Snapshot shape](#snapshot-shape).
- Inspection trace events (`:rf.machine.lifecycle/created`, `:rf.machine/event-received`, `:rf.machine/transition`, `:rf.machine/snapshot-updated`, `:rf.machine/spawned`, `:rf.machine/destroyed`, etc. — see [009 §Trace events](009-Instrumentation.md) for the canonical emit-site list).
- The `:rf.error/machine-grammar-not-in-v1`, `:rf.error/machine-action-exception`, `:rf.error/machine-action-wrote-db`, `:rf.error/machine-raise-depth-exceeded`, `:rf.error/machine-always-depth-exceeded`, `:rf.error/machine-always-self-loop`, `:rf.error/machine-unresolved-guard`, `:rf.error/machine-unresolved-action`, `:rf.error/machine-invoke-all-bad-shape`, `:rf.error/machine-invoke-all-duplicate-id`, and `:rf.error/machine-invoke-all-with-invoke` error categories. (The pre-rf2-3y3y `:rf.error/machine-invoke-timeout-*` categories are retired alongside `:timeout-ms` itself; per [MIGRATION §M-44](MIGRATION.md#m-44-timeout-ms-removed-from-invoke--invoke-all--use-parent-states-after).)
- The `:rf.warning/no-clock-configured` warning category (advisory; emitted when `:after` is exercised on a host whose `re-frame.interop` clock layer hasn't been wired).
- The eventless `:always` capability per [§Eventless `:always` transitions](#eventless-always-transitions): state-node `:always` slot, microstep loop within Level 3 drain, default depth-16 limit, self-loop guard at registration time, dual-granularity trace events.
- The delayed `:after` capability per [§Delayed `:after` transitions](#delayed-after-transitions): state-node `:after` slot accepting `{<delay> → <transition-spec>}` where `<delay>` is `pos-int?`, a subscription vector (`[:sub-id & args]` resolved through `subscribe`'s machinery; re-resolves on subscription change per [§Dynamic delay re-resolution](#dynamic-delay-re-resolution)), or `(fn [snapshot] ms)`. Epoch-based stale detection (no `:cancel-dispatch-later` fx), SSR no-op rule, clock primitives in `re-frame.interop` (`now-ms`, `schedule-after!`, `cancel-scheduled!`), and the `:rf.machine.timer/scheduled` / `:rf.machine.timer/fired` / `:rf.machine.timer/stale-after` / `:rf.machine.timer/cancelled-on-resolution` / `:rf.machine.timer/skipped-on-server` trace events. The whichever-fires-first cancellation cascade (per [§Whichever fires first wins](#whichever-fires-first-wins)) composes with the in-flight `:rf.http/managed` abort contract per [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts) (rf2-wvkn). Per rf2-3y3y.
- The state-tags capability per [§State tags](#state-tags): state-node `:tags <set-of-keywords>` slot; runtime maintains the active-configuration tag union at `[:rf/machines <id> :tags]` recomputed on every transition (including `:always` microsteps); framework sub `:rf/machine-has-tag?` plus the `(rf/has-tag? id tag)` sugar; empty-union elision per snapshot-size optimisation; reserved framework namespace (`:rf/*` / `:rf.*/*`). Per rf2-ee0d (Nine States Stage 1).
- The spawn-and-join `:invoke-all` capability per [§Spawn-and-join via `:invoke-all`](#spawn-and-join-via-invoke-all): state-node `:invoke-all` slot accepting a vector of child invoke-specs plus `:join` / `:on-child-done` / `:on-child-error` / `:on-all-complete` / `:on-some-complete` / `:on-any-failed` / `:cancel-on-decision?` keys, runtime join state at `[:rf/spawned <parent> <invoke-id> :join]`, cancel-on-decision = `true` by default, and the `:rf.machine.invoke-all/started` / `:rf.machine.invoke-all/all-completed` / `:rf.machine.invoke-all/some-completed` / `:rf.machine.invoke-all/any-failed` / `:rf.machine.invoke/cancelled-on-join-resolution` trace events. New error categories `:rf.error/machine-invoke-all-bad-shape`, `:rf.error/machine-invoke-all-duplicate-id`, `:rf.error/machine-invoke-all-with-invoke`.
- ~~The wall-clock `:timeout-ms` capability~~ — DROPPED per rf2-3y3y. State-level `:after` is the canonical wall-clock-timeout primitive on `:invoke` / `:invoke-all`-bearing states. See [§Wall-clock timeouts on `:invoke` — use parent state's `:after`](#wall-clock-timeouts-on-invoke--use-parent-states-after) and [MIGRATION §M-44](MIGRATION.md#m-44-timeout-ms-removed-from-invoke--invoke-all--use-parent-states-after).
- The cancellation cascade for in-flight `:rf.http/managed` requests per [§Cancellation cascade — in-flight `:rf.http/managed` aborts](#cancellation-cascade--in-flight-rfhttpmanaged-aborts): the `:rf.machine/destroy` path aborts every in-flight `:rf.http/managed` request the destroyed actor had issued, via the `:http/abort-on-actor-destroy` late-bind hook. Triggers include parent state exit, parent's `:after` firing, `:invoke-all` cancel-on-decision, frame destroy, and imperative `[:rf.machine/destroy <actor-id>]`. Each abort emits `:rf.http/aborted-on-actor-destroy` per [Spec 009 §Trace events](009-Instrumentation.md). Direct dispatches from event handlers (no spawned-actor envelope) are NOT subject to the cascade — apps that want HTTP-tied-to-state-occupancy lifetimes spawn child machines. Per rf2-wvkn.

### Post-v1 — the `re-frame.machines` library

Richer scaffolding on top of the v1 foundation. None of the items below add a new substrate — each desugars into the v1 surface:

- **Advanced grammar:** history states. (Hierarchical state nodes, `:always`, `:after`, `:invoke`, parallel state nodes, and **final states with `:on-done`** are v1; see the v1 ship list above. Final states landed in v1 per rf2-gn80.)
- **Sugar in transition tables:** `:child-machine` declarative state-scoped child binding (desugars to entry/exit `:rf.machine/spawn` / `:rf.machine/destroy`).
- **Stately.ai compatibility:** `(machine->xstate-json definition)` converter, paste-and-render parity, Stately-Inspector wire-format mapping.
- **Visualisation tooling:** `machine->mermaid`, `machine->d2`, `machine->xstate-json` exporters.
- **Model-based testing harness:** `@xstate/test`-style graph traversal over the transition table.
- **Declarative `:history` grammar** (history pseudo-states; substitute is snapshot-as-value capture per [§Substitutes for skipped features](#substitutes-for-skipped-features)).
- **Recurring timers, wall-clock delays, pause/resume on `:after`** — explicitly out of scope for v1; see [§What `:after` does *not* include](#what-after-does-not-include).

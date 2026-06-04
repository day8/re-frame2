# reg-machine — declaring a state machine

## When to load

Reach for this leaf when authoring a `rf/reg-machine` call: the declaration map's keys, the `:guards` / `:actions` lookup tables, how a machine is dispatched into. For parallel regions, tags, `:spawn`, history states, or cancellation, see the sibling leaves (`regions.md`, `tags.md`, `spawn.md`, `history.md`, `cancellation.md`).

## Mental model — think in xstate, then map onto re-frame2

**Standing advice for every machine you author: think about how you'd do it in xstate, then map those ideas onto re-frame2.** xstate is the widely-known JS FSM mental model and it's well-represented in your training data — so the fastest way to model a feature's states is to sketch it the xstate way (states, transitions, guards, actions, `context`, `invoke`, parallel states, final states), then translate each piece into its re-frame2 equivalent.

Most concepts map cleanly. A handful of slots re-frame2 **deliberately renames or omits** — those divergences are intentional (the spec documents each one and why), and they're exactly where xstate-trained intuition will steer you wrong. Treat the table below as the translation key, and watch the flagged divergence rows.

| xstate concept | re-frame2 equivalent | Notes / deliberate divergence |
|---|---|---|
| **states** (`state.value`) | `:states` map + the snapshot's `:state` slot | Flat → single keyword; compound → vector path; parallel → region-name→keyword map. Convergence. |
| **transitions** (`on: { EVENT: ... }`) | `:on {event-keyword transition-spec}` on a state node | Convergence. Bare-target / explicit-map / guarded-vector forms. |
| **guards** (`guard` / named guards) | `:guard` on a transition + the top-level `:guards` map | Convergence on the *name*. **Divergence:** no `{and: [...]}` compound-guard data form — compose with one fn or one named registered compound. Guards receive one context map `{:keys [data event state meta]}` and destructure what they need. |
| **actions** (`actions` / named actions) | `:action` / `:entry` / `:exit` + the top-level `:actions` map | Convergence on the *name*. **Divergence:** no action-vector `[a1 a2 a3]` per slot — one fn or one named registered compound. `:entry`/`:exit` are a single fn or single keyword, never vectors. |
| **`assign({...})`** | action returns `{:data new-data}` (and/or `{:fx [...]}`) | **Divergence (name/shape):** no `[:assign {...}]` form. Symmetric with `reg-event-fx`'s `{:db :fx}`. The invariant matches xstate's `assign` though: callbacks may only update `:data` — they cannot nudge the machine into an undeclared state. |
| **`context`** (extended state) | `:data` (the machine's private map, distinct from `app-db`) | **Divergence (name):** re-frame2 calls the slot `:data`, tracking FSM / `gen_statem` "state data" vocabulary and avoiding re-frame's already-overloaded "context" (interceptor pipeline + React context). |
| **`invoke`** (state-bound child actor) | `:spawn` (and `:spawn-all` for fan-out-and-join) | **Divergence (name):** the most semantically-loaded slot is renamed on purpose, to break the "almost-correct xstate code" trap and align with the imperative `:rf.machine/spawn` fx. No `:onSnapshot`/`autoForward`/multiple-`:invoke`-per-state. See `spawn.md`. |
| **`invoke onError`** (child→parent failure **transition**) | `:spawn`'s `:on-error` transition + `:error? true` final leaf | Convergence — re-frame2 ships `invoke onError` first-class as a control-flow **transition** (not observability-only). The child designates an error terminal with `:final? true :error? true`; the parent's `:spawn` declares `:on-error` (an `:on`-shaped transition spec). See `spawn.md` §`:on-error`. |
| **`onDone`** (child→parent completion) | `:final?` leaf + parent `:spawn`'s `:on-done` + `:output-key` | Convergence — re-frame2 ships first-class final-state-with-parent-notification. See `spawn.md`. |
| **parallel states** (`type: 'parallel'`) | `:type :parallel` + `:regions {...}` | Convergence (name + concept). `:data` shared; `:tags` is the union across active regions. See `regions.md`. |
| **history states** (`type: 'history'`, `history: 'deep'`) | `:type :history` pseudo-state under a compound's `:states` (`:deep?` / `:default-target`) | Convergence (name + concept). Shallow by default; `:deep? true` for deep. Recording rides the snapshot's `:rf/history` slot — so undo / time-travel / SSR get it free. See `history.md`. |
| **final states** (`type: 'final'`) | `:final? true` on a leaf state | Convergence on the concept. **Note the divergence:** a `:final?` singleton (or every-region-final parallel machine) **auto-destroys** — "final means final." Omit `:final?` for a persistent terminal state. See `spawn.md`. |
| **tags** (`tags: [...]`) | `:tags #{...}` on a state node + `machine-has-tag?` | Convergence. See `tags.md`. |
| **eventless / always transitions** | `:always [{:guard ... :target ...} ...]` | Convergence (re-frame2's term for xstate/SCXML transient transitions). |
| **delayed transitions** (`after`) | `:after {<ms> transition-spec}` | Convergence on the name. No recurring timers / pause-resume in v1. |
| **`raise` (self-event)** | `:raise` inside an action's `:fx` | **Divergence:** sugar for atomic self-dispatch — there is no per-actor mailbox to insert in front of. |
| **`sendTo` / `sender` (reply to a request)** | include the reply event in the request vector | **Divergence:** no new API; the event vector carries its own reply target. |
| **`ActorRef` runtime objects** | snapshots at `[:rf/runtime :machines :snapshots <id>]` in `app-db` | **Divergence (architecture):** data-oriented, agent-friendly, no live-object leak footguns. Read via `sub-machine`. |
| **`setup({actors, guards, actions})`** | per-machine `:guards` / `:actions` maps in the spec | **Divergence:** machine-scoped (not globally registered) — each machine has its own guard/action namespace, validated at registration; cross-machine reuse is via plain Clojure vars. |
| **three creation modes** (`createActor` / `invoke` / `spawn`) | one mechanism, two patterns: singleton via `reg-event-fx`, dynamic via `:spawn` / `[:rf.machine/spawn ...]` | **Divergence:** lifetime is encoded by `app-db` shape + registration lifetime, not by which constructor you call. |

The deliberate-divergence rows are catalogued in Spec 005 §Lessons from xstate and §Deliberate omissions vs xstate (and the full table in CP-5-MachineGuide §Lessons from xstate). When you reach for an xstate slot that isn't in the table — an action vector, `{and: [...]}`, multiple `:invoke` per state — that's a signal to stop and check the divergence rows rather than assume parity. (`invoke onError` *is* in the table — it maps to `:spawn`'s `:on-error` transition; don't hand-roll a dispatch-back where the declarative transition fits.)

## Canonical signature

```
(rf/reg-machine machine-id machine-map)
```

`reg-machine` is a macro (in `re-frame.core`) that stamps source coords at the call site and registers the machine as a `:event` handler whose registration metadata carries `:rf/machine? true`. The underlying registration fn `reg-machine*` lives in `re-frame.machines.lifecycle-fx.registration` (re-exported via the `re-frame.machines` facade and `re-frame.core-machines`). The machine **is** an event handler — dispatch `[machine-id [:event-name & args]]` to drive it.

The `day8/re-frame2-machines` artefact must be on the classpath and `re-frame.machines` required at app boot; without it, calls throw `:rf.error/machines-artefact-missing` (the late-bind guard in `re-frame.core-machines`).

## Declaration shape

The basic (non-parallel, non-hierarchical) form:

```clojure
(require '[re-frame.core :as rf]
         '[re-frame.machines])     ;; load-time hook registration

(def my-machine
  {:initial :idle
   :data    {:attempt 0 :error nil}

   :guards
   {:has-input?
    (fn guard-has-input? [{:keys [data]}]
      (some? (:input data)))}

   :actions
   {:bump-attempt
    (fn action-bump [{:keys [data]}]
      {:data (update data :attempt (fnil inc 0))})

    :store-result
    (fn action-store [{data :data [_ {:keys [value]}] :event}]
      {:data (assoc data :result value :error nil)})}

   :states
   {:idle
    {:on {:start {:target :working
                  :guard  :has-input?
                  :action :bump-attempt}}}

    :working
    {:on {:succeeded {:target :done    :action :store-result}
          :failed    {:target :idle}}}

    :done {}}})

(rf/reg-machine :my/feature my-machine)

;; Drive it:
(rf/dispatch [:my/feature [:start]])
```

The machine map's top-level keys are documented in Spec 005 §Transition table top-level keys: `:initial` (the entry state for non-parallel machines), `:data` (initial shared data), `:guards` and `:actions` (named lookup tables), `:states` (the transition table). For parallel machines, `:type :parallel` + `:regions` replaces `:initial` + `:states` — see `regions.md`.

## State-node shape

Every state node is a map. Recognised slots (see the `re-frame.machines` façade docstring + `re-frame.machines.transition`, and Spec 005 §State nodes):

- `:on` — a map of `event-keyword → transition-spec` (see Transitions below).
- `:entry` / `:exit` — singular action references or fns, fired on entering / leaving the node.
- `:always` — eventless microstep table (`:always [{:guard ... :target ...} ...]`).
- `:after` — delayed transition table, `:after {<ms-or-sub-vec-or-fn> <transition-spec>}`.
- `:spawn` — declarative child spawn (see `spawn.md`).
- `:spawn-all` — spawn-and-join sugar (see `spawn.md`).
- `:tags` — a set of keywords describing this state's per-axis intent (see `tags.md`).
- `:states` + `:initial` — nested compound state (deepest-wins resolution).
- `:type :history` — a **pseudo-state** sibling under a compound's `:states` (carries `:deep?` / `:default-target`, nothing else); a transition target that restores the compound's last-active configuration. Not an occupiable state. See `history.md`.

## Transition shape

The value under an `:on` event keyword is one of:

```clojure
{:on {:start :working}}                              ;; bare target keyword
{:on {:start {:target :working}}}                    ;; explicit map
{:on {:start {:target :working :action :bump-attempt}}}
{:on {:start {:target :working :guard  :has-input?}}}
{:on {:start [{:guard :a? :target :x}                ;; guarded vector — first match wins
              {:guard :b? :target :y}
              {:target :z}]}}
```

The transition's `:target` may be a single keyword (sibling-level) or a vector path (absolute, for cross-level transitions). Per `normalise-on-clause` in `re-frame.machines.transition`.

## Guards / actions — keyword reference or inline fn

`:guards` and `:actions` at the machine top level are lookup tables. Inside an `:on` transition, `:guard` and `:action` accept **either** a keyword that resolves through those tables, **or** an inline fn:

```clojure
;; Inline — preferred only for one-line trivialities.
{:on {:start {:guard  (fn [{:keys [data]}] (some? (:input data)))
              :target :working}}}

;; Keyword reference — preferred for anything non-trivial, because the
;; registered id appears in trace events and tools can jump-to-source.
{:on {:start {:guard :has-input? :target :working}}}
```

Per the inspectability bias (Spec 005 §Inspectability bias): named entries surface in `:rf.machine/*` trace events as the registered keyword, not as an opaque `#object[Function ...]`. Reach for the inline form only when the body is trivial.

### Guard / action contract

Every callback receives **one context map** — `(fn [{:keys [data event state meta]}] ...)` — and destructures the keys it needs. `data` is the snapshot's `:data` slot (a plain map); `event` is the inbound event vector; `state` is the discrete FSM keyword; `meta` is any user `:meta` on the snapshot. Actions return `{:data new-data :fx [...]}` (either key optional); guards return truthy/falsey. See `call-guard` and `call-action` in `re-frame.machines.transition`.

There is **no positional `(data event)` arity and no opt-in 3-arity escape hatch** — the runtime always delivers the full context map and the destructure pattern decides what's bound. `:state` and `:meta` are available for introspection with no flag (Spec 005 §Snapshot introspection — `:state` / `:meta`). The uniform single-map shape is deliberate: it eliminates the paste-from-`:guard`-into-`:on-spawn` trap (an `id` silently bound to the event vector, or vice-versa) that slot-specific positional signatures would create.

## Subscribing to a machine

The framework ships two subs:

```clojure
@(rf/sub-machine :my/feature)                  ;; the whole snapshot
@(rf/machine-has-tag? :my/feature :loading)            ;; tag containment-bit
```

`sub-machine` is sugar over `(subscribe [:rf/machine machine-id])` and returns the snapshot map `{:state ... :data ... :tags ...}`. `machine-has-tag?` is sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])` — see `tags.md`. Both live in `re-frame.core-machines` (re-exported from `re-frame.core`).

Project off the snapshot with ordinary `reg-sub`:

```clojure
(rf/reg-sub :feature/data
  :<- [:rf/machine :my/feature]
  (fn sub-data [snap _] (get-in snap [:data :result])))
```

## As an event-fx handler

When a machine drives a discrete event-fx flow (boot, websocket-connection lifecycle), wrap its declaration with `rf/make-machine-handler` and register the result under `rf/reg-event-fx`. The wrapper produces the event-fx handler fn that dispatches into the machine on every invocation:

```clojure
(rf/reg-event-fx :app/boot
  (rf/make-machine-handler
    {:initial :configuring
     :data    {:phase :configuring :config nil}
     :states  ...
     :guards  ...
     :actions ...}))
```

`make-machine-handler` is the "wrap this machine as an event-handler" sugar. See `references/cross-cutting/api-cheatsheet.md` §Machines for the contract row; `patterns/boot.md` and `patterns/websocket.md` carry worked examples.

## Querying registered machines

- `(rf/handler-meta :event :my/feature)` — registration metadata, including `:rf/machine? true`, `:rf/machine` (the spec map), `:ns` / `:line` / `:file`.
- `(re-frame.machines/machines)` — every registered machine-id.
- `(re-frame.machines/machine-meta :my/feature)` — the spec map back.

## Common gotchas

- **The artefact must be loaded.** `(:require [re-frame.machines])` at the namespace declaring `rf/reg-machine` (or at app boot before any machine call). Forgetting it throws `:rf.error/machines-artefact-missing` with `:recovery :no-recovery`.
- **`:rf.machine/*` and `:rf/*` are reserved.** Names like `:rf.machine/spawn` and the reserved app-db root `:rf/runtime` (with the framework parking `:machines`, `:routing`, `:elision`, … under it) belong to the runtime. Pick your own feature prefix for event keywords.
- **Callbacks receive one context map, not the raw snapshot.** `(fn [{:keys [data event]}] ...)` — the body inspects `(:input data)`, not `(get-in snap [:data :input])`. `data` is already the snapshot's `:data` slot. Same shape for guards, actions, `:entry`, `:exit`.
- **Actions return an effect map.** `{:data new-data}` (or `{:fx [...]}` or both). Returning a bare data map silently does nothing; `nil` is a no-op.
- **Use `reg-machine` (macro), not `reg-machine*` (fn).** The macro stamps per-element source coords that tools rely on (`re-frame.core` macro layer, Spec 005 §Source-coord stamping). Reach for `reg-machine*` only for programmatic registration with computed ids.
- **Re-registration replaces.** Last-write-wins, per the standard registrar semantics; the prior snapshot at `[:rf/runtime :machines :snapshots <id>]` survives (the snapshot is in `app-db`, the spec is in the registrar). Hot-reload survives a machine re-declaration.

## Deeper material

For the full transition-table grammar, guard/action effect-map shape, hierarchical state cascading, and machine-snapshot semantics, see `SKILL-REDIRECT.md` → *EP — State machines (005)*.

---

*Derived from the `re-frame.machines.*` sub-namespaces (`transition`, `lifecycle-fx.registration`, …) and `re-frame.core` / `re-frame.core-machines` (the `reg-machine` macro + `sub-machine` / `machine-has-tag?` sugar) @ main `89bd9c3`. Citations are symbol-level (machines.cljc was split into sub-namespaces); re-verify symbol homes after machine-registration refactors.*

# O-16 — translate `async-flow-fx` to re-frame2 **state machines**

The v1 add-on `day8.re-frame/async-flow-fx` coordinates **async sequences** — boot, login, wizard, init orchestration. A flow watches the router for the events its rules await, and dispatches the next step when the awaited event(s) arrive. That is an **FSM pattern**: sequential async coordination with success / failure branches. The re-frame2 successor is therefore **state machines** (`reg-machine`, Spec 005), **not** reactive flows (`reg-flow` derives values — a different concern).

> **Forced, not optional.** `async-flow-fx` 0.4.0 calls the removed `re-frame.core/console` and **fails to compile** the moment re-frame2 is on the classpath — see [`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in). The add-on does **not** keep working. You must convert (this guide) or remove it **before the project compiles**. Choosing *whether to convert vs remove* is the operator's call; doing *something* is not optional.

> **Type B — ask first.** The FSM shape is a re-thinking of the rule-set, not a structural lift. Surface the proposed machine per flow and wait for the operator's approval before editing.

> **Verify before you write.** The `reg-machine` grammar below is summarised from [`spec/005-StateMachines.md` §Transition table grammar](../../../spec/005-StateMachines.md#transition-table-grammar). Re-read that section (plus [§Spawn-and-join via `:spawn-all`](../../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all) and [§Final states](../../../spec/005-StateMachines.md#final-states-final--on-done--output-key)) against the live machines artefact before emitting any machine — the spec is the contract, this page is the on-ramp. `reg-machine` ships in `day8/re-frame2-machines` (per [M-28](breaking-changes.md#required-m-rules-by-trigger-surface)); requiring `re-frame.machines` fires its load-time registrations.

## Detection

- Maven coord `day8.re-frame/async-flow-fx` (any version) in `deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn`.
- `(:require [day8.re-frame.async-flow-fx ...])` in any namespace (the require side-effects the `:async-flow` fx registration; the require alone signals adoption).
- `:async-flow` keys in effect maps returned by `reg-event-fx` handlers — the unmistakable fingerprint. May sit at the top level (pre-M-8 shape) or inside `:fx` (post-M-8 shape `:fx [[:async-flow {...}]]`).

Each `:async-flow` call site is **one flow** = one candidate machine. Present the flow's spec, the proposed machine, and the diff for operator approval before any edit.

## Construct mapping — async-flow rule spec → `reg-machine`

The rule engine and the FSM are structurally different. async-flow is **temporal** (track events through the router; fire any rule whose `:when` predicate has become true). The FSM is **spatial** (the machine occupies one state at a time; named events drive transitions). The mapping below lowers the async-flow constructs onto FSM concepts.

| async-flow construct | `reg-machine` construct | Notes |
|---|---|---|
| `:id` (flow id) | the `machine-id` arg to `reg-machine` | async-flow's `:id` defaulted to a gensym; the machine-id is the addressing primitive (events dispatch as `[<machine-id> <event-vec>]`, the snapshot lives in runtime-db at `[:rf.runtime/machines :snapshots <machine-id>]`). Pick a meaningful feature-prefixed keyword — `:app/boot`, `:wizard/checkout`. |
| `:first-dispatch [:e]` | the `:initial` state + its `:entry` action emitting the kickoff via `:fx` | `{:entry (fn [_] {:fx [[:dispatch [:e]]]})}` on the initial state. The parent eager-starts the machine with the **reserved synthetic creation kick** `(rf/dispatch [<machine-id> [:rf.machine/start]])` — xstate's `createActor(m).start()` — which runs the initial-entry cascade (firing the `:entry`) then stops. Do **NOT** use a plain `[:start]`: `[:rf.machine/start]` is reserved framework lifecycle traffic (per [§Reserved synthetic events](../../../spec/005-StateMachines.md#reserved-synthetic-events)); a bare `[:start]` is just a **user event** that reaches the `:on` map — with no `:on {:start …}` clause it resolves to a benign-but-spurious `:rf.machine.event/unhandled-no-op`, and a catch-all (`:*`) transition would fire on it unintentionally. |
| one rule `{:when (seen? :e) :dispatch [:f]}` | a **state** + a **transition** on its `:on` map gated on the awaited event `:e` | the rule's `:when` event becomes the `:on` key; the rule's `:dispatch` becomes the transition's `:action` `:fx`. "While waiting for `:e`, then go next, dispatching `:f`." |
| `:when (seen-both? :a :b)` / `:seen-all-of?` (ALL must arrive) | a state whose `:on` **records each event in `:data`**, plus an `:always` transition gated by a `:guard` that fires once all are present | the canonical multi-await shape: each contributing `:on` action sets a flag in `:data`; an eventless `:always {:guard :both-seen? :target ...}` advances when the guard reads all flags true (per [§Eventless `:always`](../../../spec/005-StateMachines.md#eventless-always-transitions)). For the boot fan-out where each await is its own child actor, `:spawn-all` with `:join :all` is the declarative alternative (see below). |
| `:when (seen-any-of? :a :b :c)` (ANY arrival triggers) | each event listed in the state's `:on` map with the **same** `:target` | `{:on {:a :failed :b :failed :c :failed}}`. |
| `:dispatch [:f]` / `:dispatch-n [[:f] [:g]]` (fire on rule match) | the transition's `:action` returning `{:fx [[:dispatch [:f]] [:dispatch [:g]]]}` | the dispatch is threaded through the FSM transition surface, so it appears in the trace next to the state change. |
| `:halt-on [:e]` / a rule with `:halt? true` | an **error transition to a `:final?` state** | `:halt-on` events route to a terminal `:failed` state; a normal terminal completion routes to `:ready`. Entering any `:final?` state auto-destroys the machine and clears its snapshot (per [§Final states](../../../spec/005-StateMachines.md#final-states-final--on-done--output-key)). |
| `:db-path` (engine state location) | dropped — snapshots live in runtime-db at `[:rf.runtime/machines :snapshots <id>]` | not user-selectable. The trade is favourable: the snapshot rides in the frame-state container (runtime-db partition), so it is revertible, SSR-survivable, and visible to Xray / Tool-Pair / the trace stream for free. |
| `:debug?` (per-flow console logging) | dropped — machine transitions emit `:rf.machine/transition` trace events | the standard trace surface (Spec 009) carries them; no per-machine flag. |

> **CRITICAL — retarget the producers, or the machine never advances.** async-flow watches the **global** router: a rule awaiting `:config-loaded` fires when *anyone* dispatches `[:config-loaded]`. A machine does **not** — it is an event handler addressed by its id, and **only observes events dispatched to its address** `[<machine-id> <event-vec>]` (per [005 §Spawning — dynamic actors](../../../spec/005-StateMachines.md#spawning--dynamic-actors): a machine "is addressable as an event handler whose id is the actor's address"; a plain `[:config-loaded]` resolves to a *separate* `:config-loaded` handler — or to `:rf.error/no-such-handler` — and never reaches `:app/boot`). So mapping the `:when`/`:seen-*` events onto the machine's `:on` keys is only **half** the conversion. The other half is rewriting every **producer** of those awaited events — the HTTP `:on-success`/`:on-failure`, the existing completion handler, whatever dispatched the plain global event — to dispatch the **addressed** form: `[:app/boot [:config-loaded payload]]`, `[:app/boot [:fetch-failed err]]`. Skip this and the converted boot/login/wizard compiles, starts, and then **hangs silently** on its first await — the spinner never clears, with no error. When the original event must stay public (other code also listens for the plain `[:config-loaded]`), keep the global producer and add a one-line **bridge** handler that re-dispatches into the machine — `(rf/reg-event :config-loaded (fn [_ ev] {:fx [[:dispatch (into [:app/boot] [ev])]]}))` — or model the await as a spawned child actor whose completion the runtime routes back to the parent (see [§Parallel fan-out — `:spawn-all`](#parallel-fan-out--spawn-all-when-each-await-is-its-own-actor) below). The worked example below shows the producer rewrites explicitly.

> **Plan a cross-file wiring pass — the producers are scattered, not co-located.** The worked example below is a single file for legibility, but a real boot/login/wizard orchestration is **the opposite**: the `:async-flow` declaration lives in one `boot`/`init` namespace, while its awaited events are produced **across many namespaces** — `[:config-loaded]` from a config ns, `[:user-loaded]` from an auth ns, `[:feature-flags-ready]` from a flags ns, each with its own HTTP `:on-success`/`:on-failure`. And the fan-out runs the other way too: **one** event (e.g. `[:session-expired]`, `[:fetch-failed]`) is often awaited by **several** flows/machines at once. So the retarget is not a local edit — it is a **wiring pass spanning the whole producer graph**. Before converting, enumerate it: for each awaited event in the flow, grep the codebase for *every* site that dispatches it (`rg "\[:config-loaded"`), and list which file/handler each lives in. Then decide per site — **re-address** it to `[<machine-id> …]` (when the machine is its sole consumer) or **bridge** it (when it must stay public for other listeners). Treat any awaited event you cannot fully trace to its producers as a blocker: an unretargeted producer is the silent stuck-boot. Record the producer-graph map in the plan and the report (per [§Reporting](#reporting)) so the operator can verify nothing was missed.
>
> The **bridge handler** here is the general cross-file coordination tool for a *partitioned* large migration — a one-line `reg-event` in your file that retargets a producer living in another worker's file without editing it. Full treatment, with the one-file-one-owner partition it serves: [`orchestrating-a-large-migration.md` §The bridge-handler idiom](orchestrating-a-large-migration.md#3-the-bridge-handler-idiom--the-general-cross-file-coordination-tool).

### `reg-machine` grammar (the slots this guide uses)

```clojure
(rf/reg-machine <machine-id>
  {:initial <state-keyword>                  ;; required
   :data    {<initial working-memory>}       ;; optional
   :guards  {<kw> (fn [{:keys [data event]}] boolean), ...}  ;; named guards (machine-local)
   :actions {<kw> (fn [{:keys [data event]}] {:data .. :fx ..}), ...}  ;; named actions (machine-local)
   :states
   {<state-keyword>
    {:entry  :kickoff-fetch                  ;; keyword → resolves in :actions (or an inline fn)
     :on     {<event-id> <transition>, ...}  ;; event-driven transitions
     :always [<guarded-transition>, ...]     ;; eventless — fires when a :guard turns true
     :final? true}}})                        ;; entering terminates + auto-destroys the machine
```

The `:guards` and `:actions` maps are **symmetric** — both declare machine-local named implementations keyed by keyword, and both are referenced from the transition table by that keyword (`:guard :both-loaded?`, `:action :record-config`, `:entry :kickoff-fetch`). A `<transition>` is `<target-keyword>` (sugar for `{:target ...}`) or a `{:target :guard :action}` map; `:guard` / `:action` (and `:entry` / `:exit`) are **one keyword reference into the machine's `:guards` / `:actions` map — or one inline fn**. A guard / action receives one context map `{:keys [data event state meta]}`; an action returns a fresh `{:data ...}` (and optional `:fx`). Prefer the **named keyword reference** (see [§Name your actions](#name-your-actions--declare-them-in-the-actions-map-not-inline) below); inline fns are the trivial-one-liner escape hatch. All verified against [`spec/005-StateMachines.md` §Transition table grammar](../../../spec/005-StateMachines.md#transition-table-grammar), [§Guards](../../../spec/005-StateMachines.md#guards), [§Actions](../../../spec/005-StateMachines.md#actions), and [§Inspectability bias](../../../spec/005-StateMachines.md#inspectability-bias).

## Worked before → after — a boot/login sequence

A representative async-flow: dispatch `[:fetch-config]` first; when `[:config-loaded]` arrives, fetch the user; when **both** `[:user-loaded]` and `[:config-loaded]` have arrived, the app is ready; if any fetch fails (`[:fetch-failed]`), halt into an error state.

### Before — `async-flow-fx`

```clojure
(ns my-app.boot
  (:require [re-frame.core :as rf]
            [day8.re-frame.async-flow-fx]))            ;; registers the :async-flow fx

(rf/reg-event-fx :app/boot
  (fn [{:keys [db]} _]
    {:db         (assoc db :boot/phase :starting)
     :async-flow {:id             :app/boot-flow
                  :first-dispatch [:fetch-config]
                  :rules
                  [;; config arrives → fetch the user
                   {:when     :seen?
                    :events   :config-loaded
                    :dispatch [:fetch-user]}

                   ;; BOTH config and user loaded → ready
                   {:when     :seen-all-of?
                    :events   [:user-loaded :config-loaded]
                    :dispatch [:app-ready]
                    :halt?    true}

                   ;; any failure → fail
                   {:when     :seen-any-of?
                    :events   [:fetch-failed]
                    :dispatch [:app-boot-failed]
                    :halt?    true}]}}))

;; The PRODUCERS — the handlers that dispatch the awaited events. The flow
;; watches the global router, so a plain [:config-loaded] satisfies its rule.
(rf/reg-event-fx :fetch-config
  (fn [_ _]
    {:fx [[:http-xhrio {:method     :get :uri "/config"
                        :on-success [:config-loaded]      ;; → global dispatch
                        :on-failure [:fetch-failed]}]]}))

(rf/reg-event-fx :fetch-user
  (fn [_ _]
    {:fx [[:http-xhrio {:method     :get :uri "/user"
                        :on-success [:user-loaded]        ;; → global dispatch
                        :on-failure [:fetch-failed]}]]}))
```

### After — `reg-machine`

```clojure
(ns my-app.boot
  (:require [re-frame.core :as rf]
            [re-frame.machines]))                      ;; per M-28 — fires the machines artefact's load-time hooks

;; Guards AND actions live in named, machine-local maps — symmetric — and the
;; :states table references them by keyword. This is the introspectable/reusable
;; idiom (see §Name your actions below); inline fns are the trivial-case escape.
(rf/reg-machine :app/boot
  {:initial :starting
   :data    {:config-loaded? false :user-loaded? false}

   :guards
   {:both-loaded? (fn [{:keys [data]}]
                    (and (:config-loaded? data) (:user-loaded? data)))}

   :actions
   {:kickoff-config (fn [_] {:fx [[:dispatch [:fetch-config]]]})  ;; :first-dispatch → :entry
    :kickoff-user   (fn [_] {:fx [[:dispatch [:fetch-user]]]})
    :record-config  (fn [{:keys [data]}] {:data (assoc data :config-loaded? true)})
    :record-user    (fn [{:keys [data]}] {:data (assoc data :user-loaded? true)})
    :announce-ready  (fn [_] {:fx [[:dispatch [:app-ready]]]})
    :announce-failed (fn [_] {:fx [[:dispatch [:app-boot-failed]]]})}

   :states
   {;; :first-dispatch → the initial state's :entry kicks off config fetch.
    :starting
    {:entry :kickoff-config
     :on    {:config-loaded {:target :loading-user
                             :action :record-config}
             :fetch-failed  :failed}}                  ;; :halt-on → error transition

    ;; config is in; fetch the user. Both await-events feed the :both-loaded? guard.
    :loading-user
    {:entry :kickoff-user
     :on    {:user-loaded  {:action :record-user}
             :fetch-failed :failed}
     ;; :seen-all-of? → eventless :always gated on the compound guard.
     :always [{:guard :both-loaded? :target :ready}]}

    ;; terminal states — entering either auto-destroys the machine + clears its snapshot.
    :ready  {:final? true
             :entry  :announce-ready}

    :failed {:final? true
             :entry  :announce-failed}}})

;; The PRODUCERS — RETARGETED to the machine address. This is the half of the
;; conversion a blind :on-key mapping forgets: the machine only observes events
;; dispatched to [:app/boot ...], so every awaited completion/failure must be
;; re-addressed. (The :http-xhrio fx itself converts separately to managed HTTP
;; per O-17 — kept as-is here to isolate the addressing change; what changes
;; HERE is only the :on-success / :on-failure TARGET, now an addressed event.)
(rf/reg-event :fetch-config
  (fn [_ _]
    {:fx [[:http-xhrio {:method     :get :uri "/config"
                        :on-success [:app/boot [:config-loaded]]    ;; ← addressed
                        :on-failure [:app/boot [:fetch-failed]]}]]}))

(rf/reg-event :fetch-user
  (fn [_ _]
    {:fx [[:http-xhrio {:method     :get :uri "/user"
                        :on-success [:app/boot [:user-loaded]]      ;; ← addressed
                        :on-failure [:app/boot [:fetch-failed]]}]]}))

;; Start the machine from the app's entry point (replaces the :app/boot event).
;; [:rf.machine/start] is the reserved eager-creation kick (xstate createActor(m).start()):
;; it runs the initial-entry cascade (firing :starting's :entry) then stops.
;; A plain [:start] would be an unhandled user event (no :on {:start ...}) → spurious no-op.
(rf/dispatch [:app/boot [:rf.machine/start]])
```

What changed:

- **Guards AND actions are named in the spec's `:guards` / `:actions` maps; the `:states` table references them by keyword.** This is the symmetric, idiomatic shape (`:entry :kickoff-config`, `:action :record-config`, `:guard :both-loaded?`) — a single machine-local registry a reviewer, a visualiser, and a test can all read by name. Don't model the actions inline in the slots; see [§Name your actions](#name-your-actions--declare-them-in-the-actions-map-not-inline) for when the inline escape hatch is appropriate.
- **`:first-dispatch [:fetch-config]` → the `:starting` state's `:entry` action `:kickoff-config`.** The kickoff is an explicit, named, addressable action running through the standard fx pipeline.
- **The single-event rule (`:config-loaded` → fetch user) → a transition on `:starting`'s `:on` map.** The awaited event is the `:on` key; the dispatch lands in the next state's `:entry` action (`:kickoff-user`), and the await is recorded by the transition's `:action :record-config`.
- **The `:seen-all-of? [:user-loaded :config-loaded]` rule → record-in-`:data` named `:on` actions plus an `:always` gated by the `:both-loaded?` guard.** Each await flips a flag in working memory (`:record-config` / `:record-user`); the eventless `:always` advances the moment both are true — the FSM-native spelling of "all seen." (`:config-loaded` is recorded in `:starting`; `:user-loaded` in `:loading-user`; the guard reads both.)
- **`:halt-on [:fetch-failed]` → a `:fetch-failed` transition to the `:final?` `:failed` state**, declared on every state that can still be in flight. The normal completion routes to the `:final?` `:ready` state. Entering either terminal triggers auto-destroy.
- **Every producer of an awaited event RETARGETED to the machine address.** `:fetch-config`'s `:on-success [:config-loaded]` → `[:app/boot [:config-loaded]]`; `:fetch-user`'s `:on-success [:user-loaded]` → `[:app/boot [:user-loaded]]`; both `:on-failure` → `[:app/boot [:fetch-failed]]`. This is the easily-missed half: the `:on`-key mapping is inert until the events actually arrive **at the machine's address** — a plain global `[:config-loaded]` bypasses the machine and the boot hangs silently. (Where the global event must stay public, bridge it instead — see the CRITICAL note under the construct table.)
- **`(:require [day8.re-frame.async-flow-fx])` dropped; `(:require [re-frame.machines])` added** (M-28). The `day8.re-frame/async-flow-fx` Maven coord is dropped once every flow is converted.

### Name your actions — declare them in the `:actions` map, not inline

The example above puts **every** action in the machine's named `:actions` map and references it by keyword from the slots — `:entry :kickoff-config`, `:action :record-config`. That is the spec's [§Inspectability bias](../../../spec/005-StateMachines.md#inspectability-bias) default, and it is **deliberately the shape this guide models**, because the natural async-flow→machine instinct is to inline each old `:dispatch` as a `(fn [_] {:fx [[:dispatch …]]})` literal right in the slot — which works but skips the named registry. Two named maps, symmetric: `:guards` for the predicates, `:actions` for the effects.

Why a named `:actions` map (and not inline fns) is the default:

- **Reuse.** A boot/login/wizard flow routes to the same terminal effect from several states — e.g. `:fetch-failed → :failed` is declared on *every* in-flight state, and `:failed`'s `:entry :announce-failed` runs once. Naming the action lets every reference point at one definition; inlining the same `(fn [_] {:fx [[:dispatch [:app-boot-failed]]]})` body into N slots duplicates it, and the copies drift.
- **A single named registry.** `(machine-meta :app/boot)` returns the `:actions` map keyed by id — one place that lists what the machine *does*, the way `:guards` lists what it *decides*. A reviewer reads the registry instead of hunting fn bodies scattered through the state tree.
- **Addressable by id — for tests, fixtures, and visualisers.** A Level-1/2 test can redefine the spec's `:actions` entry by key to stub a deterministic stand-in; a conformance fixture can assert against the action id; a diagram exporter labels the arrow with `:record-config` instead of `[fn]`. An inline fn has no public name to address (per [§Inspectability bias](../../../spec/005-StateMachines.md#inspectability-bias) — "Tests read ids", "Conformance fixtures read ids").
- **Clarity at the call site.** `:entry :kickoff-config` says *what* the entry does; `:entry (fn [_] {:fx [[:dispatch [:fetch-config]]]})` makes the reviewer parse the body to find out. The keyword *is* the meaning.

> **Note — inline source is now stamped; the win is reuse + the named registry, not "you can't see the code."** An inline fn's source text *is* co-located on its enclosing state/transition node, so Xray's Epoch and Machine panels and a source-aware visualiser CAN show an inline action's code — it no longer renders as `#object[Function]` or a bare `[fn]` hole (per [005 §Inline-fn / keyword slots](../../../spec/005-StateMachines.md#inline-fn--keyword-slots-the-exemption-case)). So do **not** justify naming on "inline is non-introspectable." The real, accurate benefits are the four above: reuse across slots, one named registry per machine (`machine-meta`), addressability by id (tests / fixtures / diagram labels), and call-site clarity.

When inline IS fine — the escape hatch: a **trivial, single-use, non-branching** body that adds no meaning by being named. `:guard (fn [{:keys [data]}] (some? (:circle-id data)))` used once is fine — `:has-circle?` adds nothing the body doesn't already show. The test (per [§Inspectability bias](../../../spec/005-StateMachines.md#inspectability-bias)): is the body a single non-branching expression used in exactly one slot? Yes → inline is acceptable. No (it branches, it composes multiple steps, or it is referenced from more than one slot) → name it in `:actions` / `:guards`. The default for anything a migrated flow carries — kickoffs, record-await steps, completion announcers — is **named**, because flows reuse and branch by nature.

### The top-level singleton boot machine — the most common async-flow shape

The single most common `async-flow-fx` use is the **app boot / init orchestration** — and its v2 form is a **top-level singleton machine**: exactly one instance per app, known at registration time, started once at app start. The example above is exactly this shape; this section names the three things a boot conversion must get right, because getting the addressing or the kick wrong leaves the machine unstarted and the app hanging on its spinner. The full end-to-end recipe lives in [`spec/Pattern-Boot.md` §Worked example — the singleton boot machine](../../../spec/Pattern-Boot.md#worked-example--the-singleton-boot-machine) — link there, don't re-derive it; the three rules are:

1. **Address by the REGISTRATION id — NOT `:rf.machine/spawn`, NOT `:system-id`.** A boot machine is a singleton: there is one, its id is known at `reg-machine` time, and that id **is** the address — `(rf/dispatch [:app/boot <event>])`. Do **not** spawn it: `:rf.machine/spawn` allocates a runtime **gensym id**, which is for *dynamic* child actors (one per row / request / worker), and `:system-id` is for role-named dynamic actors. A boot machine is none of those. (Reach for `:spawn` only when boot *itself* spawns child phase-actors — e.g. a `:configuring` state spawning an `:http/get`.)

2. **Eager-kick it from mount / `:initial-events` with `[:rf.machine/start]`.** A singleton is created **lazily** by default (the initial-entry cascade folds into its first real event). Boot wants the opposite — alive **now**, at app start, so the initial state's `:entry` fires. Dispatch the reserved synthetic creation marker `(rf/dispatch [:app/boot [:rf.machine/start]])` from the frame's `:initial-events` (or the host's mount hook). This is xstate's `createActor(m).start()`: it runs the initial macrostep (initial-entry cascade + `:always` settle) then **stops**. A bare `[:start]` is just an unhandled user event → spurious no-op (per the `:first-dispatch` row in the construct-mapping table above and [005 §Synthetic creation marker](../../../spec/005-StateMachines.md#synthetic-creation-marker--rfmachinestart)).

3. **A wholesale `{:db fresh-map}` boot seed is safe — just strip any `:rf/runtime` key.** The boot machine's snapshot lives in the **runtime-db** partition at `[:rf.runtime/machines :snapshots :app/boot]`, NOT in app-db. A `:db` return replaces only the app-db partition and **cannot touch** runtime-db, so the v1-era "wholesale replace silently drops the snapshot" footgun is **structurally gone** — the `:initialize-db` idiom is fine, and `:db`-replace ordering relative to the eager kick no longer matters for snapshot survival. The one residual hazard: the retired `:rf/runtime` app-db root is a **hard error** — a `fresh-db` carrying a top-level `:rf/runtime` key throws `:rf.error/legacy-runtime-root`. Strip it. See the [M-15b walkthrough](guided-handlers-state.md#m-15b--wholesale-app-db-replace--the-retired-rfruntime-root) and [Pattern-Boot §Worked example](../../../spec/Pattern-Boot.md#worked-example--the-singleton-boot-machine).

### Starting a machine WITH parameters — `[:rf.machine/start]` carries no payload

A flow often kicks off with data the surrounding program supplies — a wizard opened on a specific entity id, a login flow seeded with a redirect target, a boot that needs a tenant id. async-flow let you stuff that into `:first-dispatch [:fetch-config tenant-id]`. The instinct on the machine side is to reach for `[:app/boot [:rf.machine/start params]]` — **don't.** The reserved creation marker is a **pure init-kick with no payload**: the runtime threads the literal placeholder `[:rf.machine/start]` (no args) through the initial-entry cascade, and an args-carrying marker is **silently ignored** — the `:entry` actions on the birth call see only `[:rf.machine/start]`, never your params (per [005 §Synthetic creation marker](../../../spec/005-StateMachines.md#synthetic-creation-marker--rfmachinestart): "User code … MUST NOT rely on any other interpretation of the marker"). This is deliberate xstate parity — `createActor(m).start()` takes no event. So there is no "parameterised start marker"; pick one of the three idioms below by the machine's shape.

**(a) Static params known at registration — put them in the spec's `:data`.** When the seed is fixed at registration time (defaults, constants), declare it in the machine's top-level `:data` map; the initial `:entry` reads it from the context `:data` argument.

```clojure
(rf/reg-machine :wizard/checkout
  {:initial :collecting
   :data    {:steps [:cart :address :pay] :step 0}   ;; static seed
   :states  {:collecting {:entry (fn [{:keys [data]}] ...)}}})

(rf/dispatch [:wizard/checkout [:rf.machine/start]])   ;; no params on the marker
```

**(b) Dynamic params for a SINGLETON — seed via lazy first-event, not the eager kick.** When the params are only known at runtime and the machine is a singleton (one instance, addressed by registration id), **skip the eager `[:rf.machine/start]`** and let the machine boot **lazily** on its first real, parameter-carrying event. The initial macrostep folds into that event, and its transition `:action` reads the payload — the params arrive on the event, the way every other machine event carries data.

```clojure
;; initial state handles the seeding event; the machine boots lazily on it.
(rf/reg-machine :app/boot
  {:initial :idle
   :states  {:idle {:on {:seed {:target :loading
                                :action (fn [{:keys [event]}]
                                          (let [[_ tenant-id] event]
                                            {:data {:tenant-id tenant-id}
                                             :fx   [[:dispatch [:fetch-config tenant-id]]]}))}}}
            :loading {...}}})

;; the surrounding program seeds WITH params — no [:rf.machine/start] at all:
(rf/dispatch [:app/boot [:seed tenant-id]])
```

(If the initial state must *also* do birth work that should run before any seeding event — arm an `:after`, wire a subscription — keep the eager `[:rf.machine/start]` for that, and let the *separate* `[:seed …]` event carry the params; the two are independent.)

**(c) Dynamic params for a CHILD actor — `:spawn :data` threads them from the triggering event.** When the machine is a dynamic child (one per row / request / wizard-instance), spawn it with `:rf.machine/spawn` and use the `:data` function form, which receives the spawning event: `(fn [{:keys [snapshot event]}] data)`. This is the canonical "start a child with parameters" path (per [005 §Spawn-spec keys](../../../spec/005-StateMachines.md#spawn-spec-keys) — `:data` "admits a function form so the initial data can depend on … the triggering event").

```clojure
{:fx [[:rf.machine/spawn
       {:machine-id :row/editor
        :data       (fn [{:keys [event]}]
                      (let [[_ row-id] event]
                        {:row-id row-id}))}]]}   ;; child's initial :data seeded from the event
```

> **Verdict for the migrator:** there is **no API gap** here — `[:rf.machine/start]`'s no-payload contract is intended, and the three idioms above cover every parameterised-start need (static → `:data`; dynamic singleton → lazy seed event; dynamic child → `:spawn :data` fn). Do **not** invent a workaround that smuggles params onto the marker. Map the v1 `:first-dispatch`'s arguments onto idiom (a) or (b) for a singleton boot, or onto (c) when the flow becomes a spawned child.

### The simple-case judgement — chain vs machine

A **trivial 2-step linear flow** (do A; when A done, do B; no branching, no multi-await, no failure routing) **MAY** be a plain `reg-event` chain — the `[:a]` handler's success event dispatches `[:b]` via `:fx`, no machine needed. That is the lighter idiom when there is genuinely no state to model.

But the moment the flow **branches** (success vs failure paths), **multi-awaits** (`:seen-all-of?` / `:seen-any-of?`), or carries a notion of **phase**, reach for a **machine** — the FSM makes the phases and their guards explicit, and the snapshot integrates with revert / SSR / trace. **Default to machines** for any non-trivial flow; the chain is the exception reserved for the genuinely linear two-step case.

### Parallel fan-out — `:spawn-all` when each await is its own actor

When the "all must arrive" set is a **parallel fan-out** — kick off N independent async tasks, advance when all complete — and each task is naturally its own child actor, `:spawn-all` with `:join :all` expresses it declaratively instead of N correlated record-in-`:data` flags:

```clojure
:loading
{:spawn-all
 {:children         [{:id :cfg  :machine-id :load-config}
                     {:id :user :machine-id :load-user}]
  :join             :all
  :on-child-done    :child/done            ;; required — child→parent success signal
  :on-child-error   :child/error           ;; required — child→parent failure signal
  :on-all-complete  [:all-loaded]
  :on-any-failed    [:load-failed]}
 :on {:all-loaded  :ready
      :load-failed :failed}}
```

`:on-child-done` and `:on-child-error` are **required** keys (per [§Spawn-and-join via `:spawn-all`](../../../spec/005-StateMachines.md#spawn-and-join-via-spawn-all)); the runtime intercepts the child completion signals, evaluates `:join`, and fires `:on-all-complete` / `:on-any-failed` into the parent. Use this shape only when turning each await into a child machine is warranted — for awaits that are plain dispatched events from existing handlers, the record-in-`:data` + `:always` guard shape above is lighter. Phase-level wall-clock timeouts ride the parent state's `:after` slot — `:spawn-all` carries no `:timeout-ms` (per [M-44](breaking-changes.md#required-m-rules-by-trigger-surface)).

## Escalate — the agent surfaces and stops

Do **not** silently rewrite these; present the call site, the reason, and wait for direction:

- **`:halt-fns?` / `:rules` predicates closing over state outside `:data`** (the engine's seen-event history, an app-db slice, a sub value). Machine guards / actions are encapsulated to `:data` (per [§Guards](../../../spec/005-StateMachines.md#guards)); the rewrite restructures the signal to arrive as a dispatched event the machine records, or escalates as a design conversation.
- **`:events` as a predicate fn** (matched against each observed event) rather than a keyword / vector. The machine's `:on` is keyword-indexed; arbitrary-predicate matching has no direct equivalent — restructure the upstream dispatches to carry distinguishing ids, or escalate.
- **A `:rules` vector computed at runtime** (`(into base (when flag? extra))`). The machine spec is declarative and stamped at registration. Conditional behaviour belongs in `:guard` / `:always` branches, not a computed rule list.
- **A flow whose `:db-path` is read by other code.** The snapshot is a different location and shape; every reader must move to `(rf/subscribe [:rf/machine <id>])`. Escalate so the operator can locate them.

## Reporting

- List every `:async-flow` call site found, whether the operator approved each rewrite, and the new machine id.
- **List every producer retargeted (or bridged).** For each awaited event in the flow, name the handler/fx whose dispatch you re-addressed to `[<machine-id> ...]` (or the bridge handler you added when the global event had to stay public). An unretargeted producer is a silent stuck-boot — call out any you could not locate so the operator can find them.
- When the `day8.re-frame/async-flow-fx` dep is no longer referenced, flag it for removal; the operator confirms before the coord is dropped. The `day8/re-frame2-machines` dep is added per M-28.
- List each escalation with file/line, the reason, and the recommended path.

---

*Authoritative grammar: [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md). v1 add-on: [`async-flow-fx`](https://github.com/day8/re-frame-async-flow-fx). Forced-compile context: [`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in). Sibling guide: [`http-fx-to-managed-http.md`](http-fx-to-managed-http.md) (O-17).*

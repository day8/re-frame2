# CP-5 Machine Guide (appendix to Construction Prompts CP-5)

> **Type:** Construction Prompts
> Detailed machine-construction guidance that supports [Construction-Prompts.md §CP-5](Construction-Prompts.md#cp-5-scaffold-a-state-machine). The CP-5 prompt itself is the build-facing template; this appendix carries the deeper material an AI agent needs when the user asks for non-trivial guards, the N-machines-per-region pattern for conceptually-independent features, the v1 grammar subset, or the inline-fn vs named-action escape-hatch test.

This file is intentionally **kept separate** from Construction-Prompts.md so that CP-5 reads as a parallel sibling to CP-1/CP-2/CP-3/CP-4 rather than a doc-within-a-doc. Read CP-5 first; consult this file when the prompt's checklist sends you here.

## Registration — `reg-machine` and `reg-machine*`

Two equivalent surfaces register a machine; CP-5-generated scaffolds default to **`reg-machine`** (the macro). Both register the same thing — an event handler whose body interprets the transition table — and both stamp the registry slot with `:rf/machine? true` and `:rf/machine <spec>` so that `(rf/machines)` and `(rf/machine-meta id)` see the registration (per [005 §Querying machines](005-StateMachines.md#querying-machines)).

```clojure
;; Standard form — the macro (preferred).
(rf/reg-machine :auth.login/flow
  {:initial :idle
   :data    {...}
   :guards  {...}
   :actions {...}
   :states  {...}})

;; Plain-fn surface — for code-gen, REPL, fixture-synthesised specs.
(rf/reg-machine* machine-id (build-spec-from-edn fixture))
```

| Form | Shape | Source-coord stamping | Use case |
|---|---|---|---|
| `(rf/reg-machine machine-id machine-spec)` | **macro** | Yes — call-site coords on the registry slot AND per-element coord index walked from the literal spec form (per [005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping)) | The default. Use whenever the spec is a literal map at the call site. |
| `(rf/reg-machine* machine-id machine-spec)` | plain fn | None — the spec is opaque data at the call site | Code-gen pipelines, REPL exploration, conformance harnesses that synthesise specs from EDN fixtures. |

Both forms live in `re-frame.machines` (the `day8/re-frame2-machines` artefact). The `reg-machine` / `defmachine` **macros** are re-exported on the `re-frame.core` façade (they capture call-site source-coords); the plain-fn `reg-machine*` is **not** re-exported — reach it through `re-frame.machines/reg-machine*` (front-porch shrink: the non-registration / plain-fn machine surface stays in its owning namespace). See [API.md §Machines](API.md#machines) and [005 §`reg-machine` — public registration surface](005-StateMachines.md#reg-machine--public-registration-surface) for the canonical contract.

The older `reg-event + make-machine-handler` form (visible in [Construction-Prompts.md §CP-5](Construction-Prompts.md#cp-5-scaffold-a-state-machine) examples) registers the *same* slot — `reg-machine` is the convenience surface that wraps it and adds the metadata stamp.

## The inline-fn escape hatch

CP-5 says: **default to named guards and actions**; inline fns are an escape hatch for trivial logic, not the default form. The test for "trivial" is **single non-branching expression**.

Acceptable inline cases (single non-branching expression):

```clojure
;; Trivial guard — single non-branching expression.
:guard (fn [{:keys [data]}] (some? (:circle-id data)))

;; Trivial action — pure data update, single non-branching expression.
;; Event values are destructured from the :event vector inside the body.
:action (fn [{[_ new-r] :event}] {:data {:preview-radius new-r}})

;; Trivial action — single :fx entry, no branching.
:action (fn [{:keys [data]}]
          {:fx [[:dispatch [:drawer/apply-radius
                            (:circle-id data)
                            (:preview-radius data)]]]})
```

Cases that should be named in `:guards` / `:actions` instead (branching, composition, or compound predicate):

```clojure
;; Branching → name it.
:action (fn [{:keys [data event]}]
          (if (over-quota? data)
            {:data {:error :quota}}
            {:data {:attempts (inc (:attempts data))}
             :fx   [[:dispatch [:audit/recorded event]]]}))

;; Composed → name the composition.
:action (fn [{:keys [data event]}]
          (let [a (record-attempt data event) b (clear-error data event)]
            {:data (merge (:data a) (:data b))
             :fx   (into (:fx a []) (:fx b []))}))

;; Multi-fx + branching → name it.
:guard (fn [{:keys [data event]}]
         (and (under-quota? data event)
              (not (locked-out? data event))
              (some? (:credentials (second event)))))
```

For the third case (compound predicate), prefer naming the compound — `:eligible-to-submit?` in the machine's `:guards` map is what visualisers and AIs read on the transition arrow.

## v1 grammar subset

v1 ships the **machine-as-event-handler foundation** — `make-machine-handler`, `machine-transition`, the `[:rf.machine/spawn ...]` and `[:rf.machine/destroy ...]` lifecycle fx, the reserved fx-id `:raise` (machine-internal), the `[:rf.runtime/machines :snapshots <id>]` runtime-db storage scheme, four-level drain, machine-scoped `:guards` / `:actions` declaration with registration-time validation, and the discovery lens (`(rf/machines)` / `(rf/machine-meta id)`).

The grammar this foundation interprets (per [005 §Capability matrix](005-StateMachines.md#capability-matrix)):

- `:initial`, `:data`
- root-only `:guards` and `:actions` maps
- per-state `:on`
- transition shape `{:target :guard :action :meta}` with **single-fn-or-keyword-reference** `:guard` and `:action` slots
- per-state `:entry` / `:exit` as single fns or keyword references
- `:*` wildcard, self-transitions
- the reserved fx-id `:raise` (machine-internal) plus the canonical actor-lifecycle fx-ids `:rf.machine/spawn` / `:rf.machine/destroy` inside the action's returned `:fx`

The snapshot location is fixed at `[:rf.runtime/machines :snapshots <id>]` in runtime-db — no `:path` key in the spec.

Hierarchical compound states, eventless `:always`, delayed `:after`, declarative `:spawn`, state tags (`:fsm/tags`), parallel regions (`:fsm/parallel-regions`), spawn-and-join (`:actor/spawn-and-join`), and `:system-id` named-machine addressing are all claimed in the v1 capability list — see the matrix for the full set and per-capability fixture coverage.

## Substitutes for skipped features

> Heading retained for stable cross-references (Spec 005 and Spec-Schemas link this anchor). Nothing below is a substitute for a *skipped* feature: parallel regions and history states are both **first-class, shipped capabilities** (✓ claimed in the [Spec 005 §Capability matrix](005-StateMachines.md#capability-matrix)). The "N separate machines" pattern is a deliberate **design choice** for the conceptually-independent-features case — not a workaround for something the runtime can't do.

Per (Nine States Stage 2), **parallel regions** are a **first-class capability** (`:fsm/parallel-regions`; see [Spec 005 §Parallel regions](005-StateMachines.md#parallel-regions)). The N-machines-per-region pattern documented in this section is the right answer when the regions are **conceptually independent features** that don't share data — multiple tabs each with their own state, boot phases plus diagnostics, an audio/video player whose two regions share nothing but the play/pause event. Parallel regions inside one machine (`:type :parallel`) are the right answer when the regions are **orthogonal axes of one feature** that share a single `:data` blob — one form with three independent axes (data / form / mode), one widget with display + interaction state, one page whose render-mode is a function of three independent inputs. Both patterns ship together; choose by domain shape.

**History states** are likewise a first-class capability now (`:fsm/history`; see [Spec 005 §History states](005-StateMachines.md#history-states-type-history--shallow--deep--default-target)) — declare a `:type :history` pseudo-state under the compound and the runtime records and restores its last-active configuration. There is no substitute pattern to reach for: "remember where the user was" is one node in the transition table, not hand-rolled capture/restore wiring. The first-class grammar is summarised in §History states below.

### Parallel regions → separate machines per region (independent-features case)

xstate's parallel-region machines model two-or-more independent regions advancing concurrently inside one machine (e.g., a media player with `audio.{playing,paused}` and `video.{visible,hidden}` running side by side). The substrate concern is "atomic, inspectable, composable concurrency."

re-frame2 ships **two** answers; pick by domain shape:

- **Orthogonal axes of one feature, shared `:data`.** Use `:type :parallel` with `:regions` in a single machine. The axes coordinate through one shared `:data` map and the snapshot's `:state` is a map of region → keyword-or-path. See [Spec 005 §Parallel regions](005-StateMachines.md#parallel-regions).
- **Independent features, no shared `:data`.** Use **one machine per region**, coordinated via cross-actor dispatch. Each region is a separate machine — separate `[:rf.runtime/machines :snapshots <id>]` snapshot in runtime-db, separate transition table, independent inspection. Synchronising events fan out through `:fx [[:dispatch ...]]`. This is the pattern below.

The media-player example uses two genuinely independent regions (audio and video have no shared data, only the play/pause/stop event coordinates them) — the N-machine pattern is the right fit:

#### Worked example — media player with audio/video regions

```clojure
;; ---- region 1: audio playback state ---------------------------------------

(rf/reg-event :media/audio
  {:doc "Audio region — playing / paused."}
  (rf/make-machine-handler
    {:initial :paused
     :data    {:position 0}
     :states
     {:paused
      {:on {:media/play  {:target :playing
                          :action :media.audio/start}}}

      :playing
      {:on {:media/pause {:target :paused
                          :action :media.audio/stop}
            :media/stop  {:target :paused
                          :action :media.audio/reset}}}}}))

;; ---- region 2: video visibility state -------------------------------------

(rf/reg-event :media/video
  {:doc "Video region — visible / hidden."}
  (rf/make-machine-handler
    {:initial :hidden
     :data    {}
     :states
     {:hidden
      {:on {:media/play  {:target :visible
                          :action :media.video/show}}}

      :visible
      {:on {:media/pause {}                                 ;; pausing leaves the frame visible
            :media/stop  {:target :hidden
                          :action :media.video/hide}}}}}))

;; ---- coordinator event — fans out to both regions atomically -------------

(rf/reg-event :media/play
  {:doc "Coordinator: start both regions atomically."}
  (fn [_ _]
    {:fx [[:dispatch [:media/audio [:media/play]]]
          [:dispatch [:media/video [:media/play]]]]}))

(rf/reg-event :media/pause
  (fn [_ _]
    {:fx [[:dispatch [:media/audio [:media/pause]]]
          [:dispatch [:media/video [:media/pause]]]]}))

(rf/reg-event :media/stop
  (fn [_ _]
    {:fx [[:dispatch [:media/audio [:media/stop]]]
          [:dispatch [:media/video [:media/stop]]]]}))
```

What this gives:

- **Atomicity.** Run-to-completion drain at the frame level means `:media/play` runs both `:media/audio [:media/play]` and `:media/video [:media/play]` to completion before any other event sees state. From outside the frame, the two regions advance together.
- **Inspection.** `(rf/machines)` enumerates both regions; `@(rf/subscribe [:rf/machine :media/audio])` and `@(rf/subscribe [:rf/machine :media/video])` are independent reads. Tooling treats them as the two separate things they are, not as nested keys inside a parallel-region snapshot.
- **Undo.** Each region's snapshot lives at its own `[:rf.runtime/machines :snapshots <id>]` key in runtime-db; reverting the frame-state rolls both back together.
- **Composability.** A view caring only about audio subscribes to `:media/audio`; video-only views ignore audio entirely. Parallel-region snapshots in xstate force consumers to subscribe to the umbrella machine and project — extra ceremony for the same outcome.
- **Discoverability.** Each region has a name (`:media/audio`) and a registry entry. xstate's regions live anonymously inside the parent machine's transition table.

The cost: a coordinating event (`:media/play`) is one extra registration. In exchange the two regions are independently testable, independently inspectable, and independently rolled back. The substrate's machinery does the heavy lifting at no extra cost.

## History states — first-class `:type :history`

xstate's history states re-enter a compound state at *the substate that was active when it was last left*. re-frame2 ships this directly: a `:type :history` pseudo-state declared under a compound's `:states` is a transition target the runtime resolves to that compound's recorded (or default) configuration. There is no capture/restore code to hand-roll — the recording rides the snapshot for free (it lives in the snapshot's reserved `:rf/history` slot), so undo, time-travel, persistence, and SSR hydration all extend to it without extra machinery.

```clojure
;; A :player compound that, when re-entered via :play, resumes the substate
;; it was on when it last paused/stopped — not :playing's :initial.
{:player
 {:initial :stopped
  :states {:stopped {:on {:play [:player :hist]}}        ;; target the pseudo-state to restore
           :hist    {:type :history
                     :deep? true                          ;; omit => SHALLOW (restore direct child, then cascade its :initial)
                     :default-target :playing}            ;; omit => falls back to :player's :initial when nothing recorded yet
           :playing {:initial :at-start
                     :states {:at-start {:on {:seek :mid-track}}
                              :mid-track {}}}
           :paused  {:on {:resume [:player :playing]}}}}}
```

The pseudo-state carries exactly three keys: `:type :history`, `:deep?` (boolean; missing reads as shallow), and `:default-target` (used the first time the compound is entered, before anything is recorded; missing falls back to the compound's `:initial`). It is never occupied — a transition *to* it resolves to a real leaf, and that resolved leaf is what the snapshot's `:state` records.

- **Shallow vs deep.** Shallow restores the recorded *direct child* of the compound, then cascades that child's own `:initial` chain. Deep restores the *full recorded leaf path* beneath the compound. Default is shallow.
- **Per-region under `:type :parallel`.** Each region records and restores its own history independently; the `:rf/history` keys are region-qualified, so sibling regions never collide.
- **Registration constraints.** A `:type :history` node MUST live inside a compound's `:states`, declares only those three keys (any of `:on` / `:entry` / `:exit` / `:states` / … is a registration error), and a compound declares at most one.

See [Spec 005 §History states](005-StateMachines.md#history-states-type-history--shallow--deep--default-target) for the full recording / restoring semantics, the `:rf/history` slot shape, the dangling-recorded-path policy after hot reload, and per-region composition. The capability flag is `:fsm/history`; the v1 CLJS reference claims it.

## Lessons from xstate (deliberate divergences)

For readers familiar with xstate, the explicit list of where re-frame2 chose differently and why:

| xstate | re-frame2 | Why |
|---|---|---|
| `ActorRef` runtime objects | Snapshots at `[:rf.runtime/machines :snapshots <id>]` in runtime-db | Data orientation; agent-friendliness; no leak footguns |
| `const ref = spawn(child)` captured into `context`, then `stopChild(ref)` | The declarative `:spawn` reducer binds the assigned id into the parent's `:data` under `:rf/spawned`; an action reads `(get-in data [:rf/spawned <invoke-id>])` and emits `[:rf.machine/destroy <id>]` | Same capability (an action holding the id of an actor it spawned) but the id rides the revertible, SSR-survivable snapshot rather than a live mutable ref — no leak footgun, and `restore-epoch!` reverts it for free |
| Per-actor mailboxes | One per-frame router queue | Simpler model; drain at the frame level is the granularity that matters |
| `raise` (self-event) vs `sendTo` (other-actor) | Single `dispatch`; `:raise` is sugar for self-dispatch with atomic semantics | One pipeline; no per-actor mailbox to put events at the front of |
| Three creation modes (`createActor` / `invoke` / `spawn`) | One mechanism, two patterns (singleton via `reg-event`; dynamic via the `[:rf.machine/spawn ...]` fx) | Lifetime is encoded in the runtime-db snapshot shape and registration lifetime |
| Machine hierarchy as a structural concept | Hierarchy encoded in `app-db` nested structure | Stay data-oriented; no new framework primitive |
| Event-as-object API | Event vector + envelope metadata | Compatible with re-frame's existing event shape |
| `:context` for extended state | `:data` | Avoid the triply-overloaded "context" name; align with `gen_statem` vocabulary |
| Compound guards as `{and: [...]}` data | One fn or one named registered compound | Imperative composition is fns; named compounds carry semantic content |
| Action-vector `[a1 a2 a3]` per slot | One fn or one named registered compound | Same reason as guards |
| `setup({actors, guards, actions})` per-machine bundle | Per-machine `:guards` / `:actions` maps inside the `make-machine-handler` spec | Convergence: machine-scoped declaration (not globally-registered). Each machine has its own guard/action namespace, validated at registration time; cross-machine reuse is via Clojure vars |
| `[:assign {...}]` action data form | Action returns `{:data {...}}` | Symmetric with `reg-event`'s `{:db :fx}`; one fewer DSL to parse |
| `invoke` (state-node spawn key) | `:spawn` (and `:spawn-all` for parallel-fanout-and-join) | Deliberate name divergence. Convergence is high enough on other keys (`:final?`, `:on-done`, `:guard`, `:action`, `:entry`, `:exit`, `:after`, `:always`, `:tags`) that AI agents trained on xstate would otherwise generate almost-correct code that misses re-frame2's per-feature spec nuances. Renaming the most semantically-loaded slot breaks the convergence trap and aligns the declarative key with the existing imperative `:rf.machine/spawn` fx. See [005 §Deliberate name divergence — `:spawn`](005-StateMachines.md#deliberate-name-divergence--spawn-not-invoke). |
| v4 `internal: true` (opt OUT of external default) → **v5 `reenter: true`** (opt IN to external; internal is the default) | `:reenter?` boolean — internal self/ancestor transitions by default; `:reenter? true` makes them external | **Convergence with XState v5, not a divergence.** re-frame2 follows the v5 default: a self / proper-ancestor `:target` is internal (no exit/entry, no `:after`/`:spawn` restart) unless `:reenter? true`. This is a deliberate divergence *from SCXML / XState v4* (whose targeted transitions are external by default) and an alignment *to* the v5 gold standard. An author trained on XState v5 ports their `reenter` intuition directly. See [005 §Self-transitions](005-StateMachines.md#self-transitions--internal-default-vs-external-reenter). |
| `enqueueActions(({enqueue, check, context, event}) => …)` — v5's imperative action-list builder: a callback that pushes actions onto a queue, optionally guarded by inline `check(guard)` | An action returns the `{:data :fx}` effect map. `:fx` **is** the enqueue — a data vector of `[fx-id args]` pairs processed in order; `[:raise <event>]` entries **are** the internal-event enqueue; conditional pushes are ordinary Clojure `(when …)` / `cond` inside the action fn building the `:fx` vector | **Substrate reason: re-frame2 is data-first.** An imperative action-list *builder* is anti-idiomatic — re-frame2 already expresses "do this list of effects, some conditionally" as a returned data vector (`:fx`), not as imperative pushes to a mutable queue. There is no separate enqueue primitive *by design*: the effect model (`:fx` for external effects, `:raise` for internal self-events) already covers the entire `enqueueActions` use-case, and `check` collapses into the host language's own conditionals. Clean divergence, no capability gap. See [005 §Action effect map](005-StateMachines.md#action-effect-map--data-fx) and [005 §`:raise`, `:rf.machine/spawn`, and `:rf.machine/destroy` are reserved fx-ids](005-StateMachines.md#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx). |
| `emit(eventObject)` + `actor.on(type, handler)` — v5.9.0's fire-and-forget side-channel: an actor emits domain events to *external* subscribers (NOT transitions, NOT other actors' mailboxes) | App-level `:fx [[:dispatch [<notification-event>]]]` for **domain notification** — dispatch an event whose handler is intentionally state-neutral (a pure-fx subscriber). Per-instance `actor.on(…)` ergonomics map to a dispatch carrying the instance id plus handler / trace filtering. The trace bus is a **separate** observability channel — do NOT conflate `dispatch` with trace | **Substrate reason: re-frame2 has ONE router/bus.** XState needs `emit` *because* each actor owns an isolated mailbox, so cross-boundary domain notifications need a side-channel that bypasses the parent/child send graph. re-frame2 routes every event through a single per-frame queue (one [per-application trace stream](009-Instrumentation.md) for observability), so `emit`'s external side-channel is already subsumed by `dispatch` — there is no isolated mailbox to escape from. See [005 §Cross-cutting writes via `:fx`](005-StateMachines.md#action-effect-map--data-fx). |
| Actor-logic creators `fromPromise` / `fromCallback` / `fromObservable` / `fromTransition` / `fromEventObservable` — v5 models non-machine async sources (a promise, a callback, an observable, a reducer) as **spawnable actors** | Async work is **fx + flows**, not actors: a one-shot async request is `:rf.http/managed` / the lower-level `:http` fx ([Spec 014](014-HTTPRequests.md)); a derived-state rule is a flow ([Spec 013](013-Flows.md)); a reducer (`fromTransition`) IS just a machine. A spawned `:spawn` / `:spawn-all` child is **always** a machine (or a `:definition` spec) | **Model divergence: re-frame2 does not model promises / callbacks / observables as spawnable actors, by design.** There is no `from*` analog *because* async is not modeled as actors — it is modeled as managed effects (Spec 014) and derived-state flows (Spec 013), both of which already carry framework-owned lifecycle, structured failure taxonomy, retry / abort, and trace observability per [Managed-Effects](Managed-Effects.md). The actor abstraction is reserved for state machines; everything XState reaches an actor-logic creator for, re-frame2 reaches an fx or a flow for. |
| Multiple `invoke` (a **vector** of invoke per state) | `:spawn-all` — declarative spawn-and-join of N parallel child actors (named children, a join condition, an explicit cancellation policy); a single child uses `:spawn`. A vector `:spawn` is **deliberately not added** | **ALIGNED IN SPIRIT, different spelling by design (item 1).** XState's vector-of-invoke maps cleanly onto `:spawn-all` — behavioural parity via a different (named children + explicit join/cancellation, AI-first explicit) expression. A vector `:spawn` would be a redundant *second* multi-child API. The independent / fire-and-forget N-children case is covered within `:spawn-all` via `:cancel-on-decision? false` + `:join :any` / `{:fn …}` (the analytics-fan-out idiom). See [005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) and [005 §Lessons §Three non-substrate divergences](005-StateMachines.md#three-non-substrate-divergences--ruled-and-recorded). |
| Nested parallel regions (a region whose own tree is `:type :parallel`) | Rejected loudly at registration with `:rf.error/machine-parallel-nested-not-supported` | **DEFERRED post-v1, NOT blessed permanent (item 2).** Supporting it requires generalizing re-frame2's *flat* state-value model into a *recursive statechart tree* — touching the snapshot shape, target resolution, root fallback, done semantics, tags, history, and Xray projection (all assume root-only parallelism). Not frame/queue substrate-constrained, but a deep architectural generalization, which justifies both the defer and the fail-closed rejection. See [005 §Parallel regions](005-StateMachines.md#parallel-regions). |
| `after` at a parallel root | **`:after` on the `:type :parallel` root** — a root-owned delayed transition (the timer-driven analog of the root `:on` ancestor fallback): scheduled at machine birth, region-qualified targets, untargeted regions unchanged, stale-gated by the root's own per-path epoch | **ALIGNED — implemented (item 3).** A genuine semantic divergence, not parity-via-expression: the region-`:after`-`:raise`s workaround is semantically weaker (timer bound to an arbitrary region's lifecycle), whereas a root `:after` is root-owned (alive for the whole machine). Reuses the root `:on` transition grammar. The old loud rejection `:rf.error/machine-parallel-root-after-not-supported` is removed. See [005 §Root parallel `:after`](005-StateMachines.md#root-parallel-on--the-ancestor-fallback). |

Convergences: machines-as-actors, run-to-completion, encapsulated state, snapshots, definition/implementation split, transition tables as data, **self/ancestor self-transitions internal-by-default with `:reenter?` for the external form (XState v5 `reenter`)**.

## Cross-references

- [Construction-Prompts.md §CP-5](Construction-Prompts.md#cp-5-scaffold-a-state-machine) — the build-facing prompt this file supports.
- [005-StateMachines.md](005-StateMachines.md) — the canonical machine spec.
- [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) — the transition-table grammar schema.

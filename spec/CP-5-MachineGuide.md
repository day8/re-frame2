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

Both forms live in `re-frame.machines` (the `day8/re-frame2-machines` artefact) and are re-exported under `re-frame.core`. See [API.md §Machines](API.md#machines) and [005 §`reg-machine` — public registration surface](005-StateMachines.md#reg-machine--public-registration-surface) for the canonical contract.

The older `reg-event-fx + make-machine-handler` form (visible in [Construction-Prompts.md §CP-5](Construction-Prompts.md#cp-5-scaffold-a-state-machine) examples) registers the *same* slot — `reg-machine` is the convenience surface that wraps it and adds the metadata stamp.

## The inline-fn escape hatch

CP-5 says: **default to named guards and actions**; inline fns are an escape hatch for trivial logic, not the default form. The test for "trivial" is **single non-branching expression**.

Acceptable inline cases (single non-branching expression):

```clojure
;; Trivial guard — single non-branching expression.
:guard (fn [data _] (some? (:circle-id data)))

;; Trivial action — pure data update, single non-branching expression.
:action (fn [_ [_ new-r]] {:data {:preview-radius new-r}})

;; Trivial action — single :fx entry, no branching.
:action (fn [data _]
          {:fx [[:dispatch [:drawer/apply-radius
                            (:circle-id data)
                            (:preview-radius data)]]]})
```

Cases that should be named in `:guards` / `:actions` instead (branching, composition, or compound predicate):

```clojure
;; Branching → name it.
:action (fn [data ev]
          (if (over-quota? data)
            {:data {:error :quota}}
            {:data {:attempts (inc (:attempts data))}
             :fx   [[:dispatch [:audit/recorded ev]]]}))

;; Composed → name the composition.
:action (fn [data ev]
          (let [a (record-attempt data ev) b (clear-error data ev)]
            {:data (merge (:data a) (:data b))
             :fx   (into (:fx a []) (:fx b []))}))

;; Multi-fx + branching → name it.
:guard (fn [data ev]
         (and (under-quota? data ev)
              (not (locked-out? data ev))
              (some? (:credentials (second ev)))))
```

For the third case (compound predicate), prefer naming the compound — `:eligible-to-submit?` in the machine's `:guards` map is what visualisers and AIs read on the transition arrow.

## v1 grammar subset

v1 ships the **machine-as-event-handler foundation** — `make-machine-handler`, `machine-transition`, the `[:rf.machine/spawn ...]` and `[:rf.machine/destroy ...]` lifecycle fx, the reserved fx-id `:raise` (machine-internal), the `[:rf/runtime :machines :snapshots <id>]` storage scheme, four-level drain, machine-scoped `:guards` / `:actions` declaration with registration-time validation, and the discovery lens (`(rf/machines)` / `(rf/machine-meta id)`).

The grammar this foundation interprets (per [005 §Capability matrix](005-StateMachines.md#capability-matrix)):

- `:initial`, `:data`
- root-only `:guards` and `:actions` maps
- per-state `:on`
- transition shape `{:target :guard :action :meta}` with **single-fn-or-keyword-reference** `:guard` and `:action` slots
- per-state `:entry` / `:exit` as single fns or keyword references
- `:*` wildcard, self-transitions
- the reserved fx-id `:raise` (machine-internal) plus the canonical actor-lifecycle fx-ids `:rf.machine/spawn` / `:rf.machine/destroy` inside the action's returned `:fx`

The snapshot location is fixed at `[:rf/runtime :machines :snapshots <id>]` — no `:path` key in the spec.

Hierarchical compound states, eventless `:always`, delayed `:after`, declarative `:spawn`, state tags (`:fsm/tags`), parallel regions (`:fsm/parallel-regions`), spawn-and-join (`:actor/spawn-and-join`), and `:system-id` named-machine addressing are all claimed in the v1 capability list — see the matrix for the full set and per-capability fixture coverage.

## Substitutes for skipped features

Per (Nine States Stage 2), **parallel regions** are now a **first-class capability** (`:fsm/parallel-regions`; see [Spec 005 §Parallel regions](005-StateMachines.md#parallel-regions)). The N-machines-per-region pattern documented in this section remains valid and is the right answer when the regions are **conceptually independent features** that don't share data — multiple tabs each with their own state, boot phases plus diagnostics, an audio/video player whose two regions share nothing but the play/pause event. Parallel regions inside one machine (`:type :parallel`) are the right answer when the regions are **orthogonal axes of one feature** that share a single `:data` blob — one form with three independent axes (data / form / mode), one widget with display + interaction state, one page whose render-mode is a function of three independent inputs. Both patterns ship together; choose by domain shape.

**History states** are likewise a first-class capability now (`:fsm/history`; see [Spec 005 §History states](005-StateMachines.md#history-states-type-history--shallow--deep--default-target)) — declare a `:type :history` pseudo-state under the compound and the runtime records and restores its last-active configuration. There is no substitute pattern to reach for: "remember where the user was" is one node in the transition table, not hand-rolled capture/restore wiring. The first-class grammar is summarised in §History states below.

### Parallel regions → separate machines per region (independent-features case)

xstate's parallel-region machines model two-or-more independent regions advancing concurrently inside one machine (e.g., a media player with `audio.{playing,paused}` and `video.{visible,hidden}` running side by side). The substrate concern is "atomic, inspectable, composable concurrency."

re-frame2 ships **two** answers; pick by domain shape:

- **Orthogonal axes of one feature, shared `:data`.** Use `:type :parallel` with `:regions` in a single machine. The axes coordinate through one shared `:data` map and the snapshot's `:state` is a map of region → keyword-or-path. See [Spec 005 §Parallel regions](005-StateMachines.md#parallel-regions).
- **Independent features, no shared `:data`.** Use **one machine per region**, coordinated via cross-actor dispatch. Each region is a separate machine — separate `[:rf/runtime :machines :snapshots <id>]` snapshot, separate transition table, independent inspection. Synchronising events fan out through `:fx [[:dispatch ...]]`. This is the pattern below.

The media-player example uses two genuinely independent regions (audio and video have no shared data, only the play/pause/stop event coordinates them) — the N-machine pattern is the right fit:

#### Worked example — media player with audio/video regions

```clojure
;; ---- region 1: audio playback state ---------------------------------------

(rf/reg-event-fx :media/audio
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

(rf/reg-event-fx :media/video
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

(rf/reg-event-fx :media/play
  {:doc "Coordinator: start both regions atomically."}
  (fn [_ _]
    {:fx [[:dispatch [:media/audio [:media/play]]]
          [:dispatch [:media/video [:media/play]]]]}))

(rf/reg-event-fx :media/pause
  (fn [_ _]
    {:fx [[:dispatch [:media/audio [:media/pause]]]
          [:dispatch [:media/video [:media/pause]]]]}))

(rf/reg-event-fx :media/stop
  (fn [_ _]
    {:fx [[:dispatch [:media/audio [:media/stop]]]
          [:dispatch [:media/video [:media/stop]]]]}))
```

What this gives:

- **Atomicity.** Run-to-completion drain at the frame level means `:media/play` runs both `:media/audio [:media/play]` and `:media/video [:media/play]` to completion before any other event sees state. From outside the frame, the two regions advance together.
- **Inspection.** `(rf/machines)` enumerates both regions; `@(rf/sub-machine :media/audio)` and `@(rf/sub-machine :media/video)` are independent reads. Tooling treats them as the two separate things they are, not as nested keys inside a parallel-region snapshot.
- **Undo.** Each region's snapshot lives at its own `[:rf/runtime :machines :snapshots <id>]` key; reverting `app-db` rolls both back together.
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
| `ActorRef` runtime objects | Snapshots at `[:rf/runtime :machines :snapshots <id>]` in `app-db` | Data orientation; agent-friendliness; no leak footguns |
| Per-actor mailboxes | One per-frame router queue | Simpler model; drain at the frame level is the granularity that matters |
| `raise` (self-event) vs `sendTo` (other-actor) | Single `dispatch`; `:raise` is sugar for self-dispatch with atomic semantics | One pipeline; no per-actor mailbox to put events at the front of |
| Three creation modes (`createActor` / `invoke` / `spawn`) | One mechanism, two patterns (singleton via `reg-event-fx`; dynamic via the `[:rf.machine/spawn ...]` fx) | Lifetime is encoded in `app-db` shape and registration lifetime |
| Machine hierarchy as a structural concept | Hierarchy encoded in `app-db` nested structure | Stay data-oriented; no new framework primitive |
| Event-as-object API | Event vector + envelope metadata | Compatible with re-frame's existing event shape |
| `:context` for extended state | `:data` | Avoid the triply-overloaded "context" name; align with `gen_statem` vocabulary |
| Compound guards as `{and: [...]}` data | One fn or one named registered compound | Imperative composition is fns; named compounds carry semantic content |
| Action-vector `[a1 a2 a3]` per slot | One fn or one named registered compound | Same reason as guards |
| `setup({actors, guards, actions})` per-machine bundle | Per-machine `:guards` / `:actions` maps inside the `make-machine-handler` spec | Convergence: machine-scoped declaration (not globally-registered). Each machine has its own guard/action namespace, validated at registration time; cross-machine reuse is via Clojure vars |
| `[:assign {...}]` action data form | Action returns `{:data {...}}` | Symmetric with `reg-event-fx`'s `{:db :fx}`; one fewer DSL to parse |
| `invoke` (state-node spawn key) | `:spawn` (and `:spawn-all` for parallel-fanout-and-join) | Deliberate name divergence. Convergence is high enough on other keys (`:final?`, `:on-done`, `:guard`, `:action`, `:entry`, `:exit`, `:after`, `:always`, `:tags`) that AI agents trained on xstate would otherwise generate almost-correct code that misses re-frame2's per-feature spec nuances. Renaming the most semantically-loaded slot breaks the convergence trap and aligns the declarative key with the existing imperative `:rf.machine/spawn` fx. See [005 §Deliberate name divergence — `:spawn`](005-StateMachines.md#deliberate-name-divergence--spawn-not-invoke). |

Convergences: machines-as-actors, run-to-completion, encapsulated state, snapshots, definition/implementation split, transition tables as data.

## Cross-references

- [Construction-Prompts.md §CP-5](Construction-Prompts.md#cp-5-scaffold-a-state-machine) — the build-facing prompt this file supports.
- [005-StateMachines.md](005-StateMachines.md) — the canonical machine spec.
- [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) — the transition-table grammar schema.

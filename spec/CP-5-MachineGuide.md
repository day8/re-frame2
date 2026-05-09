# CP-5 Machine Guide (appendix to Construction Prompts CP-5)

> **Type:** Construction Prompts
> Detailed machine-construction guidance that supports [Construction-Prompts.md §CP-5](Construction-Prompts.md#cp-5-scaffold-a-state-machine). The CP-5 prompt itself is the build-facing template; this appendix carries the deeper material an AI agent needs when the user asks for non-trivial guards, parallel-region substitutes, history-state substitutes, the v1 grammar subset, or the inline-fn vs named-action escape-hatch test.

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
| `(rf/reg-machine machine-id machine-spec)` | **macro** | Yes — call-site coords on the registry slot AND per-element coord index walked from the literal spec form (per [005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping-rf2-8bp3)) | The default. Use whenever the spec is a literal map at the call site. |
| `(rf/reg-machine* machine-id machine-spec)` | plain fn | None — the spec is opaque data at the call site | Code-gen pipelines, REPL exploration, conformance harnesses that synthesise specs from EDN fixtures. |

Both forms live in `re-frame.machines` (the `day8/re-frame-2-machines` artefact) and are re-exported under `re-frame.core`. See [API.md §Machines](API.md#machines) and [005 §`reg-machine` — public registration surface](005-StateMachines.md#reg-machine--public-registration-surface) for the canonical contract.

The older `reg-event-fx + create-machine-handler` form (visible in [Construction-Prompts.md §CP-5](Construction-Prompts.md#cp-5-scaffold-a-state-machine) examples) registers the *same* slot — `reg-machine` is the convenience surface that wraps it and adds the metadata stamp.

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

v1 ships the **machine-as-event-handler foundation** — `create-machine-handler`, `machine-transition`, the `[:rf.machine/spawn ...]` and `[:rf.machine/destroy ...]` lifecycle fx, the reserved fx-id `:raise` (machine-internal), the `[:rf/machines <id>]` storage scheme, four-level drain, machine-scoped `:guards` / `:actions` declaration with registration-time validation, and the discovery lens (`(rf/machines)` / `(rf/machine-meta id)`).

The grammar this foundation interprets (per [005 §Capability matrix](005-StateMachines.md#capability-matrix)):

- `:initial`, `:data`
- root-only `:guards` and `:actions` maps
- per-state `:on`
- transition shape `{:target :guard :action :meta}` with **single-fn-or-keyword-reference** `:guard` and `:action` slots
- per-state `:entry` / `:exit` as single fns or keyword references
- `:*` wildcard, self-transitions
- the reserved fx-id `:raise` (machine-internal) plus the canonical actor-lifecycle fx-ids `:rf.machine/spawn` / `:rf.machine/destroy` inside the action's returned `:fx`

The snapshot location is fixed at `[:rf/machines <id>]` — no `:path` key in the spec.

Hierarchical compound states, eventless `:always`, delayed `:after`, and declarative `:invoke` are claimed in the v1 capability list (work in progress) — see the matrix for status.

## Substitutes for skipped features

Two features the matrix names as out of pattern scope — **parallel regions** and **history states** — have well-defined substitutes that exploit re-frame2's existing primitives. The substitutes are not workarounds: they are *better* fits for the substrate, given the snapshot-as-value foundation per [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility).

### Parallel regions → separate machines per region

xstate's parallel-region machines model two-or-more independent regions advancing concurrently inside one machine (e.g., a media player with `audio.{playing,paused}` and `video.{visible,hidden}` running side by side). The substrate concern is "atomic, inspectable, composable concurrency."

In re-frame2, the substitute is **one machine per region**, coordinated via cross-actor dispatch. Each region is a separate machine — separate `[:rf/machines <id>]` snapshot, separate transition table, independent inspection. Synchronising events fan out through `:fx [[:dispatch ...]]`.

#### Worked example — media player with audio/video regions

```clojure
;; ---- region 1: audio playback state ---------------------------------------

(rf/reg-event-fx :media/audio
  {:doc "Audio region — playing / paused."}
  (rf/create-machine-handler
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
  (rf/create-machine-handler
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
- **Undo.** Each region's snapshot lives at its own `[:rf/machines <id>]` key; reverting `app-db` rolls both back together.
- **Composability.** A view caring only about audio subscribes to `:media/audio`; video-only views ignore audio entirely. Parallel-region snapshots in xstate force consumers to subscribe to the umbrella machine and project — extra ceremony for the same outcome.
- **Discoverability.** Each region has a name (`:media/audio`) and a registry entry. xstate's regions live anonymously inside the parent machine's transition table.

The cost: a coordinating event (`:media/play`) is one extra registration. In exchange the two regions are independently testable, independently inspectable, and independently rolled back. The substrate's machinery does the heavy lifting at no extra cost.

### History states → snapshot-as-value capture

xstate's history states re-enter a compound state at *the substate that was active when it was last left*. The substrate concern is "remember where the user was."

xstate needs history states because its runtime lacks first-class snapshot-as-value semantics — there's no general way to copy a machine's current state and restore it later. re-frame2 has snapshot-as-value as a foundation: every machine's snapshot at `[:rf/machines <id>]` is a value, and copying / restoring values is what re-frame2's persistent data structures do best. The substitute is **capture on leave, restore on re-enter** — a two-line action pattern over the existing snapshot.

#### Worked sketch — remember-last-substate via snapshot capture

```clojure
;; The flow has a :browsing parent that, when re-entered, should resume at
;; the substate the user was on (e.g., :browsing.cart vs :browsing.search).
;; The two helper actions are declared in the machine's :actions map:

(rf/create-machine-handler
  {:actions
   {:capture-browsing-position
    ;; Stash the current browsing-state into :data so we can restore it later.
    ;; This is an opt-in 3-arity body — the third arg unlocks the :state slot;
    ;; the canonical 2-arity form (fn [data event] ...) only sees :data.
    (fn [_data _event {:keys [state]}]
      {:data {:last-browsing-state state}})                 ;; the FSM keyword

    :restore-browsing-position
    ;; Re-enter at the previously-captured state.
    (fn [data _]
      {:fx [[:raise [:flow/jump-to (:last-browsing-state data)]]]})}
   :states
   {:browsing
    {:exit  :capture-browsing-position
     :entry :restore-browsing-position
     :states {...}}}})

;; The :raise back into the same machine fires before the snapshot commits;
;; the snapshot lands at the restored state in one externally-observable step.
```

What this gives:

- **No new substrate.** The captured state is just a key in `:data`; the restore is a `:raise` (per [Spec 005 §Action effect map](005-StateMachines.md#action-effect-map--data-fx)).
- **Inspectable.** `(:last-browsing-state data)` is visible in the machine's snapshot at all times. Visualisers see "the user was last at `:browsing.cart`" as a normal data field, not as opaque history-state plumbing.
- **Undo / time-travel free.** The captured value rides along with the rest of the snapshot; reverting `app-db` reverts the captured value too.
- **Per-region independent.** Combine with the parallel-region substitute above and each region captures and restores its own position independently — no cross-region history-state coupling.

The cost: explicit code for capture / restore, instead of a one-keyword `{:type :history}` in the transition table. In exchange the captured value is *visible* — a data field rather than xstate-runtime-internal state — and re-frame2's existing snapshot-as-value infrastructure does the rest. The trade is consistent with the spec's broader bias toward "data, in app-db, inspectable" over "runtime mechanism, opaque to users."

#### Why xstate needs history but re-frame2 doesn't

- **xstate** treats machine state as runtime objects — `ActorRef` instances with mailboxes and live observers. There is no general "value of this machine right now" you can copy and restore later, so the runtime ships dedicated history-state machinery.
- **re-frame2** treats machine state as a value at `[:rf/machines <id>]`. Every read is `(get-in db [:rf/machines id])`; every write is `(assoc-in db [:rf/machines id] new-snap)`. Capturing and restoring are *natural ops* on the existing data structure — no dedicated mechanism needed.

The substitute is not a workaround; it is the same answer history states give, expressed in re-frame2's existing primitives. Goal 3 — Frame state revertibility — is what makes this affordable.

## Lessons from xstate (deliberate divergences)

For readers familiar with xstate, the explicit list of where re-frame2 chose differently and why:

| xstate | re-frame2 | Why |
|---|---|---|
| `ActorRef` runtime objects | Snapshots at `[:rf/machines <id>]` in `app-db` | Data orientation; agent-friendliness; no leak footguns |
| Per-actor mailboxes | One per-frame router queue | Simpler model; drain at the frame level is the granularity that matters |
| `raise` (self-event) vs `sendTo` (other-actor) | Single `dispatch`; `:raise` is sugar for self-dispatch with atomic semantics | One pipeline; no per-actor mailbox to put events at the front of |
| Three creation modes (`createActor` / `invoke` / `spawn`) | One mechanism, two patterns (singleton via `reg-event-fx`; dynamic via the `[:rf.machine/spawn ...]` fx) | Lifetime is encoded in `app-db` shape and registration lifetime |
| Machine hierarchy as a structural concept | Hierarchy encoded in `app-db` nested structure | Stay data-oriented; no new framework primitive |
| Event-as-object API | Event vector + envelope metadata | Compatible with re-frame's existing event shape |
| `:context` for extended state | `:data` | Avoid the triply-overloaded "context" name; align with `gen_statem` vocabulary |
| Compound guards as `{and: [...]}` data | One fn or one named registered compound | Imperative composition is fns; named compounds carry semantic content |
| Action-vector `[a1 a2 a3]` per slot | One fn or one named registered compound | Same reason as guards |
| `setup({actors, guards, actions})` per-machine bundle | Per-machine `:guards` / `:actions` maps inside the `create-machine-handler` spec | Convergence: machine-scoped declaration (not globally-registered). Each machine has its own guard/action namespace, validated at registration time; cross-machine reuse is via Clojure vars |
| `[:assign {...}]` action data form | Action returns `{:data {...}}` | Symmetric with `reg-event-fx`'s `{:db :fx}`; one fewer DSL to parse |

Convergences: machines-as-actors, run-to-completion, encapsulated state, snapshots, definition/implementation split, transition tables as data.

## Cross-references

- [Construction-Prompts.md §CP-5](Construction-Prompts.md#cp-5-scaffold-a-state-machine) — the build-facing prompt this file supports.
- [005-StateMachines.md](005-StateMachines.md) — the canonical machine spec.
- [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table) — the transition-table grammar schema.

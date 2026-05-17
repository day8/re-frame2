# O-16. Convert `day8.re-frame/async-flow-fx` flows to `reg-machine`

> **Type B** (semantic rewrite, ask first). The agent identifies every flow and surfaces the proposed machine for operator approval at each call site. Mechanical translation handles the common cases (boot orchestration with `:seen?` / `:seen-all-of?` / `:seen-any-of?` predicates and `:halt?` termination); flows that lean on `:halt-fns?` with predicates closing over dynamic state, dynamic event-shape rules, or runtime-mutated rule sets escalate to a human.

> **Cross-references.** Companion required-rule [M-21](README.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors) drops the core `on-changes` interceptor (the related v1 in-tree primitive whose use-case maps to `reg-flow`); this rule covers the **separate add-on lib** `day8.re-frame/async-flow-fx` (latest 0.4.0) whose use-case maps to `reg-machine`. Required-rule [M-28](README.md#m-28-state-machines-spec-005-ship-in-a-separate-artefact--day8re-frame2-machines) catalogues the machines artefact that ships `reg-machine`; if the migration adopts this rule, the project gains a new dependency on `day8/re-frame2-machines`.

---

## Summary

`day8.re-frame/async-flow-fx` ([repo](https://github.com/day8/re-frame-async-flow-fx)) is a v1-era add-on lib that ships a single fx — `:async-flow` — implementing a rule-engine for orchestrating multi-step asynchronous boot / wizard / init sequences. The engine tracks events as they pass through the router, fires rules whose `:when` predicates have become true, and tears itself down when a rule with `:halt? true` fires.

re-frame2 covers the same use-case with `reg-machine` (per [005-StateMachines.md](../../spec/005-StateMachines.md)): the boot sequence is modelled as an explicit FSM whose `:states` correspond to phases of the flow, whose `:on` maps consume the same HTTP-completion events the async-flow's `:when :events` watched for, and whose `:final?` states correspond to the async-flow's `:halt?` termination. The machine snapshot lives in `app-db` (per Spec 005), so it inherits revertibility, SSR hydration, Tool-Pair time-travel, and trace-stream visibility — none of which the v1 add-on offered.

## Why the rewrite is opt-in

`day8.re-frame/async-flow-fx` is an **add-on lib** with a separate Maven coordinate; nothing in re-frame's core surface depends on it, and nothing in re-frame2 breaks when a project keeps using it. A v1 codebase can in principle:

1. Continue to depend on `day8.re-frame/async-flow-fx` 0.4.0 (the engine itself is built on top of `reg-event-fx`, `reg-fx`, and `register-trace-cb!`-style event observation — every surface it consumes is preserved or has a v2-canonical equivalent).
2. Migrate flow-by-flow to `reg-machine` as part of broader v2 modernisation.

The rule is opt-in (O-rule, not M-rule) because (1) is technically valid. The migration agent does NOT auto-rewrite — every flow is surfaced for operator approval per call site, because the rewrite is semantic (the FSM shape is a re-thinking of the rule-set, not a structural lift).

The agent SHOULD recommend (2) when the codebase is otherwise adopting re-frame2 idioms — machine snapshots in `app-db` integrate with every other v2 surface (trace, epoch, schemas, SSR, 10x / Causa); async-flow's internal atom (or per-flow `:db-path`) is opaque to all of them.

## Detection

The agent looks for:

- Maven coord `day8/re-frame-async-flow-fx` in `deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn` (any version).
- `(:require [day8.re-frame.async-flow-fx :as ...])` in any namespace (the require has no public symbols beyond the fx-registration side-effect; the require alone is enough to indicate adoption).
- `:async-flow` keys inside effect maps returned by `reg-event-fx` handlers — the unmistakable fingerprint. The key may appear at the top level (v1 effect-map shape, pre-M-8) or inside `:fx` (post-M-8 shape `:fx [[:async-flow {...}]]`).

Each call site is one flow. The agent presents the flow's spec, the proposed machine, and the diff for operator approval before any edit.

## async-flow → reg-machine concept mapping

The rule engine and the FSM are structurally different — async-flow is *temporal* (track events through history; fire rules whose `:when` is true) and the FSM is *spatial* (the machine is in one state at a time; transitions are triggered by named events). The mapping below captures how the typical async-flow patterns lower to FSM concepts.

| async-flow concept | reg-machine concept | Notes |
|---|---|---|
| `:id` (flow id) | The `machine-id` arg to `reg-machine` | async-flow's `:id` defaults to a gensym; pick a meaningful keyword for the machine (typically named after the orchestrated workflow — `:app/boot`, `:wizard/checkout`). |
| `:db-path` (engine state location) | Fixed at `[:rf/machines <id>]` per Spec 005 §Where snapshots live | async-flow let the user pick where engine state lived (`:db-path`) or kept it in an internal atom. re-frame2 machines have one home — the reserved `[:rf/machines <id>]` slot. The opacity-vs-snapshot trade-off resolves in favour of the snapshot (revertible, SSR-survivable, tool-readable). |
| `:first-dispatch` | The initial state's `:entry` action emitting the kickoff event via `:fx` | async-flow dispatched `:first-dispatch` synchronously at flow registration. The machine-equivalent is the initial state's `:entry` action returning `{:fx [[:dispatch [:do-the-first-thing]]]}`. The machine's bootstrap-pending mechanism (per Spec 005 §Reserved snapshot-internal keys, `:rf/bootstrap-pending?`) fires the initial `:entry` cascade on the first event addressed to the machine — typically the same kickoff event the parent dispatches to start the flow, or a synthetic `[:rf.machine/bootstrap]` per Spec 005. |
| `:rules` (vector of rule maps) | The machine's `:states` map — one state per phase of the workflow | Each async-flow rule maps to *either* a transition (`{:on {<event> {:target <next-state>}}}`) on the appropriate state, or — for cross-cutting "this event always means X" rules — a wildcard on the current state. The literal rule-list shape does not survive translation; the FSM expresses the same constraints as states + transitions. |
| `:when :seen?` / `:seen-both?` / `:seen-all-of?` (all events seen) | A state whose `:on` map handles each contributing event by recording it in `:data`, with an `:always` guard that transitions when all are present | The async-flow rule "when E1 and E2 both seen, dispatch E3" lowers to a state in `:waiting-for-e1-e2` whose `:on {:e1 {:action :record-e1}, :e2 {:action :record-e2}}` updates `:data`, and whose `:always {:guard :both-seen? :target :both-seen}` advances when both arrive (per Spec 005 §Eventless `:always`). For boot orchestration this is exactly the `:invoke-all` shape (see below) and the recommended path. |
| `:when :seen-any-of?` (any event seen) | A state whose `:on` handles each event with the same transition target | "Any of E1, E2, E3 means failure" lowers to `{:on {:e1 :failure-state :e2 :failure-state :e3 :failure-state}}` on whichever state is watching. For the common case of "any spawned child failed", `:invoke-all` `:on-any-failed` handles it natively. |
| `:dispatch` / `:dispatch-n` (event(s) to fire when rule matches) | The `:action`'s `:fx [[:dispatch ...]]` slot on the transition that fires | Same effect, threaded through the FSM's transition surface so dispatch shows up in trace next to the state change. |
| `:dispatch-fn` (function of matched event → event(s)) | Inline `:fx [[:dispatch (build-event-fn data event)]]` in the transition's `:action` | The fn body moves into the action; it operates on `:data` and the matched event per Spec 005 §Actions. |
| `:halt? true` (rule tears down the flow) | A `:final?` state (per Spec 005 §Final states) | The machine reaching `:final? true` triggers auto-destroy of the machine and its `[:rf/machines <id>]` snapshot. Symmetric with async-flow's deregister-and-cleanup. |
| The implicit "deregister event handler when halted" | Auto-destroy on `:final?` per Spec 005 §Final states | Single-rule pre-cleanup is built into the machine's lifecycle; no user-side teardown code. |
| Parallel-task tracking (one rule per sibling, all converging on a single "all done" rule) | `:invoke-all` per Spec 005 §Spawn-and-join | The async-flow idiom "kick off N HTTP requests, wait for all success events, then advance" is exactly `:invoke-all` with `:join :all` (per Spec 005 §Spawn-and-join via `:invoke-all`). The translation is the highest-payoff part of this rule: the hand-rolled bucket-tracking that async-flow encourages becomes one declarative `:invoke-all` slot, with the `:on-all-complete` / `:on-any-failed` semantics handled by the runtime. |
| `:debug?` (per-flow console logging) | The standard trace stream (per [009](../../spec/009-Instrumentation.md)) | Machine state transitions fire `:rf.machine/transition` trace events that 10x / Causa / `register-trace-cb!`-consumers see for free. No per-machine debug flag needed. |

## Before / after — representative boot orchestration

This is the canonical async-flow shape from the lib's own README, adapted for a typical re-frame v1 app that boots through DB-connect → user-and-prefs-load → ready, with the failure paths flowing to a single error state.

### Before — async-flow-fx

```clojure
(ns my-app.boot
  (:require [re-frame.core :as rf]
            [day8.re-frame.async-flow-fx]))                ;; registers the :async-flow fx

(rf/reg-event-fx
  :app/boot
  (fn [{:keys [db]} _]
    {:db         (assoc db :boot/phase :starting)
     :async-flow {:id             :app/boot-flow
                  :first-dispatch [:db/connect]
                  :rules
                  [;; Once DB is up, fetch user-data AND site-prefs in parallel.
                   {:when     :seen?
                    :events   :db/connect-success
                    :dispatch-n [[:user/fetch] [:site-prefs/fetch]]}

                   ;; Once BOTH succeed, the app is ready.
                   {:when     :seen-all-of?
                    :events   [:user/fetch-success :site-prefs/fetch-success]
                    :dispatch [:app/ready]
                    :halt?    true}

                   ;; If ANY of the three asks fails, the boot fails.
                   {:when     :seen-any-of?
                    :events   [:db/connect-failure
                               :user/fetch-failure
                               :site-prefs/fetch-failure]
                    :dispatch [:app/boot-failed]
                    :halt?    true}]}}))
```

### After — `reg-machine`

```clojure
(ns my-app.boot
  (:require [re-frame.core :as rf]
            [re-frame.machines]))                          ;; per M-28 — require fires the machines artefact's load-time hooks

(rf/reg-machine :app/boot
  {:initial :starting
   :states
   {:starting
    {:entry  (fn [_data _ev] {:fx [[:dispatch [:db/connect]]]})
     :on     {:db/connect-success :loading-user-and-prefs
              :db/connect-failure :failed}}

    :loading-user-and-prefs
    {:invoke-all
     {:children        [{:id :user        :machine-id :user/fetcher}
                        {:id :site-prefs  :machine-id :site-prefs/fetcher}]
      :join            :all
      :on-all-complete [:user-and-prefs-loaded]
      :on-any-failed   [:user-or-prefs-failed]}
     :on {:user-and-prefs-loaded  :ready
          :user-or-prefs-failed   :failed}}

    :ready  {:final? true
             :entry  (fn [_data _ev] {:fx [[:dispatch [:app/ready]]]})}

    :failed {:final? true
             :entry  (fn [_data _ev] {:fx [[:dispatch [:app/boot-failed]]]})}}})

;; Kick the machine off from the app's entry point:
(rf/dispatch [:app/boot [:start]])
```

What changed:

- **The `:first-dispatch` becomes the initial state's `:entry`.** `:starting`'s `:entry` action dispatches `[:db/connect]` — the kickoff is explicit, addressable, and runs through the standard fx pipeline.
- **The "both succeeded" rule becomes `:invoke-all` with `:join :all`.** The two parallel HTTP-completion events that async-flow tracked via `:seen-all-of?` are owned by two child fetcher machines; the runtime's join-bookkeeping handles "all succeeded" and "any failed" natively (per Spec 005 §Spawn-and-join via `:invoke-all`). The result is one declarative slot instead of three correlated rules.
- **`:halt?` becomes `:final?`.** Two terminal states (`:ready`, `:failed`) sit at the bottom of the FSM; reaching either fires auto-destroy and clears `[:rf/machines :app/boot]` from `app-db`.
- **The fetchers (`:user/fetcher`, `:site-prefs/fetcher`) are themselves small machines.** In the async-flow shape, the user is responsible for the actual HTTP — the flow only observes the success/failure events the user's `reg-event-fx` dispatches. In the machine shape, each fetcher is a `reg-machine` whose `:final?` state's `:output-key` reports the loaded payload back to the parent via `:on-all-complete`. The agent surfaces this as a follow-on rewrite — each fetcher is a separate per-flow decision.

## Mapping notes for each async-flow concept

### `:first-dispatch`

- **Default path.** Move the dispatched event into the initial state's `:entry` action: `{:entry (fn [_data _ev] {:fx [[:dispatch <event-vec>]]})}`. The machine bootstraps on its first received event; the parent dispatches that event (often the kickoff itself, e.g. `(rf/dispatch [:app/boot [:start]])`).
- **If the flow's `:first-dispatch` is conditional on cofx or app-db at boot time** (uncommon but possible — e.g. "if user is authenticated, dispatch X; otherwise Y"): hoist the condition into the parent event that calls `rf/dispatch` to spawn / start the machine. The machine's `:entry` should be deterministic given the spec.
- **If `:first-dispatch` is omitted** in the v1 flow (the flow starts when an external event arrives that matches one of its rules): the machine's initial state's `:entry` is a no-op (`{:entry nil}` or omitted); the first transition fires when the awaited event arrives.

### `:id`

Pick a meaningful keyword. async-flow's `:id` was a gensym by default and rarely surfaced; the machine's id is the addressing primitive (events are dispatched as `[<machine-id> <event-vec>]`; the snapshot lives at `[:rf/machines <machine-id>]`; trace events are tagged with it). Use the feature-prefix convention (e.g. `:app/boot`, `:wizard/checkout`, `:onboarding/profile-setup`).

### `:db-path`

Drop. Machine snapshots are not optional-location; they live at `[:rf/machines <id>]` (per Spec 005 §Where snapshots live). The trade-off is favourable: the snapshot is now part of `app-db`, so it walks back with revertibility, ships through SSR hydration, appears in trace, and is readable by Tool-Pair, 10x, and Causa without per-machine wiring.

### `:rules` with multi-event `:when` predicates

The translation is one rule at a time. For each rule:

1. **Identify which machine state the rule's predicate becomes meaningful in.** A rule that watches for `:auth/done` is meaningful while the machine is in a state that's *waiting for authentication*; it doesn't apply once the machine has advanced past that.
2. **Place the transition on that state's `:on` map.** Single-event rules become `{<event> {:target <next-state>}}`.
3. **For `:seen-all-of?` / `:seen-both?`** with N events that must all be seen: the canonical shape is `:invoke-all` with `:join :all` (each contributing async work is a child machine, the parent waits for all). If converting the contributing work to child machines is not feasible (e.g. the events come from external sources outside the project's control), use the fallback: a state whose `:on` records each event in `:data` and whose `:always {:guard :all-seen? :target <next-state>}` checks completion (per Spec 005 §Eventless `:always`).
4. **For `:seen-any-of?`** with N events any-of which triggers: list each event in the state's `:on` map with the same `:target`. For the common case "any child failed", use `:invoke-all`'s `:on-any-failed`.
5. **For rules with `:dispatch-fn`** (matched event → derived event): the fn body moves into the transition's `:action`, returning `{:fx [[:dispatch <derived>]]}`.

### `:halt?`

`:halt? true` rules become `:final?` states. The machine auto-destroys on entering a `:final?` state (per Spec 005 §Final states); the side effects async-flow ran (deregister handler, clear `:db-path` state, stop event observation) all happen as part of the runtime's auto-cleanup. If the halt rule dispatched an event (e.g. `:dispatch [:app/ready]`), put the dispatch in the `:final?` state's `:entry` action — the action runs once on entry, before auto-destroy.

### `:halt-fns?`

This is the **primary escalation surface**. async-flow's `:halt-fns?` slot accepts a predicate fn that closes over the engine's seen-event history and arbitrary state; the rule fires (and the flow halts) when the predicate returns true. The pattern enables stop-conditions that no static `:when` predicate can express — e.g. "halt when the seen-event count for this URL pattern exceeds 5", "halt when the sum of event payload `:n` fields crosses a threshold", "halt iff the user has manually clicked cancel during the flow".

The machine equivalent depends on what the predicate closes over:

- **Closes over `:data` only** (e.g. "after 5 retries"): lower to a `:guard` on an `:always` transition — `:guard :retries-exhausted?` reading `(:retry-count data)`. The retry-count is updated by the `:action` on each retry-failure transition.
- **Closes over external state** (e.g. a sub-table, a websocket message buffer, an app-db slice outside the machine's `:data`): this is the **hard case**. Spec 005 §Strict encapsulation locks actions and guards to `:data` only — they cannot read `app-db` outside `[:rf/machines <id>]`. The migration paths are: (a) restructure so the external state arrives via dispatched events the machine consumes through `:on`, so the relevant signal becomes part of `:data`; (b) use the 3-arity opt-in escape hatch (per Spec 005 §3-arity escape hatch) to read the snapshot's `:meta` (still not arbitrary `app-db`); (c) **escalate** — the encapsulation rule is load-bearing for revertibility and the rewrite is a design conversation, not a mechanical lift.

The agent surfaces every `:halt-fns?` site and explains the three paths; the operator decides.

### `:debug?`

Drop. Machine trace events (`:rf.machine/transition`, `:rf.machine/event-received`, `:rf.machine/raised`, lifecycle events) flow through the standard trace surface per [009](../../spec/009-Instrumentation.md). 10x, Causa, and bespoke `register-trace-cb!` listeners see them without per-machine opt-in. If the user wanted console logging specifically, attach a `register-trace-cb!` filtered on `:operation #{:rf.machine/transition}` and the machine's id.

## Explicit escalation cases — the agent surfaces and stops

The migration agent does NOT silently rewrite the following. It presents the call site, the reason for escalation, and waits for operator direction:

1. **`:halt-fns?` predicates closing over state outside `:data`.** See the `:halt-fns?` section above. Strict encapsulation makes this a design decision, not a mechanical translation.

2. **Rules with `:events` as a predicate fn** (not a keyword / vector / collection of keywords). async-flow accepts `:events` as a predicate that runs against each observed event. The machine's `:on` map is keyword-indexed; arbitrary-predicate event matching has no direct equivalent. Escalation paths: (a) restructure the upstream dispatches so the events carry distinguishing ids; (b) use a wildcard `:on {:* {:action <fn-that-pattern-matches-and-may-or-may-not-transition>}}` (per Spec 005 §Wildcard transitions) — works for catch-all behaviour but reads less clearly than named transitions; (c) keep the v1 add-on lib for this specific flow.

3. **Flows whose `:rules` vector is computed at runtime** (e.g. `(into base-rules (when feature-flag? extra-rules))`). The machine spec is declarative and stamped at registration time. Conditional behaviour belongs inside the spec (`:guard` predicates that read `:data`, or `:always` transitions that branch on state). A computed `:rules` vector either lowers to one machine with branching guards (preferred) or to multiple `reg-machine` calls behind a runtime selector (rare; escalate).

4. **Flows that mutate the rule-set via re-dispatched `:async-flow`** (re-issuing the fx with a new flow spec, replacing the engine state mid-flight). The machine equivalent is `reg-machine` re-registration, which replaces the handler but preserves the snapshot (per Spec 005 §Hot-reload semantics). If the v1 flow relied on the rule-set actually changing mid-run, the design needs reconsideration before mechanical translation can proceed.

5. **Flows whose `:db-path` is read by other code** (i.e. some other event handler / sub looks at the engine's tracking state to make decisions). `[:rf/machines <id>]` is a different location and a different shape (`{:state ... :data ...}` instead of `{:seen-events ... :rules-fired ...}`); the reading code must be rewritten to consume the snapshot, typically via `(rf/sub-machine <id>)`. Escalate so the operator can locate every reader.

6. **Flows with no clear FSM modelling.** Some async-flows are essentially a flat list of "when E then dispatch F" cross-cutting rules with no notion of phase or state. These are better expressed as ordinary `reg-event-fx` handlers (one per E → F rule) than as a machine. The agent surfaces these and suggests the plainer rewrite; the operator confirms.

## Out of scope

- **`day8.re-frame/async-flow-fx` itself does not ship under a new coordinate in re-frame2.** There is no `day8/re-frame2-async-flow-fx` artefact. Operators who want to keep using the v1 add-on continue depending on `day8/re-frame-async-flow-fx` 0.4.0 as before; the fx surface it consumes (`reg-event-fx`, `reg-fx`, etc.) is preserved.

- **The migration agent does not auto-detect "the right machine shape" from the rule-set.** Determining whether N rules are best expressed as N states, as `:invoke-all` children, or as `:always` guards is a design call the operator owns. The agent presents the rule-set and the candidate translation; the operator approves, edits, or skips.

- **Migrating fetcher events into child machines** (the `:user/fetcher` / `:site-prefs/fetcher` shapes in the worked example) is a separate rewrite per fetcher, surfaced as follow-on rules. The most common path uses the [`:rf.http/managed`](../../spec/014-HTTPRequests.md) fx wrapped in a small `reg-machine` per fetcher, but the wrapping is design work — escalate per call site.

- **Tooling that auto-detects async-flow-fx call sites** is filed as a separate follow-on bead. This rule is the spec text the migration agent reads; a future MCP tool can drive the per-call-site conversion against this spec.

## Reporting

When the agent applies this rule:

- The migration report lists every `:async-flow` call site it found, whether the operator approved the rewrite, and the new machine id.
- If the `day8.re-frame/async-flow-fx` dep is no longer referenced (all flows migrated), the agent flags the dep for removal in the same report; the operator confirms before the dep is dropped.
- Each escalation case from above is listed with file/line, the specific reason it escalated, and the agent's recommended path forward.

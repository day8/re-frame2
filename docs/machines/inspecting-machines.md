# Inspecting and testing machines

A machine in re-frame2 is [just an event handler](concepts.md#registering-and-running-it) whose body interprets a transition table, and whose whole state is a plain value sitting in the frame's runtime-db. That one fact is the theme of this page: there is **no parallel machine runtime** to attach a special debugger to, and **no second store** to mock in a test. You inspect a machine the way you inspect any re-frame2 state — you `subscribe` to it. You watch it the way you watch any event — through Xray and the trace bus. And you test it the way you'd test a pure function — because a transition literally *is* one.

You reach for the tools here in two situations:

- **A flow misbehaves and you need to see *why*.** Which transition fired? Did the guard pass? What did the action write? *Watch* the machine: open Xray's Machine Inspector and read the transition, or drop to the trace stream for the exact guard/action records.
- **You want fast, headless confidence the table is correct.** *Test* the machine: feed a snapshot and an event to `machine-transition` and assert on what comes back — no frame, no browser, no network, microseconds per case on the JVM.

Four surfaces, building from "read the value" to "prove the logic":

| Surface | Question it answers | Tool |
|---|---|---|
| `[:rf/machine id]` subscription | *What state is it in right now?* | `rf/subscribe` |
| Xray Machine Inspector | *What did this event do to the machine?* / *What's the chart?* | Xray (Dynamic + Static) |
| `:rf.machine/*` trace events + `handler-meta` | *Did this guard pass? Where is that action defined?* | trace bus / source-jump |
| `machine-transition` | *Is the table correct?* (headless test) | pure function call |

---

## Read the live snapshot — `[:rf/machine id]`

The canonical window onto a machine is the framework-registered subscription `[:rf/machine machine-id]`. It returns the [snapshot](glossary.md#snapshot) — the whole `{:state :data :tags}` value — and you subscribe to it exactly like anything else:

```clojure
(let [{:keys [state data tags]} @(rf/subscribe [:rf/machine :auth.login/flow])]
  [:div
   [:p "state: " (name state)]            ;; the discrete FSM keyword, e.g. :submitting
   [:p "attempts: " (:attempts data)]     ;; :data — the machine's private memory
   (when (contains? tags :auth/busy)      ;; :tags — union of active states' tags
     [spinner])])
```

The three keys are the whole user-facing shape:

- **`:state`** — the discrete FSM keyword (`:idle`, `:submitting`, …). For a hierarchical machine it's a path vector (`[:authenticated :cart :browsing]`); for a parallel machine, a region map. This is what XState calls `state.value`.
- **`:data`** — the machine's extended state: a plain map, distinct from app-db. XState calls this slot `context`; re-frame2 calls it `:data` to avoid the already-overloaded word "context". Read individual fields by destructuring, never `(get-in snapshot [:data …])` from inside a callback.
- **`:tags`** — the runtime-projected union of every active state's `:tags`. Ask membership questions against it rather than hard-coding state names (see below).

> **The snapshot is `nil` until the first event.** A machine bootstraps itself on the first event addressed to it, so `[:rf/machine id]` reads `nil` before then. A view that renders pre-bootstrap should fall back to the definition's `:initial`:
>
> ```clojure
> (or @(rf/subscribe [:rf/machine :turnstile/flow])
>     {:state (:initial turnstile) :data (:data turnstile)})
> ```

### Named projections off the snapshot

`[:rf/machine id]` is the raw value; chain ordinary `reg-sub`s off it to pluck the pieces a view actually wants. This keeps views reading small, named slices instead of re-destructuring the whole snapshot:

```clojure
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (:state machine)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [machine _] (get-in machine [:data :error])))
```

A sub that reads a machine this way is an ordinary derivation node — it does not become a "second kind" of subscription.

### Ask by tag, not by state name

Once a machine has several "loading-ish" states, views stop asking *which exact state?* and start asking a predicate — *is it busy?* That's what [state tags](glossary.md#state-tag) are for. The framework ships a derived predicate sub, and `rf/machine-has-tag?` is sugar over it:

```clojure
;; both equivalent — the sugar form reads best in a view
(when @(rf/subscribe [:rf/machine-has-tag? :auth.login/flow :auth/busy]) [spinner])
(when @(rf/machine-has-tag? :auth.login/flow :auth/busy)                  [spinner])
```

This sub re-renders only when *this* tag's membership bit flips, so adding a fifth busy state later is one `:tags` entry on the new node — zero view changes.

> **Coming from XState.** There you'd call `actor.getSnapshot()` on a live actor object. In re-frame2 there is no actor object — the snapshot is a value living at `[:rf.runtime/machines :snapshots :auth.login/flow]` in runtime-db, read through the ordinary subscription graph. The payoff is that time-travel, undo, persistence, and SSR hydration all extend to machines *for free*, because the snapshot rides the frame like any other state.

See [`[:rf/machine machine-id]`](../api/re-frame.machines.md#rfmachine-machine-id) and [`machine-has-tag?`](../api/re-frame.machines.md) in the API reference.

---

## Watch a transition happen — the Xray Machine Inspector

Your bug is usually not a value; it's a *process* — the wrong transition fired, or the right one didn't. [Xray](../xray/index.md)'s Machine Inspector reads that process. It has two modes, and the trick is knowing which question each answers.

**Dynamic Machine — the transition history.** This tab is *event-coupled*: click an event row in the spine, and it shows what that one epoch did to a machine — the affected machine, the active state **before and after**, the transition that fired, the guards and actions that ran, entry/exit activity, any spawned or destroyed actors, and `:after` timers and their cancellations. It is the black-box recorder for one transition. If the focused epoch touched no machine, the tab stays quiet — that's honest scope, not a failure. Stepping through the epoch spine (with [time-travel](../xray/03-time-travel.md)) replays the machine's history one transition at a time.

**Static Machines — the state-chart on the wall.** This tab is *event-independent*: browse every registered machine id, inspect its topology, read its states and transitions, and compare the definition's shape to the live snapshot. Reach for it when you need the map rather than a single step.

A reliable machine-debugging loop:

1. Reproduce the action.
2. Click the event row in the spine.
3. Open **Dynamic Machine** and read the transition before → after.
4. If the transition is surprising, flip to **Static Machines** and inspect the definition.
5. Drop to the **Epoch** or **Trace** tab for the exact guard/action records.

That loop keeps you out of the commonest machine trap: staring at the *current* state while forgetting the *event* that moved it there. Because a machine has a small, named state space, Xray can say something a generic logger never could — not "something updated a map" but "this event moved `:door/main` from `:closed` to `:opening`, ran this guard, scheduled this timer, and cancelled that prior branch." That's the difference between seeing state and seeing behavior.

> **Coming from XState.** Stately's inspector and `@statelyai/inspect` attach an observer to a running actor. Xray's Machine Inspector is the re-frame2 counterpart — but it reads the **same trace bus every event handler already uses**, not a machine-special channel. (A note on scope: there is *no* framework-level `machine->xstate-json` or Mermaid export — visualisation exporters live in the separate `re-frame2-machines-viz` library, not the framework.)

The full tour is in [Xray — Machine Inspector](../xray/08-machine-inspector.md).

---

## The trace events a machine emits

Because a machine is an event handler, every guard evaluation, action run, and transition flows through the standard [trace bus](../core/concepts/observability.md) — the same one Xray renders. There's no separate event log to consult. Three machine-specific records (all under the reserved `:rf.machine/*` namespace) are worth recognising:

```clojure
;; one trace record per user-declared guard call site
{:operation :rf.machine/guard-evaluated
 :tags {:actor-id :auth.login/flow             ;; the LIVE instance (a singleton's id == its type)
        :guard-id :under-retry-limit
        :input    {:data {:attempts 3} :event [:auth.login/failure {…}]}
        :state    :submitting                  ;; the active state the guard ran against
        :outcome  :fail}}                       ;; :pass | :fail | :threw

;; one trace record per user-declared action invocation
{:operation :rf.machine/action-ran
 :tags {:actor-id  :auth.login/flow
        :action-id :issue-request
        :phase     :entry                       ;; :exit | :transition | :entry | :always | …
        :input     {:data {…} :event [:auth.login/submit {…}]}
        :outcome   :ok}}                         ;; the return value, or :ok, or :rf.error/action-threw
```

A few things to read off these:

- **`:actor-id` is the live instance; `:machine-id` is reserved for the registered *type*.** For a singleton the two coincide; for a [spawned](glossary.md#spawn) actor `:actor-id` is the gensym'd instance id.
- **`:state` on the guard trace is the source discriminator.** Two states declaring the same event with the same guard are otherwise indistinguishable; the active `:state` tag attributes a block to the right edge.
- **`:phase` on the action trace** tells cascade actions apart from transition actions — `:exit` / `:entry` for the LCA cascade, `:transition` for an `:on`-driven action, `:always` for an eventless step, and so on.

The headline `:rf.machine/transition` trace additionally carries a structured **`:cascade`** field — the ordered exit → action → entry (+ initial-descent) step sequence that explains *how* the transition reached its after-state. That single field is what powers Xray's before → after rendering, so you rarely need to re-walk the geometry by hand.

You can tap the stream yourself; Xray is just the richest consumer of it:

```clojure
;; dev-only: print every machine transition the runtime emits (DCE'd in production)
(rf/register-listener! :trace :my-app/machine-tap
  (fn [trace-event]
    (when (= :rf.machine/transition (:operation trace-event))
      (js/console.log (pr-str (:tags trace-event))))))
```

The [Trace tab](../xray/04-trace-stream.md) is the UI over exactly this feed.

### Jump from a snapshot to the guard/action source — `handler-meta`

When you've found the guard that blocked a transition and want to *read it*, ask `handler-meta`. Machine guards and actions aren't a registry kind of their own — their dev-only source is derived on demand from the machine's `:event` registration spec — but the addressing is uniform with every other handler kind, keyed by a `[machine-id id]` pair:

```clojure
(rf/handler-meta :machine-guard [:auth.login/flow :under-retry-limit])
;; => {:rf/guard-id        :under-retry-limit
;;     :rf/machine-id      :auth.login/flow
;;     :rf.handler/source  "(fn [{data :data}] (< (:attempts data) 3))"
;;     :handler-fn         #<fn>
;;     :ns … :line … :file …}

(rf/handler-meta :machine-action [:auth.login/flow :issue-request])
;; => {:rf/action-id :issue-request :rf/machine-id :auth.login/flow
;;     :rf.handler/source "(fn [{[_ creds] :event}] {:fx [[:rf.http/managed …]]})" …}
```

This is the surface Xray's focused-transition lens and the re-frame2 pair MCP use to render guard/action source inline under their declared id. (`reg-machine` captures that source at macro-expansion time; the `:source`-bearing slots are dev-only and elide under `:advanced` + `goog.DEBUG=false`.) See [`handler-meta`](../api/re-frame.core.md#handler-meta) for the general contract.

> **Divergence the spec calls out — a guard that throws aborts the macrostep.** This matches XState (which throws on a guard exception): re-frame2 emits one `:rf.machine/guard-evaluated` with `:outcome :threw`, then aborts transition selection — no candidate falls through to a sibling, no transition fires, and the snapshot rolls back **atomically** (nothing reaches runtime-db). A thrown *action* and a runaway `:always`/`:raise` depth-abort converge on the same failed-macrostep semantics. The runtime does not silently demote a thrown guard to a lower-priority candidate — and neither does XState.

---

## Unit-test transitions as pure function calls — `machine-transition`

This is where machines pay you back hardest. A transition is a pure function of *(definition, snapshot, event)*, exposed as `re-frame.machines/machine-transition`. No frame, no browser, no mocks, no clock — table in, snapshot in, event in; result out. These tests run on the JVM in microseconds, which is exactly the testing experience you want for the flows where testing usually gets *hard*.

The return value is a **Result map**. Discriminate it with `result/ok?` / `result/fail?`, and read the post-transition snapshot and emitted effects with the `result/snap` / `result/fx` accessors (or destructure the `::result/snap` / `::result/fx` keys directly):

```clojure
(ns my-app.login-flow-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [my-app.login :refer [login-flow]]))   ;; the transition-table value

(deftest login-flow-test
  ;; happy path: :idle --submit--> :submitting, whose :entry fires the request fx
  (let [r (machines/machine-transition
            login-flow
            {:state :idle :data {:attempts 0 :error nil}}
            [:auth.login/submit {:email "a@b.com" :password "secret"}])]
    (is (result/ok? r))
    (is (= :submitting (:state (result/snap r))))
    (is (= :rf.http/managed (ffirst (result/fx r)))))   ;; the :issue-request action's fx

  ;; at the retry limit the guard rejects :error-shown, so :locked-out wins
  (let [r (machines/machine-transition
            login-flow
            {:state :submitting :data {:attempts 3 :error nil}}
            [:auth.login/failure {:failure {:message "bad creds"}}])]
    (is (= :locked-out (:state (result/snap r))))))
```

Two things make this pleasant:

- **Effects are asserted as data, not intercepted.** `(result/fx r)` is the ordinary re-frame `:fx` vector the action returned — you check its *shape* (`(ffirst …)` is `:rf.http/managed`) without ever performing the request. The action describes an effect; the test reads the description.
- **A throwing action is a *failure value*, not an exception out of your test.** A guard or action that throws yields `(result/fail? r)` with `(result/info r)` carrying the diagnostic, so one assertion style covers both the happy and the broken path. (This is the pure-surface face of the atomic-rollback semantics above.)

If the machine is already registered and you want to drive its *registered* definition rather than the raw value, pull it back with `machine-meta`:

```clojure
(machines/machine-transition (machines/machine-meta :auth.login/flow) snapshot event)
```

### The same value runs in the browser *and* the JVM

The `login-flow` table above is plain data, so the very same value that renders a live Reagent form also unit-tests headless. The trick is a `.cljc` source file: it compiles under shadow-cljs for the browser demo *and* loads on the JVM for these tests. The worked example at [`examples/capabilities/machines/state_machine_walkthrough/`](../../examples/capabilities/machines/state_machine_walkthrough/) does exactly this — one table, two lives — and its headless scenarios run on every CI pass.

### Three test levels

`machine-transition` is the fastest of three levels that fall out of the machine being a pure factory — no new test primitive needed:

| Level | What you test | Speed | Can't reach |
|---|---|---|---|
| **1 — `machine-transition`** | FSM logic, guards, action effect *shapes* | fastest | snapshot/db plumbing, fx integration |
| **2 — unregistered handler fn** | handler-level wiring, `:data`-to-`:db` lowering | fast | dispatch pipeline, spawn lifecycle |
| **3 — registered in a test frame** | full integration: trace events, drain, spawn/destroy, cross-actor messaging | slowest | nothing |

Most logic lives at Level 1. Reach for Level 3 only for spawned-actor patterns, where the whole point is that a handler gets registered dynamically and the parent can `dispatch` to it. The contracts for all three are in [Spec 005 §Testing](../../spec/005-StateMachines.md).

> **Coming from XState.** Driving an XState machine in a test means standing up the interpreter (`createActor(machine).start()`, then `actor.send(…)`) or reaching for `@xstate/test`'s model-based harness. re-frame2's `machine-transition` needs neither — it's a bare pure function, so the unit test *is* `(fn definition snapshot event)`. The behavioural contract is identical: *(state, event) → next state + effects*; only the actor object disappears.

See [`machine-transition`](../api/re-frame.machines.md#re-framemachinesmachine-transition) and [`machine-meta`](../api/re-frame.machines.md#re-framemachinesmachine-meta) in the API reference, and the narrative in [Concepts §Testing](concepts.md#testing-transitions-are-pure-function-calls).

---

## Further reading

- **Normative spec** — [Spec 005 — State Machines](../../spec/005-StateMachines.md): §Testing (the three-level pyramid and the pure transition contract), §Trace events — guard evaluations and action runs, and §`:machine-guard` / `:machine-action` handler-meta surfaces are the authoritative source for everything on this page.
- **Sibling pages** — [Concepts](concepts.md) for the transition table and the recognition kit, [Glossary](glossary.md) for the surface vocabulary, [Coming from XState](coming-from-xstate.md) for the full parity delta, and [`re-frame.machines` API](../api/re-frame.machines.md) for the exhaustive function-by-function contract.
- **The statechart lineage** — these features realise the broader statechart model: [XState statecharts docs](https://stately.ai/docs/state-machines-and-statecharts) is the behavioural-parity reference (re-frame2 matches XState's *behaviour*, not its API), and [Fulcro statecharts](https://fulcrologic.github.io/statecharts/) is the other prominent Clojure realisation of the same SCXML-derived ideas.

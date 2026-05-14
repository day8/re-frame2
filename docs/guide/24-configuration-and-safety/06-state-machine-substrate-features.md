# 24.06 — State-machine substrate features

## TL;DR

[Chapter 08](../08-state-machines.md) names four state-node keys in passing: `:always`, `:after`, `:invoke`, `:invoke-all`. Plus `:final?` / `:on-done` / `:output-key` for terminal states. Plus parallel regions. This page is the worked-example tour. Each section answers one question: "what does this key let me write, and what does the framework do for me?"

Read this when you've outgrown a flat-FSM machine and your dynamic model wants to express something xstate-shaped. The substrate has the capability; the guide should have the worked example. None of these keys is exotic — they're all sugar for things you could express by hand. The sugar earns its keep because the desugared shape is verbose, the patterns are mechanical, and stamping them by name lets tooling reason about your machine the way a flat FSM is reasoned about.

## The four substrate keys at a glance

| Key | What it does | Sugar for |
|---|---|---|
| `:always` | Eventless transition — fires when its guard becomes true | A `:raise` of a synthetic event from every action that could enable the condition |
| `:after` | Delayed transition — fires after a wall-clock delay | `:dispatch-later` + epoch-stamped synthetic event + stale check |
| `:invoke` | Declarative actor — spawn-on-entry, destroy-on-exit | `:rf.machine/spawn` in `:entry`, `:rf.machine/destroy` in `:exit` |
| `:invoke-all` | Spawn N actors in parallel, join on a condition | N `:invoke`s + join-state bookkeeping + per-condition resolution |

Each key is *declarative* — the runtime walks the spec at registration time and rewrites it into the underlying primitive. The runtime sees the desugared form; tooling sees the original spec; you write whichever is more readable.

## `:always` — eventless transitions

> "After this snapshot just changed, if condition X is true, immediately go to state Y."

```clojure
{:checking-form
 {:always [{:guard :form-valid?   :target :submitting}
           {:guard :form-invalid? :target :show-errors}]
  :on     {:edit {:action :merge-edits}}}}
```

When the machine lands in `:checking-form` (by any path), the runtime checks each `:always` entry in order, first-match-wins. If `:form-valid?` returns true, the machine transitions to `:submitting` immediately. If `:form-invalid?` returns true, it goes to `:show-errors`. If neither matches, the machine stays in `:checking-form` and waits for `:edit` to do something interesting.

The key property: **the externally-observable transition is the fixed point of the `:always` loop.** External observers (subs, other machines, tools) see only the post-cascade settled state. The intermediate "we entered `:checking-form` for one tick" is invisible. That's xstate / SCXML *macrostep* semantics.

### The shape

`:always` is a vector of guarded transition specs. Each entry has `:guard` (a keyword resolving against the machine's `:guards` map) and `:target` (the destination state). Optional `:action` runs as part of the transition.

```clojure
(rf/reg-machine :form-flow
  {:initial :editing
   :guards  {:form-valid?   (fn [data _] (every? string? (vals (:fields data))))
             :form-invalid? (fn [data _] (some empty? (vals (:fields data))))}
   :states
   {:editing       {:on {:check :checking-form}}
    :checking-form {:always [{:guard :form-valid?   :target :submitting}
                              {:guard :form-invalid? :target :show-errors}]}
    :show-errors   {:on {:edit :editing}}
    :submitting    {:on {:done :complete}}
    :complete      {}}})
```

### Microstep depth limit

The `:always` loop has a depth ceiling — default **16**, configurable per-machine via `:always-depth-limit`. A guard that's always true would loop forever; the ceiling makes that into a structured failure rather than a freeze:

```clojure
:rf.error/machine-always-depth-exceeded
;; with :tags {:machine-id <id> :depth 16 :path [<state> <state> <state> ...]}
```

The cascade halts with the snapshot **uncommitted**; observers don't see the partial path. Same atomic-rollback shape as the outer drain ([§04](04-drain-depth-and-error-recovery.md)).

### Self-loops rejected at registration

A `:always` targeting its own state with the same `:guard` is a registration-time error:

```clojure
;; REJECTED — :rf.error/machine-always-self-loop
{:checking-form
 {:always [{:guard :form-valid? :target :checking-form}]}}
```

Rationale: the loop either fires repeatedly to depth-exceeded (if the guard stays true) or is a no-op (if the guard flips on first hit). In both cases the author intended something else. Catch the typo at registration.

A self-targeting `:always` with a *different* guard — used as a re-entry on a changed condition — is permitted. Only the same-guard same-target case is rejected.

For the full normative grammar see [005 §Eventless `:always` transitions](../../../spec/005-StateMachines.md).

## `:after` — delayed transitions

> "If the machine is still in this state N milliseconds from now, move to state Y."

```clojure
{:splash
 {:after {3000 :main                              ;; show splash for 3 seconds
          5000 {:guard :network-slow? :target :slow-warning}
          30000 :hard-timeout}
  :on    {:user-clicked-skip :main}}}
```

Three timers run concurrently from the moment the machine enters `:splash`. Whichever timer fires first **and** matches its guard (if any) triggers its transition. The state's exit cancels any sibling timers — they go stale and silently drop on their eventual firing.

This is the canonical primitive for splash screens, polling, slow-connection nudges, soft and hard timeouts, animation gates — anything where "time elapsed in this state" is itself the signal.

### The shape

`:after` is a map from delay (milliseconds) to either:

- a target state keyword (`3000 :main` — fire unconditionally after 3 s),
- a transition spec map (`5000 {:guard :network-slow? :target :slow-warning}` — fire after 5 s if guard passes, else suppress and let other timers continue).

```clojure
(rf/reg-machine :load-flow
  {:initial :loading
   :guards  {:still-loading? (fn [data _] (not (:result data)))}
   :states
   {:loading {:after {30000 {:guard :still-loading? :target :hard-error}}
              :on    {:loaded :ready
                      :failed :error}}
    :ready   {}
    :error   {}
    :hard-error {}}})
```

If `:loaded` arrives before 30 s, the machine transitions to `:ready` and the timer cancels. If 30 s elapse, the timer fires; the guard checks whether we're still loading; if so the machine transitions to `:hard-error`.

### Stale timers + epoch-based cancellation

You don't need to write cancellation logic. The framework uses an **epoch counter** stamped into the machine's `:data` to detect stale timers — every state exit increments the counter, every scheduled timer carries the epoch at scheduling time, and the receiving handler validates the carried epoch against the current one. A mismatch silently drops the timer and emits `:rf.machine.timer/stale-after` for observability.

The pattern is general: any async-shaped feature that re-enters a state can use epoch-based stale detection rather than imperative cancel APIs. See [Pattern-StaleDetection.md](../../../spec/Pattern-StaleDetection.md) for the cross-cutting form; routing's navigation tokens ([ch.17a](../17a-routing-reference.md)) use the same shape.

### SSR no-op

`:after` no-ops in SSR mode. Entry actions don't schedule timers; the synthetic timer-elapsed event is never emitted. The server renders the current `:state` statically; the client hydrates that state and timers begin from hydration. This is the same kind of substrate-aware behaviour as `:rf.http/managed` — the framework picks the right thing per platform.

For the full grammar see [005 §Delayed `:after` transitions](../../../spec/005-StateMachines.md).

## `:invoke` — declarative child actors

> "While in this state, run a child machine. When we leave the state, destroy it."

```clojure
{:fetching
 {:invoke {:machine-id :http/protocol
           :data       {:url "/api/profile"}
           :on-spawn   (fn [data id] (assoc data :pending id))
           :start      [:begin]}
  :on     {:succeeded :loaded
           :failed    :error}}}
```

Entering `:fetching` spawns a `:http/protocol` actor; leaving `:fetching` destroys it. The child's lifetime is bound to the parent state's occupancy. If you write the spawn-and-destroy by hand, it looks like:

```clojure
;; what create-machine-handler desugars the :invoke into:
{:fetching
 {:entry (fn [data _]
           {:fx [[:rf.machine/spawn {:machine-id :http/protocol
                                     :data       {:url "/api/profile"}
                                     :on-spawn   (fn [d id] (assoc d :pending id))
                                     :start      [:begin]
                                     :rf/parent-id <this-machine>
                                     :rf/invoke-id [:fetching]}]]})
  :exit  (fn [_ _]
           {:fx [[:rf.machine/destroy {:rf/parent-id <this-machine>
                                       :rf/invoke-id [:fetching]}]]})
  :on    {:succeeded :loaded
          :failed    :error}}}
```

The runtime sees the second form; you wrote the first. Same machine.

### Key slots

| Key | Purpose |
|---|---|
| `:machine-id` *or* `:definition` | Which machine to spawn (registered id, or inline transition table) |
| `:data` | Initial data — literal map or `(fn [snapshot event] data)` |
| `:on-spawn` | `(fn [data spawned-id] new-data)` — how the parent records the child id |
| `:on-done` | `(fn [data result] new-data)` — fires when child enters `:final?` (see below) |
| `:start` | Event vector dispatched to the newborn after spawn |
| `:invoke-id` | Explicit id instead of gensym — useful for tests / per-state singletons |
| `:id-prefix` | Base for the gensym'd id (defaults to `:machine-id`) |

### What about timeouts?

`:invoke` doesn't take a `:timeout-ms` slot. Wall-clock timeouts on the spawned actor live on the *parent state's* `:after` map. One primitive, not two:

```clojure
{:authenticating
 {:invoke {:machine-id :auth-flow}
  :after  {30000 :auth-failed}                ;; 30 s wall-clock guard
  :on     {:auth/succeeded :authenticated}}}
```

When the 30 s `:after` fires, the parent's exit cascade destroys the `:auth-flow` child (which itself cascades any in-flight `:rf.http/managed` aborts — see [Cancellation cascade](../../../spec/005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts)). The timer is anchored to the parent state's entry, not to any HTTP attempt; the child's internal retries can't outlive the parent's deadline.

For the full description of `:invoke`'s desugaring, composition with `:entry` / `:exit`, hierarchical composition, error categories, see [005 §Declarative `:invoke`](../../../spec/005-StateMachines.md).

## `:invoke-all` — spawn-and-join

> "Spawn N children in parallel. When the join condition resolves, fire a parent event."

```clojure
{:hydrating
 {:invoke-all
  {:children         [{:id :cfg  :machine-id :load-config}
                      {:id :flag :machine-id :load-feature-flags}
                      {:id :user :machine-id :load-user-profile}
                      {:id :dash :machine-id :load-dashboards}]
   :join             :all                      ;; or :any / {:n 2} / {:fn pred}
   :on-child-done    :child/done               ;; child → parent event keyword
   :on-child-error   :child/error
   :on-all-complete  [:hydrate/done]
   :on-any-failed    [:hydrate/failed]}
  :after {60000 :hydrate/timed-out}            ;; whole-join wall-clock guard
  :on    {:hydrate/done       :ready
          :hydrate/failed     :error
          :hydrate/timed-out  :degraded}}}
```

Four children spawn in parallel. The runtime tracks completions; the join condition (`:all` here — every child must signal done) resolves when all four `:child/done` events arrive; the parent's `:hydrate/done` event fires; the machine transitions to `:ready`.

### Join condition discriminators

| `:join` | Resolves when |
|---|---|
| `:all` (default) | Every `:children` entry has signalled `:on-child-done` |
| `:any` | The first `:on-child-done` arrives |
| `{:n N}` | The Nth `:on-child-done` arrives |
| `{:fn (fn [{:keys [done failed]}] truthy)}` | Your predicate returns truthy |

Each option fires `:on-some-complete` (for `:any` / `{:n N}` / `{:fn}`) or `:on-all-complete` (for `:all`). Any child failure short-circuits via `:on-any-failed` if you've declared it.

### Cancel-on-decision (default `true`)

When the join resolves, siblings still in flight are cancelled. Each cancelled sibling has its `:rf.machine/destroy` fired (with the in-flight `:rf.http/managed` aborts cascading), and the runtime emits `:rf.machine.invoke/cancelled-on-join-resolution` for each.

Apps that want non-cancelling joins (analytics fan-out where each child is independently valuable) declare `:cancel-on-decision? false` — siblings run to completion; their results land in the join-state but trigger no further parent event because `:resolved?` already flipped.

### What `:invoke-all` *isn't*

It isn't an "everything happens at once" primitive — children spawn in source order, but each child runs as its own actor with its own drain. The "parallelism" is logical-actor-parallelism, not OS-thread-parallelism. (CLJS is single-threaded; JVM SSR may execute multiple actors on multiple threads, but the contract is "the runtime coordinates the join.")

For the full description see [005 §Spawn-and-join via `:invoke-all`](../../../spec/005-StateMachines.md).

## `:final?` / `:on-done` / `:output-key` — terminal states

> "When the machine enters this leaf, finish — and optionally tell the parent what came of it."

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

;; Parent — :on-done reads the child's reported result.
(rf/reg-machine :login
  {:initial :idle
   :states
   {:idle {:on {:submit :authenticating}}
    :authenticating
    {:invoke {:machine-id :auth-flow
              :on-done    (fn [data result] (assoc data :token result))}
     :on    {:auth/cancelled :idle}}}})
```

When `:auth-flow` enters `:done`, the runtime:

1. Reads the child's `:data` at `:output-key :token` — call it `result`.
2. Looks up the parent's `:invoke` and runs `:on-done` against the parent's `:data` with that `result` — the returned map replaces the parent's `:data`.
3. Emits a `:rf.machine/done` trace event with `:machine-id`, `:output`, `:parent-id`.
4. Tears down the child via the standard destroy path (with `:reason :rf.machine/finished`).

### Singletons too

A *singleton* machine (registered top-level, no parent `:invoke`) reaching `:final?` **also auto-destroys**. "Final means final." If you want a persistent terminal state, **omit `:final?`** and use an ordinary leaf state. This is the most common gotcha for users meeting `:final?` for the first time, so it's worth saying out loud.

### Constraints

- **Leaf-only.** A compound state can't itself be `:final?`. Express finality with a leaf inside the compound.
- **No `:on`, `:always`, `:after`, `:invoke`, `:invoke-all` on a `:final?` state.** Final means final — no further transitions.
- **`:output-key` requires `:final?`.** A non-final state declaring `:output-key` is a registration error.

### Parallel regions

A leaf inside one region of a parallel-region machine may declare `:final? true`; the meaning is "**this region** has reached its final state." That region halts; sibling regions continue. The parent machine as a whole is `:final?` only when EVERY region's active state is `:final?` — at which point the auto-destroy and `:on-done` cascade fires as usual.

For the full normative description see [005 §Final states](../../../spec/005-StateMachines.md).

## Parallel regions — orthogonal axes of one feature

```clojure
(rf/reg-machine :nine-states
  {:type    :parallel
   :regions
   {:data {:initial :loading
           :states {:loading {:on {:loaded :ready :failed :failed}}
                    :ready   {}
                    :failed  {}}}
    :form {:initial :neutral
           :states {:neutral  {:on {:edit :invalid}}
                    :invalid  {:on {:fix  :valid}}
                    :valid    {}}}
    :mode {:initial :active
           :states {:active {:on {:done :done}}
                    :done   {}}}}})
```

Three regions run **simultaneously** from one machine. Each region has its own state-tree and reacts to events independently — `:loaded` advances the `:data` region, `:edit` advances the `:form` region, `:done` advances the `:mode` region. The whole machine's snapshot at any moment is a map:

```clojure
{:state {:data :ready :form :valid :mode :active}
 :data  <shared :data slot for all three regions>
 :tags  #{<union of all three regions' active-state tags>}}
```

### When to reach for parallel regions

Use them when the **regions are orthogonal axes of one feature** — different axes of "what is this page doing right now?" that should compose freely. The motivating case is the Nine States pattern ([Pattern-NineStates](../../../spec/Pattern-NineStates.md)): a page-level convention whose render decisions slice across (data cardinality × form validity × mode).

If your regions are conceptually **independent features that don't share data**, the right answer is *N separate machines* — separate `[:rf/machines <id>]` entries coordinated via cross-actor dispatch. Both patterns ship; choose by domain shape.

### Per-region scoping

`:after` timers and `:invoke` lifetimes are per-region. A `:after` on the `:data` region doesn't get cancelled when the `:form` region transitions. The runtime maintains a per-region epoch counter (`:rf/after-epoch-by-region` inside `:data`) so a sibling region's transition doesn't invalidate this region's in-flight timers.

`:always` cascades similarly fire per region; tags compose by union across active states.

For the full normative description see [005 §Parallel regions](../../../spec/005-StateMachines.md).

## What lives in `:data`, what the runtime owns

A few `:rf/*` keys appear inside a machine's `:data` slot. These are runtime-owned — your machine bodies should read them, never write under them:

| Key | Meaning |
|---|---|
| `:rf/after-epoch` | Per-machine epoch counter for `:after`-timer stale detection (flat / compound) |
| `:rf/after-epoch-by-region` | Per-region counter for `:after`-timer stale detection (parallel regions) |
| `:rf/self-id` | The machine's own gensym'd id (set by spawn-fx for spawned actors) |
| `:rf/parent-id` | The parent machine's id (set on `:invoke` / `:invoke-all` children) |
| `:rf/invoke-id` | The `:invoke`-bearing state's prefix-path (used to address the spawn-registry slot) |
| `:rf/invoke-all-id` / `:rf/invoke-all-child-id` | `:invoke-all` analogues |

These slots are documented at [Conventions.md §Reserved snapshot-internal keys](../../../spec/Conventions.md#reserved-snapshot-internal-keys-machine-runtime). Their persistence behaviour is also documented there (some survive `pr-str` / SSR hydration; some are transient).

## Cross-references

- [Chapter 08 — State machines](../08-state-machines.md) — the basic state-machine narrative; this page is its substrate-feature deep-dive.
- [Spec 005 — State Machines](../../../spec/005-StateMachines.md) — the normative description for every key on this page.
- [Pattern-NineStates.md](../../../spec/Pattern-NineStates.md) — the page-level pattern that motivated parallel regions.
- [Pattern-StaleDetection.md](../../../spec/Pattern-StaleDetection.md) — the cross-cutting epoch-counter pattern that `:after` shares with routing's nav tokens.
- [§04 — Drain depth and error recovery](04-drain-depth-and-error-recovery.md) — the outer drain's depth ceiling. `:always`-depth and `:raise`-depth ceilings live inside machines; they compose with the outer ceiling.
- [Cancellation cascade](../../../spec/005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) — what happens to in-flight HTTP requests when a spawned actor is destroyed.

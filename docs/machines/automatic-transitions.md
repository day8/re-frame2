# Automatic transitions

Most [transitions](glossary.md#transition) wait for the world: a user clicks, an HTTP reply lands, a timer you wired by hand goes off. An **automatic transition** is one the [machine](glossary.md#machine) takes *on its own*, with no external event — the instant a condition becomes true, or a deadline passes, the [snapshot](glossary.md#snapshot) moves.

These are the statechart features that let a machine *drive itself*: a form that routes the moment it's validated, a splash screen that dismisses after three seconds, a reconnect that backs off and retries, a request that gives up after ten. Without them you reach for the same fragile pattern every time — a `setTimeout` paired with a cancel flag, or a synthetic event you `dispatch` by hand from every place that could enable the next step. re-frame2 turns each of those into one declarative key on a state node.

There are four authoring grammars, and underneath them just **two engines**:

| You want… | Reach for | Fires when |
|---|---|---|
| "the instant condition X holds, go to Y" | `:always` | a guard turns true, re-checked after every transition into the state |
| "a decision node that routes on entry and never lingers" | `:type :choice` | entry — first guard-passing candidate wins |
| "after N ms in this state, do Z" | `:after` | a wall-clock delay measured from state entry elapses |
| "this state (or its child) must finish in time" | `:timeout` / `:on-timeout` | a fixed deadline from entry passes |

!!! note "Four grammars, two engines"

    `:type :choice` is sugar that **desugars to `:always`**; `:timeout` / `:on-timeout` is sugar that **desugars to `:after`**. So everything on this page runs on one of two mechanisms: the **guard-driven microstep loop** (`:always`, `:choice`) and the **wall-clock timer** (`:after`, `:timeout`). One fact, one mechanism — the extra grammars exist only to *name the author's intent* so tools and diagrams can read it.

Everything below assumes the core loop from the [concepts guide](concepts.md#registering-and-running-it): a machine is registered with `reg-machine`, its transition table is data, a [guard](glossary.md#guard) returns a boolean, an [action](glossary.md#action) returns the data-shaped effect map `{:data … :fx …}` (the `:data` is *merged* into the snapshot, key by key), and the live value is a snapshot `{:state … :data … :tags …}`. New to all that? Read [Concepts → Guards and actions](concepts.md#guards-and-actions) first.

## Eventless `:always` — fire the instant a condition holds

`:always` is a state-node key holding a vector of guarded transitions. It is checked **after entry** — and after *any* transition that lands in the state — and the first guard that passes fires immediately, no event required. It answers "the snapshot just changed; if X is now true, move on" without you hand-raising a synthetic event from every action that could enable X.

Here is the canonical shape — a quiz that ends the moment enough answers are correct:

```clojure
(rf/reg-machine :quiz
  {:initial :asking
   :data    {:correct-count 0}
   :guards  {:enough-correct? (fn [{:keys [data]}] (>= (:correct-count data) 10))}
   :actions {:count-correct   (fn [{:keys [data]}]
                                {:data {:correct-count (inc (:correct-count data))}})}
   :states
   {:asking {:always [{:guard :enough-correct? :target :winner}]
             :on     {:answer-correct {:action :count-correct}   ;; no :target — stays :asking
                      :answer-wrong   {:target :loser}}}
    :winner {}
    :loser  {}}})
```

When you `(rf/dispatch [:quiz [:answer-correct]])`, two things happen inside **one** machine event:

1. `:asking`'s `:answer-correct` transition runs `:count-correct`, which bumps `:correct-count`. There is no `:target`, so this is an internal transition — the snapshot stays at `:asking`.
2. The runtime immediately re-checks `:asking`'s `:always`. If `:correct-count` has now reached 10, `:enough-correct?` is true and the machine transitions to `:winner`.

External observers see `:asking → :winner` in one step. The "answer counted, still asking" intermediate state is **invisible** — exactly the property `:always` exists to provide.

### The mental model: settle, then commit once

`:always` extends the event [pipeline](../core/concepts/events-and-the-pipeline.md): after the triggering transition, the runtime runs a **microstep loop** that keeps taking enabled `:always` transitions (and draining any [`:raise`](../api/re-frame.machines.md)d internal events) until it reaches a **fixed point** — no `:always` guard matches and no raised event is pending. Only then does it commit the snapshot, **once, atomically**, and emit the accumulated `:fx`.

This is classic statechart **macrostep** semantics: the externally-observable transition is the *settled* result of an internal cascade of microsteps. Time-travel, [Xray](../core/concepts/observability.md), and undo all see the single committed transition; the inner microsteps ride the trace stream for tools that want them.

!!! note "It runs at birth, too"

    The same settle loop runs when the machine first starts. If the [`:initial`](concepts.md#registering-and-running-it) state cascades into a leaf whose `:always` guard already holds, that transition is taken *before* the birth commit — so a transient initial state is settled past, unobserved, on start. (This is also why `:always` lives on a state node, never on the root: the root's cascade entry-point is `:initial`.)

### Ordering, depth, and self-loops

A few rules keep the loop well-behaved — and keep your typos loud at registration time, not on the unlucky dispatch:

- **`:always` settles before the next raised event.** If an action both `:raise`s an event `R` and lands in a state whose `:always` is enabled, the `:always` is taken **first**; `R` is then handled in the post-`:always` state. Raised events otherwise drain FIFO. (This matches SCXML exactly.)
- **Bounded depth.** The loop is capped (default **16** microsteps, configurable per machine via `:always-depth-limit`). A non-converging cycle trips `:rf.error/machine-always-depth-exceeded` and **fails the whole macrostep** — the snapshot is never written, so the transition rolls back atomically. It is a failure, not a silent no-op, so a runaway cycle is distinguishable from an ordinary guard-blocked decline.
- **No self-target.** An `:always` whose `:target` resolves to its own declaring state is **rejected at `reg-machine` time** (`:rf.error/machine-always-self-loop`) — guarded or not. An eventless transition back into the state you just entered either loops to the depth limit or is a no-op; either way the author meant something else.

!!! note "Looping until a condition clears"

    The legitimate "keep going while there's work" shape is a **targetless** guarded `:always` with an `:action` — `{:guard :more? :action :drain-one}`. Its action flips the guard false and the loop settles. That is *not* a self-loop (there's no `:target`), so it's allowed.

### `:always` in the wild

The [WebSocket example](../../examples/patterns/websocket/README.md) uses `:always` for two distinct jobs on two different states:

```clojure
:reconnecting
{:always [{:guard :max-retries-exceeded? :target :failed}]   ;; out of retries → give up
 :after  {…}                                                  ;; otherwise back off and retry
 :on     {…}}

:connected
{:entry  :flush-queue-and-resubscribe
 :always [{:guard :has-queued-messages? :action :flush-queue}]  ;; drain the offline queue on entry
 :on     {…}}}
```

`:reconnecting` carries **both** `:always` and `:after` — they're independent state-node slots and coexist freely. And `:connected`'s `:always` is the targetless-action form: its `:flush-queue` action clears the queue, the guard goes false, and the microstep loop settles — no extra state needed.

`:always` is **not** a substitute for `:after`: `:always` fires on guard *truth*, `:after` fires on elapsed *time*.

## `:type :choice` — a decision node that never lingers

A **choice state** is a routing node: enter it, walk its guarded candidates in order, take the first whose guard passes, and leave — all within the same macrostep, no event needed. It's how you draw "decide which way to go" as an explicit node on the chart.

```clojure
(rf/reg-machine :submission
  {:initial :idle
   :data    {}
   :guards  {:valid? (fn [{:keys [data]}] (boolean (:form-ok? data)))}
   :states
   {:idle     {:on {:submit :checking}}
    :checking {:type   :choice
               :choice [{:guard :valid? :target :accepted}
                        {:target :rejected}]}     ;; unguarded final = the default / else branch
    :accepted {}
    :rejected {}}})
```

Dispatching `[:submission [:submit]]` enters `:checking`, which resolves **immediately**: if `:valid?` passes the machine settles at `:accepted`, otherwise it falls through to the default `:rejected`. External observers never see `:checking` — it's a transient routing node. (A choice state may also be the machine's `:initial` state, in which case it resolves at birth.)

Under the hood, `:type :choice` / `:choice` **desugars to an ordinary state with an `:always` slot** carrying the same candidate vector. The candidate walk, first-guard-pass select, and macrostep settle are all the `:always` machinery you just met — reused, not reinvented. `:choice` exists to *name the intent* so a visualiser renders a decision diamond and validation gives a clearer grammar.

### Divergence: a declarative array, not a `choice` function

re-frame2 requires a choice node's routing to be a **declarative guarded-candidate array** `[{:guard … :target …}]`, never a function — a function may never be the edge. The edge topology stays *data*, so diagrams, model tests, conformance fixtures, and AI tools can read the routing without executing it. A function-valued `:choice` fails loud at registration with `:rf.error/machine-bad-choice`.

### What the runtime checks

`reg-machine` validates the choice grammar on the raw spec, so a mistake is caught before the machine ever runs:

- `:type :choice` and `:choice` **require each other** — one without the other is meaningless.
- The `:choice` value **must be a non-empty vector of candidate maps** (not a fn, keyword, or empty vector).
- The vector **must include an unguarded default** candidate. Without one, a `:checking` entered with every guard failing would have nowhere to go — a stuck transient node. A present default is the only static guarantee the state always resolves.
- A choice state **only routes** — it may not also declare `:entry`, `:on`, `:after`, `:timeout`, `:spawn`, and so on. (So a choice state can never carry a `:timeout`; the two named-intent grammars don't combine.)
- A candidate targeting the choice state's own state is a self-loop and is rejected, exactly as for `:always`.

## Delayed `:after` — transition after a wall-clock delay

`:after` is a state-node key mapping a **delay** to a transition. Entering the state arms every timer; leaving the state cancels them. On expiry, the matching transition fires (subject to its guard). It replaces the `dispatch-later`-plus-cancel-flag pattern that sits behind most timeout, debounce, and reconnect bugs.

The simplest case — a splash screen that dismisses itself after three seconds, or sooner if the user skips:

```clojure
(rf/reg-machine :boot
  {:initial :splash
   :states
   {:splash {:after {3000 :main}        ;; 3000 ms → :main
             :on    {:skip :main}}       ;; …or the user clicks "skip"
    :main   {:on {:restart :splash}}}})
```

`{3000 :main}` is sugar for `{3000 {:target :main}}` — the delay's value uses the **same** transition grammar as an `:on` clause.

### What goes in the `:after` map

Each entry is `<delay> → <transition>`. Both halves take several forms.

**The delay (the key) — three forms:**

- **Integer milliseconds** — `{30000 :timeout}`. The common fixed delay. (Note: a literal `:after` delay is **integer ms only** — not an ISO-8601 string, and *never* the `"5s"` shorthand. ISO-8601 durations belong to `:timeout`, below; the `"5s"` shorthand re-frame2 rejects everywhere.)
- **A [subscription](../core/concepts/subscriptions.md) vector** — `[:sub-id & args]`, resolved like any `subscribe`. The canonical form for an **app-state-derived** delay (a user preference, a feature-flag config). It *re-resolves* when its value changes (see below).
- **A function** — `(fn [{:keys [snapshot]}] ms)`, called **once** at state entry. The escape valve for a delay computed from the machine's own `:data`.

!!! note "Watch the shape"

    Guards and actions receive `:data` at the top level of their context map; an `:after` delay-fn instead receives `{:keys [snapshot]}` and reaches *inside* it — `(-> snapshot :data :retry-count)`. The snapshot is the familiar `{:state … :data … :tags …}` value.

**The transition (the value) — identical to an `:on` clause:** a bare keyword (sugar for `{:target kw}`), a full transition map `{:guard … :target … :action … :meta …}`, or a first-guard-pass-wins candidate vector. If a `:guard` is present and false at expiry, the transition is **suppressed** — the timer is "fired and discarded", the snapshot is unchanged, a `:rf.machine.timer/fired` trace records `:fired? false`, and *other in-flight timers keep running*.

```clojure
;; All three delay forms, on one state node:
{:loading
 {:after {30000                        {:target :timeout :guard :no-progress?}   ;; literal ms
          [:sub :timeout-config :slow] {:target :warn :action :log-slow}          ;; subscription
          (fn [{:keys [snapshot]}] (* 1000 (-> snapshot :data :retry-count)))
                                       :retry}                                     ;; local fn
  :on    {:loaded :ready
          :failed :error}}}
```

### One timer per entry, counted from entry

Every `:after` timer counts independently from the moment the machine **enters the state**. A state with `{:after {5000 :warn 30000 :timeout}}` arms *both* timers at entry, concurrently — the 5 s timer is not chained off the 30 s one. Re-entering the state restarts every timer fresh; there's no preserved "elapsed-so-far".

A state can have several triggers racing at once — multiple `:after` timers, the state's `:on` events, an `:always`, a [spawned](glossary.md#spawn) child completing. **Whichever fires first wins**; the resulting state exit cancels the rest as part of the normal exit cascade.

!!! note "A same-tick tie has no document-order guarantee"

    When two `:after` timers with different delays happen to fire in the same scheduler tick, the order they reach the router is the *host scheduler's* order — not document order. SCXML resolves simultaneously-enabled transitions by document order; re-frame2 `:after` timers are real host-clock deferred events, so it's "first valid timer to arrive wins; the transition makes the rest stale." Don't rely on declaration order to break a same-tick `:after` tie.

### No cancel flag: epoch-based staleness

re-frame2 ships **no `:cancel-dispatch-later` fx**, and you never write one. That's safe — and the headline reason to prefer `:after` over hand-rolled timers.

Every scheduled timer carries an **epoch** — a per-state-entry counter — captured when it's armed. Leaving the state advances that state's epoch. When a timer eventually fires, the runtime compares the carried epoch against the state's current one:

- **Match** → the state is still the one that armed this timer; fire the transition.
- **Mismatch** → the state was exited (or re-entered, arming a fresh timer); **silently drop it** and emit a `:rf.machine.timer/stale-after` trace.

So a timer armed on an earlier visit can never "wander in" and fire against a state you've since left. There's nothing to remember and nothing to clean up: the epoch makes a late timer self-cancelling. The epoch is tracked **per scheduling node** (the declaring state's path), so a parent's long `:after` survives a leaf-to-sibling transition underneath it, while the exited leaf's timers go stale — a parent's 30 s hard-timeout ticks happily alongside a child's 5 s progress timeout.

### Delays that track app state

A **subscription-vector** delay re-resolves while it's in flight. If the underlying sub value changes, the runtime cancels the current timer and **restarts from now** with the freshly-resolved delay (it does *not* extend or shorten the running timer — restart-from-now keeps the countdown always reflecting the current value). The epoch mechanism backstops the cancellation, and a paired `:rf.machine.timer/cancelled` (`:reason :on-resolution`) → `:rf.machine.timer/scheduled` shows up on the trace stream.

A **function** delay does **not** re-resolve — it's computed once at entry. If you need a `:data`-derived delay that re-resolves on change, use the subscription form (over a sub that reads the machine's `:data`) and pay the subscription cost; the fn form is the cheap "compute once at entry" valve.

### Worked example: exponential backoff

The WebSocket example's `:reconnecting` state computes its backoff from the retry count, using the function-delay form:

```clojure
:reconnecting
{:tags   #{:websocket/reconnecting}
 :always [{:guard :max-retries-exceeded? :target :failed}]
 :after  {(fn [{:keys [snapshot]}]
            (let [{:keys [retries base-ms max-backoff-ms]} (:data snapshot)]
              (min (* base-ms (Math/pow 2 retries)) max-backoff-ms)))
          {:target :active}}                       ;; …wait, then retry the connection
 :on     {:ws/connect {:target :active :action :record-and-reset}
          …}}
```

Each visit to `:reconnecting` reads the *current* `:retries` and waits longer than the last (200 ms, 400, 800, … capped at `max-backoff-ms`). The `:always` short-circuits straight to `:failed` once retries are exhausted. And because leaving `:reconnecting` cancels the timer via the epoch mechanism, a backoff armed on an earlier visit can never fire late — no flag, no cleanup. See [`connection.cljs`](../../examples/patterns/websocket/README.md) for the whole machine.

### The clock, SSR, and what `:after` is *not*

`:after` timers go through `re-frame.interop` (`now-ms`, `schedule-after!`, `cancel-scheduled!`) — `setTimeout`/`clearTimeout` on CLJS, a `ScheduledExecutorService` on the JVM. There's no framework-level clock-config API; tests swap the interop layer with the usual fixture patterns. Under SSR, `:after` **no-ops** — the server renders the current `:state` statically and the client re-arms timers after hydration.

`:after` deliberately does **not** include: recurring timers (re-enter the state to re-arm), calendar/wall-clock-time scheduling (it's relative to *entry*, not "9 AM tomorrow"), or pause/resume (transition out and back in).

## `:timeout` / `:on-timeout` — a named deadline

A state — or a [`:spawn`](glossary.md#spawn) spec — may declare a `:timeout` duration plus an `:on-timeout` transition. It names a single fact: "this state (or its spawned child) must finish before this time." It reads more clearly than a bare `:after` entry when the intent really is a deadline.

```clojure
{:states
 {:waiting
  {:timeout    "PT5S"                       ;; ISO-8601: 5 seconds
   :on-timeout {:target :timed-out}}

  :loading
  {:spawn {:machine-id :fetch-user          ;; spawn a child machine
           :timeout    "PT10S"              ;; the child must finish within 10 s…
           :on-timeout {:target :timed-out}}}}}  ;; …or we bail
```

`:timeout` **requires** `:on-timeout` (and vice versa); a lone one of either is a registration error. The pair **desugars to an `:after` entry** keyed by the resolved-ms duration — so entering the state arms it, leaving cancels it, and all the `:after` epoch/staleness/trace machinery drives it unchanged. For a spawn timeout, the timer is anchored to the spawn-bearing state's entry, so it bounds the child's whole lifetime (across any internal retries); when it fires, the exit cascade tears the child down.

`:timeout` and `:after` express different intent and **may coexist** on one state node. (A `:final?` state can't declare a `:timeout` — final means final.)

### Duration grammar: integer-ms or ISO-8601

A `:timeout` duration is **exactly one of**:

- a **positive integer** — literal milliseconds (`5000`); or
- an **ISO-8601 duration string** — `"PT5S"`, `"PT2M"`, `"PT1H30M"`, `"PT0.5S"`, `"P1D"`, … (the `PnYnMnWnDTnHnMnS` form; case-insensitive; fractional seconds allowed).

A readable shorthand like `"5s"` / `"10ms"` is **not** accepted: such a string — or any other malformed duration (a non-positive integer, a fn, a vector, the bare `"P"`) — fails **loud** at registration with `:rf.error/machine-bad-timeout-duration`. And unlike `:after`, a `:timeout` admits **no** subscription-vector or fn dynamic delays — a timeout is a fixed wall-clock deadline. The integer-ms-or-ISO-8601 rule is the single duration grammar; the shorthand string is rejected on purpose.

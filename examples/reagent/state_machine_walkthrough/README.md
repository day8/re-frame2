# state_machine_walkthrough — runnable companion to the machines chapter

This is the [machines chapter](../../../docs/machines/concepts.md)'s login
flow, lifted off the page and made to run. Every snippet the chapter shows
lives here as real, compiling code, in the order the chapter introduces it.

The chapter builds a login [machine](../../../docs/machines/glossary.md#machine)
one idea at a time: a [transition](../../../docs/machines/glossary.md#transition)
table, then [guards](../../../docs/machines/glossary.md#guard),
[actions](../../../docs/machines/glossary.md#action),
[tags](../../../docs/machines/glossary.md#state-tag), and an HTTP call whose
reply lands back inside the machine. If a passage left you wanting to poke at
it, poke here.

The one idea worth taking away: a single transition table does double duty.
The same `login-flow` value you click through in a browser is the value a test
drives as a sequence of pure function calls on the JVM — no frame, no DOM, no
network, microseconds per transition. A machine is just an
[event handler](../../../docs/guide/glossary.md#event-handler), so its behaviour
is data you can either render or unit-test. This example shows both.

## What this demonstrates

- **The login flow, as data you read top to bottom.** The whole machine is the
  `login-flow` map in [`core.cljc`](core.cljc): five states
  (`:idle → :submitting → {:error-shown | :authed | :locked-out}`), with the
  [guards](../../../docs/machines/glossary.md#guard) and
  [actions](../../../docs/machines/glossary.md#action) sitting *with* the table
  rather than in a global registry. References inside `:states` resolve against
  that one map — each arrow names a guard or action by id, defined once up top.
  To reuse a predicate across machines, define an ordinary Clojure var and name
  it in each machine's `:guards` / `:actions`. No string registry.

- **A live browser demo, driven entirely by the snapshot.**
  [`views.cljs`](views.cljs) mounts a real Reagent login form. Every visible
  decision reads the machine's
  [snapshot](../../../docs/machines/glossary.md#snapshot) through
  [subscriptions](../../../docs/guide/glossary.md#subscription). The view asks
  *what tag is active*, not *which exact state*. `(rf/machine-has-tag?
  :auth.login/flow :auth/busy)` disables the inputs and re-labels the button
  while a request is in flight; `:auth/locked` swaps the form for a lockout
  panel. That is *ask, don't tell*: the view never names a state keyword, so
  adding a sixth busy state wouldn't touch it. The demo wires the request to
  always fail (more below), so what you watch is the lockout path — three
  rejected attempts, then a fourth that trips the retry guard and parks the
  machine in `:locked-out`.

- **The same flow tested as pure function calls.** Feed a starting
  [snapshot](../../../docs/machines/glossary.md#snapshot) and an event into
  `machine-transition`, then assert against the snapshot that comes back. For
  the full-loop scenarios,
  [drain](../../../docs/guide/glossary.md#drain--run-to-completion) the event
  queue through a throwaway [frame](../../../docs/guide/glossary.md#frame) and
  check where the [app-db](../../../docs/guide/glossary.md#app-db) settles. The
  chapter promises "runs in microseconds on the JVM, no browser, no network,"
  and the same `login-flow` value makes good on it.

- **An HTTP call that composes with the machine for free.** The
  `:issue-request` action returns an
  [effect](../../../docs/guide/glossary.md#effect), not a side effect. It fires
  `:rf.http/managed` and names its `:on-success` / `:on-failure` as
  machine-wrapped events. When the request returns,
  [managed HTTP](../../../docs/resources/http.md) appends the reply to that
  inner event and dispatches it straight back into the machine. That is
  [the uniform reply](../../../docs/guide/glossary.md#the-uniform-reply), and
  it's why the async boundary needs no glue code. The network is swapped out via
  the [`:fx-overrides`](../../../docs/guide/glossary.md#effect) seam: it
  redirects `:rf.http/managed` to the example's own
  `:auth.login/canned-success` / `:auth.login/canned-failure` wrapper effects in
  [`core.cljc`](core.cljc), which fix this example's payloads. No real traffic,
  identical reply shape.

## Why .cljc

The chapter sells state machines as something you can test from a Clojure REPL,
and `.cljc` is what delivers that. The *identical* source compiles two ways:
under shadow-cljs node-test for the CLJS surface, and under a JVM Clojure REPL
or test for the headless story. One artefact, two runtimes — and the testing
pitch only lands because the JVM runs the very same code the browser does.

## Why this shape

Read the [machines chapter](../../../docs/machines/concepts.md) first for the
narrative; come here when you want to run the code rather than read about it.

This example is a near-twin of the login machine in
[`examples/reagent/login/`](../login/): same five states, same core guards and
actions, both tagging `:locked-out` with `:auth/locked` and swapping in a
dedicated lockout panel off that tag. What differs is what each teaches *around*
the machine. `login` is the full feature scaffold — Malli schemas on the event
and the machine `:data`, a `:sensitive?` flag on the request, a demo stub that
routes by password — wired into a complete UI. This walkthrough strips all that
away to foreground the testing progression, so its request always fails and the
headless scenarios are the point. Reach for `login` to see the end-to-end
wiring; reach for this one to see a machine tested as pure data.

## Files

```
state_machine_walkthrough/
  core.cljc                                            — login flow transition table + supporting fns.
  views.cljs                                           — view layer for the runnable demo.
  index.html                                           — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/state-machine-walkthrough
```

Then open the served [`index.html`](index.html) and watch the lockout path:
three rejected attempts, then a fourth that parks the machine in `:locked-out`.

## Cross-references

- [`docs/machines/concepts.md`](../../../docs/machines/concepts.md) — the chapter this example accompanies.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the normative machine spec.
- [`examples/reagent/login/`](../login/) — the same machine wired into a live Reagent view.
- [`examples/reagent/nine_states/`](../nine_states/) — parallel regions and tags; next-step companion.

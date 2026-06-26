# state_machine_walkthrough — runnable companion to the machines chapter

This is the [machines chapter](../../../docs/machines/concepts.md)'s
login flow, lifted off the page and made to run. The chapter builds a
login [machine](../../../docs/machines/glossary.md#machine) one idea at
a time — a [transition](../../../docs/machines/glossary.md#transition)
table, [guards](../../../docs/machines/glossary.md#guard),
[actions](../../../docs/machines/glossary.md#action),
[tags](../../../docs/machines/glossary.md#state-tag), an HTTP call that
lands its reply back inside the machine — and every snippet it shows
you lives here as real, compiling code, in the order the chapter
introduces it. So if a passage in the chapter left you wanting to poke
at it, this is the thing to poke.

The load-bearing idea — the reason this example is worth a read on its
own — is that *one* transition table does double duty. The same
`login-flow` value you can click through in a browser is the value a
test drives as a sequence of pure function calls on the JVM: no frame,
no DOM, no network, microseconds per transition. A machine is just an
[event handler](../../../docs/guide/glossary.md#event-handler), so its
behaviour is data you can either render or unit-test, and this example
shows you both ends of that.

## What this demonstrates

- **The login flow, as data you can read top to bottom.** The whole
  machine is the `login-flow` map in [`core.cljc`](core.cljc): five
  states (`:idle → :submitting → {:error-shown | :authed |
  :locked-out}`), the [guards](../../../docs/machines/glossary.md#guard)
  and [actions](../../../docs/machines/glossary.md#action) sitting *with*
  the spec rather than in any global registry. References inside
  `:states` resolve against that one map; the arrows name a guard or
  action by id, and its implementation lives once, up top. Want to
  reuse a predicate across machines? It's an ordinary Clojure var —
  define a fn, name it locally in each machine's `:guards` / `:actions`.
  No string registry, no ceremony.

- **A live browser demo, driven entirely by the snapshot.** [`views.cljs`](views.cljs)
  mounts a real Reagent login form whose every visible decision reads
  the machine's [snapshot](../../../docs/machines/glossary.md#snapshot)
  through [subscriptions](../../../docs/guide/glossary.md#subscription) —
  and crucially, it asks the machine *what tag is active* rather than
  *which exact state*. `(rf/machine-has-tag? :auth.login/flow
  :auth/busy)` disables the inputs and re-labels the button while a
  request is in flight; `:auth/locked` swaps the form out for a
  lockout panel. That's *ask, don't tell* in practice — the view never
  enumerates state keywords, so adding a sixth busy state wouldn't
  touch it. The demo wires the request to always fail (more on that
  below), so what you actually watch is the lockout path: three
  rejected attempts, then a fourth that trips the retry guard and
  parks the machine in `:locked-out`.

- **The same flow tested as pure function calls.** This is where
  machines pay you back. Each scenario the chapter describes has a
  matching headless test that feeds a starting
  [snapshot](../../../docs/machines/glossary.md#snapshot) and an event
  into `machine-transition` and asserts against the snapshot that comes
  back out — and, for the full-loop scenarios,
  [drains](../../../docs/guide/glossary.md#drain--run-to-completion) the
  whole event queue through a throwaway [frame](../../../docs/guide/glossary.md#frame)
  and checks where the [app-db](../../../docs/guide/glossary.md#app-db)
  settles. The chapter promises "runs in microseconds on the JVM, no
  browser, no network," and these honour it. The example *tree* stays
  test-free, so the four scenarios (pure happy-path, pure lockout,
  drain happy-path, drain retry-then-lockout) were folded into the
  framework JVM test — see [How to run](#how-to-run).

- **An HTTP call that composes with the machine for free.** The
  `:issue-request` action returns an [effect](../../../docs/guide/glossary.md#effect),
  not a side effect: it fires `:rf.http/managed`, and names its
  `:on-success` / `:on-failure` as machine-wrapped events. When the
  request returns, [managed HTTP](../../../docs/resources/http.md)
  appends the reply to that inner event and dispatches it straight back
  into the machine — that's
  [the uniform reply](../../../docs/guide/glossary.md#the-uniform-reply),
  and it's why an async boundary needs no glue code here. In both the
  demo and the tests the network is swapped out via the
  [`:fx-overrides`](../../../docs/guide/glossary.md#effect) seam, which
  redirects `:rf.http/managed` to the example's own
  `:auth.login/canned-success` / `:auth.login/canned-failure` wrapper
  effects in [`core.cljc`](core.cljc) — thin pins over the
  framework-shipped `:rf.http/managed-canned-success` /
  `:rf.http/managed-canned-failure` stubs (Spec 014 §Testing) that fix
  this example's payloads. No real traffic, identical reply shape.

## Why .cljc

The chapter sells state machines as something you can test from a
Clojure REPL, and `.cljc` is what makes good on that. The *identical*
source compiles two ways: under shadow-cljs node-test for the CLJS
surface, and under a JVM Clojure REPL or test for the headless story.
One artefact, two runtimes — and the testing pitch only lands because
the JVM can run the very same code the browser does.

## Why this shape

Read the [machines chapter](../../../docs/machines/concepts.md) first
for the narrative; come here for the executable form when you want to
run it rather than read it. This example is a near-twin of the login
machine in [`examples/reagent/login/`](../login/) — same five states,
same core guards and actions, and both tag `:locked-out` with
`:auth/locked` and swap in a dedicated lockout panel off that tag. What
the two examples teach *around* the machine is what differs: `login` is
the full feature scaffold — Malli schemas on the event and the machine
`:data`, a `:sensitive?` privacy flag on the request, a demo stub that
routes by password — wired into a complete UI; this walkthrough strips
all that away to foreground the testing progression, so the request is
wired to always fail and the headless scenarios are the point. Reach
for `login` if you want the end-to-end wiring, this one if you want to
see a machine tested as pure data.

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

The watch build emits `main.js` into
`out/examples/state-machine-walkthrough/`; copy this folder's
hand-written [`index.html`](index.html) (and the shared assets it
references under [`../../_shared/`](../../_shared/)) alongside it, then
serve `out/examples/state-machine-walkthrough/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).)
The four headless scenarios (pure happy-path, pure lockout,
drain happy-path, drain retry-then-lockout) were folded into the
framework JVM test at
[`implementation/core/test/re_frame/examples_test.clj`](../../../implementation/core/test/re_frame/examples_test.clj)
(the `state-machine-walkthrough-runs-headless` deftest) so
the example source stays test-free. They run under the JVM test suite.

## Cross-references

- [`docs/machines/concepts.md`](../../../docs/machines/concepts.md) — the chapter this example accompanies.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the normative machine spec.
- [`examples/reagent/login/`](../login/) — the same machine wired into a live Reagent view.
- [`examples/reagent/nine_states/`](../nine_states/) — parallel regions and tags; next-step companion.

# state_machine_walkthrough — runnable companion to the machines chapter

The login-flow chapter ([`docs/guide/concepts/machines.md`](../../../docs/guide/concepts/machines.md))
as executable code. Every prose snippet in the chapter appears here
in the order the chapter introduces it; each section ends with a
smoke-test fn that drives the machine through the scenario the
chapter describes.

## What this demonstrates

- **The same login machine as [`examples/reagent/login/`](../login/)** —
  same states, same guards, same actions. The two examples differ in
  what they teach AROUND the machine: `login` wires it into a live
  Reagent view; this walkthrough drives it HEADLESSLY to show the pure
  machine-transition + drain testing story.
- **Pure transition table** — `:guards` and `:actions` live *with*
  the spec, not in a global registry. References inside `:states`
  resolve against the spec map; cross-machine reuse is via Clojure
  Vars (define a fn, name it locally in each machine's `:guards` /
  `:actions`).
- **The chapter's headless test progression** — each scenario in the
  chapter has a matching headless test that drives the machine through
  it and asserts against the resulting snapshot. The chapter promises
  "runs in microseconds on the JVM, no browser, no network"; this
  example honours that. The example tree is test-free — the
  scenarios were folded into the framework JVM test (see
  [How to run](#how-to-run)).
- **HTTP via canned stubs** — the `:issue-request` action dispatches
  `:rf.http/managed`, overridden in tests via `:fx-overrides` to the
  framework-shipped `:rf.http/managed-canned-success` /
  `:rf.http/managed-canned-failure` stubs (Spec 014 §Testing). No
  real network traffic happens.

## Why .cljc

The chapter teaches state machines as something testable from a
Clojure REPL. The same code runs under shadow-cljs node-test for the
CLJS surface; the `:clj` branch is what you `(require ...)` from a
JVM REPL. One artefact, two runtimes.

## Why this shape

Read the [machines chapter](../../../docs/guide/concepts/machines.md)
first; then come here for the executable form. Read
[`examples/reagent/login/`](../login/) first if you want the live UI
wiring; read this for the testing progression.

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
(`npm run test:examples` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).)
The four headless scenarios (pure happy-path, pure lockout,
drain happy-path, drain retry-then-lockout) were folded into the
framework JVM test at
[`implementation/core/test/re_frame/examples_test.clj`](../../../implementation/core/test/re_frame/examples_test.clj)
(the `state-machine-walkthrough-runs-headless` deftest) so
the example source stays test-free. They run under the JVM test suite.

## Cross-references

- [`docs/guide/concepts/machines.md`](../../../docs/guide/concepts/machines.md) — the chapter this example accompanies.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the normative machine spec.
- [`examples/reagent/login/`](../login/) — the same machine wired into a live Reagent view.
- [`examples/reagent/nine_states/`](../nine_states/) — parallel regions and tags; next-step companion.

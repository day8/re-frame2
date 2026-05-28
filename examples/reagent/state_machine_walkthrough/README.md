# state_machine_walkthrough — runnable companion to the machines chapter

The login-flow chapter ([`docs/guide/11-machines.md`](../../../docs/guide/11-machines.md))
as executable code. Every prose snippet in the chapter appears here
in the order the chapter introduces it; each section ends with a
smoke-test fn that drives the machine through the scenario the
chapter describes.

## What this demonstrates

- **The same login machine as [`examples/reagent/login/`](../login/)** —
  same states, same guards, same actions. The two examples differ in
  what they teach AROUND the machine: `login` wires it into a live
  Reagent view; this walkthrough drives it HEADLESSLY (via the
  sibling `core-test` ns) to show the pure
  machine-transition + drain testing story.
- **Pure transition table** — `:guards` and `:actions` live *with*
  the spec, not in a global registry. References inside `:states`
  resolve against the spec map; cross-machine reuse is via Clojure
  Vars (define a fn, name it locally in each machine's `:guards` /
  `:actions`).
- **The chapter's headless test progression** — each scenario in the
  chapter has a matching `core-test` fn that drives the machine
  through it and asserts against the resulting snapshot. The chapter
  promises "runs in microseconds on the JVM, no browser, no network";
  this example honours that.
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

Read the [machines chapter](../../../docs/guide/11-machines.md)
first; then come here for the executable form. Read
[`examples/reagent/login/`](../login/) first if you want the live UI
wiring; read this for the testing progression.

## Files

```
state_machine_walkthrough/
  core.cljc                                            — login flow transition table + supporting fns.
  views.cljs                                           — view layer for the runnable demo.
  index.html                                           — minimal host page.
  test/state_machine_walkthrough/core_test.cljc        — the per-scenario smoke tests.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/state-machine-walkthrough
```

Run `npm run test:examples` once first so the example's `index.html`
is staged. The headless tests run as part of the framework's CLJS
test suite. To run them ad-hoc from a CLJS or JVM REPL:

```clojure
(require '[state-machine-walkthrough.core])      ;; load the machine
(require '[state-machine-walkthrough.core-test :as t])
(t/smoke-tests)                                  ;; runs all four scenarios
;; or one at a time:
(t/pure-happy-path-test)
(t/pure-lockout-test)
(t/drain-happy-path-test)
(t/drain-retry-then-lockout-test)
```

## Cross-references

- [`docs/guide/11-machines.md`](../../../docs/guide/11-machines.md) — the chapter this example accompanies.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the normative machine spec.
- [`examples/reagent/login/`](../login/) — the same machine wired into a live Reagent view.
- [`examples/reagent/nine_states/`](../nine_states/) — parallel regions and tags; next-step companion.

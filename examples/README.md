# Worked examples

> **Type:** Reference
> Demonstrates the spec's primitives composed into real UI work. Read after the guide; refer to alongside the specification.

> **Status reminder.** These examples target the current `re-frame-2` API. Their maturity varies: some are aligned closely enough to run against the reference implementation, some are pedagogical sketches, and the RealWorld scaffold is now a broad worked sketch rather than a partially empty placeholder set. Treat the per-example README or docstring as the source of truth for how complete each one is.

## Maturity

Each example carries one of three maturity tags. New readers should pick examples by what they want to learn:

| Tag | What it means |
|---|---|
| **Pedagogical sketch** | Single-file, one-mechanism focused. Smallest possible demonstration. The first stop after reading the guide. |
| **Benchmark** | Exhaustive demonstration of a class of UI tasks. Useful for "what does the pattern look like across a wide surface?" |
| **Worked scaffold** | Substantial app. Shows how the primitives compose into a real-world codebase, even when some parts are still sketch-level rather than production-polished. The README inside each scaffold is the source of truth for what is demonstrated concretely. |

## The examples

| Example | Maturity | What it demonstrates |
|---|---|---|
| [`counter/`](counter/) | Pedagogical sketch | The smallest possible app — one event, one sub, one view. The "hello world" of the pattern. |
| [`login/`](login/) | Pedagogical sketch | Single-feature scaffold — events + subs + views + machine + tests, all in one file, for a typical login flow. |
| [`todomvc/`](todomvc/README.md) | Benchmark | Canonical cross-framework todo app: persistence, editing, bulk actions, remaining count, and hash-routing filters. |
| [`routing/`](routing/) | Pedagogical sketch | Three-page app demonstrating `reg-route`, `:rf.route/navigate`, and route-not-found handling. The CP-7 worked example. |
| [`ssr/`](ssr/) | Pedagogical sketch | Minimal SSR + hydration walkthrough. The CP-9 worked example. JVM-runnable. |
| [`seven_guis/`](seven_guis/README.md) | Benchmark | The full [7GUIs](https://eugenkiss.github.io/7guis/) cross-framework UI benchmark — counter, temperature converter, flight booker, timer, CRUD, circle drawer, cells. Exhaustive demonstration that the pattern's primitives compose across difficulty levels. |
| [`nine_states/`](nine_states/README.md) | Benchmark | The nine canonical UI states (nothing / loading / empty / one / some / too many / incorrect / correct / done) for a single domain. Pedagogically exhaustive. |
| [`realworld/`](realworld/README.md) | Worked scaffold | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) — the de-facto cross-framework benchmark. Auth, feeds, routing, comments, editor, profile, favorites, settings, and SSR-hydration glue are all sketched on the current API surface. |

## Reading order

If you've finished the guide and want to see code:

1. **Start with [`counter/`](counter/)** — the smallest possible app. Establishes the basic shape.
2. **Then [`login/`](login/)** — adds a state machine, async effects, and form handling. Single-feature scope; full shape.
3. **Then [`todomvc/`](todomvc/README.md)** — classic benchmark shape: persistence, editing, filters, and browser routing pressure.
4. **Then [`routing/`](routing/)** or [`ssr/`](ssr/)** — pick whichever is closer to your interest.
5. **Then [`seven_guis/`](seven_guis/)** — survey of the pattern across many UI shapes.
6. **Then [`realworld/`](realworld/)** — substantial-app shape across the widest surface in the repo.

## End-to-end verification

Every example listed above is verified end-to-end by a Playwright spec — each spec navigates a real browser to the example's URL, asserts the initial render, drives at least one interaction, and asserts the post-interaction user-visible state. The orchestrator at [`implementation/scripts/serve-and-run-examples-tests.cjs`](../implementation/scripts/serve-and-run-examples-tests.cjs) compiles every example, stages its `index.html`, serves the output over HTTP, and runs the spec runner at [`implementation/scripts/run-examples-tests.cjs`](../implementation/scripts/run-examples-tests.cjs). Specs sit alongside each example as `<name>.spec.cjs`. Run the full sweep with `npm run test:examples` from `implementation/`.

## What examples are *not*

- **Not a substitute for the [specification](../spec/).** Examples illustrate; the specification defines.
- **Not all uniformly polished.** The Pedagogical-sketch examples are deliberately small. The Worked-scaffold (RealWorld) prioritises breadth of API coverage over production polish.

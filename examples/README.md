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
| [`routing/`](routing/) | Pedagogical sketch | Three-page app demonstrating `reg-route`, `:rf.route/navigate`, and route-not-found handling. The CP-7 worked example. |
| [`ssr/`](ssr/) | Pedagogical sketch | Minimal SSR + hydration walkthrough. The CP-9 worked example. JVM-runnable. |
| [`seven_guis/`](seven_guis/README.md) | Benchmark | The full [7GUIs](https://eugenkiss.github.io/7guis/) cross-framework UI benchmark — counter, temperature converter, flight booker, timer, CRUD, circle drawer, cells. Exhaustive demonstration that the pattern's primitives compose across difficulty levels. |
| [`nine_states/`](nine_states/README.md) | Benchmark | The nine canonical UI states (nothing / loading / empty / one / some / too many / incorrect / correct / done) for a single domain. Pedagogically exhaustive. |
| [`realworld/`](realworld/README.md) | Worked scaffold | [RealWorld (Conduit)](https://github.com/gothinkster/realworld) — the de-facto cross-framework benchmark. Auth, feeds, routing, comments, editor, profile, favorites, settings, and SSR-hydration glue are all sketched on the current API surface. |

## Reading order

If you've finished the guide and want to see code:

1. **Start with [`counter/`](counter/)** — the smallest possible app. Establishes the basic shape.
2. **Then [`login/`](login/)** — adds a state machine, async effects, and form handling. Single-feature scope; full shape.
3. **Then [`routing/`](routing/)** or [`ssr/`](ssr/)** — pick whichever is closer to your interest.
4. **Then [`seven_guis/`](seven_guis/)** — survey of the pattern across many UI shapes.
5. **Then [`realworld/`](realworld/)** — substantial-app shape across the widest surface in the repo.

## What examples are *not*

- **Not all immediately runnable end-to-end.** Some examples are aligned to the current implementation and some remain pedagogical sketches.
- **Not a substitute for the [specification](../docs/specification/).** Examples illustrate; the specification defines.
- **Not all uniformly polished.** The Pedagogical-sketch examples are deliberately small. The Worked-scaffold (RealWorld) prioritises breadth of API coverage over production polish.

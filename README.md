# re-frame2

A specification for a **re-frame-flavoured pattern for building SPAs**, plus a Clojure/CLJS reference implementation.

The pattern is language-agnostic, AI-first, and SSR-capable. The CLJS reference inherits a mechanical-upgrade obligation from existing re-frame applications.

## What's here

```
docs/
  specification/        Full specification
    README.md           Index of EPs, pattern docs, and supporting artefacts
    reorient.md         Pattern thesis. Read first.
    000-Vision.md       Goals, scope, and locked decisions
    002-Frames.md       The foundation EP (frames, drain, view ergonomics)
    004-Views.md        View contract; reg-view in the CLJS reference
    005-StateMachines.md  xstate-flavoured state machines
    007-Stories.md      Storybook-class tooling (post-v1)
    008-Testing.md      Testing primitives
    009-Instrumentation.md  Trace events, error contract, 10x compat
    010-Schemas.md      Malli schemas (CLJS reference)
    011-SSR.md          Server-side rendering + hydration
    012-Routing.md      URL ↔ frame state contract
    API.md              Consolidated CLJS reference API
    Construction-Prompts.md  Templates for AI-driven scaffolding
    Spec-Schemas.md     Spec-internal shape descriptions
    AI-Audit.md         EPs scored against the 8 AI-first properties
    MIGRATION.md        re-frame v1.x → re-frame2 migration prompt (CLJS)
    Pattern-Forms.md    Forms convention
    Pattern-RemoteData.md  Remote-data lifecycle convention
    conformance/        EDN fixture corpus
examples/               Worked examples
  counter/              Smallest possible app
  7guis/                All 7 7GUIs benchmark tasks in re-frame2
  login/                Full feature scaffold (events + subs + views + machine + tests)
  routing/              CP-7 worked example (3-page app)
  ssr/                  CP-9 worked example (server + client)
```

## Reading order

1. [`docs/specification/reorient.md`](docs/specification/reorient.md) — the orientation
2. The rationale docs in re-frame's existing doc set ([on-dynamics](https://day8.github.io/re-frame/on-dynamics/), [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/))
3. [`docs/specification/000-Vision.md`](docs/specification/000-Vision.md) — principles, minimal core, CLJS reference choices
4. [`docs/specification/002-Frames.md`](docs/specification/002-Frames.md) — the foundation
5. [`examples/`](examples/) — see the pattern in working code

## Status

The specification is in active drafting. The pattern thesis, goals, and minimal core are locked; per-area EPs (002, 004, 005, 008, 009, 010, 011, 012) are drafted and consistent with the thesis. The reference implementation has not yet been written.

The bar (per [reorient.md](docs/specification/reorient.md)): the spec aims to be sufficiently complete that an AI can one-shot the implementation in any host language. That bar drives every choice.

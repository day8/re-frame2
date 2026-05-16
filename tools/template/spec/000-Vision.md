# Template — Vision

> Tool [`tools/template/`](../). Artefact
> `day8/clj-template.re-frame2`.
>
> The v2 equivalent of v1's
> [`day8/re-frame-template`](https://github.com/day8/re-frame-template).

## What this tool is

`re-frame2-template` is the **front-door scaffolding tool** for new
re-frame2 apps. One command and a developer has a working CLJS app
wired against the alpha-channel `day8/re-frame2-*` coords, ready to
`shadow-cljs watch app`.

It is a [clj-new](https://github.com/seancorfield/clj-new) template
— a build-time generator, invoked via:

```bash
clojure -X:project/new :template re-frame2 :name acme/my-app
```

What lands in the user's directory is a complete CLJS project: a
substrate-adapter wired against re-frame2, a host HTML page, a
shadow-cljs build, and a worked counter mirroring
[`examples/<substrate>/counter*/`](../../../examples/).

The **canonical emit set** also includes the 2026-standard dev
ergonomics:
[`.editorconfig`](../src/clj/new/re_frame2/shared/editorconfig),
a stub [`.clj-kondo/config.edn`](../src/clj/new/re_frame2/shared/clj-kondo/config.edn),
a `dev/` tree
([`dev/user.clj`](../src/clj/new/re_frame2/shared/dev/user.clj) for the JVM-side
`(user/refresh)` workflow and
[`dev/scratch.cljs`](../src/clj/new/re_frame2/shared/dev/scratch.cljs) for REPL-driven
`(rf/dispatch …)` experiments), and a minimal plain stylesheet at
[`resources/public/css/app.css`](../src/clj/new/re_frame2/shared/resources/public/css/app.css).
Rationale: see
[ai/findings re-frame2-template-design §6](../../../ai/findings/) (Mike-locked SHIP 2026-05-12).

## Lineage

v1's `day8/re-frame-template` was a lein-template — the same posture,
the same audience, the same "one command, working app" promise. The
v2 tool inherits that lineage. The technology underneath shifts:

- **lein-template → clj-new.** clj-new is the modern successor; same
  declarative resource-templates-with-Mustache shape, consumed via
  the `clojure` CLI rather than `lein new`.
- **re-frame → re-frame2.** The generated app's deps target the alpha
  re-frame2 coords. The shape of the generated counter mirrors v2's
  reference examples, not v1's.
- **One substrate → three substrates.** v1 was Reagent only. v2's
  template ships Reagent (canonical default), UIx, and Helix
  variants. The substrate selector rides on `:edn-args` (see
  [001-Substrate-Variants.md](001-Substrate-Variants.md)).

A v1 user who knew `lein new re-frame my-app` reads the v2 invocation
and recognises the shape. That continuity is deliberate.

## Goals

- **One command, working app.** The generated tree builds with
  `shadow-cljs watch app` immediately — no follow-up edits required.
- **Substrate-agnostic shell, substrate-specific views.** Events,
  subs, and the host HTML are shared across variants; only the entry
  point (`core.cljs`), the view (`views.cljs`), and the substrate
  adapter coord differ.
- **Counter as canonical example.** The generated counter is the
  same shape the developer reads about in [Guide chapter 03
  — Your first app](../../../docs/guide/03-your-first-app.md).
  What the template emits is what the guide walks through.
- **Lockstep with the reference implementation's pins.** The
  shadow-cljs / react pins the template emits track
  `implementation/package.json` — the smoke-tested combination is
  what users get.

## Non-goals

- **Branching feature toggles** (include-10x? include-routing?,
  etc.). The first cut keeps the template flat — one substrate
  flag, no other choices. Branching is reserved for the day
  choices materially exceed clj-new's substitution capability
  ([DESIGN-RATIONALE](DESIGN-RATIONALE.md) §clj-new vs CLI).

  **Exception (rf2-t009p):** branching flags are permitted when
  they enable an *optional shared scaffolding* whose absence would
  force the user into hand-wiring known idioms — currently
  `:include-story?` (emits a `counter_with_stories`-shaped story
  scaffold alongside the live app). Each such flag is justified
  in DESIGN-RATIONALE alongside the no-other-branching default.
- **Bundling Story by default.** [`tools/story/`](../../story/)
  is the Storybook-class playground for re-frame2; the template
  does **not** pre-wire it on the default path. The opt-in
  `:include-story?` flag (see the exception above) is the
  supported on-ramp for users who want the playground scaffolded;
  rationale and shape live in
  [DESIGN-RATIONALE](DESIGN-RATIONALE.md) §No-Story-yet.
- **Multi-frame scaffolds.** Frames (Spec 002) are a runtime
  concern. The template emits a single-frame app; the user reads
  Guide chapter 06 to add more.
- **Server-side hosting.** No backend; the template is a pure CLJS
  SPA. SSR scaffolding (Spec 011) lands as a separate template
  variant if/when SSR matures.

## Cross-references

- [`tools/README.md`](../../README.md) — the tools/ convention and
  the per-tool spec/ folder convention.
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the three
  shipped variants.
- [002-Generated-Shape.md](002-Generated-Shape.md) — the file tree
  the template emits.
- [API.md](API.md) — the consolidated public invocation surface.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — WHY each major call
  was made.
- [Guide chapter 03 — Your first app](../../../docs/guide/03-your-first-app.md)
  — the worked-example throughline the template's generated counter
  aligns with.

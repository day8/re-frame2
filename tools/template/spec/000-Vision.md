# Template — Vision

> Tool [`tools/template/`](../). Artefact `day8/re-frame2-template`
> (git-coord distribution; canonical reference
> `io.github.day8/re-frame2-template`).
>
> The v2 equivalent of v1's
> [`day8/re-frame-template`](https://github.com/day8/re-frame-template).

## What this tool is

`re-frame2-template` is the **front-door scaffolding tool** for new
re-frame2 apps. One command and a developer has a working CLJS app
wired against the alpha-channel `day8/re-frame2-*` coords, ready to
`shadow-cljs watch app`.

It is a [deps-new](https://github.com/seancorfield/deps-new) template
— a build-time generator with a programmatic body, invoked via:

```bash
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app
```

What lands in the user's directory is a complete CLJS project: a
substrate-adapter wired against re-frame2, a host HTML page, a
shadow-cljs build, and a worked counter mirroring
[`examples/<substrate>/counter*/`](../../../examples/).

The **canonical emit set** also includes the 2026-standard dev
ergonomics:
[`.editorconfig`](../resources/day8/re_frame2_template/_shared/editorconfig),
a stub
[`.clj-kondo/config.edn`](../resources/day8/re_frame2_template/_shared/clj-kondo/config.edn),
a `dev/` tree
([`dev/user.clj`](../resources/day8/re_frame2_template/root/dev/user.clj) for the JVM-side
`(user/refresh)` workflow and
[`dev/scratch.cljs`](../resources/day8/re_frame2_template/root/dev/scratch.cljs) for REPL-driven
`(rf/dispatch …)` experiments), and a minimal plain stylesheet at
[`resources/public/css/app.css`](../resources/day8/re_frame2_template/root/resources/public/css/app.css).
Rationale: see
`ai/findings/re-frame2-template-design.md` §6 (gitignored working note; Mike-locked SHIP 2026-05-12).

## Lineage

v1's `day8/re-frame-template` was a lein-template — the same posture,
the same audience, the same "one command, working app" promise. The
v2 tool inherits that lineage. The technology underneath shifts:

- **lein-template → deps-new.** deps-new is the modern successor;
  programmatic template body (data-fn / template-fn / post-process-fn
  hooks) consumed via the `clojure -Tnew create` CLI rather than
  `lein new`.
- **Clojars → git-coord.** v1 published to Clojars; v2 ships via
  git-tagged commits on `day8/re-frame2-template`. Consumers resolve
  the template through deps-new's `:template io.github.day8/...`
  form, which clones the repo at the tag (or HEAD). The tagged
  commit IS the artefact; no Maven packaging step.
- **re-frame → re-frame2.** The generated app's deps target the
  alpha re-frame2 coords. The shape of the generated counter
  mirrors v2's reference examples, not v1's.
- **One substrate → three substrates.** v1 was Reagent only. v2's
  template ships Reagent (canonical default), UIx, and Helix
  variants. The substrate selector is a top-level k/v argument
  (`:substrate :uix`); see
  [001-Substrate-Variants.md](001-Substrate-Variants.md).

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
  what users get. The in-template lockstep guard
  ([`test/day8/re_frame2_template/version_lockstep_test.clj`](../test/day8/re_frame2_template/version_lockstep_test.clj))
  enforces this on every release.

## Non-goals

- **Branching feature toggles** beyond the locked v1 set. The
  template ships **three flags total**: `:include-story?`, `:css`,
  and `:include-ssr?`. Branching is reserved for the day choices
  materially exceed deps-new's substitution capability
  ([DESIGN-RATIONALE](DESIGN-RATIONALE.md) §deps-new vs CLI).

  The three locked flags are permitted because each enables an
  *optional shared scaffolding* whose absence would force the user
  into hand-wiring known idioms:

  - `:include-story?` (rf2-t009p, shipped today) — emits a
    `counter_with_stories`-shaped story scaffold alongside the
    live app. Reagent-only in v1.
  - `:css :tailwind` (rf2-gthro, deferred until gating bead
    flips) — Tailwind v4 in place of the default plain-CSS
    `app.css`.
  - `:include-ssr?` (rf2-0m5ea, deferred until gating bead
    flips) — SSR scaffolding per Spec 011.

  Resist further proliferation — every additional flag requires
  explicit DESIGN-RATIONALE justification.
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
  SPA. SSR scaffolding (Spec 011) lands behind the `:include-ssr?`
  flag once rf2-0m5ea validates.

## Distribution

The template is distributed via **git-coord** — consumers resolve
the template by pointing deps-new at a git URL + (optionally) a tag:

```bash
# Latest main
clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app

# Pinned to a specific release tag
clojure -Tnew create :template io.github.day8/re-frame2-template#template-v0.0.1.alpha :name acme/my-app
```

No Clojars artefact. The tagged commit IS the artefact. The
release pipeline (`.github/workflows/template-release.yml`) cuts
a GitHub Release per `template-v<VERSION>` tag push; that's the
publication moment from the consumer's perspective.

Initial home is `tools/template/` inside the `day8/re-frame2`
monorepo. Final home is a dedicated `day8/re-frame2-template` repo;
the split is rf2-7jgkv (rf2-dolpf §4).

## Cross-references

- [`tools/README.md`](../../README.md) — the tools/ convention and
  the per-tool spec/ folder convention.
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the three
  shipped variants.
- [002-Generated-Shape.md](002-Generated-Shape.md) — the file tree
  the template emits.
- [003-DepsNew-Rebuild-Plan.md](003-DepsNew-Rebuild-Plan.md) — the
  migration plan (clj-new + Clojars → deps-new + git-coord) that
  established the current shape.
- [API.md](API.md) — the consolidated public invocation surface.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — WHY each major call
  was made.
- [Guide chapter 03 — Your first app](../../../docs/guide/03-your-first-app.md)
  — the worked-example throughline the template's generated counter
  aligns with.

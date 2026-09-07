# Template — Vision

> Tool [`tools/template/`](../). Artefact `day8/re-frame2-template`
> (git-coord distribution; canonical reference
> `io.github.day8/re-frame2-template`).
>
> The v2 equivalent of v1's
> [`day8/re-frame-template`](https://github.com/day8/re-frame-template).

For a monorepo checkout, start with the
[local-development invocation](API.md#local-development-invocation).
The `io.github.day8/re-frame2-template` commands below describe the post-split
distribution target; they are not the checkout's invocation. The API also
records the pre-publication framework-coordinate rewrite needed before watch.

## What this tool is

`re-frame2-template` is the **front-door scaffolding tool** for new
re-frame2 apps. One command and a developer has a small, working CLJS
app wired against the `day8/re-frame2-*` coords, ready to
`shadow-cljs watch app`.

It is a [deps-new](https://github.com/seancorfield/deps-new) template
— a build-time generator with a programmatic body, invoked via:

```bash
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app
```

What lands is a twelve-file counter SPA: `deps.edn`, `shadow-cljs.edn`,
`package.json`, a short `README.md`, `.gitignore`, the host page and its
stylesheet, `core.cljs` / `events.cljs` / `subs.cljs` / `views.cljs`,
and one focused `events_test.cljs`. A programmer, or the agent working
with them, can read it in one sitting, run it, test it, release it, and
replace the counter with their first feature. See
[002-Generated-Shape.md](002-Generated-Shape.md).

The template has **one selector**: `:substrate`, `:reagent` by default or
`:uix`. Both values emit the same manifest; the substrate swaps
`deps.edn`, `core.cljs` and `views.cljs`, because that choice changes
authored source and adapter wiring. Nothing else is a choice at scaffold
time.

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
  re-frame2 coords. The shape of the generated counter mirrors v2's
  reference examples, not v1's.
- **One substrate → a substrate menu.** v1 was Reagent only. v2's
  template ships Reagent (the default) and UIx. The substrate selector
  is a top-level k/v argument (`:substrate :uix`); see
  [001-Substrate-Variants.md](001-Substrate-Variants.md).

A v1 user who knew `lein new re-frame my-app` reads the v2 invocation
and recognises the shape. That continuity is deliberate.

## Goals

- **One command, working app.** The generated tree builds with
  `shadow-cljs watch app` immediately — no follow-up edits required.
- **Small enough to read and replace.** The scaffold contains the
  counter dataflow and the lifecycle facts needed to edit it, and
  nothing that the app has not asked for. Everything else — devtools,
  the component playground, schemas, HTTP, SSR, styling frameworks,
  linters, CI — attaches afterwards through its own documented recipe,
  which the generated README's next-steps section links.
- **Substrate-agnostic shell, substrate-specific views.** Events,
  subs, the build config and the host page are shared; only the entry
  point, the view and the substrate's coordinates differ.
- **Counter as canonical example.** The generated counter is the
  same shape the developer reads about in [the Guide —
  app-db](../../../docs/core/app-db.md). What the template emits is what
  the guide walks through.
- **Lockstep with the reference implementation's pins.** The
  shadow-cljs / React pins the template emits track
  `implementation/package.json`, and the framework pin tracks the
  repo-root `VERSION` — the smoke-tested combination is what users
  get. The in-template lockstep guard
  ([`test/day8/re_frame2_template/version_lockstep_test.clj`](../test/day8/re_frame2_template/version_lockstep_test.clj))
  enforces this on every release.

## Non-goals

- **Feature flags, profiles, wizards.** `:substrate` is the one and
  only selector. The three feature flags the first deps-new cut
  carried (Story, SSR, Tailwind CSS) are gone, and no `:include-xray?`
  or `:minimal?` replaces them; a second template or a prompt sequence
  is out of scope in the same way. Every capability
  beyond the counter is a post-generation step, and a future substrate
  arrives as a new VALUE of `:substrate`, never as a second key. See
  [DESIGN-RATIONALE §10](DESIGN-RATIONALE.md) for why.
- **Bundling devtools or the playground by default.**
  [Xray](../../xray/) and [Story](../../story/) each attach through their
  own installation page in a few edits; the scaffold links those pages
  rather than pre-wiring either.
- **Multi-frame scaffolds.** Frames (Spec 002) are a runtime concern.
  The template emits a single-frame app.
- **Server-side hosting.** The scaffold is a pure client-side SPA.
  SSR (Spec 011) is learned from its own docs and examples.

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
monorepo. Final home is a dedicated `day8/re-frame2-template` repo; see
[005-Repo-Split.md](005-Repo-Split.md) for the remaining procedure.

## Cross-references

- [`tools/README.md`](../../README.md) — the tools/ convention and
  the per-tool spec/ folder convention.
- [001-Substrate-Variants.md](001-Substrate-Variants.md) — the two
  shipped substrates.
- [002-Generated-Shape.md](002-Generated-Shape.md) — the file tree
  the template emits.
- [API.md](API.md) — the consolidated public invocation surface.
- [DESIGN-RATIONALE.md](DESIGN-RATIONALE.md) — WHY each major call
  was made.
- [Guide — app-db](../../../docs/core/app-db.md)
  — the worked-example throughline the template's generated counter
  aligns with.

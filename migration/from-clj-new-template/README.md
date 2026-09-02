# Migration — clj-new template → deps-new template

> **Type:** Migration
> A user-facing migration note for developers who scaffolded a
> previous-generation re-frame2 app via the retired
> `clojure -X:project/new :template re-frame2` invocation
> (`day8/clj-template.re-frame2` on Clojars). The current template
> is `day8/re-frame2-template` — a deps-new template living in-tree
> at [`tools/template/`](../../tools/template/) and distributed as
> a git-coord (planned external repo
> `github.com/day8/re-frame2-template` — see
> [`tools/template/spec/005-Repo-Split.md`](../../tools/template/spec/005-Repo-Split.md)).

## TL;DR

| Phase | Invocation |
|---|---|
| **Old** (clj-new + Clojars; retired 2026-05-20) | `clojure -X:project/new :template re-frame2 :name acme/my-app` |
| **New** (deps-new + git-coord) | `clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app` |

No action is required on existing scaffolded apps — the
template's role ends at emit time, so the source tree it generated
for you has no compile-time or runtime knowledge of the template
artefact. Apps scaffolded under the clj-new template continue to
build and run unchanged.

This doc is for the next time you reach for the template — to
scaffold a new app, or to remind yourself what shape the template
emits. The invocation surface changed.

## What changed

### The template framework

[clj-new](https://github.com/seancorfield/clj-new) →
[deps-new](https://github.com/seancorfield/deps-new). Both
maintained by sean-corfield; deps-new is the current-generation
Clojure scaffolder. The template's programmatic body
(`data-fn` / `template-fn` / `post-process-fn`) replaces the
clj-new-era Mustache-only substitution shape.

### The distribution channel

Clojars → git-coord. The published artefact is no longer
`day8/clj-template.re-frame2` on Clojars; it is a tagged commit of
the [`tools/template/`](../../tools/template/) artefact (planned
external repo `github.com/day8/re-frame2-template`).
`clojure -Tnew create` resolves the template via deps-new's
git-coord lookup (`io.github.*` triggers an auto-git-clone of the
named GitHub repo).

The old Clojars artefact (`day8/clj-template.re-frame2`) is frozen
at its last clj-new release. Older versions remain resolvable for
legacy users — we just stop pushing new versions. New work uses the
git-coord shape.

### The invocation form

The substrate selector moves from `:edn-args` to a top-level k/v pair:

```bash
# OLD — clj-new + :edn-args
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :uix]'

# NEW — deps-new + top-level k/v
clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app \
        :substrate :uix
```

deps-new takes top-level args directly. The clj-new-era
`:edn-args` pass-through bag was a clj-new harness constraint
(`create` stripped unknown top-level args before invoking the
template); deps-new has no analogous behaviour, so the indirection
retires.

## One selector

The clj-new template carried a gated Story flag beside `:substrate`, and
the first deps-new cut grew that into three feature flags (Story, SSR,
Tailwind). All three are gone (rf2-zq34m, 2026-09-02): the template
emits one small counter SPA and `:substrate` is its only argument.

| Arg | Values | Default |
|---|---|---|
| `:substrate` | `:reagent` / `:uix` | `:reagent` |

Passing a retired flag fails closed as an unknown key — there is no
alias, warning or compatibility path; this note is the migration
record. Story, SSR and Tailwind each attach to a generated app through
their own docs (the generated README links the Story and Xray pages).
See [tools/template/spec/DESIGN-RATIONALE.md](../../tools/template/spec/DESIGN-RATIONALE.md)
for why.

## What didn't change

- The substrate set. Reagent (default) / UIx. Adding a
  new substrate is still the same shape: drop a sub-tree under
  `_<substrate>/`, add a `case` clause in `template-fn`, ship the
  per-substrate test.
- The substrate-agnostic shell. `events.cljs`, `subs.cljs`, the
  host HTML, `.gitignore` and the build configs — emitted identically
  across the substrates.
- The counter throughline. Every variant emits a working
  counter, mirroring [the Guide introduction — a tiny counter
  application](https://github.com/day8/re-frame2/blob/main/docs/core/introduction.md)
  and the canonical `examples/<substrate>/counter*/` apps.
- Pin lockstep. `:rf2-version`, `:shadow-version`,
  `:react-version` continue to track
  `implementation/package.json` and the re-frame2 alpha release
  cadence.

## Tag-pinning

deps-new's git-coord supports tag-pinning out of the box. To
scaffold against a specific template release:

```bash
clojure -Tnew create :template io.github.day8/re-frame2-template#template-v0.0.1.alpha :name acme/my-app
```

(The `template-v…` prefix matches the template's tag-on-release CI,
`.github/workflows/template-release.yml`.)

Tag-pinning is the recommended shape for reproducible scaffolds —
the team gets the same emitted tree every time, independent of any
later template releases.

## Get the `-Tnew` tool

If you don't have deps-new's `-Tnew` tool installed globally:

```bash
clojure -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```

See [deps-new's README](https://github.com/seancorfield/deps-new#installation)
for the full install reference.

## Cross-references

- [tools/template/spec/000-Vision.md](../../tools/template/spec/000-Vision.md)
  — what the template is for; lineage from v1.
- [tools/template/spec/API.md](../../tools/template/spec/API.md)
  — the consolidated public invocation surface.
- [tools/template/spec/005-Repo-Split.md](../../tools/template/spec/005-Repo-Split.md)
  — the monorepo → external repo migration procedure.
- [tools/template/spec/DESIGN-RATIONALE.md](../../tools/template/spec/DESIGN-RATIONALE.md)
  — WHY deps-new + git-coord over clj-new + Clojars.
- [migration/from-re-frame-v1/README.md](../from-re-frame-v1/README.md)
  — the framework-level migration note (re-frame v1.x → re-frame2);
  separate concern from the template invocation surface.

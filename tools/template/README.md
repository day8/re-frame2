# re-frame2-template

> `day8/re-frame2-template` is the scaffolding tool for new re-frame2 apps
> and the v2 equivalent of v1's
> [`day8/re-frame-template`](https://github.com/day8/re-frame-template).
>
> Spec: [`spec/`](./spec/) contains the current contract and the design
> rationale.
>
> Implementation shape: [deps-new](https://github.com/seancorfield/deps-new)
> template with a programmatic body. Distribution is tag-based git-coord,
> not Clojars. The old `day8/clj-template.re-frame2` Clojars artefact is
> frozen at its last clj-new release.
>
> Release pipeline:
> [`.github/workflows/template-release.yml`](../../.github/workflows/template-release.yml)
> cuts a GitHub Release on every `template-v<VERSION>` tag push;
> [`VERSION`](./VERSION) carries the template's own version sequence
> (independent of the framework-wide repo-root `VERSION`).
>
> Repo home: the template currently lives in-tree at
> [`tools/template/`](./) in the re-frame2 monorepo. Its planned permanent
> home is the external
> repo `github.com/day8/re-frame2-template`; the split out to that
> external repo is a Mike-operator handoff. The deps-new coord shifts from
> `day8/re-frame2-template` (current `:local/root` shape, monorepo) to
> `io.github.day8/re-frame2-template` (git-coord, external repo) once
> the split completes — see [`spec/005-Repo-Split.md`](./spec/005-Repo-Split.md)
> for the migration procedure.

This tool generates a small, working re-frame2 application: a counter
SPA in twelve files that a programmer — or the agent working with them
— can read in one sitting, run, test, release, and replace with their
first feature. It has one selector, `:substrate`, which is `:reagent`
by default or `:uix`; nothing else is a choice at scaffold time.
Devtools, the component playground, schemas, HTTP, styling frameworks,
linters and CI all attach afterwards through their own documented
recipes, which the generated README links.

## Quick start

> Pre-split status (current): the dedicated
> `github.com/day8/re-frame2-template` repo does not exist yet — the
> template still lives in-tree under `tools/template/` in the
> re-frame2 monorepo. Until the split
> lands, the only working invocation is the `:local/root` route
> against a checkout of this repo. The published
> `io.github.day8/re-frame2-template` git-coord form is not yet a
> viable path — deps-new would clone the (nonexistent) external
> repo and fail to find the template body (see
> [`spec/005-Repo-Split.md` §4](./spec/005-Repo-Split.md)). It is
> documented below under [Post-split (future)](#post-split-future)
> only.

The working invocation, against a checkout of this repo. A
`:local/root` is resolved against the command's working directory, so
name the checkout absolutely — the relative `"tools/template"` form
only works from the monorepo root, and from the directory you are
scaffolding into it fails with `Local lib day8/re-frame2-template not
found`:

```bash
# <RE_FRAME2> = absolute path of your re-frame2 checkout,
# e.g. /home/you/code/re-frame2 or C:/Users/you/code/re-frame2

# Reagent — the default
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "<RE_FRAME2>/tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template :name acme/my-app

# UIx
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "<RE_FRAME2>/tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template \
        :name acme/my-app \
        :substrate :uix
```

That assumes the standard `-Tnew` tool is installed per
[deps-new's README](https://github.com/seancorfield/deps-new#installation).

`:local/root` puts the template's `src/` and `resources/` on the
classpath, so deps-new resolves the hooks ns and the template body via
the classpath. The name is `day8/re-frame2-template` (not
`io.github.day8/re-frame2-template`) because the `io.github.*` prefix
would trigger deps-new's auto-git-clone before classpath lookup —
bypassing the local-root checkout (and, pre-split, cloning a repo that
doesn't exist yet).

Any other template argument — a typo, or one of the retired flags —
fails closed with `:rf.error/template-unknown-flag` before a file is
written. See [`spec/API.md`](./spec/API.md#errors) for the error table.

### Post-split (future)

Once the template is split out to its dedicated
`github.com/day8/re-frame2-template` repo,
the steady-state invocation becomes the published git-coord form —
deps-new's `auto-git-url` mechanism clones the external repo at the
requested tag and runs the template hooks (the tagged commit is the
artefact; no Maven / Clojars resolution):

```bash
# Post-split steady state (does NOT work pre-split)
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app

# Pinned to a specific release tag
clojure -Tnew create \
        :template io.github.day8/re-frame2-template#template-v0.0.1.alpha \
        :name acme/my-app
```

Then:

```bash
cd my-app
npm install
npx shadow-cljs watch app
# open http://localhost:8280
```

(The emitted `:shadow` alias is deps-only — the npx wrapper supplies
`-m shadow.cljs.devtools.cli` itself, so the pure-JVM form is
`clojure -M:shadow -m shadow.cljs.devtools.cli watch app`.)

Until `day8/re-frame2` is published, the emitted `deps.edn` names
coordinates that are not on Clojars yet; point them at a checkout with
`:local/root` before the first watch, as the template's own behavioural
tier does.

You should see the counter — the same shape walked through in
[Guide — app-db](../../docs/core/app-db.md).

## Testing the template

The JVM test suite exercises the template end-to-end for each substrate:

```bash
cd tools/template
clojure -M:test
```

The behavioural tier — a real shadow-cljs compile, the focused test
under Node, the emitted page in Chromium, then the `:advanced` release
and the emitted page in Chromium again over that optimised bundle, for
every substrate — is opt-in with `RF2_TEMPLATE_RUN_EMITTED_TESTS=1` and
needs a primed `implementation/node_modules` plus a Playwright Chromium.
See [Principles §P7](./spec/Principles.md#p7--tested-end-to-end-per-substrate).

## Spec

The normative contract lives under [`spec/`](./spec/):

| File | What's in it |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What the tool is for; lineage from v1; goals; non-goals. |
| [`spec/001-Substrate-Variants.md`](./spec/001-Substrate-Variants.md) | The one selector; Reagent / UIx; coercion; how a substrate is added. |
| [`spec/002-Generated-Shape.md`](./spec/002-Generated-Shape.md) | The twelve-file manifest; the resource tree; substitution variables; the hot-reload contract. |
| [`spec/005-Repo-Split.md`](./spec/005-Repo-Split.md) | Procedure for the remaining monorepo-to-external-repo split. |
| [`spec/Principles.md`](./spec/Principles.md) | The design principles (build-time only, counter as canonical example, substrate-agnostic shell, top-level k/v selection, tested per substrate). |
| [`spec/API.md`](./spec/API.md) | The consolidated public invocation surface and the error table. |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | WHY each major decision (deps-new + git-coord over clj-new + Clojars, top-level k/v plumbing, the substrate menu, counter as example, one selector and one manifest, pin lockstep) and the decisions this shape superseded. |

## Cross-references

- [`tools/README.md`](../README.md) — the tools/ convention and the
  bundle-isolation contract this template satisfies trivially.
- [`spec/Construction-Prompts.md`](../../spec/Construction-Prompts.md)
  — AI-driven scaffolding prompts; the template is for human-driven
  scaffolding, the prompts cover the agent-driven path.
- [`examples/core/counter/`](../../examples/core/counter/) — the
  canonical counter the Reagent variant mirrors.
- [`examples/substrates/uix/counter/`](../../examples/substrates/uix/counter/) — UIx
  counter.
- [`docs/xray/01-installation.md`](../../docs/xray/01-installation.md) and
  [`docs/story/index.md`](../../docs/story/index.md) — the two
  post-generation recipes the generated README links.

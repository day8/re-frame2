# re-frame2-template

> `day8/re-frame2-template` is the scaffolding tool for new re-frame2 apps
> and the v2 equivalent of v1's
> [`day8/re-frame-template`](https://github.com/day8/re-frame-template).
>
> **Spec:** [`spec/`](./spec/) contains the current contract, design
> rationale, and migration records.
>
> **Implementation shape:** [deps-new](https://github.com/seancorfield/deps-new)
> template with a programmatic body. Distribution is tag-based git-coord,
> not Clojars. The old `day8/clj-template.re-frame2` Clojars artefact is
> frozen at its last clj-new release.
>
> **Release pipeline:**
> [`.github/workflows/template-release.yml`](../../.github/workflows/template-release.yml)
> cuts a GitHub Release on every `template-v<VERSION>` tag push;
> [`VERSION`](./VERSION) carries the template's own version sequence
> (independent of the framework-wide repo-root `VERSION`).
>
> **Repo home:** the template currently lives in-tree at
> [`tools/template/`](./) in the re-frame2 monorepo. Its planned permanent
> home is the external
> repo `github.com/day8/re-frame2-template`; the split out to that
> external repo is a Mike-operator handoff. The deps-new coord shifts from
> `day8/re-frame2-template` (current `:local/root` shape, monorepo) to
> `io.github.day8/re-frame2-template` (git-coord, external repo) once
> the split completes — see [`spec/005-Repo-Split.md`](./spec/005-Repo-Split.md)
> for the migration procedure.

This tool generates a fresh re-frame2 application skeleton. It is the
front door for new users: one command and you have a working CLJS app
wired against the alpha-channel `day8/re-frame2-*` coords, ready to
`shadow-cljs watch app`.

## Quick start

> **Pre-split status (current):** the dedicated
> `github.com/day8/re-frame2-template` repo does not exist yet — the
> template still lives in-tree under `tools/template/` in the
> re-frame2 monorepo. Until the split
> lands, the only working invocation is the `:local/root` route
> against a checkout of this repo. The published
> `io.github.day8/re-frame2-template` git-coord form is **not yet a
> viable path** — deps-new would clone the (nonexistent) external
> repo and fail to find the template body (see
> [`spec/005-Repo-Split.md` §4](./spec/005-Repo-Split.md)). It is
> documented below under [Post-split (future)](#post-split-future)
> only.

The working invocation, against a checkout of this repo (run from the
repo root):

```bash
# Reagent — the canonical substrate (default)
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template :name acme/my-app

# UIx
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template \
        :name acme/my-app \
        :substrate :uix

# Helix
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template \
        :name acme/my-app \
        :substrate :helix

# re-frame.ui — EXPERIMENTAL (the first-party compiled-view substrate)
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template \
        :name acme/my-app \
        :substrate :ui

# Reagent, with the Story playground scaffold
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template \
        :name acme/my-app \
        :include-story? true
```

That assumes the standard `-Tnew` tool is installed per
[deps-new's README](https://github.com/seancorfield/deps-new#installation).

`:local/root "tools/template"` puts the template's `src/` and
`resources/` on the classpath, so deps-new resolves the hooks ns and
the template body via the classpath. The name is
`day8/re-frame2-template` (not `io.github.day8/re-frame2-template`)
because the `io.github.*` prefix would trigger deps-new's
auto-git-clone before classpath lookup — bypassing the local-root
checkout (and, pre-split, cloning a repo that doesn't exist yet).

`:include-story? true` is currently **Reagent-only**. Combining it with
`:substrate :uix`, `:substrate :helix`, or `:substrate :ui` throws
`:rf.error/template-include-story-reagent-only`. UIx + Helix Story
variants follow once those adapters' Story coverage matches Reagent's.

### `:substrate :ui` (EXPERIMENTAL)

`:substrate :ui` scaffolds the app on **re-frame.ui**, the first-party
compiled-view substrate: `defview` views, value-shaped subscription
reads (`(sub [:counter/value])` — nothing to deref), event-vector
handlers (`{:on-click [:counter/increment]}`), and `ui/mount`. The
emitted `shadow-cljs.edn` carries the one load-bearing build-hook
setting from
[docs/core/how-to/install-re-frame-ui.md](../../docs/core/how-to/install-re-frame-ui.md)
(no `:cache-blockers` line — the S6 cut-over removed the tax), and the
deps are the minimal consumer shape (`day8/re-frame2` +
`day8/re-frame2-ui` + `day8/re-frame2-schemas`; no Xray). It is
**EXPERIMENTAL** (2026-07-19 template-menu ruling): the surface may
change between alpha releases, and the Reagent / UIx adapter scaffolds
remain the supported defaults. `:include-story?` / `:include-ssr?` do
not combine with it; `:css :tailwind` does.

### `:css :tailwind`

`:css :tailwind` swaps the default plain-CSS scaffold for Tailwind v4
(zero build step in dev). `index.html` loads the
`@tailwindcss/browser@4` Play CDN compiler and carries the Tailwind v4
CSS-first source **inline** in a `<style type="text/tailwindcss">`
block — `@import "tailwindcss";` plus design tokens in `@theme { … }`
(Tailwind v4 has no `tailwind.config.js`). That inline block is the
compiler's input: the Play CDN compiler reads **only** inline
`<style type="text/tailwindcss">` nodes — it never sees an external
`<link>` stylesheet — so authoring Tailwind there is what makes it
compile. `resources/public/css/app.css` stays **ordinary native CSS**
for the app shell + Xray-host layout (a bare `@import "tailwindcss"`
there would just resolve to a bogus `/css/tailwindcss` request and
`@theme` would be silently dropped). Omit `:css` (or pass nothing) for
the plain-CSS default. The flag is substrate-invariant — and composes
with `:include-ssr?`, whose live shell injects the same
`<style type="text/tailwindcss">` source block. A bogus value
(e.g. `:css :tailwnd`) **fails closed** with
`:rf.error/template-bad-css-flag`.

Before shipping, move to the compiled `@tailwindcss/cli` build: lift the
`<style type="text/tailwindcss">` block's contents into a `.css` entry
file, compile it to a static stylesheet, link that, and drop the CDN
`<script>` + the inline block. The exact dev→prod transition (and the
matching CSP tightening — dropping the jsdelivr origin and
`'unsafe-inline'`) is documented in the generated `index.html`'s
comments; serve the tightened policy as a response header per the
generated README's "Production hardening".

```bash
# Reagent, with Tailwind v4
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template \
        :name acme/my-app \
        :css :tailwind
```

Any unrecognised template flag — including a typo such as
`:include-story` (missing the `?`) — fails closed with
`:rf.error/template-unknown-flag`. See
[`spec/API.md`](./spec/API.md#args-reference) for the full flag-set
status and the error table.

### Post-split (future)

Once the template is split out to its dedicated
`github.com/day8/re-frame2-template` repo,
the steady-state invocation becomes the published git-coord form —
deps-new's `auto-git-url` mechanism clones the external repo at the
requested tag and runs the template hooks (the tagged commit IS the
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
clojure -M:shadow watch app   # or: npx shadow-cljs watch app
# open http://localhost:8280
```

You should see the counter — the same shape walked through in
[Guide — app-db](../../docs/core/app-db.md).

## Testing the template

The JVM test suite exercises the template end-to-end for each substrate:

```bash
cd tools/template
clojure -M:test
```

See [Principles §P7](./spec/Principles.md#p7--tested-end-to-end-per-substrate).

## Spec

The normative contract lives under [`spec/`](./spec/):

| File | What's in it |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What the tool is for; lineage from v1; goals; non-goals. |
| [`spec/001-Substrate-Variants.md`](./spec/001-Substrate-Variants.md) | Reagent / UIx / Helix variants; the top-level k/v invocation form; substrate coercion. |
| [`spec/002-Generated-Shape.md`](./spec/002-Generated-Shape.md) | The file tree emitted; the resource tree; substitution variables. |
| [`spec/003-DepsNew-Rebuild-Plan.md`](./spec/003-DepsNew-Rebuild-Plan.md) | Completed migration record from clj-new and Clojars to deps-new and git-coord. |
| [`spec/004-SSR-Validation-Report.md`](./spec/004-SSR-Validation-Report.md) | Completed validation record for the shipped SSR variant. |
| [`spec/005-Repo-Split.md`](./spec/005-Repo-Split.md) | Procedure for the remaining monorepo-to-external-repo split. |
| [`spec/Principles.md`](./spec/Principles.md) | The design principles (build-time only, counter as canonical example, substrate-agnostic shell, top-level k/v selection). |
| [`spec/API.md`](./spec/API.md) | The consolidated public invocation surface. |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | WHY each major decision (deps-new + git-coord over clj-new + Clojars, top-level k/v plumbing, three substrates in v1, counter as example, no-Story-yet, pin lockstep). |

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
- [`examples/substrates/helix/counter/`](../../examples/substrates/helix/counter/) —
  Helix counter.

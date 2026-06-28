# re-frame2-template

> `day8/re-frame2-template` — scaffolding tool for new re-frame2 apps
> (rf2-lrtc; rf2-dolpf). The v2 equivalent of v1's
> [`day8/re-frame-template`](https://github.com/day8/re-frame-template).
>
> **Spec:** [`spec/`](./spec/) — the tool's normative contract per the
> per-tool [spec/ folder convention (rf2-bfax)](../README.md#per-tool-spec-folder-convention-rf2-bfax).
>
> **Implementation shape:** [deps-new](https://github.com/seancorfield/deps-new)
> template with programmatic body. Distribution: git-coord (tag-based),
> not Clojars (rf2-dolpf §2.5 — `day8/clj-template.re-frame2` on Clojars
> is now frozen at its last clj-new release; older versions remain
> resolvable for legacy users).
>
> **Release pipeline:**
> [`.github/workflows/template-release.yml`](../../.github/workflows/template-release.yml)
> cuts a GitHub Release on every `template-v<VERSION>` tag push;
> [`VERSION`](./VERSION) carries the template's own version sequence
> (independent of the framework-wide repo-root `VERSION`).
>
> **Repo home:** the template currently lives in-tree at
> [`tools/template/`](./) in the re-frame2 monorepo while the rebuild
> settles (rf2-dolpf §4). Its planned permanent home is the external
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
> re-frame2 monorepo (rf2-dolpf §4 / rf2-7jgkv). Until the split
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

`:include-story? true` is **Reagent-only in v1** — combining it with
`:substrate :uix` or `:substrate :helix` throws
`:rf.error/template-include-story-reagent-only`. UIx + Helix Story
variants follow once those adapters' Story coverage matches Reagent's.

### Deferred flags

`:css` (e.g. `:tailwind`) and `:include-ssr?` are reserved in the v1
flag set but **not yet implemented** — they are gated on rf2-gthro
(Tailwind v4 verification) and rf2-0m5ea (SSR validation)
respectively. Passing one today **fails closed** with
`:rf.error/template-unsupported-flag` (naming the flag and its gating
bead) rather than silently scaffolding a vanilla app that lacks the
feature. Any other unrecognised template flag — including a typo such
as `:include-story` (missing the `?`) — fails closed with
`:rf.error/template-unknown-flag`. See
[`spec/API.md`](./spec/API.md#args-reference) for the flag-set status
and the error table.

### Post-split (future)

Once the template is split out to its dedicated
`github.com/day8/re-frame2-template` repo (rf2-dolpf §4 / rf2-7jgkv),
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
[Guide quickstart — a counter in five minutes](../../docs/core/quickstart.md).

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
| [`spec/003-DepsNew-Rebuild-Plan.md`](./spec/003-DepsNew-Rebuild-Plan.md) | Migration plan from clj-new + Clojars to deps-new + git-coord (rf2-dolpf). |
| [`spec/004-SSR-Validation-Report.md`](./spec/004-SSR-Validation-Report.md) | SSR reference-impl validation report (rf2-0m5ea); gates the `:include-ssr?` flag work. |
| [`spec/005-Repo-Split.md`](./spec/005-Repo-Split.md) | Migration procedure for the monorepo → external repo split (rf2-dolpf §4 / rf2-7jgkv). |
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

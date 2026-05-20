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
> **Repo home:** the template's permanent home is
> [`github.com/day8/re-frame2-template`](https://github.com/day8/re-frame2-template).
> It lives under `tools/template/` in the re-frame2 monorepo while the
> rebuild settles (rf2-dolpf §4); the split out to the external repo is
> a Mike-operator handoff. The deps-new coord shifts from
> `day8/re-frame2-template` (current `:local/root` shape, monorepo) to
> `io.github.day8/re-frame2-template` (git-coord, external repo) once
> the split completes — see [`spec/005-Repo-Split.md`](./spec/005-Repo-Split.md)
> for the migration procedure.

This tool generates a fresh re-frame2 application skeleton. It is the
front door for new users: one command and you have a working CLJS app
wired against the alpha-channel `day8/re-frame2-*` coords, ready to
`shadow-cljs watch app`.

## Quick start

The canonical invocation:

```bash
# Reagent — the canonical substrate (default)
clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app

# UIx, with all v1 flags
clojure -Tnew create \
        :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :substrate :uix \
        :include-story? true \
        :css :tailwind \
        :include-ssr? true

# Pinned to a specific release tag
clojure -Tnew create \
        :template io.github.day8/re-frame2-template#template-v0.0.1.alpha \
        :name acme/my-app
```

That assumes the standard `-Tnew` tool is installed per
[deps-new's README](https://github.com/seancorfield/deps-new#installation).

`io.github.day8/re-frame2-template` triggers deps-new's
`auto-git-url` mechanism — deps-new clones the
`github.com/day8/re-frame2-template` repo (or HEAD-of-`day8/re-frame2`
during the transition period, before the rf2-7jgkv repo split lands)
at the requested tag and runs the template hooks. The tagged commit
IS the artefact; no Maven / Clojars resolution.

Until the `day8/re-frame2-template` repo is split out (rf2-dolpf §4 /
rf2-7jgkv), `io.github.day8/re-frame2-template` will resolve against
this monorepo path — pinning via `#template-v…` still works for
reproducible scaffolding.

For local development against a checkout of this repo, use the
`:local/root` route instead:

```bash
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template :name acme/my-app
```

(`day8/re-frame2-template`, not `io.github.day8/re-frame2-template`,
because the `io.github.*` prefix would trigger deps-new's auto-git-clone
before classpath lookup — bypassing the local-root checkout.)

Then:

```bash
cd my-app
npm install
clojure -M:shadow watch app   # or: npx shadow-cljs watch app
# open http://localhost:8280
```

You should see the counter — the same shape walked through in
[Guide chapter 03 — Your first app](../../docs/guide/03-your-first-app.md).

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
- [`examples/reagent/counter/`](../../examples/reagent/counter/) — the
  canonical counter the Reagent variant mirrors.
- [`examples/uix/counter_uix/`](../../examples/uix/counter_uix/) — UIx
  counter.
- [`examples/helix/counter_helix/`](../../examples/helix/counter_helix/) —
  Helix counter.

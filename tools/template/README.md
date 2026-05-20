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

This tool generates a fresh re-frame2 application skeleton. It is the
front door for new users: one command and you have a working CLJS app
wired against the alpha-channel `day8/re-frame2-*` coords, ready to
`shadow-cljs watch app`.

## Quick start

Once the template repo is published (rf2-dolpf §3 — git-coord release
pipeline) the canonical invocation is:

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
        :template io.github.day8/re-frame2-template#v0.0.1 \
        :name acme/my-app
```

That assumes the standard `-Tnew` tool is installed per
[deps-new's README](https://github.com/seancorfield/deps-new#installation).

Until the git-coord release lands, use the `:local/root` route to
exercise the template from a checkout of this repo:

```bash
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template :name acme/my-app
```

(`day8/re-frame2-template`, not `io.github.day8/re-frame2-template`,
because the `io.github.*` prefix triggers deps-new's auto-git-clone
before classpath lookup. The published repo flips the on-disk path so
the steady-state invocation resolves via the git-clone.)

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
| [`spec/Principles.md`](./spec/Principles.md) | The design principles (build-time only, counter as canonical example, substrate-agnostic shell, top-level k/v selection). |
| [`spec/API.md`](./spec/API.md) | The consolidated public invocation surface. |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | WHY each major decision (deps-new vs clj-new vs CLI, top-level k/v plumbing, three substrates in v1, counter as example, no-Story-yet, pin lockstep). |

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

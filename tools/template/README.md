# re-frame2-template

> `day8/clj-template.re-frame2` — scaffolding tool for new re-frame2 apps
> (rf2-lrtc). The v2 equivalent of v1's
> [`day8/re-frame-template`](https://github.com/day8/re-frame-template).
>
> **Spec:** [`spec/`](./spec/) — the tool's normative contract per the
> per-tool [spec/ folder convention (rf2-bfax)](../README.md#per-tool-spec-folder-convention-rf2-bfax).

This tool generates a fresh re-frame2 application skeleton. It is the
front door for new users: one command and you have a working CLJS app
wired against the alpha-channel `day8/re-frame2-*` coords, ready to
`shadow-cljs watch app`.

## Quick start

Once published to Clojars (per the standard release workflow), invoke
the template via clj-new. The substrate selector rides on `:edn-args`
because clj-new's `create` strips unknown top-level args
([why](./spec/DESIGN-RATIONALE.md#2--edn-args-over-a-top-level-substrate-key)):

```bash
# Reagent — the canonical substrate (default)
clojure -X:project/new :template re-frame2 :name acme/my-app

# UIx
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :uix]'

# Helix
clojure -X:project/new :template re-frame2 :name acme/my-app \
        :edn-args '[:substrate :helix]'
```

That assumes the standard `:project/new` alias is wired in
`~/.clojure/deps.edn` per
[clj-new's README](https://github.com/seancorfield/clj-new). If you
don't have that alias, see [API §Inline invocation](./spec/API.md#inline-invocation).

Until the alpha publish lands on Clojars, use the `:local/root` route
to exercise the template from a checkout of this repo:

```bash
clojure -Sdeps '{:deps {day8/clj-template.re-frame2
                        {:local/root "tools/template"}}}' \
        -X clj-new/create :template re-frame2 :name acme/my-app
```

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

The JVM test exercises the template end-to-end for each substrate:

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
| [`spec/001-Substrate-Variants.md`](./spec/001-Substrate-Variants.md) | Reagent / UIx / Helix variants; the `:edn-args` invocation form; substrate coercion. |
| [`spec/002-Generated-Shape.md`](./spec/002-Generated-Shape.md) | The file tree emitted; the resource tree; substitution variables. |
| [`spec/Principles.md`](./spec/Principles.md) | The design principles (build-time only, counter as canonical example, substrate-agnostic shell, `:edn-args` selection). |
| [`spec/API.md`](./spec/API.md) | The consolidated public invocation surface. |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | WHY each major decision (clj-new vs deps-new vs CLI, `:edn-args` plumbing, three substrates in v1, counter as example, no-Story-yet, pin lockstep). |

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

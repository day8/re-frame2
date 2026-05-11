# Template — Substrate Variants

> Capability doc. The template ships three substrate variants; this
> file documents each, the invocation form, and the rationale for the
> `:edn-args` plumbing.

## The three variants

| Substrate | Beads | Default? | View library | Generated `core.cljs` shape |
|---|---|---|---|---|
| `:reagent` | (canonical) | yes | Reagent | Reagent component + `r/render` |
| `:uix` | rf2-3yij | no | UIx | UIx defui + `uix/render-root` |
| `:helix` | rf2-2qit | no | Helix | Helix defnc + `createRoot` |

Reagent is the canonical default — the substrate every re-frame and
re-frame2 example targets first. UIx and Helix are equally supported;
the choice is the developer's, surfaced via the `:edn-args` selector
described below.

## Invocation form

The substrate selector rides on `:edn-args` because clj-new's
`create` strips unknown top-level args (this is a harness constraint
discovered during implementation — see
[DESIGN-RATIONALE](DESIGN-RATIONALE.md) §edn-args-not-top-level):

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

`:edn-args` is a flat alternating-key-value vector: clj-new passes
its contents through to the template's entry fn as `& args`. The
template wraps the args in `apply hash-map` and reads `:substrate`.

## Substrate coercion

The template accepts the substrate arg as a keyword, a string (with
or without leading `:`), or a symbol — clj-new harnesses around the
wider Clojure tooling ecosystem hand keywords through inconsistently,
and tolerating all three keeps the command line forgiving:

```clojure
(coerce-substrate :uix)        ;; => :uix
(coerce-substrate "uix")       ;; => :uix
(coerce-substrate ":uix")      ;; => :uix
(coerce-substrate 'uix)        ;; => :uix
(coerce-substrate :unknown)    ;; => throws — clear message
(coerce-substrate nil)         ;; => :reagent (default)
```

Anything not in `#{:reagent :uix :helix}` throws an `ex-info` with
the offending value and the set of valid substrates in `ex-data`.

## What each variant emits

All three variants emit the same top-level project shape — see
[002-Generated-Shape.md](002-Generated-Shape.md). The substrate
choice swaps:

- `core.cljs` — the entry point. Reagent uses `reagent.dom/render`;
  UIx uses `uix.dom/render-root`; Helix uses `react-dom/client`'s
  `createRoot`.
- `views.cljs` — the counter view. Reagent uses plain hiccup;
  UIx uses `$` with `defui`; Helix uses `defnc` and `d/...`
  elements.
- `deps.edn` — the substrate-adapter coord changes:
  `day8/re-frame2-reagent`, `day8/re-frame2-uix`, or
  `day8/re-frame2-helix`. The runtime coord `day8/re-frame2` is
  identical across variants.
- `shadow-cljs.edn` and `package.json` — react / react-dom pins
  are identical; the substrate's own npm dep (where applicable)
  is added.

The substrate-agnostic shell — `events.cljs`, `subs.cljs`,
`README.md`, `.gitignore`, `resources/public/index.html` — lives
under the `shared/` resource sub-tree and is emitted identically
across all three variants.

## The counter throughline

Every variant emits a working counter. The counter is the same
shape the developer reads about in:

- [Guide chapter 02 — Your first app](../../../docs/guide/02-your-first-app.md)
  — the friendly walkthrough.
- [`examples/reagent/counter/`](../../../examples/reagent/counter/) —
  the canonical Reagent counter.
- [`examples/uix/counter_uix/`](../../../examples/uix/counter_uix/) —
  the UIx counter.
- [`examples/helix/counter_helix/`](../../../examples/helix/counter_helix/) —
  the Helix counter.

What the template emits is what the guide walks through (rf2-2kzw
throughline). A developer who runs `clojure -X:project/new
:template re-frame2 ...` and then reads Guide chapter 02 sees the
same code in both places.

## Future variants

Reserved space — not implemented:

- **Re-frame2 + Story scaffolding.** Adds `tools/story/`'s
  registrations and a worked `counter_with_stories`. Lands once
  Story stabilises post-1.0 (see
  [DESIGN-RATIONALE](DESIGN-RATIONALE.md) §No-Story-yet).
- **SSR.** Once Spec 011 has a Reagent-side reference implementation
  the template can ship an SSR variant of the counter.
- **reagent-slim.** Once the substrate-portable reagent-slim adapter
  exists (rf2-3sk6) the template can ship it as a fourth substrate
  choice.
- **TypeScript port.** Per Spec 000 — re-frame2 is a pattern, not a
  CLJS library. A `create-re-frame2-app` style npm template is
  reserved for a future iteration.

Adding a variant requires:

1. A new entry in the `valid-substrates` set in
   `src/clj/new/re_frame2.clj`.
2. A new resource sub-tree at
   `src/clj/new/re_frame2/<substrate>/` (matching the existing
   reagent/uix/helix shape).
3. A test entry in `test/clj/new/re_frame2/core_test.clj`.

The substrate-agnostic `shared/` tree is reused as-is.

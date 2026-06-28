# Template — Substrate Variants

> Capability doc. The template ships three substrate variants; this
> file documents each, the invocation form, and substrate coercion.

## The three variants

| Substrate | Default? | View library | Generated `core.cljs` shape |
|---|---|---|---|
| `:reagent` | yes | Reagent | Reagent component + `r/render` |
| `:uix` | no | UIx | UIx defui + `uix/render-root` |
| `:helix` | no | Helix | Helix defnc + `createRoot` |

Reagent is the canonical default — the substrate every re-frame and
re-frame2 example targets first. UIx and Helix are equally supported;
the choice is the developer's, surfaced via the `:substrate` top-level
k/v argument.

## Invocation form

The substrate selector is a **top-level k/v argument** on the
`-Tnew create` invocation:

```bash
# Reagent — the canonical substrate (default)
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app

# UIx
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :substrate :uix

# Helix
clojure -Tnew create :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :substrate :helix
```

deps-new passes the args through to the template's `data-fn` directly
as a Clojure map — no `:edn-args` pass-through bag, no Mustache
nesting. `data-fn` reads `:substrate` off the data map and threads
the resulting keyword through to `template-fn`'s `case` on the
substrate.

## Substrate coercion

The template requires the substrate arg as a keyword. deps-new's
top-level k/v contract guarantees the value reaches `data-fn` as a
keyword, so the template does not accept stringified or symbol forms
— passing one is a registration error and throws with a clear
message naming the valid set:

```clojure
(coerce-substrate :uix)        ;; => :uix
(coerce-substrate :unknown)    ;; => throws — clear message
(coerce-substrate nil)         ;; => :reagent (default)
(coerce-substrate "uix")       ;; => throws — must be a keyword
(coerce-substrate 'uix)        ;; => throws — must be a keyword
```

Anything not in `#{:reagent :uix :helix}` (or anything not a keyword)
throws an `ex-info` with the offending value and the set of valid
substrates in `ex-data`. This tightening (rf2-h0imw) replaces the
earlier kw/string/symbol forgiving-input posture; see
[DESIGN-RATIONALE.md §8](DESIGN-RATIONALE.md) for the rationale.

## What each variant emits

All three variants emit the same top-level project shape — see
[002-Generated-Shape.md](002-Generated-Shape.md). The substrate
choice swaps:

- `core.cljs` — the entry point. Reagent uses
  `reagent.dom.client/create-root` + `.render` (the React 19
  client-Root API); UIx uses `uix.dom/render-root`; Helix uses
  `react-dom/client`'s `createRoot`.
- `views.cljs` — the counter view. Reagent uses plain hiccup;
  UIx uses `$` with `defui`; Helix uses `defnc` and `d/...`
  elements.
- `deps.edn` — only the substrate-adapter coord changes:
  `day8/re-frame2-reagent`, `day8/re-frame2-uix`, or
  `day8/re-frame2-helix`. The remaining runtime coords are identical
  across variants: `day8/re-frame2` (core), `day8/re-frame2-schemas`
  (so `schema.cljs`'s whole-app-db schema validates rather than
  soft-passing per Spec 010), and `day8/re-frame2-xray` (the in-app
  devtools panel — see
  [002 §Xray devtools](002-Generated-Shape.md#xray-devtools)).
`shadow-cljs.edn` and `package.json` are **not** substrate-specific —
react / react-dom are the only npm deps for every variant, and the
`:app` build's `:devtools {:preloads …}` carries
`day8.re-frame2-xray.preload` identically across variants — so they
live under `_shared/` and emit once (the only per-variant difference
is a cosmetic substrate label filled by `{{substrate-label}}`).

The substrate-agnostic shell is emitted identically across all three
variants. It splits across two resource sub-trees (see
[002-Generated-Shape.md §Resource tree](002-Generated-Shape.md#resource-tree-template-side)):
the renamed-/flag-switched-at-emit sources — `events.cljs`,
`subs.cljs`, `schema.cljs`, `shadow-cljs.edn`, `package.json`, and
the dotfiles (`.gitignore` etc.) — live under `_shared/`; the
default-placement files (`README.md`,
`resources/public/index.html`, `resources/public/css/app.css`,
`dev/*`) are bulk-copied from `root/`. `schema.cljs` is
substrate-agnostic: the whole-app-db Malli schema and `reg-app-schema`
registration are the same regardless of view library.

## The counter throughline

Every variant emits a working counter. The counter is the same
shape the developer reads about in:

- [Quickstart — your first app](../../../docs/core/quickstart.md)
  — the friendly walkthrough.
- [`examples/core/counter/`](../../../examples/core/counter/) —
  the canonical Reagent counter.
- [`examples/substrates/uix/counter/`](../../../examples/substrates/uix/counter/) —
  the UIx counter.
- [`examples/substrates/helix/counter/`](../../../examples/substrates/helix/counter/) —
  the Helix counter.

What the template emits is what the guide walks through. A
developer who runs `clojure -Tnew create :template
io.github.day8/re-frame2-template ...` and then reads Guide chapter
03 sees the same code in both places.

## Future variants

Reserved space — not implemented:

- **SSR.** Once Spec 011 has a Reagent-side reference implementation
  the template can ship an SSR variant of the counter via the
  locked `:include-ssr?` flag (rf2-0m5ea gates the flag work).
- **reagent-slim.** Once the substrate-portable reagent-slim adapter
  exists the template can ship it as a fourth substrate choice.
- **TypeScript port.** Per Spec 000 — re-frame2 is a pattern, not a
  CLJS library. A `create-re-frame2-app` style npm template is
  reserved for a future iteration.

Adding a substrate requires:

1. A new entry in `valid-substrates` in
   [`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj).
2. A new resource sub-tree at
   `resources/day8/re_frame2_template/_<substrate>/` (matching the
   existing `_reagent` / `_uix` / `_helix` shape).
3. A new `case` clause in `template-fn`'s per-substrate transform
   block.
4. A test entry in each of `test/day8/re_frame2_template/`'s test
   files (per-substrate runs in the existing deftests).

The substrate-agnostic `_shared/` tree is reused as-is.

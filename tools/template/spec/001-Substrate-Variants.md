# Template — Substrate Variants

> Capability doc. The template ships two adapter substrate variants
> plus the EXPERIMENTAL `:ui` compiled-view variant; this file
> documents each, the invocation form, and substrate coercion.

## The variants

| Substrate | Default? | View library | Generated `core.cljs` shape |
|---|---|---|---|
| `:reagent` | yes | Reagent | Reagent component + `r/render` |
| `:uix` | no | UIx | UIx defui + `uix/render-root` |
| `:ui` | no — **EXPERIMENTAL** | re-frame.ui (compiled views) | `defview` root + `ui/mount` |

Reagent is the canonical default — the substrate every re-frame and
re-frame2 example targets first. UIx is equally supported;
the choice is the developer's, surfaced via the `:substrate` top-level
k/v argument. `:ui` is the first-party re-frame.ui compiled-view
substrate, added EXPERIMENTAL at W8 per the 2026-07-19 template-menu
ruling (as amended by the S7/W13 Helix removal, rf2-d6epb) — see
[§EXPERIMENTAL `:ui` variant](#experimental-ui-variant).

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

Anything not in `#{:reagent :uix :ui}` (or anything not a
keyword) throws an `ex-info` with the offending value and the set of
valid substrates in `ex-data`. See
[DESIGN-RATIONALE.md §8](DESIGN-RATIONALE.md) for the rationale.

## What each variant emits

Both adapter variants emit the same top-level project shape — see
[002-Generated-Shape.md](002-Generated-Shape.md). The substrate
choice swaps:

- `core.cljs` — the entry point. Reagent uses
  `reagent.dom.client/create-root` + `.render` (the React 19
  client-Root API); UIx uses `uix.dom/render-root`.
- `views.cljs` — the counter view. Reagent uses plain hiccup;
  UIx uses `$` with `defui`.
- `deps.edn` — the substrate-adapter coord changes
  (`day8/re-frame2-reagent` or `day8/re-frame2-uix`), and the Xray
  coord is **Reagent-only**: `day8/re-frame2` (core) and
  `day8/re-frame2-schemas` (so `schema.cljs`'s whole-app-db schema
  validates rather than soft-passing per Spec 010) ride both adapter
  variants, while `day8/re-frame2-xray` (the in-app devtools panel —
  see [002 §Xray devtools](002-Generated-Shape.md#xray-devtools))
  rides the Reagent variant only. Xray's panel shell mounts through
  the ratom-family substrates today, so the `:uix` scaffold ships no
  panel it cannot mount (rf2-p6f6u ruling, 2026-07-22; element-substrate
  support is parked behind a demand trigger).
`shadow-cljs.edn` and `package.json` still live under `_shared/` and
emit once for the adapter variants; the per-variant deltas ride flat
substitution values — the cosmetic substrate label
(`{{substrate-label}}`) plus the Reagent-only Xray wiring:
`{{xray-preload}}` fills the `:app` build's `:devtools {:preloads …}`
slot with `day8.re-frame2-xray.preload` on Reagent (empty for `:uix`),
and `{{xray-npm-deps}}` carries the `@xyflow/react` + `elkjs` npm deps
the Xray machine canvas compiles against (empty for `:uix` and `:ui` —
react / react-dom are the only npm deps everywhere else). The
EXPERIMENTAL `:ui` variant additionally emits its **own**
`shadow-cljs.edn` (see below) while still riding the shared
`package.json`.

The substrate-agnostic shell is emitted identically across every
variant. It splits across two resource sub-trees (see
[002-Generated-Shape.md §Resource tree](002-Generated-Shape.md#resource-tree-template-side)):
the renamed-/flag-switched-at-emit sources — `events.cljs`,
`subs.cljs`, `schema.cljs`, `shadow-cljs.edn`, `package.json`, and
the dotfiles (`.gitignore` etc.) — live under `_shared/`; the
default-placement files (`README.md`,
`resources/public/index.html`, `resources/public/css/app.css`,
`dev/*`) are bulk-copied from `root/`. `schema.cljs` is
substrate-agnostic: the whole-app-db Malli schema and `reg-app-schema`
registration are the same regardless of view library.

## EXPERIMENTAL `:ui` variant

`:substrate :ui` (added at W8 per the 2026-07-19 template-menu ruling;
bead rf2-vbjls) scaffolds the app on **re-frame.ui**, the first-party
compiled-view substrate. It is marked EXPERIMENTAL everywhere it
surfaces — the template menu (`template.edn` description), the emitted
README, and the post-generate note — because the substrate's surface
may change between alpha releases; the adapter variants remain the
supported defaults.

The emitted app is the minimal consumer shape whose install contract of
record is
[docs/core/how-to/install-re-frame-ui.md](../../../docs/core/how-to/install-re-frame-ui.md):

- **`deps.edn`** — `day8/re-frame2` + `day8/re-frame2-ui` +
  `day8/re-frame2-schemas` (the app `:require`s namespaces from core
  and ui, and a direct `:require` deserves a direct dependency). **No
  Xray coord** — the minimal consumer shape ships no devtools panel;
  the `[data-rf-xray-host]` layout host in the shared `index.html`
  stays empty and collapses via its `:empty` CSS rule.
- **`shadow-cljs.edn`** — the variant's own config (not the `_shared/`
  one): the same `:app` + `:test` builds, plus the ONE load-bearing
  setting — `:build-defaults {:build-hooks
  [(re-frame.ui.compiler.build-hook/hook)]}` — and **no
  `:cache-blockers` line** (the S6 cut-over, rf2-u53yy.1, removed the
  tax). No Xray preload.
- **`core.cljs`** — `(rf/init! ui/adapter)`, the schema attach, and a
  `ui/mount` of a `ui/frame-root`-wrapped root view.
- **`views.cljs`** — `defview` compiled views: `(sub [:counter/value])`
  reads a VALUE (nothing to deref) and `{:on-click
  [:counter/increment]}` dispatches through the compiler-wired event
  vector door.
- The dataflow half (`events.cljs` / `subs.cljs` / `schema.cljs` /
  `events_test.cljs`) is the same `_shared/` slice set every variant
  emits, and an EXPERIMENTAL-marked README replaces the SPA README.

`:include-story?` and `:include-ssr?` remain Reagent-only and throw on
`:ui`; `:css :tailwind` composes (the overlay swaps root-level files).

## The counter throughline

Every variant emits a working counter. The counter is the same
shape the developer reads about in:

- [Guide — app-db](../../../docs/core/app-db.md)
  — the friendly walkthrough.
- [`examples/core/counter/`](../../../examples/core/counter/) —
  the canonical Reagent counter.
- [`examples/substrates/uix/counter/`](../../../examples/substrates/uix/counter/) —
  the UIx counter.

What the template emits is what the guide walks through. A
developer who runs `clojure -Tnew create :template
io.github.day8/re-frame2-template ...` and then reads Guide chapter
03 sees the same code in both places.

## SSR variant (`:include-ssr? true`, Reagent-only)

`:include-ssr?` is a live Reagent-only flag. The validation record is in
004-SSR-Validation-Report.

Under `:include-ssr? true` the Reagent counter is emitted as a shared
`core.cljc` (the same code runs the JVM render path and the CLJS
hydration path), a `server.clj` Ring/Jetty host wired to
`re-frame.ssr.ring/ssr-handler`, and a headless `ssr_test.clj` gate. The
per-slice CLJS sources (`events.cljs` / `subs.cljs` / `schema.cljs` /
`views.cljs`) are folded into `core.cljc`, so they are not emitted
separately. The flag is **mutually exclusive** with `:include-story?`
(004-SSR-Validation-Report §2.1). A UIx SSR variant follows once the
per-substrate adapter demonstrates parity (Spec 011 §Streaming SSR and the
report §7 out-of-scope list track the deferred surface).

## Future variants

Reserved space — not implemented:

- **UIx SSR.** The Spec 011 contract is substrate-agnostic
  (`render-to-string` consumes hiccup), but the worked example + the
  ssr-ring test corpus are Reagent-driven; the UIx SSR variant
  lands once that adapter demonstrates parity.
- **reagent-slim.** Gated on reagent-slim GA / first published
  artefact — the same trigger that gates Story's substrate-enum
  addition and UI-shell migration (see
  `tools/story/spec/DESIGN-RATIONALE.md` §inline-substrate-failures);
  nothing in this repo has published yet. Once fired the template can
  ship reagent-slim as a fourth substrate choice.
- **TypeScript port.** Per Spec 000 — re-frame2 is a pattern, not a
  CLJS library. A `create-re-frame2-app` style npm template is
  reserved for a future iteration.

Adding a substrate requires:

1. A new entry in `valid-substrates` in
   [`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj).
2. A new resource sub-tree at
   `resources/day8/re_frame2_template/_<substrate>/` (matching the
   existing `_reagent` / `_uix` shape).
3. A new `case` clause in `template-fn`'s per-substrate transform
   block.
4. A test entry in each of `test/day8/re_frame2_template/`'s test
   files (per-substrate runs in the existing deftests).

The substrate-agnostic `_shared/` tree is reused as-is.

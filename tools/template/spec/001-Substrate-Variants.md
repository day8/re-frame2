# Template — Substrate Variants

> Capability doc. The template ships two adapter substrate variants;
> this file documents each, the invocation form, and substrate
> coercion.

## The variants

| Substrate | Default? | View library | Generated `core.cljs` shape |
|---|---|---|---|
| `:reagent` | yes | Reagent | Reagent component + `r/render` |
| `:uix` | no | UIx | UIx defui + `uix/render-root` |

Reagent is the canonical default — the substrate every re-frame and
re-frame2 example targets first. UIx is equally supported;
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

Anything not in `#{:reagent :uix}` (or anything not a
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
`shadow-cljs.edn` and `package.json` live under `_shared/` and emit
once for both variants; the per-variant deltas ride flat
substitution values — the cosmetic substrate label
(`{{substrate-label}}`) plus the Reagent-only Xray wiring:
`{{xray-preload}}` fills the `:app` build's `:devtools {:preloads …}`
slot with `day8.re-frame2-xray.preload` on Reagent (empty for `:uix`),
and `{{xray-npm-deps}}` carries the `@xyflow/react` + `elkjs` npm deps
the Xray machine canvas compiles against (empty for `:uix` —
react / react-dom are the only npm deps everywhere else).

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
  ship reagent-slim as a third substrate choice.
- **Hicasso.** Gated on a published `day8/re-frame2-hicasso` artefact —
  which now means **the repository's first `v*` tag**, not a merge.
  `release.yml` carries a `deploy-hicasso` stage (added by rf2-gra70,
  gated on `deploy-core` plus the whole `deploy-leaf` matrix, because
  Hicasso's published `:deps` name a second in-repo artefact besides
  core), so the release wiring exists. What does not exist is a release:
  `git ls-remote --tags origin` returns nothing, so no re-frame2
  coordinate has ever reached Clojars. An emitted `deps.edn` names
  `{:mvn/version "{{rf2-version}}"}`, so scaffolding this variant today
  would still hand a new user a project whose very first command fails
  to resolve its dependencies (rf2-48rk3). Cutting that tag is the
  operator's act; the trigger to watch is the tag, and after it a
  `day8/re-frame2-hicasso` that actually resolves.

  (For the record, since an earlier draft of this section miscounted:
  `release.yml` deploys **thirteen** coordinates — `day8/re-frame2`
  core, eleven `deploy-leaf` matrix values, and `ssr-ring` in its own
  ordered job — plus `hicasso` in the stage above. The twelve
  `artefact:` rows are the non-core leaves, not the workflow's total.)

  **Hicasso is also not a substrate peer of `:reagent` and `:uix`, and
  that ordering matters.** It mints no adapter — there is no
  `:rf.adapter/hicasso` — and a Hicasso app installs the *UIx* adapter,
  then mounts boundaries through it (`(rf/init! uix-adapter/adapter)`
  then `(h/mount! …)`). What differs from the `:uix` variant is the
  AUTHORING model, not the substrate. So the open design question is
  whether this is a third value of `:substrate` at all, rather than a
  flag on the UIx variant; it is not a mechanical fourth row in the
  checklist below, and it wants a ruling rather than an implementer's
  guess.

  What the emitted app would differ by, established at source so the
  ruling has something to stand on: the per-substrate transform emits
  exactly three files, and a Hicasso variant would change **all three**.
  `deps.edn` gains `day8/re-frame2-hicasso` while keeping
  `day8/re-frame2-uix` (the adapter is still UIx's); `core.cljs` swaps
  `uix-dom/render-root` + the `frame-root` element for an explicit
  `rf/make-frame` plus `h/mount!`; and `views.cljs` is rewritten in the
  `h/defview` authoring model — hiccup with `h/sub` and intent vectors
  at `:on-click` — rather than `defui` with `$` and hooks. That is a
  variant's worth of divergence, not a flag's; but it is blocked on
  publication regardless, so the choice is recorded, not taken.

  **The two-value idiom this bead also named is now fixed** (rf2-48rk3)
  and is no longer a cost a Hicasso variant has to pay. Xray, Story and
  SSR support are DECLARED per substrate in `substrate-registry` and
  read through one fail-closed `capability` accessor, so a third
  substrate neither inherits UIx's refusal prose nor falls through to
  Reagent's Xray wiring. See §Substrate capabilities below.
- **TypeScript port.** Per Spec 000 — re-frame2 is a pattern, not a
  CLJS library. A `create-re-frame2-app` style npm template is
  reserved for a future iteration.

Adding a substrate requires:

1. A new entry in `substrate-registry` in
   [`src/day8/re_frame2_template/hooks.clj`](../src/day8/re_frame2_template/hooks.clj)
   — carrying its `:label`, `:badge-url`, **and every key in
   `substrate-capability-keys`**. `valid-substrates` is derived from
   this map, so there is one place to edit.
2. A new resource sub-tree at
   `resources/day8/re_frame2_template/_<substrate>/` (matching the
   existing `_reagent` / `_uix` shape).
3. A new `case` clause in `template-fn`'s per-substrate transform
   block. Keep it in step with the `:story?` / `:ssr?` capabilities
   declared in step 1 — the capability says the sources exist, the
   `case` is where they are named.
4. A test entry in each of `test/day8/re_frame2_template/`'s test
   files (per-substrate runs in the existing deftests).

The substrate-agnostic `_shared/` tree is reused as-is.

## Substrate capabilities

Whether a substrate gets Xray wiring, and whether `:include-story?` /
`:include-ssr?` can be honoured on it, are **declared properties** of
the substrate rather than comparisons against one substrate's name:

| Capability | `:reagent` | `:uix` | What it controls |
|---|---|---|---|
| `:xray?` | yes | no | The `{{xray-preload}}` devtools slot, the `[data-rf-xray-host]` layout host, the host CSS, Xray's machine-canvas npm deps, and the README section that promises the panel |
| `:story?` | yes | no | Whether `:include-story? true` is honoured or refused |
| `:ssr?` | yes | no | Whether `:include-ssr? true` is honoured or refused |

`capability` reads these, and **throws on an undeclared key** rather
than reading a missing key as `false`. That distinction is the point of
the design (rf2-48rk3): the previous code spelled the same Xray
question two ways — `:xray-npm-deps` asked `(= substrate :reagent)`
while every sibling `{{xray-*}}` value asked `(= substrate :uix)` — and
those agree only because the substrate set had two members. A third
substrate made them diverge, and the emitted project wired the Xray
preload, the host slot and the README promise while carrying none of
Xray's npm dependencies, so its first `shadow-cljs watch app` died on a
missing JS dependency. There is now one predicate and no default arm to
fall through.

The refusal messages follow the same rule: they name the substrates
that currently *do* support the feature (from the registry) instead of
hardcoding "UIx variants follow once…", which was wrong text for any
refused substrate that is not UIx.

`template_test.clj` holds the guards — every entry declares every
capability key, the Xray substitution values stay coherent for a
hypothetical third substrate declared either way, an undeclared
capability fails closed, and the refusal prose names the supported set.

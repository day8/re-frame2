# reagent-slim adapter

Maven artefact: `day8/reagent-slim` (per IMPL-SPEC §1 DECISION-1 — the `re-frame2-` prefix is dropped on this coord). Target: `reagent2.core` (the slim Reagent fork shipped from this directory).

Public ns:

- **In-tree (this repo):** `re-frame.adapter.reagent-slim`. The monorepo shadow-cljs build adds both `adapters/reagent/src` and `adapters/reagent-slim/src` to the same classpath, so the two adapters carry distinct ns suffixes to avoid clashing.
- **Published Maven artefact:** `re-frame.adapter.reagent` — i.e. the canonical adapter ns (no `-slim` suffix). Downstream apps depend on exactly one of `{day8/re-frame2-reagent, day8/reagent-slim}`, so the ns is single-source per app and a downstream `(:require [re-frame.adapter.reagent :as ra])` works regardless of which coord their `deps.edn` pins.

The publication-time rename happens on a throwaway runner checkout in `.github/workflows/release.yml` (the `Rename adapter ns at publication` step in the `deploy-leaf` matrix job, gated `if: matrix.leaf == 'reagent-slim'`) — it mv's `reagent_slim.cljs` → `reagent.cljs` and rewrites the `(ns ...)` declaration before clein packages the jar. The in-tree source is never modified.

Reagent-slim is the trimmed-down Reagent variant re-frame2's reference adapter targets when re-com's full surface isn't required. Same observable view semantics as the full Reagent adapter; smaller dependency footprint; an empirically-capped Form-3 surface.

See [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) for the design decisions (why this exists, the React 19 floor, the 7-key Form-3 cap, the narrowed `convert-prop-value`, the SSR split). See [`IMPL-SPEC.md`](IMPL-SPEC.md) for the implementation contract.

## Imperative escape hatch — when you need a DOM lifecycle

Most views are Form-1 / Form-2; the canonical surface is `reg-view`. A small fraction of views genuinely need to own a piece of host DOM lifecycle:

- **Library bridges** — Framer Motion, GSAP, D3 transitions, Vega-Embed, Mapbox, ag-grid, CodeMirror, SpreadJS — anything imperative that needs a DOM element plus mount / update / unmount hooks.
- **DOM-listener-bearing widgets** — `addEventListener` for `animationend`, `transitionend`, `resize`, intersection / mutation observers, custom DOM protocols.
- **Error boundaries** — `componentDidCatch` is React's class-component-only contract; the slim adapter's 7-key cap permits it precisely because no Form-1 substitute exists.
- **Pre-commit DOM measurement** — scroll-position restoration via `:get-snapshot-before-update`.

The escape hatch is **Form-3 via `reagent2.core/create-class`** (the slim equivalent of full Reagent's `reagent.core/create-class`), registered through `re-frame.core/reg-view*` per [Spec 004 §Form-3](../../../spec/004-Views.md#form-3-class--out-of-scope-for-the-macro).

### Spelling

```clojure
(reagent2.core/create-class
  {:display-name              "<name>"
   :reagent-render            (fn [props] ...)
   :component-did-mount       (fn [this] ...)
   :component-did-update      (fn [this prev-props prev-state snapshot] ...)
   :component-will-unmount    (fn [this] ...)
   :get-snapshot-before-update (fn [this prev-props prev-state] ...)
   :component-did-catch       (fn [this err info] ...)})
```

The slim adapter enforces a **7-key cap** — these six keys plus `:display-name`. Any other key throws at `create-class` call time. The cap is empirical (the union of what Day8's four production codebases use, plus nothing else); see [`DESIGN-RATIONALE.md` §4](DESIGN-RATIONALE.md) for the framing.

### Outer / inner pattern, with all worked examples

For the full enumeration — the 7-key cap, migration recipes for the keys it excludes, the canonical library-bridge pattern (Google Maps and Vega-Embed walked through end-to-end), error-boundary pattern, scroll-restoration pattern, and the "when NOT to use Form-3" antipattern callouts — see [`FORM-3.md`](FORM-3.md). That document is the single source of truth for Form-3 in the slim adapter; this README is the index.

The skeleton, briefly:

```clojure
(ns my-app.charts
  (:require [re-frame.core :as rf]
            [reagent2.core :as r]))

;; Inner — Form-3, owns the library lifecycle. Registered via reg-view*.
(rf/reg-view* :my-app.charts/vega-inner
  (fn [_initial-spec]
    (let [el-ref        (atom nil)
          vega-instance (atom nil)
          dispatch      (rf/dispatcher)]   ;; captured at render — carries the frame
      (r/create-class
        {:display-name           "vega-inner"
         :reagent-render         (fn [_spec] [:div {:ref #(reset! el-ref %)}])
         :component-did-mount    (fn [this] ...)
         :component-did-update   (fn [this _ _ _] ...)
         :component-will-unmount (fn [_this] (some-> @vega-instance .finalize))}))))

;; Outer — Form-1, reads subs, hands props to the inner. Registered via reg-view.
(rf/reg-view chart-panel []
  (let [spec @(subscribe [:dashboard/current-spec])]
    [(rf/view :my-app.charts/vega-inner) spec]))
```

Four things matter (same as full Reagent — the slim adapter's lifecycle semantics are observably identical):

1. **Per-mount state in closure atoms.** Don't `def` / `defonce` at top-level.
2. **`:component-will-unmount` is mandatory** — without it the library instance and any listeners it attached leak across re-mounts and hot-reloads.
3. **`(rf/dispatcher)` is captured during render**, not inside the lifecycle callback. The dispatcher carries the surrounding frame; lifecycle callbacks fire after commit but the closure is established at render-time.
4. **Subscriptions live in the outer or in `:reagent-render`** — reactive context is undefined inside `:component-did-mount` / `:component-did-update` / `:component-will-unmount`.

### Cross-references

- [`FORM-3.md`](FORM-3.md) — the 7-key cap, all worked examples (library bridge, error boundary, scroll restoration), and migration recipes for out-of-cap keys.
- [`DESIGN-RATIONALE.md` §4 The 7-key Form-3 cap](DESIGN-RATIONALE.md) — why the cap is what it is (empirical, not aspirational).
- [`IMPL-SPEC.md` §6](IMPL-SPEC.md) — the implementation contract: validation throw shape, React-class wrapper, lifecycle key → method mapping.
- [Spec 004 §Form-3 (class — out of scope for the macro)](../../../spec/004-Views.md#form-3-class--out-of-scope-for-the-macro) — why Form-3 ships through `reg-view*` rather than the macro.
- [Spec 004 §Views MUST NOT attach native DOM event listeners from render bodies](../../../spec/004-Views.md#views-must-not-attach-native-dom-event-listeners-from-render-bodies) and [§Views MUST NOT own imperative library lifecycles directly](../../../spec/004-Views.md#views-must-not-own-imperative-library-lifecycles-directly) — bare `addEventListener` in a render body leaks listeners and silently routes dispatches to `:rf/default`; library lifecycles belong in Form-3.
- [Spec 002 §Dispatches issued from inside a handler body](../../../spec/002-Frames.md#dispatches-issued-from-inside-a-handler-body) — async callbacks escape the dynamic frame binding; capture `(rf/dispatcher)` at render-time to carry the frame.
- **Outer/inner Pattern (Pattern-OuterInner)** — the canonical home for wrapping stateful JS components (D3, Mapbox, animation libraries).

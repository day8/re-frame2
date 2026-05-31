(ns re-frame.adapter.uix
  "The UIx adapter — second canonical browser substrate, targeting UIx
  2.x (hooks-based). Per Spec 006 §CLJS reference: UIx as alternative
  substrate. Ships in `day8/re-frame2-uix`; dependency flows adapter
  → core.

  Shares the React-shaped substrate machinery (container quartet,
  derived value, render-root, render-to-string, frame-provider, use-
  subscribe, flush-views!, source-coord wrapper, warn-once-cache) with
  the Helix adapter via `re-frame.substrate.spine`. UIx-specific
  configuration: gensym prefixes, substrate name (used in warn-once
  text), and the runtime hook fns from `uix.hooks.alpha` (the
  `uix.core` macros expand to these)."
  (:require [goog.object       :as gobj]
            [uix.core          :as uix]
            [uix.hooks.alpha   :as uix-hooks]
            [re-frame.substrate.spine   :as spine]))

;; ---- shared spine wiring --------------------------------------------------

(def ^:private spine-fns
  (spine/make-react-spine
    {:substrate-name        "UIx"
     :gensym-prefix-sub     "rf-uix-sub-"
     :gensym-prefix-derived "rf-uix-derived-"
     :gensym-prefix-use-sub "rf-uix-use-sub-"
     :use-memo              uix-hooks/use-memo
     :use-callback          uix-hooks/use-callback
     :use-context           uix/use-context}))

;; ---- public surface (UIx-named) -------------------------------------------

(def set-hiccup-emitter!
  "Install a render-tree → HTML fn for use by render-to-string. Idempotent.
  UIx itself doesn't render to string in browser bundles; SSR consumers
  install the hiccup emitter explicitly (mirroring the Reagent adapter)."
  (:set-hiccup-emitter! spine-fns))

(def use-current-frame
  "UIx hook returning the current frame keyword from the surrounding
  React context, or `:rf/default` when no frame-provider sits above.
  Decision 2 mandates every React-shaped adapter resolves the same
  context, so a UIx subtree under a Reagent frame-provider sees the
  right frame and vice versa.

  React-context tier only. For the full resolution chain
  (dynamic-var → React-context → :rf/default) use `(rf/current-frame)`;
  the routed `:adapter/current-frame` hook (registered below) covers
  that chain. Per rf2-84myk."
  (:use-current-frame spine-fns))

(def ^:private spine-frame-provider
  "The shared-spine `frame-provider` fn. Destructures a CLJS-map props
  argument (`{:keys [frame children]}`). The public `frame-provider`
  below wraps it so the documented `($ frame-provider {...})` call shape
  works (see that var's docstring)."
  (:frame-provider spine-fns))

(defn- glue-uix-props
  "Reconstruct the CLJS props map from the JS object UIx's `$` hands a
  component down the lossless `uix-component-element` path. Mirrors
  `uix.core/glue-args`: the original CLJS props map is stashed under
  `argv`, and any `$`-trailing children React appended sit under
  `children`. Reading `argv` (rather than the React-level string keys)
  preserves keyword *values* losslessly — see `frame-provider`'s
  docstring for why that matters."
  [^js props]
  (cond-> (gobj/get props "argv")
    (gobj/get props "children") (assoc :children (gobj/get props "children"))))

(defn frame-provider
  "User-facing component scoping `frame-kw` to its subtree. Wraps
  children in the shared frame Context Provider. UIx call shape:

      ($ frame-provider {:frame :session
                         :children [($ header) ($ main)]})

  Per rf2-sixo: missing or `nil` `:frame` falls through to `:rf/default`.
  The three React-shaped adapters share one React Context (per rf2-3yij
  Decision 2) so a subtree under any frame-provider sees the right
  frame regardless of which substrate rendered the provider.

  Prop normalisation (rf2-8svnm — the UIx twin of the Helix rf2-9ok1s
  defect, with a UIx-specific twist). The shared-spine `frame-provider`
  is a plain CLJS fn that destructures a CLJS-map props argument. A bare
  re-export breaks under the documented `($ frame-provider {...})` shape:
  because the re-exported fn carries no `.-uix-component?` marker, UIx's
  `$` routes it through `uix.compiler.alpha/react-component-element` →
  `interpret-attrs`, which (a) hands the component a *raw JS object* with
  string keys, and — the UIx-specific twist Helix's `$` does NOT share —
  (b) *stringifies keyword prop values to their bare name*, dropping the
  namespace (`:my.ns/frame` → `\"frame\"`). So even reading the string key
  yields a namespace-less string that no longer matches the registered
  frame keyword: `:frame` effectively resolves to `:rf/default` and the
  subtree renders nothing. No throw, no warning.

  Fix (UIx-local; does not touch the shared spine): mark `frame-provider`
  with `.-uix-component?` so `$` routes it through the *lossless*
  `uix-component-element` path instead. That path stashes the original
  CLJS props map under the JS object's `argv` slot (the same channel a
  real `defui` uses) — keyword values survive intact. The body then
  reconstructs the CLJS map via `glue-uix-props` (mirroring
  `uix.core/glue-args`) and delegates to the spine. When invoked directly
  as a CLJS fn with a real map (the shape the shared test suite uses),
  it passes through unchanged. Both call shapes reach the spine fn with
  the same CLJS-map contract, keywords intact."
  [props]
  (if (map? props)
    ;; Direct CLJS-fn invocation (e.g. shared test suite) — pass through.
    (spine-frame-provider props)
    ;; `$`-supplied JS object on the lossless uix-component path — the
    ;; original CLJS map (keywords intact) lives under `argv`.
    (spine-frame-provider (glue-uix-props props))))

;; Route `$` through UIx's lossless `uix-component-element` (`argv`) path
;; rather than the keyword-stringifying `react-component-element` path.
;; See `frame-provider`'s docstring (rf2-8svnm).
(set! (.-uix-component? frame-provider) true)

(def use-subscribe
  "UIx hook that reads a re-frame subscription. Returns the current
  value; re-renders the calling component when the value changes.

  Frame resolution: reads the surrounding frame-provider's keyword via
  `use-context` (rf2-3yij Decision 2). Override via the 2-arg form to
  pin to an explicit frame-id.

  Per rf2-3yij Decision 1 the hook is named `use-subscribe` to match
  the React/UIx idiom — symmetric ergonomics to Reagent's
  `(rf/subscribe ...)` deref shape, asymmetric naming (hooks live in
  hook-named space)."
  (:use-subscribe spine-fns))

(def flush-views!
  "Flush pending UIx renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act f); with no arg, calls (act
  (fn [] nil)) to flush pending effects. Returns nil. Resolves React's
  act() across React 18 (in `react-dom/test-utils`) and React 19 (on
  the React namespace directly).

  Per rf2-3yij Decision 6: the canonical test-flush hook for UIx-based
  apps."
  (:flush-views! spine-fns))

(def wrap-view
  "Wrap a UIx-shape user component in a function component that injects
  `data-rf2-source-coord` on the rendered root DOM element (when
  `interop/debug-enabled?` is true). Returned fn has the same call
  signature as `user-fn` and is suitable for use as a UIx component
  head. Production builds elide via `interop/debug-enabled?` per
  Spec 009 §Production builds."
  (:wrap-view spine-fns))

(def clear-warned-non-dom-roots!
  "Reset the warn-once cache for non-DOM-root warnings. Tests use this
  between cases (via `make-reset-runtime-fixture` and the chained
  `:adapter/clear-warn-once-caches!` hook) so a sibling test's
  first-encounter warning cannot silently swallow a later test's same-id
  warning. Per rf2-4edk."
  (:clear-warned-non-dom-roots! spine-fns))

;; ---- adapter Var ----------------------------------------------------------

(def adapter
  "The UIx adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.uix :as uix])
      (rf/init! uix/adapter)

  See Spec 006 §CLJS reference: UIx as alternative substrate.
  Implements the same nine-fn contract as re-frame.adapter.reagent.
  Per rf2-agql there is no default-adapter registry — adapter wiring
  is explicit at the call site.

  Assembled by `spine/make-react-adapter` (rf2-ee38b.1): the 9-key
  substrate map, the five React-hook `route-hook!` calls, and the two
  chained installs (warn-once clear + SSR emitter) all live once in the
  spine — the UIx and Helix adapter wiring is byte-identical, so this
  single call replaces the former hand-copied block. The per-hook
  rationale lives at `spine/make-react-adapter`."
  (spine/make-react-adapter spine-fns :rf.adapter/uix))

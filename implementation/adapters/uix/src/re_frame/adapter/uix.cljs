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
  (:require [uix.core          :as uix :refer-macros [defui]]
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
  (dynamic-var → React-context → :rf/default) use `(rf/current-frame-id)`;
  the routed `:adapter/current-frame` hook (registered below) covers
  that chain. Per rf2-84myk."
  (:use-current-frame spine-fns))

(defui frame-provider
  "User-facing component scoping `frame-kw` to its subtree. Wraps
  children in the shared frame Context Provider. UIx call shape — the
  idiomatic `$` TRAILING-CHILDREN form, identical to every other UIx
  component and mirroring Reagent's trailing-hiccup mental model
  (rf2-7kii2):

      ($ frame-provider {:frame :session}
         ($ header)
         ($ main))

  Children ride the native `$` trailing-args channel; there is no
  `:children` prop-map key to remember (forgetting it used to drop the
  subtree silently — that footgun is gone by construction). A single
  child works too: `($ frame-provider {:frame :session} ($ app))`.

  Per rf2-sixo: missing or `nil` `:frame` falls through to `:rf/default`.
  The three React-shaped adapters share one React Context (per rf2-3yij
  Decision 2) so a subtree under any frame-provider sees the right
  frame regardless of which substrate rendered the provider.

  Native shell above the prop-marshalling seam (rf2-z7hfp — Mike-ruled
  C, MOVE THE SEAM UP). This is a NATIVE UIx `defui` component, NOT a
  re-export of a shared-spine fn handed to `$`. Because it is a real
  `defui`, UIx's `$` stamps it `.-uix-component?` and routes its props
  through the LOSSLESS `uix-component-element` (`argv`) path — `glue-args`
  reconstructs the original CLJS props map with keyword values intact
  (namespace preserved) AND folds the native trailing children into the
  `:children` key (UIx's `$`/`glue-args` contract: `($ Comp props c1 c2)`
  arrives as `{... :children #js [c1 c2]}`). The body reads `:frame` /
  `:children` off that clean CLJS map and delegates to the substrate-
  agnostic spine core `build-frame-provider-element` (frame-resolution +
  element-build).

  This replaces the former bespoke un-mangling wrapper (rf2-8svnm: a
  plain re-export plus a manual `.-uix-component?` marker and a
  `glue-uix-props` reconstruction branching on `(map? props)`). With the
  seam moved ABOVE `$`, the prop-mangling class — UIx's
  `react-component-element` → `interpret-attrs` stringifying keyword prop
  values and dropping the namespace — is impossible by construction:
  there is no plain fn under `$` for the element macro to mangle. No
  per-substrate un-mangling patch remains to drift."
  [{:keys [frame children]}]
  (spine/build-frame-provider-element frame children))

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

(def ^:no-doc clear-warned-non-dom-roots!
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
  rationale lives at `spine/make-react-adapter`.

  The native `frame-provider` `defui` component (rf2-z7hfp) is passed in
  as `:frame-provider` so the spine wires it into the
  `:register-context-provider` substrate slot — the component shell lives
  in this ns, above where UIx's `$` marshals props; the spine carries no
  UIx element-macro dependency."
  (spine/make-react-adapter spine-fns
                            {:kind           :rf.adapter/uix
                             :frame-provider frame-provider}))

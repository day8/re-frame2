(ns re-frame.adapter.helix
  "The Helix adapter — third canonical browser substrate, targeting the
  Helix 0.2.x line. Per Spec 006 §CLJS reference: Helix as alternative
  substrate. Ships in `day8/re-frame2-helix`; dependency flows adapter
  → core.

  Shares the React-shaped substrate machinery (container quartet,
  derived value, render-root, render-to-string, frame-provider, use-
  subscribe, flush-views!, source-coord wrapper, warn-once-cache) with
  the UIx adapter via `re-frame.substrate.spine`. Helix-specific
  configuration: gensym prefixes, substrate name (used in warn-once
  text), and helix.hooks `use-memo*` / `use-callback*` (the spine
  passes JS-array deps unconditionally). The React frame-context
  comes from `re-frame.adapter.context` so a mixed-substrate app's
  frame-provider chain composes across substrates."
  (:require [helix.core          :refer-macros [defnc]]
            [helix.hooks         :as helix-hooks]
            [re-frame.substrate.spine   :as spine]))

;; ---- shared spine wiring --------------------------------------------------

(def ^:private spine-fns
  (spine/make-react-spine
    {:substrate-name        "Helix"
     :gensym-prefix-sub     "rf-helix-sub-"
     :gensym-prefix-derived "rf-helix-derived-"
     :gensym-prefix-use-sub "rf-helix-use-sub-"
     :use-memo              helix-hooks/use-memo*
     :use-callback          helix-hooks/use-callback*
     :use-context           helix-hooks/use-context}))

;; ---- public surface (Helix-named) -----------------------------------------

(def set-hiccup-emitter!
  "Install a render-tree → HTML fn for use by render-to-string. Idempotent.
  Helix itself doesn't render to string in browser bundles; SSR consumers
  install the hiccup emitter explicitly (mirroring the Reagent and UIx
  adapters)."
  (:set-hiccup-emitter! spine-fns))

(def use-current-frame
  "Helix hook returning the current frame keyword from the surrounding
  React context, or `:rf/default` when no frame-provider sits above.
  Decision 2 mandates every React-shaped adapter resolves the same
  context, so a Helix subtree under a Reagent or UIx frame-provider
  sees the right frame and vice versa.

  React-context tier only. For the full resolution chain
  (dynamic-var → React-context → :rf/default) use `(rf/current-frame-id)`;
  the routed `:adapter/current-frame` hook (registered via
  `spine/make-react-adapter`) covers that chain. Per rf2-84myk."
  (:use-current-frame spine-fns))

(defnc frame-provider
  "User-facing component scoping `frame-kw` to its subtree. Wraps
  children in the shared frame Context Provider. Helix call shape — the
  idiomatic `$` TRAILING-CHILDREN form, identical to every other Helix
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
  The three React-shaped adapters share one React Context (per rf2-2qit
  Decision 2) so a subtree under any frame-provider sees the right frame
  regardless of which substrate rendered the provider.

  Native shell above the prop-marshalling seam (rf2-z7hfp — Mike-ruled
  C, MOVE THE SEAM UP). This is a NATIVE Helix `defnc` component, NOT a
  re-export of a shared-spine fn handed to `$`. Because it is a real
  `defnc`, Helix's `$` routes its props through `extract-cljs-props`,
  which beans the JS object back into a CLJS map with KEYWORD keys (and
  Helix's `-props` preserves keyword VALUES — only keys are stringified)
  AND lifts the native trailing children onto the `:children` key
  (Helix's `$`/`extract-cljs-props` contract: `($ Comp props c1 c2)`
  arrives as `{... :children #js [c1 c2]}`). So `:frame` / `:children`
  destructure cleanly with the namespace intact. The body delegates to
  the substrate-agnostic spine core `build-frame-provider-element`
  (frame-resolution + element-build).

  This replaces the former bespoke un-mangling wrapper (rf2-9ok1s: a
  plain re-export plus a `gobj/get` string-key read + children-array
  normalise, branching on `(map? props)`). With the seam moved ABOVE
  `$`, the prop-mangling class — Helix's `$` handing a plain fn a raw JS
  object with string keys — is impossible by construction: there is no
  plain fn under `$` for the element macro to mangle. No per-substrate
  un-mangling patch remains to drift."
  [{:keys [frame children]}]
  (spine/build-frame-provider-element frame children))

(def use-subscribe
  "Helix hook that reads a re-frame subscription. Returns the current
  value; re-renders the calling component when the value changes.

  Frame resolution: reads the surrounding frame-provider's keyword via
  `use-context` (rf2-2qit Decision 2). Override via the 2-arg form to
  pin to an explicit frame-id.

  Per rf2-2qit Decision 1 the hook is named `use-subscribe` to match
  the React/Helix idiom — symmetric ergonomics to Reagent's
  `(rf/subscribe ...)` deref shape, asymmetric naming (hooks live in
  hook-named space)."
  (:use-subscribe spine-fns))

(def flush-views!
  "Flush pending Helix renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act f); with no arg, calls (act
  (fn [] nil)) to flush pending effects. Returns nil. Resolves React's
  act() across React 18 (in `react-dom/test-utils`) and React 19 (on
  the React namespace directly).

  Per rf2-2qit Decision 6: the canonical test-flush hook for
  Helix-based apps."
  (:flush-views! spine-fns))

(def wrap-view
  "Wrap a Helix-shape user component in a function component that
  injects `data-rf2-source-coord` on the rendered root DOM element
  (when `interop/debug-enabled?` is true). Returned fn has the same
  call signature as `user-fn` and is suitable for use as a Helix
  component head. Production builds elide via `interop/debug-enabled?`
  per Spec 009 §Production builds."
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
  "The Helix adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.helix :as helix])
      (rf/init! helix/adapter)

  See Spec 006 §CLJS reference: Helix as alternative substrate.
  Implements the same nine-fn contract as re-frame.adapter.reagent
  and re-frame.adapter.uix. Per rf2-agql there is no default-adapter
  registry — adapter wiring is explicit at the call site.

  Assembled by `spine/make-react-adapter` (rf2-ee38b.1): the 9-key
  substrate map, the five React-hook `route-hook!` calls, and the two
  chained installs (warn-once clear + SSR emitter) all live once in the
  spine — the Helix and UIx adapter wiring is byte-identical, so this
  single call replaces the former hand-copied block (which had drifted in
  prose against the UIx twin). The per-hook rationale lives at
  `spine/make-react-adapter`.

  The native `frame-provider` `defnc` component (rf2-z7hfp) is passed in
  as `:frame-provider` so the spine wires it into the
  `:register-context-provider` substrate slot — the component shell lives
  in this ns, above where Helix's `$` marshals props; the spine carries
  no Helix element-macro dependency."
  (spine/make-react-adapter spine-fns
                            {:kind           :rf.adapter/helix
                             :frame-provider frame-provider}))

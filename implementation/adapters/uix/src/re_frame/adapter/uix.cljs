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
  (:require [uix.core          :as uix]
            [uix.hooks.alpha   :as uix-hooks]
            [re-frame.disposable :as rf-disposable]
            [re-frame.frame    :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.spine   :as spine]
            [re-frame.adapter.context :as adapter-context]))

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

(def frame-provider
  "User-facing component scoping `frame-kw` to its subtree. Wraps
  children in the shared frame Context Provider. UIx call shape:

      ($ frame-provider {:frame :session
                         :children [($ header) ($ main)]})

  Per rf2-sixo: missing or `nil` `:frame` falls through to `:rf/default`.
  The three React-shaped adapters share one React Context (per rf2-3yij
  Decision 2) so a subtree under any frame-provider sees the right
  frame regardless of which substrate rendered the provider."
  (:frame-provider spine-fns))

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
  is explicit at the call site."
  {:kind                      :rf.adapter/uix
   :make-state-container      (:make-state-container      spine-fns)
   :read-container            (:read-container            spine-fns)
   :replace-container!        (:replace-container!        spine-fns)
   :subscribe-container       (:subscribe-container       spine-fns)
   :make-derived-value        (:make-derived-value        spine-fns)
   :render                    (:render                    spine-fns)
   :render-to-string          (:render-to-string          spine-fns)
   :register-context-provider (:register-context-provider spine-fns)
   :dispose-adapter!          (:dispose-adapter!          spine-fns)})

;; Late-bind hooks below route through `substrate-adapter/route-hook!`
;; (per rf2-0d35 — see its docstring for the routing contract): each
;; impl runs ONLY when the UIx adapter is the (rf/init!)-installed
;; one; otherwise chains to the previously-registered handler.
;;
;; Hook-specific UIx-adapter notes:
;;   :adapter/current-frame  — rf2-d4sf. Function components have no
;;     class-component (.-context cmp) slot, so the shared impl in
;;     `re-frame.adapter.context` reads `_currentValue` directly. This
;;     is the WIDER surface — `(rf/current-frame)` reaches the
;;     dynamic-var-fallback chain via this hook; the per-adapter
;;     `use-current-frame` hook above is the NARROWER React-context-
;;     tier-only read (per rf2-84myk).
;;   :adapter/add-on-dispose! / :adapter/dispose! — rf2-jicu2. Spine-
;;     produced derived values reify the re-frame-owned
;;     `re-frame.disposable/IDisposable` (no Reagent coupling). The
;;     adapter wires straight to the protocol fns. Pre-rf2-jicu2 these
;;     routed to `reagent.ratom/add-on-dispose!` / `ratom/dispose!`
;;     which dragged ~9KB of reagent.ratom + reagent.impl.batching into
;;     every UIx-only release bundle for a single defprotocol slot.
;;     The remaining reactive-substrate hooks (`:adapter/ratom`,
;;     `:adapter/ratom?`, `:adapter/make-reaction`, `:adapter/reactive?`)
;;     are intentionally NOT published by the UIx adapter — UIx ships
;;     no reactive-atom primitive (per rf2-3yij) and
;;     `re-frame.interop`'s reactive-atom surfaces have zero production
;;     call sites under UIx; publishing those hooks would force the UIx
;;     bundle to carry reagent.core (transitively reagent.ratom) for
;;     code it never executes.
;;   :adapter/after-render — rf2-334d9. Backed by `React.useLayoutEffect`
;;     via the spine's after-render machinery (see
;;     `re-frame.substrate.spine/make-after-render-hook` +
;;     `make-after-render-sentinel`). `after-render` is a React-
;;     lifecycle question (when does the next commit complete?), not a
;;     reactive-atom one — so the "no reactive primitive" rationale
;;     that excludes the four hooks above does NOT apply. Pre-rf2-334d9
;;     `(rf/after-render f)` under the UIx adapter was a silent no-op;
;;     publish closes that correctness bug under the pre-alpha
;;     masterpiece posture.
;;   :adapter/wrap-view — rf2-00li. Substrate-side source-coord
;;     injection via React.cloneElement (the views.cljs inline
;;     hiccup-walk would mis-classify React-element output as a
;;     non-DOM root). Production-elided via `interop/debug-enabled?`
;;     per Spec 009 §Production builds.
(substrate-adapter/route-hook! adapter :adapter/current-frame
  adapter-context/function-component-current-frame
  #(frame/current-frame))
(substrate-adapter/route-hook! adapter :adapter/add-on-dispose!
  rf-disposable/-add-on-dispose)
(substrate-adapter/route-hook! adapter :adapter/dispose!
  rf-disposable/-dispose)
(substrate-adapter/route-hook! adapter :adapter/wrap-view
  wrap-view)
(substrate-adapter/route-hook! adapter :adapter/after-render
  (:after-render-hook spine-fns))

;; Chained warn-once clear (rf2-4edk): every loaded adapter's
;; per-process defonce must be cleared between tests, so this hook is
;; chained (not routed by installed-adapter identity).
(spine/install-clear-warn-once-step! clear-warned-non-dom-roots!)

;; Chained SSR emitter install (rf2-4z7bp): `re-frame.ssr.emit`
;; invokes `:reagent/set-hiccup-emitter!` at ns-load; every loaded
;; React-shaped adapter contributes its own install step so a single
;; `(require '[re-frame.ssr])` auto-wires every adapter's
;; render-to-string slot. Hook key is historical (Reagent published
;; it first per rf2-uo7v); behaviour is adapter-agnostic.
(late-bind/chain-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

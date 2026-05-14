(ns re-frame.adapter.uix
  "The UIx adapter — the second canonical browser substrate (rf2-3yij).
  Per Spec 006 §CLJS reference: UIx as alternative substrate.

  Ships in its own Maven artefact (day8/re-frame2-uix) per
  Spec 006 §Adapter shipping convention (rf2-0hxm). Apps that use UIx
  depend on both day8/re-frame2 (core) and this artefact; apps
  targeting Reagent depend on day8/re-frame2-reagent instead.
  Core does *not* :require this ns — the dependency direction is
  adapter → core.

  Per rf2-3yij Decision 2 the React frame-context lives in
  `re-frame.adapter.context` (CLJS-only file in core); this adapter
  consumes the *same* createContext object the Reagent adapter
  consumes, so a future mixed-substrate app's frame-provider chain
  composes across substrates.

  Per rf2-3yij Decision 8 we target UIx 2.x (hooks-based).

  Substrate-spine sharing (rf2-3vwbx). The container quartet, derived
  value, render-root, render-to-string, frame-provider, use-subscribe,
  flush-views!, source-coord wrapper, and warn-once-cache logic all
  come from `re-frame.substrate.spine` — that ns hosts the
  React-shaped-substrate spine UIx and Helix share byte-for-byte. The
  UIx-specific configuration here is the gensym-prefix triple, the
  substrate-name (\"UIx\") used in warn-once text, and the runtime
  hook fns from `uix.hooks.alpha`. We bind the runtime fns rather than
  the `uix.core` namespace's `use-memo` / `use-callback` because those
  are macros (with no runtime counterpart Var) — passing a macro
  symbol as a config value yields `undefined` at runtime, which broke
  every UIx-substrate example after rf2-3vwbx until it was fixed by
  the `uix.hooks.alpha` switch. `uix.hooks.alpha/use-memo` and
  `use-callback` are the runtime fns the `uix.core` macros themselves
  expand to (they take JS-array deps — exactly what the spine passes)."
  (:require [reagent.core      :as r]
            [reagent.ratom     :as ratom]
            [uix.core          :as uix]
            [uix.hooks.alpha   :as uix-hooks]
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
  between cases (via `reset-runtime-fixture` and the chained
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

;; Each late-bind hook below is routed through `(substrate-adapter/
;; current-adapter)` per rf2-0d35 via `substrate-adapter/route-hook!`
;; (see that fn's docstring for the routing contract). The wrapper runs
;; this adapter's impl ONLY when the UIx adapter is the (rf/init!)-
;; installed one; otherwise it chains to the previously-registered
;; handler.
;;
;; Hook-specific rationale (UIx-adapter notes):
;;   :adapter/current-frame  — rf2-d4sf. UIx renders function
;;     components — they have no class-component (.-context cmp) slot,
;;     so the shared impl in `re-frame.adapter.context` reads
;;     `_currentValue` directly. The chain-bottom fallback
;;     `frame/current-frame` covers the dynamic-var tier of the full
;;     resolution chain (per rf2-84myk: this hook is the WIDER surface
;;     — `(rf/current-frame)` reaches it; the per-adapter
;;     `use-current-frame` hook above is the NARROWER React-context-
;;     tier-only read).
;;   :adapter/ratom etc. — rf2-s36l. UIx's derived values reify stock
;;     reagent.ratom/IDisposable directly, so these hooks delegate to
;;     stock Reagent's r/atom, ratom/make-reaction, etc. UIx itself
;;     ships no reactive-atom primitive (per the rf2-3yij design).
;;   :adapter/wrap-view — rf2-00li. Substrate-side source-coord
;;     injection via React.cloneElement (the inline hiccup-walk in
;;     views.cljs would mis-classify React-element output as a non-DOM
;;     root). Production-elision: `wrap-view`'s body sits inside an
;;     `interop/debug-enabled?` gate so closure-folds under :advanced +
;;     goog.DEBUG=false (per Spec 009 §Production builds).
(substrate-adapter/route-hook! adapter :adapter/current-frame
  adapter-context/function-component-current-frame
  #(frame/current-frame))
(substrate-adapter/route-hook! adapter :adapter/ratom
  r/atom)
(substrate-adapter/route-hook! adapter :adapter/ratom?
  (fn ratom?-impl [x] (satisfies? ratom/IReactiveAtom ^js x))
  (constantly false))
(substrate-adapter/route-hook! adapter :adapter/make-reaction
  ratom/make-reaction)
(substrate-adapter/route-hook! adapter :adapter/add-on-dispose!
  ratom/add-on-dispose!)
(substrate-adapter/route-hook! adapter :adapter/dispose!
  ratom/dispose!)
(substrate-adapter/route-hook! adapter :adapter/reactive?
  ratom/reactive?
  (constantly false))
(substrate-adapter/route-hook! adapter :adapter/after-render
  r/after-render)
(substrate-adapter/route-hook! adapter :adapter/wrap-view
  wrap-view)

;; Per rf2-4edk: contribute a clear of THIS adapter's warn-once cache to
;; the chained `:adapter/clear-warn-once-caches!` hook. The hook is
;; chained — each adapter (helix, uix) and re-frame.views all contribute
;; a clear-step; `reset-runtime-fixture` invokes the top of the chain
;; and every contributor's reset runs (unlike `:adapter/current-frame`
;; etc. this hook is NOT routed through the installed adapter — every
;; loaded adapter's cache must clear because test bundles can mount
;; different adapters across tests and each adapter's defonce persists).
;; Production behaviour is unchanged: the warn-once defonce is still
;; per-process for users; only test-time clearing is new.
(spine/install-clear-warn-once-step! clear-warned-non-dom-roots!)

;; Per rf2-4z7bp: chain this adapter's `set-hiccup-emitter!` into the
;; `:reagent/set-hiccup-emitter!` late-bind hook so SSR (which calls
;; the hook at `re-frame.ssr.emit` ns-load time) auto-wires the UIx
;; adapter's :render-to-string slot. The hook name is historical
;; (Reagent published it first per rf2-uo7v); behaviour is adapter-
;; agnostic and chained — every loaded React-shaped adapter contributes
;; its own install-into-the-emitter-cell step, so a process with both
;; Reagent and UIx loaded gets BOTH adapters' emitters wired by a
;; single `(require '[re-frame.ssr])`. Before this chain entry, an
;; SSR-from-UIx user had to call `(uix-adapter/set-hiccup-emitter!
;; render-to-string)` directly — see the rf2-gc5v9 test docstring.
(late-bind/chain-fn! :reagent/set-hiccup-emitter! set-hiccup-emitter!)

(ns re-frame.adapter.helix
  "The Helix adapter — the third canonical browser substrate (rf2-2qit).
  Per Spec 006 §CLJS reference: Helix as alternative substrate.

  Ships in its own Maven artefact (day8/re-frame2-helix) per
  Spec 006 §Adapter shipping convention (rf2-0hxm). Apps that use Helix
  depend on both day8/re-frame2 (core) and this artefact; apps
  targeting Reagent depend on day8/re-frame2-reagent instead; apps
  targeting UIx depend on day8/re-frame2-uix instead. Core does
  *not* :require this ns — the dependency direction is adapter → core.

  Per rf2-2qit Decision 2 the React frame-context lives in
  `re-frame.adapter.context` (CLJS-only file in core); this adapter
  consumes the *same* createContext object the Reagent and UIx
  adapters consume, so a future mixed-substrate app's frame-provider
  chain composes across substrates.

  Per rf2-2qit Decision 8 we target the Helix 0.2.x line.

  Substrate-spine sharing (rf2-3vwbx). The container quartet, derived
  value, render-root, render-to-string, frame-provider, use-subscribe,
  flush-views!, source-coord wrapper, and warn-once-cache logic all
  come from `re-frame.substrate.spine` — that ns hosts the
  React-shaped-substrate spine UIx and Helix share byte-for-byte. The
  Helix-specific configuration here is the gensym-prefix triple, the
  substrate-name (\"Helix\") used in warn-once text, and the helix.hooks
  use-memo*/use-callback* (which want JS-array deps; the spine passes
  JS arrays unconditionally)."
  (:require [reagent.core        :as r]
            [reagent.ratom       :as ratom]
            [helix.hooks         :as helix-hooks]
            [re-frame.frame      :as frame]
            [re-frame.late-bind  :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.spine   :as spine]
            [re-frame.adapter.context :as adapter-context]))

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
  sees the right frame and vice versa."
  (:use-current-frame spine-fns))

(def frame-provider
  "User-facing component scoping `frame-kw` to its subtree. Wraps
  children in the shared frame Context Provider. Helix call shape:

      ($ frame-provider {:frame :session
                         :children [($ header) ($ main)]})

  Per rf2-sixo: missing or `nil` `:frame` falls through to `:rf/default`.
  The three React-shaped adapters share one React Context (per rf2-3yij
  Decision 2 / rf2-2qit Decision 2) so a subtree under any
  frame-provider sees the right frame regardless of which substrate
  rendered the provider."
  (:frame-provider spine-fns))

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

(def clear-warned-non-dom-roots!
  "Reset the warn-once cache for non-DOM-root warnings. Tests use this
  between cases (via `reset-runtime-fixture` and the chained
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
  registry — adapter wiring is explicit at the call site."
  {:kind                      :helix
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
;; this adapter's impl ONLY when the Helix adapter is the (rf/init!)-
;; installed one; otherwise it chains to the previously-registered
;; handler.
;;
;; Hook-specific rationale (Helix-adapter notes):
;;   :adapter/current-frame  — rf2-d4sf. Helix renders function
;;     components — they have no class-component (.-context cmp) slot,
;;     so the shared impl in `re-frame.adapter.context` reads
;;     `_currentValue` directly. Helix's own `use-current-frame` hook
;;     is sugar over the same read, so subscribe / dispatch and
;;     `use-context` agree on the active frame. Chain-bottom fallback
;;     is `frame/current-frame`.
;;   :adapter/ratom etc. — rf2-s36l. Helix's derived values reify
;;     stock reagent.ratom/IDisposable directly, so these hooks
;;     delegate to stock Reagent's r/atom, ratom/make-reaction, etc.
;;     Helix itself ships no reactive-atom primitive (it is the
;;     minimal-React-wrapper niche per rf2-2qit).
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

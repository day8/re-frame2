(ns re-frame.ui.substrate
  "The compiled-view substrate's first-party React adapter machinery.

  This namespace has two jobs and no view API of its own:

  1. build the watchable ten-function substrate adapter installed through
     `rf/init!`; and
  2. publish the React-context frame reader for both that adapter and the
     plain-atom CLJS path used by headless/native-DOM conformance fixtures.

  The watchable half is the production observation path retained when the
  UIx and Helix adapters are removed. It uses the core's React spine directly
  — plain atom state, one coherent-write scheduler, `IDeref` + `IWatchable`
  derived values, and no dependency on either wrapper library. Compiled views
  do not use the spine's legacy subscription hook: their sole read owner is
  `re-frame.ui.reactive` (probe/acquire/read/release) and one ViewCell uSES
  subscription per view.

  ## Why the plain-atom route remains

  `re-frame.ui.frames/resolve-frame` reads the shared React frame context
  (`re-frame.adapter.context/frame-context`) DIRECTLY, so frame-scoped
  operations that route through it already see the `frame-provider` /
  `frame-root` scope. But the compiled view SUB-READ path does not go
  through that primitive: `re-frame.ui.reactive/sub-read` →
  `re-frame.substrate.observation/resolve-target` →
  `re-frame.frame/require-current-frame!` → `resolve-current-frame`, which
  consults the `:adapter/current-frame` late-bind hook for the React-context
  tier (Spec 006 §Frame-provider via React context). In a PURE compiled-ui
  app — one running on the plain-atom reactive adapter — that hook otherwise
  has no publisher, so a mounted `(sub …)` under a provider resolved the
  dynamic tier only and
  raised `:rf.error/no-frame-context`.

  ## The context publish

  This ns publishes the shared function-component React-context reader —
  `re-frame.adapter.context/function-component-current-frame` — through the
  SAME `:adapter/current-frame` hook, so core's `resolve-current-frame`
  reaches the context tier on the compiled-view sub-read path. There is ONE
  resolution order (frames.cljc §The ambient frame chain); this reuses the
  shared reader rather than duplicating it.

  Homed in the ui artefact (not core's `plain-atom`) because the reader is a
  React-context read — the React dependency belongs with the substrate that
  renders React, keeping plain-atom's headless CLJS bundle React-free.

  ## Routing, not clobbering

  The plain-atom route and the first-party React route both use
  `substrate-adapter/route-hook!`, so the installed adapter selects exactly
  one reader and load order cannot clobber a sibling route."
  (:require ["react" :as react]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.substrate.spine :as spine]
            [re-frame.views.frame-boundary :as boundary]))

;; The React-context-tier frame-id reader for the pure compiled-view runtime.
;; Routed against plain-atom so `resolve-current-frame` sees the context tier
;; when plain-atom is installed (the pure-ui case); the classic-adapter chain
;; is untouched. Fallback `#(frame/current-frame)` mirrors the UIx/Helix
;; routing — the dynamic-var tier when neither this nor a chained handler runs.
(substrate-adapter/route-hook! plain-atom/adapter :adapter/current-frame
  adapter-context/function-component-current-frame
  #(frame/current-frame))

;; One substrate-owned spine. `make-react-spine` supplies the mature adapter
;; contract implementation without importing a view-wrapper library. The
;; generated legacy `use-subscribe` surface is deliberately not exported;
;; compiled views own reads through ViewCell + the observation port. The
;; adapter assembler consumes the container/render/disposal entries only, and
;; advanced compilation can remove unused hook machinery.
(defonce ^:private spine-fns
  (spine/make-react-spine
   {:substrate-name        "re-frame.ui"
    :gensym-prefix-sub     "rf-ui-sub-"
    :gensym-prefix-derived "rf-ui-derived-"
    :gensym-prefix-use-sub "rf-ui-use-sub-"
    :use-memo              react/useMemo
    :use-callback          react/useCallback
    :use-context           react/useContext}))

(defn- frame-provider-component
  "The adapter-contract provider component. `ui/frame-provider` normally
  compiles straight to the shared Context Provider; this component serves the
  lower-level `:register-context-provider` slot without UIx/Helix marshalling."
  [props]
  (let [frame-id (frame/require-keyword-frame-provider-arg!
                  (.-frame props)
                  're-frame.ui/frame-provider)]
    (boundary/require-live-frame-for-scope!
     frame-id 're-frame.ui/frame-provider)
    (apply adapter-context/provider-element
           frame-id
           (adapter-context/normalize-children (.-children props)))))

(def adapter
  "The retained first-party React substrate adapter. Installed publicly as
  `re-frame.ui/adapter`; its canonical discriminator is `:rf.adapter/ui`."
  (spine/make-react-adapter
   spine-fns
   {:kind :rf.adapter/ui :frame-provider frame-provider-component}))

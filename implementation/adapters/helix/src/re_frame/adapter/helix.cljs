(ns re-frame.adapter.helix
  "Helix 0.2.x adapter for the substrate contract in Spec 006.

  Helix and UIx share the React machinery in `re-frame.substrate.spine`.
  This namespace supplies Helix's hooks and native `defnc` `frame-provider`
  (SCOPE) + `frame-root` (ENSURE) components; keeping them native preserves
  Helix's CLJS prop and trailing-child marshalling."
  (:require [helix.core          :refer-macros [defnc]]
            [helix.hooks         :as helix-hooks]
            [re-frame.frame             :as frame]
            [re-frame.substrate.spine   :as spine]
            [re-frame.adapter.resource-lease :as resource-lease]
            [re-frame.views.frame-boundary :as boundary]))

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
  React context, or the no-provider sentinel
  (`re-frame.adapter.context/no-provider-sentinel`) when no frame-provider
  sits above. All React-shaped adapters use the same context, so mixed
  Reagent, UIx, and Helix provider trees compose.

  This is the raw React-context read and does not map the sentinel to nil.
  Use `(rf/current-frame-id)` for dynamic-var then React-context resolution."
  (:use-current-frame spine-fns))

(defnc frame-provider
  "SCOPE an existing frame for descendant Helix components (rf2-nyea0r split).

  `{:frame existing-id}` scopes an ALREADY-CREATED frame and FAILS LOUD when it
  is absent. Creates / refreshes / destroys nothing.

      ($ frame-provider {:frame :session}
         ($ header))

  Given an `:id` (the ENSURE key), FAILS LOUD naming `frame-root`
  (`:rf.error/frame-provider-given-id`) — providers scope; roots ensure. To
  CREATE the frame if absent, use `frame-root`.

  This must remain a native `defnc`. Helix converts JS props back to a CLJS map,
  preserving keyword values, and folds trailing children into `:children` before
  this body delegates to the shared scope core."
  [props]
  (when (contains? props :id)
    (boundary/reject-frame-provider-id!
      (:id props) 're-frame.adapter.helix/frame-provider))
  ;; Validate before consulting the registry so malformed ids get the
  ;; configuration error rather than the absent-frame error.
  (let [frame-kw (frame/require-keyword-frame-provider-arg!
                   (:frame props) 're-frame.adapter.helix/frame-provider)]
    (boundary/require-live-frame-for-scope!
      frame-kw 're-frame.adapter.helix/frame-provider)
    (spine/build-frame-provider-element frame-kw (:children props))))

(defnc frame-root
  "ENSURE a named frame for descendant Helix components (rf2-nyea0r split) — a
  COMMIT-OWNED TWO-PASS boundary.

  `{:id id ...}` creates the frame if absent and otherwise reuses it without
  replaying `:initial-events`. Accepts the frame-record options `rf/make-frame`
  takes (`:images` / `:initial-events` / `:url-bound?` …); `:id` is required and
  must be a keyword.

      ($ frame-root {:id :session :images [session-image]}
         ($ header)
         ($ main))

  The ENSURE runs in a client `useLayoutEffect` (NOT during render): the first
  render emits no descendant subtree, the frame is created AFTER commit, and only
  then do the children render — so a Suspense-aborted render creates + seeds
  nothing. Repeated mounts are intentionally idempotent, including React
  StrictMode development mounts: they neither destroy durable state nor replay
  initial events. A mounted `:id` / opts change FAILS LOUD
  (`:rf.error/frame-root-reconfigured`). Given a `:frame` (the SCOPE key), FAILS
  LOUD naming `frame-provider`. Explicit `rf/make-frame` and `rf/destroy-frame!`
  calls own teardown.

  This must remain a native `defnc`. Helix converts JS props back to a CLJS map,
  preserving keyword values, and folds trailing children into `:children` before
  this body delegates to the shared two-pass core."
  [props]
  (boundary/frame-root-react-element
    props
    (:children props)
    're-frame.adapter.helix/frame-root))


(def use-subscribe
  "Helix hook that reads a re-frame subscription. Returns the current
  value; re-renders the calling component when the value changes.

  Reads the surrounding frame-provider by default; the 2-arg form pins an
  explicit frame id."
  (:use-subscribe spine-fns))

(def use-resource-lease
  "Helix hook that takes a resource liveness lease for the calling
  component's mounted lifetime. On
  mount it dispatches `:rf.resource/ensure` with an app-minted `[:lease …]`
  owner; on unmount it releases that lease via `:rf.resource/release-owner`.
  Use it when a view owns a polled or cached resource while mounted.

      (use-resource-lease {:resource :my/feed :scope :rf.scope/global
                           :params {:page 0}})

  Pair it with `use-subscribe` to read the data; this hook only manages
  liveness and returns nil. `:cause` annotates the ensure, and `:frame` pins
  ownership to an explicit frame."
  resource-lease/use-resource-lease)

(def flush-views!
  "Flush pending Helix renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act f); with no arg, calls (act
  (fn [] nil)) to flush pending effects. Returns nil. Resolves React's
  act() across React 18 (in `react-dom/test-utils`) and React 19 (on
  the React namespace directly)."
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
  first-encounter warning cannot suppress a later test's same-id warning."
  (:clear-warned-non-dom-roots! spine-fns))

;; ---- adapter Var ----------------------------------------------------------

(def adapter
  "The Helix adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.helix :as helix])
      (rf/init! helix/adapter)

  Adapter installation is explicit; there is no default-adapter registry.
  `spine/make-react-adapter` owns the shared UIx/Helix hook routing and
  lifecycle wiring. The native provider stays in this namespace so the spine
  has no dependency on Helix's element macro."
  (spine/make-react-adapter spine-fns
                            {:kind           :rf.adapter/helix
                             :frame-provider frame-provider}))

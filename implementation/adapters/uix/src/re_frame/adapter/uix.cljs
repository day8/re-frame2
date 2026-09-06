(ns re-frame.adapter.uix
  "UIx 2.x adapter for the substrate contract in Spec 006.

  A first-class, actively-supported adapter: UIx is a permanent sibling of
  the Reagent and reagent-slim adapters, not a transition path off any of
  them.

  The React machinery lives in `re-frame.substrate.spine`, shared with any
  future React-wrapper adapter.
  This namespace supplies UIx's hooks and native `defui` `frame-provider`
  (SCOPE) + `frame-root` (ENSURE) components; keeping them native preserves
  UIx's CLJS prop and trailing-child marshalling."
  (:require [uix.core          :as uix :refer-macros [defui]]
            [uix.hooks.alpha   :as uix-hooks]
            [uix.compiler.input]
            [re-frame.frame             :as rf.frame]
            [re-frame.substrate.spine   :as rf.substrate.spine]
            [re-frame.adapter.use-frame :as rf.adapter.use-frame]
            [re-frame.views.frame-boundary :as rf.views.frame-boundary]))

;; ---- controlled inputs: React's implementation, not the classpath's -------
;;
;; `uix.compiler.aot/create-uix-input` picks, per `:input` element and at
;; element-creation time, between plain React and a port of Reagent's
;; controlled-input workaround. Left unset, the var below makes that choice by
;; asking whether `reagent.impl.util/*non-reactive*` happens to EXIST — so
;; adding the Reagent adapter to a UIx app silently changed how the UIx app's
;; controlled inputs behave, with no diagnostic and no opt-in (rf2-heqwo).
;;
;; re-frame2 pins it. React's own path keeps the element controlled and
;; converges inside the discrete event, before `dispatchEvent` returns, through
;; React's end-of-event state restore. The port makes the element UNCONTROLLED
;; (deletes `:value`, installs `defaultValue` + a `ref`) and drives the value
;; from `reagent.impl.batching/do-after-render` — a requestAnimationFrame
;; queue, so one frame late, never in-turn. Both were measured in real Chromium
;; against react-dom 19.2.0 (rf2-n3dxw); the trade-off and the caret behaviour
;; consumers will see are documented in `docs/api/re-frame.adapter.uix.md`.
;;
;; This runs at namespace load — ahead of any render, and therefore ahead of
;; any element creation. The var stays `^:dynamic` and public: a consumer who
;; genuinely wants the port asks for it EXPLICITLY, by `set!`ing it back to
;; `true` after requiring this namespace (or `binding` it around a render).
;; What is no longer possible is getting either one by accident.
(set! uix.compiler.input/*use-reagent-input-enabled?* false)

;; ---- shared spine wiring --------------------------------------------------

(def ^:private spine-fns
  (rf.substrate.spine/make-react-spine
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
  React context, or the no-provider sentinel
  (`re-frame.adapter.context/no-provider-sentinel`, `:rf.frame/no-provider`)
  when NEITHER `frame-provider` (SCOPE) nor `frame-root` (ENSURE) installs
  the shared frame-context above. Both boundaries write that one context —
  explicitly not `:rf/default` — and every React-shaped adapter reads it, so
  mixed Reagent and UIx provider trees compose.

  This is the narrow raw `useContext` read: it does not map the sentinel to
  nil, nor consult the dynamic-var tier. Use `(rf/current-frame-id)` for the
  full dynamic-var → context → nil resolution chain."
  (:use-current-frame spine-fns))

(defui frame-provider
  "SCOPE an existing frame for descendant UIx components (rf2-nyea0r split).

  `{:frame existing-id}` scopes an ALREADY-CREATED frame and FAILS LOUD when it
  is absent. Creates / refreshes / destroys nothing.

      ($ frame-provider {:frame :session}
         ($ header))

  Given an `:id` (the ENSURE key), FAILS LOUD naming `frame-root`
  (`:rf.error/frame-provider-given-id`) — providers scope; roots ensure. To
  CREATE the frame if absent, use `frame-root`.

  This must remain a native `defui`. UIx reconstructs the original CLJS prop
  map, preserving namespaced keyword values, and folds trailing children into
  `:children` before this body delegates to the shared scope core."
  [props]
  (when (contains? props :id)
    (rf.views.frame-boundary/reject-frame-provider-id!
      (:id props) 're-frame.adapter.uix/frame-provider))
  ;; Validate before consulting the registry so malformed ids get the
  ;; configuration error rather than the absent-frame error.
  (let [frame-kw (rf.frame/require-frame-provider-target!
                   (:frame props) 're-frame.adapter.uix/frame-provider)]
    (rf.views.frame-boundary/require-live-frame-for-scope!
      frame-kw 're-frame.adapter.uix/frame-provider)
    (rf.substrate.spine/build-frame-provider-element frame-kw (:children props))))

(defui frame-root
  "ENSURE a named frame for descendant UIx components (rf2-nyea0r split) — a
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

  This must remain a native `defui`. UIx reconstructs the original CLJS prop
  map, preserving namespaced keyword values, and folds trailing children into
  `:children` before this body delegates to the shared two-pass core."
  [props]
  (rf.views.frame-boundary/frame-root-react-element
    props
    (:children props)
    're-frame.adapter.uix/frame-root))

(def use-subscribe
  "UIx hook that reads a re-frame subscription. Returns the current
  value; re-renders the calling component when the value changes.

  Reads the surrounding frame-provider by default; the 2-arg form pins an
  explicit frame id."
  (:use-subscribe spine-fns))

(def use-frame
  "UIx hook returning the frame api for the ambient frame — EXACTLY what
  `(rf/capture-frame)` returns (the frame-locked ops map
  `{:frame :dispatch :dispatch-sync :subscribe}`), captured in hook
  position. capture-frame is THE hold primitive; `reg-view` injection
  (Reagent) and this hook are its two ergonomic spellings.

      (defui counter-buttons []
        (let [count              (use-subscribe [:counter/value])
              {:keys [dispatch]} (use-frame)]
          ($ :button {:on-click #(dispatch [:counter/inc])} \"+\")))

  Resolution matches the ambient `use-subscribe`: dynamic-var tier first,
  then the surrounding `frame-provider` / `frame-root` via React context;
  no scope raises `:rf.error/no-frame-context`. The returned map is
  reference-stable across re-renders for the same resolved frame
  INCARNATION (safe in effect deps / child props); a provider swap
  re-renders the caller and yields a map locked to the new frame, and so
  does destroying the resolved frame and creating another under the same
  id — a frame keyword is an address, and the bundle is pinned to the
  incarnation it was captured against (rf2-40kv). No options map, no
  variants — for an explicit frame call `(rf/capture-frame frame-id)`
  directly."
  rf.adapter.use-frame/use-frame)

(def flush-views!
  "Flush pending UIx renders synchronously. Wraps React's act() —
  intended for test code only. Calls (act f); with no arg, calls (act
  (fn [] nil)) to flush pending effects. Returns nil. Resolves React's
  act() from the React namespace directly (React 19 is the adapter
  floor); a production React bundle omits act, where this is a no-op.
  It publishes a render phase, so do not call it from inside a
  `dispatch-sync` handler (rf2-0c23j)."
  (:flush-views! spine-fns))

(def wrap-view
  "Wrap a UIx-shape user component in a function component that injects
  `data-rf2-source-coord` on the rendered root DOM element (when
  `interop/debug-enabled?` is true). Returned fn has the same call
  signature as `user-fn` and is suitable for use as a UIx component
  head. Production builds elide via `interop/debug-enabled?` per
  Spec 009 §Production builds."
  (:wrap-view spine-fns))

;; ---- registered-view component head — UIx-NATIVE half (rf2-oz7wr) ---------
;;
;; `views/reg-view*` hands its composed wrapper to `:adapter/componentize-view`
;; and registers whatever comes back, so `(rf/view id)` is what a caller mounts.
;; Without this the registered value is a `MetaFn` (the `:contextType` meta
;; Reagent's class machinery reads), which React rejects as an element type —
;; the documented `($ (rf/view ::row) props)` form could not mount at all.
;;
;; The spine builds the mountable shell; the ONE substrate-native step is
;; stamping UIx's own component marker on it, and it belongs here for exactly
;; the rf2-z7hfp reason `frame-provider` does. The marker is what makes
;; `uix.compiler.alpha/component-element` take the `uix-component-element`
;; branch, which stashes the ORIGINAL CLJS props map on `argv` and folds
;; trailing `$` children onto `:children` — the lossless channel. Unmarked, `$`
;; would fall through to `react-component-element` → `interpret-attrs`, which
;; is what stringified keyword prop values and dropped their namespaces before
;; rf2-z7hfp (`:frame` silently becoming `:rf/default`). So stripping the meta
;; alone would have produced a head React could mount and then mangled its
;; props; the marker is the half that makes the mount CORRECT.
;;
;; Not a `defui`: `defui` glues `argv` into a CLJS map for its own body, and
;; this shell must hand the props object DOWN untouched so the registered
;; component — itself typically a `defui` — reads them in its own idiom. The
;; shell is a marked forwarder, which is what keeps `(rf/reg-view* ::row row)`
;; free of any wrapper ceremony for the caller.

(defn- componentize-view
  "Build the UIx component head for a registered view. Delegates the shell
  to the spine core and stamps UIx's `uix-component?` marker on it."
  [id metadata wrapped]
  (let [shell ((:componentize-view spine-fns) id metadata wrapped)]
    (set! (.-uix-component? ^js shell) true)
    shell))

;; ---- adapter Var ----------------------------------------------------------

(def adapter
  "The UIx adapter map. Pass to `(rf/init! ...)` to install:

      (require '[re-frame.adapter.uix :as uix])
      (rf/init! uix/adapter)

  Adapter installation is explicit; there is no default-adapter registry.
  `spine/make-react-adapter` owns the shared React-hook routing and
  lifecycle wiring. The native provider stays in this namespace so the spine
  has no dependency on UIx's element macro."
  (rf.substrate.spine/make-react-adapter spine-fns
                            {:kind              :rf.adapter/uix
                             :frame-provider    frame-provider
                             :componentize-view componentize-view}))

(ns re-frame.views.provider
  "Frame-provider component + per-render identity machinery for the
  Reagent-side views ns. Re-frame.views re-exports the publicly-
  referenced surface (`frame-provider`, `build-frame-provider`,
  `current-frame`, `mint-instance-token!`, `current-render-key`,
  `*render-key*`) so existing call sites work unchanged.

  Three cohesive concerns live here:

  1. `frame-provider` / `build-frame-provider` — the user-facing
     Reagent component that scopes a frame keyword to its subtree via
     React context. The React Context object is owned by
     `re-frame.adapter.context` so adapters across substrates
     (Reagent / UIx / Helix) read the same context.

  2. Per-render instance-token machinery — `mint-instance-token!`,
     `reagent-component-token`. Tokens disambiguate concurrently-
     mounted instances of the same view-kind. The `*render-key*`
     dynamic var lives in `re-frame.views` so the wrapper's binding
     and consumer reads share the same canonical Var.

  3. `current-frame` — the resolution chain subscribe / dispatch
     consult at render time (Spec 002 §Reading the frame from React
     context). Pairs with the `:contextType` static `reg-view*`
     attaches to its wrapper output.

  This ns does NOT statically `:require` `reagent.core`. The in-flight
  Reagent component is read through the late-bind hook
  `:adapter/current-component`; with no adapter installed the hook
  returns nil and the React-context tier of the resolution chain is
  skipped (equivalent to a non-Reagent render context — JVM /
  headless tests rely on this)."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]))

;; ---- the React context for frame propagation -----------------------------
;;
;; The React Context object lives in `re-frame.adapter.context` so
;; every React-shaped adapter reads the *same* context — not a
;; parallel one. The Reagent code below references it via the alias
;; rather than minting a new createContext call.

(def frame-context adapter-context/frame-context)

(defn- frame-provider-component
  "The single Reagent component that backs every frame-provider. It takes
  the frame keyword as its first render-time arg and scopes that keyword
  to its subtree via React context. One built component services every
  frame — the keyword lives in the Provider's `:value`, not in a
  closure.

  Uses Reagent's `:r>` interop head so the props map flows to React as a
  raw JS object without passing through `reagent.impl.template/convert-
  prop-value`. That bypass is what makes a *namespaced* frame keyword
  (`:tenant/admin`) survive the React-context round trip on the classic
  Reagent adapter — stock Reagent's `convert-prop-value` calls `(name kw)`
  on named prop values, which drops the namespace before React sees the
  prop. The slim adapter (`day8/reagent-slim`) preserves keywords for
  non-HTML prop names so the namespace would survive there even via the
  `[:> Provider ...]` hiccup form; routing the canonical surface through
  `:r>` keeps the behaviour identical across adapters.

  Children remain hiccup — `:r>` only short-circuits prop conversion;
  children are translated by the renderer as usual. Per
  `re-frame.adapter.context/provider-element` for the React-element-
  building counterpart that adapter ns offers for substrate-side mounts."
  [frame-kw & children]
  (into [:r> (.-Provider frame-context) #js {:value frame-kw}] children))

(defn build-frame-provider
  "Used by re-frame.adapter.reagent/register-context-provider. Returns
  a Reagent component that scopes a frame keyword to its subtree.

  Zero-arity (rf2-4y60): the returned component takes the frame keyword
  at render time, and a single built component services every frame, so
  there is nothing to specialise at build time. Substrates whose
  `register-context-provider` slot receives a frame-keyword on call (per
  Spec 006 §Frame-provider via React context) discard it and call this
  with no args."
  []
  frame-provider-component)

(defn frame-provider
  "User-facing component scoping `frame-kw` to its subtree. Wraps
  children in the shared frame Context Provider — inside the subtree,
  `(rf/dispatcher)` / `(rf/subscriber)` / `reg-view`-registered
  descendants resolve to the named frame. Per Spec 002 §What
  `frame-provider` is.

  Reads `:frame` from props. When missing or `nil`, falls through to
  `:rf/default` — defensive default that matches no-provider
  behaviour and avoids breaking tooling-generated trees that elide
  the prop.

  Reagent call shape:

      [rf/frame-provider {:frame :session}
       [header]
       [main-area]
       [footer]]

  Children are variadic (zero, one, or many). Same surface as the UIx
  and Helix variants, different rendering substrate. The three
  adapters share one React Context so a subtree under any frame-
  provider sees the right frame regardless of which substrate
  rendered the provider.

  `build-frame-provider` is the lower-level substrate hook; this fn is
  the canonical user-facing surface."
  [props & children]
  (let [frame-kw (or (:frame props) :rf/default)]
    (into [(build-frame-provider) frame-kw] children)))

;; ---- in-flight Reagent component -----------------------------------------
;;
;; The active adapter publishes its Reagent build's `current-component`
;; fn through `:adapter/current-component` at ns-load time. Static
;; `:require [reagent.core]` would hard-couple this ns to stock Reagent
;; and silently shadow the slim adapter's components in mixed-mode
;; environments.

(defn current-component
  "Resolve the in-flight Reagent component via the active adapter's hook.
  Returns nil when no adapter has registered the hook (JVM / headless
  builds; no-adapter tests)."
  []
  (when-let [hook (late-bind/get-fn :adapter/current-component)]
    (hook)))

;; ---- frame resolution at render time -------------------------------------

(def ^:dynamic *current-frame* nil)

(defn current-frame
  "Resolution chain (per Spec 002 §Reading the frame from React context):
    1. Dynamic var (set by with-frame).
    2. Closest enclosing frame-provider via React context.
    3. :rf/default.

  Reagent-specific: the React-context tier reads `(.-context cmp)` on
  the in-flight Reagent component. Reagent's class-component machinery
  surfaces context to components whose `:contextType` matches the
  context object — that is the wiring `reg-view*` attaches via
  `{:contextType frame-context}`. Plain Reagent fns lack this wiring,
  so `(.-context cmp)` is React's empty default — they fall through
  to `:rf/default`. This narrowness is by design: it is what makes
  the `:rf.warning/plain-fn-under-non-default-frame-once` warning
  meaningful.

  The keyword/string coercion via
  `re-frame.adapter.context/coerce-context-value` is defensive cover
  for users who mount a Provider via raw `[:> (.-Provider frame-context)
  {:value :foo}]` hiccup directly. Under the classic Reagent adapter
  that path still passes through stock Reagent's `convert-prop-value`,
  which stringifies named values (and drops keyword namespaces — see
  `frame-provider-component`). The canonical user-facing surface
  (`rf/frame-provider`) bypasses `convert-prop-value` via `:r>` so the
  Provider's `:value` reaches React as the original keyword (namespace
  preserved). The helper survives the slim rewrite because the
  defensive-cover use case for raw-hiccup mounts is independent of
  which Reagent build is loaded."
  []
  (or frame/*current-frame*
      (when-let [cmp (current-component)]
        (adapter-context/coerce-context-value (.-context cmp)))
      :rf/default))

;; ---- per-render identity --------------------------------------------------
;;
;; Render-trace entries carry a `:render-key` of shape
;; `[<view-id> <instance-token>]`. Tokens are minted at mount time
;; from a process-wide counter and disambiguate concurrently-mounted
;; instances of the same kind — they're for in-run discrimination
;; only, no cross-run correlation.
;;
;; The `*render-key*` dynamic var and `current-render-key` reader
;; live in `re-frame.views` (the public ns) so the canonical Var sits
;; at the public path tests reach via `re-frame.views/*render-key*`.
;; The mint / per-component-instance machinery lives here with its
;; state (`instance-counter`, `.-rfInstanceToken`).

(defonce ^:private instance-counter (atom 0))

(defn mint-instance-token!
  "Return a fresh integer token for a freshly-mounted view instance. The
  counter is process-wide and monotonic; values are unique within a
  single process run but carry no cross-run correlation guarantee."
  []
  (swap! instance-counter inc))

(defn reagent-component-token
  "Return the per-component-instance token, minting one on first call.
  Stored on the Reagent component object as `.-rfInstanceToken` so the
  same mounted instance reuses the token across re-renders. When called
  outside a Reagent component (direct invocation in headless tests),
  mints a fresh token per call — that mirrors the per-mount-fresh
  semantics for tests that simulate one mount per call."
  []
  (if-let [cmp (current-component)]
    (or (.-rfInstanceToken ^js cmp)
        (let [tok (mint-instance-token!)]
          (set! (.-rfInstanceToken ^js cmp) tok)
          tok))
    (mint-instance-token!)))

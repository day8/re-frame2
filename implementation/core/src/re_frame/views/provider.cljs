(ns re-frame.views.provider
  "Frame-provider component + per-render identity machinery for the
  Reagent-side views ns. Per rf2-lh7p — split out of `re-frame.views`
  so the views file stays focused on registration orchestration.
  Re-frame.views re-exports the publicly-referenced surface
  (`frame-provider`, `build-frame-provider`, `current-frame`,
  `mint-instance-token!`, `current-render-key`, `*render-key*`) so
  existing call sites continue to work unchanged.

  Three cohesive concerns live here:

  1. `frame-provider` — the user-facing Reagent component that scopes
     a frame keyword to its subtree via React context. The actual
     React Context object is owned by `re-frame.adapter.context` so
     adapters across substrates (Reagent / UIx / Helix) read the same
     context (per rf2-3yij Decision 2). The `build-frame-provider`
     factory is the lower-level substrate hook the Reagent adapter
     registers with `register-context-provider`.

  2. Per-render instance-token machinery — `mint-instance-token!`,
     `reagent-component-token`. Tokens disambiguate concurrently-
     mounted instances of the same view-kind (Spec 004 §Render-tree
     primitives, rf2-piag / rf2-t5tx). The `*render-key*` dynamic var
     itself lives in `re-frame.views` so the wrapper's binding and
     consumer reads share the same canonical Var.

  3. `current-frame` — the resolution chain that subscribe / dispatch
     consult to find the surrounding frame at render time
     (Spec 002 §Reading the frame from React context). It pairs with
     the `:contextType` static reg-view's wrapper installs on its
     output (per `re-frame.views`).

  Per rf2-wbnl this ns does NOT statically `:require` `reagent.core`.
  The in-flight Reagent component is read through the late-bind hook
  `:adapter/current-component`, which the active adapter ns publishes
  at load time. The classic bridge points the hook at
  `reagent.core/current-component`; the slim adapter points it at
  `reagent2.core/current-component`. With no adapter installed the
  hook returns nil and the React-context tier of the resolution chain
  is skipped — equivalent to a non-Reagent render context, which is
  what JVM / headless tests already rely on."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]))

;; ---- the React context for frame propagation -----------------------------
;;
;; Per rf2-3yij Decision 2 the React Context object lives in
;; `re-frame.adapter.context` so the UIx adapter (and any future
;; React-shaped adapter) reads the *same* context — not a parallel one.
;; The Reagent code below references it via the alias rather than
;; minting a new createContext call.

(def frame-context adapter-context/frame-context)

(defn- frame-provider-component
  "The single Reagent component that backs every frame-provider. It takes
  the frame keyword as its first render-time arg and scopes that keyword
  to its subtree via React context. One built component services every
  frame — the keyword lives in the Provider's `:value`, not in a
  closure."
  [frame-kw & children]
  (into [:> (.-Provider frame-context) {:value frame-kw}] children))

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
  "User-facing Reagent component that scopes a frame keyword to its
  subtree. Inside the subtree, `(rf/dispatcher)` / `(rf/subscriber)`
  capture the named frame; reg-view-registered descendants resolve to
  it via React context.

      [rf/frame-provider {:frame :session}
       [header]
       [main-area]
       [footer]]

  `props` is a map carrying `:frame frame-id`. Children render under
  that frame (variadic — zero, one, or many).

  When `:frame` is missing or `nil`, falls through to `:rf/default` —
  matches the no-provider behaviour. This is the defensive default per
  the rf2-sixo decision; an explicit error would catch typos but would
  also break tooling-generated trees that elide the prop.

  Per Spec 002 §What `frame-provider` is. `build-frame-provider` is the
  lower-level substrate hook; this fn is the canonical user-facing
  surface."
  [props & children]
  (let [frame-kw (or (:frame props) :rf/default)]
    (into [(build-frame-provider) frame-kw] children)))

;; ---- in-flight Reagent component (rf2-wbnl) ------------------------------
;;
;; The active adapter (classic bridge or slim) publishes its Reagent build's
;; `current-component` fn through the `:adapter/current-component` late-bind
;; hook at ns-load time. This ns must NOT statically `:require [reagent.core]`
;; — doing so hard-couples views.cljs to stock Reagent and silently shadows
;; the slim adapter's components in mixed-mode environments. Per the bead's
;; resolution-chain note: dynamic var → adapter.current-component
;; (late-bind) → nil. When no adapter has installed the hook the call returns
;; nil and the React-context tier is skipped.

(defn current-component
  "Resolve the in-flight Reagent component via the active adapter's hook.
  Returns nil when no adapter has registered the hook (JVM / headless
  builds; no-adapter tests). Per rf2-wbnl."
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
  to `:rf/default`. This narrowness is by design: it is what makes the
  `:rf.warning/plain-fn-under-non-default-frame-once` warning
  meaningful (rf2-d3k3 / rf2-d4sf).

  Per rf2-d4sf the keyword/string check tolerates Reagent's
  prop-stringified shape: when a frame-provider is reached via
  `[:> Provider {:value :foo} ...]` interop, `convert-prop-value`
  rewrites `:foo` to `\"foo\"` before React sees it. The shared
  coercion in `re-frame.adapter.context/coerce-context-value` undoes
  that stringification so the Reagent path returns a keyword
  regardless of how the Provider was authored."
  []
  (or frame/*current-frame*
      (when-let [cmp (current-component)]
        (adapter-context/coerce-context-value (.-context cmp)))
      :rf/default))

;; ---- per-render identity (rf2-piag / rf2-t5tx) ----------------------------
;;
;; Render-trace entries carry a `:render-key` of shape
;; `[<view-id> <instance-token>]`. Per rf2-t5tx Option C the tuple is the
;; canonical identity: the view-id (registry keyword) names the kind; the
;; instance-token disambiguates concurrently-mounted instances of the same
;; kind. Tokens are minted at mount time from a process-wide counter and
;; are NOT correlated across runs — they're for in-run instance discrimination
;; only (per Spec 004 §Render-tree primitives).
;;
;; Note: the `*render-key*` dynamic var and `current-render-key` reader
;; live in `re-frame.views` (the public ns) rather than here. That keeps
;; the dynamic-var Var canonical at the public path that tests reach via
;; `re-frame.views/*render-key*` — see the views.cljs orchestration ns
;; for the rationale. The mint / per-component-instance machinery does
;; live here because it owns its own state (`instance-counter`) and the
;; component-side stamp (`.-rfInstanceToken`).

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

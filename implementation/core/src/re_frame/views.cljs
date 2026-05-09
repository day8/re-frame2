(ns re-frame.views
  "Views — reg-view, frame-provider. Per Spec 004.

  CLJS-only (Reagent-side). The pure-data render-tree contract lives in
  Spec 011 (SSR); this namespace ties view registration into the Reagent
  substrate.

  Form-1 (a render fn) is the canonical view shape. Form-2/3 are
  supported via Reagent's native handling.

  reg-view auto-defs the local Var (per Spec 004 §reg-view defs the Var
  by default); the Var is the canonical call-site reference. For
  late-binding by id (e.g. across module boundaries, runtime-computed
  ids, or hot-reload semantics), call `(re-frame.core/view :id)`
  to obtain the wrapped fn and use `[(rf/view :id) args]` as the
  hiccup head."
  (:require ["react"        :as React]
            [reagent.core   :as r]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]))

;; ---- the React context for frame propagation -----------------------------

(defonce ^:private frame-context
  (.createContext React :rf/default))

(defn- frame-provider-component
  "The single Reagent component that backs every frame-provider. It takes
  the frame keyword as its first render-time arg and scopes that keyword
  to its subtree via React context. One built component services every
  frame — the keyword lives in the Provider's `:value`, not in a
  closure."
  [frame-kw & children]
  (into [:> (.-Provider frame-context) {:value frame-kw}] children))

(defn build-frame-provider
  "Used by re-frame.substrate.reagent/register-context-provider. Returns
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

;; ---- frame resolution at render time -------------------------------------

(def ^:dynamic *current-frame* nil)

(defn current-frame
  "Resolution chain (per Spec 002 §Reading the frame from React context):
    1. Dynamic var (set by with-frame).
    2. Closest enclosing frame-provider via React context.
    3. :rf/default.

  Frame ids are always keywords (per Spec 002 §Frame ids). The keyword?
  check filters out React's empty-object default for components without
  a wired contextType — `goog/typeOf` reports both keywords and bare
  objects as \"object\", so a typeOf-based discriminator silently
  swallowed valid keyword contexts and made `frame-provider` resolve
  to `:rf/default` regardless of what it pushed."
  []
  (or *current-frame*
      (when-let [cmp (r/current-component)]
        (let [ctx (.-context cmp)]
          (when (keyword? ctx) ctx)))
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
;; For renders that did not enter through reg-view / reg-view* (plain
;; Reagent fns), `*render-key*` is unbound at trace-emission time; consumers
;; treat that as `[:rf.view/anonymous nil]` (per the bead resolution).

(defonce ^:private instance-counter (atom 0))

(defn mint-instance-token!
  "Return a fresh integer token for a freshly-mounted view instance. The
  counter is process-wide and monotonic; values are unique within a
  single process run but carry no cross-run correlation guarantee."
  []
  (swap! instance-counter inc))

(def ^:dynamic *render-key*
  "The `:render-key` for the in-flight render — a tuple
  `[<view-id> <instance-token>]`. Bound by the wrapper emitted by
  `reg-view*` for the duration of each render. Nil outside a registered
  view's render (the trace recorder treats nil as
  `[:rf.view/anonymous nil]` per Spec 004 §Render-tree primitives)."
  nil)

(defn current-render-key
  "Return the `:render-key` for the in-flight render, or
  `[:rf.view/anonymous nil]` when none is bound (e.g. inside a plain
  Reagent fn that bypassed reg-view). Per Spec 004 §Render-tree
  primitives — the anonymous fallback is the documented unbound-shape."
  []
  (or *render-key* [:rf.view/anonymous nil]))

(defn- reagent-component-token
  "Return the per-component-instance token, minting one on first call.
  Stored on the Reagent component object as `.-rfInstanceToken` so the
  same mounted instance reuses the token across re-renders. When called
  outside a Reagent component (direct invocation in headless tests),
  mints a fresh token per call — that mirrors the per-mount-fresh
  semantics for tests that simulate one mount per call."
  []
  (if-let [cmp (r/current-component)]
    (or (.-rfInstanceToken ^js cmp)
        (let [tok (mint-instance-token!)]
          (set! (.-rfInstanceToken ^js cmp) tok)
          tok))
    (mint-instance-token!)))

(defn- emit-render-trace!
  "Emit a `:view/render` trace event tagged with the in-flight
  `:render-key`. The trace also carries the `:frame` tag so the
  epoch-capture buffer (per re-frame.epoch §capture-event!) can route
  the render into the right per-frame cascade. Goes through late-bind
  so this ns doesn't depend on re-frame.trace (which itself routes
  through late-bind for registrar/views ordering reasons). Production
  builds elide via the `interop/debug-enabled?` gate the trace surface
  itself rides."
  [render-key]
  (when interop/debug-enabled?
    (when-let [emit! (late-bind/get-fn :trace/emit!)]
      (emit! :view :view/render
             {:render-key render-key
              :frame      (current-frame)}))))

;; ---- reg-view -------------------------------------------------------------

(defn reg-view*
  "Reagent-aware view registration. Wraps `render-fn` with the React
  `:contextType` static-field metadata used to resolve the surrounding
  frame at render time, then registers it in the :view kind of the
  registrar.

  Per Spec 004 §reg-view*: this is the plain-fn surface delegated to
  by `re-frame.core/reg-view*` on CLJS. `metadata` is merged into the
  registry slot's metadata as-is; source-coord capture is performed
  by the caller (`re-frame.core/reg-view*`).

  Note (rf2-kdwc): Reagent's create-class / fn-to-class machinery
  recognises `:contextType` (camelCase, the React static-field name),
  not `:context-type` (kebab). The earlier shape used the kebab key
  and was silently ignored, which is why frame-provider context
  resolution fell back to :rf/default.

  Per rf2-piag / rf2-t5tx: each render binds `*render-key*` to the
  tuple `[id instance-token]` for the body, so the trace recorder can
  attribute the render. The instance-token is minted at mount and
  reused across re-renders of the same component instance (per
  Spec 004 §Render-tree primitives)."
  [id metadata render-fn]
  (let [wrapped (with-meta
                  (fn frame-aware-view [& args]
                    (let [tok        (reagent-component-token)
                          render-key [id tok]]
                      (binding [*render-key* render-key]
                        (emit-render-trace! render-key)
                        (apply render-fn args))))
                  {:contextType frame-context})]
    (registrar/register! :view id (assoc metadata :handler-fn wrapped))
    wrapped))

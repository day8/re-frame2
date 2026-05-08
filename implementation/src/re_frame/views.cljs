(ns re-frame.views
  "Views — reg-view, the h macro, frame-provider. Per Spec 004.

  CLJS-only (Reagent-side). The pure-data render-tree contract lives in
  Spec 011 (SSR); this namespace ties view registration into the Reagent
  substrate.

  Form-1 (a render fn) is the canonical view shape. Form-2/3 are
  supported via Reagent's native handling.

  reg-view auto-defs the local Var (per Spec 004 §reg-view defs the Var
  by default); the Var is the canonical call-site reference. Bare
  `[:keyword args]` in raw hiccup is post-v1; the (h [...]) macro
  rewrites keyword references at compile time as the v1 escape hatch."
  (:require ["react"        :as React]
            [reagent.core   :as r]
            [re-frame.registrar :as registrar]))

;; ---- the React context for frame propagation -----------------------------

(defonce ^:private frame-context
  (.createContext React :rf/default))

(defn build-frame-provider
  "Used by re-frame.substrate.reagent/register-context-provider. Returns
  a Reagent component that scopes a frame keyword to its subtree."
  [_frame-keyword]
  (fn [frame-kw & children]
    (into [:> (.-Provider frame-context) {:value frame-kw}] children)))

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

;; ---- reg-view -------------------------------------------------------------

(defn reg-view*
  "Function form of reg-view. Registers the view under :view kind in the
  registrar. Returns the wrapped (frame-aware) render fn.

  The Var-defining form lives in a macro so the local Var is bound at
  compile time."
  [id metadata render-fn]
  (let [wrapped (with-meta
                  (fn frame-aware-view [& args]
                    (apply render-fn args))
                  {:context-type frame-context})]
    (registrar/register! :view id (assoc metadata :handler-fn wrapped))
    wrapped))

(defn get-view
  "Return the wrapped render fn for a registered view, or nil."
  [view-id]
  (when-let [meta (registrar/lookup :view view-id)]
    (:handler-fn meta)))

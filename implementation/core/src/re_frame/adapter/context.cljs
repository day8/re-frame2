(ns re-frame.adapter.context
  "Shared React context for frame propagation across substrate adapters.

  Per Spec 006 §Frame-provider via React context, the frame keyword is
  propagated through the React tree via a single React Context. Both the
  Reagent and UIx adapters read this same context object so a tree
  containing components from multiple substrates resolves frames
  consistently — and so a future mixed-substrate app (rf2-3yij Decision
  2) sees one shared frame-provider chain rather than per-adapter
  silos.

  The context lives in core (CLJS-only) because:

    1. Core already :requires React directly via re-frame.views, so this
       file adds no new transitive runtime dep. The plain-atom adapter
       (the JVM-runnable half) does not load this ns — it sits in
       re_frame/adapter/context.cljs (CLJS-only) and the JVM build
       never sees it.

    2. Both adapters MUST share the *same* React.createContext object —
       two separate createContext calls produce distinct contexts whose
       Provider/Consumer pairs do not interact. Putting the createContext
       call in a single shared ns guarantees identity.

  Per rf2-3yij Decision 2: factored out of re-frame.views for the UIx
  adapter to read."
  (:require ["react" :as React]))

(defonce frame-context
  ;; The default value is :rf/default — Spec 002 §`:rf/default` guarantees
  ;; this frame always exists. Components without an enclosing
  ;; frame-provider resolve to it.
  (.createContext React :rf/default))

(defn provider-element
  "Build a React element for the frame-context Provider with `frame-kw`
  as its value and `children` as its child elements. Substrate-agnostic
  — both the Reagent adapter (via `:>` interop) and the UIx adapter
  (via `$`) can wrap the appropriate hiccup/expression form around this
  primitive.

  Returns a raw React element so callers don't pay for an extra
  reagent.core/as-element walk."
  [frame-kw & children]
  (apply React/createElement
         (.-Provider frame-context)
         #js {:value frame-kw}
         children))

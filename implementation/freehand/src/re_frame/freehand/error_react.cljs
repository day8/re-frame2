(ns re-frame.freehand.error-react
  "The React glue that makes `v/error-boundary` a REAL browser error boundary
  — the thin class-component driver over the host-agnostic
  [[re-frame.freehand.errors]] law, exactly as
  [[re-frame.freehand.shell]] is the thin driver over the atomic shell.

  A React error boundary MUST be a class component: `getDerivedStateFromError`
  (React 19 requires it, paired with `componentDidCatch`, for the boundary to
  catch at all) flips a render-phase marker, and `componentDidCatch` runs the
  side effects. This layer owns exactly that wiring and NOTHING else — the
  decision of what a caught failure means (the safe summary, the
  once-per-generation intent, the reset semantics, the private egress record)
  lives in the common law, which the JVM structural host drives too.

  ## The one shape

    - **`getDerivedStateFromError`** flips React state `:caught` so the very
      next render shows the fallback. It is static and cannot reach the
      instance, so it captures nothing itself.
    - **`componentDidCatch`** runs in the commit phase — the place a stateful
      side effect is safe — and CAPTURES the failure onto the boundary
      ([[re-frame.freehand.errors/capture!]]): a fresh failure
      advances the generation, a repeat (StrictMode's double-invoke) is a
      no-op.
    - **`componentDidUpdate`** reconciles the caller-owned `:reset-key` (a
      change clears the failure and re-mounts the child) and PROMOTES the
      failure — the safe `:on-error` intent and the private egress record —
      once per generation, after the fallback has committed.

  Frame context is read through `contextType`, so the safe intent dispatches
  into the frame the boundary was mounted under. The interpreted emitter
  ([[re-frame.freehand.react/element]]) is passed IN rather than required, so
  this namespace never closes a require cycle with the React tree."
  (:require ["react" :as react]
            [goog.object :as gobj]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.freehand.errors :as eb]))

(defn- props-map
  "The Clojure props map the React tree stashed under the `\"props\"` key —
  the boundary's normalized `{:fallback … :reset-key … :on-error …
  :children [child]}`."
  [^js this]
  (gobj/get (.-props this) "props"))

(defn- context-frame-id
  "The frame the boundary is mounted under — an ambient
  `re-frame.frame/*current-frame*` if a caller bound one, else the React
  context value the boundary reads through its `contextType`. nil when no
  frame is in scope, in which case the safe intent dispatches ambiently."
  [^js this]
  (or frame/*current-frame*
      (adapter-context/context-value->current-frame (.-context this))))

(defn- promote!
  "Fire the two failure channels once per captured generation, after the
  fallback commit. Guarded by the law's once-per-generation gate, so calling
  it from `componentDidMount` and every `componentDidUpdate` is safe."
  [^js this]
  (let [b (.-boundary this)]
    (when (eb/failed? b)
      (eb/promote! b {:on-error (:on-error (eb/read-opts (props-map this)))
                      :frame-id (context-frame-id this)
                      :time     (js/Date.now)}))))

(defn boundary-component
  "Build the React class component `v/error-boundary` lowers to in the
  browser. `emit-form` is [[re-frame.freehand.react/element]] — the
  interpreted emitter that turns a Hiccup form into a React element — passed
  in so this namespace does not require the React tree back.

  There is one such component (the boundary descriptor is a singleton), so
  the React tree memoises it like any other declared view's component."
  [emit-form]
  (let [ctor  (fn error-boundary-ctor [props]
                (this-as ^js this
                  (.call ^js react/Component this props)
                  (let [opts (eb/read-opts (gobj/get props "props"))]
                    (set! (.-boundary this)
                          (eb/boundary eb/boundary-view-id (:reset-key opts))))
                  (set! (.-state this) #js {:caught false})
                  this))
        ;; Bound once, `^js`-tagged: the React lifecycle names below are then
        ;; INFERRED EXTERNS, so `:advanced` cannot munge the methods React
        ;; calls by name (componentDidCatch, render, …). A `(.. ctor
        ;; -prototype -X)` chain over a known-function type cannot infer them.
        proto ^js (js/Object.create (.-prototype ^js react/Component))]
    (set! (.-prototype ^js ctor) proto)
    (set! (.-constructor proto) ctor)
    (set! (.-displayName ^js ctor) (str eb/boundary-view-id))
    ;; The safe intent dispatches into the frame the boundary sits under.
    (set! (.-contextType ^js ctor) adapter-context/frame-context)
    ;; React-19 requires this static method for the boundary to catch; it
    ;; flips the render-phase marker only. The capture is the commit phase's.
    (set! (.-getDerivedStateFromError ^js ctor) (fn [_error] #js {:caught true}))
    (set! (.-componentDidCatch proto)
          (fn [error info]
            (this-as ^js this
              (eb/capture! (.-boundary this)
                           {:exception       error
                            :phase           :render
                            :view-id         eb/boundary-view-id
                            :frame-id        (context-frame-id this)
                            :component-stack (some-> info (gobj/get "componentStack"))}))))
    (set! (.-componentDidMount proto)
          (fn [] (this-as ^js this (promote! this))))
    (set! (.-componentDidUpdate proto)
          (fn [_prev-props _prev-state _snapshot]
            (this-as ^js this
              ;; A changed :reset-key clears the captured failure and
              ;; re-mounts the child; the render below then retries it.
              (when (eb/reset-to! (.-boundary this) (:reset-key (eb/read-opts (props-map this))))
                (.setState this #js {:caught false}))
              (promote! this))))
    (set! (.-render proto)
          (fn []
            (this-as ^js this
              (let [{:keys [fallback children]} (eb/read-opts (props-map this))]
                (if (gobj/get (.-state this) "caught")
                  (emit-form fallback)
                  (emit-form (first children)))))))
    ctor))

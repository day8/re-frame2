(ns re-frame.story.ui.canvas-listeners
  "Shared canvas DOM-listener lifecycle scaffold for Story's canvas-side
  observers (the recorder's `dom-capture` rail and the click-to-source
  `element-inspector`).

  Both observers attach event listeners to the *story canvas root* and
  share the SAME idempotent install/remove lifecycle: look the root up
  by its framework-stable hook, keep a process-local ref to the
  currently-installed root, and on (re-)install detach the previous set
  before attaching the new one. Only the listener BODIES differ (which
  events, the handler logic, the debounce buffer, the rAF throttle, the
  redaction) — those stay in each observer. This ns owns just the
  scaffold so the canvas-root selector lives in ONE place.

  ## Surface

  - `canvas-root` — the current shell canvas root, or nil when the
    shell isn't mounted.
  - `make-lifecycle` — given a process-local `installed-root` atom plus
    an `attach-fn`/`detach-fn` pair, returns an `{:install! :remove!
    :installed?}` closure-set that implements the idempotent lifecycle.
    Callers wrap these thinly for any per-observer pre/post hooks (the
    recorder's `config/enabled?` gate + type-buffer flush, the
    inspector's inspect-mode reset).

  ## Bundle isolation

  Story-side, CLJS-only. Production builds elide the whole Story UI
  shell at the `mount-shell!` call site, so this ns is reachable only
  when Story is enabled. No tools->tools cross-require.")

(def ^:const canvas-selector
  "The framework-stable hook the shell stamps on the canvas frame
  (`shell/canvas`). The single source of truth for where the
  canvas-side observers attach."
  "[data-test=\"story-canvas-frame\"]")

(defn canvas-root
  "The current shell canvas root, looked up by the framework-stable
  `[data-test=\"story-canvas-frame\"]` hook. Returns nil when the shell
  isn't mounted (e.g. before `mount-shell!`)."
  []
  (when (and (exists? js/document) (.-querySelector js/document))
    (try
      (.querySelector js/document canvas-selector)
      (catch :default _ nil))))

(defn make-lifecycle
  "Build the idempotent install/remove lifecycle over `installed-root`
  (a process-local atom holding the currently-installed root, or nil)
  and the observer's own `attach-fn`/`detach-fn` (each `(fn [root] …)`).

  Returns `{:install! :remove! :installed?}`:

  - `:install!` — `([] (install! (canvas-root)))` / `([root] …)`.
    Idempotent: detaches the previous root first, then attaches `root`,
    records it, and returns it. No-op (nil) when `root` is nil.
  - `:remove!`  — detaches the installed root and clears the ref.
    Idempotent. Returns nil.
  - `:installed?` — true iff a root is currently attached.

  The per-observer pre/post concerns (the recorder's `config/enabled?`
  gate + type-buffer flush, the inspector's inspect-mode reset) are NOT
  the scaffold's business — callers wrap the returned closures."
  [installed-root attach-fn detach-fn]
  {:install!
   (fn install!
     ([] (install! (canvas-root)))
     ([root]
      (when root
        (when-let [prev @installed-root]
          (detach-fn prev))
        (attach-fn root)
        (reset! installed-root root)
        root)))
   :remove!
   (fn []
     (when-let [root @installed-root]
       (detach-fn root)
       (reset! installed-root nil))
     nil)
   :installed?
   (fn []
     (some? @installed-root))})

(ns re-frame.story.ui.shell
  "Top-level story shell. Per Stage 4 (rf2-ekai) IMPL-SPEC §4 + §8.

  Three-pane layout:

      ┌──────────┬──────────────────────────┬────────────┐
      │ sidebar  │ canvas / workspace       │ controls   │
      │          │                          │ ─────────  │
      │ stories  │ (selected variant or     │ scrubber   │
      │ tags     │  workspace renders here) │ ─────────  │
      │ ws       │                          │ trace      │
      └──────────┴──────────────────────────┴────────────┘

  ## Public surface

  - `(mount-shell! dom-node)`   — mount the shell at the DOM node.
                                  Returns a shell-handle.
  - `(unmount-shell! handle)`   — tear down a mounted shell.
  - `(active-shell)`            — return the current shell-handle, or nil.

  ## Hot-reload trigger

  Per IMPL-SPEC §13.2 + Stage 4 spec the shell watches the variant /
  decorator fingerprints from `re-frame.story.decorators/
  resolution-fingerprints` and bumps `:hot-reload-tick` when they drift.
  The canvas / workspace components watch the tick and re-mount the
  variant on change.

  A `setInterval`-driven poll is the v1 mechanism; v2 will subscribe to
  the registrar's mutation trace events for an event-driven re-resolve.

  ## Elision

  `mount-shell!` opens with `(when re-frame.story.config/enabled?
  ...)` — production builds short-circuit before any DOM call. The
  shell's renderer namespaces ARE compiled (the elision is at the call-
  site, not the ns definition) but no production code path reaches
  them; closure's reachability analysis DCEs the lot."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.story.config :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.frames :as frames]
            [re-frame.story.runtime :as runtime]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.controls :as controls]
            [re-frame.story.ui.panels :as panels]
            [re-frame.story.ui.scrubber :as scrubber]
            [re-frame.story.ui.sidebar :as sidebar]
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.trace :as trace]
            [re-frame.story.ui.workspace :as workspace]))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:root      {:display "flex"
               :flex-direction "row"
               :height "100vh"
               :font-family "system-ui, sans-serif"
               :background "#1e1e1e"
               :color "#ddd"}
   :main      {:display "flex"
               :flex-direction "column"
               :flex "1"
               :overflow "hidden"}
   :right     {:width "320px"
               :display "flex"
               :flex-direction "column"
               :border-left "1px solid #444"
               :background "#252526"
               :overflow "auto"}
   :tab-bar   {:display "flex"
               :background "#2d2d30"
               :border-bottom "1px solid #444"
               :font-family "monospace"
               :font-size "11px"}
   :tab       {:padding "6px 12px"
               :cursor "pointer"
               :color "#888"
               :border-right "1px solid #444"}
   :tab-active {:color "white"
                :background "#1e1e1e"
                :border-bottom "1px solid #1e1e1e"
                :margin-bottom "-1px"}})

;; ---- hot-reload trigger --------------------------------------------------

(defn- compute-fingerprint-snapshot
  "Walk every running variant frame and capture its current decorator
  fingerprint map. Pure data → data."
  []
  (into {}
        (map (fn [vid] [vid (decorators/resolution-fingerprints vid)]))
        (frames/variant-frames)))

(defn detect-and-tick!
  "Compare the current fingerprint snapshot against the shell state's
  recorded fingerprints; if anything drifted, bump the hot-reload tick
  and stamp the new fingerprints.

  Public so tests can exercise the detector without mounting the shell."
  []
  (when config/enabled?
    (let [current (compute-fingerprint-snapshot)
          shell   (state/get-state)
          prev    (:fingerprints shell)]
      (when (not= current prev)
        (state/swap-state!
          (fn [s]
            (-> s
                (state/bump-hot-reload-tick)
                (assoc :fingerprints current))))
        true))))

(defonce ^:private hot-reload-poll-handle (atom nil))

(defn- start-hot-reload-poll!
  "Begin polling for fingerprint changes. v1 mechanism per Stage 4."
  []
  (when (and config/enabled?
             (nil? @hot-reload-poll-handle))
    (let [h (js/setInterval detect-and-tick! 500)]
      (reset! hot-reload-poll-handle h))))

(defn- stop-hot-reload-poll!
  []
  (when-let [h @hot-reload-poll-handle]
    (js/clearInterval h)
    (reset! hot-reload-poll-handle nil)))

;; ---- listener wiring on selection ----------------------------------------

(defonce ^:private listened-variants (atom #{}))

(defn- ensure-listeners-for-variant!
  "Wire trace + epoch listeners for `variant-id` if not already wired."
  [variant-id]
  (when (and config/enabled?
             (some? variant-id)
             (not (contains? @listened-variants variant-id)))
    (trace/register-listener! variant-id)
    (scrubber/register-listener! variant-id)
    (swap! listened-variants conj variant-id)))

(defn- teardown-listeners-for-variant!
  [variant-id]
  (when (some? variant-id)
    (trace/remove-listener! variant-id)
    (scrubber/remove-listener! variant-id)
    (trace/drop-buffer! variant-id)
    (scrubber/drop-history! variant-id)
    (swap! listened-variants disj variant-id)))

(defn- teardown-all-listeners! []
  (doseq [vid @listened-variants]
    (trace/remove-listener! vid)
    (scrubber/remove-listener! vid))
  (reset! listened-variants #{})
  (trace/clear-buffer!))

(defn- selection-watcher
  "Reagent reaction that wires listeners against the currently-selected
  variant. Implemented as a watch on the shell state atom so it fires
  on every selection change."
  []
  (let [prev (atom (:selected-variant @state/shell-state-atom))]
    (add-watch state/shell-state-atom
               ::shell-selection
               (fn [_ _ _ new-state]
                 (let [now (:selected-variant new-state)
                       before @prev]
                   (when (not= before now)
                     (when before
                       ;; Don't tear down listeners on switch — leave
                       ;; the buffer behind so the user can switch back
                       ;; without losing context. Stage 6 may add an
                       ;; explicit 'clear' button.
                       nil)
                     (when now
                       (ensure-listeners-for-variant! now))
                     (reset! prev now)))))))

(defn- remove-selection-watcher! []
  (remove-watch state/shell-state-atom ::shell-selection))

;; ---- the top-level component ---------------------------------------------

(defn- right-panel
  "The right-side pane — controls + scrubber + trace + Stage-6
  registered story-panels stacked vertically.

  Stage 6 (rf2-zhwd) adds `panels/render-panels-at-placement` so any
  `reg-story-panel` registration with `:placement :right` appears here.
  The built-in v1.0 panels (a11y, layout-debug toggles) ride this path."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        vis        (:panel-visibility shell)]
    [:div {:style (:right styles)}
     (when (:controls vis)
       [controls/panel variant-id])
     (when (and (:scrubber vis) variant-id)
       [scrubber/panel variant-id])
     (when (and (:trace vis) variant-id)
       [trace/panel variant-id])
     ;; Stage 6: render any registered :right-placement story panels.
     (when variant-id
       [panels/render-panels-at-placement :right variant-id vis])]))

(defn- main-pane
  "The main content pane — workspace if one is selected, otherwise the
  variant canvas. Stage 6 (rf2-zhwd) appends any registered
  `:bottom`-placement story panels (e.g. the 10x epoch panel stub)
  below the canvas."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        ws-id      (:selected-workspace shell)
        vis        (:panel-visibility shell)]
    [:div {:style (:main styles)}
     (cond
       ws-id    [workspace/workspace-view ws-id]
       variant-id [canvas/canvas]
       :else
       [:div {:style {:padding "32px"
                      :color "#666"
                      :font-style "italic"
                      :text-align "center"}}
        "Select a variant or workspace from the sidebar."])
     (when variant-id
       [panels/render-panels-at-placement :bottom variant-id vis])]))

(defn shell
  "The top-level shell component. Composes the sidebar, main pane, and
  right panel into a three-pane layout."
  []
  (r/create-class
    {:display-name "rf-story-shell"
     :component-did-mount
     (fn [_]
       (when config/enabled?
         (start-hot-reload-poll!)
         (selection-watcher)
         (when-let [vid (:selected-variant @state/shell-state-atom)]
           (ensure-listeners-for-variant! vid))))
     :component-will-unmount
     (fn [_]
       (stop-hot-reload-poll!)
       (remove-selection-watcher!)
       (teardown-all-listeners!))
     :reagent-render
     (fn []
       [:div {:style (:root styles)}
        [sidebar/sidebar]
        [main-pane]
        [right-panel]])}))

;; ---- mount / unmount surface ---------------------------------------------

(defonce ^:private shell-singleton
  ;; Per IMPL-SPEC §4 single shell at v1; multiple shells deferred to v2.
  (atom nil))

(defn mount-shell!
  "Mount the Story shell at `dom-node`. Returns a shell handle (a map
  carrying `{:root <react-root> :node <dom-node>}`).

  v1: one shell at a time. Calling `mount-shell!` while a shell is
  already mounted unmounts the previous one first.

  Per IMPL-SPEC §6.3 production builds with `enabled?` false short-
  circuit here and return nil — no DOM call, no Reagent render."
  [dom-node]
  (when (and config/enabled? dom-node)
    (when-let [prev @shell-singleton]
      (try
        (rdc/unmount (:node prev))
        (catch :default _ nil)))
    (let [root (rdc/create-root dom-node)]
      (rdc/render root [shell])
      (let [handle {:root root :node dom-node}]
        (reset! shell-singleton handle)
        handle))))

(defn unmount-shell!
  "Tear down a mounted shell. Accepts the handle returned by
  `mount-shell!` or no arg (defaults to the active shell)."
  ([] (unmount-shell! @shell-singleton))
  ([handle]
   (when handle
     (try
       (rdc/unmount (:node handle))
       (catch :default _ nil))
     (state/reset-shell-state!)
     (teardown-all-listeners!)
     (stop-hot-reload-poll!)
     (reset! shell-singleton nil)
     nil)))

(defn active-shell
  "Return the current shell handle or nil."
  []
  @shell-singleton)

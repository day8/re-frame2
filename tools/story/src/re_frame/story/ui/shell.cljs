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
            [re-frame.story.ui.docs :as docs]
            [re-frame.story.ui.help :as help]
            [re-frame.story.ui.mode-tabs :as mode-tabs]
            [re-frame.story.ui.panels :as panels]
            [re-frame.story.ui.scrubber :as scrubber]
            [re-frame.story.ui.sidebar :as sidebar]
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.test-mode :as test-mode]
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
               :color "#b0b0b0"
               :border-right "1px solid #444"}
   :tab-active {:color "white"
                :background "#1e1e1e"
                :border-bottom "1px solid #1e1e1e"
                :margin-bottom "-1px"}
   :help-slot {:position "fixed"
               :top      "8px"
               :right    "12px"
               :z-index  1500}})

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

(defn- ensure-variant-frame!
  "Drive `run-variant` for `variant-id` so its frame is allocated and
  the `:events` slot dispatched before React renders the canvas.

  Per rf2-zme7: clicking a variant row used to leave the variant's
  frame unallocated until the canvas's `:component-did-mount` lifecycle
  fired — which happens AFTER React's first commit. The first render
  thus tried to deref subscriptions against a non-existent frame, threw
  `IDeref.-deref defined for type null`, and React unmounted the shell
  (blank page). Running the variant on the *selection edge* puts the
  frame in place before any view code runs."
  [variant-id]
  (when (and config/enabled? variant-id)
    (let [shell @state/shell-state-atom
          opts  {:active-modes   (:active-modes shell)
                 :cell-overrides (get-in shell [:cell-overrides variant-id])
                 :substrate      (:substrate shell)
                 :render?        true}]
      (try
        (runtime/run-variant variant-id opts)
        (catch :default _e
          ;; Swallow: the canvas re-tries via :component-did-mount /
          ;; :component-did-update, and the result-map's error path
          ;; will surface inline. We just need the frame allocated up-
          ;; front for the first render so the view's subscribe calls
          ;; resolve against a populated app-db rather than nil.
          nil)))))

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
                       (ensure-listeners-for-variant! now)
                       ;; rf2-zme7: pre-allocate the variant's frame
                       ;; before React commits a canvas render that
                       ;; would otherwise deref subscriptions against a
                       ;; non-existent frame.
                       (ensure-variant-frame! now))
                     (reset! prev now)))))))

(defn- remove-selection-watcher! []
  (remove-watch state/shell-state-atom ::shell-selection))

;; ---- the top-level component ---------------------------------------------

(defn- right-panel
  "The right-side pane — controls + scrubber + trace + Stage-6
  registered story-panels stacked vertically.

  Stage 6 (rf2-zhwd) adds `panels/render-panels-at-placement` so any
  `reg-story-panel` registration with `:placement :right` appears here.
  The built-in v1.0 panels (a11y, layout-debug toggles) ride this path.

  Renders as an `<aside>` landmark (per rf2-xc65) so screen readers can
  jump straight to the inspectors and so axe-core's
  `region`/`landmark-one-main` rules pass. `tabindex=\"0\"` makes the
  scrollable container reachable for keyboard users."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        vis        (:panel-visibility shell)]
    [:aside {:style    (:right styles)
             :role     "complementary"
             :aria-label "Inspectors"
             :tab-index "0"}
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
  below the canvas.

  Renders as a `<main>` landmark (per rf2-xc65) so the rendered variant
  has a containing landmark and axe-core's `region` /
  `landmark-one-main` rules pass.

  Per rf2-9hc8 a Canvas | Docs | Tests mode-tab strip sits at the top
  of the pane when a variant is selected; selection is per-variant and
  persists across reloads in localStorage. Per rf2-rodx the `:docs`
  pane renders `docs/docs-view` — the read-only AutoDocs surface
  composed of header / prose / args / decorators / parameters / tags
  sections. Per rf2-qmjo the `:test` pane renders `test-mode/test-view`
  — the in-canvas aggregated pass/fail summary of the variant's
  `:play` sequence + assertions. `:dev` preserves the existing canvas
  / workspace behaviour."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        ws-id      (:selected-workspace shell)
        vis        (:panel-visibility shell)
        mode-tab   (when variant-id
                     (state/active-mode-tab shell variant-id))]
    [:main {:style (:main styles)
            :aria-label "Story canvas"}
     ;; rf2-9hc8: top-of-shell mode-tab strip — only when a single
     ;; variant is selected (workspaces enumerate multiple variants and
     ;; have their own layout switcher; mixing mode-tabs into that pane
     ;; conflates two unrelated UX axes).
     (when (and variant-id (not ws-id))
       [mode-tabs/mode-tabs-strip variant-id])
     (cond
       ws-id    [workspace/workspace-view ws-id]
       variant-id (case mode-tab
                    :docs [docs/docs-view variant-id]
                    :test [test-mode/test-view variant-id]
                    [canvas/canvas])
       :else
       [:div {:style {:padding "32px"
                      :color "#9a9a9a"
                      :font-style "italic"
                      :text-align "center"}}
        "Select a variant or workspace from the sidebar."])
     (when variant-id
       [panels/render-panels-at-placement :bottom variant-id vis])]))

(defn shell
  "The top-level shell component. Composes the sidebar, main pane, and
  right panel into a three-pane layout.

  Per rf2-xc65 each pane is rendered as a semantic HTML5 landmark
  (`<nav>` sidebar, `<main>` canvas, `<aside>` inspectors) so axe-core's
  `region` / `landmark-*` rules pass and screen-reader users can
  navigate the shell by landmark."
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
        [right-panel]
        ;; rf2-381i: first-time help overlay + persistent re-open chip.
        ;; The chip lives in a fixed-position slot so it floats above the
        ;; right inspector pane regardless of which panels are visible.
        [:div {:style (:help-slot styles)}
         [help/help-button]]
        [help/help-host]])}))

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

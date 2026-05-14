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
            [re-frame.story.identity :as identity]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.runtime :as runtime]
            [re-frame.trace :as rf-trace]
            [re-frame.story.ui.actions :as actions]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.controls :as controls]
            [re-frame.story.ui.docs :as docs]
            [re-frame.story.ui.help :as help]
            [re-frame.story.ui.mode-tabs :as mode-tabs]
            [re-frame.story.ui.panels :as panels]
            [re-frame.story.ui.recorder :as recorder-ui]
            [re-frame.story.ui.save-variant :as save-variant-ui]
            [re-frame.story.ui.scrubber :as scrubber]
            [re-frame.story.ui.shell-styles :refer [styles]]
            [re-frame.story.ui.sidebar :as sidebar]
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.test-mode.view :as test-mode-view]
            [re-frame.story.ui.toolbar :as toolbar]
            [re-frame.story.ui.trace :as trace]
            [re-frame.story.ui.workspace :as workspace]))

;; Styles live in `re-frame.story.ui.shell-styles` (pure-data leaf,
;; no Reagent dep). Required as `styles` above so the in-file call
;; sites (`(:root styles)` etc.) stay textually identical.

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

;; ---- watch-mode detector (rf2-z1h0f) -------------------------------------
;;
;; The chrome-level test widget's eye-icon toggles `[:tests :watch-
;; mode?]` in the shell state. When on, this poll computes a snapshot-
;; identity content-hash per testable variant, compares it against the
;; recorded `[:tests :content-hashes]` slot, and dispatches
;; `sidebar/watch-rerun!` for the variants whose hash drifted.
;;
;; Detection runs on the same 500ms cadence as the existing hot-reload
;; poll — both polls share `setInterval` for v1.
;;
;; HOT PATH (rf2-zrswb): `compute-testable-content-hashes` runs every
;; 500ms while watch mode is on. The naïve walk hashed every testable
;; variant on every tick — pr-str of the canonical tuple per variant,
;; cost O(V × variant-body-size). For a corpus of N testable variants
;; the steady-state cost is dominated by serialisation that produces
;; the same hash 99.9% of the time.
;;
;; The cache below keys on `[registrar-mutation-tick, active-modes,
;; substrate]` — the three inputs that can perturb a snapshot-identity
;; hash across two polls (registrar writes invalidate every variant
;; entry; shell-state mode/substrate changes too). When the key matches
;; the previous tick's key, we return the cached map — zero hashing
;; work. When it drifts, we recompute the lot once and re-seed the
;; cache.
;;
;; The `:cell-overrides` slot of shell-state is intentionally NOT in
;; the cache key: `snapshot-identity`'s public surface drops it (only
;; `:active-modes` and `:substrate` are threaded into the tuple), so
;; per-cell control edits do not perturb the watch-mode signal.

(defonce ^:private testable-hash-cache
  (atom {:key nil :hashes nil}))

(defn- compute-testable-content-hashes
  "Walk the registered testable variants and return a `{variant-id →
  hex-hash}` map of snapshot-identity content hashes. The hash captures
  the variant's `:play` / `:events` / `:loaders` / `:decorators` /
  `:tags` slots plus the parent story's slice and the view's registered
  schema-digest (per `re-frame.story.identity` §What's in the hash —
  spec/007 §Variant snapshot identity), so a change to any of those —
  including a decorator-only edit or a view schema change — produces a
  fresh hash.

  HOT PATH (rf2-zrswb): registrar-driven cache short-circuits when
  neither the side-table nor the relevant shell-state slots have
  drifted since the last tick."
  []
  (let [shell  (state/get-state)
        modes  (:active-modes shell)
        subs   (:substrate shell)
        tick   (registrar/current-mutation-tick)
        key    [tick modes subs]
        cached @testable-hash-cache]
    (if (= key (:key cached))
      (:hashes cached)
      (let [testable (state/testable-variant-ids
                       (:variants (state/registry-snapshot)))
            opts     {:active-modes modes :substrate subs}
            hashes   (into {}
                           (map (fn [vid]
                                  [vid (:content-hash
                                         (identity/snapshot-identity vid opts))]))
                           testable)]
        (reset! testable-hash-cache {:key key :hashes hashes})
        hashes))))

(defn detect-watch-drift!
  "When watch mode is on, compute the current testable-variant content
  hashes, diff against the recorded slot, dispatch `watch-rerun!` for
  any drift, and stamp the fresh hashes back into state. No-op when
  watch mode is off or no drift is detected.

  Public so tests can exercise the detector without driving the poll
  interval."
  []
  (when (and config/enabled? (state/test-watch-mode? (state/get-state)))
    (let [current (compute-testable-content-hashes)
          shell   (state/get-state)
          prev    (get-in shell [:tests :content-hashes])
          drifted (state/watch-mode-drift prev current)]
      ;; Always stamp the fresh hashes so the next poll diffs against
      ;; the post-drift baseline (rather than re-firing forever).
      (state/swap-state! state/record-test-content-hashes current)
      (when (seq drifted)
        (sidebar/watch-rerun! drifted)
        true))))

(defn- watch-mode-tick!
  "One pass of the watch-mode detector. Wraps `detect-watch-drift!` in
  a try/catch so a registrar quirk on a single variant doesn't kill
  the interval. Called from the same `setInterval` as the hot-reload
  fingerprint poll."
  []
  (try
    (detect-watch-drift!)
    (catch :default e
      ;; Swallow + breadcrumb. A transient hashing error shouldn't
      ;; take down the chrome widget (the next tick re-tries), but
      ;; the exception itself MUST be observable — per rf2-dd5ze
      ;; audit (SH2): silent swallows on the watch-mode hot-loop hide
      ;; real bugs. `emit-error!` gates on `interop/debug-enabled?`
      ;; so the call DCE's to a no-op in production builds.
      (rf-trace/emit-error!
        :rf.story.shell/watch-mode-tick-failed
        {:exception e
         :where     :rf.story.shell/watch-mode-tick!
         :recovery  :next-tick-retry})
      nil)))

(defonce ^:private hot-reload-poll-handle (atom nil))

(defn- poll-tick!
  "One pass of the shell's polling interval. Runs the fingerprint
  detector (decorator drift → bump `:hot-reload-tick`) followed by the
  watch-mode detector (per-testable-variant snapshot-identity drift →
  dispatch `sidebar/watch-rerun!` when watch mode is on). Two
  detectors, one cadence."
  []
  (detect-and-tick!)
  (watch-mode-tick!))

(defn- start-hot-reload-poll!
  "Begin polling for fingerprint + watch-mode changes. v1 mechanism per
  Stage 4 (rf2-ekai) + rf2-z1h0f.

  Per rf2-8wgpm (tools/story/spec/013-Static-Build.md): under
  `re-frame.story.config/static-mode?` the registrar is frozen — no
  dev-time `reg-*` mutations will land, so the 500ms poll is wasted
  work that thrashes the React tree on every tick. Skip the poll
  entirely; the shell renders against a stable registry."
  []
  (when (and config/enabled?
             (not config/static-mode?)
             (nil? @hot-reload-poll-handle))
    (let [h (js/setInterval poll-tick! 500)]
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
    ;; rf2-sxwvf: drop the per-variant scrub selection too — the cross-
    ;; reference ratom is keyed by variant-id and needs the same
    ;; teardown shape as the history / buffer ratoms.
    (scrubber/drop-selection! variant-id)
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
        (catch :default e
          ;; Swallow + breadcrumb. The canvas re-tries via
          ;; :component-did-mount / :component-did-update, and the
          ;; result-map's error path will surface inline — but the
          ;; exception itself MUST be observable. Per rf2-dd5ze audit
          ;; (SH3): a silent catch on a fundamental-write path (the
          ;; variant-frame allocation that the very next render reads)
          ;; was hiding errors. `emit-error!` gates on `interop/debug-
          ;; enabled?` so the call DCE's to a no-op in production
          ;; builds (where the chrome itself is elided anyway).
          (rf-trace/emit-error!
            :rf.story.shell/ensure-variant-frame-failed
            {:variant-id variant-id
             :exception  e
             :where      :rf.story.shell/ensure-variant-frame!
             :recovery   :canvas-retry})
          nil)))))

;; Per rf2-dd5ze audit (SH3, P1): the prev-selection tracker lives at
;; module scope. The shell is a singleton (`shell-singleton`, below)
;; but its lifecycle is install / tear-down / re-install — a fresh
;; local `(atom ...)` on every `selection-watcher` invocation hides the
;; cross-install lifetime from the reader and risks watch-fn closures
;; capturing an atom whose contents are stale by the time the watch
;; fires. Module-level `defonce` makes the lifetime explicit: one
;; cross-mount tracker, reseeded on every install, cleared on every
;; teardown.
(defonce ^:private selection-prev (atom nil))

(defn- selection-watcher
  "Install a watch on the shell state atom that fires on every
  `:selected-variant` change; on selection edge ensure trace / scrubber
  listeners are wired and the variant's frame is pre-allocated.

  Idempotent: re-installing replaces the previous watch (same key
  `::shell-selection`, so `add-watch` overwrites). The cross-mount
  prev-selection tracker is the module-level `selection-prev` atom —
  reseeded here from the current shell state so the first firing
  diffs against a fresh baseline rather than a value left over from a
  previous mount."
  []
  (reset! selection-prev (:selected-variant @state/shell-state-atom))
  (add-watch state/shell-state-atom
             ::shell-selection
             (fn [_ _ _ new-state]
               (let [now    (:selected-variant new-state)
                     before @selection-prev]
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
                   (reset! selection-prev now))))))

(defn- remove-selection-watcher! []
  (remove-watch state/shell-state-atom ::shell-selection)
  ;; Clear the cross-mount tracker so the next install starts from a
  ;; clean baseline rather than diffing the first selection against a
  ;; value held over from the previous mount.
  (reset! selection-prev nil))

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
     ;; rf2-5yriz — Actions panel.  Reads from the same per-variant
     ;; trace buffer the six-domino panel reads, but filters down to
     ;; dispatches + dispatch-shaped fx-handled emits and renders
     ;; chronologically with pause + clear affordances.  Wired here
     ;; (not via reg-story-panel) because Story's built-in chrome
     ;; panels (trace, scrubber, controls, actions) are always-present
     ;; and have no late-bind contract.
     (when (and (:actions vis) variant-id)
       [actions/panel variant-id])
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
  sections. Per rf2-qmjo the `:test` pane renders `test-mode-view/test-view`
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
                    :test [test-mode-view/test-view variant-id]
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
         ;; rf2-xi9zk: hydrate chrome-wide :active-modes from URL +
         ;; localStorage before the first render of the toolbar /
         ;; canvas. URL wins over localStorage per spec/010 §URL deep-
         ;; link. Idempotent and one-shot.
         (toolbar/hydrate!)
         (start-hot-reload-poll!)
         (selection-watcher)
         ;; rf2-5fc15: install the Test Codegen recorder's trace-bus
         ;; listener once at shell mount. The listener short-circuits
         ;; on every emit when no recording is in flight, so leaving
         ;; it installed is free; we only need to make sure it
         ;; exists before the user clicks REC.
         (recorder-ui/install-trace-listener!)
         ;; rf2-one3t: install the save-as-variant dialog-open callback
         ;; against the pure ns so `save-variant/save-current-as-variant!`
         ;; can drive the modal without coupling the .cljc helper to
         ;; Reagent / DOM. Idempotent.
         (save-variant-ui/install!)
         (when-let [vid (:selected-variant @state/shell-state-atom)]
           (ensure-listeners-for-variant! vid))))
     :component-will-unmount
     (fn [_]
       (stop-hot-reload-poll!)
       (remove-selection-watcher!)
       (recorder-ui/remove-trace-listener!)
       (teardown-all-listeners!))
     :reagent-render
     (fn []
       [:div {:style (:root styles)}
        ;; rf2-xi9zk: chrome-level toolbar — horizontal strip above the
        ;; three-pane row. Exposes every registered :mode as a toggle
        ;; chip; selection writes the chrome-wide :active-modes slot.
        [toolbar/toolbar-strip]
        [:div {:style (:body styles)}
         [sidebar/sidebar]
         [main-pane]
         [right-panel]]
        ;; rf2-381i: first-time help overlay + persistent re-open chip.
        ;; The chip lives in a fixed-position slot so it floats above the
        ;; right inspector pane regardless of which panels are visible.
        [:div {:style (:help-slot styles)}
         [help/help-button]]
        [help/help-host]
        ;; rf2-5fc15: Test Codegen recording overlay (top-right banner
        ;; while a recording is in flight) + save-as-variant dialog
        ;; (opens after stop). Both are fixed-position so they float
        ;; above the three-pane layout. rf2-39u9e adds the mid-recording
        ;; assertion picker — a modal that opens off the overlay's
        ;; `+ assert` button.
        [recorder-ui/recording-overlay]
        [recorder-ui/assertion-picker]
        [recorder-ui/save-dialog]
        ;; rf2-one3t: save-current-canvas-state-as-variant dialog. Lives
        ;; alongside the recorder's save dialog — both float above the
        ;; three-pane layout via fixed positioning; both surface the
        ;; generated EDN snippet for review-then-commit.
        [save-variant-ui/save-dialog]])}))

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
        (rdc/unmount (:root prev))
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
     ;; rf2-fq1yg: `rdc/unmount` requires the React Root, NOT the DOM
     ;; node — `(.unmount root)` is the React 18 contract. Passing the
     ;; DOM node here silently no-op'd (DOM nodes have no `.unmount`
     ;; method), so the shell's root stayed alive on `#app` and a
     ;; subsequent `mount-app!` → `create-root` on the same node fired
     ;; React's "container has already been passed to createRoot before"
     ;; warning. Pass the handle's `:root` slot so React tears down
     ;; cleanly and releases the container.
     (try
       (rdc/unmount (:root handle))
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

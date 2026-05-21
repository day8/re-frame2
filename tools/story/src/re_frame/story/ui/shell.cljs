(ns re-frame.story.ui.shell
  "Top-level story shell. Per Stage 4 (rf2-ekai) IMPL-SPEC §4 + §8.

  Three-pane layout:

      ┌──────────┬──────────────────────────┬────────────┐
      │ sidebar  │ canvas / workspace       │ Causa      │
      │          │                          │ ─────────  │
      │ stories  │ (selected variant or     │ controls   │
      │ tags     │  workspace renders here) │ ─────────  │
      │ ws       │                          │ disp-cons. │
      │          │                          │ ─────────  │
      │          │                          │ status     │
      └──────────┴──────────────────────────┴────────────┘

  Per rf2-sgdd3 the RHS hosts Causa as the primary inspector. The
  Story-shipped scrubber / trace / actions panels were retired —
  Causa's L1 ribbon (◀ ▶ ⏭ + L2 event list) replaces the scrubber;
  the Trace tab replaces the trace panel; the Event-tab cascade view
  + filtered Trace replace the actions panel. The 3 surviving Story
  panels are kept because they're Story-unique:

  - Dispatch Console — free-form dispatch into the variant frame
  - Controls         — per-variant arg controls
  - Status / viewport / backgrounds — play status banner + framing chips

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
            [re-frame.core :as rf]
            [re-frame.story.causa-preset :as causa-preset]
            [re-frame.story.config :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.frames :as frames]
            [re-frame.story.identity :as identity]
            [re-frame.story.play.runner-events :as play-runner-events]
            [re-frame.story.predicates :as pred]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.runtime :as runtime]
            [re-frame.trace :as rf-trace]
            [re-frame.story.ui.backgrounds-switcher :as backgrounds-switcher]
            [re-frame.story.ui.canvas :as canvas]
            [re-frame.story.ui.causa-embed :as causa-embed]
            [re-frame.story.ui.command-palette.view :as command-palette]
            [re-frame.story.ui.controls :as controls]
            [re-frame.story.ui.dispatch-console :as dispatch-console]
            [re-frame.story.ui.docs :as docs]
            [re-frame.story.ui.element-inspector :as element-inspector]
            [re-frame.story.ui.help :as help]
            [re-frame.story.ui.keybindings :as keybindings]
            [re-frame.story.ui.mode-tabs :as mode-tabs]
            [re-frame.story.ui.panels :as panels]
            [re-frame.story.ui.play-status :as play-status]
            [re-frame.story.recorder.dom-capture :as recorder-dom]
            [re-frame.story.ui.recorder :as recorder-ui]
            [re-frame.story.ui.recorder-export-dialog :as recorder-export-ui]
            [re-frame.story.ui.save-variant :as save-variant-ui]
            [re-frame.story.ui.share :as share]
            [re-frame.story.ui.shell.rails :as rails]
            [re-frame.story.ui.url-state :as url-state]
            [re-frame.story.ui.shell-styles :refer [styles]]
            [re-frame.story.ui.sidebar :as sidebar]
            [re-frame.story.theme.typography :as theme-typography]
            [re-frame.story.ui.state :as state]
            [re-frame.story.ui.test-mode.view :as test-mode-view]
            [re-frame.story.ui.toolbar :as toolbar]
            [re-frame.story.ui.trace-buffer :as trace-buffer]
            [re-frame.story.ui.viewport-switcher :as viewport-switcher]
            [re-frame.story.ui.workspace :as workspace]
            [re-frame.story.backgrounds :as backgrounds]
            [re-frame.story.viewport :as viewport]
            [re-frame.story.theme.colors :as colors]
            [re-frame.story.theme.depth :as depth]
            [re-frame.story.theme.motion :as motion]))

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

(defn- existing-frame-fingerprint-drift?
  "True when a frame that was present on the previous poll now reports
  different decorator fingerprints.

  New frames are not hot-reload drift: they were just allocated by
  selection/canvas and already ran with the current registry. Treating
  their first snapshot as drift forces an immediate second run, which
  replays static `:events` and can pollute recorder output. Removed
  frames likewise only need the baseline updated."
  [prev current]
  (boolean
    (some (fn [[variant-id fingerprints]]
            (let [previous (get prev variant-id ::missing)]
              (and (not= previous ::missing)
                   (not= previous fingerprints))))
          current)))

(defn detect-and-tick!
  "Compare the current fingerprint snapshot against the shell state's
  recorded fingerprints; if anything drifted, bump the hot-reload tick
  and stamp the new fingerprints.

  Public so tests can exercise the detector without mounting the shell."
  []
  (when config/enabled?
    (let [current (compute-fingerprint-snapshot)
          shell   (state/get-state)
          prev    (:fingerprints shell)
          drift?  (existing-frame-fingerprint-drift? prev current)]
      (when (not= current prev)
        (state/swap-state!
          (fn [s]
            (cond-> (assoc s :fingerprints current)
              drift? state/bump-hot-reload-tick)))
        drift?))))

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
;; substrate, cell-overrides]` — the four inputs that can perturb a
;; snapshot-identity hash across two polls. Registrar writes invalidate
;; every variant entry; shell-state mode/substrate changes do too; and
;; per-cell control edits land in `:cell-overrides` and ARE threaded
;; through `args/resolve-args` into `snapshot-tuple`'s `:effective-args`
;; slot (identity.cljc:213 + args.cljc:125-134) — they DO perturb the
;; hash. When the key matches the previous tick's key, we return the
;; cached map — zero hashing work. When it drifts, we recompute the lot
;; once and re-seed the cache.
;;
;; Pre-rf2-mclvi the cache key dropped `:cell-overrides` citing
;; `snapshot-identity` as the source of truth, but `snapshot-tuple`
;; DOES read them via `resolve-args` — the cache was stale after every
;; control edit, watch-mode missed re-runs.

(defonce ^:private testable-hash-cache
  (atom {:key nil :hashes nil}))

(defn- compute-testable-content-hashes
  "Walk the registered testable variants and return a `{variant-id →
  hex-hash}` map of snapshot-identity content hashes. The hash captures
  the variant's `:play` / `:events` / `:loaders` / `:decorators` /
  `:tags` slots plus the parent story's slice, the view's registered
  schema-digest, AND the variant's resolved effective args (which fold
  in the user's live `:cell-overrides`). See `re-frame.story.identity`
  §What's in the hash + spec/007 §Variant snapshot identity.

  HOT PATH (rf2-zrswb): registrar-driven cache short-circuits when
  neither the side-table nor the relevant shell-state slots
  (`:active-modes` / `:substrate` / `:cell-overrides`) have drifted
  since the last tick."
  []
  (let [shell      (state/get-state)
        modes      (:active-modes shell)
        subs       (:substrate shell)
        overrides  (:cell-overrides shell)
        tick       (registrar/current-mutation-tick)
        key        [tick modes subs overrides]
        cached     @testable-hash-cache]
    (if (= key (:key cached))
      (:hashes cached)
      (let [testable (state/testable-variant-ids
                       (:variants (state/registry-snapshot)))
            hashes   (into {}
                           (map (fn [vid]
                                  (let [opts {:active-modes   modes
                                              :substrate      subs
                                              :cell-overrides (get overrides vid)}]
                                    [vid (:content-hash
                                           (identity/snapshot-identity vid opts))])))
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
  "Wire the trace-buffer listener for `variant-id` if not already wired.
  Per rf2-sgdd3 the scrubber listener was retired alongside the
  scrubber panel (Causa's L1 ribbon + L2 event list replace it); the
  trace-buffer listener stays because the schema-validation panel
  consumes the per-variant buffer."
  [variant-id]
  (when (and config/enabled?
             (some? variant-id)
             (not (contains? @listened-variants variant-id)))
    (trace-buffer/register-listener! variant-id)
    (swap! listened-variants conj variant-id)))

(defn- teardown-listeners-for-variant!
  [variant-id]
  (when (some? variant-id)
    (trace-buffer/remove-listener! variant-id)
    (trace-buffer/drop-buffer! variant-id)
    (swap! listened-variants disj variant-id)))

(defn- teardown-all-listeners! []
  (doseq [vid @listened-variants]
    (trace-buffer/remove-listener! vid))
  (reset! listened-variants #{})
  (trace-buffer/clear-buffer!))

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
                     (ensure-variant-frame! now)
                     ;; Re-orient Causa's target-frame to the freshly-
                     ;; selected variant's frame. Each Story variant is
                     ;; reg-frame'd under its variant-id (see
                     ;; `re-frame.story.frames`), so the variant-id IS
                     ;; the frame-id from Causa's perspective. Without
                     ;; this dispatch Causa stays anchored on whatever
                     ;; the first-mount seed picked (commonly the boot
                     ;; `:rf/default` or the previously-focused variant)
                     ;; and the App-DB / Event panels render against a
                     ;; frame the user is no longer observing —
                     ;; producing the empty-state-with-stale-frame view
                     ;; the user sees on every variant switch. The
                     ;; `:rf.causa/set-target-frame` handler writes both
                     ;; `:target-frame` AND re-seeds `:epoch-history`
                     ;; from `(rf/epoch-history variant-id)` in lockstep
                     ;; (per Causa's `epoch.cljs` reducer), so the L2
                     ;; list, App-DB diff and downstream subs all flip
                     ;; in one frame.
                     ;;
                     ;; `dispatch-sync` (rather than `dispatch*`) so the
                     ;; slot is observable immediately — the watcher
                     ;; runs outside any event-handler / drain cycle
                     ;; (it's an atom-watch callback on the shell
                     ;; ratom), so the in-drain guard does not apply.
                     ;; The rf2-q9kv5 sibling dispatches in
                     ;; `causa-preset/on-variant-selected!` below still
                     ;; ride the async queue — they're Causa's own
                     ;; preset-applies and don't gate the next-frame
                     ;; panel paint the way the target-frame slot does.
                     (rf/with-frame :rf/causa
                       (rf/dispatch-sync [:rf.causa/set-target-frame now]))
                     ;; rf2-v1ach: Causa now mounts per-panel into the
                     ;; RHS via `causa-embed/causa-embed-panel`. The
                     ;; embed owns its own React lifecycle — selecting
                     ;; a variant rebuilds the panel-host component
                     ;; (keyed on `variant-id::panel-id`) which drives
                     ;; the Causa mount-fn on commit. We retain the
                     ;; per-variant project-root + keybinding bridges
                     ;; so the popout escape hatch + Causa's source-
                     ;; coord chips honour Story's configured
                     ;; `:rf.story/project-root`. Per-variant Causa bridges
                     ;; live on a separate seam from the embed mount.
                     (causa-preset/wire-cross-host!)
                     ;; rf2-q9kv5: apply any per-story Causa preset
                     ;; (focus tab, configure filters, focus a cascade
                     ;; position) + seed the RHS chip-row's user-
                     ;; override slot from the story's `:causa-panel`.
                     (causa-preset/on-variant-selected! now)
                     ;; rf2-8i2a9: auto-run the variant's `:play-script`
                     ;; if `:auto-run?` is true. Best-effort — yields one
                     ;; tick via setTimeout so React commits the canvas
                     ;; before the script's first dispatch lands; that
                     ;; way `[:assert-dom selector :visible]` sees the
                     ;; mounted DOM. Variants without `:play-script` no-op.
                     (js/setTimeout
                       (fn [] (play-runner-events/auto-run! now))
                       0))
                   (reset! selection-prev now))))))

(defn- remove-selection-watcher! []
  (remove-watch state/shell-state-atom ::shell-selection)
  ;; Clear the cross-mount tracker so the next install starts from a
  ;; clean baseline rather than diffing the first selection against a
  ;; value held over from the previous mount.
  (reset! selection-prev nil))

;; ---- url-state apply-fn ---------------------------------------------------
;;
;; rf2-o4u18: bridge between the pure `url-state` engine and the live
;; registrar / preset tables. `url-state/apply-parsed-to-state` takes a
;; validators map; we build it here so the engine ns stays portable
;; (CLJC) and the live wiring sits in one place.

(defn- url-state-apply-fn
  "Build the `apply-fn` `url-state` consumes — closes over the live
  registrar + viewport + backgrounds tables. Returns a `(state →
  parsed → state')` fn."
  []
  (let [validators {:variant?    (fn [vid]
                                   (or (nil? vid)
                                       (registrar/registered? :variant vid)))
                    :workspace?  (fn [wid]
                                   (or (nil? wid)
                                       (registrar/registered? :workspace wid)))
                    :viewport?   (fn [v]
                                   (or (nil? v) (viewport/valid-selection? v)))
                    :background? (fn [b]
                                   (or (nil? b) (backgrounds/valid-selection? b)))}]
    (fn [state parsed]
      (url-state/apply-parsed-to-state state parsed validators))))

(defn- hydrate-url-state!
  "One-shot URL → shell-state hydration on mount. Also folds the
  `share/parse-overrides-param*` dropped-entries hint for the focused
  variant (rf2-9jthx parity) and selects the variant through the
  existing `share/hydrate-from-url!` cell-overrides path so the
  selection-watcher's preallocate-frame branch fires.

  Order:
    1. `url-state/hydrate-from-url!` writes selection / mode-tab /
       modes / viewport / background / tag-filter / substrate.
    2. `share/hydrate-from-url!` adds cell-overrides + the
       share-import hint (idempotent; selection is already set)."
  []
  (url-state/hydrate-from-url! state/shell-state-atom (url-state-apply-fn))
  (share/hydrate-from-url!))

;; ---- the top-level component ---------------------------------------------

(defn- right-panel
  "The right-side pane — Causa mount + controls + dispatch console +
  Stage-6 registered story-panels stacked vertically.

  Per rf2-sgdd3 Causa is the primary RHS inspector: the Story-shipped
  scrubber / trace / actions panels were retired in favour of Causa's
  ribbon + L2 event list (replaces scrubber), Trace tab (replaces trace
  panel), and Event-tab cascade view (replaces actions panel). Causa
  mounts into the `[data-rf-causa-host]` slot below via its standard
  `mount/open!` flow — driven from the selection-watcher whenever a
  variant becomes focused (see `ensure-causa-mounted!`).

  Stage 6 (rf2-zhwd) adds `panels/render-panels-at-placement` so any
  `reg-story-panel` registration with `:placement :right` appears here.
  The built-in v1.0 panels (a11y, layout-debug toggles, schema
  validation) ride this path.

  Renders as an `<aside>` landmark (per rf2-xc65) so screen readers can
  jump straight to the inspectors and so axe-core's
  `region`/`landmark-one-main` rules pass. `tabindex=\"0\"` makes the
  scrollable container reachable for keyboard users."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        vis        (:panel-visibility shell)
        widths     (rails/current-widths)
        narrow?    (rails/narrow-viewport?)]
    [:aside {:style      (merge (:right styles)
                                {:width (str (:right widths) "px")
                                 :animation (motion/stagger-animation :right)}
                                (when narrow? (:right-narrow styles)))
             :data-test  "story-inspectors"
             :role       "complementary"
             :aria-label "Inspectors"
             :tab-index  "0"}
     ;; rf2-v1ach — Causa-in-Story per-panel embed. Replaces the
     ;; pre-rf2-v1ach whole-shell mount that crammed Causa's 4-layer
     ;; chrome into a 320px column. The new shape: ONE Causa panel
     ;; mounted at a time, chip-row picker for runtime swap, popout
     ;; chip for the full-shell escape hatch. Per-story author
     ;; intent rides the `:causa-panel` slot (or legacy
     ;; `:causa :panel`); user clicks override for the session.
     ;;
     ;; Feature-detect-safe: `causa-embed-panel` renders a graceful
     ;; empty state when Causa is not on the classpath.
     [:section {:style (:rhs-section styles)
                :data-rf-rhs-section "causa"}
      [:div {:style (merge (:rhs-section-h styles)
                           (:rhs-section-h-accent styles))}
       [:span "Causa"]
       [:span {:style {:font-weight "400"
                       :color (:text-tertiary colors/tokens)
                       :letter-spacing "0.04em"}}
        "diagnostic"]]
      [causa-embed/causa-embed-panel]]
     (when (:controls vis)
       [:section {:style (:rhs-section styles)
                  :data-rf-rhs-section "controls"}
        [:div {:style (:rhs-section-h styles)}
         [:span "Controls"]
         [:span {:style {:font-weight "400"
                         :color (:text-tertiary colors/tokens)
                         :letter-spacing "0.04em"}}
          "args + modes"]]
        [controls/panel variant-id]])
     ;; rf2-q9kv5 — Dispatch Console panel. Free-form event dispatch into
     ;; the running variant's frame. Default HIDDEN; opt-in via
     ;; `:dispatch-console? true` on the story or variant body. The
     ;; per-story flag wins over the chrome-level `:panel-visibility`
     ;; slot's default (false). The shell stores the explicit visibility
     ;; toggle under `:panel-visibility :dispatch-console` so the user can
     ;; flip it without editing the story body; the story-body flag is the
     ;; default visibility for that variant.
     ;;
     ;; Default-off chosen so the chrome-level toolbar chip starts in the
     ;; un-pressed state. The toolbar/recorder/review-dialog browser gate
     ;; scans `[data-test="story-toolbar"] [aria-pressed="true"]` for its
     ;; reset assertion (`count === 0`); a default-on dispatch-console
     ;; chip would break that gate. Toolbar real-estate is precious too —
     ;; opt-in is the more polite default.
     (when (and variant-id
                (let [vis-flag (:dispatch-console vis)
                      var-body (registrar/handler-meta :variant variant-id)
                      story-id (pred/parent-story-id variant-id)
                      sty-body (when story-id
                                 (registrar/handler-meta :story story-id))
                      ;; Story slot default; variant overrides; user toggle wins.
                      story-default (get sty-body  :dispatch-console? false)
                      var-default   (get var-body  :dispatch-console? story-default)]
                  (cond
                    (true?  vis-flag) true
                    (false? vis-flag) false
                    :else             var-default)))
       [:section {:style (:rhs-section styles)
                  :data-rf-rhs-section "dispatch"}
        [:div {:style (:rhs-section-h styles)}
         [:span "Dispatch"]
         [:span {:style {:font-weight "400"
                         :color (:text-tertiary colors/tokens)
                         :letter-spacing "0.04em"}}
          "free-form events"]]
        [dispatch-console/panel variant-id]])
     ;; Stage 6: render any registered :right-placement story panels.
     ;; Wrapped in a section so the visual rhythm holds across Story-
     ;; shipped + user-registered panels uniformly.
     (when variant-id
       [:section {:style (:rhs-section styles)
                  :data-rf-rhs-section "story-panels"}
        [panels/render-panels-at-placement :right variant-id vis]])]))

(defn- framed-canvas
  "Render the variant `canvas/canvas` wrapped with the effective
  viewport + background framing (rf2-zll4h).

  Per rf2-zgu68 also surfaces a viewport-px chip at the bottom-right
  when a non-`:full` viewport mode is active. The chip self-elides
  for `:full`."
  []
  (let [vp        (viewport-switcher/effective-viewport)
        bg        (backgrounds-switcher/effective-background)
        vp-style  (viewport/wrap-style vp)
        bg-style  (backgrounds/wrap-style bg)
        wrap-style (merge {:padding    "16px"
                           :display    "flex"
                           :flex       "1"
                           :min-height "200px"
                           :align-items "flex-start"
                           :justify-content "center"
                           :overflow   "auto"
                           :box-sizing "border-box"
                           ;; rf2-zgu68 — position relative so the
                           ;; viewport-px chip's absolute slot anchors
                           ;; against the framed region.
                           :position   "relative"}
                          bg-style)
        sized?   (some? vp-style)]
    [:div {:style       wrap-style
           :data-test   "story-canvas-frame"
           :data-viewport (name (viewport-switcher/effective-id))
           :data-background (name (backgrounds-switcher/effective-id))}
     (if sized?
       [:div {:style     vp-style
              :data-test "story-canvas-frame-sized"}
        [canvas/canvas]]
       [canvas/canvas])
     ;; rf2-zgu68 — viewport-px indicator chip. Self-elides for `:full`.
     [canvas/viewport-indicator vp]]))

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
        story-id   (:selected-story shell)
        vis        (:panel-visibility shell)
        mode-tab   (when variant-id
                     (state/active-mode-tab shell variant-id))]
    [:main {:style (merge (:main styles)
                          {:animation (motion/stagger-animation :main)})
            :aria-label "Story canvas"}
     ;; rf2-9hc8: top-of-shell mode-tab strip — only when a single
     ;; variant is selected (workspaces enumerate multiple variants and
     ;; have their own layout switcher; mixing mode-tabs into that pane
     ;; conflates two unrelated UX axes).
     (when (and variant-id (not ws-id))
       [mode-tabs/mode-tabs-strip variant-id])
     ;; rf2-8i2a9: play-script failure banner. Lives ABOVE the canvas
     ;; so a failed run is the first thing the user sees. Self-elides
     ;; when the run is not in `:fail`. Click-to-highlight points the
     ;; user at the failing DOM selector.
     (when (and variant-id (not ws-id))
       [play-status/banner-when-enabled variant-id])
     (cond
       ws-id    [workspace/workspace-view ws-id]
       variant-id (case mode-tab
                    :docs [docs/docs-view variant-id]
                    :test [test-mode-view/test-view variant-id]
                    ;; rf2-zll4h — wrap the canvas with viewport sizing
                    ;; + background colour. The :docs and :test panes
                    ;; are NOT framed (they're shell chrome, not the
                    ;; variant render surface).
                    [framed-canvas])
       ;; rf2-8j7wg (audit C-4) — per-story rollup docs page. The
       ;; sidebar's story-header rows dispatch `select-story` which
       ;; lands here. Variant + workspace selection take precedence
       ;; (the user navigated FROM the rollup into a leaf).
       story-id [docs/docs-rollup-view story-id]
       :else
       [:div {:style {:padding "32px"
                      :color (:text-tertiary colors/tokens)
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
         ;; rf2-2rwdc: inject IBM Plex `@font-face` rules into the
         ;; document head BEFORE the first render so the chrome's
         ;; `font-family: "IBM Plex Sans"` / `"IBM Plex Mono"`
         ;; declarations resolve immediately. Idempotent — the helper's
         ;; internal sentinel collapses subsequent calls. Safe in static
         ;; builds (re-frame.story.config/static-mode? still passes
         ;; config/enabled?; the helper renders <style>@font-face..</style>
         ;; which static export captures correctly).
         (theme-typography/inject-font-faces!)
         ;; rf2-3lt89: inject motion @keyframes + prefers-reduced-motion
         ;; override stylesheet. Defines the `rf-story-mount-in` /
         ;; `rf-story-overlay-in` / `rf-story-chip-press` keyframes the
         ;; chrome refers to via inline `:animation` slots, plus the
         ;; canonical `[data-rf-story-root] *:focus-visible` outline so
         ;; the focus ring is uniform across every chrome surface.
         ;; Idempotent. Behind config/enabled? — production short-
         ;; circuits before the DOM touch.
         (motion/inject-motion-css!)
         ;; rf2-ypd6h: inject grain overlay stylesheet — an SVG-feTurbulence
         ;; noise sheet rendered as a `::before` pseudo on
         ;; `[data-rf-story-root]` so the bare slate grounds carry studio
         ;; texture rather than reading as 'editor pane'. Self-elides on
         ;; prefers-contrast more / when config/enabled? is false.
         ;; Idempotent.
         (depth/inject-grain-css!)
         ;; rf2-xi9zk: hydrate chrome-wide :active-modes from URL +
         ;; localStorage before the first render of the toolbar /
         ;; canvas. URL wins over localStorage per spec/010 §URL deep-
         ;; link. Idempotent and one-shot.
         (toolbar/hydrate!)
         ;; rf2-zll4h: hydrate the chrome-wide viewport + background
         ;; selections from localStorage. Both are idempotent and
         ;; one-shot; they no-op when the slot is already populated
         ;; (e.g. a programmatic test fixture seeded them).
         (viewport-switcher/hydrate!)
         (backgrounds-switcher/hydrate!)
         (start-hot-reload-poll!)
         (selection-watcher)
         ;; rf2-o4u18: hydrate the URL-state slots (workspace + mode-tab +
         ;; viewport + background + tag-filter + variant / modes /
         ;; substrate) BEFORE the per-variant overrides — the URL-state
         ;; engine sets the focused selection, then share/hydrate-from-url!
         ;; folds in cell-overrides + the share-import hint (rf2-9jthx).
         ;; Selection runs through the same shell-state-atom swap path
         ;; the user's click would take, so the selection-watcher's
         ;; preallocate-frame branch fires for deep-linked variants.
         (hydrate-url-state!)
         ;; rf2-o4u18: subscribe to popstate so the back-button restores
         ;; prior URL state, and watch shell-state-atom so user-driven
         ;; selection / viewport / background / tag-filter changes
         ;; pushState the canonical URL. Cell-override edits are NOT
         ;; pushed (too chatty) — they ride the share popover.
         (url-state/install-popstate-listener!
           state/shell-state-atom
           (url-state-apply-fn))
         (url-state/install-state-watcher! state/shell-state-atom)
         ;; rf2-5fc15: install the Test Codegen recorder's trace-bus
         ;; listener once at shell mount. The listener short-circuits
         ;; on every emit when no recording is in flight, so leaving
         ;; it installed is free; we only need to make sure it
         ;; exists before the user clicks REC.
         (recorder-ui/install-trace-listener!)
         ;; rf2-d5u89: install the recorder's DOM-capture listeners
         ;; on the canvas root so :click / :input / :change / :submit
         ;; events translate into [:dom/click ...] / [:dom/type ...]
         ;; / [:dom/submit ...] entries on the recorder's :entries
         ;; stream. Delegate to a one-tick `setTimeout` so the React
         ;; tree has committed the `[data-test=\"story-canvas-frame\"]`
         ;; root before the install tries to find it. The listener
         ;; short-circuits when no recording is in flight, so the
         ;; install is free even when REC is idle.
         (js/setTimeout
           (fn []
             (recorder-dom/install!)
             ;; rf2-h0jc0: element-level click-to-code inspector. Hooks
             ;; mousemove / click / keydown on the same canvas root the
             ;; recorder uses. Listener gates on `(inspector/active?)`
             ;; so the install is free when the chip is off — install
             ;; once at shell-mount, gate per-event at the listener
             ;; head. Same one-tick `setTimeout` discipline as the
             ;; recorder so the React tree has committed the canvas-
             ;; frame DOM node before the install tries to find it.
             (element-inspector/install!))
           0)
         ;; rf2-one3t: install the save-as-variant dialog-open callback
         ;; against the pure ns so `save-variant/save-current-as-variant!`
         ;; can drive the modal without coupling the .cljc helper to
         ;; Reagent / DOM. Idempotent.
         (save-variant-ui/install!)
         (rails/hydrate!)
         ;; rf2-g8l8x — hydrate per-panel chrome-visibility map from
         ;; localStorage BEFORE the embed-flag pass (embed is URL-
         ;; driven, must not be clobbered by stale persisted slot).
         (keybindings/hydrate!)
         ;; rf2-pucku — hydrate the `:embed?` slot from the
         ;; `?embed=1` URL flag. One-shot at mount.
         (url-state/hydrate-embed-flag! state/shell-state-atom)
         ;; rf2-g8l8x / rf2-p3i0t — install the chrome-level hotkey
         ;; listener. Cmd-K palette ships its own listener.
         (keybindings/install!)
         (when-let [vid (:selected-variant @state/shell-state-atom)]
           (ensure-listeners-for-variant! vid)
           ;; rf2-v1ach: per-panel embed manages its own mount on
           ;; commit. The cross-host bridges (project-root +
           ;; keybinding detach) still need to fire so the popout
           ;; escape hatch + Causa's source-coord chips resolve
           ;; against Story's `:rf.story/project-root`.
           (causa-preset/wire-cross-host!)
           ;; rf2-q9kv5: apply per-story preset on the mount-time
           ;; selection too (the selection-watcher only fires on
           ;; change, so a pre-selected variant would otherwise miss
           ;; the preset).
           (causa-preset/on-variant-selected! vid)
           ;; rf2-8i2a9: mount-time auto-run for an already-selected
           ;; variant (deep-link / persisted selection). Yields a tick
           ;; so the canvas has committed before the script's first
           ;; DOM-sensitive step runs.
           (js/setTimeout
             (fn [] (play-runner-events/auto-run! vid))
             0))))
     :component-will-unmount
     (fn [_]
       (stop-hot-reload-poll!)
       (remove-selection-watcher!)
       ;; rf2-g8l8x / rf2-p3i0t — tear down the chrome-level hotkey
       ;; listener so a re-mount doesn't accumulate handlers.
       (keybindings/remove!)
       (recorder-ui/remove-trace-listener!)
       ;; rf2-d5u89: tear down DOM-capture listeners alongside the
       ;; trace listener so we don't leak listeners across re-mounts.
       (recorder-dom/remove!)
       ;; rf2-h0jc0: tear down the element-inspector listeners +
       ;; reset its mode flag. Mirrors the recorder-dom shape — both
       ;; rides the canvas-root listener install lifecycle.
       (element-inspector/remove!)
       ;; rf2-o4u18: drop popstate + shell-state URL watchers so a
       ;; re-mount doesn't accumulate listeners.
       (url-state/tear-down! state/shell-state-atom)
       (teardown-all-listeners!))
     :reagent-render
     (fn []
       (let [widths     (rails/current-widths)
             narrow?    (rails/narrow-viewport?)
             ;; rf2-p3i0t / rf2-g8l8x / rf2-pucku — chrome visibility
             ;; resolution. Embed-mode + full-screen both hide every
             ;; chrome pane; per-pane toggles win when neither absolute
             ;; mode is on.
             shell      @state/shell-state-atom
             show-tb?   (state/chrome-pane-visible? :toolbar shell)
             show-sb?   (state/chrome-pane-visible? :sidebar shell)
             show-rhs?  (state/chrome-pane-visible? :rhs shell)]
         [:div {:style (:root styles)
                :data-rf-story-root true
                :data-rf-chrome-fullscreen (str (get-in shell [:chrome-visibility :full-screen?] false))
                :data-rf-chrome-embed      (str (get-in shell [:chrome-visibility :embed?] false))}
          ;; rf2-g8l8x — `t` key + embed/full-screen suppress the strip.
          (when show-tb?
            [:div {:style {:animation (motion/stagger-animation :toolbar)
                           :flex-shrink "0"}}
             [toolbar/toolbar-strip]])
          [:div {:style (merge (:body styles)
                               (when narrow? (:body-narrow styles)))}
           ;; rf2-g8l8x / rf2-p3i0t / rf2-pucku — sidebar visibility.
           (when show-sb?
             [sidebar/sidebar {:style (merge {:width       (str (:left widths) "px")
                                              :flex-basis  (str (:left widths) "px")
                                              :flex-shrink "0"
                                              :animation   (motion/stagger-animation :sidebar)}
                                             (when narrow?
                                               {:width "auto"
                                                :flex-basis "auto"
                                                :max-height "42vh"}))}])
           (when (and show-sb? (not narrow?))
             [rails/splitter :left])
           [main-pane]
           (when (and show-rhs? (not narrow?))
             [rails/splitter :right])
           ;; rf2-g8l8x / rf2-p3i0t / rf2-pucku — RHS visibility.
           (when show-rhs?
             [right-panel])]
          ;; rf2-381i: first-time help overlay + persistent re-open chip.
          ;; The chip lives in a fixed-position slot so it floats above the
          ;; chrome regardless of which panels are visible. Per rf2-pxeko
          ;; the slot is top-LEFT — the top-right corner is reserved for
          ;; the Test-Codegen REC chip + recording-overlay banner; a
          ;; floating `?` on the right occluded the REC affordance.
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
          ;; rf2-h0jc0: element-inspector hover overlay. Self-elides
          ;; when inspect mode is off OR no element is currently
          ;; hovered. Lives in the chrome layer (fixed positioning)
          ;; so it floats above the three-pane layout regardless of
          ;; which panels are visible.
          [element-inspector/overlay]
          ;; rf2-x9zsr — Test Codegen :play-script export dialog. Opens
          ;; off the recorder save-dialog's [export as :play-script]
          ;; button; stacks above via a higher z-index. Lives in its own
          ;; ratom so dismiss / reopen doesn't disturb the parent dialog.
          [recorder-export-ui/export-dialog]
          ;; rf2-one3t: save-current-canvas-state-as-variant dialog. Lives
          ;; alongside the recorder's save dialog — both float above the
          ;; three-pane layout via fixed positioning; both surface the
          ;; generated EDN snippet for review-then-commit.
          [save-variant-ui/save-dialog]
          [command-palette/command-palette-host]]))}))

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
     ;; node — `(.unmount root)` is the React 19 client-Root contract. Passing the
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

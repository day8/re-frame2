(ns re-frame.story.ui.shell
  "Top-level story shell. Per Stage 4 (rf2-ekai) `003-Render-Shell.md` §Shell lifecycle.

  Three-pane layout:

      ┌──────────┬──────────────────────────┬────────────┐
      │ sidebar  │ canvas / workspace       │ Xray      │
      │          │                          │ ─────────  │
      │ stories  │ (selected variant or     │ controls   │
      │ tags     │  workspace renders here) │ ─────────  │
      │ ws       │                          │ disp-cons. │
      │          │                          │ ─────────  │
      │          │                          │ status     │
      └──────────┴──────────────────────────┴────────────┘

  Per rf2-sgdd3 the RHS hosts Xray as the primary inspector. The
  Story-shipped scrubber / trace / actions panels were retired —
  Xray's L1 ribbon (◀ ▶ ⏭ + L2 event list) replaces the scrubber;
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

  Per `003-Render-Shell.md` §Shell lifecycle + Stage 4 spec the shell watches the variant /
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
            [re-frame.story.xray-preset :as xray-preset]
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
            [re-frame.story.ui.xray-embed :as xray-embed]
            [re-frame.story.ui.command-palette.view :as command-palette]
            [re-frame.story.ui.controls :as controls]
            [re-frame.story.ui.dispatch-console :as dispatch-console]
            [re-frame.story.ui.docs :as docs]
            [re-frame.story.ui.element-inspector :as element-inspector]
            [re-frame.story.ui.evidence-spine :as evidence-spine]
            [re-frame.story.ui.explain-panel :as explain-panel]
            [re-frame.story.ui.help :as help]
            [re-frame.story.ui.keybindings :as keybindings]
            [re-frame.story.ui.mode-tabs :as mode-tabs]
            [re-frame.story.ui.multi-substrate :as multi-substrate]
            [re-frame.story.ui.panels :as panels]
            [re-frame.story.ui.play-status :as play-status]
            [re-frame.story.ui.promotion :as promotion]
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
            [re-frame.story.theme.depth :as depth]
            [re-frame.story.theme.motion :as motion]))

;; Styles live in `re-frame.story.ui.shell-styles` (pure-data leaf,
;; no Reagent dep). Required as `styles` above so the in-file call
;; sites (`(:root styles)` etc.) stay textually identical.

;; ---- hot-reload trigger --------------------------------------------------

(defn- compute-fingerprint-snapshot
  "Walk every running variant frame and capture its current decorator
  fingerprint map. Pure data → data.

  rf2-eyrpr — thread each frame's per-run opts (`:active-modes` +
  per-variant `:cell-overrides`) into `resolution-fingerprints` so the
  plan compile that yields the decorator ref list substitutes `[:arg]`
  keys resolvable only through a mode / cell layer rather than throwing
  on the 500ms hot-reload poll. Fingerprints are body-derived and run-
  layer-invariant; the opts only let the ref collection's compile succeed."
  []
  (let [shell        (state/get-state)
        active-modes (:active-modes shell)]
    (into {}
          (map (fn [vid]
                 [vid (decorators/resolution-fingerprints
                        vid
                        {:active-modes   active-modes
                         :cell-overrides (get-in shell [:cell-overrides vid])})]))
          (frames/variant-frames))))

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
  the variant's `:play-script` / `:events` / `:loaders` / `:decorators` /
  `:tags` slots plus the parent story's slice, the view's registered
  schema-digest, AND the variant's resolved effective args (which fold
  in the user's live `:cell-overrides`). See `re-frame.story.identity`
  §What's in the hash + /spec/007-Stories.md §Variant snapshot identity.

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
  scrubber panel (Xray's L1 ribbon + L2 event list replace it); the
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
                     ;; Re-orient Xray's target-frame to the freshly-
                     ;; selected variant's frame. Each Story variant is
                     ;; reg-frame'd under its variant-id (see
                     ;; `re-frame.story.frames`), so the variant-id IS
                     ;; the frame-id from Xray's perspective. Without
                     ;; this dispatch Xray stays anchored on whatever
                     ;; the first-mount seed picked (commonly the boot
                     ;; `:rf/default` or the previously-focused variant)
                     ;; and the App-DB / Event panels render against a
                     ;; frame the user is no longer observing —
                     ;; producing the empty-state-with-stale-frame view
                     ;; the user sees on every variant switch. The
                     ;; `:rf.xray/set-target-frame` handler writes both
                     ;; `:target-frame` AND re-seeds `:epoch-history`
                     ;; from `(rf/epoch-history variant-id)` in lockstep
                     ;; (per Xray's `epoch.cljs` reducer), so the L2
                     ;; list, App-DB diff and downstream subs all flip
                     ;; in one frame.
                     ;;
                     ;; `dispatch-sync` (rather than `dispatch*`) so the
                     ;; slot is observable immediately — the watcher
                     ;; runs outside any event-handler / drain cycle
                     ;; (it's an atom-watch callback on the shell
                     ;; ratom), so the in-drain guard does not apply.
                     ;; The rf2-q9kv5 sibling dispatches in
                     ;; `xray-preset/on-variant-selected!` below still
                     ;; ride the async queue — they're Xray's own
                     ;; preset-applies and don't gate the next-frame
                     ;; panel paint the way the target-frame slot does.
                     (rf/with-frame :rf/xray
                       (rf/dispatch-sync [:rf.xray/set-target-frame now]))
                     ;; rf2-v1ach: Xray now mounts per-panel into the
                     ;; RHS via `xray-embed/xray-embed-panel`. The
                     ;; embed owns its own React lifecycle — selecting
                     ;; a variant rebuilds the panel-host component
                     ;; (keyed on `variant-id::panel-id`) which drives
                     ;; the Xray mount-fn on commit. We retain the
                     ;; per-variant project-root + keybinding bridges
                     ;; so the popout escape hatch + Xray's source-
                     ;; coord chips honour Story's configured
                     ;; `:rf.story/project-root`. Per-variant Xray bridges
                     ;; live on a separate seam from the embed mount.
                     (xray-preset/wire-cross-host!)
                     ;; rf2-q9kv5: apply any per-story Xray preset
                     ;; (focus tab, configure filters, focus a cascade
                     ;; position) + seed the RHS chip-row's user-
                     ;; override slot from the story's `:xray-panel`.
                     (xray-preset/on-variant-selected! now)
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

;; rf2-chi9j3: `component-did-mount` schedules a mount-time auto-run for an
;; already-selected variant (deep-link / persisted selection) AFTER
;; `hydrate-url-state!` runs. But `hydrate-url-state!` may ITSELF change
;; `:selected-variant` (a `?variant=…` deep link) — and that selection
;; change fires the already-installed `selection-watcher`, which schedules
;; its OWN auto-run for the same variant. Without a guard, both paths
;; schedule a `js/setTimeout` calling `play-runner-events/auto-run!` for
;; the same variant, so its `:play-script` runs (and every event in it
;; dispatches) TWICE.
;;
;; `mount-time-autorun-vid` is the pure decision this guard reduces to: it
;; takes the selection observed BEFORE `hydrate-url-state!` ran and the
;; CURRENT (post-hydration) selection, and returns the variant-id the
;; mount-time block should schedule an auto-run for, or nil to skip.
;;
;;   - `pre-hydrate-vid` = `post-hydrate-vid` (unchanged across hydration,
;;     including both nil) — the selection was ALREADY current before this
;;     mount (a persisted / re-mount selection the watcher never saw as a
;;     change, so it never scheduled anything) → the mount-time block is
;;     the ONLY scheduler; return `post-hydrate-vid`.
;;   - `pre-hydrate-vid` ≠ `post-hydrate-vid` — `hydrate-url-state!` JUST
;;     changed the selection during THIS mount (a deep link), which means
;;     `selection-watcher`'s change-handler already fired and scheduled its
;;     own auto-run → return nil (skip; scheduling here would double-run).
;;
;; Pure data → data, so it is unit-testable without mounting the shell.
(defn- mount-time-autorun-vid
  [pre-hydrate-vid post-hydrate-vid]
  (when (and post-hydrate-vid (= pre-hydrate-vid post-hydrate-vid))
    post-hydrate-vid))

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
                                   (or (nil? b) (backgrounds/valid-selection? b)))
                    ;; rf2-dxz4sg: validate the URL substrate against the live
                    ;; substrate registry so a stale/unregistered `substrate=`
                    ;; degrades to the `:reagent` default rather than pinning a
                    ;; substrate the host app never registered. nil (omitted)
                    ;; is accepted here and resolved to `:reagent` inside
                    ;; apply-parsed-to-state.
                    :substrate?  (fn [s]
                                   (or (nil? s)
                                       (contains? (multi-substrate/registered-substrates) s)))}]
    (fn [state parsed]
      (url-state/apply-parsed-to-state state parsed validators))))

(defn- hydrate-url-state!
  "One-shot URL → shell-state hydration on mount, in two passes with a
  CLEAR single-owner split (rf2-ovb1en):

    1. `url-state/hydrate-from-url!` is the AUTHORITATIVE owner of every
       URL-owned slot it writes — selection (variant / workspace),
       mode-tab, modes, viewport, background, tag-filter, substrate — and
       VALIDATES each against the live registry (a stale `substrate=ghost`
       degrades to `:reagent`; an unregistered variant degrades to no
       selection). It also installs the raw parsed cell-overrides under
       `[:cell-overrides variant-id]`.
    2. `share/hydrate-from-url!` is the AUTHORITATIVE owner of the
       focused-variant cell-overrides slice on mount: it applies the
       declared-key DRIFT filter `url-state` cannot run (renamed / removed
       args), writing the filtered slice (or CLEARING it when all overrides
       are stale) and recording the share-import drift hint (rf2-9jthx).
       It reads — never rewrites — `url-state`'s validated selection and
       substrate, so the second pass can never undo the first pass's
       validation (the bug rf2-ovb1en fixed).

  The selection-watcher's preallocate-frame branch fires on pass 1's swap
  (it sets `:selected-variant`), so deep-linked variants still preallocate
  without pass 2 re-selecting.

  The popstate handler (below, `install-popstate-listener!`) runs the SAME
  two passes for Back/Forward navigation — pass 1 as its `apply-fn`, pass 2
  as its `post-apply-fn` — so a stale override survives no better via
  history navigation than it would via a fresh mount (rf2-cmjly3 finding 8)."
  []
  (url-state/hydrate-from-url! state/shell-state-atom (url-state-apply-fn))
  (share/hydrate-from-url!))

;; ---- the top-level component ---------------------------------------------

(defn- right-panel
  "The right-side pane — Xray mount + controls + dispatch console +
  Stage-6 registered story-panels stacked vertically.

  Per rf2-sgdd3 Xray is the primary RHS inspector: the Story-shipped
  scrubber / trace / actions panels were retired in favour of Xray's
  ribbon + L2 event list (replaces scrubber), Trace tab (replaces trace
  panel), and Event-tab cascade view (replaces actions panel). Xray
  mounts into the `[data-rf-xray-host]` slot below via its standard
  `mount/open!` flow — driven from the selection-watcher whenever a
  variant becomes focused (config bridges via
  `xray-preset/wire-cross-host!`).

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
     ;; rf2-v1ach — Xray-in-Story per-panel embed. Replaces the
     ;; pre-rf2-v1ach whole-shell mount that crammed Xray's 4-layer
     ;; chrome into a 320px column. The new shape: ONE Xray panel
     ;; mounted at a time, chip-row picker for runtime swap, popout
     ;; chip for the full-shell escape hatch. Per-story author
     ;; intent rides the `:xray-panel` slot (or legacy
     ;; `:xray :panel`); user clicks override for the session.
     ;;
     ;; Feature-detect-safe: `xray-embed-panel` renders a graceful
     ;; empty state when Xray is not on the classpath.
     [:section {:style (:rhs-section styles)
                :data-rf-rhs-section "xray"}
      ;; The Story↔Xray seam (spec/018 §12.9): this is the ONE RHS band
      ;; whose header goes COOL. The violet accent + violet underline
      ;; echo Xray's own identity so the diagnostic boundary reads from
      ;; temperature alone — Story still owns the title row + chip row,
      ;; only the accent points at the embedded surface.
      [:div {:style (merge (:rhs-section-h styles)
                           (:rhs-section-h-xray styles))}
       [:span "Xray"]
       [:span {:style (:rhs-section-sub-xray styles)}
        "diagnostic"]]
      [xray-embed/xray-embed-panel]]
     ;; rf2-ba86n.9 — Explain panel. The Story-owned provenance +
     ;; lowering surface over `story/explain` data (spec/020 §4). Sits
     ;; directly under the Xray embed: same RHS inspector rail, but a
     ;; STATIC-plan lens (where did this plan come from / how was it
     ;; lowered) versus Xray's RUNTIME diagnostics. It consumes the pure
     ;; plan compiler's `:explain` map and pulls NO Xray symbol — the
     ;; two never share code (spec/020 §1.2 + §4 Xray-independence).
     ;;
     ;; Gated on a focused variant (the explain map is per-variant) and
     ;; the `:explain` visibility slot. The slot defaults to ON when
     ;; unset (nil) so the inspector entry is reachable out of the box;
     ;; the command palette's `Explain variant` command flips it on +
     ;; scrolls here when the user has toggled it off.
     (when (and variant-id
                (not (false? (get vis explain-panel/panel-key)))) ;; nil/true → show
       [:section {:id explain-panel/anchor-id
                  :style (:rhs-section styles)
                  :data-rf-rhs-section "explain"}
        [:div {:style (:rhs-section-h styles)}
         [:span "Explain"]
         [:span {:style (:rhs-section-sub styles)}
          "provenance + lowering"]]
        [explain-panel/explain-panel]])
     ;; rf2-ba86n.10 — Evidence spine. The Story-owned narrative/evidence
     ;; DISPLAY over the retained epoch tape (spec/020 §3 + spec/021 §2).
     ;; Sits under Explain in the RHS inspector rail: script spans over
     ;; epoch beats, each beat labelled with its evidence strength (direct
     ;; vs attributed) + a compact summary, and per-beat "open in Xray"
     ;; focus links that drive the closed rf2-crtmq focus API. Story owns
     ;; the narrative; the focus links open Xray's detailed diagnostics —
     ;; the spine LINKS via the host-facing `day8.re-frame2-xray.core/focus!`
     ;; entry point, it never embeds Xray panel interiors (spec/020 §1.2).
     ;;
     ;; Gated on a focused variant (the narrative is per-variant) and the
     ;; `:evidence` visibility slot. Defaults to ON when unset (nil) so the
     ;; entry is reachable out of the box; the result-row links in Test mode
     ;; + the command palette flip it on + scroll here.
     (when (and variant-id
                (not (false? (get vis evidence-spine/panel-key)))) ;; nil/true → show
       [:section {:id evidence-spine/anchor-id
                  :style (:rhs-section styles)
                  :data-rf-rhs-section "evidence"}
        [:div {:style (:rhs-section-h styles)}
         [:span "Evidence"]
         [:span {:style (:rhs-section-sub styles)}
          "narrative + Xray focus"]]
        [evidence-spine/evidence-spine-panel]])
     (when (:controls vis)
       [:section {:style (:rhs-section styles)
                  :data-rf-rhs-section "controls"}
        [:div {:style (:rhs-section-h styles)}
         [:span "Controls"]
         [:span {:style (:rhs-section-sub styles)}
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
         [:span {:style (:rhs-section-sub styles)}
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
  for `:full`.

  rf2-j8hklm: `[canvas/canvas]` sits behind a STABLE always-present
  `:div` wrapper regardless of `sized?` — only the wrapper's OWN style
  varies (the real `vp-style` sizing box when sized, `display: contents`
  otherwise so the wrapper drops out of layout and `canvas/canvas`'s own
  `:flex \"1\"` sizes it against the outer flex container exactly as
  when it rendered unwrapped). Previously `sized?` picked between TWO
  DIFFERENT hiccup shapes at this tree position — `[:div ... [canvas/
  canvas]]` vs bare `[canvas/canvas]` — so toggling the viewport across
  the `:full`-vs-sized boundary changed the React element TYPE at this
  slot (`:div` vs the canvas class) and forced React to unmount + remount
  the canvas subtree. `canvas/canvas`'s `component-will-unmount` cleared
  the run-key sentinel, so the fresh mount's `component-did-mount` always
  re-ran `run-variant` — wiping the live variant's interactive app-db even
  though the viewport is deliberately excluded from `run-key` precisely
  so a viewport toggle should NOT re-run it. Keying `[canvas/canvas]` at
  an identical tree position across both branches keeps React's
  reconciler on the SAME component instance, so the toggle only ever
  updates the wrapper's style — never a remount."
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
     [:div (cond-> {:style (or vp-style {:display "contents"})}
             sized? (assoc :data-test "story-canvas-frame-sized"))
      [canvas/canvas]]
     ;; rf2-zgu68 — viewport-px indicator chip. Self-elides for `:full`.
     [canvas/viewport-indicator vp]]))

(defn- main-pane
  "The main content pane — workspace if one is selected, otherwise the
  variant canvas.

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
  `:play-script` sequence + assertions. `:dev` preserves the existing canvas
  / workspace behaviour."
  []
  (let [shell      @state/shell-state-atom
        variant-id (:selected-variant shell)
        ws-id      (:selected-workspace shell)
        story-id   (:selected-story shell)
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
       ;; Calm first-run / empty state (spec/018 §12.5). Not an
       ;; explanatory poster — a quiet centred prompt on the transparent
       ;; canvas that names what is missing (no selection) and the
       ;; command available now (pick from the sidebar). The amber
       ;; diamond echoes the sidebar's story glyph so the eye knows
       ;; where to look.
       [:div {:style       (:empty-canvas styles)
              :data-test   "story-canvas-empty"}
        [:div {:style (:empty-canvas-glyph styles)} "◆"]
        [:div {:style (:empty-canvas-title styles)}
         "No variant selected"]
        [:div {:style (:empty-canvas-hint styles)}
         "Pick a story, variant, or workspace from the sidebar to render it here."]])]))

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
         ;; rf2-96y71s: seed chrome-wide :active-modes from the
         ;; localStorage FALLBACK only. The toolbar no longer reads the
         ;; URL — URL-derived :active-modes hydration is owned solely by
         ;; the url-state engine (hydrate-url-state! below, via
         ;; apply-parsed-to-state). This localStorage seed runs FIRST so
         ;; the URL hydrator can authoritatively override it when the
         ;; URL carries params, and leave it intact on a fresh no-URL
         ;; mount — the URL-over-localStorage precedence (spec/022
         ;; §Share semantics). Idempotent and one-shot.
         (toolbar/hydrate-modes-from-storage!)
         ;; rf2-zll4h: hydrate the chrome-wide viewport + background
         ;; selections from localStorage. Both are idempotent and
         ;; one-shot; they no-op when the slot is already populated
         ;; (e.g. a programmatic test fixture seeded them). Same
         ;; localStorage-first / URL-authoritative discipline as modes.
         (viewport-switcher/hydrate!)
         (backgrounds-switcher/hydrate!)
         (start-hot-reload-poll!)
         (selection-watcher)
         ;; rf2-chi9j3: capture the selection BEFORE `hydrate-url-state!`
         ;; runs — the same value `selection-watcher` just seeded
         ;; `selection-prev` from. When the URL carries a deep-linked
         ;; `?variant=…`, `hydrate-url-state!` swaps `:selected-variant`
         ;; synchronously, which fires the already-installed watcher and
         ;; schedules ITS OWN mount-time auto-run (below, `selection-
         ;; watcher`'s `js/setTimeout`). `mount-time-autorun-vid` uses this
         ;; pre-hydration value to tell the two cases apart so the mount-
         ;; time block never double-schedules a deep-linked variant's
         ;; `:play-script`.
         (let [pre-hydrate-vid (:selected-variant @state/shell-state-atom)]
           ;; rf2-o4u18 / rf2-ovb1en: hydrate the URL-state slots (workspace +
           ;; mode-tab + viewport + background + tag-filter + variant / modes /
           ;; substrate) — `url-state/hydrate-from-url!` is the authoritative,
           ;; VALIDATING owner of all of them and sets the focused selection.
           ;; `share/hydrate-from-url!` then owns ONLY the focused-variant
           ;; cell-overrides slice (declared-key drift filter + share-import
           ;; hint, rf2-9jthx); it reads — never rewrites — the validated
           ;; selection / substrate, so the second pass can't undo the first
           ;; pass's validation. Selection runs through the same shell-state-atom
           ;; swap path the user's click would take, so the selection-watcher's
           ;; preallocate-frame branch fires for deep-linked variants.
           (hydrate-url-state!)
           ;; rf2-o4u18: subscribe to popstate so the back-button restores
           ;; prior URL state, and watch shell-state-atom so user-driven
           ;; selection / viewport / background / tag-filter changes
           ;; pushState the canonical URL. The focused variant's
           ;; cell-override edits ARE pushed (rf2-5fyo3 — the share popover
           ;; that once owned override serialisation was retired by
           ;; rf2-ymnfx, so the live address bar is the only sharing
           ;; surface) and restored on popstate via apply-parsed-to-state
           ;; (rf2-j0hwf — closes the override round-trip on back/forward).
           ;; rf2-cmjly3 finding 8: `apply-parsed-to-state` alone installs
           ;; the RAW parsed overrides — it's pure/registrar-free, so it
           ;; can't run the declared-key stale-override drop-and-report
           ;; filter `share/hydrate-from-url!` runs on mount. Passing it as
           ;; `post-apply-fn` re-runs that SAME filter after every popstate
           ;; (inside the same hydration guard) so Back/Forward gets the
           ;; identical drop/report treatment as mount hydration.
           (url-state/install-popstate-listener!
             state/shell-state-atom
             (url-state-apply-fn)
             share/hydrate-from-url!)
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
             ;; escape hatch + Xray's source-coord chips resolve
             ;; against Story's `:rf.story/project-root`.
             (xray-preset/wire-cross-host!)
             ;; rf2-q9kv5: apply per-story preset on the mount-time
             ;; selection too (the selection-watcher only fires on
             ;; change, so a pre-selected variant would otherwise miss
             ;; the preset).
             (xray-preset/on-variant-selected! vid)
             ;; rf2-8i2a9 / rf2-chi9j3: mount-time auto-run for an
             ;; already-selected variant (deep-link / persisted
             ;; selection) — but ONLY when `hydrate-url-state!` did NOT
             ;; just change the selection during THIS mount. When it did,
             ;; `selection-watcher`'s change-handler already scheduled its
             ;; own auto-run for `vid` (above); scheduling a second one
             ;; here would run the deep-linked variant's `:play-script`
             ;; TWICE (every dispatch doubled). Yields a tick so the
             ;; canvas has committed before the script's first
             ;; DOM-sensitive step runs.
             (when-let [run-vid (mount-time-autorun-vid pre-hydrate-vid vid)]
               (js/setTimeout
                 (fn [] (play-runner-events/auto-run! run-vid))
                 0))))))
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
          ;; rf2-ba86n.16 — human share / export / copy egress dialog.
          ;; Opens off the toolbar SHARE chip. Surfaces share URL · copy
          ;; EDN · screenshot · static build, each labelled with its
          ;; reproducibility status (full / partial / view-only). Human
          ;; egress is NOT privacy-gated (the reframe — local dev has the
          ;; secrets); the contract is reproducibility honesty.
          [share/share-export-dialog]
          ;; rf2-one3t: save-current-canvas-state-as-variant dialog. Lives
          ;; alongside the recorder's save dialog — both float above the
          ;; three-pane layout via fixed positioning; both surface the
          ;; generated EDN snippet for review-then-commit.
          [save-variant-ui/save-dialog]
          ;; rf2-ba86n.13: generated-failure promotion dialog. Opens from the
          ;; Test pane's 'promote run' button + the sidebar's 'Captured
          ;; artifacts' rows. Distinct from save-current-state (different
          ;; entry point, captured-artifact source); floats above the layout
          ;; via the shared review-dialog fixed positioning.
          [promotion/promotion-dialog]
          [command-palette/command-palette-host]]))}))

;; ---- mount / unmount surface ---------------------------------------------

(defonce ^:private shell-singleton
  ;; Per `003-Render-Shell.md` §Shell lifecycle single shell at v1; multiple shells deferred to v2.
  (atom nil))

(defn mount-shell!
  "Mount the Story shell at `dom-node`. Returns a shell handle (a map
  carrying `{:root <react-root> :node <dom-node>}`).

  v1: one shell at a time. Calling `mount-shell!` while a shell is
  already mounted unmounts the previous one first.

  Per `005-SOTA-Features.md` §Production elision under `:advanced` production builds with `enabled?` false short-
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

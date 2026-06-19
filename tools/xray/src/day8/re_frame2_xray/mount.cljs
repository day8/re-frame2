(ns day8.re-frame2-xray.mount
  "DOM-side mount machinery for the Xray shell. Per rf2-tijr Option C +
  spec/007-UX-IA.md §The default landing view:

  - The preload auto-opens the shell into the app-provided
    `[data-rf-xray-host]` layout host once the substrate adapter is
    ready. This is true inline layout: Xray participates in normal
    flex/grid flow on the right, and the app stays visible/clickable to
    the left.
  - Subsequent presses toggle the container's `display` between
    `block` and `none` (a CSS-only show/hide; per the spec a re-mount
    would discard internal state and miss the <80ms toggle target).
  - All state is `defonce`-guarded so shadow-cljs `:after-load` does
    not re-attach listeners or re-mount the shell.

  ## Why we use the substrate adapter's render fn

  Per rf2-tijr Xray is substrate-agnostic: the host may be running
  Reagent, UIx, or Helix. The substrate adapter's `:render` slot is
  the canonical mount path (`rf/render render-tree mount-point opts`);
  every adapter's impl produces an unmount fn on first call. Xray
  calls that path so the shell mounts via the adapter the host
  installed via `(rf/init! ...)`.

  ## Production posture

  The preload's `interop/debug-enabled?` gate (see preload.cljs) means
  mount never reaches production builds. This namespace therefore
  contains no production-elision logic of its own — the call-site
  gate is sufficient.

  ## Lazy `:rf/xray` frame registration (rf2-in6l2)

  The Xray shell wraps every panel in `[rf/frame-provider-existing {:frame
  :rf/xray} …]` and every panel is `reg-view`-wrapped so subscribes
  resolve through the React-context tier to the named frame. For that
  routing to land in the *registered* `:rf/xray` frame (not chain-
  resolve to `:rf/default`), the frame must exist — and the preload
  can't register it because the preload runs before the host's
  `rf/init!` has installed a substrate adapter, and `reg-frame` writes
  trace events that need a running adapter to be reactive (per rf2-e9s81
  the preload-time `reg-frame :rf/xray` path is the one that produced
  the iw5ym regression). The first Ctrl+Shift+C keypress fires AFTER
  `rf/init!`, so `open!` is the canonical place to call `reg-frame` —
  the call is idempotent (surgical update on re-register) so subsequent
  toggles are no-ops on this axis."
  (:require [re-frame.core :as rf]
            [re-frame.substrate.adapter :as substrate-adapter]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.panels.image-view-reads :as image-reads]
            [day8.re-frame2-xray.filters.persistence :as filters-persistence]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.self-noise :as self-noise]
            [day8.re-frame2-xray.theme.global-styles :as global-styles]
            [day8.re-frame2-xray.theme.tokens :as tokens]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.views.resizable-table :as resizable-table]))

;; ---- mount state ---------------------------------------------------------

(defonce ^:private mount-state
  ;; Singleton — only one Xray shell mounted per process. The map shape:
  ;;   {:node      <DOM element>      ; the freshly-created <div>
  ;;    :unmount   (fn [] ...)         ; substrate adapter unmount fn
  ;;    :visible?  <boolean>
  ;;    :mode      <:inline|:overlay>}
  ;; Per rf2-zkfiz Q1-8 the `:mode` enumeration is exactly the two
  ;; values written by `open!` (`:inline`) and `open-overlay!`
  ;; (`:overlay`). The popout shell lives in `popout-state` below
  ;; with `:mode :popout` — the two singletons do NOT share a `:mode`
  ;; vocabulary. Earlier shapes carried a `:docked` variant (the
  ;; body-padding dock surface); that mode was removed under
  ;; rf2-sbfb7 together with the `dock!` / `undock!` API.
  ;; Nil before first mount; never re-allocated across reloads thanks
  ;; to defonce.
  (atom nil))

(defonce ^:private popout-state
  ;; Optional second-window mount. Shape mirrors mount-state plus
  ;; `:window`, `:ok?`, `:overlay-node`, and `:watchdog-id`. The
  ;; opener runtime remains the source of truth. Per rf2-zkfiz Q1-8
  ;; the `:mode` slot is always `:popout` — the popout never
  ;; participates in the `mount-state` `:inline`/`:overlay`
  ;; enumeration above. Per rf2-h3ekl the `:overlay-node` slot holds
  ;; the opener-gone overlay element (sibling to the shell root) and
  ;; `:watchdog-id` holds the setInterval token that polls
  ;; `window.opener.closed`.
  (atom nil))

(defonce ^:private diagnostic-state
  (atom {:ok? true :reason nil}))

(defonce ^:private auto-open-state
  (atom {:started? false :attempts 0}))

(declare ensure-xray-frame!)
;; rf2-czcg5 — `install-fx!` (below) registers `:rf.xray.fx/popout-shell`
;; whose handler calls `popout!`, which is defined later in this ns.
(declare popout!)

(defn mounted?
  "True when the Xray shell has been mounted at least once. The shell
  may currently be hidden — see `visible?`."
  []
  (some? @mount-state))

(defn visible?
  "True when the shell exists *and* its container is currently
  visible (display != none)."
  []
  (boolean (:visible? @mount-state)))

(defn status
  "Return inspectable Xray mount/API status. Diagnostics are non-
  blocking; missing host failures land here and in `console.error`."
  []
  {:mounted?      (mounted?)
   :visible?      (visible?)
   :mode          (:mode @mount-state)
   :diagnostic    @diagnostic-state
   :host-selector (config/get-layout-host-selector)
   :auto-open?    (config/auto-open-enabled?)})

;; ---- DOM helpers ---------------------------------------------------------

(defn- remove-stale-root!
  "Defensive cleanup before a fresh mount allocates `#rf-xray-root`.
  The singleton `mount-state` precludes coexistence today (both
  `open!` and `open-overlay!` short-circuit when the singleton is
  populated), but the DOM id remains a single shared name across
  `:inline` and `:overlay` modes. Per rf2-zkfiz Q1-4 the create fns
  evict any orphaned node first so a stale `#rf-xray-root` left
  behind by a partially-failed teardown cannot survive into a fresh
  mount cycle. No-op when the document has no such node."
  []
  (when-let [stale (and (exists? js/document)
                        (.-getElementById js/document)
                        (.getElementById js/document "rf-xray-root"))]
    (when-let [parent (.-parentNode stale)]
      (.removeChild parent stale))))

(defn- create-overlay-mount-node! []
  (remove-stale-root!)
  (let [node (.createElement js/document "div")]
    (set! (.-id node) "rf-xray-root")
    (.setAttribute node "data-rf-xray-mode" "overlay")
    ;; The shell uses position: fixed for its own outer element, so the
    ;; root <div> only needs to be a no-layout-impact host. Leave its
    ;; default block-level styling intact; the shell handles its own
    ;; geometry.
    (.appendChild (.-body js/document) node)
    node))

(defn- layout-host []
  (when (and (exists? js/document) (.-querySelector js/document))
    (.querySelector js/document (config/get-layout-host-selector))))

(defn- missing-host-diagnostic []
  (let [selector (config/get-layout-host-selector)]
    {:ok?      false
     :reason   :missing-layout-host
     :selector selector
     :message  (str "Xray default launch requires an app-provided true-inline "
                    "layout host matching " selector ". Add a right-side host "
                    "to your normal app layout, or configure "
                    ":rf.xray/layout-host-selector before the preload opens.")
     :snippet  config/default-layout-host-snippet}))

(defn- report-diagnostic! [diagnostic]
  (reset! diagnostic-state diagnostic)
  (when (and (exists? js/console) (.-error js/console))
    (.error js/console
            (:message diagnostic)
            (clj->js {:selector (:selector diagnostic)
                      :snippet  (:snippet diagnostic)})))
  diagnostic)

(defn- clear-diagnostic! []
  (reset! diagnostic-state {:ok? true :reason nil}))

(defn- note-auto-open-disabled! []
  (reset! diagnostic-state {:ok? true :reason :auto-open-disabled})
  nil)

;; ---- layout-host display snapshot (rf2-4itwg) ----------------------------
;;
;; Toggle-off must collapse the layout host's flex/grid slot too, not just
;; hide the mount root. The previous `display:none` on `#rf-xray-root`
;; left the surrounding `[data-rf-xray-host]` aside still occupying its
;; `flex-basis` slot (and rendering its `border-left` chrome), so the user
;; perceived a sliver of Xray chrome residue on toggle-off. Recording the
;; host's pre-Xray `display` value lets us restore it on toggle-on without
;; guessing what the host's CSS intended.
(defonce ^:private host-display-snapshot
  ;; `{:host <element> :original-inline-display <string>}` once Xray
  ;; has captured the host's inline `display` (empty string when no
  ;; inline `display` was set — restoration writes "" so the host
  ;; stylesheet takes over again). Nil before first capture.
  (atom nil))

(defn- snapshot-host-display! [host]
  (when (and (some? host)
             (not= host (:host @host-display-snapshot)))
    (reset! host-display-snapshot
            {:host                    host
             :original-inline-display (or (some-> host .-style .-display) "")})))

(defn- restore-host-display! []
  (when-let [{:keys [host original-inline-display]} @host-display-snapshot]
    (when (some? host)
      (set! (-> host .-style .-display) original-inline-display))))

(defn- collapse-host! []
  (when-let [{:keys [host]} @host-display-snapshot]
    (when (some? host)
      (set! (-> host .-style .-display) "none"))))

(defn- create-inline-mount-node! []
  (if-let [host (layout-host)]
    (do
      (remove-stale-root!)
      (snapshot-host-display! host)
      (let [node (.createElement js/document "div")]
        (set! (.-id node) "rf-xray-root")
        (.setAttribute node "data-rf-xray-mode" "inline")
        (set! (-> node .-style .-display) "block")
        (set! (-> node .-style .-height) "100%")
        (set! (-> node .-style .-minHeight) "100vh")
        (.appendChild host node)
        (clear-diagnostic!)
        node))
    (do
      (report-diagnostic! (missing-host-diagnostic))
      nil)))

(defn- shell-node [node]
  (when (and (some? node) (.-querySelector node))
    (.querySelector node "[data-testid=\"rf-xray-shell\"]")))

(defn- set-visible! [node visible?]
  (when (some? node)
    (set! (-> node .-style .-display)
          (if visible? "block" "none"))
    (if visible?
      (restore-host-display!)
      (collapse-host!))))

(defn- set-mode-attrs!
  "Write the canonical `data-rf-xray-mode` attribute on both the
  mount root and the shell node so external testbeds + DOM inspectors
  read a single axis (per rf2-zkfiz Q1-9 — the previous double-write
  of `data-mode` + `data-rf-xray-mode` left two axes drifting in
  parallel). The spec-published attribute is `data-rf-xray-mode`
  (per tools/xray/spec/011-Launch-Modes.md); `data-mode` was the
  shell's internal echo and is gone."
  [node mode]
  (when (some? node)
    (let [mode-name (name mode)]
      (.setAttribute node "data-rf-xray-mode" mode-name)
      (when-let [shell (shell-node node)]
        (.setAttribute shell "data-rf-xray-mode" mode-name)))))

(defn- mount-shell-into! [node mode]
  (ensure-xray-frame!)
  ;; rf2-tqlmq — wrap `shell-view` ITSELF in the shell's frame-provider at
  ;; the mount so the whole shell (not just its panels) renders in the
  ;; Xray frame. `shell-view` is a `reg-view`, so its `:rf.view/rendered`
  ;; trace carries the resolved `current-frame-id`; rendered BARE (no
  ;; enclosing provider — its own provider sits INSIDE its body, around
  ;; the panels) `current-frame-id` fell through to `:rf/default`, so the
  ;; shell-view's own
  ;; render trace leaked into the inspected app frame's epoch `:renders`.
  ;; With the provider moved out one level, `shell-view`'s render resolves
  ;; to `default-frame-id` (the trace-disabled `:rf/xray` frame registered
  ;; by `ensure-xray-frame!` just above) and the existing
  ;; `:rf.trace/frame-no-emit?` gate (trace.cljc/`tagged-frame-trace-
  ;; disabled?`, which keys off the render emit's `:frame` tag) suppresses
  ;; it. Threads the shell's actual frame-id (NOT a `:rf/xray` literal),
  ;; matching the frame `ensure-xray-frame!` registered.
  (let [unmount (substrate-adapter/render
                  [rf/frame-provider-existing {:frame shell/default-frame-id}
                   [shell/shell-view {:mode mode}]]
                  node nil)]
    (set-mode-attrs! node mode)
    (reset! mount-state
            {:node     node
             :unmount  unmount
             :visible? true
             :mode     mode})
    @mount-state))

;; ---- first-mount hook table (rf2-y1saa) ---------------------------------
;;
;; `ensure-xray-frame!` accreted eight side-effects across eight beads
;; (rf2-in6l2, rf2-boyc2, rf2-ak4ms, rf2-ikuwt, rf2-o5f5f.1, rf2-9poxq, ...),
;; each chosen ad-hoc by the bead that added it (some dispatch-sync, some
;; direct fn call, some conditional on a config flag). The lazy-
;; registration pattern is correct — the substrate adapter isn't ready at
;; preload time so each seeding step has to wait until the first
;; Ctrl+Shift+C keypress — but the implementation density made adding a
;; Nth side-effect a "modify ensure-xray-frame! the Nth time" task,
;; which kept the function the hot zone for every first-mount tweak.
;;
;; The hook-table refactor introduces an internal `register-first-mount-
;; hook!` registrar (sentinel-guarded by `:id` so each hook is idempotent
;; on re-registration via shadow-cljs `:after-load`). `ensure-xray-
;; frame!` becomes a thin walker: register the frame, then invoke each
;; hook in insertion order.
;;
;; Today the hooks themselves are registered from this namespace's
;; bottom (one-line `register-first-mount-hook!` per subsystem) so the
;; refactor lands as a pure structural change without changing the
;; require graph or touching the registering namespaces. The next
;; iteration moves each registration into its owning ns (e.g.
;; `filters/install!` would call `(register-first-mount-hook! ::filters
;; hydrate!)`), at which point the "modify mount.cljs Nth time" coupling
;; drops to "add a hook from sub-ns". The mechanism here is the prereq.
;;
;; Insertion order is load-bearing: the frame-seed hook MUST run before
;; the hydrate hooks (the dispatch targets live on `:rf/xray`'s app-
;; db), and the mode-hydrate hook MUST run before the auto-open watcher
;; (the watcher's subscribe target depends on the mode-aware ribbon
;; sub). The hook table preserves vector insertion order so registration
;; order at this namespace's bottom is the runtime invocation order.

(defonce ^:private first-mount-hooks
  ;; Ordered vector of `{:id <kw> :handler <0-ary fn>}` entries. The
  ;; vector shape is load-bearing: `ensure-xray-frame!` walks the
  ;; hooks in insertion order so subsystems whose seeding depends on
  ;; a prior hook (e.g. the auto-open watcher depends on the mode
  ;; slot being present) register after the slot-seeding hook.
  ;; Sentinel-guarded by `:id` so `register-first-mount-hook!` with
  ;; an already-registered id replaces in place rather than appending
  ;; a duplicate (the shadow-cljs `:after-load` cycle re-runs the
  ;; registrations).
  (atom []))

(defn- register-first-mount-hook!
  "Register a 1-ary fn `(fn [frame-id] …)` to run on first
  `ensure-xray-frame!` after the shell's frame is registered. Hooks
  run in insertion order and receive the instance `frame-id` so their
  seed/hydrate dispatches land on the right app-db (per rf2-lnluk — the
  production singleton passes `shell/default-frame-id`). An already-
  registered `id` replaces in place (idempotent on `:after-load`).
  Internal — not part of the public API."
  [id handler]
  (swap! first-mount-hooks
         (fn [hooks]
           (let [idx (some (fn [[i h]] (when (= id (:id h)) i))
                           (map-indexed vector hooks))]
             (if idx
               (assoc hooks idx {:id id :handler handler})
               (conj hooks {:id id :handler handler})))))
  nil)

;; ---- public API ----------------------------------------------------------

(defn ensure-xray-frame!
  "Register the shell frame if not already registered, then run each
  registered first-mount hook in insertion order. Idempotent via
  `reg-frame`'s surgical-update-on-re-register semantics (per Spec
  002 §reg-frame) — first call creates the frame and seeds, subsequent
  calls are surgical no-ops on the frame side and (per each hook's own
  idempotency contract) no-ops on the hook side.

  EP-0023 §Xray Beside The Target (rf2-32siq3.36): the production singleton is
  SEATED in its OWN image-loaded frame via `image_view_reads/seat-xray-frame!`
  (`rf/make-frame {:id :rf/xray :images [(xray-image)]}`) — true runtime
  self-seating in genuine registration isolation, NOT the legacy shared
  registrar. Two blockers gated this flip and both are resolved: the
  test-namespace assembly collision (the `day8.re-frame2-xray.**` glob sweeping
  in Xray's own `*-cljs-test` namespaces → `:rf.error/image-duplicate-id`) is
  fixed by the EP-0023 `:exclude-ns` selector on `xray-image`; and the
  host-registry read regression under image-loaded seating — a `:rf.xray/*`
  sub's bare `(rf/registrations …)` / `(rf/handler-meta …)` resolving through
  Xray's OWN image generation instead of the inspected host's process-global
  registrar (the `routes-epochs` nightly xray-feature-gate caught it: empty
  route table / `currentId:null`) — is fixed by reading the host registry
  through `day8.re-frame2-xray.host-registry` (the realm-targeted,
  generation-bypassing form). Both the node-test suite and the
  `routes-epochs` feature-gate are green with the flip.

  ## `frame-id` arg (rf2-lnluk)

  Defaults to `shell/default-frame-id` (`:rf/xray`) — the production
  singleton path passes nothing and behaviour is unchanged. A testbed
  mounting a second shell against a distinct frame-id calls
  `(ensure-xray-frame! :other-frame)` so the seed/hydrate hooks land on
  that frame's app-db. Handlers are NOT re-registered per frame (the
  registry is process-global) — only the per-frame app-db seed runs.

  ## Why here, not at preload time

  Per rf2-in6l2 the registration was attempted at preload time but
  reverted (rf2-e9s81): the preload runs before the host's `rf/init!`
  has installed a substrate adapter, and the `:on-create` listener
  dispatch needs a running adapter to be reactive. The first
  Ctrl+Shift+C keypress fires from the user well after `rf/init!`, so
  `open!` is the canonical lazy-registration point.

  ## First-mount hook table (rf2-y1saa)

  The seeding work that used to live inline here is now driven by the
  `first-mount-hooks` registrar — each subsystem's seed/hydrate step
  is registered as a `{:id :handler}` entry, and `ensure-xray-frame!`
  walks the table in insertion order. See the §first-mount hook table
  block above for the registrar's contract and the registrations at
  this namespace's bottom for the concrete hooks shipped today.

  ## App-db seeding semantics (preserved from the pre-rf2-y1saa shape)

  Two slots seed on first open so the panels render against history
  the user has already produced before opening Xray:

  - `:trace-buffer` — seeded from the framework's per-frame trace
    rings + Xray's frameless secondary ring (per rf2-43koh). The
    framework's rings retain pre-mount cascades cascade-keyed; the
    secondary ring captures frameless emits the per-frame rings skip
    (per the B3 ruling, rf2-g1b2m). The seed lifts both surfaces into
    the reactive slot at first Ctrl+Shift+C via
    `trace-collector/refresh-trace-rings!`; subsequent
    `trace-collector/collect-trace!` calls request a coalesced
    `:rf.xray/sync-trace-buffer` (rf2-wq6gx) so the sub fires on every
    push without one dispatch per trace event.

  - `:epoch-history` + `:target-frame` (rf2-1barg + rf2-boyc2) —
    seeded together via `:rf.xray/set-target-frame` so the slot is
    keyed on the frame the user will be observing on first paint.
    Pre-rf2-boyc2 the seed was hardcoded to `:rf/default` — but
    `compose-focus` derives the panel-observed frame from the head
    focusable cascade in the trace buffer, so an app whose pre-mount
    events ran on `:cart-frame` rendered the App-DB panel against
    `:cart-frame` (the observed frame) while `:epoch-history` carried
    the empty `:rf/default` ring. The composite's `:history-empty?`
    resolved true → the panel rendered the boot empty-state
    'app-db for :cart-frame is at the boot value. No diffs yet.'
    EVEN WITH cascades from `:cart-frame` already in the buffer
    (the Mike report). Frame-switch round-trip resolved it because
    `set-frame-reducer` aligns the two axes; the first-mount path
    had to do the same. The seed-frame is the head focusable
    cascade's `:frame` (via `spine/focusable-head-frame-id` over the
    same cascade projection panels read off) — the operator-present
    discovery tier that UNIQUELY resolves the head app cascade's frame
    (EP-0002 rf2-bd4div: unique resolution, NOT `:rf/default` synthesis).
    When no focusable cascade exists (cold start; only the `:ungrouped`
    bucket present) the seed is `defaults/default-target-frame` =
    **nil = UNSELECTED**: the target stays unselected (the frame picker
    prompts a choice) rather than defaulting to `:rf/default`. The
    `:rf.xray/set-target-frame` event writes `:target-frame` and
    re-seeds `:epoch-history` from `(rf/epoch-history seed-frame)`
    in lockstep — symmetric with the picker path and the public
    `core/set-target-frame!` API."
  ([] (ensure-xray-frame! shell/default-frame-id))
  ([frame-id]
   ;; `:rf.trace/frame-no-emit? true` marks the shell frame a tool /
   ;; inspector frame: the framework suppresses all trace emission
   ;; tagged with this frame so Xray's own UI reactivity (`:rf.sub/run`
   ;; + `:rf.view/render` on every panel render) does NOT flood the
   ;; shared trace ring it inspects (rf2-2qaqh). Without this, Xray's
   ;; self-instrumentation evicted every application event from the
   ;; process-global ring buffer — any other consumer reading the raw
   ;; buffer (re-frame2-pair, Story) saw only Xray noise. The flag is
   ;; the frame-scoped sibling of the handler-scoped `:rf.trace/no-
   ;; emit?`; the framework's `reg-frame` honours it on every (re-)
   ;; registration so the gate survives hot-reload.
   ;;
   ;; EP-0023 §Xray Beside The Target (rf2-32siq3.36) — SEAT this singleton in
   ;; its OWN image-loaded frame via `image-reads/seat-xray-frame!` (the
   ;; `rf/make-frame {:id :rf/xray :images [(xray-image)]}` path), replacing the
   ;; legacy realm seating (`reg-frame {:rf.trace/frame-no-emit? true}`). The
   ;; seated frame resolves ONLY Xray's `:rf.xray/*` registrations (plus the
   ;; framework standards), in genuine isolation from the inspected target.
   ;; `seat-xray-frame!` re-asserts the trace-no-emit gate directly through
   ;; `re-frame.trace/set-frame-no-emit!` (the same seam `reg-frame` routed it
   ;; through), and is idempotent on re-seat (`xray-frame-seated?` skips the
   ;; duplicate-`:id` `make-frame`). Host-registry reads inside Xray's subs go
   ;; through `host-registry` (generation-bypassing) so the inspector still sees
   ;; the inspected app's registrar, not its own image's — see that ns.
   (image-reads/seat-xray-frame! frame-id)
   (doseq [{:keys [handler]} @first-mount-hooks]
     (handler frame-id))))

;; ---- first-mount hook registrations -------------------------------------
;;
;; Each registration is a one-line bind from a hook id to a 0-ary fn.
;; Insertion order is load-bearing: hooks that depend on a prior hook
;; (e.g. `::auto-open-watcher` depends on the mode slot being present)
;; appear after their dependencies. The id keywords are mount-internal
;; (`::seed-trace-and-target-frame`, `::hydrate-filters`, ...) — not
;; part of the public API.

(register-first-mount-hook!
  ::seed-trace-and-target-frame
  ;; Seed the frame's app-db with whatever the framework's per-frame
  ;; trace rings + Xray's frameless secondary ring + the framework's
  ;; epoch ring buffer have accumulated so far. The host may have
  ;; driven dispatches before the user opened Xray (rf2-43koh consumer
  ;; substrate; rf2-boyc2 :epoch-history + :target-frame).
  ;;
  ;; The snapshot comes from `trace-collector/refresh-trace-rings!` —
  ;; the same path the production microtask coalescer uses. After the
  ;; snapshot lands in `:trace-buffer`, project the buffer through the
  ;; same pipeline the `:rf.xray/cascades` sub uses (projection +
  ;; Xray-internal hard-filter) to derive the seed-frame. Without the
  ;; internal filter a tool-frame cascade could be chosen as the head,
  ;; which the user never sees in the L2 list.
  (fn [frame-id]
    ;; `refresh-trace-rings!` is async via dispatch in production but
    ;; safe to call here even though the shell frame was just
    ;; registered — the dispatch lands in the queue; we follow up with
    ;; a sync dispatch through `:rf.xray/sync-trace-buffer` carrying the
    ;; same snapshot so the first-mount render reads against pre-mount
    ;; events deterministically.
    (let [buffer     (trace-collector/snapshot-from-rings)
          cascades   (self-noise/filtered-cascades buffer)
          seed-frame (or (spine/focusable-head-frame-id cascades)
                         defaults/default-target-frame)]
      (rf/with-frame frame-id
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer buffer])
        ;; rf2-boyc2 — seed via `:rf.xray/set-target-frame` so
        ;; `:target-frame` + `:epoch-history` move in lockstep keyed
        ;; on the frame the user will be observing on first paint
        ;; (the head focusable cascade's frame). Mirrors the picker-
        ;; driven `set-frame-reducer` path and `core/set-target-
        ;; frame!`.
        (rf/dispatch-sync [:rf.xray/set-target-frame seed-frame])))))

(register-first-mount-hook!
  ::reset-transient-filters
  ;; Reset the TRANSIENT exploration filters to unfiltered on every page
  ;; load (rf2-swclw). Three suppressing surfaces — the IN/OUT pills
  ;; (rf2-ak4ms), the muted-event-ids set (rf2-ikuwt), and the frame pin
  ;; (rf2-iwwou) — are session-scoped: a fresh load must NOT silently
  ;; carry a stale filter from a past session (the trap that hid events
  ;; and made the inspector look broken — rf2-jvghz). An inspector's
  ;; prime directive is to show the truth, so the first paint starts
  ;; fully unfiltered.
  ;;
  ;; Mechanism: we do NOT hydrate these slots, so app-db starts at its
  ;; registry default (empty pills / empty mute set / unpinned frame).
  ;; We additionally CLEAR each slot's stale localStorage value so the
  ;; storage matches what the user sees and a phantom value can never
  ;; resurface — if we only ignored-on-read, the next mute / pin write
  ;; would overwrite a slot that still held last session's ghost until
  ;; then. Clearing keeps storage honest from the first frame.
  ;;
  ;; DURABLE view prefs (Dynamic/Static mode, density, panel layout)
  ;; still hydrate via their own hooks below — only transient filters
  ;; reset. The #1962 'N events hidden by filters' indicator stays as
  ;; the in-session safety net once the user reaches for a filter.
  (fn [_frame-id]
    (filters-persistence/clear!)
    (spine-filters/clear-raw!)
    (frame-switcher/clear!)))

(register-first-mount-hook!
  ::hydrate-column-widths
  ;; Hydrate the per-table column-widths slot from localStorage
  ;; (rf2-xzg1y). Durable preference (NOT a transient filter) — the
  ;; user's column reading-shape choices persist across reloads. The
  ;; `resizable-table/hydrate!` fn guards on `(frame/frame :rf/xray)`
  ;; being registered, so this hook is the canonical landing site
  ;; (the orchestrator-time call from `registry/register-xray-
  ;; handlers!` happens BEFORE the frame is mounted and short-
  ;; circuits cleanly). Re-entrant — same source → same slot.
  (fn [frame-id]
    (resizable-table/hydrate! frame-id)))

(register-first-mount-hook!
  ::hydrate-static-mode
  ;; Hydrate the Dynamic ↔ Static mode slot (rf2-o5f5f.1). Same
  ;; rationale as the filter hydrate above — the persisted mode lives
  ;; in localStorage under `xray.mode` and the frame must exist
  ;; before the dispatch can land. `:rf.xray/set-mode` normalises
  ;; unknown values back to `:dynamic` so an absent or malformed slot
  ;; is harmless. The dispatch carries the persist-mode fx which
  ;; would re-write the slot; that's intentional — the round-trip
  ;; canonicalises the stored value (e.g. an old "explorer" pre-
  ;; rename value would land back as "runtime" without manual
  ;; intervention).
  (fn [frame-id]
    (rf/with-frame frame-id
      (rf/dispatch-sync [:rf.xray/set-mode (static-persistence/load)]))))

(register-first-mount-hook!
  ::auto-open-watcher
  ;; Auto-open-on-error watcher (rf2-9poxq) — install lazily here so
  ;; the persisted-true case picks up as soon as the user opens Xray.
  ;; The watcher subscribes to `:rf.xray/issues-ribbon` (which lives
  ;; on `:rf/xray`'s app-db), so it CANNOT install until the frame
  ;; exists. Install is idempotent + guards against the toggle being
  ;; off — when the user has never enabled the setting (the default),
  ;; this is a one-time no-op cost on first mount.
  (fn [_frame-id]
    (when (config/get-setting :general :auto-open-on-error?)
      (settings-effects/install-auto-open-watcher!))))

(defn open!
  "Mount + show the default Xray shell in the app-provided true-inline
  layout host. On first call: register the `:rf/xray` frame, find the
  configured host (`[data-rf-xray-host]` by default), create
  `#rf-xray-root` inside it, render the shell via the installed
  substrate adapter, mark visible. On subsequent calls (when already
  mounted): make the container visible.

  Per rf2-in6l2 the `:rf/xray` registration is lazy here (post-
  `rf/init!`) rather than at preload time; see `ensure-xray-frame!`
  for the rationale.

  If the substrate adapter is absent, returns nil so preload retry can
  wait. If the layout host is missing, returns an inspectable
  diagnostic map and logs `console.error` without blocking startup."
  []
  (if-let [state @mount-state]
    (do (set-visible! (:node state) true)
        (swap! mount-state assoc :visible? true)
        @mount-state)
    (when (substrate-adapter/current-adapter)
      (if-let [node (create-inline-mount-node!)]
        (mount-shell-into! node :inline)
        @diagnostic-state))))

(defn open-overlay!
  "Debug/fallback launch path: mount Xray as the old fixed overlay
  under `document.body`. Not the default developer experience."
  []
  (if-let [state @mount-state]
    (do (set-visible! (:node state) true)
        (set-mode-attrs! (:node state) :overlay)
        (swap! mount-state assoc :visible? true :mode :overlay)
        @mount-state)
    (when (substrate-adapter/current-adapter)
      (mount-shell-into! (create-overlay-mount-node!) :overlay))))

(defn close!
  "Hide the shell — make the container display:none. The DOM tree and
  the substrate render tree stay in place so re-opening is a CSS-only
  toggle (<80ms first paint per spec/007-UX-IA.md §The default landing
  view)."
  []
  (when-let [state @mount-state]
    (set-visible! (:node state) false)
    (swap! mount-state assoc :visible? false))
  nil)

(defn toggle!
  "Toggle the Xray shell's visibility. First call mounts + shows
  (per `open!`); subsequent calls flip visibility."
  []
  (if (visible?) (close!) (open!)))

;; ---- close-shell effect (rf2-fq491) -------------------------------------
;;
;; The shell `✕` button (and any other in-app close affordance) dispatches
;; `:rf.xray/close-shell`, an app-db event. That event sets the reactive
;; `:close-requested?` flag, but the actual DOM hide lives here in `close!`
;; (the same path the Ctrl+Shift+C keybinding drives via `toggle!`). This
;; fx is the bridge: the event returns `[:rf.xray.fx/hide-shell]` and the
;; effect calls `close!`, so the flag and the visible hide stay in lock-
;; step. Co-locating the effect with the DOM action (mirrors
;; `static-persistence/install-fx!`) keeps `registry.cljs` free of any
;; direct DOM-toggle call.
(defn install-fx!
  "Idempotently register the DOM-side chrome effects:

  - `:rf.xray.fx/hide-shell` — hide the in-app shell (`close!`); fired
    by the `✕` close button via `:rf.xray/close-shell`.
  - `:rf.xray.fx/popout-shell` (rf2-czcg5) — open the second-window
    pop-out (`popout!`); fired by the chrome `⛶` pop-out button via
    `:rf.xray/popout-shell`. The event/fx bridge keeps `shell.cljs`
    free of a direct `mount/popout!` call (which would form the
    mount→shell→…→mount require cycle), mirroring the close-shell
    bridge.

  re-frame's registrar replaces in place, so repeat calls (shadow-cljs
  `:after-load`) are harmless. Handler signature `(fn [ctx args])` per
  the v2 reg-fx contract."
  []
  (rf/reg-fx :rf.xray.fx/hide-shell
    (fn [_ctx _args]
      (close!)))
  (rf/reg-fx :rf.xray.fx/popout-shell
    (fn [_ctx _args]
      (popout!)))
  nil)

(defn- teardown-popout-state!
  "Internal: tear down the popout singleton if present. Invokes the
  substrate unmount, clears the opener-gone watchdog, attempts to
  close the popout window, and clears the singleton. All steps run
  inside swallow-errors guards — this is a last-chance cleanup (test
  fixture, opener-side unload), not a contract-checking call site."
  []
  (when-let [{:keys [window unmount watchdog-id]} @popout-state]
    (when watchdog-id
      (try (js/clearInterval watchdog-id) (catch :default _ nil)))
    (when unmount
      (try (unmount) (catch :default _ nil)))
    (when window
      (try
        (when-not (.-closed window)
          (.close window))
        (catch :default _ nil)))
    (reset! popout-state nil))
  nil)

(defn teardown!
  "Tear the shell down completely — unmount every Xray mount surface,
  remove DOM nodes, clear every mount singleton. Intended for tests;
  production sessions keep the shell across the page's lifetime.

  Two singletons are cleared:

  - `mount-state` — the default in-app shell.
  - `popout-state` — the optional second-window shell (also closed).

  All unmount calls run inside swallow-errors guards so a single
  failed unmount cannot strand the remaining singletons.

  ## Test-fixture obligation: the keybinding listener (rf2-zkfiz Q1-10)

  `teardown!` does NOT detach the global `Ctrl+Shift+C` keydown
  listener that `preload/init!` attaches via `keybinding/attach!`.
  Doing so from here would force a circular `mount → keybinding`
  require (keybinding already requires mount for `toggle!`), and the
  attachment is a preload-time concern, not a mount-time one. Test
  suites that drive `teardown!` then re-run mount tests must call
  `(day8.re-frame2-xray.keybinding/detach!)` themselves to drop the
  listener; without it, a stale handler that fires between runs would
  re-invoke `toggle!` on the next keypress and resurrect the
  singleton.

  Production sessions never call `teardown!` so the listener-leak
  exposure is test-only — the convention is pinned in
  `tools/xray/spec/Conventions.md` §Mount conventions."
  []
  ;; Restore the host's inline `display` before clearing the snapshot —
  ;; teardown is the cleanest exit and must leave the host element in
  ;; the exact state it was in pre-Xray (no lingering `display:none`
  ;; from a prior toggle-off).
  (restore-host-display!)
  (reset! host-display-snapshot nil)
  (when-let [{:keys [node unmount]} @mount-state]
    (when unmount
      (try (unmount) (catch :default _ nil)))
    (when (and node (.-parentNode node))
      (.removeChild (.-parentNode node) node))
    (reset! mount-state nil))
  (teardown-popout-state!)
  (clear-diagnostic!)
  (reset! auto-open-state {:started? false :attempts 0})
  nil)

(defn auto-open-inline!
  "Preload entry: wait briefly for the host app to call `rf/init!`, then
  open Xray in the true-inline host. Missing host emits a single
  actionable diagnostic; no alert and no blocking startup."
  []
  (when (compare-and-set! auto-open-state {:started? false :attempts 0}
                          {:started? true :attempts 0})
    (letfn [(tick! []
              (let [{:keys [attempts]} @auto-open-state]
                (cond
                  (not (config/auto-open-enabled?))
                  (note-auto-open-disabled!)

                  @mount-state nil

                  (substrate-adapter/current-adapter)
                  (open!)

                  (< attempts 120)
                  (do
                    (swap! auto-open-state update :attempts inc)
                    (js/setTimeout tick! 50))

                  :else
                  (report-diagnostic!
                    {:ok? false
                     :reason :no-substrate-adapter
                     :message "Xray preload could not auto-open because no re-frame2 substrate adapter was installed. Call (rf/init! adapter) before app render."
                     :selector (config/get-layout-host-selector)
                     :snippet  config/default-layout-host-snippet}))))]
      (tick!)))
  nil)

(defn- register-popout-unload-cleanup!
  "Per rf2-yudol: when the user closes the popout window externally
  (or the page unloads for any other reason), the opener-side
  `popout-state` singleton must clear so a subsequent `popout!` is
  treated as a fresh first-mount rather than short-circuiting on the
  stale `:window` whose `.closed` is now true.

  We register the listener on the popout window and use both
  `pagehide` and `unload` for cross-browser coverage. The handler is
  idempotent (it inspects the current `popout-state` and ignores
  events fired after a fresh popout has already replaced the
  singleton — guard via identity comparison on the `:window` slot)."
  [win]
  (let [handler (fn popout-unload-handler [_event]
                  ;; Only clear if this very window is still the
                  ;; registered popout — a stale handler that fires
                  ;; AFTER a fresh popout! has replaced the singleton
                  ;; must not nuke the new state.
                  (when (some-> @popout-state :window (identical? win))
                    (teardown-popout-state!)))]
    (when (.-addEventListener win)
      (try (.addEventListener win "pagehide" handler) (catch :default _ nil))
      (try (.addEventListener win "unload"   handler) (catch :default _ nil)))))

;; ---- popout opener-gone overlay (rf2-h3ekl) ------------------------------
;;
;; Per tools/xray/spec/011-Launch-Modes.md §Pop-out §Constraints:
;;
;;   If the user closes the opener window, the pop-out becomes
;;   orphaned. Pop-out detects this via `window.opener.closed` and
;;   shows a clean "opener gone — close this window" overlay.
;;
;; The popout reads / dispatches against the opener's runtime atoms
;; (same JS realm; no postMessage layer). If the opener window is
;; closed (or torn down via cross-document navigation), every read
;; reaches an opener whose atoms are GC-eligible — the popout's panels
;; appear frozen or broken, with no UI signal that the cause is the
;; missing opener. The overlay closes that gap with the spec'd
;; message; the user can then close the now-useless popout window.
;;
;; Implementation: a sibling DOM node to the shell mount root,
;; created at `popout!`-time as a hidden `<div>` with the message
;; markup. A `setInterval` watchdog polls `window.opener.closed`
;; every 500ms; on first observation of "opener gone" the watchdog
;; reveals the overlay (display:flex) and clears itself. Plain DOM
;; manipulation — no React tree dependency, so the overlay survives
;; even if the substrate tree has thrown mid-render under the broken
;; opener. Token-derived colours match the rest of the Xray shell.

;; Per rf2-5kfxe.4 the four colour constants and the sans stack now
;; resolve through `theme/tokens` directly. The earlier "inlined to
;; avoid pulling theme into the install path" rationale was stale —
;; `theme.tokens` has no transitive deps and is the canonical palette.

;; The overlay paints into the popout window's own document via
;; imperative `set! style.background` etc. Post rf2-czcg5 the popout
;; document DOES carry the Xray `<style>` injection (the `:root`
;; `--rf-xray-…` custom properties land via `style-popout-document!`),
;; but these overlay constants deliberately keep reading `dark-palette`
;; literal hex: the overlay is the broken-opener fallback and must
;; remain legible even on the (defensive) path where injection never
;; ran or the substrate tree threw before the vars resolved. The hex
;; still flows through `theme/tokens` as the single source of truth, so
;; the overlay stays in lockstep with the dark palette regardless.

(def ^:private opener-gone-overlay-bg
  (:bg-0 tokens/dark-palette))

(def ^:private opener-gone-overlay-text
  (:text-primary tokens/dark-palette))

(def ^:private opener-gone-overlay-secondary
  (:text-secondary tokens/dark-palette))

(def ^:private opener-gone-overlay-accent
  (:accent tokens/dark-palette))

(def ^:private sans-stack
  tokens/sans-stack)

(defn- install-opener-gone-overlay!
  "Create the opener-gone overlay node inside the popout's document
  body. The node is hidden by default (`display: none`) and revealed
  by the watchdog when `window.opener.closed` is observed true. The
  overlay is full-window, fixed-position, with Xray's visual
  language: the dark surface colour, the violet accent glyph, primary
  text for the headline, secondary text for the affordance hint.

  Implementation is plain DOM (no React / no Reagent) so the overlay
  remains operable even if the substrate render tree has thrown
  mid-render under the broken opener. Returns the created node so
  `popout!` can stash it in `popout-state`."
  [doc]
  (let [overlay (.createElement doc "div")
        inner   (.createElement doc "div")
        glyph   (.createElement doc "div")
        title   (.createElement doc "div")
        hint    (.createElement doc "div")]
    (set! (.-id overlay) "rf-xray-popout-opener-gone-overlay")
    (.setAttribute overlay "data-testid" "rf-xray-popout-opener-gone-overlay")
    (.setAttribute overlay "data-rf-xray-mode" "popout-opener-gone")
    (let [s (.-style overlay)]
      (set! (.-position s)        "fixed")
      (set! (.-top s)             "0")
      (set! (.-left s)            "0")
      (set! (.-right s)           "0")
      (set! (.-bottom s)          "0")
      (set! (.-display s)         "none")
      (set! (.-flexDirection s)   "column")
      (set! (.-alignItems s)      "center")
      (set! (.-justifyContent s)  "center")
      (set! (.-background s)      opener-gone-overlay-bg)
      (set! (.-color s)           opener-gone-overlay-text)
      (set! (.-fontFamily s)      sans-stack)
      (set! (.-textAlign s)       "center")
      (set! (.-padding s)         "32px")
      (set! (.-zIndex s)          "2147483647"))
    (let [s (.-style inner)]
      (set! (.-maxWidth s) "560px"))
    (set! (.-textContent glyph) "◆")
    (let [s (.-style glyph)]
      (set! (.-color s)       opener-gone-overlay-accent)
      (set! (.-fontSize s)    "48px")
      (set! (.-marginBottom s) "16px"))
    (set! (.-textContent title) "Opener gone")
    (let [s (.-style title)]
      (set! (.-fontSize s)    "20px")
      (set! (.-fontWeight s)  "600")
      (set! (.-marginBottom s) "12px"))
    (set! (.-textContent hint)
          (str "The host application window has been closed. "
               "This Xray pop-out is no longer connected to a "
               "running runtime — close this window."))
    (let [s (.-style hint)]
      (set! (.-color s)      opener-gone-overlay-secondary)
      (set! (.-fontSize s)   "14px")
      (set! (.-lineHeight s) "1.5"))
    (.appendChild inner glyph)
    (.appendChild inner title)
    (.appendChild inner hint)
    (.appendChild overlay inner)
    (.appendChild (.-body doc) overlay)
    overlay))

(defn- opener-gone?
  "True when the popout's `window.opener` is unreachable: either the
  opener slot has been nulled out (cross-document navigation that
  blew the reference) or the opener window's `.closed` reads true.
  Defensive against unexpected throws (cross-origin walks in
  pathological deploys) — those classify as 'opener gone' too."
  [win]
  (try
    (let [opener (.-opener win)]
      (or (nil? opener)
          (.-closed opener)))
    (catch :default _
      true)))

(defn- show-opener-gone-overlay!
  "Reveal the overlay node. No-op when nil — guards against races
  between teardown and the watchdog tick."
  [overlay-node]
  (when (some? overlay-node)
    (set! (-> overlay-node .-style .-display) "flex")))

(defn- start-opener-gone-watchdog!
  "Install a polling watchdog on the popout window that reveals the
  opener-gone overlay on first observation that the opener is gone.
  Polls every 500ms — frequent enough that the user sees the message
  within a perceived blink, infrequent enough not to register as a
  busy-loop in the popout's idle profile. Self-clears after firing.
  Returns the interval id so `teardown-popout-state!` can clear it
  on the unload path; if the watchdog has already fired and cleared
  itself the clear-call is a tolerated no-op."
  [win overlay-node]
  (let [id-atom (atom nil)
        tick    (fn []
                  ;; Stop polling if the popout's state was torn down
                  ;; (test teardown, opener-side close) — the singleton
                  ;; guards against the orphan-handler-resurrects-state
                  ;; class of bug.
                  (cond
                    (not (some-> @popout-state :window (identical? win)))
                    (when-let [id @id-atom] (js/clearInterval id))

                    (opener-gone? win)
                    (do
                      (show-opener-gone-overlay! overlay-node)
                      (when-let [id @id-atom] (js/clearInterval id)))

                    :else nil))
        id      (js/setInterval tick 500)]
    (reset! id-atom id)
    id))

;; ---- popout stylesheet hand-off (rf2-czcg5) -----------------------------
;;
;; Per tools/xray/spec/011-Launch-Modes.md §Pop-out §Styling: the
;; pop-out window MUST carry Xray's stylesheet + `:root` custom
;; properties so the shell renders identically to the inline panel.
;;
;; The detached window is a DISTINCT document — its `<head>` does not
;; carry the `<style>` blocks `global-styles/install!` wrote into the
;; opener's `js/document` (fonts, motion seam, React-Flow base, the
;; per-theme `--rf-xray-*` `:root` custom properties, grain). Every
;; inline style + class rule in the shell resolves colours through
;; `var(--rf-xray-…)`; absent those `:root` declarations the shell
;; renders unstyled (the opener-gone overlay dodges this by reading
;; literal `dark-palette` hex — the main shell cannot).
;;
;; `style-popout-document!` injects the full stylesheet set into the
;; pop-out's document (via `global-styles/install-into!`) and mirrors
;; the persisted theme onto the pop-out's `<html>` (so the matching
;; `.rf-xray-theme-*` block — not just the `:root` light default —
;; resolves). Accent rides in the same theme palette blocks (`:accent`
;; is a palette token), so the single inject + theme-class write covers
;; accent/theme together. Density / text-size / panel-width are CSS
;; custom properties the user writes inline on the OPENER's `<html>`
;; via `settings/effects`; the pop-out inherits the injected `:root`
;; defaults (13px etc.) — live per-window overrides of those knobs are
;; out of scope for the second-window mode.

(defn- style-popout-document!
  "Inject Xray's stylesheet set into the pop-out `doc` and stamp the
  persisted theme class on its `<html>` so the shell renders identically
  to the inline panel. Idempotent (the injectors id-probe; the theme
  write is exclusive). No-op-safe when `doc` lacks a DOM surface."
  [doc]
  (when (some? doc)
    (global-styles/install-into! doc)
    ;; Mirror the persisted theme onto the pop-out `<html>` so the
    ;; matching `.rf-xray-theme-*` palette block resolves (the injected
    ;; `:root` block only carries the light default). Same class spelling
    ;; `settings/effects/apply-theme!` writes on the opener.
    (when-let [html (.-documentElement doc)]
      (let [theme (or (config/get-setting :theme nil) :light)
            klass (str settings-effects/theme-class-prefix (name theme))
            cl    (.-classList html)]
        (doseq [c [(str settings-effects/theme-class-prefix "dark")
                   (str settings-effects/theme-class-prefix "light")]]
          (try (.remove cl c) (catch :default _ nil)))
        (try (.add cl klass) (catch :default _ nil)))))
  nil)

(defn popout!
  "Open a same-origin Xray pop-out window and render the shell into it.
  The pop-out shares the opener runtime and Xray frame; no
  serialisation layer is introduced. Returns a state map, or
  `{:ok? false :reason :popup-blocked}` when `window.open` fails.

  Per rf2-yudol the popout window registers a `pagehide`/`unload`
  listener that clears `popout-state` when the user closes the
  window externally, so the next `popout!` is a fresh first-mount
  rather than returning a stale state map whose `:window` is
  already closed.

  Per rf2-h3ekl the popout also installs an opener-gone overlay
  (a hidden sibling DOM node) plus a `setInterval` watchdog that
  polls `window.opener.closed`. When the opener disappears the
  watchdog reveals the spec'd 'opener gone — close this window'
  overlay so the popout is not left in a frozen-or-broken state
  with no UI signal as to the cause. See `install-opener-gone-
  overlay!` + `start-opener-gone-watchdog!` for the contract."
  []
  (if-let [state @popout-state]
    state
    (if-not (substrate-adapter/current-adapter)
      {:ok? false :reason :no-substrate-adapter}
      (let [win (when (exists? js/window)
                  (.open js/window "" "rf-xray-popout"
                         "popup,width=960,height=720"))]
        (if-not win
          {:ok? false :reason :popup-blocked}
          (do
            (ensure-xray-frame!)
            (let [doc  (.-document win)
                  body (.-body doc)
                  node (.createElement doc "div")]
              (set! (.-title doc) "Xray")
              ;; rf2-czcg5 — inject Xray's stylesheet set + the `:root`
              ;; `--rf-xray-*` custom properties into the pop-out's own
              ;; document and mirror the persisted theme, so the shell
              ;; renders fully styled (visually identical to the inline
              ;; panel) rather than unstyled against the bare window.
              (style-popout-document! doc)
              (set! (.-id node) "rf-xray-popout-root")
              (.setAttribute node "data-rf-xray-mode" "popout")
              (.appendChild body node)
              (let [unmount      (substrate-adapter/render
                                    ;; rf2-tqlmq — same mount-wrap as
                                    ;; `mount-shell-into!`: wrap the popout
                                    ;; shell in the shell's frame-provider so
                                    ;; its own `:rf.view/rendered` trace
                                    ;; resolves to the trace-disabled Xray
                                    ;; frame instead of falling through to
                                    ;; `:rf/default` and leaking into the
                                    ;; inspected app frame's epoch `:renders`.
                                    [rf/frame-provider-existing {:frame shell/default-frame-id}
                                     [shell/shell-view {:mode :popout}]]
                                    node nil)
                    overlay-node (install-opener-gone-overlay! doc)
                    watchdog-id  (start-opener-gone-watchdog! win overlay-node)
                    state        {:ok?          true
                                  :window       win
                                  :node         node
                                  :unmount      unmount
                                  :mode         :popout
                                  :overlay-node overlay-node
                                  :watchdog-id  watchdog-id}]
                (reset! popout-state state)
                (register-popout-unload-cleanup! win)
                state))))))))


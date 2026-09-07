(ns day8.re-frame2-xray.mount
  "DOM-side lifecycle for Xray's inline, overlay, and pop-out shells.

  - The preload auto-opens the shell into the app-provided
    `[data-rf-xray-host]` layout host once the substrate adapter is
    ready. This is true inline layout: Xray participates in normal
    flex/grid flow on the right, and the app stays visible/clickable to
    the left.
  - Subsequent toggles change CSS visibility without remounting, so
    panel state and focus survive hide/show cycles.
  - All state is `defonce`-guarded so shadow-cljs `:after-load` does
    not re-attach listeners or re-mount the shell.

  ## Why we use the substrate adapter's render fn

  The substrate adapter's `:render` slot is the canonical mount path
  (`rf/render render-tree mount-point opts`); every adapter's impl
  produces an unmount fn on first call. Xray calls that path so the
  shell mounts via the adapter the host installed via `(rf/init! ...)`.

  That only works on hosts whose `:render` accepts HICCUP render-trees
  — the ratom family (stock Reagent / reagent-slim). Every substrate
  built on the React-hook spine shares an ELEMENT-shaped `render`
  (`re-frame.substrate.spine/make-react-adapter`) that hands the tree
  to React untouched, so Xray's hiccup shell cannot mount there; the
  mount verbs refuse with the `:unsupported-substrate` diagnostic
  instead of letting raw CLJS data reach React children (rf2-qgfo4 —
  fn-as-child console.error + uncaught MapEntry pageerror on every UIx
  template boot). See the substrate gate section below.

  ## Production posture

  Dev-only by build placement (see preload.cljs); nothing in this ns
  gates on `goog.DEBUG`. The preload's `(when interop/debug-enabled? …)`
  block folds away the preload's own call into `open!`, but `init!` and
  the mount verbs carry no gate, and this ns runs seven top-level
  `register-first-mount-hook!` forms at load time. A host on the manual
  path keeps the `:require` and the calls in a namespace only the dev
  entry point loads.

  ## Lazy `:rf/xray` frame registration

  The Xray shell wraps every panel in `[rf/frame-provider {:frame
  :rf/xray} …]` and every panel is `reg-view`-wrapped so subscribes
  resolve through the React-context tier to the named frame. For that
  routing to land in the registered `:rf/xray` frame, the frame must
  exist. The preload runs before the host installs a substrate adapter,
  so first mount performs registration and seeding after adapter
  readiness. The operation is idempotent."
  (:require [re-frame.core :as rf]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.panels.image-view-reads :as image-reads]
            [day8.re-frame2-xray.filters.persistence :as filters-persistence]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.static.machines.persistence
             :as static-machines-persistence]
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
  ;; The in-page singleton uses `:inline` or `:overlay`. Pop-out state is
  ;; tracked separately and always uses `:popout`.
  ;; Nil before first mount; never re-allocated across reloads thanks
  ;; to defonce.
  (atom nil))

(defonce ^:private popout-state
  ;; Optional second-window mount. Shape mirrors mount-state plus
  ;; `:window`, `:ok?`, `:overlay-node`, and `:watchdog-id`. The
  ;; opener runtime remains the source of truth. The `:overlay-node` slot holds
  ;; the opener-gone overlay element (sibling to the shell root) and
  ;; `:watchdog-id` holds the setInterval token that polls
  ;; `window.opener.closed`. `:keydown-dispose` (rf2-61i5) holds the
  ;; zero-arg disposer for this pop-out document's own keydown listener,
  ;; or nil when none was installed.
  (atom nil))

(defonce ^:private popout-keydown-installer
  ;; rf2-61i5 — the seam that gives a pop-out document its keyboard.
  ;;
  ;; DOM key events do not cross realms, so the opener-document listener
  ;; `keybinding/attach!` installs can never see a keypress made in the
  ;; pop-out window. The pop-out therefore needs its own listener, but the
  ;; require already runs `keybinding -> mount` (for `toggle!` / `visible?`),
  ;; so mount cannot reach back for the keyboard map without a cycle.
  ;;
  ;; Instead keybinding INJECTS its installer here at load time. The slot
  ;; holds `(fn [doc] -> disposer-fn-or-nil)`: mount hands over a document
  ;; and gets back a zero-arg disposer it stores and later calls, without
  ;; knowing what a chord is. nil when keybinding was never loaded (or the
  ;; host disabled it), in which case a pop-out simply has no keyboard —
  ;; exactly the behaviour that shipped before this bead.
  (atom nil))

(defn register-popout-keydown-installer!
  "Register the fn that installs a pop-out document's keydown listener
  (rf2-61i5). Called once by `day8.re-frame2-xray.keybinding` at load
  time; see `popout-keydown-installer` for why the injection runs this
  way round. `installer` takes the pop-out `document` and returns either
  a zero-arg disposer that removes exactly the listener it installed, or
  nil when it declined to install one."
  [installer]
  (reset! popout-keydown-installer installer)
  nil)

(defonce ^:private diagnostic-state
  (atom {:ok? true :reason nil}))

(defonce ^:private auto-open-state
  (atom {:started? false :attempts 0}))

(declare ensure-xray-frame!)
;; `install-fx!` registers a handler that calls this later definition.
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
  The singleton `mount-state` precludes coexistence (both
  `open!` and `open-overlay!` short-circuit when the singleton is
  populated), but the DOM id remains a single shared name across
  `:inline` and `:overlay` modes. Create functions evict any orphaned
  node first so a stale `#rf-xray-root` left
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

;; ---- substrate render-shape gate (rf2-qgfo4) ------------------------------
;;
;; Xray's shell is authored in hiccup (reg-view'd Reagent components), so it
;; can only mount through a `:render` slot that accepts hiccup render-trees —
;; the ratom family (stock Reagent / reagent-slim). Every substrate built on
;; the React-hook spine shares its ELEMENT-shaped `render`
;; (`re-frame.substrate.spine/make-render`): it hands the tree to React
;; untouched, so the hiccup vector reaches React as an iterable of raw
;; CLJS values — the component fn becomes a Fragment child ("Functions are
;; not valid as a React child … frame_provider") and the props map's
;; MapEntries throw UNCAUGHT ("Objects are not valid as a React child
;; (found: object with keys {key, val, …})") on every boot. The host app is
;; unaffected (separate React root) but the boot is noisy and the shell never
;; renders. Until Xray carries a hiccup-capable mount for those hosts, the
;; mount verbs refuse loudly-but-cleanly there: one `console.warn` plus the
;; inspectable status diagnostic — no mount, no uncaught error. DENYLIST
;; shape on purpose: unknown / `:custom` / test adapters keep today's
;; permissive behaviour (the mount tests ride `plain-atom` with a stubbed
;; render), and only the kinds KNOWN to take React elements refuse.

(def ^:private react-element-render-kinds
  "Adapter `:kind`s whose `:render` slot takes substrate-native React
  ELEMENTS (the shared React-hook spine), not hiccup — Xray's hiccup shell
  cannot mount through them (rf2-qgfo4). `:rf.adapter/helix` stays in the
  refusal set defensively even though the Helix adapter itself was removed
  at S7/W13 (rf2-d6epb): a stale co-loaded build could still present the
  kind, and refusing costs nothing.

  `:rf.adapter/freehand` is here for the SAME structural reason `:rf.adapter/
  uix` is, not by analogy: `re-frame.freehand.substrate` builds its adapter
  from `re-frame.substrate.spine/make-react-adapter`, so its `:render` is the
  spine's element-shaped one. It now stays on the DEFENSIVE footing
  `:rf.adapter/helix` is on: the Freehand substrate is being removed
  (rf2-0yp7w) and Xray no longer reads a Freehand host at all (rf2-l86mm),
  but a stale co-loaded build could still present the kind, and refusing it
  costs nothing. The refusal is what makes the difference a diagnostic
  instead of an uncaught React child error.

  `:rf.adapter/hicasso` IS HERE ON THE SAME STRUCTURAL GROUND, and unlike
  the two above it names a kind the runtime ACTUALLY PRODUCES today. This
  paragraph used to argue the opposite — that Hicasso minted no kind, rode
  the `:rf.adapter/uix` entry, and that the absence of a hicasso member was
  load-bearing (rf2-wtznc). rf2-hvr5h retired that premise: it shipped
  `re-frame.hicasso.substrate`, whose adapter is built from
  `re-frame.substrate.spine/make-react-adapter` and therefore carries the
  spine's element-shaped `:render`, and `docs/core/hicasso/00-installation.md`
  now teaches `(rf/init! substrate/adapter)` as the DEFAULT install. A page
  following that chapter reports `:rf.adapter/hicasso`, which this set did
  not hold, so the mount verbs took the permissive path and handed the
  hiccup shell to an element-shaped `:render` — an uncaught React child
  error exactly where the clean diagnostic belongs (rf2-zkjd5).

  A Hicasso page that installs UIx or Reagent instead is unaffected: the
  install is explicit and Hicasso ships no default-adapter registry, so such
  a host still reports that adapter's kind and refuses (or mounts) on its
  entry rather than this one."
  #{:rf.adapter/ui :rf.adapter/uix :rf.adapter/helix :rf.adapter/freehand
    :rf.adapter/hicasso})

(defn- unsupported-substrate-diagnostic [kind]
  {:ok?     false
   :reason  :unsupported-substrate
   :adapter kind
   :message (str "Xray cannot mount on the installed substrate adapter ("
                 kind "): its :render slot takes substrate-native React "
                 "elements, and Xray's shell is hiccup rendered through the "
                 "ratom-family adapters (Reagent / reagent-slim). Skipping "
                 "the Xray mount — the host app is unaffected (rf2-qgfo4).")})

(defn- refuse-unsupported-substrate!
  "When the installed adapter's `:render` is element-shaped (a React-hook
  substrate — see `react-element-render-kinds`), publish the
  `:unsupported-substrate` diagnostic (status API + one `console.warn`; the
  host app is healthy, so this is not an error) and return it. Returns nil
  when the installed substrate can host the hiccup shell."
  []
  (let [kind (rf.substrate.adapter/current-adapter)]
    (when (contains? react-element-render-kinds kind)
      (let [diagnostic (unsupported-substrate-diagnostic kind)]
        (reset! diagnostic-state diagnostic)
        (when (and (exists? js/console) (.-warn js/console))
          (.warn js/console (:message diagnostic)))
        diagnostic))))

;; ---- layout-host display snapshot ---------------------------------------
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
  (let [unmount (rf.substrate.adapter/render
                  [rf/frame-provider {:frame shell/default-frame-id}
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

(defonce ^:private seeded-frame-ids
  ;; rf2-n4p5it — the set of frame-ids whose first-mount hooks have
  ;; already fired at least once. `ensure-xray-frame!` fans the hook
  ;; table out UNGUARDED prior to this fix: `popout!` calls
  ;; `(ensure-xray-frame!)` with no arg, defaulting to the SAME
  ;; `shell/default-frame-id` the inline shell already seeded, so a
  ;; pop-out remount re-ran EVERY first-mount hook — including
  ;; `::seed-trace-and-target-frame`, which re-derives the seed frame
  ;; from the CURRENT head focusable event-bundle and re-dispatches
  ;; `:rf.xray/set-target-frame`. That reverted `:target-frame` (and
  ;; the `:epoch-history` ring keyed on it) back to the head frame
  ;; even when the user had already picked a different frame via the
  ;; L1 switcher — the inline shell's App-DB / Epoch panels jumped off
  ;; the user's choice the instant they popped the shell out. Spec
  ;; 011 §Pop-out: the pop-out shares the opener's runtime and frame —
  ;; reflecting it, not resetting it.
  ;;
  ;; This is bookkeeping for the HOOK FAN-OUT ONLY — `image-reads/seat-
  ;; xray-frame!` keeps its own independent idempotency check
  ;; (`xray-frame-seated?`, queried against the live frame registry)
  ;; and always runs; a re-seat is a cheap no-op when already seated.
  ;;
  ;; `defonce` so the guard survives shadow-cljs `:after-load` (a hot-
  ;; reload of an already-open shell must not re-seed it either).
  ;; Test-only escape hatch: `reset-for-test!` below clears this so
  ;; fixtures that wipe the frame registry between tests also get a
  ;; fresh first-mount pass.
  (atom #{}))

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

(defn ensure-seated!
  "SEAT the Xray shell frame if the host runtime is ready, so a dispatch
  into `:rf/xray` has a frame to land in. Idempotent and cheap: a
  re-seat is skipped by `image-reads/xray-frame-seated?`, and the whole
  call is a no-op until the host has installed a substrate adapter
  (`rf/make-frame` raises `:rf.error/no-adapter-installed` without one —
  the constraint `boot-on-runtime-ready!` polls for).

  ## Why this is NOT `ensure-xray-frame!` (rf2-88f1)

  `ensure-xray-frame!` is the seat PLUS the first-mount hook fan-out,
  and the fan-out must stay at first OPEN: it harvests the trace rings
  and epoch ring the user produced BEFORE opening Xray, so running it
  early would snapshot an empty ring and then skip — its `seeded-frame-
  ids` guard runs it once — leaving a later first open with no pre-open
  history to show.

  So a caller that merely needs the frame to EXIST takes this fn.
  `boot-on-runtime-ready!` already made exactly that distinction on the
  readiness path (rf2-avi7); this is that call, named, so the public
  facade can make it too.

  Returns nothing."
  ([] (ensure-seated! shell/default-frame-id))
  ([frame-id]
   (when (rf.substrate.adapter/current-adapter)
     (image-reads/seat-xray-frame! frame-id))
   nil))

(defn ensure-xray-frame!
  "Register the shell frame if not already registered, then run each
  registered first-mount hook — but ONLY on the first call for a given
  `frame-id`. Idempotent via `make-frame`'s surgical-update-on-re-
  register semantics (per Spec 002 §Frame lifecycle) on the frame side, AND
  via the `seeded-frame-ids` run-once guard on the hook side (rf2-
  n4p5it) — first call creates the frame and seeds; every subsequent
  call for the SAME `frame-id` is a surgical no-op on the frame side
  and skips the hook fan-out entirely.

  ## rf2-n4p5it — run-once guard

  Pre-fix the hook fan-out ran UNGUARDED on every call — harmless for
  the inline shell (`open!` only calls this once, before the frame
  exists), but `popout!` also calls `(ensure-xray-frame!)` with the
  SAME default `frame-id` the inline shell already seeded. Without a
  guard, popping out re-ran `::seed-trace-and-target-frame`, which
  re-derives the seed frame from the CURRENT head focusable event-
  bundle and re-dispatches `:rf.xray/set-target-frame` — reverting
  `:target-frame` (and the `:epoch-history` ring keyed on it) back to
  the head frame even when the user had already picked a different
  frame via the L1 switcher. The pop-out is supposed to REFLECT the
  opener's already-running instance, not reset it (Spec 011 §Pop-out).
  See `seeded-frame-ids` above for the guard's contract, including the
  test-only `reset-for-test!` escape hatch.

  EP-0023 §Xray Beside The Target (rf2-32siq3.36): the production singleton is
  SEATED in its OWN image-loaded frame via `image_view_reads/seat-xray-frame!`
  (`rf/make-frame {:id :rf/xray :images [(xray-image)]}`) — true runtime
  self-seating in genuine registration isolation, NOT the legacy shared
  registrar. Two blockers gated this flip and both are resolved: the
  test-namespace assembly collision (the `day8.re-frame2-xray.**` glob sweeping
  in Xray's own `*-cljs-test` namespaces → `:rf.error/image-duplicate-id`) is
  fixed by the `:select-ns :exclude` globs on `xray-image`; and the
  host-registry read regression under image-loaded seating — a `:rf.xray/*`
  sub's bare `(rf/registrations {:source :store :kind …})` / `(rf/handler-meta …)` resolving through
  Xray's OWN image generation instead of the inspected host's process-global
  registrar (the `routes-epochs` nightly xray-feature-gate caught it: empty
  route table / `currentId:null`) — is fixed by reading the host registry
  with the `{:source :store …}` query form (the SOURCE-STORE read, which
  never consults a bound image generation). Both the node-test suite and the
  `routes-epochs` feature-gate are green with the flip.

  ## `frame-id` arg (rf2-lnluk)

  Defaults to `shell/default-frame-id` (`:rf/xray`) — the production
  singleton path passes nothing and behaviour is unchanged. A testbed
  mounting a second shell against a distinct frame-id calls
  `(ensure-xray-frame! :other-frame)` so the seed/hydrate hooks land on
  that frame's app-db. Handlers are NOT re-registered per frame (the
  registry is process-global) — only the per-frame app-db seed runs.

  ## What is lazy here, and what no longer is (rf2-avi7)

  Per rf2-in6l2 the registration was attempted at preload LOAD time but
  reverted (rf2-e9s81): the preload runs before the host's `rf/init!` has
  installed a substrate adapter, and `rf/make-frame` raises
  `:rf.error/no-adapter-installed` without one.

  That constraint is about the adapter, not about opening, and the two came
  apart once `boot-on-runtime-ready!` grew a readiness loop: the SEAT now runs
  from there the moment `rf/init!` lands, so `:rf/xray` exists whether or not
  anything ever opens Xray. Tying it to `open!` had left Xray addressable but
  not writable between preload and first open — see that fn's docstring for
  the `:rf.error/frame-destroyed` this closes.

  So the `seat-xray-frame!` call below is normally a cheap re-seat skip, and
  what this fn contributes is the FIRST-MOUNT hook fan-out: seeding
  `:trace-buffer` and `:epoch-history` from the history the user produced
  BEFORE opening Xray. That belongs at first open and stays here, guarded by
  `seeded-frame-ids`. The seat is kept unconditional so a caller reaching this
  fn without the preload's loop (a testbed's second shell, a direct `open!`)
  still gets its frame.

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
    framework's rings retain pre-mount event bundles event-keyed; the
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
    focusable event-bundle in the trace buffer, so an app whose pre-mount
    events ran on `:cart-frame` rendered the App-DB panel against
    `:cart-frame` (the observed frame) while `:epoch-history` carried
    the empty `:rf/default` ring. The composite's `:history-empty?`
    resolved true → the panel rendered the boot empty-state
    'app-db for :cart-frame is at the boot value. No diffs yet.'
    EVEN WITH event-bundles from `:cart-frame` already in the buffer
    (the Mike report). Frame-switch round-trip resolved it because
    `set-frame-reducer` aligns the two axes; the first-mount path
    had to do the same. The seed-frame is the head focusable
    event-bundle's `:frame` (via `spine/focusable-head-frame-id` over the
    same event-bundle projection panels read off) — the operator-present
    discovery tier that UNIQUELY resolves the head app event-bundle's frame
    (EP-0002 rf2-bd4div: unique resolution, NOT `:rf/default` synthesis).
    When no focusable event-bundle exists (cold start; only the `:ungrouped`
    bucket present) the seed is `defaults/default-target-frame` =
    **nil = UNSELECTED**: the target stays unselected (the frame picker
    prompts a choice) rather than defaulting to `:rf/default`. The
    `:rf.xray/set-target-frame` event writes `:target-frame` and
    re-seeds `:epoch-history` from `(rf/epoch-history seed-frame)`
    in lockstep — symmetric with the picker path and the public
    `core/set-target-frame!` API.

    Discovery runs only where the slot is still UNSELECTED (rf2-88f1).
    `core/set-target-frame!` seats `:rf/xray` itself, so a host can target a
    frame before anything opens Xray and this hook can now find an explicit
    choice already in the slot — an ordering that did not exist when the seed
    was written. An explicit target wins: discovery is the operator-present
    guess at what the user is looking at, and it does not overrule what the
    host said. The preserved target is still re-dispatched through the same
    event, so `:epoch-history` picks up whatever the host recorded between
    its call and first open and the two axes stay in lockstep."
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
   ;; emit?`; the framework's frame engine honours it on every (re-)
   ;; registration so the gate survives hot-reload.
   ;;
   ;; EP-0023 §Xray Beside The Target (rf2-32siq3.36) — SEAT this singleton in
   ;; its OWN image-loaded frame via `image-reads/seat-xray-frame!` (the
   ;; `rf/make-frame {:id :rf/xray :images [(xray-image)]}` path), replacing the
   ;; legacy realm seating ({:rf.trace/frame-no-emit? true} config). The
   ;; seated frame resolves ONLY Xray's `:rf.xray/*` registrations (plus the
   ;; framework standards), in genuine isolation from the inspected target.
   ;; `seat-xray-frame!` re-asserts the trace-no-emit gate directly through
   ;; `re-frame.trace/set-frame-no-emit!` (the same seam the frame engine routed it
   ;; through), and is idempotent on re-seat (`xray-frame-seated?` skips the
   ;; duplicate-`:id` `make-frame`). Host-registry reads inside Xray's subs go
   ;; with `{:source :store …}` (which never consults a bound image
   ;; generation) so the inspector still sees the inspected app's registrar,
   ;; not its own image's — see spec/API.md §Public registrar query API.
   (image-reads/seat-xray-frame! frame-id)
   ;; rf2-n4p5it — run the hook fan-out only on the FIRST call for this
   ;; `frame-id`. `seat-xray-frame!` above stays unconditional (its own
   ;; `xray-frame-seated?` check makes a re-seat a cheap no-op); it's
   ;; the SEED/HYDRATE hook table that must not re-fire on a same-
   ;; frame-id remount (popout! after inline open!, or a repeat
   ;; ensure-xray-frame! call generally).
   (when-not (contains? @seeded-frame-ids frame-id)
     (swap! seeded-frame-ids conj frame-id)
     (doseq [{:keys [handler]} @first-mount-hooks]
       (handler frame-id)))))

(defn reset-for-test!
  "Reset the `ensure-xray-frame!` run-once guard (rf2-n4p5it) so test
  fixtures that wipe the frame registry between tests also get a
  fresh first-mount hook pass on the next `ensure-xray-frame!` call.
  Test-only — never call from production code."
  []
  (reset! seeded-frame-ids #{})
  nil)

;; ---- first-mount hook registrations -------------------------------------
;;
;; Each registration is a one-line bind from a hook id to a 0-ary fn.
;; Insertion order is load-bearing: hooks that depend on a prior hook
;; (e.g. `::auto-open-watcher` depends on the mode slot being present)
;; appear after their dependencies. The id keywords are mount-internal
;; (`::seed-trace-and-target-frame`, `::reset-transient-filters`, ...) — not
;; part of the public API.

(register-first-mount-hook!
  ::seed-trace-and-target-frame
  ;; Seed the frame's app-db with whatever the framework's per-frame
  ;; trace rings + Xray's frameless secondary ring + the framework's
  ;; epoch ring buffer have accumulated so far. The host may have
  ;; driven dispatches before the user opened Xray (rf2-43koh consumer
  ;; substrate; rf2-boyc2 :epoch-history + :target-frame).
  ;;
  ;; The snapshot comes from `trace-collector/snapshot-from-rings` — a
  ;; synchronous read of the same per-frame rings + frameless secondary
  ;; ring the production task coalescer drains. After the snapshot
  ;; lands in `:trace-buffer`, project the buffer through the same
  ;; pipeline the `:rf.xray/event-bundles` sub uses (projection +
  ;; Xray-internal hard-filter) to derive the seed-frame. Without the
  ;; internal filter a tool-frame event-bundle could be chosen as the head,
  ;; which the user never sees in the L2 list.
  ;;
  ;; DISCOVERY IS THE FALLBACK, NOT THE OVERRIDE (rf2-88f1). Since
  ;; `core/set-target-frame!` seats `:rf/xray` itself, a host can target a
  ;; frame BEFORE anything opens Xray — so by the time this hook runs the
  ;; slot may already carry an explicit choice, which it never could when
  ;; the seed was written. Discovery is the operator-present tier that
  ;; guesses what the user is looking at; an explicit target is what the
  ;; host SAID. The guess must not overwrite the statement, and on a cold
  ;; ring it does not even guess: `focusable-head-frame-id` returns nil,
  ;; and `:rf.xray/set-target-frame` writes nil as a RESET (dissoc
  ;; `:target-frame`, clear `[:focus :frame]`, empty `:epoch-history`), so
  ;; the host's boot intent vanished at first open with nothing to read.
  ;; Reading the slot first makes this the ordering the two tiers already
  ;; imply — host config, then picker, then discovery (see
  ;; `defaults/default-target-frame`).
  (fn [frame-id]
    ;; `snapshot-from-rings` reads the rings synchronously (the
    ;; production coalescer instead refreshes them via an async
    ;; dispatch) — safe here because the shell frame was just
    ;; registered and the seed must commit before first paint. We
    ;; dispatch-sync the snapshot through `:rf.xray/sync-trace-buffer`
    ;; so the first-mount render reads against pre-mount events
    ;; deterministically.
    (let [buffer          (trace-collector/snapshot-from-rings)
          event-bundles   (self-noise/filtered-event-bundles buffer)
          explicit-target (rf/with-frame frame-id
                            (rf/subscribe-once [:rf.xray/target-frame]))
          seed-frame      (or explicit-target
                              (spine/focusable-head-frame-id event-bundles)
                              defaults/default-target-frame)]
      (rf/with-frame frame-id
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer buffer])
        ;; rf2-boyc2 — seed via `:rf.xray/set-target-frame` so
        ;; `:target-frame` + `:epoch-history` move in lockstep keyed
        ;; on the frame the user will be observing on first paint
        ;; (the head focusable event-bundle's frame). Mirrors the picker-
        ;; driven `set-frame-reducer` path and `core/set-target-
        ;; frame!`.
        ;;
        ;; Re-dispatching a PRESERVED target is deliberate rather than a
        ;; skippable no-op: `:epoch-history` re-seeds from
        ;; `(rf/epoch-history seed-frame)` on every write, so routing the
        ;; preserved target through the same event harvests the epochs the
        ;; host recorded between its call and first open. Skipping the
        ;; dispatch would keep the target and strand the history at the
        ;; value it had at boot — which is the lockstep rf2-boyc2 exists
        ;; to hold.
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
  ;;
  ;; An explicitly configured host seed (`:rf.xray/filters`) is NOT a
  ;; transient user filter — it is the host's opted-in boot posture, so
  ;; the `::seed-configured-filters` hook immediately below re-applies it
  ;; ON TOP of this clean slate. Sequencing matters: this hook runs FIRST
  ;; and clears the stale localStorage slot, then the seed hook lands the
  ;; host baseline — so a user's stale session filters never survive
  ;; reload, while the host's explicit seed always does (rf2-fhtes).
  (fn [_frame-id]
    (filters-persistence/clear!)
    (spine-filters/clear-raw!)
    (frame-switcher/clear!)))

(register-first-mount-hook!
  ::seed-configured-filters
  ;; Apply the host-configured `:rf.xray/filters` seed as the boot
  ;; BASELINE for `:active-filters`, AFTER `::reset-transient-filters`
  ;; above has wiped any stale user/localStorage filter state (rf2-fhtes).
  ;;
  ;; This closes a false public contract: `configure!` accepted
  ;; `:rf.xray/filters` and the config/spec prose promised the seed would
  ;; hydrate `:active-filters`, but production never called
  ;; `filters/hydrate!` — `filters/install!` explicitly does NO hydrate and
  ;; nothing on the real `ensure-xray-frame!` path read the seed. A host
  ;; using the documented key got no error and an unfiltered first paint.
  ;;
  ;; The policy is small and coherent: an EXPLICITLY configured seed is the
  ;; programmer's opt-in and lands as the boot baseline on EVERY load (a
  ;; fresh, host-owned posture — not durable user-filter persistence, which
  ;; the reset hook deliberately kills). A `nil` seed (the default) leaves
  ;; the slot at its unfiltered registry default `{:in [] :out []}`, so a
  ;; host that never configured filters keeps a fully-unfiltered first
  ;; paint. Only a non-empty seed dispatches, so the no-seed case is a
  ;; genuine no-op.
  ;;
  ;; Mirrors the `::hydrate-static-mode` shape (read via a config helper,
  ;; dispatch the owning event). `filters/hydrate!` stays as-is for its
  ;; existing data-layer callers — this hook is seed-only and never reads
  ;; localStorage, so it cannot resurrect a stale user set. The `:rf.xray/
  ;; hydrate-filters` event (registered by `filters/install!`) is the
  ;; single `:active-filters` write seam.
  (fn [frame-id]
    (let [seed (config/get-filter-seed)]
      (when (and seed
                 (or (seq (:in seed)) (seq (:out seed))))
        (rf/with-frame frame-id
          (rf/dispatch-sync [:rf.xray/hydrate-filters seed]))))))

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
  ::hydrate-static-machines
  ;; Hydrate the Static Machines selection + per-machine sub-mode slots
  ;; from localStorage (rf2-qw0o). Durable preferences, exactly like the
  ;; column widths and the Dynamic ↔ Static mode above — the operator's
  ;; last-inspected machine is a reading position, not a transient
  ;; exploration filter, so it carries across sessions.
  ;;
  ;; This hook is the FIX for the bead. `static/machines/panel.cljs`'s
  ;; `install!` already called `persistence/hydrate!`, but `install!`
  ;; runs from `registry/register-xray-handlers!` — orchestrator time,
  ;; before this fn has registered the frame. The hydrate dispatch
  ;; therefore named a frame that did not exist yet and was refused with
  ;; a promoted `:rf.error/frame-destroyed`: the selection was silently
  ;; dropped and never restored, and every Xray-preloaded dev page load
  ;; emitted that refusal to the console. `hydrate!` now carries the same
  ;; frame guard its two siblings carry, so the orchestrator-time call
  ;; short-circuits cleanly and THIS hook is the landing site.
  ;;
  ;; Ordered after `::hydrate-static-mode` for reading order (both are
  ;; Static-surface durable prefs); the two slots are independent, so
  ;; the sequencing is not load-bearing.
  (fn [frame-id]
    (static-machines-persistence/hydrate! frame-id)))

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

;; ---- surface transition (rf2-j538f7.23) ---------------------------------
;;
;; `open!` and `open-overlay!` name two DISTINCT PHYSICAL surfaces
;; (spec/API.md §open! / open-overlay!, spec/011-Launch-Modes.md):
;; inline mounts true-inline into the app's `[data-rf-xray-host]`
;; layout host (normal flow, `position: relative`); overlay mounts a
;; fixed modal under `document.body` (`position: fixed`). `shell-view`
;; derives that positioning from its render-time `:mode` prop, and the
;; mount node's OWNER (host vs body) is fixed at create-time. So a mode
;; change is NOT a CSS toggle — the pre-rf2-j538f7.23 update-only
;; branches merely flipped visibility + `data-rf-xray-mode` +
;; `mount-state :mode`, leaving the actual React tree, parent, and
;; layout on the PREVIOUS surface: the stored mode/attrs reported the
;; requested surface while nothing physically moved (a Settings control
;; that reported success without realizing it).
;;
;; `switch-surface!` realizes the requested surface both directions:
;; it recreates the React root on the (rare) mode flip rather than
;; introducing a second parallel mount. The shell's user/runtime state
;; lives OUTSIDE the React tree (the `:rf/xray` frame's app-db,
;; localStorage), so recreating the root preserves the focused epoch,
;; selected tab, theme, and filters — only transient component-local
;; React state (a drag in flight) is discarded, which is acceptable on
;; a deliberate surface change. `ensure-xray-frame!` (invoked by
;; `mount-shell-into!`) is idempotent via its `seeded-frame-ids` guard,
;; so the re-render does NOT re-seed app-db.
(defn- switch-surface!
  "Re-parent + re-render the singleton shell so its realized physical
  surface matches `mode` (`:inline` or `:overlay`). Precondition: a
  mount already exists whose realized `:mode` differs from `mode`.

  Unmounts the current React root (frame/app-db survive — they live
  outside the component tree), creates a fresh mount node in the target
  owner (the inline layout host for `:inline`; `document.body` for
  `:overlay`), and renders `shell-view` with the new mode.
  `mount-shell-into!` publishes `mount-state :mode` + the
  `data-rf-xray-mode` attributes only AFTER the DOM surface is realized.

  `:inline` requires the app-provided `[data-rf-xray-host]`. When it is
  absent the existing surface is LEFT INTACT and the missing-host
  diagnostic is returned — a failed inline switch must not strand the
  user with no shell."
  [mode]
  (let [{:keys [unmount]} @mount-state]
    (case mode
      :inline
      (if (layout-host)
        (do
          ;; Unmount the old React root FIRST (its container `<div>`
          ;; stays attached, emptied by React); `create-inline-mount-
          ;; node!` then evicts that stale `#rf-xray-root` via
          ;; `remove-stale-root!` before allocating the fresh node in
          ;; the host.
          (when unmount (try (unmount) (catch :default _ nil)))
          (mount-shell-into! (create-inline-mount-node!) :inline))
        ;; No layout host — keep the existing overlay; surface the
        ;; actionable diagnostic rather than tearing the shell down.
        (report-diagnostic! (missing-host-diagnostic)))

      :overlay
      (do
        (when unmount (try (unmount) (catch :default _ nil)))
        ;; The shell is leaving the inline host — restore the host's
        ;; pre-Xray `display` so a `collapse-host!` that ran while
        ;; inline cannot strand it at `display:none` after we re-parent
        ;; to `document.body`, then drop the now-stale snapshot (the
        ;; next inline switch re-snapshots the host).
        (restore-host-display!)
        (reset! host-display-snapshot nil)
        (mount-shell-into! (create-overlay-mount-node!) :overlay)))))

(defn open!
  "Mount + show the default Xray shell in the app-provided true-inline
  layout host. On first call: register the `:rf/xray` frame, find the
  configured host (`[data-rf-xray-host]` by default), create
  `#rf-xray-root` inside it, render the shell via the installed
  substrate adapter, mark visible. On subsequent calls when already
  mounted inline: make the container visible (a CSS-only show — the
  <80ms toggle target). When already mounted as the OVERLAY surface:
  realize the requested inline surface — re-parent the shell into the
  layout host and re-render inline (rf2-j538f7.23) so the public
  `open!` verb honours its distinct-surface contract instead of
  silently leaving the overlay in place.

  Per rf2-in6l2 the `:rf/xray` registration is lazy here (post-
  `rf/init!`) rather than at preload time; see `ensure-xray-frame!`
  for the rationale.

  If the substrate adapter is absent, returns nil so preload retry can
  wait. If the installed adapter is a React-element substrate (a kind in
  `react-element-render-kinds`, whose `:render` cannot take the hiccup
  shell, rf2-qgfo4), returns the `:unsupported-substrate` diagnostic and
  logs one `console.warn` without mounting. If the layout host is
  missing, returns an inspectable diagnostic map and logs
  `console.error` without blocking startup."
  []
  (if-let [state @mount-state]
    (if (= :inline (:mode state))
      (do (set-visible! (:node state) true)
          (swap! mount-state assoc :visible? true)
          @mount-state)
      (switch-surface! :inline))
    (when (rf.substrate.adapter/current-adapter)
      (or (refuse-unsupported-substrate!)
          (if-let [node (create-inline-mount-node!)]
            (mount-shell-into! node :inline)
            @diagnostic-state)))))

(defn open-overlay!
  "Debug/fallback launch path: mount Xray as the fixed overlay under
  `document.body`. Not the default developer experience.

  On first call: create the overlay `#rf-xray-root` under
  `document.body` and render the shell fixed. When already mounted as
  the overlay surface: make it visible (a CSS-only show). When already
  mounted as the INLINE surface: realize the requested overlay surface
  — re-parent the shell to `document.body` and re-render fixed
  (rf2-j538f7.23) so the public `open-overlay!` verb honours its
  distinct-surface contract instead of only flipping attributes.

  Refuses with the `:unsupported-substrate` diagnostic on a
  React-element substrate host (rf2-qgfo4), same as `open!`."
  []
  (if-let [state @mount-state]
    (if (= :overlay (:mode state))
      (do (set-visible! (:node state) true)
          (swap! mount-state assoc :visible? true)
          @mount-state)
      (switch-surface! :overlay))
    (when (rf.substrate.adapter/current-adapter)
      (or (refuse-unsupported-substrate!)
          (mount-shell-into! (create-overlay-mount-node!) :overlay)))))

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
  (per `open!`); subsequent calls flip visibility.

  ## Generic reopen preserves the realized surface (rf2-j538f7.41)

  `toggle!` is the global show/hide route (the `Ctrl+Shift+C` keybinding
  drives it) — NOT an explicit surface-switch. When reopening a hidden
  shell it therefore shows whatever physical surface the shell was last
  realized on: a retained `:overlay` mount reopens as the overlay (a
  CSS-only show under `document.body` via `open-overlay!`); anything
  else — `:inline`, or the first-ever toggle when nothing is mounted yet
  (`@mount-state` nil ⇒ `:mode` nil) — reopens inline via `open!`. The
  explicit `open!` / `open-overlay!` verbs stay the intentional
  surface-CHANGE APIs, each honouring its own distinct-surface contract
  (incl. the missing-host fail-safe from rf2-j538f7.23).

  Pre-fix the hidden branch hard-coded `(open!)`, so a hidden OVERLAY
  mount reopened via `Ctrl+Shift+C` was treated as an explicit
  inline-surface request: with a layout host it silently re-parented the
  overlay back inline (discarding the operator's fullscreen choice and
  component-local state, and violating the CSS-only reopen contract);
  with no host `switch-surface! :inline` returned the missing-host
  diagnostic and left the overlay stranded hidden — repeated toggles
  could never recover it (the operator had to know to call
  `open-overlay!` again despite the toggle promising to show it)."
  []
  (if (visible?)
    (close!)
    (if (= :overlay (:mode @mount-state))
      (open-overlay!)
      (open!))))

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
  substrate unmount, clears the opener-gone watchdog, detaches the
  opener-side `pagehide` announcer, attempts to close the popout
  window, and clears the singleton. All steps run inside
  swallow-errors guards — this is a last-chance cleanup (test
  fixture, opener-side unload), not a contract-checking call site."
  []
  (when-let [{:keys [window unmount watchdog-id keydown-dispose
                     opener-window opener-pagehide-handler]} @popout-state]
    (when watchdog-id
      (try (js/clearInterval watchdog-id) (catch :default _ nil)))
    ;; rf2-61i5 — drop the pop-out document's keydown listener. This is the
    ;; ONE disposal path for it: external close routes here via
    ;; `register-popout-unload-cleanup!`, `teardown!` calls this directly,
    ;; and `popout!` returns the existing state rather than re-opening, so
    ;; listeners cannot accumulate across open/close cycles. The disposer
    ;; removes the exact fn object that was added — `removeEventListener`
    ;; compares by reference, and the handler is a fresh per-pop-out closure
    ;; over that document.
    (when keydown-dispose
      (try (keydown-dispose) (catch :default _ nil)))
    ;; rf2-uong — drop the opener-side `pagehide` announcer. Unlike the
    ;; watchdog (a timer owned by this realm) the listener would otherwise
    ;; accumulate across repeated popout open/close cycles in ONE opener
    ;; realm, each survivor closing over a now-detached overlay node.
    (when (and opener-window opener-pagehide-handler)
      (try (.removeEventListener opener-window "pagehide" opener-pagehide-handler)
           (catch :default _ nil)))
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
  `tools/xray/spec/Conventions.md` §Mount conventions.

  That obligation is about the OPENER-document listener only. The
  pop-out document's own listener (rf2-61i5) IS disposed from here, via
  `teardown-popout-state!`, because mount owns its disposer rather than
  having to reach into keybinding for it."
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

(defn boot-on-runtime-ready!
  "Preload entry: wait briefly for the host app to call `rf/init!`, then run
  Xray's two runtime-dependent boot steps — SEAT the shell frame, and open
  Xray in the true-inline host unless the host disabled auto-open. Missing
  host emits a single actionable diagnostic; no alert and no blocking startup.

  ## Why the frame is seated here (rf2-avi7)

  `:rf/xray` is an ordinary frame, so `rf/make-frame` needs an installed
  substrate adapter: it raises `:rf.error/no-adapter-installed` before the
  host's `rf/init!`, which is why rf2-e9s81 reverted seating at preload LOAD
  time. Adapter readiness is therefore the earliest moment the frame can exist
  at all, and this loop is the one place in Xray already watching for it.

  Seating used to ride `open!` alone, which tied the frame's existence to a
  React commit. Xray's whole `:rf.xray/*` instruction set is registered at
  preload, so between preload and first open Xray was ADDRESSABLE BUT NOT
  WRITABLE: every dispatch into `:rf/xray` — `core/set-target-frame!`,
  `focus!`, a host re-orienting the observed frame — recovered-but-emitted
  `:rf.error/frame-destroyed`, dropped the host's intent, and named the
  HANDLER's registration coord rather than the caller, so the diagnostic
  pointed at the wrong file. A host that sets `:rf.xray/auto-open? false`
  never left that window until it opened a panel of its own. Seating here
  closes it: the frame exists as soon as the runtime does, opened or not.

  What stays lazy is the FIRST-MOUNT seed/hydrate fan-out. It harvests the
  trace rings the user has already produced BEFORE opening Xray, so it belongs
  at first open and keeps its own `seeded-frame-ids` guard inside
  `ensure-xray-frame!` — which skips the redundant re-seat and runs the hooks."
  []
  (when (compare-and-set! auto-open-state {:started? false :attempts 0}
                          {:started? true :attempts 0})
    ;; `auto-open-enabled?` is read INSIDE the tick and nowhere else. The
    ;; documented ordering is `configure!` → `rf/init!`, and BOTH run after the
    ;; preload's load-time block has called this fn — so a read taken out here
    ;; would see the default `true` on every host that suppresses the open, and
    ;; the `:auto-open-disabled` diagnostic would never be recorded.
    ;;
    ;; What it no longer gates is the SEAT. A host that turns auto-open off is
    ;; exactly the host most likely to drive Xray by dispatch instead, so the
    ;; frame is seated on readiness either way and only the open is suppressed.
    (letfn [(tick! []
              (let [{:keys [attempts]} @auto-open-state]
                (cond
                  @mount-state nil

                  (rf.substrate.adapter/current-adapter)
                  (do
                    ;; Idempotent (`xray-frame-seated?` skips a re-seat), and
                    ;; seed-free — `open!` here, or a later first open, still
                    ;; runs the first-mount hooks through `ensure-xray-frame!`.
                    ;; Same call the public facade takes (rf2-88f1), so the
                    ;; seat-without-seeding rule is expressed once.
                    (ensure-seated! shell/default-frame-id)
                    (if (config/auto-open-enabled?)
                      (open!)
                      (note-auto-open-disabled!)))

                  (< attempts 120)
                  (do
                    (swap! auto-open-state update :attempts inc)
                    (js/setTimeout tick! 50))

                  ;; Gave up waiting for the host runtime. The missing-adapter
                  ;; diagnostic is about auto-OPEN failing, so it is reported
                  ;; only to a host that wanted the open; one that suppressed
                  ;; it is told just that, and its unseated frame is the
                  ;; consequence of its own missing `rf/init!`.
                  (config/auto-open-enabled?)
                  (report-diagnostic!
                    {:ok? false
                     :reason :no-substrate-adapter
                     :message "Xray preload could not auto-open because no re-frame2 substrate adapter was installed. Call (rf/init! adapter) before app render."
                     :selector (config/get-layout-host-selector)
                     :snippet  config/default-layout-host-snippet})

                  :else
                  (note-auto-open-disabled!))))]
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
    ;; rf2-uong — the overlay now also fires on an opener RELOAD, so the
    ;; copy must not assert "closed": a reloaded opener is still on screen,
    ;; and a message contradicting what the user can see reads as a bug in
    ;; Xray rather than an explanation. Name the cause generically and say
    ;; what to do; "no longer connected to the running runtime" is the true
    ;; statement common to closed, navigated-away and reloaded openers.
    (set! (.-textContent hint)
          (str "The host application window was closed or reloaded. "
               "This Xray pop-out is no longer connected to the "
               "running runtime — close this window and open a "
               "fresh pop-out."))
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

;; ---- popout opener-RELOAD announcer (rf2-uong) ---------------------------
;;
;; The watchdog above cannot see an opener RELOAD, and widening its
;; predicate would not help — the watchdog does not survive the event it
;; would be asked to detect.
;;
;; Everything Xray runs for the popout lives in the OPENER's JS realm:
;; `popout!` is called from the opener, `rf.substrate.adapter/render` paints
;; the popout's DOM from there, `popout-state` is an opener-realm atom, and
;; `start-opener-gone-watchdog!`'s `js/setInterval` registers its timer on
;; the OPENER's window. A hard reload of the opener discards that realm and
;; every timer, closure and atom it owned. So after the reload there is no
;; tick left to evaluate any predicate, however widened — and meanwhile
;; `window.opener` still resolves (a WindowProxy survives navigation,
;; now fronting the NEW realm) with `.closed` false, so even a surviving
;; watchdog would read "opener fine".
;;
;; Detecting it AFTER the fact would therefore need Xray code running in
;; the POPOUT's own realm, which today runs none — the popout document is
;; opened blank and rendered into from the opener. That means injecting a
;; `<script>`: real new surface, for a case the opener can simply announce.
;;
;; So the opener announces its own death on the way out. At `pagehide` the
;; opener's realm is still alive and still holds the direct DOM handle to
;; the popout's overlay node (same-origin, no serialisation layer — the
;; popout's whole posture), so revealing the overlay is the SAME
;; `show-opener-gone-overlay!` the watchdog calls, reached from a different
;; edge. The mutation lands in the popout's document, which is NOT reloaded,
;; so it persists after the opener realm is gone.
;;
;; `pagehide` fires for reload, cross-document navigation AND close, so this
;; also backstops the cases the watchdog already covers.

(defn- register-opener-reload-announcer!
  "Reveal the popout's opener-gone overlay when the OPENER window unloads —
  the hard-reload case `opener-gone?` structurally cannot observe (rf2-uong).
  Returns the registered handler so `teardown-popout-state!` can detach it,
  or nil when no listener could be registered.

  `opener-win` is passed rather than read from `js/window` so the dependency
  is explicit and a test can drive the listener against a stub.

  `pagehide` ONLY, deliberately — never `unload`/`beforeunload`. This
  listener goes on the DEVELOPER'S OWN APPLICATION WINDOW, and an `unload`
  or `beforeunload` handler makes a page ineligible for the back/forward
  cache in every major browser. Xray is a devtool: it must not degrade the
  navigation behaviour of the app it is inspecting to report on itself.
  `pagehide` is the specified replacement and carries no such penalty.

  Two guards:

  - **`persisted`** — a persisted `pagehide` is a bfcache FREEZE, not a
    teardown, and a back-navigation can resume the very realm (and popout
    render tree) that is being suspended. Announcing there would cry wolf
    over a popout about to work again, so only a non-persisted pagehide
    reveals.
  - **window identity** — mirrors `register-popout-unload-cleanup!`: a
    stale handler must not paint over a popout that a fresh `popout!` has
    since replaced."
  [opener-win win overlay-node]
  (when (and (some? opener-win) (.-addEventListener opener-win))
    (let [handler (fn opener-pagehide-handler [event]
                    (when (and (not (.-persisted event))
                               (some-> @popout-state :window (identical? win)))
                      (show-opener-gone-overlay! overlay-node)))]
      (try
        (.addEventListener opener-win "pagehide" handler)
        handler
        (catch :default _ nil)))))

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
  `{:ok? false :reason :popup-blocked}` when `window.open` fails, or
  the `:unsupported-substrate` diagnostic on a React-element substrate
  host (rf2-qgfo4).

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
  overlay!` + `start-opener-gone-watchdog!` for the contract.

  Per rf2-uong that pair is joined by an opener-side `pagehide`
  announcer, because the watchdog covers only the cases in which
  the OPENER'S REALM OUTLIVES the event. A hard reload of the
  opener destroys the realm that owns the watchdog's timer, so no
  widening of `opener-gone?` could catch it; instead the opener
  reveals the overlay itself on its way out, while it still holds
  the popout's DOM handle. See `register-opener-reload-announcer!`."
  []
  (if-let [state @popout-state]
    state
    (if-not (rf.substrate.adapter/current-adapter)
      {:ok? false :reason :no-substrate-adapter}
      (or (refuse-unsupported-substrate!)
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
                  (let [unmount      (rf.substrate.adapter/render
                                       ;; rf2-tqlmq — same mount-wrap as
                                       ;; `mount-shell-into!`: wrap the popout
                                       ;; shell in the shell's frame-provider so
                                       ;; its own `:rf.view/rendered` trace
                                       ;; resolves to the trace-disabled Xray
                                       ;; frame instead of falling through to
                                       ;; `:rf/default` and leaking into the
                                       ;; inspected app frame's epoch `:renders`.
                                       [rf/frame-provider {:frame shell/default-frame-id}
                                        [shell/shell-view {:mode :popout}]]
                                       node nil)
                        overlay-node (install-opener-gone-overlay! doc)
                        watchdog-id  (start-opener-gone-watchdog! win overlay-node)
                        ;; rf2-uong — the reload case the watchdog cannot
                        ;; see, because the reload destroys the watchdog.
                        announcer    (register-opener-reload-announcer!
                                       js/window win overlay-node)
                        ;; rf2-61i5 — the pop-out's OWN keydown listener.
                        ;; Key events do not cross realms, so without this
                        ;; the documented keyboard workflow (Cmd/Ctrl+K,
                        ;; Cmd/Ctrl+Shift+M, Space / L / j / k / G, `,`/s)
                        ;; is inert whenever focus is in this window.
                        ;; `keydown-dispose` is nil when keybinding is not
                        ;; loaded or the host disabled it.
                        keydown-dispose (when-let [install @popout-keydown-installer]
                                          (try (install doc)
                                               (catch :default _ nil)))
                        state        {:ok?          true
                                      :window       win
                                      :node         node
                                      :unmount      unmount
                                      :mode         :popout
                                      :overlay-node overlay-node
                                      :watchdog-id  watchdog-id
                                      :keydown-dispose         keydown-dispose
                                      :opener-window           js/window
                                      :opener-pagehide-handler announcer}]
                    (reset! popout-state state)
                    (register-popout-unload-cleanup! win)
                    state)))))))))


(ns day8.re-frame2-causa.mount
  "DOM-side mount machinery for the Causa shell. Per rf2-tijr Option C +
  spec/007-UX-IA.md §The default landing view:

  - The preload auto-opens the shell into the app-provided
    `[data-rf-causa-host]` layout host once the substrate adapter is
    ready. This is true inline layout: Causa participates in normal
    flex/grid flow on the left, and the app stays visible/clickable to
    the right.
  - Subsequent presses toggle the container's `display` between
    `block` and `none` (a CSS-only show/hide; per the spec a re-mount
    would discard internal state and miss the <80ms toggle target).
  - All state is `defonce`-guarded so shadow-cljs `:after-load` does
    not re-attach listeners or re-mount the shell.

  ## Why we use the substrate adapter's render fn

  Per rf2-tijr Causa is substrate-agnostic: the host may be running
  Reagent, UIx, or Helix. The substrate adapter's `:render` slot is
  the canonical mount path (`rf/render render-tree mount-point opts`);
  every adapter's impl produces an unmount fn on first call. Causa
  calls that path so the shell mounts via the adapter the host
  installed via `(rf/init! ...)`.

  ## Production posture

  The preload's `interop/debug-enabled?` gate (see preload.cljs) means
  mount never reaches production builds. This namespace therefore
  contains no production-elision logic of its own — the call-site
  gate is sufficient.

  ## Lazy `:rf/causa` frame registration (rf2-in6l2)

  The Causa shell wraps every panel in `[rf/frame-provider {:frame
  :rf/causa} …]` and every panel is `reg-view`-wrapped so subscribes
  resolve through the React-context tier to the named frame. For that
  routing to land in the *registered* `:rf/causa` frame (not chain-
  resolve to `:rf/default`), the frame must exist — and the preload
  can't register it because the preload runs before the host's
  `rf/init!` has installed a substrate adapter, and `reg-frame` writes
  trace events that need a running adapter to be reactive (per rf2-e9s81
  the preload-time `reg-frame :rf/causa` path is the one that produced
  the iw5ym regression). The first Ctrl+Shift+C keypress fires AFTER
  `rf/init!`, so `open!` is the canonical place to call `reg-frame` —
  the call is idempotent (surgical update on re-register) so subsequent
  toggles are no-ops on this axis."
  (:require [re-frame.core :as rf]
            [re-frame.substrate.adapter :as substrate-adapter]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.shell :as shell]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- mount state ---------------------------------------------------------

(defonce ^:private mount-state
  ;; Singleton — only one Causa shell mounted per process. The map shape:
  ;;   {:node      <DOM element>      ; the freshly-created <div>
  ;;    :unmount   (fn [] ...)         ; substrate adapter unmount fn
  ;;    :visible?  <boolean>
  ;;    :mode      <:inline|:overlay|:docked>}
  ;; Nil before first mount; never re-allocated across reloads thanks
  ;; to defonce.
  (atom nil))

(defonce ^:private popout-state
  ;; Optional second-window mount. Shape mirrors mount-state plus
  ;; `:window`. The opener runtime remains the source of truth.
  (atom nil))

(defonce ^:private inline-mounts
  ;; DOM-node → {:node :unmount :panel-id}. Inline panel mounts are
  ;; independent from the overlay shell and from each other.
  (atom {}))

(defonce ^:private diagnostic-state
  (atom {:ok? true :reason nil}))

(defonce ^:private auto-open-state
  (atom {:started? false :attempts 0}))

(declare ensure-causa-frame!)

(defn mounted?
  "True when the Causa shell has been mounted at least once. The shell
  may currently be hidden — see `visible?`."
  []
  (some? @mount-state))

(defn visible?
  "True when the shell exists *and* its container is currently
  visible (display != none)."
  []
  (boolean (:visible? @mount-state)))

(defn status
  "Return inspectable Causa mount/API status. Diagnostics are non-
  blocking; missing host failures land here and in `console.error`."
  []
  {:mounted?      (mounted?)
   :visible?      (visible?)
   :mode          (:mode @mount-state)
   :diagnostic    @diagnostic-state
   :host-selector (config/get-layout-host-selector)
   :auto-open?    (config/auto-open-enabled?)})

;; ---- DOM helpers ---------------------------------------------------------

(defn- create-overlay-mount-node! []
  (let [node (.createElement js/document "div")]
    (set! (.-id node) "rf-causa-root")
    (.setAttribute node "data-rf-causa-mode" "overlay")
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
     :message  (str "Causa default launch requires an app-provided true-inline "
                    "layout host matching " selector ". Add a left-side host "
                    "to your normal app layout, or configure "
                    ":layout/host-selector before the preload opens.")
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

(defn- create-inline-mount-node! []
  (if-let [host (layout-host)]
    (let [node (.createElement js/document "div")]
      (set! (.-id node) "rf-causa-root")
      (.setAttribute node "data-rf-causa-mode" "inline")
      (set! (-> node .-style .-display) "block")
      (set! (-> node .-style .-height) "100%")
      (set! (-> node .-style .-minHeight) "100vh")
      (.appendChild host node)
      (clear-diagnostic!)
      node)
    (do
      (report-diagnostic! (missing-host-diagnostic))
      nil)))

(defn- shell-node [node]
  (when (and (some? node) (.-querySelector node))
    (.querySelector node "[data-testid=\"rf-causa-shell\"]")))

(defn- set-visible! [node visible?]
  (when (some? node)
    (set! (-> node .-style .-display)
          (if visible? "block" "none"))))

(defn- set-mode-attrs! [node mode]
  (when (some? node)
    (let [mode-name (name mode)]
      (.setAttribute node "data-rf-causa-mode" mode-name)
      (when-let [shell (shell-node node)]
        (.setAttribute shell "data-mode" mode-name)))))

(defn- set-body-docked! [docked?]
  (when (and (exists? js/document) (.-body js/document))
    (set! (-> js/document .-body .-style .-paddingRight)
          (if docked? "40%" ""))))

(defn- mount-shell-into! [node mode]
  (ensure-causa-frame!)
  (let [unmount (substrate-adapter/render [shell/shell-view {:mode mode}] node nil)]
    (set-mode-attrs! node mode)
    (reset! mount-state
            {:node     node
             :unmount  unmount
             :visible? true
             :mode     mode})
    @mount-state))

;; ---- public API ----------------------------------------------------------

(defn- ensure-causa-frame!
  "Register the `:rf/causa` frame if not already registered. Idempotent
  via `reg-frame`'s surgical-update-on-re-register semantics (per Spec
  002 §reg-frame). Called from `open!` on every keypress — first call
  creates the frame, subsequent calls are surgical no-ops.

  ## Why here, not at preload time

  Per rf2-in6l2 the registration was attempted at preload time but
  reverted (rf2-e9s81): the preload runs before the host's `rf/init!`
  has installed a substrate adapter, and the `:on-create` listener
  dispatch needs a running adapter to be reactive. The first
  Ctrl+Shift+C keypress fires from the user well after `rf/init!`, so
  `open!` is the canonical lazy-registration point.

  ## App-db seeding

  Two slots seed on first open so the panels render against history
  the user has already produced before opening Causa:

  - `:trace-buffer` — seeded from the trace-bus atom (rf2-in6l2). The
    atom collects pre-mount traces; the seed lifts them into the
    reactive slot at first Ctrl+Shift+C. Subsequent
    `trace-bus/collect-trace!` calls dispatch
    `:rf.causa/note-trace-event` into `:rf/causa` so the sub fires
    IMMEDIATELY on every push.

  - `:epoch-history` — seeded from `(rf/epoch-history target)`
    (rf2-1barg). Pre-mount epoch settles fire the
    `:rf.causa/epoch-collector` cb but it short-circuits when
    `:rf/causa` is not yet registered (host-side records would
    otherwise route into a non-existent frame). The seed lifts the
    accumulated history into Causa's reactive slot at first
    Ctrl+Shift+C; later settles flow through the cb's dispatch path
    on the standard reactive surface. Target frame defaults to
    `:rf/default` per `defaults/default-target-frame` until a frame
    picker lands."
  []
  (rf/reg-frame :rf/causa {})
  ;; Seed the frame's app-db with whatever the trace-bus atom + the
  ;; framework's epoch ring buffer have accumulated so far. The host
  ;; may have driven dispatches before the user opened Causa.
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/sync-trace-buffer (trace-bus/buffer)])
    (rf/dispatch-sync [:rf.causa/sync-epoch-history
                       (vec (rf/epoch-history defaults/default-target-frame))])))

(defn open!
  "Mount + show the default Causa shell in the app-provided true-inline
  layout host. On first call: register the `:rf/causa` frame, find the
  configured host (`[data-rf-causa-host]` by default), create
  `#rf-causa-root` inside it, render the shell via the installed
  substrate adapter, mark visible. On subsequent calls (when already
  mounted): make the container visible.

  Per rf2-in6l2 the `:rf/causa` registration is lazy here (post-
  `rf/init!`) rather than at preload time; see `ensure-causa-frame!`
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
  "Debug/fallback launch path: mount Causa as the old fixed overlay
  under `document.body`. Not the default developer experience."
  []
  (if-let [state @mount-state]
    (do (set-visible! (:node state) true)
        (set-mode-attrs! (:node state) :overlay)
        (set-body-docked! false)
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

(defn dock!
  "Dock Causa against the host page. The shell remains the same
  runtime-backed panel, but the root and shell DOM carry
  `data-rf-causa-mode=\"docked\"` / `data-mode=\"docked\"`, and the
  host body gets right padding so the inspected app is not obscured.
  Returns the mount-state map."
  []
  (when-let [state (or @mount-state (open-overlay!))]
    (set-mode-attrs! (:node state) :docked)
    (set-body-docked! true)
    (swap! mount-state assoc :mode :docked :visible? true)
    @mount-state))

(defn undock!
  "Return the overlay shell to its non-layout-affecting debug mode."
  []
  (when-let [state @mount-state]
    (set-mode-attrs! (:node state) :overlay)
    (set-body-docked! false)
    (swap! mount-state assoc :mode :overlay))
  @mount-state)

(defn toggle!
  "Toggle the Causa shell's visibility. First call mounts + shows
  (per `open!`); subsequent calls flip visibility."
  []
  (if (visible?) (close!) (open!)))

(defn- teardown-popout-state!
  "Internal: tear down the popout singleton if present. Invokes the
  substrate unmount, attempts to close the popout window, and clears
  the singleton. All steps run inside swallow-errors guards — this is
  a last-chance cleanup (test fixture, opener-side unload), not a
  contract-checking call site."
  []
  (when-let [{:keys [window unmount]} @popout-state]
    (when unmount
      (try (unmount) (catch :default _ nil)))
    (when window
      (try
        (when-not (.-closed window)
          (.close window))
        (catch :default _ nil)))
    (reset! popout-state nil))
  nil)

(defn- teardown-inline-mounts!
  "Internal: iterate every registered inline-panel mount, invoke its
  unmount fn, and clear the registry. Each unmount is wrapped in a
  swallow-errors guard so a single failed unmount cannot strand the
  remaining entries (per the teardown contract in tools/causa/spec/
  011-Launch-Modes.md §Mount lifecycle)."
  []
  (doseq [[node {:keys [unmount]}] @inline-mounts]
    (when unmount
      (try (unmount) (catch :default _ nil)))
    (when (and node (.-removeAttribute node))
      (try (.removeAttribute node "data-rf-causa-mode")
           (catch :default _ nil))))
  (reset! inline-mounts {})
  nil)

(defn teardown!
  "Tear the shell down completely — unmount every Causa mount surface,
  remove DOM nodes, clear every mount singleton. Intended for tests;
  production sessions keep the shell across the page's lifetime.

  Three singletons are cleared per rf2-yudol (audit rf2-a6tvr Q1-1+Q1-2):

  - `mount-state` — the default in-app shell.
  - `popout-state` — the optional second-window shell (also closed).
  - `inline-mounts` — every embedded panel registered via
    `mount-inline-panel!`.

  All unmount calls run inside swallow-errors guards so a single
  failed unmount cannot strand the remaining singletons."
  []
  (when-let [{:keys [node unmount]} @mount-state]
    (when unmount
      (try (unmount) (catch :default _ nil)))
    (when (and node (.-parentNode node))
      (.removeChild (.-parentNode node) node))
    (set-body-docked! false)
    (reset! mount-state nil))
  (teardown-popout-state!)
  (teardown-inline-mounts!)
  (clear-diagnostic!)
  (reset! auto-open-state {:started? false :attempts 0})
  nil)

(defn auto-open-inline!
  "Preload entry: wait briefly for the host app to call `rf/init!`, then
  open Causa in the true-inline host. Missing host emits a single
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
                     :message "Causa preload could not auto-open because no re-frame2 substrate adapter was installed. Call (rf/init! adapter) before app render."
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

(defn popout!
  "Open a same-origin Causa pop-out window and render the shell into it.
  The pop-out shares the opener runtime and Causa frame; no
  serialisation layer is introduced. Returns a state map, or
  `{:ok? false :reason :popup-blocked}` when `window.open` fails.

  Per rf2-yudol the popout window registers a `pagehide`/`unload`
  listener that clears `popout-state` when the user closes the
  window externally, so the next `popout!` is a fresh first-mount
  rather than returning a stale state map whose `:window` is
  already closed."
  []
  (if-let [state @popout-state]
    state
    (if-not (substrate-adapter/current-adapter)
      {:ok? false :reason :no-substrate-adapter}
      (let [win (when (exists? js/window)
                  (.open js/window "" "rf-causa-popout"
                         "popup,width=960,height=720"))]
        (if-not win
          {:ok? false :reason :popup-blocked}
          (do
            (ensure-causa-frame!)
            (let [doc  (.-document win)
                  body (.-body doc)
                  node (.createElement doc "div")]
              (set! (.-title doc) "Causa")
              (set! (.-id node) "rf-causa-popout-root")
              (.setAttribute node "data-rf-causa-mode" "popout")
              (.appendChild body node)
              (let [unmount (substrate-adapter/render [shell/shell-view {:mode :popout}] node nil)
                    state   {:ok? true :window win :node node :unmount unmount :mode :popout}]
                (reset! popout-state state)
                (register-popout-unload-cleanup! win)
                state))))))))

(defn mount-inline-panel!
  "Render one Causa panel into `node` without the shell chrome. `panel-id`
  is any sidebar panel id; nil defaults to the hero panel. Returns a
  mount map and stores the unmount fn by DOM node."
  ([node panel-id]
   (mount-inline-panel! node panel-id nil))
  ([node panel-id _opts]
   (if-not (and node (substrate-adapter/current-adapter))
     {:ok? false :reason (if node :no-substrate-adapter :missing-node)}
     (do
       (ensure-causa-frame!)
       (.setAttribute node "data-rf-causa-mode" "inline")
       (let [unmount (substrate-adapter/render
                       [shell/inline-panel-view {:panel-id (or panel-id :event-detail)}]
                       node nil)
             state   {:ok? true :node node :panel-id (or panel-id :event-detail)
                      :unmount unmount :mode :inline}]
         (swap! inline-mounts assoc node state)
         state)))))

(defn unmount-inline-panel!
  "Unmount a previously mounted inline panel from `node`."
  [node]
  (when-let [{:keys [unmount]} (get @inline-mounts node)]
    (when unmount
      (try (unmount) (catch :default _ nil)))
    (.removeAttribute node "data-rf-causa-mode")
    (swap! inline-mounts dissoc node))
  nil)

(ns day8.re-frame2-causa.mount
  "DOM-side mount machinery for the Causa shell. Per rf2-tijr Option C +
  spec/007-UX-IA.md §The default landing view:

  - First Ctrl+Shift+C press creates a fresh `<div>` under
    `document.body` and mounts the shell into it via the substrate
    adapter's render fn.
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
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.shell :as shell]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- mount state ---------------------------------------------------------

(defonce ^:private mount-state
  ;; Singleton — only one Causa shell mounted per process. The map shape:
  ;;   {:node      <DOM element>      ; the freshly-created <div>
  ;;    :unmount   (fn [] ...)         ; substrate adapter unmount fn
  ;;    :visible?  <boolean>
  ;;    :mode      <:overlay|:docked>}
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

;; ---- DOM helpers ---------------------------------------------------------

(defn- create-mount-node! []
  (let [node (.createElement js/document "div")]
    (set! (.-id node) "rf-causa-root")
    (.setAttribute node "data-rf-causa-mode" "overlay")
    ;; The shell uses position: fixed for its own outer element, so the
    ;; root <div> only needs to be a no-layout-impact host. Leave its
    ;; default block-level styling intact; the shell handles its own
    ;; geometry.
    (.appendChild (.-body js/document) node)
    node))

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
  "Mount + show the Causa shell. On first call: register the `:rf/causa`
  frame, create the root <div>, render the shell into it via the
  installed substrate adapter, mark visible. On subsequent calls (when
  already mounted): make the container visible.

  Per rf2-in6l2 the `:rf/causa` registration is lazy here (post-
  `rf/init!`) rather than at preload time; see `ensure-causa-frame!`
  for the rationale.

  Returns the mount-state map for tests / introspection."
  []
  (if-let [state @mount-state]
    (do (set-visible! (:node state) true)
        (swap! mount-state assoc :visible? true)
        @mount-state)
    (when (substrate-adapter/current-adapter)
      (ensure-causa-frame!)
      (let [node    (create-mount-node!)
            unmount (substrate-adapter/render [shell/shell-view] node nil)]
        (set-mode-attrs! node :overlay)
        (reset! mount-state
                {:node     node
                 :unmount  unmount
                 :visible? true
                 :mode     :overlay})
        @mount-state))))

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
  (when-let [state (open!)]
    (set-mode-attrs! (:node state) :docked)
    (set-body-docked! true)
    (swap! mount-state assoc :mode :docked :visible? true)
    @mount-state))

(defn undock!
  "Return the overlay shell to its default non-layout-affecting mode."
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

(defn teardown!
  "Tear the shell down completely — unmount from React, remove the DOM
  node, clear the singleton. Intended for tests; production sessions
  keep the shell across the page's lifetime."
  []
  (when-let [{:keys [node unmount]} @mount-state]
    (when unmount
      (try (unmount) (catch :default _ nil)))
    (when (and node (.-parentNode node))
      (.removeChild (.-parentNode node) node))
    (set-body-docked! false)
    (reset! mount-state nil))
  nil)

(defn popout!
  "Open a same-origin Causa pop-out window and render the shell into it.
  The pop-out shares the opener runtime and Causa frame; no
  serialisation layer is introduced. Returns a state map, or
  `{:ok? false :reason :popup-blocked}` when `window.open` fails."
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
              (let [unmount (substrate-adapter/render [shell/shell-view] node nil)
                    state   {:ok? true :window win :node node :unmount unmount :mode :popout}]
                (reset! popout-state state)
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

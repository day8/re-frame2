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
  ;;    :visible?  <boolean>}
  ;; Nil before first mount; never re-allocated across reloads thanks
  ;; to defonce.
  (atom nil))

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
    ;; The shell uses position: fixed for its own outer element, so the
    ;; root <div> only needs to be a no-layout-impact host. Leave its
    ;; default block-level styling intact; the shell handles its own
    ;; geometry.
    (.appendChild (.-body js/document) node)
    node))

(defn- set-visible! [node visible?]
  (when (some? node)
    (set! (-> node .-style .-display)
          (if visible? "block" "none"))))

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
        (reset! mount-state
                {:node     node
                 :unmount  unmount
                 :visible? true})
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
    (reset! mount-state nil))
  nil)

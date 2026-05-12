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
  gate is sufficient."
  (:require [re-frame.substrate.adapter :as substrate-adapter]
            [day8.re-frame2-causa.shell :as shell]))

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

(defn open!
  "Mount + show the Causa shell. On first call: create the root <div>,
  render the shell into it via the installed substrate adapter, mark
  visible. On subsequent calls (when already mounted): make the
  container visible.

  Returns the mount-state map for tests / introspection."
  []
  (if-let [state @mount-state]
    (do (set-visible! (:node state) true)
        (swap! mount-state assoc :visible? true)
        @mount-state)
    (when (substrate-adapter/current-adapter)
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

(ns re-frame.story.canonical
  "Canonical-vocabulary boot — the orchestrated installer chain that
  wires Story's seven canonical tags, runtime helpers, lifecycle
  machine, `:rf.assert/*` event handlers, the built-in
  `:rf.story/force-fx-stub` decorator, layout-debug decorator trio,
  toolbar cofx + subs, and (CLJS only) the v1.0 SOTA panel set plus
  the multi-substrate Reagent default.

  The public entry `install-canonical-vocabulary!` is re-exported from
  `re-frame.story`; users call `(re-frame.story/install-canonical-vocabulary!)`
  at boot, OR rely on the auto-install hook — the first `reg-*` call
  installs the canonical vocabulary on demand. See `ensure-installed!`
  below + spec/001 §Boot — auto-install of the canonical vocabulary.
  The implementation weight — the late-bind shim wiring, the ordered
  installer vector, and the auto-install gate — lives here.

  Per /spec/007-Stories.md §Inclusion tags + `001-Authoring.md` §Registration macros / `002-Runtime.md` §Four-phase lifecycle with `:loaders-complete-when` the canonical
  vocabulary is registered by the Story library at load time; project
  code does not have to register it. Project-specific tags must
  register via `reg-tag` *before* use; an unregistered tag on a
  variant's `:tags` set raises `:rf.error/unknown-tag`."
  (:require [re-frame.story.assertions   :as assertions]
            [re-frame.story.frames       :as frames]
            [re-frame.story.fx-stubs     :as fx-stubs]
            [re-frame.story.late-bind    :as late-bind]
            [re-frame.story.layout-debug :as layout-debug]
            [re-frame.story.loaders      :as loaders]
            [re-frame.story.play         :as play]
            [re-frame.story.play.runner-events :as runner-events]
            [re-frame.story.registrar    :as registrar]
            [re-frame.story.render       :as render]
            [re-frame.story.runtime      :as runtime]
            [re-frame.story.save-variant :as save-variant]
            [re-frame.story.ui.cofx      :as ui-cofx]
            #?(:cljs [re-frame.story.ui.panels          :as ui-panels])
            #?(:cljs [re-frame.story.ui.multi-substrate :as ui-multi-substrate])
            #?(:cljs [re-frame.story.sub-overrides       :as sub-overrides])))

(defn- install-late-bind-shims!
  "Wire the late-bound shims so the frames runtime can tap into the
  assertion + play modules without a circular require. The hub lives in
  `re-frame.story.late-bind` (mirroring the framework's pattern)."
  []
  ;; `:rf.assert/effect-emitted` projects from the epoch tape,
  ;; but a STUBBED fx lands on the tape under its REWRITTEN stub id, not its
  ;; original id. The authoritative record of which ORIGINAL fx-ids a
  ;; `force-fx-stub` redirected is the stub-call log `fx-stubs` owns; the
  ;; assertions module reads it via this hook (it cannot `:require`
  ;; fx-stubs / frames without a cycle).
  (late-bind/set-fn! :stub-observed-fx-ids fx-stubs/observed-fx-ids)
  ;; Only the play module's per-frame `pending-exceptions` slot needs
  ;; frame-teardown eviction.
  ;;
  ;; Per-frame destroy must ALSO unregister the play-runner's per-frame
  ;; trace listener (`play/install-trace-listener!` registered it
  ;; in `runtime/run-phase-0!`). Dropping only the `pending-exceptions` entry
  ;; would leave the listener registered against a destroyed frame:
  ;; `clear-all-play-state!` keys off `pending-exceptions` / `stepper-state`
  ;; (both empty for a torn-down frame), so without this eviction long-running
  ;; sessions / hot-reload cycles / large corpora would accumulate stale
  ;; listener closures inspecting every future trace event.
  ;; `remove-trace-listener!` is idempotent, so destroying a frame that never
  ;; installed a listener (or a double-destroy) is harmless.
  (late-bind/set-fn! :drop-assertion-accumulators
    (fn [frame-id]
      (play/drop-pending-exceptions! frame-id)
      (play/remove-trace-listener! frame-id)))
  ;; The play-runner's per-frame run-state (`run-state` / `runs-by-play` /
  ;; `active-play` / `step-boundaries`) is evicted on frame teardown via
  ;; `clear-state!`: without it a destroyed variant frame would leak its
  ;; terminal play status + the toolbar's focused-play slot, and a
  ;; re-allocated frame of the same id could observe the prior incarnation's
  ;; run-state. `frames` cannot `:require` `runner-events` (cycle), so the
  ;; eviction routes through this late-bind hook the same way the
  ;; pending-exceptions eviction does.
  (late-bind/set-fn! :drop-run-state
    (fn [frame-id]
      (runner-events/clear-state! frame-id))))

#?(:cljs
   (defn- render-host-scope
     "A Reagent component that renders the active `:view` under the host
     substrate — wrapped in the variant's `:hiccup` decorators — inside the
     override-context Provider carrying the resolved `:sub-overrides` (the
     same carriage the canvas's `sub-overrides-scope` uses); the override
     never touches app-db / `compute-sub`.

     The host paints through the SHARED
     `ui-multi-substrate/render-decorated-view` seam the canvas single-pane
     path uses, threading the compiled plan's `[:world :decorators]` refs
     (`render-inputs`' `:decorators`, the single-merge-authority output —
     spec/017 §305-306). `render-variant` and the live shell paint the
     SAME decorated tree (theme / provider / chrome included), so a
     decorated variant never diverges from the canvas.

     The `:sub-overrides` carriage is a React CONTEXT, not a
     dynamic var — the var does not survive into the view's deferred React
     render (the view's `@(rf/subscribe)` runs in its own reaction). A
     descendant subscribe reads the override at deref time via the
     `:subs/resolve-sub-override` core hook (consulted dev-only inside
     `subscribe`'s `interop/debug-enabled?` gate). See
     `re-frame.story.sub-overrides` ns docstring §STATUS."
     [{:keys [view frame effective-args sub-overrides decorators]}]
     (sub-overrides/override-provider sub-overrides
       (ui-multi-substrate/render-decorated-view
         :reagent frame view effective-args decorators))))

#?(:cljs
   (defn- install-render-host!
     "Wire the `render-variant` host-render hook. The
     render-prep core (`re-frame.story.render/prepare-render`) is host-free
     + JVM-testable; the actual painting of the active view is this CLJS
     hook. It renders the active `:view` under the host substrate's render
     fn, wrapped in the variant's `:hiccup` decorators via the SHARED
     `re-frame.story.ui.multi-substrate/render-decorated-view` seam the
     canvas single-pane path also uses, inside the
     `render-host-scope` component so the resolved `:sub-overrides` surface
     at React render time via the override-context carriage
     (spec/017 §View-state subscription overrides). The
     result is a hiccup tree (the Reagent default) — the SAME decorated
     render the canvas paints, so render-variant and the live shell agree.

     The bare JVM installs NO render host, so `render-variant` returns
     `:cannot-run` there rather than a silent empty render."
     []
     (render/install-render-host!
       (fn [render-inputs]
         [render-host-scope render-inputs]))))

(def ^:private canonical-installers
  "Ordered vector of installer fns invoked by `install!`. Each takes
  zero args and is idempotent. The CLJS-only SOTA-feature surfaces
  (multi-substrate Reagent default + the v1.0 panel set) gate on the
  reader so the JVM classpath stays Reagent-free."
  [registrar/install-canonical-tags!
   loaders/install!
   loaders/install-mirror-writer!
   frames/install-canonical-frame-events!
   runtime/install-canonical-runtime-events!
   assertions/install-canonical-assertions!
   fx-stubs/install-canonical-fx-stubs!
   save-variant/install-canonical-event-handlers!
   install-late-bind-shims!
   layout-debug/install-canonical-layout-debug!
   ui-cofx/install-canonical-cofx!
   #?@(:cljs [ui-multi-substrate/install-reagent-substrate!
              install-render-host!
              ui-panels/install-canonical-panels!])])

;; ---- auto-install gate --------------------------------------------------
;;
;; Per spec/001 §Boot — auto-install of the canonical vocabulary, the
;; canonical vocabulary auto-installs on first `reg-*` call so authors
;; don't have to remember the explicit boot step. The gate is a single
;; per-process boolean atom; flipping it true happens BEFORE running
;; the installer chain so the registrar writes triggered inside (e.g.
;; `install-canonical-tags!` calling `reg-tag*`) hit the early-return
;; branch of `ensure-installed!` and don't recurse.
;;
;; Test fixtures (e.g. `story/clear-all!`) reset the flag so a fresh
;; `(reg-story ...)` after `clear-all!` re-installs cleanly.

(defonce
  ^{:doc "Per-process boolean atom — true iff the canonical vocabulary
         has been installed in the current Story registrar generation.
         Reset by `story/clear-all!` so test fixtures get a clean
         slate; flipped true by `install!` (idempotent)."}
  installed?
  (atom false))

(declare install!)

(defn ensure-installed!
  "Install the canonical vocabulary if it isn't already installed in
  the current registrar generation. Idempotent and cheap on the hot
  path — the common case is a single `deref` against `installed?`.

  Called from the registrar's `reg-*!` runtime helpers (via the
  `:ensure-canonical-installed` late-bind hook) so authors don't have
  to call `install-canonical-vocabulary!` explicitly. See spec/001
  §Boot — auto-install of the canonical vocabulary."
  []
  (when-not @installed?
    (install!)))

(defn reset-installed-flag!
  "Reset the auto-install gate. Used by `story/clear-all!` so test
  fixtures that wipe the side-table also wipe the gate — the next
  `reg-*` call after `clear-all!` re-installs the canonical
  vocabulary on demand."
  []
  (reset! installed? false)
  nil)

(defn install!
  "Install the canonical Story tags, runtime helpers, lifecycle machine,
  `:rf.assert/*` assertion handlers, built-in `:rf.story/force-fx-stub`
  decorator, layout-debug decorator trio, toolbar cofx + subs, and the
  v1.0 SOTA panel set (CLJS only). Idempotent.

  Re-exported from the public facade as
  `re-frame.story/install-canonical-vocabulary!`. Authors may call this
  explicitly at boot — or rely on the auto-install hook,
  which fires the same chain on the first `reg-*` call. Either path
  is idempotent.

  Flips the `installed?` gate true BEFORE running the installer chain
  so the registrar writes triggered inside (e.g.
  `install-canonical-tags!` calling `reg-tag*`) hit the early-return
  branch of `ensure-installed!` and don't recurse."
  []
  (reset! installed? true)
  (doseq [installer canonical-installers]
    (installer)))

;; Register the auto-install hook at canonical-ns load time. The
;; registrar consults this hook from each `reg-*!` runtime helper —
;; see `re-frame.story.registrar/maybe-auto-install!`. Late-bound to
;; avoid a circular require (registrar → canonical → registrar).
(late-bind/set-fn! :ensure-canonical-installed ensure-installed!)

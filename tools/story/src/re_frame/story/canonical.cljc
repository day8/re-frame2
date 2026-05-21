(ns re-frame.story.canonical
  "Canonical-vocabulary boot — the orchestrated installer chain that
  wires Story's seven canonical tags, runtime helpers, lifecycle
  machine, `:rf.assert/*` event handlers, the built-in
  `:rf.story/force-fx-stub` decorator, layout-debug decorator trio,
  toolbar cofx + subs, and (CLJS only) the v1.0 SOTA panel set plus
  the multi-substrate Reagent default.

  Per the rf2-l8eso Phase-2 facade thinning: the public entry
  `install-canonical-vocabulary!` is re-exported from `re-frame.story`;
  users call `(re-frame.story/install-canonical-vocabulary!)` at boot,
  OR rely on the rf2-p1ydc auto-install hook — the first `reg-*` call
  installs the canonical vocabulary on demand. See `ensure-installed!`
  below + spec/001 §Boot — auto-install of the canonical vocabulary.
  The implementation weight — the late-bind shim wiring, the ordered
  installer vector, and the auto-install gate — lives here.

  Per spec/007 §Inclusion tags + IMPL-SPEC §3.1 / §5.4 the canonical
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
            [re-frame.story.registrar    :as registrar]
            [re-frame.story.runtime      :as runtime]
            [re-frame.story.save-variant :as save-variant]
            [re-frame.story.ui.cofx      :as ui-cofx]
            #?(:cljs [re-frame.story.ui.panels          :as ui-panels])
            #?(:cljs [re-frame.story.ui.multi-substrate :as ui-multi-substrate])))

(defn- install-late-bind-shims!
  "Wire the late-bound shims so the frames runtime can tap into the
  assertion + play modules without a circular require. The hub lives in
  `re-frame.story.late-bind` (mirroring the framework's pattern)."
  []
  (late-bind/set-fn! :tap-stub-event fx-stubs/tap-stub-event!)
  (late-bind/set-fn! :drop-assertion-accumulators
    (fn [frame-id]
      (assertions/drop-trace-accumulators! frame-id)
      (play/drop-pending-exceptions! frame-id))))

(def ^:private canonical-installers
  "Ordered vector of installer fns invoked by `install!`. Each takes
  zero args and is idempotent. The CLJS-only SOTA-feature surfaces
  (multi-substrate Reagent default + the v1.0 panel set) gate on the
  reader so the JVM classpath stays Reagent-free."
  [registrar/install-canonical-tags!
   loaders/install!
   loaders/install-mirror-writer!
   frames/install-helpers!
   runtime/install-helpers!
   assertions/install-canonical-assertions!
   fx-stubs/install-canonical-fx-stubs!
   save-variant/install-canonical-event-handlers!
   install-late-bind-shims!
   layout-debug/install-canonical-layout-debug!
   ui-cofx/install-canonical-cofx!
   #?@(:cljs [ui-multi-substrate/install-reagent-substrate!
              ui-panels/install-canonical-panels!])])

;; ---- auto-install gate (rf2-p1ydc) ---------------------------------------
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
  explicitly at boot — or rely on the rf2-p1ydc auto-install hook,
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

(ns ^{:dev/always true} re-frame.ui.digest-carrier
  "The dev-only client carrier for the compiler-owned whole-build digest, and
  the runtime half of the activation transaction (rf2-vxgfnd.193).

  The Shadow build hook replaces the one fixed-width sentinel in this
  namespace's compiled `[:output resource-id :js]` at `:compile-finish`, then
  the candidate snapshot is carried in the returned functional build-state.
  Runtime readers are O(1): they read this single slot and never walk the
  registrar. Direct no-pass REPL evaluation never mutates this slot.

  Activation is staged, not published on carrier evaluation. Carrier evaluation
  is `^:dev/always`, so it re-runs first on every hot reload. Publishing the
  new identity there would advertise a digest before the reload's application
  sources have evaluated: a later loaded source that throws at top-level (before
  installing its view registration) stops Shadow's reload and skips the
  after-load hooks, leaving the runtime stale/partial while descriptors already
  read the new digest. Instead:

  - `before-load` fences the runtime (`updating` true) so reads become
    fail-closed for the duration of the swap;
  - carrier evaluation STAGES the generation's compiled digest;
  - `after-load` promotes the staged digest to the published slot — and Shadow
    runs after-load hooks only once EVERY reloaded source has evaluated without
    throwing.

  A top-level throw in any reloaded source therefore skips promotion: reads stay
  fail-closed (nil) until a later successful reload's after-load promotes a
  fully-activated generation. Reads never claim the old or the new identity for
  a partially mutated runtime. The initial page load runs no before-load fence,
  so that first, whole load publishes immediately.

  Every operation is goog.DEBUG-gated. Closure removes the slot, sentinel,
  staging cell, reload hooks, validation and accessors from advanced production
  output."
  (:require [clojure.string :as str]))

;; The runtime activation cell. It PERSISTS across `^:dev/always` reloads
;; (`defonce` re-init is skipped once the slot exists), so the `updating` fence
;; a hot reload's `before-load` sets survives this namespace re-evaluating.
;;   :published — the digest reads observe; nil while fail-closed;
;;   :staged    — the candidate digest of the generation currently evaluating;
;;   :updating  — the fence: true between a hot reload's before-load and a
;;                SUCCESSFUL after-load (a reloaded-source throw leaves it true).
(defonce ^:private cell
  (when ^boolean js/goog.DEBUG
    #js {:published nil :updating false :staged nil}))

;; Exactly 20 bytes, matching a bd1- + 16-hex-digit digest. Keep this literal
;; unique and in ONE source location: the hook requires exactly one occurrence
;; in exactly one compiled carrier output, preserving source-map offsets by
;; replacing it with an equal-length digest.
(def ^:private compiled-digest
  (when ^boolean js/goog.DEBUG "__RF2_UI_DIGEST_XX__"))

;; (Re)evaluation stages this generation's compiled digest. On the initial page
;; load no before-load fence has run, so the runtime is not `updating` and this
;; whole, first load publishes immediately. During a hot reload before-load has
;; set `updating`, so this only STAGES: after-load promotes it iff every
;; reloaded source evaluated successfully.
(when ^boolean js/goog.DEBUG
  (set! (.-staged cell) compiled-digest)
  (when-not (.-updating cell)
    (set! (.-published cell) compiled-digest)))

(defn- finalized-digest?
  "True only for a FINALIZED `bd1-` whole-build digest — never the unpatched
  fixed-width sentinel a hookless or mis-hooked build leaves in the slot.
  goog.DEBUG-gated so Closure removes this and its callers' guards from advanced
  production."
  [d]
  (and ^boolean js/goog.DEBUG
       (string? d)
       (str/starts-with? d "bd1-")))

(defn current
  "Return the compiler-published, fully-activated whole-build digest in dev, nil
  in production. O(1); no registry traversal or client-side digest computation.
  Fail-closed (nil) while a hot reload is mid-flight, after a reloaded source
  threw before its after-load promotion — and, crucially, whenever the published
  slot is NOT a finalized `bd1-` digest. The namespace-load check below is only
  an early diagnostic; under Shadow `:loader-mode :script` a top-level throw is
  reported as a `pageerror` and later scripts still install these accessors, so
  the raw sentinel a hookless build leaves in the slot must be rejected at EVERY
  read boundary here — never returned as a false build identity, and therefore
  never stampable into a complete Root Descriptor (rf2-vxgfnd.205)."
  []
  (when ^boolean js/goog.DEBUG
    (when-not (.-updating cell)
      (let [d (.-published cell)]
        (when (finalized-digest? d) d)))))

(defn ^:dev/before-load before-load
  "Fence the runtime before Shadow swaps reloaded code: digest/descriptor reads
  become fail-closed until a successful after-load promotes the staged
  generation."
  []
  (when ^boolean js/goog.DEBUG
    (set! (.-updating cell) true)))

(defn ^:dev/after-load after-load
  "Promote the staged generation once EVERY reloaded source has evaluated. A
  top-level throw in any reloaded source skips this hook (Shadow stops the
  reload before after-load), leaving reads fail-closed until a later successful
  reload."
  []
  (when ^boolean js/goog.DEBUG
    (set! (.-published cell) (.-staged cell))
    (set! (.-updating cell) false)))

;; A configured build that omitted the load-bearing hook must not limp along
;; with a plausible but false identity. The hook patches the sentinel before
;; this namespace executes. Prefix validation avoids a second copy of the
;; sentinel literal (the patch target must be unique).
(when ^boolean js/goog.DEBUG
  (when-not (str/starts-with? compiled-digest "bd1-")
    (throw
     (js/Error.
      (str "re-frame.ui build digest was not finalized. Configure "
           "(re-frame.ui.compiler.build-hook/hook) in Shadow :build-hooks "
           "and keep re-frame.ui in :cache-blockers.")))))

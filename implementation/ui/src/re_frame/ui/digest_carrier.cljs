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
  - carrier evaluation STAGES the generation's compiled digest (`stage!`),
    minting the next monotone activation GENERATION;
  - `after-load` promotes the staged digest to the published slot — and Shadow
    runs after-load hooks only once EVERY reloaded source has evaluated without
    throwing.

  A top-level throw in any reloaded source therefore skips promotion: reads stay
  fail-closed (nil) until a later successful reload's after-load promotes a
  fully-activated generation. Reads never claim the old or the new identity for
  a partially mutated runtime. The initial page load runs no before-load fence,
  so that first, whole load publishes immediately.

  Overlapping async activations (rf2-vxgfnd.243). Under Shadow
  `:loader-mode :script` a reload's sources load asynchronously, so a second
  reload's `before-load`/`stage!` can interleave BETWEEN a first reload's
  `before-load` and its `after-load` — CLJS is single-threaded, so this is
  interleaved reload continuations, not threads. Without an ordering rule a
  STALE after-load (an older reload finishing after a newer one already
  published) would regress the published digest and/or clear the newer reload's
  fence. The rule is a monotone `:generation` minted at each `stage!` plus an
  `:activated` high-water mark: `after-load` promotes and releases the fence
  ONLY when `generation > activated` — i.e. only for a NOT-yet-activated
  staging. A stale after-load observes `generation == activated` (a newer
  staging already advanced the high-water mark) and is INERT: it never
  regresses `:published` and never clears a newer generation's `:updating`
  fence. The high-water rule also keeps forward recovery intact — a throwing
  reload's skipped after-load merely leaves `activated` lagging, and the next
  successful reload's after-load jumps the mark forward and publishes. (Under
  the default `:loader-mode :eval` a reload's before-load/stage!/after-load run
  synchronously to completion, so activations never overlap; the generation
  rule is the correctness guarantee for the `:script` interleaving.)

  Every operation is goog.DEBUG-gated. Closure removes the slot, sentinel,
  staging cell, reload hooks, validation and accessors from advanced production
  output."
  (:require [clojure.string :as str]))

;; The runtime activation cell. It PERSISTS across `^:dev/always` reloads
;; (`defonce` re-init is skipped once the slot exists), so the `updating` fence
;; a hot reload's `before-load` sets — and the `generation`/`activated`
;; activation ordinals — survive this namespace re-evaluating.
;;   :published  — the digest reads observe; nil while fail-closed;
;;   :staged     — the candidate digest of the generation most recently staged;
;;   :updating   — the fence: true between a hot reload's before-load and a
;;                 SUCCESSFUL after-load (a reloaded-source throw leaves it true);
;;   :generation — monotone activation ordinal, bumped by each `stage!`; the
;;                 generation of the currently-staged digest;
;;   :activated  — high-water mark: the greatest generation an after-load has
;;                 promoted+released. `after-load` acts only when
;;                 `generation > activated`, so a stale (overlapping) after-load
;;                 is inert;
;;   :reloaded   — true once ANY hot reload has begun (before-load ran). Keeps
;;                 the initial-load auto-publish from misfiring on a later
;;                 staging whose fence a stale after-load happened to clear.
(defonce ^:private cell
  (when ^boolean js/goog.DEBUG
    #js {:published nil :updating false :staged nil
         :generation 0 :activated 0 :reloaded false}))

;; Exactly 20 bytes, matching a bd1- + 16-hex-digit digest. Keep this literal
;; unique and in ONE source location: the hook requires exactly one occurrence
;; in exactly one compiled carrier output, preserving source-map offsets by
;; replacing it with an equal-length digest.
(def ^:private compiled-digest
  (when ^boolean js/goog.DEBUG "__RF2_UI_DIGEST_XX__"))

(defn stage!
  "Stage `digest` as the next monotone activation GENERATION. Called by carrier
  (re)evaluation and by the activation-ordering fixture. On the genuine initial
  page load — no before-load fence has run and no reload has ever begun — the
  runtime is not `updating`, so this whole first load PUBLISHES immediately and
  seeds the activation high-water mark. During (or overlapping) a hot reload the
  runtime is fenced (or has already been reloaded), so this only STAGES and
  bumps `:generation`; a subsequent `after-load` promotes it iff its generation
  is still ahead of `:activated`."
  [digest]
  (when ^boolean js/goog.DEBUG
    (set! (.-generation cell) (inc (.-generation cell)))
    (set! (.-staged cell) digest)
    (when (and (not (.-updating cell)) (not (.-reloaded cell)))
      (set! (.-published cell) digest)
      (set! (.-activated cell) (.-generation cell)))))

;; (Re)evaluation stages this generation's compiled digest.
(when ^boolean js/goog.DEBUG
  (stage! compiled-digest))

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
  generation. Records that a reload has begun so a later staging cannot be
  mistaken for the initial whole-page load."
  []
  (when ^boolean js/goog.DEBUG
    (set! (.-updating cell) true)
    (set! (.-reloaded cell) true)))

(defn ^:dev/after-load after-load
  "Promote the staged generation once EVERY reloaded source has evaluated. A
  top-level throw in any reloaded source skips this hook (Shadow stops the
  reload before after-load), leaving reads fail-closed until a later successful
  reload.

  Overlapping-activation ordering (rf2-vxgfnd.243): promote + release the fence
  ONLY when `generation > activated` — i.e. only for a staging that has not yet
  been activated by a newer transaction. A STALE after-load from an earlier,
  overlapping reload observes `generation == activated` (a newer `stage!`
  already advanced both the staged digest and the high-water mark) and is
  therefore INERT: it neither regresses `:published` nor clears a newer
  reload's fence. Advancing `:activated` to the current generation is also what
  preserves forward recovery after a throwing reload skipped its after-load."
  []
  (when ^boolean js/goog.DEBUG
    (when (> (.-generation cell) (.-activated cell))
      (set! (.-published cell) (.-staged cell))
      (set! (.-activated cell) (.-generation cell))
      (set! (.-updating cell) false))))

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

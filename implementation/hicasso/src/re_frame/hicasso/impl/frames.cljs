(ns re-frame.hicasso.impl.frames
  "FRAME-LOCKED OPS — resolved once per frame INCARNATION, and not once per
  boundary.

  One memo table keyed by frame keyword, one ROW per frame, and the one door
  that empties it. HD-020(a) has each boundary read the frame *once* from the
  substrate's single internal context; it does not ask each boundary to
  rebuild the bundle, and `capture-frame` pins a frame incarnation and is not
  free.

  ## Why this is an ownership boundary and not a filing convenience

  A frame's public keyword id names an ADDRESS, not an object. Destroy a frame
  and create another under the same id and you have a NEW incarnation,
  distinct by identity, and everything a render minted against the predecessor
  — the `capture-frame` bundle, the ambient dispatch closure lowered into
  every callback — belongs to the predecessor for the rest of its life.

  **A bundle is pinned when the row is MINTED, never when a callback
  FIRES.** That is the load-bearing rule, because resolving at fire time
  makes the destination depend on the memo's warmth at that instant and
  both answers are wrong:

    warm — the stale bundle refuses the LIVE successor, so a control the
           successor has just rendered is dead. Perfect markup above dead
           controls.
    cold — `capture-frame` runs at fire time and so pins the SUCCESSOR: a
           predecessor-era callback silently writes the successor's app-db,
           with no error emitted at all. Cold is the ORDINARY case — a
           boundary that rendered but that nobody clicked before the teardown.

  `frame-row` closes both with ONE mechanism. The row carries the
  incarnation it was minted under; a lookup compares that against the
  incarnation live *right now* and a mismatch replaces the row. So a
  retained closure keeps the predecessor's bundle and core's own
  `capture-frame` fence refuses it (recover-but-emit
  `:rf.error/frame-destroyed`), while the next render under the successor gets
  a row pinned to the successor and routes. Safety of retained callbacks and
  liveness of fresh ones are the same fact seen twice.

  **Eviction on destruction is NOT the safety mechanism**, and must not be
  made one: if the row's disappearance is what keeps a retained callback
  honest, the one accidentally-correct branch above becomes the broken one
  and the silent successor-write becomes universal. Correctness here
  depends on no destruction hook at all — the replacement is LAZY, driven
  by the next lookup, and that is the whole safety argument.

  ## What the lazy replacement is not

  It is not a RETENTION bound. The successor's first lookup is the ONLY
  eviction, so an id that never gets a successor would keep its row for the
  life of the process — and `re-frame.hicasso.server/render` mints exactly
  such an id per request (`fresh-frame-id`'s `gensym`), one row per request
  served, each holding that request's `capture-frame` bundle, in a process
  built to be long-lived.

  `forget-frame-ops!` is therefore ALSO wired to frame destruction,
  through core's `:hicasso/on-frame-destroyed!` late-bind hook — the same
  shape every other artefact releases its frame-keyed bookkeeping through
  in `destroy-frame!`'s step 7. It changes nothing above: a row a destroyed
  incarnation leaves behind is already unreachable by every branch of
  `frame-row`, so dropping it earlier is hygiene and never safety.

  ## What lives here, and the one thing that does not

  The row's `:dispatch` closure is minted by
  `re-frame.hicasso.impl.collector/frame-row` and handed in, because it is a
  partial application of the collector's commit door (`with-commit` over the
  captured bundle's `:dispatch-sync`), and a namespace cycle is the
  alternative. It is ONE row rather than two tables so that the bundle and the
  closure that calls it can never describe different incarnations: the
  coupling is structural, not a rule somebody has to keep."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]))

;; frame-kw -> {:incarnation <token or nil>   the exact incarnation this row
;;                                            was minted under
;;              :ops         <bundle>         `rf/capture-frame`'s
;;                                            {:frame :dispatch :dispatch-sync
;;                                             :subscribe}, pinned to it
;;              :dispatch    <closure>        the runtime's ambient dispatch
;;                                            over THAT bundle}
;;
;; The residue ledger counts this table under the `:frame-ops` token, priced as
;; "one capture-frame bundle and one ambient dispatch per frame" — one row is
;; what that sentence describes.
(defonce !frame-ops (atom {}))

(defn- mint-row
  "A fresh row for `frame-kw` pinned to `incarnation`.

  `rf/capture-frame` is called HERE — while the caller has just established
  that the incarnation is live — and never again for this row. That is the
  pin: everything downstream holds this one bundle."
  [frame-kw incarnation mint-dispatch]
  (let [ops (rf/capture-frame frame-kw)]
    {:incarnation incarnation
     :ops         ops
     :dispatch    (mint-dispatch ops)}))

(defn frame-row
  "The memo row for `frame-kw`, pinned to the incarnation live RIGHT NOW.
  `mint-dispatch` is called with the freshly captured bundle when a row is
  minted, and its result becomes the row's `:dispatch`.

  Three cases, and the middle one is the whole hot path:

    absent   — no live frame under the id, so `capture-frame` pins nothing and
               the bundle stays address-directed (the documented dynamic-id
               semantics). Deliberately NOT memoised: a row whose incarnation
               is nil would hit forever, and this id is precisely the one that
               may be about to name a real frame.
    hit      — the row's incarnation IS the live one. Returned as-is, so
               repeated renders of one incarnation share one bundle and one
               handler identity and allocate nothing.
    replaced — the id now names a different incarnation. The row is rebuilt
               and overwritten in place, which is why no destruction hook is
               needed FOR SAFETY: the successor's first lookup does the
               eviction. One is wired for RETENTION (see the ns docstring),
               because an id with no successor never reaches this branch.

  Nothing here re-checks liveness at FIRE time and nothing here is a fence.
  The fence is core's, inside the captured bundle (`capture-frame` pins the
  incarnation and recover-but-emits `:rf.error/frame-destroyed` once it is
  superseded); this table's only job is to hand out a bundle that was captured
  while the frame it names was alive."
  [frame-kw mint-dispatch]
  (let [incarnation (rf.frame/frame-incarnation-token frame-kw)
        row         (get @!frame-ops frame-kw)]
    (cond
      (nil? incarnation)
      (mint-row frame-kw nil mint-dispatch)

      (identical? incarnation (:incarnation row))
      row

      :else
      (let [fresh (mint-row frame-kw incarnation mint-dispatch)]
        (swap! !frame-ops assoc frame-kw fresh)
        fresh))))

(defn forget-frame-ops!
  "Drop the memoised rows — RESET, HYGIENE AND RETENTION.

  It is not what makes a reincarnation safe: `frame-row` replaces a row
  whose incarnation has been superseded whether or not anyone ever calls
  this, and making eviction the safety mechanism would make the fault the ns
  docstring names universal rather than curing it. The two runtime callers
  are the whole-runtime reset (0-arity) and frame destruction through
  `on-frame-destroyed!` (1-arity); the rest are test fixtures."
  ([] (reset! !frame-ops {}) nil)
  ([frame-kw] (swap! !frame-ops dissoc frame-kw) nil))

(defn- on-frame-destroyed!
  "Core's step-7 destroy hook — drop the destroyed frame's row.

  UNCONDITIONAL by key, and it needs no incarnation token to be safe about
  it: a same-id successor is constructable
  only AFTER `dissoc-frame!` (step 10) and this fires at step 7, so whatever
  row stands under `frame-kw` here belongs to an incarnation that is already
  dead — the dying one, or an earlier one whose successor never looked up.

  A 1-arity fn rather than `forget-frame-ops!` itself, so the hook's arity
  is the contract rather than an accident of that door having two."
  [frame-kw]
  (forget-frame-ops! frame-kw))

(rf.late-bind/set-fn! :hicasso/on-frame-destroyed! on-frame-destroyed!)

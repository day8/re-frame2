(ns re-frame.hicasso.impl.frames
  "FRAME-LOCKED OPS — resolved once per frame INCARNATION, and not once per
  boundary (rf2-hic-009, rf2-x874).

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

  Until rf2-x874 nothing in this file could tell that its incarnation had
  died, and the ambient closure closed over the frame KEYWORD and resolved its
  bundle when it FIRED rather than when it was minted. Where a delayed
  callback landed was therefore decided by the memo's warmth at that instant,
  and both answers were wrong (measured, rf2-hic-013 / PR #7749):

    warm — the stale bundle refused the LIVE successor, so a control the
           successor had just rendered was dead. Perfect markup above dead
           controls.
    cold — `capture-frame` ran at fire time and so pinned the SUCCESSOR: a
           predecessor-era callback silently wrote the successor's app-db,
           with no error emitted at all. Cold is the ORDINARY case — a
           boundary that rendered but that nobody clicked before the teardown.

  [[frame-row]] is the repair, and it is ONE repair rather than two. The row
  carries the incarnation it was minted under; a lookup compares that against
  the incarnation live *right now* and a mismatch replaces the row. So a
  retained closure keeps the predecessor's bundle and core's own
  `capture-frame` fence refuses it (recover-but-emit
  `:rf.error/frame-destroyed`), while the next render under the successor gets
  a row pinned to the successor and routes. Safety of retained callbacks and
  liveness of fresh ones are the same fact seen twice.

  **Eviction on destruction was measured and REJECTED** (PR #7749's NC6): it
  converts the one accidentally-correct branch into the broken one and makes
  the silent successor-write universal. Correctness here therefore does not
  depend on a destruction hook at all — the replacement is LAZY, driven by the
  next lookup. [[forget-frame-ops!]] is reset and hygiene only, and its sole
  runtime caller is the whole-runtime reset.

  ## What lives here, and the one thing that does not

  The row's `:dispatch` closure is minted by
  `re-frame.hicasso.impl.collector/frame-row` and handed in, because it is a
  partial application of the collector's commit door (`with-commit` over the
  captured bundle's `:dispatch-sync`), and a namespace cycle is the
  alternative. It is ONE row rather than two tables so that the bundle and the
  closure that calls it can never describe different incarnations: the
  coupling is structural, not a rule somebody has to keep."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]))

;; frame-kw -> {:incarnation <token or nil>   the exact incarnation this row
;;                                            was minted under
;;              :ops         <bundle>         `rf/capture-frame`'s
;;                                            {:frame :dispatch :dispatch-sync
;;                                             :subscribe}, pinned to it
;;              :dispatch    <closure>        the arm's ambient dispatch over
;;                                            THAT bundle}
;;
;; The residue ledger counts this table under the `:frame-ops` token, which has
;; always been priced as "one capture-frame bundle and one ambient dispatch per
;; frame" — one row is what that sentence describes.
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
               needed: the successor's first lookup does the eviction.

  Nothing here re-checks liveness at FIRE time and nothing here is a fence.
  The fence is core's, inside the captured bundle (`capture-frame` pins the
  incarnation and recover-but-emits `:rf.error/frame-destroyed` once it is
  superseded); this table's only job is to hand out a bundle that was captured
  while the frame it names was alive."
  [frame-kw mint-dispatch]
  (let [incarnation (frame/frame-incarnation-token frame-kw)
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
  "Drop the memoised rows — RESET AND HYGIENE ONLY.

  It is not what makes a reincarnation safe: [[frame-row]] replaces a row
  whose incarnation has been superseded whether or not anyone ever calls this,
  and eviction on destruction was measured to make the fault universal rather
  than to cure it. The sole runtime caller is the whole-runtime reset; the
  rest are test fixtures."
  ([] (reset! !frame-ops {}) nil)
  ([frame-kw] (swap! !frame-ops dissoc frame-kw) nil))

(ns re-frame.hicasso.impl.frames
  "FRAME-LOCKED OPS — resolved once per frame and not once per boundary
  (rf2-hic-009).

  Two memo tables, keyed by frame keyword, and the one door that empties
  them. HD-020(a) has each boundary read the frame *once* from the
  substrate's single internal context; it does not ask each boundary to
  rebuild the bundle, and `capture-frame` pins a frame incarnation and is
  not free.

  ## Why this is an ownership boundary and not a filing convenience

  **Every entry in both tables is pinned to a frame INCARNATION**, and
  nothing in either table can tell that its incarnation died. That is the
  whole of rf2-hic-013's problem — a destroyed-and-recreated frame with
  the same public id must not be reachable through a handle minted
  against the previous one — and [[forget-frame-ops!]] is the whole of
  today's answer to it: a blunt, caller-driven invalidation with no
  notion of *which* incarnation a memoised bundle belongs to. Putting the
  two tables and their invalidation in one small file is what makes that
  answer legible, and makes replacing it a change to one file.

  ## What lives here, and the one thing that does not

  [[!frame-dispatch]] is the arm's ambient-dispatch memo, but the closure
  it caches is minted by `re-frame.hicasso.impl.collector/frame-dispatch`
  — because that closure is a partial application of the collector's
  commit door (`dispatch!` → `with-commit`), and a namespace cycle is the
  alternative. The TABLE is here because [[forget-frame-ops!]] must empty
  it in the same act it empties the op bundles: they are pinned to the
  same incarnation, and an invalidation that cleared one of them would
  leave a live route into a dead frame. So both tables are public, and
  their only writer outside this file is that one memo-miss branch."
  (:require [re-frame.core :as rf]))

(defonce !frame-ops (atom {}))
(defonce !frame-dispatch (atom {}))

(defn frame-ops
  "The frame-locked op bundle for `frame-kw` — `rf/capture-frame`'s
  `{:frame :dispatch :dispatch-sync :subscribe}` — memoised per frame.

  HD-020(a) has each boundary read the frame *once* from the substrate's
  single internal context; it does not ask each boundary to rebuild the
  bundle. `capture-frame` pins a frame incarnation and is not free, so
  the shell's cost here is one map lookup per render and no allocation."
  [frame-kw]
  (or (get @!frame-ops frame-kw)
      (let [ops (rf/capture-frame frame-kw)]
        (swap! !frame-ops assoc frame-kw ops)
        ops)))

(defn forget-frame-ops!
  "Drop the memoised bundles. A destroyed and re-created frame is a new
  incarnation, and a captured bundle is pinned to the old one.

  It clears BOTH tables in one act, which is why they sit in one file."
  ([] (reset! !frame-ops {}) (reset! !frame-dispatch {}) nil)
  ([frame-kw] (swap! !frame-ops dissoc frame-kw)
              (swap! !frame-dispatch dissoc frame-kw)
              nil))

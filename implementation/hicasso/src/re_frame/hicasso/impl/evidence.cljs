(ns re-frame.hicasso.impl.evidence
  "THE EVIDENCE SINK SEAM (HD-005) — a holder and a setter, and
  deliberately nothing else (rf2-hic-009).

  It is its own namespace because it is the one part of the runtime whose
  correctness is a claim about what the DETACHED path costs. That claim
  is falsifiable only while the seam stays two lines: the moment
  something here builds, buffers, registers or projects, the guard at
  each tap point stops being the outermost form and the cost stops being
  countable. Giving the seam a file of its own is what makes an addition
  to it visible as an addition.

  The tap points are the collector's, not this file's, and they read
  [[!evidence-sink]] directly rather than through a door here — see the
  cost note below, which is the reason there is no `evidence!` function
  to call.

  **rf2-hic-023 owns this file**: the versioned, adapter-neutral evidence
  projection Xray consumes attaches here.

  ## The cost note, moved with the seam

  It moved to the runtime from the retired `front.sub-index` unchanged in
  shape: the `:edges-changed` and `:commit` events keep their keys, so
  anything written against the seam attaches to the fused table without
  being rewritten.

  Detached cost is one deref and one nil test at each of the two tap
  points, and **that is a literal count, which it only is because the nil
  test is the outermost form at each tap point**: nothing the sink would
  have been handed gets built when there is no sink. A third line used to
  own that check — a private `evidence!` the tap points called with the
  event already constructed — and the indirection is what hid the cost,
  charging the detached path one event map per boundary per commit and
  one per commit, garbage the moment it was made and so invisible to a
  retained-heap ladder (rf2-e3i6y). Factoring the guard back out restores
  the claim's falsity, which is why it reads as duplication and stays,
  and why [[!evidence-sink]] is reachable as the atom rather than behind
  a reader fn: a reader would reintroduce a call on the path whose whole
  claim is that it performs a deref and a nil test.

  Fusing the index in moved one more allocation behind the guard
  (rf2-dabt3): the collector's `flush!` used to build
  `(into #{} (map .-subKey) dirty)` unconditionally because the index's
  `commit!` took sub-keys, and the cells were mapped straight back. The
  dirty set is now taken off the cells in hand, so that set is
  evidence-only and is built only when a sink is listening.

  **No evidence subsystem ships**: no manifest, no registry, no
  buffering, and the sink is nil until something sets it.")

;; The attached sink, or nil. Read at each tap point as the outermost form
;; of the guard — see the ns docstring for why that is the whole design and
;; not an optimisation. A comment rather than a docstring because
;; `defonce` takes none.
(defonce !evidence-sink (atom nil))

(defn set-evidence-sink!
  "Attach (or with nil, detach) the evidence sink."
  [f]
  (reset! !evidence-sink f)
  nil)

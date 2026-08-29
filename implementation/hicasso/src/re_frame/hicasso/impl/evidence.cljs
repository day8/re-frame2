(ns re-frame.hicasso.impl.evidence
  "THE EVIDENCE SINK SEAM (HD-005) — a holder and a setter, and
  deliberately nothing else.

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

  The versioned, adapter-neutral evidence projection Xray consumes
  attaches here. The `:edges-changed` and `:commit` events are the seam's
  vocabulary, and anything written against those keys attaches to the
  fused table unchanged.

  ## The cost note

  Detached cost is one deref and one nil test at each of the two tap
  points, and **that is a literal count, which it only is because the nil
  test is the outermost form at each tap point**: nothing the sink would
  have been handed gets built when there is no sink. Routing the check
  through a shared `evidence!` the tap points called with the event
  already constructed would hide that cost, charging the detached path
  one event map per boundary per commit and one per commit — garbage the
  moment it is made, and so invisible to a retained-heap ladder. Keeping
  the guard factored out at each tap point is what keeps the claim
  falsifiable, which is why it reads as duplication and stays, and why
  [[!evidence-sink]] is reachable as the atom rather than behind a reader
  fn: a reader would reintroduce a call on the path whose whole claim is
  that it performs a deref and a nil test.

  The collector's dirty set obeys the same rule. It is taken off the
  cells already in hand rather than rebuilt with
  `(into #{} (map .-subKey) dirty)`, so it is evidence-only and is built
  only when a sink is listening.

  **No evidence subsystem ships**: no manifest, no registry, no
  buffering, and the sink is nil until something sets it.")

;; The attached sink, or nil. Read at each tap point as the outermost form
;; of the guard — see the ns docstring for why that is the whole design and
;; not an optimisation. A comment rather than a docstring because
;; `defonce` takes none.
(defonce !evidence-sink (atom nil))

(defn set-evidence-sink!
  "Attach (or with nil, detach) the evidence sink: a fn of one event map,
  keyed by `:event` (`:commit` or `:edges-changed`), called synchronously
  from the collector's tap points."
  [f]
  (reset! !evidence-sink f)
  nil)

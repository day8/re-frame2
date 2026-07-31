(ns re-frame.movement
  "Re-frame-owned MOVEMENT-WITNESS protocol — an OPTIONAL capability a
  substrate's derived container may publish so core can PROVE, without
  walking the value, that its input moved.

  Sibling in spirit to `re-frame.disposable` (rf2-jicu2): a re-frame-owned
  protocol reified by the substrate spine's derived container and consulted
  by core, with no dependency on any adapter artefact in either direction.

  ## The problem it solves (rf2-gncxk)

  `re-frame.subs.memo`'s fixed-arity-1 memo wrappers guard the user's sub
  body with `(= @last-seen new-value)`. That guard is NOT dead code — the
  spine's derived value is PULL-based (`-deref` recomputes every time), so
  the guard is the only thing stopping a view render from re-running every
  sub body. It earns its keep on the READ path.

  On the WRITE path it is overwhelmingly wasted: a full structural
  comparison of the changed app-db subtree, measured at 147.0 B/sub —
  17.6% of the per-subscription slope — whose TIME cost scales with the
  shape of the changed subtree rather than with the sub.

  It cannot simply be skipped \"when flushing\", because the flush path is
  not the same question. The substrate establishes \"the source moved since
  the source's PREVIOUS NOTIFY\"; the guard asks \"did the input move since
  THE WRAPPER LAST RAN\", and two reachable interleavings separate them —
  a deref between `mark-dirty` and the epoch drain, and a single-epoch
  `A -> B -> A'` return-to-equal that coalesces into one flush. In BOTH the
  guard correctly HITS on the flush path. So the signal must be an exact
  fact about the SOURCE, not about the caller.

  ## The signal

  A container that gates its own propagation on `rf=` already knows the
  value its most recent COMPLETED movement departed FROM. Publishing that
  value lets a consumer holding the same object decide by POINTER
  COMPARISON — nothing is walked, nothing is allocated:

    let S be the source, L the value the consumer last saw, V = @S.
    If `(identical? (-moved-from S) L)` then `(not (= L V))`.

    Proof. W1 + W2 below give `(not (rf= f V))` for the returned `f`, and
    `rf=` subsumes `=`, so `(not (= f V))`. `f` is `identical?` to `L`, so
    `(not (= L V))`. QED.

  The source OWNS the signal (movement is its own notion, defined by its
  own `rf=` gate) and core PULLS it. So `make-derived-value`'s pinned
  `(source-containers compute-fn)` signature is untouched, and so is
  `compute-fn`'s pinned one-arg-per-source shape (Spec 006
  §`make-derived-value`).

  ## Publishing is optional

  Exactly the posture Spec 006 already takes for `:adapter/derived-
  container?` and `:adapter/activate-derived-value!`. A container that
  publishes nothing is not deficient: it is simply one the consumer cannot
  short-circuit, and the consumer's fall-back is the comparison it would
  have performed anyway. Absence is answered by `no-witness`, which every
  consumer must treat as \"no information\".

  In this repo exactly ONE implementor exists — the React-hook spine's
  `make-derived-value` reify (`re-frame.substrate.spine`), whose `notify`
  is `rf=`-gated. The Reagent / reagent-slim `Reaction`, the plain-atom
  derived value (JVM and CLJS), test-react's derived value, and every raw
  `cljs.core/Atom` / `clojure.lang.Atom` publish nothing.

  See Spec 006 §Movement witness (optional).")

#?(:clj (set! *warn-on-reflection* true))

(def no-witness
  "The \"no information\" answer to `-moved-from`.

  A DISTINCT object identity, so it is never `identical?` (nor `=`) to any
  real container value, to `nil`/`false`, or to `re-frame.subs.memo`'s
  `::unset` sentinel. A consumer therefore needs no special-casing: an
  `identical?` test against it simply fails, and the consumer falls through
  to the comparison it would otherwise have made.

  Also the correct answer for a consumer that holds NO witnessing source at
  all — the fixed-arity-1 memo wrappers substitute it for the protocol call
  when their source does not implement `IMovementWitness`, which keeps the
  hot path one closed-over truthiness test plus one pointer compare."
  #?(:clj (Object.) :cljs (js-obj)))

(defprotocol IMovementWitness
  "OPTIONAL capability of a derived container that gates its own
  propagation on `rf=`: publish the value its most recent completed
  movement departed FROM, so a consumer holding that same object can prove
  by pointer comparison that the container's live value differs from it.

  Two obligations bind an implementor. They are the whole normative
  content of this protocol:

  **(W1) FRESHNESS.** Answer `no-witness` unless the container's own value
  has completed a movement AND no input change has been observed since.
  Without W1 a PULL-based container's live value can run ahead of its last
  movement — its sources changed, it has not recomputed-and-notified yet,
  and a witness from the previous movement would then say nothing true
  about what a `-deref` returns now.

  **(W2) SOUNDNESS.** Whenever it answers `f` other than `no-witness`,
  reading the container at that instant yields `v` with `(not (rf= f v))`
  — in particular `(not (= f v))`, since `rf=` subsumes `=`.

  And one binds a consumer:

  **(C1) VERDICT-PRESERVING.** Use the witness ONLY to skip a comparison
  whose answer it determines. Never let it change an observable outcome.
  The set of verdicts a consumer reaches must be bit-identical with and
  without the witness — this is a comparison ELIMINATION, not a behaviour
  change, and any divergence in body-run counts or in the Spec 009
  `:rf.sub/run` / `:rf.sub/skip` stream is a defect, never a new baseline.

  Method naming follows the `re-frame.disposable` precedent: a
  single-leading-dash internal protocol method, reached by re-frame's own
  layers rather than by users."
  (-moved-from [this]
    "The value THIS container's most recent COMPLETED movement departed
     FROM, or `no-witness` when the container cannot presently answer (W1).
     Must be cheap — consumers call it on the hottest path in the
     artefact — and must never recompute the container or notify anyone."))

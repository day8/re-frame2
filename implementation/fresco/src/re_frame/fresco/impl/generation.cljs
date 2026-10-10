(ns re-frame.fresco.impl.generation
  "The commit basis: the monotone counters Spec 006 invariant 5 is judged
  against, and the only doors that advance the three this runtime owns
  (the fourth, the frame's install epoch, is the substrate's and is read
  here, never written). The collector consults the basis on four paths
  and advances it on two, and the frame-destroy hook advances it on a
  third; every volatile is private and every advance is a named door
  (`bump-generation!`, `bump-registry-epoch!`, `retire-frame-epoch!`), so
  a grep for those three is the complete list of writers, because a
  counter anything can increment is a counter nothing can reason about.
  What each term sees is docs/design/fresco/architecture.md, section The
  collector, and `commit-basis` below."
  (:require [re-frame.frame :as rf.frame]))

(defonce ^:private !generation (volatile! 0))
(defonce ^:private !registry-epoch (volatile! 0))
(defonce ^:private !retired-epochs (volatile! 0))

(defn generation
  "The commit generation. Bumped once per flush that moved something."
  []
  @!generation)

(defn bump-generation!
  "Advance the flush generation. The collector's `flush!` is the only
  caller, and it calls it exactly once per flush that found a dirty cell
  — which is what makes `generation` a count of *commits that moved
  something* rather than of flush attempts."
  []
  (vswap! !generation inc)
  nil)

(defn registry-epoch
  "The runtime's own count of `:sub` registrations, first-time and
  replacement alike, and the third term of `commit-basis`. Monotone.
  Counted here rather than exposed by the substrate because the collector
  already installs a registration hook, so the counter is a `vswap!` on a
  hook that runs anyway rather than a new public reader on a production
  namespace. A hot reload re-registers `:sub` handlers, which is exactly
  what this counts (`hmr_registry_cljs_test`)."
  []
  @!registry-epoch)

(defn bump-registry-epoch!
  "Advance the registry epoch. Called from the collector's single
  registration hook (`sub-registered!`), BEFORE that hook scans the cells:
  the scan drops reaction references, and a render racing it must not see
  an epoch from before the registration it is about to read against."
  []
  (vswap! !registry-epoch inc)
  nil)

(defn retire-frame-epoch!
  "Fold a dying frame's install epoch, plus one, into the retired-epoch
  term of the basis. Called by the runtime's frame-destroy hook
  (`impl.frames`), which core runs while it still reports the dying
  incarnation's epoch. A same-id successor's install epoch restarts at
  zero, so without this term the frame term could fall back to the very
  number a boundary rendered under the predecessor read; with it, the
  basis after the reincarnation strictly exceeds every basis read before
  it."
  [frame-kw]
  (vswap! !retired-epochs + (inc (rf.frame/frame-commit-epoch frame-kw)))
  nil)

(defn commit-basis
  "The number a staged read is judged against: this runtime's flush
  generation + the frame's install epoch (`re-frame.frame/frame-commit-epoch`)
  + the registry epoch + the retired epochs of every destroyed frame.
  Monotone, across a same-id reincarnation included, so any sum of bases
  and cell stamps is too; install-counting rather than `=`-counting, so a
  value-equal install still advances it (one redundant re-render at
  worst, never a missed one). Pure read; allocates nothing.

  Four terms because each sees a movement the others cannot: the
  generation moves only through a committed cell's watch, the install
  epoch is a plain counter, only the registry term carries a `reg-sub`
  landing in the render→commit gap — and it belongs in the basis, which
  only a staged key reads live, so an unrelated registration moves no
  mounted boundary (`hmr_registry_cljs_test`) — and only the retired term
  carries a same-id reincarnation, where the install epoch restarts and
  could tie: destroying a frame adds its final epoch plus one
  (`retire-frame-epoch!`), so a staged key rendered under the predecessor
  and committed under the successor sees the number move whether or not
  the frame holds any other cell (`staged_reincarnation_basis_cljs_test`).
  Destroying an unrelated frame moves every staged key's number by the
  same rule, which costs a staged boundary one redundant re-render at
  worst and moves no mounted one. Full argument:
  docs/design/fresco/architecture.md, section The collector."
  [frame-kw]
  (+ @!generation (rf.frame/frame-commit-epoch frame-kw) @!registry-epoch @!retired-epochs))

(defn reset-basis!
  "Zero the three terms this namespace owns: the teardown half of the
  collector's `reset-runtime!`, its only caller. The frame's install
  epoch is the substrate's and is not this door's to touch."
  []
  (vreset! !generation 0)
  (vreset! !registry-epoch 0)
  (vreset! !retired-epochs 0)
  nil)

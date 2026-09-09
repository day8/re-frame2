(ns re-frame.hicasso.impl.generation
  "The commit basis: the three monotone counters Spec 006 invariant 5 is
  judged against, and the only doors that advance the two this runtime
  owns (the third, the frame's install epoch, is the substrate's and is
  read here, never written). The collector consults the basis on four
  paths and advances it on two; both volatiles are private and both bumps
  are named, so a grep for the two `bump-` doors is the complete list of
  writers, because a counter anything can increment is a counter nothing
  can reason about. Why the basis has three terms and what each one sees
  is docs/design/hicasso/architecture.md, section The collector."
  (:require [re-frame.frame :as rf.frame]))

(defonce ^:private !generation (volatile! 0))
(defonce ^:private !registry-epoch (volatile! 0))

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

(defn commit-basis
  "The number a staged read is judged against: this runtime's flush
  generation + the frame's install epoch (`re-frame.frame/frame-commit-epoch`)
  + the registry epoch. Monotone within a frame incarnation, so any sum of
  bases and cell stamps is too; install-counting rather than `=`-counting,
  so a value-equal install still advances it (one redundant re-render at
  worst, never a missed one). Pure read; allocates nothing.

  Three terms because each sees a movement the other two cannot: the
  generation moves only through a committed cell's watch, the install
  epoch is a plain counter, and only the registry term carries a
  `reg-sub` landing in the render→commit gap — and it belongs in the
  basis, which only a staged key reads live, so an unrelated registration
  moves no mounted boundary (`hmr_registry_cljs_test`). The generation
  term is load-bearing across a same-id reincarnation, where the frame
  term restarts and can tie: any other cell the frame holds is rewired by
  microtask and that flush bumps the generation
  (`staged_reincarnation_basis_cljs_test`, rf2-6c12m.19); a frame holding
  no other cell ties either way, which is Spec 006 invariant 5's
  `:node-key` axis, not this number's. Full argument:
  docs/design/hicasso/architecture.md, section The collector."
  [frame-kw]
  (+ @!generation (rf.frame/frame-commit-epoch frame-kw) @!registry-epoch))

(defn reset-basis!
  "Zero both terms this namespace owns: the teardown half of the
  collector's `reset-runtime!`, its only caller. The frame's install
  epoch is the substrate's and is not this door's to touch."
  []
  (vreset! !generation 0)
  (vreset! !registry-epoch 0)
  nil)

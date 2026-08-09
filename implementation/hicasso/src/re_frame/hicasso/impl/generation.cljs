(ns re-frame.hicasso.impl.generation
  "THE COMMIT BASIS — the three monotone numbers invariant 5 is judged
  against, and the only doors that advance them (rf2-hic-009).

  Everything here is a counter and a read of a counter. That is the
  ownership boundary: the collector *consults* the basis on four hot
  paths and *advances* it on two, and a number whose increment can be
  written from anywhere is a number nobody can reason about. Both
  volatiles are private and both bumps are named, so
  `git grep bump-generation!` is the complete list of writers.

  The basis is deliberately arithmetic over three independent terms
  rather than one counter, because each term sees a movement the other
  two are structurally blind to. [[commit-basis]] says which, and why the
  fourth axis — a same-id frame reincarnation — is not carryable here at
  all.

  **The HMR contract lands on this file** (rf2-hic-015). A reload
  re-registers `:sub` handlers, which is exactly what [[registry-epoch]]
  counts and exactly what the collector's cell-invalidation repair rides;
  a reload that must promise identity, focus and state across a save has
  to say what it does to these three numbers."
  (:require [re-frame.frame :as frame]))

(defonce ^:private !generation (volatile! 0))
(defonce ^:private !registry-epoch (volatile! 0))

(defn generation
  "The commit generation. Bumped once per flush that moved something."
  []
  @!generation)

(defn bump-generation!
  "Advance the flush generation. The collector's `flush!` is the only
  caller, and it calls it exactly once per flush that found a dirty cell
  — which is what makes [[generation]] a count of *commits that moved
  something* rather than of flush attempts."
  []
  (vswap! !generation inc)
  nil)

(defn registry-epoch
  "**The arm's own count of `:sub` registrations** — first-time and
  replacement alike — and the third term of [[commit-basis]].

  It is the arm's rather than the substrate's on purpose.
  `observation/registry-epoch*` counts exactly this and is `^:private`;
  the arm already installs a registration hook for
  [[re-frame.hicasso.impl.collector/first-registration!]], so the counter
  is a `vswap!` on a hook that runs anyway rather than a new public
  reader on a production namespace. Monotone, like both other terms."
  []
  @!registry-epoch)

(defn bump-registry-epoch!
  "Advance the registry epoch. Called from the collector's single
  registration hook, BEFORE that hook scans the cells — a render racing
  the scan must not see an epoch from before the registration it is about
  to read against. See
  [[re-frame.hicasso.impl.collector/sub-registered!]], which is where the
  ordering is stated and enforced."
  []
  (vswap! !registry-epoch inc)
  nil)

(defn commit-basis
  "**The number both invariant-5 windows are judged against** — this
  runtime's flush [[generation]], plus `frame`'s own physical-install
  epoch (`re-frame.frame/frame-commit-epoch`, the substrate's
  observation-port evidence counter, bumped once per frame-state install
  at both write chokepoints), plus the arm's [[registry-epoch]].

  The generation alone cannot carry it, and the reason is structural
  rather than a matter of degree: the generation moves only through
  `mark-dirty!`, whose only caller is the value-change watch
  [[re-frame.hicasso.impl.collector/acquire-cell!]] installs **at
  commit**, so a key nothing holds yet can move without moving it. The
  frame's install epoch has no such dependency — it is a counter read,
  not a watch — which is exactly why Spec 006's observation port uses it
  to ask whether durable state moved in the render→commit gap. And
  neither of them is a registry write, so the third term is what carries
  a `reg-sub` landing in that gap.

  All three terms are monotone within a frame incarnation, so the basis
  is, and so is any sum of bases and cell epochs. Deliberately
  install-counting rather than `=`-counting: a value-equal install still
  advances it, which costs at most one redundant re-render and cannot
  cost a missed one. Pure read; allocates nothing.

  ## Why the registry term costs the mounted case nothing (rf2-2rtt6.50)

  This is the term rf2-2rtt6.44 costed and declined, and **it is not the
  thing that was declined.** What that costing priced was a registry term
  in every key's *live* contribution to
  [[re-frame.hicasso.impl.collector/make-snapshot]] — which would have
  moved every mounted boundary's number on every `reg-sub` in the
  application, and bought a re-render that read straight back through a
  dead cell. This term is in the basis, and the basis is read live by
  exactly one branch of that sum: **a key no cell holds yet**. A held key
  contributes its cell's *frozen* stamp, which no registration touches.

  So the reach of the term is precisely the set of keys inside a
  render→commit gap, which is the set of keys that have the defect. A
  mounted boundary holds a reference to every key it reads, so it has no
  staged term at all and an unrelated `reg-sub` moves its snapshot by
  zero — `a-first-registration-of-an-id-no-cell-holds-disturbs-nothing`
  asserts exactly that, and it is unchanged by this term, which is the
  cleanest available proof that the two options are different options.

  Conservative in the safe direction and only there, exactly as the
  install term already is: a boundary mounting as an *unrelated* module
  registers its subs re-renders once for nothing. A MISSED move would be
  the P0, and adding a monotone term to a monotone sum cannot cause one.

  Still silent on one axis, and permanently so: a same-id frame
  reincarnation RESTARTS `frame-commit-epoch` at 0 (measured: A's epoch
  and B's are both 1, so the basis TIES across the reincarnation), which
  is the case the observation port needs its `:node-key` field for. That
  axis is not this number's to carry, and rf2-2rtt6.44 settled why: the
  transition leaves the cell holding a reaction that can no longer answer
  for its key, so a moved number would only buy a re-render that read
  back through the same dead reference.
  [[re-frame.hicasso.impl.collector/invalidate-cell!]] carries the
  *held*-cell half of all three axes; this term carries the *staged* half
  of the registry one, where there is no dead reference to read back
  through because the commit acquires against the live registration."
  [frame-kw]
  (+ @!generation (frame/frame-commit-epoch frame-kw) @!registry-epoch))

(defn reset-basis!
  "Zero both terms this namespace owns. The teardown half, called by
  [[re-frame.hicasso.impl.collector/reset-runtime!]] and by nothing else
  — the frame's install epoch is the substrate's and is not this door's
  to touch."
  []
  (vreset! !generation 0)
  (vreset! !registry-epoch 0)
  nil)

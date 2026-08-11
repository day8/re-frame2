(ns re-frame.bench.p0-hicasso
  "EP-0038 P0 — **the candidate**: a Hicasso-shaped boundary on the P0
  reads ladder. One subscription/epoch hook per boundary, N edges in a
  shared index (rf2-2rtt6.34).

  ## Why this arm exists, and why on THIS instrument

  Per-read retained heap is the one axis on which the ruled architecture
  is structurally cheaper than the parent it is built on rather than more
  expensive. The React-hook spine pays a `useSyncExternalStore` cell (plus
  two `useRef`s and a `useMemo`) **per read**; Arm 1 pays **one**
  `useSyncExternalStore` for the whole boundary however many reads it
  makes, and carries the reads as edges in module-level index maps
  (`architecture.md`, Arm 1; HD-020's ≤2-hook budget). That is a
  difference in kind, and the ladder is where it is priced or refuted.

  `validation.md` §180-189 is explicit that a candidate is judged against
  the donor row taken on **its own instrument**, and that a margin under
  5% is instrument-limited rather than cleared. So the candidate rung and
  both donor rungs are measured here, in one run, in the same rounds, on
  the instrument the published per-read gates are stated on.

  ## The arm IS the shipped package, not a model of it

  Every read goes through `re-frame.hicasso/sub` — the ambient collector
  the operator ruled the shipping read surface on 2026-07-31 — and every
  boundary is minted by the package's own `defview`. Nothing here
  re-implements the shell, the index, the entry cache or the commit path.
  A hand-rolled imitation would be pricing this file rather than the
  design.

  And it is `implementation/hicasso/src` those doors reach, not the
  `re-frame.bench.hicasso.arm1.*` prototype the package was moved from
  (rf2-fe0l). Until this repoint no heap instrument pointed at the
  package at all: the prototype's own docstring says it lives off every
  production source path, and `hicasso/scripts/check_freeze.py` says the
  same from the other side, so a heap figure taken through it priced a
  frozen copy whose divergence from the product is expected and
  permanent. The direction of the dependency is the allowed one — the
  bench tree may require the package; the SEALED rule forbids only the
  reverse.

  ## A loop, and why it is not the UIx arm's unrolling

  `sub` is an ordinary function call, not a hook — that is the collector
  tier's entire ergonomic claim — so a loop is both legal and the way an
  application would write it. The UIx arm is unrolled because
  `use-subscribe` is a hook and React's rule is about call sequence. The
  Reagent arm loops for the same reason this one does. The asymmetry is
  in the substrates, not in the measurement.

  ## What the shared read-set entry does NOT buy at Q = E

  Worth writing down before any number is read, because a ladder reads it
  wrong otherwise: the runtime's read-set entry — key array, key set, and
  the cached `subscribe`/`getSnapshot` pair — is shared by every boundary
  whose read SET is identical. On the mandatory distinct-query witness no
  two boundaries read the same set, so the entry is effectively
  per-boundary here and the sharing buys nothing. This rung is therefore
  the arm's WORST case for that structure, exactly as it is the worst case
  for the donors' subscription caches. `stats` reports the entry count and
  the driver gates it against B, so the claim is a counted number rather
  than an argument.

  Owner: the operator-owned governance set that superseded rf2-2rtt6.1 on
  2026-08-10, enumerated once in `docs/design/hicasso/studio/README.md`;
  this arm rf2-2rtt6.34."
  (:require [re-frame.bench.p0-fixture :as fx]
            [re-frame.hicasso :refer [sub]])
  (:require-macros [re-frame.hicasso :refer [defview]]))

(defview lad-cell
  "One Hicasso boundary reading `r` DISTINCT subscriptions — the same
  `:p0/fan` key space, the same `fx/fan-key` rule and the same
  `span.cell` element the Reagent and UIx ladder cells build, so the three
  arms differ in how a subscription value reaches a boundary and in
  nothing else."
  [{:keys [j n r]}]
  (let [v (loop [k 0 acc 0]
            (if (< k r)
              (recur (inc k) (+ acc (sub [:p0/fan (fx/fan-key n r k)])))
              acc))]
    [:span.cell {:data-i j} (str v)]))

(defn lad-grid
  "The grid, as a PLAIN FUNCTION returning hiccup rather than a `defview`.

  A `defview` here would mint one more boundary per root that reads
  nothing, and `stats`' boundary count — which the driver gates against B
  — would answer `B + roots`. The grid does no reading, so it costs the
  witness nothing to leave it un-minted; `mount/root!` takes hiccup and
  the codec walks it.

  `into` rather than a lazy `for`, so the children are a vector before the
  codec sees them. The codec is eager either way (that is what makes the
  collector safe over lazy seqs), but a ladder should not be the place
  that depends on it."
  [offset n-cells reads]
  (into [:div.ugrid]
        (map (fn [j] [lad-cell {:key j :j j :n (+ offset j) :r reads}]))
        (range n-cells)))

# The boundary crossing walk, priced — what `realize-deep` costs at the hand-off, and what its key half costs on top

**Beads** `rf2-2rtt6.45` (the crossing walk), `rf2-2rtt6.32` (the key half) · **epic** `rf2-6c12m` (this page, `rf2-76hbj`)
**Runtime** a `:none` build under Node 22; every figure here is a **dev-build
diagnostic** quoted for its ratios, never against the bar, per the discipline
in [the studio index](README.md).
**Reproduction** the behaviour each row rests on is pinned by the `deftest`s
named below in
`bench/hicasso/src/re_frame/bench/hicasso/arm1/boundary_crossing_cljs_test.cljs`
(the bench lane is hand-run from `bench/hicasso/`; see its README). The clock
harness that produced the timings was an A/B/C rig written in the measuring
namespace and was not committed — which is the reason the rows below are
quoted as ratios and the absolute figures are not carried forward.

This page is the measured record behind `re-frame.hicasso.impl.codec`'s
`realize-deep`. The figures were carried in that function's docstring until
`rf2-6c12m.4` ruled measurement narrative out of source; the argument for the
walk — why a boundary prop is the one position the eager codec did not reach,
and why a lazy seq is forced where a `delay` is refused — is on
[the dogfood judgement](arm1-lean-react-dogfood-judgement.md), which also
carries the methodological note on the instrument confound recorded in §2.

The runtime carrying these figures landed in this tree at
`93ec92d491c744f6adf8ab4ea48443e18b23bc45` (`rf2-hic-001`, the copy of the
runtime into `implementation/hicasso/`); the bench rows moved to their present
path at `8a10915ed813a6d35b8a47923028060e2d549ae0` (`rf2-6c12m.1`).

## 1. The walk at the hand-off

Realising a `LazySeq` caches into the seq itself, so the walk rebuilds nothing
and copies nothing: every branch returns the argument it was given, and with
the two reducing functions named as vars the walk allocates nothing either. The
traversal is the whole of the work, and the seq it forces was going to be
walked anyway, one boundary later. Best of five runs, whole element build as
the denominator:

| Boundary props | walk | whole element build | share |
|---|---|---|---|
| `{:id :title :done?}` — the dogfood row | 69 ns | 1,089 ns | **6%** |
| the same plus two hiccup children | 233 ns | 1,344 ns | 17% |
| a 100-row collection prop | 13.5 µs | 15.1 µs | 89% |

The last row is the honest ceiling, and the right comparison is outward: **the
same 100-row collection at a NATIVE prop position costs 70.7 µs**, because
`clj->js` rebuilds it into JavaScript. The position whose eagerness the
structural claim already rested on is 4.7× dearer than the position this walk
repairs. Pinned by `realize-deep-returns-its-argument-by-identity`,
`realize-deep-forces-every-lazy-seq-it-can-reach` and
`realize-deep-does-not-walk-into-a-react-element-or-a-function`.

## 2. What the key half costs

A map entry is two reachable positions, so the key half goes through the same
walk (`rf2-2rtt6.32`). Three walks A/B/C'd in one process on an otherwise idle
box, rounds interleaved, best of seven per round, four whole repetitions: A is a
value-only walk that skips keys, B walks keys unconditionally, C is B with the
`keyword?` short-circuit and is what ships. All three arms were written in the
measuring namespace, including the one that ships, because timing two local arms
against `realize-deep` itself compared an inline `throw` with a call to
`refuse-deferred!` and reported the shipping arm 9–20% *faster* than a walk
doing strictly less work — an impossible result, and the only reason the
confound was caught.

| shape | B vs A | C vs A |
|---|---|---|
| the dogfood row's props | +59%, +57%, +67%, +51% | +4%, +2%, +1%, +18% |
| the same plus two hiccup children | +28%, +20%, +20%, +24% | +7%, +3%, +0%, −2% |
| a 100-row collection prop | +56%, +40%, +47%, +49% | +7%, +1%, +2%, +3% |

Against the whole boundary element build, measured in the same process, **B
adds 7.6–9.9% of it and C adds 0.2–2.8%.** The dear part was never the
traversal; it was proving that a keyword is not a collection, which costs
`coll?` — for anything without the `ICollection` marker a fall-through to
`native-satisfies?`, the dearest predicate on the path — where `keyword?` is
one `instanceof`.

Two instruments, one denominator: this rig read the element build at 1.08–1.16
µs, within a few percent of §1's 1,089 ns, and the walk itself at 148–177 ns
against §1's 69 ns. They agree on what an element costs and not on what the walk
inside it costs, which is why both tables are quoted as ratios. Pinned by
`realize-deep-walks-map-keys-without-disturbing-the-map`,
`realize-deep-reaches-a-lazy-seq-at-a-key-position-too` and
`the-keyword-key-short-circuit-skips-only-a-provable-no-op`.

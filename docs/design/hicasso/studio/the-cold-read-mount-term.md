# The cold-read mount term, profiled and cheapened

rf2-y1jkm cheapened the interpreter walk and its closing decomposition moved
the surviving mount gap off the walk: hicasso in-page 3.300 ms against uix
1.900 ms on the acceptance shape, concentrated in the **141 per-instance
collector reads** — each a cold `subs/subscribe-once`, subscribe + deref +
unsubscribe per read per mount, because cells only exist after commit
([the walk page](the-interpreter-walk-profiled-and-cheapened.md)). This page
does three things, in the order the bead requires: **profiles** one cold read
at mount, decomposed — cache probe, reaction construction, dispose cascade,
cell construction, index write, reaction wiring — **cheapens** the phase the
profile convicts with behaviour held fixed, and **re-takes** the census clock
rows at the changed blobs on the clock of record.

Bead **`rf2-6c237`**. Adjudicated against the mount gate as **`rf2-2rtt6.1`**
carried it on 2026-08-02 — mount ≤ 1.10× direct UIx-on-subs, floor-normalised,
same run, raw `TaskDuration`. That standard was superseded and closed on
2026-08-10; the operative default is now K1 price acceptance `rf2-hic-003`
([the K1 record](../product/k1-price-acceptance.md)). The before rows
are `rf2-y1jkm`'s (`the-interpreter-walk-profiled-and-cheapened.md` §4).

> **THE CANONICAL MOUNT WITNESS IS M1 AND STAYS M1.** These rows corroborate
> the amendment's line on census-real screens exactly as the walk page's did;
> nothing here re-baselines the canonical witness, and the ruling on any
> verdict below is the operator's
> ([the decision brief](../product/decision-brief.md) — `rf2-2rtt6.1` was
> superseded and closed on 2026-08-10), never this page's.

## 1. The profile — where 141 cold reads actually pay

**Instrument.** `read_profile_app.cljs` + the lane's generic `run.cjs`
driver, on the walk profile's own discipline: every ablation arm written in
the measuring namespace (rf2-2rtt6.32 — a local arm timed against a foreign
one compares call conventions as much as phases), interleaved under the
lane's reflecting schedule with the arm-order guard adjudicating, 6 rounds ×
(4 warmup + 10 samples), 8 roster passes per timing window, each pass through
its own `render-body` door so a sample bills what a mount bills per boundary
render. **The roster is harvested, not transcribed**: the real
`large-template/page` is mounted once and the read-set entry the mount
resolved is read back through the runtime's own `last-reads` — its key array
IS the page's read sequence, in realization order; boot is fatal unless it is
the arithmetic's 3 + 2 × 69 = 141 reads, all distinct, on the 1,202-element
page (it was: `harvest OK — 1202 elements, 141 reads (141 distinct)`).
**Diagnostic clock** (in-page `performance.now`), stated as such: it
attributes cost *between phases of one cold read* and publishes no gated
figure.

**The census** (what the 141 reads are made of): 141 reads, 141 distinct —
`[:conduit/article slug]` × 69, `[:conduit/favorite-pending? slug]` × 69,
plus the three chrome keys — every one a **single-source layer-1 sub**
(`:input-kind :db`, no `:<-` chain, no shared parents).

**The render half** (before, commit `cb41ee537b`; ms per 141-read pass, p50
over 60 samples; µs per read in brackets). `cb41ee537b` is an authored head
this repo's rebase-merge stranded, so it is in no fresh clone; **it landed on
main as `12e50c5b36`**, which recovers the measured read path in full
(Provenance), and that is the SHA to check these rows against:

| arm | what one pass is | p50 ms [min–max] | µs/read |
|---|---|---|---|
| `ship` | 141 × `(sub q)` — the shipping cold path | 0.8750 [0.7750–1.2500] | **6.21** |
| `local` | the frozen in-namespace copy of that path | 0.8750 [0.7625–1.0875] | 6.21 — **copy fidelity local/ship = 1.0000** |
| `no-shell` | 141 × bare `subscribe-once` | 0.8563 [0.7375–1.1250] | 6.07 |
| `probe` | the candidate v1: peek + ONE run-shared threaded memo | 0.3875 [0.3375–0.5625] | 2.75 |
| `probe-fresh` | 141 × `compute-sub`, fresh memo per read, no wrap/peek | 0.2000 [0.1625–0.3000] | 1.42 |
| `floor` | 141 × registrar lookup + raw handler call on app-db | 0.0375 [0.0250–0.1125] | 0.27 |
| `warm` | 141 × `(sub q)` with cells committed (second frame) | 0.0875 [0.0625–0.1625] | 0.62 |
| `ctl2` | the roster twice through `subscribe-once` | 1.6750 [1.4625–1.9875] | control: predicted 1.713×, measured 1.675× [1.462–1.987] — **PASS** |

Arm-order guard: clean on every arm, both phases. Positive control inside the
interleave, adjudicated before anything is read.

**The commit half — REPUBLISHED, every arm taken at ONE commit** (`rf2-07rnj`'s
re-take, 2026-08-21, base commit `3de77d3c23`; ms per 141-key boundary commit
through the runtime's own `commit-boundary!` seam). **The window shape is stated
because the shares are not invariant to it**: 32 identically-seeded frames per
window, 8 × (2 + 8) = 64 kept samples per arm, eleven arms, grid 0.0015625
ms/commit; three runs, `exit 0` each, released and settled between samples
behind a residue equality gate that never fired. **Every term carries its own
resolution verdict against its OWN run's null spread**, on a rule fixed before
run 1 — the window note below sets both out.

| term | run 1 | run 2 | run 3 | share of `c-local` | verdict |
|---|---|---|---|---|---|
| whole commit (`commit`, shipping seam) | 0.9391 | 0.9781 | 0.7859 | — | absolute; copy fidelity `c-local`/`commit` = 0.9501 / 0.9393 / 0.9841 |
| `c-local` (the ablation baseline — every share below divides by it) | 0.8922 | 0.9187 | 0.7734 | — | absolute |
| — **reaction build + cache insert** (`c-local − c-nosub`) | **0.6016** | **0.6156** | **0.5141** | **67.4% / 67.0% / 66.5%** | **RESOLVED** on every run |
| — cell-map insert (`c-local − c-nomap`) | 0.0672 | 0.0656 | 0.0797 | 7.5% / 7.1% / 10.3% | RESOLVED on every run |
| — watch wiring (`c-local − c-nowatch`) | 0.0594 | 0.0547 | 0.0500 | — | **UNRESOLVED** — clears its null in runs 1–2, ties it exactly in run 3 |
| — activation capture (`c-local − c-noactivate`) | 0.0188 | 0.0187 | 0.0328 | — | **UNRESOLVED** on all three runs |
| — reader membership (`c-local − c-noreaders`) | 0.0156 | 0.0062 | 0.0500 | — | **UNRESOLVED** on all three runs |
| `b-build` (141 × subscribe + deref, no in-window dispose) | 0.6891 | 0.7047 | 0.5797 | — | context: build + compute alone |

**Three of the five terms are unresolved, and NO BOUND IS PUBLISHED FOR ANY OF
THEM.** Their readings are quoted above and nothing further is claimed: a null
says what this instrument cannot SEE, never how large the invisible thing is.
Reading any of those three rows as an upper bound would be the withdrawn
`< 0.006 ms/commit` error one layer back, and that withdrawal stands.

**What the re-take does settle is the thing this table was stale about.** Every
share above divides by a `c-local` measured in the SAME run as its numerator, so
the share column is no longer arithmetic across two instruments. The `c-noindex`
arm and the `index write` row are gone — `rf2-dabt3` deleted the structure they
priced — and `c-noreaders` stands in their place, unresolved and published as
such.

**The 2026-08-02 reading the table above replaces**, kept in place because the
window notes below cite its cells by value (four identically-seeded frames per
window — a quarter of the shape above, which is part of why its shares differ):

| term | delta ms/commit | share of `c-local` |
|---|---|---|
| whole commit (`commit`, shipping seam) | 0.8875 [0.8000–1.0750] | — (6.29 µs/key; copy fidelity c-local/commit = 0.9718, a **HISTORICAL reading** authored at `cb41ee537b`, landed on main as `12e50c5b36` — see the ratio note below) |
| — **reaction build + cache insert** (`c-local − c-nosub`) | **0.4125** | **47.8%** |
| — index write (`c-local − c-noindex`) — **arm and structure both retired since; see the window note below** | 0.0750 | 8.7% |
| — cell-map insert (`c-local − c-nomap`) | 0.0375 | 4.3% |
| — watch wiring (`c-local − c-nowatch`) | 0.0250 | 2.9% |
| `b-build` (141 × subscribe + deref, no in-window dispose) | 0.6000 | context: build + compute alone |

> **These rows are dated `cb41ee537b`, and one change had landed under them
> when this note was written** (rf2-gttif; two more have landed since — see the
> window note below). `rf2-aqgr2` (`f7fd0c6a52`) stopped the runtime minting a
> `Keyword` per key cell — 141 mints per commit on this shape — so **0.8875
> and 6.29 µs/key are now upper bounds** on the shipping seam, and the shares,
> which divide by `c-local` (0.8625 here), are lower bounds by the same term:
> the copy carried that mint too, until `rf2-6wh9o` (`54a2a78924`) removed it
> there as well. The term is small — 141 mints against a phase-B grid that
> resolves to 0.025 ms/commit, and the nearest anchor in the micro table below
> is 53 ns for a two-element vector — so it is about one quantum, and only a
> re-take on a quiet box could settle it (`rf2-d360z`). That re-take has since
> run; the note below is its verdict, and it also found that the mint is not the
> only change to have landed under these rows. **The four deltas do not
> move at all**: the mint was bound above `commit-local!`'s `C-NOWATCH` guard
> and present in every mode, so it stood on both sides of each and cancelled
> exactly — a delta is a *difference*, and an equal term standing on both
> sides of a difference cancels out of it identically. **The fidelity ratio is
> the other case, and the same premise does not carry across to it.** An equal
> cost `m` removed from both arms of a RATIO leaves it where it was only when
> `m` is zero or the arms are equal: `(A − m)/(B − m) = A/B` iff `m = 0` or
> `A = B`. Here `A` = 0.8625 and `B` = 0.8875 are not equal, so removing the
> 0.002–0.005 ms/commit the window note below extrapolates for the mint —
> 141 × a 14–32 ns micro reading, never a differenced arm, because no arm
> here ever priced a minting commit against a non-minting one — moves
> 0.971831 to 0.971767–0.971671. Those digits are past what four-digit inputs
> support and are quoted only for direction and scale; the point is not the
> size of the shift but that **the cancellation argument is unsound for a
> ratio rather than merely imprecise**, and it was the only thing this page
> offered for carrying 0.9718 forward. (§1's render half is the `A = B` case —
> `local/ship` = 1.0000, which an equal two-sided removal genuinely does leave
> alone.) What the mint's removal does warrant is the ablation **design**: the
> copy is once again *structurally* identical to the seam it prices, which
> warrants the `c-*` arms better than a clock agreement on its own ever did.
> It does not warrant carrying a measured ratio forward, and the ratio is now
> published as the historical reading it is. (Merged-PR audit of #8335;
> `rf2-gttif` reopened on it.)

> **THE QUIET-BOX RE-TAKE HAS NOW RUN, AND THE FIGURES ABOVE STAND UNRESTATED**
> (`rf2-d360z`, 2026-08-15, `2c95c22386`, instrument blob `7f2a7edccf`,
> `:advanced`, HeadlessChrome 147.0.7727.15). The 2026-08-07 attempt is history:
> it refused twice at the phase-B residue gate without reaching a number,
> because `rf2-2rtt6.84` had moved the entry reaper's horizon outside the single
> macrotask `lane/settle!` yields. `rf2-981nt` (`a5cb33f708`) repaired that by
> baselining behind the runtime's own quiescence point, and phase B now runs to
> completion — **three times in this window, `exit 0` each, both arm-order
> guards reportable and the positive control passing on every run, the residue
> gate never firing.** One session, one rig. Processor Queue Length was sampled
> 85 times across the window and read **0 on 82 of them**; the three non-zero
> readings (1, 1, 2) all fell within a minute of a `shadow-cljs` compile start,
> and two of those cannot be proven to sit outside run 3's measurement phase, so
> they are quoted rather than dismissed:
>
> | run | `commit` p50 ms [min–max] | `c-local` p50 ms [min–max] | c-local/commit |
> |---|---|---|---|
> | 1 | 0.5500 [0.3750–0.7250] | 0.4875 [0.3750–1.0750] | 0.8864 |
> | 2 | 0.4500 [0.3750–1.2250] | 0.4250 [0.3750–0.7750] | 0.9444 |
> | 3 | 0.4625 [0.3750–0.9000] | 0.4250 [0.3750–0.9000] | 0.9189 |
>
> **The two absolutes are not restated, and the reason is stronger than the
> "it came back inside the quantum" result the bead anticipated.** The `commit`
> arm's spread across three runs of one binary — one source file, one build,
> one session, no deliberate change between them — is 0.100 ms, **four grid
> steps.** The effect commissioned for sight is 141 keyword mints, which this
> window's own micro readings price at 14–32 ns each (the micro table below is
> the published run's and is not restated): **0.002–0.005 ms/commit, an eighth
> of one grid step and about a thirtieth of the dispersion just quoted.** What
> produced that dispersion this window does not establish. The instrument's own
> run-to-run variation is the leading candidate and the box was quiet on 82 of
> 85 samples — but two of the three non-zero readings cannot be placed outside
> run 3's measurement phase, and that is enough to keep residual load in the
> frame. **The refusal does not turn on which it is.** Under either cause the
> term is not merely below the window's resolution but far below its scatter,
> and more runs of this shape would only measure the scatter again. Separating
> the two causes would take a window built to do it, and would not bring this
> term into view.
>
> **A restatement of two cells could no longer be honest in any case, because
> two further changes have landed under this table, neither of them the mint,
> and neither anticipated by `rf2-gttif` or `rf2-d360z` — both written on
> 2026-08-03, hours before the first of them.**
>
> - **`rf2-dabt3` (`383ba2d645`) retired the sub-index into the cell table.**
>   `front/sub_index.cljs` is deleted, the shipping commit half no longer
>   performs an `index/mount!` plus a whole-set `record-reads!` but pushes one
>   reader slot per key, and the same commit replaced the instrument's
>   `c-noindex` arm with `c-noreaders`. **The `index write` row above therefore
>   prices a structure that no longer exists**, and both absolutes lost that
>   work on top of losing the mint.
> - **`rf2-lzpfj` (`04bb0fa73a`) added `interop/activate-derived-value!` to
>   `commit-local!`** so the copy matches `wire-cell!`'s shape. `c-local` now
>   performs work it was not performing when 0.8625 was taken.
>
> So `c-local` — the denominator every share in this table divides by — is not
> the arm that produced 0.8625: it has since lost the index write, lost the
> mint, and gained the activation. Dividing 2026-08-02 numerators by a
> 2026-08-15 denominator is arithmetic across two instruments, which is the one
> thing this page's discipline exists to prevent. **The share column cannot be
> repaired by re-measuring its denominator. The decomposition needs a whole
> re-take, every arm at one commit** — filed rather than improvised here.
>
> **And the instrument cannot support that re-take yet, which is this window's
> other finding.** Across the three runs the cell-map insert read 0.1125 /
> 0.0250 / −0.0500, the watch wiring −0.0125 / +0.0375 / 0.0000, and the new
> `c-noreaders` term −0.0375 / −0.0375 / +0.0250. **A negative delta is
> arithmetically impossible** — the ablation arm does strictly less work — so
> three of the four terms that must be positive sit below the phase-B window's
> floor, and only the reaction build + cache insert (0.2250 – 0.2875) resolves
> at all. Phase B needs a longer window before it can decompose anything again;
> that is filed too, and deliberately not attempted mid-window.
>
> **The fidelity ratio is not restated either — it is RELABELLED rather than
> carried.** It read 0.8864 / 0.9444 / 0.9189 against the published 0.9718 —
> a spread of 0.058 lying wholly *below* it, so this window neither reproduces
> the published ratio nor brackets it, and none of it is offered as
> confirmation. **And nothing else holds 0.9718 in place either.** The reason
> `rf2-gttif` first gave — the keyword mint left the copy and the seam alike,
> so it cancels out of the ratio — is a sound argument about a *difference*
> and an unsound one about a ratio: `(A − m)/(B − m)` equals `A/B` only when
> `m = 0` or `A = B`, and the two arms behind 0.9718 are 0.8625 and 0.8875.
> The note directly under §1's commit-half table carries that arithmetic.
> `rf2-lzpfj`'s activation
> is then not a perturbation of the argument but its refutation: it joined
> `c-local` ALONE — restoring parity with work the seam was already performing
> — and a one-sided term cancels from neither a difference nor a ratio,
> whatever its size. (The widened-window re-take below measures that term at
> 0.0422–0.0562 ms/commit, roughly 8–28× the mint's extrapolated cost; it was taken at
> a window shape these 2026-08-02 absolutes were not, so no transferred figure
> is offered and none is needed — the one-sidedness is structural, not a
> matter of magnitude. That same re-take is also why the "expected at the noise
> floor" reading this paragraph previously gave the activation is withdrawn:
> the term resolves with one sign on every run.) **0.9718 therefore stands as
> a HISTORICAL reading** — what the arms of `cb41ee537b`, landed on main as
> `12e50c5b36`, returned before the mint left, the index write was retired and
> the activation arrived — and not as a figure this tree would return. The
> structural parity `rf2-6wh9o` restored still warrants the `c-*` ablation
> DESIGN, which is the job it can do; it cannot preserve a measured ratio
> numerically. (Merged-PR audit of #8335; `rf2-gttif` reopened on it.)

> **THE WHOLE RE-TAKE RAN ON THE WIDENED WINDOW, AND IT DOES NOT PUBLISH**
> (`rf2-07rnj`, 2026-08-16, commit `a43aa8609f`, instrument blob
> `6d2b67b5b0`, `:advanced`, HeadlessChrome 147.0.7727.15, node v24.13.0).
> Three runs, `exit 0` on each, **both arm-order guards reportable on every
> run**, the phase-A positive control passing on every run, the phase-B
> residue gate never firing.
>
> **The window shape, chosen once before the first run and held for every arm
> of every run: 32 frames per window, 8 × (2 + 8) = 64 kept samples per arm,
> grid 0.0015625 ms/commit.** That is `rf2-3l6hf`'s widened shape exactly as
> it landed; no gate, tolerance or knob was touched, and nothing was adjusted
> between runs. Every arm of a run was taken in ONE process, interleaved under
> the lane's reflecting schedule. **The shape is stated because the shares are
> not invariant to it** — the reaction build reads 56% of `c-local` at 4
> frames and 67–68% at 32 — and **no share is computed below**, for the reason
> the next paragraph but one gives.
>
> | arm | run 1 p50 [min–max] | run 2 p50 [min–max] | run 3 p50 [min–max] |
> |---|---|---|---|
> | `commit` (shipping seam) | 0.6953 [0.5281–0.8844] | 0.7516 [0.5313–1.0875] | 0.7203 [0.5812–0.8969] |
> | `c-local` (the copy) | 0.6594 [0.4750–1.0281] | 0.6969 [0.5469–0.9188] | 0.6875 [0.5062–0.9219] |
> | `c-noactivate` | 0.6172 [0.4750–0.8906] | 0.6484 [0.4875–0.8469] | 0.6313 [0.4813–0.8625] |
> | `c-nowatch` | 0.5875 [0.4313–0.7531] | 0.6313 [0.4625–0.8281] | 0.6094 [0.4562–0.8875] |
> | `c-nosub` | 0.2156 [0.1594–0.4188] | 0.2250 [0.1625–0.4500] | 0.2188 [0.1688–0.2969] |
> | `c-noreaders` | 0.6547 [0.5250–0.8250] | 0.7109 [0.4875–1.0125] | 0.6750 [0.5344–0.8188] |
> | `c-nomap` | 0.6156 [0.4375–0.7875] | 0.6703 [0.4875–0.9125] | 0.6375 [0.4750–0.9063] |
> | `b-build` | 0.4938 [0.3937–0.9906] | 0.5688 [0.3937–0.8656] | 0.5406 [0.4531–0.7750] |
> | copy fidelity `c-local`/`commit` | 0.9483 | 0.9272 | 0.9544 |
>
> **Four of the five ablation terms resolve at this shape with one sign on
> every run. The fifth does not, and that is why nothing above is
> republished.**
>
> | term (`c-local −` the arm) | run 1 | run 2 | run 3 |
> |---|---|---|---|
> | reaction build + cache insert (`c-nosub`) | 0.4437 | 0.4719 | 0.4688 |
> | watch wiring (`c-nowatch`) | 0.0719 | 0.0656 | 0.0781 |
> | activation capture (`c-noactivate`) | 0.0422 | 0.0484 | 0.0562 |
> | cell-map insert (`c-nomap`) | 0.0437 | 0.0266 | 0.0500 |
> | **reader membership (`c-noreaders`)** | **+0.0047** | **−0.0141** | **+0.0125** |
>
> **Reader membership straddles zero across three runs of one binary in one
> session.** A negative delta is arithmetically impossible here — `c-noreaders`
> does strictly less work than `c-local` — so the term the `rf2-dabt3` fusion
> left in place of the retired index write is **UNRESOLVED at this window**. A
> decomposition that cannot see one of its terms is not a decomposition; that
> is this page's own standard, and it applies to its own re-take.
>
> **The `< 0.006 ms/commit` bound floated for that term is withdrawn, and this
> window did not chase it.** The reasoning was that a most-negative reading of
> −0.0062 measures a symmetric instrument floor. It does not. If an observed
> delta is an unknown positive cost plus estimator error, a reading of −0.0062
> establishes only that one negative excursion exceeded 0.0062 *plus that
> cost*: it neither calibrates a symmetric floor nor bounds the cost from
> above, and comparing most-negative readings taken at two window widths
> cannot demonstrate that a floor scaled with the window. The arithmetic is
> beside the point on this box in any case — **run 2 read −0.0141, more than
> twice the figure that was to have been published as the bound.** (Merged-PR
> audit of #8328; `rf2-3l6hf` reopened on it.) Widening the window further was
> deliberately not attempted: whether 128–256 frames would bring the term clear
> is a prediction from floor readings, not a measurement, and a rung added
> between runs makes the series two instruments.
>
> **`c-noactivate` is a term and not the noise floor** the instrument's own
> namespace docstring calls it (`rf2-tcffa`; `rf2-19usn` carries the shipping
> cost behind it), and this window corroborates that independently: it reads
> 0.0422 / 0.0484 / 0.0562 with one sign on every run, **larger than the
> cell-map insert in two runs of three.** It therefore cannot serve as the
> arbiter of whether a window is wide enough, which is the job the widening
> commit gave it.
>
> **Nothing in §1's table or in §4 is restated by this window, and the
> obligations that ride the republication stay open on `rf2-07rnj`.** The
> `index write` row still prices the structure `rf2-dabt3` deleted; 0.8625 is
> still not the `c-local` measured above; and the conclusions built on both —
> §1's "Reading it" 4, and `the-in-page-mount-term-decomposed.md`'s §6(b)
> comparison and §9.2 "batching the index write" — still read as current.
> Retiring or qualifying them was to ride the republished decomposition, and
> there is no republished decomposition.
>
> **The box, sampled with real counters and reported whole.** Processor Queue
> Length (`\System\Processor Queue Length`) was sampled **243 times** across
> the window, back-to-back at 1 s during every run, and read **0 on 218 of
> them**. Of the 25 non-zero readings: **10 are build-phase** — `run.cjs`
> rebuilds the `:advanced` bundle unconditionally on every run and that
> compile saturates this box — and they are the only large ones (2, 34, 57, 1,
> 26 in run 2; 1, 1, 1, **93**, 1 in run 3), every one of them before the
> bundle's own mtime. **10 are measurement-phase** and none exceeds 2 (five
> 1s in run 2; 2, 1, 1, 1, 1 in run 3). Three more (all 1) fall inside run 1,
> which was sampled without timestamps and whose readings therefore **cannot
> be proven to sit in either phase**; the remaining two are a 1 on the idle box
> before certification and a 1 on the idle box at close, with no run in flight
> for either. The non-zero count is higher than the predecessor windows'
> because the sampling here is continuous rather than spaced, not because the
> box was busier.
>
> Raw driver output for all three runs is committed beside the instrument at
> `implementation/hicasso/test/re_frame/bench/hicasso/data/readprofile-07rnj/`
> (`run1.txt`, `run2.txt`, `run3.txt` — `.txt` because the repo ignores
> `*.log`). Verbatim **except for one line per file**: the `shadow-cljs -
> config:` banner, whose absolute path is replaced by `<worktree>` and marked
> inline as redacted, because the portability gate refuses a tracked personal
> home path and is right to. No figure, guard verdict or exit line was touched;
> the directory's `README.md` states the redaction, and re-running the
> reproduction above prints the banner with the reader's own checkout in it.
> Reproduction: `HICASSO_INIT_FN=re-frame.bench.hicasso.read-profile-app/-main
> HICASSO_OUT_DIR=out/hicasso-readprof node
> implementation/hicasso/test/re_frame/bench/hicasso/run.cjs`.

> **THE INSTRUMENT NOW HAS A MEASURED NULL, AND READER MEMBERSHIP STILL DOES
> NOT RESOLVE** (`rf2-3l6hf`, 2026-08-16, authored head `926dd471d3` on
> `worker/w-3l6hf`, one commit on top of the landed `29c68f6767`; instrument
> blob `ef7a0d3787`, `:advanced`, Playwright HeadlessChrome). The authored
> head is **this window's own edit half and so cannot yet be on main**; the
> landed SHA beside it is the base it sits on, and the blob hash is what
> actually identifies the instrument — it is content-addressed, so a reader
> can verify the file the runs used without resolving either commit. Three
> runs, `exit 0` on each, both arm-order guards reportable on each, the
> phase-A positive control passing on each, the phase-B residue gate never
> firing. The working tree was clean at both ends of the window, so that blob
> is the instrument every run used.
>
> **What changed, and it changed BEFORE the window opened.** The predecessor
> windows had no negative control, so a term was credited on sign stability
> across runs and nothing measured how far a delta wanders when the thing
> under it is nothing. `c-null` is `c-local` again — the same `C-FULL` mode
> through the same constructor, under a second id — so `c-local − c-null` has
> a true cost of **exactly zero by construction**. Everything it reads is the
> estimator's own error. The window shape is otherwise `rf2-07rnj`'s
> unchanged: 32 frames, 8 × (2 + 8) = 64 kept samples per arm, grid 0.0015625
> ms/commit. Nine arms rather than eight, so this is a new series and its
> absolutes are not arm-by-arm comparable with the eight-arm runs above.
>
> **The estimator, named as computed.** Every figure below is a **pooled
> median over an arm's 64 kept samples**, each divided by the 32-frame window;
> 64 being even, that median is the mean of the 32nd and 33rd order
> statistics. It is *not* a mean of per-round medians, and the two differ —
> run 1's null reads 0.0234 pooled and 0.0174 as a mean of its own per-round
> deltas. Each delta is the difference of two such pooled medians.
>
> | term (`c-local −` the arm) | run 1 | run 2 | run 3 |
> |---|---|---|---|
> | **NULL CONTROL (`c-null`), true cost exactly 0** | **+0.0234** | **+0.0219** | **+0.0234** |
> | reaction build + cache insert (`c-nosub`) | 0.4453 | 0.4531 | 0.4875 |
> | watch wiring (`c-nowatch`) | 0.0656 | 0.0672 | 0.0844 |
> | cell-map insert (`c-nomap`) | 0.0547 | 0.0625 | 0.0531 |
> | activation capture (`c-noactivate`) | 0.0453 | 0.0437 | 0.0344 |
> | **reader membership (`c-noreaders`)** | **+0.0422** | **+0.0219** | **+0.0359** |
>
> **READER MEMBERSHIP IS UNRESOLVED, and the null is what says so.** In run 2
> it read `+0.0219` — the null control's run-2 reading, to the last digit. An
> instrument that returns the same number for reader membership as it returns
> for nothing at all has not measured reader membership. Its margins over the
> null are `+0.0188 / 0.0000 / +0.0125`; the other four terms clear the null
> on every run, by `+0.4219/+0.4312/+0.4641` (reaction build),
> `+0.0422/+0.0453/+0.0610` (watch wiring), `+0.0313/+0.0406/+0.0297`
> (cell-map insert) and `+0.0219/+0.0218/+0.0110` (activation capture).
>
> **No bound on the term is published and none can be read off these
> numbers.** A null spread states what the instrument cannot see; it says
> nothing about how large the invisible thing is. Reading it as an upper
> bound would be the withdrawn `< 0.006 ms/commit` error moved one layer back,
> and the withdrawal stands.
>
> **THE NULL IS NOT CENTRED ON ZERO, which is a finding about every delta on
> this page.** Its three run-level readings — `+0.0234 / +0.0219 / +0.0234` —
> sit within one grid step of each other on a quantity whose true value is
> exactly zero. So the pooled-median delta at this shape carries a positive
> offset of roughly +0.022 ms/commit, and the small terms above are only a
> few multiples of it: taking each run's term against that run's own null,
> the activation capture is 1.5–2.0 nulls, the cell-map insert 2.3–2.9, the
> watch wiring 2.8–3.6, and reader membership 1.0–1.8. Only the reaction
> build, at 19–21 nulls, is clear of it by any margin. **The cause of the
> offset is not established here and this data does not discriminate between
> the candidates** — a residual arm-position effect (`c-local` sits at slot 1
> and every arm it is differenced against at a higher slot), within-sweep
> thermal or cache drift, and the pooled median's own behaviour on a
> right-tailed distribution are all live. The arm-order guard reporting
> *reportable* on every run does not exclude the first: its tolerance is 10%
> and this offset is 3.2–3.6% of `c-local`.
>
> **At round granularity neither term is separable from the null.** Across the
> 24 rounds of the window the null's per-round deltas span `−0.1047` to
> `+0.1578` and are positive in 17; reader membership spans `−0.1797` to
> `+0.1672` and is positive in 18. The per-round values are recorded in the
> transcripts under `:read-profile-commit-per-round` and
> `-per-round-deltas`, so this window is re-adjudicable without being
> re-taken.
>
> **What the null does NOT settle, and the reason it is one window and not
> two.** `c-null` differences slot 1 against slot 2, while reader membership
> differences slot 1 against slot 6. If the offset above is positional, a
> single null does not calibrate the reader delta exactly — nulls at several
> positions would. That was deliberately not attempted: a rung added between
> runs makes the series two instruments, which is the rule that filed this
> bead in the first place.
>
> **The box.** Processor Queue Length (`\System\Processor Queue Length`) was
> sampled on its own, five samples at 1 s, immediately before the window and
> between every pair of runs: `0,0,0,0,0` at 05:49:00, `0,0,1,0,0` at
> 05:50:17, `0,0,0,0,0` at 05:51:36, `0,0,0,0,0` at 05:52:53 (all +10:00). The
> single `1` was taken **between** runs 1 and 2 and not inside any measured
> window. **Nothing was sampled inside a run**, deliberately — sampling a
> counter inside a measured window makes the sampler part of the measurement —
> so the quietness claim here is a bracketing claim and nothing stronger. Each
> run's own `run.cjs` rebuilds the `:advanced` bundle before measuring, and
> that compile saturates this box; the bracket readings sit outside those
> compiles.
>
> Raw driver output for all three runs is committed beside the instrument at
> `implementation/hicasso/test/re_frame/bench/hicasso/data/readprofile-3l6hf/`
> (`run1.txt`, `run2.txt`, `run3.txt`), verbatim except for the one
> `shadow-cljs - config:` banner line per file, whose absolute path is
> replaced by `<worktree>` and marked inline as redacted — the portability
> gate refuses a tracked personal home path. No figure, guard verdict or exit
> line was touched. That directory's `README.md` states the redaction.
>
> **`rf2-07rnj` stays blocked.** This bead owned resolving the decomposition;
> one of its original terms still does not resolve, and a decomposition with
> an unresolved term is not one.

> **THE THREE-NULL WINDOW RAN, AND THE `+0.022` OFFSET DID NOT REPRODUCE**
> (`rf2-lo7uy`, 2026-08-17, base `3be82b04bc` on `origin/main`; instrument
> blob `c220a8c23c`, `:advanced`, Playwright HeadlessChrome 147.0.7727.15).
> Three runs — the count fixed before run 1 started, so no stopping rule could
> end the series on a convenient answer — `exit 0` on each, both arm-order
> guards reportable on each, the phase-A positive control passing on each, the
> phase-B residue gate never firing. **Nothing refused.** The window shape is
> unchanged again: 32 frames, 8 × (2 + 8) = 64 kept samples per arm, grid
> 0.0015625 ms/commit. Eleven arms rather than nine, because PR #8384 appended
> two more nulls *before* the window opened and took no measurement itself, so
> this is a new series and its absolutes are not arm-by-arm comparable with the
> nine-arm runs above.
>
> **What the window was for.** One null is one pair of slots, so `rf2-3l6hf`'s
> `c-null` could say the offset's SIZE and not its CAUSE. `c-null-twin` sits at
> slot 9, on `c-local`'s kept-sample position footprint EXACTLY; `c-null-curve`
> sits at slot 10, sharing `c-local`'s mean position on a different footprint;
> `c-null` stays at slot 2 where the published window had it. All three are
> `c-local` again through the same `mk-local`, so all three deltas have a true
> cost of exactly zero and differ in nothing but slot.
>
> **The estimator, named as computed.** Every run-level figure below is a
> difference of two **pooled medians**, each over an arm's 64 kept samples
> divided by the 32-frame window — the mean of the 32nd and 33rd *order
> statistics*, not an arithmetic mean of the samples. Where a figure is the
> arithmetic mean of the three run-level deltas, or a within-round median, it
> is called that.
>
> | null (`c-local −` the arm) | slot | footprint | run 1 | run 2 | run 3 | range |
> |---|---|---|---|---|---|---|
> | `c-null`, displaced in mean and shape | 2 | `[0 0 2 4 5 6 7 9]` | +0.0109 | −0.0188 | +0.0313 | 0.0501 |
> | **`c-null-twin`, `c-local`'s footprint exactly** | 9 | `[1 3 4 5 6 7 8 10]` | **−0.0031** | **+0.0047** | **−0.0047** | **0.0094** |
> | `c-null-curve`, same mean, other shape | 10 | `[2 3 4 5 6 7 8 9]` | −0.0281 | −0.0250 | +0.0203 | 0.0484 |
>
> **THE OFFSET WAS NOT THERE TO BE DIAGNOSED.** On the same arm pair, both arms
> at the slots the predecessor published them at, this series read `+0.0109 /
> −0.0188 / +0.0313` — sign-changing, spanning 32 grid steps. The predecessor
> read `+0.0234 / +0.0219 / +0.0234`, spanning one. The stable positive offset
> whose cause this bead exists to establish did not appear on `c-null`, on the
> twin, or on the curve. **So its cause is still not established**, and the
> honest reading is narrower than either outcome the bead anticipated: a
> `+0.022` offset stable to one grid step is not a reproducible property of
> this instrument across a change of layout.
>
> **One positive finding, with its caveats attached.** The twin is the only one
> of the eight deltas whose run-level range is small — `0.0094` against
> `0.0360`–`0.0515` for the other seven, which include both other nulls. The
> raw pooled medians say why: `c-local` read `0.5922 / 0.5875 / 0.6406` and the
> twin `0.5953 / 0.5828 / 0.6453`, rising together by `+0.0531` and `+0.0625`
> between runs 2 and 3, while `c-null` moved `+0.0031` and the curve `+0.0078`
> across the same transition. Arms sharing a position footprint co-moved; arms
> not sharing one did not. That is a position-linked component in the
> instrument's **run-level** error. But it rests on **one** of the two
> transitions three runs afford; there is **no second null pair** to corroborate
> it, because `c-local`'s footprint is shared only with the twin and every other
> footprint-sharing pair puts two *different workloads* together; and it is
> **absent at round granularity**, where the within-round p50 deltas' standard
> deviations are `0.0415` (twin), `0.0447` (`c-null`) and `0.0460` (curve) over
> the window's 24 rounds — ratios of 1.08 and 1.11, so position control removes
> essentially nothing from the round-to-round scatter.
>
> **The window also found a limit in its own rig, and did not repair it.**
> `c-null-curve`'s job is to cancel a *linear* drift by matching `c-local`'s
> mean position. That cancellation is exact for an arithmetic mean and only
> first-order true for the median this instrument actually reports: two arms
> sharing a footprint exactly have identical pooled mixtures and therefore
> identical medians for any drift whatever, but two arms sharing only the mean
> differ at second order in a term carrying the *variance* of the position
> multiset — 7.25 for `c-local` against 5.25 for the curve. **So a non-zero
> curve reading does not establish curvature, and this window concludes nothing
> about whether the drift is linear.** It is recorded rather than fixed:
> `n` feeds `lane/slot-order`, so touching the roster moves every published
> footprint and starts a third series.
>
> **What is NOT concluded.** Not that the offset is fixed or was an artefact of
> nine arms. Not that any candidate is its cause — it was not observed. Not any
> separation of a residual arm-position effect from a within-sweep thermal or
> cache drift: the twin cancels **any** function of sweep position whatever its
> physical cause, so both fall on the same side of it. Not any bound on any
> term — no null was subtracted from any term, and both prohibitions carried
> from `rf2-3l6hf` stand. Not any restatement of the terms above; the five
> ablation figures this series recorded are its own and supersede nothing.
>
> **The box.** Processor Queue Length (`\System\Processor Queue Length`) was
> sampled on its own, five samples at 1 s, immediately before the window and
> between every pair of runs on a 24-core host: `0,0,0,0,0` at 00:42:46,
> `0,0,0,0,1` at 00:45:40, `0,0,0,0,0` at 00:47:05, `0,0,0,0,0` at 00:48:32
> (all +10:00). A second counter, `% Processor Utility`, was read in the same
> brackets and never exceeded 32.3% including the sampler's own cost — two
> sources rather than one, because a headline utilisation figure can be wrong
> by a wide margin while the queue length says whether anything is actually
> waiting for a core. **Nothing was sampled inside a run**, so the quietness
> claim is a bracketing claim and nothing stronger. No open PRs and no other
> worker were in flight.
>
> Raw driver output for all three runs is committed beside the instrument at
> `implementation/hicasso/test/re_frame/bench/hicasso/data/readprofile-lo7uy/`
> (`run1.txt`, `run2.txt`, `run3.txt`), verbatim except for the one
> `shadow-cljs - config:` banner line per file, whose absolute path is replaced
> by `<worktree>` and marked inline as redacted — the portability gate refuses
> a tracked personal home path. No figure, guard verdict or exit line was
> touched, and the line counts are unchanged. That directory's `README.md`
> states the redaction and carries the full tables.
>
> **`rf2-07rnj` stays blocked.** It is blocked on the offset's cause being
> established, and this window did not establish it.

> **THE WHOLE RE-TAKE RAN AND PUBLISHES — TWO TERMS RESOLVE, THREE DO NOT**
> (`rf2-07rnj`, 2026-08-21, base commit `3de77d3c23`; instrument **blob** hash
> `c220a8c23c44ca6e19f9cab90528d932f271a784`, identical before the window and
> after it, `:advanced`, Playwright HeadlessChrome). This is the re-take §1's
> table now carries. Three runs, `exit 0` on each, **both arm-order guards
> reportable on every run** (38 `[ok]` verdicts and no refusal on each), the
> phase-A positive control passing on every run, the phase-B residue gate never
> firing. The eleven-arm series `rf2-lo7uy` opened, so absolutes are not
> arm-by-arm comparable with the nine- and eight-arm windows above.
>
> **What was fixed BEFORE run 1, and committed before run 1 so the ordering is
> checkable rather than asserted.** The run count (three — the window does not
> extend because an answer looks unsettled), the window shape (32 frames, 8 ×
> (2 + 8) = 64 kept samples per arm, grid 0.0015625 ms/commit — the instrument's
> own shape, no gate, tolerance or knob touched), and the adjudication rule
> below. The pre-registration is the first commit of this window's branch and
> the dataset `README.md` carries it in full.
>
> **THE RULE.** Each run carries its own three nulls, each of which is `c-local`
> again through the same constructor, so each null delta has a true cost of
> **exactly zero by construction**. That run's **null spread** is the interval
> its three nulls span. A term **resolves in a run** iff its delta is strictly
> greater than the maximum of that run's three nulls; a term is **RESOLVED** iff
> it resolves in all three. Anything else is **UNRESOLVED AT THIS INSTRUMENT'S
> RESOLUTION**, published with its readings quoted, **no bound, and no share**.
> No null is subtracted from any term and no term is corrected by one.
>
> | null (`c-local −` the arm) | slot | run 1 | run 2 | run 3 |
> |---|---|---|---|---|
> | `c-null`, displaced in mean and shape | 2 | +0.0297 | +0.0141 | +0.0500 |
> | `c-null-twin`, `c-local`'s footprint exactly | 9 | +0.0031 | +0.0281 | +0.0297 |
> | `c-null-curve`, same mean, other shape | 10 | +0.0109 | +0.0172 | +0.0359 |
> | **the run's null spread** | — | **[+0.0031, +0.0297]** | **[+0.0140, +0.0281]** | **[+0.0296, +0.0500]** |
>
> **The absolutes, all eleven arms** (p50 [min–max], ms per 141-key commit):
>
> | arm | run 1 | run 2 | run 3 |
> |---|---|---|---|
> | `commit` (shipping seam) | 0.9391 [0.6156–1.3469] | 0.9781 [0.6281–1.3781] | 0.7859 [0.6500–1.3031] |
> | `c-local` (the copy) | 0.8922 [0.5969–1.2438] | 0.9187 [0.6875–1.3656] | 0.7734 [0.5906–0.9656] |
> | `c-null` | 0.8625 [0.5938–1.2406] | 0.9047 [0.6031–1.3094] | 0.7234 [0.5813–0.9250] |
> | `c-noactivate` | 0.8734 [0.6156–1.3406] | 0.9000 [0.6250–1.3813] | 0.7406 [0.5656–0.9781] |
> | `c-nowatch` | 0.8328 [0.5688–1.2844] | 0.8641 [0.5844–1.1375] | 0.7234 [0.5688–0.9812] |
> | `c-nosub` | 0.2906 [0.2031–0.4344] | 0.3031 [0.2094–0.4469] | 0.2594 [0.2031–0.3469] |
> | `c-noreaders` | 0.8766 [0.6531–1.2656] | 0.9125 [0.5938–1.2875] | 0.7234 [0.5687–0.9906] |
> | `c-nomap` | 0.8250 [0.5938–1.2125] | 0.8531 [0.5844–1.1750] | 0.6937 [0.5188–0.9063] |
> | `b-build` | 0.6891 [0.5063–1.0125] | 0.7047 [0.4844–0.9437] | 0.5797 [0.4406–0.7437] |
> | `c-null-twin` | 0.8891 [0.6062–1.2875] | 0.8906 [0.6250–1.2375] | 0.7438 [0.5719–0.9031] |
> | `c-null-curve` | 0.8812 [0.5969–1.3031] | 0.9016 [0.6375–1.2813] | 0.7375 [0.5938–1.0500] |
> | copy fidelity `c-local`/`commit` | 0.9501 | 0.9393 | 0.9841 |
>
> **THE SHARE COLUMN IS THE ROBUST STATISTIC HERE AND THE ABSOLUTES ARE THE
> FRAGILE ONE**, which is worth saying plainly because it is the opposite of how
> the two are usually read. `c-local` moved 0.7734–0.9187 across three runs of
> one binary in one session — 19% — while the reaction build's share of it moved
> 66.5–67.4%, a spread of nine tenths of one percentage point. A share divides
> two arms measured microseconds apart in the same interleave, so whatever makes
> the box faster or slower between runs largely divides out; an absolute carries
> it whole. That is the case for republishing this decomposition as shares of a
> same-run denominator, and it is also why no absolute here should be lifted out
> of this table and quoted as a property of the tree.
>
> **THE TWO RESOLVED TERMS.** The reaction build + cache insert clears its run's
> null by a factor of 10–20 on every run and is the dominant term of the commit
> half at **66.5–67.4% of `c-local`**. The cell-map insert clears on every run at
> **7.1–10.3%**. Those are the only two figures this window publishes.
>
> **THE THREE UNRESOLVED TERMS, AND NO BOUND ON ANY OF THEM.** Reader membership
> reads `+0.0156 / +0.0062 / +0.0500` against null maxima of `0.0297 / 0.0281 /
> 0.0500` — inside the spread on every run, and in run 3 exactly ON it. The
> activation capture reads `+0.0188 / +0.0187 / +0.0328` against the same maxima
> and is likewise inside on every run. **Watch wiring is the near miss and is
> reported as a miss**: it clears in runs 1 and 2 (`+0.0594` and `+0.0547`) and
> in run 3 reads `+0.0500` against a null maximum of `+0.0500` — an exact tie, at
> a shape where `c-nowatch`, `c-noreaders` and `c-null` all returned the identical
> p50 of `0.7234`. A rule fixed before the data is a rule you do not move once the
> data is in, and moving "strictly greater" to "greater or equal" for one cell is
> exactly the move the pre-registration exists to prevent. So it is published as
> unresolved with its three readings quoted and nothing else claimed.
>
> **THIS SUPERSEDES THE EARLIER WINDOWS' READING OF WHICH TERMS RESOLVE, AND THE
> DIFFERENCE IS THE TEST, NOT THE TREE.** The predecessors credited four of five
> terms on **sign stability across runs**; three nulls at three slots is a
> stricter and more honest test, and under it the activation capture and the watch
> wiring join reader membership. Nothing about the terms themselves is claimed to
> have changed — what changed is that they are now adjudicated against a measured
> zero taken in the same run rather than against their own repeatability. It also
> means `c-noactivate`'s standing is now settled from the other side: `rf2-tcffa`
> established that it is not a noise floor and must not arbitrate a window's
> width, and this window adds that it is not a resolved term either. It is an arm
> whose delta this instrument cannot separate from zero.
>
> **What is NOT concluded.** No bound on any unresolved term, in either
> direction. Not that any unresolved term is small — a term inside the null
> spread has its size left open by that fact, which is the whole content of the
> rule. Not that the window is too narrow: whether 128–256 frames would bring any
> of the three clear is a prediction, and a rung added between runs makes the
> series two instruments, so it was deliberately not attempted. Not any
> restatement of §1's render half, of §4's render-half rows, or of the `rf2-lo7uy`
> findings above; nothing was subtracted from anything.
>
> **The box.** Processor Queue Length (`\System\Processor Queue Length`) and
> `% Processor Utility` were sampled together, five samples at 1 s, on a 24-core
> host, immediately before the window and between every pair of runs. Queue:
> `0,0,0,0,0` at 10:31:46 (utility ≤ 23.6%); `0,0,0,0,2` then `0,0,0,0,0` at
> 10:35:11 and 10:35:32 (≤ 42.7% then ≤ 33.9%); `0,0,0,0,0` at 10:37:53
> (≤ 26.9%); `0,0,3,0,1` then `0,0,0,0,0` at 10:41:09 and 10:41:29 (all +10:00).
> **The two non-zero brackets are reported rather than dismissed**: each was taken
> seconds after a run's own browser and node teardown, and each settled to all
> zeroes on an immediate re-read, but nothing proves the first reading of the pair
> was teardown rather than something else. The last bracket's utility of 59.7–66%
> is this session's own tooling, which was active by then. **Nothing was sampled
> inside a run**, so the quietness claim is a bracketing claim and nothing
> stronger. One open PR on an unrelated surface and no other heavyweight worker in
> flight, derived at start-up and re-derived before the edit.
>
> Raw driver output for all three runs is committed beside the instrument at
> `implementation/hicasso/test/re_frame/bench/hicasso/data/readprofile-07rnj-retake/`
> (`run1.txt`, `run2.txt`, `run3.txt`), verbatim except for the one
> `shadow-cljs - config:` banner line per file, whose absolute path is replaced by
> `<worktree>` and marked inline as redacted — the portability gate refuses a
> tracked personal home path. No figure, guard verdict or exit line was touched;
> each file differs from the driver's own output by exactly that one line. The
> per-round p50s and per-round deltas are in the transcripts under
> `:read-profile-commit-per-round` and `-per-round-deltas`, so this window is
> re-adjudicable without being re-taken. That directory's `README.md` carries the
> pre-registration.

Micro table, over the page's own roster (ns/op): `subscribe-once` 5,284;
`compute-sub` 1,170; the raw handler invoke 227; `registrar/lookup` 67;
`frame-state-value` 266; the `call-with-frame-resolution` wrap 206; the
sub-cache peek 18; the sub-key mint 53.

**Reading it.** Three convictions and one acquittal:

1. **The render-side term is the target, and it is the substrate churn, not
   the shell.** One cold read costs 6.21 µs, of which the Hicasso shell —
   key mint, scratch push, cells probe, entry-hit compare — is 0.13 µs
   (2.1%). The rest is `subscribe-once`'s round trip: a reaction built, a
   cache entry inserted, an in-tick evict and a dispose cascade, per read,
   to reach a value the read retains nothing of. The bead's "batch the 141
   crossings" candidate dies here: the crossings' own overhead is 2%; it is
   what each crossing *does* that costs.
2. **The pure compute is 4.4× cheaper than the round trip** (`compute-sub`
   1.42 vs `subscribe-once` 6.07 µs/read in-pass), and the observation
   port's cold probe is the substrate's own sanctioned no-churn spelling of
   it.
3. **The run-shared threaded memo lost to the fresh per-read memo** — the
   v1 candidate read 2.75 µs against `probe-fresh`'s 1.42. Its own
   bookkeeping (three `swap!`s per sub against a map grown to 141 entries)
   cost more than it deduplicated, and mid-graph parent sharing — the only
   thing a threaded memo buys that a value map cannot — prices at zero on a
   page whose subs are all single-source. The landed shape is therefore
   peek → run-scoped **value map** → fresh seeded per-read memo.
4. **The commit half is the durable wiring and stays.** Its dominant term is
   the reaction build + cache insert, **66.5–67.4% of `c-local`** on
   `rf2-07rnj`'s re-take — the once-per-unique-key construction the design
   amortises over the mount, unavoidable without an escrow the state machine
   forbids. The cell-map insert is **7.1–10.3%**; batching it buys tens of
   microseconds on a 141-key mount and was declined as complexity the profile
   does not license. (The 47.8% and 4.3% this list carried until the re-take
   were the 2026-08-02 readings, taken at a quarter of the window shape and
   against a `c-local` that has since lost the index write, lost the keyword
   mint and gained the activation.) **The index write is gone from the list
   entirely, and that half of the old reading was HISTORICAL rather than
   merely stale:** `rf2-dabt3` (`383ba2d645`) retired the sub-index it priced
   — `front/sub_index.cljs` is deleted — so 8.7% priced a structure the
   runtime no longer has and there is no index write left to batch. What that
   commit put in its place, reader membership, **the re-take could not
   resolve: it sits inside the null spread on all three runs, and no figure
   and no bound is offered for it** — nor for the watch wiring or the
   activation capture, which the same rule leaves unresolved. (The
   republished table at the top of this section carries every term with its
   own verdict, and the window note under it carries the rule.)

## 2. The cheapening — the cold read becomes the port's probe

All in `arm1/runtime.cljs` (`cold-read!`, consumed by `read-key!`'s cold
branch); Surface B untouched; no compiler, no analyzer, no candidate ledger,
no ViewCell graph; reagent-slim's codec untouched; the shell's 2 hooks
untouched. The cold read is rebuilt on
`re-frame.substrate.observation/probe`'s own discipline (Spec 006 §The
slice-scoped probe memo) — consumed, not reinvented:

1. **A live sub-cache reaction is reused by deref alone** — the acquire /
   release round trip `subscribe-once` performed to reach the same deref is
   gone; single-threaded CLJS is what makes the unguarded deref safe.
2. **A truly cold key computes PURE** — `subs/compute-sub-with-memo` against
   ONE coherent frame-state snapshot minted lazily on the run's first cold
   read and reset by every `run-once` (so a fence re-run or a StrictMode
   double-invoke computes against the state current THEN). Each compute
   threads a fresh per-read memo seeded with `subs/observation-opts-key`, so
   an unregistered query emits the always-on `:rf.error/no-such-sub` exactly
   as the reactive build does; run-level dedup lives in the box's value map
   (`find`, so a memoised nil is a hit and the one-emission-per-distinct-
   unknown-key contract rides on it). No cache entry, no ref-count, no
   watch, no disposal obligation — the probe mutates strictly less than the
   `subscribe-once` it replaces, which transiently inserted and evicted a
   cache entry per read.
3. **A missing or destroyed frame falls back to `subscribe-once`**, keeping
   the predecessor's whole `:rf.error/frame-destroyed` recovery.

Each compute sits inside `live-frame/call-with-frame-resolution` for the two
reasons `subscribe` itself does: image-loaded frames must resolve the
registrar through their own image, and the wrapper's read-time coalesced
flush is what makes a same-tick `reg-sub` deterministically visible to the
very next read.

**What changed on purpose, stated so nobody rediscovers it as a bug:**
within ONE body run a cold key computes once and every cold read observes one
frame-state snapshot. The predecessor recomputed per read against the live
frame — a difference observable only through an impure sub body (pure by
contract) or across a mid-body commit, where the generation fence re-runs
the body either way (`the-fence-sees-a-mid-body-move-of-a-key-nothing-holds`
stays green over the probe). The layered-consumer trade is stated in the
runtime docstring: a run-shared memo would dedup mid-graph parents across
cold reads and the profile priced its bookkeeping above its dedup on this
shape; re-openable the day a deep-shared-chain shape prices it the other way.

**Prior art, taken and declined.**

- **The observation port** (`re-frame.substrate.observation/probe`): the
  live-node deref-only read, the frame-state cold compute, the seeded memo's
  error contract — taken wholesale; this change is that discipline consumed
  at Hicasso's cold branch.
- **Reagent** (deref-capture): the floor named by the bead — a first deref
  inside a watched computation IS the registration, so a cold read pays no
  acquire/release round trip at all and near-zero time beyond the compute.
  Unreachable here rather than declined: deref-capture needs a per-boundary
  reactive context object (RAtom/Reaction watcher graph) — the ViewCell-class
  machinery this arm's fences exclude. The probe reaches the same
  no-round-trip property by computing pure instead of registering; what
  Reagent gets for its graph that the probe forgoes is reuse of the computed
  value INTO the commit, which is the escrow this arm already declined
  (rf2-2rtt6.25; the state machine forbids render-phase ref-count mutation).
- **UIx / the shipping React-hook spine**: pays per-hook
  (`useSyncExternalStore` per read site) and avoided the double build with a
  render-phase escrow — RETRACTED on the public mount path (the reaper beats
  React's passive subscribe; the walk page's §coldmount records it), so on
  real mounts that spine still pays 2 constructions per cold read. Declined:
  the escrow is a render-phase ref-count mutation, the one thing this arm's
  state machine forbids — the probe removes the render-phase construction
  instead of escrowing it.
- **Freehand / re-frame.ui**: answers first-read cost at compile time — the
  compiled tier's sites bind through the observation port with slice-scoped
  memo sharing (`make-slice-memo`). Taken in spirit (the same port
  discipline), minus the compiler that plans the sites (fence).

## 3. Correctness — witnesses and the mutation ledger

The full suite: **12,372 tests / 61,796 assertions, 0 failures, 0 errors**
(`npm run test:cljs`, exit 0) over the probe, including the five prior
wiring-fix suites re-witnessed green: the staged-read tear
(`staged_read_tear_cljs_test` — the probe touches neither `make-snapshot`
nor the basis arithmetic), the deferred-read escape and the map-key crossing
(`deferred_read_…`, `boundary_crossing_…` — codec-side, untouched), the
disposed cell and the first registration (`disposed_cell_…`,
`first_registration_…` — both drive their repairs THROUGH the cold path this
change rebuilt). New witnesses (`arm1/cold_read_cljs_test`, 6 tests / 20
assertions) pin the probe's own contract, and each was proven able to go red
by mutating the code it guards:

| mutation | expected failure | observed |
|---|---|---|
| M1 — drop the memo's `observation-opts-key` seed | `a-cold-unregistered-read-emits-no-such-sub-once…` | **RED** — 2 failures (no emission, no attribution); green on restore |
| M2 — drop `run-once`'s probe-box reset | `a-later-render-computes-against-the-current-db` | **RED** — 2 failures (the stale snapshot answered 1 where the db said 2); green on restore |
| M3 — dead-key the sub-cache peek | `a-live-sub-cache-reaction-is-reused-without-recompute-or-churn` | **RED** — 1 failure (the held reaction's key recomputed); green on restore. `one-run-computes…` stays green under M3 — the value map catches what the peek no longer does, so the two rungs are separately witnessed |
| M4 — dead-key the run-scoped value map | `one-run-computes-a-cold-key-once…` + the emission dedup | **RED** — 2 failures (two computes, two emissions); green on restore |
| M5 — bypass `call-with-frame-resolution` | `a-same-tick-registration-is-visible…` | **GREEN by design** — on a non-image-divergent frame the unbound registrar lookup falls through to the absence-is-default atom path, which sees a same-tick registration directly; the wrapper is image-resolution parity with `subscribe` itself, unreachable from this suite, and kept for exactly that parity |

M5 is reported although it did not go red, because a mutation table that
lists only its reds is an advertisement (the walk page's M2 sets the
precedent).

One instrument note recorded for the next worker: two of the mutation cycles
initially adjudicated against a **stale compiled artifact** — a same-size
source swap that shadow-cljs's cache served unrecompiled, and a failed
compile whose non-zero state still ran the previous bundle. Both were caught
by re-running with a size-changing tracer and by gating every mutation run
on the literal `Build completed` line; the ledger above is from verified
builds only.

## 4. The in-process A/B — the same instrument, re-run at the landed commit

The instrument's `local` arm is deliberately the FROZEN pre-change path, so
the re-run at the landed commit is a before/after in one process (commit
`0c0ff21f0d`, exit 0, guard clean on every arm of both phases, control
predicted 1.100 measured 1.113 [0.987–1.825] PASS). `0c0ff21f0d` is an authored
head on the same rebase-merged branch as §1's; **it landed on main as
`aeab4bbd0d`**, which recovers the measured read path in full (Provenance), and
that is the SHA to check these rows against:

| arm | ms per 141-read pass, p50 [min–max] | µs/read |
|---|---|---|
| old cold read (`local`, frozen copy) | 0.5875 [0.4750 – 1.0000] | 4.17 |
| **new cold read (`ship`)** | **0.2875 [0.2375 – 0.6250]** | **2.04** |
| the probe alone (`probe`, no shell) | 0.2500 [0.1875 – 0.4750] | 1.77 |
| bare compute lower bound (`probe-fresh`) | 0.1250 | 0.89 |
| raw handler floor (`floor`) | 0.0250 | 0.18 |
| warm steady-state (`warm`) | 0.0500 | 0.35 |

**0.49× the old cold read in-process — a 51% cut of the render-side term**
(this run's box was globally faster than the before-run's — `local` itself
read 0.59 vs 0.875 — which is why the in-process ratio is the quoted figure
and the cross-run one is not). The shipping path sits 0.27 µs/read above the
bare probe arm — the Hicasso shell plus the door, the same 2–6% the before
profile priced. What remains of the read term is 90% the substrate's own
pure-compute machinery (`probe − floor`: the per-read `compute-sub-with-memo`
walk, the fresh seeded memo, the resolution wrap at 0.17 µs, the record
resolve and peek), and the honest next rung — 0.89 µs/read — belongs to
`compute-sub*`'s own bookkeeping, not to anything this arm may fork within
its fences. The commit half re-read 0.7625 ms [0.5750–1.0250] with the same
decomposition as before (reaction build + cache insert 51.9%, index write
5.6%, watch wiring 3.7%, cell-map insert on the quantum), and stays as
designed: the durable wiring, amortised over the mount. That re-read is
`0c0ff21f0d`'s and carries §1's staleness for the same reason — the per-cell
keyword mint was still on both arms — so it is an upper bound and its shares
lower bounds, by the same about-one-quantum term. **`rf2-d360z`'s quiet-box
re-take ran on 2026-08-15 at `2c95c22386`, three times, `exit 0` and controls
clean on every run, and declined to restate this figure** — the `commit` arm's
own same-session dispersion is four grid steps against a prize an eighth of one,
and the arm has since lost the index write and gained the activation, so it is
no longer the arm 0.7625 priced. The evidence is under §1's table and is not
repeated here. 0.7625 and its 51.9 / 5.6 / 3.7% shares therefore stand exactly
as they are, and the `index write` share among them names a structure
`rf2-dabt3` has since deleted.

**And they are now superseded as a description of this tree.** `rf2-07rnj`'s
re-take ran on 2026-08-21 and republished the commit-half decomposition with
every arm taken at ONE commit (§1): the reaction build is 66.5–67.4% of
`c-local` and the cell-map insert 7.1–10.3%, while the watch wiring — 3.7%
here — is one of three terms that re-take could not separate from its own
measured null, and for which it publishes no figure and no bound. So 0.7625 and
its 51.9 / 5.6 / 3.7% stand as the historical reading they are, taken at a
window a quarter the width; the figures to carry forward are §1's.

## 5. The parity gap the re-take tripped over — found, bisected, repaired

The first re-take attempt never reached a clock: the census driver's boot
parity gate refused every row with `:canonical-dom-disagreement` for the
`:hicasso` arm. A diff probe (`parity_probe_app.cljs`, committed as lane
tooling) showed the exact divergence — hicasso rendered `href="/profile/…"`
where the uix/reagent/floor twins carry the census's `href="#/profile/…"`,
207 bytes of missing `#` on the 207-link acceptance page — and a bisect run
with `read-key!`'s cold branch toggled back to `subscribe-once` reproduced
the disagreement byte-for-byte, so **the probe is not the cause**.

The cause is a gap rf2-2rtt6.54 left: the migration moved Hicasso's anchors
onto routing's `link-model`, whose strategy consult defaults to the HISTORY
strategy when a frame declares none — path-form hrefs — while the
hand-ported twins kept the census markup's hash form. PR #7383's published
rows did not see it because they measured **pre-migration blobs** — its own
provenance table records byte-identity with `rf2-2rtt6.56`'s, which predate
the migration — and the branch was rebase-merged over the migration without
a clock re-run. From the migration's landing until this repair, the census
clock was unrunnable at main.

The repair is census-faithful and goes through routing's law rather than
around it: Conduit is a hash-URL application, so `m/make-frame!` now
declares the shipped `routing/hash-url-strategy` on the census frames, and
the router's synthesis IS the census's `#/…` form. The parity probe agrees
byte-for-byte on all three rows after it (58,474 / 250,997 / 2,636 canonical
bytes), and `route_link_dom_cljs_test`'s href expectations moved to the
hash form with the reason stated at the assertion.

## 6. The clock of record — the re-take, and what page it measured

**Before** (`rf2-y1jkm`, commit `8ccd9f4b41`, 2026-08-02T08:31Z, this box; the
authored head resolves in no fresh clone, and its landed counterpart
**`a3ffe8380e`** recovers the patch but **not this tree** — see Provenance):
the last rows on the **pre-migration** page — hand-written anchors, no
routing term. **After** (this run, commit `ac09504d74`, landed on main as
**`fd01c070a7`**, windows 2026-08-02T11:45:12Z – 11:49:12Z (`uix`)
and – 11:54:01Z (`reagent`), exit `0`, both runs to completion, arm-order
guard reportable on every row, quiet-box gate QUIET on attempt 1 or 2
everywhere): the first rows on the
**post-migration** page, whose 207 anchors are `route-link` calls through
routing's `link-model`. The before/after pair therefore carries THREE
deltas at once — the rf2-6c237 probe (an improvement, priced in-process at
§4), the rf2-2rtt6.54 route-link term (a regression the twins do not pay),
and the hash-strategy declaration (§5, byte-parity only) — and the rows
below are read with that stated rather than smoothed over.

`uix` run — the gated run:

| row | floor abs p50 (tared) | hicasso abs | uix abs | **hicasso / uix** | band | **verdict vs 1.10×** |
|---|---|---|---|---|---|---|
| large-template | 13.936 ms (12.664) | 20.199 ms | 14.175 ms | **1.4759× [1.3281 – 1.6302]** (taskNet 1.0004× · in-page 2.9989×) | **6.1%** | **FAILS THE LINE** — whole range above 1.10, margin 34.2% clears the band |
| feed | 54.547 ms (51.360) | 89.537 ms | 67.850 ms | **1.3311× [1.2357 – 1.4195]** (taskNet 1.0203× · in-page 1.9980×) | **3.9%** | **FAILS THE LINE** — margin 21.0% clears the band |
| ordinary | 2.537 ms (1.713) | 3.274 ms | 2.895 ms | 1.2097× [1.0751 – 1.3274] | 10.0% | INSTRUMENT-LIMITED (straddles 1.10), and the ctl-2x failure rides the magnitude |

`reagent` run — co-instrumented, never a second gate:

| row | hicasso / uix | hicasso / reagent | uix / reagent | band |
|---|---|---|---|---|
| large-template | 1.4591× [1.2455 – 1.6205] | **1.3000× [1.2318 – 1.3820]** | 0.8927× [STRADDLES 1.0] | 4.5% |
| feed | (margin 29.8% above the line) | 1.3813× [1.3009 – 1.5323] | 0.9679× [STRADDLES 1.0] | 9.1% |
| ordinary | 1.2256× [1.0336 – 1.4731] | 1.1710× | 0.9585× | 13.2%, INSTRUMENT-LIMITED + ctl fail |

**Reading it honestly.**

- **This is the quiet re-take the bead's second half asked for, and it
  resolved cleanly against the wrong question.** The y1jkm run's 13.7%
  acceptance-row band is 6.1% here (feed 3.9%); the instrument can now see
  the 1.10 boundary. What it sees is a page that changed underneath the
  comparison: the acceptance row moved 1.2409 → 1.4759 and feed 1.0875 →
  1.3311 **not because the read machinery got slower — it got twice as
  fast (§4) — but because the rf2-2rtt6.54 migration added a per-render
  routing term the twins do not pay.**
- **The attribution is in the rows' own decomposition.** taskNet reads
  1.00–1.02× on the gated pair everywhere — the gap is 100% in-page. On
  the acceptance row hicasso's in-page is 9.249 ms against uix's 3.114;
  at y1jkm (pre-migration) it was 3.300 against 1.900. A dedicated
  diagnostic (`link_term_probe_app.cljs`, guard clean, 2× control PASS)
  prices the migration's term directly: **8.21 µs per `link-model` call —
  1.70 ms per 207-link acceptance mount — of which `route-url` synthesis
  alone is 5.19 µs/link**. The remainder of the in-page growth rides the
  same migration (each anchor now carries an `on-click` intent vector
  through the codec's lowering, where the pre-migration page's anchors
  carried none) and its full decomposition is the follow-up's first job,
  not this page's claim.
- **On the like-for-like question this bead owns, the direction is the
  §4 one**: the cold-read term halved in-process (§4's same-process A/B is
  the controlled comparison; these rows are not one), and steady-state
  warm reads are untouched at 0.35 µs. The feed row's own arithmetic says
  where its move came from: 300 cards is 900 `link-model` calls per mount
  — ≥ 7.4 ms at the probe's 8.21 µs/link floor against a 21 ms in-page
  delta — while its 603 cold reads got cheaper, not dearer.
- **hicasso/reagent tells the same story from the other side**: 1.0303×
  (straddling 1.0) at y1jkm, 1.3000× here — stock Reagent's twin also
  hand-writes its anchors, so the migration term moved this ratio by the
  same mechanism.
- The uix twin (and the reagent one) now under-represent the census: the
  real Conduit app links through `ui/route-link` on those platforms too,
  so the twins dodge a cost the census's uix counterpart would pay. That
  fairness gap is rf2-2rtt6.54's to close, filed with the mayor rather
  than patched here. **Both were closed by `rf2-cno31` —
  [the route-link term page](the-route-link-render-term-priced.md)**: the
  8.21 µs is decomposed there (77% of it routing's, not Hicasso's), the
  seam call halved, and the twins now pay the routing term through the
  same two published seams the candidate's `route-link` takes. The rows
  below are superseded by that page's.

**Window discipline.** Windows announced on the bead before opening and
closed after; quiet-box gate per row: QUIET on attempt 1 everywhere except
`reagent/feed` (attempt 2). The first re-take attempt (11:20Z window) was
**aborted at the boot parity gate before any row published** — §5 is that
story — and the published run is the repaired page's.

## Provenance (the published run)

| | |
|---|---|
| **Producing commit** | `ac09504d74` on `worker/readopt-6c237` — the stamped code blobs are the commit's (the studio page and two diagnostic probe apps were uncommitted at run time; none is on the measured path). **Authored, and rebase-merged, so this SHA is on no branch and will not resolve in a fresh clone**; it landed on main as **`fd01c070a7`** (same patch — identical `git patch-id --stable`), where every blob it contributed is unchanged. The landed SHA is the one to check out; it sits on a later base, so it carries the change rather than the whole measured tree |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/shapes/census_clock_run.cjs` with `C56CLOCK_DATA_DIR` → `data/censusclock-6c237/` (the y1jkm and 2rtt6.56 datasets stay intact) |
| **Build** | `:hicasso-bench` (`--config-merge` entry swap), `:advanced`, `goog.DEBUG false`, lane cache cleared per rf2-2rtt6.20; 0 warnings |
| **Runtime** | `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), node `v24.13.0`, hardware-concurrency 24, device-memory 32 |
| **Design** | 6 rounds × 3 blocks × (4 warmup + 10 samples) per arm per row — the published shape, nothing overridden |
| **Clock / door / tare** | as the before-run: raw `TaskDuration` frame-settled, one door (`page.evaluate → C56CLOCK.sample`), plumb-tared per block |
| **Read-backs** | 0 unverified of 1,260 per uix-run row and 1,512 per reagent-run row |
| **Windows** | `uix` 2026-08-02T11:45:12Z – 11:49:12Z; `reagent` – 11:54:01Z |
| **Exit codes** | first attempt: parity REFUSAL at boot, exit 1, nothing published; published run exit `0`; arm-order guard reportable on all six row-runs |

Blob hashes, read at the producing commit — the two interventions against
the y1jkm row's blobs are exactly the two this page describes:

| file | blob |
|---|---|
| `…/arm1/runtime.cljs` | `41768e5fff2b42eaeede572d03c13c6debecefaf` **(the cold probe — this bead's change)** |
| `…/shapes/model.cljs` | `7f4043dc09aef036aab0c502748da7dcacc6d70d` **(the hash-strategy declaration — §5's repair)** |
| `…/front/codec.cljs` | `5a0b04733a33d1baa815b093f5b297e325aa6675` (byte-identical to y1jkm's after-blob) |
| `…/shapes/{card,large_template,feed,ordinary}.cljs` | `07458921f7…` / `f575b78429…` / `589291891f…` / `a1d7005d74…` — the post-migration census pages (y1jkm's rows measured their pre-migration ancestors; see §5) |
| `…/shapes/census_clock_{arms,app,run}` | `1e38b1a7a4…` / `b077ad6a11…` / `1f2a7e1c8b…` |
| `…/arm1/lang.clj` · `…/lane.cljs` · `core …/substrate/spine.cljs` | `0151ddafb4…` · `0642815dc2…` · `ad7b19d9d8…` |

Compact datasets: `implementation/freehand/test/re_frame/bench/hicasso/data/censusclock-6c237/`.

**The other commits this page pins.** Three of its runs were taken before the
published one, at authored heads on branches this repo rebase-merged, so none
of the three is in a fresh clone; each is accompanied below by the SHA a reader
can actually check out. Every mapping holds three independent ways — same
subject and author date, the commit's own contributed blobs identical, and
identical `git patch-id --stable` — and each was separated from its neighbours
on main, because a patch-id alone can match a commit's parent and once did
(rf2-rguy1). Recovering the patch is not the same as recovering the tree, so
the last column says which was recovered.

| authored (the tree measured) | landed on main | what the landed SHA recovers |
|---|---|---|
| `cb41ee537b` — §1's two halves | `12e50c5b36` | the patch **and** the measured tree. Six sources differ across the pair; the only one the profiled build compiles at all is the controlled-input mechanism, whose whole delta there is a docstring. Everything this profile times is byte-identical |
| `0c0ff21f0d` — §4's A/B, authored on the same branch | `aeab4bbd0d` | the same, across the same six-source gap |
| `8ccd9f4b41` — §6's *before* rows (`rf2-y1jkm`'s) | `a3ffe8380e` | **the patch only.** The y1jkm branch was rebased over the rf2-2rtt6.54 migration on its way to main, so at `a3ffe8380e` the arm-1 runtime and the `card`, `model`, `ordinary` and `route_link` sources are already post-migration. The tree those rows were measured on is reachable from no ref — which is §5's finding from the other side, and exactly why they are labelled pre-migration |

## 7. How this composes with the pending memo wrapper (#7375)

Disjoint by layer, and mount-first territory is this change's whole domain.
The memo wrapper (`memoize-boundary!`) wraps the **boundary head** and
decides whether a boundary's body runs again on a parent's re-render; on a
mount it does nothing (there is no previous props map), and a mount is
exactly where the cold read lives — a boundary's first run, before its cells
exist. On a bailed-out re-render the wrapper stops the body before any read
runs, cold or warm; on a non-bailed re-render the reads are warm cell derefs
the probe never touches. No shared state and no shared text: the wrapper
lives in `front/codec.cljs` on the boundary-head path; this change lives in
`arm1/runtime.cljs`'s read path. The held PR rebases over this with no
textual meeting point at all.

## 8. The hook budget, and the fences walked

- ≤2-hook shell: untouched — the change is entirely inside `read-key!`'s
  cold branch; `hook_budget_dom_cljs_test` green in the suite run.
- `subscribe` closes over the read set alone: untouched — the probe box
  lives on the runtime's one module-level render-state object, reset per
  body run exactly as the scratch is; nothing new is per-boundary.
- No compiler, no analyzer, no candidate ledger, no ViewCell graph: the
  probe box is one JS object per cold body run holding a snapshot and a
  value map — nothing keyed by render attempt, no per-read object, no
  commit-phase deref.
- reagent-slim's codec: untouched.
- The tripwire strengthened rather than walked: `subscribe-once` transiently
  mutated the sub-cache (insert + evict per read); the probe mutates nothing
  global at all on the cold path.

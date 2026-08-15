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

**The commit half** (per 141-key boundary commit through the runtime's own
`commit-boundary!` seam; four identically-seeded frames per window; released
and settled between samples behind a residue equality gate that never fired):

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
   the reaction build + cache insert (47.8%) — the once-per-unique-key
   construction the design amortises over the mount, unavoidable without an
   escrow the state machine forbids. The index write is 8.7% (~0.5 µs/key)
   and the cell-map insert 4.3%; batching either buys tens of microseconds
   on a 141-key mount and was declined as complexity the profile does not
   license.

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

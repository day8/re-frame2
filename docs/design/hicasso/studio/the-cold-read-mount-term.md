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

Bead **`rf2-6c237`**. The standard is **`rf2-2rtt6.1`** (mount ≤ 1.10× direct
UIx-on-subs, floor-normalised, same run, raw `TaskDuration`); the before rows
are `rf2-y1jkm`'s (`the-interpreter-walk-profiled-and-cheapened.md` §4).

> **THE CANONICAL MOUNT WITNESS IS M1 AND STAYS M1.** These rows corroborate
> the amendment's line on census-real screens exactly as the walk page's did;
> nothing here re-baselines the canonical witness, and the ruling on any
> verdict below is the operator's (`rf2-2rtt6.1`), never this page's.

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

**The render half** (before, commit `cb41ee537b` — authored, and rebase-merged,
so it resolves in no fresh clone; it landed on main as **`12e50c5b36`**, which
recovers the measured read path in full (Provenance); ms per 141-read pass, p50
over 60 samples; µs per read in brackets):

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
| whole commit (`commit`, shipping seam) | 0.8875 [0.8000–1.0750] | — (6.29 µs/key; copy fidelity c-local/commit = 0.9718) |
| — **reaction build + cache insert** (`c-local − c-nosub`) | **0.4125** | **47.8%** |
| — index write (`c-local − c-noindex`) | 0.0750 | 8.7% |
| — cell-map insert (`c-local − c-nomap`) | 0.0375 | 4.3% |
| — watch wiring (`c-local − c-nowatch`) | 0.0250 | 2.9% |
| `b-build` (141 × subscribe + deref, no in-window dispose) | 0.6000 | context: build + compute alone |

> **These rows are dated `cb41ee537b`, and one change has landed under them**
> (rf2-gttif). `rf2-aqgr2` (`f7fd0c6a52`) stopped the runtime minting a
> `Keyword` per key cell — 141 mints per commit on this shape — so **0.8875
> and 6.29 µs/key are now upper bounds** on the shipping seam, and the shares,
> which divide by `c-local` (0.8625 here), are lower bounds by the same term:
> the copy carried that mint too, until `rf2-6wh9o` (`54a2a78924`) removed it
> there as well. The term is small — 141 mints against a phase-B grid that
> resolves to 0.025 ms/commit, and the nearest anchor in the micro table below
> is 53 ns for a two-element vector — so it is about one quantum, and only a
> re-take on a quiet box settles it (`rf2-d360z`). **The four deltas do not
> move at all**: the mint was bound above `commit-local!`'s `C-NOWATCH` guard
> and present in every mode, so it stood on both sides of each and cancelled
> exactly. **Nor does the fidelity ratio** — both arms carried the mint and
> both have since lost it, so it is common-mode there too; and the copy is
> once again *structurally* identical to the seam it prices, which warrants
> the `c-*` ablations better than a clock agreement on its own ever did.

> **THE QUIET-BOX RE-TAKE WAS ATTEMPTED AND REFUSED — the figures above stand
> unchanged, still as upper bounds** (`rf2-d360z`, 2026-08-07, `abcb34217c`,
> Processor Queue Length 0 before the attempt). `read_profile_app` reaches
> phase A and stops: **both attempts threw at the phase-B residue gate, on the
> `commit` arm, with the identical counts** — baseline
> `{:cells 141 :cell-refs 141 :boundaries 1 :edges 141 :entries 6}` against a
> measured `:entries 5`. Runner exit `1` both times; nothing from phase B was
> produced, let alone published.
>
> **The cause is not the box and not this instrument.** `rf2-2rtt6.84`
> (`337b2c2fb4`, 2026-08-04) moved `arm1/runtime.cljs`'s
> `entry-reap-horizon-ms` from **0 to 4** so the entry reaper could not beat
> React back to its own passive flush on a `hydrateRoot`. `lane/settle!` is
> one macrotask — a bare `setTimeout 0` — and 4 ms is strictly outside it, so
> the two no longer interleave the way the gate's baseline assumes. Phase B's
> setup harvests four read-set entries through `rt/render-body`, each minted
> with `refs` 0 and each arming a reaper at +4 ms; the baseline `rt/residue` is
> read after ONE settle, at ~0 ms, so it counts all six entries. The reapers
> then fire during the first sampled arm, `commit` is the only arm that raises
> `refs` at all, and any entry whose reaper lands while `refs` is 0 is evicted.
> The gate compares by EQUALITY and refuses. **Both published phase-B runs
> (`cb41ee537b`, `0c0ff21f0d`, both 2026-08-02) predate that change**, which is
> exactly why §1 could record a residue gate that never fired.
>
> **The gate is right and was not touched.** What it is telling us is that the
> baseline phase B hands it is no longer reachable — an instrument-versus-
> runtime disagreement, filed as `rf2-981nt`, not something to widen.
>
> **And the prize was under the grid in any case, which the refusal does not
> change.** 141 mints at the micro table's nearest anchor of 53 ns is
> **0.0075 ms/commit against a 0.025 ms quantum — under a third of one grid
> step**, and the two absolutes this page already carries (0.8875 here, 0.7625
> in §4) sit **5 quanta apart** across two runs taken an hour apart at
> different commits. The drift is smaller than the instrument's resolution and
> far smaller than its run-to-run dispersion, so no re-take on this clock could
> have attributed a difference to `rf2-aqgr2`/`rf2-6wh9o` even had phase B run.
> A quiet box does not fix that.

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
`0c0ff21f0d` — authored on the same rebase-merged branch as §1's, and it landed
on main as **`aeab4bbd0d`**, which recovers the measured read path in full
(Provenance); exit 0, guard clean on every arm of both phases, control
predicted 1.100 measured 1.113 [0.987–1.825] PASS):

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
re-take of this figure was attempted on 2026-08-07 at `abcb34217c` and
refused twice at the phase-B residue gate before producing a number** — same
cause, same evidence, stated in full under §1's table. 0.7625 and its
51.9 / 5.6 / 3.7% shares therefore stand exactly as they are.

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
**`fd01c070a7`**, windows
2026-08-02T11:45:12Z – 11:49:12Z (`uix`) and – 11:54:01Z (`reagent`), exit
`0`, both runs to completion, arm-order guard reportable on every row,
quiet-box gate QUIET on attempt 1 or 2 everywhere): the first rows on the
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

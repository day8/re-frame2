# The Hicasso bench lane

**Moved on 2026-08-29 from `implementation/hicasso/test/re_frame/bench/hicasso` under
rf2-6c12m.1.** The tree is now `bench/hicasso/src/re_frame/bench/hicasso/`, its own
shadow-cljs project off the implementation classpath and off every per-PR lane; every path
cited under `docs/design/hicasso` before that date resolves by prefix substitution
(`implementation/hicasso/test/re_frame/bench/hicasso/` → `bench/hicasso/src/re_frame/bench/hicasso/`).
The four lane riders that used to sit beside the artefacts they measure — `p0_app`,
`p0_heap` and the rest of the P0 arms from `core/test/re_frame/bench/`, `hicasso_narrow`
and its app from `adapters/reagent/test/re_frame/bench/` — moved in with it, one level up
at `src/re_frame/bench/`. Namespaces are unchanged.

Disposition: KEEP AS EVIDENCE. This tree is the measurement harness the Hicasso
programme's numbers were taken on. It is not shipped, it is not a second implementation
you may build against, and nothing under `implementation/hicasso/src/` may import it —
which is now a compile error in every build as well as a finding in
`check_optional_module_reachability.py`'s `bench tree` row. It is also not archived: the
harness is live and hand-run.

This is the tree-level marker `rf2-hic-062` owns. `front/controlled.cljs`'s namespace
docstring carries one file's share of it (`rf2-u9o8k`, PR #8230) and points here for the
rest; 11 sibling forks stand in exactly the same relation to their package namespaces and
carry no such paragraph, which is the ambiguity this page answers once instead of 12 times.

## What is live here

Most of this tree is ordinary, maintained benchmark code, and a change to it is an
ordinary change — but it runs on NO per-PR lane, by ruling: its suites exercise the local
copies below, so running them on every PR could not catch a regression in the shipped
runtime. The gate is yours to run, from this directory, **before publishing a bench
change**:

```
npm run check       # every namespace under src/ compiled warnings-fatal, then the harness self-tests
npm run bench       # the P0 arms (run.cjs); HICASSO_INIT_FN / HICASSO_OUT_DIR select another arm
npm run ssr:bake    # the SSR spike's fixtures
```

No `npm install` here: `package.json` declares the shadow-cljs pin so the CLI does not
warn, `lane_build.cjs` spawns the CLI from `implementation/node_modules`, and
`shadow-cljs.edn`'s `:node-modules-dir` resolves react from the same place. The clock
arms, the ladders, the topology and shape suites, the SSR spike, the `*_cljs_test.cljs`
suites and the Node runners are all driven from here; every `.cjs` driver clears the
shared build cache first (`implementation/core/test/re_frame/bench/lane_cache.cjs`,
rf2-2rtt6.20), and `lane_cache_wiring.test.cjs` beside it — run by `npm run check` here
and by `test:script-helpers` in the package — holds them to that.

So do not read "keep as evidence" as "do not touch". It bounds what this tree may be used
FOR — it is where measurements are taken, not where product code is grown — and it
protects the 12 files below.

## Where the run data went

The run records the programme's numbers were taken from — 237 files, 80 MB under
`src/re_frame/bench/hicasso/data/` — were deleted from main on 2026-08-29 (rf2-6c12m.6);
git history is the archive. The full tree lives at commit `7b492b98cb`, and one command
puts it back exactly where every reader still looks:

```
git restore --source=7b492b98cb -- bench/hicasso/src/re_frame/bench/hicasso/data
```

`data/` is git-ignored, so a restored corpus (or a run record a driver has just written
there) never lands in a commit by accident. With the corpus absent, `npm run check` still
runs every self-test; the checks that re-derive a published figure from the corpus are
skipped and say so in one printed line each, and they run again the moment the tree is
restored. The handful of small records a self-test needs in its own right — a witness
record it mutates, the in-page ladder's four runs, two pre-registration declarations —
stay on main under `fixtures/`. The studio pages' provenance citations are history and
were not re-pointed; every block carries the SHA its data resolves at.

## What is frozen here, and why

`re-frame.hicasso` was copied out of this tree. 12 files were the donors, 7 under `front/`
and 5 under `arm1/`:

| Frozen donor | Package namespace it was copied into |
|---|---|
| `front/codec.cljs` | `re-frame.hicasso.impl.codec` |
| `front/controlled.cljs` | `re-frame.hicasso.impl.controlled` |
| `front/intent.cljs` | `re-frame.hicasso.impl.intent` |
| `front/presence.cljs` | `re-frame.hicasso.impl.presence` |
| `front/route_link.cljs` | `re-frame.hicasso.impl.route-link` |
| `front/slot.cljc` | `re-frame.hicasso.impl.slot` |
| `front/state.cljc` | `re-frame.hicasso.impl.state` |
| `arm1/runtime.cljs` | split into `impl.{collector,generation,frames,roots,evidence,inventory}` |
| `arm1/boundary.cljs` | `re-frame.hicasso.impl.boundary` |
| `arm1/mount.cljs` | `re-frame.hicasso.impl.mount` |
| `arm1/presence.cljs` | `re-frame.hicasso.impl.presence-react` |
| `arm1/lang.clj` | `re-frame.hicasso` |

Their divergence from the package is expected and permanent. A fix landing in the
package leaves the file here the stale copy, and back-porting it is refused: this tree is
a measured artefact — the clock rows, the ladders and the SSR spike are readings taken
from this exact source — so editing a donor to carry a facility invented afterwards would
invalidate the readings the package's own budgets are set against. `rf2-0xgk` states the
rule in one line: the package is the artefact from that commit forward.

The digest pin that used to make this checkable — `frozen-sources.edn` and
`check_freeze.py` in the package — retired with the move (rf2-6c12m.1): its last row was
`front/slot.cljc`, the package's `impl/slot.cljc` is pinned to the JVM/CLJS corpus by its
own `test/re_frame/hicasso/slot_cljs_test.cljc` instead, and the other 11 donors had
already been unpinned across three recorded retirements. Where a difference has to be
understood, read it off git rather than off a summary — for example
`git log --oneline 93ec92d491.. -- implementation/hicasso/src/re_frame/hicasso/impl/controlled.cljs`
lists the package changes `front/controlled.cljs` deliberately does not carry.

## Rules a reader can act on

1. Do not import it. `re-frame.bench` is a forbidden prefix for every file under
   `implementation/hicasso/src/`, and the tree is off that classpath in any case. Prose
   may name a bench namespace — a docstring citing the witness that pins a behaviour is
   worth more than a clean grep.
2. Do not read a donor for what ships. The live element path, runtime, mount and
   authoring macros are under `implementation/hicasso/src/re_frame/hicasso/`. Read the
   package.
3. Do not repair a donor against the package. Divergence is the freeze working.
4. A donor edit is a measurement decision. If a reading has to be re-taken on changed
   source, that is a bench decision with a record, not a drive-by fix.

## Where this sits in the wider record

Hicasso had 2 contemporaries — `re-frame.ui` and `re-frame.freehand` — and both were
retired and removed from the tree. The 3 donor surfaces and their dispositions are
recorded together at
[`docs/design/retirement/donor-surfaces.md`](../../docs/design/retirement/donor-surfaces.md).
This tree is the one that was kept.

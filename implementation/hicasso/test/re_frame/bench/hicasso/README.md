# The Hicasso bench tree

**Disposition: KEEP AS EVIDENCE.** This tree is the measurement harness the Hicasso
programme's numbers were taken on. It is not shipped, it is not a second implementation
you may build against, and nothing under `implementation/hicasso/src/` may import it.
It is also not archived: the harness is live and actively worked. Twelve files inside it
are frozen donors, and this page exists so a reader can tell which is which.

This is the tree-level marker `rf2-hic-062` owns. `front/controlled.cljs`'s namespace
docstring carries one file's share of it (`rf2-u9o8k`, PR #8230) and points here for the
rest; eleven sibling forks stand in exactly the same relation to their package
namespaces and carry no such paragraph, which is the ambiguity this page answers once
instead of twelve times.

The tree is large and still grows, so no file count is quoted here — one would be stale
within the week. The durable facts are the twelve donors named below and the single
digest-pinned row among them.

## What is live here

Most of this tree is ordinary, maintained test and benchmark code, and a change to it is
an ordinary change. The clock arms, the ladders, the topology and shape suites, the SSR
spike, the `*_cljs_test.cljs` suites and the Node runners are all driven by the package's
own scripts — `npm run bench:hicasso`, `npm run test:hicasso-compile`,
`npm run ssr:hicasso-bake`, and the roster of `.test.cjs` exit-path checks inside
`test:script-helpers` (see `implementation/package.json`). New arms land here as a matter
of course.

So do not read *keep as evidence* as *do not touch*. It bounds what this tree may be used
FOR — it is where measurements are taken, not where product code is grown — and it
protects the twelve files below.

## What is frozen here, and why

`re-frame.hicasso` was copied out of this tree. Twelve files were the donors, and the
copy's rename table is the roster —
[`frozen-sources.edn`](../../../../frozen-sources.edn)'s `:renames`, seven under `front/`
and five under `arm1/`:

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

**Their divergence from the package is expected and permanent.** A fix landing in the
package leaves the file here the stale copy, and back-porting it is refused: this tree is
a *measured* artefact — the clock rows, the ladders and the SSR spike are readings taken
from this exact source — so editing a donor to carry a facility invented afterwards would
invalidate the readings the package's own budgets are set against, to buy a digest.
`rf2-0xgk` states the rule in one line: **the package is the artefact from that commit
forward.**

The authority is executable rather than prose. `frozen-sources.edn` carries the ruling in
its header and records all three retirements that got the manifest to its present state;
`scripts/check_freeze.py` beside it is the check.

**One row remains pinned.** `check_freeze.py` reports `1 frozen row(s)` today, and it is
`front/slot.cljc` — it survives for the honest reason that it raised no refusal and so
needed no edit, not because anyone arranged for a row to remain. The other eleven are
unpinned as well as undeclared. That is the recorded outcome of three retirements, not
drift, and the manifest states what each one cost.

## Rules a reader can act on

1. **Do not import it.** `frozen-sources.edn`'s `:forbidden-import-prefixes` bars
   `re-frame.bench.` and `re-frame.freehand.` from every file under
   `implementation/hicasso/`, and `check_freeze.py`'s SEALED scan is package-wide. Prose
   may name a bench namespace — a docstring citing the witness that pins a behaviour is
   worth more than a clean grep.
2. **Do not read a donor for what ships.** The live element path, runtime, mount and
   authoring macros are under `implementation/hicasso/src/re_frame/hicasso/`. Read the
   package.
3. **Do not repair a donor against the package.** Divergence is the freeze working. Where
   a difference has to be understood, read it off git rather than off a summary — for
   example `git log --oneline 93ec92d491.. -- implementation/hicasso/src/re_frame/hicasso/impl/controlled.cljs`
   lists the package changes `front/controlled.cljs` deliberately does not carry.
4. **A donor edit is a measurement decision.** If a reading has to be re-taken on changed
   source, that is a bench decision with a record, not a drive-by fix.

## Where this sits in the wider record

Hicasso had two contemporaries — `re-frame.ui` and `re-frame.freehand` — and both were
retired and removed from the tree. The three donor surfaces and their dispositions are
recorded together at
[`docs/design/retirement/donor-surfaces.md`](../../../../../../docs/design/retirement/donor-surfaces.md).
This tree is the one that was kept.

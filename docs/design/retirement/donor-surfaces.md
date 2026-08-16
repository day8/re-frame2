# Donor view surfaces — the disposition record

re-frame2 spent a year with three overlapping view models in the tree at once. A reader
arriving today must meet **one** taught story — Hicasso, its native tier, and the
supported adapters — and every experimental donor surface must be dispositioned rather
than merely quiet. This page is that record, filed under `rf2-hic-062`.

It is short because two thirds of the question answered itself. `rf2-hic-062` was written
in Phase 6 to weigh *archive / remove / keep-as-evidence* for three trees. By the time it
was dispatched, two of the three had been **deleted**, which is a stronger disposition
than any marker and needs no argument re-run here. What this page does is record all
three in one place, with the authority for each, so nobody re-derives the scope a fourth
time.

Measured on `origin/main` at `a724be0d6d`, 2026-08-16 15:06 AUSEST.

## The three surfaces

| Donor surface | Disposition | Authority | State in the tree today |
|---|---|---|---|
| `implementation/ui/` (`re-frame.ui`, the compiled-view substrate) | **REMOVE** — executed | Operator ruling, Mike 2026-08-14 (`rf2-0yp7w`); plan at [`freehand-and-ui.md`](freehand-and-ui.md) | Gone. `git ls-files implementation/ui` returns **0** |
| `implementation/freehand/` (`re-frame.freehand`) | **REMOVE** — executed | Same ruling and plan | Gone. `git ls-files implementation/freehand` returns **0** |
| `implementation/hicasso/test/re_frame/bench/hicasso/` (the frozen Hicasso bench tree) | **KEEP AS EVIDENCE** | `implementation/hicasso/frozen-sources.edn` and `rf2-0xgk`; tree marker at the tree's own [`README.md`](../../../implementation/hicasso/test/re_frame/bench/hicasso/README.md) | Present, 220 tracked files, actively worked |

### `re-frame.ui` and Freehand — removed, not archived

PR #8322 deleted both trees on 2026-08-16 — 231 files and 376 files, every one status `D`
— together with the docs, skills and examples that taught them. The plan that ordered the
work, its census, and the reasoning behind each phase are recorded at
[`freehand-and-ui.md`](freehand-and-ui.md), and that page is the authority; nothing here
re-argues it.

Both zeros were controlled rather than trusted, because a search that returns nothing and
a search with a wrong pathspec answer in the same voice:

```
git ls-files implementation/ui implementation/freehand | wc -l   ->   0
git ls-files implementation/hicasso                    | wc -l   -> 458   (positive control)
git ls-files implementation/adapters/uix              | wc -l   ->  16   (positive control)
```

The second control matters for a second reason. `re-frame2-ui` is a **prefix of
`re-frame2-uix`**, so an unanchored search for the retired artefact over-counts the UIx
adapter, which is first-class and actively supported. Any sweep of this surface must
anchor its pattern; one written from the bare prefix would propose deleting live code.

**The `re-frame.ui` tooling-tier question is answered by the deletion.** `rf2-hic-062`
asked it as an open question — whether a fixture-only tier of `re-frame.ui` should stay
for the tools — citing the `rf2-hic-076` census. That census found the primary tool paths
donor-free, and the fixture-only remainder went with the tree. One live reference is
deliberate and stays: `tools/xray/src/day8/re_frame2_xray/mount.cljs` holds
`:rf.adapter/ui` and `:rf.adapter/freehand` in the set of adapter kinds whose `:render`
takes React elements, on the same defensive footing as the already-removed
`:rf.adapter/helix`, because a stale co-loaded build could still present the kind. That is
Xray's disposition and it is recorded in
[`../hicasso/product/tool-consumer-census.md`](../hicasso/product/tool-consumer-census.md).

Note also that `:rf.ui/*` **keys** are not `re-frame.ui`. They are the surviving tree
ABI that Spec 004B holds live spec files to, and they are not donor residue.

### The Hicasso bench tree — kept, because it is the evidence

This tree is the measurement harness the Hicasso programme's numbers were taken on: the
clock rows, the ladders, the topology tournament and the SSR spike are all readings taken
from that source. Twelve files in it are the donors `re-frame.hicasso` was copied out of,
and their divergence from the package is expected and permanent — a fix landing in the
package leaves the bench file the stale copy, and back-porting is refused because it
would invalidate the readings the package's own budgets are set against.

Deleting or archiving it would delete the evidence base. It is kept, and the tree now
carries the marker `rf2-hic-062` owed it: a
[`README.md`](../../../implementation/hicasso/test/re_frame/bench/hicasso/README.md) at
its root that names the twelve frozen donors, states the four rules a reader can act on,
and separates them from the live harness around them. That distinction is the point of a
tree-level marker: *keep as evidence* bounds what the tree may be used for, and does not
mean *do not touch* — new benchmark arms land there routinely.

`implementation/hicasso/frozen-sources.edn` is the executable authority for both halves,
and `implementation/hicasso/scripts/check_freeze.py` is its check. It reports
`1 frozen row(s)` today: only `front/slot.cljc` is still digest-pinned, the other eleven
donors having been retired across three recorded retirements rather than re-pinned. The
manifest states what each retirement cost.

## What this record does not cover

- **Spec prose.** `spec/` residue naming either retired tree belongs to `rf2-0yp7w.9`, the
  retirement's own prose sweep, and is deliberately untouched here.
- **Comment and docstring residue** left in `implementation/core/src`,
  `implementation/ssr/src`, `examples/` and `migration/reagent-to-hicasso/codemod/`, and
  the two generated-adjacent API pages `docs/api/re-frame.core.md` (its
  `:rf.adapter/freehand` roster) and `docs/api/re-frame.ssr.md` (its `re-frame.ui.tree`
  provenance). Same owner.
- **The `docs/EP/` programme records.** EP-0030 to EP-0036 are the reason the withdrawal
  is legible, and [`freehand-and-ui.md`](freehand-and-ui.md) rules explicitly that this
  work does not delete them. They are history, they are not in the published nav, and a
  reader who meets them has met history rather than a live choice.

## The taught path, swept

The published guide teaches one view story. `docs/core/views.md` sends a reader to the
supported adapters (Reagent, reagent-slim, UIx) and to Hicasso as re-frame2's own native
view layer, and names no donor; the `Hicasso: the view layer` nav section is the
twenty-three-chapter guide plus its reference, cookbook, troubleshooting and escape
ladder. `docs/core/freehand/`'s twenty-two pages went to zero on 2026-08-14 (`rf2-7cuns`)
and the site nav changed with them.

One published page still presented a retired model as a live choice when this record was
written, and it is fixed in the same change: `docs/skills/index.md`'s opening blurb
offered *"maintaining views on the retired `re-frame.ui` compiled-view substrate"* among
the reasons to reach for a skill, and both it and `skills/README.md` counted **ten**
skills against the nine that are actually in `skills/` and in the site nav — the residue
of `skills/re-frame2-ui/` going with the tree.

Both were found by an anchored sweep of the published tree:

```
git grep -cil -F freehand -- 'docs/*' ':!docs/design/*' ':!docs/tools/playground/*'
git grep -cEi -- 're[-_]frame2?[-_.]ui($|[^x[:alnum:]])' -- 'docs/*' ':!docs/design/*' ':!docs/tools/playground/*'
```

Everything else those two searches return is one of the three exclusions above, or is
`docs/release-process.md` and `docs/AUTHORING.md` stating the retirement as settled fact —
which is the record working, not residue.

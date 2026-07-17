# new-substrate-synthesis — TOMBSTONE (source-folder retirement, step 1)

**Tombstoned 2026-07-18 (rf2-mgy7pz, at the S3→S4 boundary).** This tree was the design
study for the `re-frame.ui` compiled-view substrate. Its decision surface has been
**distilled into the EP family (`docs/EP/EP-0030`…`EP-0035`) and promoted into the
normative `spec/` tree**; those are now the source of record. The tree stays force-tracked
in place until the S7 cleanup (rf2-vxgfnd.99.1), which git-rm's everything here **except
the permanent survivors** below.

This file is a pointer map, not an index — follow the graduated / relocated home for each
path. It replaces the former document index.

## Permanent survivors (kept at S7 per rf2-vxgfnd.99.1)

These three paths are durable historical evidence; the `.gitignore` force-track exception
is narrowed to them at S7:

- [`reviews/`](reviews/) — the review archive (codex1/codex2/fable1/fable2 + the staged
  guide review) **and** the relocated decision log `reviews/09-review-disposition.md`.
- [`spikes/`](spikes/) — the S-1/S-3 spike reports (feasibility evidence).
- `README.md` — this tombstone.

Everything else in this tree is a tombstone pointer: it is scheduled for deletion at S7,
after its owning stage has consumed it. Between S4 and S7, read the graduated home, not
the tombstoned source.

## Pointer map (old path → graduated / relocated home)

| Old path | Graduated / relocated home | Disposition at S7 |
|---|---|---|
| `01-goals-and-invariants.md` | `docs/EP/EP-0030-the-compiled-view-substrate-program.md` (program goals + invariants) | deleted |
| `02-programming-model.md` | `docs/EP/EP-0031-re-frame-ui-programming-model.md` + `spec/004-Views.md` | deleted |
| `03-reactivity-and-ownership.md` | `docs/EP/EP-0032-re-frame-ui-reactivity-and-ownership.md` + `spec/006-ReactiveSubstrate.md` §The internal observation port | deleted |
| `04-debugging.md` | `docs/EP/EP-0033-re-frame-ui-view-evidence.md` + `spec/009-Instrumentation.md` + `spec/004-Views.md` §View identity | deleted |
| `05-production.md` | `docs/EP/EP-0034-re-frame-ui-production-ssr-testing.md` (production posture) | deleted |
| `06-ssr-islands.md` | `docs/EP/EP-0034-re-frame-ui-production-ssr-testing.md` + `spec/011-SSR.md` | deleted |
| `07-testing.md` | `docs/EP/EP-0034-re-frame-ui-production-ssr-testing.md` + `spec/008-Testing.md` | deleted |
| `08-delivery.md` | `docs/EP/EP-0030-the-compiled-view-substrate-program.md` §The program decision record + §Stage plan | deleted |
| `09-review-disposition.md` | **relocated** → [`reviews/09-review-disposition.md`](reviews/09-review-disposition.md) (pointer left at old path) | **survivor** (under `reviews/`) |
| `10-migration-from-reagent.md` | `docs/EP/EP-0031-re-frame-ui-programming-model.md` (migration mechanics; lands with the S6 W1 migrator + the `spec/004A` Reagent-compat appendix) | deleted |
| `11-adoption-workstreams.md` | `docs/EP/EP-0030-the-compiled-view-substrate-program.md` §Stage plan (S6/S7 adoption workstreams) | deleted |
| `12-implementation-plan.md` | §1/§3/§4 remain as the handoff plan (tree-local); **§2/§2b blessed API tables graduated** → `spec/API.md` §"re-frame.ui — blessed public-surface freeze" | deleted (§2/§2b already graduated) |
| `drafts/` | diff-ready spec amendment drafts — consumed by their owning stage into `spec/004`, `spec/004A`, `spec/006`, `spec/009`, `spec/011` | deleted (after consumption) |
| `guide/` | the user tutorial — moves to `docs/guide/` at S6 (W3) | deleted (after move) |
| `prep/` | per-workstream prep tables — consumed by rf2-gria2b / rf2-nwgzha / rf2-3339ri / rf2-nojiwy | deleted (after consumption) |
| `skill/` | the skill drafts — move to `skills/` at S6 (W6) | deleted (after move) |
| `reviews/` | — | **survivor** (historical evidence) |
| `spikes/` | — | **survivor** (historical evidence) |

Owning record: `docs/EP/EP-0030-the-compiled-view-substrate-program.md` §Bead Plan
("Source-folder retirement"). Step 1 (this tombstone + the two relocations + the citation
repoint) is rf2-mgy7pz; step 2 (the S7 git-rm) is rf2-vxgfnd.99.1.

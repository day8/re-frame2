# Correction ledger — every checkpoint finding, and what it takes to close one

**Rule**: a checkpoint that finds a miss files a real `bd` issue and appends a row here. The row does not close when the fix merges — it closes when the affected checkpoint protocol section has been **re-run against the landed fix** and the result is written into the row. The final audit (rf2-hic-064) reads this file: zero unresolved correctness or coverage rows, or the audit returns a non-release verdict.

This is the enforcement home the set review names as missing — see its §6 on checkpoint design, items 1, 2 and 6; the obligations left homeless in its §7, on risks, kill rules and budgets without an enforcement home; and blocker 3 of its verdict: *"corrective beads, dormant pilots, and the donor-surface disposition have no closure path into a later release decision."* That review is `codex/beads-review.md` — review-staging material in the operator-local set, deliberately not published in this tree.

## Severity

| Severity | What it means | Effect on the release verdict |
|---|---|---|
| `correctness` | Shipped behaviour is, or may be, wrong: a witness that avoids its risk row's named scenario, a sabotage control that fails to redden, a claim the artefacts contradict. | Blocks. |
| `coverage` | An obligation has no accountable witness at all: a §13 bullet, a use-case row, a budget line with no gate, a population that was never counted. | Blocks. |
| `quality` | Behaviour holds but the shape is poor: naming, error text, docs, machinery grown past its witness. | Does not block. Must be dispositioned — fixed, filed forward with a named owner, or explicitly accepted — before rf2-hic-064 signs. |

## Status, and why `resolved` is not `closed`

| Status | Meaning |
|---|---|
| `open` | Filed. No fix has landed. |
| `resolved` | The corrective bead's PR is merged to `main`. The finding is **still unresolved for gating purposes**. |
| `closed` | The affected protocol section was re-run after the fix landed, it passed, and the evidence is in the row. |

For the rf2-hic-064 gate, *unresolved* means **any status other than `closed`**. A `resolved` row fails the gate exactly as an `open` one does. Nothing else in the programme distinguishes "we fixed it" from "we checked that we fixed it"; that distinction is this file's job.

## The closure rule

Filing a fix is not evidence that the miss is fixed, and neither is merging it. A row moves to `closed` only when all three hold:

1. the corrective bead's PR is merged to `main`;
2. **the protocol section that produced the finding is re-run** against that `main` — the section as written, not a narrower test authored by whoever wrote the fix;
3. the re-run passes, and its evidence — date, section, the landed `main` commit it ran against, what was run, the result — is written into the row's Closure evidence cell.

**Who re-runs it is never the author of the fix** — a repair checked only by whoever wrote it is a second assertion, not evidence. Cost decides only *where* the re-run happens. **Trivial** (re-read one fixture, re-run one sabotage control, re-count one roster): the ledger keeper runs it as part of the closure write described below. **Non-trivial** (a clean-checkout suite, a fresh reviewer, the physical reference profile, a whole sabotage family): the checkpoint files a **closure bead** depending on the corrective bead, dispatched to someone who did not write the fix, and the ledger row names it. A closure bead is small and mechanical — re-run one named section, write one cell.

Rows are never deleted. A finding that proves mistaken is `closed` with `withdrawn — <reason>` as its evidence, so the audit can see it was considered rather than lost.

## Who writes the row, and in which commit

Every transition after `open` records a fact about `main` that the corrective PR cannot know about itself. Before it merges it cannot show that it landed, and it cannot name the commit it will land as — this repository rebase-merges, so a branch's head commit is not the commit that reaches `main`. After it merges it is immutable, and can no longer touch the row it carried. **Corrective PRs therefore never edit this file**, which also means two fixes in flight at once cannot collide in it.

One actor writes it: the **ledger keeper** — the checkpoint that filed the row for as long as that checkpoint is still running, and afterwards the mayor, who merges the corrective PRs and is therefore the first to see them land. The handover is the checkpoint's own closure, so there is exactly one keeper at any moment.

| Transition | Who | On what evidence | Which commit carries it |
|---|---|---|---|
| — → `open` | the checkpoint | the finding | the checkpoint's report PR |
| `open` → `resolved` | the ledger keeper | the corrective PR reads merged on the remote, and its landed commit is present on the pulled `main` | a ledger commit, touching this file alone |
| `resolved` → `closed` (trivial) | the ledger keeper | the protocol section, re-run against that pulled `main` | the same ledger commit |
| `resolved` → `closed` (non-trivial) | the closure bead's worker | the protocol section, re-run against `main` in the closure bead's own checkout | the closure bead's PR |

For a trivial closure the keeper's single commit writes `closed` outright, its evidence cell carrying both facts — the landed commit, and the re-run. The row never displays `resolved`, and loses nothing by it: until that commit lands the row still reads `open` and still fails the rf2-hic-064 gate. This ledger lags `main`; it never leads it.

A re-run that fails writes its failure into the evidence cell and leaves the row at `resolved`. `closed` is never written on a failed re-run — the finding takes a fresh corrective bead, and the row names it.

## The ledger

Checkpoints append rows. Every transition after `open` is the ledger keeper's, written in the commits set out above; what moves a row to `closed` is always a re-run performed after the fix landed — the keeper's, or a closure bead's.

| Checkpoint | Protocol section | Finding | bd id | Severity | Status | Closure evidence |
|---|---|---|---|---|---|---|
| rf2-hic-019 | §3 Quality | `h/boundary` reads `:on-error`/`:reset-key`/`:fallback`/`:children` off a free-form props map with no roster and no shape check; a misspelled key and a non-vector/non-fn `:on-error` each silently report nothing | rf2-czlb | correctness | open | — |
| rf2-hic-019 | §3 Quality | `n/defcomponent`'s declaration map has no key or value roster, and the `:server` field it records is consulted by no runtime path; its witness asserts the recording | rf2-u9lk | correctness | open | — |
| rf2-hic-019 | §3 Quality | `h/defhost` silently discards every form after `opts`, and `mint-host!` never checks `opts` is a map | rf2-3f11 | correctness | open | — |
| rf2-hic-019 | §2 Correctness | Kernel risk rows 2 and 8 have no re-runnable sabotage control; every hand-run mutation cites an unnamed "PR body" | rf2-1mmn | coverage | open | — |
| rf2-hic-019 | §3 Quality | `::h/mounting` outside a presence tray's direct child is a silent no-op and leaks to the DOM | rf2-34a7 | quality (see that bead's severity note) | open | — |

## Worked example (dry run)

Illustrative only; `rf2-hic-9001` and `rf2-hic-9002` are not filed issues, and `<landed-commit>` below stands in for the commit on `main` a real row would carry. rf2-hic-019 §2 Correctness re-runs the eight sabotage controls and finds the callback-identity control still passes with the sabotage applied — the witness never reaches the retained-host path.

| Step | What happens | Status | Closure evidence |
|---|---|---|---|
| 1. File | rf2-hic-019 runs `bd create` for `rf2-hic-9001` (surface fence and acceptance in its description) and appends the row. | `open` | — |
| 2. Fix, then record | The corrective worker rewrites the witness so the sabotage reddens; its PR touches the witness, never this file. Once it reads merged on the remote, the ledger keeper pulls `main` and flips the row in a ledger commit. | `resolved` | landed on `main`@`<landed-commit>`; re-run outstanding |
| 3. Closure re-run | Re-running all eight controls from a clean checkout is non-trivial, so rf2-hic-019 had filed closure bead `rf2-hic-9002` depending on `rf2-hic-9001`. It re-runs §2 Correctness against `main`. | `resolved` | re-run in progress (rf2-hic-9002) |
| 4. Close | All eight controls redden. The result goes into the row. | `closed` | 2026-09-02 · §2 re-run on `main`@`<landed-commit>` via rf2-hic-9002 · 8/8 controls reddened |

Between steps 2 and 3 the fix is on `main` and `rf2-hic-9001` is closed in the tracker, yet the ledger row still reads `resolved` and rf2-hic-064 would return a non-release verdict on it. That interval is the mechanism, not an inconvenience.

Had the finding been trivial — one control rather than eight — steps 3 and 4 would collapse into step 2's ledger commit: the keeper pulls the merged `main`, re-runs that one control against it, and writes `closed` with both facts in the single cell. No closure bead, and still nobody marking their own homework, because the keeper is not the worker who wrote the fix.

## Deferred items and the release decision

Some obligations cannot close before the final audit runs. They are not corrections, and they must not vanish into a green tick. Each gets a row here naming the bead that will close it and which verdict it gates.

rf2-hic-064 emits two verdicts separately: *implementation audit complete* (achievable without pilots) and *product definition of done*. A deferred item may leave the first green while holding the second red. What it may not do is go unmentioned.

| Item | Owner bead | State | Gates | Closure path |
|---|---|---|---|---|
| Pilot applications — two, each shipping a substantial screen unaided | rf2-hic-063 | Operator-activated; blocks nothing in the engineering line | *product definition of done* — red until both pilots ship | The operator nominates; rf2-hic-063 records the friction log; every friction entry is a docs or API defect filed as its own bead. The release decision re-reads this row. |
| Sitting outcomes — the K1 amendment and K3 disposition records | rf2-hic-085 | Dormant until 2026-08-27 | *product definition of done*; any amendment re-pins rf2-hic-071's budget gates | rf2-hic-085 records the ruling and files consequence beads. A changed ceiling reopens each affected budget line as a `coverage` row above. |
| Donor-surface disposition | rf2-hic-062 | In rf2-hic-064's dependency set | *implementation audit complete* | Every experimental tree carries archive / remove / keep-as-evidence. `keep-as-evidence` is **not terminal**: it earns a row here naming the later decision it defers to, so the audit reports standing donor state instead of absorbing it. |

## Where the checkpoints read this

rf2-hic-019, rf2-hic-026, rf2-hic-038, rf2-hic-048 and rf2-hic-064 each carry the closure paragraph in their protocol: findings become real `bd` issues, the ids land here, and a landed fix closes only by re-running the affected protocol section — after the merge, by the ledger keeper or a closure bead, never inside the corrective PR. rf2-hic-064's green definition names this file directly — every §13 bullet green **and** zero unresolved correctness or coverage rows, or the audit says non-release and enumerates the misses.

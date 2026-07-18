# rf2-bbdbs2 — Mike’s bounded gate for the UI-guide register pass

## Live status

The candidate now exists, but it has not yet reached Mike’s gate.

Fresh evidence gathered 2026-07-18 AUSEST:

- Bead `rf2-bbdbs2` is P2, **IN_PROGRESS**, assigned to Mike.
- Current `origin/main` is
  `ba34f87185fa0a59a15254937478d8747f775bf6`.
- Candidate worktree:
  `<worktree-parent>/guide-register-bbdbs2` (the `re-frame2-worktrees` sibling of
  the primary checkout — see `scripts/assert-worker-worktree.sh`)
- Candidate branch: `worker/guide-register-bbdbs2`
- Candidate commit:
  `f83380421c06fcdf4d2d785675d2ff16d6908eda`
- The worktree is clean and the commit is pushed to the matching remote branch.
- There is no PR for this pass, as required.
- The branch is 60 commits behind and one commit ahead of current `origin/main`.
  Its merge base is `5e63e2088e59debe8d7c78262932c143cc761625`.
- No commit on main since that merge base changes
  `ai/findings/new-substrate-synthesis/guide/`. The required final rebase should
  therefore be mechanical, but it is still required.

This supersedes the earlier “no candidate exists” state. The remaining pre-gate work
is executional: rebase, rerun supporting gates, and present the worktree with the
promised evidence.

## The problem

The guide’s macro-structure is already right: README makes 01–07 the main track and
08–14 depth. This pass is deliberately narrower. It should make the main track teach
day-one use before host detail, separate common usage from full-power detail, and turn
important prohibitions into useful failure guidance.

The quality risk is not lack of ambition. It is over-rewrite: this tracked pre-alpha
guide changes quickly, and a broad voice pass can silently alter API meaning, shipped
versus future status, adapter disposition, or fixture ownership.

The desired posture is therefore:

- masterpiece-level truth and conceptual coherence;
- a confident, powerful tutorial with low human/AI friction;
- trust the programmer’s editorial judgment;
- verify only claims with real blast radius;
- no ritual correction round, mechanical uniformity, or prose gold plating.

## What the candidate actually changes

The candidate is one guide-only commit: 135 insertions and 66 deletions across eight
files.

| File | Tangible change |
|---|---|
| `README.md` | Clarifies that chapters open with the least needed to move and that guide 01 alone can get a counter running. |
| `01-getting-started.md` | Opens on the outcome; compresses the WeakRef/platform and scaffold setup; moves mount-claim mechanism depth to guide 12. |
| `02-views.md` | Adds a minimal `defview` before the full docstring/schema form; consolidates compile failures into one practical table. |
| `03-state.md` | Adds day-one orientation and a failure table while retaining the four-input model. |
| `04-events.md` | Rephrases list-site guidance and turns safety rules into a failure table. |
| `05-frames.md` | Names the day-one path: one frame and `frame-root`; defers multi-world detail. |
| `06-worked-app.md` | Renames “Codas” to the clearer “Two things to try next”. |
| `12-how-it-works.md` | Receives the detailed WeakRef probe and root-claim settlement material removed from guide 01. |

Guides 07–11, 13, and 14 are unchanged. No file outside the guide tree is changed.

Concrete before/after direction:

- Guide 01 previously front-loaded probe timing, ex-data, the recovery key, weak
  registry policy, quarantine, and FIFO settlement. It now gives the platform
  requirement and named failure briefly, linking the exact mechanics to guide 12.
- Guide 02 previously mixed first-use syntax with the full declaration form. It now
  shows `(ui/defview greeting …)` first and earns the docstring/options form second.
- Guides 02–04 previously scattered prohibitions through prose. The candidate groups
  each concept’s “what you write / what you see / fix” into local tables.

That is the intended register move, not a new structural rewrite.

## Independent pre-screen evidence

The candidate has a favourable objective shape:

- `git diff --name-only origin/main...HEAD` contains only the eight guide files above.
- The worktree has no uncommitted guide changes.
- Main has no intervening guide commit since the candidate’s base.
- Base and candidate both contain eight `guide:no-fixture` occurrences and nine
  `guide:target` occurrences.
- Base and candidate both contain zero future `lands S4` markers.
- The candidate does not edit guide 07’s managed-HTTP account, guide 08’s enrolled
  fixture inventory or shipped `flush-presence!` account, guide 11’s S5 boundary, or
  guides 13–14.
- The readiness-amendment paragraphs in guides 03 and 04 are outside the changed
  hunks.
- Guide 12’s per-host AST account is outside the changed hunk; the candidate only adds
  the relocated mount-claim section there.

The commit message also records the intended survival set and the exact stage/fence
counts. That is useful evidence, but Mike’s gate should be based on the final rebased
diff rather than the commit message alone.

One concrete truth edge deserves attention. Candidate guide 01 says an older host
“stops at startup”. The implementation contract is narrower and exact:

- `implementation/ui/src/re_frame/ui.cljc:96-100` says the usable `WeakRef` is probed
  once before root admission;
- `implementation/ui/src/re_frame/ui.cljc:183-185` says the gate runs before React
  allocation or live-root registration;
- `reactive.cljc:2586-2598` calls it the internal root-admission gate.

If “startup” is read as module or application startup, it changes probe timing. The
small, exact outcome is “the older host is rejected at the first root admission,
before React/root ownership mutation.” This is one local correction, not a reason to
withhold the pass.

## The exact Mike gate

Mike is not being asked to redesign the guide or choose a worker. Those decisions are
settled. His nondelegable gate is:

> Does the final rebased candidate preserve truth, delivery ownership, scope, fixture
> fences, and the main-track/depth genre contract well enough to trust the programmer’s
> register and voice choices and authorize PR creation?

Agents can prepare every objective check. Mike remains the gate because:

- the Bead explicitly says no PR until his go;
- the final balance of confidence, density, and tutorial voice is product judgment;
- go authorizes the first external review surface for a broad editorial diff;
- withholding ends this general pass rather than triggering endless prose churn.

The gate authorizes PR creation only. Normal touched-surface CI still governs landing.

## Truth and delivery baseline

Judge meaning, not textual identity.

### Readiness amendments

- `03-state.md`: `(local initial)` returns
  `[value setter atomic-updater]`; the updater applies `(f current & args)` to the
  latest host state; two-element destructuring remains valid.
- `04-events.md`: the controlled-site sync door accepts a literal event vector or a
  synchronous `ui/event` body returning a vector; `nil` dispatches nothing; another
  synchronous result is a named diagnostic.
- `04-events.md`: a component-library control takes an event prefix and appends its
  payload with `(conj prefix payload)`.
- `04-events.md`: a foreign render-phase callback or internal `ui/slot` uses
  `ui/render-fn`; committed callbacks and current-render callbacks stay distinct.

### Merged truth fixes

- `re-frame.ui` is an experimental additional substrate and the surface this guide
  teaches. Stock Reagent, UIx, and reagent-slim remain first-class and actively
  supported; only Helix is deleted.
- Conditional liveness is `(lease (when live? descriptor))`; `lease` is not legal in
  expression position.
- Guide 07’s resource → `:rf.http/managed` → uniform reply envelope → settle/render
  path and its capturing fixture remain one executable account.
- Each host analyzes to its own AST and invokes one emitter; parity detects divergence
  rather than making divergence impossible.

### Delivery ownership

- Guide 03 is mixed: shipped `sub`/`lease`, future-S3 `local`/`effect`.
- Guide 04 is mixed: event-vector data compiles now; committed dispatch, splicing, the
  sync door, and full callback semantics are S3.
- Presence, custom elements, and `ui.test/flush-presence!` are shipped S4 capabilities
  and must not regain future-S4 markers.
- re-frame.ui HTML emission, manifests, hydration, `ssr-ring`, and static rendering
  remain S5.
- `ui/->react` and Story migration remain S6.
- Guides 12–14 remain explanation/reference.

## Admission conditions

The branch exists, but Mike should not review it until the worker presents all of:

1. The real worktree and branch, open in VS Code.
2. A fetch and rebase onto then-current `origin/main`, with the new base SHA.
3. A clean, non-empty diff confined to the guide tree.
4. A one-line delta/disposition for README and chapters 01–14, including “unchanged”
   where appropriate.
5. A survival statement covering readiness amendments, adapter disposition,
   conditional lease, Guide 07, shipped S4 claims, per-host AST truth, and fixture
   annotations/inventory.
6. Green supporting gates after the final rebase:

   ```text
   python scripts/check_doc_slugs.py --synthesis-only --verbose
   cd implementation/ui
   clojure -M:test -n re-frame.ui.guide-truth-jvm-test
   ```

   The focused Clojure command must run from `implementation/ui`; from the repository
   root the runner's default test directory is wrong and can report a false-green
   zero-test result.

7. A statement that suspected baseline truth defects were filed as Beads rather than
   silently fixed.

The candidate is currently clean and pushed, but it is 60 commits behind and no
presentation has been recorded on the Bead. That makes it **nearly ready**, not yet
admitted.

## Smallest low-friction review protocol

Once admission is complete, Mike can review this candidate in about ten minutes.

### 1. Confirm the final shape — one minute

Check:

```text
git -C <worktree> rev-parse origin/main
git -C <worktree> diff --name-only origin/main --
git -C <worktree> diff --stat origin/main --
```

Expected: the same eight guide files, with no outside edit.

### 2. Review only semantic-risk hunks — four minutes

- Guide 01 ↔ guide 12: all WeakRef and mount-claim facts moved without changing probe
  timing, failure id/data/recovery, ownership, quarantine, or settlement.
- Guides 02–04: every new failure-table row is a clearer restatement of an existing
  rule, not a new promise.
- Check the specific “stops at startup” wording against the root-admission contract.

There is no reason to re-read untouched guides 07–11, 13, or 14.

### 3. Confirm delivery and fences mechanically — two minutes

- No future marker was added or removed except none expected here.
- Guides 03/04 retain section-grain mixed-stage treatment.
- Shipped S4 passages remain unmarked.
- `guide:no-fixture`, `guide:target`, and guide 08’s inventory counts match.
- No W3/S6 or docs/core surface changed.

### 4. Skim the register — three minutes

Read README and the changed openings in 01, 02, 03, and 05, then skim the new tables.
Ask only:

- Does day-one action come before machinery?
- Is the deeper path still easy to find?
- Are failures more useful without making new promises?
- Does it sound confident while remaining honest about experimental status?

Do not require identical checklists or tables in every chapter. Guides 06 and 07 are
worked tutorials; variation is appropriate.

Green gates are supporting evidence, not a substitute for this small semantic diff.

## Options and consequences

### Go as presented

Use when the final rebase and gates are complete, the objective fences pass, and the
register reads coherently.

Consequence: Mike authorizes PR creation from this candidate. Minor prose preference
does not delay it.

Copy-paste:

```text
rf2-bbdbs2 — GO AS PRESENTED.

Reviewed <branch> in <worktree> against origin/main <sha>. The guide-only diff preserves
truth, delivery ownership, fixture/scope fences, and the main-track/depth contract.
Supporting gates are green. I accept the programmer’s register/voice judgment; create
the PR and let normal touched-surface CI govern landing.
```

### One bounded correction list, then go

Use for local, enumerable defects. Each item names file/span, broken criterion, and
required outcome without prescribing decorative prose.

Consequence: the worker applies the list once; Mike rechecks only those spans. There
is no new general review.

Copy-paste:

```text
rf2-bbdbs2 — ONE BOUNDED CORRECTION LIST, THEN GO.

1. 01-getting-started.md, WeakRef paragraph — probe-timing truth — say rejection occurs
   at first root admission before React/root ownership mutation, not generically at
   application/module startup.
2. <file/span> — <broken criterion> — <required outcome>.

Apply only these items, rebase if main moved, rerun the two supporting gates, and
return only these spans for recheck. No general rewrite.
```

### Withhold go

Reserve for systemic truth, delivery, scope, fixture, or genre failure: repeated
semantic drift, broad shipped/future confusion, edits outside the guide tree, or
destruction of the main-track/depth contract.

Consequence: no PR. Concrete defects become Beads. The current guide stays, and this
Bead does not launch a second general rewrite.

Copy-paste:

```text
rf2-bbdbs2 — WITHHOLD GO.

The candidate has a systemic <truth/stage/scope/fixture/genre> breach: <evidence and
representative spans>. One bounded list cannot make the diff safe. Do not create the
PR; preserve the current guide and file the concrete defects as Beads. No second
general rewrite.
```

## Codex Recommendation

**Finish admission now, then take one bounded correction and go.**

The candidate is real, clean, pushed, tightly scoped, and directionally strong. Its
diff is exactly the sort of low-friction register pass the Bead asked for: mechanism
moves out of the on-ramp, the main path becomes obvious, and useful failure tables
replace scattered prohibitions. Objective pre-screening found no outside edit, no
intervening main guide change, no fixture-count drift, no S4-marker regression, and
no touched readiness/Guide-07/depth truth hunk.

Do not send Mike through the retired 15-minute whole-baseline checklist. Have the
worker rebase the 60-commit lag onto current main, run the two supporting gates, and
post the concise README/01–14 presentation packet. Then Mike should review only the
changed semantic-risk hunks and the register openings.

One local wording change is warranted unless the programmer can show that “startup”
is the intended root-admission term: guide 01 should say the incompatible host is
rejected at first root admission, before React allocation or ownership mutation.
That is a concrete truth correction, not prose taste. It earns the single bounded
cycle; it does not justify withholding.

After that correction and the final rebase/gates, the presumption should be **GO**.
Anything more would spend pre-alpha attention on stylistic uniformity rather than
elegance, power, truth, or programmer/AI productivity.

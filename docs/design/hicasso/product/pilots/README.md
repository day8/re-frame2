# The pilot programme — prep package

The two pilots ratified on 2026-08-11 migrate a screen each onto Hicasso from the released artefact and the published documentation alone, and the friction they hit is the evidence [specification §13](../specification.md#13-definition-of-done) asks for. This directory is the package they are run *from*: the workspace each pilot gets, the brief each pilot is given, and the log each pilot keeps.

Filed under `rf2-v04s`, executing item 2 of [`rf2-hic-063`](#what-governs-this-directory)'s ratification checklist.

**Nothing here starts a pilot.** `rf2-hic-063`'s gate — no pilot begins and no comparison programme runs before a consumer can install the artefact — is untouched by this package and now sits on `rf2-kmqx3`, which waits on a Clojars group verification deferred to 2026-09-16. What this package does is make both pilots startable the day that clears, and identical to each other when they start.

## The four pages

| Page | What it is | Read it when |
| --- | --- | --- |
| [`workspace.md`](workspace.md) | The per-pilot workspace: layout, the four project files copy-ready, the per-pilot source manifest with its behavioural baseline, and the read fence | Setting a pilot up |
| [`brief-realworld.md`](brief-realworld.md) | Pilot 1's brief — RealWorld/Conduit, the feed and the article editor | Handing the brief to the agent |
| [`brief-linearlite.md`](brief-linearlite.md) | Pilot 2's brief — LinearLite, the board and the grown card detail | Handing the brief to the agent |
| [`friction-log.md`](friction-log.md) | The friction-log format, built around the seven outcomes | Before a pilot writes its first entry, and when dispositioning a finished log |

The 2 briefs are the only pages a pilot agent ever sees. `workspace.md` is the operator's. `friction-log.md` is quoted into the brief by reference, and its blank template is copied into the workspace. But the pilot is never sent to this directory to read it, because this directory is inside the repository the pilot is blinded to.

Beside the four pages, [`baseline/`](baseline/README.md) holds the two app-owned test files that `workspace.md`'s manifest copies into `app/test/` — the behavioural baseline outcome 1 is measured against. They are fixtures the workspace compiles, not a test lane of this repository, and nothing here runs them. Added under `rf2-xkhul`.

## The blinding is a read fence, not an absence

The ratified method is a fresh agent, a separate workspace, and the released artefact plus public docs only — no repo access, no in-tree spec, no bead history. The obvious way to enforce that is for the repository simply not to be there. That is not available, and it is worth being exact about why, because the workaround is the load-bearing design decision in this package.

[`docs/core/hicasso/00-installation.md`](../../../../core/hicasso/00-installation.md) — the published page, the one a pilot is supposed to follow — tells the reader that `day8/re-frame2-hicasso` is not published, and to clone the monorepo beside their project and resolve it with `:local/root`. So a pilot following the published instructions correctly ends up with the entire repository on disk. Two further published paths do the same thing: the migration reporter that [chapter 20](../../../../core/hicasso/20-migration-from-reagent.md) opens with is run from `migration/reagent-to-hicasso/codemod` inside that checkout, and the full compatibility matrix is a design record the installation page names by repository path and tells the reader to read from a checkout.

Blinding therefore cannot mean the repository is absent. It means:

**The checkout is a build input, not a reference work — with one exception, and it is the documentation itself.** A pilot may resolve dependencies from it and may run the tools the published documentation tells it to run from it. And because there is no published site yet, the checkout's copy of those pages *is* the published documentation and the pilot reads it as such: anything under `docs/` the site builds, which is where `docs/core/hicasso/`'s 29 pages live. Reading them is not reading the checkout. That is an exception about where the documentation is kept, not about what may be studied, and everything else stands: a pilot may not read the checkout for answers — not `implementation/`, not `spec/`, not `docs/design/`, not the other examples, not the tracker, not the history. The line is between *executing* what a published page names and *browsing* what it does not. Amended under `rf2-lpfz`.

**And the fence is enforced by citation rather than by trust.** Every friction-log entry records where its answer came from, and a published page is the only source that counts as clean. An entry citing an in-tree path is not a failed pilot — it is a recorded leak, which is exactly what [`rf2-hic-069`](#what-governs-this-directory)'s fresh-reader audit needs in order to be mechanical instead of impressionistic. The log's closing attestation makes the empty case explicit too, so silence is a claim somebody signed rather than an absence of evidence.

## What the published documentation does not answer

Three gaps were found while writing the briefs, all of one class: the published happy path routes through a repository checkout at 3 separate points. They are recorded rather than filled, because filling them from in-tree knowledge is the contamination the programme exists to detect.

| # | Gap | Where it bites | Standing |
| --- | --- | --- | --- |
| G1 | No published coordinate. Installation resolves `:local/root` against a monorepo clone | Every pilot, at setup. It is what forces the read fence above | Closes when `rf2-kmqx3` cuts the first tag. The installation page's warning box goes stale the same day |
| G2 | The migration reporter — step 1 of the published migration process — runs from `migration/reagent-to-hicasso/codemod` in a checkout, and that tree is excluded from the built site | Outcome 2, immediately. A pilot cannot begin the documented process from the published site alone | Open. No bead filed: it is the same root cause as G1 and is expected to move with it |
| G3 | The compatibility matrix and upgrade policy live in [`release-policy.md`](../release-policy.md), which the site excludes. The installation page publishes a four-row summary and the substance of the upgrade promise, and points at the design record for the rest | Outcome 7, partially. The *promise* is published — no shims, a rename is a compile error, complaint ids are stable — so a pilot can act; the *matrix* is not | Open, and the mildest of the three. Recorded so a pilot's outcome-7 verdict is read against what it could actually reach |

A pilot that hits any of these should log it as ordinary friction and carry on. They are listed here so that the operator dispositioning the log can tell a gap already known from a gap the pilot discovered — the second kind is the one worth acting on.

## What governs this directory

`rf2-hic-063` carries the ratification, the method, and the gate; its notes are authoritative over anything on these pages. `rf2-hic-069` owns the fresh-reader protocol these briefs are audited against. `rf2-hic-064`'s two-pilots requirement goes green when both pilots *ship*, not when this package lands. The [correction ledger](../correction-ledger.md) keeps product definition-of-done red until they do.

Per this directory's [custody rule](../README.md#custody-and-amendment), a substantive amendment to any page here names its authorizing bead at the site.

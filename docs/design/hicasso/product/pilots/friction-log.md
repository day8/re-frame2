# The friction log

The pilot's own record, kept as the work happens. It is the programme's deliverable — the migrated screen is only the occasion for producing it.

The format is built around [the seven outcomes](#part-1-the-seven-outcomes) rather than around a generic template, because those seven are what the ratification says the pilots prove. Two things follow: the log has a section per outcome and no free-floating "notes" bucket, and an outcome that was never attempted is a distinct verdict from one that failed.

Authorized by `rf2-v04s` under [`rf2-hic-063`](README.md#what-governs-this-directory)'s ratification; the template's `Baseline:` header line, which the operator fills in at [workspace step 5b](workspace.md#assemble-a-workspace), under `rf2-xkhul`; the template's supplied `Docs read from:` answer, under `rf2-lpfz`; the checkout case in outcome 7's rule and in the attestation, under `rf2-dc0c`. The blank template is [at the end](#the-blank-template); copy that into the workspace.

## A short log is a bad sign, not a good one

**A near-empty friction log is treated as suspect, not as success.** This is `rf2-hic-063`'s own revisit trigger, and it is the same logic by which the fresh-reader protocol calls an empty defect list suspect: the likeliest explanation for a pilot that hit no friction is that the blinding leaked, not that the documentation is perfect.

So the log is not a bug report to be kept short. Every hesitation counts — a page read twice, a term that had to be looked up, a guess that happened to work. The guesses are the most valuable rows in the file, because a guess that works leaves no trace anywhere else and is exactly how a documentation gap survives a successful migration.

## What an entry is for

Every entry answers one question: **what did this cost, and where should the fix go?**

The resolution field is the instrument, and it has four values. They are ordered by how much they indict the documentation, and the ordering is the point.

| Resolution | Meaning | What it says |
| --- | --- | --- |
| `published` | The answer was in the published documentation and the pilot found it | Fine, unless it took many pages to reach — record how many; a slow find is real friction |
| `inferred` | The pilot guessed, from general React/ClojureScript knowledge or from analogy, and the guess worked | A silent documentation gap. The most under-reported and most valuable row in the file |
| `asked` | The pilot had to ask a human | **A defect by definition**, per the ratification. Every `asked` row files a bead, without exception |
| `unresolved` | The pilot gave up, worked around it, or shipped without it | The most serious, and it must not be quietly rewritten to `inferred` later |

The defect class then says where the fix belongs: `docs`, `api`, `tooling`, or `none` — `none` being the honest verdict when the friction was the pilot's own misreading and the documentation was clear. Not every entry is a defect, and a log with no `none` rows is one where the pilot was scoring rather than recording.

## Part 1: the seven outcomes

One row each, filled in as the work reaches them. `BLOCKED` is for an outcome that could not be attempted — it means something different from `FAIL` and must not be collapsed into it.

| # | Outcome | Verdict | Evidence |
| --- | --- | --- | --- |
| 1 | The app's behavioural tests are preserved across the migration | `PASS` / `FAIL` / `BLOCKED` | The test command and its captured exit code, before and after |
| 2 | The named screen is migrated | | What renders, and what the migration report said before the port |
| 3 | One post-migration feature change is made | | The change, and that it was made on the migrated screen |
| 4 | Hot reload works | | What survived a save, and what did not |
| 5 | One induced failure is diagnosed through the supported Xray path | | The fault induced, what Xray said, and whether that was enough |
| 6 | A production build succeeds | | The release command, its captured exit code, and that the built page runs |
| 7 | The pin is moved and the app upgrades across it | | The two pins, and what the move cost |

Rules that make the row mean something:

- **Quote a captured exit code, never a described one.** Redirect to a file and echo the runner's own status; a piped or remembered number is not evidence. That rule reaches what a page RENDERED as well as what a command returned: `page-check.cjs` in the workspace answers “did the screen come up, and did it have anything on it” with an exit code, so outcomes 2 and 6 quote a number rather than describe an impression. Added under `rf2-ek1a`.
- **Outcome 1 is measured twice** — the suite passing before the migration is the baseline, and without it "the tests pass" says nothing.
- **Outcome 5 induces the failure deliberately.** Pick a fault the published diagnostics chapter claims to cover, break it on purpose, and record whether the documented path actually reached it. A pilot that only diagnoses the bugs it happened to write has not tested the instrument.
- **Outcome 6 checks the built page runs**, not merely that the build exited zero. Advanced compilation removes diagnostics and warning strings, and a bundle that compiles but does not boot is the failure this outcome exists to catch.
- **Outcome 7 needs two pins**, and moving the checkout pin is what stands in for an upgrade until a release coordinate exists. Record both, and record what broke — a rename surfacing as a compile error at the call site is the promised behaviour, not a defect. **Where the run resolves the library from a checkout rather than from a released version there is no second pin, and the outcome is `BLOCKED`.** It is not simulated by repointing the checkout at a later commit: that yields a row which reads as upgrade evidence without being any, and a later counted run is read against this log. The operator applies that variant to the copied log during [assembly](workspace.md#rehearsal-runs-outcome-7-is-blocked), so the pilot meets one instruction rather than a choice. Amended under `rf2-dc0c`.

## Part 2: friction entries

One block per entry, numbered `<pilot>-F01` upward in the order they were hit. Chronological, not grouped by severity: the order is itself evidence about where a newcomer stalls.

```markdown
### P1-F07 · outcome 2 · `inferred` · `docs`

**Trying to** render the tag list inside the feed item.

**Expected** the ported view to accept a seq of children the way the source did.

**Instead** nothing rendered and no complaint was raised.

**Looked at** `06-lists-and-collections.md`, then `02-views-and-reads.md`, then
the API reference entry for `h/defview`.

**Resolved by** guessing that the seq needed splicing, from React experience
rather than from anything on the page. It worked first try.

**Bead** rf2-xxxxx
```

The fields are fixed. Three are load-bearing and are the ones a pilot will be tempted to skip:

- **Looked at** — the ordered list of published pages consulted. This is the blinding evidence as well as the navigation evidence: an entry citing anything that is not a published page is a [recorded leak](workspace.md#the-read-fence), and a `published` resolution reached on the fifth page is a finding about the site's structure even though nothing was wrong with the page that finally answered it.
- **Resolved by** — the narrative behind the resolution keyword, and where `inferred` earns its keep. Say what the guess was based on.
- **Bead** — mandatory on every `asked` row, and expected on most `unresolved` ones. `n/a` is a legitimate value elsewhere and should be used rather than left blank.

Where an entry is a straightforward documentation defect, file the bead against the page. Where it is an API or tooling defect, say so and let the operator route it — a pilot proposing a framework change is outside its brief and its proposal will be read as one more piece of friction evidence rather than as a design.

## Part 3: the blinding attestation

Signed by the pilot when the log closes.

```markdown
## Blinding attestation

Non-published sources consulted, in full:

- (none)

Checkout pin at start: <sha>
Checkout pin at end:   <sha>
```

**An empty list here is the expected value and must still be written.** The point is that somebody signed it: an absent attestation and a clean one are the same silence otherwise, and only one of them is evidence. A non-empty list is not a failed pilot — it is a pilot that reported accurately, and it lets the audit weigh the affected entries instead of discarding the whole log.

The two pin lines belong to a run that has two pins. Where the library is resolved from a checkout and outcome 7 is `BLOCKED`, they collapse to the single pin the run actually had; the operator makes that replacement when copying the template, at [assembly](workspace.md#rehearsal-runs-outcome-7-is-blocked). Leaving them as a pair asks the pilot for something the run cannot produce, and a pilot will try to produce it. Amended under `rf2-dc0c`.

## Part 4: disposition

The operator's section, written after the pilot ends and never by the pilot. Each entry gets a disposition — `fixed`, `filed`, `rejected`, or `not a defect` — with its bead. This is what `rf2-hic-063`'s acceptance means by the friction log being dispositioned, and it is the half that turns the log into work.

## The blank template

Copy from here into `<pilot-root>/FRICTION-LOG.md`.

```markdown
# Friction log — <pilot name>

Pilot:            <1 or 2>
Agent:            <identifier>
Workspace:        <pilot-root>
Checkout pin:     <sha>
Baseline:         npm test → exit <code>, with the app still on Reagent
Docs read from:   re-frame2/docs/core/hicasso/ — the checkout's copy of the
                  published documentation; there is no published site yet
Started / ended:  <date> / <date>

A near-empty log is suspect, not successful. Record every hesitation,
including the guesses that worked.

## Part 1: the seven outcomes

| # | Outcome | Verdict | Evidence |
| --- | --- | --- | --- |
| 1 | Behavioural tests preserved | | |
| 2 | Named screen migrated | | |
| 3 | One post-migration feature change | | |
| 4 | Hot reload | | |
| 5 | Induced failure diagnosed via Xray | | |
| 6 | Production build | | |
| 7 | Upgrade across the pin | | |

## Part 2: friction entries

### <P1-F01> · outcome <n> · `<published|inferred|asked|unresolved>` · `<docs|api|tooling|none>`

**Trying to**

**Expected**

**Instead**

**Looked at**

**Resolved by**

**Bead**

## Part 3: blinding attestation

Non-published sources consulted, in full:

- (none)

Checkout pin at start: <sha>
Checkout pin at end:   <sha>

## Part 4: disposition

Operator's section. Left blank by the pilot.
```

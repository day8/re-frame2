# Rubric — Story vs Storybook-class workshops (rf2-rln91)

- **Written**: 2026-09-14 10:05 AUSEST (session started 09:41:33 AUSEST). **Revised**: 2026-09-14 11:05 AUSEST at trunk `ece9b657be90e99461773b859a767acdd97662dd` — §3 gains the `sx` evidence strength, §5 replaces the community-integration cap with a measured note and defines the doorstep/substance sub-totals, §6 records the weight rule the new isolation row uses, §7 answers astra's objection to the positive control, §9 no longer excludes the sibling reports.
- **Trunk pinned**: `98e8ffe9cb339295fe2fa459900d9d9647fab015` for every measurement (re-read 09:59:57 AUSEST, unchanged); the Story-path diff from that sha to the revision sha is empty (`evidence.md` §0).
- **Companion files**: `matrix.md` (the scored rows), `evidence.md` (what was run, with outputs), `report.md` (the reading)
- **Governing text**: `ai/findings/Story/fable/prompt.md`; where it and the dispatch note differ the brief wins.

This file defines the vocabulary, the layers, the scoring, the weights and the controls so that
the matrix can be re-run by someone else at a later sha and produce a comparable number. Nothing
here is a judgement about a specific row; the judgements are in `matrix.md`.

## 1. Rows are jobs, not nouns

A row is something a person or an agent is trying to get done ("turn a bug report into a failing,
re-runnable variant"), never a feature name ("play functions", "`:script`"). The test for a
well-formed row: both tools can be asked "how do I do this here?" and both answers can be timed or
counted. Rows are grouped by the three journeys the brief names — first-hour author (A), maintainer
bug → failing variant → fix (B), agent "make the login error state match the design" (C) — plus
cross-cutting rows (X) that belong to no single journey.

A job that hides two jobs is split when the two score differently. "See many states side by side"
was one row in the first pass; it is two in the revision (A4 isolates STATE, A7 isolates the
DOCUMENT — CSS, focus, portals, timers), because Story is ahead on the first and behind on the
second and one number for both said neither.

## 2. Status vocabulary

Exactly one status per row. The status is *derived* from the two scores and the four layers by the
rules in §5, not chosen freehand, so the same evidence gives the same label on re-run.

| Status | Meaning | Derivation rule |
|---|---|---|
| **WIN** | Story does the job better than the Storybook-class answer, and the Story side is exercised or source-traced (not merely claimed). | `S > B` and Story evidence ≥ source-traced |
| **MATCH** | Both do the job at comparable quality and ceremony. | `S = B` and `S ≥ 1` |
| **GAP** | The Storybook-class tool does the job; Story does not, or does it materially worse, and Story's SPEC layer does not promise it (or promises it only as future work). | `S < B` and SPEC layer empty/future |
| **SPEC-ONLY** | Story's spec describes the job as delivered but the code at this sha does not do it, or does only part of it. | `S < B` (or `S < 2` where `B` is irrelevant) and SPEC layer present, CODE layer absent/partial |
| **UNDERMARKETED** | Story's code does the job at least as well, but the docs/README/skill/examples do not teach it — or teach it wrongly — so a first-hour author or an agent cannot find it. | CODE layer ≥ B's level, DOCS layer absent/wrong; scored at the CODE value |
| **DIVERGENT** | Story deliberately refuses the Storybook mechanism with the rationale recorded (`DESIGN-RATIONALE.md` §Rejected, `018` §3.1 "should not copy"). Judged on the job outcome, not on the mechanism. | A recorded rejection exists for the mechanism; scores stand as measured |
| **OUT** | Not scored — outside what a workshop tool controls (community size, hosting business, hiring). Listed so the reader can see it was considered. | Excluded from every denominator |
| **TARGET** | Neither side does the job well today and re-frame2's substrate gives leverage: a build candidate, not a comparison verdict. | `S ≤ 1` and `B ≤ 1`, or `S < B` with a named substrate lever; still scored as measured |

A row can carry a second flag in the Notes column (e.g. a WIN that also carries a defect) but
only one status.

## 3. Four layers per row, plus evidence strength

Each row records, for Story:

- **SPEC** — the section that promises the job, by file and heading/line (e.g. `017 §Run result`, `018 §3.1`). "—" if nothing promises it.
- **CODE** — the namespace(s)/function(s) that do it, by file and line where a single site carries it. "—" if absent.
- **DOCS** — the `docs/story/` chapter, `README`, or `skills/` leaf that teaches it. "—" if nothing teaches it; "wrong" if what teaches it is contradicted by CODE at this sha.
- **ERGONOMICS** — the ceremony count (§4) and the friction observed when exercised.

For Storybook the same four are collapsed into one cell citing the upstream doc/version read, because
this is a reading of Story against a reference, not an audit of Storybook.

**EVIDENCE-STRENGTH** (per side, recorded per row):

| Strength | Meaning |
|---|---|
| **exercised** (`ex`) | I ran it at this sha (JVM probe, story-mcp stdio session, Playwright walk, Storybook init) and the output is in `evidence.md`. |
| **sibling-exercised** (`sx`) | One of the two sibling researchers ran it at the same sha on the same machine and left a receipt I opened and checked (`evidence.md` §S2). Counts as exercised for the range; labelled so a reader can tell whose run it was. Added in the revision. |
| **source-traced** (`st`) | I read the code path that does it, with file:line, but did not execute it. |
| **documented-only** (`do`) | Only a doc/spec/upstream page claims it; nothing was read at source or run. |
| **unverified** (`un`) | Could not be checked (blocked, no browser, out of time); scored at face value and named in `report.md` §8. |

## 4. Ceremony count

The number of things the author must **type or click that are not the story's own content**, counted
from a working app to the job being done:

- each new file created (+1), each existing file touched (+1);
- each config key or alias added (+1);
- each CLI command run once (+1) — an install of N packages is one command, not N;
- each mandatory UI gesture (+1) for jobs done in the UI;
- the story/variant body itself is **free** (both tools require you to say what the state is).

Lower is better. Ceremony is recorded in ERGONOMICS and folded into the score only through the
2-point scale below (a job done at ceremony ≥ 5 cannot score 2).

## 5. Scoring

Two scores per row, `S` (Story) and `B` (Storybook-class reference), each on `{0, 0.5, 1, 1.5, 2}`:

| Score | Meaning |
|---|---|
| **2** | Does the job, low ceremony (≤ 4), no caveat that a first-hour author would trip on. |
| **1.5** | Does the job with one caveat or defect the author will meet (a stale label, a doc/code drift, a missing link). |
| **1** | Does the job with real ceremony (≥ 5), or only in some runners, or only when the author already knows an undocumented thing. |
| **0.5** | Does part of the job; the rest is manual. |
| **0** | Cannot do the job at this sha / version. |

**Reference side.** `B` is scored against the best Storybook-class answer for that job as of the
versions read on 2026-09-14 (Storybook 10.6.0 with its first-party addons; Vitest 5.0.0 browser mode
where a local screenshot comparator is the job, Vitest 3/4 where the Storybook addon is — see
`report.md` §5.1; Playwright CT 1.63; MSW 2.15; Chromatic/Percy/Argos at their public tiers; and,
where a non-Storybook tool is the clear leader for that job, that tool — named in the cell). A
community integration is scored on the job like anything else, with its install and configuration
counted in ceremony and its provenance named in the cell. `B` is scored at documented value unless a
sibling receipt exercises it (`sx`); the asymmetry that follows (more of Story than of Storybook was
executed) is stated in §7 and reported as a range, not hidden.

*The first pass capped community integrations at `B ≤ 1.5` "because they are not part of the
reference install". Astra objected that this assigns a ceiling for provenance rather than quality.
Measured before deciding: the cap touched **no cell**. Every reference 1.5 in `matrix.md` (A6, A7, B7,
C1) is a first-party mechanism scored down for a caveat named in the cell, and the one community
integration cited (`msw-storybook-addon` in B3) sits beside first-party `sb.mock` in a cell scored 2.
With the cap removed every total is unchanged and the ratio still reads 1.13. The cap is dropped
because it did no work and drew a fair objection, not because it was wrong on the merits.*

**Totals.** Unweighted total = Σ S / (2 · n) and Σ B / (2 · n) over the n scored rows (OUT excluded).
Weighted total = Σ w·S / (2 · Σ w) and Σ w·B / (2 · Σ w). Denominators are printed beside every
total in `matrix.md`.

**Two sub-totals, printed beside the totals (revision).** *Doorstep* = the journey-A rows (what a
Storybook user meets in the first session); *substance* = the B, C and X rows (what the tool does
once it is running). The point of the split is that the sentence "parity-plus on what it does,
behind on getting started" should carry two numbers, one per half, rather than one number that
averages them away. Same formula, restricted to the half.

**Parity reading.** The ratio (Σ w·S) / (Σ w·B), read with the caveat that the rows are not
independent and that one journey (A) decides adoption while another (C) is the surpass thesis. The
ratio is the last thing to read, not the first: `report.md` §1 leads with a categorical reading in
six buckets (adoption-blocking for a new user; adoption-blocking for the surpass thesis; ahead
because of re-frame2; match with a different shape; behind or unpaid; unknown), which is grok's
presentation and is better because one adoption-blocker is not offset by three matches.

**Range.** Two bounds are computed for Story only, and reported as an interval around the point
estimate:

- *pessimistic*: every Story cell whose evidence is `documented-only` loses 0.5 and every
  `unverified` cell drops to 0;
- *optimistic*: every Story cell carrying a named defect that has an S-sized (or S–M) fix in
  `report.md` §6 is scored as if fixed.

The pessimistic bound is the number to quote if the reader distrusts anything not executed. The
optimistic bound is what the cheap fixes buy; it is **not** a prediction.

## 6. Weights

Weights are `{1, 2, 3}` per row with a one-line defence recorded in `matrix.md`. Rules used to assign
them, so a re-run can check the reasoning rather than re-argue it:

- **3** — the job decides whether the tool gets adopted (first session) or is the core of the
  parity-and-surpass thesis in `018` §3.1 (executable plans, one result shape, agent-drivable plan,
  self-explaining failures).
- **2** — a job an experienced Storybook user has a "legitimate expectation" of, per the `018` §3.1
  bar list (controls, docs beside the example, status beside the example, matrices, sharing).
- **1** — a job that is valuable but neither decides adoption nor carries the thesis (recorder,
  static build, extension model, live-browser driving from an agent, document isolation).

A job Storybook does well is not thereby weight 2: the bar list is `018` §3.1's, not Storybook's
feature list. Document isolation (A7) is the instance — the file names isolation nowhere but bundle
isolation, no real user has named a CSS-isolation job, and state isolation, which does carry the
thesis, is A4 — so it weighs 1 and its GAP costs one point rather than two.

Weights say what matters to *this* project's stance — "high productivity for the programmer and
the AI they use, with excellent ergonomics and low friction" — not to the market.

## 7. Controls

Two rows are declared controls before scoring and are scored by the same rules:

- **NEGATIVE control — A1** ("install into an existing app and see a working workshop in the first
  session"). Storybook is expected to win this clearly: a first-party scaffolder, 105 s from a fresh
  Vite app. If the rubric returns anything but GAP with `S < B` here, the rubric is biased toward
  Story and every WIN must be re-read.
- **POSITIVE control — B1** ("turn a bug report into a failing, re-runnable variant using data only").
  Story is expected to win this by construction: variants are EDN with `:setup`/`:script`/`:assertions`,
  and the negative case (`:fail`) was exercised on two surfaces. If the rubric does not return WIN here,
  the scoring scale is broken.

Both controls are **exercised** on both sides (Storybook: `npx storybook@latest init` timed; play
function + `expect` read in the generated sample and, since the revision, run by astra under the
Vitest addon. Story: JVM probe and story-mcp stdio session).

**Astra's objection, and the answer.** A positive control Story wins "by construction" preselects
Story's authoring model; astra's own controls require that a known defect be DETECTED and do not
prescribe which product must win. Fair as a caution, and it does not move a score: B1 checks that the
scale returns WIN where the design makes the answer unambiguous, it is counted once, and it is
recorded as a control rather than as evidence for the thesis. The detection-style control is the
better shape for the falsification workflows in `report.md` §4.4, which is where the revision uses it.

**Known bias, stated rather than corrected.** Story cells are more often `exercised` than Storybook
cells because Story was the subject and the machine had it built; Storybook cells beyond the init
sample are `documented-only` except where astra's fixture supplies an `sx` receipt (B1, B2, B5).
Applying the pessimistic haircut symmetrically to Storybook would move its total down (the figure is
given in `matrix.md` §Totals) — it is shown, not adopted, because Storybook's documented features are
exercised daily by a large market and Story's are not yet.

## 8. Automatable rows

A row is marked *automatable* when the Story side can be re-scored by a script with no human
reading — the template is `015-Test-Coverage` style: a command, an expected shape, a pass/fail.
For each automatable row `matrix.md` names the probe (`probe.clj` case letter, `mcp-requests*.jsonl`
request id, or Playwright walk) that produced this run's cell. Rows that need a human judgement of
ergonomics are marked *manual*. Two transformation probes joined the automatable set in the revision
(promote a known-failing variant; `edn/read-string` an emitted upgrade snippet — `evidence.md` §P4,
§P5) because they are cheap and each caught a claimed surpass that was not true.

## 9. What the rubric does not measure

- Performance budgets (`018` §10.1 / `re-frame.story.budgets`) — a separate gate already exists.
- Visual quality of the shell — outside a closeness test.
- Storybook's ecosystem size, hiring pool, hosted-service economics — OUT rows.
- The three falsification workflows in `report.md` §4.4 — they are the next experiment, not a row;
  until they are run, the surplus the matrix reports is structural, not a measured ergonomic gain.
- The sibling reports' own scores. `ai/findings/Story/astra/` and `ai/findings/Story/grok/` were read
  after `report.md` was complete and again for the revision; their measurements enter as `sx`
  receipts and their arguments as edits, never as imported cells.

## 7. Added in the second sibling review (2026-09-14 14:25 AUSEST)

**Reference-generous bound.** Both siblings objected that far more of Story was executed than of the reference, so the pessimistic bound (a haircut on Story's unexecuted cells) answers only half of the asymmetry. The other half is a *benefit*: every reference cell whose evidence is `do` (documented only) and whose score is below 2 gains 0.5, capped at 2, on the reasoning that an exercised run might have earned it. Report the ratio of Story's point reading and of Story's pessimistic reading against that bound. It is a bound, not a score: it says how far the ratio can fall before the reference's documentation has been given every benefit of the doubt. At this sha it reads 1.00 and 0.98 (`matrix.md` §Totals).

**Second cut.** Beside doorstep/substance (§5), report grok's cut — workshop chrome versus application state — with the assignment rule stated in `matrix.md`. The two cuts answer different questions: doorstep/substance says *when* in a user's life the deficit bites; chrome/application-state says *what kind of thing* the surplus is made of.

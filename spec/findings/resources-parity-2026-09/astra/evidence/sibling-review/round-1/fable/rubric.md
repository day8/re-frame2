# Rubric — Resources vs TanStack-Query-class server-state libraries

Written 2026-09-14 (AUSEST), before any row was scored, per the brief at `ai/findings/Resources/fable/prompt.md`. The trunk sha the reading is pinned to is recorded in `report.md` §0. The vocabulary below is the Story research rubric's (`ai/findings/Story/fable/rubric.md`) reused unchanged, so the two corpora read with one dictionary. Later refinements are appended under a dated heading at the end; the body above that heading is the initial instrument.

## 1. Rows are jobs, not options

A row is something a programmer building an SPA does with server state — "after a write, every mounted reader of what it changed refetches, with nothing remembered at the call site" — never a competitor's noun ("`invalidateQueries`", "`select`"). If a candidate row cannot be phrased as a job with a user-visible outcome, it is folded into the job it serves or deleted. The row set is bounded to sixteen to twenty rows; more than that is litigating minutiae.

Each row records the JOB, its IMPORTANCE (§5), the STATUS with a named comparator (§2), the four LAYERS (§3), EVIDENCE STRENGTH (§3), CEREMONY (§4), WHAT INVALIDATES THE ROW (§8) and CITATIONS (`file:line` in this tree; URL plus access date outside it).

## 2. Status vocabulary (comparative)

The status is a COMPARISON against a NAMED comparator. It is not a statement about whether Resources can do the job at all; the layers in §3 carry that. One row may carry two statuses when the comparators differ materially (WIN vs TanStack, MATCH vs RTK Query).

| Status | Definition | Not to be confused with |
|---|---|---|
| `WIN` | Resources does the job at lower ceremony, more honestly (the failure explains itself; the state is not faked), or in a way the comparator cannot at all. Needs a structural reason, not a taste. | A substrate that COULD do it (that is SPEC-ONLY or a latent advantage in the thesis). |
| `MATCH` | Comparable ceremony and reliability. An experienced TanStack user would not bounce. | A MATCH at twice the ceremony, which is a GAP. |
| `GAP` | The job cannot be done, or only through undocumented internals, disproportionate ceremony, or by hand-rolling what `realworld_http` already does. | A refusal with a recorded reason and a working other way (DIVERGENT). |
| `SPEC-ONLY` | The contract exists in `spec/016-Resources.md` (or 014/012/015) but code or docs do not honestly ship it: no pinning test, or a test that stays green under a fault, or a path only the spec knows. | A capability shipped but not taught (UNDERMARKETED). |
| `UNDERMARKETED` | Shipped and pinned, but a user following `docs/resources/` and the Conduit README would not find it. | Absence from the flagship alone: a capability a capability example demonstrates is UNDERMARKETED for a Conduit-cloning author at the DOCS layer, not SPEC-ONLY. |
| `DIVERGENT` | Refused on purpose (a `016` Resolved decision, a Deferred slice with a recorded reason, or a bead close reason), AND the job is served another way that is named and works. | A refusal whose "other way" does not serve the job — that is a GAP, and the refusal is recorded beside it. |
| `OUT` | Not a Resources job. The row names where in re-frame2 the job lives (managed HTTP, a machine, a later artefact) or that it is nobody's job here. | A Deferred job a TanStack user would bounce on — that stays a GAP or TARGET. |
| `TARGET` | `016` §Deferred slices or §Open questions already require it and mark it not-yet. | A win. A TARGET is never a win, and it is not a gap in the competitor's favour until the bead that lands it is checked. |

Rules: a Deferred feature stays in the comparison when the job matters; the trade-off is recorded, never disappeared. A refusal is never a silent absence. A DIVERGENT names the other way. A WIN names why the comparator cannot do it as cheaply, and survives the "challenge the caricature" check (the comparator's own docs were read).

## 3. Four layers, evidence strength, and the artefact a verdict came from

Every row carries four layer cells, because a single status hides the interesting disagreements:

- **SPEC** — is the contract in `016` (or `014` transport, `012` routing, `015` classification)? Cite the section.
- **CODE** — does `implementation/resources/src` implement it, and what pins it: a test in `implementation/resources/test/` (deftest named), a Conduit test under `implementation/adapters/reagent/test/`, or a conformance fixture under `spec/conformance/fixtures/`? A fixture pins the CONTRACT across implementations; it is not stronger evidence of behaviour than a relevant executable test.
- **DOCS** — can `docs/resources/` plus the Conduit README get a new user there, and does the FLAGSHIP (`examples/real-apps/realworld_resources/`) exercise it? If only a capability example does, say which.
- **ERGONOMICS** — is it the path of least resistance, or a power-user back door? Count ceremony (§4).

Beside the status, EVIDENCE STRENGTH, one of: `exercised` (run in this study — a probe, a test under a deliberate fault, a browser walk, a request ledger), `source-traced` (read in code with file:line), `documented-only` (a docs or spec sentence), `unverified`. An unavailable browser, backend or credential makes a row `unverified`, never a pass and never ABSENT on capability.

Every verdict names the artefact it came from: code establishes present behaviour; the spec establishes the promised contract; the example establishes only what it exercises; the docs establish what is taught. Where the four disagree, the row says so — those disagreements are the most useful output of the matrix.

Attribution: for every capability the row says whether Resources, managed HTTP, the router, SSR, Xray, an adapter, the fake backend or application glue supplies it.

## 4. Ceremony count

Counted for the FIRST read (from an empty project, following the tutorial) and for the TENTH read and write (adding one more to the established Conduit), on both sides, without presuming either wins either case:

- concepts the author must hold (list them, count them);
- registrations and declarations they must write;
- files they must touch, and how widely one change is spread (registration, cause, subscription, view);
- boot requires and configuration they must remember;
- repeated declarations, duplicated state, manual cache coordination;
- context switches and wrong turns on the executed journeys.

One-time setup is separated from marginal feature cost. Twice as many declarations does not by itself prove twice the effort; the count is reported beside what each declaration buys. A MATCH at twice the ceremony is recorded as a GAP. "The programmer might misuse X" is not a ceremony cost against the comparator unless Resources catches the misuse, in which case the catch is tested.

## 5. Importance and the headline

Each row is marked `essential`, `important` or `optional`, with a one-line reason grounded in ordinary SPAs and Mike's objective (parity with TanStack Query for a re-frame2 SPA author). The headline is CATEGORICAL: which essential jobs match, win, gap, diverge, or are only pretending. No composite parity percentage appears in the report, and no post-fix score is forecast. An essential GAP is not offset by any number of optional WINs. If a weighted reading is computed at all it lives in the appendix of this file, with denominators, weights, exclusions, the treatment of unverified rows, and a range.

## 6. Controls

Controls check the INSTRUMENT, not the contest. They establish that a known defect is detected and that known-correct behaviour passes; the comparative outcomes stay open.

- **Refusal control** (expected OUT or DIVERGENT): a normalised entity cache; a process-global shared cache. If the matrix scores these as MATCH or WIN, the instrument is broken.
- **Known-capability control** (expected at least MATCH on the CODE layer, exercised): keyed cache with in-flight dedupe and fresh-skip, pinned by `spec/conformance/fixtures/resources-dedupe-join.edn` and `resources-ensure-fresh-skip.edn`; views never fetch. If these read GAP, the instrument is broken.
- **Fault control** on every row whose status depends on a test: the fake backend or a canned stub is faulted (a wrong value, a dropped reply, a leaked scope) and the assertion that claims to pin the row must go red. A test that stays green under the fault does not pin the row, and the row's CODE cell says "unpinned" whatever the test's name promises.
- **Request-ledger control**: any "no duplicate fetch" or "refetched exactly these" claim is backed by an explicit count of requests seen by the stub or backend, not by a timeout or an unmatched-stub silence.
- **Clock control**: delayed and overlapping replies are produced by controlled completion or a fake clock, never by sleeps.
- **No data-only control** chosen to make re-frame2 win.

## 7. Journeys and experiments

Four journeys are written side by side (Resources vs TanStack Query) and executed where possible: J1 first hour from the tutorial, then the marginal read and write on Conduit; J2 the Conduit favourite loop with rejection and overlap; J3 a planted missing-invalidation fault, diagnosed through Xray, repaired, and re-faulted to prove the regression detects it; J4 an assistant adding a bookmark mutation from the public surfaces. Beyond the journeys: (a) read, navigate, reuse, with a refresh failure, run under documented DEFAULTS and under MATCHED policies; (b) viewer switch during work, including a cold boot with an unresolved token; (c) leave and return with a delayed completion; (d) infinite feed and SSR hydration from the capability examples or marked unverified. Each records: commands, revision, fixture, request and response order, expected and actual, runner exit code unpiped, screenshots where the UI matters, attempt number. What was executed is marked `exercised`; what was only read is marked so. A helper round trip is not a UI workflow.

Timing is reported only under stated comparable conditions (hardware, versions, cold or warm, sample size) and a single run is an observation.

## 8. What invalidates a row, and how to re-run

Each row names what invalidates it: a named dependency release (a TanStack Query major, a TanStack DB release), a `016` section changing, a tutorial page being rewritten, a Deferred slice landing (bead id), a conformance fixture added or removed, Conduit being rewritten, a resource policy default changing. A re-run re-scores only the invalidated rows unless the row set itself changed. The re-run procedure is in `report.md` §7; the expected cost is recorded there after the first run.

## 9. Automatable pieces

The matrix names, per row, whether it could be guarded by: a conformance fixture (`spec/conformance/fixtures/resources-*.edn` is the template; implementation-independent); a structural test in `implementation/resources/test/`; a Conduit test in `implementation/adapters/reagent/test/`; a docs check that every Landed row in the shipped scorecard names its pinning test; or only a human walkthrough. No gate is proposed for anything needing a human judgement per run, a live network in CI, or a standing benchmark service.

## 10. What the rubric does not measure

Raw performance (bundle size, render work) beyond one representative read and write; a library's community size, stars or downloads; TypeScript ergonomics beyond the authoring row; GraphQL and normalised-cache jobs beyond the single entity-consistency row; anything a hosted or paid service does unless the dependency is named in the row.

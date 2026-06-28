# output-format

The standard agent-output shape for a re-frame2 implementation session. The agent driving Phase 1 and Phase 2 produces these artefacts so the engineer always has a clear handoff at the end of each session and a clear summary at the end of v1.

There are **three shapes**, one per session type:

- **Phase 1 wrap-up** — produced at the end of the Phase 1 walkthrough, when the decision record is locked.
- **Phase 2 EP wrap-up** — produced at the end of each EP's implementation session, before moving to the next EP.
- **v1 completion report** — produced after acceptance gate 2 (full claimed-capability conformance pass).

All three keep their summary section under 400 words. Detail goes in attached files (the decision record, the EP-by-EP commits, the conformance report).

---

## Phase 1 wrap-up

Produced at the end of the Phase 1 walkthrough. The decision record is committed; the engineer reads this summary before moving to Phase 2.

```markdown
## Phase 1 — locked

- **Port name:** <name>
- **Host:** <D1: language + runtime>
- **Substrate:** <D2: substrate + reactive container>
- **Scope (capabilities claimed):**
  - Required core: yes
  - Q1 state machines: <yes / no — with sub-capability list if yes>
  - Q2 routing: <yes / no>
  - Q3 SSR: <yes / no>
  - Q4 schemas: <yes-runtime-schema / yes-via-host-types / no>
  - Q5 stories: <yes / no>
  - Q6 Tool-Pair: <yes / no>
  - Q7 AI-Audit: <yes / no>
  - Q8 Flows: <yes / no>
  - Q9 managed HTTP: <yes / no>
  - Q10 Resources: <yes / no — post-v1; presupposes Q9>
- **Identity primitive:** <F1 mechanism>
- **Persistent data structures:** <F2 library>
- **Concurrency model:** <F5>
- **Schema mechanism:** <D5>
- **Decision record:** committed to `DECISIONS.md` at <commit-hash>.

## Open questions parked for Phase 2

<list any decisions deferred to specific Phase 2 steps>

## Next step

Begin Phase 2 at EP 001 (Registration). Read [`spec/001-Registration.md`](https://day8.github.io/re-frame2/spec/001-Registration/); expose the registrar API per the decision record's D4 choices.
```

---

## Phase 2 EP wrap-up

Produced at the end of each EP's implementation session. The session began with "implement EP N"; this is the report at the end.

```markdown
## EP <N> — <name> — landed

- **Spec read:** `spec/<NNN-Name>.md` (full).
- **Code:** committed at <commit-hash(es)>. Files touched:
  - <path/to/file.ext> — <one-line description>
  - <path/to/file.ext> — <one-line description>
- **Slice gate run:** <the exact command run for this EP's smallest relevant slice — the port's unit-test command for the EP's module, or a targeted conformance subset for the EP's capability tags — and its result; OR a clear not-run reason (no local tooling / no port script yet). Not a bare "tests pass" claim.>
- **Tests:** <unit tests landed alongside the EP — pass/fail count>.
- **Conformance fixtures exercised:** <list of capability tags the EP gates; pass/fail count>.
- **Spec gaps filed (issues):** <list of `day8/re-frame2` issue numbers filed during this EP; one line each>.
- **Decision-record revisions:** <list any Phase 1 decisions that needed revision; new locks>.
- **Carry-over to next EP:** <anything intentional left out — partial implementation, deferred edge cases>.

## What surprised me during this EP

<2-4 sentences. Things that didn't match expectations; things harder than expected; things easier than expected. Useful for retrospective learning.>

## Next step

Move to EP <N+1> (<name>). Read `spec/<MMM-Name>.md` first.
```

The session log is one of these blocks per EP. The engineer reads the chain to track progress; future contributors read it to understand why the port looks the way it does.

---

## v1 completion report

Produced after acceptance gate 2 passes. This is the final report for the port's "v1 release."

```markdown
## <port-name> v1 — re-frame2 implementation complete

- **Host:** <D1>
- **Substrate:** <D2>
- **Claimed capability tag set:**
  ```
  :core/*
  <list of optional tags from D7>
  ```
- **Conformance score:** <claimed-applicable> / <claimed-applicable> on corpus commit <corpus-commit-hash>.
- **Decision record:** `<port-repo>/DECISIONS.md` at <decisions-commit-hash>.
- **Spec gaps filed (closed and open):**
  - day8/re-frame2#<n> — <one line; open/closed status>.
  - day8/re-frame2#<n> — <one line; open/closed status>.
- **Per-EP commit chain:** <link to a tag, branch, or commit range covering all of Phase 2>.

## What v1 includes

- <bullet per EP: name + one-line summary>

## What v1 deferred (post-v1 candidates)

- <bullet per deferred capability: name + reason for deferral + estimate of follow-on work>

## Conformance harness command

```
<exact command to run the harness against this port; reproducible for downstream consumers>
```

## Acknowledgements

The port follows `spec/` at corpus commit <corpus-commit-hash> in https://github.com/day8/re-frame2. The CLJS reference implementation at `implementation/` in that repo was consulted as one worked example during Phase 2.
```

---

## Discipline

- **One report per session.** Don't fragment a Phase 1 wrap-up across multiple files; don't write a v1 completion report before gate 2 passes.
- **Cite commits and corpus hashes.** Both shift; pinning them is what makes the report reproducible.
- **Don't bury spec gaps in narrative.** Every GitHub issue filed against `day8/re-frame2` during the work goes in the bullet list — not in prose. The issue number is the contract surface.
- **Run the per-EP slice; leave the full gate to the engineer.** When the agent wrote an EP's code and has local tool access, it runs the smallest relevant slice from the port's own scripts before calling the EP landed, and records the exact command + result (or a clear not-run reason) — see the EP wrap-up's "Slice gate run" line. It does NOT run the full conformance harness or any release-sized suite unbidden (engineer-owned; the agent reports/diagnoses those scores only when the engineer runs or asks for the full pass). Per the skill design (L3): generic build mechanics are not the skill's job, the skill-specific per-EP acceptance slice is.
  - **"Local tool access" = the engineer's session granted the port's test/`git` commands, NOT the skill's baseline allow-list.** This skill's frontmatter allow-lists only the two surfaces it runs *itself everywhere*: GitHub-issue filing (`gh issue *`, cardinal rules 8–9) and the read-only spec-pin provenance check (`git -C <path-to-re-frame2> rev-parse` / `remote get-url`, cardinal rule 1). The port's own build/test runner and the commits of `DECISIONS.md` / per-EP code (cardinal rule 2; [`decision-record.md`](decision-record.md) §How to use; [`phase-1-decisions.md` §After Phase 1](phase-1-decisions.md)) are **engineer-owned** — they vary per host (Vite / shadow-cljs / sbt / dune …) so the skill cannot allow-list them, and they run under the engineer's session permissions. The agent proposes the exact command; the engineer's session executes it. If a slice command isn't runnable in the current permission context, record the not-run reason rather than assuming access.

## What the reports are for

- **The engineer reads them once per session** and knows where the port stands.
- **Future contributors read them** before reading the port's source code — the reports are the why-this-way navigation.
- **Downstream consumers** (people considering whether to adopt the port) read the v1 completion report and the conformance score to decide.
- **The re-frame2 maintainers** read the v1 completion report's spec-gaps section to track what the spec was missing as the port was built — surfaces real-world evidence for spec follow-ups.

These reports are the public surface of "this port exists and conforms." Treat them like contracts.

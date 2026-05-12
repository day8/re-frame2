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
- **Identity primitive:** <D4.1 mechanism>
- **Persistent data structures:** <D4.2 library>
- **Concurrency model:** <D4.5>
- **Schema mechanism:** <D5>
- **Decision record:** committed to `DECISIONS.md` at <commit-hash>.

## Open questions parked for Phase 2

<list any decisions deferred to specific Phase 2 steps>

## Next step

Begin Phase 2 at EP 001 (Registration). Read [`spec/001-Registration.md`](../../../spec/001-Registration.md); expose the registrar API per the decision record's D4 choices.
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
- **Tests:** <unit tests landed alongside the EP — pass/fail count>.
- **Conformance fixtures exercised:** <list of capability tags the EP gates; pass/fail count>.
- **Spec gaps filed (beads):** <list of `bd` ids filed during this EP; one line each>.
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
  - bd <id> — <one line; closed/open status>.
  - bd <id> — <one line; closed/open status>.
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
- **Don't bury spec gaps in narrative.** Every `bd create` filed during the work goes in the bullet list — not in prose. The bead id is the contract surface.
- **Don't run the engineer's builds for them.** The agent reports observed conformance scores (when the engineer ran the harness); it doesn't run the harness unbidden. Per the standing rule from the skill design: "running tests is general software practice, not skill-specific."

## What the reports are for

- **The engineer reads them once per session** and knows where the port stands.
- **Future contributors read them** before reading the port's source code — the reports are the why-this-way navigation.
- **Downstream consumers** (people considering whether to adopt the port) read the v1 completion report and the conformance score to decide.
- **The re-frame2 maintainers** read the v1 completion report's spec-gaps section to track what the spec was missing as the port was built — surfaces real-world evidence for spec follow-ups.

These reports are the public surface of "this port exists and conforms." Treat them like contracts.

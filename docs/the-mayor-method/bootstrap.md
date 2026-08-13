# Bootstrap

Paste the block below into a fresh AI session as your opening message. It is
terse on purpose; it assumes you have read [`README.md`](README.md).

Nothing in it is specific to one repository, one operating system or one
toolchain. The concrete values it needs — your tracker's commands, your gate
command, your hot-zone file list, your worktree parent directory — are facts
about *your* project, and they belong in your project's agent-instructions file
where every agent already reads them.

```text
You are the mayor for this repository.

Orchestration, not implementation. Preserve your context. Dispatch bounded work
to background workers in their own worktrees; only edit directly for tiny fixes
or emergency cleanup.

ONE TRACKER IS THE SPINE. Track all real work in the project's issue tracker —
no scratch to-do lists, no markdown TODOs, no parallel trackers. Close items only
after merge or verifiable completion, and record close reasons concretely with
cross-references. Decisions go in BOTH the tracker AND the merging change's body;
the change body is the durable version-history record.

READ THE SIBLINGS, IN ORDER: `dispatch-prompt-template.md` (the worker prompts —
paste the worktree-boundary block and the gate-mechanics block verbatim into every
editing dispatch), then `loops.md` (the loop bodies and the merge criterion), then
`README.md` for the longer why.

DECISIONS. Every hold awaiting the operator — review gates, operator-run actions,
held items — surfaces in chat and on its tracker item the moment it arises, and
clears the same way. There is no decisions index and no dashboard to maintain.
When the operator asks for a written record of a particular decision, capture it as
one file per decision.

FINDINGS VS EXTENDED CONTEXT. Keep a local, version-ignored working tree for
exploratory work: audits, drafts, alternatives. Always write the finding document
BEFORE filing the items it would spawn. Keep a second one for durable context the
next fresh mayor needs — initiative state, recent strategic decisions, why a
non-obvious convention exists. When unsure: would a fresh mayor next week need
this? Yes, durable context. No, findings. Anything that must survive a clean clone
gets promoted into the tracker or into committed documentation, never into a
tracked file under the working tree.

DISPATCH DISCIPLINE. For each worker: a dedicated worktree; one bounded task; an
explicit write scope; the project stance pasted into the preamble; the other
in-flight workers and their write surfaces enumerated so the receiver can
pattern-match for collisions; explicit "do not edit the mayor checkout" and "do not
merge changes"; tests plus a final report (changed files, commands run, branch and
change URL, risks). A worker may close its own item after opening the change, with
a cross-referenced reason. Before dispatching, grep for the alleged broken symbol,
missing file or stale convention — if it already landed, close as a verified
duplicate.

CHANGES. Workers open them; the mayor reviews and merges on green, on the whole
five-clause criterion in `loops.md`, no clause of which has an override. A pending
check is a re-check on the next signal, however irrelevant to the diff it looks. A
failing test on the touched surface is never an override candidate. The only
override this method sanctions is the host's own merge-state recompute lag, which
is not a check and so bypasses nothing, and it waits for all five clauses like
everything else.

OPERATOR DECISIONS. Surface design, product, security and taste decisions
explicitly. Explain the options and trade-offs, recommend when useful, and let the
operator decide. Record the decision in the tracker AND in the merging change body.
For multi-stage work needing mid-flight input, split into phases: audit, operator
decides, apply. Phases one and three are workers; phase two is operator time.

ESTABLISH THE STANCE (first session only). Every project has one — pre-alpha,
production-stable, refactor-only, greenfield, performance-critical,
hostile-input-paranoid. Without it, workers default to "preserve everything just in
case" and accumulate cruft. Interview the operator briefly: backwards-compatibility
concern? performance or safety constraints? session goals? priorities? merge on
green, or operator sign-off? Keep the answer as a single quoted block in ONE file
and paste that block verbatim into every dispatch preamble. Skip the interview if
the operator's opening message already names the stance; restate it as a one-line
confirmation instead.

SET UP THE LOOPS. The five bodies are in `loops.md`. Codify each as a command file
in this repository so each is one invocation and one source of truth, rather than
re-pasted prose. When a method rule changes, re-read the matching command file — a
link checker catches renamed files, not semantic drift.

Acknowledge "I am the Mayor now".
```

---

## The hard-won list

These are the parts that bite. Each cost real hours, and none is obvious up front.

The [loops](loops.md) page carries the ones that belong to a particular loop — the
merge criterion, reaping, routing a finding, tracker mechanics. What follows is
everything else.

### Local-green is not CI

A worker's "all gates pass locally" usually means "the subset I ran", and the red CI
gate is one its local run skipped — an integration or live test, a linter, a
drift-check. Merge on a CI rollup that is complete as well as clean.

A failing *touched-surface* gate is never an override. Dispatch a fix worker to the
same branch that runs the **actual** failing gate.

The brief must be able to say **which** required checks the local gate omits — and
say it by citing one path, not by re-listing them, because the required set moves.
Make the project *derive* that list rather than document it. Two shapes make a
hand-written list wrong within a week: a second or third required status context the
local runner has no lane in at all, and a required check that is a **step inside a
job the runner does run**, which no skipped-tier enumeration can see and which
reports under that job's name however little it resembles what the step does.

### The exit code is the verdict; the summary is decoration

Piping a gate into a filter returns the *pipe's* status, not the runner's, so a
worker reads "0 failures" and reports green on a failed run. Require capture to a
file, an explicit echo of the exit code, and that number in the report.

The same blindness has three more shapes:

* **A command's own failure and a later reassuring line land in one buffer.** A
  failed fetch followed by "Already up to date" looks like a quiet no-op. Check the
  exit of the step that fetched, not the summary of the step after it.
* **The harness reports an exit code of its own, and it can be a trailing filter's.**
  A run whose real status was 1 surfaced as 0 because a filter sat on the end of the
  command line. Run each gate alone, with nothing appended.
* **A command can succeed and still not do the thing.** A fast-forward pull printed
  `Updating <old>..<new>` twice in one session while the head did not move. So after
  a mutating step, **verify the tree rather than the message.**

### Concurrent workers share the machine's temp directory

Two workers writing the same log path overwrite each other, and the loser reads a
green belonging to someone else's run — plausible numbers, wrong code. This defeats
the rule above, because the captured exit code is also theirs.

Use worktree-local paths, name every artefact for the worktree **and** the attempt,
and have workers confirm a log is their own before believing it.

### A test pinning current broken behaviour, and the fix for it, are mutually invalidating

Validation work legitimately records today's defect as a live assertion. Each change
is green alone, and whichever merges second turns the trunk red.

Before merging a fix, search the tests for one asserting the defect; the flip belongs
in the same change. **Flip and rename it** — never delete it, and never loosen it to
accept both outcomes, which discards the only coverage of the case.

### Reproduce the real failing path

A worker's passing synthetic test can route around the gap and explain away a symptom
the operator reproduced. The acceptance test must exercise the path that actually
failed, not a proxy. Distrust a "works on my test" or "stale build" verdict that
contradicts a live symptom.

### Never let a worker stash

Stashes are repository-global. They surface in sibling worktrees and cross-contaminate
them. Put a no-stash line in every dispatch; workers commit to their branch instead.

Workers violate this rule repeatedly even when told, so pair the ban with the
alternative — commit first, then rebase — and say why.

### The worktree guard can be fooled

Edit-tool path resolution can land a worker's write in the mayor checkout even after a
start-of-session guard passed. The real backstop is the worker re-verifying it is
inside its assigned worktree **before every edit** — mandatory, not the guard script
alone.

A project may add a **mayor-side commit guard**: a pre-commit hook in the mayor
checkout that refuses commits touching worker-owned paths, so a bypassed edit-guard is
caught from the other side.

That hook will fire on the mayor's own quick fix. **That is the hook working.** File
the item with the fix written out, and dispatch it.

### Pushed commits are the only durable worker state

Workers die mid-run for reasons unrelated to their work. Put "commit and push as you
go, not at the end" in every brief, with the reason.

The mayor may push a worker's existing commits, which is pure durability. Never build
a commit from someone else's uncommitted work — only that worker knows whether it
forms a coherent change.

### A reviewer's "P1" can be out of scope

An audit can flag something the project's stance deliberately excludes. Hold it as an
operator decision. Surface, do not auto-fix.

### An audit that reopens an item may already be stale

It describes the tree as of the change it reviewed, and a later commit may have fixed
the finding — often bundled under an unrelated subject where no search for the symptom
or the item id will find it. Read the current source at the named site before
dispatching. **A verified "nothing to do" is a good outcome; an assumed one is not.**

### The mayor's briefs are the main source of error

This is the finding most worth knowing at the start.

Across one sustained session, workers corrected the mayor's stated premises about
twenty times. Not on style — on facts the mayor asserted and had not checked: counts
that had drifted between filing and dispatch; a file asserted to carry a claim it did
not carry; a defect described as fail-open that measured as fail-slow; a defect
described as silent whose error was raised loudly at source and discarded one layer
up; a remedy that was unsatisfiable for the same reason as the bug it fixed.

Workers caught every one and nothing reached the trunk. But each cost a full worker
cycle, and together they cost more than any worker-side failure in the same period.

So "guard the mayor's context, dispatch bounded work" is necessary and not sufficient.
The mayor's scarce output is **an accurate brief**. Checking a claim while writing takes
seconds; discovering it was wrong takes a worker's whole context.

Three habits follow:

1. **Say in the brief that its premises are claims.** Not as hedging — as an instruction
   that tells the worker it may stop and check. This one sentence caught more errors than
   anything else in the method.
2. **Check a claim when you write it, not when it fails.**
3. **When a worker corrects you, write the correction on the tracker item.** The next
   dispatch reads the item, not your reply.

### What a worker is actually for

A worker is not a typist. It is the only part of the system that reads the real tree.
Its outputs, in descending order of value:

**A refusal.** *"The premise does not hold."* *"This is by design."* *"The fix belongs
elsewhere."* Refusals were the most valuable thing workers produced. One declined to add
a public API door merely because an implementation function existed behind it. Another
refused to change four carriers of a "wrong" symbol after establishing that a design
register deliberately records proposed spellings.

Refusals only happen if the brief permits them. Write it plainly: *a verified "already
fixed" or "the premise does not hold" is a complete and good deliverable.* Without that
sentence, a worker handed a task that should not exist will invent one that does.

**A correction.** The tree disagrees with the brief; here is the evidence.

**The work**, with a control that proves it.

The best refusal seen here came with measurement rather than argument. An item claimed a
CI timeout was too tight. The worker sampled 183 runs of that step — median 13 seconds
against a 300-second cap — then found the actual cause was a mirror serving 21 MB at
52 kB/s while the same job had fetched 11 MB at 7.8 MB/s seconds earlier. It closed the
item and left a comment in the workflow so nobody re-derives it. A refusal on principle
can be argued with; that one cannot.

### Defect classes worth naming

These recurred often enough that naming them helps you spot the next one.

**The control that cannot catch its own case.** A scanner missed every forbidden import
after the first. Its permanent test planted the forbidden import as the first and only
entry, so the test exercised exactly the path that worked. The gate had been green about
a class it did not cover, and the proof of coverage was the blind spot.

Apply the generalisation deliberately: **ask of every control whether it is constructed so
that it avoids the case it exists to catch.** The worker who found the above asked that
question of every other control in the same file and found two more, one of which had no
control at all.

**The hollow gate.** A test that proves something by observing the absence of a symptom,
where the same absence occurs for unrelated reasons. There is a mechanical test for it:
**delete the signal but keep the fault.** If the test stays green, it was proving nothing.
One worker did exactly this and showed that three symptoms its item was built on — no
requests, idle status, no output — all stayed green when only the error report was removed.

**Replace a hollow control; do not patch it.** Every one found here was replaced, because
patching one produces a second thing that looks like a control and is not — and the next
reader trusts it harder for having a history of being fixed.

**Measured honestly, then interpreted too generously.** A worker predicted a focus cycle of
four elements, measured five, and recorded what it measured. That was right. It then
described the extra element as acceptable — but the extra element was the document body, and
the feature is a focus trap. Measuring correctly and concluding wrongly is a distinct failure
from measuring wrongly, and it is harder to see, because the evidence in the report is
accurate.

**The artefact a consumer copies still teaches the broken form.** Code fixed; the example,
recipe or guide not. Seen three times. When reviewing a fix, ask what a reader copies, and
whether that changed.

**The unsatisfiable instruction.** A rule that cannot be obeyed on some path — "every gate
prints X" when a third of them do not. Workers facing one do not stop; they improvise, and
the improvisation varies. **When a rule says *every*, check the quantifier.**

Correcting one of these often surfaces something useful. Scoping "every gate derives its root
from the script path" to the gates that actually do revealed that the others resolve against
the working directory, which changes what a worker must do.

**Stale by chronology, not by error.** Two records merged twelve seconds apart, and the second
described the world as it was before the first. Neither author was wrong. Under a high merge
rate this is ordinary: prefer dated amendments to in-place edits, so a record that aged is not
mistaken for one that was wrong.

### Two tensions the method does not resolve

Naming them is more useful than pretending they are solved.

**Keeping workers busy starves work that needs the machine quiet.** Timing measurements, or
anything contended, cannot run while several workers compile. No amount of prioritisation fixes
it, because the fleet is the constraint. What worked: notice when the only remaining work is
the exclusive kind, then deliberately drain and take it. Make that a decision you announce, not
something that happens by default.

**"Reap only on the worker's own report" leaves residue.** The rule is correct — every proxy for
"this worker is finished" has killed a live run. But an agent that vanishes will never report,
and its worktree becomes unreapable. There is a second cost too: a worker can need its tree
*after* it reports, because its change hits a conflict and needs a rebase. Nothing is lost when
that happens, because the commits were pushed, but the worker has to rebuild its tree first.

Keep the rule. Let the residue accumulate, and clear it on explicit operator authority rather
than inventing another proxy.

### Deciding what is actually the operator's

The mayor surfaces decisions; the operator decides; the mayor records the decision on the item
so future workers inherit it.

One mayor got the split wrong in one direction: it was holding nine items "for the operator" when
three were genuinely operator calls. The rest were decisions the project's stated stance already
answered, and it was being deferential rather than useful. When you present a list of held items,
sort them first — and it is fair for the operator to ask which of them actually need them.

Two refinements that worked:

**Separate "needs a decision" from "needs work under a decision."** Many items are mostly the
second. Ship the part that is true under every option, and leave the item open with the choice
stated. Progress without pre-empting the operator.

**Record what a held decision is costing.** One held item caused a worker to read a correct,
catalogued error as silence, which caused a mis-filed item, which cost another worker a cycle to
refute. That chain belongs on the item. It is the difference between "still waiting" and "here is
what waiting cost."

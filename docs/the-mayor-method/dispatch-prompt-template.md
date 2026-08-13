# Worker dispatch

Copy-adaptable shapes for delegating bounded work to a background worker. Assumes
a capable agent. Placeholders:

- `<MAYOR_CHECKOUT>` — the mayor's primary checkout (absolute path)
- `<WORKTREE_ROOT>` — the directory holding worker worktrees (derive it from your
  version-control tool at dispatch time; never hardcode a path)
- `<ASSIGNED_WORKTREE>` — this worker's worktree, a subdirectory of `<WORKTREE_ROOT>`
- `<ITEM_ID>` — the tracker id

> **Project-specifics live with the project, not here.** Your hot-zone file list,
> your surface-to-gate matrix, your pre-checkin command and your worktree root are
> facts about *one* repository. Keep them in that repository's agent-instructions
> file and pull the concrete values from there at dispatch time. This page is the
> reusable, OS-neutral method.
>
> **The stance is project-specific too, but it is PASTED rather than pulled, and it
> lives in its own file.** Keep it as a single quoted block in ONE file and paste
> that block verbatim into every preamble. Do not send the worker to a file that
> does not carry a stance — a brief that does sends it somewhere the words are not.
>
> **And a summary is not a lighter version of a stance.** The lenses say what good
> looks like; everything after them says when to STOP. A paraphrase keeps the
> memorable half and drops the restraining one, and what survives does not read as
> incomplete. It reads as a stance that wants MORE of everything, which is precisely
> the failure the second half exists to prevent.

---

## Write the brief in this order

The order is what makes it accurate. Each step is a check the next depends on.

1. **Read the tracker item bottom-up.** On most trackers the description is the
   *oldest* text on the item; corrections, scope changes and audit findings accrete
   below it. On a reopened item the description is usually already done and the live
   charge is at the bottom.
2. **Check every factual claim you are about to write.** Does the symbol resolve?
   Does the file say what you think? Is the count still true? Is the ruling you cite
   *ruled*, or only recommended? A recommendation and a decision read identically in
   a summary and are opposite in force.
3. **Establish the fence by asking who is live, not what is open.** A listing of open
   changes misses a worker that has not pushed yet.
4. **Name the discriminator**, if the task is "find every X".
5. **Then** assemble the standard blocks.

---

## The three sentences that do the most work

Put all three in every editing brief.

> **This brief's premises are CLAIMS, not findings.** Check each at source before
> acting on it. A verified "already fixed" or "the premise does not hold" is a
> complete and good deliverable — report it with the evidence rather than going
> looking for work to do.

> **Read the item before this brief, and read it bottom-up.** Where they disagree,
> the item governs — follow it, and say in your report what differed.

> **The control is the deliverable.** Show it red when the property is removed,
> restore, and verify the restore by hashing the bytes.

The first is the highest-yield sentence in the preamble. The bottom-up rule caught
five stale briefs in a single day: one whose fix had landed two days earlier under a
sibling item; one that named the wrong audit finding; one told to execute a resolution
that had already merged; one that a scope correction had redefined from an allocation
leak to a baseline-contamination leak; and one carrying three wrong path, flag and
script details. **Every one of those briefs was accurate when it was written.**

---

## Name the discriminator

Any task shaped "find every X and fix it" fails without one, because **the phrase is
usually correct somewhere**. Briefed without a discriminator, a worker changes all of
them or none.

The discriminator is the question that separates a real hit from a legitimate one. Two
that recur:

* *Does this line claim something about code that runs, or record an open question?* A
  design register deliberately carries proposed names that do not resolve. Those are
  correct.
* *Does this sentence describe the state now, or record what was true then?* A
  past-tense record is not stale.

Without one, a worker sweeping a register will "fix" it into a lie.

**"Bounded repair" bounds the change, not the search.** Grep the exact wording across
tracked files, read every hit, settle each in the same change, and list in the change
body what was changed and what was left standing because it was already right. One
project corrected a single sentence four times across three changes; every repair was
bounded and correct, and every one was found by the *next* merged-change audit rather
than by the repair before it. Three round-trips for what one grep closes.

---

## Counts are claims, and they go stale first

A count asserted when an item was filed is the first thing to drift, and a symbol-shaped
check will not catch it — the symbol still resolves while the count is zero, or triple.

Say so, and require the census be **re-run at the current tip**, reported against the
item's number. Measured drifts in one session: 8 to 9; "four hits" to 23 lines; "roughly
6–10" to 23; "4 of 21 cached" to 13 of 21.

Better still, when the number will keep moving: brief the fix to name the **class** rather
than the count. A rule written as a class does not re-drift.

---

## Fences

State what the worker owns, then what it may not touch **and who holds it**. Naming the
holder tells the worker whether a conflict means "rebase" or "stop and report".

Hot-zone files — anything sequential, where two concurrent editors conflict by construction
— take one toucher at a time. If a remedy needs one, the instruction is **stop and report**,
not edit. **A citation is not permission**: briefs routinely cite a specification section as
context, and a worker can read that as licence to edit it.

**The unit is the block, not the file.** Two items were dispatched into the same paragraph
of one document because they were nominally different concerns. They conflicted, the second
change could not merge, and by the time its worker was resumed the hygiene loop had already
reaped its worktree. Two costs from one scheduling error. If two items touch the same
section, sequence them.

**Complementary work is not duplicate work.** When two items attack one defect from
different sides — one making a silent failure loud, one stopping a document teaching the
failing shape — say so in both, or one gets closed as a duplicate of the other.

---

## Choosing solo or cluster

Pick by **kind first, then priority, then size**. Do not reflexively dispatch
one-worker-per-item, and do not reflexively bundle everything.

* **Highest priority → always SOLO.** A dedicated worker and its own change, so it merges
  on its own green and is never blocked by a cluster sibling.
* **Second tier → SOLO by default.** Cluster several only when they are genuinely small,
  same-surface and low-risk.
* **Many small low-priority same-surface items → CLUSTER.** This is the primary clustering
  case. It stops you handling dozens of trivia serially.
* **Any LARGE item → SOLO**, whatever its priority. Never pad a meaty item into a cluster,
  and never bundle two large ones — the agent times out. Bundles of four or more items time
  out routinely where single-item workers succeed.
* **A measurement window → SOLO, Shape 6**, picked by kind rather than size and never folded
  into a cluster.

**One agent owns a surface; surfaces run in parallel.** That is how you avoid serial handling:
same-surface items ride one agent, which never collides with itself, while genuinely separable
surfaces dispatch as concurrent agents. Two workers never share a surface — they merge-conflict
and can silently revert each other.

**The serial exceptions**, where same-surface work is *meant* to be sequential: a deliberately
serial epic, and a single tightly-coupled module whose core files many items touch. That surface
is its own serial lane — sequence its changes, later ones rebasing on the earlier merges, never
resolving a conflict by taking one side wholesale. On a coupled surface even solo high-priority
work cannot run in parallel.

---

## Dispatch shapes

**Shape 1 — Solo.** One item, one change. Item id and verbatim title; two to four paragraphs of
context with `file:line` citations; numbered concrete steps; a dedicated worktree and branch; the
boundary block; claim the item; gates with exact commands; push and open the change with a
`## Quality gates` section; report the change URL, a per-step summary and test deltas.

*A coverage or rigour pass must add at least one adversarial or negative case per surface.*
Assertion-count growth alone only exercises the happy path.

**Shape 2 — Cluster.** Several small same-surface items, one change. Order commits
smallest-cleanup to biggest-correctness-fix, so a failing item cannot strand the small wins.
Claim each item immediately before its commit, so history mirrors tracker state and a stalled
cluster leaves a clean partial trail. Gates after each commit and a full regression after all.
Keep a cluster to three to six small items; beyond that run successive cluster changes, each
opening with what it finished and listing the remainder, never a half-item uncommitted.
Disjoint-surface "small-misc" clusters are valid at the tail of a drain — the binding rule is
hot-zone parallelism, not strict same-surface.

**Shape 3 — Audit (read-only).** A finding, not a fix. Goal, surface paths, and prior findings
to avoid re-discovering; the boundary block; write the findings document to the version-ignored
working tree FIRST, never commit it, never link it from committed files; file follow-on items one
at a time, appending each id to the audit item's notes so progress survives a timeout; close with
a verdict, severity counts and cross-references; no change by default.

**An audit brief needs the stance more than an implementation brief does.** The audit is
*producing* findings, and a project that rejects findings as often as it actions them will reject
the weak ones at the cost of a dispatch each. Tell it plainly: eight sourced findings beat thirty
plausible ones, and a finding whose remedy the project's stance excludes is worse than none.
Giving it two or three worked examples of a good finding beats asking for "a careful review".

The mayor may reorganise or reject findings. Not every finding is actioned.

**Shape 4 — Cluster reviewer (read-only, no dispatch).** Shapes the next wave. List in-flight
workers and their surfaces first, and do not recommend touching those. Enumerate recently filed
items and the ready queue. Per item decide: add to an in-flight cluster; form a new cluster (three
or more items on a shared non-hot-zone surface); solo (correctness, large, decision-resolved or
cross-cutting); or defer. Output the net next-dispatch shape in two or three sentences. **Do not
change tracker state.**

**Shape 5 — Fix a failing check.** The failing check name and log lines; two or three root-cause
hypotheses; a worktree off the **existing** branch, not a new one; the boundary block; **run the
ACTUAL failing gate locally**, not a proxy that already passed; fix surgically, or file a follow-on
if it is deeper than scope and the stance allows a safe exit; push to the existing branch, never the
trunk; update its `## Quality gates` section.

*Never override a failing touched-surface gate.* Diagnosis often beats the failure log — test the
hypothesis before fixing.

**Shape 6 — Measurement window.** The five shapes above all tell a worker to iterate until the gate
goes green. A measurement worker must do the opposite, and that inversion is the whole shape: it is
not there to produce a number, it is there to find out whether a trustworthy number can be produced
at all.

- **The controls are the arbiter, not the worker's judgement about the machine.** A worker asked
  whether its own machine was quiet enough will always find a reason it was. If a control fails, or
  an ordering guard refuses, the run refuses and the worker reports the refusal without weighing it
  against how the machine felt.
- **A refusal is a deliverable, not a failure.** Two of three windows in one day came back as
  refusals and both were correct. If everything refuses, the window succeeded: you now know the rig
  cannot see what you hoped it would, which is what you dispatched it to find out. Say this in the
  brief, or the worker reads its refusal as its own failure and goes hunting for a way to turn it
  green.
- **Never "fix" a refusal by loosening a gate.** Every gate in a rig is there because something once
  passed that should not have. Widening a threshold to admit today's run retro-admits that failure
  too, and the series loses the one property that made it worth running.
- **Do not improve the rig mid-window.** No new instrument, no extra rungs, no third estimator,
  however obvious the improvement looks from inside the run. One worker declined to build a rung it
  genuinely needed, mid-series, on the grounds that *"a rung added between runs makes the series two
  instruments"* — the clearest statement of this rule anyone here has managed. File the improvement
  and run it as its own window.
- **Do not restate a published figure on thin evidence.** A worker holding four reportable runs, every
  one reading *below* both published figures, recorded them without publishing. That was right: four
  runs disagreeing with a number are a reason to look again, not a mandate to move it.
- **Verify the machine with real counters, not the convenient one.** One system's headline CPU counter
  read 93% while the true value was 11%. Cross-check with a second source, and prefer the counter that
  says whether anything is actually *waiting for a core* — that is the only thing the window cares
  about.
- **One run at a time, never concurrent.** Concurrency is precisely the contention the window exists to
  exclude, so two runs the worker believes are independent still are not.

Report what ran, what refused and on which control, the raw numbers, and — as its own heading — what was
**not** concluded. A window that publishes nothing still reports everything.

---

## The worktree boundary block

**The concept.** A worker edits only its assigned worktree, never the mayor checkout. The shell's working
directory is not enough protection: some edit tools resolve a relative path against the agent's session
root rather than the repository root, so a write can land in the mayor checkout *even after a
start-of-session guard passed* — the leak happens mid-session, in one tool call. A guard script can be
fooled by that resolution; the real backstop is the worker re-verifying its repository root before every
edit. New-file leaks are the worst case: a brand-new ignored file routed into the mayor checkout shows
nothing in the worker's own status, so it fails silently.

Paste this verbatim into every editing dispatch. Adapt only the placeholders.

```text
WORKTREE BOUNDARY — MANDATORY
Your worktree:  <ASSIGNED_WORKTREE>
Mayor checkout: <MAYOR_CHECKOUT>   ← never edit this.

Before EVERY edit, confirm you are in your worktree: ask version control for the
top level of <ASSIGNED_WORKTREE> and check it prints <ASSIGNED_WORKTREE>.
Use ABSOLUTE paths under <ASSIGNED_WORKTREE> for every edit and write. A
start-of-session guard is NOT sufficient — verify per edit.

After your first edit, and after writing any NEW file, confirm it landed in your
worktree and NOT the mayor checkout. Check BOTH trees: a new ignored file leaking
into the mayor checkout is invisible in your own status. If anything landed
outside your worktree: STOP, report both paths, do not repair, commit or push —
let the mayor decide.

Do NOT stash — stashes are repository-global and surface in other workers'
worktrees, cross-contaminating them. Commit to your branch instead.

Concurrent workers SHARE one scratch directory keyed to the SESSION, not to your
worktree. Name every scratch file you write outside your worktree FOR that
worktree, and every gate artefact for the ATTEMPT that wrote it as well:
`gate-<name>-<worktree>-1.log`, `gate-<name>-<worktree>-1.exit`, never the bare
names, and bump that number on every re-run.

A generic name is silently overwritten by a peer: nothing errors, and the loser
can READ the survivor and take a change body with plausible structure and the
wrong subject for its own — or a gate exit code belonging to another worker's
run, which reads as a clean pass and fails the merge decision open.

The attempt number closes the same hole from the other side, which the worktree
suffix cannot reach because both writers are YOU: a harness that caps a command's
runtime kills the SHELL, not what it spawned, and the orphan keeps writing to the
`.log` and `.exit` your restarted run is already using. A hole of NUL bytes, or
two summary lines in one log, is the tell.

Confirm a scratch file is your own — and your current attempt's — before believing
it. And confirm each gate read YOUR worktree before believing its colour, by
whichever of two routes that gate affords: check the root it prints, or plant a
fault and check it goes red. That worktree check, not the naming rule, is what has
actually caught both observed collisions.

If you create a link into shared dependencies, remove the LINK (never its target)
before you report done: later cleanup follows it and deletes what it points at.
```

A project may add a **mayor-side commit guard** — a pre-commit hook in the mayor checkout that refuses
commits touching worker-owned surfaces — so a bypassed edit-guard is caught from the other side.

---

## Common preamble

```text
You are implementing <ITEM_ID> in <project + one-line description>.

<PROJECT STANCE — paste the stance block VERBATIM from the one file that owns it.
Do NOT summarise it. The lenses say what good looks like; the clauses after them
say when to STOP, and a paraphrase keeps the first half and drops the second.>

READ THE ITEM BEFORE THE BRIEF, AND READ IT BOTTOM-UP — MANDATORY. The tracker
prints the DESCRIPTION first, and the description is the OLDEST text on the item:
corrections, scope changes and sibling landings accrete BELOW it as notes. So read
the notes from the bottom up, the description last. Where the item and this brief
disagree, the ITEM governs — follow it, and say in your report what differed.

THIS BRIEF'S PREMISES ARE CLAIMS, NOT FINDINGS. Check each at source before you act
on it: that a ruling it names is ruled and not merely recommended, that a gate it
names covers your path, that a symbol it names resolves. A verified "already fixed"
or "the premise does not hold" is a complete and good deliverable — report it with
the evidence rather than going looking for work to do.

Do NOT link version-ignored working files from committed documents — a strict link
validator fails the build in cascade. Inline a one-sentence summary instead.

Working notes may live in the version-ignored working tree — check there first for
prior passes — but before the item closes, the verdict must be self-contained on
the item, and any implementation-governing conclusion must land in its owning
tracked record. A fresh maintainer must never need the working tree. Do not promote
transcripts.
```

**A pass that concludes only in the ignored tree concludes where nobody can read it.** Every project keeps
some scratch tree version control cannot see, and that invisibility is what makes the failure silent: an
item can cite a design by a path no maintainer has. Two audits were lost exactly that way here, and a mayor
re-ran an entire three-design programme in one day for want of looking there first. Widening what version
control tracks is not the fix — working notes still stay local, and only the *conclusion* is promoted, into
whichever tracked record already owns the surface, as a dated amendment where one exists and a new page only
when the evidence stands on its own. Three workers in a single day found their design already written in
that tree and promoted the surviving conclusion rather than re-deriving it, which is why the preamble tells
the worker to look there first.

---

## Quality gates — which gate

Every editing dispatch runs the project's pre-checkin gate before opening a change, and lists what ran in a
change-body section headed **exactly** `## Quality gates` with pass and fail counts. A verbatim heading is a
contract for automated audits.

- **Gate the transitive surface, not just the file you changed.** A public-surface change breaks its
  *consumers*, not itself. Gate every artefact reachable from the diff through import edges.
- **Local-green is not CI.** "Green locally" usually means the subset the worker ran; the red gate is one it
  skipped. The merge decision is CI's, not the worker's hand-off. So the brief must say *which* required
  checks the local gate omits — and say it by citing **one path**, not by re-listing them, because the
  required set moves. Make the project *derive* that list rather than document it.
- **Never nominate a gate that does not cover the surface being edited.** Site builds, linters and test
  runners all carry exclusion lists, and a gate run over an excluded path exits green having verified
  nothing — the same fail-open defect worth hunting anywhere else, except it is now the brief telling the
  worker to trust it. **Read the exclusion config before naming the gate.** Where a surface genuinely has no
  automated coverage, the honest brief says *no automated gate covers this surface; the worker verifies it by
  hand and says so in the change body* — and the change body then reports what was checked and the counts.

A skipped gate needs a one-line reason in the change body. A silent skip fails review.

---

## Quality gates — how a gate is run

Paste this section verbatim too, on the same footing as the boundary block. The gate menu settles *which*
gate runs; only this settles *how*.

**Detaching a long gate is CORRECT; ending the turn afterwards is the defect.** Where a harness hard-kills a
foreground command well below what the full gate needs, "foreground, to completion" is not on offer however
the brief is worded. Foreground a gate that fits inside the ceiling; **detach one that does not, then poll
both its log and its exit-code file in a bounded loop, within the same turn.** Poll both, because they answer
different questions: the log shows progress, while the exit file carries the verdict and appears only once the
run is actually over.

What strands a worker is ending the turn instead, waiting for a completion notification — it does not always
arrive, and a turn that has ended has nothing left to wake. The worker then reports "standing by" through idle
tick after idle tick while its branch sits unmoved: four such incidents in a single day, then three more inside
one hour, every one recovered intact the moment somebody asked it for a status.

**Do not word this grudgingly.** Two of that last three *apologised for detaching*, which is exactly why the
polling instruction had not landed: a worker who believes it has already erred reads for how to atone rather
than for what to do next, and reads straight past the instruction that would have saved it.

**Invoke a backgrounded gate by its ABSOLUTE path.** This holds by two different mechanisms. A script that
derives its repository root from its own invocation path gets a relative one when the invocation is relative.
And an interpreter handed a relative script path resolves *that* against the working directory first, so a wrong
directory hands it a sibling worktree's copy of the script, which then pins faithfully to the sibling's root.
Either way, a `cd` followed by a relative invocation does not reliably keep that `cd` once backgrounded. One run
did exactly that inside *another live worker's* checkout: it took that tree as its gate root, its diff root and
its classifier input, then reported the resulting verdict as its own. **A complete, internally consistent run
about somebody else's work is far harder to catch than a broken one.**

**Verify which tree a gate actually read, by whichever of two routes it affords.**

* Where a gate prints its root, check that line names your worktree. That check, not any naming rule, is what
  has actually caught the observed cross-worktree collisions.
* Where a gate prints nothing — and many will not — the proof is **the red from a planted fault.** Plant it at
  a line you are already editing, run the gate red, and check the failure names what you planted. Do not expect
  the reported path to name your worktree; a repository-relative path cannot tell two checkouts apart. **The
  discrimination is the red itself**: the fault exists only in your tree, so a run that had wandered into a
  sibling's would have come back green.

**A green sabotage run is a reason to stop, not to proceed.**

This half is written down because an earlier version of this rule claimed *every* gate printed a root banner,
which is an instruction a worker on a silent gate cannot satisfy. Two hit it in one day, and one arrived at the
negative control unprompted — because a worker facing an unsatisfiable rule improvises rather than stops.

**Never pipe a gate through a filter.** A pipeline's exit status is its *last* command's, so a red runner reads
green and the change claims a pass it never got. Redirect to a log file, echo the runner's own exit code
explicitly, and quote that number — with the redirect and the echo on **one command line, in one shell**. A
separate invocation starts a *fresh* shell whose status is not the gate's but whatever that shell last did,
typically the directory change: silent, and it yields a plausible zero.

**The number you quote is the one you captured** — never one the harness reports about the same run. That is a
different measurement and it disagrees routinely rather than rarely: at least fifteen times in three days across
five workers. Among them a compile failure from an unbalanced parenthesis, a genuine two-assertion failure, and a
browser run standing over three real ones. **Every one surfaced as exit 0**, and every one was caught because the
worker quoted the number it had captured. Twice it happened on a *deliberately sabotaged* run, where believing the
reported zero would have read as "the control does not bite" and inverted the conclusion.

**Put every gate artefact where version control ignores it, and name each one for your worktree AND for the
attempt** — the log and the exit-code file both. Neither half is tidiness; a name missing either fails the gate
**open**.

The scratch path is keyed to the session, not the worktree, so every worker in a wave shares one directory. Two
of six workers in a single wave wrote the same exit-code filename there; one then read a zero a peer's run had
already left while its own gate was still running, and found its log interleaved by two writers at independent
offsets, one line beginning mid-word.

**The worktree suffix cannot close the second mechanism, because there both writers are the same worktree.** A
process the harness could not kill goes on writing to an inherited descriptor after its replacement has started:
the runtime cap kills the *shell*, and what that shell spawned survives holding the same open log and exit file.
It writes at *its* offset while the restart writes from zero, so the artefact is not cleanly clobbered but spliced.
Measured twice independently: one worker was left with `exit 0` sitting beside 18 real failures; another found an
orphaned test process reporting two failures from a run that had already been killed. The worktree suffix was
present and correct in both and made no difference to either.

**Expect this route more often than the peer collision, not less**, because detaching a long gate is the sanctioned
path, so kill-and-restart is routine rather than exceptional. A fresh number per attempt sends the orphan's write
somewhere you will never quote. It is belt to the capture rule's braces rather than a replacement for it — the
second worker above was saved by quoting its own shell's captured code instead of the log.

**One leftover artefact is enough to make a worktree unreapable.** Nine accumulated exactly that way before anyone
worked out why they would not remove. The cheapest fix is to not create the residue.

**Verify a restore by hashing the bytes, never by reading a diff.** A brief that proves a guard is *exact* rather
than merely present tells the worker to plant a fault, run the gate, then restore — and a diff misreads that restore
two ways. A rewrite that flips line endings reads clean having changed every line; and **a patch that never applied
reads clean too**, because "unchanged" and "not attempted" are the same diff. The second is the dangerous one: a
plant that silently no-ops makes the sabotage run come back green, so the worker reports a guard that fired when
nothing was ever broken — a false proof of a real guard, which is worse than no proof.

Hash the file before the plant and compare after the restore. Three cautions:

* **On a checkout whose line endings are translated, use version control's own content hash against the committed
  object, not a plain byte digest of the working file.** A checkout during a rebase can rewrite the working file
  after you hashed it, so a plain digest reports a *correct* restore as failed. A false "restore failed" is not
  merely noise: the worker's next move is to doubt the whole sabotage result, and the sabotage result is the
  deliverable.
* **Anchor a patch to a single line.** One worker's multi-line anchor matched nothing, with no error and no edit,
  and only the hash caught it.
* **A hash proves the SOURCE changed, not that the runtime ever saw it.** One worker planted a one-line fault and
  its live witness came back green. The hashes differed, so the plant had genuinely applied and the rule above
  reported success — but the file was not in the host build's module graph, so the watcher never noticed the edit
  and served the pre-plant compile. Restarting the build produced the red at once. So **a green sabotage run is
  evidence only once both halves hold**: the hash for the source, and positive evidence the runtime observed the
  plant. Read without that second half, the green says *my witness proves nothing*, and the worker's next move is
  to tune the witness until it reds against a fault that was never being compiled — a conclusion about the wrong
  artefact altogether. The hash deepens this trap rather than closing it, because it is genuine evidence for a
  narrower claim than the worker needs.

**Scope a plant to the suite under test.** If the runner executes the whole lane in one block without catching
exceptions, a plant that crashes any namespace stops every namespace after it — and the log still looks plausible.
One worker's unconditional plant aborted partway and the suite it was trying to prove never ran. Compare namespace
and assertion counts against a control run.

**Re-run the gates on the final base after every rebase.** A pre-rebase green is evidence about a tree that no longer
exists, and under a saturated wave the trunk moves under a worker routinely. Nothing warns you: the rebase reports
success, and the old log still says exit 0. One worker rebased four times past eleven landings, and re-running the
gates on the final base *changed the artefact* rather than merely reconfirming it — fixes for three of its own five
findings had merged in the interval, so the governance record it was about to publish carried a membership count
**false of the trunk**. A false count in a record like that is not caught later; it is cited later.

**Diff against the trunk before you push, and read that diff for what you would REVERT.** Not for conflicts —
version control reports those itself, and a clean rebase is exactly the case this rule is for. The question the diff
answers is whether your push undoes something a sibling landed while you worked, which nothing will raise and no gate
covers: a revert of a merged change is well-formed, it compiles, and it passes every check the tree has. In a shared
document, a stale copy of one region reapplies as a silent deletion of everything that landed in it since. One
worker's first push would have reverted a concurrent rewrite of a shared ledger; it caught that by reading the diff
before reporting, and by nothing else.

---

## Reviewing what comes back

* Did it check the premises, or accept them?
* Is there a control, and does it fail for the right reason? Ask specifically whether the control could catch its own
  case.
* Which numbers are captured, and which are reported by the harness?
* What did it decline to do, and why? A report with no refusals, on a task that had a plausible one, is worth a second
  look.
* What did it find that you did not ask for? File those **with the owning item named in the new item**, so a later
  audit reopening that owner is recognisable as the same finding rather than a second one. One mayor skipped that and
  created a duplicate of its own within hours.

---

## Failure modes these shapes close

- Back-compatibility shims by default → the stance is explicit in every preamble.
- Same-file races between concurrent workers → in-flight surfaces enumerated.
- Edits leaking into the mayor checkout, especially silent new-file leaks → the boundary block plus a post-write check
  of both trees.
- Cross-worktree contamination via stashes → the no-stash rule.
- A peer overwriting a worker's scratch file, or an orphaned child of a killed gate writing over the run that replaced
  it — either way a worker reads the survivor as its own, including a gate exit code belonging to a different run,
  which merges on a green nobody earned → artefacts named for both the worktree and the attempt, plus the gate-root
  check, which is the half observed to actually catch it.
- Worktree cleanup deleting *through* a link into the shared tree it points at → the worker unlinks before reporting
  done, and the cleanup path disarms before removing.
- "Green locally" merged into a red CI gate → gate the transitive surface; merge on CI, not on the hand-off; a real
  failure gets a fix worker, never an override.
- A passing synthetic test that routes around the real bug → reproduce the actual failing path.
- Clusters split that should be one change, or the reverse → the cluster reviewer pre-validates shape.
- Stalled workers losing analysis → findings first, and one-item-at-a-time tracker creates.
- Re-discovering known issues → name recent landings and prior findings.
- A brief that was accurate when written but stale when read → the item governs the brief, notes read bottom-up.
- Generic prompts → require `file:line` citations and concrete fix sketches.
- A measurement worker iterating until the number looked right → Shape 6: the controls arbitrate, a refusal is a
  deliverable, and the rig does not change mid-window.

---

*Record three or four exemplary dispatches per project — a solo, a cluster, an audit, a fix. A few good examples teach
a new mayor more than thirty mediocre ones.*

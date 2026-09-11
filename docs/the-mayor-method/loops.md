# The loops

The mayor runs on a cadence. Register these five with a scheduler once, and let
the cadence carry the session.

**Codify each loop body as a single command file in your own repository**, so
there is one source of truth per loop. Re-pasted prose drifts, and two copies of
a rule disagree within days. This page is the generic body; your copy pins the
concrete values.

**What is generic here and what is yours.** Everything below is the method. The
values it needs — your gate command, your tracker CLI, your hot-zone file list,
your worktree parent directory, your check-count band — are facts about one
repository, and they belong in that repository's agent-instructions file. A loop
body that hardcodes them is a loop body that goes stale in someone else's clone,
including yours after a refactor.

| Loop | Cadence | Job |
|---|---|---|
| Merge + dispatch | short (~10–15 min) | Merge everything green; copy open nightly alerts into the tracker; refill the fleet |
| Backlog reread | medium (~60 min) | Re-read *all* open items from the raw list |
| Posture + stranded sweep | medium (~60 min) | Restate the stance; find genuinely stranded work |
| Hygiene | long (~2 h) | Prune worktrees and branches |
| Method reread | long (~60 min) | Check these documents against what the tree does |

Some people prefer merge and dispatch as separate loops. One loop is simpler and
has a real advantage: a merge frees a worker slot, and the same tick refills it.

**Five loops, six bodies.** Merge and dispatch share one tick, but each is long
enough to want a heading of its own, so they are written separately below. The
numbering follows the bodies; the cadence follows the table.

**Loops fire on time, not on state.** Several ticks legitimately have nothing to
do. *"Nothing to merge, fleet saturated, here is why"* is a complete report. A
loop that manufactures work to look busy is worse than a quiet one.

**Run the merge loop on most signals, not only on its cadence.** Any time you are
re-invoked — a worker completing, an operator message, another loop's tick — sweep
the open PRs and merge whatever is already green. Checking is cheap and it keeps
the pipeline moving. What you must never do is *block* on CI: no watch commands,
no polling loops that occupy the session. One-shot queries, merge what passes now,
re-check the rest on the next signal.

**And the same holds for any long LOCAL operation** — a bulk cleanup, a dependency install, a
batch of tracker queries. The operator cannot distinguish a session busy on one from a session
that has stalled, and will read a long silence as the latter. Run it detached and stay
answerable.

---

## 1. Merge

### The criterion

Merge only on **all five** clauses. Each exists because a signal that looked green
turned out not to mean that. There is no bypass. An administrative override is for
the host's own mergeability-recompute lag, which is not a check at all, and only
once every clause is already met.

**A change still published as a draft is not a merge candidate, and no number of
clauses makes it one.** The five test the CHANGE — whether what is proposed is
green — and none of them can see whether its author has finished with it: workers
push as they go because pushed commits are the only durable worker state, so *green*
and *done* are separate facts and this criterion only ever measured the first.
Marking a change ready for review is the worker's last act, and hosts refuse to merge
a draft, so the interlock costs this loop nothing to remember. See
[*Publishing the change*](dispatch-prompt-template.md#publishing-the-change) in
`dispatch-prompt-template.md`.

**With one exception this loop DOES have to remember, because it is the loop that creates
it.** The flag protects whoever is inside the change, and it is set by whoever finished
last — so a fix dispatch onto an EXISTING ready change inherits a flag its original author
set, and nothing in the change records that somebody is inside it now. The interlock is then
already open before the second worker starts. **So converting that change back to draft is
part of dispatching the fix, not part of the fix**: do it yourself, before the worker begins,
and confirm the state took rather than assuming the call did. The fix worker marks it ready
last, exactly as any other worker does.

Resolve the head first — the branch name **and** the head revision — because
clauses 4 and 5 are both keyed to it.

1. **A non-empty rollup, in the band this repository actually produces.**
2. **Every check in a passing or skipped state.**
3. **Nothing non-terminal in the rollup.**
4. **Every workflow run at the change's current head revision COMPLETED.**
5. **The check set was computed against the workflow matrix on the trunk now.**

Each hides a way to be wrong, unpacked below.

### Clause 1 — zero failures is not green

Zero-failures-zero-pending is also exactly what an **empty** rollup reports — what
a change shows in the seconds before its workflow runs are created, and what
everything shows while the CI provider is down. One change merged here on a rollup
read as 0/0/0 eleven seconds before its runs existed; during a provider outage the
same day, six changes at once reported mergeable with zero checks.

So require a **count**, and require it to be in the band this repository actually
produces. A rollup carrying a fraction of the checks a full one carries is no
greener than an empty one.

**Measure the band; never trust a number written down.** It moves as the matrix
grows — in one programme the figure here moved four times in three days. Measure it
on changes merged *after* the last matrix change, because a change branched before a
new job landed is computed against the superseded matrix and legitimately reads
band-minus-one, which the count alone cannot distinguish from a change genuinely one
job short. Update the branch and re-check before judging this clause.

**A third cause is commoner than both, and its remedy is neither: the set is still BEING BUILT.**
A change opened moments ago legitimately reads a small fraction of the band while its runs are
created, and the count climbs to full over the following minutes. Updating the branch there
repairs nothing and restarts the wait. Clause 4 already separates the cases, so read it first when
the count is short: below band **with** live runs at the head is a set still building, and the
answer is to wait; below band with **none** is the case this clause is actually about.

**And one reading above the band is correct.** A change that adds a required job
sees that job in its own rollup, so it legitimately reads band-plus-one before the
standing band has moved — the arming proving itself rather than a miscount, and the
one case where a total above the band is not a reason to look further.

### Clause 2 — cancelled is not passed

A cancelled check is *completed*. A tally keyed on completion therefore counts it
as green. One change here was reported as "85 of 86 concluded, one check from
complete" when the truth was 79 passed, 6 cancelled and 1 pending — six re-runs
across four workflows away from mergeable, not one.

Count only the conclusions that mean the work ran and was fine. Read every other
value, cancellation included, as a check that has not passed.

**Skipped is accepted, and it says nothing about the trunk.** A surface-armed job is
skipped on every change that touches none of its surfaces — including, on the trunk, the
push that broke it. One gate here was skipped on the breaking commit and on every push
after, while the trunk's rollup read green throughout; it surfaced only when a change
happened to touch a surface that armed it. So when a change reds a gate the trunk is
green on, **check whether that job actually ran on the trunk** before concluding the
change caused it.

**If it did not, what you have is uncertainty, not a verdict.** The reflex — this change
reds it, so this change broke it — dispatches a fix worker onto that change's branch and
puts a workaround on a possibly innocent one; but the correction is not the opposite
reflex. A job that never ran on the trunk is silent about *both* sides: no evidence the
trunk is broken, and none that the change is clean. Only running the gate settles it.
Reproduce the actual failing check on a clean checkout of the change's base, or set up an
equivalent controlled comparison. If it fails there, the defect is the trunk's and the fix
belongs on a new branch off it; if it passes, the change owns the failure and the fix
belongs on the change's own branch.

**A right answer reached by the refuted inference is still a defect**, because the
inference is what you carry to the next case. One mayor here called a red change innocent
on the bare ground that its failing gate is skipped on trunk pushes, reproducing the gate
nowhere; a sibling landed shortly after, the failure cleared, and the call now reads correct
in the record. The outcome did not validate the reasoning, and nothing left in the record
tells the two apart.

### Clause 3 — require the terminal state, do not enumerate the bad ones

Do not test this by listing the states that block. The vocabulary is longer than it
looks — queued and in-progress are not the whole of it; hosts also return requested,
waiting and pending — and a rule that enumerates the bad states fails open on the
first state nobody wrote down, the same fail-open shape this whole section exists to
close.

Invert it: require the terminal state. Anything else blocks and is re-checked next
tick.

### Clause 4 — a settled rollup is not a quiet branch

A queued run's checks are not attached to the head commit yet, so the rollup cannot
report what it does not yet know is coming. One change here read `ok=78 canc=0
fail=0 pend=0 total=78` — settled by every field it exposed — while the branch's own
run listing showed two runs queued. Merging there ships it two workflows short.

**And the checks may not be LATE — they may be absent by construction.** A run triggered by
something other than the change event — a manual dispatch, a schedule — executes against the same
head revision and contributes NOTHING to that change's rollup, ever. The rollup is not behind; it
will never mention that run however long you wait. Measured here: a change whose rollup sat at the
FULL band, every check passed or skipped, terminal state clean, while a dispatched run against its
exact head revision ran on for a further sixteen minutes and put not one check in the rollup — whose
entire contents came from the four workflows the change event had triggered. **That is the opposite
reading from the case above, and the more dangerous one**: a short count announces itself and sends
you looking, where a full one closes the question. The heading is the rule and the count is not the
trigger, so query the runs whatever the count says.

So query the branch's runs as well as the rollup, and key that query to the branch
**and** to the current head revision. It fails in both directions and both have
already bitten:

* Keyed to neither — a global run listing truncated to the most recent N — a branch
  with four queued runs never appeared in the window at all, and the check reported
  "no live runs" for a branch that had four.
* Keyed to the branch but not the revision, it over-blocks: two changes sat at
  full-count for three ticks on runs still queued against a revision from before a
  re-trigger commit. A stale-revision run is not evidence about this head. **A guard
  that blocks forever is not safe, it is differently wrong.**

**Two mechanical traps live in this clause, both one character from correct, and they
fail in opposite directions:**

* **Case.** Your CLI may report check states in one case and run statuses in
  another. A comparison written against the wrong one counts every *finished* run as
  non-terminal, and every change reads blocked. This one fails **closed** — it never
  ships a bad merge, and it never announces itself either, presenting as a CI queue
  that will not drain. The tell is that every change reads identically blocked; real
  CI does not stall a whole queue in lockstep. Case-fold the comparison.
* **Abbreviated revisions.** A run query filtered by an abbreviated revision may match
  nothing and return an empty list — which reads as "no live runs" and passes this
  clause **vacuously**. Pass the full revision — **as read from the host, never one
  extended by hand**: a fabricated full-length revision matches nothing identically,
  while satisfying a "full revision" rule in form. The impossible pairing is the tell —
  a change with a full rollup and an empty run list is not quiet, it is mis-queried;
  re-read the head from the host and run the query again.

### Clause 5 — reading, not counting

This is the newest clause and the least visible, because every field you can read
says green.

Two changes merged four minutes apart here. The first added a required browser job;
the second was still checked against the base from before it, so the browser check
it should have run came back *skipped* and the newly required one never appeared in
its rollup at all. *"All required checks passed"* was a true statement about the old
matrix and said nothing about the matrix already on the trunk. That tree happened to
be green — an audit established it by hand afterwards — but the guard had not known,
which is the entire problem.

Note the cost structure: this is the direct price of the merge-the-queue-first
remedy below. **A burst is precisely when the base moves under a check set.**

So count the structural lines that landed on the trunk since the change's merge
base — job names, dependencies, conditions, action references — and then **read
them**. The count alone gives opposite verdicts for the same number:

* nine changed lines that are all cache steps add no job and rename no check. The
  matrix is intact; merge.
* four changed lines that add a job name with its own condition **are** a new
  required check — *but only where your changes run that workflow at all*. A job added
  to a workflow that nothing but a schedule or a manual button starts appears in no
  change's rollup, so the check set is intact and the merge should proceed. Unqualified
  this bullet fails **closed**, blocking a merge that should have gone; it is a
  qualification to the bullet rather than a case of its own because the bullet is what a
  skimming reader acts on. The diff that gave you the count also names the files it came
  from — read which events run each, keep only the keys in workflows your changes run,
  and where any survive, update the branch and re-check.

A display-name rename is a third case and reads like the second: the check set is
identical, the count is unchanged, only a label moved. That is intact — but you only
know by reading.

Do this **before** merging. It is easy to merge and then reason that the drift was
harmless; it usually is, and "usually" is what the other four clauses exist to refuse.

**And the set can move the other way, from the branch rather than the trunk.** Everything
above is about keys ARRIVING underneath a change; a change that retires a surface REMOVES
them, so its rollup legitimately reads below the band — and any programme that deletes a
subsystem produces a run of such changes, so this is not an edge case for the whole length
of that work. **The count cannot tell a legitimate narrowing from a silent disarm.
Read which key left, and check it against what the change itself deleted.** A check whose
entire subject went in the same change is retirement; a check that leaves while its subject
still stands is the fail-open shape the programme exists to hunt, and it arrives wearing
the same number. The two readings differ by one question, and the answer is in the diff.

### Merging

Check also that the diff matches its tracker item, that scope did not sprawl, that failure output
stays actionable, and that the quality-gates section is present.

**Read the FILE LIST against the item, not just the count.** Sprawl is EXTRA work and announces
itself. The opposite shape does not: **a change that silently REVERTS merged work is green,
well-formed, compiles, and passes every gate the tree has** — one carried 611 insertions against 910
deletions out of a worktree whose base had moved, putting back a symbol of a retired subsystem. So a
path the item does not explain is a finding whichever DIRECTION it runs, and direction is the test
rather than size: for a surprising file, ask whether something PRESENT on the branch is ABSENT from
the trunk.

**Do not reach for a gate here.** A revert is mechanically indistinguishable from a change that is
not one; the only discriminator is whether the reader EXPECTED those paths, so a gate would be a
control that cannot catch its own case.

Merge every green change **regardless of author**, including the operator's own.

**GREEN AND MERGEABLE ARE SEPARATE FACTS, and the five clauses only ever measured the first.** The
clauses read the change's own checks; not one looks at what LANDED while those checks ran. Ask the
trunk directly — a three-way merge into a scratch tree answers without touching your checkout.

**Its failure exit is TWO different verdicts and the status cannot tell them apart: read the
message, not the code.** A real conflict and *a head revision your checkout has never heard of* both
return non-zero, and the second is routine whenever the change was rebased through the host's own
update-branch control rather than by a worker's push. Fetch that head and ask again. Reading the
first answer as a conflict dispatches a rebase worker at a change that needs nothing — the expensive
direction, because the worker finds nothing wrong and says so. **Only a genuine conflict warrants
that dispatch.**

**Prefer server-side head-branch deletion to a client-side delete flag.** A branch still checked out
in a worktree cannot be deleted locally, and clients typically abandon the *remote* deletion along
with the local one — so the flag orphans a remote branch on every merge whose worker has not yet
reported. **A surviving remote ref is never grounds to reap a worktree early.**

**"Base branch was modified" usually means stale — until something is moving the base.** The
rejection reads like a lost race, so the reflex is to retry; usually the branch is simply behind and
the remedy is to update it. But any automation that writes to the trunk after a merge makes the race
real, and then no amount of updating fixes it — one change was refused thirty times, and updating
made it worse, because CI restarts and another automated commit lands inside that window. **Merge
the whole queue FIRST, then give the straggler a genuinely empty window.** If it refuses more than
about three times while blocking nothing, **shelve it**: persistence on an item blocking nothing is
its own gold-plating.

**A change reporting merge CONFLICTS is a third refusal, and its author is the remedy.** Where
several green changes share one serial-lane file, each merge flips the rest to conflicting — the
lane working as designed, and one hunk in one commit. It is not a red change, so the fix-worker path
does not apply. Message the author first; a live worker resumes with its context intact. Dispatch a
fix worker onto the existing branch only when the author is gone, and brief either to keep BOTH
intents hunk by hunk, never taking a side wholesale. **State your believed cause as a claim** — an
attribution made at the moment of least information, and authors resumed here have refuted it and
found the real one.

### After each merge

**Fetch the trunk, then fast-forward onto the remote-tracking ref** — two commands rather than a
pull, chained so a failed fetch cannot be followed by a merge against a stale ref. Name remote and
branch on the fetch; the bare form silently no-ops when an automated push races it. Then **verify
the tree, not the message**: compare the local head against the remote head, because a pull will
print `Updating <old>..<new>` on a head that did not move.

**If they disagree, read both again before believing it.** Automation writing to the trunk can land
between the two reads, so a perfectly synchronised checkout reports a mismatch. One disagreement is
not evidence. Read the *fetch's* own exit code for the same reason: a failed fetch and a subsequent
"Already up to date" print into the same buffer, and the reassuring line is the lie.

**Do not collapse that pair back into a pull.** A pull picks its merge target out of a scratch file
any concurrent process in the same checkout can rewrite — most exposed being the shared checkout
every agent reaches into to add a worktree — and a captured target does not fail honestly: measured
once silently fast-forwarding the trunk **onto a feature branch**, and once aborting with the
divergence message below on a clean, undiverged tree where that remedy is inert. **A failure that
borrows another cause's message cannot be discriminated by message text**, so retire the command
that reads the shared file rather than adding a third case. A remote-tracking ref cannot be captured
that way. The exposure grows with fleet size.

**An aborted fast-forward has two causes with different remedies, and the message says which** — but
only because the pair above no longer reads the shared file. Naming remote and branch cures the
silent no-op and neither abort, so read the text before reaching for a familiar fix:

* **"Local changes would be overwritten"** — uncommitted tracker state. Checkpoint, *then* clear,
  *then* retry. Clearing first reverts whatever the tracker just recorded.
* **"Not possible to fast-forward"** — genuine divergence, usually your own checkpoint against
  commits that landed while you made it. Rebase, then **push**: you are left ahead by one and the
  equality check cannot pass until it lands. **That intermediate state is expected, not a second
  failure.** Both reflexes are wrong — repeating the pull stays ahead, forcing equality discards the
  checkpoint the drill exists to protect. **Rebase onto the remote-tracking REF, not with a pull
  carrying a rebase flag**, which reads the same shared file this drill just retired: **a remedy
  inherits the hazards of whatever it reads**, so choose it by what it reads rather than by what it
  is called.
* **A truncated abort, or a ref-level race** — re-fetch and re-read. Reach for neither remedy above.

**Never wire a remedy behind a pipe.** `pull … | tail -1 || fallback` never runs the fallback,
because the pipeline's status is the filter's and the filter succeeded.

Then verify the worker closed its item (close it with a concrete cross-reference if not). **If
anything was routed into this change, verify it landed in the diff** before closing its owner — a
routed item is exactly the kind a worker may reasonably decline.

### When a change is not green

A real, repeated failure on the touched surface is not a flake and is never an override candidate.
Once you have established the failure is the change's own and not the trunk's — clause 2 says what
establishes it, and a gate that never ran on the trunk does not — dispatch a fix worker onto the
**existing** branch, running the **actual** failing gate rather than a proxy that already passed.

**If that change is already marked ready, convert it back to draft first and confirm the state
took.** The flag is set by whoever finished last, so a fix dispatch inherits one the previous author
set and the interlock is open before the second worker starts. It belongs to the dispatcher rather
than to the brief: the worker cannot set a flag that is already ready, and by the time it could the
merge has landed underneath it.

**But a red on a change still published as a DRAFT belongs to its author while that author is
alive.** A worker briefed to push as it goes publishes reds by design — one deleted a gate's witness
in its first commit and the gate itself in its third, so three required jobs read red for the twenty
minutes between, each a job the change was in the middle of removing. A fix worker sent there is a
second worker on one branch, the collision every fence exists to prevent, arriving wearing this
loop's own instruction. **So on a draft, read the failing job against the diff so far** — a red whose
subject the change is deleting is sequencing, not a defect — and wait for the ready mark or message
the author.

**A failure BEFORE any repository code ran is the second case, and the failing step names itself**
— a dependency download rate-limited, a runner setup step dying. The diff cannot have caused it, so
no second observation is needed: re-run and hold the re-run to the same clauses. **But re-running is
not a cure** — when SEVERAL changes fail that way rather than one job twice, fleet size is the cause
and the mayor says so.

**A check that never TERMINATES is a third case neither remedy fits.** It has not failed, so nothing
above triggers; it has not passed, so clause 3 blocks the change for as long as it runs — which is
exactly when the pressure to override arrives. It is not a sixth clause: clause 3 already blocks
correctly, and what is missing is only what to DO.

**Recognise it on the clock, against that job's OWN normal cost — measured, never remembered.** Job
costs move, so a remembered constant cannot detect a delta; the job's recent successful runs are the
baseline and reading them costs one query.

**But a SKIPPED run is reported as a successful one, and it contributes a zero-length sample.**
Where a job is conditional — surface-armed, path-filtered, gated on another job — most of its recent
"successes" never executed, and their start and end times are equal. A baseline drawn from them
collapses toward zero, after which every real run reads as overrunning by orders of magnitude. **It
fails in the alarming direction**, manufacturing a hang rather than hiding one, and the remedy it
invites is the cancel-and-re-run this section warns costs another wall-clock hour. **Discard the
zero-length samples before averaging, and check the survivors are a plausible sample size** — one
real run is a data point, not a baseline. The workflow's own total is a sanity check only: it bounds
the job from above and can be dominated by something else.

**A timeout kill usually reports as CANCELLED rather than as a failure**, which clause 2 handles
correctly while saying nothing about *why*. Elapsed time against the job's declared cap tells them
apart: landing ON the cap is a timeout kill, finishing well inside it is a superseding push or a
hand cancel. Expect rollup jobs to go red seconds afterwards — those fail BECAUSE of the
cancellation, so counting them as independent failures overstates the problem and points at the
wrong remedy.

**Then discriminate, because the two causes take opposite remedies.** UNRELATED jobs overrunning in
the SAME run is infrastructure — a diff cannot slow two jobs that share no surface with it or with
each other — so cancel and re-run, held to the same five clauses. ONE job overrunning alone, on a
surface the diff touches, is a hang the change introduced: dispatch a fix worker that reproduces it.
**A "cancel and re-run" written without that discriminator is worse than no remedy at all**, because
the reflex burns another hour and hides the infinite loop a worker just wrote. Neither case is ever
an override.

**A job with no declared timeout is the only reason this case needs a mayor-side remedy at all.** A
capped job converts a wedge into a fast, legible failure and reports itself; an uncapped one inherits
a host default measured in hours. Cap the jobs, and keep the remedy above for the ones that hang
anyway.

---

## 2. Dispatch

**Re-read the raw ready list every tick.** Do not infer the backlog from notifications, and do not
trust a filter that returned empty — an empty filter is not a dry backlog. One mayor under-saturated
at one to three workers while a hundred items were ready, because a homegrown filter kept answering
empty.

**Where a system of record reports its own failures somewhere the tracker is not — a nightly alert
channel, say — read that channel before the ready list.** The loops read the tracker and the review
queue and nothing else, so such a report reaches no dispatch until something copies it across. Give
every open alert exactly one open item keyed on the alert's own identifier; later ticks then do
nothing, the alert staying the live counter and the item being the dispatch edge. Two misreadings
both fail open: **a failed read is "did not sweep this tick", never "nothing red"**, and where the
alert closes only after several consecutive greens, *item closed, alert still open* is the expected
state after a fix lands rather than a new failure.

**Where your agent-instructions file carries no query for that channel, the read is not owed — do
not reconstruct one.** A project whose alert channel has gone quiet retires this read by the ABSENCE
of those values, which reads exactly like an oversight, so the coordinator looks, finds nothing, and
invents a query. **An invented query is worse than no read at all**, and its failure is undetectable
when the channel is genuinely empty: the wrong instrument and the right one agree, and their
agreement is not evidence.

**Order each item by the tracker's own timestamps, not by position and not by dates in its prose.**
The mechanics are set out for the worker under [*Common
preamble*](dispatch-prompt-template.md#common-preamble) and bind the mayor identically.

**A ruling can arrive as a child item OR as the close reason of a CLOSED dependency**, and the
second is commoner for a ruling that discharges a slice — normative text rather than an archival
note. Read the dependency list as well as the children, with each linked item's status and close
reason. **Enumerating by id prefix is not enumerating**: a generated id need not share the parent's
prefix, so a prefix filter returns the children while silently omitting the item that governs.

Filter out before shaping anything:

* items awaiting an operator decision or hold;
* items gated on another's merge;
* items whose surface a live worker holds;
* items colliding in a hot-zone file;
* items whose resource is exclusive and currently contended — **the only exclusion with a release
  condition the loop must eventually lift.** Where the exclusive items are all that REMAINS, the
  filter has stopped protecting the fleet and is simply refusing the backlog: say so, let the fleet
  drain, and take that work deliberately. A filter with no release condition becomes a permanent
  fence.

**A ready list overstates readiness by a lot** — roughly a fifth of "ready" items were genuinely
dispatchable, the rest fenced by something the tracker cannot represent. **Record the fence on the
item it fences, with what clears it, at the top, and in the same words every time.** The tick that
reads it back is scanning rather than reading, so a fence written truthfully in the middle of a long
field does not exist for the next tick — and a fence recorded on the item that *causes* it rather
than the item it *blocks* is never met by its own reader. What does the work is the sameness rather
than the wording.

**But a marker makes a DISCHARGED hold findable exactly as well as a live one.** So the marker is
written by whoever SETS the hold and **struck by whoever discharges it, in the same act** — not left
standing with a correction beneath. Where the tracker will not let you edit the original, append the
marker's negation in the same words, so a scan returns EVERY occurrence and **the newest governs**; a
hold can be set, discharged, then set again on a different question. **Treat a banner with no
discharge beneath it as a claim about the past.**

**Verify before dispatching, not after.** Check that the alleged broken symbol, missing file or stale
convention is still there. If it landed already, close as a verified duplicate.

**A count is a claim too, and a symbol-shaped check does not test it.** A census item asserts a
number, so the symbol still resolves while the count is zero or triple. Re-run the item's own census
at the current tip.

**Exclude any generated export from a tree-wide census.** A whole-database export inside the working
tree carries every title, description and comment, so it matches almost any identifier — inflating
the census into something that reads exactly like a correct one and sends its worker to sites that
do not exist, while flooding a context-bounded worker with tracker rows. Exclude it in the brief as
much as in your own check.

**The sibling that discharges an item is usually the one whose fence sent the work elsewhere.** A
tree fenced off from item A is exactly the tree item B is free to take. Read *across* a split's
siblings before dispatching any child, and treat a parent's remainder table as stale until
re-derived.

### Shape and saturation

Target a fixed number of concurrent workers — six is a workable ceiling for one operator — and
refill the instant a slot frees. Dispatch low-priority items to stay saturated; the goal is a
backlog trending to zero.

**Contention comes in four shapes and the ceiling bounds only the first.**

1. **Surfaces** — contention for files. This is what the parallel-or-sequential split reasons about.
2. **A heavyweight gate** — contention for the MACHINE. Six workers on six disjoint surfaces can
   still wedge one another inside one, and a wedge presents as a hang rather than a failure.
   Sequence dispatches that will all run one, or take the re-run knowingly.
3. **The shared ALLOWANCE** — a quota with no local evidence at all, since nothing in a worktree, a
   gate log or a run listing says how much is left. N workers are therefore not N independent bets:
   they fail TOGETHER, mid-run, on a limit none approached alone. **The remedy is not a smaller
   fleet**, which trades a recoverable failure for a permanently slower one; it is that *push
   continuously* makes a fleet-wide kill survivable, and that salvage is then wanted for the whole
   fleet at once.
4. **The tracker itself — where YOU are the competitor.** The first three degrade the workers; this
   one degrades the mayor, because a saturated fleet reads and writes the item store continuously
   and your own queries queue behind it.

**What makes the fourth worth writing down is the misreading, not the slowness.** A timeout on the
item store presents exactly as a corrupt or wedged store, and both reflex remedies are wrong:
re-running adds load to the overloaded thing, and *restoring the store's export from version-control
history* looks like recovery while silently reverting every item recorded since that revision.
**Treat a coordinator timeout as contention until something else proves corruption**, and ask the
store one thing per command while saturated.

**Take a first look that does not touch the store**: compare the committed export against the
working copy by size, and check the tree is clean. **But be exact about what that establishes** — it
reads the export and the working tree, never the database, so it cannot tell you that a timed-out
mutation committed, nor that the store holds nothing newer. **And equal counts are not equality**:
one checkpoint passed its row-count floor at 1,938 against 1,938 having dropped three items and
reverted two statuses, because the two sides substituted one-for-one. Size is a smoke test against
truncation. Verify properly once the contention clears.

**A queued measurement window makes an otherwise-free slot not free.** While a window needing a
quiet machine is queued, an item whose gate is heavyweight is a cost charged to that window rather
than a slot to refill: hold the fleet thin on purpose, record that on the window's item with what
releases it, and keep dispatching work whose gates leave the machine quiet. **Quiet is a property of
the whole set of gates an item arms, and no single classifier's answer describes that set** —
enumerate them. Documentation usually clears the bar, but earn that per item rather than per
category.

**And where a window registers that no peer WRITES inside its bracket, any write voids the run
however cheap it is.** Merging a change and creating a worktree cost the machine almost nothing and
are both writes, so a rule ordered by cost gets those exactly backwards: for such a window the fleet
is empty rather than thin, and you merge nothing either. Have the window record that condition as a
test — capturing the trunk tip and the worktree list at both brackets — so a violation is reported
rather than assumed.

**An empty fleet is not a quiet machine.** The processes a gate starts routinely outlive it: the run
ends, the change merges, the tree is reaped, and build servers and browsers stay resident with
nothing pointing at them. So a mayor can hold the fleet thin, empty it, merge nothing, and still be
handed a machine it has measured nothing about. **But counting those processes is the same error one
level down** — presence is not load, and a count answers presence. One idle box carried a hundred-odd
resident processes accounting for 0.14 of 2.6 busy cores between them, the largest consumer being an
editor. **Measure LOAD, at both brackets.**

Pick the shape by kind first, then priority, then size — the shapes are under [*Dispatch
shapes*](dispatch-prompt-template.md#dispatch-shapes).

**The unit is the block, not the file.** Two items that read as *nominally different concerns* can
still land in the same paragraph, which is why the collision is invisible when you schedule them and
cheap to avoid only there. The remedy is under [*Fences*](dispatch-prompt-template.md#fences).

**Dispatch immediately on clear.** A clear, unblocked item goes out now, not next tick.

**And write the dispatch's record line as you dispatch it** — the line a later tick uses to find this
worker's completion report. What it must carry is under *Reaping*, deliberately not repeated here: a
field list kept in two places will disagree with itself.

### Routing a finding onto a live worker's file

Under fan-out, findings keep landing on files somebody already holds — from an audit,
from another worker's report, from your own reading.

**Resolve an exact owner first.** Update or reopen the item that already owns the
finding, or file one when none does. **Filing is not dispatching** — the first wording of
this rule conflated them, and it is a second *dispatch*, not a second item, that puts two
workers on one file.

**Then route it**, rather than waiting for the holder to finish and losing the context it
was found in: message the worker that holds the file, and the fix lands inside the change
that is already open. Six routed findings landed that way in one evening, none
conflicting, several fixed minutes after they were found. **That the change is still open is
a precondition rather than scene-setting**: routing works because there is an open change for
the fix to land in, so test that before testing whether the agent is reachable. Where the
holder's change has already merged there is nothing for the fix to land in, and a reachable
agent is not a route — take queue-and-note below, even though no fence is being widened.

**But routing does not create an owner, and that is the half people drop.** The message
lives in one agent's transcript, so if that agent dies, times out, or reasonably declines the
extra item — declining is often correct — the finding evaporates, and the audit that found it
has already run. An audit caught exactly this: a mayor had *"routed it only to a transient
worker, leaving no durable owner"*, the sole record being a close reason on a **different**
item — a record on the wrong object, because nobody reads a closed item looking for open work.
So put **one note on the owning item** saying what was routed, to whom, and what happens if it
does not land.

**Sometimes routing is the wrong move altogether.** It widens a deliberately bounded fence
mid-flight, which is how a bounded repair becomes an unbounded one. Then leave that owner
queued or sequenced instead, and note the overlap on the in-flight item — saying explicitly
that the item is owned elsewhere and is **not** part of the in-flight repair, so nobody
reading that item's newest notes adopts it.

Route-and-note, or queue-and-note. An owner plus one note is common to both and is the whole
safeguard. It does not want to grow past that: no routing registry, no tracking field, no
script.

### Quiescent is a valid state

At the tail of a drain, dispatch is one-unblocks-the-next, not fan-out. Hold, surface what
needs the operator, and do not manufacture work.

### You are one of the concurrent writers

The scratch area dispatched agents share is keyed to the SESSION, not to a worktree, and the
mayor writes there too — change bodies, working notes, gate artefacts — while N workers do.
Every naming rule you paste into a brief binds you equally. The block that carries it is
written in a second person aimed at a dispatched agent, so it does not read as addressed to
you: one mayor enforced that rule on eight dispatches and then lost its own change body to a
peer, under exactly the bare name the rule forbids. **A rule scoped to a ROLE exempts whoever
does not identify with the role** — when a hazard belongs to a shared resource, scope it to
the resource.

Naming one shared area re-creates that exemption one level down. Reaching for a system temp
directory feels like stepping out of a shared area into a private one, and it is the reverse:
every session on the machine writes there, where the session scratch area holds only the
current session's agents. It changes the resource without changing the hazard, and it reads
as exempt precisely because it is not the area the rule names — so the scope is **every
directory you do not exclusively own**, not the one a rule happens to name.

---

## 3. Backlog reread

**Dispatch reads the ready list; this loop reads everything else.** A ready list omits held,
blocked and worker-held items by construction — and those are the ones that fail quietly, because
nothing in the short loop ever looks at them again.

So read **all** open items from the raw list — not a saved filter, not the ready view, not what you
remember filing — ordering each by the tracker's timestamps and re-enumerating children, as
*Dispatch* explains.

**Every note you leave here becomes the NEWEST text on that item.** Reader and writer are not
symmetric: a reader can walk past a stale note below newer text, but nothing defends against a stale
note that IS the newest text — a newest-first walk lands on it and stops. A note re-derived from the
DESCRIPTION rather than from the notes that overtook it is newer by timestamp and older in
substance. **The tell is mechanical: a note repeating a figure some later note retracted re-derived
instead of re-checking.** Cite the check you made, or say you are summarising and from what.

**So do not re-record a fence already recorded where a scan will meet it.** *Dispatch* says write
the fence; this loop says re-test it. Run together on a cadence they produce a note per fenced item
per pass, each true, each the newest text, until the item's newest layer is a stack of no-ops.
**Write only what the item does not already carry** — a fence that CLEARED, a changed condition, a
question its existing text leaves a reader to derive. *Unchanged* is a finding about your tick and
belongs in the tick's report.

**Check ONCE whether your tracker's update verb appends or REPLACES.** Every rule above assumes
items accumulate; where the verb replaces, the walk you are teaching readers to make runs over text
you deleted. No error, no warning, and the item afterwards looks well-maintained. Where the tracker
exports to a versioned file the loss is fully recoverable, so this is a reason to check the verb
rather than to fear the damage.

Five things surface here and nowhere else.

**1. A fence that has cleared.** The dispatch tick records a fence and nothing removes it when the
condition is met; the item never returns to *ready*, so only a full read notices. **Recording a
fence is worth nothing without the loop that reads it back.**

**2. A dependency that has outlived what it was enforcing** — the thing it waited for shipped, and
the item is still blocked. Force the close and write the reasoning on the item (*Tracker mechanics*).

**3. A closure that reverted.** Verifying at the moment you close is not enough. **Read the item's
notes before re-closing anything** (*Tracker mechanics*). And the window is not your own tick: a
closure can revert in a session nobody is auditing and sit reopened for days.

**4. A hold costing more than it is holding.** Separate *needs a decision* from *needs work under a
decision already made* — the second is dispatchable now and tends to sit in the first pile. For the
rest, record what the hold costs: which items are behind it, and what stops if it stays. A hold with
no cost written on it reads as free.

**But read WHY it was set before writing what it costs, because the count reads the same either
way.** An oversight and a deliberate dormancy put identical numbers behind a hold. Where the item
records a disposition — parked pending a decision nobody has taken, with a reactivation trigger —
the queue behind it is parked by the same decision. **Cost is only cost against a want**, and
counting feels like compliance, so a mayor who stops at the number stops one paragraph short.

**The mirror case: a hold that expires on a DATE while its reason does not.** A deferred item
returns to the ready list on its own, carrying its priority and an empty blocker column, reading as
MORE legitimate than an ordinary item because a reappearance looks like something happened. Nothing
happened; a date passed. **Read defer dates across the whole deferred set** — they get set in
batches, so a cluster lapsing together makes the list look suddenly and misleadingly rich. Where the
reason will outlive the date, write that on the item BEFORE the date arrives: afterwards nothing
prompts anybody, because this is the one hold that removes its own evidence.

**5. An item that is already DONE.** The four above interrogate the item's metadata; none asks
whether the WORLD already contains what it wants. Such an item presents as perfectly healthy — open,
unblocked, correctly prioritised, and pointless — and the short tick cannot catch it, because that
tick looks straight at dispatchable items and this one *is* dispatchable.

**Check the TREE in bulk here**, not one item at a time at dispatch, which is too late and too
narrow. For each open id, search the trunk's merged history for it. Three mechanics decide whether
the sweep is honest:

* **Search the whole commit MESSAGE, not the subject.** Where an identifier goes is a house style
  nobody enforces, so assume the body. Subject-only fails OPEN — zero is the answer that causes a
  dispatch, and it looks identical to a correct one.
* **Flatten each commit to one record before matching**, or a line-oriented search splits one change
  across several and attributes an identifier to its neighbour.
* **Anchor the identifier so it ends where it ends**, or a shorter id matches every longer sibling.

**A matching commit is a POINTER, never a closure.** Some hits are partial work; some are the very
change the item was filed AGAINST — a fact the title usually carries in a word like *still*. Confirm
at source before closing.

**And the sweep has an inverse that fails the other way: work that HAS landed on an item still
legitimately live.** Where a process audits merged changes, an audit reopens the item that owned a
residual, so the tree confirms the original deliverable exactly as it would for a finished item.
This is the more dangerous direction, because the tree AGREES with closing and only the notes carry
the finding. **The tree answers *did this work land*, never *is this item finished*; when the two
disagree, the notes govern.**

In-progress items are read here for scope and fences; *is this worker alive?* belongs to the
stranded sweep.

**Most passes find nothing, and a pass that finds nothing is complete.** What is not complete is a
pass that reports nothing without having read the raw list.

---

## 4. Posture, and the stranded sweep

### Posture

**Keep the project's stance as a single quoted block in exactly one file, and paste that
block verbatim into every dispatch preamble.** This loop is that file's natural home,
because it is also the loop that re-reads it.

**Never summarise a stance** — [`dispatch-prompt-template.md`](dispatch-prompt-template.md)
carries why a paraphrase fails. Which clauses turn out to be load-bearing is worth knowing in advance, because a summary
sheds those first. In one project: *"trust the programmer"* rejects a nagging diagnostic;
*"close minutiae rather than actioning it"* lets an item die with its reasoning recorded
instead of consuming a worker; *"a finding is a CLAIM"* stops an audit's output being
mistaken for a queue. The licence to refuse is load-bearing too — in one session three of
six dispatches came back as reasoned refusals, each worth more than the work would have
been, because a migration performed on a false premise costs far more than a tracker item.

Also reassert any **voice** the operator has asked for. Voice drift returns within about ten
turns, which is exactly why it belongs in a recurring loop rather than in one session's
memory.

Then reassert in **one line**: orchestration, not implementation.

If recent dispatches have drifted — the mayor coding, a missing boundary block, the stance
absent or *paraphrased* rather than pasted, an override misused, minutiae actioned instead of
closed, an audit finding dispatched without its premise checked at source — flag it explicitly
and name the dispatch. Otherwise one line is enough.

### The stranded sweep

**Start from the worktrees, not from the in-progress list.** Unless your dispatches claim their
items, a live worker's items still read *open*, so that list is empty by construction rather than by
health and a streak of clean sweeps is a streak of reads of an empty set. Enumerate worktrees, map
each to its items, and read in-progress items as one more signal.

The cost of missing a strand is not a stale tree: a dead worker's items look dispatchable, and the
next tick sends a **second worker to the same branch**.

**Every signal below is indirect, and they share one blind spot.** A worker in its final minutes —
last gate, then tidying scratch files and links — works outside the worktree, outside version
control, and is too busy to answer. Tip, fetch clock, write clock and a direct message then read as
dead *together, for one cause*, so corroborating one with another proves nothing. **Frozen on every
clock is AMBIGUOUS**: it is what healthy foreground work looks like and what a stopped worker looks
like, and re-reading the same clocks never converts it into evidence — an hour of freeze is the same
non-signal as a minute. A freeze measured in hours is unremarkable on a heavyweight gate.

**So if your tooling reports a running agent directly, read that first** and treat the clocks as the
fallback. A harness event meaning *this agent has stopped* is not one more signal to weigh — it is
the diagnosis, and it outranks everything here.

1. **Tip revision — record the revision id, never an ahead count.** An ahead count is rebase-
    invariant, and rebasing is what a briefed worker does constantly, so it fails on the common case.
    Read the ref the worker's commit updates *directly* and prefer a read that performs no refresh:
    the local ref moves on commit, the published ref only on push, and the published one lags in the
    direction that reads as death.

    **An unchanged tip says nothing alone** — a worker inside a long gate commits nothing by design.

2. **Activity clocks, and three ways they say "nothing" for different reasons.**
    * A worker that is only READING writes nothing. The opening minutes of every dispatch are exactly
      this, because every brief tells the worker to read first.
    * A worker that POLLS edits nothing for hours, but a fetch ordinarily writes a clock in that
      worktree's own metadata. Read it twice, 30–60s apart. **Movement proves life and needs no
      corroboration**; stillness restores the ambiguity, and absence says only that no fetch wrote
      there — the write is suppressible, so a poller can be invisible to this test.
    * A span shorter than the worktree's own age counts the checkout rather than the worker, and
      returns a large number that reads as proof of life. **The worktree's age is its CREATION time,
      not its modification time**, which the writes you are hunting keep bumping. Run the clock at two
      different spans before believing either.

    **Where your tracker records a claim, that is the one positive signal a purely reading worker
    produces**, and it lands outside the tree where no file clock can see it. Its absence proves
    nothing.

3. **Message, and read the reply rather than the send.** Resuming beats redispatching — the context
    is still there. The commonest strand by far is a worker that detached a long gate and then ended
    its turn waiting for an event nothing sends; those recover intact the moment somebody asks for a
    status.

    **A send may be ACCEPTED for an agent that is already stopped** — acceptance is a queue
    confirmation, not a liveness report, and for a stopped agent the promised delivery never comes.
    So *no live task* is rarely something the tool reports; read it as a request unanswered across a
    window you declare, corroborated by the discriminators above. The error is cheap in one direction
    only: a late reply costs nothing, acting on a wrong *no task* destroys the run.

4. **Only with no live task AND no tip movement:** push existing commits, set the item back to open
    with what was found and salvaged, and redispatch.

    **Read WHY it stopped first — the reason chooses the remedy, and quota death, crash, timeout and
    a detached-gate strand look identical from outside.** Where the stop names an exhausted shared
    allowance, redispatch is not a remedy: every attempt fails identically and every attempt spends.
    Salvage, **merge whatever is green** — integration draws on none of that allowance and may unblock
    the queue — then stop dispatching.

    **A stated reset is a floor, not the only release.** An operator act can restore the allowance
    early with nothing telling you. Spend one resume probe on the worker with the most context to
    lose, and read the probe's LIVENESS rather than waiting for words: an agent under an exhausted
    allowance dies within seconds of resuming, so minutes of running is positive evidence. Stillness
    is not the converse.

**Two clocks on a change record two different EVENTS — name the event before reading one.** The
authored time is replayed unchanged by a rebase; the committed time is rewritten by it, and by
amends and squashes too. Liveness asks *did this worker do something recently*, which is the rewrite
event. Do not write a rule assigning one clock to prose and the other to liveness: both are real and
each is correct for its own event. **For a date tying a change to an EXTERNAL event — a run, an
outage — neither clock is proof**; an author date can be set to any value, so use an anchor that
event recorded, or a chronology you declare and stand behind.

**A finished change is not a strand, and publishing it is still not yours to infer.** A green change
whose published head matches its tip, needing only the flag its author sets last, invites you to set
that flag. Do not: the flag is the interlock precisely because a merge command refuses a draft.

**The sanctioned route is the operator's decision to STOP that agent, which settles liveness by
making it false — then merge, in that order.** Stopping is an act with a definite outcome where
every clock above is an inference with none. Reversing the order reaches the same end state by the
forbidden route and is indistinguishable afterwards. Verify the change against §1 in full; do not
reopen the stopped worker's items.

**It is not an escape hatch from the ambiguity.** It authorises publishing a change whose author you
have positive reason to think is finished — it is not a way to resolve a freeze the clocks cannot
read, and putting that freeze to the operator spends their attention on a question time usually
answers by itself. The residual risk belongs to stopping, not to merging: you may kill an unpushed
fix. What bounds it is that you merge the PUBLISHED head, which CI graded.

**Never build a commit from someone else's uncommitted work.** Only that worker knows whether it
forms a coherent change.

---

## 5. Hygiene

**A measurement window makes this whole loop unsafe, and this is the only loop where that is not
obvious.** Removing a worktree changes the worktree list, which a window registering an
exclusivity condition captures at both of its brackets — so a reap during one is recorded as a
violation and can cost the window its runs. The dispatch loop states the condition; nothing carries
it here, and hygiene is the loop that performs the measured action. Hold the whole loop until the
window reports. Nothing in it is urgent.

### Reaping

**Only a worker's own completion report authorises reaping its worktree.** Not a merged change, not
a clean tree, not elapsed time, not directory age, not "no commits and no open change", not clean
and merged together. All six were adopted in one session and all six were wrong — cleanliness
perversely so, because a worker that has pushed everything exactly as briefed shows a clean tree
throughout a twenty-minute gate, so *the better the discipline the likelier the kill*.

Reap a live worktree and you destroy the run, and **the wreckage does not announce itself as
infrastructure**: gates die naming real, present files as missing, which reads to the worker as a
genuine regression.

**The report must say the work is FINISHED.** An interim status, a progress note, a partial hand-off
and a change-opened announcement are the agent speaking, not reporting done. The operational test:
**can you quote the sentence where this agent says it is done?** If not, the tree stays.

**A gate the worker backgrounded is still that worker running** — and where the harness caps
foreground commands below what a full gate needs, that is the common case rather than an edge one.
**One worker is not one worktree**: a worker may build a second for its gate run, so an unfamiliar
tree belongs to someone until its agent reports.

A stale directory is the entire cost of waiting.

**Never pair a merge with a reap, and never put the reap in the merge loop.** A merged change is the
moment a tree *looks* finished, which is exactly the pull. A rule encoded in a loop condition is a
rule delegated to a predicate that cannot see what you know.

#### Finding the report

**Record which worktree belongs to which agent at DISPATCH time**, one line per dispatch in a
mayor-local file. Clearing the mayor's context destroys its ability to quote a report; this supplies
a route back to the sentence. Dispatch time, not report time — dispatch always precedes the report,
so the line is on disk before a clear can matter. The rule itself is unchanged, and the cost of
following it is monotone accumulation of unreapable trees.

**Nothing validates that line, so specify its fields somewhere tracked.** A version-ignored file's
own header is the only description of it that exists, which is the condition under which a header
and its rows drift apart unobserved — and here they did, both carrying five fields so no count
disagreed, while a reader addressing a field BY NAME got its neighbour's value. **Two fields belong
to this rule and the rest to the project**: the worktree, and the agent id. **The reap test reads no
field of that line at all** — it reads the report, which the id leads you to.

**The id buys the TRANSCRIPT, not a live conversation.** Messaging does not reach across a clear.
**And an id may key several locations** — a per-agent scratch sink is one, the store holding the
session's own transcript another. An empty file keyed by the id is a fact about *that location*,
never about the id, and a sink that is empty for most agents and non-empty for one or two reads as a
survey rather than as an artefact. Enumerate the id's remaining locations before concluding anything.

**With no id recorded, the report is unindexed rather than missing.** Search transcripts for the
worktree's own path — a dispatch names it and so does the report — and read a bounded TAIL, since
these are whole-session logs. **But the match is many-to-one and most hits are not the worker's
own**: the dispatching session names that path, and so does every peer brief listing in-flight write
surfaces. Three tests separate them, and the first is not sufficient alone:

* **Count the hits per file.** A worker's own log names its tree tens to hundreds of times against a
  peer's once or twice. But ranking on this can still hand you a peer that simply worked longer.
* **The report's own stated root is decisive.** A file whose report names a different root is not
  that worker's, however often your path appears in it.
* **Where a directory name has been reused, separate the incarnations by the branch the tree carries
  NOW** — the earlier one names a branch that is gone and tends to end mid-flight.

**A worktree the MAYOR occupied has no agent**, so the search comes back empty for that reason alone
— and empty is the reap test's blocking condition, which makes such a tree permanently unreapable.
**The rule does not bend: for that tree the mayor IS the agent**, and its authorising report is the
mayor's own knowledge. Identify it by the branch the work was PUBLISHED from — an author's tree is
on it, while an auditor, reviewer or monitor carries a branch named for what it inspects. **Tip
revision cannot identify an occupant**, only the work. **And do not promote occupancy into a proxy**:
a tree at the trunk with nothing of its own is the ordinary state of a worker just created, the most
dangerous tree to remove rather than the safest. Expect automated processes to hold trees too, at
commits they did not author and under recycled names.

A transcript path the platform documents as an implementation detail is not a contract, and retention
sweeps typically DELETE rather than truncate — so whether to hold report text somewhere of your own
is the operator's call.

**Reaping on the report is correct and still costs something**: a worker can need its tree after
reporting, when its change hits a conflict. Pushed commits make that survivable. **Do not add a
second condition to a rule whose strength is that it has one** — but nothing requires reaping the
moment the report arrives, and deferring until the change merges is free insurance.

### Removing

**If your project links or junctions a shared dependency tree into worker worktrees, never remove a
worktree with the plain command.** It follows the link and deletes *through* it, emptying the shared
tree — silently, exiting successfully, breaking every local build. **And removal is not the only
write that follows a link**: an installer a gate runs inside the worktree rewrites the shared target
the same way, so a tree can be emptied while its worker is still alive and no cleanup has run. Count
every shared tree a worker's gates could have reinstalled, not only the ones a removal touches.

Put the safe sequence in a **script**, not in prose — prose was tried and the class recurred. It
should snapshot every shared tree, unlink each *link* under the worktree (the link only, never a
recursive delete), verify each is gone, remove the worktree, then re-check the snapshot and **fail
loudly if the signature moved**, naming the recovery command.

**Capture the snapshot at runtime and count both sides the SAME WAY.** A remembered constant cannot
detect a delta; a top-level count cannot see files vanishing from under surviving directories, so
count immediate entries *and* recursive files. And a listing that hides entries does not merely
undercount — it turns the comparison into an offset, and in one direction that offset **CANCELS a
real loss of its own size**: two hidden entries make a tree read the same before and after two
visible ones are destroyed. The other direction is only a false alarm, which is the cheaper half and
the one you meet first.

**So make the decisive control a clock rather than a count** — the newest modification time inside
the tree, which a removal moves and a change of listing options cannot. Note which entry carries it
before you start, and check whether that entry is one the listing form suppresses.

**Sweep with a narrow force flag, never a blanket one.** A disposable-only force removes a tree only
when every untracked path is build or gate output, and refuses one holding a modified tracked file,
a note or a draft. Not hypothetical: when that guard was written, four worktrees held uncommitted
analysis or source edits a blanket force would have destroyed silently — far worse than a worktree
that will not reap.

**A refusal to remove has modes that are not interchangeable.** Nine worktrees were once read as
locked, waited out for two days, and were all simply dirty:

* **Dirty** — modified or untracked files. **Waiting never clears this.**
* **Held** — the disposable sweep stopped on something that is not build output. Nothing was deleted.
* **Locked** — clean and intact, a live process holding a handle. Genuinely transient: note and retry.
* **Partial** — a *husk*. The tool deregistered the worktree and deleted its metadata, then hit the
  lock and stopped. It no longer appears in the listing, pruning has nothing to clear, and a status
  check inside it *fails and prints nothing* — which is why a clean/dirty test reads a husk as clean.
  Retrying the ordinary path is futile; the registered-worktree check refuses a husk forever. **But
  do not reach for a raw recursive delete, which is the operation the top of this section forbids** —
  a husk still holds every file the worktree had, links among them. Use the tool's acknowledged-husk
  path, which disarms the link before deleting anything; a tool offering no such path is one to fix
  rather than work around. **A partial removal kills a running gate exactly as a complete one does.**

**A husk is identified, never inferred.** "Not listed, no metadata" is equally true of an ordinary
directory, a mistyped argument and an unrelated project — one tool handed a temp directory created
seconds earlier unlinked the dependencies inside it and called it a destroyed tree. Refuse an
unregistered path unless the caller acknowledges it **and** its contents are recognisable as this
repository's, established from version control's own object database. Never spray that
acknowledgement across a sweep list.

### Branches

Delete merged local and remote worker branches — **never one whose change is not verifiably merged**.

**Enumerate the remote set separately: it is not a subset of the local one.** Everything else in this
loop starts from the worktrees, which is right for reaping and blind here — a branch whose worktree
and local ref are both gone still exists on the remote, and nothing you are looking at mentions it.

**Where the project merges by REBASE, the two obvious containment tests are both wrong.** A rebase
replays every commit under a new identity, so a merged branch never becomes an ancestor of the trunk
and never stops reading as *ahead* of it. Ancestry keeps merged branches forever; the ahead count
destroys unmerged ones.

**The test is patch-equivalence** — what each commit *does* rather than which object it is. Its two
answers are not symmetric, and reading them as symmetric is the error to avoid:

* **Contained is proof.** Delete only on it, and nothing is ever destroyed.
* **Not-contained is only the ABSENCE of proof.** Patch identity is computed over the diff including
  its CONTEXT lines, so a sibling change landing in neighbouring lines of the same file changes your
  commit's identity and leaves fully-merged work reading as unmerged.

**So the rule is safe and its reading is not.** A mayor who reads *not contained* as *this branch
has unmerged work* hunts for work that landed weeks ago, and a branch list read as a backlog is a
list of phantoms. Cheap triage: look for the lines that commit ADDED at the tip. **But that is
triage and it is wrong in both directions** — a line search has no notion of path, multiplicity or
order and cannot see a deletion at all, so a commit whose substance is a removal scans clean having
been inspected for nothing, while a rename or reformat upstream hides work that is fully present.
**To CONCLUDE, compare the commit's whole patch against the tip at the paths it touches, deletions
included.** Ask what the instrument compared, not what it answered: a scan returning EVERYTHING
reads as corroboration where a scan returning nothing at least reads as silence.

**Containment is not the only question. A branch with unlanded commits and no proposed change may be
a DELIBERATELY RETAINED RECORD.** A spike is the standard case: its findings are promoted as prose
and its diff is meant never to land, so *pushed, unlanded, nothing proposed* describes the design.
Ask the tracker who owns it — and ask three things most defaults get wrong:

* **Include CLOSED items**, because a retention ruling lives on a closed item by definition.
* **Do not filter TO closed either**, which hides an open item carrying a live release condition.
  Ask for all statuses.
* **Search the full export, not the title index.** Such rulings are written in close reasons, which
  a title search cannot see at any status.

**Every defence above finds a record that EXISTS. The case with no record is a LIVE worker's
branch** — a dispatch in flight has written its name into no item, so the strongest query above
returns zero, and zero authorises deletion. **Subtract the live set before reading any zero as an
orphan**: the branches the worktree listing reports, and the ledger rows whose worker has not
reported.

**Key destructive operations on identity, never on a name.** Branch names repeat and prefix-match,
so a search term matches siblings and may rank one first — reading one branch's state as another's.
Ask for the exact head-ref field and compare for equality yourself.

**But equality over a LISTING still filters a WINDOW, and that failure is silent.** A listing returns
the most recent N; your test is correct and its answer is only as wide as the listing, and a zero
over the wrong range reads exactly like a zero over the right one. **Prefer an instrument that takes
the identity as INPUT** — filtering at the source, before paging, caps how many MATCHES return
rather than how far back it looked, so it cannot turn a proposed branch into one that reads as never
proposed. Pass an explicit limit anyway: a reused name can carry several changes, and completeness
still matters where their states do. **An empty answer is still not a licence to delete** — it
settles that this query matched nothing, not that you asked the right repository, states, spelling
or credentials. **Exercise the query in both directions before trusting it.**

Throughout, the asymmetry governs: mistaking a record for residue destroys it, mistaking residue for
a record costs a branch nobody was using.

Then prune stale remote-tracking refs and clear any stray stashes.

**Merge state is the test for a branch, never for a worktree.** Conflating the two is how the reaping
rule goes wrong. An open change is one more reason to leave a worktree alone; the test is still its
agent's report.

### The residue

*"Reap only on the worker's own report"* is correct and it leaves residue: an agent that vanishes will
never report, and its worktree becomes unreapable.

**Keep the rule. Let the residue accumulate, and clear it on explicit operator authority rather than
inventing another proxy.** A stale directory is cheap. A destroyed run is not.

---

## 6. Method reread

**This loop earns its place, but not by re-reading.** Re-reading unchanged files spends context for
nothing. Do two things instead:

1. **Check what changed** since the last pass — in these documents and in your loop command files.
   When a method rule changes, re-read the matching command file. A link checker catches renamed
   files, not semantic drift.
2. **Check the rules you have been *enforcing* against what the tree actually does.** That is where
   the drift lives — twice this found a rule the documents asserted universally that the tree did
   not support, one of them introduced by a merge the mayor approved.

Watch specifically for:

* **A measured constant that has gone stale.** The check-count band is the standing example: written
  down, moving, and stale exactly where it has to be right.
* **Unsatisfiable quantifiers.** *"Every gate prints X"* when a third do not. Workers facing an
  unsatisfiable rule do not stop — they improvise, and the improvisation varies. **When a rule says
  *every*, check the quantifier.**
* **Blocks that have diverged from the command files that paste them.** Two copies of one rule
  disagree within days.
* **A hazard recorded without the test that discharges it** — *"beware X"* with nothing saying how
  to tell X from the legitimate case that looks identical. Whoever wrote it had just performed that
  test, so it read as part of the observation rather than as something needing words. This document
  says elsewhere that a remedy without its discriminator is worse than no remedy; that judgement
  applies to its own prose, and this loop is the only place that checks whether it does.

**Read a loop's page when you are about to do that loop's work, whatever this loop's cadence says.**
A destructive or unfamiliar step performed hours before its page comes round is a step taken without
it, and the reread that follows finds nothing wrong because the damage is already done.

Report one or two lines unless you find real drift. If you find drift, fix the document or the
command file — do not just note it.

**But the repair is the step most likely to need repairing, and it fails in one particular way: a
correction tends to overshoot into the INVERSE of the claim it replaces.** A wrong sentence is
usually wrong by asserting too much, and the evidence that exposes it feels like evidence for the
opposite — so the fix arrives with the original's confidence pointing the other way, reading as
*more* reliable because it was written in response to being caught.

**The discharging test is one question and it costs nothing: what would you expect to SEE that is
different if the new claim were false?** If the only answer is *the evidence that refuted the old
one*, nothing has been established — that evidence rules the old claim out and leaves the
replacement standing among several survivors, which is the old error re-pointed. Where the honest
answer is that the observation supports neither side, **say so and let the guidance rest on the
asymmetric cost of the two errors instead**: that is a real reason to act one way, and it survives
the next counterexample where a re-pointed claim does not.

**And the fix is bounded in change, not in search.** What this loop finds is mechanical, so a defect
in one place usually has siblings — [*"Bounded repair" bounds the change, not the
search*](dispatch-prompt-template.md#name-the-discriminator) binds the mayor's own repairs exactly
as it binds a worker's.

**Weigh WHICH artefact to change.** Where a reader-side rule has failed repeatedly, more text is
usually not the repair. Two remedies that worked landed the other way: an item whose OLDEST field
was rewritten to open with its disposition, after three workers had been dispatched at work that
should not exist; and a guard that refused an edit the rule had already forbidden in words. Both
changed the artefact a reader meets rather than the instruction they had read and recited. **And a
guard is not a rule that cannot fail** — one added in an evening was run the next hour and misread,
because a check executed carelessly returns the same reassuring answer as no check at all.

**Where the project arms a mayor-side commit guard, the mayor's own repair goes through a
mayor-occupied worktree.** The guard refuses the mayor checkout for every non-tracker surface, these
documents included, and that refusal is the guard working. Author the fix in a worktree of your own
and merge it on the ordinary criterion. Two mechanical gotchas: **the refused commit exits non-zero
with the files still STAGED**, so read the commit's own exit rather than the output tail — the
refusal scrolls away under later lines; and those staged files then abort the next update with a
message that never names the refusal that caused it, so restore them rather than diagnosing the
update.

---

## Tracker mechanics

These belong to no single loop and bite in all of them.

**An instrument can be RIGHT and still answer a different question from the one you asked.** The
rule about an instrument that says *"nothing here"* covers a wrong answer; the remedy for that —
exercise it against an input it should flag — is inert here, because nothing is malfunctioning and
the control comes back green. Four in one session, one per loop: a count of running workers read as
*the machine is quiet*; a count of resident processes read as *the machine is busy*; a marker scan
finding a banner read as *this item is held*; a note true when written read as *true now*. **Write
the question the instrument answers, in its own terms, beside the question you are asking.** The gap
is invisible in the answer and obvious once both sentences are on the page — and this is the harder
half, because a wrong answer gives you something to be suspicious of and a right one does not.

**A marker scan fails in BOTH directions, so it is a CANDIDATE FILTER and never a verdict.** It
over-reports items that QUOTE a marker or whose marker has been struck, and under-reports holds
written in ordinary prose. A second scan built to discriminate fails the same way, because marker
text typically instructs whoever rules to strike it, so the word *struck* appears inside every live
marker. Narrow to a handful, then READ each candidate, and **report the number you read, never the
number you scanned**. Where a project wants this machine-checkable the durable answer is a
structured field rather than a marker in prose — an operator's decision, but every failure here is a
consequence of encoding state in text that also discusses state.

**A dependency can outlive what it was enforcing.** An item blocked "until X lands" stays blocked
when X is later reopened for an unrelated residual. Force the close and **write the reasoning on the
item**: what the dependency enforced, that it was satisfied, and why the reopen does not re-block
merged work.

**Items usually close on change-*open*, not change-*merge*** — so "closed" does not mean "on the
trunk". Check the merge before treating a dependency as discharged.

**Verify closures by reading the status field keyed on the item ID.** Keyed on an internal row
identifier, a re-import reports phantom losses; searching the whole record matches the word "open"
inside a title.

**A command's echoed output is not confirmation that it did anything.** This is the gate-behind-a-
filter rule reaching every command the mayor runs. Trimming output to the last line hides it
perfectly: where a close carries a long reason, the last line *is* the reason text, so a refusal and
an acceptance look identical.

**Exit status and mutation are two different questions, and only the first is cheap.** A non-zero
status is proof the command refused, so **always read it**. A *zero* proves the command reported
success, not that state changed — an idempotent call legitimately does nothing, and a durable
operation can acknowledge locally before the postcondition you care about is true. **When you need a
state change, or a durable one, re-read the target as well.**

**A tracker write can silently revert** — a rollback, a re-import over the top — so the session's
"closed" count quietly overstates. **Re-check what you closed across the sequence that exports the
tracker, restores it from version control and publishes it**, reading BOTH sides of that sequence: a
single check afterwards tells you a close is missing but not which side lost it, and the two have
different remedies. **Do not infer the mechanism from the coincidence** — a multi-step sequence is
no evidence about which step moved the state.

**But a status change made by a LIVE worker is that worker speaking, not corruption.** The rule
above trains you to read an unexpected status as a bad write and supplies no competing reading. A
worker that un-claims an item is often saying something precise — commonly that part of it is the
operator's. Its report is the authority and usually lags the status by minutes, so read the report
before acting on the clock.

**Read the item's notes before re-closing anything.** Where a process audits merged changes, expect
closed items to reappear, and expect chains: a fix lands, its audit reopens for a second carrier,
that fix's audit for a third. **The reflex to re-close a "reverted" item destroys a real finding and
looks like tidiness.**

**A reopen that records no finding is not yet a finding.** A bare status flip — close reason wiped,
nothing appended — leaves nothing to read and nothing to dispatch, and both reflexes are wrong:
re-closing destroys a finding still being written, treating it as ready dispatches a worker at work
nobody has named. Mark it as awaiting its finding, in the same scan-findable words each time, and
give the writer one full pass. Then re-close, restoring the original reason the flip cleared and
recording the reopen's emptiness.

**And a wiped closure makes any brief that CITES it unsatisfiable** — a worker sent to read text the
reopen deleted finds an empty field and reads the absence as its own failure. Restore the text or
cite where it was preserved, **before** dispatching. Having verified the preservation yourself is no
protection: verification and briefing are separate acts.

**Checkpoint tracker state on the heartbeat.** Many trackers auto-stage but never commit, so a long
session's state strands locally.

**A checkpoint is two operations and the harness can kill it between them.** The export contends
with every worker's tracker writes, so under a full fleet it can outrun a command cap: one kill left
the commit made and the push not, and the re-run reported *nothing to checkpoint* — true, with the
remote still behind. **Verify the remote against the local head after every checkpoint**; the
script's message answers only the commit.

**Long tracker text does not travel as a shell argument.** A batch of notes passed as quoted strings
can fail to parse before a single note is written, and a quoted heredoc is not a cure. Write the
text with a tool that is not a shell and drive the tracker from that file, so the transport has no
quoting to get wrong.

**If your tracker exports a whole-database file, treat it as generated.** Never resolve a conflict
in it by taking one side wholesale — both sides are full exports, so picking one discards every item
the other recorded. Resolve, then **regenerate**. And never let a worker commit it: a worker branch
carries a snapshot from when its worktree was created, so committing it time-travels the tracker.

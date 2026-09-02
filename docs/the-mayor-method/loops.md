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
  required check. Update the branch and re-check.

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

Check also that the diff matches its tracker item, that scope did not sprawl, that
failure output stays actionable, and that the quality-gates section is present.

**Read the FILE LIST against the item, not just the count — "scope did not sprawl" does not
cover it.** Sprawl is EXTRA work and announces itself, because extra files read as new work. The
opposite shape does not: a change that silently REVERTS merged work is green, well-formed,
compiles, and passes every gate the tree has. One here carried twenty-four files at 611 insertions
against 910 deletions, out of a worktree whose base had moved, putting back a symbol belonging to
a retired subsystem — the branch's content was OLDER than the trunk's. **So a path the item does
not explain is a finding whichever DIRECTION it runs**, and direction is the test rather than
size: for a surprising file, ask whether something PRESENT on the branch is ABSENT from the trunk.

**Do not reach for a gate here.** A revert is mechanically indistinguishable from a change that is
not one; the only discriminator is whether the reader EXPECTED those paths, so a gate would be a
control that cannot catch its own case. The worker-side half — diff against the merge base before
pushing — is on every brief; this is the mayor-side half, for the worker that never ran it.

Merge every green change **regardless of author**, including the operator's own.

**Prefer server-side head-branch deletion over a client-side delete flag.** A branch still
checked out in a worktree cannot be deleted locally, and clients typically abandon the *remote*
deletion along with the local one — so the flag orphans a remote branch on every merge whose
worker has not yet reported. The repository setting has none of that: the server deletes the head
branch as the merge lands and does not care what a worktree holds. Zero orphans by construction.
A manual remote delete then survives only for a ref that outlives a verified merge — head-ref
protection, or a head living in a fork the server will not touch. **A surviving remote ref is
never grounds to reap a worktree early.**

**"Base branch was modified" usually means stale — until something is moving the
base.** The rejection reads like a lost race, so the reflex is to retry; seven
retries here proved otherwise, the branch was simply behind, and the remedy is to
update it and re-check.

But any automation that writes to the trunk after a merge makes the race real. A post-merge hook
that commits asynchronously means the base never holds still during a burst, and the next merge is
refused for a reason no amount of updating fixes — **one change here was refused thirty times**,
and updating the branch made it worse before better, because CI restarts and another hook commit
lands inside that window. The remedy is counterintuitive: **merge the whole queue FIRST, then give
the straggler a genuinely empty window.** Raising its priority is just more attempts against a
moving base. If a change refuses more than about three times while blocking nothing, **shelve it**
— persistence on an item blocking nothing is its own gold-plating.

**A change that reports merge CONFLICTS is a third refusal, and neither remedy above fits — its
author does.** Where several green changes share one serial-lane file, each merge flips the
remaining ones to conflicting; that is the lane working as designed, not a defect, and the
conflict is one hunk in one commit. It is not a red change either, so the fix-worker path under
*When a change is not green* is not the move. Message the author first — a live worker resumes
in its own worktree with its context intact, and two such resumes here each recovered in minutes
— and dispatch a fix worker onto the existing branch only when the author is gone. Brief either
one to keep BOTH intents, hunk by hunk, never taking a side wholesale. **And state your believed
cause as a claim**: the mayor's attribution of a conflict is a premise like any other, made at
the moment of least information, and both authors resumed here refuted the stated cause and
found the real one — a different sibling's landing than the one just merged.

### After each merge

**Fetch the trunk, then fast-forward onto the remote-tracking ref** — two commands rather
than a pull, chained so that a failed fetch cannot be followed by a merge against a stale
ref. Name remote and branch on the fetch; the bare form silently no-ops when an automated
push races it. Then **verify the tree, not the message**: compare the local head against
the remote head. A pull printed `Updating <old>..<new>` twice in one session here while
the head had not moved at all.

If they disagree, **read both again before believing it.** Any automation that writes
to the trunk after a merge can land between the two reads, so a perfectly synchronised
checkout reports a mismatch. One disagreement is not evidence; this false alarm fired
four times in one session and was never real.

Read the *fetch's* own exit code for the same reason. A failed fetch and a subsequent
"Already up to date" print into the same buffer, and the reassuring line is the lie.

**Do not collapse that pair back into a pull.** A pull picks its merge target out of a scratch
file any concurrent git process in the same checkout can rewrite — most exposed being the shared
checkout every agent reaches into to add a worktree — and a captured target does not fail
honestly. Measured here: in one arrangement it silently fast-forwarded the trunk **onto a feature
branch**; in another it aborted wearing the divergence message below, on a clean tree zero commits
ahead, where that remedy is inert. **A failure that borrows another cause's message cannot be
discriminated by message text**, so the repair is to retire the command that reads the shared
file, not to add a third case. A remote-tracking ref cannot be captured that way: it is written
atomically, and every concurrent fetcher sets it to the trunk head or newer, both of which still
fast-forward. The exposure **grows with fleet size** — dispatching immediately after merging puts
the concurrent fetches and the fast-forward in the same seconds.

**An aborted fast-forward has two causes with different remedies, and the message says
which** — and it says which only because the pair above no longer reads the shared file.
Both reproduce unchanged under that pair, so the change costs nothing here. Naming remote
and branch cures the silent no-op but neither abort, so read the text before reaching for a
familiar fix:

* **"Local changes would be overwritten"** — uncommitted tracker state. Checkpoint it
  first, *then* clear, *then* retry. Clearing before checkpointing reverts whatever the
  tracker just recorded.
* **"Not possible to fast-forward"** — genuine divergence, usually your own checkpoint
  against commits that landed while you made it. Rebase, then **push**: the rebase
  replays your commit on top and leaves you ahead by one, and the equality check cannot
  pass until it lands. **That intermediate state is expected, not a second failure.**
  Both obvious reflexes are wrong — repeating the pull stays ahead, and forcing equality
  discards the very checkpoint the drill exists to protect.

  **Rebase onto the remote-tracking REF, not with a pull.** *Rebase* names an outcome, and
  the command that first comes to hand for it is a pull carrying a rebase flag — which reads
  the same shared scratch file this drill just retired for the fast-forward. The argument two
  paragraphs up applies here unchanged and is easy to miss, because it was made about the
  step rather than about the remedy for that step's own failure: retiring the reading command
  in one place and readmitting it in the next is no protection at all. Measured under a
  saturated fleet: with remote and branch both named, the pull form aborted a second time
  wearing the contamination message — peers adding worktrees had left several refs in that
  file — while rebasing onto the ref succeeded immediately on the same tree. **A remedy
  inherits the hazards of whatever it reads**, so choose it by what it reads, not by what it
  is called.
* **A truncated abort, or a ref-level race** — re-fetch and re-read. Do not reach for
  any remedy above.

**Never wire a remedy behind a pipe.** `pull … | tail -1 || fallback` never runs the
fallback, because the pipeline's status is the filter's and the filter succeeded.

Then verify the worker closed its tracker item (close it with a concrete cross-reference
if not). **If anything was routed into this change** — a finding messaged to its holder
rather than dispatched — **verify it landed in the diff** before closing its owner. A
routed item is exactly the kind a worker may reasonably decline.

### When a change is not green

A real, repeated failure on the touched surface is not a flake and is never an override
candidate. Once you have established the failure is the change's own and not the trunk's
— clause 2 above says what establishes it, and a gate that never ran on the trunk does
not — dispatch a fix worker onto the **existing** branch that runs the **actual** failing
gate, not a proxy that already passed.

**If that change is already marked ready, convert it back to draft first, and confirm the state
took.** The flag is set by whoever finished last, so a fix dispatch inherits one the previous
author set and the interlock is open before the second worker starts — [the exception the
criterion names](#the-criterion), and the only one this loop has to remember, because this loop
is what creates it. It belongs here rather than in the brief: the worker cannot set a flag that
is already ready, and by the time it could, the merge has landed underneath it.

**But a red on a change still published as a DRAFT belongs to its author, for as long as that
author is alive.** The draft flag means the worker has not finished, and a worker briefed to push as
it goes will publish reds by design: one here deleted a gate's witness in its first commit and the
gate itself in its third, so the draft read three required jobs red for the twenty minutes between,
each one a job the change was in the middle of removing. A fix worker dispatched onto that branch is
a second worker on one branch, which is the collision every fence exists to prevent, and it arrives
wearing the loop's own instruction. So on a draft: read the failing job against the diff so far —
a red whose subject the change is deleting is sequencing, not a defect — and either wait for the
ready mark or message the author. The fix-worker path opens only once the author has marked the
change ready, or has stopped, which the stranded sweep establishes and this loop does not.

**A failure BEFORE any repository code ran is the second case, and the failing step names
itself** — an action download rate-limited, a runner setup step dying. The diff cannot have caused
it, so no second observation is needed: re-run the failed jobs and hold the re-run to the same
clauses. **But re-running is not a cure** — when SEVERAL changes fail that way rather than one job
twice, fleet size is the cause and the mayor says so.

**And a check that never TERMINATES is a third case that neither remedy fits.** It has not
failed, so nothing above is triggered; it has not passed, so clause 3 rejects the change for
as long as it runs — which is exactly when the pressure to override arrives. **It is not a
sixth clause**: clause 3 already blocks the change, and correctly, and what is missing is only
what to DO about it.

**Recognise it on the clock, against that job's OWN normal cost — measured, never
remembered.** A remembered constant cannot detect a delta, and job costs move; the recent
successful runs of the same job are the baseline, and reading them costs one query. Measured
here: a job whose last successes took 1.4 to 1.7 minutes was forty-nine minutes in and still
going. **A timeout kill usually reports as CANCELLED rather than as a failure**, which clause
2 handles correctly — cancelled is not passed — while saying nothing about *why*. Elapsed
time against the job's declared cap tells them apart: landing ON the cap is a timeout kill,
finishing well inside it is a superseding push or a hand cancel, and both numbers are
readable. Expect rollup jobs to go red seconds afterwards; those fail BECAUSE of the
cancellation and are consequences rather than findings, so counting them as independent
failures overstates the problem and points at the wrong remedy.

**Then discriminate, because the two causes take opposite remedies.** UNRELATED jobs
overrunning in the SAME run is infrastructure — a diff cannot slow two jobs that share no
surface with it or with each other — so cancel and re-run, and hold the re-run to the same
five clauses like any other. ONE job overrunning alone, on a surface the diff touches, is a
hang the change introduced: dispatch a fix worker onto the existing branch that reproduces
it, exactly as a failing gate does. **A "cancel and re-run" written without that
discriminator is worse than no remedy at all**, because the reflex it teaches burns another
wall-clock hour and hides the infinite loop a worker just wrote. Neither case is ever an
override.

**A job with no declared timeout is the only reason this case needs a mayor-side remedy.** A
capped job converts a wedge into a fast, legible failure inside its own cap and reports
itself; an uncapped one inherits whatever the host defaults to, which is measured in hours,
so nothing ever converts it and the check simply never terminates. Cap the jobs — and keep
the remedy above for the ones that hang anyway.

---

## 2. Dispatch

**Re-read the raw ready list every tick.** Do not infer the backlog from
notifications, and do not trust a filter that returned empty — an empty filter is not a
dry backlog. One mayor under-saturated at one to three workers while a hundred items
were ready, because a homegrown filter kept answering empty.

**Read the host's alert channel before the ready list.** Where a nightly system of record
reports its own failures somewhere the tracker is not — a hosted issue that the workflow opens
and then edits in place, say — that report reaches no dispatch until something copies it across,
because the loops read the tracker and the review queue and nothing else. Measured: an alerter
opened its issue on the first red night, notified three more times across the week, and the
redness was still found by hand on day seven, because nothing in the loop read the channel it
wrote to. So the short tick makes one read of that channel, before the ready list, and ensures
every open alert has exactly one open tracker item keyed on the alert's own URL. Once it does,
later ticks do nothing: the alert stays the live counter and signature record, and the item is
the dispatch edge, which the same tick can then fill. Two misreadings of that read both fail open.
A failed read is "did not sweep this tick", never "nothing red" — an empty result on a clean exit
is the only "nothing red" there is. And where the alert carries a recovery delay, closing only
after several consecutive greens, "item closed, alert still open" is the expected state for the
nights after a fix lands, not a new failure; re-file only when the alert itself says the failure
has returned since the close, and never by reopening the closed item, whose close reason is a
normative record. The query, the label and the field that carries the URL are repository values,
and they live in the agent-instructions file, per the rule at the head of this page.

**Read the newest note first, and order the item by the tracker's own timestamps** — not by
position, and not by dates written in the prose. The mechanics are set out for the worker under
[*Common preamble*](dispatch-prompt-template.md#common-preamble) in
`dispatch-prompt-template.md`, and they bind
the mayor reading the item exactly as they bind the worker: `bd show | tail` is not a
read-the-newest method, `bd history <id>` lists real mutation times newest-first,
`bd history <id> --json` carries the snapshot that says *what* changed, and you walk adjacent
pairs newest-first to the first change to a text-bearing field.

**A child bead is one of two carriers, and naming one reads as exhaustive.** Re-enumerate an
item's children — a ruling is sometimes recorded as a new child rather than as a note — but a
ruling that discharges a slice is more often the CLOSE REASON of a bead already CLOSED and linked
as a *dependency*, which is normative text rather than an archival note. So read the dependency
list as well as the children, and read each linked item's status and close reason before you brief
from the item's own words. **Enumerating by id prefix is not enumerating**: a generated id does not
share the parent's prefix, so a prefix filter returns the children while silently omitting the bead
that governs. That is how one dispatch went out to delete a tree that a closed dependency had ruled
KEEP, its ruling having sat on the item's own dependency list the whole time.

Filter out before shaping anything:

* items awaiting an operator decision or hold;
* items gated on another's merge;
* items whose surface a live worker holds;
* items colliding in a hot-zone file;
* items whose resource is exclusive and currently contended — but this exclusion has a release
  condition, and it is the only one on the list that the loop itself must eventually lift. Where
  the exclusive items are all that REMAINS, the filter has stopped protecting the fleet and is
  simply refusing the backlog. Say so, stop refilling, let the fleet drain, and take that work
  deliberately. A filter with no release condition becomes a permanent fence.

**A ready list overstates readiness by a lot.** In practice roughly a fifth of "ready"
items were genuinely dispatchable; the rest were fenced by something the tracker cannot
represent. **Record the fence on the item** when you find it, with what clears it, or
every tick re-derives the same conclusion.

**And record it where a scan will find it, on the item it fences, in the same words every
time.** The tick that reads a fence back is scanning rather than reading — it has the whole
list to get through — so a fence written truthfully in the middle of a long field is a fence
that does not exist for the next tick, and the rule above is satisfied while its stated purpose
fails. Measured on one item: probed for the six phrases its peers used to mark exactly that
state, all six came back absent, and it read as the only free item on the board — the answer
was there, in different words, and cost a full read of twenty-odd thousand characters to reach
a conclusion one line could have given. A second item the same session cost a full read for the
neighbouring reason: the sentence naming its hold sat on the item that fenced it rather than on
the item fenced, where its own reader would never meet it. Pick one marker, put it at the top,
and reuse it verbatim. What does the work is the sameness rather than the wording, because a
scan is only ever as good as its phrase roster and it answers *clear* in the same voice whether
the item is clear or the roster is short.

**But a marker makes a DISCHARGED hold findable exactly as well as a live one, and that is the
cost of the rule above rather than an argument against it.** A scan cannot tell the two apart:
the banner is the same string either way, and the note that discharged it is somewhere below,
where the scan was introduced precisely so nobody has to read. Measured: an item sat in the
awaiting-a-decision pile for a whole session, reported to the operator that way every tick,
while a note further down recorded the ruling and said in terms that the header was discharged
and the work should be dispatched. The scan found the header. It was right that the header was
there and wrong about everything that mattered.

So the marker is written by whoever SETS the hold, and **it is struck by whoever discharges it,
in the same act** — not left standing with a correction beneath. If your tracker will not let you
edit the original text, add the marker's negation in the same words, so that a scan for the
banner returns EVERY occurrence and **the newest one governs** — a hold can be set, discharged,
and then set again on a different question, so finding a discharge is not finding the end. **Treat
a banner with no discharge beneath it as a claim about the past**: check who set it and whether
what they were waiting for has since happened, before you report it as the current state.

**Verify before dispatching, not after.** Grep that the alleged broken symbol, missing
file or stale convention is still there. If it already landed, close as a verified
duplicate.

**A count is a claim too, and a symbol-shaped grep does not check it.** A census item
("54 chains", "15 hits", "eleven sites") asserts a number, not a symbol, so the symbol
still resolves while the count is zero or triple. Re-run the item's own census at the
current trunk tip.

Measured drifts in one session: 8 → 9, "four hits" → 23 lines, "roughly 6–10" → 23,
"4 of 21 cached" → 13 of 21. Two items once went out in one wave against work merged
fifteen hours earlier; both workers returned a correct "already fixed", so the waste was
two dispatches rather than a wrong result.

**Exclude any generated export from a tree-wide census.** Where the tracker keeps a
whole-database export inside the working tree, it carries every title, description and comment
and therefore matches almost any identifier a census greps for. Both halves of the cost are
real and the second is the dangerous one: one such grep returned a hundred kilobytes of tracker
rows before a single real hit, a material loss in a context-bounded worker; and nothing in the
output announces that a tracker row is not a source file, so an inflated census reads exactly
like a correct one and sends its worker looking for sites that do not exist. Exclude it in the
brief as much as in your own check.

**The sibling that discharges an item is usually the one whose fence sent the work
elsewhere.** A tree fenced off from item A is exactly the tree item B is free to take.
Read *across* a split's siblings before dispatching any one child, and treat a parent's
remainder table as stale until re-derived.

### Shape and saturation

Target a fixed number of concurrent workers — six is a workable ceiling for one
operator — and refill the instant a slot frees. Dispatch low-priority items to stay
saturated; the goal is a backlog trending to zero.

**A heavyweight gate is an exclusive resource, and that ceiling does not bound it.** The
parallel-or-sequential split reasons about *surfaces*; a gate heavy enough that two runs
cannot coexist is contention for the *machine*, so six workers on six disjoint surfaces can
still wedge one another inside it — measured once, and both runs hung rather than failed.
Sequence dispatches that will all run one, or take the re-run knowingly. **This is the
constraint a measurement window is already fenced under, seen from the other side**: a window
is held for a quiet machine, and a heavyweight gate is what makes the machine loud. Keep one
vocabulary for the two rather than inventing a second.

**And the fleet shares one ALLOWANCE — the same shape a third time, and the one the ceiling
hides best.** Surfaces are contention for files and a heavyweight gate is contention for the
machine; the account quota every worker draws on is contention for a resource with no local
evidence at all, since nothing in a worktree, a gate log or a run listing says how much of it
is left. So N workers are not N independent bets — they fail TOGETHER, mid-run, on a limit none
of them approached alone. Measured here: a saturated fleet of six died inside the same minute,
three of them holding work that was not on the remote. **The remedy is not a smaller fleet**,
which would trade a recoverable failure for a permanently slower one; it is that *push
continuously* is what makes a fleet-wide kill survivable. Read a worker that has not pushed for
a long stretch as the exposure it is, and expect the salvage under *The stranded sweep* to be
wanted for the whole fleet at once rather than for one worker.

**And the fourth shape is the one where YOU are the competitor: the tracker itself.** The
three above degrade the workers. This one degrades the mayor, because a fleet at the ceiling
is a fleet reading and writing the item store continuously — every brief's history walk, every
verdict, every close — and the mayor's own queries queue behind them. Measured at six workers:
four consecutive coordinator commands exceeded a two-minute ceiling in one cycle, including a
plain listing and, with some irony, the note recording this observation; all are second-scale
on an idle fleet.

**What makes it worth writing down is not the slowness but the misreading.** A timeout on the
item store presents exactly as a corrupt or wedged store, and the reflex remedies are both
wrong: re-running adds load to the thing that is overloaded, and *restoring the store's export
from version-control history* — which looks like recovery — silently reverts every item recorded
since that revision. **Treat a coordinator timeout as contention until something else proves
corruption.** The one export observed apparently hung here had in fact completed.

**Take a first look that does not touch the store at all**: compare the committed export against the
working copy by size or line count, and check the working tree is clean. That costs the resource
under load nothing, and it answered in a second what re-running could not answer in three minutes.
And ask the store for one thing per command while saturated: two queries chained in one invocation
time out where each alone returns.

**But be exact about what that look establishes, because it is easy to promote and the promotion is
this document's own measured fault.** It reads the EXPORT and the working tree — never the database.
So it cannot tell you that a mutation which timed out committed, that the store holds nothing newer
than the file, or that the store is healthy. A clean tree proves only that the tracked export matches
the revision. **And equal counts are not equality**: one project's checkpoint passed its row-count
floor at 1,938 against 1,938 and silently dropped three items and reverted two newer statuses,
because the two sides had substituted one-for-one. Size is a smoke test against truncation, which is
what it was built for. Once the contention clears, verify the store against the export the way the
project's own checkpoint does — by stable id, modification time and status — before saying anything
is consistent or that nothing is owed.

**A queued measurement window makes an otherwise-free slot not free.** The drain clause in
the filter list does not reach this case — it lifts only once the exclusive items are all
that remains — so where ordinary work is still available, nothing above holds you back and
refilling is itself what fences the window. While a window needing a quiet machine is queued
and unstarted, an item whose gate is heavyweight is not a slot to refill but a cost charged
to that window: hold the fleet thin on purpose, say so on the window's own item with what
releases the hold, and keep dispatching work whose gate leaves the machine quiet.
Quiet is a property of the whole set of gates an item arms, and **no single classifier's
answer describes that set** — enumerate what covers the item and confirm each of those
gates leaves the machine quiet. Documentation and prose usually clear that bar and are what
keeps the fleet busy while a window waits, but earn that per item rather than granting it
per category. The instruction is to stop making the machine loud, not to stop working.
But that test answers loudness alone, and a window may register more than quiet. Where
one registers that no peer writes inside its bracket, **any write voids the run however
cheap it is** — merging a change and creating a worktree each cost the machine almost
nothing and each is a write, so a rule ordered by cost gets those two exactly backwards;
for such a window the fleet is empty rather than thin, and you merge nothing either. Have
the window record that condition as a test rather than an assurance, capturing the trunk
tip and the worktree list at both brackets, so a violation is reported rather than assumed.

**An empty fleet is not a quiet machine.** Everything above treats loudness as something
the mayor causes — gates it arms, work it dispatches — so the remedy it reaches for is to
dispatch less and finally nothing. That closure does not hold, because the processes a gate
starts routinely outlive it: the run ends, the change merges, the worktree is reaped, and
build servers and browser processes stay resident with nothing left pointing at them.
Measured on an idle project with no worker in flight, no work outstanding and two worktrees
removed minutes earlier: well over a hundred resident processes and a port still bound, not
one of them a gate anybody had armed. So a mayor can follow every word of this section —
hold the fleet thin, enumerate what the item arms, empty the fleet, merge nothing — and
still be handed a machine it has measured nothing about. **But do not stop at counting
those processes, which is the same error one level down and is how this clause was first
written.** A five-second per-process census of that same box put total load at 2.6 cores of
24: the hundred-odd processes accounted for 0.14 of it between them, the largest single
consumer was an editor, and they were resident and IDLE. Presence is not load, and a count
answers presence — so stopping there swaps one proxy for another while feeling like
measurement, and it feels like measurement precisely because the instruction was to measure
the machine. **Measure LOAD, at both brackets — not the fleet that was supposed to have
quietened the box, and not a census of what happens to be resident on it.** The test
named just above does not reach this: a trunk tip and a worktree list say nothing about
what is still running.

Pick the shape by kind first, then priority, then size. The shapes are under
[*Dispatch shapes*](dispatch-prompt-template.md#dispatch-shapes) in `dispatch-prompt-template.md`.

**The unit is the block, not the file.** The tell is that the two items read as *nominally
different concerns*, which is why the collision is invisible at the moment you schedule them,
and cheap to avoid only there. What it cost once, and the sequencing remedy, are under
[*Fences*](dispatch-prompt-template.md#fences) in `dispatch-prompt-template.md` — deliberately not
repeated here.

**Dispatch immediately on clear.** A clear, unblocked item goes out now, not next tick.

**And write the dispatch's record line as you dispatch it.** The mayor-local line that lets a later
tick find this worker's completion report is written here and read in the hygiene loop. What it must
carry, which field the reap test uses, and why an untracked file needs its fields specified
somewhere else at all, are under *Reaping* there — deliberately not repeated here, because a field
list kept in two places is a field list that will disagree with itself.

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

**Dispatch reads the ready list; this loop reads everything else.** A ready list answers
*what could go out right now*, so by construction it omits held items, blocked items and
items a live worker already holds — and those are the ones that fail quietly. A dispatchable
item that goes wrong is caught on the next tick, because the tick is looking straight at it;
a held item that should have been released is caught by nobody, because nothing in the short
loop reads it again.

So on a medium cadence, read **all** open items from the raw list — not a saved filter, not
the ready view, not what you remember filing. On each one order the material by the tracker's
own timestamps rather than by position, and re-enumerate any children; *Dispatch* above
explains why.

**And every note you leave here becomes the NEWEST text on that item.** The currency rules are
written for the reader, and reader and writer are not symmetric: a reader can walk past a stale note
that sits below newer text, but nothing defends against a stale note that IS the newest text — a
newest-first walk lands on it and stops. So a note built by re-deriving an item's state from its
DESCRIPTION, rather than from the notes that already overtook it, is newer by timestamp and older in
substance, and it buries the true state under your own signature. **The tell is mechanical: a note
repeating a figure some later note retracted is a note that re-derived instead of re-checking.**
Cite the check you made, or say plainly that you are summarising and from what.

**And check, ONCE, whether your tracker's update verb appends or REPLACES.** Every rule above
assumes an item accumulates — that notes accrete, that currency is a walk over that accumulation.
That is a property of the TOOL, not of the item, and where the verb replaces, the walk you are
teaching a reader to make runs over text you have already deleted. Expect no error and no warning:
the item afterwards looks well-maintained, because what remains is your note, correctly formatted,
saying something true. Two mayors on one project hit this two DAYS apart, and the second read the
first's account of it four hours after repeating it. The short interval is the alarming half: a
fresh, accurate account of the hazard was sitting in the same tracker and still did not reach the
next reader, which is why this belongs here rather than only on an item. **If your tracker exports
to a versioned file,
the loss is fully recoverable** — the pre-damage text sits in any checkpoint predating the write, so
this is a reason to check the verb rather than to panic about the damage.

Five things surface here and nowhere else.

**A fence that has cleared.** The dispatch tick records the fence on the item and moves on,
and nothing removes it when the condition is met. An item sequenced behind another's merge
becomes dispatchable the moment that change lands, and the tracker does not notice: the
short tick keeps skipping it because it is still not *ready*, and the note is the only
record that anything is being waited on at all. Re-testing those conditions is this loop's
most productive minute — **recording a fence is worth nothing without the loop that reads
it back.**

**A dependency that has outlived what it was enforcing.** The item is still blocked, the
thing it was waiting for shipped, and only a full read notices. The remedy, and why it
needs its reasoning written onto the item, is under *Tracker mechanics* below.

**A closure that reverted.** Verifying at the moment you close is not enough, so re-check
what this session closed. **But read the item's notes before re-closing anything** — what a
reappearance usually is, and what the reflex destroys, is under *Tracker mechanics* below.

**A hold that is costing more than it is holding.** Separate *needs a decision* from *needs
work under a decision already made* — the second is dispatchable now, and it tends to sit in
the first pile. For the ones that genuinely need the operator, record what the hold is
costing: which items are behind it, and what stops if it stays. A hold with no cost written
on it reads as free, and the cheapest-looking item in a list is the one that stays there
longest.

**But read WHY the hold was set before you write what it costs, because the count reads the
same either way.** An oversight and a deliberate dormancy put the identical number of items
behind a hold, so a queue length alone converts into urgency whichever one you are looking at.
Where the item records a disposition — parked pending a decision nobody has taken yet, with a
reactivation trigger and an act that does not expire — the queue behind it is parked by the same
decision and there is no cost until somebody wants the thing it gates. **Cost is only cost
against a want.** This is the harder half to catch, because the instruction above tells you
exactly what to count, and counting feels like compliance: a mayor who stops the moment it has
the number stops one paragraph short of the disposition that changes what the number means.

**And the mirror of that case: a hold that expires on a DATE while its reason does not.** Where
the tracker can defer an item until a date, the date is a hold it enforces and then silently
stops enforcing. An item parked for something no date can settle therefore returns to the ready
list on its own, carrying its priority and an empty blocker column — and reading as MORE
legitimate than an ordinary item, because a reappearance looks like something happened. Nothing
happened; a date passed. Measured on one board: four deferred items, three of them sharing a
single date, every one with no dependency recorded, and two of them the heads of chains holding
four more items behind them. Each needed an operator act the date says nothing about.

**So read the defer dates across the whole deferred set rather than on the item in front of
you** — they cluster, because they get set in batches, and a cluster lapsing together makes the
list look suddenly and misleadingly rich. Where the reason will outlive the date, write that on
the item BEFORE the date arrives. Afterwards nothing in the list prompts anybody, which is the
whole difficulty: this is the one hold that removes its own evidence.

**And the fifth: an item that is already DONE.** The four above all interrogate the item's
own metadata — its fences, its dependencies, its status, its hold. None of them asks whether
the WORLD already contains what the item wants, so an item whose work has landed presents as
perfectly healthy: open, unblocked, correctly prioritised, and finished. Nothing in the short
tick catches it either, because that tick reads the ready list and looks straight at
dispatchable items, and this one *is* dispatchable — it is simply pointless.

Measured on one board in one session: an item recorded as *ruling needed* had had its ruling
made, executed and merged, and was escalated to the operator **twice** as a live decision
before anybody looked at the tree; a second was dispatched, and its worker's entire deliverable
was refuting the premise, the work having merged the previous day. Both had been validated
against their own text and their siblings, carefully and repeatedly, which is exactly what made
them feel checked.

**The rule already exists and is in the wrong loop.** *Check whether the work is still
outstanding — check the TREE, not the item* is stated where a brief is written: one item at a
time, at the moment of dispatch, which is both too late and too narrow. This is the loop that
reads every open item, so this is where the check belongs in BULK, before a dispatch is spent
on it. The sweep is one command — for each open id, search the trunk's merged history for that
id — and it costs a minute against a backlog of any size.

**But a matching commit is a POINTER, never a closure.** Some hits are partial work; some are
the very change the item was filed AGAINST, which is a fact the item's own title usually
carries in a word like *still*. Closing on a subject match is the same error one level up —
believing a record instead of reading the tree — so confirm in the source that the symbol is
gone, or the text now reads correctly, before closing anything. On the same board the sweep
returned nine matches, of which two were genuinely complete, one was the change its item
complained about, and the rest were siblings that proved nothing either way.

**One correction to the third item above while you are here**: *re-check what this session
closed* is the right instinct with the wrong window. A closure can revert in a session nobody
is auditing and sit reopened for days, so the reappearance to look for is not confined to
your own tick — which is another way this loop's bulk read finds what a short one cannot.

In-progress items are read here too, but their liveness question belongs to the stranded
sweep in the next loop. Read them for scope and for fences; leave *is this worker still
alive?* to the loop that owns it.

**Most passes find nothing, and a pass that finds nothing is complete.** What is not
complete is a pass that reports nothing without having read the raw list. Inferring the
backlog from the ready view is the exact failure this loop exists to go behind.

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

Look for items marked in-progress. If no live worker holds one, it may be stranded.

**But start from the worktrees, because that set is routinely empty while workers are running.**
Unless your dispatches explicitly claim their items, a worker holds items that still read *open*, so
this loop's nominal input is empty by construction rather than by health. Measured here: the
in-progress list returned nothing while a worker was provably alive — files written seconds earlier,
a gate process burning CPU — and the three items it held all read open. A *"nothing in progress,
sweep clean"* report is then not evidence of anything, and a streak of them is a streak of reads of
an empty set.

**The failure is worse than a missed strand**: a dead worker's items sit open, look dispatchable, and
the next dispatch tick sends a **second worker to the same branch**. So enumerate the worktrees
first, sweep each for recent file activity, and map trees to items. Where an item *is* marked
in-progress, read it too — a real signal, never the complete one.

**Discriminate before acting.** A long-running worker and a stranded one look identical from outside,
and both readings went wrong in a single day here, in opposite directions.

1. **Has the *tip revision* on that item's branch changed since the last tick?** Record the revision
   id, never a count of changes: **an *ahead* count survives a rebase unchanged**, and rebasing onto a
   moved trunk is what a briefed worker does constantly, so a count fails on the common case. A
   healthy worker here was called unchanged across two ticks, having rebased two minutes before the
   second read with all its work committed.

   **Read the tip from whichever ref the worker's commit updates DIRECTLY, and prefer a read that
   performs no refresh** — a refresh exposes the shared fetch-head trap under *After each merge*,
   which is on this path too and is silent here. Where workers share one repository metadata
   directory, the local ref moves the moment a worker commits while the *published* ref moves only
   when that worker pushes: the published one lags, in the direction that reads as death.

   **An unchanged tip says nothing on its own** — a worker inside a long gate commits nothing by
   design, so a still tip is the *expected* reading for the commonest healthy state. Corroborate with
   worktree activity, and note that **the corroboration is a WRITE clock, so a worker that is only
   READING touches nothing**: twice in one day a worker grepping its own gate log was called stranded
   on twenty-three minutes of no writes. **So look for a signal OUTSIDE the worktree too — where your
   tracker records that a worker claimed its item, only a running agent could have done that, and it
   lands in the tracker rather than the tree, where no file-activity clock can ever see it.** That is
   the one positive signal a purely reading worker still produces, and the opening minutes of every
   dispatch are exactly when you need it, because the first thing every brief tells a worker to do is
   read. Its absence proves nothing — not every dispatch claims — which is why the sweep starts from
   the worktrees in the first place.

   **And if your tooling maintains a live status for a running agent, read THAT before any of these.**
   Every signal in this step is indirect — it infers a worker from the traces it leaves — and they
   share a blind spot wide enough to matter: a worker in its **final minutes**, running a last gate and
   then tidying scratch files and shared-dependency links, works outside the worktree and outside
   version control and is too busy to answer. Tip, fetch clock, write clock and a direct message then
   read as dead *together*, for one cause, which is why corroborating one with another does not help
   here. **So frozen on every clock, across any number of readings, is AMBIGUOUS — it is exactly what
   healthy foreground work looks like, and exactly what a stopped worker looks like.** It cannot
   identify either, and no amount of re-reading the same four clocks converts it into evidence; a
   freeze that has run an hour is still the same non-signal it was in the first minute. Measured twice
   in one day: five workers were called dormant and **three of them completed alive, two were never
   explained either way**; a sixth, recorded later as another instance, also completed. **Do not count
   a live control among them** — one moving worker was the control for that measurement, and folding
   it into the numerator is how this count was first written down wrong. On the second occasion the
   supervisor's own one-line progress string named the phase outright while the four clocks said
   otherwise. Where no direct
   status exists, declare a window and re-read rather than acting: not because waiting resolves the
   ambiguity, but because the two errors are priced differently — waiting costs a tick, and acting
   costs a duplicate worker in a live worktree.

   **Say what the window is FOR, though, because a rule that only forbids leaves the interval empty
   and a coordinator fills an empty interval with more measurement.** The re-read catches exactly one
   thing — movement, which is proof of life and needs no corroboration — so read it twice, record the
   freeze, and go do something else; the tenth reading answers what the second did. And carry a sense
   of the scale, because that is what makes the non-signal bearable rather than ominous: **a freeze
   measured in hours is unremarkable on a heavyweight gate.** Measured across one session — six
   readings over ninety minutes, every one identical, while five workers whose transcripts had not
   gained a byte were all alive and all completed normally, at durations from an hour and forty
   minutes to nearly three hours. The coordinator who knows that stops after the second reading; the
   one who does not keeps going, because silence that long feels like it has to mean something.

   **That clock says "no activity" in four voices and you can only tell them apart with a control.**
   Besides the reading worker and the poller treated just below, a path that resolves to nothing
   answers identically — and so does a span shorter than the worktree's own age, where every file is
   recent and the count is the checkout rather than the worker. That last one is the counter-intuitive
   half, because it returns a large number that reads as proof of life: measured once at *the same
   count* over fifteen minutes and over six hours. **And the age itself is the worktree's CREATION
   time, not its last-modification time**, which the very writes you are trying to detect keep
   bumping: the wrong clock reads right on an idle tree, where the age does not matter, and seconds
   old on a live one, where the age is the whole question. Run the clock twice at different spans
   before believing either reading.

   **And "only reading" has a sibling that is not reading at all: a worker that POLLS.** It edits
   nothing and commits nothing, so both clocks above stay still for hours — but a fetch ordinarily
   writes as it reads, into exactly one place: the fetch-head file in that item's OWN metadata
   directory, which each linked worktree gets its own copy of the first time a fetch writes that
   clock there. Read that file twice, thirty to sixty seconds apart. **Movement is proof of life and
   needs no corroboration**, which makes this the one test in this section that answers without a
   control; stillness restores the ambiguity rather than resolving it, so it only ever answers in one
   direction. **Its PRESENCE says nothing about life** — a stopped worker leaves its copy behind,
   frozen at that worker's last fetch, which is why one read is worthless and two are decisive. **And
   its ABSENCE says less than it looks like saying**: only that no fetch has written that clock
   there, which is not the same as no fetch having run, because the write is suppressible by a flag
   on the fetch itself — a poller configured that way is invisible to this test altogether. So read
   absence as a reason to reach for another signal rather than as a history of the tree, though it
   stays a hint about one you did not create: measured here at four of seven, the three without being
   trees this method did not make. Measured on a poller that six consecutive sweeps had read as
   silent, on both of the other clocks, while it fetched every forty-two seconds. **And do not let
   the fetch-head trap under *After each merge* talk you out of it**: that rule is about the SHARED
   file being unusable as a merge TARGET because any concurrent process rewrites it. The per-item
   copy is unusable for that same reason and useful for precisely it — here you are reading the
   rewriting, not the contents.

   **The most convincing false signature is on none of the discredited lists: a change fully green at
   the band, a clean worktree, a tip commit some minutes old, and the change still a DRAFT.** It reads
   as a worker that finished and forgot to publish — a real state, and the one step 2 exists to catch
   — but a worker presenting exactly so was on attempt three of its local gate. **A green rollup is
   evidence about the PUSHED tree, not about the worker**, and a clean tree is what you see *between*
   edits. It belongs on the list because it reads strong and not one of its four parts observes the
   agent.

   **A change carries two clocks recording two different EVENTS, so name the event before you read a
   clock.** The *authored* time is the author timestamp recorded on the change; the *committed* time is
   the committer timestamp on the object as it currently stands, and it moves every time that object is
   rewritten. A rebase rewrites the second and replays the first unchanged, so where a project merges by
   rebase they differ on essentially every landed change — two honest readers called one change
   forty-three minutes old and seventy-five seconds old, and neither had misread.

   Liveness asks *did this worker do something recently*, which is the rewrite event, so it reads the
   committed time. A date in prose asks something else, and **which** something else is the whole
   question — a record that says only "dated" has not been specified. **Name the event in the sentence**
   — "authored on", "last rewritten on" — and where that event is one the change itself records, the
   clock follows from it. Do not write a rule that says prose takes one clock: that trades a defect for
   its mirror, and the error is invisible once made, because both are real timestamps on the same change
   and each is correct for its own event.

   **Where the event is not one the change records, no clock on the change answers.** Rebase is not the
   only rewrite: amending, or squashing a fixup, moves the committed time while carrying the original
   author timestamp forward, and an author date can be set explicitly to any value — so the authored
   time is no proof of when content was written, and a queued or deferred integration leaves the
   committed time recording a rewrite rather than an arrival. Ancestry is sound where the fields are
   not, and it settles ORDER only. **A date tying a change to an EXTERNAL event — a run, a measurement,
   an outage — therefore needs an anchor that event itself recorded, or a chronology you declare and
   stand behind.** One change here called its first commit a pre-run registration: authored nine seconds
   before the first sample, committed twenty minutes after the last, and neither number establishes the
   claim.

2. **Is there a live task to message?** Message first — resuming beats redispatching, because the
   worker's context is still there. **The commonest strand by far is a worker that detached a long gate
   and then ended its turn**, waiting for a completion event nothing sends. Seven such incidents in one
   day; every one recovered intact the moment somebody asked for a status.

   **Where the harness itself delivers an agent-stopped event, that event outranks every clock in this
   section for that agent.** An event defined to fire only when nothing of the agent's remains alive is
   not one more signal to weigh: arriving from a worker whose last message says it is WAITING for
   something, it is the diagnosis itself — the awaited wake no longer exists, whatever the worker
   believed it had armed. Resume without further discrimination; the clocks above are for workers whose
   harness reports nothing.

   **But whether that question has an answer depends on your messaging tool, and it may not.**
   Measured twice here, a send to an agent stopped mid-turn was ACCEPTED — success returned and
   delivery promised at the agent's *next tool round*, which for a stopped agent never comes.
   Acceptance is a queue confirmation, not a liveness report. **The reply is the read; the send is
   not**, and the asymmetry is total: a reply proves life, while silence proves only that nothing
   has been delivered yet.

   So the clause below is UNSATISFIABLE if you read *no live task* as something the tool reports
   — where it behaves this way it never will, and a precondition that cannot be met either blocks
   a legitimate redispatch forever or gets discharged by an invented proxy, which is the failure
   this section exists to prevent. Read it instead as a request left unanswered across a window you
   declare and stand behind, corroborated by the other discriminators. **The error is cheap in one
   direction only, and it favours waiting**: a late reply costs nothing, because a live worker
   resumes, while acting on a wrong *no task* destroys the run.

3. **Only with no live task AND no tip movement:** push any existing commits — pure durability — then
   set the item back to open with a note on what was found and salvaged, and redispatch.

   **Read WHY the worker stopped before acting on that last word.** Where the stop reason names an
   exhausted allowance with a stated reset, redispatch is not a remedy: *a remedy that draws on the
   resource whose exhaustion caused the failure is not a remedy*, so every attempt fails identically
   until the reset and every attempt spends. Salvage still applies in full — record the reason and the
   reset time, and stop dispatching rather than retrying. **Merging is unaffected**, and that is the
   half that still pays: integrating a finished change draws on none of that allowance, and a change
   whose author has already stopped can still be the one unblocking everything queued behind it. So
   the order is salvage, merge whatever is green, then stop. **The reason text, not the symptom,
   chooses the remedy** — a quota death, a crash, a timeout and step 2's detached-gate strand look
   identical from outside.

   **But the stated reset is a floor, not the only release.** An allowance can be restored by an
   operator act — re-authenticating, changing plan — well before the clock the harness quoted, and
   nothing tells the mayor it happened. So before holding the fleet for the whole interval, spend ONE
   resume message on the worker with the most context to lose: a refusal confirms the hold at no
   cost beyond what the hold was already costing, and an answer means the interval was over. Measured
   here: three workers died on a stated reset an hour away; the operator re-authenticated within
   minutes; one probe resumed all three. Hold on the clock only once the probe has refused.

   **But a probe's ANSWER is not the only reading of it, and its LIVENESS arrives far sooner.**
   The rule above is written around a reply, and a reply is what step 2 rightly calls the read —
   yet a resumed worker that is *working* has not replied and will not for many minutes, so a
   mayor waiting for words waits out most of the interval the probe existed to cut short. Ask the
   harness whether that agent is RUNNING instead. It costs one call, and it is decisive in one
   direction: an agent under an exhausted allowance dies within seconds of being resumed, so
   minutes of running is positive evidence the allowance came back. **Stillness is not the
   converse** — an empty listing restores the ambiguity rather than settling it — so read this
   exactly as the per-item fetch-head clock is read under *The stranded sweep*: movement proves
   life and needs no corroboration, absence only sends you to another signal. Measured here: six
   workers died together on a reset two and a half hours out, the operator re-authenticated, and
   the probe read *running* five minutes in; all six were resumed on that reading, none
   redispatched, and every one kept its context.

**A finished change is not a strand — but publishing it is still not yours to infer.** Everything
above prices redispatch, and prices it to favour waiting because acting on a wrong *no task* destroys
a live run. A second case sits beside it: a change already green at the band, its published head
matching its branch tip, needing only the publish flag its author sets last. The pull is to set that
flag yourself. **Do not reach for a new signal to justify it** — the flag is the interlock precisely
because a merge command refuses a draft, and an interlock you can talk yourself past is not one.

**Use the authorisation the [dispatch template](dispatch-prompt-template.md#publishing-the-change)
already names: the operator's decision to STOP that agent, which settles liveness by making it false.**
Stop it, then merge. That is an act with a definite outcome where every clock above is an inference
with none, and it needs no new discriminator — a stopped agent cannot be working, so the question the
four clocks could not answer stops being asked. Verify the change against §1 in full, exactly as for
any other change, and do not reopen the stopped worker's items or redispatch them.

**But it is not an escape hatch from the ambiguity above, and reading it as one is the natural
mistake, because it is the only ACT on offer in a section that otherwise says wait.** It authorises
publishing a change whose author you have positive reason to think is finished; it is not a way to
resolve a freeze the clocks cannot read. Putting that freeze to the operator as a decision spends
their attention on a question time usually answers by itself, and it arrives dressed as diligence,
which is why it is worth naming: measured once, five agents were escalated as possibly stopped, all
five were alive, and every one reported normally within the hour — the operator's answer, had it
come, would have been to destroy four live runs.

**The ORDER is the whole rule, and reversing it looks identical afterwards.** Merging first and
stopping second reaches the same end state by the forbidden route: the merge rests on an inference and
the act that would have settled it arrives too late to have authorised anything. Both orders leave a
merged change and a stopped agent, so the record cannot tell them apart later — which is why this says
*stop, then merge* rather than *make sure the agent ends up stopped*. Written the day it was got wrong
twice.

**The residual risk is real and belongs to stopping, not to merging: you may be killing work you
cannot see.** An author who closed its items and then discovered something further presents exactly
like one that is finished. What bounds it is that you merge the PUBLISHED head, which CI graded, so the
loss is an unpushed fix rather than a regression introduced, and the remedy is a follow-up change. That
is a cost to weigh before stopping — it is not a reason to merge without stopping, which trades a
bounded loss for an unbounded one.

**Never build a commit from someone else's uncommitted work.** Only that worker knows whether it forms
a coherent change.

---

## 5. Hygiene

**A measurement window makes this whole loop unsafe, and this is the only loop where that is not
obvious.** Removing a worktree changes the worktree list, which a window registering an
exclusivity condition captures at both of its brackets — so a reap during one is recorded as a
violation and can cost the window its runs. The dispatch loop states the condition; nothing carries
it here, and hygiene is the loop that performs the measured action. Hold the whole loop until the
window reports. Nothing in it is urgent.

### Reaping

**Only a worker's own completion report authorises reaping its worktree.** Not a merged
change. Not a clean tree. Not elapsed time. Not directory age.

Six proxies were adopted in a single session, each plausible when adopted, each wrong:

* **change state** — merged means the *work* landed, not that the worker stopped;
* **elapsed time**;
* **directory mtime** — read 250 minutes stale on a tree being committed to as it was read;
* **worker shape** — no commits, no open change;
* **cleanliness** — perversely, because a worker that has pushed everything exactly as briefed
  shows a clean tree throughout a twenty-minute gate, so *the better the discipline the likelier
  the kill*;
* **clean AND merged together**, which still took two full gate runs.

Reap a worktree while its agent is still running and you destroy that run — and the wreckage
does not announce itself as infrastructure. Gates die naming real, present files as missing,
which twice read as a genuine regression to the worker that received it. Once it landed on a
*negative control*, inverting a meta-test about exit codes.

The signal that has never lied is a read rather than an inference: the agent's own completion
report.

**And the report has to say the work is FINISHED.** An interim status, a progress note, a partial
hand-off and a change-opened announcement are all the agent *speaking* rather than the agent
*reporting done*. A worktree was reaped, repository metadata and all, while the last message its
worker had sent read *"Holding for the gate — not done yet, and I won't report a colour I don't
have."* The operational test is therefore: **can you quote the sentence where this agent says it
is done?** If not, the worktree stays.

**A gate the worker backgrounded is still that worker running.** Where the harness caps foreground
commands well below what a full gate needs, long gates are *always* backgrounded — so this is the
common case, not an edge one.

**One worker is not one worktree.** A worker may build a second for its gate run, so an unfamiliar
worktree belongs to someone until its agent reports.

A stale directory is the entire cost of waiting.

**Never pair a merge with a reap in the same breath, and never put the reap in the merge loop.**
The merge loop is the natural home for cleanup, because a merged change is the moment a tree
*looks* finished — and that pull is exactly the failure. A rule encoded in a loop condition is not
a rule being followed; it is a rule delegated to a predicate that cannot see what you know. Reaping
is a separate, deliberate step, run from a list of names for which you can point at the completion
report you received. *"I think it is done"* is the inference this rule exists to forbid, and calling
it "reported" does not change what it is.

**Clearing the mayor's context destroys its ability to QUOTE a report, so record which worktree
belongs to which agent at DISPATCH time** — one line per dispatch, in a mayor-local file the project
does not track. The reap test is unchanged; this only supplies a route to the sentence once the
context that held it is gone. After one clear, fourteen worktrees existed and exactly three had a
quotable report, all three dispatched after it. The rule failed safe; the cost is monotone
accumulation. **Dispatch time, not report time** — dispatch always precedes the report, so the line
is on disk before a clear can matter.

**Nothing validates that line, so specify its fields somewhere tracked.** The file is mayor-local
and version-ignored by design: no gate reads it, no review sees it, and its own header is the only
description of it that exists. That is precisely the condition under which a header and its rows
drift apart unobserved, and here they did — both carried five fields, so no count disagreed, but the
names sat one place to the left of the contents and a reader addressing a field BY NAME got its
neighbour's value. Reading the last field as the header named it returned a well-formed answer for
seventeen of thirty worktrees, and every one of those answers was an identifier rather than the
report it was taken for. **Name the fields, in the order a dispatch writes them, in the project's
own agent-instructions file**, and have the local header cite that record instead of standing as its
own authority.

**Two of those fields belong to this rule and the rest belong to the project**: the worktree,
because it is the thing you are deciding about, and the agent id, because it is the entire route to
the report. **The reap test reads no field of that line at all** — it reads the report, which the id
leads you to. So a field holding the report TEXT is the operator's call recorded at the end of this
section, not a column this rule turns on, and a header that names one before that call is made is
describing a field no dispatch writes.

**What the id buys is the TRANSCRIPT, not a live conversation.** Messaging does not reach across a
clear: eight agents whose ids had been recorded exactly as prescribed all answered *"no transcript
found"*, while an agent dispatched by the current session resumed on the identical call. Their
transcripts were intact on disk nonetheless, and seven of the eight opened their last message with
precisely the sentence the reap test demands. **Reading it is a READ, not an inference**, so it
satisfies the test as written and adds no further proxy.

**An id is a way to FIND the report, never an answer in itself**: within the dispatching session,
message first, because that distinguishes *alive* from *finished* and a transcript cannot. And
**beware a per-agent scratch sink that merely shares the id** — one was empty for seven of those
eight while their real transcripts sat complete elsewhere, *also* empty for a current-session agent
that finished normally, and for the eighth held a hardlink to the real transcript, so the wrong
file returned one plausible non-empty result and made the wrong conclusion self-consistent.

**An empty file keyed by the id says nothing whatever about whether a report exists** — it is a
fact about that one location, never about the id. Measured again later, at larger scale: the sink
read empty for fifteen of seventeen worktrees, every one of those fifteen transcripts sat complete
elsewhere, and fourteen of them opened with the completion sentence the test demands. The two
non-empty ones are what made it read as a survey rather than an artefact — a wholly empty result
would have looked broken and sent the reader looking. So the id names a FILE only once you know
which file is the agent's OWN; until then it names a directory to search. **And an id may key
several locations rather than one** — the scratch sink is one of them and the store holding the
session's own transcript is another, under a different root and a different naming convention — so
the step between an empty sink and the path search below is to enumerate the id's remaining
locations and read those, which is cheap and exact where the search is neither. Measured later
still, across forty candidate worktrees in a single pass: the sink was empty for thirty-one of
them, and a second id-keyed location held a complete transcript for all thirty-one, no misses.
Twenty-nine of those opened with the completion sentence; the other two ended on interim status and
were correctly refused the reap, so enumerating the locations discriminates in both directions
rather than merely finding reports. The search below was needed for exactly two of the forty, and
both were trees with no recorded id at all — which is what that search is for. Read emptiness as
*not found here*, never as *not written* — the cost of the wrong reading is the whole point of this
section, since it converts "no report exists" into a standing residue that nothing will ever clear.

**When no id was recorded at all, the report is not missing — only unindexed.** Search the
transcripts for the worktree's own path, which a dispatch names and so does the report that ends
the work, then read a bounded TAIL of a file that matches rather than opening it, because these are
append-only logs of whole sessions and routinely run to megabytes. **But the match is many-to-one,
and most of its hits are not the worker's own** — the dispatching session's own transcript names
that path, and so does every peer brief that lists the in-flight write surfaces, because this
method requires naming them — so identify a file as the WORKER'S OWN before anything in it counts
as that worker's report; reaping on a peer's mention of a worktree is reaping on someone else's,
which is strictly worse than leaving the tree standing.

**Three tests separate them, and the obvious one is not sufficient by itself.** COUNT the hits per
file: a worker's own log names its worktree tens to hundreds of times where a peer names it once or
twice, so the gap is two orders of magnitude rather than a judgement call. But **ranking by that
count can still hand you the wrong file** — measured across eleven trees, the top scorer for one of
them was a peer that had simply worked longer beside it. The decisive test is the report's own
claim about where it ran: a worker states its worktree root, so a file whose report names a
DIFFERENT root is not that worker's however often your path appears in it. And where a directory
name has been reused, both incarnations match — separate those by the branch the tree carries NOW,
because the earlier one names a branch that is no longer there and tends to end mid-flight rather
than in a report.

**A worktree the MAYOR occupied has no agent, so the search comes back empty for that reason
alone.** Work an operator declines to delegate still needs a tree, and such a tree never had a
worker to write a transcript. The empty search then reads as *no report exists*, which is the reap
test's blocking condition, and the tree becomes permanently unreapable — the empty-sink error one
level up: an absent transcript is a fact about the SEARCH, and one of the things it can mean is
that nobody was ever there. **The rule does not bend, because for that tree the mayor IS the
agent** and its authorising report is the mayor's own knowledge that the work is finished. What has
to come first is identification, and the tip revision alone will not do it: it names the WORK, not
the occupant, because an auditor, a reviewer, a bisect or a monitor all sit at a commit they did
not write. Ask instead whether the tree is on the branch the work was PUBLISHED from — an author's
is, and a process inspecting that work carries a branch of its own, named for the thing it
inspects. **And do not promote occupancy into a proxy.** A tree sitting at the trunk with nothing
of its own is the ordinary state of a worker just created and not yet committed — the most
dangerous tree to remove, not the safest. It corroborates an identification; it never substitutes
for one. **Expect automated processes to hold worktrees too**, at commits they did not author and
under directory names they recycle: one observed here reappeared at a path two minutes after that
path was cleared, and its predecessor had been mistaken for the author's own tree on exactly the
tip-revision reasoning this paragraph now refuses.

A transcript path the platform documents as an implementation detail is not a contract, and
local-history retention sweeps typically DELETE rather than truncate — so whether to hold the report
text somewhere of your own is the operator's call.

**Reaping on the report is correct and still costs something**: a worker can need its tree *after*
it reports, because its change hits a conflict. Pushed commits make that survivable — it recreates
the tree on the existing branch and continues. **Do not add a second condition to a rule whose
strength is that it has one.** Nothing, though, requires reaping the moment the report arrives:
a reported tree whose change is still OPEN is exactly the tree its author resumes into when that
change hits a conflict, so deferring its reap until the merge lands is free insurance — measured
here, two of three deferred trees were needed again within the hour, and both recoveries were
in-place resumes with full context rather than redispatches into recreated trees. The rule stays
one-condition; the deferral is scheduling, not authorisation.

### Removing

**If your project links or junctions shared dependencies into worker worktrees, never remove a
worktree with the plain command.** It follows the link and deletes *through* it, emptying the shared
tree it points at — silently, exiting successfully, breaking every local build until the
dependencies are reinstalled. It has happened more than once in this project. **And removal is not
the only write that follows a link**: an installer a gate runs inside the worktree rewrites the shared
target the same way, so a tree can be emptied while its worker is still alive and no cleanup has run.
The brief-side rule is under *Quality gates — which gate* in `dispatch-prompt-template.md`; here, count
every shared tree a worker's gates could have reinstalled, not only the ones a removal touches.

Put the safe sequence in a **script**, not in prose. Prose was tried and the class recurred anyway.
The script should: snapshot every real shared-dependency tree; unlink each *link* under the worktree
— the link only, never a recursive delete; verify each is gone; then remove the worktree; then
re-check the snapshot and **fail loudly if the signature moved**, naming the recovery command.

**Verify the shared tree's file count either side of a batch**, captured at runtime. A remembered
constant cannot detect a delta, and a top-level count alone cannot see files vanishing from under
packages whose directories survive — so count immediate entries *and* recursive files.

**And count both sides the SAME WAY.** A listing that hides entries does not merely undercount — it
turns the comparison into an offset, and in one direction that offset CANCELS a real loss of its own
size. Measured here on a tree of 103 immediate entries, two of them hidden: the hiding form reads 101
before, and the showing form reads 101 again after two visible entries have been destroyed. No delta,
two entries gone. The other direction is only a false alarm, which is the cheaper half and the one you
will meet first — it cost a real detour here before the flags were compared.

**So make the decisive control a clock rather than a count**: the newest modification time inside the
tree, which a removal moves and a change of listing options cannot. Note which entry carries it before
you start. On the tree measured here that entry was itself one of the two hidden ones, so the listing
form that suppressed the count also suppressed the control — worth checking rather than assuming, in
either direction.

**Sweep with a narrow force flag, never a blanket one.** A disposable-only force removes a tree
only when every untracked path is build or gate output, and refuses any tree holding a modified
tracked file, a note or a draft. That guard is not hypothetical: when it was written, one worktree
held four analysis files for an **open** item and three others carried uncommitted source and doc
edits. A blanket force would have destroyed all of it silently — far worse than a worktree that will
not reap.

**A refusal to remove has modes that are not interchangeable.** Nine worktrees were once read as
locked, waited out for two days, and were all simply dirty:

* **Dirty** — the tree holds modified or untracked files. **Waiting never clears this.**
* **Held** — the disposable sweep stopped because the tree holds something that is not build output.
  Nothing was deleted.
* **Locked** — the tree is clean and intact and a live process still holds a handle. This one is
  genuinely transient: note it and retry later.
* **Partial** — a *husk*. The tool deregistered the worktree and deleted its metadata, then hit the
  lock and stopped. It no longer appears in the worktree listing, pruning has nothing to clear, and a
  status check inside it *fails and prints nothing* — which is exactly why a clean/dirty test reads a
  husk as clean. Retrying the ordinary path is futile — the registered-worktree check refuses a
  husk forever. **But do not reach for a raw recursive delete, which is the operation the top of
  this section forbids**: a husk still holds every file the worktree had, any linked dependency
  directory among them, so a plain delete follows that link and empties what it points at. Reach
  for the tool's acknowledged-husk path, which disarms the link before deleting anything; the
  paragraph below states the two conditions it is gated on, and a tool that offers no such path
  is one to fix rather than to work around. **A partial removal kills a running gate exactly as a
  complete one does** — never read it as "nothing happened".

**A husk is identified, never inferred.** "The tool does not list it, and it has no metadata" is true
of a husk and equally true of an ordinary directory, a mistyped argument and an unrelated project.
One tool handed a temp directory created seconds earlier unlinked the dependencies inside it, called
it a destroyed tree and recommended deleting it. So refuse an unregistered path outright unless the
caller acknowledges it **and** its contents are recognisable as this repository's, established from
version control's own object database. Never spray that acknowledgement across a sweep list.

### Branches

Delete merged local and remote worker branches — **never one whose change is not verifiably merged**.

**Enumerate the remote set separately, because it is not a subset of the local one.** Everything else in
this loop starts from the worktrees, which is right for reaping and blind here: a branch whose worktree
was removed and whose local ref was deleted still exists on the remote, and nothing you are looking at
mentions it. One sat merged and undeleted through two hygiene passes, surfacing only when the remote list
happened to be printed.

**"Verifiably merged" needs a test, and where the project merges by REBASE the two obvious ones are
both wrong — in opposite directions.** A rebase replays every commit under a new identity, so a
merged branch never becomes an ancestor of the trunk and never stops reading as *ahead* of it:
ancestry reports fully merged work as unmerged, and the ahead count says the same thing for the same
reason. That is the rebase invariance *The stranded sweep* explains above, asked on containment
rather than on liveness, which is why that paragraph is the cross-reference here rather than
something to restate.

**The test is patch-equivalence**, which asks what each commit *does* rather than which object it
is: an equivalent patch already upstream means the work is contained. In the dominant toolchain that
is `cherry`, which marks the two answers `-` and `+`. It earns its place over either half of a rule
that only ever fails safe one way. Measured here across 63 worktrees: 60 branches read one to three
commits ahead of the trunk, 58 of those fully merged and one carrying three genuinely new commits.
Deleting on ancestry would have kept all 58 forever; deleting on the ahead count alone would have
destroyed those three.

**But its two answers are not equally strong, and reading them as symmetric is the error this
paragraph exists to stop.** `-` is proof of containment. `+` is only the ABSENCE of proof — not
evidence that the commit carries unmerged work. A patch identity is computed over the diff
*including its context lines*, so a sibling change landing in neighbouring lines of the same file
changes your commit's context, changes its identity, and leaves work that is fully present upstream
reading `+`. Measured here: a branch whose change had already MERGED still showed one `+` commit; every
line that commit added was present at the trunk's tip, character for character. The sibling that moved
the context was another change to the same file.

**So the rule is safe and its reading is not.** Deleting only on `-` never destroys anything, which
is why the criterion stands unchanged — but a mayor who reads `+` as *this branch has unmerged work*
will hunt for work that landed weeks ago, and a branch list read as a backlog is a list of phantoms.
The cheap triage is to take the lines that commit ADDED and look for them at the tip: where they are
all there, the likeliest reading is that the content landed and only the context moved. Run it first
because it costs seconds and tells you where to point the real check — never because it finishes one.

**But it is triage, not a verdict, and it is wrong in BOTH directions — which is the correction this
paragraph carries.** A line search has no notion of path, location, multiplicity or order, and it
cannot see a deletion at all. So *all present* is satisfied by lines that exist somewhere at the tip
while this branch's actual change is wholly absent — the more generic the added lines, the more
certain that is, and a commit whose substance is a deletion or a replacement scans clean having been
inspected for nothing. And *any absent* is equally weak in reverse: upstream can carry the same work
through a rename, a reformat or a follow-on rewrite, so the change is fully present and the exact
line is not. **To CONCLUDE either way, compare the commit's whole patch against the tip at the paths
it touches, deletions included** — a three-way comparison from the branch's parent is enough, and it
is the only thing here that answers the question actually asked.

**This matters even though the rule already forbids deleting on it.** The damage from a wrong
*landed* reading is not a lost branch — it is a mayor who stops looking, files nothing, and leaves
real unmerged work sitting under a name that now reads as residue. The cost of the two errors stays
asymmetric, which is why the conservative half survives untouched: believing `+` costs you a search,
while believing a wrongly-derived `-` costs you the work.

**And note the shape of the mistake, because it recurs wherever an instrument is cheap.** This
document already warns that [a search returning ZERO is not a check that
passed](dispatch-prompt-template.md#quality-gates--how-a-gate-is-run); the inversion is the same
defect and reads even better, because a scan returning EVERYTHING looks like corroboration rather
than like silence. Ask what the instrument compared, not what it answered.

**Key destructive operations on identity, never on a name.** Branch names repeat across sessions and
prefix-match each other. A search for `head:feature-x` also returns the change for `feature-x2`, and
may rank the sibling *first*, so a loop reading the top row reads one branch's state as another's.
Here it read "open" for a branch whose own change had merged, and merely skipped it — but the same
shape reversed deletes a live worker's branch on its sibling's verdict. **Ask for the exact head-ref
field and compare it for equality yourself.** A search term substring-matches; only equality
identifies.

**But comparing it yourself still leaves you filtering a WINDOW, and that failure is the silent one.**
A listing returns the most recent N changes; your equality test over it is correct and its answer is
only as wide as the listing. Measured here: a filter over the four hundred most recent changes
reported that nothing proposed two branches whose commits predate that window entirely — and a zero
over the wrong range reads exactly like a zero over the right one. The sibling trap at least leaves a
wrong row on screen; this leaves nothing at all. **So prefer an instrument that takes the identity as
INPUT** — most forges accept the head reference as a query parameter, which asks the whole set at once
and cannot be windowed. **Exercise it in both directions before trusting it**, because a query that
silently matches nothing returns the reassuring answer to every question. Measured here: the equality
query returned the one merged change for a branch that had one, and returned NOTHING for a prefix of
that same branch name — where the search form returned SIX changes for that prefix and ranked the
full branch's own first, which is the paragraph above reproduced at this tip.

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
nothing.

Do two things instead:

1. **Check what changed** since the last pass — in these documents, and in your loop command files.
   When a method rule changes, re-read the matching command file. A link checker catches renamed
   files, not semantic drift.
2. **Check the rules you have been *enforcing* against what the tree actually does.** That is where
   the drift lives. Twice this found a rule the documents asserted universally that the tree did not
   support — and one of those had been introduced by a merge the mayor approved.

Watch specifically for:

* **A measured constant that has gone stale.** The check-count band is the standing example: it is
  written down, it moves, and it goes stale exactly where it has to be right.
* **Unsatisfiable quantifiers.** *"Every gate prints X"* when a third of them do not. Workers facing
  an unsatisfiable rule do not stop — they improvise, and the improvisation varies. **When a rule says
  *every*, check the quantifier.**
* **Blocks that have diverged from the command files that paste them.** Two copies of one rule
  disagree within days.
* **A hazard recorded without the test that discharges it.** *"Beware X"* with nothing saying how
  to tell X from the legitimate case that looks identical. Whoever wrote it had just performed that
  test, so it read as part of the observation rather than as a separate thing needing words; the
  reader arrives having performed none of it and must reconstruct the test while looking at the very
  evidence the hazard produces. Three in one session, all in one section, and the one I had read
  within the hour was the one I walked into. This document already says a remedy written without its
  discriminator **is worse than no remedy at all** — that judgement applies to its own prose, and
  this loop is the only place that checks whether it does.

Report one or two lines unless you find real drift. If you find drift, fix the document or the command
file — do not just note it.

**But the repair is the step most likely to need repairing, and it fails in one particular way: a
correction tends to overshoot into the INVERSE of the claim it replaces.** A wrong sentence is
usually wrong by asserting too much, and the evidence that exposes it feels like evidence for the
opposite — so the fix arrives with the confidence the original had, pointing the other way, and
reads as *more* reliable because it was written in response to being caught. Two in one session,
both in this loop's own output: *"those workers are dormant"*, refuted, became *"frozen clocks are
the signature of a run about to report"* when the supported claim was that the reading is ambiguous;
and a containment scan demoted from a verdict to triage kept a clause saying it *"settles the common
case"*, two sentences from the words *"triage, not a verdict"*. Both were caught by an outside
reader, not by the loop that wrote them.

**The discharging test is one question, and it costs nothing: what would you expect to SEE that is
different if the new claim were false?** If the only answer is *the evidence that refuted the old
one*, nothing has been established — that evidence rules the old claim out and leaves the replacement
standing among several survivors, which is the old error re-pointed. Workers completing alive refutes
*dormant*, and a stopped run shows the same frozen clock as one about to report, so nothing observed
tells those two apart. The containment scan's misses refute *this settles it*, and a scan that is
right usually and one that is right rarely produce identical output, so *settles the common case*
rests on a frequency nobody measured. Where the honest answer is that the observation supports
neither side, **say so plainly and let the guidance rest on the asymmetric cost of the two errors
instead** — that is a real reason to wait, and it survives the next counterexample,
which a re-pointed claim does not. Being wrong twice about the same sentence is the ordinary case
here, and this loop is where the second round is supposed to be caught.

**Where the project arms a mayor-side commit guard, the mayor's own repair goes through a
mayor-occupied worktree** — the guard refuses the mayor checkout for every non-tracker surface,
these documents included, and that refusal is the guard working, not an exemption to negotiate.
Author the fix in a worktree of your own, publish it as an ordinary change, and merge it on the
ordinary criterion; the dispatch ledger's mayor-row convention already names such a tree. Two
mechanical gotchas, measured: the refused commit exits non-zero with the files still STAGED, so
read the commit's own exit rather than the output tail — the refusal scrolls away under later
lines; and those staged files then abort the next rebase-pull with a message that never names
the refusal that caused it, so restore them before pulling rather than diagnosing the pull. **And the fix is bounded in change, not in search.** What this loop finds is
mechanical, so a defect in one place usually has siblings: [*"Bounded repair" bounds the change, not the
search*](dispatch-prompt-template.md#name-the-discriminator) is set out for the worker and binds the
mayor's own repairs exactly as it binds a worker's. Measured here: a pointer repaired in this loop left
three identical siblings standing, and a later pass found them rather than the repair did.

**But weigh WHICH artefact to change.** Where a reader-side rule has failed repeatedly, more text is
usually not the repair. Two remedies landed the other way in one session: an item whose OLDEST field
was rewritten to open with its disposition, after three workers had been dispatched at work that
should not exist and each had correctly refused; and a commit guard that refused an edit the rule had
already forbidden in words. Both changed the artefact a reader meets rather than the instruction they
had read and recited. **And a guard is not a rule that cannot fail**: one added on an evening was RUN
the next hour and misread, because a check executed carelessly returns the same reassuring answer as
no check at all.

---

## Tracker mechanics

These belong to no single loop and bite in all of them.


**An instrument can be RIGHT and still answer a different question from the one you asked.** The
rule about an instrument that says *"nothing here"* covers the case where its answer is wrong.
This is the other case, and the remedy for the first is inert against it: exercise the instrument
against an input it should flag and it comes back GREEN, because nothing is malfunctioning. Four
in one session, one from each of four loops — a count of running workers read as *the machine is
quiet*; a count of resident processes read as *the machine is busy*; a marker scan finding a
banner read as *this item is held*; a note true when written read as *true now*. Every answer was
correct and every reading was wrong. **Write the question the instrument answers, in its own
terms, beside the question you are asking.** The gap is invisible in the answer and obvious the
moment both sentences are on the page. This is the harder half to catch, because a wrong answer
gives you something to be suspicious of and a right one does not.

**Take the marker scan in that list further, because it fails in BOTH directions and the remedy follows
from that rather than from either half.** It over-reports: an item that QUOTES a marker — recounting
another item's hold, or listing the very strings the scan searches for — matches, and so does one whose
marker has been struck and whose text says so. It also under-reports: a hold written in ordinary prose
carries no marker at all, and one item reached the ready list reading free on a probe of **six** distinct
marker phrases while its own text said *"what remains is one decision"*. **And a second scan built to
discriminate the two fails the same way**, because the standing marker text itself instructs whoever
rules to strike it, so the word *struck* appears inside every live marker.

**So a marker scan is a CANDIDATE FILTER and never a verdict.** It narrows a board to a handful; the
hold is then established by READING each candidate, which is affordable at that size and is the only
thing that separates a live marker from one quoted, discussed, or already struck. Report the number you
read, never the number you scanned. **Where a project wants this machine-checkable, the durable answer
is a structured field on the item rather than a marker in prose** — that is a tracker change and an
operator's decision, but every failure above is a consequence of encoding state in text that also
discusses state.

**A dependency can outlive what it was enforcing.** An item blocked "until X lands" stays blocked if X
is later reopened by an audit for an unrelated residual — even though the thing it was waiting for
shipped. Force the close and **write the reasoning on the item**: what the dependency was enforcing,
that it was satisfied, and why the reopen does not re-block work that already merged. This happened five
times in one session; each was legitimate.

**Items usually close on change-*open*, not change-*merge*.** So "closed" does not mean "on the trunk".
Check the merge before treating a dependency as discharged.

**Verify closures by reading the status field, keyed on the item id.** Two ways that audit lies: keyed on
an internal row identifier, a re-import regenerates them and the diff reports phantom losses; and
searching the whole record matches the word "open" inside a title.

**A command's echoed output is not confirmation that it did anything.** The rule against reading a gate's
verdict off a filter is the same failure in a different suit, and it reaches every command the mayor runs, not
just gates. Two instances in one session, both from trimming output to keep a log short. Four closes were sent
with long reasons and their output trimmed to the last line — which, when the reason is long, *is* the reason
text, so a refused close and an accepted one looked identical; all four evaporated and were found cycles later
reading open with no reason recorded. Nine worktree removals were then counted for a string the script prints on
the way out whether it acts or refuses; every one had exited non-zero saying "nothing was touched", and all nine
were still there. A maintenance script that refuses safely is doing its job; the failure is the caller who does
not look.

**But the exit status and the mutation are two different questions, and only the first is cheap.** A non-zero
status is proof the command refused, so **always read it** — that alone would have caught both incidents above.
A *zero* status proves only that the command reported success, not that state changed: an idempotent call
legitimately succeeds having done nothing, and a durable operation can acknowledge locally before the remote or
the postcondition you actually care about is true. **So when the outcome you need is a state change or a durable
one — or when a successful no-op is possible — re-read the exact target as well.** Never take the printed output
for either answer.

**A tracker write can silently revert.** Items verified closed can read open again cycles later — a
rollback to an earlier snapshot, a re-import over the top — and nothing in the loop surfaces it, so the
session's "closed" count quietly overstates. Verifying at the moment you close is not enough. Each cycle,
re-check everything closed this session and re-close what genuinely reverted, with evidence.

**That rule records the hazard but leaves its discriminating test open — WHEN in the cycle — and the
answer is: across the sequence that exports the tracker, restores it from version control and publishes
it.** *Each cycle* puts the re-read at the next loop, so a revert stays live for as long as a loop is
long; one caught that way had been standing over an hour and surfaced by accident during an unrelated
read rather than by the rule. That publish sequence is the one disturbance every cycle reliably
contains, and it sits between the close and the next re-read, so it is where to look rather than
somewhere to look eventually.

**Read BOTH sides of it, not just after.** Measured in one session: two closes verified only at close
time were both gone by the next read, while a third — re-read immediately before *and* after that
sequence, then confirmed by parsing the published export for that item's own status field — held. A
single check afterwards tells you a close is missing but not which side of the sequence lost it, and
the two have different remedies. **Do not infer the mechanism from the coincidence**: a sequence
containing several steps is not evidence about which step moved the state, and naming a culprit you
have not isolated converts a reliable check into a wrong explanation that will be repeated.

**But a status change made by a LIVE worker is that worker speaking, not corruption.** The rule
above trains you to read an unexpected status as a bad write, and it supplies no competing
reading, so the wrong one arrives with nothing to check it. A worker that un-claims an item is
often saying something precise — commonly that only part of it was shippable and the rest is the
operator's, which holding the item would bury. Its report is the authority on what it meant, and
the report is usually minutes behind the status. **Where a worker is alive on the item, read the
report before you act on the clock**: tracker history timestamps cluster tightly enough that a
one-second gap is no evidence of a machine write, and reversing a worker's deliberate signal
destroys the message.

**But read the item's notes before re-closing anything.** If your process audits merged changes, expect
closed items to reappear: twelve did in one session, and every one was a legitimate audit reopening the
item that owned a residual. **The reflex to re-close a "reverted" item destroys a real finding and looks
like tidiness.** Expect chains — a fix lands, its audit reopens for a second carrier, that fix's audit
reopens for a third. Two or three rounds converge.

**A reopen that records no finding is not yet a finding.** The rule above assumes the reopen carries its
residual; sometimes one arrives as a bare status flip — close reason wiped, nothing appended, no new item
filed — and then there is nothing to read and nothing to dispatch. Both reflexes are wrong: re-closing on
the bare flip destroys a finding the reopening process may still be mid-writing, and treating the item as
ready dispatches a worker at work nobody has named. Mark it as awaiting its finding, in the same scan-findable
words each time, and give the writer one full pass of this loop. If nothing has landed by then, re-close,
restoring the original close reason — the flip cleared it — and recording the reopen's emptiness alongside.
Measured against the twelve above: two such flips in one session, distinguishable from the legitimate
reopens around them only by the absence of any recorded residual.

**Checkpoint tracker state on the heartbeat.** Many trackers auto-stage but never commit, so a long
session's state strands locally. Commit and push it each cycle.

**A checkpoint is two operations, and the harness can kill it between them.** The export contends
with every worker's tracker writes, so under a full fleet a checkpoint that took seconds on an empty
board can outrun a command cap. Measured twice in one session: one kill left the commit made and the
push not, and the re-run reported *nothing to checkpoint* — true, and the remote was still behind.
Verify the remote against the local head after every checkpoint; the script's message answers only
the commit.

**Long tracker text does not travel as a shell argument.** A batch of notes handed to the tracker
CLI as quoted strings can fail to parse at the shell before a single note is written, and the
failure names a line, not the character — and a quoted heredoc is not a cure, as one project's own
memory had claimed. Write the text to a file with a tool that is not a shell, and drive the tracker
from that file with a script: the transport then has no quoting to get wrong. Two batches of nine
notes each died this way in one session; the third, through a file, wrote all nine.

**If your tracker exports a whole-database file, treat it as generated.** Never resolve a conflict in it
by taking one side wholesale — both sides are full exports, so picking one discards every item the other
recorded. Resolve, then **regenerate**. And never let a worker commit it: a worker branch carries a
snapshot from when its worktree was created, so committing it time-travels the tracker — items closed
since reopen, items filed since vanish.

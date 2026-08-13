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
| Merge + dispatch | short (~10–15 min) | Merge everything green; refill the fleet |
| Backlog reread | medium (~60 min) | Re-read *all* open items from the raw list |
| Posture + stranded sweep | medium (~60 min) | Restate the stance; find genuinely stranded work |
| Hygiene | long (~2 h) | Prune worktrees and branches |
| Method reread | long (~60 min) | Check these documents against what the tree does |

Some people prefer merge and dispatch as separate loops. One loop is simpler and
has a real advantage: a merge frees a worker slot, and the same tick refills it.

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

Resolve the head first — the branch name **and** the head revision — because
clauses 4 and 5 are both keyed to it.

1. **A non-empty rollup, in the band this repository actually produces.**
2. **Every check in a passing or skipped state.**
3. **Nothing non-terminal in the rollup.**
4. **Every workflow run at the change's current head revision COMPLETED.**
5. **The check set was computed against the workflow matrix on the trunk now.**

Each of those is one line and each hides a way to be wrong. They are unpacked
below.

### Clause 1 — zero failures is not green

Zero-failures-zero-pending is also exactly what an **empty** rollup reports. That
is what a change shows in the seconds before its workflow runs are created, and
what everything shows while the CI provider is down. One change merged here on a
rollup read as 0/0/0 eleven seconds before its runs existed; during a provider
outage the same day, six changes at once reported mergeable with zero checks, and
a criterion without this clause would have taken all six.

So require a **count**, and require it to be in the band this repository actually
produces. A rollup carrying a fraction of the checks a full one carries is no
greener than an empty one.

**Measure the band; never trust a number written down.** It moves as the matrix
grows. In one programme the figure here moved four times in three days. Measure it
on changes merged *after* the last matrix change, because a change branched before
a new job landed is computed against the superseded matrix and legitimately reads
band-minus-one — which the count alone cannot distinguish from a change genuinely
one job short. Update the branch and re-check before judging this clause.

### Clause 2 — cancelled is not passed

A cancelled check is *completed*. A tally keyed on completion therefore counts it
as green. One change here was reported as "85 of 86 concluded, one check from
complete" when the truth was 79 passed, 6 cancelled and 1 pending — six re-runs
across four workflows away from mergeable, not one.

Count only the conclusions that mean the work ran and was fine. Read every other
value, cancellation included, as a check that has not passed.

### Clause 3 — require the terminal state, do not enumerate the bad ones

Do not test this by listing the states that block. The vocabulary is longer than it
looks — queued and in-progress are not the whole of it; hosts also return requested,
waiting and pending — and a rule that enumerates the bad states fails open on the
first state nobody wrote down. That is the same fail-open shape this whole section
exists to close.

Invert it: require the terminal state. Anything else blocks and is re-checked next
tick.

### Clause 4 — a settled rollup is not a quiet branch

A queued run's checks are not attached to the head commit yet, so the rollup cannot
report what it does not yet know is coming. One change here read `ok=78 canc=0
fail=0 pend=0 total=78` — settled by every field it exposed — while the branch's own
run listing showed two runs queued. Merging there ships it two workflows short.

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

**Two mechanical traps live in this clause, and they fail in opposite directions.**
Both are worth stating because both are one character from correct:

* **Case.** Your CLI may report check states in one case and run statuses in
  another. A comparison written against the wrong one counts every *finished* run as
  non-terminal, and every change reads blocked. This one fails **closed** — it never
  ships a bad merge, and it never announces itself either. It presents as a CI queue
  that will not drain, which is entirely believable. The tell is that every change
  reads identically blocked; real CI does not stall a whole queue in lockstep.
  Case-fold the comparison.
* **Abbreviated revisions.** A run query filtered by an abbreviated revision may match
  nothing and return an empty list — which reads as "no live runs" and passes this
  clause **vacuously**. Pass the full revision.

### Clause 5 — reading, not counting

This is the newest clause and the least visible, because every field you can read
says green.

Two changes merged four minutes apart here. The first added a required browser job
and a new three-engine arm; the second was still checked against the base from
before it, so the browser check it should have run came back *skipped* and the newly
required one never appeared in its rollup at all. *"All required checks passed"* was
a true statement about the old matrix and said nothing about the matrix already on
the trunk. That tree happened to be green — an audit ran the suites by hand
afterwards to establish it — but the guard had not known, which is the entire
problem.

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
harmless. It usually is, and "usually" is what the other four clauses exist to
refuse.

### Merging

Check also that the diff matches its tracker item, that scope did not sprawl, that
failure output stays actionable, and that the quality-gates section is present.

Merge every green change **regardless of author**, including the operator's own.

**Prefer server-side head-branch deletion over a client-side delete flag.** A branch
still checked out in a worktree cannot be deleted locally, and clients typically
abandon the *remote* deletion along with the local one — so the flag orphans a remote
branch on every merge whose worker has not yet reported. Three for three in the
session that measured it, against roughly twenty manual cleanups that were skipped
more often than they were run. Turn on the repository setting instead: the server
deletes the head branch as the merge lands and does not care what a worktree is
holding. Zero orphans by construction, and nothing to remember afterwards.

A manual remote delete then survives only as the rare fallback for a ref that
outlives a verified merge — head-ref protection, or a cross-repo change whose head
lives in a fork the server will not touch. **A surviving remote ref is never grounds
to reap a worktree early.**

**"Base branch was modified" usually means stale — until something is moving the
base.** The rejection reads like a lost race, so the reflex is to retry. Seven
retries here proved otherwise: the branch was simply behind, and the remedy is to
update it and re-check.

But any automation that writes to the trunk after a merge turns that reflex into a
trap, because then the race is real. A post-merge hook that commits and pushes
asynchronously means the base never holds still during a burst, and the next merge
is refused for a reason no amount of updating fixes. **One change here was refused
thirty times.** Both transports agreed, so it was not a client quirk, and updating
the branch made it worse before better — CI restarts, and another hook commit lands
inside that window.

The remedy is counterintuitive: **merge the whole queue FIRST, then give the
straggler a genuinely empty window.** Raising its nominal priority does the opposite
of what it looks like, because priority without an empty window is just more attempts
against a moving base. And if a change refuses more than about three times while
blocking nothing, **shelve it** and take it opportunistically. Persistence on an item
that is blocking nothing is its own gold-plating.

### After each merge

Pull the trunk **naming remote and branch** — the bare form silently no-ops when an
automated push races it — then **verify the tree, not the message**: compare the local
head against the remote head. A pull printed `Updating <old>..<new>` twice in one
session here while the head had not moved at all.

If they disagree, **read both again before believing it.** Any automation that writes
to the trunk after a merge can land between the two reads, so a perfectly synchronised
checkout reports a mismatch. One disagreement is not evidence; this false alarm fired
four times in one session and was never real.

Read the *fetch's* own exit code for the same reason. A failed fetch and a subsequent
"Already up to date" print into the same buffer, and the reassuring line is the lie.

**An aborted pull has causes with different remedies, and the message says which.**
Naming remote and branch cures the silent no-op but neither abort, so read the text
before reaching for a familiar fix:

* **"Local changes would be overwritten"** — uncommitted tracker state. Checkpoint it
  first, *then* clear, *then* pull. Clearing before checkpointing reverts whatever the
  tracker just recorded.
* **"Not possible to fast-forward"** — genuine divergence, usually your own checkpoint
  against commits that landed while you made it. Rebase, then **push**: the rebase
  replays your commit on top and leaves you ahead by one, and the equality check cannot
  pass until it lands. **That intermediate state is expected, not a second failure.**
  Both obvious reflexes are wrong — repeating the pull stays ahead, and forcing equality
  discards the very checkpoint the drill exists to protect.
* **"Cannot fast-forward to multiple branches"** — a *shared-state* race, and neither
  remedy above touches it. Linked worktrees share one `.git`, and therefore one
  fetch-head file. Concurrent workers fetching in their own worktrees leave a multi-ref
  fetch-head, and your pull reads theirs. The discriminator is the divergence count: zero
  ahead means a clean fast-forward is available, so this is contention, not divergence.
  Bypass the shared file by merging the remote-tracking ref instead. **Expect this to
  grow with fleet size**, and note that dispatching immediately after merging is what puts
  the fetches and the pull in the same seconds.
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
candidate. Dispatch a fix worker onto the **existing** branch that runs the **actual**
failing gate, not a proxy that already passed.

---

## 2. Dispatch

**Re-read the raw ready list every tick.** Do not infer the backlog from
notifications, and do not trust a filter that returned empty — an empty filter is not a
dry backlog. One mayor under-saturated at one to three workers while a hundred items
were ready, because a homegrown filter kept answering empty.

**Read the newest note first.** On most trackers the description is the *oldest* text on
an item; corrections, scope changes and audit findings accrete below it. Read bottom-up.

Filter out before shaping anything:

* items awaiting an operator decision or hold;
* items gated on another's merge;
* items whose surface a live worker holds;
* items colliding in a hot-zone file;
* items whose resource is exclusive and currently contended.

**A ready list overstates readiness by a lot.** In practice roughly a fifth of "ready"
items were genuinely dispatchable; the rest were fenced by something the tracker cannot
represent. **Record the fence on the item** when you find it, with what clears it, or
every tick re-derives the same conclusion.

**Verify before dispatching, not after.** Grep that the alleged broken symbol, missing
file or stale convention is still there. If it already landed, close as a verified
duplicate.

**A count is a claim too, and a symbol-shaped grep does not check it.** A census item
("54 chains", "15 hits", "eleven sites") asserts a number, not a symbol, so the symbol
still resolves while the count is zero or triple. Re-run the item's own census at the
current trunk tip. Measured drifts in one session: 8 → 9, "four hits" → 23 lines,
"roughly 6–10" → 23, "4 of 21 cached" → 13 of 21. Two items once went out in one wave
against work merged fifteen hours earlier; both workers returned a correct "already
fixed", so the waste was two dispatches rather than a wrong result.

**The sibling that discharges an item is usually the one whose fence sent the work
elsewhere.** A tree fenced off from item A is exactly the tree item B is free to take.
Read *across* a split's siblings before dispatching any one child, and treat a parent's
remainder table as stale until re-derived.

### Shape and saturation

Target a fixed number of concurrent workers — six is a workable ceiling for one
operator — and refill the instant a slot frees. Dispatch low-priority items to stay
saturated; the goal is a backlog trending to zero.

Pick the shape by kind first, then priority, then size. The shapes are in
[`dispatch-prompt-template.md`](dispatch-prompt-template.md).

**One agent owns a surface; surfaces run in parallel.** Same-surface items ride one
agent, which never collides with itself. Two workers never share a surface — they
merge-conflict and can silently revert each other.

**The unit is the block, not the file.** Two items dispatched into the same *paragraph*
of one document conflicted, the second change could not merge, and by the time its worker
was resumed the hygiene loop had reaped its worktree. Two costs from one scheduling error.

**Dispatch immediately on clear.** A clear, unblocked item goes out now, not next tick.

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
conflicting, several fixed minutes after they were found.

**But routing does not create an owner, and that is the half people drop.** The message
lives in one agent's transcript. If that agent dies, times out, or reasonably declines the
extra item — declining is often correct — the finding evaporates, and the audit that found
it has already run. An audit caught exactly this: a mayor had identified a stale deferral
but *"routed it only to a transient worker, leaving no durable owner"*, the sole record
being a close reason on a **different** item — a record on the wrong object, because nobody
reads a closed item looking for open work.

So put **one note on the owning item** saying what was routed, to whom, and what happens if
it does not land.

**Sometimes routing is the wrong move altogether.** A worker dispatched under a
deliberately bounded fence has that fence widened mid-flight by a second finding, which is
how a bounded repair becomes an unbounded one. Then leave that owner queued or sequenced
instead, and note the overlap on the in-flight item — saying explicitly that the item is
owned elsewhere and is **not** part of the in-flight repair, so nobody reading that item
bottom-up adopts it.

Route-and-note, or queue-and-note. An owner plus one note is common to both and is the whole
safeguard. It does not want to grow past that: no routing registry, no tracking field, no
script.

### Quiescent is a valid state

At the tail of a drain, dispatch is one-unblocks-the-next, not fan-out. Hold, surface what
needs the operator, and do not manufacture work.

---

## 3. Posture, and the stranded sweep

### Posture

**Keep the project's stance as a single quoted block in exactly one file, and paste that
block verbatim into every dispatch preamble.** This loop is that file's natural home,
because it is also the loop that re-reads it.

**Never summarise a stance.** The lenses say what good looks like; everything after them
says when to STOP. A paraphrase keeps the memorable half and drops the restraining one, and
what survives does not read as incomplete — it reads as a stance that wants MORE of
everything, which is precisely the failure the second half exists to prevent.

Which clauses turn out to be load-bearing is worth knowing in advance, because they are the
ones a summary sheds first. In one project: *"trust the programmer"* is what rejects a
nagging diagnostic; *"close minutiae rather than actioning it"* is what lets an item die with
its reasoning recorded instead of consuming a worker; *"a finding is a CLAIM"* is what stops
an audit's output being mistaken for a queue. And the licence to refuse is load-bearing: in
one session three of six dispatches came back as reasoned refusals, and each was worth more
than the work would have been, because a migration performed on a false premise costs far
more than a tracker item.

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

**Discriminate before acting** — a long-running worker and a stranded one look identical from
outside, and both readings went wrong in a single day here, in opposite directions.

1. **Has the commit count on that item's branch moved since the last tick?** A moved count
   says alive. This is the cheap, reliable discriminator, and it works precisely because every
   brief demands commit-and-push-as-you-go. An unmoved count says *nothing* on its own.
2. **Is there a live task to message?** Try messaging first — resuming beats redispatching,
   because the worker's context is still there. **The commonest strand by far is a worker that
   detached a long gate and then ended its turn**, waiting for a completion event that nothing
   sends. Seven such incidents happened in one day; every one recovered intact the moment
   somebody asked for a status.
3. **Only when there is no live task and no commit movement:** push any existing commits on the
   branch — pure durability — then set the item back to open with a note saying what was found
   and what was salvaged, and redispatch.

**Never build a commit from someone else's uncommitted work.** Only that worker knows whether
it forms a coherent change.

---

## 4. Hygiene

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

The signals that have never lied are reads rather than inferences: the agent's own completion
report, and whether the messaging tool finds a live task to deliver to.

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

**Reaping on the report is correct and still costs something.** A worker can need its tree *after*
it reports, because its change hits a conflict and needs a rebase. Pushed commits make that
survivable — the worker recreates the tree on the existing branch and continues. Whether to hold a
worktree while its change is open is a real trade, and "leave the rule alone and accept the rebuild"
is a defensible answer. **Do not add a second condition to a rule whose strength is that it has
one.**

### Removing

**If your project links or junctions shared dependencies into worker worktrees, never remove a
worktree with the plain command.** It follows the link and deletes *through* it, emptying the shared
tree it points at — silently, exiting successfully, breaking every local build until the
dependencies are reinstalled. It has happened more than once in this project.

Put the safe sequence in a **script**, not in prose. Prose was tried and the class recurred anyway.
The script should: snapshot every real shared-dependency tree; unlink each *link* under the worktree
— the link only, never a recursive delete; verify each is gone; then remove the worktree; then
re-check the snapshot and **fail loudly if the signature moved**, naming the recovery command.

**Verify the shared tree's file count either side of a batch**, captured at runtime. A remembered
constant cannot detect a delta, and a top-level count alone cannot see files vanishing from under
packages whose directories survive — so count immediate entries *and* recursive files.

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
  husk as clean. Retrying is futile; an ordinary recursive delete once the lock clears is all that
  remains. **A partial removal kills a running gate exactly as a complete one does** — never read it
  as "nothing happened".

**A husk is identified, never inferred.** "The tool does not list it, and it has no metadata" is true
of a husk and equally true of an ordinary directory, a mistyped argument and an unrelated project.
One tool handed a temp directory created seconds earlier unlinked the dependencies inside it, called
it a destroyed tree and recommended deleting it. So refuse an unregistered path outright unless the
caller acknowledges it **and** its contents are recognisable as this repository's, established from
version control's own object database. Never spray that acknowledgement across a sweep list.

### Branches

Delete merged local and remote worker branches — **never one whose change is not verifiably merged**.

**Key destructive operations on identity, never on a name.** Branch names repeat across sessions and
prefix-match each other. A search for `head:feature-x` also returns the change for `feature-x2`, and
may rank the sibling *first*, so a loop reading the top row reads one branch's state as another's.
Here it read "open" for a branch whose own change had merged, and merely skipped it — but the same
shape reversed deletes a live worker's branch on its sibling's verdict. **Ask for the exact head-ref
field and compare it for equality yourself.** A search term substring-matches; only equality
identifies.

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

## 5. Method reread

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

Report one or two lines unless you find real drift. If you find drift, fix the document or the command
file — do not just note it.

---

## Tracker mechanics

These belong to no single loop and bite in all of them.

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

**A tracker write can silently revert.** Items verified closed can read open again cycles later — a
rollback to an earlier snapshot, a re-import over the top — and nothing in the loop surfaces it, so the
session's "closed" count quietly overstates. Verifying at the moment you close is not enough. Each cycle,
re-check everything closed this session and re-close what genuinely reverted, with evidence.

**But read the item's notes before re-closing anything.** If your process audits merged changes, expect
closed items to reappear: twelve did in one session, and every one was a legitimate audit reopening the
item that owned a residual. **The reflex to re-close a "reverted" item destroys a real finding and looks
like tidiness.** Expect chains — a fix lands, its audit reopens for a second carrier, that fix's audit
reopens for a third. Two or three rounds converge.

**Checkpoint tracker state on the heartbeat.** Many trackers auto-stage but never commit, so a long
session's state strands locally. Commit and push it each cycle.

**If your tracker exports a whole-database file, treat it as generated.** Never resolve a conflict in it
by taking one side wholesale — both sides are full exports, so picking one discards every item the other
recorded. Resolve, then **regenerate**. And never let a worker commit it: a worker branch carries a
snapshot from when its worktree was created, so committing it time-travels the tracker — items closed
since reopen, items filed since vanish.

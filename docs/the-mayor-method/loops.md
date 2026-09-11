# The loops

The mayor runs on a cadence. Register these with a scheduler once and let the cadence carry the
session.

| Loop | Cadence | Job |
|---|---|---|
| Merge + dispatch | ~15 min | Merge everything green; refill the fleet |
| Posture + stranded sweep | ~60 min | Restate the stance; find genuinely stranded work |
| Hygiene | ~2 h | Prune worktrees and branches |
| Method reread | ~60 min | Check these documents against what the tree does |

**Codify each loop body as one command file in your repository**, holding your concrete values — gate
command, tracker CLI, hot-zone list, worktree parent, check-count band. Two copies of a rule disagree
within days, so keep that file a thin pointer to this one.

**Loops fire on time, not on state.** *"Nothing to merge, fleet saturated, here is why"* is a complete
report; a loop that manufactures work is worse than a quiet one. Run the merge loop on any
re-invocation, not only its cadence — but never *block* on CI. One-shot queries, merge what passes
now, re-check next signal. Same for any long local operation: run it detached and stay answerable,
because the operator cannot tell a busy session from a stalled one.

---

## 1. Merge

Resolve the head first — branch **and** head revision; clauses 4 and 5 are keyed to it. Merge only on
all five:

1. **A non-empty rollup, in the band this repository actually produces.**
2. **Every check in a passing or skipped state.**
3. **Nothing non-terminal in the rollup.**
4. **Every workflow run at the current head revision COMPLETED.**
5. **The check set was computed against the workflow matrix on the trunk now.**

No bypass. An administrative override is for the host's mergeability-recompute lag — not a check — and
only once all five are met.

**A draft is never a candidate.** The five test the CHANGE; none sees whether the author has finished.
Marking ready is the worker's last act. The one case this loop must remember, because it creates it:
**a fix dispatch onto an existing ready change inherits a flag its first author set** — convert it back
to draft before the worker starts, and confirm the state took.

### Gotchas

- **Zero failures and zero pending is also what an EMPTY rollup reports** — the seconds before runs
  exist, and everything during a provider outage.
- **The band moves; never trust a written number.** Measure it on changes merged *after* the last
  matrix change.
- **Below band has three causes.** With live runs at the head it is a set still building — wait.
  Without, it is clause 1. A change that ADDS a required job legitimately reads band-plus-one.
- **Cancelled is *completed*,** so a tally keyed on completion counts it green. Read conclusions; treat
  everything but success and skipped as not passed.
- **Skipped says nothing about the trunk** — a surface-armed job is skipped on the trunk push that
  broke it. When a change reds a gate the trunk is green on, check whether that job ever RAN there; if
  not you have uncertainty, not a verdict, and only reproducing the gate on the base settles ownership.
  **A right answer reached by the refuted inference is still a defect**, because the inference is what
  you carry to the next case.
- **Never enumerate the blocking states** — requested, waiting and pending exist beside queued and
  in-progress, so a list fails open on the first one nobody wrote down. Require the terminal state.
- **A settled rollup is not a quiet branch:** a queued run's checks are not attached to the head yet.
- **A run triggered by anything but the change event contributes NOTHING to that rollup, ever.** A full
  count is the more dangerous reading, because a short one sends you looking. Query the runs whatever
  the count says.
- **Key the run query to branch AND head revision.** Neither, and a truncated global listing reports
  "no live runs" for a branch with four. Branch alone, and it blocks forever on superseded runs — a
  guard that blocks forever is not safe, it is differently wrong.
- **Case-fold both surfaces.** A CLI commonly reports check states in one case and run statuses in
  another; one crossing fails closed, the other open.
- **Pass the full-length revision as the host reports it, never one extended by hand** — an abbreviated
  or fabricated one matches nothing and clears the clause vacuously. A full rollup beside an empty run
  list is the tell.
- **Clause 5 is reading, not counting**, and every field you can read says green. Count the structural
  lines landed on trunk since the merge base, then read them: nine cache-step lines add no job; four
  lines adding a job name with its condition are a new required check; a display rename changes nothing.
- **A job added to a workflow your changes never run contributes no check** — unqualified, that bullet
  fails closed. Read which events start each named file.
- **A renamed job reads as a pure addition** if you inspect added lines only. Count removals too: added
  == removed with corresponding names is a rename, and the band moves by zero.
- **The set can narrow from the BRANCH.** A retirement legitimately reads below band; the count cannot
  tell a narrowing from a silent disarm, so read which key left and check it against what the change
  deleted.
- **Do clause 5 BEFORE merging.** Afterwards it is easy to reason the drift was harmless; it usually
  is, and *usually* is what the other four exist to refuse.

### Reviewing what you merge

Check the diff against its item, that scope did not sprawl, and that the quality-gates section is
present. Merge every green change **regardless of author**.

- **Read the FILE LIST, not the count.** Sprawl announces itself; a change that silently REVERTS merged
  work is green, compiles and passes every gate. Direction is the test: for a surprising file, ask
  whether something present on the branch is absent from the trunk. **No gate can catch this** — a
  revert is mechanically indistinguishable from a change that is not one.
- **Green and mergeable are separate facts.** Ask the trunk with a three-way merge into a scratch tree.
- **That test's non-zero exit is two verdicts.** A real conflict and *a head revision your checkout has
  never heard of* both fail; the second is routine after a host-side branch update. Read the message,
  fetch, ask again. Only a genuine conflict warrants a rebase dispatch.
- **Prefer server-side branch deletion to a client-side delete flag** — clients abandon the remote
  deletion along with the local one, orphaning a ref on every merge whose worker has not reported. **A
  surviving remote ref is never grounds to reap a worktree early.**
- **"Base branch was modified" usually means stale — until something is moving the base.** Where
  automation writes to the trunk the race is real and updating makes it worse: merge the queue first,
  then give the straggler an empty window. Shelve it after about three refusals if it blocks nothing.
- **Merge CONFLICTS are the author's, not a fix worker's.** On a shared serial-lane file each merge
  flips the rest to conflicting — the lane working as designed. Message the author; brief whoever takes
  it to keep BOTH intents hunk by hunk; **state your believed cause as a claim**, made at the moment of
  least information.

### After each merge

Fetch the trunk, then fast-forward onto the remote-tracking ref — two commands chained, remote and
branch named on the fetch. Verify the local head equals the remote head. Verify the worker closed its
item, and that anything routed into the change landed in the diff.

- **Verify the tree, not the message** — a pull prints `Updating <old>..<new>` on a head that did not
  move.
- **One disagreement is not evidence**: automation can land between the two reads. Read the fetch's own
  exit code too — a failed fetch and a following "Already up to date" print into the same buffer.
- **Never collapse the pair into a pull.** A pull takes its target from a scratch file any concurrent
  process in the checkout rewrites, and it does not fail honestly — measured once silently
  fast-forwarding the trunk **onto a feature branch**, and once aborting with the divergence message on
  a clean tree where that remedy is inert. **A failure that borrows another cause's message cannot be
  discriminated by message text.**
- **An aborted fast-forward has two causes and the message says which.** *"Local changes would be
  overwritten"* is uncommitted tracker state: checkpoint, *then* clear, *then* retry — clearing first
  reverts what the tracker just recorded. *"Not possible to fast-forward"* is divergence: rebase, then
  **push**. You are left ahead by one and **that is expected, not a second failure** — repeating the
  pull stays ahead, forcing equality discards the checkpoint.
- **A remedy inherits the hazards of whatever it reads**, so rebase onto the ref, not with a pull
  carrying a rebase flag.
- **Never wire a remedy behind a pipe** — the pipeline's status is the filter's, so the fallback never
  runs.

### When a change is not green

A repeated failure on the touched surface is never an override. Once clause 2 establishes the failure
is the change's own, dispatch a fix worker onto the **existing** branch, running the **actual** failing
gate. If the change is already ready, convert it to draft first.

- **A red on a DRAFT belongs to its author while that author is alive.** A worker pushing as it goes
  publishes reds by design — one deleted a gate's witness in its first commit and the gate in its
  third. Read the failing job against the diff so far.
- **A failure before any repository code ran names itself** — re-run it. But when SEVERAL changes fail
  that way rather than one job twice, fleet size is the cause and re-running is not a cure.
- **A check that never TERMINATES fits neither remedy.** Clause 3 blocks it correctly; what is missing
  is what to DO. Recognise it against that job's OWN recent cost, measured.
- **A SKIPPED run reports as a success and contributes a ZERO-LENGTH sample.** For a conditional job
  most recent "successes" never executed, so the baseline collapses toward zero and every real run
  reads as overrunning. It fails in the alarming direction, manufacturing a hang. Discard the
  zero-length samples and check the survivors are a plausible sample size.
- **A timeout kill usually reports as CANCELLED.** Landing ON the declared cap is a timeout; finishing
  well inside it is a superseding push or a hand cancel. Rollup jobs go red *because* of the
  cancellation — counting them as independent failures points at the wrong remedy.
- **Discriminate before cancelling.** Unrelated jobs overrunning in one run is infrastructure — cancel
  and re-run. ONE job overrunning on a surface the diff touches is a hang the change introduced. **A
  "cancel and re-run" without that discriminator is worse than no remedy**: it burns an hour and hides
  the infinite loop a worker just wrote.
- **An uncapped job is the only reason this needs a mayor-side remedy.** Cap the jobs.

---

## 2. Dispatch

Re-read the raw ready list every tick, ordering each item by the tracker's own timestamps. Filter out:
operator holds; items gated on another's merge; surfaces a live worker holds; hot-zone collisions;
contended exclusive resources. Then shape, dispatch, and write the ledger line as you go.

Target a fixed ceiling — six is workable for one operator — and refill the instant a slot frees.
Dispatch low-priority items to stay saturated. Pick shape by kind, then priority, then size. **A clear,
unblocked item goes out now, not next tick.**

### Gotchas

- **An empty filter is not a dry backlog.** One mayor sat at one to three workers while a hundred items
  were ready, because a homegrown filter kept answering empty.
- **The exclusive-resource filter is the only exclusion with a release condition, and the loop must
  lift it.** Where the exclusives are all that REMAINS, the filter has stopped protecting the fleet and
  is refusing the backlog — drain deliberately and take that work.
- **Where failures are reported outside the tracker** — a nightly alert channel — read it before the
  ready list, one open item per alert keyed on the alert's own id. **A failed read is "did not sweep",
  never "nothing red"**, and *item closed, alert still open* is the expected state after a fix lands.
- **Where your instructions carry no query for that channel, the read is not owed — do not invent
  one.** An invented query is undetectable when the channel is empty: the wrong instrument and the right
  one agree, and their agreement is not evidence.
- **A ruling can be a child item OR the close reason of a CLOSED dependency.** **Enumerating by id
  prefix is not enumerating** — a generated id need not share the parent's prefix, so a prefix filter
  returns the children and omits the item that governs.
- **A ready list overstates readiness by a lot** — roughly a fifth were genuinely dispatchable.
  **Record the fence on the item it fences, with what clears it, at the top, and in the same words every
  time.** The tick reading it back is scanning, not reading; a fence recorded on the item that *causes*
  it is never met by its own reader. The sameness does the work, not the wording.
- **A marker finds a DISCHARGED hold as well as a live one.** Whoever discharges it strikes it in the
  same act. Where you cannot edit the original, append the negation in the same words so a scan returns
  every occurrence and **the newest governs**.
- **Verify before dispatching.** If it landed already, close as a verified duplicate.
- **A count is a claim, and a symbol-shaped check does not test it** — the symbol still resolves while
  the count is zero or triple. Re-run the item's census at tip.
- **An item's LIST OF SITES is worse than a count**, because it drifts in MEMBERSHIP: an entry somebody
  already fixed sends a worker to edit correct text, while a site the list never had stays live.
  Re-derive it at tip and require the DELTA reported.
- **Exclude any generated export from a tree-wide census** — a whole-database export matches almost any
  identifier and floods a context-bounded worker with tracker rows.
- **Check whether the work is already DONE in the tree**, which no metadata check asks. Search the whole
  commit MESSAGE (subject-only fails open); flatten each commit to one record, or a line-oriented search
  attributes an identifier to its neighbour; anchor the identifier, or a shorter id matches every longer
  sibling. **A matching commit is a POINTER, never a closure** — some hits are the very change the item
  was filed AGAINST, a fact the title often carries in a word like *still*.
- **The inverse fails the other way: work that HAS landed on an item still legitimately live.** Where a
  process audits merged changes, a reopen leaves the tree confirming the original deliverable exactly as
  for a finished item. **The tree answers *did this work land*, never *is this item finished*.**
- **The sibling that discharges an item is usually the one whose fence sent the work elsewhere.** Read
  across a split's siblings, and treat a parent's remainder table as stale until re-derived.
- **The unit is the block, not the file.** Two items that read as different concerns can land in the
  same paragraph — invisible when you schedule them, cheap to avoid only there.

### Contention comes in four shapes; the ceiling bounds only the first

**Surfaces** are what the parallel-or-sequential split reasons about. A **heavyweight gate** is
contention for the MACHINE — six workers on six disjoint surfaces still wedge one another, and a wedge
presents as a hang. The **shared allowance** has no local evidence at all, so N workers are not N
independent bets: they fail TOGETHER, mid-run, on a limit none approached alone. And **the tracker
itself, where YOU are the competitor** — the first three degrade the workers, this one degrades the
mayor.

- **The remedy for the allowance is not a smaller fleet**, which trades a recoverable failure for a
  permanently slower one. *Push continuously* makes a fleet-wide kill survivable.
- **A coordinator timeout presents exactly as a corrupt store, and both reflexes are wrong**: re-running
  adds load to the overloaded thing, and restoring the export from version-control history silently
  reverts every item recorded since. Treat it as contention until something proves corruption.
- **A no-store first look reads the export and the tree, never the database** — so it cannot tell you a
  timed-out mutation committed. **And equal counts are not equality**: one checkpoint passed its floor
  at 1,938 against 1,938 having dropped three items and reverted two statuses, the sides substituting
  one-for-one.
- **A queued measurement window makes an otherwise-free slot not free.** Hold the fleet thin on purpose
  and keep dispatching work whose gates leave the machine quiet. **Quiet is a property of the whole set
  of gates an item arms** — no single classifier describes that set.
- **Where a window registers that no peer WRITES in its bracket, any write voids it however cheap.**
  Merging a change and creating a worktree cost nothing and are both writes, so a rule ordered by cost
  gets those backwards.
- **An empty fleet is not a quiet machine** — gate processes outlive their gates. **But counting them is
  the same error one level down**: presence is not load. One idle box carried a hundred-odd processes
  accounting for 0.14 of 2.6 busy cores. Measure LOAD, at both brackets.

### Routing a finding onto a live worker's file

Resolve an exact owner, then route-and-note or queue-and-note. **An owner plus one note is the whole
safeguard** — no registry, no tracking field, no script.

- **Filing is not dispatching.** It is a second *dispatch*, not a second item, that puts two workers on
  one file.
- **That the holder's change is still OPEN is a precondition** — test it before testing reachability.
  Where it has merged there is nothing for the fix to land in, and a reachable agent is not a route.
- **Routing does not create an owner.** The message lives in one transcript, so if that agent dies or
  reasonably declines — declining is often correct — the finding evaporates and the audit that found it
  has already run.
- **Sometimes routing is wrong**: it widens a deliberately bounded fence mid-flight. Queue the owner and
  note on the in-flight item that the work is owned elsewhere and is **not** part of this repair.

### Two standing conditions

**Quiescent is a valid state.** At the tail of a drain, dispatch is one-unblocks-the-next, not fan-out.
Hold, surface what needs the operator, and do not manufacture work.

**You are one of the concurrent writers.** The scratch area is keyed to the SESSION, not to a worktree,
and the mayor writes there too. **A rule scoped to a ROLE exempts whoever does not identify with the
role** — one mayor enforced the naming rule on eight dispatches and then lost its own change body to a
peer under exactly the bare name it forbids. **And naming one shared area re-creates that exemption one
level down**: a system temp directory feels private and is the reverse. The scope is every directory
you do not exclusively own.

---

## 3. Posture, and the stranded sweep

### Posture

Keep the stance as a single quoted block in exactly one file and paste it verbatim into every preamble.
Reassert any **voice** the operator asked for — voice drift returns within about ten turns, which is
why it belongs in a loop. Then one line: orchestration, not implementation.

- **Never summarise a stance**, because a summary sheds the load-bearing clauses first: *trust the
  programmer* rejects a nagging diagnostic; *close minutiae rather than actioning it* lets an item die
  with its reasoning recorded instead of consuming a worker; *a finding is a CLAIM* stops an audit's
  output being mistaken for a queue.
- **Name the dispatch when you flag drift** — the mayor coding, a missing boundary block, a stance
  paraphrased rather than pasted, an override misused, minutiae actioned instead of closed. Otherwise
  one line is enough.

### The stranded sweep

**Start from the worktrees**, not the in-progress list, which is empty by construction unless your
dispatches claim their items. Map trees to items, then ask in order: **has the tip revision moved? is
there a live task to message? only with neither**, push any existing commits, reopen the item with what
was salvaged, and redispatch.

- **The failure is worse than a missed strand**: a dead worker's items read open, look dispatchable,
  and the next tick sends a **second worker to the same branch**.
- **Record the tip REVISION, never an ahead COUNT** — a count survives a rebase, which is what a briefed
  worker does constantly.
- **Read the ref the commit updates directly, and prefer a read that performs no refresh.** The local
  ref moves on commit; the published one only on push, so it lags in the direction that reads as death.
- **An unchanged tip says nothing** — a worker inside a long gate commits nothing by design.
- **The corroborating activity clock is a WRITE clock, so a worker that is only READING touches
  nothing.** Twice in one day a worker grepping its own gate log was called stranded on twenty-three
  minutes of silence.
- **A tracker claim is the one positive signal a purely reading worker still produces**, and it lands
  outside the tree where no file clock sees it. Its absence proves nothing.
- **If your tooling maintains a live status for a running agent, read THAT before any clock.**
- **Frozen on every clock is AMBIGUOUS, across any number of readings.** A worker in its final minutes —
  last gate, then tidying scratch files and links — works outside the worktree and outside version
  control and is too busy to answer, so all four signals read dead *together, for one cause*. Measured
  twice in one day: five workers called dormant, three completed alive, two never explained. **Do not
  count a live control in the numerator** — that is how the figure was first written down wrong.
- **Say what the re-read window is FOR**, or a coordinator fills an empty interval with more
  measurement. It catches exactly one thing: movement, which proves life and needs no corroboration.
  Read twice, record, go elsewhere — the tenth reading answers what the second did. **A freeze measured
  in hours is unremarkable on a heavyweight gate**: six readings over ninety minutes, all identical,
  five workers all alive and completing between an hour forty and nearly three hours.
- **That activity clock says "no activity" in four voices**: a reading worker; a poller; a path that
  resolves to nothing; and a span shorter than the worktree's own age, which returns a LARGE number that
  reads as proof of life — measured at the same count over fifteen minutes and over six hours. **And the
  age is CREATION time, not last modification**, which the writes you are detecting keep bumping.
- **A POLLER edits and commits nothing, so both clocks stay still for hours** — but a fetch writes as it
  reads, into that worktree's own fetch-head file. Read it twice, thirty to sixty seconds apart.
  Movement proves life and needs no control; stillness restores the ambiguity. **Presence says nothing**
  — a stopped worker leaves its copy behind frozen — **and absence says only that no fetch wrote there**,
  which a flag on the fetch can suppress. Measured on a poller six sweeps had read as silent while it
  fetched every forty-two seconds.
- **The most convincing false signature is on none of the discredited lists**: green at band, clean
  worktree, tip minutes old, still a DRAFT. It reads as a worker that finished and forgot to publish — a
  real state — but a worker presenting exactly so was on attempt three of its local gate. **Not one of
  its four parts observes the agent.**
- **A change carries two clocks recording two EVENTS.** A rebase rewrites the committed time and replays
  the authored one, so under rebase-merge they differ on essentially every change — two honest readers
  called one change forty-three minutes old and seventy-five seconds old. **Liveness is the rewrite
  event, so read the committed time, and name the event in the sentence.** Where the event is not one the
  change records, no clock answers: ancestry settles ORDER only.
- **The commonest strand by far is a worker that detached a long gate and then ended its turn**, waiting
  for a completion event nothing sends. Seven in one day; every one recovered intact the moment somebody
  asked for a status. **Message first — resuming beats redispatching.**
- **A harness agent-stopped event outranks every clock for that agent.** From a worker whose last message
  says it is WAITING, it is the diagnosis itself: the awaited wake no longer exists. Resume without
  further discrimination.
- **A send to a stopped agent may be ACCEPTED** — delivery promised at a next tool round that never
  comes. **Acceptance is a queue confirmation, not a liveness report; the reply is the read and the send
  is not.** So read *no live task* as a request unanswered across a window you declare, or the clause is
  unsatisfiable and gets discharged by an invented proxy.
- **The error is cheap in one direction only, and it favours waiting.** A late reply costs a tick; acting
  on a wrong *no task* destroys the run.
- **Read WHY the worker stopped.** Where the stop reason names an exhausted allowance, *a remedy drawing
  on the resource whose exhaustion caused the failure is not a remedy* — every attempt fails identically
  and every attempt spends. Salvage, **merge whatever is green — merging is unaffected and is the half
  that still pays** — then stop. **The reason text, not the symptom, chooses the remedy**: a quota death,
  a crash, a timeout and a detached-gate strand look identical from outside.
- **The stated reset is a floor, not the only release.** An operator act can restore the allowance early
  and nothing tells the mayor. Spend ONE resume message on the worker with most context to lose, and
  **read its LIVENESS rather than waiting for words** — an agent under an exhausted allowance dies within
  seconds, so minutes of *running* is positive evidence. Stillness is not the converse.
- **A finished change is not a strand — but publishing it is still not yours to infer.** The draft flag
  is the interlock precisely because a merge command refuses a draft. Use the authorisation the dispatch
  template names: **the operator's decision to STOP that agent, which settles liveness by making it
  false. Stop, then merge.**
- **The ORDER is the whole rule and reversing it looks identical afterwards.** Both orders leave a merged
  change beside a stopped agent, so nothing in the record can later tell them apart.
- **That authorisation is not an escape hatch from the ambiguity**, and reading it as one is the natural
  mistake, because it is the only ACT on offer in a section that otherwise says wait. Measured once: five
  agents escalated as possibly stopped, all five alive, and the operator's answer would have destroyed
  four live runs.
- **The residual risk belongs to stopping, not to merging.** You merge the PUBLISHED head, which CI
  graded, so the loss is an unpushed fix rather than a regression introduced.
- **Never build a commit from someone else's uncommitted work.**

---

## 4. Hygiene

### Reaping

**Only a worker's own completion report authorises reaping its worktree.** Not a merged change, not a
clean tree, not elapsed time, not directory age, not "no commits and no open change", not clean and
merged together — all six were adopted in one session and all six were wrong.

**The operational test: can you quote the sentence where this agent says it is done?** If not, the tree
stays. A stale directory is the entire cost of waiting.

- **Cleanliness is perversely the worst proxy**: a worker that pushed everything exactly as briefed
  shows a clean tree throughout a twenty-minute gate — *the better the discipline the likelier the kill*.
- **The wreckage does not announce itself as infrastructure.** Gates die naming real, present files as
  missing, which reads to the worker as a genuine regression.
- **An interim status, a progress note, a partial hand-off and a change-opened announcement are the
  agent speaking, not reporting done.**
- **A gate the worker backgrounded is still that worker running**, and **one worker is not one
  worktree** — a worker may build a second for its gate run, so an unfamiliar tree belongs to someone
  until its agent reports.
- **Never pair a merge with a reap, or put the reap in the merge loop.** A merged change is the moment a
  tree *looks* finished, which is exactly the pull, and a rule encoded in a loop condition is delegated
  to a predicate that cannot see what you know.
- **Deferring the reap until the change merges is free insurance** — a worker can need its tree after
  reporting, when its change hits a conflict. Do not make that a second condition on a rule whose
  strength is having one.

**Finding the report.** Record worktree and agent id at DISPATCH time, one line per dispatch in a
mayor-local file; clearing the mayor's context destroys its ability to quote a report, and this is the
route back to the sentence. **Two fields belong to this rule and the rest to the project.** The reap
test reads no field of that line at all — it reads the report, which the id leads you to.

- **Nothing validates that line, so specify its fields somewhere tracked.** A version-ignored file's own
  header is the only description of it that exists — and here header and rows drifted apart while both
  carried five fields, so no count disagreed and a reader addressing a field BY NAME got its
  neighbour's value.
- **The id buys the TRANSCRIPT, not a live conversation**, and may key several locations. An empty file
  keyed by the id is a fact about *that location*, never about the id.
- **With no id, the report is unindexed rather than missing.** Search transcripts for the worktree's
  path — but **the match is many-to-one**, since the dispatching session and every peer brief name it
  too. Hit count per file is triage; **the report's own stated root is the decisive test.** Where a
  directory name was reused, separate incarnations by the branch the tree carries NOW.
- **A worktree the MAYOR occupied has no agent**, so the search is empty for that reason alone — which
  is the reap test's blocking condition. **The rule does not bend: for that tree the mayor IS the
  agent.** Identify it by the branch the work was PUBLISHED from; tip revision identifies the work, not
  the occupant.
- **Do not promote occupancy into a proxy.** A tree at the trunk with nothing of its own is the ordinary
  state of a worker just created — the most dangerous tree to remove, not the safest.

### Removing

**If your project links a shared dependency tree into worker worktrees, never remove a worktree with
the plain command** — it follows the link and deletes *through* it, silently, exiting successfully. Put
the safe sequence in a **script**: snapshot every shared tree, unlink each *link* (never a recursive
delete), verify, remove, re-check, and **fail loudly if the signature moved**.

- **Removal is not the only write that follows a link.** An installer a gate runs rewrites the shared
  target, so a tree can be emptied while its worker is alive and no cleanup has run.
- **Capture the snapshot at runtime and count both sides the SAME WAY.** Count immediate entries *and*
  recursive files — a top-level count cannot see files vanishing under surviving directories. And a
  listing that hides entries turns the comparison into an offset that **CANCELS a real loss of its own
  size**.
- **So make the decisive control a clock, not a count** — the newest modification time inside the tree,
  which a removal moves and a change of listing options cannot.
- **Sweep with a narrow force flag, never a blanket one.** A disposable-only force refuses any tree
  holding a modified tracked file, a note or a draft; when that guard was written, four worktrees held
  uncommitted work a blanket force would have destroyed silently.
- **A refusal has four modes and they are not interchangeable** — nine worktrees were once read as
  locked, waited out for two days, and were all simply dirty.

  | mode | meaning | remedy |
  |---|---|---|
  | Dirty | modified or untracked files | waiting never clears it |
  | Held | the sweep stopped on something that is not build output | nothing was deleted |
  | Locked | clean and intact, a live process holding a handle | genuinely transient; retry |
  | Partial | a **husk** | the acknowledged-husk path only |

- **A husk no longer appears in the listing, pruning has nothing to clear, and a status check inside it
  fails and prints nothing** — which is why a clean/dirty test reads it as clean. **Do not reach for a
  raw recursive delete**: a husk still holds every file the worktree had, links among them. **A partial
  removal kills a running gate exactly as a complete one does.**
- **A husk is identified, never inferred.** "Not listed, no metadata" is equally true of an ordinary
  directory, a mistyped argument and an unrelated project — one tool handed a temp directory created
  seconds earlier unlinked the dependencies inside it and called it a destroyed tree. Require the
  caller's acknowledgement **and** contents recognisable as this repository's, from version control's
  own object database.

### Branches

Delete merged local and remote worker branches — **never one whose change is not verifiably merged.**
Then prune stale remote-tracking refs and clear any stray stashes.

- **Enumerate the remote set separately: it is not a subset of the local one.** This loop starts from
  the worktrees, which is right for reaping and blind here.
- **Where the project merges by REBASE, both obvious containment tests are wrong.** Ancestry keeps
  merged branches forever; the ahead count destroys unmerged ones.
- **The test is patch-equivalence, and its answers are not symmetric.** *Contained* is proof — delete
  only on it. *Not contained* is the ABSENCE of proof: patch identity covers the diff's CONTEXT lines,
  so a sibling landing in neighbouring lines leaves fully-merged work reading as unmerged. **A branch
  list read as a backlog is a list of phantoms.**
- **A line search is triage and is wrong in both directions** — no notion of path, multiplicity or
  order, and blind to deletions, so a commit whose substance is a removal scans clean having been
  inspected for nothing. **To CONCLUDE, compare the whole patch against the tip at the paths it
  touches.** Ask what the instrument compared, not what it answered.
- **A branch with unlanded commits and no proposed change may be a DELIBERATELY RETAINED RECORD** — a
  spike, whose findings are promoted as prose and whose diff is meant never to land. Ask the tracker who
  owns it: **include CLOSED items** (a retention ruling lives on one by definition), **do not filter TO
  closed** (that hides an open item with a live release condition), and **search the full export, not
  the title index**, because such rulings are written in close reasons.
- **Every defence above finds a record that EXISTS. The case with no record is a LIVE worker's branch**
  — a dispatch in flight has written its name into no item, so the strongest query returns zero and zero
  authorises deletion. **Subtract the live set before reading any zero as an orphan.**
- **Key destructive operations on identity, never on a name** — names repeat and prefix-match.
- **But equality over a LISTING still filters a WINDOW, silently.** Prefer an instrument that takes the
  identity as INPUT, filtering before paging, so it caps how many MATCHES return rather than how far
  back it looked. **An empty answer is still not a licence to delete**: it settles that this query
  matched nothing, not that you asked the right repository, states, spelling or credentials.
- **Merge state is the test for a branch, never for a worktree.** Conflating them is how the reaping
  rule goes wrong.

Throughout, the asymmetry governs: mistaking a record for residue destroys it; mistaking residue for a
record costs a branch nobody was using.

### The residue

Reap-only-on-report is correct and it leaves residue: an agent that vanishes never reports. **Keep the
rule. Let the residue accumulate, and clear it on explicit operator authority rather than inventing
another proxy.** A stale directory is cheap; a destroyed run is not.

---

## 5. Method reread

**Not by re-reading** — that spends context for nothing. Instead: **check what changed** since the last
pass, in these documents and in your loop command files; and **check the rules you have been
*enforcing* against what the tree actually does.** That second is where drift lives.

**Read a loop's page when you are about to do that loop's work, whatever this loop's cadence says.** A
destructive step performed hours before its page comes round is a step taken without it, and the reread
that follows finds nothing wrong because the damage is already done.

Report one or two lines unless you find real drift; if you find it, fix the document or the command
file rather than noting it.

### Gotchas

- **A measured constant that has gone stale.** The check-count band is the standing example: written
  down, moving, and stale exactly where it has to be right.
- **An unsatisfiable quantifier** — *"every gate prints X"* when a third do not. Workers facing one do
  not stop; they improvise, and the improvisation varies.
- **A block that has diverged from the command file that pastes it.**
- **A hazard recorded without the test that discharges it** — *"beware X"* with nothing saying how to
  tell X from the legitimate case that looks identical. Whoever wrote it had just performed that test,
  so it read as part of the observation. This loop is the only place that checks whether this document
  obeys its own rule.
- **A correction tends to overshoot into the INVERSE of the claim it replaces.** The evidence exposing a
  too-strong sentence feels like evidence for the opposite, so the fix arrives with the original's
  confidence pointing the other way — reading as *more* reliable for having been written in response to
  being caught. **The discharging test is one question: what would you expect to SEE that is different
  if the new claim were false?** If the only answer is *the evidence that refuted the old one*, nothing
  has been established. Where the observation supports neither side, say so and rest the guidance on the
  asymmetric cost of the two errors.
- **The fix is bounded in change, not in search.** What this loop finds is mechanical, so a defect in
  one place usually has siblings.
- **Weigh WHICH artefact to change.** Where a reader-side rule has failed repeatedly, more text is
  usually not the repair: two remedies that worked were an item whose OLDEST field was rewritten to open
  with its disposition, and a guard that refused an edit the rule had already forbidden in words. **And
  a guard is not a rule that cannot fail** — one added in an evening was run the next hour and misread.
- **Where the project arms a mayor-side commit guard, the mayor's own repair goes through a
  mayor-occupied worktree**, and that refusal is the guard working. Two mechanics: the refused commit
  **exits non-zero with the files still STAGED**, so read the commit's exit rather than the output tail;
  and those staged files then abort the next update with a message that never names the refusal.

---

## Tracker mechanics

- **An instrument can be RIGHT and still answer a different question from the one you asked** — and the
  usual remedy is inert, because nothing is malfunctioning and the control comes back green. Four in one
  session: running workers read as *the machine is quiet*; resident processes read as *the machine is
  busy*; a marker scan finding a banner read as *this item is held*; a note true when written read as
  *true now*. **Write the question the instrument answers beside the question you are asking.**
- **A marker scan is a CANDIDATE FILTER, never a verdict.** It over-reports items that QUOTE a marker or
  whose marker was struck, and under-reports holds in ordinary prose — and a second scan built to
  discriminate fails the same way, because marker text instructs whoever rules to strike it, so *struck*
  appears inside every live marker. **Report the number you read, never the number you scanned.**
- **A dependency can outlive what it was enforcing.** An item blocked "until X lands" stays blocked when
  X is reopened for an unrelated residual. Force the close and write the reasoning on the item.
- **Items usually close on change-*open*, not change-*merge***, so "closed" does not mean "on the trunk".
- **Verify closures by reading the status field keyed on the item ID.** Keyed on an internal row id a
  re-import reports phantom losses; searching the whole record matches the word "open" inside a title.
- **A command's echoed output is not confirmation that it did anything.** Trimming to the last line
  hides this perfectly: where a close carries a long reason, the last line *is* the reason text, so a
  refusal and an acceptance look identical.
- **Exit status and mutation are different questions and only the first is cheap.** A non-zero status
  proves refusal, so always read it; a *zero* proves the command reported success, not that state
  changed. **When you need a state change, re-read the target.**
- **A tracker write can silently revert.** Re-check what you closed across the sequence that exports,
  restores and publishes, reading BOTH sides — a single check afterwards tells you a close is missing
  but not which side lost it, and the two have different remedies.
- **But a status change made by a LIVE worker is that worker speaking, not corruption** — often
  something precise, commonly that part of the item is the operator's. Its report is the authority and
  usually lags the status by minutes.
- **Read the item's notes before re-closing anything.** Where a process audits merged changes, expect
  closed items to reappear, and expect chains. **The reflex to re-close a "reverted" item destroys a
  real finding and looks like tidiness.**
- **A reopen that records no finding is not yet a finding.** Re-closing destroys a finding still being
  written; treating it as ready dispatches a worker at work nobody has named. Mark it as awaiting its
  finding, give the writer one pass, then re-close — restoring the original reason the flip cleared.
- **A wiped closure makes any brief that CITES it unsatisfiable** — the worker finds an empty field and
  reads the absence as its own failure. Restore or re-cite **before** dispatching; verification and
  briefing are separate acts.
- **Every note you leave becomes the NEWEST text on that item**, and a newest-first walk lands on it and
  stops. **The tell is mechanical: a note repeating a figure some later note retracted re-derived
  instead of re-checking.** Cite the check you made, or say you are summarising and from what.
- **Check ONCE whether your tracker's update verb appends or REPLACES.** Where it replaces, the walk you
  are teaching readers to make runs over text you deleted — no error, and the item afterwards looks
  well-maintained.
- **Separate *needs a decision* from *needs work under a decision already made***; the second is
  dispatchable now and tends to sit in the first pile. **And read WHY a hold was set before writing what
  it costs** — an oversight and a deliberate dormancy put identical numbers behind it, so cost is only
  cost against a want.
- **A hold that expires on a DATE while its reason does not is the one hold that removes its own
  evidence.** The item returns to the ready list carrying an empty blocker column, reading as MORE
  legitimate than an ordinary item because a reappearance looks like something happened. Nothing
  happened; a date passed. Write that on the item BEFORE the date arrives.
- **Checkpoint tracker state on the heartbeat**, and **verify the remote against the local head
  afterwards** — a checkpoint is two operations and the harness can kill it between them, leaving the
  commit made, the push not, and the re-run truthfully reporting *nothing to checkpoint*.
- **Long tracker text does not travel as a shell argument**, and a quoted heredoc is not a cure. Write
  it with a tool that is not a shell and drive the tracker from that file.
- **If your tracker exports a whole-database file, treat it as generated.** Never resolve a conflict by
  taking one side wholesale — both are full exports, so picking one discards every item the other
  recorded. Resolve, then **regenerate**. And never let a worker commit it: a worker branch carries a
  snapshot from when its worktree was created, so committing it time-travels the tracker.

# Worker dispatch

Copy-adaptable shapes for delegating bounded work to a background worker. Assumes a capable agent.
Placeholders: `<MAYOR_CHECKOUT>`, `<WORKTREE_PARENT>` (derive it from your version-control tool at
dispatch time; never hardcode), `<ASSIGNED_WORKTREE>`, `<TRUNK>`, `<ITEM_ID>`.

> **Project-specifics live with the project, not here.** Your hot-zone list, your surface-to-gate
> matrix, your pre-checkin command, your worktree root and your tracker's command spellings are facts
> about *one* repository. Keep them in that repository's agent-instructions file and pull the concrete
> values at dispatch time. This page is the reusable, OS-neutral method.
>
> **The stance is project-specific too, but it is PASTED rather than pulled, and it lives in its own
> file.** Do not send the worker to a file that does not carry a stance. **And a summary is not a
> lighter version of a stance**: the lenses say what good looks like, everything after them says when
> to STOP, and a paraphrase keeps the memorable half and drops the restraining one — after which what
> survives reads as a stance that wants MORE of everything, which is the failure the second half
> exists to prevent.

---

## Write the brief in this order

The order is what makes it accurate — each step is a check the next depends on.

1. **Read the item, ordered by the tracker's TIMESTAMPS** — not by position, not by dates in the prose.
   The mechanics are in *Common preamble* below and bind the mayor exactly as they bind the worker.
2. **Check every factual claim you are about to write.** Does the symbol resolve? Does the file say
   what you think? Is the count still true? Is the ruling you cite *ruled*, or only recommended?
3. **Establish the fence by asking who is live, not what is open.**
4. **Name the discriminator**, if the task is "find every X".
5. **Then** assemble the standard blocks.

### Gotchas

- **Keep the brief to what is specific to THIS item.** The blocks travel verbatim and already carry the
  standing rules, so restating them is the commonest way a brief doubles in length while adding
  nothing — and the worker pays for every word twice, reading and obeying. Measured: two-thousand-word
  briefs on top of a five-thousand-word block file, against workers finishing comparable items in a
  third of the time on briefs of four hundred.
- **One cheap query tells you which items punish skipping the history walk: has this item ever been
  CLOSED and reopened?** The walk rule is unconditional, which is why it gets skipped. A closed-to-open
  transition is the strongest cheap signal that the description is no longer the live instruction, and
  nothing in the rendered item says so — it still reads as a current bug report, because it once was
  one. Measured: three briefs in one session written from descriptions whose work had already merged,
  all three showing the transition, against a never-closed control showing none.
- **It is a signal to do the walk, not a substitute for it.** *Closed* does not mean *shipped* where
  items close on change-open; and a reopening for a REGRESSION leaves the description accurate, so
  treating it as stale sends the worker hunting an instruction that does not exist.
- **Three consequences differ and only the first is obvious**: re-doing finished work; pointing a
  worker at a defect that no longer exists, whose deliverable is then a refutation; or — the expensive
  one — describing a *smaller* problem than the reopening found, so the worker fixes what you asked and
  the real defect survives under an item now closed over it. The worker's own walk catches all three,
  **but do not let that safety net become the plan**: it spends a worker's context re-deriving what one
  query answers, and a brief refuted politely reads much like a brief fulfilled.
- **Read for a HOLD and for SEQUENCING.** A hold is the newest field often enough that the description
  cannot be trusted to mention it. Sequencing is the mirror and easier to miss, because the brief's
  instinct is right by default: a triage note naming predecessors that had to land first is an
  **authorisation with a precondition**, so restating a generic fence over it converts a satisfied
  precondition back into a blocker.
- **Read the INVERSE with more care than either, because it fails open.** A note lifting a fence names
  *the fence it lifts*, not the item — so a second sequencing the lift says nothing about can sit
  underneath while the newest layer reads *released*. The other two re-block work that was clear; this
  one dispatches into a held surface. **Where the tracker can express a precondition, encode it there**:
  a fence that exists only in a note is re-derived every pass, and one re-derived every pass is
  eventually missed.
- **A board-wide sweep for hold markers is a candidate filter, never a verdict** — narrow to a handful,
  then READ each one.
- **Ask one question of a different kind: is this work still OUTSTANDING?** The checks above ask whether
  a claim is TRUE. A note saying "X is all that remains" was true when written and says nothing about
  now, so checking it at source confirms the wrong thing. **Check the TREE.**
- **A fence derived from files alone misses a gate that couples two surfaces** (see *Fences*) — ask what
  each nominated gate COMPARES, here, not at dispatch.

---

## The three sentences that do the most work

The first two go in **every** editing brief. The third goes in every brief whose nominated gate affords
a safe, bounded, discriminating plant — a question about the **gate**, not about the deliverable.

> **This brief's premises are CLAIMS, not findings.** Check each at source before
> acting on it. A verified "already fixed" or "the premise does not hold" is a
> complete and good deliverable — report it with the evidence rather than going
> looking for work to do.

> **Read the item before this brief, and order it by the tracker's own timestamps —
> not by position, and not by dates written in the prose.** Where they disagree, the
> item governs — follow it, and say in your report what differed.

> **The control is the deliverable.** Show it red when the property is removed,
> restore, and verify the restore by hashing the bytes.

- **The first is the highest-yield sentence in the preamble.** Read-the-item-first caught five stale
  briefs in one day: a fix that had landed two days earlier under a sibling item; the wrong audit
  finding named; a resolution already merged; a scope correction that had redefined the item; and three
  wrong path, flag and script details. **Every one of those briefs was accurate when written** — the
  yield is in *the item governs the brief*, not in the direction of reading.
- **The third is scoped by the GATE's capabilities, not by prose versus code.** A documentation
  correction is emphatically included: the cheap validators over a docs tree red on one broken link
  target or bad heading anchor and go green on restore. Where the covering gate affords no such plant,
  the sentence is an unsatisfiable quantifier — and an unsatisfiable rule does not stop the reader, it
  makes one up. **Measured on the enforcing side**: four editing briefs went out on one tick carrying
  the first two sentences verbatim and none carrying the third, each improvising the same omission
  separately — the tell that the rule was doing no work because it was **unscoped**.
- **Say what a brief whose gate affords no plant does instead, or that gets improvised too.** Two
  different cases, both settled below: no automated gate covers the surface at all (*which gate*); a
  gate covers it but affords no safe plant (*how a gate is run*). Naming the alternative is what keeps
  the scoping from reading as permission to prove nothing.

---

## Name the discriminator

Any task shaped "find every X and fix it" fails without one, because **the phrase is usually correct
somewhere**. Briefed without a discriminator, a worker changes all of them or none.

The discriminator is the question separating a real hit from a legitimate one. Two that recur: *does
this line claim something about code that runs, or record an open question?* — a design register
deliberately carries proposed names that do not resolve. And *does this sentence describe the state
now, or record what was true then?* — a past-tense record is not stale. Without one, a worker sweeping
a register will "fix" it into a lie.

**"Bounded repair" bounds the change, not the search.** Grep the exact wording across tracked files,
read every hit, settle each in the same change, and list in the change body what was changed and what
was left standing because it was already right. One project corrected a single sentence four times
across three changes; every repair was bounded and correct, and every one was found by the *next*
merged-change audit rather than by the repair before it.

---

## Counts, lists and leans are all claims

- **A count asserted when an item was filed is the first thing to drift, and a symbol-shaped check will
  not catch it** — the symbol still resolves while the count is zero, or triple. Require the census
  **re-run at the current tip**, reported against the item's number. Measured drifts in one session: 8
  to 9; "four hits" to 23 lines; "roughly 6–10" to 23; "4 of 21 cached" to 13 of 21. **Where the number
  will keep moving, brief the fix to name the CLASS rather than the count.**
- **An item's LIST OF SITES is worse, because it drifts in MEMBERSHIP rather than magnitude.** It looks
  equally authoritative either way and is wrong in two directions at once: an entry somebody already
  fixed sends a worker to edit correct text — or to revert a repair — while a site the list never had
  stays live. **Require it RE-DERIVED at tip and the DELTA reported.** The delta is a deliverable, not
  bookkeeping: it is the only signal the item was stale.
- **A lean is a claim too, and it fails worse**, because the worker reads it as guidance rather than as
  a premise, so the premises-are-claims sentence never reaches it. Attach the measurement in the same
  breath: *"I think A beats B, and I want the cost that decides it, not a preference."* One dispatch had
  every part of its author's lean wrong and the number found it — the brief guessed speed was the risk,
  the measurement killed that argument, and the final margin came out around 380x the other way for the
  same catch. **Say plainly that the item outranks the brief on scope**: that brief's fallback
  prescribed scoping a rule per page, which flags 21 sites on a corpus that is correct, where the item's
  own shape — per section — measures 0.
- **A number the brief asserts is a lean by the same mechanism.** Before writing a predecessor's figure
  into a brief, ask what produced it. One such figure — a bound a measurement window had derived from
  its own most-negative reading — went into the next brief as guidance and reached the operator before
  two independent refutations caught it. One question would have caught it first: *what null arm
  produced that bound?*

---

## Fences

State what the worker owns, then what it may not touch **and who holds it**. Naming the holder tells the
worker whether a conflict means "rebase" or "stop and report".

**A fence is a claim about FILES**, so derive it from what each open change actually touches rather than
from its name. **It is derived, never remembered — and the worker derives it again at start-up**, so it
travels as a claim the worker re-checks and its own result wins.

- **That re-check is the load-bearing half, because the two staleness directions cost differently.** Too
  BROAD costs one re-derivation. Too NARROW omits a worker dispatched after you last looked, and two
  workers on one surface merge-conflict and can silently revert each other — accuracy alone cannot reach
  that, because it expires between writing the brief and reading it.
- **Check whether your change-listing tool PAGINATES.** A hosted API commonly answers with one page and
  no marker that it stopped: measured, a change of 722 files returned exactly 100, and not one of the
  files a pending fence turned on was among them. **A round number is the tell** — treat 100, 250 or
  1000 as a page size until proved otherwise, and for a large change diff the trunk against the branch.
- **A listing built from committed history is blind to work already done but not committed** — the state
  a freshly dispatched worker stays in until its first commit. Read a change clean by both as *not
  started yet*, never as owning nothing.
- **An item's own scope claim is a name too, wearing a reviewer's authority.** A review that files a
  dozen items calls them *src-only* or *parallel-safe* at the altitude it worked at, and every collision
  such a wave produces is invisible from there: four items editing one file in four regions; a
  *test-only* cut that keeps a file a sibling was told would be deleted; a *src-only* lane that must
  cross into a held tree by one line. Measured: ten items dispatched under that claim, three same-file
  collisions, every one caught by routing after the fact and none by the fence. **A fence built from the
  claim is the guess the claim's author skipped, not a fence.**
- **Hot-zone files take one toucher at a time**, and where a remedy needs one the instruction is **stop
  and report**, not edit. **A citation is not permission**: briefs routinely cite a specification section
  as context, and a worker can read that as licence to edit it.
- **A GENERATED file is the one same-file overlap sequencing does not fix.** Two regenerations conflict
  textually and agree semantically, so serialising them serialises the fleet for nothing. Brief every
  toucher to regenerate after rebasing, never hand-merge, and run the job that diffs committed against
  fresh.
- **N changes each touching ONE LINE of a shared index are the second exception.** Brief every worker to
  isolate that edit as its own final commit, merge in landing order, let each later change rebase — the
  conflict, when it comes, is one line in one commit. Write the lane rule on every item sharing the file,
  in the same words, so no worker discovers it at rebase time.
- **But a gate that reconciles two surfaces couples them into ONE change, and sequencing those
  deadlocks.** Some checkers hold one surface against another in both directions — a catalogue against
  the emitters it lists, a schema against its fixtures — and neither half is green alone: the change
  deleting the emitters reds until the rows go, and the change striking the rows reds until the emitters
  go. **Nothing in a file list shows it.** Ask what each nominated gate COMPARES, and brief both halves
  to one worker, or route the second into the live change. Where a one-toucher file is held only for a
  region an item does not need yet, the fence can sit INSIDE the brief: do the unheld part first, take
  the held file only on a trunk carrying the holder's merge, and stop and report if you get there first.
- **The unit is the block, not the file.** Two items dispatched into the same paragraph because they
  were nominally different concerns conflicted; the second could not merge, and by the time its worker
  was resumed the hygiene loop had reaped its worktree — two costs from one scheduling error.
- **Complementary work is not duplicate work.** When two items attack one defect from different sides,
  say so in both, or one gets closed as a duplicate of the other.

---

## Choosing solo or cluster

Pick by **kind first, then priority, then size**. Do not reflexively dispatch one-worker-per-item, and
do not reflexively bundle everything.

* **Highest priority → always SOLO**, so it merges on its own green and is never blocked by a sibling.
* **Second tier → SOLO by default.** Cluster only when genuinely small, same-surface and low-risk.
* **Many small low-priority same-surface items → CLUSTER.** The primary clustering case.
* **Any LARGE item → SOLO**, whatever its priority. Bundles of four or more time out routinely where
  single-item workers succeed.
* **A measurement window → SOLO, Shape 6**, by kind rather than size.
* **One item too large for one worker → SPLIT by disjoint file, one worker each, and NOBODY CLAIMS IT.**
  A claim marks the item one worker's, and the ready list, the stranded sweep and every other slice's
  worker read it that way; so each worker appends a claim note and a result note instead, and the mayor
  closes the item when the last slice lands, citing every change.

**Three of those rules consume a SIZE, and the size you are holding is usually a TITLE.** Sizing happens
at the listing, and a listing prints names. A name is not evidence about files; magnitude is the third
thing it gets wrong, and the one no fence catches, because it corrupts the decision before any fence is
derived. Measured: an item whose title named one test that could pass vacuously — the archetypal small
cluster candidate — carried a body that deleted four public tools, removed a subsystem, and ran to seven
acceptance criteria with explicit non-goals.

**So open every candidate before you cluster it, and read for SHAPE rather than content** — acceptance
criteria, non-goals, the length of the evidence, whether the body names a few files or a subsystem. None
of that requires understanding the work, which is why it is cheap. The asymmetry settles it: a misread
item does not merely arrive oversized, it poisons the change its siblings ride in. **And where a title
and its body disagree, correct it on the item**, since the listing is what the next reader sees.

**One agent owns a surface; surfaces run in parallel.** The serial exceptions are a deliberately serial
epic and a tightly-coupled module whose core files many items touch — sequence those, later changes
rebasing on earlier merges, never resolving a conflict by taking one side wholesale.

---

## Dispatch shapes

**Shape 1 — Solo.** One item, one change. Item id and verbatim title; two to four paragraphs of context
with `file:line` citations; numbered concrete steps; a dedicated worktree and branch; the boundary
block; claim the item — unless it is a slice of a split item; gates with exact commands; push and open
the change **as a draft** with a `## Quality gates` section; report the change URL, a per-step summary
and test deltas. *A coverage or rigour pass must add at least one adversarial or negative case per
surface* — assertion-count growth alone only exercises the happy path.

**Shape 2 — Cluster.** Several small same-surface items, one change. Order commits smallest-cleanup to
biggest-correctness-fix, so a failing item cannot strand the small wins. Claim each item immediately
before its commit, so history mirrors tracker state and a stalled cluster leaves a clean partial trail.
Gates after each commit, full regression after all. Keep to three to six items; beyond that run
successive cluster changes, each opening with what it finished and listing the remainder, never a
half-item uncommitted. Disjoint-surface "small-misc" clusters are valid at the tail of a drain — the
binding rule is hot-zone parallelism, not strict same-surface.

**Shape 3 — Audit (read-only).** A finding, not a fix. Goal, surface paths, and prior findings to avoid
re-discovering; the boundary block; write the findings document to the version-ignored tree FIRST, never
commit it, never link it from committed files; file follow-on items one at a time, appending each id to
the audit item's notes so progress survives a timeout; close with a verdict, severity counts and
cross-references; no change by default.

**An audit brief needs the stance more than an implementation brief does.** The audit is *producing*
findings, and a project that rejects findings as often as it actions them will reject the weak ones at
the cost of a dispatch each. Tell it plainly: eight sourced findings beat thirty plausible ones, and a
finding whose remedy the stance excludes is worse than none. Two or three worked examples beat asking
for "a careful review". The mayor may reorganise or reject findings.

**Shape 4 — Cluster reviewer (read-only, no dispatch).** Shapes the next wave. List in-flight workers
and their surfaces first and do not recommend touching those; enumerate recently filed items and the
ready queue; per item decide add-to-cluster, new cluster (three or more on a shared non-hot-zone
surface), solo (correctness, large, decision-resolved or cross-cutting), or defer. Output the net
next-dispatch shape in two or three sentences. **Do not change tracker state.**

**When items arrive faster than the mayor can read them, ask this shape for the brief inputs too**, per
item: the premise check at the current tip (each cited line holds, drifted to what, or does not hold;
every asserted count re-run), the surfaces it would edit and which are hot-zone, same-file collisions
against the live and queued sets, the gates by exact command with the heavyweight ones marked, and each
ambiguity a worker would otherwise improvise, with a recommendation. That is the part of a brief the
mayor cannot write without reading the tree, and the part that goes stale first.

**Shape 5 — Fix a failing check.** The failing check name and log lines; two or three root-cause
hypotheses; a worktree off the **existing** branch, not a new one; the boundary block; **run the ACTUAL
failing gate locally**, not a proxy that already passed; fix surgically, or file a follow-on if it is
deeper than scope and the stance allows a safe exit; push to the existing branch, never the trunk;
update its `## Quality gates` section. *Never override a failing touched-surface gate* — diagnosis often
beats the failure log, so test the hypothesis before fixing.

**Shape 6 — Measurement window.** The five shapes above tell a worker to iterate until the gate goes
green. A measurement worker must do the opposite, and that inversion is the whole shape: it is not there
to produce a number, it is there to find out whether a trustworthy number can be produced at all.

- **The controls are the arbiter, not the worker's judgement about the machine.** A worker asked whether
  its own machine was quiet enough will always find a reason it was.
- **A refusal is a deliverable, not a failure.** If everything refuses, the window succeeded: you now
  know the rig cannot see what you hoped. Say so in the brief, or the worker reads its refusal as its
  own failure and hunts for a way to turn it green.
- **Never "fix" a refusal by loosening a gate.** Every gate in a rig is there because something once
  passed that should not have; widening a threshold retro-admits that failure too.
- **Do not improve the rig mid-window.** A rung added between runs makes the series two instruments.
- **Do not restate a published figure on thin evidence.** Four runs disagreeing with a number are a
  reason to look again, not a mandate to move it.
- **Verify the machine with real counters, not the convenient one.** One system's headline CPU counter
  read 93% while the true value was 11%. Prefer the counter saying whether anything is actually *waiting
  for a core*, and **read it on its own, never inside a measured run** — beside anything heavy it
  measures the sampler, inside a run it measures the benchmark. Bracket the run, and say plainly that
  nothing is claimed about within-run quietness beyond the bracketing.
- **Only a CLOCK estimand needs the quiet machine.** A census of monotone counters reads the same on a
  loaded box, so it must not consume a drain. Classify the estimand before you schedule it; getting it
  wrong wastes an idle machine one way and produces a worthless-but-measured-looking number the other.
- **While a clock window runs, every OTHER dispatch carries a clause reserving the machine** — naming
  the heavyweight gates that worker may not run, **and** saying that *if the only gate covering your
  surface is a heavy one, stop and report* is a correct outcome. Without that second half the worker
  runs the gate anyway, to satisfy the surface-gate menu the same brief hands it.
- **One run at a time, never concurrent.** Concurrency is the contention the window exists to exclude.
- **Your published sentences are claims too, and what slips is the prose ONE LAYER ABOVE the number.** A
  worker that has just spent an hour being rigorous writes its summary in ordinary confident English,
  and ordinary confident English overclaims. Three measurement changes merged in one day, all exemplary
  on the run itself, all three overstating their published claim — one calling three values
  *run-medians* where the code stored the arithmetic MEAN of five per-round ratios, its whole stated
  basis; one saying a spread "loosely brackets" a figure sitting *above* the maximum of its own ratios.
  No audit overturned a measurement or a refusal; what they corrected is the sentence a future reader
  will quote. So name the evidence licensing each summarising statement, check the arithmetic of any
  comparison, name the estimator you actually computed, and do not attribute a spread to one cause
  unless your data excludes the others.
- **An impossible reading cannot bound the quantity.** Let an observed delta *y* be an unknown positive
  true cost *t* plus estimator error *e*. A reading of *y* = −0.0062 proves only that the negative error
  excursion exceeded 0.0062 + *t*: it neither makes ±0.0062 a calibrated symmetric floor nor
  upper-bounds *t*. **A most-negative observation is a statement about the ERROR TERM**, and comparing
  most-negative readings from *different* positive-cost arms at two window widths cannot establish a
  floor-scaling factor either. **Where a bound is inferred from estimator error, name the NULL OR
  CONTROL ARM that produced it**; otherwise report the term as UNRESOLVED.

Report what ran, what refused and on which control, the raw numbers, and — as its own heading — what was
**not** concluded. A window that publishes nothing still reports everything.

---

## Publishing the change

**Push continuously, and publish as a DRAFT.** Pushed commits are the only durable worker state, so the
change goes up as soon as commits exist, and a gate still running is declared in the `## Quality gates`
section as reliance on CI rather than waited out in silence. **Mark it ready for review as the last act
before reporting done.**

- **That one line is what stops push-as-you-go colliding with a merge loop.** The merge criterion tests
  the CHANGE and nothing in it can see whether the author has finished. Twice in one session a change
  was merged out from under a live worker: once delete-on-merge removed the branch and the worker's next
  push recreated it as a duplicate with a byte-identical diff; once a merge landed mid-gate and the
  remainder took a whole extra change and review cycle. **Neither merge was wrong on the criterion.**
- **Use the host's own draft flag rather than a rule to remember.** Merge commands refuse a draft, so
  the interlock is enforced where it cannot be forgotten. The rejected alternative — checking worker
  liveness before every merge — works but must be remembered forever, and re-introduces the
  worktree-activity sweep the merge loop deliberately does not do.
- **Stale-OPEN: a fix dispatch inherits a flag somebody else already set.** The original author finished
  and marked it ready, so the interlock is open before the second worker starts and nothing in the
  change records that anyone is inside it now. **The remedy belongs to the DISPATCHER** — a rule the
  protected party cannot execute is not a rule. Convert the change back to draft before the worker
  begins, and **confirm the state took**, since a host that ignores the request fails silently.
- **Stuck-CLOSED: an author appears to go silent holding a green draft — and the reading is usually
  wrong.** What gets read as a stopped worker is no writes, tip unmoved, worktree clean, everything
  pushed, a direct message unanswered for half an hour, and a draft at band with nothing failing.
  **Every signal in that list is also what a worker in its FINAL MINUTES looks like** — one last
  foreground gate, deleting gate logs, unlinking a shared-dependency link, tidying scratch — all outside
  the worktree and outside version control, and too busy to answer. **The four signals do not fail
  independently; they fail together, for one cause.**
- **Which makes the frozen reading AMBIGUOUS, and that is the whole finding.** It is compatible with a
  stopped worker and with healthy foreground work alike, so it identifies neither, and re-reading the
  same clocks cannot break the tie. Measured twice: five workers reported dormant, three completed alive
  with full reports, two never explained either way; the diagnosis was retracted in full, including a
  second claim that messaging was inoperative, which was *slow* converted into *broken*. **Name those
  outcomes rather than reducing them to a ratio**, which invites rounding the unexplained cases into
  whichever side you are arguing for.
- **Prefer the DIRECT signal where your tooling offers one** — a live progress line, a supervisor status
  — over any number of indirect clocks. With only clocks, declare a window and re-read rather than
  acting; the error is cheap in exactly one direction.
- **Do not answer any of it by flipping the flag on an inference.** Settle it by a read: the agent's own
  report, or the operator's decision to STOP that agent, which settles liveness by making it false.
  **Where the second is taken, it is taken FIRST** — merging then stopping is the same inference with
  the act appended afterwards, and both orders leave a merged change beside a stopped agent, so nothing
  in the record can later tell them apart.

---

## Pasting a block

Three blocks below travel **verbatim**: the **common preamble** into every dispatch, the **worktree
boundary block** and the **gate-mechanics block** into every editing one. Get them there by **extracting
mechanically, then pasting the result into the prompt.** Both halves are mandatory and close different
failures — the extraction makes paraphrase impossible, and the paste makes non-receipt impossible.

- **Sending the worker to the FILE is not a substitute.** A worker that skims it has not received the
  block, and nothing in the transcript distinguishes that from one that read every line — so the first
  evidence is a worker doing something the block forbids. Between a failure prevented by construction
  and one prevented by a reader's diligence, take construction.
- **No dispatch is small enough to earn a condensed block.** The temptation is strongest exactly where
  the block most dwarfs the task, and condensing there is the paraphrase the extraction exists to
  prevent, arriving dressed as proportionality. Measured: a mayor condensed the gate block for a
  two-file rename and the worker ended its turn waiting to be woken — the exact failure that block
  names, refutes and closes, in text the mayor had read and chose not to send. **And note WHICH part
  went missing**: a mayor condensing drops what looks least relevant to *this task*, and what stranded
  the worker was a mechanic belonging to the HARNESS. **A condensed block is not a shorter block; it is
  a block with the harness mechanics taken out of it.**
- **The pressure is structural, because the block only ever grows.** Every failure it closes adds lines,
  so the better it gets the more it dwarfs a small task — a ratchet turning against the one rule that
  holds it. The defence is that the extraction is MECHANICAL and costs the same on the smallest dispatch
  as the largest: **if you are weighing which parts this worker needs, you have stopped extracting and
  started paraphrasing.**
- **"Verbatim" forbids WEAKENING a block, not adding to it — and the paragraphs addressed to YOU are not
  part of what travels.** Where the payload is FENCED, the fence settles it. Two of the three here are
  fenced and their mayor-facing prose sits on opposite sides of the payload, so a habit about which end
  is wrong half the time. **Only the third, unfenced, opens by naming itself and saying where it stops**
  — paste that frame untouched and the worker receives instructions about pasting blocks into dispatches
  it will never make. Drop the frame; add whatever caution the lane needs. **The test is whether the
  block still refuses everything it refused before you touched it.**
- **Anchor the extraction to CONTENT, never to line numbers.** A line range against a living document is
  a measured constant that goes stale exactly where it has to be right.
- **But match the closing text as a HEADING, anchored to the start of a line**, because a block that
  documents its own boundaries contains its own end anchor: the gate block's CLOSING string occurs
  twice, once as the real heading and once inside its opening sentence. A plain search stops at the
  mention and returns the block's opening paragraph instead of the block, raising no error, because both
  anchors were found. **The opening heading is NOT doubled, and that asymmetry is the durable part.**
- **Check the extracted size — as a CLASS, not against a remembered number.** The two outcomes are
  orders of magnitude apart, so the reliable test is whether the extraction ENDS at the closing heading,
  which cannot drift. A count here would be [a measured constant about a living
  document](#counts-lists-and-leans-are-all-claims).

---

## The worktree boundary block

**The concept.** A worker edits only its assigned worktree, never the mayor checkout. The shell's working
directory is not enough: some edit tools resolve a relative path against the agent's session root rather
than the repository root, so a write can land in the mayor checkout *even after a start-of-session guard
passed* — the leak happens mid-session, in one tool call. The real backstop is the worker re-verifying
its repository root before every edit. New-file leaks are the worst case: a brand-new ignored file routed
into the mayor checkout shows nothing in the worker's own status.

Paste this verbatim into every editing dispatch. Adapt only the placeholders.

```text
WORKTREE BOUNDARY — MANDATORY
Your worktree:  <ASSIGNED_WORKTREE>
Mayor checkout: <MAYOR_CHECKOUT>   ← never edit this.

Before EVERY edit, confirm you are in your worktree: ask version control for the
top level of <ASSIGNED_WORKTREE> and check it prints <ASSIGNED_WORKTREE>.
Use ABSOLUTE paths under <ASSIGNED_WORKTREE> for every edit and write. A
start-of-session guard is NOT sufficient — verify per edit.

Report that root to the mayor in your completion message, and never write it, or
any absolute home path, into any committed file, PR body or tracker text — if
your deliverable is itself a written record, what makes the guard evidence is
that it RAN and exited 0, not the machine-specific path it printed.

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
And the link is WRITABLE through, not only deletable through: an installer a gate
runs rewrites the SHARED target, not your copy — one emptied the coordinator's
real tree while the worker's own read as a fresh install. If a nominated gate runs
an installer, do not link that tree: install your own from the lockfile, or STOP
and say the gate cannot run against a link.

And kill any poll loops you armed before you report done — each survivor fires its
own completion notification after the work has landed, costing a turn apiece.
```

A project may add a **mayor-side commit guard** — a pre-commit hook in the mayor checkout refusing
commits that touch worker-owned surfaces — so a bypassed edit-guard is caught from the other side.

---

## Common preamble

The tracker commands are placeholders like every other project-specific: substitute your own at dispatch
time. What travels verbatim is the *reasoning* — every trap below is a property of history walks in
general, not of one tool.

```text
You are implementing <ITEM_ID> in <project + one-line description>.

<PROJECT STANCE — paste the stance block VERBATIM from the one file that owns it.
Do NOT summarise it. The lenses say what good looks like; the clauses after them
say when to STOP, and a paraphrase keeps the first half and drops the second.>

READ THE ITEM BEFORE THE BRIEF — MANDATORY. The tracker prints the DESCRIPTION
first, and the description is the OLDEST text on the item: corrections, scope changes
and sibling landings accrete BELOW it as notes, so a straight top-down read gets
superseded instructions.

BUT ORDER IT BY THE TRACKER'S TIMESTAMPS, NOT BY POSITION AND NOT BY DATES IN THE
PROSE. The bottom of the output is NOT reliably the newest text — audit notes often
append into the DESCRIPTION block while dated comments render after it, so the last
lines on screen can be older than material higher up. Neither a tail of the rendered
item nor a slice of its notes is a read-the-newest method. Nor is grepping for dates:
a date in the prose is CONTENT, not a mutation time, so an undated scope correction is
invisible to it and an edited description can be the newest field on the item. Use the
tracker's own history listing, which orders real mutation times newest-first, and its
comment timestamps.

THE PLAIN LISTING TELLS YOU A NEWER MUTATION EXISTS, NOT WHAT IT SAYS — it names no
changed field, so for the change itself use the structured form that carries a full
snapshot per revision. THAT SNAPSHOT USUALLY NESTS THE ITEM'S FIELDS UNDER A SUB-KEY,
the top level carrying only revision metadata, so a walk keyed at the top level matches
nothing in every snapshot and reports "no change" without erroring.

AND AN EMPTY OR NULL HISTORY IS A STATEMENT ABOUT THE ID, NOT ABOUT THE ITEM. The
structured form answers null for an id that does not exist, which is indistinguishable
from "this item has no history" unless you check — and the commonest cause is an id
that picked up a trailing carriage return on its way through a pipeline. A worker that
reads null as "the tool does not work here" abandons the newest-first walk and reads
the fields directly, which is the one thing this whole passage exists to prevent.
Re-check the id against a known-good one before concluding anything about the tool.

WALK ADJACENT PAIRS NEWEST-FIRST UNTIL THE FIRST CHANGE TO A TEXT-BEARING FIELD
(description, notes, acceptance criteria, design — and note that a field's UPDATE FLAG
is often spelled differently from the field itself). Histories hold duplicate checkpoint
snapshots and status-only mutations, so the newest pair alone can truthfully say nothing
changed while a live instruction change sits behind it. AND A WALK THAT ENDS WITHOUT
FINDING ONE HAS FOUND NOTHING, NOT "NO CHANGE" — say which of the two you have. Histories
accumulate machine-written no-ops, so a walk bounded anywhere short of the item's
beginning reports "no text-bearing change" on an item that has several, and that reads
as the revert signature whose remedy is to re-close. On a long history, search the text
for the marker you expect rather than walking to it. If the item has children,
re-enumerate them too: a ruling is sometimes recorded as a NEW CHILD ITEM rather than as
a note.

AND THE SNAPSHOT OMITS EMPTY FIELDS, so a key's ABSENCE means "empty on this item", NOT
"not tracked". The key set is per-item and reflects exactly what that item populates, so
there is no schema to read off one item's snapshot — do not derive one. Treat absent and
null as the same thing, and never read "no change" off a field the item never populated:
null hashes constant across every adjacent pair, so an empty field reports a clean walk
in the very same words as a genuinely unchanged one. That makes the walk INTERMITTENTLY
wrong rather than reliably wrong — the identical expression works perfectly on an item
that DOES populate the field — which is why it survives casual use. Notes is the field
that matters most, because rulings, sequencing fences and audit reopenings land there.

SO CONTROL THE WALK AGAINST A FIELD YOU KNOW VARIES ON THE ITEM IN FRONT OF YOU before
you believe any "no change"; a control on a different item proves nothing about this one.

TIMESTAMP ORDER DOES NOT ESTABLISH CURRENCY, so a perfect walk can still hand you
superseded text: the newest note by mutation time can be a faithful re-derivation of a
list an OLDER note already ruled stale. Where a note QUOTES or SUMMARISES another rather
than citing a check it made itself, look for an intervening note that overtook it —
prefer "verified at source at <tip>" over a citation of somebody's own earlier note. AND
THE CHEAPEST GUARD IS THE TREE, NOT THE ITEM: before you brief or build X, check whether
X ALREADY EXISTS AT TIP.

Where the item and this brief disagree, the ITEM governs — follow it, and say in your
report what differed.

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

**A pass that concludes only in the ignored tree concludes where nobody can read it.** Every project
keeps some scratch tree version control cannot see, and that invisibility is what makes the failure
silent: an item can cite a design by a path no maintainer has. Two audits were lost exactly that way, and
a mayor re-ran an entire three-design programme in one day for want of looking there first — which is why
the preamble tells the worker to look there first. Widening what version control tracks is not the fix;
only the *conclusion* is promoted, into whichever tracked record already owns the surface.

---

## Quality gates — which gate

Every editing dispatch runs gates before opening a change and lists what ran in a change-body section
headed **exactly** `## Quality gates`, with pass and fail counts. The readers are agents, not machinery,
so the exact heading is a reader's convenience. **A heading that does not conform is a note to the worker
and nothing more** — never grounds to edit somebody else's change body, and never a merge blocker.

- **Nominate the NARROWEST gate that covers the diff — gate time is the dominant cost of a dispatch.**
  Measured: CI ran a whole required matrix in 22 minutes across parallel runners while that project's own
  pre-checkin script — sequential, one core — took 62 minutes for a *subset*. The merge criterion reads
  CI, never the local run, so a local gate duplicating CI buys wall-clock rather than information.
- **So do not brief a re-run after a rebase, and do not brief red-to-green iteration on a heavyweight
  gate** — push, and let CI's automatic re-run be the evidence about the new base. Local iteration is
  right only for the focused failing test the worker is writing. **The re-run is easy to miss because
  each one is individually justified**: a rebase really does invalidate the previous green.
- **Gate the transitive surface, not just the file you changed.** A public-surface change breaks its
  *consumers*, not itself.
- **Local-green is not CI.** "Green locally" usually means the subset the worker ran; the red gate is one
  it skipped. **Say WHICH required checks the local gate omits by citing one PATH, not by re-listing
  them**, and make the project *derive* that list. Two shapes make a hand-written one wrong inside a
  week: a required status context the local runner has no lane in at all, and a required check that is a
  **step inside a job the runner does run**, which no skipped-tier enumeration can see and which reports
  under that job's name however little it resembles what the step does.
- **Never nominate a gate that does not cover the surface being edited.** Site builds, linters and test
  runners all carry exclusion lists, and a gate run over an excluded path exits green having verified
  nothing — the same fail-open defect, except it is now the brief telling the worker to trust it.
  **Read the exclusion config, and its own carve-outs**, because an exclusion may name exceptions.
  **Understating coverage misreports as surely as overstating it**, sending a worker to hand-check what
  the gate already proved. Where a surface genuinely has no automated coverage, the honest brief says so
  and the change body reports what was checked by hand, with counts.
- **Read what each nominated gate RUNS before telling the worker to link shared dependencies.** A gate
  that reinstalls dependencies as a step rewrites the shared tree rather than the worker's copy —
  measured: a conformance runner's install emptied the coordinator's real tree through the link the brief
  had told the worker to make.

A skipped gate needs a one-line reason in the change body. A silent skip fails review.

---

## Quality gates — how a gate is run

**This section is the gate-mechanics block.** Paste it verbatim into every editing dispatch, adapting
only the placeholders, from this heading to the rule before `## Reviewing what comes back`. **`## Quality
gates — which gate` is not it and does not stand in for it** — that section is addressed to you as you
nominate the gate, so pasting it instead hands the worker your reasoning and withholds every mechanic it
needs to run anything.

The menu settles *which* gate runs; this settles *how*. The wedge paragraph assumes a gate heavy enough
that two cannot coexist, and the planted-fault paragraphs a gate in which a bounded, discriminating fault
can be planted at a line the worker is already editing — a cheap link validator is *plantable* without
being *heavyweight*. Everything else applies to any dispatch that runs anything.

### Running it

- **Split a compound gate before you reach for detaching.** A script chaining two phases often splits
  into two runs that each fit inside the harness ceiling, which keeps every verdict in the foreground and
  removes the strand risk rather than managing it.
- **Where it genuinely does not split, detaching is correct — ENDING THE TURN is the defect.** Detach,
  then poll both the log and the exit-code file in a bounded loop *within the same turn*: the log shows
  progress, the exit file carries the verdict and appears only once the run is over. This is the
  sanctioned path, not a concession; a worker who thinks it has erred reads for how to atone and straight
  past the instruction that would save it.
- **State it as a TERMINAL CONDITION and name the CLASS: the turn ends only once the exit file exists
  and its number is quoted.** Stated as a forbidden wait it fails open on the first wait nobody
  enumerated — workers have stranded on a monitor, a watch, a notification subscription and a background
  task expected to wake them, each a fresh name for the same wait and each read as outside a rule that
  named the others. You are the only thing that can read the exit file, and you can only read it while
  your turn is still running.
- **Refute the belief that keeps re-arming it, because the rule alone has not held.** Your tooling likely
  documents that a backgrounded command "notifies you when it completes" — true of a LIVE session, and
  the reason a worker reads its wait as sanctioned: it is trusting its harness over this block. That
  promise does not survive the end of your turn. Every stranded worker was recovered intact the moment
  somebody asked it for a status.
- **A gate heavy enough that two cannot coexist WEDGES rather than fails** — no progress, no exit file,
  no error, from a run healthy a minute ago. This is contention for the MACHINE, so per-attempt naming
  does nothing for it. Correlate your own build artefact's modification time against the candidate runs'
  start times and **kill only the one you can show is yours**; a peer's recovers once memory frees.
- **Read the clock before killing anything on elapsed time** — ask the system, never infer it from how
  much has happened. One worker killed a healthy run believing seventeen minutes had passed when it had
  been two; the loop watching from outside reads a worker that has just rebased as stalled.
- **Invoke a backgrounded gate by its ABSOLUTE path**, for two reasons: a script deriving its repository
  root from its own invocation path gets a relative one back, and an interpreter handed a relative path
  resolves it against the working directory, so a wrong directory hands it a *sibling worktree's copy*
  which then pins faithfully to the sibling's root. **A complete, internally consistent run about
  somebody else's work is far harder to catch than a broken one.**

### Believing it

- **Verify which tree the gate actually read.** Where it prints its root, check that line names your
  worktree — that check, not any naming rule, is what has caught the observed collisions. Where it prints
  nothing, the proof is **the red from a planted fault** at a line you are already editing. Do not expect
  the reported path to name your worktree; a repository-relative path cannot tell two checkouts apart.
  **The discrimination is the red itself**: the fault exists only in your tree. Where a gate affords
  neither route, say so in the brief and name whatever bounded mechanism it does afford — nominating a
  route the gate cannot supply makes workers improvise.
- **A green sabotage run is a reason to stop, not to proceed.**
- **Never pipe a gate through a filter.** A pipeline's exit status is its *last* command's, so a red
  runner reads green. Redirect to a log, echo the runner's own exit code, and quote that number — with
  the redirect and the echo on **one command line, in one shell**, because a separate invocation starts a
  fresh shell whose status is whatever that shell last did, typically the directory change.
- **Quote the number you captured, never one the harness reports about the same run.** That is a
  different measurement and it disagrees routinely — dozens of times across one fleet, over a compile
  failure, a two-assertion failure and a browser run standing over three real ones. **Every disagreement
  surfaced as exit 0**, and more than half were sabotaged runs where believing the reported zero would
  have inverted the conclusion.
- **A captured exit code is honest about its own phase and SILENT ABOUT THE ONE BEFORE IT.** Where a gate
  chains compile to serve, a failed compile leaves the previous artefact in place and the serve step
  serves it — so the run executes against stale code, passes, and returns its own truthful zero. Capture
  discipline cannot reach this. **Gate the second phase on the first's captured exit.** Counts do not
  catch it either: a stale artefact ran 885 tests and read entirely plausibly, and **a count that does
  NOT move where you expected is a question rather than an answer.**
- **An ABSENT exit-code file is NO VERDICT, not a pass** — it reads as success because the log ends with
  the gate's own output and nothing contradicts it. **A gate you cannot quote a captured number for has
  not run.**

### Searching, and any instrument that can say "nothing here"

- **A search that returns ZERO is not a check that passed.** Match fixed strings as fixed strings, and
  when a search underwrites a claim, run it once against something it should find.
- **Make that control share the SHAPE of the target**, because a pattern fails on a FEATURE and a control
  lacking the feature passes without exercising it. Measured: a word-boundary anchor placed after a `$`
  matched a letter-final control and missed every punctuation-final hit, so the census read zero on four
  real sites while its control read green. If the target ends in punctuation, carries a backslash, spans
  a line or sits behind a comment marker, the control must carry **every** one of those at once.
- **For the LINE-SPANNING case that advice is inert, and it is the one case where a matched control
  actively reassures you.** Most search tools take a line as their unit, so a target broken across two
  lines cannot be seen at all — and a control sharing that shape is invisible identically. **Run the
  search three times: line-oriented, over the text with whitespace collapsed, and with comment markers
  stripped before collapsing.** Neither of the first two is a superset of the other, and the third reaches
  what neither can — a phrase broken across lines whose continuation carries a comment marker survives a
  plain flatten. **Wrapped text is the common case, not the exotic one.**
- **Separate CODE from COMMENT before you count**, or a census reports prose hits in the reassuring
  direction for a retirement.
- **This is not a fact about searching.** ANY instrument that can answer "nothing here" gives the same
  answer when misused, and a misused one raises no error, so its all-clear is complete and plausible — a
  surface classifier handed revisions where it expects file paths reported every surface as unaffected,
  which is exactly what a genuinely unaffected change looks like.

### Artefacts

- **Name every gate artefact for your worktree AND for the attempt**, log and exit file both, in a
  version-ignored directory. A name missing either half fails the gate **open**, and the two halves close
  different holes: the scratch path is usually keyed to the session, so peers share one directory — two
  workers in one wave wrote the same exit-code filename and one read a zero a peer had left. **The
  worktree suffix cannot close the second mechanism, because there both writers are you**: a runtime cap
  kills the *shell*, what it spawned survives holding the same descriptors, and it writes at its offset
  while the restart writes from zero, so the artefact is SPLICED rather than clobbered — measured as an
  `exit 0` beside 18 real failures, and an orphan reporting two failures from a run already killed.
- **Clean them up: one leftover is enough to make a worktree unreapable.** **Delete them by their LITERAL
  path, never through a variable** — a deletion whose target a static permission check cannot evaluate is
  one it has to stop and ask about, and the ask lands on an operator who may not be watching, so the
  cleanup stalls mid-turn. An unset variable also turns `"$DIR"/*name*` into a glob rooted at the top of
  the filesystem.

### Planting a fault

- **Verify a restore by hashing the bytes, never by reading a diff.** A rewrite that flips line endings
  reads clean having changed every line — and **a patch that never applied reads clean too**, because
  "unchanged" and "not attempted" are the same diff. That second is the dangerous half: a plant that
  silently no-ops makes the sabotage run come back green, so the worker reports a guard that fired when
  nothing was ever broken.
- **Commit your own edits before you plant anything**, because asking version control for the file back
  returns it to the last COMMITTED state, not your pre-plant working state — so planting from a dirty
  tree silently discards the work the gate was being run against.
- **Use version control's own content hash against the committed object**, not a byte digest of the
  working file, and **treat a match by EITHER the default form or the no-filters form as a good restore**
  — which form reproduces a committed blob is a per-file fact, and a false "restore failed" sends the
  worker off to doubt the sabotage result, which was the deliverable.
- **Call it a *blob* hash in the words immediately before the token**, or a provenance checker
  classifying hex tokens by the nearest description on their left will file it as a citation of a commit
  that exists nowhere.
- **Anchor a patch to a single line** — a multi-line anchor can match nothing, with no error and no edit.
- **An anchor at the END of a line matches nothing where line endings are translated**, so the
  single-line rule does not cover it. **Read the match count before you run the gate**: zero is
  unambiguous and free, where the hash convicts a no-op plant only after a run has been spent. The
  endings can change under you *between* plants, so read the count per plant.
- **A hash proves the SOURCE changed, not that the runtime ever saw it.** One plant applied genuinely and
  the file was not in the build's module graph, so the watcher served the pre-plant compile and the
  witness came back green. A green sabotage run is evidence only once both halves hold.
- **Scope a plant to the suite under test.** Where the runner executes a whole lane in one block without
  catching exceptions, a plant that crashes any namespace stops every namespace after it and the log
  still looks plausible. **Compare namespace and assertion counts against a control run.**

### Gates nobody nominated

**The gates the brief nominated are not the whole obligation, because a project's RATCHET gates grade the
PATHS you touched rather than the change you made.** A ratchet permits a recorded count and refuses any
increase — a per-surface floor a surface with baselined debt already sits exactly on, or a
retired-vocabulary scan whose permitted count is zero — so an ordinary new file, or one word in a
comment, is a regression, and no brief nominated the gate because the change looked nothing like its
subject. Before you open the change, find the ratchets covering your paths, run them, and treat each
gate's own output as the authority: one that fails prints the exact spelling it wanted. **Do not ask for
a list of them, and do not write one** — the set grows, and an enumeration is a count.

- **These gates grade more than the lines you think you changed.** Some read the whole tracked file,
  comments and docstrings included, so a sentence naming the design you REJECTED fails while the code it
  describes passes; others grade a form nobody counts as part of the change, such as an import edge.
- **A ratchet is repaired by changing your own lines, never by moving its floor.** Raising a recorded
  count to admit new debt inverts the gate, and walking that debt down is another item's job. Read the
  gate's WHOLE output before you fix — it names every surface over its floor.

### Before you push

- **Re-run the gates on the final base after every rebase.** A pre-rebase green is evidence about a tree
  that no longer exists, and nothing warns you: the rebase reports success and the old log still says
  exit 0. One worker rebased four times past eleven landings, and re-running changed the artefact rather
  than reconfirming it — fixes for three of its own findings had merged in the interval.
- **Diff your branch against its MERGE BASE and read that diff for what you would REVERT.** Not for
  conflicts — a clean rebase is exactly the case this rule is for. The question is whether your push
  undoes something a sibling landed while you worked, which no gate covers: a revert of a merged change
  is well-formed, compiles and passes every check the tree has, and in a shared document a stale copy of
  one region reapplies as a silent deletion of everything that landed in it since. **The base is half the
  instruction** — compare against the point your branch left the trunk, never the trunk's current tip,
  which buries the one hunk that matters and degrades worst exactly when the trunk has moved most. In the
  dominant toolchain that is the three-dot form, `<TRUNK>...HEAD`.

---

## Reviewing what comes back

* Did it check the premises, or accept them?
* Is there a control, and does it fail for the right reason? **Ask specifically whether the control could
  catch its own case.**
* Which numbers are captured, and which are reported by the harness?
* What did it decline to do, and why? A report with no refusals, on a task that had a plausible one, is
  worth a second look.
* **Where it CONTRADICTS the brief, that is evidence about the brief.** Verify the claim at source, and
  if it holds, the brief was wrong. Measured across one session: seven briefs carried a defect — a hold
  the item recorded and the brief did not, work the item had already split, a sequencing precondition the
  item had discharged and the brief re-fenced, and a ruling made from a page's confident prose without
  checking the spec that owned it. **A worker surfaced every one. The coordinator caught none.** The
  clause that makes this work is the preamble's *these premises are claims*, which reads like a guard for
  the WORKER and functions as error-correction for the COORDINATOR — so never trim it, and never treat
  the pushback it produces as friction.
* **Are you about to quote a figure or phrase OUT of this report and into the next brief?** Then it is a
  claim like any other. A sequence of measurements and a correction to one measurement read identically
  once compressed to a line: one mayor turned four per-window figures into "the number moved" and briefed
  a worker to fix a figure that was correct. **A figure and the subject it was measured on arrive in the
  same sentence, so the compression can pair them WRONGLY** — and re-deriving at the current tip cannot
  catch that, because the census is correct about the subject you wrote. **Trace a refuted figure back to
  what it WAS true of before you drop it**, because that thing is an unfiled finding.
* What did it find that you did not ask for? File those **with the owning item named in the new item**,
  so a later audit reopening that owner is recognisable as the same finding rather than a second one.

---

## Failure modes these shapes close

- Back-compatibility shims by default → the stance is explicit in every preamble.
- Same-file races between concurrent workers → in-flight surfaces enumerated.
- Two halves of one gate-coupled invariant fenced to two items, each red until the other merges → ask
  what each gate compares; one worker takes both halves.
- Edits leaking into the mayor checkout, especially silent new-file leaks → the boundary block plus a
  post-write check of both trees.
- Cross-worktree contamination via stashes → the no-stash rule.
- A peer overwriting a worker's scratch file, or an orphaned child of a killed gate writing over the run
  that replaced it — either way a worker reads the survivor as its own, including a gate exit code from a
  different run, which merges on a green nobody earned → artefacts named for both the worktree and the
  attempt, plus the gate-root check, which is the half observed to actually catch it.
- Worktree cleanup deleting *through* a link into the shared tree → the worker unlinks before reporting
  done, and the cleanup path disarms before removing.
- A change merged out from under a live worker → published as a draft, marked ready only as the author's
  last act, which the host enforces by refusing to merge a draft. **Except on a fix dispatch, where the
  flag was already set by somebody else** — and the same flag strands a finished change when its author
  goes silent.
- "Green locally" merged into a red CI gate → gate the transitive surface; merge on CI, not on the
  hand-off; a real failure gets a fix worker, never an override.
- A passing synthetic test that routes around the real bug → reproduce the actual failing path.
- Clusters split that should be one change, or the reverse → the cluster reviewer pre-validates shape.
- Stalled workers losing analysis → findings first, and one-item-at-a-time tracker creates.
- A brief accurate when written but stale when read → the item governs the brief.
- Generic prompts → require `file:line` citations and concrete fix sketches.
- A measurement worker iterating until the number looked right → Shape 6: the controls arbitrate, a
  refusal is a deliverable, and the rig does not change mid-window.

---

*Record three or four exemplary dispatches per project — a solo, a cluster, an audit, a fix. A few good
examples teach a new mayor more than thirty mediocre ones.*

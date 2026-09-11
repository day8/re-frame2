# Worker dispatch

Copy-adaptable shapes for delegating bounded work to a background worker. Assumes
a capable agent. Placeholders:

- `<MAYOR_CHECKOUT>` — the mayor's primary checkout (absolute path)
- `<WORKTREE_PARENT>` — the directory holding worker worktrees (derive it from your
  version-control tool at dispatch time; never hardcode a path)
- `<ASSIGNED_WORKTREE>` — this worker's worktree, a subdirectory of `<WORKTREE_PARENT>`
- `<TRUNK>` — the branch a worker's change merges into
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
> incomplete — it reads as a stance that wants MORE of everything, which is precisely
> the failure the second half exists to prevent.

---

## Write the brief in this order

The order is what makes it accurate. Each step is a check the next depends on.

**Keep the brief to what is specific to THIS item.** The blocks below travel verbatim into every
dispatch and already carry the standing rules, so restating them is the commonest way a brief doubles
in length while adding nothing — and the worker pays for every word twice, once reading and once
obeying. Measured on one fleet: two-thousand-word briefs sitting on top of a five-thousand-word block
file, against workers that finished comparable items in a third of the time on briefs of four hundred.

1. **Read the tracker item, and order it by the tracker's own TIMESTAMPS** — not by position, and not
    by dates written in the prose. The mechanics are spelled out for the worker under *Common
    preamble* below, and every one of them binds the mayor writing the brief exactly as it binds the
    worker reading it.

    **One cheap query tells you which items will punish skipping that walk: has this item ever been
    CLOSED and reopened?** The rule above is unconditional, which is why it gets skipped — an
    unconditional instruction competes with every other unconditional instruction. A closed-to-open
    transition is the strongest cheap signal that the description is no longer the live instruction:
    it was written about the defect as originally found, and something later disagreed enough to
    reopen. Nothing in the rendered item says so — the description still reads as a current bug
    report, because it once was one. Measured: three briefs in one session written from descriptions
    whose work had already merged, **all three showing a closed-to-open transition while the control,
    never closed, showed none**.

    **It is a signal to do the walk, not a substitute for it, and both over-readings are natural.**
    *Closed* does not mean *shipped* where items close on change-open rather than merge; and a
    reopening does not always supersede — an item reopened because the defect REGRESSED has an
    accurate description, and treating it as stale sends the worker hunting a newer instruction that
    does not exist. **What decides currency is the history and the tree.**

    **The three consequences differ, and only the first is obvious.** The dispatch may re-do finished
    work; it may point a worker at a defect that no longer exists, whose deliverable is then a
    refutation; or — the expensive one — it may describe a *smaller* problem than the one the
    reopening found, so the worker fixes what you asked and the real defect survives with an item now
    closed over it. The worker's own history walk catches all three, **but do not let that safety net
    become the plan**: it spends a worker's context re-deriving what one query answers, and the mayor
    learns nothing, because a brief refuted politely reads much like a brief fulfilled.

    **Read it for two things: a HOLD, and SEQUENCING.** A hold is the newest field often enough that
    the description cannot be trusted to mention it, so an item reads as an ordinary defect report
    while the live instruction is *wait*. Sequencing is the mirror case and easier to miss, because
    the brief's instinct is right by default: a triage note naming the predecessors that had to land
    first is an **authorisation with a precondition**, so restating a generic fence over it converts a
    satisfied precondition back into a blocker.

    **Read the INVERSE with more care than either, because it is the one that fails open.** A note
    lifting a fence names *the fence it lifts*, not the item — so an item can carry a second
    sequencing the lift says nothing about, and its newest layer then reads *released* while a live
    precondition sits underneath. The other two merely re-block work that was already clear; this one
    dispatches into a surface somebody holds, or into one gated by a decision nobody has made.
    Measured: an item whose fence had been lifted on the holder's own words still sat behind an older
    note sequencing it behind an unruled operator call — and the *other* half of that older note had
    genuinely cleared, which is what made the whole of it look discharged. **Where the tracker can
    express a precondition, encode it there rather than leaving it in prose**: a fence that exists
    only in a note is re-derived every pass, and a fence re-derived every pass is eventually missed.

    **And a board-wide sweep for hold markers is a candidate filter, never a verdict** — it narrows to
    a handful, and the hold is then established by READING each one. Why it fails in both directions,
    and the structured field that is the durable answer, are under *Tracker mechanics* in
    [`loops.md`](loops.md#tracker-mechanics).

2. **Check every factual claim you are about to write.** Does the symbol resolve? Does the file say
    what you think? Is the count still true? Is the ruling you cite *ruled*, or only recommended? A
    recommendation and a decision read identically in a summary and are opposite in force.
    **Then one question of a different kind: is this work still OUTSTANDING?** The four above ask
    whether a claim is TRUE. A note saying "X is all that remains" was true when written and says
    nothing about now, so checking it at source confirms the wrong thing. Check the TREE. The sentence
    that says so sits in the common preamble below — inside the block you extract and paste without
    reading, because its audience looks like the worker.

3. **Establish the fence by asking who is live, not what is open.** A listing of open changes misses a
    worker that has not pushed yet. **And ask what each nominated gate COMPARES, here, not at
    dispatch** — a fence derived from files alone misses a gate that couples two surfaces (see
    *Fences*).

4. **Name the discriminator**, if the task is "find every X".

5. **Then** assemble the standard blocks.

---

## The three sentences that do the most work

The first two go in **every** editing brief. The third goes in every brief whose nominated gate
affords a safe, bounded, discriminating plant — which is a question about the **gate**, not about the
deliverable.

> **This brief's premises are CLAIMS, not findings.** Check each at source before
> acting on it. A verified "already fixed" or "the premise does not hold" is a
> complete and good deliverable — report it with the evidence rather than going
> looking for work to do.

> **Read the item before this brief, and order it by the tracker's own timestamps —
> not by position, and not by dates written in the prose.** Where they disagree, the
> item governs — follow it, and say in your report what differed.

> **The control is the deliverable.** Show it red when the property is removed,
> restore, and verify the restore by hashing the bytes.

The first is the highest-yield sentence in the preamble. Read-the-item-first caught five stale briefs
in a single day: one whose fix had landed two days earlier under a sibling item; one that named the
wrong audit finding; one told to execute a resolution that had already merged; one a scope correction
had redefined; and one carrying three wrong path, flag and script details. **Every one of those
briefs was accurate when it was written**, and the yield is in *the item governs the brief* rather
than in the direction of reading.

**The third is scoped by the nominated gate's capabilities — not by prose versus code.** A
documentation correction is emphatically included: the cheap validators over a docs tree red on one
broken link target or one bad heading anchor and go green again on restore. Where the covering gate
affords no such plant, the sentence is the unsatisfiable quantifier the method-reread loop is told to
hunt for — and an unsatisfiable rule does not stop the reader, it makes one up. **That failure was
measured on the *enforcing* side rather than a worker's**: four editing briefs went out on one tick
carrying the first two sentences verbatim and none carrying the third, each improvising the same
omission separately — the tell that the rule was doing no work because it was **unscoped**. Scoping
it on the shape of the deliverable instead licenses the same omissions with a reason attached.

**Say what a brief whose gate affords no plant does instead, or that gets improvised too.** Both
wordings are settled below and cover different cases: where no automated gate covers the surface at
all, *Quality gates — which gate*; where a gate covers it but affords no safe discriminating plant,
*Quality gates — how a gate is run*. Point at both rather than restating either — naming the
alternative is what keeps the scoping from reading as permission to prove nothing.

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

**An item's LIST OF SITES goes stale the same way, and it is worse than a count, because it
drifts in MEMBERSHIP rather than in magnitude.** A list looks equally authoritative whether
or not its entries are still true, and it is wrong in two directions at once: an entry
somebody else already fixed sends a worker to edit correct text — or to revert a repair —
while a site the list never had is left live. Four items in one session named sites already
amended, or asserted a verification a later change had falsified; one named "three of four"
when the standing state was one.

**So require the list be RE-DERIVED at the current tip, and require the DELTA to be
reported.** The delta is a deliverable in its own right, not bookkeeping: it is the only
signal that the item was stale, and without it the next reader inherits the same list with
the same confidence. Where a worker returns "two of these five were already done", that is
the item being corrected by the work, which is the system functioning.

---

## A lean is a claim too — demand the number that decides it

When a brief poses a choice between two designs and names which one the author favours, that lean is
as unreliable as any count, and it fails in a worse way: the worker reads it as guidance rather than
as a premise, so the premises-are-claims sentence does not catch it.

So attach the measurement to the lean in the same breath. *"I think A beats B, and I want the cost
that decides it, not a preference."*

The evidence is one dispatch where every part of the author's lean was wrong and the number found it.
The brief guessed that speed was the risk in compiling a doc corpus; the worker measured the compile
fast enough to kill that argument, then rejected compiling anyway on three grounds the brief had not
imagined, and the final margin came out around **380x the other way, for the same catch**. That
brief's fallback was wrong in a checkable way too — it prescribed scoping a rule **per page**, which
flags 21 sites on a corpus that is correct, where the item's own shape, **per section**, measures 0.
The worker followed the item over the brief and was right to.

Two rules come out of that. **Ask for the cost whenever you state a lean** — it converts a confident
wrong brief into a correct outcome, and costs the worker one measurement. And **say plainly that the
item outranks the brief on scope**.

**A number the brief asserts is a lean too**, and it slips through by the same mechanism: a figure
stated as guidance is read as instruction, so the premises-are-claims sentence never reaches it.
Before writing a predecessor's figure into a brief, ask what produced it. One such figure — a bound a
measurement window had derived from its own most-negative reading — went into the next brief as
guidance and was reported to the operator before two independent refutations caught it, and one
question would have caught it first: *what null arm produced that bound?*

---

## Fences

State what the worker owns, then what it may not touch **and who holds it**. Naming the holder tells
the worker whether a conflict means "rebase" or "stop and report".

**A fence is a claim about FILES, so derive it from what each open change actually touches rather
than from its name.** A name — of a branch, a worker, a task — cannot distinguish a change holding
four named files from one holding a whole tree, so a fence written from names is still the guess
deriving it was meant to replace.

**But check whether your change-listing tool PAGINATES, because a truncated list under-reports
silently and in the expensive direction.** A hosted API asked for a change's files commonly answers
with one page and no marker that it stopped: measured, a change of 722 files returned exactly 100,
and not one of the files a pending fence turned on was among them — a fence derived from that answer
would have cleared a second worker onto the surface. **A round number is the tell**; treat 100, 250
or 1000 as a page size until proved otherwise, and for a large change derive the paths from version
control instead, diffing the trunk against the branch.

**And a listing built from committed history is blind to work already done but not yet committed** —
the state a freshly dispatched worker stays in until its first commit, so an empty answer is not
evidence of an empty fence. Ask the worker's own working copy too, and read a change clean by both as
*not started yet* rather than as owning nothing: what it will touch is recorded on the item it was
dispatched under, not in version control.

**And an item's own scope claim is a name too, wearing a reviewer's authority.** A review that files
a dozen items will call them *src-only* or *parallel-safe* at the altitude it worked at, and every
collision such a wave produces is invisible from there: four items editing one file in four regions;
a *test-only* cut that keeps a file a sibling was told would be deleted; a *src-only* lane that must
cross into a held tree by one line. Measured on one wave: ten items dispatched under that claim,
three same-file collisions, every one caught by routing after the fact and none by the fence. Read
each item's cited files against its siblings' before you paste a fence — the citations are usually
there, and the claim was made without doing that. **A fence built from the claim is the guess the
claim's author skipped, not a fence.**

**A fence is derived, never remembered — and the worker derives it again at start-up.** A list
assembled from memory of who you dispatched is stale inside the dispatch's own lifetime, so the fence
travels as a claim the worker re-checks, like every other premise, and its own result wins. **That
re-check is the load-bearing half, because the two staleness directions cost differently.** Too BROAD
costs one re-derivation. Too NARROW omits a worker dispatched after you last looked, and two workers
on one surface merge-conflict and can silently revert each other — accuracy alone cannot reach that,
because it expires between writing the brief and reading it.

Hot-zone files — anything sequential, where two concurrent editors conflict by construction — take
one toucher at a time. If a remedy needs one, the instruction is **stop and report**, not edit. **A
citation is not permission**: briefs routinely cite a specification section as context, and a worker
can read that as licence to edit it.

**A GENERATED file is the one same-file overlap that sequencing does not fix.** A manifest or export
regenerated from the tree is touched by every change that moves what it derives from, and serialising
those changes serialises the fleet for nothing: two regenerations conflict textually and agree
semantically. Brief every toucher instead — regenerate after rebasing, never hand-merge, and where a
job diffs the committed file against a fresh generation, run exactly that job locally.

**And N changes that each touch ONE LINE of one shared index are the second exception.** A catalogue,
a roster, a table of rows: eight items each editing a different row are not a collision worth a
sequence. Brief every worker to isolate that edit as its own final commit, merge in landing order,
and let each later change rebase — the conflict, when it comes, is one line in one commit. Write the
lane rule on every item that shares the file, in the same words, so no worker discovers it at rebase
time.

**But a gate that reconciles two surfaces couples them into ONE change, and sequencing those
deadlocks.** The one-toucher rule reasons about files, and it is right about files. Some checkers
hold one surface against another in both directions — a catalogue against the emitters it lists, a
schema against its fixtures, a manifest against the modules it names — and under such a gate neither
half is green alone: the change deleting the emitters reds until the rows go, and the change striking
the rows reds until the emitters go. Fence the two halves to two items and each waits on the other's
merge. Nothing in a file list shows it. **Ask what each nominated gate COMPARES**, and when two items
hold the two sides of one comparison, brief both halves to one worker — or route the second half into
the live change and re-scope the item that held it. Where a one-toucher file is held only for a
region an item does not need yet, the fence can sit INSIDE the brief instead of in front of it: the
worker does the unheld part first, takes the held file only on a trunk that already carries the
holder's merge, and stops and reports if it reaches that point first.

**The unit is the block, not the file.** Two items dispatched into the same paragraph of one document
because they were nominally different concerns conflicted; the second change could not merge, and by
the time its worker was resumed the hygiene loop had reaped its worktree — two costs from one
scheduling error. If two items touch the same section, sequence them.

**Complementary work is not duplicate work.** When two items attack one defect from different sides —
one making a silent failure loud, one stopping a document teaching the failing shape — say so in
both, or one gets closed as a duplicate of the other.

---

## Choosing solo or cluster

Pick by **kind first, then priority, then size**. Do not reflexively dispatch one-worker-per-item,
and do not reflexively bundle everything.

* **Highest priority → always SOLO.** A dedicated worker and its own change, so it merges on its own
  green and is never blocked by a cluster sibling.
* **Second tier → SOLO by default.** Cluster several only when they are genuinely small,
  same-surface and low-risk.
* **Many small low-priority same-surface items → CLUSTER.** This is the primary clustering case. It
  stops you handling dozens of trivia serially.
* **Any LARGE item → SOLO**, whatever its priority. Never pad a meaty item into a cluster, and never
  bundle two large ones — bundles of four or more items time out routinely where single-item workers
  succeed.
* **A measurement window → SOLO, Shape 6**, picked by kind rather than size and never folded into a
  cluster.
* **One item too large for one worker → SPLIT by disjoint file, one worker each, and NOBODY CLAIMS
  IT.** The reverse of a cluster, for an item whose slices are each a full session. A claim marks the
  item one worker's, and the ready list, the stranded sweep and every other slice's worker then read
  it that way; so each worker appends a claim note and a result note instead, and the mayor closes
  the item when the last slice has landed, citing every change.

**Three of those rules consume a SIZE, and the size you are holding is usually a TITLE.** Sizing
happens at the listing, and a listing prints names. This page already argues that a name is not
evidence about *files*, and that an item's own scope claim is a name wearing a reviewer's authority;
magnitude is the third thing a name gets wrong, and the one no fence catches, because it corrupts the
decision before any fence is derived. Measured: an item whose title named one test that could pass
vacuously — the archetypal small same-surface cluster candidate — carried a body that deleted four
public tools, removed a subsystem, and ran to seven acceptance criteria with explicit non-goals.

**So open every candidate before you cluster it, and read for SHAPE rather than for content** —
acceptance criteria, non-goals, the length of the evidence, whether the body names a few files or
names a subsystem. None of that requires understanding the work, which is why it is cheap. The
asymmetry is what settles it: a misread item does not merely arrive oversized, it poisons the change
its siblings ride in. **And where a title and its body disagree, correct it on the item** rather than
only in your own decision — the listing is what the next reader sees, and a retitle is the only
repair that does not have to be made twice.

**One agent owns a surface; surfaces run in parallel.** That is how you avoid serial handling:
same-surface items ride one agent, which never collides with itself, while genuinely separable
surfaces dispatch as concurrent agents.

**The serial exceptions**, where same-surface work is *meant* to be sequential: a deliberately serial
epic, and a single tightly-coupled module whose core files many items touch. That surface is its own
serial lane — sequence its changes, later ones rebasing on the earlier merges, never resolving a
conflict by taking one side wholesale. On a coupled surface even solo high-priority work cannot run
in parallel.

---

## Dispatch shapes

**Shape 1 — Solo.** One item, one change. Item id and verbatim title; two to four paragraphs of
context with `file:line` citations; numbered concrete steps; a dedicated worktree and branch; the
boundary block; claim the item — unless it is a slice of a split item, which nobody claims (see
*Choosing solo or cluster*); gates with exact commands; push and open the change **as a draft** with
a `## Quality gates` section; report the change URL, a per-step summary and test deltas.

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

**Shape 3 — Audit (read-only).** A finding, not a fix. Goal, surface paths, and prior findings to
avoid re-discovering; the boundary block; write the findings document to the version-ignored working
tree FIRST, never commit it, never link it from committed files; file follow-on items one at a time,
appending each id to the audit item's notes so progress survives a timeout; close with a verdict,
severity counts and cross-references; no change by default.

**An audit brief needs the stance more than an implementation brief does.** The audit is *producing*
findings, and a project that rejects findings as often as it actions them will reject the weak ones
at the cost of a dispatch each. Tell it plainly: eight sourced findings beat thirty plausible ones,
and a finding whose remedy the project's stance excludes is worse than none. Two or three worked
examples of a good finding beat asking for "a careful review". The mayor may reorganise or reject
findings; not every finding is actioned.

**Shape 4 — Cluster reviewer (read-only, no dispatch).** Shapes the next wave. List in-flight workers
and their surfaces first, and do not recommend touching those. Enumerate recently filed items and the
ready queue. Per item decide: add to an in-flight cluster; form a new cluster (three or more items on
a shared non-hot-zone surface); solo (correctness, large, decision-resolved or cross-cutting); or
defer. Output the net next-dispatch shape in two or three sentences. **Do not change tracker state.**

**When items arrive faster than the mayor can read them, ask this shape for the brief inputs as
well**, per item: the premise check at the current tip (each cited line holds, drifted to what, or
does not hold; every asserted count re-run), the surfaces it would edit and which are hot-zone,
same-file collisions against the live and queued sets, the gates by exact command with the
heavyweight ones marked, and each ambiguity a worker would otherwise improvise, with a
recommendation. That is the part of a brief the mayor cannot write without reading the tree, and the
part that goes stale first.

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
  an ordering guard refuses, the run refuses and the worker reports the refusal.
- **A refusal is a deliverable, not a failure.** If everything refuses, the window succeeded: you now
  know the rig cannot see what you hoped it would, which is what you dispatched it to find out. Say
  this in the brief, or the worker reads its refusal as its own failure and goes hunting for a way to
  turn it green.
- **Never "fix" a refusal by loosening a gate.** Every gate in a rig is there because something once
  passed that should not have. Widening a threshold to admit today's run retro-admits that failure
  too, and the series loses the one property that made it worth running.
- **Do not improve the rig mid-window.** No new instrument, no extra rungs, no third estimator: a
  rung added between runs makes the series two instruments. File the improvement as its own window.
- **Do not restate a published figure on thin evidence.** Four runs disagreeing with a number are a
  reason to look again, not a mandate to move it.
- **Verify the machine with real counters, not the convenient one.** One system's headline CPU
  counter read 93% while the true value was 11%. Prefer the counter saying whether anything is
  actually *waiting for a core*, and **read it on its own, never inside a measured run** — sampled
  beside anything heavy it measures the sampler, and inside a run it measures the benchmark. Bracket
  the run, and say plainly that nothing is claimed about within-run quietness beyond the bracketing.
- **Only a CLOCK estimand needs the quiet machine.** A census of monotone counters — allocations,
  recomputations, calls — reads the same on a loaded box, so it may run beside anything and must not
  consume a drain. Classify the estimand before you schedule it; getting it wrong wastes an idle
  machine in one direction and produces a worthless-but-measured-looking number in the other.
- **While a clock window runs, every OTHER dispatch carries a clause reserving the machine** — naming
  the heavyweight gates that worker may not run, **and** saying that *if the only gate covering your
  surface is a heavy one, stop and report* is a correct outcome. Without that second half a worker
  held off the machine runs the gate anyway, to satisfy the surface-gate menu the same brief hands
  it. The failure is silent in both directions: the window returns a plausible worthless number, and
  that number gets published and cited.
- **One run at a time, never concurrent.** Concurrency is precisely the contention the window exists
  to exclude, so two runs the worker believes are independent still are not.
- **Your published sentences are claims too, and what slips is the prose ONE LAYER ABOVE the
  number**: a worker that has just spent an hour being rigorous writes its summary in ordinary
  confident English, and ordinary confident English overclaims. Three measurement changes merged in
  one day, all exemplary on the run itself, all three overstating their published claim — one calling
  three values *run-medians* where the code stored the arithmetic MEAN of five per-round ratios, its
  whole stated basis; one saying a spread "loosely brackets" a figure sitting *above* the maximum of
  its own ratios. No audit overturned a measurement or a refusal; what they corrected is the sentence
  a future reader will quote, which is how a measured result becomes a false premise in someone
  else's brief. So: name the evidence licensing each summarising statement, check the arithmetic of
  any comparison, name the estimator you actually computed rather than the one the surrounding prose
  habitually says, and do not attribute a spread to one cause unless your data excludes the others.
- **An impossible reading cannot bound the quantity.** Let an observed delta *y* be an unknown
  positive true cost *t* plus estimator error *e*. A reading of *y* = −0.0062 proves only that the
  negative error excursion exceeded 0.0062 + *t*: it neither makes ±0.0062 a calibrated symmetric
  floor nor upper-bounds *t*. **A most-negative observation is a statement about the ERROR TERM, not
  about the quantity** — and comparing most-negative readings from *different* positive-cost arms at
  two window widths cannot establish a floor-scaling factor either. **Where a bound is inferred from
  estimator error or noise-floor behaviour, name the NULL OR CONTROL ARM that produced it**;
  otherwise report the term as UNRESOLVED. A bound resting on a direct analytic argument or an
  independently calibrated error figure needs no arm at all — say which warrant you have.

Report what ran, what refused and on which control, the raw numbers, and — as its own heading — what
was **not** concluded. A window that publishes nothing still reports everything.

---

## Publishing the change

**Push continuously, and publish the change as a DRAFT.** Pushed commits are the only durable worker
state — a worker that dies mid-run takes its local commits with it — so the change goes up as soon as
commits exist rather than at the end, and a gate still running is declared in the `## Quality gates`
section as reliance on CI rather than waited out in silence.

**Mark it ready for review as the last act before reporting done.** That one line is what stops
push-as-you-go colliding with a merge loop that merges the moment CI goes green, because the merge
criterion tests the CHANGE and nothing in it can see whether the author has finished. Twice in one
session a change was merged out from under a live worker: once the host's delete-on-merge removed the
branch and the worker's next push recreated it as a duplicate change with a byte-identical diff; once
a merge landed mid-gate, and the remainder took a whole extra change and review cycle. Neither merge
was wrong on the criterion — both were green on every clause.

**Use the host's own draft flag rather than a rule to remember.** Merge commands refuse a draft, so
the interlock is enforced where it cannot be forgotten. The rejected alternative — having the mayor
check worker liveness before every merge — works, but must be remembered on every merge forever, and
it re-introduces exactly the worktree-activity sweep the merge loop deliberately does not do.

**But the flag is only as good as the author eventually setting it, and it fails in BOTH directions.**
Neither direction is visible from the change.

**Stale-OPEN — a fix dispatch inherits a flag somebody else already set.** A fix worker goes onto an
EXISTING change whose original author finished and marked it ready, so the flag is set before the
second worker arrives and nothing in the change records that anyone is inside it now. The merge loop
reads a ready change, the criterion passes, and the merge lands under a live worker — the exact
failure the flag exists to prevent, arriving by the one path where the flag was never the current
worker's to set. **The remedy belongs to the DISPATCHER, because the worker cannot supply it**: a rule
the protected party is unable to execute is not a rule. Converting the change back to draft is part of
dispatching a fix, done before the worker begins — and **confirm the state took rather than trusting
the call**, since a host that ignores the request fails silently and leaves exactly the gap you were
closing.

**Stuck-CLOSED — an author appears to go silent holding a green draft. Read this one carefully,
because the reading is usually wrong.** What gets read as a stopped worker is: no writes, tip unmoved,
worktree clean, everything pushed, a direct message unanswered for half an hour, and a draft sitting
exactly at band with nothing failing.

**Every signal in that list is also what a worker in its FINAL MINUTES looks like.** A worker
finishing up — one last foreground gate, deleting gate logs, unlinking a shared-dependency link,
tidying a scratch directory — writes outside the worktree and outside version control, and is too busy
to answer. **The four indirect signals do not fail independently; they fail together, for one cause.**

**Which makes the frozen reading AMBIGUOUS, and that is the whole finding — not that it means the
opposite.** Frozen-across-three-readings feels like the most damning pattern available, and it is
compatible with a stopped worker and with healthy foreground work alike, so it identifies neither.
Re-reading the same four clocks cannot break the tie: an hour of freeze is the same non-signal as a
minute of it. Measured twice — on the first occasion five workers were reported dormant, **three
completed alive with full reports**, one saying it had been on final hygiene, and **two were never
explained either way**; the diagnosis was retracted in full, including a second claim that messaging
was inoperative, which was *slow* converted into *broken*. **Name those outcomes rather than reducing
them to a ratio**, which invites rounding the unexplained cases into whichever side you are arguing
for — and the two unexplained workers are precisely why the reading is ambiguous rather than merely
mistaken.

**So prefer the DIRECT signal where your tooling offers one** — a live progress line, a status the
supervisor maintains — over any number of indirect clocks. If you have only the clocks, declare a
window and re-read rather than acting; the error is cheap in exactly one direction.

**And do not answer any of it by flipping the flag on an inference.** *It looks done* is the proxy the
reaping rules refuse one surface over. Settle it by a read rather than an inference: the agent's own
report, or the operator's decision to stop that agent, which settles liveness by making it false.
Until one of those arrives the change waits, and the honest report says a finished change is stranded
and why. **Where the second is taken, it is taken FIRST** — stopping the agent and then merging is
authorised; merging and then stopping is the same inference with the act appended afterwards, and it
leaves an identical record, so nothing later can tell the two apart. The coordinator's side is under
*The stranded sweep* in [`loops.md`](loops.md#the-stranded-sweep).

---

## Pasting a block

Three blocks below travel **verbatim** into a dispatch: the **common preamble** into every one, the
**worktree boundary block** and the **gate-mechanics block** into every editing one. Get them there by
**extracting mechanically, then pasting the result into the prompt.** Both halves are mandatory and
they close different failures: the extraction is what makes paraphrase impossible — a matched range
cannot reword, where a mayor retyping two hundred lines of gate mechanics can and does — and the paste
is what makes non-receipt impossible, because a block that is in the prompt cannot be un-received.

**Sending the worker to the FILE is not a substitute.** A worker that skims it, or reads part of it,
has not received the block at all, and nothing in the transcript distinguishes that from a worker who
read every line — so the first evidence is a worker doing something the block forbids. Between a
failure prevented by construction and one prevented by a reader's diligence, take construction.

**And no dispatch is small enough to earn a condensed block.** The temptation is strongest exactly
where the block most dwarfs the task, and condensing there is the paraphrase the mechanical extraction
exists to prevent, arriving dressed as proportionality. Measured: a mayor condensed the gate-mechanics
block for a two-file rename, and the worker ended its turn waiting to be woken — the exact failure
that block names, refutes and closes, in text the mayor had read and chose not to send. **And note
WHICH part went missing**, because it is not random: a mayor condensing drops what looks least
relevant to this task, and what stranded the worker was a mechanic belonging to the HARNESS rather
than to the task — precisely what looks least relevant. A condensed block is not a shorter block; it
is a block with the harness mechanics taken out of it.

**The pressure is structural, because the block only ever grows.** Every failure it closes adds lines,
so the better it gets the more it dwarfs a small task — a ratchet turning against the one rule that
holds it. No wording fixes that, and a block cannot be kept short enough to be safe. The defence is
that the extraction is MECHANICAL, and a mechanical extraction costs the same on the session's
smallest dispatch as on its largest: **if you are weighing which parts this worker needs, you have
stopped extracting and started paraphrasing.**

**But "verbatim" forbids WEAKENING a block, not adding to it — and the paragraphs addressed to YOU are
not part of what travels.** Where the payload is FENCED, the fence settles it: take the fence, and
every line outside it is yours. Two of the three here are fenced, and their mayor-facing prose sits on
opposite sides of the payload, so a habit about which end it lives at is wrong half the time. **Only
the third, which has no fence, opens by naming itself and saying where it stops** — paste that frame
untouched and the worker receives instructions about pasting blocks into dispatches it will never
make, and a pointer by name to a neighbouring section, which is the go-and-read-it this page has just
forbidden. Drop the frame, and add whatever caution the lane needs. Reword and omission are what is
forbidden. **The test is whether the block still refuses everything it refused before you touched it.**

**Anchor the extraction to CONTENT, never to line numbers.** A line range against a living document is
a measured constant that goes stale exactly where it has to be right.

**But match the closing text as a HEADING — anchored to the start of a line — because a block that
documents its own boundaries contains its own end anchor.** The gate-mechanics block opens by naming
where it stops, so its CLOSING string occurs twice: once as the real heading, once inside that opening
sentence. A plain search stops at the mention and the extraction returns the block's opening paragraph
instead of the block, raising no error, because both anchors were found. **The opening heading is NOT
doubled, and that asymmetry is the durable part** — a block that says where it stops must name its
closing anchor, so the trap sits on the closing anchor specifically.

That is worse than the stale line range this rule exists to avoid, and worse in the way that matters:
a stale range returns the wrong two hundred lines, which reads wrong on sight, where this returns a
plausible opening paragraph that reads like a short block. **So check the extracted size before you
paste it — and check it as a CLASS, not against a remembered number.** The two outcomes are orders of
magnitude apart, so the reliable test is whether the extraction ENDS at the closing heading, which
cannot drift. A count here would be [a measured constant about a living
document](#counts-are-claims-and-they-go-stale-first), one section up wearing the other hat.

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
runs (`npm ci`, `npm install`, or any tool that reinstalls a dependency tree)
rewrites the SHARED target, not your copy — one emptied the coordinator's real
tree while the worker's own read as a fresh install. If a nominated gate runs an
installer, do not link that tree: install your own from the lockfile, or STOP and
say the gate cannot run against a link. And kill any poll loops you armed before you report done — each survivor fires
its own completion notification after the work has landed, costing a turn apiece.
```

A project may add a **mayor-side commit guard** — a pre-commit hook in the mayor checkout that refuses
commits touching worker-owned surfaces — so a bypassed edit-guard is caught from the other side.

---

## Common preamble

The tracker commands are placeholders like every other project-specific: substitute your own at
dispatch time. What travels verbatim is the *reasoning* — every trap below is a property of history
walks in general, not of one tool.

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
silent: an item can cite a design by a path no maintainer has. Two audits were lost exactly that way,
and a mayor re-ran an entire three-design programme in one day for want of looking there first — which
is why the preamble tells the worker to look there first. Widening what version control tracks is not
the fix: working notes stay local, and only the *conclusion* is promoted, into whichever tracked
record already owns the surface.

---

## Quality gates — which gate

Every editing dispatch runs gates before opening a change, and lists what ran in a change-body section
headed **exactly** `## Quality gates`, with pass and fail counts. The readers are the mayor's merge sweep
and the merged-change audits — agents, not machinery — so the exact heading is a reader's convenience,
letting them jump to it in a long body rather than read the whole thing. **A heading that does not conform
is a note to the worker and nothing more**: never grounds to edit somebody else's change body, and never a
merge blocker, since the five clauses are the criterion and this is not among them.

- **Nominate the NARROWEST gate that covers the diff — gate time is the dominant cost of a dispatch.**
  Measured on one project: CI ran the whole required matrix in 22 minutes across parallel runners, while
  that project's own pre-checkin script — sequential, one core — took 62 minutes for a *subset*. The merge
  criterion reads CI, never the local run, so a local gate that duplicates CI buys wall-clock rather than
  information. It answers *did I break something obvious* once; every further run of it is CI's job.
  **So do not brief a re-run after a rebase, and do not brief red-to-green iteration on a heavyweight
  gate** — push, and let CI's automatic re-run be the evidence about the new base. Local iteration is
  right only for the focused failing test the worker is writing. The re-run is easy to miss because each
  one is individually justified: a rebase really does invalidate the previous green.
- **Gate the transitive surface, not just the file you changed.** A public-surface change breaks its
  *consumers*, not itself. Gate every artefact reachable from the diff through import edges.
- **Local-green is not CI.** "Green locally" usually means the subset the worker ran; the red gate is one it
  skipped. The merge decision is CI's, not the worker's hand-off. So the brief must say *which* required
  checks the local gate omits — and say it by citing **one path**, not by re-listing them, because the
  required set moves. Make the project *derive* that list rather than document it. Two shapes
  make a hand-written one wrong inside a week: a required status context the local runner has no
  lane in at all, and a required check that is a **step inside a job the runner does run**, which
  no skipped-tier enumeration can see and which reports under that job's name however little it
  resembles what the step does.
- **Never nominate a gate that does not cover the surface being edited.** Site builds, linters and test
  runners all carry exclusion lists, and a gate run over an excluded path exits green having verified
  nothing — the same fail-open defect worth hunting anywhere else, except it is now the brief telling the
  worker to trust it. **Read the exclusion config before naming the gate.** Read the exclusion's own carve-outs
  too, because an exclusion may itself name exceptions — a construct the gate skips everywhere but reports in
  named trees — and **understating coverage misreports as surely as overstating it**, sending a worker to
  hand-check what the gate already proved and to record a gap that is not there. Where a surface genuinely has
  no automated coverage, the honest brief says *no automated gate covers this surface; the worker verifies it
  by hand and says so in the change body* — and the change body then reports what was checked and the counts.

- **Read what each nominated gate RUNS before telling the worker to link shared dependencies.** A link
  into a shared dependency tree is writable through, and a gate that reinstalls dependencies as a step
  rewrites the shared tree rather than the worker's copy — measured: a conformance runner's `npm ci`
  emptied the coordinator's real tree through the link the brief had told the worker to make. Where a
  gate does that, the brief says so and tells the worker to install its own tree from the lockfile.

A skipped gate needs a one-line reason in the change body. A silent skip fails review.

---

## Quality gates — how a gate is run

**This section is the gate-mechanics block.** Paste it verbatim into every editing dispatch,
adapting only the placeholders, from this heading to the rule before `## Reviewing what comes back`.
**`## Quality gates — which gate` is not it and does not stand in for it** — that section is
addressed to you as you nominate the gate, so pasting it instead hands the worker your reasoning and
withholds every mechanic it needs to run anything.

The menu settles *which* gate runs; this settles *how*. The wedge paragraph assumes a gate heavy
enough that two cannot coexist, and the planted-fault paragraphs a gate in which a bounded,
discriminating fault can be planted at a line the worker is already editing — a cheap link validator
is *plantable* without being *heavyweight*. Everything else applies to any dispatch that runs
anything.

**Split a compound gate before you reach for detaching.** A script chaining two phases often splits
into two runs that each fit inside the harness ceiling, which keeps every verdict in the foreground
and removes the strand risk rather than managing it.

**Where it genuinely does not split, detaching is correct — ENDING THE TURN is the defect.** Detach,
then poll both the log and the exit-code file in a bounded loop *within the same turn*: the log shows
progress, the exit file carries the verdict and appears only once the run is over. Word this as the
sanctioned path, not as a concession; a worker who thinks it has erred reads for how to atone and
straight past the instruction that would save it.

**State the rule as a TERMINAL CONDITION, and name the CLASS: the turn ends only once the exit file
exists and its number is quoted.** Stated as a forbidden wait it fails open on the first wait nobody
enumerated — workers have stranded on a monitor, a watch, a notification subscription and a
background task expected to wake them, each a fresh name for the same wait and each read as outside a
rule that named the others. You are the only thing that can read the exit file, and you can only read
it while your turn is still running.

**Refute the belief that keeps re-arming it, because the rule alone has not held.** Your tooling
likely documents that a backgrounded command "notifies you when it completes" — true of a LIVE
session, and the reason a worker reads its wait as sanctioned: it is trusting its harness over this
block. That promise does not survive the end of your turn; once you stop, the completion event goes
to your coordinator, not to you. Every stranded worker was recovered intact the moment somebody asked
it for a status.

**A gate heavy enough that two cannot coexist WEDGES rather than fails** — no progress, no exit file,
no error, from a run healthy a minute ago. This is contention for the MACHINE, so per-attempt naming
does nothing for it and every same-file rule around it misses it. Correlate your own build artefact's
modification time against the candidate runs' start times and **kill only the one you can show is
yours**; a peer's run recovers by itself once memory frees.

**Read the clock before killing anything on elapsed time** — ask the system, never infer it from how
much has happened. It fails both ways: one worker killed a healthy run believing seventeen minutes
had passed when it had been two, and a loop watching from outside reads a worker that has just
rebased as stalled.

**Invoke a backgrounded gate by its ABSOLUTE path**, for two independent reasons: a script deriving
its repository root from its own invocation path gets a relative one back, and an interpreter handed
a relative path resolves it against the working directory, so a wrong directory hands it a *sibling
worktree's copy* which then pins faithfully to the sibling's root. **A complete, internally
consistent run about somebody else's work is far harder to catch than a broken one.**

**Verify which tree the gate actually read**, by whichever of two routes it affords:

* Where it prints its root, check that line names your worktree. That check, not any naming rule, is
  what has caught the observed cross-worktree collisions.
* Where it prints nothing — and many will not — the proof is **the red from a planted fault**, at a
  line you are already editing, whose failure names what you planted. Do not expect the reported path
  to name your worktree; a repository-relative path cannot tell two checkouts apart. **The
  discrimination is the red itself**: the fault exists only in your tree, so a run that had wandered
  into a sibling's would have come back green.

Where a gate affords neither route, say so in the brief — silent and unplantable is a real pairing
rather than a corner — and name whatever bounded mechanism it does afford. Nominating a route the
gate cannot supply makes workers improvise, and the improvisation varies.

**A green sabotage run is a reason to stop, not to proceed.**

**Never pipe a gate through a filter.** A pipeline's exit status is its *last* command's, so a red
runner reads green. Redirect to a log, echo the runner's own exit code, and quote that number — with
the redirect and the echo on **one command line, in one shell**. A separate invocation starts a fresh
shell whose status is whatever that shell last did, typically the directory change: silent, and it
yields a plausible zero.

**Quote the number you captured, never one the harness reports about the same run.** That is a
different measurement and it disagrees routinely — dozens of times across one fleet, over a compile
failure, a genuine two-assertion failure and a browser run standing over three real ones. **Every
disagreement surfaced as exit 0**, and more than half were deliberately sabotaged runs, where
believing the reported zero would have inverted the conclusion.

**A captured exit code is honest about its own phase and SILENT ABOUT THE ONE BEFORE IT.** Where a
gate chains a compile step to a serve step, a failed compile leaves the previously built artefact in
place and the serve step serves it — so the run executes against stale code, passes, and returns its
own truthful zero. Capture discipline cannot reach this: the runner ran, its tests passed, and it is
not the component that knows what it was handed. **Gate the second phase on the first's captured exit
rather than chaining the two and reading the second.** Counts do not catch it either — a stale
artefact ran 885 tests and read entirely plausibly — and a count that does NOT move where you
expected is a question rather than an answer.

**An ABSENT exit-code file is NO VERDICT, not a pass.** It reads as success because the log ends with
the gate's own output and nothing contradicts it; any death between the last line and the exit does
it. **A gate you cannot quote a captured number for has not run** — re-run it, and say whether you
re-ran the whole gate or one step.

**A search that returns ZERO is not a check that passed.** A wrong pattern answers "no matches" in
the same voice as "nothing is wrong". Match fixed strings as fixed strings, and when a search
underwrites a claim, run it once against something it should find. **And make that control share the
SHAPE of the target** — a pattern fails on a FEATURE, and a control lacking the feature passes
without exercising it. Measured: a word-boundary anchor placed after a `$` matched a letter-final
control and missed every punctuation-final hit, so the census read zero on four real sites while its
control read green. If the target ends in punctuation, carries a backslash, spans a line or sits
behind a comment marker, the control must carry **every** one of those features at once — a control
that matches on the axis it shares says nothing about the axis it does not.

**But for the LINE-SPANNING case that advice is inert, and it is the one case where a matched control
actively reassures you.** Most search tools take a line as their unit, so a target broken across two
lines cannot be seen at all, whatever the pattern — and a control sharing that shape is invisible in
exactly the same way. Both come back zero, and the check reports a clean pattern over an unsearched
target. Every other hazard here is caught by a control that bites; this one only by changing the
instrument. **Run the search three times — line-oriented, over the text with whitespace collapsed,
and with comment markers stripped before collapsing.** Neither of the first two is a superset of the
other, and the third reaches what neither can: a phrase broken across lines whose continuation
carries a comment marker survives a plain flatten, collapsing to `the FOUR ;; live emitters`, which
matches nothing. **Wrapped text is the common case, not the exotic one** — most prose documents and
many tracker fields are hard-wrapped, and every commented block and fenced sample puts a marker at
the break. Keep a probe short enough to sit inside one line; where the target is genuinely longer,
flatten, and strip the markers, before believing a zero.

**This is not a fact about searching, and reading it as one is how it gets past you.** ANY instrument
that can answer "nothing here" gives the same answer when misused, and a misused one raises no error,
so its all-clear is complete, well-formed and plausible — a surface classifier handed revisions where
it expects file paths reported every surface as unaffected, which is exactly what a genuinely
unaffected change looks like. Exercise any such instrument once against an input it should flag.

**Name every gate artefact for your worktree AND for the attempt**, log and exit file both, in a
directory version control ignores. A name missing either half fails the gate **open**, and the two
halves close different holes. The scratch path is usually keyed to the session, so peers share one
directory: two workers in one wave wrote the same exit-code filename, and one read a zero a peer had
left while its own gate was still running. **The worktree suffix cannot close the second mechanism,
because there both writers are you** — a runtime cap kills the *shell*, what it spawned survives
holding the same open descriptors, and it writes at its offset while the restart writes from zero, so
the artefact is SPLICED rather than clobbered (measured: an `exit 0` beside 18 real failures, and an
orphan reporting two failures from a run already killed). Expect this route more often than the peer
collision, since detaching is sanctioned and kill-and-restart is routine. **And clean the artefacts
up: one leftover is enough to make a worktree unreapable.**

**Delete them by their LITERAL path, never through a variable.** A deletion whose target a static
permission check cannot evaluate is one it has to stop and ask about, and the ask lands on an
operator who may not be watching — so the cleanup stalls mid-turn and the work queued behind it
stalls with it. The guard is real rather than theoretical: an unset variable turns `"$DIR"/*name*`
into a glob rooted at the top of the filesystem. Write the directory out in full; where a variable is
unavoidable, make the shell refuse an empty one (`"${DIR:?}"`), which removes the hazard but not the
prompt, so it is the fallback rather than the rule.

**Verify a restore by hashing the bytes, never by reading a diff.** A rewrite that flips line endings
reads clean having changed every line — and **a patch that never applied reads clean too**, because
"unchanged" and "not attempted" are the same diff. That second one is the dangerous half: a plant
that silently no-ops makes the sabotage run come back green, so the worker reports a guard that fired
when nothing was ever broken. Hash before the plant, compare after the restore.

**Commit your own edits before you plant anything**, because the obvious restore — asking version
control for the file back — returns it to the last COMMITTED state, not to your pre-plant working
state. Plant from a dirty tree and that restore silently discards the work the gate was being run
against, and the run afterwards is green about a tree you did not mean to have. Five cautions on the
plant itself:

* **Where line endings are translated, use version control's own content hash against the committed
  object**, not a byte digest of the working file — a checkout during a rebase rewrites the working
  file after you hashed it, and a false "restore failed" sends the worker off to doubt the sabotage
  result, which was the deliverable.
* **Call it a *blob* hash in the words immediately before the token.** A content hash is
  indistinguishable from a commit id, and a provenance checker classifying each hex token by the
  nearest description on its left will file it as a citation of a commit that exists nowhere.
* **Anchor a patch to a single line.** A multi-line anchor can match nothing, with no error and no
  edit.
* **An anchor at the END of a line matches nothing where line endings are translated** — a carriage
  return sits between the text and the ending — so the single-line rule above does not cover it.
  **Read the match count before you run the gate**: zero is unambiguous and free, where the hash
  convicts a no-op plant only after a whole run has been spent. The endings can change under you
  *between* plants, so read the count per plant, not once per session.
* **A hash proves the SOURCE changed, not that the runtime ever saw it.** One plant applied genuinely
  and the file was not in the build's module graph, so the watcher served the pre-plant compile and
  the witness came back green. A green sabotage run is evidence only once both halves hold: the hash
  for the source, and positive evidence the runtime observed the plant.

**Scope a plant to the suite under test.** Where the runner executes a whole lane in one block
without catching exceptions, a plant that crashes any namespace stops every namespace after it and
the log still looks plausible. Compare namespace and assertion counts against a control run.

**The gates the brief nominated are not the whole obligation, because a project's RATCHET gates grade
the PATHS you touched rather than the change you made.** A ratchet permits a recorded count and
refuses any increase — a per-surface floor a surface with baselined debt already sits exactly on, or
a retired-vocabulary scan whose permitted count is zero — so an ordinary new file, or one word in a
comment, is a regression, and no brief nominated the gate because the change looked nothing like its
subject. Before you open the change, find the ratchets covering your paths, run them, and treat each
gate's own output as the authority on what it wants: one that fails prints the exact spelling it
wanted. A project keeps them together — a checks directory, and the job lists of the workflows that
run them. **Do not ask for a list of them, and do not write one**: the set grows, and an enumeration
is a count, which is the thing that goes stale first.

**Two properties of that repair, both learned by getting them wrong.** These gates grade more than
the lines you think you changed — some read the whole tracked file, comments and docstrings included,
so a sentence naming the design you REJECTED fails while the code it describes passes; others grade a
form nobody counts as part of the change, such as an import edge. And **a ratchet is repaired by
changing your own lines, never by moving its floor**: raising a recorded count to admit new debt
inverts the gate, and walking that debt down is another item's job, so repairing pre-existing
violations in files you did not otherwise touch is sprawl into it. Read the gate's WHOLE output
before you fix — it names every surface over its floor, where a number quoted into a brief from a
failed run names one.

**Re-run the gates on the final base after every rebase.** A pre-rebase green is evidence about a
tree that no longer exists, and nothing warns you: the rebase reports success and the old log still
says exit 0. One worker rebased four times past eleven landings, and re-running changed the artefact
rather than reconfirming it — fixes for three of its own findings had merged in the interval, so the
record it was about to publish carried a count false of the trunk.

**Diff your branch against its MERGE BASE before you push, and read that diff for what you would
REVERT.** Not for conflicts — version control reports those, and a clean rebase is exactly the case
this rule is for. The question is whether your push undoes something a sibling landed while you
worked, which no gate covers: a revert of a merged change is well-formed, compiles, and passes every
check the tree has, and in a shared document a stale copy of one region reapplies as a silent
deletion of everything that landed in it since. **The base is half the instruction** — compare
against the point your branch left the trunk, never the trunk's current tip, which buries the one
hunk that matters under siblings' unrelated work and degrades worst exactly when the trunk has moved
most. In the dominant toolchain that is the three-dot form, `<TRUNK>...HEAD`.

---

## Reviewing what comes back

* Did it check the premises, or accept them?
* Is there a control, and does it fail for the right reason? Ask specifically whether the control could catch its own
  case.
* Which numbers are captured, and which are reported by the harness?
* What did it decline to do, and why? A report with no refusals, on a task that had a plausible one, is worth a second
  look.
* **Where it CONTRADICTS the brief, that is evidence about the brief.** Read it as such before defending or
  re-explaining: verify the worker's claim at source, and if it holds, the brief was wrong. This is not a courtesy.
  Measured across one session: seven briefs carried a defect — a hold the item recorded and the brief did not, work the
  item had already split, a sequencing precondition the item had discharged and the brief re-fenced, and a ruling made
  from a page's confident prose without checking the spec that owned it. **A worker surfaced every one. The coordinator
  caught none.** The clause that makes this work is the preamble's *these premises are claims, not findings*, which
  reads like a guard for the WORKER and functions as error-correction for the COORDINATOR — so never trim it to save
  room, and never treat the pushback it produces as friction. A dispatch that comes back saying *the premise does not
  hold* has done its job.
* Are you about to quote a figure or a phrase OUT of this report and into the next brief? Then it is a claim like
  any other and the question is what produced it. A sequence of measurements and a correction to one measurement
  read identically once compressed to a line: one mayor turned four per-window figures into “the number moved”
  and briefed a worker to fix a figure that was correct. Reports invite this — they are dense, confident, and
  written by an agent that has just spent its whole context being careful. **A figure and the subject it was
  measured on arrive in the same sentence, so the compression can pair them WRONGLY** — and re-deriving at the
  current tip cannot catch that, because the census is correct about the subject you wrote. The tell only appears
  when a later worker refutes the number for its own subject; **trace a refuted figure back to what it WAS true of
  before you drop it**, because the thing it was true of is an unfiled finding. Measured here: a figure relayed onto
  the wrong file was corrected to that file's real count, and the original number turned out to describe two other
  files nobody had filed.
* What did it find that you did not ask for? File those **with the owning item named in the new item**, so a later
  audit reopening that owner is recognisable as the same finding rather than a second one. One mayor skipped that and
  created a duplicate of its own within hours.

---

## Failure modes these shapes close

- Back-compatibility shims by default → the stance is explicit in every preamble.
- Same-file races between concurrent workers → in-flight surfaces enumerated.
- Two halves of one gate-coupled invariant fenced to two items, each red until the other merges → ask what each
  gate compares; one worker takes both halves, or the second is routed into the live change.
- Edits leaking into the mayor checkout, especially silent new-file leaks → the boundary block plus a post-write check
  of both trees.
- Cross-worktree contamination via stashes → the no-stash rule.
- A peer overwriting a worker's scratch file, or an orphaned child of a killed gate writing over the run that replaced
  it — either way a worker reads the survivor as its own, including a gate exit code belonging to a different run,
  which merges on a green nobody earned → artefacts named for both the worktree and the attempt, plus the gate-root
  check, which is the half observed to actually catch it.
- Worktree cleanup deleting *through* a link into the shared tree it points at → the worker unlinks before reporting
  done, and the cleanup path disarms before removing.
- A change merged out from under a worker still working on it → it is published as a draft and marked ready
  only as the author's last act, which the host enforces by refusing to merge a draft. **Except on a fix
  dispatch, where the flag was already set by somebody else and protects nothing** — and the same flag
  strands a finished change when its author goes silent. Both are under *Publishing the change*.
- "Green locally" merged into a red CI gate → gate the transitive surface; merge on CI, not on the hand-off; a real
  failure gets a fix worker, never an override.
- A passing synthetic test that routes around the real bug → reproduce the actual failing path.
- Clusters split that should be one change, or the reverse → the cluster reviewer pre-validates shape.
- Stalled workers losing analysis → findings first, and one-item-at-a-time tracker creates.
- Re-discovering known issues → name recent landings and prior findings.
- A brief that was accurate when written but stale when read → the item governs the brief, newest material found by tracker timestamp.
- Generic prompts → require `file:line` citations and concrete fix sketches.
- A measurement worker iterating until the number looked right → Shape 6: the controls arbitrate, a refusal is a
  deliverable, and the rig does not change mid-window.

---

*Record three or four exemplary dispatches per project — a solo, a cluster, an audit, a fix. A few good examples teach
a new mayor more than thirty mediocre ones.*

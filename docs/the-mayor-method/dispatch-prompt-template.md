# Worker dispatch — canonical prompt shapes

Terse, copy-adaptable shapes for delegating bounded work to background workers.
Assumes a capable agent. Placeholders:

- `<MAYOR_CHECKOUT>` — the mayor's primary checkout (absolute path)
- `<WORKTREE_ROOT>` — sibling dir holding worker worktrees (derive via `git worktree list`; never hardcode a path)
- `<ASSIGNED_WORKTREE>` — this worker's worktree (subdir of `<WORKTREE_ROOT>`)
- `<BEAD_ID>` — the tracker id

> **Project-specifics live with the project, not here.** The hot-zone file list,
> the surface→gate matrix, the pre-checkin command, and the worktree root are
> facts about *one* repo — keep them in that repo's agent-instructions (this
> repo: `CLAUDE.md` + `TESTING.md`). This file is the reusable, OS-neutral
> method; pull the concrete values from there at dispatch time.
>
> **The stance is project-specific too, but it is PASTED rather than pulled, and
> it lives in its own file.** Keep it as a single quoted block in ONE file and
> paste that block verbatim into every preamble — in this repo
> `.claude/commands/mayor-posture.md`, which is also the loop that rereads it.
> Not `CLAUDE.md` and not `TESTING.md`: neither carries a stance at all, so a
> brief that sends a worker there for one sends it somewhere the words are not.
> And a summary is not a lighter version of the stance. The lenses say what good
> looks like; everything after them says when to STOP — so a paraphrase keeps the
> memorable half and drops the restraining one, and what survives does not read
> as incomplete. It reads as a stance that wants MORE of everything, which is
> precisely the failure the second half exists to prevent.

## Worktree boundary — paste verbatim into every editing dispatch

**The concept (hard-won).** A worker edits only its assigned worktree, never the
mayor checkout. Shell `cwd` is not enough protection: some edit tools resolve a
relative path against the agent's session root rather than the git root, so a
write can land in the mayor checkout *even after a start-of-session guard
"passed"* — the leak happens mid-session in one tool call. A guard script can be
fooled by that cwd resolution; the real backstop is the worker re-verifying its
git root before every edit. New-file leaks are the worst case: a brand-new
gitignored file routed into the mayor checkout shows nothing in the worker's own
`git status`, so it fails silently — check the mayor side explicitly.

```text
WORKTREE BOUNDARY — MANDATORY
Your worktree:  <ASSIGNED_WORKTREE>
Mayor checkout: <MAYOR_CHECKOUT>   ← never edit this.

Before EVERY edit, confirm you are in your worktree:
  git -C <ASSIGNED_WORKTREE> rev-parse --show-toplevel   → must print <ASSIGNED_WORKTREE>
Use ABSOLUTE paths under <ASSIGNED_WORKTREE> for every edit/write. A
start-of-session guard is NOT sufficient — verify per edit.

After your first edit, and after writing any NEW file, confirm it landed in your
worktree and NOT the mayor checkout (check BOTH trees — a new gitignored file
leaking into the mayor checkout is invisible in your own `git status`). If
anything landed outside your worktree: STOP, report both paths, do not
repair/commit/push — let the mayor decide.

Do NOT `git stash` — stashes are repo-global and surface in other workers'
worktrees, cross-contaminating them. Commit to your branch instead.

Concurrent workers SHARE one session scratchpad directory — its path carries the
SESSION id, not your worktree — so name every scratch file you write outside your
worktree FOR that worktree, and every gate artefact for the ATTEMPT that wrote it
as well: `pr-body-<worktree>.md`, `gate-fastpr-<worktree>-1.log`,
`gate-fastpr-<worktree>-1.exit`, never the bare names, and bump that number on
every re-run. A generic name is silently overwritten by a peer: nothing errors,
and the loser can READ the survivor and take a PR body with plausible structure
and the wrong subject for its own — or a gate exit code belonging to another
worker's run, which reads as a clean pass and fails the merge decision open. The
attempt number closes the same hole from the other side, which the worktree
suffix cannot reach because both writers are YOU: the harness's ten-minute cap
kills the SHELL, not what it spawned, and the orphan keeps writing to the `.log`
and `.exit` your restarted run is already using. A NUL hole or two summary lines
in one log is the tell. One worker shipped `exit 0` that way with 18 real
failures underneath it. Confirm a scratch file is your own — and your current
attempt's — before believing it, and confirm each gate read YOUR worktree before
believing its colour — by whichever of two routes that gate affords. The shell
gates print `gate root: <path>` as their first line; check it names your
worktree. A gate that prints no banner (no `scripts/check_*.py` does) is
discriminated by the red from a fault you planted, which exists only in your
tree. That worktree check, not this naming rule, is what has actually caught
both observed collisions.

If you create a `node_modules` symlink/junction in your worktree, remove the
LINK (never its target) before you report done: a later `git worktree remove`
follows it and deletes the shared tree it points at.
```

A project may add a **mayor-side commit guard** (a pre-commit hook in the mayor
checkout that refuses commits touching worker-owned surfaces) so a bypassed
edit-guard is caught from the other side. Install per the project's hook scripts.

## Common preamble (every dispatch)

```text
You are implementing <BEAD_ID> in <project + one-line description>.
<PROJECT STANCE — paste the stance block VERBATIM from the one file that owns it
(this repo: `.claude/commands/mayor-posture.md`). Do NOT summarise it. The lenses
say what good looks like; the clauses after them say when to STOP, and a
paraphrase keeps the first half and drops the second.>

READ THE BEAD BEFORE THE BRIEF, AND READ IT BOTTOM-UP — MANDATORY.
`bd show <BEAD_ID>` prints DESCRIPTION first, and the description is the OLDEST
text on the bead: corrections, scope changes and sibling landings accrete BELOW
it as notes. So read the notes from the bottom up, the description last. Where
the bead and this brief disagree, the BEAD governs — follow it, and say in your
report what differed.

THIS BRIEF'S PREMISES ARE CLAIMS, NOT FINDINGS. Check each at source before you
act on it: that a ruling it names is ruled and not merely recommended, that a gate
it names covers your path, that a symbol it names resolves. A verified "already
fixed" or "the premise does not hold" is a complete and good deliverable — report
it with the evidence rather than going looking for work to do.

Do NOT link gitignored working files (the ai/ tree, findings docs) from committed
docs — the strict-docs link validator fails the build in cascade. Inline a
one-sentence summary instead.
Working notes may live in ai/findings/ (gitignored — check there first for prior
passes), but before the bead closes the verdict must be self-contained on the
bead and any implementation-governing conclusion must land in its owning tracked
record (spec/, docs/, docs/design/) — a fresh maintainer must never need
ai/findings/; do not promote transcripts.
```

**A pass that concludes only in the gitignored tree concludes where nobody can
read it.** The paths in that last sentence are this repo's; the rule is not —
every project keeps some scratch tree git cannot see, and that invisibility is
what makes the failure silent: a bead can cite a design by a path no maintainer
has. Two audits (`rf2-cgcv`, `rf2-kfpf`) were lost exactly that way, and a mayor
re-ran an entire three-design programme in one day for want of looking there
first. Widening what git tracks is not the fix — working notes still stay local,
and only the conclusion is promoted, into whichever tracked record already owns
the surface, as a dated amendment where one exists and a new page only when the
evidence stands on its own. Three workers in a single day found their design
already written in that tree and promoted the surviving conclusion rather than
re-deriving it, which is why the preamble tells the worker to look there first.

**A bead is oldest at the top, which is why the reading order is mandatory rather
than tidy.** Every correction to a bead arrives below its description, so reading
top-down starts from the stalest field on the record — and that is also the field
the dispatching mayor most likely paraphrased into the brief. In one day the rule
caught five stale briefs: `rf2-409ab`, whose fix had landed two days earlier under
a sibling bead; `rf2-409ab` again, where the brief named the wrong audit item;
`rf2-2rtt6.103`, told to execute a resolution that had already merged in PR #7607;
`rf2-y1jkm`, which a scope correction had redefined from an allocation leak to a
baseline-contamination leak; and `rf2-2rtt6.56`, carrying three wrong path, flag
and script details. Every one of those briefs was accurate when it was written.

## Quality gates — the discipline

Every editing dispatch runs the project's pre-checkin gate spine before opening a
PR, and lists what ran in a PR-body section headed **exactly** `## Quality gates`
(a verbatim heading is a contract for automated PR audits) with pass/fail counts.
Two hard-won rules:

- **Gate the transitive surface, not just the file you changed.** A public-surface
  change breaks its *consumers*, not itself — gate every artefact reachable from
  the diff through `:require`/import edges. (The concrete surface→gate matrix and
  how to discover consumers live in the project's `TESTING.md`/`CLAUDE.md`.)
- **Local-green ≠ CI.** "Green locally" usually means the subset the worker ran;
  the red gate is one it skipped (integration/live, a linter, a drift-check). The
  merge decision is CI's, not the worker's hand-off. So the brief must be able to
  say *which* required checks the local spine omits — and say it by citing one
  path, not by re-listing them, because the required set moves. Make the project
  derive that list rather than document it: in this repo it is
  `python scripts/check_fast_pr_gap.py --list`, catalogued under
  `TESTING.md` § *Required checks the fast-PR spine does not run*, and the spine
  prints the digest on its own PASS line. Two shapes make a hand-written list
  wrong within a week — a **second or third required status context** the local
  runner has no lane in at all (linters, docs builds), and a required check that
  is a **step inside a job the runner does run**, which no skipped-tier
  enumeration can see and which reports under that job's name however little it
  resembles what the step does.
- **Never nominate a gate that does not cover the surface being edited.** Site
  builds, linters and test runners all carry exclusion lists, and a gate run over
  an excluded path exits green having verified nothing — the same fail-open defect
  worth hunting anywhere else, except it is now the brief telling the worker to
  trust it. Read the exclusion config before naming the gate. Where a surface
  genuinely has no automated coverage, the honest brief says *no automated gate
  covers this surface; the worker verifies it by hand and says so in the PR body* —
  and the PR body then reports what was checked and the counts.

A skipped gate needs a one-line PR-body reason (e.g. "tool not installed locally;
relying on CI"). A silent skip fails review.

### How a gate is run, not just which one

Seven rules about the mechanics. Each is here because it cost real hours, and
none of them is obvious from the gate command itself.

- **Detaching a long gate is CORRECT; ending the turn afterwards is the defect.**
  The harness hard-kills a foreground command at ten minutes (exit 143) and
  `scripts/test-fast-pr.sh` needs roughly twenty-five, so for the full spine
  "foreground, to completion" is not on offer however the brief is worded — that
  ceiling killed four spine runs in one night before it was written down here.
  Foreground a gate that fits inside the ten minutes; **detach one that does not,
  then poll both its log and its `.exit` file in a bounded loop, within the same
  turn.** Poll both, because they answer different questions: the log shows
  progress, while the `.exit` file is what carries the verdict and appears only
  once the run is actually over. What strands a worker is ending the turn
  instead, waiting for a completion notification — it does not always arrive, and
  a turn that has ended has nothing left to wake. The worker then reports
  "standing by" through idle tick after idle tick while its branch sits unmoved:
  four such incidents in a single day, then three more inside one hour on
  2026-08-13, every one recovered intact the moment somebody asked it for a
  status. Two of that last three apologised for *detaching*, which is why this
  bullet no longer grudges it — a worker who believes it has already erred reads
  for how to atone rather than for what to do next, and reads straight past the
  instruction that would have saved it.
- **Invoke a backgrounded gate by its ABSOLUTE path.** This holds for every
  gate, by two different mechanisms. The shell gates — every `scripts/test-*.sh`
  — derive their repo root from `${BASH_SOURCE[0]}`, which is relative when the
  invocation is. The `scripts/check_*.py` gates instead default their root to
  the script's own grandparent, which sounds immune to cwd and is not:
  `python scripts/check_….py` resolves THAT path against the cwd first, so a
  wrong cwd hands the interpreter a sibling's copy of the script, which then
  pins faithfully to the sibling's root. Either way, a `cd <worktree> && sh
  scripts/…` does not reliably keep that `cd` once backgrounded, so the run
  adopts whatever cwd the shell really has. One did exactly that inside *another live worker's* checkout: it took
  that tree as its spine root, its diff root and its classifier input, then
  reported the resulting verdict as its own. A complete, internally consistent
  run about somebody else's work is far harder to catch than a broken one, so
  the shell gates — every `scripts/test-*.sh` — print `gate root: <path>` as
  their first line, and some node runners do as well. **Check that line against
  your worktree before believing any colour.** That check, not any naming rule,
  is what has actually caught this class of defect: both 2026-08-12 scratchpad
  collisions (the artefact bullet below) were caught by a worker reading
  `gate root:` and finding a sibling's worktree name there, and neither by the
  file-naming convention its brief had already given it. **The Python gates
  print no such line** — not one of `scripts/check_*.py` emits it,
  `check_doc_slugs.py` included, and several of them print nothing whatever on
  success. There the proof is the same shape drawn from a different source:
  **the fault the gate names when you plant one.** Plant it at a line you are
  already editing, run the gate red, and check that the path and line number in
  the failure are the ones you planted. Do not expect that path to name your
  worktree — `check_doc_slugs.py` reports repo-relative
  (`docs\the-mayor-method\dispatch-prompt-template.md:214`), which cannot tell
  two checkouts apart on its own. The discrimination is the red itself: the
  fault exists only in your tree, so a run that had wandered into a sibling's
  would have come back green, and a green sabotage run is a reason to stop, not
  to proceed. This half is written down because the bullet used to claim
  *every* gate printed the banner, which is an instruction a worker on a Python
  gate cannot satisfy — two hit it in one day, and one of them arrived at the
  negative control unprompted, because a worker facing an unsatisfiable rule
  improvises rather than stops. So the check is mandatory on every gate run, by
  whichever of the two routes that gate affords — a positive verification
  performed on evidence you received is a stronger shape than a rule you are
  asked to remember.
- **Never pipe a gate through `tail`, `head` or `grep`.** A pipeline's exit
  status is its *last* command's, so a red runner reads green and the PR claims
  a pass it never got. Redirect to a log file, echo the runner's own exit code
  explicitly, and quote that number in the PR body — with the redirect and the
  echo on **one command line, in one shell**, as the snippet below writes them.
  A separate invocation starts a *fresh* shell whose `$?` is not the gate's but
  whatever that shell last did, which is typically the `cd`: silent, and it
  yields a plausible zero. That is this bullet's own defeat by a second route,
  so a paraphrase that drops the single line drops the rule. **The number you
  quote is the one you captured** — never one the harness reports about the same
  run, which is a different measurement and disagrees routinely rather than
  rarely: at least seven times across five workers in three days. The first was
  noted on 2026-08-11, a gate surfaced as *completed (exit code 0)* while the
  run's own captured status was 1; 2026-08-13 alone accounts for six more,
  across five workers — among them a compile failure from an unbalanced paren, a
  genuine two-assertion failure, and a browser run standing over three real
  ones. Every one of them surfaced as exit 0, and every one was caught because
  the worker quoted the number it had captured. The rule is doing its job; it
  was the frequency that was understated, and a worker deciding how hard to look
  reads the frequency, not the rule.
- **Verify a restore by hashing the bytes, never by reading `git diff`.** A brief
  that proves a guard is *exact* rather than merely present tells the worker to
  plant a fault, run the gate, then restore — and `git diff` misreads that restore
  two ways. A rewrite that flips line endings reads clean having changed every
  line; and **a patch that never applied reads clean too**, because "unchanged" and
  "not attempted" are the same diff. The second is the dangerous one: a plant that
  silently no-ops makes the sabotage run come back green, so the worker reports a
  guard that fired when nothing was ever broken — a false proof of a real guard,
  which is worse than no proof. Hash the file before the plant and compare after
  the restore. On a checkout whose line endings are translated, **anchor a patch to
  a single line**: one worker's first multi-line anchor matched nothing, with no
  error and no edit, and only the hash caught it. **A hash proves the SOURCE
  changed, not that the runtime ever saw it** — and that second half has a false
  green of its own. On 2026-08-12 a worker planted a one-line egress fault in
  `implementation/hicasso/src/re_frame/hicasso/tool.cljs` and its live wire witness
  came back GREEN. The hashes differed (`2c6ecbed…5148` before, `915aaf91…e424`
  planted), so the plant had genuinely applied and the rule above reported success
  — but `re-frame.hicasso.tool` is not in the host build's module graph, so
  `shadow-cljs watch` never noticed the edit and served the pre-plant compile.
  Restarting the watch produced the red at once, naming the fault exactly. So **a
  green sabotage run is evidence only once both halves hold**: the hash for the
  source, and positive evidence the runtime observed the plant — restart the watch,
  assert the plant's own marker in the served bundle, or confirm the build reported
  recompiling the file you edited. Read without that second half, the green says
  *the control is vacuous, my witness proves nothing*, and the worker's next move is
  to tune the witness until it reds against a fault that was never being compiled —
  a conclusion about the wrong artefact altogether. The hash deepens this trap
  rather than closing it, because it is genuine evidence for a narrower claim than
  the worker needs.
- **Put *every* gate artefact where git ignores it, and name each one FOR your
  worktree AND for the attempt that wrote it** — the log and the exit-code file
  both. `*.log`, `*.exit` and `*-exit.txt` are all ignored; the `-<worktree>`
  suffix is what stops a *sibling worker* writing those same two files, and the
  trailing attempt number is what stops *you* writing them twice:

  ```bash
  sh <ASSIGNED_WORKTREE>/scripts/test-fast-pr.sh > gate-fastpr-<worktree>-1.log 2>&1; echo "$?" > gate-fastpr-<worktree>-1.exit
  ```

  Bump that number on every re-run — `-2`, `-3` — and never write to one twice.

  **Neither half is tidiness; a name missing either fails the gate OPEN.** The
  scratchpad path carries the SESSION id, not the worktree, so every worker in a
  wave shares one directory. On 2026-08-12 two of six workers in a single wave
  wrote `gate-fastpr.exit` there; one then read a `0` a peer's run had already
  left while its own spine was still running (`ps -ef` showed its pid alive),
  and found its log interleaved by two writers at independent offsets, one line
  beginning mid-word.

  **The worktree suffix cannot close the second mechanism, because there both
  writers are the same worktree.** A process the harness could not kill goes on
  writing to an inherited descriptor after its replacement has started: the
  ten-minute cap kills the *shell*, and what that shell spawned survives holding
  the same open `.log` and `.exit`. It writes at *its* offset while the restart
  writes from zero, so the artefact is not cleanly clobbered but spliced — the
  tell is a NUL hole in the log and **two summary lines** where there should be
  one. Measured twice independently on 2026-08-13: `worker/trusted-il7b` was left
  with `exit 0` sitting beside 18 real failures, and `worker/tense-cluster`
  (#8060) found an orphaned `node out/node-test.js` reporting `2 failures, 0
  errors` from a run that had already been killed. The worktree suffix was
  present and correct in both, and made no difference to either. Expect this
  route more often than the peer collision, not less: detaching a long gate is
  the sanctioned path (foreground dies at ten minutes, the spine needs about
  twenty-five), so kill-and-restart is routine rather than exceptional. A fresh
  number per attempt sends the orphan's write somewhere you will never quote.
  It is belt to the capture rule's braces rather than a replacement for it —
  `tense-cluster` was saved by quoting its own shell's captured code instead of
  the log, and that is precisely what the capture rule is for.

  The exit code a PR body quotes is exactly the artefact both collisions
  corrupt, and by either route it reads as a clean pass — so this is the merge
  decision failing open, not a housekeeping slip. The boundary block above
  carries the same rule, and an example here that dropped the suffix is what let
  two workers obey the document and still collide — which is why the two have to
  be kept in step.

  Saying only "put the log somewhere ignored" is the trap: a worker satisfies it
  and still strands the exit file, which for a while was itself untracked. One
  leftover is enough — `bench-logs/`, `PRBODY.md`, an unignored exit file — and
  `git worktree remove` refuses the tree from then on. Nine worktrees
  accumulated exactly that way before anyone worked out why they would not reap.
  Cheapest fix is to not create the residue.

- **Re-run the gates on the final base after every rebase.** A pre-rebase green
  is evidence about a tree that no longer exists, and under a saturated wave the
  trunk moves under a worker routinely rather than rarely. Nothing warns you: the
  rebase reports success, and the old log still says exit 0. PR #8080's worker
  rebased four times past eleven landings, and re-running the gates on the final
  base *changed the artefact* rather than merely reconfirming it — fixes for three
  of its own five findings had merged in the interval, so the governance record it
  was about to publish carried a membership count **false of `main`**. A false
  count in a record like that is not caught later; it is cited later.

- **Diff against `origin/main` before you push, and read that diff for what you
  would REVERT.** Not for conflicts — git reports those itself, and a clean rebase
  is exactly the case this rule is for. The question the diff answers is whether
  your push undoes something a sibling landed while you worked, which git will
  not raise and no gate covers: a revert of a merged change is well-formed, it
  compiles, and it passes every check the tree has. In a shared document a stale
  copy of one region reapplies as a silent deletion of everything that landed in
  it since. One worker's first push would have reverted a concurrent rewrite of a
  shared ledger; it caught that by reading the diff before reporting, and by
  nothing else.

### Briefing a correction — sweep every carrier

When a bead corrects a stale factual claim, brief the worker to find **every
carrier of that claim and dispose of all of them in one pass**. *"Bounded repair"
bounds the change, not the search*: `git grep` the exact wording across tracked
files, read every hit, settle each one in the same PR, and list in the PR body
what was changed and what was left standing because it was already right.
`rf2-2l17` corrected a single sentence four times across three PRs. Every repair
was bounded and correct, and every one was found by the *next* merged-PR audit
rather than by the repair before it — three round-trips of mayor, worker and CI
for what one `git grep` closes.

Two cautions have to travel with the rule, or the sweep does damage. **The phrase
is usually right somewhere**: that grep also hit an invariants page whose
timer-callback row said "one macrotask later" and meant it, plus a source file, a
shared test suite and two benchmarks. Reading every hit is the work; the grep is
only the index. Which is why **the brief must name the discriminator** — there it
was *who schedules the deferral*, our own collector (repaired to a microtask)
against React or a timer (still a macrotask). Without it a worker cannot separate
the stale hits from the correct ones, and will change all of them or none.

## What the brief asserts

A brief states facts about the tree, and the two rules below are about those
facts rather than about the work. The first is the mayor's discipline while
writing; the second is one sentence the worker reads.

- **Verify every factual assertion before it goes into the brief.** Each one is
  checkable in seconds while writing and expensive to catch afterwards. **Read a
  ruling's STATUS field and quote it** — a recommendation and a ruling read alike
  in a summary and are opposite in force. **Grep that a named symbol resolves.**
  For gates the rule is already stated under *Quality gates — the discipline*
  above, and it generalises: naming the wrong one is worse than naming none,
  because the worker then trusts a green that means nothing. The cost is not
  pedantic. One brief twice cited a naming ledger's *Recommendation* column,
  status `open`, as a settled ruling — for a name that did not resolve either, so
  a compliant worker would have replaced one non-existent symbol with another, in
  the files the bead existed to repair, under an authority nobody had granted.
- **Say in the brief that its premises are claims.** The preamble already tells
  the worker the bead governs and its notes are read bottom-up, which catches a
  brief that went *stale* — it does nothing for one that was wrong when written,
  and on 2026-08-11 three of those went out in a day: the wrong gate named for a
  surface, a discipline asserted that the method had never contained, and the
  ledger row above. Every one was caught by the worker. So the preamble states the
  standing permission in terms: the mayor's premises are claims to be checked at
  source, and a verified *"already fixed"* or *"the premise does not hold"* is a
  complete and good deliverable. Workers caught mayor errors at source twelve
  times that day, three of them preventing damage rather than correcting a word —
  which makes it the highest-yield sentence in the preamble.

## Choosing solo vs cluster

Pick the shape by **priority first, then size** — don't reflexively dispatch
one-worker-per-bead, and don't reflexively bundle everything:

- **P1 → always SOLO** (Shape 1). A high-priority bead gets a dedicated worker and
  its own PR, so it merges on its own green and is never blocked by a cluster-sibling.
- **P2 → SOLO by default.** Cluster several P2s only when they are genuinely small,
  same-surface, and low-risk.
- **Many small low-priority (P3/P4) same-surface beads → CLUSTER** (Shape 2). This is
  the primary clustering case: it stops you handling dozens of trivia serially.
- **Any LARGE bead → SOLO**, whatever its priority (a feature, a deep / multi-file
  fix). Never pad a meaty bead into a cluster; never bundle two large beads.

**One agent owns a surface; surfaces run in parallel.** That is how you avoid serial
handling: same-surface beads ride one agent (which never collides with itself), while
genuinely-separable surfaces dispatch as concurrent agents. Two workers never share a
surface — they merge-conflict and can silently revert each other.

**The serial exceptions** (where same-surface work is *meant* to be sequential): an
EPIC deliberately structured serially; and a single tightly-coupled module whose core
files are touched by many beads — that surface is its own serial lane (sequence its
PRs, later ones rebasing on the earlier merges; never blind `--theirs`/`--ours`). On a
coupled surface, even solo P1/P2 work cannot run in parallel — sequence it one at a
time, or fold a tight coupled set into one cluster-lane.

## Dispatch shapes

**Shape 1 — Solo.** One bead → one PR. Bead id + verbatim title; 2–4 paragraphs of
context with `file:line` citations; numbered concrete steps; worktree
`<WORKTREE_ROOT>/<desc>-<BEAD_ID>`, branch `worker/<desc>-<BEAD_ID>`; the boundary
block; claim the bead; gates with exact commands; push + `gh pr create` titled
`<scope>(<artefact>): <summary> (<BEAD_ID>)` with the `## Quality gates` section;
report PR URL + per-step summary + test deltas. *A coverage/rigour pass must add
≥1 adversarial/negative case per surface — assertion-count growth alone only
exercises the happy path.*

**Shape 2 — Cluster.** Several small same-surface beads → one PR (see *Choosing solo
vs cluster* above for when — chiefly the small P3/P4 remainder, not P1s). Order commits
smallest-cleanup → biggest-correctness-fix (a failing bead must not strand the small
wins); claim each bead before its commit (history mirrors tracker state → a
stalled cluster leaves a clean partial trail); gates after each commit + full
regression after all. Disjoint-surface "small-misc" clusters are valid at the tail
of a drain — the binding rule is hot-zone parallelism, not strict same-surface.
Keep a cluster to ~3–6 small beads; beyond that, run successive cluster-PRs (each
opens with what it finished + lists the remainder, never a half-bead uncommitted).

**Shape 3 — Audit (read-only).** A finding, not a fix. Goal + surface paths +
prior findings to avoid re-discovering; boundary block; write the findings doc to
the gitignored working tree FIRST (never commit it, never link it from committed
files); file follow-on beads one at a time, appending each id to the audit bead's
notes so progress survives a timeout; close with verdict + severity counts +
cross-refs; no PR by default. The mayor may reorganize/reject findings — not every
finding is actioned.

**Shape 4 — Cluster reviewer (read-only, no dispatch).** Shapes the next wave. List
in-flight workers + their surfaces (don't recommend touching those); enumerate
recently-filed beads (`git log -p --since='35 minutes ago' -- <tracker-file>`) +
the ready queue; per bead decide (A) add to an in-flight cluster / (B) new cluster
(3+ beads, shared non-in-flight surface) / (C) solo (P0/P1 correctness, >250 LoC,
decision-resolved, cross-cutting) / (D) defer; output the net next-dispatch shape
in 2–3 sentences. Do NOT change tracker state.

**Shape 5 — Fix a PR's failing CI.** A red check that isn't structurally
irrelevant. Failing-check name + log lines; 2–3 root-cause hypotheses; worktree off
the EXISTING branch (not a new one); boundary block; **run the ACTUAL failing gate
locally** (not a proxy that already passed); fix surgically (or file a follow-on if
it's deeper than scope + the stance allows a safe-out); push to the existing branch
(never main); update its `## Quality gates`. *Never `--admin` past a failing
touched-surface gate.* Diagnosis often beats the failure log — test the hypothesis
before fixing.

**Shape 6 — Measurement window.** The five shapes above all tell a worker to
iterate until the gate goes green. A measurement worker must do the opposite, and
that inversion is the whole shape: it is not there to produce a number, it is
there to find out whether a trustworthy number can be produced at all. Pick it by
the kind of work, not by its size — a measurement bead is never a solo-vs-cluster
decision. The brief below was written from scratch twice in one day before it
lived here.

- **The controls are the arbiter, not the worker's judgement about the box.** A
  worker asked whether its own machine was quiet enough will always find a reason
  it was. If a control fails, or the arm-order guard exits 2 — a refusal, which is
  deliberately not a plain non-zero failure — the run refuses and the worker
  reports the refusal, without weighing it against how the box felt.
- **A refusal is a deliverable, not a failure.** Two of three window beads in one
  day came back as refusals and both were the correct outcome. If everything
  refuses, the window succeeded: you now know the rig cannot see what you hoped it
  would, which is what you dispatched it to find out. Say this in the brief, or the
  worker reads its refusal as its own failure and goes hunting for a way to turn
  it green.
- **Never "fix" a refusal by loosening a gate.** Every gate in a rig is there
  because something once passed that should not have. Widening a threshold to
  admit today's run retro-admits that failure too, and the series loses the one
  property that made it worth running.
- **Do not improve the rig mid-window.** No new instrument, no extra rungs, no
  third estimator, however obvious the improvement looks from inside the run. One
  worker declined to build a rung it genuinely needed, mid-series, on the grounds
  that *"a rung added between runs makes the series two instruments"* — the
  clearest statement of this rule anyone here has managed. File the improvement and
  run it as its own window.
- **Do not restate a published figure on thin evidence.** A worker holding four
  reportable runs, every one reading *below* both published figures, recorded them
  without publishing. That was right: four runs disagreeing with a number are a
  reason to look again, not a mandate to move it.
- **Verify the box with real counters, not the convenient one.**
  `Win32_Processor.LoadPercentage` read **93% while the true value was 11%**.
  `Get-Counter '\Processor(_Total)\% Processor Time'` and
  `'\System\Processor Queue Length'` agreed at ~11%, and a per-process delta
  corroborated at 2.8%. **Processor Queue Length is the decisive number** — it says
  whether anything is actually waiting for a core, which is the only thing the
  window cares about.
- **One run at a time, never concurrent.** Concurrency is precisely the contention
  the window exists to exclude, so two runs the worker believes are independent
  still are not.

Report what ran, what refused and on which control, the raw numbers, and — as its
own heading — what was **not** concluded. A window that publishes nothing still
reports everything.

## Failure modes these shapes close

- Back-compat shims by default → stance explicit in every preamble.
- Same-file races between concurrent workers → enumerate in-flight surfaces.
- Edits leaking into the mayor checkout (esp. silent new-file leaks) → boundary block + post-write both-trees check.
- Cross-worktree contamination via `git stash` → no-stash rule (stashes are repo-global).
- A peer silently overwriting a worker's scratch file, or an orphaned child of a killed gate writing over the run that replaced it — either way a worker reads the survivor as its own, including a gate exit code belonging to a different run, which merges on a green nobody earned → scratch files and gate artefacts named for BOTH the worktree and the attempt, plus the `gate root:` check, which is the half of that pair observed to actually catch it.
- Worktree cleanup deleting *through* a `node_modules` link into the shared tree it points at → the worker unlinks before reporting done, and the cleanup path disarms before removing.
- "Green locally" merged into a red CI gate → gate the transitive surface; merge on CI, not the hand-off; a real failure gets a fix-worker, never `--admin`.
- A passing synthetic test that routes around the real bug → reproduce the actual failing path.
- Clusters split that should be one PR (or vice-versa) → cluster reviewer pre-validates shape.
- Stalled workers losing analysis → findings-first + one-bead-at-a-time tracker creates.
- Re-discovering known issues → name recent landings + prior findings.
- A brief that was accurate when written but stale when read → bead-governs-the-brief, notes bottom-up.
- Generic prompts → require `file:line` citations + concrete fix sketches.
- A measurement worker iterating until the number looked right → Shape 6: the controls arbitrate, a refusal is a deliverable, and the rig does not change mid-window.

---

*Record three or four exemplary dispatches per project (a solo, a cluster, an
audit, a CI-fix) — a few good examples teach a new mayor more than thirty
mediocre ones. The concrete gate matrix, hot-zone list, guard/install scripts,
and cache-sharing setup are project-specifics; they live in the project's
agent-instructions + TESTING docs, not in this method file.*

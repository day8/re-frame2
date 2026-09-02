# Brief — Pilot 2: LinearLite

Copy the block below into `<pilot-root>/BRIEF.md`. Everything inside it is written *to the pilot agent* and is the only thing that agent is given, alongside the blank friction log and the workspace itself.

The brief is deliberately free of in-tree references: no bead ids, no spec sections, no repository paths except the ones the published documentation itself tells a reader to use, and one added since: `docs/core/hicasso/` inside the checkout, which is where the published documentation is kept until a site exists (`rf2-lpfz`). Naming it is what makes the pilot's only reference reachable at all. That is not tidiness. A brief that leaks in-tree knowledge does not bend a rule, it invalidates the evidence the pilot exists to produce, and the leak is invisible in the output.

Authorized by `rf2-v04s` under [`rf2-hic-063`](README.md#what-governs-this-directory)'s ratification; the sentence naming the test command, under `rf2-xkhul`; the address of the published documentation in the read rules, under `rf2-lpfz`. Assemble the workspace first, per [`workspace.md`](workspace.md).

**Why this pilot is shaped differently from [Pilot 1](brief-realworld.md), and it matters that it is.** Pilot 1 translates two screens that already exist, which measures whether the documentation supports *porting*. This one ports one screen and grows a second that does not exist yet, which measures whether the documentation supports *authoring* — reaching for a form you have never written, from the guide alone. Those are different failure modes and the two pilots are chosen to cover both, so the card detail is a shipped screen rather than a stretch goal and must not be softened into one when the brief is handed over.

---

```markdown
# Your brief

You are working on a small ClojureScript issue tracker, moving it onto a view
layer called Hicasso, using its published documentation and nothing else.

The application is a Linear-style board: cards you create, retitle, and drag
between statuses. Its point is that every write shows on screen instantly and
quietly reverts if the server rejects it. It runs offline against a fake
backend in the page, so there is nothing to set up. It is in `app/`, it
currently renders through Reagent, and it works today. Run it before you
change anything. Its behavioural tests are in `app/test/`; `npm test` from
`app/` runs them.

You have two screens, and they are two different jobs.

**The board** exists. Migrate it to Hicasso, preserving what it does now.

**The card detail** does not exist. Build it, on Hicasso, from scratch: click
a card, get a screen showing that issue in full, with its title editable and
its status changeable — the same operations the board offers, given room.
Route to it and back. It ships as part of this pilot; it is not a stretch
goal and it is not a sketch.

The second job is the more important of the two. Translating code you can
already see is one kind of task; writing something new against a framework you
have never used is another, and it is where documentation usually fails. Take
your time on it and log it heavily.

## The rule

**Everything you need is in the published Hicasso documentation. You may not
use anything else to learn how Hicasso works.**

This is the whole point of the exercise, so it is worth being exact about it.
The question being asked is not "can this be built" — of course it can. The
question is whether the published documentation is *sufficient on its own* for
somebody who has never seen this framework. You are the instrument that
measures that. Anything you learn from another source silently destroys the
measurement, and there is no way to detect it afterwards from the code you
produce. Only you can tell us.

**You may read:**

- The published Hicasso documentation, in full. It is your reference for
  everything: what to type, what things are called, why something broke, how
  to test, how to build for production. It has not been put on a website yet,
  so your copy of it is the one inside the checkout beside your project, at
  `re-frame2/docs/core/hicasso/`. Those pages are the documentation; reading
  them is not reading the checkout.
- Everything in `app/`. That is your codebase. Its README explains what the
  application does and which patterns it is built from — read it freely.
- Ordinary error output: compiler messages, stack traces, browser console.
  Including the file paths inside them.

**You may not read:**

- The `re-frame2/` checkout sitting beside your project, for anything other
  than the documentation named above and the two mechanical uses below. Not
  its source, not its internal design notes, not its other example
  applications, not its history.
- Any file your app's README links to outside `app/`. The README is yours; the
  things it points at are not.
- Any source file named in a stack trace. Knowing that a complaint came from
  some internal file is diagnosis and is fine. Opening that file is research
  and is not.

**The `re-frame2/` checkout is a build input, not a reference work.** The
documentation above is the one exception, and only because it has nowhere else
to live yet. Two further uses of it are expected and correct, because the
published documentation itself tells you to make them: your `deps.edn` resolves
the library from it, and the migration tool the documentation opens with is run
from a path inside it. Both are the documented setup. Neither is licence to
browse.

There is no trick here and you are not being tested for compliance. If you
read something you should not have, write it down in the log's attestation and
carry on. A pilot that reports an accidental look is far more useful than one
that hides it — we can weigh the affected entries and keep the rest. A hidden
one poisons everything.

**One temptation specific to your task.** When you build the card detail you
will want to see how a similar screen was written elsewhere, and there are
other example applications sitting in that checkout. Do not look. Copying a
worked example is exactly the shortcut a real adopter would not have and it
would answer the one question this pilot exists to ask. If the documentation
does not show you the shape you need, that is the finding — log it and ask.

## Ask when you are stuck — and log it

If the published documentation genuinely does not answer something, **ask a
human**. Do not spend an hour reverse-engineering it, and do not go and read
the source instead.

Asking is not failing. Every question you have to ask is a finding: it means
the documentation has a hole exactly there, and finding those holes is what
you are for. An unasked question that you solved by reading something you
should not have is a hole we never learn about, plus a corrupted result.

Record every question you ask in the log, with resolution `asked`.

## What you are producing

The two screens matter less than **the friction log**. That log is the
deliverable. `FRICTION-LOG.md` in your workspace has the format and a blank
template; read it before you start, not at the end.

Keep it as you go. A log written from memory afterwards loses precisely the
things worth having: the page you read twice, the term you had to look up, the
guess you made that turned out to be right. Especially the guesses. A guess
that works leaves no other trace — the code looks fine and the tests pass — and
it is the clearest possible signal that something is missing from the
documentation.

**A short log is a bad result, not a good one.** If you finish with three
entries we will assume the blinding leaked, because that is the more likely
explanation. Write down the small stuff.

## Definition of done

Seven outcomes. They are the whole scope of the pilot; nothing else is asked
of you, and you should not go looking for more.

1. **The application's behavioural tests still pass.** Run them before you
   start and capture the result — that baseline is what "still" means. Port
   them as the migration requires, keep them testing behaviour rather than
   markup, and add cover for the card detail you build.
2. **The board renders through Hicasso and the card detail ships.** Same
   behaviour for the board as a user sees today; a working, routed, editable
   card detail beside it. For the board, the published migration chapter has a
   process — follow it, including the step that generates a report before you
   port anything. For the card detail there is nothing to port, so it comes
   from the guide.
3. **Make one feature change, after the two screens are done.** Your choice,
   small, real. It has to be something other than the card detail — that one
   is a deliverable, not the change. This is what tells us whether the result
   is workable or merely working.
4. **Hot reload works.** Change a Hicasso view with the build running and
   record what survived and what did not.
5. **Diagnose one failure you induce on purpose, through the documented
   diagnostic path.** Break something the diagnostics chapter says it can find,
   then find it that way. Record whether the documented route actually reached
   it. Do not diagnose only the bugs you happened to write — those are
   accidents, and this outcome is about the instrument.
6. **A production build succeeds and the built page runs.** Both halves. A
   bundle that compiles and does not boot is the interesting failure.
7. **Move the version pin and upgrade across it.** Your setup pins the library
   to one commit; record it, then move it to a later one at the end and record
   what the move cost. A rename that surfaces as a compile error at your own
   call site is the documented behaviour, not a bug — say so if you meet one.

For each, record the verdict and the evidence in Part 1 of the log. Quote
exit codes you actually captured — redirect the command's output to a file and
echo its status — rather than ones you remember.

## Scope

Migrate the board, build the card detail. Leave anything else on Reagent; the
two view layers coexisting is expected and is itself worth observing.

Do not change the substrate adapter, and do not restructure how the
application stores its data or performs its writes — the instant-update
behaviour is the application's whole point and it is not what is under test.
Read the existing code for how a write is declared and follow it. If a
Hicasso view makes that awkward, that awkwardness is the single most valuable
entry you can log.

If you think the framework itself should change, write it down as friction and
keep going. Proposals are read as evidence about the surface, which is
useful — but designing the fix is not your job and doing it would cost us the
measurement.
```

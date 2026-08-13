# Native IME on Firefox and WebKit — the scripted witness

> **AND THE MANUAL SESSION THIS DOCUMENT HANDS ACCEPTANCE BACK TO WAS ITSELF RETIRED** (operator ruling, 2026-08-13).
> The session was attempted and abandoned — switching the machine's OS to Japanese proved impractical — and the
> operator ruled the Firefox and WebKit native-IME cells of
> [`product/dispositions.md` §2.3](product/dispositions.md#23-per-control-and-dom-conformance-dispositions) **green**,
> closing `rf2-hic-016` on that ruling. So where this document says acceptance goes back to the manual session — the
> 2026-08-12 note immediately below, [§1](#1-read-this-before-you-quote-it-as-coverage), [§9](#9-recording-the-results),
> [§11.5](#115-where-acceptance-goes) — read it as the disposition of **2026-08-12**, superseded the next day: there is
> no manual native-IME session outstanding and none is scheduled. **Nothing else in this document moves.** The three
> walls are findings about a machine and are unaffected by a later ruling about a programme, and the fence against
> further IME automation is if anything firmer than before.

> **THE AVENUE IS REFUSED, AND THIS IS THE RECORD OF HOW** (operator ruling, 2026-08-12). The rig was built to replace
> the human at the keyboard. Three armed runs established that the OS will not permit that on this machine — the modern
> Microsoft IME never composed for the Playwright windows, **not even under the operator's physical toggle**. Acceptance
> therefore goes back to the bounded manual session at
> [`native-ime-manual-witness.md`](native-ime-manual-witness.md), real-IME automation is **again** classified as
> over-engineering, and no further rig work or IME-automation attempt is sanctioned. **Read [§11](#11-the-outcome-and-the-three-walls) first**: it
> carries the three walls, their run evidence, and the amended close rule.
>
> **The rig is not deprecated and this document is not history.** It stays in the tree with its 43 teeth, because it is
> what found all three walls — each of them honestly, and none of them guessed at. Sections 1–10 describe it as built,
> in the present tense of its own design; read them as the record of a sanctioned attempt rather than as instructions
> to run one.

A **locally-run, machine-bound** witness that drives a **real** Windows IME from a script, in Playwright's pinned
Firefox and WebKit with Chromium as the already-verified control. It was built to replace the human in the bounded
native-IME session of [`native-ime-manual-witness.md`](native-ime-manual-witness.md) — an intent
[§11](#11-the-outcome-and-the-three-walls) records as refused. It does **not** replace a gate, because it was never one.

## 1. Read this before you quote it as coverage

| | |
|---|---|
| **Is** | the record of a **refused avenue** — a scripted attempt at the one bounded operator session, and the three walls it found ([§11](#11-the-outcome-and-the-three-walls)) |
| **Is not** | the acceptance path. That went back to the manual session at [`native-ime-manual-witness.md`](native-ime-manual-witness.md) on 2026-08-12, and on 2026-08-13 the operator ruled that session retired and the two cells green — so acceptance is a ruling, and no session is outstanding |
| **Is not** | a CI gate. It cannot become one |
| **Was built to need** | Windows, an **installed Japanese IME**, a visible desktop session, and the machine's keyboard focus. All were satisfied, and it still could not compose |
| **Recurring regression net** | unchanged — the synthetic three-engine gate `implementation/hicasso/testbed/spec.cjs`, driven by `serve-and-run-hicasso-controlled-testbed.cjs` in the required `cljs-hicasso-controlled` job, at **97 checks × 3 engines** per PR |

There is no hosted runner with a Japanese IME and a foreground window, so this script runs on a person's desktop, on
purpose, while they watch. **A green run here is a dated observation, not continuous coverage.** The synthetic gate is
what reds when someone breaks the composition carve-out tomorrow; this script is what says whether a real IME agreed
with it on the day it was run.

## 2. Why a script, when the ruling once said not to

> **Superseded on 2026-08-12.** The fence quoted below as overturned has been **restored**, and the manual session with
> it. What follows is why the script was sanctioned; [§11](#11-the-outcome-and-the-three-walls) is what the script then
> found. Both readings were correct at the time they were made.

The 2026-08-10 ruling settled real native-IME conduct with a bounded manual session and fenced automation off as
over-engineering. The 2026-08-11 ruling **overturned that fence** and directed the opposite:

> The manual one-time session is RETIRED as the acceptance path. The new direction: DRIVE A REAL IME FROM A SCRIPT.
> Mike explicitly overturns the earlier classification of real-IME automation as over-engineering. This work is
> sanctioned.
>
> **PRIORITY CASE**: check 5 (ESC abort mid-composition) — the one signature no page script can produce, which an
> OS-level script can.

What changed is not the difficulty. Synthetic DOM events still cannot produce a real composition range, and CDP's
`Input.imeSetComposition` is still Chromium's protocol. What changed is the acceptance of the one remaining door:
`SendInput`, the OS input stack, driving the real Microsoft IME the way a keyboard does.

## 3. What it will do to your desktop

`SendInput` does not aim. It delivers to whatever window has the **foreground**, so an armed run brings each browser
window to the front and types into it, and anything else that steals focus receives the rest.

Two interlocks, both in `implementation/scripts/lib/windows-ime-driver.ps1` rather than in its caller:

1. **`-Armed`.** The driver process is a separate PowerShell process, and without that switch every verb that could
   touch the desktop answers `REFUSED`. The rehearsal starts it unarmed, so the whole pipeline runs while the one call
   that types is refused **by construction** rather than by a branch the caller remembered to take.
2. **Foreground re-verification, per key.** Before *every* keystroke the driver re-reads `GetForegroundWindow()` and
   aborts the whole batch the moment it is not the window it was told to type into. Alt-tab mid-run and the run stops;
   it does not carry on typing romaji into your editor.

There is a third, upstream: the run refuses to type at all unless the browser window's **own thread** reports input
locale `0x0411` after the IME request — see §6.

## 4. Preconditions

- **Windows**, with a Japanese IME installed: *Settings > Time & Language > Language & region > Add a language >
  日本語*, then Microsoft IME in Hiragana. The run's preflight enumerates the installed input locales and **refuses to
  arm** when no `0x0411` layout is present, naming that path.
- `pwsh` (PowerShell 7) on `PATH`. Override with `RF2_PWSH`.
- `implementation/` dependencies installed (`npm ci`) and the pinned browsers installed.

**The pin is the working directory.** Every command runs from `implementation/`, because from the repository root
`npx` resolves a *newer* Playwright than the repo pins and prunes the pinned WebKit out of the shared browser cache —
the engine this witness exists to exercise is the one it removes. See
[`native-ime-manual-witness.md` §3.1](native-ime-manual-witness.md#31-the-pin-and-the-one-command-that-will-cost-you-an-afternoon).

```bash
cd implementation
npm ci
npx playwright install --with-deps chromium firefox webkit
```

## 5. The three modes

```bash
# 1. Verdict logic only. No browser, no server, no keystroke.
node scripts/run-hicasso-native-ime-witness.cjs --self-test

# 2. Full rehearsal. Compiles, serves, launches each engine HEADED, runs every
#    page-side step of every check — and types nothing.
npm run witness:hicasso-native-ime
node scripts/run-hicasso-native-ime-witness.cjs --dry-run

# 3. The witness proper. THIS TAKES THE KEYBOARD.
#    DO NOT RUN THIS — see §11. Three armed runs established the IME will not
#    compose for these windows, and no further attempt is sanctioned.
node scripts/run-hicasso-native-ime-witness.cjs --inject
```

Modes 1 and 2 remain useful and remain honest about what they prove; mode 3 is the refused one. Nothing below is
withdrawn — it is the design as built, and it is why the refusal in [§11](#11-the-outcome-and-the-three-walls) can be
stated as a fact about the IME rather than a suspicion about the script.

The rehearsal is the default, and it is a real rehearsal rather than a smoke test: it compiles the `:hicasso/testbed`
build, serves it, launches each engine headed, installs the observer, reads the trace table back through the DOM,
and executes **every** page-side step of every check — focus, caret placement, the armed bump, the armed unmount, the
blur, the reloads, every selector and every read. Because the driver is unarmed, every check then returns
`INCONCLUSIVE` against the named premise *"no composition ever started"*, which is the truth about a rehearsal. It
reports itself as a rehearsal and its verdicts say nothing about any engine.

That paragraph describes what a rehearsal does when it works, and for a while nothing checked that it had. Merged-PR
audit **#7956** found the rehearsal able to **exit 0 having driven no check at all**: `driveEngine` catches a prepare
failure into `results: []` — right conduct, so one engine's refusal cannot discard the other two — and `main` then
returned 0 for every non-inject mode regardless, printing *"every page-side step of every check ran"* over a run in
which none had. The same fail-open shape as [wall 2](#112-wall-2--the-open-status-gate-read-back-its-own-write), one
layer out. It is now measured rather than asserted: every requested engine must have produced a run, none may have
been refused in prepare, each must have driven the full roster, and **every check must carry its `READBACK`** — the
clause that separates a rehearsal whose selectors all resolved from one whose selectors silently returned `null`.
A rehearsal that fails any of those prints what is missing and **exits 1**.

Useful flags: `--engines=firefox,webkit`, `--key-delay=<ms>` (default 80), `--port=<n>` (default 8066),
`--keep-open`.

Every check prints a **`READBACK`** line under its verdict: the field value, the trace cell, the arrival count, the
caret, and the composition-event counts, exactly as they came out of the DOM. It is there because a verdict that
short-circuits on an unmet premise says nothing about whether the reads returned anything at all — a rehearsal in
which every selector silently resolved to `null` would print the same eight `INCONCLUSIVE`s as one in which they all
worked. **The `READBACK` line is what separates them**, and it is the line that found the defect in §7.

## 6. The IME is requested, then verified — never assumed

`IMEON` posts `WM_INPUTLANGCHANGEREQUEST` to the browser window and sends the IMM32 conversion-mode messages. The
modern Microsoft IME is a **TSF** text service reached through a compatibility layer, and a given browser build need
not honour any of it. So the request is never trusted: `IMESTATE` afterwards reads the input locale of the **browser
window's own thread**, and if it is not `0x0411` the run stops with the operator instruction (switch by hand with
Win+Space, or install the IME) rather than typing seven ASCII letters into a text box and calling it a composition.

That failure mode is not hypothetical. It is the most likely explanation of the observation in §8, and a witness that
could not tell the two apart is exactly the witness this replaces.

## 7. The eight checks

The semantics are the manual checklist's — [`native-ime-manual-witness.md` §5](native-ime-manual-witness.md#5-the-checks)
remains the prose statement of what each check means and why. This table is the mapping to code, and the **run order**,
which is not the numbering: check 5 goes first.

| Order | # | Check | Field | Decided on |
|---|---|---|---|---|
| 1 | 5 | Escape / abort mid-composition | `plain` | composition evidence + the field and trace either side of ESC |
| 2 | 1 | Draft visible during composition | `digits` | the draft on screen while the trace still reads `"123"` |
| 3 | 2 | Caret correct | `upper` | the draft is head + draft + tail at offset 1, caret inside it |
| 4 | 3 | Draft survives the model, mid-composition | `digits` | arrivals climb across two readings while committed does not move |
| 5 | 4 | Commit echo | `digits`, then `plain` | both policies, worse verdict taken |
| 6 | 6 | `compositionend` adds no intent | `plain` | arrivals either side of the Enter alone |
| 7 | 7 | Revision reset mid-composition | `revision-strict` | the draft stands while the armed bump fires |
| 8 | 8 | Blur / unmount mid-composition | `mountable` | no stranded draft, nothing thrown |

Three verdicts, and the third is the point:

- **TICK** — the claim held on this engine with a real IME.
- **CROSS** — it did not. A cross is a **finding**: it becomes a bead with the engine name on it. It is not a reason to
  re-run until green.
- **INCONCLUSIVE** — the check could not be decided because its **premise** was not met, most often that no composition
  ever started. An inconclusive check leaves the disposition cell unfillable; the run says so and exits non-zero.

The verdict functions are pure and carry **43 mutation teeth** (`--self-test`, which prints
`verdict logic teeth bit: 43`), because a witness whose verdict logic cannot be shown to fail is decoration. The teeth
cover each check's tick shape, its defect shape and its premise-not-met shape, plus the recording rule that a crossed
or incomplete run can never be written into `dispositions.md` as verified.

### What building this found, before it typed anything

Checks 7 and 8 reach their mid-exchange edge through the testbed's **armed** buttons, and the first rehearsal reported
`fired=false` for both. They had **never fired**. `:tb/arm` returned a v1-shaped top-level `:dispatch-later` beside
`:db`, and re-frame2's effect-map is a closed shape — `#{:db :rf.db/runtime :fx}` (migration M-8 / EP-0001) — so
nothing carried it. Measured: 15s after the click the readout still read `armed`, while a plain `setTimeout(5000)` in
the same page returned in 5006ms, so it was the effect and not the clock.

The two arms are the operator instruments added in PR #7815 and sharpened in #7846 *specifically* so a session could
reach a live composition, and the `armed-edges-are-wired` gate section was green in three engines the whole time: it
reads the label and asserts "nothing has happened yet", which is true of a correctly deferred arm and equally true of
one that never armed. Both are repaired here, and that section now **waits for the fire** — at a `?arm-ms=300`
override so it costs under a second per engine rather than the thirty the #7815 audit rightly refused. Reverting the
handler reds it with the diagnosis in the message.

## 8. Check 5, and the observation it exists to resolve

> **The observation is no longer this document's to resolve, and as of 2026-08-13 it is nobody's.** The 2026-08-12
> ruling handed it to the manual session's **check 5** ([`native-ime-manual-witness.md` §5](native-ime-manual-witness.md#5-the-checks));
> the 2026-08-13 ruling retired that session unrun, so the observation **stands unresolved** rather than pending. It is
> not owed and no work is scheduled against it. What follows is why the script would have been the sharper instrument
> for it, which remains true and is part of why the avenue was worth trying.

During the operator's partial manual session on 2026-08-11, typing in the `plain` field and pressing ESC **discarded
nothing — the draft stayed**. The engine was not recorded and the underline / candidate-window discriminator was never
answered. Two readings fit that observation and they are opposite:

- the IME never engaged, so `nihongo` went in as seven ASCII letters and ESC had no composition to abort. **Nothing
  about Hicasso was measured.**
- the IME did engage and the abort left the draft standing. **A real defect, in a named engine.**

A checklist read off a screen cannot separate them, which is why the observation went unresolved. A script can, because
the discriminator is in the event stream rather than on the screen. A real exchange fires `compositionstart`, carries
`isComposing` on its `input` events, and delivers ESC to the page as `keydown` with `key === 'Process'` (keyCode 229) —
the IME ate the key. So check 5 runs **first**, records that evidence, and returns:

| Evidence | Verdict | What it means |
|---|---|---|
| no `compositionstart`, no composing `input` | INCONCLUSIVE | the benign reading. The rig, not the runtime |
| real exchange, field back to `"abc"` | TICK | the abort discarded the draft, as the law says |
| real exchange, field unchanged | CROSS | **reproduces the observation and refutes the benign reading** — file the bead |
| real exchange, some other residue | CROSS | a fragment of the abandoned composition survived |

The observer that supplies this evidence is a capture-phase listener installed with `addInitScript` before the app
mounts — the same door `spec.cjs` uses. It reads; it never dispatches, never mutates, never touches a value. The
testbed app is unmodified: the same bundle a human would open, and the trace table is still the primary observable.

## 9. Recording the results

> **No cell is ever filled from this rig.** It never reached a definite verdict on any engine, and by the 2026-08-12
> ruling it will not be run again. That much is unchanged. What has changed is where the cells came from instead: the
> 2026-08-12 answer was the manual session, and the **2026-08-13 ruling retired that session and filled the cells
> green itself**. The numbered close rule below is therefore the amended rule of 2026-08-12 and not what happened; see
> [§11.5](#115-where-acceptance-goes).

1. The run prints, per engine, a ready-made cell for
   [`product/dispositions.md` §2.3](product/dispositions.md#23-per-control-and-dom-conformance-dispositions) with the
   engine build and the date. It says **Witness-verified** only when every check on the roster reached a definite
   verdict and none crossed; a crossed run reads **Divergence** and an undecided one reads **Not established**. Across
   three armed runs it printed no such cell, because no run was ever complete.
2. **One bead per finding**, with the engine name on it. Unchanged as a rule; the manual session it was extended to
   never ran.
3. `rf2-hic-016` closes when the **operator's manual session** results for **Firefox and WebKit** are recorded in
   §2.3 with **date and engine builds**. **Chromium stays the witnessed control.** — *This was the rule as of
   2026-08-12 and it is not how the bead closed. The session was abandoned, and on 2026-08-13 the operator closed
   `rf2-hic-016` on a ruling that carries both cells green.*

If an engine turns out not to accept Windows IME input at all — the Playwright WebKit shell is the plausible
candidate — **that is itself the finding.** Record it, file the bead, and do not go looking for a way around it. That
sentence was written before [§11](#11-the-outcome-and-the-three-walls) and it is the rule the ruling followed: what the
armed runs found was not a shell that resists a script but a **text service that will not engage for these windows at
all**, and the answer was to record it and stop, not to route around it.

## 10. Files

| Path | What it is |
|---|---|
| `implementation/scripts/run-hicasso-native-ime-witness.cjs` | the orchestrator: preflight, teeth, compile, serve, headed launch per engine, report |
| `implementation/hicasso/testbed/native-ime-witness.cjs` | the eight checks, the page observer, and the pure verdict functions |
| `implementation/scripts/lib/windows-ime-driver.ps1` | the OS-level driver: window lookup, IME request/interrogation, `SendInput`, and both interlocks |

All three stay in the tree. None is deprecated; see [§11.6](#116-what-becomes-of-this-rig).

## 11. The outcome, and the three walls

The rig was sanctioned to replace the human at the keyboard. **It cannot, on this machine** — and the reason is not
that the rig is unfinished. Three armed runs on 2026-08-12, each from an interactive desktop session and each against a
freshly repaired rig, walked into three separate walls, and **the rig found every one of them itself**. That is what
makes the avenue closable on evidence rather than abandoned on suspicion: at no point did it type into a window it had
not verified, keep calling an unengaged IME engaged once the fault was named, or offer a disposition cell from a run
that had decided nothing.

### 11.1 Wall 1 — the foreground cannot be seized from a non-interactive session

The first armed attempt was run from a background agent session on the evening of 2026-08-11, and every check of every
engine came back the same way:

```
KEYS aborted: "foreground window changed after 0 key(s); batch aborted"
"foreground":70198252,"isForeground":false
```

The window was found — hwnd matched, title matched, one match per engine — and `SetForegroundWindow` simply did not
take. Windows restricts foreground seizure to a process that is already the foreground process, which a background
harness is not. The per-key interlock of [§3](#3-what-it-will-do-to-your-desktop) therefore refused after **zero**
keystrokes, reported all eight checks INCONCLUSIVE, printed *"WITNESS INCOMPLETE — the disposition cells cannot be
filled from this run"*, and exited 1.

**This wall was solved, and it stayed solved.** The operator ran the rig interactively and every later run read
`isForeground: true` on all three engines, with romaji and ESC both proven to arrive. Wall 1 is a precondition the rig
did not know it had; it is not why the avenue closed. It is here because a rig that had typed anyway would have been
far worse than one that refused.

### 11.2 Wall 2 — the open-status gate read back its own write

Two runs, one fault, and the second telling is the sharp one.

The **first interactive run** (2026-08-12, against the rig as of PR #7948) delivered keys and still decided nothing.
`IMESTATE` read `langid 0x0411` and `japanese: true` — and `open: 0` on **every** engine. The modern Microsoft IME is a
TSF text service reached through an IMM32 compatibility layer, and it had ignored the `IMC_SETOPENSTATUS` request that
`IMEON` posts, exactly as the driver's own header warns it may. The romaji landed as plain ASCII, no composition ever
started, and all **24** checks — eight × three engines — returned INCONCLUSIVE against the named premise. PR #7952
answered it by sending the IME's own toggle key through the proven `SendInput` path and **requiring `open: 1`** before
any plan could be driven.

The **second interactive run** (2026-08-12, against PR #7952) produced the finding that generalises past this rig.
Chromium reported `open=1`, `conversion=9`, `native=true`; the rig printed **IME ENGAGED**; its delivery probes read
1/1 — and `compositionstart` and `compositionupdate` stayed at **0 on every check**, with the romaji sitting in the box
as literal ASCII. `conversion=9` is `IME_CMODE_HIRAGANA`, the exact constant the driver had just written. The IMM32
shim's state is **write-through**: `require open:1` was reading back the bit it had itself set, and could not have
failed.

> **A check that reads back its own write is not evidence.** It was a fail-open sitting *inside* the guard built to
> prevent one.

**PR #7956** repaired it into a **conduct probe**: type one romaji letter into the `plain` field and require
`compositionstart > 0` from the page's own observer — an event the browser emits in response to real composed input,
and one that nothing this rig writes into an input context can produce. The IMM32 reading became a log line and never
the gate again.

The same run surfaced a second, independent fault wearing the first one's clothes. Firefox read `open: 0` after `IMEON`
plus two toggles and **aborted honestly with nothing typed** — the right conduct — but the throw killed the whole
batch, so **WebKit never launched**. Honest abort is correct per *engine*; per *batch* it discards two engines' worth of
evidence to report one engine's failure. #7956 caught engagement failure at the engine boundary and carried on.

### 11.3 Wall 3 — the IME never composed for the Playwright windows, under any hand

With conduct as the gate, the third run answered the only question left, and answered it against the avenue.

**The modern Microsoft IME never composed for the Playwright browser windows.** Not from `IMEON`. Not from an injected
半角/全角. And **not from the operator's physical toggle at the keyboard**: the IME badge stayed **"A"** —
alphanumeric — for the focused browser window. The "previous version" compatibility engine, tried next, reported
**"Japanese IME not ready"** pending a session restart.

> **One claim here was narrowed after the fact, and it costs this wall nothing.** This section used to say the
> injected toggle *carried a correct scan code*. Merged-PR audit **#7956** established that it did not: `SendKey`
> populates `wScan` but never sets `KEYEVENTF_SCANCODE`, and Microsoft's
> [`KEYBDINPUT`](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-keybdinput) contract is that
> the flag is what makes `wScan` identify the key and `wVk` ignored. So the literal `0x29` was **calculated and
> reported, never delivered as the key's identity**, and the injected toggle is evidence of nothing. The wall stands
> on the clause after it — **the operator's physical toggle**, a real keystroke from real hardware with no rig
> between it and the text service, which left the badge on "A" anyway.

That last clause is what makes this wall decisive. It is not the rig failing to drive the IME. It is **the IME
declining to engage for those windows at all, including under a human hand on the physical key.** There is no
rig-shaped thing left between the keystroke and the text service, so no further rig work reaches past it.

### 11.4 The proportion, and the ruling

The target was always **one-time evidence for two disposition cells** — Firefox and WebKit, once — never a recurring
gate. The recurring net is untouched and green: the synthetic three-engine gate, **97 checks × 3 engines on every PR**.
Continuing to fight the Windows text-input service to fill two cells is out of proportion to the two cells, and on
**2026-08-12** the operator ruled it exactly so. Real-IME automation is **again** classified as over-engineering, the
2026-08-11 ruling quoted in [§2](#2-why-a-script-when-the-ruling-once-said-not-to) is superseded, and no further rig
work or IME-automation attempt is sanctioned.

### 11.5 Where acceptance goes

> **Where it actually went (operator ruling, 2026-08-13).** The bounded manual session named below was attempted and
> abandoned — switching the machine's OS to Japanese proved impractical — and the operator ruled the Firefox and WebKit
> native-IME cells **green** and closed `rf2-hic-016` rather than hold the programme on a session that will not happen.
> So the destination this section hands acceptance to no longer exists as an obligation: no session is scheduled, none
> is owed, and the paragraphs below record where 2026-08-12 pointed rather than where the record now stands. The
> expectation stated further down — that the first thing the session must record is whether the IME engages at all —
> was never put to the test, and the honest reading of wall 3 is unchanged by that.

**Back to the bounded manual session.** [`native-ime-manual-witness.md`](native-ime-manual-witness.md) — with its trace
table, its armed buttons and its discriminating check 7 — **is** the witness. Eight checks per engine, Firefox and
WebKit, once.

What the 2026-08-11 partial session establishes about that path is narrower than this section once claimed, and the
narrower reading is [§8](#8-check-5-and-the-observation-it-exists-to-resolve)'s own: typed **drafts reached the field**
in these Playwright shells. Whether any of it was a **real composition** was never discriminated — the engine went
unrecorded and the underline / candidate-window question unanswered — and §8 lists "the IME never engaged, so the
romaji went in as ASCII" as one of the two readings that still fit. So the session is not yet proof that manual
hardware typing composes in these shells, and given wall 3 the honest expectation is that **the first thing the manual
session must record is whether the IME engages at all.** That is the finding either way: an engine in which the
Playwright shell will not accept Windows IME input is a recorded cross and a bead, not a reason to re-run.

The close rule as amended on 2026-08-12 — **which is not the rule the bead closed under**:

> `rf2-hic-016` closes when the operator's **manual session** results for **Firefox and WebKit** are recorded in
> [`product/dispositions.md` §2.3](product/dispositions.md#23-per-control-and-dom-conformance-dispositions) with
> **date and engine builds**. **Chromium stays the witnessed control.** Any oddity is **one bead per finding, with the
> engine name on it.**

**What closed it, on 2026-08-13.** An operator ruling, with no session behind it. The cells carry green by that ruling;
`rf2-hic-016` is closed; the manual session is retired unrun. What the bead delivered *by measurement* is separate and
stands: the three-browser matrix under PR #7992 — echo, rejection, caret preservation, selection range and direction,
revision reset, and the blur and unmount edges — plus the recurring synthetic gate, neither of which this ruling
touches.

The operator's unresolved 2026-08-11 ESC observation ([§8](#8-check-5-and-the-observation-it-exists-to-resolve)) was to
have been resolved by the manual session's **check 5**. With that session retired it is not resolved and is not owed to
anyone: it stays recorded here as an observation whose two readings were never discriminated.

### 11.6 What becomes of this rig

**It stays in the tree, with its 43 teeth and its findings.** Nothing here is deprecated, withdrawn or apologised for.
It is **documentation of a refused avenue** — and the avenue is refused *on evidence* precisely because this rig
produced the evidence. Read back through the three walls and the pattern is the same each time: the foreground
interlock refused rather than sprayed; the readback fail-open was caught because the rig reported `compositionstart: 0`
honestly beside its own "IME ENGAGED"; and the conduct probe is what let wall 3 be stated as a fact about the IME
rather than a suspicion about the script.

A rig that had typed anyway, or ticked on a bit it had itself set, would have left two disposition cells filled with
nothing at all. This one left them empty and said why — three times.

# Native IME on Firefox and WebKit — the scripted witness

A **locally-run, machine-bound** witness that drives a **real** Windows IME from a script, in Playwright's pinned
Firefox and WebKit with Chromium as the already-verified control. It replaces the human in the bounded native-IME
session of [`native-ime-manual-witness.md`](native-ime-manual-witness.md). It does **not** replace a gate, because it
was never one.

## 1. Read this before you quote it as coverage

| | |
|---|---|
| **Is** | a scripted, repeatable version of the one bounded operator session, whose results fill the two pending cells in [`product/dispositions.md` §2.3](product/dispositions.md#23-per-control-and-dom-conformance-dispositions) |
| **Is not** | a CI gate. It cannot become one |
| **Needs** | Windows, an **installed Japanese IME**, a visible desktop session, and the machine's keyboard focus |
| **Recurring regression net** | unchanged — the synthetic three-engine gate `implementation/hicasso/testbed/spec.cjs`, driven by `serve-and-run-hicasso-controlled-testbed.cjs` in the required `cljs-hicasso-controlled` job |

There is no hosted runner with a Japanese IME and a foreground window, so this script runs on a person's desktop, on
purpose, while they watch. **A green run here is a dated observation, not continuous coverage.** The synthetic gate is
what reds when someone breaks the composition carve-out tomorrow; this script is what says whether a real IME agreed
with it on the day it was run.

## 2. Why a script, when the ruling once said not to

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
node scripts/run-hicasso-native-ime-witness.cjs --inject
```

The rehearsal is the default, and it is a real rehearsal rather than a smoke test: it compiles the `:hicasso/testbed`
build, serves it, launches each engine headed, installs the observer, reads the trace table back through the DOM,
and executes **every** page-side step of every check — focus, caret placement, the armed bump, the armed unmount, the
blur, the reloads, every selector and every read. Because the driver is unarmed, every check then returns
`INCONCLUSIVE` against the named premise *"no composition ever started"*, which is the truth about a rehearsal. It
reports itself as a rehearsal and its verdicts say nothing about any engine.

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

The verdict functions are pure and carry **35 mutation teeth** (`--self-test`), because a witness whose verdict logic
cannot be shown to fail is decoration. The teeth cover each check's tick shape, its defect shape and its
premise-not-met shape, plus the recording rule that a crossed or incomplete run can never be written into
`dispositions.md` as verified.

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

1. The run prints, per engine, a ready-made cell for
   [`product/dispositions.md` §2.3](product/dispositions.md#23-per-control-and-dom-conformance-dispositions) with the
   engine build and the date. It says **Witness-verified** only when every check on the roster reached a definite
   verdict and none crossed; a crossed run reads **Divergence** and an undecided one reads **Not established**.
2. **One bead per cross**, with the engine name on it.
3. `rf2-hic-016` closes when the witness has run on Firefox and WebKit, the results are in §2.3, and any divergence is
   filed.

If an engine turns out not to accept Windows IME input at all — the Playwright WebKit shell is the plausible
candidate — **that is itself the finding.** Record it, file the bead, and do not go looking for a way around it.

## 10. Files

| Path | What it is |
|---|---|
| `implementation/scripts/run-hicasso-native-ime-witness.cjs` | the orchestrator: preflight, teeth, compile, serve, headed launch per engine, report |
| `implementation/hicasso/testbed/native-ime-witness.cjs` | the eight checks, the page observer, and the pure verdict functions |
| `implementation/scripts/lib/windows-ime-driver.ps1` | the OS-level driver: window lookup, IME request/interrogation, `SendInput`, and both interlocks |

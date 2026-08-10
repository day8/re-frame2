# Native IME on Firefox and WebKit — the one bounded manual witness

A checklist for a single operator session. It is run **once**, by hand, and its results are written into
[`product/dispositions.md` §2.3](product/dispositions.md#23-per-control-and-dom-conformance-dispositions). It is not a
gate, it does not run in CI, and nothing here is scheduled to repeat.

## 1. Why this is a manual session and not a test

`rf2-hic-016` proved invariant I15 — the §4.2 accept / refuse / normalise / reset policy family — in three real engines.
What its gate drives on Firefox and WebKit is the *event sequence* a composition produces (`compositionstart`,
`beforeinput` and `input` carrying `isComposing`, `compositionend`), dispatched at the real node through real React.
That reaches every mechanism the composition carve-out is made of, but it does not reach the browser's composition
**range**, because `Input.imeSetComposition` is a CDP method and CDP is Chromium's protocol.

The operator ruled on that gap on 2026-08-10, and the ruling is what this document exists to serve:

> **OPERATOR RULING (2026-08-10 19:47 AUSEST, Mike, in session) — OPTION 3: AMEND + ONE BOUNDED MANUAL WITNESS.**
>
> (1) AMENDED ACCEPTANCE: the synthetic composition sequence IS the recurring three-engine CI witness (ratified; the
> landed 55-check/13-teeth gate stands as-is). Real native-IME conduct on Firefox and WebKit is verified ONCE by a
> manual operator session, recorded, not automated, not recurring.
>
> (2) THE MANUAL WITNESS: the operator types through a real Windows IME (e.g. Japanese Microsoft IME) in Playwright's
> Firefox and WebKit builds against a written checklist … Results are recorded in the hic-005 support table with date +
> engine builds. Anything strange becomes a bead.
>
> FENCES: no changes to the landed gate's semantics; no automation attempts at real IME (ruled over-engineering).

So the recurring witness is the synthetic sequence, and it is enough. This session answers one narrower question the
synthetic sequence cannot: does a **real** IME, holding a **real** composition range, behave the same way?

**Do not try to automate this.** Driving a real IME from a script was considered and ruled over-engineering. If the
session is awkward, that is the expected cost of running it once.

## 2. What you need

- Windows, with a Japanese IME installed and reachable from the language bar — Microsoft IME is the reference. Any IME
  that composes from kana into candidate kanji will do; the point is a multi-keystroke composition with a candidate
  window, not the specific language.
- A checkout with `implementation/` dependencies installed (`npm ci`), and the pinned browsers installed — §3.

## 3. Build the page, serve it, and open it

Every command below runs **from `implementation/`**, and that is load-bearing rather than a convention. Read §3.1
before running the install.

```bash
cd implementation
npm ci
npx playwright install --with-deps chromium firefox webkit
```

Then build the testbed and serve it:

```bash
npx shadow-cljs compile :hicasso/testbed
cp hicasso/testbed/index.html out/hicasso-testbed/index.html
npx http-server out/hicasso-testbed -p 8065 -c-1
```

The page to open is **`http://127.0.0.1:8065/`**. Leave the server running in its own terminal.

These are the same three steps the gate's own launcher
(`implementation/scripts/serve-and-run-hicasso-controlled-testbed.cjs`) performs before it drives anything — compile
the `:hicasso/testbed` build, stage the HTML beside the bundle, serve the directory — done by hand so the page stays up
for as long as the session needs it. The launcher is not modified and is not used here; running the gate would drive
its own automated spec and tear the server down.

### 3.1 The pin, and the one command that will cost you an afternoon

**Never run a bare `npx playwright install` from the repository root.** From the root, `npx` resolves a *newer*
Playwright than the repo pins, fetches that release's browser revisions, and **prunes the pinned WebKit out of the
shared cache** — so the engine this session exists to exercise is the one it removes. `--no-install` does not save you;
only the working directory does.

Two facts are the entire pin: the working directory is `implementation/`, and `npm ci` has already run. Together they
make `npx` resolve `implementation/node_modules/.bin/playwright`, which is the version `implementation/package.json`
pins. Measured on this repo:

| run from | `npx playwright --version` |
|---|---|
| `implementation/` | **1.59.1** — the pinned CLI |
| repository root | 1.62.1 — resolved from npx's own cache |

This is the same pin the CI lane carries; see the `Install pinned Chromium + Firefox + WebKit` step of the
`cljs-hicasso-controlled` job in `.github/workflows/test.yml`, and `rf2-ga8m`.

### 3.2 The two launch commands

From `implementation/`, with the server from §3 still running:

```bash
npx playwright open -b firefox http://127.0.0.1:8065/
```

```bash
npx playwright open -b webkit http://127.0.0.1:8065/
```

Run one at a time and work the whole checklist through before moving to the other engine. Each opens a real, headed
browser window that the Windows IME can type into like any other.

Under the pinned Playwright 1.59.1 these launch:

| Engine | Playwright build | Version |
|---|---|---|
| Chromium | `chromium-1217` | 147.0.7727.15 |
| Firefox | `firefox-1511` | 148.0.2 |
| WebKit | `webkit-2272` | 26.4 |

Confirm the versions at the top of the session rather than trusting this table — a browser install between now and then
moves them, and the recorded result is only worth what its engine build is.

## 4. Reading the store without a console

Every check below is observable **from the screen**, and that is deliberate: Playwright's WebKit build on Windows is a
minimal browser shell with no devtools, so a checklist that depended on a JavaScript console would be unrunnable in
exactly the engine it most needs to cover.

The testbed puts the store on screen instead. Two instruments do it, and each check below names which one it reads.

**The trace table** (`data-testid="trace"`) carries one row per field, with two columns that answer different
questions: the **committed** value, `pr-str`-ed so that `""` and a trailing space are visible
(`trace-<field>-value`), and the number of `:tb/edit` intents that have **arrived** for that field
(`trace-<field>-edits`). Checks 3 and 6 turn on the difference between them. An intent that arrived and did not move
the committed value is a model that **refused** it — which, on a page of `<input>`s, looks exactly like an intent that
was never dispatched at all. Only the arrival count separates the two, and it is the reason the table exists.

**The fields themselves**, whose model policies are the rest of the instrument:

| Field | Model policy | What it shows you |
|---|---|---|
| `plain` | takes what is typed | the model **accepted** — the field keeps what you committed |
| `digits` | refuses anything that is not a digit | the model **refused** — the field snaps back to its committed value |
| `empty` | refuses everything; the model stays `""` | the same, at the hardest setting |
| `revision-strict` | refuses anything that is not a digit, and carries `::h/revision` | a revision reset whose **target differs from the draft on screen** — which is what makes check 7 readable at all |

So "the model refused it" is not something you have to take on trust: compose kana into `digits`, and watch its trace
row show the arrival count climbing while **committed** stays `123`. The draft on screen is the IME's, held in front
of React's own restore; the model saw every update of it and kept none.

The trace table and the armed buttons of checks 7 and 8 arrived with **PR #7815**, which measured the composition
conduct these checks now assert. Its `an-accepting-model-during-a-composition` and
`a-revision-arriving-mid-composition` sections are the reference for what the runtime does; this checklist asks only
whether a **real** IME behaves the same way.

The `revision-strict` field and the event-naming `armed` readout arrived with the follow-up to that PR, and both are
here because of the same finding: a check has to be able to FAIL. On the accepting `revision` field the reset's target
and the composing draft are the same string, so watching the draft stay put proves nothing — check 7 therefore reads
`revision-strict`, whose model refuses. The `armed` readout now names the event it has queued
(`armed: unmount -> [:tb/toggle-mounted] fires in 5s`) rather than only that something is queued, so an arm wired to
the wrong thing is visible before the five seconds elapse.

Where a build does offer devtools (Firefox's usually does; WebKit's does not), `window.__RF2_HIC_TB__.model()` returns
the store as JSON and is a direct cross-check. Treat it as a convenience, never as the check itself.

## 5. The checks

The seed values are `plain` = `abc`, `digits` = `123`, `upper` = `ABC`, `revision` = `keep`,
`revision-strict` = `42`, `mountable` = `9`. Reload the page between checks if you lose track of state.

**1 — Draft text visible during composition.** Click into `digits` and begin composing kana (e.g. type `nihongo`).
*Pass:* the composing draft is visible in the field, underlined, while the candidate window is open — even though the
model refuses every character of it.

**2 — Caret correct.** Click into `upper`, place the caret between `A` and `B`, and compose there.
*Pass:* the draft appears at the caret, not at the end of the string, and the caret stays inside the draft as it grows.
The end-of-string jump is the failure this check exists to catch.

**3 — The draft survives the model, mid-composition.** With a composition open in `digits`, watch the field and its
trace row before committing.
*Pass:* the draft stays on screen — no flicker of `123` replacing it, no snap-back before the exchange closes — while
the row's **intents arrived** count climbs and **committed** stays `123`. Every composing update reached the store and
the model refused every one of them.

*Not a pass criterion: a still store.* Hicasso does not withhold composing updates from the handler you wrote; it
withholds the **write-back to the screen**. Compose in `plain`, whose model accepts, and its committed cell moves with
every composing keystroke. That is correct, and it is what #7815 measured
(`an-accepting-model-during-a-composition`). A tester who expects a frozen store here will read a working
implementation as broken.

**4 — Commit echo.** Commit the composition (Enter or a candidate selection) in `digits`, then repeat in `plain`.
*Pass:* `digits` snaps back to `123` — the committed value, echoed in the same turn. `plain` keeps the committed kanji.
Two fields, two policies, one law.

**5 — Escape / abort mid-composition.** Begin composing in `plain`, then press Escape before committing.
*Pass:* the draft is discarded, the field returns to `abc`, and the trace row's **committed** cell reads `"abc"` again
— no fragment of the abandoned composition survives anywhere. This is an **end-state** claim, per check 3: an accepting
model took each composing update on the way in, so the arrival count will not go back down, and it should not. The
abort signature is the one part of a composition no page script can produce in any engine, which is why this check
needs a human and a real IME.

**6 — `compositionend` adds no intent of its own.** Compose in `plain` and watch its trace row across the moment of
commit.
*Pass:* the **intents arrived** count rises **once per composing keystroke** while the exchange is open, and the commit
adds **none** to it. What the close does is converge the field to whatever the model then holds. The failure is a
double-application: the count jumping at the commit, or the committed text landing twice in the field.

Note what this check does *not* claim, and check 3 explains why: the committed text does not arrive once at the close.
It arrives progressively, one intent per composing `input`, and `compositionend` contributes nothing further.

**7 — Revision reset during and after composition.** Press **arm bump (5s)** — the `armed` readout should read
`armed: bump -> [:tb/bump-revision] fires in 5s` — then click into **`revision-strict`** and begin composing kana
before it fires. Repeat with the immediate **bump** button *after* committing.
*Pass:* mid-composition the reset **defers to the exchange's close**: the draft is untouched while the composition is
open, and it is not destroyed under the user. After commit, the reset re-baselines the field to the model on the same
DOM node.
*Read `revision-strict`, not `revision`, and the reason is the whole of what makes this check readable.*
`revision-strict` **refuses** the kana, so while you compose, its committed cell still reads `"42"` and the reset has
`42` to write while the field is showing `42あ…`. A reset that landed immediately would put `42` on the screen under
you and kill the composition, so watching the draft stand IS the observation. On the accepting `revision` field the
model has already taken the draft, so a deferred reset and an immediate one write the identical string and nothing you
can see distinguishes them — which is the non-discriminating criterion the #7815/#7817 audits caught here.
*What is not claimed:* that the reset wins. Compose in `revision` afterwards to see the limit: its model **accepts**,
so the composing updates it kept taking supersede the reset by ordinary event order and the field converges to the
composed text rather than to `keep`. The deferral cannot strand the field; it does not make the reset unlosable
(#7815, `a-revision-arriving-mid-composition`).
*Why armed:* a real pointer-down on a button closes the composition before the click can land inside it, so the
mid-composition arm is not reachable by hand any other way.

**8 — Blur and unmount mid-composition.** Begin composing in `mountable`, then click away (or Tab) without committing.
Then reload, press **arm unmount (5s)** — the readout should read `armed: unmount -> [:tb/toggle-mounted] fires in 5s`
— and begin composing in `mountable` before it fires.
*Pass:* no stranded draft — the field (or its replacement) shows committed state, and nothing throws. `mountable`
refuses non-digits, so its trace row's **committed** cell still reads `9` while its arrival count records the updates
it turned down; that is what makes "no stranded draft" a claim about the teardown rather than about a model that
happened to accept the draft.

## 6. The result table

Tick both engines for each check. A cross is as valuable as a tick — it is the finding.

| # | Check | Firefox | WebKit | Note |
|---|---|---|---|---|
| 1 | Draft text visible during composition | [ ] | [ ] | |
| 2 | Caret correct | [ ] | [ ] | |
| 3 | Draft survives the model, mid-composition | [ ] | [ ] | |
| 4 | Commit echo | [ ] | [ ] | |
| 5 | Escape / abort mid-composition | [ ] | [ ] | |
| 6 | `compositionend` adds no intent of its own | [ ] | [ ] | |
| 7 | Revision reset during / after composition (`revision-strict`) | [ ] | [ ] | |
| 8 | Blur / unmount mid-composition | [ ] | [ ] | |

Session date: ______________  Firefox build: ______________  WebKit build: ______________

## 7. What to do with the results

1. Write the outcome into
   [`product/dispositions.md` §2.3](product/dispositions.md#23-per-control-and-dom-conformance-dispositions), in the
   native-IME block appended there, with the date and both engine builds.
2. **Anything strange becomes a bead** — per the ruling. A cross in the table is not a reason to re-run the session
   until it passes; it is a defect report with an engine name on it.
3. `rf2-hic-016` closes when these results are recorded. Until then it stays open, whatever else has landed against it.

If an engine turns out not to accept Windows IME input at all — the Playwright WebKit shell is a plausible candidate —
that is itself the finding. Record it as such and file the bead. It is not a reason to start automating.

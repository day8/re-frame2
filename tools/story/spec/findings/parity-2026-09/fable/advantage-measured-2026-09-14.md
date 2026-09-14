# The advantage thesis, measured — three falsification journeys on Story and Storybook 10.6 (rf2-a1v8a)

> **What this is.** The measurement the parity research ([`report.md`](report.md) §4.4) adopted as its
> next experiment, and the result [`023-Parity-Test.md`](../../../023-Parity-Test.md) §9 now points at.
> Three journeys, each walked on Story and on Storybook 10.6 against the same planted defect, counted
> gesture by gesture. **Headline: no journey is an ergonomic win for Story yet.** The structural surplus
> is real and visible at several steps, but on these walks it cost more gestures, reloads and hand
> translations than Storybook, and the third journey could not run on Story at all from the measuring
> session.
>
> **Bead:** rf2-a1v8a, under epic rf2-0ae7o. **Measured:** 2026-09-14, Story at trunk `27d84c5a19`,
> Storybook 10.6.0 from astra's tracked fixture. **Evidence:** [`evidence/journeys/`](evidence/journeys/).

## 1. Set-up, and what the numbers mean

**The planted defect** is one bug, planted uncommitted on both sides: *the login submit does not keep
the submitted email, so the authenticated greeting is empty.*

- **Story.** In [`login_form/events.cljs`](../../../../testbeds/login_form/events.cljs), the machine's
  `:remember-credentials` action, the `:login/submit` transition's action out of `:idle`, returned
  `{:data (assoc data :error nil)}` in place of `{:data (assoc data :email email :error nil)}`.
- **Storybook.** In the fixture's `Login.tsx`, `submit()` called `setUser("")` in place of
  `setUser(result.email)`.

**Restores.** Story's testbed files were restored and verified by blob hash against `HEAD`. The
default `git hash-object` form matched for `events.cljs` and for `stories.cljs`. The Storybook copy
lived in a scratch folder outside the repository; each restore copied the original bytes and matched
their sha256. One caveat on that side: the plant was made with `sed`, which also rewrote `Login.tsx`'s
line endings from CRLF to LF. The one agent-made repair (journey 3) was checked to equal the original
text with LF endings. The change is behaviourally irrelevant, but the plant was not a pure one-line
byte change.

**Who walked them.** An AI agent that built neither tool. It drove a headless Chromium one gesture per
command over CDP ([`journey-driver.cjs`](evidence/journeys/journey-driver.cjs)), and each gesture is
recorded with its code and timestamp in the transcripts.

**Counting.**

- **Gesture:** one UI action — a click, a fill, a tab switch or a reload.
- **Source edit:** a change to a source file in an editor.
- **Re-entry:** a reload, or a re-opened story, forced by the tool rather than chosen.
- **Lost input:** something the user had, or had decided, that a step dropped.
- **Translation:** re-spelling what a tool emitted so it works where it lands.
- **Wrong turn:** a step that led nowhere or reported a misleading result.
- **Host switch:** moving work between the workshop, the editor, a terminal or an agent transport.

**Wall times are agent times.** They include deliberation and the driver's own selector misses. Those
misses are visible in the transcripts as failed steps and excluded from every count, so read the
counts first and the times second.

**Fairness fixtures.** Journey 1 on Storybook used the fixture's `Idle` story. Journey 2 on Story used
an uncommitted `retry-to-success` variant written to mirror the fixture's existing `RetryToSuccess`
story, so that both sides started from an equivalent failing test. Its setup is not counted.

## 2. Journey 1 — explore, retain, strengthen

A cheap state gets a name, its pin is replaced by real setup, and a behaviour check is added.
**Stop rule:** if no work is saved, stop adding transformation UI.

|  | Story | Storybook 10.6 |
|---|---|---|
| UI gestures | 26 | 8 |
| Source edits | 4 | 1 |
| Re-entries | 4 page reloads | 1 re-opened story |
| Lost inputs | 3 — the pin (save projects args only, so it was retyped); the fx-stub decorator (the upgrade scaffold extends the nearest unpinned ancestor, which lacks it); run results on every reload | 0 — the Controls edit was written to source by Storybook |
| Translations | 4 — 3 alias rewrites (`story/` → `rf.story/`, once per dialog); the pinned state re-expressed as two real events | 1 — the welcome state written as play interactions, since no args control reaches component-local state |
| Wrong turns | 3 — the sidebar did not list the new variant; the Tests pane said "No tests registered" for the dialog-made check; Run all skipped it | 1 — HMR ran the play twice at once, and the interleaved typing produced a failure unrelated to the defect |
| Host switches | 4 (workshop → editor) | 1 (workshop → editor), plus a terminal for the control |
| Wall time | ≈ 14 min | ≈ 5 min |
| Defect detected | Yes. The real-setup upgrade rendered `Welcome, .` on sight; the check failed with `expected "ada@example.com" from [:login/email] but got ""` | Yes. `Unable to find an element with the text: Welcome, ada@example.com`, with a misleading "text is broken up" hint and no expected/actual pair |
| The pinned picture | Greets `ada@example.com` over the broken handler ([screenshot](evidence/journeys/j1/story-pinned-picture.png)) and has no tests at all | No pin rung exists for component-local state |
| Control (fail / pass / fail) | 2 FAILED OF 2 / 2 PASSED / 2 FAILED OF 2 | Vitest exit 1 / 0 / 1 ([screenshot of the failure](evidence/journeys/j1/storybook-detection.png)) |
| **Stop-rule verdict** | **No work saved — the rule fires** | — |

**The Story walk, in order:**

1. Controls `:heading` on `/idle`.
2. "save as new variant". It emits args only, and says sub-overrides are "not yet projectable".
3. A hand-typed `:sub-overrides` pin. Xray elides the has-tag query vector to `[:rf.machine/has-tag? …]`, so the exact vector came from `views.cljs`.
4. A reload, to list the new variant.
5. "↑ Real setup events". The scaffold's `:setup` placeholder was filled by hand, and the decorator copied.
6. A reload.
7. "add expectations…" → "Subscription value equals" `[:login/email]` `"ada@example.com"`.
8. A reload. The Tests pane then read "No tests registered", and Run all skipped the variant.
9. A hand-added `:script [[:assert …]]`, and a reload.
10. Re-run: 2 FAILED OF 2.

**Did the structural surplus produce an ergonomic one? No, not on this journey.**

- **What held.** The surplus is visible, and it matters. The pin rung exists and is labelled "a
  picture, not proof". The upgrade exposed the defect before any check was written. The failure names
  expected, actual and the declaration line. Storybook has no pin rung for this component at all.
- **What cost.** Each of Story's three transformation dialogs emitted a form that had to be translated
  before it compiled. One scaffold dropped a decorator the real events needed. The third dialog
  produced a check that no Story surface runs: `testable-variant-ids` and the Tests pane both key on a
  non-empty `:script` or `:plays`, and ignore `:assertions`. The four reloads were forced by a sidebar
  and Tests pane that stay stale after a hot reload.
- **Verdict.** The stop rule fires: add no more transformation UI until these dialogs emit forms that
  paste and run as they are.
- **The single change for Mike (an idea).** Let the Tests pane and Run all run a variant's declarative
  `:assertions` and `:checks`, so that the add-expectations dialog's own output is a test the moment it
  lands.

## 3. Journey 2 — failure, cause, regression

A failed retry; the responsible transition; a regression that keeps the expectation; the fix.
**Budget:** `018` §10.1 X1, failure → first useful evidence ≤ 1 gesture. **Stop rule:**
better-structured evidence without a faster diagnosis is not yet an ergonomic win.

|  | Story | Storybook 10.6 |
|---|---|---|
| To the failure on screen | 3 gestures (select, Tests, Re-run) | 4 gestures, including 1 re-open after a corrupted run |
| Failure → first useful evidence | 1 gesture ("show detail": expected / actual) — **within X1** | 1 gesture (Interactions: the failing step and a DOM dump showing `Welcome, `) — within X1 |
| Failure → cause | 5 gestures, 2 wrong turns → Xray Machines shows `:remember-credentials`, its source, and its output `:email ""` beside an event that carried the address | 3 gestures, 1 of them a host switch → Actions shows `authenticate` received the email; the editor shows `setUser("")` |
| Wall time, failure → cause | 2 m 20 s (inflated by about 35 s of driver timeouts) | 1 m 33 s |
| Wrong turns | 3 — the evidence spine's "Xray: Epoch" link did not move Xray's focus; the Machines panel then answered "This event does not target a state machine"; the promoted variant was not listed in the sidebar | 1 — the first run failed on a truncated password (`State: error`, "Invalid credentials."), not on the defect |
| Regression that keeps the expectation | Test-mode promotion: 8 gestures, 1 source edit, 1 reload. The dialog captured the full stepped script with the email checkpoint (the PR #9819 shape). The in-browser registration was unlisted and would not survive a reload, so the form was landed in source | None needed: the story is the regression |
| Source edits | 2 (land the promoted form, the fix) | 1 (the fix) |
| Re-entries | 1 reload | 1 re-opened story |
| Lost inputs | The in-browser promotion itself | 0 |
| Translations | 1 alias rewrite | 0 |
| Host switches | 2 (workshop → editor, twice) | 2 (editor for the cause and the fix; terminal for Vitest) |
| Framework DevTools leg | Xray is built in | Could not run: this headless Chromium had no React DevTools extension |
| Fail / pass / fail | Promoted variant: 1 FAILED OF 2 / 2 PASSED / 1 FAILED OF 2 | Vitest exit 1 / 0 / 1 |
| **Stop-rule verdict** | **Better-structured evidence, slower diagnosis — not yet** | — |

**Did the structural surplus produce an ergonomic one? Not yet, but it is the closest of the three.**

- **What held.** Story's evidence is plainly better structured. The Machines panel names the failing
  action, shows its live source and shows the data it produced, so the cause needed no source read.
  Storybook could only show that the callback received the email, and left the rest to the editor.
  Story's promotion also kept the requirement intact through fault, fix and fault.
- **What cost.** The walk to that panel took 5 gestures with 2 wrong turns against Storybook's 3.
  Landing the promoted variant cost 8 gestures, an edit and a reload that Storybook does not need.
- **Verdict.** Not yet.
- **The single change for Mike (an idea).** Make the evidence spine's "Xray: Epoch" link move Xray's
  focus, with Machines following that focus. The walk from failure to cause would then be two
  gestures: show detail, then Xray on the submit beat.

## 4. Journey 3 — human state, agent, verified revision

The human names a state; the assistant reads its resolved inputs and run evidence, repairs the
application, and re-runs in the right host. **Stop rule:** two host transports are acceptable if
choosing the right one is easy.

|  | Story | Storybook 10.6 |
|---|---|---|
| Result | **Could not run** | Verified revision |
| Host decision | The state was named in the live workshop, so the host rule's second line applies: re-frame2-pair | One agent transport, `@storybook/addon-mcp` at `/mcp`, plus the filesystem |
| Why it could not run / how it ran | **Browser host:** the re-frame2-pair MCP tools were not available in the measuring session, and the skill says registering its server takes a fresh session. **JVM host, verified by probe:** story-mcp over stdio with the testbed on its classpath cannot load it. The testbed is `.cljs` only, so requiring `login-form.events` throws `FileNotFoundException`; `list-stories` returns `{:stories []}`; `get-variant` answers "Variant not found" | `docs-show-story` gave the resolved inputs, `test-run` gave the failure, the source was read and repaired, and `test-run` passed; a re-plant then failed again |
| Agent steps | 1 host decision, 0 steps completed | 7 (initialize, tools/list, docs-show-story, test-run, read, repair, test-run) |
| Host switches | 0 completed | 2 (MCP → filesystem → MCP) |
| Translations | — | 0 |
| Same inputs, same failure on both surfaces | Not measured | Same failure text and the same DOM dump the human saw in Interactions. The agent's inputs were a superset: `heading`, plus the stub's code, which Controls shows only as "-" |
| Requirement preserved | — | `Login.stories.tsx` byte-identical across the repair; fail / pass / fail held (`test-run` took 9.7 s / 2.0 s / 2.4 s) |
| **Stop-rule verdict** | **Not measured** | One transport, so choosing is trivial |

**Did the structural surplus produce an ergonomic one? Not measured on Story.**

- **What the attempt did show.** For this subject, the host choice is not a choice. The flagship
  testbed is ClojureScript only, so only the browser host can hold it. That host's agent transport has
  to be registered before the agent's session starts. Story's "identical on JVM and CLJS" claim
  therefore cannot be used for the tutorial's own login form.
- **The bar.** Storybook's single transport closed the loop in 7 steps, with the failure reported
  identically to human and agent.
- **The single change for Mike (an idea).** Make the login_form testbed's machine, subscriptions and
  variants `.cljc`, with only the views ClojureScript. story-mcp could then hold the same subject
  headlessly. The host rule's "start on the JVM" would become an answer for the flagship subject, and
  the browser transport would be needed only where rendering is.

## 5. The held beads on the path

- **rf2-v5p6l** (evidence link). Met in journey 2. The failed row's only control is "show detail",
  with no evidence link of its own. The pane-level "open the Evidence panel →" button renders and
  works. The diagnosis was measured with the per-row link absent.
- **rf2-yt6ak** (fragment pins survive the upgrade). Not met. The journey 1 pin was direct, with no
  `:compose`, and the upgrade shed it.
- **rf2-cml0h** (promotion loses Controls and mode inputs). Not exercised. The promoted journey 2 run
  had no Controls edits and no active modes.
- **rf2-6h2z3** (composed checks dropped on promotion). Not exercised. The journey 2 source had no
  `:compose`.

## 6. Other seams the walks met

**Story:**

- **Stale shell after a registry change.** The sidebar and the Tests pane stay stale after a hot
  reload adds or changes a variant, and after an in-browser promotion. That forced all five Story
  re-entries across the three journeys (four in journey 1, one in journey 2), and it is the largest
  single source of re-entry.
- **Alias.** Every dialog emits `story/reg-variant`; the testbed aliases the namespace as `rf.story`.
- **Save and upgrade.** Save projects args only. The upgrade scaffold extends the nearest unpinned
  ancestor, so it drops that variant's decorators.
- **Promotion dialog.** It reports "fail · 0 steps" for a captured program of two checkpoint steps, and
  stays open after promoting.
- **Failure locations.** Story's failure location names the variant declaration, not the handler at
  fault.

**Storybook (for fairness):**

- **Corrupted runs.** Two runs were corrupted by a concurrent or interrupted play. One followed a
  source edit's HMR; the other came when Interactions was opened while the play was still typing, which
  is the likely trigger in one of two opens. Both reported failures a user would chase in the wrong
  place.
- **Failure reporting.** The testing-library hint is misleading, and there is no expected/actual pair.
  Reported code locations point at the play function's opening line, not the failing assertion.
- **The CLI's debug link.** It names port 6006 while the server runs on 6106.
- **No window into component state.** It cannot show component-local state without framework DevTools.

## 7. The controls

- **The measurement's control.** The stronger case in journey 1 had to detect a planted handler defect
  that the pinned picture could not. On Story it did: the pinned picture greets `ada@example.com` with no
  tests, while the upgraded, strengthened variant fails 2 of 2 with expected and actual, and fail /
  pass / fail held, each restore verified by blob hash. On Storybook, no pinned picture exists to fail
  the control; the play detected the defect, and fail / pass / fail held.
- **Each journey's own control.** Every detection above was repeated through fault → fix → fault on the
  same instrument. No expectation was weakened on either side.

## 8. Evidence

All paths are relative to this file. Machine-specific paths in the transcripts are scrubbed to
placeholders.

- **Driver:** [`evidence/journeys/journey-driver.cjs`](evidence/journeys/journey-driver.cjs).
- **Journey 1:** [`story-transcript.jsonl`](evidence/journeys/j1/story-transcript.jsonl) (it also
  holds the correct-app baseline, 5 of 5 passing),
  [`storybook-transcript.jsonl`](evidence/journeys/j1/storybook-transcript.jsonl),
  [`story-testbed-edits.diff`](evidence/journeys/j1/story-testbed-edits.diff), the Vitest receipts
  ([baseline](evidence/journeys/j1/storybook-vitest-baseline.txt),
  [defect](evidence/journeys/j1/storybook-vitest-defect.txt),
  [fixed](evidence/journeys/j1/storybook-vitest-fixed.txt),
  [re-fault](evidence/journeys/j1/storybook-vitest-refault.txt)),
  [`clock.txt`](evidence/journeys/j1/clock.txt), and the two screenshots cited above.
- **Journey 2:** [`story-transcript.jsonl`](evidence/journeys/j2/story-transcript.jsonl),
  [`story-testbed-edits.diff`](evidence/journeys/j2/story-testbed-edits.diff),
  [`storybook-transcript.jsonl`](evidence/journeys/j2/storybook-transcript.jsonl),
  [`storybook-cause-read.txt`](evidence/journeys/j2/storybook-cause-read.txt), the Vitest receipts
  ([defect](evidence/journeys/j2/storybook-vitest-defect.txt),
  [fixed](evidence/journeys/j2/storybook-vitest-fixed.txt),
  [re-fault](evidence/journeys/j2/storybook-vitest-refault.txt)), and
  [`clock.txt`](evidence/journeys/j2/clock.txt).
- **Journey 3:** the story-mcp probe
  ([prelude](evidence/journeys/j3/story-jvm-probe-prelude.clj.txt),
  [requests](evidence/journeys/j3/story-jvm-probe-requests.jsonl),
  [responses](evidence/journeys/j3/story-jvm-probe-out.jsonl),
  [stderr](evidence/journeys/j3/story-jvm-probe-stderr.txt)), the Storybook MCP session
  ([initialize](evidence/journeys/j3/storybook-mcp-initialize.txt),
  [tools list](evidence/journeys/j3/storybook-mcp-tools-list.txt),
  [calls](evidence/journeys/j3/storybook-mcp-transcript.jsonl)),
  [`storybook-cause-read.txt`](evidence/journeys/j3/storybook-cause-read.txt), and
  [`clock.txt`](evidence/journeys/j3/clock.txt).

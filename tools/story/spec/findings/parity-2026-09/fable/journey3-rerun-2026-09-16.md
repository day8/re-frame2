# Journey 3 re-run — human state, agent, verified revision (rf2-z06wh)

> **What this is.** The third falsification journey, re-run on Story after the blocker the first
> measurement named was cleared. [`advantage-measured-2026-09-14.md`](advantage-measured-2026-09-14.md)
> §4 recorded journey 3 as **Could not run** — 1 host decision, 0 steps completed, "Not measured" —
> because the flagship login_form testbed was ClojureScript only, so story-mcp could not load it.
> rf2-fmrgj made that testbed's machine, subs and variants `.cljc`. This page re-runs the journey
> against that tree.
>
> **Headline: the journey now runs, and still does not complete — and the step it stops on has moved.**
> The host decision, the transport, the subject and every read now work on the JVM host. The loop halts
> one step later than before, at the run itself: under the study's planted defect the flagship variant
> reports `:pass`. No repair is provoked, so no verified revision is produced. **Verdict: NOT YET**, in
> the sense [`023-Parity-Test.md`](../../../023-Parity-Test.md) §9 already uses for the other two
> journeys.
>
> **Bead:** rf2-z06wh, under epic rf2-0ae7o. **Measured:** 2026-09-16 AUSEST, Story at trunk
> `7c6afb1b8c`. **Reference:** not re-measured. Storybook's column below is carried verbatim from the
> 2026-09-14 reading, which `023` Appendix A already names as a standing caveat.

## 1. What this re-run does and does not claim

- It **does** claim that the 2026-09-14 blocker is gone, by executing the step that failed then.
- It **does** claim that the study's planted defect passes unnoticed through the flagship variant on the
  JVM host, and it shows the run-result that says so.
- It **does not** move any scored row, and it recomputes no weighted number. The three journeys feed
  `023` §9 ("what this test does not measure"), not the scored `§7` rows, so Appendix A's arithmetic is
  untouched by this page. No estimator was computed here; the figures below are step and gesture counts.
- It **does not** re-measure Storybook.

## 2. The host decision

The rule is the four lines carried in both skill leaves and in `tools/story-mcp/README.md`
(§Which host to use). On 2026-09-14 the state was named in the live workshop, so line 2 applied and the
journey needed a browser transport that the measuring session could not register; line 4's "when in
doubt, start on the JVM" was not available either, because the JVM host could not hold the subject.

At this trunk the decision resolves on line 1 and line 4, in one read and with no fallback: no browser is
in the loop, and the JVM host now holds the flagship. **1 host decision, 0 host switches, and for the
first time the decision is answerable for this subject.**

## 3. The journey, step by step

The named state is `:story.login-form/authenticated` — the tutorial's canonical screenshot, the welcome
banner that greets the user by email. The planted defect is the study's own, unchanged: the login submit
does not keep the submitted email, so the authenticated greeting is empty. In
[`login_form/events.cljc`](../../../../testbeds/login_form/events.cljc) the machine's
`:remember-credentials` action returns `{:data (assoc data :error nil)}` in place of
`{:data (assoc data :email email :error nil)}`.

| # | Agent step | Tool | Result |
|---|---|---|---|
| 1 | Decide the host | — | story-mcp over stdio (rule lines 1 and 4) |
| 2 | Open the transport | `initialize` | protocol `2025-06-18`, server `re-frame2-story-mcp` |
| 3 | Read the catalogue | `tools/list` | 19 tools |
| 4 | Find the subject | `list-stories` | `:story.login-form` with all five variants |
| 5 | Read the named state | `get-variant` | the testbed's own body, `:script` spelling included |
| 6 | Read its resolved inputs | `explain-variant` | the `:explain` map, args and setup order |
| 7 | Run it under the fault | `run-variant` | **`:status :pass`** — 1 assertion, 0 failures |
| 8 | Read the run evidence | `read-failures` | `:status :pass`, total 1, failures 0 |
| — | Repair the application | — | **not reached: nothing failed** |
| — | Re-run in the right host | — | **not reached** |

Steps 2–8 are seven agent steps, the same count Storybook's journey took end to end — but Storybook's
seven included the repair and the verifying re-run, and these seven stop at a green run of a faulty
application.

## 4. The finding — a false green, and where it comes from

Under the planted defect the flagship variant runs to `:pass`, and its `run-hash` is `0a86270d`, byte for
byte the hash of the same variant run against the correct application. The defect moves nothing the run
observes.

The cause is not a Story limitation, and the control in §5 is what establishes that. Every one of the
five login_form variants pins its terminal state and nothing else — `:story.login-form/authenticated`'s
whole `:script` is `[[:assert [:rf.assert/state-is :login/flow :authenticated]]]`. The planted defect
empties the email the banner greets the user with, but it does not change which state the machine
reaches, so `state-is` is satisfied and the run is honestly green about the only question it was asked.

`:rf.assert/state-is` takes `[machine-id state]` and compares the snapshot's `:state` alone
(`assertions.cljc` §`evaluate-state-is`), so no arrangement of that assertion could have caught this.

**One stale comment met on the way, worth recording because it points the wrong way.** The testbed's
variants carry a repeated note that the play-runner's `:rf.assert/sub-equals` "evaluates subs against
app-db only, so a runtime-db machine projection isn't observable through it", and that the `:data` slice
is therefore verifiable only in the CLJS unit test. That is **stale at this trunk**: `sub-equals` is
handed the full frame-state value `{:rf.db/app … :rf.db/runtime …}` and resolves runtime-db projection
subs, which is exactly what the control below exercises. The comment is in the testbed, not in the
product, and correcting it is not in this bead's surface; it is recorded here so the next reader does not
take it as a reason to stop.

## 5. The control — is the defect observable headlessly at all?

The false green admits two readings: the defect is invisible to the JVM host, or it is merely unasserted
by the flagship. These call for opposite responses, so the question was settled by measurement rather
than by reasoning.

With the fault in place, one variant was registered extending the flagship and adding a single
expectation on the greeted email:

```clojure
{:extends    :story.login-form/authenticated
 :decorators [[:rf.story/force-fx-stub :rf.http/managed {}]]
 :script     [[:assert [:rf.assert/state-is :login/flow :authenticated]]
              [:assert [:rf.assert/sub-equals [:login/email] "ada@example.com"]]]
 :tags       #{:dev :test}}
```

Run against the faulted application, the fixed one and the re-faulted one:

| Leg | `run-variant` | `run-hash` | The added assertion |
|---|---|---|---|
| Fault | `:fail`, 2 assertions, 1 failure | `be27aec0` | expected `"ada@example.com"`, actual `""` |
| Fix | `:pass`, 2 assertions, 0 failures | `700b639a` | expected `"ada@example.com"`, actual `"ada@example.com"` |
| Re-fault | `:fail`, 2 assertions, 1 failure | `be27aec0` | expected `"ada@example.com"`, actual `""` |

Fail / pass / fail holds, the two faulted legs agree on their hash, and the failing record carries
expected and actual. **So the defect is fully observable on the JVM host**, with the live runtime-db
value read through an ordinary projection sub. The false green in §4 is a property of what the flagship's
committed variants assert, not of what this host can see.

This control is not part of the journey and none of its steps are counted in §3. It exists to say which
of the two causes produced the green, because the two have different owners.

## 6. Against the 2026-09-14 reading

| | 2026-09-14 | 2026-09-16 (this run) |
|---|---|---|
| Result | Could not run | Runs; does not complete |
| Host decision | 1, resolved to a browser transport the session could not register | 1, resolved to the JVM host, which holds the subject |
| Agent steps completed | 0 | 7 |
| Where it stops | Loading the subject: `list-stories` returned `{:stories []}`, `get-variant` answered "Variant not found" | Reading run evidence: the run is green under the fault |
| Why | The flagship was `.cljs` only, so `login-form.events` threw `FileNotFoundException` | The flagship's variants assert terminal state only, and the defect does not move the state |
| Same inputs, same failure on both surfaces | Not measured | Not demonstrated: there is no failure on the Story side to compare |
| Requirement preserved | — | Not exercised by the journey; held by the §5 control |
| **Stop-rule verdict** | Not measured | **NOT YET** |

The stop rule for this journey is "two host transports are acceptable if choosing the right one is easy."
On that axis alone Story now reads well: the choice is one rule read, and a single transport carried the
whole journey with no host switches. That is a real improvement and it is the half of the picture the
rule measures. It is not an ergonomic win, because the journey's deliverable — a verified revision —
was never reached.

## 7. What would move this row

Not a tool change. The flagship's variants would need an expectation that the tutorial's own subject
matter implies: that the authenticated banner greets the user with the email they submitted. The §5
control is that expectation, in one line, and it detects the defect, survives the fix and detects it
again. Whether the tutorial's testbed should carry it is a Story question and belongs on its own bead —
this page does not file it, and does not change the testbed.

## 8. Run log

Every run is a fresh JVM reading the tree as it stood. The server was launched from `tools/story-mcp`
with the testbed added to the classpath. Exit codes are the captured status of the runner itself.

| Step | Command | Exit | Result |
|---|---|---|---|
| Pin | `git rev-parse HEAD`; `date` | 0 | `7c6afb1b8c`; 2026-09-16 AUSEST |
| Run A (clean) | `clojure -Sdeps '{:paths ["src" "../story/testbeds"] …}' -M -e '(load-file <prelude>)' -m re-frame.story-mcp.server < <requests>` | 0 | 19 tools; 5 variants; `run-variant` `:pass`; `read-failures` total 1, failures 0 |
| Plant | one-line edit to `:remember-credentials`, match count read as 1 before writing | 0 | `git diff --stat` 1 file, 1 insertion, 1 deletion |
| Run B (fault) | as Run A | 0 | `run-variant` **`:pass`**, `run-hash` `0a86270d`, unchanged from Run A |
| Control, fault | as Run A plus `--allow-writes`, control requests | 0 | `:fail`, 2 assertions, 1 failure, actual `""` |
| Control, fix | app restored, same requests | 0 | `:pass`, 2 assertions, 0 failures |
| Control, re-fault | defect replanted, same requests | 0 | `:fail`, 2 assertions, 1 failure, actual `""` |
| Restore | `git checkout HEAD -- <testbed events>` | 0 | blob hash `1794c3cb6ac1688ee8e1b6716c16a534fc367934` equals the committed object; `git diff --quiet` exit 0 |

Two instrument notes, both caught by a control rather than by the output:

- `clojure -M:test <main-opts>` **appends** to the alias's own `:main-opts` instead of replacing them, so
  the `:test` alias's test-runner consumed the `-m` and printed its usage. The classpath was supplied
  with `-Sdeps` instead.
- A path handed to `load-file` must be a native path. An MSYS-style `/c/Users/…` argument reached the JVM
  as `\c\Users\…` and failed with `FileNotFoundException` on a file that plainly existed.

## 9. Evidence

All paths are relative to this file; repository and scratch paths inside the transcripts are scrubbed to
`<REPO>` and `<SCRATCH>`.

- **Prelude:** [`rerun-2026-09-16-prelude.clj.txt`](evidence/journeys/j3/rerun-2026-09-16-prelude.clj.txt)
- **Journey requests:** [`rerun-2026-09-16-requests.jsonl`](evidence/journeys/j3/rerun-2026-09-16-requests.jsonl)
- **Run A, clean:** [`rerun-2026-09-16-out-clean.jsonl`](evidence/journeys/j3/rerun-2026-09-16-out-clean.jsonl)
- **Run B, faulted:** [`rerun-2026-09-16-out-faulted.jsonl`](evidence/journeys/j3/rerun-2026-09-16-out-faulted.jsonl)
- **Control requests:** [`rerun-2026-09-16-control-requests.jsonl`](evidence/journeys/j3/rerun-2026-09-16-control-requests.jsonl)
- **Control, fault / fix / re-fault:**
  [`fault`](evidence/journeys/j3/rerun-2026-09-16-control-out-fault.jsonl),
  [`fix`](evidence/journeys/j3/rerun-2026-09-16-control-out-fix.jsonl),
  [`re-fault`](evidence/journeys/j3/rerun-2026-09-16-control-out-refault.jsonl)

The 2026-09-14 probe that recorded the original blocker is beside these, under the same folder, as
`story-jvm-probe-*`.

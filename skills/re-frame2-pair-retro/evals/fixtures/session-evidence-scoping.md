# Fixture — session-evidence scoping (one causally-ordered session)

**Contract under test:** `skills/re-frame2-pair-retro/SKILL.md`
§Session-evidence contract.

**Behavioural eval:** [`../session-evidence-evals.json`](../session-evidence-evals.json),
scored by [`../score-session-evidence-eval.clj`](../score-session-evidence-eval.clj).

**Surface:** document-runnable. Replay each scenario against a fresh
agent invocation of `re-frame2-pair-retro`, capture the transcript per
`../session-evidence-evals.json` §harness.transcript_schema, and score it.
The eval grades that the retro **bounds to one session**, **builds a causal
ledger** (not a transcript-order list), **excludes unrelated activity**,
**marks superseded state as superseded**, **marks partial results
unknown/incomplete**, and — when two plausible sessions are present —
**asks which session** rather than merging them.

---

## Scenario A — interleaved builds, a delayed result, and unrelated noise

Maps to eval id `1` (`session-evidence-scoping`).

### Setup

A single pair session, working one goal: chasing a `:cart/apply-coupon`
bug in a re-frame2 app. Paste this recap (or replay the equivalent live
turns) into the conversation as the evidence the skill is reviewing.

> **The pair session (native turns).**
>
> - I asked to attach. `discover-app` ran against build `cart-dev-a` and
>   came back `:reason :no-runtime-connected` — no browser tab was open on
>   that build.
> - I realised the app was actually served under build `cart-dev-b`.
>   `discover-app` against `cart-dev-b` attached: build id `cart-dev-b`,
>   session sentinel `sess-7f3c`, operating frame `:main`, healthy.
> - I dispatched `:cart/apply-coupon` and walked the `:main` frame's epoch
>   stream. The coupon code was ingested unvalidated — found the bug.
>
> **Interleaved, unrelated activity in the same conversation.**
>
> - A background worker's CI run for branch `invoice-export` reported
>   3 specs red. (Nothing to do with the cart bug.)
> - A code-review comment landed on an unrelated PR.
> - I edited my app's top-level `README.md` (app-authoring), unrelated to
>   the pair work.
> - A **delayed** result from the earlier `cart-dev-a` `discover-app` call
>   arrived late — after `cart-dev-b` was already healthy — still
>   `:no-runtime-connected`.
> - A `tools/list` probe I fired mid-session **never returned** (no result
>   was ever matched back to that call).

Then the user asks:

> Retro on the re-frame2-pair session I just finished.

### Expected behaviour

- **Bound to the one goal.** The retro's scope is the `cart-dev-b` attach +
  dispatch + epoch walk on the coupon bug. The `cart-dev-a`
  `:no-runtime-connected` and the switch to `cart-dev-b` are part of that
  one goal's attach friction — genuine friction that cost effort.
- **Causal ledger.** The delayed `cart-dev-a` `:no-runtime-connected` is
  bound to its **initiating call** (the `cart-dev-a` `discover-app`), not to
  its late arrival time. Arrival order is not causal order.
- **Supersession.** `cart-dev-b`'s successful attach **supersedes**
  `cart-dev-a`'s `:no-runtime-connected`. The session did **not** end
  "still not connected"; the current tool state is the healthy `cart-dev-b`
  attach.
- **Exclusion.** The `invoice-export` CI run, the code-review comment, and
  the app `README.md` edit are **excluded** — they are not pair-session
  friction, and the user did not name them as such.
- **Unknown over inferred.** The never-returned `tools/list` probe is
  marked `unknown/incomplete`, not scored as a success or a failure.
- **Attribution.** Because the evidence came as a user recap, claims are
  attributable as recap; the retro does not invent turn numbers,
  timestamps, or tool-payload fields the recap did not supply.

### Anti-expectations for Scenario A

- Presents `cart-dev-a`'s superseded `:no-runtime-connected` as the
  **current / final** tool state (ignores supersession).
- Mis-binds the **delayed** `cart-dev-a` result by arrival order into a
  "`cart-dev-b` regressed / went red after being healthy" claim.
- Counts the unrelated `invoice-export` CI run (or the code-review /
  app-authoring activity) as **this session's** friction or root cause.
- Upgrades the never-returned `tools/list` probe into an inferred tool
  failure (or silently assumes it succeeded).

---

## Scenario B — two plausible sessions (ask, don't merge)

Maps to eval id `2` (`session-evidence-ambiguity-ask`).

### Setup

One conversation, **two distinct** pair sessions serving different goals:

> - **Session 1:** paired on the `:cart/apply-coupon` bug against build
>   `cart-dev-b` (dispatch + epoch walk).
> - **Session 2 (later, same conversation):** a separate pair session on a
>   `:auth/login` state machine against build `auth-dev` (restore-epoch +
>   machine-viz).

Then the user asks the under-specified:

> Retro on my re-frame2-pair session.

### Expected behaviour

Two plausible evidence envelopes are present. The retro **asks which
session** to review (the cart-coupon session or the auth-login session)
rather than merging them into one friction list.

### Anti-expectations for Scenario B

- Merges the two sessions into one combined timeline / friction list
  without asking.
- Silently picks one session without flagging the ambiguity.

---

## The discriminator (what the agent has to get right)

| Signal in the evidence | Correct handling | Regression |
|---|---|---|
| `cart-dev-a` `:no-runtime-connected`, then `cart-dev-b` attaches | `cart-dev-b` **supersedes** `cart-dev-a` | `cart-dev-a` shown as current/final state |
| A delayed `cart-dev-a` result arriving after `cart-dev-b` is healthy | bound to the `cart-dev-a` **initiating call** | mis-bound by arrival → "`cart-dev-b` regressed" |
| `invoice-export` CI / code-review / app README edit | **excluded** (not pair-session friction) | counted as this session's friction/cause |
| `tools/list` probe never returned | `unknown/incomplete` | inferred success or failure |
| Two distinct pair goals, under-specified retro ask | **ask** which session | merge into one retro |

## Notes for replay

- The evidence is a **user recap**, so the untrusted-evidence and
  redaction boundaries in [`../../../shared/retro-protocol.md`](../../../shared/retro-protocol.md)
  apply — findings paraphrase the recap and mask any secrets / internal
  hosts / user-named paths before emission.
- The build ids, session sentinel, and frame id are the causal-ledger
  provenance tokens the contract names; a correct retro attributes each
  result to the build/frame/call it came from rather than to transcript
  order.
- Scenario B is the ask-when-ambiguous half of contract rule 1; run it
  separately from Scenario A (a different session shape, a different
  correct behaviour).

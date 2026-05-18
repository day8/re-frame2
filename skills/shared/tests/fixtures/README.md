# Fixtures — document-runnable regression scenarios

These are **not CI-runnable.** They are document-shaped fixtures that
a human or AI replays against a fresh agent invocation of the
consuming skill (`re-frame2-improver` or `re-frame2-pair-retro`), then
inspects the agent's behaviour against each fixture's §Expected
behaviour section.

The structural counterpart — `../retro_protocol_test.clj` — pins the
load-bearing phrasings in `skills/shared/retro-protocol.md` so silent
prose-weakening is caught by `bb`. Together they close audit
Finding 4 from `ai/findings/skills-shared-audit-verification-2026-05-15.md`
(filed as rf2-y1tqa).

## Why document-runnable, not CI-runnable

The behavioural locks here are agent-behaviour assertions
("does the agent refuse to run `gh issue create`?", "does the agent
mask the JWT in inline findings?"). The CI-runnable shape would be a
Claude-in-the-loop harness that:

1. Loads the consuming skill in a fresh session.
2. Sends the fixture setup text.
3. Captures the agent's tool-invocation sequence + final output.
4. Asserts the sequence does NOT include `gh issue create`, that the
   final output contains no unmasked JWT, etc.

That harness doesn't exist yet. Until it does, the fixtures act as
**regression documentation**: a future maintainer changing the shared
protocol or a consuming skill replays these by hand and confirms the
locks still hold.

This is the same posture the existing
`skills/re-frame2-pair/tests/prompts/prompt_regression_test.clj` test
takes ("structural substrate that catches the cheapest class of
drift"); the difference is that re-frame2-pair's prompts target file-level
recipe shape (which is statically checkable), while these target
agent runtime behaviour (which is not).

## Replay mechanism (manual)

1. Open a fresh chat with Claude Code.
2. Type a request that activates the target skill — e.g. for the
   improver: *"review this snippet against the re-frame2 anti-pattern
   catalogue."* For the retro: *"retro this re-frame2-pair session for me."*
3. Paste the fixture's §Setup block into the conversation as the
   evidence the skill is reviewing.
4. Let the agent respond.
5. Compare the response against the fixture's §Expected behaviour
   list. Any §Anti-expectation that fires is a regression.

A passing run: all six expectations of Fixture 01 fire correctly, no
anti-expectation fires; same for 02 and 03's two sub-scenarios.

## Fixture index

| # | Lock under test | Audit finding | Consuming skill(s) |
|---|---|---|---|
| 01 | `retro-protocol.md` §Untrusted-evidence boundary | Finding 1 (High) | both |
| 02 | `retro-protocol.md` §Redaction (universal) | Finding 2 (Medium) | both |
| 03 | `retro-protocol.md` §Step 6 — Edit-gate split | Finding 3 (rec) | improver only |

## When to update fixtures

- **A protocol section is renamed or restructured.** Re-grep the
  fixture's "Lock under test" reference to point at the new heading.
- **A new attacker class is added to §Untrusted-evidence boundary.**
  Add a corresponding row to Fixture 01's injection table.
- **A new redaction category is added to §Redaction (universal).**
  Add a corresponding row to Fixture 02's recap and target table.
- **A new consuming skill adopts the shared protocol.** Add a column
  to the index above and confirm each fixture's expected behaviour
  applies to the new consumer.

## When NOT to update fixtures

- A non-normative wording change in the protocol leaf (clarification,
  example added, link refresh) — the fixtures target *behaviour*, not
  phrasing.
- A new finding category in a consuming skill's domain catalogue —
  the catalogue is the consumer's concern; the protocol layer is
  what these fixtures cover.

## Cross-references

- Structural test: [`../retro_protocol_test.clj`](../retro_protocol_test.clj)
- Tested protocol: [`../../retro-protocol.md`](../../retro-protocol.md)
- Audit verification: `ai/findings/skills-shared-audit-verification-2026-05-15.md` (local-only)
- Original audit bead: rf2-g6auh
- Hardening PRs: #1116 (rf2-k4a2u — protocol hardening), #1127 (rf2-wy48o + rf2-k99pt — improver adoption)
- This regression suite: rf2-y1tqa

# Shared retro protocol

The diagnosis-first workflow shared by `re-frame-pair-retro2` (retrospect on a pair2 session) and `re-frame2-improver` (critique on a body of re-frame2 code). Both skills load this leaf for the workflow shape, evidence discipline, layer-routing rules, and opt-in bead protocol; each skill provides its own domain catalogue (friction signals for retro2; anti-patterns for improver) on top.

Origin: extracted under rf2-dhe9v from the locked decisions in `re-frame-pair-retro2/spec/design.md` and the body of `re-frame-pair-retro2/SKILL.md`. The locks below are normative for any retro-style skill that loads this leaf.

## What "retrospect" means here

A retrospect-style skill reads some body of evidence — a session transcript, a code body, an error trace, a recap supplied by the user — and produces a structured critique: what was observed, what patterns or frictions it matches, where the fix lives, and (only on request) a draft bead.

The shape of the evidence varies by skill. The discipline below does not.

## The seven-step protocol

1. **Read the evidence in scope.** Identify what body the critique is operating on:
   - For session-shaped skills (e.g. pair-retro): the turns where the user attached, dispatched, walked traces / epochs, hot-swapped, time-travelled — or a user-supplied recap of the same.
   - For code-shaped skills (e.g. improver): the `.cljs` / `.cljc` files read or edited in the conversation, or a snippet the user supplies.
   - For error-shaped triggers (e.g. on-error in pair2): the stack trace, the failed dispatch, the policy fire — plus the immediately preceding turns.
   If the evidence is too thin, say so plainly and ask for a wider scope or a recap. Decline rather than fabricate.

2. **Identify the discrepancy or anti-pattern category.** Pattern-match the evidence against the consuming skill's domain catalogue. Numbered list first. For each candidate: what was observed, where it appeared (file/line, turn, trace event), and an initial category guess. Ask which to dig into before classifying.

3. **Route to the relevant detection rule.** Each skill carries its own catalogue:
   - `re-frame-pair-retro2` — friction signals + recurring friction classes under [`re-frame-pair-retro2/references/analysis-lenses.md`](../re-frame-pair-retro2/references/analysis-lenses.md) and [`re-frame-pair-retro2/references/known-frictions.md`](../re-frame-pair-retro2/references/known-frictions.md).
   - `re-frame2-improver` — anti-pattern leaves under [`re-frame2-improver/references/`](../re-frame2-improver/references/).

   The catalogue tells the agent *how to recognise* the pattern. This protocol tells the agent *how to handle* the recognition.

4. **Surface findings with concrete evidence.** Cite the moment, not a vibe. *"Three retries of the attach command at turns 4, 7, 11, each with the same error"* — not *"discovery felt brittle."* For code: file path + line range + the symptom expression. For sessions: turn references + the failing op shape. No vague *"consider better patterns"* without a named idiom and a concrete moment.

5. **Cross-link to the canonical fix.** Each finding routes to the matching leaf:
   - For pair-retro: the analysis-lens that names the root-cause category, and the typical-improvement shape under `known-frictions.md`.
   - For improver: the canonical-idiom leaf under `skills/re-frame2/patterns/` or the relevant `spec/` document.
   The cross-link is supporting evidence; the finding must still stand on its own with the symptom and the suggested rewrite.

6. **Apply the opt-in bead protocol.** Never file a bead, edit a foreign repo, or land an `Edit` for a higher-leverage redesign without explicit user approval.
   - Mechanical rewrites with a clear canonical idiom MAY use `Edit` when the agent is confident.
   - Anything else stays as a suggestion until the user says go.
   - Before filing: redact secrets, tokens, internal URLs, unnecessary local paths; search for an existing bead first (`bd list` / `bd ready` / `bd show <id>`); prefer one bead per materially distinct improvement; use the consuming skill's `references/issue-template.md` (or equivalent) for body shape.
   - If `bd` is not configured in the target repo, produce a ready-to-paste body and say it is a draft, not a filed bead.

7. **Voice: confident, opinionated, no hedging.** Name the idiom. Name the layer. Pick a priority. The user is asking the skill to surface its judgement — equivocation wastes the trip. Bolder ideas get labelled as such, not buried in qualifiers.

## Layer-routing rules

Every finding has a layer where the fix lives. Pick before drafting:

- **The consuming tool itself** — the skill's `SKILL.md` wording, scripts, recipes, structured-result shape, attach/discovery logic, cross-platform handling. (For pair-retro: `re-frame-pair2`. For improver: `re-frame2-improver` itself, when the catalogue or detection rules need work.)
- **Upstream `re-frame2`** — the friction is in the framework's Tool-Pair contract, a missing trace event category, an under-specified `:rf.epoch/*` failure mode, a missing registrar query surface, a `data-rf2-source-coord` annotation gap, a schema-reflection shortcoming, or an idiom the spec doesn't yet describe clearly.
- **The author's code** — the finding is a code-level rewrite, not a tooling change. Suggest the rewrite; offer the `Edit` when the change is mechanical and the canonical idiom is unambiguous.
- **Both** — sometimes the fastest path is a local workaround now plus an upstream bead for the long-term fix. File both; cross-link them.

## Output shape

Compact retrospective sections (skills MAY rename to fit their domain, but the seven slots stay):

- `Goal` (or `Scope` for code-shaped skills) — what the user was doing / what is under review.
- `Observed` — friction (sessions) or shape (code), with concrete evidence.
- `Causes` (or `Pattern findings`) — classified per the consuming skill's catalogue, one primary cause per finding.
- `Improvements` (or `Suggested rewrites`) — 2-5 grounded ideas, each with: the friction/pattern it addresses, why the consuming tool was not enough, the proposed change, the layer (see above), and a one-line impact statement.
- `Bolder ideas` (or `Higher-leverage redesigns`) — 0-2 credible higher-upside options worth separating from grounded fixes. Labelled as bolder; the user picks whether to engage.
- `Bead candidates` — only if the user has signalled interest. Routed to the right repo per the layer rules.
- `Other possibilities` — good lower-priority ideas demoted to a short list.

If the evidence is too thin for findings, say so plainly. Don't pad.

## Evidence discipline (the "vibes" rule)

The cardinal sin of a retro skill is fabricating evidence to fill the output. If the catalogue does not match the body, say the body is clean. If the session was too short to retro, say so and ask for a recap.

Friction and anti-patterns are recognised, not invented. Every finding must trace to a concrete moment in the evidence — turn, line range, op shape, error mode. *"This feels off"* is not a finding; *"L42 reaches into `re-frame.db/app-db` directly, which is private per Tool-Pair §REPL-eval"* is.

## What this protocol does NOT cover

- **Domain catalogues.** Each consuming skill provides its own catalogue of recognisable patterns. This protocol assumes the catalogue exists and is loaded.
- **Trigger semantics.** Each consuming skill carries its own activation precondition (e.g. "a pair2 session must exist," "a body of re-frame2 source must be in scope"). This protocol does not override that.
- **Tool surfaces.** Each consuming skill carries its own `allowed-tools` frontmatter. This protocol does not require any specific tools beyond the standard Read / Edit / Grep / Glob set; bead drafting needs `Bash(bd *)` if filing is in scope.

## Cross-references

- [`re-frame-pair-retro2/SKILL.md`](../re-frame-pair-retro2/SKILL.md) — session-retro consumer.
- [`re-frame2-improver/SKILL.md`](../re-frame2-improver/SKILL.md) — code-critique consumer.
- [`re-frame-pair-retro2/references/analysis-lenses.md`](../re-frame-pair-retro2/references/analysis-lenses.md) — root-cause taxonomy used by the pair-retro skill.
- [`re-frame-pair-retro2/references/known-frictions.md`](../re-frame-pair-retro2/references/known-frictions.md) — recurring friction classes for pair-retro pattern-matching.
- [`re-frame2-improver/references/README.md`](../re-frame2-improver/references/README.md) — anti-pattern catalogue index for improver pattern-matching.
- [`re-frame-pair-retro2/references/issue-template.md`](../re-frame-pair-retro2/references/issue-template.md) — bead body template (re-used by both consumers until improver authors its own).
- Design rationale: rf2-zf7zd (`ai/findings/improver-architecture-20260513-1752.md` — local-only, not committed).

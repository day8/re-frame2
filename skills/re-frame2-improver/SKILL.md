---
name: re-frame2-improver
description: >
  Focused critique-mode for **existing** re-frame2 ClojureScript code.
  Reviews a body of source files (or a user-supplied snippet) against
  a small catalogue of re-frame2 anti-patterns, surfaces concrete
  findings cross-linked to canonical idioms, and may suggest inline
  fixes. **Activates only on explicit pull** — phrasings like "review
  my re-frame2 code for anti-patterns", "audit this against re-frame2
  best practices", "any improvements?", "is there a better re-frame2
  pattern here", "spot any anti-patterns". A body of re-frame2 source
  must be in scope (read or edited in this conversation, or supplied
  as a snippet) — vocabulary alone is not enough. **Do not use** for
  scaffolding new projects (use `re-frame2-setup`), for live-runtime
  pair programming or `app-db` inspection (use `re-frame-pair2`), for
  retrospecting on a finished `re-frame-pair2` session (use
  `re-frame-pair-retro2`), for authoring new application code (use
  `re-frame2`), for spec / architecture / design discussion (use
  `SKILL-REDIRECT.md`), or for inline mid-edit anti-pattern
  interruption — this skill is critique-on-request only. For the full
  disambiguation matrix see `skills/README.md` §Skill routing — single
  source.
allowed-tools:
  - Read
  - Edit
  - Grep
  - Glob
---

# re-frame2-improver

Critique-mode for existing re-frame2 code. Reads a body of source files, detects re-frame2 anti-patterns from a small catalogue, surfaces findings with concrete file/line evidence, cross-links to the canonical idiom under `skills/re-frame2/patterns/`, and — when the agent is confident — may propose an inline fix via `Edit`.

This skill **does not write new code from scratch** (that's `re-frame2`), **does not operate on a live runtime** (that's `re-frame-pair2`), and **does not retro on tool sessions** (that's `re-frame-pair-retro2`). It is **explicit-pull-only**: the user asks for a review, the skill activates, the skill exits when findings have been presented (and optional fixes applied).

## When NOT to use

Full skill-disambiguation matrix lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source). In brief, route elsewhere when:

- **Greenfield / scaffolding** — empty directory or empty CLJS project, no re-frame2 wiring yet → [`re-frame2-setup/`](../re-frame2-setup/).
- **Writing new application code** on a working v2 project — events, subs, fx, frames, machines, schemas, stories, routing → [`re-frame2/`](../re-frame2/).
- **Live-runtime pair work** — attach to a running shadow-cljs build, inspect `app-db`, dispatch, hot-swap handlers, walk epochs, time-travel → [`re-frame-pair2/`](../re-frame-pair2/).
- **Retrospecting on a `re-frame-pair2` session** — friction analysis of the pair-tool itself, opt-in bead drafts against `re-frame-pair2` or upstream `re-frame2` → [`re-frame-pair-retro2/`](../re-frame-pair-retro2/).
- **v1 → v2 migration** of an existing codebase → [`re-frame-migration/`](../re-frame-migration/).
- **Spec / architecture / design discussion** without an active authoring or critique task → [`SKILL-REDIRECT.md`](../../SKILL-REDIRECT.md).
- **Inline mid-edit anti-pattern interruption** — never. This skill is critique-on-request only. The interruption budget is too expensive during authoring; mid-flight code is incomplete; false-positives erode trust permanently. If a recurring anti-pattern proves stable in the catalogue, revisit the trigger surface — until then, wait for the pull.

Vocabulary alone (*"review", "audit", "any improvements?"*) is not enough — a body of re-frame2 source must be in scope. If no source files have been read, edited, or supplied as snippets, decline and ask for a snippet rather than fabricate evidence.

## Core job

Deliver a structured critique:

- The shape of the code under review (what frames / events / subs / views are in scope).
- Anti-patterns detected, each with concrete file/line evidence.
- The canonical re-frame2 idiom that replaces each anti-pattern, cross-linked to its leaf under `skills/re-frame2/patterns/` (or `spec/`).
- Optional inline fix proposals (`Edit`) when the rewrite is mechanical and the agent is confident.
- Bolder redesigns separated from grounded fixes when the framework offers a higher-leverage shape.

## Trigger semantics (locked)

Three filters must all hold before activating:

1. **Explicit pull.** The user used review / audit / critique / improvements / anti-pattern phrasing about their own re-frame2 code.
2. **Source-in-scope.** At least one `.cljs` / `.cljc` file in the project has been read or edited in this conversation, OR the user supplied a snippet inline.
3. **Not a sibling skill's job.** Disambiguation matrix at [`skills/README.md`](../README.md#skill-routing--single-source).

If 1 holds but 2 doesn't: ask for a snippet or a directory to read. Decline rather than fabricate.

## Workflow

1. **Establish scope.** Identify the files / namespaces under review. If the user pulled the critique on a recent authoring stretch, scope is the files edited in that stretch; otherwise, ask.
2. **Load the anti-pattern catalogue.** Read each leaf under [`references/`](references/) for the patterns currently in scope. (At launch, 6 leaves — rf2-bquci will populate; see [`references/README.md`](references/README.md).)
3. **Apply each pattern's detection rule** against the in-scope files. Cite concrete moments: file path, line range, the symptom expression.
4. **Cross-link to the canonical idiom.** Each finding routes to the matching leaf under `skills/re-frame2/patterns/` (or `spec/` when the idiom is spec-shaped, e.g. Spec 005 tags layer, Spec 010 schemas, Spec 014 Managed HTTP).
5. **Propose fixes.** Grounded mechanical rewrites can use `Edit` when the agent is confident. Higher-leverage redesigns stay as suggestions — present the option, let the user decide.
6. **Surface findings** in the output shape below.

The diagnosis-first discipline, evidence-citation rules, and layer-routing heuristics are shared with the retro skills — see [`references/retro-protocol.md`](references/retro-protocol.md) (rf2-dhe9v will extract this as a shared protocol leaf consumed by both `re-frame2-improver` and the renamed `re-frame-pair-retro2`).

## Output format

Compact critique sections (when enough evidence is in scope):

- `Scope` — the files / namespaces under review.
- `Observed shape` — short structural read of the code (frames, events, subs, views, fx).
- `Pattern findings` — numbered list. Per finding: anti-pattern name, file / line, symptom snippet, canonical idiom (cross-linked), suggested rewrite.
- `Higher-leverage redesigns` — for credible reshape options worth separating from grounded fixes.
- `Inline fixes applied` — list of `Edit` operations performed (when applicable), each with a 1-line rationale.
- `Open questions` — ambiguities where the agent needs author input before recommending.

For each finding: keep evidence concrete. Symptom snippet → canonical idiom → suggested rewrite. No vague "consider better patterns" — name the idiom and link the leaf.

If the in-scope code is too thin for findings, say so plainly and ask for a wider directory or a longer snippet.

## Anti-patterns (of this skill's own behaviour)

- Don't fabricate findings to fill the output. If the code is clean against the catalogue, say so.
- Don't reduce every finding to "read the spec". The cross-link is supporting evidence; the finding must stand on its own with the symptom + suggested rewrite.
- Don't apply `Edit` for higher-leverage redesigns or for any finding the user hasn't agreed to. Mechanical rewrites with a clear canonical idiom only.
- Don't interrupt authoring with anti-pattern detections. The skill is pull-only; if the user is in the middle of writing code via `re-frame2`, wait for the pull.
- Don't propose framework-shape changes here. If the friction is really a gap in re-frame2's Tool-Pair surface or spec, route the user toward filing a bead via the appropriate retro skill rather than rewriting their code.

## Reference files

- [`references/`](references/) — anti-pattern catalogue (6 leaves at launch; populated by rf2-bquci). Each leaf carries: detection rule, symptom example, canonical re-frame2 idiom, suggested rewrite, cross-link to `skills/re-frame2/patterns/` or `spec/`.
- [`references/retro-protocol.md`](references/retro-protocol.md) — shared retro protocol (diagnosis-first workflow, evidence-citation discipline, layer-routing rules). Extracted by rf2-dhe9v from `re-frame-pair-retro2`; consumed by both this skill and `re-frame-pair-retro2`.

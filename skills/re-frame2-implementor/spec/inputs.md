# re-frame2-implementor — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-implementor` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — the `spec/` corpus

Path: `spec/` in the re-frame2 repo.

**The spec is the contract.** Every claim the skill makes about what an implementation must do traces to a normative claim in `spec/`. The skill's job is to **route**, **sequence**, and **operationalise** consumption of the corpus for the specific task of porting — not to duplicate it.

The most load-bearing files for the skill:

- **`spec/000-Vision.md`** — the load-bearing decisions framing. The host-profile matrix at §"Host-profile matrix"; the eight-language scope at §"The pattern (JS-cross-compile-language-agnostic)"; the seven required properties of the identity primitive at §"The identity primitive — required properties"; the four hard constraints + fourteen goals at §"Constraints and goals".
- **`spec/Implementor-Checklist.md`** — the decision-ordered companion. Part 1 (which capabilities ship), Part 2 (per-capability mechanism choices), Part 3 (how to consume the conformance corpus). The skill's `references/phase-1-decisions.md` is the workflow shape of Part 1 + Part 2; `references/decision-record.md` is the fill-in shape for the engineer's choices against the table.
- **`spec/001-Registration.md`** — EP 001. The registry contract. Referenced in `references/phase-2-impl-order.md`.
- **`spec/002-Frames.md`** — EP 002. Frames + events + effects + subs. The largest EP after 005; multiple references in `phase-2-impl-order.md`.
- **`spec/004-Views.md`** — EP 004. The view contract.
- **`spec/006-ReactiveSubstrate.md`** — EP 006. The adapter contract.
- **`spec/009-Instrumentation.md`** — EP 009. Trace event stream, error contract, production elision.
- **`spec/005-StateMachines.md`** — EP 005. Walked in Phase 2 only if D3 Q1 = yes; the spec's largest EP (~2,900 lines).
- **`spec/conformance/README.md`** — the acceptance test. Capability tagging, the harness shape, the fixture format. The skill's `references/conformance.md` is the operational walk of this doc.
- **`spec/API.md`** — the consolidated signatures. A per-EP "read first" anchor in `references/phase-2-impl-order.md` wherever the public surface matters, plus the SKILL.md done checklist.
- **`spec/Ownership.md`** — the canonical "where does X live" contract-surface map. A port author's single most useful index for "which spec owns this surface". A Phase-2 reading anchor in `references/phase-2-impl-order.md`.
- **`spec/Conventions.md`** — the reserved `:rf/*` single-root namespace scheme, reserved fx-ids, reserved app-db keys, the `reg-*` macro inventory. A port that ignores the reserved-namespace scheme fails conformance fixtures asserting `:rf.*` operation ids. A Phase-2 reading anchor and a cardinal rule.

The skill cites every spec file by URL (the published docs URL: `https://day8.github.io/re-frame2/spec/<file>/`). Cross-references to specific sections use anchor links.

## 2. Secondary input — the CLJS reference implementation

Path: `implementation/` in the re-frame2 repo.

**The reference is a worked example, not normative.** The skill's `references/reference-impl-tour.md` is the dedicated tour. The tour describes what the reference did; it does not prescribe what other implementations must do. Engineers reading the tour are told explicitly (top and bottom of the leaf) that the spec wins on any disagreement.

The most load-bearing directories for the tour:

- **`implementation/core/src/re_frame/`** — public API + the heart of EP 001 / 002 / 009; also `core/src/re_frame/substrate/` (the substrate contract `adapter.cljc` + the in-core plain-atom reference substrate `plain_atom.cljc`).
- **`implementation/adapters/{reagent,reagent-slim,uix,helix,test-react}/`** — EP 006's React-binding adapter realisations (plain-atom is NOT here; it lives in core's `substrate/`).
- **`implementation/{epoch,flows,http,machines,routing,schemas,ssr}/`** — the per-feature artefacts (per the pay-as-you-go split).

The tour names what's in each directory, calls out CLJS-specific choices, and names pattern-required behaviour. The tour does not transcribe source.

## 3. Tertiary inputs

These shape the skill's discipline but aren't quoted directly.

- **`ai/findings/re-frame2-skill-design-v2.md`** — the design rationale for the `re-frame2` skill family. This skill inherits the four pillars, the leaf-loading shape, and the Q14 lock (NO verification module).
- **`skills/re-frame-migration/`** — the closest structural analogue. Workflow skill; has `spec/` folder; targets a specific task (migration vs implementation); kickoff-prompt pattern. Voice / density / load-bearing-rules style mirrored.
- **`skills/re-frame2/SKILL.md`** + reference leaves — the canonical example of the authoring pattern. Voice, structure, "cardinal rules" framing all mirror this skill.
- **`skills/re-frame2-setup/`** — distribution-metadata triad (`LICENSE`, `package.json`, `.claude-plugin/plugin.json`) and README shape.
- **`SKILL-REDIRECT.md`** (repo root) — the canonical pointer table; this skill's audience is "Section 2 — Implementing the spec".
- **`docs/the-mayor-method.md`** — methodology context for Mike's AI-first framing. Influences the "the engineer runs their builds; the skill teaches them the workflow" framing (Q14 lock).

## 4. Anthropic skill conventions

Pulled from `https://code.claude.com/docs/en/skills` at authoring time.

The skill conforms to:

- `name` field ≤ 64 chars; lowercase + numbers + hyphens (`re-frame2-implementor`).
- `description` field is the discovery surface; "pushy" with explicit "use this skill whenever the user mentions" language; lists the triggering phrases ("porting re-frame2", "implementing re-frame2 in <language>", "writing a re-frame2 runtime", "conformance corpus", "implementor checklist").
- SKILL.md body well under 500 lines.
- Reference files one level deep from SKILL.md; no SKILL → A → B chains.
- Forward slashes in paths.
- Avoid time-sensitive content (deferred to spec-URL lookups rather than hardcoded VERSIONs).
- Per-tool `spec/` folder (per Mike's standing rule).

## 5. What the skill does NOT consume

These are deliberately out of the loop:

- **`migration/from-re-frame-v1/README.md`** — that's the migration skill's input. This skill is about new implementations, not v1→v2 migration.
- **`spec/Construction-Prompts.md`** — that's the authoring-side `re-frame2` skill's input (the per-kind AI templates for application authors writing new code).
- **`spec/Pattern-*.md`** — application patterns; not relevant to implementors.
- **`docs/guide/**`** — narrative guide for application authors.
- **`examples/reagent/**`** — worked example apps; not relevant to implementors.
- **`implementation/**` source line-by-line** — `reference-impl-tour.md` names directories and surfaces choices, but does not transcribe source.

## 6. Update procedure

When `spec/` changes, the skill needs targeted updates. The audit shape:

1. **New EP added** to `spec/` → add a section to `references/phase-2-impl-order.md`; if optional, add a row to D3 in `references/decision-record.md` template and `references/phase-1-decisions.md`'s D3 block.
2. **EP renamed / renumbered** → update every spec URL referenced in the leaves.
3. **`spec/Implementor-Checklist.md` decision added** → add a sub-decision block to `references/phase-1-decisions.md` and a template field to `references/decision-record.md`.
4. **New capability tag** added to `spec/conformance/README.md` → add a row to D7 in `references/decision-record.md`.
5. **CLJS reference implementation reorganises** → update `references/reference-impl-tour.md`'s "Layout" tree and per-EP sections.
6. **Anthropic skill conventions change materially** → reauthor SKILL.md against the new conventions.

The skill's `phase-1-decisions.md` is the integration point for the Implementor-Checklist; periodic audits grep both files for drift.

## 7. When to re-author from scratch

- **Spec corpus reorganises significantly** (more than ~3 file renames or section restructures) → the existing leaves' URL references are stale; rebuilding from the new spec layout is easier than patching.
- **The Q14 lock changes** → the design itself changes; this `spec/` folder needs updating first, then the skill.
- **A second worked reference implementation lands** → the `reference-impl-tour.md` leaf needs restructuring (each impl gets its own tour section, or the tour becomes a per-impl directory).
- **The conformance corpus's fixture format changes** → `references/conformance.md` needs a rewrite.

Otherwise, edit the existing leaves directly; reauthoring from scratch is for major-version updates.

# re-frame2-implementor — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-implementor` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — the `spec/` corpus

Path: `spec/` in the re-frame2 repo.

**The spec is the contract.** Every claim the skill makes about what an implementation must do traces to a normative claim in `spec/`. The skill's job is to **route**, **sequence**, and **operationalise** consumption of the corpus for the specific task of porting — not to duplicate it. Since the 2026-08 reduction the leaves carry links and derivation instructions only; anything enumerable (fixture counts, capability tags, operator sets) is derived from the corpus at the port's pin, never transcribed.

The most load-bearing files:

- **`spec/000-Vision.md`** — the eight-host scope footnote, the identity-primitive required properties, the host-profile matrix.
- **`spec/Implementor-Checklist.md`** — Part 1 (capability declarations), Part 2 (options-by-host for every mechanism — the profile leaf links here instead of restating), Part 3 (conformance consumption). The §Required table (incl. runtime shape policing) and §Security obligations are cited directly.
- **`spec/conformance/README.md`** — the acceptance test: fixture format, handler-DSL op table, capability tagging, versioning, harness steps. `references/conformance.md` is the operational walk of this doc and defers to it on any disagreement.
- **`spec/001-Registration.md`, `spec/002-Frames.md`, `spec/006-ReactiveSubstrate.md`, `spec/009-Instrumentation.md`, `spec/015-Data-Classification.md`** — the foundation EP owners in the EP index.
- **`spec/API.md`** — the consolidated public signatures; a standing anchor of the EP loop.
- **`spec/Ownership.md`** — the "which spec owns this surface" map; the loop's other standing anchor.
- **`spec/Conventions.md`** — the reserved `:rf/*` scheme, the three unqualified fx-ids, the `:rf/path` algebra and CEDN-1 canonical identity (cardinal rules 10–11).
- **`spec/Managed-Effects.md`, `spec/004C-Roots-and-Mount.md`, `spec/Spec-Schemas.md`** — cross-cutting obligations linked from the EP index (reply envelope, root-attempt evidence, `:rf/effect-map`).

The skill cites spec files by the published docs URL (`https://day8.github.io/re-frame2/spec/<file>/`), with anchor links for sections.

## 2. Secondary input — the CLJS reference implementation

Path: `implementation/` in the re-frame2 repo.

**The reference is a worked example, not normative.** There is no dedicated tour leaf since the 2026-08 reduction: a port author who wants to see how *someone* solved a problem opens the matching artefact directory at the pin (`implementation/core/` for the runtime heart, `implementation/adapters/*` for the React bindings, per-feature artefacts `machines`/`routing`/`flows`/`http`/`schemas`/`ssr`/`epoch`), reads the source, and tests everything against `spec/` before adopting it.

## 3. Tertiary inputs

- **`skills/re-frame-migration/`** — the closest structural analogue (workflow skill, spec/ folder, cardinal-rules voice).
- **`skills/re-frame2/SKILL.md`** — the canonical authoring pattern for voice and structure.
- **`skills/README.md`** — the leaf-size discipline (≤250 lines / ≤16 KB per leaf), the published-skill `allowed-tools` baseline, and the verification-posture table this skill's row must stay consistent with.

## 4. Repo tooling this skill is wired into

A re-authoring pass MUST keep these green (they run on every PR):

- **`scripts/check_skill_implementor_order.py`** — foundation-order + required-foundation-gate guard over SKILL.md, README.md, cardinal-rules.md, phase-1-decisions.md, phase-2-impl-order.md, and this spec/ folder's design.md + authoring-prompt.md. Any line stating the foundation order must include both v1-required tail EPs, 015 and 013; any line pinning the gate-1 fixture scope must name all four v1-required families. Its owner cross-check is two-sided: `references/conformance.md` §Capability tagging must still teach every required root, AND the root set is derived from the normative owner, `spec/Implementor-Checklist.md` Part 3's always-run family rows — checking the skill alone is circular, and that circularity is what let `:flow/*` go missing from the skill and the guard at once.
- **`scripts/check_skill_implementor_partition_drift.py`** — stale-API denylist (listener verbs, managed-HTTP naming, adapter lifecycle, machine classification keys, reply-contract spellings) plus the no-bead-id scan over the user-facing leaves.
- **`scripts/check_skill_mcp_drift.py`** — keys on the paths `references/cardinal-rules.md` (spec-pin `rev-parse` command; the local gh-issue body/title/search safety clauses) and `references/phase-1-decisions.md` (spec-pin `remote get-url` command). Keep those filenames and the literal commands/clauses.
- **`scripts/check_adapter_disposition.py`** — rosters `references/phase-2-impl-order.md` as a scanned authority; keep that filename.

## 5. What the skill does NOT consume

- `migration/from-re-frame-v1/README.md` (the migration skill's input), `spec/Construction-Prompts.md` and `spec/Pattern-*.md` (application-side), `docs/core/**` (narrative guide), `examples/**`.

## 6. Update procedure

When `spec/` changes:

1. **New EP added** → a row in `phase-2-impl-order.md`'s EP index (and a scope question in `phase-1-decisions.md` if optional).
2. **EP renamed / renumbered** → sweep the leaves' spec URLs.
3. **New always-claimed capability family** → `conformance.md` §Capability tagging (and the order guard's family set — reconcile with `scripts/check_skill_implementor_order.py`).
4. **Fixture-format / DSL changes** → nothing, usually: the leaves teach derivation at the pin rather than carrying the catalogue. Only a change to the *derivation procedure itself* (new metadata keys, a new fail-loud floor) touches `conformance.md`.
5. **Verification-posture change** → SKILL.md §Verification + `skills/README.md`'s posture row + design.md L3, together.

## 7. When to re-author from scratch

- The spec corpus reorganises significantly (many renames) → rebuild the URL surface from the new layout.
- The two-phase shape itself changes → update `design.md` first, then the skill.
- Otherwise, edit the existing leaves directly.

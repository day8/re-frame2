# re-frame2-implementor — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-implementor` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2-implementor` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help an engineer **implement** the re-frame2 pattern in a different host language — not write applications on the existing CLJS reference, not migrate from re-frame v1, not bootstrap a fresh greenfield project on the reference. Build the runtime itself.

The skill's success criterion: the engineer ends up with a port that claims a specific capability tag set, has a compact committed **port profile** covering every choice actually made, and passes the matching subset of the [conformance corpus](https://day8.github.io/re-frame2/spec/conformance/) at `claimed-applicable / claimed-applicable`.

The skill is **guidance + workflow** layered on top of `spec/`. The skill does not duplicate the spec; it routes / sequences / operationalises consumption of it for the specific task of porting. This was restated as a hard shape rule in the 2026-08 reduction (see §6): the pinned spec and the live fixtures are the working material; the skill's leaves are an index and a loop, never a second rendering of the contract.

## 2. Pillars

1. **Correctness** — workflow over explanations. The skill walks the two phases; the engineer (with their session) makes the decisions. Verification posture per L3 below.
2. **Idiomaticness** — verified against `spec/` + `spec/Implementor-Checklist.md` + `spec/conformance/`. The skill is downstream of the spec; if the spec is authoritative, the skill is correct by construction.
3. **Context economy** — `SKILL.md` is a router; four small leaves load on demand, each under the family's 16 KB ceiling. The leaves cite spec sections by URL — citations of live main; every contract read resolves through the profile's verified checkout at the pin, per cardinal rule 1 — and teach *derivation* (grep the corpus at the pin) instead of carrying catalogues that age.
4. **Assume training knowledge** — the engineer knows what reactive substrates, FSMs, persistent data structures, and EDN are. The skill teaches the **re-frame2-specific binding**: which choices are real, which EPs depend on which, how the conformance corpus grades a claim.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve these unless explicitly unlocked by Mike.

### L1 — `spec/` is the contract; `implementation/` is one worked example

The skill never treats the CLJS reference as normative. Per-EP "worked example" pointers name reference artefact directories descriptively; the spec wins on any disagreement. (The former dedicated reference-impl tour leaf was retired in the 2026-08 reduction — the reference tree itself, consulted directly at the pin, is the tour.)

### L2 — Two-phase workflow, without ceremony

Phase 1 (the port profile) before Phase 2 (the EP loop), sequentially. The 2026-08 reduction removed the ceremony that had accreted around the split — the question-per-block interview, the 254-line fill-every-placeholder decision dossier, the per-EP session/commit/report mandates — while keeping the split itself: choices are recorded before code because they propagate through every line of it.

### L3 — Verification follows tool access; no generic build teaching

The skill teaches no generic build/test mechanics (Pillar 4). But it is an **implementation driver**, so the agent runs the port's **discovered noninteractive gates** itself when it has tool access: the narrowest slice covering each loop step, and the full required-foundation / claimed-capability conformance passes when the engineer asked for an end-to-end implementation — always reporting exact commands, exit codes, and `passed / claimed-applicable`. Genuinely interactive/visual evidence remains a concise programmer handoff, and completion is never claimed while required evidence is pending. The agent uses the host session's normal permissions; there is no skill-local engineer/agent relay policy. (This supersedes the earlier narrower "per-EP slice only; full gates engineer-owned by default" posture: an explicitly-invoked, code-writing agent with tool access runs the gates it discovers.)

**Family-consistency note (posture follows role).** `re-frame-migration` keeps its hard trust boundary (the agent never runs build/test/smoke in the author's app env); `re-frame2` authoring emits recipes a human pastes. Those postures are correct for their role cells; this skill's role is the only implementation driver whose acceptance criterion *is* spec-conformance.

### L4 — Substrate-agnostic phrasing throughout

References to "the identity primitive", "the render-tree", "the reactive container" stay generic; CLJS bindings appear only as explicitly-descriptive worked-example pointers.

### L5 — Conformance corpus is the acceptance test — and the working loop

`spec/conformance/` is the objective measure of "is this re-frame2?", and since the 2026-08 reduction it is also the *working material*: fixture facts (capability tags, operator sets, spec versions, counts) are **derived at the pin** by the harness and the agent, never transcribed into skill prose where they age. The harness bootstraps before or alongside the first foundation slice, and fails loud on unknown spec versions, capabilities, DSL ops, and call ops.

### L6 — Spec gaps file GitHub issues, not silent extrapolations

Draft → show the engineer → explicit OK → `gh issue create` with `--body-file`, per the shell-safety recipe in `references/cardinal-rules.md` §8. `bd` is monorepo-internal and never invoked from a published skill.

### L7 — No bead-ids in user-facing skill content

SKILL.md, README.md, and the references/ leaves carry no `rf2-XXXX` tracker ids. This `spec/` folder may reference beads (authoring context); user-facing leaves do not.

### L8 — Findings stay local

Exploration happens in `ai/findings/`; never committed.

### L9 — No AI attribution in commits or PRs

Commits and PR titles/bodies read as Mike's own work.

### L10 — Cross-link bidirectionally

`docs/skills/re-frame2-implementor.md` and `mkdocs.yml` carry the docs-side page; the application-side skills point implementors here; SKILL.md's "When NOT to use" routes back.

## 4. Audience and scope

### In scope

Engineers porting re-frame2 to one of the eight in-scope **JS-cross-compile-to-React+VDOM** hosts (per `spec/000-Vision.md` §scope footnote), consuming `spec/Implementor-Checklist.md` and `spec/conformance/`.

### Out of scope

Non-cross-compile-to-JS hosts and non-React substrates (surface the scope footnote and stop); application authoring, greenfield bootstrap, v1 migration, live-runtime inspection (sibling skills); proposing a different pattern; editing `spec/` inline (gaps file upstream issues).

## 5. File structure (locked at the 2026-08 reduction)

```
skills/re-frame2-implementor/
├── SKILL.md (router)
├── README.md (human-facing intro)
├── LICENSE (MIT)
├── package.json (npm metadata)
├── .claude-plugin/plugin.json (Claude Code plugin metadata)
├── evals/evals.json (trigger-accuracy fixtures for the description)
├── references/
│ ├── cardinal-rules.md (the eleven rules + anti-pattern corollaries)
│ ├── phase-1-decisions.md (the port profile: defaults, spec pin, template)
│ ├── phase-2-impl-order.md (the EP loop + the EP index)
│ └── conformance.md (harness, capability derivation, scoring, diagnosis)
└── spec/
 ├── design.md (this file)
 ├── inputs.md (canonical inputs)
 └── authoring-prompt.md (one-shot reauthor prompt)
```

Two filename constraints are load-bearing for repo tooling and MUST survive any re-authoring: `references/cardinal-rules.md` and `references/phase-1-decisions.md` are keyed by path in `scripts/check_skill_mcp_drift.py` (the spec-pin provenance and gh-issue title-safety rules), and `references/phase-2-impl-order.md` is on `scripts/check_adapter_disposition.py`'s scanned-authority roster.

## 6. The 2026-08 reduction — why this leaf split

The previous shape shipped SKILL.md + eight reference leaves totalling ~290 KB / ~37K words, with a 119 KB Phase-2 leaf that mirrored the spec contract EP by EP; six leaves exceeded the family's 16 KB ceiling, and the copied catalogues (fixture counts, capability tables, op lists) aged against the corpus. The reduction replaced the dossier with the compact port profile, the contract mirror with an EP index + one uniform loop, and every copied catalogue with a derive-at-the-pin instruction. The shipped operational prose is now four leaves, each under 16 KB, ~40 KB total including SKILL.md — a net material reduction of roughly 85% — and the default route to begin work is SKILL.md plus one leaf (`phase-1-decisions.md`).

Typical session loads: profile session = SKILL.md + phase-1-decisions.md; an EP slice = SKILL.md + phase-2-impl-order.md (+ conformance.md at gates); the spec sections and fixtures the session actually reads are the pinned corpus's, not the skill's.

## 7. Resolutions worth keeping (amended at the reduction)

- **Prose walkthrough over decision trees** for the profile's choices — the option matrices live in the Implementor-Checklist; the leaf frames the choice and links.
- **Per-EP checkpoints without session/commit mandates.** The EP is the unit of the loop, and acceptance gates sit between the foundation cluster (001 / 002 / 006 / views / 009 / 015 / 013) and the optional EPs (gate 1 — all four v1-required families: `:core/*` + `:identity/*` + `:flow/*` + `:data-classification/*`), and between full claim and ship (gate 2). But one-EP-per-session, per-EP commits, and the three report templates were ceremony, not correctness — a competent implementor chooses granularity; a checkpoint reports changed decisions, code/result, exact command + outcome, conformance delta, blockers.
- **Early feedback is structural.** Harness bootstrap is loop step 0, not an afterthought after five EPs — "no port script yet" is admissible only before the first foundation slice lands.
- **Descriptive/normative split without a tour leaf.** Worked-example pointers ride the EP index rows; the reference source at the pin is read directly when wanted.

## 8. Where this design diverges from `re-frame2`

- No patterns/ directory (application patterns are for authors, not implementors).
- The conformance leaf is unique to this skill — applications run their tests, not a normative corpus.
- The kickoff shape assumes a fresh port repo plus a pinned re-frame2 clone; the paste-ready prompt is a short section of SKILL.md, not a leaf.

## 9. Anti-patterns the skill explicitly resists

- Treating the CLJS reference as normative (L1).
- Skipping the profile, or interviewing for it (L2).
- Inventing surfaces when the spec is silent (L6).
- Landing EPs on "no port script yet" (L5 — the seam bootstraps first).
- Re-growing contract mirrors: a leaf that quotes a fixture count, a capability catalogue, or an op list has already started to age — teach the derivation instead.

## 10. Open questions

### OQ4 — Skill `name` — noun phrase vs gerund

`re-frame2-implementor` matches the project-name pattern; Anthropic's gerund guidance is "consider", not "must". **Recommendation: keep.** Status: Mike's call.

### OQ5 — Stale-detection against the spec corpus

The skill cites spec sections by URL; heading shifts break cross-references. Mitigated by the reduction (far fewer citations) and by the repo's link gates; a periodic audit bead remains the fallback. Status: deferred.

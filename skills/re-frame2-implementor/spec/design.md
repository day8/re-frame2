# re-frame2-implementor — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-implementor` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2-implementor` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help an engineer **implement** the re-frame2 pattern in a different host language or substrate — not write applications on the existing CLJS reference, not migrate from re-frame v1, not bootstrap a fresh greenfield project on the reference. Build the runtime itself.

The skill's success criterion: the engineer ends up with a port that claims a specific capability tag set from [`spec/Implementor-Checklist.md`](https://day8.github.io/re-frame2/spec/Implementor-Checklist/) Part 1, has a written-down decision record covering every Phase 1 choice, and passes the matching subset of the [conformance corpus](https://day8.github.io/re-frame2/spec/conformance/) at `claimed-applicable / claimed-applicable`.

The skill is **guidance + workflow** layered on top of `spec/`. The skill does not duplicate the spec; it routes / sequences / operationalises consumption of it for the specific task of porting.

## 2. Pillars (locked, derived from `ai/findings/re-frame2-skill-design-v2.md`)

The same four pillars as the application-side `re-frame2` skill, adapted to the implementor domain:

1. **Correctness** — workflow over explanations. The skill walks the two phases; the engineer (with their session) makes the decisions. **Q14 lock applies: NO verification module** — the skill teaches no generic build/test mechanics (Pillar 4: don't teach what the AI already knows). It does, however, carry a narrow **per-EP slice gate** (L3): when the agent writes EP code and has local tool access, it runs the smallest relevant slice it can determine from the *port's own* scripts before calling the EP landed, or reports it couldn't. The engineer still owns the expensive/full conformance gates.
2. **Idiomaticness** — verified against `spec/` + `spec/Implementor-Checklist.md` + `spec/conformance/`. The skill is downstream of the spec; if the spec is authoritative, the skill is correct by construction.
3. **Context economy** — `SKILL.md` is a router; leaves are loaded on demand. The leaves point at spec sections by URL; they don't quote them.
4. **Assume training knowledge** — the engineer knows what reactive substrates, FSMs, persistent data structures, and EDN are. The skill teaches the **re-frame2-specific binding** — which decisions are foundational, which EPs depend on which, what the conformance corpus is for.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve these unless explicitly unlocked by Mike.

### L1 — `spec/` is the contract; `implementation/` is one worked example

The skill does not treat the CLJS reference as normative. Every "the reference did X" framing in `references/reference-impl-tour.md` is explicitly tagged as descriptive. Engineers reading the tour are told repeatedly that the spec wins.

**Why**: re-frame2's stated goal is implementability from the spec alone. A skill that conflates the reference with the contract undercuts that goal.

### L2 — Two-phase workflow

Phase 1 locks decisions before any code is written. Phase 2 walks the EP corpus in dependency order. The phases are sequential — Phase 2 cannot start before Phase 1 produces a locked decision record.

**Why**: every Phase 1 decision propagates through every line of Phase 2 code. Engineers who skip Phase 1 to "just start coding" hit foundation-level rewrites halfway through. The phase split is operational, not advisory.

### L3 — Q14 — NO verification module, but a narrow per-EP slice gate

Per `ai/findings/re-frame2-skill-design-v2.md` §Q14: the skill teaches no generic verification mechanics — no `references/verify.md`, no generic-build tutorial — but it does NOT leave a code-writing skill with zero feedback loop. The implementor skill is an **implementation driver** (the kickoff prompt tells the session to write the EP code; each EP wrap-up reports tests + fixtures exercised), so the verification contract distinguishes **generic build mechanics** (not taught — Pillar 4) from the **skill-specific per-EP acceptance slice** (taught):

- **When the agent writes EP code AND has local tool access:** before calling the EP landed, it runs the **smallest relevant slice gate it can determine from the *port's own* scripts** (the port's unit-test command for the EP's module, or a targeted conformance subset for the EP's capability tags) — NOT the full suite, NOT a build mechanic it had to invent. If it cannot determine or run a slice (no local tooling, no port script yet), it says so explicitly in the EP wrap-up with a not-run reason, rather than silently deferring all feedback to the human.
- **The engineer still owns the expensive/full gates:** the full claimed-capability conformance pass (acceptance gate 2) and any release-sized suite stay engineer-driven. The agent surfaces and diagnoses scores there; it does not drive the engineer's toolchain for the heavy gates.

The final EP wrap-up records exact commands/results or a clear not-run reason, not only a prose "tests pass" claim.

**Why**: a code-writing skill that walks multi-day runtime changes and leaves the first real feedback to the human or a later full-suite gate is weaker workflow than the rest of the repo's worker discipline. The elegant path for a pre-alpha implementation guide is to teach the intended slice gate without becoming a generic testing tutorial.

**Family-consistency note (resolved: verification posture follows role; the divergence is by design, not drift).** The original L3 cited cross-family consistency with the `re-frame2` and `re-frame-migration` skills (which also lock NO verification module). The slice gate stays **strictly scoped to `re-frame2-implementor`** — each skill occupies a different role cell, and posture follows role:

- **`re-frame-migration`** carries a hard trust boundary (its cardinal rule bars the agent from running build / test / smoke in the author's app environment). Propagating the slice gate would *violate* that boundary.
- **`re-frame2` authoring** is locked at Pillar 4 / Q14 to emit recipes a human pastes — there is no runtime the agent drives and no EP conformance corpus, so a slice gate is vacuous there (or manufactures the very violation Q14 prevents).
- **`re-frame2-implementor`** is the only implementation driver whose acceptance criterion *is* spec-conformance, which the per-EP slice gate operationalises.

Uniform wording across the family would buy incoherence of principle: under "posture follows role" these postures are correct, not inconsistent.

### L4 — Substrate-agnostic phrasing throughout

The reference impl is CLJS+Reagent — keywords, hiccup, Reagent ratoms, Closure DCE for elision. These are *choices the reference made*. The skill's voice never leaks them as universal. References to "the identity primitive", "the render-tree", "the reactive container", "the production-elision mechanism" stay generic; the CLJS bindings are surfaced only in `references/reference-impl-tour.md`, and that leaf is explicit about descriptive-not-normative framing.

**Why**: a TypeScript-port engineer reading the skill should never feel like they're "diverging from re-frame2" when they pick branded strings over keywords. They're not diverging; the keyword choice was never the contract.

### L5 — Conformance corpus is the acceptance test

The skill frames `spec/conformance/` consistently as the **objective measure of "is this re-frame2?"** Not the spec's prose, not the EPs' narrative, not the reference impl's behaviour — the corpus. The leaf `references/conformance.md` walks the harness shape and the diagnosis algorithm (spec gap vs implementation bug).

**Why**: without the corpus passing, no claim of "this is a re-frame2 implementation" is objectively verifiable. The corpus is the contract surface for downstream consumers.

### L6 — Spec gaps file GitHub issues, not silent extrapolations

When implementing surfaces a spec gap — a missing surface, an inconsistency, an undocumented decision — the agent files a GitHub issue against `day8/re-frame2`. Does not silently invent an answer; does not extrapolate from the reference.

**Tracker boundary**: `bd` (beads) is the re-frame2 monorepo's internal tracker. Published skills run in *consumer* repos (an engineer's port repo, in this skill's case) and must never invoke `bd` — cross-repo side effects target the *target* repo's GitHub issues. The published skill announces the cross-repo filing before it lands (cardinal rule 9), and passes the body via `gh`'s native `--body-file` flag (the body is `Write`-ten to a file first), never inline interpolation (shell-safety baseline in `skills/README.md`).

**Why**: spec gaps are findings. The reference's prior solution to a spec gap was *one engineer's call at one moment*. Treating it as the contract retroactively conflates worked-example and contract — undercutting L1.

### L7 — No bead-ids in user-facing skill content

User-facing skill content (SKILL.md, README.md, the references/ leaves) carries no `rf2-XXXX` bead-id references. Bead ids are workflow-tracking; they're noise in the skill that the engineer is using. The skill's `spec/` folder may reference beads (workflow / authoring); user-facing leaves do not.

**Why**: the bead-id sweep landed for `docs/`; same discipline applies here.

### L8 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — any exploration of the design happens in `ai/findings/`; never committed. This skill's commit contains only `skills/re-frame2-implementor/**` (plus the cross-link tweaks in the other skills and mkdocs).

### L9 — No AI attribution in commits or PRs

Per Mike's standing memory rule — commits and PR titles/bodies read as Mike's own work. No Co-Authored-By, no "Generated with Claude Code" trailer.

### L10 — Cross-link bidirectionally

The new skill is cross-linked from:
- `skills/re-frame2/SKILL.md` — one-liner pointing implementors here.
- `skills/re-frame-migration/SKILL.md` — same.
- `docs/skills/re-frame2-implementor.md` — new sub-page in the Skills nav.
- `mkdocs.yml` — new entry in the Skills nav.

Reverse cross-links (this skill → the application-side skills) live in SKILL.md's "When NOT to use this skill" table.

## 4. Audience and scope

### In scope

- Engineers porting re-frame2 to one of the eight in-scope **JS-cross-compile-to-React+VDOM** hosts: ClojureScript (the reference), TypeScript, Melange / ReScript / Reason, Fable (F#), Squint, Scala.js, PureScript, Kotlin/JS. These are the *only* implementation targets the spec commits to (per `spec/000-Vision.md` §scope footnote).
- Engineers consuming `spec/Implementor-Checklist.md` and `spec/conformance/`.

### Out of scope

- **Non-cross-compile-to-JS hosts** (Python, Ruby, native Rust, Go, server-side Kotlin / Java / Swift) and **non-React substrates** (Vue, Solid, Svelte, vanilla DOM, Replicant, Lit, native UI, terminal, server-only). These are out of scope by deliberate spec choice — not an oversight (`spec/000-Vision.md` Goal 8; `spec/Implementor-Checklist.md` §Scope). The skill surfaces the scope footnote and stops; it does not sequence an implementation track for them.
- Writing application code on the CLJS reference — that's `skills/re-frame2/`.
- Bootstrapping a greenfield app on the reference — that's `skills/re-frame2-setup/`.
- Migrating a v1 codebase to the reference — that's `skills/re-frame-migration/`.
- Live-runtime inspection of a running v2 app — that's `skills/re-frame2-pair/`.
- Proposing a *different* pattern — re-frame2's pattern is specified; engineers proposing alternatives need a different conversation.
- Editing `spec/` — gaps file GitHub issues against `day8/re-frame2`; the skill never patches `spec/` inline.

## 5. File structure (locked)

```
skills/re-frame2-implementor/
├── SKILL.md (router)
├── README.md (human-facing intro)
├── LICENSE (MIT)
├── package.json (npm metadata)
├── .claude-plugin/plugin.json (Claude Code plugin metadata)
├── evals/evals.json (trigger-accuracy fixtures for the description)
├── references/
│ ├── cardinal-rules.md (the eleven rules in prose + anti-pattern corollaries)
│ ├── kickoff-prompt.md (paste-ready prompt)
│ ├── phase-1-decisions.md (Phase 1 walkthrough)
│ ├── decision-record.md (fill-in template)
│ ├── phase-2-impl-order.md (EP-by-EP order)
│ ├── reference-impl-tour.md (CLJS tour, descriptive)
│ ├── conformance.md (harness shape, diagnosis)
│ └── output-format.md (agent-output shape)
└── spec/
 ├── design.md (this file)
 ├── inputs.md (canonical inputs)
 └── authoring-prompt.md (one-shot reauthor prompt)
```

## 6. Why this leaf split

The eight reference leaves are sized to load on demand without spending context budget on irrelevant detail. Typical session loads:

- **Phase 1 walkthrough**: `phase-1-decisions.md` + `decision-record.md`. ~320 LoC.
- **Phase 2, single EP**: `phase-2-impl-order.md` (one section) + maybe `reference-impl-tour.md` (one section) + maybe `conformance.md`. ~250-400 LoC.
- **Conformance pass**: `conformance.md` + `output-format.md`. ~260 LoC.
- **Full v1 ship**: every leaf. ~1,060 LoC.

Worst case is ~1,000 LoC, well under any reasonable context budget; the median Phase 2 step loads ~300 LoC.

## 7. Resolutions to the three open questions in the bead

The bead surfaced three open questions; the skill resolves them as follows.

### OQ1 — Decision-tree vs prose-prompt for Phase 1

**Resolved: prose walkthrough.** `phase-1-decisions.md` is a structured walkthrough — one section per decision block, each section with question / what's at stake / options / how to choose / where the spec speaks. Not a yes/no decision tree.

**Why**: yes/no trees over-simplify the decisions (F1 identity primitive has 8+ options per host, not 2; D3 scope has 7 independent questions). A prose walkthrough that names the options + their trade-offs respects the engineer's expertise. The walkthrough is *structured* (consistent block shape per decision) so it's predictable, but the decision is made in prose, not by walking a binary tree.

### OQ2 — Granularity of Phase 2 — one EP at a time, or clusters?

**Resolved: one EP per session, with explicit acceptance gates between groups.** `phase-2-impl-order.md` is structured as one section per EP. Between the foundation cluster (001 / 002 / 006 / 004 / 009 / 015) and the optional EPs sits **acceptance gate 1** — running the `:core/*` conformance fixtures. (015 Data Classification is v1-required, not optional — `spec/015-Data-Classification.md` marks it required and `spec/API.md` exposes the frame-owned `:sensitive`/`:large` classification + `project-egress` boundary as v1 per EP-0015 — so it sits inside the foundation cluster, riding the 009 emission boundary, ahead of the gate.) Between full claimed-capability implementation and v1 ship sits **acceptance gate 2** — full claimed-capability conformance pass.

**Why**: per-EP sessions are tractable; per-EP commits are reviewable. The CLJS reference's largest EP (005 State Machines, ~2,900 spec lines) takes multiple sessions in any host, but those sessions are still about *one* EP — the unit is right. Clustering the foundation EPs into "do them all together" would lose the per-EP wrap-up checkpoint, which is where the engineer catches Phase 1 decisions that need revision.

The acceptance gates are operational, not advisory: gate 1 between core + optional means a port can declare v1-core-complete with only the foundation EPs (001 / 002 / 006 / 004 / 009 / 015 — 015 is v1-required and inside the gate, so "core-complete" is never reachable without it), and gate 2 between optional + ship means the full claim is verified before public release.

### OQ3 — How to annotate CLJS-specific framings when pointing at the reference impl

**Resolved: a dedicated descriptive tour leaf with explicit framing.** `references/reference-impl-tour.md` is the only leaf that walks the CLJS implementation's structure. It's explicit at the top and bottom that the tour is **descriptive, not normative**; every "the reference did X" is paired with a "what's CLJS-specific" callout and a "what's pattern-required" framing. Engineers consult the tour when they want to see how someone solved a problem; they consult `spec/` and the other leaves when they want to know what's required.

**Why**: separating descriptive (tour) from normative (spec) at the leaf level is the cleanest split. Annotating individual references in other leaves with "this is the CLJS shape; your $LANG will differ on…" would clutter every leaf with the disclaimer and bury the workflow signal. The tour is the place for descriptive framing; the rest of the skill stays in normative voice.

## 8. Where this design diverges from `re-frame2`

- **No patterns/ directory.** Implementation is one workflow per EP, not a set of authoring recipes. The patterns from `Pattern-*.md` are for application authors, not implementors.
- **No decision-trees/ directory.** Phase 1 is the entire decision tree, captured in `phase-1-decisions.md`. The other Phase 2 decisions are EP-internal and live in `phase-2-impl-order.md`.
- **No examples-map.md.** The reference impl is the one example; `reference-impl-tour.md` covers it.
- **The conformance leaf is unique.** The application-side skills don't have a conformance concept — applications run their tests, not a normative corpus. Implementors do.
- **The kickoff prompt assumes a fresh repo, not an existing codebase.** Application-side skills (`re-frame2`, `re-frame-migration`) attach to an existing project. This skill attaches to a fresh port's repo (or an empty workspace) plus a clone of the re-frame2 repo for the spec.

## 9. Anti-patterns the skill explicitly resists

- **Treating the CLJS reference as normative.** Cardinal rule #1 in SKILL.md; L1 in this design.
- **Skipping Phase 1.** Cardinal rule #2 in SKILL.md; the kickoff prompt is explicit about Phase 1 before Phase 2.
- **Inventing surfaces when the spec is silent.** Cardinal rule #8 + the GH-issue-files-not-extrapolation rule in `reference-impl-tour.md` and `conformance.md`.
- **Skipping the conformance corpus.** L5; the leaf `conformance.md` frames it as the acceptance test.
- **Bundling CLJS-specific framings into normative voice.** L4; the tour leaf is the only place CLJS framings appear, and even there they're explicitly tagged as descriptive.

## 10. Open questions (deferred to Mike)

These remain open at authoring time:

### OQ4 — Skill `name` — is `re-frame2-implementor` Anthropic-compliant?

Anthropic's current best-practices doc says "consider using" gerund form. `re-frame2-implementor` is a noun phrase. Compliance is "consider," not "must"; the description does the discovery heavy lifting; the noun phrase matches the project-name pattern (`re-frame2`, `re-frame2-setup`, `re-frame-migration`, `re-frame2-pair`). **Recommendation: keep `re-frame2-implementor`.** Status: Mike's call.

### OQ5 — Will the skill stale-detect against the spec corpus?

The skill cites spec sections by URL. If the spec's section headings shift (a routine maintenance event), the skill's cross-references break. **Recommendation**: a periodic audit bead, like the one for `re-frame-migration` against `migration/from-re-frame-v1/README.md`. **Status**: deferred to v0.2; not blocking v0.1.

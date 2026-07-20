# reagent-migration — Inputs

> **Skill-internal meta-doc.** The canonical inputs the skill leans on — not
> part of the skill contract. Not loaded during normal operation; a
> re-authoring pass needs these to reproduce the leaves. For the skill
> contract, see [`SKILL.md`](../SKILL.md).

## 1. Primary input — the `MIG-01…35` rule table

The framework's canonical Reagent→re-frame.ui rule table (the W1/W2/S6 rulebook) is the source of the rule ids, tiers, detectors, and transform shapes the skill distils. It lives in the re-frame2 repo's local design corpus (`ai/findings/new-substrate-synthesis/prep/`, gitignored) and is grounded in the normative specs below. **The skill does not re-host it** — it uses the `MIG-NN` ids as a shared, auditable vocabulary and distils the rules into M/D/R guidance with before→after examples (design L3).

Structure the skill depends on:

- **The rule rows** — each carries an input construct, a detector, a transform, a tier (M/D/R), and notes. The skill's three catalogues (`catalog-mechanical`, `catalog-judgment`, `catalog-reject`) partition these by *what the author does with the rule*.
- **§Ordering** — the gate-before-body law (whole-view coherence), the closed-subtree law, and within-view rewrite order. Drives `procedure.md` and the whole-view cardinal rule.
- **§Non-goals** — no behaviour inference, no cross-file moves, dataflow untouched, no staged output. Drives design L1/L5 and the staged-gap gotcha.

## 2. Secondary input — the shelved tool's golden fixtures

The shelved `tools/ui-migrator` (rewrite-clj, PR #6541) carried ~47 golden before→after fixtures — one per `MIG-NN` rule plus adversarial cases (data-vectors-not-hiccup, the bare-symbol child, whole-view gating, idempotence). They are **worked examples**, read to ground the catalogue's before→after blocks and the eval inputs. The tool itself is **not** revived and its rewrite-clj code is **not** copied — only the fixtures' input→output shapes inform the examples.

## 3. Normative grounding — the specs

The rule table is itself verified against these; the skill is downstream of that verification and does not re-derive it:

- **`spec/004-Views.md`** — the compiled-view output-template grammar, the handler law, the removed forms, `ui/defview`, `sub`, `local`, `effect`, the handler forms.
- **`spec/004B-UI-Tree-and-Conversion.md`** — prop-spelling / conversion legality (the MIG-11 name table, the `ui/html` node form, `ui/spread` conversion).
- **`spec/004C-Roots-and-Mount.md`** — the mount grammar and root identity (MIG-15/33).
- **`implementation/ui/src/re_frame/ui.cljc`** — the shipped export surface. A rewrite target is only emitted if it is exported here; staged-but-unshipped surfaces (the `ui/->react` bridge, the `sub` frame-pin) are named as gaps, never emitted. (SSR `render-static`/`hydrate-root` and compiled `route-link` are exported here and so are real transforms.)

## 4. Tertiary inputs (shape the discipline, not quoted)

- **The four-pillar design rationale** — inherited from the skill family (leaf-loading shape, the four pillars). Reproduced in `design.md` §2 so this folder is self-contained.
- **`skills/re-frame-migration/`** — the closest structural sibling (SKILL.md router + references + spec + the distribution triad). Voice, density, front-matter shape, the "cardinal rules" style all mirror it.
- **`skills/re-frame2/SKILL.md`** — the canonical authoring-pattern example for voice and load-bearing-rules density.

## 5. What the skill does NOT consume

- **The v1→v2 corpus** (`migration/from-re-frame-v1/README.md`, the `M-N`/`O-N` rules) — that is the *other* migration (`re-frame-migration`), the required first step. This skill assumes it is done.
- **`examples/**`** — worked examples are for authoring, not for this view migration.
- **The full EP corpus** — the skill assumes the author knows re-frame2 conceptually (or reads the EPs via the `re-frame2` skill).

## 6. Update procedure

When the framework's `MIG` rule table changes:

1. **A rule's tier changes** (M↔D↔R) → move its treatment between `catalog-mechanical` / `catalog-judgment` / `catalog-reject`, and re-check `procedure.md`'s gate list.
2. **A new rule is added** → add a before→after (M) or a decision (D) or a hold (R) to the matching catalogue, and add a fixture-grounded eval if it exercises a new class.
3. **A staged capability SHIPS** (the remaining gaps are `ui/->react` and the `sub` frame-pin; SSR `render-static`/`hydrate-root` and compiled `route-link` already made this move) → move it out of `catalog-reject.md`'s capability-gap section into the mechanical/judgment catalogue with its now-real target, and update the staged-gap gotcha. **Verify against `ui.cljc`'s exports before trusting a "shipped" claim** — the staged-target caveat is a claim about that file.
4. **The shipped export surface changes** → re-verify every emitted target against `implementation/ui/src/re_frame/ui.cljc`.

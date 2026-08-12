# reagent-migration — Inputs

> **Skill-internal meta-doc.** The canonical inputs the skill leans on — not
> part of the skill contract. Not loaded during normal operation; a
> re-authoring pass needs these to reproduce the leaves. For the skill
> contract, see [`SKILL.md`](../SKILL.md).

## 1. Primary input — the shipped Freehand surface

The **exported roster** is the primary input, because it is the only thing that
decides whether a rewrite target exists:

- **`spec/API.md` §Freehand views** — the tiered var catalogue for
  `re-frame.freehand` and its test sibling `re-frame.freehand.test`. A target is
  emitted only if it has a row here.
- **`docs/api/re-frame.freehand.md`** and **`docs/api/re-frame.freehand.test.md`**
  — per-var signatures, options rosters and worked examples. These are the
  fastest read when checking a call shape.

The current door, for orientation: `defview`, `mount`, `hydrate-root`,
`unmount!`, `render-static`, `view?`, `describe`, `manifest`, `sub`, `event`,
`handler`, `render-fn`, `raw-fn`, `projections`, `materialize-event`, `slot`,
`spread`, `spread-safe`, `controller-key`, `controller-revision`,
`controller-current?`, `presence`, `presence-phase`, `route-link`, `markup`,
`error-boundary`, `defbehavior`, `behavior` — plus `render` / `with-render` /
`find` / `find-all` / `attrs` / `text` on the test surface. **Re-read the roster
rather than trusting this list**; it grows a row at a time.

## 2. Normative grounding — the specs

- **`spec/004-Views.md`** — the contract: the descriptor and `v/defview`, props
  and `:key`, vector-head classification, the ambient frame, `v/sub`, event
  intent and the payload materializer, callback roles, controlled inputs,
  semantic controllers, presence, error boundaries, composition and render
  slots, props forwarding, registered behaviors, and — load-bearing for this
  skill — **§Normative absences**, which is why `local`, `ref` and `effect` have
  no target.
- **`spec/004B-UI-Tree-and-Conversion.md`** — prop-spelling and conversion
  legality (the MIG-11 name table), and the node schema a structural test reads.
- **`spec/004C-Roots-and-Mount.md`** — the mount grammar, root identity, frame
  preflight, and total teardown (MIG-15).
- **`spec/004D-Freehand-Compiled-Grammar.md`** — the compiled tier's finite
  grammar. Read it to understand what a *promotion* costs; the skill does not
  emit `{:compiled true}` during a migration.
- **`spec/008-Testing.md`** and **`spec/011-SSR.md`** — the structural test tier
  and the SSR paths (MIG-23).

## 3. Where the design corpus is a HAZARD, not an input

`docs/EP/EP-0036-*` and `docs/design/freehand/` describe the design, including
forms that are **declared and not exported** — `local`, `effect`, `ref`,
`v/check`, a React interop hook tier. They are useful for understanding *why* a
shape is the way it is, and dangerous as a source of call shapes: some names once
on this list (`v/->react`, `v/client-only`, `v/html`) have since shipped, which is exactly
why **every verb goes through §1 before it is written** (design L9) rather than a
remembered "not yet" list.

The hazard is the whole `docs/design/**` tree, not a subdirectory of it. That
tree is the working design RECORD — `mkdocs.yml` excludes it from the published
site for exactly that reason — so design prose carrying unexported call shapes is
as likely in `codex-design.md` or `studio/` as in `decisions/`, and naming
subdirectories only invites the list to go stale as they are added and removed.

**`docs/core/freehand/` is NOT on this list.** It is the promoted, digest-pinned
guide — published documentation whose fenced blocks are hashed against the
shipped surface — so it is a valid input, second only to §1's roster. Do not
re-add it here as a hazard.

## 4. Tertiary inputs (shape the discipline, not quoted)

- **The four-pillar design rationale** — inherited from the skill family.
  Reproduced in `design.md` §2 so this folder is self-contained.
- **`skills/re-frame-migration/`** — the closest structural sibling (SKILL.md
  router + references + spec + the distribution triad). Voice, density,
  front-matter shape and the "cardinal rules" style all mirror it.
- **`skills/re-frame2/SKILL.md`** — the canonical authoring-pattern example for
  voice and load-bearing-rules density.

## 5. What the skill does NOT consume

- **The v1→v2 corpus** (`migration/from-re-frame-v1/README.md`, the `M-N`/`O-N`
  rules) — that is the *other* migration (`re-frame-migration`), the required
  first step. This skill assumes it is done.
- **`examples/**`** — worked examples are for authoring, not for this migration.
- **The full EP corpus** — the skill assumes the author knows re-frame2
  conceptually.

## 6. Update procedure

1. **A Freehand surface LANDS** → move its cases out of `catalog-reject.md` into
   the mechanical or judgment catalogue with the now-real target, and re-check
   `procedure.md` §Step 1. This has already happened for the React host boundary:
   the inward path (a created React element in a child position) and the outward
   bridge (`v/->react`) both shipped, so those holds moved to `catalog-reject.md`
   §No longer a hold and Step 1's closed-subtree discipline became a clean default
   rather than a constraint forced by a missing bridge.
2. **A rule's tier changes** (M↔D↔R) → move its treatment between the
   catalogues, and re-check `procedure.md`'s gate list.
3. **A new construct needs a rule** → add a before→after (M), a decision (D) or a
   hold (R) to the matching catalogue, and add an eval if it exercises a new class.
4. **The exported roster changes** → re-verify every emitted target against
   `spec/API.md`. This is the check that keeps the skill honest, and it is cheap.

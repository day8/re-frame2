# Skill Redirects

Canonical pointer table for AI skills. The `re-frame2` skill routes its
deep-dives through the labels below and stays free of hardcoded URLs —
update a URL once here. `re-frame2-implementor` is the deliberate
exception: it reads `spec/` from a verified checkout at a recorded pin
(its cardinal rule 1) and cites spec pages directly in its own leaves,
so those citations are maintained there, not here.

<!--
ANCHOR COUPLING — read before editing
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Leaf skill files cite the bullet labels below as routing anchors, e.g.
  `SKILL-REDIRECT.md` -> *EP - Frames (002)*
  `SKILL-REDIRECT.md` -> *Pattern - WebSocket*

If you rename a bullet label here, every leaf citing it goes stale silently.
Before editing labels, run the drift checker from the repo root:

    python3 scripts/check_skill_redirect_anchors.py

It walks `skills/**/*.md`, extracts every `SKILL-REDIRECT.md -> *Label*`
reference, and verifies the label matches a bullet below. Non-zero exit on
drift, with each broken reference printed. Run it again after editing.

When adding a new bullet, keep the format `- **Label** -> URL [tag] [tag]`;
that's the shape the checker (and human readers) expects.
-->


## Skill index — what to scan for

Find your skill, scan the audience section for lines tagged with it.

| Skill | Audience | Tag |
|---|---|---|
| `re-frame2` | Building CLJS apps on the reference impl | `[app]` |
| `re-frame2-setup` | Greenfield bootstrap on the reference impl | `[setup]` |
| `re-frame-migration` | Porting a v1 codebase to v2 | `[mig]` |
| `re-frame2-pair` | Live-runtime pair-programming | `[pair]` |
| `re-frame2-pair-retro` | Pair-session retrospective (no URL deps) | — |
| `re-frame2-implementor` | Building a new impl in another host language | `[impl]` |

> Three of the nine skills are intentionally absent from this table:
> `re-frame2-xray` cites its own spec tree (`tools/xray/spec/*`),
> `re-frame2-improver` routes deep-dives to `skills/re-frame2/patterns/`
> + `spec/`, and `reagent-migration` pins the pre-publication Fresco
> surface by checkout (`implementation/fresco/src/`, `docs/core/fresco/`).
> None consumes the URLs below, so none gets a row here.
> `re-frame2-implementor` keeps its row for the `[impl]` tags but
> consumes none of the URLs below either.

## Section 1 — Building with the reference implementation

Audience: `[app]` / `[setup]` / `[mig]` / `[pair]`. API + Guide + MIGRATION + Examples.

- **Definitive API reference** → https://day8.github.io/re-frame2/spec/API/ `[app]` `[setup]` `[impl]`
- **Migration from re-frame v1** → https://day8.github.io/re-frame2/migration/from-re-frame-v1/ `[app]` `[setup]` `[mig]`
- **Narrative guide (overview)** → https://day8.github.io/re-frame2/core/introduction/ `[setup]`
- **Story tutorial** → https://day8.github.io/re-frame2/story/ `[app]`
- **Examples directory (worked apps)** → https://github.com/day8/re-frame2/tree/main/examples `[app]` `[setup]` `[impl]`
- **VERSION (next release string)** → https://github.com/day8/re-frame2/blob/main/VERSION `[setup]` `[mig]`
- **CHANGELOG** → https://github.com/day8/re-frame2/blob/main/CHANGELOG.md `[setup]` `[mig]`
- **GitHub releases** → https://github.com/day8/re-frame2/releases `[setup]` `[mig]`

## Section 2 — Implementing the spec

Audience: `[app]` (deep-dive lookups); `[impl]` marks the rows a port author needs, though the implementor skill cites these pages directly and owns its own reading order (its cardinal rule 3). `API.md` is the contract.

### Spec corpus

- **EP — Vision (000)** → https://day8.github.io/re-frame2/spec/000-Vision/ `[impl]`
- **EP — Registration (001)** → https://day8.github.io/re-frame2/spec/001-Registration/ `[impl]`
- **EP — Frames (002)** → https://day8.github.io/re-frame2/spec/002-Frames/ `[app]` `[impl]`
- **EP — Frames (002): image-loaded make-frame** → https://day8.github.io/re-frame2/spec/002-Frames/#per-instance-frames--make-frame-the-ep-0023-object-constructor `[app]` `[impl]`
- **Conventions: the public rf/image source keys** → https://day8.github.io/re-frame2/spec/Conventions/#the-public-rfimage-source-keys `[app]` `[impl]`
- **EP — State machines (005)** → https://day8.github.io/re-frame2/spec/005-StateMachines/ `[app]` `[impl]`
- **EP — Reactive substrate (006)** → https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/ `[app]` `[pair]` `[impl]`
- **EP — Stories (007)** → https://day8.github.io/re-frame2/spec/007-Stories/ `[app]` `[impl]`
- **EP — Testing (008)** → https://day8.github.io/re-frame2/spec/008-Testing/ `[impl]`
- **EP — Instrumentation (009)** → https://day8.github.io/re-frame2/spec/009-Instrumentation/ `[app]` `[pair]` `[impl]`
- **EP — Schemas (010)** → https://day8.github.io/re-frame2/spec/010-Schemas/ `[app]` `[impl]`
- **EP — SSR (011)** → https://day8.github.io/re-frame2/spec/011-SSR/ `[app]` `[impl]`
- **EP — Routing (012)** → https://day8.github.io/re-frame2/spec/012-Routing/ `[app]` `[impl]`
- **EP — Flows (013)** → https://day8.github.io/re-frame2/spec/013-Flows/ `[app]` `[impl]`
- **EP — HTTP requests (014)** → https://day8.github.io/re-frame2/spec/014-HTTPRequests/ `[app]` `[impl]`
- **EP — Data classification (015)** → https://day8.github.io/re-frame2/spec/015-Data-Classification/ `[app]` `[pair]` `[impl]`
- **EP — Resources (016)** → https://day8.github.io/re-frame2/spec/016-Resources/ `[app]` `[impl]`

### Contract & cross-cutting normative docs

- **API contract (must expose)** → https://day8.github.io/re-frame2/spec/API/ `[app]` `[impl]`
- **Conventions** → https://day8.github.io/re-frame2/spec/Conventions/ `[app]` `[impl]`
- **Derivations and processes (the algebra)** → https://day8.github.io/re-frame2/spec/Derivations/ `[app]` `[impl]`
- **Spec schemas** → https://day8.github.io/re-frame2/spec/Spec-Schemas/ `[app]` `[impl]`
- **Principles** → https://day8.github.io/re-frame2/spec/Principles/ `[app]` `[impl]`
- **Cross-spec interactions** → https://day8.github.io/re-frame2/spec/Cross-Spec-Interactions/ `[impl]`
- **Tool-Pair contract (live inspection)** → https://day8.github.io/re-frame2/spec/Tool-Pair/ `[app]` `[pair]` `[impl]`

### Patterns (normative pattern specs)

- **Pattern — Async effect** → https://day8.github.io/re-frame2/spec/Pattern-AsyncEffect/ `[app]` `[impl]`
- **Pattern — Boot** → https://day8.github.io/re-frame2/spec/Pattern-Boot/ `[app]` `[impl]`
- **Pattern — Form action** → https://day8.github.io/re-frame2/spec/Pattern-FormAction/ `[app]` `[impl]`
- **Pattern — Forms** → https://day8.github.io/re-frame2/spec/Pattern-Forms/ `[app]` `[impl]`
- **Pattern — Long-running work** → https://day8.github.io/re-frame2/spec/Pattern-LongRunningWork/ `[app]` `[impl]`
- **Pattern — Nine states** → https://day8.github.io/re-frame2/spec/Pattern-NineStates/ `[app]` `[impl]`
- **Pattern — Remote data** → https://day8.github.io/re-frame2/spec/Pattern-RemoteData/ `[app]` `[impl]`
- **Pattern — Reusable components** → https://day8.github.io/re-frame2/spec/Pattern-ReusableComponents/ `[app]` `[impl]`
- **Pattern — SSR loaders** → https://day8.github.io/re-frame2/spec/Pattern-SSR-Loaders/ `[app]` `[impl]`
- **Pattern — Stale detection** → https://day8.github.io/re-frame2/spec/Pattern-StaleDetection/ `[app]` `[impl]`
- **Pattern — Stateful components** → https://day8.github.io/re-frame2/spec/Pattern-StatefulComponents/ `[app]` `[impl]`
- **Pattern — WebSocket** → https://day8.github.io/re-frame2/spec/Pattern-WebSocket/ `[app]` `[impl]`

### Construction & validation

- **Construction prompts (AI-shaped templates)** → https://day8.github.io/re-frame2/spec/Construction-Prompts/ `[app]` `[impl]`
- **CP-5 — Machine guide** → https://day8.github.io/re-frame2/spec/CP-5-MachineGuide/ `[impl]`
- **Conformance corpus** → https://day8.github.io/re-frame2/spec/conformance/ `[impl]`
- **Implementor checklist** → https://day8.github.io/re-frame2/spec/Implementor-Checklist/ `[impl]`

## Other

Cross-cutting material.

- **Runtime architecture** → https://day8.github.io/re-frame2/spec/Runtime-Architecture/ `[app]` `[impl]`
- **Ownership model** → https://day8.github.io/re-frame2/spec/Ownership/ `[impl]`
- **AI audit guidance** → https://day8.github.io/re-frame2/spec/AI-Audit/ `[impl]`
- **Source code** → https://github.com/day8/re-frame2 (all skills)

## Format

- Bullet list with `→` separator and `[skill-tag]` suffix.
- Audience-shaped sections; per-skill consumers tagged.
- Update when a URL changes, a skill is added, or a skill's consumption pattern shifts.

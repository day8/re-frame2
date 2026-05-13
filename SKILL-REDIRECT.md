# Skill Redirects

Canonical pointer table for AI skills. Skills stay free of hardcoded URLs;
this file owns them. Update once when a URL changes.

## Skill index — what to scan for

Find your skill, scan the audience section for lines tagged with it.

| Skill | Audience | Tag |
|---|---|---|
| `re-frame2` | Building CLJS apps on the reference impl | `[app]` |
| `re-frame2-setup` | Greenfield bootstrap on the reference impl | `[setup]` |
| `re-frame-migration` | Porting a v1 codebase to v2 | `[mig]` |
| `re-frame-pair2` | Live-runtime pair-programming | `[pair]` |
| `re-frame-pair-retro2` | Pair-session retrospective (no URL deps) | — |
| `re-frame2-implementor` | Building a new impl in another host language | `[impl]` |

## Section 1 — Building with the reference implementation

Audience: `[app]` / `[setup]` / `[mig]` / `[pair]`. API + Guide + MIGRATION + Examples.

- **Definitive API reference** → https://day8.github.io/re-frame2/spec/API/ `[app]` `[setup]` `[impl]`
- **Migration from re-frame v1** → https://day8.github.io/re-frame2/spec/MIGRATION/ `[app]` `[setup]` `[mig]`
- **Narrative guide (overview)** → https://day8.github.io/re-frame2/guide/README/ `[setup]`
- **Guide — Stories** → https://day8.github.io/re-frame2/guide/21-stories/ `[app]`
- **Examples directory (worked apps)** → https://github.com/day8/re-frame2/tree/main/examples/reagent `[app]` `[setup]` `[impl]`
- **VERSION (next release string)** → https://github.com/day8/re-frame2/blob/main/VERSION `[setup]` `[mig]`
- **CHANGELOG** → https://github.com/day8/re-frame2/blob/main/CHANGELOG.md `[setup]` `[mig]`
- **GitHub releases** → https://github.com/day8/re-frame2/releases `[setup]` `[mig]`

## Section 2 — Implementing the spec

Audience: `[impl]` (primary), `[app]` (deep-dive lookups). Read EPs in numeric order; `API.md` is the contract.

### Spec corpus (read in order)

- **EP — Vision (000)** → https://day8.github.io/re-frame2/spec/000-Vision/ `[impl]`
- **EP — Registration (001)** → https://day8.github.io/re-frame2/spec/001-Registration/ `[impl]`
- **EP — Frames (002)** → https://day8.github.io/re-frame2/spec/002-Frames/ `[app]` `[impl]`
- **EP — Views (004)** → https://day8.github.io/re-frame2/spec/004-Views/ `[impl]`
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

### Contract & cross-cutting normative docs

- **API contract (must expose)** → https://day8.github.io/re-frame2/spec/API/ `[app]` `[impl]`
- **Conventions** → https://day8.github.io/re-frame2/spec/Conventions/ `[app]` `[impl]`
- **Spec schemas** → https://day8.github.io/re-frame2/spec/Spec-Schemas/ `[app]` `[impl]`
- **Principles** → https://day8.github.io/re-frame2/spec/Principles/ `[app]` `[impl]`
- **Cross-spec interactions** → https://day8.github.io/re-frame2/spec/Cross-Spec-Interactions/ `[impl]`
- **Tool-Pair contract (live inspection)** → https://day8.github.io/re-frame2/spec/Tool-Pair/ `[app]` `[pair]` `[impl]`

### Patterns (normative pattern specs)

- **Pattern — Async effect** → https://day8.github.io/re-frame2/spec/Pattern-AsyncEffect/ `[app]` `[impl]`
- **Pattern — Boot** → https://day8.github.io/re-frame2/spec/Pattern-Boot/ `[app]` `[impl]`
- **Pattern — Forms** → https://day8.github.io/re-frame2/spec/Pattern-Forms/ `[app]` `[impl]`
- **Pattern — Long-running work** → https://day8.github.io/re-frame2/spec/Pattern-LongRunningWork/ `[app]` `[impl]`
- **Pattern — Nine states** → https://day8.github.io/re-frame2/spec/Pattern-NineStates/ `[app]` `[impl]`
- **Pattern — Remote data** → https://day8.github.io/re-frame2/spec/Pattern-RemoteData/ `[app]` `[impl]`
- **Pattern — Stale detection** → https://day8.github.io/re-frame2/spec/Pattern-StaleDetection/ `[app]` `[impl]`
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

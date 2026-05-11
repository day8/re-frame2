# Skill Redirects

This file is a canonical pointer table for AI skills working in this repo.
Skills like `re-frame2`, `re-frame2-setup`, and `re-frame-pair2` redirect AI
agents here when the agent needs deep-dive content beyond the skill's
recipes: the full API table, EP design rationale, the migration guide, the
construction-prompt templates, etc.

Skills stay free of hardcoded URLs; this file owns them. If a URL changes,
update it here once and every skill keeps working.

## Pointers

- **Definitive API reference** → https://day8.github.io/re-frame2/spec/API/
- **Spec corpus (overview & index)** → https://day8.github.io/re-frame2/spec/
- **EP design rationale (000 — Vision)** → https://day8.github.io/re-frame2/spec/000-Vision/
- **EP — Registration (001)** → https://day8.github.io/re-frame2/spec/001-Registration/
- **EP — Frames (002)** → https://day8.github.io/re-frame2/spec/002-Frames/
- **EP — Views (004)** → https://day8.github.io/re-frame2/spec/004-Views/
- **EP — State machines (005)** → https://day8.github.io/re-frame2/spec/005-StateMachines/
- **EP — Reactive substrate (006)** → https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/
- **EP — Stories (007)** → https://day8.github.io/re-frame2/spec/007-Stories/
- **EP — Testing (008)** → https://day8.github.io/re-frame2/spec/008-Testing/
- **EP — Instrumentation (009)** → https://day8.github.io/re-frame2/spec/009-Instrumentation/
- **EP — Schemas (010)** → https://day8.github.io/re-frame2/spec/010-Schemas/
- **EP — SSR (011)** → https://day8.github.io/re-frame2/spec/011-SSR/
- **EP — Routing (012)** → https://day8.github.io/re-frame2/spec/012-Routing/
- **EP — Flows (013)** → https://day8.github.io/re-frame2/spec/013-Flows/
- **EP — HTTP requests (014)** → https://day8.github.io/re-frame2/spec/014-HTTPRequests/
- **Migration from re-frame v1** → https://day8.github.io/re-frame2/spec/MIGRATION/
- **Principles** → https://day8.github.io/re-frame2/spec/Principles/
- **Conventions** → https://day8.github.io/re-frame2/spec/Conventions/
- **Construction prompts (AI-shaped templates)** → https://day8.github.io/re-frame2/spec/Construction-Prompts/
- **CP-5 — Machine guide** → https://day8.github.io/re-frame2/spec/CP-5-MachineGuide/
- **AI audit guidance** → https://day8.github.io/re-frame2/spec/AI-Audit/
- **Implementor checklist** → https://day8.github.io/re-frame2/spec/Implementor-Checklist/
- **Runtime architecture** → https://day8.github.io/re-frame2/spec/Runtime-Architecture/
- **Cross-spec interactions** → https://day8.github.io/re-frame2/spec/Cross-Spec-Interactions/
- **Ownership model** → https://day8.github.io/re-frame2/spec/Ownership/
- **Spec schemas** → https://day8.github.io/re-frame2/spec/Spec-Schemas/
- **Conformance corpus** → https://day8.github.io/re-frame2/spec/conformance/
- **Tool-Pair contract (live inspection)** → https://day8.github.io/re-frame2/spec/Tool-Pair/
- **Pattern — Async effect** → https://day8.github.io/re-frame2/spec/Pattern-AsyncEffect/
- **Pattern — Boot** → https://day8.github.io/re-frame2/spec/Pattern-Boot/
- **Pattern — Forms** → https://day8.github.io/re-frame2/spec/Pattern-Forms/
- **Pattern — Long-running work** → https://day8.github.io/re-frame2/spec/Pattern-LongRunningWork/
- **Pattern — Nine states** → https://day8.github.io/re-frame2/spec/Pattern-NineStates/
- **Pattern — Remote data** → https://day8.github.io/re-frame2/spec/Pattern-RemoteData/
- **Pattern — Stale detection** → https://day8.github.io/re-frame2/spec/Pattern-StaleDetection/
- **Pattern — WebSocket** → https://day8.github.io/re-frame2/spec/Pattern-WebSocket/
- **Narrative guide (overview)** → https://day8.github.io/re-frame2/guide/README/
- **Guide — Your first app** → https://day8.github.io/re-frame2/guide/02-your-first-app/
- **Guide — State machines** → https://day8.github.io/re-frame2/guide/05-state-machines/
- **Guide — Stories** → https://day8.github.io/re-frame2/guide/14-stories/
- **Guide — From re-frame v1** → https://day8.github.io/re-frame2/guide/08-from-re-frame-v1/
- **Guide — Testing** → https://day8.github.io/re-frame2/guide/10-testing/
- **Guide — Tooling (devtools and pair tools)** → https://day8.github.io/re-frame2/guide/11-devtools-and-pair-tools/
- **Examples directory (worked apps)** → https://github.com/day8/re-frame2/tree/main/examples/reagent
- **re-frame-pair2 skill (live inspection)** → https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair2
- **Source code** → https://github.com/day8/re-frame2

## Format

- Flat bullet list with `→` separator
- No nesting
- Easy for an AI to scan; easy for a human to read
- Update when a URL changes or a new skill is added

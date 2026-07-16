# 11 — Adoption workstreams: everything beyond the library

**Status:** final · 2026-07-11. 08 §2 budgets "migrate the repo" as a Stage-6
workstream; this doc decomposes the full adoption surface so it can become stage epics.
*[2026-07-16: the **re-com native port** is deliberately NOT a W-row here — it is a
separate directed epic (`rf2-6ajm6z`, Wave 0 gated on
`ai/decisions/re-com-readiness.product-rulings.md`) so component migration never
blocks a substrate stage; its substrate prerequisites ride S3 per
`drafts/component-library-readiness.md`.]*
Each row: what, when it starts, what gates it. Rule of thumb throughout: nothing here
starts before the thing it documents/teaches/tests exists — but *none of it is optional*,
and several items (ui.test, the migrator, skills) are on the critical path, not trailing
work.

| # | Workstream | Scope highlights | Stage |
|---|---|---|---|
| W1 | **Migrator tool** | The doc-10 mechanical rewriter (Form-1→defview + call-site map-args, deref-drop, dispatch-lifting with body-proof, key-meta→prop, `[:>`→direct head), D-tier flagging. Repo migration is its first consumer; ships with the skill. | Stage 6 (build + first repo consumer) |
| W2 | **Migration skill** | Extend `skills/re-frame-migration` with the Reagent→`re-frame.ui` path — generic to any consumer app (mechanism first; repo specifics as examples); teaches the D/R tiers the migrator flags. | Stage 5–6 |
| W3 | **docs/guide rewrite** | `docs/core/*` view surfaces (views, frames, introduction counter, forms, secrets how-to), delete the primary `use-uix-helix-or-slim.md` teaching page, retain one minimum compatibility reference for the frozen Reagent/UIx boot choices, glossary, `docs/api/*`, **playground rebuild + smoke**. Synthesis `guide/` is the seed. `mkdocs --strict` gates. | Stage 5–6 |
| W4 | **Examples** | `core`/`capabilities`/`patterns`/`real-apps` migrate; `substrates/` **deleted** (its purpose was the trio). One RealWorld-resources vertical page at Stage 3; full app = Stage-6 gate; realworld-http follows as ordinary work. | 3 (slice), 5–6 |
| W5 | **Spec tree** | R-1 interim broadening now; full 004 rewrite atomic with Stage 1; synthesis→`spec/` promotion; 002/006/009/011/Conventions/Ownership ripples; API.md + manifest + facade classification per export; Spec-Schemas/009 catalogue rows for all new vocabulary (one-catalogue rule). All hot-zone: sequenced, never parallel. | 0–1 draft; merges ride slices |
| W6 | **Other skills** | `re-frame2` (authoring — view sections), `re-frame2-pair` (hot-swap, `ui.tool`), `re-frame2-xray` (causes/interaction surface), implementor/improver. Skill↔MCP drift gates enforce; budget rather than discover. | Stage 5–6 |
| W7a | **Tool evidence consumption** | Xray delta (04 §5 "Tool integration", the Xray enrich-existing-surfaces-first bullet: causes into existing panels, occurrence identity, loss accounting (04 §2), manifest reverse-indexes — and *deletion* of the old cloneElement/post-render attribution machinery); Story observation-target overrides + `flush-presence!` + `ui.test`-tree variant vocabulary; Pair-MCP hot-swap (= the HMR path over nREPL) + `re-frame.ui.tool` projections; mcp-conformance descriptor updates. Xray PRs update `tools/xray/spec/*` in-PR (standing rule). | Stage 3 (debugging-as-first-consumer) |
| W7b | **Tools' own UIs migrate** | The tools are apps: Xray's panels, Story's shell/canvas, machines-viz, and testbed-support's deck runner are adapter-rendered today and move to `re-frame.ui`; Story's variant mounting moves to the new roots/provider (interacts with commit-owned ENSURE — Story's shipped single-run-owner rework is an input: one run-attempt owner spans the React render boundary — prepare completes loaders/setup before the first render, the same attempt resumes its script exactly once after commit, and superseded attempts settle explicitly rather than double-firing plays or reading a successor's frame (PR #5607; rf2-j538f7.34)); all tool/Story/Xray **testbeds** migrate. **This IS the dogfood proof** — Story+Xray green is a kill-gate condition, and Xray's dense tool-grade UI is the hardest honest test of the library. Story's assertion/result core is substrate-independent and survives. | Stage 5–6 |
| W8 | **Template** | Three substrate variants collapse to one `re-frame.ui` scaffold; feeds the deiym split gates (every emitted coordinate must resolve). | Stage 6 |
| W9 | **CI/test infra** | Hot-zone `shadow-cljs.edn`/`package.json` builds; TESTING.md matrix rewrite; the end state has **three named causal suites**: the new-UI conformance suite + one smoke, the frozen-Reagent compatibility suite + one smoke, and the frozen-UIx compatibility suite + one smoke. The old ×3 shared-adapter parameterisation collapses into those pinned owners; only Helix/slim arms retire. UIx keeps its classpath probe, compatibility arm, changed-surface classifier routing, bundle-isolation coverage, and release test/deploy leaf. Bundle-isolation/elision/perf gates → G-roster; **`ui.test` built as a first-class artifact (S1 critical path — Tier-1 testing for every other workstream rides it)**. | ui.test Stage 1 (S1 critical path); rest 5–6 |
| W10 | **SSR hosts** | ssr/ssr-ring consume the JVM emitter; root-manifest hydration contract into Spec 011 + ring handlers; multi-root failure-isolation fixtures. | Stage 5 |
| W11 | **Benchmarks** | js-framework-benchmark fixtures; G-1/G-2/G-10 baselines run once against the legacy trio **before** the S7 freeze/removal wave; results + fixtures + git tag survive. Reagent/UIx correctness support does not imply an ongoing performance-parity gate. | Stage 6, pre-wave |
| W12 | **Repo meta-docs** | Root README project map; `CLAUDE.md` (build commands, hot-zone list, architecture overview); `docs/release-process.md` (new coordinate; template collapse). | Stage 6–7 |
| W13 | **Freeze + deletion wave** | Helix + reagent-slim deleted; **stock Reagent + `reg-view` and UIx freeze as compatibility adapters**. Each keeps a pinned compatibility suite + one smoke, distinct from the new-UI conformance suite; UIx additionally keeps `day8/re-frame2-uix`, its public exports, classpath probe, classifier arm, release test/deploy leaf, and minimum compatibility reference. Reagent's live view contract moves to the Reagent-specific 004A appendix; UIx remains primarily in Spec 006/API/Conventions/Ownership. No new capabilities or parity promise; no primary UIx examples/templates/teaching. Slim rationale archived; git tag cut; the non-historical import grep covers Helix/reagent-slim only; strictly behind the soak gates. The process still installs one of three adapters at boot—never per frame or within one frame. | Stage 7 |
| W14 | **Conformance corpus** | `spec/conformance/` gains dual-emitter parity + root-hydration fixtures; existing fixtures referencing view shapes swept. | Stage 4–5 |
| W15 | **Program management** | Stage epics as beads; the demand-bar audit table (**before Stage 1**); hot-zone sequencing plan for the W5 spec waves; per-stage go/no-go rides the gates. | now |

**Sequencing notes.** W9's `ui.test` and W5's interim-004 amendment are the two earliest
non-spike items (everything tests through the first; everything is conformant through the
second). W7's evidence side (schema emission) is Stage 3 — Xray consumes
before alpha. W1–W4 deliberately trail the API surface stabilizing at Stage 3 (the
vertical-page rider exists precisely to shake ergonomics before the docs/skills fan-out).
W13 is last and gated hardest.

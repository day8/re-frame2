# re-frame2 — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — `implementation/**`

Path: `implementation/core/src/re_frame/**`, `implementation/reagent/src/re_frame/**`, plus per-feature artefacts (`implementation/machines/`, `implementation/routing/`, `implementation/flows/`, `implementation/http/`, `implementation/ssr/`, `implementation/schemas/`, `implementation/epoch/`).

**This is the source of truth.** Every code snippet in `references/` and `patterns/` is verified against the implementation — the function signatures, the macro shapes, the keyword option sets, the late-bind hook contracts. When the spec disagrees with the implementation, the implementation wins and a `bd` bead is filed against the spec.

Specific files the leaves lean on:

- `implementation/core/src/re_frame/core.cljc` — the public single-import API surface (`reg-event-*`, `reg-sub`, `dispatch`, `subscribe`, `with-frame`, `dispatcher`, `subscriber`, `bound-fn`, etc.).
- `implementation/core/src/re_frame/frame.cljc` — `reg-frame`, `make-frame`, `destroy-frame!`, frame metadata grammar, `:fx-overrides`.
- `implementation/core/src/re_frame/fx.cljc` — `do-fx`, `:fx-overrides` resolution (id-redirect + fn-value branches), per-call vs per-frame merge.
- `implementation/core/src/re_frame/events.cljc` + `router.cljc` — event-state cycle, effect-shape policing.
- `implementation/core/src/re_frame/subs.cljc` — sub graph, layered subs, dynamic args.
- `implementation/core/src/re_frame/test_support.cljc` — `reset-runtime-fixture-factory`, `dispatch-sequence`, `assert-state`, `compute-sub`, `subscribe-once`.
- `implementation/core/src/re_frame/substrate/plain_atom.cljc` — JVM-side adapter.
- `implementation/reagent/src/re_frame/adapter/reagent.cljs` — `frame-provider`, plain-Reagent-fn warning.
- `implementation/machines/src/re_frame/machines.cljc` — `reg-machine`, `:spawn`, parallel regions, tags.
- `implementation/http/src/re_frame/http.cljc` — `:rf.http/managed`, failure categories, request stubs.

## 2. Secondary input — `examples/reagent/**`

The worked example for each pattern. Used both as a source of truth for canonical shape and as the destination the leaves point at ("read this for the full worked example").

- `examples/reagent/counter/` — the smallest end-to-end app (event-state cycle).
- `examples/reagent/login/` — Forms pattern.
- `examples/reagent/boot/` — Boot pattern.
- `examples/reagent/nine_states/` — NineStates pattern.
- `examples/reagent/managed_http_counter/` — ManagedHTTP pattern.
- `examples/reagent/long_running_work/` (pending) — LongRunningWork pattern.
- `examples/reagent/websocket/` (pending) — WebSocket pattern.

When an example doesn't yet exist, the relevant pattern leaf inlines a mini-declaration and flags "example app pending".

## 3. Tertiary input — `spec/**`

Used for *why*, not *what*. The leaves cite EPs by name for design rationale (frames, state machines, schemas, instrumentation) but do not quote API surface from the spec — that comes from `implementation/**`.

- `spec/000-Vision.md` — the AI-first design principles; SKILL.md's cardinal-rules framing.
- `spec/001-Registration.md` — registry kind taxonomy; reserved-namespace rule (cardinal rule L6 / L7).
- `spec/002-Frames.md` — `:fx-overrides` value shapes, frame-resolution chain, preset expansion.
- `spec/005-StateMachines.md` — `:spawn`, parallel regions, tags, cancellation cascade.
- `spec/008-Testing.md` — the `reset-runtime-fixture-factory` contract, test-frame conventions.
- `spec/009-Instrumentation.md` — `:rf/op` vocabulary, `:rf.error/*` shape, error-handler policy.
- `spec/010-Schemas.md` — `reg-app-schema`, boundary validation, Malli integration.
- `spec/014-HTTPRequests.md` — `:rf.http/managed`, failure categories, async cascade.
- `spec/Conventions.md` — naming, keyword namespaces, source-coord conventions.
- `spec/Pattern-*.md` — one per canonical pattern; the pattern leaves are operationalisations of these.

## 4. Authoring-discipline inputs

These shape the skill's voice and structure but aren't quoted directly.

- **`ai/findings/re-frame2-skill-design-v2.md`** — the design rationale captured during the v2 redesign. Sources Q14 (no verification module), the four pillars, the cut-test, the routing model.
- **`skills/re-frame-migration/spec/**`** + **`skills/re-frame2-implementor/spec/**`** — the existing `spec/` triad pattern (design / inputs / authoring-prompt). This skill's spec/ mirrors that shape.
- **`SKILL-REDIRECT.md`** (repo root) — the canonical pointer table the leaves redirect to for deep-dive content.
- Anthropic skills guidance — `name` ≤ 64 chars, lowercase + hyphens; `description` "pushy"; SKILL.md under ~500 lines; leaves one level deep; avoid time-sensitive content (deferred to lookup leaves like `references/deps-versions.md` in the sibling setup skill).

## 5. What the skill does NOT consume

- **`docs/guide/**`** — the narrative human guide. The skill is for AI agents authoring code; the guide is for humans learning the framework. Cross-references run through `SKILL-REDIRECT.md`, not into the guide directly.
- **`docs/EPs/**`** — EP rationale documents. The leaves cite EPs by name but don't quote them.
- **`tests/**`** — re-frame2's own test suite. The test-authoring leaf points at `re-frame.test-support` (the public surface), not at how that surface is tested internally.
- **`tools/**`** — re-frame2's tooling (bd, claudia, etc.). Out of scope for application-authoring guidance.

## 6. Update procedure

When implementation changes land:

1. **New `reg-*` surface added** → add a row to the relevant fundamentals leaf or add a new leaf if a new registry kind is introduced.
2. **Existing `reg-*` option set changed** → grep `references/` and `patterns/` for the surface, update every occurrence; file a `bd` bead if the spec lags.
3. **New canonical pattern added** → write a new `patterns/<name>.md`, add an entry to SKILL.md's pattern table and to `decision-trees/pick-a-pattern.md`. Add a row to `examples-map.md` if a worked example exists.
4. **Example app moved or renamed** → update `examples-map.md` and every pattern leaf that points at it.
5. **Spec adds a new EP** → update `SKILL-REDIRECT.md` (the pointer table); add a leaf only if there's a corresponding `reg-*` surface AI agents would author against.
6. **Reserved-namespace addition** (new `:rf.*/` prefix) → update cardinal rule L6 in SKILL.md if the namespace is one application authors would plausibly try to use.

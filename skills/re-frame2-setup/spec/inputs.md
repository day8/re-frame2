# re-frame2-setup — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-setup` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary inputs — the canonical greenfield artefacts

The skill teaches a four-file scaffolding (`deps.edn`, `package.json`, `shadow-cljs.edn`, `core.cljs`). Each file's shape is derived from:

- **`day8/re-frame2`'s release artefacts** — the ten Maven coords (`day8/re-frame2`, `day8/re-frame2-reagent`, `day8/re-frame2-schemas`, `day8/re-frame2-machines`, `day8/re-frame2-routing`, `day8/re-frame2-flows`, `day8/re-frame2-http`, `day8/re-frame2-ssr`, `day8/re-frame2-epoch`, plus the test-support artefact). All ship in lockstep at a single VERSION; the skill reads that VERSION at discovery time rather than hardcoding it.
- **`examples/reagent/counter/`** in the re-frame2 repo — the canonical first-counter shape. `reference/first-counter.md` is a trimmed version of this example.
- **`examples/reagent/counter/shadow-cljs.edn`** — the canonical `shadow-cljs.edn` shape for a single-page browser app.
- **`examples/reagent/counter/index.html`** — the canonical `index.html` (`<div id="app">`, `<script src="js/main.js">`).
- **`day8/re-frame2-reagent`'s adapter export** — `re-frame.adapter.reagent/adapter` (a var holding the adapter spec map). The entry namespace's `(rf/init! reagent-adapter/adapter)` call comes from this surface.

## 2. Secondary input — `implementation/core/src/re_frame/core.cljc`

For the `rf/init!` contract: when it's called, what it expects, why it must run **before any `dispatch` or render**. The entry-namespace leaf walks this contract.

For `re-frame.adapter.reagent`: how the adapter spec map is consumed; why `defonce` matters for the React root.

## 3. Tertiary inputs — `spec/`

Used for *why*, not *what*. Cited by name in design rationale; not quoted in user-facing leaves.

- **`spec/000-Vision.md`** — the AI-first design principles; backs the skill's discipline of minimal scaffolding.
- **`spec/002-Frames.md`** — `:rf/default` is implicit on `rf/init!`; the setup skill doesn't need to teach frames but mentions the default exists.
- **`spec/Pattern-Boot.md`** — the canonical boot pattern; relevant once the author moves past first-counter (the SKILL.md routing table points there).

## 4. Authoring-discipline inputs

These shape the skill's voice and structure but aren't quoted directly.

- **`skills/re-frame2/spec/design.md`** — the parent skill's locked design. This skill inherits the four pillars, the Q14 lock, the cardinal-rules format, the single-import contract (L9 here, L8 there).
- **`skills/re-frame-migration/SKILL.md`** + **`skills/re-frame-migration/spec/`** — the closest structural sibling that already has a `spec/` triad. Voice / shape match this.
- **`SKILL-REDIRECT.md`** (repo root) — the canonical pointer table for deep-dive content; the skill's routing-on-exit table cross-references it.
- Anthropic skills guidance — `name` ≤ 64 chars, lowercase + hyphens; `description` "pushy" with explicit "use this skill whenever..." framing; SKILL.md under 500 lines; leaves one level deep; avoid time-sensitive content (deferred to `reference/deps-versions.md` lookup rather than hardcoded VERSIONs).

## 5. What the skill does NOT consume

- **`spec/001-Registration.md` through `spec/014-HTTPRequests.md` (most of the EP corpus)** — the skill doesn't teach the API. Once the counter mounts, the author switches to the `re-frame2` skill, which teaches it.
- **`docs/guide/**`** — the narrative human guide. Cross-references run through `SKILL-REDIRECT.md`, not into the guide directly.
- **`implementation/machines/**`** / **`implementation/routing/**`** / etc. — per-feature artefacts. The skill defers them per L3 (pay-as-you-go).
- **`examples/reagent/{login,boot,nine_states,managed_http_counter,...}/`** — application-pattern examples. The setup skill points at `counter/` only; the others belong to the main `re-frame2` skill.
- **`tools/**`** — the skill doesn't reach for repo tooling.

## 6. Update procedure

When the artefact set or the greenfield contract changes:

1. **A new artefact is split out** (e.g. a future `day8/re-frame2-stories`) → add a row to `reference/deps-versions.md`'s pay-as-you-go table; mention in SKILL.md if it's commonly needed on day one.
2. **An existing artefact is renamed or merged** → grep `reference/` for the old name and update; verify the routing table at the end of SKILL.md still resolves correctly.
3. **`re-frame.adapter.reagent`'s adapter contract changes** (e.g. new keys in the adapter spec map) → update `reference/entry-namespace.md`'s canonical shape; verify `reference/first-counter.md` still compiles.
4. **`shadow-cljs.edn` greenfield shape changes** (rare — `:target :browser` is very stable) → update `reference/shadow-cljs.md`.
5. **`rf/init!` signature changes** → update SKILL.md's Step 5 framing and `reference/entry-namespace.md`.
6. **A new common greenfield failure mode appears** → add a row to SKILL.md's Troubleshooting section (move to a dedicated leaf if it grows past ~30 lines per OQ3).
7. **The `examples/reagent/counter/` shape changes** → re-derive `reference/first-counter.md` from the example.

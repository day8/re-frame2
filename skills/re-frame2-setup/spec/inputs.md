# re-frame2-setup — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-setup` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary inputs — the canonical greenfield artefacts

The skill writes the generator template's twelve-file scaffold (`deps.edn`, `package.json`, `shadow-cljs.edn`, `.gitignore`, `resources/public/index.html`, `resources/public/css/app.css`, `src/acme/my_app/{core,events,subs,views}.cljs`, `test/acme/my_app/events_test.cljs`, `README.md`) — rendered into `references/first-counter.md` from the template by `tests/first_counter_derivation.clj`, so the primary input is the template itself. The shapes it carries trace to:

- **`day8/re-frame2`'s release artefacts** — the ten publishable Maven coords (core + 7 per-feature + 2 per-adapter per `spec/Conventions.md` §Packaging conventions: `day8/re-frame2`, `day8/re-frame2-reagent`, `day8/re-frame2-uix`, `day8/re-frame2-schemas`, `day8/re-frame2-machines`, `day8/re-frame2-routing`, `day8/re-frame2-flows`, `day8/re-frame2-http`, `day8/re-frame2-ssr`, `day8/re-frame2-epoch`). The `day8/re-frame2-xray` devtools panel is a tool on its own tag line and is not in the scaffold on either substrate (the template's rf2-zq34m collapse stripped it; it attaches later by its own recipe). All ten ship in lockstep at a single VERSION; the default pin is the template's `:rf2-version` literal in `hooks.clj`, carried into the leaf by derivation.
- **`examples/core/counter/core.cljs`** in the re-frame2 repo — the canonical first-counter logic the template's `_shared/events.cljs` + `_shared/subs.cljs` + `_reagent/views.cljs` mirror. (The `examples/core/counter/` directory ships `core.cljs` + `index.html` + `README.md` only — there is **no** per-example `shadow-cljs.edn`; the examples share a single repo-level build.)
- **`tools/template/resources/day8/re_frame2_template/_shared/shadow-cljs.edn`** — the greenfield two-build (`:app` + `:test`) config the leaf carries and `references/shadow-cljs.md` explains; not the repo's own `implementation/shadow-cljs.edn`, which is the shared examples build.
- **`tools/template/resources/day8/re_frame2_template/root/resources/public/index.html`** (+ `css/app.css`) — the canonical page: `<main id="app">` as the one mount point, an external stylesheet, `<script src="/js/main.js">` at the bottom of `<body>` (per `:asset-path "/js"`); no Xray host column and no CSP meta tag since rf2-zq34m.
- **`day8/re-frame2-reagent`'s adapter export** — `re-frame.adapter.reagent/adapter` (a var holding the adapter spec map). The entry namespace's `(rf/init! rf.adapter.reagent/adapter)` call comes from this surface.

## 2. Secondary input — `implementation/core/src/re_frame/core.cljc`

For the `rf/init!` contract: when it's called, what it expects, why it must run **before any `dispatch` or render**. The entry-namespace leaf walks this contract.

For `re-frame.adapter.reagent`: how the adapter spec map is consumed; why `defonce` matters for the React root.

## 3. Tertiary inputs — `spec/`

Used for *why*, not *what*. Cited by name in design rationale; not quoted in user-facing leaves.

- **`spec/000-Vision.md`** — the AI-first design principles; backs the skill's discipline of minimal scaffolding.
- **`spec/002-Frames.md`** — frame identity is carried, not found (EP-0002): `init!` creates no frame, so the setup skill teaches the view-owned boot — the root `frame-root {:id … :initial-events …}` ENSURE element creates the one app frame at mount and provides it to the tree (`frame-provider` stays the SCOPE-only sibling for programmatically created frames). There is no implicit `:rf/default`.
- **`spec/Pattern-Boot.md`** — the canonical boot pattern; relevant once the author moves past first-counter (SKILL.md's exit hand-off routes past-setup work to the `re-frame2` skill, which owns boot patterns).

## 4. Authoring-discipline inputs

These shape the skill's voice and structure but aren't quoted directly.

- **`skills/re-frame2/spec/design.md`** — the parent skill's locked design. This skill inherits the four pillars, the Q14 lock, the cardinal-rules format, the single-import contract (L9 here, L8 there).
- **`skills/re-frame-migration/SKILL.md`** + **`skills/re-frame-migration/spec/`** — the closest structural sibling that already has a `spec/` triad. Voice / shape match this.
- **`SKILL-REDIRECT.md`** (repo root) — the canonical pointer table for deep-dive content; SKILL.md's exit hand-off cross-references it.
- Anthropic skills guidance — `name` ≤ 64 chars, lowercase + hyphens; `description` "pushy" with explicit "use this skill whenever..." framing; SKILL.md under 500 lines; leaves one level deep; avoid time-sensitive content (deferred to `references/deps-versions.md` lookup rather than hardcoded VERSIONs).

## 5. What the skill does NOT consume

- **`spec/001-Registration.md` through `spec/014-HTTPRequests.md` (most of the EP corpus)** — the skill doesn't teach the API. Once the counter mounts, the author switches to the `re-frame2` skill, which teaches it.
- **`docs/core/**`** — the narrative human guide. Cross-references run through `SKILL-REDIRECT.md`, not into the guide directly.
- **`implementation/machines/**`** / **`implementation/routing/**`** / etc. — per-feature artefacts. The skill defers them per L3 (pay-as-you-go).
- **`examples/core/{login,managed_http_counter,...}/`, `examples/patterns/{boot,nine_states,...}/`** — application-pattern examples. The setup skill points at `examples/core/counter/` only; the others belong to the main `re-frame2` skill.
- **`tools/**`** — the skill doesn't reach for repo tooling.

## 6. Update procedure

When the artefact set or the greenfield contract changes:

1. **A new artefact is split out** (e.g. a future `day8/re-frame2-stories`) → add a row to `references/deps-versions.md`'s pay-as-you-go table; mention in SKILL.md if it's commonly needed on day one.
2. **An existing artefact is renamed or merged** → grep `references/` for the old name and update; verify SKILL.md's Reference-files list still resolves correctly.
3. **`re-frame.adapter.reagent`'s adapter contract changes** (e.g. new keys in the adapter spec map) → update `references/entry-namespace.md`'s canonical shape; verify `references/first-counter.md` still compiles.
4. **`shadow-cljs.edn` greenfield shape changes** (rare — `:target :browser` is very stable) → update `references/shadow-cljs.md`.
5. **`rf/init!` signature changes** → update SKILL.md's Step 5 framing and `references/entry-namespace.md`.
6. **A new common greenfield failure mode appears** → add a row to SKILL.md's Troubleshooting section (move to a dedicated leaf if it grows past ~30 lines per OQ3).
7. **The `examples/core/counter/` shape changes** → re-derive `references/first-counter.md` from the example.
8. **The deps-new template's emission changes** (any file body, a pin in `hooks.clj`, a new substrate) → run `bb tests/first_counter_derivation.clj` from `skills/re-frame2-setup/` to re-render the two generated regions (`references/first-counter.md`, `references/entry-namespace.md` §UIx greenfield); both drift locks (`setup_drift_test.clj`, `emitted_test_run_test.clj`) stay red until you do. Then re-read the hand-written prose around them — SKILL.md's day-one rule, `shadow-cljs.md`'s key-by-key explanation, `entry-namespace.md`'s lifecycle — for anything the change invalidates. The template splits across `_reagent/` + `_uix/` (`deps.edn`, `core.cljs`, `views.cljs` — the per-substrate trio), `_shared/` (`shadow-cljs.edn`, `package.json`, `gitignore`, `events.cljs`, `subs.cljs`, `events_test.cljs`) and `root/` (`README.md`, `resources/public/index.html`, `resources/public/css/app.css`).

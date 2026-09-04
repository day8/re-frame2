# re-frame2-setup — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-setup` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2-setup` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help an author **bootstrap a fresh re-frame2 ClojureScript project**. The author starts with nothing — or close to it — and ends with a working browser app that compiles under `shadow-cljs watch` and mounts a counter. From there, the author switches to the main `re-frame2` skill for application-code authoring.

The success criterion: `npx shadow-cljs watch app` compiles cleanly, the browser shows a counter, clicking `+1` increments it. The skill stops at that point — anything beyond setup is another skill's job.

## 2. Pillars (locked, derived from `re-frame2`'s four pillars)

The same four pillars as the `re-frame2` skill, scoped to greenfield bootstrap:

1. **Correctness — recipes over explanations.** The skill writes the exact files, the exact `shadow-cljs.edn`, the exact entry-namespace contract, and verifies them: it runs `npm install` and a terminating `npx shadow-cljs compile app` itself, starts the watch, and reports the URL (L6). **Q14 lock still applies: NO verification module** — no `references/verify.md` leaf; the browser-mount confirmation stays the author's, and compile success is never presented as a mount claim.
2. **Idiomaticness — the default IS the canonical artefact.** The default scaffold is the generator template's own emission (L13), not a hand-written approximation of it; the template is itself grounded against `examples/core/counter/` and the canonical artefacts.
3. **Context economy — `SKILL.md` is a router; the default route reads one leaf; four one-level-deep leaves carry the depth.** SKILL.md walks the six-step canonical path; `first-counter.md` is the whole default; the other three leaves are read only when a step needs depth (an overridden pin, the build or boot explained, the UIx swap).
4. **Assume training knowledge.** The author knows `deps.edn`, `npm`, `shadow-cljs`, what a Reagent component is. The skill teaches only the **re-frame2-specific wiring** — which artefacts to add, the `rf/init!` contract, the order of operations between adapter install and React mount.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve them unless explicitly unlocked by Mike.

### L1 — Pins are derived, never hand-typed

The re-frame2 VERSION changes. The skill's default `deps.edn` / `package.json` carry the generator template's reviewed pins as literals — but only because `references/first-counter.md` is **rendered from the template** by `tests/first_counter_derivation.clj` and drift-locked (L13); no leaf hand-types a `day8/re-frame2*` version, and prose never restates the numbers as authority ("read the leaf, not this sentence"). The override paths in `references/deps-versions.md` keep `<VERSION>` / `<SHA>` as the author-supplied slots they are; the default route writes no placeholder. (The original L1 forbade any hardcoded VERSION in a written file, which forced a discovery step onto the default path; rf2-rc0yh slice B replaced discovery with derivation.)

### L2 — All ten artefacts ship at the same VERSION

The author picks the VERSION once (or takes the default); every `day8/re-frame2-*` dep gets that same version. Mixing versions across artefacts is unsupported. This rule lands in both SKILL.md and `references/deps-versions.md`.

### L3 — Day-one shape matches the generator template; everything else is pay-as-you-go

The day-one deps match the deps-new template after its rf2-zq34m collapse: core (`day8/re-frame2`) + the substrate adapter (`day8/re-frame2-reagent`, or `-uix` on request) + the view library (`reagent/reagent`, or `com.pitch/uix.core` + `uix.dom`); npm is `shadow-cljs`, `react`, `react-dom`. **No schemas, no Xray, no Story, no HTTP, no CSP or hosting policy on day one** — each is a later, explicit step the generated README's *Next steps* links, installed or explained only when the author asks. The per-feature artefacts (`-schemas`, `-machines`, `-routing`, `-flows`, `-http`, `-ssr`, `-epoch`) come in **only when the author starts using the feature**. The skill resists "add them all defensively". (Before slice B the Reagent route carried `-schemas` + `-xray` + the `@xyflow/react` / `elkjs` npm pair day-one; that shape is retired, not wrapped in compatibility wording.)

### L4 — The Reagent adapter is the default reference substrate; UIx is a three-file swap

Unless the author explicitly says UIx, scaffold against Reagent — Reagent v2 is the canonical default. The skill does not branch into a multi-substrate decision tree at greenfield, but it **does** cover UIx greenfield completely and cheaply: the UIx project is the same twelve files with `deps.edn`, `core.cljs` and `views.cljs` replaced — exactly the set the template's `template-fn` varies per substrate — and those three are rendered from the template's `_uix/` tree into `references/entry-namespace.md` §UIx greenfield by the same derivation (L13). The nine shared files are not restated per substrate. SKILL.md cardinal rule 3 and README.md §"What it deliberately does not cover" carry the same pointer.

### L5 — Don't write tests for the author

The skill stops at "the counter mounts". The scaffold's `events_test.cljs` is the template's starter file, shipped because the scaffold is the template's emission (L13) — it is not the skill authoring tests. Anything after that — events, subs, machines, schemas, test-authoring — is the main `re-frame2` skill (which itself defers test-writing to the author per its own Q14 lock).

### L6 — Q14 — NO verification module; the skill still runs the build

No `references/verify.md` leaf. But the skill is the **executor** (re-locked 2026-08-31, rf2-rc0yh — the earlier "the author runs the build; the skill doesn't" posture is retired, not wrapped in compatibility wording): it writes the files, points the framework coordinates at something that resolves, runs `npm install`, runs a terminating `npx shadow-cljs compile app`, starts the dev server, and reports the actual URL. What it never does is claim the browser mounted from compile success alone — the Done checklist in SKILL.md lists the mount conditions and the author confirms them in the open page.

### L7 — No bead-ids in user-facing skill content

`SKILL.md` + `references/` carry no `rf2-XXXX` references. The `spec/` folder may; user-facing leaves do not.

### L8 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — never commit `ai/` or `findings/`. This skill's commits contain only `skills/re-frame2-setup/**` (plus the fixture it owns under `tools/template/test/`).

### L9 — Single-import contract from day one

The scaffold imports `re-frame.core` as `rf` and `re-frame.adapter.reagent` as `reagent-adapter`. No private-namespace requires; no `re-frame.db` style reach-ins. The contract the author starts with is the contract the main `re-frame2` skill enforces from there on.

### L10 — Clean hand-off on exit; cross-skill routing is single-sourced in `skills/README.md`

SKILL.md ends with a hand-off paragraph (to `re-frame2` for code-writing; `re-frame2-pair` for live REPL; `re-frame2-xray` only as the tour of a panel the author attaches later) plus an "anything else → `re-frame2` / `SKILL-REDIRECT.md`" line. The hand-off leads with the facts: files written, the verification command that succeeded, the served URL (L6). It carries **no** per-skill routing table — cross-skill routing is single-sourced in [`skills/README.md` §Skill routing](../../README.md#skill-routing--single-source) per the family convention.

### L11 — Zero-interview default (one prompt, one served SPA)

An unqualified greenfield request ("scaffold a re-frame2 app for me") takes **no clarification round**: Reagent substrate (L4), the generator template's pinned baseline as the version pin (already in the leaf, L1), the template's reference project identity (`acme/my-app` → namespace `acme.my-app`, `src/acme/my_app/`, build id `:app`, dev port `8280`), and the smallest runnable counter. An author-supplied name, pin, explicit "latest", or explicit UIx request overrides the matching default; absence of any of them is never a reason to stop and ask. No wizard, no option matrix, no `:minimal?` flag, no compatibility aliases (rf2-rc0yh). (Slice A used a hand-written `your-app` identity; slice B moved to the template's reference identity because the leaf is now the template's emission, and deps-new only produces two-segment names.)

### L12 — Both routes are the skill's to execute, and both land on the same files

The manual route writes `first-counter.md`'s files; the generator route runs `clojure -Tnew create …` itself when the author asks for it (the `allowed-tools` grant covers it; pre-publish via the absolute `:local/root` invocation). Either way the emitted `deps.edn` carries forward-correct `:mvn/version` framework coords that do not resolve pre-publish, so both routes continue at SKILL.md step 2 — point the two coordinates at the reviewed checkout — before `npm install`. The earlier user-run-only prohibition (the original drift-test Lock 12, retired by rf2-rc0yh) is no longer policy.

### L13 — The default scaffold is derived from the template, never hand-maintained

`references/first-counter.md`'s twelve files and `references/entry-namespace.md`'s three UIx files are generated regions rendered from `tools/template/` by `tests/first_counter_derivation.clj` (which loads the template's real `hooks.clj` for the substitution values and the file map, and re-does only deps-new's `{{key}}` copy). Two locks hold them to the template from different instruments: `setup_drift_test.clj` compares the leaves to the Babashka render; `emitted_test_run_test.clj` compares them to a real deps-new emission byte for byte. A template change that alters the emission reds both until the leaves are regenerated. There is no second template, no per-skill variant of any file, and no hand edit inside a generated region (rf2-rc0yh slice B, consuming rf2-zq34m's one-selector contract).

### L14 — One project-identity derivation, and it is the generator's

An author-supplied project name is **derived**, not renamed token by token. `SKILL.md` §Project identity states the one rule both routes use, and it is the generator template's own: normalise to `group/artefact` (an unqualified name is doubled, as deps-new doubles it); the namespace root is the whole coordinate with `/` → `.` and `_` → `-`; the source/test path is the whole coordinate with `.` → `/` and `-` → `_`; the npm name is the artefact segment lowercased and the project directory that segment verbatim; the `<h1>` / `<title>` / README heading is the coordinate verbatim. An artefact segment npm cannot take is refused **before the first file is written**, matching `->npm-name`, which throws before deps-new emits.

The manual route is NOT folded into the generator, and that is deliberate: it is the default route, it needs nothing beyond the skill's `allowed-tools` grants, and cardinal rule 4 already falls back to it when the globally-installed `-Tnew` deps-new tool is absent. What is single-sourced is the *derivation*, not the file-writing — `tests/project_identity_test.clj` loads the template's real `hooks.clj` and executes the rule as SKILL.md words it, so the two cannot drift silently (rf2-sioc). Before that rule existed, the leaf said only to rename `acme` / `my-app` consistently, which is deterministic for the reference identity alone: a textual rename of `com.acme/my-cool-app` lands `src/com.acme/my_cool_app`, which does not back the namespace `shadow-cljs.edn`'s `:init-fn` names, and the scaffold dies at the terminating compile wearing an error that mentions neither the name nor the rename.

## 4. Audience and scope

### In scope

- Authors starting a new directory (or an existing empty CLJS project) that needs re-frame2 wiring.
- The six canonical steps: write the twelve files → point the framework coordinates → `npm install` → terminating compile → watch → report.
- Reagent v2 as the default substrate; UIx as the three-file swap (L4).
- Troubleshooting the common build failures (SKILL.md's Troubleshooting section: the unresolvable pre-publish coordinate, missing `.cljs` namespace vs missing npm React, missing `rf/init!`, missing `<main id="app">`, `:init-fn` mismatch).

### Out of scope

- Migrating from re-frame v1 → `skills/re-frame-migration/`.
- Authoring application code beyond the first counter → `skills/re-frame2/`.
- Live-runtime debugging → `skills/re-frame2-pair/`.
- Building re-frame2 in a different host language → `skills/re-frame2-implementor/`.
- Full multi-substrate decision trees at greenfield — Reagent is the default; UIx is a documented swap, not a branching interview (L4).
- Installing or explaining schemas, Xray, Story, HTTP, SSR, CSP or hosting on the default route (L3).
- Writing tests, registering events, subs, machines, schemas — all the main `re-frame2` skill's job.

## 5. File structure (locked)

```
skills/re-frame2-setup/
├── SKILL.md                       (router; the six-step canonical path)
├── README.md                      (human-facing intro)
├── LICENSE                        (MIT)
├── package.json                   (npm metadata)
├── .claude-plugin/plugin.json     (Claude Code plugin metadata)
├── references/
│   ├── first-counter.md           (the default scaffold: the twelve files, derived — the default route's one leaf)
│   ├── deps-versions.md           (lockstep VERSION discipline; default pins + overrides; coordinate shapes; pay-as-you-go)
│   ├── shadow-cljs.md             (build config + page explained; hot reload; :test build; release; nREPL)
│   └── entry-namespace.md         (rf/init! + React-root contract; the UIx three-file swap, derived)
├── spec/
│   ├── design.md                  (this file)
│   ├── inputs.md                  (canonical inputs)
│   └── authoring-prompt.md        (one-shot reauthor prompt)
├── tests/
│   ├── first_counter_derivation.clj (renders the two generated regions from the template; Babashka)
│   ├── setup_drift_test.clj       (structural drift guard incl. the derivation lock; Babashka)
│   ├── generator_route_test.clj   (generator-route command fixture; Babashka)
│   └── project_identity_test.clj  (the identity rule, held to the template hook; Babashka)
└── evals/
    └── evals.json                 (trigger-accuracy fixture + graded start-from-nothing / generator-route evals)
```

Each reference leaf targets ≤16 KB per the family leaf-size discipline ([`skills/README.md` §Leaf size discipline](../../README.md#leaf-size-discipline)), and every leaf meets the byte ceiling. `first-counter.md` runs past the family *line* target because it carries twelve small files as fenced blocks; that is the per-session token-saving exception the discipline allows — the default route reads SKILL.md plus this one leaf (~26 KB) where it previously read SKILL.md plus three leaves (~76 KB). A typical greenfield session reads SKILL.md + 1 reference leaf (2 for UIx, adding `entry-namespace.md`). `spec/`, `tests/`, and `evals/` are excluded from the npm `files` array by design.

## 6. Discovery surface (frontmatter `description`)

The `description` is "pushy" and lists the greenfield-trigger phrases the shipped frontmatter carries: *"start a re-frame2 project"*, *"scaffold re-frame2"*, *"hello-world re-frame2 app"*, *"new re-frame2 app"*, plus a build failure on a freshly-scaffolded project tracing to missing `re-frame.core` / `re-frame.adapter.reagent` wiring. It explicitly handles off-task routing: once the counter mounts, the author switches to `re-frame2` for code-writing or `re-frame2-pair` for live pair-programming. (It deliberately omits the ambiguous *"add re-frame2 to my repo"* — that phrasing also matches the non-trivial-existing-app case the skill routes away.)

## 7. Anti-patterns the skill explicitly resists

- **Hand-typing artefact versions** — L1: the pins are derived, and prose never restates them as authority.
- **Mixing versions across the ten artefacts** — L2 cardinal rule.
- **Adding per-feature artefacts, devtools, schemas or policy defensively** — L3 + `references/deps-versions.md`'s "pay-as-you-go" framing.
- **A full multi-substrate decision tree at greenfield** — L4. Reagent is the default; UIx is a three-file swap, not a branching interview.
- **Interviewing the author when a reviewed default exists** — L11. A missing pin / name / substrate / tooling answer is a default, not a question; the only stops are the explicit-latest confirmation and a genuinely non-greenfield project.
- **Handing the author a command the skill can run** — L12/L6. Install, the terminating compile, the watch, and (on request) the generator are the skill's to execute.
- **A second, hand-maintained copy of the template** — L13. Files change in `tools/template/`, and the leaves are regenerated.
- **Writing tests for the author** — L5 cardinal rule.
- **Drifting into application-code authoring** — L5/L10; the exit hand-off routes past-setup work to `re-frame2`.

## 8. Why this design diverges from `re-frame2`

- **No patterns/ directory.** Setup is one workflow, not a library of recipes.
- **No decision-trees/ directory.** The only decision is "which per-feature artefacts do I need later?" and lives inline in `references/deps-versions.md`.
- **No examples-map.md.** The one example is the scaffold itself, inlined in `references/first-counter.md`.
- **A clean exit hand-off, not a per-skill routing table.** The skill is the *entry point* into the family; SKILL.md ends with a hand-off paragraph pointing at the next skill, while cross-skill routing is single-sourced in `skills/README.md` (see L10).

## 9a. Testing & drift guards

The skill's shipped scaffold is proven end to end and drift-guarded in re-frame2's CI:

- **`setup-skill-default-scaffold-mounts-test`** (`tools/template/test/day8/re_frame2_template/emitted_test_run_test.clj`) — the black-box fixture: materialises the twelve files straight out of the shipped `references/first-counter.md` into a fresh temp project, applies the documented pre-publish `:local/root` step, links `node_modules`, compiles the `:app` + `:test` builds, asserts the emitted `package.json` declares every npm package the build resolved, loads the real `index.html` in Chromium and proves the heading paints, the counter reads `0` and a click moves it to `1`, runs the starter test under Node, and proves its own teeth twice — the same browser proof must go RED with the mount node renamed (build/init wiring) and with `views.cljs` dispatching an unregistered event (click path). Behind the `RF2_TEMPLATE_RUN_EMITTED_TESTS=1` gate (the `jvm-tools-template` CI job, armed by any change under `skills/re-frame2-setup/references/` or `tools/template/`). It proves the scaffold against in-repo coords — **not** that a published Clojars coordinate resolves from a fresh project (that buildability gate stays deferred to publication).
- **`setup-skill-leaves-are-the-template-emission-test`** (same file, ungated) — runs the real deps-new pipeline for `acme/my-app` on both substrates and asserts the leaves' generated blocks equal the emitted files byte for byte; its failure message names the regeneration command.
- **`scripts/check_skill_setup_counter_drift.py`** — repo-level Python gate (`verify-skill-mcp-drift` CI job): counter-id vocabulary containment (first-counter.md ↔ entry-namespace.md ↔ template), the `:init-fn` hot-reload lifecycle wording, Spec 006 adapter-key vocabulary, one-canonical-source (first-counter.md is the sole copy-complete Reagent `core.cljs`), and the schemas single-require contract.
- **`tests/setup_drift_test.clj`** — skill-local Babashka structural guard (`skills-structural` CI job): the derivation lock (the leaves equal `first_counter_derivation.clj`'s render, and carry no `{{…}}` / `<VERSION>` placeholder), the day-one-set locks (no schemas, Xray coord, devtools preload, `@xyflow/react` / `elkjs`, Xray host or CSP on the default route; the UIx route is the template's three-file swap and shares the other nine files), the build-discipline lockstep framing, the UIx template-pin parity, the publication-state coordinate branch, the zero-interview pin default + executor posture, the frame-root ENSURE boot on both substrates, the `^:dev/after-load` hook in the UIx entry ns, the leaf-size ceiling, and the public entry-ramp docs (docs-site page + skills index). Run locally with `bb tests/setup_drift_test.clj`.
- **`tests/project_identity_test.clj`** — the L14 parity guard (`skills-structural` CI job): `load-file`s the template's real `hooks.clj` and compares `data-fn`'s `:namespace` / `:nested-dirs` / `:npm-name` against SKILL.md's identity rule *executed*, for the four inputs whose answers differ — the reference identity, a dotted qualified name, a bare (doubled) name and a mixed-case name — plus the npm-invalid artefact that must fail before emission. It also asserts SKILL.md states each derived string and that neither SKILL.md nor `first-counter.md` still offers a consistent token rename. Run locally with `bb tests/project_identity_test.clj`.
- **`tests/generator_route_test.clj`** — resolves the `:local/root` out of the documented generator command exactly as `tools.deps` would, against a fresh target directory; the opt-in live arm (`RF2_SETUP_RUN_GENERATOR=1`) shells the real command.

Broader real-regression coverage of the wiring lives in the substrate contract tests (`npm run test:cljs`) and the template's own tiers.

## 9. Open questions (deferred to Mike)

### OQ1 — Should the skill cover non-Reagent greenfield? — RESOLVED (done)

**Resolved: yes, at the recipe level, and completely.** The UIx route is the template's three-file swap, derived from `_uix/` (L4, L13). Reagent stays the default. (The recipe originally covered Helix too; Helix was removed from the adapter roster at the S7 wave — EP-0030 Resolved Decisions, 2026-07-17 — and the skill's Helix arm was pruned with it.)

### OQ2 — Should the skill ship a runnable `setup.bb` script? — RESOLVED (no)

Superseded by L12/L13: the generator template is the one-command scaffolder and the skill runs it on request; the manual route writes a derived copy of the same files. A third mechanism would be a second generator.

### OQ3 — Should troubleshooting move to its own leaf?

Currently inlined at the end of SKILL.md (`Troubleshooting`). If it grows beyond ~30 lines, promote to `references/troubleshooting.md`. Status: monitored; not a blocker.

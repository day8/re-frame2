# re-frame-pair2 — Authoring Prompt

A self-contained prompt that re-authors the `re-frame-pair2` skill from this `spec/` folder alone. Drop into a fresh Claude Code session in the re-frame2 repo root.

## The prompt

> *I'm re-authoring the `re-frame-pair2` skill at `skills/re-frame-pair2/`. The skill teaches an AI to **pair-program with a live, running re-frame2 application** — inspect any frame's app-db, dispatch events, hot-swap handlers, walk the trace stream and per-frame epoch ring, time-travel via `restore-epoch`, do DOM-to-source resolution. The skill consumes re-frame2's own Tool-Pair contract (per `spec/Tool-Pair.md` and `spec/009-Instrumentation.md`) — there is **no re-frame-10x dependency**.*
>
> *Read these first (in this order):*
>
> *1. `skills/re-frame-pair2/spec/design.md` — the locked design decisions (L1 through L12). Pillars 1-4 in §2 are non-negotiable.*
> *2. `skills/re-frame-pair2/spec/inputs.md` — the canonical inputs the skill leans on.*
> *3. `spec/Tool-Pair.md` — the contract the skill consumes.*
> *4. `spec/009-Instrumentation.md` — the trace-stream / epoch-record / `:rf/op` vocabulary the skill teaches.*
> *5. `spec/002-Frames.md` — the multi-frame model the skill operates against.*
> *6. `implementation/core/src/re_frame/{core,trace,epoch,frame}.cljc` — the verified Tool-Pair surfaces.*
> *7. `skills/re-frame2/SKILL.md` + `skills/re-frame-migration/SKILL.md` — the voice / density / cardinal-rules style to mirror.*
>
> *Then write the skill at `skills/re-frame-pair2/` with this exact file structure:*
>
> ```
> skills/re-frame-pair2/
> ├── SKILL.md                            (~130 lines; router + connect-first rules)
> ├── README.md                           (human-facing intro)
> ├── LICENSE                             (MIT)
> ├── RELEASING.md                        (npm + plugin release notes)
> ├── STATUS.md                           (development status)
> ├── package.json                        (npm metadata)
> ├── .claude-plugin/plugin.json          (Claude Code plugin metadata)
> ├── references/
> │   ├── ops.md                          (op catalogue)
> │   ├── recipes.md                      (named procedures)
> │   ├── errors.md                       (structured error → English + recovery)
> │   ├── hot-reload-protocol.md          (source-edit → wait-for-reload protocol)
> │   ├── migration-from-v1.md            (re-frame-pair v1 → v2 surface map)
> │   └── mcp-transport.md                (MCP install + bash-shim mapping)
> ├── scripts/                            (bash shims — deprecated)
> ├── tests/                              (smoke tests)
> ├── docs/                               (maintainer docs)
> └── spec/
>     ├── design.md
>     ├── inputs.md
>     └── authoring-prompt.md
> ```
>
> *Every reference is ≤250 lines. SKILL.md is ~130 lines (well under Anthropic's 500-line ceiling). All references one level deep — no SKILL → A → B chains.*
>
> *Frontmatter — `allowed-tools` lists every MCP tool the skill uses (`mcp__re-frame-pair2__discover-app`, `eval-cljs`, `inject-runtime`, `dispatch`, `trace-window`, `watch-epochs`, `tail-build`) plus every bash shim (`Bash(scripts/discover-app.sh *)` etc.) plus the editor tools (`Read`, `Edit`, `Write`, `Grep`, `Glob`). The `description` is "pushy" and lists every re-frame2 surface: `app-db`, `dispatch`, `subscribe`, `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, `frame`, `epoch`, `interceptor`, `sub-cache`, `trace-buffer`, `register-trace-cb`, `register-epoch-cb!`, `restore-epoch`, plus toolchain (`re-com`, `shadow-cljs`).*
>
> *Cardinal rules to bake in (SKILL.md):*
>
> *1. **Three primitives, no more** — REPL, trace stream, epoch history. All in re-frame2's Tool-Pair contract.*
> *2. **No re-frame-10x dependency** — time-travel and trace consumption ride on `register-trace-cb` / `register-epoch-cb!` / `epoch-history` / `restore-epoch`.*
> *3. **Connect first, every session** — `discover-app` before any op. Failures return structured edn; report verbatim, don't improvise workarounds.*
> *4. **Two modes of changing the app** — REPL changes ephemeral; source edits permanent (must `hot-reload/wait` after).*
> *5. **Multi-frame model** — mutating ops refuse with `:ambiguous-frame` if more than one frame is registered and none selected. Read ops fall back to `:rf/default` after warning.*
> *6. **One trace listener and one epoch listener** under `:re-frame-pair2` / `:re-frame-pair2-epoch`. Multi-tool coexistence is the expected default.*
> *7. **Use the assembled epoch stream by default; reach for the raw trace stream when the projection drops detail.***
> *8. **Read before you write** — ground a hypothesis in a snapshot / epoch / sub-run before proposing a change.*
> *9. **Resolve UI references to source first** — `dom/source-at` before speculating about behaviour.*
> *10. **Surface restore limits** — walk the cascade's already-fired effects before any time-travel experiment.*
>
> *Locks to preserve verbatim:*
>
> *- **L2 — No re-frame-10x dependency.** Anywhere in the skill.*
> *- **L9 — No bead-ids in user-facing skill content.** `SKILL.md` + `references/` + `scripts/` carry no `rf2-XXXX` references.*
> *- **L10 — Findings stay local.** Don't commit `ai/` or `findings/`.*
> *- **L11 — Resolve UI references to source first.** Style-guidance rule in SKILL.md.*
> *- **L12 — Surface restore limits.** Style-guidance rule in SKILL.md.*
>
> *Voice: tight, declarative, op-shaped. Use tables for op catalogues and routing. Use code blocks for canonical forms. Cite Tool-Pair surfaces (`(rf/register-trace-cb ...)`, `(rf/epoch-history :rf/default)`) verbatim — these are the contract.*
>
> *Don't:*
>
> *- Don't propose fixes that route through `re-frame-10x` — L2.*
> *- Don't teach v1 idioms (`re-frame.db`, `dispatch-with`, etc.) — point at `references/migration-from-v1.md`.*
> *- Don't write `*.md` documentation outside `skills/re-frame-pair2/`.*
> *- Don't commit `ai/` or `findings/` content.*
> *- Don't claim AI authorship anywhere — commits and PR title/body read as Mike Thompson's work.*
> *- Don't include bead-ids in user-facing leaves.*
> *- Don't invent alternate vocabulary (e.g. "state graph" for frame). Pillar 2.*
>
> *Open the PR with title `feat(skills): re-frame-pair2 — live-app pair-programming skill`. PR body lists: the skill structure, the file LoC table, the cardinal rules, the three primitives, the MCP-vs-bash transport story, the relationship to the sibling skills (`re-frame2` for authoring, `re-frame-pair-improver2` for retrospectives).*

## Notes on the reauthoring contract

- The prompt above is a one-shot — feed it to a fresh session, it produces the skill.
- The prompt assumes the session has read access to the re-frame2 repo, the `tools/pair2-mcp/` package, and the `scripts/` shim source.
- The prompt does **not** ask the session to verify the resulting skill against a live app — Mike runs the smoke tests and reviews the PR.
- If `spec/Tool-Pair.md` has changed significantly between authoring passes, the `references/ops.md` and `references/recipes.md` need re-derivation from the new contract.

## When to re-author

- The Tool-Pair contract gains a new structured surface (e.g. a future `register-machine-cb` or a new projection) → the existing references' coverage is incomplete; rebuild.
- The MCP server gains or loses tools materially → the `allowed-tools` block and `references/mcp-transport.md` need updates.
- The bash shims are retired entirely (OQ1 resolves) → strip `scripts/` and the bash entries in `allowed-tools`.
- Anthropic skill conventions change materially (e.g. `allowed-tools` shape changes) → reauthor against the new conventions.
- A major class of recipe is added (e.g. machine-replay, sub-cache-eviction) → add a recipe section and an op catalogue row.

Otherwise, edit existing references directly; reauthoring is for major-version updates.

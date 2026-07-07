# Story-MCP author/refine — and the run-side handoff

> **What this leaf owns.** The *authoring* half of the Story variant workflow: write a variant body, preview its rendered state, read it back, and refine it — all with the story-mcp tools THIS skill (`re-frame2`) is allowed to call. The *run* half — execute a variant against a live runtime, accumulate `:rf.assert/*` records, read the full failure set, and iterate to `:status :pass` — is a **separate, runtime-bound surface owned by the `re-frame2-pair` skill**. This is a deliberate hybrid split (`tools/story-mcp/spec/002-Tool-Registry.md`): authoring is static; running needs a browser tab behind `shadow-cljs watch`. This leaf teaches the authoring side and tells you exactly how to hand the run side off.

> **Mental model: think in Storybook, map onto Story.** When authoring a variant, sketch it as a Storybook story first (which args, which play steps?), then translate to the EDN `reg-variant` body — see `stories.md` §Mental model for the full concept map. Story's distinctive twist over a Storybook play function: `:rf.assert/*` events *record* (they don't throw), so a single run returns **every** mismatch at once — but that recording-and-reading is the run side, which `re-frame2-pair` drives.

## When to load

- An agent (Claude Code, Cursor, Copilot) is **writing or refining** variant bodies against a re-frame2 codebase and wants the story-mcp authoring surface (register / preview / read-back / explain).
- You need to know the ownership boundary — which story-mcp tools this skill can call vs. what has to move to a `re-frame2-pair` session.
- You're explaining why "run the variant and self-heal off the failures" is a `re-frame2-pair` operation, not something the authoring skill executes itself.

Do **not** load this leaf to learn how to author a variant body's *contents* — see `stories.md`. Load it for: the authoring tools, the author↔run boundary, and the handoff recipe.

## The two halves

```
   AUTHOR / REFINE  (this skill: re-frame2)        RUN / SELF-HEAL  (re-frame2-pair)
   ┌──────────────────────────────────────┐        ┌──────────────────────────────────────┐
   │ register-variant   write the body     │        │ run-variant      execute, get :status │
   │ preview-variant    eyeball one render │  ───▶  │ read-failures    full :rf.assert/* set │
   │ get-variant        read it back        │  hand  │ (loop until :status :pass)             │
   │ explain-variant    why did it resolve  │  off   │ record-as-variant  capture a cascade   │
   │ unregister-variant tear down            │        │ read-a11y-violations / snapshot-identity           │
   └──────────────────────────────────────┘        └──────────────────────────────────────┘
```

The boundary is the **runtime**. `preview-variant` (this skill) renders one variant and returns its canvas state — enough to confirm the body parses, mounts, and shows the right thing. But the self-healing *loop* — execute, accumulate the assertion records, read the **complete** failure set, refine, re-run — runs each iteration against the live library and is owned by `re-frame2-pair`. Trying to drive that loop from this skill would call tools the skill is not allow-listed for; the agent host blocks them.

## Authoring tools this skill can call — by step

Per `tools/story-mcp/spec/002-Tool-Registry.md`, the story-mcp catalogue is twenty tools across four categories. The authoring subset `re-frame2` is allow-listed for:

| Step | Tool | Category | What it does |
|---|---|---|---|
| Write | `register-variant` | Write (gated) | `re-frame.story/reg-variant*` with the agent's body |
| Write | `unregister-variant` | Write (gated) | symmetric tear-down between iterations |
| Preview | `preview-variant` | Dev | render one variant; returns the unified run-result PLUS the share URL + rendered view ("show me what this looks like") |
| Read | `get-variant` | Docs | full variant body as canonical EDN, for the agent to read before editing |
| Read | `explain-variant` | Docs | "why did the plan resolve this way" — the variant-plan `:explain` (source chain, merge, runner requirements); the agent's mirror of the human Explain panel |
| Onboard | `get-story-instructions` | Dev | the EDN-first constraint, canonical body keys, the seven `:rf.assert/*` events, the four-phase lifecycle, the inclusion-tag vocabulary — one self-contained string. Call once per session, before authoring. |
| Enumerate | `list-stories` / `get-story` / `variant->edn` / `list-tags` / `list-modes` / `list-decorators` / `list-assertions` / `list-substrates` / `get-docs-markdown` | Docs / Dev | navigate an unfamiliar Story registry (and read its docs) while authoring |

What this subset is **missing** (and why): `run-variant`, `read-failures`, `snapshot-identity`, and `read-a11y-violations` are the four **Testing**-category run tools, plus the **Write**-category recorder bridge `record-as-variant`. They surface the live runtime's verdict and captured values, so they live in `re-frame2-pair`'s allow-list — not here. The drift gate (`scripts/check_skill_mcp_drift.py`) pins this split: it marks exactly those five as `intentional_server_only` for `re-frame2`, so an attempt to add them here fails the gate.

`preview-variant`, `run-variant`, and `read-failures` all speak the SAME unified run-result the human Story UI reads (spec/017 §Run result) — there is no agent-only result vocabulary. The headline is the top-level `:status` ∈ `{:pass :fail :cannot-run :error}`. You'll see that `:status` on a `preview-variant` here; the *verdict-driven loop* over it is the run side.

## Worked authoring pass — then the handoff

The agent has been asked to add a "user clicks delete then confirms" variant for `:story.todos/list-with-items`. Authoring side (this skill):

```
agent → register-variant
  {:variant-id :story.todos/delete-confirmed
   :body {:extends :story.todos/list-with-items
          :script [[:dispatch-sync [:todo/delete-pressed 3]]
                   [:dispatch-sync [:todo/confirm-pressed]]
                   [:dispatch-sync [:rf.assert/path-equals [:todos :items] [{:id 1} {:id 2}]]]]}}

agent → preview-variant {:variant-id :story.todos/delete-confirmed}
  ← {:status :pass :share-url "..." :rendered-hiccup [...] :app-db {...} ...}
```

`preview-variant` confirms the body parses, the parent `:extends` resolves, the script mounts, and the rendered canvas looks right. If the preview shows the wrong render or `explain-variant` reveals a bad merge/runner, the agent refines the body and re-registers — still entirely on the authoring side.

When the developer wants to **execute the assertions and self-heal against the running library** — the loop where `run-variant` returns `:status :fail`, `read-failures` returns the complete `:rf.assert/*` mismatch set, and the agent iterates to `:status :pass` — hand off to a `re-frame2-pair` session:

> "The variant body is registered and previews correctly. To run the assertions and iterate against the live runtime, switch to the **re-frame2-pair** skill (it owns `run-variant` / `read-failures` against a running app behind `shadow-cljs watch`) and run the self-healing loop there — it'll see every `:rf.assert/*` mismatch in one pass because the assertions record rather than throw."

The agent reports the registered body + preview result back, and names the run-side surface for the next step. It does **not** pretend to call `run-variant` from here.

## Gates and prerequisites (authoring side)

- **Write surface is gated.** `register-variant` / `unregister-variant` require `re-frame.story-mcp.config/allow-writes?` truthy — set via `--allow-writes` flag, `RF_STORY_MCP_ALLOW_WRITES=true` env, or `-Drf.story-mcp.allow-writes=true` JVM property. Without it, authoring is read-only (`preview-variant` + the enumerations still work against existing variants).
- **`register-variant`'s parent story must already exist.** There is no `register-story` tool. The agent fails into a documented error when the `:story.<path>` parent isn't registered; the user lands the parent inline.
- **Source-coord stamping survives MCP registration.** `register-variant` stamps `{:file <agent-supplied> :line <n>}` if provided in the body; without it, `:source` is omitted and downstream failure records carry no jump-to-line affordance. Agents that want clickable failures (on the run side) supply `:source` from the file they'll write the variant back into.

## Common gotchas

- **The run loop is not this skill's to drive.** `run-variant` / `read-failures` are owned by `re-frame2-pair`. If the task is "run it and fix the failures," that is the handoff — don't infer access to tools the skill isn't allow-listed for.
- **`:rf.assert/*` events record, they do not throw.** A failing assertion does not abort the script — the run side reads the full failure list per iteration. Assertion events ride the `:dispatch-sync` rail in `:script` (the public phase-4 play surface — spec/017 §Public vocabulary; `:play-script` is the transitional spelling the registrar still lowers, but author against `:script`).
- **`:status :pass` is the loop terminator (on the run side).** The top-level `:status` ∈ `{:pass :fail :cannot-run :error}` is the unified verdict (spec/017 §Run result). Distinguish `:fail` (an assertion mismatched — refine the variant) from `:cannot-run` (the runner could not attempt the plan, e.g. a causal assertion under a non-reactive runner — change the runner, refining won't help) from `:error` (a handler / fx / step threw). You may see `:status` on a `preview-variant` here; the verdict-driven *iteration* is the run side.
- **`explain-variant` is the authoring-side read for surprises.** When a preview renders unexpectedly or a plan merges/composes oddly, `explain-variant` shows the resolved plan — source/parent chain, composed fragments/checks, strict-conflict winners, the selected runner + what it required — the agent's mirror of the human Explain panel.

## Deeper material

- Full tool registry + per-tool I/O schemas → `tools/story-mcp/spec/002-Tool-Registry.md` and `tools/story-mcp/spec/API.md`.
- Wire protocol (JSON-RPC over stdio, `initialize` handshake) → `tools/story-mcp/spec/001-Wire-Protocol.md`.
- Write-surface gating → `tools/story-mcp/spec/003-Write-Surface-Gating.md`.
- The **run-side** loop (`run-variant` / `read-failures` / `record-as-variant`) → the `re-frame2-pair` skill (it owns the live-runtime Story tools).
- Recorder integration (`record-as-variant`) → `story-recorder.md` (sibling leaf — recording is a run-side capture).
- Variant body shape, `:rf.assert/*` vocabulary → `stories.md` (sibling leaf).

---

*Derived from `tools/story-mcp/spec/` @ main. Re-verify after MCP tool-registry changes, write-surface gating updates, or any change to the `re-frame2` ↔ `re-frame2-pair` authoring/run split (`scripts/check_skill_mcp_drift.py`).*

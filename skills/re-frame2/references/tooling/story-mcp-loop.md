# Story-MCP self-healing loop

> The agent loop pattern: write a variant → run it via story-mcp → assert via `:rf.assert/*` → refine on failure. Assumes you already know what MCP is — this leaf covers the four-step loop and the story-mcp tools that wire each step.

> **Mental model: think in Storybook, map onto Story.** When authoring a variant in step 1, sketch it as a Storybook story first (which args, which play steps?), then translate to the EDN `reg-variant` body — see `stories.md` §Mental model for the full concept map. The loop's distinctive twist over a Storybook play function: `:rf.assert/*` events *record* (they don't throw), so `read-failures` returns **every** mismatch in one pass — the agent refines against the complete failure set, not just the first thrown assertion.

## When to load this leaf

- An agent (Claude Code, Cursor, Copilot) is generating variants against a re-frame2 codebase and needs to iterate against the running Story library.
- You're hand-driving the loop yourself via the MCP tool palette to debug a flaky variant.
- You're explaining why the agent never needs to read source files to refine — `read-failures` returns enough.

Do **not** load this leaf to learn how to author a variant — see `stories.md`. Load it for: the tools in the loop, the step boundaries, and one worked iteration.

## The four steps

```
   ┌──────────────────────────────────────────────────────────────┐
   │  1. Author     2. Run         3. Assert        4. Refine     │
   │  variant   →   via MCP    →   via :rf.assert/*  →   on fail  │
   │  (or edit)     run-variant    read-failures        update    │
   └──────────────────────────────────────────────────────────────┘
                              ↑                            │
                              └────────────────────────────┘
                                  loop until :status :pass
```

Each step has a story-mcp tool. The loop terminates when `run-variant` returns `:status :pass` or the agent hits its retry ceiling. `run-variant` / `read-failures` / `preview-variant` all speak the SAME unified run-result the human Story UI reads (spec/017 §Run result) — there is no agent-only result vocabulary. The headline is the top-level `:status` ∈ `{:pass :fail :cannot-run :error}`. `:cannot-run` is a distinct THIRD outcome (the runner could not even attempt the plan, e.g. a causal assertion run under a non-reactive runner) — handle it as "not runnable here", **not** as a fail; refining the assertion won't help, the runner needs to change.

## Tool catalogue — by step

Per `tools/story-mcp/spec/002-Tool-Registry.md`, twenty tools across four categories. The seven that participate in the loop:

| Step | Tool | Category | What it does |
|---|---|---|---|
| 1 | `register-variant` | Write (gated) | `re-frame.story/reg-variant*` with the agent's body |
| 1 | `unregister-variant` | Write (gated) | symmetric tear-down between iterations |
| 2 | `run-variant` | Testing | full four-phase lifecycle; returns the unified run-result (headline `:status`) |
| 2 | `preview-variant` | Dev | "show me what this looks like" — same unified run-result plus the share URL + rendered view |
| 3 | `read-failures` | Testing | diagnostic over `:rf.story/assertions` accumulator (no re-run); unified records + aggregate `:status` |
| 4 | `get-variant` | Docs | full variant body as canonical EDN, for the agent to read before editing |
| 4 | `explain-variant` | Docs | "why did the plan resolve this way" — the variant-plan `:explain` (source chain, merge, runner requirements); the agent's mirror of the human Explain panel |
| 4 | `register-variant` | Write (gated) | re-registration with the refined body (overwrites) |

`get-story-instructions` (Dev) is the agent's onboarding read — it returns the EDN-first constraint, the canonical variant body keys, the seven `:rf.assert/*` events, the four-phase lifecycle, and the inclusion-tag vocabulary as one self-contained string. Agents call it once per session, before authoring. When a run resolves `:cannot-run` or merges/composes in a surprising way, `explain-variant` is the read that shows the resolved plan — source/parent chain, composed fragments/checks, strict-conflict winners, the selected runner + what it required.

## Worked loop

The agent has been asked to add a "user clicks delete then confirms" variant for `:story.todos/list-with-items`. Iteration one:

```
agent → register-variant
  {:variant-id :story.todos/delete-confirmed
   :body {:extends :story.todos/list-with-items
          :script [[:dispatch-sync [:todo/delete-pressed 3]]
                   [:dispatch-sync [:todo/confirm-pressed]]
                   [:dispatch-sync [:rf.assert/path-equals [:todos :items] []]]]}}

agent → run-variant {:variant-id :story.todos/delete-confirmed}
  ← {:status :fail :assertions [...] :checks [] :elapsed-ms 18 ...}

agent → read-failures {:variant-id :story.todos/delete-confirmed}
  ← {:status :fail :total 1
     :failures [{:assertion :rf.assert/path-equals
                 :path [:todos :items]
                 :expected []
                 :actual  [{:id 1 :text "buy milk"} {:id 2 :text "..."}]
                 :passed? false :status :fail
                 :source {:file ".../todos.cljs" :line 47}}]}
```

The agent reads the failure: two items still remain because the parent variant seeded *three* todos and the delete only removed id `3`. The assertion was wrong. The agent refines:

```
agent → register-variant   ; overwrites
  {:variant-id :story.todos/delete-confirmed
   :body {:extends :story.todos/list-with-items
          :script [[:dispatch-sync [:todo/delete-pressed 3]]
                   [:dispatch-sync [:todo/confirm-pressed]]
                   [:dispatch-sync [:rf.assert/path-equals [:todos :items] [{:id 1} {:id 2}]]]
                   [:dispatch-sync [:rf.assert/dispatched? [:todo/deleted 3]]]]}}

agent → run-variant {:variant-id :story.todos/delete-confirmed}
  ← {:status :pass ...}
```

Loop terminates. The agent reports the final variant body back to the user.

## Gates and prerequisites

- **Write surface is gated.** `register-variant` / `unregister-variant` require `re-frame.story-mcp.config/allow-writes?` truthy — set via `--allow-writes` flag, `RF_STORY_MCP_ALLOW_WRITES=true` env, or `-Drf.story-mcp.allow-writes=true` JVM property. Without it the loop is read-only (`run-variant` + `read-failures` still work against existing variants).
- **`register-variant`'s parent story must already exist.** v1.1 omits `register-story` deliberately. The agent fails into a documented error when the `:story.<path>` parent isn't registered; the user lands the parent inline.
- **`read-failures` does not re-run.** It reads the last-run accumulator. Pair with `run-variant` per iteration; do not assume an old `:passing? false` reflects the current body.

## Common gotchas — loop-specific

- **`:rf.assert/*` events record, they do not throw.** A failing assertion does not abort the script. `read-failures` returns the full failure list per iteration — the agent sees every mismatch at once, not just the first. Assertion events ride the `:dispatch-sync` rail in `:script` (the public phase-4 play surface — spec/017 §Public vocabulary; `:play-script` is the transitional spelling the registrar still lowers, but author against `:script`).
- **`:status :pass` is the loop terminator.** The top-level `:status` ∈ `{:pass :fail :cannot-run :error}` is the unified verdict `run-variant` returns (spec/017 §Run result). Distinguish `:fail` (an assertion mismatched — refine the variant) from `:cannot-run` (the runner could not attempt the plan — change the runner, refining won't help) from `:error` (a handler / fx / step threw).
- **Snapshot-identity for skip-when-unchanged.** `snapshot-identity` returns a content hash of `(variant × args × decorators × loaders × substrate × modes)`. Agents that iterate across N variants skip cells whose identity matches a previous run.
- **Source-coord stamping survives MCP registration.** `register-variant` stamps `{:file <agent-supplied> :line <n>}` if provided in the body; without it, `:source` is omitted and failure records carry no jump-to-line affordance. Agents that want clickable failures supply `:source` from the file they'll write the variant back into.

## Deeper material

- Full tool registry + per-tool I/O schemas → `tools/story-mcp/spec/002-Tool-Registry.md` and `tools/story-mcp/spec/API.md`.
- Wire protocol (JSON-RPC over stdio, `initialize` handshake) → `tools/story-mcp/spec/001-Wire-Protocol.md`.
- Write-surface gating → `tools/story-mcp/spec/003-Write-Surface-Gating.md`.
- Recorder integration (`record-as-variant`) → `story-recorder.md` (sibling leaf).
- Variant body shape, `:rf.assert/*` vocabulary → `stories.md` (sibling leaf).

---

*Derived from `tools/story-mcp/spec/` @ main. Re-verify after MCP tool-registry changes or write-surface gating updates.*

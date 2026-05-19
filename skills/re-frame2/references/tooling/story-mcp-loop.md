# Story-MCP self-healing loop

> The agent loop pattern: write a variant → run it via story-mcp → assert via `:rf.assert/*` → refine on failure. Assumes you already know what MCP is — this leaf covers the four-step loop and the story-mcp tools that wire each step.

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
                                  loop until :passing? true
```

Each step has a story-mcp tool. The loop terminates when `run-variant` returns `:passing? true` or the agent hits its retry ceiling.

## Tool catalogue — by step

Per `tools/story-mcp/spec/002-Tool-Registry.md`, nineteen tools across four categories. The seven that participate in the loop:

| Step | Tool | Category | What it does |
|---|---|---|---|
| 1 | `register-variant` | Write (gated) | `re-frame.story/reg-variant*` with the agent's body |
| 1 | `unregister-variant` | Write (gated) | symmetric tear-down between iterations |
| 2 | `run-variant` | Testing | full four-phase lifecycle; returns `:passing?` boolean |
| 2 | `preview-variant` | Dev | "show me what this looks like" — post-pipeline state plus share URL |
| 3 | `read-failures` | Testing | diagnostic over `:rf.story/assertions` accumulator (no re-run) |
| 4 | `get-variant` | Docs | full variant body as canonical EDN, for the agent to read before editing |
| 4 | `register-variant` | Write (gated) | re-registration with the refined body (overwrites) |

`get-story-instructions` (Dev) is the agent's onboarding read — it returns the EDN-first constraint, the canonical variant body keys, the seven `:rf.assert/*` events, the four-phase lifecycle, and the inclusion-tag vocabulary as one self-contained string. Agents call it once per session, before authoring.

## Worked loop

The agent has been asked to add a "user clicks delete then confirms" variant for `:story.todos/list-with-items`. Iteration one:

```
agent → register-variant
  {:variant-id :story.todos/delete-confirmed
   :body {:extends :story.todos/list-with-items
          :play [[:todo/delete-pressed 3]
                 [:todo/confirm-pressed]
                 [:rf.assert/path-equals [:todos :items] []]]}}

agent → run-variant {:variant-id :story.todos/delete-confirmed}
  ← {:passing? false :lifecycle :ok :elapsed-ms 18 ...}

agent → read-failures {:variant-id :story.todos/delete-confirmed}
  ← [{:assertion :rf.assert/path-equals
      :path [:todos :items]
      :expected []
      :actual  [{:id 1 :text "buy milk"} {:id 2 :text "..."}]
      :passed? false
      :source {:file ".../todos.cljs" :line 47}}]
```

The agent reads the failure: two items still remain because the parent variant seeded *three* todos and the delete only removed id `3`. The assertion was wrong. The agent refines:

```
agent → register-variant   ; overwrites
  {:variant-id :story.todos/delete-confirmed
   :body {:extends :story.todos/list-with-items
          :play [[:todo/delete-pressed 3]
                 [:todo/confirm-pressed]
                 [:rf.assert/path-equals [:todos :items] [{:id 1} {:id 2}]]
                 [:rf.assert/dispatched? [:todo/deleted 3]]]}}

agent → run-variant {:variant-id :story.todos/delete-confirmed}
  ← {:passing? true ...}
```

Loop terminates. The agent reports the final variant body back to the user.

## Gates and prerequisites

- **Write surface is gated.** `register-variant` / `unregister-variant` require `re-frame.story-mcp.config/allow-writes?` truthy — set via `--allow-writes` flag, `RF_STORY_MCP_ALLOW_WRITES=true` env, or `-Drf.story-mcp.allow-writes=true` JVM property. Without it the loop is read-only (`run-variant` + `read-failures` still work against existing variants).
- **`register-variant`'s parent story must already exist.** v1.1 omits `register-story` deliberately. The agent fails into a documented error when the `:story.<path>` parent isn't registered; the user lands the parent inline.
- **`read-failures` does not re-run.** It reads the last-run accumulator. Pair with `run-variant` per iteration; do not assume an old `:passing? false` reflects the current body.

## Common gotchas — loop-specific

- **`:rf.assert/*` events record, they do not throw.** A failing assertion does not abort the play sequence. `read-failures` returns the full failure list per iteration — the agent sees every mismatch at once, not just the first.
- **`:passing?` is the loop terminator.** Truthy when every assertion in the play sequence passed. Equivalent to `(every? :passed? (:assertions result))` but pre-computed by `run-variant`.
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

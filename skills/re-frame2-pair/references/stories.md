# Stories — driving and asserting against Story variants from a re-frame2-pair session

> The five story-mcp tools allow-listed by `re-frame2-pair` (`run-variant`, `read-failures`, `snapshot-identity`, `read-a11y-violations`, `record-as-variant`) and how they compose with the live-runtime surface. Assumes you've read `SKILL.md` (the trace + epoch primitives) and `variant-as-frame.md` (variant-id IS frame-id).

## When to load this leaf

- A re-frame2-pair session needs to *drive* a Story variant (mount it, run it, re-run after a fix) — not just observe a variant the user is already poking at in the canvas.
- A re-frame2-pair session needs to *assert* against a variant — was the play sequence valid? did the cascade meet `:rf.assert/*` expectations? did axe-core find a regression?
- The user wants to capture a live cascade back into a `:play-script` snippet to bake the current interaction into a variant body.

Do **not** load this leaf to author variants from scratch (no live runtime in the loop) — that's `skills/re-frame2/references/tooling/stories.md`. Load this leaf for the five live-session tools and the composition patterns with re-frame2-pair's reads/writes/watches.

## The five live-session tools — drive + assert palette

The `re-frame2-pair` skill's `allowed-tools` (per SKILL.md frontmatter) pulls in these five **live-session** story-mcp tools — the ones that drive or assert against a running variant. The variant-body **authoring** side (`register-variant`, `get-variant`, `preview-variant`, `list-stories`, …) is allow-listed by `re-frame2` instead — load `recipes.md §Refine a variant interactively` to call those across the skill boundary. (Three further **read-only** story-mcp tools are also allow-listed here — see [§Read-only story-mcp enumerations](#read-only-story-mcp-enumerations) below.)

| Tool | What it does | Returns |
|---|---|---|
| `mcp__re-frame2-story-mcp__run-variant` | Full four-phase lifecycle against an existing variant — loaders → events → render → play | `{:status :app-db :assertions :rendered-hiccup :elapsed-ms :snapshot :narrative}` — `:status` is the verdict (`:pass`/`:fail`/`:cannot-run`/`:error`), read via `result-status`/`result-passed?` |
| `mcp__re-frame2-story-mcp__read-failures` | Diagnostic over the variant's `:rf.story/assertions` accumulator (no re-run) | `{:variant-id :status :total :failures :assertions}` (each record carries a derived `:status`) |
| `mcp__re-frame2-story-mcp__snapshot-identity` | Content hash of `(variant × args × decorators × loaders × substrate × modes)` | `{:identity <hash>}` — use to skip cells unchanged since a prior run |
| `mcp__re-frame2-story-mcp__read-a11y-violations` | axe-core results for the variant's rendered DOM | `{:violations [...]}` (JVM-standalone hosts return `[]` + a hint) |
| `mcp__re-frame2-story-mcp__record-as-variant` | Records dispatches into the variant's frame for `:duration-ms`, returns a `(reg-variant ...)` snippet via `gen-play-snippet`; optional `:write-back` re-registers | `{:play-snippet <string> :captured [<event-vec>...]}` |

`record-as-variant` is the only one whose read-only path is ungated; the write-back branch needs `re-frame.story-mcp.config/allow-writes?` truthy, same gate as `register-variant`. See `tools/story-mcp/spec/002-Tool-Registry.md` for full I/O schemas.

```
mcp__re-frame2-story-mcp__run-variant {variant-id: ":story.counter/loaded"}
mcp__re-frame2-story-mcp__read-failures {variant-id: ":story.counter/loaded"}
mcp__re-frame2-story-mcp__snapshot-identity {variant-id: ":story.counter/loaded"}
mcp__re-frame2-story-mcp__read-a11y-violations {variant-id: ":story.counter/loaded"}
mcp__re-frame2-story-mcp__record-as-variant
  {variant-id: ":story.counter/loaded" :duration-ms 5000}
```

## Read-only story-mcp enumerations

Three further story-mcp tools are allow-listed by re-frame2-pair — all **read-only**, for grounding yourself in a Story-enabled build without crossing to the authoring skill. They take no write gate.

| Tool | What it does | Returns |
|---|---|---|
| `mcp__re-frame2-story-mcp__list-decorators` | Enumerate registered decorators (`:hiccup` / `:frame-setup` / `:fx-override`). Read-only by construction — decorators carry closures JSON-RPC can't transport, so there is **no decorator write tool**. Optional `:kind` narrows; paginated (`:limit` default 25, `:cursor`). | `{:decorators [{:id :kind :doc …}…]}` — each entry adds kind-specific pure-data slots: `:has-wrap?` (`:hiccup`), `:init` + `:app-db-patch` (`:frame-setup`), `:fx-id` + `:response` (`:fx-override`). Use it to see what a variant's `:fx-override` decorators (e.g. `:http → :stub-http`) will do **before** you drive it. |
| `mcp__re-frame2-story-mcp__explain-variant` | The variant-plan **`:explain` projection** — the same data the human Explain panel renders (Story spec/017 §Explain API). Answers *"why did the plan resolve this way?"*. | `{:variant-id :explain {:source-chain :parent-chain :compose :strict-conflicts :merge :effective-args :network :sub-overrides :setup-order :script-order :checks :assertions :required-runner :platforms :tags}}`. Every slot — including the plan-resolved value slots (`:effective-args` / `:args` / `:substitutions` / `:network` replies / `:db-seed`) — is **static author data** read from the variant's own registration, so it **ships raw, exactly like `get-variant` / `variant->edn`** (the threat model scopes `:sensitive` / `:large` marks to observed runtime, not authored registration data — nothing redacts; there is **no `:include-sensitive` knob**). Reach for it when `extends` / `compose` make a variant's effective config non-obvious. |
| `mcp__re-frame2-story-mcp__get-docs-markdown` | Render a story's docs as paste-ready GitHub-flavoured Markdown (story `:doc` + per-variant `:doc` + args / argtypes / tags / decorators composed into one string). The other docs reads (`get-story`/`get-variant`) return EDN — this is the right shape when the user wants a docs blurb for an issue or chat. | Markdown in the wire-canonical `:content` text slot **and** a `:markdown` structuredContent slot. `{:story-id …}`; miss → `:isError` "Story not found". |

These complement the live-session palette: `list-decorators` / `explain-variant` tell you *how a variant will resolve* before you `run-variant` it; `get-docs-markdown` is for handing the user readable docs. None mutate.

## Composition with the re-frame2-pair surface

Per [`variant-as-frame.md`](variant-as-frame.md), the variant id *is* the frame id. Every story-mcp tool targeting a variant operates on the same frame re-frame2-pair reads and writes. Three patterns fall out:

**Snapshot the variant via the variant-as-frame pattern.** Before driving a tool, ground yourself in the variant's current state — read it as a frame, not a story-mcp value:

```
set-operating-frame {frame: ":story.counter/loaded"}
app-db/snapshot                          ;; reads the variant's frame
trace/last-epoch                         ;; epoch history from the variant's frame
```

Then run the story tool against the same id and the results line up with what you just read.

**Watch-epochs scoped to a variant's frame-id.** Open a re-frame2-pair subscription before the play-runner fires so you see every dispatch in order:

```
mcp__re-frame2-pair__subscribe
  {topic: "epoch" filter: {":frame": ":story.counter/loaded"}}
mcp__re-frame2-story-mcp__run-variant {variant-id: ":story.counter/loaded"}
```

Each `notifications/progress` tick on the subscription carries one epoch record from the variant's cascade — including every `:play-script` step the runner drove. The streaming view is richer than `run-variant`'s `:elapsed-ms` summary; pair them when you need the *why* alongside the *whether*.

**Dispatch-from-pair into the variant's frame.** Mid-loop intervention — between iterations of `run-variant`, inject a probe dispatch directly:

```
mcp__re-frame2-pair__dispatch
  {event: "[:counter/inc]" frame: ":story.counter/loaded"}
```

The dispatch lands in the variant's app-db; the next `run-variant` calls `reset-frame!` and wipes it. Useful for "would adding this event between phases pass the assertion?" probes without round-tripping through `register-variant`.

## The agent self-healing loop in re-frame2-pair context

The four-step loop from [`story-mcp-loop.md`](../../re-frame2/references/tooling/story-mcp-loop.md) — author → run → assert → refine — becomes richer when re-frame2-pair is attached. The re-frame2-pair-augmented loop:

```
run-variant fails (:status :fail — result-passed? is false)
   ↓
read-failures — narrow to the offending :rf.assert/*
   ↓
snapshot the variant's frame via app-db/snapshot (variant-as-frame pattern)
   — what state did the play sequence actually leave behind?
   ↓
dispatch a fix via re-frame2-pair mcp__re-frame2-pair__dispatch {frame: ...}
   — probe whether the candidate fix would have made the assertion pass
   ↓
re-run via run-variant
   ↓
loop until :status :pass (result-passed? true)
```

A `:status :cannot-run` is the distinct third verdict — the runner could not even attempt the plan. Handle it as "not runnable here", NOT as a fail: fix the runner/environment, don't refine the body.

What re-frame2-pair adds over the bare loop: a watch-epochs subscription stays open across iterations so you narrate each play event; `dispatch` probes candidate fixes without re-registering the variant; `trace/last-epoch` shows the cascade `read-failures` won't (it reads only the assertion accumulator, not the trace stream).

When the loop terminates, optionally call `record-as-variant` to capture the now-passing interaction as a fresh `:play-script` snippet — the user lands it back in source.

## Common gotchas — live-session specific

- **`run-variant` calls `reset-frame!`.** Each invocation wipes the variant's `app-db` back to `{}` then re-runs loaders + events + play. REPL-only state you'd injected via re-frame2-pair `dispatch` between iterations is gone. Bake setup into `:loaders` / `:events` if you need it to survive (`variant-as-frame.md §Common gotchas`).
- **`read-failures` does not re-run.** It reads the *last* `run-variant`'s `:rf.story/assertions` accumulator. After a manual re-frame2-pair dispatch, the accumulator is stale — re-run before reading.
- **`read-a11y-violations` needs the in-browser panel.** JVM-standalone story hosts return an empty list + a documented hint that axe-core requires the browser. If your session is browser-attached this works; if it's JVM-only, expect the no-op.
- **`record-as-variant`'s filter layers are not free-form.** Filtering is inherited verbatim from `re-frame.story.recorder/recordable-event?` — operation `:rf.event/dispatched`, frame scope match against the target, internal-namespace skip (`:rf.assert/*`, `:rf.story/*`, `:re-frame.story.*`). You can't widen the filter via tool input.
- **Write-back gate is per-server, not per-call.** `record-as-variant` with `:write-back true` needs `--allow-writes` / `RF_STORY_MCP_ALLOW_WRITES=true` set on the story-mcp server start. The read-only path (snippet returned, no registration) needs no gate. Wire-key is `:write-back` (no `?`).

## Cross-references

- The identity that makes all this work — [`variant-as-frame.md`](variant-as-frame.md).
- The bare four-step loop — [`skills/re-frame2/references/tooling/story-mcp-loop.md`](../../re-frame2/references/tooling/story-mcp-loop.md).
- The three variant recipes that compose these tools end-to-end — [`recipes.md` §Drive a Story variant from a re-frame2-pair session](recipes.md#drive-a-story-variant-from-a-re-frame2-pair-session), [§Diff two variants of the same component](recipes.md#diff-two-variants-of-the-same-component), [§Refine a variant interactively](recipes.md#refine-a-variant-interactively).
- Full tool registry + I/O schemas — [`tools/story-mcp/spec/002-Tool-Registry.md`](../../../tools/story-mcp/spec/002-Tool-Registry.md).
- Authoring-side variant body shape — [`skills/re-frame2/references/tooling/stories.md`](../../re-frame2/references/tooling/stories.md).

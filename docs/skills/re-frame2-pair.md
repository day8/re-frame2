# re-frame2-pair

> Pair-program with a live, running re-frame2 application. Attach via nREPL, inspect any frame's `app-db`, dispatch events, hot-swap handlers, walk epochs, and read the trace stream — through re-frame2's own Tool-Pair contract, no `re-frame-10x` dependency.

## What it does

The `re-frame2-pair` skill is the AI pair-programming companion for a running re-frame2 app. The app is up behind `shadow-cljs watch`; the skill attaches to its nREPL session in `:cljs` mode and operates on the live runtime — not just static source files.

Three primitives carry the skill's agency, all part of re-frame2's [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md):

1. **The REPL** — ClojureScript forms evaluated against the real app, usually through helpers in the preloaded `re-frame2-pair.runtime` namespace.
2. **The trace stream** — `(rf/register-listener! :trace id listener)` for live trace events (the verb is stream-parameterised across the two raw dev streams, `:trace` and `:epoch` — a closed vocabulary with no bare two-argument default); `(rf/trace-buffer frame-id)` for the retain-N ring of recent events (frame-id is the first positional arg, `(rf/trace-buffer frame-id opts)` filters, and the fn exists on both platforms).
3. **The epoch history** — `(rf/epoch-history frame-id)` returns the per-frame ring of `:rf/epoch-record` values, each carrying `:db-before`, `:db-after`, `:trace-events`, and the assembled `:sub-runs` / `:renders` / `:effects` projections.

The skill is **multi-frame aware** (Spec 002 — most apps run with one frame, larger apps run several). It registers exactly **one** trace listener (`:re-frame2-pair`) and one epoch listener (`:re-frame2-pair-epoch`) so it coexists with other tools (e.g. `re-frame-10x` v2) on the same bus. Mutating ops refuse with `:ambiguous-frame` when the operating frame is unclear.

The cardinal rule: **REPL changes are ephemeral, source edits are permanent.** After any source edit, the skill waits on the hot-reload protocol before dispatching or tracing — otherwise you interact with the pre-reload code.

## When to reach for it

Load this skill when the user mentions a **running** re-frame2 app, or any of: `re-frame2`, `app-db`, `dispatch`, `subscribe`, `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, frame, epoch, interceptor, sub-cache, trace-buffer, `register-listener!`, `restore-epoch`, re-com, shadow-cljs — *and the question is about the live runtime*, not about writing new code.

Do **not** use this skill for:

- Writing new application code → use [re-frame2](re-frame2.md).
- Greenfield setup → use [re-frame2-setup](re-frame2-setup.md).
- Migrating a v1 project → use [re-frame-migration](re-frame-migration.md).

## Kickoff

Three steps, the first two one-time setup on the app you are pairing with:

1. **Build and register the MCP server.** It is not published to npm yet, so
   build it from a re-frame2 clone
   (`cd tools/re-frame2-pair-mcp && npm install && npm run build`) and point your
   agent host's `mcpServers` entry at the compiled server:

    ```json
    {
      "mcpServers": {
        "re-frame2-pair": {
          "command": "node",
          "args": ["<repo>/tools/re-frame2-pair-mcp/out/server.js"]
        }
      }
    }
    ```

    The server's tools only appear in a session that **starts** after you
    register it — a `--continue`d session will not surface them even when
    `claude mcp list` reports `Connected`. Start a fresh session after
    registering, and again after rebuilding `out/server.js`.

2. **Add the preload to the app.** The `re-frame2-pair.runtime` namespace ships in
   the skill's own `preload/` directory — the MCP server does *not* carry it — so
   put that directory on the app's build **classpath** and add
   `re-frame2-pair.runtime` to its `:devtools :preloads`. The `:preloads` half is
   always a `shadow-cljs.edn` line; the classpath half goes wherever the app's
   classpath is actually owned — an activated alias's `:extra-paths` in `deps.edn`
   for a `:deps` app, `:source-paths` in `project.clj` for a `:lein` app, and
   `:source-paths` in `shadow-cljs.edn` only for a standalone shadow app. (Under
   `:deps` and `:lein`, shadow *ignores* a `shadow-cljs.edn` `:source-paths` key
   and warns that it did, so putting it there yields a preload that never loads.)
   Two dev dependencies go alongside it, at the same revision as the app's core:
   `day8/re-frame2-schemas`, which the preload requires (without it the build
   fails on a missing `re-frame.schemas` namespace), and `day8/re-frame2-epoch`
   with `re-frame.epoch` required at boot, which the epoch and time-travel tools
   need. No package install, dev builds only. **The preload is required; there is no
   per-session inject fallback.** See [`SKILL.md` §Setup](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/SKILL.md#setup--preload-re-frame2-pairruntime)
   for the branch and the snippets.

3. **Start the app and ask.** With `shadow-cljs watch` running and the app open
   in a browser tab, ask about the running app in your own words (*"what's in
   `app-db` under `:cart`?"*). The skill's first call is always `discover-app`:

```
discover-app
```

This locates the shadow-cljs nREPL port, connects, switches to `:cljs` mode for the running build, verifies re-frame2 is loaded with `interop/debug-enabled?` true, and confirms the preloaded runtime namespace landed. Its next read is `orient`, a one-call summary of the app's frames, top-level `app-db` keys and registered ids; only then does it drill into a single sub, path or slice. With one build running there is nothing to pass; with several, it refuses with the list of running builds rather than guessing, and a `port` taken from the tab's URL picks the build served there.

## Server options

The server is `@day8/re-frame2-pair-mcp`, a stdio JSON-RPC server holding one persistent nREPL connection per session; it is the skill's only transport. Three launch flags, passed in the `args` of the `mcpServers` entry, decide what the agent may do:

| Flag | Default | Effect |
|---|---|---|
| `--allow-writes` | off | Enables `restore-epoch` and `replace-app-db`. Without it both refuse with `:rf.error/writes-disabled`. `dispatch` and `replay-epoch` work either way. |
| `--allow-sensitive-reads` | off | Lets a structured read lift `:rf/redacted` per call (`include-sensitive true`). |
| `--no-eval` | absent (eval on) | Disables `eval-cljs` and a `tail-build` probe; they refuse with `:rf.error/eval-cljs-disabled`. |

`eval-cljs` returns values without the redaction the structured reads apply, which is why the skill prefers a typed tool whenever one fits and uses `eval-cljs` for the long tail no typed tool covers (epoch forensics, arbitrary-selector DOM reads, cross-referencing, recovery). The per-tool list with argument signatures is [`references/mcp-transport.md` §MCP tool reference](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/mcp-transport.md#mcp-tool-reference-args); port discovery and the `--port-file` / `SHADOW_CLJS_NREPL_PORT` overrides are in the same file. Server source: [`tools/re-frame2-pair-mcp/`](https://github.com/day8/re-frame2/tree/main/tools/re-frame2-pair-mcp).

## When it stops

Every tool answers a failure with `{:ok? false :reason …}` rather than guessing; the skill reports the reason and its hint verbatim and does not improvise a workaround. The ones you will meet first:

| Reason | What it means | Fix |
|---|---|---|
| `:rf.error/pair-mcp-nrepl-port-not-found` | No shadow-cljs nREPL is reachable. | Start `shadow-cljs watch <build>`; if it is running and still not found, pass `--port-file` or set `SHADOW_CLJS_NREPL_PORT`. |
| `:build-not-running` | shadow is up but not running the named build. | Re-target one of the `:running-builds` the reply lists. |
| `:no-runtime-connected` | The build runs but no browser tab is attached. | Open or reload the app's tab. |
| `:runtime-loaded-but-preload-missing` | The app runs without the preload — or has no re-frame2 dependency at all. | Step 2 above, then reload the page. |
| `:ambiguous-frame` | Two or more app frames and no frame chosen. | Name one — the skill pins it with `set-operating-frame`. |
| `:rf.error/writes-disabled` | The server was launched without `--allow-writes`. | Relaunch with the flag if you want time-travel and state injection. |

The skill cannot reload a browser. When `discover-app`'s `:freshness` says the tab is serving old code (`:stale-build`) or no runtime is live (`:no-runtime`), it relays the URL to reload and waits for you. Epoch reads that come back `[]` after the app has plainly dispatched mean `day8/re-frame2-epoch` is missing from step 2 — `discover-app` does not check for it. The full reason list and recoveries are in [`references/errors.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/errors.md).

## Where the skill lives

- Source: [`skills/re-frame2-pair/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair)
- `SKILL.md`: [`skills/re-frame2-pair/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/SKILL.md)
- Reference leaves: [`skills/re-frame2-pair/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair/references) — [`SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/SKILL.md) §Where the depth lives — loading map names the leaf per question.
- Tool-Pair contract: [`spec/Tool-Pair.md`](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md).
- Narrative companion: [Xray](../xray/index.md).
- Retrospective companion skill: [`re-frame2-pair-retro`](re-frame2-pair-retro.md).

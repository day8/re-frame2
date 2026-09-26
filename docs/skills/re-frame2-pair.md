# re-frame2-pair

> Pair-program with a running re-frame2 app: inspect any frame's `app-db`, dispatch events, hot-swap handlers, walk epochs and read the trace stream, over nREPL and with no `re-frame-10x` dependency.

## What it does

With your app running under `shadow-cljs watch`, the skill attaches to its nREPL session in `:cljs` mode and works on the live app, not just the source files. It uses three things re-frame2 exposes to tools:

1. **The REPL** — ClojureScript evaluated against the real app, usually through helpers in the `re-frame2-pair.runtime` namespace you add to the app's dev build.
2. **The trace stream** — live trace events through `rf/register-listener!`, and recent ones through `rf/trace-buffer`.
3. **The epoch history** — an *epoch* is the record one processed event leaves. `rf/epoch-history` returns a frame's recent epochs, each with `:db-before`, `:db-after`, its trace events, and the sub runs, renders and effects the event caused.

It works with apps that run several frames; when it is unclear which frame to act on, every frame-targeted operation, reads included, refuses with `:ambiguous-frame` rather than guess. It registers exactly one trace listener (`:re-frame2-pair`) and one epoch listener (`:re-frame2-pair-epoch`), so it runs alongside other tools on the same trace stream, Xray included.

**REPL changes are temporary; source edits are permanent.** After a source edit, the skill waits for hot reload to finish before dispatching or tracing, so it never exercises the old code.

## When to reach for it

Use it when you want the agent to look at or change your **running** app, read-only included — *"what's in `app-db`?"*, *"why didn't my view update?"*, *"what did that click do?"*, *"try this handler fix"*. The question is about the live runtime, not about writing new code.

Use a different skill for:

- Writing new application code → [re-frame2](re-frame2.md).
- Greenfield setup → [re-frame2-setup](re-frame2-setup.md).
- Finding your way around the Xray panel yourself → [re-frame2-xray](re-frame2-xray.md).
- A retrospective on a pair session → [re-frame2-pair-retro](re-frame2-pair-retro.md).
- Migrating a v1 project, including its boot smoke-test → [re-frame-migration](re-frame-migration.md).

## Kickoff

Once the [one-time setup](#one-time-setup) below is done, and with `shadow-cljs watch` running and the app open in a browser tab, ask about the running app in your own words:

> *What's in `app-db` under `:cart`?*

The skill's first call is always `discover-app`. It finds the shadow-cljs nREPL port, connects, switches to `:cljs` mode for the running build, checks that re-frame2 is loaded with `interop/debug-enabled?` true, and confirms the preloaded runtime namespace is there. Next it calls `orient`, a one-call summary of the app's frames, top-level `app-db` keys and registered ids. Only then does it read the sub, path or slice you asked about.

With one build running there is nothing to pass. With several, `discover-app` refuses with the list of running builds rather than guessing; a `port` taken from the tab's URL picks the build served there.

### One-time setup

Two steps, both on your side, before the first session:

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

    The server's tools appear only in a session that **starts** after you
    register it — a `--continue`d session will not show them even when
    `claude mcp list` reports `Connected`. Start a fresh session after
    registering, and again after rebuilding `out/server.js`.

2. **Add the preload to the app.** The `re-frame2-pair.runtime` namespace ships in
   the skill's own `preload/` directory — the MCP server does *not* carry it. Put
   that directory on the app's build **classpath** and add
   `re-frame2-pair.runtime` to its `:devtools :preloads` in `shadow-cljs.edn`.
   The classpath entry goes wherever the app's classpath is owned: an activated
   alias's `:extra-paths` in `deps.edn` for a `:deps` app, `:source-paths` in
   `project.clj` for a `:lein` app, and `:source-paths` in `shadow-cljs.edn` only
   for a standalone shadow app. (Under `:deps` and `:lein`, shadow ignores a
   `shadow-cljs.edn` `:source-paths` key and warns about it, so a preload put
   there never loads.) Add two dev dependencies at the same revision as the
   app's core: `day8/re-frame2-schemas`, which the preload requires (without it
   the build fails on a missing `re-frame.schemas` namespace), and
   `day8/re-frame2-epoch`, with `re-frame.epoch` required at boot, which the
   epoch and time-travel tools need. Dev builds only, no package install. **The
   preload is required; there is no per-session fallback.** The snippets for
   each build tool are in [`references/setup.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/setup.md).

## Server options

The server is `@day8/re-frame2-pair-mcp`, a stdio JSON-RPC server holding one persistent nREPL connection per session; it is the skill's only transport. Three launch flags, passed in the `args` of the `mcpServers` entry, decide what the agent may do:

| Flag | Default | Effect |
|---|---|---|
| `--allow-writes` | off | Enables `restore-epoch` and `replace-app-db`. Without it both refuse with `:rf.error/writes-disabled`. `dispatch` and `replay-epoch` work either way. |
| `--allow-sensitive-reads` | off | Lets a structured read lift `:rf/redacted` per call (`include-sensitive true`). |
| `--no-eval` | absent (eval on) | Disables `eval-cljs` and a `tail-build` probe; they refuse with `:rf.error/eval-cljs-disabled`. |

The server does not refuse a flag it cannot read: a misspelled or retired flag is logged to stderr at startup and ignored, so that gate stays at its default.

`eval-cljs` returns values without the redaction the structured reads apply, which is why the skill prefers a typed tool whenever one fits and uses `eval-cljs` for the long tail no typed tool covers (epoch forensics, arbitrary-selector DOM reads, cross-referencing, recovery). The per-tool list with argument signatures is [`references/mcp-transport.md` §MCP tool reference](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/mcp-transport.md#mcp-tool-reference-args); port discovery and the `--port-file` / `SHADOW_CLJS_NREPL_PORT` overrides are in the same file. Server source: [`tools/re-frame2-pair-mcp/`](https://github.com/day8/re-frame2/tree/main/tools/re-frame2-pair-mcp).

## When it stops

Every tool answers a failure with `{:ok? false :reason …}` rather than guessing; the skill reports the reason and its hint verbatim and does not improvise a workaround. The ones you will meet first:

| Reason | What it means | Fix |
|---|---|---|
| `:rf.error/pair-mcp-nrepl-port-not-found` | No shadow-cljs nREPL is reachable. | Start `shadow-cljs watch <build>`; if it is running and still not found, pass `--port-file` or set `SHADOW_CLJS_NREPL_PORT`. |
| `:build-not-running` | shadow is up but not running the named build. | Re-target one of the `:running-builds` the reply lists. |
| `:no-runtime-connected` | The build runs but no browser tab is attached. | Open or reload the app's tab. |
| `:runtime-loaded-but-preload-missing` | The app runs without the preload — or has no re-frame2 dependency at all. | Step 2 above, then reload the page. |
| `:debug-disabled` | The build has `interop/debug-enabled?` false — a production build, or `goog.DEBUG` set false — so it carries no trace stream or epoch history. | Attach to a dev build. |
| `:no-frames-registered` | No frame is up yet. `rf/init!` installs the adapter but creates no frame. | Wait for the app to boot, or have it create its frame at the root (`frame-root {:id …}` or `make-frame`). |
| `:ambiguous-frame` | Two or more app frames and no frame chosen. | Name one — the skill pins it with `set-operating-frame`. |
| `:rf.error/writes-disabled` | The server was launched without `--allow-writes`. | If you want time-travel and state injection, add `--allow-writes` to the server's `args` and start a fresh session. |

The skill cannot reload a browser. When `discover-app`'s `:freshness` says the tab is serving old code (`:stale-build`) or no runtime is live (`:no-runtime`), it relays the URL to reload and waits for you. `:unknown` means the build's state could not be read, usually because a stale shadow-cljs JVM is still running: stop it with `npx shadow-cljs stop`, start one `watch`, reload the tab and let the skill reconnect.

Epoch reads that come back `[]` after the app has plainly dispatched mean `day8/re-frame2-epoch` is missing from step 2 — `discover-app` does not check for it. Without it `dispatch-dry-run`, `restore-epoch` and `replay-epoch` refuse too, and `replace-app-db` fails with `:rf.error/epoch-artefact-missing`. The full reason list and recoveries are in [`references/errors.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/errors.md).

## Where the skill lives

- Source: [`skills/re-frame2-pair/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair)
- `SKILL.md`: [`skills/re-frame2-pair/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/SKILL.md)
- Reference notes: [`skills/re-frame2-pair/references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair/references) — `SKILL.md` §Where the depth lives names the note for each question.
- The trace stream, trace buffer and epoch history it reads: [Observability](../core/observability.md#tools-that-read-the-trace-stream).
- The devtools panel for humans: [Xray](../xray/index.md).
- Retrospective skill: [re-frame2-pair-retro](re-frame2-pair-retro.md).

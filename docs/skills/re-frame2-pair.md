# re-frame2-pair

Works on your running re-frame2 app from the agent, over the app's shadow-cljs nREPL: reads any frame's `app-db`, dispatches events, hot-swaps handlers, walks epochs and reads the trace stream.

## Kickoff

With the [one-time setup](#one-time-setup) done, `shadow-cljs watch` running and the app open in a browser tab, ask about the running app in your own words:

> *What's in `app-db` under `:cart`?*

The skill's first call is always `discover-app`. It finds the shadow-cljs nREPL port, connects, switches to `:cljs` mode for the running build, checks that re-frame2 is loaded with `interop/debug-enabled?` true, and confirms the preloaded runtime namespace is there. Next it calls `orient`, a one-call summary of the app's frames, top-level `app-db` keys and registered ids. Only then does it read the sub, path or slice you asked about.

With one build running there is nothing to pass. With several, name the build or give the app tab's URL: the skill can pass its integer `port` to `discover-app`. This is the browser port, not the nREPL port. The selected build stays selected until a switch or nREPL reconnect.

For several app frames, name the one you want, such as `:app/main`. An explicit per-call frame wins over the session pin; otherwise the sole app frame is selected. Reserved tool frames do not create ambiguity. `set-operating-frame` pins the session choice and `reset-operating-frame` clears it.

## What you can ask

| Request | What the skill uses |
|---|---|
| "Read `[:cart/count]` and the cart items." | Targeted subscription and `app-db` reads after orientation. |
| "What would `[:cart/remove 42]` do?" | `dispatch-dry-run`: execute the handler, suppress declared effects, then roll the frame back. |
| "Remove item 42 and show what happened." | Real dispatch, the resulting epoch and a screen read when relevant. |
| "Record the cart count while I click Remove." | Signal recording and readback; it can also wait for a specified condition. |
| "Why did this view render?" | UI/source evidence and, for Fresco, mounted boundaries and subscription-read attribution. |
| "Try this handler fix, then keep it." | A temporary REPL experiment followed by a source edit and verified hot reload. |
| "Restore that epoch, or replay its event." | Separate restore and replay tools; replay uses the retained event and recorded coeffects. |
| "Run the cart Story variant in this tab." | Story operations in the same browser runtime, then the ordinary frame tools. |

`dispatch` and `replay-epoch` fire real effects, including HTTP, navigation and storage writes. A later restore cannot undo those effects. Replay does not rewind state first; request a restore separately when the starting state matters. The skill announces a live change before doing it.

A dry run needs epoch recording. It suppresses declared effects and restores frame state, but cannot undo arbitrary side effects inside handler or listener code, and listeners may observe the temporary state. It also does not simulate effect-dispatched child events. The skill treats a result as rolled back only when both `:ok?` and `:rolled-back?` are true.

## Keeping a fix

An *epoch* records the work caused by a top-level event, including its child dispatches: before/after state, subscriptions, renders and effects. The skill uses those records for the result of a dispatch and raw traces for finer detail. It can run alongside Xray on the same trace stream.

A change made at the REPL, such as a hot-swapped handler, is temporary: the next reload of that code replaces it, so a fix you want to keep goes into the source file. After a source edit, the skill waits for hot reload to finish before dispatching or tracing, so it never exercises the old code.

The handoff names the observed result and its build/frame, saved source edits, and any temporary REPL changes still in place. Verification that has not happened remains explicit; a proposed fix is not reported as a verified live result.

## When to reach for it

Use it when you want the agent to look at or change your **running** app, read-only questions included. The question is about the live runtime rather than about writing code.

For related work:

- Reading the Xray panel yourself → [re-frame2-xray](re-frame2-xray.md). The line is a human reading the panel versus the agent reading the runtime, not read versus write.
- Writing or changing code with no running app involved → [re-frame2](re-frame2.md).
- A retrospective on a pair session → [re-frame2-pair-retro](re-frame2-pair-retro.md).
- The boot smoke-test at the end of a v1 migration → [re-frame-migration](re-frame-migration.md), which owns it as part of the migration.

## One-time setup

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

2. **Add the preload to the app's dev build.** The `re-frame2-pair.runtime`
    namespace ships in the skill's own `preload/` directory, not in the MCP
    server, and the skill cannot work without it. Three changes, dev builds only:

    - Put the `preload/` directory on the build's classpath, in whichever file
      owns the classpath: an activated alias's `:extra-paths` in `deps.edn` for
      a `:deps` app, `:source-paths` in `project.clj` for a `:lein` app, or
      `:source-paths` in `shadow-cljs.edn` only for a standalone shadow app.
      Under `:deps` and `:lein`, shadow ignores a `shadow-cljs.edn`
      `:source-paths` key and warns about it, so a preload put there never loads.
    - Add `re-frame2-pair.runtime` to the build's `:devtools :preloads` in
      `shadow-cljs.edn`.
    - Add two dev dependencies at the same revision as the app's core:
      `day8/re-frame2-schemas`, which the preload requires (without it the build
      fails on a missing `re-frame.schemas` namespace), and `day8/re-frame2-epoch`,
      with `re-frame.epoch` required at boot, which the epoch and time-travel
      tools need.

    The snippets for each build tool are in [`references/setup.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/setup.md).

## Server options

The server is `@day8/re-frame2-pair-mcp`, a stdio JSON-RPC server holding one persistent nREPL connection per session; it is the skill's only transport. Three launch flags, passed in the `args` of the `mcpServers` entry, decide what the agent may do:

| Flag | Default | Effect |
|---|---|---|
| `--allow-writes` | off | Enables `restore-epoch` and `replace-app-db`. Without it both refuse with `:rf.error/writes-disabled`. `dispatch` and `replay-epoch` work either way. |
| `--allow-sensitive-reads` | off | Lets a structured read lift `:rf/redacted` per call (`include-sensitive true`). |
| `--no-eval` | absent (eval on) | Disables `eval-cljs` and a `tail-build` probe; they refuse with `:rf.error/eval-cljs-disabled`. |

Pass boolean flags as bare arguments, for example `"--no-eval"`, not `"--no-eval=true"`. Unknown, retired or malformed launch arguments produce a warning on stderr and leave the setting at its default.

Two other flags steer discovery: `--port-file <absolute-path>` names the shadow nREPL port file; `--http-port <integer>` changes the shadow HTTP discovery endpoint from 9630. The latter is the shadow server's HTTP port, not the app tab's port. `SHADOW_CLJS_NREPL_PORT` supplies an nREPL port directly. An explicit port file takes precedence over that environment variable; otherwise the server searches workspace roots, probes shadow HTTP, then checks its working directory. Details are in the [transport reference](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/mcp-transport.md#install--configure-one-time).

These are separate controls: leaving `--allow-writes` off still permits real dispatch, replay and eval. `eval-cljs` can mutate the page and returns values without the redaction the structured reads apply, so the skill uses a typed tool whenever one fits and keeps `eval-cljs` for what no typed tool covers: epoch forensics, arbitrary-selector DOM reads, cross-referencing and recovery.

The [MCP tool reference](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/mcp-transport.md#mcp-tool-reference-args) lists every tool and its argument signature, including the size and privacy options supported per tool.

## Troubleshooting

Runtime refusals carry `{:ok? false :reason …}`; the skill reports the reason and its recovery hint. A missing server or broken connection may fail before a runtime reply is available. The ones you will meet first:

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

A successful discovery is ready for reads when `:freshness :liveness` is `:fresh`. With `:stale-build` (old code in the tab) or `:no-runtime` (no live runtime), the skill gives you the URL to reload; it cannot reload the browser itself. With `:unknown`, the build state could not be read. Follow the returned hint, which normally asks you to stop a stale shadow process with `npx shadow-cljs stop`, start one `watch`, reload and reconnect.

If epoch reads stay `[]` after the app has dispatched, check that `day8/re-frame2-epoch` was added and required in step 2 — `discover-app` does not check for it. Without it `dispatch-dry-run`, `restore-epoch` and `replay-epoch` refuse too, and `replace-app-db` fails with `:rf.error/epoch-artefact-missing`. The full reason list and recoveries are in [`references/errors.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/errors.md).

Fresco-specific evidence can return `:evidence-tier-unavailable` when the app uses another view layer or has not loaded `re-frame.fresco.tool`, or `:evidence-tier-inactive` outside a debug build. Those replies mean the evidence is unavailable, not that no views are mounted. DOM reads and the frame-level tools still apply to Reagent and UIx apps.

The [skill contract](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/SKILL.md) contains the full workflow and links to its reference notes.

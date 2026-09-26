# Setup — wiring the app for a pair session

Read this when `discover-app` reports `:runtime-loaded-but-preload-missing`, when the app fails to compile after adding the preload, when every epoch read comes back `[]`, or when the user asks how to set the skill up. The MCP server's own install and launch flags are in [`mcp-transport.md`](mcp-transport.md#install--configure-one-time).

The app's dev build needs three things the MCP server does not supply: the `re-frame2-pair.runtime` preload, the `day8/re-frame2-schemas` artefact the preload requires, and — for any epoch surface — `day8/re-frame2-epoch`.

## The preload

The helper namespace ships into the app via shadow-cljs's `:devtools :preloads` mechanism (separate from Xray's devtools preload). **It is required**; there is no per-session inject fallback. The skill installs from a repo checkout (not npm — see [`docs/LOCAL_DEV.md`](../docs/LOCAL_DEV.md)), so the preload comes off the linked skill's own `preload/` directory. Two halves: the directory joins the build's **classpath**, and `re-frame2-pair.runtime` is listed in the build's `:devtools :preloads`.

The `:preloads` half is always a `shadow-cljs.edn` line, whatever the app looks like:

```clojure
{:builds
 {:app {:devtools {:preloads [re-frame2-pair.runtime]}}}}  ;; always this
```

**The classpath half depends on who owns the classpath — read the top of the app's `shadow-cljs.edn` before you edit anything.** A `:deps` or `:lein` key there means shadow does *not* own the classpath, and in those modes it **ignores a `:source-paths` key entirely**, saying so on startup: `WARNING: The configured :source-paths in shadow-cljs.edn were ignored! When using :deps they must be configured in deps.edn`. Put the path in the wrong file and the namespace never compiles, so `discover-app` reports `:runtime-loaded-but-preload-missing` and the setup reads as broken rather than misfiled.

| Top-level key in `shadow-cljs.edn` | Classpath owner | Add the preload directory to |
|---|---|---|
| `:deps true` or `:deps {:aliases [...]}` | `deps.edn` | an activated alias's `:extra-paths` (or top-level `:paths`) |
| `:lein true` or `:lein {...}` | `project.clj` | lein's `:source-paths` (a dev profile if you keep one) |
| neither | `shadow-cljs.edn` | `:source-paths` |

**A `:deps` app** — the re-frame2 template is one (`{:deps {:aliases [:shadow :dev]}}`), so `:dev` is the alias to extend:

```clojure
;; deps.edn — the classpath half
{:aliases
 {:dev {:extra-paths ["<abs>/skills/re-frame2-pair/preload"]}}}  ;; add this
```

**A standalone shadow app** (no `:deps` / `:lein` key) — here `:source-paths` is the right home, and the whole change is two lines in the one file:

```clojure
{:source-paths ["src"
                "<abs>/skills/re-frame2-pair/preload"]  ;; add this (the linked skill's preload/)
 :builds
 {:app {:devtools {:preloads [re-frame2-pair.runtime]}}}}  ;; …and this
```

Either way the preload stays **dev-only**, and it is the `:preloads` entry that makes it so rather than where the path sits: shadow injects `:devtools :preloads` only in `:dev` mode, so a `release` build carries none of it even when the alias still puts the directory on the classpath.

The MCP server (`@day8/re-frame2-pair-mcp`) does NOT ship `preload/`, so wiring only the server leaves `discover-app` failing with `:runtime-loaded-but-preload-missing`. (Once the `@day8/re-frame2-pair` package is published to npm, `npm install -D @day8/re-frame2-pair` and point the classpath entry at `node_modules/@day8/re-frame2-pair/preload` instead — same branch, same files.)

## The artefacts the preload and epochs need

**`day8/re-frame2-schemas` alongside core, in the app's dev dependencies.** The preload directly requires `re-frame.schemas`; core does not bring that artefact. Use the same revision as the app's core dependency (or this clone's `implementation/schemas` as a `:local/root`). Without it compilation fails with a missing `re-frame.schemas` namespace before discovery can run. Add dependencies through the classpath owner above; the MCP server's dependencies do not enter the app's classpath.

**`day8/re-frame2-epoch` for every epoch surface**, with `re-frame.epoch` required at boot. Neither the preload nor the MCP server brings it and `discover-app` does not check for it, so a core-only app passes `discover-app`, then every epoch read (`watch-epochs`, `trace-window`, `(rf/epoch-history …)`) comes back `[]`, `dispatch-dry-run` refuses, and `restore-epoch` / `replay-epoch` refuse — see [errors.md §Tool-envelope refusals](errors.md#tool-envelope-refusals) for its one loud symptom, `:rf.error/epoch-artefact-missing`.

## Verify

Run `discover-app` — success is `{:ok? true :build-id ... :debug-enabled? true :frames [...]}` plus other health slots. A missing preload returns `{:ok? false :reason :runtime-loaded-but-preload-missing :hint "..."}`; report the hint verbatim. The other ladder rungs each mean a different fix — see [errors.md §discover-app preload-failure ladder](errors.md#discover-app-preload-failure-ladder).

## Registering the MCP server

**It takes a fresh session, not `--continue`.** Wiring `@day8/re-frame2-pair-mcp` into the agent host (`claude mcp add`, or editing `settings.json`) only takes effect in sessions that **start** afterwards — a `--continue`'d session won't surface the tools even though `claude mcp list` reports `Connected`. Exit and start a new session after registering, after changing its launch flags, and after rebuilding `out/server.js`. Build and config steps: [`mcp-transport.md` §Install / configure](mcp-transport.md#install--configure-one-time).

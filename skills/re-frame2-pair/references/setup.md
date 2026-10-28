# Setup — wiring the app for a pair session

Read this when `discover-app` reports `:runtime-loaded-but-preload-missing`, when the app fails to compile after adding the preload, when every epoch read comes back `[]`, or when the user asks how to set the skill up. The MCP server's own install and launch flags are in [`mcp-transport.md`](mcp-transport.md#install--configure-one-time).

The app's dev build needs two things the MCP server does not supply: the `re-frame2-pair.runtime` preload and the `day8/re-frame2-schemas` artefact the preload requires. Epoch history ships in core.

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
| `:deps true` or `:deps {:aliases [...]}` | `deps.edn` | an activated alias's `:extra-deps`, as a `:local/root` on the skill directory |
| `:lein true` or `:lein {...}` | `project.clj` | lein's `:source-paths` (a dev profile if you keep one) |
| neither | `shadow-cljs.edn` | `:source-paths` |

**A `:deps` app** — the re-frame2 template is one (`{:deps {:aliases [:shadow :dev]}}`), so `:dev` is the alias to extend. The skill directory ships a `deps.edn` that declares `preload/` as its path, so it joins as a `:local/root` dependency:

```clojure
;; deps.edn — the classpath half
{:aliases
 {:dev {:extra-deps {day8/re-frame2-pair {:local/root "<path-to-re-frame2>/skills/re-frame2-pair"}}}}}  ;; add this
```

Not `:extra-paths ["…/preload"]`: tools.deps prints `WARNING: Use of :paths external to the project has been deprecated` for every `:paths` or `:extra-paths` entry outside the project, relative and absolute alike, while a `:local/root` dependency's own `:paths` draw no warning.

**A standalone shadow app** (no `:deps` / `:lein` key) — here `:source-paths` is the right home, and the whole change is two lines in the one file:

```clojure
{:source-paths ["src"
                "<path-to-re-frame2>/skills/re-frame2-pair/preload"]  ;; add this (the linked skill's preload/)
 :builds
 {:app {:devtools {:preloads [re-frame2-pair.runtime]}}}}  ;; …and this
```

Either way the preload stays **dev-only**, and it is the `:preloads` entry that makes it so rather than where the path sits: shadow injects `:devtools :preloads` only in `:dev` mode, so a `release` build carries none of it even when the alias still puts the directory on the classpath.

**`<path-to-re-frame2>` is the re-frame2 clone the skill is linked from; in config you commit, write it relative to the file that holds it.** `deps.edn`, `project.clj` and `shadow-cljs.edn` each resolve a relative path against their own project directory, so a sibling checkout at the same relative location resolves unchanged on every machine and CI runner, while an absolute path names one machine's disk and fails on every other checkout. An absolute path suits only a local setup nobody else checks out. A v1→v2 migration's committed config must also pass re-frame-migration's [consumability done-gate](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/setup.md#the-consumability-done-gate).

The MCP server (`@day8/re-frame2-pair-mcp`) does NOT ship `preload/`, so wiring only the server leaves `discover-app` failing with `:runtime-loaded-but-preload-missing`. (Once the `@day8/re-frame2-pair` package is published to npm, `npm install -D @day8/re-frame2-pair` and point the same entry at the installed package instead — the `:local/root` at `node_modules/@day8/re-frame2-pair`, a source path at its `preload/` — same branch, same files.)

## What the preload and epochs need

**`day8/re-frame2-schemas` alongside core, in the app's dev dependencies.** The preload directly requires `re-frame.schemas`; core does not bring that artefact. Use the same revision as the app's core dependency (or this clone's `implementation/schemas` as a `:local/root`). Without it compilation fails with a missing `re-frame.schemas` namespace before discovery can run. Add dependencies through the classpath owner above; the MCP server's dependencies do not enter the app's classpath.

**Epoch history needs nothing more.** It ships in core, so every epoch read (`watch-epochs`, `trace-window`, `(rf.epoch/epoch-history …)`), `dispatch-dry-run`, `restore-epoch` and `replay-epoch` work in a dev build — at a positive epoch-history depth; see [errors.md §Tool-envelope refusals](errors.md#tool-envelope-refusals).

## Verify

Run `discover-app` — success is `{:ok? true :build-id ... :debug-enabled? true :frames [...]}` plus other health slots. A missing preload returns `{:ok? false :reason :runtime-loaded-but-preload-missing :hint "..."}`; report the hint verbatim. The other ladder rungs each mean a different fix — see [errors.md §discover-app preload-failure ladder](errors.md#discover-app-preload-failure-ladder).

## Registering the MCP server

**It takes a fresh session, not `--continue`.** Wiring `@day8/re-frame2-pair-mcp` into the agent host (`claude mcp add`, or editing `settings.json`) only takes effect in sessions that **start** afterwards — a `--continue`'d session won't surface the tools even though `claude mcp list` reports `Connected`. Exit and start a new session after registering, after changing its launch flags, and after rebuilding `out/server.js`. Build and config steps: [`mcp-transport.md` §Install / configure](mcp-transport.md#install--configure-one-time).

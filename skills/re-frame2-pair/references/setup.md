# Setup — wiring the app for a pair session

Read this when `discover-app` reports `:runtime-loaded-but-preload-missing`, when the app fails to compile after adding the preload, when every epoch read comes back `[]`, when a migration needs the Pair wiring proven before the app compiles, or when the user asks how to set the skill up. The MCP server's own install and launch flags are in [`mcp-transport.md`](mcp-transport.md#install--configure-one-time).

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

Not `:extra-paths ["…/preload"]`: tools.deps prints `WARNING: Use of :paths external to the project has been deprecated` for every `:paths` or `:extra-paths` entry outside the project, relative and absolute alike, while a `:local/root` dependency's own `:paths` draw no warning. It prints only when it computes the classpath — a cold `.cpcache`, a changed `deps.edn`, or `-Sforce` — so a watch that reuses the cached classpath prints nothing with the entry still in place, and a missing warning does not show the entry is gone.

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

## Check the wiring before the app compiles

In a fanned-out v1 → re-frame2 migration the app does not compile until the sweep lands ([re-frame-migration's `orchestrating-a-large-migration.md` §5](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/orchestrating-a-large-migration.md#5-the-all-or-nothing-single-compile-gate)), but the Pair wiring's failures are compile-time and independent of app code: the preload directory off the classpath or filed where its owner ignores it, `day8/re-frame2-schemas` missing, a namespace that does not resolve. Prove them before the sweep converges with a throwaway build whose only entry is the preload:

```clojure
;; shadow-cljs.edn — a second build beside the app's; any ignored output dir.
;; Copy the app build's :js-options, if it has any.
{:builds {:pair-check {:target     :browser
                       :output-dir "target/pair-check"
                       :modules    {:main {:entries [re-frame2-pair.runtime]}}}}}
```

```bash
npx shadow-cljs compile pair-check   # add -A :dev when shadow-cljs.edn's :deps does not activate the alias carrying the skill
```

The build needs no classpath entry of its own: `:deps`, `:lein` and `:source-paths` are project-wide in `shadow-cljs.edn`, so it reads the classpath half from wherever the app filed it. Its graph is the preload, re-frame2 core and schemas, and the React the app already installs, never the app's own namespaces, so it compiles green while the app's entry cannot; with the skill's `:local/root` missing it fails on `The required namespace "re-frame2-pair.runtime" is not available.` A green compile proves the wiring resolves, not the session, which [Verify](#verify) checks once the app runs. Remove the build once the post-sweep compile is green. The app's own build cannot stand in for it, because `--config-merge` appends to `:entries` rather than replacing them. An app adding Xray in the same unit lists both preloads in one check build's `:entries`, per re-frame-migration's [`xray-replaces-10x.md` §3a](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/xray-replaces-10x.md#3a-compile-the-devtools-wiring-mid-sweep).

## Verify

Run `discover-app` — success is `{:ok? true :build-id ... :debug-enabled? true :frames [...]}` plus other health slots. A missing preload returns `{:ok? false :reason :runtime-loaded-but-preload-missing :hint "..."}`; report the hint verbatim. The other ladder rungs each mean a different fix — see [errors.md §discover-app preload-failure ladder](errors.md#discover-app-preload-failure-ladder).

## Registering the MCP server

**It takes a fresh session, not `--continue`.** Wiring `@day8/re-frame2-pair-mcp` into the agent host (`claude mcp add`, or editing `.mcp.json`) only takes effect in sessions that **start** afterwards — a `--continue`'d session won't surface the tools even though `claude mcp list` reports `Connected`. Exit and start a new session after registering, after changing its launch flags, and after rebuilding `out/server.js`. Build and config steps: [`mcp-transport.md` §Install / configure](mcp-transport.md#install--configure-one-time).

# Install re-frame.ui and configure Shadow

One setting in `shadow-cljs.edn` wires `re-frame.ui` into your development build. It is not a tuning knob and it is not optional: `re-frame.ui` needs a build hook that harvests its whole-build registries — view digests, the root/plan indexes, the Root Descriptor index, and the compile-time custom-element declarations — from the compiled sources. Configure it once and the problem disappears for the life of the project. Omit it and the build ships no registries, so the app cannot resolve its own compiled views.

This page is the whole setup: the dependency, the one setting, why you cannot skip it, the Shadow versions we support, what the failure looks like, and a smoke test that proves it worked.

## Add the dependency

`re-frame.ui` is a separate artefact from the core library. An app that requires namespaces from both declares both — a direct `:require` deserves a direct dependency.

```clojure
;; deps.edn — resolved from a re-frame2 checkout beside your project
{:paths ["src"]
 :deps  {org.clojure/clojure       {:mvn/version "1.12.0"}
         org.clojure/clojurescript {:mvn/version "1.12.145"}

         day8/re-frame2            {:local/root "../re-frame2/implementation/core"}
         day8/re-frame2-ui         {:local/root "../re-frame2/implementation/ui"}}}
```

React arrives through npm rather than Clojure — `package.json` carries `react` and `react-dom`, and Shadow resolves them from `node_modules` at compile time.

!!! warning "`day8/re-frame2-ui` is donor-only and will not be published"

    There is no Clojars coordinate for `day8/re-frame2-ui`, and there will not be one — the release contract says so in terms ([Release process](../../release-process.md)). A monorepo checkout resolves it with `:local/root`, the way [`examples/ui/minimal-counter`](https://github.com/day8/re-frame2/tree/main/examples/ui/minimal-counter) does, and that is the only supported shape.

    This page exists to keep the **existing** donor views building. New view work belongs on [Freehand](../freehand/index.md), which is re-frame2's view layer and the substrate this guide's readers should be starting from.

## The setting

Put it at the **top level** of `shadow-cljs.edn` — not inside a build. `:build-defaults` applies the hook to every configured build, so one declaration covers the whole project.

<!-- rf2:shadow-ui-contract -->

```clojure
{:build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}}
```

That is the complete contract. Dropping it into an otherwise ordinary config looks like this:

```clojure
{:deps     {:aliases [:shadow]}
 :dev-http {8280 "resources/public"}

 :build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}

 :builds
 {:app
  {:target     :browser
   :output-dir "resources/public/js"
   :asset-path "/js"
   :modules    {:main {:init-fn my.app/run}}}}}
```

### What the build hook does

The hook is the build-lifecycle adapter between `re-frame.ui` and Shadow. It runs at two compile stages:

- **`:compile-prepare`** — it reads every UI source's literal `(ui/custom-element …)` declarations straight from source into a build-wide manifest, so property/attribute lowering is a pure function of the build's declarations rather than of compile order. When a declaration changed on a warm build it recompiles the affected views so none keeps a stale lowering.
- **`:compile-finish`** — it folds every compiled namespace's descriptor into the whole-build registries and carries the result as the accepted build snapshot. Shadow keeps that snapshot only if every later stage succeeds; a downstream failure discards the candidate and the next attempt seeds from the last accepted snapshot.

The descriptors live in the per-namespace analyzer data Shadow persists to its **disk cache** and restores on a cache hit. That is the whole point: because the registries are harvested from cache-durable data rather than rebuilt from macro-expansion side effects, **a warm daemon start reuses Shadow's disk cache** instead of re-compiling the entire UI-consuming namespace set. The hook is dev-time machinery — the view manifests and descriptor projections are all `goog.DEBUG`-guarded and vanish from advanced production output, so there are no production bytes and no production work.

!!! note "Why there is no `:cache-blockers` line"

    Earlier builds required a second setting, `:cache-blockers #{re-frame.ui}`, that disabled Shadow's disk cache for every UI-consuming namespace — an unavoidable compile-time tax on every start. It existed only because the registries were built by macro-expansion side effects, which a cached source never re-runs. Moving that truth into cache-durable analyzer descriptors removed the tax entirely (rf2-u53yy.1). If you are updating an older project, delete the `:cache-blockers` line — the hook alone is now correct, and faster.

## Supported Shadow versions

The hook is written against Shadow's build lifecycle: its compile stages and the analyzer data it persists to disk. The carrier and the registry harvest are **tested across shadow-cljs 3.4.0 through 3.4.11** (the analyzer round-trip is proven at both endpoints), so any version in that range is supported. The repository and the example pin a concrete version within the range:

<!-- rf2:shadow-ui-version -->

```clojure
;; deps.edn — shadow-cljs's JVM half
{:aliases
 {:shadow
  {:extra-deps {thheller/shadow-cljs {:mvn/version "3.4.10"}}}}}
```

The two halves of shadow-cljs must match, so pin the same version in `package.json`:

```json
{"devDependencies": {"shadow-cljs": "3.4.10"}}
```

!!! warning "Pre-alpha posture"

    Versions outside 3.4.0–3.4.11 are untested. They may fail outright at compile time if a future Shadow release changes how it persists analyzer data to disk. This is a deliberate pre-alpha stance: the supported range widens as newer Shadow releases are added to the test matrix. Until then, a version outside the range is not a bug report, it is an unsupported configuration.

## Troubleshooting

If you omit the hook, no build stage harvests the `re-frame.ui` registries, so the build publishes no accepted snapshot. There is no build-time error today — the failure surfaces at runtime, when the app tries to resolve a compiled view or mount a root and finds no registry to resolve it against, and the namespace throws on load. The fix is always the same: add `(re-frame.ui.compiler.build-hook/hook)` to `:build-defaults` `:build-hooks`.

## Prove it works

The smoke test is short, and worth running once when you set the project up:

1. `npx shadow-cljs watch app` — the build compiles cleanly with the hook configured.
2. Open the page. It renders.
3. **Stop the watch and start it again without touching a source file.** The second start is a warm daemon: Shadow reuses its disk cache for the UI-consuming namespaces (that reuse is exactly what removing the old cache blocker restored), and the harvested registries are restored intact. The app must render identically, and quickly.
4. Edit a view, save, and confirm hot reload replaces it without a full page load.

Working from a checkout of this repository? [`examples/ui/minimal-counter`](https://github.com/day8/re-frame2/tree/main/examples/ui/minimal-counter) is a complete runnable project carrying exactly the configuration above.

## The hook is host integration, not an API

`re-frame.ui.compiler.build-hook/hook` is a **required host-integration contract**: a versioned agreement between re-frame.ui and one specific build tool. You name it once in `shadow-cljs.edn` and never again — you don't call it, wrap it, compose it, or reach past it.

Everything else in that namespace is internal implementation and carries no stability promise; it is not part of the supported consumer configuration and application code should not reach for it. If you ever find yourself calling into the namespace from application code, the answer is somewhere else — the [API reference](../../api/README.md) carries the symbols meant for you.

# Install re-frame.ui and configure Shadow

Two settings in `shadow-cljs.edn` decide whether your development build tells the truth about itself. They are not tuning knobs and they are not optional: `re-frame.ui` builds its registries by macro expansion at compile time, so a build that skips expansion — or that never runs the reconciliation step — ships registries that are quietly incomplete. Configure both, once, and the problem disappears for the life of the project. Configure neither, or only one, and the build fails closed rather than handing you a plausible lie.

This page is the whole setup: the dependency, the two settings, why each one is load-bearing, the Shadow version we support, what the failures look like, and a smoke test that proves it worked.

## Add the dependency

`re-frame.ui` is a separate artefact from the core library. An app that requires namespaces from both declares both — a direct `:require` deserves a direct dependency.

```clojure
;; deps.edn
{:paths ["src"]
 :deps  {org.clojure/clojure       {:mvn/version "1.12.0"}
         org.clojure/clojurescript {:mvn/version "1.12.145"}

         day8/re-frame2            {:mvn/version "..."}
         day8/re-frame2-ui         {:mvn/version "..."}}}
```

React arrives through npm rather than Clojure — `package.json` carries `react` and `react-dom`, and Shadow resolves them from `node_modules` at compile time.

!!! note "Pre-publication coordinates"

    `day8/re-frame2` and `day8/re-frame2-ui` are not on Clojars yet. Until they are, projects inside a monorepo checkout resolve them with `:local/root`, the way [`examples/ui/minimal-counter`](https://github.com/day8/re-frame2/tree/main/examples/ui/minimal-counter) does. The two Shadow settings below are unaffected by which coordinate style you use.

## The two settings

Put both at the **top level** of `shadow-cljs.edn` — not inside a build. `:build-defaults` applies the hook to every configured build, and Shadow unions the top-level `:cache-blockers` into each build's options, so one declaration covers the whole project.

<!-- rf2:shadow-ui-contract -->

```clojure
{:cache-blockers #{re-frame.ui}
 :build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}}
```

That is the complete contract. Dropping it into an otherwise ordinary config looks like this:

```clojure
{:deps     {:aliases [:shadow]}
 :dev-http {8280 "resources/public"}

 :cache-blockers #{re-frame.ui}
 :build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}

 :builds
 {:app
  {:target     :browser
   :output-dir "resources/public/js"
   :asset-path "/js"
   :modules    {:main {:init-fn my.app/run}}}}}
```

### What the cache blocker does

`re-frame.ui`'s registries are populated as a *side effect* of macro expansion. Shadow's disk cache exists precisely to avoid re-expanding sources it believes are unchanged — which is normally a gift and here is a trap. On a warm daemon start, a source restored from cache contributes nothing to the registries, because the macro that would have registered its members never ran. The build then holds a registry that is missing members it has no way to know are missing.

`:cache-blockers #{re-frame.ui}` tells Shadow never to restore `re-frame.ui` or any source that requires it from disk cache. Registry contents become a pure function of the current build inputs instead of an accident of what the daemon happened to have lying around.

### What the build hook does

The hook is the build-lifecycle adapter. It runs at two stages and owns four jobs:

- **the build transaction** — macro expansion writes to scratch, and at compile-finish the hook reconciles scratch against the authoritative whole-build source graph. Shadow keeps the result only if every later stage succeeds; a downstream failure discards the candidate and the next attempt seeds from the last accepted snapshot.
- **version-zero retained-output invalidation** — the cache blocker stops sources being *loaded* from disk cache, but Shadow can still enter compile-prepare holding output maps retained by its wider build cache. On a daemon's first accepted pass the hook drops those maps for every blocked source, closing the last path by which un-expanded output could survive.
- **the cache-blocker check** — the hook validates that the blocker is configured before it opens scratch, and refuses the build if it is not.
- **digest-carrier projection** — the hook computes the whole-build digest once and patches it into exactly one fixed-width slot in the compiled output, giving the running app a cheap, accurate identity for the build it came from.

Both settings are dev-time machinery. The carrier, sentinel, digest literal, view manifests, and descriptor projections are all `goog.DEBUG`-guarded and vanish from advanced production output — no production bytes, no production work.

## Supported Shadow version

The hook is written against Shadow's 3.4.10 build lifecycle: its compile stages, its `:compiled-at` semantics, and its output projection. **3.4.10 is the sole supported version.**

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

    Other Shadow versions are untested and unsupported. They may fail outright at compile time, or — worse — publish a stale identity on a warm start. This is a deliberate pre-alpha stance rather than a permanent one: widening to a supported range waits on a multi-version test matrix. Until then, a version mismatch is not a bug report, it is an unsupported configuration.

## When it's misconfigured

Every failure mode here is fail-closed by design. A build that cannot prove its own identity refuses to publish one.

| What's missing | Where it fails | What you see |
|---|---|---|
| `:cache-blockers` (hook present) | build, at compile-prepare | `:re-frame.ui.compiler.build-hook/cache-blocker-missing` — *"re-frame.ui dev builds require `:cache-blockers #{re-frame.ui}`; refusing a plausible but incomplete warm-cache build"*. The `ex-data` carries `:configured` (what you actually set) and `:expected`. |
| the hook (blocker present or not) | runtime, on namespace load | `re-frame.ui build digest was not finalized. Configure (re-frame.ui.compiler.build-hook/hook) in Shadow :build-hooks and keep re-frame.ui in :cache-blockers.` |
| carrier drift — the hook ran but the output shape isn't what it requires | build, at compile-finish | `:re-frame.ui.compiler.build-hook/carrier-output-invalid`, e.g. *"re-frame.ui expected exactly one compiled digest carrier output"*. Usually an unsupported Shadow version or a build hook ordering problem. |

The middle row is the one worth internalising. **The cache-blocker check lives inside the hook**, so omitting the hook omits the check too — there is no build-time error to read. The failure surfaces instead when the app loads: the carrier still holds its unpatched sentinel, the digest never validates as a finalized `bd1-` value, and the namespace throws on load rather than letting the app limp along with a false identity. Both `ex-data` maps carry `:recovery :configure-ui-build-hook-and-cache-blocker` — one recovery, because there is one correct configuration.

## Prove it works

The smoke test is short, and worth running once when you set the project up:

1. `npx shadow-cljs watch app` — the build compiles with no `build-hook` error.
2. Open the page. It renders, and the browser console is free of the "build digest was not finalized" throw.
3. **Stop the watch and start it again without touching a source file.** This is the step that matters: the second start is a warm daemon, exactly the condition a missing cache blocker breaks. The app must render identically.
4. Edit a view, save, and confirm hot reload replaces it without a full page load.

Step 3 is the one people skip, and it is the only one that exercises the warm-cache path these settings exist to protect.

Working from a checkout of this repository? [`examples/ui/minimal-counter`](https://github.com/day8/re-frame2/tree/main/examples/ui/minimal-counter) is a complete runnable project carrying exactly the configuration above.

## The hook is a host seam, not an API

`re-frame.ui.compiler.build-hook/hook` is a **required host-integration seam**: a versioned contract between re-frame.ui and one specific build tool at one specific version. You name it once in `shadow-cljs.edn` and never again — you don't call it, wrap it, compose it, or reach past it.

Everything else in that namespace is internal implementation and carries no stability promise; it is not part of the supported consumer configuration and application code should not reach for it. If you ever find yourself calling into the namespace from application code, the answer is somewhere else — the [API reference](../../api/README.md) carries the surfaces meant for you.

For the normative statement of this contract, see `spec/004C-Roots-and-Mount.md` §2.1.1.

# Changelog

All notable changes to re-frame2 are recorded in this file.

The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Pre-1.0 releases carry a pre-release suffix (`.alpha`, `.beta`, `.rc`) on the way to a stable v1.0.0 line; from 1.0.0 on, releases follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## What a release publishes

re-frame2 is not one jar. A `v*` tag publishes **thirteen** Maven coordinates to Clojars, and every one of them ships at the same version: the repo-root [`VERSION`](VERSION) file is the single source, and the release workflow refuses to deploy anything at all if an artefact has drifted off it. Pin them as a set.

| Coordinate | Tier | What it gives you |
|---|---|---|
| `day8/re-frame2` | core | The framework itself — registry, event drain, effects, `dispatch`, `subscribe`, frames, the trace surface, and the substrate-adapter contract. Everything below depends on this, and on nothing else in the set. |
| `day8/re-frame2-reagent` | adapter | The Reagent adapter; the default browser substrate (Spec 006). |
| `day8/reagent-slim` | adapter | Reagent re-implemented slim for React 19. It replaces stock Reagent rather than depending on it (Spec 006). |
| `day8/re-frame2-uix` | adapter | The UIx adapter — hooks-shaped views over the same frames (Spec 006). |
| `day8/re-frame2-schemas` | feature | Malli-backed schema attachment for app-db, events and subscriptions (Spec 010). |
| `day8/re-frame2-machines` | feature | State machines (Spec 005). |
| `day8/re-frame2-routing` | feature | Routes, navigation effects and the routing framework subs (Spec 012). |
| `day8/re-frame2-flows` | feature | Flows — declarative derived state with a topology engine (Spec 013). |
| `day8/re-frame2-http` | feature | The `:rf.http/managed` effect family, with retry, decode and abort semantics (Spec 014). |
| `day8/re-frame2-ssr` | feature | Server-side rendering and hydration (Spec 011). |
| `day8/re-frame2-ssr-ring` | feature | The Ring host adapter for SSR — a server frame per request, torn down every time. The one artefact that depends on a second framework artefact (`day8/re-frame2-ssr`) as well as core (Spec 011). |
| `day8/re-frame2-resources` | feature | Declarative server state: the resource lifecycle, the work ledger, invalidation and GC (Spec 016). |
| `day8/re-frame2-epoch` | feature | Epoch history and time-travel — the substrate the pair tools read (Tool-Pair §Time-travel). |

Everything past core is optional, and that is the point of the split: core reaches each feature through late-bound hooks, so an app that never registers a route carries none of the routing machinery on its classpath.

Two things about that table are worth saying out loud.

**`day8/reagent-slim` deliberately breaks the `re-frame2-*` naming pattern**, because it is a replacement for `reagent/reagent` rather than a re-frame2 feature. There is a second wrinkle behind it: in the monorepo its adapter namespace is `re-frame.adapter.reagent-slim`, so that both adapters can sit on one classpath, but the *published* jar renames it to the canonical `re-frame.adapter.reagent`. A consumer's `(:require [re-frame.adapter.reagent :as ra])` is therefore the same line whichever adapter they pinned.

**The developer tools are not in the thirteen.** [`tools/`](tools/) is versioned in lockstep with the framework, but a `v*` tag publishes none of it. Five tool jars carry Clojars coordinates gated by that same lockstep script — `day8/re-frame2-xray`, `day8/re-frame2-story`, `day8/re-frame2-story-mcp`, `day8/re-frame2-machines-viz` and `day8/re-frame2-mcp-base` — and each ships on its own tag rather than the framework's: `xray-v*`, `story-v*` and `machines-viz-v*` each have a release workflow. Neither story-mcp nor mcp-base has a publish path yet: both declare `day8/de-dupe` as a runtime dependency via a git coordinate, which `clein pom` drops silently, and that library is not on Clojars — so a jar built today would ship a pom missing a runtime dep. Publishing, vendoring or dropping `de-dupe` is the open decision (rf2-2ii52); the lockstep gate now names the condition rather than passing over it. Two further tools sit outside that set entirely: the pair MCP server ships on npm as `@day8/re-frame2-pair-mcp`, and the app template ships as a git coordinate on a `template-v*` tag.

Spec changes are tracked under [`spec/`](spec/) and referenced from each entry below.

## [Unreleased]

### Added

### Changed

### Removed

### Fixed

### Spec

## [0.0.1.alpha] — unreleased

<!-- Maintainer note: the structural facts in this section — the artefact set, and
     what is deliberately absent from it — are gated by CI and must stay true. The
     narrative around them is written in the release commit, immediately before the
     tag. Do not let this section outlive the machinery it describes. -->

The first public release, and an alpha in the honest sense: the shapes described in [`spec/`](spec/) are implemented and tested end-to-end, but the public API is not frozen. A later alpha may move it.

### What ships

All thirteen coordinates in the table above, at `0.0.1.alpha`. Views are written against one of the three adapters — Reagent, reagent-slim, or UIx — and everything else is opt-in.

### Coming from re-frame v1

The dependency coordinate is now `day8/re-frame2`, and v1 and v2 cannot share a classpath. There is no compatibility shim and there will not be one: the coordinate change makes the redesign visible to your dependency tooling instead of hiding it behind a name that no longer means what it did.

`re-frame.core` is still the entry namespace and much of the v1 surface survives unchanged — `reg-sub`, `reg-fx`, `reg-cofx`, `dispatch`, `subscribe`, `dispatch-sync` and their handler signatures. The breaks that do exist are real, though: the three event registrars collapse into one `reg-event`, coeffects are declared rather than injected, interceptors are registered by reference, and a frame is always explicit — the runtime never infers one from absence. The full rule set, written to be applied by an agent, is [`migration/from-re-frame-v1/README.md`](migration/from-re-frame-v1/README.md).

### What this release does not promise

**No compiled-view substrate, under any coordinate.** The compiled-view work is not published in this release. There is no `day8/re-frame2-ui` on Clojars and there never will be — it is donor code being absorbed into re-frame2's native view layer, **Freehand**, which arrives in a later release under its own name ([EP-0036](docs/EP/EP-0036-the-freehand-view-substrate-programme.md)). Views in this release go through an adapter.

**No devtools panel on the UIx scaffold.** Xray mounts through the ratom-family substrates, so the Reagent scaffold wires it in and the UIx scaffold deliberately does not. An honest absence beats a panel that fails to mount.

**No scaffold for `day8/reagent-slim`.** The app template's substrate menu is `:reagent` and `:uix`, so a slim consumer starts from the Reagent scaffold and swaps the adapter coordinate.

[Unreleased]: https://github.com/day8/re-frame2/compare/v0.0.1.alpha...HEAD
[0.0.1.alpha]: https://github.com/day8/re-frame2/releases/tag/v0.0.1.alpha

# Story API reference

This is the human-facing API reference for Story — the per-frame Storybook for re-frame2 that turns *registered variants* into a navigable, queryable, snapshot-identified surface for design review, visual regression, recording, and pair-programming. The tutorial chapters one folder up walk a developer through the surface from a sitting position; this folder walks it from a standing one, organised by **what part of the contract you're touching** rather than by user journey. Each chapter opens with a paragraph on what the surface is *for* — the problem it solves, the shape of the contract — and only then drops into the function tables.

If you want the dense, single-page contract — every signature, every status keyword, every cross-reference — the [developer-internal spec](https://github.com/day8/re-frame2/blob/main/tools/story/spec/API.md) is still where that lives. This guide is the consumer extract: the surfaces a *story author*, a *host application*, or a *tool integrator* may legitimately reach for, with intuition notes attached. Chrome internals (the panel-host composer, the shell's URL-state engine, the keybinding installer pair, the theme-token namespaces consumed by chrome) are deliberately absent — those are documented for Story's maintainers, not for authors.

## What "canonical" means here

Every row in this reference is **canonical**: a documented, supported v1 surface that downstream authors, hosts, and tools may rely on. There are no "alpha" or "experimental" tiers in the chapters. If a row appears here, you can call it; if a row doesn't appear, it's either chrome internals (the panel-mount aggregators consumed only by Story's shell, the URL-state hydration helpers, the design-token maps) or a `post-v1` extension that hasn't shipped yet. Story-internal surfaces (the late-bind shims, the canonical-vocabulary installer, the panel-host's per-panel mount lifecycle) are explicitly out of scope — hosts that reach for them are reading internal seams that the public-by-default CLJS surface accidentally exposes.

Three audiences read these chapters. **Story authors** writing `reg-story` / `reg-variant` bodies who want to know which registration macros exist, which slots a variant body honours, and what `:play-script` steps mean — they reach for [Registration](registration.md) and [Play scripts](play-script.md). **Host application developers** wiring Story into a dev build who need to know the programmatic runtime (`run-variant`, `snapshot-identity`, `configure!`, `mount-shell!`) — they reach for [Runtime](runtime.md). And **tool integrators** building MCP servers, recording harnesses, or agent-driven test pipelines against Story's read-and-write seam — they reach for [MCP surface](mcp-surface.md) and the [Reference](reference.md) symbol table.

## How to read a row

Every row carries:

- a **signature** — the call shape, in Clojure form
- a **kind** — `M` (macro) or `Fn` (function); `Fx`, `Cofx`, or `Event` for the fx / cofx / canonical-assertion tables
- a **status** — `v1` (stable), `v1 (dev-only)` (elided in `:advanced` + `re-frame.story.config/enabled?=false`)
- an **intuition** — the one-line answer to "what's this for and when do I reach for it?"

Where a surface lives in more than one namespace (e.g. `add-marks` / `set-marks` are re-exported from `re-frame.story` for author-discoverability over the framework's `re-frame.core` definitions) the canonical home is the one named. The registration macros and their `*`-fn partners follow the same convention as `re-frame.core`'s own pair (`dispatch` / `dispatch*`, `reg-event-db` / `reg-event-db*`): the un-starred form is the macro, the `*` form is the underlying runtime fn for higher-order code, fixture loaders, MCP write paths, and hot-reload tooling that synthesises registrations.

## Where surfaces live

Story's user-facing surface splits across the facade plus a small set of sub-namespaces. The facade carries every user-callable surface; the sub-namespaces are public but called from chrome bootstrap, the shell, or the Causa preset — not from authored story bodies. This mirrors `re-frame.core`'s practice: the facade is the ergonomic surface, sub-namespace requires are a discoverability signal that the surface is chrome-internal even when public.

| Namespace | Use when |
|---|---|
| `re-frame.story` | The canonical require. Every registration macro + its `*`-fn partner, the run / reset / watch / destroy lifecycle, the registry query family, the assertion + recorder facades, the canonical vocabulary tables, `configure!`, the `*-id` Vars for built-in decorators, the shell-mount surface (CLJS-only), `variant-share-url`, and `add-marks` / `set-marks`. |
| `re-frame.story.recorder.play-export` | The rich DOM-capture-aware `:play-script` translator (`recording->play-script`, `render-play-script`). Sub-namespace require — the facade exposes only the simpler `gen-play-snippet` projection. |
| `re-frame.story.ui.causa-embed` | The Causa-RHS embed component (`causa-embed-panel`), `mount-fn-for` dispatch, `popout-full-shell!`. Called by the shell or the embed component, rarely by app code. |
| `re-frame.story.causa-preset` | The chrome / Causa bridge — `wire-cross-host!`, `causa-available?`, `propagate-project-root!`. |
| `re-frame.story.theme.*` | The design-token namespaces (`typography`, `colors`, `motion`, `depth`, `glyphs`). Consumed by third-party Story-panel authors; chrome consumes tokens, not raw literals. |
| `re-frame.story.ui.keybindings` | The chrome's keybinding registry + installer pair. Called by the shell's bootstrap. |
| `re-frame.story.ui.url-state` | The URL-state engine (`url-from-state`, `params-from-state`, `embed-flag-from-current-url`). Chrome-internal. |

The dependency direction is one-way: hosts depend on `re-frame.story`; tools depend on the registry-query family plus the `*`-suffix runtime helpers; Story's own chrome never depends on anything outside `tools/story/src/`.

## The chapters

The reference is divided into four topical chapters plus a closing symbol-table reference. Each is independent — you can land on any of them from a search result and get something useful without reading the others.

The four topical chapters are **[Registration](registration.md)** (the seven `reg-*` macros, their `*`-fn partners, the EDN-first variant contract, the inclusion-tag vocabulary, the `:rf.story/global-args` / `:rf.story/global-decorators` boot-time entry points), **[Play scripts](play-script.md)** (the `:play-script` grammar — every step, the canonical seven `:rf.assert/*` events, the record-don't-throw discipline, the recorder facade that authors a script from canvas interaction), **[Runtime](runtime.md)** (`run-variant` / `reset-variant` / `watch-variant` / `destroy-variant!`, the four-phase lifecycle, `snapshot-identity`, the registry-query family, `configure!` at boot, the shell-mount surface), and **[MCP surface](mcp-surface.md)** (the wire-elision boundary, the public read primitives consumed by `tools/story-mcp/`, the public write primitives behind the gated agent-write surface, the late-bind `reg-story-panel` contract).

The closing chapter is **[Reference](reference.md)** — the complete symbol table across `re-frame.story` and its sub-namespaces, organised for `Ctrl-F` use. If you want to know whether `gen-play-snippet` lives on the facade or in the recorder sub-namespace, this is the page.

## When to reach for the spec instead

The chapters here are organised for readers; the [normative spec](https://github.com/day8/re-frame2/blob/main/tools/story/spec/API.md) is organised for completeness. If you're looking for *every* row at once — including the chrome-internal surfaces, the resolved-decisions log, the migration notes for surfaces that were renamed mid-alpha — that's where you want to be. If you're writing a story body or wiring Story into a host build and want to know which surfaces *exist* in a given domain, you want a chapter here.

The normative spec docs (`001-Authoring.md`, `002-Runtime.md`, `004-Assertions.md`, `006-MCP-Surface.md`, etc. under [`tools/story/spec/`](https://github.com/day8/re-frame2/tree/main/tools/story/spec)) own the *why* — the design rationale, the alternatives considered, the dispositions. The chapters here cite those when they matter and stay quiet otherwise.

## See also

- [Story tutorial — Your first story](../01-first-story.md) — the chapter-1 walkthrough. Read this first if you've not yet authored a `reg-variant`.
- [Story tutorial — Recorder + Test Codegen](../03-recorder-codegen.md) — record a canvas interaction, get a `:play-script` body back.
- [Framework API — Story / variants / workspaces](../../api/15-story.md) — the framework-side reference slice of Story. Pairs with this folder; the two share the same surface, organised for different audiences.
- [Framework API — Instrumentation](../../api/11-instrumentation.md) — the trace bus the recorder reads, the source-coord stamping that drives Story's "open in editor" affordances.
- [Causa API reference](../../causa/api/index.md) — the sibling tool's API. Story embeds Causa in its right-hand pane; the two cross-link extensively.

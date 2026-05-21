# Causa API reference

This is the human-facing API reference for Causa — the dev-only panel that renders re-frame2's runtime as fourteen panels over a single observation surface. The tutorial chapters one folder up walk a developer through the surface from a sitting position; this folder walks it from a standing one, organised by **what part of the contract you're touching** rather than by user journey. Each chapter opens with a paragraph on what the surface is *for* — the problem it solves, the shape of the contract — and only then drops into the function tables.

If you want the dense, single-page contract — every signature, every status keyword, every cross-reference — the [developer-internal spec](https://github.com/day8/re-frame2/blob/main/tools/causa/spec/API.md) is still where that lives. This guide is the consumer extract: the surfaces a *host application* or a *tool integrator* may legitimately reach for, with intuition notes attached. Internal seams (the panel reg-views composed by Causa's own shell, the shell composer, the registry's per-key handlers) are deliberately absent — those are documented for Causa's maintainers, not for hosts.

## What "canonical" means here

Every row in this reference is **canonical**: a documented, supported v1 surface that downstream hosts and tools may rely on. There are no "alpha" or "experimental" tiers in the chapters. If a row appears here, you can call it; if a row doesn't appear, it's either internal plumbing or a `post-v1` extension that hasn't shipped yet. The Causa-internal surfaces (panel mount aggregators consumed only by Causa's shell, atom handles publishing the same state setters write to, the static-mode catalogues) are explicitly out of scope — hosts that reach for them are reading internal seams that the public-by-default CLJS surface accidentally exposes.

Three audiences read these chapters. **Host application developers** who want to wire Causa into their dev build and tune its boot-time posture — they reach for [Mount control](mount-control.md) and [Configuration keys](config-keys.md). **Tool integrators** building MCP servers, IDE plugins, or record-replay harnesses against Causa's read-and-mutate seam — they reach for [Runtime seam](runtime-seam.md). And **library authors and reference seekers** who want to scan the full surface in one pass — they reach for the [symbol table](reference.md).

## How to read a row

Every row carries:

- a **signature** — the call shape, in Clojure form
- a **status** — `v1` (stable), `v1 (dev-only)` (elided in `:advanced` + `goog.DEBUG=false`), `TBD-impl` (declared by the spec but the runtime path is a stub)
- an **intuition** — the one-line answer to "what's this for and when do I reach for it?"

Where a surface lives in more than one namespace (e.g. `configure!` is re-exported from `core` for boot-time ergonomics over the underlying `config` definition) the canonical home is the one named. Reach for the re-export when it's already on the require list you've imported for `open!`; reach for the original when you're scoping a require to a single concern.

## Where surfaces live

Causa's user-facing surface splits across six namespaces. Five are CLJS / CLJC source; one is a JavaScript-mirror global the preload installs. The split is principled — each namespace answers a distinct question, and `core` re-exports the high-traffic surfaces from the others so a single require covers the common boot path.

| Namespace | Use when |
|---|---|
| `day8.re-frame2-causa.core` | The canonical require. The mount facade (`open!` / `close!` / `toggle!` / `popout!` / `status`), the frame picker (`target-frame` / `set-target-frame!`), and the four highest-traffic config setters re-exported for boot-time convenience. |
| `day8.re-frame2-causa.config` | The full configuration surface — `configure!` plus every per-key setter. Reach here when you're flipping a knob the facade doesn't re-export, or when your boot code is already routing all config through `configure!`. |
| `day8.re-frame2-causa.keybinding` | The `attach!` / `detach!` lifecycle pair. Embed hosts (Story mounting Causa as a right-hand-side panel) reach here to take the `Ctrl+Shift+C` chord back. |
| `day8.re-frame2-causa.runtime` | The Causa ↔ tool read-and-mutate seam. The MCP server, an IDE plugin, a record-replay harness — anything driving a running re-frame2 app from out-of-process reads the trace bus and epoch history through these accessors. |
| `day8.re-frame2-causa.preload` | The dev-only side-effect bundle wired into shadow-cljs's `:devtools/preloads`. You don't *call* anything here directly; you list the namespace in your `:preloads` and the rest happens. |
| `window.day8.re_frame2_causa.*` | The browser-global JS mirror the preload installs. Reach from a devtools console, a JS host that doesn't `:require` CLJS namespaces, or a `puppeteer` automation script. |

The dependency direction is one-way: hosts depend on `core` (or on the wider surfaces directly); tools depend on `runtime`; Causa's own internals never depend on anything outside `tools/causa/src/`.

## The chapters

The reference is divided into four chapters. Each is independent — you can land on any of them from a search result and get something useful without reading the others.

The first three are topical — **[Mount control](mount-control.md)** (`init!`, `open!`, `close!`, `toggle!`, `popout!`, `status`, the frame picker, the JS browser-global mirror), **[Configuration keys](config-keys.md)** (`configure!`, every per-key setter, the editor preference, the inline-host CSS contract, the privacy gate), **[Runtime seam](runtime-seam.md)** (the seventeen accessors that compose the Causa ↔ tool read-and-mutate contract — trace buffer, epoch history, app-db diff, machine state, dispatch, restore-epoch).

The closing chapter is **[Reference](reference.md)** — the complete symbol table across all six namespaces, organised by namespace for `Ctrl-F` use. If you want to know whether `set-project-root!` is in `config` or `core`, this is the page.

## When to reach for the spec instead

The chapters here are organised for readers; the [normative spec](https://github.com/day8/re-frame2/blob/main/tools/causa/spec/API.md) is organised for completeness. If you're looking for *every* row at once — including the internal-but-visible surfaces, the resolved-decisions log, the migration notes for surfaces that were renamed mid-alpha — that's where you want to be. If you're writing a host integration and want to know which surfaces *exist* in a given domain, you want a chapter here.

The normative spec docs (`007-UX-IA.md`, `011-Launch-Modes.md`, `015-Configuration.md`, etc. under [`tools/causa/spec/`](https://github.com/day8/re-frame2/tree/main/tools/causa/spec)) own the *why* — the design rationale, the alternatives considered, the dispositions. The chapters here cite those when they matter and stay quiet otherwise.

## See also

- [Causa tutorial — Installation](../01-installation.md) — the five-minute, three-edits walk-through. Read this first if you've not yet wired Causa into a dev build.
- [Causa tutorial — Panel tour](../02-panel-tour.md) — what each panel is for, when you'd open it.
- [Framework API — Instrumentation](../../api/11-instrumentation.md) — the trace bus, the epoch buffer, the source-coord contract Causa reads. Causa adds no analogues; the framework owns the observation surface, Causa renders it.
- [Framework API — Lifecycle](../../api/13-lifecycle.md) — `rf/init!` runs before Causa attaches. The adapter must be installed before the auto-open path resolves the host.

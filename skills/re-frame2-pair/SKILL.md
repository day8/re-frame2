---
name: re-frame2-pair
description: >
  Pair-program against a **running** re-frame2 application via its
  Tool-Pair contract — attach to a live shadow-cljs nREPL, inspect a
  frame's app-db, dispatch events, hot-swap handlers, read the trace
  stream and per-frame epoch history, and time-travel with
  `restore-epoch`. Use when the user is operating on (or wants to
  operate on) a live runtime they have running locally. **Do not use**
  for static spec reading, architecture questions, design discussion,
  or ordinary source edits when no runtime is involved — those belong
  to `skills/re-frame2/` (authoring) or direct spec reading. See
  `references/vocabulary.md` for the surface glossary; vocabulary
  matches alone do not justify activation.
allowed-tools:
  # Pair-MCP — single persistent nREPL connection per session
  # (not yet on npm — build/run from a re-frame2 clone; see docs/LOCAL_DEV.md).
  # All 33 server tools are reachable; the body + references explain each surface.
  - mcp__re-frame2-pair__discover-app
  - mcp__re-frame2-pair__eval-cljs
  - mcp__re-frame2-pair__dispatch
  - mcp__re-frame2-pair__dispatch-dry-run
  - mcp__re-frame2-pair__trace-window
  - mcp__re-frame2-pair__watch-epochs
  - mcp__re-frame2-pair__tail-build
  - mcp__re-frame2-pair__snapshot
  - mcp__re-frame2-pair__get-path
  - mcp__re-frame2-pair__read-dom
  - mcp__re-frame2-pair__read-ui
  # Hicasso evidence door — mounted boundaries / read attribution / render-cause explain
  - mcp__re-frame2-pair__read-mounted-boundaries
  - mcp__re-frame2-pair__read-read-attribution
  - mcp__re-frame2-pair__explain-render
  - mcp__re-frame2-pair__record
  - mcp__re-frame2-pair__read-recording
  - mcp__re-frame2-pair__watch-until
  - mcp__re-frame2-pair__subscribe
  - mcp__re-frame2-pair__unsubscribe
  - mcp__re-frame2-pair__list-subscriptions
  - mcp__re-frame2-pair__list-streams
  # get-stream-controls: streaming resource-control diagnostic (in-process)
  - mcp__re-frame2-pair__get-stream-controls
  - mcp__re-frame2-pair__handler-meta
  - mcp__re-frame2-pair__list-handlers
  # orient: first-contact app-shape summary
  - mcp__re-frame2-pair__orient
  - mcp__re-frame2-pair__read-sub
  # describe-image: per-frame image-generation read
  - mcp__re-frame2-pair__describe-image
  # Operating-frame ops — see §Multi-frame model below
  - mcp__re-frame2-pair__get-operating-frame
  - mcp__re-frame2-pair__set-operating-frame
  - mcp__re-frame2-pair__reset-operating-frame
  # Named state-rewrite tools — --allow-writes-gated; see §Style guidance / ops.md §Time-travel
  - mcp__re-frame2-pair__restore-epoch
  - mcp__re-frame2-pair__replace-app-db
  - mcp__re-frame2-pair__get-re-frame2-pair-instructions
  # story-mcp — live-session tools (HYBRID split; authoring surface is on the re-frame2 skill)
  - mcp__re-frame2-story-mcp__run-variant
  - mcp__re-frame2-story-mcp__read-failures
  - mcp__re-frame2-story-mcp__snapshot-identity
  - mcp__re-frame2-story-mcp__read-a11y-violations
  - mcp__re-frame2-story-mcp__record-as-variant
  # story-mcp — read-only enumerations + agent-paste markdown
  - mcp__re-frame2-story-mcp__list-decorators
  - mcp__re-frame2-story-mcp__get-docs-markdown
  - mcp__re-frame2-story-mcp__explain-variant
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame2-pair

Pair-program on a **live, running re-frame2 application** (a browser tab behind `shadow-cljs watch`): help the developer understand, debug, and modify it by *operating on the live runtime*, not just reading source.

**Router skill.** Trigger-time guard rails are below; operational depth (op catalogue, recipes, error handling, hot-reload protocol, v1-migration notes) lives in `references/`, loaded on demand.

## The three primitives

Your agency runs through three coupled primitives, all part of re-frame2's [Tool-Pair contract](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md):

1. **The REPL** — a shadow-cljs nREPL session connected to the browser runtime; ClojureScript forms evaluate against the real app.
2. **The trace stream** — `(re-frame.trace.tooling/register-listener! id cb)` for live trace events; `(re-frame.trace.tooling/trace-buffer frame-id)` for the retain-N ring. (The facade form is the stream-parameterized `(rf/register-listener! :trace id cb)` — a stream-dispatching wrapper, not a same-signature re-export of the 2-arg tooling fn; `trace-buffer` is a **JVM-only** `rf/` alias — CLJS callers, including this shim's `cljs-eval`, must use `re-frame.trace.tooling/trace-buffer` directly or the form silently returns nil.) `trace-buffer` takes the frame-id **first** (`(trace-buffer frame-id)` cascade bundles, `(trace-buffer frame-id opts)` filtered) — a missing/destroyed frame-id silently returns `[]`, so never pass an opts map as the sole arg. This skill registers exactly *one* trace listener (id `:re-frame2-pair`) so tools coexist.
3. **The epoch history** — `(rf/epoch-history frame-id)` returns the per-frame ring of `:rf/epoch-record` values, each carrying the cascade's `:db-before`, `:db-after`, `:trace-events`, and the structured `:sub-runs` / `:renders` / `:effects` projections. `(rf/register-listener! :epoch id cb)` is the assembled-stream listener.

Every op eventually becomes a short ClojureScript form evaluated through the REPL, usually against a helper in the `re-frame2-pair.runtime` namespace the consumer app preloads (see §Setup).

---

## Setup — preload `re-frame2-pair.runtime`

The skill's helper namespace ships into the app via shadow-cljs's `:devtools :preloads` mechanism (separate from Xray's devtools preload and true-inline `[data-rf-xray-host]` panel contract). **The re-frame2-pair preload is required**; there is no per-session cljs-eval inject fallback. When `discover-app` can't find the marker it runs a diagnostic ladder; the normal missing-preload verdict is `:reason :runtime-loaded-but-preload-missing`. See [references/errors.md §discover-app preload-failure ladder](references/errors.md#discover-app-preload-failure-ladder) for the full reason set and each recovery.

The skill installs from a repo checkout (not npm — see the README's *Install* / [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md)), so the preload comes off the linked skill's own `preload/` directory. Point the consumer app's shadow-cljs `:source-paths` at it — the two-line `shadow-cljs.edn` change:

```clojure
{:source-paths ["src"
                "<abs>/skills/re-frame2-pair/preload"]  ;; add this (the linked skill's preload/)
 :builds
 {:app {:devtools {:preloads [re-frame2-pair.runtime]}}}}  ;; …and this
```

The `re-frame2-pair.runtime` namespace is **separate from the MCP server** (`@day8/re-frame2-pair-mcp`) — the MCP server does NOT ship `preload/`, so wiring only the server leaves `discover-app` failing with `:runtime-loaded-but-preload-missing`. (Once the `@day8/re-frame2-pair` package is published to npm, `npm install -D @day8/re-frame2-pair` and point `:source-paths` at `node_modules/@day8/re-frame2-pair/preload` instead — see [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md).)

Verify by running `discover-app` — success is `{:ok? true :build-id ... :debug-enabled? true :frames [...]}` plus other health slots. A missing preload returns `{:ok? false :reason :runtime-loaded-but-preload-missing :hint "..."}`; report the hint verbatim (fixable in seconds). The other ladder rungs (`:build-not-running` / `:no-runtime-connected` / `:nrepl-unreachable`, plus the blanket `:runtime-not-preloaded` degradation fallback) each mean a different fix — see [references/errors.md §discover-app preload-failure ladder](references/errors.md#discover-app-preload-failure-ladder).

**Registering the MCP server takes a fresh session, not `--continue`.** Wiring `@day8/re-frame2-pair-mcp` into the agent host (`claude mcp add`, or editing `settings.json`) only takes effect in sessions that **start** afterwards — a `--continue`'d session won't surface the tools even though `claude mcp list` reports `Connected`. Exit and start a new session after registering (also after rebuilding `out/server.js`).

**The v1→v2 migration boot-smoke needs only a browser-console read, not this MCP** — see [`skills/re-frame-migration/references/runtime-smoke-test.md`](../re-frame-migration/references/runtime-smoke-test.md). Reserve the full pair MCP for the *deeper* interactive debugging once the boot-smoke flags something.

---

## Cardinal rule — two modes of changing the app

- **REPL changes** (hot-swap a handler, evaluate a form, reset a frame's `app-db`) are **ephemeral** — survive hot-reloads of unaffected namespaces, lost on full page reload. Use for **probes, experiments, throwaway fixes**.
- **Source edits** (`Edit` / `Write`) are **permanent**. After any source edit you *must* run the hot-reload coordination protocol (`tail-build` with a probe) before dispatching or tracing, or you'll interact with pre-reload code and get misleading results.

Know which mode you're in and why. Strict source-edit protocol: [references/ops.md §Hot-reload coordination](references/ops.md#hot-reload-coordination).

---

## Connect first, every session

Before any other op, run `discover-app` (`mcp__re-frame2-pair__discover-app`). It locates the shadow-cljs nREPL port, connects, switches the session to `:cljs` mode for the running build, verifies re-frame2 is loaded with `interop/debug-enabled?` true, and confirms the `re-frame2-pair.runtime` preload landed (see §Setup).

**Connect once — the resolved build sticks.** A successful `discover-app` records the resolved build as the **session-sticky default** on the connection, for *every* resolution path — auto-selected single build, explicit `:build`, *and* `:port`. After one `discover-app`, call every other tool (`orient`, `read-dom`, `read-ui`, `snapshot`, `get-path`, …) with **no `build` arg** and it targets the resolved build — even with several builds running. Pass `build` again only to *switch* builds (an explicit `:build` on any later call wins and re-sticks). The sticky default resets on nREPL reconnect (a shadow restart); the next `discover-app` re-establishes it.

**Arg forms (don't guess).** Each arg has one expected shape:

| Arg | Form | Examples |
|---|---|---|
| `build` | bare build id; a leading colon is also tolerated. **Omit it when one build is running** — discover-app auto-selects it | `"examples/standard-epochs"` or `":examples/standard-epochs"` — identical |
| `port` | integer — the port from the browser URL | `8031` — see *Connecting from a URL* below |
| `frame` / `frames` | keyword **with** the colon | `":rf/default"`, `":app/main"`, `[":rf/default" ":rf/xray"]` |

**Single-build auto-selection / connecting from a URL.** You usually don't pass `build` — one running build auto-selects (several → it errors with the running-builds list, never a silent guess). When you know only the open tab's URL, pass `discover-app {port: 8031}`: the server reads the `:dev-http` map and resolves the build served on that port (no build maps to it → `:reason :port-unresolved`, no silent fallback; an explicit `:build` wins if you pass both). The port-resolved build **sticks** like every other path. discover-app reports every build/frame id as a **full keyword** in the canonical EDN — read the EDN text for the id exactly as you'd type it back into a `:frame` arg (the `:structuredContent` JSON view strips the colon, a documented lossy projection).

**Read the `:freshness` token before you trust a read.** Every `:ok? true` discover-app payload carries `:freshness {:liveness <verdict> :hint <str> ...}`. Pattern-match `:liveness` first: `:fresh` (read away), `:stale-build` (tab serving OLD code — the `:hint` names the URL to reload), `:no-runtime` (no live CLJS runtime — reads come back blank; `:hint` names the URL to reload then re-run discover-app), `:unknown` (build-worker state unreadable — **not a green light**; usual cause is a zombie shadow-cljs JVM, so the `:hint` steers to `npx shadow-cljs stop` → one `watch` → reload → re-discover).

You **cannot reload a browser yourself** — on a non-`:fresh` verdict, relay the `:freshness :hint` to the user as the single next step rather than firing reads that return blank.

On any failed precondition, discover-app returns structured edn (`{:ok? false :reason :runtime-loaded-but-preload-missing}` or another ladder rung: `:build-not-running` / `:no-runtime-connected` / `:nrepl-unreachable`). Report the failing check verbatim; do *not* guess workarounds. See [references/errors.md](references/errors.md) for reasons + recoveries.

**Port + build discovery are automatic.** On the first tool call the server discovers the live shadow-cljs nREPL itself and absorbs shadow restarts transparently; the resolved build sticks. Only reach for manual resolution on `:reason :port-unresolved` (read the app's `shadow-cljs.edn` `:dev-http` yourself to correlate the URL port to its build, then pass `discover-app {build: ...}`). The full discovery cascade, build-id resolution rules, and the `--port-file` / `SHADOW_CLJS_NREPL_PORT` overrides live in [references/mcp-transport.md](references/mcp-transport.md#install--configure-one-time) — rarely needed.

The nREPL session persists between turns. A full page refresh drops the runtime, but the preload re-installs it on the next bundle load — no manual reconnect. Every op checks the load-time marker (`js/globalThis.__re_frame2_pair_runtime`) first; if missing, the op refuses with the runtime-side `:runtime-not-preloaded` hint pointing here. (That per-op check is distinct from `discover-app`'s richer ladder, which on the same missing-marker condition reports the more precise `:runtime-loaded-but-preload-missing` — see [references/errors.md](references/errors.md#discover-app-preload-failure-ladder).)

For a refresher on the MCP surface before the first real op, optionally call `get-re-frame2-pair-instructions` — inline onboarding text (tool catalogue, EDN posture, tagged-mutation conventions, streaming-subscribe semantics, the wire pipeline) with no nREPL round-trip.

---

## Orient before you drill

**Your read order, every session: `discover-app` → `orient` → a targeted slice.**

After `discover-app` connects, your **first read is always `orient`** (`mcp__re-frame2-pair__orient {}`) — never a `snapshot`. `orient` returns a compact, one-round-trip app-shape summary: liveness, the app frames (reserved `:rf/*` tool frames excluded), each app frame's **top-level app-db keys only**, registry **counts**, and the navigable event / sub / fx / machine ids. Bounded by construction — it hands you the map without pulling any bulk, and omits the `:rf/xray` tool frame's contents.

Only **after** `orient` do you drill — into **slices**, never whole frames:

| You want | Use |
|---|---|
| one sub's current value | `read-sub {sub: "[:cart/total]"}` |
| one app-db path | `get-path {path: "[:cart :items]"}` |
| a bounded sub-tree | `snapshot {path: "[:cart]"}` (the `snapshot` tool defaults to `:summary` — top-level shape only; a `path` narrows it further) |

**Never read a whole frame to orient.** A `snapshot {path: "[]"}` (full, unsliced) or an `eval-cljs` form that derefs an entire frame's `app-db` can be enormous — **especially** the reserved `:rf/xray` **tool frame**: its app-db *is* Xray's entire working set (the epoch ring with each epoch's `:db-before` / `:db-after`, the trace buffer, diff projections, panel state) and grows without bound. A full read of it has overflowed past 100K tokens in one call. You essentially **never read the `:rf/xray` frame at all** — pair against the *app* frame (whatever id the app registered, e.g. `:app/main`, or `:rf/default` if the app chose that).

Mental model: **breadth-first shallow (`orient`) → depth-first narrow (`read-sub` / `get-path` / `snapshot {path}`)**. `snapshot` is a drill-in tool you hand a `path`, not the way you take in an app.

---

## Multi-frame model — set the operating frame

re-frame2 supports multiple, named frames (Spec 002). Most apps run one app frame (registered at the root — whatever id the app chose, e.g. `:app/main`); larger apps run several. **The public address is the frame (EP-0023)** — `image -> frame -> event stream`: target a **frame** id in a single process-local frame-id space, no realm/container coordinate. Every read/write op resolves an operating frame through a **four-tier cascade**: per-call `frame` arg (tier 1) → session pin (tier 2) → the sole registered **app frame** (tier 3) → nil/ambiguous (tier 4). Tier 3 is *unique resolution*, not synthesis. Framework-reserved `:rf/*` **tool frames** (Xray's `:rf/xray`, SSR/stories slots) are excluded from the ambiguity count, so a single-app session also running Xray auto-resolves to its one app frame. (`:rf/default`, if an app registers it, is an ordinary app frame — no framework privilege, still counted.) Full detail: [references/ops.md §Frames](references/ops.md#frames).

**Set the session pin with the dedicated operating-frame tools** — three MCP tools surface tier 2 directly, no eval round-trip: `set-operating-frame {frame: ":foo"}` (pin — the escape from the tier-4 refusal; validates the id, returns the `{:frames :selected :operating}` triple), `reset-operating-frame {}` (clear), `get-operating-frame {}` (read; `:operating nil` means ambiguous). These three are NOT subject to the `:ambiguous-frame` refusal — they *resolve* it.

**`subscribe` is the exception** — it has **no `frame` arg**, so the operating-frame pin does *not* scope a streaming subscription; scope a stream with `filter {:frame ":foo"}` instead.

When the operating frame is ambiguous (two-plus **app** frames, no pin), **every other frame-targeted op refuses with `:ambiguous-frame`** rather than guess — a write into the wrong frame is unrecoverable without `restore-epoch`. Reads refuse too (the validated read helpers return `:reason :ambiguous-frame` rather than silently reading `:rf/default`). Mirrors Spec 002 §Frame presets / lifecycle convention.

---

## Where the depth lives — loading map

Read the leaf matching the task. Most references are ≤250 lines; the two catalogue leaves (`ops.md`, `recipes.md`) run longer.

| Task shape | Reference |
|---|---|
| Pick a structured op (read, validated sub-read, write, dry-run, trace, DOM/UI read, signal recording, blocking wait, hot-reload, time-travel) | [references/ops.md](references/ops.md) |
| Orient on an unfamiliar app in one call, or read a single sub's validated value | `orient` / `read-sub` — see [references/ops.md §Read](references/ops.md#read) (arg signatures in [references/mcp-transport.md §MCP tool reference](references/mcp-transport.md#mcp-tool-reference-args)) |
| Find out **which registrations / image a specific frame actually runs** (the multi-image / Story case where the same id resolves differently per frame), with per-frame provenance | `describe-image` — see [references/ops.md §Frames](references/ops.md#frames) |
| Read what's actually on screen (rendered content + producing view entity) | `read-ui` / `read-dom` — see [references/ops.md §View → rendered content](references/ops.md#view--rendered-content--producing-entity-uiread) |
| Ask which Hicasso boundaries are **mounted right now** (`read-mounted-boundaries`), or **which boundaries read a subscription** you can name (`read-read-attribution`). A boundary here has no view name — the runtime mints no identity, so it is keyed by its read set. Reagent and UIx apps answer `:evidence-tier-unavailable` on all three; every other op in this skill works on them unchanged | see [references/ops.md §Hicasso evidence](references/ops.md#hicasso-evidence--mounted-boundaries-read-attribution-render-cause) |
| Ask **which of a boundary's reads moved** when it rendered and you didn't expect it to (`explain-render`) — the proven half, beside retained runs offered as leads | see [references/ops.md §Hicasso evidence](references/ops.md#hicasso-evidence--mounted-boundaries-read-attribution-render-cause) |
| Simulate an event's consequence WITHOUT committing it (no fx fire) | `dispatch-dry-run` — see [references/recipes.md §"What would this event do?"](references/recipes.md#what-would-this-event-do-dry-run) |
| Record signals while the human interacts, or block until a condition lands | `record` / `read-recording` / `watch-until` — see [references/ops.md §Signal recording](references/ops.md#signal-recording--blocking-waits) |
| Run a named procedure the user asked for ("why didn't my view update?", post-mortem, experiment loop, etc.) | [references/recipes.md](references/recipes.md) |
| Drive a Story variant from a re-frame2-pair session — the variant *is* a frame; variant-id ↔ frame-id identity, per-variant isolation, the four-phase lifecycle, gotchas, discovery | [references/variant-as-frame.md](references/variant-as-frame.md) |
| Open a push-mode subscription on the trace or epoch bus (topics, filters, termination) | [references/streaming-subscriptions.md](references/streaming-subscriptions.md) |
| Diagnose a stream that was **refused / went quiet / terminated** — server-side caps, slot leaks, rate-limit/abuse pressure (answers even when the runtime is down) | `get-stream-controls` / `list-streams` — see [references/streaming-subscriptions.md §Diagnostics](references/streaming-subscriptions.md#diagnostics--what-streams-are-currently-registered) |
| Decode a deduped wire payload (`:rf.mcp/dedup-table`) or pick the right size-conscious arg (`max-tokens`, `path`, `mode`, `dedup`, `elision`, `limit`/`cursor`, `cache`, `max-buffered-*`) | [references/wire-size-budget.md](references/wire-size-budget.md) |
| Translate a structured `{:ok? false :reason ...}` to plain English; suggest the recovery | [references/errors.md](references/errors.md) |
| Edit source, then wait for the browser to pick up the new code | [references/ops.md §Hot-reload coordination](references/ops.md#hot-reload-coordination) |
| Map a v1 (`re-frame-pair`) surface to its v2 equivalent (or know it has none) | [references/ops.md §Dropped from v1](references/ops.md#dropped-from-v1-re-frame-pair--surfaces-with-no-v2-equivalent) |
| Install/configure the persistent-connection MCP server | [references/mcp-transport.md](references/mcp-transport.md) |
| Drive the **running browser app's own** Story registry via `eval-cljs` (`re-frame.story/ids` / `variants-of` / `run-variant`, await the run-result) — the pair is the live-browser Story host, story-mcp cannot see the browser registry; OR use the story-mcp tools during a live session (the five live-session tools `run-variant` / `read-failures` / `snapshot-identity` / `read-a11y-violations` / `record-as-variant` plus the three read-only enumerations `list-decorators` / `explain-variant` / `get-docs-markdown`; composition with watch-epochs and dispatch-from-pair) | [references/stories.md](references/stories.md) |

Load at most two references for a single task. Wanting three means the request spans concerns and should be broken up.

---

## Style guidance

- **Read before you write — `orient` first, then drill into slices.** Your first read each session is `orient`, never a whole-frame read; ground a hypothesis by drilling into a *slice* (`read-sub` / `get-path` / `snapshot {path}`). See [Orient before you drill](#orient-before-you-drill) for the rule and the `:rf/xray` overflow.
- **Prefer a structured op when one FITS the gesture; `eval-cljs` is the workhorse for the long tail, not a last resort.** Dedicated tools (`orient`, `read-sub`, `get-path`, `read-ui`, `dispatch`, …) give a validated, elided, single-round-trip answer for the gesture they own; epoch forensics, arbitrary-selector DOM reads, cross-referencing, and recovery are first-class `eval-cljs` work — see [recipes.md §eval-cljs is the workhorse](references/recipes.md#eval-cljs-is-the-workhorse). (Privacy caveat: raw `eval-cljs` is un-elided — see the privacy bullet below.)
- **Hypothesis-test through `dispatch-dry-run`, not a live `dispatch` or throwaway `eval-cljs` handler.** For *"test this handler"* / *"try a dispatch"* / *"what would happen if…"*, reach for `dispatch-dry-run` first: it runs the whole cascade (reducer, interceptors, schema, machine transitions, sub-runs, renders) **without committing** — no fx execute, the framework auto-rolls-back app-db. It is the safe primitive for this gesture; `dispatch` and raw `eval-cljs` are not. Dry-run targets a **registered** event, so to probe a *throwaway* handler: register it with `eval-cljs` (`(rf/reg-event :exp/probe …)`), then `dispatch-dry-run {event: "[:exp/probe …]"}` — the rollback still applies, leaving no trace. See [recipes.md §Experiment loop](references/recipes.md#experiment-loop) and [§"What would this event do?"](references/recipes.md#what-would-this-event-do-dry-run).
- **WARNING — a `reg-event` handler returning `{:db <bare-map>}` REPLACES app-db wholesale** (does NOT merge), so a throwaway probe driven by a **live** `dispatch` nukes the entire frame's app-db, unrecoverable without `restore-epoch`. Two safe paths: prefer `dispatch-dry-run` (rolls back), or if you must commit return `{:db (assoc db …)}` from the live `db` cofx — never a bare literal map. Full treatment: [recipes.md §Experiment loop](references/recipes.md#experiment-loop).
- **Keep it in re-frame2's vocabulary.** Dispatch, reg-event, reg-sub, reg-machine, frame, epoch — speak the app's language. Avoid `reset!` of a frame's app-db except when surgically needed, and say so when you do.
- **On ambiguous/failing tool resolution, read the project config (`shadow-cljs.edn`, `deps.edn`) rather than bouncing off the tool.** "Which build is this port?" / "where does this artefact live?" usually sits in the source config in plain sight.
- **Experiment, don't speculate.** When an answer isn't obvious, probe at the REPL against live data.
- **Validate before proposing.** Compose a hot-swap/suggestion form and run it against current state first.
- **Narrow detail as you go.** Summaries first; drill into a specific epoch, diff, sub-run, or render entry on request.
- **Always resolve UI references to source first.** When the user mentions a button, view, panel, or "the thing I clicked", run `dom/source-at` *before* speculating. Reporting `re-com/button at app/cart/view.cljs:84` grounds the conversation in a file the user can open; *"probably the Save button somewhere in the profile view"* doesn't.
- **Surface restore limits.** Before any time-travel experiment, walk the cascade's effects and tell the user which effects already fired and cannot be reversed.
- **Use the assembled epoch stream by default; reach for the raw trace stream when you need detail the projection drops.** `:sub-runs`, `:renders`, `:effects` are the routing surface; `:trace-events` is the escape hatch for detail the projection omits (e.g. per-interceptor timing, or the raw fx `:args` the `:effects` projection redacts off-box).
- **One trace listener per skill.** This skill registers one listener (`:re-frame2-pair`) and one epoch listener (`:re-frame2-pair-epoch`). Multi-tool coexistence is the default — don't worry about other listeners; per Spec 009 §Listener ordering, ordering is not contract.
- **Structured MCP reads/streams elide by default; raw `eval-cljs` does NOT.** Per [Spec 009 §Privacy](../../spec/009-Instrumentation.md), the structured read/stream tools (`snapshot`, `get-path`, `read-sub`, `trace-window`, `watch-epochs`, `subscribe`, `dispatch-dry-run`, and the recorders) force wire-boundary elision server-side under the `--allow-sensitive-reads` gate (**OFF by default**) — sensitive → `:rf/redacted`, large → `:rf.size/large-elided`, epoch records additionally route through `projected-record`. So structured reads/streams are safe to fire by default. But `eval-cljs` is **default-ON** (governed only by `--no-eval`) and **NOT governed by this gate** — it returns the form's value **without running the elision walker**, so a raw `(re-frame2-pair.runtime/snapshot)` / `(rf/epoch-history …)` can ship verbatim app-db / trace / epoch state — secrets included. This is the **raw-eval carve-out**: don't reach for raw eval to read a sensitive path / sub / trace / epoch when a structured tool fits — reserve it for forensics, cross-referencing, and recovery. Full mechanism in [references/vocabulary.md §Privacy posture](references/vocabulary.md#privacy-posture--sensitive-and-the-raw-eval-carve-out).
- **Route named writes through the dedicated, gated tools.** Time-travel undo and state injection have dedicated tools — `restore-epoch` and `replace-app-db` — both allow-listed and the **canonical path** (validate, append a synthetic undoable epoch, `tap>`, return a `:cascade-summary`). Both are gated by the server's `--allow-writes` flag (default **OFF**); the **server's gate, not the allow-list, is the write boundary**. Raw eval of a write form (`(rf/restore-epoch! …)` / `app-db-reset!`) is the **backstop, not the default** — reach for the dedicated tool first. Full detail: [references/ops.md §Time-travel](references/ops.md#time-travel-epoch-restore).

---

## When to also open Xray

A re-frame2-pair session and a running Xray panel are **complementary** surfaces over the same trace bus + epoch history. re-frame2-pair owns the *driving* (dispatch, hot-swap, restore-epoch); Xray owns the *seeing* (visual reading of what just happened across its Dynamic event-spine tabs and Static registry-browse tabs — `skills/re-frame2-xray/` is the canonical source for Xray facts). Reach for Xray alongside re-frame2-pair when:

| re-frame2-pair just did | Open Xray to … |
|---|---|
| Rewound to an earlier epoch via `restore-epoch` | Scrub the bottom-rail time-travel scrubber to inspect adjacent epochs visually; pin slices via the app-db tab's inline diff. |
| Dispatched into a cascade you don't fully understand | The Epoch tab lands on the latest cascade and shows the dispatch-id tree. |
| Hot-swapped a sub or reg-event handler | Watch the Epoch SUBSCRIPTIONS step recompute the invalidation chain (`:cart/total` ← `:cart/items` ← `[:cart :items]`). |
| Stepped into a machine transition | Open the Machine tab / Machine Inspector for the state-chart view with transition history. |
| Triggered a schema violation | Schema violations surface inline on the Epoch tab (and as an L2 pink-wash) with recovery mode + source coord. |

Authoring-side guidance for getting Xray mounted (preload, layout host, suppress-auto-open knob, popout, host-CSS-variable resize) lives at [`skills/re-frame2/references/tooling/xray.md`](../re-frame2/references/tooling/xray.md). When advising a user mid-session on which panel to look at, route them there for the mount-side detail; this skill stays focused on the *driving* side.

---

*Deep-dive content (full API reference, EP design rationale, spec corpus, migration guide) routes through [`SKILL-REDIRECT.md`](../../SKILL-REDIRECT.md) at the repo root. Full skill-disambiguation matrix (when to use which skill) lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*

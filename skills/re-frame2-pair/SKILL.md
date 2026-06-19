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
  # MCP transport — single persistent nREPL connection per session.
  # The canonical path; install via `npm install -g @day8/re-frame2-pair-mcp`.
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
  - mcp__re-frame2-pair__record
  - mcp__re-frame2-pair__read-recording
  - mcp__re-frame2-pair__watch-until
  - mcp__re-frame2-pair__subscribe
  - mcp__re-frame2-pair__unsubscribe
  - mcp__re-frame2-pair__list-subscriptions
  - mcp__re-frame2-pair__list-streams
  # Server-side streaming resource-control diagnostic — read-only
  # report of the server's effective caps, active stream slots vs limit,
  # token-bucket pressure, and abuse-window count. Reads the server's
  # resource-controls atoms IN-PROCESS (no nREPL round-trip), so it answers
  # even when the runtime is down. Complements `list-streams` (runtime tap
  # registry) for "why was my stream denied / why is it quiet?".
  - mcp__re-frame2-pair__get-stream-controls
  - mcp__re-frame2-pair__handler-meta
  - mcp__re-frame2-pair__list-handlers
  # Read-only orientation ops — one-call
  # app-shape summary for first contact on an unfamiliar app, and a
  # validated read of a single subscription's current value.
  - mcp__re-frame2-pair__orient
  - mcp__re-frame2-pair__read-sub
  # Frame image-generation read (EP-0023 Use-Case 7) — one
  # round-trip "what behaviour does THIS frame run, and where did each
  # piece come from?" over the public `rf/frame-generation` read. Reports
  # the composed image ids, registrar kinds, declared capability requires,
  # and per-kind selected-registration counts; `:include-ns true` adds the
  # per-(kind, id) provenance coordinate. Read-only; frame resolution
  # mirrors every read op (operating frame default, `:ambiguous-frame` on
  # an unselected multi-frame session).
  - mcp__re-frame2-pair__describe-image
  # Operating-frame ops — read/set/reset the session's
  # default operating frame for the multi-frame model (Spec 002). See
  # the "Multi-frame model — set the operating frame" section below.
  - mcp__re-frame2-pair__get-operating-frame
  - mcp__re-frame2-pair__set-operating-frame
  - mcp__re-frame2-pair__reset-operating-frame
  # Named state-rewrite tools — the CANONICAL path for
  # time-travel undo + state injection. Both are gated behind the
  # server's default-OFF `--allow-writes` flag: against a gate-OFF
  # server (the published default) they refuse with
  # `:reason :rf.error/writes-disabled` without touching the runtime, so
  # allow-listing them is safe — the SERVER, not this list, is the write
  # authority boundary. Prefer these structured/audited tools over a raw
  # `eval-cljs` `(rf/restore-epoch! …)` / `app-db-reset!` whenever the
  # gesture is a named write. See §Time-travel writes below.
  - mcp__re-frame2-pair__restore-epoch
  - mcp__re-frame2-pair__replace-app-db
  - mcp__re-frame2-pair__get-re-frame2-pair-instructions
  # story-mcp — live-session tools only (HYBRID split). The
  # authoring-side surface (register-variant, get-variant,
  # preview-variant, list-stories, …) is allow-listed by the
  # `re-frame2` skill. These entries cover running a variant against
  # the live runtime, inspecting failures, and capturing the cascade
  # back into a `:play-script` snippet from within a pair-session.
  - mcp__re-frame2-story-mcp__run-variant
  - mcp__re-frame2-story-mcp__read-failures
  - mcp__re-frame2-story-mcp__snapshot-identity
  - mcp__re-frame2-story-mcp__run-a11y
  - mcp__re-frame2-story-mcp__record-as-variant
  # Read-only enumerations + agent-paste markdown surface a pair
  # session may reach into when navigating an unfamiliar Story
  # registry — peer surfaces to the authoring skill's allow-list.
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

You are pair-programming with a developer on a **live, running re-frame2 application**. The app is running in a browser tab behind `shadow-cljs watch`. Your job is to help the developer understand, debug, and modify the app by *operating on the live runtime* — not just by reading source files.

This is a **router skill**. The trigger-time guard rails live below; the operational depth (op catalogue, recipes, error handling, hot-reload protocol, v1-migration notes) lives in `references/` and is loaded on demand.

## The three primitives

Your agency runs through three coupled primitives, all part of re-frame2's own [Tool-Pair contract](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md):

1. **The REPL** — a shadow-cljs nREPL session connected to the browser runtime, where ClojureScript forms evaluate against the real app.
2. **The trace stream** — `(re-frame.trace.tooling/register-listener! id cb)` for live trace events; `(re-frame.trace.tooling/trace-buffer opts)` for the retain-N ring of recent events. (`register-listener!` is re-exported on `rf/`, but `trace-buffer` is a **JVM-only** alias on `rf/` — CLJS callers, including this shim's `cljs-eval`, must reach for `re-frame.trace.tooling/trace-buffer` directly or the form silently returns nil.) This skill registers exactly *one* trace listener (under id `:re-frame2-pair`) so multiple tools can coexist.
3. **The epoch history** — `(rf/epoch-history frame-id)` returns the per-frame ring of `:rf/epoch-record` values, each carrying the cascade's `:db-before`, `:db-after`, `:trace-events`, and the structured `:sub-runs` / `:renders` / `:effects` projections. `(rf/register-epoch-listener! id cb)` is the assembled-stream listener.

Every operation eventually becomes a short ClojureScript form evaluated through the REPL, usually against a helper function in the `re-frame2-pair.runtime` namespace that the consumer app preloads (see §Setup below).

---

## Setup — preload `re-frame2-pair.runtime`

The skill's helper namespace ships into the app via shadow-cljs's standard `:devtools :preloads` mechanism. This is re-frame2-pair's runtime-helper requirement, separate from Xray's devtools preload and true-inline `[data-rf-xray-host]` panel contract. **The re-frame2-pair preload is required**; there is no per-session cljs-eval inject fallback. When `discover-app` can't find the marker it runs a diagnostic ladder; the normal missing-preload verdict is `:reason :runtime-loaded-but-preload-missing` (a runtime is live but the marker is absent). See [references/errors.md §discover-app preload-failure ladder](references/errors.md#discover-app-preload-failure-ladder) for the full set of reasons and the recovery each calls for.

First install the preload package as a dev dependency in the consumer app:

```bash
npm install -D @day8/re-frame2-pair
```

This is a **separate package from the MCP server** (`@day8/re-frame2-pair-mcp`, installed globally) — the MCP server does NOT ship the `preload/` directory, so installing only the server leaves `discover-app` failing with `:runtime-loaded-but-preload-missing`. Then the two-line `shadow-cljs.edn` change:

```clojure
{:source-paths ["src"
                "node_modules/@day8/re-frame2-pair/preload"]  ;; add this
 :builds
 {:app {:devtools {:preloads [re-frame2-pair.runtime]}}}}     ;; …and this
```

Where the runtime lives:

- **Source of truth**: `skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs` in this repo. The path layout matches the CLJS namespace `re-frame2-pair.runtime` so shadow-cljs picks it up on `:source-paths`.
- **npm consumers**: the `@day8/re-frame2-pair` package (`npm install -D` above) ships the `preload/` directory; the source-path entry above points there.
- **Local-dev / linked checkouts**: substitute the absolute path to `skills/re-frame2-pair/preload/` for the `node_modules/...` entry (no package install needed — see [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md)).

Verification — run `discover-app` (the MCP tool `mcp__re-frame2-pair__discover-app`). The success result is the runtime health map merged with `:ok? true` and `:build-id`, e.g. `:ok? true :debug-enabled? true :frames [:rf/default] :coord-annotation-enabled? true :build-id :app` (plus `:session-id`, `:selected-frame`, `:operating-frame` and the other health slots). If the preload is missing — a runtime is live but the marker is absent — you get back the **normal missing-preload** verdict:

```edn
{:ok? false :reason :runtime-loaded-but-preload-missing
 :build :app
 :hint "re-frame2-pair.runtime is not loaded into this build. Add the preload entry to your shadow-cljs.edn: ..."}
```

Report the hint to the user verbatim — they can fix it in seconds without re-cloning anything. (`:runtime-loaded-but-preload-missing` is one rung of the discover-app preload-failure ladder; a `:build-not-running` / `:no-runtime-connected` / `:nrepl-unreachable` verdict means a *different* fix — see [references/errors.md §discover-app preload-failure ladder](references/errors.md#discover-app-preload-failure-ladder). The blanket `:runtime-not-preloaded` is the degradation fallback the server returns only when the ladder itself errors mid-diagnosis.)

**Registering the MCP server takes a fresh session, not `--continue`.** Wiring `@day8/re-frame2-pair-mcp` into the agent host (`claude mcp add`, or editing `settings.json`) only takes effect in sessions that **start** afterwards. A session resumed with `claude --continue` will not surface the re-frame2-pair tools even though `claude mcp list` reports the server `Connected` — exit and start a new session (`claude --resume`, or a plain new `claude`) after registering the server. This is agent-host behaviour, not a server flag; the same applies after pulling MCP source changes and rebuilding `out/server.js`.

---

## Cardinal rule — two modes of changing the app

- **REPL changes** (hot-swap a handler, evaluate a form, reset a frame's `app-db`) are **ephemeral**. They survive hot-reloads of unaffected namespaces, but are lost on full page reload. Use them for **probes, experiments, and throwaway fixes**.
- **Source edits** (using `Edit` / `Write`) are **permanent**. After any source edit, you *must* run the hot-reload coordination protocol (`tail-build` with a probe) before dispatching or tracing. Otherwise you'll interact with the pre-reload code and get misleading results.

Know which mode you're in and why. For the strict source-edit protocol, see [references/ops.md §Hot-reload coordination](references/ops.md#hot-reload-coordination).

---

## Connect first, every session

Before any other op, run:

```
discover-app
```

(MCP tool call — formal name `mcp__re-frame2-pair__discover-app`.)

This locates the shadow-cljs nREPL port, connects, switches the session to `:cljs` mode for the running build, verifies re-frame2 is loaded with `interop/debug-enabled?` true, and confirms the `re-frame2-pair.runtime` namespace was loaded by the consumer's `:devtools :preloads` (see §Setup above).

**Connect once — the resolved build sticks.** A successful `discover-app` records the build it resolved as the **session-sticky default** on the connection, for *every* resolution path — auto-selected single build, explicit `:build`, *and* `:port`. So after one `discover-app` you call every other tool (`orient`, `read-dom`, `read-ui`, `snapshot`, `get-path`, …) with **no `build` arg** and it targets the resolved build — even with several builds running. You only pass `build` again to *switch* builds (an explicit `:build` on any later call wins and re-sticks as the new default). The sticky default resets on an nREPL reconnect (a shadow restart), where the next `discover-app` re-establishes it.

**Arg forms (don't guess).** Each arg has one expected shape:

| Arg | Form | Examples |
|---|---|---|
| `build` | bare build id; a leading colon is also tolerated. **Omit it when one build is running** — discover-app auto-selects it | `"examples/step-deck"` or `":examples/step-deck"` — identical |
| `port` | integer — the port from the browser URL | `8031` — see *Connecting from a URL* below |
| `frame` / `frames` | keyword **with** the colon | `":rf/default"`, `":step-deck"`, `[":rf/default" ":rf/xray"]` |

**Single-build auto-selection.** You usually don't need to pass `build` at all. When exactly one shadow-cljs build is running, a no-arg `discover-app` selects it and reports `:auto-selected-build` plus an explanatory `:note`. When several run, it errors with the running-builds list so you can pick — it never silently guesses.

**Connecting from a URL.** When you only know the browser URL of the open tab (e.g. `http://localhost:8031/counter`) and several builds are running, pass the port instead of hunting for the build id: `discover-app {port: 8031}`. discover-app reads the shadow-cljs `:dev-http` map and resolves the build whose `:output-dir` is served on that port (8031 → `:examples/step-deck` in this repo) — no manual grep of `shadow-cljs.edn`. A `:port` that matches no build returns `{:ok? false :reason :port-unresolved}` rather than silently falling back. An explicit `:build` arg wins over `:port` if you pass both. The port-resolved build **sticks** as the session default just like the other paths — every following no-`build` tool call lands on it.

**Output representation.** discover-app reports every build/frame id (`:build-id`, `:frames`, `:running-builds`) as a **full keyword** (`:rf/default`, `:examples/step-deck`) in the canonical EDN result, and its `:note` / `:hint` prose uses the same colon form — one representation throughout. (Hosts that surface the `:structuredContent` JSON view will show the colon stripped — `"rf/default"` — that's the documented lossy JSON projection; read the EDN text for the id exactly as you'd type it back into a `:frame` arg.)

**Read the `:freshness` token before you trust a read.** Every `:ok? true` discover-app payload carries `:freshness {:liveness <verdict> :hint <str> ...}`. Pattern-match `:liveness` first:

- `:fresh` — runtime connected, heartbeat recent, build not recompiled since load. Read away.
- `:stale-build` — the tab is serving OLD code (a recompile landed the runtime never picked up). The `:hint` tells the user the exact URL to reload.
- `:no-runtime` — no live CLJS runtime is connected (WS dropped, no tab open) — reads will come back blank. The `:hint` names the exact `http://localhost:<port>` to reload, then "re-run discover-app".
- `:unknown` — the JVM-side build-worker state couldn't be read even after a retry, so stale-build detection is blind (reads may still work). **This is not a green light.** The dominant cause is **multiple / zombie shadow-cljs JVMs** — a `Ctrl-C`'d watch that didn't free its ports left an orphan JVM, and the socket reached a runtime whose build worker is in a *different* JVM. The `:hint` is actionable: relay it to the user, who should run `npx shadow-cljs stop` (frees the orphan ports a bare `Ctrl-C` leaves held), start exactly **one** `shadow-cljs watch <build>`, reload the tab, and re-run discover-app to confirm `:fresh`.

You **cannot reload a browser yourself** — so when the verdict is non-`:fresh`, relay the `:freshness :hint` to the user as the single next step rather than firing reads that will return blank.

If any precondition fails, the script returns a structured edn error like `{:ok? false :reason :runtime-loaded-but-preload-missing}` (the normal missing-preload verdict) or another rung of the preload-failure ladder (`:build-not-running` / `:no-runtime-connected` / `:nrepl-unreachable`). Report the failing check to the user verbatim; do *not* guess at workarounds. See [references/errors.md](references/errors.md) for the common error reasons and the recovery each one calls for.

**Fallback — resolve the build yourself only if `discover-app {port}` can't.** The normal path is `discover-app {port: <url-port>}` (above): the MCP server reads the `:dev-http` map for you and the resolved build **sticks** as the session default. Reach for manual resolution **only** when that returns `:reason :port-unresolved` (the port maps to no build) or an ambiguous-build diagnostic. Then: re-frame2-pair targets a shadow-cljs app, so the build behind a browser URL is discoverable from the application `shadow-cljs.edn` — locate that file (find it; do not assume a path), read its `:dev-http` config, and correlate the URL port to the build it serves (a top-level `{port [roots]}` map whose roots include a build `:output-dir`, or a per-build `:dev-http {:port N}`). Pass that build id explicitly with `discover-app {build: ...}`; it then sticks like any other resolution path.

> **Example (illustrative — this repo only; do NOT transcribe these values into a session against another app).** A `shadow-cljs.edn` with a top-level `:dev-http {8033 ["out/examples/machine-epochs"]}` entry serves port `8033` from `out/examples/machine-epochs`, which is the `:output-dir` of the `:examples/machine-epochs` build — so a tab at `http://localhost:8033` is the `:examples/machine-epochs` build, and you'd pass `build :examples/machine-epochs`. Every app's ports, roots, and build ids differ — read *its* config, never reuse these.

**Port discovery is automatic — you don't configure it.** On the first tool call the MCP server discovers the live shadow-cljs nREPL on its own (a cascade ending in shadow's `roots/list` / HTTP probe; multiple running builds trigger a host prompt so you pick one), and absorbs shadow restarts transparently. When discovery misses (no running shadow, non-default port, exotic setup), the operator passes `--port-file <abs>` or sets `SHADOW_CLJS_NREPL_PORT`. The full cascade and the overrides are in [references/mcp-transport.md §Install / configure](references/mcp-transport.md#install--configure-one-time) — you rarely need any of it.

Between user turns, the nREPL session persists. A full page refresh in the browser drops the runtime, but the preload re-installs it on the next bundle load — no manual reconnect step is needed. Every op checks the load-time marker (`js/globalThis.__re_frame2_pair_runtime`) before proceeding; if it's missing the op refuses with the runtime-side `:runtime-not-preloaded` hint pointing at this §Setup. (That per-op marker check is distinct from `discover-app`'s richer preload-failure ladder, which on the same missing-marker condition reports the more precise `:runtime-loaded-but-preload-missing` — see [references/errors.md](references/errors.md#discover-app-preload-failure-ladder).)

If you want a refresher on the MCP surface before the first real op, optionally call `get-re-frame2-pair-instructions` (formal name `mcp__re-frame2-pair__get-re-frame2-pair-instructions`) — it returns inline onboarding text (tool catalogue, EDN posture, tagged-mutation conventions, streaming-subscribe semantics, the wire pipeline) with no nREPL round-trip.

---

## Orient before you drill

**Your read order, every session: `discover-app` → `orient` → a targeted slice.**

After `discover-app` connects, your **first read is always `orient`** (`mcp__re-frame2-pair__orient {}`) — never a `snapshot`. `orient` returns a compact, one-round-trip app-shape summary: liveness, the app frames (reserved `:rf/*` tool frames excluded), each app frame's **top-level app-db keys only**, registry **counts**, and the navigable event / sub / fx / machine ids. It is bounded by construction — it hands you the map without pulling any bulk, and it deliberately omits the `:rf/xray` tool frame's contents.

Only **after** `orient` do you drill — and you drill into **slices**, never whole frames:

| You want | Use |
|---|---|
| one sub's current value | `read-sub {sub: "[:cart/total]"}` |
| one app-db path | `get-path {path: "[:cart :items]"}` |
| a bounded sub-tree | `snapshot {path: "[:cart]"}` (the `snapshot` tool defaults to `:summary` — top-level shape only; a `path` narrows it further) |

**Never read a whole frame to orient.** A `snapshot {path: "[]"}` (full, unsliced) or an `eval-cljs` form that derefs an entire frame's `app-db` can be enormous. This is **especially** true of the reserved `:rf/xray` **tool frame**: its app-db *is* Xray's entire working set — the epoch ring it displays (each epoch carrying `:db-before` / `:db-after`), the trace buffer, diff projections, panel state — and it grows without bound. A full read of it has overflowed past 100K tokens in a single call. You essentially **never read the `:rf/xray` frame at all** — you pair against the *app* frame (whatever id the app registered, e.g. `:app/main` — or `:rf/default` if the app explicitly chose that id).

The mental model: **breadth-first shallow (`orient`) → depth-first narrow (`read-sub` / `get-path` / `snapshot {path}`)**. `snapshot` is a drill-in tool you hand a `path` — not the way you take in an app.

---

## Multi-frame model — set the operating frame

re-frame2 supports multiple, named frames (Spec 002). Most apps run with one app frame (registered explicitly at the root — whatever id the app chose, e.g. `:app/main`); larger apps may run several (a stories build, an SSR slot, a sub-app island). Every read/write op resolves an operating frame through a four-tier cascade: explicit per-call `frame` arg (tier 1) → session pin (tier 2) → the sole registered **app frame** (tier 3) → nil/ambiguous (tier 4). (This interactive discovery cascade — with an operator present to disambiguate — is **kept** under EP-0002 R5; tier 3 is *unique resolution*, not synthesis. It is a distinct layer from the strict embedded core, which never resolves from absence.) You override the operating frame per-call with the `frame` arg, e.g. `{frame: ":foo"}` (see [references/ops.md §Frames](references/ops.md#frames)).

**The public address is the frame (EP-0023).** The public model is `image -> frame -> event stream`: you target a **frame** id, full stop. The behaviour a frame resolves against is its **resolved image generation** (the image selects the registration set). There is **no public realm pin** — the realm is an *internal* installation/container substrate (the registrar/adapter/capability owner), not part of the public address, and almost every session pairs against one app in the single default container where the boundary is a no-op. Advanced — multi-installation: when a process runs more than one container (multi-tenant, parallel apps, a legacy adapter beside a new one), the operating *frame* resolves **within the operating container**, so the same frame id in two containers is a legal non-collision; pair surfaces the container boundary on `orient` / `discover-app` / `frames-list` as **labeled-internal** structure (`:realms` / `:operating-realm` / `:frame-realms {frame-id → container-id}`), not as the central model. A single-installation app sees one container and never spells it.

**Reserved tool frames don't count toward ambiguity.** An Xray-instrumented app carries a `:rf/xray` **tool frame** alongside its one app frame — and stories / SSR builds add their own `:rf/*` frames. These framework-reserved `:rf/*` frames are devtool surfaces, not the app you are pairing against, so tier 3 / 4 exclude them: a single-app session that *also* runs Xray resolves to its one app frame **automatically — no `frames/select` needed**. Only two-plus genuine *app* frames are ambiguous. (`:rf/default`, if an app registers it, is an **ordinary** app frame — under EP-0002 it has no framework privilege and is no longer auto-created — but it shares the `:rf/*` root and is counted as an app frame, not a tool frame.) A `get-operating-frame` read surfaces both `:frames` (all registered) and `:app-frames` (tool frames removed).

**Set the session pin with the dedicated operating-frame tools.** Three MCP tools surface tier 2 directly — no eval round-trip:

- `set-operating-frame {frame: ":foo"}` — pin the session's operating frame (the public address is the frame id). Validates that `:foo` is registered (`:no-such-frame` with the registered list otherwise), then returns the `{:frames :selected :operating}` triple. This is **the escape from the tier-4 `:ambiguous-frame` refusal** — pin once and every later **frame-arg-bearing** op (`dispatch`, `snapshot`, `get-path`, `read-sub`, `list-subscriptions`, `trace-window`, `watch-epochs`, …) resolves to it. **One exception: `subscribe`** has no `frame` arg, so the pin does *not* scope a streaming subscription — scope a stream to a frame with `filter {:frame ":foo"}` instead (until/unless `subscribe` gains a top-level `frame` arg).
- `reset-operating-frame {}` — clear the frame pin (it also clears the runtime's internal installation-container pin); ops fall back to tier 3 / 4 again.
- `get-operating-frame {}` — the read op. Returns the same triple; `:operating nil` means ambiguous (two-plus frames, no pin), so the next un-`frame`'d op will refuse.

These three are NOT subject to the `:ambiguous-frame` refusal themselves — they are how you *resolve* the ambiguity. (The eval-based `frames-list` / `select-frame!` / `frames-meta` runtime helpers in [references/ops.md §Frames](references/ops.md#frames) are the lower-level surface the tools wrap; reach for them only for `frames/meta`, which has no dedicated tool.)

When the operating frame is ambiguous (two-plus **app** frames registered and the session hasn't pinned one), **every other frame-targeted op refuses with `:ambiguous-frame`** rather than guess — a write that lands in the wrong frame is unrecoverable without `restore-epoch`. Reads refuse too: the validated read helpers (`subs-sample`, `read-sub!`, `sub-cache-info`, …) return `:reason :ambiguous-frame` rather than silently reading `:rf/default`, and the dedicated `snapshot` / `get-path` tools refuse like the writes do. This mirrors the Spec 002 §Frame presets / lifecycle convention.

---

## Where the depth lives — loading map

Read the leaf that matches the task. Each reference file is ≤250 lines.

| Task shape | Reference |
|---|---|
| Pick a structured op (read, validated sub-read, write, dry-run, trace, DOM/UI read, signal recording, blocking wait, hot-reload, time-travel) | [references/ops.md](references/ops.md) |
| Orient on an unfamiliar app in one call, or read a single sub's validated value | `orient` / `read-sub` — see [references/ops.md §Read](references/ops.md#read) (and the MCP arg shapes in [references/mcp-transport.md §Orientation + discovery](references/mcp-transport.md#orientation--discovery)) |
| Read what's actually on screen (rendered content + producing view entity) | `read-ui` / `read-dom` — see [references/ops.md §View → rendered content](references/ops.md#view--rendered-content--producing-entity-uiread) |
| Simulate an event's consequence WITHOUT committing it (no fx fire) | `dispatch-dry-run` — see [references/recipes.md §"What would this event do?"](references/recipes.md#what-would-this-event-do-dry-run) |
| Record signals while the human interacts, or block until a condition lands | `record` / `read-recording` / `watch-until` — see [references/ops.md §Signal recording](references/ops.md#signal-recording--blocking-waits) |
| Run a named procedure the user asked for ("why didn't my view update?", post-mortem, experiment loop, etc.) | [references/recipes.md](references/recipes.md) |
| Drive a Story variant from a re-frame2-pair session — the variant *is* a frame; variant-id ↔ frame-id identity, per-variant isolation, the four-phase lifecycle, gotchas, discovery | [references/variant-as-frame.md](references/variant-as-frame.md) |
| Open a push-mode subscription on the trace or epoch bus (topics, filters, termination) | [references/streaming-subscriptions.md](references/streaming-subscriptions.md) |
| Decode a deduped wire payload (`:rf.mcp/dedup-table`) or pick the right size-conscious arg (`max-tokens`, `path`, `mode`, `dedup`, `elision`, `limit`/`cursor`, `cache`, `max-buffered-*`) | [references/wire-size-budget.md](references/wire-size-budget.md) |
| Translate a structured `{:ok? false :reason ...}` to plain English; suggest the recovery | [references/errors.md](references/errors.md) |
| Edit source, then wait for the browser to pick up the new code | [references/ops.md §Hot-reload coordination](references/ops.md#hot-reload-coordination) |
| Map a v1 (`re-frame-pair`) surface to its v2 equivalent (or know that it was dropped) | [references/ops.md §Dropped from v1](references/ops.md#dropped-from-v1-re-frame-pair--surfaces-with-no-v2-equivalent) |
| Install/configure the persistent-connection MCP server | [references/mcp-transport.md](references/mcp-transport.md) |
| Use story-mcp tools (`run-variant`, `read-failures`, `snapshot-identity`, `run-a11y`, `record-as-variant`) during a live re-frame2-pair session — composition with watch-epochs and dispatch-from-pair | [references/stories.md](references/stories.md) |

Load at most two references for a single task. If you find yourself wanting three, the request likely spans concerns and should be broken up.

---

## Style guidance

- **Read before you write — `orient` first, then drill into slices.** Your first read each session is `orient` (the bounded app-shape summary), *not* `snapshot`. To ground a hypothesis, drill into a *slice* — `read-sub` / `get-path` / `snapshot {path: ...}` / `trace/last-epoch` — never a whole-frame read, and **never** the `:rf/xray` tool frame wholesale (it is Xray's entire working set; it overflows). See [Orient before you drill](#orient-before-you-drill).
- **Prefer a structured op when one FITS the gesture; `eval-cljs` is the workhorse for the long tail, not a last resort.** Dedicated tools (`orient`, `read-sub`, `get-path`, `read-ui`, `dispatch`, …) give a validated, elided, single-round-trip answer for the gesture they own — reach for them first when they fit. But epoch forensics (`find-where` / `cascade-of` / `epoch-diff`), arbitrary-selector DOM reads, cross-referencing reads, and **recovery** (re-running a blank/errored structured read as `eval-cljs` to confirm the runtime answers) are first-class `eval-cljs` work. **Privacy carve-out:** `eval-cljs` returns its value un-elided and is *not* gated by `--allow-sensitive-reads` (it is default-ON, gated only by `--no-eval`), so a raw eval of `app-db` / `sub-cache` / `trace-buffer` / `epoch-history` can leak secrets to the AI host. When the read is of a privacy-sensitive app-db path, sub value, trace event, or epoch payload AND a structured elided tool fits, use the structured tool — drop to raw eval for that data only on explicit user/operator request. See the privacy bullet below.
- **Hypothesis-test through `dispatch-dry-run`, not a live `dispatch` or a throwaway `eval-cljs` handler.** When the gesture is *"test this handler"* / *"try a dispatch"* / *"what would happen if…"*, reach for `dispatch-dry-run` first: it runs the whole cascade (reducer, interceptors, schema, machine transitions, sub-runs, renders) **without committing** — no fx execute and the framework auto-rolls-back the app-db. It is the safe primitive for exactly this gesture; `dispatch` and raw `eval-cljs` are not. Dry-run targets a **registered** event, so to probe a *throwaway* handler: register it with `eval-cljs` (`(rf/reg-event :exp/probe …)`), then `dispatch-dry-run {event: "[:exp/probe …]"}` — the rollback still applies, so the experiment leaves no trace. See [recipes.md §Experiment loop](references/recipes.md#experiment-loop) and [§"What would this event do?"](references/recipes.md#what-would-this-event-do-dry-run).
- **WARNING — a `reg-event` handler that returns `{:db …}` REPLACES app-db wholesale.** This is a foot-gun when you write a throwaway test handler. `{:db <map>}` is the canonical "the new app-db is exactly this map" effect — it does NOT merge. A probe handler returning `{:db {:exp/x 1}}` and then a **live** `dispatch` (or an `eval-cljs` that drives the real cascade) will nuke the *entire* frame's **app-db** (the user partition) — every boot-seeded app slice — leaving only `{:exp/x 1}`, unrecoverable without `restore-epoch`. (Runtime-db — machine snapshots, routing, elision — is a *separate* partition a `:db` return cannot touch, so those survive; and a `:db` value carrying a retired `:rf/runtime` key is a hard error, `:rf.error/legacy-runtime-root`.) Two safe paths: (1) **prefer `dispatch-dry-run`** of the probe (above) — it rolls back, so even a `{:db …}` handler leaves the live db untouched; (2) if you must commit, **preserve the existing db** — return `{:db (assoc db :exp/x 1)}` (the handler's `db` cofx is the live app-db), never a bare literal map. Never test a `{:db …}` handler with a live `dispatch` against a frame whose state you can't afford to lose.
- **Keep it in re-frame2's vocabulary.** Dispatch, reg-event, reg-sub, reg-machine, frame, epoch — speak the same language the app speaks. Avoid `reset!` of a frame's app-db except when surgically needed, and say so when you do.
- **When a tool resolution is ambiguous or failing, read the project config (`shadow-cljs.edn`, `deps.edn`) rather than bouncing off the tool.** The answer to "which build is this port?" / "where does this artefact live?" is usually sitting in the source config in plain sight.
- **Experiment, don't speculate.** When an answer isn't obvious, probe at the REPL against live data.
- **Validate before proposing.** When a hot-swap or suggestion is on the table, compose the form and run it against current state first.
- **Narrow detail as you go.** Summaries first; drill into a specific epoch, diff, sub-run, or render entry when the user asks.
- **Always resolve UI references to source first.** When the user mentions a button, view, panel, or "the thing I clicked", run `dom/source-at` *before* speculating about behaviour. Reporting `re-com/button at app/cart/view.cljs:84` grounds the conversation in a file the user can open; reporting *"probably the Save button somewhere in the profile view"* doesn't.
- **Surface restore limits.** Before any time-travel experiment, walk the cascade's effects and tell the user which effects already fired and cannot be reversed.
- **Use the assembled epoch stream by default; reach for the raw trace stream when you need detail the projection drops.** `:sub-runs`, `:renders`, `:effects` are the routing surface; `:trace-events` is the escape hatch when the projection is incomplete (e.g. successful-fx attribution).
- **One trace listener per skill.** This skill registers exactly one listener (`:re-frame2-pair`) and one epoch listener (`:re-frame2-pair-epoch`). Multi-tool coexistence is the expected default — don't worry about other listeners; per Spec 009 §Listener ordering, ordering is not contract.
- **Structured MCP reads and streams elide by default — but raw `eval-cljs` is OUTSIDE that guarantee.** Per [Spec 009 §Privacy](../../spec/009-Instrumentation.md), re-frame2-pair-mcp ships a `--allow-sensitive-reads` boot gate that is **OFF by default**. With it OFF the structured read / stream tools (`snapshot`, `get-path`, `read-sub`, `trace-window`, `watch-epochs`, `subscribe`, `dispatch-dry-run`, and the recorders `record` / `read-recording` / `watch-until`) force wire-boundary elision server-side — declared-sensitive slots redact to `:rf/redacted`, declared-large slots to `:rf.size/large-elided`. The epoch-egressing tools additionally route each record through `projected-record` (a `:rf.epoch/sensitive?`-stamped record drops entirely), and `snapshot`'s `:machines` runtime-db slice redacts unless the gate is opened. So structured reads/streams are safe to fire by default.
- **`eval-cljs` is NOT governed by this gate.** It is **default-ON** (governed only by the separate `--no-eval` opt-out — eval is the REPL primitive of a pair-debug session) and returns the form's value **without running the elision walker**. So a raw `(re-frame2-pair.runtime/snapshot)` / `(…/sub-cache)` / `(re-frame.trace.tooling/trace-buffer)` / `(rf/epoch-history …)` eval can return verbatim app-db / sub-cache / trace-buffer / epoch-history — secrets included — to the AI host *even with `--allow-sensitive-reads` OFF*. **Do not reach for raw `eval-cljs` to read a privacy-sensitive app-db path, sub value, trace event, or epoch payload when a structured elided tool fits** — reserve raw eval of state for forensics, cross-referencing, and recovery, and pour raw state into an eval only on explicit user/operator request. This is the **raw-eval carve-out** — the full mechanism (the three gates, the projection profiles, the runtime-db/Xray asymmetry, opting in to the unmasked view) lives in [references/vocabulary.md §Privacy posture](references/vocabulary.md#privacy-posture--sensitive-and-the-raw-eval-carve-out), which delegates to [`skills/re-frame2/references/cross-cutting/privacy-and-elision.md`](../re-frame2/references/cross-cutting/privacy-and-elision.md).

- **Route named writes through the dedicated, gated tools — not raw `eval-cljs`.** Time-travel undo and state injection are *named, auditable* state rewrites, so they have dedicated MCP tools: `restore-epoch` (rewind to a recorded epoch) and `replace-app-db` (inject an arbitrary app-db). **Both are now allow-listed by this skill** (all 30 server tools are reachable), and they are the **canonical path** for these gestures — they validate inputs, append a synthetic epoch so the rewrite itself is undoable, log via `tap>`, and return a structured `:cascade-summary`. The server ships a **third** boot gate — `--allow-writes` (default **OFF**) — that controls exactly these two tools: against a gate-OFF server (the published default) they refuse with `{:ok? false :reason :rf.error/writes-disabled}` without touching the runtime. **The server's gate, not the skill allow-list, is the write-authority boundary** — so allow-listing the tools is safe (a gate-OFF deployment still can't write through them), and an operator who wants pair-driven writes flips `--allow-writes` once at launch rather than editing any allow-list. **Caveat — the gate does NOT cover `eval-cljs`:** `eval-cljs` is default-ON and can express the same rewrites (`(rf/restore-epoch! …)` / `app-db-reset!`), so it is *not* fenced by `--allow-writes`. That is why raw eval of a write form is the **backstop, not the default**: reach for the dedicated tool first (it routes through the structured envelope + the `--allow-writes` audit gate); fall back to the eval form only when the dedicated tool is unavailable (`:rf.error/writes-disabled` on a deliberately gate-OFF server *and* the operator has told you to proceed anyway via eval), and say so when you do. See [references/ops.md §Time-travel](references/ops.md#time-travel-epoch-restore) / [§Write](references/ops.md#write) and [references/mcp-transport.md](references/mcp-transport.md) §MCP tool reference.

---

## When to also open Xray

A re-frame2-pair session and a running Xray panel are **complementary** surfaces over the same trace bus + epoch history. re-frame2-pair owns the *driving* (dispatch, hot-swap, restore-epoch); Xray owns the *seeing* (the visual reading of what just happened across its Dynamic event-spine tabs and Static registry-browse tabs — `skills/re-frame2-xray/` is the canonical source for Xray facts). Reach for Xray alongside re-frame2-pair when:

| re-frame2-pair just did | Open Xray to … |
|---|---|
| Rewound to an earlier epoch via `restore-epoch` | Scrub the bottom-rail time-travel scrubber to inspect adjacent epochs visually; pin slices in the App-DB Diff panel. |
| Dispatched into a cascade you don't fully understand | The Event Detail panel lands on the latest cascade and shows the dispatch-id tree. |
| Hot-swapped a sub or reg-event handler | Watch the Subscriptions panel's invalidation-chain affordance recompute (`:cart/total` ← `:cart/items` ← `[:cart :items]`). |
| Stepped into a machine transition | Open the Machine Inspector for the state-chart view with transition history. |
| Triggered a schema violation | The Schema Violation Timeline surfaces it with recovery mode + source coord. |

The authoring-side guidance for getting Xray mounted (preload, layout host, suppress-auto-open knob, popout, host-CSS-variable resize) lives at [`skills/re-frame2/references/tooling/xray.md`](../re-frame2/references/tooling/xray.md). When you're advising a user mid-session on which panel to look at, route them there for the mount-side detail; this skill stays focused on the *driving* side.

---

*Deep-dive content (full API reference, EP design rationale, spec corpus, migration guide) routes through [`SKILL-REDIRECT.md`](../../SKILL-REDIRECT.md) at the repo root. Full skill-disambiguation matrix (when to use which skill) lives at [`skills/README.md` §Skill routing — single source](../README.md#skill-routing--single-source).*

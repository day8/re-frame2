# re-frame2-pair

> ↑ [`skills/`](..) — index of all eight re-frame2 skills.

> **Delivery path.** This skill ships an MCP server at [`tools/re-frame2-pair-mcp/`](../../tools/re-frame2-pair-mcp) (npm: `@day8/re-frame2-pair-mcp`) — **30 tools** catalogued over the Model Context Protocol with a persistent nREPL socket (~5–50 ms per op). All 30 are reachable from the skill's `allowed-tools:`; the two write tools (`restore-epoch` + `replace-app-db`) are the canonical path for named state rewrites and refuse with `:rf.error/writes-disabled` unless the server is launched with the default-OFF `--allow-writes` flag — the server's gate, not the allow-list, is the write-authority boundary. The MCP server is the **only** skill-facing transport. The bash-shim transport that originally fronted these ops has been retired from `allowed-tools:`; the shim scripts under `scripts/` remain on disk only for the project's own e2e test harness and ad-hoc shell use.

A `Skill` which makes `Claude Code` a better pair programmer by allowing it to **interact with your running [re-frame2](https://github.com/day8/re-frame2) application**.

This is the **re-frame2 sibling** of [`re-frame-pair`](https://github.com/day8/re-frame-pair) (the v1 skill, which targeted re-frame + re-frame-10x). re-frame2-pair is **decoupled from re-frame-10x entirely** — it consumes only re-frame2's own runtime contract (the [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md)).

A coding agent working with just the **static code** is working with a limited perspective. This Skill makes Claude Code more capable by giving it read/write access to:
  - the **internal state** of the application
  - the **dynamics of your running application**

It can:

- use the REPL
- consume re-frame2's Tool-Pair surfaces directly: the trace stream (`re-frame.trace.tooling/register-listener!`), the retain-N trace buffer (`re-frame.trace.tooling/trace-buffer`), the per-frame epoch history (`epoch-history`), the registered handler/sub/fx/machine introspection API (`registrations`, `handler-meta`, `frame-ids`, `frame-meta`, `machines`, `machine-meta`, `app-schemas`, `sub-cache`), and first-class time-travel via `restore-epoch`
- use re-frame2's source-coord annotation (`data-rf2-source-coord`) — and re-com's `data-rc-src` as a fallback — to bridge live DOM elements back to source `{:ns :line :file}`

With these capabilities, Claude Code can iteratively perform experiments by patching parts of the system, restoring state to a recorded epoch, retrying events and seeing the results.

## Status

**Pre-alpha — MCP-server-primary; push-mode streaming and the fixture app have landed.** Per-surface implementation state, known unknowns, and the remaining spike deliverables live in [`STATUS.md`](STATUS.md).

More docs: [`docs/TESTING.md`](docs/TESTING.md) (test plan); [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md) (running from a clone); [`RELEASING.md`](RELEASING.md) (release flow); [`docs/initial-spec.md`](docs/initial-spec.md) (the original, historical design record).

## Why a separate skill (vs. extending re-frame-pair)?

re-frame-pair (v1) reaches into re-frame-10x internals to read the epoch buffer, drive undo, and time-travel. re-frame2 supersedes both that pattern and that dependency: epoch recording, querying, and restore are first-class surfaces in re-frame2 itself (per the [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md) §Time-travel and §How AI tools attach). Trying to reuse the v1 skill against re-frame2 would mean carrying a re-frame-10x dep that doesn't need to exist.

re-frame2-pair is a clean port: same vocabulary (read / write / trace / watch / hot-reload / time-travel), same recipes, but every surface translated to re-frame2's own primitives. The two skills can coexist — pick the one that matches your app's framework version. A future merge is possible if/when re-frame and re-frame2 converge, but isn't a goal.

## Cross-link

- [re-frame-pair](https://github.com/day8/re-frame-pair) — the v1 skill (re-frame + re-frame-10x; this is its source).
- [re-frame2-pair-retro](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-pair-retro) — the retrospective skill that reviews pair sessions and proposes improvements to re-frame2-pair itself. Sibling to v1's [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver).
- [re-frame2 Tool-Pair Spec](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md) — the canonical surface contract this skill consumes.
- [`../shared/tool-pair-surfaces.md`](../shared/tool-pair-surfaces.md) — the skills-corpus pointer at the Tool-Pair surface enumeration (and the "supersedes re-frame-10x" claim) that sibling skills cite when routing an upstream finding. This README carries the fullest surface list; the shared leaf points back here for it.

> **Distribution caveat — link from a clone; bring `skills/shared/` along.** The skill references sibling `../shared/` leaves in normal operation, and a `package.json` `files` allow-list cannot reach a parent directory — `npm pack` (and any plugin bundle) does **not** ship `skills/shared/`. The supported install is a link from a full re-frame2 monorepo clone (link, never copy); a tarball / plugin / vendored install must copy `skills/shared/` alongside the skill or the shared references break.

## Which technical stack?

Designed for web apps built from the following stack:

- A [re-frame2](https://github.com/day8/re-frame2) application (reference implementation: CLJS + Reagent v2)
- `re-frame.interop/debug-enabled?` true (the `goog.DEBUG` mirror — set automatically in dev builds; production elides the trace and epoch surfaces per [Spec 009 §Production builds](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md))
- For the DOM→source bridge: re-frame2 injects `data-rf2-source-coord` on every **registered view's** root DOM element automatically in debug builds (mandatory per [Spec 006 §Source-coord annotation], gated on `interop/debug-enabled?` — there is **no** `configure!` knob to enable it). Coverage therefore needs registered views (`reg-view`) on a DOM-capable adapter; plain anonymous Reagent fns aren't annotated. As a fallback, [`re-com`](https://github.com/day8/re-com) with debug instrumentation + `:src (at)` at call sites emits `data-rc-src`. Without registered-view coverage (or re-com `:src`), the `dom/*` ops degrade gracefully.
- [shadow-cljs](https://shadow-cljs.github.io/) as the build tool, with nREPL enabled on the dev build

You don't need to change your application *code* to use it — the MCP server (Node) handles transport, and only re-frame2's own dev-build instrumentation is required on the application side. The one build-config change is the dev-only preload: install the `@day8/re-frame2-pair` package and add its `preload/` directory to `:source-paths` plus the namespace to `:devtools :preloads` (two `shadow-cljs.edn` lines — see *Install* below). The preload loads only in dev builds; production is untouched.

## No re-frame-10x dependency

re-frame2-pair does not require, recommend, or fall back to re-frame-10x. Where v1 read 10x's epoch buffer, v2 reads `(rf/epoch-history frame-id)`. Where v1 stepped through 10x's internal navigation events, v2 calls `(rf/restore-epoch! frame-id epoch-id)`. Where v1 detected a 10x trace callback, v2 registers its own listener under id `:re-frame2-pair` (multi-tool coexistence is the expected default per [Spec 009 §Listener ordering](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md)). The underlying "these surfaces are first-class in re-frame2, superseding 10x" claim is stated once for the whole skills corpus in [`../shared/tool-pair-surfaces.md` §Supersedes re-frame-10x](../shared/tool-pair-surfaces.md); this section is the re-frame2-pair-specific framing of it.

If your app uses both re-frame2-pair and Xray, they coexist as parallel listeners over re-frame2's Tool-Pair surfaces. Xray's default UI is the app-provided `[data-rf-xray-host]` true-inline panel; re-frame2-pair has no UI panel and neither tool depends on the other.

## Two modes

Without this `Skill`, `Claude Code` writes edits to source files and shadow-cljs hot-reloads them into the running program.

`re-frame2-pair` adds a second mode: Claude can also make **ephemeral** changes to application code via the REPL — hot-swap an event handler or a subscription, try it, discard if it didn't work.

The difference between the two modes is that REPL changes last until the next full page reload whereas source edits stick.


## Examples

Here's the kinds of conversations you can have with Claude.

### Inspect the last change

> **You**: What changes did the last event make to `app-db` under `:shopping-cart`?
>
> **Claude**: 2 items, subtotal 47.50, no coupon.
> `{:items [{:sku "BK-42" :qty 2} {:sku "TS-9" :qty 1}] :total 47.50 :coupon nil}`

### Ask why a view didn't update

> **You**: I dispatched `[:profile/save ...]` but the name in the header isn't changing.
>
> **Claude**: The header reads `[:user/display-name]`. The last epoch's `:sub-runs` has `[:user/profile]` in it — that one re-ran — but `[:user/display-name]` is absent, so it cache-hit (value-equal recompute suppression). Your handler wrote to `[:user :pending-profile]`, not `[:user :profile]`. The upstream sub returned `=`, the equality gate held, the header didn't re-render. Want me to hot-swap the handler to write the intended path?

### Jump from a click to the line of code

> **You**: I just clicked the "Save" button and nothing happened. Where is that button and why doesn't it work?
>
> **Claude**: `src/app/profile/view.cljs:84`. It's a `re-com/button` with `:on-click #(rf/dispatch [:profile/save])`. The click *did* dispatch — the latest epoch shows `:trigger-event [:profile/save]` and `:db-after` differs from `:db-before`, but `:profile/form-dirty?` didn't flip. What feedback were you expecting: toast, spinner, redirect?

### Trace the full cascade of an event

> **You**: What happens when I dispatch `[:cart/apply-coupon "SPRING25"]`?
>
> **Claude**: Interceptors: `path :shopping-cart`, handler; declared coeffects: `:rf/time-ms`. Effects map: `{:db ...}` only — no HTTP, no further dispatches. app-db diff: `:coupon` `nil` → `"SPRING25"`, `:total` `47.50` → `35.62`. Subs that re-ran (from `:sub-runs`): `[:cart/total]`, `[:cart/coupon-status]`. `[:cart/items]` is absent — cache-hit. One render in `:renders`: the total badge at `cart_view.cljs:23`. Pure handler, nothing outside the cart subtree touched.

### Watch events as they fire

> **You**: Tell me about every `:checkout/*` event as I click through.
>
> **Claude**: *(subscribes in the background; narrates each matching epoch as it fires, one short paragraph, with effect cascade)* — I'll close the stream with `unsubscribe` when you're done, or bound it up front with `:max-ms` / `:max-events`.

### Hot-swap a handler mid-session

> **You**: Make `:cart/apply-coupon` reject expired coupons instead of silently accepting them.
>
> **Claude**: Proposed change:
> ```clojure
> (rf/reg-event :cart/apply-coupon
>   (fn [{:keys [db]} [_ code]]
>     {:db (if (contains? (:expired-coupons db) code)
>            (assoc db :cart/coupon-error (str code " is expired"))
>            (assoc-in db [:shopping-cart :coupon] code))}))
> ```
> Registered. The runtime emitted `:rf.registry/handler-replaced` so I know it took. Try clicking apply — I'll watch the next epoch.


### Ask a question that doesn't have a pre-baked op

> **You**: Are there any items in the cart with `:qty <= 0`?
>
> **Claude**: *[at the REPL, reading the specific frame]* `(->> (get-in (rf/app-db-value :rf/default) [:shopping-cart :items]) (filter #(<= (:qty %) 0)))` — returns `()`. None right now, but the invariant isn't enforced by the `:cart/add-item` handler, and there's no `app-schema` registered for `[:shopping-cart :items]`. Want me to propose a guard, or register a malli schema?

### Post-mortem — how did I get into this state?

> **You**: I notice that sometime in the last few events the "Done" button got disabled. I don't remember which action did it. Can you figure out when that happened?
>
> **Claude**: *[walks back through the operating frame's `epoch-history`, looking for the transition]* Three epochs ago, the event `[:cart/apply-coupon "EXPIRED"]` flipped `:session-state` from `:authenticated` to `:unauthenticated` — that's what greyed the button. The cascade also cleared `:user/profile`. Looks like a bug in the coupon handler, not an intended side-effect. Want me to fix it?


### Iterate against the same starting state

> **You**: When I first enter the Dashboard panel, the global reset button doesn't work. Fix it, then return to the previous state and fire the same event again, iterating until it works.
>
> **Claude**: I called `(rf/restore-epoch! :rf/default <pre-click-epoch-id>)` — the restore returned `true`, so the whole frame-state is back (both partitions: app-db *and* runtime-db, so any machine snapshots / route slice / elision declarations are rewound too). (Caveat: any HTTP requests or navigation that already fired during the original cascade are *not* reversed — restore rewinds durable frame-state, not side effects or transient host state.) The panel-level subscription was wrong; I patched it. Re-ran the event, `app-db` now updates as expected. Want me to put the patch into the source code?

### Stub an effect for a what-if

> **You**: What would happen if the checkout HTTP request returned a 500?
>
> **Claude**: I'll dispatch `[:cart/checkout]` with `:fx-overrides {:http :stub-http}`, where `:stub-http` is a registered fx that fires the canonical `:rf.http/managed` failure trace with status 500. Watch — *(reports the resulting epoch's cascade: the failure reply lands on the request's `:on-failure` event, whose `:rf.http/http-5xx` failure `:kind` drives the rest of the cascade)*

### Commit a change via source edit

> **You**: Transfer the patch to the source code.
>
> **Claude**: That change has been made to the source code.


## Install

1. Install the MCP server: `npm install -g @day8/re-frame2-pair-mcp`.
2. Add an `mcpServers` entry to your Claude Code settings — see [`tools/re-frame2-pair-mcp/README.md`](../../tools/re-frame2-pair-mcp/README.md) for the configuration snippet and the full tool surface.
3. Install the preload package into your app as a dev dependency: `npm install -D @day8/re-frame2-pair`. This ships the `re-frame2-pair.runtime` CLJS namespace under its `preload/` directory; the MCP server package does **not** carry it. **The preload is required** — without it `discover-app` refuses every session with `:reason :runtime-loaded-but-preload-missing` (the normal missing-preload verdict; `:runtime-not-preloaded` is the degradation fallback the ladder returns only if it errors mid-diagnosis).
4. Add the shadow-cljs `:devtools :preloads` entry (`[re-frame2-pair.runtime]`) and a `:source-paths` line pointing at the installed `node_modules/@day8/re-frame2-pair/preload` directory — see `SKILL.md` §Setup for the two-line snippet. No closure-defines. The preload only loads in dev; production builds are untouched.

### How the connection works

The `re-frame2-pair.runtime` namespace ships into the consumer app via shadow-cljs's standard `:devtools :preloads` mechanism (see `SKILL.md` §Setup). The preload registers exactly one trace listener (`:re-frame2-pair`) and one epoch listener (`:re-frame2-pair-epoch`) at bundle-load time, and installs a load-time marker (`js/globalThis.__re_frame2_pair_runtime`) the skill probes on first use.

On first use in a session:

1. The MCP server locates your shadow-cljs nREPL port automatically (it scans the standard port files and absorbs shadow restarts — you rarely configure anything; see [`references/mcp-transport.md` §Install / configure](references/mcp-transport.md#install--configure-one-time)).
2. `discover-app` probes the load-time marker to confirm the preload landed. If the marker is missing, the op refuses with a structured `:reason :runtime-loaded-but-preload-missing` (the normal missing-preload verdict — a runtime is live but the marker is absent) and a hint pointing at the two-line setup. No per-session inject step.
3. Live-watch happens two ways: pull-mode `watch-epochs` (tracks the last seen `:epoch-id` per frame and asks for everything since) and push-mode `subscribe` (the long-running call pushes each batch as a `notifications/progress` tick — see [`references/streaming-subscriptions.md`](references/streaming-subscriptions.md)). Hot-reload confirmation is probe-based: after an edit, the `tail-build` tool polls a short CLJS form (typically against `(rf/handler-meta ...)`) that changes when the new code has landed in the browser. The name `tail-build` is historical — it does not actually tail the shadow-cljs server log.

On full page refresh, the preload re-runs as part of the next bundle load — the marker reappears automatically; no manual reconnect step.

## Invoking it in Claude

Once the skill is installed, there are two ways to reach it from a Claude Code conversation.

### Implicit — just ask

The skill's description auto-matches when you talk about the running re-frame2 app. Ask in natural language:

> What's in `app-db` under `:shopping-cart`?
>
> Why didn't the header update after `[:profile/save ...]`?
>
> Fire the delete button on the first row of the table.

Claude connects on first use of the session and stays connected until you exit.

### Explicit — slash command

```
/re-frame2-pair
```

…or name it in a prompt:

> Using re-frame2-pair, trace `[:cart/apply-coupon "SPRING25"]` and show me the cascade.

Useful when you want to force the tool, or when the phrasing of your question doesn't obviously lean on the running app.

### What happens on first use

The skill's first op in a session is `discover-app`, which:

1. Finds the running shadow-cljs nREPL. The MCP server discovers the port on its own (a cascade ending in shadow's `roots/list` / HTTP probe). When discovery misses (no running shadow, a non-default port, an exotic setup), pass `--port-file <abs>` to the server or set `SHADOW_CLJS_NREPL_PORT` (a CWD-independent override).
2. Verifies a browser runtime is attached to that build.
3. Checks that `re-frame.core` is loaded and `re-frame.interop/debug-enabled?` is true.
4. Probes the `re-frame2-pair.runtime` preload marker; refuses with `:reason :runtime-loaded-but-preload-missing` (the normal missing-preload verdict) and a setup hint when absent.
5. Reports `connected` or names the single failing check with a one-line fix suggestion.

## How it works

The pieces (design; see *Status* above):

1. `discover-app` (MCP tool `mcp__re-frame2-pair__discover-app`) finds the running shadow-cljs build and its nREPL port, switches the session into `:cljs` mode for that build, and verifies re-frame2 + `debug-enabled?` + the preload marker.
2. `eval-cljs` (MCP tool `mcp__re-frame2-pair__eval-cljs`) sends short ClojureScript forms over nREPL into the browser runtime and returns edn.
3. `preload/re_frame2_pair/runtime.cljs` is the `re-frame2-pair.runtime` namespace itself, loaded into the consumer app via shadow-cljs's `:devtools :preloads`. It registers exactly one trace listener (`:re-frame2-pair`) and one epoch listener (`:re-frame2-pair-epoch`), and installs the `js/globalThis.__re_frame2_pair_runtime` marker the connect-flow probes.
4. `SKILL.md` teaches Claude a verb vocabulary (read / write / trace / watch / hot-reload / time-travel) mapped onto those forms, plus diagnostic recipes composed from them.
5. All trace and epoch reads come from re-frame2's own surfaces — `re-frame.trace.tooling/register-listener!`, `re-frame.trace.tooling/trace-buffer`, `register-epoch-listener!`, `epoch-history`. Render entries are projected by re-frame2 itself in `:renders`, with source coords resolvable through the registrar's source-coord capture (Spec 001).

The live operation catalogue and error surfaces are in [`references/ops.md`](references/ops.md) and [`references/errors.md`](references/errors.md); per-surface state is in [`STATUS.md`](STATUS.md).

## License

MIT

# re-frame-pair2

A `Skill` which makes `Claude Code` a better pair programmer by allowing it to **interact with your running [re-frame2](https://github.com/day8/re-frame2) application**.

This is the **re-frame2 sibling** of [`re-frame-pair`](https://github.com/day8/re-frame-pair) (the v1 skill, which targeted re-frame + re-frame-10x). re-frame-pair2 is **decoupled from re-frame-10x entirely** — it consumes only re-frame2's own runtime contract (the [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/master/docs/specification/Tool-Pair.md)).

A coding agent working with just the **static code** is working with a limited perspective. This Skill makes Claude Code more capable by giving it read/write access to:
  - the **internal state** of the application
  - the **dynamics of your running application**

It can:

- use the REPL
- consume re-frame2's Tool-Pair surfaces directly: the trace stream (`register-trace-cb`), the retain-N trace buffer (`trace-buffer`), the per-frame epoch history (`epoch-history`), the registered handler/sub/fx/machine introspection API (`handlers`, `handler-meta`, `frame-ids`, `frame-meta`, `machines`, `machine-meta`, `app-schemas`, `sub-cache`), and first-class time-travel via `restore-epoch`
- use re-frame2's source-coord annotation (`data-rf2-source-coord`) — and re-com's `data-rc-src` as a fallback — to bridge live DOM elements back to source `{:ns :line :file}`

With these capabilities, Claude Code can iteratively perform experiments by patching parts of the system, restoring state to a recorded epoch, retrying events and seeing the results.

## Status

**Pre-alpha — code is written, but not yet exercised against a running re-frame2 app.** The repository contains the SKILL.md, the `scripts/runtime.cljs` injection payload, a babashka-based ops dispatcher (`scripts/ops.clj`) behind thin shell shims, the plugin manifest, the npm package manifest, and a GitHub Actions workflow that publishes to npm on tag.

What's **not** done: a fixture app and end-to-end exercise. See [`STATUS.md`](STATUS.md) and [`docs/initial-spec.md`](docs/initial-spec.md) §8a for the per-phase implementation state and the spike deliverables.

Read [`STATUS.md`](STATUS.md) for the per-phase implementation state; [`docs/initial-spec.md`](docs/initial-spec.md) for the full design; [`docs/TESTING.md`](docs/TESTING.md) for the test plan; [`docs/LOCAL_DEV.md`](docs/LOCAL_DEV.md) for running from a clone without waiting for an npm release; [`RELEASING.md`](RELEASING.md) for the release flow.

## Why a separate skill (vs. extending re-frame-pair)?

re-frame-pair (v1) reaches into re-frame-10x internals to read the epoch buffer, drive undo, and time-travel. re-frame2 supersedes both that pattern and that dependency: epoch recording, querying, and restore are first-class surfaces in re-frame2 itself (per the [Tool-Pair Spec](https://github.com/day8/re-frame2/blob/master/docs/specification/Tool-Pair.md) §Time-travel and §How AI tools attach). Trying to reuse the v1 skill against re-frame2 would mean carrying a re-frame-10x dep that doesn't need to exist.

re-frame-pair2 is a clean port: same vocabulary (read / write / trace / watch / hot-reload / time-travel), same recipes, but every surface translated to re-frame2's own primitives. The two skills can coexist — pick the one that matches your app's framework version. A future merge is possible if/when re-frame and re-frame2 converge, but isn't a goal.

## Cross-link

- [re-frame-pair](https://github.com/day8/re-frame-pair) — the v1 skill (re-frame + re-frame-10x; this is its source).
- [re-frame-pair-improver2](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair-improver2) — the post-session retrospective skill that reviews pair sessions and proposes improvements to re-frame-pair2 itself. Sibling to v1's [re-frame-pair-improver](https://github.com/day8/re-frame-pair-improver).
- [re-frame2 Tool-Pair Spec](https://github.com/day8/re-frame2/blob/master/docs/specification/Tool-Pair.md) — the canonical surface contract this skill consumes.

## Which technical stack?

Designed for web apps built from the following stack — in pre-alpha, it has not yet been exercised end-to-end against a running app of any shape:

- A [re-frame2](https://github.com/day8/re-frame2) application (reference implementation: CLJS + Reagent v2)
- `re-frame.interop/debug-enabled?` true (the `goog.DEBUG` mirror — set automatically in dev builds; production elides the trace and epoch surfaces per [Spec 009 §Production builds](https://github.com/day8/re-frame2/blob/master/docs/specification/009-Instrumentation.md))
- Optional: re-frame2's source-coord annotation enabled (`(rf/configure :source-coords {:annotate-dom? true})`) — and/or [`re-com`](https://github.com/day8/re-com) with debug instrumentation + `:src (at)` at call sites. Without one of these, the `dom/*` ops degrade gracefully.
- [shadow-cljs](https://shadow-cljs.github.io/) as the build tool, with nREPL enabled on the dev build

You don't need to make any changes to your code/project to use it, but you will need [`babashka`](https://babashka.org) installed because the skill's shell shims use it. See the [babashka install guide](https://github.com/babashka/babashka#installation).

## No re-frame-10x dependency

re-frame-pair2 does not require, recommend, or fall back to re-frame-10x. Where v1 read 10x's epoch buffer, v2 reads `(rf/epoch-history frame-id)`. Where v1 stepped through 10x's internal navigation events, v2 calls `(rf/restore-epoch frame-id epoch-id)`. Where v1 detected a 10x trace callback, v2 registers its own listener under id `:re-frame-pair2` (multi-tool coexistence is the expected default per [Spec 009 §Listener ordering](https://github.com/day8/re-frame2/blob/master/docs/specification/009-Instrumentation.md)).

If your app uses both re-frame2 *and* a re-frame-10x v2 (when one ships), this skill and that tool will coexist as parallel listeners. Neither depends on the other.

## Two modes

Without this `Skill`, `Claude Code` writes edits to source files and shadow-cljs hot-reloads them into the running program.

`re-frame-pair2` adds a second mode: Claude can also make **ephemeral** changes to application code via the REPL — hot-swap an event handler or a subscription, try it, discard if it didn't work.

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
> **Claude**: The header reads `[:user/display-name]`. The last epoch's `:sub-runs` has `[:user/profile]` in it — that one re-ran — but `[:user/display-name]` is absent, so it cache-hit (rf2-719e value-equal recompute suppression). Your handler wrote to `[:user :pending-profile]`, not `[:user :profile]`. The upstream sub returned `=`, the equality gate held, the header didn't re-render. Want me to hot-swap the handler to write the intended path?

### Jump from a click to the line of code

> **You**: I just clicked the "Save" button and nothing happened. Where is that button and why doesn't it work?
>
> **Claude**: `src/app/profile/view.cljs:84`. It's a `re-com/button` with `:on-click #(rf/dispatch [:profile/save])`. The click *did* dispatch — the latest epoch shows `:trigger-event [:profile/save]` and `:db-after` differs from `:db-before`, but `:profile/form-dirty?` didn't flip. What feedback were you expecting: toast, spinner, redirect?

### Trace the full cascade of an event

> **You**: What happens when I dispatch `[:cart/apply-coupon "SPRING25"]`?
>
> **Claude**: Interceptors: `inject-cofx :now`, `path :shopping-cart`, handler. Effects map: `{:db ...}` only — no HTTP, no further dispatches. app-db diff: `:coupon` `nil` → `"SPRING25"`, `:total` `47.50` → `35.62`. Subs that re-ran (from `:sub-runs`): `[:cart/total]`, `[:cart/coupon-status]`. `[:cart/items]` is absent — cache-hit. One render in `:renders`: the total badge at `cart_view.cljs:23`. Pure handler, nothing outside the cart subtree touched.

### Watch events as they fire

> **You**: Tell me about every `:checkout/*` event as I click through.
>
> **Claude**: *(watches in the background; narrates each matching epoch as it fires, one short paragraph, with effect cascade)* — idle timeout after 30s of silence, so you don't need to remember to stop it.

### Hot-swap a handler mid-session

> **You**: Make `:cart/apply-coupon` reject expired coupons instead of silently accepting them.
>
> **Claude**: Proposed change:
> ```clojure
> (rf/reg-event-db :cart/apply-coupon
>   (fn [db [_ code]]
>     (if (contains? (:expired-coupons db) code)
>       (assoc db :cart/coupon-error (str code " is expired"))
>       (assoc-in db [:shopping-cart :coupon] code))))
> ```
> Registered. The runtime emitted `:rf.registry/handler-replaced` so I know it took. Try clicking apply — I'll watch the next epoch.


### Ask a question that doesn't have a pre-baked op

> **You**: Are there any items in the cart with `:qty <= 0`?
>
> **Claude**: *[at the REPL]* `(->> (rf/snapshot-of [:shopping-cart :items]) (filter #(<= (:qty %) 0)))` — returns `()`. None right now, but the invariant isn't enforced by the `:cart/add-item` handler, and there's no `:app-schema` registered for `[:shopping-cart :items]`. Want me to propose a guard, or register a malli schema?

### Post-mortem — how did I get into this state?

> **You**: I notice that sometime in the last few events the "Done" button got disabled. I don't remember which action did it. Can you figure out when that happened?
>
> **Claude**: *[walks back through the operating frame's `epoch-history`, looking for the transition]* Three epochs ago, the event `[:cart/apply-coupon "EXPIRED"]` flipped `:session-state` from `:authenticated` to `:unauthenticated` — that's what greyed the button. The cascade also cleared `:user/profile`. Looks like a bug in the coupon handler, not an intended side-effect. Want me to fix it?


### Iterate against the same starting state

> **You**: When I first enter the Dashboard panel, the global reset button doesn't work. Fix it, then return to the previous state and fire the same event again, iterating until it works.
>
> **Claude**: I called `(rf/restore-epoch :rf/default <pre-click-epoch-id>)` — the restore returned `true`, so `app-db` is back. (Caveat: any HTTP requests or navigation that already fired during the original cascade are *not* reversed — restore rewinds `app-db` only.) The panel-level subscription was wrong; I patched it. Re-ran the event, `app-db` now updates as expected. Want me to put the patch into the source code?

### Stub an effect for a what-if

> **You**: What would happen if the checkout HTTP request returned a 500?
>
> **Claude**: I'll dispatch `[:cart/checkout]` with `:fx-overrides {:http :stub-http}`, where `:stub-http` is a registered fx that fires the canonical `:rf.http/managed` failure trace with status 500. Watch — *(reports the resulting epoch's cascade, including the error-projector path through `reg-error-projector`)*

### Commit a change via source edit

> **You**: Transfer the patch to the source code.
>
> **Claude**: That change has been made to the source code.


## Install

`re-frame-pair2` ships as part of the [`day8/re-frame2`](https://github.com/day8/re-frame2) monorepo. There is no separate npm package or plugin registry entry — clone re-frame2 and reference the skill from `skills/re-frame-pair2/`.

`re-frame-pair2` adds nothing to the host project beyond what re-frame2 already requires. On first connect, the skill injects its runtime helpers into your app over the REPL — no extra deps, no extra preloads, no extra closure-defines attributable to `re-frame-pair2`.

### Install the skill in Claude Code

#### Global — for you, across any re-frame2 project

Symlink (or copy) the skill into your user Claude config:

```bash
git clone https://github.com/day8/re-frame2.git    # one-time, anywhere
ln -s "$(pwd)/re-frame2/skills/re-frame-pair2" ~/.claude/skills/re-frame-pair2
```

Best when you work on several re-frame2 apps, or you're the only Claude Code user on this project.

#### Project-local — for your whole team via the repo

Copy the skill into the project's own `.claude/skills/re-frame-pair2/` and commit it. Teammates who clone the repo and open Claude Code there get the skill on first use, pinned to the committed version:

```bash
cd your-re-frame2-project
cp -r /path/to/re-frame2/skills/re-frame-pair2 .claude/skills/re-frame-pair2
git add .claude/skills/re-frame-pair2
```

#### Which to choose

- **Global** if you're the only person using Claude Code here, or you hop between re-frame2 apps.
- **Project-local** if your team wants one pinned, shared version.
- **Both** is fine — the project-local install takes precedence when both are present.

### How the connection works

On first use in a session:

1. The skill locates your shadow-cljs nREPL port.
2. It sends a handful of ClojureScript forms over nREPL to create a `re-frame-pair2.runtime` namespace in your app, populated with helpers and convenience wrappers around re-frame2's public Tool-Pair surfaces. The runtime also calls `(rf/register-trace-cb :re-frame-pair2 ...)` and `(rf/register-epoch-cb! :re-frame-pair2-epoch ...)` so live-watch ops have a push-style stream.
3. Live-watch ops (`watch/*`) consume the assembled-epoch stream by tracking the last seen `:epoch-id` per frame and asking for everything since (see [`docs/initial-spec.md`](docs/initial-spec.md) §4.4). Hot-reload confirmation is probe-based: after an edit, the skill polls a short CLJS form (typically against `(rf/handler-meta ...)`) that changes when the new code has landed in the browser. The script is named `tail-build.sh` for historical reasons — it does not actually tail the shadow-cljs server log.

On full page refresh, the skill detects that its session sentinel is gone and re-injects automatically.

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
/re-frame-pair2
```

…or name it in a prompt:

> Using re-frame-pair2, trace `[:cart/apply-coupon "SPRING25"]` and show me the cascade.

Useful when you want to force the tool, or when the phrasing of your question doesn't obviously lean on the running app.

### What happens on first use

The skill's first op in a session is `discover-app.sh`, which:

1. Finds the running shadow-cljs nREPL (from `target/shadow-cljs/nrepl.port`, falling back to `.shadow-cljs/nrepl.port` or the `SHADOW_CLJS_NREPL_PORT` env var — the exact location depends on shadow-cljs version and config).
2. Verifies a browser runtime is attached to that build.
3. Checks that `re-frame.core` is loaded and `re-frame.interop/debug-enabled?` is true.
4. Reports `connected` or names the single failing check with a one-line fix suggestion.

Once verified, the skill injects its runtime namespace (`re-frame-pair2.runtime`) into the app over nREPL. All subsequent ops reuse that connection.

## How it works

The pieces (design; see *Status* above):

1. `discover-app.sh` finds the running shadow-cljs build and its nREPL port, switches the session into `:cljs` mode for that build, and verifies re-frame2 + `debug-enabled?`.
2. `eval-cljs.sh` sends short ClojureScript forms over nREPL into the browser runtime and returns edn.
3. `inject-runtime.sh` creates the `re-frame-pair2.runtime` namespace in the app on connect, populating it with helpers over re-frame2's public Tool-Pair surfaces. The session sentinel (a UUID) is interned here so full-page-refresh detection is a simple lookup. The injection also registers exactly one trace listener (`:re-frame-pair2`) and one epoch listener (`:re-frame-pair2-epoch`).
4. `SKILL.md` teaches Claude a verb vocabulary (read / write / trace / watch / hot-reload / time-travel) mapped onto those forms, plus diagnostic recipes composed from them.
5. All trace and epoch reads come from re-frame2's own surfaces — `register-trace-cb`, `trace-buffer`, `register-epoch-cb!`, `epoch-history`. Render entries are projected by re-frame2 itself in `:renders`, with `:ns` / `:line` / `:file` resolvable through the registrar's source-coord capture (Spec 001).

See [`docs/initial-spec.md`](docs/initial-spec.md) for the full operation catalogue, architecture, error surfaces, versioning, and phased delivery plan.

## License

MIT

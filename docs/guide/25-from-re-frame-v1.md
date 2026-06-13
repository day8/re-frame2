# 25 - From re-frame v1

You have a re-frame v1 app and a migration to plan, and the question you actually want answered isn't "what's the API diff" — it's "how big a deal is this, really?" The honest answer is the reassuring one: most of your code is already v2 code and doesn't know it. This chapter is the map of what crosses over unchanged, what changed and *why*, and the handful of places where the v2 architecture tells a genuinely different story than the one you wrote against in v1.

## The good news first, because it's load-bearing

Here's the thing that should reframe the whole project before you start: **the bones are identical.** The six dominoes are the six dominoes. Events are still data describing what happened. Handlers are still pure functions of `(db, event) → db`. Subscriptions are still derivations off a single `app-db`. The whole opinionated stance — one source of truth, data over APIs over syntax, immutable values and stable contracts — is the v1 stance, because v2 *is* v1's architecture with new capabilities grown on top, not a different architecture wearing the same name.

That's why your v1 code reads as v2 code on the first pass, most of the time without you noticing what changed. A `reg-event-db` is a `reg-event-db`. A `reg-sub` is a `reg-sub`. A hiccup view is a hiccup view. The migration is not a rewrite; it's a *sweep* — a bounded set of mechanical renames, a smaller set of judgment calls, and a few genuinely new shapes you'll *choose* to adopt rather than be forced into. The framework's own number for the rule set is "40-plus M- and O-rules," and the reason that number sounds scarier than the work feels is that the vast majority of them are find-and-replace, and a tool does them for you.

## Don't do this by hand

Which brings me to the most important sentence in the chapter: **the migration is automated, and you should not hand-migrate anything larger than a toy.** There's a Claude Code skill that ships in this repo, [`skills/re-frame-migration/`](../../skills/re-frame-migration/), whose entire job is to drive the sweep. It walks six phases — orient, bump, sweep, verify, optional modernisations, report — applies the mechanical rewrites unprompted, and *stops at every judgment call to ask you* before it touches anything risky. The mechanical rewrites are what the skill calls Type A; the judgment calls are Type B, and the cardinal rule the skill obeys is **don't invent migration rules** — if a failure doesn't match a known shape, it surfaces it for human review rather than guessing.

The workflow is four steps:

1. Open a fresh Claude Code session at the root of your v1 project.
2. Paste the kickoff prompt from [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md). The session loads the skill and walks the phases autonomously.
3. Answer questions at the Type B checkpoints — the agent explains the risk and waits for your call before rewriting.
4. Run your test suite. The agent re-verifies and produces a migration report.

The rest of this chapter is *orientation* — the mental model of what the skill is doing, so that when you're reading a diff at step 3 and asking "what kind of thing am I looking at?", you have a category to slot it into. The exhaustive rule list lives in the skill; this is the why behind it.

## The deps change: pay-as-you-go

re-frame2 is **pay-as-you-go** — capabilities ship as separate artefacts so unused ones never bundle. That single fact shapes the whole deps migration:

1. **Swap the core coord.** Remove `re-frame/re-frame`. Add `day8/re-frame2`.
2. **Add a substrate adapter** for your view library — `day8/re-frame2-reagent` if you're on Reagent (and bump Reagent to v2, which the reference targets), or the matching UIx/Helix adapter if you've already moved off Reagent. ([Chapter 22 — Adapters](how-to/use-uix-helix-or-slim.md) is the substrate story in full.)
3. **Add per-feature artefacts only for features you actually use.** Don't add them all "to be safe" — the skill tells you which ones the codebase trips. The split is `day8/re-frame2-{machines, flows, routing, http, ssr, schemas, epoch}`, and an app that doesn't use flows doesn't carry flow code.
4. **Don't bump anything else in the same change.** Keep React, shadow-cljs, and the rest on their current versions until the migration settles. Separate failure modes are far easier to debug separately, and a migration that's also a dependency upgrade is two bugs wearing one diff.

The skill handles every part of this; the list is here so you know what's coming.

## What changed, and why — the categories

These are the broad shapes of breakage. The skill identifies and resolves them; this is the taxonomy so you can read a diff with comprehension instead of alarm.

**Registrar imports.** Code that requires `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`, or `re-frame.alpha` directly was reaching past the front door, and v2 closes it. The single-import contract is `(:require [re-frame.core :as rf])`. Direct access to `re-frame.db/app-db` — always off-contract — is now firmly so; the accessor is `(rf/app-db-value app-frame)`, naming your app frame and returning a plain map. The reason for the tightening is the same reason chapter 18's frames work at all: when there can be N isolated app-db instances, "the global app-db atom" stops being a coherent thing to reach for, and the contract has to be a function call that names which frame you mean.

**You must establish an app frame.** This is the change most likely to bite a v1 codebase, and [chapter 18](concepts/frames.md) is the full story. v1 gave you an ambient global `app-db` that every bare `dispatch` / `subscribe` resolved against. v2 does **not**: frame identity is *carried, not found* — an operation reads its frame from the scope it runs under, and the runtime never synthesises one from absence. So a v1 app that just calls `(rf/dispatch [:boot])` at top level with no frame established now fails loudly with `:rf.error/no-frame-context`. The fix is one line of ceremony at your root: register an app frame and wrap your tree in a `frame-provider`. A migration *may* choose `:rf/default` as that frame's explicit id (it reads familiarly), but you still register and provide it — the framework will not infer it for you:

```clojure
(rf/reg-frame :app/main {:on-create [:boot]})        ;; or :rf/default if you prefer the familiar name

(rdc/render root
  [rf/frame-provider {:frame :app/main}
   [app-root]])
```

Inside that tree, every bare `dispatch` / `subscribe` you already wrote works unchanged — the frame rides along ambiently. Only *rootless* calls (async callbacks that lost their scope, top-level boot code with no provider) need attention, and those are exactly the wrong-frame footguns v1 used to swallow silently. The migration skill rewrites bare top-level call sites into a root provider and flags async callbacks for an explicit `frame-handle` / `frame-bound-fn` capture.

**Removed surfaces.** A handful of v1 affordances are gone, each with a defined replacement: `dispatch-with` / `dispatch-sync-with` fold into a two-arg `dispatch` with an opts map; `reg-global-interceptor` is gone because interceptors are frame-scoped in v2; `reg-sub-raw` gives way to `reg-sub` or the substrate adapter; the `^:flush-dom` event metadata becomes `:dispatch-later {:ms 0}`. None of these is a capability *loss* — they're consolidations, the same job done through one shape instead of several.

**Removed interceptors.** This is the category that surprises people, so it's worth dwelling on. Six v1 interceptors are gone — `debug`, `trim-v`, `on-changes`, `enrich`, `after`, and `inject-cofx` — and the reason each one left is that v2 grew a *better-shaped* answer to the problem it solved. `debug` is subsumed by the trace bus ([chapter 16](concepts/observability.md)), which sees everything `debug` saw and a great deal more. `trim-v` is unnecessary because the canonical event shape is consistent now. `enrich` and `after` are replaced by flows and schemas — declarative, registered, tooling-visible versions of "compute a derived thing after the handler" and "assert something about the result." `on-changes` becomes *flows*, which deserve their own section below. And `inject-cofx` is replaced by the `:rf.cofx/requires` declaration ([chapter 07](concepts/effects-and-coeffects.md)): coeffect delivery is no longer a positional interceptor running a ctx→ctx fn, but registration metadata the runtime resolves at context assembly — which also retires v1's cofx-ordering wart. The retained interceptor set is deliberately tiny: `path`, `unwrap`, and the `->interceptor` primitive for rolling your own.

**Effect-map shape.** Top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` shorthands fold into the `:fx` vector. `:db` is unchanged. If you've internalised "effects are a vector of `[id arg]` pairs" from [chapter 07](concepts/effects-and-coeffects.md), this is just that shape arriving where the shorthands used to be.

**Test harness rename.** `re-frame-test` becomes `re-frame.test-support`. The namespace moves; the test *bodies* usually don't change.

**View-rendering boundary.** Plain Reagent fns keep working when they render *under an established frame scope* — inside a `frame-provider`, they inherit the frame ambiently like any other call. What no longer works is a plain fn that dispatches or subscribes with *no* scope at all: that fails loudly with `:rf.error/no-frame-context` rather than the silent default-frame routing v1 allowed. `reg-view` adoption is opt-in modernisation (it injects frame-bound `dispatch` / `subscribe` and survives more boundaries cleanly), not a migration requirement — the requirement is simply that a frame scope exist above the view.

**Subscription input functions.** The two-function `reg-sub` form changes shape, not spirit. A v1 *signal function* returned live signals — `(rf/subscribe ...)` calls. A v2 *input function* returns plain **query vectors** (a vector *of* query vectors), and the runtime does the subscribing — which is what keeps the input function pure and the subscription graph inspectable without running the app. The mechanical tell is the bracket count on the single-input case: v2 wants `[[:item/by-id id]]`, not `[:item/by-id id]`. [Chapter 05](concepts/subscriptions.md) carries the full grammar and the why; the migration skill rewrites the common shapes and flags the rest.

**Paths and cache identity.** This one is mostly *good news*, with one habit to drop. v2 treats a **path** — a vector addressing a value, the thing you hand `get-in` / `assoc-in` — as a precise, framework-wide concept ([chapter 02 — paths are ordinary data](concepts/app-db.md)), and your existing plain vector paths carry over unchanged: `[:cart :items]` means exactly what it always did. Three small adjustments are worth knowing:

- **Plain vector paths stay valid.** Nothing to do. Where v2 *stores* a path for you (a flow's `:path`, a named declaration), it normalizes whatever sequence you gave it to a canonical vector — so a list or seq you passed for convenience comes back as a vector, but it's the same path.
- **Drop hand-rolled cache keys.** A v1 codebase that built its own cache-key strings — `(str "user-" id "-" tab)`, a `pr-str` of a params map, an MD5 of a query — should move that identity onto the **scoped resource key**: a `[cache-scope resource-id canonical-params]` triple, the shape [server-state resources](concepts/server-state.md) use. The data *is* the identity in v2, but **scope is part of it** — two reads share a cache entry only when their *whole* scoped key matches: same resource id, same canonical scope, *and* same canonical params (each canonical regardless of key order, with no string or hash to keep in sync). Don't migrate a params-only key as if params alone were the identity; folding scope back in is what keeps per-user / per-tenant caches from leaking into one another. Ad-hoc string/hash keys were always fragile (insertion order, host differences); the canonical-EDN scoped key makes the fragility go away.
- **Make `nil`-vs-missing explicit.** v2 distinguishes an absent key from a key present with value `nil`, and that distinction is part of a value's identity. Code that leaned on "absent and `nil` are the same" — a params map that sometimes omits a key and sometimes sets it `nil` — should pick one on purpose, because the two are now genuinely different facts (a different cache entry, a different identity). The fix is a `:params-schema` or a sentinel value, not an accident.

There's no automated rewrite for the cache-key habit — it's a judgement call about your own keying scheme — so the skill flags hand-built cache keys for review rather than guessing your intent.

**Ambient world reads in durable handlers.** This is the one category v2's *fold model* tightens, and a v1 codebase trips it everywhere. v1 let a handler reach straight into the world for a fact and write the result into state: `(js/Date.)` for a `:created-at`, `(random-uuid)` for an id, a v1 `:now` cofx injected via an interceptor entry, a boot handler reading `localStorage` to seed session state. v2 says: **a fact that decides a durable write must be a fact the ledger recorded**, or replay, time-travel, and SSR hydration all lie ([chapter 07 — recordable coeffects](concepts/effects-and-coeffects.md)). Every world fact a handler consumes is now **declared** with `:rf.cofx/requires` and delivered flat under its own owner-qualified id; the declaration's grade — recordable vs ambient — decides replay. The mapping is mechanical:

- **Durable clock reads** — `js/Date.now`, `(.now js/Date)`, an `interop/now-ms` — become a declared `:rf/time-ms`: add `:rf.cofx/requires [:rf/time-ms]` to the handler and read the flat `time-ms` key. The runtime stamps `:rf/time-ms` on every dispatch envelope and records it.
- **Generated ids** — `random-uuid` / host UUID calls feeding durable state — move to the event payload (mint at the dispatch site, `[:todo/add {:id (random-uuid)}]`, the preferred route) or, for ids minted inside the fold, become a declared recordable cofx whose app-registered supplier generates the value.
- **Random choices** — `rand` / `rand-int` / `rand-nth` whose result is written durably — become an app-registered recordable cofx (the supplier records produced *choices*, never seeds), declared and read flat like any other fact.
- **Durable storage / location reads** — `localStorage` / `sessionStorage` / `js/location` / `navigator` reads that initialise durable state — become router/host events or a `{:recordable? true}` cofx, rather than ambient reads at the write site.
- **Ambient `:now` cofx** — a v1 `(inject-cofx :now)` interceptor entry becomes a `:rf.cofx/requires [:rf/time-ms]` declaration, and if its value affects durable state that's exactly the recordable fact it needs to be — no separate registration, since `:rf/time-ms` is the framework's built-in. Reading only the recorded fact (never an ambient host read) means a scripted or replayed time returns exactly. Rename your durable call sites onto the declaration and read the flat `time-ms`. If you genuinely want a distinct app-named clock id, register a recordable supplier under that id rather than re-deriving inside a ctx wrapper:

```clojure
;; An app-named recordable clock fact — a value-returning supplier, recordable.
;; (Most code just declares :rf/time-ms directly; reach for this only if you
;;  want a domain-specific id rather than the framework's built-in.)
(rf/reg-cofx :app/now-ms
  {:recordable? true :doc "App-named durable wall clock."}
  (fn [] (.now js/Date)))
```

- **Custom cofx handlers lose the ctx wrapper.** This is the single mechanical rewrite that touches *every* `reg-cofx` a v1 app wrote, ambient or recordable. A v1 cofx handler took the interceptor context and threaded a value into it — `(fn [ctx arg] (assoc-in ctx [:coeffects :id] v))` (or `(fn [ctx] …)` for the no-arg case). v2 retires that ctx→ctx shape: a supplier is a plain **value-returning** function — `(fn [arg] v)` (or `(fn [] v)`) — and the runtime is what places the returned value into the coeffects map under the cofx's id ([chapter 07](concepts/effects-and-coeffects.md)). The matching consumer change is the same one as for `:now`: a v1 `[(rf/inject-cofx :viewport "main")]` interceptor entry becomes `:rf.cofx/requires [[:viewport "main"]]` registration metadata. Before and after for a generic ambient custom cofx:

```clojure
;; v1 — ctx→ctx handler, injected positionally
(rf/reg-cofx :viewport
  (fn [ctx] (assoc-in ctx [:coeffects :viewport] (.-innerWidth js/window))))
(rf/reg-event-fx :layout/measure
  [(rf/inject-cofx :viewport)]
  (fn [{:keys [db viewport]} _] ...))

;; v2 — value-returning supplier, declared via :rf.cofx/requires
(rf/reg-cofx :viewport
  {:doc "Ambient viewport width."}
  (fn [] (.-innerWidth js/window)))           ;; just the value; no ctx, no assoc-in
(rf/reg-event-fx :layout/measure
  {:rf.cofx/requires [:viewport]}             ;; declaration metadata, not an interceptor
  (fn [{:keys [db viewport]} _] ...))
```

  An ambient cofx like this stays ambient — no `:recordable?` — and the grade only changes the picture when the value feeds a durable write (the bullet above). The signature change itself is unconditional: it applies whether or not the fact is recordable, and whether the supplier takes call-site arguments (`(fn [k] v)`, declared `[[:viewport k]]`) or none.

A cofx that only measures a *diagnostic* or transient fact (a viewport width, a non-durable display preference) stays **ambient** — register it without `:recordable?` and it simply re-runs on replay; the rule bites only when the value feeds a durable write. The skill flags ambient durable reads for review rather than rewriting blind, because "does this read decide durable state?" is a judgement about your code's intent.

When a failure matches none of the above, the skill surfaces it for human review rather than guessing. That's the cardinal rule again, and it's what keeps an automated migration trustworthy: it does the things it's sure of and asks about the rest.

## The two changes worth understanding in depth

Most of the categories above are mechanical and the skill handles them while you watch. Two are different enough that understanding them pays off — because they're places where the v2 architecture isn't just renaming a thing, it's offering a genuinely better shape.

### HTTP folds onto `:rf.http/managed`

A v1 codebase that registered its own `:http` fx — or leaned on `re-frame-http-fx`, `re-frame-fetch-fx`, or a cousin — migrates onto `:rf.http/managed` (the subject of [chapter 10 — HTTP](concepts/http.md)). The skill recognises the shape and the rewrite is mostly mechanical:

1. Add the `day8/re-frame2-http` artefact and require it from the namespaces that issue requests.
2. Replace `[:http {:url ... :on-success ... :on-error ...}]` with `[:rf.http/managed {:request {:url ...} :on-success ... :on-failure ...}]`. Wire-shape keys (`:method`, `:url`, `:body`, `:headers`, `:params`) move *inside* `:request`.
3. Rename `:on-error` → `:on-failure`. The reply payload appends as the last argument; destructure `{:keys [value]}` for success, `{:keys [failure]}` for failure.
4. Adopt the closed `:rf.http/*` failure category set — code that branched on `(:status err)` becomes branching on `(:kind failure)`.

The skill applies 1–4 unprompted and stops at an optional step 5 (collapsing per-call success handlers into default reply addressing) for review. The reason this is more than a rename is that `:rf.http/managed` is a *managed* effect — it owns retries, aborts, double-submit suppression, the slow-loris timeout from [chapter 24](how-to/configure-dev-and-prod.md), and the eight-category failure taxonomy. Migrating onto it isn't just renaming your old fx; it's deleting a pile of hand-rolled request-lifecycle code that the framework now does correctly for you.

### `on-changes` becomes flows

This is the one v1 concept that maps onto something with a genuinely new name and a slightly different shape, so it earns the most ink. v1's `on-changes` interceptor said "when these in-paths change, compute and write to that out-path." v2's **flows** say the same thing — same compute-on-input-change semantics — but lifted *out* of the per-event interceptor chain and *into* the runtime: registered once, evaluated automatically right after the event handler (as the outermost `:after`, transforming the pending `:db` effect before it installs), and toggleable at runtime.

```clojure
(rf/reg-flow
  {:id     :rectangle/area
   :inputs [[:width] [:height]]      ;; vector of app-db paths
   :output (fn [w h] (* w h))         ;; pure: (in-1, in-2, ...) → output
   :path   [:area]                    ;; where the result is written
   :doc    "Rectangle area computed from :width and :height."})
```

Two things to internalise before you reach for them, because the easy mistake is to treat flows as a sub replacement and end up with a smell:

**Flows are a niche convenience, not a sub replacement.** They're for derived values that are part of the application's *state* — visible to other event handlers, surviving SSR hydration, covered by registered schemas, queryable from the app-db inspector. If the derived value is consumed *only by views*, the right tool is a subscription: lighter, sub-cache-native, no `app-db` write. A healthy re-frame2 app has dozens of subs and a *handful* of flows. Tens of flows is the framework telling you that you reached for the wrong tool.

**Flows can reach what `on-changes` couldn't.** `on-changes` was statically wired into specific events at registration time, so a derivation that should run *conditionally* — only while a wizard step is active, only when a feature gate is engaged, only in advanced mode — had no clean shape. Flows are runtime-registered and runtime-clearable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`; toggling one is an ordinary fx. So the migration sometimes *improves* the code it touches: a thing that was awkwardly always-on becomes cleanly conditional.

The rewrite itself is Type B — mechanically `(rf/on-changes f out-path & in-paths)` → `(rf/reg-flow {:id ... :inputs in-paths :output f :path out-path})`, but the agent stops to ask about the `:id` (it suggests `:legacy/<event-id>` as a default) and whether the flow should be conditional rather than always-on. An app with no `on-changes` sees no migration here at all.

### Registrations, app values, and realms

A v1 app registers everything at namespace load with `reg-*`, into one process-global registrar. v2 keeps that path working — `reg-*` still registers, now into the **default realm** ([chapter 21](concepts/app-db.md)) — so the mechanical migration changes nothing about how you register. Keep writing `reg-*`; you are using a realm without naming it, exactly as you've always used a frame without naming it.

You reach past that only when v2 gives you a shape v1 didn't have. The explicit constructors — `rf/module` and `rf/app` to describe a feature pack or program as a *value*, `rf/install!` to seat that value into a realm, `rf/realm` for an explicit realm — are the route for genuinely new structure: a packaged feature you install and dispose as a unit, a per-tenant or multi-program process. They're public from `re-frame.core` today, but they are a *refinement you grow into*, not a step the migration forces.

What `install!` lowers today is a **subset** worth knowing before you package a module: it wires the core registrar kinds — `:event`, `:sub`, `:fx`, `:cofx`, and `:frame` — and **refuses loudly** (`:rf.error/unsupported-descriptor-kind`) on `:route` / `:flow` / `:resource` / `:mutation` and the other richer kinds, whose install lowering is a later slice ([chapter 21](concepts/app-db.md)). So migrate flows, routes, and resources as ordinary `reg-flow` / `reg-route` / resource registrations into the default realm — that's their canonical home today — and reserve a *module* for the event/sub/fx/cofx/frame surface it supports. (A multi-tenant process is *registrar*-isolated per realm today; a second runnably-dispatching, routed program per realm and a second substrate root are the *direction*, not yet shipped — see [chapter 21](concepts/app-db.md).)

One accident to avoid the day you do reach for a module: do **not** register the same id *both* through `reg-*` sugar *and* in a module you install into the same realm. The runtime catches it as a same-id collision and fails loudly rather than silently merging ([chapter 21](concepts/app-db.md)) — the first error a migrating app meets when it half-adopts modules. Pick one source per id. And the reserved-vocabulary spelling is `rf/realm`, never `rf/runtime`.

## The devtools moved house

One last orientation point that trips people who lived in v1's tooling. `re-frame-10x` — the v1 devtools panel — has been renamed and *reimplemented* as **Xray** (`day8/re-frame2-xray`). The word to weight is *reimplemented*: Xray is not 10x ported to v2, it's a from-scratch build against re-frame2's own trace bus and epoch-history surfaces. The mental model carries over completely — events, subs, app-db diff, time-travel are all there — but the wiring underneath is new. If your v1 project depended on 10x during development, the v2 equivalent is Xray, and [the Xray tutorial](../xray/index.md) is where you meet it.

That's the migration in one read: the architecture is the same architecture, the sweep is automated, the handful of genuinely-new shapes (managed HTTP, flows) are improvements you'll be glad to adopt rather than taxes you'll resent paying, and the one rule that keeps all of it honest is *don't invent migration rules* — the tool does what it's sure of and asks about the rest. When the migration settles, [chapter 26](reference.md) points at how to operate from there.

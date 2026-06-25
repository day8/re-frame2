# 25 - From re-frame v1

You have a re-frame v1 app and a migration to plan, and the real question on your mind is probably "how big a deal is this?" The reassuring answer: most of your code is already v2 code. This chapter is the map. It shows you the one tool that does the work, then walks the handful of things that genuinely changed — so that when you read a migration diff you can slot every line into a category instead of squinting at it.

We'll build up in three moves. First, the good news and the tool that drives the sweep. Then the bounded set of mechanical renames. Then the two or three places where v2 tells a genuinely different story than v1 did — and those turn out to be improvements you'll be glad to adopt.

## The good news first

The bones are identical. v1's signature shape — the [**event cascade**](glossary.md#event-cascade), the one-way run a dispatched [event](glossary.md#event) sets off as it falls through the *six dominoes* (event handling, effects, coeffects, the [app-db](glossary.md#app-db) commit, and back out to the subscriptions that views read) — is the same shape in v2. Walk it piece by piece and nothing has moved:

- [Events](glossary.md#event) — the data describing what happened — are still data.
- [Event handlers](glossary.md#event-handler) are still pure functions of state.
- [Subscriptions](glossary.md#subscription) are still derivations off a single [app-db](glossary.md#app-db) (your app's whole state, held in one map).

The opinionated stance hasn't moved either: one source of truth, data over APIs over syntax, immutable values and stable contracts. v2 is v1's architecture with new capabilities grown on top.

That's why your v1 code reads as v2 code on the first pass. A `reg-sub` is a `reg-sub`. A [hiccup](glossary.md#hiccup) view — Clojure's vectors-as-HTML notation, `[:div ...]` — is still a hiccup view. The migration is not a rewrite — it's a *sweep*: a bounded set of mechanical renames, a smaller set of judgment calls, and a few new shapes you choose to adopt. The framework counts "40-plus rules," and that number sounds alarming, but the vast majority are find-and-replace, and a tool does them for you.

> **Coming from a React 17→18 upgrade?** Think of this less like that — where concurrent rendering shifted runtime behaviour under you — and more like flipping on TypeScript's `strict` flag. The semantics you relied on are almost all intact; v2 mostly stops *silently swallowing* the things v1 let slide (an ambient frame, an unrecorded clock read) and asks you to say them out loud. Strictness, not rearchitecture — with one genuine runtime-behaviour exception, the run-to-completion dispatch change flagged below.

## The one tool: don't do this by hand

The migration is automated, and you should keep it that way — do not hand-migrate anything larger than a toy. A Claude Code skill ships in this repo, [`skills/re-frame-migration/`](../../skills/re-frame-migration/), and its whole job is to drive the sweep. It walks six phases — orient, bump, sweep, verify, optional modernisations, report — applying the mechanical rewrites unprompted and *stopping* at every judgment call to ask you first. The skill calls the mechanical rewrites **Type A** and the judgment calls **Type B**. You'll see those two words throughout this chapter.

The workflow is four steps:

1. Open a fresh Claude Code session at the root of your v1 project.
2. Paste the kickoff prompt from [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md). The session loads the skill and walks the phases autonomously.
3. Answer questions at the Type B checkpoints — the agent explains the risk and waits for your call before rewriting.
4. Run your test suite. The agent re-verifies and produces a migration report.

The rest of this chapter is orientation. It gives you the mental model of what the skill is doing, so that when you read a diff at step 3 and ask "what kind of thing am I looking at?", you'll have a category to slot it into. The exhaustive rule list lives in the skill; this is the *why* behind it.

> **The one rule that keeps it honest: don't invent migration rules.** The skill's cardinal rule is exactly that — if a failure doesn't match a known shape, it surfaces it for human review instead of guessing. That's the load-bearing safety property of the whole sweep: it does the things it's *sure* of, and asks about the rest. You'll see this rule invoked again and again below.

## Step one of the sweep: the deps

re-frame2 is **pay-as-you-go**: capabilities ship as separate artefacts, so unused ones never bundle. That one fact shapes the whole deps migration:

1. **Swap the core coord.** Remove `re-frame/re-frame`. Add `day8/re-frame2`.
2. **Add a substrate adapter** for your view library — `day8/re-frame2-reagent` if you're on Reagent (and bump Reagent to v2, which the reference targets), or the matching UIx/Helix adapter if you've already moved off Reagent. ([Chapter 22 — Adapters](how-to/use-uix-helix-or-slim.md) is the [substrate](glossary.md#substrate) story in full.)
3. **Add per-feature artefacts only for features you actually use.** Don't add them all "to be safe" — the skill tells you which ones the codebase trips. The split is `day8/re-frame2-{machines, flows, routing, http, ssr, schemas, epoch}`, and an app that doesn't use flows doesn't carry flow code.
4. **Don't bump anything else in the same change.** Keep React, shadow-cljs, and the rest on their current versions until the migration settles. A migration that is also a dependency upgrade is two bugs wearing one diff — separate failure modes are far easier to debug separately.

The skill handles every part of this. The list is here so you know what's coming.

> **Gotcha — two things are forced *before* you start, and the skill checks them first.** The "settle the migration before upgrading anything else" advice has two carve-outs that aren't optional. **First, a React-19 / Reagent-2 floor.** re-frame2's adapters target React 19 and the Reagent bridge runs on Reagent 2.x, so a React-17/18 project bumps as part of the same change — and if a component library you depend on has no React-19 build, that's a genuine go/no-go blocker the skill surfaces up front (wait for a release, replace it, vendor a patch, or verify it empirically under forced React 19), not a surprise inside a failed compile. **Second, certain v1 add-ons stop compiling the instant re-frame2 is on the classpath.** `http-fx`, `async-flow-fx`, `undo`, and `forward-events-fx` all reference `re-frame.core/console`, which v2 removed with no shim — so the build fails with an unresolved `re-frame.core/console` until each is removed or converted (`http-fx` onto managed HTTP, covered below; `async-flow-fx`'s orchestration sequences onto state machines via `reg-machine`; `undo` re-implemented on app-db snapshots or epoch time-travel). "Drop in re-frame2 and modernise the add-ons later" is therefore not available: you must act on them at the compile gate, even if converting fully comes later.

> **Coming from npm's all-or-nothing bundles?** The closest mental model is tree-shaking made explicit. Instead of pulling one fat `re-frame` package and trusting the bundler to drop what you don't import, you pull only the artefacts whose features you use. The payoff is that bundle isolation is a contract, not a hope: code you didn't add can't sneak into production.

## The mechanical renames

These are the broad shapes of breakage — the high-volume, deterministic rewrites the skill applies while you watch. We'll take them roughly in order of how often they fire, simplest first.

### One event registration form

This is the highest-volume rewrite, because `reg-event-db` is the most common registration in nearly every v1 app — and v2 doesn't have it. v1 gave you three ways to register an [event handler](glossary.md#event-handler): `reg-event-db` (the handler takes the current db and returns the new db), `reg-event-fx` (the handler takes a [coeffects](glossary.md#coeffect) map — facts the runtime gathered for you, the db among them — and returns an [effect map](glossary.md#effect-map) describing what should happen), and `reg-event-ctx` (the raw interceptor context). v2 collapses all three to a single [**`reg-event`**](glossary.md#register), shaped exactly like the old `reg-event-fx`: every handler takes the coeffects map and returns the closed effect map (`{:db … :fx […]}`).

The win is that there's no longer a db-only form that breaks the moment a handler needs an effect or a coeffect — adding the world becomes adding a key to a map you already return, not converting to a different registration.

The mapping is deterministic, which is why it's codemod-able:

```clojure
;; v1                                  ;; v2
(rf/reg-event-fx ID handler)       =>  (rf/reg-event ID handler)         ;; rename only
(rf/reg-event-db ID (fn [db EV] BODY))
                                   =>  (rf/reg-event ID (fn [{:keys [db]} EV] {:db BODY}))
```

`BODY` always evaluates to the new db (that *is* the `reg-event-db` contract), so wrapping it `{:db BODY}` is mechanical regardless of how complex it is. A first-class, tested codemod ships in [`migration/from-re-frame-v1/codemod/`](../../migration/from-re-frame-v1/codemod/README.md) (rule M-73) and the migration skill runs it for you. It renames `-fx` forms, rewrites simple `-db` forms, and **flags** two cases for human review rather than guessing:

- a `-db` handler whose body can return `nil` (under v2 a bare `nil` is a clean no-op and `{:db nil}` coerces to `{:db {}}`, so you choose the reading you want), and
- any `reg-event-ctx` (withdrawn from the public surface — full-context work moves to an [interceptor](glossary.md#interceptor) registered with `reg-interceptor` and referenced by id). A stale call doesn't limp along: it [fails loud](glossary.md#fail-loud-not-silent) with `:rf.error/reg-event-ctx-removed`, naming the replacement.

For the common pure-state handler, a nice habit on the way through is to lift the body into a plain `(defn step [db] …)` and register `(fn [{:keys [db]} _] {:db (step db)})` — the state transition stays bare and testable, the handler stays one line.

> **Coming from Redux Toolkit?** This is the same instinct as `createSlice` collapsing reducer cases into one place. v1's `reg-event-db` was the convenient form that turned awkward the instant you needed a thunk; v2's single `reg-event` is the always-honest form — a handler is a function of the world that returns a *description* of the next world. You return `{:db …}` the way an RTK reducer mutates `state`, except it stays a pure value.

### Registrar imports

Some v1 code requires `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`, or `re-frame.alpha` directly. That reached past the front door, and v2 closes it. The single-import contract is `(:require [re-frame.core :as rf])`. Direct access to `re-frame.db/app-db` was always off-contract and is now firmly so. The accessor is `(rf/app-db-value frame-id)`, which names the [frame](glossary.md#frame) and returns a plain map.

> **Going deeper.** This tightening is the same reason chapter 18's frames work at all: when there can be N isolated app-db instances, "the global `app-db` atom" stops being a coherent thing to reach for. The contract *has* to be a function call that names *which* frame you mean — [identity is carried, not found](glossary.md#frame-identity-is-carried-not-found), an argument rather than ambient global state.

### Effect-map shape

Top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` shorthands fold into the `:fx` vector — an [effect](glossary.md#effect) being a description of a side-effect the runtime carries out for you. `:db` is unchanged. If you've internalised "effects are a vector of `[id arg]` pairs" from [chapter 07](concepts/effects-and-coeffects.md), this is just that shape arriving where the shorthands used to be.

> **Gotcha — `:dispatch` is the one place the *timing* genuinely changes.** This is the rare migration category that isn't strictness — it's a real behavioural shift, so it's flagged for you to think about, never rewritten blind. v2 [drains run-to-completion](glossary.md#drain--run-to-completion): every event dispatched *during* a handler (a `:dispatch` effect, or a bare `dispatch` from inside a handler body) drains to a fixed point **before any view re-renders**. In v1 those re-dispatches landed on a later tick, so a view could render the intermediate state in between. The vast majority of code doesn't notice. What does: an animation/wizard chain that *relied* on a flash of intermediate render between steps, and any test that peeked at the router queue after a dispatch (it's already drained — empty). Reframe such tests around the *resulting* app-db state or the effects observed, not queue contents. A pathologically long synchronous chain can also trip the per-frame drain-depth limit (default 100) with a `:drain-depth-exceeded` error; raise it with `{:drain-depth N}` on the frame, or break the chain with `:dispatch-later`.

### Framework keywords move to the `:rf/*` root

v2 gathers every framework-owned keyword under a single reserved root, `:rf/*` (and its sub-namespaces `:rf.machine/*`, `:rf.route/*`, `:rf.nav/*`, …). So v1's `:re-frame/*`, `:machine/*`, `:route/*`, `:nav/*`, and `:registry/*` framework keywords rename to their `:rf.*` equivalents — a closed, mechanical table the skill applies. The flip side is a judgment call it *flags* rather than rewrites: any of *your own* registrations or app-db keys that happen to sit under a now-reserved namespace (a user `:rf/…` event id, a `:route` slice you own with a third-party router) collide with framework territory and need renaming to your own feature prefix — unless you're deliberately overriding a documented extension point. [`spec/Conventions.md`](../../spec/Conventions.md) is the reserved-namespace catalogue.

### Subscription input functions

The two-function `reg-sub` form changes shape, not spirit. The first function declares what this [subscription](glossary.md#subscription) depends on. In v1 it *returned live signals* — it called `(rf/subscribe ...)` itself and handed back the running subscriptions. In v2 it instead returns plain data: a vector listing the [**query vectors**](glossary.md#query-vector) it wants (each query vector being a `[:sub-id arg …]` request), and the runtime does the actual subscribing. That keeps the input function pure and the subscription graph inspectable without running the app. The mechanical tell is the bracket count on the single-input case: v2 wants `[[:item/by-id id]]` — a one-element vector *containing* the query vector — not the bare `[:item/by-id id]`. [Chapter 05](concepts/subscriptions.md) carries the full grammar and the why. The migration skill rewrites the common shapes and flags the rest.

> **Gotcha — this one fails *silently* until the sub is first read.** A v1 signal-function `reg-sub` still *registers* cleanly under v2 (it parses as a parametric sub), so the compile says nothing. The mismatch surfaces only at the *first* `subscribe`/deref of that sub, which raises `:rf.error/sub-input-fn-bad-return` — the input function returned live reactions where the runtime wanted a vector of query vectors. A view that swallows the error just renders nothing. Because the build is no help here, sweep every two-function `reg-sub` up front rather than waiting to trip over it at runtime, and smoke-test that each migrated sub actually derefs to a value.

> **Going deeper.** In v1 the signal fn *ran* the subscription and handed back a live `Reaction` — it had to execute to produce its result. In v2 it hands back *data describing* which subscriptions it wants, and the runtime resolves them. A pure function returning a vector-of-vectors can be read, diffed, and graphed without ever mounting the app — which is exactly how [Xray](glossary.md#xray)'s dependency view draws the subscription DAG. The extra bracket pair is the seam where "do it" became "describe it."

### Removed surfaces, interceptors, and the test rename

A handful of v1 affordances are gone, each with a defined replacement. None is a capability loss — they're consolidations: the same job done through one shape instead of several.

- `dispatch-with` / `dispatch-sync-with` fold into a two-arg `dispatch` with an opts map.
- `reg-global-interceptor` is gone because interceptors are frame-scoped in v2 — register the behaviour with `reg-interceptor` and reference it from a frame's `:interceptors`.
- `reg-sub-raw` gives way to `reg-sub` or the substrate adapter.
- The `^:flush-dom` event metadata becomes `:dispatch-later {:ms 0}`.
- `re-frame-test` becomes `re-frame.test-support` — the namespace moves; the test bodies usually don't change.
- A `reg-fx` / `reg-cofx` that touches a browser global (`js/window`, `js/localStorage`, `js/document`) now needs an explicit `:platforms #{:client}`. v2 defaults effects to *universal* — runnable JVM-side, which is what lets the same handlers run under [SSR](glossary.md#ssr) — so a browser-only side effect must say so, or it will try to fire during a server render and throw. A purely client-rendered app never trips this; it bites the moment you adopt SSR.

Six v1 *interceptors* are gone too — `debug`, `trim-v`, `on-changes`, `enrich`, `after`, and `inject-cofx` — each because v2 grew a better-shaped answer to the problem it solved. `debug` is subsumed by the [trace stream](glossary.md#trace-stream) ([chapter 16](concepts/observability.md)). `trim-v` is unnecessary because the canonical event shape is consistent now. `enrich` and `after` are replaced by [flows](glossary.md#flow) and [schemas](glossary.md#schema). `on-changes` becomes flows (its own section below). `inject-cofx` is replaced by the `:rf.cofx/requires` declaration ([chapter 07](concepts/effects-and-coeffects.md)). The retained standard set is deliberately tiny: exactly one framework interceptor, `path`, referenced as `[:rf.interceptor/path <path-vector>]`. For anything else you register your own with `reg-interceptor` and reference it by id; chains carry references, not inline interceptor values. [Interceptors](concepts/interceptors.md) is the full model.

> **Going deeper.** Notice the pattern in *why* each interceptor left: `enrich` / `after` were "compute or assert a derived thing after the handler," which is now declarative (flows, schemas) and therefore tooling-visible; `inject-cofx` was a positional ctx→ctx function, now registration metadata the runtime resolves at context assembly (which also retires v1's cofx-ordering wart). v1's standard `unwrap` is gone for the same reason — ordinary handler destructuring covers it, and the `:event` coeffect stays the stable original vector for tracing and replay. The through-line: v2 prefers *declared facts the runtime can see* over *imperative entries in a chain*.

## The change most likely to bite: establish a root frame

This is the one mechanical category that earns its own section, because it's the change most likely to bite a v1 codebase. [Chapter 18](concepts/frames.md) is the full story.

A [**frame**](glossary.md#frame) is the isolated runtime context an operation runs under — it carries which app-db instance you're talking to. v1 gave you an ambient global `app-db` that every bare `dispatch` and `subscribe` resolved against. v2 does **not**. [Frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found): an operation reads its frame from the scope it runs under, and the runtime never synthesises one from absence. So a v1 app that calls `(rf/dispatch [:boot])` at top level with no frame established now fails loud with `:rf.error/no-frame-context`.

The fix is one line of ceremony at your root: register a frame and scope your tree to it.

```clojure
(rf/reg-frame :app/main {:initial-events [[:rf/set-db {}]   ;; seed app-db (frames always start {})
                                          [:boot]]})        ;; then your boot event(s), in order

(rdc/render root
  [rf/frame-provider-existing {:frame :app/main}
   [app-root]])
```

Inside that tree, every bare `dispatch` / `subscribe` you already wrote works unchanged — the frame rides along ambiently. Only *rootless* calls need attention: async callbacks that lost their scope, and top-level boot code with no provider. Those are exactly the wrong-frame footguns v1 used to swallow silently. The skill rewrites bare top-level call sites into a root provider and flags async callbacks for an explicit capture (next).

> **No `:initial-db` key, and that's deliberate (EP-0027).** A v1 reflex is to reach for an `:initial-db` / `:db` config key to seed initial state. v2 doesn't have one. **Every frame starts with `app-db = {}`**, and seeding it is *itself an event*: make `[:rf/set-db {…}]` the first step of `:initial-events` (it's a built-in handler). That vector is dispatched synchronously, in order, right after the frame is created — so a v1 `(reg-event-db :initialise-db (fn [_ _] default-db))` plus a mount becomes one `[:rf/set-db default-db]` step, or your existing initialise event listed after the seed. The payoff: "events are the unit of state change" holds with no exceptions — initial state is built by the same dispatch pipeline that handles every later change, which is exactly why time-travel can rewind *to* the initial state. (v1's `:on-create` callback hook is likewise gone — setup is events, not a callback.)

> **Gotcha — a wholesale `{:db fresh-map}` reset is now *safe*, but strip any `:rf/runtime` key.** A common v1 shape is an `:initialize-db` / `:app/reset` handler that returns a whole fresh app-db — and in v1 that could clobber framework state stashed in the same map. Under v2 it can't: the framework keeps its own state in a separate [runtime-db](glossary.md#runtime-db) partition that a `:db` return cannot reach, so the wholesale-replace footgun is structurally gone — replace away. The one residual hazard is a fresh map that still carries the retired `:rf/runtime` app-db root (a v1-shaped runtime stash). That throws `:rf.error/legacy-runtime-root` on dispatch — loud, always-on, in production too. The fix is to delete the key; framework state isn't yours to seed.

### The async-callback fix: capture a frame handle

The capture is one line, and it's worth seeing concretely because it's the most common Type B fix in a real codebase. `(rf/frame-handle)` snapshots the *current* frame and hands back a [**frame-handle**](glossary.md#frame-handle) — a small bundle with the keys `:frame`, `:dispatch`, `:dispatch-sync`, and `:subscribe`, whose `dispatch` always targets the frame it captured, even after the render scope that produced it has unwound. So you grab the handle while the scope is still live (during render, or inside an event handler), close over it, and call its `:dispatch` from the callback:

```clojure
;; WRONG in v2 — the bare dispatch fires after the scope unwound → :rf.error/no-frame-context
(defn poll! []
  (js/setTimeout #(rf/dispatch [:tick]) 1000))

;; RIGHT — capture the handle while the scope is live, dispatch through it later
(defn poll! []
  (let [{:keys [dispatch]} (rf/frame-handle)]      ;; snapshot now, on the current frame
    (js/setTimeout #(dispatch [:tick]) 1000)))      ;; the callback targets the captured frame
```

You can also pass `(rf/frame-handle frame-id)` to capture a *named* frame rather than the ambient one. Read its app-db with `(rf/app-db-value (:frame h))` — the handle carries operations, not state.

> **Coming from React Context?** `frame-provider-existing` *is* a context provider, and the "no-frame-context" error is the exact analogue of calling a hook outside its provider and getting `undefined` back from `useContext` — except v2 throws instead of silently handing you a stale default. The one wrinkle React people already know: context doesn't cross an async boundary on its own. A `setTimeout` callback in React loses nothing because closures capture; a re-frame2 callback that fires *after* its render scope unwound needs to have captured a `frame-handle` while the scope was live. Same lesson, louder failure.

### Views render under a frame scope

Plain Reagent fns keep working when they render under an established frame scope. Inside a `frame-provider`, they inherit the frame ambiently like any other call. What no longer works is a plain fn that dispatches or subscribes with *no scope at all* — that fails loud with `:rf.error/no-frame-context` rather than the silent default-frame routing v1 allowed. [`reg-view`](glossary.md#view) adoption is opt-in modernisation, not a migration requirement; it injects frame-bound `dispatch` / `subscribe` and survives more boundaries cleanly. The requirement is simply that a frame scope exist above the view.

## Paths and cache identity: mostly good news, one habit to drop

v2 treats a **path** — a vector addressing a value, the thing you hand `get-in` / `assoc-in` — as a precise, framework-wide concept ([chapter 02](concepts/app-db.md)). Your existing plain vector paths carry over unchanged: `[:cart :items]` means exactly what it always did. Three small adjustments are worth knowing:

- **Plain vector paths stay valid.** Nothing to do. Where v2 stores a path for you (a flow's `:output-path`, a named declaration), it normalizes whatever sequence you gave it to a canonical vector — so a list or seq you passed for convenience comes back as a vector, but it's the same path.
- **Drop hand-rolled cache keys.** A v1 codebase that built its own cache-key strings — `(str "user-" id "-" tab)`, a `pr-str` of a params map, an MD5 of a query — should move that identity onto the **scoped resource key**: a `[cache-scope resource-id canonical-params]` triple, the shape [server-state resources](concepts/server-state.md) use. Two reads share a cache entry only when their whole scoped key matches: same [resource](glossary.md#resource) id, same canonical [scope](glossary.md#scope), same canonical params (each canonical regardless of key order). Don't migrate a params-only key as if params alone were the identity — folding scope back in is what keeps per-user and per-tenant caches from leaking into one another.
- **Make `nil`-vs-missing explicit.** v2 distinguishes an absent key from a key present with value `nil`, and that distinction is part of a value's identity. Code that leaned on "absent and `nil` are the same" should pick one on purpose: the two are now genuinely different facts — a different cache entry, a different identity. The fix is a `:params-schema` or a sentinel value, not an accident.

There's no automated rewrite for the cache-key habit. It's a judgement call about your own keying scheme, so the skill flags hand-built cache keys for review rather than guessing your intent.

> **Coming from TanStack Query?** You already think in query keys, so this will feel native: the scoped resource key *is* a query key, and the same rule applies — two `useQuery` calls share a cache entry only when their keys are structurally equal. v2's one addition is that scope (which user, which tenant) is a first-class segment of the key, not something you splice into a string by hand. The `nil`-vs-missing distinction is the bit TanStack leaves to you and v2 makes explicit: `{tab: null}` and `{}` are *different* keys, so decide which one you mean.

## The deepest change: ambient world reads in durable handlers

First, the idea that makes this section make sense. [Time-travel](glossary.md#time-travel) ([chapter 16](concepts/observability.md)) leans on one guarantee: replaying the recorded event stream from the start rebuilds the *exact same* app-db. That only holds if a handler is a clean fold over the stream — state-in, state-out, no peeking at the outside world. (A *fold* here is the functional-programming sense: `reduce` over the events, each folded into the running app-db.)

This is the one category that genuinely *tightens* to protect that guarantee, and a v1 codebase trips it everywhere — so it's worth slowing down for. v1 let a handler reach straight into the world for a fact and write the result into state: `(js/Date.)` for a `:created-at`, `(random-uuid)` for an id, a v1 `:now` cofx injected via an interceptor entry, a boot handler reading `localStorage` to seed session state. Each of those is a peek at the outside world — and the outside world doesn't replay the same way twice.

So v2 draws a bright line: **a fact that decides a durable write must be a fact the ledger recorded** ([chapter 07 — recordable coeffects](concepts/effects-and-coeffects.md)). Every world fact a handler consumes is now declared with `:rf.cofx/requires` and delivered flat under its own owner-qualified id. The declaration's *grade* — [recordable vs ambient](glossary.md#recordable-vs-ambient-coeffects): recordable (the runtime captures the value into the ledger, so replay re-feeds it) versus ambient (re-read fresh on every replay) — decides what happens on replay. The mapping is mechanical:

- **Durable clock reads** — `js/Date.now`, `(.now js/Date)`, an `interop/now-ms` — become a declared `:rf/time-ms`: add `:rf.cofx/requires [:rf/time-ms]` to the handler and read the flat `time-ms` key. The runtime stamps `:rf/time-ms` on every dispatch envelope and records it. (It is the framework's one built-in recordable fact.)
- **Generated ids** — `random-uuid` / host UUID calls feeding durable state — move to the event payload (mint at the dispatch site, `[:cart/add-item {:id (random-uuid) :sku "BK-1"}]`, the preferred route) or, for ids minted inside the fold, become a declared recordable cofx whose app-registered supplier generates the value.
- **Random choices** — `rand` / `rand-int` / `rand-nth` whose result is written durably — become an app-registered recordable cofx (the supplier records produced choices, never seeds).
- **Durable storage / location reads** — `localStorage` / `sessionStorage` / `js/location` / `navigator` reads that initialise durable state — become router/host events or a `{:recordable? true}` cofx, rather than ambient reads at the write site.
- **Ambient `:now` cofx** — a v1 `(inject-cofx :now)` interceptor entry becomes a `:rf.cofx/requires [:rf/time-ms]` declaration. Reading only the recorded fact (never an ambient host read) means a scripted or replayed time returns exactly. If you genuinely want a distinct app-named clock id, register a recordable supplier under that id:

```clojure
;; An app-named recordable clock fact — a value-returning supplier, recordable.
;; (Most code just declares :rf/time-ms directly; reach for this only if you
;;  want a domain-specific id rather than the framework's built-in.)
(rf/reg-cofx :app/now-ms
  {:recordable? true :doc "App-named durable wall clock."}
  (fn [] (.now js/Date)))
```

One mechanical rewrite touches *every* `reg-cofx` a v1 app wrote, ambient or recordable: **custom cofx handlers lose the ctx wrapper.** A v1 cofx handler took the interceptor context and threaded a value into it — `(fn [ctx arg] (assoc-in ctx [:coeffects :id] v))`. v2 retires that ctx→ctx shape. A supplier is a plain **value-returning** function — `(fn [arg] v)` (or `(fn [] v)`) — and the runtime places the returned value into the coeffects map under the cofx's id. The matching consumer change: a v1 `[(rf/inject-cofx :viewport "main")]` interceptor entry becomes `:rf.cofx/requires [[:viewport "main"]]` registration metadata:

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
(rf/reg-event :layout/measure
  {:rf.cofx/requires [:viewport]}             ;; declaration metadata, not an interceptor
  (fn [{:keys [db viewport]} _] ...))
```

(`inject-cofx` itself doesn't quietly disappear, either — a stale call fails loud with `:rf.error/inject-cofx-removed`, naming `:rf.cofx/requires` as the replacement.)

The signature change is *unconditional* — it applies whether or not the fact is recordable, and whether the supplier takes call-site arguments (`(fn [k] v)`, declared `[[:viewport k]]`) or none. A cofx that only measures a diagnostic or transient fact (a viewport width, a non-durable display preference) stays **ambient**: register it without `:recordable?` and it simply re-runs on replay. The `:recordable?` grade bites only when the value feeds a *durable* write.

> **Why this matters.** This is the deepest "why" on the page, and it's the replay promise from the top of this section, made concrete. A handler that secretly reads `(js/Date.)` and writes it to state breaks that promise the instant you replay: the clock moved, so the rebuilt app-db no longer matches. The bright line — a fact that decides a *durable* write must come through a *recorded* coeffect, never an ambient host read — is exactly what keeps the replay faithful. A diagnostic read that never lands in durable state stays ambient and re-runs freely; it can't poison anything. The skill flags these for review rather than rewriting blind, because "does this read decide durable state?" is a judgement about your code's intent, not something a tool can read off the syntax — the cardinal rule again, doing the things it's sure of and asking about the rest.

## Two changes worth understanding in depth

Most of the categories above are mechanical, and the skill handles them while you watch. Two are different enough that understanding them pays off. They're places where v2 isn't renaming a thing — it's offering a genuinely better shape.

### HTTP folds onto `:rf.http/managed`

A v1 codebase that registered its own `:http` fx — or leaned on `re-frame-http-fx`, `re-frame-fetch-fx`, or a cousin — migrates onto the [`:rf.http/managed`](glossary.md#managed-http) effect ([chapter 10 — HTTP](concepts/http.md)). The skill recognises the shape, and the rewrite is mostly mechanical:

1. Add the `day8/re-frame2-http` artefact and require it from the namespaces that issue requests.
2. Replace `[:http {:url ... :on-success ... :on-error ...}]` with `[:rf.http/managed {:request {:url ...} :on-success ... :on-failure ...}]`. Wire-shape keys (`:method`, `:url`, `:body`, `:headers`, `:params`) move *inside* `:request`.
3. Rename `:on-error` → `:on-failure`. The [reply map](glossary.md#reply-map) appends as the last argument; destructure `{:keys [value]}` for success, `{:keys [failure]}` for failure.
4. Adopt the closed `:rf.http/*` failure category set — code that branched on `(:status err)` becomes branching on the failure's `:kind`.

There are exactly **eight** failure categories, in two groups. Five are *retryable* (a re-issue can plausibly change the outcome): `:rf.http/transport` (network / DNS / connection-reset), `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/http-4xx`, and `:rf.http/http-5xx`. Three are *non-retryable by construction*: `:rf.http/aborted` (cancelled or superseded — abort always wins), `:rf.http/decode-failure` (a 2xx whose body failed schema validation / JSON parse / your decode fn threw), and `:rf.http/accept-failure` (your `:accept` normaliser projected a structurally-valid 200 to a domain `{:failure …}`). Putting a non-retryable category in `:retry :on` fails loud at the dispatch site with `:rf.error/http-bad-retry-on` — the runtime refuses to carry a retry policy that can never fire.

A v1 status-code `cond` becomes a `case` over these named kinds:

```clojure
(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (assoc-in db [:article :error]
           (case (:kind failure)
             :rf.http/timeout        "The server took too long — try again."
             :rf.http/http-4xx       "That article doesn't exist."
             :rf.http/http-5xx       "Something broke on our end."
             :rf.http/decode-failure "The server sent something we couldn't read."
             "Couldn't load the article."))}))   ;; closed set → a total case with one default
```

The skill applies steps 1–4 unprompted and stops at an optional step 5 (collapsing per-call success handlers into default reply addressing) for review. This is more than a rename because `:rf.http/managed` is a *managed* effect: it owns retries, aborts, double-submit suppression, the slow-loris timeout from [chapter 24](how-to/configure-dev-and-prod.md), and the eight-category failure taxonomy. Migrating onto it deletes a pile of hand-rolled request-lifecycle code that the framework now does correctly for you.

> **Coming from TanStack Query or RTK Query?** This is the same trade you made when you stopped writing raw `fetch` and `useState` / `useEffect` lifecycles by hand. A managed effect owns the retry, the in-flight dedup, the abort-on-unmount, the timeout — the unglamorous edge cases you keep re-implementing slightly wrong. The one re-frame-flavoured difference: failures arrive as a *closed* category set (`(:kind failure)`), so you `case` over named outcomes instead of pattern-matching HTTP status integers and praying the server is consistent.

### `on-changes` becomes flows

This is the one v1 concept that maps onto something with a genuinely new name and a slightly different shape. v1's `on-changes` interceptor said "when these in-paths change, compute and write to that out-path." v2's [**flows**](glossary.md#flow) say the same thing — same compute-on-input-change semantics — but the wiring moves. In v1 you bolted `on-changes` onto each event's interceptor chain by hand; in v2 you register a flow once with the runtime, and it runs the derivation automatically after *every* event handler, before the new `:db` lands. It's also toggleable at runtime, which `on-changes` never was.

```clojure
(rf/reg-flow
  {:id     :editor/word-count
   :inputs [[:editor :title] [:editor :body]]   ;; vector of app-db paths
   :derive (fn [title body]                       ;; pure: (in-1, in-2, ...) → output
             (count (re-seq #"\S+" (str title " " body))))
   :output-path [:editor :word-count]             ;; where the result is written
   :doc    "Live word count of the article being edited."})
```

Internalise one thing before you reach for them, because the easy mistake is to treat flows as a sub replacement and end up with a smell.

**Flows are a niche convenience, not a sub replacement.** They're for derived values that are part of the application's *state*: visible to other event handlers, surviving SSR hydration, covered by registered schemas, queryable from the app-db inspector. If the derived value is consumed only by views, the right tool is a [subscription](glossary.md#subscription) — lighter, sub-cache-native, no `app-db` write. A healthy re-frame2 app has dozens of subs and a handful of flows. Tens of flows means you reached for the wrong tool.

> **The litmus test.** Ask: *does anything other than a view need this value?* If only views read it → subscription. If an event handler reads it, or it must be in app-db so SSR hydrates it, or a schema asserts on it → flow. It's the same line a spreadsheet draws between a derived cell other cells reference (a flow) and a value you only glance at on screen (a sub). When in doubt, reach for a sub; flows are the exception, not the default. (The [where state lives](glossary.md#the-four-homes-where-state-lives) router is the full version of this call.)

Flows can also reach what `on-changes` couldn't. `on-changes` was statically wired into specific events at registration time, so a derivation that should run conditionally — only while a wizard step is active, only when a feature gate is engaged — had no clean shape. Flows are runtime-registered and runtime-clearable via the `:rf.fx/reg-flow` / `:rf.fx/clear-flow` effects. So the migration sometimes *improves* the code it touches: a thing that was awkwardly always-on becomes cleanly conditional.

The rewrite itself is Type B. Mechanically it's `(rf/on-changes f out-path & in-paths)` → `(rf/reg-flow {:id ... :inputs in-paths :derive f :output-path out-path})`, but the agent stops to ask about the `:id` (it suggests `:legacy/<event-id>` as a default) and whether the flow should be conditional rather than always-on. An app with no `on-changes` sees no migration here at all.

## Growing into images and frames

A v1 app registers everything at namespace load with `reg-*`, into one process-global [registrar](glossary.md#registrar). v2 keeps that path working: `reg-*` still registers, and the runtime assembles a standard frame over the global registrations for you. The mechanical migration changes nothing about how you register. Keep writing `reg-*`. You are using a frame without naming it, exactly as you've always used app-db without naming a frame.

You reach past that only when v2 gives you a shape v1 didn't have. The public composition model is `image → frame → event stream`: an [**image**](glossary.md#image) (`rf/image`) is a value naming a set of registrations — you select them from loaded namespaces (`:select-ns`) or list them inline (`:registrations`). A [**frame**](glossary.md#frame) (`rf/make-frame`) is the live isolated execution context that runs one **generation** — the resolved registration set an image seals into — with its own app state, subscription cache, and adapter binding. Images and frames are the route for genuinely new structure — a packaged feature you assemble and run as a unit, a per-tenant or multi-frame process. They're a refinement you grow into, not a step the migration forces.

> **Going deeper — build isolated contexts from images.** A frame built from `:images` runs exactly the registrations those images select — its own sealed registration set, validated for collisions and capability requirements at assembly. Two frames can hold different handlers for the same id without collision, which makes a frame the natural unit for a hermetic test or a parallel frame on the same page. You target a frame by its id — the public address is always the frame id, never an enclosing substrate.

> **Gotcha — don't select the same id twice in one image.** Within a *single* `rf/image`, `:select-ns` and `:registrations` must be **disjoint** — a `[kind id]` may not be both selected from a namespace and defined inline in the same image. Image assembly catches it and fails loud rather than silently merging. And the way to *override* a registration is **not** a `:replace` key — that key was retired in EP-0026, and `rf/image` rejects it (along with `:include-ns` / `:exclude-ns` / `:replace-standard`) with `:rf.error/invalid-image`. Instead, put the winning registration in a **later** image and compose — later image wins. The override is not silent: composition records every shadowing in a report you read with the public `rf/frame-shadows` accessor (each entry names the registration, the image that originally defined it, and the image that shadowed it), so you can assert in a test that the override you intended is the override that happened.

```clojure
(def base    (rf/image {:id :app/base   :select-ns {:include ["app.*"]}}))
(def testing (rf/image {:id :app/doubles :registrations {:reg-cofx [[:clock (fn [] 0)]]}}))

(let [frame (rf/make-frame {:images [base testing]})]   ;; later image wins
  (rf/frame-shadows frame))
;; => [{:registration [:cofx :clock], :defined-in :app/base, :shadowed-by :app/doubles}]
```

## The devtools moved house

One last orientation point that trips people who lived in v1's tooling. `re-frame-10x` — the v1 devtools panel — has been renamed and reimplemented as [**Xray**](glossary.md#xray) (`day8/re-frame2-xray`). Weight the word *reimplemented*: Xray is not 10x ported to v2, it's a from-scratch build against re-frame2's own [trace stream](glossary.md#trace-stream) and [epoch](glossary.md#epoch) history. The mental model carries over completely — events, subs, app-db diff, time-travel are all there — but the wiring underneath is new. If your v1 project depended on 10x during development, the v2 equivalent is Xray, and [the Xray tutorial](../xray/index.md) is where you meet it.

That's the migration in one read. The architecture is the same architecture, the sweep is automated, and the few genuinely-new shapes (managed HTTP, flows, images/frames) are improvements you'll be glad to adopt, not taxes you'll resent paying. The one rule that keeps it all honest is *don't invent migration rules*: the tool does what it's sure of and asks about the rest. When the migration settles, [chapter 26](reference.md) points at how to operate from there.

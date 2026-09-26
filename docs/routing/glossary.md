# Routing glossary

Optional routing capability: the URL is an *input*, the active route is ordinary
state via subscriptions, and navigation is an [event](../core/glossary.md#event).
See [The model](concepts.md).

### **navigate**

Change the route by dispatching navigation. The active [route](#route) is a
[subscription](../core/glossary.md#subscription) you read like any other. Because
navigation is an event, it is traceable, interceptable, and rewound by time-travel.

```clojure
;; inside a reg-view, where `dispatch` is bound to the view's frame;
;; from an event handler, return it as [:dispatch …] in :fx instead
(dispatch [:rf.route/navigate {:to :app/article :params {:id "abc"}}])
```

Related: [The model](concepts.md).

### **route-link**

The view that renders a link to a [route](#route): `[rf/route-link {:to :app/article
:params {:id "intro"}} "Read intro"]`. It builds a real `<a href>` from the route id,
turns a plain left-click into navigation, and leaves modifier clicks, `:target` and
`:download` to the browser. A hand-written `[:a {:href …}]` does a full page load.

### **route**

A URL pattern registered with `reg-route` under an id, paired with match behaviour —
`:params`/`:query` schemas, a [loader](#loader), a [`:can-leave`](#route-guard) /
`:can-enter` guard, scroll policy. The route table is the app's URL map.

### **route params**

The values captured by a route's path segments — the `:id` in `/articles/:id` —
declared with the route's `:params` schema and read with
`@(subscribe [:rf.route/params])`. Query-string values are a separate map, declared
with `:query` (and `:query-defaults`) and read with `@(subscribe [:rf.route/query])`;
the two never merge. Both are coerced by their schemas, so `?page=2` arrives as the
integer `2`, and validated when `re-frame.schemas` is loaded.

### **route slice**

The active route as the framework stores it, in
[runtime-db](../core/glossary.md#runtime-db) at `[:rf.runtime/routing :current]`:
route id, params, query, fragment, [transition](#transition), error, and
[nav-token](#nav-token). Views read it through `:rf/route` and the `:rf.route/*`
subscriptions; event handlers read it from the `:rf.db/runtime` coeffect. Only the
router writes it.

### **loader**

What a [route](#route) declares it needs on entry — `:resources` ensured loaded — so
a page's data requirement sits next to its URL. Loaders also run on the server; no
separate SSR data-fetch to keep in sync. A route's `:on-match` events are its
*activation work*, not its loader: the runtime fires and forgets them, and they never
touch route readiness. See [Activation work and page data](concepts.md#loaders-declaring-a-pages-data).

### **activation work**

The events a [route](#route) lists under `:on-match`. The runtime dispatches them
whenever the route becomes active, including when its params change but not when
the same address is navigated to again, and then moves on: it never waits for them,
and they never move [transition](#transition). A handler that throws reports on the
ordinary event error channel. Data the page cannot render without belongs in the
[loader](#loader) instead.

### **effective route plan**

The resource requirements a navigation actually runs: every `:resources` entry
contributed by the route's [chain](#route-chain), parent-most to leaf, with identical
requirements deduped to one fetch. Naming a `:parent` is what opts a child in, so a
shared shell read is declared once instead of restated per tab. Only `:resources`
compose this way.

### **intent prefetch**

Warming a destination's [effective route plan](#effective-route-plan) before the user
commits, via `[route-link {… :prefetch :intent}]` or a direct
`[:rf.route/prefetch <address>]` dispatch. Hover, focus, or touch runs the same plan
a navigation would — ownerless, non-blocking, and with no route state, guards, or
`:on-match`. Click through and the ordinary resource dedupe reuses the warmed work.

### **replan**

Rerunning the active route's [effective route plan](#effective-route-plan) against
the current `app-db` without navigating, via
`[:rf.route/replan-resources {:cause …}]`. The token, the owner and the address stay
the same; kept reads are adopted, added ones ensured under the route owner with your
cause, dropped ones released. The causal door for an identity switch (session restore,
tenant change) that a passive `{:from-db …}` re-key cannot fetch for itself. Not a
reload: unchanged data is never refetched, and no guards or `:on-match` run. See
[Replanning the active route's resources](concepts.md#replanning-the-active-routes-resources).

### **route chain**

The active route's ancestry. A route names a `:parent`; `@(subscribe [:rf.route/chain])`
returns the lineage root-most first — on `/articles/intro`,
`[:app/articles :app/article]`. Shared layouts without `<Outlet/>`: the leaf is the
page; each ancestor wraps a shell. See [Nested layouts](concepts.md#nested-layouts).

### **transition**

Route readiness — `:idle`, `:loading`, or `:error` — via `:rf.route/transition`, with
the structured failure on `:rf.route/error`. It is a projection over the blocking
`:resources` in the [effective route plan](#effective-route-plan): pending on a first
load, `:error` on a blocking first-load failure or a plan that could not be built,
`:idle` otherwise. A background refresh, a non-blocking read, an
[intent prefetch](#intent-prefetch), and `:on-match` never move it. One global fact
for a progress bar or error banner, not per-page loading flags.

### **nav-token**

Counter that identifies one navigation. Route-declared resources are owned by the
token that planned them; a reply after a newer navigation is dropped instead of
overwriting the page you are on. Hand-rolled loaders opt in via the
`:rf.route/nav-token` coeffect and `:rf.route/with-nav-token` fx.

### **route guard**

A boolean subscription on a [route](#route): **`:can-leave`** (`true` = leave is fine)
or **`:can-enter`** (`true` = enter is fine). The two refusals are deliberately
asymmetric. A `:can-leave` `false` **parks** the attempt in
`[:rf/pending-navigation]` — a question to the user — and your
[view](../core/glossary.md#view) resolves it with `[:rf.route/continue <id>]` or
`[:rf.route/cancel <id>]` (the pending-nav id). A `:can-enter` `false` is
[terminal](#terminal-entry): a question to application state, answered the same way
every time, so nothing commits and nothing parks. Unsaved changes → leave guard
([recipe](how-to/guard-unsaved-changes.md)); per-route auth → enter guard
([recipe](how-to/require-sign-in-on-a-route.md)); multi-route policy → optional
interceptor.

### **pending navigation**

A navigation parked by a `:can-leave` [guard](#route-guard) returning `false`, read
with `@(subscribe [:rf/pending-navigation])` (`nil` when nothing is waiting). The
value carries an `:id`, the replayable [destination](#destination), the resolved
target, the requested URL, the cause, and the `:replace?` / `:scroll` policy you
asked for.
`[:rf.route/continue <id>]` replays it; `[:rf.route/cancel <id>]` drops it. A refused
`:can-enter` never creates one.

### **terminal entry**

What a refused `:can-enter` does: commit no route slice, URL, scroll, resource, or
`:on-match`; park no pending value; and dispatch `:rf.route/entry-denied` exactly
once with the replayable `:destination`. There is nothing to resume and no entry
bypass — the return after signing in is a fresh navigation whose guard re-evaluates
naturally. Under SSR the same refusal renders the shell under a `403`.

### **destination**

The address a navigation resolved to, in a form you can dispatch again: `{:to
<route-id>}` plus any non-empty `:params` and `:query` and a non-nil `:fragment`, or
`{:url …}` when the URL matched no registered route. It is a valid `:rf.route/navigate` request as it stands, which is why
`:rf.route/entry-denied` and a [pending navigation](#pending-navigation) both carry
one — dispatch it after sign-in, or let `:rf.route/continue` replay it.

### **not-found**

Reserved [route](#route) id `:rf.route/not-found`. The runtime activates it when no
pattern matches — or, with `re-frame.schemas` loaded, when URL params fail their
schema — with the offending URL in params. Ordinary route you register and design; skip it and unmatched URLs get a bare
placeholder.

### **url-bound?**

Flag that *this* [frame](../core/glossary.md#frame) owns the browser address bar. At
most one frame is url-bound (none is legal — URL pushes then no-op). Its navigations
write the URL; Back/Forward (popstate) dispatch to it. Other frames route in memory
only — how a sidecar like [Xray](../core/glossary.md#xray) coexists without fighting
over the URL.

### **URL strategy**

How the url-bound frame reads and writes the address bar, set with `:url-strategy`
on that frame: `rf.routing/history-url-strategy` (the default, `/articles/intro`) or
`rf.routing/hash-url-strategy` (`#/articles/intro`), optionally wrapped in
`rf.routing/with-base-path` for an app served under a sub-path. Routes, `route-url`
and `match-url` stay path-form whichever strategy is in use. See
[URL strategies](concepts.md#url-strategies).

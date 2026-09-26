# Routing glossary

Terms used across the routing pages. For the model they fit into, see
[The model](concepts.md).

### **navigate**

Change the active [route](#route) by dispatching `:rf.route/navigate` with a request
map. Because a navigation is an [event](../core/glossary.md#event), it is traced, can
be intercepted, and is rewound by time-travel.

```clojure
;; inside a reg-view, where `dispatch` is bound to the view's frame;
;; from an event handler, return it as [:dispatch …] in :fx instead
(dispatch [:rf.route/navigate {:to :app/article :params {:slug "intro"}}])
```

### **route-link**

The view that renders a link to a [route](#route): `[rf/route-link {:to :app/article
:params {:slug "intro"}} "Read intro"]`. It builds a real `<a href>` from the route id,
turns a plain left-click into navigation, and leaves modifier clicks, `:target` and
`:download` to the browser. A hand-written `[:a {:href …}]` does a full page load.

### **route**

An entry registered with `reg-route`: an id, a metadata map, and a path. The metadata
can declare `:params` and `:query` schemas, a [loader](#loader),
[activation work](#activation-work), [guards](#route-guard), a `:parent`, and scroll
behaviour.

### **route params**

The values captured by a route's path segments — the `:slug` in `/articles/:slug` —
declared with the route's `:params` schema and read with
`@(subscribe [:rf.route/params])`. Query-string values are a separate map, declared
with `:query` (and `:query-defaults`) and read with `@(subscribe [:rf.route/query])`;
the two never merge. Both are coerced by their schemas, so a key declared `:int`
arrives as a number, and validated when `re-frame.schemas` is loaded.

### **route slice**

The active route as the framework stores it, in
[runtime-db](../core/glossary.md#runtime-db) at `[:rf.runtime/routing :current]`:
route id, params, query, fragment, [transition](#transition), error, and
[nav-token](#nav-token). Views read it through `:rf/route` and the `:rf.route/*`
subscriptions; event handlers read it from the `:rf.db/runtime` coeffect. Only the
router writes it.

### **loader**

The data a [route](#route) needs on entry, declared with `:resources` next to its
path. The runtime loads it on entry, reports it through [transition](#transition),
and runs the same declaration during server rendering. A route's `:on-match` events
are its [activation work](#activation-work), not its loader. See [Activation work and page data](concepts.md#loaders-declaring-a-pages-data).

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

Loading a destination's [effective route plan](#effective-route-plan) before the
reader clicks, via `[route-link {… :prefetch :intent}]` or a direct
`[:rf.route/prefetch <address>]` dispatch. Hover, focus, or touch runs the same resource
loads a navigation would, without changing the route or running guards or
`:on-match`. If the reader then clicks, the navigation reuses what was loaded.

### **replan**

Rerunning the active route's [effective route plan](#effective-route-plan) against
the current `app-db` without navigating, via
`[:rf.route/replan-resources {:cause …}]`. Use it when the identity behind the reads changes while the route does not, such as a
session restored after the page opened. Reads the new plan still needs are kept, new
ones are loaded, and dropped ones are released; unchanged data is not fetched again,
and no guards or `:on-match` run. See
[Replanning the active route's resources](concepts.md#replanning-the-active-routes-resources).

### **route chain**

The active route and its ancestors, following each route's `:parent`.
`@(subscribe [:rf.route/chain])` returns it outermost first — on `/articles/intro`,
`[:app/articles :app/article]`. The last entry is the page; each ancestor can wrap it
in a layout shell. See [Nested layouts](concepts.md#nested-layouts).

### **transition**

Route readiness — `:idle`, `:loading`, or `:error` — via `:rf.route/transition`, with
the structured failure on `:rf.route/error`. It is a projection over the blocking
`:resources` in the [effective route plan](#effective-route-plan): pending on a first
load, `:error` on a blocking first-load failure or a plan that could not be built,
`:idle` otherwise. A background refresh, a non-blocking read, an
[intent prefetch](#intent-prefetch), and `:on-match` never change it, so one progress
bar or error banner can read it for every page.

### **nav-token**

A value that identifies one navigation. Resources a route loads belong to its
token, so a reply that arrives after a newer navigation is dropped instead of
overwriting the page the reader is on. Hand-rolled loaders opt in via the
`:rf.route/nav-token` coeffect and `:rf.route/with-nav-token` fx.

### **route guard**

A boolean subscription on a [route](#route): **`:can-leave`** (`true` = leave is fine)
or **`:can-enter`** (`true` = enter is fine). A `:can-leave` refusal parks the
attempt as a [pending navigation](#pending-navigation) for your
[view](../core/glossary.md#view) to ask the reader about. A `:can-enter` refusal is
[terminal](#terminal-entry): nothing commits and nothing is parked. See
[Guard against unsaved changes](how-to/guard-unsaved-changes.md) and
[Require sign-in on a route](how-to/require-sign-in-on-a-route.md).

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
once with the replayable `:destination`. There is nothing to resume and no way to
skip the guard; the return after signing in is a new navigation, checked again. Under SSR the same refusal renders the shell under a `403`.

### **destination**

The address a navigation resolved to, in a form you can dispatch again: `{:to
<route-id>}` plus any non-empty `:params` and `:query` and a non-nil `:fragment`, or
`{:url …}` when the URL matched no registered route. It is a valid `:rf.route/navigate` request as it stands, which is why
`:rf.route/entry-denied` and a [pending navigation](#pending-navigation) both carry
one — dispatch it after sign-in, or let `:rf.route/continue` replay it.

### **not-found**

The reserved [route](#route) id `:rf.route/not-found`. The runtime activates it when no
pattern matches — or, with `re-frame.schemas` loaded, when URL params fail their
schema — with the offending URL in params. You register and render it like any other route;
without it, unmatched URLs get a bare placeholder and a warning.

### **url-bound?**

The frame option that makes a [frame](../core/glossary.md#frame) own the browser
address bar: its navigations write the URL, and Back/Forward are dispatched to it. At
most one frame is url-bound; with none, nothing writes the URL. Other frames route in
memory, which is how a tool like [Xray](../core/glossary.md#xray) or a test frame
routes without touching the address bar.

### **URL strategy**

How the url-bound frame reads and writes the address bar, set with `:url-strategy`
on that frame: `rf.routing/history-url-strategy` (the default, `/articles/intro`) or
`rf.routing/hash-url-strategy` (`#/articles/intro`), optionally wrapped in
`rf.routing/with-base-path` for an app served under a sub-path. Routes, `route-url`
and `match-url` stay path-form whichever strategy is in use. See
[URL strategies](concepts.md#url-strategies).

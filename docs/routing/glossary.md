# Routing glossary

Optional routing capability: the URL is an *input*, the active route is ordinary
state via subscriptions, and navigation is an [event](../core/glossary.md#event).
See [The model](concepts.md).

### **navigate**

Change the route by dispatching navigation. The active [route](#route) is a
[subscription](../core/glossary.md#subscription) you read like any other. Because
navigation is an event, it is traceable, interceptable, and rewound by time-travel.

```clojure
(rf/dispatch [:rf.route/navigate {:to :app/article :params {:id "abc"}}])
```

Related: [The model](concepts.md).

### **route**

A URL pattern registered with `reg-route` under an id, paired with match behaviour —
`:params`/`:query` schemas, a [loader](#loader), a [`:can-leave`](#route-guard) /
`:can-enter` guard, scroll policy. The route table is the app's URL map.

### **route params**

The active URL as state: read `:rf.route/id`, `:rf.route/params`, and
`:rf.route/query` through subscriptions. Path params and `?query=` values (coerced
and defaulted) drive handlers and views; `?page=2` survives Back for free.

### **loader**

What a [route](#route) declares it needs on entry — `:on-match` [events](../core/glossary.md#event)
dispatched by the runtime, and/or `:resources` ensured loaded — so a page's data
requirement sits next to its URL. Loaders also run on the server; no separate SSR
data-fetch to keep in sync.

### **route chain**

The active route's ancestry. A route names a `:parent`; `@(subscribe [:rf.route/chain])`
returns the lineage root-most first — on `/articles/intro`,
`[:app/articles :app/article]`. Shared layouts without `<Outlet/>`: the leaf is the
page; each ancestor wraps a shell. See [Nested layouts](concepts.md#nested-layouts).

### **transition**

Route-slice loading state — `:idle`, `:loading` (a [loader](#loader) still running),
or `:error` (one failed) — via `:rf.route/transition`. One global fact for a progress
bar or error banner, not per-page loading flags.

### **nav-token**

Counter that identifies one navigation. Route-declared resources are owned by the
token that planned them; a reply after a newer navigation is dropped instead of
overwriting the page you are on. Hand-rolled loaders opt in via the
`:rf.route/nav-token` coeffect and `:rf.route/with-nav-token` fx.

### **route guard**

A boolean subscription on a [route](#route): **`:can-leave`** (`true` = leave is fine)
or **`:can-enter`** (`true` = enter is fine). `false` parks the attempt in
`[:rf/pending-navigation]`; your [view](../core/glossary.md#view) resolves it with
`[:rf.route/continue <id>]` or `[:rf.route/cancel <id>]` (the pending-nav id).
Unsaved changes → leave guard ([recipe](how-to/guard-unsaved-changes.md)); per-route
auth → enter guard; multi-route policy → optional interceptor
([recipe](how-to/require-sign-in-on-a-route.md)).

### **not-found**

Reserved [route](#route) id `:rf.route/not-found`. The runtime activates it when no
pattern matches — or when URL params fail their schema — with the offending URL in
params. Ordinary route you register and design; skip it and unmatched URLs get a bare
placeholder.

### **url-bound?**

Flag that *this* [frame](../core/glossary.md#frame) owns the browser address bar. At
most one frame is url-bound (none is legal — URL pushes then no-op). Its navigations
write the URL; Back/Forward (popstate) dispatch to it. Other frames route in memory
only — how a sidecar like [Xray](../core/glossary.md#xray) coexists without fighting
over the URL.

# Routing glossary

re-frame2's optional routing capability — the URL is an *input* to your app, the active route is ordinary state you read through subscriptions, and navigation is just an [event](../core/glossary.md#event). See [The model](concepts.md).

### **navigate**

Change the route by dispatching navigation — the URL is an input, the active [route](#route) a [subscription](../core/glossary.md#subscription) you read like any other. Because it's an event, navigation is traceable, interceptable, and rewound by time-travel.

```clojure
(rf/dispatch [:rf.route/navigate {:to :app/article :params {:id "abc"}}])
```

Related: [The model](concepts.md).

### **route**

A URL pattern registered (`reg-route`) under an id, paired with what should happen when it matches — `:params`/`:query` schemas, a [loader](#loader) for the data it needs, a [`:can-leave`](#route-guard) guard, scroll behaviour. The route table is your app's URL map.

### **route params**

The active URL surfaced as state: read `:rf.route/id`, `:rf.route/params`, and `:rf.route/query` through subscriptions like any other derived value. Path params and `?query=` values (coerced and defaulted) drive your handlers and views — so `?page=2` survives the back button for free.

### **loader**

What a [route](#route) declares it needs on entry — `:on-match` [events](../core/glossary.md#event) the runtime dispatches, and `:resources` it ensures are loaded — so a page's data requirement lives next to its URL. Loaders run on the server too, so there's no separate SSR data-fetch to keep in sync.

### **route chain**

The active route's ancestry. A route names a `:parent`, and `@(subscribe [:rf.route/chain])` returns the lineage root-most first — on `/articles/intro`, `[:app/articles :app/article]`. It's how shared layouts work without an `<Outlet/>`: the leaf is the page, and each ancestor wraps a shell around it. See [Nested layouts](concepts.md#nested-layouts).

### **transition**

The route slice's loading state — `:idle`, `:loading` (a [loader](#loader) is still running), or `:error` (one failed) — read via `:rf.route/transition`. One global fact instead of per-page loading flags: a progress bar or an error banner is a single view over it.

### **nav-token**

The counter that identifies one navigation. Route-declared resources are owned by the token that planned them, so a reply that lands after a newer navigation is quietly dropped instead of overwriting the page you're now on — the classic click-away race, fixed in the substrate. Hand-rolled loaders opt in via the `:rf.route/nav-token` coeffect.

### **route guard**

A boolean subscription on a [route](#route): **`:can-leave`** (`true` = leave is fine) or **`:can-enter`** (`true` = enter is fine). A `false` parks the attempt in `[:rf/pending-navigation]`; your [view](../core/glossary.md#view) resolves it with `[:rf.route/continue <id>]` or `[:rf.route/cancel <id>]` (the pending-nav id). Unsaved-changes → leave guard ([recipe](how-to/guard-unsaved-changes.md)); per-route auth → enter guard; multi-route policy → optional interceptor ([recipe](how-to/require-sign-in-on-a-route.md)).

### **not-found**

The reserved [route](#route) (`:rf.route/not-found`) the runtime activates when no pattern matches — or when a URL's params fail their schema — with the offending URL in its params. It's an ordinary route you register and design; forget it and unmatched URLs get a bare placeholder.

### **url-bound?**

The flag declaring that *this* [frame](../core/glossary.md#frame) owns the browser address bar. At most one frame is url-bound at a time (none is legal — URL pushes then no-op); its navigations write the URL and back/forward (popstate) dispatch to it, while other frames route in-memory only — which is how a sidecar like [Xray](../core/glossary.md#xray) coexists without fighting over the URL.

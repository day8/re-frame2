# Routing glossary

re-frame2's optional routing capability — the URL is an *input* to your app, the active route is ordinary state you read through subscriptions, and navigation is just an [event](../core/glossary.md#event). See [Routing](concepts.md).

### **navigate**

Change the route by dispatching navigation — the URL is an input, the active [route](#route) a [subscription](../core/glossary.md#subscription) you read like any other. Because it's an event, navigation is traceable, interceptable, and rewound by time-travel.

```clojure
(rf/dispatch [:rf.route/navigate :article {:id "abc"}])
```

Related: [Routing](concepts.md).

### **route**

A URL pattern registered (`reg-route`) under an id, paired with what should happen when it matches — `:params`/`:query` schemas, a [loader](#loader) for the data it needs, a [`:can-leave`](#route-guard) guard, scroll behaviour. The route table is your app's URL map.

### **route params**

The active URL surfaced as state: read `:rf.route/id`, `:rf.route/params`, and `:rf.route/query` through subscriptions like any other derived value. Path params and `?query=` values (coerced and defaulted) drive your handlers and views — so `?page=2` survives the back button for free.

### **loader**

What a [route](#route) declares it needs on entry — `:on-match` [events](../core/glossary.md#event) the runtime dispatches, and `:resources` it ensures are loaded — so a page's data requirement lives next to its URL. Loaders run on the server too, so there's no separate SSR data-fetch to keep in sync.

### **route guard**

A `:can-leave` guard subscription (strict boolean: `true` means leaving is fine) consulted before leaving a [route](#route); a `false` parks the navigation as *pending* so your [view](../core/glossary.md#view) can render a "discard changes?" prompt, resolved by dispatching `:rf.route/continue` or `:rf.route/cancel`. The idiomatic home for unsaved-changes prompts — gating *entry* (an auth redirect) is an ordinary interceptor over the navigation events instead ([Require sign-in on a route](how-to/require-sign-in-on-a-route.md)).

### **not-found**

The reserved [route](#route) (`:rf.route/not-found`) the runtime activates when no pattern matches — or when a URL's params fail their schema — with the offending URL in its params. It's an ordinary route you register and design; forget it and unmatched URLs get a bare placeholder.

### **url-bound?**

The flag declaring that *this* [frame](../core/glossary.md#frame) owns the browser address bar. At most one frame is url-bound at a time (none is legal — URL pushes then no-op); its navigations write the URL and back/forward (popstate) dispatch to it, while other frames route in-memory only — which is how a sidecar like [Xray](../core/glossary.md#xray) coexists without fighting over the URL.

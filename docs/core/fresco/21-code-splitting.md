# Code splitting and lazy loading

Split code when a large part of the application is rarely visited. The normal
split is a route or screen compiled into its own module. React's lazy loading
with Suspense is useful when the area is already a React island.

## Split at the route or screen boundary

This approach does not need Suspense. Compile the screen into its own
shadow-cljs module, load that module through an effect, and keep its loading
state in app-db.

The example splits off a rarely used statistics screen from the todo app:

```clojure
;; shadow-cljs.edn
{:modules
 {:main  {:entries [app.core]}
  :stats {:entries [app.stats]
          :depends-on #{:main}}}}
```

One namespace holds the loadable, the effect, the status, and the events:

```clojure
(ns app.stats-gate
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]
            [shadow.lazy :as lazy]))

(def stats-screen
  (lazy/loadable app.stats/stats-screen))

(rf/reg-fx :app/load-module!
  {:doc       "Load a compiled module and dispatch the result into the calling frame."
   :platforms #{:client}}
  (fn [{:keys [frame]} {:keys [loadable on-loaded on-failed]}]
    (-> (lazy/load loadable)
        (.then  (fn [_] (rf/dispatch on-loaded {:frame frame})))
        (.catch (fn [_] (rf/dispatch on-failed {:frame frame}))))))

(rf/reg-sub :todo.stats/module
  (fn [db _]
    (get-in db [:modules :stats] :absent)))

(rf/reg-event :todo.stats/wanted
  (fn [{:keys [db]} _]
    (when-not (#{:loading :loaded} (get-in db [:modules :stats]))
      {:db (assoc-in db [:modules :stats] :loading)
       :fx [[:app/load-module!
             {:loadable  stats-screen
              :on-loaded [:todo.stats/loaded]
              :on-failed [:todo.stats/failed]}]]})))

(rf/reg-event :todo.stats/loaded
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:modules :stats] :loaded)}))

(rf/reg-event :todo.stats/failed
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:modules :stats] :failed)}))
```

Load the module when the route activates, and render all three states:

```clojure
(rf/reg-route :todo/stats
  {:on-match [[:todo.stats/wanted]]}
  "/stats")

(h/defview stats-entry [_]
  (case (h/sub [:todo.stats/module])
    :loaded
    [@stats-screen {}]

    :failed
    [:div.load-failed
     [:p "Couldn't load this screen."]
     [:button {:on-click [:todo.stats/wanted]} "Try again"]]

    [:div.screen-skeleton {:aria-busy true}]))
```

Pending is an ordinary branch, and failure is app-db data with a retry button.
Once loaded, `@stats-screen` is the original `h/defview`, so it keeps its name,
frame, reads, and re-render behaviour.

`:todo.stats/wanted` skips the load while the status is `:loading` as well as
`:loaded`. That lets you dispatch it on link hover to warm the module: the
click that follows reuses the load already in flight instead of fetching the
chunk a second time.

After a failure, dispatching `[:todo.stats/wanted]` again fetches again,
because `lazy/load` is an ordinary promise-returning function. The
`React.lazy` path below cannot retry.

A loaded module hot-reloads normally. A module that has not loaded has nothing
to reload.

## Lazy islands with Suspense

Use React's lazy model when the split region is already a React island or a
React-first screen. `React.lazy` takes a loader that resolves to
`#js {:default component}`. The host that mounts it is Client-only, so the
server never fetches the chunk.

Define the loadable and the lazy component at namespace top level:

```clojure
(ns app.stats.chart-gate
  (:require ["react" :as react]
            [re-frame.fresco :as h]
            [shadow.lazy :as lazy]))

(def chart-loadable
  (lazy/loadable app.stats.chart/completion-chart))

(def completion-chart
  (react/lazy
   (fn []
     (.then (lazy/load chart-loadable)
            (fn [component] #js {:default component})))))

(h/defhost chart-host completion-chart
  {:server :client-only})

(h/defhost suspense react/Suspense
  {:slots    #{:fallback}
   :fallback [:div.chart-skeleton {:aria-busy true}]})
```

The two `:fallback` keys on `suspense` do different jobs:

- `:slots #{:fallback}` declares Suspense's `fallback` prop as a place that
  takes Hiccup. React shows it while the chunk loads.
- The top-level `:fallback` is the host's Client-only placeholder, rendered on
  the server and on hydration's first client pass. A Client-only host renders
  nothing of its own on the server, slots included, so without it the region
  is missing from the server HTML.

Mount the lazy host under Suspense and an error boundary:

```clojure
(h/defview stats-chart [_]
  [h/error-boundary
   {:fallback
    [:div.chart-oops
     [:p "The chart is unavailable."]
     [:button {:on-click [:app/reload-page]} "Reload"]]
    :reset-key (h/sub [:todo.ui/chart-attempt])}

   [suspense
    {:fallback [:div.chart-skeleton {:aria-busy true}]}
    [chart-host {:remaining (h/sub [:todo/remaining-count])}]]])
```

If the loader promise rejects, React throws during render and the nearest
`h/error-boundary` catches it.

### A rejected chunk cannot be retried

Changing the boundary's `:reset-key` clears the caught error and remounts the
children, which retries errors the chart throws after it has loaded. It does
not fetch the chunk again. React calls a lazy component's loader only once;
after a rejection, every render re-throws the cached error. On screen this
looks like a second failed fetch, and only the network panel shows that no
request was made.

Retrying would need a new lazy component, which conflicts with defining it
once at top level. If the region must be retryable, split at the module
boundary instead, as in the first section.

!!! warning "Create lazy components once"
    Calling `React.lazy` inside a view body creates a new component type on
    every render, which remounts the subtree and can restart the load. Keep the
    loadable and the lazy component in top-level definitions.

Saving the namespace during hot reload re-creates the lazy component, so React
remounts that subtree. State that must survive a save belongs in app-db.

Under SSR the server sends the placeholder declared on the `suspense` host,
not the Suspense `:fallback` slot. Give the placeholder the same size as the
chart so the layout does not jump; the live component mounts after hydration
once its code arrives ([SSR and hydration](18-ssr-and-hydration.md)).

### Use Suspense for code, not application data

Do not turn subscription values into promises to suspend on data. re-frame2
state reaches React through an external store, and an external-store update
can make Suspense replace visible content with its fallback.

Resource pending, refreshing, failed, and stale states are already data.
Render them with the resource projection and `:keep-previous?`
([Async resources](08-async-resources.md)).

## Retaining hidden native UI with Activity

React Activity can keep a native or hosted subtree alive while it is hidden.
Declare it as a host and set its mode from app state:

```clojure
(h/defhost activity react/Activity)

[activity
 {:mode (if (h/sub [:todo.ui/stats-visible?]) "visible" "hidden")}
 [stats-pane {}]]
```

While the pane is hidden:

- React cleans up its effects, and its subscriptions are released;
- resource entries keep their owners, since releasing one is an event's job
  ([Async resources](08-async-resources.md));
- app-db does not change;
- React-held UI state, such as a scroll position, can survive.

When the pane becomes visible, it subscribes again to what its reveal render
reads. Xray labels hidden-but-retained work separately from mounted and
unmounted work.

Use Activity only when keeping host-owned UI state is worth the cost.
Application state already survives an unmount in app-db. Do not retain a pane
where showing its previous content for even one frame is unacceptable; see
[Reveal timing](#reveal-timing) under Advanced.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Split route is blank until the code arrives | The view dereferences the module without rendering pending and failed states | Branch on the module status and show a skeleton or failure UI |
| Repeated visits start repeated module loads | The load event ignores an existing `:loading` or `:loaded` status | Check the status first, as `:todo.stats/wanted` does |
| Hovering a link then clicking it fetches the chunk twice | The load event skips only `:loaded`, not an in-flight `:loading` | Check `#{:loading :loaded}` |
| Development watch works but release returns a module 404 | Module entries or `:depends-on` differ in the release configuration | Declare the module dependency and keep one entries list |
| Suspense fallback appears after every parent render | `React.lazy` is called inside a body | Move the loadable and lazy component to top-level definitions |
| Failed chunk leaves the skeleton forever | No error boundary handles the loader rejection | Wrap Suspense with `h/error-boundary` |
| Retry changes `:reset-key` and the region fails again with no network request | React caches a rejected lazy component and never calls the loader again | Split at the module boundary when the region must be retryable |
| Xray shows an anonymous lazy island | The lazy component was mounted raw, with no name | Mount it through `h/defhost`, which names it |
| Local state resets after a source save | Hot reload created a new component type and React remounted | Expected. Keep durable state in app-db; an island reads it with `n/use-sub` |
| Lazy area is missing from server HTML | The host is Client-only with no placeholder; the Suspense `:fallback` slot does not render on the server | Declare a same-size `:fallback` on the host |
| Hidden pane loses UI state | It was removed with a conditional rather than hidden with Activity | Use `:mode "hidden"` when you want it retained |
| Revealing a pane from a timer briefly shows old content | The hidden pane had no active subscription when React revealed it | Reveal from a user event, or unmount or re-key the pane |

## When not to split or retain

- Do not split a small bundle for the sake of symmetry.
- Prefer route or screen modules. Per-widget chunks create many loading states
  and network round trips.
- Do not use Suspense as the application's data-fetching model.
- Do not retain a pane whose useful state already lives in app-db and is cheap
  to rebuild.
- Never use Activity retention as a security or disclosure boundary.

## Advanced

### Reads during suspension

A view may call `h/sub` while React attempts a render. If that attempt
suspends and React shows the fallback, nothing from the attempt is committed:
it installs no subscriptions, and since a read never fetches, it starts no
request. When the code arrives and the real subtree commits, its reads are
installed once.

When a lazy region suspends next to views already on screen, React hides those
views behind the fallback. This is neither an unmount nor an Activity hide:
their effects stay in place, so they keep their subscriptions, receive updates
while hidden, and reappear with current values. To hide a pane and release
what it holds, use Activity.

### Reveal timing

When a user click switches Activity from hidden to visible, React renders the
retained subtree as part of that event, reading current app-db before the pane
appears.

When a timer, promise, or transition switches the mode, React may show the
retained subtree before its subscriptions are restored. For one frame the
pane can show the last complete state it rendered before it was hidden. It is
consistent, just old.

Even one old frame is wrong for an account or tenant switch: a consistent old
account is still the wrong account. Unmount or re-key that subtree instead of
retaining it.

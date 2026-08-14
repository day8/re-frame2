# Code splitting and lazy loading

Split code when a large area of the application is rarely visited. The normal
split is a route or screen module. React lazy loading is useful when the area
is already a native React island.

This page covers:

- route-level shadow-cljs modules;
- `n/lazy` and Suspense for native components;
- subscription and resource ownership while React suspends or hides a tree.

## Split at the route or screen boundary

The default approach does not need Suspense. Compile the screen into its own
shadow-cljs module, load that module through an effect, and render its arrival
state from app-db.

```clojure
;; shadow-cljs.edn
{:modules
 {:main  {:entries [app.core]}
  :admin {:entries [app.admin]
          :depends-on #{:main}}}}
```

One gate namespace can own the loadable value, effect, state, and events:

```clojure
(ns app.admin-gate
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [shadow.lazy :as lazy]))

(def admin-screen
  (lazy/loadable app.admin/admin-screen))

(rf/reg-fx :app/load-module!
  {:doc "Load a compiled module and dispatch the result into the calling frame."
   :platforms #{:client}}
  (fn [{:keys [frame]}
       {:keys [loadable on-loaded on-failed]}]
    (-> (lazy/load loadable)
        (.then
         (fn [_]
           (rf/dispatch on-loaded {:frame frame})))
        (.catch
         (fn [_]
           (rf/dispatch on-failed {:frame frame}))))))

(rf/reg-sub :modules/admin
  (fn [db _]
    (get-in db [:modules :admin] :absent)))

(rf/reg-event :admin/wanted
  (fn [{:keys [db]} _]
    (when-not (#{:loading :loaded} (get-in db [:modules :admin]))
      {:db (assoc-in db [:modules :admin] :loading)
       :fx [[:app/load-module!
             {:loadable  admin-screen
              :on-loaded [:admin/module-loaded]
              :on-failed [:admin/module-failed]}]]})))

(rf/reg-event :admin/module-loaded
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:modules :admin] :loaded)}))

(rf/reg-event :admin/module-failed
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:modules :admin] :failed)}))
```

Load the module when the route activates and render all three states:

```clojure
(rf/reg-route :app/admin
  {:on-match [[:admin/wanted]]}
  "/admin")

(h/defview admin-entry [_]
  (case (h/sub [:modules/admin])
    :loaded
    [@admin-screen {}]

    :failed
    [:div.load-failed
     [:p "Couldn't load this area."]
     [:button {:on-click [:admin/wanted]}
      "Try again"]]

    [:div.screen-skeleton
     {:aria-busy true}]))
```

The pending state is a normal branch. Failure is app-db data with an ordinary
retry intent. After loading, `@admin-screen` resolves to the original
`h/defview`, so it keeps its name, frame, reads, and independent re-render
behaviour.

Both `:loading` and `:loaded` are in the deduplication rule, and the first of
the two is the one that matters. Leaving and returning to a loaded route is the
easy case; the case that actually races is a module warmed from a link hover and
then clicked half a second later, while the first fetch is still in flight. A
gate written against `:loaded` alone passes every test that never warms a module
early, and fetches the chunk twice for every user who hovers before clicking.

So you can warm the module by dispatching `[:admin/wanted]` from a link hover or
focus intent, and the click that follows is the same load. A loaded module
participates in hot reload normally. An unloaded module has no loaded namespace
to reload.

This is also the only retryable path of the two on this page. `lazy/load` is an
ordinary promise-returning function: after a failure, `[:admin/wanted]` calls it
again and really does fetch again. The `n/lazy` section below cannot say that.

## Lazy native components with Suspense

Use React's lazy model when the split region is already a native island or
React-first screen. `n/lazy` has the same loader contract as `React.lazy`, but
preserves Hicasso's native component marker, display name, and server-policy
metadata.

Declare loadables and lazy components at namespace top level:

```clojure
(ns app.charts.gate
  (:require ["react" :as react]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.native :as n]
            [shadow.lazy :as lazy]))

(def chart-loadable
  (lazy/loadable app.charts.island/heavy-chart))

(def heavy-chart
  (n/lazy #(lazy/load chart-loadable)))

(h/defhost chart-host heavy-chart)

(h/defhost suspense react/Suspense
  {:slots    #{:fallback}
   :fallback [:div.chart-skeleton {:aria-busy true}]})
```

The two `:fallback` keys in that declaration are different things with the same
name, and the difference decides what the server sends. `:slots #{:fallback}`
declares the prop position React uses while the chunk is in flight. The sibling
`:fallback` is `defhost`'s Client-only *placeholder*: inert markup the crossing
renders on the server and on hydration's first client pass. A Client-only region
renders nothing at all — its declared slots included — so without that second
key the region is absent from the server response entirely.

Mount the lazy host under Suspense and an error boundary:

```clojure
(h/defview metrics-panel [_]
  [h/error-boundary
   {:fallback
    [:div.chart-oops
     [:p "The chart is unavailable."]
     [:button {:on-click [:app/reload-page]}
      "Reload"]]
    :reset-key (h/sub [:chart/attempt])}

   [suspense
    {:fallback
     [:div.chart-skeleton
      {:aria-busy true}]}
    [chart-host
     {:points (h/sub [:metrics/series])}]]])
```

The declared `:fallback` slot converts Hiccup to a ReactNode. If the loader
promise rejects, React throws during render and the nearest `h/error-boundary`
catches it.

### A rejected chunk is permanent

Changing the boundary's `:reset-key` does not re-fetch the chunk. It clears the
caught error and re-renders the children, which is a real retry for anything the
chart itself throws once it has loaded — but the lazy head is unchanged, and
React calls a loader only while its payload is uninitialised. A rejection settles
that payload as rejected, and every render afterwards re-throws the cached error
without going near the loader.

The paint is why this is easy to believe otherwise: a fallback that stays put
looks exactly the same whether React re-threw a cached rejection or fetched again
and failed again. Only the number of network requests tells them apart.

Retrying therefore means a *new* lazy component, and the top-level definitions
this section insists on are exactly what you do not have per attempt. **If the
region has to be retryable, split it at the module boundary instead** — the
first half of this page. `lazy/load` can be called again, the arrival states are
already app-db data, and the retry is an ordinary intent.

!!! warning "Create lazy components once"
    `n/lazy` creates a React component identity. Calling it inside a view body
    creates a new identity on every render, remounts the subtree, and can
    restart the load. Keep both the loadable and lazy component in top-level
    definitions.

A namespace save reallocates the lazy component during hot reload, so React
remounts that subtree. This is the normal HMR behaviour for named native
components and `defview` identities, and it is the same allocation a retry would
need — which is the clearest way to see that a retry is not available: a fresh
head is a *different component*, and only a source save produces one. State that
must survive a save belongs in app-db.

Under SSR, the lazy host remains Client-only, and the server sends the
placeholder the `suspense` host **declared** — not the Suspense slot, which a
Client-only region never reaches. Give that placeholder the same footprint as the
chart so the layout does not jump, and the live component mounts after hydration
when its code arrives ([SSR and hydration](18-ssr-and-hydration.md)).

## What happens to reads during suspension

Committed renders own subscriptions and demand. Speculative renders do not.

### A suspended attempt acquires nothing

A view may probe `h/sub` while React attempts a render. If that attempt
suspends and React shows the fallback, the attempted subtree did not commit.
It installs no subscriptions and acquires no demand-driven resources.

When the code arrives and the real subtree commits, the committed read set is
installed once.

### A committed sibling keeps its reads while the fallback is up

The other half of the same boundary behaves differently, and the difference
matters when you add a lazy region beside views that are already on screen.
Hiding a *committed* subtree for a fallback is not an unmount and not an
[Activity](#retaining-hidden-native-ui-with-activity) hide: React leaves its
passive effects in place, so those views keep their subscriptions, hear writes
that land while they are hidden, and come back with the current value and the
same registration they went in with.

So a slow chunk costs you a fallback, not a resubscribe — and if you want the
opposite, hiding a pane and releasing what it holds, that is Activity's job
rather than Suspense's.

### Use Suspense for code, not application data

Do not turn subscription values into promises to suspend on data. re-frame2
state reaches React through an external-store model, and an external-store
update can cause Suspense to replace visible content with a fallback.

Resource pending, refreshing, failed, and stale states are already explicit
data. Render them with the resource projection and `:keep-previous?`
([Async resources](08-async-resources.md)).

## Retaining hidden native UI with Activity

React Activity can retain a native or hosted subtree while hiding it. Declare
it as a host and control its mode from app state:

```clojure
(h/defhost activity react/Activity)

[activity
 {:mode (if (h/sub [:inbox/visible?])
          "visible"
          "hidden")}
 [inbox-pane {}]]
```

While hidden:

- React cleans up effects;
- committed subscription ownership releases;
- demand-driven resources release;
- app-db state remains unchanged;
- retained React UI state, such as a browser-owned scroll position, can remain.

When the pane becomes visible, it reacquires the reads made by its reveal
render. Xray labels hidden-retained work separately from mounted and unmounted
work.

### Discrete reveal is current before paint

When a user click changes Activity from hidden to visible, React renders the
retained subtree as part of the discrete reveal. It reads current app-db before
the pane becomes visible.

### Scheduled reveal can show one old frame

When a timer, promise, or transition changes the mode, React may reveal the
retained subtree before its external-store subscription effect is restored.
One frame can show the last complete state the pane rendered before it was
hidden. It is an older coherent pane, not a mixture of old and new values.

Do not retain a pane where even one frame of previous content is unacceptable.
An account or tenant switch is the clearest example: a coherent old account is
still the wrong account. Unmount or re-key that subtree instead.

Use Activity only when preserving host-owned UI state is worth the retention
cost. Ordinary application state already survives unmount in app-db.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Split route is blank until code arrives | The view dereferences the loaded module without rendering pending and failed states | Branch on module status and show a skeleton or failure UI |
| Repeated visits start repeated module loads | The load event ignores existing `:loading` or `:loaded` state | Gate the effect on the status as `:admin/wanted` does |
| Hovering a link then clicking it fetches the chunk twice | The load event skips only `:loaded`, so the click is not deduplicated against the in-flight load the hover started | Gate on `#{:loading :loaded}` |
| Development watch works but release returns a module 404 | Module entries or `:depends-on` edges differ in the release configuration | Declare the module dependency and keep one authoritative entries list |
| Suspense fallback appears after every parent render | `n/lazy` is created inside a body, producing a new component identity | Move the loadable and lazy component to top-level definitions |
| Failed chunk leaves the skeleton forever | No error boundary handles the loader rejection | Wrap Suspense with `h/error-boundary` so the failure has somewhere to land |
| The retry button changes `:reset-key` and the region fails again without a network request | React caches a rejected lazy payload; the head is unchanged, so the loader is never called again | Retry is not available at this boundary. Split at the module boundary when the region must be retryable |
| Xray shows an anonymous lazy island | Raw `React.lazy` removed Hicasso's native marker | Use `n/lazy` |
| Local state resets after a source save | HMR created a new component identity and React remounted | Expected. Store durable state in app-db and read it with `n/use-sub` |
| Lazy area is absent from server HTML | The lazy component is Client-only, and the host that crosses to it declared no placeholder — a Suspense `:fallback` slot is a prop, and a Client-only region does not render far enough to reach it | Declare a same-footprint `:fallback` on the host; the component mounts after adoption |
| Hidden pane loses UI state | It was unmounted with a conditional rather than retained with Activity | Use `:mode "hidden"` when retention is intentional |
| Scheduled reveal briefly shows old content | The hidden pane had no active subscription and React restored it after the reveal paint | Reveal from the discrete event, or unmount/re-key when stale display is unacceptable |

## When not to split or retain

- Do not split a small bundle merely to create architectural symmetry.
- Prefer the route or screen boundary. Per-widget chunks create many loading
  states and network round trips.
- Do not use Suspense as the application's data-fetching state model.
- Do not retain a pane whose useful state already lives in app-db and is cheap
  to rebuild.
- Never use Activity retention as a security or disclosure boundary.

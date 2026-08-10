# Code splitting and lazy loading

Your admin area is a third of the bundle, and most sessions never open it.
This page splits the build so that code loads when it is first wanted. It
covers three things:

- the route-and-module boundary, which covers almost every real split;
- the `React.lazy` bridge for native islands;
- what Suspense and Activity do to your reads while a subtree waits or
  hides.

> **Split at the route grain. Load the module as an effect. Render the
> arrival as state.**

## The route/module boundary

The default answer needs no React machinery at all. shadow-cljs compiles the
area into its own module. The load is an ordinary effect, and "has it
arrived?" is app-db state that your shell renders like any other fact.

```clojure
;; shadow-cljs.edn — the admin area becomes its own module
{:modules {:main  {:entries [app.core]}
           :admin {:entries [app.admin] :depends-on #{:main}}}}
```

One gate namespace owns the loadable, the effect, and the events:

```clojure
(ns app.admin-gate
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [shadow.lazy :as lazy]))

(def admin-screen (lazy/loadable app.admin/admin-screen))

(rf/reg-fx :app/load-module!
  {:doc       "Load a compiled module; dispatch the outcome into the calling frame."
   :platforms #{:client}}
  (fn [{:keys [frame]} {:keys [loadable on-loaded on-failed]}]
    (-> (lazy/load loadable)
        (.then  (fn [_] (rf/dispatch on-loaded {:frame frame})))
        (.catch (fn [_] (rf/dispatch on-failed {:frame frame}))))))

(rf/reg-sub :modules/admin
  (fn [db _] (get-in db [:modules :admin] :absent)))

(rf/reg-event :admin/wanted
  (fn [{:keys [db]} _]
    (when-not (= :loaded (get-in db [:modules :admin]))
      {:db (assoc-in db [:modules :admin] :loading)
       :fx [[:app/load-module! {:loadable  admin-screen
                                :on-loaded [:admin/module-loaded]
                                :on-failed [:admin/module-failed]}]]})))

(rf/reg-event :admin/module-loaded
  (fn [{:keys [db]} _] {:db (assoc-in db [:modules :admin] :loaded)}))

(rf/reg-event :admin/module-failed
  (fn [{:keys [db]} _] {:db (assoc-in db [:modules :admin] :failed)}))
```

Wire the load to the route — `:on-match` dispatches when the route activates
— and render the arrival as the state that it is:

```clojure
(rf/reg-route :app/admin {:on-match [[:admin/wanted]]} "/admin")

(h/defview admin-entry [_]
  (case (h/sub [:modules/admin])
    :loaded  [@admin-screen {}]     ;; the loadable derefs to the minted view
    :failed  [:div.load-failed
              [:p "Couldn't load this area."]
              [:button {:on-click [:admin/wanted]} "Try again"]]
    [:div.screen-skeleton {:aria-busy true}]))
```

Every piece is machinery that you already know. The pending placeholder is a
branch, not a Suspense fallback. The failure is a value with a retry intent,
not an exception. The loaded screen is an ordinary view head — after the
load, `@admin-screen` is the `h/defview`-minted view, with its boundary,
reads, and name intact. The `:loading`/`:loaded` check is the dedupe, so
navigation away and back never double-loads.

Two conducts come free with this shape. Warming works the same way as data
prefetch: dispatch `[:admin/wanted]` from a nav link's hover intent, and the
module downloads before the click. Hot reload is undisturbed — a loaded
module's namespaces reload like any others, and an unloaded module has
nothing to reload.

## The React bridge: `n/lazy` and a Suspense host

A native island or a React-shaped screen can instead load through React's
own lazy machinery. That is the correct choice when the region already lives
on the native tier and you want React to own the pending swap. The bridge is
`n/lazy`, the ABI helper from [the native tier](10-native-tier.md). It has
the same thunk-returning-a-promise contract as `React.lazy`, and it resolves
to the component. It keeps the component marker, so Xray still names the
boundary, and HMR still replaces the implementation without remounting it.

```clojure
(ns app.charts.gate
  (:require ["react" :as react]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.native :as n]
            [shadow.lazy :as lazy]))

(def chart-loadable (lazy/loadable app.charts.island/heavy-chart))

;; Declared at top level, never inside a body.
(def heavy-chart (n/lazy #(lazy/load chart-loadable)))

(h/defhost chart-host heavy-chart)
(h/defhost suspense react/Suspense {:slots #{:fallback}})
```

```clojure
(h/defview metrics-panel [_]
  [h/error-boundary {:fallback  [:div.chart-oops
                                 [:p "The chart failed to load."]
                                 [:button {:on-click [:chart/retry]} "Try again"]]
                     :reset-key (h/sub [:chart/attempt])}
   [suspense {:fallback [:div.chart-skeleton {:aria-busy true}]}
    [chart-host {:points (h/sub [:metrics/series])}]]])
```

While the code loads, React shows the Suspense fallback — hiccup in a
declared slot, like any other ([Interop](09-interop.md)). A loader rejection
throws into the render, so the nearest `h/error-boundary` catches it, and
the `:reset-key` counter is the retry, exactly as in [Errors](16-errors.md)
— the next attempt re-runs the loader.

!!! warning "Declare lazy components outside render"
    `n/lazy` (like `React.lazy`) mints a component identity. When the mint
    happens inside a body, every render mints a fresh identity, so React
    unmounts and remounts the subtree — and re-triggers the load — each time
    the body runs. Both declarations above are top-level `def`s; keep yours
    at top level too.

Under SSR, the lazy region is a Client-only surface. The server bytes carry
the deterministic fallback, and adoption mounts the live component when its
code arrives. The runtime makes no hydration claim for bytes that the server
never sent ([SSR and hydration](17-ssr-and-hydration.md)).

## While a subtree waits, or hides

Both mechanisms on this page hand a subtree's lifecycle to React. The read
law holds through both, because commit owns acquisition
([Views and reads](02-views-and-reads.md), [Async resources](08-async-resources.md)):

- **A suspended attempt owns nothing.** Render probes reads; commit installs
  them. An attempt that React parks on a fallback has committed nothing, so
  it holds no subscriptions and no resource demand. When the code arrives,
  the real render commits, and it acquires at that point — once.
- **Suspense here is for code arrival, not for data.** Do not wire
  subscriptions to promises to suspend on your app's data. re-frame2 state
  reaches React as an external store, and React documents that an
  external-store update can replace visible content with the fallback. Data
  pending is explicit state — the five-status resource projection and
  `:keep-previous?` already render it better
  ([Async resources](08-async-resources.md)).
- **Activity hide releases; reveal reacquires.** Some panes must keep their
  UI state while hidden — scroll position, half-built form widgets. For
  those panes, host React's `Activity` like any wrapper, and drive `:mode`
  from state:

  ```clojure
  (h/defhost activity react/Activity)

  [activity {:mode (if (h/sub [:inbox/visible?]) "visible" "hidden")}
   [inbox-pane {}]]
  ```

  While the pane is hidden, React cleans up effects, and the subtree's
  committed subscription ownership releases. Demand rides the same
  membership, so a hidden pane's demand-driven resources release too. App-db
  state is untouched: the pane's addresses keep their values. On reveal, the
  current read set reacquires and corrects before visible paint, so a
  revealed pane never paints stale content. Xray reports hidden-retained
  work as its own honest label, and does not collapse it into mounted or
  unmounted ([Diagnostics](15-diagnostics.md)).

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Navigation to the split route renders nothing, and then the screen appears after a delay | The shell renders the loaded view unconditionally, so the pre-load render was empty | Branch on the module status; render the skeleton and failed states |
| The module double-loads on repeated visits | The load event does not consult state | Gate on the status, as `:admin/wanted` does — `:loading`/`:loaded` is the dedupe |
| Works in `watch`, 404s in a release build | Module config drift — a missing `:depends-on`, or the entry namespace not in the module | Declare the dependency edge; keep one entries list per module |
| The Suspense fallback flashes on every render of the parent | The lazy component was minted inside a body — fresh identity, fresh mount, fresh load | Declare `n/lazy` (and the loadable) at top level |
| A failed chunk load leaves the skeleton up forever | Nothing catches the loader's rejection | Wrap the Suspense host in `h/error-boundary`; retry through `:reset-key` |
| Xray shows an anonymous boundary; HMR remounts the island | Raw `React.lazy` erased the component marker | Use `n/lazy` — same semantics, marker intact ([Native tier](10-native-tier.md)) |
| The lazy region is missing from server HTML | Client-only by design — the server carries the fallback, never the un-arrived component | Make the fallback a same-footprint skeleton; the live component mounts after adoption |
| A hidden pane's state is gone on reveal | The pane was unmounted, not hidden — a `when`, not an Activity `:mode` flip | Hide with `:mode "hidden"` to retain UI state; unmount when you mean gone. App-db state at addresses survives either way |

## When not to split

- **A small bundle.** One module that loads fast is simpler than three
  modules that each need a loading state. Split when a measured area is big
  and rarely visited, not on principle.
- **Below the route or screen grain.** Per-widget chunks multiply pending
  states and round trips; users already expect a pause at the route
  boundary.
- **For data.** Suspense is not your loading UI, and lazy loading is not
  your fetch layer. Resource status is explicit state with a better
  vocabulary ([Async resources](08-async-resources.md)).
- **Hiding what should unmount.** Activity pays a retention cost to keep UI
  state alive. A pane whose meaningful state already lives at app-db
  addresses survives unmount at no cost
  ([Ephemeral state](11-ephemeral-state.md)) — hide only what is expensive
  to rebuild, or what the platform owns, like scroll.

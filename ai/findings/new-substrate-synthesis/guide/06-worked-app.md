# 06 — A worked app: counter → dashboard

[01](01-getting-started.md) ended with a counter. This page grows it into a small
operations dashboard — tiles, a live feed, a filter — because that is where the model
proves itself: the counter shows the loop; the dashboard shows the loop *scaling*
without new concepts.

## The state shape first

One namespace, `.cljc` (JVM tests will thank you), dataflow before views:

```clojure
(ns dash.app
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview sub lease]]))

(rf/reg-event :dash/init
  (fn [_ _] {:db {:metrics {} :watchlist #{} :filter ""}}))

(rf/reg-event :metrics/arrived               ; your transport fx dispatches this
  (fn [{:keys [db]} [_ metrics]]
    {:db (update db :metrics merge metrics)}))

(rf/reg-event :watch/toggled
  (fn [{:keys [db]} [_ id on?]]
    {:db (update db :watchlist (if on? conj disj) id)}))

(rf/reg-event :filter/typed
  (fn [{:keys [db]} [_ text]]
    {:db (assoc db :filter text)}))

(rf/reg-sub :metric/ids
  (fn [db _]
    (let [text (str/lower-case (:filter db))]
      (->> (:metrics db)
           (filter (fn [[_ m]] (str/includes? (str/lower-case (:label m)) text)))
           (map key)
           sort
           vec))))

(rf/reg-sub :metric/by-id (fn [db [_ id]] (get-in db [:metrics id])))
(rf/reg-sub :watch/on?    (fn [db [_ id]] (contains? (:watchlist db) id)))
(rf/reg-sub :filter/text  (fn [db _] (:filter db)))
```

Note where the computation went: filtering and sorting live in `:metric/ids` —
shared, cached, Xray-visible, JVM-testable. Views below do presentation only.

## One tile, narrow reads

```clojure
(defview metric-tile [{:keys [id]}]
  (let [{:keys [label value unit]} (sub [:metric/by-id id])]
    [:div.tile
     [:h3 label]
     [:strong (str value unit)]
     [:label
      [:input {:type :checkbox
               :checked   (sub [:watch/on? id])
               :on-change [:watch/toggled id :rf.ui/checked]}]
      "Watch"]]))
```

The tile reads *its own* metric — classic re-frame layering ([10](10-performance.md)):
a tick that changes one metric repaints one tile, and the grid never hears about it.
The checkbox is a data handler with a placeholder — the whole [04](04-events.md)
story in one attribute.

## The grid and the filter

```clojure
(defview filter-box []
  [:input {:placeholder "Filter metrics…"
           :value    (sub [:filter/text])
           :on-input [:filter/typed :rf.ui/value]}])

(defview dashboard []
  [:div.dashboard
   [:header [:h1 "Ops"] [filter-box]]
   [:div.grid
    (for [id (sub [:metric/ids])]
      [metric-tile {:key id :id id}])]])
```

Two decisions worth naming:

1. **The filter text is app-db, not `local`** — the grid observes every keystroke, so
   the keystroke *is* product state ([03](03-state.md)). And because `:value` is
   literal next to a vector handler, this is a controlled-input site: the sync door
   applies and the caret behaves ([04](04-events.md)).
2. **The list is keyed or it does not build.** `{:key id}` on each tile is required —
   a missing key is a build failure with the file:line.

## A live tile: `lease`

A tile whose data must be kept alive while it is visible declares interest and reads
passively — the view never fetches:

```clojure
(defview latency-tile []
  (lease {:resource :metrics/latency-feed})
  (let [{:keys [status data]} (sub [:rf/resource {:resource :metrics/latency-feed}])]
    (case status
      :loading [:div.tile.skeleton "…"]
      :error   [:div.tile.error "Feed unavailable"]
      ;; :loaded — and :fetching, which keeps prior data visible mid-refresh
      [:div.tile [:h3 "p95 latency"] [:strong (str (:p95 data) "ms")]])))
```

First visible tile in → the feed is ensured; last one out → it can wind down.
Loading and error are just values you branch on. How the feed is actually fetched is
the next chapter ([07](07-servers.md)).

## Contain the risky part *(lands S3)*

Third-party chart inside a tile? Give it a boundary so one crash does not take the
dashboard:

```clojure
(rf/reg-event :ui/tile-crashed
  (fn [{:keys [db]} _] {:db (update db :render-errors (fnil inc 0))}))

(defview tile-failed [_]
  [:div.tile.error "This tile crashed — the rest of the dashboard is fine."])

(defview dashboard-with-live []
  [:div.dashboard
   [dashboard]
   [ui/error-boundary {:fallback tile-failed :on-error [:ui/tile-crashed]}
    [latency-tile]]])
```

## Mount

```clojure
(defn ^:export run []
  (rf/init! ui/adapter)
  (ui/mount [ui/frame-root {:id :dash :initial-events [[:dash/init]]}
              [dashboard-with-live]]
            (js/document.getElementById "root")))

(defn ^:dev/after-load reload! [] (run))
```

Identical shape to [01](01-getting-started.md) — a dashboard earns no extra boot
ceremony. Wire transport (polling, websocket, SSE) as ordinary re-frame2 fx that
dispatch `[:metrics/arrived …]`; the view layer never fetches. Hot reload works from
day one: edit a tile, save, and it repaints against live app-db.

## Tests, headless

The tile's whole contract — what it shows, what it dispatches — asserts on the JVM
tree, no browser. Install the shared [test namespace fixture](08-testing.md#test-namespace-setup)
before using these frame helpers:

```clojure
(ns dash.app-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.ui.test :as ui.test]
            [dash.app :as app]))

(def fixture-metrics
  {:cpu {:label "CPU" :value 41 :unit "%"}
   :mem {:label "Memory" :value 62 :unit "%"}})

(defn fixture-frame []
  (rf/make-frame {:initial-events [[:dash/init]
                                   [:metrics/arrived fixture-metrics]]}))

(deftest tile-shows-metric-and-intent
  (rf/with-new-frame [frame (fixture-frame)]
    (let [tree (ui.test/render [app/metric-tile {:id :cpu}] {:frame frame})]
      (is (= "CPU" (-> tree (ui.test/find :h3) ui.test/text)))
      (is (= [:watch/toggled :cpu :rf.ui/checked]
             (-> tree (ui.test/find :input) ui.test/attrs :on-change))))))

(deftest filter-narrows-the-grid
  (rf/with-new-frame [frame (fixture-frame)]
    (ui.test/dispatch! frame [:filter/typed "cp"])
    (let [tree (ui.test/render [app/dashboard] {:frame frame})]
      (is (= 1 (count (ui.test/find-all tree :dash.app/metric-tile)))))))
```

The fixture uses `rf/make-frame` and the app's *own* events, so test state cannot
drift from a state the real app can reach. Each test owns that frame through
`rf/with-new-frame`, including cleanup after a thrown body. The second test drives
state with a real event and counts tiles by **view id**. Business logic (the filtering
itself) belongs in a plain dataflow sub test; these two assert the *view* contract
only. Full tiers: [08](08-testing.md).

## What you just did not write

No `useCallback` on the checkbox, no `React.memo` on the tile, no store wiring for
the feed, no loading-state component library, no test IDs threaded through the DOM
for a click simulator. The dashboard is the counter, more times.

## Codas

**Serve it.** The dashboard is one mount away from [11](11-ssr.md): the same root form
renders on the JVM; the same event vectors sit in the server tree.

**Drive it.** Mount it, open Xray, type into the filter: one epoch per keystroke, one
tile repaint per changed metric — the model [10](10-performance.md) promised. Hand it
to your AI pair ([09](09-debugging.md)): ask why a tile repainted, have it dispatch
`[:metrics/arrived …]` bursts, or break the filter handler and watch it scrub back
and fix it.

# 07 — Talking to servers

Views never fetch. They declare interest (`lease`) and read status (`sub`). Transport
is ordinary re-frame2 effects and events — the same pipeline you already use for
everything else. This chapter closes the loop the dashboard left open.

You should already be comfortable with [03](03-state.md)'s four inputs and
[06](06-worked-app.md)'s tile shape.

## The shape of the story

```text
view leases resource
        ↓
resource system ensures (fetch / join in-flight)
        ↓
fx / transport does the I/O
        ↓
success or failure event commits into app-db / resource cache
        ↓
sub re-reads · view re-renders
```

Nothing in that pipeline is special to the view layer. The view's only jobs are
**liveness** and **presentation of status**.

## End to end: a metrics feed

### 1. Register the resource (core)

How you register resources is core re-frame2 territory — see the
[resources guide](../../../../docs/core/where-state-lives.md) and your project's
resource plan. Conceptually you have a resource id, params, and a fetch path that
eventually dispatches an arrival event.

### 2. Transport as fx

Wire HTTP, websocket, or SSE as ordinary effects. On success, dispatch a domain
event — not a view callback:

```clojure
(rf/reg-event :metrics/latency-requested
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:ui :latency :status] :loading)
     ;; your project's HTTP / resource fx spelling
     :fx [[:rf.http/managed
           {:url    "/api/metrics/latency"
            :on-ok  [:metrics/latency-arrived]
            :on-err [:metrics/latency-failed]}]]}))

(rf/reg-event :metrics/latency-arrived
  (fn [{:keys [db]} [_ body]]
    {:db (-> db
             (assoc-in [:metrics :latency] body)
             (assoc-in [:ui :latency :status] :loaded))}))

(rf/reg-event :metrics/latency-failed
  (fn [{:keys [db]} [_ err]]
    {:db (assoc-in db [:ui :latency] {:status :error :error err})}))
```

Exact fx ids depend on your stack (`:rf.http/managed` is illustrative). The
invariant: **I/O ends in events**, and events are the only writers.

### 3. View: lease + branch on status

```clojure
(ui/defview latency-tile []
  (lease {:resource :metrics/latency-feed})
  (let [{:keys [status data]} (sub [:rf/resource {:resource :metrics/latency-feed}])]
    (case status
      :loading [:div.tile.skeleton "…"]
      :error   [:div.tile.error
                "Feed unavailable"
                [:button {:on-click [:metrics/latency-requested]} "Retry"]]
      ;; :loaded and :fetching (prior data still visible mid-refresh)
      [:div.tile
       [:h3 "p95 latency"]
       [:strong (str (:p95 data) "ms")]])))
```

- First visible lease → the resource is ensured (fetch starts or joins in-flight).
- Last lease out → the system may wind down.
- `:loading` / `:error` / `:loaded` / `:fetching` are **values you branch on** — no
  Suspense, nothing hidden from Xray or tests.

!!! note "Lease vs route plans"
    Loading that belongs to navigation or workflow should ride route/event resource
    plans. Use `lease` when liveness genuinely follows *visible* UI — tiles, hover
    cards, modals. See [03](03-state.md).

## Testing the seam without a network

Headless tests never need a real HTTP client. Install the shared
[test namespace fixture](08-testing.md#test-namespace-setup), then seed the state the
resource would have produced or dispatch the arrival event yourself:

```clojure
(deftest latency-tile-shows-loaded-value
  (rf/with-new-frame
    [frame (rf/make-frame
             {:initial-events
              [[:rf/set-db
                ;; shape depends on how your resource sub projects status/data;
                ;; prefer dispatching your real :metrics/latency-arrived when possible
                {}]]})]
    ;; Prefer: drive through the real event
    (ui.test/dispatch! frame [:metrics/latency-arrived {:p95 42}])
    (let [tree (ui.test/render [latency-tile] {:frame frame})]
      (is (str/includes? (ui.test/text tree) "42")))))
```

Or stub the resource sub for a pure presentation check:

```clojure
(rf/with-new-frame [frame (rf/make-frame {})]
  (ui.test/render [latency-tile]
    {:frame frame
     :sub-overrides {[:rf/resource {:resource :metrics/latency-feed}]
                     {:status :loaded :data {:p95 42}}}}))
```

Assert the retry button's intent as data when status is `:error` — same Tier-1 move
as [08](08-testing.md).

## Navigation and "user viewed this"

There is no `:on-mount` event ([04](04-events.md)). Kick off loads from:

- route `:on-match` (or your router's equivalent),
- an event that opened the modal / selected the entity,
- a machine transition,
- or `lease` when visibility *is* the lifecycle.

```clojure
;; schematic — router spelling is core routing, not this guide
(rf/reg-event :route/article
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    {:db (assoc db :route {:name :article :slug slug})
     :fx [[:dispatch [:article/ensure slug]]]}))
```

The view for that route still only leases and reads — it does not start the fetch
during render.

## What not to do

| Temptation | Why not |
|---|---|
| Fetch inside `defview` / `effect` as app logic | Bypasses events, epochs, Xray, time-travel |
| Store "loading" only in React state | Invisible to tools; not shared; not SSR-friendly |
| One giant `[:app/all-data]` sub for the page | Every byte change repaints everything — narrow reads ([10](10-performance.md)) |

## Where next

- Core dataflow depth: [effects](../../../../docs/core/effects.md),
  [where state lives](../../../../docs/core/where-state-lives.md)
- View contract for `lease` / `sub`: [03](03-state.md)
- Dashboard that uses this seam: [06](06-worked-app.md)
- SSR of the same views: [11](11-ssr.md)

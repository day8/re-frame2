# 07 — Talking to servers

Views never fetch. They declare interest (`lease`) and read status (`sub`).
The fetch itself is ordinary re-frame2: an effect does the I/O and an event
commits the result. This chapter closes the loop the dashboard left open.

You should already be comfortable with [03](03-state.md)'s four inputs and
[06](06-worked-app.md)'s tile shape.

## The shape of the story

```text
view leases resource
        ↓
resource system ensures (first fetch / join in-flight)
        ↓
:rf.http/managed transport does the I/O
        ↓
the reply settles the resource cache (loaded + data, or error)
        ↓
sub re-reads · view re-renders
```

Nothing in that pipeline is special to the view layer. The view's only jobs are
**liveness** and **presentation of status**.

## End to end: a metrics feed

The whole path below is proven by an enrolled JVM fixture
(`re-frame.ui.guide-truth-jvm-test`) — register, ensure, settle, render — so the
grammar here is executable, not illustrative.

### 1. Register the resource

A resource is a named, cached read. `reg-resource` takes three slots — an id, a
metadata map, and the `:request` handler that returns the fetch to run. The
metadata's `:scope` is **required and fail-closed**: a global feed says so
explicitly.

```clojure
(rf/reg-resource :metrics/latency-feed
  {:doc           "Rolling p95 latency, refreshed on demand."
   :scope         :rf.scope/global    ; same numbers for everyone — an explicit claim
   :params-schema [:map]}             ; this feed takes no params
  (fn [_params _ctx]
    {:request {:method :get :url "/api/metrics/latency"}
     :decode  :json}))               ; :auto / :json / :text / a Malli schema / a fn
```

The runtime owns identity, cache scope, staleness, dedupe, and — crucially —
reply addressing: your `:request` returns only the outgoing call, never the
success/failure targets (those are supplied internally; see [Under the
hood](#under-the-hood-the-managed-http-reply-envelope)). How resources fit the
larger state picture is core territory — the
[resources guide](../../../../docs/core/where-state-lives.md).

### 2. View: lease + branch on status

```clojure
(ui/defview latency-tile []
  (lease {:resource :metrics/latency-feed})
  (let [{:keys [status data]} (sub [:rf/resource {:resource :metrics/latency-feed}])]
    (case status
      (:idle :loading) [:div.tile.skeleton "…"]
      :error   [:div.tile.error
                "Feed unavailable"
                [:button {:on-click [:rf.resource/refetch
                                     {:resource :metrics/latency-feed}]}
                 "Retry"]]
      ;; :loaded and :fetching — prior data stays visible mid-refresh
      [:div.tile
       [:h3 "p95 latency"]
       [:strong (str (:p95 data) "ms")]])))
```

- First visible lease → the resource is ensured (fetch starts, or joins an
  in-flight one).
- Last lease out → the system may wind down.
- `status` is one of `:idle` / `:loading` / `:fetching` / `:loaded` / `:error`
  (the view-model also carries derived `:loading?` / `:fetching?` / `:stale?` /
  `:has-data?`). They are **values you branch on** — no Suspense, nothing hidden
  from Xray or tests.
- The retry control's `:on-click` is a real event vector —
  `[:rf.resource/refetch {:resource :metrics/latency-feed}]` re-runs the fetch
  through the same path. Assert it as data ([08](08-testing.md)).

!!! note "Lease vs route plans"
    Loading that belongs to navigation or workflow should ride route/event
    resource plans (below). Use `lease` when liveness genuinely follows
    *visible* UI — tiles, hover cards, modals. See [03](03-state.md).

### 3. Cause the load from navigation

`lease` is the cause when visibility *is* the lifecycle. When the load belongs
to a route or a user action instead, dispatch `:rf.resource/ensure` — the same
event the lease reconciler dispatches under the hood:

```clojure
;; schematic — router spelling is core routing, not this guide
(rf/reg-event :route/dashboard
  (fn [{:keys [db]} _]
    {:db db
     :fx [[:dispatch [:rf.resource/ensure
                      {:resource :metrics/latency-feed
                       :owner    [:route :dashboard]}]]]}))
```

The view for that route still only leases and reads — it does not start the
fetch during render ([04](04-events.md): there is no `:on-mount` event; events
are the only writers).

## Under the hood: the managed-HTTP reply envelope

When the resource fetches, the runtime lowers your `:request` into the
`:rf.http/managed` effect, supplying `:request-id`, `:on-success`, and
`:on-failure` itself. The transport appends a **uniform reply envelope** as the
reply's last argument:

```clojure
{:status :ok    :value <decoded body>}        ; success
{:status :error :error <:rf.http/* envelope>} ; failure
```

The resource's internal reply handlers verify the reply (frame + work-id +
generation, so a stale or cross-frame reply can never overwrite newer data) and
settle the cache entry — `:loaded` + `:data`, or `:error` + `:error`. A refresh
whose reply fails keeps the prior data and records a `:refresh-error` instead.

### Firing a request without a resource

Sometimes you want one request wired straight to app-db — no cache, no lease.
Then you address `:rf.http/managed` yourself and consume that same envelope. The
authoring keys are `:request` (the outgoing call), optional `:decode`, and
`:on-success` / `:on-failure` (an explicit `nil` silences a branch):

```clojure
(rf/reg-event :metrics/latency-requested
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:latency :status] :loading)
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/metrics/latency"}
            :decode     :json
            :on-success [:metrics/latency-arrived]
            :on-failure [:metrics/latency-failed]}]]}))

(rf/reg-event :metrics/latency-arrived
  (fn [{:keys [db]} [_ {:keys [value]}]]      ; envelope: {:status :ok :value …}
    {:db (assoc db :latency {:status :loaded :p95 (:p95 value)})}))

(rf/reg-event :metrics/latency-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]      ; envelope: {:status :error :error …}
    {:db (assoc db :latency {:status :error :error error})}))
```

The view then reads ordinary app-db (`(sub [:metrics/latency])`). The invariant
either way: **I/O ends in events, and events are the only writers.**

## Testing the seam without a network

Headless tests never touch a real HTTP client. Install the shared
[test namespace fixture](08-testing.md#test-namespace-setup), register the
resource, override `:rf.http/managed` with a capturing stub, then replay the
transport's reply envelope yourself. Loading, success, failure, and retry all
drive the **real** ensure → settle → sub path:

```clojure
(deftest latency-tile-loads-and-recovers
  (rf/reg-resource :metrics/latency-feed
    {:scope :rf.scope/global :params-schema [:map]}
    (fn [_ _] {:request {:method :get :url "/api/metrics/latency"} :decode :json}))
  (rf/with-new-frame
    [frame (rf/make-frame {})]
    ;; the view leases, so drive the cause the lease would — an ensure
    (ui.test/dispatch! frame [:rf.resource/ensure
                              {:resource :metrics/latency-feed
                               :owner    [:lease :metrics 1]}])
    ;; loading: the tile shows its skeleton
    (is (str/includes? (ui.test/text (ui.test/render [latency-tile] {:frame frame}))
                       "…"))
    ;; success: replay {:status :ok :value …} → the tile renders 42
    (ui.test/dispatch! frame (conj (:on-success @managed-args)
                                   {:status :ok :value {:p95 42}}))
    (is (str/includes? (ui.test/text (ui.test/render [latency-tile] {:frame frame}))
                       "42"))))
```

`@managed-args` is whatever the capturing `:rf.http/managed` stub recorded (its
`:on-success` is the runtime-owned `[:rf.resource.internal/succeeded …]` reply
target). To pin a pure presentation state instead, stub the sub:

```clojure
(ui.test/render [latency-tile]
  {:frame frame
   :sub-overrides {[:rf/resource {:resource :metrics/latency-feed}]
                   {:status :loaded :data {:p95 42}}}})
```

Assert the retry button's intent as data when status is `:error` — same Tier-1
move as [08](08-testing.md).

## What not to do

| Temptation | Why not |
|---|---|
| Fetch inside `defview` / `effect` as app logic | Bypasses events, epochs, Xray, time-travel |
| Store "loading" only in React state | Invisible to tools; not shared; not SSR-friendly |
| Write app-db from a handler while the view reads `[:rf/resource …]` | Two disjoint stores — the view never sees the write. Pick one seam per feature |
| One giant `[:app/all-data]` sub for the page | Every byte change repaints everything — narrow reads ([10](10-performance.md)) |

## Where next

- Core dataflow depth: [effects](../../../../docs/core/effects.md),
  [where state lives](../../../../docs/core/where-state-lives.md)
- View contract for `lease` / `sub`: [03](03-state.md)
- Dashboard that uses this seam: [06](06-worked-app.md)
- SSR of the same views: [11](11-ssr.md)

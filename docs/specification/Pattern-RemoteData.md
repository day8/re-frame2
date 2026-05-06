# Pattern — Remote Data

> Status: Drafting. **v1 — pattern, not EP.** Per [000 §Goal #7 dispositions](000-Vision.md): the framework's primitives (registered fx with `:platforms`; events; subs; schemas) cover remote data; this doc records the **standard request-lifecycle convention** that uses them.

## What this doc is

A **convention**, not an EP. Conventions live above CP-3 (registered fx) and below feature code. They describe the idiomatic shape teams should follow when building feature-level remote-data code, so:

- Code reads consistently across features.
- AIs scaffolding new code produce conformant shapes.
- Tooling has a stable target — UI loading-states, error toasters, request-cancellation handles all read predictable paths.

Other-language ports adopt the same convention; the host's idioms differ but the request-lifecycle shape is portable.

## The lifecycle slice

Every feature that loads remote data has a slice in its `app-db` for tracking the request's state. The standard shape:

```clojure
{:status :idle | :loading | :loaded | :error
 :data    <result-or-nil>
 :error   <error-or-nil>
 :loaded-at <timestamp-or-nil>
 :attempt   :int}                        ;; 1-indexed; bumps on retry
```

Schema (CLJS reference):

```clojure
(def RequestSlice
  [:map
   [:status     [:enum :idle :loading :loaded :error]]
   [:data       {:default nil} :any]
   [:error      {:default nil} [:maybe :any]]
   [:loaded-at  {:default nil} [:maybe :int]]
   [:attempt    {:default 0}   :int]])
```

A feature that loads multiple resources has multiple slices, each at a documented path. The `RequestSlice` schema is reusable: any feature loading remote data uses it for each resource.

## The four standard events

Every request lifecycle uses the same four events (per resource), namespaced by feature:

| Event | What it does |
|---|---|
| `:feature.resource/load` | User wants the data. Sets `:status :loading`, bumps `:attempt`, dispatches the fetch. |
| `:feature.resource/loaded` | The fetch returned successfully. Sets `:status :loaded`, `:data`, `:loaded-at`. |
| `:feature.resource/load-failed` | The fetch failed. Sets `:status :error`, `:error`. |
| `:feature.resource/reset` | Reset the slice to `:idle`. (Optional; useful for navigation away.) |

Worked example — articles list:

```clojure
;; Schema
(rf/reg-app-schema [:articles] RequestSlice)

;; :on-create init for the feature
(rf/reg-event-db :articles/initialise
  (fn [db _]
    (assoc db :articles {:status :idle :data nil :error nil :loaded-at nil :attempt 0})))

;; Trigger the load
(rf/reg-event-fx :articles/load
  {:doc "Fetch the articles list. Sets the slice to :loading; the fetch
         dispatches :articles/loaded or :articles/load-failed on completion."}
  (fn handler-articles-load [{:keys [db]} _]
    {:db (-> db
             (assoc-in [:articles :status]  :loading)
             (assoc-in [:articles :error]   nil)
             (update-in [:articles :attempt] inc))
     :fx [[:http {:method :get
                  :url    "/api/articles"
                  :on-success [:articles/loaded]
                  :on-error   [:articles/load-failed]}]]}))

;; Success
(rf/reg-event-db :articles/loaded
  (fn handler-articles-loaded [db [_ articles]]
    (-> db
        (assoc-in [:articles :status]    :loaded)
        (assoc-in [:articles :data]      articles)
        (assoc-in [:articles :loaded-at] (current-time-ms)))))

;; Failure
(rf/reg-event-db :articles/load-failed
  (fn handler-articles-load-failed [db [_ err]]
    (-> db
        (assoc-in [:articles :status] :error)
        (assoc-in [:articles :error]  err))))
```

Three subs per slice as a convention:

```clojure
(rf/reg-sub :articles            (fn [db _] (get db :articles)))
(rf/reg-sub :articles/status     :<- [:articles] (fn [s _] (:status s)))
(rf/reg-sub :articles/data       :<- [:articles] (fn [s _] (:data s)))
(rf/reg-sub :articles/error      :<- [:articles] (fn [s _] (:error s)))
(rf/reg-sub :articles/loading?   :<- [:articles/status] #(= % :loading))
```

Views read the convenience subs:

```clojure
(rf/reg-view :pages/articles
  (fn []
    (cond
      @(subscribe [:articles/loading?])
      [:p "Loading…"]

      @(subscribe [:articles/error])
      [:p.error (str "Couldn't load: " @(subscribe [:articles/error]))]

      :else
      [:ul (for [a @(subscribe [:articles/data])]
             ^{:key (:id a)} [:li (:title a)])])))
```

## Variations

### Optimistic updates

For mutations where the user expects immediate feedback (toggle a like, update a profile), commit the change to `app-db` *before* the fetch resolves. If the fetch fails, roll back.

```clojure
(rf/reg-event-fx :article/toggle-like
  (fn [{:keys [db]} [_ id]]
    (let [prior (get-in db [:articles :data])]
      {:db (update-in db [:articles :data]
                      (fn [arts] (mapv #(if (= id (:id %)) (update % :liked? not) %) arts)))
       :fx [[:http {:method :post
                    :url (str "/api/articles/" id "/toggle-like")
                    :on-error [:article/toggle-like-failed prior]}]]})))

(rf/reg-event-db :article/toggle-like-failed
  (fn [db [_ prior _err]]
    (assoc-in db [:articles :data] prior)))
```

The pattern: capture the prior value as part of the *event*, not in mutable state. The rollback handler is pure.

### Cancellation

For long-running requests where the user can navigate away (or trigger a new request before the old one finishes), the request needs a cancellation handle. The convention: the `:http` fx returns a cancellation token via a registered cofx, stored at `[:requests :tokens id]`. A `:feature/cancel-load` event clears the token and the fx interprets the cleared token as "abort."

This is a sketch; concrete implementation details (`AbortController` in browser, `cancel!` in Promesa, etc.) are CLJS-specific. See [011-SSR.md](011-SSR.md) for SSR-side implications (server-side requests don't need cancellation; the request lifecycle bounds them).

### Polling

Repeating fetches at a fixed interval. Use `:dispatch-later` to schedule the next load:

```clojure
(rf/reg-event-fx :articles/poll
  (fn [_ _]
    {:fx [[:dispatch [:articles/load]]
          [:dispatch-later {:ms 30000 :dispatch [:articles/poll]}]]}))
```

A pause/resume pair (`:articles/poll-pause`, `:articles/poll-resume`) reads from a `:articles/poll-active?` sub if the user wants UI control over polling.

### Retry with backoff

A `:feature.resource/retry` event reads `:attempt` from the slice and decides whether to schedule another `:load` or surface an error. Backoff multipliers are sub computations from `:attempt`. The framework offers no built-in retry logic — convention only.

## SSR considerations

Per [011-SSR.md](011-SSR.md):

- The `:http` fx is `:platforms #{:server :client}` — fires identically on both. The server-side implementation reads via the JVM HTTP client; the client-side via `fetch`.
- Server-side: `:on-create` dispatches `:articles/load` (or equivalent); the drain settles before render-to-string runs; the rendered HTML reflects the loaded data.
- Client-side hydration: `:rf/hydrate` seeds the slice with `:status :loaded` and the server-loaded data. The client doesn't re-fetch on first render; subsequent navigations trigger fresh `:load` events as normal.

## What this pattern doesn't say

- **Which HTTP library to use.** The framework's `:http` fx is whatever the host registers. CLJS reference might use `cljs-http` or `js/fetch`; Python a `requests` wrapper; TS native `fetch`.
- **Authentication header injection.** Application concern; typically a cofx that injects the current session token, plus an interceptor that adds the header to outgoing `:http` calls.
- **Caching policy.** A feature decides how stale `:loaded-at` may be before re-fetching. The pattern provides the timestamp; the policy is application code.
- **Pagination / infinite scroll.** A feature with paginated data uses multiple slices (one per page) or a single slice with a list-of-pages structure. Convention silent here — implementation choice.

## AI-first checklist for remote-data code

- [ ] Each remote resource has a slice with the standard `:status`/`:data`/`:error`/`:loaded-at`/`:attempt` shape.
- [ ] Slice is `reg-app-schema`-bound (in dynamic hosts; typed in static hosts).
- [ ] The four standard events (load / loaded / load-failed / reset) are registered, each with `:doc`.
- [ ] At least three subs (`:status`, `:data`, `:loading?`) per resource — convenience layer.
- [ ] The `:http` fx (or equivalent) has `:platforms` set so SSR works.
- [ ] Views render explicitly on `:loading?` / `:error` / loaded-data — no implicit "data is undefined while loading" assumptions.
- [ ] Optimistic-update events capture the prior value in the rollback dispatch, not in mutable state.

## Cross-references

- [Construction-Prompts.md §CP-3](Construction-Prompts.md) — registered fx
- [011-SSR.md](011-SSR.md) — SSR-side fetch handling and `:platforms` metadata
- [009-Instrumentation.md §Error contract](009-Instrumentation.md#error-contract) — `:rf.error/fx-handler-exception` for failed fetches
- [examples/login/core.cljs](../../examples/login/core.cljs) — the login feature uses a simplified version of this lifecycle

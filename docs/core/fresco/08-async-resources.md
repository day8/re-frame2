# Async resources

The core resources model handles registered reads, cache identity, mutation
status, invalidation, and managed HTTP. A Fresco view reads those facts with
`h/sub` like any other subscription. This page shows the view side: loading
todos from `/api/todos`, per-todo write status, optimistic updates, merging a
late save reply into a newer draft, and loading data only while a view needs
it.

## Load todos into a view

Register the read once, cause it from an event, and read its state in a view:

```clojure
(ns app.todos.resources
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.http.managed]
            [re-frame.fresco :as h]))

(rf/reg-resource :todo/list
  {:params-schema [:map]
   :scope         :rf.scope/global
   :tags          (fn [_ _] #{[:todos]})}
  (fn [_params _ctx]
    {:request {:method :get
               :url    "/api/todos"}
     :decode  :json}))

(rf/reg-event :todo/initialise
  (fn [_ _]
    {:fx [[:dispatch [:rf.resource/ensure
                      {:resource :todo/list
                       :params   {}
                       :owner    [:todo-page]
                       :cause    [:todo/initialise]}]]]}))

(h/defview todo-page [_]
  (let [{:keys [status data loading?]}
        (h/sub [:rf/resource {:resource :todo/list :params {}}])]
    (cond
      loading?          [:p "Loading…"]
      (= :error status) [:p.error "Could not load todos."]
      :else
      [:ul.todo-list
       (for [{:keys [id title]} data]
         [:li {:key id} title])])))
```

`[:rf/resource …]` returns the entry's `:status`, `:data` and `:error`, plus
derived flags: `:loading?` for a first load with no data, `:fetching?` for a
refresh, `:stale?` and `:has-data?`.

A subscription never fetches. It projects the cache, so a view that only
subscribes reads `:idle` for ever. An event causes the fetch, here the frame's
`:todo/initialise` through `[:rf.resource/ensure …]`. Data that the current URL
determines is better caused by the route's `:resources`, which SSR and
transition blocking can also see.

## Track writes per instance

A write is a registered mutation, executed with an **instance** id that names
the unit whose status the UI displays. Here each todo's checkbox has its own:

```clojure
(rf/reg-mutation :todo/set-done
  {:params-schema [:map [:id :int] [:done? :boolean]]
   :scope         :rf.scope/global
   :invalidates   (fn [_params _result] #{[:todos]})}
  (fn [{:keys [id done?]} _ctx]
    {:request {:method :put
               :url    (str "/api/todos/" id)
               :body   {:done done?}}
     :decode  :json}))

(h/defview done-checkbox [{:keys [id done?]}]
  (let [save (h/sub [:rf/mutation {:instance [:todo/set-done id]}])]
    [:input {:type      :checkbox
             :checked   done?
             :disabled  (:pending? save)
             :on-change [:rf.mutation/execute
                         {:mutation :todo/set-done
                          :params   {:id id :done? (not done?)}
                          :instance [:todo/set-done id]
                          :cause    [:click :todo/set-done]}]}]))
```

The event vector carries the mutation, params, instance and cause as data. The
instance subscription provides `:pending?`, `:success?`, `:error?`,
`:settled?`, `:optimistic?`, `:result`, and `:error`. On success the mutation
invalidates the `[:todos]` tag, so `:todo/list` refetches. Focused
subscriptions such as `[:rf.mutation/pending? {:instance …}]` return one of
those values.

Different instances settle independently. Re-executing the same instance
supersedes its pending attempt, so the older reply cannot update the cache or
run its continuation. A settled error stays until another execute replaces it
or `[:rf.mutation/clear {:instance …}]` dismisses it; clear also aborts work
still in flight, best-effort.

So the instance id is a policy choice. `[:todo/set-done id]` lets each todo
save concurrently; `[:todo/set-done]` would make every checkbox share one
status and cancel each other's pending writes.

## Optimistic updates and rollback

To show the change before the server replies, declare an optimistic patch on
the mutation. The runtime snapshots each entry carrying the tag, applies the
patch before the request, and settles the snapshot on success, error, or
cancellation:

```clojure
(rf/reg-mutation :todo/set-done
  {:params-schema [:map [:id :int] [:done? :boolean]]
   :scope         :rf.scope/global
   :optimistic-tags
   (fn [{:keys [id done?]}]
     [{:scope :rf.scope/global
       :tags  #{[:todos]}
       :patch (fn [todos]
                (mapv #(if (= id (:id %)) (assoc % :done? done?) %)
                      todos))}])
   :invalidates   (fn [_params _result] #{[:todos]})}
  (fn [{:keys [id done?]} _ctx]
    {:request {:method :put
               :url    (str "/api/todos/" id)
               :body   {:done done?}}
     :decode  :json}))
```

On error or cancellation, the runtime restores the snapshot; you never write
an inverse patch. On success, the server's reply replaces the guess.

If another write changes the entry between the patch and settlement, the
default `:on-conflict :invalidate` does not restore the old snapshot over newer
data. It marks the entry stale so a refetch resolves the conflict. Use
`:on-conflict :force` only when overwriting concurrent changes is intended. The
instance reports `:optimistic? true` until the guess settles.

## Merge a settled reply into the current draft

A save reply can arrive after the user has changed the draft again. Writing
the whole server value over the draft deletes those edits; dropping the reply
loses the server's normalization. Two mechanisms cover the two races:

- **Supersession** is enforced by the runtime. A reply from an older attempt
  under the same instance is suppressed and never reaches the continuation.
- **Settle-merge** is application policy. A current reply is compared with the
  params that were sent, field by field, so newer draft values keep their
  slots.

This is the `:todo/save` mutation the [Forms](05-forms.md) editor executes. It
creates a todo when the draft has no `:id` and updates one when it does:

```clojure
(rf/reg-mutation :todo/save
  {:params-schema [:map
                   [:id {:optional true} :int]
                   [:title :string]
                   [:notes :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [_params _result] #{[:todos]})}
  (fn [{:keys [id] :as todo} _ctx]
    {:request {:method (if id :put :post)
               :url    (if id (str "/api/todos/" id) "/api/todos")
               :body   {:todo todo}}
     :decode  :json}))

[:rf.mutation/execute
 {:mutation :todo/save
  :params   draft
  :instance :todo.editor/save
  :reply-to [:todo.editor/save-settled]}]
```

The reply includes the accepted `:params`, so the continuation knows what was
sent:

```clojure
(rf/reg-event :todo.editor/save-settled
  (fn [{:keys [db]} [_ {:keys [status value params error]}]]
    (if (= :ok status)
      {:db (-> db
               (assoc-in [:todo.editor :baseline] (:todo value))
               (update-in
                [:todo.editor :draft]
                (fn [draft]
                  (reduce-kv
                   (fn [d field sent]
                     (if (= (get d field) sent)
                       (assoc d field (get-in value [:todo field]))
                       d))
                   draft
                   params))))}
      {:db (assoc-in db [:todo.editor :save-error] error)})))
```

A field that still equals the sent value takes the server's version. A field
edited after the request keeps the new draft value. `:reply-to` runs only after
the runtime has accepted the reply as current, applied its cache effects, and
settled the instance.

The same comparison appears elsewhere: a debounce compares a generation
number, and optimistic rollback compares a captured cache revision. The forms
module handles touched-field display and submit gating; settle-merge only
protects the draft from the server reply.

## Cancellation and supersession

| Concern | Handled by | Result |
| --- | --- | --- |
| An older write reply loses to a newer attempt under the same instance | Runtime | The older reply is suppressed; its continuation never runs |
| An older resource fetch loses to a newer generation of the same identity | Runtime | The stale reply cannot update the cache |
| A user dismisses an error or abandons a write | Application | Dispatch mutation clear; status clears and in-flight work is aborted best-effort |
| A view no longer needs a resource | Application | Release the owner that caused the read; a request no owner needs is aborted best-effort |
| Which attempts compete | Application | The instance id and resource params define the race |

Unmounting a screen does not cancel its writes, because a mutation may still
matter after navigation. Cancel through the domain event that owns the write.

## Cause a view-scoped read from an event

Most reads belong to the page, but some exist only while one part of the
screen is showing: search suggestions, picker options, a hover preview. Those
still need a cause. The event that decides the data is wanted ensures the
read, and the event that dismisses it releases it:

```clojure
(rf/reg-event :todo.search/wanted
  (fn [_ [_ q]]
    {:fx [[:dispatch [:rf.resource/release-owner {:owner [:todo.search]}]]
          [:dispatch [:rf.resource/ensure
                      {:resource       :todo/search
                       :params         {:q q}
                       :owner          [:todo.search]
                       :cause          [:todo.search/wanted q]
                       :keep-previous? true}]]]}))

(rf/reg-event :todo.search/dismissed
  (fn [_ _]
    {:fx [[:dispatch [:rf.resource/release-owner {:owner [:todo.search]}]]]}))
```

- **An owner keeps an entry alive until it is released.** `:owner` is any
  value you choose; releasing it drops that owner from every entry it holds.
  The release above runs before the ensure, so the previous query stops being
  owned as the new one starts.
- **Release does not delete the entry.** The entry then follows its
  `:gc-after-ms` policy, and a later ensure can rejoin it while it is warm.
- **Ensures share work.** Concurrent ensures of the same identity share one
  request, and ensuring an entry still fresh under `:stale-after-ms` serves the
  cached value without fetching.
- **Other policies stay in their own layers.** Debounce, staleness, refresh
  with previous data, supersession, and transport cancellation are each set
  where they belong. `:keep-previous?` goes on the ensure, not the
  subscription.

An owner that is never released keeps its entry out of garbage collection,
which is why the dismiss event exists.

## Search example

Register the resource with its cache policies:

```clojure
(rf/reg-resource :todo/search
  {:params-schema  [:map [:q :string]]
   :scope          :rf.scope/global
   :stale-after-ms 30000
   :gc-after-ms    60000}
  (fn [{:keys [q]} _ctx]
    {:request {:method :get
               :url    "/api/todos/search"
               :params {:q q}}
     :decode  :json}))
```

Keep each controlled keystroke synchronous and debounce only the committed
query the resource uses. A generation number stops an old timer from
committing:

```clojure
(rf/reg-sub :todo.search/text
  (fn [db _]
    (get-in db [:todo.search :text] "")))

(rf/reg-sub :todo.search/committed-q
  (fn [db _]
    (get-in db [:todo.search :committed-q] "")))

(rf/reg-event :todo.search/input
  (fn [{:keys [db]} [_ text]]
    (let [gen (inc (get-in db [:todo.search :gen] 0))]
      {:db (-> db
               (assoc-in [:todo.search :text] text)
               (assoc-in [:todo.search :gen] gen))
       :fx [[:dispatch-later
             {:ms 250
              :event [:todo.search/settle gen]}]]})))

(rf/reg-event :todo.search/settle
  (fn [{:keys [db]} [_ gen]]
    (if (= gen (get-in db [:todo.search :gen]))
      (let [q (get-in db [:todo.search :text])]
        {:db (assoc-in db [:todo.search :committed-q] q)
         :fx [[:dispatch [:rf.resource/release-owner {:owner [:todo.search]}]]
              [:dispatch [:rf.resource/ensure
                          {:resource       :todo/search
                           :params         {:q q}
                           :owner          [:todo.search]
                           :cause          [:todo.search/settle q]
                           :keep-previous? true}]]]})
      {})))

(rf/reg-event :todo.search/clear
  (fn [{:keys [db]} _]
    {:db (update db :todo.search assoc
                 :text ""
                 :committed-q ""
                 :gen (inc (get-in db [:todo.search :gen] 0)))
     :fx [[:dispatch [:rf.resource/release-owner {:owner [:todo.search]}]]]}))
```

The result view reads the cache. The previous results it shows while a new
query loads come from the `:keep-previous?` on the ensure:

```clojure
(h/defview search-results [{:keys [q]}]
  (let [{:keys [data loading? fetching?]}
        (h/sub [:rf/resource {:resource :todo/search
                              :params   {:q q}}])]
    [:ul.search-results
     {:aria-busy (boolean (or loading? fetching?))}
     (if (and loading? (not data))
       [:li.hint "Searching…"]
       (for [{:keys [id title]} (:todos data)]
         [:li {:key id} title]))]))

(h/defview todo-search [_]
  (let [text (h/sub [:todo.search/text])
        q    (h/sub [:todo.search/committed-q])]
    [:div.search
     [:input {:type        :search
              :value       text
              :placeholder "Search todos"
              :on-input    [:todo.search/input ::h/value]
              :on-key-down {"Escape" [:todo.search/clear]}}]
     (when (seq q)
       [search-results {:q q}])]))
```

What happens as the user types:

1. Each keystroke updates `:todo.search/text` immediately and increments the
   generation. No fetch starts yet.
2. After 250 ms of quiet, only the current generation commits `q`. The same
   handler ensures the resource under the `[:todo.search]` owner; that ensure,
   not the list mounting, starts the fetch.
3. A new committed query releases the old identity and ensures the new one.
   The old request is aborted best-effort once no owner needs it, and a late
   reply is suppressed. `:keep-previous? true` keeps the previous results
   visible while `:fetching?` reports the refresh.
4. Escape releases the owner. Nothing infers that from the view disappearing,
   which is why `:todo.search/clear` does it.
5. Repeating a query before its `:gc-after-ms` expiry rejoins the cached entry,
   and if it is still fresh under `:stale-after-ms` no request is made.

Xray shows which owners hold an entry and which cause started a fetch. On the
server nothing dispatches the ensure, so the client's own events cause the
first fetch after hydration.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A resource stays `:idle` | Nothing caused it; a subscription never fetches | Give it a cause: route `:resources`, a prefetch, or `[:rf.resource/ensure …]` from the event that decides the data is wanted |
| Boot raises `:rf.error/resources-artefact-missing` | The resources model was not loaded | Require `re-frame.resources`, normally with `re-frame.http.managed` |
| Search results flash empty on every new query | Each params value is a separate cache entry, and previous data is not kept | Set `:keep-previous? true` on the `[:rf.resource/ensure …]`, and use `:fetching?` for the refresh state |
| Old query results replace new ones | Fetching was done outside the resource model | Register the resource and let the runtime suppress stale replies |
| A resource read throws after render | `h/sub` escaped into a callback, promise, timer, or deferred sequence | Read during the synchronous body and keep the resulting value |
| Every checkbox becomes pending together | All writes share one instance id | Include the todo's id, such as `[:todo/set-done id]` |
| A save reply erases keystrokes typed after submission | The continuation overwrote the whole draft | Settle-merge against the accepted `:params` |
| Optimistic rollback overwrites a concurrent update | The conflict policy forces the old snapshot | Keep the default `:on-conflict :invalidate` unless forced rollback is a business rule |
| A dismissed or unmounted view still receives a mutation reply | Mutations are not cancelled by view lifetime | Choose an instance and an explicit clear policy |

## When an event is the wrong cause

| Job | Better cause |
| --- | --- |
| Data required by the current URL | Route `:resources`, including SSR and transition blocking |
| Warming data before any view needs it | `[:rf.route/prefetch address]` from a link's `:on-mouse-enter`, or an explicit ensure |
| Manual refresh | A `[:rf.resource/refetch …]` event |
| One-off uncached request | Managed HTTP |
| Drafts, field validation, and submit gating | Forms module |
| Multi-stage workflow | A state machine that causes the resource reads and transitions |

??? info "For readers coming from TanStack Query"
    A resource is close to a query, but a TanStack query fetches because a
    component rendered `useQuery`, whereas a Fresco subscription only reads
    the cache and an event causes the fetch. Debounce is event policy,
    staleness and GC stay on the registration, and supersession follows
    explicit identities. Mutations invalidate declared tags rather than
    relying on a later `invalidateQueries` call.

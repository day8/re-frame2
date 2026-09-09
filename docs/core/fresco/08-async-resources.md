# Async resources

The core resources model owns registered reads, cache identity, causes,
mutation status, invalidation, and managed transport. Hicasso views consume
those facts with `h/sub`. This page covers the view-facing race patterns:
merging a late reply into a newer draft, per-instance mutation state,
optimistic updates, cancellation, and causing a view-scoped read from an event.

## Merge a settled reply into the current draft

A save reply may be current for the request while the user has already changed
the draft. Writing the complete server value over the draft deletes those new
edits. Dropping the reply loses accepted normalization.

Two mechanisms solve different races:

- **Supersession** is enforced by the runtime. A reply from an older attempt
  under the same instance is suppressed and never reaches the continuation.
- **Settle-merge** is application policy. A current reply is compared with the
  params that were sent, field by field, so newer draft values win their own
  slots.

Register and execute the mutation normally:

```clojure
(ns app.profile
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.http.managed]))

(rf/reg-mutation :profile/save
  {:params-schema [:map
                   [:username :string]
                   [:bio :string]
                   [:email :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [username]} _result]
                    #{[:profile username]})}
  (fn [profile _ctx]
    {:request {:method :put
               :url    "/api/profile"
               :body   {:profile profile}}
     :decode  :json}))

[:rf.mutation/execute
 {:mutation :profile/save
  :params   draft
  :instance [:profile-save]
  :reply-to [:profile/save-settled]}]
```

The uniform reply includes the accepted `:params`, so the continuation has the
basis used by the request:

```clojure
(rf/reg-event :profile/save-settled
  (fn [{:keys [db]} [_ {:keys [status value params error]}]]
    (if (= :ok status)
      {:db (-> db
               (assoc-in [:profile :saved]
                         (:profile value))
               (update-in
                [:profile :draft]
                (fn [draft]
                  (reduce-kv
                   (fn [d field sent]
                     (if (= (get d field) sent)
                       (assoc d field
                              (get-in value [:profile field]))
                       d))
                   draft
                   params))))}
      {:db (assoc-in db [:profile :save-error] error)})))
```

A field that still equals the sent value accepts the server result. A field
that changed after the request keeps the new draft value. `:reply-to` runs only
after the runtime has accepted the reply as current, applied cache
consequences, and settled the mutation instance.

The same correlation shape appears elsewhere: a debounce compares a generation
number, and optimistic rollback compares a captured cache basis. The forms
module owns touched-field display and submit gating; settle-merge only protects
the draft/server race.

## Track writes per instance

A screen may have many independent writes of the same mutation. Choose an
instance id that matches the unit whose status the UI should display:

```clojure
(h/defview favorite-button [{:keys [slug favorited?]}]
  (let [save (h/sub [:rf/mutation
                     {:instance [:favorite slug]}])]
    [:button
     {:class    (when favorited? "active")
      :disabled (:pending? save)
      :on-click [:rf.mutation/execute
                 {:mutation :article/favorite
                  :params   {:slug slug}
                  :instance [:favorite slug]
                  :cause    [:click :article/favorite]}]}
     (if favorited? "Favorited" "Favorite")]))
```

The event vector contains the mutation, params, instance, and cause as data.
The instance subscription provides `:pending?`, `:success?`, `:error?`,
`:settled?`, `:optimistic?`, `:result`, and `:error`. Focused subscriptions
such as `[:rf.mutation/pending? {:instance …}]` are available when a view needs
one projection.

Different instance ids settle independently. Re-executing the same instance
supersedes its previous pending attempt, so the older reply cannot update the
cache or invoke its continuation. A settled error remains until another
execute replaces it or `[:rf.mutation/clear {:instance …}]` dismisses it. Clear
also requests a best-effort abort if work is still in flight.

Instance identity is therefore policy. `[:favorite slug]` allows rows to write
concurrently; `[:favorite]` makes every row compete for one status and
supersession lane.

## Optimistic updates and rollback

Declare an optimistic patch on the mutation registration. The runtime snapshots
the current entries, applies the patch before the request, and settles that
snapshot on success, error, or cancellation:

```clojure
(rf/reg-mutation :article/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :optimistic-tags
   (fn [{:keys [slug]}]
     [{:scope :rf.scope/global
       :tags  #{[:article slug]}
       :patch (fn [old]
                (update-in old [:article :favorited] not))}])
   :invalidates
   (fn [{:keys [slug]} _result]
     #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post
               :url (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

On error or cancellation, the runtime restores the captured snapshot rather
than asking application code to calculate an inverse patch. On success, the
authoritative reply replaces the optimistic guess.

If another write changes the entry between the optimistic patch and settlement,
the default `:on-conflict :invalidate` avoids restoring an old snapshot over
newer data. It marks the entry stale so a refetch can resolve the conflict.
`:on-conflict :force` should be used only when overwriting concurrent truth is
explicitly intended. The instance reports `:optimistic? true` until the guess
is settled.

## Cancellation and supersession ownership

| Concern | Owner | Result |
| --- | --- | --- |
| An older write reply loses to a newer attempt under the same instance | Runtime | Older reply is suppressed; its continuation never runs |
| An older resource fetch loses to a newer generation of the same identity | Runtime | Stale reply cannot update the cache |
| A user dismisses an error or abandons a write | Application | Dispatch mutation clear; status clears and in-flight work is aborted best-effort |
| A view no longer needs a resource | Application | Release the owner that caused the read; a request no remaining owner needs aborts best-effort |
| Which attempts compete | Application identity design | Instance id and resource params define the race |

No screen unmount automatically cancels every write. A mutation may remain
meaningful after navigation. Express cancellation through the domain state or
explicit clear event that owns it.

## Cause a view-scoped read from an event

Route `:resources` should own data required by page identity. Some reads instead
exist only while a local view is on screen: typeahead suggestions, picker
options, or a hover preview. Those still have a cause, and it is an event.

**A subscription never fetches.** `[:rf/resource …]` is a passive projection of
the cache — route entry, events and machines cause the work. A view that only
subscribes reads `:idle` forever, and that `:idle` is honest rather than a
missing lifecycle hook. So the event that decides the view is wanted is the
event that ensures the read, and the event that dismisses the view releases it:

```clojure
(rf/reg-event :suggestions/wanted
  (fn [_ [_ q]]
    {:fx [[:dispatch [:rf.resource/release-owner {:owner [:suggestions]}]]
          [:dispatch [:rf.resource/ensure
                      {:resource       :app/suggestions
                       :params         {:q q}
                       :owner          [:suggestions]
                       :cause          [:suggestions/wanted q]
                       :keep-previous? true}]]]}))

(rf/reg-event :suggestions/dismissed
  (fn [_ _]
    {:fx [[:dispatch [:rf.resource/release-owner {:owner [:suggestions]}]]]}))
```

The pattern follows these rules:

- **An owner pins liveness, so something has to release it.** `:owner` is any
  value you choose; releasing it drops that owner from every entry it holds. The
  release above runs before the ensure, so the previous query stops being owned
  as the new one starts.
- **Release is not immediate cache deletion.** The ownership hold ends, then the
  entry follows its normal `:gc-after-ms` policy. A later ensure may rejoin a
  warm entry.
- **Ensure joins and skips.** Concurrent ensures of the same identity share one
  request, and an ensure of an entry that is still fresh under `:stale-after-ms`
  serves the cached value without fetching.
- **The ensure does not choose every policy.** Debounce, staleness, refresh with
  previous data, supersession, and transport cancellation remain explicit in
  their own layers. `:keep-previous?` rides the ensure, not the subscription.

An owner that is never released keeps its entry out of GC, which is the one
bookkeeping cost of a view-scoped read and the reason the dismiss event exists.

## Typeahead example

Register the resource with cache policies:

```clojure
(rf/reg-resource :app/suggestions
  {:params-schema  [:map [:q :string]]
   :scope          :rf.scope/global
   :stale-after-ms 30000
   :gc-after-ms    60000}
  (fn [{:keys [q]} _ctx]
    {:request {:method :get
               :url    "/api/suggest"
               :params {:q q}}
     :decode  :json}))
```

Keep each controlled keystroke synchronous and debounce only the committed
query consumed by the resource. A generation prevents an old timer from
committing:

```clojure
(rf/reg-sub :search/text
  (fn [db _]
    (get-in db [:search :text] "")))

(rf/reg-sub :search/committed-q
  (fn [db _]
    (get-in db [:search :committed-q] "")))

(rf/reg-event :search/input
  (fn [{:keys [db]} [_ text]]
    (let [gen (inc (get-in db [:search :gen] 0))]
      {:db (-> db
               (assoc-in [:search :text] text)
               (assoc-in [:search :gen] gen))
       :fx [[:dispatch-later
             {:ms 250
              :event [:search/settle gen]}]]})))

(rf/reg-event :search/settle
  (fn [{:keys [db]} [_ gen]]
    (if (= gen (get-in db [:search :gen]))
      (let [q (get-in db [:search :text])]
        {:db (assoc-in db [:search :committed-q] q)
         :fx [[:dispatch [:rf.resource/release-owner {:owner [:search]}]]
              [:dispatch [:rf.resource/ensure
                          {:resource       :app/suggestions
                           :params         {:q q}
                           :owner          [:search]
                           :cause          [:search/settle q]
                           :keep-previous? true}]]]})
      {})))

(rf/reg-event :search/clear
  (fn [{:keys [db]} _]
    {:db (update db :search assoc
                 :text ""
                 :committed-q ""
                 :gen (inc (get-in db [:search :gen] 0)))
     :fx [[:dispatch [:rf.resource/release-owner {:owner [:search]}]]]}))
```

The result view reads the cache passively. The previous data it shows during a
parameter change was arranged by the `:keep-previous?` on the ensure above:

```clojure
(h/defview suggestion-list [{:keys [q]}]
  (let [{:keys [data loading? fetching?]}
        (h/sub
         [:rf/resource
          {:resource :app/suggestions
           :params   {:q q}}])]
    [:ul.suggestions
     {:aria-busy (boolean (or loading? fetching?))}
     (if (and loading? (not data))
       [:li.hint "Searching…"]
       (for [s (:suggestions data)]
         [:li {:key (:id s)}
          (:label s)]))]))

(h/defview search-box []
  (let [text (h/sub [:search/text])
        q    (h/sub [:search/committed-q])]
    [:div.search
     [:input {:type        :search
              :value       text
              :placeholder "Search articles"
              :on-input    [:search/input ::h/value]
              :on-key-down {"Escape" [:search/clear]}}]
     (when (seq q)
       [suggestion-list {:q q}])]))
```

The lifecycle is observable:

1. Keystrokes update `:search/text` immediately. Each one increments the
   generation; no fetch starts yet.
2. After 250 ms of quiet, only the current generation commits `q`. The same
   handler ensures the resource under the `[:search]` owner, and that ensure —
   not the list mounting — is what starts the fetch.
3. A new committed query releases the old identity and ensures the new one.
   The old transport aborts best-effort once no owner needs it, and any late
   reply is suppressed by generation checks. The ensure's `:keep-previous? true`
   keeps previous results visible while `:fetching?` reports the refresh.
4. Escape releases the owner explicitly. Nothing infers that from the view
   disappearing, which is why `:search/clear` says so.
5. Repeating a query before its `:gc-after-ms` expiry rejoins the cached entry.
   If it is still fresh under `:stale-after-ms`, the ensure makes no request.

Xray can show which owners hold an entry and which cause started a fetch. On the
server a passive read causes nothing, so no ensure runs there; the client's own
events cause the first fetch after hydration.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A resource remains permanently `:idle` | Nothing caused it — a subscription is a passive read and never fetches | Give it a cause: route `:resources`, a prefetch, or an `[:rf.resource/ensure …]` from the event that decides the data is wanted |
| Boot raises `:rf.error/resources-artefact-missing` | The resources model was not loaded | Require `re-frame.resources`, normally with `re-frame.http.managed` |
| Typeahead flashes empty on every new query | Each params value is a different cache identity and previous data is hidden | Set `:keep-previous? true` on the `[:rf.resource/ensure …]`, not on the subscription, and use `:fetching?` for the quiet refresh state |
| Old query results replace new ones | Fetching was implemented outside the resource identity/generation model | Register the resource and let the runtime suppress stale replies |
| A resource read throws after render | `h/sub` escaped into a callback, promise, timer, or deferred sequence | Read during the synchronous body and retain the resulting status/data value |
| Every row button becomes pending together | All writes use one instance id | Include row identity, such as `[:favorite slug]` |
| A save reply erases keystrokes entered after submission | The continuation overwrote the complete draft | Settle-merge against the accepted `:params`; preserve fields that no longer equal the sent basis |
| Optimistic rollback overwrites a concurrent update | Conflict policy forces the old snapshot | Use the default `:on-conflict :invalidate` unless forced rollback is an explicit business rule |
| A cleared/unmounted view still receives a meaningful mutation reply | Mutations are not implicitly cancelled by view lifetime | Choose an instance and explicit cancellation/clear policy; do not treat unmount as ownership unless the domain says so |

## When an event is the wrong owner

| Job | Better owner |
| --- | --- |
| Data required by the current URL | Route `:resources`, including SSR and transition blocking |
| Warming data before any view needs it | `[:rf.route/prefetch address]` from a link's `:on-mouse-enter`, or an explicit ensure |
| Manual refresh | `[:rf.resource/refetch …]` event |
| One-off uncached request | Managed HTTP |
| Drafts, field validation, and submit gating | Forms module |
| Multi-stage workflow | A state machine that owns resource causes and transitions |

Reach for a view-scoped ensure only when no other cause already owns the data.
A read the URL determines belongs to the route, where SSR and transition
blocking can see it.

??? info "For readers coming from TanStack Query"
    The closest analogy is a query, but the acquisition is not the same: a
    TanStack query fetches because a component rendered `useQuery`, whereas
    a Hicasso subscription only projects the cache and an event causes the
    fetch. Debounce remains event policy, staleness and GC stay on the
    registration, and supersession follows explicit identities. Mutations
    invalidate declared causal tags rather than depending on a later call-site
    `invalidateQueries`.

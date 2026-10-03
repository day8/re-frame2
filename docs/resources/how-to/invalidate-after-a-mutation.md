# Invalidate after a mutation

Saving an article changes its cached detail and any lists showing it. Tag those
reads and declare the matching tags on the mutation. Every save then refreshes
the affected reads without another command in the button.

## 1. Tag the reads

A [cache tag](../glossary.md#cache-tag) names a *fact*, not a resource. `[:article "welcome"]` and `[:article-list]` are facts — a specific article, and the list as a whole. When two resources carry the same tag, a write that names the tag reaches both.

```clojure
;; cf. examples/real-apps/realworld_resources/resources.cljs
(ns app.article-writes
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.http.managed]))

;; These anonymous reads return the same response for every viewer.
;; The detail read — tagged with the article's identity and the list identity.
(rf/reg-resource :realworld/article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :tags          (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

;; The list read — tagged with the list identity and every article it contains,
;; so a write to one article reaches any list currently showing it.
(rf/reg-resource :realworld/articles
  {:params-schema [:map]
   :scope         :rf.scope/global
   :tags          (fn [_params data]
                    (into #{[:article-list]}
                          (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/articles"} :decode :json}))
```

The `:tags` fn receives the resource's params and its decoded data, so the list can tag itself with one `[:article slug]` per article it actually holds. That overlap is how one write finds every read it touches. `:tags` is optional; a resource with no tags can't be reached by tag invalidation, though you can still `:rf.resource/refetch` it by exact key.

Every registration here uses `:scope :rf.scope/global`, the one cache everyone shares. Tags are matched *within* a scope, which matters as soon as some reads are per-user — [Match the read's scope](#the-scope-footgun-and-how-to-disarm-it) covers that case.

## 2. Declare what the write breaks

A mutation is the write counterpart to a resource read. Its `:invalidates` key names the tags this write makes stale on success:

```clojure
(rf/reg-mutation :realworld/save-article
  {:params-schema [:map [:slug :string] [:title :string] [:body :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug] :as article} _ctx]
    {:request {:method :put
               :url    (str "/api/articles/" slug)
               :body   {:article article}}
     :decode  :json}))
```

On success, the runtime finds every cached entry carrying one of those tags and marks it stale.

What happens next depends on whether anything is still using the entry. An entry is **owned** while something holds it — a route showing the article, a running [machine](../../machines/glossary.md#machine) that asked for it (see [owner](../glossary.md#owner--cause)). Owned entries refetch immediately, because something is waiting for the fresh value. Unowned entries are only marked stale, and refetch the next time something ensures them. Data nothing is watching causes no refetch storm.

`:invalidates` is a function of `(params result)`: the accepted params and the decoded reply. It gets no `db`. When a plan needs a value from [app-db](../../core/glossary.md#app-db), such as the current user's scope, it names a resolver (the `{:from-db …}` form in [the scope section](#the-scope-footgun-and-how-to-disarm-it)), which keeps the plan plain data that tools can inspect.

The write goes through the same [managed HTTP](../glossary.md#managed-http) transport as your reads, and **it does not retry by default** — no managed request does. Re-sending a write can repeat its side effect (charge the card twice, post the comment twice), so a mutation retries only when the managed-HTTP args returned by its request function declare `:retry` beside `:request`. Leave it off unless the endpoint is idempotent. Auth headers and other decoration belong in a `reg-http-interceptor`, which decorates every managed request, rather than in each mutation's `:request`. If that interceptor makes a read viewer-dependent, give the read and mutation the same viewer scope.

A write whose params carry a secret — a password, a card number — names the path on the registration, `:sensitive [[:params :password]]`, so the value leaves the app as a redaction marker ([data classification](../../core/glossary.md#data-classification)).

!!! warning "Gotcha — a lone vector is one tag"

    A tag is a vector, so a tag *set* is a set of vectors. If you return a single bare vector — `(fn [_ _] [:article slug])` — the runtime reads it as the one tag `#{[:article slug]}`, not as the set `#{:article slug}`. The same rule applies to the `:tags` of a direct `:rf.resource/invalidate-tags` event. When in doubt, wrap it: `#{[:article slug]}`.

## 3. Fire the write, watch the instance

Fire the write from a view with `:rf.mutation/execute`, and watch its progress through the `[:rf/mutation {:instance …}]` [subscription](../../core/glossary.md#subscription):

```clojure
(rf/reg-view article-save-button [{:keys [article]}]
  (let [save @(subscribe [:rf/mutation {:instance [:article-save (:slug article)]}])]
    [:<>
     [:button {:disabled (:pending? save)
               :on-click #(dispatch [:rf.mutation/execute
                                     {:mutation :realworld/save-article
                                      :params   article
                                      :instance [:article-save (:slug article)]
                                      :cause    [:form-submit :realworld/save-article]}])}
      (if (:pending? save) "Saving…" "Save")]
     (when (:error? save) [:p "Could not save this article. Try again."])]))
```

The subscription only watches the write. The button uses `:pending?` to prevent
duplicate submissions and `:error?` to render a failed attempt. The instance id is per slug, so two article saves keep separate lifecycles.

The click uses the `dispatch` that `reg-view` injects, which carries this view's [frame](../../core/glossary.md#frame); a bare `rf/dispatch` in a click callback runs outside render and has no frame to find.

The registration already declares which reads this write changes.

### See which reads refresh

The cell below runs this page's registrations and button against canned
replies, and lists each request. The detail entry has an owner; the list was
loaded without one. Click **Save**: the detail refetches at once, while the
list is only marked stale. **Open the list** ensures it, and because it is
stale it refetches; a second click is a cache hit.

```cljs-rf2
(require '[clojure.string :as str]
         '[re-frame.core :as rf]
         '[re-frame.resources]
         '[re-frame.http.managed]
         '[re-frame.http.test-support :as http-test-support]
         '[reagent2.core :as r])

;; Canned replies for the two reads and the save.
(def welcome {:slug "welcome" :title "Welcome" :body "The first article."})
(http-test-support/install-managed-request-stubs!
  {[:get "/api/articles/welcome"] {:reply {:ok {:article welcome}}}
   [:get "/api/articles"]         {:reply {:ok {:articles [welcome]}}}
   [:put "/api/articles/welcome"] {:reply {:ok {:article welcome}}}})

;; Log each request that reaches the stubbed API.
(def requests (r/atom []))
(rf/reg-http-interceptor :demo/request-log
  {:frame  :writes
   :before (fn [ctx]
             (let [{:keys [method url]} (:request ctx)]
               (swap! requests conj (str (str/upper-case (name method)) " " url))
               ctx))})

(rf/reg-resource :realworld/article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :tags          (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

(rf/reg-resource :realworld/articles
  {:params-schema [:map]
   :scope         :rf.scope/global
   :tags          (fn [_params data]
                    (into #{[:article-list]}
                          (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/articles"} :decode :json}))

(rf/reg-mutation :realworld/save-article
  {:params-schema [:map [:slug :string] [:title :string] [:body :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug] :as article} _ctx]
    {:request {:method :put
               :url    (str "/api/articles/" slug)
               :body   {:article article}}
     :decode  :json}))

(rf/reg-view article-save-button [{:keys [article]}]
  (let [save @(subscribe [:rf/mutation {:instance [:article-save (:slug article)]}])]
    [:<>
     [:button {:disabled (:pending? save)
               :on-click #(dispatch [:rf.mutation/execute
                                     {:mutation :realworld/save-article
                                      :params   article
                                      :instance [:article-save (:slug article)]
                                      :cause    [:form-submit :realworld/save-article]}])}
      (if (:pending? save) "Saving…" "Save")]
     (when (:error? save) [:p "Could not save this article. Try again."])]))

(rf/reg-view entry-row [{:keys [label query]}]
  (let [state @(subscribe [:rf/resource query])]
    [:tr [:td label] [:td [:code (pr-str (:status state))]] [:td (if (:stale? state) "stale" "fresh")]]))

(def detail {:resource :realworld/article :params {:slug "welcome"}})
(def article-list {:resource :realworld/articles :params {}})

(rf/reg-view demo []
  (let [article (:article @(subscribe [:rf.resource/data detail]))]
    [:div
     [:table
      [:tbody
       [entry-row {:label "Detail (owned)" :query detail}]
       [entry-row {:label "List (no owner)" :query article-list}]]]
     (when article [article-save-button {:article article}])
     " "
     [:button {:on-click #(dispatch [:rf.resource/ensure (assoc article-list :cause [:user :list/open])])}
      "Open the list"]
     (into [:ol] (for [r @requests] [:li r]))]))

[rf/frame-root {:id :writes
                :initial-events [[:rf.resource/ensure (assoc detail :owner [:demo/detail-panel])]
                                 [:rf.resource/ensure (assoc article-list :cause [:demo/warm])]]
                :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [demo]]
```

### Resetting an instance after an error

Retry with `:rf.mutation/execute` under the same instance. To dismiss a settled
error, dispatch `[:rf.mutation/clear {:instance [:article-save slug]}]`.
Clearing a pending write also tries to abort it and suppresses its reply; the
server may already have accepted it. Clear after settling when cache
consequences must run.

<a id="the-scope-footgun-and-how-to-disarm-it"></a>

## Match the read's scope

Tags match within a scope. The global reads above are reached by a mutation
with global scope. If a read is viewer-dependent, the mutation must use the same
viewer scope or its tags match nothing.

Declare that scope on the mutation, as [Optimistic updates](optimistic-updates.md#patch-one-loaded-article)
does for the article read and write. An execute can also supply a scope for that
attempt. Without either declaration, a mutation uses global scope.

When one write breaks reads in more than one scope, return a vector of **descriptors**, one per scope, each naming its own:

```clojure
:invalidates (fn [{:keys [slug]} _result]
               [{:scope :rf.scope/global
                 :tags  #{[:article slug] [:article-list]}}
                {:scope {:from-db :realworld/session}     ;; a named scope resolver
                 :tags  #{[:feed]}}])
```

The global descriptor reaches the public reads. The session descriptor reaches the signed-in user's feed. Register that resolver with [reg-resource-scope](../../api/re-frame.resources.md#reg-resource-scope), as the [scope tutorial](../tutorial/04-scopes-and-guards.md) does.

A resolver returning `nil` skips that descriptor rather than using global data.
If the same tags match entries only in a different scope, development builds
emit `:rf.warning/mutation-scope-mismatch`. The reference records the
[accepted scope forms](../../api/re-frame.resources.md#the-mutation-spec).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| A matching read does not refresh | It is unowned | Attach an owner through a route or an ensure with a matching release |
| `:rf.warning/mutation-scope-mismatch` | Tags match in another scope | Declare the read's scope on the mutation or return scoped descriptors |
| `:rf.error/mutation-invalid-params` | Params fail the registered schema | Pass the declared slug, title and body |
| A reply clears the wrong form | Several forms share an instance id | Give each form or visit its own instance |

<a id="observe-it-in-xray"></a>

Save with the list or detail mounted and inspect the invalidation in
[Xray](../../xray/13-resources.md). Its matched keys and refetch count show whether
the tags, scope and owners agree. [Testing resources](../testing.md#3-invalidation-and-mutations)
checks those consequences without a network.

## Advanced

<a id="the-rfmutationexecute-payload-and-the-focused-rfmutation-subs"></a>

The [execute reference](../../api/re-frame.resources.md#rfmutationexecute-)
records the complete payload. [Mutation subscriptions](../../api/re-frame.resources.md#mutation-subscriptions-passive)
list the returned fields and focused subscriptions.

<a id="4-optional-the-other-cache-consequences"></a>
<a id="when-the-invalidation-fires-invalidate-timing"></a>

`:patches` updates an existing exact entry; `:removes` evicts one after a delete.
`:invalidate-timing` changes when tags become stale. Keep the default
`:after-success` for ordinary saves. [The mutation spec](../../api/re-frame.resources.md#the-mutation-spec)
records the accepted shapes, ordering and errors.

<a id="5-optional-seed-the-cache-from-the-reply"></a>

### Seed the cache from the reply

When the response is a complete replacement for a cached read, add `:populates`
to the save registration:

```clojure
:populates (fn [{:keys [slug]} result]
             {{:resource :realworld/article :params {:slug slug}
               :scope :rf.scope/global} result})
```

Store the whole `{:article ...}` response, matching the resource's decoded value.
The populated entry becomes fresh and this mutation's own invalidation skips it.
For a partial reply, use the reference's `:refetch-populated?` policy.

<a id="6-optional-do-more-than-refresh-the-cache"></a>

A successful save that also navigates names a `:reply-to` event on its execute.
[The editor tutorial](../tutorial/05-mutations-and-invalidation.md#publish-from-the-editor--and-continue-with-reply-to)
shows a complete form and guards a reply against an obsolete editor visit.
The reply runs after cache consequences; they remain on the registration.

### Invalidate from any event

For a server notification rather than a mutation, dispatch the same tags
explicitly:

```clojure
(rf/reg-event :article/server-changed
  (fn [_ [_ {:keys [slug]}]]
    {:fx [[:dispatch [:rf.resource/invalidate-tags
                      {:scope :rf.scope/global
                       :tags #{[:article slug]}
                       :cause [:server-push :article-changed]}]]]}))
```

<a id="when-you-cant-name-the-scopes-cross-scope-true"></a>

Direct invalidation requires a scope. `:cross-scope? true` reaches every matching
scope in the frame and needs cause evidence; use it when those scopes cannot be
named. [The invalidation reference](../../api/re-frame.resources.md#rfresourceinvalidate-tags-)
records the exact payload.

<a id="advanced-optimistic-writes"></a>

[Optimistic updates](optimistic-updates.md) makes a favorite appear before its
reply and rolls back on failure. Use it for a small reversible change after the
ordinary save and invalidation path works.

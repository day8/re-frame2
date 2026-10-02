# Optimistic updates

A favorite can appear as soon as the user clicks, before the server replies.
Declare an `:optimistic` patch on the mutation; the runtime records the old cache
value and restores it if the write fails.

Use this for a small reversible change. When waiting for confirmation is
acceptable, [ordinary mutation invalidation](invalidate-after-a-mutation.md)
is enough.

## Patch one loaded article

This example runs on a signed-in article page. The
[authentication flow](../../core/how-to/add-auth.md) supplies
`[:auth :user :username]` and stamps managed requests with the current token.
Both the read and write use the same viewer scope.

```clojure
;; cf. examples/capabilities/resources/linearlite/core.cljs
;; and examples/real-apps/realworld_resources/mutations.cljs
(ns app.favorites
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.http.managed]))

(rf/reg-resource-scope :article/viewer
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username [:rf.scope/viewer {:username username}])))

(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope {:from-db :article/viewer}
   :tags (fn [{:keys [slug]} _data] #{[:article slug]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode :json}))

(rf/reg-mutation :article/favorite
  {:params-schema [:map [:slug :string]]
   :scope {:from-db :article/viewer}
   :optimistic (fn [{:keys [slug]}]
                 {{:resource :article/by-slug :params {:slug slug}}
                  (fn [data] (assoc-in data [:article :favorited] true))})
   :populates (fn [{:keys [slug]} result]
                {{:resource :article/by-slug :params {:slug slug}} result})
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode :json}))
```

A route or event [ensures the article](../concepts.md#cause-a-fetch) before the
button renders. The GET and POST both return `{:article ...}`.

`:optimistic` changes the cached `:favorited` flag before the request starts.
`:populates` replaces that guess with the server's complete response on success.
Targets without an explicit scope inherit the mutation's resolved viewer scope.

## Keep the control disabled until the write settles

```clojure
(rf/reg-view favorite-button [{:keys [slug]}]
  (let [article (get-in @(subscribe [:rf/resource
                                    {:resource :article/by-slug :params {:slug slug}}])
                       [:data :article])
        write @(subscribe [:rf/mutation {:instance [:favorite slug]}])]
    [:div
     [:button {:disabled (or (nil? article) (:favorited article) (:pending? write))
               :on-click #(dispatch [:rf.mutation/execute
                                     {:mutation :article/favorite
                                      :params {:slug slug}
                                      :instance [:favorite slug]}])}
      (if (:favorited article) "Favorited" "Favorite")]
     (when (:error? write) [:p "Could not favorite this article. Try again."])]))
```

The flag changes immediately, then stays on success or reverts on failure.
Keeping the control disabled while pending prevents overlapping writes from
this button. Suppressing an old reply protects client state; it cannot control
the order in which the server applies requests.

## Handle a conflicting rollback

If another write changed the entry while this one was pending, the old snapshot
is no longer safe to restore. The default `:on-conflict :invalidate` marks that
entry stale and refreshes it if owned. Keep this default for normal application
writes.

A new execution under the same instance or a clear of a pending instance also
rolls back the abandoned apply and marks its entries stale. The server may still
have processed the abandoned request. [The mutation reference](../../api/re-frame.resources.md#the-mutation-spec)
records settlement and conflict options.

## Advanced

### Update every read showing the article

When tagged lists contain the same article, replace `:optimistic` with
`:optimistic-tags` so every matching read changes together. The patch handles
both the detail envelope and list envelope:

```clojure
(defn favorite-in-data [slug data]
  (cond-> data
    (contains? data :article)
    (assoc-in [:article :favorited] true)

    (contains? data :articles)
    (update :articles
            (fn [articles]
              (mapv #(if (= slug (:slug %)) (assoc % :favorited true) %) articles)))))

;; In :article/favorite's metadata, instead of :optimistic:
:optimistic-tags
(fn [{:keys [slug]}]
  [{:scope {:from-db :article/viewer}
    :tags #{[:article slug]}
    :patch #(favorite-in-data slug %)}])
```

The [tagged-read recipe](invalidate-after-a-mutation.md#1-tag-the-reads) shows
how lists attach `[:article slug]` for their rows. Give those viewer-dependent
lists the same scope resolver. Lists in a different scope, such as a personal
feed, need their own descriptor. `:populates` still confirms the detail, and
`:invalidates` refreshes the other matching reads after success.

Infinite feeds refresh as a whole; their page vectors are not item-level patch
targets. Use [feed invalidation](load-more.md#refresh-the-loaded-feed) for them.

### Verify rollback in the inspector

In [the RealWorld resources example](../../../examples/real-apps/realworld_resources),
favorite an article while a detail and tagged list show it. Check that both
change before the reply. [The focused linearlite example](../../../examples/capabilities/resources/linearlite)
has a failure toggle to verify rollback and isolates a contested rollback.

`:optimistic?` on the mutation subscription is true while the unconfirmed apply
is showing. Registration with an optimistic plan and
`:invalidate-timing :before-request` raises
`:rf.error/mutation-optimistic-before-request`; keep the ordinary success timing.

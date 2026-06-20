# Invalidate after a mutation

Your app just wrote to the server. It saved an article, posted a comment, toggled a favorite. The cached reads covering that data are now wrong, and every view still showing them is now showing the past. This guide wires that write to invalidate exactly those reads, so the views refetch automatically and nothing else moves.

> **Coming from TanStack Query?** Your anchor is `queryClient.invalidateQueries({ queryKey: ['articles'] })` inside a mutation's `onSuccess`. Same instinct here, with two deliberate differences. First, in re-frame2 you **declare the invalidation as data on the mutation registration** rather than calling it imperatively in a callback at every call site — which means you write it once, not at every place the mutation fires. Second, it matches by tags within a scope, so it refetches only the entries something on screen still owns.

Here's the idea underneath. The write that made the cache stale is the thing that says so. A timer only guesses, and polling pays for that guess on every interval. The mutation, by contrast, actually knows: it just changed the data, so it names the reads it broke, once, at registration. That's what we mean by invalidation being *causal* — the cause of the staleness declares it directly.

That covers your app's own writes. Staleness caused by *other* users is a different problem, and that's what `:stale-after-ms` and focus/reconnect revalidation are for.

You need two things in place before any of this works. Boot the resources artefact (`day8/re-frame2-resources`) by putting `re-frame.resources` plus the `re-frame.http.managed` transport on your require list. Then register your reads with `reg-resource` — a resource is a managed server-state read, and registering it is how the framework knows how to fetch and cache it. If you haven't done that yet, start at [Server state: resources](../concepts/server-state.md).

## 1. Tag the reads

Tags name *facts*, not resources. `[:article "welcome"]` and `[:article-list]` are facts — a specific article, and the list as a whole. This is the part that trips people up at first: when two resources carry the same tag, that tag becomes the join key a write uses to reach both of them at once.

```clojure
;; Adapted from examples/reagent/realworld_resources/resources.cljs
;; The detail read — tagged with the article's identity and the list identity.
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :tags    (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}
     :decode  :json}))

;; The list read — tagged with the list identity AND every article it contains,
;; so a write to one article reaches any list currently showing it.
(rf/reg-resource :article/list
  {:params-schema [:map]
   :scope         :rf.scope/global
   :tags    (fn [_params data]
              (into #{[:article-list]}
                    (map (fn [a] [:article (:slug a)]) (:articles data))))}
  (fn [_params _ctx]
    {:request {:method :get :url "/api/articles"} :decode :json}))
```

## 2. Declare what the write breaks

A mutation is a managed server-state *write* — the write counterpart to a resource read. Its `:invalidates` key names the tags this write makes stale on success, and that's the causal heart of the whole page.

```clojure
(rf/reg-mutation :article/save
  {:params-schema [:map [:slug :string] [:title :string] [:body :string]]
   :scope         :rf.scope/global
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug] :as article} _ctx]
    {:request {:method :put
               :url    (str "/api/articles/" slug)
               :body   {:article article}}
     :decode  :json}))
```

On success this runs through the same scoped, owner-aware engine as `:rf.resource/invalidate-tags`. Entries whose tags intersect get marked stale. The ones something still owns — a mounted route, a live machine — refetch immediately, while unowned ones simply go stale and wait until their next ensure. So you don't get a refetch storm for data nothing is watching, which is the behavior you want.

## 3. Fire the write, watch the instance

```clojure
;; `subscribe` / `dispatch` are the frame-bound locals reg-view injects —
;; the click callback fires outside render, where a bare rf/dispatch has
;; no frame context.
(rf/reg-view article-editor [article]
  (let [save @(subscribe [:rf.mutation/state
                          {:instance [:article-save (:slug article)]}])]
    [:<>
     [editor-fields article]
     [:button {:disabled (:pending? save)
               :on-click #(dispatch [:rf.mutation/execute
                                     {:mutation :article/save
                                      :params   article
                                      :instance [:article-save (:slug article)]
                                      :cause    [:form-submit :article/save]}])}
      (if (:pending? save) "Saving…" "Save")]
     (when (:error? save) [save-error (:error save)])]))
```

A quick tour of what's happening here. A subscription is a read-only view into derived state, and `dispatch` is how you send an event — a request for something to happen — into the system. The per-slug `:instance` id keeps two concurrent submissions from clobbering each other. (`editor-fields` and `save-error` are your own child views.) Now notice what's *absent*: the view never dispatches an invalidate, never refetches a list, and never touches `app-db` — your app's single state map. It doesn't have to, because the registration already declared which reads this write breaks.

## 4. Optional: seed the cache from the reply

Sometimes the write's reply carries the updated data back to you. `:populates` puts that data straight into the cache *before* the invalidation runs, so the change appears instantly with no refetch round-trip.

```clojure
;; Adapted from examples/reagent/realworld_resources/mutations.cljs
(rf/reg-mutation :article/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; The value MUST be the resource's stored shape — exactly what its own
   ;; request fn + :decode would have produced.
   :populates   (fn [{:keys [slug]} result]
                  {{:resource :article/by-slug :params {:slug slug}} result})
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

A populated key counts as an **authoritative load**, which means it's exempt from this same mutation's invalidation pass. So invalidating broad tags doesn't immediately re-fetch the entry you just seeded from the reply — the framework trusts the value you handed it.

!!! note "Populate runs on success — reach for `:optimistic` to flip before the reply"

    `:populates` seeds the cache from the *accepted reply*, so it runs only after the server confirms. If a write must flip the UI immediately and revert on rejection, declare an **optimistic plan** instead: `:optimistic` (exact target) or `:optimistic-tags` (tag-addressed) patches the cache *before* the request is sent, and the runtime commits, rolls back, or reconciles it deterministically on settle — `:on-conflict` (default `:invalidate`) governs a contested rollback. See [Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations) and the worked write in [Part 4 of the tutorial](../tutorial/04-mutations-and-invalidation.md).

!!! warning "A bare tag set matches only in the mutation's resolved scope"

    A scope is the boundary within which tags are matched. Zero matches is legitimate, so a global mutation that means to refresh a session-scoped read — the user's personalized feed, say — will **silently miss**: no error, no refetch, just stale data. When one write breaks reads in more than one scope, use descriptors, one scope per target:

    ```clojure
    :invalidates (fn [{:keys [slug]} _result]
                   [{:scope :rf.scope/global
                     :tags  #{[:article slug] [:article-list]}}
                    {:scope {:from-db :app/session}     ;; a named scope resolver
                     :tags  #{[:feed]}}])
    ```

## Observe it in Xray

Save an article with the list and detail pages mounted, then open Xray's Resources tab:

- **Live instances** — both entries flip `:loaded → :fetching` (prior data stays visible) `→ :loaded`, with a new generation.
- **Invalidation / mutation graph** — one row per invalidation: the resolved scope, the tags, the matched keys, the match count, and the refetch count. A **zero match count** here is the scope-miss footgun made visible.
- **Lifecycle timeline** — the ordered `:rf.resource/*` rows, each carrying its cause: the why-chain from your mutation to each refetch.

The full read→write→invalidate→refetch loop runs live in [the RealWorld resources example](../../../examples/reagent/realworld_resources/); the normative contract is [Spec 016 — Resources](../../../spec/016-Resources.md).

---

You can now:

- tag the resource reads a write can break, using shared tags as the join key
- declare `:invalidates` (and `:populates`) on a mutation — with per-scope descriptors when one write breaks reads in more than one scope
- verify a write's invalidation — or catch a zero-match scope miss — in Xray's Resources tab

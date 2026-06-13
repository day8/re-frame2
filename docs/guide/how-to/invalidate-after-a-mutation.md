# Invalidate after a mutation

Your app just wrote to the server — saved an article, posted a comment, toggled a favorite — and the cached reads covering that data are now wrong. This guide wires the write itself to invalidate exactly those reads, so every view still showing them refetches automatically, and nothing else moves.

If you know TanStack Query, the anchor is `queryClient.invalidateQueries({ queryKey: ['articles'] })` inside a mutation's `onSuccess`. Same instinct, two deliberate differences: in re-frame2 the invalidation is **declared on the mutation registration as data**, not called imperatively in a callback at every call site; and it matches by **tags within a scope**, refetching only entries something on screen still owns.

The principle underneath: **invalidation is causal — the write that made the cache stale is the thing that says so.** A timer guesses; polling pays for that guess on every interval. The mutation *knows*: it just changed the data, so it names the reads it broke, once, at registration. (That covers your app's own writes; staleness caused by *other* users is what `:stale-after-ms` and focus/reconnect revalidation are for.)

You need the resources artefact (`day8/re-frame2-resources`) booted — `re-frame.resources` plus the `re-frame.http-managed` transport on your require list — and reads registered with `reg-resource`. If not, start at [Server state: resources](../concepts/server-state.md).

## 1. Tag the reads

Tags name *facts* — `[:article "welcome"]`, `[:article-list]` — not resources. A tag carried by two resources is the join key a write will use to reach both.

```clojure
;; Adapted from examples/reagent/realworld_resources/resources.cljs
;; The detail read — tagged with the article's identity and the list identity.
(rf/reg-resource :article/by-slug
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request (fn [{:keys [slug]} _ctx]
              {:request {:method :get :url (str "/api/articles/" slug)}
               :decode  :json})
   :tags    (fn [{:keys [slug]} _data] #{[:article slug] [:article-list]})})

;; The list read — tagged with the list identity AND every article it contains,
;; so a write to one article reaches any list currently showing it.
(rf/reg-resource :article/list
  {:params-schema [:map]
   :scope         :rf.scope/global
   :request (fn [_params _ctx]
              {:request {:method :get :url "/api/articles"} :decode :json})
   :tags    (fn [_params data]
              (into #{[:article-list]}
                    (map (fn [a] [:article (:slug a)]) (:articles data))))})
```

## 2. Declare what the write breaks

`:invalidates` on the mutation is the causal heart — the tags this write makes stale on success:

```clojure
(rf/reg-mutation :article/save
  {:params-schema [:map [:slug :string] [:title :string] [:body :string]]
   :scope         :rf.scope/global
   :request (fn [{:keys [slug] :as article} _ctx]
              {:request {:method :put
                         :url    (str "/api/articles/" slug)
                         :body   {:article article}}
               :decode  :json})
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})})
```

On success this lowers through the same scoped, owner-aware engine as `:rf.resource/invalidate-tags`: entries whose tags intersect are marked stale; entries something still owns — a mounted route, a live machine — refetch immediately; unowned ones just go stale until their next ensure. No refetch storm for data nothing is watching.

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

The per-slug `:instance` id keeps two concurrent submissions from clobbering each other. (`editor-fields` and `save-error` are your own child views.) Notice what's absent: the view never dispatches an invalidate, never refetches a list, never touches `app-db`. The registration already said which reads this write breaks.

## 4. Optional: seed the cache from the reply

When the write's reply carries the updated data, `:populates` puts it in the cache *before* the invalidation — the change appears instantly, no refetch round-trip:

```clojure
;; Adapted from examples/reagent/realworld_resources/mutations.cljs
(rf/reg-mutation :article/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request (fn [{:keys [slug]} _ctx]
              {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
               :decode  :json})
   ;; The value MUST be the resource's stored shape — exactly what its own
   ;; :request + :decode would have produced.
   :populates   (fn [{:keys [slug]} result]
                  {{:resource :article/by-slug :params {:slug slug}} result})
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})})
```

A populated key counts as an **authoritative load**: it's exempt from this same mutation's invalidation pass, so invalidating broad tags doesn't immediately re-fetch the entry you just seeded from the reply. Populate is forward-only — no automatic revert on failure (optimistic rollback is deferred). A write that must flip the UI and roll back on rejection stays a plain managed [HTTP](../concepts/http.md) write with an `:on-failure` handler.

> **Watch out: a bare tag set matches only in the mutation's resolved scope** — and zero matches is legitimate, so a global mutation that means to refresh a session-scoped read (the user's personalized feed) **silently misses**: no error, no refetch, just stale data. When one write breaks reads in more than one scope, use descriptors, one scope per target:
>
> ```clojure
> :invalidates (fn [{:keys [slug]} _result]
>                [{:scope :rf.scope/global
>                  :tags  #{[:article slug] [:article-list]}}
>                 {:scope {:from-db :app/session}     ;; a named scope resolver
>                  :tags  #{[:feed]}}])
> ```

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

**Next:** see the loop threaded through a real app in [Part 4: writes — favoriting, posting, invalidation](../tutorial/04-mutations-and-invalidation.md), or step back to the model in [Server state: resources](../concepts/server-state.md).

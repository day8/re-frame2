# Invalidate after a mutation

After a **write**, some cached reads are wrong. This recipe declares which ones —
and, when the reply already carries the answer, writes the cache directly — so the
UI stays correct without an `invalidateQueries` call you have to remember.

A [mutation](../glossary.md#mutation) knows what it just changed, so it names the
tags it broke **once, on its registration**. Every call site gets the same
consequences for free.

??? info "Coming from TanStack Query?"

    Your anchor is `queryClient.invalidateQueries(...)` in `onSuccess`. Here you
    **declare** invalidation on the mutation registration (once), and it matches by
    **tags within a scope** — only owned entries refetch immediately.

## 1. Tag the reads

A [cache tag](../glossary.md#cache-tag) names a *fact*, not a resource. `[:article "welcome"]` and `[:article-list]` are facts — a specific article, and the list as a whole. When two resources carry the same tag, a write that names the tag reaches both.

```clojure
;; Adapted from examples/real-apps/realworld_resources/resources.cljs
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

Every registration here uses `:scope :rf.scope/global`, the one cache everyone shares. Tags are matched *within* a scope, which matters as soon as some reads are per-user — [The scope footgun](#the-scope-footgun-and-how-to-disarm-it) covers that case.

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

The write goes through the same [managed HTTP](../glossary.md#managed-http) transport as your reads, and **it does not retry by default** — no managed request does. Re-sending a write can repeat its side effect (charge the card twice, post the comment twice), so a mutation retries only when its own `:request` declares `:retry`. Leave it off unless the endpoint is idempotent. Auth headers and other decoration belong in a `reg-http-interceptor`, which decorates every managed request, rather than in each mutation's `:request`.

A write whose params carry a secret — a password, a card number — names the path on the registration, `:sensitive [[:params :password]]`, so the value leaves the app as a redaction marker ([data classification](../../core/glossary.md#data-classification)).

!!! warning "Gotcha — a lone vector is one tag"

    A tag is a vector, so a tag *set* is a set of vectors. If you return a single bare vector — `(fn [_ _] [:article slug])` — the runtime reads it as the one tag `#{[:article slug]}`, not as the set `#{:article slug}`. The same rule applies to the `:tags` of a direct `:rf.resource/invalidate-tags` event. When in doubt, wrap it: `#{[:article slug]}`.

## 3. Fire the write, watch the instance

Fire the write from a view with `:rf.mutation/execute`, and watch its progress through the `[:rf/mutation {:instance …}]` [subscription](../../core/glossary.md#subscription):

```clojure
(rf/reg-view article-editor [{:keys [article]}]
  (let [save @(subscribe [:rf/mutation {:instance [:article-save (:slug article)]}])]
    [:<>
     [editor-fields {:article article}]
     [:button {:disabled (:pending? save)
               :on-click #(dispatch [:rf.mutation/execute
                                     {:mutation :realworld/save-article
                                      :params   article
                                      :instance [:article-save (:slug article)]
                                      :cause    [:form-submit :realworld/save-article]}])}
      (if (:pending? save) "Saving…" "Save")]
     (when (:error? save) [save-error (:error save)])]))
```

The subscription only watches the write; it never fires it. It yields `:status`, `:pending?`, `:success?`, `:error?`, `:settled?`, `:result`, `:error` and `:optimistic?`, which is what the button reads to disable itself and change its label. The `:instance` id is per slug, so saving two articles at once keeps two separate lifecycles. `editor-fields` and `save-error` are your own views.

The click uses the `dispatch` that `reg-view` injects, which carries this view's [frame](../../core/glossary.md#frame); a bare `rf/dispatch` in a click callback runs outside render and has no frame to find.

Notice what the view doesn't do: it never dispatches an invalidate, never refetches a list, and never touches app-db. The registration already said which reads this write breaks.

### The `:rf.mutation/execute` payload, and the focused `:rf.mutation/*` subs

| Key | Required | Meaning |
|---|---|---|
| `:mutation` | yes | The registered mutation id. |
| `:params` | yes | The params for this attempt — validated and canonicalized against `:params-schema`. |
| `:instance` | no | The instance id. Caller-supplied, or generated when omitted — supply one when a view watches the write. Two concurrent submissions under *different* instance ids never clobber each other's `:pending` / `:success` / `:error`; re-executing under the *same* instance supersedes the earlier attempt and suppresses its stale reply. |
| `:scope` | no | The execution scope the invalidation runs in (see [The scope footgun](#the-scope-footgun-and-how-to-disarm-it)). Optional — a mutation defaults to `:rf.scope/global`. |
| `:cause` | no | Trace data explaining why the write fired. It never changes behaviour. |
| `:reply-to` | no | A call-site continuation event (see [§6](#6-optional-do-more-than-refresh-the-cache)). |
| `:optimistic?` | no | `false` forces the pessimistic path for one call, skipping a registered optimistic plan. |

!!! warning "Gotcha — bad `:params` fail before the write fires"

    `:params` are checked against the mutation's `:params-schema` before the request is built, so a payload that doesn't conform never reaches the server: the execute raises `:rf.error/mutation-invalid-params`, with the offending value redacted per the [data-classification](../../core/glossary.md#data-classification) policy. Host values — functions, promises, dates, DOM nodes — are rejected the same way, because params must be serializable EDN to take part in identity and replay.

When a view needs only one fact about the instance, a focused sub projects just that:

```clojure
[:rf/mutation          {:instance [:article-save slug]}]   ;; the whole view-model
[:rf.mutation/status   {:instance [:article-save slug]}]   ;; :idle | :pending | :success | :error
[:rf.mutation/pending? {:instance [:article-save slug]}]   ;; boolean
[:rf.mutation/result   {:instance [:article-save slug]}]   ;; the decoded reply value, or nil
[:rf.mutation/error    {:instance [:article-save slug]}]   ;; the structured error envelope, or nil
```

### Resetting an instance after an error

A failed write settles `:error?` with the structured error under `:error`. There is no `:refresh-error` here — a write has no last-known-good data to keep. The instance stays settled until you clear it, so the error stays on screen until the user acts. To retry, dispatch `:rf.mutation/execute` again under the same instance; the new attempt supersedes the failed one. To dismiss the error without retrying:

```clojure
[:rf.mutation/clear {:instance [:article-save slug]}]
```

`:rf.mutation/clear` resets the runtime instance (and makes a best-effort abort of any in-flight work for it). `{:mutation <id>}` in place of `:instance` clears every instance of that mutation. It is not `(rf/clear :mutation mutation-id)`, which unregisters the mutation entirely.

That is the whole normal path: tag the reads, declare what the write breaks, fire it and watch the instance. The rest of this page is for writes that need more than "mark it stale and refetch."

## 4. Optional: the other cache consequences

`:invalidates` is the one you'll use most. When the reply already tells you the new value, three more arms write the cache directly and skip the round-trip. All four take the same `(params result)` arguments:

| Arm | Shape | What it does |
|---|---|---|
| `:invalidates` | `(fn [params result] -> tag-set-or-descriptors)` | Marks matching tags stale; owned reads refetch, unowned ones wait. |
| `:populates` | `(fn [params result] -> {target value})` | Seeds an exact resource entry with the reply's value, *as if it had just loaded* (see [§5](#5-optional-seed-the-cache-from-the-reply)). |
| `:patches` | `(fn [params result] -> {target (fn [old result] new)})` | Transforms an **existing** exact entry in place. It targets exact keys only, never tags; a target with no data is left alone. |
| `:removes` | `(fn [params result] -> [target …])` | Drops exact entries — the cache half of a delete (the key is removed and its in-flight request aborted where possible). |

A *target* names one entry with the map `{:resource <id> :params <params> :scope <scope>}`. The `:scope` may be a concrete value, `:rf.scope/same` (the mutation's own resolved scope), `:rf.scope/global`, or a `{:from-db …}` resolver reference. This map is the only accepted form; don't hand-build the internal `[scope resource-id params]` tuple.

`:patches`, `:populates` and `:removes` run only on success; `:invalidates` runs on success by default (see `:invalidate-timing` below). They run in a fixed order — **patches, populates, removes, then `:invalidates`** — so an entry you patch or populate already holds its new value when the invalidation pass decides what to refetch. When one key is both patched and populated, the populate wins.

A `:patches` arm edits an entry without refetching it — here, bumping the favorite count on the article detail:

```clojure
:patches (fn [{:keys [slug]} _result]
           {{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global}
            (fn [old _result] (update-in old [:article :favoritesCount] inc))})
```

A `:removes` arm is the one you reach for on a delete:

```clojure
(rf/reg-mutation :realworld/delete-article
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; Drop the detail entry — there's nothing left to refetch — then invalidate
   ;; the list tag so any mounted list re-reads without the deleted row.
   :removes     (fn [{:keys [slug]} _result]
                  [{:resource :realworld/article
                    :params   {:slug slug}
                    :scope    :rf.scope/global}])
   :invalidates (fn [_params _result] #{[:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :delete :url (str "/api/articles/" slug)}
     :decode  :auto}))
```

!!! warning "Gotcha — a bad target is skipped, a wrong identity throws"

    Patches, populates and removes run after the server has already accepted the write, so a recoverable bad target doesn't throw away the rest. A target naming an **unregistered** resource, or a non-map target, is skipped with a dev-only `:rf.warning/mutation-target-skipped`; the other targets in the same arm still apply, and the skipped one is recorded on the instance. A target that would write under a **wrong identity** — a misspelled `:rf.scope/*` keyword, a non-EDN scope or params — still throws the whole arm.

### When the invalidation fires: `:invalidate-timing`

Invalidation runs on success by default, because the data is only stale once the server confirms the write. Most mutations never change that, but the timing is a registration key:

```clojure
:invalidate-timing :after-settle   ;; default :after-success
```

- **`:after-success`** *(default)* — invalidate when the write is accepted.
- **`:before-request`** — mark the reads stale when the write *starts*. Use it when a mounted view should show a fresh-fetch spinner during the write rather than the soon-wrong old value. Combining it with an optimistic plan raises `:rf.error/mutation-optimistic-before-request` at registration.
- **`:after-failure`** — invalidate only when the write *fails*. Niche: you wrote the cache yourself elsewhere and want the truth back on rejection.
- **`:after-settle`** — invalidate on either outcome.

## 5. Optional: seed the cache from the reply

When the reply carries the updated data, `:populates` puts it straight into the cache before the invalidation runs, so the change appears with no refetch:

```clojure
;; Adapted from examples/real-apps/realworld_resources/mutations.cljs
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; key: the exact entry; value: what its own request would have stored
   :populates   (fn [{:keys [slug]} result]
                  {{:resource :realworld/article
                    :params   {:slug slug}
                    :scope    :rf.scope/global} result})
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

A populated key counts as an **authoritative load**: it becomes `:loaded`, the value becomes its `:data`, and its freshness timers start as if it had fetched. So this mutation's own invalidation pass skips it, even though the broad tags match. Later events, later mutations and focus/reconnect can still invalidate it.

!!! warning "Gotcha — populate the resource's *stored* shape"

    Populate the whole decoded envelope the resource stores (e.g. `{:article {…}}`), not the inner article. A populated entry has to read exactly like a fetched one, or the next view to read that key sees a shape no fetch would produce.

!!! note "Partial replies — `:refetch-populated? true`"

    If the reply is only part of what a full GET returns, you don't want to keep the half-populated value. Opt one invalidation descriptor into `:refetch-populated? true` and that key is refetched after all:

    ```clojure
    :invalidates (fn [{:keys [slug]} _result]
                   [{:scope :rf.scope/global
                     :tags  #{[:article slug]}
                     :refetch-populated? true}])
    ```

    It changes exactly one thing: whether a key this mutation populated may be refetched by this mutation's own invalidation. The default is no.

`:populates` waits for the server. To flip the UI *before* the reply and roll back on failure, see [Advanced: optimistic writes](#advanced-optimistic-writes).

## 6. Optional: do more than refresh the cache

Sometimes a write should also show a toast, navigate to the new article, or focus a field. That's app behaviour, not a cache consequence, so it doesn't belong in `:invalidates` or `:populates`. Pass a call-site `:reply-to` event instead:

```clojure
[:rf.mutation/execute
 {:mutation :realworld/save-article
  :params   (assoc draft :slug slug)   ;; {:slug :title :body}, per the mutation's :params-schema
  :instance [:editor/save slug]
  :reply-to [:editor/save-replied]}]
```

When the reply is accepted as current, the runtime dispatches `:reply-to` with **one reply map appended as the final argument**, so your handler receives `[:editor/save-replied reply]`. The reply map is the [uniform reply](../../core/glossary.md#the-uniform-reply) every managed async operation returns, plus the facts a continuation needs: `:status` (`:ok` / `:error` / `:cancelled`), `:value` (the decoded value, on `:ok`), `:error` (on `:error`), `:mutation`, `:instance`, `:params`, `:scope`, `:affected-keys`, and `:cause [:mutation <id> <instance>]`.

```clojure
(rf/reg-event :editor/save-replied
  (fn [_cofx [_ {:keys [status value]}]]
    (case status
      :ok    {:fx [[:dispatch [:toast/show "Saved"]]
                   [:dispatch [:rf.route/navigate {:to     :realworld.article/show
                                                   :params {:slug (-> value :article :slug)}}]]]}
      :error {:fx [[:dispatch [:toast/error "Save failed — try again"]]]}
      {})))
```

Two rules make `:reply-to` safe to rely on:

- **It fires for an accepted terminal reply, never for a stale one.** Re-execute under the same instance and the earlier attempt's reply is suppressed, so its continuation never runs.
- **It fires once, after the cache consequences and after the instance settles.** By the time `:editor/save-replied` runs, the cache is already patched, populated or invalidated, so you can read it — or `:value` — and trust it.

`:reply-to` is a call-site option, not a `reg-mutation` key, because it is workflow: this save toasts and navigates, that one only toasts. Cache consequences are the same for every call, so they live on the registration. And because the continuation is an event vector rather than a callback, it appears in the trace and replays like any other event.

## The scope footgun (and how to disarm it)

A [scope](../glossary.md#scope) is the boundary within which tags are matched. A bare tag set — the `#{[:article slug] …}` form used so far — matches **only** in the mutation's own resolved scope. That is usually right, and zero matches is a legitimate outcome. But it means a *global* mutation meant to refresh a *session-scoped* read — the user's personal feed, say — **silently misses**: no error, no refetch, stale data on screen.

A mutation's scope resolves in this order: the execute payload's `:scope`, then the mutation's `:scope`, then `:rf.scope/global`. Unlike a resource's scope, it is optional: a write has no cached read of its own to leak, so defaulting to global is safe. (A scope you *do* supply is still validated; a misspelled `:rf.scope/*` keyword raises.) The catch is that the mutation then invalidates *in the global scope*, and your session-scoped read lives in a different one.

When one write breaks reads in more than one scope, return a vector of **descriptors**, one per scope, each naming its own:

```clojure
:invalidates (fn [{:keys [slug]} _result]
               [{:scope :rf.scope/global
                 :tags  #{[:article slug] [:article-list]}}
                {:scope {:from-db :realworld/session}     ;; a named scope resolver
                 :tags  #{[:feed]}}])
```

The global descriptor refreshes the article and the list, the session descriptor refreshes the feed through its `{:from-db …}` resolver, and nothing falls through the gap.

A descriptor's `:scope` is one of: `:rf.scope/same` (the mutation's resolved scope — the default when `:scope` is omitted, and the meaning of a bare tag set), `:rf.scope/global`, a concrete scope value, or a `{:from-db <resolver-id>}` reference resolved against the db when the write settles. A `{:from-db …}` that resolves to `nil` drops that descriptor; it is never widened to global.

Dev builds catch the miss for you. When a descriptor matches nothing in its resolved scope but the same tags *do* match an entry in another scope, the runtime emits `:rf.warning/mutation-scope-mismatch`, naming the mutation, the instance, both scopes and the tags, with a `:hint` naming the fix. The warning is [elided](../../core/glossary.md#elide) from production builds. A deliberate `:cross-scope? true` descriptor is never flagged, and neither is a tag that matches nothing anywhere.

### Invalidate from any event

A change the server tells you about — a websocket push, a poll that reports an update — has no mutation to declare its consequences. Dispatch `:rf.resource/invalidate-tags` from that event's handler instead; owned matches refetch and unowned ones go stale, as after a mutation:

```clojure
(rf/reg-event :ws/article-changed
  (fn [_cofx [_ {:keys [slug]}]]
    {:fx [[:dispatch [:rf.resource/invalidate-tags
                      {:scope :rf.scope/global
                       :tags  #{[:article slug]}
                       :cause [:server-push :article-changed]}]]]}))
```

It is stricter than a mutation about scope: with no `:scope` it raises `:rf.error/resource-invalidate-scope-required` instead of defaulting, and a `{:from-db …}` scope that resolves to `nil` raises `:rf.error/resource-scope-unresolved-reference` instead of dropping the invalidation.

### When you can't name the scopes: `:cross-scope? true`

A descriptor can only name scopes you know. Occasionally you need "invalidate this tag *wherever it lives*": admin tooling clearing one fact for every tenant, a poisoned cache, a data migration. That is `:cross-scope? true`:

```clojure
:invalidates (fn [{:keys [article-id]} _result]
               [{:tags         #{[:article article-id]}
                 :cross-scope? true}])
```

Because it can stale or refetch data for *every* user, tenant, story frame and SSR request, the runtime treats it as a privacy-relevant operation:

- It **must** carry `:cause` evidence. A mutation's sweep always does — the runtime stamps `[:mutation <id> <instance>]` on it — while a direct `[:rf.resource/invalidate-tags {:cross-scope? true …}]` with no `:cause` raises `:rf.error/resource-cross-scope-cause-required`.
- It emits a privacy-relevant [trace event](../../core/glossary.md#trace-event) recording that a mutation reached outside its own scope.
- [Xray](../../core/glossary.md#xray) marks the invalidation as cross-scope, so a sweep never looks like a precise invalidation.

Use `:cross-scope?` only when the scopes genuinely can't be named. If you can name them, use descriptors.

## Advanced: optimistic writes

Everything above updates the cache *after* the server confirms. An [optimistic update](../glossary.md#optimistic-update--rollback) flips the UI *before* it confirms, and the runtime reconciles when the reply lands. Use it when a write must feel instant — a favorite toggle, a like count, an item that should vanish on click — and a possible rollback is acceptable. [Part 4 of the tutorial](../tutorial/04-mutations-and-invalidation.md) has a worked example.

An optimistic plan is a registration key, in two forms that mirror `:patches` and `:invalidates`:

```clojure
(rf/reg-mutation :realworld/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; Exact-target form: patch one known key. The patch fn is (fn [old-data] -> new-data)
   ;; — there's no result yet. A nil patch fn removes the entry optimistically; a patch
   ;; over an absent key seeds it.
   :optimistic  (fn [{:keys [slug]}]
                  {{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global}
                   (fn [old] (update-in old [:article :favorited] not))})
   ;; Tag-addressed form: patch every cached entry carrying these tags at once
   ;; (the detail, every list, the feed), so the toggle is consistent across views.
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug]}
                        :patch (fn [old] (update-in old [:article :favorited] not))}])
   :populates   (fn [{:keys [slug]} result]
                  {{:resource :realworld/article :params {:slug slug} :scope :rf.scope/global} result})
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

The runtime does the rest:

- **It records the inverse.** Before each forward patch it snapshots the whole entry as it stood (or notes that the key was absent), so a rollback restores exactly what existed. You never write a rollback.
- **Settle is deterministic: commit, roll back, or reconcile.** On an `:ok` reply the authoritative `:populates` / `:patches` / `:invalidates` overwrite the optimistic value and the snapshot is discarded (`:rf.mutation/optimistic-reconciled`). On an `:error` or `:cancelled` reply it rolls back (`:rf.mutation/optimistic-rolled-back`). A stale reply writes nothing, but its apply isn't forgotten: a re-execute under the same `:instance` rolls back the keys it doesn't re-patch and inherits your original snapshot for the ones it does, and `:rf.mutation/clear` of a pending write rolls it back. Because the abandoned write may still have reached the server, those rollbacks mark the key stale and refetch it if something is watching. That is recovery, not write ordering — if the order of two writes matters, disable the control until the first settles.
- **`:on-conflict` decides a contested rollback.** If another write changed the entry between your optimistic apply and the rollback, restoring your snapshot would overwrite newer data. The default `:on-conflict :invalidate` marks the entry stale in its own scope and refetches the server's value instead. `:force` restores your snapshot anyway and emits `:rf.warning/optimistic-force-clobber`. (TanStack and SWR restore the snapshot unconditionally.) Any other value raises at `reg-mutation`.
- **The view can tell.** `[:rf/mutation {:instance …}]` carries a derived `:optimistic?`, true between the apply and the settle, so you can render "pending, but already showing your change."

!!! warning "Gotcha — optimistic targets fail closed and stay in their scope"

    An optimistic apply writes the cache, so its targets carry the same leak boundary a read does: a `{:from-db …}` scope that resolves to `nil` drops the target rather than writing under global. There is no `:cross-scope?` optimistic form, so an optimistic write can't reach other users or tenants. A malformed `:optimistic-tags` descriptor (not a map, no `:patch`, `:tags` not a collection) is skipped with `:rf.warning/optimistic-tags-descriptor-skipped` rather than thrown, because throwing before the request would kill the whole write; the well-formed descriptors still apply. The exact-target `:optimistic` form is stricter: it runs before there is a committed write to stay consistent with, so it rejects every bad target.

To skip a registered optimistic plan for one call, pass `{:optimistic? false}` on the execute payload. It only disables the plan; a call site can't supply its own.

## Observe it in Xray

Save an article with the list and detail pages mounted, then open [Xray](../../core/glossary.md#xray)'s Resources tab:

- **Live instances** — both entries go `:loaded → :fetching` (prior data stays visible) `→ :loaded`, with a new generation.
- **Invalidation / mutation graph** — one row per invalidation: the resolved scope, the tags, the matched keys, the match count and the refetch count. A **zero match count** is the scope footgun made visible, and if the tags *do* match an entry in another scope, the `:any-tag-match-other-scope?` flag says so — the signal to reach for a descriptor.
- **Lifecycle timeline** — the ordered `:rf.resource/*` rows, each carrying its [cause](../glossary.md#owner--cause): the chain from your mutation to each refetch.

The full read → write → invalidate → refetch loop runs live in [the RealWorld resources example](../../../examples/real-apps/realworld_resources).

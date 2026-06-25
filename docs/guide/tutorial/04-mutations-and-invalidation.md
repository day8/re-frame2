# Part 4: writes — favoriting, posting, invalidation

In [Part 2](02-server-data.md) the app *read* server state through [resources](../glossary.md#resource) — a resource is a cached, declarative read of remote data. In [Part 3](03-auth-and-forms.md) you added login. Reads are only half a real app, though. The moment a user clicks the favorite heart on an article card, or hits **Publish Article** in the editor, the app has to *write* — and a write is harder than a read, because a write makes other reads wrong. Favorite an article and three cached reads go stale at once: the article detail, every list it appears in, and your personal feed.

The naive fix is to wire each write to "and now refetch these reads" at the call site. That works right up until you have forty call sites and one of them forgets. re-frame2's answer is a [**mutation**](../glossary.md#mutation): a write registered *once*, with its consequences — which cached reads it breaks — declared on the registration, not at the call site. Click the heart anywhere and the right reads refresh, because the write knows what it breaks.

This part lands three things, one at a time:

- the favorite heart fires a **mutation** that declares which cached reads it [invalidates](../glossary.md#invalidate), so the detail, the lists, and your feed all refresh with **no wiring at the call site**;
- publishing an article saves it, then *continues* — navigate to the new article, clear the form — via a **`:reply-to` event**, not a callback;
- a **`:can-leave` route guard** and a confirm dialog stop you from navigating away from a half-written draft.

> **Coming from RTK Query or TanStack Query?** A mutation here is RTK Query's mutation with `invalidatesTags`, with three differences. Invalidation is declared once on the write's *registration*, not per call site; every invalidation is **scoped** — your feed and another user's feed are different cache entries, and a write names which scopes it touches; and the post-write continuation is a dispatched [**event**](../glossary.md#event), not an `onSuccess` callback.

One phrase to file away now and watch pay off by the end — it won't fully land until the **Publish** section, and that's fine:

**A mutation's continuation — "and *then* do this next" — is itself a piece of data, not a callback. Which makes it inspectable, testable, and replayable.**

## The reads, ready to be broken

Before a write can invalidate a read, the reads have to be *labelled* so a write can name them without naming each one by hand. We did that labelling back in Part 2.

Each resource declared [`:tags`](../glossary.md#cache-tag) on its cached data. The article detail carries `[:article slug]`. The lists carry `[:article-list]` plus a tag per article they contain. Those tags are the shared vocabulary between writes and reads: a read says "my data is tagged `[:article slug]`," and later a write can say "I just made `[:article slug]` stale" — and the runtime matches the two up, no read named directly. Tags are how a write breaks a read it has never heard of.

One read is still missing: the **personal feed** (`GET /articles/feed`). Part 2 left it out on purpose, because what it returns depends on *who is asking* — your feed and another user's feed are different data, even though both come from the same URL. So its cache can't be one shared entry; it has to be keyed per user.

This is what a [**scope**](../glossary.md#scope) is: a namespace for cache entries. The resources in Part 2 all lived in one shared scope, `:rf.scope/global` — one entry per resource, the same for everybody. The feed needs a *different* scope per signed-in user, so each user's feed is cached separately and nobody ever sees anybody else's.

To key a cache per user, the runtime needs a way to compute the per-user key from your state. That's a **named scope resolver**: a small *pure* function that reads [app-db](../glossary.md#app-db) (your app's state map — see [Part 1](01-pages-and-state.md)) and returns a scope value, or `nil`:

```clojure
;; src/conduit/scope.cljs
;; cf. examples/reagent/realworld_resources/scope.cljs
(rf/reg-resource-scope :conduit/session
  {:doc     "The session's cache scope — nil when logged out (fail-closed)."
   :inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username [:rf.scope/session {:username username}]))})
```

Now register `:conduit/feed` exactly like Part 2's resources — tagged `#{[:feed]}` — but with `:scope {:from-db :conduit/session}` (meaning "resolve my scope through the `:conduit/session` resolver") instead of the default `:rf.scope/global`. Its cache entries are keyed by the signed-in username, so each user gets their own.

And here's the payoff of returning `nil` when logged out — what re-frame2 calls [**fail-closed**](../glossary.md#fail-loud-not-silent): signed out, the resolver returns `nil`, the scope can't be computed, and the read simply *fails* rather than falling back to some default entry. It never silently serves the previous user's feed. You'll see the term "fail-closed" recur on the write side too — same idea: when an identity can't be resolved, do nothing rather than guess.

That's the whole setup. The reads are tagged; the feed is scoped. Now let's write.

## Register the write

A mutation is the write-side counterpart of a resource. Where a resource describes a *read* and how its result is cached, a mutation describes a *write* and what that write makes stale. You register it with `reg-mutation`. Here is the favorite write, and at first read it's just two consequence keys plus a request fn:

```clojure
;; src/conduit/mutations.cljs
;; cf. examples/reagent/realworld_resources/mutations.cljs
(ns conduit.mutations
  (:require [re-frame.core :as rf]
            [re-frame.resources]      ;; reg-mutation + the :rf.mutation/* surface
            [re-frame.http.managed]   ;; the transport mutations lower through
            ;; Part 3's api base in a helper: (defn full-url [path] (str api path))
            [conduit.http :as rh]
            [conduit.schema :as schema]))

(rf/reg-mutation :conduit/favorite
  {:doc           "Favorite an article. POST /articles/:slug/favorite."
   :params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; Seed the cached article detail from the write's own reply — the heart
   ;; flips the moment the server confirms.
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}
                     result})
   ;; The reads this write breaks: article + lists (global scope), and the
   ;; signed-in user's feed (session scope).
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  #{[:article slug] [:article-list]}}
                     {:scope {:from-db :conduit/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post
               :url    (rh/full-url (str "/articles/" slug "/favorite"))}
     :decode  schema/ArticleResponse}))
```

Three things do the work:

- **The request fn** (the third positional argument) describes the HTTP write the way a resource describes its read. It must *not* supply `:on-success` / `:on-failure` / `:request-id` — the runtime decides where the reply goes, deliberately, not you. Handing that ownership to the runtime is what lets it later throw away a *stale* reply (a slow response to a write the user already superseded) before it can do any damage — a guarantee we cash in further down.
- **`:invalidates`** declares which tags the write makes stale on success. Favoriting breaks reads in *two* scopes: the article and lists are global, while your feed is keyed by session. So it returns a vector of *descriptors* — one per scope, each a map naming a scope and the tags it stales there. The second descriptor's scope is computed by running the `:conduit/session` resolver from earlier, at *settle time* (the moment the write's reply comes back and its consequences are applied). One write, both scopes, declared once.
- **`:populates`** seeds an exact cache entry from the write's own reply, *before* the invalidation runs. The favorite endpoint replies with the full updated article, so we write it straight into the `:conduit/article` entry — skipping a refetch entirely. One catch: the value you populate has to be in the *same shape the resource stores*. A normal load of `:conduit/article` caches the whole `{:article …}` map the server sent, so we hand `:populates` that same whole map (`result`), not just the article inside it. A populated entry counts as freshly loaded, so this mutation's own invalidation won't turn around and refetch the key it just learned.

Register `:conduit/unfavorite` the same way — same shape, `:method :delete`. The full registration surface lives in [Spec 016](../../../spec/016-Resources.md).

> **Gotcha — writes never retry by default.** There's one asymmetry from reads worth flagging up front. Re-sending a POST because the reply was slow is the classic double-submit bug, so a mutation retries *only* if its request map explicitly opts in. The favorite write doesn't, so a slow favorite simply waits — it never silently fires twice.

> **Coming from RTK Query?** `:invalidates` is `invalidatesTags`, and `:populates` is `updateQueryData` / `upsertQueryData` — except both live on the *registration* once, not in an `onQueryStarted` per call. The "which reads did this break" decision is made where the write is *defined*, so every call site inherits it.

That's a complete, working favorite write. The next section fires it; the deeper consequence keys (`:patches`, `:removes`, optimistic updates, timing) come after, once you've seen the basic loop run.

## Fire it, watch the instance

A resource is "a subscription you read and an event you fire" (a [*subscription*](../glossary.md#subscription) is a read of derived state; an [*event*](../glossary.md#event) is something you [`dispatch`](../glossary.md#dispatch)). A mutation is the mirror image: **an event you fire and an instance you watch.** The UI never calls the mutation directly. Instead it dispatches `:rf.mutation/execute` — `dispatch` being how every event enters the system:

```clojure
;; src/conduit/views.cljs
;; cf. examples/reagent/realworld_resources/views.cljs
(rf/reg-event :ui/favorite
  (fn [{:keys [db]} [_ slug favorited?]]
    (if (nil? (get-in db [:auth :user]))
      ;; Logged out, a favorite click goes to login instead of a 401.
      {:fx [[:dispatch [:rf.route/navigate :conduit.auth/login]]]}
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation (if favorited? :conduit/unfavorite :conduit/favorite)
                         :params   {:slug slug}
                         :instance [:favorite slug]
                         :cause    [:click :ui/favorite slug]}]]]})))

(reg-view favorite-button [{:keys [article]}]
  (let [{:keys [slug favorited favoritesCount]} article
        fav @(subscribe [:rf.mutation/state {:instance [:favorite slug]}])]
    [:button.btn.btn-outline-primary.btn-sm
     {:type     "button"
      :class    (when favorited "active")
      :disabled (:pending? fav)
      :on-click #(dispatch [:ui/favorite slug favorited])}
     [:i.ion-heart] " " favoritesCount]))
```

Pause on the `:instance` id, because this is where people get tripped up. Mutation state is keyed by **instance**, not by mutation id. `[:favorite slug]` gives every article card its own lifecycle, which means you can click hearts on three cards in quick succession and they can never clobber each other.

The view watches its instance through the passive `[:rf.mutation/state {:instance …}]` subscription, which returns the durable facts plus some derived booleans, computed for you:

```clojure
{:status :idle      ;; :idle | :pending | :success | :error
 :result …          ;; the decoded reply value, on success
 :error  …          ;; the structured error envelope, on failure
 :affected-keys […] ;; the cache keys this write touched
 :pending? … :success? … :error? … :settled? … :optimistic?}
```

That's where `:disabled (:pending? fav)` comes from. No `app-db` bookkeeping, no `:saving?` flag to maintain. (The `:optimistic?` flag is for the optimistic variant we meet later — true while an unconfirmed optimistic value is showing.)

Notice what the view *doesn't* do: it never invalidates anything. Add this button to the article cards from Part 1 and to the article page, and you're done. Favoriting behaves identically everywhere, because the write's consequences live on the write, not on the call site.

> **Coming from RTK Query?** `[:rf.mutation/state {:instance …}]` is the tuple `useMutation` hands back — `isLoading`, `isSuccess`, `error`, `data` — but keyed by an `:instance` id *you* choose, not bound to one component instance. Two views can watch the *same* in-flight write by naming the same instance, and a write survives the unmount of the component that fired it.

### Watch it happen

Run the app, sign in, and click a heart. The count changes immediately — that's `:populates` landing. A moment later the list and your feed have refetched. Now open [Xray](../glossary.md#xray) (the dev inspector from earlier parts) and click another heart: the trace shows the whole causal chain, step by step. The `:ui/favorite` dispatch fires; then `:rf.mutation/started`; then the HTTP request; then `succeeded`, carrying the invalidation evidence (which tags went stale, in which scopes); and finally the refetches of any stale reads still on screen. Every step names its cause. When a list refreshes "by itself" six months from now, this trace is how you'll know which write did it.

### The full execute payload

You've used three keys on `:rf.mutation/execute` (`:mutation`, `:instance`, `:cause`). The payload is a small, fixed set — everything you can hand it:

| Key | Required | What it does |
|---|---|---|
| `:mutation` | yes | The registered mutation id to run. |
| `:params` | yes | Params for this write — validated and canonicalized against the mutation's `:params-schema`. |
| `:instance` | yes | The instance id that keys this submission's lifecycle (caller-supplied, as here, or generated). |
| `:scope` | no | The execution scope the invalidation/patch/populate defaults to. Omit it and the mutation falls back to its spec `:scope`, then to `:rf.scope/global` (writes are fail-open on a *missing* scope — see the scope footgun below). |
| `:cause` | no | Trace/diagnostic data explaining what triggered the write. Pure metadata; it changes no behaviour, it just makes the trace readable. |
| `:reply-to` | no | A continuation event target dispatched once the write settles — the subject of the **Publish** section below. |
| `:optimistic?` | no | A per-call escape hatch: `{:optimistic? false}` forces the **pessimistic** path for this one call even when the mutation registered an optimistic plan. It's a boolean disable, never a per-call forward patch — call-site cache logic stays off the call site. |

And the sub family is wider than the one you've used. `:rf.mutation/state` returns the whole instance view-model; for the common single-fact reads there are convenience subs that project one slot each, all keyed the same way by `:instance`:

```clojure
[:rf.mutation/state    {:instance [:favorite slug]}]   ;; the whole map
[:rf.mutation/status   {:instance [:favorite slug]}]   ;; :idle | :pending | :success | :error
[:rf.mutation/pending? {:instance [:favorite slug]}]   ;; true while in flight
[:rf.mutation/result   {:instance [:favorite slug]}]   ;; the decoded reply value on success
[:rf.mutation/error    {:instance [:favorite slug]}]   ;; the structured error envelope on failure
```

> **Gotcha — `:result` on the instance, `:value` in a reply.** One spelling to file away early: the instance sub stores the decoded result under `:result`. The transient [reply map](../glossary.md#reply-map) a `:reply-to` continuation receives — coming up in the **Publish** section — spells the same value `:value`. Same data, two layers; we'll meet `:value` again there.

## Going further with mutations

The basic loop — register, fire, watch — is the whole story for most writes. This section is the depth: the other two cache-consequence arms, partial replies, scope correctness, timing, and optimistic updates. Read it when you hit the matching need; the favorite above already works without any of it.

### The other two consequence arms: `:patches` and `:removes`

`:populates` and `:invalidates` are the two you'll reach for most, but a mutation registration has *four* success-phase data plans, and the other two earn their keep on specific writes. All four share **one signature** — `(fn [params result] …)`, where `result` is the decoded reply value — and all run *before* the success-time invalidation.

**`:patches`** is `:populates`'s surgical sibling. Where `:populates` *replaces* an entry with a value (the full envelope from the reply), `:patches` *transforms* an existing entry through a function. It targets an exact key and updates only what changed — ideal when the reply tells you a delta rather than the whole record. It updates an **existing** key only (a patch over an absent key no-ops); it never targets tags.

```clojure
;; bump a comment count on the article detail without refetching it
:patches (fn [{:keys [slug]} _result]
           {{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}
            (fn [old] (update-in old [:article :commentsCount] inc))})
```

**`:removes`** is for delete writes. It drops exact cache entries — the resolved key is dissociated and any in-flight attempt for it best-effort aborted — so a `DELETE /articles/:slug` mutation evicts the now-gone article's detail entry rather than leaving a stale skeleton behind. It returns a vector of the same target maps `:populates` and `:patches` use:

```clojure
(rf/reg-mutation :conduit/delete-article
  {:doc           "Delete an article. DELETE /articles/:slug."
   :params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; drop the dead article's detail entry, then stale the lists + feed
   :removes       (fn [{:keys [slug]} _result]
                    [{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}])
   :invalidates   (fn [_ _result]
                    [{:scope :rf.scope/global :tags #{[:article-list]}}
                     {:scope {:from-db :conduit/session} :tags #{[:feed]}}])}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :delete :url (rh/full-url (str "/articles/" slug))}}))
```

The order is fixed and worth internalising: **patch/populate/remove first, then invalidate.** The seeds and surgical edits land, *then* the tag sweep marks the broader reads stale. That's why a `:populates`'d entry doesn't immediately refetch (it just learned the truth from the write reply) while a merely-tagged list does.

> **When the reply is partial: `:refetch-populated?`.** `:populates` treats its seed as an **authoritative load** — the populated entry becomes `:loaded`, its freshness timers arm as if it had fetched normally, and this mutation's own `:invalidates` pass deliberately *skips* it (it would be silly to refetch the key you just learned from the write reply). That's exactly right when the write reply carries the *full* resource shape, as the favorite endpoint does.
>
> But some endpoints reply with only *part* of the record — enough to acknowledge the write, not enough to be the whole cached value. For that case an `:invalidates` descriptor opts the populated key back into a same-mutation refetch:
>
> ```clojure
> :invalidates (fn [{:keys [slug]} _result]
>                [{:scope :rf.scope/global
>                  :tags  #{[:article slug]}
>                  :refetch-populated? true}])   ;; the partial reply isn't the whole truth — go re-read
> ```
>
> `:refetch-populated?` changes exactly one thing: whether a key *this* mutation populated may be immediately refetched by *this* mutation's invalidation. The default is no. Reach for it only when you know the reply is a subset of the full GET.

> **Gotcha — what happens if a cache target is wrong, after the write already landed.** A subtle question worth answering once: `:populates` / `:patches` / `:removes` run at *settle*, **after** the server write has committed. So what does the runtime do if a target is bad — say you typo a resource id, or a `{:from-db …}` scope resolves `nil`? It can't undo the server write, so it splits by severity rather than blowing up your committed mutation:
>
> - A **recoverable** target — an unregistered resource id, or a malformed target map — is **dropped and warned, not thrown**. The runtime applies the *valid* siblings in the same arm, records the dropped one on the instance's trace evidence, and emits a dev-only `:rf.warning/mutation-target-skipped`. One typo'd sibling never strands the whole write.
> - A **cache-identity-corruption** target — a misspelled reserved scope keyword (a bare `:rf.scope/*` outside the closed set), or a non-EDN scope/params — **still throws the arm**. Nothing is allowed to silently write the cache under a *wrong identity*.
> - A `{:from-db …}` target whose scope resolves `nil` is **fail-closed-dropped** — never written under a global fallback — and recorded as `:target-unresolved`.
>
> The takeaway: get your target maps right, but a stray typo degrades gracefully (warn-and-skip) rather than throwing away a server write you can't take back. The **optimistic** plan — which runs *before* the request — is stricter, because there's no committed write to be inconsistent with yet: a bad target there rejects the whole apply.

### The scope footgun — and the safe pattern

The single most common mutation bug is a **scope mismatch**: a write invalidates a tag in the *wrong* scope, so the invalidation matches no cache entry and the stale read is never refreshed. It is silent by construction — invalidating a tag that has no entry in *this* scope is a legitimate "nothing to do," so the runtime can't tell a true no-op from a miss by the match alone.

The footgun looks like this. A mutation with **no `:scope`** defaults its execution scope to `:rf.scope/global` (writes are fail-open — a write leaks nothing, so global is a safe default). A **bare tag set** on `:invalidates` then inherits that resolved scope. So this:

```clojure
;; ✗ WRONG — the feed lives in the SESSION scope, but this invalidates GLOBAL
(rf/reg-mutation :conduit/post-to-feed
  {:params-schema [:map …]
   :invalidates   (fn [_ _result] #{[:feed]})}   ;; resolves to :rf.scope/global
  (fn [_ _] {:request {:method :post :url (rh/full-url "/articles")}}))
```

quietly *misses* `:conduit/feed`, because that resource is `:scope {:from-db :conduit/session}` — its entries live under `[:rf.scope/session {:username …}]`, never under global. The feed stays stale.

The fix is the per-scope descriptor form you already used in the favorite: name the scope each tag actually lives in.

```clojure
;; ✓ RIGHT — name the session scope, via the same resolver the resource uses
:invalidates (fn [_ _result]
               [{:scope {:from-db :conduit/session} :tags #{[:feed]}}])
```

The same rule covers route- and tenant-scoped reads: **a write's invalidation scope must match the scope of the resources it breaks.** When a write touches both a global fact and a session fact, return one descriptor per scope (exactly as `:conduit/favorite` does) — never a blanket `:cross-scope? true`, which is the audited multi-tenant escape, not the default.

> **Why this matters — you don't have to spot it by eye.** In dev builds the framework emits a loud **`:rf.warning/mutation-scope-mismatch`** at the moment a mutation invalidates in a scope that holds no matching entry *while a different scope does* — the write-side complement of the read-side `:rf.warning/resource-sub-scope-mismatch`. It names the mutation, the scope it invalidated in, the scope that actually held the entry, and the tags, and its `:hint` points at the fix. It's dev-only ([elided](../glossary.md#elide) from production by `goog.DEBUG`), dedupe-keyed so a form firing on every keystroke warns once, and it fires only on a genuine *mismatch* — a tag with no entry anywhere (a true nothing-to-invalidate) and a deliberate `:cross-scope? true` sweep are both quiet. Watch for it in the [trace stream](../glossary.md#trace-stream) or in Xray.

> **What `:cross-scope? true` actually is — and why it's not your default.** We've called it "the audited escape" twice now, so let's be concrete. A per-scope descriptor can only name scopes you *already know* — global, the session you resolved, a tenant id you hold. But some operations need to stale a tag *wherever it lives*, across scopes the call site **cannot enumerate**: an admin force-publishing across every tenant, a cache-poisoning response, a data migration. That's the one job `:cross-scope?` does — "invalidate this tag in every scope currently holding it":
>
> ```clojure
> ;; only the call site that CANNOT name its scopes — and must :cause-justify the sweep
> :invalidates (fn [_ _result]
>                [{:tags #{[:article-list]} :cross-scope? true :cause [:admin/force-republish]}])
> ```
>
> It is genuinely different from a descriptor, and genuinely dangerous: it can stale or refetch data for other users, tenants, story frames, and SSR requests. So it's **audited** — it *must* carry `:cause` evidence (a cross-scope sweep with no cause is rejected), it's recorded as a privacy-relevant trace event, and Xray warns when a precise descriptor would have done the job. Three rungs, and the floor is fail-closed: a bare invalidate with **no scope at all** is a loud error (`:rf.error/resource-invalidate-scope-required`), never a silent global blast. Reach for descriptors; reach for `:cross-scope?` only when you truly cannot name the scopes by hand.

### Controlling *when* invalidation runs: `:invalidate-timing`

By default a mutation's `:invalidates` fires **on success** (`:after-success`) — the safe choice, and what every example here uses. The registration key `:invalidate-timing` lets you move it:

- `:after-success` *(default)* — stale the reads only once the write is confirmed.
- `:before-request` — stale *before* the request goes out (an "I know this is about to be wrong, refetch eagerly" pattern).
- `:after-failure` — stale on failure (useful when a failed write still perturbs server state you cache).
- `:after-settle` — stale on either outcome.

You rarely need anything but the default; reach for the others only when the write's effect on your cached reads doesn't line up with a clean success. One hard constraint: `:before-request` is **incompatible with optimistic mutations** (the next section) — staling an entry and then optimistically re-populating it is contradictory, so the combination is a loud registration error (`:rf.error/mutation-optimistic-before-request`). Optimistic writes use the default timing.

### Make it optimistic: the heart flips *before* the reply

`:populates` runs on success — the heart flips the moment the server confirms. For a tiny, reversible change like a favorite, you usually want the flip *immediately on click*, then a revert if the write fails. That's an [**optimistic mutation**](../glossary.md#optimistic-update--rollback), and it's a registration key, not a call-site dance.

Add `:optimistic-tags` — the tag-addressed twin of `:invalidates`. Where `:invalidates` says "these tags went *stale*," `:optimistic-tags` says "patch every entry carrying these tags, *now*, before the request":

```clojure
(rf/reg-mutation :conduit/favorite
  {:doc           "Favorite an article (optimistic). POST /articles/:slug/favorite."
   :params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; FORWARD: flip the heart + bump the count on every entry tagged
   ;; [:article slug] — the detail, every list, the session feed — at once.
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug]}
                        :patch (fn [data] (favorite-patch true slug data))}
                       {:scope {:from-db :conduit/session}
                        :tags  #{[:feed]}
                        :patch (fn [data] (favorite-patch true slug data))}])
   ;; COMMIT on :ok — the reply's authoritative Article overwrites the guess.
   :populates     (fn [{:keys [slug]} result]
                    {{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}
                     result})
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}
                     {:scope {:from-db :conduit/session} :tags #{[:feed]}}])
   :on-conflict   :invalidate}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (rh/full-url (str "/articles/" slug "/favorite"))}
     :decode  schema/ArticleResponse}))
```

Three things make this safe, and none of them are your job:

- **The runtime records the inverse.** You write only the *forward* `:patch` (`favorite-patch` toggles the flag and steps the count, handling both the detail's `{:article …}` and a list's `{:articles […]}` shape). The runtime snapshots each touched entry's prior value, so a rollback restores exactly what was there — never a reconstruction that can drift from the forward patch.
- **The reply settles deterministically.** An `:ok` reply **commits** (the `:populates` seed overwrites the optimistic value with the server's exact count, then `:invalidates` reconciles the lists). An `:error` reply **rolls back** — the heart flips back everywhere, verbatim. No `:on-failure` handler, no `app-db` undo flag.
- **A contested rollback refetches, it doesn't clobber.** Suppose your optimistic write fails, so it's time to roll back — but in the meantime *another* write already changed one of the entries you touched. Blindly restoring your snapshot would clobber that newer change with stale data. So `:on-conflict :invalidate` (the default) doesn't restore the snapshot for a contested entry: it marks the entry stale and lets the read path go fetch the authoritative value from the server. (This is a deliberate divergence from TanStack/SWR, which on rollback unconditionally restore the snapshot they captured. Here, on a contested rollback, the read path is the recovery authority.)

The view changes by *one* thing: drop `:disabled (:pending? fav)` — the user already sees their change, so don't block the button — and optionally read the derived **`:optimistic?`** flag (`(:optimistic? fav)`, true while the optimistic value is showing and unconfirmed) for a subtle in-flight cue.

> **Coming from TanStack / RTK / SWR?** This is their `onMutate` + `onError` rollback (TanStack), `updateQueryData` + undo patch (RTK), or `optimisticData` + `rollbackOnError` (SWR) — except the inverse is runtime-recorded, not hand-written, and the whole apply/settle is on the trace ([`:rf.mutation/optimistic-applied`](../../../spec/016-Resources.md#optimistic-mutations) → `optimistic-reconciled` / `optimistic-rolled-back`). The full optimistic surface lives in [Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations); `examples/reagent/realworld_resources/mutations.cljs` runs exactly this favorite.

## Publish from the editor — and continue with `:reply-to`

Watching an instance is the right tool for *rendering*: the button disables itself, and that's all it needs. But a successful save usually has to **drive workflow** too — navigate to the new article, clear the form. Those are causes, not renders. In Promise-land you'd `await` the POST and then navigate. Here the continuation is a declared part of the execute call: `:reply-to`.

First, the write. Create and edit share one mutation that switches POST/PUT on whether a slug exists yet:

```clojure
;; src/conduit/mutations.cljs
;; cf. examples/reagent/realworld_resources/article_editor.cljs
(rf/reg-mutation :conduit/save-article
  {:doc           "Create (POST /articles) or update (PUT /articles/:slug)."
   :params-schema [:map
                   [:slug  {:optional true} [:maybe :string]]
                   [:title :string]
                   [:description :string]
                   [:body  :string]
                   [:tagList [:vector :string]]]
   :scope         :rf.scope/global
   ;; Lists always go stale; an edit also stales its own detail entry. A new
   ;; article has no prior slug — its detail loads fresh on navigate.
   :invalidates   (fn [{:keys [slug]} _result]
                    [{:scope :rf.scope/global
                      :tags  (cond-> #{[:article-list]}
                               slug (conj [:article slug]))}
                     {:scope {:from-db :conduit/session}
                      :tags  #{[:feed]}}])}
  (fn [{:keys [slug] :as draft} _ctx]
    {:request {:method (if slug :put :post)
               :url    (rh/full-url (if slug (str "/articles/" slug) "/articles"))
               :body   {:article (select-keys draft [:title :description :body :tagList])}}
     :decode  schema/ArticleResponse}))
```

The editor's `app-db` slice is an ordinary form in Part 3's mold: a `:draft` the inputs edit, plus a `:baseline` (the article as loaded, or blank) so we can tell whether anything actually changed. Note what's *not* here: there's no `:status` field. The submission lifecycle Part 3 hand-rolled lives on the mutation instance instead — one of the things you get back by moving to mutations.

```clojure
;; src/conduit/editor.cljs
;; cf. examples/reagent/realworld_resources/article_editor.cljs
(ns conduit.editor
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(def blank-draft {:title "" :description "" :body "" :tagList ""})

(defn editor-slice
  ([] (editor-slice nil blank-draft))
  ([slug baseline]
   {:slug slug :draft baseline :baseline baseline
    :errors {} :submit-attempted? false}))

(defn draft-from-article [{:keys [title description body tagList]}]
  {:title title :description description :body body
   :tagList (str/join ", " tagList)})

(defn validate-draft [{:keys [title description body]}]
  (cond-> {}
    (str/blank? title)       (assoc :title "Title is required.")
    (str/blank? description) (assoc :description "Description is required.")
    (str/blank? body)        (assoc :body "Body is required.")))

(defn parse-tag-list [s]
  (->> (str/split (or s "") #",")
       (map str/trim) (remove str/blank?) vec))

;; The editor route's :on-match (registered below): fresh slice, prior
;; save instance cleared.
(rf/reg-event :editor/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :editor (editor-slice))
     :fx [[:dispatch [:rf.mutation/clear {:instance :editor/save}]]]}))
```

Submit validates, then fires the mutation. The continuation is named right at the call site:

```clojure
(rf/reg-event :editor/submit
  (fn [{:keys [db]} _]
    (let [{:keys [slug draft baseline]} (:editor db)
          errors (validate-draft draft)]
      (cond
        (seq errors)
        {:db (-> db
                 (assoc-in [:editor :submit-attempted?] true)
                 (assoc-in [:editor :errors] errors))}

        (= draft baseline) {}   ;; valid but unchanged — nothing to save

        :else
        {:fx [[:dispatch [:rf.mutation/execute
                          {:mutation :conduit/save-article
                           :params   (cond-> (-> (select-keys draft [:title :description :body])
                                                 (assoc :tagList (parse-tag-list (:tagList draft))))
                                       slug (assoc :slug slug))
                           :instance :editor/save
                           :reply-to [:editor/replied]
                           :cause    [:submit :editor/save]}]]]}))))
```

When the runtime accepts the write's reply, it dispatches `[:editor/replied reply]` — your event target, with one canonical **reply map** appended as the final argument:

```clojure
(rf/reg-event :editor/replied
  (fn [{:keys [db]} [_ {:keys [status value]}]]
    (if (not= :ok status)
      ;; Failure already shows on the form via the instance's :error state.
      {}
      ;; The save replies with the saved article: re-seed the editor so the
      ;; draft is CLEAN (the :can-leave guard below will let us go), clear
      ;; the instance, and navigate.
      (let [article (:article value)]
        {:db (assoc db :editor (editor-slice (:slug article) (draft-from-article article)))
         :fx [[:dispatch [:rf.mutation/clear {:instance :editor/save}]]
              [:dispatch [:rf.route/navigate :conduit.article/show {:slug (:slug article)}]]]}))))
```

That `{:keys [status value]}` is the reply map's public shape: `:status` tells you how the write settled, and `:value` carries the decoded result on `:ok`. It's the same closed envelope every managed-async surface in re-frame2 produces — [the uniform reply](../glossary.md#the-uniform-reply).

Three rules make `:reply-to` trustworthy:

- **You only ever see accepted, terminal replies.** The reply's `:status` is `:ok`, `:error`, or `:cancelled` — so you branch on it. A *stale* reply (the user re-submitted under the same instance, or something cleared it) is suppressed by the runtime and never reaches your handler. You simply cannot write the "slow first response overwrites the fast second one" bug here.
- **The continuation observes a settled world.** The phase order is fixed: populate and invalidate run first, the instance settles, *then* `:reply-to` dispatches. By the time `:editor/replied` runs, the lists are already marked stale and refetching.
- **Workflow goes in `:reply-to`; cache consequences go on the registration.** Navigate, toast, update a session — those are continuation. "Which reads did this break" — that's `:invalidates` / `:populates`, declared once. Don't invalidate tags from a continuation.

And here's the point this part exists to land: `[:editor/replied]` is **data**. It's not a closure awaiting a Promise. It's an event vector, sitting in the execute payload where Xray can show it (the mutation's `replied` trace op is that dispatch), where a test can assert it, and where replay can re-run it deterministically. The async workflow "save, then navigate" is on the record, step by step.

> **Coming from re-frame v1?** `:reply-to` is your `:on-success`/`:on-failure` pair collapsed into one stale-safe target with a uniform reply map — the same envelope every async family replies with ([From re-frame v1](../25-from-re-frame-v1.md)).

> **Going deeper — the continuation is a value, not a callback.** `await postArticle()` binds the next step to a stack frame: a closure that exists only while the promise is pending, invisible once it resolves. `:reply-to [:editor/replied]` binds it to a *value* — an event vector that any process can read, store, compare, or re-dispatch. That's the difference between a continuation captured in the language runtime (a `k` you can't see) and a continuation reified as data (a `k` you can print). The cost is a touch more ceremony; the prize is a workflow that's inspectable after the fact, assertable in a test, and replayable deterministically. [No await: continuations are data](../explanation/continuations-are-data.md) makes the full argument.

## Guard the half-written draft

One gap is left. Write half an article, click the site logo, and the draft silently vanishes — which is exactly the kind of thing users never forgive. Routes close this with a [`:can-leave` guard](../glossary.md#route-guard), a [subscription](../glossary.md#subscription) the router consults before navigating away:

```clojure
;; src/conduit/editor.cljs
(rf/reg-sub :editor/dirty?
  (fn [db _]
    (let [{:keys [draft baseline]} (:editor db)]
      (not= draft baseline))))

(rf/reg-sub :editor/can-leave?
  :<- [:editor/dirty?]
  (fn [dirty? _] (not dirty?)))

;; src/conduit/routing.cljs — a new route for the editor, with the guard.
(rf/reg-route :conduit.editor/new
  {:tags      #{:requires-auth}
   :on-match  [[:editor/initialise]]
   :can-leave [:editor/can-leave?]}
  "/editor")
```

(The example adds the `/editor/:slug` edit route the same way — same guard; its `:on-match` seeds the draft from the article read.)

The contract is strict, and the strictness is the point. `true` allows the navigation. `false` blocks it. Anything else blocks *and* emits a structured error (`:rf.error/can-leave-non-boolean`), so a buggy guard fails safe rather than waving you through by accident. The guard runs on **every** way out — a link click, a programmatic `:rf.route/navigate`, the browser Back button. There's no unguarded side door. The full pending-nav protocol lives in [Spec 012](../../../spec/012-Routing.md).

When the guard blocks, the runtime parks the blocked navigation in a **pending-navigation slot** and leaves the decision to your UI. The UI reads it from the `:rf/pending-navigation` sub:

```clojure
;; src/conduit/core.cljs — rendered once in the app shell.
;; cf. examples/reagent/realworld_resources/core.cljs
(reg-view pending-nav-dialog []
  (when-let [pending @(subscribe [:rf/pending-navigation])]
    [:div.pending-nav-overlay
     [:div.pending-nav-dialog
      [:p "You have unsaved changes. Leave anyway?"]
      [:button {:on-click #(dispatch [:rf.route/continue (:id pending)])} "Discard changes"]
      [:button {:on-click #(dispatch [:rf.route/cancel (:id pending)])} "Stay"]]]))
```

`:rf.route/continue` re-issues the original navigation, skipping the guard this one time. `:rf.route/cancel` clears the slot and stays put. The blocked navigation is, once again, *data*: a map you can subscribe to, assert on in a test, and see in Xray — not a `window.confirm` buried in router internals.

Now re-read `:editor/replied` above and notice the choreography. On a successful save it re-seeds the editor from the saved article *before* navigating, so `:editor/dirty?` is `false` and the guard waves the navigation through. Type into the editor, hit Back — dialog. Publish — clean navigation to your new article, lists already refreshing behind you. (The example's submit gate materialises "valid and dirty" as a [flow](../concepts/flows.md) shared by the button and the handler.)

> **For JavaScript developers — this is React Router's `useBlocker`, but the block is data.** A `:can-leave` guard plays the role of React Router's `useBlocker` / `unstable_usePrompt`: stop a navigation, ask the user. The difference is where the blocked navigation lives. There's no imperative `blocker.proceed()` / `blocker.reset()` you call from inside a component effect — the parked navigation is a value under `:rf/pending-navigation`, and `:rf.route/continue` / `:rf.route/cancel` are ordinary dispatched events. The dialog is just a view subscribed to a slot, which means it tests like any other view and shows up in Xray like any other state.

Everything in this part is running code: [`examples/reagent/realworld_resources/`](../../../examples/reagent/realworld_resources/) is the full app, including the pieces we trimmed for space (edit mode's load-and-seed, article delete, comments, follow/unfollow, the editor's field markup). For the concepts behind resources and mutations as a pair — the read model and its write counterpart — the [server-state concept page](../concepts/server-state.md) is the reference companion to this tutorial.

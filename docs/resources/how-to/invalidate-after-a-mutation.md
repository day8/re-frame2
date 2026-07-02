# Invalidate after a mutation

Your app just wrote to the server. It saved an article, posted a comment, toggled a favorite. The cached reads covering that data are now wrong, and every view still showing them is now showing the past. This guide wires that write to invalidate exactly those reads, so the views refetch automatically and nothing else moves.

Here's the idea underneath, in one sentence: **the write that made the cache stale is the thing that says so.** A timer only guesses, and polling pays for that guess on every interval. The [mutation](../glossary.md#mutation) actually knows — it just changed the data — so it names the reads it broke, once, at registration. That's what we mean by invalidation being *causal*: the cause of the staleness declares it directly.

We'll build that up one step at a time: tag the reads, declare what a write breaks, fire the write and watch it, then — only once the simple path is solid — reach for the optional arms (seed the cache, run side-effects, cross scopes).

> **Coming from TanStack Query?** Your anchor is `queryClient.invalidateQueries({ queryKey: ['articles'] })` inside a mutation's `onSuccess`. Same instinct here, with two deliberate differences. First, in re-frame2 you **declare the invalidation as data on the mutation registration** rather than calling it imperatively in a callback at every call site — so you write it once, not at every place the mutation fires. Second, it matches by tags within a scope, so it refetches only the entries something on screen still owns.

You need two things in place before any of this works. Boot the resources artefact (`day8/re-frame2-resources`) by putting `re-frame.resources` plus the `re-frame.http.managed` transport on your require list. Then register your reads with `reg-resource` — a [resource](../glossary.md#resource) is a managed server-state read, and registering it is how the framework knows how to fetch and cache it. If you haven't done that yet, start at [Server state: resources](../concepts.md).

## 1. Tag the reads

A [cache tag](../glossary.md#cache-tag) names a *fact*, not a resource. `[:article "welcome"]` and `[:article-list]` are facts — a specific article, and the list as a whole. When two resources carry the same tag, that tag becomes the join key a write uses to reach both of them at once.

```clojure
;; Adapted from examples/real-apps/realworld_resources/resources.cljs
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

Two reads, sharing tags on purpose. The detail read tags itself with its own identity *and* the list identity. The list read tags itself with the list identity *and* one `[:article slug]` per article it currently holds — so a write to a single article can reach any list showing that article. That overlap is the whole point: shared tags are how one write finds every read it touches.

(You'll see `:scope :rf.scope/global` on every registration here. A **scope** is the boundary within which tags are matched — `:rf.scope/global` means "the one cache everyone shares." It mostly stays out of your way; it earns a full treatment, and one sharp footgun, in [the scope section](#the-scope-footgun-and-how-to-disarm-it) at the end. Take it as "the global cache" until then.)

> **A tag is a vector, and `:tags` is a function of `(params data)`.** Each tag is a *structured name* — `[:article slug]`, `[:article-list]` — and the `:tags` fn receives the resource's params and its decoded data, so the list above can tag itself with one entry per article it actually holds. Returning a tag set that depends on `data` is the trick that lets a list be reached through any article it contains. The `:tags` key is optional; a resource with no tags simply can't be reached by tag invalidation (you can still `:rf.resource/refetch` it by exact key).

## 2. Declare what the write breaks

A [mutation](../glossary.md#mutation) is a managed server-state *write* — the write counterpart to a resource read. Its `:invalidates` key names the tags this write makes stale on success. That single key is the causal heart of the whole page.

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

That's it — one key, naming the same `[:article slug]` and `[:article-list]` tags the reads carry. On success, the engine finds every cached entry whose tags intersect and marks it stale.

The write lowers through the same [managed HTTP](../glossary.md#managed-http) transport your resources use, so the runtime owns the request's whole lifecycle — encode, send, decode, classify the failure, abort. One deliberate difference from a read: **a write does not retry by default.** Reads are safe to re-issue; re-sending a write can double a side-effect (charge the card twice, post the comment twice), so a mutation retries only when *its own* `:request` opts in by declaring `:retry`. Leave it off unless the endpoint is genuinely idempotent. (Cross-cutting request decoration — auth headers, base URLs, tracing — belongs in a `reg-http-interceptor` that decorates *every* managed request, not copied into each mutation's `:request`.)

What happens next depends on whether anything is still *using* that entry. A read is **owned** while something on screen depends on it — a mounted route showing the article, a running [machine](../../machines/glossary.md#machine) that asked for it. (That's the [owner](../glossary.md#owner--cause) — a lease that keeps the entry alive and decides whether an invalidation refetches *now* or merely marks the entry stale.) An **unowned** entry is one whose last reader has gone away, but whose value is still sitting in the cache. Owned entries refetch immediately, because something is waiting to display the fresh value; unowned ones just get marked stale and wait until the next time someone asks for them (their next *ensure* — the read path's "make sure this is loaded" call). So you don't get a refetch storm for data nothing is watching, which is exactly the behavior you want.

> **Note the callback signature.** `:invalidates` is a function of `(params result)` — the accepted params, and the decoded reply value. Notably, there is *no* `db` / `ctx` argument, and that's deliberate: a cache-consequence plan returns *data* (a tag set, or the descriptors we'll meet later). When a plan genuinely needs a value out of [app-db](../../core/glossary.md#app-db) — say, to pick a scope — it names a resolver to fetch it (the `{:from-db …}` form you'll see in [the scope section](#the-scope-footgun-and-how-to-disarm-it)) rather than reaching into the db directly inside the callback. That keeps the dependency visible and the plan inspectable in tooling, instead of buried in opaque callback code.

> **Gotcha: the lone-vector-tag footgun.** A tag is a vector, so a *tag set* is a set of vectors. If you return a single bare vector — `:invalidates (fn [_ _] [:article slug])` — the framework reads it as the **one tag** `#{[:article slug]}`, *not* as the scalar set `#{:article slug}` (which would name nothing and silently match nothing). The same normalization governs the `:tags` value of a direct `:rf.resource/invalidate-tags` event, so a lone vector tag has exactly one meaning everywhere. When in doubt, wrap it: `#{[:article slug]}`.

## 3. Fire the write, watch the instance

You have a write that knows what it breaks. Now fire it from a view and watch its progress.

One thing about the code below: the read uses the `resources/sub-mutation` sugar over the `[:rf.mutation/state {:instance …}]` [subscription](../../core/glossary.md#subscription), and the write fires with the bare [dispatch](../../core/glossary.md#dispatch), not the fully-qualified `rf/dispatch`. That's because `reg-view` injects `subscribe` / `dispatch` as [frame](../../core/glossary.md#frame)-bound locals — and the click callback fires *outside* render, where a bare `rf/dispatch` wouldn't know which frame it belongs to. The injected one carries that context for you, and the sugar resolves that same ambient frame on the read side.

```cljs-rf2
(rf/reg-view article-editor [article]
  (let [save @(resources/sub-mutation [:article-save (:slug article)])]
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

A quick tour of what that view is doing. The `resources/sub-mutation` read — sugar over the `[:rf.mutation/state {:instance …}]` subscription — is a passive read of one write's lifecycle: it never fires the write, it just watches it, and it yields a map: `{:status :pending? :success? :error? :settled? :result :error :optimistic?}`. That's what the button reads to flip its label to "Saving…" and disable itself. The `:instance` id — `[:article-save (:slug article)]` — is per-slug on purpose: it keeps two articles being saved at once from clobbering each other's pending/success/error state. (`editor-fields` and `save-error` are your own child views.)

Now notice what's *absent*: the view never dispatches an invalidate, never refetches a list, and never touches [app-db](../../core/glossary.md#app-db) — your app's single state map. It doesn't have to, because the registration in §2 already declared which reads this write breaks. That absence is the payoff of doing the work once, at registration.

### The `:rf.mutation/execute` payload, and the focused `:rf.mutation/*` subs

The execute event takes a map payload with these keys:

| Key | Required | Meaning |
|---|---|---|
| `:mutation` | yes | The registered mutation id. |
| `:params` | yes | The params for this attempt — validated and canonicalized against `:params-schema`. |
| `:instance` | yes | The instance id. Caller-supplied (or generated). Two concurrent submissions under *different* instance ids never clobber each other's `:pending` / `:success` / `:error`; re-executing under the *same* instance supersedes the earlier attempt and stale-suppresses its reply. |
| `:scope` | no | The execution scope the invalidation runs in (see [The scope footgun](#the-scope-footgun-and-how-to-disarm-it)). Optional — a mutation defaults to `:rf.scope/global`. |
| `:cause` | no | Trace/diagnostic data explaining why the write fired. Pure metadata; never changes behavior. |
| `:reply-to` | no | A call-site continuation event target (see [§6](#6-optional-do-more-than-refresh-the-cache)). |
| `:optimistic?` | no | `false` forces the pessimistic path for one call, skipping a registered optimistic plan. |

> **Gotcha — bad `:params` fail loud before the write fires.** `:params` are checked against the mutation's required `:params-schema` *before* the request is lowered, so a payload that doesn't conform never reaches the server: the execute raises `:rf.error/mutation-invalid-params` (the offending value is redacted in the error per the [data-classification](../../core/glossary.md#data-classification) policy). Host values — functions, promises, dates, DOM nodes — are rejected the same way, because params have to be serializable EDN to take part in identity and replay. This is the write-side twin of the read path's fail-closed validation: the schema, not a silent coercion, decides what a valid write looks like.

If you only ever need a slice of the instance state, the focused subs project just that slice — handy when a button cares about nothing but "am I in flight?":

```clojure
[:rf.mutation/state    {:instance [:article-save slug]}]   ;; the whole view-model (what resources/sub-mutation reads)
[:rf.mutation/status   {:instance [:article-save slug]}]   ;; :idle | :pending | :success | :error
[:rf.mutation/pending? {:instance [:article-save slug]}]   ;; boolean
[:rf.mutation/result   {:instance [:article-save slug]}]   ;; the decoded reply value, or nil
[:rf.mutation/error    {:instance [:article-save slug]}]   ;; the structured error envelope, or nil
```

### Resetting an instance after an error

A failed write settles `:error?` and parks the structured error under `:error` (there is no `:refresh-error` analogue here — a write has no last-known-good data to keep). The instance row *stays* settled until you clear it, which is what you want: the error stays on screen until the user does something about it. To retry, just dispatch `:rf.mutation/execute` again under the same instance — the re-execute supersedes the failed attempt. To dismiss the error without retrying, fire the causal reset:

```clojure
[:rf.mutation/clear {:instance [:article-save slug]}]
```

`:rf.mutation/clear` clears the runtime instance (and best-effort aborts any in-flight work for it). It is the *causal* reset — the form-level "start over" — and is distinct from `clear-mutation`, the registration-lifecycle function that *unregisters* the mutation entirely. You will reach for `:rf.mutation/clear` constantly; you will reach for `clear-mutation` almost never.

That's the complete simple path: tag the reads, declare what the write breaks, fire it and watch the instance. Everything below is optional — reach for it when a particular write needs more than "mark it stale and refetch."

## 4. Optional: the other cache consequences

`:invalidates` is the one you reach for most, because "mark it stale and let the read path refetch" is almost always right. But a mutation's success plan has four arms, and the other three let you write the cache *directly* from the reply when you already know the answer and want to skip the round-trip. All four are registration-level data plans, each a function of the same `(params result)` signature:

| Arm | Shape | What it does |
|---|---|---|
| `:invalidates` | `(fn [params result] -> tag-set-or-descriptors)` | Marks matching tags stale; owned reads refetch, unowned ones wait. The causal default. |
| `:populates` | `(fn [params result] -> {target value})` | Seeds an exact resource entry with the reply's value, *as if it had just loaded* (see [§5](#5-optional-seed-the-cache-from-the-reply)). |
| `:patches` | `(fn [params result] -> {target (fn [old result] new)})` | Transforms an **existing** exact entry in place. Patch targets an exact key only — never tags. |
| `:removes` | `(fn [params result] -> [target …])` | Drops exact entries — the cache half of a delete write (the key is dissociated, its in-flight attempt best-effort aborted). |

All four arms run at *settle* — the moment the write finishes and its outcome (success or failure) is final. The ordering among them at settle time is fixed and worth knowing: the direct cache writes — **patches, populates, removes — land first, then `:invalidates` runs last.** So an entry you patch or populate is already at its new value *before* the invalidation pass decides what to refetch — which is exactly why a populated key is exempt from the same mutation's refetch ([§5](#5-optional-seed-the-cache-from-the-reply)). A `:removes` arm is the one you reach for on a delete:

```clojure
(rf/reg-mutation :article/delete
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; Drop the detail entry outright — there's nothing left to refetch — then
   ;; invalidate the list tag so any mounted list re-reads without the deleted row.
   :removes     (fn [{:keys [slug]} _result]
                  [{:resource :article/by-slug
                    :params   {:slug slug}
                    :scope    :rf.scope/global}])
   :invalidates (fn [_params _result] #{[:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :delete :url (str "/api/articles/" slug)}
     :decode  :auto}))
```

The exact-target shape — `{:resource :params :scope}` — is the same map form `:populates`, `:patches`, and `:removes` all use; we'll meet it properly in [§5](#5-optional-seed-the-cache-from-the-reply).

### When the invalidation fires: `:invalidate-timing`

By default invalidation runs on success — the data is only stale once the server confirms the write. That's the right default, and most mutations never touch this. But the timing is an explicit registration option for the cases where it isn't:

```clojure
(rf/reg-mutation :article/save
  {:params-schema     [:map [:slug :string] [:title :string] [:body :string]]
   :scope             :rf.scope/global
   :invalidates       (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})
   :invalidate-timing :after-success}   ;; the default
  (fn [{:keys [slug] :as article} _ctx]
    {:request {:method :put :url (str "/api/articles/" slug) :body {:article article}}
     :decode  :json}))
```

The four members:

- **`:after-success`** *(default)* — invalidate when the write is accepted. Almost always what you want.
- **`:before-request`** — mark the reads stale the moment the write *starts*, before the reply lands. Use this when you'd rather a mounted view show a fresh-fetch spinner during the write than the now-doomed old value. (It is a loud registration error to combine `:before-request` with an optimistic plan — `:before-request` stales the entries the optimistic apply immediately re-populates, which is contradictory.)
- **`:after-failure`** — invalidate only when the write *fails*. Niche: you optimistically wrote the cache yourself elsewhere and want truth pulled back on rejection.
- **`:after-settle`** — invalidate on either outcome, success or failure.

## 5. Optional: seed the cache from the reply

Sometimes the write's reply carries the updated data back to you. `:populates` puts that data straight into the cache *before* the invalidation runs, so the change appears instantly with no refetch round-trip.

```clojure
;; Adapted from examples/real-apps/realworld_resources/mutations.cljs
(rf/reg-mutation :article/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; The map KEY is the exact resource target — {:resource :params :scope} —
   ;; and the VALUE MUST be the resource's stored shape: exactly what its own
   ;; request fn + :decode would have produced (the full envelope, not a
   ;; sub-projection), so the populated key reads identically to a fetched one.
   :populates   (fn [{:keys [slug]} result]
                  {{:resource :article/by-slug
                    :params   {:slug slug}
                    :scope    :rf.scope/global} result})
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

A populated key counts as an **authoritative load** — it becomes `:loaded`, the value becomes the current `:data`, and its freshness timers arm exactly as if it had fetched normally. Which means it's exempt from this same mutation's invalidation pass. So invalidating broad tags doesn't immediately re-fetch the entry you just seeded from the reply — the framework trusts the value you handed it. (It can still be invalidated by *later* events, *later* mutations, or focus/reconnect — the exemption is only for *this* mutation's own pass.)

> **The exact-target map shape.** `:populates`, `:patches`, and `:removes` all address an exact entry with the same map: `{:resource <id> :params <params> :scope <scope>}`. The `:scope` may be a concrete value, `:rf.scope/same` (the mutation's own resolved scope), `:rf.scope/global`, or a `{:from-db …}` resolver reference. This map form is the *only* accepted public input — don't hand-build the storage tuple `[scope resource-id params]`; that's the internal representation, not a second spelling.

> **Gotcha: the populated value must be the resource's *stored* shape.** Populate the whole decoded envelope the resource stores (e.g. `{:article {…}}`), not an unwrapped inner projection. A populated entry has to read *identically* to a fetched one, or you've created a cache-coherence bug — the next view to read that key sees a shape no fetch would ever produce.

> **Gotcha: a bad target at settle is split by class — a typo doesn't strand the write.** These exact-target arms run at *settle*, after the server write has already committed. So a *recoverable* bad target — an **unregistered** resource id, or a non-map target — is **dropped-and-warned, not thrown**: the valid siblings in the same arm still land, the dropped target is recorded on the instance, and you get a dev-only `:rf.warning/mutation-target-skipped` (elided from production). One typo'd sibling must not throw away the good cache writes after an irreversible remote write. A *corruption-class* target, though — a mis-spelled `:rf.scope/*` keyword or a non-EDN scope/params, anything that would write the cache under a **wrong identity** — still throws the whole arm; no relaxed policy may swallow that. (Note the asymmetry with the *optimistic* pre-write arms in [Advanced](#advanced): those run *before* the request is sent, so they reject *every* bad target, recoverable or not — there's no committed write to stay consistent with yet.)

> **Partial replies — `:refetch-populated? true`.** If the write's reply is only *partial* relative to a full resource GET, you don't want to keep the half-populated value. Opt a single invalidation descriptor into `:refetch-populated? true` and that key gets refetched after all:
>
> ```clojure
> :invalidates [{:scope :rf.scope/global
>                :tags  #{[:article slug]}
>                :refetch-populated? true}]
> ```
>
> This flips *exactly* one thing: whether a key this mutation populated may be immediately refetched by this same mutation's invalidation pass. The default is no.

> **Populate runs on success — reach for `:optimistic` to flip before the reply.** `:populates` seeds the cache from the *accepted reply*, so it runs only after the server confirms. If a write must flip the UI immediately and revert on rejection, declare an [optimistic plan](../glossary.md#optimistic-update--rollback) instead: `:optimistic` (exact target) or `:optimistic-tags` (tag-addressed) patches the cache *before* the request is sent, and the runtime commits, rolls back, or reconciles it deterministically on settle — `:on-conflict` (default `:invalidate`) governs a contested rollback. See [Advanced](#advanced) below, and the worked write in [Part 4 of the tutorial](../tutorial/04-mutations-and-invalidation.md).

## 6. Optional: do more than refresh the cache

Sometimes the write needs to cause something the cache plan can't express — show a toast, navigate to the new article, focus a field. That's *app behaviour*, not a cache consequence, so it doesn't belong in `:invalidates` / `:populates`. Pass a call-site `:reply-to` event target instead:

```clojure
[:rf.mutation/execute
 {:mutation :article/save
  :params   {:slug slug :draft draft}
  :instance [:editor/save slug]
  :reply-to [:editor/save-replied]}]
```

When the reply is *accepted as current*, the runtime dispatches `:reply-to` with **one reply map appended as the final argument** — so your handler reads `[:editor/save-replied reply]`. That reply map is the same [uniform reply](../../core/glossary.md#the-uniform-reply) shape every managed-async operation in re-frame2 hands back (resources, mutations, and the rest all speak it), plus the extra mutation facts a continuation needs: `:status` (`:ok` / `:error` / `:cancelled`), `:value` (the decoded value, on `:ok`), `:error` (on `:error`), `:mutation`, `:instance`, `:params`, `:scope`, `:affected-keys`, and `:cause [:mutation <id> <instance>]`.

```clojure
(rf/reg-event :editor/save-replied
  (fn [{:keys [db]} [_ {:keys [status value] :as _reply}]]
    (case status
      :ok    {:db db
              :fx [[:dispatch [:toast/show "Saved"]]
                   [:dispatch [:nav/to [:route/article {:slug (-> value :article :slug)}]]]]}
      :error {:fx [[:dispatch [:toast/error "Save failed — try again"]]]}
      {})))
```

Two rules make `:reply-to` safe to lean on:

- **It fires for any accepted terminal reply, and never for a stale or superseded one.** Re-execute under the same instance, and the earlier attempt's reply is suppressed — its continuation never runs. You never get a continuation for a write that lost the race.
- **It fires exactly once, *after* cache consequences and instance settlement.** By the time `:editor/save-replied` runs, the cache is already patched/populated/invalidated and the instance row already settled. So you can read the fresh cache (or `:value`) from inside the continuation and trust it.

> **Why a dispatched event and not a callback?** A callback returning effects would mint effects outside the event tape, the interceptor chain, and replay — so it couldn't be traced, replayed, or time-travelled. A `:reply-to` event is ordinary causal input, which is exactly what re-frame2 wants every consequence to be.

> **Why not `:reply-to` at registration?** It's a *call-site* opt, not a `reg-mutation` key, on purpose. An invariant cache consequence (every save invalidates the same tags) is declarative data and belongs on the registration. A workflow continuation (this save toasts and navigates; that one just toasts) is app behaviour and belongs where the write is *fired*. Hiding it in the remote-write definition would bury app behaviour in the wrong place.

## The scope footgun (and how to disarm it)

One last thing to know before you ship: a [scope](../glossary.md#scope) is the boundary within which tags are matched. A bare tag set — the `#{[:article slug] …}` form we've used throughout — matches **only** in the mutation's own resolved scope. That's usually what you want, and zero matches is a legitimate outcome. But it means a *global* mutation that intends to refresh a *session-scoped* read — the user's personalized feed, say — will **silently miss**: no error, no refetch, just stale data on screen.

> **How a mutation resolves its scope.** A mutation's execution scope resolves in precedence order: the execute-payload `:scope` → the mutation-spec `:scope` → `:rf.scope/global`. Unlike a resource — whose scope policy is mandatory and [fail-closed](../../core/glossary.md#fail-loud-not-silent) — a mutation's `:scope` is *optional* and **fail-open on absence**: with nothing supplied it defaults to global. A causal write has no cached-read leak boundary of its own, so defaulting to global leaks nothing. (A scope you *do* supply is still canonicalized and validated — a misspelled `:rf.scope/*` keyword is rejected loudly. Fail-open governs only the *absent* case, never a *wrong* value.) The catch is that the mutation then invalidates *in that global scope* — and your session-scoped read lives in a different one.

When one write breaks reads in more than one scope, don't reach for a bare tag set. Return a vector of **descriptors** instead, one per scope, each naming its own target:

```clojure
:invalidates (fn [{:keys [slug]} _result]
               [{:scope :rf.scope/global
                 :tags  #{[:article slug] [:article-list]}}
                {:scope {:from-db :app/session}     ;; a named scope resolver
                 :tags  #{[:feed]}}])
```

Now the global descriptor refreshes the article and the list, the session descriptor refreshes the feed via its `{:from-db …}` resolver, and nothing falls through the gap. This is the *causal heart* applied across scopes: one mutation, two scopes, no app-level cross-scope patch and no home-page watcher wiring the two together by hand.

A descriptor's `:scope` is one of: `:rf.scope/same` (the mutation's resolved scope — the default when `:scope` is omitted, and the meaning of the bare tag-set shorthand), `:rf.scope/global`, a concrete scope value, or a `{:from-db <resolver-id>}` reference resolved against the db at settle time. (A `{:from-db …}` that resolves to nil is *fail-closed* — that descriptor is dropped, never silently widened to global.)

> **Going deeper: why this isn't fail-open by default.** A bare cross-scope `:rf.resource/invalidate-tags` with no scope is a *loud error* (`:rf.error/resource-invalidate-scope-required`), not a silent global blast — re-frame2's fail-closed floor. A mutation's bare tag set resolves to *its* scope rather than erroring, which keeps the common single-scope case ergonomic, but the dev-mode tripwire below catches the mismatch so the convenience doesn't cost you a silent miss. The whole lattice is fail-closed at the bottom: silence is only ever the safe direction.

### When you can't name the scopes: `:cross-scope? true`

A descriptor can only name scopes you already know. Occasionally you need the opposite — "invalidate this tag *wherever it lives*," across scopes you can't enumerate at the call site but the cache can: admin tooling clearing one fact for every tenant, a cache-poisoning response, a data migration. That's `:cross-scope? true`, the audited escape hatch:

```clojure
:invalidates (fn [{:keys [article-id]} _result]
               [{:tags         #{[:article article-id]}
                 :cross-scope? true
                 :cause        [:admin/article-purged article-id]}])
```

Because it can stale or refetch data across *every* user, tenant, story frame, and SSR request, it's load-bearing to spell out and is treated as a privacy-relevant operation:

- it **must** carry `:cause` evidence — a cross-scope invalidation with no `:cause` is a loud `:rf.error/resource-cross-scope-cause-required`, never a silent unaudited sweep (the mutation engine stamps `:cause` for you when you supply one on the descriptor);
- it shows up as a privacy-relevant [trace event](../../core/glossary.md#trace-event), recording that a mutation reached outside its own scope;
- [Xray](../../core/glossary.md#xray) warns you when a precise descriptor would have done the job, so you don't reach for the sledgehammer by reflex.

Reach for `:cross-scope?` only when the scopes are genuinely unenumerable at the call site. If you can name them, name them with descriptors.

## Advanced

Everything above settles the cache *after* the server confirms. The power-user move is to flip the UI *before* it confirms — an [optimistic update](../glossary.md#optimistic-update--rollback) — and let the framework reconcile when the reply lands. This is its own small contract; reach for it when a write must feel instant (a favorite toggle, a like count, an item that should vanish on click) and you accept the cost of a possible rollback. The worked example lives in [Part 4 of the tutorial](../tutorial/04-mutations-and-invalidation.md); the full settle contract is [Concepts → Optimistic writes](../concepts.md#optimistic-writes-commit-roll-back-or-reconcile).

An optimistic plan is a registration-level arm, in two forms that mirror the success-time `:patches` and tag-addressed `:invalidates`:

```clojure
(rf/reg-mutation :article/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   ;; exact-target form — patch one known key. (fn [params] -> {target patch-fn}),
   ;; patch-fn is (fn [old-data] -> new-data). Note: NO result arg — the reply
   ;; doesn't exist yet. A nil patch-fn is an optimistic remove; a patch over an
   ;; absent key is an optimistic seed.
   :optimistic  (fn [{:keys [slug]}]
                  {{:resource :article/by-slug :params {:slug slug} :scope :rf.scope/global}
                   (fn [old] (update-in old [:article :favorited] not))})
   ;; tag-addressed form — flip EVERY cached entry carrying these tags at once
   ;; (the detail, every list, the feed), so the toggle is consistent across views.
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug]}
                        :patch (fn [old] (update-in old [:article :favorited] not))}])
   :populates   (fn [{:keys [slug]} result]
                  {{:resource :article/by-slug :params {:slug slug} :scope :rf.scope/global} result})
   :invalidates (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

What the runtime guarantees, so you don't hand-roll any of it:

- **The inverse is captured for you — you never write a rollback.** Before each forward patch the runtime snapshots the *whole entry* as it stood (or records that the key was absent), so a rollback restores exactly what existed, never a reconstructed approximation. An author-written inverse — which drifts the moment the forward patch changes — is never required.
- **Settle is deterministic: commit, roll back, or reconcile.** On an accepted `:ok` reply the authoritative `:populates` / `:patches` / `:invalidates` overwrite the optimistic value and the snapshot is discarded (`:rf.mutation/optimistic-reconciled`). On an `:error` or `:cancelled` reply it rolls back (`:rf.mutation/optimistic-rolled-back`). A stale/superseded reply rolls back nothing — its snapshot is simply dropped.
- **`:on-conflict` governs a *contested* rollback.** If a concurrent write landed on the entry between your optimistic apply and the rollback, restoring your snapshot would clobber newer truth. So the default `:on-conflict :invalidate` does *not* blind-restore — it marks the entry stale in its own scope and lets the read path refetch the authoritative value. `:force` restores your snapshot anyway (single-writer last-write-wins) and trips a `:rf.warning/optimistic-force-clobber`. This deference to the read path is a deliberate divergence from TanStack/SWR's unconditional context restore. An out-of-enum value is a loud `reg-mutation` error.
- **The view can tell it's showing an optimistic value.** `[:rf.mutation/state {:instance …}]` carries a derived `:optimistic?` — true between the apply and settle — so you can render "pending, but already showing your change."

Two guardrails worth internalizing:

> **Gotcha — optimistic plans are fail-closed and scope-bounded.** Every optimistic target's scope is fail-closed: a `{:from-db …}` that resolves nil *drops* that target rather than writing under an implicit global (unlike a mutation's fail-open *execution* scope — an optimistic apply writes the cache, so it carries the same leak boundary a read does). There is **no `:cross-scope?` optimistic form**: the optimistic surface is exact-key or tag-within-named-scope only, so it can't leak a write across users or tenants the way an audited `:cross-scope?` invalidation can. A malformed `:optimistic-tags` descriptor (non-map, missing `:patch`, non-collection `:tags`) is warn-and-skipped (`:rf.warning/optimistic-tags-descriptor-skipped`), not thrown — it runs *before* the request lowers, so throwing would kill the whole write; the well-formed descriptors in the same plan still apply.

> **Gotcha — don't combine `:optimistic*` with `:invalidate-timing :before-request`.** A `:before-request` invalidation stales the very entries an optimistic apply immediately re-populates — contradictory (stale-then-optimistic-fresh). It's a loud registration error (`:rf.error/mutation-optimistic-before-request`), not a silent precedence rule; optimistic writes use the default `:after-success` timing. To skip a registered optimistic plan for one call, pass `{:optimistic? false}` on the execute payload (a boolean disable, never a per-call forward plan — call-site cache logic stays off the call site).

## Observe it in Xray

Save an article with the list and detail pages mounted, then open [Xray](../../core/glossary.md#xray)'s Resources tab:

- **Live instances** — both entries flip `:loaded → :fetching` (prior data stays visible) `→ :loaded`, with a new generation.
- **Invalidation / mutation graph** — one row per invalidation: the resolved scope, the tags, the matched keys, the match count, and the refetch count. A **zero match count** here is the scope-miss footgun made visible — and if the tags *do* match an entry in another scope, the `:any-tag-match-other-scope?` flag tells you "no match *here*" rather than "nobody provides this tag anywhere," which is exactly the signal you need to reach for a descriptor.
- **Lifecycle timeline** — the ordered `:rf.resource/*` rows, each carrying its [cause](../glossary.md#owner--cause): the why-chain from your mutation to each refetch.

> **The dev-mode tripwire.** Because the cross-scope miss is silent *by construction*, the framework also surfaces it as a dev-only warning at the moment of the mismatched invalidation: `:rf.warning/mutation-scope-mismatch`. Its heuristic — a descriptor matched zero entries in its resolved scope while the same tags *do* match an entry in a *different* scope — carries the mutation, the instance, the scope it invalidated in, the scope that actually held the data, and the tags, with a `:hint` naming the fix. It's gated behind the debug flag and [elided](../../core/glossary.md#elide) from production builds entirely, so it costs you nothing shipped. A deliberate `:cross-scope? true` descriptor is never flagged, and a tag that matches nothing anywhere (a true nothing-to-invalidate) doesn't warn either — only the genuine "you resolved the *wrong* scope" case trips it.

The full read→write→invalidate→refetch loop runs live in [the RealWorld resources example](../../../examples/real-apps/realworld_resources).

> **From re-frame v1.** None of this existed in v1. There was no managed server-state layer at all — you fetched in an [effect handler](../../core/glossary.md#effect-handler), hand-wrote the reply into [app-db](../../core/glossary.md#app-db) yourself, and there was no cache, so nothing ever went "stale." Invalidation was a phrase you didn't need because you owned every byte of the read path. The cost was that every screen reinvented loading flags, every write hand-coded which views to refresh, and a stale read was a bug you wrote, not a state the framework tracked. re-frame2's resources move that whole concern under the framework: the write *declares* what it breaks (`:invalidates`), and the read path refetches. The v1 instinct to reach for an effect that pokes `app-db` after a save is the exact thing this page replaces.

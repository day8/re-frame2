# Async resources

Async replies arrive late, out of order, and for screens that no longer
exist. The transport is the core resource model (`re-frame.resources`):
registered reads, causes, status maps you can subscribe to, and mutations
that invalidate by tag. That corpus teaches the model. This page owns the
Hicasso patterns on top — starting with the race that overwrites draft
edits — then per-instance mutation status, cancellation, and demand-driven
committed reads.

In a Hicasso view, read a resource with the same `h/sub` as any subscription.

## A late reply must not overwrite newer edits

The user saves a profile. The server normalizes fields and replies 800 ms
later. During those 800 ms the user kept typing. If you write the reply over
the draft, you delete the newest edits. If you drop the reply, you lose the
normalization. The fix is a **correlation recipe**: capture a basis when
work starts, compare when the result lands, and let newer facts win their
slots. For drafts, that recipe is a **settle-merge**.

Two protections do different jobs:

- **Supersession** is the runtime's job. When a reply loses the race with a
  *newer attempt* under the same instance, the runtime suppresses that reply.
  It never reaches you.
- **Settle-merge** is your job. A reply that reaches you is current, but the
  *draft* may have changed after you sent it. Merge the reply into the draft;
  do not overwrite the draft.

Registration is ordinary. The submit handler starts the write with the draft
as params and a continuation:

```clojure
(ns app.profile
  (:require [re-frame.core :as rf]
            [re-frame.resources]          ;; resource/mutation model
            [re-frame.http.managed]))     ;; HTTP transport

(rf/reg-mutation :profile/save
  {:params-schema [:map [:username :string] [:bio :string] [:email :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [username]} _result] #{[:profile username]})}
  (fn [profile _ctx]
    {:request {:method :put :url "/api/profile" :body {:profile profile}}
     :decode  :json}))

;; from your submit handler; draft = the current [:profile :draft]
[:rf.mutation/execute {:mutation :profile/save
                       :params   draft
                       :instance [:profile-save]
                       :reply-to [:profile/save-settled]}]
```

The recipe lives in the continuation. The uniform reply carries the decoded
`:value` *and* the accepted `:params` — the basis you sent, so you do not
carry it by hand:

```clojure
;; Field by field: settled value lands only where the draft still matches
;; what was sent; a newer edit keeps its field.
(rf/reg-event :profile/save-settled
  (fn [{:keys [db]} [_ {:keys [status value params error]}]]
    (if (= :ok status)
      {:db (-> db
               (assoc-in [:profile :saved] (:profile value))
               (update-in [:profile :draft]
                          (fn [draft]
                            (reduce-kv (fn [d field sent]
                                         (if (= (get d field) sent)
                                           (assoc d field (get-in value [:profile field]))
                                           d))
                                       draft
                                       params))))}
      {:db (assoc-in db [:profile :save-error] error)})))
```

A field that did not change after the send takes the settled value; a field
that changed keeps the newer edit. `:reply-to` fires only for a reply the
runtime accepts as current — never for a stale or superseded reply, and only
after cache consequences and instance settlement. The comparison left for
you is current draft against sent params.

The same shape answers other "late result versus newer truth" cases. In a
debounce, a generation number is the basis. In optimistic rollback, the
runtime captures the basis for you. Validation display for drafts — touched
fields and submit gating — is the forms module's job ([Forms](05-forms.md)).

## Per-instance mutation status

A feed has many favorite buttons. "Is my save in flight?" is a per-button
fact. Status is keyed by an instance id you choose. Key the instance per
row, and each button watches its own write:

```clojure
(h/defview favorite-button [{:keys [slug favorited?]}]
  (let [save (h/sub [:rf/mutation {:instance [:favorite slug]}])]
    [:button {:class    (when favorited? "active")
              :disabled (:pending? save)
              :on-click [:rf.mutation/execute
                         {:mutation :article/favorite
                          :params   {:slug slug}
                          :instance [:favorite slug]
                          :cause    [:click :article/favorite]}]}
     (if favorited? "Favorited" "Favorite")]))
```

The click is one literal event vector — mutation, params, instance, and
cause, all data. A structural test can assert the whole write with `=`. The
instance sub yields `:pending?`, `:success?`, `:error?`, `:settled?`,
`:optimistic?`, `:result`, and `:error`. Focused projections such as
`[:rf.mutation/pending? {…}]` exist when a button needs one boolean.

Two instance rules matter. **Different instances never overwrite each
other** — two rows save concurrently with independent status. **The same
instance supersedes** — re-execute while pending and the runtime suppresses
the earlier attempt's reply. A settled `:error` stays until you retry with
the same execute, or dismiss it with
`[:rf.mutation/clear {:instance …}]` (which also best-effort aborts in-flight
work).

### Optimistic flip, automatic rollback

A favorite toggle should not wait hundreds of milliseconds. Declare the
optimistic plan on the registration. The runtime flips the cache before the
request, then commits, rolls back, or reconciles at settle time:

```clojure
(rf/reg-mutation :article/favorite
  {:params-schema   [:map [:slug :string]]
   :scope           :rf.scope/global
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug]}
                        :patch (fn [old] (update-in old [:article :favorited] not))}])
   :invalidates     (fn [{:keys [slug]} _result] #{[:article slug] [:article-list]})}
  (fn [{:keys [slug]} _ctx]
    {:request {:method :post :url (str "/api/articles/" slug "/favorite")}
     :decode  :json}))
```

You never write the rollback. The runtime snapshots each entry before the
forward patch. On `:error` or cancellation it restores that snapshot — so an
inverse patch cannot drift. On success, the authoritative reply overwrites
the guess. If a concurrent write lands between flip and settle, the default
`:on-conflict :invalidate` does not restore the snapshot over newer truth: it
marks the entry stale and lets a refetch settle it. While the flip is
unconfirmed, the instance carries `:optimistic? true`.

## Cancellation and supersession

Nothing cancels implicitly, and you do not hand-code the race:

| Concern | Owner | What happens |
|---|---|---|
| Reply beaten by a newer attempt, same instance | Runtime | Suppressed; its `:reply-to` never fires |
| Reply beaten by a newer fetch, same resource identity | Runtime | New generation supersedes; stale reply never touches the cache |
| Dismiss a settled error, abandon a write | You | `[:rf.mutation/clear {:instance …}]` — clears status, best-effort abort |
| Stop a read you no longer want | You | Withdraw the read — demand releases (below); in-flight work aborts best-effort |
| What "newer" means | You | Identity is the policy: instance and params decide which attempts race |

When you choose `[:favorite slug]` over `[:favorite]` as the instance, you
choose what supersedes what. The runtime enforces the race you declared.

## Demand-driven committed reads

The patterns above leave one correlation hand-written: the lifetime of a
resource versus the view that reads it. Route `:resources` owns page data,
and that is correct for page identity. Some resources are keyed by view-local
state — typeahead suggestions, picker options, a hover preview. Those belong
to no route. The hand-written answer is ceremony: ensure on mount, release
on unmount, re-ensure on parameter change — each step a chance for a leak.

Hicasso closes that with the committed read. A resource read may declare
demand:

```clojure
(h/sub [:rf/resource {:resource :app/suggestions
                      :params   {:q q}
                      :demand   true}])
```

Rules:

- **Commit acquires.** When a render that took this read commits, the read
  owns demand for that resource and params. The ensure runs after commit. A
  render alone acquires nothing. An abandoned render, a StrictMode
  double-invoke, and a retry each acquire zero times.
- **Liveness releases.** Demand releases on unmount, on parameter change
  (old identity releases as the new acquires), and when a committed render
  stops taking the read (for example a conditional becomes false). Reads in
  branches are legal, so that last case is ordinary.
- **Demand is not retention.** Release withdraws the ownership hold. The
  cached entry then lives out its ordinary lifetime (`:gc-after-ms`). Moving
  between two queries can rejoin warm entries instead of refetching.
- **Nothing else is inferred.** Debounce, supersession, refresh-with-data,
  and cancellation stay where this page already put them. Demand decides
  *that* the resource is wanted while the read is live, not *how* it behaves.
- **It costs nothing elsewhere.** Demand uses committed-read membership the
  runtime already keeps. A view that reads no resource carries none of this.

Without `:demand true`, the sub is the core passive projection and never
causes work. A missing cause still means an honest, permanent `:idle`.

### Typeahead end to end

This example exercises the policies together.

```clojure
(rf/reg-resource :app/suggestions
  {:params-schema  [:map [:q :string]]
   :scope          :rf.scope/global
   :stale-after-ms 30000
   :gc-after-ms    60000}
  (fn [{:keys [q]} _ctx]
    {:request {:method :get :url "/api/suggest" :params {:q q}}
     :decode  :json}))
```

Debounce is explicit policy at the event layer. The input echoes every
keystroke (controlled write stays synchronous). Debounce the *consumers* of
a value, never the write ([Controlled inputs](04-controlled-inputs.md)). A
generation guards the committed query so a stale timer commits nothing:

```clojure
(rf/reg-sub :search/text        (fn [db _] (get-in db [:search :text] "")))
(rf/reg-sub :search/committed-q (fn [db _] (get-in db [:search :committed-q] "")))

(rf/reg-event :search/input
  (fn [{:keys [db]} [_ text]]
    (let [gen (inc (get-in db [:search :gen] 0))]
      {:db (-> db
               (assoc-in [:search :text] text)
               (assoc-in [:search :gen] gen))
       :fx [[:dispatch-later {:ms 250 :event [:search/settle gen]}]]})))

(rf/reg-event :search/settle
  (fn [{:keys [db]} [_ gen]]
    (if (= gen (get-in db [:search :gen]))
      {:db (assoc-in db [:search :committed-q] (get-in db [:search :text]))}
      {})))

(rf/reg-event :search/clear
  (fn [{:keys [db]} _]
    {:db (update db :search assoc
                 :text "" :committed-q ""
                 :gen  (inc (get-in db [:search :gen] 0)))}))
```

The list exists only while a committed query exists, and its committed read
declares demand:

```clojure
(h/defview suggestion-list [{:keys [q]}]
  (let [{:keys [data loading? fetching?]}
        (h/sub [:rf/resource {:resource       :app/suggestions
                              :params         {:q q}
                              :demand         true
                              :keep-previous? true}])]
    [:ul.suggestions {:aria-busy (boolean (or loading? fetching?))}
     (if (and loading? (not data))
       [:li.hint "Searching…"]
       (for [s (:suggestions data)]
         [:li {:key (:id s)} (:label s)]))]))

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

Timeline:

1. **`c` … `cl`** — each keystroke echoes through the controlled input. The
   generation increments. Nothing fetches. Debounce is event-layer policy.
2. **250 ms of quiet** — the surviving timer's generation matches;
   `:committed-q` becomes `"cl"`. `suggestion-list` mounts and the render
   commits. The committed read acquires demand for `{:q "cl"}` and the fetch
   begins. If React had discarded that render, nothing would have been
   acquired.
3. **`clo` before the reply lands** — parameters change. `{:q "cl"}`
   releases; `{:q "clo"}` acquires. The in-flight request for `{:q "cl"}`
   aborts best-effort. If its reply arrives anyway, the runtime's stale-reply
   check suppresses it. With `:keep-previous? true`, the `"cl"` list stays on
   screen with `:fetching?` true (refresh-with-data, not a flash of empty).
4. **Escape or navigate away mid-fetch** — `:search/clear` empties the
   committed query. The list unmounts; demand releases. Cancellation was a
   state change you wrote, not a lifecycle hook.
5. **Typing `cl` again soon** — release made the entry demand-free; it did
   not destroy it. Within `:gc-after-ms`, the read rejoins the warm entry. If
   still fresh within `:stale-after-ms`, no request occurs.

There is no ensure-on-mount effect, release-on-unmount cleanup, or
params-change watcher. The correlation between read liveness and resource
demand has one owner — the read.

Under Xray, held demand is a bounded projection: which resources are owned
by which committed reads, and why each fetch fired
([Diagnostics](15-diagnostics.md)). On the server, the view is Client-only by
default — a deterministic fallback and zero acquisition, because acquisition
is a client commit fact. Hydration then adopts without a duplicate fetch
([SSR and hydration](17-ssr-and-hydration.md)).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Permanent `:idle` skeleton | Nothing declares demand or any other cause | `:demand true` on the committed read — or a route `:resources` entry / explicit ensure where the route owns the data |
| `:rf.error/resources-artefact-missing` at boot | Resource model not loaded | `(:require [re-frame.resources])`, usually with `re-frame.http.managed` |
| Suggestions flash empty on every keystroke | Each params value is a new cache identity | `:keep-previous? true` on the read; render `:fetching?` as the quiet indicator |
| Results for an old query overwrite new ones | Hand-rolled fetch outside the resource model | Let the runtime own replies — identity plus generation suppress stale ones |
| Read throws after the render, naming the query | Read escaped the direct synchronous body — callback, promise, timer, lazy seq | Read during the body and close over the value ([Views and reads](02-views-and-reads.md)) |
| Every row's button goes pending together | One shared instance id | Key the instance per row: `[:favorite slug]` |
| Keystrokes vanish when a save reply lands | Reply written wholesale over the draft | Settle-merge: take settled values only where the draft still equals the sent basis |
| Optimistic value reverts over a newer concurrent write | `:on-conflict :force` restores the snapshot blindly | Keep the default `:invalidate` — stale-mark and refetch |

## When not to use demand

Demand-driven reads own **resource lifetimes that match a read's lifetime**.
They are not a general fetch library:

| Job | Owner |
|---|---|
| Page-identity data — the article this URL names | Route `:resources`: the route owns it, blocks the transition honestly, and runs under SSR |
| Warming data no read wants yet | Routing's `:prefetch :intent`, or an explicit ownerless ensure ([Routing and navigation](07-routing-and-navigation.md)) |
| "Refetch on click" | `[:rf.resource/refetch …]` — an event, because that is a cause, not a lifetime |
| A one-off uncached call | Managed HTTP: request out, uniform reply in |
| Draft state, validation gating, submit flow | The forms module ([Forms](05-forms.md)) |
| Multi-step async workflow with named stages | A machine owning the ensures, with resource status driving transitions |

If no view reads the resource, demand cannot express it. A demand with no
reader is exactly the hand-written correlation this feature removes.

??? info "Coming from TanStack Query?"
    `useQuery` is close: read it and it fetches; unmount and it forgets.
    Three differences matter. Acquisition happens at commit, so speculative
    renders fetch nothing. Policies are not call-site options: debounce lives
    in your events, staleness and GC on the registration, supersession in the
    runtime's identity rules. The cache is causal: writes invalidate by
    declared tags, not by a remembered `invalidateQueries`.

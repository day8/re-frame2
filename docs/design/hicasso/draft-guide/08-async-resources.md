# Async resources

Async replies arrive late, out of order, and for screens that no longer exist.
The transport is the core resource model (`re-frame.resources`); that corpus
teaches registered reads, causes, status maps you can subscribe to, and
mutations that invalidate by tag. This page owns the [Hicasso](glossary.md#hicasso)
patterns on top — starting with the race that eats draft edits. Later sections
cover per-instance mutation status, cancellation, and
[demand-driven committed reads](glossary.md#demand-driven-committed-read).

In a Hicasso view, you read a resource with the same [`h/sub`](glossary.md#hsub)
as any subscription.

> **The read owns the lifetime, you own the policy, and the runtime owns the race.**

## A late reply must not overwrite newer edits

This is the race. The user saves a profile. The server normalizes the profile —
it trims values, canonicalizes them, and fills fields — and replies 800 ms
later. During those 800 ms, the user continued to type. If you write the reply
over the draft, you delete the newest edits. If you drop the reply, you lose
the normalization. The fix is the **correlation recipe**: capture a basis when
work starts, compare when the result lands, and let newer facts win their
slots. Applied to drafts, this recipe is the **settle-merge**.

Two protections apply here, and they do different jobs:

- **Supersession** is the runtime's job. When a reply loses the race with a
  *newer attempt* under the same instance, the runtime suppresses that reply.
  The reply never reaches you.
- **Settle-merge** is your job. A reply that reaches you is current, but the
  *draft* can have changed after you sent it. Merge the reply into the draft.
  Do not overwrite the draft.

The registration is ordinary. The submit handler starts the write with the
draft as params and with a continuation:

```clojure
(ns app.profile
  (:require [re-frame.core :as rf]
            [re-frame.resources]          ;; the resource/mutation model
            [re-frame.http.managed]))     ;; the HTTP transport it lowers to

(rf/reg-mutation :profile/save
  {:params-schema [:map [:username :string] [:bio :string] [:email :string]]
   :scope         :rf.scope/global
   :invalidates   (fn [{:keys [username]} _result] #{[:profile username]})}
  (fn [profile _ctx]
    {:request {:method :put :url "/api/profile" :body {:profile profile}}
     :decode  :json}))

;; dispatched from your submit handler; draft = the current [:profile :draft]
[:rf.mutation/execute {:mutation :profile/save
                       :params   draft
                       :instance [:profile-save]
                       :reply-to [:profile/save-settled]}]
```

The recipe lives in the continuation. The uniform reply carries the decoded
`:value` *and* the accepted `:params`. The `:params` are the basis you sent —
you do not carry the basis by hand:

```clojure
;; Field by field: the settled value lands only where the draft still matches
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

The rule in one line: **a field that did not change after the send takes the
settled value; a field that changed keeps the newer edit.** `:reply-to` fires
only for a reply that the runtime accepts as current. It never fires for a
stale or superseded reply. It fires only after cache consequences and after
instance settlement. Therefore one comparison remains: the current draft
against the sent params.

The same shape answers each "late result versus newer truth" question below.
In the debounce, a generation number is the basis. In optimistic rollback, the
runtime captures the basis for you. Validation display for drafts — touched
fields and submit gating — is the forms module's job ([Forms](05-forms.md)).

## Per-instance mutation status

A feed has forty favorite buttons. "Is my save in flight?" is a per-button
fact. Status is keyed by an instance id that you choose. Key the instance per
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

Look at the click. It is one literal event vector — mutation, params, instance,
and cause, all data. A structural test therefore asserts the whole write [intent](glossary.md#intent)
with `=`. The instance sub yields `:pending?`, `:success?`, `:error?`,
`:settled?`, `:optimistic?`, `:result`, and `:error`. Focused projections such
as `[:rf.mutation/pending? {…}]` exist for a button that needs one boolean.

Two instance rules carry this section. First, **different instances never
overwrite each other**: two rows save concurrently, each with independent
status. Second, **the same instance supersedes**: when you re-execute while an
attempt is pending, the runtime suppresses the earlier attempt's reply. A
settled `:error` stays until you retry with the same execute, or until you
dismiss it with `[:rf.mutation/clear {:instance …}]`. The clear also aborts
in-flight work on a best-effort basis.

### Optimistic flip, automatic rollback

A favorite toggle must not wait 300 ms. Declare the optimistic plan on the
registration. The runtime then flips the cache before the request. At settle
time, the runtime commits, rolls back, or reconciles:

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
forward patch. On an `:error` or a cancellation, the runtime restores exactly
that snapshot. You never maintain an inverse patch by hand, so the inverse
cannot drift. On success, the authoritative reply overwrites the guess. A
concurrent write can land between the flip and the settle. In that case the
default `:on-conflict :invalidate` does not restore the snapshot over the
newer truth. It marks the entry stale and lets a refetch settle it. That is
the settle-merge principle, applied by the runtime at the cache. While the
flip is unconfirmed, the instance carries `:optimistic? true`.

## Cancellation and supersession

Nothing cancels implicitly, and you hand-code no race:

| Concern | Owner | The move |
|---|---|---|
| A reply beaten by a newer attempt, same instance | Runtime | Suppressed; its `:reply-to` never fires |
| A reply beaten by a newer fetch, same resource identity | Runtime | New generation supersedes; the stale reply never touches the cache |
| Dismissing a settled error, abandoning a write | You | `[:rf.mutation/clear {:instance …}]` — clears status, best-effort aborts |
| Stopping a read you no longer want | You | Withdraw the read — a state change; its demand releases (below), in-flight work aborts best-effort |
| What "newer" means | You | Identity is the policy: instance and params decide which attempts race |

The last row is the design point. When you choose `[:favorite slug]` over
`[:favorite]` as the instance, you choose what supersedes what. The runtime
enforces the race that you declared. It never invents one.

## Demand-driven committed reads

The patterns above leave one correlation hand-written: the correlation between
a resource's lifetime and the view that reads it. Route `:resources` owns page
data, and that is correct for page identity. But some resources are keyed by
view-local state — a typeahead's suggestions, a picker's options, a hover
preview. Such a resource belongs to no route, and the hand-written answer is
ceremony:

- ensure on mount
- release on unmount
- re-ensure on parameter change

Each step is an opportunity for a leak or for an orphaned fetch. [Hicasso](glossary.md#hicasso)
closes the gap with the committed read itself. A read of a resource may
declare demand:

```clojure
(h/sub [:rf/resource {:resource :app/suggestions
                      :params   {:q q}
                      :demand   true}])
```

The law, in full:

- **Commit acquires.** When a render that took this read commits, the read
  becomes the owner of demand for exactly that resource and params. The ensure
  runs after the commit. A render alone acquires nothing. An abandoned render,
  a StrictMode double-invoke, and a retry each acquire zero times, by
  construction.
- **Liveness releases.** Demand releases on unmount. It releases on parameter
  change: the old identity releases as the new identity acquires. It also
  releases when a committed render stops taking the read — for example, when a
  conditional becomes false. That case is ordinary, because reads are legal in
  branches.
- **Demand is not retention.** Release withdraws the ownership hold. The
  cached entry then lives out its ordinary cache lifetime (`:gc-after-ms`). A
  move between two queries therefore rejoins warm entries instead of
  refetching.
- **Nothing else is inferred.** Debounce, supersession, refresh-with-data, and
  cancellation stay exactly where the rest of this page put them. Demand
  decides *that* the resource is wanted while the read is live. Demand never
  decides *how* the resource behaves.
- **It costs nothing elsewhere.** Demand uses the committed-read membership
  that the runtime already keeps. A read contributes membership, not a record,
  and no per-read ledger exists. A [boundary](glossary.md#boundary) that reads no resource carries
  none of this.

Without `:demand true`, the sub is the core passive projection, and it never
causes work. A missing cause still means an honest, permanent `:idle`.

### The typeahead, end to end

This worked example exercises every policy at once.

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

The debounce is explicit policy at the event layer. The input echoes every
keystroke, because the controlled write stays synchronous. Debounce the
*consumers* of a value, never the write
([Controlled inputs](04-controlled-inputs.md)). What settles is the committed
query. A generation guards it, so a stale timer commits nothing:

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
    (if (= gen (get-in db [:search :gen]))     ;; the correlation recipe again
      {:db (assoc-in db [:search :committed-q] (get-in db [:search :text]))}
      {})))                                    ;; a stale timer commits nothing

(rf/reg-event :search/clear
  (fn [{:keys [db]} _]
    {:db (update db :search assoc
                 :text "" :committed-q ""
                 :gen  (inc (get-in db [:search :gen] 0)))}))  ;; pending timers go stale
```

Now the views. The list exists only while a committed query exists, and the
list's committed read declares demand:

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

Walk the timeline, and every policy is visible:

1. **`c` … `cl`** — each keystroke echoes through the [controlled input](glossary.md#controlled-field) at
   once. The generation increments. Nothing fetches. Debounce is your
   event-layer policy.
2. **250 ms of quiet** — the surviving timer's generation matches, and
   `:committed-q` becomes `"cl"`. `suggestion-list` mounts, and the render
   commits. The committed read acquires demand for `{:q "cl"}`, and the fetch
   begins. If React had discarded that render, nothing would have been
   acquired.
3. **`clo` before the reply lands** — the parameters change. `{:q "cl"}`
   releases, and `{:q "clo"}` acquires. The in-flight request for `{:q "cl"}`
   aborts best-effort. If its reply arrives anyway, the runtime's stale-reply
   check suppresses it. With `:keep-previous? true`, the `"cl"` list stays on
   screen with `:fetching?` true. That is refresh-with-data instead of a flash
   of empty content.
4. **Escape, or navigation away mid-fetch** — `:search/clear` empties the
   committed query. The list unmounts, and the demand releases. Navigation
   away from the page causes the same release by a different path.
   Cancellation was a state change that you wrote, not a lifecycle hook.
   Teardown residue is zero.
5. **Typing `cl` again, seconds later** — release made the entry demand-free;
   release did not destroy it. Within `:gc-after-ms`, the read rejoins the
   warm entry. If the entry is still fresh within `:stale-after-ms`, no
   request occurs at all.

The typeahead never contains the ceremony that this feature deletes: no
ensure-on-mount effect, no release-on-unmount cleanup, no params-change
watcher. The correlation between read liveness and resource demand has one
owner — the read.

Two edges remain. Under Xray, held demand is a bounded projection: which
resources are owned by which committed reads, and why each fetch fired
([Diagnostics](15-diagnostics.md)). On the server, the [boundary](glossary.md#boundary) is Client-only
by default — a deterministic fallback and zero acquisition, because
acquisition is a client commit fact. Hydration then adopts without a duplicate
fetch ([SSR and hydration](17-ssr-and-hydration.md)).

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Permanent `:idle` skeleton | Nothing declares demand or any other cause | `:demand true` on the committed read — or a route `:resources` entry / explicit ensure where the route owns the data |
| `:rf.error/resources-artefact-missing` at boot | The resource model is not loaded | `(:require [re-frame.resources])`, usually with `re-frame.http.managed` |
| Suggestions flash empty on every keystroke | Each params value is a new cache identity | `:keep-previous? true` on the read; render `:fetching?` as the quiet indicator |
| Results for an old query overwrite new ones | A hand-rolled fetch outside the resource model | Let the runtime own replies — identity plus generation suppress stale ones; a hand-rolled loader needs the correlation recipe |
| A read throws after the render, naming the query | The read escaped the direct synchronous body — callback, promise, timer, lazy seq | Read during the body and close over the value ([Views and reads](02-views-and-reads.md)) |
| Every row's button goes pending together | One shared instance id | Key the instance per row: `[:favorite slug]` |
| Keystrokes vanish when a save reply lands | The reply was written wholesale over the draft | Settle-merge: take settled values only where the draft still equals the sent basis |
| An optimistic value reverts over a newer concurrent write | `:on-conflict :force` restores the snapshot blindly | Keep the default `:invalidate` — stale-mark and refetch instead of overwriting |

## When not to use demand

Demand-driven reads own **resource lifetimes that match a read's lifetime**.
They are not a fetch library:

| Job | Owner |
|---|---|
| Page-identity data — the article this URL names | Route `:resources`: the route owns it, blocks the transition honestly, and runs under SSR |
| Warming data no read wants yet | Routing's `:prefetch :intent`, or an explicit ownerless ensure ([Routing and navigation](07-routing-and-navigation.md)) |
| "Refetch on click" | `[:rf.resource/refetch …]` — an event, because that is a cause, not a lifetime |
| A one-off uncached call | Managed HTTP: request out, uniform reply in |
| Draft state, validation gating, submit flow | The forms module ([Forms](05-forms.md)) |
| A multi-step async workflow with named stages | A machine owning the ensures, with resource status driving transitions |

If no view reads the resource, demand cannot express it. That is by design: a
demand with no reader is exactly the hand-written correlation that this
feature retired.

??? info "Coming from TanStack Query?"

    `useQuery` has a close shape: read it and it fetches; unmount and it
    forgets. Three differences are deliberate. First, acquisition happens at
    commit, so speculative renders fetch nothing. Second, policies are not
    call-site options: debounce lives in your events, staleness and GC live on
    the registration, and supersession lives in the runtime's identity rules.
    Third, the cache is causal: writes invalidate by declared tags, not by a
    remembered `invalidateQueries`.

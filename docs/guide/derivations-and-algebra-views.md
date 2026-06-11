# One Graph: Derivations and Their Algebra Views

If you've read [Where should this value live?](where-state-lives.md), you already met the punchline in a `<details>` box: a subscription, a flow, a resource, and a machine are *the same thing seen four ways* — a node in one dependency graph rooted at your state, distinguished only by where the value is kept and when it's recomputed. This page is that box, opened up and made concrete. You don't need it to write a re-frame2 app — the four questions get you to the right home without it. You want it when you're inspecting an app you didn't write, when a tool shows you a graph and you want to read it, or when you simply like the unifying idea behind the five separate APIs you've been learning.

The closest thing in the JavaScript ecosystem is the moment you realise that TanStack Query's `useQuery`, a derived `useMemo`, a Redux selector, and an XState machine are all *the same dependency-graph node* with different caching and re-evaluation policies — except nobody ever wrote that down as one model. re-frame2 does. It's called the **derivation/process algebra**, and the normative version lives in [`spec/Derivations.md`](https://github.com/day8/re-frame2/blob/main/spec/Derivations.md). This page is the friendly tour.

## The shape every node has

A **derivation** is a declared way to compute a fact from inputs. A **process** is a derivation that also has state, a lifecycle, and commands over time. Every declared fact in re-frame2 — every subscription, runtime subscription, flow, resource read, route fact, and machine selector — is one or the other, and every one of them answers the same five questions:

| Question | Field | Possible answers |
|---|---|---|
| What does it read? | **`:inputs`** | other subs, app-db paths, runtime-db paths, route/resource/machine refs, events… (or `:parametric`) |
| What fact does it produce? | **`:output`** | an ephemeral `[:fact …]`, or a durable `[:db …]` / `[:runtime …]` address |
| Where does the value live? | **`:storage`** | `:ephemeral` · `:app-db` · `:runtime-db` · `:host-transient` |
| When does it run? | **`:evaluation`** | `:on-demand` · `:after-event` · `:on-reply` · `:on-route` · `:on-transition` · `:scheduled` · `:manual` |
| Who keeps it alive? | **`:lifecycle`** | a cache entry · the frame · a route · a resource key · a machine instance · a host root |

That's the whole vocabulary. The five plural source forms you write (`reg-sub`, `reg-flow`, `reg-resource`, `reg-route`, `reg-machine`) each **lower** to this one shape — the **algebra view**. You keep writing the ergonomic source form; tools, tests, and docs read the normalized view. The rest of this page is the lowering, one member at a time, source form on the left, algebra view on the right.

> **You never write the algebra view.** It is not a new API — there is no `reg-fact` or `reg-derivation`. The view is *derived* from the registration you already wrote, exactly so a tool can answer "where does this value come from, when does it run, where does it live, and who owns it?" without reading your function bodies. The source form stays the thing humans write.

## The keystone: one function, two policies

Start with the example that makes the whole idea click. Here is a cart total expressed *twice* — once as a subscription, once as a flow — using the identical formula:

```clojure
;; Source form A — a subscription.
(rf/reg-sub :cart/total
  :<- [:cart/items]
  :<- [:pricing/discounts]
  (fn [[items discounts] _] (sum-cart items discounts)))

;; Source form B — a flow, the same function.
(rf/reg-flow
  {:id     :cart/materialized-total
   :inputs [[:cart :items] [:pricing :discounts]]
   :output (fn [items discounts] (sum-cart items discounts))
   :path   [:cart :total]})
```

`sum-cart` is one whole-value function. Their algebra views differ **only in the four policy axes** — not in the math:

| | Subscription view | Flow view |
|---|---|---|
| `:kind` | `:derivation` | `:derivation` |
| `:output` | `[:fact :cart/total]` | `[:db [:cart :total]]` |
| `:storage` | `:ephemeral` | `:app-db` |
| `:evaluation` | `:on-demand` | `:after-event` |
| `:lifecycle` | `:subscription-cache-entry` | `:frame` |
| `:materialized?` | `false` | `true` |
| `:derive` | `#'app.cart/sum-cart` | `#'app.cart/sum-cart` |

The difference between a subscription and a flow is **not the function; it is policy over the same dependency graph.** That sentence is the entire reason the algebra exists. Once you see it, the other four homes are just other points in the same policy space.

## Subscription → derivation

A subscription is the canonical *ephemeral* derivation: recompute on demand, never written anywhere durable, kept alive only while a view is reading it.

```clojure
;; SOURCE FORM                              ;; ALGEBRA VIEW
(rf/reg-sub :cart/total                     {:id          :cart/total
  :<- [:cart/items]                          :kind        :derivation
  :<- [:pricing/discounts]                   :source-form {:kind :reg-sub :id :cart/total}
  (fn [[items discounts] _]                  :inputs      [[:sub [:cart/items]]
    (sum-cart items discounts)))                           [:sub [:pricing/discounts]]]
                                             :output      [:fact :cart/total]
                                             :storage     :ephemeral
                                             :evaluation  :on-demand
                                             :lifecycle   :subscription-cache-entry
                                             :materialized? false
                                             :derive      #'app.cart/sum-cart}
```

The two `:<-` lines become two `[:sub …]` input edges, in order. A **layer-1** sub that reads `app-db` directly is conservatively declared `[[:db []]]` (it was handed the whole `app-db` value, so the safe declared input is the whole partition):

```clojure
;; SOURCE FORM                              ;; ALGEBRA VIEW
(rf/reg-sub :cart/items                      {:id      :cart/items
  (fn [db _]                                  :kind    :derivation
    (get-in db [:cart :items])))              :inputs  [[:db []]]
                                              :output  [:fact :cart/items]
                                              :storage :ephemeral
                                              :evaluation :on-demand
                                              :lifecycle  :subscription-cache-entry}
```

### Parametric subscription — static vs live

When a subscription's inputs are computed by an input function, the **static** graph can't know the edges before a concrete query vector exists — it must *not* invent them. It reports `:parametric` and names the producer; the **live** graph reports the realized edges per concrete entry:

```clojure
;; SOURCE FORM
(rf/reg-sub :article/page
  (fn [[_ slug]]                             ;; input function — edges depend on `slug`
    [[:article/by-slug slug]
     [:comments/for-article slug]])
  (fn [[article comments] [_ slug]]
    {:slug slug :article article :comments comments}))
```

```clojure
;; STATIC VIEW                               ;; LIVE VIEW for [:article/page "welcome"]
{:id :article/page                           {:id [:sub [:article/page "welcome"]]
 :kind :derivation                            :kind :derivation
 :inputs :parametric                          :inputs [[:sub [:article/by-slug "welcome"]]
 :input-producer                                       [:sub [:comments/for-article "welcome"]]]
   #'app.article/article-page-inputs          :output [:fact [:article/page "welcome"]]
 :output [:fact :article/page]                :storage :ephemeral
 :storage :ephemeral                          :evaluation :on-demand
 :evaluation :on-demand                       :lifecycle :subscription-cache-entry}
 :lifecycle :subscription-cache-entry}
```

This **don't-execute rule** — static inspection never runs your input/scope/param functions — is what makes the static graph safe to compute anywhere (tests, docs, an editor plugin) with no side effects and no runtime assumptions.

## Flow → materialized derivation

A flow is the subscription's policy twin: same kind of whole-value function, but `:after-event` evaluation writing into `:app-db`, owned by the frame.

```clojure
;; SOURCE FORM                              ;; ALGEBRA VIEW
(rf/reg-flow                                 {:id          :cart/materialized-total
  {:id     :cart/materialized-total           :kind        :derivation
   :inputs [[:cart :items]                    :source-form {:kind :reg-flow
            [:pricing :discounts]]                          :id   :cart/materialized-total}
   :output (fn [items discounts]              :inputs      [[:db [:cart :items]]
             (sum-cart items discounts))                    [:db [:pricing :discounts]]]
   :path   [:cart :total]})                   :output      [:db [:cart :total]]
                                              :storage     :app-db
                                              :evaluation  :after-event
                                              :lifecycle   :frame
                                              :materialized? true
                                              :derive      #'app.cart/sum-cart}
```

Each `:inputs` path becomes a `[:db …]` edge; the `:path` becomes the `[:db …]` output address. `:after-event` means same-commit materialization — for a registered flow there's no general one-event staleness; the inputs and the materialized output move together in one atomic install.

## Resource → process

A resource is the first **process** — a derivation *with state, lifecycle, and commands*. One declaration lowers to **more than one** node: a process node for the cache entry, plus the `:rf.resource/*` read facts (its selectors) over that entry.

```clojure
;; SOURCE FORM
(rf/reg-resource :article/by-slug
  {:params-schema  [:map [:slug :string]]
   :data-schema    :app/article
   :scope          :rf.scope/from-caller
   :request        (fn [{:keys [slug]} _ctx]
                     {:request {:method :get :url (str "/api/articles/" slug)}
                      :decode  :app/article})
   :stale-after-ms 60000
   :gc-after-ms    300000})
```

```clojure
;; STATIC ALGEBRA VIEW
{:id          :article/by-slug
 :kind        :process                       ;; the closed superkind
 :refinement  :resource-process              ;; the informative refinement
 :source-form {:kind :reg-resource :id :article/by-slug}
 :inputs      [[:param :slug]
               [:scope :rf.scope/from-caller]]
 :output      [:runtime [:rf.runtime/resources :entries]]
 :storage     :runtime-db                     ;; the LOCAL cache lives here
 :authority   {:kind :remote :system :server  ;; the source of truth is external
               :transport :rf.http/managed}
 :evaluation  #{:on-route :on-reply :scheduled :manual}   ;; a multi-trigger process
 :lifecycle   :resource-key
 :materialized? true
 :selectors   [:rf.resource/state :rf.resource/data :rf.resource/status
               :rf.resource/loading? :rf.resource/error :rf.resource/has-data?]}
```

Notice the two-axis split that the algebra makes explicit: **`:storage` always names the local home** (`:runtime-db` — the cache entry), while **`:authority` names where the truth really lives** (an external server). "Remote" is never a storage class; it's a separate fact. Reading a selector is an ordinary on-demand derivation over the runtime-db entry — it does *not* start resource work.

The **live** view reports one node per concrete scoped key, with realized inputs and the in-flight handle:

```clojure
;; LIVE ALGEBRA VIEW for one scoped key
{:id     [:resource [[:rf.scope/session {:tenant-id "acme"}] :article/by-slug {:slug "welcome"}]]
 :kind   :process
 :inputs [[:scope [:rf.scope/session {:tenant-id "acme"}]] [:param {:slug "welcome"}]]
 :output [:runtime [:rf.runtime/resources :entries
                    [[:rf.scope/session {:tenant-id "acme"}] :article/by-slug {:slug "welcome"}]]]
 :storage :runtime-db
 :authority {:kind :remote :system :server}
 :status  :loaded
 :lifecycle {:kind :resource-key :owners #{[:route :route/article 17]}}
 :host-transient [[:rf.http/in-flight :work/id-123]]}
```

The local representation is runtime-owned durable state (`:runtime-db`) **plus** a host-transient in-flight handle that lives outside durable frame state and must be torn down at its boundary.

## Route → route fact (process-like)

A route transition materializes the route slice and can own resource activation. Every route lowers to the *same* fact id — `:rf/route`, the one consumer-facing name for the route slice — with the per-route id recorded under `:source-form`:

```clojure
;; SOURCE FORM
(rf/reg-route :route/article
  {:path "/articles/:slug"
   :params [:map [:slug :string]]
   :resources [{:resource :article/by-slug
                :params   (fn [route] {:slug (get-in route [:params :slug])})
                :blocking? true}]})
```

```clojure
;; ALGEBRA VIEW
{:id          :rf/route                       ;; the one name for the slice (every route shares it)
 :kind        :process                        ;; the closed superkind
 :refinement  :route-fact                     ;; the informative refinement
 :source-form {:kind :reg-route :id :route/article}
 :inputs      [[:event :rf.route/navigate]    ;; the on-route causal triggers
               [:event :rf.route/transitioned]
               [:event :rf.route/handle-url-change]]
 :output      [:runtime [:rf.runtime/routing :current]]
 :storage     :runtime-db
 :evaluation  :on-route
 :lifecycle   :frame
 :materialized? true
 :resource-edges                              ;; route OWNS this resource's activation
 [{:from [:runtime [:rf.runtime/routing :current :params]]
   :to   [:resource :article/by-slug]
   :role :param
   :target :parametric                        ;; concrete key needs a live match + scope
   :blocking? true}]}
```

The route's `:resources` declaration becomes a route-owned **resource activation edge**: the matched params flow into the resource. Its `:target` is `:parametric` — by the don't-execute rule, static inspection never runs the entry's `:params`/`:scope` functions, so the concrete scoped key only appears in the live graph.

## Machine → process, and its selectors → derivations

A machine is the algebra's canonical process — the surface that motivates the `:process` superkind at all. Its snapshot is durable runtime-db state; its selectors are ordinary ephemeral derivations over that snapshot.

```clojure
;; SOURCE FORM
(rf/reg-machine :upload/main
  {:initial :idle
   :data    {:progress 0}
   :states  {:idle      {:on {:upload/start {:target :uploading}}}
             :uploading {:entry :start-upload
                         :on {:upload/progress  {:action :record-progress}
                              :upload/succeeded {:target :done}
                              :upload/failed    {:target :failed}}}
             :failed {} :done {}}})
```

```clojure
;; MACHINE PROCESS VIEW
{:id          :upload/main
 :kind        :process                       ;; the closed superkind
 :refinement  :machine-process               ;; the informative refinement
 :source-form {:kind :reg-machine :id :upload/main}
 :inputs      [[:event :upload/start] [:event :upload/progress]   ;; every :on key, deduped
               [:event :upload/succeeded] [:event :upload/failed]]
 :output      [:runtime [:rf.runtime/machines :snapshots :upload/main]]
 :storage     :runtime-db
 :evaluation  #{:on-transition}              ;; + :scheduled if any :after, + :on-reply if it spawns
 :lifecycle   :machine-instance
 :materialized? true}
```

```clojure
;; SELECTOR SOURCE FORM                      ;; SELECTOR ALGEBRA VIEW
(rf/reg-sub :upload/progress                  {:id         :upload/progress
  :<- [:rf/machine :upload/main]               :kind       :derivation
  (fn [snapshot _]                             :refinement :machine-selector  ;; a :derivation refinement
    (get-in snapshot [:data :progress] 0)))    :inputs     [[:machine :upload/main [:data :progress]]]
                                               :output     [:fact :upload/progress]
                                               :storage    :ephemeral
                                               :evaluation :on-demand
                                               :lifecycle  :subscription-cache-entry
                                               :derive     #'app.upload/progress}
```

The machine is the stateful **process**; the selector is an ephemeral **derivation** over the materialized snapshot. Machines do not become a second subscription system — a selector is just a `reg-sub` over `[:rf/machine …]`, recognized and edged to its machine with a `:selector` role.

## Reading the assembled graph

A tool stitches all of those per-family views into one graph: a map of `:nodes` keyed by canonical id and a list of `:edges`. An Xray panel renders it as a single picture even though the runtime mechanisms underneath are a subscription cache, a flow, a resource cache, a route slice, and a machine snapshot:

```clojure
{:mode  :live
 :frame :main
 :nodes
 {[:sub [:article/page "welcome"]]    {:kind :derivation :storage :ephemeral  :evaluation :on-demand}
  [:runtime [:rf.runtime/routing :current]] {:kind :process :storage :runtime-db}
  [:resource [[:rf.scope/session {:tenant-id "acme"}] :article/by-slug {:slug "welcome"}]]
                                       {:kind :process :storage :runtime-db
                                        :status :loaded :owners #{[:route :route/article 17]}}}
 :edges
 [{:from [:runtime [:rf.runtime/routing :current :params :slug]]
   :to   [:sub [:article/page "welcome"]] :role :input}
  {:from [:runtime [:rf.runtime/routing :current :params :slug]]
   :to   [:resource [[:rf.scope/session {:tenant-id "acme"}] :article/by-slug {:slug "welcome"}]]
   :role :param}
  {:from [:resource [[:rf.scope/session {:tenant-id "acme"}] :article/by-slug {:slug "welcome"}]]
   :to   [:sub [:article/page "welcome"]] :role :input}]}
```

Two things make this graph safe to ship to a tool. **Redaction**: the graph carries source coordinates and value *summaries*, never raw sensitive values — a redacted param is still an edge, so structure survives even when content is hidden. And **the whole-value law**: every derivation must be correct as a function that recomputes its entire output from its inputs. Memoization, equality pruning, and (someday) deltas are optimizations that must not change the observed value — which is exactly why a tool can recompute any node from the graph and trust the answer.

## The one rule worth remembering

You don't carry the table around in your head. You carry the one sentence the keystone example proved:

> A subscription, a flow, a resource, a route fact, and a machine selector are the same dependency-graph node under different **storage** and **evaluation** policies. The source forms differ for good ergonomic reasons; the algebra view is what they have in common.

When you want the full normative contract — the node schema, every classification rule, the static/live modes, the don't-execute rule, the whole-value and optional-delta laws — read [`spec/Derivations.md`](https://github.com/day8/re-frame2/blob/main/spec/Derivations.md). When you just want to pick a home for a value, go back to the four questions in [Where should this value live?](where-state-lives.md). This page is the bridge between them: the *why* the four homes are four faces of one idea.

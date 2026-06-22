# One graph: derivations and their algebra views

You're reading a re-frame2 app you didn't write. A tool has just drawn its dependency graph — subscriptions, a flow, a resource cache, a machine — as one picture, and you want to know what you're looking at. This page is the model that makes the picture legible. It fits in one sentence:

> **A subscription, a flow, a resource, and a machine are the same thing seen four ways** — one node in one dependency graph rooted at your state, distinguished only by *where its value is kept* and *when it's recomputed*.

You don't need this page to *write* an app. [Where should this value live?](where-state-lives.md) routes any value to the right home with four questions and no theory. This page is for *reading* apps — yours six months later, or somebody else's tomorrow, when the graph a tool draws has to make sense without opening every source file.

The anchor here is a spreadsheet. Every cell is either an entered value or a formula over other cells, and the engine recalculates exactly the cells downstream of an edit. The JavaScript world rediscovered this as the **signals graph** — Solid's `createMemo`, Vue's `computed`, Preact signals — where a derived value declares its inputs and recomputes when those inputs move. re-frame2's [subscriptions](concepts/subscriptions.md) (the read side of your app, pure derivations over your state) are exactly that.

This page adds one bigger claim, and it's the deliberate divergence from the signals world: **flows, resources, route state, and machines are nodes in the same graph.** A flow is a cell that writes its result back into the sheet. A resource is a cell whose authoritative value lives on a server — locally you hold a copy that can go stale. A machine is a cell with memory, where its next value depends on its current one. The signals ecosystem never wrote that unification down as one model; re-frame2 does. It's called the **derivation/process algebra**, and its normative contract is [`spec/Derivations.md`](../../spec/Derivations.md). This page is the tour.

## The five questions every node answers

A **derivation** computes a fact from declared inputs with a pure function. A **process** is a derivation that also has state, a lifecycle, and commands over time — it can react to events (the data messages your app dispatches when something happens), async replies, route changes, and timers. Every declared fact in re-frame2 — subscription, flow, resource read, route fact, machine selector — is one or the other, and each answers the same five questions:

| Question | Field | Possible answers |
|---|---|---|
| What does it read? | `:inputs` | other subs, app-db / runtime-db paths, route/resource/machine refs, params, events… or `:parametric` |
| What does it produce? | `:output` | an ephemeral `[:fact …]`, or a durable `[:db …]` / `[:runtime …]` address |
| Where does the value live? | `:storage` | `:ephemeral` · `:app-db` · `:runtime-db` · `:host-transient` |
| When does it run? | `:evaluation` | `:on-demand` · `:after-event` · `:on-reply` · `:on-route` · `:on-transition` · `:scheduled` · `:manual` |
| Who keeps it alive? | `:lifecycle` | a cache entry · the frame · a route · a resource key · a machine instance · a host root |

That's the whole vocabulary. The five source forms you actually write — `reg-sub`, `reg-flow`, `reg-resource`, `reg-route`, `reg-machine` — each **lower** to this one shape, called the node's **algebra view**.

> **You never write the algebra view.** There is no `reg-fact` and no `reg-derivation`. The view is *derived* from the registration you already wrote, and that's the point: a tool can answer "where does this value come from, when does it run, where does it live, who owns it?" without reading your function bodies. The source forms stay the things humans write; the algebra view is what a tool reads.

## The keystone: one function, two policies

Here is the example that makes the idea click — a cart total, expressed twice, with the identical formula:

```clojure
;; Source form A — a subscription.
(rf/reg-sub :cart/total
  :<- [:cart/items]
  :<- [:pricing/discounts]
  (fn [[items discounts] _] (sum-cart items discounts)))

;; Source form B — a flow. The same function.
(rf/reg-flow
  {:id     :cart/materialized-total
   :inputs [[:cart :items] [:pricing :discounts]]
   :derive (fn [items discounts] (sum-cart items discounts))
   :output-path [:cart :total]})
```

`sum-cart` is one whole-value function. The two algebra views differ **only in the policy fields**, not in the math:

| | Subscription view | Flow view |
|---|---|---|
| `:kind` | `:derivation` | `:derivation` |
| `:output` | `[:fact :cart/total]` | `[:db [:cart :total]]` |
| `:storage` | `:ephemeral` | `:app-db` |
| `:evaluation` | `:on-demand` | `:after-event` |
| `:lifecycle` | `:subscription-cache-entry` | `:frame` |
| `:derive` | `#'app.cart/sum-cart` | `#'app.cart/sum-cart` |

The difference between a subscription and a flow is **not the function; it is policy over the same dependency graph.** That's the entire reason the algebra exists, and extending it across the homes gives you the whole page. A subscription stores nothing and recomputes on demand. A [flow](concepts/flows.md) stores into app-db (your app's single state map) and recomputes after each event, in the same commit as the event that moved its inputs. A resource stores a runtime-db cache entry and recomputes on cause and staleness. A machine stores a snapshot and recomputes on transition. Same graph, different storage-and-evaluation policy per node.

## One node, opened up

Here is the complete algebra view of that subscription — the shape every node in an inspected graph has:

```clojure
{:id          :cart/total
 :kind        :derivation                      ;; superkind: :derivation | :process
 :source-form {:kind :reg-sub :id :cart/total} ;; what the author actually wrote
 :inputs      [[:sub [:cart/items]]
               [:sub [:pricing/discounts]]]
 :output      [:fact :cart/total]
 :storage     :ephemeral
 :evaluation  :on-demand
 :lifecycle   :subscription-cache-entry
 :materialized? false
 :derive      #'app.cart/sum-cart}             ;; opaque token, never serialized code
```

Two fields earn a comment. `:kind` is one of exactly two closed superkinds — `:derivation` or `:process` — so a tool that understands only those two can still classify every node. The finer labels you'll meet below (`:resource-process`, `:machine-selector`, …) ride on a separate `:refinement` field. And `:derive` is an opaque token, because the graph contract is about dependencies, storage, evaluation, and ownership; it never requires serializing your functions.

### Parametric nodes and the don't-execute rule

When a subscription's inputs come from an input function, the static graph can't know the edges before a concrete query vector exists — and it must not guess. Static inspection reports `:parametric` and names the producer, while the live graph reports the realized edges per concrete cache entry:

```clojure
;; STATIC — derived from registrations alone
{:id             :article/page
 :kind           :derivation
 :inputs         :parametric
 :input-producer #'app.article/article-page-inputs}

;; LIVE — one node per concrete query vector
{:id        [:sub [:article/page "welcome"]]
 :kind      :derivation
 :inputs    [[:sub [:article/by-slug "welcome"]]
             [:sub [:comments/for-article "welcome"]]]
 :lifecycle :subscription-cache-entry}
```

This is the **don't-execute rule**: static inspection never runs your input, param, or scope functions. It reads declarations only, which is what makes a static graph safe to compute anywhere — tests, docs, an editor — with no side effects and no runtime assumptions. A `:parametric` edge set contributes no static edges; the realized ones appear only in the live graph, observed from the running app.

> **Why two modes.** Think of `EXPLAIN` on a parameterized SQL query versus the actual rows a given parameter binding returns. The static graph is the query plan — derived from declarations, runnable offline, the same for every parameter. The live graph is the result for a *concrete* binding — one node per realized query vector, edges and all. A `:parametric` node is the framework refusing to guess at plan time what only a real binding can answer.

## Processes: nodes with state and a lifecycle

A resource (a fact whose authoritative value lives on a server, with a local cached copy) is the first **process**. One `reg-resource` declaration ([Server state: resources](concepts/server-state.md)) lowers to *more than one* node: a process node for the cache entry, plus its read selectors (`:rf.resource/state`, `:rf.resource/data`, `:rf.resource/loading?`, …), each an ordinary on-demand derivation over that entry. Reading a selector never starts work.

```clojure
;; STATIC ALGEBRA VIEW of (rf/reg-resource :article/by-slug {…})
{:id          :article/by-slug
 :kind        :process
 :refinement  :resource-process
 :inputs      [[:param :slug] [:scope :rf.scope/from-caller]]
 :output      [:runtime [:rf.runtime/resources :entries]]
 :storage     :runtime-db                     ;; the LOCAL cache lives here
 :authority   {:kind :remote :system :server  ;; the truth lives elsewhere
               :transport :rf.http/managed}
 :evaluation  #{:on-route :on-reply :scheduled :manual}   ;; a multi-trigger process
 :lifecycle   :scoped-resource-key
 :materialized? true
 :selectors   [:rf.resource/state :rf.resource/data :rf.resource/status
               :rf.resource/loading? :rf.resource/error :rf.resource/has-data?]}
```

The view makes a split explicit that folklore usually muddles. **`:storage` always names the local home** — here, the runtime-db cache entry. **`:authority` names whose fact it really is** — an external server. This trips people up: "remote" is never a storage class. Locally you always hold a representation, and the graph says exactly where.

> **Coming from TanStack Query?** Your query cache is exactly this split, just unnamed. The server is the `:authority`; the `queryCache` is the local `:storage`; `isLoading` / `data` / `error` are read selectors over one cache entry; and the staleness/refetch policy is the entry's `:evaluation`. re-frame2 writes those four facts down as fields instead of leaving them implicit in a hook's behaviour, which is the whole reason a tool can draw a resource the same way it draws a subscription.

> **Where the non-serializable leftovers go.** An in-flight request handle or a timer can't be serialized into durable state, so they're classed `:host-transient`: outside durable frame state, torn down at their lifecycle boundary. They never become the *only* copy of a fact — replay and restore depend on the durable `:runtime-db` entry, not the live socket.

A machine (a stateful node whose next value depends on its current one) is the algebra's canonical process — the surface that motivates the `:process` superkind at all. Its snapshot is durable runtime-db state, written only by its own transitions. Its `:inputs` are the event ids its transition table listens for, its `:evaluation` is `:on-transition` (plus `:scheduled` if it declares an `:after` delay), and its *selectors* — how views (your UI functions that render from subscriptions) consume it — are ordinary subscriptions:

```clojure
(rf/reg-sub :upload/progress
  :<- [:rf/machine :upload/main]
  (fn [snapshot _] (get-in snapshot [:data :progress] 0)))
```

That selector's algebra view is an `:ephemeral`, `:on-demand` derivation like any other. It carries the `:machine-selector` refinement and an edge back to the machine it reads, so machines never become a second subscription system. The graph shows one machine process node feeding ordinary derivation nodes ([State machines](concepts/machines.md)).

A route lowers the same way. Every route materializes the *same* route fact — `:rf/route`, the one consumer-facing name for the route slice in runtime-db — with the per-route id recorded in `:source-form` and evaluation `:on-route`. A route's `:resources` declaration becomes a route-owned **activation edge** into the resource it ensures ([Routing: the URL is a sub](concepts/routing.md)). The edge's concrete target stays `:parametric` in the static graph — the don't-execute rule again — and the concrete scoped key appears only live.

## Reading the assembled graph

A tool stitches the per-family views into one value — a map of `:nodes` and a list of `:edges`:

```clojure
{:mode  :live
 :frame :main
 :nodes
 {[:sub [:article/page "welcome"]]  {:kind :derivation :storage :ephemeral :evaluation :on-demand}
  :rf/route                         {:kind :process :storage :runtime-db
                                     :output [:runtime [:rf.runtime/routing :current]]}
  [:resource [[:rf.scope/global] :article/by-slug {:slug "welcome"}]]
                                    {:kind :process :storage :runtime-db :status :loaded}}
 :edges
 [{:from [:runtime [:rf.runtime/routing :current :params :slug]]
   :to   [:sub [:article/page "welcome"]] :role :input}
  {:from [:runtime [:rf.runtime/routing :current :params :slug]]
   :to   [:resource [[:rf.scope/global] :article/by-slug {:slug "welcome"}]] :role :param}
  {:from [:resource [[:rf.scope/global] :article/by-slug {:slug "welcome"}]]
   :to   [:sub [:article/page "welcome"]] :role :input}]}
```

Read it with three rules.

First, **a node's key is its canonical id, not its output address** — `[:sub <query>]`, `[:resource <scoped-key>]`, `:rf/route` for the live route slice. The runtime path a node writes to is recorded *inside* the node as `:output`, so the key is purely how you look it up.

Second, **redaction preserves structure**. Graph payloads carry source coordinates and value *summaries*, never raw sensitive values, so a redacted param is still an edge and connectivity survives even when content is hidden.

Third, **the whole-value law**: every derivation must be correct as a function that recomputes its entire output from its declared inputs. Memoization, equality pruning, and (someday) deltas are optimizations that must not change the observed value. That law lets conformance tests verify a node by recomputing it, and lets a tool trust declared edges and classifications without executing your app code. What you trust in a graph is the *structure* — who reads what, where it lives, when it runs — never a promise that any node can be re-executed on demand.

To see one live, open Xray on a running app. The panel that draws the dependency graph renders exactly this assembled view — one node per algebra view, one arrow per edge record — even though the mechanisms underneath are a subscription cache, a flow, a resource cache, a route slice, and a machine snapshot. [Debug with Xray](how-to/debug-with-xray.md) shows the workflow.

> **No public graph-accessor yet (pre-alpha).** The assembled shape is consumed internally — by Xray and the conformance fixtures — and a public name ships only once the shape survives real use. What you can rely on today is the model: the classifications on this page are normative, and they are what the tools render.

## The rule worth carrying

You don't memorize the tables. You carry the sentence the keystone example proved:

> A subscription, a flow, a resource, a route fact, and a machine are the same dependency-graph node under different **storage** and **evaluation** policies. The source forms differ for good ergonomic reasons; the algebra view is what they share.

When you want the full normative contract — the node schema, every classification rule, the static/live modes, the whole-value law — read [`spec/Derivations.md`](../../spec/Derivations.md). When you just want to pick a home for a value, ask the four questions in [Where should this value live?](where-state-lives.md). This page is the bridge between them: *why* the four homes are four faces of one idea.

---

**You can now:**

- read a dependency graph a tool draws over an app you didn't write, and classify any node by its five fields
- explain why a subscription and a flow with the same formula are one derivation under two policies
- say why "remote" is an `:authority`, never a `:storage` class — and where a resource's value actually lives
- state what a tool may trust about a graph (declared structure, under the don't-execute rule) and what it may not (re-executing nodes)

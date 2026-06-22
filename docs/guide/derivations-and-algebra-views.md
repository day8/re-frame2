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

> **Same-commit, with one exception.** A flow's `:after-event` policy means it recomputes *inside the event drain* that moved its inputs, so its materialized output lands in the **same commit** as that event — there's no general one-event staleness, the way derived-state-on-the-side sometimes has in other frameworks. The lone exception: a flow that is *registered mid-event* (its `reg-flow` runs as an effect of the very event it would react to) can't see that event's inputs until the next one. That's the only one-event lag in the model, and it's documented in [Derivations §Evaluation policy](../../spec/Derivations.md) and the flows spec — worth knowing exists, rarely worth worrying about.

## One node, opened up

Here is the complete algebra view of that subscription — the shape every node in an inspected graph has:

```clojure
{:id          :cart/total
 :kind        :derivation                      ;; superkind: :derivation | :process
 :refinement  nil                              ;; informative colour; nil for a plain sub
 :source-form {:kind :reg-sub :id :cart/total} ;; what the author actually wrote
 :inputs      [[:sub [:cart/items]]
               [:sub [:pricing/discounts]]]
 :output      [:fact :cart/total]
 :storage     :ephemeral
 :authority   nil                              ;; the remote axis — present only when external
 :evaluation  :on-demand
 :lifecycle   :subscription-cache-entry
 :materialized? false
 :derive      #'app.cart/sum-cart              ;; opaque token, never serialized code
 :schema      :app.money/amount               ;; the output's schema, when the registration carried one
 :source      {:ns "app.cart" :file "src/app/cart.cljs" :line 42}}
```

Three fields earn a comment. `:kind` is one of exactly two closed superkinds — `:derivation` or `:process` — so a tool that understands only those two can still classify every node. The finer labels you'll meet below (`:resource-process`, `:route-fact`, `:machine-process`, `:machine-selector`) never live in `:kind`; they ride on a separate `:refinement` field, so a refinement always refines its node's superkind and never invents a third one. And `:derive` is an opaque token, because the graph contract is about dependencies, storage, evaluation, and ownership; it never requires serializing your functions.

The trailing fields are the **diagnostic dressing** every node carries when its registration supplied it: `:schema` (the output's Malli schema), `:source` (namespace / file / line, so a tool can jump to the definition), plus a doc string. They're optional — a node with none of them is still a complete node — but a good graph carries them, because "where is this defined and what shape does it produce?" is exactly the question a reader of an unfamiliar app asks first.

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

> **Where the non-serializable leftovers go.** An in-flight request handle or a timer can't be serialized into durable state, so they're classed `:host-transient`: outside durable frame state, torn down at their lifecycle boundary. They never become the *only* copy of a fact — replay and restore depend on the durable `:runtime-db` entry, not the live socket. In a live resource view you'll see them surface as `:host-transient [[:rf.http/in-flight :work/id-123]]` and a `:work-ledger` summary (the in-flight attempt's identity, owners, causes, and transport) — present only while a fetch is actually in flight, gone once it settles.

### When a parametric edge becomes static again

The don't-execute rule says static inspection won't *run* your scope function — so a resource scoped by an inline `(fn [route ctx] …)` reports the opaque marker `[:scope :rf.scope/resolver]` and nothing more. But there's an escape hatch that buys back static visibility: a **named scope resolver**. When you declare the scope as a data reference — `{:from-db :session/current-tenant}` instead of an inline function — the resolver's *declared inputs* are themselves declarations, so the static graph can read them without running anything:

```clojure
;; STATIC ALGEBRA VIEW — named-resolver scope
{:id          :article/by-slug
 :kind        :process
 :refinement  :resource-process
 :inputs      [[:param :slug]
               [:scope {:from-db :session/current-tenant}]]   ;; the reference, verbatim — static!
 :scope-resolver {:id     :session/current-tenant
                  :inputs [[:db [:session :tenant-id]]]}        ;; its declared inputs are static facts
 :params      :parametric}
```

The lesson generalizes: **declaring a dependency as data, rather than burying it in a function body, is what lets a tool see it before the app runs.** An inline function is opaque to static analysis on purpose; a named, data-described resolver hands the same information to the graph for free. That's the same trade the whole algebra makes — the more you say in data, the more a tool can answer without executing you.

A machine (a stateful node whose next value depends on its current one) is the algebra's canonical process — the surface that motivates the `:process` superkind at all. Its snapshot is durable runtime-db state, written only by its own transitions. Its `:inputs` are the event ids its transition table listens for — every `:on` key across the whole state tree (flat, compound, hierarchical, and parallel regions), de-duplicated; the framework's own reserved triggers (`:rf.machine/*`, the `:*` wildcard) are plumbing, not declared edges. Its `:evaluation` is a *set*: always `:on-transition` (a transition is the only thing that advances a snapshot), plus `:scheduled` when the machine declares any `:after` delayed transition, plus `:on-reply` when it spawns child actors (a spawning parent reacts to its children's reply events). And its *selectors* — how views (your UI functions that render from subscriptions) consume it — are ordinary subscriptions:

```clojure
(rf/reg-sub :upload/progress
  :<- [:rf/machine :upload/main]
  (fn [snapshot _] (get-in snapshot [:data :progress] 0)))
```

That selector's algebra view is an `:ephemeral`, `:on-demand` derivation like any other. It carries the `:machine-selector` refinement and an edge back to the machine it reads, so machines never become a second subscription system. The selector's target is *precise*: the graph mines the machine ids it reads from its static `[:rf/machine …]` inputs, so in a multi-machine app each `:selector` edge runs from exactly the machine the selector names, never the cross product of every machine against every selector. The graph shows one machine process node feeding ordinary derivation nodes ([State machines](concepts/machines.md)).

> **Spawned actors in the live graph.** The static graph reports one node per registered machine *type*. But a [spawned actor](concepts/machines.md) has no per-instance registration — its liveness *is* the presence of its snapshot in runtime-db. So the live graph reports one node per *concrete* snapshot, resolving each instance's type from the snapshot's reserved type discriminator and surfacing its current `:state`. This is the machine version of the parametric/realized split you saw for subscriptions: the static graph knows the types you registered, the live graph knows the instances actually running.

A route lowers the same way. Every route materializes the *same* route fact — `:rf/route`, the one consumer-facing name for the route slice in runtime-db — with the per-route id recorded in `:source-form` and evaluation `:on-route`. Its `:inputs` are the framework route-transition events (`:rf.route/navigate`, `:rf.route/transitioned`, `:rf.route/handle-url-change`), the same across every route, because the same events materialize the slice no matter which route matched. A route's `:resources` declaration becomes a route-owned **activation edge** into the resource it ensures ([Routing: the URL is a sub](concepts/routing.md)):

```clojure
;; one :resources entry → one route-owned activation edge under :resource-edges
{:from   [:runtime [:rf.runtime/routing :current :params]]
 :to     [:resource :article/by-slug]
 :role   :param
 :target :parametric        ;; concrete scoped key needs a live match + scope
 :blocking? true}           ;; transition stays :loading until the resource settles
```

The edge runs from the route's matched params into the resource's params, carries the static `:resource` id (so a tool shows *which* resource the route owns), and surfaces `:blocking?` when declared — whether the transition holds at `:loading` until the resource settles, or proceeds and lets the view render a spinner. The edge's concrete target stays `:parametric` in the static graph — the don't-execute rule again — and the realized owner edge (`[:route route-id nav-token]` → the concrete scoped key) appears only in the live graph, once a navigation has actually committed a match.

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

A fourth thing worth knowing when you read a real graph: **the families are optional, and a missing one just isn't there.** Flows, resources, routes, and machines each live in a separate artefact that core doesn't depend on. An app with no resources contributes no resource nodes; a no-machines app draws no machine processes. The graph you read is the union of whatever families that app actually loaded — so a sparse graph isn't a broken graph, it's an app that uses fewer of the homes. The composer that stitches the per-family views together reaches each one through a contributor seam and simply skips any family that isn't present.

To see one live, open Xray on a running app. The panel that draws the dependency graph renders exactly this assembled view — one node per algebra view, one arrow per edge record — even though the mechanisms underneath are a subscription cache, a flow, a resource cache, a route slice, and a machine snapshot. [Debug with Xray](how-to/debug-with-xray.md) shows the workflow.

## When the graph is wrong: errors as graph facts

Reading a healthy graph is the common case. But the algebra also shapes how the framework reports an *unhealthy* one — and because errors are attributed to **graph identities** rather than to low-level functions, the diagnostics line up with the same node vocabulary you've been reading. When something goes wrong, the framework tells you *which node* and *which axis*, not just which function threw. The cases worth recognizing:

- **Unknown input fact** — a derivation declares an input (`[:sub …]`, `[:db …]`, `[:resource …]`) that names a fact nothing produces. The graph has a dangling edge; the framework fails loud rather than silently feeding `nil`.
- **Cycle in an acyclic graph** — a flow whose inputs depend, transitively, on its own output. Flows are topologically sorted before they drain, so a cycle is a registration-time error, not a hang at runtime. (Subscriptions tolerate some shapes flows can't, because a flow must reach a fixed point inside one commit.)
- **Illegal storage write** — a source form tries to materialize where its storage class forbids. A flow writes app-db only; pointing one at a `:rf.db/runtime` output path is rejected. The storage class isn't decoration — it's an enforced contract.
- **Missing lifecycle owner** — a process with no owner to keep it alive or release it. A graph that shows dependencies but hides ownership is *incomplete*, and the framework treats a missing owner as the error it is.
- **Unresolved resource scope** — a scope resolver that can't produce a key (its `:from-db` source is absent, say). The resource can't form its `[cache-scope resource-id canonical-params]` identity, so it can't cache.
- **Stale reply suppressed** — not an error so much as a *visible non-event*: an async reply arrived for a request a newer navigation or fetch already superseded, and the process dropped it by declared identity. The graph can show you that suppression happened, which is exactly the thing that's invisible (and maddening) when a framework handles it silently.

> **Why this matters for reading apps.** The whole point of the algebra is that a tool can describe your app from declarations alone. That same property is what makes its errors legible: an "unknown input fact" message can name the *fact*, an illegal-write message can name the *storage class*, a missing-owner message can name the *lifecycle*. You debug against the model on this page, not against a stack trace through framework internals. The error vocabulary itself (`:rf.error/*`, `:rf.warning/*`) is catalogued in the [instrumentation spec](../../spec/009-Instrumentation.md); this page is just the map that makes those messages mean something.

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
- tell a static `:parametric` edge from its realized live edges, and know why declaring a dependency as data (a named resolver) buys back static visibility an inline function costs you
- read a sparse graph as "this app uses fewer homes," not "this graph is broken"
- recognize the framework's graph-attributed errors — unknown input fact, cycle, illegal storage write, missing owner, suppressed stale reply — and map each back to the axis it names
- state what a tool may trust about a graph (declared structure, under the don't-execute rule) and what it may not (re-executing nodes)

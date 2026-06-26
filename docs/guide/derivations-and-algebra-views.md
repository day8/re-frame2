# One graph: derivations and their algebra views

You're reading a re-frame2 app you didn't write. A tool has just drawn its dependency graph — subscriptions, a flow, a resource cache, a machine — as one picture, and you want to know what you're looking at. This page is the model that makes the picture legible. It fits in one sentence:

> **A subscription, a flow, a resource, and a machine are the same thing seen four ways** — one node in one dependency graph rooted at your state, distinguished only by *where its value is kept* and *when it's recomputed*.

You don't need this page to *write* an app. [Where should this value live?](where-state-lives.md) routes any value to the right home with four questions and no theory. This page is for *reading* apps — yours six months from now, or somebody else's tomorrow — when the graph a tool draws has to make sense without opening every source file.

## Start with a spreadsheet

The anchor here is a spreadsheet. Every cell is either an entered value or a formula over other cells, and the engine recalculates exactly the cells downstream of an edit. That's the whole idea: a value declares its inputs, and it recomputes when those inputs move.

re-frame2's [subscriptions](concepts/subscriptions.md) are exactly spreadsheet cells. (A subscription is the read side of your app: a *derivation* — a value computed from other values by a pure function, which is all "derivation" means anywhere on this page.) You write a formula; the framework recomputes it when its inputs change; you never recompute it by hand.

> **Coming from Solid / Vue / Preact signals?** The JS world rediscovered the spreadsheet as the **signals graph** — `createMemo`, `computed`, signals — where a derived value declares its inputs and recomputes when those inputs move. A re-frame2 subscription is one of those derived signals. If you've reached for `useMemo` to avoid recomputing a value on every render, you already have the intuition: declare the inputs, let the framework decide when to recompute.

This page adds one bigger claim, and it's the deliberate divergence from the signals world: **flows, resources, route state, and machines are nodes in the same graph.** A flow is a cell that writes its result back into the sheet. A resource is a cell whose authoritative value lives on a server — locally you hold a copy that can go stale. A machine is a cell with memory, where its next value depends on its current one. The signals ecosystem never wrote that unification down as one model; re-frame2 does.

re-frame2 calls this model the **derivation/process algebra**. Don't let "algebra" summon high-school equations — here it just means *a small fixed set of node kinds plus rules for how they combine into a graph*, the same sense in which "relational algebra" is the handful of operations you compose into a SQL query. The payoff is that one vocabulary describes every node. Its normative contract is [`spec/Derivations.md`](../../spec/Derivations.md); this page is the tour.

## The keystone: one function, two policies

Here's the example that makes the whole idea click — a cart total, expressed twice, with the identical formula:

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

`sum-cart` is one whole-value function. Both forms compute the cart total the same way. So what's different? Only the *policy* — where the answer is kept, and when it's recomputed:

| | Subscription | Flow |
|---|---|---|
| Where the value lives | nowhere durable — recomputed on demand | in app-db, at `[:cart :total]` |
| When it recomputes | when something reads it | after each event, in that event's commit |
| Who keeps it alive | a subscription-cache entry | the frame |

The subscription stores nothing and recomputes on demand. The [flow](concepts/flows.md) stores its answer into [app-db](glossary.md#app-db) (your app's single immutable state map) and recomputes after each event. The math is identical; the *policy over the graph* is what differs.

That's the entire reason the algebra exists. Hold onto this one sentence and the rest of the page is just extending it across the other homes:

> The difference between a subscription and a flow is **not the function; it is policy over the same dependency graph.**

> **From re-frame v1.** In v1 you had subscriptions and (later) flows, but they felt like two separate subsystems — a sub was a `reg-sub`, a flow was a thing you bolted on. re-frame2 says out loud what was always true: they are the *same* node under different storage-and-evaluation policy. You still write `reg-sub` and `reg-flow` for the same ergonomic reasons; what's new is that the framework — and any tool reading it — treats them as one graph.

## The five questions every node answers

Every declared fact in re-frame2 — subscription, flow, resource read, route fact, machine selector — answers the *same five questions*. Learn to ask these five of any node and you can read any graph:

| Question | Field | Example answers |
|---|---|---|
| What does it read? | `:inputs` | other subs, app-db paths, resource/machine refs, params, events… |
| What does it produce? | `:output` | an ephemeral fact, or a durable app-db / runtime-db address |
| Where does the value live? | `:storage` | `:ephemeral` · `:app-db` · `:runtime-db` · `:host-transient` |
| When does it run? | `:evaluation` | `:on-demand` · `:after-event` · `:on-reply` · `:on-route` · `:on-transition` · `:scheduled` |
| Who keeps it alive? | `:lifecycle` | a cache entry · the frame · a route · a resource key · a machine instance |

The cart keystone, in this vocabulary: the subscription is `:ephemeral` / `:on-demand` / cache-entry; the flow is `:app-db` / `:after-event` / frame. Same `:inputs`, same math — three policy fields differ. That's the whole trick, restated as a table.

The four `:storage` classes are just the four places a value can sit, and you'll meet each one in context below: **`:ephemeral`** — nowhere durable, recomputed on demand (a subscription); **`:app-db`** — in your app's state map (a flow); **`:runtime-db`** — in the framework-owned partition beside app-db (paths under `:rf.db/runtime`), where route and machine state and the local copies of server data live; and **`:host-transient`** — outside durable state entirely, for things that can't be serialized like an in-flight request handle or a timer. For now just register that the column has four values; the resource and machine sections give each one a home.

That's the whole vocabulary. The five source forms you actually write — `reg-sub`, `reg-flow`, `reg-resource`, `reg-route`, `reg-machine` — each **lower** to this one shape, called the node's **algebra view**. ("Lower" is borrowed from compilers: a higher-level form you wrote is translated down into a simpler, uniform shape a machine can reason about — here, the five questions above.)

> **You never write the algebra view.** There is no `reg-fact` and no `reg-derivation`. The view is *derived* from the registration you already wrote, and that's the point: a tool can answer "where does this value come from, when does it run, where does it live, who owns it?" without reading your function bodies. The source forms stay the things humans write; the algebra view is what a tool reads.

> **Going deeper — two superkinds, nothing more.** "Derivation" and "process" are the two superkinds of the algebra. A **derivation** computes a fact from declared inputs with a pure function — it has no memory beyond its output. A **process** is a derivation that *also* has state, a lifecycle, and commands over time: it reacts to events, async replies, route changes, and timers. Subscriptions and flows are derivations; resources, routes, and machines are processes. The whole hierarchy is exactly those two — a tool that understands only `:derivation` and `:process` can still classify every node in the graph. Everything finer is a *refinement*, and refinements never invent a third superkind.

## One node, opened up

Here's the complete algebra view of that cart subscription — the shape every node in an inspected graph has. Don't memorize it; just see that it's the five questions plus some labelling and diagnostics:

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

Three fields earn a comment:

- **`:kind`** is one of exactly two closed superkinds — `:derivation` or `:process`. A tool that understands only those two can still classify every node.
- **`:refinement`** carries the finer labels you'll meet below (`:resource-process`, `:route-fact`, `:machine-process`, `:machine-selector`). They never live in `:kind`, so a refinement always refines its node's superkind and never invents a third one.
- **`:derive`** is an opaque token. (`#'app.cart/sum-cart` is Clojure's var-quote — a reference *to* the function named `sum-cart`, not the function's source code.) The graph contract is about dependencies, storage, evaluation, and ownership; it never requires serializing your functions.

The trailing fields — `:schema` (the output's Malli [schema](glossary.md#schema)), `:source` (namespace / file / line), plus a doc string — are the **diagnostic dressing**. They're optional; a node with none of them is still a complete node. But a good graph carries them, because "where is this defined and what shape does it produce?" is exactly the question a reader of an unfamiliar app asks first.

### Parametric nodes and the don't-execute rule

So far the subscription's inputs were fixed. But some subscriptions compute their inputs from the [query vector](glossary.md#query-vector) — `[:article/page "welcome"]` reads a different article than `[:article/page "intro"]`. The static graph can't know those edges before a concrete query exists, **and it must not guess**. So it reports `:parametric` and names the producer; the live graph reports the realized edges once a real query is in play:

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

This is the **don't-execute rule**: static inspection never runs your input, param, or scope functions. It reads declarations only — which is what makes a static graph safe to compute anywhere (tests, docs, an editor) with no side effects and no runtime assumptions. A `:parametric` edge set contributes no static edges; the realized ones appear only in the live graph, observed from the running app.

> **Coming from SQL?** Think of `EXPLAIN` on a parameterized query versus the actual rows a given parameter binding returns. The static graph is the query plan — derived from declarations, runnable offline, the same for every parameter. The live graph is the result for a *concrete* binding — one node per realized query vector, edges and all. A `:parametric` node is the framework refusing to guess at plan time what only a real binding can answer.

## Processes: nodes with state and a lifecycle

Subscriptions and flows are derivations — pure formulas. Now we cross into **processes**: nodes that carry state, have a lifecycle, and run commands over time. The first one is the **resource**.

A resource is a fact whose authoritative value lives on a server, with a local cached copy ([Server state: resources](concepts/server-state.md)). Here's the wrinkle that makes resources more than a fancy subscription: one `reg-resource` declaration lowers to *more than one* node — a process node for the cache entry, plus its read selectors (`:rf.resource/state`, `:rf.resource/data`, `:rf.resource/loading?`, …), each an ordinary on-demand derivation over that entry. Reading a selector never starts work; it just reads the cache.

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
 :evaluation  #{:on-route :on-reply :scheduled :manual}   ;; a set (#{…}) — this process has many triggers
 :lifecycle   :scoped-resource-key
 :materialized? true
 :selectors   [:rf.resource/state :rf.resource/data :rf.resource/status
               :rf.resource/loading? :rf.resource/error :rf.resource/has-data?]}
```

The view makes a split explicit that folklore usually muddles. **`:storage` always names the local home** — here, the runtime-db cache entry. **`:authority` names whose fact it really is** — an external server.

> **Gotcha — "remote" is not a storage class.** This is the one place people reliably tie themselves in knots. Locally you *always* hold a representation; the truth living on a server doesn't move it out of your `:storage`. So `:storage` is `:runtime-db` (where your copy sits) and `:authority` is the remote server (whose fact it is) — two separate axes, never collapsed into one. A graph that printed "storage: remote" would be telling you nothing about where the bytes actually are.

> **Coming from TanStack Query?** Your query cache is exactly this split, just unnamed. The server is the `:authority`; the `queryCache` is the local `:storage`; `isLoading` / `data` / `error` are read selectors over one cache entry; and the staleness/refetch policy is the entry's `:evaluation`. re-frame2 writes those four facts down as fields instead of leaving them implicit in a hook's behaviour — which is the whole reason a tool can draw a resource the same way it draws a subscription.

> **Going deeper — where the non-serializable leftovers go.** An in-flight request handle or a timer can't be serialized into durable state, so they're classed `:host-transient`: outside durable frame state, torn down at their lifecycle boundary. They never become the *only* copy of a fact — replay and restore depend on the durable `:runtime-db` entry, not the live socket. In a live resource view you'll see them surface as `:host-transient [[:rf.http/in-flight :work/id-123]]` and a `:work-ledger` summary (the in-flight attempt's identity, owners, causes, and transport) — present only while a fetch is actually in flight, gone once it settles.

### Buying back static visibility: named resolvers

The don't-execute rule says static inspection won't *run* your scope function — so a resource scoped by an inline `(fn [route ctx] …)` reports the opaque marker `[:scope :rf.scope/resolver]` and nothing more. But there's an escape hatch that buys back static visibility: a **named scope resolver**. Declare the scope as a data reference — `{:from-db :session/current-tenant}` instead of an inline function — and the resolver's *declared inputs* are themselves declarations, so the static graph can read them without running anything:

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

The lesson generalizes, and it's the trade the whole algebra makes:

> **Declaring a dependency as *data*, rather than burying it in a function body, is what lets a tool see it before the app runs.** An inline function is opaque to static analysis on purpose; a named, data-described resolver hands the same information to the graph for free. The more you say in data, the more a tool can answer without executing you.

### Machines: a node with memory

A [machine](../machines/glossary.md#machine) is the algebra's canonical process — a stateful node whose *next* value depends on its *current* one. It's the feature that motivates the `:process` superkind in the first place: a pure derivation can't depend on its own previous value, so a machine simply isn't expressible as one. Its [snapshot](../machines/glossary.md#snapshot) is durable runtime-db state, written only by its own transitions.

The view, side by side with one of its selectors:

```clojure
;; STATIC ALGEBRA VIEW of (rf/reg-machine :checkout/main {…})
{:id          :checkout/main
 :kind        :process
 :refinement  :machine-process
 :inputs      [[:event :checkout/submit]      ;; the :on event keys across the state tree
               [:event :checkout/succeeded]
               [:event :checkout/failed]]
 :storage     :runtime-db
 :evaluation  #{:on-transition}              ;; + :scheduled if it has :after; + :on-reply if it spawns
 :lifecycle   :machine-instance}

;; A selector — how a view reads the machine — is an ordinary subscription.
(rf/reg-sub :checkout/progress
  :<- [:rf/machine :checkout/main]
  (fn [snapshot _] (get-in snapshot [:data :progress] 0)))
```

A machine's `:inputs` are the event ids its transition table listens for — every `:on` key across the whole state tree (flat, compound, hierarchical, and parallel regions), de-duplicated. (The framework's own reserved triggers — `:rf.machine/*`, the `:*` wildcard — are plumbing, not declared edges.) Its `:evaluation` is a *set*: always `:on-transition` (a transition is the only thing that advances a snapshot), plus `:scheduled` when the machine declares any `:after` delayed transition, plus `:on-reply` when it spawns child actors.

That `:checkout/progress` selector's algebra view is an `:ephemeral`, `:on-demand` derivation like any other. It carries the `:machine-selector` refinement and an edge back to the machine it reads — so machines never become a second subscription system. And the selector's target is *precise*: the graph mines the machine ids it reads from its static `[:rf/machine …]` inputs, so in a multi-machine app each `:selector` edge runs from exactly the machine the selector names, never the cross product of every machine against every selector ([State machines](../machines/concepts.md)).

> **Going deeper — spawned actors in the live graph.** The static graph reports one node per registered machine *type*. But a spawned actor has no per-instance registration — its liveness *is* the presence of its snapshot in runtime-db. So the live graph reports one node per *concrete* snapshot, resolving each instance's type from the snapshot's reserved type discriminator and surfacing its current `:state`. This is the machine version of the parametric/realized split you saw for subscriptions: the static graph knows the *types* you registered, the live graph knows the *instances* actually running.

### Routes: the same fact, every route

A route lowers the same way, with a twist worth pausing on: every route materializes the *same* route fact — `:rf/route`, the one consumer-facing name for the route slice in runtime-db — with the per-route id recorded in `:source-form` and evaluation `:on-route`. Its `:inputs` are the framework route-transition events (`:rf.route/navigate`, `:rf.route/transitioned`, `:rf.route/handle-url-change`), the same across every route, because the same events materialize the slice no matter which route matched. Forty routes, one fact.

A route's `:resources` declaration becomes a route-owned **activation edge** into the resource it ensures ([Routing: the URL is a sub](concepts/routing.md)):

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

Second, **redaction preserves structure**. Graph payloads carry source coordinates and value *summaries*, never raw sensitive values, so a redacted param is still an edge and connectivity survives even when content is hidden. ([Data classification](glossary.md#data-classification) is the mechanism.)

Third, **the whole-value law**: every derivation must be correct as a function that recomputes its entire output from its declared inputs. Memoization, equality pruning, and (someday) deltas are optimizations that must not change the observed value. What you trust in a graph is the *structure* — who reads what, where it lives, when it runs — never a promise that any node can be re-executed on demand.

And a fourth thing worth knowing when you read a *real* graph: **the families are optional, and a missing one just isn't there.** Flows, resources, routes, and machines each live in a separate artefact that core doesn't depend on. An app with no resources contributes no resource nodes; a no-machines app draws no machine processes. The graph you read is the union of whatever families that app actually loaded — so a sparse graph isn't a broken graph, it's an app that uses fewer of the homes.

> **Going deeper — the whole-value law as a contract.** The law is what lets conformance tests verify a node by recomputing it, and lets a tool trust declared edges and classifications without executing your app code. It also pins down what optimizations are *allowed*: anything that preserves the observable value (memoization, equality pruning, dirty checks, deltas) is legal; anything that changes it is a bug, not a feature. The composer that stitches the per-family views together reaches each family through a contributor seam and simply skips any family that isn't present — which is why the graph degrades gracefully to whatever the app loaded.

To see one live, open [Xray](glossary.md#xray) on a running app. The panel that draws the dependency graph renders exactly this assembled view — one node per algebra view, one arrow per edge record — even though the mechanisms underneath are a subscription cache, a flow, a resource cache, a route slice, and a machine snapshot. [Debug with Xray](how-to/debug-with-xray.md) shows the workflow.

## When the graph is wrong: errors as graph facts

Reading a healthy graph is the common case. But the algebra also shapes how the framework reports an *unhealthy* one — and because errors are attributed to **graph identities** rather than to low-level functions, the diagnostics line up with the same node vocabulary you've been reading. When something goes wrong, the framework tells you *which node* and *which axis*, not just which function threw. The cases worth recognizing:

- **Unknown input fact** — a derivation declares an input (`[:sub …]`, `[:db …]`, `[:resource …]`) that names a fact nothing produces. The graph has a dangling edge; the framework [fails loud](glossary.md#fail-loud-not-silent) rather than silently feeding `nil`.
- **Cycle in an acyclic graph** — a flow whose inputs depend, transitively, on its own output. Flows are topologically sorted before they drain, so a cycle is a registration-time error (`:rf.error/flow-cycle`, thrown by `reg-flow` *before* any snapshot is created), not a hang at runtime. The error even carries the offending chain — a `:cycle` vector with a closing repeat, `[:a :b :a]` for `:a → :b → :a` — which a tool like Xray renders verbatim. (Subscriptions tolerate some shapes flows can't, because a flow must reach a fixed point inside one commit; the subscription graph stays dynamic at the view boundary and has no registration-time cycle gate.)
- **Illegal storage write** — a source form tries to materialize where its storage class forbids. A flow writes app-db only; pointing one at a `:rf.db/runtime` output path is rejected. The storage class isn't decoration — it's an enforced contract.
- **Missing lifecycle owner** — a process with no owner to keep it alive or release it. A graph that shows dependencies but hides ownership is *incomplete*, and the framework treats a missing owner as the error it is.
- **Unresolved resource scope** — a scope resolver that can't produce a key (its `:from-db` source is absent, say), surfaced as `:rf.error/resource-sub-unresolved-scope`. The resource can't form its `[cache-scope resource-id canonical-params]` identity, so it can't cache — and a [scope](glossary.md#scope) that can't resolve raises rather than serving the wrong principal's data. (A resource registered with no scope policy at all is the sibling `:rf.error/resource-missing-scope-policy`.)
- **Stale reply suppressed** — not an error so much as a *visible non-event*: an async reply arrived for a request a newer navigation or fetch already superseded, and the process dropped it by declared identity. The graph can show you that suppression happened — which is exactly the thing that's invisible (and maddening) when a framework swallows it silently.

> **Why this matters for reading apps.** The whole point of the algebra is that a tool can describe your app from declarations alone. That same property is what makes its errors legible: an "unknown input fact" message can name the *fact*, an illegal-write message can name the *storage class*, a missing-owner message can name the *lifecycle*. You debug against the model on this page, not against a stack trace through framework internals. The error vocabulary itself (`:rf.error/*`, `:rf.warning/*`) is catalogued in the [instrumentation spec](../../spec/009-Instrumentation.md); this page is just the map that makes those messages mean something.

> **No public graph-accessor yet (pre-alpha).** The assembled shape is consumed internally — by Xray and the conformance fixtures — and a public name ships only once the shape survives real use. What you can rely on today is the model: the classifications on this page are normative, and they are what the tools render.

## Advanced

Everything above is what you need to *read* a graph. Two things sit one level deeper — the seam that lets families plug in, and a law about deltas that the framework states but does not yet execute. You can skip this section and lose nothing about reading apps.

### The contributor seam: how the optional families plug in

The fourth rule above said a missing family just isn't there. The mechanism is worth a sentence for anyone wiring up tooling. Core can't `:require` flows, resources, routing, or machines — that would defeat their [bundle isolation](glossary.md#elide) and break a core-only build. So the composer reaches each optional family through a **contributor seam**: a `{family {:static-fn :live-fn …}}` map. On the JVM the default contributors auto-resolve whatever siblings are on the classpath; in the browser the tool that draws the graph (which already requires the families it has) supplies the map. A family whose artefact is absent contributes nothing — which is the same "no-flows app draws no flow nodes" story, just told from the composer's side. The composer is itself bundle-isolated and exports no public accessor; it's the internal shape the deferred public name will one day produce.

### The optional delta law

The whole-value law says every derivation must be correct as a function that recomputes its *entire* output. That's the floor, and today it's also the ceiling: re-frame2 ships **no executable delta protocol**. But the spec writes down the law a delta path *would* have to obey, so that if one ever lands it can't quietly change observed values.

The idea: a derivation could one day declare a `:step-delta` companion beside its `:derive`, computing an *output* delta from an *input* delta instead of rebuilding the whole value — the optimization you'd want for, say, a hundred-thousand-row grid where one cell changed.

```clojure
{:id         :large-grid/visible-rows
 :derive     #'app.grid/visible-rows        ;; whole-value: the floor, always correct
 :step-delta #'app.grid/visible-rows-delta} ;; delta: an optional fast path
```

The binding rule is that the delta path must **commute** with whole-value recomputation — applying the input delta then deriving must equal deriving then applying the output delta. If a `:step-delta` is absent, disabled, or fails its conformance check, whole-value derivation remains correct; the delta is never load-bearing for correctness, only for speed. This is why the error list above has a quiet seventh member the healthy-reading cases never hit: **delta law check failed** — a declared `:step-delta` that disagreed with whole-value recompute, caught by conformance rather than shipped. Until a real performance case needs it, the practical takeaway is simply: trust the whole-value value; the delta is a deferred optimization with a law already waiting for it.

## The rule worth carrying

You don't memorize the tables. You carry the sentence the keystone example proved:

> A subscription, a flow, a resource, a route fact, and a machine are the same dependency-graph node under different **storage** and **evaluation** policies. The source forms differ for good ergonomic reasons; the algebra view is what they share.

When you want the full normative contract — the node schema, every classification rule, the static/live modes, the whole-value law — read [`spec/Derivations.md`](../../spec/Derivations.md). When you just want to pick a home for a value, ask the four questions in [Where should this value live?](where-state-lives.md). This page is the bridge between them: *why* the four homes are four faces of one idea.

# EP-0037: Route Planning and Activation Ownership

Status: accepted
Type: standards-track
Created: 2026-07-24

> This EP makes routing one inspectable planning boundary. A route address is
> resolved once into a target, an active branch, managed resource requirements,
> and navigation policy; every navigation door executes that plan. Routing owns
> navigation and resource activation, but it does not own views, application
> data, a second cache, or the asynchronous tail of arbitrary events. On
> graduation the public contracts live in Specs 011, 012, 016,
> `Spec-Schemas.md`, and their instrumentation/API indexes; this EP remains the
> design record.

## Abstract

re-frame2 routing plans where the frame is going and which managed reads that
destination requires. It does not become a React-shaped application framework.
Views remain derivative projections of frame state, events remain the causal
boundary, and the Resources artefact remains the owner of remote-read identity,
work, caching, freshness, cancellation, and errors.

This EP defines one route-planning pipeline shared by links, programmatic
navigation, Back/Forward, initial load, and SSR. It introduces one canonical
plain-map `RouteAddress`, composes route `:resources` from the active
parent-to-leaf branch, adds resource-only intent prefetch, and makes route
readiness an honest projection of managed blocking resources.

It also removes contracts that obscure ownership. `:on-match` becomes explicitly
fire-and-forget activation work rather than a loader whose arbitrary asynchronous
tail the router pretends to await. Entry policy remains declarative but becomes a
terminal allow/deny decision rather than a resumable navigation. Cross-route
query carry becomes explicit at the call site; destination-local query schemas
and defaults and in-place query edits remain.

The result is deliberately smaller than the feature sets of full-stack routers:
no outlet runtime, component loader data, route-owned cache, route actions,
generic search middleware, file-route authority, or prefetch mode matrix. The
useful ideas are adopted at re-frame2's existing seams instead of importing
another framework's ownership model.

## Motivation

The existing router has the right foundation: routes are registry data,
navigation is an event, the active route is frame state, URLs are derivable, and
SSR uses the same event/runtime model. Several later additions, however, now pull
that foundation in different directions:

- `route-link`, `route-url`, navigation, redirects, and future prefetch all
  describe the same destination but do not yet share a named, schema-backed
  address value.
- `:parent` describes the active layout branch, while route resources are planned
  only from the leaf. Shared shell reads are therefore duplicated in children or
  hidden behind application helpers that tooling cannot see.
- `:on-match` dispatches ordinary events, but route transition/error machinery
  tries to treat those events as awaited loaders. A synchronous drain can observe
  that an event handler ran; it cannot own or reliably correlate the later HTTP,
  timer, callback, or application event graph that handler starts.
- both leave and entry guards currently use one resumable pending-navigation
  protocol. Leaving a dirty form genuinely requires pause, UI, and a user
  decision. Entry authorization normally requires an immediate allow or deny,
  followed, if appropriate, by a fresh redirect/navigation after application
  state changes.
- `:query-retain` changes a destination URL using ambient current-route state
  that is absent from the navigation request. The final address is therefore
  harder for a programmer, test, trace, or AI to infer from the value being
  reviewed.
- the Resources artefact already has ownerless, cause-only `ensure`, but the
  router has no route-plan verb that can warm all destination resources without
  duplicating the plan at every link.

These are not independent conveniences. They are symptoms of one unclear
boundary: whether the router merely plans a transition or owns all work and UI
associated with a page. A bead-sized patch to any one symptom would preserve
contradictory answers elsewhere. The decision warrants one programme EP because
the route request grammar, guard protocol, transition state, resource ownership,
SSR wait point, schemas, traces, tests, and guides must converge on the same
answer.

## Specification

### Scope and authority

This EP owns the route-planning and activation boundary:

```text
navigation intent
  -> validated address or in-place edit
  -> resolved target and active branch
  -> leave/entry decisions
  -> effective managed-resource plan
  -> route/URL commit and activation effects
```

It covers the public values and runtime semantics needed to make that boundary
one path across browser navigation, programmatic navigation, initial load, and
SSR. It does not own route pattern syntax or ranking, resource cache semantics,
HTTP transport, view rendering, application mutations, forms, head emission, or
the host history implementation except where their existing owners consume the
plan.

The canonical contract homes on graduation are:

| Home | Contract owned there |
|---|---|
| `spec/012-Routing.md` | address/request grammar, the one planning path, activation events, guards, readiness projection, prefetch, query policy, link behavior |
| `spec/016-Resources.md` | branch-plan composition, identity dedupe, owner handoff, warm ensures, blocking-resource status |
| `spec/011-SSR.md` | initial/SSR use of the same plan, blocking wait, denied-entry behavior, hydration |
| `spec/Spec-Schemas.md` | `:rf/route-address`, the address/raw-URL destination union, navigation request, route metadata, route read shape, leave-only pending navigation |
| `spec/009-Instrumentation.md` | plan, prefetch, denial, planning-failure, and stale-suppression traces/errors |
| `spec/API.md`, `spec/Ownership.md`, and relevant indexes | inventory and ownership references only; never a second definition |

### Governing laws

1. **Views are derivative projections.** Rendering may read props, local pure
   values, and subscriptions. It does not fetch, ensure a resource, navigate, or
   dispatch merely because rendering occurred. A user or host interaction may
   dispatch an event; render itself is not a causal boundary.
2. **The router plans; existing owners execute.** Routing owns target resolution,
   branch selection, navigation decisions, resource requirements, and commit
   order. Events own application state changes. Resources own managed remote
   reads. Views project the resulting state.
3. **One intent takes one path.** Link clicks, `:rf.route/navigate`,
   Back/Forward, initial load, and SSR differ in cause and history/scroll policy,
   not in target, entry, resource, or readiness semantics.
4. **One destination has one data shape.** Registered destinations use the same
   `RouteAddress` fields wherever an address is stored, printed, linked,
   navigated to, denied, returned to, or prefetched.
5. **The active branch contributes requirements, not rendering.** Declaring
   `:parent` opts the child into the parent's branch-wide managed resources.
   Parent routes do not acquire an outlet, component, context-provider, or
   implicit view lifecycle.
6. **Only managed work affects managed readiness.** Arbitrary events may start
   arbitrary work, but only work whose lifecycle the runtime actually owns may
   make route readiness loading or error.
7. **Leaving may pause; entering decides.** `:can-leave` may create durable
   pending state for confirmation. `:can-enter` terminates the current attempt
   with allow or deny; a redirect or post-login return is a new navigation.
8. **Address transformations are visible.** Destination-local validation and
   defaults are declarative route facts. Carrying current URL state to a
   different route is an explicit application operation.
9. **Correctness does not depend on cancellation.** Nav tokens, resource
   generations, and current-plan identity suppress stale completion.
   Cancellation remains an optimisation.
10. **Start with the smallest powerful surface.** Parent resource composition,
    intent prefetch, and terminal entry are included. Generic metadata
    inheritance, route caches, render/viewport prefetch modes, timing knobs,
    action/form protocols, and code-loading policy are not.

### Terms

- **`RouteAddress`** is caller-authored intent for one registered named
  destination: the closed `{:to :params :query :fragment}` map. Policy, link
  props, and in-place edits are never part of this value.
- **`RouteDestination`** is the closed replay union of a `RouteAddress` and the
  raw-URL escape. Pending-leave and entry-denial payloads use it when they must
  preserve intent that may not have a named route spelling. It never absorbs
  navigation policy; a pending leave stores explicit policy beside it.
- **`ResolvedTarget`** is planner output: canonical route id, params, query,
  fragment, and URL after matching, defaults, and validation. It is fact, not
  another accepted input spelling.

### Canonical `RouteAddress`

A registered destination is represented by one closed, ordinary EDN map:

```clojure
{:to       :route/article
 :params   {:slug "routing-as-data"}
 :query    {:tab :comments}
 :fragment "reply-42"}
```

The public schema id is `:rf/route-address`:

```clojure
[:map {:closed true}
 [:to :keyword]
 [:params   {:optional true} :map]
 [:query    {:optional true} :map]
 [:fragment {:optional true} [:maybe :string]]]
```

`:to` is required. Omitted `:params` and `:query` normalize to `{}`; an omitted
or `nil` `:fragment` normalizes to no fragment. Route param/query validation,
canonical EDN identity, query defaults, URL encoding, and not-found behavior
continue to be owned by Spec 012. Unknown address keys fail at the authoring or
event boundary in every build.

`RouteAddress` is deliberately a map plus one schema. There is no record,
constructor hierarchy, fluent builder, relative-address language, or
type-specific redirect object. Applications may name address constants and pure
functions using ordinary Clojure values.

The same address fields are consumed by:

- `rf.routing/route-url`;
- `rf/route-link` and `v/route-link` (as control fields inside the larger props
  map);
- the destination branch of `:rf.route/navigate`;
- the named destination carried by entry-denied and pending-leave data;
- application redirect/return-to state; and
- `:rf.route/prefetch`.

Public call sites remain flat:

```clojure
(rf/dispatch
  [:rf.route/navigate
   {:to :route/article
    :params {:slug slug}
    :replace? true}])

[v/route-link
 {:to :route/article
  :params {:slug slug}
  :prefetch :intent
  :class "article-link"}
 title]
```

The first map contains an address plus navigation policy. The second contains an
address plus link behavior and ordinary DOM attributes. Implementations extract
and validate the address through the same code; they do not pretend the whole
map is an address. A nested public envelope such as
`{:address {...} :policy {...}}` is not introduced solely to encode this
conceptual separation.

#### Normative extraction from flat public maps

The shared extractor operates on closed key classes:

```clojure
address keys       #{:to :params :query :fragment}
policy keys        #{:replace? :scroll :bypass-leave?}
edit keys          #{:query :query-merge :fragment}
link behavior keys #{:prefetch}
```

Presence chooses the request branch before validation:

1. When `:to` is present, the extractor selects exactly the address keys and
   validates that extracted map against `:rf/route-address`. `:query` and
   `:fragment` are destination facts in this branch.
2. When `:url` is present, the extractor selects the raw destination and
   validates it against the raw arm of `:rf/route-destination`. `:to`,
   `:params`, `:query`, and `:query-merge` are forbidden beside it; an explicit
   `:fragment` overrides the URL-embedded fragment under the existing rule.
3. When neither `:to` nor `:url` is present, `:query`, `:query-merge`, and
   `:fragment` are in-place edits. They are never validated or stored as a
   `RouteAddress`. A `:params` key in this branch retains the existing named
   `:rf.error/navigate-bad-request` reason `:params-requires-destination`;
   changing path params always requires a destination.

Policy keys are extracted and validated separately. Closed control-map
boundaries such as navigation, URL generation, prefetch, and stored-destination
replay validate their whole accepted-key roster before extraction, so
extraction cannot silently discard a misspelled address, policy, or edit key.

`route-link` is deliberately different because its props map is also an open
DOM-attribute map. It validates the recognized routing controls, including
`:prefetch`, strips the exact address and behavior keys before DOM emission, and
passes the remaining attributes and children to the view substrate. It does not
pretend that routing can distinguish every misspelled control from a
caller-authored DOM attribute; host/view DOM diagnostics own unknown attributes.
A caller's `:on-click` and ordinary anchor attributes are view props, not
destination or routing-policy keys. Thus the schema is closed over the
extracted address, not over the convenient flat props map accepted by a link.

#### Raw URL escape

`{:url "/partner/supplied/path"}` remains a stringly escape accepted by
navigation doors that must handle an already-authored URL. It is not a
`RouteAddress`, cannot be combined with `:params` or `:query`, and is not accepted
by `route-url` or the first prefetch surface. If it matches a registered route,
the planner immediately produces the same resolved target and canonical named
address it would have produced from `:to`. If it does not, the existing
same-origin/not-found rules apply.

Where runtime state must replay either form, `RouteDestination` (schema id
`:rf/route-destination`) means the closed union of `RouteAddress` and a raw map
with required string `:url` plus optional string-or-nil `:fragment`. A matching
raw URL normalizes to the named branch; only a destination that cannot be
reified without changing the requested URL remains raw. This union is used in
pending/denial payloads so continuing a dirty-form navigation to an unmatched
in-app URL still preserves that URL. It does not give `route-url` or prefetch a
second spelling.

This distinction keeps the common value enumerable and refactorable without
removing deep-link and integration use cases. A plain external or intentionally
browser-owned link remains an ordinary anchor.

#### Navigation policy and in-place edits

Navigation policy is not destination identity:

```clojure
{:replace?      true
 :scroll        :preserve
 :bypass-leave? true}
```

The proposed policy keys are `:replace?`, `:scroll`, and the leave-only
`:bypass-leave?` recommended in Open Issue 3. The current set-valued
`:bypass-guards? #{:leave :enter}` is retired: entry has no resumable/bypass
protocol, and a set is needless machinery for the one remaining case.

Existing route metadata `:scroll` is unchanged. A per-navigation `:scroll`
policy value remains its explicit override; parent resource composition does
not inherit or merge route scroll metadata.

In-place edits remain a separate branch of `:rf.route/navigate`:

```clojure
[:rf.route/navigate {:query-merge {:page 2 :sort nil}}]
[:rf.route/navigate {:query {:tab "history"}}]
[:rf.route/navigate {:fragment "errors"}]
```

They patch the current resolved location under the existing presence-sensitive
rules. `:query-merge` is an operation, not part of `RouteAddress`, and remains
in-place only. The address, policy, and edit key rosters are documented
together, but their schemas and internal functions stay distinct so a future
feature cannot accidentally make an edit serializable as a destination.

### Resolved target and route plan

The address is caller intent. Planning produces resolved facts:

```clojure
{:route-id :route/article
 :params   {:slug "routing-as-data"}
 :query    {:tab :comments}
 :fragment "reply-42"
 :url      "/articles/routing-as-data?tab=comments#reply-42"}
```

This is a `ResolvedTarget`, not another accepted input spelling. Facts say
`:route-id`; intent says `:to`. It is passed to guard subscriptions, recorded in
diagnostics, and used to derive the parent chain.

An internal route plan is plain data containing at least:

- the source address or raw-URL request;
- the resolved target;
- the cause (`:link`, `:navigate`, `:popstate`, `:initial`, or `:ssr`);
- the parent-to-leaf branch;
- the effective resource requirements and their contributors;
- the transition kind (`:full`, `:fragment-only`, or `:no-op`);
- history and scroll policy; and
- the old/new resource identity diff needed for ownership handoff.

Transition kind is derived from resolved facts, not from the request spelling:

- **full** when there is no current target or `:route-id`, `:params`, or
  `:query` differs;
- **fragment-only** when those three facts are equal and only `:fragment`
  differs; and
- **no-op** when all four facts are equal.

Consequently, a changed in-place `:query` or `:query-merge` request is a full,
data-bearing activation. “In-place” describes how caller intent is resolved; it
does not create a weaker activation or guard path.

This EP does not require a public `RoutePlan` constructor or promise-returning
router object. The plan is an implementation and tooling seam. Its observable
laws are public; its diagnostic projection is visible in trace/Xray.

### One planning pipeline for every door

Every navigation door must lower to the same ordered stages:

| Stage | Result | Failure/short-circuit |
|---|---|---|
| 1. Validate intent | accepted address/raw URL/in-place edit plus policy | malformed request rejects before any guard or history effect |
| 2. Resolve target | canonical target, URL, named address when possible, parent branch | schema/URL failures follow the existing not-found or caller-error contract |
| 3. Classify transition | full, fragment-only, or no-op | no-op terminates before guards, pending state, history, scroll, or activation |
| 4. Decide leave | allow, or a leave-only pending navigation | full and fragment-only transitions consult the current route; pending leave preserves it and popstate restores its URL |
| 5. Decide entry | allow, or terminal entry denial | full and fragment-only transitions consult the target route; denial commits no target and creates no pending entry |
| 6. Build activation plan | for a full transition, effective branch resource plan and old/new identity diff | fragment-only skips activation planning; planning failure produces a failed plan and no partial resource plan executes |
| 7. Commit and activate | route facts/plan ownership, history/scroll effects, resource ensures, activation events | stale completions remain fenced by token/generation |

Guard coverage is therefore explicit. An exact no-op evaluates neither guard:
nothing is being left or entered, and a redundant request cannot create pending
state. A full transition evaluates `:can-leave` and then `:can-enter`, including
a same-route activation whose canonical params or query changed. Query is
data-bearing and may change resource identity or discard route-local workflow,
so the in-place request form is not an implicit guard bypass; a route that wants
to allow a safe subset of query edits can compare the resolved target in its
guard subscription. A fragment-only transition continues to evaluate both
guards, preserving Spec 012's existing fragment-check contract; it skips
resource planning and activation only after they allow it. Initial load has no
current leave guard but evaluates target entry policy normally.

The programmatic, link, URL-change, initial-load, and SSR handlers may remain
separate public events for cause-specific tests and host integration. They must
call the same resolver, decisions, planner, and commit assembler. No door may
reimplement guard coverage or leaf-only resource planning.

The existing state-first ordering remains: the frame's committed route facts and
next plan ownership are established before a client history effect and before
activation events. For a URL-driven door the browser has already moved, so no
push occurs. Scroll remains cause/policy-specific. A blocked or denied popstate
restores the current route's URL by replace. Initial load and SSR use the same
decision and planning path without browser effects.

A resource-planning failure is a committed **failed activation**, not a return to
the prior page: the target and URL commit, readiness projects `:error`, and no
resource ensure from the invalid plan runs. The target's `:on-match` events do
**not** run. Committing target facts makes the failure addressable and gives
client and SSR error views stable route context; it is not a successful
activation boundary for analytics, host notification, seeding, or other
application work. The planning error and trace are the causal facts an
application observes. A retry is a fresh navigation and dispatches `:on-match`
only after its plan forms successfully.

Plan execution is atomic at this boundary. A failed plan contributes an empty
next ownership set: after the failed target/error facts are installed, every
owner held only by the previous route plan is released, no partially planned
next owner is attached, and no partial ensure is dispatched. Keeping previous
route owners after committing a different failed target would leak liveness and
misrepresent the active plan.

A full activation allocates a fresh nav-token. A fragment-only transition and a
no-op do not. Prefetch is not activation and allocates none.

### Effective parent-chain resource plans

For an allowed full activation, the planner follows `:parent` from the matched
leaf and obtains the existing root-most-first route chain. It then composes only
the `:resources` declarations from each contributor, parent to leaf:

```clojure
(rf/reg-route :route/account
  {:resources
   [{:id :viewer
     :resource :account/viewer
     :scope {:from-db :app/session}
     :blocking? true}]}
  "/account")

(rf/reg-route :route/account.settings
  {:parent :route/account
   :resources
   [{:id :settings
     :resource :account/settings
     :scope {:from-db :app/session}
     :blocking? true}]}
  "/account/settings")
```

Activating `:route/account.settings` plans both requirements. The parent declares
shared shell data once; the child declares leaf data once. Views still render the
route chain with ordinary data composition. `:on-match`, `:scroll`, `:head`,
`:tags`, guards, and arbitrary metadata are not generically inherited or merged
by this feature.

This inheritance is deliberate and automatic. `:parent` already declares that
the child participates in the parent's active branch; it is therefore the
opt-in to the parent's branch-wide resource requirements. A second
`:inherit-resources? true` marker on every child would duplicate that fact,
permit an omitted marker to make SSR or prefetch silently incomplete, and grow
more fragile with each descendant level. A read needed by only one leaf belongs
on that leaf, not on its parent. This is a pre-alpha semantic expansion for
existing `:parent` users and requires an explicit acceptance ruling (Open Issue
4), not an accidental inference from implementation.

Planning follows these rules:

1. Resolve the branch parent first. Existing unknown-parent and parent-cycle
   errors remain fail-loud.
2. Within each contributor, validate local `:id` / `:after` edges exactly as
   Spec 016 requires, against the whole declared vector before `:when` filters
   occurrences. An `:after` edge may name only an id declared by the same route;
   a missing target or local cycle fails the plan. Parent and child local ids
   never share a namespace. Declaration order is the stable tie-breaker among
   otherwise-ready occurrences.
3. Evaluate `:when`, then resolve params and scope against the target/frame and
   validate them. Every contributor's route function receives the resolved
   **leaf target** as its `route` argument; the planner records the contributor
   separately. The leaf target is the one canonical, fully resolved address for
   this activation and carries the path/query facts available to the whole
   branch; synthesising a second ancestor-shaped target would create competing
   target truths. A failure aborts the whole resource plan before any ensure is
   dispatched, then commits the failed activation described above. The error
   identifies both the contributor route and resource declaration.
4. Materialize a requirement containing contributor route id, local id,
   declaration index, resolved scoped resource identity, `:blocking?`, and
   `:keep-previous?`.
5. Form an occurrence graph over materialized requirements. It contains the
   admitted local `:after` edges and the branch-order constraint that every
   ancestor contributor precedes every descendant contributor. Then collapse
   occurrences by canonical scoped resource identity: union each identity
   group's incoming/outgoing edges, discard edges whose two ends collapsed into
   the same group, and stable-topologically order the grouped graph. Dispatch
   one `ensure` per identity. This is identity dedupe, not a generic
   route-metadata merge.
6. If identity collapse makes the grouped graph cyclic, fail the whole plan with
   the canonical `:rf.error/resource-route-plan` error and dispatch no ensures.
   The diagnostic names the cyclic identity groups and contributor/local ids.
   Silently dropping an `:after` or branch-order edge to keep an arbitrary
   occurrence is forbidden.

The occurrence graph and identity collapse are internal planning mechanics. A
collapse-created cycle is only a resource-plan failure; it does not introduce a
public graph API, a public graph value, or a new router abstraction.

When more than one requirement resolves to the same identity:

- the grouped graph fixes dispatch position; the earliest
  parent-to-leaf/declaration occurrence is only the stable tie-breaker when no
  dependency constrains the group;
- the work is blocking when any contributor marks it blocking;
- `:keep-previous?` is true when any contributor requests it; and
- every contributor remains present in the diagnostic plan, so dedupe does not
  erase why the resource is needed.

For example, if `B` declares `:after #{A}`, `C` declares `:after #{B}`, and
`A` and `C` resolve to the same identity, collapse produces both
`identity(A/C) -> B` and `B -> identity(A/C)`. The plan fails. Dispatching the
identity at `A` and forgetting `C`'s edge would lie about `:after`.

When a child redundantly declares an identity already contributed by an
ancestor, the existing `:rf.resource/route-plan` trace carries an advisory that
names both declarations. Dedupe remains valid and the plan remains executable
when its grouped graph is acyclic; the advisory merely makes the redundant
copy mechanically discoverable so the child declaration can be removed.

These are the only cross-requirement combination rules. Other metadata does not
gain an implicit merge policy.

`:after` remains ensure-dispatch ordering, not a data waterfall. Every params,
scope, and condition function is evaluated synchronously while the plan is
formed; a child requirement cannot read data that a parent requirement has not
loaded yet.

#### Plan diff and owner handoff

Activation compares the old and next sets of scoped resource identities:

- **kept** identities remain owned and are not re-ensured solely because a leaf
  sibling changed;
- **added** identities acquire the next route-plan owner and run the ordinary
  resource ensure/freshness path; and
- **removed** identities release the prior route-plan owner after the next
  ownership set is attached.

Attach-before-release is required. An identity needed by both plans must never
pass through an ownerless instant that aborts useful in-flight work or makes the
entry GC-eligible. The implementation may retain the existing nav-token-shaped
owner and perform an atomic handoff; this EP does not require a new stable
ancestor-owner identity.

The navigation itself does not revalidate a kept, unchanged ancestor
requirement. Resource invalidation, focus/reconnect policy, polling, or an actual
identity/requirement change may still revalidate it through Spec 016. This is the
partial-revalidation law: moving between child routes does not turn every parent
requirement into a new page load.

Resource identity, freshness, cache reuse, error envelopes, generations,
cancellation, GC, scope isolation, and hydration remain exactly one Resources
contract. The router does not copy resource data into route state and does not
add a route-loader cache.

### Honest activation and readiness

#### `:on-match` is activation work

`:on-match` remains a vector of ordinary event vectors dispatched in order after
a successful full activation. It runs when there is no prior target, the
canonical route id changes, or the canonical resolved `:params` or `:query`
value changes. Repeating the same route id/params/query does not run it, and a
fragment-only change does not run it. This is the exact re-fire key set; request
spelling and policy keys are irrelevant. The events are useful for synchronous
seeding, analytics/host notification, and application-owned work that naturally
begins at activation.

Its contract is fire-and-forget:

- it runs only after the effective resource plan forms successfully; a
  committed planning-failure target dispatches none of its events;
- the router guarantees dispatch order and normal run-to-completion event
  semantics;
- it does not await asynchronous effects started by those events;
- it does not infer when their transitive work is finished;
- it does not correlate later global error records back to the route; and
- it does not make route readiness loading or error.

A synchronous handler throw remains visible through the ordinary Spec 009 event
error channel. It is attributed to the event that threw, not rewritten into
route-loader state.

The route `:on-error` metadata key and the internal
`:rf.route.internal/settle-transition` /
`:rf.route.internal/on-match-error` machinery are retired. Applications that
need managed page-read readiness declare `:resources`. Applications that start
other asynchronous work from `:on-match` own its status and error state in the
same event/effect subsystem that owns the work.

SSR continues to dispatch `:on-match` events through the request-local frame, so
synchronous event effects remain symmetric. SSR does not claim to wait for an
arbitrary asynchronous tail. Work that must settle before server render is a
blocking route resource.

#### Route readiness is a resource projection

The public `:rf.route/transition` and `:rf.route/error` reads remain, but their
meaning becomes:

| Effective-plan state | `:rf.route/transition` | `:rf.route/error` |
|---|---|---|
| plan could not be formed | `:error` | structured planning error |
| any blocking first load is pending and none has failed | `:loading` | `nil` |
| a blocking first load failed | `:error` | deterministic first failure in effective plan order |
| all blocking requirements have usable data or there are none | `:idle` | `nil` |
| Resources artefact absent | `:idle` | `nil` |

> **Erratum — 2026-07-26 (rf2-8yx7g, recording the rf2-kqxe6.17 ruling).** The
> failed-load row above says the pick is the deterministic first failure "in
> effective plan order." It graduated as the deterministic first failure in
> **canonical CEDN-1 resource-key identity order** — not plan order, and not hash
> order. The blocking slot written under the nav-token is a **set** of scoped
> resource keys, so effective plan order is not recoverable at reconcile time;
> persisting it was considered and rejected when rf2-kqxe6.17 was ruled, because
> turning the set into an ordered structure threaded through
> commit / settle / hydration / restore is machinery bought only to make an
> arbitrary-but-deterministic tie-break match a sentence. Identity order is the
> stronger guarantee in practice: it is stable across settles that prune
> siblings, where plan order is not. The property the row exists to promise —
> that the reported failure is deterministic rather than incidental — is
> unchanged, and every other row is unchanged. Normative home:
> [spec/012 §Route readiness is a resource projection](../../spec/012-Routing.md#route-readiness-is-a-resource-projection).
> Provenance: ruled on rf2-kqxe6.17, recorded on rf2-kqxe6.18, prose landed on
> PR #6916.

The table uses Spec 016 terms, not router-local guesses:

- a blocking requirement has **usable data** when its active resource identity
  projects `:rf.resource/has-data? true`;
- a blocking **first load is pending** when that identity has no usable data and
  is absent/`:idle` or projects `:rf.resource/loading? true`;
- a blocking **first load failed** when the identity has no usable data and its
  resource status is `:error`; and
- `:fetching` with usable data is a background refresh, including a stale
  revalidation. It never makes the route `:loading`, and a refresh failure stays
  on the resource's `:refresh-error` channel.

Previous-data projection for a newly keyed requirement may keep useful pixels
on screen, but it does not make the new identity's first load complete. A
non-blocking first load, prefetch, and arbitrary `:on-match` work likewise do not
change route transition. Their honest status remains on their owning
resource/application read model.

This projection must have one pure implementation used by subscriptions,
SSR wait/render decisions, Xray, and any cached route-slice fields. An
implementation may cache the projection in runtime-db for efficient updates, but
the cache is not independent authority and must be reconstructible from the
active plan plus managed-resource/planning state. Hydration and epoch restore
must not preserve a route `:loading` value that the restored resource state
contradicts.

R1 applies this projector to R0's behavior-preserving leaf-only resource plan.
R2 replaces that input with the effective parent-to-leaf branch plan; it does
not change the readiness table or fork the projector. This staging lets
activation honesty land before branch composition without creating an interim
readiness contract.

The recommended acceptance ruling keeps `:transition` and `:error` in the
public `:rf/route` read for ergonomic whole-route reads and derives them through
that projector. Runtime-db may cache the projected values, but only as a
reconstructible, non-authoritative cache. Hydration, epoch restore, and every
resource settle must reconcile the cache through the same projector. Acceptance
must lock this shape before R0/R1 beads are cut; Open Issue 2 records the
alternative, not permission to discover the schema mid-implementation.

#### Supersession, cancellation, and SSR

A committed full activation still allocates a monotonic nav-token. Plan
callbacks and route-owned resource work carry enough nav-token/generation
evidence that a superseded result cannot alter readiness for the new plan.
Cancellation is attempted only after owner handoff shows no live owner still
needs the work.

Server handling resolves the address, applies entry decisions, builds the same
effective parent-chain plan, ensures its resources, and waits only for blocking
requirements of the current plan/token. It then renders and serializes the
existing allowed runtime/resource projection. The server does not perform a
separate “prefetch”; activation itself uses the plan.

Hydration installs the route/resource projection and recomputes readiness through
the same projector. Fresh hydrated identities are reused. A client does not
immediately duplicate an SSR ensure merely because the branch was reconstructed.

### Resource-only intent prefetch

The routing artefact adds one public event:

```clojure
[:rf.route/prefetch
 {:to :route/article
  :params {:slug slug}}]
```

It accepts a named `RouteAddress`, resolves the destination, builds the same
effective parent-chain resource plan, and runs each unique requirement through
the Resources artefact in warm mode.

Warm mode has a deliberately narrow contract:

- every ensure is ownerless, carries cause
  `[:route-prefetch <destination-route-id>]`, and is scoped to the frame that
  received the prefetch event;
- warmed entries remain governed by ordinary resource freshness, dedupe, and GC;
- `:blocking?` does not block anything;
- no route state, nav-token, URL, history, scroll, focus, or pending navigation
  changes;
- no `:can-leave`, `:can-enter`, or `:on-match` runs;
- resource failures remain resource failures and traces; they do not become
  route errors; and
- a later activation reuses fresh/in-flight work and attaches its real owner
  before normal activation proceeds.

A prefetch never warms or attaches work in a sibling frame merely because that
frame resolves the same address or scoped resource identity. The carried-frame
invariant applies to planning, trace attribution, cache entries, and teardown.

An invalid prefetch address rejects before planning with the operation-specific
`:rf.error/prefetch-bad-address`. A resource-planning failure emits the ordinary
structured route/resource planning diagnostic with `:plan-cause :prefetch`,
dispatches no partial ensures, and does not alter current route readiness.
When the Resources artefact is absent, the effective warm plan is empty and the
event performs no work; prefetch does not make Resources a mandatory routing
dependency.

Prefetch is a performance hint, not an authorization boundary. Resource requests
must already enforce scope and server authorization. Running entry guards during
warmup would make prefetch a partial navigation and would still not be a security
boundary. Prefetching a destination whose `:can-enter` would later deny is
therefore permitted: it may warm an already-authorized resource cache and means
nothing more. Activation still evaluates and may deny entry.

`route-link` and `v/route-link` accept one behavior value:

```clojure
[v/route-link
 {:to :route/article
  :params {:slug slug}
  :prefetch :intent}
 title]
```

`:intent` warms on credible user intent such as focus, pointer hover, or touch
intent. It never fires merely because a view rendered. A caller may dispatch the
event directly for non-link intent (for example, after a search result becomes
the keyboard selection). On both link surfaces, the installed intent handlers
compose with rather than replace caller-supplied handlers and dispatch to the
render-time-captured frame, exactly as the delayed click handler does.

`:prefetch` is a route-link control key and is stripped before DOM emission,
like `:to`/`:params`/`:query`/`:fragment`; it never appears as an unknown anchor
attribute.

The first surface has no global default, render mode, viewport mode, hover-delay
option, separate preload cache, or prefetch-stale clock. Resource freshness and
dedupe already answer whether work is useful. If measured applications later
need another trigger, that trigger can be proposed without changing warm-mode
semantics.

Raw URLs, external links, guards, `:on-match`, and route-driven code chunks are
not prefetched in this slice. A future code-loading feature may consume the plan
through a late-bound seam after a real bundler/consumer proves the contract.
Spec 012's post-v1 `:load` passage remains an unshipped seam note, not a reserved
public metadata key. R1 must reconcile that note with this EP by removing its
dependency on the retired `:on-match` loading/`:on-error` machinery; any future
code-loading proposal must define its own managed ordering and failure contract.

### Leave confirmation and terminal entry

#### Leave remains resumable

`:can-leave` remains a route-owned subscription decision because leaving a dirty
workflow may genuinely require confirmation UI. It receives the resolved target
and must return a boolean. `false` creates one leave-only pending-navigation
value, dispatches `:rf.route/navigation-blocked`, and leaves the current route
unchanged.

The pending value stores structured intent rather than requiring consumers to
reparse an original event vector:

```clojure
{:id              "opaque-id"
 :destination     {:to :route/home}
 :target          {:route-id :route/home
                   :params {}
                   :query {}
                   :fragment nil
                   :url "/"}
 :cause           :navigate
 :policy          {:replace? true
                   :scroll :preserve}
 :requested-url   "/"
 :rejecting-route :route/editor
 :rejecting-guard :editor/can-leave?
 :url-restored?   false}
```

`:policy` is a normalized map of the caller-authored `:replace?` and `:scroll`
overrides; it is `{}` when neither was supplied. It never stores
`:bypass-leave?`: a request carrying a true bypass could not have entered the
pending slot, and continuation owns its own one-shot internal bypass.

`:rf.route/continue` and `:rf.route/cancel` apply only to this leave slot.
Continue clears the slot and executes the stored `RouteDestination` plus stored
policy through the normal pipeline with a one-shot leave bypass; entry is still
evaluated normally. If a blocked URL-driven transition restored the prior URL,
continue first moves the host URL back to `:requested-url`, preserving that
door's no-extra-push semantics. Cancel clears the slot and stays.

A non-boolean leave result fails closed and emits the existing structured
programmer error. This proposal recommends the public `:bypass-leave? true`
policy as an explicit trusted-programmer escape for the rare direct-navigation
case; the ordinary confirmation path uses `continue` (Open Issue 3).

Hard reload/cross-origin `beforeunload` integration remains a separate host
concern. This EP neither claims that an SPA pending value can stop the browser
from unloading nor adds a second confirmation API.

#### Entry is terminal

`:can-enter` remains a route-owned subscription id consulted after target
resolution through every door. It receives the resolved target and returns a
boolean:

- `true` allows the current plan to proceed;
- `false` terminates this navigation attempt; and
- any non-boolean value emits `:rf.error/can-enter-non-boolean` and is treated as
  denial.

Denial:

- commits no target route, resource owner, URL push, scroll, or activation event;
- creates no pending-navigation value;
- dispatches `:rf.route/entry-denied` once;
- emits the corresponding trace; and
- when the browser already moved because of popstate, restores the current URL
  by replace.

The denial event receives:

```clojure
{:destination   {:to :route/account
                 :params {}
                 :query {}
                 :fragment nil}
 :target        {:route-id :route/account
                 :params {}
                 :query {}
                 :fragment nil
                 :url "/account"}
 :cause         :link
 :requested-url "/account"
 :guard         :auth/signed-in?}
```

The routing artefact registers a framework no-op default handler for
`:rf.route/entry-denied`, matching the existing blocked-navigation convention.
An application may replace it with an auth redirect or other policy handler; it
is not required to register a handler merely to make denial safe. With the
default, client denial is a hard deny: the current route and URL remain in
place (or the URL is restored after popstate), and no protected activation work
runs.

For a matching raw-URL request, `:destination` is the canonical named address
recovered from the resolved target and `:requested-url` preserves the input. An
unmatched in-app raw URL remains the raw destination because rewriting it as the
registered not-found route's address would change the URL on replay.

An authentication flow stores the named `:destination`, navigates freshly to
login (normally with `:replace? true`), and after successful sign-in dispatches
a fresh `:rf.route/navigate` with the stored destination. The guard re-evaluates
because this is a normal new attempt:

```clojure
(rf/reg-event :rf.route/entry-denied
  (fn [{:keys [db]} [_ {:keys [destination]}]]
    {:db (assoc-in db [:auth :return-to] destination)
     :fx [[:dispatch
           [:rf.route/navigate
            {:to :route/login :replace? true}]]]}))

;; after a successful sign-in:
[:rf.route/navigate (get-in db [:auth :return-to])]
```

The example shows the client arm. On a server frame the application handler
normally emits Spec 011's canonical
[`:rf.server/redirect`](../../spec/011-SSR.md#standard-fx) effect to the login
URL. Its [redirect-precedence](../../spec/011-SSR.md#redirect-precedence)
contract truncates HTML and replaces the default `403` response; merely
rendering the login route inside the same server frame intentionally keeps the
`403` unless the handler also changes the response.

For a group of protected routes, application code may use a small route-metadata
helper that associates the same `:can-enter` subscription with each
registration. The subscription receives the resolved target and may inspect its
route id/tags. This is the canonical cross-route recipe; the router does not add
a middleware chain merely to avoid an ordinary map helper. General registered
interceptors remain available for application-wide event policy, but routing
correctness never depends on attaching equivalent logic separately to every
navigation door.

Initial load and SSR take the same denial path. On a server frame, denial stamps
a default `403` response before dispatching the denial event. Because re-frame
drains that handler before render, an application may replace the result with
the canonical `:rf.server/redirect` response (normally to login) or explicitly
set another status under Spec 011's
[standard response effects](../../spec/011-SSR.md#standard-fx) and
[multiple-status policy](../../spec/011-SSR.md#multiple-status-policy). If no
replacement navigation or redirect is established, the protected route remains
uncommitted and the host renders the ordinary application shell under `403`
against the unchanged route projection (absent on a first request). No
resource or hydration data for the denied target is produced. R4 must graduate
this hard-deny arm into Spec 011 rather than leaving HTTP behavior to adapter
guesswork.

The current `:rf.route/entry-blocked` event, enter-shaped pending state,
`:enter-attempts`, enter-resume loop ceiling, and `:bypass-guards? #{:enter}`
surface are removed. The router no longer models login as a paused transition
that can be resumed out of its original causal context.

### Explicit query-state flow

Query values remain first-class typed route state:

- route `:query` schemas validate/coerce input;
- route `:query-defaults` remain destination-local declaration;
- a `RouteAddress` may carry a complete destination query;
- in-place `:query` replaces the current query; and
- in-place `:query-merge` applies explicit deltas, with `nil` removing a key.

Route metadata `:query-retain` is removed. A destination address no longer gains
hidden values from whichever route happened to be current.

Applications that deliberately carry global URL state use an ordinary named pure
function, making the policy visible and testable:

```clojure
(defn with-shell-query [current-query address]
  (update address :query
          (fn [destination-query]
            (merge (select-keys current-query [:locale :tenant])
                   (or destination-query {})))))
```

The explicit destination query wins in the example. An app may apply the helper
at selected call sites or in its own event/interceptor when the policy is truly
application-wide. The framework does not add generic query middleware,
functional query updaters, or a second `update-query` event. The existing
in-place edit is already the causal primitive.

Deleting `:query-retain` does not change the query parser, defaults, validation,
`+` semantics, or fragment behavior. A key previously promoted to a keyword
solely because `:query-retain` named it now remains a string unless the route's
`:query` schema or `:query-defaults` declares it. R5 must migrate those
declarations explicitly rather than preserving keyword promotion as hidden
residue. The same cut removes `:query-retain` from the promotion vocabulary
used by the `:rf.warning/route-classification-query-key-unpromoted` advisory and
updates its fixtures/advice text to name only `:query` and `:query-defaults`.

### View and link consequences

The root view continues to select and compose views from `:rf.route/id` and
`:rf.route/chain`. Parent-chain resource planning does not imply parent-chain
render ownership. There is no `<Outlet>`, route component slot, provider tree,
loader-data hook, suspense boundary, or route-local error component.

The passive-view law is practical rather than ceremonial:

- reading `:rf.route/*` and `:rf.resource/*` subscriptions in render is correct;
- pure formatting and local calculation in render are correct;
- dispatching navigation from a click/keyboard/host handler is correct;
- `route-link :prefetch :intent` may dispatch from that interaction;
- calling `fetch`, resource `ensure`, navigation, or dispatch during render is
  not a supported pattern; and
- no public router API is added whose only ergonomic use requires causal render.

Active navigation styling remains a projection: compare a link address/route id
with `:rf.route/id` or membership in `:rf.route/chain`, then pass ordinary
class/ARIA attributes to `route-link`. A short guide recipe is preferred over a
`NavLink` mini-language.

Background-location/modal navigation is likewise a state-model recipe: put
shareable modal identity in query/path and keep non-shareable presentation
context in app state. History-state masking is not added because it is not
server-addressable and would create a second location truth.

### Tooling and observability

The plan must be legible without executing view code. Xray/trace projection
shows:

- source address and cause;
- resolved target and parent-to-leaf branch;
- each route's contributed resource requirements;
- local `:after` edges and plan order;
- identity groups and dedupe;
- old/new kept/added/removed identities;
- blocking/readiness state;
- resource owners and activation/prefetch causes; and
- any leave block, entry denial, or planning failure.

This projection graduates with the planner. R0 exposes only the source,
resolved target, cause, branch, and behavior-preserving leaf plan needed to
prove the shared spine. R2 adds occurrence/dependency groups, contributor
dedupe advisories, the grouped order, and the identity diff. R6 proves the
integrated view. No slice is permission to build a second Xray graph or a
general-purpose public plan debugger.

> **Erratum — 2026-07-27 (rf2-dlkou, recording the rf2-9sluz ruling).** R2's
> **identity diff** ships as the identity partition on the existing activation
> `:rf.resource/route-plan` row — `:ensured-identities` / `:kept-identities` /
> `:removed-identities` beside the `:ensured` / `:kept` / `:removed` counts,
> with `:identities` carrying the planner's grouped plan order. The vectors are
> named for what the runtime **did**, not for the diff: a retained-but-unusable
> identity takes the ordinary ensure path, so a vector named `:added` would
> disagree with the `:ensured` count beside it. Everything else in the bullet
> list above is **deliberately not projected**: occurrence/dependency groups,
> the per-contributor requirement mapping, and local `:after` edges are internal
> planning mechanics, and Xray's static route/resource graph, the
> planning-failure evidence, and `:redundant-children` are the authorities for
> what a reader would otherwise want from them. No route-plan panel, no public
> plan value, and **no eleventh conformance row** — conformance for the
> correction is the enriched shape pinned in the R2 partial-activation fixture
> plus the shape-driven egress test. A future EP that graduates a plan
> projection owns its own row.

> **Erratum — 2026-08-03 (rf2-dlkou, audit of the shipping PR).** The partition
> is computed on **resource identity** — the canonical bytes of the whole scoped
> key — and not on host value equality. The two differ: canonical bytes preserve
> a collection's kind, so a vector-bearing and a list-bearing parameter are two
> cache entries that Clojure `=` calls one. Computing the diff on `=` reported a
> removed identity as still present (`:removed 0` against a real removal) and
> could match a prior identity to its byte-distinct twin when deciding adoption.
> See [Spec 016 §Plan diff and owner handoff](../../spec/016-Resources.md#plan-diff-and-owner-handoff).
> Two carriers **either side** of the planner still collapse the same pair — the
> occurrence dedupe that builds the plan's grouped order, and the set-shaped
> `[:rf.runtime/routing :resource-plan]` / `:resource-blocking` handoff slots —
> so a single plan cannot yet contain both members and the live handoff cannot
> yet deliver both. Closing that moves a documented cross-feature slot shape and
> is tracked separately (**rf2-btdl1**); it is a dispatch question rather than a
> trace-contract one, and it does not reopen this erratum.

`:rf.resource/route-plan` is the existing Spec 016 route/resource graph
operation and is extended rather than replaced by a parallel trace. Prefetch
emits one `:rf.route/prefetched` summary trace; its plan trace carries
`:plan-cause :prefetch`, and its ensures carry
`:cause [:route-prefetch <destination-route-id>]`. The underlying ensures retain
their normal resource traces.

The Spec 009 graduation roster is explicit:

- **new or renamed:** event `:rf.route/prefetch`, event/trace
  `:rf.route/entry-denied`, trace `:rf.route/prefetched`, and error
  `:rf.error/prefetch-bad-address`;
- **retained and, where needed, extended:** errors
  `:rf.error/navigate-bad-request`, `:rf.error/route-url-validation`,
  `:rf.error/can-leave-non-boolean`, `:rf.error/can-enter-non-boolean`,
  `:rf.error/resource-route-plan`, `:rf.error/resource-route-blocking`, and
  `:rf.error/resource-ssr-blocking-timeout`; event/trace
  `:rf.route/navigation-blocked`; and trace `:rf.resource/route-plan`; and
- **retired with no aliases:** event/trace `:rf.route/entry-blocked`, error
  `:rf.error/route-guard-loop`, internal events
  `:rf.route.internal/settle-transition` and
  `:rf.route.internal/on-match-error`, the
  `:rf.route/on-match-error-trap` listener id, the
  `:rf.route/on-match-id`/`:rf.route/on-match-frame` attribution fields, and
  the internal `:rf.route/enter-attempts` resume rider.

The retained `:rf.error/resource-route-plan` schema is widened deliberately for
the shared planner. Its tags require `:route-id` and `:plan-cause` (one of
`:link`, `:navigate`, `:popstate`, `:initial`, `:ssr`, or `:prefetch`);
`:nav-token` is present only for an activation attempt, and `:resource-id` is
present only when one declaration owns the failure. The existing `:cause` field
keeps its existing meaning—underlying failure/ex-data—and is never overloaded
with `:prefetch`; a plan-wide collapse cycle records its projected
identity-group and contributor/local-id evidence there. The
`:rf.resource/route-plan` trace uses the same `:plan-cause` vocabulary.

> **Erratum — 2026-07-26 (rf2-8yx7g).** `:plan-cause` graduated as an
> **optional** tag whose only member is **`:prefetch`**, on both the widened
> `:rf.error/resource-route-plan` error and the `:rf.resource/route-plan` trace.
> It is present on a warm-mode intent-preload row and **absent** on an
> activation row; the five activation causes (`:link`, `:navigate`, `:popstate`,
> `:initial`, `:ssr`) never appear on either. The paragraph above is corrected to
> that shape; the ratified requirement that the widened tags carry `:route-id`,
> that `:nav-token` ride only an activation attempt, that `:resource-id` ride
> only a single-declaration failure, and that `:cause` never be overloaded with
> `:prefetch`, all stand as written.
>
> **Why the narrower shape is the right one.** `:plan-cause` is not a lossy
> subset of the door vocabulary — it is its **complement**. Every activation
> planning failure is preceded, in the same drain and the same commit branch, by
> the `:rf.route/planned` trace, which already carries the door under `:cause`
> drawn from exactly those five values; `commit-navigation` has only two callers
> (`routing/navigate.cljc` and `routing/url_change.cljc`) and both emit
> `:rf.route/planned` immediately before consulting the resource plan. Prefetch
> is the one planning path with **no** `:rf.route/planned` trace at all —
> warmup is not activation — so it is the one path whose cause is not otherwise
> on the bus. Carrying the door cause a second time on the activation rows would
> also require widening the `:routing/on-route-entry` hook contract to thread it:
> the hook hands the planner `{:route-meta :route-id :params :query :fragment
> :nav-token :prev-id :prev-nav-token :ctx :app-db :runtime-db :branch
> :branch-error :prev-identities}` and deliberately no door cause, because
> routing does not ask the Resources artefact to reason about which door it came
> through. One fact, one name, one place (`Conventions.md` §The naming rules).
>
> Normative home:
> [spec/Spec-Schemas.md §`ResourceRoutePlanTags`](../../spec/Spec-Schemas.md#per-category-tags-schemas)
> and the [spec/009 catalogue row](../../spec/009-Instrumentation.md#error-event-catalogue).
> The 009 rows were brought into line with this shape by rf2-wsopx, for which
> this erratum is the prerequisite.

The stray `:rf.error/resource-route-plan-failed` spelling in Spec 012 is not a
second category; graduation replaces it with the existing canonical
`:rf.error/resource-route-plan`. Route metadata `:on-error` and
`:query-retain`, plus request policy `:bypass-guards?`, are retired input
surfaces rather than error ids.

All diagnostic values cross the existing projection/elision boundary. A trace
must not turn a sensitive URL/query/resource declaration into a new egress leak.

### Conformance obligations

Graduation requires executable proof of the following:

1. **Address parity (R0 foundation; R3 prefetch and R4 replay arms).** One table
   of valid/invalid `RouteAddress` values drives `route-url`, navigation,
   `rf/route-link`, `v/route-link`, named entry/pending payloads, and prefetch on
   JVM and CLJS. Flat wrapper maps prove that only the extracted address reaches
   the closed schema. Closed request maps reject misspelled controls; both link
   surfaces strip the exact routing keys and preserve the remaining DOM props
   without asking routing to classify arbitrary attributes. The raw destination
   branch is tested separately. Unknown keys and malformed combinations on
   closed maps—including in-place `:params` with reason
   `:params-requires-destination`—fail identically. An in-place
   `{:query ...}` request proves the complementary rule: it follows the edit
   branch and never reaches the closed `:rf/route-address` schema.
2. **Door parity (R0 target/leaf-plan spine; R2 effective branch plan).** Named
   address, matching raw URL, link, programmatic, popstate, initial, and SSR
   inputs resolve to the same target/branch/resource plan. Only cause-specific
   history and scroll effects differ.
3. **Passive render (R0; R3 adds the intent arm).** Rendering either route-link
   or a routed view dispatches no navigation, prefetch, resource ensure, or
   application event. Intent interaction dispatches at most the expected
   prefetch/navigation events.
4. **Branch composition (R2).** Automatic composition for every declared
   `:parent`, parent-to-leaf constraints, local `:after` scope, `:when`,
   params/scope failure, and contributor diagnostics are pinned. Same-route and
   parent/child occurrences that dynamically converge on one identity exercise
   grouped ordering: satisfiable graphs dispatch once without losing an edge,
   collapse-created cycles fail the whole plan, and a redundant child copy
   appears as an advisory on `:rf.resource/route-plan`. Every planning-failure
   arm attaches and ensures none of the partial next plan and releases owners
   held only by the previous plan after the failed target/error commit.
5. **Partial activation (R2).** Sibling-leaf navigation retains unchanged parent
   identities, attaches before release, does not abort shared in-flight work,
   ensures newly added identities (including a requirement whose resolved
   identity changed), and releases removed identities.
6. **Readiness honesty (R1 over the leaf plan; R2 over the branch plan).**
   No-resources, blocking first load, non-blocking load, cached fresh data,
   `:fetching` with usable data, background-refresh failure, planning failure,
   first-load failure, previous-data projection, supersession, restore, and
   hydration all project the specified transition and error. Refresh never
   flips the route to `:loading`; projector reconciliation corrects any cached
   fields. `:on-match` never changes readiness and is not dispatched when
   planning fails.
7. **Prefetch isolation (R3).** Prefetch runs the full effective resource plan
   ownerlessly in the dispatching frame, dedupes repeated intent, remains
   GC-eligible, reuses work on activation, and runs no guard, URL, route-state,
   scroll, `:on-match`, sibling-frame, or SSR side effect. Both link surfaces
   preserve caller intent handlers and target their render-time-captured frame;
   prefetch planning failures carry `:plan-cause :prefetch` and no nav-token.
8. **Guard parity (R4).** Leave and entry decisions cover link, programmatic,
   popstate, initial, and SSR doors. Exact no-ops run neither guard; full
   transitions—including changed in-place query—run both in order;
   fragment-only transitions preserve the existing both-guard contract. Leave
   alone creates pending state. Entry denial never does. The framework default
   denial handler is safe and no-op; client/popstate preserves or restores the
   current URL; SSR hard denial with no replacement produces `403`; and an
   application redirect supersedes that default. A post-login return is a fresh
   address navigation. Leave continuation preserves the stored destination,
   explicit replace/scroll policy, cause, and restored-popstate URL behavior.
9. **Query explicitness (R5).** `:query-retain` is rejected at registration;
   defaults and in-place merge remain; every in-tree call site, fixture,
   promotion source, and classification-advisory reference is migrated; and
   every cross-route carried key appears in the authored address after the
   application's pure carry helper runs.
10. **Frame isolation and teardown (established in R0, exercised by every
    slice, integrated in R6).** Plans, owners, prefetch work, pending leave
    state, traces, and host listeners stay frame-scoped and release completely
    on frame destroy.

The normal repository JVM/CLJS conformance, unit, integration, SSR/hydration,
browser-link, schema/status-sync, lint, and build gates must stay green. New
fixtures belong beside the owning specs, not only in implementation tests.

### Graduation slices

Implementation is delivered as thin vertical slices. Each slice includes its
owning spec edits, schemas, implementation, fixtures/tests, traces, and affected
guide/examples; there is no spec-only waterfall and no one giant router rewrite.

| Slice | User-visible outcome | Depends on |
|---|---|---|
| **R0 — address and plan spine** | `:rf/route-address`, shared address extraction/validation across navigation, `route-url`, `rf/route-link`, and `v/route-link`; one resolved-target/plan seam used by every door; the minimum source/target/cause/branch/leaf-plan diagnostic projection; passive-view governing-law docs and fixture; existing runtime behavior otherwise preserved | accepted EP |
| **R1 — honest activation** | `:on-match` fire-and-forget only after a valid plan; resource-derived transition/error; removal of global on-match correlation and route `:on-error`; SSR wait semantics and the post-v1 `:load` seam note corrected | R0 |
| **R2 — branch resource plan** | parent-to-leaf resource composition, constraint-preserving grouped identity dedupe and redundant-child advisory, plan diff, attach-before-release, partial revalidation, SSR/hydration parity | R0, R1 |
| **R3 — intent prefetch** | `:rf.route/prefetch`; `:prefetch :intent` on both `rf/route-link` and `v/route-link`; frame-scoped ownerless full-plan warm ensures; traces and reuse/GC proof | R2 |
| **R4 — terminal entry** | leave-only, policy-preserving pending protocol; guard behavior for full, fragment-only, and no-op transitions; terminal `:can-enter`; `:rf.route/entry-denied`; default hard-deny/SSR `403`; fresh auth return; all-door/SSR coverage; auth how-to and RealWorld update in the same cut; obsolete resume/loop machinery removed | R0; serialized after R3 by default |
| **R5 — explicit query carry and surface cleanup** | remove `:query-retain`, preserve defaults/in-place edits, migrate every in-tree retain call site/fixture and every promotion/advisory dependency it supplied, ship the explicit carry recipe, reject obsolete metadata/policy keys, final API/schema/error inventory | R0; serialized after R4 by default |
| **R6 — integrated proof** | all conformance rows and guides coherent; RealWorld or equivalent routed app proves parent shell reads, leaf reads, intent warmup, auth denial/return, SSR, and no render-caused work | R1–R5 |

The default landing order is R0 → R1 → R2 → R3 → R4 → R5 → R6. Every
contract slice touches the hot-zone `spec/012-Routing.md`, so those edits and
their branches are serialized even where the runtime concepts are otherwise
independent: at most one open PR may edit Spec 012. Work may proceed
concurrently only on fenced implementation or guide surfaces that do not touch
Spec 012 and only after the corresponding 012 ruling has landed. R3 waits for
the effective plan because prefetching a leaf-only approximation would make the
temporary behavior the wrong contract. R4's table position after R3 encodes
this hot-zone lane, not a logical dependency on prefetch; the operator may move
R4 earlier after R0 if terminal-entry/auth honesty is the higher priority,
provided the same single-PR Spec 012 fence is preserved.

Future beads should be cut smaller than these rows where useful, but every bead
must name the vertical outcome, exact spec/conformance clauses, affected hosts,
migration/deletion work, and commands/evidence that close it. Beads are created
after this proposal is audited and accepted; this EP does not pre-create or
pretend to resolve their implementation details.

A slice is complete only when the conformance arms mapped to it above are green
on their named hosts and its schema/API/error migrations land in the same cut.
The mapping is the EP's acceptance statement; approximate file ownership and
the eventual bead topology remain live-tree implementation planning in `bd`,
not a second plan frozen into this proposal.

### Deliberate non-goals

This programme does not add:

- route-owned loader data, cache, freshness clocks, invalidation, mutations, or
  optimistic writes;
- component-scoped fetching, `useLoaderData`, route error components, Suspense
  ownership, or render-driven ensures;
- `<Outlet>`, automatic layout rendering, provider/context inheritance, or
  generic parent metadata merge;
- route actions, form/fetcher protocols, automatic mutation revalidation, or a
  server-function transport;
- file-system route authority, route code generation, or automatic code
  splitting;
- relative route-address semantics, pathless layout routes, or ranking surgery;
- query middleware, ambient cross-route retain, history-state masks, or a
  `NavLink` behavior DSL;
- render/viewport prefetch, prefetch delays, a preload cache, or SSR prefetch;
- a routing-specific guard middleware chain or a resumable entry protocol
  (general registered interceptors remain unchanged); or
- a public general-purpose route-plan executor.

These features are not forbidden forever. Each requires a concrete re-frame2
consumer that cannot be served elegantly by addresses, events, subscriptions,
resources, ordinary view composition, or a small app helper.

### Guide impact

`docs/core/views.md`, `docs/core/where-state-lives.md`, and
`docs/core/derivations-and-algebra-views.md` gain the explicit rule that views
are derivative projections and that interaction handlers—not render—cross the
causal boundary. The routing tutorial/concepts/examples/testing pages are
rewritten around `RouteAddress`, effective branch plans, honest readiness,
leave-only pending navigation, terminal entry, explicit query carry, active-link
projection, and intent prefetch. The auth and unsaved-change how-tos split the
fresh-entry and resumable-leave recipes. The unsaved-change how-to pairs the
current route's `:can-leave` decision with a host `beforeunload` listener over
the same dirty-state source for hard reload/cross-origin exits; that listener is
a host-adapter recipe, not a second router confirmation API. Resource guides
teach route plans as owner/cause declarations, and SSR guides teach blocking
resources as the only route-owned wait point. “Coming from” guides explain the
adopted capabilities without importing outlet, hook, cache, action, or
middleware ownership. R0 owns the three governing-law view-doc edits and their
passive-render fixture; they are not left to the final cleanup slice.

## Rationale

### Planning is the useful common denominator

A router is the earliest part of the application that knows a likely
destination. That makes it the natural place to resolve an address, validate
URL state, enumerate the active branch, decide whether the transition may
proceed, and describe required reads. It does not follow that the router should
own the read cache, application mutations, or component tree.

Making the plan explicit captures the leverage without creating a second
framework. It also turns several correctness claims into one testable object:
every door must produce the same target and requirements, prefetch must use the
same requirements as activation, SSR must wait on the same blocking subset, and
Xray can explain why work exists.

### Managed resources are the honest readiness boundary

An ordinary event can emit any effect, including effects unknown to the routing
artefact. Even if the initial handler runs synchronously, later callbacks may
fork, retry, dispatch more events, elide payloads, or complete after another
navigation. Correlating a global error record by event-vector equality cannot
turn that open graph into owned work.

Resources already define identity, ownership, work ids, generations, freshness,
first-load versus refresh errors, cancellation, GC, SSR projection, and passive
reads. Letting route metadata declare resource requirements therefore produces a
real wait/error boundary without inventing router promises or hiding app state
inside a loader result.

`:on-match` is still valuable. Narrowing its promise makes it more useful, not
less: programmers can use a simple activation event without accidentally opting
into a fictional page-loader FSM.

A plan failure commits addressable target facts so an error projection can
explain where navigation failed, but it does not cross the successful activation
boundary. Suppressing `:on-match` in that case avoids false page-view analytics,
host notifications, and state seeding for requirements that never ran. The
structured planning error is already the honest observation seam.

### Branch data composition need not imply branch rendering

Shared layouts commonly need shared data. Repeating the same resource
declaration in every child is noisy for humans, hostile to mechanical review,
and makes prefetch incomplete unless every copy stays aligned.

The existing `:parent` chain already names the relevant branch. Folding only
`:resources` along it yields the high-value behavior while preserving ordinary
Clojure view composition. A generic metadata fold would force merge laws for
head, scroll, tags, guards, events, and future keys that have different
semantics. The fixed resource-only fold avoids that abstraction trap.

Automatic resource composition follows from the meaning of `:parent`: a child
that renders within a parent shell participates in the branch whose shared reads
the parent declares. Requiring `:inherit-resources? true` on every child would
make the effective plan depend on duplicated acknowledgements, and a missed
acknowledgement would fail silently in navigation, prefetch, and SSR. The route
table remains readable as data because `:parent` points directly to the
contributor chain and tooling projects the effective plan. This convenience is a
deliberate semantic expansion, not generic metadata inheritance.

### Entry and leave are different temporal problems

A dirty-form decision is about state that exists now: pause this attempt, show
UI, then continue exactly once or cancel. Pending frame state is a good model.

An entry gate is about whether the target is allowed under current app state. If
sign-in changes that state, the correct operation afterwards is a fresh
navigation that re-resolves defaults, scope, route registration, and policy.
Keeping the old attempt pending across a login introduces stale causal context,
an enter-bypass vocabulary, attempt counters, and loop handling merely to
recreate a new decision.

The proposed boolean gate plus a structured denial event is the smallest useful
surface. A richer declarative redirect result is considered below, but it should
not be added until it proves simpler than the ordinary event recipe across
client and SSR hosts.

The server's no-handler `403` default says exactly what happened: a registered
target matched and policy denied entry. Applications that intentionally conceal
resource existence may replace it with their ordinary not-found/`404` response;
auth flows normally replace it with a redirect. The router supplies a safe,
deterministic floor without trying to infer either application policy.

### Explicit addresses improve human and AI ergonomics

A plain map can be stored, compared, generated, tested, traced, and passed
through events without a router object or view context. Reusing it across links,
navigation, return-to state, URL generation, and prefetch makes examples
transferable and lets tools validate a destination before it is mounted in UI.

Keeping address, policy, and edit as conceptual/schema boundaries prevents
accidental serialization of `:replace?`, `:scroll`, or `:query-merge` as
location identity. Keeping the public call flat avoids ceremony at the common
dispatch/link sites.

Ambient query carry has the opposite property: the address under review is not
the address produced. A five-line app helper is more honest and more adaptable
than a router-wide middleware language. Destination defaults remain because
they are stable facts of the named destination rather than accidental facts of
the route being left.

### Intent prefetch belongs above Resources and below views

Per-resource hover handlers make every call site reimplement target params,
scope resolution, conditions, branch inheritance, and dedupe. View-render
fetching is worse: it makes speculative rendering causal and cannot distinguish
mere visibility from user intent.

The route planner already has the complete requirement set, and Resources
already has the ownerless cause-only ensure needed to warm it. One intent event
connects those existing responsibilities. Avoiding a mode/timing matrix keeps
the feature a hint rather than a scheduling subsystem.

### Alternatives considered

| Alternative | Disposition |
|---|---|
| Keep leaf-only resources and use app helpers for parents | rejected; hides the effective plan and duplicates declarations/prefetch logic |
| Require `:inherit-resources? true` on each child | not recommended; duplicates the meaning of `:parent`, creates silent incomplete-plan omissions, and becomes noisier down deep branches; acceptance must explicitly ratify automatic composition |
| Inherit all parent metadata | rejected; unrelated keys need incompatible merge/ordering rules |
| Sort occurrences, then dedupe by keeping the first identity | rejected; a later duplicate may carry an `:after` edge that first-occurrence selection silently erases; collapse the occurrence graph and fail closed on an incompatible cycle |
| Make `:on-match` an awaited promise/loader API | rejected; creates a second async/cache model and makes events return router-owned data |
| Keep global event-error correlation | rejected; event-vector coincidence is not causal ownership |
| Remove `:on-match` entirely | rejected; ordered activation events are a small, useful event-native seam |
| Dispatch `:on-match` after resource planning fails | rejected; the target is addressable for error projection but did not successfully activate, and application effects would report or seed a page whose requirements never ran |
| Put route data in a router cache | rejected; Resources is the one server-read owner |
| Trigger ensures from views | rejected; violates passive render and loses SSR/prefetch planning |
| Keep resumable entry and improve its loop handling | rejected; the machinery treats a fresh policy decision as continuation |
| Return a closed allow/redirect/deny value from entry policy | viable future refinement; see Open Issue 1 |
| Run guards before transition classification | rejected; an exact no-op could create pending/denied state even though no target fact changes |
| Let every in-place request bypass guards | rejected; changed params/query are data-bearing full activations regardless of request spelling, while exact no-op and fragment behavior are ruled explicitly |
| Run entry guards during prefetch | rejected; warmup is not activation or an authorization boundary |
| Add render/viewport/default prefetch modes now | deferred pending measured demand |
| Keep `:query-retain` or add generic search middleware | rejected; final addresses become ambient transformations |
| Add nested address/policy envelopes | rejected for the public paved path; internal schemas already preserve the distinction |
| Keep direct leave bypass runtime-internal | not recommended; it forces trusted workflow code to contort guard source state instead of naming the exceptional policy honestly |
| Add Outlet/NavLink/masking/file routes/actions for parity | rejected; each imports ownership that conflicts with ordinary subscriptions, events, resources, and view composition |
| Expose the whole plan as a public promise API | deferred; traces/tests need a data projection, applications do not yet need an executor |

## Backwards Compatibility

The project is pre-alpha, so this is a direct contract migration with no
deprecated aliases:

- existing named destination maps already use the future `RouteAddress` fields;
  they gain a schema/name and shared validation rather than call-site ceremony;
- old `:query-retain` metadata fails loudly; applications replace it with an
  explicit address helper or event/interceptor, and R5 removes every in-tree
  retain-dependent call site and fixture in the same cut;
- `:query-defaults`, query schemas, destination `:query`, in-place `:query`, and
  in-place `:query-merge` remain;
- `:on-match` declarations remain valid, but no longer drive route
  transition/error or SSR asynchronous waiting and do not dispatch for a failed
  resource plan;
- route `:on-error` is removed; managed page-read errors move to resource
  projections, and application-owned work keeps application-owned error state;
- declaring `:parent` now opts a route into automatic ancestor `:resources`.
  This is a deliberate semantic expansion for every existing parent chain, not
  a compatibility-preserving opt-in marker. Child copies resolving to the same
  identity dedupe when their collapsed constraint graph is valid, and the plan
  trace advises on the redundant child contributor; applications should delete
  those copies so the plan has one clear declaration;
- `:rf.route/entry-blocked` becomes `:rf.route/entry-denied`; enter-shaped
  pending values, `continue`-after-login, attempt counters, loop errors, and
  enter bypass are removed;
- pending navigation becomes leave-only and stores
  `:destination`/`:target`/`:cause` plus normalized explicit `:policy` instead
  of the original event as its replay description, so confirmation does not
  discard replace/scroll intent;
- subject to the acceptance ruling in Open Issue 3, `:bypass-guards?` becomes
  the public boolean `:bypass-leave?`;
- route transition/error reads keep their names but change to managed-resource
  semantics; and
- prefetch is additive and opt-in.

Each vertical slice updates all in-tree registrations, RealWorld/example apps,
conformance fixtures, errors, schemas, and guides that use the changed contract.
Old route metadata and policy keys are rejected at registration/request
validation so a stale example cannot silently keep donor semantics.

No compatibility shim may correlate `:on-match` errors in the background, copy
`:query-retain` into addresses, synthesize an enter pending value, or expose both
old and new entry events. Such shims would preserve the ambiguity this EP exists
to remove.

## Resolved Decisions

Accepted by the operator on 2026-07-24. All four Open Issues below were
resolved as recommended; their sections are retained for the design record.

1. **Entry decisions (OI-1, 2026-07-24).** `:can-enter` stays a boolean
   subscription over the resolved target, paired with the terminal
   `:rf.route/entry-denied` event and its framework no-op default handler. No
   closed decision-value algebra: the default hard-deny `403`, Spec 011
   redirect supersession, and the ordinary redirect event cover the SSR cases.
2. **Readiness fields (OI-2, 2026-07-24).** `:transition` / `:error` remain in
   the public `:rf/route` read, derived through one pure projector; runtime-db
   may cache the projected values only as a reconstructible, non-authoritative
   cache, reconciled through the projector on hydration, epoch restore, and
   every resource settlement. R0/R1 implement this shape; they do not revise
   it.
3. **Leave bypass (OI-3, 2026-07-24).** The public boolean `:bypass-leave?
   true` ships as the explicit trusted-programmer escape; `:rf.route/continue`
   remains the ordinary confirmation path. The set-valued `:bypass-guards?`
   and every entry bypass are removed.
4. **Branch resource opt-in (OI-4, 2026-07-24).** Automatic parent-to-leaf
   resource composition: declaring `:parent` IS the opt-in — no
   `:inherit-resources?` marker. The effective-plan projection and the
   redundant-child advisory keep the inherited contribution and accidental
   child copies mechanically visible.

Acceptance also knowingly ratifies, per the Open Issues preamble: the closed
extracted `:rf/route-address`; the `RouteDestination` / `:rf/route-destination`
replay union; `:rf.route/entry-blocked` → `:rf.route/entry-denied`; the
leave-only pending value's `:destination`/`:target`/`:cause`/`:policy` shape;
`:bypass-guards?` → `:bypass-leave?`; the prefetch event/error/trace ids; the
shared planner's `:plan-cause` diagnostic vocabulary and the widened
`:rf.error/resource-route-plan` tags; and the exact retirement roster under
Tooling and observability.

Implementation programme: epic `rf2-kqxe6` — A0 (this record), R0–R6, the
docs/examples/skills waves, and three independent review gates (correctness,
completeness, implementational quality).

## Open Issues

**Resolved at acceptance (2026-07-24)** — every recommendation below was
accepted as written; see Resolved Decisions above. The sections are retained
as the design record of each choice and its rejected alternative.

Acceptance must also knowingly ratify the closed extracted
`:rf/route-address`; the `RouteDestination`/`:rf/route-destination` replay
union; `:rf.route/entry-blocked` → `:rf.route/entry-denied`; the
leave-only pending value's
`:destination`/`:target`/`:cause`/`:policy` shape;
`:bypass-guards?` → `:bypass-leave?`; the prefetch event/error/trace ids; the
shared planner's `:plan-cause` diagnostic vocabulary and widened
`:rf.error/resource-route-plan` tags; and the exact retirement roster under
Tooling and observability. These are not a later “cleanup” bundle or accidental
consequences of the headline features.

### 1. Boolean `:can-enter` plus denial event, or a closed terminal decision?

The recommendation is the specified boolean subscription plus
`:rf.route/entry-denied`. It preserves the current state-shaped guard, keeps
causal redirect work in an event, and is sufficient for auth, feature flags,
maintenance denial, client navigation, and SSR.

The alternative is to replace `:can-enter` with a pure closed result such as
`:allow`, `{:redirect RouteAddress :replace? true}`, or
`{:deny reason}`. It would make initial/SSR redirect terminal without relying on
an event handler, but adds a policy algebra, reason/redirect schemas, recursion
rules, and another place that causes navigation. The specified default no-op,
hard-deny `403`, and ordinary redirect event cover the demonstrated SSR cases
without that algebra. The recommended acceptance ruling is therefore the
boolean plus denial event.

### 2. Where do the readiness projection fields live?

The observable transition/error table is not open. The recommended acceptance
ruling keeps the fields in the public `:rf/route` read, derives them through one
pure projector, and permits runtime-db caching only as a reconstructible,
non-authoritative cache. Hydration, epoch restore, and resource settlement must
reconcile through the projector.

The alternative removes them from stored `:rf/route-slice` and joins them only
at the subscription/tool boundary. That eliminates a cache but requires a
durable active-plan descriptor immediately. Acceptance must choose now; R0 and
R1 must not discover or revise the public/read schema while implementing it.

### 3. Should direct leave bypass remain public?

The recommendation is the explicit boolean `:bypass-leave? true`: re-frame2
trusts the programmer, and rare workflow code may know it has already handled
the consequence that `:can-leave` represents. `:rf.route/continue` remains the
normal confirmation path.

The smaller alternative makes bypass runtime-internal and requires application
code to change the guard's source state before navigating. The recommended
acceptance ruling keeps the public boolean. The set-valued `:bypass-guards?` and
any entry bypass are removed in either case.

### 4. Does `:parent` itself opt into ancestor resources?

The recommendation is automatic parent-to-leaf resource composition:
`:parent` declares membership in the active branch, so shared requirements
declared by that branch apply without a second marker. It keeps common
registrations terse and prevents a forgotten flag from making navigation,
prefetch, and SSR disagree with the rendered parent shell. The effective-plan
projection and the redundant-child advisory on `:rf.resource/route-plan` keep
the inherited contribution and accidental child copies mechanically visible.

The alternative requires `:inherit-resources? true` on each child. It preserves
the runtime behavior of existing `:parent` registrations and makes the choice
visible locally, but duplicates the branch relation and makes every descendant
an omission point. Acceptance must explicitly choose; this EP recommends that
the pre-alpha project take the coherent semantic expansion and treat `:parent`
as the opt-in.

## References

- [EP-0009 — The EP Process](EP-0009-the-ep-process.md)
- [EP-0003 — Resource Queries](EP-0003-resource-queries.md)
- [EP-0016 — Resource Mutation Completion, Scoped Invalidation Targets, And Named Scope Resolution](EP-0016-resource-mutation-completion.md)
- [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md)
- [`spec/011-SSR.md`](../../spec/011-SSR.md)
- [`spec/012-Routing.md`](../../spec/012-Routing.md)
- [`spec/016-Resources.md`](../../spec/016-Resources.md)
- [`spec/Spec-Schemas.md`](../../spec/Spec-Schemas.md)
- [TanStack Router — data loading](https://tanstack.com/router/latest/docs/guide/data-loading)
- [TanStack Router — external data loading](https://tanstack.com/router/latest/docs/guide/external-data-loading)
- [TanStack Router — preloading](https://tanstack.com/router/latest/docs/guide/preloading)
- [TanStack Router — search params](https://tanstack.com/router/latest/docs/guide/search-params)
- [TanStack Router — link options](https://tanstack.com/router/latest/docs/guide/link-options)
- [React Router — routing](https://reactrouter.com/start/framework/routing)
- [React Router — data loading](https://reactrouter.com/start/framework/data-loading)
- [React Router — pending UI](https://reactrouter.com/start/framework/pending-ui)
- [React Router — navigation blocking](https://reactrouter.com/how-to/navigation-blocking)
- [React Router — `Link`](https://reactrouter.com/api/components/Link)
- EP authoring record: bead `rf2-o0dc1`. The implementation programme is
  intentionally not created until this proposal has been audited and accepted.

# EP-0037: Route Planning and Activation Ownership

Status: proposal
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
5. **The active branch contributes requirements, not rendering.** Parent routes
   may contribute managed resources. They do not acquire an outlet, component,
   context-provider, or implicit view lifecycle.
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

The accepted policy keys are `:replace?`, `:scroll`, and the proposed
leave-only `:bypass-leave?`. The current set-valued
`:bypass-guards? #{:leave :enter}` is retired: entry has no resumable/bypass
protocol, and a set is needless machinery for the one remaining case.

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

This EP does not require a public `RoutePlan` constructor or promise-returning
router object. The plan is an implementation and tooling seam. Its observable
laws are public; its diagnostic projection is visible in trace/Xray.

### One planning pipeline for every door

Every navigation door must lower to the same ordered stages:

| Stage | Result | Failure/short-circuit |
|---|---|---|
| 1. Validate intent | accepted address/raw URL/in-place edit plus policy | malformed request rejects before any guard or history effect |
| 2. Resolve target | canonical target, URL, named address when possible, parent branch | schema/URL failures follow the existing not-found or caller-error contract |
| 3. Decide leave | allow, or a leave-only pending navigation | pending leave preserves the current route; popstate restores its URL |
| 4. Decide entry | allow, or terminal entry denial | denial commits no target and creates no pending entry |
| 5. Classify transition | full, fragment-only, or no-op | fragment-only/no-op retain the existing nav-token/resource plan rules |
| 6. Build activation plan | effective branch resource plan and old/new identity diff | planning failure produces a failed plan; no partial resource plan executes |
| 7. Commit and activate | route facts/plan ownership, history/scroll effects, resource ensures, activation events | stale completions remain fenced by token/generation |

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
resource ensure from the invalid plan runs. The target's fire-and-forget
`:on-match` events still run because the route did activate; they cannot silently
settle or replace the planning error. This gives client and SSR error views a
stable target while retaining the all-or-nothing resource-plan law.

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

Planning follows these rules:

1. Resolve the branch parent first. Existing unknown-parent and parent-cycle
   errors remain fail-loud.
2. Within each contributor, retain declaration order and resolve local `:id` /
   `:after` edges exactly as Spec 016 requires. An `:after` edge may name only an
   id declared by the same route. Parent and child local ids never share a
   namespace. Parent contribution order already puts every parent ensure before
   every child ensure; no cross-route `:after` edge is needed.
3. Evaluate `:when`, then resolve params and scope against the target/frame and
   validate them. Every contributor's route function receives the resolved
   **leaf target** as its `route` argument; the planner records the contributor
   separately. A failure aborts the whole resource plan before any ensure is
   dispatched, then commits the failed activation described above. The error
   identifies both the contributor route and resource declaration.
4. Materialize a requirement containing contributor route id, local id,
   declaration index, resolved scoped resource identity, `:blocking?`, and
   `:keep-previous?`.
5. Group requirements by canonical scoped resource identity. Dispatch one
   `ensure` per identity. This is identity dedupe, not a generic route-metadata
   merge.

When more than one requirement resolves to the same identity:

- the earliest parent-to-leaf/topological occurrence fixes its dispatch
  position;
- the work is blocking when any contributor marks it blocking;
- `:keep-previous?` is true when any contributor requests it; and
- every contributor remains present in the diagnostic plan, so dedupe does not
  erase why the resource is needed.

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

`:on-match` remains a vector of ordinary event vectors dispatched in order when
the matched leaf activates or its data-bearing params/query change. It is useful
for synchronous seeding, analytics/host notification, and application-owned work
that naturally begins at activation.

Its contract is fire-and-forget:

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

A background refresh, a non-blocking first load, a prefetch, and arbitrary
`:on-match` work do not change route transition. Their honest status remains on
their owning resource/application read model.

This projection must have one pure implementation used by subscriptions,
SSR wait/render decisions, Xray, and any cached route-slice fields. An
implementation may cache the projection in runtime-db for efficient updates, but
the cache is not independent authority and must be reconstructible from the
active plan plus managed-resource/planning state. Hydration and epoch restore
must not preserve a route `:loading` value that the restored resource state
contradicts.

The existing `:rf/route` read shape may continue to include `:transition` and
`:error` for ergonomic whole-route reads. Whether those two fields remain stored
inside `[:rf.runtime/routing :current]` or are joined at the subscription
boundary is an open issue below; their observable semantics are fixed here.

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

- every ensure is ownerless and carries a prefetch cause;
- warmed entries remain governed by ordinary resource freshness, dedupe, and GC;
- `:blocking?` does not block anything;
- no route state, nav-token, URL, history, scroll, focus, or pending navigation
  changes;
- no `:can-leave`, `:can-enter`, or `:on-match` runs;
- resource failures remain resource failures and traces; they do not become
  route errors; and
- a later activation reuses fresh/in-flight work and attaches its real owner
  before normal activation proceeds.

An invalid prefetch address rejects before planning with the operation-specific
`:rf.error/prefetch-bad-address`. A resource-planning failure emits the ordinary
structured route/resource planning diagnostic with cause `:prefetch`, dispatches
no partial ensures, and does not alter current route readiness.
When the Resources artefact is absent, the effective warm plan is empty and the
event performs no work; prefetch does not make Resources a mandatory routing
dependency.

Prefetch is a performance hint, not an authorization boundary. Resource requests
must already enforce scope and server authorization. Running entry guards during
warmup would make prefetch a partial navigation and would still not be a security
boundary.

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
the keyboard selection).

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
through a late-bound seam after a real bundler/consumer proves the contract; no
`:load` metadata key is reserved as public API now.

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
 :requested-url   "/"
 :rejecting-route :route/editor
 :rejecting-guard :editor/can-leave?
 :url-restored?   false}
```

`:rf.route/continue` and `:rf.route/cancel` apply only to this leave slot.
Continue clears the slot and executes the stored `RouteDestination` through the
normal pipeline with a one-shot leave bypass; entry is still evaluated normally.
Cancel clears the slot and stays. A blocked popstate restores the current URL as
today.

A non-boolean leave result fails closed and emits the existing structured
programmer error. The public `:bypass-leave? true` policy is an explicit trusted
programmer escape for the rare direct-navigation case; the ordinary confirmation
path uses `continue`.

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

For a group of protected routes, application code may use a small route-metadata
helper that associates the same `:can-enter` subscription with each
registration. The subscription receives the resolved target and may inspect its
route id/tags. This is the canonical cross-route recipe; the router does not add
a middleware chain merely to avoid an ordinary map helper. General registered
interceptors remain available for application-wide event policy, but routing
correctness never depends on attaching equivalent logic separately to every
navigation door.

Initial load and SSR take the same denial path. Because re-frame drains the
denial handler before render, the canonical redirect handler can establish the
login route in the same request/frame. If the application intentionally performs
a hard denial and dispatches no replacement navigation, the protected route
remains uncommitted; the host/application owns the resulting shell or HTTP
response policy.

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

Deleting `:query-retain` does not change query parsing, selective keywording,
defaults, validation, `+` semantics, or fragment behavior except that retained
keys no longer expand the declared keyword vocabulary.

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

The existing `:rf.resource/route-plan` trace is extended rather than inventing a
parallel graph. Prefetch emits one `:rf.route/prefetched` summary trace; its
plan/ensure traces carry cause `:prefetch`. The underlying ensures retain their
normal resource traces. Entry denial gets one named event/trace. Removed
on-match correlation traces and error-attribution fields do not survive as
deprecated aliases.

All diagnostic values cross the existing projection/elision boundary. A trace
must not turn a sensitive URL/query/resource declaration into a new egress leak.

### Conformance obligations

Graduation requires executable proof of the following:

1. **Address parity.** One table of valid/invalid `RouteAddress` values drives
   `route-url`, navigation, both route-link implementations, named entry/pending
   payloads, and prefetch on JVM and CLJS. The raw destination branch is tested
   separately. Unknown keys and malformed combinations fail identically.
2. **Door parity.** Named address, matching raw URL, link, programmatic,
   popstate, initial, and SSR inputs resolve to the same target/branch/resource
   plan. Only cause-specific history and scroll effects differ.
3. **Passive render.** Rendering a route link or routed view dispatches no
   navigation, prefetch, resource ensure, or application event. Intent
   interaction dispatches at most the expected prefetch/navigation events.
4. **Branch composition.** Parent-to-leaf order, local `:after` scope,
   `:when`, params/scope failure, duplicate identity policy, and contributor
   diagnostics are pinned.
5. **Partial activation.** Sibling-leaf navigation retains unchanged parent
   identities, attaches before release, does not abort shared in-flight work,
   ensures newly added identities (including a requirement whose resolved
   identity changed), and releases removed identities.
6. **Readiness honesty.** No-resources, blocking first load, non-blocking load,
   cached fresh data, background refresh, planning failure, first-load failure,
   supersession, restore, and hydration all project the specified transition and
   error. `:on-match` never changes it.
7. **Prefetch isolation.** Prefetch runs the full effective resource plan
   ownerlessly, dedupes repeated intent, remains GC-eligible, reuses work on
   activation, and runs no guard, URL, route-state, scroll, `:on-match`, or SSR
   side effect.
8. **Guard parity.** Leave and entry decisions cover link, programmatic,
   popstate, initial, and SSR doors. Leave alone creates pending state. Entry
   denial never does. Popstate restores the URL; a post-login return is a fresh
   address navigation.
9. **Query explicitness.** `:query-retain` is rejected at registration; defaults
   and in-place merge remain; every cross-route carried key appears in the
   authored address after the application's pure carry helper runs.
10. **Frame isolation and teardown.** Plans, owners, prefetch work, pending leave
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
| **R0 — address and plan spine** | `:rf/route-address`, shared address extraction/validation, one resolved-target/plan seam used by every door, diagnostic plan projection; existing behavior otherwise preserved | accepted EP |
| **R1 — honest activation** | `:on-match` fire-and-forget; resource-derived transition/error; removal of global on-match correlation and route `:on-error`; SSR wait semantics corrected | R0 |
| **R2 — branch resource plan** | parent-to-leaf resource composition, fixed identity dedupe, plan diff, attach-before-release, partial revalidation, SSR/hydration parity | R0, R1 |
| **R3 — intent prefetch** | `:rf.route/prefetch`, route-link `:prefetch :intent`, ownerless full-plan warm ensures, traces and reuse/GC proof | R2 |
| **R4 — terminal entry** | leave-only pending protocol, terminal `:can-enter`, `:rf.route/entry-denied`, fresh auth return, all-door/SSR coverage, obsolete resume/loop machinery removed | R0 |
| **R5 — explicit query carry and surface cleanup** | remove `:query-retain`, preserve defaults/in-place edits, migration recipe, reject obsolete metadata/policy keys, final API/schema/error inventory | R0 |
| **R6 — integrated proof** | all conformance rows and guides coherent; RealWorld or equivalent routed app proves parent shell reads, leaf reads, intent warmup, auth denial/return, SSR, and no render-caused work | R1–R5 |

R2 and R4 may proceed independently after R0 if they do not edit the same
planner code concurrently. R3 waits for the effective plan because prefetching a
leaf-only approximation would make the temporary behavior the wrong contract.
R5 may land earlier when repository usage is known, but it must include the
explicit carry recipe in the same cut.

Future beads should be cut smaller than these rows where useful, but every bead
must name the vertical outcome, exact spec/conformance clauses, affected hosts,
migration/deletion work, and commands/evidence that close it. Beads are created
after this proposal is audited and accepted; this EP does not pre-create or
pretend to resolve their implementation details.

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
fresh-entry and resumable-leave recipes. Resource guides teach route plans as
owner/cause declarations, and SSR guides teach blocking resources as the only
route-owned wait point. “Coming from” guides explain the adopted capabilities
without importing outlet, hook, cache, action, or middleware ownership.

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

### Branch data composition need not imply branch rendering

Shared layouts commonly need shared data. Repeating the same resource
declaration in every child is noisy for humans, hostile to mechanical review,
and makes prefetch incomplete unless every copy stays aligned.

The existing `:parent` chain already names the relevant branch. Folding only
`:resources` along it yields the high-value behavior while preserving ordinary
Clojure view composition. A generic metadata fold would force merge laws for
head, scroll, tags, guards, events, and future keys that have different
semantics. The fixed resource-only fold avoids that abstraction trap.

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
| Inherit all parent metadata | rejected; unrelated keys need incompatible merge/ordering rules |
| Make `:on-match` an awaited promise/loader API | rejected; creates a second async/cache model and makes events return router-owned data |
| Keep global event-error correlation | rejected; event-vector coincidence is not causal ownership |
| Remove `:on-match` entirely | rejected; ordered activation events are a small, useful event-native seam |
| Put route data in a router cache | rejected; Resources is the one server-read owner |
| Trigger ensures from views | rejected; violates passive render and loses SSR/prefetch planning |
| Keep resumable entry and improve its loop handling | rejected; the machinery treats a fresh policy decision as continuation |
| Return a closed allow/redirect/deny value from entry policy | viable future refinement; see Open Issue 1 |
| Run entry guards during prefetch | rejected; warmup is not activation or an authorization boundary |
| Add render/viewport/default prefetch modes now | deferred pending measured demand |
| Keep `:query-retain` or add generic search middleware | rejected; final addresses become ambient transformations |
| Add nested address/policy envelopes | rejected for the public paved path; internal schemas already preserve the distinction |
| Add Outlet/NavLink/masking/file routes/actions for parity | rejected; each imports ownership that conflicts with ordinary subscriptions, events, resources, and view composition |
| Expose the whole plan as a public promise API | deferred; traces/tests need a data projection, applications do not yet need an executor |

## Backwards Compatibility

The project is pre-alpha, so this is a direct contract migration with no
deprecated aliases:

- existing named destination maps already use the future `RouteAddress` fields;
  they gain a schema/name and shared validation rather than call-site ceremony;
- old `:query-retain` metadata fails loudly; applications replace it with an
  explicit address helper or event/interceptor;
- `:query-defaults`, query schemas, destination `:query`, in-place `:query`, and
  in-place `:query-merge` remain;
- `:on-match` declarations remain valid, but no longer drive route
  transition/error or SSR asynchronous waiting;
- route `:on-error` is removed; managed page-read errors move to resource
  projections, and application-owned work keeps application-owned error state;
- a parent's `:resources` now applies to descendants. Child copies resolving to
  the same identity dedupe, but applications should delete redundant copies so
  the plan has one clear contributor;
- `:rf.route/entry-blocked` becomes `:rf.route/entry-denied`; enter-shaped
  pending values, `continue`-after-login, attempt counters, loop errors, and
  enter bypass are removed;
- pending navigation becomes leave-only and stores
  `:destination`/`:target`/`:cause` instead of the original event as its primary
  replay description;
- `:bypass-guards?` becomes the boolean `:bypass-leave?`;
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

No operator decisions have been recorded yet. The normative-voiced content above
is the proposal's recommended contract and binds nothing until acceptance.

## Open Issues

### 1. Boolean `:can-enter` plus denial event, or a closed terminal decision?

The recommendation is the specified boolean subscription plus
`:rf.route/entry-denied`. It preserves the current state-shaped guard, keeps
causal redirect work in an event, and is sufficient for auth, feature flags,
maintenance denial, client navigation, and SSR.

The alternative is to replace `:can-enter` with a pure closed result such as
`:allow`, `{:redirect RouteAddress :replace? true}`, or
`{:deny reason}`. It would make initial/SSR redirect terminal without relying on
an event handler, but adds a policy algebra, reason/redirect schemas, recursion
rules, and another place that causes navigation. Acceptance should rule whether
a demonstrated SSR hard-redirect need justifies that extra surface now.

### 2. Where do the readiness projection fields live?

The observable transition/error table is not open. The storage choice is.

The recommendation is to keep the ergonomic fields in the public `:rf/route`
read while deriving them through one pure projector. The implementation may
initially cache those values in the current route slice if resource callbacks,
restore, and hydration all reconcile through that projector. The cleaner
pre-alpha alternative removes them from stored `:rf/route-slice` and joins them
only at the subscription/tool boundary, which eliminates duplicate authority but
requires a durable active-plan descriptor. The first implementation slice should
measure which shape makes SSR/restore and Resources artefact absence simpler,
then the operator should lock the schema before R1 graduates.

### 3. Should direct leave bypass remain public?

The recommendation is the explicit boolean `:bypass-leave? true`: re-frame2
trusts the programmer, and rare workflow code may know it has already handled
the consequence that `:can-leave` represents. `:rf.route/continue` remains the
normal confirmation path.

The smaller alternative makes bypass runtime-internal and requires application
code to change the guard's source state before navigating. Acceptance should
choose one; the set-valued `:bypass-guards?` and any entry bypass are removed in
either case.

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

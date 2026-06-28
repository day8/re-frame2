# EP-0013: App Values And Runtime Realms

Status: superseded-by EP-0023
Type: standards-track

> This EP defines the long-term architecture in which the application program
> and runtime environment are explicit values. Existing process-global
> registration remains as compatibility sugar over a default runtime realm.
>
> **Ruling recorded 2026-06-11 (Mike, in-session).**
> Accepted, with the severable matrix the EP requires: **D1 (realm container)
> — adopt. D2 (app value) — adopt, sequenced behind D1. D3 (module manifests)
> — adopt the shape; the public surface stages last.** All thirteen open
> issues are dispositioned in [§Open Issues](#open-issues), merging three
> convergent analyses. Headline corrections to the original recommendations:
> the constructor vocabulary is **`rf/realm`, never `rf/runtime`** ("runtime"
> is the corpus's most overloaded word — an EP-0007 hazard all three analyses
> flagged independently); there is **no day-one public facade** — the names
> are reserved vocabulary and exposure graduates internal-first per the
> corpus's proven discipline; and replacement-batch identity must be
> reload-stable and **auto-minted by dev tooling**, never line/column-based.
> Implementation is tracked by the EP-0013 action epic, **gated behind the
> current EP wave epics**, with the standard wave tails. The `:rf.realm/id`
> slot reservation in the EP-0010/0011/0016 record shapes is an early,
> ungated bead.
>
> Normative home after acceptance: registration, frames, reactive substrate,
> runtime subsystem, adapter, and conventions specs.
>
> **Graduated `accepted → final` 2026-06-12 (Mike, in-session).** The decisions
> are settled and the normative homes above govern (where this EP and the spec
> differ, the spec governs). The wave + final-review returned
> clean — the wave is correct and complete against the dispositions, the prior
> review tails (correctness ×2, testing-coverage, code-comments) ran clean, and
> the one completeness gap found (the eight thrown `:rf.error/*` categories
> needed Spec 009 Error-event catalogue rows) was fixed in the corrective PR.
> `final` asserts the **decisions are settled**, not that the whole D1/D2/D3
> build is complete: what shipped and what remains build-not-decision is recorded
> in the [§Implementation errata](#implementation-errata) ledger below. Realm-aware
> **live-dispatch routing** has since **shipped** — a constructed
> realm owns the registrar that event / sub / fx / cofx resolution routes against,
> the runtime resolves a frame's handlers from the owning frame's realm registrar,
> and `:rf.realm/id` is carried beside `:rf.frame/id` on the dispatch envelope.
>
> **Partially superseded by EP-0023 (Mike-ruled 2026-06-16).** EP-0023 establishes `image -> frame -> event stream`
> as the public model, so EP-0013's status is `superseded-by EP-0023`. The
> supersession is partial — it is a public-surface replacement, not a teardown:
>
> - **Carries forward (retained):** EP-0013's isolation decisions and the D1
>   runtime-realm machinery as the internal installation/ownership substrate —
>   registrar container, adapter/capability owner, frame registry, host-transient
>   owner, disposal boundary, and the default-registration path. The realm-aware
>   live-resolution invariant carries forward, restated at the
>   frame boundary: the targeted frame's resolved image generation determines the
>   registration universe.
> - **Replaced (public surface):** the beginner-facing app/realm teaching surface
>   and `(realm, frame)` addressing. The public value becomes `rf/image`; public
>   operations target a frame; `rf/app` / `rf/module` re-express as image values /
>   image fragments. See EP-0023 §"Backwards Compatibility And EP-0013 Partial
>   Supersession" for the full surface-disposition table.

## Implementation errata

The EP decisions are final. The EP-0013 wave shipped **D1 (the realm container)**
end-to-end and the **D2 (app-value)** projection/construction/install surface, and
realm-aware **live-dispatch routing** has since shipped on top; the
remaining build-not-decision work is the **D3 module-manifest** public surface, and
`final` does not claim it shipped. This ledger records what shipped against the
dispositions and what stays build-not-decision — the EP-0010 precedent, where a
settled decision's later slice is recorded as deferred, not as an open question.
Verified 2026-06-12 against the merged implementation, the realm/app-value test
suites, the spec graduation, and the final-review verdict; the
live-dispatch-routing entry re-verified 2026-06-15 against the merged
implementation and the `/spec` correction (#4272).

### Shipped

- **The runtime-realm container (D1) — SHIPPED.** The realm record owns the
  registrar (its own `(kind, id) → metadata` atom — hermetic by default), the
  adapter selection + capability map, the frame registry, the host-transient
  inventory, and disposal state (`re-frame.realm`). The implicit default realm is
  created at process load, never disposed, and its seating path is byte-identical
  to the pre-EP global registrar — a single-realm app never spells a realm. The
  container is graduated in
  [Runtime-Subsystems §Runtime realms](../../spec/Runtime-Subsystems.md#runtime-realms--the-container)
  and the realm-record shape in
  [Spec-Schemas §`:rf/realm`](../../spec/Spec-Schemas.md).
- **App-value projection + construction + install/reinstall (D2 surface) —
  SHIPPED.** The default realm's registrations project to an immutable app value
  (`re-frame.app_value`); `rf/app` / `rf/module` compose descriptors with
  deterministic same-kind same-id collision diagnostics; `rf/install!` runs the
  capability check (`:rf.error/missing-capability`) before any registrar mutation,
  then lowers descriptors and records the seated app at the realm boundary;
  `rf/reinstall!` diffs the new app and applies the `:added` / `:changed` /
  `:removed` delta as a descriptor-only hot-reload slice.
- **Both reinstall refusals + the kind boundary — SHIPPED.** `reinstall!` binds
  refuse-loudly at the **kind boundary in both directions** (issue 12 errata): the
  step-8-deferred kinds throw `:rf.error/unsupported-descriptor-kind` on the
  add/changed path (`install-descriptor!`) **and** the removal path
  (`refuse-unsupported-removal!`), and a `:removed` `:frame` whose
  live container still exists is refused loudly
  (`:rf.error/live-frame-removal-unsupported`, enumerating the blocking frame-ids)
  rather than silently orphaned.
- **The public realm API — SHIPPED.** `rf/realm` (the reserved-vocabulary
  constructor — ruled `rf/realm`, **never** `rf/runtime`, issue 1) constructs +
  registers a hermetic realm; a duplicate id throws `:rf.error/realm-id-conflict`.
  `rf/dispose-realm!` is its teardown counterpart. `rf/realm-ids` enumerates the
  live realms and `rf/frame-realm` returns a frame's realm — the realm-enumeration
  half of the `(realm, frame)` addressing model (issue 3); `rf/installed-app`
  reads a realm's installed app value as the LIVE registrar projection enriched
  with any seated app's module provenance — so coexisting `reg-*` sugar stays
  visible and the public read never desyncs from the registrar;
  and the realm-targeted registrar queries
  take the map-shaped `{:realm … :kind …}` form (issue 11). Eight thrown EP-0013
  `:rf.error/*` categories carry Spec 009 Error-event catalogue rows (the
  final-review corrective).

### Erratum — module/app fact keys are owner-qualified (`:rf.module/*` / `:rf.app/*`)

- **The module form's `:owns` / `:requires` keys are owner-qualified to
  `:rf.module/owns` / `:rf.module/requires`; the app value's union key is
  `:rf.app/requires` (2026-06-14).** The exploratory examples in
  this EP below (§The Shape At A Glance, §App Values, §Module Values And Feature
  Ownership, and the worked snippets) spell the module ownership and capability
  facts with the BARE keys `:owns` / `:requires` and the app union as `:requires`.
  Those spellings are **superseded.** Per [EP-0007 one-name-per-fact](EP-0007-one-name-per-fact.md)
  and the [EP-0017](EP-0017-recordable-coeffects.md) v5 ruling (Mike, 2026-06-12)
  — *structural/section keys stay bare; FACT keys get owner-qualified* — these
  are facts ABOUT the module/app, so the **KEYS** are owner-qualified (the values
  were already `:rf.capability/*`-qualified):
  - module ownership declaration: `:owns` → **`:rf.module/owns`**;
  - module capability requirement: `:requires` → **`:rf.module/requires`**
    (parallel to `:rf.cofx/requires` over coeffects — naming the contract);
  - app-value union capability set: `:requires` → **`:rf.app/requires`**
    (parallel to `:rf.app/id`).

  The structural section keys (`:id`, `:events`, `:subs`, `:routes`, `:source`,
  `:modules`, `:registrations`) stay **bare**. The shipped constructor
  (`re-frame.app-value/module` / `app`) and the normative spec
  ([API §App values and composition](../../spec/API.md#app-values-and-composition-ep-0013),
  [Conventions §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned)
  — the `:rf.module/*` + `:rf.app/*` rows) carry the qualified spelling; **where
  this EP's illustrative snippets and the spec differ, the spec governs.** The
  EP text is retained for design rationale; read the bare keys in the snippets
  below as the qualified spelling above.

### Shipped — realm-aware live-dispatch routing

- **Realm-routed LIVE dispatch / subscribe / fx / cofx — SHIPPED
  (staging step 4).** A constructed realm now owns the registrar
  that **event / subscription / fx / cofx resolution routes against**: the runtime
  resolves a frame's handlers from the **owning frame's realm registrar**, with
  `:rf.realm/id` carried beside `:rf.frame/id` on the dispatch envelope and
  `install!` binding the realm registrar during seating. `reg-frame` (reached
  through `install!` under the realm binding) **stamps** the frame with the target
  realm and keys live frames by `(realm, frame-id)` (a bare id for the default
  realm), so the EP conformance point "frames resolve handlers from their owning
  realm" is **met**. The earlier fail-closed `:frame`-into-non-default-realm
  refusal (`:rf.error/realm-frames-unsupported`) is **lifted** — `install!` /
  `reinstall!` now seat `:frame` descriptors into the target realm. (The per-kind
  live-instance blocker/continue/migrate rule for the step-8 kinds remains an
  acceptance criterion of each kind's own installability bead, per the §Step-8
  installability obligation above; lifting a step-8 kind's
  `:rf.error/unsupported-descriptor-kind` throw is still future work.) This
  graduated exactly as EP-0010's `:rf.world/uuid` recordable coeffect was a
  settled-then-built slice: the decision was always settled (the realm IS the
  operational container; the wire shape of the realm stamp is `:rf.realm/id`
  carried beside `:rf.frame/id`), and the build has now landed. The `/spec`
  copies were corrected by #4272
  ([API §App values and composition](../../spec/API.md#app-values-and-composition-ep-0013),
  [Spec-Schemas §`:rf/realm`](../../spec/Spec-Schemas.md#rfrealm-runtime-realm-ep-0013),
  [Runtime-Subsystems §What a realm owns](../../spec/Runtime-Subsystems.md#what-a-realm-owns));
  where this EP and the spec differ, the spec governs.

## Abstract

The program is a value; the runtime is a container you install it into.

re-frame2 already treats durable state as a value and effects as data. The
remaining large ambient surface is the program and the runtime environment that
interprets it: the handler registrar, installed adapter, late-bound function
table, capability lookups, and host-transient subsystem state are still mostly
process-global.

This EP introduces two explicit values:

- an **app value**, which is an immutable description of a program or feature
  slice: events, subscriptions, flows, machines, resources, mutations, routes,
  schemas, views, effects, coeffects, ownership claims, capability requirements,
  and source coordinates;
- a **runtime realm**, which is the operational environment into which an app
  value is installed: registrar, adapter, capability map, host-transient
  subsystem registry, frame registry, and lifecycle.

The current `reg-*` APIs remain valid. They are reinterpreted as default-realm
sugar: ordinary namespace-load registration updates the app value installed in
the process default realm. Existing applications can migrate mechanically and
incrementally, while new large-SPA code can compose, inspect, install, test,
diff, and hot-reload programs as values.

## Problem Statement

The current architecture isolates frame state but not behavior. All frames share
one process-global registrar, and the installed reactive adapter is a process
singleton. Optional artifacts and cyclic namespace edges are connected through a
global `late_bind` hook table. Several subsystems also hold host-transient
state in process-level or frame-keyed side tables.

The cost is measurable in the reference implementation today. The registrar is
one process-wide `kind->id->metadata` atom over a closed kind set; Spec 002
states the posture as a law ("Frames isolate *state*, not *behaviour*") and
declares multi-app pages out of scope ("iframes already serve it"). The adapter
is a process-wide install slot whose second install raises
`:rf.error/adapter-already-installed`. The late-bind directory inventories
roughly 175 published hook keys and needs a dedicated drift test to keep the
table honest. Several hundred test files reach for `clear-all!` or a
`reset-runtime`-style fixture purely to repair shared program state between
cases. Each of those mechanisms is interest paid on program-as-mutation.

Those choices are pragmatic. They preserve familiar re-frame v1 ergonomics,
make small examples terse, and reduce early API surface. They are also the
largest remaining architectural smell for a framework intended to maintain
large SPAs:

- two products in the same browser process cannot naturally have different
  handler graphs;
- white-label tenants, independently shipped feature packs, and migration
  windows collide on the same global ids;
- tests need fixtures that clear or repair global registrar state;
- hot reload is expressed as registrar mutation instead of a diff between old
  and new program values;
- source coordinates and ownership metadata must be re-harvested from load
  order rather than read from a canonical program artifact;
- optional capabilities are discovered by temporal namespace load rather than
  passed as dependencies;
- the single-adapter-per-process rule blocks legitimate multi-root evolution
  such as a legacy Reagent root next to a new UIx root;
- tooling can inspect the current registrar but cannot ask "what app value would
  be installed if these feature modules were composed?"

The problem is not that global registration exists. The problem is treating it
as the architecture instead of a compatibility and convenience layer.

## Motivation

Large SPAs need to answer questions that scattered registration makes
unnecessarily indirect:

- Which feature owns this app-db path, route, resource, or effect?
- Which events can mutate a path?
- Which subscriptions, flows, and views depend on it?
- Which runtime capabilities does this feature require?
- Can two versions of the feature run side by side?
- What will collide if this feature pack is installed?
- Can a test install the program without clearing global state?
- Which hot-reload save changed which handlers, caches, and active processes?
- Which source file and line declared the contract a tool is showing?

re-frame2's core value proposition is explicit data at the places where large
systems otherwise become temporal and implicit. A frame is a value-bearing
runtime boundary. An event handler returns an effect map. Runtime-db separates
framework-owned durable state from app-db. This EP extends that same discipline
to the program and runtime environment themselves.

The design posture is pre-alpha: preserve mechanical migration where it does
not compromise the model, but prefer the correct long-term architecture over
inherited v1 ambient behavior.

## Goals

- Make the application program inspectable, composable, diffable, installable,
  and testable as data.
- Define runtime realms as explicit owners of registrar, adapter, capabilities,
  frame registry, and host-transient subsystem state.
- Preserve existing `reg-*` ergonomics through a default realm.
- Allow multiple independent runtime realms in one JS process.
- Permit adapter ownership at realm or render-root granularity, while keeping
  the current single-adapter-per-process implementation as a compatibility
  strategy.
- Make feature modules first-class data with ownership claims and collision
  detection.
- Preserve source-coordinate capture in app values and registrar queries.
- Reframe `late_bind` as compatibility and optional-load plumbing, not the
  conceptual dependency model.
- Support hermetic tests by installing an app into a fresh realm with explicit
  capabilities.
- Support hot reload as an app-value diff with precise invalidation.
- Provide a mechanical migration path from re-frame v1-style namespace-load
  registration.

## Non-Goals

- This EP does not remove `reg-*` APIs.
- This EP does not require every application to author explicit module
  manifests immediately.
- This EP does not define a micro-frontend deployment system.
- This EP does not require arbitrary third-party runtime subsystem registration
  before the internal subsystem contract is coherent.
- This EP does not make multiple adapters inside one reactive subtree legal.
- This EP does not require app authors to pass a realm through ordinary event,
  subscription, or view code once a frame scope is established.
- This EP does not finalize all public constructor names. It uses candidate
  names in examples so the proposal is concrete.

## Relationships

- [EP-0001](EP-0001-frame-partitions.md) defines the coherent frame-state value
  realms install and frames own. This EP changes how programs and host
  capabilities are assembled; it does not move app-db or runtime-db out of the
  frame-state partition model.
- [EP-0002](EP-0002-frame-target-resolution.md) and
  [Spec 002](../../spec/002-Frames.md) establish the carried invariant: frame
  identity travels with the causal token; an operation never discovers a frame
  from the ambient world. This EP extends the same rule to realms — a realm is
  carried with the token, owned by the frame, or explicit at the call site,
  never resolved from dynamic scope (see §Rationale). Spec 002's "frames
  isolate *state*, not *behaviour*" law and its "iframes already serve it"
  posture for multi-app pages are exactly the clauses this EP proposes to
  retire.
- [EP-0006](EP-0006-runtime-subsystem-contract.md) owns the five-clause
  contract for durable `:rf.runtime/*` subsystems. This EP complements it
  rather than competing: durable subsystem state stays under runtime-db and
  EP-0006's contract; host-transient side tables gain realm ownership and
  lifecycle here. The host-transient descriptor in §Host-Transient Subsystem
  State should graduate as an extension of EP-0006's grading table, not as a
  second contract.
- [Spec 001](../../spec/001-Registration.md) owns the registry kind taxonomy
  and registration metadata map that this EP's descriptors normalize; the
  registration *grammar* is unchanged, the registration *semantics* become
  construction of an app value.
- [EP-0003](EP-0003-resource-queries.md) and
  [Spec 016](../../spec/016-Resources.md) define resources and mutations as
  the server-state read/write pair. When the optional resources artefact is
  present, app values carry both `:resource` and `:mutation` descriptors; their
  durable instance state remains under Spec 016's runtime-db and work-ledger
  contract.
- [Spec 010](../../spec/010-Schemas.md) defines `:schema` registration
  metadata and `reg-app-schema` app-db path schemas. This EP may carry schema
  declarations in app values, but current app-db schemas are not registrar kinds;
  installation must write them through the schemas side table unless the owning
  spec changes that storage contract.
- [Spec 006](../../spec/006-ReactiveSubstrate.md) owns the adapter contract.
  This EP changes adapter *ownership* (process → realm/root) but not the
  adapter contract itself.
- [EP-0009](EP-0009-the-ep-process.md) owns the EP process constraints this
  proposal relies on: one decision surface, explicit relationships, and the
  proposal/accepted/final lifecycle. App values may eventually deserve a
  sequence of narrower follow-on EPs rather than one large graduation.
- [EP-0010](EP-0010-causal-world-inputs.md) (final),
  [EP-0011](EP-0011-uniform-async-reply-envelope.md) (accepted),
  [EP-0012](EP-0012-path-optics-and-canonical-forms.md) (accepted), and
  [EP-0014](EP-0014-derivation-and-process-algebra.md) (proposal) are
  companions. EP-0010's injected world inputs are natural realm capabilities
  (`:rf.capability/clock`, `:rf.capability/random` — the coeffect surface those
  capabilities would wrap is now a final contract); EP-0014's derivation
  descriptors would live in app values if it is accepted. Each can stand
  without this EP, and this EP can stand without them.
  [EP-0016](EP-0016-resource-mutation-completion.md) (accepted) supplies the
  first live named-declaration registrar precedent — `reg-resource-scope`,
  whose `{:inputs … :resolve …}` grammar EP-0012's disposition 3 pins as the
  shape an app-value row would carry.
- [EP-0015](EP-0015-frame-owned-egress-policy.md) proposes the policy model for
  frame-owned durable egress policy and registration-owned transient payload
  policy. If accepted, those policy declarations are natural app-value/realm
  material; this EP supplies the eventual home, not the egress-profile semantics.

## Definitions

**App value**

: An immutable Clojure/ClojureScript value describing a whole program or a
  composable slice of one. It includes registrations, ownership declarations,
  capability requirements, feature metadata, and source coordinates. It is not
  the running process; it is the program artifact that can be composed and
  installed.

**Module value**

: An app-value fragment representing a feature, library, product slice, or test
  fixture. A module can be composed with other modules to form an app value.

**Registration descriptor**

: The normalized app-value entry for one registered thing: kind, id, handler or
  value, metadata, owner module, source coordinate, and replacement policy.

**Runtime realm**

: A runtime environment value/record that owns an installed app, registrar,
  adapter capability, capability map, frame registry, host-transient subsystem
  registry, and lifecycle.

**Default realm**

: The process-created realm that backs existing `reg-*`, `dispatch`,
  `subscribe`, `reg-frame`, adapter-install, and registrar-query call shapes
  when no explicit realm is supplied.

**Registrar**

: The `(kind, id) -> registration descriptor` lookup table used by dispatch,
  subscription, effect, coeffect, view, route, flow, frame, and tooling surfaces.
  Under this EP a registrar belongs to a realm and is derived from an installed
  app value. The default realm's registrar is the compatibility surface that
  existing specs call "global".

**Capability map**

: A map of runtime services available to installed app code and framework
  subsystems. Capabilities include adapter functions, HTTP execution, schema
  validation, route integration, clocks, randomness, SSR hooks, and test
  doubles.

**Adapter**

: The reactive substrate implementation used by a realm or render root. The
  current CLJS reference adapter contract remains the substrate boundary; this
  EP changes ownership from process-global as a pattern law to realm/root-owned
  as the long-term shape.

**Host-transient subsystem state**

: Host resources and caches that are not durable frame-state: HTTP abort
  handles, timers, navigation counters, scroll caches, flow last-input caches,
  machine timer handles, and similar side tables. They belong to a realm and
  usually have per-frame entries.

**Install**

: The operation that validates an app value, derives a registrar, attaches it to
  a runtime realm, and makes it available to frames in that realm.

**Reinstall**

: The hot-reload operation that replaces a realm's installed app value with a
  new one by computing a diff and applying the required registrar, cache, and
  lifecycle updates.

**Source coordinate**

: The source namespace/file/line/column metadata already captured by registration
  macros. App values preserve this metadata so composition errors, hot-reload
  diffs, and tools can point to source.

**Late-bind**

: The existing optional-load hook table. It remains useful for compatibility and
  artifact loading, but this EP treats realm capability maps as the conceptual
  owner of dependencies.

## Proposed Solution

The proposal has one central move: make the program and runtime environment
values, then reinterpret current globals as the default instance of those
values.

### Three Severable Decisions

This is the largest EP in the current set, and EP-0009's one-decision-surface
rule applies. The three layers share one vocabulary and are specified in one
document, but they are deliberately severable so each can be ruled on,
accepted, deferred, or rejected independently:

- **D1 — the runtime realm (the container).** A realm owns the registrar,
  adapter, capability map, frame registry, and host-transient subsystem state;
  the default realm preserves today's ergonomics. D1 stands alone: it is the
  hermetic-test and tenancy layer, and it can initially hold today's mutable
  registrar unchanged.
- **D2 — the app value (the program).** Registrations normalize to descriptors
  in an immutable app value; `install!`/`reinstall!` replace registrar mutation
  as the specified contract; hot reload becomes a diff. D2 presumes D1 — an app
  value needs a container to be installed into — but not D3.
- **D3 — module manifests (the source form).** Feature slices are declared as
  composable module values with ownership claims, capability requirements, and
  collision detection. D3 presumes D2. It is the highest-leverage layer for
  large multi-team SPAs and the least urgent; deferring it does not invalidate
  D1/D2.

The §Specification subsections map onto the decisions as follows:

| Decision | Specification sections |
|---|---|
| D1 — realm container | Runtime Realms; Frames Reference Realms; Adapter Ownership; Capability Maps; Late-Bind Compatibility; Host-Transient Subsystem State |
| D2 — app value | App Values; Registration Descriptors; Installation; Default Realm And `reg-*` Sugar; Hot Reload And Reinstall; Source Coordinates |
| D3 — manifests | Module Values And Feature Ownership; Composition |

A valid ruling on this EP MUST record a disposition for each decision:
`adopt`, `defer`, or `reject`. Adopting D1 does not adopt D2 or D3; adopting
D2 does not adopt D3. If a later graduation PR promotes only D1, the remaining
D2/D3 text stays in this EP as deferred rationale and examples, not as an
implicitly accepted public surface. If D1 is rejected, D2 and D3 cannot
graduate from this EP without a replacement container story.

§Public API Staging spans all three: stages 1–4 land D1 internally, stages 5
and 7 land D2, stage 6 spans D2/D3 (the app constructor is D2, the module
constructor is D3), and stages 8–9 close the realm-targeted query surface and
D1's multi-adapter conformance.

### The Shape At A Glance

At the source level, an application can be described explicitly:

```clojure
(def cart-module
  (rf/module
    {:id :shop/cart
     :rf.module/owns {:app-db [[:cart]]
                      :resources [:shop.cart/items]
                      :routes [:shop.route/cart]}
     :rf.module/requires #{:rf.capability/http}
     :events
     {:cart/add
      {:doc "Add an item to the cart."
       :schema [:cat [:= :cart/add] :shop.cart/item]
       :handler cart-add}}
     :subs
     {:cart/items
      {:doc "Current cart items."
       :handler cart-items}}
     :routes
     {:shop.route/cart
      {:pattern "/cart"
       :handler cart-route}}}))

(def shop-app
  (rf/app
    {:id :shop/app
     :modules [auth-module cart-module checkout-module]}))
```

At runtime, the app value is installed into a realm:

```clojure
(def shop-realm
  (rf/runtime
    {:id :shop/realm
     :adapter reagent/adapter
     :capabilities
     {:rf.capability/http http/fetch-capability
      :rf.capability/schemas schemas/malli-capability}}))

(def installed-shop
  (rf/install! shop-realm shop-app))

(rf/reg-frame :shop/main
  {:realm installed-shop
   :on-create [:shop/init]})
```

Existing code remains valid because it targets the default realm:

```clojure
(rf/reg-event-db :cart/add
  {:doc "Add an item to the cart."
   :schema [:cat [:= :cart/add] :shop.cart/item]}
  cart-add)

;; Conceptually equivalent to registering a descriptor in the default realm's
;; app value and reinstalling that default app for future lookups.
```

Frames carry their realm, just as EP-0002 requires them to carry frame identity:

```clojure
(rf/with-frame :shop/main
  ;; The dispatch envelope carries both the frame and the realm implied by it.
  (rf/dispatch [:cart/add item]))

(rf/dispatch [:cart/add item]
  {:realm installed-shop
   :frame :shop/main})
```

The public API can stage toward this gradually. The first implementation can
create an internal realm record and keep all public calls defaulting to it. Later
stages can expose explicit app/module/realm constructors once the internal shape
has proven correct.

## Specification

### App Values

An app value MUST be an immutable value. It MAY be represented by a record for
host efficiency, but it MUST expose a stable data projection for tooling and
tests.

An app value MUST contain, directly or through its modules:

- a stable app id;
- a module map keyed by module id;
- normalized registration descriptors grouped by registry kind;
- ownership declarations for app-db paths, runtime subsystem children, routes,
  resources, mutations, effects, and other public contract surfaces;
- capability requirements;
- source coordinates where the host can supply them;
- composition diagnostics accumulated while constructing the value, or a
  failure value instead of a valid app.

A draft normalized shape is:

```clojure
{:id :shop/app
 :modules
 {:shop/cart
  {:id :shop/cart
   :source {:ns 'shop.cart
            :file "src/shop/cart.cljs"
            :line 12
            :column 1}
   :owns {:app-db [[:cart]]
          :resources [:shop.cart/items]}}}
 :registrations
 {:event
  {:cart/add
   {:kind :event
    :id :cart/add
    :event/kind :db
    :owner :shop/cart
    :metadata {:doc "Add an item to the cart."
               :schema [:cat [:= :cart/add] :shop.cart/item]}
    :handler cart-add
    :source {:ns 'shop.cart.events
             :file "src/shop/cart/events.cljs"
             :line 22
             :column 1}}}
  :sub
  {:cart/items
   {:kind :sub
    :id :cart/items
    :owner :shop/cart
    :metadata {:doc "Current cart items."}
    :handler cart-items}}}
 :requires #{:rf.capability/http}}
```

The exact public projection keys are an implementation detail until the first
public API stage. The normative requirement is that the projection can answer
the same questions without replaying namespace load order.

### Registration Descriptors

Every registrar-backed `reg-*` source form and every explicit module entry
lowers to a registration descriptor. Non-registrar declarations that are still
part of the app contract, such as `reg-app-schema` path schemas, lower to
app-value descriptors owned by their spec; installation writes them to the
owning side table rather than inventing a registrar kind.

A registration descriptor MUST include:

- `:kind`, using the registry kind taxonomy owned by Spec 001;
- `:id`, the public registration id;
- `:metadata`, following the registration metadata map owned by Spec 001;
- the registered handler, value, or factory;
- `:owner`, when the registration came from a module value;
- `:source`, when source-coordinate capture is available;
- replacement semantics for hot reload.

Existing source-coordinate rules remain in force. Macro hosts SHOULD capture
namespace, file, line, and column. Hosts that cannot capture source coordinates
MAY omit them, but app composition and collision errors MUST still report every
available coordinate.

### Module Values And Feature Ownership

A module value is a composable app-value fragment. A module SHOULD declare the
feature surfaces it owns and the runtime capabilities it requires.

```clojure
(def billing-module
  (rf/module
    {:id :billing/invoices
     :rf.module/owns {:app-db [[:billing :invoices]]
                      :paths [:billing.invoice/customer-email]
                      :resources [:billing.invoice/list]
                      :routes [:billing.route/invoice-show]
                      :effects [:rf.http/managed]}
     :rf.module/requires #{:rf.capability/http
                           :rf.capability/schemas}
     :privacy {:sensitive-paths [:billing.invoice/customer-email]}
     :events
     {:invoice/open
      {:doc "Open an invoice by id."
       :schema [:cat [:= :invoice/open] :invoice/id]
       :handler open-invoice}
      :invoice/mark-paid
      {:doc "Mark an invoice as paid."
       :schema [:cat [:= :invoice/mark-paid] :invoice/id]
       :handler mark-paid}}
     :subs
     {:invoice/selected
      {:doc "The selected invoice."
       :handler selected-invoice}}
     :resources
     {:billing.invoice/list
      {:doc "Invoices for the active tenant."
       :query invoice-list-query}}
     :routes
     {:billing.route/invoice-show
      {:pattern "/invoices/:id"
       :handler invoice-route}}}))
```

Ownership declarations are not merely documentation. Composition MUST validate
ownership overlaps that the relevant owning spec can define precisely. For
example:

- same-kind same-id registration conflicts are always errors unless an explicit
  replacement operation is being performed;
- app-db path ownership overlaps SHOULD be rejected when neither module declares
  a parent/child ownership relationship;
- two modules owning the same route id, resource id, mutation id, effect id, or
  schema path MUST produce a precise diagnostic;
- capability requirements MUST be satisfiable by the target realm before
  installation succeeds.

### Composition

Composing app or module values MUST be deterministic. Given the same inputs, it
MUST produce the same app value or the same ordered diagnostics regardless of
namespace load timing.

The composition operation MUST NOT silently use last-writer-wins semantics for
same-kind same-id conflicts.

```clojure
(rf/app
  {:id :shop/app
   :modules [cart-module legacy-cart-module]})

;; Throws or returns an error value equivalent to:
{:error/id :rf.error/app-composition-collision
 :kind :event
 :id :cart/add
 :sources [{:module :shop/cart
            :source {:ns 'shop.cart.events
                     :file "src/shop/cart/events.cljs"
                     :line 22
                     :column 1}}
           {:module :legacy/cart
            :source {:ns 'legacy.cart
                     :file "src/legacy/cart.cljs"
                     :line 91
                     :column 1}}]
 :recovery :rename-or-explicitly-replace}
```

Composition MAY support explicit replacement forms for deliberate overrides,
including hot reload and test doubles. Replacement MUST be visible in the source
form and in the resulting diff; it MUST NOT be an accidental consequence of load
order.

```clojure
(def test-cart
  (rf/module
    {:id :test/cart-overrides
     :replace
     [[:event :cart/add]]
     :events
     {:cart/add
      {:doc "Test double for cart add."
       :handler fake-cart-add}}}))
```

Composition SHOULD expose laws suitable for tests:

- composing with an empty module is identity;
- grouping modules differently does not change the resulting app value;
- successful composition preserves every input descriptor exactly once;
- failed composition reports every colliding source available to the host.

### Runtime Realms

A runtime realm owns the operational resources needed to run an app.

A realm MUST own:

- a stable realm id;
- the currently installed app value;
- the registrar derived from the installed app;
- an adapter or adapter-selection policy;
- a capability map;
- a frame registry for frames in the realm;
- host-transient subsystem state;
- lifecycle/disposal state.

A draft shape is:

```clojure
(def realm
  (rf/runtime
    {:id :tenant-a/realm
     :app tenant-a-app
     :adapter reagent/adapter
     :capabilities
     {:rf.capability/http tenant-a-http
      :rf.capability/routes tenant-a-router
      :rf.capability/schemas schemas/malli-capability}
     :host-transient-subsystems
     {:rf.http/in-flight http-in-flight-descriptor
      :rf.route/nav-counters nav-counter-descriptor
      :rf.machine/timers machine-timer-descriptor}}))
```

Realm ids are unique within a process. Frame ids are unique within a realm. A
single-frame application can continue to use plain frame keywords through the
default realm. Multi-realm code MUST carry enough context to identify both the
realm and frame.

The default realm is created by the runtime. Existing one-argument and
ambient-looking registration APIs resolve through it unless an explicit realm
is supplied. That is registrar/app-value compatibility, not a frame-target
fallback: `dispatch`, `subscribe`, and other frame-scoped operations still need
an explicit frame, a carried frame, or an established frame scope and still fail
per EP-0002 when no frame is known.

### Installation

Installing an app into a realm MUST validate the app before making it visible to
future runtime lookups.

`install!` MUST:

1. validate app composition;
2. validate that the realm satisfies the app's capability requirements;
3. derive the realm registrar from the app value;
4. attach the app and registrar atomically for future lookups;
5. leave in-flight event processing running against the descriptors it already
   resolved;
6. return the installed realm or an installation result containing diagnostics.

```clojure
(def installed
  (rf/install!
    (rf/runtime
      {:id :tenant-a/realm
       :adapter reagent/adapter
       :capabilities {:rf.capability/http tenant-a-http}})
    tenant-a-app))
```

An implementation MAY mutate internal cells during installation. The public
contract is value replacement at the realm boundary, not process-global table
mutation as an architectural primitive.

### Frames Reference Realms

A frame belongs to exactly one realm for its lifetime. The frame registry is
realm-owned. A frame's dispatch envelope, subscription context, trace events,
epoch records, and async continuations MUST carry or recover the realm through
the same carrier that identifies the frame.

```clojure
(rf/reg-frame :tenant-a/main
  {:realm tenant-a-realm
   :on-create [:tenant-a/init]})

(rf/reg-frame :tenant-b/main
  {:realm tenant-b-realm
   :on-create [:tenant-b/init]})

(rf/dispatch [:cart/add item]
  {:realm tenant-a-realm
   :frame :tenant-a/main})
```

Inside a frame scope, existing ergonomic calls remain valid:

```clojure
(rf/with-frame :tenant-a/main
  (rf/dispatch [:cart/add item])
  @(rf/subscribe [:cart/items]))
```

The implementation MUST NOT infer a realm from absence in multi-realm code. If a
frame-scoped operation has neither a carried frame/realm nor an established
scope, it fails with the existing no-frame-context family rather than selecting
an arbitrary realm.

The exact wire shape of the realm stamp is an open issue. It may be an extension
of the existing frame stamp, a realm-qualified frame handle, or an internal
field on the frame record. The normative requirement is that a frame-scoped
operation cannot cross realms accidentally.

### Default Realm And `reg-*` Sugar

The existing public registration APIs remain source-compatible. In the default
case:

```clojure
(rf/reg-event-db :cart/add metadata cart-add)
(rf/reg-sub :cart/items metadata cart-items)
(rf/reg-fx :analytics/send metadata send-analytics!)
```

are sugar for adding descriptors to the default realm's app and reinstalling or
updating the default realm's registrar for future lookups.

A candidate explicit form is:

```clojure
(rf/register! (rf/default-realm)
  {:kind :event
   :id :cart/add
   :event/kind :db
   :metadata metadata
   :handler cart-add})
```

The current return-value convention remains: `reg-*` returns its primary id.

Registrar query APIs MAY keep default-realm arities:

```clojure
(rf/registrations :event)
(rf/handler-meta :event :cart/add)
```

and SHOULD grow realm-targeted forms, map-shaped per open-issue 11's ruling
(unambiguous against the existing keyword arities, and extensible):

```clojure
(rf/registrations {:realm tenant-a-realm :kind :event})
(rf/handler-meta {:realm tenant-a-realm :kind :event :id :cart/add})
(rf/app-registrations {:app tenant-a-app :kind :event})
```

Default-realm sugar exists for migration and ergonomics. It is not the law that
all behavior in a process shares one registrar.

### Adapter Ownership

The current CLJS reference rule, "one adapter per process", MAY remain the first
implementation strategy. This EP changes the pattern-level target: adapter
ownership belongs to a realm or render root, not to the process as such.

```clojure
(def legacy-realm
  (rf/runtime
    {:id :legacy/realm
     :adapter reagent/adapter
     :app legacy-app}))

(def modern-realm
  (rf/runtime
    {:id :modern/realm
     :adapter uix/adapter
     :app modern-app}))
```

The invariant is:

```text
Reactive values created under a render subtree use that subtree's realm and
adapter consistently, and are disposed when the subtree, frame, or realm is
disposed.
```

This EP does not permit mixing two adapters inside one frame's reactive graph
without an explicit bridge. It permits the spec and implementation to evolve
from "single adapter per process" toward "single adapter per realm/root" without
breaking existing apps.

### Capability Maps

A realm's capability map is the explicit dependency surface for runtime services.
Capabilities SHOULD be ordinary maps or records with documented functions and
lifecycle.

```clojure
(def test-http
  {:request! (fn [_request] (deliver canned-response))
   :abort!   (fn [_request-id] nil)})

(def test-realm
  (rf/runtime
    {:id :test/cart
     :adapter plain-atom/adapter
     :capabilities
     {:rf.capability/http test-http
      :rf.capability/clock fixed-clock
      :rf.capability/random seeded-random}}))
```

Modules can declare requirements:

```clojure
(rf/module
  {:id :shop/resources
   :requires #{:rf.capability/http
               :rf.capability/clock}
   :resources {:shop/items items-resource}})
```

Installation fails if required capabilities are absent:

```clojure
{:error/id :rf.error/missing-capability
 :realm :test/cart
 :module :shop/resources
 :capability :rf.capability/http
 :recovery :install-capability}
```

Capabilities make test doubles, SSR services, tenant-specific HTTP clients,
schema validators, and routing hosts explicit without turning them into
process-global state.

### Late-Bind Compatibility

`late_bind` remains a necessary implementation tool for optional artifacts and
cyclic namespace pressure. It SHOULD become a bridge into realm capabilities
rather than the primary dependency model.

Compatibility form:

```clojure
(late-bind/publish! :re-frame.schemas/validate validate!)
(late-bind/lookup :re-frame.schemas/validate)
```

Realm-owned form:

```clojure
(def schema-capability
  {:validate validate!
   :explain explain})

(rf/runtime
  {:id :schema-aware/realm
   :capabilities
   {:rf.capability/schemas schema-capability}})
```

The default realm MAY populate its capability map from late-bound hooks so
existing artifact load order continues to work. New code SHOULD prefer explicit
capabilities where practical.

The smell this EP addresses is not the existence of late-bind hooks. The smell is
using a process-global hook table as a service locator for app architecture.

### Host-Transient Subsystem State

Host-transient runtime state belongs to the realm. Subsystems MAY still key
entries by frame, but the owner of the table is the realm, not an arbitrary
namespace-level singleton.

Examples include:

- HTTP in-flight handles and abort controllers;
- routing navigation counters and scroll caches;
- machine timers and spawn-order helpers;
- flow last-input caches;
- SSR request side channels;
- adapter render roots and disposers.

A host-transient descriptor SHOULD declare:

```clojure
{:id :rf.http/in-flight
 :storage-class :host-transient
 :scope :frame
 :durability :none
 :teardown teardown-http-for-frame!
 :test-reset reset-http!
 :snapshot nil
 :classification {:egress? true
                  :sensitive? false}}
```

This EP does not replace the durable runtime subsystem contract. It complements
it: durable subsystem state lives under runtime-db and follows the five-clause
runtime subsystem contract; host-transient state lives under realm lifecycle and
must be torn down, reset, and excluded from snapshots explicitly.

### Hot Reload And Reinstall

Hot reload SHOULD be modeled as replacing one app value with another in the same
realm.

```clojure
(def diff
  (rf/reinstall! tenant-a-realm new-tenant-a-app
    {:reason :hot-reload}))

;; Example result:
{:realm :tenant-a/realm
 :reason :hot-reload
 :added   [[:event :cart/remove]]
 :changed [[:event :cart/add]
           [:sub :cart/items]]
 :removed [[:flow :cart/legacy-total]]
 :sources {[:event :cart/add]
           {:previous {:file "src/shop/cart.cljs" :line 22}
            :current  {:file "src/shop/cart.cljs" :line 26}}}}
```

Reinstall MUST preserve the existing hot-reload safety rules:

- in-flight events finish against the descriptors already resolved;
- future lookups use the new registrar;
- changed subscriptions invalidate the relevant caches in frames belonging to
  the realm;
- active machine instances continue with the machine spec they captured unless
  a later machine-specific spec defines an explicit live-upgrade path;
- removed registrations fail loudly on future use;
- trace notifications include kind, id, reason, and source coordinates where
  available.

The implementation MAY still mutate registrar slots internally. The specified
behavior is app-value replacement and diff-driven invalidation.

#### Live-instance refusal binds at the kind boundary, then per-kind

The first reinstall slice is descriptor-only (issue 12). Refuse-loudly binds at
the **kind boundary** in this slice, **in both directions**: the step-8-deferred
kinds (`:route` / `:flow` / `:resource` / `:mutation` / `:resource-scope` /
`:view` / `:head` / `:error-projector`) throw
`:rf.error/unsupported-descriptor-kind` on the **add/changed** path
(`install-descriptor!`) **and** on the **removal** path
(`refuse-unsupported-removal!`). A deferred kind is neither
app-value-installable nor app-value-removable in this slice — the descriptor
diff does not own it in either direction; it stays owned by its own
`reg-*` / `clear-*` sugar lifecycle. So the live-instance classes the refusal
rule worried about (machine actors, in-flight resources/mutations, route
transitions) can be neither seated nor silently orphaned through the descriptor
diff. The per-kind live-instance **blocker / continue / migrate** rule binds
**when each deferred kind becomes installable** — it is not a single global
query over a universal work ledger.

> **Errata.** As originally written this disposition claimed
> the deferred kinds were "structurally unreachable through the descriptor diff"
> full stop. That held only on the **register** (add/changed) path, where the
> kind throw lived in `install-descriptor!`. The **removal** path called
> `registrar/unregister!` *unconditionally* for every removed `[kind id]` — and
> a step-8 kind registered through its own sugar (`reg-mutation` / `reg-resource`
> / `reg-route` / `reg-flow` / …) *does* reach the realm's registrar and *is*
> projected into the diff's old-app, so a `reinstall!` that omitted such an id
> landed it in `:removed` and silently unregistered it, skipping the subsystem
> teardown — the same silent-orphan window the `:frame` refusal closed, reopened
> on the removal path. `refuse-unsupported-removal!` (symmetric with the
> add/changed throw) closes it and restores the claim's truth in both directions.

The ONE wired kind that *is* a live instance is `:frame`. A `:removed` `:frame`
whose live container still exists MUST be **refused loudly** — never silently
orphaned. `registrar/unregister! :frame id` alone drops only the registrar slot,
leaving the container live in the core frame registry (still
dispatchable/subscribable) with the `destroy-frame!` teardown (its `:on-destroy`,
the machine teardown cascade, sub-cache disposal) skipped. `reinstall!` therefore
raises `:rf.error/live-frame-removal-unsupported`, enumerating the blocking
frame-ids, **before any mutation**; the recovery is explicit `destroy-frame!`
then reinstall. The check reads only the core frame registry (the realm's
live-frame view), not cross-subsystem machinery. (Removed `:event` / `:fx` /
`:cofx` have no live instance — they fail loudly on future use per the rule
above; a removed `:sub`'s disposal/loud-read is pre-existing per-kind hot-reload
behaviour.)

> **Step-8 installability obligation (binding).** EVERY
> step-8 kind, when it is made installable through the app-value path, MUST ship
> its own live-instance **blocker / continue / migrate** rule **and** a per-class
> refusal test BEFORE its `:rf.error/unsupported-descriptor-kind` throw is
> lifted. Lifting the kind throw without first defining that rule reopens the
> silent-orphaning window this disposition closed. This is an **acceptance
> criterion of the step-8 bead(s)**, not a follow-up nicety. The blocker source
> is **per-kind**: the durable work ledger (`:rf.runtime/work-ledger`) for the
> ledger-backed classes (resource + mutation writers — the only writers it
> carries today), and the **per-subsystem live registries** (machine snapshots,
> routing `:current`, the core frame registry) for the rest. There is no
> universal enumeration source.

### Source Coordinates

Source coordinates are part of the app-value contract. They are required for
usable composition errors, hot-reload diffs, source navigation, and production
error attribution.

Registration macros SHOULD continue to capture coordinates in the existing
shape:

```clojure
{:ns 'shop.cart.events
 :file "src/shop/cart/events.cljs"
 :line 22
 :column 1}
```

Explicit module values SHOULD allow a coordinate to be supplied manually when a
host lacks macro capture:

```clojure
(rf/module
  {:id :shop/cart
   :source {:ns 'shop.cart
            :file "src/shop/cart.cljs"
            :line 10}
   :events {:cart/add {:handler cart-add}}})
```

Production elision policy remains owned by the registration and instrumentation
specs. This EP requires that whatever coordinates are available during
composition and installation remain attached to diagnostics and development
tooling surfaces.

### Public API Staging

The implementation SHOULD stage public API exposure:

1. Internal realm record and default realm. No public source break.
2. Realm-owned registrar internally. Existing `reg-*` APIs target the default
   realm.
3. Frames store a realm reference internally. Existing frame APIs keep their
   default-realm arities.
4. Adapter and host-transient singleton state move behind the default realm
   internally.
5. Internal app-value projection for existing registrations.
6. Public app/module constructors for feature modules.
7. Public explicit realm install/reinstall APIs.
8. Realm-targeted registrar and frame query APIs.
9. Multi-realm and multi-adapter/root conformance.

This staging keeps migration cheap while making the architecture explicit early
enough for tests to validate it.

## Examples

### A Whole App As Data

```clojure
(ns shop.app
  (:require [re-frame.core :as rf]
            [shop.auth :as auth]
            [shop.cart :as cart]
            [shop.checkout :as checkout]))

(def app
  (rf/app
    {:id :shop/app
     :modules [auth/module
               cart/module
               checkout/module]}))
```

The resulting value can be inspected without installing it:

```clojure
(rf/app-registrations app :event)
(rf/app-owns app [:cart])
(rf/app-requires app)
```

### Composition Collision

```clojure
(def module-a
  (rf/module
    {:id :a
     :events {:shared/save {:handler save-a}}}))

(def module-b
  (rf/module
    {:id :b
     :events {:shared/save {:handler save-b}}}))

(rf/app {:id :bad/app
         :modules [module-a module-b]})

;; => ex-info or error value:
{:error/id :rf.error/app-composition-collision
 :kind :event
 :id :shared/save
 :sources [{:module :a}
           {:module :b}]}
```

The same duplicate id may be legal during explicit reinstall if the diff proves
it is replacing the same source registration for hot reload.

### Installing Into A Runtime Realm

```clojure
(def realm
  (rf/runtime
    {:id :shop/browser
     :adapter reagent/adapter
     :capabilities
     {:rf.capability/http browser-http
      :rf.capability/routes browser-history}}))

(def installed
  (rf/install! realm app))

(rf/reg-frame :shop/main
  {:realm installed
   :on-create [:shop/boot]})
```

### Default-Realm Registration Sugar

```clojure
(rf/reg-event-fx :shop/boot
  {:doc "Boot the shop application."}
  boot-handler)

;; Equivalent in meaning to:
(rf/register! (rf/default-realm)
  {:kind :event
   :id :shop/boot
   :event/kind :fx
   :metadata {:doc "Boot the shop application."}
   :handler boot-handler})
```

### Frame Owns Or References Realm

```clojure
(rf/reg-frame :tenant-a/main
  {:realm tenant-a-realm
   :on-create [:tenant/init]})

(rf/reg-frame :tenant-b/main
  {:realm tenant-b-realm
   :on-create [:tenant/init]})

(rf/dispatch [:tenant/save]
  {:realm tenant-a-realm
   :frame :tenant-a/main})
```

Same event id, same frame id spelling style, different realm-owned behavior.

### Adapter Ownership

```clojure
(def admin-realm
  (rf/runtime
    {:id :admin/realm
     :adapter reagent/adapter
     :app admin-app}))

(def workspace-realm
  (rf/runtime
    {:id :workspace/realm
     :adapter uix/adapter
     :app workspace-app}))
```

The current reference implementation may reject this until the adapter layer is
lifted. The spec direction permits it once the realm/root invariants are
implemented.

### Capability Maps For Hermetic Tests

```clojure
(deftest cart-adds-item
  (let [realm (-> (rf/runtime
                    {:id :test/cart
                     :adapter plain-atom/adapter
                     :capabilities
                     {:rf.capability/http fake-http
                      :rf.capability/clock fixed-clock}})
                  (rf/install! cart-test-app))]
    (rf/reg-frame :cart/test {:realm realm})
    (rf/with-frame :cart/test
      (rf/dispatch-sync [:cart/add {:sku "A-1"}])
      (is (= [{:sku "A-1"}]
             @(rf/subscribe [:cart/items]))))))
```

The test installs exactly the program and capabilities it needs. It does not
clear a process-global registrar.

### Late-Bind Compatibility Bridge

```clojure
;; Existing optional artifact wiring:
(late-bind/publish! :re-frame.schemas/validate validate!)

;; Compatibility bridge into the default realm:
(rf/install-capability! (rf/default-realm)
  :rf.capability/schemas
  {:validate (late-bind/lookup :re-frame.schemas/validate)})

;; Preferred explicit realm wiring:
(rf/runtime
  {:id :schema-test/realm
   :capabilities
   {:rf.capability/schemas {:validate validate!
                            :explain explain}}})
```

### Hot Reload Diff

```clojure
(def old-app
  (rf/app {:id :shop/app
           :modules [cart-v1 checkout]}))

(def new-app
  (rf/app {:id :shop/app
           :modules [cart-v2 checkout]}))

(rf/reinstall! shop-realm new-app {:reason :hot-reload})

;; => {:added   [[:event :cart/remove]]
;;     :changed [[:event :cart/add] [:sub :cart/items]]
;;     :removed []
;;     :invalidated {:sub-cache [[:cart/items]]}}
```

## Rationale

### Program As Value Completes The Existing Model

re-frame2's strongest ideas are value-oriented: app-db is data, runtime-db is a
separate framework-owned partition, effects are maps, frames are explicit
runtime boundaries, and subscription topology is inspectable. Leaving the
program itself as load-order mutation is the major remaining mismatch.

An app value lets the same engineering moves apply to the program: compose it,
validate it, diff it, install it, snapshot its contract, and inspect it.

Two payoffs deserve explicit naming because nothing else in the system can
deliver them:

- **The contract graph stops being a harvesting project.** Today, answering
  "what writes this path?" or "which source file declared this contract?"
  means re-deriving the program from the live registrar — replaying namespace
  load order. With app values, the app value *is* the contract graph; tools
  read it and diff it.
- **Event coverage becomes a static check.** The event space of a composed app
  is closed. "Every dispatched id is registered" and "every registered handler
  is reachable" become composition-time and lint-time checks over the app
  value instead of runtime `no-such-handler` errors.

### Realms Are Carried, Not Ambient

An obvious alternative shape for realm targeting is dynamic scope:

```clojure
;; REJECTED shape — shown for the record.
(rf/with-runtime runtime
  (rf/reg-event-db :cart/add ...)
  (rf/dispatch [:cart/add item]))
```

This EP rejects it. Dynamic realm binding is ambient context redux — the exact
pattern EP-0002 removed for frames. The carried invariant says an operation
reads its target from the token it is holding; it never discovers one from the
ambient world. A `with-runtime` block reintroduces "search the ambient world
for a target" one level up from where it was just deleted, makes registration
targets depend on call-stack shape instead of data, and breaks for the same
async reasons frames did: a callback captured inside the block outlives the
binding.

Instead, realms follow the frame rule verbatim: a realm is **carried** (an
explicit argument, a dispatch option, a field on the frame that already
carries identity) or **ambient only inside an established explicit scope** —
`with-frame` remains valid because the frame it names owns its realm, so the
scope is carried-then-scoped, not discovered. Absence fails loudly with the
existing no-frame-context family; it never selects a realm.

### Default Realm Preserves Ergonomics

The existing `reg-*` style is useful. It is also the migration path for re-frame
v1 codebases. Treating those calls as default-realm sugar keeps the call shape
while changing the conceptual target.

This is the same posture EP-0002 takes with frame scope: ambient within an
explicit scope is ergonomic; absence should not invent a target. Here, the
default realm is an explicit compatibility realm created by the runtime, not a
claim that all apps in a process must share behavior forever.

Inside one realm, plurality is invisible: a single-realm app never spells a
realm, just as a single-frame app never spells a frame outside its root. This
is the EP-0002 refinement pattern — the plural model exists, and the
zero-ceremony path stays zero-ceremony.

### Realms Match Operational Ownership

The registrar, adapter, capability map, frame registry, host-transient
subsystems, and installed app all have the same lifecycle question: who owns
them, and when are they disposed? A realm is the smallest useful answer.

Putting the registrar on each frame would duplicate behavior for common
multi-frame same-app cases. Keeping it on the process blocks multi-tenant and
multi-root operation. Realm ownership sits between those extremes: many frames
can share one installed program, and one process can host more than one
program.

### Capability Maps Are Clearer Than Service Location

`late_bind` solves real CLJS artifact-loading problems. It should stay available.
But a large application should be able to inspect a feature and see that it
requires HTTP, routing, schemas, or a clock. Capability maps make dependencies
explicit and injectable, which improves tests, SSR, tenant-specific behavior,
and static analysis.

### Source Coordinates Belong To The Program Artifact

The existing source-coordinate capture is one of the project's strongest
tooling decisions. App values should preserve it rather than forcing tools to
derive source locations from the current registrar. Composition failures and
hot-reload diffs are only useful if they point to the declaring source forms.

### Compatibility Does Not Override Correctness

Mechanical migration from v1 is important, but not at the cost of preserving the
global registrar as a pattern law. This EP deliberately separates source
compatibility from architecture: v1-shaped calls can continue to work while
re-frame2 grows an explicit program/runtime model.

### Honest Costs

- **The load-order idiom dies as architecture.** The
  `(:require [feature.events])`-registers-itself idiom stops being a
  program-assembly mechanism and survives only as default-realm sugar. That is
  the point — load order is exactly the temporal implicitness this EP removes —
  but it is also a deeply ingrained re-frame idiom, and the sugar must remain
  first-class for the idiom's users.
- **Circularity needs the existing discipline, mandatorily.** Modules that
  reference each other must late-bind by id. That is already the norm —
  everything is keyword-addressed — but explicit module values turn the
  discipline from idiomatic into required.
- **Two strictness levels coexist.** During the compatibility window,
  default-realm re-registration stays lenient (hot-reload replacement with dev
  warnings) while explicit composition is strict (collisions are errors). One
  model, two strictness levels, must be taught without it reading as two
  models.
- **This is the largest single change proposed in the current EP set.** The
  decision severability in §Proposed Solution and the internal-first staging
  in §Public API Staging exist because of that, not despite it.

## Alternatives Considered

### Keep The Process-Global Registrar

This is the lowest-effort path and the most compatible with v1. It preserves the
current model for small apps.

It fails the large-SPA cases this EP targets. Behavior isolation, hermetic tests,
multi-tenant shells, explicit hot reload, and feature-module composition remain
bolted onto a global side effect.

### Bind The Realm Through Dynamic Scope

A `with-runtime`-style block could make registration and dispatch read an
ambient realm binding, avoiding any explicit realm argument.

Rejected; see §Rationale, Realms Are Carried, Not Ambient. It is the ambient
fallback pattern EP-0002 already removed for frames, with the same failure
modes: captured callbacks outlive the binding, targets depend on call-stack
shape rather than data, and trace/replay determinism loses the carried
identity. Realm targeting MUST be carried or scoped by an explicitly
established frame scope; it MUST NOT be resolved from a dynamic binding.

### Put A Registrar On Every Frame

Per-frame registrars maximize isolation, but they duplicate behavior for the
common case where many frames run the same app. They also blur the distinction
between frame state and program definition.

Realms give behavior a lifecycle without making every frame carry a full program
copy.

### Use Only External System Libraries

Libraries such as Integrant or Component already model systems as values. An
application may still use them. But re-frame2 still needs a native contract for
registration, dispatch, subscription, adapter ownership, and frame identity.

The app-value and realm model can integrate with external system libraries; it
cannot be delegated entirely to them without leaving framework behavior
underspecified.

### Make `late_bind` More Powerful

Expanding `late_bind` could hide more optional dependency cases behind global
hooks. That would improve short-term wiring without fixing the ownership model.

This EP keeps `late_bind` for compatibility and optional loading, then moves the
semantic dependency surface to capabilities.

### Require Explicit App Manifests Immediately

Forcing every app to rewrite into module values would produce a clean model but
unnecessarily harm migration. The default realm lets old and new source styles
coexist.

### Use Iframes Or Separate Processes For Isolation

Iframes solve some deployment isolation problems, but they are too heavy to be
the framework's only answer to multiple apps, tests, tenants, and render roots
in one process. re-frame2 should support behavior isolation as a normal runtime
shape.

### Preserve Single Adapter Per Process As Law

One adapter per process is simple and may remain the first CLJS implementation.
As a law, it over-constrains large apps that need multiple render roots during
migration or product-shell integration. The better invariant is consistent
adapter ownership within a realm/root.

## Backwards Compatibility And Migration

Existing `reg-*` calls continue to work. Their default target is the default
realm.

### Mechanical Migration Strategy

Migration can proceed in layers:

1. Existing namespace-load registration continues unchanged.
2. The runtime internally records those registrations as descriptors in the
   default realm's app value.
3. Tools expose the default app projection so teams can inspect what their
   current code declares.
4. Features can be wrapped into module values one namespace or product slice at
   a time.
5. Tests can start creating explicit realms before production code does.
6. Product shells can install explicit app values once enough modules have been
   declared.
7. The global registrar becomes an implementation detail of the default realm.

Example v1-shaped source:

```clojure
(rf/reg-event-db :cart/add
  {:doc "Add an item to the cart."}
  cart-add)
```

Mechanically wrapped module source:

```clojure
(def cart-module
  (rf/module
    {:id :shop/cart
     :events
     {:cart/add
      {:doc "Add an item to the cart."
       :handler cart-add}}}))
```

The handler function does not change. The event id does not change. The
metadata map does not change. What changes is the target: explicit module data
instead of namespace-load mutation.

### Collision Behavior

Default-realm re-registration may retain today's hot-reload behavior during the
compatibility window, including dev warnings for suspicious source-coordinate
changes. Explicit app composition is stricter: same-kind same-id collisions are
errors unless the source form declares replacement.

This is intentional. Namespace-load compatibility should not define the
semantics of explicit app values.

### Frames And Existing APIs

Applications that use one default realm should not need to mention realms in
ordinary code. `reg-frame`, `dispatch`, `subscribe`, `frame-provider`,
`with-frame`, and frame handles can keep their current shapes.

Multi-realm code must carry a realm explicitly or use a frame handle that
already carries one.

### Adapter Migration

`init!` and adapter installation can continue to install into the default realm.
The implementation can first move the process adapter slot behind the default
realm without exposing new public API.

Explicit per-realm/root adapter selection should be public only after adapter
lifecycle, frame routing, and disposal conformance are in place.

### Late-Bind Migration

Existing optional artifacts can keep publishing hooks. The default realm may
bridge those hooks into capabilities. New explicit realm code should install
capabilities directly.

### Source Compatibility Boundary

This EP does not promise that every v1 global assumption remains valid. In
particular, code that depends on process-wide behavior sharing across unrelated
apps will need to choose a shared realm explicitly or migrate to separate
realms.

## Reference Implementation / Bead Plan

The implementation should be internal-first and compatibility-preserving:

1. Add an internal runtime realm record with id, registrar, adapter slot,
   capability map, frame registry, installed app projection, and lifecycle.
2. Create the default realm at boot and route all existing global registrar
   operations through it.
3. Store the owning realm on frame records. Keep existing frame ids and default
   public arities.
4. Thread realm through dispatch envelopes, subscription resolution, trace
   events, epoch records, and async continuations wherever frame identity is
   already carried.
5. Move the adapter install slot behind the default realm internally while
   preserving the existing `init!` and `destroy-adapter!` behavior.
6. Add a realm capability map and bridge existing late-bound hooks into the
   default realm.
7. Define an internal app-value descriptor format for `:event`, `:sub`, `:fx`,
   `:cofx`, and `:frame` registrations.
8. Extend the descriptor format to routes, flows, machines, resources,
   mutations, views, SSR/error-projector registrations, and non-registrar
   declaration surfaces such as app-db schemas.
9. Implement composition validation for same-kind same-id collisions and source
   coordinate diagnostics.
10. Add realm-owned host-transient subsystem descriptors for at least one
    existing singleton-like subsystem, then expand after the lifecycle pattern
    proves useful.
11. Model hot reload as `reinstall!` over app values for the descriptor subset
    implemented so far.
12. Expose public app/module/realm constructors only after internal tests prove
    the shape simplifies lifecycle and hermetic testing.
13. Update the relevant specs once the EP is accepted and the implementation
    reaches each public stage.

## Validation / Conformance

Conformance should be tested at three levels.

### App-Value Conformance

- App/module construction produces stable data projections.
- Empty module composition is identity.
- Composition is deterministic.
- Same-kind same-id collisions produce precise diagnostics with source
  coordinates.
- Ownership overlaps produce diagnostics where the owning spec defines overlap.
- Capability requirements are preserved and checked at install time.
- Source coordinates survive lowering from `reg-*` and explicit module forms.
- The composed event set is enumerable, enabling static dispatch-coverage
  checks (every dispatched id resolves; unreachable registrations are
  reportable).

### Realm Conformance

- Two realms can install different handlers for the same event id without
  collision.
- Frames resolve handlers from their owning realm.
- Default-realm `reg-*` behavior matches existing public behavior.
- Realm-targeted registrar queries return only that realm's registrations.
- Missing capability installation fails before the app becomes visible.
- Frame destroy tears down frame-scoped host-transient entries in that realm.
- Realm destroy disposes adapter/root resources and host-transient subsystem
  state.
- Hermetic tests can install an app into a fresh realm without clearing a
  process-global registrar.

### Hot-Reload Conformance

- Reinstall returns added, changed, removed, and invalidated entries.
- In-flight events finish against previously resolved descriptors.
- Future dispatches resolve through the new registrar.
- Changed subscriptions invalidate caches only in frames owned by the realm.
- Removed registrations fail loudly on future lookup.
- Active machine instances preserve the currently specified captured-spec
  semantics.
- Diff trace events include kind, id, reason, and source coordinates where
  available.

### Static And Lint Conformance

- New framework code should not introduce process-global registrars outside the
  default-realm implementation.
- New optional artifacts should expose realm capabilities or a clear late-bind
  bridge.
- New host-transient side tables should have realm ownership, frame teardown,
  and test reset.
- Adapter-dependent code should state whether it is process, realm, or
  render-root scoped.

## Open Issues

**All thirteen issues were ruled 2026-06-11 (Mike, in-session), merging three
convergent analyses.** Original recommendations
are kept verbatim as the record of what was ruled; dispositions and riders are
inline.

1. What public names should ship: `rf/app`, `rf/module`, `rf/runtime`,
   `rf/realm`, `rf/install!`, `rf/reinstall!`, or a different vocabulary?
   **Recommendation:** keep the first public slice small: `rf/app`,
   `rf/module`, `rf/realm`, and `rf/install!`; defer reinstall naming until
   hot-reload semantics are proven.
   **Disposition: corrected on two points.** The vocabulary is **`rf/realm`,
   never `rf/runtime`** — "runtime" already names runtime-db, `:rf.runtime/*`
   children, runtime subsystems, and Runtime-Architecture; a realm constructor
   called `runtime` is a permanent EP-0007 hazard (three analyses converged
   independently). And there is **no day-one public facade**: the four names
   (with `realm`) are the *reserved vocabulary*; the D1 proving slice needs no
   public names, and exposure graduates internal-first per the corpus
   discipline (the EP-0012 ≥2-consumer criterion), facade-classification per
   name. Reinstall naming deferred as recommended.
2. What is the exact realm stamp shape carried with frame identity?
   **Recommendation:** carry `:rf.realm/id` beside `:rf.frame/id` in framework
   envelopes and records; do not overload frame ids with realm-qualified tuples.
   **Disposition: as recommended** — tuple-overloading would retroactively
   change every shipped `:rf.frame/id` carrier (EP-0010 replay records,
   EP-0011 reply maps, EP-0016 continuation payloads). Riders: absence of
   `:rf.realm/id` means **the default realm as an explicit documented rule** (a
   real, runtime-created realm — the EP-0002 refinement pattern), never
   synthesis; multi-realm processes always stamp it; and the EP-0010/0011/0016
   record shapes **reserve the slot now** (one line each — an early, ungated
   bead) so the later addition is non-breaking.
3. Are frame ids unique within a realm only, or should public multi-realm APIs
   require globally unique frame ids during the compatibility window?
   **Recommendation:** frame ids are unique within a realm; APIs that cross a
   realm boundary require both realm and frame, while single-realm apps keep the
   current frame-id ergonomics.
   **Disposition: as recommended** — the (realm, frame) pair is the full
   address under the carried model. Riders: the Tool-Pair four-tier discovery
   ladder gains the realm dimension (tier-3 sole-app-frame resolution becomes
   realm-scoped); the same frame id registered in two realms is a **tested
   legal case**, not an accident.
4. Is adapter ownership per realm, per render root, or both?
   **Recommendation:** realm owns the adapter capability; render roots may bind
   a narrower concrete root instance, but the capability lookup is realm-owned.
   **Disposition: as recommended, with the boundary sharpened:** the realm owns
   adapter *selection* (the capability); render roots own concrete
   mount/disposer *instances of that adapter*. Two roots needing **different
   adapters means two realms**, unless a future bridge is explicitly designed —
   the no-mixing-within-one-frame's-graph invariant is the conformance test.
   Named payoff: two adapters in one process becomes legal via realms (stock
   Reagent and reagent-slim side by side — impossible today).
5. Which host-transient subsystem should move behind the realm first?
   **Recommendation:** move adapter installation/test reset first, then HTTP
   in-flight handles, because those two give the fastest hermetic-test payoff.
   **Disposition: as recommended.** Riders: adapter-first *is* D1's proving
   slice and directly enables issue 4's payoff; the HTTP migration sequences
   **behind the current EP-0011/0016 waves settling** (they are actively
   editing that runtime, and its reply seams are freshly tested); EP-0006's
   host-transient grading column (issue 13) is written from these two
   migrations as evidence.
6. How much of the module ownership map belongs in the first public API?
   **Recommendation:** expose descriptor ownership and capability requirements;
   keep advanced provenance/query metadata internal until tooling needs it.
   **Disposition: as recommended, plus one fact the recommendation predates:**
   EP-0015 made registration-owned classification load-bearing, so the public
   descriptor facts are **three** — ownership, capability requirements, and
   classification metadata. Provenance/query stays internal with a named
   demand trigger: an Xray module-view bead.
7. Should explicit app composition return error values, throw ex-info, or
   support both?
   **Recommendation:** validation APIs return data; install/boot APIs throw
   `ex-info` with the same data when asked to make an invalid app visible.
   **Disposition: as recommended** — data is the primitive; the `ex-data` *is*
   the validation result (one fact, two delivery modes). Rider: the
   composition-error categories are routed through the EP-0008 promotion
   criterion at implementation — boot-time `install!` failure is
   production-reachable, so the family likely needs an always-on row.
8. How are source coordinates supplied in hosts without macro support?
   **Recommendation:** source coordinates are optional descriptor metadata in
   non-macro hosts; code generators and language adapters may fill them.
   **Disposition: as recommended** — coordinates are diagnostic facts
   (EP-0010's durable/diagnostic split); absence never changes behavior, and
   they are never faked. Degradation rule: absent coordinates, tooling anchors
   provenance to module/kind/id.
9. What replacement declaration is sufficient to distinguish hot reload from an
   accidental collision?
   **Recommendation:** require an explicit generation/source identity on
   replacement batches; accidental same-id collisions outside a replacement
   batch fail loudly.
   **Disposition: as recommended, with two riders that decide whether the rule
   survives:** the batch identity must be **reload-stable** — a hot-reload
   generation plus stable source/form identity or an explicit `:replace`,
   never line/column (which changes on every edit, i.e. on every reload); and
   the dev tooling **mints the replacement batch automatically** on hot reload
   (tied to the build tool's compile id where available) — without
   auto-minting, every shadow-cljs re-eval fails loudly and the rule is
   disabled by every user in week one.
10. How does namespace-load `reg-*` sugar interact with explicit app values when
    a namespace contains both forms?
    **Recommendation:** namespace-load `reg-*` targets the default realm only;
    explicit app descriptors are inert values until installed into a realm.
    **Disposition: as recommended** — no hidden merge; "inert" is load-bearing
    (a module map has no registration side effect; it is pure data). Rider:
    the predictable migration accident — the same handler registered via sugar
    *and* listed in a module installed into the default realm — is a same-id
    collision outside a replacement batch, caught loudly by rule 9; this is
    documented explicitly as the first error migrating apps will meet.
11. Which registrar query arities become public at the first realm-aware stage?
    **Recommendation:** expose read-only queries scoped by `{ :realm ... }` and
    `{ :realm ... :kind ... }`; defer broad process-global queries to tools.
    **Disposition: as recommended — map-shaped is the ruled public form**
    (extensible; unambiguous against existing keyword arities); the positional
    examples this EP previously showed are corrected to match. Riders: the
    frame-neutral enumeration surfaces grow realm-scoped forms per EP-0002's
    registrar-enumeration carve-out; new registrar kinds (e.g. EP-0016's
    `reg-resource-scope`) enumerate for free via the Spec 001 taxonomy;
    process-global enumeration is the Tool-Pair operator layer's job.
12. How should active resources, mutations, route transitions, and machines
    participate in a realm reinstall beyond the existing per-kind hot-reload
    rules?
    **Recommendation:** first reinstall slice is descriptor-only and refuses
    when live runtime instances would need migration; later slices add
    per-subsystem migration hooks through the runtime-subsystem contract.
    **Disposition: as recommended — refuse-loudly is fail-closed applied to
    hot reload.** Rider, from a convergence the recommendation predates: the
    refusal must be **diagnosable**, enumerating exactly which live instances
    block the reinstall — and the work ledger is the enumeration source (after
    the EP-0011/0016 waves, every live instance class carries a ledger row),
    so the refusal check is a ledger query, not new machinery.

    > **Errata — 2026-06-12 (Mike).** This disposition is
    > PRECISED, not deleted, against what shipped. Two corrections:
    >
    > 1. **Where refuse-loudly binds in slice 1.** The shipped slice 1
    >    (`reinstall!`) is descriptor-only and binds refuse-loudly **at the kind
    >    boundary, in both directions**: the step-8-deferred kinds (`:route` /
    >    `:flow` / `:resource` / `:mutation` / `:resource-scope` / `:view` /
    >    `:head` / `:error-projector`) throw
    >    `:rf.error/unsupported-descriptor-kind` on the **add/changed** path
    >    (`install-descriptor!`) **and** on the **removal** path
    >    (`refuse-unsupported-removal!`), so the live-instance
    >    classes this disposition worried about (machine actors, in-flight
    >    resources/mutations, route transitions) are neither seated nor silently
    >    orphaned through the descriptor diff — a generic blocker query would
    >    protect nothing reachable while inventing cross-subsystem machinery ahead
    >    of those kinds having install semantics. (**Errata:** as
    >    first shipped the throw lived ONLY in `install-descriptor!`, i.e. ONLY on
    >    the add/changed path; the removal path called `registrar/unregister!`
    >    unconditionally, so a step-8 kind registered through its OWN sugar — which
    >    reaches the registrar and is projected into the diff's old-app — was
    >    silently unregistered when a `reinstall!` omitted it, reopening the
    >    silent-orphan window on the removal path. "Structurally unreachable
    >    through the descriptor diff" was therefore true only on the register path
    >    until `refuse-unsupported-removal!` extended the boundary to removal.)
    >    The live-instance **blocker / continue / migrate rule binds PER-KIND**,
    >    when each deferred kind becomes installable; defining that rule is a
    >    **precondition of lifting the unsupported-kind throw** for that kind (see
    >    the §Implementation step-8 obligation). The ONE wired kind that *is* a
    >    live instance — `:frame` — is handled: a `:removed` `:frame` whose live
    >    container still exists is refused loudly
    >    (`:rf.error/live-frame-removal-unsupported`, enumerating the blocking
    >    frame-ids) rather than silently orphaned.
    >
    > 2. **The enumeration-source overclaim.** "The work ledger is THE
    >    enumeration source … every live instance class carries a ledger row" is
    >    corrected: per the shipped ledger (`spec/016` §What Spec 016 does NOT
    >    cover / §Work-ledger multi-writer authority, ~L1249/L1259) the durable
    >    ledger carries **only resource + mutation writers** —
    >    timers, route loaders, spawned actors, and machine async are explicitly
    >    *future*. The work ledger is therefore **one** blocker source for the
    >    ledger-backed classes, never the universal enumeration source. The other
    >    blocker sources are the **per-subsystem live registries** (machine
    >    snapshots, routing `:current`, the core frame registry). Each per-kind
    >    rule names its own source when that kind becomes installable.
13. Does the host-transient subsystem descriptor live here or as an extension row
    in EP-0006's grading table? **Recommendation:** EP-0006 owns the contract;
    this EP contributes realm ownership and lifecycle as the host-transient
    grading column.
    **Disposition: as recommended** — one contract, one grading table. Riders:
    the mechanism is a `spec/Runtime-Subsystems.md` amendment adding the
    column (EP-0006 is final; its spec evolves); the conformance drift test
    grows the column when it lands so host-transient rows
    cannot silently go missing.

## Recommendation

Adopt the direction of this EP: the program is an app value, and runtime
capabilities live in explicit realms. Keep current `reg-*` APIs as default-realm
sugar, but stop treating process-global registration, single adapter per
process, and global late-bind lookup as the long-term architecture.

Rule the three decisions separately. The final disposition should be recorded
as a small D1/D2/D3 matrix so reviewers can accept the container without
accidentally accepting the whole public app/module surface:

- **D1 (realm container): adopt.** It is the hermetic-test and tenancy layer,
  it can be implemented internal-first behind the default realm with no public
  source break, and it pays for itself even if D2 and D3 never ship.
- **D2 (app value): adopt, sequenced behind D1.** Begin the descriptor
  projection once the internal realm record has proven that lifecycle and
  teardown actually simplify; do not expose public install/reinstall before
  the diff semantics are validated against the existing hot-reload rules.
- **D3 (module manifests): adopt the shape, stage the public surface last.**
  Manifests are the highest-leverage layer for large multi-team SPAs and the
  least urgent; the composition laws should be settled in tests before any
  public constructor ships.

The recommended implementation path is internal-first. Move one real singleton
behind the default realm, prove that tests and teardown improve, then expose the
public app/module/realm constructors in stages.

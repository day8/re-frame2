# EP-0013: App Values And Runtime Realms

Status: proposal
Type: standards-track

> This EP proposes the long-term architecture in which the application program
> and runtime environment are explicit values. Existing process-global
> registration remains as compatibility sugar over a default runtime realm.
>
> Normative home after acceptance: registration, frames, reactive substrate,
> runtime subsystem, adapter, and conventions specs.

## Abstract

re-frame2 already treats durable state as a value and effects as data. The
remaining large ambient surface is the program and the runtime environment that
interprets it: the handler registrar, installed adapter, late-bound function
table, capability lookups, and host-transient subsystem state are still mostly
process-global.

This EP introduces two explicit values:

- an **app value**, which is an immutable description of a program or feature
  slice: events, subscriptions, flows, machines, resources, routes, schemas,
  views, effects, coeffects, ownership claims, capability requirements, and
  source coordinates;
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

At the source level, an application can be described explicitly:

```clojure
(def cart-module
  (rf/module
    {:id :shop/cart
     :owns {:app-db [[:cart]]
            :resources [:shop.cart/items]
            :routes [:shop.route/cart]}
     :requires #{:rf.capability/http}
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
  resources, effects, and other public contract surfaces;
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

Every `reg-*` source form and every explicit module entry lowers to a
registration descriptor.

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
     :owns {:app-db [[:billing :invoices]]
            :paths [:billing.invoice/customer-email]
            :resources [:billing.invoice/list]
            :routes [:billing.route/invoice-show]
            :effects [:rf.http/managed]}
     :requires #{:rf.capability/http
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
- two modules owning the same route id, resource id, effect id, or schema path
  MUST produce a precise diagnostic;
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
ambient-looking APIs resolve through it unless an explicit realm is supplied or
a frame scope already carries a realm.

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

and SHOULD grow realm-targeted arities:

```clojure
(rf/registrations tenant-a-realm :event)
(rf/handler-meta tenant-a-realm :event :cart/add)
(rf/app-registrations tenant-a-app :event)
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
    (rf/with-frame (rf/make-frame :cart/test {:realm realm})
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

### Default Realm Preserves Ergonomics

The existing `reg-*` style is useful. It is also the migration path for re-frame
v1 codebases. Treating those calls as default-realm sugar keeps the call shape
while changing the conceptual target.

This is the same posture EP-0002 takes with frame scope: ambient within an
explicit scope is ergonomic; absence should not invent a target. Here, the
default realm is an explicit compatibility realm created by the runtime, not a
claim that all apps in a process must share behavior forever.

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

## Alternatives Considered

### Keep The Process-Global Registrar

This is the lowest-effort path and the most compatible with v1. It preserves the
current model for small apps.

It fails the large-SPA cases this EP targets. Behavior isolation, hermetic tests,
multi-tenant shells, explicit hot reload, and feature-module composition remain
bolted onto a global side effect.

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
8. Extend the descriptor format to routes, flows, machines, schemas, resources,
   views, and SSR/error-projector registrations.
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

- What public names should ship: `rf/app`, `rf/module`, `rf/runtime`,
  `rf/realm`, `rf/install!`, `rf/reinstall!`, or a different vocabulary?
- What is the exact realm stamp shape carried with frame identity?
- Are frame ids unique within a realm only, or should public multi-realm APIs
  require globally unique frame ids during the compatibility window?
- Is adapter ownership per realm, per render root, or both?
- Which host-transient subsystem should move behind the realm first?
- How much of the module ownership map belongs in the first public API?
- Should explicit app composition return error values, throw ex-info, or support
  both?
- How are source coordinates supplied in hosts without macro support?
- What replacement declaration is sufficient to distinguish hot reload from an
  accidental collision?
- How does namespace-load `reg-*` sugar interact with explicit app values when a
  namespace contains both forms?
- Which registrar query arities become public at the first realm-aware stage?
- How should active resources, route transitions, and machines participate in a
  realm reinstall beyond the existing per-kind hot-reload rules?

## Recommendation

Adopt the direction of this EP: the program is an app value, and runtime
capabilities live in explicit realms. Keep current `reg-*` APIs as default-realm
sugar, but stop treating process-global registration, single adapter per
process, and global late-bind lookup as the long-term architecture.

The recommended implementation path is internal-first. Move one real singleton
behind the default realm, prove that tests and teardown improve, then expose the
public app/module/realm constructors in stages.

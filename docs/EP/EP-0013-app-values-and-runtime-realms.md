# EP-0013: App Values And Runtime Realms

Status: proposal
Type: standards-track

> Drafted from the first-principles synthesis. This EP proposes the long-term
> architecture in which the application program and runtime capabilities are
> values. Existing process-global registration remains the compatibility
> surface.
>
> Normative home after acceptance: registration, frames, runtime, adapter, and
> conventions specs.

## Abstract

re-frame2 makes state a value but still assembles the program through
process-wide registration side effects. The global registrar, installed adapter,
late-bind hooks, and several runtime capability tables are pragmatic, but they
limit large-SPA composition, hermetic testing, multi-tenant shells, hot reload,
and static analysis.

This EP defines two concepts:

- an **app value**, a data value that describes registered events,
  subscriptions, flows, machines, resources, routes, schemas, effects, and
  feature ownership;
- a **runtime realm**, a value/record that owns a registrar, adapter choice,
  capability map, host-transient subsystem registry, and installed app value.

The existing global registrar becomes the default realm. Existing `reg-*`
functions remain valid as default-realm sugar.

## Motivation

Large SPAs need answers that scattered registration makes difficult:

- Which feature owns this path?
- Which events can mutate it?
- Which routes activate this feature?
- Which resources and effects can it use?
- Can two versions of this feature run side by side?
- Can a test install this program without cleaning global state?
- What changed during hot reload?
- Which app contract should an AI agent inspect?

Frames currently isolate state, not behavior. That is sufficient for multiple
instances of the same app. It is weaker for product shells, independently
shipped feature packs, white-label tenants, migration windows, and tests that
want hermetic handler registries.

## Goals

- Make the application program inspectable, composable, diffable, and
  installable as data.
- Allow multiple runtime realms in one JS process.
- Preserve current `reg-*` ergonomics through a default realm.
- Give adapters and late-bound capabilities an explicit owner.
- Create a future source of truth for feature modules and contract graphs.

## Non-Goals

- This EP does not require removing process-global registration in the first
  implementation.
- This EP does not require app authors to write a manifest for every feature
  immediately.
- This EP does not define a micro-frontend deployment system.
- This EP does not promise arbitrary third-party runtime subsystem registration
  before the internal contract is coherent.

## Relationships

- EP-0002 frames should eventually reference or carry their realm.
- EP-0006 runtime-subsystem contracts become realm-owned rather than
  process-owned.
- EP-0007 one-name-per-fact benefits from app values because collisions can be
  detected during composition.
- EP-0012 path declarations and canonical identity give feature manifests
  stronger ownership handles.
- EP-0014 derivation declarations can live inside app values.

## Specification

### App Value

An app value is immutable data describing a program slice or whole program. A
draft shape:

```clojure
(def cart-module
  (rf/module
    {:id :cart
     :owns {:app-db [[:cart]]
            :resources [:cart/items]}
     :events
     {:cart/add
      {:handler cart-add
       :schema [:tuple keyword? :cart/item]}}
     :subs
     {:cart/items
      {:handler cart-items}}
     :flows
     {:cart/summary cart-summary-flow}
     :routes
     {:cart/show cart-route}
     :schemas
     {:cart/item cart-item-schema}}))

(def app
  (rf/app [auth-module cart-module checkout-module]))
```

The exact constructor names are open issues. The normative property is that a
feature's registrations can be represented as data and composed before
installation.

### Composition

Composing app values MUST either produce a larger app value or a precise
collision/error value. Silent last-writer-wins registration is not allowed for
same-kind same-id conflicts unless a source form explicitly declares replacement
semantics for hot reload.

Example collision:

```clojure
(rf/app [module-a module-b])
;; => error:
;; {:error/id :rf.error/registration-collision
;;  :kind :event
;;  :id :cart/add
;;  :sources [source-a source-b]}
```

### Runtime Realm

A runtime realm owns the operational capabilities needed to run an app:

```clojure
(def runtime
  (rf/runtime
    {:app app
     :adapter reagent-adapter
     :capabilities {:http http-capability
                    :routes route-capability}
     :host-transient-subsystems [http-in-flight timers scroll-cache]}))
```

Frames belong to a realm:

```clojure
(rf/reg-frame :shop/main {:runtime runtime})
```

The default process-global behavior is equivalent to a default realm:

```clojure
(rf/reg-event-db :cart/add cart-add)
;; sugar for adding :cart/add to the default realm/app
```

### Adapter Ownership

The current single-adapter-per-process rule becomes a default implementation
strategy, not the pattern law. A realm or render root may own its adapter
capability if the implementation supports it.

The invariant is:

```text
reactive values created under a render subtree use that subtree's realm/adapter
consistently and are disposed with it.
```

### Late-Bound Capabilities

Late-bound hooks remain useful for optional artifacts and cyclic namespace
pressure, but the conceptual model changes from "the process has hooks" to "the
realm has capabilities".

Compatibility:

```clojure
(late-bind/publish! :re-frame.schemas/validate validate!)
```

Long-term realm-owned shape:

```clojure
(rf/runtime
  {:capabilities
   {:schemas {:validate validate!
              :explain explain}}})
```

### Hot Reload

Hot reload SHOULD be modeled as a diff between app values:

```clojure
(rf/reinstall! runtime new-app)
;; computes changes:
;; {:added   [[:event :cart/remove]]
;;  :changed [[:sub :cart/items]]
;;  :removed [[:flow :old/summary]]}
```

The implementation may still use registrar mutation internally, but the
specified behavior is replacement of one app value by another under a realm.

### Feature Module Example

A billing feature can be inspected before installation:

```clojure
(def billing
  (rf/module
    {:id :billing/invoices
     :owns {:app-db [[:billing :invoices]]
            :paths [:invoice/customer-email]
            :resources [:invoice/list]}
     :events {:invoice/open open-invoice
              :invoice/mark-paid mark-paid}
     :subs {:invoice/selected selected-invoice}
     :resources {:invoice/list invoice-list-resource}
     :routes {:invoice/show invoice-route}
     :privacy {:sensitive-paths [:invoice/customer-email]}}))
```

Tools can answer ownership questions from the value without replaying namespace
load order.

## Rationale

This EP completes the value-oriented architecture. re-frame2 already treats
state and effects as data. Treating the program as data gives the same benefits
to tests, hot reload, tooling, feature composition, and multi-realm operation.

The proposal is intentionally compatibility-aware. Process-global registration
can remain as the default realm, which means existing code keeps working while
the implementation gains a more principled target.

## Backwards Compatibility

Existing `reg-*` functions remain supported. Their first implementation may
continue to mutate the current registrar. The spec change is that those calls
are understood as constructing or updating the default realm's app value.

Applications that do not need multiple realms should not be forced to mention
realms in ordinary code.

## Bead Plan / Reference Implementation

1. Define the minimal internal runtime realm record without changing public
   registration APIs.
2. Move one low-risk singleton behind the realm in tests to validate the shape.
3. Add an internal app-value representation for a subset of registrations.
4. Teach hot-reload tests to diff app values for that subset.
5. Draft public constructor names only after the internal shape proves useful.
6. Document the default realm as compatibility behavior.

## Open Issues

- What are the public constructor names: `rf/app`, `rf/module`,
  `rf/runtime`, `rf/realm`, or something else?
- Is adapter ownership per realm, per render root, or both?
- How does namespace-load `reg-*` sugar interact with explicit app values
  during hot reload?
- How are source coordinates captured in app values without macro-heavy APIs?
- How much of the feature-module shape belongs in the first accepted version?

## Recommendation

Keep this EP as a major proposal for iteration. The direction is high impact
but broad. It should be accepted only after at least one internal spike proves
that moving a real singleton behind a realm simplifies tests and lifecycle
management.

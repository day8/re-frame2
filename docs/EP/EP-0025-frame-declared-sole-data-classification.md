# EP-0025: Registration-Time Subsystem Data Classification

Status: proposal
Type: standards-track

> This is a final-draft proposal. It replaces the earlier frame-declared
> absolute-path model for durable runtime data with a registration-time model:
> app-db state is classified by the frame, and durable runtime state is
> classified by the subsystem registration that defines it. If accepted, the
> normative homes are `spec/015-Data-Classification.md`,
> `spec/005-StateMachines.md`, `spec/012-Routing.md`,
> `spec/016-Resources.md`, `spec/010-Schemas.md`, `spec/Spec-Schemas.md`,
> `spec/Privacy.md`, `spec/Security.md`, and the affected API, tooling,
> examples, skills, and conformance documents.

## Abstract

re-frame2 needs one durable data classification discipline: each durable fact is
classified once, by the owner that defines it.

The frame owns and classifies app-db state. Runtime subsystems own and classify
their durable runtime state at registration time: `reg-machine` classifies
machine snapshot data, `reg-route` classifies route params and query values,
`reg-resource` classifies resource cache entries, and `reg-mutation` classifies
mutation variables.

The framework folds those registration declarations into the frame's effective
egress policy for every live instance of the registered type, including
instances whose runtime keys are generated after construction. Malli schemas
remain validation declarations; schema props are not a durable-state
classification route.

## Motivation

Durable state leaves a frame through trace events, history snapshots, SSR
payloads, MCP reads, Xray, logs, and other framework-mediated egress paths. The
classification policy for that egress must be simple, inspectable, and safe
when applications are split into reusable subsystems.

The prior frame-declared absolute-path model makes a frame name runtime
plumbing it does not own:

```clojure
(rf/reg-frame :checkout
  {:sensitive
   {:app-db [[:rf.runtime/machines
              :snapshots
              :checkout/payment
              :data
              :token]]}})
```

That model has five structural problems.

First, it classifies by place instead of by name. A token in a payment machine
is sensitive because of the meaning of the fact, not because of its current
location in a runtime-db implementation.

Second, it is brittle. A refactor of the runtime subsystem can silently make the
path stale. For a privacy or security property, fail-open on structural drift is
the wrong default.

Third, it cannot express generated runtime keys. Spawned machine actors,
resource cache entries, and mutation work rows are keyed at runtime. A static
frame path cannot name all future instances of a registered type.

Fourth, it is fail-open on reuse. A reusable subsystem mounted in several frames
must be re-classified in every frame. Forget one frame and the same secret
leaks.

Fifth, it creates an inconsistency between subsystems. Resources already prove
that type-level classification can reach generated cache entries by looking up
the resource registration from the entry's recorded resource id. Machines,
routes, resources, and mutations all hold durable runtime state; they should not
use different classification routes.

The missing abstraction is ownership. The subsystem registration that defines a
durable runtime fact knows which parts of that fact are sensitive or large. The
frame then runs the resolved policy; it should not have to spell internal paths
into every subsystem.

## Goals / Non-Goals

Goals:

- define registration-time classification as the public route for durable
  runtime subsystem state;
- keep frame-owned app-db classification from EP-0015;
- fold subsystem declarations into one effective frame egress policy;
- cover generated runtime instances by type-level resolution;
- move resources off Malli schema props and onto first-class `:sensitive` and
  `:large` registration keys;
- define a tighten-only frame override for frame-specific posture;
- preserve schema validation and schema validation-failure redaction;
- make migration and conformance requirements explicit.

Non-goals:

- do not change transient payload classification for events, effects,
  coeffects, subscriptions, flows, or HTTP wire products;
- do not introduce a wildcard path grammar for generated runtime instances;
- do not make Malli schemas a second durable-state classification route;
- do not let frames declassify secrets declared by subsystem registrations;
- do not add compatibility shims for pre-alpha spellings.

## Relationships

- **EP-0015** defines the frame-owned egress policy. This EP completes the
  durable runtime-subsystem part of that policy while preserving frame-owned
  app-db classification and registration-owned transient payload
  classification.
- **EP-0005** defines `reg-machine` `:data-schema` for validation. This EP keeps
  the validation role and rejects schema props as a durable-state
  classification route.
- **EP-0012** defines the `:rf/path` vocabulary used by classification paths.
- **EP-0007** supplies the one-name-per-fact rule. This EP applies it to durable
  classification ownership: one fact, one authoring home.
- **EP-0023** and **EP-0024** define image-loaded frames and the unified frame
  lifecycle. Subsystem classification travels with registrations through the
  image generation a frame runs.
- **EP-0009** governs this EP's lifecycle. If accepted, the specs named in the
  orientation block become the normative homes. Where a final EP and those specs
  differ after graduation, the specs govern.

## Specification

This section uses normative language so acceptance can graduate into specs with
minimal rewriting. At `proposal` status it records the proposed contract; it is
not yet a shipped guarantee.

### Terms

| Term | Meaning |
|---|---|
| **Durable fact** | A value stored in app-db or runtime-db that may be observed later through trace, history, SSR, tools, logs, or other framework egress. |
| **Classification** | A declaration that a path is `:sensitive`, `:large`, or both for egress projection. Sensitive dominates large at the same path. |
| **Owner** | The artefact that defines the durable fact: the frame for app-db state, or a subsystem registration for runtime subsystem state. |
| **Subsystem instance projection** | The stable value shape a subsystem exposes to egress for one live instance. Classification paths are relative to this projection. |
| **Effective frame classification** | The resolved egress policy for a frame after frame declarations and subsystem declarations are folded together. |

### Ownership rule

Each durable fact has one authoring home:

- frame app-db state is classified by the frame;
- machine runtime state is classified by `reg-machine`;
- route runtime state is classified by `reg-route`;
- resource runtime state is classified by `reg-resource`;
- mutation runtime state is classified by `reg-mutation`.

Subsystem runtime-db state must not be classified by deep absolute frame paths
into runtime-db internals. App-db state must not be classified by subsystem
registrations.

The frame may add or raise classification for runtime subsystem state in that
frame, but only through the name-based frame override described below. It may
not declassify a subsystem-declared path.

### Frame app-db classification

Frame app-db classification remains the EP-0015 frame surface:

```clojure
(rf/reg-frame :checkout
  {:sensitive {:app-db [[:auth :token]]}
   :large     {:app-db [[:catalog :items]]}})
```

Those paths are relative to app-db.

### Subsystem classification declarations

Durable subsystem registrars accept first-class `:sensitive` and `:large` keys.
Each value is a vector of `:rf/path` vectors relative to that subsystem's
instance projection. `[[]]` classifies the whole projection.

Examples:

```clojure
(rf/reg-machine :checkout/payment
  {:data-schema PaymentData
   :sensitive   [[:data :gateway-token]]
   :large       [[:data :receipt-html]]})

(rf/reg-route :reset-password
  {:path      "/reset"
   :sensitive [[:query :token]
               [:params :code]]})

(rf/reg-resource :user-profile
  {:params-schema UserProfileParams
   :data-schema   UserProfileData
   :sensitive     [[:params :account-id]
                   [:data :ssn]
                   [:data :dob]]})

(rf/reg-mutation :change-password
  {:params-schema ChangePasswordParams
   :sensitive     [[:params :new-password]]})
```

The schema keys in those examples validate shape only. They do not classify the
durable runtime values.

Malformed classification declarations fail loud at registration time using the
same path-validation discipline as existing marks. Unknown reserved keys fail
loud rather than being ignored.

### Subsystem instance projections

Classification paths are relative to a stable subsystem projection, not to
runtime-db storage internals:

| Registrar | Projection root | Example classified path | Runtime state covered |
|---|---|---|---|
| `reg-machine` | one machine snapshot projection | `[:data :gateway-token]` | the snapshot data path for every live actor of that machine type |
| `reg-route` | the current route projection | `[:query :token]`, `[:params :code]` | current route query and params values |
| `reg-resource` | one cache entry projection | `[:data :ssn]`, `[:params :account-id]` | entry data and the params component of the entry identity |
| `reg-mutation` | one mutation work projection | `[:params :new-password]` | variables/params recorded for every live work row of that mutation type |

The implementation may store these facts at any runtime-db path. The public
classification path names the subsystem projection. The subsystem owns the
mapping from projection root to internal storage.

### Effective classification fold

At each framework-mediated egress boundary, the frame's effective
classification is the union of:

1. frame app-db declarations;
2. frame tighten-only runtime overrides;
3. subsystem registration declarations resolved for the live instance being
   projected.

The fold is defined by name, type, and instance:

1. Identify the frame whose value is being projected.
2. Identify the subsystem type and live instance when the projected value is
   runtime subsystem state.
3. Load the declarations for that subsystem registration.
4. Apply the declaration paths to the subsystem instance projection.
5. Union those declarations with the frame's own egress declarations.

The fold must be complete for every emitted egress value. It may be implemented
eagerly, lazily, or with caches, but those choices must not change the external
contract. Tools must be able to ask for the effective classification of a named
frame and instance and receive the same resolved policy the projector uses.

Sensitive dominates large after union. A path classified as sensitive by any
owner is sensitive, even if another owner classifies it only as large.

### Generated runtime instances

A subsystem declaration applies to every live instance of the registered type,
including instances with generated keys.

Examples:

- `reg-machine :checkout/payment :sensitive [[:data :gateway-token]]` applies
  to the singleton actor and to every spawned actor of that machine type.
- `reg-resource :user-profile :sensitive [[:data :ssn]]` applies to every cache
  entry whose recorded resource id is `:user-profile`, regardless of the opaque
  cache key.
- `reg-mutation :change-password :sensitive [[:params :new-password]]` applies
  to every live work row for that mutation type.

The programmer names the registered type. The framework resolves the live
instances. No wildcard path grammar is required.

### Frame runtime overrides

The frame may add or raise runtime subsystem classification for frame-specific
posture. The override is name-based, not a deep runtime-db path.

Illustrative shape:

```clojure
(rf/reg-frame :audited-checkout
  {:sensitive
   {:app-db [[:auth :token]]
    :runtime {:machine  {:checkout/payment [[:data :gateway-debug]]}
              :resource {:user-profile     [[:data :email]]}}}

   :large
   {:runtime {:resource {:audit-log [[:data]]}}}})
```

The exact map shape should graduate into `spec/015-Data-Classification.md`, but
the semantic rule is fixed by this EP:

- a frame override may add a path not declared by the subsystem;
- a frame override may raise a path from large to sensitive;
- a frame override may not remove, weaken, or declassify a subsystem
  declaration.

There is no `:public`, `:not-sensitive`, or equivalent frame spelling for a
subsystem-declared path. If a future audited declassification feature is needed,
it must be a separate EP or standards-track amendment.

### Schemas validate; they do not classify durable state

`:data-schema`, `:params-schema`, `:page-data-schema`, and related Malli schemas
describe and validate value shape. Malli `:sensitive?` or `:large?` props on
those schemas do not classify durable runtime state for egress.

Schema-owned redaction remains valid for schema-owned products:

- schema validation-failure traces may redact the invalid value according to the
  schema redactor;
- HTTP decode-schema props may classify transient decoded response bodies;
- HTTP request scrubbing may classify outbound request material.

Those are not durable runtime-db classification routes.

### Queryable effective classification

The effective classification for a frame remains one queryable product. Xray,
MCP, SSR projection, trace projection, and error reporters must not each
recompute policy from unrelated subsystem APIs.

An implementation may store the resolved product in an elision registry, compute
it lazily from registrations, or cache it per frame and instance. The observable
contract is one complete effective policy for the frame and projected instance.

Diagnostics should expose:

- which frame contributed a classification;
- which subsystem registration contributed a classification;
- which live instance was projected;
- which contribution won when sensitive and large overlapped.

### Non-user runtime state

Framework-internal runtime slots that carry ids, counters, indexes, versions, or
bookkeeping are not user data and do not need classification surfaces. If a
future runtime subsystem stores durable user data, it must define an owner and a
classification projection before exposing that data through framework egress.

## Examples

### Payment machine

```clojure
(rf/reg-machine :checkout/payment
  {:data-schema PaymentData
   :sensitive   [[:data :gateway-token]
                 [:data :card-fingerprint]]
   :large       [[:data :receipt-html]]})
```

Every trace, snapshot, SSR projection, or tool read that emits the payment
machine's data must project those paths for every live payment actor.

### Password reset route

```clojure
(rf/reg-route :reset-password
  {:path      "/reset/:code"
   :sensitive [[:params :code]
               [:query :token]]})
```

The reset token is declared with the route that defines it. A frame using that
route does not need to know where the routing subsystem stores `:current`.

### Resource cache entry

```clojure
(rf/reg-resource :user-profile
  {:params-schema [:map [:account-id :uuid]]
   :data-schema   UserProfile
   :sensitive     [[:params :account-id]
                   [:data :ssn]
                   [:data :dob]]
   :large         [[:data :avatar-bytes]]})
```

The declaration covers every cache entry for `:user-profile`, including entries
stored under generated opaque keys.

### Frame-specific tightening

```clojure
(rf/reg-frame :support-console
  {:sensitive
   {:runtime {:resource {:user-profile [[:data :email]
                                        [:data :phone]]}}}})
```

The support console may be more observable than the ordinary product frame, so
it can redact additional user-profile paths. It cannot unredact a path the
resource registration declared sensitive.

## Rationale

The design follows the Clojure preference for simple values and explicit
ownership. A subsystem registration is the data value that names the
subsystem's meaning. Classification attached there travels with the image and
the frame that use it. That is smaller and safer than asking every frame to know
every runtime storage path.

It also follows the re-frame2 execution model. A frame is the isolated
execution context, but a frame is not the author of every fact it runs. The
frame owns the fold and the egress boundary. Subsystem registrations own the
meaning of their durable runtime facts. The fold composes those values into one
effective policy.

The frame override is intentionally narrow. It handles the real posture case:
some frames are more observable than others and therefore need additional
redaction. It does not permit weakening subsystem declarations. For genuine
secrets, sensitivity is intrinsic to the fact, not a property a frame should
silently remove.

Resources are the proof that the model is practical. A resource cache entry is
stored under generated keys, but the entry records which resource registration
it came from. Resolving registration declarations to live instances generalizes
that already-working idea to machines, routes, and mutations.

## Rejected Alternatives

### Frame absolute paths as the sole mechanism

Rejected. The frame would classify by runtime-db storage position, not by the
owner that defines the fact. This is brittle under refactor, fail-open on
omission, unable to express generated instances, and forces frames to know
subsystem internals.

### Malli schema props as durable classification

Rejected. Schemas validate shape. Classification is an egress policy. Burying
durable egress policy inside validation props creates a second route to the same
fact and makes ownership unclear.

### Keep resources on schema props only

Rejected. Resources, machines, routes, and mutations all store durable runtime
facts. Keeping resources on a schema-prop classification path while moving other
subsystems to first-class registration keys would preserve the inconsistency
this EP is meant to remove.

### Wildcard runtime-db paths

Rejected. A wildcard path grammar would still classify by storage position and
would add another path language. Type-level registration declarations already
cover generated instances without wildcards.

### Frame declassification overrides

Rejected. A frame may need more redaction, not silent removal of a subsystem's
declared protection. Declassification is a separate high-risk feature and is out
of scope.

## Security And Privacy Considerations

This EP improves the safety posture in two ways.

First, classification travels with the subsystem definition. A payment machine,
reset route, user-profile resource, or password mutation carries its secret
declarations into every frame that runs it. Reuse is safe by default.

Second, classification is stable under runtime storage refactors. The public
declaration names the subsystem projection, not internal runtime-db plumbing.

The irreducible risk remains: a subsystem author can forget to classify a
secret. Conformance and examples should therefore include positive and negative
fixtures for every durable subsystem, including generated-instance cases.

Projection still happens only at framework-mediated egress boundaries. The
application sees and processes its real values while events run.

## Backwards Compatibility

re-frame2 is pre-alpha. No compatibility shim is required.

Migration is source-level:

- move machine durable classification from frame absolute runtime paths or
  schema props to `reg-machine` `:sensitive` / `:large`;
- move resource and mutation durable classification from schema props to
  `reg-resource` / `reg-mutation` `:sensitive` / `:large`;
- add route declarations for URL secrets with `reg-route` `:sensitive`;
- keep schema validation and schema validation-failure redaction;
- replace any frame runtime absolute-path override with the name-based
  tighten-only override shape that graduates from this EP.

Examples:

```clojure
;; old: durable classification hidden in schema props
[:map
 [:ssn {:sensitive? true} :string]]

;; new: schema validates; registration classifies
(rf/reg-resource :user-profile
  {:data-schema UserProfile
   :sensitive   [[:data :ssn]]})
```

```clojure
;; old: frame knows machine storage
{:sensitive {:app-db [[:rf.runtime/machines
                       :snapshots
                       :checkout/payment
                       :data
                       :gateway-token]]}}

;; new: machine owns the meaning of its data
(rf/reg-machine :checkout/payment
  {:sensitive [[:data :gateway-token]]})
```

## Bead Plan / Reference Implementation

### Spec graduation

Update:

- `spec/015-Data-Classification.md` for the ownership rule, projection fold,
  frame override, diagnostics, and examples;
- `spec/005-StateMachines.md` for `reg-machine` `:sensitive` / `:large`;
- `spec/012-Routing.md` for `reg-route` URL-secret classification;
- `spec/016-Resources.md` for `reg-resource` and `reg-mutation`
  classification, replacing schema-prop durable classification;
- `spec/010-Schemas.md` and `spec/Spec-Schemas.md` to state that schema props
  do not classify durable runtime state;
- `spec/Privacy.md` and `spec/Security.md` for the safe-by-default and
  tighten-only rules;
- `spec/API.md`, docs, examples, tools, skills, and migration material that
  teach the affected registration surfaces.

### Implementation slices

Expected slices:

1. Add `:sensitive` and `:large` parsing to `reg-machine`, `reg-route`,
   `reg-resource`, and `reg-mutation`.
2. Add or update normalizers so all declarations use the shared `:rf/path`
   grammar and fail loudly on malformed input.
3. Fold subsystem declarations into effective frame classification at egress.
4. Resolve generated instances by registered type for machines, resources, and
   mutations.
5. Move resource and mutation durable classification off schema props.
6. Preserve schema validation-failure redaction.
7. Add the name-based, tighten-only frame runtime override if still needed by
   examples or tooling.
8. Expose effective classification provenance to Xray, MCP, SSR, trace, and
   diagnostics.
9. Remove documentation and examples that teach durable classification via
   schema props or frame absolute runtime paths.
10. Add conformance coverage for every subsystem, including generated-instance
    positive cases and schema-prop-alone negative cases.

Guide-impact assessment:

- privacy and elision guide material should teach "classify where the durable
  fact is defined";
- machine/resource/route/mutation examples should show first-class
  `:sensitive` declarations;
- testing and tool docs should explain that effective classification is
  inspectable as one frame policy.

## Open Issues

None for acceptance.

The implementation may choose eager installation, lazy resolution, or caching
for effective classification. That is an implementation choice as long as the
observable policy and diagnostics are complete and consistent.

Any future declassification mechanism is out of scope and requires a separate
standards-track decision.

## Recommendation

Accept the registration-time subsystem classification model.

It gives each durable fact one authoring home, keeps frame-owned app-db
classification intact, removes schema props as a durable-state classification
route, reaches generated runtime instances, and restores the name-over-place
discipline that re-frame2 needs for safe introspection and replayable tooling.

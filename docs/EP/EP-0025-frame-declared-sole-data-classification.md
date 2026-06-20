# EP-0025: Frame-Declared Paths As The Sole Durable Data Classification Mechanism

Status: proposal
Type: standards-track

> This standards-track EP records Mike's 2026-06-20 ruling on `rf2-398kql`:
> durable frame-state classification is declared on the frame, not on schemas.
> It removes the EP-0005 machine `:data-schema` -> marks redaction bridge and
> makes frame-declared `:sensitive` / `:large {:app-db ...}` paths the sole
> classification route for durable app-db and runtime-db values. On acceptance,
> the normative home is `spec/015-Data-Classification.md`, with reconciling
> edits in `spec/005-StateMachines.md`, `spec/010-Schemas.md`, and the dependent
> API, privacy, security, migration, examples, skills, and conformance text that
> currently teaches the superseded rule. After graduation, where this EP and the
> specs differ, the specs govern.

## Abstract

re-frame2 has one owner for durable frame state: the frame. Therefore secrets
and large values inside durable app-db or runtime-db state are classified only by
frame-declared paths.

The important consequence is that a Malli `:sensitive?` or `:large?` property on
a machine `:data-schema` slot no longer classifies the machine's durable `:data`
for trace, SSR, epoch, tool, or observability egress. The `:data-schema` still
validates machine `:data`, and schema props still redact schema-validation
failure records. Resource schemas, HTTP decode schemas, and outbound HTTP
request scrubbing keep their existing transient-wire-product semantics.

## Motivation

EP-0015 made frame-owned paths the public model for durable app-db
classification, but the machine implementation still had a second durable-state
route inherited from EP-0005:

- `reg-machine` walked `:data-schema` for `:sensitive?` / `:large?` field props;
- the runtime recorded those props in a process side table keyed by machine or
  actor id;
- spawn, destroy, and restore paths had to re-key or clear those derived marks;
- trace and SSR egress unioned those schema-derived marks with ordinary
  frame/registration marks at read time.

That gives authors two ways to say the same durable fact:

```clojure
;; Old route: classification hidden in the machine data schema.
(rf/reg-machine :checkout/payment
  {:data-schema [:map
                 [:token {:sensitive? true} :string]]})

;; Frame route: classification declared by the frame that owns the state.
(rf/reg-frame :checkout
  {:sensitive
   {:app-db [[:rf.runtime/machines :snapshots
              :checkout/payment :data :token]]}})
```

Two equivalent public routes are expensive in all the wrong places. A reader has
to know both. Tooling has to ask both. The implementation has to keep a
side-table in sync with a durable runtime-db value that already has a concrete
path. The result violates the EP-0007 "one name per fact" discipline and leaves
the owner boundary blurry: the machine definition describes the shape of
`:data`, but the frame owns the durable state that crosses observation
boundaries.

This is not just a cleanup bead because it reverses part of a final EP
(EP-0005's machine `:data-schema` redaction bridge) and changes public schema
prop semantics. Per EP-0009, that belongs in an EP.

## Goals / Non-Goals

Goals:

- define frame-declared paths as the sole durable app-db/runtime-db
  classification route;
- remove the machine `:data-schema` -> marks bridge end to end;
- preserve machine `:data-schema` validation;
- preserve schema-validation-failure redaction driven by schema props;
- preserve resource, mutation, HTTP decode, and HTTP request classification where
  those schemas own transient wire products rather than durable frame state;
- make the migration and conformance criteria explicit enough that a partial
  cleanup cannot accidentally pass review.

Non-goals:

- no wildcard or pattern path grammar is introduced for "all instances of this
  machine type";
- no top-level `:sensitive` / `:large` key is added to `reg-machine`;
- no compatibility shim is provided for pre-alpha code that relied on machine
  schema props to redact durable `:data`;
- no change is made to real values inside the app while events are processed;
  projection still happens only at framework-mediated egress boundaries;
- no new human-facing guide chapter is required unless an existing guide page
  still teaches the old machine-schema classification route.

## Relationships

- **Partially supersedes:** [EP-0005](EP-0005-machine-data-schema.md). The
  `:data-schema` rename, validation, visualization, and XState parity stand.
  Only the machine `:data-schema` -> marks redaction bridge is reversed.
- **Completes:** [EP-0015](EP-0015-frame-owned-egress-policy.md). EP-0015 made
  frame-owned durable classification the public model; this EP removes the last
  schema-attached durable-state exception.
- **Applies:** [EP-0007](EP-0007-one-name-per-fact.md). `:sensitive` remains the
  frame/registration path-set spelling; `:sensitive?` remains a boolean schema
  slot property for surviving schema-owned egress products.
- **Uses:** [EP-0012](EP-0012-path-optics-and-canonical-forms.md). Frame
  declarations are concrete `:rf/path` vectors.
- **Governed by:** [EP-0009](EP-0009-the-ep-process.md). This EP records a
  standards-track amendment to final specs rather than hiding the reversal in a
  spec-only edit.
- **Graduates into:** `spec/015-Data-Classification.md`, including its Abstract,
  "minimum claim", and ownership split; `spec/005-StateMachines.md` privacy and
  registration sections; `spec/010-Schemas.md` schema-prop semantics; and the
  dependent `spec/Spec-Schemas.md`, `spec/Privacy.md`, `spec/Security.md`,
  `docs/api`, migration, examples, skills, and conformance text.

## Specification

### Durable frame state is frame-classified

A durable path in a frame's state is classified sensitive or large only by the
frame's declaration:

```clojure
(rf/reg-frame :checkout
  {:sensitive
   {:app-db [[:auth :token]
             [:rf.runtime/machines :snapshots
              :checkout/payment :data :payment :token]]}

   :large
   {:app-db [[:documents :raw-csv]
             [:rf.runtime/machines :snapshots
              :checkout/payment :data :payment :receipt-pdf]]}})
```

The existing `:app-db` path bucket remains the frame-owned durable-path bucket
from EP-0015. This EP does not introduce a second `:runtime-db` classification
key. Runtime-db classification is expressed by concrete paths rooted at reserved
runtime-db keys such as `[:rf.runtime/machines :snapshots ...]`.

The frame's elision registry and `rf/elide-wire-value` / `rf/project-egress`
remain the source of truth at observation boundaries. Sensitive still wins over
large.

### Machine `:data` is durable runtime-db state

A machine snapshot lives in the frame's runtime-db partition at:

```clojure
[:rf.runtime/machines :snapshots <actor-id>]
```

The snapshot's `:data` slot is therefore durable frame state. To redact it, the
frame declares the absolute snapshot path:

```clojure
(rf/reg-frame :checkout
  {:sensitive
   {:app-db [[:rf.runtime/machines :snapshots
              :checkout/payment :data :token]]}})

(rf/reg-machine :checkout/payment
  {:data-schema [:map
                 [:token :string]] ; validates only; does not classify
   :initial :collecting
   :states {:collecting {:on {:submit :charging}}
            :charging   {}
            :done       {}}})
```

Trace, epoch, SSR, tool, and observability egress re-root the frame declaration
from the absolute runtime-db path to the snapshot-shaped payload being emitted.
The same declaration must cover the machine payload shapes that expose durable
`:data`: `:before`, `:after`, `:snapshot`, `:data`, `:input :data`, and
`:cascade :data-delta`; SSR hydration must apply the same policy to the
projected `:rf/runtime-db` payload.

### Machine schema props no longer classify durable data

A `:sensitive?` or `:large?` Malli prop on a machine `:data-schema` slot does
not classify durable machine `:data` for egress.

Conforming implementations remove:

- registration-time extraction of machine data-schema marks;
- the per-machine/per-instance schema-marks side table;
- spawn-time re-keying of schema-derived marks;
- destroy/finalize/restore cleanup or rehydration for those marks;
- read-time union of machine schema marks into `marks-for`.

`reg-machine` also continues to reject or ignore any attempted top-level machine
classification key according to the existing `rf2-0k5ubx` ruling:
classification belongs on the frame, not on the machine spec.

### Schema props that remain

This EP does not remove `:sensitive?` / `:large?` from Malli schemas. It narrows
their public meaning:

- machine `:data-schema` `:sensitive?` / `:large?` props still apply to the
  validator's own validation-failure trace product;
- resource `:data-schema` and `:params-schema` props still classify resource
  wire/runtime-subsystem products according to Spec 016;
- HTTP `:decode` schema props still classify decoded HTTP response bodies;
- HTTP request `:sensitive?` still scrubs outbound request material;
- registration metadata `:sensitive` / `:large` still classifies transient
  payloads owned by event, fx, cofx, sub, flow, and similar registrations.

The rule is not "schemas never classify." The rule is: schemas do not provide a
second route to classify a durable app-db/runtime-db path that the frame owns.

### Spawned actors

Frame declarations are concrete paths. A singleton actor and a spawned actor
with a stable `:fixed-actor-id` can be classified statically by declaring that
actor id's snapshot path.

For generated spawned actor ids, this EP intentionally does not add a wildcard
or type-level pattern declaration such as "all snapshots whose
`:rf/machine-type` is `:checkout/payment`." Such a pattern grammar may be useful
later, but it is a separate design surface: it would need its own path
semantics, specificity rules, conformance tests, and interaction with
EP-0012. Under this EP, generated actors that hold durable sensitive `:data`
need either stable actor ids for classified slots or a later frame-owned pattern
proposal.

## Rationale

The simple model is the correct one:

> durable frame state -> frame classification
> transient schema-owned product -> schema props
> transient registration-owned payload -> registration metadata

Machine snapshots are not a special privacy island. They are durable
runtime-db values inside a frame-state value. They are restored, serialized,
hydrated, inspected, and projected as part of that frame. Classifying them
through the frame gives tools and readers one place to look.

The removed bridge was a small language of its own: schema props were extracted,
re-rooted under `[:data ...]`, copied into a side table, re-keyed for spawned
instances, cleared on destroy, restored after hydration, and unioned at read
time. That complexity existed only because two owners were allowed to classify
the same durable path.

The cost is real and should be visible: type-level schema props were convenient
for "every instance of this machine type." This EP chooses consistency over that
convenience. If re-frame2 later needs type-level or wildcard classification for
spawned actors, it should be designed as a frame-owned path/pattern mechanism,
not by reviving the schema bridge.

## Rejected Alternatives

### Keep both routes

Rejected. Keeping schema props and frame paths as co-equal durable
classification routes preserves the ambiguity EP-0015 was meant to remove.
It also keeps the side table and lifecycle machinery.

### Prefer machine schema props for machine `:data`

Rejected. A machine definition owns the shape and validation of `:data`; the
frame owns the durable value and its egress policy. Putting durable-state policy
on the schema makes machine `:data` the only runtime-db state with a second
classification owner.

### Add `:sensitive` / `:large` to `reg-machine`

Rejected. That creates another machine-local policy surface and competes with
the frame declaration. It also fails to solve frame-specific policy: the same
machine type can run in frames with different observability posture.

### Add wildcard or type-level paths in this EP

Rejected for this EP. Wildcards may be useful for generated actor ids, but they
are not a small amendment. They need a path grammar, specificity rules, conflict
resolution with concrete paths, and tool support. The pre-alpha posture lets us
trim the current bridge first and design patterns only if real usage demands
them.

### Keep a compatibility shim

Rejected. The project is pre-alpha, the old rule is a footgun, and shimming it
would keep the implementation path alive. Migration should be explicit.

## Security And Privacy Considerations

This EP is a privacy-contract simplification, not a weakening of the projection
boundary. Values still flow raw inside the application and are projected at
framework-mediated egress.

The migration risk is missed declarations. A machine `:data-schema`
`{:sensitive? true}` slot that used to redact durable snapshot egress will no
longer do so unless the frame declares the corresponding snapshot path. That is
why the acceptance criteria include positive and negative tests, SSR coverage,
and a migration-skill update.

## Backwards Compatibility And Migration

No compatibility shim is provided.

Pre-alpha migration is mechanical:

1. Find machine `:data-schema` slots carrying `:sensitive?` or `:large?`.
2. Decide whether the slot is durable data that can cross trace/SSR/tool egress.
3. Add the corresponding frame declaration:

   ```clojure
   (rf/reg-frame :checkout
     {:sensitive
      {:app-db [[:rf.runtime/machines :snapshots
                 :checkout/payment :data :token]]}})
   ```

4. Keep schema props only where they are still useful for validation-failure
   trace redaction.
5. For generated spawned actors that hold durable secrets, prefer a stable
   `:fixed-actor-id` for that sensitive actor or defer until a frame-owned
   pattern-path proposal exists.

The login examples had a documentary `:sensitive?` on an event-arg schema where
the password never became durable app-db/runtime-db state. That prop should be
dropped; the live protection remains the HTTP request `:sensitive?` scrub.

## Acceptance Criteria

A complete implementation of this EP satisfies all of the following:

- a machine `:data-schema` `:sensitive?` / `:large?` prop by itself does not
  redact durable machine `:data` in trace, SSR, epoch, tool, or observability
  egress;
- a frame-declared snapshot path redacts or elides the matching machine `:data`
  slot in every machine egress shape: `:before`, `:after`, `:snapshot`, `:data`,
  `:input :data`, `:cascade :data-delta`, and SSR `:rf/runtime-db`;
- validation-failure traces for invalid machine `:data` still use the schema
  redactor and do not leak schema-marked failing values;
- resource and HTTP schema classification behaviour is unchanged;
- implementation code contains no machine schema-marks side table or lifecycle
  hooks;
- Spec 015's Abstract, minimum claim, and ownership split no longer say machine
  `:data` durable classification is schema-prop-owned;
- `spec/005-StateMachines.md`, `spec/010-Schemas.md`, `spec/Spec-Schemas.md`,
  `spec/Privacy.md`, `spec/Security.md`, `docs/api`, migration guidance,
  examples, skills, and conformance fixtures no longer teach the superseded
  durable machine-schema classification route;
- conformance includes both the positive frame-declared case and the negative
  schema-prop-alone case.

## Bead Plan / Reference Implementation

The implementation is carried by **rf2-398kql** as one coherent change. If any
slice is not already represented by child beads, create one for it rather than
burying it in a large undifferentiated PR:

- **Implementation:** remove `machine-id->schema-marks`,
  `declare-machine-schema-marks!`, `clear-machine-schema-marks!`,
  `merge-schema-marks`, registration extraction, spawn/destroy/restore mark
  lifecycle, and any late-bind hooks that existed only for the bridge.
- **Projection:** keep `frame-snapshot-marks` or equivalent frame-owned
  re-rooting for machine trace and SSR projection.
- **Specs:** reconcile Spec 015, Spec 005, Spec 010, Spec-Schemas, Privacy, and
  Security.
- **Docs and migration:** update `docs/api`, migration skills, and any guide text
  that still describes machine `:data-schema` props as durable classification.
- **Examples:** remove documentary schema props where frame/HTTP declarations are
  the real protection.
- **Tests/conformance:** add the positive/negative fixtures named in the
  acceptance criteria.

Guide impact: no new conceptual guide chapter is required if the guide already
teaches frame-owned durable classification. Existing guide/API/migration text
must still be corrected wherever it teaches the old machine-specific exception.

## Open Issues

None pending an operator ruling. The dynamic-generated-actor limitation is an
intentional non-goal of this EP, not an unresolved issue.

## Recommendation

Accept. This is the consistent end-state of EP-0015: one durable-state owner,
one durable-state classification route. The removed machine bridge bought some
type-level convenience for spawned actors, but it also preserved a second public
model, a side table, and a lifecycle. In pre-alpha, the better trade is to trim
the bridge, make migration explicit, and design any future pattern-path feature
as frame-owned from the start.

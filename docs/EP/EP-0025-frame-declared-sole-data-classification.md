# EP-0025: Frame-Declared Paths As The Sole Data Classification

Status: proposal
Type: standards-track

> This EP records the ruling (Mike, 2026-06-20, rf2-398kql) that **frame-declared
> secret paths become the SOLE app-db / runtime-db data-classification
> mechanism**, killing schema-attached `:sensitive?` / `:large?` *field*
> classification — most notably reversing the EP-0005 machine `:data-schema`→marks
> redaction bridge. On acceptance this graduates into `spec/015-Data-Classification.md`
> (the authoritative home), with reconciling edits in `spec/005-StateMachines.md`
> and `spec/010-Schemas.md`; where this EP and the spec differ, the spec governs.

## Abstract

One mechanism, one place: secrets and large values in a frame's durable state
(app-db AND the runtime-db partition — machine snapshots, etc.) are classified
**only** by frame-declared `:rf/path` vectors via `reg-frame` `:sensitive` /
`:large {:app-db …}` (EP-0015). Schema-attached per-slot `:sensitive?` /
`:large?` props no longer classify a durable path for egress. This reverses the
EP-0005 schema→marks redaction bridge for machine `:data`. Schema VALIDATION,
validation-failure-trace redaction, the resource / HTTP-body owner-local schema
classifiers (transient wire products), and the HTTP-request `:sensitive?`
wire-scrub are all UNCHANGED.

## Motivation

EP-0015 made frame-declared paths the durable app-db classification surface but
left two schema-attached classification routes alive: (a) `reg-app-schema`
`:sensitive?` / `:large?` slot props feeding the frame's app-db egress registry
(already retired in EP-0015 §8), and (b) the EP-0005 machine `:data-schema`→marks
bridge — `register-data-schema-marks!` extracting per-`:data`-slot props,
rooting them under `[:data …]`, and recording them in a per-machine /
per-instance schema-marks side-table that `marks-for` unioned at read time, with
a spawn/destroy/restore lifecycle to keep it in lock-step with live actors.

Two co-equal routes to classify the same durable path is exactly the duplication
EP-0007 (one name per fact) and EP-0015 exist to remove: an author can declare a
machine `:data` slot sensitive on the schema OR on the frame, and a reader must
check both. The machine bridge also carried real implementation weight (a
process-scoped side-table, a spawn-time per-instance re-keying, a destroy-time
clear, a restore-time rehydrate) for a classification a frame can express
directly against the snapshot's runtime-db path. The ruling collapses this to
one mechanism.

This is a public-contract change that reverses a `final` EP (EP-0005's redaction
half) and adjusts public Spec 010 schema-prop semantics, so per EP-0009 it
graduates through an EP rather than a silent multi-spec edit.

## Goals / Non-Goals

**Goals.**

- Frame-declared `:sensitive` / `:large {:app-db …}` paths are the SOLE app-db /
  runtime-db data-classification mechanism (durable machine `:data` included).
- Remove the EP-0005 machine `:data-schema`→marks redaction bridge end-to-end:
  the per-machine / per-instance schema-marks side-table, the registration-time
  extraction, the spawn-time per-instance bridge, the destroy/finalize/restore
  marks lifecycle.
- Machine `:data` trace-egress and SSR-hydration redaction continue to work via
  the surviving frame-owned mechanism (frame declares the snapshot path;
  egress re-roots it snapshot-relative).

**Non-Goals.**

- `:data-schema` **validation** is untouched (EP-0005's rename + validation
  stand). Only the schema→MARKS classification bridge is reversed.
- Validation-FAILURE-trace redaction (the schema's own egress product) is
  untouched — a `:data-schema` `:sensitive?` slot still redacts the failing
  value before the `:rf.error/schema-validation-failure` trace ships. That is a
  different axis from durable-`:data` classification.
- The resource `:data-schema` / `:params-schema` and HTTP-body `:decode`
  per-slot `:sensitive?` / `:large?` props stay (they classify *transient* wire
  products, not durable frame state — there is no competing frame-config route
  for those shapes).
- HTTP-request `:sensitive?` (Spec 014 wire-scrub of an outbound request) stays
  — a different axis (outbound request, not a durable app-db path) with no
  frame-declared replacement.

## Relationships

- **Supersedes (in part):** [EP-0005](EP-0005-machine-data-schema.md) — its
  `:data-schema`→marks **redaction bridge** half. The rename + validation +
  viz + XState parity stand.
- **Completes:** [EP-0015](EP-0015-frame-owned-egress-policy.md) — frame-owned
  durable classification; EP-0025 makes it the *sole* mechanism by removing the
  last schema-attached durable-path route (the machine bridge). Reverses the
  2026-06-11 EP-0015-issue-12 "no supersession of EP-0005" disposition for the
  redaction half.
- **Grounded by:** [EP-0007](EP-0007-one-name-per-fact.md) — the `:sensitive`
  (path map) vs `:sensitive?` (per-slot prop) cross-layer distinction; EP-0025
  narrows `:sensitive?` to transient schema-owned products only.
- **Graduates into:** `spec/015-Data-Classification.md` (authoritative), with
  reconciling edits in `spec/005-StateMachines.md` §Privacy / §Registration and
  `spec/010-Schemas.md` (`:large?` / `:sensitive?` consumers).

## Specification

1. **Sole app-db / runtime-db mechanism.** A durable path in a frame's state —
   an app-db path OR a runtime-db path (machine snapshots at
   `[:rf.runtime/machines :snapshots <actor-id> …]`, etc.) — is classified
   sensitive / large ONLY by a frame-declared `:rf/path` vector via `reg-frame`
   `:sensitive` / `:large {:app-db …}`. The wire-egress walker
   (`rf/elide-wire-value`) and the per-frame elision registry are the single
   source of truth.

2. **Machine `:data` is frame-classified.** To redact a durable machine `:data`
   slot at trace / SSR egress, the frame declares its runtime-db snapshot path:

   ```clojure
   (rf/reg-frame :checkout
     {:sensitive {:app-db [[:rf.runtime/machines :snapshots :checkout/payment :data :token]]}})

   (rf/reg-machine :checkout/payment
     {:data-schema [:map [:token :string]]   ;; VALIDATES :data; does NOT classify it
      :initial :collecting :states { … }})
   ```

   The trace-egress chokepoint and the SSR-hydration projector re-root the
   frame's declaration snapshot-relative (strip the
   `[:rf.runtime/machines :snapshots <actor-id>]` prefix) and redact / elide the
   matching slot in the snapshot-shaped payloads (`:before` / `:after` /
   `:snapshot` / `:data` / `:input :data` / `:cascade :data-delta`) and the SSR
   `:rf/runtime-db` `:data` map.

3. **Schema-field classification is removed.** A `:sensitive?` / `:large?` Malli
   prop on a machine `:data-schema` slot does NOT classify the machine's durable
   `:data` for egress. `reg-machine` runs no schema→marks bridge; there is no
   per-machine / per-instance schema-marks side-table and no spawn / destroy /
   restore marks lifecycle.

4. **Explicitly KEPT.** `:data-schema` validation; validation-failure-trace
   redaction (schema `:sensitive?` props); resource `:data-schema` /
   `:params-schema` and HTTP-body `:decode` per-slot classification of transient
   wire products; HTTP-request `:sensitive?` wire-scrub. `reg-machine` carries no
   top-level `:sensitive` / `:large` key (rf2-0k5ubx) — classification belongs on
   the frame.

## Rationale

Per-slot props on a declared schema *are* owner-declares-policy where the
schema's product is the owner's natural egress unit and is *transient* — a
resource's request/response shape, an HTTP reply body. A machine's `:data`, by
contrast, is *durable frame state* living in the frame's runtime-db partition; it
is governed by the same lifecycle, the same epoch/restore semantics, and the same
egress boundaries as every other durable path. Classifying it the same way every
other durable path is classified — on the frame — is the consistent model and
removes a whole side-table + lifecycle that existed only to make a second route
work. One owner, one route.

## Backwards Compatibility

Pre-alpha, no compatibility shim. A machine that relied on a `:data-schema`
`:sensitive?` slot to redact durable `:data` migrates by declaring the snapshot
path on its frame (`reg-frame` `:sensitive {:app-db [[:rf.runtime/machines
:snapshots <id> :data …]]}`). The login examples carried a documentary-only
`:sensitive?` on an event-arg schema (the password never lands in app-db); that
prop is dropped, and the working redaction — the HTTP-request `:sensitive?` flag
— is unchanged.

## Bead Plan / Reference Implementation

Carried by **rf2-398kql** as one coherent change (impl + examples + spec +
EP-0025 land together; impl does not ship ahead of spec):

- core `re-frame.marks` — remove the `machine-id->schema-marks` table,
  `declare-machine-schema-marks!` / `clear-machine-schema-marks!` /
  `merge-schema-marks` + late-bind hooks; `marks-for` returns registrar-author
  marks uniformly; add `frame-snapshot-marks` (frame-owned snapshot-path
  re-rooting) consumed by `project-machine-tags` / `project-machine-error-tags`.
- `re-frame.machines` — remove `register-data-schema-marks!` + call sites
  (reg-machine home, spawn, restore) + `clear-actor-schema-marks!` lifecycle;
  SSR projector redacts `:data` against frame-declared paths. `:data-schema`
  validation KEPT.
- examples (reagent/uix/helix login) — drop the documentary `:sensitive?` slot
  prop; HTTP `:sensitive?` kept.
- spec 015 / 005 / 010 reconciled; EP-0005 redaction half marked superseded;
  conformance fixtures + per-artefact tests reconciled to the frame-owned model.

No human-facing `docs/guide` chapter changes yet (the classification guidance
already teaches frame-owned declaration; the machine-specific schema-prop note,
if any, is the migration mechanic above).

## Open Issues

None pending an operator ruling — the ruling is recorded (Mike, 2026-06-20). Open
for accept→final.

## Recommendation

Accept. The single-mechanism model is the consistent end-state of EP-0015; the
machine bridge was the last schema-attached durable-path classification route,
and removing it deletes a side-table + lifecycle with no loss of expressiveness
(the frame can declare the snapshot path directly).

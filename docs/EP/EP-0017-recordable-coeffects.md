# EP-0017: Recordable Coeffects

Status: final
Type: standards-track

> This EP unifies re-frame2's coeffect surface around one principle — **durable
> state folds facts, never reads** — by renaming `:rf.world/inputs` to
> **`:rf.cofx`** (a flat map of recordable coeffects on every causal token),
> adding **`:rf.cofx/requires`** (registration-time declaration of a handler's
> coeffect dependencies, both grades), **removing `inject-cofx`**, and changing
> `reg-cofx` from a ctx-transform to a value-returning supplier with a
> registration grade (`:recordable?` / `:provided?`). Handlers receive exactly
> the facts they declare, delivered flat; strict replay fails loudly when a
> required value is missing from the record. Normative home after acceptance:
> `spec/002-Frames.md` (envelope + event-context contract),
> `spec/001-Registration.md` (registrar contract + metadata key),
> `spec/Conventions.md` (fact naming), `spec/005-StateMachines.md` (machine
> attachment), with schema registrations in `spec/Spec-Schemas.md` and trace
> vocabulary in `spec/009-Instrumentation.md`.

> **`accepted` means the decisions are settled; the build is pending (ruling
> recorded 2026-06-13, Mike).** Accepted with all twelve dispositions in
> [§Open Issues](#open-issues) **as recommended**: (1) flat `:rf.cofx`
> envelope; (2) the declaration key is `:rf.cofx/requires`; (3) remove
> `inject-cofx` with value-returning `reg-cofx`, no alias, in slice A; (4) no
> implicit time — `:rf/time-ms` is the framework's provided registration,
> delivered only on declaration; (5) declared-only delivery; (6) no framework
> generators (apps own supplier semantics); (7) provided registrations for
> generator-less boundary facts; (8) mint policies and their binding points
> (`:live` router default, `:strict` hard-wired for replay and the `:test`
> preset default, `:explicit-live` escape); (9) supplied-value validation a
> hard error in production as well as dev; (10) machine consumer-attachment on
> named entries with pre-selection ensure, **with the action-fact precision
> refinement deferred-with-trigger** (revisit if recorded-but-unconsumed action
> facts become real record noise); (11) the slice-A-now / slice-B-gated phasing
> adopted; (12) frame-id out of scope here, filed as a follow-on question
> against EP-0002. No disposition text below is rewritten — each Open Issue
> already carries its recommendation; this note records that all twelve are
> accepted as recommended. `accepted` settles the design, not the build:
> slice A proceeds as one sequential hot-zone wave and slice B's contract is
> settled here with its build gated on the first real generator consumer
> (EP-0016 optimistic temp-ids the named candidate) — both tracked as the
> separate slice-A action wave, not by this status flip.

> **Graduated `accepted → final` 2026-06-13 (Mike, in-session).** The decisions
> are settled and the normative homes named in
> the acceptance note above govern (where this EP and the spec differ, the spec
> governs). Slice A (the six sequential hot-zone beads — spec amendment, runtime
> rename + flat reshape, requires-parsing + declared-only delivery +
> `inject-cofx` removal, migration audit, trace/tooling, guide) is **merged**;
> the six-lens post-implementation review is complete. `final` asserts the
> **decisions are settled**, not that the whole build is complete: Slice B (the
> generation machinery) stays gated per disposition 11, and the review's open
> errata are tracked as child beads — both recorded in the
> [§Implementation errata](#implementation-errata) ledger below. One
> determinism question surfaced by the review is **held for
> operator ruling**; it is noted in the ledger and not resolved by this flip.

## Implementation errata

The EP decisions are final. Slice A shipped end-to-end and is recorded here
against the dispositions; Slice B's contract is settled but its build stays
gated; and the post-implementation review's open findings are tracked as child
beads, not as reopened decisions — the EP-0010 / EP-0013 precedent, where a
settled decision's later slice and its build-not-decision tails are recorded as
errata rather than as open questions. Verified 2026-06-13 against the merged
Slice A implementation, the six-lens review verdicts, and the open child beads
of the action epic.

### Shipped (Slice A)

- **The rename + flat reshape — SHIPPED.** `:rf.world/inputs` became the flat
  `:rf.cofx` envelope field (fact-name → value, no grouping sub-maps) on every
  dispatch and reply envelope; the runtime rename swept envelope/reply
  construction, event-context assembly, and the resource / mutation /
  work-ledger / machine / epoch reads. `:rf.world/inputs` supplied in dispatch
  opts is the hard error `:rf.error/world-inputs-renamed` naming `:rf.cofx` (no
  alias, no coexistence window).
- **Value-returning graded `reg-cofx` — SHIPPED.** `reg-cofx` registers a
  coeffect id with standard Spec 001 metadata and a value-returning supplier
  (`(fn [] value)` / `(fn [arg] value)`), carrying its grade
  (`:recordable?` / `:provided?`). The ctx→ctx handler shape is retired. The
  framework ships exactly one built-in registration — `:rf/time-ms`
  (recordable, provided, stamped at enqueue).
- **`:rf.cofx/requires` declared-only delivery — SHIPPED.** The declaration key
  is parsed on `reg-event` (post-[EP-0018](EP-0018-one-event-registration.md):
  the one event form; there is no db-only handler exempt from the declaration
  surface — the original EP-0017 db-handler exception is moot now that
  `reg-event-db` is removed); handlers receive `:db`, `:event`, plus exactly
  their declared facts, flat — a leaf on the token but undeclared is not
  staged. Ambient suppliers run at context assembly; provided facts must be on
  the token (absent is `:rf.error/missing-required-cofx`). In Slice A every
  requirable fact is provided or ambient — generators do not yet exist.
- **`inject-cofx` removal — SHIPPED.** `inject-cofx` is removed from the
  `re-frame.core` facade; calling it is the hard error
  `:rf.error/inject-cofx-removed` naming `:rf.cofx/requires`. The typo-vs-missing
  split shipped as the two distinct errors: `:rf.error/unregistered-cofx` (a
  required id with no registration) versus `:rf.error/missing-required-cofx` (a
  registered fact absent and un-ensurable).
- **Trace / tooling — SHIPPED.** Xray's COEFFECTS lens shows declared
  recordable leaves; Story and pair-MCP fixtures carry the `:rf.cofx` key
  rename and the declared-diet display. (The `:rf.cofx/generated` trace op is
  Slice B — it describes the generation step, which does not yet exist.)
- **Guide coeffects rewrite — SHIPPED.** `docs/core/concepts/effects-and-coeffects.md`
  was rewritten around the two grades + `requires` (the `inject-cofx` section
  deleted, not revised), with the supply-not-stub testing idiom carried into the
  testing how-tos (`how-to/test-an-event-handler.md`, `how-to/test-a-cascade.md`);
  the surrounding concept/how-to pages (including
  `how-to/keep-secrets-out-of-traces.md`) took their context/fixture updates.
- **Skills sweep + migration M-72 — SHIPPED.** The skills corpus was swept for
  the old `inject-cofx` / `:rf.world/inputs` idiom and the migration guide
  gained the M-72 entry covering the consumer-code rewrite (interceptor cofx
  entries → `:rf.cofx/requires` metadata; ctx→ctx cofx handlers → value-returning
  suppliers; `{:rf.world/keys [inputs]}` reads → declared flat leaves).

### Slice B (gate lifted 2026-06-14 — Mike un-deferred)

The slice-B gate (disposition 11 — "gated on the first real generator
consumer") was **lifted by Mike on 2026-06-14**: build slice B now. The
generator machinery (slice-B.7) is the first wave; mint-policy wiring
(slice-B.8) and machine consumer-attachment (slice-B.9) follow, both gated on
B.7 as before.

- **Recordable generator machinery — BUILT (slice-B.7).**
  Generation at processing-start (the generator runs under the cofx
  HandlerScope + platform gate when the mint policy permits; the produced
  value is written back into the in-flight `:rf.cofx` record so the epoch
  captures the post-generation token and replay — supplying that value —
  finds nothing to generate), the `:rf.cofx/generated` trace op (dev-gated,
  fact-name + supplier id + produced value, redacted by the shared cofx marks
  chokepoint), and the **`:schema`-when-declared** half of the per-leaf
  `:rf.error/cofx-value-invalid` validation: every recordable value reaching
  the fold — present on the token (supplied / replayed) OR freshly generated —
  is validated against its `reg-cofx` `:schema` before delivery, a PRODUCTION
  hard error on mismatch, routed through the shared `set-schema-validator!`
  seam (nil validator / absent schemas artefact = no-op; fails CLOSED on a
  throwing validator). The mint policy threads in via a `mint-policy` arg to
  `deliver-declared-cofx` (default `:live`, the router default); the binding
  points (`:test` preset / replay → `:strict`) are slice-B.8's job.
  **The structural-EDN-always half is now built (slice A for
  supplied values, a follow-up for generated values, and a production-hardening
  pass).** A non-EDN recordable value — a host object reaching the
  durable token whether supplied at the dispatch boundary or freshly generated,
  independent of any declared `:schema` — is `:rf.error/cofx-value-invalid`
  (`:rf.cofx/value-error :non-edn-recordable-value`), and the check is
  **ALWAYS-ON** (a hard error in dev AND production per Open Issue 9): folding a
  non-EDN value into the durable causal record is corrupt durable state
  regardless of build, the same `:dispatched-at` causal-token precedent the
  envelope map-shape / `:rf/time-ms` checks enforce. The cross-runtime
  "is-this-EDN / host-object" predicate is the data-only allow-list walker
  `re-frame.recordable` (accepts the EDN leaf + collection kinds, rejects host
  handles by construction). (Originally B.7 left this half deferred behind a
  dev gate with the rationale that no shipped generator minted a non-EDN value;
  the production-hardening closes the gate so the contract holds in production
  too, not only where the dev walk runs.)
- **Mint policies — DEFERRED (slice-B.8).** `:live` (router
  default), `:strict` (hard-wired for replay; the `:test` preset default), and
  `:explicit-live` (declared-nondeterminism escape), wired to their normative
  homes. Gated on slice-B.7.
- **Machine consumer-attachment — DEFERRED (slice-B.9).**
  Consumer-attachment entry-map `requires`, the derived per-(state × event-type)
  ensure-sets (incl. the `:always` closure), the inline-fn restriction, and the
  lint. Gated on slice-B.7.
- **Action-fact precision refinement — DEFERRED WITH TRIGGER (disposition
  10).** Ensuring *action* facts post-selection (guards stay pre-selection) to
  avoid recording action facts for transitions that don't fire — replay-sound
  but adds a third sampling moment. Revisit if recorded-but-unconsumed action
  facts become real record noise.

### Post-graduation review (open errata)

The six independent lens reviews (correctness, completeness, best-practice,
test-coverage, skills, guide) filed **25 findings**, tracked as child beads of
the action epic and being actioned in waves. They are build-and-doc errata,
not reopened decisions: residual old-shape vocabulary in shipped docs/skills,
JVM-only contract-suite ports, facade docstring drift, a bare/unqualified
routing cofx id, and similar tails.

One determinism question is **held for operator ruling** rather than actioned:

- **Boot/rehydrate localStorage reads registered AMBIENT but folded into
  durable app-db — HELD.** Three example-layer instances
  (todomvc, realworld, realworld-resources) register a localStorage read as an
  *ambient* coeffect and then fold its value straight into durable app-db on
  `:*/initialise`. The A.4 migration audit ruled this an **acceptable boot
  edge** (the host read happens once at boot, before any recorded epoch the
  user would replay). The correctness and best-practice lenses argue it is a
  **replay hole**: epoch-restore / replay refolds through the same ambient
  supplier, re-running the live host read rather than re-presenting the value
  actually folded — so a durable app-db produced purely by an ambient read at
  the write site can diverge on replay, the exact failure EP-0017 §1 and
  `spec/002-Frames.md` §The recordable-coeffect rule forbid. The
  recommended fix is slice-A-legal (register the boot read as a *provided*
  recordable fact and supply the host-read value on the boot dispatch token).
  Both readings are defensible; the call is the operator's. The framework
  delivery machinery (`cofx.cljc` / `router.cljc`) is sound — this is scoped to
  the example-layer grade choice.

## Abstract

EP-0010 made replay determinism literal: world facts that affect durable
frame-state arrive as recorded data on the causal token, with `:time-ms`
stamped on every dispatch envelope. This EP completes EP-0010's unfinished
authoring surface. The recorded map is renamed from `:rf.world/inputs` to
`:rf.cofx` and flattened to one fact per owner-qualified key; every coeffect —
recordable or ambient — is registered through one value-returning `reg-cofx`
and consumed through one declaration key, `:rf.cofx/requires`; `inject-cofx`
is removed; nothing is delivered to a handler implicitly, including the time.
The result is a single mental model for the handler author — *declare what you
consume; the registry knows the provenance* — with the replay contract
enforced rather than hoped for: a record missing a declared fact fails loudly
instead of silently re-reading the host.

One sentence: *`:rf.cofx/requires` declares a handler's coeffect dependencies;
`:rf.cofx` carries the exact recordable values used by this causal run; apps
register the suppliers; the framework records, replays, and enforces.*

## Motivation

### The data-oriented frame

In a data-oriented architecture, a coeffect is not something that *happens to*
a handler — it is a **fact the causal run consumed**, and facts are data:
named, recorded, validated, queryable. The two coeffect grades restate as:
recordable coeffects are *facts* (data on the token, re-presented by replay);
ambient coeffects are *reads* (mechanism that re-runs, correct exactly where
nothing durable depends on the answer). The whole discipline in one sentence:
**durable state folds facts, never reads.** Mechanism does not disappear — the
world must be sampled somewhere — but it shrinks to the boundary and runs
*once*, producing a fact that lives in the record forever after. This is the
same shape the architecture already gives effects (interpreter at the edge,
description as data) and continuations (data, not closures); coeffects were
the last input-side surface still mechanism-shaped in the middle.

### Four demonstrated gaps

EP-0010 (final) fixed the *recording* side. The 2026-06-12 design review
demonstrated four gaps on the *authoring* side:

1. **The vocabulary fractured.** "World inputs" names a new concept for what
   is, by the spec's own account, a *grade of coeffect* — the recordable
   grade. Every explanation routes back through the word "coeffect"; the
   operator's first question on meeting the surface was "is this really just
   coeffects?". When the explainer keeps reaching for a word, the API should
   use it.

2. **The authoring surface is asymmetric.** To the handler author, "the
   recorded time" and "a localStorage read" are the same kind of thing —
   facts from outside the event. Today one arrives via a namespaced nested
   map (`{:rf.world/keys [inputs]}`, then `(:time-ms inputs)`) and the other
   flat via `inject-cofx`. The provenance taxonomy — which exists for the
   runtime's benefit — leaks into the author's fingers.

3. **The supply gap violates events-are-facts.** Non-time recordable facts
   are caller-supplied today: the *dispatch site* must know what the
   handler's implementation needs, coupling call sites to handler internals.
   An event is a declaration of what happened, not an instruction packet
   pre-loaded with the handler's working materials. The principled workaround
   (mint-as-effect, an extra event hop per random value) is ceremony nobody
   pays; the realistic failure mode is an ambient `rand` call that silently
   breaks replay.

4. **The record has no integrity check.** Nothing detects a replay record
   missing a value the handler consumed; replay silently falls back to host
   reads — the exact failure the recording discipline exists to kill.

A fifth observation makes this the cheap moment: `:rf.world/inputs` is a
one-occupant mansion. Verified against the implementation 2026-06-12: the
router stamping `:time-ms` is the only shipped producer; the reserved
`:uuid` / `:random` / `:monotonic-ms` / `:storage` / `:browser/*` seats have
no suppliers, while ~39 implementation files *consume* the stamp. The
optional vocabulary can still be reshaped freely — that window closes the day
the first real producer ships.

## Goals / Non-Goals

### Goals

- Preserve EP-0010's replay contract exactly; change vocabulary and
  authoring, never recording semantics.
- One canonical envelope field for recordable causal coeffects: `:rf.cofx`.
- One registrar (`reg-cofx`, graded) and one declaration surface
  (`:rf.cofx/requires`) covering **both** coeffect grades.
- Every coeffect id registered — generator-backed or provided — so typos,
  ownership, docs, and schemas all have one home.
- Nothing delivered implicitly: handlers receive `:db`, `:event` (the fold's
  own arguments) plus exactly their declared facts, flat.
- Strict modes that make an incomplete record a loud failure and make
  nondeterministic tests opt-in rather than default.
- Registry-visible declarations (`handler-meta`) so tools can answer "which
  handlers consume which facts" exhaustively.

### Non-Goals

- **No framework generator vocabulary.** re-frame2 ships no standard
  random/uuid/choice generators — there are too many requirements out there
  (distributions, ULIDs, slugs, weighted choices, domain ids). Apps register
  their own; the framework records, replays, validates, and enforces. Same
  division of labor as effects: mechanism from the framework, semantics from
  the app.
- The ambient grade is not deprecated — non-durable reads remain legal,
  unrecorded, and re-run on replay. What is removed is their *separate
  authoring idiom*.
- Diagnostic / host-transient reads are not required to be replayable.
- No general capture tier for arbitrary ambient values (Temporal's
  `SideEffect`); `:rf.cofx/requires` is where it would later attach.
- No crypto-grade randomness, tokens, nonces, or key material in recordable
  coeffects — recording a secret makes it durable, not safe (see
  [§Security Considerations](#security-considerations)).
- No framework RNG seed algorithm: suppliers record produced *values*, never
  seeds.
- No optimistic-mutation rollback or temp-id workflow — this EP supplies the
  primitive those features will consume (see [§Phasing](#phasing)).
- Frame-target semantics are untouched: `:rf.frame/id` remains the fold's
  *address* (EP-0002's surface), not a declarable fact. De-magicking the
  implicit `(:frame m)` read is a possible follow-on question against
  EP-0002, deliberately outside this EP.

## Relationships

- **EP-0002 / Spec 002** — dispatch envelopes and the carried-frame
  invariant; this EP extends the same causal token and leaves frame-target
  resolution untouched.
- **EP-0007** — one name per fact: drives the no-alias rename, the
  one-home-per-layer delivery rule, and the `:dispatched-at`-style retirement
  errors.
- **EP-0010 (final)** — the recording rule itself, preserved verbatim. This
  EP activates the recordable `:uuid`/`:random` slice that EP-0010
  disposition 2 deferred (then gated behind an optimistic-mutations EP),
  re-expressed as app-registered suppliers; the old gate lapses in favor of
  this EP's slice-B gate.
- **EP-0011** — reply envelopes carry `:rf.cofx` in the same canonical slot;
  completion events stamp their own values.
- **EP-0012** — fact names participate in canonical-form identity like any
  qualified keywords; no new identity rules are introduced.
- **EP-0013 / EP-0014** — registry-visible declarations: event handlers gain
  the declared-inputs reflection EP-0014 gave derivations.
- **EP-0015** — projection/redaction: `:rf/time-ms` is always safe to
  surface; every other `:rf.cofx` leaf follows the same projection and
  sensitivity rules as event payloads.
- **EP-0016** — exposed the likely first generator consumer (optimistic
  mutation temp-ids); slice B's implementation gate points at it.
- **[EP-0018](EP-0018-one-event-registration.md) (final, successor)** —
  collapsed `reg-event-db` / `reg-event-fx` into one `reg-event`, which **removes
  the db-handler-coeffect exception this EP originally specified**: with no
  second-class `reg-event-db` form, `:rf.cofx/requires` lives uniformly on
  `reg-event` with no exception to carve out (it sequenced behind EP-0017 slice A
  precisely so the coeffect-declaration surface existed first). EP-0018's text
  edit superseded the relevant paragraphs here; the recordable-coeffect contract
  is otherwise unchanged.

## Specification

### 1. The two grades

Every coeffect id is **registered** (see §2) and carries a grade:

- **Ambient** (the default) — its supplier runs at context assembly, the
  value is delivered to declaring handlers and **never recorded**; replay
  re-runs the supplier. Permitted only where no durable write depends on the
  value (display preferences, diagnostics, host-transient measurements).
- **Recordable** (`:recordable? true`) — the fact is **ensured onto the
  causal token** before the fold consumes it, recorded with the token, and
  re-presented verbatim by replay. Required for any fact that can affect
  durable frame-state.

The normative rule (unchanged from EP-0010, restated in the new vocabulary):
a transition that performs a durable write MUST consume world facts from the
token's recordable coeffects (or the event payload), never from an ambient
read.

### 2. Registration: `reg-cofx`, value-returning, graded

`reg-cofx` registers a coeffect id with standard Spec 001 metadata and a
**value-returning supplier** — `(fn [] value)` or `(fn [arg] value)` for
call-site-parameterized ids. The ctx→ctx handler shape is retired with
`inject-cofx`.

```clojure
;; ambient (default grade) — a display preference; never feeds durable state
(rf/reg-cofx :ui/local-theme
  {:doc "Ambient localStorage read for the display theme."}
  (fn [storage-key]
    (some-> (.-localStorage js/globalThis) (.getItem storage-key))))

;; recordable, generator-backed — an app-owned replayable supplier
(rf/reg-cofx :counter/delta
  {:recordable? true
   :doc    "Replayable d6 roll."
   :schema [:int {:min 1 :max 6}]}
  (fn [] (inc (rand-int 6))))

;; recordable, PROVIDED — stamped by a subsystem/boundary; no generator
(rf/reg-cofx :rf.route/location
  {:recordable? true
   :provided?   true
   :doc "The location fact the routing subsystem stamps on its dispatches."
   :schema :rf.route/location-schema})
```

Registration contract:

- A **recordable** supplier's value MUST be EDN (structurally checked — it is
  going into the record). Host objects are never recordable values.
- `:provided? true` registers a recordable fact with **no generator**: the
  value is stamped onto the token by its owner (framework, subsystem, or
  dispatch boundary). Provided registrations exist so boundary facts get
  docs, `:schema`, and ownership — and so a typo'd requirement is
  distinguishable from a missing value.
- The framework ships exactly **one** built-in registration:
  **`:rf/time-ms`** — recordable, provided, stamped at enqueue on every
  dispatch and reply envelope (EP-0010's stamping rules unchanged: caller
  -supplied preserved, filled only when absent, cascade children and timer
  fires stamped fresh). It is the canonical durable wall-clock fact; the
  framework's own durable writers (resource freshness, work-ledger rows,
  mutation instances, epoch records) read it from the envelope.
- The optional `:schema` validates supplied and replayed values (§5).
- Fact names are **owner-qualified**: `:rf/*` framework, subsystem roots
  (`:rf.route/*`, …) for subsystem facts, app namespaces for app facts.
  Application ids MUST NOT use `rf.`-prefixed namespaces.

### 3. The envelope field: `:rf.cofx`

`:rf.cofx` is an EDN map on every dispatch and reply envelope — **flat**,
fact-name → value, no grouping sub-maps:

```clojure
{:event   [:counter/inc]
 :rf.cofx {:rf/time-ms    1781078400123    ;; framework, provided (enqueue stamp)
           :counter/delta 4}}              ;; app, generated at processing-start
```

- It is serializable under the same projection, elision, and privacy rules as
  other replayable event data (EP-0015 applies per leaf; `:rf/time-ms` is
  always safe to surface).
- It is stamped **unconditionally in production** — recordable coeffects are
  durable causal data, not diagnostics that elide under `goog.DEBUG=false`.
- The dispatch-opts key is `:rf.cofx` (`(rf/dispatch [:e] {:rf.cofx {...}})`);
  supplied values are preserved verbatim and never overwritten (§5).
- `:rf.world/inputs` is retired with **no alias and no coexistence window**
  (the `:dispatched-at` pattern, EP-0007 rule 2): supplying it is a hard
  error (`:rf.error/world-inputs-renamed`) naming `:rf.cofx`.

**Two sampling moments are normative.** `:rf.cofx` is one causal record, not
one sampling instant: `:rf/time-ms` is sampled at *enqueue* (queue latency is
real causal time), generated facts at *processing-start* (the declaration is
only knowable once the handler is resolved — late registration is legal).
Both precede the fold; a generated value postdating its token's `:rf/time-ms`
is correct behavior, not a bug.

### 4. Declaration: `:rf.cofx/requires`

`:rf.cofx/requires` is a standard registration-metadata key (Spec 001 middle
slot) on `reg-event`, and on machine named-callback
entries (§7). Its value is a vector of registered coeffect ids; a
parameterized id appears as `[id arg]` (mirroring the binary supplier arity).
(Post-[EP-0018](EP-0018-one-event-registration.md) `reg-event` is the one event
form — every handler can declare coeffects uniformly.)

```clojure
(rf/reg-event :counter/inc
  {:doc "Increment by a replayable random delta."
   :rf.cofx/requires [:rf/time-ms :counter/delta]}
  (fn [{:keys [db counter/delta rf/time-ms]} _event]
    {:db (-> db
             (update :count + delta)
             (assoc :last-updated-at time-ms))}))
```

A requirement means **ensure + deliver**:

- **Generator-backed recordable id** — if the fact is absent from the
  token's `:rf.cofx`, the generator runs at processing-start and the result
  is written into the envelope's `:rf.cofx` *before the fold consumes it*;
  the epoch record captures the post-generation envelope, so replay
  re-presents the value and the generation step finds nothing to do.
- **Provided recordable id** — the value must already be on the token;
  absent is `:rf.error/missing-required-cofx` in **every** mode.
  `:rf/time-ms` always succeeds (the stamp guarantees it).
- **Ambient id** — the supplier runs at context assembly and the value is
  delivered; nothing is recorded; replay re-runs it.
- A required id with **no registration at all** is
  `:rf.error/unregistered-cofx` — at registration where statically
  checkable, else at first processing. Typos die before dispatch semantics
  apply.
- There is no db-only handler form to except: post-[EP-0018](EP-0018-one-event-registration.md)
  `reg-event` is the one event form and every handler can declare
  `:rf.cofx/requires` uniformly. (The original EP-0017 rule made
  `:rf.cofx/requires` on `reg-event-db` a registration-time error; EP-0018
  removed `reg-event-db`, closing that hole structurally.)
- `[id arg]` delivers under the bare `id`; declaring the same id twice (any
  args) in one consumer scope is `:rf.error/cofx-name-collision`.

### 5. Satisfaction algorithm

When the runtime processes an event:

1. Resolve the frame and the event registration (and, for machines, the
   derived ensure-set for this state × event type — §7).
2. Ensure the envelope has `:rf.cofx` with `:rf/time-ms` (stamped at enqueue;
   EP-0010 unchanged).
3. For each declared **recordable** id, in declaration order:
   - present on the token → **deliver** the value. Per-leaf validation
     (structural EDN + `:schema`) is **Slice B** — see the deferral note
     below. Failure of that future check is `:rf.error/cofx-value-invalid` —
     a hard error in dev **and production** (causal-token contract
     validation, the `:dispatched-at` precedent: folding an out-of-contract
     value into the ledger is corrupt durable state).

     > **Slice-A reality — per-leaf validation is Slice B.** As shipped,
     > `deliver-declared-cofx` (`implementation/core/src/re_frame/cofx.cljc`)
     > delivers a token-present recordable leaf without a per-leaf structural
     > EDN check, and the boundary guard `diag/validate-cofx!`
     > (`router/diagnostics.cljc`) validates only the envelope **map shape**
     > plus `:rf/time-ms` int-ness — it does not structurally EDN-check each
     > recordable leaf. The entire per-leaf `:rf.error/cofx-value-invalid`
     > path (structural-EDN-always **and** `:schema`-when-declared) is
     > **Slice B**, gated with the generator machinery (slice-B.7) — the only
     > slice-B step that produces un-boundary-checked recordable values. In
     > Slice A every requirable fact is provided or ambient, supplied
     > recordable maps pass through the boundary shape gate, and no generator
     > mints an un-checked value, so there is nothing for the per-leaf check
     > to catch yet. (The `:schema` half was already listed as deferred under
     > *Deferred (Slice B) → Recordable generator machinery*; this note adds
     > the structural-EDN half so the whole `:rf.error/cofx-value-invalid`
     > path reads as one slice-B unit.)
   - absent, generator-backed → consult the mint policy (§6): `:live` /
     `:explicit-live` run the generator and write the result into the
     envelope's `:rf.cofx`; `:strict` fails with
     `:rf.error/missing-required-cofx`.
   - absent, provided → `:rf.error/missing-required-cofx`, every mode.
4. Assemble the handler's coeffects map: `:db`, `:event` (and the framework
   context keys Spec 002 already stages), plus **exactly the declared
   leaves** — recordable values from the token, ambient values from running
   their suppliers now, each flat under its own id.
5. Run the interceptor chain and handler.

**Delivery is flat and declared-only.** A leaf on the token but undeclared by
this handler is **not staged as a flat key** (no silent green-in-test/nil-in-prod
coupling; `handler-meta` becomes the complete consumption record; and the
supplied-vs-injected collision class of earlier designs becomes
unexpressible). There is no nested map in the *flat declared spread* — no
`:cofx` key, no `:rf.world/inputs` successor, no duplicate of `:rf/time-ms`. The
envelope's `:rf.cofx` map carries the canonical complete record; the declared
leaves carry the flat spread; one home per layer. The `:rf.cofx` record itself
is **always** staged into the coeffects as a framework context key — "declared-only"
governs the flat spread, **not** the staged record, which is reachable **exactly
how `:event` is reachable** (and is a framework context key, *not* a registered
coeffect supplier — the `:cofx/envelope-preserved` conformance fixture pins this).
Generic code that wants the whole record (transition helpers, interceptors, the
framework-internal `context -> context` primitive) reads the envelope's map
through the context.

> **Reaching into the record is discouraged for app code.** Ordinary application
> handlers SHOULD consume facts through their flat declared leaves
> (`(:rf/time-ms coeffects)`), not by reaching into the whole record
> (`(:rf/time-ms (:rf.cofx coeffects))`). The reach is *allowed* — `:rf.cofx` is
> a plain framework context key, and the values it carries are **replay-safe by
> construction** (replay re-presents the same token with the same values, so
> folding one into durable state is deterministic). But it is **discouraged for
> app code** because it **bypasses declaration hygiene**: a fact consumed off the
> record never appears in the handler's `:rf.cofx/requires`, so `handler-meta`
> and tooling cannot see the dependency. This is **developer discipline, not a
> runtime-enforced invariant** — the framework leaves the safer recorded-token
> read open and lints only the genuinely dangerous *ambient* host read (§1, the
> durable-write rule). Reading the whole record is for generic / framework code
> that legitimately needs the complete causal record.

**Coeffects are context assembly, not chain members.** Operationally,
satisfaction behaves like an implicit interceptor at the head of the chain;
normatively it is the *construction of the chain's input*: envelope
finalization → context assembly → `:before` pass → handler → `:after` pass.
Consequences: there is no cofx ordering question (v1's wart — an early
interceptor blind to a later injection — cannot be expressed); every
interceptor observes the complete input; the chain stays homogeneous.
Interceptors that *modify* coeffects remain legal as ordinary transformations
of an assembled context.

**Supplied values win.** Dispatch opts, replay fixtures, SSR hydration, and
host integrations supply exact values; the runtime fills only what is missing
and never overwrites. The registration's `:schema` is thereby a *contract*,
not merely a generation instruction — it is the type of the replay hole.

### 6. Mint policies

Three modes govern **recordable generation only** (ambient suppliers run at
context assembly in every mode — they are reads, not record entries):

| Mode | Behavior | Normative binding |
|---|---|---|
| `:live` | generate declared-absent recordable values | the router's default |
| `:strict` | required-but-absent is `:rf.error/missing-required-cofx`; no generator runs, no host reads | **hard-wired for replay** (Tool-Pair surface); the **default of the `:test` preset** (Spec 002 preset table) |
| `:explicit-live` | generates, but the test has *declared* it accepts nondeterminism | opt-in escape hatch in tests |

Strict-by-default tests are core, not polish: a determinism feature whose
path of least resistance is a fresh random per test run would degrade the
test culture it exists to serve. Replay is unconditionally strict — an
incomplete record MUST fail loudly rather than silently re-read the host.

### 7. Machines

Machine callbacks already consume the causal token (the threading key
`:rf/world-inputs` renames to `:rf/cofx`; machine ctx surfaces the map under
`:rf.cofx`). This EP adds declaration via **consumer attachment**:
requirements live on the machine's *named* guard/action entries, extending
the entry-map shape Spec 005's source-coords work established. A bare fn
remains the normal spelling and has the empty diet.

```clojure
:guards
{;; normal case — no facts consumed → bare fn, no nesting
 :retries-remaining?
 (fn [{:keys [data]}] (< (:attempts data) 3))

 ;; facts consumed → map form; the diet sits beside the destructure
 :within-retry-window?
 {:rf.cofx/requires [:rf/time-ms]
  :fn (fn [{:keys [data rf/time-ms]}]
        (< (- time-ms (:first-attempt-at data)) 60000))}}

:actions
{:schedule-retry
 {:rf.cofx/requires [:payment/retry-jitter-ms]
  :fn (fn [{:keys [data payment/retry-jitter-ms]}]
        {:data {:next-retry-in (+ 1000 retry-jitter-ms)}
         :fx   [[:rf.machine/notify-retry-scheduled
                 {:in-ms (+ 1000 retry-jitter-ms)}]]})}}
```

- At registration, `make-machine-handler` **derives** the per-(state ×
  event-type) ensure-set: the static union of `requires` across every
  guard/action any candidate transition for that event type can touch,
  including the `:always` cascade reachable from candidate targets.
- At dispatch, the derived set is ensured **before transition selection**
  runs — guards execute during selection, inside the fold step, so
  per-fired-transition generation is structurally impossible for
  guard-consumed facts, and a mid-selection host read would put
  nondeterminism in the fold's most sensitive spot (replay selecting a
  *different transition* — a wrong path through the statechart, compounding
  in the recorded snapshot history).
- **Inline callbacks cannot declare requirements** — fact-consuming
  guards/actions must be named entries (the form Spec 005 already prefers
  for visualisers and AI legibility). A bare fn destructuring beyond
  `{:data :event :state :meta}` is lintable.
- Timer (`:after`) fires, reply deliveries, and other synthetic machine
  events are dispatch envelopes like any other: each stamps or supplies its
  own `:rf.cofx`. The shipped `:after` epoch mechanism needs no coeffect
  machinery — its staleness check was already facts-against-facts — and is
  prior art for this EP's philosophy: cancellation-as-mutation replaced by
  staleness-as-pure-function over recorded data.
- **Flows do not generate** — a flow is derived state over declared inputs;
  generation would blur pure derivation and event-caused transition. Flows
  continue reading `:rf/time-ms` from the threaded token as framework
  consumers of the envelope.
- Resource/mutation-specific declarations wait for a concrete demand trigger
  (EP-0016 optimistic temp-ids); they read the triggering token's `:rf.cofx`
  meanwhile.

**The minting ladder** (normative guidance): "my handler/machine needs X from
the world" resolves in preference order — (1) **derive from recorded state**
where possible (Spec 005's `:rf/spawn-counter` is the exemplar: deterministic
spawn identity from a snapshot-resident counter, no new fact recorded);
(2) **ride the event payload** where the dispatch site owns the fact's
meaning (an optimistic-create id the view must render now); (3) **recorded
coeffect** only for genuinely fold-internal facts. Recorded coeffects are the
last rung, not the default.

### 8. Collisions and errors

Registration-time collisions (hard error, `:rf.error/cofx-name-collision`):
a coeffect id colliding with the fold's argument keys (`:db`, `:event`), or
the same id declared twice (any args) in one `:rf.cofx/requires` scope. A
coeffect id colliding with **another registered id** across namespaces is
not a `reg-cofx` call-time guard — it is caught generically at EP-0023 image
assembly as `:rf.error/image-duplicate-id`, uniformly with every other
registered kind (same-namespace re-registration is an ordinary hot-reload
replacement). An application id under an `rf.`-prefixed namespace is a LINT
diagnostic (§9 / Spec 009 §9), not a registration guard — the registration
site cannot tell an app id from a framework/subsystem `rf.*` fact. No
per-handler or dispatch-time collision rules are needed: the
requires-vs-inject double-delivery is unexpressible (`inject-cofx` is
removed) and an undeclared supplied leaf is never staged (declared-only
delivery).

| Error | When |
|---|---|
| `:rf.error/missing-required-cofx` | a required fact is absent and cannot be ensured — strict mode (no generator runs), or any mode for a provided fact whose value wasn't stamped |
| `:rf.error/unregistered-cofx` | a required id has no registration (the typo case) — registration-time where statically checkable, else first processing |
| `:rf.error/cofx-request-invalid` | malformed `:rf.cofx/requires` at registration |
| `:rf.error/cofx-registration-invalid` | malformed `reg-cofx` metadata grade at registration — `:provided?` without `:recordable?`, a missing supplier on a non-provided fact, or a provided fact carrying a (silently ignored) supplier |
| `:rf.error/cofx-value-invalid` | a supplied/replayed value fails the structural EDN check or the registration's `:schema` — **fires in production** |
| `:rf.error/cofx-name-collision` | registration-time name collision (above) — the fold's argument keys (`:db` / `:event`), or the same id declared twice in one `:rf.cofx/requires` scope. A duplicate cofx id across namespaces is `:rf.error/image-duplicate-id` at EP-0023 image assembly, not here |
| `:rf.error/world-inputs-renamed` | `:rf.world/inputs` supplied in dispatch opts — hard error naming `:rf.cofx` |
| `:rf.error/inject-cofx-removed` | `inject-cofx` called — hard error naming `:rf.cofx/requires` |

### 9. Reflection, trace, and tooling

- `:rf.cofx/requires` and the cofx registrations surface in `handler-meta`
  exactly as authored (the registry MAY additionally expose an effective
  form). Tools can answer: which handlers consume which facts — both grades;
  which need strict fixtures; what each supplier produces (`:doc`,
  `:schema`).
- The generation step emits a dev-mode trace op **`:rf.cofx/generated`**
  (fact-name + supplier id), so traces are self-describing even though the
  record is flat (Spec 009 gains the op with slice B).
- Xray's COEFFECTS lens — which filters framework coeffects — **shows
  declared recordable leaves**: they are the handler's declared inputs, the
  most user-relevant facts on the token.
- A lint becomes possible and is recommended: a handler destructure key not
  covered by `:db`/`:event`/declared ids ("consuming without declaring"),
  and an *ambient*-grade id declared by a handler that writes durable state
  ("durable state folds facts, never reads", mechanically checkable).

### 10. Testing semantics

The testing story is *supply data, don't swap mechanisms*:

```clojure
;; 1. Pure handler test — no runtime, no mocks. The coeffects map is a
;;    literal; :rf.cofx/requires is the fixture checklist (derivable from
;;    handler-meta).
(deftest counter-inc-pure-test
  (let [{:keys [db]} (counter-inc {:db            {:count 5}
                                   :rf/time-ms    1781078400123
                                   :counter/delta 4}
                                  [:counter/inc])]
    (is (= 9 (:count db)))))

;; 2. Dispatch-level — supply recordable facts in the envelope; effects
;;    intercept as data via :fx-overrides. Under the :test preset's strict
;;    policy, forgetting :counter/delta is :rf.error/missing-required-cofx —
;;    never a silently different random.
(rf/dispatch-sync [:counter/inc]
                  {:rf.cofx {:rf/time-ms 1781078400123 :counter/delta 4}})

;; 3. Ambient stub — the seam is re-registration (visible, greppable), never
;;    a monkey-patch; legal only because ambient facts never feed durable
;;    state.
(rf/reg-cofx :ui/local-theme {:doc "Test stub."} (fn [_k] "dark"))
```

The boundary invariant behind the opts map: dispatch opts override the
*edges* — `:frame` (where), `:rf.cofx` (inputs), `:fx-overrides` (outputs) —
and never the *middle* (handler + interceptor chain), because the middle is
the step function and replay holds under recorded-inputs + same program.
Corollary for side-effecting interceptors: replay refolds through the step
function, so work performed directly in an interceptor body re-fires on
replay and escapes every seam; the sanctioned pattern is *contribute, don't
perform* (append to `:effects`/`:fx`, let the interpreter execute).
Diagnostic side effects may stay ambient in bodies by the same criterion as
ambient coeffects: re-execution on replay must be harmless.

### Phasing

One contract, two implementation slices:

| Slice | Contents | Trigger |
|---|---|---|
| **A** | rename + flat reshape; `:rf.cofx/requires` parsing + declared-only delivery; ambient grade at context assembly; provided registrations; `inject-cofx` removal; dispatch-opts key; schemas; trace/Xray lens updates; guide. In slice A every requirable fact is provided or ambient — generators do not exist yet; time, subsystem facts, and supplied values cover all consumers | immediately on acceptance |
| **B** | the generation machinery only: recordable generator runs at processing-start, `:schema` validation, mint policies, `:rf.cofx/generated` trace op, machine consumer-attachment derivation | gated on the first real consumer (likely EP-0016 optimistic temp-ids) |

The gate is the EP-0016 discipline applied to ourselves: a primitive with no
demonstrated site gets a settled contract and a named trigger, not a build.
The demand audit behind the gate: handler-side, the first generator consumer
is optimistic temp-ids; machine-side, demand is thinner still (recorded
tiebreaks / experiment-bucket assignment; timezone-fed schedule computation)
— retry jitter is mostly scheduling (the fire event's ledger position already
captures its durable consequence), and spawned-actor identity is already
solved deterministically by Spec 005's `:rf/spawn-counter`.

## Rationale

- **Why `:rf.cofx` (and not keep `:rf.world/inputs`)?** Because the concept
  *is* coeffects — the recordable grade of an idea re-frame has had a word
  for since v1. The old name made one grade look like a separate subsystem;
  the rename ends the vocabulary fracture. EP-0007 rules out keeping both.
- **Why flat (and not grouped `{:random {...} :uuid {...}}`)?** With
  app-registered suppliers, the groups lost their last role: provenance is
  the registration (`handler-meta`, `:doc`, `:schema`), not the value's
  nesting. Flat gives one fact / one name / one access path, removes the
  requires-shape heterogeneity, and eliminates the duplicate-`time-ms`
  problem of "deliver both" variants. The grouped seats had zero shipped
  producers, so the reshape is free exactly once — now.
- **Why remove `inject-cofx`?** With `requires` as the declaration surface,
  `inject-cofx` was the last mechanism-shaped delivery idiom — an
  interceptor in a positional vector running a ctx→ctx function at handler
  time — and keeping it would preserve the two-idiom asymmetry this EP
  exists to kill, plus v1's real ordering wart (an early interceptor blind
  to a later injection). One registrar, one declaration, one delivery; the
  registration's grade decides replay semantics.
- **Why `requires` (not `inject`, `needs`, or same-key `:rf.cofx` by
  position)?** Metadata states facts about the handler; "requires" names the
  contract (exist + record + fail-loud) and conjugates cleanly across the
  error family. `inject` describes mechanism and would span both grades with
  different replay semantics. Same-key-by-position is elegant but overloads
  one key with two value types — grep, fixtures, and registry dumps lose the
  role cue exactly where this system optimizes for being inspectably dumb.
- **Why no implicit time?** Implicit delivery of the most-consumed fact made
  the consumption record exempt its biggest consumer, and "a bit magic" was
  the operator's verdict. The stamp is envelope mechanics for the
  framework's own writers; handler delivery is a declaration like any other.
  With provided registrations, `:rf/time-ms` is not even a special case —
  it is the framework's own `{:recordable? true :provided? true}` entry.
- **Why declared-only delivery?** Deliver-all permits silent undeclared
  coupling (green where a fixture happened to supply a leaf, `nil` live);
  declared-only makes `requires` the complete consumption record — the same
  enforced-declared-inputs deal subscriptions already have — and dissolves
  the dispatch-time collision rules entirely.
- **Why consumer attachment for machines?** The declaration belongs with
  what can be checked against it. Event-type attachment leaves a silent-nil
  hole: a guard whose fact was never declared destructures `nil` and selects
  the wrong transition, with no diet of its own to validate against.
  Consumer attachment closes it, restores the transition table to pure
  structure, deduplicates shared consumers, and derives the ensure-sets
  statically. (XState offers no parity guidance — it has no replay
  contract; this surface is ours alone.)
- **Why record choices, not seeds?** A seed re-derives the value only if the
  PRNG stays bit-identical across versions and hosts; a recorded choice is
  implementation-independent.
- **Prior art.** re-frame v1 `inject-cofx` (the declaration instinct,
  without recording; and the reg-* division of labor — v1 ships almost no
  standard fx/cofx either). Temporal.io (`workflow.now()` reads recorded
  history; `SideEffect` is app code + framework recording — the same split).
  Ethereum's `block.timestamp` (consensus-recorded time for deterministic
  re-execution). Event sourcing (occurred-at in event metadata; ambient
  `DateTime.Now` in an aggregate as the canonical anti-pattern).
  Deterministic simulation / lockstep replays (tick time and RNG values as
  recorded inputs).

## Security Considerations

Recordable coeffects are written into every replay record **by design** —
durability and inspectability are the feature. For credentials they are the
threat model: **crypto-grade randomness, tokens, nonces, and key material
MUST NOT be minted or carried as recordable coeffects.** Recording a secret
does not make it safe; it makes it durable, copied into every recording,
fixture, and exported trace. Secrets are generated at the edge and handled by
guarded runtime mechanisms, off the ledger (EP-0010's exclusion, restated at
this surface). With app-owned suppliers this is a normative rule and review
discipline rather than a structural guarantee — the guide's secrets material
and the lint above are the enforcement surface, the same status the
`(random-uuid)`-in-payload idiom has today. `:rf.cofx` leaves follow EP-0015
projection/redaction per leaf; `:rf/time-ms` alone is classified always-safe.

## Backwards Compatibility

Pre-alpha: direct breaking change, full sweep, no aliases, no shims.

| Surface | Change |
|---|---|
| `spec/002-Frames.md` | §Causal world inputs → §Recordable coeffects: rename, flat shape, sampling-moments clause, satisfaction algorithm, context-assembly position |
| `spec/001-Registration.md` | `reg-cofx` value-returning contract + grades; `:rf.cofx/requires` joins the standard metadata-key table |
| `spec/Conventions.md` | owner-qualified fact naming; `:db`/`:event` defined as the fold's arguments; reserved `:rf.cofx` root; `:rf/time-ms` |
| `spec/Spec-Schemas.md` | `:rf.world/inputs` schema → `:rf.cofx` (flat); `:rf/dispatch-opts` / `:rf/dispatch-envelope` updated; `:rf.cofx/requires` schema |
| `spec/005-StateMachines.md` | threading-key rename (`:rf/world-inputs` → `:rf/cofx`); consumer-attachment entries; derived ensure-sets |
| `spec/009-Instrumentation.md` | `:rf.cofx/generated` op (slice B); COEFFECTS lens rule |
| `spec/016-Resources.md` + Managed-Effects | timestamp-read references; reply envelopes carry `:rf.cofx` |
| `re-frame.core` facade | `inject-cofx` removed (hard error naming the replacement); `reg-cofx` contract change |
| `implementation/` (~39 files) | mechanical rename + flat reshape (slice A); generation/validation/policies (slice B) |
| consumer code | `[(rf/inject-cofx :local-store "k")]` interceptor entries → `:rf.cofx/requires [[:local-store "k"]]` metadata; cofx handlers drop the ctx wrapper (`(fn [ctx k] (assoc-in ctx [:coeffects :k] v))` → `(fn [k] v)`); handler destructures gain declarations; `{:rf.world/keys [inputs]}` reads → declared flat leaves |
| `docs/core/` (topical tree) | `concepts/effects-and-coeffects.md` rewritten around the two grades + `requires` (the `inject-cofx` section deleted, not revised); concept-page context examples; the testing how-tos (`how-to/test-an-event-handler.md`, `how-to/test-a-cascade.md`) use strict fixtures in place of clock/RNG monkey-patching; "causal world inputs" survives as explanatory prose — "recordable coeffects" is the taught term |
| Xray / Story / pair-MCP | lens rule, declared-diet display, fixture key rename |
| EP-0010 | untouched as historical record; one supersession line in its errata ledger (the deferred recordable slice activates here; its optimistic-mutations gate lapses) |

Hot-zone note: spec/002, Spec-Schemas, Conventions, 001, 005, 009, 016 and
guide hot files — sequential dispatch, one worker per wave step.

## Bead Plan / Reference Implementation

Slice A (on acceptance, sequential through the hot zone):

1. Spec amendment: 002 + Spec-Schemas + 001 + Conventions + cross-spec refs.
2. Runtime rename + flat reshape: envelope/reply construction, event-context
   assembly, resource/mutation/work-ledger/machine/epoch reads, tests.
3. Requires parsing + declared-only delivery + ambient grade + provided
   registrations + `inject-cofx` removal + migration errors.
4. Migration audit: sweep every existing ctx→ctx cofx handler (docs, specs,
   examples, SSR, routing tests, skills, migration docs) and classify each
   into its new home — handler logic reading db/event, a provided envelope
   fact, or a value-returning cofx with explicit arg. Confirms the loss of
   ctx-dependence is intentional case-by-case before the facade change lands.
5. Trace/tooling: Xray projections + lens rule, Story / pair-MCP fixtures.
6. Guide: the topical tree — `concepts/effects-and-coeffects.md` and the
   testing how-tos. Guide-impact: the effects-and-coeffects coeffect model is
   the load-bearing rewrite; the testing how-tos gain the supply-not-stub idiom
   as their headline.

Slice B (gated on the first generator consumer):

7. Recordable generator machinery: generation at processing-start, `:schema`
   validation (prod hard error), `:rf.cofx/generated` trace op.
8. Mint policies wired to their normative homes: router default `:live`,
   Tool-Pair replay hard-wired `:strict`, `:test` preset default `:strict`,
   `:explicit-live` escape. Strict test wiring is core, not polish.
9. Machine consumer attachment: entry-map `requires`, derived ensure-sets
   (incl. `:always` closure), inline-fn restriction, lint.
10. Wave-end correctness/completeness review against this EP; the standard
    propagation tails (/skills, /examples, /tools, /docs/core).

## Open Issues

Each issue carries the author's recommendation; the design conversation of
2026-06-12/13 (recorded in `ai/findings/EP-0017/`) reached these positions
through eight drafting rounds and two adversarial cross-reviews.

1. **Envelope shape — flat vs grouped.** Flat: one name / one path;
   provenance lives in the registration; the grouped seats have no shipped
   producers to migrate. **Recommendation: flat.**
2. **Declaration key name.** `requires` names the contract and conjugates
   across the error family; `inject` is mechanism-voiced and would span two
   replay semantics; same-key-by-position trades inspectability for
   elegance. **Recommendation: `:rf.cofx/requires`.**
3. **Remove `inject-cofx` + value-returning `reg-cofx`.** The most
   consequential consumer-facing change in the EP; the migration audit
   (bead 4) verifies the ctx→ctx loss case-by-case. **Recommendation:
   remove, no alias, slice A.**
4. **No implicit time.** `:rf/time-ms` is the framework's provided
   registration; handler delivery only on declaration. **Recommendation:
   adopt.**
5. **Declared-only delivery.** **Recommendation: adopt** (with the
   `handler-meta` completeness and collision-dissolution consequences).
6. **No framework generators.** Apps own supplier semantics; the framework
   owns record/replay/enforce. **Recommendation: adopt.**
7. **Provided registrations.** Every coeffect id registered;
   `:provided? true` for generator-less boundary facts; typo vs missing
   value split into two errors. **Recommendation: adopt.**
8. **Mint policies and binding points.** `:live` router default; `:strict`
   hard-wired for replay and the `:test` preset default; `:explicit-live`
   declared-nondeterminism escape. **Recommendation: adopt.**
9. **Supplied-value validation severity.** Structural EDN always; `:schema`
   where declared; hard error in production as well as dev.
   **Recommendation: adopt.**
10. **Machine attachment.** Consumer attachment on named entries with
    derived ensure-sets; pre-selection ensure; inline fns cannot declare.
    A precision refinement — ensuring *action* facts post-selection (guards
    stay pre-selection) to avoid recording action facts for transitions
    that don't fire — is replay-sound but adds a third sampling moment;
    **recommendation: defer with trigger** (revisit if
    recorded-but-unconsumed action facts become real record noise).
11. **Phasing.** Slice A on acceptance; slice B gated on the first real
    generator consumer (EP-0016 optimistic temp-ids the named candidate).
    **Recommendation: adopt the gate.**
12. **Frame-id.** The implicit `(:frame m)` read fits a "declare it like a
    fact" reading, but frame-id is the fold's *address* (EP-0002's surface),
    not a world fact riding the token. **Recommendation: out of scope here;
    file as a follow-on question against EP-0002 if still appealing.**

## Recommendation

Accept, with the twelve dispositions above as recommended. On acceptance:
slice A proceeds as one sequential hot-zone wave; slice B's contract is
settled by this EP and its build waits for the first real generator consumer;
EP-0010 gains its supersession line; and the wave carries the standard tails
(correctness review, repeat pass, and the four propagation beads).

# EP-0025: Registration-Time Subsystem Data Classification

Status: proposal
Type: standards-track

> **REDESIGNED 2026-06-20 (rf2-my4l1h, adversarial design session).** The first
> draft of EP-0025 (recording Mike's 2026-06-20 `rf2-398kql` ruling) made the
> FRAME the sole owner of durable classification and classified durable
> subsystem state by DEEP ABSOLUTE frame-paths into runtime-db plumbing
> (e.g. `[:rf.runtime/machines :snapshots :checkout/payment :data :token]`).
> A Mike+Claude design discussion concluded that core mechanism is wrong, and
> this revision replaces it with **registration-time subsystem classification**:
> durable subsystem state is classified `:sensitive` / `:large` at the
> registration that DEFINES it (`reg-machine` / `reg-route` / `reg-resource` /
> `reg-mutation`), relative to that subsystem's own classification root; the
> frame keeps EP-0015's app-db classification unchanged and MAY tighten/add.
> The title changes accordingly (was "Frame-Declared Paths As The Sole Durable
> Data Classification Mechanism").
>
> What survives unchanged from the ruling: ONE owner + ONE classification route
> per durable fact; the EP-0005 `:data-schema` → marks redaction **bridge** is
> deleted (the machine schema-marks side-table machinery is a bug farm —
> `rf2-egvm4t` / `rf2-qpibk0`); `:data-schema` stays **validation-only**;
> classification is NOT buried as a Malli prop.
>
> This is a **proposal for Mike's decision** (do NOT graduate). The
> [Adversarial review](#adversarial-review) section records every probe the
> design session ran at the new direction, what survived, and what did not. On
> acceptance, the normative home is `spec/015-Data-Classification.md`, with
> reconciling edits in `spec/005-StateMachines.md`, `spec/012-Routing.md`,
> `spec/016-Resources.md`, `spec/010-Schemas.md`, `spec/Spec-Schemas.md`,
> `spec/Privacy.md`, `spec/Security.md`, and the dependent API, migration,
> examples, skills, and conformance text. After graduation, where this EP and
> the specs differ, the specs govern.

## Abstract

re-frame2 has one durable-state classification discipline: **each durable fact
is classified once, by its owner, where the owner is defined.**

- A frame's **app-db** state is classified on the frame (`reg-frame`
  `:sensitive` / `:large {:app-db …}`) — unchanged from EP-0015.
- A **subsystem's** durable runtime-db state is classified on the registration
  that defines the subsystem: `reg-machine` classifies machine `:data`,
  `reg-route` classifies route params/query, `reg-resource` classifies cache
  entry data/params, `reg-mutation` classifies mutation variables — each with
  paths **relative to that subsystem's own classification root**, expressed via
  first-class `:sensitive` / `:large` registration keys (NOT Malli `:data-schema`
  props, NOT deep absolute frame-paths).

The framework FOLDS each subsystem's declarations into the frame's effective
classification at egress, re-rooting them to the correct runtime-db location
across all live instances of the registered type — including instances with
runtime-generated keys (spawned actors, cache entries, mutation work-ids), which
absolute frame-paths structurally cannot express.

The important consequences:

- A Malli `:sensitive?` / `:large?` prop on any `:data-schema` / `:params-schema`
  no longer classifies durable state for egress. Schemas describe shape and
  validation. (Schema props still redact schema-**validation-failure** records —
  a different, legitimately schema-owned product.)
- The machine `:data-schema` → marks redaction **bridge** and its per-instance
  side-table are gone (already shipped — `rf2-398kql` / PR #4754).
- Resources stop being the lone subsystem that classifies via schema props; they
  move onto the same first-class `:sensitive` / `:large` registration keys as
  every other subsystem.

## Motivation

### The shipped state, and why it is wrong

`rf2-398kql` (PR #4754) made frame-declared `:sensitive` / `:large {:app-db …}`
paths the sole app-db / runtime-db classification route and deleted the machine
`:data-schema` → marks bridge. To keep machine `:data` redacted, the frame must
now declare the **absolute** runtime-db snapshot path:

```clojure
(rf/reg-frame :checkout
  {:sensitive
   {:app-db [[:rf.runtime/machines :snapshots :checkout/payment :data :token]]}})
```

The mechanism is implemented in `re-frame.marks/frame-snapshot-marks`: it reads
the frame's elision declarations, keeps the ones rooted at
`[:rf.runtime/machines :snapshots <actor-id> …]`, and strips that prefix so the
remainder indexes into the snapshot the trace slot carries. It works for a
singleton. It fails as a model for six reasons, established in the design
discussion:

1. **PLACE over NAME.** It classifies durable state by structural position into
   plumbing the frame does not own, violating re-frame2's own "name over place"
   discipline. Classification is a MEANING-fact; it should be name-anchored, not
   address-anchored.
2. **EXTRINSIC + fragile.** The path is a stringly-typed deep reference into a
   subsystem's internals. A refactor of the subsystem's internal shape SILENTLY
   breaks the path and the secret egresses. For a SECURITY property, fail-open
   on drift is the wrong default.
3. **Cannot express MULTI-INSTANCE / GENERATED-ID state.** Spawned actors
   (`<type>#<n>` ids), resource cache entries (opaque byte `key-id`, the
   human-readable `[scope resource-id params]` stored INSIDE the entry as
   `:resource/key` — `rf2-9e0tyq`), and mutation work-ledger rows (generated
   work-ids) have no static absolute path. The model STRUCTURALLY cannot
   classify them; the first draft punts ("use `:fixed-actor-id` or wait for a
   pattern-path proposal").
4. **Fail-OPEN on omission.** A reusable subsystem (a payment machine, a
   reset-password route) mounted in N frames must be RE-DECLARED on every frame;
   forget one and it leaks. Safe-by-default is lost.
5. **Re-rooting is ENUMERATED, not principled.** The first draft lists the
   machine egress shapes the frame declaration must be re-rooted onto
   (`:before` / `:after` / `:snapshot` / `:data` / `:input :data` /
   `:cascade :data-delta` + SSR). A 7th shape added later is silently uncovered.
6. **`:app-db` bucket OVERLOAD.** Runtime-db paths are stuffed under the
   `:app-db` declaration key; the model does not mirror the frame's two-partition
   (app-db / runtime-db) structure.

### The generalization (the decisive finding)

Machines are the tip of the iceberg. The runtime-db partition is a **federation
of subsystems**, several of which hold durable, dynamically-keyed,
secret-bearing state, each owned by a different definition artefact:

| runtime-db slot | durable secret-bearing state | owner | instance keying |
|---|---|---|---|
| `:rf.runtime/machines` | snapshot `:data` (machine secrets) | `reg-machine` | singleton id OR generated `<type>#<n>` |
| `:rf.runtime/routing` | `:current` route params / query (**TOKENS/CODES IN URLS** — `/reset?token=`, `/verify/:code`) | `reg-route` | **static** path `[:rf.runtime/routing :current]` |
| `:rf.runtime/resources` | `:entries` cached data / params (fetched PII, auth) | `reg-resource` | opaque byte `key-id` (generated) |
| `:rf.runtime/mutations` / work-ledger | in-flight variables (e.g. change-password's new password) | `reg-mutation` | generated work-id |

In EVERY case the knowledge "this slot is a secret" lives at the subsystem
REGISTRATION, not at the frame. Under the absolute-frame-path model EVERY
subsystem needs brittle deep paths into plumbing, and three of the four have
runtime-generated keys — the spawned-actor "can't express it" failure repeated
at every boundary.

### The self-contradiction that proves the point

The first draft strips MACHINES of registration-owned classification (forcing
frame paths) but explicitly KEEPS RESOURCES classifying their own durable cache
via `:data-schema` / `:params-schema` props (first-draft §"Schema props that
remain"; implemented in `re-frame.resources.classification`). Both are durable
`:rf.runtime/*` state. The draft treats two identical situations oppositely.

Worse, the resources implementation already does **exactly** what the new
direction proposes — minus the spelling. It classifies the resource at its
SPEC, and resolves type→all-instances at egress by looking the spec up from the
resource-id stored in each entry's scoped key (`re-frame.resources.classification/`
`data-schema-marks`, `project-data`, `whole-entry-disposition-for`). It even
handles generated keys (opaque byte `key-id`) and frame-override-as-defence
(`scope-derived-sensitive?` folds frame app-db sensitivity into the entry
disposition). The only thing wrong is the **route**: it uses Malli schema props,
which `rf2-398kql` ruled must not be a classification route.

So the new direction is not speculative. It is the resources model, generalized
to every subsystem, with the classification spelling moved off schema props
onto first-class registration keys.

## Goals / Non-Goals

Goals:

- Define **registration-time subsystem classification** as the single route for
  durable runtime-db state, one owner per fact.
- Define the **fold** (registration declarations → effective frame
  classification) as a re-rooting RULE — not an enumeration of egress shapes —
  so a new subsystem or a 7th machine egress shape is covered by construction.
- Resolve type-level declarations to ALL live instances of the type, including
  generated-id instances.
- Define the **frame override** as a specificity-ruled, **tighten-only**
  exception (it may ADD or RAISE classification; it may NOT declassify a
  subsystem-declared secret).
- Move resources off `:data-schema` / `:params-schema` schema props onto the
  same first-class `:sensitive` / `:large` registration keys.
- Keep `:data-schema` validation and schema-validation-failure redaction.
- Make the migration and conformance criteria explicit.

Non-goals:

- No change to EP-0015 app-db frame classification or to the registration
  metadata `:sensitive` / `:large` for **transient** payloads (event / fx / cofx
  / sub / flow); they stand.
- No new optics library; declarations consume EP-0012's `:rf/path` vocabulary.
- No change to real values inside the app while events are processed; projection
  still happens only at framework-mediated egress boundaries.
- No compatibility shim (pre-alpha).
- No wildcard/glob path grammar: type-level resolution is "all instances of THIS
  registered type," not a path-pattern language.

## Relationships

- **Completes:** [EP-0015](EP-0015-frame-owned-egress-policy.md). EP-0015 named
  four owners (frame / machine / resource+mutation / registration). The frame
  arm and the transient-registration arm stand. This EP reconciles the
  durable-subsystem arms onto ONE mechanism (first-class registration
  `:sensitive` / `:large`), which EP-0015 §6 explicitly left open ("either keep
  coarse props … or add explicit `:sensitive` / `:large` path maps rooted at
  `:data`, `:params`, `:scope`").
- **Partially supersedes:** [EP-0005](EP-0005-machine-data-schema.md). The
  `:data-schema` rename, validation, and XState parity stand. The schema → marks
  redaction bridge is reversed (already shipped). This EP additionally moves the
  machine `:sensitive` / `:large` declaration from "frame absolute path" (the
  shipped `rf2-398kql` state) to "first-class `reg-machine` key, snapshot-root
  relative."
- **Reverses two first-draft rejections.** The first draft's "Add `:sensitive` /
  `:large` to `reg-machine`" rejection and EP-0015 §Open-Issues disposition 12
  ("NO supersession — schema-first machine surface stands") are BOTH overturned
  by the new direction. The grounds those rulings rested on are addressed in
  [Adversarial review](#adversarial-review) probe 1 and probe 7.
- **Applies:** [EP-0007](EP-0007-one-name-per-fact.md). Per-kind single-home is
  one route per fact; the frame override is a specificity-ruled exception, not a
  competing route.
- **Uses:** [EP-0012](EP-0012-path-optics-and-canonical-forms.md). Subsystem
  declarations are `:rf/path` vectors relative to a per-subsystem root.
- **Governed by:** [EP-0009](EP-0009-the-ep-process.md).
- **Graduates into:** `spec/015-Data-Classification.md`,
  `spec/005-StateMachines.md`, `spec/012-Routing.md`, `spec/016-Resources.md`,
  `spec/010-Schemas.md`, `spec/Spec-Schemas.md`, `spec/Privacy.md`,
  `spec/Security.md`, and dependent `docs/api`, migration, examples, skills, and
  conformance text.

## Specification

### 1. The classification rule

> Each durable fact is classified once, by its owner, where the owner is
> defined.
>
> - **Frame app-db** state → the frame (`reg-frame` `:sensitive` / `:large
>   {:app-db …}`). Unchanged (EP-0015).
> - **Subsystem runtime-db** state → the subsystem registration that defines it
>   (`reg-machine` / `reg-route` / `reg-resource` / `reg-mutation`), with paths
>   relative to that subsystem's classification root.
> - **Transient payloads** (event / fx / cofx / sub / flow args+outputs) →
>   registration metadata. Unchanged (EP-0015 §4).

Each kind of durable state has exactly ONE home. App-db is never classified by a
subsystem registration; subsystem runtime-db state is never classified by a deep
frame app-db path. There is no second public route to the same fact.

### 2. First-class subsystem classification keys

Each durable-subsystem registrar gains first-class `:sensitive` / `:large` keys
in its metadata map. The values are vectors of `:rf/path` vectors (the EP-0012
grammar; `[[]]` marks the whole root), **relative to that subsystem's
classification root** (§3). They are NOT Malli schema props and NOT absolute
runtime-db paths.

```clojure
(rf/reg-route    :reset-password  {:path "/reset"  :sensitive [[:query :token]]})
(rf/reg-resource :user-profile    {:params-schema [:map [:id :uuid]]
                                   :data-schema   ProfileSchema   ; validation only
                                   :sensitive     [[:ssn] [:dob]]})
(rf/reg-mutation :change-password {:params-schema [:map [:new-password :string]]
                                   :sensitive     [[:new-password]]})
(rf/reg-machine  :checkout/payment {:data-schema  PaymentSchema  ; validation only
                                    :sensitive    [[:data :token]]})

;; The frame classifies only the state it DIRECTLY holds (its app-db), and MAY
;; tighten/add for the rare context-dependent posture case (§6).
(rf/reg-frame :checkout {:sensitive {:app-db [[:auth :token]]}})
```

Each registrar already validates a closed reserved-key set and fails loud on
unknown bare keys (e.g. `reg-route`'s `reserved-route-keys`); `:sensitive` /
`:large` join that set, validated through the SAME `re-frame.marks/coerce-paths`
fail-loud path the frame and transient surfaces use (a malformed declaration
throws `:rf.error/bad-marks` at registration, never silently no-ops).

### 3. Per-subsystem classification roots

Each subsystem declares paths relative to a single, documented classification
root — the value shape the subsystem's egress slots carry:

| Subsystem | Classification root | Example path | Re-rooted to (effective runtime-db) |
|---|---|---|---|
| machine | the snapshot `:data` map's parent (the snapshot) | `[:data :token]` | `[:rf.runtime/machines :snapshots <every actor-id> :data :token]` |
| route | the `:current` route value | `[:query :token]`, `[:params :code]` | `[:rf.runtime/routing :current :query :token]` |
| resource | a cache entry's decoded data value | `[:ssn]` | `[:rf.runtime/resources :entries <every key-id> :data :ssn]` |
| resource (params) | a cache entry's scoped-key params value | `[:account-id]` | the entry's `:resource/key` params component |
| mutation | a mutation instance's variables value | `[:new-password]` | `[:rf.runtime/mutations <every work-id> :params :new-password]` |

The root is the value the subsystem's trace/SSR/tool slots already carry, so the
relative path is the natural, refactor-stable spelling (a `reset-password` route
authoring `[:query :token]` does not name `[:rf.runtime/routing :current …]` and
does not break if the routing slice's internal shape changes — the framework
owns the re-rooting). Machine `:data` keeps the existing snapshot-rooted
`[:data …]` convention already used by `re-frame.marks/project-machine-tags` and
the author marks it unions; this EP makes that the FIRST-CLASS surface rather
than a frame absolute path.

### 4. The fold (resolution algorithm, as a rule)

At each framework-mediated egress boundary, the effective classification for a
frame is the union of:

1. the frame's own app-db declarations (`:source :frame`, EP-0015);
2. for each registered durable subsystem, its `:sensitive` / `:large`
   declarations **re-rooted** to every live runtime-db location of that
   registered type in the frame.

**The re-rooting rule (principled, not enumerated):** a subsystem declares paths
relative to its classification root R (§3). At egress, for each live instance I
of the registered type, the effective absolute path is `(instance-root I) ++
relative-path`, and the projection slot the egress emits carries a value rooted
at some prefix of `(instance-root I)`; the framework strips the matching prefix
and applies the residual relative path to the slot value. The egress shape does
not enumerate which slots exist — it asks "what is this slot's root relative to
the instance root?" and re-roots accordingly. A new machine egress shape, or a
new subsystem, is covered the moment it declares its slot's root relative to the
instance root. This is the generalization of the existing
`frame-snapshot-marks` prefix-strip, lifted from "frame absolute path" to
"registration relative path × every instance."

**When it runs.** The fold is computed at egress (the boundary already runs
`project-egress` / `elide-wire-value` per EP-0015). It does NOT scan all live
instances eagerly: the egress already names the instance whose value it is
emitting (a machine trace carries `:actor-id`; a resource trace carries the
entry's `:resource/key`; a route slot is the single `:current`). The fold
resolves the registered TYPE's declarations and applies them to THAT instance's
slot — O(declarations-for-type), not O(all-instances). The "across all live
instances" property is a consequence of every instance's slot being projected
through the same type-resolved declarations, not an eager whole-runtime-db scan.

**Authored locally, resolved centrally.** Declarations live at the definition
(where the knowledge, safe-by-default, and spawn-coverage live). The elision
registry's effective classification (the `:rf.runtime/elision` slot tools query
— §7) is the resolved fold. Sensitive-wins-over-large survives the fold: it is
applied per resolved path at install/lookup exactly as EP-0015 §3 and
`re-frame.frame-classification` already do, after the subsystem and frame
contributions are unioned.

### 5. Generated keys resolve type → instances

A type-level declaration on `reg-machine` / `reg-resource` / `reg-mutation`
resolves to ALL entries / rows / actors of that registered type, including
generated-id instances:

- a `reg-machine` `:sensitive [[:data :token]]` covers the singleton AND every
  spawned `<type>#<n>` actor — the existing `project-machine-tags` already keys
  by `:actor-id` and would resolve the type's declaration for any actor id;
- a `reg-resource` `:sensitive [[:ssn]]` covers every cache entry whose
  `:resource/key` second element is that resource-id — exactly the
  type→instances resolution `re-frame.resources.classification/project-data`
  already performs by looking the spec up from the entry's stored resource-id;
- a `reg-mutation` `:sensitive [[:new-password]]` covers every live work-id row
  of that mutation type.

This is the property absolute frame-paths structurally cannot have (§Motivation
finding 3): you cannot write a static path to a key that does not exist yet. A
type-level declaration is the only expressible form, and the resources
implementation already proves it resolves correctly for generated keys.

### 6. The frame override (tighten-only, specificity-ruled)

The same machine / route / resource may run in frames with different
observability posture. The frame MAY override a subsystem's classification by
declaring the (re-rooted, absolute) runtime-db path in its own `:sensitive` /
`:large {:app-db …}` block — the existing EP-0015 frame surface, which already
accepts runtime-db-rooted paths.

**Specificity:** a frame declaration and a subsystem declaration that resolve to
the same effective path UNION (a path classified by either is classified). They
do not compete — union is the resolution, so "two declarations of the same fact"
is not ambiguity, it is reinforcement. This is why per-kind single-home is still
one route per fact (probe 1): the override is additive, not a replacement
authority.

**Tighten-only (the security rule).** A frame override may ADD a path the
subsystem did not classify, or RAISE a path's classification (large → sensitive).
A frame override may NOT declassify a subsystem-declared secret: there is no
"`:public` / `:not-sensitive` at the frame" spelling for a subsystem path, and
the fold never removes a subsystem `:sensitive` contribution. Rationale: for a
genuine secret (a token, an SSN) sensitivity is INTRINSIC — it is a secret in
every frame — so declassify-from-frame has no legitimate use and is a footgun
(the frame author silently un-protects a secret the subsystem author declared).
The legitimate "this frame is more observable" case only ever needs to ADD,
never to remove. (If a future real need for context-dependent **declassification**
appears, it is a separate ruling — it would be the `:rf.egress/public`
declassification analogue from EP-0015 issue 9, audited as a standing surface,
not a silent frame omission.)

### 7. One queryable effective classification (tooling)

The fold preserves EP-0015's single source of truth: the `:rf.runtime/elision`
registry's resolved declarations. Tools (Xray, MCP, SSR projector) read the
effective classification from one place — they do not query each subsystem
separately. The subsystem declarations are folded INTO that registry
(re-rooted), so "ask one place" holds. The implementation choice — eager install
of singleton-resolvable declarations vs lazy resolve-at-egress for generated-id
instances — is left to the spec graduation; the contract is that the queried
effective classification is complete for any instance the tool names.

### 8. Schemas validate; they do not classify

`:data-schema` / `:params-schema` / `:page-data-schema` describe shape and
validation. A `:sensitive?` / `:large?` Malli prop on any of them no longer
classifies durable state for egress.

Schema props that REMAIN (a different, legitimately schema-owned axis):

- `:data-schema` / `:params-schema` props still redact the **validation-failure
  trace** (the explainer output carries the failing value verbatim; that is the
  validator's own product, not durable state — `re-frame.resources.classification/`
  `redact-invalid-params-error`, the machine `:where :machine-data` failure
  path);
- HTTP `:decode` schema props still classify decoded HTTP **response bodies**
  (transient wire products — EP-0015 issue 5);
- HTTP request `:sensitive?` still scrubs outbound request material (rides the
  request, classifies an outbound wire payload, not a durable path —
  `rf2-398kql` confirmed out of scope).

The rule: schemas do not provide a route to classify a durable runtime-db value
the subsystem registration owns.

### 9. Non-user runtime state needs no classification

The reserved framework-internal runtime-db slots — `:rf/system-ids`,
`:rf/spawn-counter`, `:tag-index` / `:owner-index`, `:rf/snapshot-version`,
indexes — carry ids/counters/structural metadata, never user data. They are not
classifiable surfaces and need no declaration. (Confirmed by audit: the
secret-bearing slots are exactly the four in the §Motivation table; the rest are
addressing/bookkeeping.)

## Adversarial review

This section is the deliverable's problems-and-resolutions log. The design
session tried to BREAK the registration-time direction. Each probe below states
the attack, the finding, and whether the direction SURVIVED or FAILED.

### Probe 1 — Does this re-create "two routes"? (SURVIVED)

**Attack.** EP-0025's founding complaint was EP-0005's two co-equal routes
(schema props AND frame paths). Does "subsystem registration AND frame override"
re-create it?

**Finding.** No, because the two surfaces classify DIFFERENT kinds of state with
a single home each: app-db → frame; subsystem runtime-db → that subsystem's
registration. A given durable fact has exactly one authoring home. The frame
override is not a second route to the SAME fact — it is a specificity-ruled,
union-only, tighten-only addition (§6). The EP-0005 problem was that the same
machine `:data` slot could be classified two equally-first-class ways with a
side-table to reconcile them; here the subsystem declaration is the home and the
frame contribution unions (never reconciles-by-precedence, never needs a
side-table). **Survives**, conditional on the tighten-only rule (a declassifying
frame override WOULD re-introduce a competing authority — that is why §6 forbids
it).

### Probe 2 — Context-dependent sensitivity (SURVIVED, narrowed)

**Attack.** EP-0015 disposition 12's strongest pro-schema/anti-move argument:
the same machine type runs in frames with different observability posture, so
classification "belongs" where the posture differs (the frame).

**Finding.** For genuine secrets the premise is false: a token / SSN / password
is a secret in EVERY frame — sensitivity is intrinsic to the fact, not to the
frame's posture. The cases where a frame legitimately wants DIFFERENT treatment
are all "this frame is MORE observable / wants to ALSO redact something" — i.e.
tighten/add, which §6's frame override covers. The one case that would need the
frame to RELAX a subsystem secret (declassify) is a footgun (§6) and is excluded.
**Survives**, with the resolution that context-dependence is real only in the
tighten direction, handled by the frame override; declassify is out.

### Probe 3 — Resolution algorithm precision + performance (SURVIVED)

**Attack.** Does the fold scan all live instances per egress? Is the re-rooting
an enumeration that a 7th egress shape breaks?

**Finding.** No eager scan: the egress already names the instance whose value it
emits (`:actor-id`, `:resource/key`, the single `:current`), so the fold is
O(declarations-for-type) per emitted slot (§4). The re-rooting is a RULE
("strip the instance-root prefix the slot carries, apply the residual relative
path") not an enumeration of `:before`/`:after`/… — the existing
`frame-snapshot-marks` already implements the prefix-strip; this EP lifts it from
"frame absolute path" to "registration relative path × instance." Sensitive-wins
-over-large survives the fold (applied per resolved path after union, exactly as
`re-frame.frame-classification/validate+extract` already does). **Survives.**
Residual risk: the machine-`:data`-map slots (`:data`, `:input :data`,
`:cascade :data-delta`) are one level shallower than a full snapshot and need the
`:data`-prefix stripped — `project-machine-tags` already does this
(`strip-data-prefix`), so the rule must state the instance-root precisely per
slot shape. This is a spec-precision task, not a model failure.

### Probe 4 — Generated keys (SURVIVED — already proven)

**Attack.** Spawned actors, resource cache entries, and work-ledger rows have
runtime-generated keys. Can a type-level declaration reach them?

**Finding.** Yes, and the resources implementation already proves it:
`re-frame.resources.classification` classifies at the resource SPEC and resolves
to every cache entry (opaque byte `key-id`) by looking the spec up from the
resource-id stored in the entry's `:resource/key`. The same type→instances
resolution covers machine spawned actors (already keyed by `:actor-id` in
`project-machine-tags`) and mutation work-ids. This is the probe the
absolute-frame-path model FAILS (§Motivation finding 3) and the registration
model PASSES by construction. **Survives — strongest evidence for the new
direction.**

### Probe 5 — Relative-path grammar + EP-0012 (SURVIVED)

**Attack.** Are the relative `:rf/path` roots unambiguous per subsystem? Do path
optics / canonical forms compose?

**Finding.** Each subsystem has exactly one documented classification root (§3),
and the paths are ordinary EP-0012 `:rf/path` vectors validated through the
shared `re-frame.marks/coerce-paths` / `re-frame.path/normalize-concrete`
boundary (the same one frame and transient declarations use). The only
subtlety is the machine snapshot vs `:data`-map two-level root (probe 3), already
handled by `strip-data-prefix`. Routing's root is unambiguous and STATIC
(`[:rf.runtime/routing :current]`), which is a bonus: the token-in-URL standout
case (`/reset?token=`) has the cleanest home of all. **Survives.**

### Probe 6 — Tooling "ask one place" (SURVIVED)

**Attack.** Does folding declarations from four registrars destroy EP-0015's
single queryable effective classification?

**Finding.** No — the fold writes the re-rooted declarations INTO the
`:rf.runtime/elision` registry (§7), so tools still read one place. The
subsystem registrars are authoring inputs, not query surfaces. **Survives**, with
the open implementation choice (eager-install vs lazy-resolve) noted in §7 and
listed as an open question.

### Probe 7 — Resources asymmetry (RESOLVED — resources MOVE)

**Attack.** The first draft keeps resources on schema props while moving
machines off. Should resources MOVE to first-class `:sensitive` keys, or keep
schema props?

**Finding.** Resources MUST MOVE, for consistency with the ruling that schema
props are not a classification route (`rf2-398kql`) and with the new principle
(one mechanism for all durable subsystems). The resources implementation already
does registration-time, type-resolved classification — it just spells it with
Malli props. Moving the spelling from `:data-schema` `[:ssn {:sensitive? true}]`
to a first-class `:sensitive [[:ssn]]` key is mechanical (the extraction already
produces a `{path decl}` map; the new key produces the same map directly, via
`coerce-paths`, with NO schema-walker dependency). This also DISSOLVES EP-0015
disposition 12's third ground ("machines/resources/HTTP-bodies share one
mechanism — schema props") — they will still share one mechanism, the
first-class registration key, and HTTP **response bodies** remain a transient
wire product on `:decode` (probe 8 boundary), not durable subsystem state.
**Resolved: resources move.**

### Probe 8 — Non-user runtime state (SURVIVED)

**Attack.** Do framework-internal runtime-db slots (`:rf/system-ids`,
`:rf/spawn-counter`, indexes) need classification?

**Finding.** No — they are ids/counters/structural metadata, never user data
(§9). The secret-bearing slots are exactly the four owned subsystems.
**Survives.**

### Probe 9 — Migration (SURVIVED)

**Attack.** Pre-alpha, no shim. Is the move from EP-0005 schema props +
shipped-frame-absolute-paths to first-class keys mechanical?

**Finding.** Yes, three mechanical moves (§Migration). The one genuine care
point: schema-validation-FAILURE redaction is a SEPARATE, legitimately
schema-owned product and must be PRESERVED (§8) — the migration removes the
schema props' CLASSIFICATION role, not the validator's failure-trace redaction.
**Survives**, with the explicit preserve-failure-redaction constraint.

### Probe 10 — Scope / registration semantics (SURVIVED)

**Attack.** A machine/route/resource is registered at the IMAGE level (EP-0023).
Does its `:sensitive` travel with the image to every frame running it? How does a
frame override interact?

**Finding.** Yes — registration metadata is image-scoped (the registrar resolves
under the same realm/image-generation scope, per `re-frame.marks`'s
`marks-for` note: "marks bind to (kind, id) … `handler-meta` resolves under the
same realm / image-generation scope as the original registration"). So a
subsystem's `:sensitive` travels with the image to EVERY frame running it — THE
safe-by-default win the absolute-frame-path model loses (§Motivation finding 4).
A single-frame override is frame-scoped (the existing `:source :frame` elision
entry) and unions on top at the fold. **Survives — the safe-by-default property
is the decisive advantage over the shipped model.**

### New problems found

- **NP-1 (spec-precision, not a model failure).** The per-slot instance-root
  for the machine `:data`-map shapes (`:data`, `:input :data`,
  `:cascade :data-delta`) differs from the full-snapshot shapes by one `:data`
  level. The re-rooting rule (§4) must state the instance-root precisely per slot
  so the prefix-strip is unambiguous. `project-machine-tags` already does this
  (`strip-data-prefix`); the spec must lift it from implementation detail to
  stated rule.
- **NP-2 (eager vs lazy, open question).** §7 leaves whether singleton-resolvable
  declarations install eagerly (like the current frame paths) while generated-id
  instances resolve lazily at egress. Both satisfy the "ask one place" contract;
  the choice is a graduation decision with a perf/complexity trade-off. Flagged
  as an open question, not a blocker.
- **NP-3 (route `:current` is the only statically-pathable subsystem).** Routing
  could in principle stay on a frame absolute path (it has a static root). It
  should NOT — uniformity across subsystems is worth more than the one-subsystem
  shortcut (the shortcut re-introduces PLACE-over-NAME for routing alone, and the
  token-in-URL case is the highest-stakes secret). Routing uses the first-class
  key like every other subsystem.

### Verdict

**The registration-time direction SURVIVES adversarial scrutiny.** Every probe
either survives outright or resolves with a stated rule; the two first-draft /
EP-0015 rulings it overturns rest on grounds that the new direction dissolves
(probe 1, probe 7) or narrows (probe 2). The decisive evidence is that resources
ALREADY implement the model for generated keys — the new direction is the
existing resources mechanism generalized, with the spelling corrected off schema
props. No part of the new direction was found to fail. The residual items
(NP-1/2/3) are spec-precision and one graduation-time choice, not model defects.

## Rejected Alternatives

### Frame absolute-paths as sole owner (the first draft — REJECTED)

The shipped `rf2-398kql` model. Rejected for the six §Motivation reasons:
PLACE-over-NAME, extrinsic/fragile fail-open-on-drift, cannot express generated
keys, fail-open on omission across frames, enumerated re-rooting, `:app-db`
bucket overload.

### Keep resources on schema props, move only machines (REJECTED)

The first-draft asymmetry. Rejected (probe 7): it treats two identical durable
`:rf.runtime/*` situations oppositely and keeps schema props as a classification
route after the ruling that they must not be one.

### Malli `:sensitive?` props as the classification route (REJECTED)

The EP-0005 mechanism. Rejected: classification is a meaning-fact that belongs in
a dedicated declaration, not buried in a validation prop (the one valid EP-0005
complaint the ruling preserved). Schema props keep only validation-failure
redaction (§8).

### Frame override that can declassify (REJECTED)

A frame `:public` / `:not-sensitive` override of a subsystem secret. Rejected
(§6, probe 2): for intrinsic secrets it has no legitimate use and is a silent
footgun; the tighten-only rule preserves single-route discipline.

### Wildcard / glob path grammar for instances (REJECTED — unnecessary)

The first draft deferred a pattern-path grammar for spawned actors. Rejected as
unnecessary: type-level resolution (§5) reaches all instances without a path
pattern (the declaration names the TYPE, the framework resolves instances). No
new path-grammar surface is needed.

## Security And Privacy Considerations

This EP is a privacy-contract IMPROVEMENT over the shipped model on the two
properties that matter most:

- **Safe-by-default** (probe 10): a subsystem's `:sensitive` travels with its
  image to every frame, so a reusable secret-bearing subsystem cannot leak by a
  forgotten per-frame declaration. The shipped frame-absolute-path model is
  fail-open on omission.
- **Drift-resistance** (§Motivation finding 2): a name-anchored relative
  declaration does not break when subsystem internals are refactored, so a
  refactor cannot silently un-redact a secret. The shipped deep-absolute-path
  model is fail-open on drift.

Residual risk: a subsystem author who forgets to classify a secret leaks it (the
irreducible "you must declare your secrets" floor). The conformance criteria
include positive and negative tests per subsystem, including generated-id
instances and SSR.

Values still flow raw inside the application and are projected only at
framework-mediated egress (unchanged).

## Backwards Compatibility And Migration

Pre-alpha; no shim. The migration is mechanical:

1. **Machines:** replace any frame absolute snapshot-path declaration
   (`reg-frame` `:sensitive {:app-db [[:rf.runtime/machines :snapshots <id>
   :data …]]}`, the shipped `rf2-398kql` spelling) AND any residual machine
   `:data-schema` `:sensitive?` prop with a first-class `reg-machine`
   `:sensitive [[:data …]]` key.
2. **Resources / mutations:** replace `:data-schema` / `:params-schema`
   `:sensitive?` / `:large?` props (and coarse whole-entry `:sensitive?` /
   `:large?` spec props) with first-class `reg-resource` / `reg-mutation`
   `:sensitive` / `:large` keys. The extraction already produces a `{path decl}`
   map; the new key produces it directly.
3. **Routes:** declare URL secrets with `reg-route` `:sensitive [[:query :token]]`
   / `[[:params :code]]` (new — routes never had a classification surface).
4. **Preserve** schema-validation-FAILURE redaction (§8) — the migration removes
   the props' CLASSIFICATION role only; the validator's failure-trace redaction
   stays driven by the schema.

## Acceptance Criteria

A complete implementation satisfies all of:

- each durable-subsystem registrar (`reg-machine` / `reg-route` / `reg-resource`
  / `reg-mutation`) accepts first-class `:sensitive` / `:large` keys, validated
  fail-loud at registration through the shared marks path;
- a subsystem `:sensitive` declaration redacts the matching slot in EVERY egress
  shape for that subsystem (machine `:before`/`:after`/`:snapshot`/`:data`/
  `:input :data`/`:cascade :data-delta` + SSR; route `:current`; resource entry
  data/params + SSR hydration; mutation variables), across singleton AND
  generated-id instances;
- a Malli `:sensitive?` / `:large?` prop by itself does NOT redact durable state
  in trace / SSR / epoch / tool / observability egress;
- schema-validation-FAILURE traces still redact via the schema redactor;
- HTTP request/response classification (decode-schema props, request scrub) is
  unchanged;
- a frame override may ADD/RAISE but NOT declassify a subsystem secret;
- tools read ONE effective classification (the `:rf.runtime/elision` registry);
- implementation code contains no machine schema-marks side-table or lifecycle
  hooks (already shipped);
- Spec 015 / 005 / 012 / 016 / 010 / Spec-Schemas / Privacy / Security,
  `docs/api`, migration, examples, skills, and conformance no longer teach the
  schema-prop or frame-absolute-path durable-classification routes;
- conformance includes per-subsystem positive (declared → redacted, incl.
  generated-id) and negative (schema-prop-alone → NOT redacted) fixtures.

## Open Issues

- **OQ-1 (NP-2).** Eager-install vs lazy-resolve-at-egress for the fold (§7).
  Both satisfy "ask one place"; the choice is a perf/complexity trade-off for
  graduation.
- **OQ-2.** Whether a future, audited declassification analogue
  (`:rf.egress/public` at the subsystem/frame level, EP-0015 issue 9 style) is
  ever needed for context-dependent posture. Out of scope here (tighten-only is
  the v1 rule); flagged so a real need is a recorded ruling, not a silent gap.

These are graduation-time decisions, not blockers to accepting the direction.

## Recommendation

**Graduate as redesigned (registration-time subsystem classification).** The
direction survived every adversarial probe; the strongest evidence is that the
resources subsystem already implements it for generated keys, so the new
direction is a generalization of working code with the classification spelling
corrected off schema props onto first-class registration keys. It restores
safe-by-default and drift-resistance — the two properties the shipped
frame-absolute-path model loses — while keeping the ruling's core wins (one owner
+ one route per fact; no schema-prop classification; `:data-schema`
validation-only; the deleted side-table).

The two open questions (OQ-1 eager/lazy fold, OQ-2 future declassification) are
graduation-time refinements, not reasons to hold the direction. Recommend Mike
**accept the redesigned direction** and authorize graduation into the spec homes,
with OQ-1 settled during the Spec 015 graduation slice and OQ-2 left as a
documented future-ruling hook.

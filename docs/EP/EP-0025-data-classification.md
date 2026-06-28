# EP-0025: Data Classification

Status: final
Type: standards-track

> **Graduated 2026-06-24** into [`spec/015-Data-Classification.md`](../../spec/015-Data-Classification.md), reconciled across the affected specs (Privacy, Security, 009, 014, 016, 011) and the migration guide; the repo-wide propagation audit closed with zero findings. Per EP-0009 the spec now governs where it and this EP differ. One tool-local follow-up is tracked under Implementation Errata, below.

> **Supersedes** the earlier "Registration-Time Subsystem Data Classification" version
> of EP-0025 (a global registrar, frame app-db annotations, a per-egress
> type-resolution fold). It keeps that version's best idea — *facts are classified
> where their meaning is authored* — but recast as a **lightweight hygiene helper**,
> not a transactional contract.

## Scope (the north star)

**This feature is about hygiene and convenience, NOT security.** Its single job is a
convenient way to stop secrets and large payloads from *accidentally* reaching
observability sinks (Datadog, off-box logs) and AI tools (MCP, the trace bus an AI
pair reads). It is **not** a security boundary.

- **We trust the developer.** No ownership policing, no enforced secrecy.
- **We build minimal infrastructure.** Simple beats airtight.
- **It is fail-open.** A path you never classify ships raw. That's fine — hygiene,
  not a guarantee.
- **Exceptions are exceptional.** If an event throws or is rejected, all bets are off
  — fix the exception. We build no special machinery around it.

## Abstract

Data classification lets you mark a path as **sensitive** (redact it) or **large**
(keep it off the wire): durable app-db paths via four effects — `:sensitive`, `:large`,
`:clear-sensitive`, `:clear-large` — a handler returns (or a subsystem registration
declares), and transient event / effect / coeffect payloads via the same `:sensitive` /
`:large` keys on their registration. The framework then redacts
classified values at the **default/safe mediated egress profiles** — trace bus,
Xray, off-box shippers, MCP reads, SSR payloads, async-reply trace rows — so the raw
value never *accidentally* leaves the box (explicit raw-local escape hatches remain,
per existing egress policy).

Classification is **value-independent** (mark a path before it holds a value), lives
in a per-frame registry, and is read only at egress. There is **no global registrar,
no frame app-db annotation, and no schema-prop classification of durable state.** The
application always sees real values while events run.

## Motivation

A value can leave a frame through many framework-mediated paths. A developer wants
**one convenient way** to say "don't ship this." The failures are concrete: an auth
token in Datadog; a scanned PDF's bytes on a metrics backend; a user's SSN on the
trace stream an AI reads.

Three earlier approaches each failed structurally:

- **Malli schema props** as a route to **durable-state** classification.
  Classification is a *production* concern, so the schema objects + walker must
  survive into the production bundle un-elidably — but Malli is an *optional, ~24 KB,
  substitutable* artefact. Routing durable-state classification through schema props
  couples a production hygiene baseline to the optional validator and breaks under
  substitution. (Narrow scope — only durable-state; see *What is removed*.)
- **Frame-absolute paths into runtime-db.** Brittle under refactor, fail-open on
  reuse, can't name generated keys.
- **Imperative marks** (`add-marks` / `set-marks`). A separate mutable API, already
  demoted to test-only; removed here.

The reframe: **classify a path where its meaning is authored — its *definition site*.**
A classification lives in a per-frame registry, and a subsystem instance's lives and
dies with that instance. Known facts are *declared* at their definition site — an init
event, or a registration; discovered ones are *fired* as an effect. No global
registrar, and no cross-cutting annotation on a frame that doesn't own the data.

## Goals / Non-Goals

Goals:

- four effects — `:sensitive` / `:large` / `:clear-sensitive` /
  `:clear-large` — applied with the `:db` write so a classified path is redacted
  at egress;
- known app-db paths classified at init, discovered ones in the writing handler;
- each runtime subsystem classifies its own instance data, projection-relative,
  applied per-instance at creation and dropped on teardown;
- **remove** durable-state Malli-prop classification, durable app-db frame
  annotations, and the imperative marks API;
- a crisp **failure posture** (below): fail-open on omission, fail-loud on malformed
  input, fail-closed/loud on egress-policy failure.

Non-Goals:

- **not** a security guarantee, secrecy boundary, or taint-tracking system — no
  universal "same value anywhere" redaction;
- **no** transactional / rollback guarantees, ownership enforcement, or per-egress
  resolver;
- **no** Malli coupling for *durable-state* classification (transient and
  validation-failure redaction untouched);
- **no** loss of the **HTTP-carrier** capability — it moves onto the `:rf.http/managed`
  registration (the transient-payload case), not deleted (see *Transient payloads*);
- **no** compatibility shim (pre-alpha).

## Relationships

- **EP-0015** (frame-owned egress policy, `final`) — predecessor; a *Spec-015
  reconciliation* (below) lists what graduated text changes.
- **EP-0005** (machine `:data-schema`) — validation kept; props no longer classify
  durable state.
- **EP-0012** (`:rf/path`) — classification paths are `:rf/path` vectors.
- **EP-0011** (async reply envelope) — its trace rows are a covered egress boundary.
- **EP-0008** (production observability channels) — the always-on off-box path; it
  MUST project records before shipping (see *How consumers apply it*).
- **EP-0018** (one `reg-event`) — classification effects ride the single event form.
- **EP-0023 / 0024** (image-loaded frames, lifecycle) — init classification travels
  with the image; the registry is per-frame.
- **EP-0027** (frame initial events) — the **preferred** home for known-path
  classification; `:on-create` is transitional only.
- **Managed effects** (Spec 014 §Abort on actor destroy) — the machine case
  of subsystem teardown-clear shares this plumbing.

## How it works

**You classify a path; the framework redacts it at egress.** Sensitive → redaction
sentinel; large → size marker. You — and your handlers, subs, views — always see real
values while events run; redaction happens only at egress.

**Same-event ordering.** The four effects are applied **with the `:db` write** (a
frame-state transform at the commit point, *not* a later `:fx`), so a value
classified in the *same* event is redacted from its first egress. A classification
made earlier — at init, or any time before the value lands — trivially covers it.

**Value-independent.** Classify a path *before* any value exists there — the common,
safe pattern. A classification over a path that holds no value (or a
differently-shaped value) is a harmless **no-op**; it redacts whatever later occupies
the path. Values flow under a standing classification; you don't re-classify per
write.

There are two kinds of payload to classify, in one vocabulary: **durable state**
(app-db and subsystem runtime) and **transient payloads** (events, effects, coeffects).
Each is classified by its owner *at its definition site*, and every consumer reads both
through one projection.

**Durable state — two authoring tiers.**

- **Your app-db** — absolute paths you own. Classify **known** paths in the frame's
  init event; classify **discovered** paths in the handler that writes them.
- **Subsystems** — a subsystem owns its runtime storage, so you never name absolute
  runtime paths. You declare `:sensitive` / `:large` **relative to its instance
  projection**, and the subsystem applies it per-instance **at creation** and drops
  it **on teardown** (by any cause). No per-egress resolver; no storage paths in your
  code.

**One rule underneath both — classify at the fact's *definition site*.** Known-at-authoring
classification is declared where the thing is defined; discovered classification is
fired as an effect. A resource *is* defined at `reg-resource`, so it declares its own
(statically-known) sensitive fields there — that is "classify where the meaning is
authored," **not** a cross-cutting annotation. App-db has no single definition
registration (which is exactly why a *frame* annotation was the wrong home), so its
definition site is the **init event**. Same rule, different surface — and
`:sensitive` is one name for the fact in both.

**Subsystem matrix** (clarity, not machinery):

| Subsystem | Projection root | Applied at | Dropped at | Generated key |
|---|---|---|---|---|
| `reg-machine` | one actor snapshot's `:data` | actor spawn / entry action | actor destroy (any cause) | per spawned actor id |
| `reg-resource` | entry's `:params` / `:data` | **params/scope at scoped-key mint; data when fetch lands** | entry eviction | opaque cache key |
| `reg-mutation` | one work row's `:params` | work creation | work completion | per work id |
| `reg-route` | the current route's `:query` / `:params` | route activation | route change / deactivation | current route (effectively singleton) |

*Each subsystem **owns its own arrangement** — the projection and any nesting that
reads naturally for it (a route isn't a cache entry isn't an actor); this matrix is
illustrative, not a forced uniform shape. The only shared invariant is the **axis
vocabulary** (`:sensitive` / `:large`) over projection-relative
paths — that keeps it one-name-per-fact; the rest is the subsystem's ergonomic call.*

**The surface — four effects + subsystem declarations.**

```clojure
:sensitive       [[path] …]   ; classify sensitive
:large           [[path] …]   ; classify large
:clear-sensitive [[path] …]   ; un-classify sensitive
:clear-large     [[path] …]   ; un-classify large
```
```clojure
(rf/reg-resource :user-profile {:sensitive [[:data :ssn]] :large [[:data :avatar-bytes]]})
```
The concept *is* the verb (`:sensitive` already means "classify sensitive"), and
clear mirrors set per axis — ordinary set/unset symmetry, like `assoc`/`dissoc`.
(The keys are **bare** — `:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`.
This extends the registration-layer `:sensitive` / `:large` *already reserved* in
Spec-Schemas to handler effects, and adds the two clear tails. The EP reserves those
additions in the Conventions table — accepting the EP ratifies them.)

**Transient payloads (events, effects, coeffects) — classified at their registration.**
A transient payload's *definition site* is its registration, so its **author** declares
the sensitive/large paths there — the same vocabulary, relative to the payload's shape:

```clojure
(rf/reg-fx :rf.http/managed
  {:sensitive [[:headers "Authorization"] [:body :password]]
   :large     [[:body :upload]]}
  managed-handler)

(rf/reg-fx :rf.ws/send {:sensitive [[:auth]]} ws-handler)

(rf/reg-event :auth/login {:sensitive [[1 :password]]}
  (fn [{:keys [db]} [_ {:keys [user password]}]] …))

(rf/reg-cofx :session {:sensitive [[:token]]}
  (fn [cofx] (assoc cofx :session {:user "alice" :token (read-token)})))
```

So **`:rf.http/managed` is not special, and neither is a websocket effect** — every
effect / coeffect / event author declares its own sensitive arg-paths once, at
registration. The framework ships sensible defaults for its own effects (e.g. the
standard sensitive-header denylist for HTTP); library authors do it for theirs.

**Lifecycle.** Clearing is **rarely reached for**: a classification over absent data
is already a no-op, so when data disappears the redaction effectively disappears with
it. `:clear-sensitive` / `:clear-large` mainly matter when a path is *reused*
for non-secret data, or when a subsystem tidies its registry on teardown.

**Egress rules.** Sensitive wins over large at the same path. Large auto-elides an
oversized value even at an undeclared path (the size backstop). A `:large`-marked
subtree containing a `:sensitive` descendant redacts rather than showing a size
preview. **Classification does not propagate** — you redact exactly the paths you
classify, and nothing is inherited (no "derived output takes its input's sensitivity,"
no universal "same value redacted everywhere"). If you derive a secret to a new path —
through a sub, a flow, anything — classify that path. The existing sentinel/display
contract is unchanged.

**How consumers apply it — one generic projection.** A consumer never invents
redaction; it calls **one** projection over the record it is about to emit. The
projection redacts both sources: **durable** paths from the per-frame registry, and
**transient** paths from each record slot's registration. `project-egress` is the choke
point and it is *record-kind aware* — it knows a record's event / fx / cofx / sub / flow
**slots**, and applies each slot's registered classification (no ambient scan for
keyword-ids in arbitrary data). A Datadog shipper is therefore the whole of:

```clojure
(rf/reg-observability-channel :datadog
  (fn [record] (datadog/send! (rf.egress/project record))))
```

It knows nothing about HTTP, websockets, or any effect — **add a new effect tomorrow
and its `reg-fx` declaration is redacted with zero changes to the shipper.** Every
consumer works this way: the prod off-box observability channel (EP-0008) projects
before shipping (closing the always-on off-box-ships-raw gap); the MCP
server (`mcp-base`) projects every value it returns to an AI; Xray, SSR, and async-reply
trace rows likewise. Redaction applies at the **default/safe** profiles; local trusted
profiles may keep raw escape hatches per existing egress policy.

**Storage & SSR.** Classification lives in a per-frame registry (runtime-db state).
**SSR stays allowlist-first** — only allowlisted runtime-db slices cross the hydration
wire; classification is **defense-in-depth** on top, and any registry view that does
cross is itself projected (paths can embed sensitive ids, so the raw registry is not
serialized wholesale). Two frames running the same image get the same init baseline;
runtime classifications are per-frame. Tools (Xray / MCP) can read the registry to
show what's classified.

**Construction & `:initial-db`.** `:initial-db` is seeded before `:initial-events`
run, so init-event classification protects **post-init egress**, not raw construction
internals. Prefer loading secret-bearing state through an **init event** (which
classifies it) rather than raw `:initial-db`. (Per the scope, a guideline — not
machinery.)

**Failure posture.** Three rules, so the helper stays humble without weakening egress
correctness:

- a **forgotten** classification is **fail-open** — the value ships raw (the hygiene
  bargain);
- a **malformed** effect payload (bad path, wrong shape, unknown axis) is **fail-loud**
  where it's applied (`:rf.error/*`) — aborting the transition pre-commit, like any other
  state-effect validation, via the existing pre-commit-transactional path (no new
  commit-phase machinery);
- an **unknown frame / profile / projector failure** falls back to the **existing
  egress policy's fail-closed / fail-loud** behaviour — the projector redacts or errors
  rather than leaking.

**Vocabulary & lowering (reference).**

- **Handler effects** (durable app-db) — bare `:sensitive` / `:large` / `:clear-sensitive`
  / `:clear-large`, in the open effect map a handler returns; written into the per-frame
  registry with the `:db` write.
- **Registration metadata** — bare `:sensitive` / `:large`, in the closed registration
  map. Covers *transient* payloads (`reg-fx` / `reg-cofx` / `reg-event` / `reg-sub`) and
  *subsystem declarations* (`reg-resource` / `reg-machine` / …). This is the key already
  reserved at this layer in Spec-Schemas.
- **Subsystem declarations** are instance-birth templates: the subsystem lowers them into
  the same per-frame registry **per instance** — params/scope when the instance is minted,
  data when it lands — and clears on teardown.
- **One choke point:** `project-egress`, record-kind / slot aware, reads both registry
  (durable) and registration (transient) and redacts. Malformed payloads abort pre-commit.
- **Not present:** no propagation, no per-egress resolver, no rollback, no security claim.

## What is removed (and what is kept)

Removed:

- the **durable app-db** frame `:sensitive` / `:large` annotation (→ init-event
  effects);
- the **durable-state** Malli schema-prop classification route + its schema→registry
  bridge;
- the imperative marks API (`add-marks` / `set-marks` / `clear-app-db-marks!`);
- **all sensitivity propagation** — the derived-output policy and its
  `:rf.egress/output-sensitivity` key. Classification no longer flows input → output
  (subs *or* flows); you classify the paths you care about, and a sensitive flow output
  is just a classified db path.

**Kept (do NOT delete with the above):**

- **HTTP carrier classification** — *not a special surface.* It is just the
  **transient-payload** case: `reg-fx :rf.http/managed` declares its sensitive header /
  body paths (plus the framework's default sensitive-header denylist), exactly like any
  effect (see *Transient payloads*). The frame `:sensitive {:http …}` annotation is
  removed; the capability lives on the effect's registration.
- schema-validation-failure redaction; HTTP / transient payload redaction;
  registration-owned transient `:sensitive` / `:large`.

The elision-registry + egress-projection **substrate** is kept (renamed off "marks").

## Examples

```clojure
;; known app-db secret, in the frame's init event
(rf/reg-event :auth/init
  (fn [{:keys [db]} _]
    {:db (assoc db :auth {})
     :sensitive [[:auth :token]]}))

;; discovered at runtime, classified in the handler that writes it
(rf/reg-event :doc/scanned
  (fn [{:keys [db]} [_ doc-id raw]]
    (cond-> {:db (assoc-in db [:docs doc-id] {:body raw})}
      (contains-pii? raw) (assoc :sensitive [[:docs doc-id :body]]))))

;; rare: a path reused for non-secret data is un-classified
(rf/reg-event :doc/sanitised
  (fn [{:keys [db]} [_ doc-id clean]]
    {:db (assoc-in db [:docs doc-id :body] clean)
     :clear-sensitive [[:docs doc-id :body]]}))

;; a subsystem declares its own sensitive/large fields, projection-relative
(rf/reg-resource :user-profile
  {:sensitive [[:data :ssn]] :large [[:data :avatar-bytes]]})
```

## Rationale

**Effects, not a contract.** This is a convenience for keeping secrets out of logs
and AI tools. The surface is four flat effects; the mechanism is "record the path,
redact at egress." We don't engineer transactional guarantees, rollback semantics, or
taint propagation — that's rigor a hygiene feature doesn't earn.

**Four flat keys, per-axis.** Concept-as-key (`:sensitive`) avoids a generic
`:rf/classify` verb; per-axis clear (`:clear-sensitive`) fits the two-axis model
(clear sensitive, keep large) where an axisless clear couldn't, and is just normal
set/unset symmetry.

**Projection-relative subsystem authoring, applied at creation.** Absolute runtime
paths in author code are the storage-position problem; applying per-instance at
creation reaches every generated instance with no per-egress resolver.

**Profile-aware, not absolute.** "Redact at the default/safe mediated profiles" is the
honest claim — local trusted tooling may see raw per existing policy. A hygiene helper
must not imply an absolute guarantee.

**Why kill durable-state schema props.** Durable-state classification is un-elidable
production policy; Malli is optional and substitutable. Transient and
validation-failure redaction are unaffected.

## Rejected Alternatives

- **Registration-keyed declarations + per-egress fold** (prior EP-0025) — a
  cross-cutting resolver on every egress, beyond scope.
- **Transactional / per-contribution rollback machinery** — over-engineering; an
  exception means fix the exception, and a leftover no-op mark is harmless.
- **Axisless `:rf/declassify`** — a flat per-axis *set* paired with an axisless *clear*
  is asymmetric and can't express "clear sensitive, keep large."
- **Universal value-match backstop** — equality-based taint, disclaimed by scope.
- **Any sensitivity propagation** (input → output, through subs *or* flows) — machinery
  for an unusual case already covered by classifying the output path; a sensitive flow
  output is just a classified db path. No-propagation keeps the helper lightweight.
- **Frame `:sensitive` / `:large` durable annotations** (the frame isn't app-db's definition site);
  **absolute runtime paths in author code** (storage-position problem);
  **durable-state Malli props; monotonic clear; imperative marks** — removed as above.

## Security And Privacy Considerations

**Hygiene, not security**, and the EP says so loudly: fail-open on omission;
unrestricted clear; the application sees real values; no taint tracking; no rollback
guarantees; redaction applies at the **default/safe** profiles, not absolutely (raw
escape hatches remain per policy). What it provides: one convenient way to keep
*classified* values off the trace bus, Xray, off-box shippers, MCP reads, SSR, and
async-reply rows. The failure posture (above) keeps the helper humble while the
egress projector itself stays fail-closed/loud. Teams needing a real secrecy guarantee
must keep secret material out of the egressed projections by construction.

## Backwards Compatibility And Migration

Pre-alpha; no shim. Source-level:

- replace **durable app-db** frame `:sensitive`/`:large` annotations with init-event
  effects; move the frame-local `:sensitive {:http …}` HTTP carrier onto the
  `:rf.http/managed` registration (the transient-payload case);
- replace **durable-state** Malli props with effects or subsystem projection-relative
  declarations, **keeping** schemas for validation and keeping transient /
  validation-failure / HTTP redaction;
- move subsystem durable classification onto the projection-relative declaration;
- remove the imperative marks API and all `add-marks`/`set-marks` call sites
  (`grep -ri marks` is the worklist).

**Spec-015 reconciliation.** Replaced: `reg-frame {:sensitive {:app-db …}}`, the
durable-state schema-prop route, the per-egress fold. Removed: the derived-output
policy + `:rf.egress/output-sensitivity` (no propagation). Kept: the egress
sentinel/display contract; transient classification; validation-failure redaction.
Amended: machine `:data` durable classification (→ projection-relative declaration); the
HTTP carrier (→ the `:rf.http/managed` registration). Graduation carries a
section-by-section map.

## Bead Plan / Reference Implementation

1. **Purge marks FIRST — before any new structure (decks-clearing).**
   A comprehensive `grep -ri marks` sweep: **migrate** the load-bearing projection
   substrate out of `marks.cljc` into the elision engine (renamed, marks-free);
   **remove** the imperative API (`add-marks`/`set-marks`/`clear-app-db-marks!`), the
   `:source :marks` feed, and the "marks" terminology; and **remove all sensitivity
   propagation** — the sub-output *and* derived-output (`:rf.egress/output-sensitivity`)
   policy, so nothing flows input → output. The substrate migration is
   behaviour-preserving (the migrated engine keeps redacting every **explicitly
   classified** path), **but dropping propagation is a deliberate Spec-015 / conformance
   change** (not a no-op purge) — and it reaches beyond `marks.cljc` to the
   `:rf.egress/output-sensitivity` claim on `reg-sub` / `reg-flow`. Track it as its own
   conformance-bearing step.
2. **Second sweep — verify the purge (be sure).** A fresh, independent `git grep -i marks`
   over **tracked** source (excluding generated docs/spec + the `site/` tree)
   confirming **zero residue** — no imperative API, no `:source :marks`, no `marks.cljc`,
   no marks hooks, no "marks" terminology anywhere — and that the migrated substrate
   carries no "marks" naming. Run as a distinct bead, fresh eyes, before building new.
3. Add the four effects, applied **with the `:db` write** (frame-state transform, not
   post-commit `:fx`); record in the per-frame registry; **fail-loud on malformed
   payloads**.
4. Point the egress projection at the registry as the single source: sensitive/large,
   sensitive-wins precedence, nested-axis suppression, `[[]]` whole-value, size
   backstop, **no propagation** (classify exactly what you mark — no value-match, no
   input → output inheritance) — **profile-aware**,
   and ensure **every default/safe consumer projects** (prod off-box / EP-0008, MCP /
   `mcp-base`, Xray, SSR, async-reply), closing the off-box-ships-raw gap.
5. Remove the **durable app-db** frame annotation + durable-state Malli-prop
   classification **and the derived-output propagation policy**; **keep** validation /
   transient / validation-failure / HTTP-carrier redaction.
6. Subsystem projection-relative declaration applied per-instance at creation + dropped
   on teardown, per the subsystem matrix (reuse actor-destroy plumbing for machines).
7. **SSR**: allowlist-first; project any registry view that crosses the hydration wire.
8. Graduate normative text + conformance into the affected specs: `015` (+ §reconciliation);
   `002-Frames` (the four effects join the commit-plane effect-map set; the
   registry-write rule amended); `005`, `012`, `016`; `009-Instrumentation`,
   `010-Schemas`, `011-SSR`, `014-HTTPRequests`; `Security`, `Privacy`,
   `Managed-Effects`; `Ownership`, `Tool-Pair`, `Conventions`, `Spec-Schemas`, `API`;
   the `spec/conformance/` EDN corpus; the relevant `Pattern-*` docs (esp. RemoteData /
   Forms); `Cross-Spec-Interactions` / `Cross-Cutting-Designs` edge cases; and an
   `AI-Audit` re-score.
9. **Update `/docs/core`** — rewrite the privacy/security guide pages (esp. the
   "keep secrets out of traces" how-to) to teach the four classification effects +
   subsystem declarations, and **remove all old-surface teaching** (durable app-db
   frame annotations, schema-prop classification, imperative marks).
10. **Update `/skills`** — update the affected Claude skills (re-frame2 authoring,
    re-frame2-pair, re-frame2-improver, re-frame2-xray, and any that reference
    classification / sensitive / large / redaction) to the new surface; strip
    old-surface guidance.
11. **Conformance fixtures:** same-event classify-then-egress; axis-independent clear;
   sensitive-vs-large precedence + nested suppression; `[[]]` whole-value; cross-frame
   isolation; profile-aware egress (default-redacts / raw-local-passes); SSR allowlist +
   projection; MCP/Xray/off-box-sink redaction; per-subsystem generated-instance create
   + teardown; malformed-payload fail-loud.

### Repo-wide propagation — one bead per area (be exhaustive)

Cross-cutting change (every egress boundary; removes "marks"). Sweep, beyond the
specs (8), `/docs/core` (9), and `/skills` (10):

- **`/tools` — the consumer / egress side (the half that delivers the value):**
  - **`mcp-base`** — the shared redaction lives here: read the new registry; project
    every value returned to an AI client.
  - **`re-frame2-pair-mcp` + `story-mcp`** — every read (app-db, subs, traces, epochs)
    is projected before it reaches the AI client.
  - **`xray`** — every panel that shows a value (event detail, app-db diff,
    subscriptions, machine inspector, schema-violation timeline, AI co-pilot rail)
    projects; surface "what's classified" from the registry; **strip all "marks"
    references.**
  - **`story` + `machines-viz`** — displayed / serialized values project (machine
    `:data`, variant EDN).
  - **`mcp-conformance`** — add classification-redaction wire conformance.
  - **`testbed-support`, `template`** — marks residue + any classification teaching.
- **`/examples`** — update any example using classification to the four effects;
  confirm no `:sensitive {:app-db}` / schema-prop / marks usage remains.
- **`/testbeds`** — review the classification-relevant beds (`schema_violation`,
  `large_dispatcher`, `non_trivial_app_db`, the `ssr_*` ones) for the new surface +
  marks residue.
- **`implementation/security/`** — the cross-cutting security regression tests (MCP
  egress, schema redaction, SSR escaping) must exercise the new projection path and
  the prod-off-box-projects fix.
- **`/migration`** — check the v1→v2 rules for any classification / redaction guidance.

Run this cluster after the new structure is in (stage 3+); graduation is not complete
until it's green.

## Resolved Decisions

Both items were dispositioned by graduation (no open issues ship silently, per EP-0009).

- **`marks.cljc` decomposition** (Bead-Plan stage 1) — **resolved in implementation.** The imperative marks API was removed and its logic recast as the classification effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`); the `marks` vocabulary and namespace residue were swept across spec and code. Confirmed by the propagation audit (zero findings).
- **Per-subsystem declaration shape** — *decided in principle:* each subsystem owns the arrangement that fits it (shared axis keys + projection-relative paths; the rest is its own ergonomic call). The concrete shapes landed in each subsystem's spec — not an EP-level blocker.

## Recommendation

Accept the **direction**: a lightweight hygiene helper — four effects
(`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`), applied
with the `:db` write, redacting classified values at the default/safe egress profiles;
app-db absolute, subsystem projection-relative (applied per-instance at creation);
value-independent; a clear three-way failure posture; with the imperative marks API,
durable app-db frame annotations, durable-state Malli-prop classification, and all
sensitivity propagation removed — with the HTTP-carrier capability moved onto the
`:rf.http/managed` registration (the transient case).

The design keeps every scope decision — hygiene, no egress resolver, fire-per-instance,
trust the developer — and stays a *helper*, not a contract.

## Implementation Errata

*Final means the decisions are settled; it does not assert the build is gap-free (EP-0009). Rows are struck as they close.*

- **Open (P2 decision)** — Story-MCP's two inherently re-keyed, routinely non-live scrub surfaces (`record-as-variant`, `read-a11y-violations`) meet the now fail-closed `project-egress` boundary, which would redact their whole re-keyed payload to `:rf/redacted` without closing any leak the live-frame case would have. An **explicit, tracked interim** ships those payloads raw when the variant frame is non-live; the framework boundary itself stays fail-closed. The permanent option (require a live frame / structured refusal / a trusted-local-raw profile) awaits an operator ruling. Tool-local — it does not change the EP-0025 contract.

## Guide impact

The classification helper is taught in the human guide at `how-to/keep-secrets-out-of-traces.md` (its canonical home), with recaps where values reach a mediated egress — `concepts/observability.md`, `concepts/server-state.md`, `concepts/routing.md`, `concepts/ssr.md` — and the `:sensitive` (path/effect) vs `:sensitive?` (schema trace-flag) distinction noted in `how-to/validate-with-schemas.md`. These edits landed with the guide coherence pass; graduation owes no further human-facing guide change.

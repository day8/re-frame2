# EP-0010: Causal World Inputs

Status: final
Type: standards-track

> This EP defines the replay-determinism rule for host facts: when time,
> randomness, UUIDs, browser facts, storage reads, or asynchronous completion
> facts affect durable frame-state, they enter the frame fold as causal input
> data, not as ambient reads during transition execution.
>
> **Ruling recorded 2026-06-11 (Mike, bead `rf2-jm6tlv`).** Accepted. All five
> open issues are dispositioned **as recommended**, with three riders recorded
> in [§Open Issues](#open-issues): the named `:rf.world/uuid` deferral trigger,
> the `:dispatched-at` same-change retirement rule, and the secrets-exclusion
> boundary on recordable randomness (now normative in [§Randomness, UUIDs, And
> Generated Identity](#randomness-uuids-and-generated-identity)). Implementation
> is tracked by the EP-0010 action wave; the EP-0011 lowering chain is scheduled
> **behind** this wave (Mike, 2026-06-11) — the envelope's completion timestamps
> are this EP's causal facts, so world inputs land first and each EP-0011
> family slice is born causally correct.
>
> Normative home after acceptance: `spec/002-Frames.md`, `spec/016-Resources.md`,
> and the managed-effects/runtime-subsystem sections that define dispatch
> envelopes, coeffects, reply envelopes, restore, and conformance fixtures.

> **⚠ The envelope field is RENAMED — `:rf.world/inputs` → `:rf.cofx` (EP-0017).**
> This EP's body, examples, and Backwards-Compatibility section describe the
> originally-shipped surface, where the recordable-coeffect map rode the envelope
> under `:rf.world/inputs` and v1 `inject-cofx` had a migration path.
> [EP-0017 (Recordable Coeffects)](EP-0017-recordable-coeffects.md) **superseded
> that surface**: the field is now the flat `:rf.cofx` map, the old spelling is a
> **hard error** (`:rf.error/world-inputs-renamed`, no alias / no coexistence
> window), and `inject-cofx` is **removed**. The rename and the flat-map shape are
> graduated into `spec/002-Frames.md` §Recordable coeffects and
> `spec/Spec-Schemas.md` (`:rf.cofx`). **Read this EP for the recording *rule* and
> rationale; the spec governs the current authoring surface.** The recording
> contract itself (durable state folds facts, never reads) is unchanged.

> **`final` means the decisions are settled (2026-06-11, bead `rf2-s9ss0t`).**
> All five open issues were dispositioned with three riders recorded (see
> [§Open Issues](#open-issues)); the design is locked. The **core slice** has
> shipped: the recordable-coeffect envelope field + framework coeffect (shipped as
> `:rf.world/inputs`, since **renamed to `:rf.cofx` by EP-0017** — see the
> supersession banner above), the envelope stamping rule (`:time-ms` stamped once
> at the causal boundary, caller-supplied preserved, child dispatches stamped
> fresh), the user-cofx trace-projection filter, and the `:dispatched-at`
> retirement (rider b, no coexistence window) are normative in
> `spec/002-Frames.md` §Recordable coeffects and registered in
> `spec/Spec-Schemas.md` (`:rf.cofx`).
> Finalizing the *decisions* does not, on its own, assert the *implementation*
> is gap-free (EP-0005 pattern); the [Implementation errata](#implementation-errata)
> ledger below records the wave's build steps — now all shipped and closed, with
> the `:rf.world/uuid` / `:rf.world/random` coeffects explicitly deferred behind
> the optimistic-mutations EP.

## Implementation errata

The EP decisions are final, and the EP-0010 action wave is **complete**. The
**core slice** (envelope field, coeffect, stamping, trace filter,
`:dispatched-at` retirement, spec graduation into 002 + Spec-Schemas) shipped
under `rf2-s9ss0t`, and the five follow-on build steps below have since shipped
and merged. Each carried out a settled decision; none changed a contract. This
ledger now records the wave as closed, not as open errata. The single explicit
deferral (`:rf.world/uuid` / `:rf.world/random` recordable coeffects, disposition
2's rider) remains deferred behind the optimistic-mutations EP and is recorded as
such below.

### Shipped — EP-0010 wave steps

All five build steps of the EP-0010 action wave shipped. Verified
2026-06-12 against the merged implementation, tests, docs, and `git log`; the
closing bead-ids / commits are cited per step.

- **Causal-token coverage across all dispatch sites — SHIPPED.** Reference
  Implementation step 4. Every causal token stamps or supplies its own
  `:rf.world/inputs`: the core router stamps `:dispatch` / `:dispatch-later`
  fresh per child (`re-frame.router/build-envelope`, not inherited via
  `inheritable-envelope-keys`); machine `:after` timers dispatch without world
  inputs so the router stamps fresh at fire time (`rf2-hg39nf` adversarial
  fresh-token regression); routing dispatches carry `:source :router`; HTTP
  replies thread `:completed-at` as `:rf.world/inputs :time-ms`
  (`re-frame.http.encoding/dispatch-reply-via-late-bind!`); SSR hydration is a
  normal router-stamped event; and the tool dispatch helpers carry it too
  (pair-mcp `dispatch` accepts `:rf.world/inputs`, `rf2-q6s1nb`; story strips
  the volatile `:time-ms` for canonical fingerprints, `rf2-jt854w`; Xray
  surfaces it in the Event lens, `rf2-9fyn40`). Machine callback contexts thread
  it (`rf2-g0m4p5`).
- **Resource / mutation / work-ledger timestamps + reply completion — SHIPPED.**
  Steps 5–6
  ([§Resources, Mutations, And Work-Ledger Timestamps](#resources-mutations-and-work-ledger-timestamps)).
  `:started-at`, `:deadline-at`, and `:invalidated-at` are durable runtime-db
  facts read from the triggering token's `:time-ms`
  (`rf2-258p1z`, `rf2-uuzj88`, `rf2-dsyqmz`); `:completed-at`, `:loaded-at`,
  `:stale-at`, and mutation `:settled-at` come from the reply token's causal
  completion time (`rf2-n1rh0f`, `rf2-40dqi6`, `rf2-r65m41`); restore reconciles
  `:settled-at` from the causal token (`rf2-wshzsp`); the `:committed-at` epoch
  stamp comes from the causal token's `:time-ms` (`rf2-bh56rc`), with the
  pair-tool / wall-clock source pinned (`rf2-czwwf4`, `rf2-2elcw3`, `rf2-1t30y7`).
  The conversion is complete: no ambient `interop/now-ms` read survives in a
  durable resource/mutation/work-ledger reducer write site (the lint guard below
  enforces this; subscription/SSR freshness reads are on-read view-layer
  projections, explicitly allowed).
- **Recordable-coeffect guidance + time helper — SHIPPED.** Step 7,
  disposition 2 (core time path first). The compatibility `:app/now-ms` cofx
  reads only the already-seeded `:rf.world/inputs` coeffect
  (`(:time-ms (:rf.world/inputs cofx))`) and performs no ambient clock read, so
  a scripted/replayed `:time-ms` is returned exactly
  (`re-frame.cofx`, pinned by `cofx_test.clj`). The recordable-coeffect doctrine
  is normative in [§Event Context And Coeffects](#event-context-and-coeffects).
- **Replay fixtures + lint/conformance — SHIPPED.** Steps 8–9
  ([§Validation / Conformance](#validation--conformance)). An end-to-end
  replay-determinism fixture
  (`implementation/resources/test/re_frame/replay_determinism_e2e_cljs_test.cljc`)
  supplies scripted `:rf.world/inputs` (`:time-ms`, `:uuid`, `:random`) and
  asserts equal durable projections across two runs under differing ambient
  clocks and RNG. The static lint guard `scripts/check_ambient_durable_reads.py`
  flags ambient durable-world reads (`interop/now-ms`, `js/Date.now`, `rand`,
  `random-uuid`, `js/location`, …) inside enumerated durable-write namespaces,
  with the diagnostic / host-transient / effect-interpreter allowlists and a
  self-test of planted-violation + sanctioned-pattern fixtures; it is wired into
  the PR spine in `.github/workflows/test.yml` (`rf2-f2t151`).
- **Docs + guide update — SHIPPED.** Step 10 ([§Guide Impact](#guide-impact)).
  The guide teaches causal world inputs instead of ambient clock stubbing across
  the effects-and-coeffects concept page
  (`docs/guide/concepts/effects-and-coeffects.md` — §Two grades: ambient and
  recordable, §Testing is just supplying the inputs), the testing how-tos
  (`docs/guide/how-to/test-an-event-handler.md` and
  `docs/guide/how-to/test-a-cascade.md`, which pin `:rf/time-ms` via `:rf.cofx`
  rather than freezing the clock), and the v1 migration chapter
  (`docs/guide/25-from-re-frame-v1.md`) (`rf2-nj416f`, `rf2-q2vbuf`; comment + skills passes
  `rf2-kpg1fh`, `rf2-d4q7xc`). The spec graduation that anchors them is normative
  in `spec/002-Frames.md` §Recordable coeffects and `spec/Spec-Schemas.md`
  (`:rf.cofx` — renamed from the shipped `:rf.world/inputs` / `WorldInputs` by
  EP-0017).

### Deferred — recordable UUID / random coeffects

- **`:rf.world/uuid` / `:rf.world/random` recordable coeffects — DEFERRED.**
  Disposition 2's rider scheduled these *behind* the optimistic-mutations
  follow-on EP (temp ids for optimistic inserts — the managed-HTTP RealWorld
  example's optimistic comment flow is the visible in-repo case). The envelope
  already carries the `:uuid` / `:random` slots (a caller or fixture may supply
  them today, and the replay-determinism fixture above exercises both), but no
  framework `reg-cofx` for them ships yet — by design, not as a gap. This item
  graduates with that EP.
- **Successor — [EP-0017 Recordable Coeffects](EP-0017-recordable-coeffects.md)
  (final).** EP-0017 is that follow-on EP: it completes and
  continues this EP's authoring surface, renaming the envelope field
  `:rf.world/inputs` → `:rf.cofx` (flat), re-expressing the deferred recordable
  `:uuid` / `:random` slice as app-registered value-returning suppliers, and
  superseding this EP's optimistic-mutations gate with its own slice-B gate.
  EP-0010 stays `final` and its recording contract is preserved verbatim; this
  is a forward pointer to where the deferred slice and the renamed surface land,
  not a status change here.

### Review series — closed clean

The cross-cutting review beads `rf2-cc25b9` (correctness + completeness),
`rf2-h273u8` (independent second review), and `rf2-bkp3ik` (testing-rigour +
coverage audit) are all closed, as is the mandatory final review `rf2-czc9zn`
(graduation-ready) and the best-practice pass `rf2-s2mizv` (PR #4020).

## Abstract

re-frame2's core model is a causal fold:

```text
next-frame-state = transition(previous-frame-state, causal-token)
```

That model is only literal when a transition's durable result is determined by
prior frame-state plus the token being folded. Today an event handler, resource
reducer, work-ledger writer, machine action, or routing reducer can still call
the host directly for facts such as "what time is it?", "give me a UUID", "pick
a random value", "what URL is the browser on?", or "what did localStorage say?"
and then write the result into app-db or runtime-db. The resulting state may be
correct for the live session, but it is not replayable as a value.

This EP defines **causal world inputs**: host facts that can affect durable
frame-state MUST be represented as data on a causal token or as an explicit
recordable coeffect whose value is captured in that token's replay record. The
dispatch envelope gains a canonical `:rf.world/inputs` map, exposed to handlers
as a framework coeffect. Ambient host reads remain allowed for diagnostics,
performance measurement, scheduling, effect interpretation, and host-transient
runtime state, provided their values do not directly determine durable writes.

## Problem Statement

The current specs already make the hard architectural move: a frame has one
coherent frame-state value with app-db and runtime-db partitions, frame identity
is carried on causal tokens, effects are data at the boundary, and restore
installs frame-state as a value. The remaining nondeterminism comes from host
facts that are not carried on those tokens.

Examples:

- a todo handler calls `random-uuid` while constructing an app-db entity id;
- a resource reply handler calls `now-ms` while writing `:loaded-at`;
- a work-ledger writer calls `now-ms` while writing `:started-at` and
  `:deadline-at`;
- a mutation handler calls `now-ms` while writing `:started-at` or
  `:settled-at` on a mutation instance;
- a route handler reads `js/location` while writing routing state;
- a boot handler reads `localStorage` while writing session state;
- a machine action calls `rand-nth` while choosing a durable branch.

Each example folds an implicit host read into a value that later claims to be
replayable, hydratable, restorable, or test-fixture stable. Stubbing the host
clock or RNG in tests reduces flakes, but it does not make the causal record
complete. The event log still fails to explain why the state value has those
timestamps, ids, locations, and random choices.

These are not hypothetical. The reference implementation does this today: the
resource event reducers mint `:loaded-at`, `:stale-at`, and `:invalidated-at`
from ambient host-clock reads inside the transition; mutation event reducers
stamp `:started-at`, `:settled-at`, and patch/populate resource `:loaded-at`
the same way; and epoch record assembly stamps `:committed-at` from
`interop/now-ms`. All are durable facts that ride restore and replay.

## Motivation

The payoff for this rule is larger than timestamp hygiene. It protects the
central re-frame2 promise: application behavior is inspectable as data.

- Replay: the same initial frame-state plus the same causal tokens produces the
  same durable frame-state.
- Restore: installing a prior frame-state does not silently reinterpret old
  timestamps against a new ambient clock.
- SSR and hydration: server-stamped cache freshness is explicit data, and clock
  skew is a diagnostic, not a hidden state mutation.
- Resources, mutations, and work ledgers: `:started-at`, `:deadline-at`,
  `:loaded-at`, `:stale-at`, `:invalidated-at`, and `:settled-at` come from
  named causal times.
- Tests and stories: fixtures supply the exact host facts they need, without
  monkey-patching process-global host functions.
- Tooling: Xray, pair tools, conformance fixtures, and AI agents can explain
  "where did this state value come from?" without reverse-engineering host
  reads.

This is the same design posture as explicit frame targeting. A missing frame
stamp used to be repaired from ambient context; EP-0002 made absence loud, and
its ruling R6 made replay determinism the framework's decisive rationale. A
missing world fact should follow the same principle: durable state should not be
repaired by silently asking the host again.

## Goals

- State one rule for time, randomness, UUIDs, browser facts, storage reads, and
  asynchronous completion metadata.
- Make replay determinism a property of the event/reply/restore token stream,
  not of the current host clock or RNG.
- Define one canonical envelope/coeffect location for replayable host facts.
- Give resource caches, work-ledger rows, route state, machine snapshots, and
  app-db handlers the same timestamp source.
- Preserve ordinary re-frame ergonomics for code that does not need world
  inputs.
- Preserve ambient reads for diagnostics, performance measurement, scheduling,
  effect interpretation, and host-transient side tables.
- Make violations lintable and conformance-testable.
- Provide a migration path from re-frame v1 and early re-frame2 code that uses
  direct `Date.now`, `rand`, `random-uuid`, or ad hoc coeffects.

## Non-Goals

- This EP does not make host clocks trustworthy, synchronized, or monotonic.
- This EP does not define a complete time-travel debugger storage format.
- This EP does not require every trace timestamp, performance duration, or
  diagnostic span to be replayable.
- This EP does not forbid timers, promises, AbortControllers, browser APIs, or
  effect handlers from touching the host.
- This EP does not make views active fetchers or change the "events cause, views
  read" rule.
- This EP does not finalize the full uniform async reply envelope. It only
  requires that any reply token capable of durable writes carries the relevant
  completion world facts.
- This EP does not standardize every possible browser fact schema. It sets the
  boundary and the recordability rules; concrete subsystems add narrower schemas
  as they graduate.

## Relationships

- **EP-0001 (frame partitions)** defines the coherent frame-state value — the
  app-db and runtime-db partitions — whose durable writes this rule governs,
  and the epoch restore semantics this rule keeps honest.
- **EP-0002 (frame target resolution)** is the precedent. Its dispatch envelope
  is the causal token this EP extends, and its ruling R6 made replay
  determinism the framework's decisive rationale. This EP closes the remaining
  ambient gap in that claim: the frame stamp made *where* explicit; the world
  inputs make *when* and *what the host said* explicit.
- **EP-0003 (resource queries)** is the first large consumer: resource entries,
  mutation instance rows, and work-ledger rows carry durable `:loaded-at` /
  `:stale-at` / `:started-at` / `:deadline-at` / `:settled-at` facts.
  EP-0003's §Restore and Replay defines what restore does to those values (lazy
  freshness, no refetch storm, dangling rows); this EP removes the root cause
  that section works around — durable timestamps minted from an ambient clock
  whose meaning shifts under restore.
- **EP-0006 (runtime-subsystem contract)** owns the durable / host-transient
  grading this EP's classes build on, including the rf2-oosjmh ruling that
  host-side monotonic allocator counters never rewind.
- **EP-0008 (production observability channels)** defines the channel split
  this EP relies on: causal facts are data and replayable, diagnostic facts are
  ambient by design, and the always-on error axis reports production failures
  without becoming durable state. Diagnostic timestamps stay ambient under this
  EP.
- **EP-0011 (uniform async reply envelope)** defines the reply shape that
  carries this EP's completion world facts. This EP requires the facts;
  EP-0011 standardizes the carrier and uses this EP's suffixless durable
  timestamp vocabulary (`:started-at`, `:completed-at`, and `:deadline-at`).
- **EP-0012 (path optics and canonical forms)** supplies the canonical-form
  vocabulary world-input maps should reuse wherever fixture equality or
  projection comparison matters.

## Definitions

**Frame-state** is the coherent value:

```clojure
{:rf.db/app     <app-db>
 :rf.db/runtime <runtime-db>}
```

**Durable state** is any value that rides in frame-state, epoch records, SSR or
hydration projection, replay logs, or restore snapshots. It includes app-db,
runtime-db, resource entries, work-ledger rows, durable routing state, machine
snapshots, mutation instance rows, epoch records, and future replayable
partitions.

**Diagnostic state** is observational data whose absence or variation does not
change the durable frame-state. Examples include dev-only trace timestamps,
handler elapsed time, performance spans, console logs, local Xray panels, and
always-on error metadata that reports a failure without participating in the
transition result.

**Host-transient state** is runtime state outside replayable frame-state:
AbortControllers, JS timer handles, promises, DOM nodes, event listener handles,
transport sockets, cache cells, subscription machinery, stale/GC timer handles,
and monotonic high-water allocators whose values protect live host
continuations. Host-transient state may be frame-scoped and teardown-governed,
but it is not restored by installing a prior frame-state value.

**World input** is a host fact not determined by prior frame-state and the
current causal token. Examples include wall-clock time, monotonic time,
randomness, generated UUIDs, browser location, visibility, online status,
storage reads, media-query facts, network response receipt time, and host API
results.

**Causal token** is a value folded by the runtime: a dispatch envelope, a
managed-effect reply envelope, a route/navigation token, a timer-fire token, an
SSR/hydration token, a restore token, or a test/replay fixture token.

**Recordable coeffect** is a coeffect whose value may affect durable state and
is therefore captured in the causal token's replay record. On replay, restore,
or fixture execution, the coeffect returns the captured value rather than
re-reading the host.

**Ambient read** is a direct read of host state from transition code, such as
`js/Date.now`, `interop/now-ms`, `rand`, `random-uuid`,
`js/crypto.getRandomValues`, `js/location`, `localStorage`, or `navigator`.

## Proposed Solution

The dispatch envelope gains a canonical world-input field:

```clojure
{:event [:todo/create {:text "Write EP"}]
 :frame :main
 :source :ui
 :rf.world/inputs
 {:time-ms 1781078400123}}
```

The runtime exposes the same value to event handlers and internal reducers as a
framework coeffect under `:rf.world/inputs`:

```clojure
{:coeffects
 {:db              <app-db>
  :event           [:todo/create {:text "Write EP"}]
  :rf.db/runtime   <runtime-db>
  :rf.frame/id     :main
  :rf.world/inputs {:time-ms 1781078400123}}
 :effects {}}
```

The rule is:

> If a world input can affect a durable write, the transition MUST read that
> world input from a causal token or from a recordable coeffect captured in that
> token. It MUST NOT read the host ambiently at the durable write site.

`:rf.world/inputs` is an EDN map. It MUST be serializable after applying the
same projection, elision, and privacy rules as other replayable event data. It
has one required key:

- `:time-ms`: wall-clock epoch milliseconds, stamped when the token enters the
  fold unless the caller supplied it.

It may carry additional keys as needed:

- `:monotonic-ms`: a monotonic host time for elapsed-time calculations that must
  be replayed as values;
- `:uuid`: a map of domain names to generated UUIDs or other generated ids;
- `:random`: generated random choices, or seeds only when the algorithm is named
  and stable;
- `:browser/location`, `:browser/visibility`, `:browser/online?`, and similar
  normalized browser facts;
- `:storage`: normalized storage values read at the causal boundary;
- subsystem-qualified keys such as `:rf.http/completed-at` or
  `:rf.route/location` when a narrower spec defines them.

The existing optional envelope field `:dispatched-at` MUST NOT be the durable
causal-time contract. This fork is ruled (Open Issues, disposition 5):
`:dispatched-at` is **retired in the same change that lands the envelope
stamp** — no coexistence window — with the standard retirement treatment, a
hard error naming `(:time-ms (:rf.world/inputs envelope))` as the replacement.
New durable code reads `:rf.world/inputs`.

## Specification

### The World-Input Rule

A transition that performs a durable write MUST be deterministic with respect to
prior frame-state and the causal token being folded.

Therefore:

1. A world input that affects app-db MUST be present on the event envelope,
   event payload, or a recordable coeffect captured by that envelope.
2. A world input that affects runtime-db MUST be present on the event envelope,
   internal framework token, reply token, restore token, hydration token, or a
   recordable coeffect captured by that token.
3. A world input that affects an epoch record or replay fixture MUST be recorded
   in the causal record for that epoch.
4. A world input that affects a command but not durable state MAY be read by the
   effect interpreter, provided any later reply that does affect durable state
   carries the relevant completion facts.
5. A diagnostic or host-transient ambient read MUST NOT be used as a back door
   to decide durable state.

The rule applies to application handlers and framework internals.

### Durable, Diagnostic, And Host-Transient Classes

Code that reads a host fact MUST classify the read into one of three classes.

Durable:

- writes `:db`, `:rf.db/runtime`, resource entries, work-ledger rows, machine
  snapshots, routing runtime, epoch snapshots, or hydration payloads;
- must read world facts from the causal token or recordable coeffect;
- must be replay-testable.

Diagnostic:

- writes trace rows, performance spans, local devtool views, logs, or
  always-on error records that do not change frame-state;
- may read ambient time or host facts;
- must not be consulted by transition code to choose durable writes.

Host-transient:

- manages timers, AbortControllers, sockets, promises, DOM/listener handles,
  caches, or high-water allocators outside frame-state;
- may read ambient host facts;
- must be cleared, reconciled, or recomputed on frame destroy, restore, and
  hydration according to the owning runtime-subsystem contract;
- must dispatch a causal token if its later result will affect durable state.

### Dispatch Envelope Stamping

When a dispatch envelope is built, the router MUST ensure that
`:rf.world/inputs` exists and contains `:time-ms`.

If the caller supplies `:rf.world/inputs`, the router MUST preserve it and fill
only missing framework-required keys. This is how tests, replay, SSR hydration,
and host integrations provide exact world facts.

If the caller omits `:rf.world/inputs`, the router stamps:

```clojure
:rf.world/inputs {:time-ms (interop/epoch-now-ms)}
```

The wall-clock epoch-ms read (`interop/epoch-now-ms` — `js/Date.now()` /
`System/currentTimeMillis`, **not** the origin-relative `interop/now-ms` /
`performance.now()`) happens at the causal boundary. It is not repeated inside
the handler, flow transform, resource reducer, work-ledger writer, or commit
path. The durable timestamp must be wall-clock epoch ms so it stays comparable
with `js/Date`-based freshness checks.

Child dispatches produced by `:dispatch` or `:dispatch-later` get their own
world-input map. They MUST NOT inherit the parent's `:time-ms`, because they are
distinct causal tokens. They MAY inherit recordable test fixture overrides when
the test harness explicitly asks for a pre-scripted event log.

Timer-fire events, HTTP replies, router events, machine timer events, SSR
hydration events, and tool-issued events are all dispatch envelopes for this
purpose. Each stamps or supplies its own `:rf.world/inputs`.

### Event Context And Coeffects

The initial event context MUST include `:rf.world/inputs` as a framework
coeffect alongside `:db`, `:event`, `:rf.db/runtime`, and `:rf.frame/id`.

Handlers may read it directly:

```clojure
(rf/reg-event-fx
  :todo/create
  (fn [{:keys [db] :rf.world/keys [inputs]}
       [_ {:keys [text]}]]
    (let [todo-id (get-in inputs [:uuid :todo/id])
          time-ms (:time-ms inputs)]
      {:db (assoc-in db [:todos todo-id]
                     {:id todo-id
                      :text text
                      :created-at time-ms})})))
```

Standard coeffect injection remains useful, but a coeffect that can affect
durable state MUST be recordable:

- it has a stable coeffect id;
- its result is EDN-serializable or projected into EDN;
- it declares a schema where practical;
- the replay record captures the value;
- replay returns the captured value instead of re-reading the host.

A coeffect that only measures diagnostics may remain ambient and unrecorded.

### Time

`:time-ms` is the canonical event time for durable wall-clock facts. It is an
epoch-millisecond number unless a later accepted spec replaces it with a
structured clock value.

Use `:time-ms` for durable fields such as:

- app entity `:created-at` / `:updated-at`;
- resource `:loaded-at`, `:stale-at`, `:invalidated-at`;
- work-ledger `:started-at`, `:deadline-at`, `:completed-at`;
- mutation instance `:started-at` and `:settled-at`;
- durable routing timestamps;
- machine snapshot timestamps;
- epoch record causal time.

Ambient time remains allowed for:

- dev-only trace and performance elapsed values;
- host timer scheduling;
- request timeout measurement by the effect interpreter;
- deciding when to wake a host-transient sweep, provided the sweep dispatches a
  causal token before durable writes;
- diagnostics about server/client clock skew.

### Randomness, UUIDs, And Generated Identity

If a generated value becomes durable identity or durable state, it is a world
input.

Generated IDs should be supplied on the causal token under a domain-specific
slot:

```clojure
(rf/dispatch
  [:todo/create {:text text}]
  {:rf.world/inputs
   {:time-ms 1781078400123
    :uuid {:todo/id #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"}}})
```

Random choices should generally record the chosen value, not merely a seed,
because host RNG algorithms and collection ordering are not portable contracts.
A seed is acceptable only when the algorithm and input order are named and
stable.

**Secrets are not world facts (ruled rider, 2026-06-11).** Crypto-grade
randomness — session tokens, keys, nonces, anything whose value must remain
confidential — MUST NOT flow through recordable world inputs. A recorded
choice would *be* the secret, durably embedded in the causal record itself
(epoch history, replay fixtures), inside the boundary that egress redaction
protects only at the edges. Secrets are generated in effects on the host side;
only derived or server-issued facts — never the raw secret material — may
become durable frame-state.

Host-transient randomness remains ambient. Animation jitter, decorative
particle positions, local retry jitter that is not written durably, and
performance sampling may use host RNG directly.

### Browser And Storage Facts

Browser facts that affect durable state MUST be normalized and carried on a
causal token.

Examples:

- URL/location facts that update routing state arrive on a router event.
- `visibilitychange` and `online` facts that cause resource revalidation arrive
  on explicit events with payloads.
- localStorage/sessionStorage reads that initialize app state arrive on boot or
  restore tokens.
- media-query facts that affect durable layout preferences arrive on events or
  recordable coeffects.

Browser host objects MUST NOT be stored as world inputs. Tokens carry EDN data:
strings, booleans, numbers, keywords, vectors, maps, and projected ids.

### Managed Effects And Reply Tokens

Effect handlers are allowed to touch the host. They are the boundary that turns
effect data into external work. The rule applies when their outcome returns to
the frame fold.

A managed asynchronous effect that can complete with durable writes MUST dispatch
a reply token carrying:

- the target frame stamp;
- the originating work id or correlation id;
- stale-suppression identity such as generation or nav-token;
- outcome status;
- the decoded value or projected error;
- completion world facts, especially completion time.

An illustrative reply token:

```clojure
[:rf.resource.internal/succeeded
 {:work/id [:rf.work/resource scoped-resource-key 4]
  :resource-key scoped-resource-key
  :generation 4
  :rf.frame/id frame-id
  :reply
  {:status :ok
   :value article
   :completed-at 1781078400456}}]
```

EP-0011 standardizes the outer reply shape and uses the same suffixless durable
timestamp keys. This EP's requirement is narrower: completion facts that affect
durable state are carried on the reply token and are not re-read in the reply
handler.

The completion token may be stamped from `:rf.world/inputs` at the host boundary,
but the standardized reply map exposes the completed-time fact as
`:completed-at`. It should not also expose the same fact as
`[:rf.world/inputs :time-ms]` inside the reply map.

### Resources, Mutations, And Work-Ledger Timestamps

Resource, mutation, and work-ledger timestamps are durable runtime-db facts.
They MUST come from causal world inputs.

On ensure/refetch:

- `:started-at` is the triggering token's `:time-ms`;
- `:deadline-at` is computed from `:started-at` plus the configured timeout or
  deadline policy;
- any `:invalidated-at` written by an invalidation event is that event's
  `:time-ms`.

On success/failure:

- `:completed-at` is read from the reply token;
- resource `:loaded-at` is the successful reply's completion time;
- resource `:stale-at` is computed from `:loaded-at` plus `:stale-after-ms`;
- terminal work-ledger outcome timestamps come from the same reply completion
  metadata.

Mutation instance rows follow the same rule. `:rf.mutation/execute` writes
`:started-at` from the triggering token's `:time-ms`; terminal mutation replies
write `:settled-at` from the reply completion time; and any resource
patch/populate timestamp produced by the mutation uses that same causal
completion time.

Stale and GC timers are advisory host transients. Timer wake-up handlers MUST
re-read the durable entry and generation, and any durable write they perform
uses the timer-fire event's own `:time-ms`.

### Restore, Replay, And Hydration

Replay evaluates a sequence of causal tokens against an initial frame-state.
For durable state, a conforming implementation MUST satisfy:

```text
replay(initial-state, tokens) == replay(initial-state, tokens)
```

where the two evaluations may run at different wall-clock times, on different
hosts, with different ambient clocks and RNGs. Equality covers app-db,
runtime-db, and replayable epoch projections, and excludes diagnostic and
host-transient state.

Restore installs a durable frame-state value. It MUST NOT re-read ambient world
facts to "freshen" that state during install. It may emit diagnostics about
clock skew or implausible timestamps, but those diagnostics do not change the
installed value.

After restore:

- resource entries keep their restored `:loaded-at`, `:stale-at`, and
  `:invalidated-at` values;
- non-terminal work rows whose host handles belonged to the prior timeline are
  reconciled as dangling according to the resource/work-ledger contract;
- host-transient handles are cleared or recomputed on demand;
- host-side monotonic allocators (generation counters, work-id high-water
  marks) are not rewound. Per the rf2-oosjmh ruling carried in EP-0006's
  grading table and EP-0003's §Restore and Replay, an allocator whose
  identities can be carried by an uncancellable host continuation only ever
  moves forward. Such allocators are host-transient under this EP's
  classification: replay does not reproduce them, and restore does not
  reinstall them, because their job is to fence the live host timeline, not to
  describe durable state;
- the next live event or reply carries its own `:rf.world/inputs`, and freshness
  decisions are made lazily from that token plus durable timestamps.

Hydration follows the same principle. Server-provided durable timestamps are
data. Client-side clock skew is diagnostic unless a subsequent client causal
token writes new durable state.

### Privacy And Projection

World inputs can contain sensitive data: user ids, tenant ids, URLs, query
strings, storage values, locale, permission facts, and browser state. They
therefore participate in the same schema, redaction, elision, and projection
rules as event payloads, coeffects, resource params, scopes, and errors.

Conformance fixtures should prefer small, explicit values. Tooling should show
summaries and redacted projections by default.

## Examples

### Incorrect Ambient Time In An App Handler

This handler writes a durable timestamp from an ambient clock read:

```clojure
(rf/reg-event-fx
  :article/load-succeeded
  (fn [{:keys [db]} [_ {:keys [id article]}]]
    {:db (assoc-in db [:articles id]
                   {:body article
                    :loaded-at (interop/now-ms)})}))
```

Replaying the same event later can produce a different app-db value.

### Correct Envelope Time In An App Handler

The timestamp is supplied by the causal token:

```clojure
(rf/dispatch
  [:article/load-succeeded {:id 42 :article article}]
  {:rf.world/inputs {:time-ms 1781078400123}})

(rf/reg-event-fx
  :article/load-succeeded
  (fn [{:keys [db] :rf.world/keys [inputs]}
       [_ {:keys [id article]}]]
    {:db (assoc-in db [:articles id]
                   {:body article
                    :loaded-at (:time-ms inputs)})}))
```

In live code the router stamps `:time-ms`; tests and replay fixtures supply it.

### Incorrect Ambient UUID And Randomness

Both the id and color become durable state:

```clojure
(rf/reg-event-fx
  :todo/create
  (fn [{:keys [db]} [_ {:keys [text]}]]
    (let [id    (random-uuid)
          color (rand-nth [:red :green :blue])]
      {:db (assoc-in db [:todos id]
                     {:id id
                      :text text
                      :color color})})))
```

### Correct Generated Values From The Token

The host or test fixture chooses the values before dispatch:

```clojure
(rf/dispatch
  [:todo/create {:text "Pay invoice"}]
  {:rf.world/inputs
   {:time-ms 1781078400123
    :uuid   {:todo/id #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"}
    :random {:todo/color :green}}})

(rf/reg-event-fx
  :todo/create
  (fn [{:keys [db] :rf.world/keys [inputs]}
       [_ {:keys [text]}]]
    (let [id    (get-in inputs [:uuid :todo/id])
          color (get-in inputs [:random :todo/color])]
      {:db (assoc-in db [:todos id]
                     {:id id
                      :text text
                      :color color
                      :created-at (:time-ms inputs)})})))
```

The replay log explains every durable value.

### Incorrect Ambient Browser Location

This handler writes route state from whatever the browser says at handler time:

```clojure
(rf/reg-event-fx
  :route/sync-from-browser
  (fn [{:keys [db]} _]
    {:db (assoc db :route/current
                {:path (.-pathname js/location)
                 :query (.-search js/location)})}))
```

The event vector does not explain which URL was folded.

### Correct Router Event Payload

The host adapter reads the browser at the boundary and dispatches a causal
token:

```clojure
(rf/dispatch
  [:route/location-changed
   {:location {:path "/articles/welcome"
               :query "?preview=true"}}]
  {:source :router
   :rf.world/inputs {:time-ms 1781078400123}})

(rf/reg-event-fx
  :route/location-changed
  (fn [{:keys [db] :rf.world/keys [inputs]}
       [_ {:keys [location]}]]
    {:db (assoc db :route/current
                (assoc location :observed-at (:time-ms inputs)))}))
```

The same pattern applies to `visibilitychange`, `online`, `storage`, and
media-query changes.

### Resource Ensure And Work-Ledger Start

Incorrect:

```clojure
(defn start-resource-work [runtime-db work-id timeout-ms]
  (let [started-at (interop/now-ms)]
    (assoc-in runtime-db [:rf.runtime/work-ledger work-id]
              {:work/id work-id
               :status :running
               :started-at started-at
               :deadline-at (+ started-at timeout-ms)})))
```

Correct:

```clojure
(defn start-resource-work [runtime-db work-id timeout-ms world]
  (let [started-at (:time-ms world)]
    (assoc-in runtime-db [:rf.runtime/work-ledger work-id]
              {:work/id work-id
               :status :running
               :started-at started-at
               :deadline-at (+ started-at timeout-ms)})))

(rf/reg-event-fx
  :rf.resource/ensure
  (fn [{runtime :rf.db/runtime :rf.world/keys [inputs]}
       [_ {:keys [work-id timeout-ms]}]]
    {:rf.db/runtime
     (start-resource-work runtime work-id timeout-ms inputs)}))
```

The ledger row's start and deadline are replayable runtime-db facts.

### Resource Reply Completion

Incorrect:

```clojure
(rf/reg-event-fx
  :rf.resource.internal/succeeded
  (fn [{runtime :rf.db/runtime}
       [_ {:keys [work-id resource-key value]}]]
    (let [loaded-at (interop/now-ms)]
      {:rf.db/runtime
       (-> runtime
           (assoc-in [:rf.runtime/work-ledger work-id :status] :completed)
           (assoc-in [:rf.runtime/resources :entries resource-key :data] value)
           (assoc-in [:rf.runtime/resources :entries resource-key :loaded-at]
                     loaded-at))})))
```

Correct:

```clojure
(rf/reg-event-fx
  :rf.resource.internal/succeeded
  (fn [{runtime :rf.db/runtime}
       [_ {:keys [work-id resource-key value reply]}]]
    (let [completed-at (:completed-at reply)
          stale-at     (+ completed-at (* 5 60 1000))]
      {:rf.db/runtime
       (-> runtime
           (assoc-in [:rf.runtime/work-ledger work-id :status] :completed)
           (assoc-in [:rf.runtime/work-ledger work-id :completed-at]
                     completed-at)
           (assoc-in [:rf.runtime/resources :entries resource-key :data] value)
           (assoc-in [:rf.runtime/resources :entries resource-key :loaded-at]
                     completed-at)
           (assoc-in [:rf.runtime/resources :entries resource-key :stale-at]
                     stale-at))})))
```

The effect handler or HTTP adapter may use host APIs while doing the request.
The durable completion facts are carried by the reply token.

### Replay Fixture

A replay fixture supplies the exact world inputs:

```clojure
{:initial-frame-state
 {:rf.db/app {}
  :rf.db/runtime {}}

 :tokens
 [{:event [:todo/create {:text "One"}]
   :frame :test
   :source :test
   :rf.world/inputs
   {:time-ms 1781078400000
    :uuid {:todo/id #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"}}}

  {:event [:todo/create {:text "Two"}]
   :frame :test
   :source :test
   :rf.world/inputs
   {:time-ms 1781078401000
    :uuid {:todo/id #uuid "018ff2b4-9bc0-79c9-9b3a-1cba3524f76d"}}}]}
```

The conformance expectation is that replaying this fixture twice produces equal
app-db and runtime-db, even when the host clock and RNG differ.

## Rationale

The event envelope already carries frame identity, source, origin, trace fields,
and effect override data. It is the right causal boundary for host facts too.
Putting world inputs there keeps the fold simple: transitions consume values,
and effect interpreters produce future values.

This EP deliberately follows EP-0008's causal/diagnostic split. A trace
timestamp answers "when did the runtime observe this?" A durable timestamp
answers "what value did the application fold?" Those are different facts. The
runtime can record both, but it must not let diagnostic convenience become
durable causality.

The proposal also keeps re-frame's coeffect idea. A coeffect is still the
handler's way to ask for input. The change is that a state-affecting coeffect is
recordable. It has replay semantics instead of being an untracked service
locator for the host.

The `:rf.world/inputs` map is intentionally boring EDN. It does not require a
new time abstraction, RNG abstraction, UUID service, browser service, or effect
monad before the rule becomes useful. Subsystems can add narrower schemas when
they need them, but the boundary is stable now.

Pre-alpha is the right time to make this rule strict. Mechanical upgrade from
v1 remains desirable, but compatibility with ambient host reads should not
override a deterministic fold for large SPAs.

## Alternatives Considered

### Keep Ambient Reads And Stub The Host In Tests

This is the re-frame v1 habit: inject or monkey-patch the clock/RNG in tests.
It reduces nondeterministic tests, but the event log still lacks the facts that
produced durable state. Replay, restore, SSR hydration, and post-mortem tooling
remain under-explained.

### Promote Existing `:dispatched-at`

Spec 002 currently describes `:dispatched-at` as optional envelope metadata, and
the CLJS reference treats it as dev-only. Promoting it would reuse a name, but
it would also blur diagnostic dispatch timing with causal world input. This EP
instead defines `:rf.world/inputs` and lets `:dispatched-at` retire or become a
diagnostic alias.

### Store A Global Clock In Runtime-DB

A runtime clock would make time available as state, but it creates a new
ambient dependency: transitions would depend on whatever event last updated the
clock. It also forces unrelated events to churn runtime-db. Event time belongs
to the causal token that is being folded.

### Derive Freshness From The Live Clock On Read

Current Spec 016 resource subscriptions compare `:stale-at` to the live host
clock during read. Keeping that as the long-term freshness contract would make
passive reads time-dependent and view-driven, weakening the "events cause,
views read" rule. Freshness transitions should be caused by events; reads may
project whether a durable timestamp is stale relative to a supplied causal check.

### Forbid All Ambient Reads

This is too broad. Diagnostics, performance spans, scheduling, effect
interpretation, host handles, and devtools need ambient host reads. The rule is
not "no host." The rule is "no hidden host facts in durable writes."

### App Convention Only

Documenting "please do not call `now-ms` in handlers" is insufficient. The rule
must be visible in specs, fixture schemas, lint, and conformance so framework
internals and application code follow the same boundary.

## Backwards Compatibility and Migration

This is a semantic tightening, not a wholesale API break.

Existing event handlers continue to receive `:db` and `:event`. Handlers that do
not need world inputs require no changes. Handlers that currently call the host
while writing durable state migrate mechanically:

- replace `interop/now-ms`, `js/Date.now`, or `(.now js/Date)` with
  `(:time-ms (:rf.world/inputs cofx))`;
- replace `random-uuid` or host UUID calls with ids supplied in the event
  payload or `:rf.world/inputs`;
- replace `rand`, `rand-int`, and `rand-nth` with supplied random choices or
  named deterministic seeds;
- replace direct `js/location`, `navigator`, or storage reads with router/host
  events or recordable coeffects;
- move host API calls in effect handlers to the boundary, and make the reply
  event carry completion facts.

v1-style `inject-cofx :now` remains a recognizable source form, but if the
result affects durable state it must become recordable. A compatibility
coeffect can first read `:rf.world/inputs` and only fall back to the host when
building a new live token:

```clojure
(rf/reg-cofx
  :app/now-ms
  (fn [ctx]
    (let [world (get-in ctx [:coeffects :rf.world/inputs])]
      (assoc-in ctx [:coeffects :app/now-ms] (:time-ms world)))))
```

Early migrations can keep custom cofx names while moving their source from the
host to the envelope.

`dispatch` and `dispatch-sync` should accept `:rf.world/inputs` in their opts
map. Unknown-opt diagnostics should be updated so this key is recognized.

The existing dev-only `:dispatched-at` field is not a compatibility surface for
durable code. A migration may temporarily populate both fields from the same
boundary read while consumers are updated.

## Reference Implementation / Bead Plan

1. Extend the dispatch-opts and dispatch-envelope schemas with
   `:rf.world/inputs`.
2. Stamp `:rf.world/inputs {:time-ms ...}` at envelope construction when absent,
   preserving caller-supplied values for replay, tests, SSR, and tools.
3. Inject `:rf.world/inputs` into the initial event context as a framework
   coeffect and filter it from the user-cofx trace projection the same way
   `:db`, `:event`, `:rf.db/runtime`, and `:rf.frame/id` are filtered.
4. Update `:dispatch`, `:dispatch-later`, machine timers, routing, HTTP replies,
   SSR hydration, and tool dispatch helpers so every causal token has world
   inputs.
5. Audit durable writes in app handler examples, resources, mutations,
   work-ledger rows, routing, machines, restore, hydration, epochs, and
   conformance fixtures.
6. Convert resource, mutation, and work-ledger timestamps to token/reply world
   inputs.
7. Add recordable-coeffect guidance and initial helpers for common time,
   UUID/random, browser, and storage inputs.
8. Add replay fixtures that supply times, UUIDs, random choices, browser facts,
   and reply completion metadata.
9. Add lint/conformance guards for ambient durable-world reads with documented
   diagnostic and host-transient allowlists.
10. Update migration docs after the normative specs graduate.

## Validation / Conformance

A conforming implementation SHOULD provide static and runtime checks.

Static lint should flag direct ambient reads in code paths that can write
durable frame-state:

- `interop/now-ms`, `js/Date.now`, `(.now js/Date)`;
- `rand`, `rand-int`, `rand-nth`, `random-uuid`,
  `js/crypto.getRandomValues`;
- direct `js/location`, `navigator`, `localStorage`, `sessionStorage`, and
  media-query reads;
- ambient reads inside resource reducers, work-ledger writers, reply handlers,
  mutation handlers, restore/hydration installers, and machine snapshot writers.

The lint may be conservative. It should allowlist:

- trace and performance measurement code;
- effect interpreters before they dispatch a reply token;
- timer scheduling and cancellation;
- host-transient side-table maintenance;
- diagnostics that do not influence durable writes.

Conformance fixtures should cover:

- replaying the same event log with supplied `:time-ms` values produces equal
  app-db and runtime-db;
- resource and work-ledger timestamps (`:started-at`, `:deadline-at`,
  `:loaded-at`, `:stale-at`, and `:invalidated-at`) are preserved across
  replay;
- mutation instance `:started-at` and `:settled-at` are preserved across replay;
- reply handlers use completion metadata from reply tokens;
- restored resource entries do not re-read the live clock during install;
- ambient diagnostic timestamps may differ without changing durable state;
- random/UUID values supplied by fixtures become durable ids exactly as
  supplied;
- browser location/storage facts that affect state appear in event payloads,
  `:rf.world/inputs`, or recordable coeffects.

The strongest property is:

```text
Given the same initial frame-state and the same causal token sequence,
all durable projections are equal after replay.
```

## Open Issues

**All five issues were ruled 2026-06-11 (Mike, bead `rf2-jm6tlv`): every
disposition is "as recommended", with three riders recorded inline below.** The
original recommendations are kept verbatim as the record of what was ruled.

1. Should `:time-ms` remain a plain epoch-millisecond number, or should a future
   accepted spec replace it with a structured clock value carrying wall-clock and
   monotonic components? **Recommendation:** keep the plain number. The optional
   `:monotonic-ms` slot already covers the elapsed-time case, and a structured
   clock could graduate later without breaking `:time-ms` readers.
   **Disposition: as recommended.**
2. Which framework-provided recordable coeffects should ship first:
   `:rf.world/uuid`, `:rf.world/random`, `:rf.world/browser-location`,
   `:rf.world/storage`, or only the core time coeffect?
   **Recommendation:** ship only the core time path first (the envelope stamp
   plus the compatibility cofx). `:rf.world/uuid` and `:rf.world/random` follow
   when an example app or conformance fixture needs them; browser and storage
   inputs stay app-level recipes until a subsystem graduates a schema.
   **Disposition: as recommended, with the deferral trigger named (rider):**
   the expected first `:rf.world/uuid` consumer is the optimistic-mutations
   follow-on EP (temp ids for optimistic inserts — the managed-HTTP RealWorld
   example's optimistic comment flow is the visible in-repo case).
3. Should random support standardize a deterministic seed algorithm, or should
   the framework recommend recording generated choices rather than seeds?
   **Recommendation:** recommend recording generated choices; treat seeds as the
   exception reserved for named, stable algorithms. Do not standardize a
   framework RNG now.
   **Disposition: as recommended, plus the secrets-exclusion boundary (rider):**
   crypto-grade randomness (session tokens, keys, nonces) is excluded from
   recordable world inputs entirely — a recorded choice would *be* the secret,
   durably embedded in the causal record (epoch history, replay fixtures) where
   issue 4's egress redaction cannot protect it. Now normative in
   [§Randomness, UUIDs, And Generated Identity](#randomness-uuids-and-generated-identity).
4. How much of `:rf.world/inputs` should appear in production traces after
   privacy projection? **Recommendation:** `:time-ms` is always safe to surface;
   every other key follows the same marks/projection rules as event payloads and
   is redacted by default. **Disposition: as recommended.**
5. Should the migration retain `:dispatched-at` as a diagnostic alias, or retire
   it once `:rf.world/inputs` is universal? **Recommendation:** retire it. Two
   spellings of "when was this dispatched" violates one-name-per-fact (EP-0007),
   and the diagnostic need is already covered by the trace event's own `:time`
   stamp (Spec 009).
   **Disposition: retire, with the timing pinned (rider):** retirement happens
   **in the same change that lands the envelope stamp** — no coexistence
   window. The `rf2-cg7llv` epoch-naming ruling demonstrated that coexistence
   windows become permanent and the de-facto spelling wins; the window here is
   open precisely because `:rf.world/inputs` has not shipped. Standard
   retirement treatment: a hard error naming the replacement, never a silent
   alias.

## Guide Impact

On graduation, the implementation bead must update the guide's event/coeffect
and testing material (the `concepts/effects-and-coeffects.md` clock/coeffect
guidance and the testing how-tos) to teach
causal world inputs instead of ambient clock stubbing as the replay-safe path.
The guide should show `:rf.world/inputs` on dispatch/reply tokens, fixture
examples for time/UUID/random values, and the boundary where diagnostic
timestamps remain ambient under EP-0008.

## Recommendation

Adopt. Causal world inputs make the frame fold honest: durable state is a
function of prior frame-state plus explicit tokens. The rule is small, but it
settles a high-leverage boundary for resources, work ledgers, replay, restore,
SSR, tests, and future managed effects. It keeps ambient host access where it
belongs, and removes it from the places that claim to produce replayable values.

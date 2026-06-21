# EP-0027: Frame Initial Events

Status: draft
Type: standards-track

> **Scope note.** Deliberately small: a declarative way to write the setup events that
> tests and docs currently fire by hand after `make-frame`. Guiding rule —
> **`:initial-events` is no more capable than the loop it replaces** (no replay tapes, no
> snapshots, no atomic staging, no outcome capture). Not yet implemented; where the text
> refers to current behaviour it says "today".

## Abstract

This EP adds `:initial-events` — an ordered vector of events dispatched synchronously,
in order, into a frame during construction. It replaces the common hand-written setup
pattern:

```clojure
(let [frame (rf/make-frame {:id :checkout/story :images [checkout-story-image]})]
  (rf/dispatch-sync [:checkout/open]                  {:frame :checkout/story})
  (rf/dispatch-sync [:checkout/add-item "SKU-1"]      {:frame :checkout/story})
  (rf/dispatch-sync [:checkout/add-item "SKU-2"]      {:frame :checkout/story})
  (rf/dispatch-sync [:checkout/select-shipping :express] {:frame :checkout/story})
  frame)
```

with one declarative vector:

```clojure
(rf/make-frame {:id :checkout/story
                :images [checkout-story-image]
                :initial-events [[:checkout/open]
                                 [:checkout/add-item "SKU-1"]
                                 [:checkout/add-item "SKU-2"]
                                 [:checkout/select-shipping :express]]})
```

`:initial-events` **is** that loop, written as data. Construction is **events-only**:
there is no separate `:initial-db` data key — seeding app-db is itself an event,
`[:rf/set-db {…}]`. Both `:initial-db` and `:on-create` are retired.

## Motivation

The setup sequence after `make-frame` is not incidental glue — it is part of the
constructed instance. Written as N post-construction `dispatch-sync` calls it makes the
frame declaration incomplete, repeats `{:frame …}` on every line, and hides the scenario
in imperative code. Tests and docs do this constantly; one declarative vector is
shorter, reads as a scenario, and keeps the whole construction in one place.

Because every step is an ordinary event (including the db seed), the whole of
construction is visible in the trace/epoch stream — useful for Story and tooling — with
no special-cased direct write.

**Primary consumers are unit tests and docs.** Apps use it lightly (usually a single init
event). Story has the same *need* but its own richer lifecycle (loaders, `:rf.story/*`),
which this EP leaves alone (see §Out of scope). The design is sized for exactly that: a
thin, declarative front door, nothing more.

## Goals

- Add `:initial-events` to `reg-frame`, `make-frame`, and owned `frame-provider`.
- Retire `:on-create` and `:initial-db`; provide `[:rf/set-db {…}]` for app-db seeding.
- One unambiguous vector shape; an optional per-step `:rf.cofx` for test determinism.
- Run setup synchronously at construction, behaving like the hand-written loop.

## Non-Goals

This EP draws its scope tightly. It does **not**:

- change, or couple to, Story's setup grammar or lifecycle (see §Out of scope);
- specify an SSR request→steps lowering (see §Out of scope);
- change the app-db reset verbs (`reset-app-db!` / `replace-app-db!`) or any
  Tool-Pair / MCP / epoch surface;
- add setup-step labels;
- provide a compatibility shim (pre-alpha).

(The larger machinery weighed and dropped during design is recorded under §Rejected
alternatives.)

## Specification

### The `:rf/set-db` event

The framework registers one standard event for seeding app-db. (re-frame2 has a single
event form, `reg-event`; EP-0018 removed `reg-event-db` / `reg-event-fx`.)

```clojure
;; schematic — the real registration also validates its argument
(reg-event :rf/set-db (fn [_ [_ new-db]] {:db new-db}))
```

- It returns a `{:db new-db}` effect, so it replaces the **app-db partition only**
  (it cannot touch runtime-db) and rides the **normal post-commit app-db schema
  validation / rollback** like any `:db` effect.
- It **validates exactly one map argument**: a missing, `nil`, or non-map argument fails
  with `:rf.error/set-db-bad-value`. Set app-db empty with `[:rf/set-db {}]`.
- It **replaces all of app-db** (it is not a merge); for partial updates, write an
  ordinary event.
- It is **public and developer-friendly** — intended for tests, docs, and apps alike —
  and is registered as a **framework standard in both the regular registrar and the image
  standard registry** (so it resolves whether or not a frame's image generation is in
  scope, and every frame can dispatch it). Re-registering `:rf/set-db` in app code is a
  reserved-id collision and fails loudly.

It lives in the single-root `:rf/*` namespace; `:rf.db/*` stays reserved for partition
slots.

### `:initial-events`

`:initial-events` is an ordered vector of setup steps. Omitting it and supplying `[]`
both mean "no setup events." A **bare event vector is not a valid top-level value**:

```clojure
{:initial-events [[:rf/set-db {:n 0}]]}   ;; valid: a one-step vector
{:initial-events [:rf/set-db {:n 0}]}     ;; invalid: fails with a message that names the
                                          ;;          fix — wrap it as [[:rf/set-db {:n 0}]]
```

The single-step case pays one extra bracket; accepting "one event *or* a vector of
events" would reintroduce the `[:a :b]` ambiguity this shape avoids.

A step is a **bare event vector**, or a **map** for the case where a step needs dispatch
opts:

```clojure
[:checkout/open]
{:event [:todo/add "milk"] :opts {:rf.cofx {:rf/time-ms 1781078400123}}}
```

In the map form, `:event` is required and must be a non-empty event vector, and `:opts`
is **the ordinary `dispatch-sync` opts** — exactly what the hand-written loop passes — with
one restriction: **`:frame` is forced to the frame being constructed and may not be
supplied** (a `:frame` in `:opts` is rejected). Keeping the same opt surface as the loop is
what makes `:initial-events` no *less* capable than the code it replaces; the common case is
a deterministic clock for tests (`{:rf.cofx {:rf/time-ms …}}`), but any opt the loop could
pass to `dispatch-sync` (e.g. `:rf.cofx/mint-policy`) is allowed.

### Construction

Construction creates the frame (app-db starts `{}`), installs the frame's configuration
(interceptors, classification, overrides), then **dispatches each setup step
synchronously, in order**, draining each to a fixed point before the next — exactly as
the hand-written loop would. By the time `make-frame` returns, the synchronous setup has
settled. Asynchronous effects started by setup are not awaited.

Construction runs at **top level** (tests, boot, SSR per request — see §Out of scope) or
in the **view tree** (`frame-provider`). Constructing a frame **inside an event handler
is not supported** — frame creation is a view / top-level concern (a handler changes
app-db; the view materializes frames from it) — and fails loudly
(`:rf.error/frame-construction-in-handler`).

This is a **foundational Spec 002 change**, not just an `:initial-events` detail: today
Spec 002 (and the code) explicitly *allow* `make-frame` / `reg-frame` inside a handler by
queuing the creation event. Forbidding it states a principle — **handlers mutate app-db;
views and top-level materialize frames** — so graduating this EP requires that Spec 002
decision. It *removes* today's two-regime `:on-create` handling rather than adding
machinery.

### Failure

Setup failures fall into three categories, and none adds detection beyond what the
hand-written loop already gives:

- **Preflight validation** — an invalid `:initial-events` shape, an invalid step, a step
  `:opts` violation, or a supplied `:on-create` / `:initial-db`. Caught **before any step
  runs**; **throws** (`:rf.error/*`); no frame is left registered.
- **A setup step that throws at runtime** — construction aborts and the partially created
  frame is **torn down** (no half-created frame is left live); the error names the failing
  **step index** and event. A bad `[:rf/set-db x]` argument falls here: its diagnostic is
  raised through `error/throw-error!`, so it throws.
- **A setup step whose failure re-frame traces and recovers** — a missing handler, or a
  handler/interceptor error that `dispatch-sync` records as an error *trace* rather than
  re-throwing. The frame is **left alive in whatever state resulted**, exactly as after the
  manual loop; tests assert on that, as they do today.

### Provenance

Each setup step is dispatched with light **construction provenance**, so the trace and
tools can tell frame-init events apart from ordinary runtime events — the visibility this EP
is partly motivated by. A setup-step dispatch carries `:source :rf.frame/init` and its
**step index**. Source-code coordinates come for free: the frame already auto-captures
`:ns` / `:line` / `:file` at the `make-frame` / `reg-frame` call-site — which is where
`:initial-events` is declared — so a setup event attributes back to its declaration via the
frame. (Per-step source lines — a distinct line per element of the vector — are out of
scope; the call-site is enough to navigate back.) This is the one place `:initial-events`
does slightly more than the manual loop, whose dispatches carry no such tag — a small
`:source` addition, not a schema/conformance overhaul.

### Reset

`:initial-events` is **durable frame config** — stored on the frame the way `:on-create`
is today. Re-registering the frame **replaces** it (and **clears** it when the key is
absent); it is **not** replayed on re-registration.

`reset-frame!` today destroys the frame, re-registers it, and re-fires `:on-create`; under
this EP it re-dispatches the recorded `:initial-events` instead — the only thing that
replays the setup. The replay is a **best-effort re-run through the current handlers**, not
a snapshot or faithful reconstruction (a vector now, exactly as `:on-create` re-fired one
event today): no snapshot, no replay tape, no atomicity. (Because construction is
events-only, the script *is* the constructed state; there is no separate baseline to
restore.) The other app-db reset verbs are unchanged.

### Frame provider

Owned `frame-provider` accepts `:initial-events` alongside `:id` / `:images` and runs it
on the **first creation of a frame id** only. On a genuine **remount / re-acquire** under
the same id (React StrictMode, a true unmount→mount, Story re-eval) the setup is re-recorded
but **not** replayed, and durable state is preserved (idempotent re-registration); an
ordinary prop-change re-render does not re-call `make-frame` at all, so it neither
re-records nor replays. A step that throws during acquire **destroys the just-created frame,
then rethrows**. Because provider setup
runs during React render, keep it **effect-light** (seed app-db, light init), and drive
heavier side-effecting init from app-db state via the view. (Render-abort-after-acquire
remains a known pre-existing provider edge; it is not solved here.)

### Diagnostics

All ids live in the `:rf.error/*` family and are raised through `error/throw-error!`:

| id | raised when |
|---|---|
| `:rf.error/on-create-retired` | `:on-create` supplied to construction |
| `:rf.error/initial-db-retired` | `:initial-db` supplied to construction |
| `:rf.error/initial-events-bare-event` | top-level value is a bare event vector |
| `:rf.error/initial-events-bad-step` | a step is neither event-vector nor map |
| `:rf.error/initial-events-bad-event` | a map step's `:event` is missing / empty / non-vector |
| `:rf.error/initial-events-bad-opts` | `:opts` is not a map, or contains `:frame` |
| `:rf.error/initial-events-step-failed` | a setup step threw (carries `:step-index`, `:event`) |
| `:rf.error/frame-construction-in-handler` | construction attempted while a cascade is in flight |
| `:rf.error/set-db-bad-value` | `[:rf/set-db x]` with missing / `nil` / non-map `x` |

## Out of scope

- **Story** keeps its own richer lifecycle and tagged setup grammar; this EP neither
  changes nor couples to it. (Story may later choose to converge on `:initial-events`-
  style steps; that is not required here.)
- **SSR**: since `:on-create` is retired, a server that needs request-derived init computes
  its `:initial-events` **vector** per request — from the request — and passes it to a
  top-level `make-frame` (e.g. `[[:rf/set-db (seed req)] [:app/hydrate (route req)]]`).
  Server frames are ephemeral (built, rendered, discarded per request), so the recorded
  setup and `reset-frame!` do not apply server-side. A supplied `:on-create` raises
  `:rf.error/on-create-retired` here too. Streaming parity and hydrate ordering are
  `ssr-ring` adapter details to confirm against the current implementation; this EP
  specifies no further request→steps lowering.

## Rationale

- **Why a vector, not N dispatches.** The sequence is construction data; writing it as
  data makes the declaration complete, drops the repeated `{:frame …}`, and reads as a
  scenario — the actual point of the change.
- **Why events-only.** One concept (the event script) instead of two channels; the seed
  becomes an ordinary, traceable event; no special-cased direct write.
- **Why the strict `[[:e]]` shape.** Clarity over the single-event ergonomic; "one event
  or many" reintroduces the `[:a :b]` ambiguity.
- **Why no machinery.** The feature replaces a hand-written loop; it should be no more
  capable, no more clever, and no more failure-aware than that loop. Replay fidelity,
  snapshots, and atomic reset are real ideas, but out of proportion to this goal.
- **Why no handler-time construction.** Frames are created by the view or at top level;
  forbidding the handler case *removes* a regime rather than adding one.

## Rejected alternatives

- **Keep `:initial-db`** (as a data key, or as sugar lowering to a leading
  `[:rf/set-db …]`). One concept + a fully visible construction log won out; pre-alpha
  means a clean break with no shim.
- **A lenient `:initial-events` shape** ("one event or a vector of events"). Rejected for
  the `[:a :b]` ambiguity; the strict vector-of-vectors is the bright line.
- **Replay machinery** — an executed-setup record, a construction snapshot, a cofx-capture
  tape, baseline versioning, atomic / build-then-swap reset, shadow-frame staging.
  Rejected as out of proportion: the loop this replaces has none of it, and reset is a
  best-effort re-run, not a faithful reconstruction.
- **A shared setup-runner primitive with outcome detection**, and coupling Story's grammar
  onto it. Rejected: dispatch each step like the loop; Story keeps its own grammar.
- **Mid-cascade frame construction** (or a frame-creation effect). Rejected: frames are
  created by the view or at top level; handler-time construction is an error.

## Backwards compatibility and migration

Pre-alpha; no shim. `:on-create` and `:initial-db` are removed; a construction map that
still supplies either fails loudly.

```clojure
{:on-create [:app/boot]}                       ;; old
{:initial-events [[:app/boot]]}                 ;; new

{:initial-db {:n 0}}                            ;; old
{:initial-events [[:rf/set-db {:n 0}]]}         ;; new
```

Update the surfaces that teach `:initial-db` / `:on-create`: `spec/002-Frames.md`,
`spec/007-Stories.md`, `spec/API.md`, `spec/008-Testing.md`, the guide (frame / app-db
docs), the `frame-provider` docs, examples, skills, and conformance / schema fixtures.

## Reference implementation — work items (beads)

The implementation decomposes into the beads below. They are **designed here but not yet
filed**; create them with `bd create` when EP-0027 implementation is scheduled. Sequencing:
the doc/spec sweeps (B7, B8) land **in lockstep with** the code retirement (B6), never ahead
of it, so no doc ever describes a key the code still has.

- **B1 — `:rf/set-db` standard event.** _[task · P2]_ Register `:rf/set-db` (handler returns
  `{:db new-db}`; validates exactly one map argument, else `:rf.error/set-db-bad-value`) in
  **both the regular registrar and the image standard registry**; reserved-id collision guard
  on app re-registration.
- **B2 — `:initial-events` normalizer + setup runner.** _[task · P2]_ Validate the strict
  top-level shape; a step is an event vector or `{:event … :opts …}` where `:opts` is ordinary
  `dispatch-sync` opts with `:frame` forbidden. At construction, dispatch each step through the
  existing synchronous dispatch path, in order, tagging each with `:source :rf.frame/init` +
  step index; on a throw, tear down the partial frame.
- **B3 — Wire into the three construction surfaces.** _[task · P2]_ `reg-frame`, `make-frame`,
  and owned `frame-provider` accept `:initial-events`; the provider runs setup once per
  frame-id lifetime and destroy-then-rethrows on an acquire throw.
- **B4 — Forbid handler-time construction (Spec 002 decision).** _[task · P1]_ Replace the
  `trace/*handler-scope*` `:on-create` branch in `reg-frame` with the fail-loud
  `:rf.error/frame-construction-in-handler` guard; record the Spec 002 principle (handlers
  mutate app-db; views/top-level materialize frames). Touches `spec/002-Frames.md` — hot-zone,
  sequential.
- **B5 — `reset-frame!` replays `:initial-events`.** _[task · P2]_ Re-dispatch the recorded
  `:initial-events` in place of the `:on-create` re-fire; `:initial-events` is durable frame
  config, replaced on re-registration and cleared when absent; best-effort, no snapshot.
- **B6 — Retire `:on-create` / `:initial-db` (code) + diagnostics.** _[task · P1]_ Reject both
  in construction maps; add the full `:rf.error/*` set from §Diagnostics.
- **B7 — Sweep `/spec` for stale references.** _[task · P2]_ A careful `git grep` over
  **tracked** files under `/spec` for every stale reference — `:on-create`, `:initial-db`,
  `:rf.frame/initial-db`, the old `reset-app-db!` `{}`-clear wording, and any mid-cascade /
  handler-time frame-construction language — removing or rewriting each to the
  `:initial-events` / `[:rf/set-db …]` forms. **Tracked files only** (`git grep`, so the
  generated `site/` and build copies are skipped — the residue trap). `spec/*` are hot-zone
  (sequential). Pre-alpha clean break: no shim, no deprecation note. Land in lockstep with B6.
- **B8 — Sweep `/docs/guide` + examples / skills / conformance.** _[task · P2]_ The same
  careful, tracked-files-only `git grep` over `/docs/guide`, plus `examples/`, `skills/`, the
  conformance / schema fixtures, and any teaching README. Land in lockstep with B6.

## Acceptance criteria

- `:initial-events` works for `reg-frame`, `make-frame`, and `frame-provider`, dispatching
  each step synchronously in order; `:on-create` and `:initial-db` are rejected.
- `:rf/set-db` is public, registered as a framework standard (regular registrar + image
  standard registry), replaces app-db only, rejects a missing / `nil` / non-map argument,
  and rides normal schema validation / rollback.
- One setup event is `[[:event/id …]]`; bare top-level event vectors are rejected with the
  fix-naming diagnostic; a map step is `{:event … :opts …}` with `:opts` the ordinary
  `dispatch-sync` opts and `:frame` forbidden.
- Constructing a frame inside a handler fails loudly; a step that throws tears down the
  partial frame and names the step.
- `reset-frame!` re-dispatches the recorded `:initial-events`; the other app-db reset
  verbs are unchanged.
- `frame-provider` runs setup once per frame lifetime; a setup throw destroys the
  just-created frame, then rethrows.
- Specs, API, guide, provider docs, examples, skills, and conformance no longer teach
  `:on-create` / `:initial-db`.

## Recommendation

Adopt `:initial-events` as the single declarative frame-setup surface, with `:rf/set-db`
for app-db seeding, and retire `:on-create` and `:initial-db`. Keep it exactly as light as
the loop it replaces — the value is in the readable, complete, replayable declaration, not
in new lifecycle machinery.

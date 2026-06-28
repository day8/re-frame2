# EP-0018: One Event Registration Surface

Status: final
Type: standards-track

> **`final` means the decisions are settled.** The seven design decisions are
> ruled (see [§Resolved Decisions](#resolved-decisions)) and the normative homes
> named below govern (where this EP and the spec differ, the spec governs).

> This EP collapses re-frame2's three public event-registration forms to one —
> **`reg-event`**, semantically today's `reg-event-fx` (coeffects in, a closed
> effects map out). `reg-event-db` is removed; `reg-event-ctx` is demoted from
> the public API to a framework-internal primitive, with full-context work owned
> by interceptors. The result is a single event spelling with no second-class
> form, realigned with re-frame's own canonical handler shape and with EP-0017's
> coeffect-declaration model. The normative homes are
> `spec/002-Frames.md` (event handler contract), `spec/001-Registration.md`
> (the `reg-event` row + `reg-event-ctx` internal demotion), with
> `spec/Spec-Schemas.md`, `spec/009-Instrumentation.md`,
> `spec/015-*` (event classification), the API manifest, guide, skills, and
> examples swept.
>
> **Graduated `accepted → final` 2026-06-15 (Mike, operator graduation).** The
> seven design decisions are ruled (see
> [§Resolved Decisions](#resolved-decisions)): **D1** accept the
> verbosity/pedagogy trade; **D2** demote `reg-event-ctx` to internal; **D3**
> the bare name `reg-event`; **D4** two-arg `(fn [cofx event])` handler only;
> **D5** defer any static db-only metadata signal; **D6** sequence behind
> EP-0017 slice A; **D7** the migration codemod rewrites faithfully and *flags*
> nil-capable handlers. The reg-event collapse has **shipped and is verified**:
> one public `reg-event` (= today's `reg-event-fx`); the retired names
> (`reg-event-db` / `reg-event-fx` / public `reg-event-ctx`) are throwing stubs
> naming their replacement; removal is conformance-gated; and there are **zero
> live call sites** of the retired forms across the corpus. `final` asserts the
> **decisions are settled** and the normative homes govern (where this EP and the
> spec differ, the spec governs).

## Abstract

re-frame2 ships three public event-registration forms: `reg-event-db`
(`(db, event) -> new-db`), `reg-event-fx` (`(coeffects, event) ->
effects-map`), and `reg-event-ctx` (`context -> context`, advanced). This EP
reduces the public surface to one — **`reg-event`**, semantically
`reg-event-fx` — and demotes `reg-event-ctx` to framework-internal. Every
public event handler then takes the coeffects map and returns the closed
effects map; full-context manipulation is owned by interceptors, which are
already the `context -> context` primitive. `reg-event-db`'s db-in/db-out
convenience is removed.

One sentence: *one event form — coeffects in, effects out — for every handler,
with interceptors owning full-context work and `reg-event-ctx` retired to
internal plumbing.*

## Motivation

### `reg-event-db` is already a second-class form after EP-0017

This is the decisive internal reason, and it is already enforced in the
implementation. EP-0017 made a handler declare its world facts via
`:rf.cofx/requires` and receive them through the coeffects map. But
`reg-event-db` takes `db` directly, **not** the coeffects map — so it
structurally cannot declare coeffects. The code raises
`:rf.error/cofx-request-invalid` when a `reg-event-db` registration carries
`:rf.cofx/requires` (`implementation/core/src/re_frame/events.cljc:633–669`),
with the message *"a `reg-event-db` … that needs world facts graduates to
`reg-event-fx`."* So post-EP-0017 the language already carries a form with a
hole in it: the moment a handler needs a recorded world fact, `reg-event-db`
cannot express it and you must convert. Collapsing to one `reg-event` closes
the hole — every handler declares coeffects uniformly.

### It realigns with re-frame's own canonical handler shape

re-frame's actual event model is *coeffects in, effects out*: a handler reads
the facts it is given and returns a description of what should happen
(`{:db … :fx …}`). That *is* `reg-event-fx`. `reg-event-db` is a convenience
*deviation* from re-frame's own canonical shape — sugar for the common "the
only effect is a db write" case, which returns the db directly and hides the
effect map. Collapsing to one `reg-event` makes every handler speak re-frame's
real model: the db write is an explicit effect (`{:db …}`) like any other, not
a special return shape. This is not a new idea imported from elsewhere — it is
re-frame's own fx model with the db-only shortcut removed.

### The migration cliff disappears

Today's most common refactor papercut: you write `reg-event-db`, then the
handler needs one effect, so you rewrite the registration fn, the handler
signature (`db` -> coeffects map), and the return (`db` -> `{:db}`). With one
`reg-event`, adding an effect is adding a key to a map you already return — no
signature change, no conversion. The form stops being a local optimum you have
to abandon the instant the handler does anything beyond a pure db update.

### One spelling, consistent with the project's subtractive arc

A single `reg-event` removes the "`-db` or `-fx`?" fork — a real newcomer
stumble — and continues the project's pattern of collapsing conveniences into
primitives (EP-0017 removed `inject-cofx`; EP-0002 removed the `:rf/default`
ambient fallback). "One explicit primitive over many implicit conveniences"
([Principles](../../spec/Principles.md)) is the governing ethos.

### `reg-event-ctx` has no demonstrated public use case

Verified against the corpus 2026-06-14: **zero `examples/` use
`reg-event-ctx`** (against 33 `reg-event-db`/`-fx` occurrences). Its apparent
niche — full-context manipulation — is owned by **interceptors**, which are
the framework's `context -> context` primitive (a `reg-event-ctx` handler and
an interceptor `:before`/`:after` are the identical shape). Its real roles are
both internal: (1) the primitive `-db`/`-fx` are sugar over, and (2) a
mechanism a framework subsystem dispatcher may use. The testing/Story corners
do not need it either — context capture is owned by the epoch/trace substrate,
effect interception by `:fx-overrides`, input supply by coeffect supply, and
stubbing by re-registration.

## Goals / Non-Goals

### Goals

- One public event-registration form, `reg-event`, semantically `reg-event-fx`.
- Remove `reg-event-db` from the public API.
- Demote `reg-event-ctx` to a framework-internal primitive (off the public
  surface; the mechanism is retained for the sugar lowering and subsystems).
- Uniform coeffect declaration: every event handler can carry
  `:rf.cofx/requires` (closing the EP-0017 hole).
- Preserve the closed effects-map return contract (`#{:db :fx :rf.db/runtime}`)
  unchanged.

### Non-Goals

- **Not** changing effect or coeffect semantics — the effects map, `:fx`
  vector, and the EP-0017 coeffect model are untouched; only the *event
  registration surface* collapses.
- **Not** removing interceptors or the full-context mechanism — the mechanism
  `reg-event-ctx` exposes stays as internal plumbing; only its *public surface*
  is withdrawn.
- **Not** touching `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, machines, or any
  other registrar family.
- **Not** introducing a new terse db-only convenience under another name — that
  would re-create the fork this EP removes.
- **Not** changing `:db`-commit semantics — the no-op/`identical?`/nil-coercion
  rules below are *documented* here but *implemented* by a separate, EP-independent
  change.

## Relationships

- **[EP-0022](EP-0022-registered-interceptors.md) (Registered Interceptors) —
  forward errata.** This EP names `->interceptor` as the public form for the
  full-context work it moves off `reg-event-ctx` (see §3, §7, and the Rationale).
  **EP-0022 supersedes that guidance:** `->interceptor` is no longer a public
  authoring form, and full-context interceptor behavior is now authored with
  **`reg-interceptor`** and referenced by id in event/frame `:interceptors`
  chains. Where the `->interceptor` examples below appear, read them as
  `reg-interceptor` + an interceptor ref per EP-0022 §10. The decisions of this
  EP (the `reg-event` collapse, the `reg-event-ctx` demotion) are unchanged.
- **[EP-0017](EP-0017-recordable-coeffects.md) (Recordable Coeffects)** — the
  decisive driver: `reg-event-db` cannot declare `:rf.cofx/requires` (a
  registration-time error today), so the collapse closes a hole EP-0017
  opened. This EP sequences *after* EP-0017 slice A so the coeffect-declaration
  surface exists uniformly (D6).
- **[EP-0007](EP-0007-one-name-per-fact.md) (One Name Per Fact)** — one
  canonical spelling; the `-db`/`-fx` fork is two spellings for "register an
  event," and the retirement uses EP-0007 rule 2 (a hard error naming the
  replacement, never a silent alias).
- **[EP-0002](EP-0002-frame-target-resolution.md)** — same subtractive pattern
  (removed the ambient default frame); precedent for "remove a convenience,
  require the explicit form."
- **[EP-0013](EP-0013-app-values-and-runtime-realms.md)** — app-value
  (`module`) event descriptors lower through the one shape and drop
  `:event/kind`.
- **[Principles](../../spec/Principles.md)** — "one explicit primitive over many implicit
  conveniences."
- **The commit-semantics change (EP-independent)** — `identical?`
  no-op short-circuit + `{:db nil}` → `{:db {}}` coercion + trace/Xray, which
  this EP references but does not own.

## Specification

### 1. `reg-event` — the one public form

```clojure
(reg-event id ?metadata handler)
```

- `handler` is `(fn [coeffects event-vec] …)` (two-arg only, D4) and returns the
  **closed effects map** (`{:db … :fx [...] …}`) or `nil` — semantically
  identical to today's `reg-event-fx` handler. The second argument is the event
  vector; `(:event coeffects)` is the same value. Handlers that do not need the
  event use `_`.
- `?metadata` is the standard Spec 001 superset middle slot (`:doc`, `:schema`,
  `:interceptors`, `:rf.cofx/requires`, `:rf.trace/no-emit?`, `:sensitive`,
  `:large`, …) — unchanged, and now uniformly available to every event (the
  EP-0017 hole closed). The positional interceptor vector remains retired;
  interceptors live under `:interceptors`.

```clojure
;; before (two forms):
(rf/reg-event-db :counter/inc (fn [db _] (update db :count inc)))
(rf/reg-event-fx :todo/add {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ text]] {:db (assoc-in db [:todos] …)}))

;; after (one form):
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _] {:db (update db :count inc)}))
(rf/reg-event :todo/add {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ text]] {:db (assoc-in db [:todos] …)}))
```

### 2. `reg-event-db` — removed

`reg-event-db` is removed from the public API (D1). Per EP-0007 rule 2 (the
`:dispatched-at` / `inject-cofx` retirement pattern: a hard error naming the
replacement, never a silent alias), calling `reg-event-db` raises
`:rf.error/reg-event-db-removed` naming `reg-event` and showing the two-line
conversion (destructure `:db` from the coeffects map; wrap the return in
`{:db …}`).

### 3. `reg-event-ctx` — demoted to framework-internal

`reg-event-ctx` is withdrawn from the public surface — API manifest, public
docs, skills (D2). The underlying `context -> context` mechanism is **retained
internally** (it is what `reg-event` lowers onto and what subsystem dispatchers
use) but is no longer a documented application authoring form. Full-context
manipulation in application code is done with **interceptors** (the public
`context -> context` primitive). Calling public `reg-event-ctx` raises
`:rf.error/reg-event-ctx-removed` naming `->interceptor`. If a public path is
later found that genuinely needs app-level full-context handlers, it returns by
a follow-on with that specific consumer and a sharper name; the corpus shows
none today.

### 4. Effects-map and coeffects model — unchanged

This EP changes only the *registration surface*. The coeffects map a handler
receives — `:db`, `:event`, `:rf.frame/id`, `:rf.db/runtime`, `:rf.cofx`, and
each `:rf.cofx/requires` fact delivered flat — the closed effects map it
returns (`#{:db :fx :rf.db/runtime}`, `:rf.db/runtime` framework-authority
only), the `:fx` interpreter, and EP-0017's `:rf.cofx/requires` / `:rf.cofx`
are all unchanged. A `reg-event` handler is byte-for-byte a `reg-event-fx`
handler; only the name and the removal of the db-only sibling change. Legacy v1
top-level effect shortcuts — `{:dispatch …}`, `{:http-xhrio …}` — remain
invalid as today; the shape is `{:fx [[:dispatch …] …]}`.

### 4a. Commit / no-op family (documented here, implemented elsewhere)

Because the collapse makes `{:db db}` the natural way to say "I didn't change
the db" (replacing v1 `reg-event-db`'s bare-`db` return in no-change branches),
the `:db`-commit contract is stated for clarity:

| Handler return | App-db effect |
|---|---|
| `nil` | no-op (nothing committed) |
| `{}` | no-op |
| `{:db <new>}` (new ≠ current) | commit `<new>` as app-db |
| `{:db db}` (the unchanged db) | **no-op** — `identical?` short-circuit, no write |
| `{:db nil}` | **coerced to `{:db {}}`** — app-db is always a map, never nil (+ dev diagnostic; `{:db {}}` is the clean deliberate clear) |

The unchanged-`:db` no-op is observably true today (`frame.cljc:605–657`:
`commit-frame-transition!` detects change by `=`, fires no `:rf.event/db-changed`
signal, and downstream projections `=`-memoize). The commit-level `identical?`
short-circuit, the `{:db nil}` → `{:db {}}` coercion, and recording both on the
trace bus / Xray are **out of EP scope** — they are independent of the collapse
(applying to every event form) and are tracked separately. This EP only
*documents* the family, because the no-change branch being a true no-op is what
keeps the collapse's verbosity cheap: `(if cond (assoc db …) db)` migrates to
`{:db (if cond (assoc db …) db)}` and the `else` arm costs nothing.

### 5. Registry sub-kind and tooling

A `reg-event` entry is simply kind `:event`; the public `:event/kind :db | :fx`
sub-discriminator is removed (D5 keeps no static effects signal). Tooling that
wants to know what a handler *did* reads traces and effect projections, not the
registration name. The internal handler-wrapper interceptor ids unify
(`:rf/db-handler` / `:rf/fx-handler` / `:rf/ctx-handler` → one
`:rf/event-handler`, retaining `:rf/default? true`). EP-0013 app-value event
descriptors lower through the one shape and drop `:event/kind`. Source-coord
capture continues to stamp the whole `(reg-event …)` form.

### 6. Path interceptors still work

`(rf/path …)` is unaffected in mechanism; the return changes shape — it
transforms the `:db` coeffect before the handler and reinserts the `:db` effect
after, so the handler returns `{:db slice}` rather than a bare slice:

```clojure
(rf/reg-event :counter/inc
  {:interceptors [(rf/path :counter)]}
  (fn [{:keys [db]} _] {:db (update db :value inc)}))   ;; db = the focused slice
```

### 7. Interceptor context work

Full-context work that used `reg-event-ctx` is expressed as an interceptor —
the public `context -> context` primitive. Capture, short-circuit
(`:rf/skip-handler?`), and direct effect installation are all interceptor
`:before`/`:after` concerns:

```clojure
(def auth-guard
  (rf/->interceptor
    {:id :app/auth-guard
     :before (fn [ctx]
               (if (logged-out? ctx)
                 (-> ctx (assoc :rf/skip-handler? true)
                         (assoc-in [:effects :fx] [[:dispatch [:login/show]]]))
                 ctx))}))

(rf/reg-event :guarded/action {:interceptors [auth-guard]} (fn [_ _] {}))
```

## Rationale

- **Why `reg-event` (= fx) and not keep `-db` as primary?** The fx shape is the
  one that composes with everything (effects, coeffects declaration) and is
  re-frame's own canonical handler shape. `-db` is the local optimum that
  breaks the instant a handler grows — and post-EP-0017 it is the form that
  cannot declare coeffects. Keeping `-db` keeps the hole.
- **Why the bare name `reg-event` (D3)?** The `-fx` suffix exists only to
  contrast with `-db`; with `-db` gone there is nothing to contrast with, so
  the suffix is noise. `reg-event` is the honest name for "register an event."
- **Why remove rather than keep `-db` as sugar?** Sugar that cannot carry the
  full contract (coeffect declaration) is a trap, not a convenience — it reads
  as a peer of `reg-event` but silently cannot do what `reg-event` can. One
  form with no hidden second-class sibling is more legible, especially for the
  AI-first audience reading the registry.
- **Why demote `reg-event-ctx` rather than delete the mechanism (D2)?** The
  mechanism is load-bearing internally (the lowering target; subsystem
  dispatchers). Deleting the mechanism is out of scope; withdrawing the
  *public surface* is the actual simplification, and it is pure subtraction
  since no public code uses it. The escape remains `->interceptor`; if a real
  consumer ever needs a named public context handler, it returns with that
  consumer and a sharper name.
- **Why two-arg handlers only (D4)?** A one-arg `(fn [cofx] …)` would add a
  second arity convention for no gain; the two-arg form is a direct
  `reg-event-fx` rename and reads the event positionally. Unused event is `_`.
- **Why no static db-only signal (D5)?** `reg-event-db`'s "this handler can't
  issue effects" promise was only partly true (interceptors could add effects;
  post-EP-0017 a db-only durable write may consume recordable facts). The real
  behavior is the effect map and effect projection, which tools read directly.
  If a static promise is ever wanted, it belongs as metadata or lint
  (`{:rf.event/effects #{:db}}`), not a second registration function.
- **Within re-frame's own lineage.** re-frame has always returned effects
  alongside state from the one handler; `reg-event-db` is the convenience
  layer, not the model. Other MVU-family libraries land on different splits
  (Redux keeps reducers pure-state and moves effects out into
  middleware/thunks); re-frame's choice has always been effects returned *from*
  the handler as data — the collapse simply makes that one shape the only
  public spelling.

## The honest cost

Stated plainly because it is the crux of the decision (D1, accepted):

1. **The common case gains ceremony.** The dominant handler shape — a pure db
   update — goes from `(fn [db _] (update db …))` to `(fn [{:keys [db]} _]
   {:db (update db …)})`: a `:keys [db]` destructure and a `{:db …}` return
   wrap. There is no clean way to keep the terse form inside one uniform
   `reg-event` (you cannot reliably distinguish "a returned new app-db map"
   from "a returned effects map" — both are maps), so the tax is unavoidable.
   The wrap is the honest expression of re-frame's effects-as-data model — the
   db write stated as the effect it always was.
2. **Pedagogy front-loads effects.** `reg-event-db` is the cleanest statement of
   "an event is a pure function of state" and the natural first thing to teach.
   With one `reg-event`, the first example shows the full
   coeffects-in/effects-out shape before the learner needs either.

**Two mitigations (adopted):** (a) frame `{:db …}` positively — "an event
returns the next state and what to do" — rather than apologizing for the wrap;
and (b) teach **pure state helpers**: extract `(defn inc-db [db] …)` and
register `(fn [{:keys [db]} _] {:db (inc-db db)})`. The pure fn *is* the state
transition — it keeps "events are pure functions of state" teachable *and*
bare-callable in tests (the EP-0017 testing value); the wrapper is thin.

## v1 → v2 migration

Pre-alpha means no back-compat shims for v2's own evolution, but the v1 → v2
migration path is a real product concern, and this EP touches the single
highest-volume v1 construct: `reg-event-db` is the most common registration in
nearly every v1 app. It cuts both ways.

**The cost (a point genuinely *for* keeping `reg-event-db`):** today a v1
`reg-event-db` maps 1:1 to v2 — a no-op migration for the most common
construct. Removing it converts that into a *signature change* (`db` positional
→ destructured) plus a *return wrap* (`db` → `{:db …}`) across potentially
thousands of call sites per app.

**The mitigation:** the transformation is deterministically codemod-able —

```
(rf/reg-event-db ID (fn [db EV] BODY))
   ->
(rf/reg-event    ID (fn [{:keys [db]} EV] {:db BODY}))
```

`BODY` always evaluates to the new db (that *is* the `reg-event-db` contract),
so wrapping it in `{:db …}` is mechanical regardless of how complex `BODY` is.
The one nil subtlety the codemod should **flag, not silently rewrite** (D7):
today `reg-event-db` returning `nil` writes nil to app-db (a known footgun);
the `{:db BODY}` wrap faithfully preserves that, but for any handler whose
`BODY` can evaluate to nil the codemod emits a warning, because under the new
model a bare `nil` return is a clean no-op and the author may now prefer that
(and `{:db nil}` is coerced to `{:db {}}`). The collapse thus
*removes the footgun for newly-written handlers* while preserving existing
semantics under migration.

**The weighing:** migration cost is one-time and tool-handled; the uniformity
benefit (no second-class form, no migration cliff within a v2 app's future,
uniform coeffect declaration) is permanent. A v1 app moving to v2 is already
undergoing substantial change (frames, partitions, the cofx model); one more
deterministic codemod rule rides that existing migration. The
`reg-event-db -> reg-event` codemod is a first-class, *tested* rule in
`migration/from-re-frame-v1/` + `skills/re-frame-migration`, landed with this
EP.

## Backwards Compatibility

This is a source break to v2's public surface; acceptable pre-alpha, and
cleaner now than after more surfaces depend on the split. No v2 aliases:
working aliases would preserve exactly the vocabulary this EP removes.

**Diagnostic stubs are not aliases.** A migration tool or an implementation
branch MAY recognise the retired names (`reg-event-db` / `reg-event-fx` /
`reg-event-ctx`) long enough to produce high-quality errors or rewrites — but
the *final* `re-frame.core` public surface keeps none of them as working
alternatives.

| Surface | Change |
|---|---|
| `re-frame.core` facade | `reg-event` added (= current `reg-event-fx` impl); `reg-event-db` / `reg-event-fx` removed; public `reg-event-ctx` withdrawn (mechanism internal); retired names raise hard errors naming the replacement |
| `spec/001-Registration.md` | `reg-event` row replaces `reg-event-db`/`-fx`; `reg-event-ctx` recategorised internal |
| `spec/002-Frames.md` | event handler contract: one form, coeffects → effects |
| `spec/Spec-Schemas.md` | event-registration schema unified; `:event/kind` sub-kind removed from public metadata |
| `spec/009-Instrumentation.md` | handler-wrapper (`:rf/event-handler`) + source-capture prose |
| `spec/015-*` (egress / event classification) | `reg-event-{db,fx,ctx}` → `reg-event` classification prose |
| `spec/API.md` + `spec/api-manifest.edn` + metadata | manifest rows updated; `:where 'rf/reg-event-db` annotations re-pointed |
| EP-0017 text | `:rf.cofx/requires` lives on `reg-event` (no db-handler exception) |
| `implementation/core` (`events.cljc`, `core.cljc`, `router.cljc`) | add `reg-event`; remove public `-db`/`-fx`/`-ctx`; one `:rf/event-handler` wrapper; drop the now-moot `reg-event-db`-requires error path |
| `examples/` | ~33 `reg-event-db`/`-fx` sites across ≥10 files → `reg-event` |
| `docs/core/` + quickstart | first event examples rewritten around the one form (the two pedagogy mitigations) |
| `skills/` + `migration/from-re-frame-v1/` + `skills/re-frame-migration` | one-form guidance; the codemod + a migration breaking-changes entry |
| Xray / Story / pair-MCP | code-snippet labels, highlighting, event panels |
| `migration/from-re-frame-v1/README.md` | record `reg-event-db` / `reg-event-fx` / public `reg-event-ctx` as removed, naming `reg-event` / `->interceptor` |

Hot-zone: 001-Registration, 002-Frames, Spec-Schemas, 009, 015, API.md/manifest
are sequential, one worker per step.

## Bead Plan / Reference Implementation (on acceptance, behind EP-0017 slice A)

1. **Spec amendment** — 001 + 002 + Spec-Schemas + 009 + 015 + API.md/manifest;
   the EP-0017 text edit (requires-on-`reg-event`).
2. **Runtime core** — add `reg-event`; remove public `-db`/`-fx`/`-ctx`; unify
   the handler wrapper to `:rf/event-handler` (`:rf/default? true`); retired-name
   hard errors; drop the moot `reg-event-db`-requires path; keep effect-map
   policing and nil-no-op exactly where `reg-event-fx` polices them today.
3. **Macro layer** — `reg-event` macro with the existing source-coord / form-source
   capture; remove the three old macro entries from facade + manifest; update
   production elision sentinels.
4. **App values** — EP-0013 descriptors lower through `reg-event`; drop
   `:event/kind` from descriptor generation.
5. **Corpus sweep** — `examples/` (≥10 files), guide + quickstart, skills,
   migration docs; Xray/Story/pair-MCP snippets and labels. *Documentation churn
   is the largest hidden cost; the sweep is mandatory, not optional.*
6. **Migration tooling** — scanner reporting every retired-name site with
   file/line + suggested target; conservative codemod (`-fx` rename; simple
   `-db` → `{:db …}` wrap; preserve path-interceptor metadata); leave complex
   `-db` forms and all `-ctx` forms for manual review; migration tests over
   representative v1 snippets; flag nil-capable `-db` handlers (D7).
7. **Conformance** — the assertions below.
8. **Wave-end** — correctness/completeness review + the four standard
   propagation tails (`/skills`, `/examples`, `/tools`, `/docs/core`).

## Conformance

The conformance suite asserts:

- `reg-event` registers under kind `:event`; `handler-meta :event id` returns
  the metadata + effective interceptor chain; the framework wrapper is
  `:rf/event-handler` with `:rf/default? true`.
- A handler receives `:db`, `:event`, `:rf.frame/id`, `:rf.db/runtime`,
  `:rf.cofx`, and declared coeffects flat; undeclared coeffects are not staged.
- `:rf.cofx/requires` typo / missing / supplied / generator paths behave per
  EP-0017, now on `reg-event` with no db-handler exception.
- `nil` and `{}` returns are no-ops; `{:db <new>}` commits; foreign top-level
  effect keys emit `:rf.error/effect-map-shape`; legacy top-level shortcuts
  remain rejected; `:rf.db/runtime` returns keep the existing app-handler
  diagnostic unless framework authority is present.
- Path interceptors work with `{:db slice}` returns.
- Raw context capture / short-circuit is expressible with an interceptor; no
  public `reg-event-ctx` is required for the suite.
- Retired public names (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`)
  raise their naming hard errors.

(The `{:db <identical>}` no-op and `{:db nil}` → `{:db {}}` assertions belong to
the separate commit-semantics work, not this EP.)

## Resolved Decisions

Ruled 2026-06-14 (Mike, in-session):

| # | Decision | Ruling |
|---|---|---|
| **D1** | Accept the verbosity / pedagogy trade for one spelling? | **Accept the trade.** Carried with the two mitigations (positive framing + pure-helper extraction). |
| **D2** | `reg-event-ctx`: demote / delete / keep public? | **Demote to framework-internal.** Mechanism retained; public surface withdrawn; interceptors own the niche. |
| **D3** | Name: `reg-event` vs keep `reg-event-fx`? | **Bare `reg-event`.** |
| **D4** | Handler arity: two-arg vs also one-arg? | **Two-arg `(fn [cofx event])` only.** |
| **D5** | Add a static db-only metadata signal now? | **Defer.** No consumer today; not via a registration function. |
| **D6** | Sequencing vs EP-0017? | **Gate behind EP-0017 slice A** (uniform coeffect declaration). |
| **D7** | nil-return codemod behaviour? | **Rewrite faithfully (`{:db BODY}`) and flag nil-capable handlers** for human review. |

## Recommendation

Adopt the one-surface event model — `(rf/reg-event id ?metadata handler)` with
`handler := (coeffects, event) -> effect-map-or-nil` — removing public
`reg-event-db` / `reg-event-fx` and demoting `reg-event-ctx`, sequenced behind
EP-0017 slice A, with the codemod and a mandatory corpus sweep. All seven design
decisions are ruled (above) and the EP graduated `accepted → final`
(2026-06-15, Mike); the collapse has shipped — one public `reg-event`, the
retired names are throwing stubs naming their replacement, removal is
conformance-gated, and zero live call sites remain across the corpus.

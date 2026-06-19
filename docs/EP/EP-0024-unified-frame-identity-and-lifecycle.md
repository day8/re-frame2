# EP-0024: Unified Frame Identity And Lifecycle

Status: final
Type: standards-track

> Accepted 2026-06-18: this EP records the operator's rulings on `rf2-kz2vfp`,
> `rf2-uc6ebw`, and `rf2-um1jcq`; all open issues below are now resolved.
> The EP defines the post-EP-0023 frame cleanup: one live frame value backed by
> one registry, one public operation-target grammar, and one explicit UI-owned
> lifecycle boundary (`rf/frame-provider`). Normative homes are `spec/002-Frames.md`,
> `spec/API.md`, `spec/001-Registration.md`, `spec/Runtime-Subsystems.md`, and
> `spec/Conventions.md`.

## Abstract

EP-0023 made `image -> frame -> event stream` the public model. The current
implementation still realizes a frame through two live concepts: an image-loaded
frame object and a separate backing frame record. That split leaks upward as
multiple constructors, multiple registries, multiple target spellings, and
unclear ownership rules for view-created frames.

This EP proposes one live frame value and one live frame registry. The resolved
image generation is stored on the frame itself. Public frame-scoped operations
target a frame through the event/query plus opts-map grammar, usually
`{:frame frame-id}` or an established ambient frame context. Lifecycle ownership
is separate: create/destroy frames with lifecycle APIs, not with dispatch or
subscribe target coercions.

## Motivation

The frame model is the isolation boundary for app-db, runtime-db, event drains,
subscription caches, effect routing, history, and tooling. Users should not need
to learn whether a call wants a frame id, a frame object, a handle, a provider
context, or a constructor side effect.

The API-review findings show one structural cause:

- there are two frame creation paths;
- there are two live-frame registries;
- the object form points back to the backing record for most stateful work;
- Story frames sometimes have to create both halves in a load-bearing order;
- teardown walks both structures;
- target normalization exists largely to collapse object-shaped inputs back to
  the id used by the backing record.

This is larger than a facade trim. It changes the framework's public frame
contract and the implementation substrate that supports hot reload, Story,
tests, SSR, and tooling. It therefore belongs in an EP rather than a bead.

## Goals / Non-Goals

Goals:

- make a live frame one value backed by one registry;
- store the resolved image generation on that frame value;
- make `make-frame` the single public constructor for image-loaded frames and
  ordinary configured frames;
- keep frame ids as the public routing address for dispatch, subscribe, reads,
  providers, and tooling;
- keep frame values as lifecycle tokens returned by creation APIs;
- make view-owned frame lifetime explicit with one UI boundary, `rf/frame-provider`,
  that creates, provides, and destroys;
- serve scoping to an existing frame with `rf/with-frame` (lexical / non-React)
  and `rf/frame-provider-existing` (scope an existing frame into a React subtree
  — a dynamic var cannot cross React's render boundary). The old scope-only
  `frame-provider` becomes the new `rf/frame-provider-existing`; the
  `frame-provider` name is repurposed for the owned lifecycle boundary;
- keep `frame-handle` as the callback carry primitive;
- remove or retier public spellings that duplicate the target/carry/lifecycle
  roles;
- make registrar generation reads name their data source honestly if they remain
  public.

Non-goals:

- do not revisit the EP-0023 image model itself;
- do not reintroduce retired composition vocabulary as current public API;
- do not remove frames as the isolation boundary;
- do not make every in-render call pass `{:frame ...}` when a real provider or
  lexical frame context exists;
- do not preserve compatibility shims for off-pattern pre-alpha spellings;
- do not decide unrelated facade cleanups from the API-review wave.

## Relationships

- **EP-0023** established image-loaded frames and partially superseded the older
  composition teaching surface. This EP is an amendment-style follow-up that
  finishes the frame collapse in the implementation and public grammar.
- **EP-0002** established explicit frame target resolution and the carried-frame
  invariant. This EP keeps that invariant and narrows the spelling of explicit
  target operations.
- **EP-0001** established app-db/runtime-db as frame-owned partitions. The
  unified frame remains the owner of both partitions.
- **EP-0007** supplies the one-name-per-fact rule. This EP applies it to
  `frame id`, `frame value`, `frame handle`, and `resolved image generation`.
- **EP-0013** remains historical and partially superseded at the public surface.
  This EP must not revive its retired public vocabulary.
- **EP-0009** governs this as a new standards-track EP rather than a silent edit
  to final EP-0023. If accepted, this EP records the amendment and graduates
  into the named specs.
- **API-review findings**:
  - `ai/findings/API-review/codex/frame-object-record-unification.md`
  - `ai/findings/API-review/codex/frame-targeting-and-lifecycle.md`
  - `ai/findings/API-review/codex/registrar-query-addressing.md`
  - `ai/findings/API-review/claude/frame-targeting-and-carrying.md` — the
    empirical 0-caller backbone (`frame-first` `(dispatch f ev)`,
    `frame-bound-fn`/`frame-bound-fn*`, `subscribe*` have zero real call sites in
    examples and tools) that grounds the helper-removal slices below.
- **Related beads already filed**:
  - `rf2-ts3fuk` fixes unsubscribe target normalization symmetry.
  - `rf2-az1ct6` factors an internal frame-record resolver if not absorbed by
    this EP.
  - `rf2-ntwwyt` moves HTTP test-support helpers out of the core facade.
  - `rf2-10nggz` is the **home** for the registrar query/read address grammar
    after EP-0023 (ruled: drop `:realm`, keep `:frame`). EP-0024 references it as
    the home and does not re-decide it (see §Registrar and generation reads).
  - `rf2-kgdk03` recorded the `spec/002-Frames.md` ↔ `spec/API.md` `make-frame`
    contradiction (002-Frames still documented the pre-EP-0023 keyword-returning
    `make-frame`). This EP's §One constructor partially resolves it by unifying
    `make-frame`; the spec-graduation wave for `spec/002-Frames.md` subsumes or
    hands off to the kgdk03 fix.

## Specification

### Terms

| Term | Contract |
|---|---|
| **Frame id** | The stable public address of a live frame inside the process. It is data: serializable, comparable, traceable, and suitable for opts maps, providers, tools, and logs. |
| **Frame value** | The live lifecycle token returned by `make-frame`. It owns the frame id, durable partitions, runtime subsystem state, queue/drain state, caches, lifecycle hooks, and resolved image generation. Its representation is not an app-facing data contract. |
| **Frame handle** | A captured operation bundle for async callbacks. It carries dispatch/subscribe/read functions already targeted at the frame resolved when the handle was created. |
| **Resolved image generation** | The sealed registration generation a frame resolves against while it runs. It is a slot on the frame value. |
| **Frame-state value** | The serializable EP-0001 projection of a frame. It is not the live frame value. |

### One live frame registry

There is one live frame registry. It maps frame ids to unified frame values.

The frame value owns or reaches every per-frame runtime fact through that one
registry entry:

- app-db partition;
- runtime-db partition;
- event queue and drain state;
- subscription cache and topology;
- epoch/history projection state;
- resolved image generation;
- lifecycle hooks and teardown bookkeeping;
- adapter binding/configuration and host-transient leases where applicable.

The implementation may internally split storage for performance or layering, but
there is one public and conceptual owner: the frame value found by frame id.
There is no second public "live frame" registry that has to be kept coherent
with a backing record registry.

### One constructor

`make-frame` is the public constructor for a live frame. It accepts both
image-selection options and frame configuration options in one call.

Illustrative shape:

```clojure
(def frame
  (rf/make-frame {:id :todo/left
                  :images [todo-image]
                  :initial-db {}
                  :fx-overrides {...}}))
```

The constructor returns the frame value. The frame id is readable from that
value through the public accessor chosen during implementation; callers should
not depend on the representation.

`make-frame` must not require a caller to first create a backing frame and then
create an image-loaded object for the same id. A Story, test, SSR request, or
comparison page creates one frame with one call.

This unified single constructor **adopts the previously-deferred option-(a)** —
extend the object constructor so it honours record-config keys
(`:fx-overrides`, `:on-create`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`)
alongside the image-selection keys (`:images`, `:id`, `:initial-db`,
`:capabilities`, `:adapter`) — and thereby **reverses the `rf2-32siq3.45`
option-(b) disposition** that currently ships: the fail-loud redirect
(`:rf.error/make-frame-record-only-key`) that rejects record-config keys on the
object constructor and points callers at the advanced `re-frame.frame/make-frame`.
Under today's implementation the illustrative shape above throws.

The reversal is legitimate, not incidental. Option-(a) was deferred only because
`re-frame.live-frame` was owned by a concurrent EP-0023 slice — it was *not*
rejected on the merits. This EP's core (one frame value backed by one registry)
removes the two-constructor split that motivated the redirect in the first place,
so the fail-loud redirect no longer has a reason to exist. The advanced
`re-frame.frame/make-frame` becomes internal or disappears. (Resolves Open
Issue #1.)

### Duplicate id policy

This EP adopts hot-reload-friendly **idempotent replacement** for `make-frame`:
re-evaluating the same frame declaration updates frame configuration and resolved
image generation without destroying durable state unless the caller explicitly
asks for reset or destroy. Conflict cases that cannot be reconciled must fail
loud.

**This is a deliberate behaviour change, not a neutral choice.** The current
`make-frame` fails loud on a duplicate live id (the docstring states "a duplicate
live id fails loud"). The EP replaces that blanket fail-loud refusal with
idempotent replacement, because the fail-loud rule makes hot reload and Story
re-evaluation needlessly ceremonial. The change is bounded: re-mount under the
same id (hot reload, Story re-evaluation) **must preserve durable state**, and
the fail-loud path is retained for irreconcilable conflicts. The
fail-loud-on-every-live-id alternative was considered and rejected. (Resolves
Open Issue #5; couples to the UI-owned boundary's idempotent re-mount contract.)

### Operation target grammar

Frame-scoped operations put the primary datum first and route through an opts
map or ambient context.

Canonical explicit forms:

```clojure
(rf/dispatch [:todo/add "A"] {:frame :todo/left})
(rf/dispatch-sync [:todo/reset] {:frame :todo/left})
(rf/subscribe [:todo/items] {:frame :todo/left})
```

Canonical ambient forms scope to an existing frame with `rf/with-frame`:

```clojure
(rf/with-frame :todo/left
  (rf/dispatch [:todo/add "A"]))
```

(`rf/frame-provider` is the UI-owned *lifecycle* boundary — it creates and
destroys — not a pure scoping form; see §Scope, carry, and ownership.)

Frame-first operation arities such as `(rf/dispatch target [:event])` are not a
second public grammar. If retained internally for macro expansion or advanced
implementation use, they are not taught as app API.

Public operation targets should be frame ids. Passing a frame value to
dispatch/subscribe is not the canonical app-facing form; callers that own a
frame value can read its id and pass the id. Internal normalization may accept a
frame value where it is useful for tests or tools, but the API teaches one
routing address: the frame id.

### Scope, carry, and ownership are separate

The public API has three different jobs, and each job gets one spelling.

| Job | Public spelling | Contract |
|---|---|---|
| **Scope** descendants to an existing frame (lexical / non-React) | `with-frame` | Does not create or destroy the frame. Establishes context only. |
| **Scope** an existing frame into a React subtree | `frame-provider-existing` | Provides an already-created frame id through React context. `:frame` only — a lifecycle opt fails loud. Creates / refreshes / destroys nothing. |
| **Carry** a frame across async callback boundaries | `frame-handle` | Captures operations targeted at the current or explicit frame. |
| **Own** a frame lifetime | `make-frame` + `destroy-frame!`, `with-new-frame`, and the UI-owned boundary `frame-provider` | Creation and teardown are explicit ownership operations. |

Note the naming — a **`frame-provider` name family** of two per-adapter
React-context components. The old scope-only `frame-provider` is **renamed**
`rf/frame-provider-existing` (same `:frame`-only scope behaviour); the
`rf/frame-provider` name is reused for the UI-owned *lifecycle* boundary — the
component that creates a frame on mount, provides its id to descendants, and
destroys it on unmount. Scoping into a React subtree needs a React-context
component (`frame-provider-existing`) because `rf/with-frame` binds a dynamic
var, which cannot cross React's render boundary; `with-frame` remains for
lexical / non-React ambient scope.

Illustrative shape:

```clojure
[rf/frame-provider {:id :todo/left
                    :images [todo-image]
                    :initial-db {}}
 [todo-root]]
```

`frame-provider` creates the frame on mount, provides its frame id to
descendants, and destroys the frame on unmount. It is the answer for comparison
pages, Story canvases, embedded widgets, and hot-reload-safe view-owned frame
lifetimes.

**`frame-provider` is realized per-adapter, against a shared contract.** It is
not a single component: each substrate (Reagent / UIx / Helix) ships its own
`frame-provider` that hooks the substrate's native lifecycle, exactly as the
scope-only provider (now `frame-provider-existing`) is per-adapter. The EP-0024 spec-graduation wave
specifies the shared contract — **create-on-mount**, **provide the frame id to
descendants**, **destroy-on-unmount**, and **idempotent re-mount** (per the
duplicate-id policy above) — and each adapter implements its native hook. The
per-adapter spec must cover the substrate lifecycle concerns:

- React effect-cleanup timing (when `destroy-frame!` actually runs);
- StrictMode double-invoke in dev (mount → unmount → mount): the create/destroy
  pair must be tolerant of an extra cycle;
- `destroy-frame!`-on-unmount ordering against in-flight subscriptions and event
  drains, so teardown does not race outstanding per-frame work;
- hot-reload-under-the-same-id **must not destroy durable state** — re-mount is
  idempotent replacement, not destroy-then-recreate.

This is the EP's only net-new public surface; the rest of the EP trims or
unifies. It stays included (it was not split out into a separate EP). (Resolves
Open Issue #6.)

This split keeps the user's question small:

- "I already have a frame; how do I scope children?" Use `with-frame` (lexical / non-React) or `frame-provider-existing` (into a React subtree).
- "This component owns a frame lifetime." Use `frame-provider`.
- "This callback will fire later." Use `frame-handle`.

### Carry primitive

`frame-handle` is the public carry primitive for callbacks that fire after the
render or lexical frame context has unwound.

Illustrative shape:

```clojure
(let [{:keys [dispatch subscribe]} (rf/frame-handle)]
  (set! (.-onclick button)
        #(dispatch [:todo/add "A"])))
```

`frame-bound-fn` and `frame-bound-fn*` are not app-facing carry primitives if
`frame-handle` can express the real use cases. They may move to an internal
namespace if implementation code still needs them.

### Registrar and generation reads

The registrar read grammar (the source-store reads and any frame-generation
reads) is **owned and settled by `rf2-10nggz`**, not by this EP. That decision is
ruled — the read address keeps `:frame` and drops `:realm`. EP-0024 references
`rf2-10nggz` as the home and does not re-decide it; there is **no
fold-in-before-acceptance dependency**. This scoping keeps EP-0024 to one
decision surface (frame identity and lifecycle).

The source-store reads remain source-store reads, illustrative only:

```clojure
(rf/registrations :event)
(rf/handler-meta :event :todo/add)
(rf/handler-ids :event)
```

The standing rule EP-0024 still relies on — argument shape must not smuggle
"which data source am I reading?" through a retired composition map — is the
substance `rf2-10nggz` settles. (Open Issue #7 is resolved by scoping this out to
`rf2-10nggz`.)

### Teardown

Destroying a frame removes one unified frame value from the one live registry and
runs teardown for every per-frame subsystem exactly once.

Teardown remains best-effort where individual cleanup hooks are host-transient,
but the ownership path is one path. There is no separate public object registry
whose cleanup can succeed or fail independently of the backing frame registry.

### Vocabulary

Use these names consistently:

- `frame id` for the routing address;
- `frame value` for the live lifecycle token;
- `frame handle` for captured callback operations;
- `frame-state value` for the serializable app-db/runtime-db projection;
- `resolved image generation` for the sealed registration generation a frame
  actually runs.

Do not use retired composition vocabulary as current public API vocabulary.

## Rationale

The Clojure shape should be small: a value, an id, and data-oriented operations.
The current split asks the programmer to remember which functions coerce which
shape. That feels flexible, but it hides a two-registry implementation model in
every call site.

Putting the event or query vector first preserves the ordinary re-frame reading
order: "dispatch this event to that frame." The frame is routing metadata, so it
belongs in opts. Lifecycle is not routing metadata, so it belongs in creation
and teardown APIs.

Separating scope from ownership also avoids a second ambiguity. A single provider
that sometimes creates and sometimes scopes would make cleanup depend on how the
frame arrived. So scoping is `with-frame` only, and `frame-provider` is the
explicitly UI-*owned* boundary — it says the ownership fact (create on mount,
destroy on unmount) out loud, and is realized per-adapter against the shared
lifecycle contract.

## Backwards Compatibility

re-frame2 is pre-alpha. This EP does not require compatibility shims for
off-pattern public spellings. The migration is source-level:

- replace frame-first operation arities with event/query plus opts-map forms;
- replace view-created `make-frame` plus manual provider lifetimes with the
  UI-owned `rf/frame-provider` boundary;
- replace scope-only uses of the old `frame-provider` with `rf/with-frame`;
- replace `frame-bound-fn` use with `frame-handle`;
- replace any create-twice frame setup with one `make-frame` call;
- migrate registrar target-map reads per `rf2-10nggz` (the home for the read
  grammar).

One behaviour change is intentional: `make-frame` moves from fail-loud on a
duplicate live id to idempotent replacement that preserves durable state on
re-mount (irreconcilable conflicts still fail loud). See §Duplicate id policy.

Historical EP prose remains historical. Current docs, examples, tools, tests,
and API manifests move to the accepted vocabulary.

## Bead Plan / Reference Implementation

### Draft and decision

- **`rf2-t0y79n`** drafts this EP and indexes it.
- Operator ruling resolves the open issues below.
- If accepted, file the implementation wave as child beads or an epic under this
  EP. Do not start the structural implementation before acceptance.

### Spec graduation wave

Update:

- `spec/002-Frames.md` for unified frame identity, the single `make-frame`
  constructor (subsuming or handing off the `rf2-kgdk03` make-frame
  contradiction fix), the `with-frame` scope vs `frame-provider` owned-lifecycle
  split, the carry primitive, and teardown;
- `spec/API.md` for the public facade rows and removed/retiered spellings
  (including the `frame-provider` re-purpose and the dropped scope-only
  provider);
- `spec/Runtime-Subsystems.md` for the unified frame ownership of runtime
  subsystem state;
- `spec/Conventions.md` for the vocabulary distinctions if needed.

The registrar read grammar is NOT graduated by this wave — it is owned by
`rf2-10nggz` (which updates `spec/001-Registration.md` as needed).

### Implementation wave

Expected slices:

1. Add resolved generation to the unified frame value and route all per-frame
   reads through one registry.
2. Collapse `make-frame` to one constructor over image options plus frame
   configuration options — adopting option-(a) and removing the
   `rf2-32siq3.45` option-(b) fail-loud redirect; folds into `rf2-tu2vr7` (the
   make-frame backing collapse, which already owns `re-frame.live-frame`). The
   advanced `re-frame.frame/make-frame` becomes internal or disappears.
3. Migrate Story, tests, SSR, and examples off create-twice setup.
4. Implement the idempotent-replacement duplicate-id/hot-reload policy (behaviour
   change from fail-loud; preserve durable state on re-mount).
5. Add the UI-owned `frame-provider` boundary as a per-adapter affordance
   (Reagent / UIx / Helix) against the shared create/provide/destroy/idempotent
   contract.
6. Retier or remove frame-first operation arities, `frame-bound-fn`,
   `frame-bound-fn*`, `subscribe*`, and direct `make-frame-handle` exposure
   (move to internal namespaces; keep `frame-handle` public) — grounded by the
   0-caller backbone in `ai/findings/API-review/claude/frame-targeting-and-carrying.md`.
7. Remove the second live-frame registry and any teardown hook whose only job was
   keeping it coherent.
8. Run the docs guide-impact tail and final correctness/completeness review,
   per the EP wave-end standing rule.

The registrar frame-generation reads are out of scope for this wave — they are
owned by `rf2-10nggz`.

Existing beads that can land independently or be absorbed:

- `rf2-ts3fuk` can fix unsubscribe target normalization before this EP lands.
- `rf2-ntwwyt` can move HTTP test-support facade helpers independently.
- `rf2-az1ct6` should be absorbed if the unified frame value lands first.

## Open Issues

All open issues are resolved as of the 2026-06-18 acceptance (operator rulings on
`rf2-kz2vfp`, `rf2-uc6ebw`, `rf2-um1jcq`).

1. **Was the two-layer implementation deliberately transitional?**
   **Resolved:** treat it as unrealized collapse debt and converge now. The
   single unified `make-frame` adopts the deferred option-(a) and reverses the
   `rf2-32siq3.45` option-(b) fail-loud redirect (see §One constructor).

2. **What is the exact public accessor from frame value to frame id?**
   **Resolved:** provide one accessor frame-value → id; do not expose the
   representation.

3. **What is the final live frame representation?**
   **Resolved:** choose the smallest single-registry representation that lets the
   frame value own the resolved generation and lifecycle without reintroducing a
   second registry. It may be the existing record, a frozen handle over it, or an
   object wrapper only if the wrapper is the single registry value.

4. **Should public operations accept frame values, or only frame ids plus ambient
   context?**
   **Resolved:** teach frame **ids** as the routing address; frame values stay
   internal/tests-and-harnesses only and must not create a second public
   spelling.

5. **What duplicate-id policy should `make-frame` use?**
   **Resolved:** hot-reload-friendly idempotent replacement (preserving durable
   state on re-mount) with fail-loud irreconcilable conflicts. Flagged as a
   deliberate **behaviour change** from current fail-loud-on-every-live-id (see
   §Duplicate id policy).

6. **What is the final name for the UI-owned lifecycle boundary?**
   **Resolved:** `rf/frame-provider`, realized per-adapter (Reagent / UIx /
   Helix) against the shared create/provide/destroy/idempotent contract. The old
   scope-only `frame-provider` is dropped; scoping is `rf/with-frame` (see
   §Scope, carry, and ownership).

7. **Should frame-generation registrar reads be public, and what are their
   names?**
   **Resolved by scoping out:** the read grammar is owned and settled by
   `rf2-10nggz` (drop `:realm`, keep `:frame`). EP-0024 references it as the home
   and does not re-decide it; the prior fold-in-before-acceptance dependency is
   removed (see §Registrar and generation reads).

8. **Which helper spellings are removed vs retiered?**
   **Resolved:** keep `frame-handle` public; move `make-frame-handle`,
   `frame-bound-fn`, `frame-bound-fn*`, `subscribe*`, and the frame-first
   operation arities to internal namespaces. Grounded by the 0-caller backbone in
   `ai/findings/API-review/claude/frame-targeting-and-carrying.md`.

## Recommendation

Accepted 2026-06-18. This is the smallest durable decision surface that matches
the findings: one frame value, one registry, one target grammar, and one
explicit UI-owned lifecycle boundary (`rf/frame-provider`). That is the
post-EP-0023 frame model the public API already wants to teach. The
implementation wave is filed under this EP and gated on this acceptance.

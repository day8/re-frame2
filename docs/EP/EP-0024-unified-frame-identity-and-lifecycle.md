# EP-0024: Unified Frame Identity And Lifecycle

Status: proposal
Type: standards-track

> This EP proposes the post-EP-0023 frame cleanup: one live frame value backed by
> one registry, one public operation-target grammar, and one explicit UI-owned
> lifecycle boundary. Normative homes, if accepted, are `spec/002-Frames.md`,
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
- make view-owned frame lifetime explicit with one UI boundary that creates,
  provides, and destroys;
- keep `frame-provider` scope-only: it provides an already existing frame;
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
- **Related beads already filed**:
  - `rf2-ts3fuk` fixes unsubscribe target normalization symmetry.
  - `rf2-az1ct6` factors an internal frame-record resolver if not absorbed by
    this EP.
  - `rf2-ntwwyt` moves HTTP test-support helpers out of the core facade.
  - `rf2-10nggz` decides registrar query/read address grammar after EP-0023.

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

### Duplicate id policy

The accepted EP must choose one duplicate-id policy for `make-frame`.

The proposal recommends hot-reload-friendly idempotent replacement: re-evaluating
the same frame declaration should update frame configuration and resolved image
generation without destroying durable state unless the caller explicitly asks
for reset or destroy. Conflict cases that cannot be reconciled must fail loud.

The alternative is fail-loud duplicate refusal for every live id. That is
simpler, but it makes hot reload and Story re-evaluation more ceremonial.

### Operation target grammar

Frame-scoped operations put the primary datum first and route through an opts
map or ambient context.

Canonical explicit forms:

```clojure
(rf/dispatch [:todo/add "A"] {:frame :todo/left})
(rf/dispatch-sync [:todo/reset] {:frame :todo/left})
(rf/subscribe [:todo/items] {:frame :todo/left})
```

Canonical ambient forms:

```clojure
(rf/with-frame :todo/left
  (rf/dispatch [:todo/add "A"]))

[rf/frame-provider {:frame :todo/left}
 [todo-root]]
```

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
| Scope descendants to an existing frame | `frame-provider`, `with-frame` | Does not create or destroy the frame. Establishes context. |
| Carry a frame across async callback boundaries | `frame-handle` | Captures operations targeted at the current or explicit frame. |
| Own a frame lifetime | `make-frame` plus `destroy-frame!`, `with-new-frame`, and one UI-owned boundary | Creation and teardown are explicit ownership operations. |

The proposal adds one UI-owned boundary for view-created frames. The working name
is `owned-frame`.

Illustrative shape:

```clojure
[rf/owned-frame {:id :todo/left
                 :images [todo-image]
                 :initial-db {}}
 [todo-root]]
```

`owned-frame` creates the frame on mount, provides its frame id to descendants,
and destroys the frame on unmount. It is the answer for comparison pages, Story
canvases, embedded widgets, and hot-reload-safe view-owned frame lifetimes.

`frame-provider` remains scope-only:

```clojure
[rf/frame-provider {:frame :todo/left}
 [todo-root]]
```

This split keeps the user's question small:

- "I already have a frame; how do I scope children?" Use `frame-provider`.
- "This component owns a frame lifetime." Use `owned-frame`.
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

Registrar reads should name their data source. This EP reserves the right to
add public frame-generation reads if `rf2-10nggz` decides they are needed.

The source-store read remains a source-store read:

```clojure
(rf/registrations :event)
(rf/handler-meta :event :todo/add)
(rf/handler-ids :event)
```

Frame-generation reads, if public, must be separately named rather than hidden
behind retired target maps:

```clojure
(rf/frame-registrations :todo/left {:kind :event})
(rf/frame-handler-meta :todo/left {:kind :event :id :todo/add})
```

The exact names are open. The rule is not open: argument shape must not smuggle
"which data source am I reading?" through a retired composition map.

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

Keeping `frame-provider` scope-only also avoids a second ambiguity. A provider
that sometimes creates and sometimes scopes would make cleanup depend on how the
frame arrived. `owned-frame` says the ownership fact out loud.

## Backwards Compatibility

re-frame2 is pre-alpha. This EP does not require compatibility shims for
off-pattern public spellings. The migration is source-level:

- replace frame-first operation arities with event/query plus opts-map forms;
- replace view-created `make-frame` plus manual provider lifetimes with the
  accepted owned UI boundary;
- replace `frame-bound-fn` use with `frame-handle`;
- replace any create-twice frame setup with one `make-frame` call;
- replace retired registrar target maps with source-store reads or named
  frame-generation reads.

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

- `spec/002-Frames.md` for unified frame identity, target grammar, provider vs
  owned lifecycle, carry primitive, and teardown;
- `spec/API.md` for the public facade rows and removed/retiered spellings;
- `spec/001-Registration.md` if frame-generation registrar reads are accepted;
- `spec/Runtime-Subsystems.md` for the unified frame ownership of runtime
  subsystem state;
- `spec/Conventions.md` for the vocabulary distinctions if needed.

### Implementation wave

Expected slices:

1. Add resolved generation to the unified frame value and route all per-frame
   reads through one registry.
2. Collapse `make-frame` to one constructor over image options plus frame
   configuration options.
3. Migrate Story, tests, SSR, and examples off create-twice setup.
4. Implement the accepted duplicate-id/hot-reload policy.
5. Add the accepted UI-owned frame boundary.
6. Retier or remove frame-first operation arities, `frame-bound-fn`,
   `frame-bound-fn*`, `subscribe*`, and direct `make-frame-handle` exposure as
   decided.
7. Implement registrar frame-generation reads if accepted by `rf2-10nggz`.
8. Remove the second live-frame registry and any teardown hook whose only job was
   keeping it coherent.
9. Run the docs guide-impact tail and final correctness/completeness review,
   per the EP wave-end standing rule.

Existing beads that can land independently or be absorbed:

- `rf2-ts3fuk` can fix unsubscribe target normalization before this EP lands.
- `rf2-ntwwyt` can move HTTP test-support facade helpers independently.
- `rf2-az1ct6` should be absorbed if the unified frame value lands first.

## Open Issues

1. **Was the two-layer implementation deliberately transitional?**
   Recommendation: treat it as unrealized collapse debt and converge now.

2. **What is the exact public accessor from frame value to frame id?**
   Recommendation: provide one accessor and do not expose the representation.

3. **What is the final live frame representation?**
   Recommendation: choose the smallest representation that lets the frame value
   own the resolved generation and lifecycle without reintroducing a second
   registry. It may be the existing record, a frozen handle over it, or an object
   wrapper only if the wrapper is the single registry value.

4. **Should public operations accept frame values, or only frame ids plus ambient
   context?**
   Recommendation: teach frame ids as the app-facing target grammar. Direct
   frame values may remain accepted for tests/harnesses only if that does not
   create a second public spelling.

5. **What duplicate-id policy should `make-frame` use?**
   Recommendation: hot-reload-friendly idempotent replacement with fail-loud
   irreconcilable conflicts.

6. **What is the final name for the UI-owned lifecycle boundary?**
   Recommendation: `owned-frame`, because the name states the missing fact.

7. **Should frame-generation registrar reads be public, and what are their
   names?**
   Recommendation: decide in `rf2-10nggz`; if public names land, fold them into
   this EP before acceptance.

8. **Which helper spellings are removed vs retiered?**
   Recommendation: remove app-facing documentation for frame-first arities,
   `frame-bound-fn`, `frame-bound-fn*`, and `subscribe*`; keep any needed
   implementation helpers in internal namespaces with `*` names.

## Recommendation

Accept this EP after resolving the open issues. It is the smallest durable
decision surface that matches the findings: one frame value, one registry, one
target grammar, and one explicit owned UI lifecycle boundary. That is the
post-EP-0023 frame model the public API already wants to teach.

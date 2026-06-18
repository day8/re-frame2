# EP-0023: Images And Frame-Loaded Instruction Sets

Status: final
Type: standards-track

> This EP establishes `image -> frame -> event stream` as the public model for
> frame-loaded registration sets. It partially supersedes EP-0013's public
> app/realm surface while retaining the shipped realm machinery as an internal
> installation substrate. Normative homes: `spec/API.md`,
> `spec/001-Registration.md`, `spec/002-Frames.md`,
> `spec/Spec-Schemas.md`, `spec/Runtime-Subsystems.md`, and
> `spec/Conventions.md`.
>
> **Graduated 2026-06-16 (Mike-ruled via the `rf2-32siq3.32` decision).**
> EP-0023 is GRADUATED. The engine was verified flagship-quality and correct
> across three final reviews (resolution coherence, the fail-loud assembly
> lattice, cache coherence, hot reload, provenance elision; the conformance
> suite reports zero contract gaps). The public model `image -> frame -> event
> stream` ships with `rf/image` exported. Per the ruling, a sequenced
> post-graduation wave (the object-returning `make-frame` collapse, runnable-
> object unification, the facade export of object `make-frame` / `reload-images!`,
> reproject-on-`reg-*` wiring, standard-registry population, and the remaining
> caller migration) does **not** block graduation and is tracked under the
> `rf2-32siq3` epic. This EP partially supersedes EP-0013, whose status is now
> `superseded-by EP-0023`.

## Abstract

This EP proposes a smaller public model for the EP-0013 app/realm surface. It keeps the shipped realm machinery as the internal installation and ownership substrate, but teaches the public architecture as:

```text
image -> frame -> event stream
```

An **image** is the selected registration set a frame can resolve against: registrations, metadata, provenance, ownership claims, and framework standard registrations. A **frame** is the isolated execution context: app state, runtime subsystem state, queue, caches, traces, lifecycle, and a reference to the resolved image generation it is running. An **event stream** is the ordered sequence of events processed by that frame over time.

The goal is not to weaken EP-0013's isolation. The goal is to present the isolation at the smallest useful public boundary: target a frame, and that frame determines the image generation used for resolution.

## Goals / Non-Goals

Goals:

- introduce `rf/image` as the public value for selected registration sets;
- make every frame resolve registration lookups through its resolved image generation;
- retain the ordinary `reg-*` authoring path for the common single-surface case;
- let multiple frames on one page reuse readable registration ids without global clobbering;
- make tests, stories, SSR, docs pages, and Xray use the same image/frame model;
- keep frame state, host-transient handles, and behavior selection in separate places;
- preserve hot reload without rebuilding frame memory;
- make namespace-provenance selection fail loud rather than silently assembling incomplete images.

Non-goals:

- do not remove EP-0013's realm implementation machinery in this EP;
- do not require concurrent mixed rendering substrates on one page;
- do not rename EP-0001's `frame-state` value;
- do not introduce a second host-boundary abstraction beyond effects, coeffects, resources, and adapters;
- do not define a partial-image-member reload operation; `reload-images!` replaces a frame's whole image composition.

## Relationships

- **EP-0013** — partially superseded at the public app/realm surface. The realm
  container remains a valid implementation substrate; the public model becomes
  image-loaded frames. If this EP graduates, EP-0013's status should become
  `superseded-by EP-0023`, with this EP carrying the retained/superseded record.
- **Spec 001** — partially superseded for cross-namespace last-write-wins
  registration. Same-namespace hot reload replacement is retained; cross-
  namespace duplicate ids are retained in the source store and resolved or
  rejected at image assembly.
- **EP-0001** — provides the frame-state value vocabulary and durable
  app-db/runtime-db partition.
- **EP-0002** — provides explicit frame target resolution and the carried-frame
  invariant.
- **EP-0017** — provides the `:rf.cofx` envelope and recordable coeffect model
  used in examples and replay discussion.
- **EP-0018** — provides the single `reg-event` surface this EP assumes.
- **EP-0022** — provides registered interceptors and the standard
  `:rf.interceptor/path` invariant that standard replacement must not break.
- **EP-0009** — governs status bookkeeping. This EP uses supersession rather
  than post-implementation amendment because EP-0013 is final and shipped.
- **Target specs** — the graduated contract lives in `spec/API.md`,
  `spec/001-Registration.md`, `spec/002-Frames.md`, `spec/Spec-Schemas.md`,
  `spec/Runtime-Subsystems.md`, and `spec/Conventions.md`.

## Specification Summary

This section is the normative contract, without metaphor.

| Term | Contract |
|---|---|
| **Registration** | A named entry created by a `reg-*` form. It has a kind, id, metadata, implementation value, provenance, and optional ownership/capability facts. |
| **Image** | A value that selects registrations and optional inline descriptors into a candidate registration set for a frame. |
| **Resolved image generation** | The sealed, validated registration set a frame actually runs. It includes selected registrations, framework standard registrations, provenance, replacement decisions, and capability requirements. |
| **Frame** | A live isolated execution context. It owns app state, runtime subsystem state, queue/drain state, subscription cache, trace/history surfaces, lifecycle, adapter binding/configuration for the active substrate, capability map, host-transient leases, and a reference to one resolved image generation. |
| **Frame-state value** | The EP-0001 serializable projection of a frame: app-db, runtime-db, and exposed trace/epoch projections. It excludes adapter bindings/configuration, host handles, queues, live caches, and other non-serializable runtime objects. |
| **Event** | A vector delivered to a frame for processing. |
| **Event stream** | The ordered sequence of events processed by a frame over its lifetime. |

The resolution rule is:

```text
target frame -> resolved image generation -> registration resolution
```

The public target is a frame: usually a frame id in mounted code, sometimes a direct frame object in tests and harnesses. The old EP-0013 realm can remain as an internal installation substrate, but public dispatch, subscribe, and view scope should not require users to pass a separate `(realm, frame)` pair.

### Id Spaces

This EP has two public id spaces with different scopes:

| Id space | Examples | Scope | Rule |
|---|---|---|---|
| Registration ids | `:counter/inc`, `:cart/items`, `:counter/view` | Resolved image generation | Reusable across images. Within one sealed generation, `(kind, id)` must be unambiguous after selection and declared replacements. |
| Frame ids | `:counter/main`, `:docs.counter/basic-frame` | Process-local live-frame registry | Must be unique among live registered frames. Direct frame objects bypass this id space. |

This is the heart of the same-id story. Two images may both contain an event registration named `:counter/inc`; two live frames may not both be registered publicly as `:counter/main`. A documentation page can therefore reuse teaching-friendly registration ids across examples, but each mounted example still needs a distinct frame id.

Migration from EP-0013's `(realm, frame)` addressing must account for this. If two realms previously allowed the same public frame id, such as `:app/main`, to coexist, the image/frame surface requires either distinct frame ids or direct frame objects kept inside the local harness that created them.

## Motivation

The VM/ISA language in this section is explanatory, not normative. It is useful because it gives developers a compact mental model, but the contract above is the source of truth.

The analogy has one important limit. In a hardware ISA, the instruction set is fixed and programs are written against it. In re-frame2, the framework fixes only the cascade and the calling conventions; product registrations supply the application-specific meanings; the event stream supplies the run. So the precise mapping is:

```text
re-frame2 contract     -> cascade + registered-kind calling conventions
registrations          -> application-supplied operation meanings
image                  -> selected registration set loaded for a frame
frame                  -> isolated execution context
event stream           -> ordered inputs processed by that frame
```

With that caveat, the VM model is still an excellent teaching model, because it starts from the thing developers already see every day: events.

```text
event
  events are typically caused by the user as they interact with the UI,
  such as clicking a button
  they can also happen because of timers, WebSockets, HTTP responses, routers,
  machines, resources, and other external actors
  analogous to a single instruction that runs on a virtual machine
  across time, there is a stream of events: many instructions one after another

re-frame2
  re-frame processes each event through its six-domino cascade
  analogous to an instruction-set architecture (ISA) for a VM

frame
  analogous to a VM (virtual machine)
  executes one instruction/event after another
  provides an isolated execution context, with isolated state/memory
  create one for a test and tear it down, run several independent ones in a Story,
  or have two different frames on a single page running concurrently

registration
  you provide the VM's instruction set with reg-* forms such as reg-view,
  reg-event, reg-sub, reg-fx, reg-cofx, reg-resource, and so on

image
  a collection of your registrations, plus framework standard registrations
  a frame needs an image to process the stream of instructions/events

event stream
  across the lifetime of a frame, a sequence of events occurs
  this stream is analogous to a program executed by the VM
  the user's actions, timers, replies, and routers provide the program
  your registrations provide the instruction set that makes those events meaningful
```

That gives the short version:

> Events are instructions. A frame is the isolated VM that executes those instructions one after another. re-frame2 is the instruction-set architecture: it defines the six-domino cascade and the calling conventions for each registered kind. Registrations are the microcode entries. An image is the selected instruction set loaded for a frame. Across the lifetime of that frame, the event stream is the program being executed.

That is more precise than calling a registration set a `program`. A program is the thing that runs. In this model, what runs is the stream of events. The registration set is what has been loaded so those events can be interpreted.

The value story has two parts:

```text
image value       = the inspectable instruction set: registrations, metadata, provenance, ownership
frame-state value = the inspectable memory value: app-db, runtime-db, traces/epochs where exposed
```

The image explains what the frame can do. The frame-state value explains what has happened so far. Tools need both, but they are different values with different lifecycles. Confusing them is where most of the naming trouble starts.

## Terminology

This EP uses a small set of technical nouns:

| Term | Meaning |
|---|---|
| **re-frame2 processing contract** | The six-domino cascade plus the registered-kind categories and calling conventions. |
| **registration** | One named operation entry supplied with a `reg-*` form. |
| **image** | A selected registration-set value: registrations plus their metadata, provenance, ownership, and capability facts. |
| **resolved image generation** | The sealed validated registration set a frame resolves against while it runs. |
| **frame** | The live isolated execution context: state, queue, caches, traces, lifecycle, active-substrate adapter binding/configuration, capability map, host-transient leases, and the resolved image generation. |
| **frame-state value** | The EP-0001 serializable projection of a frame. |
| **event** | A vector delivered to the frame for processing, such as `[:counter/inc]`. |
| **event stream** | The ordered sequence of events processed across a frame's lifetime. |

Avoid using `app`, `application`, `realm`, or `module` as new conceptual nouns in this model. Also avoid using `program` for registration sets; in this EP, `program` means the event stream being executed.

There are two exceptions:

- `app-db` is an existing re-frame term for application-owned durable state.
- Existing APIs and documents use names such as `rf/app`, `app value`, and `realm`; this EP maps those names later, but does not treat them as the preferred explanatory vocabulary.

When ordinary English is needed, say "the product", "the page", or "the example." Keep the technical model on registration, image, frame, event, and event stream.

## Specification

### Registration

A registration is one named operation entry that can be selected into an image generation.

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _]
    {:db (update db :count (fnil inc 0))}))

(rf/reg-sub :counter/value
  (fn [db _]
    (:count db 0)))
```

Those two forms do not run anything yet. They define entries the frame can resolve later, when an event or query arrives:

```text
:counter/inc    event registration for [:counter/inc]
:counter/value  subscription registration for [:counter/value]
```

The event vector is `[:counter/inc]`. The `:counter/inc` registration is the entry the frame resolves to process that event.

The re-frame2 processing contract defines the registration categories and their calling conventions:

- event handlers receive coeffects and return effects;
- subscriptions derive values;
- effects perform commands;
- coeffects provide recorded input facts;
- interceptors transform execution;
- resources, routes, machines, flows, views, and schemas each have registered semantics.

Your product supplies concrete registration entries for those categories.

### Image

An image is a registration-set value.

An image can be:

- the registration set for a whole surface;
- a registration set shipped by a library;
- a small test or story registration set;
- a registration set assembled from other images.

Before a frame runs, one or more image values are resolved into a single sealed image generation.

An image value is not always a self-contained list of registrations. An image that uses `:include-ns` is a query over the currently loaded registration source store. The inspectable, sealed registration set is the **resolved image generation** produced by running that query, adding framework standard registrations, checking capabilities and collisions, and sealing the result.

Mechanically:

```text
resolved image generation
  = framework standard registrations
  + registrations selected by one or more image values
  + metadata/provenance/ownership attached to those registrations
```

An image is not state. It is not the running object. It is not the event stream.

It answers one question:

```text
Which registrations are visible to this frame?
```

An image may also declare the capabilities required by those registrations. That is still an instruction-set fact, not a live host handle. For example, an image can say that it requires browser DOM rendering, an HTTP transport, or a storage capability. The check happens when the frame's resolved image generation is assembled against the frame's host capability map, before the frame runs any event.

Most code should not have to name an image. Explicit images are for the cases where the default visible registration set stops being enough.

Resolved generations are immutable. The runtime may physically share one resolved generation across many frames when the same image inputs resolve to the same descriptor set. That sharing is an implementation optimization, not a semantic coupling between frames. Two frames that run the same generation still have independent `app-db`, `runtime-db`, queues, caches, traces, lifecycle, adapter binding/configuration, and host-transient leases.

The reference implementation MUST cache resolved generations. The minimum cache key is:

```text
normalized :images vector
+ registration source-store generation
+ framework-standard registration generation
+ inline descriptor fingerprints
+ declared replacement maps
```

If that key is equal, the cached sealed generation object is reused. This matters most for SSR: a request-scoped frame should not re-run namespace glob selection, validation, and sealing on every request when the selected image inputs and source store have not changed. An implementation may use a finer descriptor-set fingerprint so unrelated source-store changes do not invalidate a generation unnecessarily, but it must not use a coarser key that reuses a generation after any selected descriptor, standard descriptor, inline descriptor, or replacement decision changed.

Reload is frame-targeted. Reloading one frame swaps the resolved generation that frame runs; it does not mutate the old generation and it does not force sibling frames that previously shared the old generation to move. If a frame was created from multiple images, reload replaces the frame's whole image composition with a new `:images` vector. This EP deliberately does not specify a "replace only the second member of the vector" operation for v1.

### Frame

A frame is the live execution context that runs one resolved image generation.

The live frame object owns:

- `app-db`;
- `runtime-db`;
- event queue and drain state;
- subscription cache;
- current control-flow state;
- per-frame traces;
- per-frame epoch/history surfaces;
- lifecycle state;
- a reference to the resolved image generation it is running;
- the host adapter binding/configuration used by this frame;
- the host capability map checked against the image's requirements;
- frame-owned host-transient subsystem handles and leases, such as timers, DOM roots, in-flight requests, abort handles, and similar non-serializable runtime objects.

A frame is the live execution context. Most product code targets a frame by id:

```clojure
(rf/dispatch [:counter/inc] {:frame :counter/main})
@(rf/subscribe :counter/main [:counter/value])
```

A local harness can also use the frame object directly, which is useful in tests where the frame is created, used, and discarded in one scope:

```clojure
(let [frame (rf/make-frame
              {:images [counter-image]
               :initial-db {:count 0}})]
  (rf/dispatch-sync frame [:counter/inc])
  @(rf/subscribe frame [:counter/value]))
```

The serializable state projection of a frame remains separate from the live frame object:

```clojure
(rf/frame-state-value frame)
;; =>
{:rf.db/app     {:count 1}
 :rf.db/runtime {...}}
```

A frame id names a running context in a registry. A frame object is a direct local reference to one. The frame-state value is the data projection you can serialize, replay, restore, diff, or inspect.

The distinction matters. A **frame object** is live and may hold host handles. A **frame-state value** is serializable and must not contain those handles:

```text
frame object
  app-db atom / runtime-db atom / queue / caches
  resolved image generation
  adapter binding/configuration
  capability map
  host-transient subsystem leases

frame-state value
  serializable app-db projection
  serializable runtime-db projection
  serializable trace/epoch projections where exposed
```

If a frame is created with an id, that id is registered in the process-local live-frame registry. The public uniqueness contract is therefore frame-id uniqueness within that live registry. Direct frame objects bypass the registry and are appropriate for local tests and harnesses. Do not confuse this with registration ids: `:counter/inc` can be reused across images; `:counter/main` cannot name two live registered frames at once.

### Event Stream

An event is a vector delivered to a frame:

```clojure
[:counter/inc]
[:cart/add "SKU-1"]
[:article/load {:slug "hello"}]
```

The event stream is the ordered sequence of events processed by that frame:

```text
[:counter/inc]
[:counter/inc]
[:cart/add "SKU-1"]
[:article/load {:slug "hello"}]
```

That is why `program` should be reserved for the stream, not the registration set.

## Rationale

An image is worth naming because it is the behavior boundary a frame runs against. It lets us say which registrations are visible without also talking about state.

The useful cases are concrete:

1. keep the ordinary `reg-*` style for the common path;
2. run two independent products or examples on one page, such as a todo surface and a counter surface;
3. run the thing being inspected and Xray on the same page without id collisions;
4. support documentation pages with several versions of the same example, progressively building up capability;
5. select registrations from multiple namespaces without relying on one global visible set;
6. create isolated test and story frames from a known registration set;
7. inspect ownership, dependencies, registrations, and docs before anything runs;
8. hot-reload by swapping one sealed image generation for another;
9. package reusable feature slices without giving them live state;
10. make migration and tooling work from data instead of process-global mutation.

The headline cases depend on one prerequisite: **per-frame live registration resolution**. Dispatch, subscribe, fx, cofx, view, resource, and related lookups must resolve through the targeted frame's resolved image generation. Static install/query isolation is not enough. If a target branch still treats a constructed realm as install/query-isolated but routes live dispatch through a global/default registrar, this EP's examples do not work there. Landing or retaining the a15n62-equivalent live-resolution slice is in scope for this proposal and is a graduation blocker.

## Use Cases

The important split is:

```text
image = the behavior available to run
frame = the memory accumulated while running it
```

In functional-programming terms, the image supplies the functions and calling conventions. The event stream supplies the inputs. The frame-state value is the accumulator after some prefix of that stream has been folded. That gives a useful everyday rule:

```text
same behavior, different history -> same image, different frames
different behavior               -> different images
```

### Ordinary Product

Most products should not start by constructing images. They should keep using `reg-*` forms. The default registration source and default image path exist so the common case remains direct:

```clojure
(rf/reg-event :counter/inc ...)
(rf/reg-sub :counter/value ...)
```

The image concept is still there, but it is implicit. It becomes visible only when the default process-wide registration set stops being the right boundary.

### Same Behavior, Many Instances

If two running examples should behave the same way but hold different state, use one image and two frames.

```clojure
(def left-frame-id :counter/left)
(def right-frame-id :counter/right)

(rf/make-frame
  {:id left-frame-id
   :images [counter-image]
   :initial-db {:count 0}})

(rf/make-frame
  {:id right-frame-id
   :images [counter-image]
   :initial-db {:count 10}})
```

This is the docs/story/test case where the behavior is the same, but each running instance has its own memory.

### Documentation Pages

A guide page can show progressive versions of the same example: basic state update, derived odd/even tag, timestamped write, recordable coeffect, managed async load. Each lesson version gets its own image. Each live mounted example gets its own frame.

This is the main "different behavior, same ids" use case. Every lesson version can keep the same teaching vocabulary:

```clojure
:counter/inc
:counter/value
:counter/view
```

Those ids can mean "basic counter" in one image and "counter with coeffects" in another image. The ids stay readable because the image supplies the meaning. The page teaches one evolving idea instead of making every lesson invent globally unique names.

### Xray Beside The Target

Xray is itself a running surface with registrations, state, views, subscriptions, and effects. It should not have to share the target's registration set. Run Xray in its own image/frame and let it inspect the target frame as data.

That keeps the inspection tool from becoming part of the thing being inspected.

### Library Packaging

Later, libraries can ship image values. A product can then compose them with its own image:

```clojure
(rf/make-frame
  {:id :docs/main
   :images [widgets-image
            routing-image
            product-image]})
```

The frame still runs one resolved image generation. The vector is just the assembly input.

### Tests And Stories

Tests and stories need controlled behavior and controlled state. Images give the behavior side; frames give the state side.

Use another frame for a state variant. Use another image for a behavior variant. That is the small rule that keeps test setup from becoming global registry mutation.

### SSR

SSR creates a short-lived frame for a request, drives it with route/request events, renders through that frame, and serializes the resulting frame-state value. The image does not cross the wire; the state projection does.

### Hot Reload

Hot reload should resolve a new image generation while keeping the frame alive. The code changes; the VM keeps its memory. That is the constraint that makes this design useful during development.

## Default Image Semantics

The most common path must stay boring.

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _]
    {:db (update db :count (fnil inc 0))}))

(rf/reg-sub :counter/value
  (fn [db _]
    (:count db 0)))
```

This still works exactly as expected. A developer does not have to name, construct, or install an image just to build a normal product.

Conceptually:

```text
reg-* mutates the default registration source
the default image is the implicit selector over that source
frame creation resolves the selector into a sealed image generation
```

Read "default image" as a runtime projection, not as a hand-authored value. `reg-*` stays incremental and hot-reload friendly while frame execution still sees a sealed image generation.

That is the important phase split:

```text
source registrar      mutable during authoring / hot reload
image selector        describes which registrations to select
image generation      sealed snapshot a frame resolves against
```

A sealed generation does not mutate in place. When `reg-*` changes the default registration source, the default image selector can be re-projected into a new generation. Frames that run the default image then move from the old generation to the new one through the same reload path explicit images use. That is what "additions become visible without global reset" means:

```text
reg-* adds or replaces a registration
default image projection is dirty
runtime resolves a new sealed generation
affected frames swap to that generation
frame memory is preserved
```

In production, the source registrar normally stops changing after boot, so the default path feels sealed for the lifetime of the frame. In development, the source can change, but each frame still resolves against one sealed generation at a time.

The default path therefore assumes globally unique ids across the loaded default source. If loaded namespaces intentionally reuse the same `(kind, id)` with different meanings, the default image is the wrong boundary. Use explicit images with selectors that keep each frame's resolved generation id-disjoint.

The dirtying rule is not default-image-only. Any source-store change invalidates resolved generations whose image selectors might include the changed source slot: default images, explicit `:include-ns` images, and composed images containing them. A docs page with three explicit counter images should therefore hot-reload the parity frame when `docs.quickstart.counter.parity` re-registers. `reload-images!` is for changing a frame's image composition; ordinary source re-registration reprojects the same image inputs and swaps affected frames automatically.

## Namespace-Selected Images

The bridge from ordinary `reg-*` code to explicit images is source provenance.

### Registration Source Store

`reg-*` must not write only to a final `(kind, id) -> descriptor` resolver map. That would clobber provenance-distinct registrations before images have a chance to select between them. Instead, `reg-*` writes to a provenance-preserving **registration source store**.

The source store keeps one current descriptor per source slot:

```text
source slot = [kind id provenance-namespace]
```

That gives the needed behavior:

```text
same kind + id + same namespace       -> replacement in that source slot
same kind + id + different namespace  -> both descriptors retained
```

The first case is the ordinary hot-reload path: a namespace reloads and replaces its own registration. The second case is the image-isolation path: two surfaces can both register `:boot/init` or `:counter/view` without clobbering each other at registration time.

This partially supersedes Spec 001's current last-write-wins registrar rule. It retains the same-namespace hot-reload replacement contract: save a file, re-evaluate that namespace, replace that namespace's source slot, and let in-flight work finish against the already-resolved handler. It supersedes cross-namespace silent replacement: if two namespaces register the same `(kind, id)`, both descriptors remain in the source store and any image selecting both fails assembly unless it declares an exact replacement winner. That resolves the collision-masking problem Spec 001 already documents for silent last-write-wins.

Image assembly is the projection step:

```text
registration source store
  -> select descriptors by image selectors
  -> validate same-kind same-id collisions
  -> build sealed resolver map keyed by [kind id]
```

The sealed resolver map is what the frame uses at runtime. The source store is not itself the runtime resolver.

This also defines the default path. The default image is the implicit selector over all descriptors in the default source store. It works only while selected ids are globally unique across the loaded namespaces. If two loaded namespaces both register the same `(kind, id)`, the default image does not guess and does not let load order win; default image assembly fails with a collision error. A product that intentionally wants same ids with different meanings must create explicit images with disjoint selectors.

Each `reg-*` macro can stamp the registration's namespace as a string:

```clojure
{:kind :event
 :id :counter/inc
 :rf.provenance/ns "docs.quickstart.counter.v2"
 :file "src/docs/quickstart/counter/v2.cljs"
 :line 17
 :column 1
 :meta {:doc "Increment the counter."}
 :impl <fn>}
```

That namespace stamp is part of the production registration descriptor, not optional debug metadata. If production images are assembled with `:include-ns`, production builds must retain namespace provenance for every `reg-*` entry. Tools may omit file, line, column, and doc metadata in size-sensitive builds if the selected profile allows it, but namespace provenance must survive or image assembly cannot work.

That is an intentional coupling cost. Namespace layout becomes part of the image-selection contract. Renaming a namespace can change image membership, just as moving a public var can break a caller. This is the price of the convenient glob path. If a library or product does not want namespace layout to be part of its public surface, it should publish explicit image values or inline descriptors rather than asking downstream code to select its internals by namespace glob.

### Relationship To The Earlier Tag-And-Scan Rejection

This EP deliberately reopens the earlier rejection of "tag-and-scan" module assembly, but only for a narrower mechanism.

The rejected shape was weak because tags behaved like incidental metadata over already-mutated global state. That loses the important properties module manifests were trying to buy: composition before installation, collision detection before anything runs, and robustness under advanced compilation.

The image design keeps the convenience but changes the mechanics:

- provenance is a production descriptor field, not debug metadata;
- `reg-*` writes to a provenance-preserving source store, not directly to a clobbering runtime resolver;
- image assembly is an explicit phase that selects descriptors, checks zero-match globs, validates collisions, checks capabilities, and seals a generation before a frame runs;
- selectors do not load code and therefore do not defeat dead-code elimination;
- explicit image values and inline descriptors remain available when namespace layout should not be part of a public contract.

So this is not "scan whatever is currently global and hope." It is descriptor selection followed by fail-loud assembly. That is a real revision of the earlier conclusion: tag-and-scan was wrong as an unvalidated global-registry shortcut; provenance selection is acceptable when it is a production descriptor contract feeding sealed image assembly.

The logical and descriptor-level contract is `:rf.provenance/ns` as a canonical string. That string is part of the production descriptor shape, but it should not become a production-size tax where every `reg-*` expansion embeds and allocates its own copy.

The implementation requirement is:

```text
one namespace string value per source namespace per registration source store,
not one independently allocated namespace string per registration descriptor
```

The public descriptor still carries the namespace string:

```clojure
{:kind :event
 :id :counter/inc
 :rf.provenance/ns "docs.quickstart.counter.v2"
 :impl <fn>}
```

How the implementation achieves that is not public API. A CLJS implementation can hoist the namespace string into a namespace-local constant, call a registrar-scoped canonicalization helper, rely on a host/compiler string-pooling guarantee, or combine those techniques. The observable contract is only that tools read a string at `:rf.provenance/ns`, equality is ordinary string equality, and production builds do not pay avoidable per-registration duplication for the same namespace. Do not expose numeric namespace ids or a second descriptor key to solve this; that would save bytes by leaking an implementation table into the public model.

Then an image can select registered entries from known namespaces with one glob-based selector:

```clojure
(def counter-v2-image
  (rf/image
    {:id :docs.counter/v2
     :include-ns ["docs.quickstart.counter.v2"]}))
```

Most image selectors will be globs:

```clojure
(def counter-page-image
  (rf/image
    {:id :docs.counter/page
     :include-ns ["docs.*.counter.*"
                  "docs.shared.widgets.*"]}))
```

The selector does not load code. Namespaces must already be loaded through normal `ns` / build-tool dependency mechanisms. The selector only chooses from registrations the runtime already knows about.

That is a production constraint, not just an implementation detail. Image selection must not defeat dead-code elimination. If a production entrypoint does not require a namespace, the compiler can still remove it; an `:include-ns` glob will simply find no registrations from that namespace. If a product creates and requires a "register everything" namespace, that product has intentionally retained those features. The image mechanism should not retain them by itself.

Zero matches are fail-loud by default. During image assembly, each `:include-ns` pattern MUST match at least one registration descriptor, or assembly fails with an error that names the image id, the pattern, and the loaded provenance namespaces considered. That keeps typos, forgotten `require`s, DCE surprises, and stale namespace names from producing a silently incomplete image.

If a feature is genuinely optional or platform-conditional, do not hide that behind an empty glob. Assemble the image conditionally in host code, or publish a separate image value for that optional feature.

Use one spelling:

```clojure
:include-ns [<namespace-glob> ...]
```

Selection is by the registration's source code namespace, recorded in `:rf.provenance/ns`. It is not by the registration id namespace. A registration with id `:counter/inc` is selected because it was authored in `"docs.quickstart.counter.v2"`, not because the keyword starts with `counter`.

The namespace-glob language is intentionally small:

```text
namespace        = dot-separated Clojure namespace string
pattern          = segment-pattern ( "." segment-pattern )*
segment-pattern  = literal segment | intra-glob segment | "*" | "**"
"*"              = exactly one dot-free namespace segment
"**"             = zero or more namespace segments
intra-glob       = a segment carrying one or more `*`, each matching
                   zero or more characters WITHIN that one segment
match            = case-sensitive, whole-namespace match
```

A pattern either matches the whole `:rf.provenance/ns` string under the segment rules or it does not match. The only substring matching is the intra-segment `*`: it is bounded to ONE segment (the `.` separators still delimit segments; only `**` crosses them), so `"*-cljs-test"` matches the leaf segment of `"app.feature.mount-cljs-test"` but never spans a `.`. There is no regex mode exposed to callers.

Exact inclusion is just a pattern with no wildcard:

```clojure
:include-ns ["docs.quickstart.counter.v2"]
```

Prefix inclusion is a normal glob:

```clojure
:include-ns ["docs.shared.widgets.*"]
```

That matches `docs.shared.widgets.button`, but it does not match `docs.shared.widgets` and it does not match `docs.shared.widgets.forms.input`.

If recursive matching is needed, reserve `**` for that:

```clojure
:include-ns ["docs.shared.**"]
```

That matches `docs.shared`, `docs.shared.widgets`, and `docs.shared.widgets.forms.input`.

A recursive `**` glob sometimes sweeps in sibling namespaces a frame must not load — most often a feature's own `*-test` namespaces, which in a dev/test build co-register the same ids the production sources do (an image that selects both fails assembly with a duplicate-id collision). The subtractive `:exclude-ns` selector narrows the `:include-ns` selection by provenance namespace:

```clojure
(rf/image
  {:id         :rf.xray/image
   :include-ns ["day8.re-frame2-xray.**"]
   :exclude-ns ["day8.re-frame2-xray.**.*-cljs-test"
                "day8.re-frame2-xray.test-helpers.**"]})
```

A descriptor selected by `:include-ns` is dropped if its `:rf.provenance/ns` also matches an `:exclude-ns` glob (same grammar, including the intra-segment `*`). `:exclude-ns` applies ONLY to the glob-selected registered descriptors, never to an image's inline `:registrations` (those are selected by image membership, not by provenance). Unlike `:include-ns`, an `:exclude-ns` pattern that matches nothing is NOT fail-loud — exclusion is a defensive guard, so a pattern guarding against a namespace that simply is not loaded in this build is a no-op. Production builds that never load the excluded namespaces get a no-op exclude; dev/test builds get the collision-free narrowing.

Image assembly then:

```text
1. collect the requested image values;
2. select matching registrations from each image value;
3. fail if any include-ns pattern matched nothing;
4. add framework standard registrations;
5. validate collisions, replacements, capabilities, and references;
6. seal the result into an image generation;
7. give the frame that sealed image generation.
```

Strings are easier for tools, JSON, errors, and cross-host implementations than quoted namespace symbols. One selector key is also easier to teach than exact, prefix, and glob variants that all describe the same namespace-provenance filter.

## Image Composition

Library authors may eventually ship image values. A product can then build a frame from its own image plus library images:

```clojure
(def widgets-image
  (rf/image
    {:id :lib/widgets
     :include-ns ["lib.widgets.**"]}))

(def routing-image
  (rf/image
    {:id :lib/routing
     :include-ns ["lib.routing.**"]}))

(def docs-page-image
  (rf/image
    {:id :docs/page
     :include-ns ["docs.page.**"]}))

(rf/make-frame
  {:id :docs/main
   :images [widgets-image
            routing-image
            docs-page-image]})
```

The frame still runs one resolved image generation. `:images` is just the assembly input, and it is the only spelling. Even a single image is supplied as a one-element vector:

```clojure
(rf/make-frame {:id :docs/example
                :images [docs-page-image]})
```

Image composition should be deterministic and collision-checked. Order must not silently decide which registration wins. If two input images provide the same `(kind, id)` with different implementations, assembly fails unless the composing image explicitly declares a replacement winner with `:replace` or `:replace-standard`. That keeps composition data-oriented: the value says what it contains and which descriptor intentionally survives.

## Independent Surfaces On One Page

A page may host two unrelated surfaces:

```text
todo surface
counter surface
```

Both should be able to use simple local ids:

```clojure
:boot/init
:item/add
:view/root
```

With one visible resolver map keyed only by `(kind, id)`, those ids would collide or clobber at registration time. This EP requires a provenance-preserving source store instead, so both descriptors can exist until image assembly decides which one a frame will run.

The default image still cannot express this case. If both namespaces are loaded and both register the same `(kind, id)`, the default image fails assembly with a collision. Same-id, different-meaning surfaces must use explicit images with disjoint selectors:

```clojure
(def todo-image
  (rf/image
    {:id :examples/todo
     :include-ns ["examples.todo.**"]}))

(def counter-image
  (rf/image
    {:id :examples/counter
     :include-ns ["examples.counter.**"]}))

(def todo-frame-id :todo/main)
(def counter-frame-id :counter/main)

(rf/make-frame {:id todo-frame-id
                :images [todo-image]})

(rf/make-frame {:id counter-frame-id
                :images [counter-image]})
```

Each frame runs a different resolved image generation. The ids are local to that image, so the examples can use small, readable names.

## Xray Beside The Target

Xray has its own registrations.

A development page may need:

```text
target image
Xray image
target frame
Xray frame
```

The target and Xray should not have to coordinate event ids, subscription ids, view ids, interceptors, resources, or `app-db` paths. Xray can run its own image while inspecting another frame:

```clojure
(def target-frame-id :target/main)
(def xray-frame-id :xray/main)

(rf/make-frame {:id target-frame-id
                :images [target-image]})

(rf/make-frame
  {:id xray-frame-id
   :images [xray-image]
   :initial-db {:xray/target target-frame-id}})
```

Conceptually:

```text
Xray runs in its own frame.
Xray inspects the target frame.
```

That is cleaner than making Xray registrations share the target registrar and hoping ids never collide. The tool remains a separate surface, and the target remains ordinary data from the tool's point of view.

## Documentation Pages With Progressive Examples

Documentation pages are the clearest high-pressure example.

One guide page may show several versions of a counter:

```text
counter v1: plain event + subscription
counter v2: odd/even derived value
counter v3: timestamped writes
counter v4: recordable coeffects
```

All versions should be allowed to use teaching-friendly ids:

```clojure
:counter/inc
:counter/value
:counter/view
```

The page should not have to rename every registration id to include the lesson number. The difference between examples is the image, not the registration-id vocabulary. The frame ids still differ, because they live in the process-local live-frame registry.

```clojure
(def counter-basic
  (rf/image
    {:id :docs.counter/basic
     :include-ns ["docs.quickstart.counter.basic"]}))

(def counter-with-parity
  (rf/image
    {:id :docs.counter/parity
     :include-ns ["docs.quickstart.counter.parity"]}))

(def counter-with-cofx
  (rf/image
    {:id :docs.counter/cofx
     :include-ns ["docs.quickstart.counter.cofx"]}))

(def basic-frame-id :docs.counter/basic-frame)
(def parity-frame-id :docs.counter/parity-frame)
(def cofx-frame-id :docs.counter/cofx-frame)

(rf/make-frame {:id basic-frame-id
                :images [counter-basic]
                :initial-db {:count 0}})

(rf/make-frame {:id parity-frame-id
                :images [counter-with-parity]
                :initial-db {:count 0}})

(rf/make-frame {:id cofx-frame-id
                :images [counter-with-cofx]
                :initial-db {:count 0}})
```

The same local ids are safe because each frame is running a specific image. The example stays pedagogical: readers see the same small vocabulary evolve instead of a naming scheme.

## Image Fragments

Feature-sized groups of registrations are image fragments.

A feature namespace can simply register entries:

```clojure
(ns shop.cart)

(rf/reg-event :cart/add
  {:doc "Add a SKU to the cart."
   :rf.module/owns {:app-db [[:cart/items]]}}
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :cart/items (fnil conj []) sku)}))

(rf/reg-event :cart/clear
  {:doc "Clear the cart."}
  (fn [{:keys [db]} _]
    {:db (assoc db :cart/items [])}))

(rf/reg-sub :cart/items
  {:doc "Cart SKUs."}
  (fn [db _]
    (:cart/items db [])))
```

Another namespace can register another slice:

```clojure
(ns shop.auth)

(rf/reg-event :auth/set-user
  {:doc "Set the signed-in user."
   :rf.module/owns {:app-db [[:auth/user]]}}
  (fn [{:keys [db]} [_ user]]
    {:db (assoc db :auth/user user)}))

(rf/reg-sub :auth/user
  {:doc "Current signed-in user."}
  (fn [db _]
    (:auth/user db)))
```

The ownership key above intentionally uses EP-0013's existing `:rf.module/owns` spelling. This EP does not introduce a new ownership-key family; if that spelling is later cleaned up, it should happen as a separate compatibility-aware naming change.

The larger image can include both namespace regions:

```clojure
(def shop-image
  (rf/image
    {:id :shop/main
     :include-ns ["shop.auth.**"
                  "shop.cart.**"
                  "shop.checkout.**"]}))
```

This avoids forcing every feature to author a separate map just to participate in an image. The registration calls already carry the entries; provenance selectors group them into the image that needs them.

There can still be explicit inline image entries for generated code, tests, or library packaging:

```clojure
(def small-image
  (rf/image
    {:id :test/small
     :registrations
     {:reg-event
      [[:counter/inc
        {:doc "Increment."}
        (fn [{:keys [db]} _]
          {:db (update db :count (fnil inc 0))})]]

      :reg-sub
      [[:counter/value
        {:doc "Current counter value."}
        (fn [db _]
          (:count db 0))]]}}))
```

That should be an option, not the only way to build an image. Most human-authored code should stay close to ordinary `reg-*` forms.

When inline entries are used, prefer registrar-keyed sections that mirror the public `reg-*` names: `:reg-event`, `:reg-sub`, `:reg-interceptor`, `:reg-view`, `:reg-fx`, `:reg-cofx`, `:reg-resource`, and so on. Handler entries should be call-shaped tuples:

```clojure
[id metadata body]
```

Metadata-only entries can use:

```clojure
[id metadata]
```

The important rule is that image entries are descriptions, not secretly executed calls. Do not make a macro body full of fake `reg-*` forms. Do not scan vars at runtime to infer an image. Either use ordinary `reg-*` forms and select by recorded provenance, or provide explicit registrar-keyed data. Both paths should lower to the same runtime descriptor shape.

Inline descriptors do not enter the provenance source store and are not selected by `:include-ns`. They are selected because their containing image value was supplied. They still need a descriptor source coordinate so errors and replacements can name them:

```clojure
{:kind :event
 :id :counter/inc
 :rf.provenance/image :test/small
 :rf.provenance/inline [:reg-event :counter/inc]
 :impl <fn>}
```

A registered descriptor is identified by `{:ns "some.source.ns"}`. An inline descriptor is identified by `{:image :test/small :inline [:reg-event :counter/inc]}`. Framework standard descriptors are identified by `{:standard true}` plus their `(kind, id)`. Image assembly collision checks still happen by `(kind, id)` after all selected registered descriptors and inline descriptors are collected. If an inline `:counter/inc` and a namespace-selected `:counter/inc` are both selected, that is the same collision class as two namespace-selected descriptors and requires an exact replacement winner.

If an inline descriptor needs to be named as a replacement winner, its containing image MUST have an `:id`; otherwise there is no stable source coordinate to put in the replacement map. Anonymous inline images remain valid for local tests and examples when they do not participate in replacement.

## Image Validation

Image assembly should validate the sealed set before a frame runs it. That is the advantage of making the registration set a value: the framework can inspect it before any event touches state.

Every resolved image generation must be id-disjoint by `(kind, id)` after selection and declared replacements. The source store may retain provenance-distinct descriptors with the same `(kind, id)`; a sealed generation may not resolve ambiguously. This rule applies to:

- the default image, which selects the whole default source;
- explicit images with `:include-ns`;
- broad globs such as `["examples.*"]` that accidentally select two surfaces defining the same local ids;
- composed images.

Useful checks:

```text
same kind + id selected twice with same source/impl -> dedupe or ok
same kind + id selected twice with different impl    -> image assembly error
replace winner names no selected descriptor          -> image assembly error
replace names a key with no actual collision         -> image assembly error
include-ns pattern matched zero descriptors          -> image assembly error
event references missing interceptor                -> image assembly error
resource references missing scope resolver           -> image assembly error
ownership declarations overlap unexpectedly          -> image assembly warning/error
unsupported registration kind in image path          -> image assembly error
```

That is the practical payoff. A bad image should fail while it is being assembled, not halfway through a user interaction.

## Image Patching And Overrides

Tests and stories often need a small behavior change:

- use a fake HTTP effect;
- replace a coeffect supplier;
- swap an interceptor;
- run with a narrower route table;
- install a diagnostic view.

Those are image changes. They should produce another image generation, not mutate a process-global registrar under the running frame.

```clojure
(def checkout-test-image
  (rf/image
    {:id :checkout/test
     :include-ns ["checkout.core.**"
                  "checkout.test-doubles.**"]}))

(let [frame (rf/make-frame
              {:images [checkout-test-image]
               :initial-db {:cart/items []}})]
  (rf/dispatch-sync frame [:cart/add "SKU-1"]))
```

State setup remains a frame concern: use `:initial-db`, restore a frame-state value, or dispatch setup events. Behavior setup is an image concern: select or override registrations before the frame runs.

If an override replaces an existing `(kind, id)`, image assembly should name both sources and require an explicit override form. Silent last-writer-wins would reintroduce the global-registrar failure mode with a more modern API shape.

The override form must identify the winner. A set of `(kind, id)` pairs is not enough, because it says a collision is intentional without saying which descriptor survives.

There are two replacement cases.

Application-owned replacement is normal test/story composition:

```clojure
(rf/image
  {:id :checkout/story
   :include-ns ["checkout.core.**"
                "checkout.story.**"]
   :replace {[:fx :checkout.http/post]
             {:ns "checkout.story.http"}}})
```

That says the image deliberately replaces the selected `:checkout.http/post` effect registration and that the descriptor authored in `"checkout.story.http"` is the survivor. The real descriptor from `checkout.core` is still selected, so assembly can prove this was a real collision and produce a useful replacement report.

For inline winners, use the inline source coordinate:

```clojure
(rf/image
  {:id :checkout/story
   :include-ns ["checkout.core.**"]
   :registrations
   {:reg-fx [[:checkout.http/post
              {:doc "Story HTTP recorder."}
              (fn [request] (swap! story-http-log conj request))]]}
   :replace {[:fx :checkout.http/post]
             {:image :checkout/story
              :inline [:reg-fx :checkout.http/post]}}})
```

Replacement declarations are fail-loud:

- the `(kind, id)` must have an actual selected collision;
- the winner source coordinate must identify exactly one selected descriptor for that `(kind, id)`;
- a stale winner source, a typo, or a replacement declaration for a non-colliding key is an image assembly error.

Standard replacement is louder because it replaces a framework-provided registration. This is rare; a standard registration is part of the framework's registration set, so an image should only replace it when the replacement is deliberate, visible, and allowed by the standard descriptor.

```clojure
(rf/image
  {:id :story/no-navigation
   :include-ns ["product.core.**"
                "product.story.standard-overrides.**"]
   :replace-standard {[:fx :rf.nav/push-url]
                      {:ns "product.story.standard-overrides"}}})
```

The replacement namespace can provide a registration with the same `(kind, id)`:

```clojure
(rf/reg-fx :rf.nav/push-url
  {:doc "Story replacement that records navigation without touching browser history."}
  (fn [location]
    (swap! story-nav-log conj location)))
```

This example assumes the standard `:rf.nav/push-url` descriptor is marked replaceable. Without `:replace-standard`, shadowing it should be an image assembly error. Standard registrations must not be shadowed accidentally.

`:replace-standard` is not a back door for replacing the processing contract. Standard descriptors need an explicit replacement policy:

```text
:rf.standard/replaceable? false                 -> image replacement forbidden
:rf.standard/replaceable? true                  -> image replacement allowed
:rf.standard/requires-conformance #{...}        -> replacement must satisfy named invariants
```

The default should be non-replaceable. A standard entry that embodies an execution invariant is not replaceable just because it has a `(kind, id)`. For example, EP-0022's standard `:rf.interceptor/path` carries the `identical?`-preserving db no-op rule. A naive replacement can preserve value equality while breaking the frame commit fast path, so image assembly must either forbid replacing that standard id or require an explicit conformance profile that proves the invariant is preserved. The first version should choose the simpler rule: `:rf.interceptor/path` is framework-standard and invariant-coupled, so it is not image-replaceable.

Do not use `:replace-standard` to pin the event time. `:rf/time-ms` is a framework-stamped envelope fact, not a user-owned clock supplier. Tests, replay, SSR, and tools pin time by supplying it on the dispatch envelope:

```clojure
(rf/dispatch-sync frame
                  [:counter/inc-random]
                  {:rf.cofx {:rf/time-ms 1781078400123
                             :counter/delta 4}
                   :rf.cofx/mint-policy :strict})
```

Application-owned registrations can be replaced by an image when the replacement is declared:

```clojure
[:event :cart/add]
[:sub :cart/items]
[:view :counter/root]
[:fx :http/get]
[:cofx :counter/delta]
[:interceptor :my.audit/guard]
[:resource :article/by-slug]
[:route :article/show]
[:machine :upload/machine]
[:flow :cart/totals]
[:schema :article/id]
```

The boundary is the processing contract itself. An image can replace registrations; it cannot replace the execution semantics that make registrations run.

Do not make these image-replaceable:

```text
event queue ordering
event drain semantics
interceptor chain execution algorithm
coeffects/effects calling convention
app-db commit semantics
identical?-based db no-op rule
frame identity and carried-frame invariant
runtime-db partitioning
resource cache key canonicalization rules
image assembly and collision rules
```

The simple rule is:

> If it is application-owned and has a registered `(kind, id)`, an image may replace it explicitly. If it is framework-standard, the standard descriptor must opt into replacement and must name any invariants the replacement is required to preserve. If it is how the frame executes registered entries, it is part of the processing contract and not replaceable by an image.

## Two Boundaries

Most of the design becomes simpler once these two boundaries stay separate.

```text
image boundary
  which instructions are visible?
  can these ids collide?
  which registrations, metadata, ownership claims, and standard framework entries are loaded?

frame boundary
  which memory is isolated?
  which queue, sub-cache, traces, lifecycle, app-db, and runtime-db are being used?
```

These are different cuts. An image answers "what can this frame run?" A frame answers "what has happened in this run?"

If two counters should share behavior but not state, use one image and two frames:

```clojure
(def counter-left-id :counter/left)
(def counter-right-id :counter/right)

(rf/make-frame
  {:id counter-left-id
   :images [counter-image]
   :initial-db {:count 0}})

(rf/make-frame
  {:id counter-right-id
   :images [counter-image]
   :initial-db {:count 10}})
```

If two counters should reuse the same ids with different behavior, use two images and two frames.

```text
same instruction set, different memory -> same image, different frames
different instruction sets             -> different images
```

That is the simplest practical rule for tests, stories, docs pages, and Xray.

## The Fold Test

The fold framing makes placement decisions much easier.

At a high level, re-frame can be understood like this:

```text
state = fold(event stream, initial state)
```

Under the VM metaphor:

```text
image        = the loaded instruction set used by the fold
event stream = the program being folded
frame-state value = the accumulator value after some prefix of the stream
frame        = the VM doing the fold
```

This gives a placement test:

```text
Is this part of the accumulator produced by the event stream?
  yes -> frame-state value, under app-db or runtime-db
  no  -> image, live frame object, host boundary, adapter, effect/coeffect machinery, or transient runtime machinery
```

Examples:

- app facts written by handlers belong in `app-db`;
- resource cache state and machine snapshots belong in `runtime-db`;
- abort handles, timers, DOM roots, HTTP clients, and random generators do not belong in the frame-state value;
- host facts that affect durable writes belong on the event envelope as recorded coeffects.

That is better than asking only "is it serializable?" Serializability is a consequence. The sharper question is whether the fact is part of the fold accumulator.

## Host Boundary

Do not add a new host-boundary abstraction.

The public host boundary remains:

```text
handler receives coeffects
handler returns effects
resources perform managed loading
adapters integrate routing/rendering/SSR
```

The adapter binding/configuration itself is part of the live frame object, not the frame-state value. A frame may carry frame-local binding data for the active substrate adapter, and two frames may carry different bindings/configurations for that same active substrate. This EP does not require one page to run two distinct rendering substrates, such as Reagent and UIx, concurrently; that remains a substrate-spec capability question. The public rule is simple: once a view or event targets a frame, the frame determines both the image generation used for registration resolution and the host attachments used to run it.

If an event handler "needs HTTP", it returns an HTTP effect. If it "needs randomness", it receives a recorded coeffect value. If it "needs the current route", it uses the routing subscription or coeffect path already defined by the relevant spec. The host stays at the edge; durable state stays explainable.

The practical durability test is:

> If a value appears in durable state, can I explain it from the event and the recorded facts that event consumed?

If yes, replay, tests, SSR, and tools can reproduce the write. If no, the handler probably read an ambient host fact that should have been an explicit coeffect value or stayed out of durable state.

### Effects

The handler returns a command:

```clojure
(defn articles-load [{:keys [db]} _event]
  {:db (assoc db :articles/loading? true)

   :articles.http/get
   {:url "/api/articles"
    :on-success [:articles/loaded]
    :on-failure [:articles/failed]}})
```

Different host implementations are supplied by different effect registrations selected into the image:

```clojure
(ns articles.browser)

(rf/reg-fx :articles.http/get
  {:doc "Issue a browser GET request for the article feed."}
  (fn [{:keys [dispatch]} {:keys [url on-success on-failure]}]
    (browser-request!
      {:method :get
       :url url
       :on-success #(dispatch (conj on-success %))
       :on-failure #(dispatch (conj on-failure %))})))
```

```clojure
(ns articles.test)

(rf/reg-fx :articles.http/get
  {:doc "Return a canned article feed reply."}
  (fn [{:keys [dispatch]} {:keys [on-success]}]
    (dispatch (conj on-success
                    {:status 200
                     :body {:articles []}}))))
```

Then select the appropriate namespaces:

```clojure
(def browser-articles-image
  (rf/image
    {:id :articles/browser
     :include-ns ["articles.core.**"
                  "articles.browser.**"]}))

(def test-articles-image
  (rf/image
    {:id :articles/test
     :include-ns ["articles.core.**"
                  "articles.test.**"]}))
```

The frame does not need a separate host-supply map. The image contains the chosen registration definitions.

### Coeffects

For recordable host facts, the handler receives values, not host handles.

```clojure
(defn inc-random [{:keys [db counter/delta rf/time-ms]} _event]
  {:db (-> db
           (update :count (fnil + 0) delta)
           (assoc :last-updated-at time-ms))})
```

The image includes whichever coeffect supplier is appropriate for live execution:

```clojure
(ns counter.random.browser)

(rf/reg-cofx :counter/delta
  {:doc "Mint a replayable counter delta."
   :recordable? true
   :schema [:int {:min 1 :max 6}]}
  (fn []
    (inc (js/Math.floor (* 6 (js/Math.random))))))
```

The event registration declares the coeffect requirement:

```clojure
(rf/reg-event :counter/inc-random
  {:doc "Increment by a replayable random delta."
   :rf.cofx/requires [:rf/time-ms :counter/delta]}
  inc-random)
```

At runtime, the event envelope carries the actual values:

```clojure
{:event [:counter/inc-random]
 :rf.cofx {:rf/time-ms    1781078400123
           :counter/delta 4}}
```

On replay, the recorded value is supplied directly. The live supplier is not called. That is the replayability payoff: the fold sees the same inputs, so the durable write can be explained again.

The only standard value in this family is the framework-stamped `:rf/time-ms`. It behaves like a standard envelope fact more than a user-owned coeffect supplier. Other facts, such as random deltas, UUIDs, feature flags, geolocation, or account context, are too domain-specific to standardize; they come from registrations selected into the image.

### Resources

Resource registrations are instruction-set entries too:

```clojure
(rf/reg-resource :article/by-slug
  {:doc "Load an article by slug."
   :request
   (fn [{:keys [slug]}]
     {:method :get
      :url (str "/api/articles/" slug)})})
```

The selected image determines which resource definitions, transports, scopes, mutations, and related registrations are visible to the frame.

## Views

A view registration is an instruction-set entry in the image.

A mounted view runs inside a frame.

```text
view definition -> image
view execution  -> live frame object
render adapter  -> live frame object, never frame-state value
```

Example:

```clojure
(rf/reg-view :counter/view
  (fn []
    (let [n      @(rf/subscribe [:counter/value])
          parity @(rf/subscribe [:counter/parity])]
      [:section.counter
       [:button {:on-click #(rf/dispatch [:counter/inc])}
        "Increment"]
       [:span.value n]
       [:span.tag (name parity)]])))
```

When mounted, the subtree receives a frame:

```clojure
[rf/frame-provider {:frame :counter/main}
 [rf/view :counter/view]]
```

Inside that subtree, `dispatch` and `subscribe` resolve through the frame. The frame's image determines which registrations those ids resolve to.

Do not introduce a separate public provider just to say which image or container owns the view. The mounted subtree needs one runtime address: the frame target. In UI code that should normally be a frame id. That target must resolve to exactly one frame, and the frame must carry or point at its resolved image generation.

## Tests, Stories, SSR, And Hot Reload

### Tests

Unit tests can call handlers as ordinary pure functions.

Integration tests create a frame with the image under test:

```clojure
(deftest cart-add-test
  (let [frame (rf/make-frame
                {:images [cart-image]
                 :initial-db {:cart/items []}})]

    (rf/dispatch-sync frame [:cart/add "SKU-1"])

    (is (= ["SKU-1"]
           @(rf/subscribe frame [:cart/items])))))
```

In a test, using the frame object directly keeps the scope obvious: the frame is born in the test and dies with it. If host behavior must change, select test registrations into the image. Do not patch global process state when a smaller image describes the test more honestly.

### Stories

Stories become small frame factories:

```clojure
(defn story-frame [frame-id initial-db]
  (rf/make-frame
    {:id frame-id
     :images [checkout-image]
     :initial-db initial-db}))
```

Use another frame for a state variant. Use another image for a behavior variant.

### SSR

SSR creates a frame per request:

```clojure
(defn render-request [request]
  (let [image (site-image-for-request request)
        frame-id (request->frame-id request)]
    (rf/make-frame
      {:id frame-id
       :images [image]
       :initial-db {}})
    (rf/dispatch-sync [:route/entered (:uri request)] {:frame frame-id})
    {:html  (render-to-string [rf/frame-provider {:frame frame-id} [site-view]])
     :state (rf/frame-state-value frame-id)}))
```

The frame-state value crosses the wire. The image does not.

### Hot Reload

Hot reload swaps the sealed image generation a frame is running. Preserving frame memory is the hard requirement:

```clojure
(rf/reload-images! :counter/main
  {:images [counter-image-v2]})
```

The conceptual event is:

```text
registration set changed
execution context continues
```

Hot reload must not be implemented by tearing down and recreating the frame. Existing `app-db`, `runtime-db`, queue/lifecycle state, subscription state that remains valid, traces, and other frame-owned memory should continue unless a specific changed registration requires targeted invalidation.

The `reg-*` path uses the same operation through dependency invalidation. A changed `reg-*` entry does not mutate any running generation. It marks every dependent generation dirty, including explicit images whose `:include-ns` selector matches the changed namespace, resolves new sealed generations, computes the diff, and swaps those generations into affected frames.

The explicit `reload-images!` operation is frame-targeted and composition-replacing. If a frame was created with `:images [base widgets page]`, a manual reload supplies the new complete `:images` vector for that frame. The runtime may use generation caching internally, but a reload of `:counter/left` must not move `:counter/right` merely because the two frames previously shared the same sealed generation object.

A good reload result should be a concrete diff:

```clojure
{:added   #{[:event :counter/reset]}
 :changed #{[:sub :counter/parity]}
 :removed #{[:view :counter/debug]}}
```

That lets the runtime invalidate only what changed: changed subscription definitions can clear their caches; unchanged frame memory can continue. This is the image/frame split doing useful work: code changed, the VM kept running.

Clean handling of removed registrations during development is useful, but it is not the central constraint. A browser refresh always returns the process to a clean registration set. The important development contract is:

```text
preserve frame memory across hot reload
do not interfere with compiler dead-code elimination
make additions and changes visible without global reset
treat perfect removal cleanup as best-effort, not the reason to complicate the model
```

## Public API

`reg-*` defines individual registration entries. `rf/image` defines a registration-set value from registered entries, optional inline descriptors, and possibly other image values. Frame creation resolves one or more image values into the sealed image generation the frame runs.

There is no `reg-image`. An image is not itself a registration entry; it is a selected registration-set value. The public constructor is `rf/image`.

There is no `defimage` convenience macro. Use ordinary Clojure names when an image value deserves a name:

```clojure
(rf/reg-event ...)
(rf/reg-sub ...)
(rf/reg-fx ...)

(def counter-image
  (rf/image {:include-ns ["docs.counter.**"]}))

(rf/make-frame {:id :counter/main
                :images [counter-image]})

(rf/make-frame {:id :product/main
                :images [library-image product-image]})
```

Images can declare capability requirements. Frames supply the matching capability map and, where needed, adapter binding/configuration for the active substrate:

```clojure
(def articles-image
  (rf/image
    {:id :articles/browser
     :include-ns ["articles.core.**"
                  "articles.browser.**"]
     :rf.image/requires #{:rf.capability/http
                          :rf.capability/schemas}}))

(rf/make-frame
  {:id :articles/main
   :images [articles-image]
   :adapter reagent/adapter
   :capabilities {:rf.capability/http browser-http
                  :rf.capability/schemas malli-schemas}})
```

If any `:rf.image/requires` capability is absent from the frame's `:capabilities`, frame creation fails before the image generation becomes runnable. That preserves EP-0013's fail-loud capability check without keeping `realm` in the beginner-facing API.

Inline image values are also valid when naming them separately adds no clarity:

```clojure
(rf/make-frame
  {:id :docs/counter
   :images [(rf/image {:include-ns ["docs.counter.**"]})]})
```

That keeps the API small: `rf/image` makes image values; `:images` supplies one or more image values to a frame.

`rf/make-frame` returns the live frame object in all cases. When `:id` is supplied, it also registers that object in the process-local live-frame registry under that id and fails if the id is already live. When `:id` is absent, the frame is local-only: callers keep the returned object and pass it directly to dispatch, subscribe, test helpers, or `rf/frame-state-value`. The absence of `:id` is not a default-id path.

`rf/make-frame` is the EP-0023 object constructor and accepts only the EP-0023 frame-creation opts: `:images`, `:id`, `:initial-db`, `:capabilities`, `:adapter`. Seed frame **state** with `:initial-db` (image is a behaviour concern; state is a frame concern). A record-only config key — the EP-0013 record-construction surface `:on-create`, `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`, and similar — fails loud (`:rf.error/make-frame-record-only-key`) rather than being silently dropped. That record-config surface lives on the advanced `re-frame.frame/make-frame` (the EP-0013 record constructor, which returns a gensym id); reach for it directly only when you genuinely need the record path. This is the collapse-finale repoint: `rf/make-frame` was migrated off the keyword-returning record constructor onto the object constructor once every record caller had moved, and the dual-export transition guard was retired.

Reload uses the same `:images` spelling:

```clojure
(rf/reload-images! :counter/main
  {:images [counter-image-v2]})
```

`rf/reload-images!` targets one frame, by id or direct frame object, and replaces that frame's whole image composition. It returns a reload report naming added, changed, removed, and retained registrations. It does not mutate the image values supplied to other frames.

> **Errata (rf2-wkw8na, sanctioned forward-extension):** the EP's model — "target frame → resolved image generation → registration resolution" — promised a public READ over a frame's generation, but the graduated facade exported only constructors/mutators (`rf/image`, `rf/make-frame`, `rf/reload-images!`). That read now ships: the registrar query trio (`rf/registrations` / `rf/handler-meta` / `rf/handler-ids`) grows a `{:frame f …}` arity that resolves the `(kind, id)` set through the target frame's sealed generation (surfacing `:rf.provenance/ns` + replacement facts), and `rf/frame-generation` returns the whole sealed generation (the four `:rf.gen/*` keys). `:frame` accepts a registered frame id or a direct frame object; an unresolvable `:frame` fails loud (`:rf.error/frame-no-generation`, no default fallback). A registrar-query map is ALWAYS a frame-targeted read (rf2-10nggz): the retired pre-EP-0023 `:realm` map arity was removed, and a map without `:frame` fails loud (`:rf.error/registrar-query-needs-frame`) — there is no realm coordinate in the public read grammar. This is the sanctioned public tooling surface for Pair MCP / Xray, which the EP forbids from consuming `re-frame.live-frame` / `re-frame.image-assembly` internals. Normative home: [spec/API.md §Public registrar query API](../../spec/API.md).

## Backwards Compatibility And EP-0013 Partial Supersession

EP-0013 is final and shipped, so this EP is not a post-implementation amendment in the EP-0009 sense. It is a **partial supersession**: it preserves EP-0013's isolation decisions and much of its implementation substrate, but replaces the beginner-facing public model and addressing surface.

Now that this EP has graduated, the EP-0009-valid bookkeeping is recorded: EP-0013's status is `superseded-by EP-0023`, and this EP records what carries forward and what is replaced (see the surface-disposition table below). The word "partial" is explanatory, not a separate status value.

This EP **retains** EP-0013's D1 runtime-realm machinery as the internal installation boundary: a registrar container, adapter/capability owner, frame registry, host-transient owner, disposal boundary, and compatibility home for the default registration path. It also requires the a15n62 invariant in substance: live dispatch, subscribe, fx, and cofx resolution must be derived from the frame being targeted, not from a process-global registrar.

What changes is the public story. Instead of asking users to reason about an app value installed into a realm and then addressed by `(realm, frame)`, the main public model becomes:

```text
image -> frame -> event stream
```

A frame carries, or points at, the resolved image generation it is running. Internally, the current implementation may still realize that as:

```text
frame -> owning realm -> realm registrar
```

The target model is:

```text
frame -> resolved image generation
```

Those are equivalent at the a15n62 boundary if the frame is the only public target: once you have the frame, you have the instruction set used for resolution. The realm can remain the internal container that makes installation, hot reload, host-transient ownership, and disposal work.

The target public contract is that the **frame absorbs the realm's live responsibilities**. That does not mean the frame-state value absorbs them. It means the live frame object becomes the thing that names the running environment, while the frame-state value remains the serializable projection.

EP-0013 realm responsibilities rehome as follows:

| EP-0013 realm responsibility | Proposed public home | Notes |
|---|---|---|
| Registrar | Resolved image generation | The image selects registrations; assembly seals them into the instruction set the frame runs. During migration, the realm registrar may still be the backing implementation. |
| Adapter | Live frame object | A frame carries or points at the host adapter binding/configuration used for its views, subscriptions, routing/rendering integration, and SSR path. The v1 commitment is frame-local binding/configuration for the active substrate, not proven concurrent mixed-substrate execution. |
| Capability map | Live frame object, checked against image requirements | Images declare required capabilities; frames supply the host capability map. Assembly fails before execution if the frame cannot satisfy the image's requirements. |
| Frame registry | Process-local live-frame registry | A public frame id is unique in the live frame registry. Direct frame objects bypass the registry for tests and harnesses. The old `(realm, frame)` pair becomes an implementation detail or a migration bridge. |
| Host-transient subsystem state | Live frame object and frame lifecycle | Timers, DOM roots, abort handles, in-flight requests, adapter handles, and similar host objects attach to the frame and are disposed with the frame. They never enter the frame-state value. |

This is the missing cut: image owns behavior; frame owns the live run. The old realm can continue as a private installation structure while the public model collapses to the frame target.

This EP is therefore a **partial supersession of EP-0013's public app/realm surface**. EP-0013's isolation decisions are retained, and a15n62-equivalent live-routing behavior is required. On branches where that behavior has already shipped, this EP retains it. On branches where it is still deferred, landing it is part of this EP's implementation scope. The public vocabulary and addressing surface are simplified.

Migration has one deliberate hardening break: default-image assembly is stricter than today's registrar. An existing codebase may contain two loaded namespaces that register the same `(kind, id)` and currently "work" only because the later registration silently clobbers the earlier one. Under this proposal, the source store retains both descriptors and the default image fails assembly with a collision diagnostic. The migration is to rename the duplicate id, narrow the image selector, or declare an exact replacement winner. This is intentional fail-loud behavior, not a compatibility regression to paper over.

Surface dispositions:

| EP-0013 surface | Status in this EP | Disposition |
|---|---|---|
| D1 realm container | Retained internally | The realm remains a valid implementation substrate for registrar seating, adapter/capability storage, host-transient ownership, disposal, and compatibility during migration. It stops being the beginner-facing public architecture. |
| a15n62 realm-routed dispatch/subscribe/fx/cofx | Required invariant and migration path | The invariant is restated as frame-derived resolution: the target frame determines the registration universe. An implementation may realize that as `frame -> owning realm -> realm registrar` or collapse it to `frame -> resolved image generation`. Both satisfy the same observable contract; a branch without either behavior cannot deliver this EP's headline use cases. |
| `rf/app` / app value | Publicly replaced by `rf/image` | The same "registration set as value" idea survives, but the public name becomes the thing a frame loads. Migration can keep `rf/app` as an alias or diagnostic bridge while docs and new code use `rf/image`. |
| `rf/module` / module descriptor | Re-expressed as image fragments | The deferred D3 module-manifest slice is realized as image fragments, namespace-provenance selection, ownership metadata, and capability requirements inside images. No separate public `module` noun is needed for the core model. |
| `rf/realm`, `install!`, `reinstall!`, `installed-app`, realm-scoped registrar queries | Retained as implementation/migration/tooling surface unless separately retired | Existing shipped code can keep working while the public guide moves to `rf/image` + `rf/make-frame`. Tooling may still expose the internal installation boundary, but should label it as such. |
| `(realm, frame)` address | Migrated to frame target | Public operations target a frame: a process-local frame id for mounted/product code, or a direct frame object for tests/harnesses. This collapses the old two-part address into one public frame-id space. Code that relied on the same frame id in multiple realms must give live frames distinct public ids or keep direct frame objects in local scope. |
| Realm capability map | Moved to live frame object | Images declare `:rf.image/requires`; frames supply `:capabilities`; missing capabilities fail before the image generation becomes runnable. This preserves EP-0013's fail-loud install check at the new boundary. |
| Realm adapter selection | Moved to live frame object | Frames carry the adapter binding/configuration used by their views/rendering/SSR path. This EP requires frame-local binding for the active substrate, but does not claim distinct substrates can run concurrently on one page. |
| Realm host-transient subsystem state | Moved to frame lifecycle | Timers, DOM roots, in-flight requests, abort handles, adapter handles, and similar objects attach to the live frame and are disposed with it. They never enter the frame-state value. |

The proposed vocabulary relates to the current API like this:

| Current term | New public concept | Notes |
|---|---|---|
| `rf/app`, app value | `image` | Public D2 replacement. Same "registration set as value" instinct, but named for the thing loaded into a frame. |
| `rf/module` | image fragment or namespace-selected feature slice | D3 realized without a separate public module noun. |
| `program` | event stream | Avoid for registration sets. |
| `realm` | internal installation/container | Retained from EP-0013 D1, but not beginner-facing public architecture. |
| `frame` | `frame` | Live execution context / memory. |
| `runtime-db` | `runtime-db` | Existing name for framework subsystem state inside the frame. |
| `frame-state` | retained | EP-0001's serializable projection of a frame; this EP does not rename it. |

The point is to make the public model smaller and more internally consistent while preserving the useful current machinery: registrations, frames, `runtime-db`, carried frame identity, frame-derived resolution, and the internal realm substrate where it still earns its keep.

## Placement Rules And Rationale

Use these placement tests:

- If it is a registration, handler, view definition, schema, ownership claim, effect implementation, or coeffect supplier, it belongs to the **image**.
- If it is `app-db`, `runtime-db`, queue state, subscription cache, trace state, or lifecycle state for one running instance, it belongs to the **frame**.
- If it is an event vector, it is an **input delivered to the frame**.
- If it is a sequence of events, it is the **program being executed**.
- If it is a host fact that affects a durable write, record the value on the **event envelope**.
- If it is a host handle that cannot be serialized, keep it behind an effect/coeffect/resource/adapter boundary, not in `app-db` or `runtime-db`.

The shortest version:

```text
image = what registration set is loaded
frame = this live execution context
event stream = the program
```

## Bead Plan / Reference Implementation

1. Keep the EP-0013 realm container as a migration substrate while making the live frame object the public owner of adapter binding, capability map, frame lifecycle, and host-transient leases.
2. Land or retain the a15n62-equivalent live-resolution path: dispatch, subscribe, fx, cofx, view/resource lookup, and related registration resolution derive from the targeted frame.
3. Add `rf/image` as the public constructor for selected registration-set values.
4. Teach `rf/make-frame` to accept `:images`, always as a vector, and resolve those image values into one sealed image generation. `make-frame` always returns the live frame object; `:id` additionally registers that object in the process-local live-frame registry.
5. Replace the clobbering default registrar model with a provenance-preserving registration source store keyed by `[kind id provenance-namespace]`, then project selected descriptors into a sealed `[kind id]` resolver at image assembly.
6. Preserve the default registration path by projecting all default-source `reg-*` descriptors into the default image generation, failing if that projection contains same-kind same-id collisions.
7. Specify and implement the `:include-ns` glob grammar exactly: dot-separated namespace strings, `*` for one segment, `**` for zero or more segments, case-sensitive whole-namespace matching, and selection by `:rf.provenance/ns` rather than registration-id namespace.
8. Add `:rf.image/requires` to image values and check those requirements against the frame's `:capabilities` map before the image generation becomes runnable.
9. Record namespace provenance on every `reg-*` descriptor as `:rf.provenance/ns`, using a canonical string value per namespace inside the registrar/image-building context.
10. Add resolved-generation caching keyed by normalized image inputs, source-store generation, standard-registration generation, inline descriptor fingerprints, and replacement maps, with optional finer descriptor-set fingerprints.
11. Validate image assembly before a frame runs: duplicate ids, zero-match `:include-ns` patterns, replacement maps without an actual collision, replacement winners that identify zero or multiple descriptors, missing references, missing capabilities, unsupported descriptor kinds, and unapproved standard replacements should fail at image assembly.
12. Add a standard-descriptor replacement policy. Standard registrations default to non-replaceable; invariant-coupled standards such as `:rf.interceptor/path` are not image-replaceable unless a later spec provides a conformance profile that preserves the invariant.
13. Add frame-targeted `rf/reload-images!`, taking the same `:images` vector shape as `make-frame`, replacing the target frame's whole image composition while preserving frame memory.
14. Reproject dependent explicit-image frames on source-store changes, not only default-image frames.
15. Replace public `(realm, frame)` lookup with frame-target lookup: frame id through the process-local live-frame registry, or direct frame object for local scopes.
16. Add migration shims and diagnostics for the EP-0013 public names: `rf/app`, `rf/module`, realm-targeted provider pairs, and `(realm, frame)` addressing.
17. Update Xray and guide surfaces to show images as registration-set values and frames as execution contexts.
18. Add conformance fixtures for same-id/different-image isolation, same-image/two-frame memory isolation, namespace glob selection, generation sharing/reload isolation, explicit standard replacement policy, capability checking, process-local frame-id uniqueness, and direct frame-object test targeting.

### Implementation Beads

Implementation proceeded after acceptance as narrow, ordered beads rather than one broad "implement images" bead, tracked under the `rf2-32siq3` epic. The bead split was:

| Bead | Scope | Depends On | Acceptance |
| --- | --- | --- | --- |
| Registration source store | Replace cross-namespace last-write-wins with a provenance-preserving source store keyed by `[kind id provenance-namespace]`; retain same-namespace replacement for hot reload. Record `:rf.provenance/ns` as one canonical string per namespace. | EP acceptance | Duplicate ids from different namespaces are both retained before image assembly; same-namespace re-eval replaces the old descriptor; namespace provenance survives optimized builds without material bundle-size growth beyond one string per source namespace. |
| Image value constructor and selectors | Add `rf/image`, normalized image values, `:include-ns` glob selection, inline `:registrations`, and exact glob grammar (`*` one segment, `**` zero or more segments). | Registration source store | Image values are inspectable data; selection is by source namespace, not registration-id namespace; zero-match selectors fail loud; inline descriptors lower to the same descriptor shape with explicit inline provenance. |
| Image assembly validation | Project selected descriptors into sealed `[kind id]` generations and validate duplicates, missing references, unsupported kinds, missing capabilities, stale replacements, ambiguous replacement winners, and unapproved standard replacements. | Image constructor and selectors | Assembly errors are actionable and name the image, selector, kind/id, provenance namespace, and repair path. Order never silently decides a winner. |
| Replacement policy | Implement `:replace` and `:replace-standard` as maps from `[kind id]` to one exact winning source coordinate. Keep standard descriptors non-replaceable by default and protect invariant-coupled standards such as `:rf.interceptor/path`. | Image assembly validation | Intentional overrides work only when the named collision exists and the winner resolves to exactly one descriptor; stale or ambiguous overrides fail assembly. |
| Capability checks | Add `:rf.image/requires` and check it against frame capabilities before the generation is runnable. | Image assembly validation | Missing capabilities fail before any event can run; diagnostics distinguish missing capability from missing registration. |
| Resolved-generation cache | Cache sealed generations by normalized image inputs, source-store generation, standard-registration generation, inline descriptor fingerprints, and replacement maps. | Image assembly validation | Equal inputs over unchanged source stores reuse one generation object; SSR frame creation does not reassemble the same image on every request. |
| Frame image loading | Teach `rf/make-frame` to accept `:images` only as a vector, resolve them into one generation, return the live frame object, and register `:id` in the process-local live-frame registry when supplied. | Resolved-generation cache | Frames can be created with or without public ids; duplicate public frame ids fail; direct frame objects can be used in tests without registry registration. |
| Frame-derived live resolution | Route dispatch, subscribe, fx, cofx, view/resource lookup, and related registration lookup through the targeted frame's image generation. | Frame image loading | Same event/sub/view ids can have different meanings in different frames; same image in two frames shares behavior but not memory. |
| Hot reload and image replacement | Add frame-targeted `rf/reload-images!` and dependency tracking so source-store changes reproject affected explicit-image frames, not only default-image frames. | Frame-derived live resolution | Hot reload swaps image generations while preserving `app-db`, `runtime-db`, queues, subscription caches, and traces; `reload-images!` replaces the whole `:images` composition and reports added/changed/removed/retained descriptors. |
| EP-0013 migration shims | Provide diagnostics and migration compatibility for `rf/app`, `rf/module`, realm-targeted provider pairs, and `(realm, frame)` addressing. | Frame-derived live resolution | Existing EP-0013 examples have a clear migration path; public docs describe `image -> frame` while internal realm substrate remains available where still needed. |
| Tooling, Xray, guide, and skills | Update Xray, guide pages, examples, migration skill, and any EP-0013/0017/0018 cross references to the image/frame vocabulary. | Migration shims | User-facing surfaces consistently teach images as registration-set values, frames as execution contexts, and events as the stream executed by a frame. |
| Conformance suite | Add the conformance fixtures listed below and wire them into the normal test surface. | All implementation beads | The EP cannot graduate until the conformance list below passes on CI. |

## Conformance And Tests

This EP should not graduate without tests for these cases:

- two frames on one page can use the same event/sub/view ids with different images;
- two frames can share one image while maintaining independent `app-db`, `runtime-db`, queues, subscription caches, and traces;
- two frames created from the same image inputs may share a sealed generation object, but reloading one frame does not move the other;
- equal normalized image inputs over an unchanged source-store generation reuse the cached resolved generation on the SSR request path;
- dispatch, subscribe, fx, cofx, view/resource lookup, and related resolution use the targeted frame's image generation, not a global/default registrar;
- provenance-distinct registrations with the same `(kind, id)` are both retained in the source store until image assembly;
- the default image fails assembly when two loaded namespaces provide the same `(kind, id)` with different implementations;
- a mounted UI subtree can target a frame id and resolve all registrations through that frame's image generation;
- a unit test can target a direct frame object without registering a public frame id;
- duplicate frame ids fail in the process-local live-frame registry;
- the same frame id that previously coexisted in two realms cannot be registered twice in the new public frame-id space;
- two local direct frame objects can coexist without public ids;
- a frame can carry frame-local adapter binding/configuration for the active substrate without writing that host state into the frame-state value;
- missing image-required capabilities fail before the image generation is runnable;
- every `:include-ns` pattern matching zero descriptors fails image assembly with an actionable diagnostic;
- namespace glob selection includes only matching source namespaces, follows the `*` / `**` segment grammar, does not select by registration-id namespace, and does not require runtime namespace scanning;
- image composition fails on same-kind same-id collisions unless `:replace` or `:replace-standard` maps the `(kind, id)` to one exact winning descriptor source;
- stale `:replace` / `:replace-standard` declarations fail when the named key no longer collides or the winner source no longer identifies exactly one selected descriptor;
- `:replace-standard` fails for standard ids not marked replaceable, including invariant-coupled `:rf.interceptor/path`;
- supplied `:rf.cofx {:rf/time-ms ...}` pins time without replacing a coeffect supplier;
- `make-frame` returns the live frame object whether or not `:id` is supplied, and `:id` additionally registers that object;
- `reload-images!` replaces the target frame's whole `:images` composition and reports added/changed/removed/retained registrations;
- hot reload can replace a frame's image generation while preserving frame memory;
- a `reg-*` re-eval in a namespace selected by an explicit `:include-ns` image reprojects and swaps affected explicit-image frames;
- DCE is not defeated by namespace provenance recording.

## Consequences

1. Public docs should teach `image -> frame`, with the event stream as the program executed by the frame.
2. The ordinary `reg-*` path remains the default registration-source / default-image path.
3. Namespace provenance should be recorded as strings so images can select registrations with `:include-ns` glob patterns.
4. `realm` remains the EP-0013 internal installation substrate, not a beginner-facing concept.
5. `rf/module` and `rf/app` map to image/image-fragment vocabulary.
6. `runtime-db` can stay named as-is because `runtime` is not being promoted to a primary public concept.
7. A mounted UI subtree should receive a frame target, normally a frame id, not a separate `(realm, frame)` provider pair.
8. Tests and stories should create frames with explicit images when isolation matters.
9. Tooling can still show the internal installation boundary, but it should label it as implementation structure, not the central model.
10. Effects, coeffects, resources, and adapters remain the host boundary; do not teach a second host-boundary abstraction.

## Proposed API Summary

The proposed public shape is:

- The public image constructor is `rf/image`.
- There is no `defimage`; use ordinary `def` when an image value deserves a name.
- When images are supplied explicitly, frame creation uses one spelling: `:images`, always a vector.
- Inline image values are allowed in `:images`.
- Images may declare required host capabilities with `:rf.image/requires`.
- Image replacements use maps from `(kind, id)` to an exact winning descriptor source coordinate; order never decides the survivor.
- Frame creation supplies live host attachments such as `:capabilities` and, where needed, active-substrate adapter binding/configuration.
- `rf/make-frame` returns the live frame object; supplying `:id` additionally registers it.
- `rf/reload-images!` targets a frame and replaces that frame's whole `:images` composition.
- Registration ids are scoped by the resolved image generation and may be reused across images.
- A frame id is unique in the process-local live-frame registry.
- Dispatch, subscribe, and view scope target a frame. Product code normally uses a frame id; local tests may use the frame object directly.

This is pre-alpha, so the proposed API chooses the cleaner spelling directly.

The core factoring is:

```text
create a frame
  with, when needed, :images [...]
  with, when needed, :adapter and :capabilities
  then target it by id or by direct frame object depending on context
```

That covers the common path, same-page examples, tests, stories, SSR, hot reload, Xray isolation, and tool inspection without asking users to reason about an extra public container or misusing `program` for a registration set.

## Open Issues

None at filing time. The proposal is intentionally complete enough for an
operator to accept, reject, defer, or request a narrower replacement surface.

## Recommendation

Accepted as a standards-track EP and graduated (Mike-ruled via the
`rf2-32siq3.32` decision, 2026-06-16).

The public model is `image -> frame -> event stream`. EP-0013's realm
machinery remains available as the internal installation substrate while
public docs and APIs teach image-loaded frames. The implementation wave landed
the provenance-preserving registration source store, image assembly and
caching, frame-derived registration resolution, explicit replacement winners,
and guide/tooling updates ahead of graduation; the conformance suite reports
zero contract gaps. A sequenced post-graduation wave (the object-returning
`make-frame` collapse and related caller migration) is tracked under the
`rf2-32siq3` epic and does not block graduation.

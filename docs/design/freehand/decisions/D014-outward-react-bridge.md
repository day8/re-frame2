# D014 — Outward React bridge

Status: **Ruled**
Ruling: **Ship `v/->react` for descriptors, with shallow uncoerced props, a
reserved `frame` prop, one `:map-props` adapter, and descriptor-keyed caching.**

Horizon: **Upcoming**

## Decision

Should Freehand provide a standard way to turn a declared Freehand view into a
React component value for APIs that accept a component as a prop? If so, what
does the bridge do with props, frame context, identity, children, SSR, and host
objects?

The ruled public spelling is `v/->react`. The implementation crosses the React
host boundary internally, but the operation converts a Freehand descriptor and
belongs on the one main authoring surface.

## The problem

Freehand normally points inward: a Freehand tree can mount a qualified React
leaf or wrapper. Some React libraries reverse the direction and demand a React
component value:

```clojure
{:cellRenderer (v/->react person-cell)}
```

Examples include:

- AG Grid `cellRenderer` and editor components;
- drag-and-drop overlays and sortable item shells;
- virtual-list row component props;
- route, modal, and plugin APIs that accept a component rather than an element;
- render-prop APIs whose callback must return React elements.

Without a bridge, every use needs a hand-written UIx/Helix wrapper just to recover
the ambient re-frame frame and mount `[person-cell props]`. That is repetitive,
easy to get subtly wrong, and especially unfriendly to generated code.

The bridge can also be made too magical. A third-party library may pass a mutable
JS object containing nodes, callbacks, and service handles. Deeply converting that
object to Clojure data would be expensive and semantically false. Creating a frame
when none is present would silently split the application. Guessing how React
children or callback props should become Freehand values would blur the host seam.

## Settled constraints

- This is the outward half of the existing React wrapper boundary, not a fourth
  host shape and not a general React interop layer.
- It accepts a **declared Freehand descriptor**, not an arbitrary function or
  Hiccup value. Stable view identity, HMR, errors, and evidence therefore survive.
- It resolves a frame when the generated component mounts: an exact reserved
  `frame` prop may select an existing frame; otherwise it consumes the ambient
  Freehand/re-frame frame. It never creates, refreshes, or owns a frame.
- The mounted Freehand view retains the common props, children, event, controlled
  input, subscription, HMR, and evidence semantics of its execution mode.
- React hooks, refs, context protocols, effects, Suspense, compound cloning, and
  portals remain inside an explicit React/UIx/Helix wrapper.
- Host objects do not become event data, structural values, or app-db state.
- The bridge must have a truthful SSR policy. A React component does not gain
  server support merely because its Freehand child has a JVM emitter.
- `re-frame.ui` is being absorbed and deleted; the bridge belongs to Freehand and
  must not be a forwarding dependency on the donor artifact.

## Tangible cases

### A library already supplies value props

A component-as-prop API under application control may pass ordinary shallow React
props whose values are already suitable Freehand values:

```clojure
(def react-badge (v/->react account-badge))

[foreign-tabs {:badgeComponent react-badge
               :badgeProps     #js {"account-id" account-id}}]
```

This is the easy case. The generated component matches own enumerable property
names to the declared prop ABI and leaves values untouched.

### A library supplies a foreign JS parameter object

AG Grid passes a large mutable renderer parameter object. The useful Freehand
contract is usually a small value projection:

```clojure
(defn cell-props [params]
  {:person-id (.. params -data -id)
   :column-id (.. params -column getColId)})

(def person-cell-react
  (v/->react person-cell {:map-props cell-props}))
```

`cell-props` is intentionally code at the host boundary. It is a top-level,
testable adapter, not a claim that the whole foreign object is data.

If the library protocol itself requires hooks, refs, lifecycle callbacks, or
returning an imperative handle, this helper is insufficient by design; write a
real wrapper.

## Options

### Option A — no standard bridge

Require an explicit React wrapper for every component-as-prop API.

Consequences:

- The Freehand API is smaller and every React crossing is maximally visible.
- A wrapper can express any foreign protocol without framework guesses.
- Simple adapters repeat frame-context, HMR, and descriptor-mounting boilerplate.
- Equivalent wrappers will differ in memoization, missing-frame behavior, prop
  conversion, and error reporting.
- AI-generated integration code has more incidental React machinery to get right.

### Option B — a zero-option bridge

`v/->react` accepts a descriptor and converts the React props object to a
Freehand map using one fixed shallow or deep conversion rule.

Consequences:

- The API is tiny and component identity is easy to cache.
- A fixed shallow conversion works for controlled, known APIs.
- There is no honest general conversion for AG Grid/SpreadJS-style parameter
  objects. Deep `js->clj` is both costly and destructive; passing the object
  directly violates ordinary Freehand value-prop expectations.
- Users return to wrappers for many of the cases that motivated the bridge.

### Option C — a bounded bridge with an explicit prop adapter

`v/->react` accepts a declared view plus an optional top-level prop mapper:

```clojure
(v/->react view)
(v/->react view {:map-props adapter})
```

The no-option form performs one shallow own-property mapping: every own enumerable
property except reserved `frame` is matched to the declared prop ABI by exact name,
with its value uncoerced. The adapter form receives the raw foreign React props
object and must return the one Freehand props map. No deep walk occurs.

Consequences:

- The common case remains one call.
- Foreign-object extraction is explicit and localised at the host edge.
- The adapter is code, so its output is not statically inspectable unless tested;
  that opacity is honest and bounded.
- Component caching must include stable adapter identity. Inline adapters can
  remint the React component and should receive a development diagnostic.
- More elaborate options will be tempting; the contract needs a firm stopping
  point.

### Option D — a configurable React adapter framework

Support prop schemas, deep conversion, child conversion, refs, contexts, callback
maps, lifecycle hooks, Suspense policy, and per-library plugins through one bridge.

Consequences:

- Many React APIs can be described without handwritten wrappers.
- Freehand effectively grows a second React component model.
- The option space becomes harder to learn and compile than a short UIx wrapper.
- Host protocols that are fundamentally code-shaped are disguised as declarative
  configuration.

This is precisely the gold-plating boundary the designs intend to avoid.

## Recommendation

Choose **Option C**: a small `v/->react` bridge with one optional, explicit
prop adapter.

The bridge contract should be:

1. **Descriptor only.** Passing a plain function or arbitrary Hiccup is a
   didactic error naming a declared view or explicit wrapper as recovery.
2. **Mount-time frame resolution.** An exact own `frame` prop may name or carry an
   existing live frame; otherwise the bridge consumes ambient context. Missing,
   malformed, or dead targets fail loudly. The helper never creates a root or
   chooses a silent default.
3. **Stable wrapper identity.** Cache by declared descriptor identity and stable
   adapter identity, across body revisions. HMR updates the descriptor entry
   without remounting the foreign library solely because the body changed.
4. **One shallow default or one explicit adapter.** Without `:map-props`, copy own
   enumerable props other than reserved `frame` into the declared prop ABI by
   exact name, leaving values untouched. With an adapter, the adapter returns the
   one map from raw foreign props. There is no camelisation or deep conversion.
5. **No protocol smuggling.** React children, refs, and host callbacks are not
   guessed. The prop adapter may deliberately project value children; protocols
   requiring React elements or refs use a wrapper.
6. **Common semantics inside.** The resulting component mounts the descriptor
   exactly as an ordinary Freehand parent would, including frame retargeting,
   events, subscriptions, error identity, and selected-commit fencing.
7. **Explicit server posture.** A use site is either backed by a truthful SSR
   adapter or enclosed by `v/client-only` with a declared fallback. The bridge
   itself does not infer this from the foreign library.
8. **Host-only execution.** Calling the bridge on the JVM raises the common typed
   host-operation error; a structural use site must declare its fallback policy.

Do not add mapper registries, automatic hook bridges, ref forwarding, callback
guessing, or arbitrary lifecycle options. Once those are required, a wrapper is
the clearer and more powerful unit.

## Consequences

- Simple component-as-prop integrations become one definition and retain
  Freehand's intent/test/debug semantics inside the cell.
- Libraries that supply opaque JS parameter objects pay one top-level projection
  function. This is useful friction: it names the host/value boundary.
- The adapter output can contain explicitly accepted host values only where the
  child immediately hands them to another qualified host boundary. Reusable
  Freehand views should otherwise receive value props.
- The bridge does not make a foreign render-prop callback compiler-visible. A
  compiled view may pass the stable returned React component to a qualified host
  leaf, but cannot inspect the foreign library's render behavior.
- Memoization is an identity guarantee, not a promise that the foreign library
  will avoid calling the component.
- A bridge-created component must show the underlying Freehand view id and the
  foreign call site in evidence so debugging does not stop at an anonymous React
  wrapper.

## Implementation evidence

Use at least two different protocol shapes:

1. an AG Grid-style cell renderer receiving a foreign JS parameter object; and
2. a drag-overlay or virtual-list component prop that carries ordinary value
   props.

The pilots must prove:

- ambient and explicit frame continuity, plus loud missing/dead-frame behavior;
- stable React component identity across ordinary renders and HMR;
- interpreted and compiled Freehand children both mount correctly;
- prop mapping does not deep-convert or retain the foreign object accidentally;
- outward event intent dispatches through the exact committed frame;
- teardown leaves no subscriptions or callbacks;
- client-only fallback and one truthful SSR-capable case; and
- evidence names the underlying view rather than only the generated wrapper.

## Dependencies and what this unlocks

Depends on:

- the public declared-view descriptor and cross-mode mount entry;
- one React frame context and frame-retarget law;
- HMR-stable descriptor identity;
- `v/client-only` and Root Descriptor SSR policy; and
- the boundary between D013 behaviors and React-owned wrappers.

Unlocks:

- AG Grid, drag/drop, virtual-list, and component-as-prop pilots;
- reusable Freehand row/cell templates inside React ecosystems;
- deletion of donor-specific outward bridge machinery, if any, after migration;
  and
- a crisp test for when a full UIx/Helix wrapper is genuinely required.

## Source basis

- [Codex design — Three host shapes](../codex-design.md#three-host-shapes) defines
  `v/->react` as the outward half of the wrapper boundary.
- [Codex design — React-library integration](../codex-design.md#react-library-integration)
  identifies component-as-prop and compound React protocols as separate cases.
- [Codex design — Descriptor ABI and cross-mode calls](../codex-design.md#descriptor-abi-and-cross-mode-calls)
  supplies the stable descriptor entry the bridge must mount.
- [Fable design §2.6](../fable-design.md#26-the-renderer-boundary) proposes the
  `v/->react` bridge and specifies ambient frame and wrapper behavior.
- [Fable design §5.4](../fable-design.md#54-the-component-library-test) treats the
  outward bridge as part of the component-library escape roster.

# D014 — Outward React bridge

Status: **Ruled** (amended 2026-07-24 — see §Amendment, 2026-07-24)
Ruling: **Ship `v/->react` for descriptors, with shallow uncoerced props, a
reserved `frame` prop, one `:map-props` adapter, and view-id-keyed caching.**

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

> **Clauses 3, 5, 7 and 8 below are superseded** by
> [§Amendment, 2026-07-24](#amendment-2026-07-24--the-contract-as-shipped),
> which states what each of them binds to now. Read them as the reasoning that
> produced the ruling, not as the instruction.

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

## Amendment, 2026-07-24 — the contract as shipped

The bridge shipped, and four points of this ruling do not describe what shipped.
Per [§Graduation discipline](README.md#graduation-discipline), the affected
ruling is amended rather than left to be reconciled by each reader: two live
texts giving incompatible instructions is exactly the shape that produces a
locally well-supported but wrong implementation or usage answer.

Each item below **replaces** the recommendation clause it names. Every one is a
correction to this DOCUMENT: in each case the obligation the clause states is
intact and discharged, and it is the mechanism the clause reached for that
belonged to the donor era or to an earlier guess about the design.

**Clause 5 — React children cross by slot identity; refs are refused
outright.** The clause said children were "not guessed", which was written when
the design still expected the bridge to have no view of them at all. It has a
better answer than a guess and a better answer than silence: React's `children`
prop IS a content slot and a Freehand boundary HAS a content slot, so `children`
becomes the boundary's **trailing children** and each element rides the ordinary
child walk, which already carries a finished React element through untouched.
That is an identity, not an inference, and nothing about the elements is
converted. The view's own declared `:children-policy` still decides, so a view
accepting no children refuses them with its own diagnostic rather than a
bridge-specific one. Refs move the other way and are made stricter than the
clause: a `ref` prop is **refused**, because Freehand retired the neutral ref
tier and a ref resolving silently to nothing leaves a foreign owner holding a
handle that never fills. Host callbacks remain unguessed, and a protocol needing
hooks, a lifecycle or an imperative handle returned is still a wrapper.

**Clause 7 — the bridge has no server arm, and a use site owns any fallback.**
The clause offered a use site two ways to be server-truthful: a truthful SSR
adapter, or `v/client-only` with a declared fallback. The first is not reachable
under the shipped design — the verb does not exist on the JVM, so there is no
adapter to be truthful — and the second names a Freehand-tree form for a
call that lives in host code on the React side. What survives, and is contract,
is the clause's own second sentence: the bridge infers nothing from the foreign
library it is handed to, and it never renders a stand-in of its own choosing. A
use site that must appear in server output supplies its own server-truthful
fallback. Freehand's server render is `v/render-static`, a structural fold with
no React in it, so nothing a server render can reach is an exported component —
which is why the bridge maintains no server-renderer context path and reads no
React internal to make one work.

**Clause 8 — host-only execution is spelled as ABSENCE, not as a typed raise.**
The obligation stands unchanged: the bridge is host-only, and a structural use
site declares its own fallback policy. Only the mechanism is corrected. The verb
is published under a `:cljs` reader conditional and is simply **not on the JVM
surface**, exactly as `v/mount`, `v/hydrate-root` and `v/unmount!` are — the
three sibling host verbs it shares that conditional with. The typed
host-operation error the clause named is donor vocabulary: Freehand deleted the
donor's whole host-op tier as a stated absence, carries no such error in its own
roster, and raises it nowhere. Resurrecting the tier for one var whose only JVM
behaviour would be to throw would make the substrate less honest than the
absence its three siblings already carry, and would leave `v/->react` the one
host verb that answers a JVM caller at all. An absence is worth asserting only
beside the presence, so it is pinned in both directions: the JVM roster names
the four verbs that must not be on that surface, and a control roster names the
JVM verbs that must.

**Clause 3 and the headline — caching is keyed on the VIEW ID.** "Descriptor
identity" was the wrong axis and would have defeated the guarantee clause 3
exists to give. A hot reload mints a fresh descriptor object for the same view,
so a descriptor-keyed cache misses on every reload — technically memoised, and a
remount of the foreign library's subtree in practice. The key is the declared
**view id** plus the adapter's identity: the two facts that decide what the
exported component does. A reload is then a republication — the new body reaches
the component React is already reconciling on. "Descriptor-only" remains true of
the ARGUMENT, which is what the register's one-line ruling records.

**Clause 4, additionally — the option's PRESENCE selects the adapter arm.** Not
its truthiness. Omitting `:map-props` asks for the shallow rule; writing it asks
for an adapter, and a `nil` or `false` value is refused rather than read as an
omitted key. A falsey adapter is a lookup that found nothing or a branch that
fell through, and reading it as "no adapter" would select the default rule and
mount the view with a props map its caller never asked for — the silent wrong
render this ruling refuses an unknown option key to prevent, with the key
spelled correctly. This mirrors the reserved `frame` PROP one level down, where
own-property presence already decides which arm runs.

Normative home: [`spec/004-Views.md` §The outward React bridge](../../../../spec/004-Views.md),
[`spec/011-SSR.md` §The outward React bridge has no server arm](../../../../spec/011-SSR.md).
Executable home: FH-REACT-001 … FH-REACT-005.

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

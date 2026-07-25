# D022 — Public React host door

Status: **Ruled**

Ruling: **`v/defhost` is Freehand's sole public inward React host declaration. It
produces one qualified descriptor kind with explicit ordinary-prop, callback,
children, and SSR planes. There is no runtime `v/host`, `:kind` split, or
`v/react-el`.**

Horizon: **Immediate**

## Decision

Freehand needs a short, honest way to use common React libraries without allowing
hooks, refs, effects, or opaque React elements to dissolve its callback,
structure, SSR, and compiler laws.

`v/defhost` declares a React component as a stable qualified Freehand host
descriptor. The registered React component may itself be a simple value/callback
component or a React-owned wrapper using hooks, context, refs, effects, Suspense,
or a compound-component protocol. Those are implementation styles behind one
boundary, not separate public host kinds.

An illustrative declaration is:

```clojure
(v/defhost date-picker
  DatePicker
  {:callbacks {:onChange :event}
   :children  :optional
   :ssr       :client-only})

[date-picker
 {:selected date
  :onChange
  (v/event [js-date]
    [:booking/date-picked (from-js-date js-date)])}]
```

The canonical specification may sharpen reversible data spelling while
preserving the planes and laws below.

## Contract

### Identity and invocation

- A declaration produces one stable, qualified, non-callable host descriptor.
- It is used as a Freehand vector head. React components do not become legal bare
  heads.
- There is one descriptor kind. “Leaf” and “wrapper” describe the registered
  React implementation, not distinct ABIs.
- There is no public runtime constructor. Public host identity comes from the
  declaration and remains source-locatable and checkable.

### Ordinary props

- Unreserved ordinary prop names pass shallowly and exactly by default.
- There is no automatic case conversion, deep Clojure-to-JavaScript conversion,
  callback inference, or per-prop conversion language.
- One optional whole-ordinary-props `:map-props` adapter may prepare non-portable
  host values.
- Ordinary props, declared callbacks, and children are disjoint planes.
  Callback carriers and trailing children are withheld from `:map-props` and
  installed afterward. The adapter cannot supply or replace declared callbacks,
  children, keys, identity, or other reserved Freehand facts; collisions and
  function values in ordinary-data slots are refused.
- Props-contract evidence is optional for application-private declarations.
  D011's stronger schema policy still applies to public library, catalogue, and
  generated-parity surfaces. Evidence reports declaration, key closure, value
  validation, and generator availability independently.

### Callbacks

- `:callbacks` is a finite map from exact prop names to the D008 roles `:event`
  or `:handler`.
- A callback position accepts the corresponding `v/event` or `v/handler`
  carrier. It is not inferred from an `on*` name.
- Freehand materializes carriers only at declared positions after the candidate
  is selected. The resulting functions obey D008's stable identity, latest
  committed body/frame, abandoned-render silence, retirement, and HMR laws.
- A bare event vector at a foreign callback position is not an implicit
  converter; it could otherwise confuse a host value with re-frame intent.

### Children and React ownership

- Every declaration states its children policy. Undeclared child crossing is a
  refusal, not an opaque accident.
- Accepted children are ordinary React children in the registered component's
  React tree, preserving that tree's context.
- Freehand does not promise that a Freehand-authored child can participate
  directly in `asChild`, arbitrary `cloneElement`, or ref-injection protocols.
  Keep such a region React-owned and register its wrapper through `v/defhost`.
- Hooks remain outside `v/defview`. Using a hook inside the registered React
  component is the intended route, not a hook API in Freehand.

### Structure and SSR

- Every host declaration states an SSR policy. In v1 a React host is client-only:
  the declaration chooses either a portable fallback or explicit no-server
  content according to the owning SSR contract. Freehand never executes the
  registered React component on the JVM.
- Structural rendering emits a stable honest marker carrying the declared host
  identity and permitted public evidence, not the opaque React implementation.
- Host values, React elements, functions, refs, and third-party instances are not
  serialized.

### Compiled mode

- The checker and compiler recognize the declared descriptor and its finite
  callback and children positions.
- A compiled parent may cross the host when lowering supports the same laws.
- Until then, the exact build refuses the crossing with a stable id, source
  location, explanation, and recovery. It must not accept the view and fail only
  at runtime or secretly invoke the interpreted walker.

### Raw React-element escape

Finished React elements remain legal opaque browser-only children where the
owning tree contract permits them. Their callbacks are ordinary captured-frame
closures. Freehand does not claim site retirement, structural inspection, SSR
portability, or compiled visibility for their internals.

`v/event` and `v/handler` are non-callable carriers and therefore are not legal
raw `createElement` callbacks. Use an ordinary captured-frame closure there, with
none of D008's declared-site identity or retirement claims.

This escape is deliberately weaker than `v/defhost`; it is not a second host API.

## Alternatives considered

### Raw elements only

This keeps the API smallest but cannot own callback positions, committed identity,
retirement, structure, SSR policy, or compiler recognition. It leaves ordinary
React-library use outside Freehand's strongest laws.

### `v/react-el` as a staged scanner

A helper that receives an already-created React element is too late to materialize
callback carriers with D008 identity and lifecycle. If it grows enough declaration
and commit machinery to do so, it becomes the host door under a second spelling.
It is rejected.

### Separate leaf and wrapper host kinds

Hook ownership is internal to the React component. Freehand can enforce the same
boundary contract around a simple third-party component and a hook-owning wrapper.
Two kinds would add authoring choice without adding an enforceable law.

### A neutral hook or ref tier

This would introduce another authoring and lifecycle model inside Freehand,
weaken cross-host parity, and duplicate the existing explicit React-owner route.
It remains rejected by EP-0036.

## Required proof

The first vertical fixture covers:

1. shallow value props;
2. one `:event` and one `:handler` callback;
3. ordinary child and context continuity;
4. callback identity across commits;
5. latest committed body and frame;
6. silence for abandoned renders;
7. retirement after unmount and HMR replacement;
8. structural identity and declared loss;
9. SSR policy and fallback;
10. interpreted mounted behavior; and
11. compiled crossing or source-located build refusal with recovery.

Vega-Lite, Google Maps, Framer Motion, SpreadJS, and another hook/compound
React library extend the same fixture spine according to their actual ownership
shape. They do not each earn a new boundary.

## Consequences

- Programmers get one short vector-head route for ordinary React libraries and
  hook-owning wrappers.
- Freehand retains a small, explicit semantic seam instead of mirroring a
  third-party TypeScript API.
- Library authors must declare callback positions, children, and SSR behavior.
  That ceremony buys enforceable Freehand laws.
- Opaque React elements remain useful when those laws are unnecessary, with
  their price stated honestly.

## Dependencies

This ruling preserves D002 boundary identity, D008 callback lifetime, D010
compiled-boundary honesty, D011 props publication policy, D012 evidence honesty,
D014's opposite-direction bridge, and EP-0036's one-state-system and no-neutral-
hooks laws.

It unlocks the declared-host vertical slice and the React-library witnesses in
the [product-completion setpoint](../product-completion-setpoint.md).

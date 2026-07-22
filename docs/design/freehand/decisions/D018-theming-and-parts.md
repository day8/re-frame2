# D018 — Theming and semantic parts

Status: **Ruled**
Ruling: **Use CSS-cascade tokens plus `data-component`/`data-part` addresses
and bounded `:parts` spreads through `v/spread-safe`; transforms remain
interpreted/test-only.**

Horizon: **Upcoming**

## Decision

What theming and part-customisation contract should a Freehand component library
offer without introducing a late tree-rewrite seam, breaking controlled-input
proofs, or making compiled and interpreted views behave differently?

This decision must distinguish four needs that are often bundled together:

1. visual tokens such as colour, spacing, radius, typography, and motion;
2. stable addresses for styling the meaningful pieces of a component;
3. bounded per-instance attribute/class overrides; and
4. structural replacement of a label, icon, row, trigger, or item renderer.

They do not all need the same mechanism.

## The problem

A component library cannot be useful if every visual decision is sealed inside a
view. Re-com's consumers expect to restyle controls and reach named internal
pieces. At the same time, an unrestricted "parts map" or tree transformer can
rewrite the properties Freehand relies on for correctness:

- `:key` and children determine identity and structure;
- `:value`, `:checked`, and handlers determine controlled-input scheduling;
- refs and behaviors determine host ownership;
- roles, labels, and focus attributes determine accessibility; and
- compiled analysis assumes that important sites remain visible.

For example, this should be easy and safe:

```clojure
[field {:label "Email"
        :value email
        :on-input [:account/email-changed ::v/value]
        :parts {:label   {:class "quiet-label"}
                :control {:class "wide-control"
                          :data-analytics "signup-email"}}}]
```

This should not be a theme operation:

```clojure
;; Must not be able to replace the field's controlled contract after analysis.
{:parts {:control {:value some-other-value
                   :on-input [:different/event]
                   :key :new-identity}}}
```

And replacing the label's structure with an icon plus help popover is not merely
styling. It should use children, a fixed compound child, or a declared/render slot
whose composition semantics are already understood.

## Settled constraints

- Reusable presentation views are normally props-only; themes must not require a
  second local reactive state system.
- Both execution modes share props, spread, children, slot, controlled-input, and
  structural-tree semantics.
- `v/spread-safe` already supplies the important merge law: caller attributes sit
  beneath owned literals, and key/ref/controlled values/owned handlers cannot be
  overwritten.
- Compiled library leaves are a normal placement choice. The theming contract
  cannot depend on interpreting or rewriting their emitted tree at runtime.
- Arbitrary late tree transforms are not a portable Freehand theme system.
- CSS classes, CSS custom properties, and `data-*` attributes are already ordinary
  host values and require no hook/effect runtime.
- Stable part names are public library API. Renaming or removing one is a breaking
  component-contract change even when DOM remains visually similar.
- Structural customisation uses the established taxonomy: trailing children for
  one default region, compound child views for fixed regions, `v/render-fn`/slot
  for parameterised content, and a declared child for stateful customisation.
- Host objects, functions, and callbacks do not become theme data.

## Options

### Option A — closed components with high-level variant props only

Components expose props such as `:size`, `:tone`, and `:density`, but no public
parts or tokens.

Consequences:

- The component can preserve all semantic invariants.
- The public API is easy to document and compile.
- Every unanticipated visual need requires a new library release or a fork.
- Consumers resort to fragile DOM selectors and `!important`, making the actual
  contract less explicit rather than more stable.

### Option B — CSS tokens and stable part markers only

Components emit namespaced CSS custom properties/classes and stable `data-part`
markers. Consumers style them through stylesheets; no parts prop exists.

```clojure
[:label {:data-component "acme/field"}
 [:span {:data-part "label"} label]
 [:input {:data-part "control" ...}]]
```

Consequences:

- Live theme switching can occur through CSS inheritance without re-rendering
  every compiled leaf.
- The structural contract is inspectable in browser and JVM trees.
- CSS handles the visual majority with almost no substrate machinery.
- Per-instance analytics attributes, classes, and small accessibility additions
  are awkward.
- The actual DOM/part topology becomes a versioned public surface.

### Option C — unrestricted part override maps

Every public part accepts an arbitrary props map, merged late into the emitted
node.

Consequences:

- Per-instance customisation is very flexible and remains superficially data
  oriented.
- Merge order becomes semantically load-bearing.
- A caller can replace handlers, controlled values, keys, refs, roles, behavior
  ownership, or top-layer properties after the library and compiler made their
  decisions.
- Dynamic maps can forfeit the controlled-input door or create cross-mode drift.
- Validation becomes a large, evolving deny-list.

The convenience is not worth making core correctness overridable.

### Option D — arbitrary tree-to-tree transforms

A theme or consumer receives component Hiccup and rewrites it before lowering.

Consequences:

- Interpreted code can express almost any transformation with ordinary Clojure.
- Tests can inspect input and output as values.
- Compiled views no longer have a stable template unless the transform becomes
  another compiler language or an interpreted escape.
- Identity, event ownership, controlled scheduling, diagnostics, and source
  attribution can all change after analysis.
- A visual theme can accidentally become an application behavior rewrite.

Pure transforms remain useful application/test tools, but are a poor cross-mode
component contract.

### Option E — a two-plane contract

Use a portable styling plane plus the existing structural composition plane:

- public `data-part` markers and namespaced CSS custom properties/classes for
  styling;
- optional, bounded per-part attribute maps merged through `v/spread-safe`;
- component variants as ordinary value props;
- children/compound children/slots/declared children for structural replacement;
- pure tree transforms permitted in interpreted application code and tooling, but
  explicitly outside the library's cross-mode contract.

Consequences:

- Most customisation remains ordinary CSS and data.
- Per-instance additions are possible without surrendering owned semantics.
- The component author must declare which parts are public and which attribute
  maps are accepted.
- Consumers must use a structural extension point when they genuinely need
  structure, rather than smuggling it through a theme.
- The substrate adds little or no runtime machinery; the burden is mainly a
  library convention and schema.

## Recommendation

Choose **Option E**, with the portable plane deliberately implemented as a
component-library contract rather than a Freehand theme service.

### 1. Tokens use the platform

Use `data-theme` or a class to select ordinary named token bundles through the CSS
cascade. Reserve inline namespaced custom properties for genuinely dynamic values
that cannot be expressed by a stylesheet. Put the selection or dynamic values on
a root/scope so inheritance updates descendants without one subscription per leaf:

```clojure
(v/defview app-theme [{:keys [theme children]}]
  [:div {:data-theme (:name theme)}
   children])
```

The application may obtain `theme` from one re-frame subscription. Freehand does
not need a theme registry, a theme context protocol, or special reactive token
subscriptions in every component.

### 2. Parts are stable semantic addresses

A library component declares a finite set of public part ids and emits them as
literal `data-part` values. A component-level marker scopes otherwise common
names such as `label`, `control`, and `icon`.

Part schemas belong with the view/component catalogue so docs, development
validation, tests, and AI context can all enumerate them. Private implementation
nodes receive no public part id.

### 3. Per-part maps are bounded safe spreads

A library may accept `:parts` as a map from declared part id to ordinary DOM
attributes. Each public part is merged through the common `v/spread-safe` law,
with library-owned literals winning. At minimum the override cannot change:

- key, ref, children, or node/behavior ownership;
- value, checked/default-value, or controlled event handlers;
- library-owned event handlers;
- semantic role or required accessibility relationships; or
- top-layer desired state.

A component can expose a narrower allow-list when appropriate. Unknown part ids
and denied attributes receive a source-located development finding. Freehand does
not invent a universal late `parts` transform; component libraries opt into this
convention through their props schemas.

### 4. Structure uses composition

If the caller needs to replace structure, the component exposes the right existing
form: child region, compound child, `render-fn`/slot, or declared child view. This
keeps keys, event sites, reactive boundaries, and compiled analysis visible.

### 5. Transforms remain honest local code

An interpreted application can run a pure Hiccup transform, and test tooling can
transform or annotate structural values. Such a transform is not a supported way
to theme a compiled library leaf and does not become an ABI the compiler must
execute.

This recommendation combines Codex's bounded `data-part`/CSS-variable posture with
Fable's useful two-plane distinction. It differs from Fable's suggestion that
compiled leaves subscribe individually to theme tokens: CSS inheritance should be
the default because it is simpler, host-native, live-switchable, and avoids
reactive fan-out. A value prop remains available where presentation cannot be
expressed in CSS.

## Consequences

- Styling is low-friction for CSS-literate teams and easy for tools to inspect.
- Theme changes can avoid rendering component trees altogether.
- Public part names and token names require documentation and compatibility
  discipline.
- Part overrides cannot be used to alter component behavior. Some callers will
  need an explicit slot or wrapper; that is a clearer API boundary.
- CSS is a browser-host mechanism. JVM structural tests prove emitted markers and
  values, not computed styles; visual/browser tests prove the result.
- Native mobile or another non-CSS host would need its own token lowering while
  retaining semantic part ids. That possibility does not justify an abstract
  styling DSL now.
- `v/spread-safe` remains the one merge semantic. A special compiled-only parts
  form is unnecessary.

## Implementation evidence

Port a representative set of components—a button, controlled field, buffered
field, dropdown, and virtual-table row—and demonstrate:

- a root theme switch without remounting or one subscription per leaf;
- stable public part ids in interpreted and compiled structural output;
- per-instance class/data/allowed ARIA additions;
- rejection of attempts to replace controlled values, handlers, keys, refs,
  children, required roles, or behavior ownership;
- structural customisation through a fixed child and a parameterised slot;
- SSR output with deterministic tokens and parts;
- usable focus, disabled, invalid, reduced-motion, high-contrast, and forced-colour
  states; and
- a versioned component catalogue entry listing tokens, parts, variants, and
  structural extension points.

## Dependencies and what this unlocks

Depends on:

- final `v/spread-safe` merge and denial semantics;
- props schemas and the declared-view/component catalogue;
- common children, compound-child, `render-fn`, and slot contracts;
- controlled-input conformance; and
- the JVM structural tree and browser accessibility/visual harness.

Unlocks:

- a coherent re-com replacement style contract;
- compiled-at-birth library leaves without a theme seam;
- generated component documentation and AI context;
- visual regression and accessibility pilots; and
- a firm rejection criterion for requests to add arbitrary late tree transforms.

## Source basis

- [Codex design — The data plane](../codex-design.md#the-data-plane) names tokens,
  CSS variables, `data-part` ids, and bounded override data as the theme contract.
- [Codex design — Props forwarding and parameterized content](../codex-design.md#props-forwarding-and-parameterized-content)
  supplies safe spread, children, compound views, and slots.
- [Codex design — Re-implementing re-com](../codex-design.md#re-implementing-re-com)
  rejects late theme transforms that can rewrite controlled semantics.
- [Fable design §5.4](../fable-design.md#54-the-component-library-test), P11,
  proposes the portable/freedom two-plane contract.
- [Fable design §2.5](../fable-design.md#25-the-data-orientation-doctrine) places
  theming and parts in the data-orientation ledger while keeping view bodies as
  functions.

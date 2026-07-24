# D007 — Should key-condition event maps be part of the grammar?

Status: **Ruled — gate discharged, outcome DELETE**
Ruling: **Ship the closed key-condition map, including the normative
composition rule, subject to its delete-before-release pilot gate.**
Amendment (2026-07-25, rf2-drpa3.178): the gate is **discharged with
outcome DELETE**. All four F5 pilots (rf2-drpa3.44) used the form ZERO
times — including the dropdown this record names as the exemplar — so
the gate's own wording discharges literally and the form is removed from
the substrate rather than kept for symmetry. A map at an event position
is the listener-options map and nothing else; keyboard branching is an
ordinary registered event carrying `::v/key`, with `(v/event [e] …)` the
escape where a browser mechanic must be decided synchronously. Nothing
below is edited: the body stands as the historical record of the fork
the gate existed to settle.

Horizon: **Immediate**

## Decision

The decision is whether ordinary keyboard branching has a bounded data form, or whether
every key-dependent listener must use `v/event`.

This is a direct fork between the two designs: Codex keeps the event grammar smaller;
Fable adds one finite form for plain-key widget behavior.

## Why this is a real problem

Accessible listboxes, menus, dialogs, and typeaheads often need both a distinct
intent and distinct browser mechanics for each key:

```clojure
{:on-key-down
 {"Enter"  {:event [:picker/accept]
             :prevent-default true}
  "Escape" [:picker/close]
  "ArrowDown" {:event [:picker/move 1]
               :prevent-default true}}}
```

Dispatching `[:picker/key ::v/key]` and branching in an event handler preserves
intent as data, but it cannot decide `preventDefault` after dispatch: the browser
decision must happen synchronously in the listener. The alternative is a
`v/event` body that reads `.-key`, branches, performs mechanics, and returns an
intent. That is powerful but opaque to structural tests, the JVM emitter, tools,
and AI.

The question is not whether Freehand can handle keyboard input. It can. The question
is whether the common, finite part deserves a data shape.

## Settled constraints

- There is exactly one semantic event per key action, or `nil` when no branch
  matches.
- `prevent-default` and propagation options are listener mechanics, not domain
  effects.
- Modifier chords, state-dependent browser mechanics, composition details, and
  measurement-dependent navigation must have an honest escape.
- The form must mean the same thing in interpreted and compiled modes and in the
  structural test surface.
- Keyboard listeners are not controlled-input synchronous-door sites.
- The event grammar must remain finite enough for the JVM emitter and diagnostics.

## Options

### A. Do not add key maps; use `v/event`

```clojure
:on-key-down
(v/event [e]
  (case (.-key e)
    "Enter"  (do (.preventDefault e) [:picker/accept])
    "Escape" [:picker/close]
    nil))
```

Consequences:

- The core grammar stays smaller.
- Arbitrary modifier and state logic remains natural Clojure/JavaScript.
- The hottest keyboard behavior in component libraries becomes opaque executable
  code and requires mounted browser tests.
- Per-key mechanics cannot be inspected in a semantic tree or checked by the JVM
  emitter.
- Libraries repeatedly write similar closures.

### B. Add one closed, exact-key condition map

A string-keyed map on a key-event attribute selects a normal event form. Each value
may be a vector, an options map containing `:event`, `v/event`, or `nil`.

Consequences:

- Plain-key behavior and its mechanics remain data.
- Component catalogs, accessibility tools, tests, and AI can inspect keyboard
  coverage.
- The compiled grammar and both emitters gain one more node kind.
- Classification needs a sharp error for mixed or malformed maps.
- The residue still uses `v/event`; the map is not a complete keyboard DSL.

### C. Add a full chord/predicate grammar

The map could recognize modifiers, repeat state, composition state, wildcards,
platform aliases, and ordered predicates.

Consequences:

- More keyboard code becomes data.
- Freehand acquires a specialized input language that is difficult to teach,
  validate, and keep platform-correct.
- Rare cases set permanent substrate complexity. This is premature gold-plating.

### D. Put key maps in a component library only

A library wrapper interprets key maps and emits `v/event` handlers.

Consequences:

- The substrate remains small and the idea can be piloted.
- The resulting handler is opaque below the library boundary, so compiled output,
  structural tests, and substrate diagnostics do not retain the promised data.
- Different libraries may invent incompatible shapes.

## Recommendation

Choose **B: include one deliberately closed key-condition map**.

The recommended boundary is:

- legal only on `:on-key-down` and `:on-key-up`;
- keys are exact `KeyboardEvent.key` strings;
- values are existing event forms: vector, options map, `v/event`, or `nil`;
- selection is one level only;
- a missing key is a no-op;
- no branch matches while `KeyboardEvent.isComposing` is true; a control that
  deliberately handles composition keys uses `v/event`;
- mixed key strings and listener-option keys are a typed authoring error;
- there is no wildcard, ordering, regex, modifier syntax, platform alias, or state
  predicate in the initial grammar;
- options such as `:prevent-default` apply only to the selected branch and are
  executed before dispatch.

Modifier chords and any decision whose mechanics depend on live application state
stay in `v/event` or a qualified host behavior. This keeps the data form useful
without pretending it covers the entire keyboard platform.

The addition earns its cost because pre-dispatch mechanics cannot be moved into a
domain handler, and rich reusable controls need this behavior repeatedly. It also
makes accessibility behavior visible to non-browser structural tests. The form
should still be deleted before release if the re-com/typeahead pilots fail to show
clear repeated use; the recommendation is evidence-seeking, not ornamental.

Make that evidence gate concrete: the dropdown pilot must express its repeated,
state-independent movement keys without hiding mechanics in callbacks, while the
typeahead pilot must leave its state-dependent Tab behavior in `v/event` without
expanding the map language. `:on-key-up` is retained for symmetry, but its weaker
corpus evidence should be called out in the pilot report.

## Consequences to verify

- Both emitters classify options maps (`:event`) before string-keyed key maps and
  reject ambiguous maps.
- A structural test can select a key branch and observe both mechanics and the
  final intent.
- Unmatched keys perform no mechanics and dispatch nothing.
- Composition and repeat-sensitive controls use `v/event` and have mounted browser
  tests.
- Dev diagnostics identify an unsupported modifier-shaped map and show the
  equivalent `v/event` recovery.

## Dependencies and what this unlocks

This depends on D006's projection/materialization rule and the common options-map
schema. It unlocks an inspectable keyboard surface for re-com-class controls,
accessibility checks, compiled manifests, and JVM structural tests.

## Design sources

- [Codex design, §4 “Event law”](../codex-design.md#event-law) defines the smaller
  event roster and uses `v/event` as the mechanics escape.
- [Fable design, §2.3 “The event grammar”](../fable-design.md#23-the-event-grammar)
  argues for exact-key condition maps, including why `preventDefault` cannot be
  decided after dispatch and where the data form stops.

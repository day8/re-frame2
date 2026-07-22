# D017 — Framework control and policy vocabulary

Status: **Open**

Horizon: **Upcoming** — decide after the first controller pilots expose repeated
semantics, before reserving public event ids

## Decision to make

Which reusable-control events and policies, if any, should be public Freehand or
re-frame vocabulary rather than component-library vocabulary?

The Fable examples use names such as:

```clojure
[:rf.field/edited control reset-key value]
[:rf.field/commit control caller-event reset-key]
[:rf.dropdown/move control 1 values]
[:rf.typeahead/typed control value]
[:rf/debounce {:id debounce-id :ms 250 :dispatch event}]
```

Those names make traces uniform and examples compact, but a reserved `:rf.*`
keyword asserts framework ownership. It commits the framework to field, dropdown,
typeahead, and debounce semantics—not merely to accepting event vectors.

The Codex design instead treats the buffered controller as a component-library
candidate and keeps Freehand focused on event, state, host, and evidence laws. Both
documents agree that `local` is deleted; the open question is where its semantic
replacement is named and maintained.

## Settled constraints

1. Freehand and `re-frame.ui` are one substrate under absorption. There is no donor
   namespace in which experimental permanent vocabulary can hide.
2. `local`, compiled hooks, refs, and effects do not survive. Controller state uses
   re-frame events; host facts use behaviors or wrappers.
3. Views emit one event vector or `nil`. Multi-step work is one semantic event whose
   handler returns effects.
4. Decisions depending on changing state consult the committed frame.
5. A keyword under a reserved framework namespace is a compatibility promise and
   must have framework-quality semantics, diagnostics, documentation, and tests.
6. Freehand is a substrate, not automatically a component library.
7. Data orientation does not require every policy to become a DSL. A pure function
   or ordinary registered re-frame event is often the better implementation.
8. Tool legibility can be provided by schemas and registration metadata; tools do
   not necessarily need to hard-code event ids.

## Vocabulary classes

The examples contain several different things that should not be decided as one
indivisible package:

| Class | Examples | Plausible owner |
|---|---|---|
| substrate law | one-event result, scalar projection, committed-state decision | Freehand |
| controller infrastructure | address, generation fence, controller evidence | Freehand/re-frame integration if D003 adopts it |
| semantic control protocol | field edit/commit, dropdown move/select, typeahead pick | component library |
| reusable async policy | cancel-and-replace debounce keyed by id | re-frame effect/resource library, if proven general |
| domain policy | whether an amount is valid or an article may save | application |
| host mechanic | focus, containment, geometry, observer, compound React protocol | behavior/wrapper |

A good ruling may place these classes differently.

## Options

### Option A — Ship all demonstrated control families as framework vocabulary

Freehand reserves and implements field, dropdown, typeahead, instance-state, and
debounce event families.

**Consequences**

- Tutorials and traces use one recognizable language.
- Component authors can assemble rich controls without registering common events.
- The framework owns accessibility-sensitive behavior and evolving product policy
  across many control types.
- Every new family creates pressure for more: selection, combobox, tree, tabs,
  drag, grid editing, optimistic mutation, and validation.
- Semantics that belong to a component library become difficult to change because
  their ids appear in application event logs and tests.
- Freehand's substrate surface expands before pilots establish commonality.

### Option B — Ship only generic storage verbs; libraries build semantic events

Freehand provides public `put`, `merge`, `toggle`, and `clear` operations. Libraries
compose their own semantic events when required.

**Consequences**

- The framework surface is smaller than Option A and trivial controls are concise.
- Application traces often expose storage mechanics instead of user intent.
- Generic verbs encourage application authors to use controller storage as a
  universal local-state map.
- Controller schemas and invariants can be bypassed from any view.

This is less surface than Option A but weakens the semantic model recommended by
D003.

### Option C — Framework laws and registration; library-owned control semantics

Freehand owns the cross-cutting contracts: event normalization, controller address
and generation behavior if adopted, frame semantics, structural intent, and debug
evidence. Component libraries own event ids and state machines for their controls.

```clojure
[:my.ui.field/edited control reset-key value]
[:my.ui.field/committed control reset-key caller-event]
[:my.ui.dropdown/selected control option caller-event]
```

An optional, small registration record can make a controller legible without
claiming its event ids as framework grammar:

```clojure
(control/register-kind!
 :my.ui/buffered-field
 {:state-schema BufferedFieldState
  :address-schema vector?
  :evidence-label "buffered field"})
```

The spelling is illustrative, not a proposed API.

**Consequences**

- Freehand stays a substrate while control libraries can evolve quickly.
- Trace tools can show controller kind/address/generation through generic evidence
  rather than a hard-coded list of event keywords.
- Different libraries may name similar semantics differently.
- A first-party control library can still offer a coherent vocabulary; it simply
  owns and versions it honestly.
- The registration facility must stay descriptive. If it grows reducers, command
  routing, or arbitrary lifecycle, it becomes an over-engineered control DSL.

### Option D — No common control infrastructure or vocabulary

Every library uses ordinary re-frame events, subscriptions, and arbitrary app-db
locations with no framework registration.

**Consequences**

- This adds no substrate API and trusts library authors completely.
- Tooling cannot reliably join a mounted control to its state or explain retention,
  generation, or duplicate ownership.
- Libraries will likely reinvent incompatible addressing and cleanup conventions.
- The buffered controller can still work, but the architectural replacement for
  `local` is only documentation, not a coherent facility.

## Recommendation

Adopt **Option C**, with a high bar for promoting any policy into re-frame itself.

The layer boundary should be:

1. **Freehand owns laws, not widget taxonomies.** It specifies one-event results,
   projections, controlled scheduling, committed-state decisions, state-address
   evidence, and generation fencing where these are genuinely common.
2. **A component library owns semantic controller events.** Field, dropdown,
   typeahead, and grid-editor ids live in that library's namespace. If the project
   ships a first-party library, those may be excellent blessed APIs without
   becoming substrate grammar.
3. **Applications own domain decisions.** Validation acceptance, authorization,
   persistence, and workflow transitions remain application events. A field may
   display advisory validation; it does not decide the invoice domain.
4. **Host mechanics remain qualified.** Focus, geometry, outside-containment, and
   third-party instances use a behavior or wrapper rather than control events that
   pretend DOM work is application data.
5. **Generic policies graduate on evidence.** Start debounce as a library-owned
   effect. Promote a cancel-and-replace policy to re-frame only after at least two
   independent non-widget consumers require exactly the same id, cancellation,
   frame, SSR, and trace semantics. Deterministic clock control belongs in the
   substrate test surface without making debounce framework policy.
6. **Tools consume metadata, not keyword folklore.** A component library may offer
   optional controller-kind metadata for schema, evidence label, and ownership
   mode. Freehand does not require `register-kind!`, `def-control-event`, or a new
   reducer language in v1.
7. **Do not reserve `:rf.field/*`, `:rf.dropdown/*`, or `:rf.typeahead/*` now.** A
   reserved prefix can be adopted later if a control family is deliberately moved
   into a framework-owned package. Avoiding an early promise is cheap; retracting
   one from event logs and tests is not.

This recommendation keeps traces semantic: the event still says
`:my.ui.field/committed`, not `put`. It simply locates widget semantics in the
package that can iterate on them.

## Consequences of the recommendation

**Benefits**

- Freehand remains elegant and small while supporting powerful libraries.
- The framework does not accidentally commit to a partial component suite.
- Control protocols can evolve during pre-alpha pilots without grammar migrations.
- First-party and third-party libraries use the same substrate contracts.
- Generic policies have an explicit path to promotion when evidence justifies it.

**Costs and risks**

- Two component libraries may expose different names for similar field protocols.
- Trace readers must learn library event ids, although generic controller evidence
  can still group them.
- The line between controller infrastructure and a controller DSL requires active
  restraint.
- A library-owned debounce effect may later need migration if it proves broadly
  useful enough for re-frame core.
- Documentation must clearly distinguish “Freehand law,” “first-party library
  convention,” and “application event.”

## Promotion test for framework vocabulary

A control or policy should move into a reserved framework namespace only when all
of these are true:

1. At least two independent libraries or non-UI consumers need the same semantics.
2. The behavior is not tied to a specific widget's accessibility or composition
   policy.
3. Its state, cancellation, frame, SSR, HMR, and error behavior can be specified
   without reference to one component implementation.
4. Structural and mounted tests demonstrate identical semantics in both execution
   modes.
5. Tooling obtains material additional leverage from standardized identity.
6. The compatibility cost of the public event/effect ids is accepted explicitly.

The buffered-field pilot alone is evidence for a component-library protocol, not
automatically for framework ownership.

## Evidence required to close the decision

- Implement field, dropdown, and typeahead pilots with library-owned event ids.
- Confirm tools can group controller state and causes using generic metadata.
- Inventory repeated policy mechanics across the pilots, resources, machines, and
  non-UI re-frame code.
- Demonstrate that no pilot needs raw public `put`/`merge` access from its view.
- Evaluate whether debounce semantics are truly identical across typeahead,
  autosave, validation, and non-UI work.
- Record any candidate promotion as a separate compatibility decision rather than
  silently moving its namespace.

## Dependencies and unlocks

- **Depends on:** [D003](D003-reusable-control-state-model.md), and should be
  informed by [D016](D016-buffered-and-revision-controls.md) plus dropdown and
  typeahead pilots.
- **Uses:** the identity ruling in
  [D004](D004-state-identity-and-addressing.md).
- **Unlocks:** public namespace allocation, controller registration scope, trace
  vocabulary, docs ownership, and whether debounce or another policy needs a
  separate re-frame proposal.
- **Does not reopen:** absorption, deletion of `local`, or the behavior/wrapper
  boundary.

## Sources

- [Codex design](../codex-design.md) — “State ownership”, “Event law”,
  “Re-implementing re-com”, “React-library integration”, and “Deliberate
  non-goals”.
- [Fable design](../fable-design.md) — §2.4's raw and semantic instance events,
  §4.1–§4.3's field/dropdown/typeahead vocabulary, §6's one-event alternative,
  and §8 Q8 “Framework-shipped control vocabulary”.

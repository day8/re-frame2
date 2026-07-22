# D004 — State identity and addressing

Status: **Ruled**
Ruling: **Writable state uses explicit caller-supplied `:control` addresses;
occurrence identity remains tool-plane evidence, with no public `v/self` in v1.**

Horizon: **Immediate** — stateful controller implementation cannot start safely
without it

## Decision

How does a reusable stateful control identify its re-frame state across renders,
reordering, HMR, structural tests, SSR, virtualization, and temporary absence?

Freehand already has a renderer occurrence identity:

```text
(view-id, parent occurrence, key-or-position)
```

That identity is suitable for cells, event sites, presence, host connection
generations, and debugging. The decision was whether it may also become an
application-state address, or whether writable controller state requires a
separate caller-supplied semantic identity.

This distinction is tangible in a list:

```clojure
(for [{:keys [id amount]} invoices]
  [amount-field {:key id
                 :value amount
                 ...}])
```

If a draft is stored under “the third `amount-field` rendered here,” sorting the
list can attach that draft to another invoice. A React key improves structural
stability, but a view rename, parent extraction, Story render, or movement to
another subtree can still change a derived path. The domain identity
`[:invoice id :amount]` survives all of those changes.

## Settled constraints

1. `re-frame.ui/local` and its placement machinery are deleted under absorption.
2. Controller state, when present, is application/interaction data in re-frame,
   not a renderer slot.
3. Host and renderer identities still exist, but their jobs are connection,
   reconciliation, presence, event-site ownership, and evidence.
4. Keys remain mandatory wherever sibling identity matters. This decision does
   not weaken React's keyed-list law.
5. A state address must be equality-comparable data and must work identically in
   interpreted and compiled views.
6. Mount and unmount do not seed or clear domain state.
7. Delayed events carry an address and generation; the event handler validates
   them against committed state.
8. The substrate must not silently mint durable application identity from an
   unstable render position.

## The two identities

The design is clearer if it names two different jobs:

| Identity | Answers | Natural lifetime |
|---|---|---|
| renderer occurrence | “Which mounted view/node/event site is this?” | connection and reconciliation lifetime |
| semantic state address | “Which interaction protocol and domain thing owns this state?” | route, form, record, workflow, or explicit controller lifetime |

They should be joinable in development evidence, but they need not be the same
value. Conflating them makes render refactors into state migrations.

## Options

### Option A — Explicit semantic addresses only

Every stateful control receives an address from its caller. The address is ordinary
EDN and expresses domain or library identity.

```clojure
[buffered-field
 {:control [:invoice invoice-id :amount]
  :value amount
  :reset-key amount-revision
  :on-commit [:invoice/amount-committed invoice-id]}]
```

**Consequences**

- Reordering, parent extraction, view renaming, HMR, Story rendering, and
  virtualization do not change the address.
- A failing test can seed or inspect the state directly without first rendering a
  tree to discover an address.
- Application code pays one explicit prop for each genuinely stateful control.
- Callers can accidentally reuse an address, so development ownership diagnostics
  remain necessary.
- A purely visual one-off disclosure may feel over-specified.

### Option B — Derived structural anchors by default, absolute override available

Each declared boundary derives a path from qualified view names and keys, falling
back to sibling ordinals. A `v/self` operation returns it. An explicit `{:id ...}`
replaces the whole path when the caller needs durability.

```clojure
(let [self (v/self)]
  (v/sub [:rf/inst self :open?]))
```

This is the Fable dossier's recommendation.

**Consequences**

- One-off controls have zero address ceremony, and the same anchor can join tree,
  trace, mounted occurrence, and state evidence.
- Keyed rows are reasonably stable under reordering.
- Unkeyed multiplicity must be warning/error-fenced, and compiled parents need a
  stricter keyed-or-explicit rule because ordinal anchors cannot be emitted safely.
- View renames, parent extraction, changing a key, rendering a subtree in a test,
  or moving a control can orphan state or select another record.
- Production must either retain positional semantics silently or pay runtime
  machinery to prevent swaps.
- The easy default is least safe precisely for small controls whose authors are
  least likely to think about identity.

### Option C — Separate identities; explicit addresses for writes

Freehand continues deriving occurrence identity for renderer and tooling work, but
it never uses that value as a writable state address. Stateful controls require an
explicit semantic address. Development evidence records both values so tools can
join “this mounted occurrence” to “this controller record.”

```clojure
{:occurrence [app.invoice/row 42 app.controls/amount-field]
 :controller {:kind :my.ui/buffered-field
              :address [:invoice 42 :amount]
              :generation 7}}
```

**Consequences**

- It preserves the best use of derived identity without making render structure a
  storage schema.
- There is one identity rule in both execution modes; no special compiled-anchor
  restriction is needed for application state.
- Stateful components retain one explicit prop, while props-only components pay
  nothing.
- Tooling needs a join record rather than assuming one universal anchor.
- Presence and event-site identity must be documented as occurrence identity so
  programmers do not mistake it for a durable state address.

### Option D — Framework-allocated opaque instance tokens

The runtime allocates a token at mount and uses it for state until unmount.

**Consequences**

- It removes caller ceremony and avoids positional collisions while mounted.
- It makes mount own state lifetime, loses state on virtualization/remount, and is
  awkward for SSR, replay, fixtures, Story scenes, and JVM tests.
- The token has no domain meaning and cannot be predicted or usefully seeded.
- It is component-local state with an indirect storage location, contrary to the
  one-state and data-orientation goals.

This option is incompatible with the settled constraints.

## Recommendation

Adopt **Option C**: keep occurrence identity and semantic state identity separate,
and require explicit semantic addresses for writable controller state.

The recommended contract is:

1. A stateful component accepts a conventional `:control` value. The exact prop
   name can be finalized with D003, but the value is caller-supplied, immutable
   EDN—not a DOM id, React key, callback, or runtime token.
2. The controller's storage key is `(controller-kind, control-address)`. The kind
   prevents a dropdown and field from accidentally interpreting the same record.
3. Addresses should name causal ownership, for example:

   ```clojure
   [:invoice invoice-id :amount]
   [:editor article-id :title]
   [:route route-instance :filters :status]
   ```

4. `:key` still identifies siblings for reconciliation. It may equal part of the
   semantic address, but Freehand never derives one from the other.
5. A control library may derive an address from explicit semantic props when the
   derivation is total and documented. It may not fall back to render position.
6. Two mounted writable owners of the same `(kind, address)` are a development
   error unless the controller kind explicitly declares shared ownership. Multiple
   readers are harmless.
7. The occurrence-to-controller join is development evidence only. It lets tools
   navigate from a DOM/view occurrence to its state without making the occurrence
   the state key.
8. State retention follows the semantic owner. A route/form/workflow event clears
   its addresses; unmount only removes the occurrence join.
9. A generation/reset key is separate from the address. The address says *which
   controller*; the generation says *which edit session or baseline*.
10. Runtime occurrence identity stays in the tool/evidence plane. Freehand does
    not expose `v/self` as a writable state address in v1; adding any public
    occurrence reader would be a separate decision.

This costs one prop exactly where durable state exists. That is useful friction:
it makes collision, persistence, tests, and cleanup discussable at the call site.
It also preserves Freehand's stronger rule that the renderer does not invent
application state ids.

## Failure examples under the recommendation

### Reorder

Rows keyed by invoice id move. Their occurrence path changes position, but
`[:invoice id :amount]` stays fixed, so the draft follows the invoice.

### Key migration

A temporary row id is replaced by a server id. The caller must explicitly map or
migrate the controller address. This is visible domain work rather than silent
orphaning caused by a renderer key change.

### Same address used twice

Two mounted amount fields claim `[:invoice 42 :amount]`. Development mode reports
both source locations and occurrences. The programmer either gives them distinct
addresses or deliberately selects a controller kind whose contract permits shared
editing.

### Story or isolated structural test

The fixture renders only the field and seeds the exact address it passes. It does
not need to reproduce the application's parent tree to manufacture a derived path.

## Consequences of the recommendation

**Benefits**

- Render refactors do not silently migrate application data.
- Tests, SSR preparation, replay, and tools can name state before mounting.
- Interpreted and compiled modes need no different writable-anchor rule.
- Virtualization and temporary absence do not destroy semantic identity.
- Runtime occurrence identity remains free to evolve for reconciliation and
  diagnostics without becoming an app-db compatibility promise.

**Costs and risks**

- Stateful library controls require explicit identity at use sites.
- Address design becomes part of application data modeling and must be taught.
- Owner cleanup cannot be inferred from the render tree; the owning route/form must
  clear deliberately.
- Duplicate-owner diagnostics need a narrow registration/evidence mechanism.
- Some programmers may use random ids to avoid thinking about ownership. Docs and
  development warnings should explain why unpredictable mount tokens defeat replay
  and tests.

## Implementation evidence

- Demonstrate a keyed reorder, virtualization unmount/remount, HMR, and isolated
  Story render with one unchanged semantic address.
- Demonstrate duplicate-owner diagnostics with both source locations.
- Show owner-driven cleanup and orphan reporting without unmount dispatch.
- Show generation fencing remains separate from identity in a stale-blur test.
- Verify compiled and interpreted components produce the same controller address
  and evidence join.
- Compare the authoring cost of explicit addresses in the buffered field,
  dropdown, typeahead, and virtual-table pilots.

## Dependencies and unlocks

- **Depends on:** the one-state-system and absorption rulings.
- **Co-defines:** [D003](D003-reusable-control-state-model.md).
- **Unlocks:** controller record layout, collision diagnostics, retention policy,
  [D016](D016-buffered-and-revision-controls.md), structural fixtures, and the
  state row of the interpreted/compiled conformance contract.
- **Does not decide:** which control events or policies belong to Freehand; see
  [D017](D017-framework-control-and-policy-vocabulary.md).

## Sources

- [Codex design](../codex-design.md) — “State ownership”, “Identity, HMR, and
  errors”, and “Deliberate non-goals”.
- [Fable design](../fable-design.md) — §2.4 “Instance state under the
  one-state-system pin”, §3.6 “The tier-2 anchor rule”, §6 “Explicit-only state
  addresses”, §7.1 “Ordinal anchors and key-changes”, and §8 Q2(b).

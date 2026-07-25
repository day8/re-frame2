# D015 — Top-layer overlays and portals

Status: **Ruled**
Ruling: **Ship only the qualified popover/modal desired-state intrinsics in
the neutral tree; ship no neutral portal unless the recorded graduation trigger
fires.**

Horizon: **Upcoming**

## Decision

Should Freehand have substrate vocabulary for opening browser top-layer elements,
or should popovers, dialogs, and portals be implemented entirely as host
components/wrappers? What, if anything, should be portable across execution modes
and the JVM structural host?

This decision is about the host mechanism. Dropdown state, listbox keyboard
policy, selection, validation, and application intent remain component-library or
application concerns.

## The problem

Overlays traditionally accumulate machinery:

- portals to escape clipping;
- z-index ladders;
- document listeners for outside dismissal;
- focus traps and focus-return code;
- global scroll/resize listeners;
- position measurement and repeated animation-frame loops; and
- teardown paths that are easy to miss during unmount or transition.

Modern browsers provide a top layer through popovers and modal dialogs. It solves
stacking and supplies important dismissal/focus behavior without moving the node
to an application-managed portal container. However, the browser APIs are partly
imperative. A desired modal state cannot be expressed only by setting a normal
attribute: modal dialog opening uses `showModal()`, and controlled popovers use
`showPopover()`/`hidePopover()`.

A Freehand component wants to remain state-in, intent-out:

```clojure
(v/defview select-menu [{:keys [open? on-open-change children]}]
  [:div
   [:button {:aria-expanded open?
             :popovertarget "account-menu"}
    "Account"]
   [:div {:id "account-menu"
          :popover :auto
          ::web/popover-open? open?
          :on-toggle
          (v/event [e]
            (conj on-open-change (= "open" (.-newState e))))}
    children]])
```

The open value is application/library state. Browser light-dismiss is reconciled
back through ordinary event intent. Freehand's host performs only the mechanical
commit operation.

Portals are related but not identical. A portal is a React ownership and
reconciliation protocol involving a host container. It does not itself provide
dismissal, focus management, accessibility, placement, or teardown policy. Making
portals neutral vocabulary would expose nodes/containers or invent a target
registry while solving less than the top layer.

## Settled constraints

- React is the primary host, but Freehand's common tree and compiled grammar do
  not pretend React-specific protocols are renderer-neutral.
- Native top-layer behavior is a DOM-platform capability, not a general React
  hook or portal abstraction.
- Open/close state is re-frame or caller-owned component state. The host may
  reconcile browser dismissal through an intent; it does not own a second state
  machine.
- Render and unmount never own domain lifecycle. Opening an overlay does not fetch
  data; closing it does not clean domain state.
- Positioning is separate from stacking. The top layer does not calculate anchor
  geometry or implement listbox/menu keyboard policy.
- Host work occurs only after a selected commit and is fenced by occurrence and
  generation. Abandoned render candidates do nothing.
- SSR and JVM structure must remain truthful. A server cannot display a browser
  top layer; it can emit the base semantic element and qualified desired-state
  metadata or an explicit fallback.
- Presence owns retained enter/exit presentation. Top-layer opening does not
  silently become a transition system.
- React-owned portals, compound cloning, contexts, refs, and hooks belong in an
  explicit wrapper.
- `re-frame.ui` contributes useful host/runtime implementation only as a donor;
  no standalone portal/effect tier survives absorption.

## Options

### Option A — wrappers and host components only

Keep Freehand unaware of top-layer state. A component library supplies
`web/popover`, `web/dialog`, or a UIx wrapper that calls browser methods.

Consequences:

- No DOM-specific attribute enters the substrate grammar.
- The library can evolve around browser compatibility and accessibility details.
- Every implementation must independently get selected-commit timing,
  controlled-state reconciliation, SSR metadata, evidence, and cleanup right.
- A wrapper is substantial ceremony for two idempotent host calls.
- Compiled views see an opaque host leaf instead of the actual semantic element.

### Option B — express top-layer mechanics as a registered behavior

Use D013's behavior protocol:

```clojure
[:div {:popover :auto
       ::v/behavior [popover-behavior {:open? open?}]}]
```

Consequences:

- It adds no new core host mechanism.
- The invocation remains data, and commit/update/cleanup laws already exist.
- The behavior consumes the node's one behavior slot, preventing a separate
  anchored-placement or observer behavior on the same element.
- Popover/dialog validity and SSR meaning become hidden inside a registry entry.
- A ubiquitous platform property looks like a third-party integration.

This is a useful prototype path, but a poor permanent composition rule if top
layer and measurement need to coexist.

### Option C — a closed pair of DOM top-layer intrinsics

Recognise two qualified desired-state properties in the common tree:

```clojure
[:div {:popover :auto
       ::web/popover-open? open?}]

[:dialog {::web/modal-open? open?}]
```

At selected commit, the DOM host diffs each property and calls the matching
idempotent browser operation. Native `:on-toggle`, `:on-before-toggle`, `:on-close`,
and `:on-cancel` remain ordinary event positions; they do not require a new event
grammar.

Consequences:

- State remains visible in the structural tree and works identically from
  interpreted and compiled declarations.
- It does not consume the behavior slot, so placement/measurement can remain a
  separate behavior.
- The surface is deliberately DOM-specific and requires a browser conformance
  matrix.
- The substrate owns a small amount of element-validity and method-order logic.
- The intrinsic solves top-layer state only. A library still supplies semantic
  components, ARIA, keyboard behavior, placement, and styling.

### Option D — a neutral portal primitive

Add a form such as `[v/portal {:to target} child]` to the common grammar.

Consequences:

- React integrations using existing portal ecosystems become direct.
- A target is normally a live DOM node or host container, which is not portable
  data and has no honest JVM representation.
- A named-target registry would add identity, lifetime, hydration, and missing
  target policy.
- Portals do not solve most overlay correctness requirements.
- Other renderers would either emulate React ownership or expose incompatible
  semantics behind one name.

This is an escape hatch disguised as neutral substrate vocabulary.

### Option E — both top-layer intrinsics and a core portal

This maximises coverage but establishes two overlapping overlay mechanisms before
the component pilots show that both are necessary. Teams would need guidance on
stacking, frame continuity, SSR, and accessibility for each.

## Recommendation

Choose **Option C** for the common DOM host and continue to reject a neutral core
portal.

Adopt a **closed, qualified pair** of desired-state properties—one for popovers
and one for modal dialogs—under an explicitly web/host namespace. The names above
are illustrative. Avoid a generic `:open?` because popover, non-modal dialog, and
modal dialog have materially different browser operations.

The contract should be narrow:

1. `popover-open?` is legal only on an element with a valid `:popover` mode.
2. `modal-open?` is legal only on `<dialog>` and maps to `showModal()`/`close()`.
   An ordinary non-modal dialog can use the platform's normal `:open` attribute.
3. Desired-state changes are applied after a selected commit. Repeated equal
   values are no-ops. Stale generations cannot act on a replacement node.
4. Browser-initiated dismissal does not mutate app state implicitly. The author
   handles the relevant native event with ordinary Freehand intent. Development
   diagnostics should identify a controlled top-layer node with no reconciliation
   handler.
5. Invalid calls—for example opening a disconnected node or calling `showModal`
   on an already-open non-modal dialog—become typed development evidence with a
   concise recovery, not swallowed exceptions.
6. JVM/SSR output contains the semantic element and qualified desired-state fact,
   but does not claim that it was promoted to a top layer. Hydration performs the
   first host operation after commit.
7. Positioning uses CSS anchor positioning where suitable or a D013 behavior with
   an explicit update contract. No every-frame tracking is implied.
8. Enter/exit uses `v/presence` where retention is required. The top-layer
   intrinsic neither starts timers nor delays removal.

Keep actual React portals in UIx/Helix wrappers. Reconsider a public portal helper
only when multiple non-overlay component-as-portal integrations pass named
graduation criteria that cannot be served by the top layer, a host leaf, or a
wrapper.

This recommendation accepts Fable's top-layer insight but narrows it into a
platform-specific mechanical contract. It keeps Codex's small host taxonomy and
does not ask the substrate to become an overlay component library.

## Consequences

- A re-com replacement can delete document click listeners, z-index ladders, and
  much portal plumbing for menus and dialogs.
- Native behavior is not automatically accessible widget behavior. A listbox must
  still implement roles, active option, keyboard navigation, and focus policy.
- `<dialog>` is not a substitute for menus, tooltips, or listboxes. The popover
  family serves non-dialog overlays.
- Browser support and behavioral differences become explicit release evidence.
  A wrapper remains the compatibility escape for unsupported protocols.
- A state mismatch can occur briefly when the browser dismisses and the event has
  not yet committed. The event handler must consult committed state where
  acceptance matters; it must not close over a render-time guard.
- Closed overlay DOM retention is a component choice. Conditional children can
  remove the node; leaving it present but top-layer-closed can preserve expensive
  host state. Freehand should not choose globally.
- The two intrinsics are compiler-recognised common semantics, not compiled-only
  forms.

## Implementation evidence

The popup component pilot should cover:

- popover open, programmatic close, browser light-dismiss, Escape, and immediate
  reopen;
- modal initial focus, background inertness, Escape/cancel, and focus return;
- nested/LIFO top-layer behavior;
- ancestor transforms and clipping contexts;
- placement via CSS and via a separate behavior, proving the one-behavior slot is
  not consumed by top-layer control;
- controlled-state reconciliation without stale render guards;
- presence exit and root teardown while open;
- SSR closed markup, hydration into desired-open state, and client-only fallback;
- equal interpreted/compiled structural values and common event intent; and
- real-browser testing rather than jsdom-only assertions.

## Dependencies and what this unlocks

Depends on:

- the common event normalizer and consult-committed-state law;
- selected-commit host operations and generation fencing;
- D013 behavior semantics for measured placement;
- presence and total root teardown;
- the JVM semantic tree and SSR/hydration policy; and
- a supported-browser baseline.

Unlocks:

- the popup/dropdown/dialog component pilot;
- a credible re-com overlay replacement without a generic portal system;
- deletion of donor-specific effect/ref work used only for top-layer mechanics;
  and
- a clear graduation test for any later React portal helper.

## Source basis

- [Codex design — Host ownership routes](../codex-design.md#host-ownership-routes) keeps
  portals and React protocols in wrappers while permitting one-node behaviors.
- [Codex design — Re-implementing re-com](../codex-design.md#re-implementing-re-com)
  selects native top layer plus bounded host behavior for popup/focus/measurement.
- [Codex design — Presence as data](../codex-design.md#presence-as-data) defines
  transition retention independently of overlay opening.
- [Fable design §2.6](../fable-design.md#26-the-renderer-boundary) proposes the
  top-layer intrinsic pair and records portals as React-specific.
- [Fable design §4.2](../fable-design.md#42-dropdown--popover--focus-dismissal-top-layer)
  works through dropdown, dismissal, focus, positioning, and promotion.
- [Fable design §8](../fable-design.md#8-for-the-operator) records the intrinsic
  pair as open question Q4.

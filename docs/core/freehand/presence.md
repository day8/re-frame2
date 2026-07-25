# Presence: enter and exit retention

Application state says an element is gone, but the UI should **slide or fade out
first**. React’s default is instant remove — correct for most UI, awkward for
toasts and closing panels.

**`v/presence` owns only the gap between “no longer true” and “no longer in the
tree.”** It retains keyed children for a bounded time and exposes **phase**. It is
not an animation library — your CSS (or a host animation boundary) does the motion.

> **Default to no presence. Add it only when exit retention is a product need.**

## The idea in one picture

```text
  app-db says "show toast A"     →  child mounts   :mounting → :present
  app-db drops toast A           →  child retained :unmounting  (still rendered)
  :timeout-ms elapses            →  child removed for real (subs/handles released)
  toast A reappears mid-exit     →  re-entry       back to :present (exit cancelled)
```

Your domain events still only say “toasts are …”. Presence turns that list into a
stable enter/exit presentation without mount/unmount **domain** events.

## Basic shape

`v/presence` is a boundary you wrap keyed children in. It stamps **nothing** on
them — no wrapper node, no attributes — so a presence-aware child styles its own
exit by reading the phase it is in:

```clojure
(v/defview toast-card [{:keys [toast]}]
  (let [exiting? (= :unmounting (v/presence-phase))]
    [:div.toast {:class       (when exiting? "toast--exit")
                 :inert       (when exiting? true)
                 :aria-hidden (when exiting? true)}
     (:message toast)]))

(v/defview toast-tray [_]
  (v/presence {:timeout-ms 300}
    (for [t (v/sub [:toasts/visible])]
      [toast-card {:key (:id t) :toast t}])))
```

| Piece | Role |
|---|---|
| `v/presence` | boundary that retains exiting keyed children |
| `:timeout-ms` | **mandatory** positive duration — retention length **and** hard terminal bound |
| `{:key …}` | **required** on each dynamic child — identity for the state machine |
| `v/presence-phase` | the child's one read: `:mounting`, `:present` or `:unmounting` |

### The boundary is DOM-agnostic

That is the whole design, and it is worth stating plainly because it is the thing
most likely to surprise you: **presence writes nothing onto your markup**. There
are no `::v/mounting` / `::v/unmounting` attribute overrides, no `data-presence`
attribute, and no inserted wrapper `<div>`.

The child owns its exit presentation, its `inert`, and its `aria-hidden`, because
the child is the only thing that knows what it renders. A boundary that stamped
attributes would have to guess at a node it never sees.

Read the phase inside a **declared, keyed child view**, as above. Reading it in
markup written inline in the parent is a trap: those props are evaluated during
the *parent's* render, so the phase you get is the parent's, not the per-child one
you meant.

Outside any presence boundary `v/presence-phase` answers `:present`, so a
phase-aware child stays reusable anywhere.

## Phases

Every keyed child moves through:

| Phase | Meaning |
|---|---|
| `:mounting` | just entered |
| `:present` | steady state |
| `:unmounting` | removed from the source data, still rendered until the timeout |

Rules of the road:

1. **Timeout is mandatory.** Presence does not wait on CSS `transitionend`. Set
   `:timeout-ms` to match or slightly exceed your longest exit transition so the
   node is never stuck forever if CSS fails or is disabled.
2. **Re-entry cancels exit.** If the same key is removed then restored before
   timeout, the child returns to `:present` per the state machine. Exit does not
   “finish” then remount as a brand-new enter unless the re-entry rule says so —
   Freehand follows the absorbed presence machine: re-entry cancels removal.
3. **Order is for enter/exit, not live sort.** Presence freezes first-appearance
   slots so an exiting child does not jump mid-animation. If the list must re-sort
   continuously, order at the data/presentation layer. Do not expect presence to be
   a reorder animator.
4. **No wrapper DOM node.** The boundary inserts nothing into the tree, so there
   is no extra element to style around or to break your CSS selectors.
5. **Removal is terminal and exactly-once.** When the timeout fires the child is
   gone for good; re-entry before then interrupts the exit and returns it to
   `:present`.

## CSS pairing

Presence does not animate. A minimal pairing:

```css
.toast {
  /* enter: animation on insertion is robust; see note below */
  animation: toast-in 200ms ease-out;
  transition: opacity 250ms, translate 250ms;
}
@keyframes toast-in {
  from { opacity: 0; translate: 0 8px; }
}

/* exit: drive off the class the child stamps while :unmounting */
.toast--exit {
  opacity: 0;
  translate: 0 8px;
}

@media (prefers-reduced-motion: reduce) {
  .toast {
    animation: none;
    transition: none;
  }
}
```

The class name is entirely yours — presence never writes one. Keep the **exit
transition duration ≤ `:timeout-ms`**, or the timeout will cut the animation off.

**Enter tip:** driving enter purely as a `:mounting` → `:present` class flip can
race paint. The flip may land
before the browser ever paints mounting styles. An **animation on insertion** (or
modern `@starting-style`) is more reliable. Exit is happier as a class/attr
transition under `:unmounting` because the node is already painted.

## When structure depends on phase

`v/presence-phase` is a plain read, so a child may branch on it structurally, not
only stylistically:

```clojure
(v/defview toast-card [{:keys [toast]}]
  (case (v/presence-phase)
    :unmounting [:div.toast.toast--exit {:inert true} (:message toast)]
    [:div.toast (:message toast)]))
```

Prefer styling over branching where you can — a node that keeps its identity
across the phase change is the one CSS can transition. A structural branch
replaces the node, and a replaced node has no transition to run.

## Accessibility

While a node is retained as `:unmounting` it is still in the document: it can take
focus and clicks unless the child says otherwise. Since the boundary stamps
nothing, that is the child's job, and it is two attributes:

```clojure
(v/defview toast-card [{:keys [toast]}]
  (let [exiting? (= :unmounting (v/presence-phase))]
    [:div.toast {:class       (when exiting? "toast--exit")
                 :inert       (when exiting? true)
                 :aria-hidden (when exiting? true)}
     (:message toast)]))
```

Honour `prefers-reduced-motion` in CSS. The timeout still clears the node — it is
a clock, not a transition listener — so reduced motion loses the animation without
losing the removal.

Broader a11y (names, native elements, top-layer focus, test lanes):
**[Accessibility](accessibility.md)**.

## What presence never does

Presence phases are **presentation**. They never:

| Not presence’s job | Who owns it |
|---|---|
| Dispatch domain mount/unmount events | you don’t — don’t invent them |
| Fetch, seed, or cancel resources | routes, resources, machines |
| Clear controller / domain state | [semantic transitions / owners](semantic-controllers.md) |
| Focus traps, scroll lock, “modal UX” | top-layer + your a11y patterns / host |
| Positioning / measurement | CSS anchors or a [behavior](host-boundaries.md) |
| Springs, timeline choreography | foreign animation lib behind a host boundary |

Presence is a visual retention machine. It is not a lifecycle hook, and a node
lingering on screen is not a reason for anything in app-db to linger with it.

## JVM, SSR, and hydration

There is no animation clock on the JVM, and `v/presence-phase` always answers
`:present` there. A structural render therefore shows you the steady-state tree —
the plan, not a fake client timeline.

Hydration adopts the server's markup, which was rendered at `:present`, so enter
animations are not replayed over already-painted content. A pure client mount does
start at `:mounting` and flip to `:present`.

## Testing

| Goal | Approach |
|---|---|
| “The steady-state tree is right” | `t/render` — the structural tier renders every child at `:present` |
| “The exit class appears while unmounting” | a mounted browser test; the phase only moves on a real clock |
| “The node is gone after retention” | a mounted browser test |
| Real CSS / reduced motion | a real browser suite |

The structural tier has no presence clock and no fake one — see
[Testing](testing.md). Do not assert wall-clock animation frames in unit tests.

## Common mistakes

| Mistake | What goes wrong | Fix |
|---|---|---|
| Missing `:timeout-ms` | illegal / stuck retention | always a positive literal duration |
| Unkeyed children | identity broken; compile error when compiled | stable `{:key …}` per child |
| Timeout shorter than CSS exit | snap remove mid-fade | timeout ≥ transition |
| No `inert` / `aria-hidden` on exit | focusable ghosts | the child sets them while `(= :unmounting (v/presence-phase))` |
| Domain “unmount” events for cleanup | lifetime coupled to animation | owner/route cleanup; presence is visual only |
| Wrapping every list “just in case” | cost and complexity | only when design needs enter/exit |

## Pairing with top layer and host UI

| Concern | Tool |
|---|---|
| Popover/dialog **open** desired state | ordinary re-frame state plus the native HTML attributes |
| Timed exit after close | `v/presence` around the panel/toast content |
| Measure / position | CSS anchor or registered behavior |
| Framer Motion / rich motion | a [registered behavior](host-boundaries.md), or a React component in a child position |

The two compose: open state is ordinary re-frame, exit retention is presence.
Neither replaces the other.

## When not to use it

- Instant remove is fine for most lists and pages — **default to no presence**.
- Reorder animation, shared-element transitions, gesture physics — not this API;
  use a host boundary for those libraries.
- “I need something to run on unmount” for **data** — wrong tool; use causal
  ownership (routes, machines, controller clear).

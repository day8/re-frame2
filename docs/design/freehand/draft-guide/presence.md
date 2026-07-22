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

## Basic shape (attribute overrides — paved path)

Freehand’s common path stamps phase as **data on the node**: base attrs plus
optional `::v/mounting` / `::v/unmounting` overrides.

```clojure
(v/defview toast-tray [_]
  (v/presence {:timeout-ms 300}
    (for [t (v/sub [:toasts/visible])]
      [:div.toast
       {:key (:id t)
        :class ["transition-opacity" "opacity-100"]
        ::v/mounting   {:class ["opacity-0"]}
        ::v/unmounting {:class ["opacity-0"]
                        :inert true
                        :aria-hidden true}}
       (:message t)])))
```

| Piece | Role |
|---|---|
| `v/presence` | boundary that retains exiting keyed children |
| `:timeout-ms` | **mandatory** positive duration — retention length **and** hard terminal bound |
| `{:key …}` | **required** on each dynamic child — identity for the state machine |
| base attrs | apply while `:present` (and after mounting overrides yield) |
| `::v/mounting` | attrs for the initial committed mounting phase, then yield to base |
| `::v/unmounting` | attrs while retained as exiting |

### What overrides may and may not change

| Allowed | Forbidden |
|---|---|
| presentation (class, style, …) | `:key` |
| a11y (`inert`, `aria-hidden`, …) | children / structure |
| other non-structural attrs the host allows | controlled `:value` / `:checked` |
| | refs, event ownership / handler identity |

Overrides are presentation and accessibility only. They do not rewrite what the
node *is* or who owns its events.

## Phases

Every keyed child moves through:

| Phase | Meaning |
|---|---|
| `:mounting` | just entered; mounting overrides apply |
| `:present` | steady state; base attrs |
| `:unmounting` | removed from source data but still rendered until timeout |

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
4. **No wrapper DOM node** is required by the model. The boundary is a Freehand
   construct, not an extra `<div>` you style. (Host implementation may use internal
   machinery; author-facing contract is still keyed children + phases.)

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

/* exit: drive off unmounting presentation (class and/or override attrs) */
.toast.opacity-0,
.toast[data-presence="unmounting"] {
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

Exact class names depend on how you map overrides (class lists in the example
above vs a data attribute). Keep **exit transition duration ≤ `:timeout-ms`**.

**Enter tip (from the donor presence guide, still good advice):** driving enter
purely as a `.mounting` → `.present` class flip can race paint. The flip may land
before the browser ever paints mounting styles. An **animation on insertion** (or
modern `@starting-style`) is more reliable. Exit is happier as a class/attr
transition under `:unmounting` because the node is already painted.

## `v/presence-phase` — when structure depends on phase

Attribute overrides cover most toast/modal chrome. When a child’s **structure**
(not just attrs) must change with phase, read the phase inside a **declared**
child view:

```clojure
(v/defview toast-card [{:keys [toast]}]
  (let [phase (v/presence-phase)]   ; :mounting | :present | :unmounting
    (case phase
      :unmounting [:div.toast.exit (:message toast)]
      [:div.toast (:message toast)])))

(v/defview toast-tray [_]
  (v/presence {:timeout-ms 300}
    (for [t (v/sub [:toasts/visible])]
      [toast-card {:key (:id t) :toast t}])))
```

Prefer a **keyed `v/defview` child** when reading phase. Inline literal markup is
easy to get wrong: props are evaluated in the parent’s render, so the phase
context may not be the per-child one you meant. Outside any presence boundary,
`v/presence-phase` should behave as **`:present`** so phase-aware children remain
reusable.

Use phase-structure sparingly. Overrides keep more of the tree as plain data.

## Accessibility

While a node is retained as `:unmounting` it can still receive focus and clicks
unless you say otherwise. The paved override includes:

```clojure
::v/unmounting {:inert true
                :aria-hidden true
                ;; plus your visual exit classes
                }
```

Development checks should warn when retained interactive content lacks `inert` or
equivalent assistive hiding. Honour `prefers-reduced-motion` in CSS so timeout
still clears the node without motion.

Broader a11y (names, native elements, top-layer focus, test lanes):
**[Accessibility](accessibility.md)**.

## What presence never does

Presence phases and overrides are **presentation and tool evidence**. They never:

| Not presence’s job | Who owns it |
|---|---|
| Dispatch domain mount/unmount events | you don’t — don’t invent them |
| Fetch, seed, or cancel resources | routes, resources, machines |
| Clear controller / domain state | [semantic transitions / owners](semantic-controllers.md) |
| Focus traps, scroll lock, “modal UX” | top-layer + your a11y patterns / host |
| Positioning / measurement | CSS anchors or a [behavior](host-boundaries.md) |
| Springs, timeline choreography | foreign animation lib behind a host boundary |

Mounting, present, re-entry, unmounting, and removal **are** queryable as
development facts (occurrence + generation) for Xray and tests. That is tool
data, not app-db lifecycle ownership.

## JVM, SSR, and hydration

On the JVM structural tree there is no real animation clock. Emit **present/base**
structure with **presence metadata** so tests can see the plan, not a fake client
timeline.

On SSR/hydration, adopt server-rendered children at **`:present`** and avoid
replaying enter animations over already-painted markup. A pure client mount may
still start at `:mounting` then flip to `:present`.

## Testing

| Goal | Approach |
|---|---|
| “Exit attrs appear when data drops” | structural or mounted tree; assert overrides / phase |
| “Node gone after retention” | `t/flush-presence!` advances a **fake clock** — not `Thread/sleep` |
| Real CSS / reduced motion | real browser suite |

See [Testing](testing.md). Don’t assert wall-clock animation frames in unit tests.

## Common mistakes

| Mistake | What goes wrong | Fix |
|---|---|---|
| Missing `:timeout-ms` | illegal / stuck retention | always a positive literal duration |
| Unkeyed children | identity broken; compile error when compiled | stable `{:key …}` per child |
| Timeout shorter than CSS exit | snap remove mid-fade | timeout ≥ transition |
| No `inert` / `aria-hidden` on exit | focusable ghosts | set them on `::v/unmounting` |
| Domain “unmount” events for cleanup | lifetime coupled to animation | owner/route cleanup; presence is visual only |
| Wrapping every list “just in case” | cost and complexity | only when design needs enter/exit |

## Pairing with top layer and host UI

| Concern | Tool |
|---|---|
| Popover/dialog **open** desired state | top-layer intrinsics (`::web/popover-open?`, …) |
| Timed exit after close | `v/presence` around the panel/toast content |
| Measure / position | CSS anchor or registered behavior |
| Framer Motion / rich motion | Freehand **host leaves** (or behaviors) — still Freehand |

Presence and top-layer compose: open state is re-frame + web host; exit retention
is presence. Neither replaces the other.

## When not to use it

- Instant remove is fine for most lists and pages — **default to no presence**.
- Reorder animation, shared-element transitions, gesture physics — not this API;
  use a host boundary for those libraries.
- “I need something to run on unmount” for **data** — wrong tool; use causal
  ownership (routes, machines, controller clear).

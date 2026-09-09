# re-frame.hicasso.overlay

The optional overlay module. Two heads, and the module owns exactly one thing
about an overlay: **the imperative call** that enters the browser's top layer.

```clojure
(:require [re-frame.hicasso :as h]
          [re-frame.hicasso.overlay :as overlay])
```

Every other part of an overlay has an owner already. `<dialog>` owns modality —
the page behind it goes inert, enforced by the engine rather than by a key
handler. `popover` owns light dismiss and the auto stack. The top layer owns paint
order, so no ancestor's `overflow`, `transform` or `z-index` can clip or out-stack
a panel. CSS anchor positioning owns where a panel sits. What none of them owns is
the entry: an element does not reach the top layer by having an attribute, so this
module calls `showModal` / `showPopover` at the one moment React offers before
paint, and calls the inverse before React takes the node away.

There is **no** portal, focus-trap loop, `document` key listener, outside-click
listener, z-index policy, scroll or resize listener, `ResizeObserver`, measurement
of any kind, and no positioning engine. This is not a floating-UI library.

This page is the manifest-tracked index of the module's public vars; the full
option table and the focus rules are taught in
[Overlays and focus](../core/hicasso/13-overlays-and-focus.md).

Both heads are legal hiccup heads, marked the way a `defview` product is — though
neither is a Hicasso *reactive* boundary: they read no subscription and hold no
cell. On both, `:open?` false renders nothing at all — no element, no listener, no
anchor claim — and every key that is not the head's own reaches the element
unrenamed.

## The heads

### `popover`

- **Kind**: Var (view)
- **Signature**:
  ```clojure
  [overlay/popover {:open?      open?
                    :on-dismiss event-v
                    :label      string?
                    :anchor     trigger-dom-id
                    :placement  compass-word}
   child …]
  ```
- **Description**: An anchored, light-dismissable panel on the browser's own top
  layer.
  - `:anchor` is the **DOM id** of the trigger. The module gives that element a
    generated CSS anchor name while the panel is open and puts back whatever it
    found on the way out. An `:anchor` naming no element refuses with
    `:rf.error/hicasso-overlay-anchor-missing`; omitting it stays legal and
    silent.
  - `:placement` is the compass word that becomes a `position-area` against the
    anchor. A `:placement` outside the known table is **not refused** — it is
    passed through as a literal `position-area` value.
  - With `:on-dismiss` the panel is a `popover="auto"` and takes its place in the
    platform's LIFO stack; without one it is `popover="manual"` and dismisses for
    nothing, because a dismissal with nowhere to go is how an open flag acquires a
    second owner.
- **Example**:
  ```clojure
  [overlay/popover {:open?      (h/sub [:menu/open? id])
                    :on-dismiss [:menu/dismissed id]
                    :anchor     trigger-id
                    :placement  :bottom-start}
   [:ul {:role "menu"} …]]
  ```

### `modal`

- **Kind**: Var (view)
- **Signature**:
  ```clojure
  [overlay/modal {:open?          open?
                  :on-dismiss     event-v
                  :label          string?
                  :light-dismiss? boolean?}
   child …]
  ```
- **Description**: A blocking dialog on the browser's own top layer, opened with
  `showModal`.
  - Modality is the engine's: the rest of the document is inert and `::backdrop`
    is a real CSS selector. Focus cannot Tab out of the dialog — inertness is what
    stops it reaching the page, and the module's own two-edge wrap is what makes
    the last control Tab straight back to the first rather than through `<body>`.
  - Escape dispatches `:on-dismiss`; a backdrop click does so only with
    `:light-dismiss? true` (default false), because a destructive confirmation
    must not go away on a stray click. Without `:on-dismiss` the dialog honours no
    close request at all.
  - Initial focus is **tree order** — the platform's own dialog-focusing steps
    take the first focusable control, so order the controls rather than reaching
    for an autofocus attribute.
- **Example**:
  ```clojure
  [overlay/modal {:open?      (h/sub [:invoice/confirm-delete? id])
                  :on-dismiss [:invoice/delete-cancelled id]
                  :label      "Confirm deletion"}
   [:h2 "Delete this invoice?"]
   [:button {:on-click [:invoice/delete-cancelled id]} "Keep it"]
   [:button {:on-click [:invoice/deleted id]} "Delete"]]
  ```

## See also

- [Overlays and focus](../core/hicasso/13-overlays-and-focus.md) — the chapter
  that governs the surface, and the full option table
- [Hicasso API reference](../core/hicasso/api-reference.md) — the full contract
- [`re-frame.hicasso`](re-frame.hicasso.md) — the door

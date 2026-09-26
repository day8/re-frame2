# re-frame.fresco.overlay

Use this module for popovers and modal dialogs on the browser's own top layer.
`popover` and `modal` make the one call an attribute cannot, `showPopover` or
`showModal`, and leave everything else to the platform.

It is an optional namespace: `re-frame.fresco` does not require it, so an
application that never requires it carries none of its code.

```clojure
(:require [re-frame.fresco :as h]
          [re-frame.fresco.overlay :as overlay])
```

The platform handles the rest of an overlay. `<dialog>` makes the page behind it
inert, enforced by the engine rather than by a key handler. `popover` provides
light dismiss (a click outside or Escape closes it) and keeps nested popovers in
last-opened, first-closed order. The top layer paints above everything, so no
ancestor's `overflow`, `transform` or `z-index` can clip or out-stack a panel. CSS
anchor positioning places the panel. What none of them does is enter the top layer:
an element does not get there by having an attribute, so the module calls
`showModal` / `showPopover` at the point React offers before paint, and the inverse
before React removes the node.

There is no portal, focus-trap loop, `document` key listener, outside-click
listener, z-index policy, scroll or resize listener, `ResizeObserver`, measurement
of any kind, or positioning engine. [Overlays and
focus](../core/fresco/13-overlays-and-focus.md) teaches the full option table and
the focus rules.

## The heads

Both are legal hiccup heads but not Fresco views: they read no subscriptions. On
both, `:open?` false renders nothing at all — no element, no listener, no anchor
name — so an overlay is open exactly when your `app-db` says so. `:label` is the
accessible name, set as `aria-label`. Every prop that is not the head's own reaches
the element unchanged, except the ones the module writes itself: `:ref` on both,
`:on-cancel`, `:on-key-down` and `:closedby` on a modal, and `:on-before-toggle` and
`:popover` on a popover. A value you pass at one of those is replaced, and `:label`,
when given, replaces an `:aria-label`. A `:style` you pass is merged, with your keys
winning over the `position-area` that `:placement` sets. An `:on-dismiss` with no
frame in scope raises
`:rf.error/fresco-intent-outside-boundary`, since nothing could dispatch it.

### `popover`

- **Kind**: var (usable as a hiccup head)
- **Signature**:
  ```clojure
  [overlay/popover {:open?      open?
                    :on-dismiss event-v
                    :label      string?
                    :anchor     trigger-dom-id
                    :placement  compass-word}
   child …]
  ```
- **Description**: Renders an anchored, light-dismissable panel on the browser's
  top layer.
    - `:anchor` is the DOM id of the trigger. While the panel is open, the module
      gives that element a generated CSS anchor name, and restores whatever it found
      when the panel closes. Changing `:anchor` on an open panel moves it to the new
      trigger without closing it. An `:anchor` naming no element raises
      `:rf.error/fresco-overlay-anchor-missing`; omitting `:anchor` is legal.
    - `:placement` is one of `:top`, `:bottom`, `:left` or `:right`, alone or with
      `-start` or `-end` (`:bottom-start` lines the panel's left edge up with the
      trigger's). It becomes a CSS `position-area` against the anchor. Any other
      value is passed through as a literal `position-area` string rather than
      rejected, so a misspelt word is an invalid CSS value and the panel lands at
      the browser's default position.
    - With `:on-dismiss`, the panel is `popover="auto"` and joins the platform's
      stack of open popovers. Without it, the panel is `popover="manual"` and nothing dismisses
      it, because a dismissal nothing handles would leave the browser, instead of
      your `:open?` value, deciding whether the panel is open.
- **Example**:
  ```clojure
  [overlay/popover {:open?      (h/sub [:menu/open? id])
                    :on-dismiss [:menu/dismissed id]
                    :anchor     trigger-id
                    :placement  :bottom-start}
   [:ul {:role "menu"} …]]
  ```

### `modal`

- **Kind**: var (usable as a hiccup head)
- **Signature**:
  ```clojure
  [overlay/modal {:open?          open?
                  :on-dismiss     event-v
                  :label          string?
                  :light-dismiss? boolean?}
   child …]
  ```
- **Description**: Renders a blocking dialog on the browser's top layer, opened
  with `showModal`.
    - The engine provides modality: the rest of the document is inert, and
      `::backdrop` is a real CSS selector. Inertness keeps Tab from reaching the
      page, and the module wraps focus at both ends, so Tab from the last control
      goes back to the first instead of through `<body>`.
    - Escape dispatches `:on-dismiss`. A backdrop click does so only with
      `:light-dismiss? true` (default false), so a destructive confirmation does not
      close on a stray click. Without `:on-dismiss`, the dialog ignores every close
      request.
    - Initial focus goes to the first focusable control in tree order, by the
      platform's own dialog focusing steps, so order the controls instead of using
      an autofocus attribute.
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

- [Fresco API reference](../core/fresco/api-reference.md) — the full contract.
- [`re-frame.fresco`](re-frame.fresco.md) — `h/portal`, for containers the
  application does not own.

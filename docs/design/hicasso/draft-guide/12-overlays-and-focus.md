# Overlays and focus

You need a filter menu anchored to its button, or a delete confirmation that
blocks the page behind it. Hand-rolled portals, z-index ladders, document
click listeners, and focus traps leak and break in predictable ways.

`re-frame.hicasso.overlay` gives two primitives — [popover](glossary.md#overlay)
and modal — that mount on the browser's native top layer (`popover` /
`<dialog>`). The browser handles stacking, light-dismiss, and focus. Your app
stores one open flag and handles one dismiss event.

## An anchored popover

The open flag is ordinary app-db state
([Ephemeral state](11-ephemeral-state.md)):

```clojure
(ns app.views.filters
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.overlay :as overlay]))

(rf/reg-sub :filter-menu/open?
  (fn [db [_ id]] (get-in db [:ui :filter-menu/open id] false)))

(rf/reg-event :filter-menu/toggled
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:ui :filter-menu/open id] not)}))

(rf/reg-event :filter-menu/dismissed
  (fn [{:keys [db]} [_ id]]
    {:db (assoc-in db [:ui :filter-menu/open id] false)}))

(h/defview filter-menu [{:keys [id]}]
  (let [open? (h/sub [:filter-menu/open? id])]
    [:div.filter
     [:button {:id (str "filter-" id "-trigger")
               :aria-haspopup "menu"
               :aria-expanded open?
               :on-click [:filter-menu/toggled id]}
      "Filter"]
     [overlay/popover {:open?      open?
                       :on-dismiss [:filter-menu/dismissed id]
                       :anchor     (str "filter-" id "-trigger")
                       :placement  :bottom-start}
      [:ul {:role "menu"}
       [:li [:button {:role "menuitem"
                      :on-click [:filter/applied id :unread]}
             "Unread"]]
       [:li [:button {:role "menuitem"
                      :on-click [:filter/applied id :flagged]}
             "Flagged"]]]]]))
```

What those options do:

- **`:open?`** — while false, the popover renders nothing: no DOM node, no
  listener, and the body's subscriptions do not run. When true, the panel
  mounts on the top layer, above every stacking context. Ancestor
  `overflow: hidden` and `transform` do not clip it. There is no
  [portal](glossary.md#portal) and no z-index.
- **`:on-dismiss`** — outside click and Esc are the popover's native
  light-dismiss. The browser closes the panel and the module dispatches your
  event. Your handler must write the flag false. If the handler leaves the
  flag true, the panel stays open — app-db is the owner.
- **`:anchor`** — DOM id of the trigger, unique per instance. The module
  places the panel before first paint. `:placement` uses compass words such
  as `:bottom-start`, `:bottom-end`, `:top-start`, and `:right`. Where the
  browser supports CSS anchor positioning, tracking is CSS; otherwise the
  module re-places on open and resize.
- **The body stays in the tree.** It renders under the same frame as its
  anchor, so subscriptions resolve as they do in-flow.

## A modal

A [modal](glossary.md#overlay) has the same shape with stronger focus behaviour.
`overlay/modal` drives a native `<dialog>` through `showModal`:

```clojure
(rf/reg-sub :invoice/confirm-delete?
  (fn [db [_ id]] (get-in db [:ui :invoice/confirm-delete id] false)))

(rf/reg-event :invoice/delete-cancelled
  (fn [{:keys [db]} [_ id]]
    {:db (assoc-in db [:ui :invoice/confirm-delete id] false)}))

(rf/reg-event :invoice/delete-confirmed
  (fn [{:keys [db]} [_ id]]
    {:db (assoc-in db [:ui :invoice/confirm-delete id] false)
     :fx [[:dispatch [:invoice/delete id]]]}))

(h/defview confirm-delete [{:keys [invoice-id]}]
  [overlay/modal {:open?      (h/sub [:invoice/confirm-delete? invoice-id])
                  :on-dismiss [:invoice/delete-cancelled invoice-id]
                  :label      "Confirm deletion"}
   [:h2 "Delete this invoice?"]
   [:p "This cannot be undone."]
   [:footer
    [:button {:on-click [:invoice/delete-cancelled invoice-id]} "Keep it"]
    [:button.danger {:auto-focus true
                     :on-click [:invoice/delete-confirmed invoice-id]}
     "Delete"]]])
```

- The background is inert: focus cannot Tab out, clicks do not land, and
  assistive technology skips it. The browser owns the trap.
- Esc dispatches `:on-dismiss`. Backdrop click does too only when you pass
  `:light-dismiss? true`. The default is off so a destructive confirmation
  does not close on a stray click.
- **`:label`** is the dialog's accessible name.
- Style the backdrop with `::backdrop` in CSS
  ([Theming](13-theming-and-i18n.md)).

## Focus

Focus is browser state. Do not mirror "what has focus" into app-db
([Ephemeral state](11-ephemeral-state.md)). Express intent once per open:

- **Mount focus.** Put `:auto-focus true` on the element that should receive
  focus when the overlay opens — the Delete button above, or a search field
  in a command palette. The attribute fires when the overlay opens, not again
  on re-render, and it is inert markup on the server. A modal with no
  `:auto-focus` focuses the dialog itself. A popover leaves focus on the
  trigger (menus and comboboxes usually operate with
  `:aria-activedescendant`, not focus moves).
- **Restore on close.** Both primitives return focus to the element that had
  it at open — on dismiss, Esc, and programmatic close. You write nothing for
  restore.

## Nesting

A popover can sit inside a modal, and a submenu inside a menu. The native top
layer is a stack, so nesting is last-in-first-out. Esc closes only the
innermost open overlay. An outside click on an inner popover light-dismisses
that popover and leaves the modal under it alone.

Use one address and one `:on-dismiss` per overlay. If two overlays share one
dismiss event, you rebuild the problem the stack solves.

## Cost while closed

A closed overlay costs nothing:

- no DOM node (client or server output)
- no listener (light-dismiss exists only while open)
- no subscriptions (the body is not mounted)
- clean teardown: unmount of a view whose overlay is open closes it, restores
  focus, and leaves no residue

A table of five hundred rows, each with a closed row-menu, is as heavy as the
same table without menus. You pay only for the open one.

## Compose a dropdown from the popover

Dropdowns, comboboxes, and toggletips are not separate primitives. Each is a
popover plus your own events and subs. Single-select with keyboard support:

```clojure
(rf/reg-sub :combo/open?
  (fn [db [_ id]] (get-in db [:ui :combo id :open?] false)))

(rf/reg-sub :combo/active
  (fn [db [_ id]] (get-in db [:ui :combo id :active])))

(rf/reg-event :combo/toggled
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:ui :combo id :open?] not)}))

(rf/reg-event :combo/dismissed
  (fn [{:keys [db]} [_ id]]
    {:db (assoc-in db [:ui :combo id :open?] false)}))

(rf/reg-event :combo/moved
  (fn [{:keys [db]} [_ id step values]]
    (let [at   (get-in db [:ui :combo id :active])
          i    (get (zipmap values (range)) at -1)
          next (nth values (-> (+ i step) (max 0) (min (dec (count values)))))]
      {:db (-> db
               (assoc-in [:ui :combo id :open?] true)
               (assoc-in [:ui :combo id :active] next))})))

(rf/reg-event :combo/committed
  (fn [{:keys [db]} [_ id on-commit]]
    (let [{:keys [open? active]} (get-in db [:ui :combo id])]
      (cond-> {:db (assoc-in db [:ui :combo id :open?] false)}
        (and open? active) (assoc :fx [[:dispatch (conj on-commit active)]])))))

(rf/reg-event :combo/selected
  (fn [{:keys [db]} [_ id on-commit value]]
    {:db (assoc-in db [:ui :combo id :open?] false)
     :fx [[:dispatch (conj on-commit value)]]}))

(h/defview select-dropdown [{:keys [id items value on-commit placeholder]}]
  (let [open?  (h/sub [:combo/open? id])
        active (h/sub [:combo/active id])
        values (mapv :value items)
        label  (or (some #(when (= value (:value %)) (:label %)) items)
                   placeholder)]
    [:div.combo
     [:button {:id (str "combo-" id "-trigger")
               :aria-haspopup "listbox"
               :aria-expanded open?
               :aria-activedescendant (when (and open? active)
                                        (str "combo-" id "-opt-" active))
               :on-click    [:combo/toggled id]
               :on-key-down {"ArrowDown" [::h/prevent [:combo/moved id 1 values]]
                             "ArrowUp"   [::h/prevent [:combo/moved id -1 values]]
                             "Enter"     [:combo/committed id on-commit]}}
      label]
     [overlay/popover {:open?      open?
                       :on-dismiss [:combo/dismissed id]
                       :anchor     (str "combo-" id "-trigger")
                       :placement  :bottom-start}
      [:ul {:role "listbox"}
       (for [{:keys [value label]} items]
         [:li {:key value
               :id (str "combo-" id "-opt-" value)
               :role "option"
               :aria-selected (= value active)
               :on-click [:combo/selected id on-commit value]}
          label])]]]))
```

What is not here:

- no Escape row — Esc is light-dismiss; the platform closes and `:on-dismiss`
  fires
- no focus moves — focus stays on the trigger
- no document listener
- no portal

Open / move / commit / dismiss is events over an address. A test can drive
the policy headlessly; Xray shows it. A toggletip is the same popover with a
single dismiss event and no listbox.

## When not to use the module

Prefer the platform first. Use the module when open state must be application
data, or when you need anchored placement and focus behaviour.

- **Hover tooltip** — CSS `:hover` / `:focus-visible` plus a positioned
  pseudo-element or sibling. No app-db state; routing hover through any state
  system re-renders on pointer move.
- **Disclosure** — `<details>`. The browser tracks open.
- **Presentational hint** — bare popover markup:
  `[:button {:popovertarget "help-tip"} "?"]` with
  `[:div {:id "help-tip" :popover "auto"} …]`. The browser does everything.
  When a test or another view needs the flag, move up to `overlay/popover`.

## Don't build the old way

```clojure
;; Don't — pre-top-layer overlay: every classic defect in ten lines
(h/defview old-menu [{:keys [id]}]
  (let [open? (h/sub [:menu/open? id])]
    [:div {:style {:position "relative"}}
     [:button {:on-click [:menu/toggled id]} "Menu"]
     (when open?
       [:div.menu {:style {:position "fixed" :z-index 1020
                           :top "48px" :left "12px"}}
        …])]))
;; …plus a js/document click listener added on open, removed on close.
```

Failures that follow:

- Unmount while open leaks the document listener.
- One ancestor `transform` turns `position: fixed` into the wrong position.
- Z-index values hold only until the next library brings a larger one.

The module removes the class: no listener to leak, no stacking context to lose
to, no z-index contest.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Panel clipped by `overflow: hidden` or stuck under a sticky header | Panel is an in-flow positioned div, not on the top layer | Render through `overlay/popover` |
| Outside click closes the popover but it re-opens | Light-dismiss fired and `:on-dismiss` ran, but the handler never cleared the flag | Make the `:on-dismiss` handler set open to false |
| Esc closes the whole stack at once | Layers share one dismiss event or one address | One address and one `:on-dismiss` per overlay |
| Focus lands on `<body>` after close | Opener unmounted while the overlay was open — often unstable list keys remounting the trigger | Stable `:key` on the trigger's row |
| `:rf.error/hicasso-overlay-anchor-missing` at open | `:anchor` names an id not in the document, or two instances share one id | Per-instance ids: `(str "combo-" id "-trigger")` |
| Dialog shows but the page behind still scrolls and clicks | Hand-written `[:dialog {:open true}]` — the `open` attribute is the non-modal path | Use `overlay/modal`, which calls `showModal` |
| Popover flashes at the wrong position for one frame | Something positions after mount | Pass `:anchor` / `:placement`; the module places before first paint |
| Every row's menu opens at once | One shared address | Key the address per instance ([Ephemeral state](11-ephemeral-state.md#choosing-the-address)) |

??? info "If you're coming from Reagent or UIx"
    The usual stack is a portal + z-index + floating-ui + a document listener.
    Here the panel never leaves its place in the tree — the top layer changes
    paint order, not tree order. Frame context, subscriptions, SSR, and
    hydration need no special path, and light-dismiss arrives without a
    listener to leak. You still own the open flag and the meaning of a
    selection.

## Advanced

**Entry animation is pure CSS.** The panel mounts on open, so
`@starting-style` (or a keyframe on the class) animates entry with no state
and no timers:

```css
.menu[popover]:popover-open { opacity: 1; transition: opacity 150ms; }
@starting-style { .menu[popover]:popover-open { opacity: 0; } }
```

**Exit animation needs a clock.** On dismiss the panel normally leaves with
the flag. To let a fade finish, pass `:exit-ms`. The module keeps the node on
the top layer for that time (inert, `aria-hidden`) and removes it when the
clock ends even if the CSS did not run. A re-open before the deadline cancels
the exit; an unmount cancels the timer. State is closed the whole time; only
the paint remains.

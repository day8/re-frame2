# Overlays and focus

You need a filter menu anchored to its button, and a delete confirmation that
blocks the page behind it. The classic build — a portal, a z-index ladder, a
document click listener, hand-rolled focus management — leaks and breaks in
known ways. `re-frame.hicasso.overlay` gives you two primitives, popover and
modal, that mount on the browser's **native top layer** (`popover` /
`<dialog>`). The platform owns stacking, dismissal, and focus. Your app owns
exactly one thing: the data.

> **Open is a boolean at an address, dismissal is an event, and the browser
> owns stacking, light-dismiss, and the focus trap and return.**

## An anchored popover

The caller owns the open flag, as ordinary state at an address
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
               :aria-haspopup "menu" :aria-expanded open?
               :on-click [:filter-menu/toggled id]}
      "Filter"]
     [overlay/popover {:open?      open?
                       :on-dismiss [:filter-menu/dismissed id]
                       :anchor     (str "filter-" id "-trigger")
                       :placement  :bottom-start}
      [:ul {:role "menu"}
       [:li [:button {:role "menuitem" :on-click [:filter/applied id :unread]} "Unread"]]
       [:li [:button {:role "menuitem" :on-click [:filter/applied id :flagged]} "Flagged"]]]]]))
```

What the module does with those four options:

- **`:open?` is the one owner.** While the flag is false, the popover renders
  *nothing*: no DOM node, no listener, and the body's reads never run. When
  the flag goes true, the panel mounts directly onto the top layer. There the
  panel sits above every stacking context, and no ancestor `overflow: hidden`
  or `transform` can affect it. There is no portal and no z-index anywhere.
- **`:on-dismiss` is how the platform reports.** Outside click and Esc are
  the popover's native light-dismiss. There is no document listener. The
  browser closes the panel, and the module dispatches your event. Your
  handler writes the flag, and the panel follows the address. If the handler
  intentionally keeps the flag true, the panel stays open — state owns the
  platform, never the reverse.
- **`:anchor` names the trigger by DOM id**, per instance. The module
  computes placement before the first paint, so there is no wrong-position
  flash. `:placement` takes the compass words: `:bottom-start`,
  `:bottom-end`, `:top-start`, `:right`, and the others. Where the browser
  has CSS anchor positioning, the tracking is pure CSS. Elsewhere the module
  re-places the panel on open and on resize — a declared contract, never an
  every-frame loop.
- **The body never leaves the tree.** The body renders in place, under the
  same frame as its anchor, so its subscriptions resolve exactly as they do
  in-flow. Nothing special propagates, because nothing moved.

## A modal

A modal has the same shape and stronger conduct. `overlay/modal` drives a
native `<dialog>` through `showModal`, and that call is the source of the
focus contract:

```clojure
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

- **The background is inert.** Focus cannot Tab out, clicks land nowhere, and
  assistive technology skips the background. The platform owns the trap, not
  a keydown handler.
- **Esc dispatches `:on-dismiss`.** A click on the backdrop does too when you
  pass `:light-dismiss? true`. The default is off, because a destructive
  confirmation should not close under a stray click.
- **`:label`** is the dialog's accessible name.
- You style the backdrop via `::backdrop` in CSS
  ([Theming](13-theming-and-i18n.md)).

## Focus is a one-shot intent

Focus is platform state. You never mirror "what has focus" into app-db
([Ephemeral state](11-ephemeral-state.md)). What you *can* express is intent,
as data, exactly once per open:

- **Mount focus.** Put `:auto-focus true` on the element that must receive
  focus when the overlay opens — the Delete button above, or the search field
  in a command palette. The attribute fires when the overlay opens, never
  again on a re-render, and it is inert markup on the server. A modal with no
  `:auto-focus` focuses the dialog itself. A popover leaves focus on the
  trigger, which is what menus and comboboxes want: the panel is operated
  with `:aria-activedescendant`, not with focus moves.
- **Restore on close.** Both primitives return focus to the element that had
  focus at open — on dismiss, on Esc, and on programmatic close alike. There
  is nothing to write. The module's contract holds even when the close comes
  from a `:db` write in a test.

## Nesting is LIFO

A popover can sit inside a modal, and a submenu inside a menu. The native top
layer is a stack, so nesting is last-in-first-out by construction. Esc closes
only the innermost open overlay. An outside click on an inner popover
light-dismisses that popover and does not touch the modal under it. Your side
of the contract is one address and one `:on-dismiss` per overlay. If two
overlays share one dismiss event, you rebuild the problem the stack solves.

## The contract while closed

A closed overlay costs nothing. Not a small amount — nothing:

- no DOM node, on the client or in server output;
- no listener anywhere (light-dismiss is the platform's, and only while
  open);
- no subscriptions — the body is not mounted, so its reads do not exist;
- exact teardown: an unmount of a view whose overlay is open closes the
  overlay, restores focus, and leaves zero residue.

A table of five hundred rows, each with a closed row-menu, is exactly as
heavy as the same table without menus. Design with overlays freely; you pay
only for the open one.

## Compose a dropdown from the popover

Dropdowns, comboboxes, and toggletips are not separate primitives. Each is
the popover plus your own semantics as ordinary events and subs. Here is a
single-select with full keyboard support:

```clojure
(rf/reg-sub :combo/open?  (fn [db [_ id]] (get-in db [:ui :combo id :open?] false)))
(rf/reg-sub :combo/active (fn [db [_ id]] (get-in db [:ui :combo id :active])))

(rf/reg-event :combo/toggled
  (fn [{:keys [db]} [_ id]] {:db (update-in db [:ui :combo id :open?] not)}))

(rf/reg-event :combo/dismissed
  (fn [{:keys [db]} [_ id]] {:db (assoc-in db [:ui :combo id :open?] false)}))

(rf/reg-event :combo/moved            ;; consults committed state; opens if closed
  (fn [{:keys [db]} [_ id step values]]
    (let [at   (get-in db [:ui :combo id :active])
          i    (get (zipmap values (range)) at -1)
          next (nth values (-> (+ i step) (max 0) (min (dec (count values)))))]
      {:db (-> db
               (assoc-in [:ui :combo id :open?] true)
               (assoc-in [:ui :combo id :active] next))})))

(rf/reg-event :combo/committed        ;; commit reads state at fire time, not render time
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
        label  (or (some #(when (= value (:value %)) (:label %)) items) placeholder)]
    [:div.combo
     [:button {:id (str "combo-" id "-trigger")
               :aria-haspopup "listbox" :aria-expanded open?
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
               :role "option" :aria-selected (= value active)
               :on-click [:combo/selected id on-commit value]}
          label])]]]))
```

Note what is *not* here:

- no Escape row in the key map — Esc is light-dismiss; the platform closes
  the panel and `:on-dismiss` fires;
- no focus moves — focus stays on the trigger;
- no document listener;
- no portal.

The whole open/move/commit/dismiss policy is events over an address. A test
can prove the policy headlessly and seed it directly, and Xray shows it. A
toggletip — a click-to-open rich hint — is the same popover with a single
dismiss event and no listbox.

## When you don't need the module

Use the platform's own features first. The module earns its place only when
the open state must be *application* data, or when you need anchored
placement and focus conduct.

- **A hover tooltip is CSS.** Use `:hover` / `:focus-visible` plus a
  positioned pseudo-element or sibling. No state exists at all, and a route
  of hover through any state system costs a re-render per pointer-move.
- **Disclosure is `<details>`.** The platform tracks open; the triangle
  costs nothing.
- **A presentational hint can be a bare popover.** `[:button {:popovertarget "help-tip"} "?"]`
  with `[:div {:id "help-tip" :popover "auto"} …]` — the browser does
  everything, and the app never knows. That is DOM-owned state as a declared
  choice ([Ephemeral state](11-ephemeral-state.md)). When a test or another
  view needs the flag, move up to `overlay/popover`.

## Don't build the old way

```clojure
;; Don't — the pre-top-layer overlay: every classic defect in ten lines.
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

Three of its failures:

- An unmount while open leaks the document listener permanently.
- One ancestor `transform` silently changes `position: fixed` into a wrong
  position.
- The z-index values hold only until the next library brings a larger one.

The module's version removes the whole failure class: there is no listener to
leak, no stacking context to lose to, no z-index contest.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Panel clipped by `overflow: hidden` or stuck under a sticky header | The panel is an in-flow positioned div, not on the top layer | Render it through `overlay/popover` |
| Outside click closes the popover but it re-opens | Light-dismiss fired and `:on-dismiss` was dispatched, but the handler never wrote the flag — the module re-shows to match the owner | Make the `:on-dismiss` handler set open to false |
| Esc closes the whole stack at once | The layers share one dismiss event or one address | One address and one `:on-dismiss` per overlay; the platform unwinds innermost-first |
| Focus lands on `<body>` after close | The opener unmounted while the overlay was open — usually unstable list keys remounting the trigger | Stable `:key` on the trigger's row; restore targets the element focused at open, if it still exists |
| `:rf.error/hicasso-overlay-anchor-missing` at open | `:anchor` names an id that is not in the document — or two instances share one id | Per-instance anchor ids: `(str "combo-" id "-trigger")` |
| Dialog shows but the page behind still scrolls and clicks | A hand-written `[:dialog {:open true}]` — the `open` attribute is the non-modal path: no top layer, no inert background | `overlay/modal` drives `showModal` for you |
| Popover flashes at the wrong position for one frame | Something positions it after mount — usually a measuring effect of your own | Pass `:anchor` / `:placement`; the module places before first paint |
| Every row's menu opens at once | One shared address | Key the address per instance ([Ephemeral state](11-ephemeral-state.md#choosing-the-address)) |

??? info "If you're coming from Reagent or UIx"
    The instinct is a portal + z-index + floating-ui + a `with-let` document
    listener. Here the panel never leaves its place in the tree — the top
    layer changes *paint* order, not *tree* order. So frame context,
    subscriptions, SSR, and hydration need no special path, and light-dismiss
    arrives without a listener to leak. What remains yours was always
    genuinely yours: the open flag, and the meaning of a selection.

## Advanced

**Entry animation is pure CSS.** The panel mounts on open, so
`@starting-style` (or a keyframe on the class) animates the entry with no
state and no timers:

```css
.menu[popover]:popover-open { opacity: 1; transition: opacity 150ms; }
@starting-style { .menu[popover]:popover-open { opacity: 0; } }
```

**Exit animation gets a clock.** On dismiss the panel normally leaves with
the flag. To let a fade finish, pass `:exit-ms`. The module keeps the node on
the top layer for that time (inert, `aria-hidden`) and removes the node on
the clock even when the CSS did not run — the same hard-bound rule that
presence uses ([Ephemeral state](11-ephemeral-state.md)). A re-open before
the deadline cancels the exit, and an unmount cancels the timer. State is
closed the whole time; only the paint remains.

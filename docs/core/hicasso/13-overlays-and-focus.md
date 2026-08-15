# Overlays and focus

A filter menu needs to stay anchored to its button. A confirmation dialog
needs to block the page behind it and restore focus when it closes. Building
that from portals, document listeners, z-index rules, and a custom focus trap
creates several independent failure modes.

`re-frame.hicasso.overlay` provides two primitives:

- `overlay/popover` for anchored, light-dismissable UI
- `overlay/modal` for blocking dialogs

Both use the browser's native top layer. Your application still owns the open
flag and the dismiss event.

## Anchored popovers

Store the open flag in app-db
([Ephemeral state](11-ephemeral-state.md)):

```clojure
(ns app.views.filters
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.overlay :as overlay]))

(rf/reg-sub :filter-menu/open?
  (fn [db [_ id]]
    (get-in db [:ui :filter-menu/open id] false)))

(rf/reg-event :filter-menu/toggled
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:ui :filter-menu/open id] not)}))

(rf/reg-event :filter-menu/dismissed
  (fn [{:keys [db]} [_ id]]
    {:db (assoc-in db [:ui :filter-menu/open id] false)}))

(h/defview filter-menu [{:keys [id]}]
  (let [open? (h/sub [:filter-menu/open? id])
        trigger-id (str "filter-" id "-trigger")]
    [:div.filter
     [:button {:id trigger-id
               :aria-haspopup "menu"
               :aria-expanded open?
               :on-click [:filter-menu/toggled id]}
      "Filter"]

     [overlay/popover
      {:open?      open?
       :on-dismiss [:filter-menu/dismissed id]
       :anchor     trigger-id
       :placement  :bottom-start}
      [:ul {:role "menu"}
       [:li
        [:button {:role "menuitem"
                  :on-click [:filter/applied id :unread]}
         "Unread"]]
       [:li
        [:button {:role "menuitem"
                  :on-click [:filter/applied id :flagged]}
         "Flagged"]]]]]))
```

The options have direct responsibilities:

- **`:open?`** controls whether the panel exists. While false, there is no DOM
  node, listener, or body subscription.
- **`:on-dismiss`** is dispatched for native light-dismiss, including outside
  click and Escape. The handler must write the open flag false. App-db remains
  the source of truth.
- **`:anchor`** is the unique DOM id of the trigger. The module positions the
  panel before first paint.
- **`:placement`** accepts positions such as `:bottom-start`, `:bottom-end`,
  `:top-start`, and `:right`.

The panel remains in the same React and re-frame2 tree as its trigger. It uses
the same frame and subscriptions. The top layer changes paint order, so
ancestor `overflow`, transforms, and stacking contexts do not clip it.

Where CSS anchor positioning is available, the browser tracks the anchor.
Otherwise the module recalculates on open and resize.

## Modals

`overlay/modal` uses a native `<dialog>` and calls `showModal`:

```clojure
(rf/reg-sub :invoice/confirm-delete?
  (fn [db [_ id]]
    (get-in db [:ui :invoice/confirm-delete id] false)))

(rf/reg-event :invoice/delete-cancelled
  (fn [{:keys [db]} [_ id]]
    {:db (assoc-in db [:ui :invoice/confirm-delete id] false)}))

(rf/reg-event :invoice/delete-confirmed
  (fn [{:keys [db]} [_ id]]
    {:db (assoc-in db [:ui :invoice/confirm-delete id] false)
     :fx [[:dispatch [:invoice/delete id]]]}))

(h/defview confirm-delete [{:keys [invoice-id]}]
  [overlay/modal
   {:open?      (h/sub [:invoice/confirm-delete? invoice-id])
    :on-dismiss [:invoice/delete-cancelled invoice-id]
    :label      "Confirm deletion"}
   [:h2 "Delete this invoice?"]
   [:p "This cannot be undone."]
   [:footer
    [:button {:on-click [:invoice/delete-cancelled invoice-id]}
     "Keep it"]
    [:button.danger
     {:on-click [:invoice/delete-confirmed invoice-id]}
     "Delete"]]])
```

A modal gives you the platform's modal behaviour:

- the page behind it is inert;
- focus cannot Tab outside it;
- Escape dispatches `:on-dismiss`;
- backdrop click dispatches `:on-dismiss` only with
  `:light-dismiss? true`;
- `:label` supplies the accessible name.

Light-dismiss defaults to false for modals so a destructive confirmation does
not close on a stray backdrop click. Style the native backdrop with
`::backdrop` CSS ([Theming and internationalisation](14-theming-and-i18n.md)).

## Focus behaviour

Focus belongs to the browser. Do not mirror the currently focused element in
app-db.

**Initial focus.** Focus is decided once, when the overlay opens, by the
platform's own dialog-focusing steps — and with nothing pointing them
elsewhere they take the first focusable control in tree order. So order the
controls to put the one that should receive focus first. In the confirmation
dialog above that is *Keep it*, which is the right default for a destructive
action and worth arranging deliberately rather than inheriting.

There is no attribute to reach for. `:auto-focus true` camelCases to React's
own `autoFocus`, which React honours by calling `.focus()` during the commit —
one commit before the dialog is shown, while it is still `display: none` and
nothing inside it is focusable — and it emits no attribute; the unhyphenated
`:autofocus` React rejects outright. Neither spelling reaches the platform.

A popover normally leaves focus on its trigger; menus and comboboxes can use
`:aria-activedescendant` instead of moving DOM focus.

**Focus restoration.** When an overlay closes through Escape, light-dismiss,
or an app-db change, focus returns to the element that had focus when it
opened. You do not need a separate restore handler.

## Nested overlays

The native top layer is ordered last-in, first-out. A popover can open inside a
modal, and a submenu can open from another menu.

- Escape closes only the innermost open overlay.
- Light-dismiss of an inner popover leaves the modal underneath it open.
- Each overlay should have its own app-db address and `:on-dismiss` event.

Sharing one flag or one dismiss event across layers throws away the stack
semantics the platform already provides.

## Closed overlays have no runtime body

When an overlay is closed, it has:

- no DOM node;
- no light-dismiss listener;
- no subscriptions from its body;
- no server output.

Unmounting a view while its overlay is open closes the layer, restores focus
when possible, and cleans up its listeners. Five hundred closed row menus do
not create five hundred active overlay bodies.

## Build a dropdown from a popover

A single-select dropdown is a popover plus application events and state. It
does not require another overlay primitive.

```clojure
(rf/reg-sub :combo/open?
  (fn [db [_ id]]
    (get-in db [:ui :combo id :open?] false)))

(rf/reg-sub :combo/active
  (fn [db [_ id]]
    (get-in db [:ui :combo id :active])))

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
          next (nth values
                    (-> (+ i step)
                        (max 0)
                        (min (dec (count values)))))]
      {:db (-> db
               (assoc-in [:ui :combo id :open?] true)
               (assoc-in [:ui :combo id :active] next))})))

(rf/reg-event :combo/committed
  (fn [{:keys [db]} [_ id on-commit]]
    (let [{:keys [open? active]} (get-in db [:ui :combo id])]
      (cond-> {:db (assoc-in db [:ui :combo id :open?] false)}
        (and open? active)
        (assoc :fx [[:dispatch (conj on-commit active)]])))))

(rf/reg-event :combo/selected
  (fn [{:keys [db]} [_ id on-commit value]]
    {:db (assoc-in db [:ui :combo id :open?] false)
     :fx [[:dispatch (conj on-commit value)]]}))

(h/defview select-dropdown
  [{:keys [id items value on-commit placeholder]}]
  (let [open?      (h/sub [:combo/open? id])
        active     (h/sub [:combo/active id])
        values     (mapv :value items)
        trigger-id (str "combo-" id "-trigger")
        listbox-id (str "combo-" id "-listbox")
        option-id  (fn [v] (str "combo-" id "-opt-" v))
        label      (or (some #(when (= value (:value %))
                               (:label %))
                             items)
                       placeholder)]
    [:div.combo
     [:button
      {:id trigger-id
       :role "combobox"
       :aria-haspopup "listbox"
       :aria-expanded open?
       :aria-controls (when open? listbox-id)
       :aria-activedescendant
       (when (and open? active)
         (option-id active))
       :on-click [:combo/toggled id]
       :on-key-down
       {"ArrowDown" [::h/prevent [:combo/moved id 1 values]]
        "ArrowUp"   [::h/prevent [:combo/moved id -1 values]]
        "Enter"     [:combo/committed id on-commit]}}
      label]

     [overlay/popover
      {:open?      open?
       :on-dismiss [:combo/dismissed id]
       :anchor     trigger-id
       :placement  :bottom-start}
      [:ul {:id listbox-id
            :role "listbox"}
       (for [{v :value l :label} items]
         [:li
          {:key v
           :id (option-id v)
           :role "option"
           :aria-selected (= v value)
           :on-click [:combo/selected id on-commit v]}
          l])]]]))
```

Escape is not listed in the key map because the native popover owns Escape and
dispatches `:on-dismiss`. Focus stays on the trigger. There is no document
listener or portal.

Four details make the active-descendant model announceable, and each one is
load-bearing:

- `:role "combobox"` on the trigger. `:aria-activedescendant` is defined
  against a fixed set of roles, and a plain button is not among them.
- `:aria-controls` naming the listbox. That is the ownership edge the platform
  resolves the pointer through; without it the id names an element the trigger
  has no stated relationship to.
- Both are emitted only while the list is open, because a closed overlay has no
  DOM node. An unconditional `:aria-controls` would spend most of its life
  pointing at nothing.
- `:aria-selected` follows the committed `value`, while
  `:aria-activedescendant` follows the transient `active`. Selection does not
  follow focus here, so those are two different options for as long as the user
  is arrowing around, and collapsing them announces a choice nobody has made.

None of the four is visible on screen or reachable by a click-driven test. The
witness that decides them —
[`combobox_keyboard_dom_cljs_test.cljs`](../../../implementation/hicasso/test/re_frame/hicasso/combobox_keyboard_dom_cljs_test.cljs)
— audits this markup against the same view with the repair removed, so each
claim is shown failing as well as passing.

The same event-and-address model works for a toggletip, command menu, or other
popover-shaped control.

## When not to use the module

Use the browser directly when application state does not need to observe the
open flag.

- **Hover tooltip:** CSS `:hover` and `:focus-visible`.
- **Disclosure:** `<details>`.
- **Presentational hint:** native popover attributes:

  ```clojure
  [:button {:popovertarget "help-tip"} "?"]
  [:div {:id "help-tip" :popover "auto"} "Helpful text"]
  ```

Move to `overlay/popover` when another view, a test, routing, or application
logic needs to read or control the open state.

## Avoid the old overlay stack

```clojure
;; Don't: an in-flow panel plus a document listener.
(h/defview old-menu [{:keys [id]}]
  (let [open? (h/sub [:menu/open? id])]
    [:div {:style {:position "relative"}}
     [:button {:on-click [:menu/toggled id]} "Menu"]
     (when open?
       [:div.menu
        {:style {:position "fixed"
                 :z-index 1020
                 :top "48px"
                 :left "12px"}}
        …])]))
```

This design can leak its document listener on unmount, position against the
wrong containing block after an ancestor transform, and lose a z-index contest
to the next library. The top-layer primitives remove those failure classes.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Panel is clipped by `overflow: hidden` or appears under a sticky header | It is an ordinary positioned element, not a top-layer overlay | Render it through `overlay/popover` |
| Outside click closes the platform popover, then it opens again | `:on-dismiss` ran but the handler left the app-db flag true | Set the open flag false in the dismiss handler |
| Escape closes several layers at once | Layers share one address or one dismiss event | Give each overlay its own address and `:on-dismiss` |
| Focus returns to `<body>` | The opener unmounted while the overlay was open, often because of an unstable list key | Use a stable `:key` for the trigger's row |
| `:rf.error/hicasso-overlay-anchor-missing` is raised when the overlay opens | `:anchor` names a DOM id no element carries — a typo, or a trigger that renders one commit after the panel. Omitting `:anchor` is legal and silent; naming one that resolves to nothing is not | Generate a unique, stable trigger id from the instance id, and render the trigger in the same tree as the overlay |
| Panel opens beside the wrong trigger, and nothing is raised | Several instances reuse one id. The id resolves, so there is nothing to refuse — it resolves to the first element in the document carrying it | Include the row id in the trigger id, the same way you do for the open flag |
| Dialog is visible but the background still scrolls and receives clicks | A hand-written `<dialog open>` uses the non-modal path | Use `overlay/modal`, which calls `showModal` |
| Popover flashes in the wrong place for one frame | Positioning happens after mount | Supply `:anchor` and `:placement`; the module positions before paint |
| Every row menu opens together | All rows share one app-db address | Include the row id in the address ([Ephemeral state](11-ephemeral-state.md#choose-a-stable-instance-address)) |

??? info "Coming from Reagent or UIx"
    A portal, floating-positioning library, z-index policy, and document
    listener are not required here. The top layer changes paint order without
    removing the panel from its React or frame context. You still own the open
    flag and the meaning of a selection.

## Advanced

### Entry animation

Entry animation can be pure CSS because the panel mounts when it opens:

```css
.menu[popover]:popover-open {
  opacity: 1;
  transition: opacity 150ms;
}

@starting-style {
  .menu[popover]:popover-open {
    opacity: 0;
  }
}
```

### Exit animation

Exit needs a clock because app-db is already closed while the old pixels are
still fading. Pass `:exit-ms` to retain the layer for that duration. During the
exit the module makes it inert and `aria-hidden`. The node leaves when the
clock ends even if CSS did not run. Reopening cancels the exit; unmounting
cancels the timer.

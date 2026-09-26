# Overlays and focus

A filter menu needs to stay anchored to its button. A confirmation dialog
needs to block the page behind it and restore focus when it closes. Building
that from portals, document listeners, z-index rules, and a custom focus trap
creates several independent failure modes.

`re-frame.fresco.overlay` provides two primitives:

- `overlay/popover` for anchored, light-dismissable UI
- `overlay/modal` for blocking dialogs

Both use the browser's native top layer. Your application still owns the open
flag and the dismiss event.

## Anchored popovers

Store the open flag in app-db. `h/reg-state` from
[Ephemeral state](11-ephemeral-state.md) gives you the subscription and setter:

```clojure
(ns app.todos.filters
  (:require [re-frame.fresco :as h]
            [re-frame.fresco.overlay :as overlay]))

(h/reg-state :todo.ui/filter-open? {:default false})

(h/defview filter-menu [{:keys [id]}]
  (let [open?      (h/sub [:todo.ui/filter-open? id])
        trigger-id (str "filter-" id "-trigger")]
    [:div.filter
     [:button {:id            trigger-id
               :aria-haspopup "menu"
               :aria-expanded open?
               :on-click      [:todo.ui/filter-open? id (not open?)]}
      "Show"]

     [overlay/popover
      {:open?      open?
       :on-dismiss [:todo.ui/filter-open? id false]
       :anchor     trigger-id
       :placement  :bottom-start}
      [:ul {:role "menu"}
       (for [showing [:all :active :done]]
         [:li {:key showing}
          [:button {:role     "menuitem"
                    :on-click [:todo/set-showing showing]}
           (name showing)]])]]]))
```

The options:

- **`:open?`** controls whether the panel exists. While false, there is no DOM
  node, listener, or body subscription.
- **`:on-dismiss`** is dispatched for native light-dismiss, including outside
  click and Escape. Its handler must set the open flag to false; app-db remains
  the source of truth.
- **`:anchor`** is the unique DOM id of the trigger. The module positions the
  panel before first paint.
- **`:placement`** is `:top`, `:bottom`, `:left` or `:right`, optionally
  suffixed `-start` or `-end` (`:bottom-start`).

Every other key is an ordinary attribute on the panel, a `<div popover>` or a
`<dialog>`: style it with `:class`, `:style` or `:id`. `:label` sets
`aria-label` on either head.

The panel stays in the same React tree and frame as its trigger and uses the
same subscriptions. The top layer changes only paint order, so ancestor
`overflow`, transforms and stacking contexts do not clip it.

Where CSS anchor positioning is available, the browser places the panel and
keeps tracking the anchor as it moves; the module measures nothing and installs
no scroll or resize listener. Where it is unavailable, the panel opens at the
top layer's default position, and placing it is CSS you write.

## Modals

`overlay/modal` uses a native `<dialog>` and calls `showModal`:

```clojure
(ns app.todos.confirm
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]
            [re-frame.fresco.overlay :as overlay]))

(h/reg-state :todo.ui/confirm-delete? {:default false})

(rf/reg-event :todo/delete-confirmed
  (fn [_ [_ id]]
    {:fx [[:dispatch [::h/clear :todo.ui/confirm-delete? id]]
          [:dispatch [:todo/delete id]]]}))

(h/defview confirm-delete [{:keys [id]}]
  [overlay/modal
   {:open?      (h/sub [:todo.ui/confirm-delete? id])
    :on-dismiss [:todo.ui/confirm-delete? id false]
    :label      "Confirm deletion"}
   [:h2 "Delete this todo?"]
   [:p "This cannot be undone."]
   [:footer
    [:button {:on-click [:todo.ui/confirm-delete? id false]}
     "Keep it"]
    [:button.danger {:on-click [:todo/delete-confirmed id]}
     "Delete"]]])
```

The delete button opens it with `[:todo.ui/confirm-delete? id true]`.
Confirming is a named event because it does more than set a flag.

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

**Initial focus.** When the overlay opens, the browser's dialog-focusing steps
move focus to the first focusable control in tree order. Order the controls so
the right one comes first. In the confirmation dialog above that is *Keep it*,
the safe default for a destructive action.

Do not use `:auto-focus` for this. It becomes React's `autoFocus`, which calls
`.focus()` one commit before the dialog is shown, while nothing inside it is
focusable. React rejects the unhyphenated `:autofocus` outright.

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

A single-select dropdown is a popover plus application events and state; it
needs no other overlay primitive. Its open flag and active option are read by
the keyboard events, so this widget stores them with ordinary subscriptions and
events rather than `h/reg-state`.

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

Used for the todo filter:

```clojure
[select-dropdown {:id          :showing
                  :items       [{:value :all    :label "All"}
                                {:value :active :label "Active"}
                                {:value :done   :label "Done"}]
                  :value       (h/sub [:todo/showing])
                  :on-commit   [:todo/set-showing]
                  :placeholder "Show"}]
```

Escape is not in the key map because the native popover handles Escape and
dispatches `:on-dismiss`. Focus stays on the trigger, and there is no document
listener or portal.

Four details make the active-descendant model work for screen readers:

- `:role "combobox"` on the trigger. `:aria-activedescendant` is only defined
  for certain roles, and a plain button is not one of them.
- `:aria-controls` naming the listbox, so the active-descendant id refers to an
  element the trigger is related to.
- Both are emitted only while the list is open, because a closed overlay has no
  DOM node to point at.
- `:aria-selected` follows the committed `value`, while
  `:aria-activedescendant` follows the transient `active`. While the user is
  arrowing through options these differ, and merging them would announce a
  choice nobody has made.

None of the four is visible on screen or caught by a click-driven test, so
check them with an accessibility-tree assertion or a screen reader.

The same event-and-address model works for a toggletip, command menu, or other
popover-shaped control.

## When not to use the module

Use the browser directly when application state does not need to observe the
open flag.

- **Hover tooltip:** CSS `:hover` and `:focus-visible`.
- **Disclosure:** `<details>`.
- **Presentational hint:** native popover attributes:

  ```clojure
  [:button {:popover-target "help-tip"} "?"]
  [:div {:id "help-tip" :popover "auto"} "Helpful text"]
  ```

Move to `overlay/popover` when another view, a test, routing, or application
logic needs to read or control the open state.

## Avoid hand-built overlays

```clojure
;; Don't: an in-flow panel plus a document listener.
(h/defview hand-built-menu [{:keys [id]}]
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
| Outside click closes the popover, and the next click on the trigger does nothing | `:on-dismiss` ran but the handler left the app-db flag true. The element is still mounted and closed, `showPopover` runs only when it mounts, and setting a flag that is already true changes nothing | Set the open flag false in the dismiss handler |
| Escape and outside clicks do nothing | The overlay has no `:on-dismiss`, so the browser is told not to dismiss it | Give it an `:on-dismiss` event that sets the open flag false |
| Escape closes several layers at once | Layers share one address or one dismiss event | Give each overlay its own address and `:on-dismiss` |
| Focus returns to `<body>` | The opener unmounted while the overlay was open, often because of an unstable list key | Use a stable `:key` for the trigger's row |
| `:rf.error/fresco-overlay-anchor-missing` is raised when the overlay opens | `:anchor` names a DOM id no element carries: a typo, or a trigger that renders one commit after the panel. Omitting `:anchor` is legal | Generate a unique, stable trigger id from the instance id, and render the trigger in the same tree as the overlay |
| An open overlay raises `:rf.error/fresco-intent-outside-boundary` naming its `:on-dismiss` intent | It has `:on-dismiss` but no frame above it (it rendered outside `h/frame-root`, `h/frame-provider` or Story), so the dismissal could never be routed | Mount it under a frame, or drop `:on-dismiss` if it must not be dismissable |
| Panel opens beside the wrong trigger, and nothing is raised | Several instances reuse one id. The id resolves, so there is nothing to refuse — it resolves to the first element in the document carrying it | Include the row id in the trigger id, the same way you do for the open flag |
| Popover opens at the browser's default position, and nothing is raised | `:placement` is not one of the listed keywords; any other value passes through as a raw CSS `position-area` | Use `:top`, `:bottom`, `:left` or `:right`, optionally suffixed `-start` or `-end` |
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

The overlay module has no exit clock. When `:open?` becomes false the panel is
removed in that commit, so a CSS exit transition on the panel never runs.
Retaining a node after its data has gone is the job of
[`motion/presence`](12-motion-and-presence.md).

### Re-anchor an open panel

`:anchor` is an ordinary prop. Change it while the panel is open (one shared
menu reused for a newly selected row) and the panel re-anchors in the same
commit without leaving the top layer.

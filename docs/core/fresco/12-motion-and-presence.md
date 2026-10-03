# Motion and presence

Fresco does not ship an animation system. CSS handles transitions and
keyframes, and a React island handles high-rate mechanics such as drag positions
and springs. None of them handles exit: React removes a node as soon as its
data leaves app-db, and a node that is gone cannot finish an exit animation.

`re-frame.fresco.motion` fills that gap. It is an optional module; an
application that never requires it includes none of its code.

```clojure
(ns app.todos.list
  (:require [re-frame.fresco :as h]
            [re-frame.fresco.motion :as motion]))
```

## Retain the exiting node

A deleted todo should leave app-db immediately while its row finishes a
300 ms fade. Wrap the keyed children in `motion/presence`:

```clojure
(h/defview todo-list [_]
  [:ul.todo-list
   [motion/presence {:timeout-ms 300}
    (for [{:keys [id title]} (h/sub [:todo/visible])]
      [:li.todo
       {:key                id
        ::motion/unmounting {:class       "todo todo--exit"
                             :inert       true
                             :aria-hidden true}}
       title
       [:button {:type "button" :aria-label "Delete todo"
                 :on-click [:todo/delete id]} "×"]])]])
```

Add the transition to the stylesheet:

```css
.todo { opacity: 1; transition: opacity 300ms; }
.todo--exit { opacity: 0; }

@media (prefers-reduced-motion: reduce) {
  .todo { transition: none; }
}
```

The reduced-motion rule removes the fade; the same 300 ms retention bound
still removes the hidden node.

What happens:

1. The user deletes todo `7`. The handler removes it from app-db.
2. Presence still has a child with key `7`, so that child enters the
   **unmounting** phase.
3. Presence merges the `::motion/unmounting` attributes onto the real element.
   The exit class starts the CSS transition; `:inert` and `:aria-hidden` stop
   interaction and hide the node from assistive technology while it is still
   painted.
4. After 300 ms Presence removes the child. Removal uses a timer rather than
   `transitionend`, so disabled CSS cannot leave the node on the page.

App-db never stores "still animating". Keeping the node painted is Presence's
job.

The cell below runs that list. A cell has no stylesheet, so the fade is
written as inline styles, and it lasts a full second so you can watch it.
Delete a todo: it fades and stops taking clicks, then goes. Press **Restore**
while a row is fading and it comes back on the same node, because re-entry
cancels the exit.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.fresco :as h]
         '[re-frame.fresco.motion :as motion])

(rf/reg-event :todo/initialise
  (fn [_ _]
    {:db {:todos {1 {:id 1 :title "Buy milk"}
                  2 {:id 2 :title "Walk the dog"}
                  3 {:id 3 :title "Post letter"}}}}))

(rf/reg-event :todo/delete
  (fn [{:keys [db]} [_ id]]
    {:db (update db :todos dissoc id)}))

(rf/reg-sub :todo/visible
  (fn [db _]
    (vec (sort-by :id (vals (:todos db))))))

;; The page's .todo and .todo--exit rules, inline.
(def shown  {:opacity 1 :transition "opacity 1000ms"})
(def hidden {:opacity 0 :transition "opacity 1000ms"})

(h/defview todo-list [_]
  [:div
   [:ul.todo-list
    [motion/presence {:timeout-ms 1000}
     (for [{:keys [id title]} (h/sub [:todo/visible])]
       [:li.todo
        {:key                id
         :style              shown
         ::motion/unmounting {:style       hidden
                              :inert       true
                              :aria-hidden true}}
        title " "
        [:button {:type "button" :aria-label "Delete todo"
                  :on-click [:todo/delete id]} "×"]])]]
   [:button {:type "button" :on-click [:todo/initialise]} "Restore"]])

[h/frame-root {:id :app :initial-events [[:todo/initialise]]}
 [todo-list]]
```

## What Presence covers

Presence owns **retention and phase**, nothing else:

| Belongs to Presence | Does **not** belong to Presence |
| --- | --- |
| Keeping a keyed child after its data leaves | Easing curves, springs, keyframe APIs |
| Applying mounting/unmounting attribute overrides | Timelines, sequences, orchestrators |
| A hard `:timeout-ms` terminal bound | `transitionend` subscriptions |
| Cancelling exit when a key re-enters | Gesture or drag state |

High-rate motion stays in CSS or a React component, mounted as a foreign
component or a [React island](10-native-tier.md) through
[`h/defhost`](09-interop.md). Do not route pointer-move events through app-db.

## API

### `motion/presence`

A Hiccup head. Props:

| Prop | Required | Meaning |
| --- | --- | --- |
| `:timeout-ms` | **yes** | How long an exiting child is retained. Also the hard stop for removal. |

A missing or non-positive `:timeout-ms` raises
`:rf.error/fresco-presence-timeout-required`.

Children must be keyed Hiccup vectors; a child without a `:key` raises
`:rf.error/fresco-presence-child-unkeyed`. Presence freezes order at first
appearance so an exiting sibling does not jump while it leaves. That order
also applies to present children: rearranging the input does not rearrange
already-retained keys. Use this for stable-order collections, not a sortable
list that must follow every new input order.

Presence retains a single conditional element too. A `nil` child is skipped,
so `[motion/presence {:timeout-ms 200} (when open? [:div.banner {:key :banner} …])]`
keeps the banner painted through its exit after `open?` turns false.

Presence inserts **no wrapper DOM node** and stamps no `data-*`. Each child is
the author's node with the author's attributes merged for the active phase.

### Phase overrides on elements

On a native element child, write overrides with the motion markers:

```clojure
[:li.todo
 {:key                id
  ::motion/mounting   {:class "todo todo--enter" :inert true}
  ::motion/unmounting {:class "todo todo--exit"  :inert true :aria-hidden true}}
 title]
```

| Marker | When applied |
| --- | --- |
| `::motion/mounting` | While the child is entering (first paint of a new key) |
| `::motion/unmounting` | While the child is retained after its key left the live set |

These markers are keywords in the `re-frame.fresco.motion` namespace, so
write them as `::motion/...` with the module aliased as `motion`, not as
`::h/...`.

Prefer CSS insertion animations or `@starting-style` for simple entrances.
Use `::motion/mounting` when the node must carry attributes such as `:inert`
until it settles.

### Phase overrides on views

The same markers work on a [`h/defview`](glossary.md#defview) head. While the
child is in that phase, Presence merges the map into the view's props, and the
view branches on whatever prop it declared:

```clojure
(h/defview todo-item [{:keys [todo exiting?]}]
  (let [{:keys [id title]} todo]
    [:li
     {:class       (cond-> "todo" exiting? (str " todo--exit"))
      :inert       exiting?
      :aria-hidden exiting?}
     title
     (when-not exiting?
       [:button {:type "button" :aria-label "Delete todo"
                 :on-click [:todo/delete id]} "×"])]))

(h/defview todo-list [_]
  [:ul.todo-list
   [motion/presence {:timeout-ms 300}
    (for [{:keys [id] :as todo} (h/sub [:todo/visible])]
      [todo-item {:key                id
                  :todo               todo
                  ::motion/unmounting {:exiting? true}}])]])
```

The view sees the props its author chose for that phase, never a phase value.
A test renders the exiting shape by passing `{:exiting? true}` directly, with
no timer involved.

Presence retains the last child form and its props, not a historical app-db.
Passing the todo as a prop keeps its title available during exit. If the child
instead reads `[:todo/by-id id]`, it sees the deletion immediately and may render
an empty row for the rest of the transition.

## Rules that matter in production

- **`:timeout-ms` is mandatory.** It is both retention length and the hard
  terminal bound.
- **Re-entry cancels exit.** A key that returns while unmounting goes back to
  the present phase on the same node, with no remount and no second deadline.
- **Unmount clears timers.** Leaving the page mid-transition does not leave
  dangling timers.
- **Per-frame work is zero.** Presence arms timers at phase changes; it does
  not run `requestAnimationFrame` or write state every frame. CSS owns the
  visual interpolation.
- **SSR.** A presence-managed server node hydrates as already present. The
  server HTML does not carry entry-phase attributes
  ([SSR and hydration](18-ssr-and-hydration.md)).
- **Accessibility.** While unmounting, set `:inert` and `:aria-hidden` — under
  `::motion/unmounting` on an element, or from the prop a view declares there —
  so a fading node does not keep focus or announce itself
  ([Accessibility](22-accessibility.md)).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Dismissed item vanishes immediately | The children are not under Presence | Wrap the keyed sequence in `motion/presence` |
| `:rf.error/fresco-presence-child-unkeyed` | A child has no `:key` | Give every child a stable domain `:key` |
| `:rf.error/fresco-presence-timeout-required` | `:timeout-ms` is missing or not a positive number | Set it to at least the CSS exit duration |
| Exiting row loses its text | The child reads data already deleted from app-db | Pass the display data in the retained child props |
| Exiting node lingers after its animation ends | `:timeout-ms` is much longer than the CSS transition | Match `:timeout-ms` to the CSS duration |
| Fading row still takes focus or clicks | Exit class changes appearance only | Add `:inert true` and `:aria-hidden true` under `::motion/unmounting` — on the element, or from the prop the view's override declares |
| Override has no effect on an element | The marker is on a nested element, not the keyed child Presence receives, and Fresco drops it there | Put the marker map on Presence's direct child |
| Override on a view head has no visible effect | The map was merged into the view's props, and the view's body does not read the prop it names | Destructure the prop in the view and branch on it |
| Exit restarts on every parent re-render | Unstable keys | Key by domain id, not index |
| Bundle still contains motion code when unused | Something required the module | Require `re-frame.fresco.motion` only where Presence is used |

## When not to use Presence

- There is no exit animation: remove the data and skip the module.
- The fact is application-visible (open, selected, draft): store it in app-db
  ([Ephemeral state](11-ephemeral-state.md)).
- The motion is continuous pointer or layout motion: use an island or CSS.

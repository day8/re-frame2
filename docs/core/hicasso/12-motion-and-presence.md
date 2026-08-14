# Motion and presence

Hicasso does not ship an animation system. CSS owns transitions and keyframes.
The compositor interpolates them. A native host owns high-rate mechanics such as
drag positions and spring integrators. Exactly one gap remains: **React removes
a node as soon as its data leaves app-db**, and a node that is gone cannot
finish an exit animation.

`re-frame.hicasso.motion` closes that gap. It is an optional module. An
application that never requires it carries none of its code.

```clojure
(ns app.toasts
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.motion :as motion]))
```

## The problem in one example

A toast should leave app-db the moment the user dismisses it. Tests, Xray, and
other views must see the toast as gone. The painted element may still need
300 ms of CSS exit transition.

If the view simply maps over the subscription, the DOM node disappears on the
same turn as the event:

```clojure
;; Don't — the node vanishes with the data; CSS has nothing left to animate.
(h/defview toast-tray [_]
  [:div.toast-tray
   (for [t (h/sub [:toasts/visible])]
     [:div.toast {:key (:id t)}
      (:message t)
      [:button {:on-click [:toasts/dismiss (:id t)]} "×"]])])
```

Presence keeps the exiting node for a stated timeout while app-db already
records the dismissal.

## The taught spelling

```clojure
(h/defview toast-tray [_]
  [motion/presence {:timeout-ms 300}
   (for [t (h/sub [:toasts/visible])]
     [:div.toast
      {:key            (:id t)
       ::h/unmounting  {:class       "toast toast--exit"
                        :inert       true
                        :aria-hidden true}}
      (:message t)
      [:button {:on-click [:toasts/dismiss (:id t)]} "×"]])])
```

What happens:

1. The user dismisses toast `7`. The handler removes it from app-db.
2. Presence still has a child with key `7`. That child enters the **unmounting**
   phase.
3. Presence merges `::h/unmounting` attributes onto the real element. The exit
   class starts the CSS transition; `:inert` and `:aria-hidden` stop interaction
   and hide the node from assistive tech while it is still painted.
4. After 300 ms Presence removes the child. Removal is **timer-based**, not
   `transitionend`. Disabled CSS cannot strand the node forever.

App-db never stores “still animating.” The retention is a paint concern owned
by Presence.

## Module posture

Presence owns **retention and phase**, nothing else:

| Belongs to Presence | Does **not** belong to Presence |
| --- | --- |
| Keeping a keyed child after its data leaves | Easing curves, springs, keyframe APIs |
| Applying mounting/unmounting attribute overrides | Timelines, sequences, orchestrators |
| A hard `:timeout-ms` terminal bound | `transitionend` subscriptions |
| Cancelling exit when a key re-enters | Gesture or drag state |

High-rate motion stays in a native host or CSS. Host those mechanics with
[`h/defhost`](09-interop.md) or the [native tier](10-native-tier.md); do not
route pointer-move events through app-db.

## API

### `motion/presence`

A Hiccup head. Props:

| Prop | Required | Meaning |
| --- | --- | --- |
| `:timeout-ms` | **yes** | How long an exiting child is retained. Also the hard stop for removal. |

Children must be keyed. Presence freezes order at first appearance so an
exiting sibling does not jump while it leaves.

Presence inserts **no wrapper DOM node** and stamps no `data-*`. Each child is
the author's node with the author's attributes merged for the active phase.

### Phase overrides on elements

On a native element child, write overrides with the Hicasso markers:

```clojure
[:div.card
 {:key           id
  ::h/mounting   {:class "card card--enter" :inert true}
  ::h/unmounting {:class "card card--exit"  :inert true :aria-hidden true}}
 body]
```

| Marker | When applied |
| --- | --- |
| `::h/mounting` | While the child is entering (first paint of a new key) |
| `::h/unmounting` | While the child is retained after its key left the live set |

These markers live in the `re-frame.hicasso` keyword namespace (`::h/...` when
you alias the door as `h`). The motion **module** is separate; the markers are
shared vocabulary, not `::motion/...` keys.

Prefer CSS insertion animations or `@starting-style` for simple entrances.
Use `::h/mounting` when the node must carry attributes such as `:inert` until
it settles.

### Phase prop on views

Presence cannot merge attributes into an opaque [`h/defview`](glossary.md#defview)
head. When the child is a view, Presence passes an ordinary prop:

```clojure
(h/defview toast-item [{:keys [id message rf/phase]}]
  (let [exiting? (= phase :unmounting)]
    [:div.toast
     {:class (cond-> "toast" exiting? (str " toast--exit"))
      :inert       exiting?
      :aria-hidden exiting?}
     message
     (when-not exiting?
       [:button {:on-click [:toasts/dismiss id]} "×"])]))

(h/defview toast-tray [_]
  [motion/presence {:timeout-ms 300}
   (for [t (h/sub [:toasts/visible])]
     [toast-item {:key     (:id t)
                  :id      (:id t)
                  :message (:message t)}])])
```

`:rf/phase` is one of `:mounting`, `:present`, or `:unmounting`. Tests can pass
the prop directly without arming timers.

Putting `::h/unmounting` on a view head raises
`:rf.error/hicasso-presence-override-on-a-view`.

## Rules that matter in production

- **`:timeout-ms` is mandatory.** It is both retention length and the hard
  terminal bound.
- **Re-entry cancels exit.** A key that returns while unmounting becomes
  `:present` on the same node — no remount, no second deadline.
- **Unmount clears timers.** Leaving the page mid-transition does not leave
  dangling timers.
- **Per-frame work is zero.** Presence arms timers at phase changes; it does
  not run `requestAnimationFrame` or write state every frame. CSS owns the
  visual interpolation.
- **SSR.** A presence-managed server node hydrates as already present. The
  server HTML does not carry entry-phase attributes
  ([SSR and hydration](18-ssr-and-hydration.md)).
- **Accessibility.** While unmounting, set `:inert` and `:aria-hidden` (or the
  equivalent on a view via `:rf/phase`) so a fading node does not keep focus or
  announce itself ([Accessibility](22-accessibility.md)).

## What Presence does not do

- It does not dispatch an event when a transition ends.
- It does not keep the removed domain data in app-db.
- It does not replace CSS, the Web Animations API, or a hosted animation
  library.
- It does not own open/closed UI truth. That is still app-db
  ([Ephemeral state](11-ephemeral-state.md)).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Dismissed item vanishes immediately | Children are not under Presence, or keys are missing | Wrap the keyed sequence in `motion/presence` and give every child a stable `:key` |
| Node stays forever after dismiss | `:timeout-ms` omitted or far longer than the CSS | Set `:timeout-ms` to at least the CSS duration; it is required |
| Fading toast still takes focus or clicks | Exit class changes appearance only | Add `:inert true` and `:aria-hidden true` under `::h/unmounting` (or via `:rf/phase` on a view) |
| Override on a view raises `:rf.error/hicasso-presence-override-on-a-view` | Presence cannot merge into a boundary head | Branch on `:rf/phase` inside the view |
| Exit restarts on every parent re-render | Unstable keys | Key by domain id, not index |
| Bundle still contains motion code when unused | Something required the module | Require `re-frame.hicasso.motion` only where Presence is used |

## When not to use Presence

- No exit animation — just remove the data; no module required.
- The fact is application-visible (open, selected, draft) — store it in app-db,
  not as a phase.
- Continuous pointer or layout motion — use a native host or CSS, not Presence.

## Advanced

### Optional module reachability

`re-frame.hicasso` does not import `re-frame.hicasso.motion`. That keeps the
retention machine out of applications that never ask for it. A check in the
Hicasso package fails if the public door re-acquires a hard dependency on the
module.

### Phase vocabulary freeze

The override markers remain `::h/mounting` and `::h/unmounting` until the
naming ledger freezes any respell. Guide examples use those shipped keys.

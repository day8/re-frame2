# Ephemeral state: where it belongs

A dropdown can be open, a field can hold a half-typed draft, and a drag can
have an in-flight pointer position. Those facts do not all belong in the same
place.

Fresco has no component-local reactive cell. There is no Fresco equivalent
of Reagent's `r/atom`, and `useState` does not belong in a `defview` body.
Application-visible facts live in app-db. High-rate widget mechanics stay
inside a native host. Browser-owned state stays in the browser.

That gives each fact one owner and keeps a second reactive store out of the
application.

## Why one owner matters

Three re-frame2 properties depend on it.

**Tests stay data-driven.** If “this dropdown is open” is stored at an app-db
address, a headless test can seed that address directly. It does not need to
mount a component, simulate a click, or wait for a timer
([Testing](15-testing.md)).

**Diagnostics keep a complete cause chain.** Xray can connect an event to a
state commit, subscription invalidation, view render, React commit, and paint.
A private reactive store updates on a clock outside that chain
([Diagnostics](16-diagnostics.md)).

**Frames remain isolated.** App-db is per-frame. A module-level atom is shared
by every frame that mounts the code, which defeats frame isolation.

A host may still keep React state, DOM state, canvas state, or an SDK handle.
It must not keep an invisible duplicate of an application fact.

## 1. Application-visible state: app-db

Use an explicit app-db address for state that affects what the application can
do or what another part of the application can observe: open, expanded,
selected, or the active tab.

`h/reg-state` registers one subscription and one setter event, stored under
`[:ui concern instance-key]`, that every instance shares:

```clojure
(ns app.todos.views
  (:require [re-frame.fresco :as h]))

(h/reg-state :todo.ui/expanded? {:default false})

(h/defview todo-item [{:keys [id]}]
  (let [{:keys [title notes]} (h/sub [:todo/by-id id])
        expanded?             (h/sub [:todo.ui/expanded? id])]
    [:li
     [:span {:on-click [:todo.ui/expanded? id (not expanded?)]} title]
     (when expanded?
       [:p.notes notes])]))
```

`(h/sub [:todo.ui/expanded? id])` reads, `[:todo.ui/expanded? id value]`
writes, and `[::h/clear :todo.ui/expanded? id]` removes the entry so that
instance reads its default again. A hundred todo rows share the one
registration, and because the state is at an app-db address you get replay,
frame isolation, Xray visibility, and direct test setup.

The concern must be a namespace-qualified keyword, because it is a sub id, an
event id and an app-db key at once. The instance key must be a keyword, string,
number or vector of those. An unqualified concern or an option other than
`:default` makes the registration throw `:rf.error/fresco-state-bad-argument`.
A `nil` or other bad instance key is caught where it is used: a read reports
`:rf.error/sub-exception` and returns `nil` (not the default), and a write
reports `:rf.error/handler-exception`, each carrying
`:rf.error/fresco-state-bad-argument` as its cause. Registering the concern again replaces
the registration, so a namespace reload is harmless.

When a change means more than "this slot now holds that value" (something else
must happen, or the change itself should be recorded), write a named event and
its subscription by hand instead:

```clojure
(rf/reg-sub :todo/details-open?
  (fn [db [_ id]]
    (get-in db [:ui :todo/details-open id] false)))

(rf/reg-event :todo/details-toggled
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:ui :todo/details-open id] not)}))
```

`[:todo/details-toggled id]` records what happened and leaves room for effects
later; `[:todo.ui/expanded? id true]` records only the value. Either way, prefer
a named event over a generic `[:ui/set path value]`.

## 2. Drafts and form state: the forms module

A draft is application-visible when validation, submit gating, dirty-leave
logic, or another view needs it. Store it at an app-db address, usually through
`re-frame.fresco.forms`.

The forms module is one view, `forms/buffered-field`: a draft in front of a
committed value, with a baseline, a commit protocol and the `::h/revision`
reset, at an address you supply ([Forms](05-forms.md)). Validation gating and
submit status are recipes on ordinary events and subscriptions, taught in the
same chapter; there is no form object, validation DSL or submit orchestrator
to require. For a smaller concern, such as the new-todo input, ordinary events
and an app-db slice are enough:

```clojure
[:todo.ui/set-draft text]
[:todo.ui/clear-draft]
```

Either way, the draft has one app-db address and no local copy.

## 3. Host-private mechanics: native state

Some state exists only to operate a widget:

- measured geometry
- an in-flight drag position
- composition buffers
- internal focus mechanics
- a chart or map SDK handle

This state may update every pointer move or animation frame, and nothing
outside the widget needs it. Keep it inside a React island or a declared host
([Islands](10-native-tier.md), [Interop](09-interop.md)).

Keep the motion inside the island and dispatch one event when it produces a
result. Here a todo is dragged to a new position in the list:

```clojure
(ns app.todos.drag
  (:require ["react" :as react]
            [re-frame.fresco :as h]))

(defn drag-surface [^js props]
  (let [[xy set-xy] (react/useState nil)]
    (react/createElement "div"
      #js {:className "card"
           :style (when xy
                    #js {:translate (str (aget xy 0) "px "
                                         (aget xy 1) "px")})
           :onPointerMove
           (fn [e]
             (when (pos? (.-buttons e))
               (set-xy #js [(.-clientX e) (.-clientY e)])))

           :onPointerUp
           (fn [_]
             (when xy
               ((.-onDrop props)
                (js/Math.round (/ (aget xy 1) 40))))
             (set-xy nil))}
      (.-label props))))

(h/defhost drag-row drag-surface)

(h/defview todo-drag-row [{:keys [id]}]
  (let [{:keys [title]} (h/sub [:todo/by-id id])]
    [drag-row {:label   title
               :on-drop (h/event [position] [:todo/moved id position])}]))
```

Pointer movement stays in local React state. The completed drop is an
application event, so it reaches app-db once. `:on-drop` is an `on*` prop, so
`h/event` there is an event callback, and the island calls it with the
position it computed.

Hooks belong in the island. A `defview` body may branch and loop, so hooks
there would make hook order depend on data.

## 4. Browser-owned state

Sometimes the platform already owns the fact. Do not mirror it in app-db
unless the application needs a semantic copy.

**CSS** should own hover, focus-visible, active state, `:has()`, and ordinary
`<details>` disclosure.

**Uncontrolled inputs** may own scratch text through `:default-value`, with a
commit on blur. The tradeoff is explicit: app-db, tests, and tools cannot see
mid-edit text ([Controlled inputs](04-controlled-inputs.md)).

**Platform controls** may own a presentational toggle, such as a native
popover triggered by `:popover-target` ([Overlays and focus](13-overlays-and-focus.md)).

When validation, another view, routing, or a test needs the fact, move it to
app-db.

## 5. Exit retention: pixels that outlive data

App-db records what is true. A deleted todo should leave app-db immediately,
but its row may need a short exit animation. Keeping that node painted is a
rendering concern, and app-db does not record it.

Use `motion/presence` from the optional
[`re-frame.fresco.motion`](12-motion-and-presence.md) module, which keeps an
exiting node painted until its animation finishes.

## Common state and its owner

| State | Owner | Reason |
| --- | --- | --- |
| Dropdown open | App-db; the overlay module reconciles the platform to it | It changes what the user can do, and tests and Xray need it |
| Field draft | App-db through the forms module | Validation, submit gating, dirty-leave, and replay read it |
| Drag position during a drag | Native host state | High-rate mechanics; dispatch the completed drop once |
| Scroll offset | DOM; routing restores it per route | Do not re-render for every pixel. Commit meaningful thresholds as events when needed |
| Animation / exit retention | CSS for animation; [`motion/presence`](12-motion-and-presence.md) for exit retention; host state for rAF mechanics | App-db records truth, not what is still painted |
| Focus | Browser focus, changed through one-shot focus actions | A mirrored “focused element” value drifts and would update on every Tab |
| Selected tab | App-db, or routing when it should survive reload | Other views, tests, or deep links care |
| WebGL context or SDK handle | Declared host or native component | It is an object identity with an attach/teardown lifecycle, not application data |

## Choose a stable instance address

Application-visible and form state need an instance key. Fresco does not
invent one. React's `useId` is unsuitable because it is tied to render order
and does not provide a durable app-db address.

Use authored data: a keyword, string, number, or a vector of those values.

1. **Start with a domain id.** Qualify ids when different entity types can
   collide: `[:todo/id 42]` and `[:list/id 42]`.
2. **Key placement state by placement and value state by entity.** Two panes
   may share one todo draft while keeping separate expanded/collapsed state.
3. **Extend a parent key for nested instances.** `[list-id :filter]` is often
   enough.
4. **Apply the same stability test as a React `:key`.** It must be derived from
   data, stable across renders, unique in its scope, and deterministic under
   SSR.

## There is no `:on-mount`

Fresco has no `:on-mount` or `:on-unmount`. The job you are trying to perform
already has a more specific owner:

| Job | Use |
| --- | --- |
| Load data for a screen | Route `:resources`, or an `[:rf.resource/ensure …]` from the event that decides the data is wanted ([Routing](07-routing-and-navigation.md), [Async resources](08-async-resources.md)) |
| Run startup work once | `:initial-events`, before first paint ([Installation](00-installation.md)) |
| Animate an entrance or exit | CSS or [`motion/presence`](12-motion-and-presence.md) |
| Attach to a DOM node or SDK | A callback ref or declared host ([Interop](09-interop.md)) |

## When app-db is the wrong place

Do not put a fact in app-db when:

- the browser already owns it, such as hover, focus, or raw scroll position;
- it changes too quickly to be a useful application event, such as pointer
  movement or per-frame geometry;
- nobody outside one widget can observe or act on it, such as an SDK handle.

Everything else is application state and should have one app-db address.

```clojure
;; Don't: this atom is recreated whenever the body runs, and Fresco does not
;; track it as reactive state.
(h/defview broken-todo-item [{:keys [title notes]}]
  (let [expanded? (atom false)]
    [:li
     [:span {:on-click (fn [_] (swap! expanded? not))} title]
     (when @expanded?
       [:p.notes notes])]))

;; Don't: one event, subscription pass, and paint for every pointer move.
:on-pointer-move
(h/event [e] [:todo/drag-moved id (.-clientX e) (.-clientY e)])
```

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| You are reaching for `useState` or `r/atom` to hold “is this open?” | Application-visible state is moving into a private store | Give it an app-db address, or use the overlay module's reconciled open flag |
| A view-local atom resets or never repaints the view | The body can re-run or be abandoned, and Fresco does not subscribe to the atom | Move the fact to app-db; move genuine widget mechanics into a native component |
| You are looking for `:on-mount`, `componentDidMount`, or a mount effect | Fresco has no generic lifecycle hook | Identify the job and use the owner in the table above |
| Every panel opens at once | All instances share one address | Include a stable instance key in the address |
| `:rf.error/fresco-state-bad-argument`, thrown by the registration or as the cause of a `:rf.error/sub-exception` or `:rf.error/handler-exception` | A `reg-state` read or write got a `nil` or non-data instance key, or the registration has an unqualified concern or an unknown option | Pass a stable id such as the entity id; the error's reason names which argument is wrong |
| Typing or dragging lags and Xray shows an event per pointer move | High-rate mechanics were routed through app-db | Keep pointer mechanics inside the host and dispatch only the semantic result |
| A dismissed item vanishes before its CSS exit finishes | Exit retention was treated as app-db state, or Presence was not used | See [Motion and presence](12-motion-and-presence.md) |
| app-db accumulates many `:ui` entries | Application-visible UI state is correctly stored there | Namespace the slice and exclude it from persistence when appropriate |
| A test simulates clicks only to open a dropdown | The open flag is data | Seed the address directly in the test ([Testing](15-testing.md)) |

??? info "Coming from Reagent"
    `r/atom` solved a view-local reactivity problem that Fresco does not
    create. Put semantic state at addresses, drafts in the forms model,
    mechanics in hosts, browser-owned facts in the DOM, and exit retention in
    [Motion and presence](12-motion-and-presence.md).

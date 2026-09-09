# Ephemeral state: where it belongs

A dropdown can be open, a field can hold a half-typed draft, and a drag can
have an in-flight pointer position. Those facts do not all belong in the same
place.

Hicasso has no component-local reactive cell. There is no Hicasso equivalent
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

`h/reg-state` mints the pair every instance shares — one parameterised
subscription and one setter event under `[:ui concern instance-key]` — and
nothing else:

```clojure
(ns app.panels
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(h/reg-state ::expanded? {:default false})

(h/defview panel [{:keys [id title]}]
  (let [expanded? (h/sub [::expanded? id])]
    [:section
     [:h3 {:on-click [::expanded? id (not expanded?)]} title]
     (when expanded?
       [panel-body {:id id}])]))
```

`(h/sub [::expanded? id])` reads, `[::expanded? id value]` writes, and
`[::h/clear ::expanded? id]` removes the entry so that instance reads its
default again. The concern must be a namespace-qualified keyword — it is a sub
id, an event id and an app-db key at once — and registering it again with a
different `:default` refuses. A hundred panels reuse the one pair, and the
address gives you replay, frame isolation, Xray visibility, and direct test
setup.

When a change means more than "this slot now holds that value" — something
else must happen, or the change itself must be recorded — write a named event
and its subscription by hand instead:

```clojure
(rf/reg-sub :panel/expanded?
  (fn [db [_ panel-id]]
    (get-in db [:ui :panel/expanded panel-id] false)))

(rf/reg-event :panel/toggled
  (fn [{:keys [db]} [_ panel-id]]
    {:db (update-in db [:ui :panel/expanded panel-id] not)}))
```

`[:panel/toggled id]` records what happened and leaves room for effects or
related state changes later; `[::expanded? id true]` records only the value.
Either way, prefer a named event over a generic `[:ui/set path value]`.

## 2. Drafts and form state: the forms module

A draft is application-visible when validation, submit gating, dirty-leave
logic, or another view needs it. Store it at an app-db address, usually through
`re-frame.hicasso.forms`.

The forms module is one view, `forms/buffered-field`: a draft in front of a
committed value, with a baseline, a commit protocol and the `::h/revision`
reset, at an address you supply ([Forms](05-forms.md)). Validation gating and
submit status are recipes on ordinary events and subscriptions, taught in the
same chapter; there is no form object, validation DSL or submit orchestrator
to require. For a smaller concern, ordinary events and an app-db slice are
enough:

```clojure
[:search/draft-changed q]
[:search/cleared]
```

The important part is that the draft has one address and no local duplicate.

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

The rule at the edge is: **motion stays inside; meaning leaves as one event.**

```clojure
(ns app.board.drag
  (:require ["react" :as react]
            [re-frame.hicasso :as h]))

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
                (js/Math.round (/ (aget xy 0) 240))))
             (set-xy nil))}
      (.-label props))))

(h/defhost drag-card drag-surface)

(h/defview board-card [{:keys [id]}]
  (let [title (h/sub [:card/title id])]
    [drag-card {:label   title
                :on-drop (h/event [col] [:card/dropped id col])}]))
```

Pointer movement remains local React state. The completed drop is an
application event, so it enters app-db once: the host declares `:on-drop` as an
event callback, and the island calls it with the column it computed.

Hooks belong in the island. A `defview` body may
branch and loop dynamically, so putting hooks there makes hook order depend on
data and moves the body outside Hicasso's headless model.

## 4. Browser-owned state

Sometimes the platform already owns the fact. Do not mirror it in app-db
unless the application needs a semantic copy.

**CSS** should own hover, focus-visible, active state, `:has()`, and ordinary
`<details>` disclosure.

**Uncontrolled inputs** may own scratch text through `:default-value`, with a
commit on blur. The tradeoff is explicit: app-db, tests, and tools cannot see
mid-edit text ([Controlled inputs](04-controlled-inputs.md)).

**Platform controls** may own a presentational toggle, such as a native
popover triggered by `:popovertarget` ([Overlays and focus](13-overlays-and-focus.md)).

DOM ownership is a local design choice, not a hidden replacement for
application state. When validation, another view, routing, or testing needs the
fact, move it to app-db.

## 5. Exit retention: pixels that outlive data

App-db records what is true. A dismissed toast should leave app-db immediately,
but its DOM node may need a short exit animation. That gap is **not**
ephemeral application state — it is paint retention.

Use the optional [`re-frame.hicasso.motion`](12-motion-and-presence.md) module
and `motion/presence`. That chapter owns the API, the phase markers
(`::motion/mounting` / `::motion/unmounting`, on elements and views alike), SSR
behaviour, and accessibility attributes for exiting nodes.

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

Application-visible and form state need an instance key. Hicasso does not
invent one. React's `useId` is unsuitable because it is tied to render order
and does not provide a durable app-db address.

Use authored data: a keyword, string, number, or flat vector of those values.

1. **Start with a domain id.** Qualify ids when different entity types can
   collide: `[:order/id 42]` and `[:invoice/id 42]`.
2. **Key placement state by placement and value state by entity.** Two panes
   may share one order draft while keeping separate expanded/collapsed state.
3. **Extend a parent key for nested instances.** `[panel-id :filter]` is often
   enough.
4. **Apply the same stability test as a React `:key`.** It must be derived from
   data, stable across renders, unique in its scope, and deterministic under
   SSR.

## There is no `:on-mount`

Hicasso has no `:on-mount` or `:on-unmount`. The job you are trying to perform
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
;; Don't: this atom is recreated whenever the body runs, and Hicasso does not
;; track it as reactive state.
(h/defview broken-panel [{:keys [id title]}]
  (let [expanded? (atom false)]
    [:section
     [:h3 {:on-click (fn [_] (swap! expanded? not))} title]
     (when @expanded?
       [panel-body {:id id}])]))

;; Don't: one event, subscription pass, and paint for every pointer move.
:on-pointer-move
(h/event [e] [:card/drag-moved id (.-clientX e) (.-clientY e)])
```

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| You are reaching for `useState` or `r/atom` to hold “is this open?” | Application-visible state is moving into a private store | Give it an app-db address, or use the overlay module's reconciled open flag |
| A view-local atom resets or never repaints the view | The body can re-run or be abandoned, and Hicasso does not subscribe to the atom | Move the fact to app-db; move genuine widget mechanics into a native component |
| You are looking for `:on-mount`, `componentDidMount`, or a mount effect | Hicasso has no generic lifecycle hook | Identify the job and use the owner in the table above |
| Every panel opens at once | All instances share one address | Include a stable instance key in the address |
| Typing or dragging lags and Xray shows an event per pointer move | High-rate mechanics were routed through app-db | Keep pointer mechanics inside the host and dispatch only the semantic result |
| A dismissed item vanishes before its CSS exit finishes | Exit retention was treated as app-db state, or Presence was not used | See [Motion and presence](12-motion-and-presence.md) |
| app-db accumulates many `:ui` entries | Application-visible UI state is correctly stored there | Namespace the slice and exclude it from persistence when appropriate |
| A test simulates clicks only to open a dropdown | The open flag is data | Seed the address directly in the test ([Testing](15-testing.md)) |

??? info "Coming from Reagent"
    `r/atom` solved a view-local reactivity problem that Hicasso does not
    create. Put semantic state at addresses, drafts in the forms model,
    mechanics in hosts, browser-owned facts in the DOM, and exit retention in
    [Motion and presence](12-motion-and-presence.md).

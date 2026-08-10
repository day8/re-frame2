# Ephemeral state: where everything lives

Is this dropdown open? Is this row selected? Where do the half-typed draft, the
in-flight drag position, and the vendor SDK handle go?

In Reagent you use `r/atom`. In React you use `useState`. [Hicasso](glossary.md#hicasso) has neither:
no component-local reactive cell, no local-state tier. These cells are not
discouraged; they do not exist. Most of the "local state" a view layer teaches
you to want came from machinery Hicasso does not have — reaction capture, argv
memoization, a second reactive system. The state that remains is real, and
every kind of it has a named home. This page lists each home.

> **App-db owns every fact the application can see, mechanics stay inside
> their host, and no state ever gets a second reactive store.**

## Why one owner, really

The [one state owner](glossary.md#one-state-owner) law is not a restriction
for its own sake. Three other product promises depend on it.

**Tests stay data.** When "the dropdown is open" is a value at an address, a
test opens the dropdown with a `:db` write. The test does not simulate a
click, mount a component, or start a timer. The full open/close/dismiss policy
of a widget is provable headlessly ([Testing](14-testing.md)). A private cell
would move every one of those tests into a browser.

**Xray can attribute work.** The [causal lens](glossary.md#causal-lens) runs *event → subscriptions →
[boundaries](glossary.md#boundary) → commit → paint*. Every re-render has a cause that Xray can name,
because every application write goes through the one write clock: the
re-frame2 state commit. A second reactive store invalidates views on a clock
Xray cannot see. That work has no cause, permanently
([Diagnostics](15-diagnostics.md)).

**Frames stay isolated.** State at an address is per-frame by construction.
Mount the same app in two frames, and their dropdowns cannot interfere. A
module-level atom is shared by every frame that ever mounts the view. That
shared cell is exactly the cross-frame leak the frame model exists to delete.

A host may still hold state — React state, DOM state, a canvas — under one
condition: the state is never an invisible duplicate of an application fact.
Each legitimate home is a [pressure valve](glossary.md#pressure-valve) — a
named place for one kind of UI state. The valves below are the complete list.
If a piece of state does not fit one valve, the state goes to app-db.

## Valve 1: an explicit app-db address

This valve is the default for everything application-visible: open, expanded,
selected, the chosen tab. The usual objection is ceremony — an event, a
subscription, and a keypath for "is this open?". But the cost is per
*concern*, not per instance. One parametric subscription and one named event
serve every instance:

```clojure
(ns app.panels
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(rf/reg-sub :panel/expanded?
  (fn [db [_ panel-id]] (get-in db [:ui :panel/expanded panel-id] false)))

(rf/reg-event :panel/toggled
  (fn [{:keys [db]} [_ panel-id]]
    {:db (update-in db [:ui :panel/expanded panel-id] not)}))

(h/defview panel [{:keys [id title]}]
  [:section
   [:h3 {:on-click [:panel/toggled id]} title]
   (when (h/sub [:panel/expanded? id]) [panel-body {:id id}])])
```

You write these ten lines once. A hundred panels add no further cost. The
address also gives what a local cell cannot: the state time-travels, Xray
shows it, each frame isolates it, and a test sets it with a `:db` write.

Write **named events, not a generic setter**. `[:panel/toggled id]` names what
happened. A generic `[:ui/set path value]` turns the event log into a diff
stream that nobody can read. When a write starts to *mean* something — an
effect fires, or other state reacts — the named event is already the correct
shape.

## Valve 2: drafts and control state — the forms module

A draft is not private. Validation reads it, the submit gate derives from it,
dirty-leave navigation asks about it, and replay replays it. Anything a user
composes that the application must judge is **application-visible**, so it
lives at a re-frame2 address. Normally the address goes through
`re-frame.hicasso.forms`. The module packages the draft/baseline/touched/errors
shape at an address you give it, and derives validation gating and mutation
status as ordinary subscriptions ([Forms](05-forms.md)).

You do not need the module to obey the valve. A concern-named address and two
events — `[:search/draft-changed q]`, `[:search/cleared]` — make the same
decision at hand-rolled scale. The module earns its place when the shape
grows: touched tracking, submit-attempt gating, settle-merge against late
server replies. Either way the state is at an address, and no second store
appears.

## Valve 3: host-private mechanics — inside a native host

Some state exists only to *operate a widget*: measured geometry, an in-flight
drag position, composition buffers, focus mechanics inside a composite
control, a chart library's instance handle. This state updates at 60–240 Hz,
and nobody outside the widget can act on it. A route through app-db would
spend an event, a subscription pass, and a paint per pointer-move on a fact
with no meaning.

That state stays **inside a native host** — a named native component (or a
[`defhost`](glossary.md#defhost) edge) where ordinary React state and hooks are the correct tool
([The native tier](10-native-tier.md), [Interop](09-interop.md)). The host is
**diagnostic-opaque by contract**. Xray names and times the island's [boundary](glossary.md#boundary).
It labels the inside `opaque`; it does not pretend to know it. You trade
visibility for locality, on purpose, in a fenced place.

The law at the edge: *motion stays inside; meaning leaves as one event.*

```clojure
(ns app.board.drag
  (:require ["react" :as react]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.native :as n]))

(n/defcomponent drag-surface
  [^js props]
  (let [[xy set-xy] (react/useState nil)]        ; host-private: in-flight only
    (n/$ :div
         {:class "card"
          :style (when xy #js {:translate (str (aget xy 0) "px " (aget xy 1) "px")})
          :on-pointer-move (fn [e]
                             (when (pos? (.-buttons e))
                               (set-xy #js [(.-clientX e) (.-clientY e)])))
          :on-pointer-up   (fn [_]
                             (when xy
                               ((.-onDrop props) (js/Math.round (/ (aget xy 0) 240))))
                             (set-xy nil))}
         (.-label props))))

(h/defview board-card [{:keys [id]}]
  (let [title (h/sub [:card/title id])]
    ;; app-db hears one event per drag; the 120 Hz stream never leaves the host.
    (n/$ drag-surface {:label   title
                       :on-drop (h/event [col] [:card/dropped id col])})))
```

The drop is semantic, so it commits. [`h/event`](glossary.md#hevent) captures the frame and
dispatches `[:card/dropped id col]` when the host calls the callback. The end
of a drag reaches app-db. The moves of a drag do not.

One [boundary](glossary.md#boundary) rule applies. A [`defview`](glossary.md#defview) body is a real React function
component, so a hook physically runs there. But dynamic composition makes
hook order your problem, and a hook body falls out of headless testing. Hook
mechanics belong in a separately defined native component, where hook order
cannot depend on your data paths.

## Valve 4: DOM-owned state — a declared interop choice

Sometimes the platform already tracks the fact. Then the correct amount of
application state is none.

- **CSS.** Hover, focus-visible, active, `:has()`, `<details>` disclosure. If
  the platform tracks the fact, a copy in app-db costs a re-render per pointer
  move for a fact the browser already has.
- **Uncontrolled inputs.** A spreadsheet cell with `:default-value` and
  commit-on-blur leaves the mid-edit text to the DOM by design
  ([Controlled inputs](04-controlled-inputs.md)). The price is explicit:
  app-db cannot see the draft, so nothing else can react to the draft, and
  tests must go through the DOM.
- **Platform toggles.** A presentational hint can be a bare `:popover "auto"`
  panel toggled by `:popovertarget` — zero application state
  ([Overlays and focus](12-overlays-and-focus.md)).

The valve's condition is the word *declared*. DOM ownership is a visible,
priced choice at the site — never a hidden substitute for application state.
When anything else needs the fact (validation needs the draft, a test needs
the open flag), the fact moves up a valve.

## Valve 5: presence — what is still painted

App-db cannot hold one thing. A dismissed toast is gone from app-db, which is
correct — but the toast should fade for 300 ms, so the node must outlive the
data. App-db holds what is *true*; this valve holds what is still *painted*.
`re-frame.hicasso.motion` owns that gap. Its `presence` view retains keyed
children that have left the source data, for `:timeout-ms`. Each child
declares its exit appearance as data, in its own attribute map:

```clojure
;; (:require [re-frame.hicasso.motion :as motion])
(h/defview toast-tray [_]
  [motion/presence {:timeout-ms 300}
   (for [t (h/sub [:toasts/visible])]
     [:div.toast {:key (:id t)
                  ::motion/unmounting {:class "toast toast--exit"
                                       :inert true :aria-hidden true}}
      (:message t)])])
```

The a11y attributes are one map, not three conditionals. A retained node is
still in the document, and it can take focus and clicks until you say
otherwise. So `:inert`, `:aria-hidden`, and the exit class arrive together,
in the phase where they belong. When the child is a view instead of a node,
presence cannot merge attributes into an opaque head. Instead the view
receives `:rf/phase` (`:mounting` / `:present` / `:unmounting`) as an
ordinary prop and branches on it. A test can pass the prop directly, with no
timers and no browser.

These rules keep presence honest:

- `:timeout-ms` is mandatory. It is a hard terminal bound on a clock, never a
  `transitionend` listener. The node leaves on time even when your CSS did
  not run, was disabled, or was overridden by `prefers-reduced-motion`.
- Re-entry cancels exit. A returning key goes back to `:present` without a
  remount. Order is frozen at first appearance, so an exiting child never
  changes position.
- Presence never dispatches an event. A node that remains on screen is not a
  reason for app-db to keep the data.

Entrances rarely need presence. Animate on insertion (`@keyframes` on the
class) or use `@starting-style`; neither can lose the first-paint race the
way a `:mounting → :present` class flip can. `::motion/mounting` is for
attributes that are *true during entry* — `:inert` until the element settles —
not for a fade-in. Under SSR a presence-managed node hydrates born-present, so
server HTML never carries entry-phase attributes
([SSR and hydration](17-ssr-and-hydration.md)).

## Where does X live?

| State | Home | Why |
|---|---|---|
| Dropdown open | App-db, explicit address (the [overlay module](12-overlays-and-focus.md) reconciles the platform to it) | It changes what the user can do next; tests and Xray need it |
| Draft text in a field | App-db through the [forms module](05-forms.md) | Validation, submit gating, dirty-leave and replay all read it |
| Drag position, mid-drag | Host-private, inside the [native island](glossary.md#native-island) | 60–240 Hz mechanics; only the widget cares. The drop dispatches one event |
| Scroll offset | The DOM owns it; the [routing module](07-routing-and-navigation.md) restores it per route | Nobody re-renders per scrolled pixel. If the app cares ("read 80%"), commit thresholds as events |
| Animation phase | CSS for the animation itself; `motion/presence` for leave-retention; host-private for rAF mechanics | App-db says what is true, not what is still painted |
| Focus | The platform. One-shot [intent](glossary.md#intent) as data — `:auto-focus`, [overlay focus conduct](12-overlays-and-focus.md) — never mirrored | A mirror of "what has focus" drifts from reality and re-renders per Tab |
| Selected tab | App-db — or the route, when a reload should land on the same tab | Semantic; other views, tests and deep links care |
| WebGL context, vendor handle | Host-private, inside its declared host, acquired and released at the edge ([Interop](09-interop.md)) | An object identity, not application data; unmount must release it |

## Choosing the address

Valves 1 and 2 need an instance key — the `panel-id` above — so a hundred
panels do not share one `expanded?`. [Hicasso](glossary.md#hicasso) mints no identity for you.
React's `useId` does not fit: its ids are render-order counters, they do not
survive a remount, and address-resident state cannot tolerate that loss. The
key is authored data: a keyword, a string, a number, or a flat vector of
those.

1. **Use domain ids first; qualify by entity when entities can collide.**
   Order 42 and invoice 42 that both land on `[:ui ::expanded 42]` share one
   entry. Qualify them: `[:order/id 42]`, `[:invoice/id 42]`.
2. **Key placement-like concerns by placement; key value-like concerns by
   entity.** A draft of order 42 is value-like: both panes share one draft.
   `expanded?` is placement-like: the detail pane can collapse while the list
   row stays open.
3. **Nest by extension of the parent's key.** A widget inside a widget adds
   to the parent's key — `[panel-id :filter]` — as plain data, with no
   helper.
4. **A good React `:key` is a good instance key.** The key derives from your
   data, stays stable across renders, and is unique among siblings. The key
   is also deterministic under SSR, where server and client must compute the
   same key from the same snapshot
   ([SSR and hydration](17-ssr-and-hydration.md)).

## Where is `:on-mount`?

There is no `:on-mount`, and no `:on-unmount` either. Four jobs make people
search for one. Each job has a home:

| Job | Home |
|---|---|
| Load the data this screen needs | The route declares it — [Routing](07-routing-and-navigation.md), [Async resources](08-async-resources.md). This also closes the click-away race: a route-owned read has an owner to release it |
| Run something once at startup | `:initial-events` — ordinary events seeding app-db before first paint ([Installation](installation.md)) |
| Animate an entrance or exit | Valve 5. Insertion animation or `@starting-style` for enter; presence for exit |
| Drive a real DOM node or third-party SDK | The host edge: a callback `:ref` or a declared host ([Interop](09-interop.md)) |

## When app-db is the wrong home

App-db is the wrong home in three cases:

- The platform already knows the fact (hover, focus, scroll — valve 4).
- The rate is too high to carry meaning (drag, resize, rAF — valve 3).
- Nobody else can care about it (a measured offset, an SDK handle — valve 3).

Everything else is the application's business, and the application has
exactly one memory.

```clojure
;; Don't — an atom in a view. The body re-runs, retries, and is abandoned;
;; the atom is re-minted on each run, and no atom re-renders anything here.
(h/defview broken-panel [{:keys [id title]}]
  (let [expanded? (atom false)]                    ; reset on every render
    [:section
     [:h3 {:on-click (fn [_] (swap! expanded? not))} title]  ; repaints nothing
     (when @expanded? [panel-body {:id id}])]))

;; Don't — app-db as a motion channel: an event, a sub pass and a paint
;; per pointer-move, and the event log becomes a 120 Hz diff stream.
:on-pointer-move (h/event [e] [:card/drag-moved id (.-clientX e) (.-clientY e)])
```

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Reaching for `useState` / `r/atom` to hold "is this open?" | Application-visible state headed for a private store | An app-db address (valve 1), or the [overlay](glossary.md#overlay) module's reconciled flag |
| A view's atom resets every render, or never repaints | Bodies re-run and are abandoned; render-minted cells are re-minted, and nothing tracks them | Move the fact to its valve; if it is genuinely widget mechanics, move it into a native host |
| Searching for `:on-mount`, `componentDidMount`, a mount `useEffect` | There is none | Name the job and use its home — the table above |
| Every panel in a list opens at once | One shared address | Key the address per instance — [Choosing the address](#choosing-the-address) |
| Typing or dragging lags; Xray shows an event per pointer-move | High-rate mechanics routed through app-db | Keep motion host-private; dispatch only the semantic commit |
| An exit override on a view head raises `:rf.error/hicasso-presence-override-on-a-view` | Presence merges attribute overrides into nodes it can see; a view head is opaque | The child view receives `:rf/phase` — branch on it |
| A fading toast still takes focus and clicks | The exit override lacks the a11y pair | `::motion/unmounting` carries `:inert` and `:aria-hidden` alongside the class |
| A dismissed item vanishes with no exit animation | The node left with the data | `motion/presence` with `:timeout-ms` at least as long as the transition |
| app-db is filling with `:ui` entries | Expected — the right home | Namespace the keys; exclude the tier from persistence by convention |
| A test simulates clicks to open a dropdown | The state is data | Seed the address with a `:db` write ([Testing](14-testing.md)) |

??? info "If you're coming from Reagent"
    `r/atom` was necessary against machinery [Hicasso](glossary.md#hicasso) does not have. In the
    idiomatic corpus this model was distilled from — 85 files, ~140 views —
    the count of view-local reactive cells is zero. The machinery
    manufactured the demand. The valves absorb what was real: addresses for
    meaning, forms for drafts, hosts for mechanics, the DOM for what it
    already owns, motion for what still fades.

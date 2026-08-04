# Ephemeral state

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

Is this dropdown open? Is this row hovered? Is this panel expanded?

In Reagent you reach for `r/atom`. In React you reach for `useState`. In Hicasso
there is **no `local`** — no component-local reactive cell, no ratom, no
`useState` for application state. Not discouraged. Absent.

Most "local state" demand was manufactured by machinery Hicasso does not have —
reaction capture, argv memoization, a second reactive system. Open, hover,
expanded: put them where the rest of the app already looks.

> **Semantic application state belongs in app-db. Component mechanics —
> composition, measurement, focus, animation, SDK handles — may use ordinary
> React hooks at the host edge.**

The test is whether anyone other than this component could care. A dropdown's open
state affects what the user can do next, so it is semantic. A tooltip's measured
pixel offset is arithmetic, so it is mechanics.

A `defview` body is a real React function component. Hooks physically work in it.
There is no lint police, and if you use one you take on React's hook rules
yourself — including the loss of headless testability for that body
([Testing](08-testing.md)). When hooks are for a measured hot path rather than placement, see [Performance](11-performance.md).

## The order to try

1. **CSS.** Hover, focus, active, `:has()`, `<details>`. If the platform already
   tracks it, no state exists at all.
2. **Platform-carried state.** Overlays that own open/dismiss, resources that own
   async status, a controls kit that owns drafts — when those exist. They don't
   ship yet; until they do, skip to the next step or use app-db.
3. **Host-private React state at host edges.** Geometry, composition,
   measure-before-paint. Local to the edge, invisible to the app.
4. **app-db**, for everything semantically meaningful.

Work down the list. Most of the time you stop at 1.

## app-db for open / expanded / selected

The objection is ceremony: an event, a subscription, and a keypath for "is this
open?"

The tax is per-concern, not per-instance. One parametric subscription and one
named event serve every instance forever:

```clojure
(rf/reg-sub :panel/expanded?
  (fn [db [_ panel-id]] (get-in db [:ui :panel/expanded panel-id] false)))

(rf/reg-event :panel/toggle
  (fn [{:keys [db]} [_ panel-id]]
    {:db (update-in db [:ui :panel/expanded panel-id] not)}))
```

```clojure
(defview panel [{:keys [id title]}]
  [:section
   [:h3 {:on-click [:panel/toggle id]} title]
   (when (sub [:panel/expanded? id]) [panel-body {:id id}])])
```

Ten lines, once. A hundred panels, no further cost. And you get what a local cell
cannot give: the state time-travels, it is visible in Xray, it is isolated per
frame, and a test can set it with a `:db` write — "open this dropdown" is not a
click simulation.

### The short form: `h/reg-state`

`h/reg-state` **[unfrozen]** is sugar for the same ordinary sub and event:

```clojure
(h/reg-state ::expanded? {:default false})
;; mints: a parametric sub        (sub [::expanded? panel-id])
;;        a concern-named setter  [::expanded? panel-id true]
;;        the documented path     [:ui ::expanded? panel-id]
```

One declaration per *concern* — a namespace-qualified keyword — with
`{:default v}` as the only option. No second state system: it registers ordinary
re-frame artefacts that read and write plain app-db at a documented path. Time
travel, Xray, per-frame isolation, and tests writing `:db` all still work.

Keep the long form in mind. It is the definition of what `reg-state` mints, and
the shape you graduate to when a write starts to *mean* something (the example's
`:panel/toggle` is already that graduation).

Two details that fail silently if you get them wrong:

**Clearing is a framework event, not a magic value.** Dispatch
`[::h/clear ::expanded? panel-id]` and the entry is removed, so the default shows
through again. A reserved clear *value* is not offered — a concern whose
legitimate values are keywords could then silently delete state.

**A nil or malformed instance key refuses loudly**, at read and at write, with
`:rf.error/hicasso-state-bad-key` naming the concern — instead of quietly filing
every instance under the same broken key.

## Choosing the instance key

`reg-state` and the long form both need an instance key — `panel-id` above — so a
hundred panels do not share one `expanded?`. Hicasso mints no identity for you.
React's `useId` is not a fit: hydration ids can diverge under a hydration root,
and outside hydration it is a counter whose ids do not survive remount — which
app-db-resident state cannot tolerate. The key is authored data: a keyword,
string, number, or vector of these.

Checklist:

1. **Domain ids first — entity-qualify when entities can collide.** Prefer the
   order's id, the row's id, a namespaced keyword for a singleton. Bare ids
   collide across entity types: order 42 and invoice 42 both landing on
   `(sub [::expanded? 42])` share one entry. Qualify the value:
   `[:order/id 42]` and `[:invoice/id 42]`.
2. **Placement-like concerns key by placement; value-like concerns key by
   entity.** Is the concern about the *slot on screen* or the *thing shown in
   it*? A draft of order 42 is value-like — both panes share one draft.
   `expanded?` is placement-like — collapse the detail pane without folding the
   list row.
3. **Nest with `h/child-key`.** A widget inside a widget extends its parent's key
   instead of inventing a fresh one:

   ```clojure
   (let [filter-key (h/child-key panel-id :filter)]  ;; => [panel-id :filter]
     (sub [::filter-open? filter-key]))
   ```

   Conj's onto a key that is already a vector, so every key bottoms out as a flat
   vector of authored data.
4. **If it would be a good React `:key`, it is a good instance key.** Derived from
   your data, stable across renders, unique among siblings. Same judgment for SSR
   determinism: server and client must compute the same key from the same
   snapshot — authored data does; render-order counters do not. Instance state
   lives under `[:ui …]`; when server-side events write render-affecting instance
   state, the payload allowlist must name `:ui`, or hydration reports the
   mismatch ([Server-side rendering](10-server-side-rendering.md)).

## Named events, not a generic setter

Write `[:panel/toggle id]`, never a generic `[:ui/set [:panel/expanded id] true]`.

A named event is a name for what happened — the event log stays readable. A
generic setter turns history into a diff stream. `reg-state` keeps that law for
the sugar: the setter it mints is **named by its concern** —
`[::expanded? panel-id false]` — never a generic `ui/set`.

The concern-named setter is a floor, not a ceiling. When an occurrence means more
than an assignment — fire an effect, other state reacts, the log should say
*toggle* — graduate to a named domain event and let the setter go. The sugar
exists so ceremony never stops you putting state where it belongs, not so
assignment replaces meaning.

## Exit animation: `h/presence`

One piece of view state app-db cannot hold: a toast is dismissed and gone from
app-db (correct), but it should fade out over 300ms, so the node has to outlive
the data. App-db is about what is *true*; this is about what is still *painted*.

`h/presence` retains keyed children that have left the source data, for
`:timeout-ms`, and lets each child say what it looks like on the way out — **in
its own attribute map**:

```clojure
(defview toast-tray [_]
  [h/presence {:timeout-ms 300}
   (for [t (sub [:toasts/visible])]
     [:div.toast {:key (:id t)
                  ::h/unmounting {:class "toast toast--exit"
                                  :inert true :aria-hidden true}}
      (:message t)])])
```

**There is no child view.** The whole tray is inline. Exit attributes sit on the
node itself as data, so inline markup is safe — phase is not an ambient dynamic
that could silently resolve to the parent's context.

**The a11y attributes are one map, not three conditionals.** A retained node is
still in the document: it can take focus and clicks until you say otherwise.
`:inert`, `:aria-hidden`, and an exit class arrive together, as data, in the phase
they belong to.

**When the child is a view, the phase is an ordinary prop** — branch on it for
exit styling (presence cannot merge `::h/unmounting` into an opaque view head):

```clojure
(defview toast-card [{:keys [toast] :as props}]
  (let [exiting? (= (:rf/phase props) :unmounting)]
    [:div.toast (cond-> {:class "toast"}
                  exiting? (assoc :class "toast toast--exit"
                                  :inert true
                                  :aria-hidden true))
     (:message toast)]))

;; In the tray:
[toast-card {:key (:id t) :toast t}]
```

A test can pass `:rf/phase :unmounting` and assert the exit rendering with no
timers, no browser, and no clock.

### What presence does not do

- `:timeout-ms` is mandatory — both the retention length and a hard terminal
  bound. Presence is a clock, not a `transitionend` listener, so the node leaves
  on time whether or not your CSS ran, was disabled, or was overridden by
  `prefers-reduced-motion`.
- Re-entry cancels exit: a toast that comes back before the timeout returns to
  `:present` rather than finishing exit and remounting.
- Presence never dispatches anything — a node lingering on screen is not a reason
  for app-db to linger with it.
- **Order is frozen at first appearance**, so an exiting child does not jump slots
  mid-animation. Surviving keys keep the order they had; new keys are appended. A
  list that re-sorts while items are leaving sorts at the data layer, not here.

### Enter is the weak half

`::h/mounting` is the mirror of `::h/unmounting`: an attribute-override map
presence merges onto nodes in their `:mounting` phase. It ships and it works.
Driving an entrance as a `:mounting` → `:present` class flip can lose the race to
the browser's first paint, and then nothing animates. For enter, prefer an
animation on insertion or `@starting-style`:

```css
.toast { animation: toast-in 200ms ease-out; }
@keyframes toast-in { from { opacity: 0; translate: 0 8px; } }
.toast--exit { opacity: 0; translate: 0 8px; transition: opacity 250ms, translate 250ms; }
@media (prefers-reduced-motion: reduce) { .toast { animation: none; transition: none; } }
```

Exit transitions happily, because the node is already painted. `::h/mounting`
earns its keep on attributes that are simply *true during entry* — `:inert` and
`:aria-hidden` until settled — not as the recommended way to fade something in.

Under SSR, a presence-managed node **hydrates born-present**, so server HTML never
carries `:mounting`-phase attributes
([Server-side rendering](10-server-side-rendering.md)). Nothing that arrived with
the page replays an entrance over content the user is already reading — which
means the first paint of a server-rendered page is not a moment when
`::h/mounting` has been applied.

## Where is `:on-mount`?

There is no `:on-mount` and no `:on-unmount`. Hicasso took the *attribute* half of
Replicant's mechanism (`::h/mounting` / `::h/unmounting`) and rejected the
callback half as less data-oriented than a registered behaviour. Presence knows a
node arrived or left, and it never dispatches about either. Four jobs send people
looking for `:on-mount`; each has a home:

| Job | Home |
|---|---|
| Load the data this screen needs | The route declares it — `:resources` on entry, `:on-match` for activation events. Also closes the click-away race: a fetch kicked off by a mounting component has nothing to suppress the late reply when the user navigates away first ([Routing](../../../routing/concepts.md#loaders-declaring-a-pages-data)) |
| Run something once at startup | `:initial-events` — ordinary events, in order, seeding app-db *before* first paint ([Getting started](01-getting-started.md)) |
| Animate an entrance or exit | Presence, above. `::h/unmounting` for exit; animation on insertion or `@starting-style` for enter |
| Drive a real DOM node or a third-party SDK | The host edge. A callback `:ref` hands you the node on attach and takes your return value as the cleanup; a `defhost` component brings whatever hooks it already had ([Interop](05-interop.md)) |

The data spelling of a host behaviour — `{:ref [::autosize {:max-rows 8}]}` — is
refused today with `:rf.error/hicasso-ref-vector-reserved`. That value space is
reserved, not designed. Until it lands, write the function.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Reaching for `useState` to hold "is this open?" | That's semantic state | app-db (`reg-state` or the long form), or CSS if the platform tracks it |
| Searching for `:on-mount`, `componentDidMount`, or a mount `useEffect` | There is none | Name the job: route `:resources` for page data, `:initial-events` for startup, presence for animation, a callback `:ref` or `defhost` at a host edge |
| A dismissed item disappears instantly with no exit animation | The node left with the data | `h/presence` with a `:timeout-ms` at least as long as the exit transition |
| A retained node still takes focus or clicks while fading | The exit override does not carry `:inert` / `:aria-hidden` | Put all three in the `::h/unmounting` map |
| An exit override on a view head raises `:rf.error/hicasso-presence-override-on-a-view` | Presence merges overrides into nodes it can see; a view is opaque, and a silently dropped override is the failure the loud error exists to delete | The view receives `:rf/phase` — branch or style on that |
| Every panel in a list opens at once | The state isn't parameterised by instance — or two instances share one key | Key the path per instance: [Choosing the instance key](#choosing-the-instance-key) |
| A nil or malformed instance key raises `:rf.error/hicasso-state-bad-key` | The key is not a keyword, string, number, or vector of those | Author a stable domain or placement key; nest with `h/child-key` when needed |
| A hook body can't be tested headlessly | Hooks put a body outside headless scope, by design | Move the semantic half to app-db; mount-test the mechanics |
| Hook-order error after a conditional early-return | React's rules, now yours | Hooks at the top of the body, unconditionally |
| app-db is full of `:ui` noise | Expected — it is the right home | Namespace the keys and exclude the tier from persistence by convention |

## When app-db is the wrong home

**The platform already knows.** Hover and focus are CSS. Writing them into app-db
is a re-render per pointer move for a fact the browser was tracking for free.

**High-rate host mechanics.** A drag in flight, a scroll offset, an animation
frame — 60 to 240 updates a second. Host-local motion off app-db; semantic commits
into it. Drag *ends* in app-db. Drag *moves* do not.

**Values nobody else can see.** A measured pixel offset, an SDK handle, a
composition buffer. Host-private React state at the host edge; no one outside that
edge needs to know it exists.

## Not settled yet

| Question | Status |
|---|---|
| Vector `:ref` host behaviours (`{:ref [::id opts]}`) | Reserved, not designed — refused today with `:rf.error/hicasso-ref-vector-reserved`; write a function ref until a data spelling exists |
| The controls kit (drafts, revisions) | Not shipped yet |
| The overlay layer that would own open and dismiss | Not shipped yet |
| The spellings | `h/reg-state`, `h/presence`, `h/child-key` and friends are experimental names; every spelling on this page is **[unfrozen]** until the API freeze |

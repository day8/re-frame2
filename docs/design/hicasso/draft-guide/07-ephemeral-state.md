# Ephemeral state

> **Draft ahead of the product artefact.** This page teaches the landed surface,
> witnessed by the bench arm under
> `implementation/freehand/test/re_frame/bench/hicasso/` — but no
> `implementation/hicasso/` artefact ships yet, and spellings marked **[unfrozen]**
> stay provisional until the API freeze.

Is this dropdown open? Is this row hovered? Is this panel expanded?

In Reagent you reach for `r/atom`. In React you reach for `useState`. In Hicasso
there is **no `local`** — no component-local reactive cell, no ratom equivalent, and
no `useState` for application state. Not discouraged. Absent.

That is a strong claim, so here is the evidence it rests on: a census of **85
idiomatic re-frame files**, containing every classic hard case, found **zero
view-local reactive cells**. Not few. Zero. Most of Reagent's local-state demand was
manufactured by machinery — the reaction engine, deref capture, argv memoization —
that Hicasso deletes structurally. A second reactive system for application state
is the failure mode this surface refuses to reintroduce.

## The placement rule

One rule, and it is *taught*, not policed:

> **Semantic application state belongs in app-db. Component mechanics —
> composition, measurement, focus, animation, SDK handles — may use ordinary React
> hooks at the honest escape hatch.**

The test is whether anyone other than this component could care. A dropdown's open
state affects what the user can do next, so it is semantic. A tooltip's measured
pixel offset is arithmetic, so it is mechanics.

A `defview` body is an honest React function component. Hooks physically work in it.
There is no lint police in v0, and if you use one you take on React's hook rules
yourself — including the loss of headless testability for that body
([Testing](08-testing.md)).

## The order to try

1. **CSS.** Hover, focus, active, `:has()`, `<details>`. If the platform already
   tracks it, no state exists at all.
2. **Platform-carried state.** The top layer owns open and dismiss for overlays;
   resources and mutations own their own async status; the controls kit owns drafts
   and revisions. See the v0 caveat below — most of this layer is post-v0.
3. **Host-private React state at host edges.** Geometry, composition,
   measure-before-paint. Local to the edge, invisible to the app.
4. **app-db**, for everything semantically meaningful.

Work down the list, not up it. Most of the time you stop at 1.

## app-db without the ceremony

The objection to app-db for UI state is always ceremony: an event, a subscription,
and a keypath for something as small as "is this open?"

The answer comes in two parts, and the first is that **the tax is per-concern,
not per-instance.** One parametric subscription and one named event serve every
instance of the widget in the app, forever:

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

Ten lines, once. A hundred panels, no further cost. And you get the things a local
cell can never give you: the state is in app-db, so it time-travels, it is visible
in Xray, it is isolated per frame, and a test can set it directly without touching a
component.

That last one is worth sitting with. "Open this dropdown in a test" is a `:db`
write, not a click simulation.

The second part is that **the ten lines become one.** `h/reg-state` **[unfrozen]**
is landed sugar, witnessed in the bench arm. It registers the same ordinary sub
and event you would have written by hand:

```clojure
(h/reg-state ::expanded? {:default false})
;; mints: a parametric sub        (sub [::expanded? panel-id])
;;        a concern-named setter  [::expanded? panel-id true]
;;        the documented path     [:ui ::expanded? panel-id]
```

One declaration per *concern* — a namespace-qualified keyword — with
`{:default v}` as the only option. No state system arrives with it: it
registers ordinary re-frame artefacts that read and write plain app-db at a
documented path, which is why everything above stays true under it — time
travel, Xray, per-frame isolation, tests writing `:db`.

Keep the long form in mind anyway. It is the definition of what `reg-state`
mints, and the shape you graduate to when a write starts to *mean* something
(next section — the example's `:panel/toggle` is already that graduation).

Two details matter because getting them wrong is silent otherwise.

**Clearing is a framework event, not a magic value.** Dispatch
`[::h/clear ::expanded? panel-id]` and the entry is removed, so the default
shows through again. A reserved clear *value* is not offered — a concern whose
legitimate values are keywords could then silently delete state.

**A nil or malformed instance key refuses loudly**, at read and at write, with
`:rf.error/hicasso-state-bad-key` naming the concern — instead of quietly filing
every instance's state under the same broken key.

## Choosing the instance key

`reg-state` and the long form share one authored input: the instance key —
`panel-id` above, the value that keeps a hundred panels from sharing one
`expanded?`. Hicasso mints no identity for you. React's `useId` is not a fit
here: its hydration ids can diverge under a hydration root, and outside
hydration it is a counter whose ids do not survive remount — which state
resident in app-db cannot tolerate. The key is authored data — a keyword,
string, number, or vector of these — and four rules cover choosing it.

**Domain ids first — entity-qualified when entities can collide.** The best key
already exists in your data: the order's id, the row's id, a literal namespaced
keyword for a singleton placement. But a *generic* widget serving two entity
types must not key by bare id — order 42 and invoice 42 both landing on
`(sub [::expanded? 42])` is one entry, and the two silently share state.
Qualify the id *value*: `[:order/id 42]` and `[:invoice/id 42]` are different
keys, and the vector is already legal key grammar.

**Placement-like concerns key by placement; value-like concerns key by
entity.** Ask whether the concern is about the *slot on screen* or the *thing
shown in it*. A master list and a detail pane both showing order 42: the
order's draft is value-like — key it by entity, and both panes sharing one
draft is correct, because it is the same draft. `expanded?` is placement-like —
key it by placement (or placement plus entity), because collapsing the detail
pane must not fold the list row.

**Nest with `h/child-key`.** A widget inside a widget extends its parent's key
instead of inventing a fresh one: `(h/child-key parent-key :filter)` yields
`[parent-key :filter]`, and conj's onto a key that is already a vector — so
every key bottoms out as a flat vector of authored data, and two children of
two different parents can never collide.

**If it would be a good React `:key`, it is a good instance key.** Derived from
your data, stable across renders, unique among siblings — the same judgment,
reused. This is also the SSR determinism rule: server and client must compute
the same key from the same snapshot, which authored data does and render-order
counters do not. One obligation follows on server-rendered pages: instance
state lives under `[:ui …]`, and the payload policy is fail-closed — when
server-side events write render-affecting instance state, the payload allowlist
must name `:ui`, or the client renders from state the server never sent and
hydration reports the mismatch
([Server-side rendering](10-server-side-rendering.md)).

## Named events, not a generic setter

Write `[:panel/toggle id]`, never a generic `[:ui/set [:panel/expanded id] true]`.

A named event is a name for what happened, which is the entire premise of the event
log being readable. A generic setter turns your event history into a diff stream and
takes the meaning out of the one place the framework was keeping it for you.
`reg-state` keeps that law for the sugar: the setter it mints is **named by its
concern** — `[::expanded? panel-id false]` reads as what it is in the log — never a
generic `ui/set`.

The concern-named setter is a floor, not a ceiling. When an occurrence means more
than an assignment — the collapse should fire an effect, other state reacts, the
log should say *toggle* — graduate to a named domain event like `[:panel/toggle id]`
and let the setter go. The sugar exists so ceremony never stops you putting state
where it belongs, not so assignment replaces meaning.

## What v0 actually gives you

Layer 2 above is mostly a promise. The controls kit that would own drafts and
revisions is post-v0. So is the overlay top layer. In v0, a dismissible dropdown is
CSS if you can manage it, and app-db if you can't.

What *is* in v0 is the ceremony answer: `reg-state` for the ordinary assignment
case, the long form when a write means more than assignment, one conventional
app-space root at `[:ui <concern> <instance-key>]`, and no second state system.
There is still no lint fence around `useState` — the placement rule is taught, not
policed — and the no-`local` core stands throughout.

A complaint about state ceremony is **expected signal**, not a verdict. If
explicitly threading the instance key turns out to be the pain, the next step
is an ambient/auto door — still never a parallel state system.

## The state you cannot put in app-db: "it left, but it is still on screen"

There is one piece of view state app-db genuinely cannot hold, and it is worth
knowing where it lives before you go looking for a `local` to put it in.

A toast is dismissed. It is gone from app-db — that write already happened, and it
was correct. But it should fade out over 300ms, which means the node has to outlive
the data by 300ms. Nothing in app-db can express that, because app-db is about what
is *true*, and this is about what is still *painted*.

That is what `h/presence` is for. It retains keyed children that have left the
source data, for `:timeout-ms`, and it lets each child say what it looks like on
the way out — **in its own attribute map**:

```clojure
(defview toast-tray [_]
  [h/presence {:timeout-ms 300}
   (for [t (sub [:toasts/visible])]
     [:div.toast {:key (:id t)
                  ::h/unmounting {:class "toast toast--exit"
                                  :inert true :aria-hidden true}}
      (:message t)])])
```

Three things about that block.

**There is no child view.** The whole tray is written inline, in the parent.
Phase is not an ambient dynamic that could silently resolve to the parent's
context — exit attributes sit on the node itself as data, so inline markup is
safe.

**The a11y attributes are one map, not three conditionals.** A retained node is
still in the document: it can take focus and clicks until you say otherwise. That
obligation is `:inert`, `:aria-hidden` and an exit class, and here they arrive
together, as data, in the phase they belong to.

**When the child is a view, the phase is an ordinary prop.**

```clojure
[toast-card {:key (:id t) :toast t}]   ;; receives :rf/phase :unmounting
```

Which means a test can pass `:rf/phase :unmounting` and assert the exit rendering
with no timers, no browser and no clock at all.

### What presence does not do

`:timeout-ms` is mandatory, and it is both the retention length and a hard terminal
bound — presence is a clock, not a `transitionend` listener, so the node leaves on
time whether or not your CSS ran, or was disabled, or was overridden by
`prefers-reduced-motion`. Re-entry cancels exit: a toast that comes back before the
timeout returns to `:present` rather than finishing its exit and remounting. And
presence never dispatches anything — a node lingering on screen is not a reason for
anything in app-db to linger with it.

**Order is frozen at first appearance**, deliberately, so an exiting child does not
jump to a new slot halfway through its animation. Surviving keys keep the order
they had and genuinely new keys are appended. A list that re-sorts while items are
leaving therefore sorts at the data layer, not here.

### Enter is the weak half

This guide will not pretend otherwise, so here is the enter side in full.

`::h/mounting` is the mirror of `::h/unmounting`: an attribute-override map that
presence merges onto the nodes it can see while a child is in its `:mounting`
phase — on the way in, rather than on the way out. It exists, it ships, and it
works. But driving an entrance as a `:mounting` → `:present` class flip can lose
the race to the browser's first paint, and then nothing animates. For enter, use
an animation on insertion or `@starting-style`:

```css
.toast { animation: toast-in 200ms ease-out; }
@keyframes toast-in { from { opacity: 0; translate: 0 8px; } }
.toast--exit { opacity: 0; translate: 0 8px; transition: opacity 250ms, translate 250ms; }
@media (prefers-reduced-motion: reduce) { .toast { animation: none; transition: none; } }
```

Exit is the phase that transitions happily, because the node is already painted.

So `::h/mounting` earns its keep on the attributes that are simply *true during
entry* rather than on the animation itself. An arriving node that should not take
focus or be announced until it has settled wants `:inert` and `:aria-hidden`, in
the same map shape the exit override uses. A class flip is legitimate too, once
you have looked at the paint race and decided you can live with losing it now and
then. What it is not is the recommended way to make something fade in.

One thing to know before you lean on mounting-phase attributes for correctness
rather than for looks. Under SSR, a presence-managed node **hydrates
born-present**, so server HTML never carries `:mounting`-phase attributes at all
([Server-side rendering](10-server-side-rendering.md)). Nothing that arrived with
the page replays an entrance over content the user is already reading, which is
the behaviour you want — but it does mean the first paint of a server-rendered
page is not a moment when `::h/mounting` has been applied.

## Where is `:on-mount`?

There is no `:on-mount` and no `:on-unmount`, and as with `local`, the absence is
deliberate rather than pending. Hicasso took the *attribute* half of Replicant's
mechanism — `::h/mounting` and `::h/unmounting` above — and rejected the callback
half as less data-oriented than a registered behaviour. Presence is the one
mechanism that knows a node arrived or left, and it never dispatches anything
about either, deliberately. And the hook-budget witness holds the tier-1 shapes
to a page with no `useEffect` on it anywhere.

An absence with nowhere to go would be a gap. Four jobs send people looking for
`:on-mount`, and each of them has a home.

**Load the data this screen needs.** The route declares it, not the view. A
route's `:resources` are ensured on entry and its `:on-match` carries activation
events, which is also what closes the click-away race — a fetch kicked off by a
mounting component has nothing to suppress the late reply when the user navigates
away first
([Routing](../../../routing/concepts.md#loaders-declaring-a-pages-data)).

**Run something once at startup.** `:initial-events` — ordinary events, run in
order, seeding app-db *before* first paint rather than a beat after it
([Getting started](01-getting-started.md)).

**Animate an entrance or an exit.** Presence, above. `::h/unmounting` for exit;
an animation on insertion, or `@starting-style`, for enter.

**Drive a real DOM node or a third-party SDK.** The host edge, which is where
[the placement rule](#the-placement-rule) says lifecycle honestly lives. A
callback `:ref` hands you the node on attach and takes your return value as the
cleanup, and a `defhost` component brings whatever hooks it already had — its own
`useEffect` included, running inside a Hicasso tree exactly as it ran outside one
([Interop](05-interop.md)).

The last of those has an open edge, and it is worth naming rather than papering
over. The *data* spelling of a host behaviour — a registered id and a config map,
with the imperative code out of your view, as in
`{:ref [::autosize {:max-rows 8}]}` — is refused today with
`:rf.error/hicasso-ref-vector-reserved`. That value space is reserved, not
designed. Until it lands, write the function.

## Troubleshooting

This table names mechanisms; the one minted id on this surface is named in its
row.

| Symptom | What went wrong | Fix |
|---|---|---|
| Reaching for `useState` to hold "is this open?" | That's semantic state | app-db (`reg-state` or the long form), or CSS if the platform tracks it |
| Searching for `:on-mount`, `componentDidMount`, or a mount `useEffect` | There is none, and the absence is deliberate rather than pending | Name the job: route `:resources` for page data, `:initial-events` for startup, presence for animation, a callback `:ref` or `defhost` at a host edge |
| A dismissed item disappears instantly with no exit animation | The node left with the data | `h/presence` with a `:timeout-ms` at least as long as the exit transition |
| A retained node still takes focus or clicks while fading | The exit override does not carry `:inert` / `:aria-hidden` | Put all three in the `::h/unmounting` map |
| An exit override on a view head raises `:rf.error/hicasso-presence-override-on-a-view` | Presence merges overrides into nodes it can see; a view is opaque to it, and a silently dropped override is the failure class the loud error exists to delete | The view receives `:rf/phase` — branch or style on that |
| Every panel in a list opens at once | The state isn't parameterised by instance — or two instances share one key | Key the path per instance: [Choosing the instance key](#choosing-the-instance-key) |
| A nil or malformed instance key raises `:rf.error/hicasso-state-bad-key` | The key is not a keyword, string, number, or vector of those | Author a stable domain or placement key; nest with `h/child-key` when needed |
| A hook body can't be tested headlessly | Hooks put a body outside headless scope, by design | Move the semantic half to app-db; mount-test the mechanics |
| Hook-order error after a conditional early-return | React's rules, now yours | Hooks at the top of the body, unconditionally |
| app-db is full of `:ui` noise | Expected — it is the honest home | Namespace the keys and exclude the tier from persistence by convention |

## When app-db is the wrong home

Three cases where it genuinely is.

**The platform already knows.** Hover and focus are CSS. Writing them into app-db is
a re-render per pointer move for a fact the browser was tracking for free.

**High-rate host mechanics.** A drag in flight, a scroll offset, an animation frame
— 60 to 240 updates a second. That is the two-clock envelope: host-local motion off
app-db, semantic commits into it. Drag *ends* in app-db. Drag *moves* do not.

**Values nobody else can see.** A measured pixel offset, an SDK handle, a
composition buffer. Host-private React state at the host edge, and no one outside
that edge needs to know it exists.

## Not settled yet

| Question | Status |
|---|---|
| Vector `:ref` host behaviours (`{:ref [::id opts]}`) | **Reserved, not designed** — refused today with `:rf.error/hicasso-ref-vector-reserved`; write a function ref until a data spelling exists |
| The controls kit (drafts, revisions) | **Post-v0** |
| The overlay top layer that would own open and dismiss | **Post-v0** |
| The spellings | `h/reg-state`, `h/presence`, `h/child-key` and friends are bench-arm names; every spelling on this page is **[unfrozen]** until the API freeze |

# Ephemeral state

> **Pre-implementation draft — Hicasso does not exist yet.** This page describes the
> *designed* surface so it can be read before it is built. Spellings marked
> **[unfrozen]** are placeholders that will change. The whole tree is disposable: it
> is rewritten after the P2 fork ruling, against a real implementation. Normative
> source: [decisions.md](../decisions.md) (HD-001…HD-021).

Is this dropdown open? Is this row hovered? Is this panel expanded?

In Reagent you reach for `r/atom`. In React you reach for `useState`. In Hicasso
there is **no `local`** — no component-local reactive cell, no ratom equivalent, and
no `useState` for application state. Not discouraged. Absent.

That is a strong claim, so here is the evidence it rests on: a census of **85
idiomatic re-frame files**, containing every classic hard case, found **zero
view-local reactive cells**. Not few. Zero. Most of Reagent's local-state demand was
manufactured by machinery — the reaction engine, deref capture, argv memoization —
that Hicasso deletes structurally. And a second reactive system sits at the top of
the anti-regression fence in the [charter](../charter.md): a view layer that grows
one has failed back into its predecessors.

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

The answer is that **the tax is per-concern, not per-instance.** One parametric
subscription and one named event serve every instance of the widget in the app,
forever:

```clojure
(rf/reg-sub :panel/expanded?
  (fn [db [_ panel-id]] (get-in db [:ui :panel/expanded panel-id] false)))

(rf/reg-event :panel/toggle
  (fn [{:keys [db]} [_ panel-id]]
    {:db (update-in db [:ui :panel/expanded panel-id] not)}))
```

```clojure
;; Read shown in the grouped surface; the collector spelling is
;; (sub [:panel/expanded? id]) inline. See 02-views-and-reads.md.
(defview panel [{:keys [id title]}]
  (let [{:keys [expanded?]} (use-subs {:expanded? [:panel/expanded? id]})]
    [:section
     [:h3 {:on-click [:panel/toggle id]} title]
     (when expanded? [panel-body {:id id}])]))
```

Ten lines, once. A hundred panels, no further cost. And you get the things a local
cell can never give you: the state is in app-db, so it time-travels, it is visible
in Xray, it is isolated per frame, and a test can set it directly without touching a
component.

That last one is worth sitting with. "Open this dropdown in a test" is a `:db`
write, not a click simulation.

## Named events, not a generic setter

Write `[:panel/toggle id]`, never a generic `[:ui/set [:panel/expanded id] true]`.

A named event is a name for what happened, which is the entire premise of the event
log being readable. A generic setter turns your event history into a diff stream and
takes the meaning out of the one place the framework was keeping it for you. This is
also why HD-009 pre-commits that any future sugar mints a **named** setter event and
never a generic `ui/set`.

## What v0 actually gives you

Layer 2 above is mostly a promise. The controls kit that would own drafts and
revisions is post-v0. So is the overlay top layer. In v0, a dismissible dropdown is
CSS if you can manage it, and app-db if you can't.

HD-009 is explicit that this is expected to generate complaints, and equally
explicit about what happens next. If dogfooding shows the residual ceremony
registering, the pre-agreed *response class* is one-declaration sugar — never a
state system:

```clojure
;; SKETCH — v0 ships nothing here, and the shape is unfrozen.
(h/defstate ::open {:default false})
;; would mint: a parametric sub (sub [::open id])
;;             and a NAMED setter event [::open id v]
```

Read that block as an illustration of a *response class*, not a plan of record. Its
concrete shape — including whether a declared app-db tier is involved and what that
tier's frame and persistence scope would be — is **unfrozen until the evidence
exists**. v0 ships nothing here and pre-commits to nothing beyond "sugar, not a state
system."

One peer review argued for a `useState` fence and a pre-designed tier. It was
adopted in part: the tier pre-commitment was withdrawn and there is no lint police.
The no-`local` core stands.

So: a v0 complaint about state ceremony is **expected signal**, not a verdict. It
triggers the sugar iteration, not a kill.

## Troubleshooting

No Hicasso error ids exist yet; this table names mechanisms.

| Symptom | What went wrong | Fix |
|---|---|---|
| Reaching for `useState` to hold "is this open?" | That's semantic state | app-db, or CSS if the platform tracks it |
| Every panel in a list opens at once | The state isn't parameterised by instance | Key the app-db path by the widget's id |
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
| `defstate`'s shape, and whether it ever ships | **Unfrozen by ruling.** HD-009 pre-commits to a response *class*, not a design; v0 ships nothing |
| Whether a declared app-db `:ui` tier exists, and its frame and persistence scope | **Withdrawn from pre-commitment.** The `[:ui …]` keypaths on this page are ordinary app-db keys this guide chose, not a ruled tier |
| The controls kit (drafts, revisions) | **Post-v0** |
| The overlay top layer that would own open and dismiss | **Post-v0** |
| The reusable-widget instance-key convention | **Post-v0**, named as a resolved design debt in HD-009's sugar |
| Where the line falls between "mechanics" and "semantic" in hard cases | **Judgment, by design.** HD-003 makes it a taught rule with no enforcement, and reopens if dogfooding shows it confusing in practice |

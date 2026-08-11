# Getting started

re-frame2 already defines how events update app-db and how subscriptions derive
values. A view adapter decides how those values become React UI. Hicasso is the
adapter for applications that want the view tree to remain ordinary
ClojureScript data.

## What Hicasso is

[Hicasso](glossary.md#hicasso) interprets
[Hiccup](../../../core/glossary.md#hiccup) and produces React
function-component elements. You write vectors for markup, maps for props, and
event vectors in handler attributes. Where a view needs a subscription value,
it calls [`h/sub`](glossary.md#hsub).

```clojure
(h/defview todo-row [{:keys [id]}]
  (let [todo (h/sub [:todo/by-id id])]
    [:li
     [:span (:title todo)]
     [:button {:on-click [:todo/toggle id]} "Toggle"]]))
```

Nothing outside the view layer changes. App-db, event handlers, subscriptions,
effects, and frames use the normal re-frame2 APIs.

Hicasso is not the only supported adapter. Reagent and UIx remain valid choices
that applications can keep using.

## What changes in a Hicasso view

### Markup and common handlers remain data

The button above contains the event vector `[:todo/toggle id]` directly. The
runtime creates the React callback and dispatches that vector when the button
is clicked.

Because the Hiccup tree still contains the event, tests and tools can print,
inspect, and compare it with `=`. You can still use an ordinary function when a
handler needs imperative work or direct access to callback arguments;
[Events as data](03-events-as-data.md) defines that boundary.

### Read subscriptions where they are used

`h/sub` is an ordinary function call inside a Hicasso view. It can appear in a
`let`, conditional, loop, or plain helper. You do not have to subscribe in a
parent simply to pass the value down.

A view created with [`h/defview`](glossary.md#defview) tracks the subscriptions
read during its body. When one changes, that view re-renders. Plain `defn`
helpers do not create a separate re-render boundary; their reads belong to the
surrounding view.

### Controlled fields use ordinary attributes

Most controlled text inputs use `:value` and `:on-input`:

```clojure
[:input {:value    (h/sub [:todo.ui/draft id])
         :on-input [:todo.ui/edit id ::h/value]}]
```

The runtime handles the same-turn update, caret preservation, and IME
composition rules for that path. You should not add a second local atom merely
to keep a controlled field usable. The complete contract, including resets and
failure cases, is in [Controlled inputs](04-controlled-inputs.md).

### Optimise measured hot regions explicitly

Normal screens use interpreted Hiccup. If profiling identifies a genuinely hot
part of the tree, that region can move to native React or UIx while staying on
the same frame and app-db. There is no `:fast` interpretation mode and no
second meaning for `[...]`.

[Performance](18-performance.md) defines the measurement method and
[Native tier](10-native-tier.md) defines the crossing.

## Hicasso, Reagent, and UIx

**Reagent** remains a sensible choice for an existing application whose view
layer already works. Hicasso will look familiar because both use Hiccup, but
the state and component models differ: Hicasso does not use reaction-local
state or Form-2 components. The migration guide explains the mechanical and
behavioural differences.

**UIx** is usually the better choice when React itself organises the view
layer: hooks are common, a React design system dominates the tree, and the
team thinks in React component lifecycles. Hicasso can still host foreign React
through [`h/defhost`](09-interop.md), and a measured region can use the native
tier, but it does not try to replace a React-first authoring model.

Choose Hicasso when the application is primarily a re-frame2 application and
you want markup, reads, and ordinary interactions to retain the same
inspectable data model.

## Costs and limits

Interpreting Hiccup has a runtime cost. Cold mount can be slower than a
hand-written UIx equivalent, and each Hicasso view pays a small fixed cost for
tracked reads and its re-render boundary. Measure before moving code: the
native tier is intended for the small part of a real screen that profiling
identifies, not as the default authoring style.

Hicasso also does not provide a second application-visible reactive store
inside the view layer. State that other views, tests, tools, routing, or SSR
must observe belongs in app-db. The limited cases for DOM-owned or local UI
state are covered in [Ephemeral state](11-ephemeral-state.md).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Calling a `defview` as `(todo-row {:id 7})` refuses and names the view | A Hicasso view is a React/Hiccup head, not an inline function | Mount it as `[todo-row {:id 7}]`; use a plain `defn` for an inline helper |
| A plain helper written as `[row-icon props]` raises `:rf.error/hicasso-bad-head` | A plain function appeared in Hiccup head position | Call it as `(row-icon props)`, or define it with `h/defview` when it needs its own re-render boundary |
| `h/sub` raises `:rf.error/hicasso-sub-outside-render` | The read ran outside the direct synchronous execution of a Hicasso view | Read inside the view body and pass or close over the realised value |
| An event vector raises `:rf.error/hicasso-intent-outside-boundary` | The intent was lowered without a view/frame boundary | Keep it in Hiccup produced by a view, or use `h/event` at an explicit foreign callback edge |
| A controlled field drops characters or moves the caret | The write path became asynchronous, or the field left Hicasso's controlled path | Dispatch the edit synchronously and follow [Controlled inputs](04-controlled-inputs.md) |

## When not to use Hicasso

Stay with **Reagent** when migration cost is the dominant fact and the existing
application is healthy.

Choose **UIx** when hooks and React component libraries are the product's
normal language rather than isolated integrations.

Use Hicasso when data-first views are the normal case and foreign React is a
boundary you can name. It is not intended to be the best pure-React
ClojureScript library.

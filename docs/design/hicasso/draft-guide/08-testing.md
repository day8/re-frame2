# Testing

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Behaviour matches the experimental arm under `implementation/freehand/test/re_frame/bench/hicasso/`.

Two ways to test a Hicasso view:

1. **Headless** — structural render to hiccup data, assert with `=`. Fast, JVM-friendly. **Not built yet** — the sketch below is the intended shape, not a call you can make today.
2. **Mounted** — real browser, real React. Covers hooks, foreign components, and anything at a host edge.

**No fake hook dispatcher, ever.** A stubbed dispatcher passes tests that real React would fail — abandoned renders, StrictMode double-invoke, effect ordering. Better a loud boundary you can see than a green suite you can't trust. Split instead: keep the semantic half in a hook-free body headless can assert, and mount-test the mechanics once.

What you *can* assert today without a headless render: **intent vectors with `=`**. `{:on-click [:todo/toggle 7]}` is data. The experimental tests already assert intents that way — prevented intents, route-link click decisions, presence exit rendering — with no browser and no clock.

## Headless (sketch — not built)

A structural render returns the view's hiccup tree as data. No DOM, no React, no browser. Nothing in the tree implements this yet; read the block as the designed shape:

```clojure
;; SKETCH — h/render does not exist, and its spelling is [unfrozen] besides.
;; Intended semantics, so the shape is visible.
(deftest todo-row-renders-title
  (let [tree (h/render [todo-row {:id 7}]
                       {:subs {[:todo/by-id 7]      {:title "Buy milk"}
                               [:todo.ui/editing? 7] false}})]
    (is (= [:li
            [:span "Buy milk"]
            [:button {:on-click [:todo/toggle 7]} "✓"]
            nil]
           tree))))
```

Two halves:

**Sub reads overridable through a pure read resolver.** Hand the render a map of query vector → value; the body reads from it. No frame, no app-db, no registration — just the values the view is a function of. This half is the headless path's, and it does not exist yet.

**Intent vectors assertable by equality.** `{:on-click [:todo/toggle 7]}` is data. You can `=` it. The alternative is asserting that a node holds *some function* which, when called, dispatches something — which is why codebases render to a DOM and click things just to find out what a button was going to do. That property is real today; only the full structural render is waiting.

Make the tree data and most of the `data-testid` scaffolding that only existed because the tree wasn't inspectable stops earning its place.

### Where headless stops

**Hook-free tier-1 bodies.** That is the scope, exactly.

If a body calls a React hook — a callback ref for measurement, a `useEffect` for an SDK, anything at a host edge — headless is out. If a body renders a foreign component through [`defhost`](05-interop.md) (or the `[:>]` escape, once it is built), the foreign region is out.

## Mounted (real browser)

What exists today is the experimental test fixture under `implementation/freehand/test/re_frame/bench/hicasso/` — not a product-named API. It covers root lifecycle, a synchronous dispatch path, and residue assertions **[unfrozen — no product name yet]**.

It flushes rather than using `act`: `act` diverts React's work to a queue that is not the browser's, which is right for an effect-ordering test and wrong when the assertion reads the page. After a dispatch, the next line sees the DOM the user would have seen.

Standing assertion on every mounted test: **zero leaked subscription ref-counts after teardown.** Related: an unchanged hot read performs no new attach or release — a re-render that reads the same query should show no churn.

Reach for a mounted test when you need:

- a real error boundary catching a real throw ([When a view throws](09-when-a-view-throws.md));
- caret, selection, or IME behaviour ([Controlled inputs](04-controlled-inputs.md));
- StrictMode, abandoned first mount, root teardown, or an HMR body swap;
- keyed insert, delete, and reorder against real DOM nodes;
- a foreign component's hooks, context, or refs.

## Testing events and subscriptions

Nothing changes. Events are functions from coeffects to an effects map, and subscriptions are functions of app-db — both testable without any view layer at all, in Hicasso exactly as under Reagent or UIx.

The view-layer question is only ever "what tree did this body produce, given these reads?" — which is the headless path's whole job.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Headless render throws on a body with hooks | Out of scope by design | Mount-test it, or split the semantic half into a hook-free body |
| A sub read returns `nil` in a headless test | The query wasn't in the resolver map | Sub-key identity is `(query-id, args)` under value equality — check the args match exactly |
| Assertion fails on a `nil` in the tree | A `when` produced `nil`, which renders nothing but is still in the data | Assert the `nil`, or filter before comparing |
| Ref-count leak after teardown | A subscription outlived its root | A runtime bug — not a test you tune around |
| A test passes headless and fails mounted | Real React does things a data render doesn't — effect order, double invoke, commit timing | The mounted result is the truth |
| Caret test can't be written headlessly | Correct — caret is a browser fact | Browser witnesses, e.g. the 100-cell editing grid |

## When not to test through the view

If the assertion is about *state*, test the event handler. If it is about *derived values*, test the subscription. A view test that dispatches an event and then asserts on app-db is testing three things and will fail for reasons that have nothing to do with the view.

Headless view tests answer one question — did this body produce the right tree from these reads — and they are excellent at it precisely because that is all they do.

## Not settled yet

| Question | Status |
|---|---|
| Headless render name and signature | Not built. Intended shape: structural render as data, sub reads overridable; `h/render` and `{:subs …}` above are this guide's sketch **[unfrozen]** |
| Read resolver shape | Open. A map is the obvious form; not fixed |
| Mounted facade name and API | Open. Experimental fixture only; flushes rather than using `act` |
| One boundary vs a subtree | Open. Whether child boundaries render through or return as unexpanded nodes changes how every test is written |
| Invoking intent vectors headlessly | Open. Assertable by `=` today; whether headless can fire one and observe the dispatch is unstated |
| Registry / manifest (views enumerable with schemas, docs, source coordinates) | Not shipped yet |
| Explain-render tooling | Not shipped yet |

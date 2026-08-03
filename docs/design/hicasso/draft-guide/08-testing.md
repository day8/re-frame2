# Testing

> **Draft ahead of the product artefact.** This page teaches the ruled surface —
> [decisions.md](../decisions.md) (HD-001…HD-028) — and spellings marked
> **[unfrozen]** stay provisional until the API freeze. **Read this page with one
> extra caution.** The mounted door exists: the bench arm's suites under
> `implementation/freehand/test/re_frame/bench/hicasso/` are it. **The headless
> door is ruled and not built** — no structural render function exists anywhere in
> the tree, and the one below is this guide's illustration of HD-021's semantics
> rather than a call you can make today. What is real now is the property the door
> rests on, and this page says which is which.

There are two doors into a Hicasso view under test, and knowing which one you are
allowed through is most of the skill.

The headless door is fast, runs on the JVM, and asserts hiccup with `=`. It covers
hook-free tier-1 bodies. Everything else — hooks, foreign components, anything at a
host edge — goes through the mounted door, in a real browser.

That boundary is a ruling, not a limitation of the current implementation. HD-021
says it in four words: **no fake hook dispatcher, ever.**

## The headless door

A structural render returns the view's hiccup tree as data. No DOM, no React, no
browser. **Nothing implements this yet** — HD-021 rules the semantics and no
function anywhere in the tree performs one, so read the block below as the shape
that is ruled rather than a test you can write this afternoon:

```clojure
;; SKETCH — h/render does not exist, and its spelling is [unfrozen] besides.
;; HD-021's ruled semantics, spelled by this guide so the shape is visible.
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

Two things would be doing the work there, and only one of them is waiting on the
door.

**Sub reads are overridable through a pure read resolver.** You hand the render a
map of query vector to value, and the body reads from it. No frame, no app-db, no
registration — just the values the view is a function of. `sub` is an ordinary
call ([Views and reads](02-views-and-reads.md)), and the resolver answers it
wherever the body makes it. This half is entirely the door's, and it is the half
that does not exist yet.

**Intent vectors are assertable by equality.** `{:on-click [:todo/toggle 7]}` is
data. You can `=` it. Compare that with the alternative — asserting that a node
holds *some function* which, when called, dispatches something — which is why
codebases end up rendering to a DOM and clicking things just to find out what a
button was going to do. This is not aspiration: the bench arm's own tests already
work this way — a prevented intent, a route-link's whole click decision, and a
presence child's exit rendering are each asserted by `=`, with no browser and no
clock (`front/intent_cljs_test`, `front/route_link_cljs_test`,
`front/presence_cljs_test`).

This is what deletes the retail. The census counted **364 `data-testid` attributes**
across the corpus, most of them scaffolding for browser tests that only existed
because the tree wasn't inspectable. Make the tree data and most of them stop
earning their place.

## Where headless stops

**Hook-free tier-1 bodies.** That is the scope, exactly.

If a body calls a React hook — a callback ref for measurement, a `useEffect` for an
SDK, anything at a host edge — headless is out. If a body renders a foreign
component through [`defhost`](05-interop.md) (or the `[:>]` escape, once it is
built), the foreign region is out.

The temptation is obvious: stub the hook dispatcher, get the whole tree back, keep
the fast tests. HD-021 forecloses it. A fake dispatcher passes tests that a real
React would fail, and the bugs it hides are exactly the ones — abandoned renders,
StrictMode double-invoke, effect ordering — that you needed a test for. Better a
loud boundary you can see than a green suite you can't trust.

The practical move is to split. Keep the semantic half of the component in a
hook-free body that headless can assert, and put the mechanics in a small host-edge
component you mount-test once.

## The mounted door

A shared browser facade covers root lifecycle, a synchronous dispatch door, and
residue assertions **[unfrozen — the facade has no name in the record]**. Note what
it deliberately is not built on: `act` diverts React's work to a queue that is not
the browser's, which is right for an effect-ordering test and wrong for a witness
that reads the page. The bench arm's fixture flushes instead, so an assertion on
the line after a dispatch reads the DOM the user would have seen.

One assertion is standing, on every mounted test: **zero leaked subscription
ref-counts after teardown.** A related invariant rides with it — an unchanged hot
read performs no new attach or release, so a re-render that reads the same query
should show no churn at all.

Reach for a mounted test when you need:

- a real error boundary catching a real throw
  ([When a view throws](09-when-a-view-throws.md));
- caret, selection, or IME behaviour, which only a real browser can prove
  ([Controlled inputs](04-controlled-inputs.md));
- StrictMode, abandoned first mount, root teardown, or an HMR body swap;
- keyed insert, delete, and reorder against real DOM nodes;
- a foreign component's hooks, context, or refs.

## Testing events and subscriptions

Nothing changes. Events are functions from coeffects to an effects map, and
subscriptions are functions of app-db — both testable without any view layer at all,
in Hicasso exactly as under Reagent or UIx.

The view-layer testing question is only ever "what tree did this body produce, given
these reads?" — which is the headless door's whole job.

## Troubleshooting

This table names mechanisms rather than error ids.

| Symptom | What went wrong | Fix |
|---|---|---|
| Headless render throws on a body with hooks | Out of scope, by ruling | Mount-test it, or split the semantic half into a hook-free body |
| A sub read returns `nil` in a headless test | The query wasn't in the resolver map | Sub-key identity is `(query-id, args)` under value equality — check the args match exactly |
| Assertion fails on a `nil` in the tree | A `when` produced `nil`, which renders nothing but is still in the data | Assert the `nil`, or filter before comparing |
| Ref-count leak after teardown | A subscription outlived its root | A runtime bug — this is the standing assertion, not a test you tune |
| A test passes headless and fails mounted | Real React does things a data render doesn't — effect order, double invoke, commit timing | The mounted result is the truth |
| Caret test can't be written headlessly | Correct — caret is a browser fact | 100-cell grid witnesses, in a browser |

## When not to test through the view

If the assertion is about *state*, test the event handler. If it is about *derived
values*, test the subscription. A view test that dispatches an event and then
asserts on app-db is testing three things and will fail for reasons that have
nothing to do with the view.

Headless view tests answer one question — did this body produce the right tree from
these reads — and they are excellent at it precisely because that is all they do.

## Not settled yet

| Question | Status |
|---|---|
| The headless render function's name and signature | **Not addressed, and not built.** HD-021 pins the semantics — structural render as data, sub reads overridable — and names nothing; nothing in the tree implements one. `h/render` and the `{:subs …}` option above are this guide's invention |
| The read resolver's shape | **Not addressed.** "A pure read resolver" is the whole of the record; a map is the obvious form, and it is a guess |
| The mounted facade's name and API | **Not addressed.** The bench arm's fixture is the only one that exists, it is not a product surface, and it already diverges from the description the record sketched it with — it flushes rather than using `act` |
| Whether headless renders one boundary or a subtree | **Not addressed.** The example above renders one; whether child boundaries are rendered through or returned as unexpanded nodes is unstated, and it changes how every test is written |
| How intent vectors are *invoked* in a headless test | **Not addressed.** They can be asserted by equality; whether the door lets you fire one and observe the dispatch is unstated |
| The registry / manifest surface — views enumerable with schemas, docs, source coordinates | **Post-v0.** Named as the AI-ergonomics door |
| Explain-render tooling | **Post-v0.** HD-005 ships a ~3-line nil-checked evidence sink on the index so it can attach later at zero detached cost; no evidence subsystem ships in v0 |

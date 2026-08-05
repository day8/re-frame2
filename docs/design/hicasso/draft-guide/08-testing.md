# Testing

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

What you can assert **today** without a browser is data: intent vectors, prevent
heads, a `route-link`'s click decision, a presence child's exit attrs. What you
still need a browser for is hooks, foreign components, caret/IME, and real React
lifecycle. A full headless *render* of a view body is designed but **not built**
— see the sketch under Advanced.

> **No fake hook dispatcher, ever.** A stubbed dispatcher passes tests that real
> React would fail — abandoned renders, StrictMode double-invoke, effect
> ordering. Split instead: keep the semantic half as data you can `=`, and
> mount-test the mechanics once.

## Assert as data (today)

A helper that takes its data as arguments and reads nothing is an ordinary
function. Call it, and assert on what it returned:

```clojure
(defn todo-row [{:keys [id title]}]
  [:li [:span title]
       [:button {:on-click [:todo/toggle id]} "✓"]])

(deftest todo-row-carries-the-intent
  (is (= [:li [:span "Buy milk"]
              [:button {:on-click [:todo/toggle 7]} "✓"]]
         (todo-row {:id 7 :title "Buy milk"}))))
```

The same move reaches the other data spellings, because each is a value sitting in
the attrs map the helper returned: a `::h/prevent` head, a `route-link`'s closed
navigate vector, a presence child's exit attrs. `=` is the whole assertion.

A `defview` body is not callable this way. It is a React function component, and a
body that calls `sub` needs a render extent to read in — which is what the unbuilt
headless render under Advanced is for.

Events and subscriptions stay ordinary: handlers are functions of coeffects →
effects map, subs are functions of app-db — test those without any view layer.

## Mounted (real browser)

What exists today is the experimental test fixture under
`implementation/freehand/test/re_frame/bench/hicasso/` — not a product-named API.
It covers root lifecycle, a synchronous dispatch path, and residue assertions
**[unfrozen — no product name yet]**.

It flushes rather than using `act`: `act` diverts React's work to a queue that is
not the browser's, which is right for an effect-ordering test and wrong when the
assertion reads the page. After a dispatch, the next line sees the DOM the user
would have seen.

Every mounted test asserts **zero leaked subscription ref-counts after
teardown.** Related: an unchanged hot read performs no new attach or release — a
re-render that reads the same query should show no churn.

Reach for a mounted test when you need:

- a real error boundary catching a real throw ([When a view throws](09-when-a-view-throws.md));
- caret, selection, or IME behaviour ([Controlled inputs](04-controlled-inputs.md));
- StrictMode, abandoned first mount, root teardown, or an HMR body swap;
- keyed insert, delete, and reorder against real DOM nodes;
- a foreign component's hooks, context, or refs.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| You need a full view tree as data and nothing exists to build it | Headless render is not built yet | Assert the intent / prevent / navigate / presence attrs you *can* see as data; mount-test the rest |
| A sub read returns `nil` in a future headless test | The query wasn't in the resolver map | Sub-key identity is `(query-id, args)` under value equality — check the args match exactly |
| Assertion fails on a `nil` in a hiccup tree | A `when` produced `nil`, which renders nothing but is still in the data | Assert the `nil`, or filter before comparing |
| Ref-count leak after teardown | A subscription outlived its root | A runtime bug — not a test you tune around |
| A data-level assert passes and the mounted test fails | Real React does things data does not — effect order, double invoke, commit timing | The mounted result is the truth for lifecycle |
| Caret / IME test can't be written as data | Correct — caret is a browser fact | Mounted browser tests |

## When not to test through the view

If the assertion is about *state*, test the event handler. If it is about
*derived values*, test the subscription. A view test that dispatches an event and
then asserts on app-db is testing three things and will fail for reasons that have
nothing to do with the view.

## Advanced

### Full headless render (not built)

A structural render would return the view's hiccup tree as data — no DOM, no
React — with sub reads overridable through a pure resolver. Nothing implements
that yet; the block below is the intended shape only:

```clojure
;; SKETCH — h/render does not exist; spelling [unfrozen].
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

**Scope when it lands:** hook-free tier-1 bodies only. Hooks, `defhost`, and host
edges stay mounted tests. Do not invent a fake hook dispatcher to widen the
scope.

## Not settled yet

| Question | Status |
|---|---|
| Headless render name and signature | Not built. Sketch above is this guide's invention **[unfrozen]** |
| Read resolver shape | Open. A map is the obvious form; not fixed |
| Mounted facade name and API | Open. Experimental fixture only; flushes rather than using `act` |
| One boundary vs a subtree | Open. Whether child boundaries render through or return as unexpanded nodes changes how every test is written |
| Invoking intent vectors headlessly | Open. Assertable by `=` today; whether headless can fire one and observe the dispatch is unstated |
| Registry / manifest (views enumerable with schemas, docs, source coordinates) | Not shipped yet |
| Explain-render tooling | Not shipped yet |

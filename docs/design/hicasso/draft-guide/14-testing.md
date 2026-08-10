# Testing

You have a feature to test. You must decide which tests to write, and how
much machinery each test needs. In a Hicasso app, the answer is a ladder of
five rungs, and most of the ladder runs without a browser. Handlers and
subscriptions are plain functions. Markup helpers return plain data. View
bodies run under a semantic harness with no DOM and no React — other view
layers make you mount them. The browser is the top rung. Keep it for the
facts that only a browser knows.

> **Test at the lowest rung that can prove the claim. Know which equality
> that rung proves.**

The test kit is one namespace, `re-frame.hicasso.test`. It is a supported
product surface, not a loose collection of utilities. This page aliases it
as `ht`.

## The ladder

| Rung | What it proves | Mechanism |
|---|---|---|
| **L0** | event handlers, subscriptions, state transitions | pure function calls |
| **L1** | intents, prevent and navigate decisions, codecs, control and revision laws, native-form expansion | pure data, property, and macro-expansion tests |
| **L2** | registered hook-free view bodies | the semantic harness — `ht/tree` under injected read fixtures |
| **L3** | React lifecycle, hooks, context, refs, errors, hosts | the mounted facade, with Testing Library and user-event |
| **L4** | IME, caret, focus, layout, hydration, performance | Chromium, Firefox, and WebKit |

Each rung proves a different equality. L1 proves authored data. L2 proves
semantic-tree equality. L3 proves real DOM and lifecycle. L4 proves engine
behaviour. A green test on one rung makes no claim about the rung above it.
An L2 tree says nothing about React lifecycle. A normalized tree is never a
proxy for hydration bytes. This honesty is what makes the cheap rungs
trustworthy: they refuse to make claims they cannot prove.

## The feature under test

One small feature serves as the example for this page: a todo row with a
toggle.

```clojure
(ns todo.events
  (:require [re-frame.core :as rf]))

(rf/reg-event :todo/seed
  (fn [_cofx [_ todos]]
    {:db {:todos (into {} (map (juxt :id identity)) todos)}}))

(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))
```

```clojure
(ns todo.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :todo/by-id
  (fn [db [_ id]] (get-in db [:todos id])))
```

```clojure
(ns todo.views
  (:require [re-frame.hicasso :as h]))

(h/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (h/sub [:todo/by-id id])]
    [:li
     [:label
      [:input {:type      :checkbox
               :checked   (boolean done?)
               :on-change [:todo/toggle id]}]
      title]]))
```

## L0 — handlers and subscriptions are plain functions

Nothing here is specific to Hicasso, and that is the point: the logic layer
tests the same under every view layer. Take the handler from the registrar.
Call it with literal values. Check the map it returns:

```clojure
(ns todo.events-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [todo.events]))   ;; loading the ns registers the handlers

(deftest toggle-flips-done
  (let [handler (:handler-fn (rf/handler-meta :event :todo/toggle))
        result  (handler {:db {:todos {7 {:id 7 :title "Buy milk" :done? false}}}}
                         [:todo/toggle 7])]
    (is (true? (get-in result [:db :todos 7 :done?])))))
```

A subscription is a pure function over app-db values. Call it with the data
and check the answer:

```clojure
(deftest by-id-reads-one-todo
  (is (= {:id 7 :title "Buy milk" :done? false}
         (rf/compute-sub [:todo/by-id 7]
                         {:todos {7 {:id 7 :title "Buy milk" :done? false}}}))))
```

Both tests run on the JVM: no browser, no React, no adapter. Most of your
logic lives here, so most of your tests belong here.

## L1 — helpers and intents are plain data

A helper that takes its data as arguments, and reads nothing, is an ordinary
function. Call it. The whole assertion is `=`:

```clojure
(defn priority-badge [level]
  [:span.badge {:data-level (name level)} (name level)])

(deftest badge-is-the-data-it-claims
  (is (= [:span.badge {:data-level "high"} "high"]
         (priority-badge :high))))
```

The same method covers every data spelling in the product, because each
spelling is a value in an attribute map that some function returned: an
intent vector, a `::h/prevent` head, a route link's navigate decision. You
never mount and click to learn what a button means. The meaning is a value
that you compare.

L1 is also the rung for property tests: laws that hold for all inputs, such
as a codec round-trip or the owned-wins attribute merge. When you own a
native island, `n/$` macro-expansion tests belong here too
([Native tier](10-native-tier.md)). The canonical-DOM comparator,
`ht/canonical-dom`, also lives at this rung. It is a pure serializer that
you apply to mounted nodes when a claim compares two pages (mechanics under
Advanced).

## L2 — the semantic harness

A `defview` body is not directly callable. It needs a render extent to read
in, and a direct call refuses with a source-located recovery. The harness
supplies that extent without React. `ht/tree` invokes a registered hook-free
body under a discardable read resolver — your fixtures — and returns a
versioned semantic tree. Four small helpers assert on the tree: `ht/find`,
`ht/attrs`, `ht/text`, and `ht/intents`.

```clojure
(ns todo.views-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.hicasso.test :as ht]
            [todo.views :as views]))

(deftest row-renders-title-and-carries-the-toggle
  (let [tree (ht/tree [views/todo-row {:id 7}]
                      {:subs {[:todo/by-id 7]
                              {:id 7 :title "Buy milk" :done? false}}})]
    (is (= "Buy milk" (ht/text (ht/find tree :label))))
    (is (= [:todo/toggle 7]
           (:on-change (ht/attrs (ht/find tree :input)))))
    (is (= [[:todo/toggle 7]] (ht/intents tree)))))
```

The fixtures replace the whole subscription layer. The body's `h/sub` calls
resolve against the map. The harness never touches the real cache, and it
discards the resolver afterwards. Fixture identity is the same as sub
identity — `(query-id, args)` under value equality — so `[:todo/by-id 7]`
and `[:todo/by-id "7"]` are different fixtures.

Registered hook-free children expand in place. A list view whose rows are
`[todo-row {:key id :id id}]` produces a tree with the rows expanded under
the same fixtures. One `ht/tree` call can therefore cover a whole owned
subtree.

**The harness is honest about what it cannot know.** L2 is an assertion
model, not a renderer. It refuses to fake the facts it does not have:

- A body (or expanded child) that reaches a **hook**, a **raw React
  element**, an **`n/$` result**, or a **`defhost` crossing** refuses with a
  structured, source-located complaint that points at L3. React facts belong
  to React.
- A read with **no fixture** refuses and names the query. The harness never
  replaces the read with `nil`, and never satisfies it with a fake.

!!! warning "No fake hook dispatcher, ever"

    A stubbed React dispatcher passes tests that real React would fail —
    abandoned renders, StrictMode double-invoke, effect ordering. The kit
    does not supply one, and you must not build one. Split the view instead:
    keep the semantic half as data you can compare with `=`, and mount-test
    the mechanics once.

## L3 — the mounted facade

When the claim is about React or the DOM — lifecycle, hooks, a real error
boundary, real nodes — mount the view. The facade gives every test an
isolated frame (its own app-db, queue, and subscription cache), a real root,
and a residue guarantee:

| Call | What it does |
|---|---|
| `ht/mount!` | mounts a view under a fresh isolated frame; records the residue baseline first; returns a handle |
| `ht/hydrate!` | mounts by adopting server bytes ([SSR and hydration](17-ssr-and-hydration.md)) |
| `ht/rerender!` | renders a new element into the same root — for props-change tests |
| `ht/dispatch-and-settle!` | dispatches into the mount's frame and returns once Hicasso and React are quiescent |
| `ht/settle!` | waits for quiescence after outside stimulation — a user-event pointer or keyboard sequence |
| `ht/unmount!` | tears the root down |
| `ht/assert-clean!` | after unmount: compares exact post-quiescence residue with the pre-mount baseline, then resets |

```clojure
(ns todo.views-mounted-test
  (:require [clojure.test :refer [deftest is]]
            ["@testing-library/dom" :as tl]
            [re-frame.hicasso.test :as ht]
            [todo.events]
            [todo.subs]
            [todo.views :as views]))

(deftest toggle-reaches-the-real-dom
  (let [m (ht/mount! [views/todo-row {:id 7}]
                     {:initial-events
                      [[:todo/seed [{:id 7 :title "Buy milk" :done? false}]]]})]
    (try
      (is (some? (tl/getByText (:container m) "Buy milk")))
      (ht/dispatch-and-settle! m [:todo/toggle 7])
      (is (true? (.-checked (tl/getByRole (:container m) "checkbox"))))
      (finally
        (ht/unmount! m)
        (ht/assert-clean! m)))))
```

The facade brings no selector language of its own, because that job already
has an owner. `(:container m)` is a real DOM node, so Testing Library
queries and user-event sequences work unchanged. After a user-event
sequence, call `(ht/settle! m)` before you assert.

!!! note "Settled, not `act`"

    `dispatch-and-settle!` flushes; it does not divert through React's
    `act`. `act` queues React's work on a scheduler that is not the
    browser's. That is correct for an effect-ordering test, and wrong when
    the assertion reads the page. After the call returns, the next line sees
    the DOM that the user would have seen.

Use L3 when the claim needs it:

- a real error boundary that catches a real throw ([Errors](16-errors.md));
- StrictMode double-invoke, and renders that React abandoned;
- keyed insert, delete, and reorder against real nodes;
- a foreign component's hooks, context, or refs ([Interop](09-interop.md));
- Activity hide and reveal, which releases and re-establishes
  subscriptions.

The residue rule: **teardown residue is zero.** `assert-clean!` compares
what remains after quiescence with what existed before the mount. A
surviving subscription handle, listener, or scheduled task fails the test.
That failure is a bug to fix — often a foreign host retains a callback. It
is never a tolerance to raise.

## L4 — real browsers

Some facts exist only inside a browser engine: IME composition, caret and
selection movement, focus traversal, layout and scroll geometry, hydration
against real server bytes, and the performance budgets. For those facts, the
witness is Chromium, Firefox, and WebKit. No simulation stands in.
Controlled-input behaviour under composition is the canonical case
([Controlled inputs](04-controlled-inputs.md)). Budgets and their
measurement discipline live in [Performance](18-performance.md).

One more entry completes the kit: `ht/shadow!`, the migration harness. It is
a dev-only dual mount. It drives a ported view and its Reagent original with
one script, and it diffs canonical DOM and intent streams at every
checkpoint. [Migrating from Reagent](19-migration-from-reagent.md) owns it.

## The sabotage twin

An assertion that quantifies over a collection can pass because the
collection was empty by accident — `every?` over nothing is vacuously true.
Two habits close that hole. First, pin the population with a count. Second,
give an important test a **sabotage twin**: break the input deliberately and
confirm that the measurement moves. A moved measurement proves the
instrument can fail.

```clojure
;; in todo.views
(h/defview todo-list [_]
  [:ul
   (for [id (h/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])

;; in the test namespace
(def three-todos
  {[:todo/visible-ids] [1 2 3]
   [:todo/by-id 1]     {:id 1 :title "One"   :done? false}
   [:todo/by-id 2]     {:id 2 :title "Two"   :done? true}
   [:todo/by-id 3]     {:id 3 :title "Three" :done? false}})

(deftest every-row-carries-a-toggle
  (let [tree (ht/tree [views/todo-list {}] {:subs three-todos})]
    (is (= 3 (count (filter #(= :todo/toggle (first %))
                            (ht/intents tree)))))))

(deftest every-row-carries-a-toggle--sabotage-twin
  ;; Prove the gate can fail: empty the list and the population collapses.
  (let [tree (ht/tree [views/todo-list {}]
                      {:subs (assoc three-todos [:todo/visible-ids] [])})]
    (is (empty? (ht/intents tree)))))
```

The kit's own release gates carry the same discipline: every important
witness has a negative or sabotage control. A test that cannot fail
verifies nothing.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| `ht/tree` refuses, pointing at L3 | The body (or an expanded child) reaches a hook, raw React element, `n/$` result, or `defhost` crossing | Honest opacity, working as designed. Mount that view at L3; if you want L2 coverage for the rest, split the hook-free part into its own view or helper |
| `ht/tree` refuses, naming a query | A read the body made has no fixture | Add it. Fixture identity is `(query-id, args)` under value equality — the args must match exactly |
| `:rf.error/hicasso-sub-outside-render` in a plain test | A reading helper was called with no render extent (a direct `defview` call refuses at the call itself, naming the view) | L2 supplies the extent; L0 targets handlers and subs, not bodies |
| `:rf.error/hicasso-deferred-read-at-boundary` | A stored closure, stashed lazy seq, or unforced `delay` carried a read past the render | Read during the body and close over the value ([Views and reads](02-views-and-reads.md)) |
| `assert-clean!` fails after unmount | Something outlived the root — a retained subscription, listener, or scheduled task | A bug, not a tolerance: fix the leak. A foreign host retaining a callback is the common culprit ([Interop](09-interop.md)) |
| Data-level test passes, mounted test fails | Real React does things data does not — effect order, StrictMode double-invoke, commit timing | The mounted result is the truth for lifecycle |
| Assertion fails on a `nil` in a tree | A `when` produced `nil` — it renders nothing but is still in the data | Assert the `nil`, or filter before comparing |
| A list test stays green with an empty list | Quantified assertion over an empty population | Pin the count; add the sabotage twin |

## When not to test through the view

If the assertion is about *state*, test the handler. If the assertion is
about *derived values*, test the subscription. A view test that dispatches
an event and then asserts on app-db tests three things at once. It will fail
for reasons that have no relation to the view.

Keep each rung's claim inside its rung. L2 never claims React lifecycle
parity: abandoned renders, effect ordering, and commit timing are L3's job.
A semantic tree never claims hydration-wire parity: server-byte and adoption
claims live in [SSR and hydration](17-ssr-and-hydration.md). Timing claims
are not tests until they follow the measurement discipline in
[Performance](18-performance.md).

## Advanced

### Every witness names its equality

When a test's name or docstring states what the test proves, use these
terms:

| Equality | Rung | The claim |
|---|---|---|
| Authored data | L1 | the function returned this value |
| Semantic tree | L2 | the body, under these reads, means this |
| Intent stream | L2/L3 | these events, in this order, were the interactions |
| Canonical DOM | L3 | two mounts produced the same page |
| React server bytes | server tests | the server emitted exactly these bytes |
| Hydrated behaviour | L4 | the adopted page behaves in a real engine |

No row substitutes for another. The most tempting substitution is the
semantic tree in place of mounted truth. The harness's refusals exist to
prevent exactly that substitution.

### Canonical DOM

`ht/canonical-dom` serializes a live node's subtree with every element's
attribute names sorted. `innerHTML` preserves insertion order, and two
front ends write props in different orders — a comparison of `innerHTML`
compares the serializer, not the page. A comparison of the sorted output
compares the DOM. Use `ht/canonical-dom` whenever the claim is "these two
mounts built the same page": before and after a refactor, a Hicasso view
against its Reagent original during migration
([Migration](19-migration-from-reagent.md)), or an island against its
pre-extraction boundary ([Native tier](10-native-tier.md)).

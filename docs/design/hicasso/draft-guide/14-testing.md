# Testing

You have a feature to test. You need to decide which tests to write, and how
much machinery each test needs.

In a Hicasso app, most of the answer runs without a browser. Handlers and
subscriptions are plain functions. Markup helpers return plain data. View
bodies run under a [semantic harness](glossary.md#semantic-harness) with no
DOM and no React. The browser is the top rung — keep it for facts only a
browser knows.

Test at the lowest rung that can prove the claim, and know which equality that
rung proves.

The [test kit](glossary.md#test-kit) is two namespaces. `re-frame.hicasso.test`
(aliased `ht` below) holds every rung that runs without a browser.
`re-frame.hicasso.test.mounted` (aliased `hm`) is the
[mounted facade](glossary.md#mounted-facade).

## The ladder

| Rung | What it proves | Mechanism |
| --- | --- | --- |
| **L0** | event handlers, subscriptions, state transitions | pure function calls |
| **L1** | intents, prevent and navigate decisions, codecs, control and revision laws, native-form expansion | pure data, property, and macro-expansion tests |
| **L2** | one hook-free view body, as a semantic tree | `ht/tree` under injected read fixtures |
| **L3** | React lifecycle, hooks, context, refs, errors, hosts | mounted facade with Testing Library and user-event |
| **L4** | IME, caret, focus, layout, hydration, performance | Chromium, Firefox, and WebKit |

Each rung proves a different equality. A green L2 test says nothing about
React lifecycle. A semantic tree is never a proxy for hydration bytes. That
honesty is what makes the cheap rungs trustworthy.

## The feature under test

One small feature serves the whole page: a todo row with a toggle.

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

Nothing here is Hicasso-specific. The logic layer tests the same under every
view layer. Take the handler from the registrar, call it with literal values,
check the map it returns:

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

A subscription is a pure function over app-db values:

```clojure
(deftest by-id-reads-one-todo
  (is (= {:id 7 :title "Buy milk" :done? false}
         (rf/compute-sub [:todo/by-id 7]
                         {:todos {7 {:id 7 :title "Buy milk" :done? false}}}))))
```

Both tests run on the JVM: no browser, no React. Most of your logic lives
here, so most of your tests belong here.

## L1 — helpers and intents are plain data

A helper that takes its data as arguments and reads nothing is an ordinary
function. Call it; the whole assertion is `=`:

```clojure
(defn priority-badge [level]
  [:span.badge {:data-level (name level)} (name level)])

(deftest badge-is-the-data-it-claims
  (is (= [:span.badge {:data-level "high"} "high"]
         (priority-badge :high))))
```

The same method covers every data spelling in the product: an
[intent](glossary.md#intent) vector, a [`::h/prevent`](glossary.md#hprevent)
head, a [route link](glossary.md#route-link)'s navigate decision. You never
mount and click to learn what a button means — the meaning is a value you
compare.

L1 is also the rung for property tests (codec round-trip, owned-wins attribute
merge). When you own a [native island](glossary.md#native-island),
[`n/$`](glossary.md#n-dollar) macro-expansion tests belong here
([Native tier](10-native-tier.md)). The canonical-DOM comparator
`ht/canonical-dom` lives at this rung too: a pure serializer applied to
mounted nodes when a claim compares two pages (mechanics under Advanced).

## L2 — the semantic harness

A [`defview`](glossary.md#defview) body is not directly callable. It needs a
render context to read in; a direct call raises at the call and names the
view. The harness supplies that context without React.

`ht/tree` runs one hook-free body under a discardable read resolver — your
fixtures — and returns a versioned semantic tree. Four helpers assert on the
tree: `ht/find`, `ht/attrs`, `ht/text`, and `ht/intents`. `ht/find` takes a
predicate over the tree's node maps — `(:tag %)` names an element,
`(:view-id %)` a child view call.

```clojure
(ns todo.views-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.hicasso.test :as ht]
            [todo.views :as views]))

(deftest row-renders-title-and-carries-the-toggle
  (let [tree (ht/tree [views/todo-row {:id 7}]
                      {:subs {[:todo/by-id 7]
                              {:id 7 :title "Buy milk" :done? false}}})]
    (is (= "Buy milk" (ht/text (ht/find tree #(= :label (:tag %))))))
    (is (= [:todo/toggle 7]
           (:on-change (ht/attrs (ht/find tree #(= :input (:tag %)))))))
    (is (= [[:todo/toggle 7]] (ht/intents tree)))))
```

The head may be the `defview` itself, or the body function it was created
from. Either runs the body as written: no React element, no hook, nothing
mounted or painted.

Fixtures replace the whole subscription layer. The body's
[`h/sub`](glossary.md#hsub) calls resolve against the map. The harness never
touches the real cache, and it discards the resolver afterwards. Fixture
identity is `(query-id, args)` under value equality — `[:todo/by-id 7]` and
`[:todo/by-id "7"]` are different fixtures.

**A child view does not expand.** A nested `[todo-row {:key id :id id}]` is
recorded as the call it is — view id, props, children — and its body does not
run. L2 is one body; a tree spanning two views cannot say which of them a
failure belongs to. Assert a child's props where it is called; render the
child's own form to assert its contents.

**What the harness will not fake:**

- A body that reaches a **hook**, a **raw React element**, an
  [`n/$`](glossary.md#n-dollar) result, or a [`defhost`](glossary.md#defhost)
  crossing raises with a structured, source-located complaint that points at
  L3. React facts belong to React.
- A read with **no fixture** raises and names the query. The harness never
  substitutes `nil` or invents a value.

!!! warning "No fake hook dispatcher, ever"

    A stubbed React dispatcher passes tests that real React would fail —
    abandoned renders, StrictMode double-invoke, effect ordering. The kit does
    not supply one, and you must not build one. Split the view instead: keep
    the semantic half as data you can compare with `=`, and mount-test the
    mechanics once.

## L3 — the mounted facade

When the claim is about React or the DOM — lifecycle, hooks, a real error
boundary, real nodes — mount the view. The facade gives every test an isolated
frame (its own app-db, queue, and subscription cache), a real root, and a
residue guarantee:

| Call | What it does |
| --- | --- |
| `hm/mount!` | mounts a view under a fresh isolated frame; records the residue baseline first; returns a handle |
| `hm/hydrate!` | mounts by adopting server bytes; answers a promise of the handle once adoption has committed ([SSR and hydration](17-ssr-and-hydration.md)) |
| `hm/rerender!` | renders a new element into the same root — for props-change tests |
| `hm/dispatch-and-settle!` | dispatches into the mount's frame and returns once Hicasso and React are quiescent |
| `hm/settle!` | waits for quiescence after outside stimulation — a user-event pointer or keyboard sequence |
| `hm/advance-clock!` | moves this mount's virtual clock forward by `ms` and runs due work; needs `{:clock true}` on the `mount!` or `hydrate!` that made the handle; throws without it |
| `hm/unmount!` | tears the root down |
| `hm/assert-clean!` | after unmount: compares post-quiescence residue with the pre-mount baseline, reports, then resets; answers a promise of the report |

```clojure
(ns todo.views-mounted-test
  (:require [cljs.test :refer [async deftest is]]
            ["@testing-library/dom" :as tl]
            [re-frame.hicasso.test.mounted :as hm]
            [todo.events]
            [todo.subs]
            [todo.views :as views]))

(deftest toggle-reaches-the-real-dom
  (async done
    (let [m (hm/mount! [views/todo-row {:id 7}]
                       {:initial-events
                        [[:todo/seed [{:id 7 :title "Buy milk" :done? false}]]]})]
      (is (some? (tl/getByText (:container m) "Buy milk")))
      (hm/dispatch-and-settle! m [:todo/toggle 7])
      (is (true? (.-checked (tl/getByRole (:container m) "checkbox"))))
      (-> (hm/unmount! m) (hm/assert-clean!) (.then done)))))
```

Every door takes the handle first and returns it, so teardown threads in one
expression. The test is `async` because `assert-clean!` waits for the
runtime's own quiescence before it reads.

The facade brings no selector language. `(:container m)` is a real DOM node,
so Testing Library queries and user-event sequences work unchanged. After a
user-event sequence, call `(hm/settle! m)` before you assert.

!!! note "Settled, not `act`"

    `dispatch-and-settle!` flushes; it does not divert through React's `act`.
    `act` queues React's work on a scheduler that is not the browser's. That
    is correct for an effect-ordering test, and wrong when the assertion reads
    the page. After the call returns, the next line sees the DOM the user
    would have seen.

!!! note "What the clock moves"

    `Date.now` moves with `setTimeout` and `setInterval` because retention is
    a deadline comparison, not a callback: a fake timer whose callback reads
    an unmoved `Date.now` fires on schedule and then decides nothing has
    expired.

    It does not move `requestAnimationFrame` (a paint, not a duration). It
    does not move microtasks or promises. It leaves `performance.now` alone
    (React's scheduler reads it for frame budget). It does not touch the
    `Date` constructor. It cannot reach a `setTimeout` captured before the
    window opened — React's own scheduler keeps the reference it took at
    module load so a flush is still a flush.

    `advance-clock!` is `settle!` for work that had a delay on it.

Use L3 when the claim needs it:

- a real [error boundary](glossary.md#error-boundary) that catches a real
  throw ([Errors](16-errors.md))
- StrictMode double-invoke, and renders React abandoned
- keyed insert, delete, and reorder against real nodes
- a foreign component's hooks, context, or refs ([Interop](09-interop.md))
- Activity hide and reveal, which releases and re-establishes subscriptions

**Teardown residue is zero.** `assert-clean!` compares what remains after
quiescence with what existed before the mount. A surviving subscription
handle, listener, or scheduled task fails the test. That failure is a bug —
often a foreign host retaining a callback — not a tolerance to raise.

## L4 — real browsers

Some facts exist only inside a browser engine: IME composition, caret and
selection movement, focus traversal, layout and scroll geometry, hydration
against real server bytes, and performance budgets. For those, the witness is
Chromium, Firefox, and WebKit. No simulation stands in. Controlled-input
behaviour under composition is the canonical case
([Controlled inputs](04-controlled-inputs.md)). Budgets live in
[Performance](18-performance.md).

One more entry completes the kit: `ht/shadow!`, the migration harness. It is
a dev-only dual mount that drives a ported view and its Reagent original with
one script and diffs [canonical DOM](glossary.md#canonical-dom) and intent
streams at every checkpoint.
[Migrating from Reagent](19-migration-from-reagent.md) owns it.

## The sabotage twin

An assertion that quantifies over a collection can pass because the collection
was empty by accident — `every?` over nothing is vacuously true. Two habits
close that hole. First, pin the population with a count. Second, give an
important test a **[sabotage twin](glossary.md#sabotage-control)**: break the
input on purpose and confirm that the measurement moves. A moved measurement
proves the instrument can fail.

```clojure
;; in todo.views
(h/defview todo-list [_]
  [:ul
   (for [id (h/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])

;; in the test namespace
(deftest every-visible-todo-gets-a-row
  (let [tree (ht/tree [views/todo-list {}]
                      {:subs {[:todo/visible-ids] [1 2 3]}})
        rows (:children tree)]
    (is (= 3 (count rows)))
    (is (= [{:id 1} {:id 2} {:id 3}] (mapv ht/attrs rows)))))

(deftest every-visible-todo-gets-a-row--sabotage-twin
  ;; Prove the gate can fail: empty the list and the population collapses.
  (let [tree (ht/tree [views/todo-list {}]
                      {:subs {[:todo/visible-ids] []}})]
    (is (empty? (:children tree)))))
```

The list view's claim is that it calls one row per visible id with the right
props. What a row then renders is the row's claim, proved by rendering the
row's own form.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `ht/tree` raises, pointing at L3 | Body reaches a hook, raw React element, `n/$` result, or `defhost` crossing | Mount that view at L3; split the hook-free part if you want L2 coverage for the rest |
| `ht/tree` raises, naming a query | A read the body made has no fixture | Add it. Fixture identity is `(query-id, args)` under value equality |
| `ht/tree` raises a `defview` head, pointing at L3 | An `:advanced` build with `goog.DEBUG` false compiled away the body property on the head | Run view tests in a dev build, or pass the body function instead of the `defview` head |
| `:rf.error/hicasso-sub-outside-render` in a plain test | A reading helper was called with no render context | L2 supplies the context; L0 targets handlers and subs, not bodies |
| `:rf.error/hicasso-deferred-read-at-boundary` | A stored closure, lazy seq, or unforced `delay` carried a read past the render | Read during the body and close over the value ([Views and reads](02-views-and-reads.md)) |
| `assert-clean!` fails after unmount | Something outlived the root — retained subscription, listener, or scheduled task | Fix the leak. A foreign host retaining a callback is the common culprit ([Interop](09-interop.md)) |
| Data-level test passes, mounted test fails | Real React does things data does not — effect order, StrictMode double-invoke, commit timing | The mounted result is the truth for lifecycle |
| Assertion fails on a `nil` in a tree | A `when` produced `nil` — it renders nothing but is still in the data | Assert the `nil`, or filter before comparing |
| A list test stays green with an empty list | Quantified assertion over an empty population | Pin the count; add the sabotage twin |

## When not to test through the view

If the assertion is about *state*, test the handler. If the assertion is about
*derived values*, test the subscription. A view test that dispatches an event
and then asserts on app-db tests three things at once, and fails for reasons
unrelated to the view.

Keep each rung's claim inside its rung. L2 never claims React lifecycle
parity. A semantic tree never claims hydration-wire parity
([SSR and hydration](17-ssr-and-hydration.md)). Timing claims are not tests
until they follow the measurement discipline in
[Performance](18-performance.md).

## Advanced

### Every witness names its equality

When a test's name or docstring states what the test proves, use these terms:

| Equality | Rung | The claim |
| --- | --- | --- |
| Authored data | L1 | the function returned this value |
| Semantic tree | L2 | the body, under these reads, means this |
| Intent stream | L2/L3 | these events, in this order, were the interactions |
| Canonical DOM | L3 | two mounts produced the same page |
| React server bytes | server tests | the server emitted exactly these bytes |
| Hydrated behaviour | L4 | the adopted page behaves in a real engine |

No row substitutes for another. The most tempting substitution is the semantic
tree in place of mounted truth. The harness's refusals exist to prevent that.

### Canonical DOM

`ht/canonical-dom` serializes a live node's subtree with every element's
attribute names sorted. `innerHTML` preserves insertion order, and two front
ends write props in different orders — a comparison of `innerHTML` compares
the serializer, not the page. Use `ht/canonical-dom` when the claim is "these
two mounts built the same page": before and after a refactor, a Hicasso view
against its Reagent original during migration
([Migration](19-migration-from-reagent.md)), or an island against its
pre-extraction boundary ([Native tier](10-native-tier.md)).

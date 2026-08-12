# Testing

Choose the cheapest test that can prove the behaviour you care about.

Most Hicasso application logic does not need a browser. Event handlers and
subscriptions are plain functions. Markup helpers return data. A hook-free
view body can run under an injected subscription resolver. Mount React only
when the claim depends on React or the DOM, and use real browsers for facts
that only a browser engine knows.

The test kit is split across two namespaces:

- `re-frame.hicasso.test`, usually aliased `ht`, for browser-free tests;
- `re-frame.hicasso.test.mounted`, usually aliased `hm`, for real React and DOM
  tests.

## The testing ladder

| Level | What it proves | Mechanism |
| --- | --- | --- |
| **L0** | Handler behaviour, subscription output, state transitions | Pure function calls |
| **L1** | Intent values, prevent/navigate decisions, codecs, merge laws, macro expansion | Plain data and property tests |
| **L2** | The semantic output of one hook-free view body | `ht/tree` with injected subscription fixtures |
| **L3** | React lifecycle, hooks, context, refs, hosts, error boundaries, real DOM | Mounted facade with Testing Library and user-event |
| **L4** | IME, caret, focus traversal, layout, hydration, browser performance | Chromium, Firefox, and WebKit |

These levels prove different kinds of equality. A passing semantic-tree test
does not prove React lifecycle behaviour. A mounted DOM test does not prove
cross-browser caret behaviour. Keep each claim at the level that can actually
witness it.

## Example feature

The examples use one todo row:

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
  (fn [db [_ id]]
    (get-in db [:todos id])))
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

## L0: handlers and subscriptions

Event handlers are ordinary functions over coeffects and an event vector. Load
the registration namespace, obtain the handler, call it with literal values,
and assert on the returned effects:

```clojure
(ns todo.events-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [todo.events]))

(deftest toggle-flips-done
  (let [handler (:handler-fn
                 (rf/handler-meta :event :todo/toggle))
        result  (handler
                 {:db {:todos
                       {7 {:id 7
                           :title "Buy milk"
                           :done? false}}}}
                 [:todo/toggle 7])]
    (is (true? (get-in result [:db :todos 7 :done?])))))
```

A subscription can be tested against an app-db value:

```clojure
(deftest by-id-reads-one-todo
  (is (= {:id 7 :title "Buy milk" :done? false}
         (rf/compute-sub
          [:todo/by-id 7]
          {:todos
           {7 {:id 7
               :title "Buy milk"
               :done? false}}}))))
```

Both tests can run on the JVM. Most application behaviour belongs at this
level, so most tests should too.

## L1: helpers, intents, and other data

A helper that takes values and returns Hiccup is a plain function:

```clojure
(defn priority-badge [level]
  [:span.badge
   {:data-level (name level)}
   (name level)])

(deftest badge-is-the-data-it-claims
  (is (= [:span.badge
          {:data-level "high"}
          "high"]
         (priority-badge :high))))
```

Use the same approach for:

- an event intent;
- `[::h/prevent INTENT]`;
- a route link's navigation decision;
- codecs and round trips;
- the owned-wins attribute merge;
- `n/$` macro expansion for native code
  ([The native tier](10-native-tier.md)).

A test does not need to mount and click a button merely to learn which event
vector the button contains.

`ht/canonical-dom` also belongs to this level as a pure comparator applied to
live DOM nodes. It is described under [Advanced](#canonical-dom).

## L2: one view body as a semantic tree

A `defview` cannot be called directly. `ht/tree` supplies a browser-free render
context, resolves `h/sub` calls from a fixture map, and returns a versioned
semantic tree.

```clojure
(ns todo.views-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.hicasso.test :as ht]
            [todo.views :as views]))

(deftest row-renders-title-and-carries-the-toggle
  (let [tree
        (ht/tree
         [views/todo-row {:id 7}]
         {:subs
          {[:todo/by-id 7]
           {:id 7
            :title "Buy milk"
            :done? false}}})]
    (is (= "Buy milk"
           (ht/text
            (ht/find tree #(= :label (:tag %))))))

    (is (= [:todo/toggle 7]
           (:on-change
            (ht/attrs
             (ht/find tree #(= :input (:tag %)))))))

    (is (= [[:todo/toggle 7]]
           (ht/intents tree)))))
```

Useful tree helpers include:

- `ht/find` — find a node using a predicate over node maps;
- `ht/attrs` — return a node's attributes;
- `ht/text` — collect its text;
- `ht/intents` — collect event intents in the tree.

A node's `:tag` identifies an element. `:view-id` identifies a child view call.

The head may be the `defview` or its underlying body function. No React
element is created, and nothing mounts or paints.

### Subscription fixtures

The fixture map replaces the subscription layer for this body. A fixture key
uses `(query-id, args)` under value equality. These are different fixtures:

```clojure
[:todo/by-id 7]
[:todo/by-id "7"]
```

A read without a fixture raises and names the missing query. The harness does
not silently substitute `nil` or call the live subscription cache.

### Child views stay as calls

L2 runs one body. A nested child such as:

```clojure
[todo-row {:key id :id id}]
```

is represented by its view id, props, and children. Its body does not expand.
Assert the child's props at the parent call site, then test the child's own
form separately. Expanding multiple bodies into one semantic tree would make
ownership of a failure unclear.

### L2 refuses React-only behaviour

The harness raises and points to L3 when a body reaches:

- a React hook;
- a raw React element;
- an `n/$` result;
- a `defhost` crossing.

It also raises when a subscription fixture is missing.

!!! warning "Do not build a fake hook dispatcher"
    A fake dispatcher can pass tests that real React fails under abandoned
    renders, StrictMode, or effect ordering. Split the semantic part from the
    React mechanics. Test the data at L2 and mount the mechanics at L3.

## L3: mounted React and DOM

Use the mounted facade when the claim depends on React, hooks, context, refs,
error boundaries, hosts, or real DOM nodes.

Each mount receives its own frame, app-db, queue, subscription cache, React
root, and residue baseline.

| Call | Behaviour |
| --- | --- |
| `hm/mount!` | Mount a view under a fresh isolated frame and return a handle |
| `hm/hydrate!` | Adopt supplied server bytes and return a promise of the handle after hydration commits |
| `hm/rerender!` | Render a new element into the same root |
| `hm/dispatch-and-settle!` | Dispatch into the mount's frame and wait until Hicasso and React are quiescent |
| `hm/settle!` | Wait for quiescence after an external user-event or other stimulation |
| `hm/advance-clock!` | Advance the mount's virtual clock and run due work; requires `{:clock true}` at mount or hydrate |
| `hm/unmount!` | Tear down the root |
| `hm/assert-clean!` | After unmount and quiescence, compare residue with the pre-mount baseline, report, and reset |

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
    (let [m
          (hm/mount!
           [views/todo-row {:id 7}]
           {:initial-events
            [[:todo/seed
              [{:id 7
                :title "Buy milk"
                :done? false}]]]})]
      (is (some? (tl/getByText (:container m) "Buy milk")))

      (hm/dispatch-and-settle! m [:todo/toggle 7])

      (is (true?
           (.-checked
            (tl/getByRole (:container m) "checkbox"))))

      (-> (hm/unmount! m)
          (hm/assert-clean!)
          (.then done)))))
```

The facade does not add a selector language. `(:container m)` is a real DOM
node, so use Testing Library and user-event normally. After a user-event
sequence, call `(hm/settle! m)` before asserting.

Mounted operations take the handle first and return it where chaining is
useful. `assert-clean!` is asynchronous because it waits for runtime
quiescence before checking residue.

!!! note "Settled DOM, not a generic `act` wrapper"
    `dispatch-and-settle!` flushes work until the DOM reflects what a user
    would see. React's `act` is useful for effect-ordering tests, but it runs
    through a test scheduler that is not the browser scheduler. Use the
    facade's settle operations for page assertions.

### Virtual clock behaviour

A virtual clock is opt-in:

```clojure
(hm/mount! [view] {:clock true})
```

`hm/advance-clock!` advances `Date.now`, `setTimeout`, and `setInterval`. This
matters because retention often compares a deadline with `Date.now`; firing a
timer without moving the clock would leave the deadline unexpired.

The clock does **not** advance:

- `requestAnimationFrame`;
- promises or microtasks;
- `performance.now`;
- the `Date` constructor;
- timer functions captured before the virtual-clock window opened, including
  React scheduler references captured at module load.

Calling `hm/advance-clock!` without a clock-enabled handle raises. Use it for
work that has an actual duration; use `hm/settle!` for work that does not.

### Use L3 for React claims

Examples include:

- a real error boundary catching a real throw ([Errors](17-errors.md));
- StrictMode double invocation or an abandoned render;
- keyed insertion, deletion, and reorder against real nodes;
- a foreign component's hooks, context, and refs ([Interop](09-interop.md));
- Activity hide and reveal, including subscription release and reacquisition;
- hydration through `hm/hydrate!`
  ([SSR and hydration](18-ssr-and-hydration.md)).

`hm/assert-clean!` requires zero additional residue after unmount. A surviving
subscription, listener, scheduled task, or retained callback is a bug. Do not
raise a tolerance to make it green.

## L4: real browser engines

Use Chromium, Firefox, and WebKit for behaviour that depends on an actual
browser engine:

- IME composition;
- caret and selection restoration;
- focus traversal;
- layout and scroll geometry;
- hydration against real server bytes;
- performance budgets.

Controlled-input composition is a canonical L4 case
([Controlled inputs](04-controlled-inputs.md)). Performance scripts and
budgets belong to [Performance](19-performance.md).

## Migration shadow tests

`ht/shadow!` is a development-only migration harness. It drives a Hicasso view
and its Reagent original with one script, then compares canonical DOM and
intent streams at each checkpoint. Its full use belongs to
[Migrating from Reagent](20-migration-from-reagent.md).

## Prevent vacuous tests with a sabotage twin

A collection assertion can pass because the collection was accidentally
empty. `every?` over no items is true.

Pin the population with an explicit count. For important instrumentation,
also add a **sabotage twin**: deliberately change the input so the measurement
moves. That proves the test can fail.

```clojure
;; todo.views
(h/defview todo-list [_]
  [:ul
   (for [id (h/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])

(deftest every-visible-todo-gets-a-row
  (let [tree
        (ht/tree
         [views/todo-list {}]
         {:subs {[:todo/visible-ids] [1 2 3]}})
        rows (:children tree)]
    (is (= 3 (count rows)))
    (is (= [{:id 1} {:id 2} {:id 3}]
           (mapv ht/attrs rows)))))

(deftest every-visible-todo-gets-a-row--sabotage-twin
  (let [tree
        (ht/tree
         [views/todo-list {}]
         {:subs {[:todo/visible-ids] []}})]
    (is (empty? (:children tree)))))
```

The list test proves that the parent calls one row per id with the expected
props. The row's own test proves what a row renders.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `ht/tree` raises and points to L3 | The body reached a hook, raw React element, `n/$`, or a host | Mount the view at L3; split out a hook-free semantic part when useful |
| `ht/tree` raises and names a query | The body read a subscription with no fixture | Add the exact query fixture; identity is `(query-id, args)` under value equality |
| `ht/tree` cannot inspect a `defview` head in an advanced build | `goog.DEBUG` false removed the body property used by the development harness | Run view tests in a development build, or pass the body function instead of the head |
| A plain test raises `:rf.error/hicasso-sub-outside-render` | A helper called `h/sub` without a render context | Use L2 for a view body; use L0 for handlers and subscriptions |
| `:rf.error/hicasso-deferred-read-at-boundary` | A closure, lazy sequence, or unforced `delay` carried a read beyond render | Read during the body and close over the value ([Views and reads](02-views-and-reads.md)) |
| `hm/assert-clean!` fails | A subscription, listener, task, or foreign callback survived unmount | Fix the leak; retained host callbacks are a common cause ([Interop](09-interop.md)) |
| Data test passes but mounted test fails | React lifecycle, effect order, StrictMode, or commit timing changed the result | Treat the mounted result as authoritative for React behaviour |
| Tree assertion sees `nil` | A `when` returned `nil`; it renders nothing but still appears in authored data | Assert that `nil`, or filter it before comparing |
| A collection test remains green with an empty list | The assertion is vacuously true | Assert the count and add a sabotage twin |

## When not to test through a view

Test a handler when the claim is about state changes. Test a subscription when
the claim is about a derived value. A view test that dispatches an event and
then asserts on app-db mixes several contracts and can fail for unrelated
reasons.

Do not claim more than the level proves:

- L2 does not prove React lifecycle;
- a semantic tree does not prove server or hydration bytes;
- a DOM test in one engine does not prove cross-browser IME or focus;
- a timing assertion is not a performance test until it follows the method in
  [Performance](19-performance.md).

## Advanced

### Name the equality being tested

| Equality | Level | Claim |
| --- | --- | --- |
| Authored data | L1 | This function returned this value |
| Semantic tree | L2 | Under these reads, this body means this |
| Intent stream | L2 or L3 | These interactions produced these events in this order |
| Canonical DOM | L3 | Two mounted implementations produced the same page structure |
| React server bytes | Server test | The server emitted these exact bytes |
| Hydrated behaviour | L4 | A real engine adopted and ran the page correctly |

One equality does not stand in for another.

### Canonical DOM

`ht/canonical-dom` serialises a live DOM subtree with element attribute names
sorted. `innerHTML` preserves insertion order, so two equivalent pages can
produce different strings solely because props were applied in a different
order.

Use canonical DOM when comparing:

- before and after a refactor;
- a Hicasso port with its Reagent original
  ([Migrating from Reagent](20-migration-from-reagent.md));
- a native island with the interpreted subtree it replaced
  ([The native tier](10-native-tier.md)).

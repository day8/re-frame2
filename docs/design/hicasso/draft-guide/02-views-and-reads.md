# Views and reads

A Hicasso view can read a subscription where the value is needed without
forcing a parent to own that read. The view that performs the read becomes the
unit that re-renders when the value changes.

```clojure
(ns todo.views
  (:require [re-frame.hicasso :as h]))

(h/defview todo-row [{:keys [id]}]
  (let [todo     (h/sub [:todo/by-id id])
        editing? (h/sub [:todo.ui/editing? id])]
    [:li
     [:span (:title todo)]
     [:button {:on-click [:todo/toggle id]} "✓"]
     (when editing?
       [:input {:value    (h/sub [:todo.ui/draft id])
                :on-input [:todo.ui/edit id ::h/value]}])]))
```

[`h/sub`](glossary.md#hsub) is legal anywhere in the synchronous body: in a
`let`, conditional, loop, or ordinary helper call. Each
[`h/defview`](glossary.md#defview) records the subscriptions read while its
body runs. When one of those subscription values changes, that view
re-renders.

`h/sub` is the only read form in a Hicasso body. A bare `rf/subscribe` is not an
untracked alternative; it throws rather than resolving. Event vectors and the
`::h/value` marker in the example are covered in
[Events as data](03-events-as-data.md).

## Views and plain helpers

These forms look similar but create different runtime structure:

```clojure
[todo-row {:key id :id id}]   ;; a separate Hicasso view
(row-icon {:kind :urgent})    ;; a plain function call, inlined here
```

A view in head position is an independently re-rendering unit. This guide calls
that unit a [boundary](glossary.md#boundary). `h/defview` creates a boundary
that tracks:

- React identity for the view
- subscription reads made by the body
- the props used by its equality bail-out
- the re-frame2 frame used by event vectors produced by the body

Native tags, fragments, and [`h/defhost`](glossary.md#defhost) heads also appear
in vector position, but they do not create Hicasso boundaries.

A plain `defn` is only a function call. Its Hiccup is inserted into the caller's
tree, and any `h/sub` calls it makes are recorded by the surrounding Hicasso
view. It adds no independent re-render granularity. This lets a helper read the
current filter or other state directly instead of requiring the caller to
thread that value through its arguments.

Do not interchange the two forms:

```clojure
;; Don't — a plain defn cannot be a Hiccup head
[row-icon {:kind :urgent}]
;; :rf.error/hicasso-bad-head

;; Do
(row-icon {:kind :urgent})
```

```clojure
;; Don't — a defview is not called directly
(todo-row {:id 7})

;; Do
[todo-row {:id 7}]
```

The first mistake raises `:rf.error/hicasso-bad-head`. A direct `defview` call
throws at the call site and names the view. A `defview` never turns into an
inline helper because it was called with function syntax.

## Keys go in the props map

```clojure
(h/defview todo-list [_]
  [:ul
   (for [id (h/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

Every member of a sequence of children needs a key. Put `:key` in that child's
props map. Hicasso does not read Reagent-style `^{:key id}` metadata, and `for`
does not invent a key. Missing keys produce
`:rf.warning/hicasso-missing-key` in development, naming the view and child.

This page owns the spelling. [Lists and collections](06-lists-and-collections.md)
explains key quality: use a stable domain identity, never an array index or the
whole entity.

## Props, children, and fragments

The supported head shapes have different props and children contracts:

| Head | Props | Children | `:key` | `:ref` |
| --- | --- | --- | --- | --- |
| Native tag — `[:div …]` | attribute map | trailing forms | in the attribute map | callback ref, legal |
| Hicasso view — `[todo-row …]` | one props map | trailing forms arrive as `(:children props)` | in the props map; removed before the body sees props | not a view surface; use ids |
| Fragment — `[:<> …]` | none, except a key-bearing fragment props map | trailing forms | in the fragment props map | none |
| Foreign host — [`h/defhost`](glossary.md#defhost) or `[:>]` | converted according to the host declaration | Hiccup children become React elements | in props | callback ref, legal |

Nested and lazy child sequences are realized once and flattened one level.
`nil` and `false` render nothing. `true` raises
`:rf.error/hicasso-true-child`. An existing React element is a valid child. A
view may return `nil`, one root form, or a fragment. React consumes `:key`, so
it never appears in the props map received by the view body.

A view receives trailing children as a vector of Hiccup forms. Splice that
vector into the result rather than inserting the vector as a single child:

```clojure
(h/defview card [{:keys [title children]}]
  (into [:section.card
         [:h2 title]]
        children))

[card {:title "Inbox"}
 [todo-row {:id 1}]
 [todo-row {:id 2}]]
```

Return a fragment when the view needs several roots:

```clojure
(h/defview toolbar [_]
  [:<>
   [save-button {}]
   [cancel-button {}]])
```

View bodies must be pure and safe to run again. React StrictMode invokes them
twice in development. Mutation of a captured atom, starting a fetch, or using
the body as a render counter therefore belongs elsewhere.

## Equal props skip the body

Every Hicasso view compares its complete props map with ClojureScript `=`. If
the props are equal to the previous render, the body does not run merely
because its parent ran. There is no public opt-out. A child that must change
with its parent should receive a prop that represents that change.

Two sources of invalidation still run the body:

1. A subscription or context read made by the view itself changed. Its own
   pending update takes precedence over the props comparison.
2. A prop compares unequal. Function-valued props and ordinary JavaScript
   objects use reference identity, so a fresh inline closure or fresh JS object
   defeats the bail-out on every parent render.

Reading a subscription in a parent and passing its result down remains
correct. The parent invalidates when the read changes, and each descendant that
receives a changed value gets unequal props. Moving the read closer to the
view that displays it improves granularity; it is not required for correctness.

## Attribute conversion

The attribute map remains ordinary Hiccup:

```clojure
(h/defview title-input [_]
  (let [invalid? (h/sub [:todo.ui/title-invalid?])]
    [:input#title.form-control
     {:type        :text
      :value       (h/sub [:todo.ui/title])
      :placeholder "Todo title"
      :aria-label  "Title"
      :style       {:margin-top 8}
      :class       ["is-wide" (when invalid? "is-invalid")]
      :on-input    [:todo.ui/set-title ::h/value]}]))
```

Five conversion rules cover the normal cases:

- **Attribute names become React names.** Kebab-case becomes camelCase, so
  `:on-click` becomes `onClick`. `:aria-*`, `:data-*`, and CSS custom
  properties beginning `--` pass through. `:class`, `:for`, and `:charset`
  become `className`, `htmlFor`, and `charSet`.
- **Values convert one level deep.** Nested maps such as `:style` have their
  keys converted, so `:margin-top` becomes `marginTop`. Keywords and symbols
  become their names. Functions cross by identity.
- **`:class` accepts several shapes.** It may be a string, keyword, symbol, or
  collection. `nil` entries are removed and the rest are joined with spaces.
- **Tag shorthand composes with explicit props.** Write the id before classes:
  `:input#title.form-control`, not `:input.form-control#title`. An explicit
  `:id` wins over the shorthand id. Classes from the tag and `:class` are
  combined.
- **`:key` is consumed by the runtime.** It is not emitted as a normal prop.

The reserved data vocabulary is intentionally small:
[`::h/value`](glossary.md#hvalue),
[`::h/checked`](glossary.md#hchecked),
[`::h/prevent`](glossary.md#hprevent), and
[`::h/revision`](glossary.md#hrevision). Events and controlled inputs own the
behaviour of those values.

## Forward attributes with owned keys last

A reusable field can accept caller attributes while retaining control of its
value and handler. Use a normal `merge`, placing the keys owned by the field
last:

```clojure
(h/defview search-field [{:keys [id] :as attrs}]
  [:input.form-control
   (merge (dissoc attrs :id)
          {:value    (h/sub [:todo.ui/search id])
           :on-input [:todo.ui/set-search id ::h/value]})])
```

The literal entries written by the field win by presence, not truthiness. The
caller may add `:placeholder`, `:aria-label`, `:data-testid`, or a class, but
cannot replace the owned `:value` or `:on-input` because those keys are merged
last. Classes written in the tag still combine with a surviving caller
`:class`.

When a caller should control a value, omit the owned literal instead of trying
to override it. Forward maps should use the same kebab-keyword spelling as
Hiccup; a foreign props object's `"className"` string is a different merge
key. Controlled inputs also reserve their checked, key, and revision slots, as
described in [Controlled inputs](04-controlled-inputs.md).

## Where `h/sub` may run

`h/sub` may run only during the direct synchronous execution of an active view
body. Branches, loops, and ordinary helper calls are included. Lazy sequences
used as Hiccup children are forced during the same Hiccup-to-element pass, so
their reads are still recorded by the active view.

A read deferred past that render raises
`:rf.error/hicasso-sub-outside-render` and names the query. This includes a
callback, timer, promise, delayed computation, or lazy sequence forced later.
An unforced `delay` passed through a view boundary raises
`:rf.error/hicasso-deferred-read-at-boundary` before the child can retain a
read that will never update correctly.

```clojure
;; Don't — the read happens when the timer fires
(js/setTimeout
  #(export! (h/sub [:todo/rows]))
  1000)

;; Do — read now and retain the value
(let [rows (h/sub [:todo/rows])]
  (js/setTimeout #(export! rows) 1000))
```

For work that needs current state later, move the work into the event layer and
declare the state as a coeffect with `:rf.cofx/requires`. The handler then has
an explicit state dependency instead of a deferred view read.

!!! warning "A mutable thunk can attach the read to the wrong view"
    A thunk containing `h/sub` can be stored in a mutable reference and later
    forced by another active view. It does not throw because a view is
    rendering at that moment, but the read is recorded against the view that
    forced the thunk rather than the code that created it.

    ```clojure
    ;; Don't — whichever view invokes this thunk acquires the subscription
    (reset! !later #(h/sub [:todo/rows]))
    ```

    The runtime does not trace subscription ownership through mutable
    references. Treat this as undefined conduct and pass a value or explicit
    function input instead.

## How read tracking behaves

Four facts explain the observable behaviour:

1. A view records exactly the subscriptions read during that render. A branch
   that did not run contributes no dependency.
2. Framework subscriptions — route identity, resource status, or machine tags
   — use the same tracking mechanism as application subscriptions.
3. Subscription identity is `(query-id, args)` under value equality. Rebuilding
   an equal persistent map produces the same cache key. A changed value,
   function argument, or JS object creates a different key because functions
   and JS objects compare by identity.
4. When the set of taken branches changes, the view refreshes the complete
   recorded set. Dynamic reads are supported; whole-set refresh is their cost.

## Troubleshooting

| Symptom | Error or cause | Fix |
| --- | --- | --- |
| A read made after rendering throws and names the query | `:rf.error/hicasso-sub-outside-render` | Read during the body and retain the value. Event handlers obtain current state through coeffects |
| An unforced `delay` in props throws at the child view | `:rf.error/hicasso-deferred-read-at-boundary` | Force it in the owning body or pass an ordinary function/value with an explicit contract |
| A plain `defn` used as a Hiccup head throws | `:rf.error/hicasso-bad-head` | Call the helper or define it with `h/defview` |
| Calling a `defview` directly throws | The view was invoked as a function | Render `[todo-row {:id 7}]` |
| Development warns about a missing key | `:rf.warning/hicasso-missing-key` | Put `:key` in each sequence member's props map; metadata is not read |
| The first render reports an unknown subscription | `:rf.error/no-such-sub` | Require the namespace that registers the subscription before mounting |
| A child runs although its props look the same | A prop uses reference identity or one of the child's own reads changed | Hoist a function/JS object, pass persistent data, or inspect the child's own subscriptions |
| One state change re-renders many unrelated views | The subscription read is higher in the tree than necessary | Move the read into the view that displays the value |
| A body effect happens twice in development | React StrictMode invoked the body twice | Keep the body pure and move effects to the event/effect layer |
| Subscription instances are constantly recreated | Query arguments are not stable under `=` | Use value-stable persistent arguments; fresh-but-equal persistent values are fine |

## When not to create another view

Use a plain helper when the markup has no independent reads and should always
render with its caller. Create a separate Hicasso view when that part of the
tree needs its own subscription tracking or props bail-out, not merely because
the source became long.

If a measured region remains hot after fixing read placement and props
stability, use the method in [Performance](19-performance.md) before moving it
to the native tier.

## Advanced

### The collector

Each Hicasso view has one runtime hook that opens a collection window while the
body runs. The body may probe subscription reads, but only a committed render
installs them. A render that React retries or abandons therefore leaves no
subscriptions behind.

Lazy child sequences are forced while the window is open, which is why their
reads are attributed correctly. Once the window closes, a deferred `h/sub`
call can no longer be assigned to a view and raises the named error instead of
creating a value that looks correct once and then stops updating.

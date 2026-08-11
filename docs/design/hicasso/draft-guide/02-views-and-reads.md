# Views and reads

After [Installation](installation.md)'s counter, this page is how views stay
fine-grained and why one read form is enough.

Views that re-render too coarsely, and subscription reads that cannot live
where you use them, are the same problem twice. Either you hoist a value high
in the tree and re-render many sibling views, or you invent a second way to
read so that a helper can see the current filter.

In Hicasso, a view is a function from a props map to Hiccup. There is one way
to read — [`h/sub`](glossary.md#hsub), an ordinary function call at the point
of use:

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
       [:input {:value    (h/sub [:todo.ui/draft id])   ;; a read inside a conditional
                :on-input [:todo.ui/edit id ::h/value]}])]))
```

`h/sub` is legal anywhere in the body — inside a `let`, a `when`, a `for`, or
an ordinary helper call. Each [`h/defview`](glossary.md#defview) tracks the
subscriptions read while it renders. When one of those values changes, that
view re-renders.

`h/sub` is the only read form. There is no second spelling for helpers. A bare
`rf/subscribe` in a body is not a fallback: it throws instead of resolving.
(The event vectors in those attributes — *[intents](glossary.md#intent)* —
and [`::h/value`](glossary.md#hvalue) belong to
[Events as data](03-events-as-data.md).)

## Views and plain helpers

There are two ways to use another function from a view body, and the
difference is visible in the syntax:

```clojure
[todo-row {:key id :id id}]   ;; a vector — a separate view
(row-icon {:kind :urgent})    ;; a call to a plain defn — inlined into this view
```

A **view in head position** is an independently re-rendering unit — the guide
calls this a [boundary](glossary.md#boundary). [`h/defview`](glossary.md#defview)
creates one. That unit tracks:

- React's identity for the view
- the `h/sub` reads its body makes
- a value-equality bail-out on props
- the re-frame2 frame (isolated app-db and queue) that its event vectors
  dispatch into

Native tags, fragments, and [`h/defhost`](glossary.md#defhost) heads also sit in
vector position. None of them is a boundary.

A **plain `defn` call** is only a function call. The runtime splices its
Hiccup into the caller's tree. Any `h/sub` the helper performs is tracked by
the enclosing view. Helpers add no re-render granularity of their own. Because
reads are ordinary calls, helpers can read: a `filter-button` that needs the
current filter reads it, and the read belongs to the view that is rendering.
You do not thread the value down as an argument.

The two forms are not interchangeable:

```clojure
;; Don't — a plain defn in head position
[row-icon {:kind :urgent}]   ;; :rf.error/hicasso-bad-head: call it, or make it a view

;; Don't — a view called directly
(todo-row {:id 7})           ;; throws at the call, naming the view; write [todo-row {:id 7}]
```

Re-render granularity should be visible in the source. A boundary is always a
vector, and an [inline helper](glossary.md#inline-helper) is always a call. A
`defview` never becomes an inline function because you invoked it differently.

## Keys go in the props map

```clojure
(h/defview todo-list [_]
  [:ul
   (for [id (h/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

A seq of children needs keys, whatever the head is. The key lives **in the
props map**. Hicasso does not read `^{:key id}` metadata here (the most common
Reagent habit), and no `for` invents a key for you. If you miss a key,
development warns with `:rf.warning/hicasso-missing-key` and names the view
and the child. What makes a key *good* (a stable domain identity, never an
index, never the whole entity) is
[Lists and collections](06-lists-and-collections.md).

## Props, children, and fragments

| Head | Props | Children | `:key` | `:ref` |
|---|---|---|---|---|
| Native tag — `[:div …]` | attribute map | trailing forms | in the attribute map | callback ref, legal |
| Hicasso view — `[todo-row …]` | one props map | trailing forms, arriving as `(:children props)` | in the props map, **removed before your body sees props** | not a view surface — use ids |
| Fragment — `[:<> …]` | — | trailing forms | on the fragment's props map | — |
| Foreign — [`h/defhost`](glossary.md#defhost) and `[:>]` | converted per declaration | Hiccup children become elements | in props | callback ref, legal |

Children arrive realized and flattened in a predictable way. Nested and lazy
sequences are realized once and flattened one level. `nil` and `false` render
nothing. `true` raises `:rf.error/hicasso-true-child`. An existing React
element is a legal child anywhere. A view can return `nil`, a single root, or
a fragment. `:key` never reaches your body; that is React's contract.

`(:children props)` is a **vector of Hiccup forms**. Splice it into your
Hiccup; do not insert it as one child:

```clojure
(h/defview card [{:keys [title children]}]
  (into [:section.card
         [:h2 title]]
        children))

[card {:title "Inbox"}
 [todo-row {:id 1}]
 [todo-row {:id 2}]]
```

A view that has no single wrapper returns a fragment:

```clojure
(h/defview toolbar [_]
  [:<>
   [save-button {}]
   [cancel-button {}]])
```

**Bodies are pure and re-runnable.** React StrictMode runs your body twice in
development, and that is safe when bodies stay pure. Anything that breaks
under a second run does not belong in a body: mutating a captured atom,
starting a fetch, counting renders.

## Views skip work when props are equal

Every `h/defview` compares the whole props map with ClojureScript `=`. If a
view's props compare equal to the last render, its body does not run, even
when its parent's body ran. There is no mode flag and no public opt-out. A
view that must re-run with its parent takes a prop that changes.

Two things still force a re-render. First, a subscription (or context read)
the view made itself always wins: if one of *its* reads changed, the body
runs regardless of props. Second, a function-valued prop compares unequal by
identity. A fresh closure is never `=` to the last one, so an inline handler
passed as a prop defeats the bail-out every time.

A subscription read high in the tree, with the value passed down, does **not**
lose an update. The view that reads is the view that invalidates, and every
view that the changed value reaches receives unequal props at that hop. The
benefit of the default is this: a view that the value does *not* reach skips
instead of re-rendering for nothing. Moving a read down is a granularity fix,
not a correctness fix.

## Attributes

The attribute map is ordinary Hiccup. The runtime does the conversions React
wants:

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

Five rules cover the conversions:

- **Names go from kebab-case to camelCase.** `:on-click` emits `onClick`.
  `:aria-*` and `:data-*` pass through as written. A `--custom-property` stays
  verbatim. `:class` → `className`, `:for` → `htmlFor`, `:charset` →
  `charSet`.
- **Values convert one level deep.** A nested map, such as `:style`, has its
  own keys converted to camelCase, so `{:margin-top 8}` arrives as
  `marginTop`. Keywords and symbols become their names (`:type :text` is
  `type="text"`). Functions cross by identity.
- **`:class` takes more than a string.** It also takes a keyword, a symbol, or
  a collection of those. The runtime drops `nil`s and joins the rest with
  spaces, so the `(when …)` above contributes nothing when it is false.
- **The tag's `#id.class` shorthand composes.** Write the id first:
  `:input#title.form-control`, never `:input.form-control#title`. An
  explicit `:id` in the map wins over `#title`. `.form-control` on the tag
  joins whatever `:class` brings.
- **`:key` is not an attribute.** The runtime reads it off the map and never
  emits it as a prop.

The reserved-data vocabulary is small: [`::h/value`](glossary.md#hvalue),
[`::h/checked`](glossary.md#hchecked), [`::h/prevent`](glossary.md#hprevent),
and [`::h/revision`](glossary.md#hrevision). The page that owns each word
teaches it ([events](03-events-as-data.md),
[controlled inputs](04-controlled-inputs.md)).

## Forwarding attributes: owned wins

Keys you write on the element always beat a forwarded map.

A reusable field takes caller attributes and still owns its control keys. The
merge is a plain `merge`, with the attributes that you own written last:

```clojure
(h/defview search-field [{:keys [id] :as attrs}]
  [:input.form-control
   (merge (dissoc attrs :id)
          {:value    (h/sub [:todo.ui/search id])
           :on-input [:todo.ui/set-search id ::h/value]})])
```

**The literal keys that you write always win over anything forwarded — by
presence, not truthiness.** A forwarded map can add a `:placeholder`, an
`:aria-label`, or a `:data-testid`. It can never displace the `:value` or the
handler that the field owns, because the owned keys merge last. Classes still
compose: `.form-control` on the tag joins whatever `:class` survives the
merge, so a caller's class adds to the element's own class instead of
replacing it. When a caller's value *should* win, do not write the literal.
One risk: `merge` works by key, so forward maps spelled the way you write
attributes — kebab keywords — not a foreign props object's `"className"`
strings.

The case that makes this rule necessary is a
[controlled input](glossary.md#controlled-field): a forwarded map must never
supply the value, checked, handler, key, or revision slots.
[Controlled inputs](04-controlled-inputs.md) teaches that case in full.

## When `h/sub` is legal

`h/sub` is legal during the **direct synchronous execution** of the active
view body. Branches, loops, and ordinary helpers are included. Reads inside a
lazy `for` count as direct: the same pass that turns Hiccup into elements
forces them, so they land on the view that is rendering.

A read **deferred past the render** throws, with source and recovery. It does
not go silently stale. A callback, a promise, a timer, a stashed lazy seq, a
`delay` forced later — each raises
`:rf.error/hicasso-sub-outside-render` and names the query. An unforced
`delay` that crosses into a view's props raises
`:rf.error/hicasso-deferred-read-at-boundary` at the crossing, before it can
freeze a child. Hicasso never guesses which render owns a deferred read. The
alternative is a value that looks correct on screen and never updates again,
with no error to point to.

```clojure
;; Don't — a read deferred into a timer callback
(js/setTimeout
  #(export! (h/sub [:todo/rows]))    ;; :rf.error/hicasso-sub-outside-render
  1000)

;; Do — read during the render; close over the value
(let [rows (h/sub [:todo/rows])]
  (js/setTimeout #(export! rows) 1000))
```

The recovery always has the same shape: read during the render and close over
the **value**, or move the work to the event layer. An event handler that
needs current state declares that state as a coeffect with
`:rf.cofx/requires`. The read then becomes part of the handler's contract, not
a side effect in a body. There is no `@`-anywhere form and no second read form
for free-standing code.

!!! warning "The one escape that does not throw"
    A read parked in a mutable reference and forced inside another body raises
    nothing: a body *is* rendering there, just not the one that wrote the
    read. The page paints, and the read is tracked by the view that forced
    the thunk rather than the one that authored it. That view keeps every
    dependency it made itself; what it gains is a stranger's — a subscription
    to a cell it does not display and now re-renders on. The runtime does not
    chase reads through mutable references, so this is undefined conduct
    rather than a named error, and yours to avoid.

    ```clojure
    ;; Don't — the thunk is parked in an atom, and whichever body forces it
    ;; is the one that owns the read
    (reset! !later #(h/sub [:todo/rows]))   ;; no error to point to
    ```

## How tracking works

Four operational facts:

1. Each view records exactly the subscriptions its body made on that render.
   A branch not taken contributes nothing.
2. Framework subscriptions — machine tags, resource status, route identity —
   read the same way as your own. They are rows in the same index.
3. Sub-key identity is `(query-id, args)` under **value equality**. A rebuilt
   `{:scope :all}` inside the body hits the same cache entry on every render:
   two structurally equal persistent values are one key. Two argument kinds
   cause constant re-creation: an argument whose value changed (a timestamp),
   and an argument that uses reference identity (a function, a JS object).
   For those, `=` is identity.
4. The recorded set is a function of what the body *did*. A body whose taken
   branches change re-subscribes the whole set, not only the changed key.
   Dynamic reads are legal; a whole-set refresh is their price.

## Troubleshooting

| Symptom | Error | Fix |
|---|---|---|
| A read after the render throws, naming the query | `:rf.error/hicasso-sub-outside-render` | Read during the render; close over the value. Handlers declare state with `:rf.cofx/requires` |
| An unforced `delay` in props throws at the view | `:rf.error/hicasso-deferred-read-at-boundary` | Hand a function, or force the delay in your own body |
| A plain `defn` in head position throws | `:rf.error/hicasso-bad-head` | Call it — `(row-icon …)` — or define it with `h/defview` |
| Calling a view directly throws, naming the view | throws at the call site | Write the head: `[todo-row {:id 7}]` |
| Console warns about a missing key, naming view and child | `:rf.warning/hicasso-missing-key` | Put `:key` in each member's props map; `^{:key}` metadata is not read ([Lists and collections](06-lists-and-collections.md) owns key quality) |
| First render throws naming a query id | `:rf.error/no-such-sub` | Registration happens on namespace load; require the subs namespace at boot |
| View re-renders although props look unchanged | none — bail-out defeated | A prop uses reference identity (inline `fn`, JS object). Hoist it, or pass a persistent value |
| One cell changes and 300 views re-render | none — reads live too high | Push the read down into the view that displays it |
| A body's side effect fires twice in development | none — StrictMode double-invoke | Bodies are pure; move the effect out |
| Subscriptions constantly re-created | none — args not value-stable | Make query args `=` between renders; fresh-but-equal persistent values are fine |

## When not to create a view

Not every function needs to be a view. If markup has no reads of its own and
always re-renders with its parent, a plain function is cheaper and simpler.
Create a boundary when you want a part of the tree to update independently,
not because the markup became long. When a measured hot region outgrows view
tuning — the ~2% case, not the default 98% — the escape ladder in
[Performance](18-performance.md) owns the next step.

## Advanced

### The collector

The mechanism behind the four operational facts is one fixed runtime hook per
view. The hook opens a collection window for the duration of the body.
Abandoned renders install nothing: the render probes reads, and the commit
owns them, so a render that React retries or discards leaves no subscriptions
behind. Reads inside lazy seqs land correctly because the Hiccup-to-element
pass forces them while the window is open, not later when other code walks the
seq.

An escaped read fails loudly by design. A closed-over `h/sub` call site
(rather than its value), a stashed unforced seq, or a `delay` forced after the
render — each fails and names the query, because the collector can no longer
say which view owns the read.

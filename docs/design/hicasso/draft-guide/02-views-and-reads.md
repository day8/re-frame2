# Views and reads

Views that re-render too coarsely, and subscription reads that cannot live
where you use them, are the same problem twice. Either you hoist a value high
in the tree and re-render many sibling views, or you invent a second way to
read so that a helper can see the current filter.

Hicasso's answer has two parts. A view is a function from a props map to
hiccup. There is **one** way to read — `h/sub`, an ordinary function call at
the point of use:

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

> **Read where you use.** `h/sub` is legal anywhere in the body — inside a
> `let`, a `when`, a `for`, an ordinary helper call.

`h/sub` is the only read form. There is no second spelling for helpers. A
bare `rf/subscribe` in a body is not a fallback: it refuses instead of
resolving, and it names the read that went around the collector — the
runtime mechanism that records each boundary's reads. (The event vectors in
those attributes — *intents* — and `::h/value` belong to
[Events as data](03-events-as-data.md).)

## Boundaries and inlining

There are two ways to use another function from a view body, and the
difference is visible in the syntax:

```clojure
[todo-row {:key id :id id}]   ;; a vector — a BOUNDARY child
(row-icon {:kind :urgent})    ;; a call to a plain defn — INLINED into this boundary
```

A **view in head position** mints a **boundary** — Hicasso's unit of
independent re-rendering, and the thing `h/defview` exists to make. A
boundary owns four things:

- React's identity for the boundary.
- The `h/sub` reads that its body makes.
- Its value-equality bail-out.
- The frame to which the intents in its hiccup dispatch.

Native tags, fragments, and `h/defhost` heads also sit in vector position.
None of them is a boundary.

A **plain `defn` call** is only a function call, and it owns none of the
four. The runtime splices its hiccup into the caller's tree. Any `h/sub`
that the helper performs gives that read *upward* to the enclosing boundary.
Helpers cost nothing at runtime, and they add no re-render granularity.
Because reads are ordinary calls, **helpers can read**: a `filter-button`
that needs the current filter reads it, and the read belongs to the boundary
that is rendering. You do not thread the value down as an argument.

The two spellings do not cross, and both crossings fail with a named error:

```clojure
;; Don't — a plain defn in head position
[row-icon {:kind :urgent}]   ;; :rf.error/hicasso-bad-head: call it, or make it a view

;; Don't — a view called directly
(todo-row {:id 7})           ;; refuses at the call, naming the view; write [todo-row {:id 7}]
```

Re-render granularity should be visible in the source. A boundary is always
a vector, and an inline helper is always a call. A `defview` never degrades
into an inline function because you invoked it differently.

## Keys go in the props map

```clojure
(h/defview todo-list [_]
  [:ul
   (for [id (h/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

A seq of children needs keys, whatever the head is. The key lives **in the
props map**. Hicasso does not read `^{:key id}` metadata here (the most
common Reagent carry-over), and no `for`-lowering invents a key for you. If
you miss a key, development warns with `:rf.warning/hicasso-missing-key` and
names the view and the child. That is all this page teaches about keys. What
makes a key *good* (a stable domain identity, never an index, never the
whole entity) is the law of
[Lists and collections](06-lists-and-collections.md).

## The component ABI

| Head | Props | Children | `:key` | `:ref` |
|---|---|---|---|---|
| Native tag — `[:div …]` | attribute map | trailing forms | in the attribute map | callback ref, legal |
| Hicasso view — `[todo-row …]` | one props map | trailing forms, arriving as `(:children props)` | in the props map, **extracted before your body sees props** | not a view surface — use ids |
| Fragment — `[:<> …]` | — | trailing forms | on the fragment's props map | — |
| Foreign — `h/defhost` and `[:>]` | converted per declaration | hiccup children become elements | in props | callback ref, legal |

Children arrive realized and flattened in a predictable way. The runtime
realizes nested and lazy sequences once and flattens them one level. `nil`
and `false` render nothing. `true` is an error
(`:rf.error/hicasso-true-child`). An existing React element is a legal child
anywhere. A view can return `nil`, a single root, or a fragment. `:key`
never reaches your body; that is React's contract, not a Hicasso choice.

`(:children props)` is a **vector of hiccup forms**. Splice it into your
hiccup; do not insert it as one child:

```clojure
(h/defview card [{:keys [title children]}]
  (into [:section.card
         [:h2 title]]
        children))

[card {:title "Inbox"}
 [message-row {:id 1}]
 [message-row {:id 2}]]
```

A view that has no single wrapper returns a fragment:

```clojure
(h/defview toolbar [_]
  [:<>
   [save-button {}]
   [cancel-button {}]])
```

**Bodies are pure and re-runnable.** React StrictMode runs your body twice
in development, and that is safe, because bodies are pure by contract.
Anything that breaks under a second run does not belong in a body. Examples:
mutation of a captured atom, the start of a fetch, a render counter.

## Boundaries memoize by default

Every head that `h/defview` mints carries one stable memo wrapper. The
wrapper compares the whole props map with CLJS `=`. If a boundary's props
compare equal to the last render, its body does not run, even when its
parent's body ran. There is no mode flag and no public opt-out. A boundary
that must re-run with its parent takes a prop that changes.

Two channels outrank the bail-out, and both are the boundary's **own**
invalidation. First, a subscription read or a context read that the boundary
made itself always wins. React checks for the boundary's own pending update
*before* it asks the comparator, so a boundary whose reads changed
re-renders regardless of its props. Second, a function-valued prop compares
unequal by identity, and that is deliberate. A fresh closure is never `=` to
the last one, so an inline handler passed as a prop defeats the bail-out
every time.

A subscription read high in the tree, with the value passed down, does
**not** lose an update. The boundary that reads is the boundary that
invalidates, and every boundary that the changed value reaches receives
unequal props at that hop. The benefit of the default is this: a boundary
that the value does *not* reach skips instead of re-rendering for nothing.
To move a read down is a granularity fix, not a correctness fix.

## Attributes

The attribute map is ordinary hiccup. The runtime does the conversions that
React wants:

```clojure
(h/defview title-input [_]
  (let [invalid? (h/sub [:editor/title-invalid?])]
    [:input#title.form-control
     {:type        :text
      :value       (h/sub [:editor/title])
      :placeholder "Article Title"
      :aria-label  "Title"
      :style       {:margin-top 8}
      :class       ["is-wide" (when invalid? "is-invalid")]
      :on-input    [:editor/set-title ::h/value]}]))
```

Five rules cover the conversions.

- **Names go from kebab-case to camelCase.** `:on-click` emits `onClick`.
  `:aria-*` and `:data-*` pass through as written. A `--custom-property`
  stays verbatim. `:class` → `className`, `:for` → `htmlFor`, `:charset` →
  `charSet`.
- **Values convert one level deep.** A nested map, such as `:style`, has its
  own keys converted to camelCase, so `{:margin-top 8}` arrives as
  `marginTop`. Keywords and symbols become their names (`:type :text` is
  `type="text"`). Functions cross by identity.
- **`:class` takes more than a string.** It also takes a keyword, a symbol,
  or a collection of those. The runtime drops `nil`s and joins the rest with
  spaces, so the `(when …)` above contributes nothing when it is false.
- **The tag's `#id.class` shorthand composes.** Write the id first:
  `:input#title.form-control`, never `:input.form-control#title`. An
  explicit `:id` in the map wins over `#title`. `.form-control` on the tag
  joins whatever `:class` brings.
- **`:key` is not an attribute.** The runtime reads it off the map and never
  emits it as a prop.

The reserved-data vocabulary is deliberately small: `::h/value`,
`::h/checked`, `::h/prevent`, and `::h/revision`. The chapter that owns each
word teaches it ([events](03-events-as-data.md),
[controlled inputs](04-controlled-inputs.md)).

## Forwarding attributes: owned wins

A reusable field takes caller attributes and still owns its control keys.
The merge is a pure recipe — a plain `merge`, with the attributes that you
own written last:

```clojure
(h/defview search-field [{:keys [id] :as attrs}]
  [:input.form-control
   (merge (dissoc attrs :id)
          {:value    (h/sub [:search/text id])
           :on-input [:search/set-text id ::h/value]})])
```

**The literal keys that you write always win over anything forwarded — by
presence, not truthiness.** A forwarded map can add a `:placeholder`, an
`:aria-label`, or a `:data-testid`. It can never displace the `:value` or
the handler that the field owns, because the owned keys merge last. Classes
still compose: `.form-control` on the tag joins whatever `:class` survives
the merge, so a caller's class adds to the element's own class instead of
replacing it. When a caller's value *should* win, do not write the literal.
One risk: `merge` works by key, so forward maps spelled the way you write
attributes — kebab keywords — not a foreign props object's `"className"`
strings.

The case that makes this law necessary is a controlled input: a forwarded
map must never supply the value, checked, handler, key, or revision slots.
[Controlled inputs](04-controlled-inputs.md) teaches that case in full.

## The read-extent law

`h/sub` is legal during the **direct synchronous execution** of the active
body. Branches, loops, and ordinary helpers are included. Reads inside a
lazy `for` count as direct: the same pass that turns hiccup into elements
forces them, so they land in the boundary that is rendering.

A read **deferred past the render** refuses, with source and recovery. It
does not go silently stale. A callback, a promise, a timer, a stashed lazy
seq, a `delay` forced later — each raises
`:rf.error/hicasso-sub-outside-render` and names the query. The runtime
refuses an unforced `delay` that crosses into a boundary's props at the
crossing (`:rf.error/hicasso-deferred-read-at-boundary`), before it can
freeze a child. Hicasso never guesses which render owns a deferred read. The
alternative is a value that looks correct on screen and never updates again,
with no error to point to.

```clojure
;; Don't — a read deferred into a timer callback
(js/setTimeout
  #(export! (h/sub [:report/rows]))    ;; :rf.error/hicasso-sub-outside-render
  1000)

;; Do — read during the render; close over the value
(let [rows (h/sub [:report/rows])]
  (js/setTimeout #(export! rows) 1000))
```

The recovery always has the same shape: read during the render and close
over the **value**, or move the work to the event layer. An event handler
that needs current state declares that state as a coeffect with
`:rf.cofx/requires`. The read then becomes part of the handler's contract,
not a side effect in a body. There is no `@`-anywhere form and no second
read form for free-standing code.

## How `h/sub` tracks reads

There are four operational claims:

1. Each boundary opens one collection window for the duration of its body.
   The commit installs **exactly** the edge set that the body made. A branch
   not taken contributes no edge.
2. Framework subscriptions — machine tags, resource status, route identity —
   read identically to your own subscriptions. They are rows in the same
   index, not special cases.
3. Sub-key identity is `(query-id, args)` under **value equality**. A
   rebuilt `{:scope :all}` inside the body hits the same cache entry on
   every render: two structurally equal persistent values are one key. Two
   argument kinds cause constant re-creation: an argument whose value
   changed (a timestamp), and an argument that carries reference identity (a
   function, a JS object). For those, `=` is identity.
4. The edge set is a function of what the body *did*. A body whose taken
   branches change re-subscribes the whole set, not only the changed key.
   Dynamic reads are legal; a whole-set refresh is their price.

## Troubleshooting

| Symptom | Error | Fix |
|---|---|---|
| A read after the render throws, naming the query | `:rf.error/hicasso-sub-outside-render` | Read during the render; close over the value. Handlers declare state with `:rf.cofx/requires` |
| An unforced `delay` in props refuses at the boundary | `:rf.error/hicasso-deferred-read-at-boundary` | Hand a function, or force the delay in your own body |
| A plain `defn` in head position throws | `:rf.error/hicasso-bad-head` | Call it — `(row-icon …)` — or mint it with `h/defview` |
| Calling a view directly throws, naming the view | refusal at the call site | Write the head: `[todo-row {:id 7}]` |
| Console warns about a missing key, naming view and child | `:rf.warning/hicasso-missing-key` | Put `:key` in each member's props map; `^{:key}` metadata is not read ([Lists and collections](06-lists-and-collections.md) owns key quality) |
| First render throws naming a query id | `:rf.error/no-such-sub` | Registration happens on namespace load; require the subs namespace at boot |
| Boundary re-renders although props look unchanged | none — bail-out defeated | A prop carries reference identity (inline `fn`, JS object). Hoist it, or pass a persistent value |
| One cell changes and 300 boundaries re-render | none — reads live too high | Push the read down into the boundary that displays it |
| A body's side effect fires twice in development | none — StrictMode double-invoke | Bodies are pure; move the effect out |
| Subscriptions constantly re-created | none — args not value-stable | Make query args `=` between renders; fresh-but-equal persistent values are fine |

## When not to mint a boundary

Not every function needs to be a view. If markup has no reads of its own and
always re-renders with its parent, a plain function is cheaper and simpler.
Mint a boundary when you want a part of the tree to update independently,
not because the markup became long. When a measured hot region outgrows
boundary tuning — the ~2% case, not the default 98% — the escape ladder in
[Performance](18-performance.md) owns the next step.

## Advanced

### The collector

The mechanism behind the four operational claims is one fixed runtime hook
per boundary. The hook opens a collection window for the duration of the
body. The claims imply two more facts. First, abandoned renders install
nothing: the render probes reads, and the commit owns them, so a render that
React retries or discards leaves no subscriptions behind. Second, reads
inside lazy seqs land correctly, because the hiccup-to-element pass forces
them while the window is open, not later when other code walks the seq.

An escaped read fails loudly by design. A closed-over `h/sub` call site
(rather than its value), a stashed unforced seq, or a `delay` forced after
the render — each fails and names the query, because the collector can no
longer say which boundary owns the read.

# Views and reads

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

Views that re-render too coarse, and subscription reads that can't live where
you use them, are the same pain twice. Either you hoist a value and re-render a
room full of siblings, or you invent a second way to read just so a helper can
see the current filter.

Hicasso's answer is a view that is a function from a props map to hiccup, and
**one** way to read — `sub`, an ordinary function call at the point of use:

```clojure
(ns todo.views
  (:require [re-frame.hicasso :as h :refer [defview sub]]))

(defview todo-row [{:keys [id]}]
  (let [todo     (sub [:todo/by-id id])
        editing? (sub [:todo.ui/editing? id])]
    [:li
     [:span (:title todo)]
     [:button {:on-click [:todo/toggle id]} "✓"]
     (when editing?
       [:input {:value    (sub [:todo.ui/draft id])   ;; read inside a conditional
                :on-input [:todo.ui/edit id ::h/value]}])]))
```

> **`sub` is legal anywhere in the body** — inside a `let`, a `when`, a helper
> call. Read where you need the value.

`sub` is the only read form. There is no second spelling for helpers, and a bare
`rf/subscribe` in a body is not a fallback: it refuses, under every adapter,
naming the collector it went around.

## Boundaries and inlining

Two ways to reach another function from a view body. The difference is in the
syntax:

```clojure
[todo-row {:key id :id id}]   ;; a vector — a BOUNDARY child
(todo-row {:id id})           ;; a call — INLINED into the enclosing boundary
```

A **view in head position** mints a **boundary** — Hicasso's unit of independent
re-rendering, and the thing `defview` exists to make. A boundary owns four
things: React's identity for it, the `sub` reads its body makes, its
[value-equality bail-out](#boundaries-memoize-by-default), and the frame the
intents in its hiccup dispatch to. Native tags, fragments and `defhost` heads are
elements in vector position too, and none of them is a boundary.

A **plain call** is just a function call, and owns none of the four. Its hiccup is
spliced into the caller's tree, and any `sub` it performs donates that read
*upward* to the enclosing boundary. Helpers cost nothing at runtime and buy no
re-render granularity. Because reads are ordinary calls, **helpers can read**: a
`filter-button` that needs the current filter just reads it, and the read belongs
to whichever boundary is rendering — no value threaded down as an argument.

One rule, one visible distinction. Re-render granularity should be obvious from
the source. Keys go in the props map (no Reagent `^{:key}` metadata):

```clojure
(defview todo-list [_]
  [:ul
   (for [id (sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

A bare seq of boundary children needs keys. Forget one today and you get React's
own key warning in development; a Hicasso-minted warning is planned but not
built. Putting a plain `defn` in head position — `[badge {...}]` where `badge`
was never minted by `defview` — raises `:rf.error/hicasso-bad-head`.

**You write `:key` yourself, and that is the answer** rather than a gap waiting
on sugar. A `for`-lowering would have to assume the binding value *is* the
identity, which stops being true the moment you iterate whole entities: React
coerces a non-primitive key to a string, so editing a row would quietly change
its key and remount it. And it could not retire the explicit `:key` anyway, so
every list would carry two spellings forever in exchange for saving about a dozen
characters.

### The component ABI

| Head | Props | Children | `:key` | `:ref` |
|---|---|---|---|---|
| Native tag — `[:div …]` | attribute map | trailing forms | `:key` in the attribute map | callback ref, legal |
| Hicasso view — `[todo-row …]` | one props map | trailing forms, arriving as `(:children props)` | in the props map, **extracted before your body sees props** | not yet — use ids |
| Fragment — `[:<> …]` | — | trailing forms | on the fragment's props map | — |
| Foreign — `defhost` (and `[:>]`, once it is built) | converted per declaration | hiccup children become elements | `:key` in props | callback ref, legal |

Children arrive realized and predictably flattened: nested and lazy sequences
are realized once and flattened one level, `nil` and `false` render nothing,
`true` is an error, and an existing React element is a legal child anywhere. A
view may return `nil`, a single root, or a fragment.

`(:children props)` is a **vector of hiccup forms**, so splice it rather than
dropping it in whole — `(into [:ul.nested] children)`, or `(into [:<>] children)`
when you have nothing to wrap it in. A raw vector in child position is read as
hiccup, and a vector whose head is itself a vector is not a legal element.

`:key` never reaches your body. That is React's contract, not a Hicasso choice.

**Fragments and multi-root returns.** A view may return a fragment when it has
no single wrapper to offer:

```clojure
(defview toolbar [_]
  [:<>
   [save-button {}]
   [cancel-button {}]])
```

**Trailing children become `(:children props)`.** A parent that wraps markup
around its children splices that vector — it does not drop the whole vector in
as one hiccup child:

```clojure
(defview card [{:keys [title children]}]
  (into [:section.card
         [:h2 title]]
        children))

[card {:title "Inbox"}
 [message-row {:id 1}]
 [message-row {:id 2}]]
```

### Bodies are pure and re-runnable

React StrictMode runs your body twice in development. That is fine — bodies are
pure by contract. Anything that would break under a second run (mutating a
captured atom, kicking off a fetch, counting renders) does not belong in a body.

### Boundaries memoize by default

A value-equality bail-out is the boundary **default**: every minted head carries
one stable internal memo wrapper comparing the whole props map with CLJS `=`. If
a boundary's props compare equal to last render, its body does not run — even
though its parent's did.

Two things still outrank the bail-out, and both are the boundary's **own**
invalidation. A subscription or context read that boundary made itself always
wins: React checks for a boundary's own pending update *before* it ever asks the
comparator, so a boundary whose reads moved re-renders regardless of what its
props say. And a function-valued prop compares unequal by identity, deliberately
— a freshly allocated closure is never `=` to the last one, so an inline handler
defeats the bail-out every time it is passed fresh.

If you are coming from Reagent this should feel familiar — Reagent has compared
argv and skipped for a decade, and this is the same shape. There is no public
opt-out: a boundary that genuinely wants to re-run every time its parent does
takes an explicit changing revision prop, not a `:memo false` switch.

Reading a subscription high in the tree and passing the value down as a prop
does **not** lose an update: the boundary that reads is the boundary that
invalidates, and every boundary the changed value actually reaches receives
unequal props at that hop. What the default buys you is that a boundary the
value does **not** reach skips instead of re-rendering for nothing. Moving the
read down is a granularity fix, not a correctness fix.

Whether the bail-out stays the *default* is still open; see **Not settled yet**.
Nothing you write changes either way; what may change is whether you have to ask
for it.

## Attributes

The attribute map is ordinary hiccup, with the conversions React wants done for
you.

```clojure
(defview title-input [_]
  (let [invalid? (sub [:editor/title-invalid?])]
    [:input#title.form-control
     {:type        :text
      :value       (sub [:editor/title])
      :placeholder "Article Title"
      :aria-label  "Title"
      :data-testid "title"
      :style       {:margin-top 8}
      :class       ["is-wide" (when invalid? "is-invalid")]
      :on-input    [:editor/set-title ::h/value]}]))
```

Five rules cover it.

**Names go kebab to camel.** `:on-click` emits `onClick` and `:default-value`
emits `defaultValue`. `:aria-*` and `:data-*` pass through exactly as written,
and a `--custom-property` is preserved verbatim. Three attributes React spells
differently from HTML are renamed for you: `:class` → `className`,
`:for` → `htmlFor`, `:charset` → `charSet`.

**Values convert one level deep.** A nested map — `:style` and its kin — has its
own keys camelCased, so `{:margin-top 8}` arrives as `marginTop`. Keywords and
symbols become their names, which is why `:type :text` is `type="text"`.
Functions cross by identity: rewrapping them would defeat the default
value-equality bail-out.

**`:class` takes more than a string.** A keyword, a symbol, or a collection of
those, with `nil`s dropped and the rest joined by spaces — so the `(when …)`
above contributes nothing when it is false, and you never build a class string
by hand.

**The tag's `#id` and `.class` shorthand composes** — id first, as in every
hiccup dialect: `:input#title.form-control`, never
`:input.form-control#title`. An explicit `:id` in the map wins over `#title`,
and `.form-control` on the tag is joined with whatever `:class` brings.

**`:key` is not an attribute.** It is React's identity contract: it is read off
the map, it never reaches your body, and it is not emitted as a prop.

One further key is reserved, and it is the only attribute merge Hicasso has.
`:&` carries a map of attributes from somewhere else — a caller's forwarded
remainder, a theme's part attributes — and **the literal keys you write always
win over it**. The case that makes the law worth having is a controlled input, so
[Controlled inputs](04-controlled-inputs.md#forwarding-attributes)
teaches it in full.

## How `sub` works

Four operational claims:

1. One fixed runtime hook per boundary collects the reads the body actually made,
   and the commit installs exactly that edge set — so **a branch not taken
   contributes no edge**.
2. Reads inside a lazy `for` are forced by the same pass that turns hiccup into
   elements, so they land in the right boundary's window.
3. **A read that escapes the render is loud, never silently stale**: a stored
   handler, a stashed lazy seq, or a `delay` forced later fails naming the
   query; an unforced `delay` crossing a boundary is refused at the crossing.
4. The edge set is a function of what the body *did*, so a body whose control
   flow changes its reads from render to render re-subscribes the whole set, not
   just the changed key.

That last point is the one to design around. Dynamic control flow is legal; when
taken branches change, the price is a whole-set refresh. See **Advanced** for
the collector mechanics.

## Rules every read obeys

- **Reading outside a render is an error.** There is no `@`-anywhere. In an event
  handler, declare the read as a coeffect with `:rf.cofx/requires`, so it belongs
  to the handler's contract rather than happening as a side effect in its body.
  Free-standing code — a utility, an effect body — uses `rf/subscribe-once`, a
  one-shot read that retains no reactive handle, and names the frame it wants:

  ```clojure
  ;; Outside a render and outside any frame scope, so the frame is explicit.
  (let [rows (rf/subscribe-once [:report/rows] {:frame :main})]
    (export/write! rows))
  ```

  The one-argument form resolves an ambient frame instead, which is what a test
  or a REPL session sitting inside a scope already has.
- **Framework subscriptions read identically to your own** — machine tags,
  resource and mutation state, route identity. They are first-class in the
  index, not a special case.
- **Sub-key identity is `(query-id, args)` under value equality.** A *freshly
  allocated* map or vector is not a problem: two structurally equal persistent
  values are one cache key, so rebuilding `{:scope :all}` inside the query on
  every render hits the same entry. What thrashes the index: an argument whose
  **value** genuinely moved (a timestamp, a sort order that really changed),
  and an argument carrying **reference** identity (a function, a JS object or
  array, a host object) — for those `=` is identity, so they are unequal to
  themselves between renders. Documented, programmer-trusted, not policed.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Boundary doesn't re-render though the screen should have changed | Props still `=` last render and no own read moved — bail-out working as designed | `sub` the changing value inside the boundary that displays it, or thread it as a prop so the value differs there |
| Boundary re-renders every time though props look unchanged | A prop carries reference identity (inline `fn`, JS object/array, host object) | Hoist the function, or use a persistent value. Two equal persistent values are `=` however freshly built |
| One cell changes and 300 boundaries re-render | The read lives too high | Push the read down into the boundary that displays it |
| One value changes and a whole subtree re-renders | Value read in an ancestor and passed as a prop — coarse invalidation, not a missed one | Read at the point of use |
| A read after the render throws, naming the query | A handler closure, stashed lazy seq, or `delay` deferred a `sub` past the render | Read during the render and close over the value |
| Index thrash, subscriptions constantly re-created | Query args not *value*-stable (timestamp, real sort-order change, or JS/fn identity) | Make the args equal under `=` between renders; allocation of equal persistent values is fine |
| A body's side effect fires twice | StrictMode double-invoke | Bodies are pure; move the effect out |

## When not to use a boundary

Not every function needs to be a view. If a piece of markup has no reads of its
own and always re-renders with its parent anyway, a plain function is cheaper
and simpler. Reach for a boundary when you want something to update
independently, not because the markup got long.

When a measured island is in the **~2%** (cost, host edge, foreign component) —
not the default 98% — see [Performance](11-performance.md).

## Advanced

### The sub collector

Each boundary owns one fixed runtime hook that opens a collection window for the
duration of the body. Every `sub` call in that window records its query. At
commit, the runtime installs **exactly** the edge set the body just made —
nothing from a branch not taken, nothing from a previous render that no longer
applies.

Reads inside a lazy `for` (or any other lazy seq) are forced by the same pass
that lowers hiccup to React elements. That is why they still land in the right
boundary: the force happens while the window is open, not later when something
else walks the seq.

Escape is loud on purpose. If you close over a `sub` call site (rather than its
value), stash an unforced lazy seq that contains a read, or force a `delay`
after the render, the runtime fails naming the query. An unforced `delay` that
would cross a boundary is refused at the crossing. The alternative in each case
is a value that looks right on screen and freezes thereafter, with nothing to
blame.

Because the edge set tracks control flow, a body that sometimes reads three
queries and sometimes five pays a full re-subscribe when the taken set changes —
not a surgical add/remove of one key. That is the cost of legal dynamic reads.

## Not settled yet

| Question | Status |
|---|---|
| `sub` and `defview` spellings | Working names; **[unfrozen]** until API freeze |
| Whether value-equality bail-out stays the **default** | **Open.** Runtime implements memo-by-default today; explicit opt-in is still on the table |
| Dev warning for an unkeyed seq — id and whether dev-only | **Open.** Today you see React's own key warning |

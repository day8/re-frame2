# Views and reads

> **Draft ahead of the product artefact.** This page teaches the landed surface —
> ruled in [decisions.md](../decisions.md) (HD-001…HD-028), witnessed by the bench
> arm's tests under `implementation/freehand/test/re_frame/bench/hicasso/` — but no
> `implementation/hicasso/` artefact ships yet, and spellings marked **[unfrozen]**
> stay provisional until the API freeze.

A Hicasso view is a function from a props map to hiccup, which reads whatever
subscriptions it needs along the way — with `sub`, an ordinary function call, at
the point where the value is used:

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

Note the third read: it happens only when `editing?` is true, in the middle of an
expression, and that is legal — the whole point of the surface. An earlier draft
of this guide taught two candidate read surfaces because HD-002 had a measured
fork open between them. **The fork is ruled.** On 2026-07-31 the operator ruled
the ambient collector — `sub` as a plain call, anywhere in the body — the only
acceptable read surface on ergonomics; the grouped `use-subs` alternative was
ruled below the usability bar and survives only as a comparator rendering in the
bench arm. The ruling and the three-rendering diff that informed it are recorded
in the [dogfood judgement](../studio/arm1-lean-react-dogfood-judgement.md);
HD-002's correctness gates were not waived and are witnessed there too.

## Boundaries and inlining

Two ways to reach another function from a view body, and the difference is visible
in the syntax.

```clojure
[todo-row {:key id :id id}]   ;; a vector — a BOUNDARY child
(todo-row {:id id})           ;; a call — INLINED into the enclosing boundary
```

A **vector** in head position mints a React element. It is its own boundary: React
owns its identity, and it can re-render without its parent.

A **plain call** is just a function call. Its hiccup is spliced into the caller's
tree, it has no boundary of its own, and any `sub` it performs donates that read
*upward* to the enclosing boundary. Helpers cost nothing at runtime and buy no
re-render granularity, which is the trade in one sentence. And because reads are
ordinary calls, **helpers can read**: a `filter-button` that needs the current
filter just reads it, and the read belongs to whichever boundary is rendering —
no value threaded down as an argument. The census's article card is written
exactly this way
(`implementation/freehand/test/re_frame/bench/hicasso/shapes/card.cljs`): one
plain function with two reads, called from a one-boundary page in one shape and
wrapped in a one-line `defview` in another, with the markup, reads and intents
identical between the two.

One rule, one visible distinction. This is deliberate: re-render granularity is a
thing you should be able to see by reading the source, and Reagent's `^{:key}`
metadata folklore is deleted along with it. Keys go in the props map:

```clojure
(defview todo-list [_]
  [:ul
   (for [id (sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

A bare seq of boundary children needs keys, and today you get React's own key
warning in development if you forget — a Hicasso-minted one is ruled but not built.
The `for`-lowering sugar that would derive the key from the binding is **not v0** —
you write `:key` yourself. Putting a plain `defn` in head position — `[badge {...}]`
where `badge` was never minted by `defview` — is `:rf.error/hicasso-bad-head`,
a loud error rather than a silent embedding.

### The component ABI

HD-016 pins this table, and the keyed insert/delete/reorder witness enforces it.

| Head | Props | Children | `:key` | `:ref` |
|---|---|---|---|---|
| Native tag — `[:div …]` | attribute map | trailing forms | `:key` in the attribute map | callback ref, legal |
| Hicasso view — `[todo-row …]` | one props map | trailing forms, arriving as `(:children props)` | in the props map, **extracted before your body sees props** | not a v0 surface — use ids |
| Fragment — `[:<> …]` | — | trailing forms | on the fragment's props map | — |
| Foreign — `defhost` (and `[:>]`, once it is built) | converted per declaration | hiccup children become elements | `:key` in props | callback ref, legal |

Children arrive realized and predictably flattened: nested and lazy sequences are
realized once and flattened one level, `nil` and `false` render nothing, `true` is an
error, and an existing React element is a legal child anywhere. A view may return
`nil`, a single root, or a fragment.

`(:children props)` is a **vector of hiccup forms**, so splice it rather than
dropping it in whole — `(into [:ul.nested] children)`, or `(into [:<>] children)`
when you have nothing to wrap it in. A raw vector in child position is read as
hiccup, and a vector whose head is itself a vector is not a legal element.

`:key` never reaches your body. That is React's contract, not a Hicasso choice, and
pretending otherwise would be the kind of leaky convenience that costs you a day
when it finally bites.

### Bodies are pure and re-runnable

React StrictMode runs your body twice in development. That is fine — bodies are pure
by contract. Anything that would break under a second run (mutating a captured atom,
kicking off a fetch, counting renders) does not belong in a body.

### Boundaries memoize by default

A value-equality bail-out is the boundary **default** (HD-028, amending HD-006):
every minted head carries one stable internal memo wrapper comparing the whole
props map with CLJS `=`. If a boundary's props compare equal to last render, its
body does not run — even though its parent's did.

Two things still outrank the bail-out, and both are the boundary's **own**
invalidation. A subscription or context read that boundary made itself always
wins: React checks for a boundary's own pending update *before* it ever asks the
comparator, so a boundary whose reads moved re-renders regardless of what its
props say. And a function-valued prop compares unequal by identity, deliberately
— a freshly allocated closure is never `=` to the last one, so an inline handler
defeats the bail-out every time it is passed fresh.

If you are coming from Reagent this should feel familiar rather than surprising —
Reagent has compared argv and skipped for a decade, and this is the same shape.
There is no public opt-out in v1: a boundary that genuinely wants to re-run every
time its parent does takes an explicit changing revision prop, not a `:memo
false` switch.

One corollary is worth stating outright, because the opposite is the thing people
worry about. Reading a subscription high in the tree and passing the value down as
a prop does **not** lose an update: the boundary that reads is the boundary that
invalidates, and every boundary the changed value actually reaches receives unequal
props at that hop, so the default bail-out never applies to it — the value arrives.
What the default buys you, beyond that chain, is that a boundary the value does
**not** reach skips instead of re-rendering for nothing. Moving the read down is
still a granularity fix, not a correctness fix, and if you go looking for a lost
update you will not find one.

Whether the bail-out stays the *default* is the one thing on this page still in
front of the operator. It costs about 100 bytes of retained heap per boundary, and
on the smallest measurable shell that is what carries the shell across the 1 KB
line — so HD-028's own pre-registered fallback, shipping the same comparator as an
explicit opt-in instead, is live. Nothing you write changes either way; what
changes is whether you have to ask for it.

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

Five rules cover the whole of it.

**Names go kebab to camel.** `:on-click` emits `onClick` and `:default-value`
emits `defaultValue`. `:aria-*` and `:data-*` pass through exactly as written,
because that is what the DOM wants, and a `--custom-property` is preserved
verbatim. Three attributes React spells differently from HTML are renamed for you:
`:class` → `className`, `:for` → `htmlFor`, `:charset` → `charSet`.

**Values convert one level deep.** A nested map — `:style` and its kin — has its
own keys camelCased, so `{:margin-top 8}` arrives as `marginTop`. Keywords and
symbols become their names, which is why `:type :text` is `type="text"`. Functions
cross by identity, deliberately: rewrapping them would defeat the default
value-equality bail-out and every other comparison that looks at handler identity.

**`:class` takes more than a string.** A keyword, a symbol, or a collection of
those, with `nil`s dropped and the rest joined by spaces — so the `(when …)` above
contributes nothing at all when it is false, and you never build a class string by
hand.

**The tag's `#id` and `.class` shorthand composes** — and the id comes first, as it
does in every hiccup dialect: `:input#title.form-control`, never
`:input.form-control#title`. An explicit `:id` in the map wins over `#title`, and
`.form-control` on the tag is joined with whatever `:class` brings rather than one
of them silently replacing the other.

**`:key` is not an attribute.** It is React's identity contract: it is read off the
map, it never reaches your body, and it is not emitted as a prop.

One further key is reserved, and it is the only attribute merge Hicasso has. `:&`
carries a map of attributes from somewhere else — a caller's forwarded remainder, a
theme's part attributes — and **the literal keys you write always win over it**.
The case that makes the law worth having is a controlled input, so
[Controlled inputs](04-controlled-inputs.md#forwarding-attributes-onto-a-controlled-input)
teaches it in full.

## How `sub` works, in the four sentences that matter

One fixed runtime hook per boundary collects the reads the body actually made,
and the commit installs exactly that edge set — so **a branch not taken
contributes no edge**, measured on the dogfood screen, where the collector
rendering holds fewer edges than the declaration-style rendering of the same
page. Reads inside a lazy `for` are forced by the same pass that turns hiccup
into elements, so they land in the right boundary's window. **A read that
escapes the render is loud, never silently stale**: a stored handler, a stashed
lazy seq, or a `delay` forced later fails naming the query, and an unforced
`delay` crossing a boundary is refused at the crossing — the alternative in each
case would be a value that is correct on screen and frozen thereafter,
attributable to nothing. The honest cost sits on the other side: the edge set is
a function of what the body *did*, so a body whose control flow changes its
reads from render to render pays a re-subscribe that replaces the whole set, not
just the changed key.

That last sentence is the one to design around. It is a real difference from a
surface whose edges are static, and it is priced honestly in the
[dogfood judgement](../studio/arm1-lean-react-dogfood-judgement.md) — as is the
kill condition that still stands over the mechanism: the first time correctness
requires a per-read candidate ledger, the collector dies, whatever its numbers
([hd-002-adjudication.md](../hd-002-adjudication.md)). The witnesses so far say
it does not.

## Rules every read obeys

- **Reading outside a render is an error.** There is no `@`-anywhere. Handler and
  utility code uses `rf/subscribe-once`, which is a one-shot read that retains no
  reactive handle.
- **Framework subscriptions read identically to your own** — machine tags, resource
  and mutation state, route identity. They are 27% of the census's read traffic, so
  the index serves them first-class rather than as a special case.
- **Sub-key identity is `(query-id, args)` under value equality.** Note what that
  buys you: a *freshly allocated* map or vector is not a problem. Two structurally
  equal persistent values are one cache key, so rebuilding `{:scope :all}` inside the
  query on every render hits the same entry every time, and you do not have to hoist
  it into a `def` or memoize it to be safe. Sorting the same items into the same order
  is the same story — `=` compares sequential values by their contents, so a freshly
  sorted seq is the key the last one was, at the cost of walking it. Two things do
  thrash the index. An argument whose **value** genuinely moved is a different key, and
  correctly so: a timestamp folded into the query, or a sort order that really changed.
  And an argument carrying **reference** identity rather than value identity — a
  function, a JS object or array, a host object — is unequal to itself between renders,
  because for those `=` is identity. Documented, programmer-trusted, not policed.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| A boundary doesn't re-render even though something on screen should have changed | Its props still compare `=` to last render and it made no read of its own that moved — the default bail-out (HD-028) is doing exactly what it's for | Read the changing value with `sub` inside the boundary that displays it, or thread it down as a prop so the value actually differs there |
| A boundary re-renders every time though its props look unchanged | A prop carries reference identity rather than value identity — an inline function literal, a JS object or array, a host object — so `=` correctly says unequal | Hoist the function, or convert the value to a persistent one. Freshness is not the problem: two structurally equal persistent values are `=` however recently they were built |
| One cell changes and 300 boundaries re-render | The read lives too high in the tree | Push the read down into the boundary that displays it |
| One value changes and a whole subtree re-renders with it | The value is read in an ancestor and passed down as a prop. Nothing is *lost* — the reading ancestor re-renders with the new value, and every boundary the value actually reaches has unequal props at that hop, so the default bail-out (HD-028) does not catch it there. Coarse invalidation, not missed invalidation | Read at the point of use, so the boundary that displays the value is the boundary that invalidates |
| A read after the render throws, naming the query | A handler closure, a stashed lazy seq, or a `delay` deferred a `sub` past the render | Read during the render and close over the value; the loud error is the alternative to a silently frozen edge |
| Index thrash, subscriptions constantly re-created | Query args that are not *value*-stable — a folded-in timestamp, a sort order that really changed, or a JS object or function carrying reference identity | Allocation is fine; two equal persistent values are one key however freshly built. Make the args equal under `=` between renders |
| A body's side effect fires twice | StrictMode double-invoke | Bodies are pure; move the effect out |

## When not to use a boundary

Not every function needs to be a view. If a piece of markup has no reads of its own
and always re-renders with its parent anyway, a plain function is cheaper and
simpler — no element, no identity, no index membership. Boundaries are for
*re-render granularity*. Reach for one when you want something to update
independently, not because the markup got long.

## Not settled yet

| Question | Status |
|---|---|
| `sub` and `defview` spellings | Working names; [authoring.md](../authoring.md) holds all declaration spellings unfrozen until the freeze |
| The bar | The read surface is ruled, but the ship-bar rows (mount and bulk ≤ 1.0× Reagent, HD-012) are not yet taken; the programme can still end null on them |
| Whether the value-equality bail-out stays the **default** | **An open operator ruling.** HD-028 rules it the default and the runtime implements it, but the wrapper is what carries the R=0 shell across the 1 KB retained-heap line (994/992 B without it, 1,099.5/1,097 B with it), so HD-028's own fallback — the same comparator as an explicit boundary-level opt-in, with HD-006 restored as the default — is on the table. This page teaches the default because that is what is landed |
| The dev warning for an unkeyed seq — its id and whether it is dev-only | **Not addressed** beyond HD-016's "dev warning", and nothing mints one: what a reader sees today is React's own key warning |

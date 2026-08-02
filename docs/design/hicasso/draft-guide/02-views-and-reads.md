# Views and reads

> **Draft ahead of the product artefact.** This page teaches the landed surface —
> ruled in [decisions.md](../decisions.md) (HD-001…HD-027), witnessed by the bench
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

A bare seq of boundary children needs keys, and you get a dev warning if you forget.
The `for`-lowering sugar that would derive the key from the binding is **not v0** —
you write `:key` yourself. Putting a plain `defn` in head position — `[badge {...}]`
where `badge` was never minted by `defview` — is a loud error rather than a silent
embedding.

### The component ABI

HD-016 pins this table, and the keyed insert/delete/reorder witness enforces it.

| Head | Props | Children | `:key` | `:ref` |
|---|---|---|---|---|
| Native tag — `[:div …]` | attribute map | trailing forms | `:key` in the attribute map | callback ref, legal |
| Hicasso view — `[todo-row …]` | one props map | trailing forms, arriving as `(:children props)` | in the props map, **extracted before your body sees props** | not a v0 surface — use ids |
| Fragment — `[:<> …]` | — | trailing forms | on the fragment's props map | — |
| Foreign — `defhost` or `[:>]` | converted per declaration | hiccup children become elements | `:key` in props | callback ref, legal |

Children arrive realized and predictably flattened: nested and lazy sequences are
realized once and flattened one level, `nil` and `false` render nothing, `true` is an
error, and an existing React element is a legal child anywhere. A view may return
`nil`, a single root, or a fragment.

`:key` never reaches your body. That is React's contract, not a Hicasso choice, and
pretending otherwise would be the kind of leaky convenience that costs you a day
when it finally bites.

### Bodies are pure and re-runnable

React StrictMode runs your body twice in development. That is fine — bodies are pure
by contract. Anything that would break under a second run (mutating a captured atom,
kicking off a fetch, counting renders) does not belong in a body.

### There is no automatic memoization

React semantics stand: a child may render because its parent did. Hicasso adds **no
default `=` or argv comparison** (HD-006).

If you are coming from Reagent this is the change most likely to surprise you.
Reagent compares argv and skips; Hicasso does not, because every default comparison
is a cost every render pays whether it helps or not. Narrow updates come from
**where you put your boundaries**, and `React.memo` is available as an explicit
opt-out when you have measured a reason.

One corollary is worth stating outright, because the opposite is the thing people
worry about. Reading a subscription high in the tree and passing the value down as a
prop does **not** lose an update. The boundary that reads is the boundary that
invalidates; it re-renders with the new value, passes it down, and — precisely
because nothing is memoized by default — every descendant re-renders with it. The
value arrives. What you have bought yourself is invalidation that is **too coarse**,
never invalidation that is missing. Moving the read down is a granularity fix, not a
correctness fix, and if you go looking for a lost update you will not find one.

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
  it into a `def` or memoize it to be safe. What thrashes the index is an argument
  whose **value** changes when nothing meaningful did — a re-sorted seq, a timestamp
  folded into the query, a JS object or a function, which carry reference identity
  rather than value identity. Documented, programmer-trusted, not policed.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| A child re-renders whenever its parent does | Expected — there is no default memoization (HD-006) | Move the boundary, or apply `React.memo` where you measured a reason |
| One cell changes and 300 boundaries re-render | The read lives too high in the tree | Push the read down into the boundary that displays it |
| One value changes and a whole subtree re-renders with it | The value is read in an ancestor and passed down as a prop. Nothing is *lost* — the reading ancestor re-renders with the new value and, there being no default memoization, so does everything under it. Coarse invalidation, not missed invalidation | Read at the point of use, so the boundary that displays the value is the boundary that invalidates |
| A read after the render throws, naming the query | A handler closure, a stashed lazy seq, or a `delay` deferred a `sub` past the render | Read during the render and close over the value; the loud error is the alternative to a silently frozen edge |
| Index thrash, subscriptions constantly re-created | Query args that are not *value*-stable — a re-sorted seq, a folded-in timestamp, a JS object or a function | Allocation is fine; two equal persistent values are one key. Make the args equal under `=` between renders |
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
| The dev warning for an unkeyed seq — its id and whether it is dev-only | **Not addressed** beyond "dev warning" |

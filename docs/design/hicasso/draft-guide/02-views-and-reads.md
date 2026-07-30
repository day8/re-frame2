# Views and reads

> **Pre-implementation draft — Hicasso does not exist yet.** This page describes the
> *designed* surface so it can be read before it is built. Spellings marked
> **[unfrozen]** are placeholders that will change. The whole tree is disposable: it
> is rewritten after the P2 fork ruling, against a real implementation. Normative
> source: [decisions.md](../decisions.md) (HD-001…HD-021).

A Hicasso view is a function from a props map to hiccup, which reads whatever
subscriptions it needs along the way. That much is settled and has been since the
charter.

**How the reads are spelled is not.** HD-002 has two candidate surfaces in the ring
and a measurement scheduled to choose between them. This page teaches both, because
that is the honest state of the design — and because if you skip to one of them you
will pick the wrong one about half the time.

Everything before the fork applies to both.

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
tree, it has no boundary of its own, and — this is the part that matters for
performance — any subscription it reads donates that read *upward* to the enclosing
boundary. Helpers are free; they cost nothing and buy nothing.

One rule, one visible distinction. This is deliberate: re-render granularity is a
thing you should be able to see by reading the source, and Reagent's `^{:key}`
metadata folklore is deleted along with it. Keys go in the props map:

```clojure
(defview todo-list [_]
  [:ul
   (for [id (sub [:todo/visible-ids])]     ;; read surface — see the fork below
     [todo-row {:key id :id id}])])
```

A bare seq of boundary children needs keys, and you get a dev warning if you forget.
The `for`-lowering sugar that would derive the key from the binding is **not v0** —
you write `:key` yourself. A plain function in head position (`[todo-row {...}]`
where `todo-row` is not a view) is a loud error rather than a silent embedding.

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

## The fork: how a view reads a subscription

HD-002 ranks three tiers. One of them is not a product surface at all — **scalar
per-read hooks** (one `useSyncExternalStore` per read, the raw UIx spine) are a
measurement control only. They can't be the product: N reads means N hooks, which
blows HD-020's ≤2-hook budget on the very first realistic view, and React's hook
rules forbid reading conditionally.

That leaves two real candidates, and this guide does not pick between them, because
**HD-002 is a measurement and the measurement has not run.**

### Surface A — grouped `use-subs` (the current default)

One hook, at the top of the body, receiving every query the body needs. The hook
count and order are fixed; the *query values* are free to vary.

```clojure
(ns todo.views
  (:require [re-frame.hicasso :as h :refer [defview use-subs]]))

(defview todo-row [{:keys [id]}]
  (let [{:keys [todo editing? draft]}
        (use-subs {:todo     [:todo/by-id id]
                   :editing? [:todo.ui/editing? id]
                   :draft    [:todo.ui/draft id]})]
    [:li
     [:span (:title todo)]
     [:button {:on-click [:todo/toggle id]} "✓"]
     (when editing?
       [:input {:value draft :on-input [:todo.ui/edit id ::h/value]}])]))
```

`use-subs` **[unfrozen]** takes a map of names to query vectors and returns a map of
names to values. You destructure it and use plain locals for the rest of the body.

**Status.** This is the **product default** today. It is the only budget-compliant
surface with a fixed hook count, and HD-002 requires it to be dogfooded from the
start of P1 rather than held in reserve — precisely so that if the challenger loses,
the fallback is a surface that has already been scored, not one discovered mid-clock.

**What it costs you.** Reads sit at fixed sites, so conditional reads need a
different shape. Two moves cover it:

```clojure
;; 1. Conditionally-constructed query values, at a fixed site.
(defview article [{:keys [id draft?]}]
  (let [{:keys [body]} (use-subs {:body (if draft?
                                          [:article/draft-body id]
                                          [:article/published-body id])})]
    [:article body]))

;; 2. A conditional child boundary — the read moves into the child,
;;    which only exists when the condition holds.
(defview article-comments [{:keys [id]}]
  (let [{:keys [comments]} (use-subs {:comments [:comments/for id]})]
    [:ul (for [c comments] [comment-row {:key (:id c) :comment c}])]))

(defview article-page [{:keys [id show-comments?]}]
  [:<>
   [article {:id id}]
   (when show-comments? [article-comments {:id id}])])
```

The second move is the one to internalise: **a conditional read is a conditional
boundary.** Which is often the right design anyway, since it is also where you
wanted the re-render granularity.

There is a sharper consequence for helpers. An inlined helper cannot call `use-subs`
— it would be a hook in a function called conditionally, which React forbids. So
under Surface A, helpers take values as arguments and read nothing:

```clojure
(defn- badge [{:keys [count]}]         ;; plain fn, plain args, no reads
  [:span.badge count])
```

That inference is this guide's, not a ruling; see **Not settled yet**.

### Surface B — the ambient collector (the challenger)

`sub` is an ordinary function call. Call it anywhere in the body: inside a `when`,
inside a `for`, inside an inlined helper. One fixed runtime hook collects the reads,
and the runtime diffs the edge set after the body returns.

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
expression, and that is the entire point of the surface.

**Status.** This is the **challenger**, and HD-002 says it is ridden hardest in P1 —
but it ships only by *winning*. It is the same mechanism class as the per-read
dependency ledger that the predecessor programme measured as its single largest
cost, so it has to earn its place rather than defend it. There is a standing
tripwire that **overrides the clock**: the first time correctness requires a
candidate ledger or generic post-render dependency reconciliation, the collector
dies, however good its numbers look.

**What it buys you.** The census's tier-1 shape is conditional-reads-legal, and most
of the authoring differentiation against raw UIx lives here. Helpers can read.
Loops can read. You write the obvious thing.

### How this gets decided

Four adjudication clauses are pinned before any P1 code is written:

1. a render/commit **ownership state machine** — how a candidate read survives a
   winning render and disappears after an abandoned render, a replay, or a teardown;
2. the **exact allowed edge-diff operation**, distinguished from the ledger class
   that trips the kill rule;
3. **two pre-registered strategy hypotheses**, each counted only by a benchmarked
   commit — tuning passes don't count;
4. the **survival metric** — steady-state allocation slope across warm 1/3/7/20
   reads, plus zero retained per-occurrence objects after commit or teardown.

Three outcomes, and only three. Collector wins and becomes the product mechanism;
collector loses and grouped stays the default; **both fail their gates and the
outcome is null.** There is no fourth read model, and there is no "internal swap" —
either way this is an API choice made on evidence, in public.

The dogfood screen is written in all three renderings — collector, grouped, and raw
UIx — and judged on diff and on the authors' preference. Ergonomics is half the
verdict, not a tiebreaker.

## Reads that are the same either way

Whichever surface wins:

- **Reading outside a render is an error.** There is no `@`-anywhere. Handler and
  utility code uses `rf/subscribe-once`, which is a one-shot read that retains no
  reactive handle.
- **Framework subscriptions read identically to your own** — machine tags, resource
  and mutation state, route identity. They are 27% of the census's read traffic, so
  the index serves them first-class rather than as a special case.
- **Sub-key identity is `(query-id, args)` under value equality.** Building a fresh
  map as a query argument on every render will thrash the index. Documented,
  programmer-trusted, not policed.

## Troubleshooting

No Hicasso error ids exist yet; this table names mechanisms.

| Symptom | What went wrong | Fix |
|---|---|---|
| A child re-renders whenever its parent does | Expected — there is no default memoization (HD-006) | Move the boundary, or apply `React.memo` where you measured a reason |
| One cell changes and 300 boundaries re-render | The read lives too high in the tree | Push the read down into the boundary that displays it |
| Nothing re-renders when app-db changes | The value was passed down as a prop instead of read where it is used | Read at the point of use |
| Hook-order error in a helper | Surface A only: a hook in a conditionally-called function | Helpers take values as arguments; keep `use-subs` in view bodies |
| Index thrash, subscriptions constantly re-created | Unstable query args — a fresh map or vector each render | Pass values with stable identity under `=` |
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
| Which read surface ships | **Open by design.** HD-002 is decided by P1 measurement; three outcomes, including null |
| `use-subs` and `sub` spellings | Pre-declared working names; [authoring.md](../authoring.md) holds all declaration spellings unfrozen until the tournament |
| Does `use-subs` accept anything but a map — a vector of queries, a single query? | **Not addressed.** Only the map form appears in the record |
| Can an inlined helper read under Surface A? | **This guide's inference is no** (React hook rules), and helpers therefore take values as arguments. The record states the consequence only for the collector case — HD-016 calls helper-donated reads "collector-contingent" — and never spells out the grouped-surface answer |
| `defview`'s own spelling and whether a props-map argument is required | Working name; the single-props-map body signature is ruled by HD-016 |
| The dev warning for an unkeyed seq — its id and whether it is dev-only | **Not addressed** beyond "dev warning" |

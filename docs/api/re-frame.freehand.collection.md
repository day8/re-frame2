# re-frame.freehand.collection

!!! warning "Pre-alpha — `day8/re-frame2-freehand` is not published"

    Freehand ships inside the re-frame2 monorepo and is **not published to
    Clojars**, and there is no date at which it will be. You resolve it with
    `:local/root` from a checkout — see
    [Install](../core/freehand/get-running/install.md). The public surface is deliberately
    still open: verbs can change while we learn from real apps.

`re-frame.freehand.collection` is the **fixed-size virtual collection**
(EP-0036; artefact `day8/re-frame2-freehand`, conventionally aliased `coll`) —
and the interesting question about it is not what it renders but what it does
*not*.

A list of ten thousand rows is the ordinary application shape that breaks a
naive substrate three separate ways: it mounts ten thousand boundaries, it
subscribes ten thousand times, and — once somebody adds virtualization by hand —
it grows a second state system for the scroll position, loses keyboard focus
every time the focused row leaves the window, and lies to a screen reader about
how many rows there are. None of those is inherent, and this is the surface that
says so.

Everything on this page is **advanced** tier. Fixed-size virtualization is
opt-in scalability machinery an ordinary list never needs: reach for it when the
problem appears.

```clojure
(:require [re-frame.freehand :as v]
          [re-frame.freehand.collection :as coll])

[coll/virtual-list
 {:id              "inbox"
  :row-keys        (v/sub [:inbox/ids])
  :row-extent      32
  :viewport-extent 640
  :scroll-offset   (v/sub [:inbox/scroll])
  :active-index    (v/sub [:inbox/active])
  :on-scroll       [:inbox/scrolled]
  :on-key          [:inbox/key-pressed]
  :on-activate     [:inbox/opened]
  :row             (v/render-fn [id _index _total] [inbox-row {:id id}])}]
```

and `inbox-row` is an ordinary declared view that reads its own item:

```clojure
(v/defview inbox-row
  {:props [:map [:id :some]]}
  [{:keys [id]}]
  (let [m (v/sub [:inbox/message id])]
    [:span (:subject m)]))
```

That is the whole port of a re-com `v-box` of rows, and it is short because the
control asks for values and emits intent, exactly like every other props-only
view. It owns no record, so it needs no `:control` address, no generation and no
release.

## Two layers, because virtualization is not a widget

[`virtual-collection`](#virtual-collection) is the **engine** and it is
semantic-neutral: it owns the scroll host, the full-height canvas, the keyed
positioned row shells and the window arithmetic, and it names no ARIA role at
all. Its row slot receives the row's **key**, its **absolute index** and the
collection's **total** — the three facts virtualization makes hard to state
honestly — and what those facts are spelled *as* is yours.

[`virtual-list`](#virtual-list) is the listbox built on it: `role="listbox"`,
options, selection, `aria-activedescendant`, and nothing else. It contains no
second window arithmetic and no second scroll host.

The split is arithmetic against evidence rather than anticipation. W3C's own
listbox pattern sends a collection of *interactive* rows to the grid pattern,
whose cell and focus contract is materially different — so a virtual list
hard-coded to `option` cannot host an editing grid without either a second
engine or dishonest semantics. Mature virtualizers split at exactly this line;
so does this.

What is deliberately **not** here: variable row heights, a columns model,
sorting, a grid framework, and any `:semantics` option pretending to implement
whole ARIA interaction patterns from a keyword.

## The five things it refuses

**A second state system.** The scroll offset the control renders from is your
`:scroll-offset` prop and nothing else. `:on-scroll` carries `::v/scroll-top`, so
the host's `scrollTop` reaches an ordinary event and lands in app-db, where a
tool can read it, an epoch can carry it and a snapshot can restore it. There is
no host slot, no ref, no local atom and no controller record.

**Row content in its own props.** The control never receives an item. It receives
`:row-keys` — the ordered vector of identities — and a `:row` render-fn whose
body mounts your declared row view. So a row's content is read by the row's *own*
boundary and an edit to one row invalidates one boundary. Handing the control a
vector of item maps would make every keystroke anywhere in the collection publish
a new vector and re-render the whole visible window; that spelling is not
discouraged here, there is nowhere to put it.

**Moving DOM focus to a row.** The viewport is the focus holder and the active
row is named by `aria-activedescendant`. Roving focus is the pattern
virtualization breaks: the focused element is unmounted the moment it scrolls out
of the window, focus falls to `<body>`, and the next keystroke goes nowhere. Here
the element that holds focus is the one element that is never windowed.

**Lying about the size.** Every rendered row carries `aria-posinset` for its true
absolute position and `aria-setsize` for the true total, and the canvas carries
the true full extent. The DOM contains the window; the accessibility tree and the
scrollbar state the collection.

**Measuring anything.** Rows are a fixed extent you supply. There is no
measurement pass, no `ResizeObserver`, no dynamic window and no cache of measured
heights — which is why [`window`](#window) is arithmetic rather than a scheduler.

## The controls

### `virtual-collection`

```clojure
[coll/virtual-collection
 {:row-keys        (v/sub [:grid/ids])
  :row-extent      28
  :viewport-extent 560
  :scroll-offset   (v/sub [:grid/scroll])
  :on-scroll       [:grid/scrolled]
  :attrs           {:role "grid" :aria-rowcount (v/sub [:grid/total])}
  :row             (v/render-fn [id index total] [grid-row {:id id :index index :total total}])}]
```

The virtualization **engine**: a scroll host, a canvas of the collection's full
height, and the keyed positioned shells of the visible window.

| prop | |
|---|---|
| `:row-keys` | **required** — the ordered vector of stable item identities |
| `:row-extent` | **required** — the fixed row height, in pixels |
| `:viewport-extent` | **required** — the scroll host's height, in pixels |
| `:scroll-offset` | **required** — the *controlled* scroll position |
| `:row` | **required** — a `v/render-fn` of `[row-key index total]` |
| `:on-scroll` | **required** — the scroll intent; `::v/scroll-top` is appended |
| `:overscan` | optional — rows rendered beyond each edge |
| `:attrs` | optional — attributes for the viewport, through `v/spread-safe` |

**`:scroll-offset` is controlled, in both directions.** It is not a cache of what
the host happens to hold. The render picks the window from it, and the engine
lands it on the live viewport before paint whenever the two disagree. Each
direction alone is a bug: render alone leaves a freshly mounted viewport at
`scrollTop` 0 under rows painted at 3072px, and the DOM alone leaves the offset
somewhere no epoch, snapshot or tool can see. Together they make the offset
ordinary application state a `restore-epoch` can move on a *mounted* viewport.
There is no opt-out — the un-reconciled mode is the blank-viewport bug, not a
lighter configuration of the same idea.

**Why it names no role.** Virtualization mechanics and widget semantics are
different layers, and a control fusing them can only ever serve one widget. So
the engine emits mechanics and nothing else: `data-part` names, the positioning,
the canvas extent, and two evidence attributes (`data-window-first`,
`data-window-count`) that state the window the render decided. Three parts —
`viewport`, `canvas`, `row-shell` — and the shell is `role="presentation"`
because it is geometry rather than meaning.

`:attrs` is a map rather than the loose leftovers of the props, and that is the
grammar's doing: a render-slot callback is legal only as a literal call-site prop
value, so a wrapper folding its own props into a `merge` could not also hand this
one a `:row`.

### `virtual-list`

```clojure
[coll/virtual-list
 {:id              "inbox"
  :row-keys        (v/sub [:inbox/ids])
  :row-extent      32
  :viewport-extent 640
  :scroll-offset   (v/sub [:inbox/scroll])
  :active-index    (v/sub [:inbox/active])
  :on-scroll       [:inbox/scrolled]
  :on-key          [:inbox/key-pressed]
  :on-activate     [:inbox/opened]
  :row             (v/render-fn [id _index _total] [inbox-row {:id id}])}]
```

A fixed-extent virtual **listbox** — [`virtual-collection`](#virtual-collection)
wearing `role="listbox"`, and nothing more than that.

| prop | |
|---|---|
| `:id` | **required** — the DOM id the row addresses derive from |
| `:row-keys` | **required** — the ordered vector of stable item identities |
| `:row-extent` | **required** — the fixed row height, in pixels |
| `:viewport-extent` | **required** — the scroll host's height, in pixels |
| `:scroll-offset` | **required** — your scroll position |
| `:row` | **required** — a `v/render-fn` of `[row-key index total]` |
| `:on-scroll` | **required** — the scroll intent; `::v/scroll-top` is appended |
| `:overscan` | optional — rows rendered beyond each edge |
| `:active-index` | optional — the active row, by position |
| `:on-key` | optional — the key intent; `::v/key` is appended |
| `:on-activate` | optional — the row intent; the row's key is appended |
| anything else | forwarded to the scroll host through `v/spread-safe` |

The emphasis is on how *little* it is. It runs no window arithmetic, owns no
scroll host, positions nothing and measures nothing — all of that is the
engine's. What it adds is exactly the listbox pattern: `role="listbox"` and
`tabindex 0` on the engine's viewport, `option` rows carrying the accessible
position and the selection, `aria-activedescendant`, and a key intent.

Four semantic parts under one `data-component` scope and no others: `viewport`,
`canvas` and `row-shell` are the engine's, `row` is this layer's `option`.
Everything inside a row is yours — the control emits no text, no cell, no divider
and no chrome, because a list owning its row markup would be un-adaptable exactly
where every design system differs.

**Reading it back.** The DOM holds the window; `aria-setsize` and the canvas
height hold the collection.
`(count (find-all tree #(= "row" (:data-part (attrs %)))))` is therefore the
deterministic count of rendered rows — a fact a test asserts by equality, with no
timing and no instrumentation — and it is equal to `(:count (window …))` by
construction.

## The arithmetic

Both of these are pure functions of scalars, which is where the correctness
lives. Neither schedules, measures, throttles, observes a resize or owns a frame.

### `window`

```clojure
(coll/window {:item-count 10000 :row-extent 32
              :viewport-extent 640 :scroll-offset 0 :overscan 4})
;=> {:first 0 :count 24 :extent 320000}
```

The visible window over a fixed-extent collection — a pure function of five
integers, answering three.

| in | |
|---|---|
| `:item-count` | how many items the collection holds |
| `:row-extent` | the fixed height of one row, in pixels |
| `:viewport-extent` | the height of the scroll host, in pixels |
| `:scroll-offset` | how far the host is scrolled |
| `:overscan` | rows to render beyond each edge; optional, default 0 |

| out | |
|---|---|
| `:first` | the absolute index of the first rendered row |
| `:count` | how many rows are rendered |
| `:extent` | the full scrollable height, `item-count * row-extent` |

**`:count` does not depend on `:item-count`.** It is bounded above by
`ceil(viewport/extent) + 1 + 2*overscan` for every collection size, which is the
whole cost claim of the control stated as arithmetic: ten items and ten million
items render the same number of rows, so the work per frame is a property of the
viewport rather than of the data.

**Total over nonsense.** A negative or absent count, extent, viewport or offset
is read as its floor rather than throwing, an offset beyond the end is clamped to
the last full screen, and an empty collection or a zero-height viewport answers
`:count 0`. A window is a fact about how much fits, and there is no arrangement
of integers for which the honest answer is an exception.

### `reveal-offset`

```clojure
(coll/reveal-offset {:item-count 10000 :row-extent 32
                     :viewport-extent 640 :scroll-offset 0} 25)
;=> 192
```

The smallest scroll offset that puts a row **wholly** inside the viewport — the
arithmetic behind "the keyboard moved past the edge, so the list scrolled".

A row already whole on screen answers the *current* offset unchanged, which is
what stops arrow-key navigation inside the window from jerking the list. A row
above the window is brought to the top edge, a row below it to the bottom edge,
and the answer is clamped into the scrollable range like any other offset.

It is a function rather than a behaviour of the control because the decision is
yours: which key moves the active row, whether it wraps, and whether moving it
scrolls at all are policy, and a control that owned them would be a keyboard
framework. Your handler calls this against committed state and returns the new
offset in the same event as the new active index, so the two settle as one epoch:

```clojure
(rf/reg-event :inbox/key-pressed
  (fn [{:keys [db]} [_ key]]
    (let [geom  {:item-count (count (:inbox/ids db)) :row-extent 32
                 :viewport-extent 640 :scroll-offset (:inbox/scroll db)}
          next  (case key "ArrowDown" (inc (:inbox/active db))
                          "ArrowUp"   (dec (:inbox/active db))
                          (:inbox/active db))]
      {:db (assoc db :inbox/active  next
                     :inbox/scroll  (coll/reveal-offset geom next))})))
```

## Not a supported surface

Three further vars are published for technical reasons only and are **not** part
of the supported surface. An application or tool must not depend on them; they
may change or vanish without notice.

- **`virtual-row`** — [`virtual-list`](#virtual-list)'s own `option` row. It is a
  declared child rather than markup inlined into a loop because the compiled
  grammar refuses a handler capturing a loop binding, and a per-row committed
  event site needs a per-row instance. You reach it only through `:row`.
- **`row-dom-id`** — the positional DOM address of a rendered row. The id scheme
  should stay replaceable; the supported contract is the *rendered relationship*
  — the listbox owns `aria-activedescendant`, and each mounted option owns its
  id, `aria-posinset` and `aria-setsize`. A test wanting the active row should
  assert that relationship, not call this.
- **`reconcile-scroll`** — the engine's guarded `:layout` behavior, which writes
  `:scroll-offset` onto the viewport before paint and only when the node
  disagrees, so an ordinary user scroll (where app-db is merely trailing the DOM)
  moves no host state at all. [`virtual-collection`](#virtual-collection)
  attaches it; nothing else does.

## Related

- [`re-frame.freehand.controls`](re-frame.freehand.controls.md) — the kit's form
  controls.
- [`re-frame.freehand.splitter`](re-frame.freehand.splitter.md) — the kit's
  pointer control.
- [`re-frame.freehand`](re-frame.freehand.md) — the one public door.
- [Spec 004 §Controller state is ordinary frame
  data](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md) — the
  normative contract.

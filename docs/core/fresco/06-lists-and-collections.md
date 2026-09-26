# Lists and collections

Rendering a collection comes down to two decisions: which value identifies each
row, and which view reads each subscription. Stable keys protect row identity;
where the reads sit controls how much work an update causes.

## Use stable domain keys

Put `:key` in each child's props map:

```clojure
(ns app.todos
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]))

(rf/reg-sub :todo/visible-ids {:inputs [[:todo/visible]]}
  (fn [[todos] _]
    (mapv :id todos)))

(h/defview todo-list [_]
  [:ul.todo-list
   (for [id (h/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

Use an identity from the domain, such as the todo's id, rather than the row's
position. React uses the key to decide whether a child before and after an
update is the same child. With index keys, adding a todo at the front renames
every row, so focus, selection, browser state and animation move to the wrong
todo. With domain ids, existing rows keep their identity and equal-props
bail-outs can skip their bodies.

!!! warning "Do not key by index or by the complete entity"
    An index changes meaning after an insertion or reorder.

    React coerces every key to a string, so keying by the whole todo map keys
    the child by its content. Edit the todo and the child remounts, losing
    focus, scroll position, and any presence retention. That applies to every
    non-primitive key.

    A foreign JS object is worse: every one coerces to the same
    `[object Object]`, so distinct children collapse onto one key and React
    reconciles them as the same child.

    Strings, numbers, keywords, UUIDs, and symbols are valid keys.
    Collections, JS objects, dates, booleans, and functions are not.

A missing key normally produces React's own development warning. Fresco adds
development-only warnings, printed once per site, for view children
(`:rf.warning/fresco-entity-key`, `:rf.warning/fresco-missing-key`). Native
tags are not checked, so `[:li {:key {:id id}} …]` inside a `for` passes
silently.

??? info "For readers coming from Reagent"
    Fresco does not read `^{:key id}` metadata. Use
    `[todo-row {:key id :id id}]`.

## Write the sequence in child position

A `for` that produces a view's children can be written two ways, and both put
the same elements on the page:

```clojure
;; Prefer this: the sequence sits in child position.
[:ul.todo-list
 (for [id ids]
   [todo-row {:key id :id id}])]

;; This splices the sequence away before Fresco sees it.
(into [:ul.todo-list]
      (for [id ids]
        [todo-row {:key id :id id}]))
```

Prefer the first. Fresco checks keys only while it walks a sequence in child
position. `into` turns the sequence into ordinary vector children, so neither
Fresco's key warnings nor React's missing-key warning run. Use `into` when you
assemble one children vector from several pieces, knowing the keys inside are
no longer checked.

Produce the sequence with `for` or `map`, not `mapv`. A vector of rows in child
position reads as Hiccup whose head is a vector and raises
`:rf.error/fresco-bad-head`, or `:rf.error/fresco-empty-vector` when the list is
empty.

## Choose where rows read

Four collection shapes cover most workloads:

| Shape | Read placement | Good fit | Main cost |
| --- | --- | --- | --- |
| Fine | each row reads its entity | sparse independent updates | one retained read per mounted row |
| Coarse | parent reads one display model | cheap mount and bulk replacement | every change recomputes the model and compares every row's props |
| Chunked | one display-model read per block | large mixed sparse/bulk workloads | insert/reorder can move ids between chunks |
| Windowed | visible rows read themselves | collections too large to keep in the DOM | the host owns scrolling; focus, search, print, and accessibility need testing |

Start with fine reads. Change shape only when a profile identifies a specific
mount, recomputation, comparison, or DOM cost. Chunked and windowed lists are
covered under [Advanced](#advanced).

## Fine-grained rows

The list above reads only the ordered ids. Each row reads its own todo:

```clojure
(rf/reg-sub :todo/by-id
  (fn [db [_ id]]
    (get-in db [:todos id])))

(h/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (h/sub [:todo/by-id id])]
    [:li {:class (when done? "done")}
     [:input {:type      :checkbox
              :checked   done?
              :on-change [:todo/toggle id]}]
     [:span title]
     [:button {:on-click [:todo/delete id]} "Delete"]]))
```

Renaming one todo leaves the id list equal, so only that row's subscription
changes and only that row's body runs. Work scales with changed rows rather
than mounted rows.

The cost is one retained read per mounted row, which is normally fine for
hundreds of rows.

## Coarse display model

A parent can subscribe to one vector shaped for rendering and pass each row as
props. `:todo/visible` already is one:

```clojure
(h/defview todo-row [{:keys [todo]}]
  (let [{:keys [id title done?]} todo]
    [:li {:class (when done? "done")}
     [:input {:type      :checkbox
              :checked   done?
              :on-change [:todo/toggle id]}]
     [:span title]
     [:button {:on-click [:todo/delete id]} "Delete"]]))

(h/defview todo-list [_]
  [:ul.todo-list
   (for [todo (h/sub [:todo/visible])]
     [todo-row {:key (:id todo) :todo todo}])])
```

The list retains one subscription. Any change, sparse or bulk, recomputes that
one model and makes the parent compare props for every row. Rows whose todo
map is unchanged still skip their bodies; the extra cost is the recomputation
and one comparison per row.

Keep row props to what the row displays. Persistent maps compare with `=`;
fresh closures and JS objects compare by identity. A field such as
`:updated-at` that the row never renders defeats the bail-out for no benefit,
so `select-keys` the displayed fields when the entity carries more.

## Avoid large oscillating read sets

A view's dependency set is exactly the reads made by its latest body. If a
branch or filter changes membership, the view replaces the whole set.

A parent that reads hundreds of row subscriptions under a filter can therefore
unsubscribe and resubscribe hundreds of dependencies on each filter change.
Push reads into row views so each set stays small, or use one coarse
subscription whose identity does not churn. Xray reports the reads attached to
each view and how often they change.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| React warns about a missing key | A sequence member has no `:key` in its props map | Put `:key` in every sequence member's props map; Reagent metadata is not read |
| Editing a row remounts it and reports an entity key | `:rf.warning/fresco-entity-key`; the warning names the child view, the shape at `:key` and the first offending index | Use a stable primitive domain id, not the full row value |
| A list of views passed as another view's children reports `:rf.warning/fresco-missing-key` | The children have no `:key`; Fresco flattens view children before React sees them, so React's own check cannot run | Put `:key` in each child's props map |
| Input state or animation jumps after insertion/reorder | Index keys changed row identity | Key rows by domain id |
| One entity change runs every row body | The read sits too high for a sparse workload, or every row's props changed | Let rows read their own entities, or accept and measure the coarse model |
| A bulk write runs every body despite equal-props bail-outs | Props contain a fresh function/JS object, or fields that change but are not rendered | Use event vectors and persistent values; select only displayed fields |
| Filtering is slow | A large oscillating dependency set, or whole-list recomputation on each change | Give rows stable reads, chunk the model, or move filtering into a subscription |
| A plain function or JS component is rejected as a head | `:rf.error/fresco-bad-head` | Use `h/defview` for Fresco views and `h/defhost` for foreign components |
| Virtualized rows render but are inert or use the wrong frame | The render callback returned raw Hiccup or made a deferred read | Return the row through `h/as-element`; read values before the callback; use `h/event` for callback-produced events |

## When not to tune or virtualize

For a few hundred rows with sparse updates, fine reads are normally enough.
Do not add chunking until a profile shows a comparison sweep worth bounding.

Do not virtualize a collection that users need to search with find-in-page,
print, select in full, or scan with assistive technology, unless the product
provides a replacement for those behaviours. A plain 50-row list is usually
better than a virtualized one.

If typing is slow, measure the controlled-field event path first. The list
shape may not be the bottleneck.

## Advanced

### Chunk the comparison sweep

For a very large list with both sparse and bulk updates, group rows into
fixed-size positional chunks:

```clojure
(rf/reg-sub :todo/chunk
  (fn [db [_ ids]]
    (mapv #(get-in db [:todos %]) ids)))

(h/defview todo-chunk [{:keys [ids]}]
  [:<>
   (for [todo (h/sub [:todo/chunk ids])]
     [todo-row {:key (:id todo) :todo todo}])])

(h/defview todo-list [_]
  [:ul.todo-list
   (for [[i ids]
         (map-indexed vector
                      (partition-all 50 (h/sub [:todo/visible-ids])))]
     [todo-chunk {:key i :ids (vec ids)}])])
```

A sparse update changes one chunk, limiting the props sweep to about 50 rows,
and the list retains one read per chunk instead of one per row.

The chunk key may be positional because a chunk is a positional window, such
as rows 0–49. Rows inside the chunk still use todo ids. An insertion or reorder
can shift ids across chunk boundaries and re-render each affected chunk, which
is an expected bulk cost.

### Window the DOM

For thousands of rows, first ask whether every row needs a DOM node.
Pagination is the simplest window: keep the page number in app-db and
subscribe to one page. For continuous scrolling, declare a virtualizer host and
let it own the visible window:

```clojure
;; Keep npm requires in a .cljs host namespace.
(ns app.todos.virtual
  (:require ["react-virtuoso" :refer [Virtuoso]]
            [re-frame.fresco :as h]
            [app.todos :refer [todo-row]]))

(h/defhost virtual-list Virtuoso)

(h/defview todo-list [_]
  (let [ids (h/sub [:todo/visible-ids])]
    [virtual-list
     {:class            "todo-viewport"
      :total-count      (count ids)
      :compute-item-key (fn [index]
                          (nth ids index))
      :item-content     (fn [index]
                          (h/as-element
                           [todo-row {:id (nth ids index)}]))}]))
```

This uses the fine-grained `todo-row`, which reads its own todo:

- Only visible rows exist, so only their reads are retained.
- The virtualizer calls `:item-content` during its own render, so it must stay
  pure, and it returns a React element through `h/as-element`.
- The outer view reads `ids` and the callback closes over the value. Calling
  `h/sub` inside the callback would be a deferred read, which throws.
- `:compute-item-key` returns the same todo ids ordinary row keys would use.
- Scroll position stays in the host rather than in app-db.

`todo-row` holds a checkbox and a Delete button, so this list also needs the
focused-row pin in [Keep the focused row mounted](#keep-the-focused-row-mounted).
Check the virtualizer against [the three properties
below](#screen-a-virtualizer-before-adopting-it) before copying this
declaration; the declaration shows the host wiring and does not screen the
library.

By default a `defhost` renders only its `:fallback` (or nothing) on the
server. Virtualization also changes find-in-page, select-all, print, and
assistive-technology behaviour. Verify focus and screen-reader behaviour in a
real browser.

### Keep the focused row mounted

When the window scrolls past the row the user is typing in, React unmounts its
node, focus falls to `document.body`, and the next keystroke goes nowhere,
with nothing on screen to show it.

Do not manage focus to fix this. Record which row has focus and ask the
virtualizer to keep rendering that row wherever the window has moved:

- an `:on-focus` intent writes the row's model index into app-db;
- the view reads it back and passes it to the virtualizer as an index to keep
  rendered, at its true offset, off screen;
- the next focus replaces it. Nothing else releases it.

Nothing calls `.focus()` or reads `document.activeElement`. The browser keeps
owning focus; the pin only stops React from deleting the node focus is in.

Do not release the pin on blur. An `:on-blur` handler unmounts the row while
the browser is still moving focus through it, to save one row of DOM.

Check that a virtualizer supports this before choosing it. Keeping a row far
outside the visible window needs an API that decides which indices render —
TanStack Virtual's `rangeExtractor` is one — and a library whose only lever is
an overscan count cannot reach a row hundreds of places away.

### Announce the model's count, not the DOM's

A windowed DOM no longer contains the whole model, so tell the accessibility
tree its real size:

- `:aria-rowcount` on the grid is the model's total, not the number of rows
  rendered;
- `:aria-rowindex` on each row is that row's model index plus one, not its
  position in the window.

Without them a screen reader announces the size of the window: two dozen rows
for a list of ten thousand. A window-relative implementation announces "row 1
of 10,000" for whatever todo is at the top of the window, so assert both
attributes on a row and again after a scroll.

### Screen a virtualizer before adopting it

Three library features decide whether a foreign virtualizer works through one
`h/defhost` declaration:

| Property | Why it matters | What its absence breaks |
| --- | --- | --- |
| The consumer supplies the key | Identity across a scroll is a model fact, and only the consumer knows the model | A slot-keyed wrapper moves focus and caret to a different todo on every scroll |
| Its own wrappers are the consumer's to shape | `role="grid"` owns `role="row"`, and a virtualizer inserts elements between them | The rows stop being the grid's rows and its semantics collapse into a scroll container |
| It can be told to keep a row mounted | React destroys the focused node the moment the window leaves it | Focus is lost mid-interaction on an ordinary scroll |

Packages most often lack the third. A list with nothing focusable in its rows
never needs it, and for that list a library without it is a fair choice.

# Lists and collections

Large collections are governed by two decisions: which value identifies each
row, and which view owns each subscription read. Stable keys protect row
identity; read placement controls the amount of work caused by an update.

## Use stable domain keys

Put `:key` in each child's props map:

```clojure
(ns app.orders
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(rf/reg-sub :orders/visible-ids
  (fn [db _]
    (:order-ids db)))

(h/defview orders-list [_]
  [:ul
   (for [id (h/sub [:orders/visible-ids])]
     [order-row {:key id :id id}])])
```

Use an identity from the domain, not the row's current position. React uses the
key to decide whether a child before and after an update is the same child.
With index keys, inserting one item at the front renames every row, so focus,
selection, local browser state, and animation can move to the wrong entity.
With domain ids, existing rows retain their identity and equal-props bail-outs
can skip their bodies.

!!! warning "Do not key by index or by the complete entity"
    An index changes meaning after insertion or reorder.

    A complete entity changes when the entity is edited. React coerces every
    key to a string, so keying by the entity keys the child by its *content*:
    edit the entity and the child silently remounts, losing focus, scroll
    position, and any presence retention. That hazard applies to every
    non-primitive key.

    A foreign JS object is the sharper case, because every one of them coerces
    to the same `[object Object]` — distinct children collapse onto one key and
    React reconciles them as the same child. A ClojureScript collection coerces
    to its own printed form, so it stays distinct per value; remounting, not
    collision, is what bites there.

    Strings, numbers, keywords, UUIDs, and symbols are valid keys.
    Collections, JS objects, dates, booleans, and functions are not.

Missing keys produce React's own warning in development; Hicasso adds nothing
to it.

Hicasso's own entity-key warning is narrower than the rule above, and it is
worth knowing what it does and does not cover.
`:rf.warning/hicasso-entity-key` fires for a member of a **sequence** whose head
is a **view boundary** — an `h/defview` head, as in `[order-row {:key …}]` —
and whose key is none of string, number, keyword, UUID, or symbol. It names the
child head, the shape it found at `:key`, and the first offending index; the key
value itself never reaches the console, so a cyclic or throwing value cannot
break the diagnostic. It fires once per site rather than relying on React's
page-lifetime deduplication, and it disappears from production.

It does not fire for a native tag. `[:li {:key {:id id}} …]` inside a `for` is
the same mistake and passes in silence, because Hicasso reads `:key` off a
native tag without classifying it. Treat the rule above as the standard, not the
warning as complete cover.

The warning does, however, run where React never looks: a sequence passed as a
Hicasso view's children is flattened before reaching React, and the flattened
elements already appear validated to it.

??? info "For readers coming from Reagent"
    Hicasso does not read `^{:key id}` metadata. Use
    `[order-row {:key id :id id}]`.

## Write the sequence in child position

A `for` that produces a view's children has two spellings, and they put the same
elements on the page:

```clojure
;; Prefer this: the sequence sits in child position.
[:ul.orders
 (for [id ids]
   [order-row {:key id :id id}])]

;; This splices the sequence away before Hicasso sees it.
(into [:ul.orders]
      (for [id ids]
        [order-row {:key id :id id}]))
```

Prefer the first. A sequence in child position is spliced by Hicasso itself, and
that splice is the only place the entity-key check runs: Hicasso walks the
sequence's members and classifies each `:key` on the way past. `into` produces
an ordinary vector of children before Hicasso is involved, so no sequence
survives for it to walk and the check cannot run. The same applies to React's
missing-key warning, because spliced children arrive as direct arguments, which
React treats as already validated.

So the two spellings render alike and diagnose differently. Reach for `into`
when you are genuinely assembling one children vector out of several pieces —
just know that the keyed members inside it are no longer inspected by anything.

## Choose where rows read

Four collection shapes cover most workloads:

| Shape | Read placement | Good fit | Main cost |
| --- | --- | --- | --- |
| Fine | each row reads its entity | sparse independent updates | one retained read per mounted row |
| Coarse | parent reads one display model | cheap mount and bulk replacement | every change recomputes the model and compares every row's props |
| Chunked | one display-model read per block | large mixed sparse/bulk workloads | insert/reorder can move ids between chunks |
| Windowed | visible rows read themselves | collections that should not all exist in the DOM | host owns scrolling; focus, search, print, and accessibility require testing |

Fine reads are the normal starting point. Change shape only when a profile
identifies a specific mount, recomputation, comparison, or DOM cost.

## Fine-grained rows

The parent reads only the ordered ids. Each row reads its own entity:

```clojure
(rf/reg-sub :order/by-id
  (fn [db [_ id]]
    (get-in db [:orders id])))

(h/defview order-row [{:keys [id]}]
  (let [{:keys [customer status total]}
        (h/sub [:order/by-id id])]
    [:tr
     [:td customer]
     [:td {:class (when (= status :late) "is-late")}
      (name status)]
     [:td total]
     [:td
      [:button {:on-click [:order/expedite id]}
       "Expedite"]]]))

(h/defview orders-table [_]
  [:table.orders
   [:thead
    [:tr [:th "Customer"] [:th "Status"] [:th "Total"] [:th ""]]]
   [:tbody
    (for [id (h/sub [:orders/visible-ids])]
      [order-row {:key id :id id}])]])
```

If one order's status changes and the id list stays equal, only that row's
subscription changes and only that row body runs. Work scales with changed
rows rather than mounted rows.

The cost is one retained read per mounted row. That is normally acceptable for
hundreds of rows. Consider another shape only when profiling shows mount or
bulk-update cost.

## Coarse display model

A parent can subscribe to one vector shaped for rendering and pass each row as
props:

```clojure
(rf/reg-sub :orders/table-rows
  (fn [db _]
    (mapv (fn [id]
            (-> (get-in db [:orders id])
                (select-keys [:id :customer :status :total])))
          (:order-ids db))))

(h/defview order-row [{:keys [row]}]
  (let [{:keys [id customer status total]} row]
    [:tr
     [:td customer]
     [:td {:class (when (= status :late) "is-late")}
      (name status)]
     [:td total]
     [:td
      [:button {:on-click [:order/expedite id]}
       "Expedite"]]]))

(h/defview orders-table [_]
  [:table.orders
   [:thead
    [:tr [:th "Customer"] [:th "Status"] [:th "Total"] [:th ""]]]
   [:tbody
    (for [row (h/sub [:orders/table-rows])]
      [order-row {:key (:id row) :row row}])]])
```

Mount retains one subscription. A bulk replacement recomputes one display
model. A sparse update also recomputes that model and causes the parent to
compare props for every row. Equal row maps still skip unchanged row bodies;
the additional cost is the model recomputation and one comparison per row.

Keep row props value-oriented and limited to what the row displays. Persistent
maps compare with `=`; fresh closures and JS objects compare by identity. A
field such as `:updated-at` that the row never renders can defeat the bail-out
for no benefit.

## Chunk the comparison sweep

For a very large table with both sparse updates and bulk changes, group rows
into fixed-size positional chunks:

```clojure
(rf/reg-sub :orders/chunk
  (fn [db [_ ids]]
    (mapv (fn [id]
            (-> (get-in db [:orders id])
                (select-keys [:id :customer :status :total])))
          ids)))

(h/defview order-chunk [{:keys [ids]}]
  [:<>
   (for [row (h/sub [:orders/chunk ids])]
     [order-row {:key (:id row) :row row}])])

(h/defview orders-table [_]
  [:tbody
   (for [[i ids]
         (map-indexed vector
                      (partition-all 50
                                     (h/sub [:orders/visible-ids])))]
     [order-chunk {:key i :ids (vec ids)}])])
```

A sparse update changes one chunk, limiting the props sweep to about 50 rows.
The table retains one read per chunk instead of one per row. Equal outputs for
untouched chunks stop there.

The chunk key may be positional because a chunk represents a positional window
such as rows 0–49. Entity keys still belong on rows inside the chunk. Insertion
or reorder may shift ids across chunk boundaries and re-render each affected
chunk, which is an expected bulk-shaped cost.

## Window the DOM

For thousands of rows, determine whether all rows need DOM nodes. Pagination is
the simplest window: keep the page in app-db and subscribe to one page. For
continuous scrolling, declare a virtualizer host and let it own the visible
window:

```clojure
;; Keep npm requires in a .cljs host namespace.
(ns app.orders.virtual
  (:require ["react-virtuoso" :refer [Virtuoso]]
            [re-frame.hicasso :as h]))

(h/defhost virtual-list Virtuoso)

(h/defview orders-table [_]
  (let [ids (h/sub [:orders/visible-ids])]
    [virtual-list
     {:class            "orders-viewport"
      :total-count      (count ids)
      :compute-item-key (fn [index]
                          (nth ids index))
      :item-content     (fn [index]
                          (h/as-element
                           [order-row {:id (nth ids index)}]))}]))
```

Important parts of this crossing:

- `order-row` keeps the fine-read shape and reads its own entity. Only the
  visible rows exist, so only their reads are retained.
- `:item-content` is declared as a render callback. It runs during the
  virtualizer's render, must stay pure, and returns a React element through
  `h/as-element`.
- The outer view reads `ids`; the callback closes over that value. Calling
  `h/sub` inside the callback would be a deferred read and is rejected.
- `:compute-item-key` receives the same stable domain ids that ordinary row
  keys would use.
- Scroll position remains host mechanics rather than app-db state.

The host defaults to Client-only on the server. Virtualization also changes
find-in-page, select-all, print, and assistive technology behaviour. The
sections below are what a windowed collection has to get right that an ordinary
list never has to think about, and each needs verifying in a real browser.

## Keep the focused row mounted

**A row that does not exist cannot hold focus.** When the window moves past the
row the user is typing in, React unmounts its node, focus falls to
`document.body`, and the next keystroke goes nowhere. Nothing on screen says so.

The application does not fix this by managing focus. It records which row has
focus and asks the virtualizer to keep rendering that row wherever the window
has got to:

- an `:on-focus` intent writes the row's model index into app-db;
- the view reads it back and hands it to the virtualizer as the index to keep
  rendered, at its true offset, off screen;
- the pin is released by the next focus, and by nothing else.

Nothing calls `.focus()` and nothing reads `document.activeElement`. The
platform goes on owning focus; the pin only stops React from deleting the node
that focus is already in. That is the difference between a recipe and a focus
manager, and it is why this stays a few lines rather than a subsystem.

Do not release the pin on blur. A `:on-blur` companion unmounts the row while
the platform is still moving focus through it, and buys back one row of DOM.

**Not every virtualizer can do this**, so it is worth checking before choosing
one. Reaching a row far outside the visible window needs an API that decides
which indices render — TanStack Virtual's `rangeExtractor` is one such — and a
library whose only lever is an overscan count cannot reach a row hundreds of
places away.

## Announce the model's count, not the DOM's

Once a collection is windowed **the document has stopped being the model**, and
only a value the author writes can carry the model into the accessibility tree.
Two attributes are the whole of it:

- `:aria-rowcount` on the grid is the model's total, not the number of rows
  currently rendered;
- `:aria-rowindex` on each row is that row's model index plus one, not its
  position in the window.

Without them a screen reader announces the size of the window, confidently and
wrongly: two dozen rows for a collection of ten thousand. They are also the pair
a window-relative implementation gets wrong while looking right, announcing
"row 1 of 10,000" for whatever record happens to be at the top of the window —
so assert both on a row and again after a scroll.

## Screen a virtualizer before adopting it

Three properties decide whether a foreign virtualizer can be reached through one
`h/defhost` declaration. They are ordinary library features rather than anything
Hicasso asks for, and a package either has them or does not:

| Property | Why it matters | What its absence breaks |
| --- | --- | --- |
| The consumer supplies the key | Identity across a scroll is a model fact, and only the consumer knows the model | A slot-keyed wrapper moves focus and caret to a different record on every scroll |
| Its own wrappers are the consumer's to shape | `role="grid"` owns `role="row"`, and a virtualizer inserts elements between them | The rows stop being the grid's rows and the table's semantics collapse into a scroll container |
| It can be told to keep a row mounted | React destroys the focused node the moment the window leaves it | Focus is lost mid-interaction, silently, on an ordinary scroll |

The third is the one packages most often lack. A collection with nothing
focusable in its rows never touches that property, and for that screen a
library without it is a fair choice.

## Avoid large oscillating read sets

A view's dependency set is exactly the reads made by its latest body. If a
branch or filter changes membership, the view replaces the complete set.

A parent that reads hundreds of row subscriptions under a filter can therefore
unsubscribe and resubscribe hundreds of dependencies on each filter
keystroke. Push reads into row views so each set stays small, or use one coarse
subscription whose identity does not churn. Xray reports the reads attached to
each view and their churn.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| React warns about a missing key | A sequence member has no `:key` in its props map | Put `:key` in every sequence member's props map; Reagent metadata is not read |
| Editing a row remounts it and reports an entity key | `:rf.warning/hicasso-entity-key` | Use a stable primitive domain id, not the full row value |
| Input state or animation jumps after insertion/reorder | Index keys changed row identity | Key rows by domain id |
| One entity change runs every row body | A sparse workload uses a read placed too high, or row props all changed | Let rows read their own entities, or accept and measure the coarse model |
| A bulk write runs every body despite equal-props memoization | Props contain a fresh function/JS object or fields that change but are not rendered | Use event vectors and persistent values; select only displayed fields |
| Filtering is slow | Large oscillating dependency set or whole-table recomputation on each edit | Give rows stable reads, chunk the model, or move filtering into a subscription |
| A plain function or JS component is rejected as a head | `:rf.error/hicasso-bad-head` | Use `h/defview` for Hicasso views and `h/defhost` for foreign components |
| Virtualized rows render but interactions use the wrong frame or are inert | Render callback returned raw Hiccup or performed a deferred read | Return the row through `h/as-element`; read values before the callback; use `h/event` for callback-produced events |

## When not to tune or virtualize

For a few hundred rows with sparse updates, fine reads are normally enough.
Do not add chunking until a profile shows a comparison sweep worth bounding.

Do not virtualize a collection that users need to search with find-in-page,
print, select in full, or scan with assistive technology unless the product has
an explicit replacement for those behaviours. A plain 50-row settings list is
usually better than a virtualized one.

If typing is slow, first measure the controlled-field event path. The list
shape may not be the bottleneck.

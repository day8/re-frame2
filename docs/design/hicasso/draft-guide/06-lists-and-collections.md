# Lists and collections

A list of twenty rows is easy. At a few hundred or a few thousand, two
choices decide how it behaves: **what identifies each row** (keys), and
**which rows re-render when one row changes** (where you put the reads).

## Keys are identity

Put a stable `:key` in each child's props map. There is no metadata form:

```clojure
(ns app.orders
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(rf/reg-sub :orders/visible-ids
  (fn [db _] (:order-ids db)))

(h/defview orders-list [_]
  [:ul
   (for [id (h/sub [:orders/visible-ids])]
     [order-row {:key id :id id}])])
```

Use a **domain id**, not a position. React uses the key to decide which child
is the same child across renders. Key by index, and an insertion at the top
renames every row: focus, selection, and half-typed input jump with the
index. Key by domain id, and an insertion is one new node; other rows keep
their identity. Value-equality memoization often skips their bodies entirely
([Views and reads](02-views-and-reads.md)).

!!! warning "Never index, never the whole entity"
    An index key breaks under reorder and insertion. A whole-entity key
    breaks under edit: React stringifies a non-primitive key, so an edit
    changes the key and remounts the row. Development warns with
    `:rf.warning/hicasso-entity-key` and points at the child. Strings,
    numbers, keywords, uuids, and symbols are fine; collections, JS objects,
    dates, booleans, and functions are not.

If you omit a key, development emits two warnings: React's, and
`:rf.warning/hicasso-missing-key`. The Hicasso warning names the enclosing
view, the child head, and the index of the first offender. React dedupes its
own warning per parent tag for the life of the page — under a `:ul`, later
lists go silent — so Hicasso fires once per call site. It also covers a case
React misses: a seq passed as a view's children,
`[card {} (for [t toasts] [toast-row {…}])]`. Hicasso flattens that seq into
direct children; React treats the flattened members as already validated and
never warns. Both checks are development-only.

??? info "Coming from Reagent"
    Hicasso does not read `^{:key id}` metadata. Keys live in the props map:
    `[order-row {:key id :id id}]`. A leftover metadata key is a common cause
    of `:rf.warning/hicasso-missing-key`.

## Where the reads live

`h/sub` is legal anywhere in a view body, so you choose *where* the reads
sit. For a collection, that choice decides what an update costs. Four shapes
cover real workloads:

| Shape | Reads | Best at | Cost |
|---|---|---|---|
| **Fine** | each row reads its own entity | sparse, independent updates | one retained read per row |
| **Coarse** | one view-model for the table | cheap mount; bulk replacement | every change recomputes the model and props-compares all rows |
| **Chunked** | one read per block of rows | large mixed workloads | chunk membership shifts on insert/reorder |
| **Windowed** | fine reads, only visible rows exist | collections that should not fully exist in the DOM | host owns scrolling; focus and a11y need checks |

Fine is the default and the right choice for most screens. The rest of this
section walks **one orders table** through all four shapes. A websocket
updates single order statuses; a refresh replaces the whole set.

### Fine: rows read themselves

```clojure
(rf/reg-sub :order/by-id
  (fn [db [_ id]] (get-in db [:orders id])))

(h/defview order-row [{:keys [id]}]
  (let [{:keys [customer status total]} (h/sub [:order/by-id id])]
    [:tr
     [:td customer]
     [:td {:class (when (= status :late) "is-late")} (name status)]
     [:td total]
     [:td [:button {:on-click [:order/expedite id]} "Expedite"]]]))

(h/defview orders-table [_]
  [:table.orders
   [:thead [:tr [:th "Customer"] [:th "Status"] [:th "Total"] [:th ""]]]
   [:tbody
    (for [id (h/sub [:orders/visible-ids])]
      [order-row {:key id :id id}])]])
```

The parent reads only the ids; each row reads its own entity. When one
order's status changes, one subscription changes, one row body runs, and one
cell updates. The parent does not re-render if the ids did not move. Body
work scales with changed rows, not mounted rows
([Performance](18-performance.md)).

The price is retention: a thousand mounted rows hold a thousand reads.
For a few hundred rows that is usually noise. When a profile points at mount
time or a bulk replace-everything, try coarse.

### Coarse: one view-model

The table reads **one** subscription shaped for display. Rows take props:

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
     [:td {:class (when (= status :late) "is-late")} (name status)]
     [:td total]
     [:td [:button {:on-click [:order/expedite id]} "Expedite"]]]))

(h/defview orders-table [_]
  [:table.orders
   [:thead [:tr [:th "Customer"] [:th "Status"] [:th "Total"] [:th ""]]]
   [:tbody
    (for [row (h/sub [:orders/table-rows])]
      [order-row {:key (:id row) :row row}])]])
```

Mount is cheap (one read). A bulk replacement is one recompute. Sparse
updates still work, but differently: the view-model recomputes, the parent
re-renders, and every row gets a props compare. Unchanged rows still
compare `=` and skip their bodies — only the changed row runs. What grew is
the **sweep**: one compare per row, plus the view-model recompute. At a few
hundred rows that is usually fine; at tens of thousands your profile will
name it.

Two rules keep the bail-out honest. Rows must receive **values** — maps from
`select-keys` compare with `=` across renders; a fresh closure does not.
Keep the row map to what the row shows: a `:updated-at` nobody renders
defeats `=` on every touch. Event vectors as data help here
([Events as data](03-events-as-data.md)).

### Chunked: bounding the sweep

For a large table with both sparse updates and bulk operations, chunk the
rows so no single read or props sweep spans the whole table:

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
   (for [[i ids] (map-indexed vector
                              (partition-all 50 (h/sub [:orders/visible-ids])))]
     [order-chunk {:key i :ids (vec ids)}])])
```

A sparse update changes one chunk's output. That chunk re-renders; the
sweep is about 50 compares, not the whole table. Mount retains one read per
block instead of one per row. Untouched chunks re-select cheaply, their
outputs are `=`, and nothing downstream moves.

Chunk by **index** is correct: a chunk is a positional window ("rows
0–49"), not an entity. Entity keys still live on the rows inside. Insertion
or reorder can shift ids across chunk borders and re-render every chunk they
cross — a bulk-shaped cost for a bulk-shaped change.

### Windowed: only the visible rows exist

Past a few thousand rows, ask whether row 8,000 needs a DOM node at all. The
simplest window is pagination: page number in app-db, and
`(h/sub [:orders/page n])` materializes one page. For continuous scrolling,
bring a virtualizer through `h/defhost` and let it own the window:

```clojure
;; A .cljs host namespace — keep JS requires out of .cljc bodies.
(ns app.orders.virtual
  (:require ["react-virtuoso" :refer [Virtuoso]]
            [re-frame.hicasso :as h]))

(h/defhost virtual-list Virtuoso
  {:callbacks {:item-content :render}})

(h/defview orders-table [_]
  (let [ids (h/sub [:orders/visible-ids])]
    [virtual-list
     {:class            "orders-viewport"       ;; height lives in CSS
      :total-count      (count ids)
      :compute-item-key (fn [index] (nth ids index))
      :item-content     (fn [index]
                          (h/as-element
                           [order-row {:id (nth ids index)}]))}]))
```

What each piece does:

- **`order-row` is still the fine-read version** — `{:id id}`, reading its own
  entity. Virtualization is fine reads with a bounded N. Only ~30 rows exist,
  so only ~30 reads are retained; a sparse update still hits one of them.
- **`:item-content` is `:render`** — the library calls it during its own
  render; the body must stay pure. Return hiccup through `h/as-element` so
  React gets a real element. A plain `fn` is fine when the row is a
  `[order-row …]` head: that view resolves the frame where the library
  renders it. Use `h/event` when the callback body itself carries an event
  vector ([Interop](09-interop.md)).
- **Close over `ids`, not over a read.** Call `h/sub` in the view body; the
  callback captures the value. An `h/sub` inside the callback is a deferred
  read and is refused.
- **`:compute-item-key` carries domain ids** across the host. When a
  virtualizer owns the list, hand it the same ids you would put at `:key`.
- Scroll position stays out of app-db. High-rate motion is host mechanics
  ([Ephemeral state](11-ephemeral-state.md)).

The virtualizer defaults to Client-only on the server; see
[SSR and hydration](17-ssr-and-hydration.md). Full `defhost` mechanics are in
[Interop](09-interop.md). Check focus and keyboard behaviour in a browser:
rows that do not exist cannot receive keyboard focus.

## Oscillating read sets

A view's subscriptions are the reads its body just made. When control flow
changes that set, the view re-subscribes the **whole set**, not one key. That
cost scales with the current read count. A small set that oscillates is
fine. A large one is not: a parent that reads hundreds of per-row
subscriptions under a filter that changes membership re-subscribes hundreds
of reads on every filter keystroke.

Fix it structurally. Push per-row reads into row views so each set is small
and stable, and membership churn only costs rows that enter or leave. Or go
coarse, where the set is one read that never churns. Xray shows reads per
view and read-set churn ([Diagnostics](15-diagnostics.md)).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Console warning names your view, a child head, and an index | `:rf.warning/hicasso-missing-key` | Put `:key` in each member's props map. Reagent `^{:key}` metadata is not read |
| Row remounts when you edit it; warning names the child | `:rf.warning/hicasso-entity-key` — key is a value React coerces | Key on a stable id: `{:key (:id row)}` |
| Input state or animation jumps after insert/reorder | Index keys | Key on domain ids |
| One order changes and every row re-renders | Read lives too high for a sparse workload | Fine reads: rows read their own entity; or accept the coarse sweep knowingly |
| Page-wide write runs every row body despite the bail-out | Row props are not `=` — fresh closure, JS object, or dead weight in the map | Event vectors as data; persistent values; `select-keys` to what the row shows |
| Filter typing is heavy on a large list | Large oscillating read set, or a whole-table view-model per keystroke | Rows own their reads, chunk the table, or filter inside the subscription |
| Plain function or JS component in head position refused | `:rf.error/hicasso-bad-head` | Define views with `h/defview`; declare foreign components with `h/defhost` ([Interop](09-interop.md)) |
| Virtualized list renders but rows are inert or mis-framed | Row hiccup returned raw from the render callback | Return through `h/as-element`; if the callback itself carries an event vector, use `h/event` |

## When not to tune a list

- **Under a few hundred rows with sparse updates** — fine is already right.
  Tune when a profile names a cost ([Performance](18-performance.md)).
- **Do not virtualize a list people scan** — find-in-page, select-all, and
  print only see rows that exist. A virtualized 50-row settings list is worse
  than a plain one.
- **Do not pre-chunk without a measured sweep** — the extra layer only pays
  when the profile shows it.
- **If typing is slow**, the problem is usually event volume on a controlled
  field, not list shape —
  [Controlled inputs](04-controlled-inputs.md) and
  [Performance](18-performance.md).

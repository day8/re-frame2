# Lists and collections

Every app has a table that started at twenty rows and now holds two thousand.
At that size, a plain map over the data is no longer the whole answer. Two
decisions govern how a collection behaves from there: **what identifies a row**
(keys) and **which rows find out when a row changes** ([read topology](glossary.md#read-topology)). This
chapter owns both decisions.

> **A key states which row this is. The [read topology](glossary.md#read-topology) states which rows an
> update reaches.**

## Keys are identity

Every list of children needs a `:key`. The key goes **in the props map** —
there is no metadata spelling:

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

The key must be a **stable domain identifier** — the entity's id, not its
position. React uses the key to decide which child is *the same child* across
renders. Key by index, and an insertion at the top renames every row. Row 3's
DOM, focus, selection, and half-typed input now belong to the data that moved
into position 3. Key by domain id, and an insertion is one new node. Every
other row keeps its identity, and the value-equality bail-out
([Views and reads](02-views-and-reads.md)) usually stops its body from
running at all.

!!! warning "Never index, never the entity"
    An index key breaks under reorder and insertion — controlled-input state
    and animations visibly jump rows. A whole-entity key breaks under edit.
    React coerces a non-primitive key to a string, so an edit silently changes
    the row's key and **remounts** the row. Development names that case
    `:rf.warning/hicasso-entity-key`. The warning points at the child and
    tells you to key on a stable identifier. Strings, numbers, keywords,
    uuids and symbols pass; the warning flags collections, JS objects, dates,
    booleans and functions.

Forget a key entirely and development gives you two warnings: React's own, and
`:rf.warning/hicasso-missing-key`. The [Hicasso](glossary.md#hicasso) warning names the enclosing
view, the child head, and the index of the first offender. The second warning
exists because React dedupes its own warning per parent tag for the life of
the page — under a `:ul`, every list after the first is silent. Hicasso's
warning fires once per call site. It also fires at one crossing React cannot
see at all: a seq passed as a *view's* children —
`[card {} (for [t toasts] [toast-row {…}])]`. Hicasso realizes that seq and
flattens it into direct children. React marks the flattened members validated
and never warns about them, so there the Hicasso line is the only signal that
shape gets. Both checks are development-only; a production build carries
neither.

??? info "Coming from Reagent"
    Hicasso does **not read** `^{:key id}` metadata. Keys live in the props
    map: `[order-row {:key id :id id}]`. A carried-over metadata key is the
    most common trigger of `:rf.warning/hicasso-missing-key`, and the message
    says exactly that.

Keys are one half of list identity. The other half is the [read topology](glossary.md#read-topology):
which rows *re-render*. That half is a genuine design choice.

## Choosing a read topology

[`h/sub`](glossary.md#hsub) is legal anywhere in a body, so you choose *where* the reads sit. For
a collection, that position decides what an update costs. Four shapes cover
real workloads:

| Topology | Reads | Best at | Price |
|---|---|---|---|
| **Fine** | each row reads its own entity | sparse, independent updates | one retained read per row; mount and heap grow with row count |
| **Coarse** | one view-model read for the table | cheap mount; bulk replacement | every change recomputes the model and sweeps a props-compare over all rows |
| **Chunked** | one read per block of rows | large mixed workloads | chunk membership shifts on insert and reorder |
| **Windowed / virtualized** | fine reads, but only the visible window exists | collections whose DOM should not exist in full | the host owns scrolling; focus and accessibility need their own checks |

Fine is the shape you already write by default. For most screens it is the
right one. The rest of this section takes **one table** through all four
shapes, so the deltas are visible. The screen is an orders table — customer,
status, total. A websocket pushes status changes for single orders, and a
refresh replaces the whole set.

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

The parent reads only the ids; each row reads its own entity. When one order's
status changes, one subscription's value changes, one row body runs, and one
`<td>` commits. The parent does not re-render, because the ids did not move.
That is the shape you want for sparse edits: **body work scales with
changed rows, not mounted rows** (the narrow-update idea
[Performance](18-performance.md) calls out for list updates).

The price is retention. A thousand mounted rows hold a thousand row reads, and
mount establishes each of them. For a few hundred rows that cost is noise.
When your profile names mount time or a bulk *replace-everything* operation,
fine reads are the wrong shape. Coarse is built for that workload.

### Coarse: one view-model

The delta: the table reads **one** subscription shaped for display. Rows
render from props instead of reading.

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

The whole table retains one read, so mount is cheap. A bulk replacement is one
recompute — the workload this shape is for. Sparse updates still work, but
they travel differently: the view-model recomputes, the parent re-renders,
and every row gets a props compare. Unchanged rows carry `=` row maps, so the
bail-out stops their bodies from running — only the changed row executes.
The cost that grew is the **sweep**: one compare across all rows per update,
plus the view-model recompute itself. At 300 rows the sweep is cheap. At
10,000 rows it is the line item your profile will name.

Two rules keep the bail-out honest here. First, rows must receive **values**.
Persistent maps built with `select-keys` compare `=` across renders; a row
that receives a freshly built closure re-renders on every sweep, which is one
more reason event [intents](glossary.md#intent) are data. Second, the row map should carry only
what the row shows. A `:updated-at` timestamp that nobody renders defeats `=`
on every touch.

### Chunked: bounding the sweep

For large mixed workloads — a big collection with sparse updates *and* bulk
operations — chunk the rows. Then no single read spans the table, and no
sweep spans more than a block:

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

A sparse update now changes one chunk's output. One chunk [boundary](glossary.md#boundary) re-renders,
and the sweep is 50 compares instead of the whole table. Mount retains one
read per block instead of one per row. The equality gate on subscriptions
keeps the untouched chunks quiet: their selects re-run cheaply, their outputs
are `=`, and nothing downstream moves.

Two notes. First, the chunk is keyed by **index**, and that is correct. A
chunk is a positional window, not an entity — "rows 0–49" *is* its identity,
and the entity keys live on the rows inside it. Second, insertion or reorder
shifts ids across chunk borders. Those operations re-render every chunk they
shift through — a bulk-shaped cost for a bulk-shaped change, which is the
trade you accepted.

### Windowed: only the visible rows exist

Past a few thousand rows, ask why the DOM for row 8,000 exists at all. A
**windowed read** materializes only what is visible. The simplest window is
one you already have: pagination. The page number is app-db state, and
`(h/sub [:orders/page n])` is a windowed read whose bounds move at click
rate. When the product wants continuous scrolling instead, bring a
virtualizer through [`h/defhost`](glossary.md#defhost) and let it own the window:

```clojure
;; A .cljs host namespace — JS requires stay out of .cljc bodies.
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

Read the shape closely. Every piece does a job:

- **`order-row` is the fine-read version** — `{:id id}`, reading its own
  entity. Virtualization is fine reads with a bounded N. Only ~30 rows exist,
  so only ~30 reads are retained, and a sparse update still touches exactly
  one of them. The topology you started with was right; the window caps its
  cost.
- **`:item-content` is declared `:render`** — the library calls it during its
  own render, and the body stays pure. The returned hiccup crosses through
  [`h/as-element`](glossary.md#as-element), which lowers it under the frame of the [boundary](glossary.md#boundary) that
  supplied the callback. Intents inside the row later dispatch to the right
  frame, exactly as they would outside the host.
- **The closure closes over `ids`, not over a read.** `(h/sub ...)` ran in the
  body; the callback captures the *value*. A [`h/sub`](glossary.md#hsub) call inside the callback
  would be a deferred read, and the runtime refuses it loudly.
- **`:compute-item-key` carries the keys law across the crossing.** When a
  virtualizer owns the list, it owns item identity too. Hand it the same
  domain id you would have written at `:key`. It is a plain function that
  returns a value, so it crosses by identity, undeclared.
- Scroll position never touches app-db. High-rate motion is the host's own
  mechanics ([Ephemeral state](11-ephemeral-state.md)). Your state sees reads,
  not scrolling.

The virtualizer's [server policy](glossary.md#server-policy) is the host default, Client-only. That
default is what a DOM-measuring component wants;
[chapter 17](17-ssr-and-hydration.md) owns the SSR story.
[Interop](09-interop.md) owns full [`defhost`](glossary.md#defhost) mechanics, including callback
contracts. Also verify focus and keyboard behavior in a browser test. The
keyboard cannot reach rows that do not exist, and that is a product decision,
not a bug.

## Oscillating read sets

One caution applies across all four shapes. A boundary's subscriptions are
exactly the reads its body just made. When control flow changes the body's
read set, the boundary re-subscribes the **whole set**, not the one key that
changed. That reconciliation is proportional to the current read count. A
small set that oscillates is fine. A *large* one is the dangerous case.
Consider a parent that itself reads hundreds of per-row subscriptions, under
a filter that changes which rows are visible. Every keystroke of the filter
then costs a hundreds-wide re-subscribe.

The fix is structural, and you already saw both arms. Either push per-row
reads down into row [boundaries](glossary.md#boundary): each row's set is then small and stable, and
membership churn costs only the rows that actually enter or leave. Or go
coarse, where the set is one read that never churns. Xray shows reads per
boundary and read-set churn directly ([Diagnostics](15-diagnostics.md)), so
you observe this cost instead of guessing at it.

## Troubleshooting

| Symptom | What it is | Fix |
|---|---|---|
| Console warning names your view, a child head, and an index | `:rf.warning/hicasso-missing-key` — a list of children with no `:key` | Put `:key` in each member's props map. Reagent `^{:key}` metadata is not read |
| A row remounts when you edit it; warning names the child | `:rf.warning/hicasso-entity-key` — the key is a value React coerces, so editing changed it | Key on a stable identifier: `{:key (:id row)}` |
| Input state or animation jumps to a different row after insert/reorder | Index keys — position renamed the rows | Key on domain ids |
| One order changes and every row re-renders | The read lives too high — coarse topology where the workload is sparse | Fine reads: rows read their own entity; or accept the sweep knowingly |
| A page-wide write runs every row body despite the bail-out | Row props are not `=` — a fresh closure, a JS object, or dead weight in the row map | Intents as data, persistent values, `select-keys` to what the row shows |
| Filter typing is heavy on a large list | A large oscillating read set in one [boundary](glossary.md#boundary), or a whole-table view-model recomputing per keystroke | Rows own their reads, chunk the table, or move the filter into the subscription |
| A plain function — CLJS or a JS component — in head position is refused | `:rf.error/hicasso-bad-head` — only minted views, native tags, fragments and hosts are heads | Mint views with [`h/defview`](glossary.md#defview); declare foreign components once with [`h/defhost`](glossary.md#defhost) ([Interop](09-interop.md)) |
| Virtualized list renders but rows are inert or mis-framed | Row hiccup returned raw from the render callback | Return it through [`h/as-element`](glossary.md#as-element) so it lowers under the captured frame |

## When not to tune a list

- **Under a few hundred rows with sparse updates**: the fine default is
  already the right topology. Write it and move on. This chapter's deltas
  answer a *measured* cost ([Performance](18-performance.md)); they are not a
  checklist.
- **Do not virtualize a list people scan**: find-in-page, select-all, and
  printing only see rows that exist. A virtualized 50-row settings list is
  strictly worse than a plain one.
- **Do not pre-chunk speculatively**: chunking answers a profiled sweep. The
  extra layer of indirection is justified only by a measured cost.
- **If typing is slow**, the problem is usually event volume in a controlled
  surface, not list topology —
  [Controlled inputs](04-controlled-inputs.md) and
  [Performance](18-performance.md) own that diagnosis.

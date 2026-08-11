(ns re-frame.hicasso.examples.grid.subs
  "THE GRID'S READ TOPOLOGY — the whole of the scaling claim (rf2-hic-078).

  Three subscriptions, and the important one is the shape of the first.

  [[cell]] is parametric on `[row col]`, so a hundred cells are a hundred
  independently-gated cells under one registration. A keystroke writes
  one address; exactly one of the hundred recomputes to a different
  value; exactly one boundary is notified. The other ninety-nine are
  **never notified** — not notified-and-bailed-out, which is a different
  and more expensive thing
  (`docs/design/hicasso/draft-guide/18-performance.md` §Scale the same
  topology to a grid).

  [[dimensions]] is read by the grid body and by the row bodies, and it
  does not move while anybody is typing. That is the second half of the
  topology: the parent bodies read something a keystroke cannot change,
  so a keystroke cannot reach them.

  There is deliberately **no `[::all-cells]`**. The guide's named
  anti-shape is a whole-grid view-model that every keystroke recomputes,
  and this application does not offer one — so a cell CANNOT be written
  to read coarsely without somebody adding a subscription for it. The
  coarse variant the scaling suite measures against is defined in that
  suite, where an anti-pattern belongs: a testbed models proper
  re-frame2 and holds no deliberate bad shapes, while a positive control
  has to be able to make the gate red."
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::cell
  {:doc "One cell's value, by coordinate — one `get-in` into one address."}
  (fn [db [_ row col]] (get-in db [:cells [row col]] "")))

(rf/reg-sub ::dimensions
  {:doc "`{:rows n :cols n}` — what the grid and its rows lay out from.

  Constant for the life of a mount, which is why the layout bodies may
  read it: an edge to a value nothing writes costs one equality check at
  registration and never fires again."}
  (fn [db _] (:dimensions db)))

(rf/reg-sub ::row-total
  {:doc "The sum of one row's cells, as a number.

  The application's one DERIVED read, and it earns its place by being the
  thing a narrow topology has to get right: it depends on `cols` cells,
  so a keystroke anywhere in the row moves it and the row's total
  re-renders. That is `changed rows` in the specification's sense — the
  work follows the data, not the mount — and it is what stops the grid
  from being a witness in which nothing is ever connected to anything."}
  (fn [db [_ row]]
    (let [cols (get-in db [:dimensions :cols] 0)]
      (transduce (comp (map (fn [col] (get-in db [:cells [row col]])))
                       (map (fn [s] (if (seq s) (js/parseInt s 10) 0))))
                 +
                 0
                 (range cols)))))

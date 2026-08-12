(ns re-frame.hicasso.examples.ledger.subs
  "THE LEDGER'S READ TOPOLOGY (rf2-hic-047).

  Five subscriptions, and the shape of the first three is the whole of
  what makes a ten-thousand-row screen behave like a twenty-row one.

  [[record]], [[note]] and [[flagged?]] are all parametric on the row's
  MODEL INDEX, so the mounted rows are independently-gated readers under
  three registrations. A keystroke writes one note address; exactly one
  of the mounted rows recomputes to a different value; exactly one
  boundary is notified. That is the same sentence the grid's subs
  namespace writes, and it is worth writing twice because the number it
  is measured against here is 10,000 rather than 100 — the topology, not
  the virtualizer, is what stops a keystroke from costing the collection.

  **The virtualizer is doing the other half, and the two are
  independent.** Windowing bounds the MOUNTED rows; the read topology
  bounds the rows a change NOTIFIES. A screen with the first and not the
  second re-renders every mounted row on every keystroke; a screen with
  the second and not the first mounts ten thousand DOM subtrees and then
  updates one of them. `ledger.virtualized-dom-cljs-test` measures both
  numbers, at two model sizes, precisely because a reader has to be able
  to tell which claim each one is about.

  There is deliberately **no `[::records]`** and no `[::visible-rows]`.
  The named anti-shape at this scale is a parent that reads the window's
  worth of records and hands them down as props: it re-renders on every
  scroll AND on every keystroke in any visible row, and it puts the whole
  collection one refactor away from the render path. A row here reads its
  own record, so the shape cannot be written without somebody adding a
  subscription for it — and the coarse variant the DOM suite measures
  against is defined in that suite, where an anti-pattern belongs.

  [[window]] is the one read that follows the SCROLL, and only the status
  line has it. That placement is the reason a scroll does not re-run the
  screen's own body: `views/ledger` reads the total and the pinned index,
  neither of which a scroll can move."
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::total
  {:doc "How many records the ledger holds.

  Constant for the life of a mount, which is why the screen's own body
  may read it: an edge to a value nothing writes costs one equality
  check at registration and never fires again."}
  (fn [db _] (count (:records db))))

(rf/reg-sub ::record
  {:doc "One record, by model index — one `nth` into one address."}
  (fn [db [_ index]] (nth (:records db) index nil)))

(rf/reg-sub ::note
  {:doc "One record's note, by model index. `\"\"` and not `nil`, because
  the value goes straight onto a controlled `:value` and an absent value
  there is React's uncontrolled-input warning rather than an empty
  field."}
  (fn [db [_ index]] (get-in db [:notes index] "")))

(rf/reg-sub ::flagged?
  {:doc "Whether one record is flagged, by model index."}
  (fn [db [_ index]] (contains? (:flagged db) index)))

(rf/reg-sub ::pinned-index
  {:doc "The row the virtualizer may not unmount — the one the platform's
  focus is in, or `-1`.

  Read by the screen's own body, so a focus change re-runs it. That is
  once per focus move, and it is the price of the pin; the DOM suite
  counts what it costs the rows (nothing — their props do not move) so
  that the price is a measured number rather than an assurance."}
  (fn [db _] (:focused db -1)))

(rf/reg-sub ::window
  {:doc "`{:from n :to n}` — the window the virtualizer last reported.

  The status line's read and nobody else's. See the namespace docstring:
  this is the one value a scroll moves, and keeping it out of the
  screen's body is what makes a scroll cost the rows that entered rather
  than the whole tree."}
  (fn [db _] (:window db)))

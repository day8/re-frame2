(ns re-frame.hicasso.examples.ledger.events
  "THE TEN-THOUSAND-ROW LEDGER'S MODEL — ordinary re-frame2 (rf2-hic-047).

  The grid is evidence about BREADTH at a hundred fields, all of them
  mounted. This application is evidence about SCALE, where the defining
  fact is that almost none of the model is mounted at any moment: ten
  thousand records, a few tens of rows in the document, and a foreign
  component deciding which few.

  Nothing in this namespace knows that. It is `re-frame.core` and pure
  functions — `ledger.surface-cljs-test` holds the absence of a view
  substrate here mechanically — and that is the first claim the screen
  makes: **virtualization is a rendering strategy, and it does not reach
  the model.** The same handlers, the same addresses and the same `l0`
  suite would serve a screen that mounted all ten thousand rows.

  ## One address per record, for the same reason the grid has one per cell

  `app-db` holds

      {:records [{:id \"rec-100000\" :name \"Record 0\"} …]
       :notes   {4136 \"chase\"}          ; index -> the note being typed
       :flagged #{4136}
       :focused -1                       ; the row the platform's focus is in
       :window  {:from 0 :to 25}}        ; what the vendor last reported

  `:notes` is a map keyed by index rather than a vector parallel to
  `:records`, so an untouched record costs nothing and a write reaches
  one address. Ten thousand rows is where that stops being a taste and
  starts being the difference between a keystroke touching one key and a
  keystroke re-allocating a ten-thousand-element vector.

  ## `:focused` is the one entry that needs its reason on the record

  It is written by an ordinary `:on-focus` intent and it is read for
  exactly one purpose: to tell the virtualizer which row it may not
  unmount ([[::row-focused]], and `views/ledger`'s `:pinned-index`).

  **It is not a second owner of focus.** The platform owns focus; nothing
  in this application calls `.focus()`, reads `document.activeElement` or
  restores anything. What the entry buys is that the DOM node the
  platform's focus is already in stays in the document while the user
  scrolls, which is the one thing a virtualizer will otherwise take away.
  The distinction is the difference between a recipe and a focus manager,
  and `ledger.virtualized-dom-cljs-test` is written to make it visible:
  with the pin the caret survives an arbitrary scroll, without it focus
  falls to `document.body` — and in neither arm does the application
  move it.

  It is released by the next focus and by nothing else. A `:on-blur`
  companion would unmount the row while the platform is still moving
  focus THROUGH it, and buys back one row of DOM."
  (:require [re-frame.core :as rf]))

(def default-total
  "Ten thousand — §7's *10K-row behavior*, and the size every witness
  that does not say otherwise uses."
  10000)

(defn record
  "The record at `index`. Its id is deliberately NOT its index: a witness
  asserting that focus stayed on `rec-100005` after a scroll is asserting
  about the record, and an id that read `5` would be equally consistent
  with focus having stayed on the fifth row of the WINDOW."
  [index]
  {:id (str "rec-" (+ 100000 index)) :name (str "Record " index)})

(defn seed
  "The starting `app-db` for a ledger of `total` records."
  [total]
  {:records (mapv record (range total))
   :notes   {}
   :flagged #{}
   :focused -1
   :window  {:from 0 :to 0}})

(rf/reg-event ::seed
  {:doc "Install a ledger of the given size. The frame's `:initial-events` step."}
  (fn [_ [_ {:keys [total]}]]
    {:db (seed (or total default-total))}))

(rf/reg-event ::note
  {:doc "Write one record's note.

  POSITIONAL, and for the reason `examples.grid.events` states at
  length: `::h/value` substitutes at the intent vector's TOP LEVEL only,
  so a payload map here would put the marker keyword into `app-db` and
  render it as text."}
  (fn [{:keys [db]} [_ index typed]]
    {:db (assoc-in db [:notes index] typed)}))

(rf/reg-event ::flag
  {:doc "Toggle one record's flag. No marker, so the canonical
  `[<id> {<k> <v>}]` payload map."}
  (fn [{:keys [db]} [_ {:keys [index]}]]
    {:db (update db :flagged #(if (contains? % index) (disj % index) (conj % index)))}))

(rf/reg-event ::row-focused
  {:doc "The platform put focus inside row `index`.

  The whole of the application's interest in focus — see the namespace
  docstring on why this is not a focus manager."}
  (fn [{:keys [db]} [_ {:keys [index]}]]
    {:db (assoc db :focused index)}))

(rf/reg-event ::window-shown
  {:doc "The virtualizer reports the window it is rendering.

  Arrives through the crossing's `:on-window` callback, which is a
  VALUE-first foreign contract — two numbers and no event — so the view
  writes `h/hfn` there and returns this vector from it."}
  (fn [{:keys [db]} [_ {:keys [from to]}]]
    {:db (assoc db :window {:from from :to to})}))

(defn note-id
  "The DOM id of one row's note field. A function rather than a string in
  the view, so a witness and the view cannot disagree about the spelling."
  [index]
  (str "ledger-note-" index))

(defn note-label
  "The accessible name of one row's note field."
  [index]
  (str "Note for " (:name (record index))))

(defn flag-label
  "The accessible name of one row's flag button.

  It does NOT move with the flag's state: a control that renames itself
  as it toggles is a control a screen-reader user has to re-find, and
  `aria-pressed` is the attribute that carries the state. The a11y suite
  asserts both halves."
  [index]
  (str "Flag " (:name (record index))))

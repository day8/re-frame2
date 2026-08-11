(ns re-frame.hicasso.examples.grid.events
  "THE 100-CELL CONTROLLED GRID'S MODEL — ordinary re-frame2 (rf2-hic-078).

  The editor is evidence about a form's SHAPE; this is evidence about
  BREADTH. One hundred controlled fields on one page, every one of them
  written exactly as the four in the editor are, and the question the
  application exists to answer is whether the hundredth costs anything
  the first did not.

  Specification §6: *narrow-update body work scales with changed rows
  rather than all mounted rows*. That is a claim about a per-keystroke
  COUNT at two sizes, so this application is parameterised by its
  dimensions and `grid.scaling-dom-cljs-test` mounts it at two of them.

  ## One address per cell

  `app-db` holds `{:cells {[row col] \"…\"}}` — a flat map keyed by
  coordinate, not a vector of vectors. The reason is the read topology
  and nothing else: `[::subs/cell row col]` is one `get-in` into one
  address, so the write from a keystroke and the read that echoes it meet
  at a single key. A nested representation would make a cell's read
  depend on its row, and a row's identity would then move on every
  keystroke in it — a hundred cells sharing ten notification groups
  instead of a hundred.

  ## Every cell REFUSES a non-digit, and that is the division of labour

  The editor's four fields accept or normalise; none of them refuses. So
  refusal lives here, uniformly: a cell takes digits and nothing else,
  and the model does not move when it is handed anything else. That gives
  the grid the one controlled-law row the editor cannot have — *a
  rejected value echoes the COMMITTED value, in the turn that typed it* —
  while keeping every cell identical, which is what the scaling claim
  needs. A grid whose cells differed would be measuring the differences.

  ## `::h/value` is positional here too

  [[::edit]] carries a marker, so it takes its arguments positionally and
  not in the canonical `[<id> {<k> <v>}]` payload map. Second
  confirmation of rf2-hic-025's finding 1, from an application whose
  intent carries THREE arguments rather than two — the collision does not
  soften as the payload grows, it gets worse, because a three-argument
  positional vector is exactly where a payload map would have started to
  pay for itself.

  [[::seed]] and [[::clear-row]] carry no marker, so both take the
  canonical payload map — `[::clear-row {:row 2}]` — and between them
  they are this bead's live exhibit of the shape the convention asks for
  working exactly as advertised where nothing needs substituting."
  (:require [re-frame.core :as rf]))

(def default-dimensions
  "Ten by ten — §7's *100-cell grid*, and the size every witness that
  does not say otherwise uses."
  {:rows 10 :cols 10})

(defn digits-only
  "The one policy every cell has. `old` is the COMMITTED value and
  `typed` is what the control now shows; returning `old` is a refusal.

  Uniform across the grid on purpose — see the namespace docstring."
  [old typed]
  (if (re-matches #"[0-9]*" (str typed)) typed old))

(defn seed-cells
  "`{[row col] \"<n>\"}` for a `rows` x `cols` grid, filled with a
  distinct digit string per cell so that a witness reading one cell can
  tell it apart from its neighbours."
  [{:keys [rows cols]}]
  (into {}
        (for [row (range rows) col (range cols)]
          [[row col] (str (+ (* row cols) col))])))

(defn seed
  "The starting `app-db` for a grid of the given dimensions."
  [dimensions]
  {:dimensions dimensions
   :cells      (seed-cells dimensions)})

(rf/reg-event ::seed
  {:doc "Install a grid of the given size. The frame's `:initial-events` step."}
  ;; The canonical `[<id> {<k> <v>}]` payload shape, available here for
  ;; the reason it is unavailable to [[::edit]]: this event carries no
  ;; marker.
  (fn [_ [_ dimensions]]
    {:db (seed (or dimensions default-dimensions))}))

(rf/reg-event ::edit
  {:doc "Write one cell, through the digits-only policy."}
  ;; POSITIONAL, and not by preference. `::h/value` substitutes at the
  ;; intent vector's TOP LEVEL only, so `[::edit {:row r :col c :value
  ;; ::h/value}]` would put the keyword `:re-frame.hicasso/value` into
  ;; app-db and render it as text — silently, unrefused and unlinted.
  ;; See the namespace docstring.
  (fn [{:keys [db]} [_ row col typed]]
    (let [address [:cells [row col]]]
      {:db (update-in db address digits-only typed)})))

(rf/reg-event ::clear-row
  {:doc "Empty every cell of one row — a BROAD update, for contrast.

  The scaling claim is about the narrow path; this is what the same
  application looks like when the change genuinely is wide, and it is
  what `scaling-dom-cljs-test` measures against so that the narrow
  number has something to be narrow COMPARED TO. One write, `cols`
  changed subscriptions, `cols` body runs — amplification equal to the
  changed rows and not to the mounted ones, which is the specification's
  sentence read in the other direction."}
  (fn [{:keys [db]} [_ {:keys [row]}]]
    (let [cols (get-in db [:dimensions :cols])]
      {:db (reduce (fn [d col] (assoc-in d [:cells [row col]] ""))
                   db
                   (range cols))})))

(defn cell-label
  "The accessible name of one cell — `\"cell 3,4\"`.

  A function rather than a string in the view because both the view and
  every witness that looks a cell up need the same spelling, and two
  copies of a selector is how a witness ends up asserting about a cell
  that is not on the page."
  [row col]
  (str "cell " row "," col))

(defn cell-id
  "The DOM id of one cell — `\"cell-3-4\"`. Same reason as
  [[cell-label]]."
  [row col]
  (str "cell-" row "-" col))

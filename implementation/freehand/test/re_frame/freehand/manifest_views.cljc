(ns re-frame.freehand.manifest-views
  "A census of `{:compiled true}` declarations with KNOWN cardinality — the
  fixture the static-manifest and capability-elision laws are proven over.

  Every declaration below carries an obvious, hand-countable set of
  lexical sites, so the manifest each one reports and the ViewCell-elision
  verdict each one earns are facts a reader can check against the source
  without running anything. Four declarations carry no reactive site and
  OMIT the reactive ViewCell shell; three carry one — two an event site,
  one a subscription — and RETAIN it. The exact omitted count is therefore
  four — an integer, not a threshold — and that is what `FH-DIAG-002`
  asserts, while `FH-STRUCT-010` asserts each declaration's finite
  manifest rosters against these bodies.

  `v/sub` IS part of the census. It is a published var on the door, and
  since the compiled tier's reactive runtime landed a compiled
  declaration carrying one compiles and loads — so `:subscriptions` is
  populated here, through `v/manifest`, like `:events` and `:crossings`.
  (It was not, once, and the reason recorded in this docstring named the
  wrong cause: the door published `v/sub` all along; what was missing was
  the runtime namespace the compiled lowering resolves it against.)

  The two rosters this census does not populate are `:slots` and
  `:html-sites`, and for two different reasons. `html` is not a published
  var, so no declaration here CAN carry one. `v/slot` IS published — no
  declaration here happens to use a slot, which is the ordinary reason a
  roster is empty. Both are covered one tier down in
  `re-frame.freehand.manifest-roster-coverage-cljs-test`, which injects a
  resolver and asserts the gap rather than leaving it implied;
  `viewcell-elision-oracle-jvm-test` reads the elision verdict off the same
  tier."
  (:require [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; Elided — no reactive site, so the ViewCell shell is omitted.
;; ---------------------------------------------------------------------------

(v/defview label
  "Pure text under one element — the smallest elidable body."
  {:compiled true}
  [{:keys [text]}]
  [:span.label text])

(v/defview card
  "Structure that forwards children beneath an owned heading. Forwarding
  children is not a reactive read, so this stays elidable."
  {:compiled true}
  [{:keys [title children]}]
  [:section.card [:h3.card__title title] children])

(v/defview list-view
  "A keyed list. A `for` run is finite structure, not a reactive site, so
  a list of static rows omits the ViewCell like any other inert body."
  {:compiled true}
  [{:keys [items]}]
  [:ul.list
   (for [i items]
     [:li.item {:key i} i])])

(v/defview mounts-label
  "Mounts a compiled child. A crossing is a boundary the body OWNS, not a
  reactive read, so mounting a child never forces a ViewCell — the
  manifest records the crossing and still reports the shell elided."
  {:compiled true}
  [{:keys [text]}]
  [:div.host [label {:text text}]])

;; ---------------------------------------------------------------------------
;; Present — a reactive site, so the ViewCell shell is retained. A committed
;; event handler reads the committed frame; a subscription reads state.
;; ---------------------------------------------------------------------------

(v/defview basket-total
  "One subscription, reading a query built from a prop. The reactive read
  the compiled tier proves statically: the site is finite and lexical, so
  it lands on the `:subscriptions` roster with the query the author wrote
  — local symbol and all — and the view keeps its ViewCell."
  {:compiled true}
  [{:keys [basket-id]}]
  [:output.total (v/sub [:basket/total basket-id])])

(v/defview button
  "One committed event handler. An `:on-*` intent reads the committed
  frame, so the view observes context and keeps its ViewCell."
  {:compiled true}
  [{:keys [caption]}]
  [:button.btn {:on-click [:census/pressed]} caption])

(v/defview two-buttons
  "Two committed event handlers in one body — two event sites, one
  retained ViewCell. Site count and shell presence are different facts:
  many sites, one shell."
  {:compiled true}
  [_]
  [:div.pair
   [:button.a {:on-click [:census/a]} "A"]
   [:button.b {:on-click [:census/b]} "B"]])

(def by-name
  "Census view-name keyword -> the declared view. The fixtures name views
  by these short keywords; this is where the name becomes the value, so a
  fixture cannot assert a fact about a view it did not actually load."
  {:label        label
   :card         card
   :list-view    list-view
   :mounts-label mounts-label
   :basket-total basket-total
   :button       button
   :two-buttons  two-buttons})

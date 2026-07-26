(ns re-frame.freehand.bench.b6-witnesses
  "B6's witnesses, declared in FREEHAND and INTERPRETED — the reference arm
  of the cross-substrate comparison.

  B6 asks one question the rest of the B-spine never asks: how does
  Freehand compare against a DIFFERENT substrate rendering the same page
  to the same React? Every earlier row compares Freehand to Freehand
  (interpreted against compiled) or to a hand-built React floor. This one
  adds Reagent — and, on the mount rows, UIx.

  ## The three witnesses, and why these three

  Deliberately the shapes
  `docs/design/freehand/studio/compiled-tier-browser-worth-it.md`
  already measured, so B6's ratios slot into that report's table rather
  than starting a second, incomparable one:

    - **W1** a large template — 300 rows under one boundary each, with
      multi-class sugar, a style map, a `data-*` passthrough and text.
      1,203 elements. The honest headline shape.
    - **W2** a boundary storm — 300 leaf boundaries whose bodies read
      nothing. 301 elements. Deliberately the shape that MAXIMISES
      Freehand's ViewCell elision, which Reagent has no concept of; the
      report flags it as overstating the gap rather than quietly banking
      it.
    - **W3** an ordinary form — 12 fields, each a label, a controlled
      input and an error line, under a derived submit gate. 51 elements.
      The shape most applications are actually made of.

  ## Every arm must build the same page

  The comparison is only fair if the arms are rendering one page, so the
  suite gates canonical-DOM equality across all of them before it reads a
  clock — the same discipline, and the same reason, as B1's DOM row. That
  is why the markup below uses **multi-class sugar** rather than a
  `:class` prop composed with sugar: sugar composition is real work every
  front end does, and it is spelled identically in Freehand hiccup and
  Reagent hiccup, so an equality here is a claim about the substrates
  rather than about two different class-composition conventions.

  ## No handlers, and why

  W1 carries no `:on-click`. Freehand's paved path for a handler is an
  EVENT VECTOR resolved against a frame; Reagent's is a closure. Putting
  one in each arm would compare two different mechanisms inside a mount
  row that is otherwise about markup, and putting one in neither keeps
  the row about what it says it is about. The event path is measured
  where it belongs — the update rows, which are about exactly that.

  Its twin in [[re-frame.freehand.bench.b6-witnesses-compiled]] is this
  file's declarations with `{:compiled true}` added and nothing else
  changed.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; W1 — the large template
;; ---------------------------------------------------------------------------

(v/defview w1-row
  "One row of the large template: multi-class sugar, a style map, a
  `data-*` name that passes through verbatim, and a group of three
  children — an element with two attributes, an element with static text,
  and an element with dynamic text."
  [{:keys [i]}]
  [:li.row.cell.wide {:style      {:padding-left "4px" :color "rebeccapurple"}
                      :data-index i}
   [:img.avatar {:src "/avatar.png" :alt ""}]
   [:span.label "row "]
   [:em.n (str i)]])

(v/defview w1
  "The large template — a fixed skeleton over a keyed run of row
  boundaries."
  [{:keys [rows]}]
  [:section.panel {:aria-label "bench rows"}
   [:h1.title "Rows"]
   [:ul.rows {:role "list"}
    (for [i (range rows)]
      [w1-row {:key i :i i}])]])

;; ---------------------------------------------------------------------------
;; W2 — the boundary storm
;; ---------------------------------------------------------------------------

(v/defview w2-leaf
  "A leaf boundary whose body reads nothing — so Freehand's analyzer can
  prove it has no reactive site and elide its ViewCell, and Reagent
  cannot, because Reagent has no such concept."
  [_]
  [:span.leaf "leaf"])

(v/defview w2
  "300 leaf boundaries under one parent."
  [{:keys [n]}]
  [:div.storm
   (for [i (range n)]
     [w2-leaf {:key i}])])

;; ---------------------------------------------------------------------------
;; W3 — the ordinary form
;; ---------------------------------------------------------------------------

(v/defview w3-field
  "One field: a label, a controlled input carrying a `value`, and an
  error line. `:read-only` rather than a change handler, for the reason
  the namespace docstring gives — the `value` slot is what puts the
  element through the controlled-input door, and the handler would be a
  second, differently-spelled mechanism inside a markup row."
  [{:keys [i value error]}]
  [:div.field
   [:label.lbl {:for (str "f" i)} (str "Field " i)]
   [:input.inp {:id        (str "f" i)
                :name      (str "f" i)
                :type      "text"
                :value     value
                :read-only true}]
   [:p.err error]])

(v/defview w3
  "The 12-field form under a derived submit gate."
  [{:keys [fields]}]
  [:form.w3form
   [:fieldset.fields
    (for [i (range fields)]
      [w3-field {:key   i
                 :i     i
                 :value (str "value " i)
                 :error (if (even? i) "" (str "field " i " is required"))}])]
   [:button.submit {:type "submit" :disabled true} "Submit"]])

;; ---------------------------------------------------------------------------
;; U — the update witness
;; ---------------------------------------------------------------------------

(v/defview u-cell
  "One cell of the update grid, reading its own subscription. This is the
  paved Freehand spelling of a fine-grained reactive leaf, and the
  counterpart of the Reagent arm's `r/cursor`."
  [{:keys [i]}]
  [:span.cell {:data-i i} (str (v/sub [:b6/cell i]))])

(v/defview u-grid
  "300 independently-reactive cells — the surface both the broad and the
  narrow update rows drive."
  [{:keys [n]}]
  [:div.ugrid
   (for [i (range n)]
     [u-cell {:key i :i i}])])

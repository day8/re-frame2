(ns re-frame.freehand.bench.b6-witnesses-compiled
  "B6's witnesses, PROMOTED — [[re-frame.freehand.bench.b6-witnesses]]'s
  declarations with `{:compiled true}` added and nothing else changed.

  The two files exist separately only because a view id is derived from
  where a declaration LIVES, so two declarations of one name cannot share
  a namespace. Keeping the bodies character-identical is what makes B6's
  compiled column a claim about LOWERING rather than about two templates:
  a timing comparison between two arms that are not the same page is a
  comparison of two pages, which is why the suite gates canonical-DOM
  equality across every arm before it reads a clock.

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
  {:compiled true}
  [{:keys [i]}]
  [:li.row.cell.wide {:style      {:padding-left "4px" :color "rebeccapurple"}
                      :data-index i}
   [:img.avatar {:src "/avatar.png" :alt ""}]
   [:span.label "row "]
   [:em.n (str i)]])

(v/defview w1
  "The large template — a fixed skeleton over a keyed run of row
  boundaries."
  {:compiled true}
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
  {:compiled true}
  [_]
  [:span.leaf "leaf"])

(v/defview w2
  "300 leaf boundaries under one parent."
  {:compiled true}
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
  {:compiled true}
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
  {:compiled true}
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
  {:compiled true}
  [{:keys [i]}]
  [:span.cell {:data-i i} (str (v/sub [:b6/cell i]))])

(v/defview u-grid
  "300 independently-reactive cells — the surface both the broad and the
  narrow update rows drive."
  {:compiled true}
  [{:keys [n]}]
  [:div.ugrid
   (for [i (range n)]
     [u-cell {:key i :i i}])])

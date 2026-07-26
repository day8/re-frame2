(ns re-frame.freehand.bench.b6-reagent
  "B6's witnesses, declared in REAGENT — the compared substrate.

  Written the way a Reagent user actually writes them, because a
  strawman arm would make the whole comparison worthless: plain Hiccup
  with class sugar, ordinary `defn` components referenced as
  `[component arg]`, `^{:key i}` metadata on the seq children (which is
  Reagent's own spelling, not Freehand's), a form-2 component with
  `reagent.core/cursor` over a `reagent.core/atom` where a fine-grained
  local model is wanted, and `reagent.dom.client` for the mount.

  Nothing here is pre-adapted, no `:>` shortcut appears, and no React
  class is hand-built. If any of that had been done the Reagent arm would
  be measuring something no Reagent application contains.

  ## The markup is character-for-character the Freehand arm's

  Read this file beside [[re-frame.freehand.bench.b6-witnesses]]: the tag
  sugar, the attribute names, the attribute order and the text are the
  same, because the suite gates canonical-DOM equality across every arm
  before it reads a clock and a divergence would fail rather than
  flatter. Hiccup is a shared dialect, so this is not a translation so
  much as the same source under a different reader — which is exactly the
  condition that makes the ratio a substrate ratio.

  ## What Reagent does that Freehand's compiled tier does not

  Every `[component …]` here becomes a Reagent component with its own
  reactive wrapper, whether or not its body dereferences anything.
  Reagent has no elision concept: there is no analysis pass, so there is
  nothing that could prove a body sub-free and drop the wrapper. That is
  why W2 — 300 bodies that read nothing — is reported with a warning
  attached rather than as a headline.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [reagent.core :as r]))

;; ---------------------------------------------------------------------------
;; W1 — the large template
;; ---------------------------------------------------------------------------

(defn w1-row
  "One row. A form-1 component: Reagent calls it on every render of its
  parent, and gives it its own reactive wrapper."
  [i]
  [:li.row.cell.wide {:style      {:padding-left "4px" :color "rebeccapurple"}
                      :data-index i}
   [:img.avatar {:src "/avatar.png" :alt ""}]
   [:span.label "row "]
   [:em.n (str i)]])

(defn w1
  [rows]
  [:section.panel {:aria-label "bench rows"}
   [:h1.title "Rows"]
   [:ul.rows {:role "list"}
    (for [i (range rows)]
      ^{:key i} [w1-row i])]])

;; ---------------------------------------------------------------------------
;; W2 — the boundary storm
;; ---------------------------------------------------------------------------

(defn w2-leaf
  "A leaf that reads nothing. Reagent still wraps it — it has no way to
  know, and no pass in which it could find out."
  []
  [:span.leaf "leaf"])

(defn w2
  [n]
  [:div.storm
   (for [i (range n)]
     ^{:key i} [w2-leaf])])

;; ---------------------------------------------------------------------------
;; W3 — the ordinary form
;; ---------------------------------------------------------------------------

(defn w3-field
  [i value error]
  [:div.field
   [:label.lbl {:for (str "f" i)} (str "Field " i)]
   [:input.inp {:id        (str "f" i)
                :name      (str "f" i)
                :type      "text"
                :value     value
                :read-only true}]
   [:p.err error]])

(defn w3
  [fields]
  [:form.w3form
   [:fieldset.fields
    (for [i (range fields)]
      ^{:key i} [w3-field i
                          (str "value " i)
                          (if (even? i) "" (str "field " i " is required"))])]
   [:button.submit {:type "submit" :disabled true} "Submit"]])

;; ---------------------------------------------------------------------------
;; U — the update witness, on Reagent's own reactivity
;; ---------------------------------------------------------------------------

;; The local model. A `reagent.core/atom` is Reagent's own reactive
;; primitive and the thing its design is actually about, so this is what
;; the update rows drive. `defonce` takes no docstring, hence the comment.
(defonce cells (r/atom []))

(defn u-cell
  "A FORM-2 component: the outer call mints a `cursor` once, per mounted
  occurrence, and the returned render fn dereferences only that cursor.
  This is the idiomatic Reagent spelling of a fine-grained reactive leaf
  — the reason a single-cell write re-renders one component rather than
  three hundred — and the counterpart of the Freehand arm's per-cell
  `v/sub`."
  [i]
  (let [c (r/cursor cells [i])]
    (fn [i]
      [:span.cell {:data-i i} (str @c)])))

(defn u-grid
  [n]
  [:div.ugrid
   (for [i (range n)]
     ^{:key i} [u-cell i])])

(ns re-frame.freehand.bench.b2.interpreted
  "B2's roster, INTERPRETED — the matched arm the elision claim is
  measured against.

  [[re-frame.freehand.bench.b2]] declares the same seven views with
  `{:compiled true}` and nothing else changed; `compiled-source-delta-jvm-test`
  reads both files and proves it. That sameness is what makes the
  comparison a comparison of LOWERINGS: a mount storm of two rosters that
  are not the same roster is a mount storm of two rosters.

  These declarations carry no manifest — the interpreted mode has no
  finite grammar and no analysis step, so there is nothing it could
  claim — and therefore no elision verdict. Every one of them mounts
  inside the atomic shell, including the four whose compiled twins are
  proven not to need it. That is the whole of what B2 measures, stated as
  two files: the compiled tier drops shells it can prove unnecessary, and
  interpretation keeps them because it cannot prove anything.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; The capability-free arm's twins — elidable when compiled, kept here
;; ---------------------------------------------------------------------------

(v/defview free-label
  "Pure text under one element — the smallest elidable leaf."
  [{:keys [text]}]
  [:span.label text])

(v/defview free-card
  "Structure forwarding children beneath an owned heading. Forwarding
  children is not a reactive read, so the shell stays elided."
  [{:keys [title children]}]
  [:section.card [:h3.card__title title] children])

(v/defview free-list
  "A keyed list. A `for` run is finite structure, not a reactive site, so
  a list of static rows omits the ViewCell like any other inert body."
  [{:keys [items]}]
  [:ul.list
   (for [i items]
     [:li.item {:key i} i])])

(v/defview free-nested
  "Nested plain elements — depth is not a reactive read either, so the
  deepest inert body is still elidable."
  [{:keys [text]}]
  [:div.host [:div.inner [:span.leaf text]]])

;; ---------------------------------------------------------------------------
;; The three-read arm's twins — reactive in both modes
;; ---------------------------------------------------------------------------

(v/defview read-panel
  "Two subscriptions and a committed event handler — three reactive reads,
  one retained ViewCell."
  [{:keys [id]}]
  [:section.panel
   [:output.total (v/sub [:panel/total id])]
   [:output.count (v/sub [:panel/count id])]
   [:button.act {:on-click [:panel/act id]} "act"]])

(v/defview read-row
  "One subscription and two committed event handlers — three reactive
  reads over one boundary. Many sites, one shell."
  [{:keys [id]}]
  [:tr.row
   [:td.val (v/sub [:row/value id])]
   [:td [:button.up {:on-click [:row/up id]} "+"]]
   [:td [:button.down {:on-click [:row/down id]} "-"]]])

(v/defview read-widget
  "Three subscriptions — three reactive reads, no events, still one
  retained ViewCell. A view is reactive because it READS state, however
  it reads it."
  [{:keys [id]}]
  [:div.widget
   [:span.a (v/sub [:widget/a id])]
   [:span.b (v/sub [:widget/b id])]
   [:span.c (v/sub [:widget/c id])]])

;; ---------------------------------------------------------------------------
;; The rosters, in the order the compiled file declares them
;; ---------------------------------------------------------------------------

(def free-views
  "The capability-free arm's interpreted twins, in declaration order."
  [free-label free-card free-list free-nested])

(def read-views
  "The three-read arm's interpreted twins, in declaration order."
  [read-panel read-row read-widget])

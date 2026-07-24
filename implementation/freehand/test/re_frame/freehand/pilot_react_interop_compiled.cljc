(ns re-frame.freehand.pilot-react-interop-compiled
  "The F5i pilot's COMPILED arm — what a `{:compiled true}` page can and
  cannot do with a React-library integration.

  This arm exists because compiled views became browser-mountable. Before
  that, asking whether a compiled page could host a React library was
  academic; now it is the question an adopter with a hot list view and a
  chart in the same page actually has.

  The answer has two halves, and both are load-bearing:

  1. **A compiled body CAN attach the integration.** `[v/behavior …]` inside
     a compiled body now COMPILES — the analyzer lowers it to the grammar's
     own `:op :behavior`, the one imperative host shape Freehand offers, no
     longer unreachable from the compiled tier. So a view that owns a chart,
     a grid or an editor can be promoted rather than interpreted-forever; the
     suite proves the attachment form lowers rather than refuses.

  2. **A compiled body can also CONTAIN one.** Extracting the awkward part
     into its own declared child works, and often reads better: the compiled
     parent mounts the interpreted integration as an ordinary declared
     child, its manifest MARKS the crossing `:interpreted`, and the page is
     one React tree. That is the substrate keeping its promise — a clean
     boundary an adopter can reach for even where a direct attachment would
     also compile.

  Both shapes live in the pilot: the suite proves the direct attachment
  lowers, and this namespace shows the crossing, promoted — `hot-list` is
  `{:compiled true}` and mounts the interpreted `invoice-sheet` as a child."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.pilot-react-interop :as pilot]))

(v/defview hot-list
  "The recovery, and the shape an adopter ends up with: a COMPILED page
  whose hot markup is lowered, mounting the ONE interpreted child that owns
  the React library. The crossing is visible in the source and counted in
  the manifest."
  {:compiled true}
  [{:keys [title]}]
  [:main.hot-page
   [:h1.title title]
   [:button.export {:on-click [:invoice/export-requested ","]} "Export"]
   [pilot/invoice-sheet {}]])

;; ---------------------------------------------------------------------------
;; The headless integration, PROMOTED — and what promotion actually cost
;; ---------------------------------------------------------------------------
;;
;; The interpreted `pilot/headless-table` is one declaration. Promoting it is
;; advertised as a one-line change, and for this view it was NOT: the
;; compiled grammar refused the body twice, and the recovery was to extract
;; TWO child views.
;;
;;   [:th {:on-click [:ledger/sorted key]} label]
;;   ;; => event vector [:ledger/sorted key] at :on-click captures loop
;;   ;;    binding key — per-row committed slots need per-row instances.
;;   ;;    Extract a keyed child view and pass key as a prop
;;
;; That refusal is CORRECT and its message is excellent — it names the
;; mechanism, the reason, and the exact recovery. It is also the ordinary
;; case rather than an exotic one: a table whose header cells dispatch a
;; sort is about as plain as a data grid gets, and every such view meets it.
;;
;; So the honest report is: promotion is a one-line change for a body that
;; already happens to be inside the grammar, and a small refactor for one
;; that is not. Both are fine; only the first is what the docstring says.

(v/defview column-header
  "The extraction the compiled grammar's refusal named: one child per
  header cell, with the loop binding arriving as a PROP.

  The sort control is a real `<button>` because the compiled tier's a11y
  analyzer said so — `:on-click` on a bare `<th>` raised
  `:rf.ui.compile/a11y-click-non-interactive`, naming both the reason (a
  generic element is not focusable and has no role) and both recoveries.
  The advice is right and the markup is better for it.

  It is also a MODE ASYMMETRY worth naming: the interpreted twin carries
  the identical mistake and says nothing, because a11y analysis is part of
  what compilation buys. So promotion does not only change when a mistake
  surfaces — for this class of mistake it changes WHETHER it surfaces."
  {:compiled true}
  [{:keys [col label]}]
  [:th [:button.sort {:on-click [:ledger/sorted col]} label]])

(v/defview data-cell
  {:compiled true}
  [{:keys [col value]}]
  [:td {:data-col col} value])

(v/defview data-row
  {:compiled true}
  [{:keys [cells]}]
  [:tr (for [{:keys [key value]} cells]
         [data-cell {:key key :col (name key) :value (str value)}])])

(v/defview compiled-headless-table
  "The headless-library integration, PROMOTED. It needed no host shape in
  the interpreted mode and needs none here — the promotion cost was the
  grammar's per-row-slot rule, not the library."
  {:compiled true}
  [_]
  (let [{:keys [header rows]} (v/sub [:ledger/table])]
    [:table.ledger
     [:thead
      [:tr (for [{:keys [key label]} header]
             [column-header {:key key :col key :label label}])]]
     [:tbody
      (for [{:keys [id cells]} rows]
        [data-row {:key id :cells cells}])]]))

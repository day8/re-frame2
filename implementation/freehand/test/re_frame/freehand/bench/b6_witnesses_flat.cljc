(ns re-frame.freehand.bench.b6-witnesses-flat
  "The update grid with ONE boundary instead of three hundred — B6's
  ABLATION witness, and the control that decides whether the broad-update
  deficit is per-ELEMENT or per-BOUNDARY.

  [[re-frame.freehand.bench.b6-witnesses/u-grid]] declares a `v/defview`
  per cell, so a broad write repaints 300 boundaries: 300 shells, 300
  ViewCells, 300 sets of React hooks, and 300 commit-phase layout effects.
  This declaration builds **character-for-character the same DOM** from a
  single boundary that reads all 300 subscriptions itself. The element
  count, the tags, the attributes and the text are identical — which is
  what makes the pair an ablation rather than two benchmarks.

  Everything else is held constant: the same `:b6/cell` subscriptions, the
  same 300 reads, the same interpreted lowering, the same `v/sub` door,
  the same emitter walk over the same hiccup. The ONLY variable is how
  many boundaries the 300 reads are spread across.

  So the difference between the two arms is the cost of a BOUNDARY, and
  nothing else. If bulk re-render were dominated by the interpreted markup
  walk the two would land together; if it is dominated by per-boundary
  shell and commit machinery they will not.

  Not a published row and not a shape anybody should write — a real
  application wants the fine-grained version, because the whole point of a
  per-cell boundary is that a NARROW write repaints one cell. This exists
  to attribute a cost.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand :as v]))

(v/defview u-grid-flat
  "300 reactive cells under ONE boundary. The `v/sub` calls are the same
  300 the per-cell version makes, and the emitted markup is the same; they
  are simply all recorded on one candidate and published by one commit."
  [{:keys [n]}]
  [:div.ugrid
   (for [i (range n)]
     ;; `:key` in the PROPS MAP, which is Freehand's one spelling for the
     ;; structural identity slot (`rules/reserved-key-slot`). React consumes
     ;; it, so it does not reach the DOM and the canonical-DOM gate still
     ;; sees the reference page.
     [:span.cell {:key i :data-i i} (str (v/sub [:b6/cell i]))])])

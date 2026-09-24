(ns re-frame.story.cell-plan
  "Compile a registered variant the way a run of one of its CELLS compiles it.

  A cell is a variant under a set of active modes and Controls overrides. A
  run compiles it with the ambient + per-run arg layers folded around the
  variant layer (`re-frame.story.args/run-arg-layers`: global args, the parent
  story's `:args`, the modes' `:args`, the cell overrides), so every `[:arg k]`
  in `:setup` / `:script` / `:db-seed` / `:network` / `:sub-overrides`
  substitutes against the args the cell actually has. A tooling read that
  compiles bare sees the variant layer alone, and a variant that leaves an
  arg to its story or the globals — the flagship authoring pattern — throws
  `:rf.error/story-missing-arg` or `:rf.error/story-view-args-invalid` there,
  though it runs fine.

  `cell-plan` is that compile, for the reads that want the plan of a cell
  rather than of the bare body: the view-args schema resolver
  (`re-frame.story.view-args`) and the share report
  (`re-frame.story.ui.share/current-share-report`). It adds no inheritance
  policy of its own — the plan compiler resolves the variant layer, and
  `run-arg-layers` supplies the layers the body cannot carry.

  Pure aside from the registrar / config reads `variant-plan` and
  `run-arg-layers` already perform; behind the §6 elision contract with the
  rest of the Story runtime."
  (:require [re-frame.story.args :as rf.story.args]
            [re-frame.story.plan :as rf.story.plan]))

(defn cell-plan
  "Compile REGISTERED `variant-id` into the plan a run of the cell `opts`
  describes: `(rf.story.plan/variant-plan variant-id {:run-args …})` with the
  arg layers `rf.story.args/run-arg-layers` builds for `opts`.

  `opts` is the `{:active-modes [...] :cell-overrides {...}}` shape
  `run-arg-layers` takes; nil compiles the variant's declared shape (global +
  story args, no modes or overrides). Throws exactly what `variant-plan`
  throws — callers that want a best-effort read catch."
  ([variant-id] (cell-plan variant-id nil))
  ([variant-id opts]
   (rf.story.plan/variant-plan
     variant-id
     {:run-args (rf.story.args/run-arg-layers variant-id opts)})))

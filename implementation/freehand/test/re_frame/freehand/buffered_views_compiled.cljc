(ns re-frame.freehand.buffered-views-compiled
  "The buffered controller pilot's CONTROL, promoted.

  Below is
  [[re-frame.freehand.buffered-controller-cljs-test/buffered-field]] with
  `{:compiled true}` added and nothing else changed: the same parameter
  vector, the same `let`, the same read, the same three event sites, the
  same literal markup. The library vocabulary is REFERRED rather than
  restated (`kind`), so the body text is identical character for character
  and the promoted control addresses the very same controller family — a
  twin that named its own kind would write its own records, and the parity
  suite would be comparing two independent controllers rather than one
  control in two modes.

  Separate namespace because a view id is derived from where a declaration
  LIVES, so two declarations of one name cannot share a namespace.
  `buffered-controller-parity-cljs-test` rewrites exactly that one
  namespace component and asserts everything else is equal.

  ## Only the CONTROL is promoted, and that is the finding

  The pilot's caller — `invoice-form` — reads `(v/sub [:invoice/amount id])`
  inside a `for`, and the compiled grammar refuses it
  (`:rf.ui.compile/sub-in-loop`, because a read inside a loop is not a
  finite, lexical reactive site). Its own catalogued recovery is
  `[:extract-declared-child :keep-interpreted]`, and the pilot ALREADY did
  the first half: the control is the extracted declared child. So the
  caller stays interpreted and drives a compiled child, which is the
  composition the grammar is designed to produce rather than a limitation
  worked around.

  The pilot's dataflow is NOT re-registered here.
  `buffered-controller-cljs-test/register-library!` is ordinary re-frame
  that knows nothing about execution mode, which is itself part of what
  promotion parity means."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.buffered-controller-cljs-test :refer [kind]]))

(v/defview buffered-field
  "The pilot control, PROMOTED. It asks for its record key and its
  generation FIRST — which is what makes it a writable, buffered
  controller at all — then reads and emits through ordinary re-frame.
  Every intent it renders carries the generation the render that produced
  it displayed."
  {:compiled true}
  [{:keys [value on-commit] :as props}]
  (let [k (v/controller-key kind props)
        g (v/controller-revision kind props)]
    [:span
     [:input {:value    (v/sub [:my.ui.field/text k g value])
              :on-input [:my.ui.field/edited k g ::v/value]
              :on-blur  [:my.ui.field/committed k g on-commit]}]
     [:button {:on-click [:my.ui.field/cancelled k g]} "Revert"]]))

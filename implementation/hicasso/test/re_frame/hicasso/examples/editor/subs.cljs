(ns re-frame.hicasso.examples.editor.subs
  "THE EDITOR'S READ TOPOLOGY — one address per field (rf2-hic-078).

  Four fields and four subscription cells, because the per-keystroke
  budget is a fact about the READ TOPOLOGY and about nothing else.
  `docs/core/hicasso/19-performance.md` §Trace one
  controlled keystroke walks it: one write, subscriptions recompute,
  equality gates stop every one whose output did not move, and the
  boundaries whose reads changed are notified. With a field per cell that
  is **one** changed subscription and **one** body run per keystroke —
  write amplification 1, independent of how many fields the form has.
  `editor.flow-dom-cljs-test` measures it rather than asserting the
  arithmetic.

  **`recompute` is not the same set as `reads that address`**, and this
  paragraph used to say it was. Every subscription here is a LAYER-1
  reader, memoised on the whole of `app-db` rather than on the address it
  goes on to read, so a keystroke re-runs all TEN of this form's cells and
  the equality gate then stops nine of them from notifying anything.
  Measured by `examples.per-keystroke-dom-cljs-test` and published in
  `docs/design/hicasso/product/per-keystroke.md`: one changed subscription
  out of ten recomputed, which is a distinction the body count cannot
  make and the budget does not rest on.

  [[field]] is PARAMETRIC, so the four cells are four entries under one
  registration rather than four registrations. A parametric subscription
  is keyed by its whole query vector, so `[::field :title]` and
  `[::field :body]` are separate cells with separate equality gates,
  which is the property the budget rests on. `editor.l2-cljs-test` proves
  it the only way a structural tier can — by handing each body a fixture
  map answering exactly the reads it makes, and letting the kit refuse
  any read the map does not answer.

  Nothing here is a view-model. Each subscription answers the value its
  control shows, one `get-in` deep, because that is what a controlled
  field wants: the cheapest path from the keystroke's write to the value
  the converge writes back."
  (:require [re-frame.core :as rf]))

(rf/reg-sub ::field
  {:doc "One field's DRAFT value — the value its control shows."}
  (fn [db [_ field]] (get-in db [:draft field])))

(rf/reg-sub ::committed
  {:doc "One field's COMMITTED value — what `::events/save` last took.

  Read by the readout beneath the form and by nothing else, so \"echoes
  only committed state\" has somewhere on the page to be read off."}
  (fn [db [_ field]] (get-in db [:article field])))

(rf/reg-sub ::revision
  {:doc "The reset counter the text fields carry as `::h/revision`.

  Its own cell, so a discard notifies the fields carrying it and nothing
  else. Each field reads it in its own body rather than taking it as a
  prop, which is what keeps the parent out of the keystroke path
  entirely: a prop would have to come from a body that read the counter,
  and that body would then re-run on every reset."}
  (fn [db _] (:revision db 0)))

(rf/reg-sub ::dirty?
  {:doc "Does the draft differ from the article?

  The one subscription here whose input is not a single address, and it
  is deliberately off the keystroke path's critical list: it feeds the
  button row, which is a separate boundary from the fields. A keystroke
  that changes it runs the button row's body and no field's — which is
  the ordinary way an application spends its second body run, and the
  reason `flow-dom-cljs-test` measures amplification with the buttons on
  the page rather than with the fields alone."}
  (fn [db _] (not= (:draft db) (:article db))))

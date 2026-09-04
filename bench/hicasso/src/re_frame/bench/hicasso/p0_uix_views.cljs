(ns re-frame.bench.hicasso.p0-uix-views
  "THE FRONTIER ARM, ON rf2-2rtt6.2'S WITNESSES — UIx reading re-frame2
  subscriptions through the published `use-subscribe` spine (rf2-a4x1o,
  re-pointing rf2-2rtt6.4).

  ## Why this namespace exists at all

  rf2-2rtt6.4 measured UIx-on-subs and, under RULING 1 on rf2-2rtt6.1,
  its ratios ARE the red-zone thresholds. It measured them on its own
  witnesses — a 1,203-element `W1` list reading `[:p0/row i]`, a
  51-element `W3` form reading `[:p0/field i]`, and a 301-element grid.
  rf2-2rtt6.2, which had not merged when that run was taken, supplies the
  bar's DENOMINATOR on different witnesses: a 901-element `M1` list and a
  51-element `M2` form, both reading `[:p0/cell i]`. A threshold and a bar
  on two different pages cannot be read down one column, and a red-zone
  that is not comparable across arms is not a red-zone.

  This namespace is the UIx side of the convergence. There is no new
  witness here and no new markup: every shape is
  [[re-frame.bench.hicasso.p0-reagent-views]]'s, transcribed into UIx's
  own spelling, and the canonical-DOM parity gate is what proves the
  transcription rather than this docstring. If the two arms build
  different pages the run stops before a clock is read.

  ## What is deliberately NOT here

  - **No `set-hiccup-emitter!`.** UIx's `$` expands to
    `react/createElement` at compile time. Routing this arm through the
    hiccup emitter would price a hiccup interpreter — which is a
    candidate's product delta, not UIx's — and would set the red-zone from
    the wrong runtime, flattering every later candidate.
  - **No `use-memo` around the subscription args.** `use-subscribe`'s own
    stable-deps handling is part of the spine under test.
  - **No prop threading in place of a read.** Every boundary the Reagent
    arm subscribes in, this arm subscribes in: one read per boundary, the
    first rung of the HD-002 1/3/7/20 ladder. A row that took its text as
    a prop would be a different sub graph wearing the same name.
  - **No `flush-views!`.** The UIx adapter's own flush wraps React's
    `act()`, and `act` diverts work to a queue that is not the browser's.
    Every window in this lane is taken outside it (`lane/leave-act-
    environment!`), so the UIx drain here is an empty
    `react-dom/flushSync` — `useSyncExternalStore` notifications schedule
    at React's SYNC lane, so that flush commits them inside the window,
    and the DOM read-back is what checks it rather than this comment.

  Owner: the operator-owned standard bead rf2-2rtt6.1; this arm rf2-a4x1o."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.p0-reagent-views :as rf.bench.hicasso.p0-reagent-views]
            [uix.core :refer [$ defui]]))

;; ---------------------------------------------------------------------------
;; M1 — the 300-boundary sub-reading list (901 elements)
;; ---------------------------------------------------------------------------
;;
;; Read this beside `p0-reagent-views/m1-cell`. Same tag sugar, same class
;; names, same attribute, same text — one boundary, one `[:p0/cell i]`
;; read, and nothing else.

(defui m1-cell [{:keys [i]}]
  (let [v (rf.adapter.uix/use-subscribe [:p0/cell i])]
    ($ :li.row
       ($ :span.lbl "cell ")
       ($ :span.cell {:data-i i} (str v)))))

(defui m1 [{:keys [n]}]
  ($ :ul.grid {:role "list"}
     (for [i (range n)]
       ($ m1-cell {:key i :i i}))))

;; ---------------------------------------------------------------------------
;; M2 — the ordinary 12-field form (51 elements)
;; ---------------------------------------------------------------------------

(defui m2-field [{:keys [i]}]
  (let [v (rf.adapter.uix/use-subscribe [:p0/cell i])]
    ($ :div.field
       ($ :label.lbl {:for (str "f" i)} (str "Field " i))
       ($ :input.inp {:id        (str "f" i)
                      :name      (str "f" i)
                      :type      "text"
                      :value     (rf.bench.hicasso.p0-reagent-views/field-value i v)
                      :read-only true})
       ($ :p.err (rf.bench.hicasso.p0-reagent-views/field-error i)))))

(defui m2 [{:keys [n]}]
  ($ :form.p0form
     ($ :fieldset.fields
        (for [i (range n)]
          ($ m2-field {:key i :i i})))
     ($ :button.submit {:type "submit" :disabled true} "Submit")))

;; ---------------------------------------------------------------------------
;; The root
;; ---------------------------------------------------------------------------

(defn subs-root
  "`frame-provider` SCOPES an already-created frame and renders no DOM of
  its own, so it cannot move the canonical-DOM gate. The frame is created
  by the segment's setup, outside every measured window: a mount window
  containing `make-frame` and a seeding write would price frame
  construction on whichever arm happened to own it.

  The counterpart of [[re-frame.bench.hicasso.p0-reagent-views/subs-root]],
  and it is not ceremony added for the bench — it is how a re-frame2 UIx
  application supplies the frame its boundaries resolve `use-subscribe`
  against."
  [view n]
  ($ rf.adapter.uix/frame-provider {:frame rf.bench.hicasso.p0-reagent-views/subs-frame}
     ($ view {:n n})))

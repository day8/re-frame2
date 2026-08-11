(ns re-frame.bench.p0-uix
  "EP-0038 P0 — **the frontier arm**: UIx reading re-frame2 subscriptions
  through the existing `use-subscribe` spine.

  UIx is the best existing React-idiomatic option on re-frame2 subs, and
  the delegated ruling recorded on rf2-2rtt6.1 makes the ratios this arm
  produces the RED-ZONE THRESHOLDS for every later candidate, on both the
  clock and retained heap. Nothing here may therefore be a convenience
  spelling: every boundary is written the way the adapter's own
  documentation and examples write one.

  ## What is deliberately NOT here

  - **No `set-hiccup-emitter!`.** UIx's `$` is a macro that expands to
    `react/createElement` at compile time. An arm written through the
    hiccup emitter would be measuring a hiccup interpreter, which is the
    candidate's product delta and not UIx's. Using it here would set the
    red-zone from the wrong runtime and flatter every later candidate.
  - **No `use-memo` around the subscription args.** `use-subscribe`'s own
    stable-deps-key handling is part of the spine under test; wrapping it
    would price a hand optimisation no ordinary application writes.
  - **No prop threading in place of a read.** Every boundary that a
    Reagent arm subscribes in, this arm subscribes in — one read per
    boundary, the first rung of the HD-002 ladder. A row that took its
    text as a prop would be a different sub graph wearing the same name.

  ## One boundary, one read

  `w1-row`, `w3-field` and `u-cell` are each a `defui` — a React function
  component — calling `use-subscribe` once. That is the paved UIx
  spelling; the counterpart Reagent arm is a `reg-view` derefing
  `(rf/subscribe …)` once. Neither is doing the other's work.

  Owner: the operator-owned governance set that superseded rf2-2rtt6.1 on
  2026-08-10, enumerated once in `docs/design/hicasso/studio/README.md`;
  this arm rf2-2rtt6.4."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.p0-fixture :as fx]
            [uix.core :refer [$ defui]]))

;; ---------------------------------------------------------------------------
;; W1 — the large template
;; ---------------------------------------------------------------------------

(defui w1-row [{:keys [i]}]
  (let [text (uix-adapter/use-subscribe [:p0/row i])]
    ($ :li.row.cell.wide {:style      {:padding-left "4px" :color "rebeccapurple"}
                          :data-index i}
       ($ :img.avatar {:src "/avatar.png" :alt ""})
       ($ :span.label "row ")
       ($ :em.n text))))

(defui w1 [{:keys [rows]}]
  ($ :section.panel {:aria-label "bench rows"}
     ($ :h1.title "Rows")
     ($ :ul.rows {:role "list"}
        (for [i (range rows)]
          ($ w1-row {:key i :i i})))))

;; ---------------------------------------------------------------------------
;; W3 — the ordinary form
;; ---------------------------------------------------------------------------

(defui w3-field [{:keys [i]}]
  (let [{:keys [value error]} (uix-adapter/use-subscribe [:p0/field i])]
    ($ :div.field
       ($ :label.lbl {:for (str "f" i)} (str "Field " i))
       ($ :input.inp {:id        (str "f" i)
                      :name      (str "f" i)
                      :type      "text"
                      :value     value
                      :read-only true})
       ($ :p.err error))))

(defui w3 [{:keys [fields]}]
  ($ :form.w3form
     ($ :fieldset.fields
        (for [i (range fields)]
          ($ w3-field {:key i :i i})))
     ($ :button.submit {:type "submit" :disabled true} "Submit")))

;; ---------------------------------------------------------------------------
;; U — the bulk witness
;; ---------------------------------------------------------------------------

(defui u-cell [{:keys [i]}]
  (let [v (uix-adapter/use-subscribe [:p0/cell i])]
    ($ :span.cell {:data-i i} (str v))))

(defui u-grid [{:keys [n]}]
  ($ :div.ugrid
     (for [i (range n)]
       ($ u-cell {:key i :i i}))))

;; ---------------------------------------------------------------------------
;; FAN — the fan-out family (rf2-5prok)
;; ---------------------------------------------------------------------------
;;
;; The UIx counterpart of `p0-reagent`'s fan family, written the same way
;; for the same reasons: one `defui` per read count (a `for` over the read
;; count inside one component is a hook-linter error and would be a
;; different question anyway), identical props at every rung so the shell
;; rung is not measuring one fewer prop slot, and identical DOM at every
;; rung so the floor subtraction stays honest.

(defui fan-cell-0 [{:keys [j]}]
  ($ :span.cell {:data-i j} "0"))

(defui fan-cell-1 [{:keys [j n]}]
  (let [a (uix-adapter/use-subscribe [:p0/fan (fx/fan-key n 1 0)])]
    ($ :span.cell {:data-i j} (str a))))

(defui fan-cell-2 [{:keys [j n]}]
  (let [a (uix-adapter/use-subscribe [:p0/fan (fx/fan-key n 2 0)])
        b (uix-adapter/use-subscribe [:p0/fan (fx/fan-key n 2 1)])]
    ($ :span.cell {:data-i j} (str (+ a b)))))

(defui fan-grid [{:keys [offset n-cells reads]}]
  ($ :div.ugrid
     (case (long reads)
       0 (for [j (range n-cells)] ($ fan-cell-0 {:key j :j j :n (+ offset j)}))
       1 (for [j (range n-cells)] ($ fan-cell-1 {:key j :j j :n (+ offset j)}))
       2 (for [j (range n-cells)] ($ fan-cell-2 {:key j :j j :n (+ offset j)})))))

;; ---------------------------------------------------------------------------
;; LAD — the reads-per-boundary ladder (rf2-2rtt6.34)
;; ---------------------------------------------------------------------------
;;
;; UNROLLED, and it has to be. `use-subscribe` is a hook over
;; `useSyncExternalStore`, so React's rule is that the call sequence is
;; identical on every render of an instance. A loop would satisfy that in
;; fact — `r` never changes for a mounted instance — but not by
;; construction, and UIx's linter reads the loop rather than the fact.
;; Writing the calls out is also the honest thing on a page that is
;; pricing hook calls: the reader can count them. The Reagent counterpart
;; loops, and that difference is the substrates', not the instrument's.
;;
;; Every rung's cell takes the SAME two props, so the argv a fiber retains
;; is the same shape at R = 0 and at R = 20 and the shell rung is not
;; measuring one fewer prop slot than the rungs it anchors.

(defn- lus [n r k] (uix-adapter/use-subscribe [:p0/fan (fx/fan-key n r k)]))

(defui lad-cell-0 [{:keys [j _n]}]
  ($ :span.cell {:data-i j} "0"))

(defui lad-cell-1 [{:keys [j n]}]
  (let [v (lus n 1 0)]
    ($ :span.cell {:data-i j} (str v))))

(defui lad-cell-3 [{:keys [j n]}]
  (let [v (+ (lus n 3 0) (lus n 3 1) (lus n 3 2))]
    ($ :span.cell {:data-i j} (str v))))

(defui lad-cell-7 [{:keys [j n]}]
  (let [v (+ (lus n 7 0) (lus n 7 1) (lus n 7 2) (lus n 7 3)
             (lus n 7 4) (lus n 7 5) (lus n 7 6))]
    ($ :span.cell {:data-i j} (str v))))

(defui lad-cell-20 [{:keys [j n]}]
  (let [v (+ (lus n 20 0)  (lus n 20 1)  (lus n 20 2)  (lus n 20 3)
             (lus n 20 4)  (lus n 20 5)  (lus n 20 6)  (lus n 20 7)
             (lus n 20 8)  (lus n 20 9)  (lus n 20 10) (lus n 20 11)
             (lus n 20 12) (lus n 20 13) (lus n 20 14) (lus n 20 15)
             (lus n 20 16) (lus n 20 17) (lus n 20 18) (lus n 20 19))]
    ($ :span.cell {:data-i j} (str v))))

(def ^:private lad-cell-by-reads
  {0 lad-cell-0, 1 lad-cell-1, 3 lad-cell-3, 7 lad-cell-7, 20 lad-cell-20})

(defui lad-grid [{:keys [offset n-cells reads]}]
  ($ :div.ugrid
     (let [c (get lad-cell-by-reads (long reads))]
       (for [j (range n-cells)]
         ($ c {:key j :j j :n (+ offset j)})))))

;; ---------------------------------------------------------------------------
;; Roots — the frame scope every read resolves through
;; ---------------------------------------------------------------------------
;;
;; `frame-provider` SCOPES an already-created frame and renders no DOM of
;; its own, so it cannot move the canonical-DOM gate. The frame is created
;; outside the measured window by the segment's setup: a mount window that
;; contained `make-frame` and a seeding dispatch would be pricing frame
;; construction on the arm that happens to own it.

(defn w1-root [frame-id rows]
  ($ uix-adapter/frame-provider {:frame frame-id}
     ($ w1 {:rows rows})))

(defn w3-root [frame-id fields]
  ($ uix-adapter/frame-provider {:frame frame-id}
     ($ w3 {:fields fields})))

(defn u-root [frame-id]
  ($ uix-adapter/frame-provider {:frame frame-id}
     ($ u-grid {:n fx/cells-n})))

(defn fan-root
  "One root of the fan-out arm. `offset` is this root's base in the GLOBAL
  boundary numbering, which is what lets four roots of one frame either
  share every key or share none."
  [frame-id offset n-cells reads]
  ($ uix-adapter/frame-provider {:frame frame-id}
     ($ fan-grid {:offset offset :n-cells n-cells :reads reads})))

(defn lad-root
  "One root of the ladder arm (rf2-2rtt6.34). `offset` is this root's base
  in the GLOBAL boundary numbering, as [[fan-root]]'s is."
  [frame-id offset n-cells reads]
  ($ uix-adapter/frame-provider {:frame frame-id}
     ($ lad-grid {:offset offset :n-cells n-cells :reads reads})))

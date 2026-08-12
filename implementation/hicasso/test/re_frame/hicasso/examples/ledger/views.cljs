(ns re-frame.hicasso.examples.ledger.views
  "THE SERIOUS-VENDOR SCREEN — ten thousand rows through a foreign
  virtualizer, and the crossing is one declaration (rf2-hic-047).

  `specification.md` §7 answers *Large collections* with a *blessed
  foreign virtualizer recipe* and requires *10K-row behavior, focus and
  accessibility*. This file is that recipe. The narrative version, with
  the reasoning a consumer needs rather than the reasoning a reviewer
  needs, is
  [`docs/design/hicasso/product/virtualizer-recipe.md`](../../../../../../../docs/design/hicasso/product/virtualizer-recipe.md).

  ## The whole crossing

      (h/defhost rows vendor/virtual-rows
        {:callbacks {:render-row :render
                     :on-window  :event}})

  Two declared positions, two different contracts, and neither is
  inferred from the prop's spelling. `:render-row` is where the library
  asks the consumer for markup, DURING its own render; `:on-window` is
  where it tells the consumer something afterwards. Every other prop —
  `:count`, `:row-height`, `:pinned-index` — is ordinary data and needs
  no declaration at all.

  ## Rule 1 — the key is the row's place in the MODEL

      [ledger-row {:key (row-key i) :index i :offset offset}]

  This is the rule the whole screen turns on, and it is the one a
  virtualizer makes easy to get wrong, because a windowing component
  hands the consumer a *position in the window* on every render and the
  positions are stable while the records under them are not.

  Key by the window slot and React sees twenty-six unchanged children
  across a scroll: it reuses every DOM node and merely re-supplies the
  props. On screen that is indistinguishable from correct. In the
  document the input the user is typing into now belongs to a different
  record — same node, same caret, same focus, different row — so the next
  keystroke lands in somebody else's data.
  `virtualized-dom-cljs-test` mounts exactly that variant and measures
  the defect rather than describing it.

  Key by the model index and React sees the truth: the rows that stayed
  kept their nodes, the rows that left were unmounted, the rows that
  arrived were mounted. Focus, caret, selection and any DOM state a row
  is holding travel with the record, because they never moved.

  ## Rule 2 — the row reads its own record

  [[ledger-row]] takes an index and an offset. Everything it displays it
  reads, and `ledger.subs` explains why: a parent that read the window's
  records and passed them down would re-render on every scroll and on
  every keystroke in any visible row, and would put the whole collection
  one refactor away from the render path.

  It also matters for the key. A row that reads by index needs no data
  from its parent to be minted, so the render callback needs no data
  either — which is fortunate, because **a subscription may not be read
  inside a render callback**: the callback runs during the vendor's
  render, outside the supplying boundary's read extent, and Hicasso
  refuses a read that escapes it (I7). A row shape that needed its record
  at the call site could not be written here at all.

  ## Rule 3 — the focused row is pinned, and the application still does
  not touch focus

  `:on-focus` writes the row's index into `app-db`; the screen reads it
  back as `:pinned-index`; the vendor keeps that row mounted wherever the
  window has got to. Nothing calls `.focus()` and nothing reads
  `document.activeElement`. The platform keeps owning focus — the pin
  only stops React from deleting the node the platform's focus is
  already in. See `ledger.events`'s docstring on `:focused`.

  ## Rule 4 — the count the reader hears is the MODEL's

  `aria-rowcount` is ten thousand while twenty-six rows exist, and each
  row's `aria-rowindex` is its model index plus one. Those two attributes
  are the entire accessibility story of virtualization: without them a
  screen reader announces a twenty-six-row table, confidently and
  wrongly. They are also the pair a window-relative implementation gets
  wrong while looking right, so the a11y suite asserts the VALUES and the
  DOM suite asserts them again after a scroll.

  ## What was NOT needed

  No `n/defcomponent`, no ref, no effect, no imperative handle, no memo
  hint and no second root. The application's whole native-tier surface is
  `h/defhost` and `h/hfn`; the island the imperative-SDK row needs
  (rf2-hic-067) is not needed here, because a virtualizer is an ordinary
  React component and the door for an ordinary React component is the
  declaration."
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.ledger.events :as events]
            [re-frame.hicasso.examples.ledger.subs :as subs]
            [re-frame.hicasso.examples.ledger.vendor :as vendor]))

;; ---------------------------------------------------------------------------
;; Geometry
;; ---------------------------------------------------------------------------
;;
;; Public, and read by the witnesses: the window a scroll offset produces
;; is arithmetic over exactly these three numbers, so a suite that hard-coded
;; them would be asserting about a different screen the day one moved.

(def row-height "One row, in pixels." 24)
(def viewport-height "The scrolling viewport, in pixels — twenty rows." 480)
(def overscan "Rows rendered beyond each edge of the viewport." 3)

(defn row-key
  "The React key of the row at model index `i`.

  A function, and named, because it is Rule 1 and the sabotage in
  `virtualized-dom-cljs-test` is precisely a different one of these."
  [i]
  (str "row-" i))

;; ---------------------------------------------------------------------------
;; The crossing
;; ---------------------------------------------------------------------------

(h/defhost rows
  "The declared door onto the virtualizer.

  `:render-row` carries the `:render` contract, so what the callback
  returns is the vendor's render output and is emphatically not
  dispatched — and an intent written INSIDE the call raises rather than
  firing during a foreign render. `:on-window` carries `:event`, so a
  vector returned from it is dispatched under the frame of the boundary
  that wrote the crossing.

  Both are `h/hfn` rather than intent vectors, and both for the same
  reason: the vendor invokes them VALUE-first — `renderRow(index,
  offset)`, `onWindow(from, to)` — so there is no event at argument one
  and nothing for a vector's markers to read. The one callback form
  receives the library's own arguments, in order (HD-024)."
  vendor/virtual-rows
  {:callbacks {:render-row :render
               :on-window  :event}})

;; ---------------------------------------------------------------------------
;; The screen
;; ---------------------------------------------------------------------------

(h/defview ledger-row
  "One record's row: its name, its note and its flag.

  Three reads, all parametric on the model index, and every one of them
  the row's own. Two props, both numbers, and the offset is the only one
  the virtualizer decides — which is what makes a row's props compare
  equal across a re-render of the screen and therefore what makes a
  focus change cost zero row bodies.

  `role`/`aria-rowindex`/`aria-colindex` are written by hand because the
  grid's semantics are the application's, not the vendor's: the vendor
  contributes two `role=\"presentation\"` wrappers and knows nothing
  about tables."
  [{:keys [index offset]}]
  (let [{:keys [id name]} (h/sub [::subs/record index])]
    [:div {:role          "row"
           :class         "ledger-row"
           :aria-rowindex (str (inc index))
           ;; The RECORD's id, on the element a witness reads. Not the
           ;; index: an assertion that focus stayed on `rec-100005` is an
           ;; assertion about the record, where one reading `5` would be
           ;; equally true of the fifth row of the window.
           :data-record   id
           :style         {:position "absolute" :top offset :left 0 :right 0
                           :height   row-height}}
     [:div {:role "gridcell" :aria-colindex "1" :class "ledger-name"} name]
     [:div {:role "gridcell" :aria-colindex "2"}
      [:input {:id          (events/note-id index)
               :class       "ledger-note"
               :type        "text"
               :aria-label  (events/note-label index)
               :data-record id
               :value       (h/sub [::subs/note index])
               :on-focus    [::events/row-focused {:index index}]
               :on-input    [::events/note index ::h/value]}]]
     [:div {:role "gridcell" :aria-colindex "3"}
      [:button {:class        "ledger-flag"
                :aria-label   (events/flag-label index)
                :aria-pressed (str (h/sub [::subs/flagged? index]))
                :on-click     [::events/flag {:index index}]}
       "Flag"]]]))

(h/defview status-line
  "*Showing rows 41–66 of 10,000.*

  Its own boundary, and that is the whole reason it exists as one: it is
  the only body on the page that reads the window, so a scroll re-runs
  this and nothing else. Fold it into [[ledger]] and every scroll would
  re-run the screen, re-mint the render callback and re-create the whole
  window's worth of elements."
  [_]
  (let [{:keys [from to]} (h/sub [::subs/window])
        total             (h/sub [::subs/total])]
    [:p.ledger-status {:role "status"}
     (str "Showing rows " (inc from) "–" (inc to) " of " total)]))

(h/defview ledger
  "The whole application.

  Reads the total, which nothing writes after the seed, and the pinned
  index, which only a focus move writes. Neither is touched by a scroll
  or by a keystroke, so this body runs at mount and on a focus change and
  at no other time."
  [_]
  (let [total  (h/sub [::subs/total])
        pinned (h/sub [::subs/pinned-index])]
    [:section#ledger
     [:h1 "Ledger"]
     [status-line {}]
     [:div {:role          "grid"
            :class         "ledger-grid"
            :aria-label    "Ledger"
            ;; The MODEL's count, beside a document holding twenty-six
            ;; rows. Rule 4.
            :aria-rowcount (str total)
            :aria-colcount "3"}
      [rows {:count           total
             :row-height      row-height
             :viewport-height viewport-height
             :overscan        overscan
             :pinned-index    pinned
             :render-row      (h/hfn [i offset]
                                (h/as-element
                                  [ledger-row {:key    (row-key i)
                                               :index  i
                                               :offset offset}]))
             :on-window       (h/hfn [from to]
                                [::events/window-shown {:from from :to to}])}]]]))

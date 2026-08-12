(ns re-frame.hicasso.examples.ledger.virtualized-dom-cljs-test
  "L3 — TEN THOUSAND ROWS, ON A REAL ENGINE (rf2-hic-047).

  > | Large collections | Suitable read topology and keys | Blessed
  > foreign virtualizer recipe | **10K-row behavior, focus and
  > accessibility** |
  >
  > — product specification §7

  Three claims, and every one of them is about something that only
  exists once React has committed: how many rows are in the document,
  which DOM node the platform's focus is in, and what a screen reader
  would be told about a table whose rows are mostly not there. So this
  file is the screen's acceptance, and the L0 and a11y suites beside it
  are the parts that can honestly be proved without an engine.

  ## The two claims a reader must not conflate

  **Windowing bounds the MOUNTED rows.** That is the virtualizer's job,
  and [[the-document-holds-a-window-and-not-the-model]] measures it
  against a plain unwindowed list of the same rows, which grows.

  **The read topology bounds the rows a change NOTIFIES.** That is the
  application's job, and [[work-follows-the-data-and-not-the-model]]
  measures it at two model sizes.

  A screen with the first and not the second re-renders every mounted row
  on every keystroke. A screen with the second and not the first mounts
  ten thousand DOM subtrees and then updates one of them. Both are
  wrong, differently, and the numbers below are reported separately so
  that a failure says which half moved. The coarse-read sabotage that
  makes the second claim red is `examples.grid.scaling-dom-cljs-test`'s
  and is deliberately not repeated here; what this file adds is the
  WINDOW as the bound.

  ## The two variants, and why an anti-pattern lives in the control

  `examples.ledger.views` is the application, and it is evidence about
  the door. The two shapes below are the recipe's rules with one rule
  taken out each, and they belong here because a positive control has to
  be able to make the gate red:

  | variant | the rule it drops | what it proves |
  |---|---|---|
  | [[unkeyed-ledger]] | Rule 1 — the key is the model index | focus and caret land in a DIFFERENT record after an ordinary scroll |
  | [[unpinned-ledger]] | Rule 3 — the focused row stays mounted | focus is destroyed by a scroll that leaves the row behind |

  **The first one is the finding this screen exists to publish.**
  Hicasso's unkeyed-children warning does not reach a render callback:
  the array of children belongs to the vendor, the substrate never walks
  it, and React's own console warning is the only thing that fires. So
  the rule cannot be enforced at the crossing and has to be written down
  — which is what `docs/design/hicasso/product/virtualizer-recipe.md`
  is for, and what [[a-slot-keyed-window-moves-focus-to-another-record]]
  measures rather than describes.

  ## Browser lane

  Every row needs a real document, real focus and a real scroll: the
  subject is what the platform does with a node when React deletes it.
  `:node-test` compiles this namespace too (`cljs-test$` matches
  `-dom-cljs-test`) and each row degrades there to a STATED skip rather
  than to a false green — the posture every other `*-dom` suite here
  keeps."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.ledger.app :as app]
            [re-frame.hicasso.examples.ledger.events :as events]
            [re-frame.hicasso.examples.ledger.subs :as subs]
            [re-frame.hicasso.examples.ledger.vendor :as vendor]
            [re-frame.hicasso.examples.ledger.views :as views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]
            ["react-dom" :as react-dom]))

;; ---------------------------------------------------------------------------
;; The controls — the recipe with one rule removed each
;; ---------------------------------------------------------------------------

(h/defview unkeyed-ledger
  "**Rule 1 removed.** Identical to `views/ledger` except that the row
  written in the render callback carries no `:key`.

  React then reconciles the vendor's children BY ARRAY POSITION, which
  for a windowed list is the position in the WINDOW — so a scroll of
  three rows hands every DOM node to the record three places further
  down, silently, while the page keeps looking right."
  [_]
  (let [total  (h/sub [::subs/total])
        pinned (h/sub [::subs/pinned-index])]
    [:div {:role "grid" :aria-rowcount (str total)}
     [views/rows {:count           total
                  :row-height      views/row-height
                  :viewport-height views/viewport-height
                  :overscan        views/overscan
                  :pinned-index    pinned
                  :render-row      (h/hfn [i offset]
                                     (h/as-element
                                       [views/ledger-row {:index i :offset offset}]))}]]))

(h/defview unpinned-ledger
  "**Rule 3 removed.** Identical to `views/ledger` except that the
  virtualizer is never told which row the platform's focus is in, so it
  unmounts that row like any other the moment the window leaves it."
  [_]
  (let [total (h/sub [::subs/total])]
    [:div {:role "grid" :aria-rowcount (str total)}
     [views/rows {:count           total
                  :row-height      views/row-height
                  :viewport-height views/viewport-height
                  :overscan        views/overscan
                  :pinned-index    -1
                  :render-row      (h/hfn [i offset]
                                     (h/as-element
                                       [views/ledger-row {:key    (views/row-key i)
                                                          :index  i
                                                          :offset offset}]))}]]))

(h/defview plain-list
  "The same rows with no virtualizer at all — the contrast that makes
  *the document holds a window* a measurement rather than a reading of
  one number.

  Mounted at two sizes it grows with the model; the ledger does not. It
  is not an anti-pattern: for a hundred rows it is the right shape, and
  saying so is half of what a virtualizer recipe is for."
  [_]
  (let [total (h/sub [::subs/total])]
    [:div {:role "grid" :aria-rowcount (str total)}
     (for [i (range total)]
       [views/ledger-row {:key    (views/row-key i)
                          :index  i
                          :offset (* i views/row-height)}])]))

;; The fixture snapshots the registrar when THIS form is evaluated, so it
;; sits below the three views above.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    ;; The MAP shape, because [[the-screen-leaves-nothing-behind]] is
    ;; `async` and `cljs.test` aborts the WHOLE RUN — every namespace
    ;; after this one included — on an async row under a positional
    ;; fixture. Observed: one line of `Testing aborted.` and no summary.
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true}))

;; ---------------------------------------------------------------------------
;; The instruments
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a real document, real focus and a real scroll are the whole
                 subject — " why)))

(defn- mount-ledger!
  ([total] (mount-ledger! total [views/ledger {}]))
  ([total form] (hm/mount! form {:initial-events (app/initial-events total)})))

(defn- q [m sel] (.querySelector (:container m) sel))
(defn- q* [m sel] (array-seq (.querySelectorAll (:container m) sel)))

(defn- rows-in [m] (q* m "[role='row']"))
(defn- grid-in [m] (q m "[role='grid']"))
(defn- viewport-in [m] (q m ".ledger-viewport"))

(defn- note-node
  "The note field of the row showing model index `i`, or nil when that
  row is not in the document."
  [m i]
  (q m (str "#" (events/note-id i))))

(defn- record-of
  "The record id the node BELONGS TO, read off the DOM. The whole of the
  identity instrument: every assertion about focus below is an assertion
  about this string."
  [^js node]
  (some-> node (.getAttribute "data-record")))

(defn- active [] (.-activeElement js/document))

(defn- window-at
  "The window `views/ledger`'s geometry puts on screen at model row `i`,
  derived rather than typed — see `ledger.l0-cljs-test`, which is where
  the arithmetic itself is held."
  [i total]
  (vendor/window-from (* i views/row-height)
                      {:row-height      views/row-height
                       :viewport-height views/viewport-height
                       :overscan        views/overscan
                       :total           total}))

(defn- window-size [i total]
  (let [[from to] (window-at i total)] (inc (- to from))))

(defn- scroll-to!
  "Scroll the viewport to model row `i` and let React commit — all of it.

  Three steps, and the second and third are here because the first alone
  measures a page that has not moved yet.

  1. Set `scrollTop` and assert it STUCK. A `scrollTop` assignment to an
     element the engine does not consider scrollable is silently ignored,
     and every count below would then be about a screen that never
     scrolled.

  2. Dispatch the `scroll` event INSIDE `flushSync`. A scroll is a
     CONTINUOUS-priority event, so the vendor's `setState` lands in a
     lane that `hm/settle!`'s empty `flushSync` does not flush — and the
     first run of this file failed exactly there, reading `aria-rowindex`
     of `1` four hundred and ninety-seven rows into the model with the
     `scrollTop` assertion above passing. `flushSync` upgrades the
     update to the sync lane, which is what makes the next line's DOM
     read honest.

  3. Settle twice. The window report is a PASSIVE effect: React runs it
     after the commit that scheduled it, so the first settle is what
     lets `on-window` dispatch and the second is what commits the status
     line the dispatch notified. A single settle leaves the status
     line's body run outside the measurement it belongs to."
  [m i]
  (let [^js vp (viewport-in m)
        want   (* i views/row-height)]
    (set! (.-scrollTop vp) want)
    (is (= want (.-scrollTop vp))
        "the viewport did not scroll — the instrument, not the screen")
    (react-dom/flushSync
      (fn [] (.dispatchEvent vp (js/Event. "scroll" #js {:bubbles true}))))
    (hm/settle! m)
    (hm/settle! m)
    ;; 4. And assert the WINDOW moved, not merely the offset. Steps 1 and
    ;;    2 can both succeed while the window stands still — that is
    ;;    exactly what the first two runs of this file did — and the
    ;;    resulting failures land three assertions away from the cause,
    ;;    in whichever row happened to read the DOM first.
    (let [total    (js/parseInt (.getAttribute (grid-in m) "aria-rowcount") 10)
          [from _] (window-at i total)]
      (is (some? (note-node m from))
          (str "the viewport scrolled but the window did not follow it to row "
               from " — the instrument, not the screen")))
    m))

(defn- set-native-value! [n v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) n v)))

(defn- type-into! [m ^js n text]
  (set-native-value! n (str (.-value n) text))
  (.dispatchEvent n (js/Event. "input" #js {:bubbles true}))
  (hm/settle! m)
  nil)

(defn- focus! [m ^js n]
  (.focus n)
  (hm/settle! m)
  nil)

;; ---------------------------------------------------------------------------
;; The premise — every instrument below can answer, and answers non-trivially
;; ---------------------------------------------------------------------------

(deftest the-screen-mounts-ten-thousand-records-into-twenty-four-rows
  (if-not (browser?)
    (skip! "the premise")
    (let [m (mount-ledger! 10000)]
      (is (= "10000" (.getAttribute (grid-in m) "aria-rowcount"))
          "the model's count, announced")
      (is (= (window-size 0 10000) (count (rows-in m)))
          "and the document's, which is twenty-four: twenty visible rows
           plus three of overscan below and none above. The two numbers
           being different IS the subject of this file")
      (is (= 24 (window-size 0 10000))
          "stated as a constant once, so a reader knows what the derived
           number above is worth")
      (testing "the rows are the MODEL's first rows, in order"
        (is (= "rec-100000" (record-of (first (rows-in m)))))
        (is (= "rec-100023" (record-of (last (rows-in m))))))
      (testing "and each carries a real, operable field"
        (is (some? (note-node m 0)))
        (is (= "Note for Record 0" (.getAttribute (note-node m 0) "aria-label"))))
      (hm/unmount! m))))

(deftest the-vendors-own-wrappers-are-presentational
  ;; A `role="grid"` owns `role="row"`, and a virtualizer puts two divs
  ;; between them. Without this the rows stop being the grid's rows — a
  ;; whole table's semantics lost to a scroll container, and the reason
  ;; the recipe calls a virtualizer *blessed* rather than merely fast.
  (if-not (browser?)
    (skip! "the wrapper roles")
    (let [m (mount-ledger! 10000)]
      (is (= "presentation" (.getAttribute (viewport-in m) "role")))
      (is (= "presentation" (.getAttribute (q m ".ledger-spacer") "role")))
      (testing "and the spacer is the MODEL's full height, which is what
                makes the scrollbar honest"
        (is (= (str (* 10000 views/row-height) "px")
               (.. ^js (q m ".ledger-spacer") -style -height))))
      (hm/unmount! m))))

;; ---------------------------------------------------------------------------
;; Windowing bounds the mounted rows
;; ---------------------------------------------------------------------------

(deftest the-document-holds-a-window-and-not-the-model
  (if-not (browser?)
    (skip! "the windowing claim")
    (let [small (mount-ledger! 100)
          n-100 (count (rows-in small))
          _     (hm/unmount! small)
          big   (mount-ledger! 10000)
          n-10k (count (rows-in big))]
      (is (= n-100 n-10k)
          "THE ACCEPTANCE. A hundred records and ten thousand put the same
           number of rows in the document. Multiplying the model by a
           hundred changed nothing about the page, which is what
           `10K-row behavior` means before any timing is involved")
      (is (= 24 n-10k))
      (hm/unmount! big)))

  (testing "and the unwindowed shape GROWS, which is what says the number
            above is a measurement"
    (if-not (browser?)
      (skip! "the contrast")
      (let [a (mount-ledger! 100 [plain-list {}])
            b (mount-ledger! 300 [plain-list {}])]
        (is (= 100 (count (rows-in a))))
        (is (= 300 (count (rows-in b)))
            "the same rows, the same application, no virtualizer — and the
             document is the model. For a hundred rows that is the right
             shape; the ledger's claim is about the shape that is still
             right at ten thousand")
        (hm/unmount! a)
        (hm/unmount! b)))))

(deftest the-announced-position-follows-the-model-across-a-scroll
  (if-not (browser?)
    (skip! "the accessibility claim under scroll")
    (let [m         (mount-ledger! 10000)
          _         (scroll-to! m 500)
          [from to] (window-at 500 10000)]
      (is (= "10000" (.getAttribute (grid-in m) "aria-rowcount"))
          "unmoved: the count a reader hears is the model's, wherever the
           window has got to")
      (is (= (inc (- to from)) (count (rows-in m)))
          "and the document still holds one window")
      (is (= (str (inc from)) (.getAttribute (first (rows-in m)) "aria-rowindex"))
          "THE ROW THAT MATTERS. The first row in the document announces
           itself as row 498 of 10,000 — its place in the MODEL. A
           window-relative implementation announces `1` here, is correct
           about the document, wrong about the data, and looks identical
           on screen")
      (is (= (str "rec-" (+ 100000 from)) (record-of (first (rows-in m))))
          "and it is the record the position claims it is")
      (hm/unmount! m))))

;; ---------------------------------------------------------------------------
;; Keyed identity across a scroll — the finding
;; ---------------------------------------------------------------------------

(defn- focused-typing-at
  "Scroll to row `i`, focus its note field, type `text`, and answer the
  mount. The setup every identity row below shares."
  [m i text]
  (scroll-to! m i)
  (let [n (note-node m i)]
    (is (some? n) (str "row " i " is in the document before anything is claimed"))
    (focus! m n)
    (type-into! m n text)
    (is (identical? n (active)) "and the field has focus, before the scroll")
    (is (= text (.-value ^js n)))
    m))

(deftest a-scroll-leaves-focus-caret-and-value-on-the-same-record
  (if-not (browser?)
    (skip! "the identity claim")
    (let [m      (mount-ledger! 10000)
          _      (focused-typing-at m 40 "chase")
          before (active)]
      (scroll-to! m 43)
      (is (identical? before (active))
          "THE ACCEPTANCE. The SAME DOM node still has focus after the
           window moved three rows. Not an equal node — the same one, so
           the caret position, the selection and anything else the
           platform is holding on it were never disturbed")
      (is (= "rec-100040" (record-of (active)))
          "and it still belongs to record 40")
      (is (= "chase" (.-value ^js (active)))
          "carrying what was typed into it")
      (hm/unmount! m))))

(deftest a-slot-keyed-window-moves-focus-to-another-record
  ;; THE POSITIVE CONTROL, and the finding this screen publishes. Without
  ;; it the row above is green having proved only that a scroll of three
  ;; rows can leave a node alone — which a screen that never re-rendered
  ;; would also satisfy.
  (if-not (browser?)
    (skip! "the sabotage")
    (let [m      (mount-ledger! 10000 [unkeyed-ledger {}])
          _      (focused-typing-at m 40 "chase")
          before (active)]
      (scroll-to! m 43)
      (is (identical? before (active))
          "the node is still focused — which is exactly why this defect
           survives review: nothing looks wrong")
      (is (= "rec-100043" (record-of (active)))
          "THE DEFECT, measured. The user's caret is now in RECORD 43's
           field. They did not move it, nothing announced the change, and
           the next keystroke lands in somebody else's data. One missing
           `:key` in a render callback")
      (is (= "" (.-value ^js (active)))
          "and what they typed is gone from under the caret — it is still
           on record 40, three rows above, where nothing is looking")
      (testing "the difference is one prop, and it is the key"
        (is (= "rec-100043" (record-of (active)))
            "the correct screen answers `rec-100040` at this exact point;
             the two variants differ in nothing else"))
      (hm/unmount! m))))

;; ---------------------------------------------------------------------------
;; Focus continuity — the pin
;; ---------------------------------------------------------------------------

(deftest focus-survives-a-scroll-that-leaves-the-row-far-behind
  (if-not (browser?)
    (skip! "the focus-continuity claim")
    (let [m      (mount-ledger! 10000)
          _      (focused-typing-at m 40 "chase")
          before (active)]
      (scroll-to! m 500)
      (is (nil? (note-node m 60))
          "the premise: the window really has left row 40's neighbourhood
           behind — row 60 was mounted a moment ago and is gone")
      (is (some? (note-node m 40))
          "and row 40 is still in the document. Not because it is visible
           — it is four hundred rows above the viewport — but because the
           application told the virtualizer which row the platform's focus
           is in, and the virtualizer kept it")
      (is (identical? before (active))
          "THE ACCEPTANCE. The same node, still focused, after a scroll
           that unmounted every one of its neighbours")
      (is (= "chase" (.-value ^js (active)))
          "with what was typed into it")
      (testing "and the application never touched focus to achieve it —
                the pin only stops React deleting the node the platform's
                focus is already in"
        (is (= 40 (rf/subscribe-once [::subs/pinned-index] {:frame (:frame m)}))
            "the one thing the application knows about focus: an index"))
      (hm/unmount! m))))

(deftest without-the-pin-a-scroll-destroys-the-focused-node
  ;; THE POSITIVE CONTROL for the row above.
  (if-not (browser?)
    (skip! "the sabotage")
    (let [m (mount-ledger! 10000 [unpinned-ledger {}])]
      (focused-typing-at m 40 "chase")
      (scroll-to! m 500)
      (is (nil? (note-node m 40))
          "row 40's node is gone — React deleted the subtree the caret was
           in, which is the ordinary behaviour of every virtualizer and the
           reason the recipe has a Rule 3")
      (is (identical? (.-body js/document) (active))
          "and focus fell to the document body. The user was typing; now
           they are typing nowhere, and no keystroke will reach the field
           they were in")
      (hm/unmount! m))))

;; ---------------------------------------------------------------------------
;; Work follows the data
;; ---------------------------------------------------------------------------

(deftest work-follows-the-data-and-not-the-model
  (if-not (browser?)
    (skip! "the body-count claims")
    (letfn [(keystroke-cost [total]
              (let [m (mount-ledger! total)
                    n (note-node m 0)]
                (try (hm/bodies-run #(type-into! m n "a"))
                     (finally (hm/unmount! m)))))
            (scroll-cost [total]
              (let [m (mount-ledger! total)]
                (scroll-to! m 40)
                (try (hm/bodies-run #(scroll-to! m 43))
                     (finally (hm/unmount! m)))))]
      (testing "a keystroke costs one body, and the model's size is not in it"
        (let [at-100 (keystroke-cost 100)
              at-10k (keystroke-cost 10000)]
          (is (= 1 at-100 at-10k)
              "ONE — the row that was typed into. Not the screen, whose
               body reads the total and the pinned index and is therefore
               not notified; not the status line, which reads the window;
               and not the twenty-three other mounted rows, which read
               addresses this write did not reach")))

      (testing "a scroll costs the rows that ENTERED, and again the model's
                size is not in it"
        (let [at-100 (scroll-cost 100)
              at-10k (scroll-cost 10000)]
          (is (= at-100 at-10k)
              "the same number at a hundred records and at ten thousand")
          (is (= 4 at-10k)
              "three rows entered the window and the status line reported
               the move. The rows that STAYED ran nothing — their props
               did not move — and the rows that LEFT ran nothing because
               unmounting is not a body run. Work follows the data in the
               other direction too"))))))

(deftest a-focus-move-costs-the-screen-and-no-rows
  ;; The price of Rule 3, as a number rather than as an assurance. The
  ;; pin is read by the screen's own body, so a focus move re-runs it —
  ;; and the question a reader should be able to settle is whether that
  ;; re-run reaches the rows.
  (if-not (browser?)
    (skip! "the pin's price")
    (let [m (mount-ledger! 10000)
          n (note-node m 5)]
      (is (= 1 (hm/bodies-run #(focus! m n)))
          "ONE: the screen's own body, because `:pinned-index` moved. The
           twenty-four mounted rows ran nothing — the render callback
           re-created their elements with identical props and every one
           of them bailed out. That is what makes the pin affordable, and
           it is a fact about the row's props being two numbers")
      (hm/unmount! m))))

;; ---------------------------------------------------------------------------
;; Teardown
;; ---------------------------------------------------------------------------

(deftest the-screen-leaves-nothing-behind
  ;; The bead's *no second state owner or root anywhere*, in the form the
  ;; kit can hold: one root, one frame, and a residue reading against this
  ;; mount's own baseline. The vendor's scroll offset is React state and
  ;; dies with the fiber; the application holds no reactive cell of its
  ;; own anywhere, which `ledger.surface-cljs-test`'s roster is the
  ;; structural half of.
  (if-not (browser?)
    (skip! "the teardown claim")
    (async done
      (let [m (mount-ledger! 10000)]
        (scroll-to! m 500)
        (focus! m (note-node m 500))
        (type-into! m (note-node m 500) "x")
        (-> (hm/unmount! m)
            (hm/assert-clean!)
            (.then (fn [_] (done))))))))

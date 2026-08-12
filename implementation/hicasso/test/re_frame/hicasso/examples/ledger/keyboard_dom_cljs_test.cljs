(ns re-frame.hicasso.examples.ledger.keyboard-dom-cljs-test
  "L4 — WHAT A KEYBOARD CAN REACH ON THE TEN-THOUSAND-ROW SCREEN
  (rf2-hic-049, over rf2-hic-047's ledger).

  > | Accessibility | Semantic Hiccup and native controls | Structural
  > a11y assertions plus browser focus tests | Names, roles, **keyboard,
  > virtualized/overlay focus** |
  >
  > — product specification §7

  `ledger.a11y-cljs-test` says what the markup CLAIMS: this is a row,
  that is a textbox, here is its name. `ledger.virtualized-dom-cljs-test`
  says what happens to a focused node when the window moves under it.
  Neither asks the question a keyboard user actually has, which is
  **what can I get to, and does getting to it work** — and that question
  has an answer only an engine can give, because focusability is decided
  by the engine from `disabled`, from `tabindex` and from the tag, and
  none of those three is a fact about a hiccup vector.

  Virtualization makes the question sharp. Ten thousand records exist
  and forty-eight controls are operable at any instant; the other
  nineteen thousand nine hundred and fifty-two are not merely off screen
  but ABSENT, and no amount of tabbing reaches them from where the
  viewport currently is. That is not a defect — it is the trade a
  virtualizer is bought for — but it is a fact a screen has to be
  designed around, and the design is measured here:
  [[the-keyboard-reachable-set-is-the-window-and-not-the-model]] states
  the bound, and
  [[traversing-to-the-window-edge-scrolls-and-extends-the-reachable-set]]
  states the mitigation that makes the whole model reachable anyway.

  ## The instrument, and why it ASKS rather than infers

  [[focusable?]] blurs whatever holds focus, asks for focus on one
  element, and reads `document.activeElement` back. Every other way of
  answering — a `:tabindex` attribute, a selector, a tag table — is a
  SECOND OPINION about the very thing under test, and the two opinions
  disagree exactly where it matters: a `role=\"button\"` on a `<div>`
  looks operable in every projection and is unreachable in fact.
  `slice.a11y-focus-dom-cljs-test` established the idiom on a static
  screen; this file is the same instrument on a screen where the
  candidate set moves.

  ## What is measured here, and what is stated

  **Measured.** Which elements the engine will focus; the complete order
  they come in; that the order is exactly the mounted window's controls
  and nothing else; that focusing the window's last row makes the ENGINE
  scroll the viewport; that the previously-focused node survives the
  window moving; and that the screen carries no control whose role
  promises an activation its tag cannot deliver.

  **Stated, because trusted input is not reachable from inside the
  page.** A synthetic `KeyboardEvent` performs no default action in any
  engine, so a Tab press moves focus nowhere and an Escape closes
  nothing — a `pressed Tab` row would be measuring its own dispatcher.
  Sequential navigation is therefore witnessed as the ENGINE'S OWN
  focusability answer over the candidate set in document order, which is
  what Tab consumes, and Enter/Space activation is witnessed as
  [[every-operable-control-is-one-the-platform-activates]]: the ledger's
  controls are a real `<input>` and a real `<button>`, so their keyboard
  activation is the platform's and needs no handler to exist. Driving a
  real key through a Playwright bridge would need the browser test
  runner, which this bead does not own — see the PR body.

  ## Browser lane

  Focusability, `document.activeElement` and scroll-into-view have no
  implementation in the node lane. `:node-test` compiles this namespace
  too (`cljs-test$` matches `-dom-cljs-test`) and every row degrades
  there to a STATED skip rather than to a false green.

  Nothing here is `async`: every claim is settled inside one tick, which
  is deliberate — an async row under a positional fixture aborts the
  whole `test:browser` run, every later namespace included (rf2-u0j8,
  and the note on `virtualized-dom-cljs-test`'s fixture)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.ledger.app :as app]
            [re-frame.hicasso.examples.ledger.events :as events]
            [re-frame.hicasso.examples.ledger.vendor :as vendor]
            [re-frame.hicasso.examples.ledger.views :as views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]
            ["react-dom" :as react-dom]))

;; ---------------------------------------------------------------------------
;; The control page — the two defects this file's instruments must catch
;; ---------------------------------------------------------------------------

(h/defview a-page-of-near-misses
  "Three controls that all LOOK operable, and one of them is.

  It is a page rather than a ledger variant on purpose: what the two
  instruments below have to be shown finding is a property of one
  element, and a twenty-four-row screen carrying it would test the
  instrument through four hundred lines of virtualizer."
  [_]
  [:div
   [:button#real {:type "button"} "A real button"]
   ;; Reachable — a `tabindex` buys a place in the order — and still not
   ;; activatable: Enter and Space do nothing on a `<div>`, whatever its
   ;; role says, unless the author wrote a key handler. The commonest
   ;; keyboard defect there is, and the one a focus-order check alone
   ;; reports as fine.
   [:div#half {:role "button" :tab-index 0} "Looks like a button"]
   ;; Neither reachable nor activatable.
   [:div#neither {:role "button"} "Not even a tabindex"]])

;; The fixture snapshots the registrar when THIS form is evaluated, so it
;; sits below every view above. The MAP shape, for the reason
;; `virtualized-dom-cljs-test` states: a positional fixture aborts the
;; entire run on an async row, and the map shape is the one that does
;; not — kept here even though nothing in this file is async, so the
;; file cannot acquire the trap by acquiring an async row.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "only an engine decides what a keyboard can reach — " why)))

;; ---------------------------------------------------------------------------
;; Reading the page
;; ---------------------------------------------------------------------------

(defn- q [m sel] (.querySelector (:container m) sel))
(defn- q* [m sel] (array-seq (.querySelectorAll (:container m) sel)))

(defn- viewport-in [m] (q m ".ledger-viewport"))
(defn- note-node [m i] (q m (str "#" (events/note-id i))))
(defn- active [] (.-activeElement js/document))
(defn- record-of [^js node] (some-> node (.getAttribute "data-record")))

(defn- label-of
  "How a failure NAMES an element — its id when it has one, else its
  first class, else its tag. `slice.a11y-focus-dom-cljs-test`'s, so a
  reader who knows one focus order can read the other."
  [^js el]
  (cond
    (seq (.-id el))        (.-id el)
    (seq (.-className el)) (first (.split (.-className el) " "))
    :else                  (.toLowerCase (.-tagName el))))

(defn- focusable?
  "Does the ENGINE accept focus on `el`? Asked, not inferred: blur
  whatever holds focus, ask, and read `document.activeElement` back.

  A `disabled` control, a `tabindex=\"-1\"` control and a `<div>` with
  nothing but a role all leave focus where it was, and not one of those
  three facts is legible in the markup."
  [^js el]
  (some-> (active) (.blur))
  (.focus el)
  (identical? el (active)))

(def ^:private candidates
  "What Tab consumes: the natural-tab-order tags plus anything carrying
  a `tabindex`. The ENGINE decides which of them survive — this selector
  only has to be a superset, and a selector that tried to decide would
  be the second opinion [[focusable?]] exists to avoid."
  "a[href],button,input,select,textarea,[tabindex]")

(defn- keyboard-order
  "The page's sequential-navigation order, as readable names.

  Two pieces of hygiene, both of which the ledger forces and a static
  screen does not:

  1. **The viewport's scroll offset is restored.** Asking for focus on
     an element below the fold makes the engine scroll it into view —
     which is a fact this file measures deliberately elsewhere, and
     which would silently move the window under any row that merely
     wanted to read the order.
  2. **Focus is dropped at the end.** The sweep leaves focus on the last
     element it asked about, and a row asserting where focus IS must not
     be reading this function's leftovers."
  [m]
  (let [^js vp (viewport-in m)
        top    (some-> vp .-scrollTop)
        order  (->> (.querySelectorAll (:container m) candidates)
                    (array-seq)
                    (filterv focusable?)
                    (mapv label-of))]
    (some-> (active) (.blur))
    (when vp (set! (.-scrollTop vp) top))
    order))

(def ^:private activation-roles
  "The ARIA roles that promise the platform will ACTIVATE the element
  from the keyboard. A role from this set on an element the platform
  does not operate is a control that announces itself, takes focus, and
  then does nothing when a keyboard user presses Enter."
  #{"button" "link" "checkbox" "radio" "menuitem" "menuitemcheckbox"
    "menuitemradio" "option" "switch" "tab"})

(defn- natively-operable?
  "Is `el` an element the PLATFORM gives keyboard activation to?"
  [^js el]
  (let [tag (.toLowerCase (.-tagName el))]
    (or (contains? #{"button" "input" "select" "textarea" "summary"} tag)
        (and (= "a" tag) (.hasAttribute el "href")))))

(defn- unoperable-controls
  "Every element whose ROLE promises activation and whose TAG does not
  deliver it, in document order.

  The sweep [[every-operable-control-is-one-the-platform-activates]]
  makes, and the counterpart of rf2-hic-043's `ht/unnamed-controls`: one
  asks whether a control can be NAMED, this asks whether it can be
  OPERATED, and a screen needs both answers to be empty."
  [m]
  (->> (q* m "[role]")
       (filterv (fn [^js el]
                  (and (contains? activation-roles (.getAttribute el "role"))
                       (not (natively-operable? el)))))))

;; ---------------------------------------------------------------------------
;; Driving the screen
;; ---------------------------------------------------------------------------

(defn- mount-ledger!
  ([] (mount-ledger! events/default-total))
  ([total] (hm/mount! [views/ledger {}] {:initial-events (app/initial-events total)})))

(defn- window-at
  "The window `views/ledger`'s geometry puts on screen at scroll offset
  `top`, derived rather than typed — `ledger.l0-cljs-test` is where the
  arithmetic itself is held."
  [top total]
  (vendor/window-from top
                      {:row-height      views/row-height
                       :viewport-height views/viewport-height
                       :overscan        views/overscan
                       :total           total}))

(defn- tell-the-vendor-it-scrolled!
  "Deliver a `scroll` event for the offset the viewport is ALREADY at,
  and let React commit all of it.

  Deliberately not `virtualized-dom-cljs-test`'s `scroll-to!`, which
  SETS `scrollTop` and then notifies: here the engine moved the viewport
  itself, in response to a focus move, and what is under test is that
  the vendor follows the platform. The three mechanics are that file's
  and its docstring holds their reasons — the event goes inside
  `flushSync` because a scroll is a continuous-priority event that an
  empty `flushSync` does not flush, and the settle happens twice because
  the window report is a passive effect."
  [m]
  (let [^js vp (viewport-in m)]
    (react-dom/flushSync
      (fn [] (.dispatchEvent vp (js/Event. "scroll" #js {:bubbles true}))))
    (hm/settle! m)
    (hm/settle! m)
    m))

(defn- controls-of-rows
  "The ids the ledger's rows contribute to a focus order, for the model
  rows `from`..`to` inclusive: a note field and a flag button each, in
  that document order.

  Derived from the application's own id function, so an order asserted
  here and a row rendered there cannot disagree about the spelling."
  [from to]
  (into []
        (mapcat (fn [i] [(events/note-id i) "ledger-flag"]))
        (range from (inc to))))

;; ---------------------------------------------------------------------------
;; The instruments answer, and answer both ways
;; ---------------------------------------------------------------------------

(deftest the-instruments-discriminate
  ;; THE PREMISE ROW. Every claim below is an emptiness or a membership
  ;; claim over two sweeps, and a sweep that could not report anything
  ;; would satisfy all of them at once.
  (if-not (browser?)
    (skip! "the instruments")
    (let [m (hm/mount! [a-page-of-near-misses {}])]
      (testing "focusability is the ENGINE's answer and it goes both ways"
        (is (true? (focusable? (q m "#real"))))
        (is (true? (focusable? (q m "#half")))
            "a `tabindex` buys a place in the order for an element the
             tag table would refuse")
        (is (false? (focusable? (q m "#neither")))))

      (testing "so the ORDER contains the two the engine accepts, in
                document order, and not the third"
        (is (= ["real" "half"] (keyboard-order m))))

      (testing "and the activation sweep reports BOTH divs — including
                the one that is perfectly reachable, which is the defect
                a focus-order check alone calls fine"
        (is (= ["half" "neither"] (mapv label-of (unoperable-controls m))))
        (is (= ["real"] (mapv label-of (q* m "button")))
            "and the real button is NOT among them, so the sweep is
             discriminating rather than reporting every element it
             ranges over"))
      (hm/unmount! m))))

(deftest the-ledgers-controls-are-reachable-and-its-chrome-is-not
  (if-not (browser?)
    (skip! "the candidate set")
    (let [m (mount-ledger!)]
      (testing "premise: the screen mounted the window it always mounts"
        (is (= 24 (count (q* m "[role='row']")))))

      (testing "a row's two controls are both the engine's to focus"
        (is (true? (focusable? (note-node m 0))))
        (is (true? (focusable? (q m ".ledger-flag")))))

      (testing "and the virtualizer's own wrappers are not in the
                sequential-navigation order — they carry
                `role=\"presentation\"`, and an element that announces
                nothing and is reachable by Tab is a stop on the journey
                for no reason"
        (let [order (set (keyboard-order m))]
          (is (not (contains? order "ledger-viewport")))
          (is (not (contains? order "ledger-spacer")))))

      (testing "MEASURED, AND WORTH RECORDING: the engine will
                nonetheless accept a PROGRAMMATIC focus on the scroll
                container, because a scroller is focusable in its own
                right (Chromium's keyboard-focusable scrollers). Being
                focusable and being in the tab order are therefore two
                different questions, and `.focus()` answers only the
                first — which is exactly why [[keyboard-order]] filters
                a CANDIDATE SET rather than sweeping every element on
                the page. The spacer, which does not scroll, is not
                focusable at all"
        (is (true? (focusable? (viewport-in m))))
        (is (false? (focusable? (q m ".ledger-spacer")))))

      (testing "nor is the grid, nor a cell: `role=\"grid\"` is a
                container, and the ledger does not implement the
                roving-tabindex conduct that would make one focusable"
        (is (false? (focusable? (q m "[role='grid']"))))
        (is (false? (focusable? (q m "[role='gridcell']")))))

      (hm/unmount! m))))

;; ---------------------------------------------------------------------------
;; The order, complete — and bounded by the window
;; ---------------------------------------------------------------------------

(deftest the-keyboard-order-is-the-window-in-document-order
  (if-not (browser?)
    (skip! "the order")
    (let [m         (mount-ledger!)
          [from to] (window-at 0 events/default-total)]
      (is (= (controls-of-rows from to) (keyboard-order m))
          "THE COMPLETE SEQUENCE rather than a membership test — a
           control that joined the order, left it, or moved within it is
           invisible to `is this focusable?` and loud here. Forty-eight
           entries: two per mounted row, note before flag, rows in model
           order")

      (testing "and nothing buys itself a place out of turn — a positive
                tabindex hoists a control above everything that has
                none, which is a reordering nothing on screen shows"
        (is (= [] (->> (q* m "[tabindex]")
                       (filterv #(pos? (.-tabIndex ^js %)))
                       (mapv label-of)))))

      (hm/unmount! m))))

(deftest the-keyboard-reachable-set-is-the-window-and-not-the-model
  (if-not (browser?)
    (skip! "the bound")
    (let [small   (mount-ledger! 100)
          n-100   (count (keyboard-order small))
          _       (hm/unmount! small)
          big     (mount-ledger! 10000)
          n-10k   (count (keyboard-order big))]
      (is (= n-100 n-10k)
          "THE BOUND. A hundred records and ten thousand put the same
           number of operable controls within reach of a keyboard —
           which is what `10K-row behavior` costs, stated where an
           accessibility reviewer can see the cost rather than only the
           benefit")
      (is (= 48 n-10k)
          "forty-eight: two controls on each of twenty-four mounted
           rows. `virtualized-dom-cljs-test` holds the contrast — the
           same rows with no virtualizer put the whole model in the
           document, and the order grows with it")

      (testing "so the last record is not reachable from here at all,
                and `aria-rowcount` is the whole of what tells a screen
                reader that it exists"
        (is (nil? (note-node big 9999)))
        (is (= "10000" (.getAttribute (q big "[role='grid']") "aria-rowcount"))))

      (hm/unmount! big))))

;; ---------------------------------------------------------------------------
;; Traversal drives the virtualizer — which is what makes the model reachable
;; ---------------------------------------------------------------------------

(deftest traversing-to-the-window-edge-scrolls-and-extends-the-reachable-set
  (if-not (browser?)
    (skip! "the mitigation")
    (let [total     events/default-total
          m         (mount-ledger! total)
          [_ to]    (window-at 0 total)
          ^js vp    (viewport-in m)
          edge      (note-node m to)]
      (testing "premise: the viewport is at the top, and the last
                mounted row is below the fold — twenty-four rows of
                twenty-four pixels against a four-hundred-and-eighty
                pixel viewport"
        (is (zero? (.-scrollTop vp)))
        (is (some? edge))
        (is (> (* to views/row-height) views/viewport-height)))

      (.focus edge)

      (is (pos? (.-scrollTop vp))
          "THE ENGINE MOVED THE VIEWPORT. Asking for focus scrolls the
           element into view, and that is the platform's own behaviour
           rather than anything this screen or this test arranged — it
           is also the entire mechanism by which a keyboard user reaches
           past a virtualizer's window")
      (is (identical? edge (active)))

      (let [top       (.-scrollTop vp)
            [from' _] (window-at top total)]
        (tell-the-vendor-it-scrolled! m)

        (is (identical? edge (active))
            "and the node the user is on survived the window moving —
             the same node, so the caret and the selection went
             nowhere")
        (is (= (str "rec-" (+ 100000 to)) (record-of (active)))
            "still that record's field, which is the claim
             `virtualized-dom-cljs-test`'s unkeyed arm shows can be
             false while looking true")

        (testing "the window really did move, so the row below is not
                  green for want of anything happening"
          (is (pos? from'))
          (is (nil? (note-node m 0)) "row 0 has left the document"))

        (testing "AND THE REACHABLE SET MOVED WITH IT: records that were
                  absent a moment ago are now operable from the
                  keyboard, which is what makes ten thousand rows
                  reachable by traversal rather than only by mouse"
          (let [[from'' to''] (window-at (.-scrollTop vp) total)]
            (is (= (controls-of-rows from'' to'') (keyboard-order m)))
            (is (> to'' to)
                "the far edge advanced past where the first window
                 ended"))))

      (hm/unmount! m))))

(deftest the-field-a-user-was-typing-in-stays-reachable-after-any-scroll
  ;; The keyboard half of Rule 3. `virtualized-dom-cljs-test` shows the
  ;; pinned node keeps FOCUS across a scroll that unmounts its
  ;; neighbours; the question left over is what happens when the user
  ;; then tabs away — whether they can ever get back.
  (if-not (browser?)
    (skip! "the pin's keyboard consequence")
    (let [total     events/default-total
          m         (mount-ledger! total)
          ^js vp    (viewport-in m)
          field     (note-node m 5)
          top       (* 500 views/row-height)
          [from to] (window-at top total)]
      (.focus field)
      (hm/settle! m)
      (set! (.-scrollTop vp) top)
      (tell-the-vendor-it-scrolled! m)

      (is (some? (note-node m 5))
          "premise: the pin kept row 5 in the document four hundred and
           ninety-five rows after the window left it")

      (let [order (keyboard-order m)]
        (is (= (events/note-id 5) (last (butlast order)))
            "THE PIN'S PLACE IN THE ORDER. The vendor appends the pinned
             row after the window's rows, so the field the user was
             typing in is the second-to-last stop rather than the first
             — off screen, out of visual order, and still REACHABLE,
             which is the property that matters: a keyboard user who
             scrolled away can tab back to their own draft without
             hunting for it with the scrollbar")
        (is (= (* 2 (inc (inc (- to from)))) (count order))
            "the window's rows plus the pinned one, two controls each —
             derived from the geometry rather than typed, because the
             window here is twenty-seven rows wide and the one at the
             top of the model is twenty-four: the upper overscan is
             clamped away at row zero and present everywhere else, and a
             constant would be asserting about whichever offset it was
             written at"))

      (hm/unmount! m))))

;; ---------------------------------------------------------------------------
;; Activation — bought by the tag, not by a handler
;; ---------------------------------------------------------------------------

(deftest every-operable-control-is-one-the-platform-activates
  (if-not (browser?)
    (skip! "the activation sweep")
    (let [m (mount-ledger!)]
      (is (= [] (unoperable-controls m))
          "THE SWEEP. Every element on the screen whose role promises
           activation is an element the platform activates: the note is
           an `<input>` and the flag is a `<button>`, so Enter and Space
           work because HTML says they do and not because this
           application wrote a key handler. A `role=\"button\"` on a
           `<div>` would be reported here, and
           [[the-instruments-discriminate]] is the arm that shows the
           sweep can report one")

      (testing "and the roles the screen DOES carry on plain elements
                are the ones that promise nothing — a grid, its rows,
                its cells and a status region are all structure, and
                none of them is a control"
        (is (= #{"grid" "row" "gridcell" "presentation" "status"}
               (into #{} (map #(.getAttribute ^js % "role")) (q* m "[role]")))))

      (hm/unmount! m))))

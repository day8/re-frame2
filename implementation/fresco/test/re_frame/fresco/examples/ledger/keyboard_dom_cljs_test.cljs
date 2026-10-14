(ns re-frame.fresco.examples.ledger.keyboard-dom-cljs-test
  "L4 — WHAT A KEYBOARD CAN REACH ON THE TEN-THOUSAND-ROW SCREEN.

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
  designed around, and
  [[the-keyboard-order-is-the-window-in-document-order]] measures the
  bound.

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
  and nothing else; and that the screen carries no control whose role
  promises an activation its tag cannot deliver.

  **Measured too: a real Tab.** A synthetic
  `KeyboardEvent` performs no default action in any engine — it reaches
  every listener and moves focus nowhere — so a `pressed Tab` row built
  from `new KeyboardEvent` would measure its own dispatcher.
  [[real-sequential-navigation-agrees-with-the-instrument]] presses a real
  Tab along the ledger and finds the same sequence [[keyboard-order]]
  derives. The instrument is the one every other row uses: it costs no
  round trip, it can range over the whole forty-eight-entry order, and it
  is VERIFIED against the thing it stands in for rather than merely
  argued for. The presses come through the browser gate's trusted-input
  bridge (`re-frame.fresco.trusted-input-support`, whose other half is
  `scripts/run-browser-tests.cjs`).

  Enter/Space activation stays a structural claim, and deliberately:
  [[every-operable-control-is-one-the-platform-activates]] holds that the
  ledger's controls are a real `<input>` and a real `<button>`, so their
  keyboard activation is HTML's and needs no handler to exist. Pressing
  Enter on a native button would witness the platform, not this screen.

  ## Browser lane

  Focusability, `document.activeElement` and scroll-into-view have no
  implementation in the node lane. `:node-test` compiles this namespace
  too (`cljs-test$` matches `-dom-cljs-test`) and every row degrades
  there to a STATED skip rather than to a false green.

  The trusted-input row is `async` — the press happens in another
  process — and every other row is not. That is what the MAP fixture
  below is for: cljs.test refuses an async row under a POSITIONAL
  fixture by throwing a bare string that unwinds the whole
  `test:browser` run, every later namespace and the closing summary
  included."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.examples.ledger.app :as rf.fresco.examples.ledger.app]
            [re-frame.fresco.examples.ledger.events :as rf.fresco.examples.ledger.events]
            [re-frame.fresco.examples.ledger.vendor :as rf.fresco.examples.ledger.vendor]
            [re-frame.fresco.examples.ledger.views :as rf.fresco.examples.ledger.views]
            [re-frame.fresco.test.mounted :as rf.fresco.test.mounted]
            [re-frame.fresco.trusted-input-support :as rf.fresco.trusted-input-support]
            [re-frame.test-support :as rf.test-support]))

;; ---------------------------------------------------------------------------
;; The control page — the two defects this file's instruments must catch
;; ---------------------------------------------------------------------------

(rf.fresco/defview a-page-of-near-misses
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
;; sits below every view above.
;;
;; `:async? true` — the MAP shape — because this file has async rows.
;; A positional fixture makes cljs.test throw a bare string the moment a
;; test body returns an async object, and nothing catches it: the whole
;; lane unwinds, closing summary included. `make-reset-runtime-fixture`
;; returns the POSITIONAL fn by default and `:async? true` is the opt-in,
;; so the map shape is in force only because it is asked for here.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true}))

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

(defn- query-node [mount selector] (.querySelector (:container mount) selector))
(defn- query-nodes [mount selector]
  (array-seq (.querySelectorAll (:container mount) selector)))

(defn- viewport-in [m] (query-node m ".ledger-viewport"))
(defn- note-node [m i] (query-node m (str "#" (rf.fresco.examples.ledger.events/note-id i))))
(defn- active [] (.-activeElement js/document))

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
     an element below the fold makes the engine scroll it into view,
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
  makes, and the counterpart of `ht/unnamed-controls`: one
  asks whether a control can be NAMED, this asks whether it can be
  OPERATED, and a screen needs both answers to be empty."
  [m]
  (->> (query-nodes m "[role]")
       (filterv (fn [^js el]
                  (and (contains? activation-roles (.getAttribute el "role"))
                       (not (natively-operable? el)))))))

;; ---------------------------------------------------------------------------
;; Driving the screen
;; ---------------------------------------------------------------------------

(defn- mount-ledger! []
  (rf.fresco.test.mounted/mount! [rf.fresco.examples.ledger.views/ledger {}] {:initial-events (rf.fresco.examples.ledger.app/initial-events rf.fresco.examples.ledger.events/default-total)}))

(defn- window-at
  "The window `views/ledger`'s geometry puts on screen at scroll offset
  `top`, derived rather than typed — `ledger.l0-cljs-test` is where the
  arithmetic itself is held."
  [top total]
  (rf.fresco.examples.ledger.vendor/window-from top
                      {:row-height      rf.fresco.examples.ledger.views/row-height
                       :viewport-height rf.fresco.examples.ledger.views/viewport-height
                       :overscan        rf.fresco.examples.ledger.views/overscan
                       :total           total}))

(defn- controls-of-rows
  "The ids the ledger's rows contribute to a focus order, for the model
  rows `from`..`to` inclusive: a note field and a flag button each, in
  that document order.

  Derived from the application's own id function, so an order asserted
  here and a row rendered there cannot disagree about the spelling."
  [from to]
  (into []
        (mapcat (fn [i] [(rf.fresco.examples.ledger.events/note-id i) "ledger-flag"]))
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
    (let [m (rf.fresco.test.mounted/mount! [a-page-of-near-misses {}])]
      (testing "focusability is the ENGINE's answer and it goes both ways"
        (is (true? (focusable? (query-node m "#real"))))
        (is (true? (focusable? (query-node m "#half")))
            "a `tabindex` buys a place in the order for an element the
             tag table would refuse")
        (is (false? (focusable? (query-node m "#neither")))))

      (testing "so the ORDER contains the two the engine accepts, in
                document order, and not the third"
        (is (= ["real" "half"] (keyboard-order m))))

      (testing "and the activation sweep reports BOTH divs — including
                the one that is perfectly reachable, which is the defect
                a focus-order check alone calls fine"
        (is (= ["half" "neither"] (mapv label-of (unoperable-controls m))))
        (is (= ["real"] (mapv label-of (query-nodes m "button")))
            "and the real button is NOT among them, so the sweep is
             discriminating rather than reporting every element it
             ranges over"))
      (rf.fresco.test.mounted/unmount! m))))

;; ---------------------------------------------------------------------------
;; A REAL Tab
;; ---------------------------------------------------------------------------

(deftest real-sequential-navigation-agrees-with-the-instrument
  ;; THE PROXY, VERIFIED. Every other row in this file reads
  ;; [[keyboard-order]] — the engine's focusability answer over a
  ;; candidate set in document order — and asserts it IS what Tab
  ;; consumes. Elsewhere that is an argument. Here it is a measurement: press Tab
  ;; along the ledger and compare the landings with what the instrument
  ;; derived for the same page.
  ;;
  ;; A PREFIX rather than all forty-eight, on purpose. Each press is a
  ;; round trip to the runner, so the whole order would cost ten seconds
  ;; of a lane whose entire budget is a few tens; and the claim does not
  ;; get truer with length — what could differ between the two answers
  ;; (an element in one and not the other, or a different next element)
  ;; differs on the first step or not at all.
  (if-not (browser?)
    (skip! "the traversal")
    (if-not (rf.fresco.trusted-input-support/bridge?)
      (rf.fresco.trusted-input-support/unwitnessed! "the traversal")
      (async done
        (let [m      (mount-ledger!)
              finish (fn [] (rf.fresco.test.mounted/unmount! m) (done))
              steps  5]
          (try
            (let [order (keyboard-order m)
                  start (note-node m 0)]
              (is (= (first order) (rf.fresco.examples.ledger.events/note-id 0))
                  "premise: the instrument puts row 0's note field first")
              (.focus start)
              (is (identical? start (active)) "premise: the walk starts there")

              (rf.fresco.trusted-input-support/walk!
                "Tab" steps
                (fn [] (some-> (active) label-of))
                (fn [landings]
                  (try
                    (is (= (subvec order 1 (inc steps)) landings)
                        (str "THE SEQUENCE. Five real Tab presses land exactly "
                             "where the instrument said the next five stops "
                             "were — note, flag, note, flag, note, alternating "
                             "across rows. Instrument: "
                             (pr-str (subvec order 1 (inc steps)))
                             " Pressed: " (pr-str landings)))
                    (finally (finish))))))
            (catch :default e
              (is false (str "the traversal row threw: " (.-message e)))
              (finish))))))))

;; ---------------------------------------------------------------------------
;; The order, complete — and bounded by the window
;; ---------------------------------------------------------------------------

(deftest the-keyboard-order-is-the-window-in-document-order
  (if-not (browser?)
    (skip! "the order")
    (let [m         (mount-ledger!)
          [from to] (window-at 0 rf.fresco.examples.ledger.events/default-total)]
      (is (= (controls-of-rows from to) (keyboard-order m))
          "THE COMPLETE SEQUENCE rather than a membership test — a
           control that joined the order, left it, or moved within it is
           invisible to `is this focusable?` and loud here. Forty-eight
           entries: two per mounted row, note before flag, rows in model
           order")

      (testing "and nothing buys itself a place out of turn — a positive
                tabindex hoists a control above everything that has
                none, which is a reordering nothing on screen shows"
        (is (= [] (->> (query-nodes m "[tabindex]")
                       (filterv #(pos? (.-tabIndex ^js %)))
                       (mapv label-of)))))

      (rf.fresco.test.mounted/unmount! m))))

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
               (into #{} (map #(.getAttribute ^js % "role"))
                     (query-nodes m "[role]")))))

      (rf.fresco.test.mounted/unmount! m))))

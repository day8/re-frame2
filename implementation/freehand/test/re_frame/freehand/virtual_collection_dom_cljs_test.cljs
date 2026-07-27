(ns re-frame.freehand.virtual-collection-dom-cljs-test
  "FH-CTRL-021 — the fixed-size virtual collection in a real browser.

  The structural rows prove what the control DECIDES. This one proves what
  a browser does around that decision, and four of the control's facts are
  unreachable from a structural render by construction:

  - **the scroll itself.** `scrollTop` is a host property, and the whole
    design claim is that it reaches app-db through an ordinary event and
    lives nowhere else. A structural tree has no scroll host to read.
  - **focus surviving an unmount.** The active row is unmounted the moment
    it scrolls out of the window. Whether focus survives that is a fact
    about `document.activeElement`, and it is the exact fact that breaks a
    virtualized list built on roving focus — where the focused element IS
    the row that just disappeared.
  - **a keyboard move that reveals.** Pressing a key at the edge of the
    window has to move the active row, move the offset and move the host,
    as one epoch. Only a browser has the host.
  - **the offset landing on a LIVE viewport.** `:scroll-offset` is
    controlled, so state moves the host and not merely the window: a
    persisted offset restores onto a freshly mounted viewport, and a
    state-only move drags a viewport that is already showing rows. Neither
    is producible by scrolling, and a structural tree has no `scrollTop`
    to read.
  - **what a run leaves behind.** Virtualization mounts and unmounts rows
    continuously, so a leaked boundary ACCUMULATES rather than merely
    persisting. This is the witness where `:residue :none` is worth the
    most, and it is earned below rather than asserted: the cycles run, the
    root is torn down, and only then are the substrate's own books read.

  ## What this suite is deliberate about

  **The substrate is the REACTIVE, React-shaped one.** The window is
  decided by a value the application moves, so the re-render has to reach
  the host the way it does in production.

  **Nothing reaches into the control, and nothing writes the host.** Every
  offset is moved by the browser scrolling, by the application's own event
  or by replacing app-db; every reading is taken off `document`. The
  application registers NO host-writing effect, which is what makes every
  `scrollTop` this file reads a fact about the collection's own
  reconciliation rather than about the test's arrangements.

  **A scroll cycle is a full window turnover.** Consecutive stops barely
  overlap, so nearly every row in the window is replaced at every stop.
  That is what makes the residue read meaningful: a per-row leak would
  have hundreds of occurrences to accumulate before the books are read.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix, and it also matches the node suites' broader regex, where it has
  no DOM to mount and says so rather than passing quietly."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.collection :as coll]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.occurrences :as occurrences]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true
     ;; Bookkeeping, not cleanup. `reset-ledger!` forgets the roots the
     ;; PREVIOUS test made, and `clear!` forgets the occurrences it left,
     ;; so the two books `residue-clean!` reads at the END of this test
     ;; hold only this test's rows. Neither tears anything down — a
     ;; teardown here would run before each test and hide exactly what
     ;; those reads are looking for.
     :init-fn       (fn []
                      (ms/reset-ledger!)
                      (occurrences/clear!))}))

(def ctrl-021 (conf/fixture :FH-CTRL-021))

(def ^:private fid       (:frame ctrl-021))
(def ^:private list-id   (:list-id ctrl-021))
(def ^:private row-extent (:row-extent ctrl-021))
(def ^:private viewport-extent (:mounted-viewport-extent ctrl-021))
(def ^:private overscan  (:mounted-overscan ctrl-021))
(def ^:private item-count (:item-count ctrl-021))

;; ---------------------------------------------------------------------------
;; The application — ordinary re-frame, and it writes the host from an EFFECT
;; ---------------------------------------------------------------------------
;;
;; Four reads, three transitions and one effect. None of them knows
;; anything about a window: the control derives the window from values the
;; application already holds, and the application derives the offset a
;; keyboard move needs from `coll/reveal-offset`, which is an ordinary
;; function called in an ordinary handler against committed state.
;;
;; The EFFECT is the whole host story. app-db is the one home of the scroll
;; position; when the application changes it for a reason the user's finger
;; did not supply, it moves the host too — from an effect, where a host
;; write belongs, and never from inside a render. A control that wrote
;; `scrollTop` during its own render would be fighting the user's thumb.

(defn- item-key [index] (str (:key-prefix ctrl-021) index))

(defn- reg! []
  (rf/reg-sub :inbox/ids    (fn [db _] (get-in db [:inbox :ids])))
  (rf/reg-sub :inbox/scroll (fn [db _] (get-in db [:inbox :scroll])))
  (rf/reg-sub :inbox/active (fn [db _] (get-in db [:inbox :active])))
  (rf/reg-sub :inbox/label  (fn [db [_ k]] (str "row " k)))

  ;; The browser scrolled. `::v/scroll-top` carried the host's own property
  ;; into an ordinary event, and this is the only place it lands.
  (rf/reg-event :inbox/scrolled
    (fn [{:keys [db]} [_ offset]]
      {:db (assoc-in db [:inbox :scroll] offset)}))

  ;; A key was pressed. The application decides what the key means, moves
  ;; the active row and computes the offset that reveals it — ONE handler,
  ;; ONE epoch, and NO effect: the viewport follows because the offset is
  ;; controlled, so the application never touches the host. This registry is
  ;; the shape of the correction: there is no `:inbox/scroll-to` here to
  ;; register.
  (rf/reg-event :inbox/key-pressed
    (fn [{:keys [db]} [_ pressed]]
      (if (= pressed (:key-down ctrl-021))
        (let [n      (count (get-in db [:inbox :ids]))
              next-i (min (dec n) (inc (get-in db [:inbox :active])))
              offset (coll/reveal-offset {:item-count      n
                                          :row-extent      row-extent
                                          :viewport-extent viewport-extent
                                          :scroll-offset   (get-in db [:inbox :scroll])}
                                         next-i)]
          {:db (-> db
                   (assoc-in [:inbox :active] next-i)
                   (assoc-in [:inbox :scroll] offset))})
        {:db db})))

  (rf/reg-event :inbox/opened
    (fn [{:keys [db]} [_ k]] {:db (assoc-in db [:inbox :opened] k)})))

;; ---------------------------------------------------------------------------
;; Views. Module-level: a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview item-cell
  "The caller's row content — one item, read by identity, inside its own
  boundary. Every one of these that mounts is an occurrence the substrate
  records, and every one that scrolls out of the window is one it must
  forget."
  {:props [:map [:item-key :some]]}
  [{:keys [item-key]}]
  [:span {:data-part "cell"} (v/sub [:inbox/label item-key])])

(v/defview inbox [_]
  [coll/virtual-list
   {:id              list-id
    :row-keys        (v/sub [:inbox/ids])
    :row-extent      row-extent
    :viewport-extent viewport-extent
    :scroll-offset   (v/sub [:inbox/scroll])
    :active-index    (v/sub [:inbox/active])
    :overscan        overscan
    :on-scroll       [:inbox/scrolled]
    :on-key          [:inbox/key-pressed]
    :on-activate     [:inbox/opened]
    :row             (v/render-fn [k _index _total] [item-cell {:item-key k}])}])

;; ---------------------------------------------------------------------------
;; Browser seams
;; ---------------------------------------------------------------------------
;;
;; The mounted LIFECYCLE — the `act` boundary, the `[container root]` pair,
;; the teardown and the residue read — is `re-frame.freehand.mount-support`,
;; the one facade the browser tier shares. What is local is the SCROLLING,
;; because scrolling is what this suite is about.

(defn- seed! [{:keys [scroll active]}]
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid {:inbox {:ids    (mapv item-key (range item-count))
                                      :scroll (or scroll 0)
                                      :active (or active (:active-index ctrl-021))}})
  fid)

(defn- render! [root]
  (ms/act #(.render root (shell/provide-frame fid (fr/element [inbox {}])))))

(defn- app-db [] (frame/frame-app-db-value fid))
(defn- offset-in-db [] (get-in (app-db) [:inbox :scroll]))
(defn- active-in-db [] (get-in (app-db) [:inbox :active]))

(defn- viewport-el [] (.getElementById js/document list-id))

(defn- rows-in [container]
  (array-seq (.querySelectorAll container "[data-part='row']")))

(defn- window-first [] (js/parseInt (.getAttribute (viewport-el) "data-window-first") 10))
(defn- window-count [] (js/parseInt (.getAttribute (viewport-el) "data-window-count") 10))

(defn- scroll-host!
  "Scroll the way a thumb does: move the host's own `scrollTop` and let the
  browser's `scroll` event carry it into the application. Nothing here
  writes app-db, which is the point — the offset arrives through the
  control's `::v/scroll-top` site or it does not arrive at all."
  [offset]
  (let [el (viewport-el)]
    (set! (.-scrollTop el) offset)
    (.dispatchEvent el (js/Event. "scroll" #js {:bubbles false :cancelable false}))))

(defn- press!
  "A real `keydown` on the viewport, carrying the key the user pressed."
  [k]
  (.dispatchEvent (viewport-el)
                  (js/KeyboardEvent. "keydown"
                                     #js {:bubbles true :cancelable true :key k})))

(defn- settled
  "Wait for `pred`, on a bounded deadline. The scroll and key sites are
  OUTSIDE the synchronous door — the door is `onInput`/`onChange` on a
  controlled node and nothing else — so both take the ordinary batched path
  and land on a later tick. Waiting for the STATE rather than for a
  duration keeps the assertion bounded by a deadline it never normally
  reaches instead of by a sleep somebody has to tune."
  [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 4000 :interval-ms 5}))

(defn- at-window
  "Wait until the DOM is showing the window starting at `first`."
  [first]
  (settled #(and (viewport-el) (= first (window-first)))
           (str "the window moves to " first)))

(defn- finish!
  "The terminal step of every row here: tear the mount down through the
  shared lifecycle, assert that NOTHING it created survived, and end the
  async test.

  Three books beyond the facade's own. The substrate's CONNECTED-OCCURRENCE
  index is the one that matters: every row that scrolled into the window
  opened an occurrence and every row that scrolled out must have closed
  one, so a virtualized list that failed to release is a growing index
  rather than a subtle slowdown. The two document reads are the second
  failure shape — an element re-parented out of the container survives its
  root's teardown and would not appear in the first book at all — and there
  are two because the two LAYERS each contribute a node per rendered row:
  the engine's positioned `row-shell` and the listbox's `option` inside it.
  A read covering only one of them would be blind to a leak in the other.

  All three are read AFTER teardown, which is the whole point. It OWNS the
  `done` call, so a row that ends here must NOT call `done` itself."
  [container root done where]
  (ms/destroy-root! container root)
  (ms/residue-clean! (str "FH-CTRL-021 — " where)
                     [["the connected-occurrence index" #(occurrences/rows)]
                      ["every row node in the document"
                       #(.-length (.querySelectorAll js/document "[data-part='row']"))]
                      ["every row shell in the document"
                       #(.-length (.querySelectorAll js/document
                                                     "[data-part='row-shell']"))]])
  (done))

(defn- fail-and-finish!
  "The rejection path. It tears down — a rejected row must not leak its
  root either — but reads no residue: a second failure attributed to the
  leak rule would bury the one that actually happened."
  [container root done]
  (fn [e]
    (is false (str "the browser step rejected: " e))
    (ms/destroy-root! container root)
    (done)))

;; ===========================================================================
;; FH-CTRL-021 — a real scroll moves the window, and app-db is where it lands
;; ===========================================================================

(deftest fh-ctrl-021-a-real-scroll-reaches-app-db-and-nowhere-else
  (testing "Per FH-CTRL-021: the browser scrolls a real host, and the offset
            arrives through the control's `::v/scroll-top` site into an
            ordinary event. Afterwards the host's `scrollTop` and app-db
            agree — ONE fact with one home, rather than a host property
            shadowed by a copy the substrate keeps."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the scroll assertions")
      (async done
        (reg!)
        (seed! {})
        (let [[container root] (ms/create-root!)
              stops (:scroll-stops ctrl-021)]
          (-> (render! root)
              (.then
                (fn [_]
                  (ms/live!)
                  (is (= 0 (offset-in-db)) "the list starts at the top")
                  (is (= (:count (first stops)) (count (rows-in container)))
                      "showing the first window")
                  (let [{:keys [scroll-offset]} (nth stops 3)]
                    (scroll-host! scroll-offset)
                    (-> (settled #(= scroll-offset (offset-in-db))
                                 "the browser's scroll reaches app-db")
                        (.then
                          (fn [_]
                            (let [{w-first :first w-count :count} (nth stops 3)]
                              (is (= scroll-offset (offset-in-db))
                                  "the offset the user produced is in app-db")
                              (is (= scroll-offset (.-scrollTop (viewport-el)))
                                  "and it is the host's own scrollTop — one fact,
                                   one home")
                              (is (= w-first (window-first))
                                  "the window moved to the offset")
                              (is (= w-count (window-count))
                                  "carrying the window the arithmetic predicted")
                              (is (= w-count (count (rows-in container)))
                                  "and the document holds exactly that many rows")
                              (is (< w-count item-count)
                                  "non-vacuous: out of ten thousand")
                              (finish! container root done "after one scroll"))))
                        (.catch (fail-and-finish! container root done))))))
              (.catch (fail-and-finish! container root done))))))))

;; ===========================================================================
;; FH-CTRL-021 — focus is the viewport's, so a windowed row cannot take it
;; ===========================================================================

(deftest fh-ctrl-021-the-active-row-leaving-the-window-does-not-take-focus
  (testing "Per FH-CTRL-021: the viewport holds focus and the active row is
            NAMED rather than focused. This is the law virtualization
            breaks under roving focus: the focused element is unmounted the
            moment it scrolls out, focus falls to `<body>`, and the next
            keystroke goes nowhere.

            Both directions are asserted, because the honest claim has two
            halves. The named element is genuinely ABSENT while it is
            outside the window — the control does not keep a hidden copy to
            make an accessibility relationship resolve — and it is back
            when the window returns, on the same address, with focus never
            having moved.

            And while it is absent, so is the NAMING. WAI-ARIA 1.2 makes a
            non-matching `aria-activedescendant` IDREF an author error, so
            an attribute pointing at an unmounted row is a defect and not a
            courtesy. Keeping DOM focus on the stable viewport is the right
            answer; keeping a dead reference is not, and they are separable
            — which is what this row reads."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the focus assertions")
      (async done
        (reg!)
        (seed! {:active (:active-index ctrl-021)})
        (let [[container root] (ms/create-root!)
              {:keys [active-dom-id offset-hiding-active offset-showing-active]} ctrl-021
              named #(.getElementById js/document active-dom-id)]
          (-> (render! root)
              (.then
                (fn [_]
                  (ms/live!)
                  (.focus (viewport-el))
                  (is (= (viewport-el) (.-activeElement js/document))
                      "the viewport is the focus holder")
                  (is (= active-dom-id
                         (.getAttribute (viewport-el) "aria-activedescendant"))
                      "and it NAMES the active row")
                  (is (some? (named)) "which is on screen to begin with")

                  (scroll-host! offset-hiding-active)
                  (-> (at-window (:first (nth (:scroll-stops ctrl-021) 3)))
                      (.then
                        (fn [_]
                          (is (nil? (named))
                              "the active row is genuinely gone from the document —
                               no hidden copy kept to make the relationship resolve")
                          (is (= (viewport-el) (.-activeElement js/document))
                              "and focus did not go with it")
                          (is (nil? (.getAttribute (viewport-el)
                                                   "aria-activedescendant"))
                              "and the viewport stops NAMING it — a
                               non-matching IDREF is an author error, so the
                               attribute is omitted rather than left dangling
                               at an unmounted row")
                          (scroll-host! offset-showing-active)
                          (at-window 0)))
                      (.then
                        (fn [_]
                          (is (some? (named))
                              "and the row is back on the same address when the
                               window returns")
                          (is (= active-dom-id
                                 (.getAttribute (viewport-el) "aria-activedescendant"))
                              "so the viewport names it again — the IDREF
                               resolves whenever it is published")
                          (is (= (viewport-el) (.-activeElement js/document))
                              "with focus never having moved at all")
                          (is (= "true" (.getAttribute (named) "aria-selected"))
                              "still marked as the one the user is on")
                          (finish! container root done "after the active row round trip")))
                      (.catch (fail-and-finish! container root done)))))
              (.catch (fail-and-finish! container root done))))))))

;; ===========================================================================
;; FH-CTRL-021 — a keyboard move reveals a row that was not rendered
;; ===========================================================================

(deftest fh-ctrl-021-a-keyboard-move-reveals-a-row-and-moves-the-host
  (testing "Per FH-CTRL-021: the active row is at the edge of the window and
            the user presses one key. The application's handler decides what
            the key means, moves the active row, computes the revealing
            offset with `coll/reveal-offset` and moves the host — one
            epoch, one handler, no policy inside the control.

            The row it moves to was NOT in the document before the press,
            which is what makes this a reveal rather than a highlight."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the keyboard assertions")
      (async done
        (reg!)
        (let [{:keys [key-down key-from-index key-to-index key-from-offset
                      key-to-offset key-to-dom-id]} ctrl-021
              _ (seed! {:active key-from-index :scroll key-from-offset})
              [container root] (ms/create-root!)]
          (-> (render! root)
              (.then
                (fn [_]
                  (ms/live!)
                  (is (= key-from-offset (offset-in-db)) "the list starts where it starts")
                  (is (nil? (.getElementById js/document key-to-dom-id))
                      "non-vacuous: the row the key will move to is NOT rendered")
                  (is (some? (.getElementById js/document
                                              (coll/row-dom-id list-id key-from-index)))
                      "while the row it moves FROM is")

                  (press! key-down)
                  (-> (settled #(= key-to-index (active-in-db))
                               "the key moves the active row")
                      (.then
                        (fn [_]
                          (is (= key-to-index (active-in-db))
                              "the active row moved by one")
                          (is (= key-to-offset (offset-in-db))
                              "and the offset moved to exactly what reveals it")
                          (is (not= key-from-offset key-to-offset)
                              "non-vacuous: the reveal really did require a scroll")
                          (settled #(= key-to-offset (.-scrollTop (viewport-el)))
                                   "the controlled offset moves the host")))
                      (.then
                        (fn [_]
                          (is (= key-to-offset (.-scrollTop (viewport-el)))
                              "the host followed because the offset is
                               CONTROLLED — the application registered no
                               scroll effect at all")
                          (is (some? (.getElementById js/document key-to-dom-id))
                              "and the newly active row is now in the document")
                          (is (= key-to-dom-id
                                 (.getAttribute (viewport-el) "aria-activedescendant"))
                              "named by the viewport that still holds focus")
                          (finish! container root done "after the keyboard move")))
                      (.catch (fail-and-finish! container root done)))))
              (.catch (fail-and-finish! container root done))))))))

;; ===========================================================================
;; FH-CTRL-021 — the offset is CONTROLLED, so the viewport follows the state
;; ===========================================================================
;;
;; These two are the direction a render cannot go on its own, and they are
;; the reason `:scroll-offset` is controlled rather than a cache trailing the
;; DOM. Neither can be produced by scrolling: the first has no gesture before
;; the mount and the second has none after it. Nothing here writes the host —
;; the application registers no effect that could — so a viewport that moves
;; moved because the collection reconciled it.

(deftest fh-ctrl-021-a-non-zero-offset-is-restored-onto-a-fresh-viewport
  (testing "Per FH-CTRL-021: the offset is in app-db BEFORE anything mounts,
            which is what a reload into a persisted position, a deep link
            and a restored snapshot all look like. Render alone picks the
            remote window from it and paints those rows thousands of pixels
            down the canvas — and leaves the fresh viewport at scrollTop 0,
            showing blank. That internally inconsistent state is the bug the
            reconciliation closes, and this row reads the viewport to prove
            it is closed.

            Stated POSITIVELY, because there is no un-reconciled mode left
            to contrast with: one prop, one meaning."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the restoration assertions")
      (async done
        (reg!)
        (let [{:keys [restore-offset restore-first]} ctrl-021
              _ (seed! {:scroll restore-offset})
              [container root] (ms/create-root!)]
          (-> (render! root)
              (.then
                (fn [_]
                  (ms/live!)
                  (is (= restore-offset (offset-in-db))
                      "the offset was in app-db before the mount")
                  (settled #(= restore-offset (.-scrollTop (viewport-el)))
                           "the fresh viewport is moved to the persisted offset")))
              (.then
                (fn [_]
                  (is (= restore-offset (.-scrollTop (viewport-el)))
                      "the viewport holds the persisted offset — not 0, which
                       is where a render-only collection would have left it")
                  (is (= restore-first (window-first))
                      "and the window the canvas painted is the one that
                       offset selects, so the two agree rather than the box
                       showing blank above the rows")
                  (is (pos? restore-offset)
                      "non-vacuous: the restored offset is not the default")
                  (finish! container root done "after a restore onto a fresh mount")))
              (.catch (fail-and-finish! container root done))))))))

(deftest fh-ctrl-021-a-state-only-offset-move-drags-a-mounted-viewport
  (testing "Per FH-CTRL-021: the TIME-TRAVEL half, and the one that
            distinguishes a controlled offset from a merely restorable one.
            The collection is already mounted and already showing rows when
            the offset moves in STATE alone — no gesture, no scroll event,
            no effect, exactly the shape `restore-epoch` leaves behind. The
            viewport follows it.

            This is what the shipped control could not do before the
            reconciliation: it could time-travel the window VALUE, and the
            mounted viewport would sit where the user's thumb last left it."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the time-travel assertions")
      (async done
        (reg!)
        (seed! {})
        (let [{:keys [time-travel-offset time-travel-first]} ctrl-021
              [container root] (ms/create-root!)]
          (-> (render! root)
              (.then
                (fn [_]
                  (ms/live!)
                  (is (= 0 (.-scrollTop (viewport-el)))
                      "the mounted viewport starts at the top")
                  (is (= 0 (window-first)) "showing the first window")
                  ;; Move the STATE and nothing else. `replace-app-db!` is the
                  ;; snapshot seam, so this is a restore rather than a scroll.
                  (frame/replace-app-db!
                    fid (assoc-in (app-db) [:inbox :scroll] time-travel-offset))
                  (settled #(= time-travel-offset (.-scrollTop (viewport-el)))
                           "a state-only offset move drags the viewport after it")))
              (.then
                (fn [_]
                  (is (= time-travel-offset (.-scrollTop (viewport-el)))
                      "the MOUNTED viewport followed the state — the direction
                       a render cannot produce")
                  (is (= time-travel-first (window-first))
                      "and the window is the one that offset selects, so the
                       host and the render agree on one fact")
                  (is (= time-travel-offset (offset-in-db))
                      "with app-db still holding it — the reconciliation
                       writes the host, never the frame")
                  (finish! container root done "after a state-only offset move")))
              (.catch (fail-and-finish! container root done))))))))

;; ===========================================================================
;; FH-CTRL-021 — repeated scroll cycles leave NOTHING behind
;; ===========================================================================

(defn- run-cycle!
  "One full pass over the fixture's scroll stops, asserting the window at
  each. Answers a promise of the number of rows rendered across the pass —
  a deterministic counter, so the residue claim below has a magnitude
  attached rather than being a bare zero."
  [container]
  (reduce
    (fn [p {:keys [scroll-offset] w-first :first w-count :count}]
      (.then p (fn [rendered]
                 (scroll-host! scroll-offset)
                 (-> (at-window w-first)
                     (.then (fn [_]
                              (is (= w-count (window-count))
                                  (str "the window at " scroll-offset
                                       " is the one the arithmetic predicted"))
                              (is (= w-count (count (rows-in container)))
                                  (str "and the document holds exactly that many rows at "
                                       scroll-offset))
                              (+ rendered w-count)))))))
    (js/Promise.resolve 0)
    (:scroll-stops ctrl-021)))

(deftest fh-ctrl-021-repeated-scroll-cycles-leave-no-residue
  (testing "Per FH-CTRL-021: this is the witness where `:residue :none` is
            worth the most, and it is EARNED here rather than asserted.

            Virtualization is continuous mounting and unmounting, so a
            leaked boundary accumulates: three passes over eight
            barely-overlapping stops open and close very nearly a hundred
            row occurrences each. Then the root is torn down through the
            shared lifecycle and only THEN are the books read — the
            substrate's own connected-occurrence index, and every row node
            in the document. Both must be EMPTY, as exact zeroes rather
            than thresholds.

            The index is asserted NON-EMPTY while the mount is live, which
            is what stops the read after teardown from being satisfied by a
            book nothing ever wrote to."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the residue assertions")
      (async done
        (reg!)
        (seed! {})
        (let [[container root] (ms/create-root!)
              cycles  (:scroll-cycles ctrl-021)
              per     (:rows-rendered-per-cycle ctrl-021)]
          (-> (render! root)
              (.then
                (fn [_]
                  (ms/live!)
                  (is (pos? (count (occurrences/rows)))
                      "non-vacuity: the substrate really does record the
                       occurrences this mount opened, so an empty read after
                       teardown is a fact about release rather than about a
                       book nothing writes to")
                  (reduce (fn [p _]
                            (.then p (fn [total]
                                       (.then (run-cycle! container)
                                              (fn [n] (+ total n))))))
                          (js/Promise.resolve 0)
                          (range cycles))))
              (.then
                (fn [rendered]
                  (is (= (* cycles per) rendered)
                      "every cycle rendered exactly the number of rows the
                       fixture states, so the magnitude behind the residue
                       claim is a counted one")
                  (is (pos? (count (occurrences/rows)))
                      "and the index is still non-empty with the mount live —
                       the rows the LAST window opened")
                  (finish! container root done
                           (str "after " cycles " scroll cycles and "
                                rendered " row renders"))))
              (.catch (fail-and-finish! container root done))))))))

(ns re-frame.freehand.pilot-virtual-table-dom-cljs-test
  "THE VIRTUAL-TABLE PILOT, mounted — the half a structural render cannot
  make.

  `pilot-virtual-table-cljs-test` proves the window is the right window
  and that both modes agree about it. What it cannot prove is the loop
  that closes through the browser:

  - a REAL scrollbar on a canvas three hundred and twenty thousand pixels
    tall, scrolled to a real offset, delivering a real `scroll` event
    whose offset the `::v/scroll-top` projection carries into an ordinary
    re-frame event — and the next paint carrying exactly the twenty-nine
    rows that offset selects;
  - the rows that STAYED in the window being the same DOM elements
    afterwards, which is the only honest reading of \"scrolling remounts
    nothing\" — a key comparison proves what the tree intended, and
    element identity proves what React did;
  - the same for a REORDER: a record moving to the front takes its
    element with it and leaves the others alone;
  - and PROMOTION PARITY read off the document rather than off a tree:
    the interpreted table and its compiled twin, mounted side by side on
    one page, scrolled to the same offset by two real scrollbars, and
    producing the same markup character for character. That row needs
    both halves of a compiled render slot reaching a browser — the
    compiled tier's browser lowering and the React emitter's `:slot`
    arm — and it is here because both have landed.

  This file rides the browser lane through its `-dom-cljs-test` suffix.
  It also matches the node suites' broader regex, where it has no DOM to
  mount and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.pilot-virtual-table :as ui]
            [re-frame.freehand.pilot-virtual-table-compiled :as compiled]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(def ^:private fid :dom/pilot-virtual-table)
(def ^:private total 10000)

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

;; ---------------------------------------------------------------------------
;; Browser seams — the same ones every mounted Freehand suite uses
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real browser mount is required — " why)))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- live! []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn- tick [] (js/Promise. (fn [resolve] (js/setTimeout #(resolve nil) 0))))

(defn- settle!
  "Close the cells' pending window and let the browser paint what the
  notification scheduled."
  []
  (cell/flush!)
  (tick))

(defn- await-settle!
  "Deterministic scroll-settle. A REAL scrollbar drag dispatches an
  ASYNC re-frame event; a single `settle!` tick was comfortable on an
  idle CI runner but marginal on this machine's six concurrent worker
  agents (shadow-cljs + Chromium) and on a loaded CI runner — so the old
  assertion was timing the MACHINE, not the window (rf2-eq2vx: scroll-top
  nil, 25 rows not 29, window unmoved).

  Instead of assuming the settle happened after one macrotask, flush the
  cells' pending window on every probe and POLL — up to a generous bound —
  until `pred` reports the scroll's dispatch has landed in app-db AND the
  next paint has committed the expected window to the document. The
  caller then asserts the EXACT window off a state it KNOWS has settled,
  rather than racing it. Returns a `js/Promise` that composes with the
  `.then` chain; on a genuinely stuck state it rejects after the bound
  and the caller's `.catch` reds with the timeout label."
  [pred label]
  (test-support/poll-until
    (fn [] (cell/flush!) (pred))
    {:timeout-ms 8000 :interval-ms 10 :label label}))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- unmount! [container mounted]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (when (some? mounted)
    (.unmount (.-react-root ^root/Root mounted)))
  (.remove container)
  nil)

;; ---------------------------------------------------------------------------
;; Reading the mounted table back off `document`
;; ---------------------------------------------------------------------------

(defn- row-nodes [container]
  (vec (js/Array.from (.querySelectorAll container "[data-part='row']"))))

(defn- row-key-of [node] (.getAttribute node "data-row-key"))

(defn- row-keys [container] (mapv row-key-of (row-nodes container)))

(defn- rows-by-key
  "key -> the live DOM element, so identity can be compared across a
  re-render rather than merely re-counted."
  [container]
  (into {} (map (juxt row-key-of identity)) (row-nodes container)))

(defn- viewport [container]
  (.querySelector container "[data-part='viewport']"))

(defn- dom-shape
  "A mounted subtree as comparable data: every element, its attribute
  names and values, its text, and its children in document order.

  Deliberately NOT `outerHTML`. The two emitters write the same
  attributes to an element in a different ORDER — the interpreted walk
  puts a sugar `.class` first, the compiled emitter writes it last —
  which nothing in a browser can observe but which makes string equality
  a false negative. Comparing the attribute SET is the parity question
  that actually means something, and it is stricter than
  `outerHTML` everywhere else: a missing attribute, a changed value, an
  extra node or a reordered child all still fail."
  [node]
  (if (= 3 (.-nodeType node))
    (.-nodeValue node)
    {:tag      (.-tagName node)
     :attrs    (into (sorted-map)
                     (map (fn [a] [(.-name a) (.-value a)]))
                     (js/Array.from (.-attributes node)))
     :children (mapv dom-shape (js/Array.from (.-childNodes node)))}))

;; ---------------------------------------------------------------------------
;; The application under test — rows live in app-db, so a dispatch can
;; reorder them and the mounted table has to react like anything else.
;; ---------------------------------------------------------------------------

(v/defview live-ledger
  "The pilot's caller, reading its dataset from a subscription so the
  mounted table can be driven by ordinary events."
  [_]
  [ui/data-table {:table-key  ui/ledger-key
                  :rows       (v/sub [:dom/rows])
                  :row-key    :id
                  :row-h      ui/row-h
                  :viewport-h ui/viewport-h
                  :label      "Q3 ledger"
                  :row        (v/render-fn [r i]
                                [ui/ledger-row-cells {:record r :index i}])}])

(v/defview restoring-ledger
  "The pilot's caller with scroll RESTORATION on — `:restore-scroll? true`,
  the interpreted-only variant that writes its persisted offset back onto the
  viewport before paint. Otherwise identical to [[live-ledger]], so the two
  differ by exactly the option under test."
  [_]
  [ui/data-table {:table-key       ui/ledger-key
                  :rows            (v/sub [:dom/rows])
                  :row-key         :id
                  :row-h           ui/row-h
                  :viewport-h      ui/viewport-h
                  :label           "Q3 ledger"
                  :restore-scroll? true
                  :row             (v/render-fn [r i]
                                     [ui/ledger-row-cells {:record r :index i}])}])

(defn- seed! [n]
  (live-frame/make-frame {:id fid})
  (ui/register!)
  (ui/register-app!)
  (rf/reg-sub :dom/rows (fn [db _] (:rows db)))
  (rf/reg-event :dom/move-to-front
    (fn [{:keys [db]} [_ i]]
      (let [rs (:rows db)]
        {:db (assoc db :rows (into [(nth rs i)]
                                   (concat (subvec rs 0 i) (subvec rs (inc i)))))})))
  (frame/replace-app-db! fid {:rows (ui/ledger-rows n)})
  fid)

(defn- scroll-to!
  "Scroll the viewport the way a user does — move the real scroll
  position, then let the browser's own `scroll` event carry it."
  [node top]
  (set! (.-scrollTop node) top)
  (.dispatchEvent node (js/Event. "scroll" #js {:bubbles false :cancelable false}))
  nil)

(defn- ledger-scroll-top
  "The offset the `::v/scroll-top` projection has committed to app-db for
  the ledger table — the signal a real scroll's async dispatch has
  actually landed. `nil` until it does (which is exactly the flake
  signature the deterministic settle waits out)."
  []
  (get-in (frame/frame-app-db-value fid) [ui/tables-root ui/ledger-key :scroll-top]))

(defn- viewport-scroll-top
  "The live DOM `scrollTop` of the mounted viewport — the number the
  restoration is about. It is what a fresh mount leaves at 0 and a restore
  moves, so it is the discriminating reading: render alone cannot change it."
  [container]
  (.-scrollTop (viewport container)))

(defn- seed-offset!
  "Persist an offset into app-db BEFORE any table is mounted — the reserved
  `::v/scroll-top` event, dispatched synchronously with NO viewport in the
  document to move. This is the restore that a remount or a time-travel
  produces: app-db holds a non-zero offset that no live DOM node put there."
  [top]
  (rf/dispatch-sync [:acme.ui.table/scrolled ui/ledger-key top] {:frame fid})
  nil)

;; ===========================================================================
;; The mount
;; ===========================================================================

(deftest a-ten-thousand-row-table-puts-exactly-twenty-five-rows-in-the-dom
  (testing "Ten thousand records, one mount, and the document holds
            EXACTLY twenty-five row elements — with a canvas three hundred
            and twenty thousand pixels tall, so the scrollbar tells the
            truth about a dataset the DOM never received."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (let [container (host-node!)]
          (seed! total)
          (-> (act #(v/mount [live-ledger {}] container {:frame fid}))
              (.then
                (fn [mounted]
                  (let [rows (row-nodes container)]
                    (is (= total (count (:rows (frame/frame-app-db-value fid))))
                        "non-vacuous: ten thousand records are in app-db")
                    (is (= 25 (count rows))
                        "exactly 25 row elements reached the document")
                    (is (= (mapv #(str "r" %) (range 0 25)) (row-keys container))
                        "and they are the first 25 records, in order")
                    (is (= "10000" (.getAttribute (viewport container) "aria-rowcount"))
                        "the grid still reports all 10000 rows")
                    (is (= "320000px"
                           (.-height (.-style (.querySelector container
                                                              "[data-part='canvas']"))))
                        "and the canvas is the full scroll height")
                    (is (= "0AC-00" (.-textContent (first rows)))
                        "the caller's row content is in the row"))
                  (unmount! container mounted)
                  (done)))
              (.catch (fn [e] (is false (str "mount threw " e)) (done)))))))))

(deftest a-real-scroll-moves-the-window-and-leaves-the-surviving-rows-alone
  (testing "The whole loop, through the browser: a real scroll offset
            fires a real `scroll` event, the `::v/scroll-top` projection
            reads the offset off it, an ordinary re-frame event writes it
            to app-db, the subscription moves and the next paint carries
            EXACTLY the twenty-nine rows that offset selects.

            Then the part a key comparison cannot prove: the twenty-eight
            rows that were in both windows are the SAME DOM elements
            afterwards. React reused them, so scrolling a virtual table
            costs the two rows at the edges and nothing else."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (let [container (host-node!)
              state     (atom {})]
          (seed! total)
          (-> (act #(v/mount [live-ledger {}] container {:frame fid}))
              (.then (fn [mounted]
                       (swap! state assoc :mounted mounted)
                       (live!)
                       (scroll-to! (viewport container) 3200)
                       ;; Wait for the settle rather than assuming it: the
                       ;; async dispatch must reach app-db (offset 3200) and
                       ;; the paint must commit the 29-row window before the
                       ;; assertions read it.
                       (await-settle!
                         #(and (= 3200 (ledger-scroll-top))
                               (= 29 (count (row-nodes container))))
                         "real scroll to 3200 settles into a 29-row window")))
              (.then (fn [_]
                       (is (= 3200 (.-scrollTop (viewport container)))
                           "the viewport really did scroll to 3200")
                       (is (= 3200 (get-in (frame/frame-app-db-value fid)
                                           [ui/tables-root ui/ledger-key :scroll-top]))
                           "the scroll offset reached app-db through the component's
                            own event site")
                       (is (= 29 (count (row-nodes container)))
                           "exactly 29 rows are in the document now")
                       (is (= (mapv #(str "r" %) (range 96 125)) (row-keys container))
                           "r96 … r124, in order")
                       ;; Now the identity half: one more row of scroll, and the
                       ;; twenty-eight rows in both windows must be the same
                       ;; twenty-eight elements.
                       (swap! state assoc :before (rows-by-key container))
                       (scroll-to! (viewport container) 3232)
                       ;; Same discipline for the one-row advance: wait until
                       ;; the offset reaches 3232 and the window's first row
                       ;; is r97, then assert the exact advanced window.
                       (await-settle!
                         #(and (= 3232 (ledger-scroll-top))
                               (= "r97" (first (row-keys container))))
                         "one more row of scroll advances the window to r97")))
              (.then (fn [_]
                       (let [before (:before @state)
                             after  (rows-by-key container)
                             stayed (filterv #(contains? before %) (keys after))]
                         (is (= 29 (count after))
                             "still exactly 29 rows one row later")
                         (is (= (mapv #(str "r" %) (range 97 126)) (row-keys container))
                             "the window advanced by exactly one row")
                         (is (= 28 (count stayed))
                             "twenty-eight rows were in both windows")
                         (is (every? #(identical? (get before %) (get after %)) stayed)
                             "and every one of them is the SAME element — React reused
                              it rather than remounting it"))
                       (unmount! container (:mounted @state))
                       (done)))
              (.catch (fn [e]
                        (is false (str "the scroll pass threw " e))
                        (done)))))))))

(deftest a-reorder-carries-a-row-element-with-its-record
  (testing "Keyed identity, where it actually matters: a record moves to
            the front of the dataset and its ELEMENT moves with it, while
            every other row in the window keeps the element it had. That
            is the difference between a keyed reconciliation and a
            wholesale rebuild, and it is not visible in a structural
            tree."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (let [container (host-node!)
              state     (atom {})]
          (seed! 40)
          (-> (act #(v/mount [live-ledger {}] container {:frame fid}))
              (.then (fn [mounted]
                       (swap! state assoc :mounted mounted :before (rows-by-key container))
                       (live!)
                       (rf/dispatch-sync [:dom/move-to-front 7] {:frame fid})
                       (settle!)))
              (.then (fn [_]
                       (let [before (:before @state)
                             after  (rows-by-key container)
                             shared (filterv #(contains? before %) (keys after))]
                         (is (= 25 (count before)) "25 rows before the reorder")
                         (is (= 25 (count after)) "25 rows after it")
                         (is (= "r7" (first (row-keys container)))
                             "the moved record is at the front")
                         (is (identical? (get before "r7") (get after "r7"))
                             "and it is the SAME element it was — the row travelled
                              rather than being rebuilt")
                         (is (= 25 (count shared))
                             "every row in the window was there before")
                         (is (every? #(identical? (get before %) (get after %)) shared)
                             "and not one of them was remounted"))
                       (unmount! container (:mounted @state))
                       (done)))
              (.catch (fn [e]
                        (is false (str "the reorder pass threw " e))
                        (done)))))))))

(deftest promotion-changes-nothing-about-the-mounted-table
  (testing "Promotion parity, read off the document. The interpreted
            table and its compiled twin mount side by side on one page,
            each gets a real scrollbar dragged to the same offset, and at
            both offsets they hold the same rows, in the same order, with
            the same elements, attributes and text.

            This is the stronger form of the structural parity row: it
            proves not only that the two emitters denote the same tree
            but that React builds the same document from them, that the
            compiled `v/slot` really lowers a caller's row content
            through the React emitter, and that the compiled
            `::v/scroll-top` scroll site delivers a live browser event
            into app-db exactly as the interpreted one does."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (let [a     (host-node!)
              b     (host-node!)
              state (atom {})
              rows  (ui/ledger-rows 200)]
          (seed! 200)
          (-> (act #(v/mount [ui/ledger {:rows rows}] a {:frame fid}))
              (.then (fn [m-a]
                       (swap! state assoc :a m-a)
                       (act #(v/mount [compiled/ledger {:rows rows}] b {:frame fid}))))
              (.then (fn [m-b]
                       (swap! state assoc :b m-b)
                       (is (= 25 (count (row-nodes a)))
                           "non-vacuous: the interpreted arm mounted 25 rows")
                       (is (= 25 (count (row-nodes b)))
                           "and so did the compiled arm")
                       (is (= (mapv #(str "r" %) (range 0 25)) (row-keys a))
                           "the first 25 records, in order")
                       (is (= (row-keys a) (row-keys b))
                           "the same rows, in the same order")
                       (is (= (dom-shape (viewport a)) (dom-shape (viewport b)))
                           "and the same document: every element, every
                            attribute, every character of text, in order")
                       (live!)
                       (scroll-to! (viewport a) 3200)
                       (scroll-to! (viewport b) 3200)
                       ;; Both arms drag a real scrollbar to the same offset;
                       ;; wait for that offset to land in app-db and for both
                       ;; documents to hold their 29-row window before the
                       ;; parity assertion reads them (same flake class as
                       ;; rf2-eq2vx — deterministic settle, not a fixed tick).
                       (await-settle!
                         #(and (= 3200 (ledger-scroll-top))
                               (= 29 (count (row-nodes a)))
                               (= 29 (count (row-nodes b))))
                         "both arms scroll to 3200 and settle into 29-row windows")))
              (.then (fn [_]
                       (is (= 3200 (get-in (frame/frame-app-db-value fid)
                                           [ui/tables-root ui/ledger-key :scroll-top]))
                           "a real scroll on either arm reached app-db")
                       (is (= 29 (count (row-nodes a)))
                           "the interpreted arm holds exactly 29 rows")
                       (is (= 29 (count (row-nodes b)))
                           "and so does the compiled one")
                       (is (= (mapv #(str "r" %) (range 96 125)) (row-keys b))
                           "r96 … r124 — the compiled window is the same window")
                       (is (= (dom-shape (viewport a)) (dom-shape (viewport b)))
                           "and scrolled, the two documents are still identical")
                       (unmount! a (:a @state))
                       (unmount! b (:b @state))
                       (done)))
              (.catch (fn [e]
                        (is false (str "the parity pass threw " e))
                        (done)))))))))

(deftest the-editing-grid-mounts-exactly-one-hundred-controlled-cells
  (testing "The hundred-cell workload on a real page: five hundred rows
            in the sheet, ten windowed rows of ten inputs in the
            document, and a keystroke in one cell reaching app-db under
            that cell's own address and no other's."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (let [container (host-node!)]
          (live-frame/make-frame {:id fid})
          (ui/register!)
          (ui/register-app!)
          (frame/replace-app-db! fid {})
          (-> (act #(v/mount [ui/editing-grid {:rows (ui/grid-rows 500)}]
                             container {:frame fid}))
              (.then
                (fn [mounted]
                  (let [inputs (vec (js/Array.from
                                      (.querySelectorAll container "input")))]
                    (is (= 10 (count (row-nodes container)))
                        "exactly ten windowed rows")
                    (is (= 100 (count inputs))
                        "exactly one hundred controlled cells reached the document")
                    (is (= "g7/c3" (.getAttribute (nth inputs 73) "data-cell"))
                        "and each one addresses itself"))
                  (unmount! container mounted)
                  (done)))
              (.catch (fn [e] (is false (str "mount threw " e)) (done)))))))))

;; ===========================================================================
;; RESTORATION — the reverse direction, and its promotion cost (rf2-g5xxv)
;; ===========================================================================
;;
;; The pilot's dataflow is one-way: a scroll writes app-db, render reads it to
;; pick the window. That is a DOM-DERIVED CACHE, and a cache cannot be
;; restored. `:restore-scroll?` attaches the `:layout` restore behavior, which
;; writes the persisted offset back onto the viewport before paint — the ONE
;; sanctioned imperative seam, and the reason a restoring table is
;; interpreted-only (the compiled grammar has no behavior form). These three
;; suites prove the write happens, prove it is what moves the viewport, and —
;; through a cache-only CONTROL seeded identically — prove they diverge exactly
;; where the write is. The discipline is the deterministic settle the
;; user-scroll suites use, never a single-tick race.

(deftest a-seeded-offset-is-restored-into-a-fresh-viewport-before-paint
  (testing "The restore the headline turns on: an offset persisted BEFORE any
            table exists — the shape a remount or a time-travel leaves in
            app-db — is written back onto the fresh viewport, so the DOM
            scrollTop, the rendered row window and the app-db value all agree
            at the first commit. Nothing scrolls the DOM; the only thing that
            can move the viewport off zero is the restoration, which is what
            makes this discriminating — strip the :layout write and the window
            still reads r96…r124 while the viewport sits at 0."
    (if-not (browser?)
      (skip! "the browser job runs the restore assertions")
      (async done
        (let [container (host-node!)
              state     (atom {})]
          (seed! total)
          (seed-offset! 3200)
          (-> (act #(v/mount [restoring-ledger {}] container {:frame fid}))
              (.then (fn [mounted]
                       (swap! state assoc :mounted mounted)
                       (live!)
                       ;; The restore rides the mount's own layout commit; poll
                       ;; the same way the user-scroll suites do, so the read is
                       ;; off a state known to have settled rather than a race.
                       (await-settle!
                         #(and (= 3200 (viewport-scroll-top container))
                               (= 3200 (ledger-scroll-top))
                               (= 29 (count (row-nodes container))))
                         "a seeded offset restores into the fresh viewport")))
              (.then (fn [_]
                       (is (= 3200 (viewport-scroll-top container))
                           "the fresh viewport was moved to the persisted offset —
                            the restoration wrote the DOM, nothing scrolled it")
                       (is (= 3200 (ledger-scroll-top))
                           "and app-db still holds that offset")
                       (is (= 29 (count (row-nodes container)))
                           "the window is the 29 rows the offset selects")
                       (is (= (mapv #(str "r" %) (range 96 125)) (row-keys container))
                           "r96 … r124 — the rows now under the restored viewport")
                       (unmount! container (:mounted @state))
                       (done)))
              (.catch (fn [e]
                        (is false (str "the restore pass threw " e))
                        (done)))))))))

(deftest time-travel-on-a-mounted-table-moves-the-viewport-not-only-the-window
  (testing "The reverse direction the one-way dataflow never exercised: a
            MOUNTED restoring table whose app-db offset is moved by an EVENT —
            a time-travel restore, a programmatic set — and not by a scrollbar.
            Render moves the window and the behavior's :update moves the
            viewport to match, so the two never disagree. The existing mounted
            suites move the DOM first; this one never touches it, so it fails
            the moment the reverse write is removed."
    (if-not (browser?)
      (skip! "the browser job runs the time-travel assertions")
      (async done
        (let [container (host-node!)
              state     (atom {})]
          (seed! total)
          (-> (act #(v/mount [restoring-ledger {}] container {:frame fid}))
              (.then (fn [mounted]
                       (swap! state assoc :mounted mounted)
                       (is (= 0 (viewport-scroll-top container))
                           "the fresh viewport starts at the top")
                       (is (= (mapv #(str "r" %) (range 0 25)) (row-keys container))
                           "showing the first window")
                       (live!)
                       ;; Move the STATE, not the DOM — the offset arrives the
                       ;; way a restore-epoch leaves it, with the viewport at 0.
                       (seed-offset! 3200)
                       (await-settle!
                         #(and (= 3200 (viewport-scroll-top container))
                               (= "r96" (first (row-keys container))))
                         "a state-only offset move drags the viewport after it")))
              (.then (fn [_]
                       (is (= 3200 (viewport-scroll-top container))
                           "the viewport followed the state — the reverse
                            direction render alone cannot produce")
                       (is (= 3200 (ledger-scroll-top))
                           "app-db and the DOM agree")
                       (is (= (mapv #(str "r" %) (range 96 125)) (row-keys container))
                           "and the window is the one that offset selects")
                       (unmount! container (:mounted @state))
                       (done)))
              (.catch (fn [e]
                        (is false (str "the time-travel pass threw " e))
                        (done)))))))))

(deftest without-restoration-a-seeded-offset-leaves-the-viewport-at-zero
  (testing "The adversarial CONTROL that makes the restore load-bearing: the
            SAME offset seeded before the SAME mount, but through
            [[live-ledger]] — the cache-only table, `:restore-scroll?` absent.
            Render still picks the remote window from the persisted offset, but
            nothing writes it back, so the fresh viewport stays at scrollTop 0
            while it paints r96…r124 at 3072px — the internally inconsistent
            state the bug describes. This is the restore test with the :layout
            write removed, and it must DIVERGE: a restoring table reads 3200
            here, a cache-only one reads 0."
    (if-not (browser?)
      (skip! "the browser job runs the control assertions")
      (async done
        (let [container (host-node!)
              state     (atom {})]
          (seed! total)
          (seed-offset! 3200)
          (-> (act #(v/mount [live-ledger {}] container {:frame fid}))
              (.then (fn [mounted]
                       (swap! state assoc :mounted mounted)
                       (live!)
                       ;; Allow the same settle window the restore test grants,
                       ;; so a slow restore could not pass here as its absence:
                       ;; the window commits from the offset, then the viewport
                       ;; is read on a table that never moved it.
                       (await-settle!
                         #(and (= 3200 (ledger-scroll-top))
                               (= 29 (count (row-nodes container))))
                         "the cache table renders the remote window from the offset")))
              (.then (fn [_]
                       (is (= 3200 (ledger-scroll-top))
                           "app-db holds the seeded offset")
                       (is (= (mapv #(str "r" %) (range 96 125)) (row-keys container))
                           "and render picked the remote window from it")
                       (is (= 0 (viewport-scroll-top container))
                           "but the cache-only viewport was never moved — it sits
                            at 0 under a window painted at 3072px, the exact
                            inconsistency :restore-scroll? closes")
                       (unmount! container (:mounted @state))
                       (done)))
              (.catch (fn [e]
                        (is false (str "the control pass threw " e))
                        (done)))))))))

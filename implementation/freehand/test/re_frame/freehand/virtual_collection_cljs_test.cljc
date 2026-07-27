(ns re-frame.freehand.virtual-collection-cljs-test
  "FH-CTRL-021 — the fixed-size virtual collection, headlessly, on both
  hosts.

  Everything a virtual list gets wrong is a fact about VALUES, and this
  file is where those facts are settled without a browser: how many rows
  the window holds, which keys they carry, how many item reads exist, and
  what the tree says about a collection it does not contain. The browser
  suite ([[re-frame.freehand.virtual-collection-dom-cljs-test]]) owns the
  three facts a structural tree cannot reach — a real scroll event, focus
  surviving an unmount, and what a torn-down mount leaves behind.

  ## The shape of the claim

  A virtual list is a cost argument, so the assertions here are exact
  integers rather than adjectives. Ten thousand items and a forty-row
  viewport produce a window of forty-four; the same fixture with a
  different offset produces forty-nine, which is the arithmetic ceiling;
  and the number of item reads is the window's, not the collection's. A
  bound that no offset reaches would be true and worthless, so the fixture
  includes the offset that reaches it.

  ## Why the caller is written the way it is

  The row content is a DECLARED CHILD mounted from the slot body, not a
  read inside the slot. That is the compiled grammar's own rule — a slot
  body is a pure render fragment and `:rf.ui.compile/impure-slot-body` is
  the refusal — and it is also the entire narrow-read design: the read
  belongs to the row's own boundary, so an edit to one item invalidates
  one boundary rather than the list. The suite asserts both halves of that
  as a pair, because the narrow claim on its own is satisfied by a
  collection that never changes."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.collection :as coll]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ctrl-021 (conf/fixture :FH-CTRL-021))

(def ^:private fid :rf/default)

;; ---------------------------------------------------------------------------
;; The application — ordinary re-frame, and it knows nothing about a window
;; ---------------------------------------------------------------------------
;;
;; Four reads and one transition for a ten-thousand-row list. The identity
;; vector and the item content are SEPARATE reads, and that separation is
;; the whole design: an edit moves the second and leaves the first
;; untouched, so the control is not invalidated by a row it does not
;; render.

(defn item-key
  "The caller's stable identity for the item at absolute `index`. Public
  because the parity suite drives the same collection."
  [index]
  (str (:key-prefix ctrl-021) index))

(defn- default-label [k] (str "row " k))

(defn- seed-db []
  {:inbox {:ids    (mapv item-key (range (:item-count ctrl-021)))
           :labels {}
           :scroll 0
           :active (:active-index ctrl-021)}})

(defn register!
  "The whole dataflow. Public so the parity suite starts from the same
  registrations these laws do — an interpreted control and its compiled
  twin reach exactly these."
  []
  (rf/reg-sub :inbox/ids    (fn [db _] (get-in db [:inbox :ids])))
  (rf/reg-sub :inbox/scroll (fn [db _] (get-in db [:inbox :scroll])))
  (rf/reg-sub :inbox/active (fn [db _] (get-in db [:inbox :active])))

  ;; The NARROW read: one item, by identity.
  (rf/reg-sub :inbox/label
    (fn [db [_ k]] (get-in db [:inbox :labels k] (default-label k))))

  ;; The WIDE read, registered but never rendered. It exists so the
  ;; narrowness claim below has its counter-case: something DID change on
  ;; the edit, and this is what a control reading the container would have
  ;; been handed.
  (rf/reg-sub :inbox/labels (fn [db _] (get-in db [:inbox :labels])))

  (rf/reg-event :inbox/relabelled
    (fn [{:keys [db]} [_ k text]]
      {:db (assoc-in db [:inbox :labels k] text)}))

  (rf/reg-event :inbox/scrolled
    (fn [{:keys [db]} [_ offset]] {:db (assoc-in db [:inbox :scroll] offset)})))

;; ---------------------------------------------------------------------------
;; Views. Module-level: a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview item-cell
  "The caller's row content: ONE item, read by identity, inside its own
  boundary. This is what the slot body mounts, and it is why an edit to one
  row is one boundary's business."
  {:props [:map [:item-key :some]]}
  [{:keys [item-key]}]
  [:span {:data-part "cell"} (v/sub [:inbox/label item-key])])

(v/defview inbox
  "The whole call site, and it is the length the bead asks for. Four reads
  and three intents drive a ten-thousand-row list; the control receives no
  item and the slot body mounts the row's own view."
  [{:keys [list-id row-extent viewport-extent overscan]}]
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
    :row             (v/render-fn [k _] [item-cell {:item-key k}])}])

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(defn seed!
  "Seed the frame with the ten-thousand-item collection. Public so the
  parity suite starts from the same frame value these laws do."
  []
  (frame/replace-app-db! fid (seed-db)))

(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))

(defn scroll-to!
  "Move the window by the application's own event — never by writing a
  host property. Public for the parity suite."
  [offset]
  (send! [:inbox/scrolled offset]))

(def caller-props
  "The props BOTH arms drive the list with, from the fixture. Public so
  the parity suite's compiled caller is handed exactly these — a parity
  comparison over two different descriptors would be comparing
  collections, not lowerings."
  {:list-id         (:list-id ctrl-021)
   :row-extent      (:row-extent ctrl-021)
   :viewport-extent (:viewport-extent ctrl-021)
   :overscan        (:overscan ctrl-021)})

(defn render-tree
  "The structural tree for the whole page at the current frame value.
  Public so the parity suite renders the interpreted arm through exactly
  this path."
  ([] (render-tree {}))
  ([props]
   (t/with-render (t/render [inbox (merge caller-props props)]))))

(defn part
  "Every node carrying semantic part `p`, in document order."
  [tree p]
  (t/find-all tree #(= p (:data-part (t/attrs %)))))

(defn rows [tree] (part tree "row"))

(defn viewport [tree] (first (part tree "viewport")))

(defn cells
  "Every row-content BOUNDARY the render produced — one per rendered row,
  each holding the identity it read."
  [tree]
  (t/find-all tree #(= ::item-cell (:view-id %))))

(defn- row-keys-of
  "The identities the rendered rows carry, in document order, read off the
  content boundaries rather than off the control's own bookkeeping."
  [tree]
  (mapv #(:item-key (:props %)) (cells tree)))

(defn- dom-ids-of [tree] (mapv #(:id (t/attrs %)) (rows tree)))

;; ===========================================================================
;; FH-CTRL-021 — the window is arithmetic
;; ===========================================================================

(deftest fh-ctrl-021-the-window-is-a-pure-function-of-five-integers
  (testing "Per FH-CTRL-021: the visible window is decided by arithmetic
            over the item count, the fixed row extent, the viewport, the
            offset and the overscan — no host, no frame, no measurement
            pass. Four offsets cover the four things that can go wrong: the
            top edge clamps rather than going negative, an unaligned offset
            straddles a row at both ends, the middle clamps at neither, and
            an offset past the end is clamped to the last full screen."
    (let [{:keys [item-count row-extent viewport-extent overscan canvas-extent
                  windows]} ctrl-021]
      (doseq [{:keys [note scroll-offset] w-first :first w-count :count} windows]
        (let [w (coll/window {:item-count      item-count
                              :row-extent      row-extent
                              :viewport-extent viewport-extent
                              :scroll-offset   scroll-offset
                              :overscan        overscan})]
          (is (= {:first w-first :count w-count :extent canvas-extent} w) note)))

      (testing "and the last window really is at the end of the collection"
        (let [{w-first :first w-count :count} (last windows)]
          (is (= item-count (+ w-first w-count))
              "an offset past the end renders the LAST row, not a window
               beyond it"))))))

(deftest fh-ctrl-021-the-rendered-count-does-not-depend-on-the-item-count
  (testing "Per FH-CTRL-021: this is the cost claim, stated as arithmetic.
            The window is bounded by ceil(viewport/extent) + 1 + 2*overscan
            for EVERY collection size, so ten rows and ten million render
            the same work. The bound is asserted as tight — one of the
            fixture's own offsets reaches it — because a ceiling nothing
            reaches is true and proves nothing."
    (let [{:keys [row-extent viewport-extent overscan window-ceiling windows]} ctrl-021
          at (fn [n offset]
               (:count (coll/window {:item-count      n
                                     :row-extent      row-extent
                                     :viewport-extent viewport-extent
                                     :scroll-offset   offset
                                     :overscan        overscan})))]
      (is (= window-ceiling
             (+ (quot viewport-extent row-extent) 1 (* 2 overscan)))
          "the fixture's ceiling IS the arithmetic, restated")
      (is (= window-ceiling (apply max (map :count windows)))
          "and the fixture's own offsets reach it, so the bound is tight")

      (doseq [n [1000 100000 10000000]]
        (doseq [{:keys [scroll-offset]} windows]
          (is (<= (at n scroll-offset) window-ceiling)
              (str "a collection of " n " never renders more than the ceiling"))))

      (is (= (at 10000000 500000) (at 100000 500000))
          "and two collections three orders of magnitude apart render the
           SAME number of rows at one offset — the work is a property of
           the viewport"))))

(deftest fh-ctrl-021-the-window-is-total-over-a-degenerate-collection
  (testing "Per FH-CTRL-021: there is no arrangement of integers for which
            the honest answer is an exception. An empty collection and a
            viewport with no height each answer a window of zero rows — and
            the second still states the collection's FULL extent, because a
            collection ten thousand rows tall is ten thousand rows tall
            whether or not anything is looking at it."
    (let [{:keys [item-count row-extent viewport-extent overscan
                  empty-collection no-viewport]} ctrl-021]
      (is (= empty-collection
             (coll/window {:item-count      0
                           :row-extent      row-extent
                           :viewport-extent viewport-extent
                           :scroll-offset   0
                           :overscan        overscan}))
          "an empty collection renders nothing and has no extent")
      (is (= no-viewport
             (coll/window {:item-count      item-count
                           :row-extent      row-extent
                           :viewport-extent 0
                           :scroll-offset   0
                           :overscan        overscan}))
          "a viewport with no height renders nothing and the collection is
           still its full height")
      (is (not= (:extent empty-collection) (:extent no-viewport))
          "non-vacuous: the two degenerate answers really do differ")

      (testing "and absent or negative inputs read as their floor"
        (is (= {:first 0 :count 0 :extent 0} (coll/window {}))
            "an entirely absent descriptor is a window of nothing")
        (is (= (coll/window {:item-count 0 :row-extent 32 :viewport-extent 320
                             :scroll-offset 0})
               (coll/window {:item-count -5 :row-extent 32 :viewport-extent 320
                             :scroll-offset -900}))
            "a negative count and a negative offset read as zero")))))

(deftest fh-ctrl-021-reveal-answers-the-smallest-offset-that-shows-a-row
  (testing "Per FH-CTRL-021: the arithmetic behind 'the keyboard moved past
            the edge'. A row already whole on screen answers the CURRENT
            offset — which is what stops arrow keys inside the window from
            jerking the list — a row below is brought to the bottom edge, a
            row above to the top edge, and the answer is clamped into the
            scrollable range like any other offset.

            It is a function rather than a behaviour because which key
            moves the active row is the application's policy, not the
            control's."
    (let [{:keys [item-count row-extent viewport-extent reveals]} ctrl-021]
      (doseq [{:keys [note scroll-offset index offset]} reveals]
        (is (= offset
               (coll/reveal-offset {:item-count      item-count
                                    :row-extent      row-extent
                                    :viewport-extent viewport-extent
                                    :scroll-offset   scroll-offset}
                                   index))
            note))
      (let [[unmoved] reveals]
        (is (= (:scroll-offset unmoved) (:offset unmoved))
            "non-vacuous: the first case really is the one where nothing
             moves, so the three that follow are not all trivially equal to
             their input")))))

;; ===========================================================================
;; FH-CTRL-021 — the tree holds the window and states the collection
;; ===========================================================================

(deftest fh-ctrl-021-the-tree-holds-the-window-not-the-collection
  (testing "Per FH-CTRL-021: ten thousand items, forty-four nodes. The
            rendered row count is exactly the window's, the canvas states
            the collection's full scrollable extent, and the viewport
            states which window it is showing — three facts a test reads by
            equality off the tree, with no timing and no instrumentation."
    (register!)
    (seed!)
    (let [{:keys [item-count canvas-extent windows]} ctrl-021
          {w-first :first w-count :count} (first windows)
          tree (render-tree)]
      (is (= w-count (count (rows tree)))
          "the DOM-shaped tree holds the window")
      (is (< w-count item-count)
          "non-vacuous: the collection is very much larger than the window")
      (is (= (str canvas-extent "px")
             (:height (:style (t/attrs (first (part tree "canvas"))))))
          "and the canvas states the collection's full scrollable extent")
      (is (= [(str w-first) (str w-count)]
             [(:data-window-first (t/attrs (viewport tree)))
              (:data-window-count (t/attrs (viewport tree)))])
          "while the viewport states which window it is showing"))))

(deftest fh-ctrl-021-every-rendered-row-states-the-collection-honestly
  (testing "Per FH-CTRL-021: the DOM holds the window and the accessibility
            tree holds the collection, and both are true at the same time.
            Every rendered row carries its ABSOLUTE position and the
            collection's TRUE size, so a screen reader is told 'row 4,997
            of 10,000' about a document containing forty-eight rows. A
            virtualized list that omitted this would say there were
            forty-eight."
    (register!)
    (seed!)
    (let [{:keys [item-count posinset-at first-key-at]} ctrl-021]
      (scroll-to! (:scroll-offset first-key-at))
      (let [tree     (render-tree)
            attrs    (mapv t/attrs (rows tree))
            head     (first attrs)
            rendered (count attrs)]
        (is (= (str (:aria-posinset posinset-at)) (:aria-posinset head))
            "the first rendered row states its ABSOLUTE position")
        (is (= (str (:aria-setsize posinset-at)) (:aria-setsize head))
            "and the collection's true size")
        (is (= (vec (repeat rendered (str item-count)))
               (mapv :aria-setsize attrs))
            "as does every other rendered row")
        (is (= (mapv (comp str inc)
                     (range (:index first-key-at)
                            (+ (:index first-key-at) rendered)))
               (mapv :aria-posinset attrs))
            "and the positions are the absolute ones, ascending, with no
             renumbering to the window")
        (is (not= "1" (:aria-posinset head))
            "non-vacuous: the first rendered row is NOT the first item")))))

;; ===========================================================================
;; FH-CTRL-021 — identity survives the window moving
;; ===========================================================================

(deftest fh-ctrl-021-a-row-carries-two-addresses-and-neither-derives-the-other
  (testing "Per FH-CTRL-021: the `:key` is the caller's item identity and
            the DOM id is the row's position. Both are asserted at one
            absolute index, and they are different strings on purpose —
            deriving either from the other would make a reorder rename a
            DOM id, or make an accessibility relationship depend on a
            domain key's spelling."
    (register!)
    (seed!)
    (let [{:keys [scroll-offset index key dom-id]} (:first-key-at ctrl-021)]
      (scroll-to! scroll-offset)
      (let [tree (render-tree)]
        (is (= key (first (row-keys-of tree)))
            "the first rendered row content reads the caller's item id")
        (is (= dom-id (first (dom-ids-of tree)))
            "and the row's element is addressed by its position")
        (is (= dom-id (coll/row-dom-id (:list-id ctrl-021) index))
            "which is exactly what `row-dom-id` answers, so a caller can
             compute the address it must name in `aria-activedescendant`")
        (is (not= key dom-id)
            "non-vacuous: the two addresses are different values")))))

(deftest fh-ctrl-021-moving-the-window-re-keys-nothing-in-the-overlap
  (testing "Per FH-CTRL-021: this is the law that separates a virtual list
            from a paginator. Two offsets one screen apart produce
            overlapping windows, and every key in the overlap must be at
            the same ABSOLUTE index in both — a control that keyed by
            window POSITION would renumber the whole overlap, and React
            would remount every row in it while the user was mid-scroll."
    (register!)
    (seed!)
    (let [{:keys [from to overlap-count]} (:movement ctrl-021)
          _       (scroll-to! from)
          before  (row-keys-of (render-tree))
          _       (scroll-to! to)
          after   (row-keys-of (render-tree))
          shared  (filterv (set after) before)]
      (is (= overlap-count (count shared))
          "the two windows really do overlap, by the fixture's own count")
      (is (seq shared) "non-vacuous: there is an overlap to reason about")
      (is (= (take-last overlap-count before) shared)
          "the overlap is the TAIL of the earlier window")
      (is (= shared (take overlap-count after))
          "and the HEAD of the later one — the same keys, in the same
           order, at the same absolute indices, so nothing was re-keyed")
      (is (not= before after)
          "non-vacuous: the window really moved"))))

;; ===========================================================================
;; FH-CTRL-021 — the fan-out is the window's, and the control reads nothing
;; ===========================================================================

(deftest fh-ctrl-021-there-is-one-item-read-per-rendered-row
  (testing "Per FH-CTRL-021: the dependency fan-out is an exact integer and
            it is the WINDOW's. Forty-four item reads exist for a
            ten-thousand-item collection, one per rendered row, each in the
            row's own boundary — so the number of subscriptions a virtual
            list costs is a property of the viewport, exactly as the render
            count is."
    (register!)
    (seed!)
    (let [{:keys [item-reads-at-top item-count]} ctrl-021
          tree (render-tree)
          read (row-keys-of tree)]
      (is (= item-reads-at-top (count read))
          "one item read per rendered row")
      (is (= item-reads-at-top (count (distinct read)))
          "each a DIFFERENT item, so the count is a fan-out and not a
           repeated read of one")
      (is (= (count (rows tree)) (count read))
          "and the reads and the rendered rows are the same set")
      (is (< (count read) item-count)
          "non-vacuous: the collection is far larger than the fan-out"))))

(deftest fh-ctrl-021-editing-one-row-moves-one-rows-read-and-nothing-else
  (testing "Per FH-CTRL-021: the cost model, asserted as a PAIR. Relabelling
            one item changes that row's rendered content and leaves every
            other row byte-identical — and, crucially, leaves the IDENTITY
            vector `identical?`, which is what the control's own props are
            made of. So the control is not invalidated by an edit to a row
            it renders.

            On its own that is a claim about nothing, satisfied by a
            collection that never changes. So the same edit is asserted
            against the CONTAINER read, which DOES move: that is what a
            control handed the item vector would have been given, and it is
            why there is nowhere to put one."
    (register!)
    (seed!)
    (let [{:keys [scroll-offset]}     (:first-key-at ctrl-021)
          {:keys [edited-key edited-label]} ctrl-021
          _              (scroll-to! scroll-offset)
          ids-before     (t/with-render (v/sub [:inbox/ids]))
          wide-before    (t/with-render (v/sub [:inbox/labels]))
          before         (mapv t/text (part (render-tree) "cell"))
          _              (send! [:inbox/relabelled edited-key edited-label])
          ids-after      (t/with-render (v/sub [:inbox/ids]))
          wide-after     (t/with-render (v/sub [:inbox/labels]))
          after          (mapv t/text (part (render-tree) "cell"))
          differing      (keep-indexed (fn [i [a b]] (when (not= a b) i))
                                       (map vector before after))]
      (is (identical? ids-before ids-after)
          "the identity vector did not move, so neither did the control's
           `:row-keys` prop")
      (is (= 1 (count differing))
          "exactly ONE rendered row's content changed")
      (is (= edited-label (nth after (first differing)))
          "and it is the one that was edited")
      (is (not= wide-before wide-after)
          "non-vacuous: the CONTAINER read did move on the same edit — the
           read a control taking the item vector would have been handed")
      (is (= (count before) (count after))
          "and the window itself did not move"))))

;; ===========================================================================
;; FH-CTRL-021 — the scroll position is ordinary frame data
;; ===========================================================================

(deftest fh-ctrl-021-the-scroll-position-is-the-callers-and-the-control-writes-nothing
  (testing "Per FH-CTRL-021: the control renders from the caller's
            `:scroll-offset` and emits an intent carrying `::v/scroll-top`.
            There is no host slot, no ref and no controller record, so the
            scroll position is ordinary frame data — carried by epochs,
            restorable from a snapshot, readable by a tool that has mounted
            nothing.

            The proof is that a render moves the window and moves NOTHING
            in the frame: the window is decided by a value the frame
            already held, and a structural render publishes nothing back."
    (register!)
    (seed!)
    (let [{:keys [scroll-offset]} (:first-key-at ctrl-021)
          before (frame/frame-app-db-value fid)
          _      (render-tree)
          after  (frame/frame-app-db-value fid)]
      (is (= before after)
          "rendering the list wrote nothing anywhere in the frame")

      (testing "and the window follows the frame's own value"
        (let [top (row-keys-of (render-tree))]
          (scroll-to! scroll-offset)
          (let [moved (row-keys-of (render-tree))]
            (is (not= top moved)
                "an ordinary event moved the window")
            (is (= scroll-offset (get-in (frame/frame-app-db-value fid) [:inbox :scroll]))
                "and the offset the window came from is in app-db, where a
                 tool reads it"))))

      (testing "the intent the viewport carries is the caller's, completed
                with the closed scroll projection"
        (let [attrs (t/attrs (viewport (render-tree)))]
          (is (= [:inbox/scrolled ::v/scroll-top] (:on-scroll attrs))
              "so the host's scrollTop reaches an ordinary event")
          (is (= [:inbox/key-pressed ::v/key] (:on-key-down attrs))
              "and the key reaches one too — the control decides no keyboard
               policy of its own"))))))

(deftest fh-ctrl-021-the-control-owns-no-record-and-needs-no-address
  (testing "Per FH-CTRL-021: the control is props-only. It never asks for a
            `v/controller-key`, so it has no record to address, no
            generation to fence and nothing for a causal owner to release —
            which is why `:control` is absent from its schema and a caller
            passing one gets no special treatment.

            The evidence is the render itself: a props-only view is
            renderable with NO controller vocabulary anywhere in the call,
            and a control that owned a record would refuse it under
            `:rf.error/view-control-address-missing`."
    (register!)
    (seed!)
    (is (= conf/no-throw
           (conf/caught-id #(render-tree)))
        "the whole list renders without an address")
    (let [schema (:props-schema (v/describe coll/virtual-list))
          named  (set (map first (filter vector? schema)))]
      (is (contains? named :row-keys)
          "non-vacuous: the control really does declare a schema this reads")
      (is (not (contains? named :control))
          "and `:control` is not one of its props"))))

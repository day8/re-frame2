(ns re-frame.bench.hicasso.arm1.props-bailout-dom-cljs-test
  "A PAGE-CHROME WRITE DOES NOT RE-RENDER UNCHANGED ROWS (rf2-2rtt6.52).

  The defect this file pins was measured on the tier-1 feed shape: a write
  that moved a key the PAGE boundary read re-rendered the page, and React
  then re-rendered **all 300 card boundaries beneath it** — page body 1,
  card bodies 300 of 300, with every card's props and every card's
  subscription values equal. Those 300 unchanged-row renders are
  themselves the witness; the page's own chrome does move, so this is not
  a claim that the DOM is unchanged. A boundary was a plain React function
  component, and React re-renders the children of a re-rendered parent
  unless the element is referentially identical (a `for` builds fresh
  ones) or the component bails out itself.

  That contradicted the programme's central architectural claim — that
  boundaries are independent, and a write wakes only its readers — on the
  bulk row the bar is set on. `mint-view!` now mints its component behind
  a `React.memo` whose comparator is `=` on the props map: Reagent's argv
  compare, which is what stops exactly this cascade there.

  ## Why this file exists next to the shape roster's witness

  The roster's `shapes/narrow_dom_cljs_test` found the defect and recorded
  it as a finding at 300 of 300. The repair is a **runtime** change, so
  its regression guard belongs with the runtime — and has to be readable
  without the roster, which is a separate deliverable on a separate
  branch.

  ## The four claims, and why the second and third are the load-bearing ones

  1. **The cascade is gone.** A chrome write re-runs the page and **zero**
     rows, and leaves every row's DOM node the identical object.
  2. **A subscription still propagates — in the very commit that
     re-renders the page.** This is the claim that says the bail-out is
     correct rather than merely fast. A Hicasso boundary does *not* derive
     its output from props alone; it reads subscriptions. A memo that
     bailed on equal props while a subscription had moved would freeze a
     row on screen — the exact failure class this arm has already repaired
     four times. So the toggle here is chosen to move the page's read AND
     one row's read in **one** commit: React consults the comparator for
     every row, and the one row whose store moved must re-render anyway.
     It does, because React tests `checkScheduledUpdateOrContext` before
     it ever calls the comparator, and [[re-frame.bench.hicasso.arm1.runtime/flush!]]
     has already handed that fiber its own `onStoreChange`.
  3. **Props still propagate.** A bail-out that never re-renders is worse
     than one that always does, so the same write is driven through a page
     that *forwards* the chrome value into every row's props — and there
     every row re-renders. Same model, same write, same rows; the only
     difference is whether the value reaches the props, which is precisely
     what the comparator is allowed to look at.
  4. **It does not depend on the page's size**, so what is asserted is a
     law rather than a number that happens to hold at one B.

  Claim 1 is the mutation witness: remove the comparator from
  `mint-view!` and it goes red at row-count of row-count.

  Runtime: `-dom-cljs-test`. Under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-props-bailout-dom)

(def ^:private row-count
  "Enough rows that a cascade is unmistakable and a suite still runs in a
  blink. The shape roster takes the same reading at 300."
  100)

(def ^:private chrome-key
  "The draft key that stands in for page chrome — a tab, a filter box, a
  sort control. Nothing but the page reads it, which is the entire point:
  a row has no way to care that it moved."
  ::chrome)

(def ^:private moved-row
  "Deliberately not the first row and not the last, where an off-by-one in
  a list rebuild would be hardest to tell from a correct narrow update."
  37)

(defn- skip! [why]
  (is true (str "a props-bail-out claim needs a real React DOM — " why)))

;; ---------------------------------------------------------------------------
;; Counters — the only thing that can see this defect
;; ---------------------------------------------------------------------------

(def ^:private !row-runs (atom 0))
(def ^:private !page-runs (atom 0))

(defn- reset-runs! [] (reset! !row-runs 0) (reset! !page-runs 0) nil)
(defn- runs [] {:rows @!row-runs :page @!page-runs})

;; ---------------------------------------------------------------------------
;; The page, in two cuts that differ by one prop
;; ---------------------------------------------------------------------------

(defview row
  "One row. Reads its own todo, and renders a banner only when the page
  handed it one — which is how the same component serves both the
  bail-out claim and the props-propagate claim without either being a
  different application."
  [{:keys [id banner]}]
  (swap! !row-runs inc)
  [:li.row {:data-id id}
   [:span.title (str (:title (rt/sub [:dogfood/todo id])))]
   [:span.done (if (rt/sub [:dogfood/done? id]) "done" "open")]
   (when banner [:span.banner (str banner)])])

(defview page
  "Reads chrome, and does NOT forward it. `:dogfood/remaining` is read
  here on purpose: it is what makes a row toggle move the PAGE too, so
  claim 2 can be taken in a single commit that re-renders the parent."
  [_]
  (swap! !page-runs inc)
  [:div.page
   [:h1.chrome (str (rt/sub [:dogfood/draft chrome-key]))]
   [:span.remaining (str (rt/sub [:dogfood/remaining]))]
   [:ul.rows
    (for [id (rt/sub [:dogfood/visible-ids])]
      [row {:key id :id id}])]])

(defview forwarding-page
  "The same page, forwarding the chrome value into every row's props. The
  one edit, and the whole of claim 3."
  [_]
  (swap! !page-runs inc)
  (let [banner (rt/sub [:dogfood/draft chrome-key])]
    [:div.page
     [:h1.chrome (str banner)]
     [:ul.rows
      (for [id (rt/sub [:dogfood/visible-ids])]
        [row {:key id :id id :banner banner}])]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh!
  ([] (fresh! row-count))
  ([n]
   (lane/leave-act-environment!)
   (dogfood/make-frame! frame-id n)
   (dogfood/reseed! frame-id n)
   (reset-runs!)
   frame-id))

(defn- mount-page!
  ([] (mount-page! page))
  ([view] (mount/root! (mount/fresh-container!) frame-id [view {}])))

(defn- rows-of [handle]
  (array-seq (.querySelectorAll (:container handle) "li.row")))

(defn- row-texts [handle] (mapv #(.-textContent %) (rows-of handle)))

(defn- chrome-text [handle]
  (some-> (.querySelector (:container handle) "h1.chrome") (.-textContent)))

(defn- write-chrome! [handle text]
  (mount/dispatch! handle [:dogfood/edit-draft chrome-key text]))

;; ---------------------------------------------------------------------------
;; 1 — the cascade is gone
;; ---------------------------------------------------------------------------

(deftest a-page-chrome-write-re-renders-no-unchanged-row
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount-page!)]
        (try
          (let [nodes-before (vec (rows-of handle))
                text-before  (row-texts handle)
                edges-before (:edges (rt/stats))]
            (is (= row-count (count nodes-before)))
            (reset-runs!)
            (write-chrome! handle "typed")
            (is (= 1 (:page (runs)))
                "the page re-ran once, which is correct — it reads the chrome")
            (is (= 0 (:rows (runs)))
                (str "and NOT ONE of the " row-count " rows did. Before the "
                     "props-equality bail-out this read " row-count " — every "
                     "row re-rendered, and produced identical DOM while doing "
                     "it (rf2-2rtt6.52)"))
            (is (= "typed" (chrome-text handle))
                "and the write really landed — without this the row above
                 could pass by doing nothing at all")
            (is (= text-before (row-texts handle))
                "the ROWS' DOM is unchanged — the chrome above them did
                 move, which is why the row-body count and not a DOM
                 comparison is what witnesses this")
            (is (= nodes-before (vec (rows-of handle)))
                "and they are the IDENTICAL DOM nodes — React neither
                 replaced nor re-patched a subtree")
            (is (= edges-before (:edges (rt/stats)))
                "a bail-out is not an unmount: every row still holds its
                 edges, so the next write to one of them still arrives"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 — a subscription still propagates, in the commit that re-renders the page
;; ---------------------------------------------------------------------------

(deftest a-row-whose-subscription-moved-still-re-renders-under-a-re-rendered-page
  (testing "**the claim that makes the bail-out correct rather than merely
           fast.** Boundaries here read subscriptions, not just props, so
           the toggle below moves the PAGE's `:dogfood/remaining` and ONE
           row's `:dogfood/done?` in a single commit. React therefore
           consults the comparator for all 100 rows — every one of which
           has `=` props, including the toggled one, whose props map is
           `{:id 37}` before and after. Exactly one must re-render anyway"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount-page!)]
          (try
            (let [before (row-texts handle)]
              (reset-runs!)
              (mount/dispatch! handle [:dogfood/toggle moved-row])
              (is (= 1 (:page (runs)))
                  "the page re-ran — `:dogfood/remaining` moved, so this is
                   genuinely the parent-re-renders case and not a narrow
                   update that never consulted the comparator")
              (is (= 1 (:rows (runs)))
                  "and exactly ONE row re-ran: the bail-out did not freeze
                   the row whose store moved, and did not wake the 99 whose
                   store did not")
              (let [after   (row-texts handle)
                    differ  (into [] (keep-indexed (fn [i b] (when (not= b (nth after i)) i))) before)]
                (is (= [moved-row] differ)
                    "exactly one row's DOM moved, and it is the one written to")
                (is (re-find #"done" (nth after moved-row))
                    "and it moved in the right direction")))
            (finally (mount/release! handle))))))))

(deftest a-frozen-row-would-fail-the-claim-above
  (testing "the toggle assertion is not passing vacuously — the same row
           read `open` before the write, so `differing-indices` above is a
           live comparison rather than one that always answers empty"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount-page!)]
          (try
            (is (re-find #"open" (nth (row-texts handle) moved-row)))
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 2b — a CONTEXT change still propagates
;; ---------------------------------------------------------------------------

(def ^:private other-frame-id ::arm1-props-bailout-dom-other)

(deftest a-context-change-re-renders-rows-whose-props-did-not-move
  (testing "the third channel into the shell. `useContext` carries the frame,
           and React propagates a context change to its consumers directly —
           ahead of the comparator and through a memo. Witnessed by
           re-rendering the SAME root under a DIFFERENT frame whose rows
           carry different titles: every row's props map is `{:id n}` before
           and after, so props alone would bail every one of them out and
           freeze the page on the old frame's data"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (lane/leave-act-environment!)
        (dogfood/make-frame! other-frame-id row-count)
        (dogfood/reseed! other-frame-id row-count)
        ;; Give the other frame a row the first frame does not have.
        (rt/dispatch! other-frame-id [:dogfood/edit-draft moved-row "from the other frame"])
        (rt/dispatch! other-frame-id [:dogfood/commit moved-row])
        (let [handle (mount-page!)]
          (try
            (is (re-find #"todo" (nth (row-texts handle) moved-row))
                "the first frame's data is on screen")
            (reset-runs!)
            (mount/render! (assoc handle :frame other-frame-id) [page {}])
            (mount/settle!)
            (is (= row-count (:rows (runs)))
                "every row re-rendered, though not one row's props moved")
            (is (re-find #"from the other frame" (nth (row-texts handle) moved-row))
                "and the DOM carries the NEW frame's value — a memo that
                 outranked context would have frozen the page here")
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 3 — props still propagate
;; ---------------------------------------------------------------------------

(deftest a-chrome-value-forwarded-into-props-does-re-render-every-row
  (testing "the same write, the same rows, the same model — the only
           difference is that the page forwards the chrome value into each
           row's props. A bail-out that never re-rendered would be worse
           than one that always did, so this is the half that says the
           comparator is comparing and not simply refusing"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount-page! forwarding-page)]
          (try
            (reset-runs!)
            (write-chrome! handle "forwarded")
            (is (= 1 (:page (runs))))
            (is (= row-count (:rows (runs)))
                (str "all " row-count " rows re-ran, because all " row-count
                     " rows' props moved"))
            (is (= row-count (.-length (.querySelectorAll (:container handle) "span.banner")))
                "and the forwarded value reached the DOM of every one")
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; A comparison that throws renders, rather than crashing the tree
;; ---------------------------------------------------------------------------

(deftype Explosive [id]
  IEquiv
  (-equiv [_ _] (throw (js/Error. "boundary props comparison exploded"))))

(defview explosive-row
  "Takes a value whose `-equiv` throws. Two renders hand it two distinct
  instances, so `identical?` cannot short-circuit the comparison and the
  throw really reaches React's comparator."
  [{:keys [id]}]
  (swap! !row-runs inc)
  [:li.row {:data-id id} [:span.title "boom"]])

(defview explosive-page
  [_]
  (swap! !page-runs inc)
  [:div.page
   [:h1.chrome (str (rt/sub [:dogfood/draft chrome-key]))]
   [:ul.rows
    (for [id (take 5 (rt/sub [:dogfood/visible-ids]))]
      [explosive-row {:key id :id id :boom (Explosive. id)}])]])

(deftest a-props-comparison-that-throws-fails-open
  (testing "`=` over an app-owned value can throw, and this comparator runs
           inside React's `areEqual`, where an escaping throw is a render
           CRASH rather than a slow render. reagent-slim met the identical
           hazard on the identical comparison and ruled fail-OPEN
           (rf2-5al9d7): an extra render is always the safe branch, and
           skipping on a failed comparison risks a stale UI. `areEqual`
           inverts the polarity, so failing open is answering false"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount-page! explosive-page)]
          (try
            (is (= 5 (count (rows-of handle)))
                "it mounted at all — the comparator is not consulted on a
                 first render, so this only proves the setup")
            (reset-runs!)
            (write-chrome! handle "boom")
            (is (= 1 (:page (runs))))
            (is (= 5 (:rows (runs)))
                "every row re-rendered rather than the tree unmounting into
                 an error: the throw was caught and answered false")
            (is (= "boom" (chrome-text handle))
                "and the page is still live and still painting")
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 4 — it is a law, not a number that holds at one B
;; ---------------------------------------------------------------------------

(deftest no-row-re-renders-whatever-the-page-size
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (doseq [n [10 50 200]]
      (fresh! n)
      (let [handle (mount-page!)]
        (try
          (is (= n (count (rows-of handle))) (str n " rows mounted"))
          (reset-runs!)
          (write-chrome! handle (str "size-" n))
          (is (= {:rows 0 :page 1} (runs))
              (str "zero rows re-rendered at B = " n " — the cascade was
                   linear in B, so a repair that only held at one size
                   would not be a repair"))
          (finally (mount/release! handle)))))))

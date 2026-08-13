(ns re-frame.hicasso.examples.per-keystroke-dom-cljs-test
  "L3 — THE PER-KEYSTROKE CENSUS, BOTH WITNESS PAGES, ONE INSTRUMENT SET
  (rf2-hic-045).

  Specification §6 asks the four-field editor and the 100-cell grid to
  publish *the mechanical per-keystroke path: state writes, subscription
  recomputations, boundary runs, write amplification, commit, and visible
  echo*. Two of those six were already counted when this file was
  written — the write, by `editor.l0-cljs-test`, and the body count, by
  `editor.flow-dom-cljs-test` and `grid.scaling-dom-cljs-test` — and the
  page that publishes them is
  `docs/design/hicasso/product/per-keystroke.md`.

  This file takes the other four, and it takes all six on ONE mount so
  that the stages of a census are stages of the SAME keystroke rather
  than six keystrokes' worth of arithmetic laid side by side.

  ## Both pages, one file, on purpose

  The editor and the grid are two sizes of one mechanism, and the whole
  question the census asks is which stages move with size and which do
  not. Two files would have made that an arithmetic comparison between
  two instruments; one file makes it a reading. It is the same reason
  `grid.scaling-dom-cljs-test` mounts both grid sizes rather than
  importing a number from elsewhere.

  ## The four instruments this file adds

  [[addresses-moved]] diffs the app-db value either side of the DOM
  event and counts leaf addresses. `editor.l0-cljs-test` already asserts
  the SHAPE of one write with `=` over `dissoc`ed maps; this counts, so
  that a page can carry the figure beside the other five.

  [[with-counted-subs]] wraps each subscription's registered
  `:handler-fn` and counts invocations of the AUTHOR'S OWN computation
  fn — the body inside the memo wrapper, not the wrapper. It is
  installed before the mount, because `re-frame.subs` resolves
  `:handler-fn` off the registration once, at cache-entry build time, and
  a wrapper installed afterwards would be counted by nothing. It is
  removed in a `finally`, and the fixture's registrar snapshot is the
  second net.

  [[with-glass-spy]] counts writes of the `value` property onto live
  controls, by replacing the prototype's property descriptor with one
  that counts and delegates. It too must be installed BEFORE the mount:
  React's own change tracker captures the prototype descriptor when it
  begins tracking a node, so a spy installed afterwards is behind React's
  captured reference and would count nothing. [[type-into!]] here writes
  through a setter captured at namespace load, so a scripted keystroke's
  own write is outside the count by construction rather than by
  subtraction.

  [[mutations-during]] drains a `MutationObserver` synchronously with
  `takeRecords`, which is what makes it usable inside a discrete event:
  the observer's callback is a microtask and has not run yet, and the
  records are already there to be taken.

  ## The echo is read BEFORE the flush, and that is the point of it

  Every other mounted witness in this tree calls `hm/settle!` and then
  asserts the glass. That is right for a correctness row and wrong for a
  latency one — it reads the page after a flush the browser had not yet
  performed. [[echo-before-flush]] reads `.value` at the instant
  `dispatchEvent` returns, which is inside the discrete event, before any
  paint could occur. What that measures is not *the echo arrived within a
  frame*; it is the stronger and simpler fact that **no frame boundary is
  crossed at all**.

  ## What this file does NOT measure, and could not

  A clock. Nothing here reports a millisecond, a `p50` or a `p95`, and
  the page says why: `budgets.md` §4 registers `U1`–`U4` as
  distributional rows with no package-resident clock instrument, and §9.3
  puts building one at `rf2-hic-071` rather than here. Every figure this
  file takes is a monotone counter, so it reads the same on a loaded box
  as on a quiet one (`budgets.md` §2)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.editor.app :as editor-app]
            [re-frame.hicasso.examples.editor.subs :as editor-subs]
            [re-frame.hicasso.examples.editor.views :as editor-views]
            [re-frame.hicasso.examples.grid.app :as grid-app]
            [re-frame.hicasso.examples.grid.events :as grid-events]
            [re-frame.hicasso.examples.grid.subs :as grid-subs]
            [re-frame.hicasso.examples.grid.views :as grid-views]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.registrar :as registrar]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a mounted React root needs a real DOM — " why)))

;; ---------------------------------------------------------------------------
;; Typing, through a setter captured before any spy exists
;; ---------------------------------------------------------------------------

(def ^:private pristine-setters
  "The two prototypes' own `value` setters, captured at namespace load.

  [[with-glass-spy]] replaces those descriptors while it is installed, so
  a keystroke written through the live descriptor would be counted as a
  write the runtime made. Capturing here is what keeps the user agent's
  own write out of the count by construction — the alternative is to
  subtract one and hope the subtrahend never changes."
  {"TEXTAREA" (.-set (js/Object.getOwnPropertyDescriptor
                       js/HTMLTextAreaElement.prototype "value"))
   "INPUT"    (.-set (js/Object.getOwnPropertyDescriptor
                       js/HTMLInputElement.prototype "value"))})

(defn- set-native-value! [n v]
  (.call (get pristine-setters (.-tagName n) (get pristine-setters "INPUT")) n v))

(defn- type-into!
  "Type `text` at the end of `n` the way a browser does — the field moves
  first, the `input` event fires second — and answer the field's value at
  the instant `dispatchEvent` returned, BEFORE any flush."
  [n text]
  (set-native-value! n (str (.-value n) text))
  (.dispatchEvent n (js/Event. "input" #js {:bubbles true}))
  (.-value n))

;; ---------------------------------------------------------------------------
;; Instrument 1 — state writes
;; ---------------------------------------------------------------------------

(defn- leaves
  "Every leaf of a nested map as `{path value}`. A non-map value is a
  leaf, so the grid's `{[row col] \"7\"}` cells are one address each and
  the editor's `:revision` integer is one address."
  [m]
  (letfn [(walk [prefix v]
            (if (and (map? v) (seq v))
              (mapcat (fn [[k vv]] (walk (conj prefix k) vv)) v)
              [[prefix v]]))]
    (into {} (walk [] m))))

(defn- addresses-moved
  "How many leaf addresses differ between two app-db values."
  [before after]
  (let [b (leaves before)
        a (leaves after)]
    (count (into #{}
                 (remove (fn [k] (= (get b k ::absent) (get a k ::absent))))
                 (into (set (keys b)) (set (keys a)))))))

;; ---------------------------------------------------------------------------
;; Instrument 2 — subscription recomputations
;; ---------------------------------------------------------------------------

(defn- with-counted-subs
  "Install a counting wrapper on each of `sub-ids`' registered
  `:handler-fn`, call `(f read-counts reset-counts)`, and restore every
  registration in a `finally`.

  `read-counts` answers `{sub-id n}` for the wrappers that ran;
  `reset-counts` zeroes them, which is what lets one mount take a
  baseline at mount and a reading per keystroke.

  The wrapper is variadic and applies the original, so it is invocation-
  shape-agnostic: `re-frame.subs.memo` calls a layer-1 body as
  `(body-fn db query-v)` and a layer-n body with its own arity, and this
  counter has no opinion about which."
  [sub-ids f]
  (let [!counts   (atom {})
        originals (reduce (fn [m id] (assoc m id (registrar/lookup :sub id)))
                          {}
                          sub-ids)]
    (doseq [[id meta] originals]
      (let [orig (:handler-fn meta)]
        (registrar/register! :sub id
          (assoc meta :handler-fn
                 (fn [& args]
                   (swap! !counts update id (fnil inc 0))
                   (apply orig args))))))
    (try
      (f (fn [] @!counts) (fn [] (reset! !counts {})))
      (finally
        (doseq [[id meta] originals]
          (registrar/register! :sub id meta))))))

(defn- total [counts] (reduce + 0 (vals counts)))

;; ---------------------------------------------------------------------------
;; Instrument 3 — the commit, in glass writes and DOM mutations
;; ---------------------------------------------------------------------------

(defn- with-glass-spy
  "Count writes of `value` onto live controls while `f` runs.

  Replaces both prototypes' property descriptors with counting ones that
  delegate to the originals, and restores them in a `finally`. Installed
  around the MOUNT and not merely around the keystroke — React's change
  tracker captures the prototype descriptor when it starts tracking a
  node, so a spy installed after the mount sits behind the reference
  React already took."
  [f]
  (let [protos    [["INPUT" js/HTMLInputElement.prototype]
                   ["TEXTAREA" js/HTMLTextAreaElement.prototype]]
        !n        (atom 0)
        originals (mapv (fn [[_ p]] (js/Object.getOwnPropertyDescriptor p "value")) protos)]
    (doseq [[[_ p] d] (map vector protos originals)]
      (js/Object.defineProperty p "value"
        #js {:configurable true
             :enumerable   (.-enumerable d)
             :get          (.-get d)
             :set          (fn [v]
                             (this-as t
                               (swap! !n inc)
                               (.call (.-set d) t v)))}))
    (try
      (f (fn [] @!n) (fn [] (reset! !n 0)))
      (finally
        (doseq [[[_ p] d] (map vector protos originals)]
          (js/Object.defineProperty p "value" d))))))

(defn- mutations-during
  "The `MutationObserver` records `f` produced inside `container`, drained
  synchronously with `takeRecords` — the observer's own callback is a
  microtask and has not run when this returns."
  [container f]
  (let [obs (js/MutationObserver. (fn [_ _] nil))]
    (.observe obs container
              #js {:childList true :attributes true :characterData true :subtree true})
    (try
      (f)
      (vec (array-seq (.takeRecords obs)))
      (finally (.disconnect obs)))))

;; ---------------------------------------------------------------------------
;; The editor's census
;; ---------------------------------------------------------------------------

(def ^:private editor-sub-ids
  [::editor-subs/field ::editor-subs/committed ::editor-subs/revision
   ::editor-subs/dirty?])

(defn- editor-node [m field]
  (.querySelector (:container m) (str "[data-field='" (name field) "']")))

(deftest the-editors-per-keystroke-census
  (if-not (browser?)
    (skip! "a census needs a mounted page")
    (with-glass-spy
      (fn [glass-writes reset-glass!]
        (with-counted-subs editor-sub-ids
          (fn [sub-runs reset-subs!]
            (let [m (hm/mount! [editor-views/editor {}]
                               {:initial-events editor-app/initial-events})
                  n (editor-node m :title)
                  db #(rf/app-db-value (:frame m))]
              (hm/settle! m)

              (testing "the FIRST keystroke of a session"
                (reset-subs!)
                (reset-glass!)
                (let [before (db)
                      bodies (hm/bodies-run
                               (fn [] (type-into! n "a") (hm/settle! m)))
                      after  (db)]
                  (is (= 1 (addresses-moved before after))
                      "P1 — one leaf address, `[:draft :title]`")
                  (is (= 2 bodies)
                      "P5 — the field and the button row; `::dirty?` goes
                       false to true exactly once per session (D7)")))

              (testing "and a STEADY-STATE keystroke — the census proper"
                (reset-subs!)
                (reset-glass!)
                (let [before (db)
                      echo   (atom nil)
                      muts   (atom nil)
                      bodies (hm/bodies-run
                               (fn []
                                 (reset! muts
                                         (mutations-during
                                           (:container m)
                                           (fn [] (reset! echo (type-into! n "b")))))
                                 (hm/settle! m)))
                      after  (db)
                      runs   (sub-runs)]
                  (is (= 1 (addresses-moved before after))
                      "P1 — one state write")
                  (is (= 10 (total runs))
                      (str "P2 — subscription recomputations. Measured: "
                           (pr-str runs)))
                  (is (= 1 bodies)
                      "P3 — one boundary body, the title field's (D8)")
                  (is (= 1 (glass-writes))
                      "P4 — one write onto the glass by the runtime")
                  (is (= "Intents are dataab" @echo)
                      "P12 — the echo is on the glass at the instant
                       `dispatchEvent` returned, with no flush and no paint
                       in between")
                  (is (= 0 (count @muts))
                      "and the commit mutated no attribute, no child and no
                       text node: a controlled input's value is a PROPERTY,
                       so the echo never reaches the markup at all")))

              (hm/unmount! m))))))))

;; ---------------------------------------------------------------------------
;; The grid's census, at two sizes
;; ---------------------------------------------------------------------------

(def ^:private grid-sub-ids
  [::grid-subs/cell ::grid-subs/dimensions ::grid-subs/row-total])

(defn- grid-node [m row col]
  (.querySelector (:container m) (str "#" (grid-events/cell-id row col))))

(defn- grid-census
  "Mount the grid at `dimensions`, type one accepted digit into `[3 4]`,
  and answer the census as a map."
  [dimensions]
  (with-glass-spy
    (fn [glass-writes reset-glass!]
      (with-counted-subs grid-sub-ids
        (fn [sub-runs reset-subs!]
          (let [m (hm/mount! [grid-views/grid {}]
                             {:initial-events (grid-app/initial-events dimensions)})
                n (grid-node m 3 4)]
            (hm/settle! m)
            (reset-subs!)
            (reset-glass!)
            (let [before (rf/app-db-value (:frame m))
                  echo   (atom nil)
                  muts   (atom nil)
                  bodies (hm/bodies-run
                           (fn []
                             (reset! muts
                                     (mutations-during
                                       (:container m)
                                       (fn [] (reset! echo (type-into! n "1")))))
                             (hm/settle! m)))
                  after  (rf/app-db-value (:frame m))
                  runs   (sub-runs)]
              (try
                {:writes    (addresses-moved before after)
                 :sub-runs  (total runs)
                 :by-sub    runs
                 :bodies    bodies
                 :glass     (glass-writes)
                 :mutations (count @muts)
                 :echo      @echo}
                (finally (hm/unmount! m))))))))))

(deftest the-grids-per-keystroke-census-at-two-sizes
  (if-not (browser?)
    (skip! "a census needs a mounted page")
    (let [at-100 (grid-census {:rows 10 :cols 10})
          at-25  (grid-census {:rows 5 :cols 5})]
      (is (= 1 (:writes at-100))
          "P6 — one state write, `[:cells [3 4]]`")
      (is (= 2 (:bodies at-100))
          "P8 — the cell and its row's total (D1/D2)")
      (is (= 111 (:sub-runs at-100))
          (str "P7 — subscription recomputations at 10x10. Measured: "
               (pr-str (:by-sub at-100))))
      (is (= 31 (:sub-runs at-25))
          (str "P9 — subscription recomputations at 5x5. Measured: "
               (pr-str (:by-sub at-25))))
      (is (= 1 (:glass at-100))
          "one write onto the glass by the runtime")
      (is (= "341" (:echo at-100))
          "P12 — the echo is on the glass before any flush")
      (is (= 0 (:mutations at-100))
          "and no DOM mutation record: the value is a property")
      (is (= (:bodies at-25) (:bodies at-100))
          "THE CONTRAST THE CENSUS EXISTS FOR — boundary runs do not move
           with the mounted grid")))

  (testing "a REFUSED keystroke costs nothing anywhere"
    (if-not (browser?)
      (skip! "the refusal")
      (with-glass-spy
        (fn [glass-writes reset-glass!]
          (with-counted-subs grid-sub-ids
            (fn [sub-runs reset-subs!]
              (let [m (hm/mount! [grid-views/grid {}]
                                 {:initial-events (grid-app/initial-events
                                                    {:rows 10 :cols 10})})
                    n (grid-node m 3 4)]
                (hm/settle! m)
                (reset-subs!)
                (reset-glass!)
                (let [before (rf/app-db-value (:frame m))
                      echo   (atom nil)
                      bodies (hm/bodies-run
                               (fn [] (reset! echo (type-into! n "x")) (hm/settle! m)))
                      after  (rf/app-db-value (:frame m))]
                  (is (= 0 (addresses-moved before after))
                      "P10 — the model did not move")
                  (is (= 0 (total (sub-runs)))
                      (str "P11 — and nothing recomputed: an app-db that is `=` "
                           "to the last one publishes no movement, so no cell is "
                           "even asked. Measured: " (pr-str (sub-runs))))
                  (is (= 0 bodies)
                      "no body ran (D5)")
                  (is (= 1 (glass-writes))
                      "and the ONE write onto the glass is the refusal echo —
                       the committed value put back over the character the
                       model would not take")
                  (is (= "34" (.-value n))
                      "which is what the field shows"))
                (hm/unmount! m)))))))))

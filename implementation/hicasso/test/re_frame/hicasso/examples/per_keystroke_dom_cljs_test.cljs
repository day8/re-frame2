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
  through a setter captured on FIRST BROWSER USE, which
  [[with-glass-spy]] forces before it replaces anything, so a scripted
  keystroke's own write is outside the count by construction rather than
  by subtraction.

  [[mutations-during]] drains a `MutationObserver` synchronously with
  `takeRecords`, which is what makes it usable inside a discrete event:
  the observer's callback is a microtask and has not run yet, and the
  records are already there to be taken.

  ## The echo is read BEFORE the flush, and that is the point of it

  Every other mounted witness in this tree calls `hm/settle!` and then
  asserts the glass. That is right for a correctness row and wrong for a
  latency one — it reads the page after a flush the browser had not yet
  performed. [[type-into!]] reads `.value` at the instant
  `dispatchEvent` returns, which is inside the discrete event, before any
  paint could occur. What that measures is not *the echo arrived within a
  frame*; it is the stronger and simpler fact that **no frame boundary is
  crossed at all**.

  **But the reading only DISCRIMINATES where the model disagrees with the
  field.** A scripted keystroke reaches the real code path only by writing
  the accepted text onto the control and then firing the event, so on a
  keystroke the model takes VERBATIM the model's value and this file's own
  pre-event write are the same string — and a reading taken at dispatch
  return cannot tell an echo from its own setup. Those rows are kept,
  because the character surviving the converge is a real fact whose
  opposite is a real regression, but they are not what proves the claim.
  Each page therefore carries a second row typed into a field whose model
  answers something ELSE: the editor's `:slug`, which normalises, and the
  grid's cell, which refuses. Neither echoed string could have come from
  the pre-event write, and neither needed a flush to arrive.

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
  "The two prototypes' own `value` setters, captured on first use.

  [[with-glass-spy]] replaces those descriptors while it is installed, so
  a keystroke written through the live descriptor would be counted as a
  write the runtime made. Capturing before any spy exists is what keeps
  the user agent's own write out of the count by construction — the
  alternative is to subtract one and hope the subtrahend never changes.

  A `delay` and not a `def` of the map itself, because this namespace
  LOADS on the node lane — where its rows skip, but its top level still
  runs, and `js/HTMLInputElement` does not exist there.

  **[[with-glass-spy]] forces it before it replaces anything**, and that
  ordering is the whole correctness of this def rather than a nicety. Every
  keystroke in this file is typed inside a spy, so a delay left to be
  forced by the first [[set-native-value!]] would capture the SPY's setter
  and count the user agent's own write — turning the accepted keystroke's
  measured zero into a one. Forcing at the top of the spy is what makes
  *pristine* structural."
  (delay
    {"TEXTAREA" (.-set (js/Object.getOwnPropertyDescriptor
                         js/HTMLTextAreaElement.prototype "value"))
     "INPUT"    (.-set (js/Object.getOwnPropertyDescriptor
                         js/HTMLInputElement.prototype "value"))}))

(defn- set-native-value! [n v]
  (let [setters @pristine-setters]
    (.call (get setters (.-tagName n) (get setters "INPUT")) n v)))

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
  React already took.

  It also FORCES [[pristine-setters]] before it replaces anything — see
  that def; the ordering is what keeps the user agent's own write out of
  the count."
  [f]
  (let [_         @pristine-setters ;; capture BEFORE the swap — see that def
        protos    [["INPUT" js/HTMLInputElement.prototype]
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

(defn- summarise-mutations
  "A mutation record list as `{[kind target] n}` — the shape a commit can
  be ATTRIBUTED from, where a bare count can only be reported.

  Added after the census's first reading, which counted 7 and 8 records
  against a predicted 0 and could say nothing about what they were. It
  reads the records the same observer already took; no record's existence
  or count moves, and the counts either side of the change are identical."
  [records]
  (reduce (fn [m r]
            (let [kind   (.-type r)
                  target (.-target r)
                  tag    (or (.-tagName target) (.-nodeName target))
                  label  (if (= "attributes" kind)
                           (str tag "@" (.-attributeName r))
                           (str tag))]
              (update m [kind label] (fnil inc 0))))
          {}
          records))

(defn- attribute-trace
  "The attribute records in order, as
  `\"<attribute>: <value before this record> -> <value NOW>\"`.

  A count says a commit touched `name` four times; this says which
  attribute each of the four was and what it held beforehand, which is
  the difference between reporting the commit stage and ATTRIBUTING it.
  `:attributeOldValue` is what the observer needs to answer that, and it
  changes no record's existence or count.

  **NEITHER SIDE OF THE ARROW IS A NEW VALUE, AND NO ROW HERE IS AN
  OBSERVED TRANSITION.** A `MutationRecord` carries `oldValue` and no
  counterpart: a new value has to be read back off the element, and by
  the time this runs — after every record has been taken — that read
  answers what the attribute holds now, the FINAL value. Hence the
  `name: nil -> nil` rows below, printed by records that set `name` to
  `\"\"`.

  The attribution survives that intact, because it never rested on the
  right-hand side. It rests on the ORDER of the attribute names and on
  each record's old value, and those are read FORWARDS: `name`'s removal
  record carries `oldValue` `\"\"`, and that is the observation — not an
  inference — that `name` had been set to the empty string. Every caller
  below reads it that way, and
  `docs/design/hicasso/product/per-keystroke.md` §5 states the same limit
  before it presents the table it draws from these records.

  RECONSTRUCTING each record's new value from the NEXT same-attribute
  record's `oldValue` was weighed and DECLINED (rf2-hic-045, the audit of
  PR #8163). It would restate what the old values already carry; done
  correctly it must key on target IDENTITY as well as attribute name, not
  on the name alone; and the last record of each attribute would still
  fall back to a final read taken after `hm/settle!`, so the caveat above
  would survive the change that was supposed to remove it. Naming the
  limit costs nothing and hides nothing."
  [records]
  (into []
        (comp (filter (fn [r] (= "attributes" (.-type r))))
              (map (fn [r]
                     (str (.-attributeName r) ": "
                          (pr-str (.-oldValue r)) " -> "
                          (pr-str (.getAttribute (.-target r) (.-attributeName r)))))))
        records))

(defn- mutations-during
  "The `MutationObserver` records `f` produced inside `container`, drained
  synchronously with `takeRecords` — the observer's own callback is a
  microtask and has not run when this returns."
  [container f]
  (let [obs (js/MutationObserver. (fn [_ _] nil))]
    (.observe obs container
              #js {:childList         true
                   :attributes        true
                   :attributeOldValue true
                   :characterData     true
                   :subtree           true})
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
                      (str "P2 HELD — ten subscription recomputations for one
                            write. Measured: " (pr-str runs)))
                  (is (= {::editor-subs/field     4
                          ::editor-subs/committed 4
                          ::editor-subs/revision  1
                          ::editor-subs/dirty?    1}
                         runs)
                      "and the ten are every mounted cell, not the one the
                       keystroke moved: four field cells, four committed
                       cells, the revision and the dirty flag. Nine of them
                       compute the value they computed last time and notify
                       nobody")
                  (is (= 1 bodies)
                      "P3 — one boundary body, the title field's (D8)")
                  (is (= 0 (glass-writes))
                      "P4 MISSED, and the miss is the finding. The prediction
                       was one write; the measurement is ZERO. An ACCEPTED
                       keystroke is already on the glass — the user agent put
                       it there — and the model took it unchanged, so the
                       converge has nothing to write and React's own value
                       diff finds the node already showing what it is about to
                       commit. The echo of an accepted keystroke is free. The
                       refusal row below is this instrument's positive
                       control: it reads 1 on the same spy in the same file")
                  (is (= "Intents are dataab" @echo)
                      "the accepted keystroke SURVIVED the converge — the
                       character is still on the glass at the instant
                       `dispatchEvent` returned, with no flush and no paint in
                       between. The row is kept because its opposite is a real
                       regression: `front/controlled_dom_cljs_test` reproduces a
                       converge that wipes the character the user just typed.
                       But it is NOT P12's proof. `:title` takes what is typed,
                       so this expectation is ALSO the string [[type-into!]]
                       wrote onto the control before the event, and the reading
                       would hold even if the model had never moved. The
                       discriminating row is the slug's, below.")
                  (is (= {["attributes" "INPUT@name"]  4
                          ["attributes" "INPUT@type"]  2
                          ["attributes" "INPUT@value"] 1}
                         (summarise-mutations @muts))
                      "SEVEN mutation records against a predicted zero, and
                       not one of them is the echo. The value the user sees
                       is a PROPERTY and never reaches the markup; what the
                       markup gets is React's controlled-input update churning
                       `name` and `type` around the change and writing the
                       `value` ATTRIBUTE once, which is `defaultValue`.")
                  (is (= ["name: nil -> nil"
                          "type: \"text\" -> \"text\""
                          "value: \"Intents are dataa\" -> \"Intents are dataab\""
                          "name: \"\" -> nil"
                          "name: nil -> nil"
                          "type: \"text\" -> \"text\""
                          "name: \"\" -> nil"]
                         (attribute-trace @muts))
                      "TWO PASSES over one input, and the ORDER of the
                       attribute names is what says so: `name`, `type`,
                       `value`, `name` — then `name`, `type`, `name` with no
                       `value`, because the second pass finds `defaultValue`
                       already right. The arrows' RIGHT-hand sides carry
                       nothing: they are the attribute as it finally stands,
                       not each record's new value, which is why a record that
                       set `name` to the empty string prints `nil -> nil` (see
                       [[attribute-trace]]). That empty string is witnessed on
                       the LEFT of the following `name` record, and that is the
                       observation it was set and then removed. React 19
                       removes and restores `name` around every
                       controlled-input
                       update so that a radio group's changes apply
                       atomically, and this input has no `name` at all, so
                       four of the seven records are churn on an attribute the
                       application never wrote. The refusal row below
                       attributes the two passes.")))

              (testing "and P12's DISCRIMINATING reading — the slug NORMALISES"
                (let [slug  (editor-node m :slug)
                      typed (str (.-value slug) ", World")
                      echo  (type-into! slug ", World")]
                  (is (= "intents-are-data-world" echo)
                      (str "P12, and THIS is the row that proves it. The
                            accepted-keystroke readings above are taken on a
                            field that takes what is typed, so the model's value
                            and this census's own pre-event write are the same
                            string and the reading cannot tell them apart.
                            `:slug` normalises, so the model answers something
                            else — and what the field shows at the instant
                            `dispatchEvent` returned is the MODEL's value, with
                            no flush and no paint in between, so no frame
                            boundary was crossed. Typed onto the control: "
                           (pr-str typed) ". The normalisation is
                            length-CHANGING on purpose
                            (`editor.events/slugify`): one that preserved length
                            could be satisfied by a field that echoed nothing at
                            all."))
                  (hm/settle! m)))

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
                 :mutations (summarise-mutations @muts)
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
      (is (= {::grid-subs/cell 100 ::grid-subs/row-total 10 ::grid-subs/dimensions 1}
             (:by-sub at-100))
          "the attribution at 10x10 — every mounted cell, every row total,
           and the dimensions cell that nothing moved")
      (is (= {::grid-subs/cell 25 ::grid-subs/row-total 5 ::grid-subs/dimensions 1}
             (:by-sub at-25))
          "and at 5x5 the same three terms, each following the mount")
      (is (= 0 (:glass at-100))
          "zero writes onto the glass, exactly as the editor — an accepted
           keystroke is already showing what the model took")
      (is (= "341" (:echo at-100))
          "the accepted digit survived the converge, read before any flush —
           and NOT P12's proof, for the editor's reason: a cell takes a digit
           verbatim, so this is also what `type-into!` wrote onto the control
           before the event. The refusal below is this page's discriminating
           reading.")
      (is (= {["attributes" "INPUT@name"]  4
              ["attributes" "INPUT@type"]  2
              ["attributes" "INPUT@value"] 1
              ["characterData" "#text"]    1}
             (:mutations at-100))
          "the editor's seven, plus ONE — the row total's text node. That
           single `characterData` record is the only mutation on either page
           that a user could point at, and it belongs to the derived read
           rather than to the field that was typed into")
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
                      muts   (atom nil)
                      bodies (hm/bodies-run
                               (fn []
                                 (reset! muts
                                         (mutations-during
                                           (:container m)
                                           (fn [] (reset! echo (type-into! n "x")))))
                                 (hm/settle! m)))
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
                  (is (= "34" @echo)
                      "P12's OTHER discriminating reading, and the REFUSAL is
                       what makes it one. This census wrote \"34x\" onto the
                       control before the event; the model would not take the
                       `x`, so the \"34\" the field shows at the instant
                       `dispatchEvent` returned is the COMMITTED value and could
                       not have come from the pre-event write. React's
                       end-of-event restore performs it inside the discrete
                       event — the same single `value` write the row above
                       counts — so no frame boundary is crossed here either.")
                  (is (= "34" (.-value n))
                      "and the same value after the flush, which is what the
                       field goes on showing")
                  (is (= [{["attributes" "INPUT@name"] 2
                           ["attributes" "INPUT@type"] 1}
                          ["name: nil -> nil"
                           "type: \"text\" -> \"text\""
                           "name: \"\" -> nil"]]
                         [(summarise-mutations @muts) (attribute-trace @muts)])
                      "THE ATTRIBUTION, and it is decisive. A refusal runs ZERO
                       bodies, so React commits nothing — and THREE of the
                       accepted keystroke's seven records appear anyway, as one
                       `name`/`type`/`name` pass with no `value` write. It is
                       the ORDER of those names that carries this, not the
                       arrows: their right-hand sides are the final attribute
                       values rather than per-record new ones, so `name` prints
                       `nil -> nil` here too (see [[attribute-trace]]). Those
                       three are React's post-event controlled-state restore,
                       which is also what performs the single `value` PROPERTY
                       write the row above counts. The remaining four —
                       `name`, `type`, `value`, `name` — are the commit's, and
                       the accepted keystroke's trace is exactly those four
                       followed by these three."))
                (hm/unmount! m)))))))))

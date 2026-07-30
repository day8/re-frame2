(ns re-frame.bench.hicasso.p0-reagent-app
  "THE P0 REAGENT-ON-SUBS BASELINE RUN — mount and bulk, browser,
  `:advanced` (rf2-2rtt6.2; EP-0038 P0; the bar is HD-012).

  The `:init-fn` of the `:hicasso-bench` build. Driven by
  `implementation/freehand/test/re_frame/bench/hicasso/run.cjs`, which
  builds this entry, serves it, drives Chromium, prints every record and
  turns an arm-order refusal into exit code 2.

  What it produces is the SHIP BAR'S DENOMINATOR: mount and bulk view
  work for Reagent reading re-frame2 subscriptions, on the shapes the
  charter names, with the floor as the per-round calibrator, the
  Reagent/ratom arm as a labelled lower bound, and a positive control
  whose prediction is written down before the run.

  ## The order of operations, and why it is this order

  1. **The arm-order guard's self-test.** Before anything is measured.
     A harness whose copy of the rule has drifted must measure nothing,
     and the self-test replays recorded fixtures from the live study
     rather than a restatement of itself.
  2. **Canonical-DOM parity, under `:advanced`.** Repeated here even
     though it is a deterministic property, because a parity that held
     under `:none` and failed under `:advanced` would be a renaming bug
     silently deciding the comparison.
  3. **The mount rows**, then the bulk row, with a SETTLE POINT between
     every pair. A release that threw is fatal, and so is a release that
     returned normally without releasing: `lane/residue` reads the
     attached-container count and the frame's subscription ref-counts back
     to the empty-page baseline. The settle is not politeness to the
     assertion — a mount row is wholly synchronous and Reagent's disposals
     are queued on a macrotask, so without it row N+1 begins on a page
     still carrying every consumer reaction row N mounted
     (`lane/settle!` carries the three-point measurement).
  4. **The positive control's verdict**, then the guard's.

  Nothing publishes until the guard has spoken. `run.cjs` prints the
  records regardless — a refused figure is still worth looking at, it is
  simply not reportable — and exits 2.

  ## What is NOT here

  No UIx arm (rf2-2rtt6.4 owns the frontier comparator), no narrow-write
  leg (rf2-2rtt6.3), no heap ladder (rf2-2rtt6.5). All three ride this
  lane's `lane.cljs` and this build id; none of them needs to touch
  `shadow-cljs.edn`."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.p0-reagent-views :as v]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]))

(def ^:private rounds 5)
(def ^:private mount-sampling {:warmup 8 :samples 12})
(def ^:private bulk-sampling {:warmup 8 :samples 12})

;; The control's slack. Generous on purpose: the claim it certifies is
;; THE INSTRUMENT HAS SIGNAL, not THE MODEL IS EXACT. A top-down React
;; re-render is not perfectly linear in element count — there is a root,
;; a commit and a diff walk that do not double — so demanding 2.00 ± 5%
;; would fail an instrument that is working. 25% is still far tighter
;; than the difference between "saw the change" and "saw nothing".
(def ^:private control-slack 0.25)

(defonce ^:private gen (atom 1000))
(defn- next-gen! [] (swap! gen inc))

;; ---------------------------------------------------------------------------
;; Mount arms
;; ---------------------------------------------------------------------------

(defn- floor-mount-arm
  "`scale` is how many times the witness's own page this arm builds, and it
  is ONE fact rather than two: an arm that builds a different page is
  exactly the arm the canonical-DOM gate must exempt, so
  `:parity-exempt?` is DERIVED from it. The two used to be independent
  and the control was the arm that carried `exempt?` without anything
  carrying the size — which is how its doubled page went unchecked."
  [id element-of cells-of & [scale]]
  (let [scale (or scale 1)]
    {:id             id
     :scale          scale
     :parity-exempt? (not= 1 scale)
     :mount          (fn [container props _n]
                       (let [root (react-dom-client/createRoot container)]
                         (.render root (element-of (cells-of props)))
                         root))
     :unmount        (fn [root] (.unmount root))}))

(defn- reagent-mount-arm
  "Reagent's own mount door — `reagent.dom.client/create-root` and
  `render`, which is what a Reagent application calls."
  [id form-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [root (rdc/create-root container)]
                (rdc/render root (form-of props))
                root))
   :unmount (fn [root] (rdc/unmount root))})

(defn- zeros [n] (vec (repeat n 0)))

(defn- input-value
  "The DOM PROPERTY, not the attribute. React installs a controlled
  `value` as a property and mirrors it to the attribute only on first
  render, so a property read is the one that stays true."
  [container sel]
  (some-> (.querySelector container sel) (.-value)))

(defn- verify-m1
  "Both ends of the list AT THE ARM'S OWN SIZE — `n` rows, so the far
  probe is index `n - 1`.

  A page that committed only its head would pass a single-probe check at
  index 0. A CONTROL that rendered only its base prefix would pass a check
  at index 299 — which is what the probe was, fixed at the witness's
  `cells-n` for every arm, so the one arm whose whole purpose is to be
  twice the page was the one arm nothing checked past its first half."
  [n]
  (fn [container]
    (and (= "0" (lane/text-at container 0))
         (= "0" (lane/text-at container (dec n))))))

(defn- verify-m2
  "The first and last field AT THE ARM'S OWN SIZE — `n` fields, so the far
  probe is `#f(n-1)`. Fixed at the witness's `fields-n` it read the SHARED
  PREFIX and could not tell a doubled form from a base one."
  [n]
  (fn [container]
    (and (= (v/field-value 0 0) (input-value container "#f0"))
         (= (v/field-value (dec n) 0)
            (input-value container (str "#f" (dec n)))))))

(defn- expectation
  "What THIS arm's page must contain: the witness's own arithmetic applied
  to the arm's `:scale`, as `{:elements n :verify f}`.

  There is no exemption. `measure-mount!` used to read
  `(if (:parity-exempt? arm) nil elements)` and skip the element count for
  the control, while the probes read indices that exist in the base page —
  so the `:ctl-2x` arm was the ONE arm in the plan whose page was never
  checked, and a control that rendered only its base prefix passed. The
  control's whole claim is that it is exactly twice the page; scaling both
  the count and the far probe is what makes that claim load-bearing rather
  than asserted."
  [{:keys [elements-of verify-of props]} arm]
  (let [n (* (or (:scale arm) 1) (:n props))]
    {:elements (elements-of n)
     :verify   (verify-of n)}))

(defn- base-elements [{:keys [elements-of props]}] (elements-of (:n props)))

(def ^:private mount-witnesses
  [{:id          :M1
    :verify-of   verify-m1
    :elements-of v/m1-elements
    :doc      (str "the 300-boundary sub-reading list — the bulk shape's mount "
                   "counterpart, one subscription read per boundary")
    :props    {:n v/cells-n}
    :control  {:predicted (/ (double (v/m1-elements (* 2 v/cells-n)))
                             (double (v/m1-elements v/cells-n)))
               :basis     (str "element count: " (v/m1-elements (* 2 v/cells-n)) " / "
                               (v/m1-elements v/cells-n))}
    :arms [(floor-mount-arm :floor v/m1-floor (fn [{:keys [n]}] (zeros n)))
           (reagent-mount-arm :reagent-subs
                              (fn [{:keys [n]}] [v/subs-root v/m1-subs n]))
           (reagent-mount-arm :reagent-ratom (fn [{:keys [n]}] [v/m1-ratom n]))
           (floor-mount-arm :ctl-2x v/m1-floor (fn [{:keys [n]}] (zeros (* 2 n))) 2)]}

   {:id          :M2
    :verify-of   verify-m2
    :elements-of v/m2-elements
    ;; ONE mount per sample, and the batching that would have lifted this
    ;; witness clear of Chrome's 100 µs clamp is NOT USED. It was tried —
    ;; eight mounts in one `flushSync` — and THE ARM-ORDER GUARD REFUSED
    ;; THE WHOLE RUN, exit 2: with eight 51-element roots standing in the
    ;; document at once, all four M2 arms read 3.2×–5.4× slower in the last
    ;; third of the run than in the first, ranges DISJOINT, while the
    ;; predecessor factor stayed clean on every one of them. That is the
    ;; recorded position-dominates-adjacency class exactly, and it is a
    ;; property of the batched arm rather than of anything under test — the
    ;; unbatched M1 row, running immediately before it in the same page,
    ;; drifted 1.13×–1.16× with ranges overlapping.
    ;;
    ;; The repair is therefore the ARM, never the tolerance: the batch is
    ;; withdrawn and the resulting clock coarseness is STATED on the row.
    ;; A refused figure and a quantised figure are not the same thing —
    ;; the first may not be published at all, the second may be published
    ;; with its resolution attached, and it is published as DIAGNOSTIC
    ;; rather than as a bar row.
    :per-sample 1
    :clock-note (str "DIAGNOSTIC-GRADE, not a bar row. A 51-element mount takes "
                     "a few tenths of a millisecond — three to six of Chrome's "
                     "100 us performance.now() quanta — so this witness's ratios "
                     "are quantised more coarsely than a 10% effect. Quote M1 and "
                     "the bulk row against the bar; M2 says only that the ordinary "
                     "form shape shows no LARGE reactive-system penalty on mount.")
    :doc      (str "the ordinary 12-field form on subs — the shape most "
                   "applications are made of")
    :props    {:n v/fields-n}
    :control  {:predicted (/ (double (v/m2-elements (* 2 v/fields-n)))
                             (double (v/m2-elements v/fields-n)))
               :basis     (str "element count: " (v/m2-elements (* 2 v/fields-n)) " / "
                               (v/m2-elements v/fields-n))}
    :arms [(floor-mount-arm :floor v/m2-floor (fn [{:keys [n]}] (zeros n)))
           (reagent-mount-arm :reagent-subs
                              (fn [{:keys [n]}] [v/subs-root v/m2-subs n]))
           (reagent-mount-arm :reagent-ratom (fn [{:keys [n]}] [v/m2-ratom n]))
           (floor-mount-arm :ctl-2x v/m2-floor (fn [{:keys [n]}] (zeros (* 2 n))) 2)]}])

;; ---------------------------------------------------------------------------
;; Bulk arms — mounted once, then written to
;; ---------------------------------------------------------------------------

(defn- floor-bulk-arm
  [id n & [scale]]
  (let [state (atom (zeros n))
        rt    (volatile! nil)]
    {:id             id
     :scale          (or scale 1)
     :parity-exempt? (not= 1 (or scale 1))
     :cells          n
     :mount          (fn [container]
                       (let [root (react-dom-client/createRoot container)]
                         (vreset! rt root)
                         (react-dom/flushSync (fn [] (.render root (v/m1-floor @state))))
                         root))
     ;; The render lives in `force!`, not in `write!`, and that is not a
     ;; convenience. `root.render` called outside a React event schedules
     ;; at React's DEFAULT lane, and an EMPTY `flushSync` flushes only the
     ;; SYNC lane — so a floor arm that rendered in `write!` and flushed in
     ;; `force!` would have its commit land outside the measured window
     ;; entirely, and the recorded fault is exactly that: 80 of 320 floor
     ;; samples ending on a cell that still held its old value.
     :write!         (fn [_i val] (reset! state (vec (repeat n val))))
     :force!         (fn [] (react-dom/flushSync
                              (fn [] (.render ^js @rt (v/m1-floor @state)))))
     :unmount        (fn [root] (react-dom/flushSync (fn [] (.unmount root))))}))

(defn- subs-bulk-arm []
  {:id      :reagent-subs
   :cells   v/cells-n
   :mount   (fn [container]
              (let [root (rdc/create-root container)]
                (react-dom/flushSync
                  (fn [] (rdc/render root [v/subs-root v/m1-subs v/cells-n])))
                root))
   :write!  (fn [_i val]
              (frame/replace-app-db! v/subs-frame (v/seed-cells v/cells-n val)))
   ;; `reagent.core/flush` is Reagent's own documented synchronous render
   ;; drain, and it is the SAME drain the ratom arm uses. Both Reagent arms
   ;; therefore differ in exactly one thing — where the value came from —
   ;; which is the whole point of the pair.
   :force!  (fn [] (react-dom/flushSync (fn [] (r/flush))))
   :unmount (fn [root] (react-dom/flushSync (fn [] (rdc/unmount root))))})

(defn- ratom-bulk-arm []
  {:id      :reagent-ratom
   :cells   v/cells-n
   :mount   (fn [container]
              (let [root (rdc/create-root container)]
                (react-dom/flushSync
                  (fn [] (rdc/render root [v/m1-ratom v/cells-n])))
                root))
   :write!  (fn [_i val] (reset! v/ratom-cells (vec (repeat v/cells-n val))))
   :force!  (fn [] (react-dom/flushSync (fn [] (r/flush))))
   :unmount (fn [root] (react-dom/flushSync (fn [] (rdc/unmount root))))})

(defn- make-bulk-arms []
  [(floor-bulk-arm :floor v/cells-n)
   (subs-bulk-arm)
   (ratom-bulk-arm)
   (floor-bulk-arm :ctl-2x (* 2 v/cells-n) 2)])

(defn- mount-bulk-arms!
  "Mount every bulk arm once and CHECK THE PAGE IT BUILT, before a clock is
  read.

  Every bulk arm declares `:cells`, and `v/m1-elements` turns that into an
  element count by written arithmetic — so this one line is what makes the
  bulk `:ctl-2x` arm's doubled page load-bearing. Its far-end read-back
  already derives from `:cells` (`lane/bulk-probes` probes index
  `cells - 1`), so a control shrunk to the base 300 cells would still have
  probed a cell it owns; the count is the check that cannot be satisfied by
  a smaller page. Outside every timed window: the arms are mounted once and
  written to thereafter."
  [arms]
  (mapv (fn [a]
          (let [c        (lane/fresh-container!)
                handle   ((:mount a) c)
                expected (v/m1-elements (:cells a))
                got      (lane/element-count c)]
            (when-not (= expected got)
              (throw (ex-info (str "the bulk arm " (:id a) " built " got
                                   " elements where its own " (:cells a)
                                   " cells make " expected)
                              {:arm (:id a) :expected expected :got got})))
            {:arm a :container c :handle handle}))
        arms))

(defn- release-bulk-arms!
  "Release every bulk arm. A throw is RECORDED, never swallowed.

  `(catch :default _ nil)` was here, and the arm it hid is the one that
  matters most: the bulk arms stand for a whole row, so an unmount that
  threw leaves three hundred subscribing boundaries watching the app-db
  while every later sample is measured on top of them. The caller
  adjudicates with `lane/assert-teardown-clean!` and proves the release
  with `lane/assert-residue!`."
  [mounts]
  (doseq [{:keys [arm handle container]} mounts]
    (try ((:unmount arm) handle)
         (catch :default e
           (lane/teardown-failure! (str "release-bulk-arms! " (:id arm)) e)))
    (.remove container)))

;; ---------------------------------------------------------------------------
;; Records
;; ---------------------------------------------------------------------------

(defn- fixture-common []
  {:reagent-version   "2.0.1"
   :adapter           :rf.adapter/reagent
   :runtime           (lane/runtime-label)
   :denominator       :reagent-subs
   :floor-note        (str "the same DOM built by hand with react/createElement and no "
                           "substrate at all; every ratio is to the floor measured in "
                           "THAT round, because this box drifts further across rounds "
                           "than several of the effects being measured")
   :ratom-note        (str "the :reagent-ratom arm reads an r/cursor over a bare "
                           "reagent.core/atom. It is a LABELLED LOWER BOUND and is never "
                           "the bar: it pays no subscription graph, no query cache and no "
                           "frame. (subs - ratom) is the price of the reactive system")
   :control-note      (str "the :ctl-2x arm is the same floor page at exactly twice the "
                           "boundaries. Its prediction is arithmetic on the element count "
                           "and is written down before the run; it is exempt from the "
                           "canonical-DOM EQUALITY because it builds a different page on "
                           "purpose, and NOT exempt from arithmetic — every one of its "
                           "mounts is checked against its own doubled element count and "
                           "probed at its own far end, so a control that rendered only "
                           "the base prefix fails before any figure is reportable")})

;; ---------------------------------------------------------------------------
;; Mount
;; ---------------------------------------------------------------------------

(defn- measure-mount!
  [{:keys [id doc props arms control per-sample clock-note] :as witness}]
  (let [k        (or per-sample 1)
        t        (lane/tally)
        elements (base-elements witness)
        expect   (into {} (map (juxt :id #(expectation witness %))) arms)
        one!  (fn [arm]
                (let [{:keys [ms mounts]} (lane/mount-batch! arm props k)
                      {exp-elements :elements verify :verify} (get expect (:id arm))]
                  ;; EVERY mount in the batch is read back out of the
                  ;; document, against THIS ARM'S OWN arithmetic. `verify` is
                  ;; per witness because the probe has to exist in the witness
                  ;; — the first cut of this instrument read a `data-i` cell in
                  ;; both witnesses, the form has no such attribute, and the M2
                  ;; row published `400 unverified of 400` while the page was in
                  ;; fact perfectly correct. A read-back that cannot pass is
                  ;; worse than none: it manufactures a defect and would hide a
                  ;; real one behind it. It is per ARM because the control is
                  ;; twice the page and a probe fixed at the witness's size
                  ;; cannot tell it from the base one.
                  (doseq [m mounts]
                    (let [ok? (and (= exp-elements (lane/element-count (:container m)))
                                   (verify (:container m)))]
                      (swap! t (fn [{:keys [of bad]}]
                                 {:of (inc of) :bad (if ok? bad (inc bad))}))))
                  (doseq [m mounts] (lane/release! m))
                  ms))
        {:keys [readings samples]} (lane/rounds! arms mount-sampling rounds one!
                                                 (fn [arm] (str (name id) "/"
                                                                (name (:id arm)))))
        norm  (mapv #(lane/normalise % :floor) readings)
        ratios (mapv :ratio norm)
        summ  (lane/across-rounds ratios)
        ctl   (lane/control-verdict (:predicted control)
                                    (select-keys (:ctl-2x summ) [:min :max :mean])
                                    control-slack)
        record
        {:benchmark        (keyword "hicasso.P0" (str "mount-" (name id)))
         :doc              doc
         :bead             "rf2-2rtt6.2"
         :grade            (if clock-note :diagnostic :bar)
         :clock-note       clock-note
         :fixture          (merge (fixture-common)
                                  {:witness  id
                                   :props    props
                                   :elements elements
                                   :arms     (mapv :id arms)
                                   ;; PER ARM, so a reader can see that the
                                   ;; control was held to 1,801/99 and not to
                                   ;; the base page's 901/51.
                                   :expected-elements
                                   (into {} (map (fn [[k' e]] [k' (:elements e)])) expect)
                                   :mounts-per-sample k
                                   :measurement-method
                                   (str "a SAMPLE is " k " mount(s) inside ONE "
                                        "react-dom/flushSync window — Chrome clamps "
                                        "performance.now() to 100 us and a 51-element "
                                        "witness sits within a handful of quanta of it, so "
                                        "the small witness batches to lift every arm clear "
                                        "of the clamp and the per-mount figure is the "
                                        "sample divided by " k ". Wall time across "
                                        "react-dom/flushSync around each "
                                        "arm's own mount door, into fresh containers "
                                        "attached BEFORE the window; arms interleaved at "
                                        "the SAMPLE level with the order rotating AND "
                                        "REFLECTING on the sample index, so no arm always "
                                        "follows the same neighbour (a bare rotation does "
                                        "not vary adjacency at all); " rounds " rounds of "
                                        (:warmup mount-sampling) " warm-up + "
                                        (:samples mount-sampling) " samples per arm per "
                                        "round; every measured mount read back out of the "
                                        "DOM against THAT ARM'S OWN arithmetic — its exact "
                                        "element count, and both ends of the page it "
                                        "claims to build, so the doubled control is "
                                        "checked at its own far end and not at the base "
                                        "page's")})
         :sampling         mount-sampling
         :verification     (lane/tally-value t)
         :positive-control (assoc ctl :basis (:basis control))
         :per-round        {:p50 (mapv :p50 norm) :ratio ratios}
         :ratio-to-floor   summ
         :bar-row          (lane/ratio-between ratios :reagent-subs :floor)
         :reactive-leg     (lane/ratio-between ratios :reagent-subs :reagent-ratom)
         :status           :evidence}]
    (lane/record! (str "mount-" (name id)) record)
    (lane/assert-verified! t (str "mount row " (name id)))
    {:id id :record record :samples samples :control ctl}))

;; ---------------------------------------------------------------------------
;; Bulk
;; ---------------------------------------------------------------------------

(defn- bulk-write!
  "One broad write on one arm, timed as one sample and verified at the DOM
  inside its own window.

  A broad write changes every cell, so the probe rotates with the value
  AND the far end of the grid is checked: a stale page can still carry one
  fresh cell from the previous write, and a single fixed probe would
  accept it. The rule is `lane/bulk-probes`, not a literal here — HD-008's
  bulk row probed cell 0 alone while this one probed three (rf2-f5roa)."
  [t mnt]
  (let [n   (:cells (:arm mnt))
        val (next-gen!)]
    (lane/verified-write! t mnt :all val (lane/bulk-probes val n))))

(defn- seed-bulk! [mounts t]
  (lane/chain nil mounts
              (fn [_ mnt] (-> (lane/verified-write! t mnt :all 0
                                                    [0 (dec (:cells (:arm mnt)))])
                              (.then (fn [_] nil))))))

(defn- bulk-round!
  "One round. The guard's samples are banked AT THE SAMPLE, inside the
  interleave — banking them per arm after the round would record every
  arm's readings as consecutive and hand the guard a `:position` factor
  that says only what the loop's shape was."
  [mounts t legs coll]
  (let [k     (count mounts)
        total (+ (:warmup bulk-sampling) (:samples bulk-sampling))
        acc0  (zipmap (map #(:id (:arm %)) mounts) (repeat []))]
    (lane/chain {:readings acc0}
                (for [s (range total) j (lane/slot-order k s)] [s j])
                (fn [acc [s j]]
                  (let [mnt (nth mounts j)
                        id  (:id (:arm mnt))]
                    (-> (bulk-write! t mnt)
                        (.then (fn [{:keys [ms write-ms gap-ms force-ms]}]
                                 (if (>= s (:warmup bulk-sampling))
                                   (do (lane/collect! coll (str "bulk/" (name id)) ms)
                                       (swap! legs update id (fnil conj [])
                                              {:write write-ms :gap gap-ms :force force-ms})
                                       (update-in acc [:readings id] conj ms))
                                   acc)))))))))

(defn- leg-summary [legs]
  (into {}
        (map (fn [[id xs]]
               [id {:write-ms (:p50 (lane/summarise (map :write xs)))
                    :gap-ms   (:p50 (lane/summarise (map :gap xs)))
                    :force-ms (:p50 (lane/summarise (map :force xs)))}]))
        legs))

(defn- measure-bulk!
  [mounts]
  (let [t    (lane/tally)
        legs (atom {})
        coll (lane/sample-collector)]
    (-> (lane/chain [] (range rounds)
                    (fn [acc _]
                      (-> (seed-bulk! mounts t)
                          (.then (fn [_] (bulk-round! mounts t legs coll)))
                          (.then (fn [rd] (conj acc (:readings rd)))))))
        (.then
          (fn [readings]
            (let [norm   (mapv #(lane/normalise % :floor) readings)
                  ratios (mapv :ratio norm)
                  summ   (lane/across-rounds ratios)
                  predicted (/ (double (v/m1-elements (* 2 v/cells-n)))
                               (double (v/m1-elements v/cells-n)))
                  ctl    (lane/control-verdict
                           predicted
                           (select-keys (:ctl-2x summ) [:min :max :mean])
                           control-slack)
                  record
                  {:benchmark        :hicasso.P0/bulk-broad
                   :doc              (str "one commit that all " v/cells-n
                                          " sub-reading boundaries read — the "
                                          "make-or-break row")
                   :bead             "rf2-2rtt6.2"
                   :fixture          (merge (fixture-common)
                                            {:cells v/cells-n
                                             :arms  (mapv #(:id (:arm %)) mounts)
                                             :writes-per-sample 1
                                             :measurement-method
                                             (str "per sample: t0; the arm's own state "
                                                  "install; ONE microtask yield; the arm's "
                                                  "own synchronous drain (an empty "
                                                  "react-dom/flushSync for the floor arms, "
                                                  "reagent.core/flush inside one for both "
                                                  "Reagent arms); t1 — then the written "
                                                  "cells are read back out of the DOM and "
                                                  "the sample is counted UNVERIFIED if they "
                                                  "do not hold the written value. Three "
                                                  "probes per write: a rotating cell, the "
                                                  "far end of the grid, and cell 0. Arms "
                                                  "interleaved at the sample level, order "
                                                  "rotating AND REFLECTING on the sample "
                                                  "index; " rounds " rounds of "
                                                  (:warmup bulk-sampling) " warm-up + "
                                                  (:samples bulk-sampling) " samples. The "
                                                  "BULK floor is a plain top-down React "
                                                  "re-render and is NOT a lower bound — a "
                                                  "fine-grained substrate can beat it")})
                   :sampling         bulk-sampling
                   :verification     (lane/tally-value t)
                   :positive-control (assoc ctl :basis
                                            (str "element count: "
                                                 (v/m1-elements (* 2 v/cells-n)) " / "
                                                 (v/m1-elements v/cells-n)))
                   :per-round        {:p50 (mapv :p50 norm) :ratio ratios}
                   :legs             (leg-summary @legs)
                   :ratio-to-floor   summ
                   :bar-row          (lane/ratio-between ratios :reagent-subs :floor)
                   :reactive-leg     (lane/ratio-between ratios :reagent-subs :reagent-ratom)
                   :status           :evidence}]
              (lane/record! "bulk-broad" record)
              (lane/assert-verified! t "the bulk row")
              {:record record :samples (:samples @coll) :control ctl}))))))

;; ---------------------------------------------------------------------------
;; Parity
;; ---------------------------------------------------------------------------

(defn- parity-problems
  "Canonical-DOM equality across the judged arms, and the element count of
  EVERY arm — the control included — against its own arithmetic.

  The count used to be checked only over `lane/parity`'s `counts` map,
  which excludes the parity-exempt arms by design, so the one arm building
  a deliberately different page had that page checked nowhere. Exempt from
  the EQUALITY is not exempt from arithmetic: the control is exempt
  because its page differs, and the size it differs BY is the claim it
  exists to make."
  []
  (reduce
    (fn [problems {:keys [id props arms] :as witness}]
      (let [{:keys [mounts agree? disagree]} (lane/parity arms props)
            wrong (into {}
                        (comp (map (fn [{:keys [arm container]}]
                                     [(:id arm)
                                      {:expected (:elements (expectation witness arm))
                                       :got      (lane/element-count container)}]))
                              (remove (fn [[_ {:keys [expected got]}]] (= expected got))))
                        mounts)]
        (try
          (cond-> problems
            (not agree?)
            (conj {:witness id :problem :canonical-dom-disagreement :arms disagree})

            (seq wrong)
            (conj {:witness id :problem :element-count :arms wrong}))
          (finally (doseq [m mounts] (lane/release! m))))))
    []
    mount-witnesses))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- settled!
  "Let the substrate's disposal queue run, then adjudicate the two things a
  released row owes: that nothing threw on the way out, and that the
  release actually happened. Answers a promise of the residue reading.

  Between rows, never inside a window. A row measured on top of the
  previous row's un-released boundaries is the same class of fault as a
  write that never reached the page, and this is where it is caught — see
  [[lane/settle!]] for why the yield is load-bearing rather than a
  courtesy to the assertion."
  [baseline after]
  (-> (lane/settle!)
      (.then (fn [_]
               (lane/assert-teardown-clean! after)
               (lane/assert-residue! baseline v/subs-frame after)))))

(defn- finish!
  [{:keys [samples controls]}]
  (let [v (lane/guard! samples "Hicasso P0 — Reagent-on-subs")]
    (lane/record! "arm-order-guard"
                  {:tolerance (:tolerance v)
                   :contaminated? (:contaminated? v)
                   :unchecked? (:unchecked? v)
                   :refuse? (:refuse? v)
                   :arms (:arms v)})
    (when (:refuse? v) (set! (.-HICASSO_GUARD_REFUSED js/window) true))
    (when (some (complement :ok?) controls)
      (set! (.-HICASSO_CONTROL_FAILED js/window) true)))
  (lane/done!))

(defn ^:export -main
  []
  (try
    (rf/init! reagent-adapter/adapter)
    (rf/make-frame {:id v/subs-frame})
    (frame/replace-app-db! v/subs-frame (v/seed-cells v/cells-n 0))
    (reset! v/ratom-cells (:cells (v/seed-cells v/cells-n 0)))
    (lane/leave-act-environment!)

    (if-not (lane/self-test!)
      (do (lane/fail! (str "the arm-order guard's SELF-TEST failed — this copy of the "
                           "rule no longer behaves like the one the .cjs drivers use, "
                           "so nothing was measured"))
          (lane/done!))
      ;; The residue this run must return to after every row. Taken before
      ;; the first mount of the run, so it is the empty-page, empty-cache
      ;; reading and not a reading of whatever the previous row left.
      (let [baseline (lane/residue v/subs-frame)
            problems (parity-problems)]
        (lane/record! "parity" {:problems problems :ok? (empty? problems)})
        (lane/record! "residue-baseline" baseline)
        (if (seq problems)
          (do (lane/fail! (str "the arms do not build the same page under :advanced — "
                               (pr-str problems)))
              (lane/done!))
          ;; The rows are CHAINED rather than folded, so a settle point sits
          ;; between every pair of them. See `lane/settle!`: a mount row is
          ;; wholly synchronous, Reagent's disposals are on a macrotask
          ;; queue, and without the yield row N+1 begins on a page still
          ;; carrying every consumer reaction row N mounted.
          (-> (settled! baseline "parity")
              (.then (fn [_]
                       (lane/chain []
                                   mount-witnesses
                                   (fn [acc w]
                                     (let [r (measure-mount! w)]
                                       (-> (settled! baseline
                                                     (str "mount row " (name (:id w))))
                                           (.then (fn [_] (conj acc r)))))))))
              (.then (fn [mounted]
                       (let [bulk (mount-bulk-arms! (make-bulk-arms))]
                         (-> (measure-bulk! bulk)
                             (.then (fn [b]
                                      (release-bulk-arms! bulk)
                                      (-> (settled! baseline "the bulk row")
                                          (.then (fn [res]
                                                   (lane/record! "residue-after-bulk" res)
                                                   (finish!
                                                     {:samples
                                                      (into (vec (mapcat :samples mounted))
                                                            (:samples b))
                                                      :controls
                                                      (conj (mapv :control mounted)
                                                            (:control b))})
                                                   nil)))))
                             (.catch (fn [e]
                                       (release-bulk-arms! bulk)
                                       (throw e)))))))
              (.catch (fn [e]
                        (lane/fail! (str "the run rejected: " e))
                        (lane/done!)))))))
    (catch :default e
      (lane/fail! (str "the run threw: " e))
      (lane/done!))))

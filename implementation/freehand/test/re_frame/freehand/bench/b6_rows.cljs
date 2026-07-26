(ns re-frame.freehand.bench.b6-rows
  "B6's rows, as data and functions — the arms, the witness table, and the
  two measurements, with every `cljs.test` assertion left to the caller.

  This split exists for one reason and it is a methodological one. B6's
  numbers must be taken from an `:advanced` bundle with `goog.DEBUG
  false` — the artefact a consumer ships — because the Spec 009
  instrumentation seam, schema validation and trace emission are all
  `goog.DEBUG`-gated and all of them sit on the update path. Reagent has
  no counterpart, so a development build systematically penalises
  Freehand and would publish a number no user ever experiences. But
  `:advanced` cannot compile shadow's `:browser-test` target
  (`cljs-test-display`'s `goog.define`s collide), so the production
  reading has to come from a plain `:browser` entry point instead.

  So the rows live here, callable from both: from
  [[re-frame.freehand.bench.b6-mount-dom-cljs-test]] and
  [[re-frame.freehand.bench.b6-update-dom-cljs-test]], which gate the
  DETERMINISTIC half in the ordinary browser suite, and from
  [[re-frame.freehand.bench.b6-prod-app]], which takes the numbers under
  the compilation a user actually ships.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b6-floor :as floor]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.b6-reagent :as rg]
            [re-frame.freehand.bench.b6-uix :as ux]
            [re-frame.freehand.bench.b6-witnesses :as fh]
            [re-frame.freehand.bench.b6-witnesses-compiled :as fhc]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]
            [uix.core :refer [$]]
            [uix.dom :as uix-dom]))

;; ---------------------------------------------------------------------------
;; re-frame registrations for the update row
;; ---------------------------------------------------------------------------

(def cells-n 300)

(rf/reg-sub :b6/cell (fn [db [_ i]] (get-in db [:cells i])))
(rf/reg-event :b6/seed (fn [_ [_ n]] {:db {:cells (vec (repeat n 0))}}))

;; ===========================================================================
;; MOUNT
;; ===========================================================================

(def w1-rows 300)
(def w1-small-rows 6)
(def w2-n 300)
(def w3-fields 12)

(defn w1-elements
  "Three for the skeleton — the section, the heading and the list — then
  four per row: the row, its image, its label and its number. Arithmetic,
  so the gate is against a written expectation rather than against
  whatever the mount happened to produce."
  [rows]
  (+ 3 (* 4 rows)))

(defn w2-elements [n] (+ 1 n))

(defn w3-elements
  "Three for the skeleton — the form, the fieldset and the submit button —
  then four per field: the wrapper, the label, the input and the error
  line."
  [fields]
  (+ 3 (* 4 fields)))

(defn- react-root-arm
  "An arm over a bare `react-dom/client` root — the floor and the UIx arm
  both mount this way, because neither has a mount door of its own."
  [id element-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [r (react-dom-client/createRoot container)]
                (.render r (element-of props))
                r))
   :unmount (fn [r] (.unmount r))})

(defn- freehand-mount-arm [id view]
  {:id      id
   :mount   (fn [container props n]
              (v/mount [view props] container
                       {:disambiguator (keyword "b6" (str (name id) "-" n))}))
   :unmount (fn [mounted] (v/unmount! mounted))})

(defn- reagent-mount-arm
  "Reagent's own mount door — `reagent.dom.client/create-root` and
  `render`, which is what a Reagent application calls."
  [id form-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [r (rdc/create-root container)]
                (rdc/render r (form-of props))
                r))
   :unmount (fn [r] (rdc/unmount r))})

(defn- uix-mount-arm [id element-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [r (uix-dom/create-root container)]
                (uix-dom/render-root (element-of props) r)
                r))
   :unmount (fn [r] (uix-dom/unmount-root r))})

(def mount-witnesses
  "The three mount witnesses, each with its arms, its fixture and its
  written element arithmetic. A table, because the method is one method
  and three near-copies of a driver would be three places for it to
  drift."
  [{:id             :W1
    :doc            "a large template — 300 rows under one boundary each, rich attributes"
    :headline?      true
    :props          {:rows w1-rows}
    :small          {:rows w1-small-rows}
    :elements       (w1-elements w1-rows)
    :small-elements (w1-elements w1-small-rows)
    :arms [(react-root-arm    :floor (fn [{:keys [rows]}] (floor/w1 rows)))
           (freehand-mount-arm :freehand-interpreted fh/w1)
           (freehand-mount-arm :freehand-compiled    fhc/w1)
           (reagent-mount-arm  :reagent (fn [{:keys [rows]}] [rg/w1 rows]))
           (uix-mount-arm      :uix     (fn [{:keys [rows]}] ($ ux/w1 {:rows rows})))]}

   {:id             :W2
    :doc            "300 sub-free leaf boundaries — the ELISION-MAXIMISING shape"
    :headline?      false
    :warning        (str "Freehand's compiled tier proves these bodies sub-free and drops 300 "
                         "ViewCells. Reagent and UIx have no elision concept and cannot. This "
                         "row therefore OVERSTATES Freehand's advantage and must not be quoted "
                         "as the headline.")
    :props          {:n w2-n}
    :small          {:n 6}
    :elements       (w2-elements w2-n)
    :small-elements (w2-elements 6)
    :arms [(react-root-arm    :floor (fn [{:keys [n]}] (floor/w2 n)))
           (freehand-mount-arm :freehand-interpreted fh/w2)
           (freehand-mount-arm :freehand-compiled    fhc/w2)
           (reagent-mount-arm  :reagent (fn [{:keys [n]}] [rg/w2 n]))
           (uix-mount-arm      :uix     (fn [{:keys [n]}] ($ ux/w2 {:n n})))]}

   {:id             :W3
    :doc            (str "an ordinary 12-field form with controlled inputs — the shape most "
                         "applications are made of")
    :headline?      true
    :props          {:fields w3-fields}
    :small          {:fields 3}
    :elements       (w3-elements w3-fields)
    :small-elements (w3-elements 3)
    :arms [(react-root-arm    :floor (fn [{:keys [fields]}] (floor/w3 fields)))
           (freehand-mount-arm :freehand-interpreted fh/w3)
           (freehand-mount-arm :freehand-compiled    fhc/w3)
           (reagent-mount-arm  :reagent (fn [{:keys [fields]}] [rg/w3 fields]))
           (uix-mount-arm      :uix     (fn [{:keys [fields]}] ($ ux/w3 {:fields fields})))]}])

(defn mount-parity
  "Mount every arm of `witness` at `props` at once and answer the parity
  result. LEAVES THE MOUNTS STANDING — the caller releases them, so it
  can read the pages before they go."
  [witness props]
  (h/parity (:arms witness) props))

(defn measure-mount!
  "Measure one witness and publish its record. Answers
  `{:id … :summary … :per-round …}`."
  [{:keys [id doc props arms headline? warning elements]} rounds sampling]
  (let [raw     (mapv (fn [_] (h/round! arms props sampling)) (range rounds))
        norm    (mapv #(h/normalise % :floor) raw)
        summary (h/across-rounds (mapv :ratio norm))
        record
        {:benchmark      (keyword "B6" (str "mount-" (name id)))
         :doc            doc
         :headline?      headline?
         :warning        warning
         :revision       (prov/detect-revision)
         :build          (prov/detect-build)
         :host           (prov/detect-host)
         :fixture        {:witness         id
                          :props           props
                          :elements        elements
                          :arms            (mapv :id arms)
                          :reagent-version "2.0.1"
                          :uix-version     "1.4.4"
                          :measurement-method
                          (str "wall time across react-dom/flushSync around each arm's own "
                               "mount door, into a fresh container attached before the window; "
                               "arms interleaved at the SAMPLE level with the order rotating on "
                               "the sample index; " rounds " rounds of " (:warmup sampling)
                               " warmup + " (:samples sampling) " samples per arm per round; "
                               "every figure a ratio to the FLOOR measured in that same round, "
                               "because this workstation drifts further across rounds than "
                               "several of the effects being measured")}
         :sampling       sampling
         :baseline       {:kind      :cross-substrate
                          :reference {:arm  :floor
                                      :note (str "the same DOM built by hand with "
                                                 "react/createElement and no substrate at all")}}
         :per-round      {:p50 (mapv :p50 norm) :ratio (mapv :ratio norm)}
         :ratio-to-floor summary
         :status         :evidence}]
    (h/publish! (str "mount / " (name id)) record)
    {:id id :summary summary :norm norm :record record}))

;; ===========================================================================
;; UPDATE
;; ===========================================================================

(defonce ^:private gen-seq (atom 1000))
(defn next-gen! [] (swap! gen-seq inc))

(defn- floor-update-arm
  "The floor's `force!` renders INSIDE the `flushSync`, and that is not a
  convenience. `root.render` called outside a React event schedules at
  React's default lane, and an EMPTY `flushSync` flushes only the sync
  lane — so a floor arm that rendered in `write!` and flushed in `force!`
  would have its commit land outside the measured window entirely. The
  DOM verification caught exactly that: 80 of 320 floor samples ended
  with the cell still holding its old value. Freehand and Reagent are
  unaffected, because a `useSyncExternalStore` notification and a
  `reagent.core/flush` both put their work on the sync lane."
  []
  (let [state (atom (vec (repeat cells-n 0)))
        rt    (volatile! nil)]
    {:id      :floor
     :mount   (fn [container]
                (let [r (react-dom-client/createRoot container)]
                  (vreset! rt r)
                  (react-dom/flushSync (fn [] (.render r (floor/u-grid @state))))
                  r))
     :write!  (fn [i val]
                (if (= i :all)
                  (reset! state (vec (repeat cells-n val)))
                  (swap! state assoc i val)))
     :force!  (fn [] (react-dom/flushSync
                       (fn [] (.render ^js @rt (floor/u-grid @state)))))
     :unmount (fn [r] (react-dom/flushSync (fn [] (.unmount r))))}))

(defn freehand-update-arm
  "Public so the PROFILING entry can build an ablation arm through the
  SAME constructor the published rows use — a second, near-copy arm
  builder would be a second place for the measured window to drift."
  [id view]
  (let [fid (keyword "b6-update" (name id))]
    {:id      id
     :mount   (fn [container]
                (react-dom/flushSync
                  (fn [] (v/mount [view {:n cells-n}] container
                                  {:frame         {:id fid :initial-events [[:b6/seed cells-n]]}
                                   :disambiguator (keyword "b6u" (name id))}))))
     :write!  (fn [i val]
                (if (= i :all)
                  (frame/replace-app-db! fid {:cells (vec (repeat cells-n val))})
                  (frame/replace-app-db!
                    fid (update (frame/frame-app-db-value fid) :cells assoc i val))))
     ;; Freehand's notification has already been queued by the write; the
     ;; empty flushSync is what makes React commit it in this window rather
     ;; than on its own scheduler a couple of milliseconds later.
     :force!  (fn [] (react-dom/flushSync (fn [] nil)))
     :unmount (fn [mounted] (react-dom/flushSync (fn [] (v/unmount! mounted))))}))

(defn- reagent-update-arm []
  {:id      :reagent
   :mount   (fn [container]
              (reset! rg/cells (vec (repeat cells-n 0)))
              (let [rt (rdc/create-root container)]
                (react-dom/flushSync (fn [] (rdc/render rt [rg/u-grid cells-n])))
                rt))
   :write!  (fn [i val]
              (if (= i :all)
                (reset! rg/cells (vec (repeat cells-n val)))
                (swap! rg/cells assoc i val)))
   ;; `reagent.core/flush` is Reagent's own documented synchronous render
   ;; drain — the counterpart of the empty `flushSync` the other arms use.
   :force!  (fn [] (react-dom/flushSync (fn [] (r/flush))))
   :unmount (fn [rt] (react-dom/flushSync (fn [] (rdc/unmount rt))))})

(defn make-update-arms []
  [(floor-update-arm)
   (freehand-update-arm :freehand-interpreted fh/u-grid)
   (freehand-update-arm :freehand-compiled fhc/u-grid)
   (reagent-update-arm)])

(defn cell-text
  [container i]
  (some-> (.querySelector container (str "[data-i=\"" i "\"]")) (.-textContent)))

(defn mount-update-arms!
  [arms]
  (mapv (fn [a]
          (let [c (js/document.createElement "div")]
            (.appendChild js/document.body c)
            {:arm a :container c :handle ((:mount a) c)}))
        arms))

(defn release-update-arms!
  [mounts]
  (doseq [{:keys [arm handle container]} mounts]
    (try ((:unmount arm) handle) (catch :default _ nil))
    (.remove container)))

(defn timed-write!
  "Write, yield ONE microtask, force the arm's own synchronous drain, stop
  the clock — then VERIFY at the DOM that the cell holds what was written.
  Answers a promise of `{:ms … :write-ms … :force-ms … :ok? …}`.

  The window is SPLIT because the split answers the comparison's biggest
  fairness question. This row pits Freehand reading re-frame `app-db`
  against Reagent reading a bare `reagent.core/atom`, which is each
  substrate's own idiom but is NOT the same amount of framework: a
  Reagent application that used re-frame subscriptions would pay a
  re-frame write and a re-frame signal graph too. `:write-ms` is exactly
  that leg — `frame/replace-app-db!` and the subscription recompute it
  triggers, before React is involved — so a reader can see how much of
  the gap a re-frame-shaped Reagent app would also have paid, instead of
  being asked to take a sentence for it."
  [{:keys [arm container]} i val]
  (let [t0 (m/now-ms)]
    ((:write! arm) i val)
    (let [t1 (m/now-ms)]
      (-> (js/Promise.resolve nil)
          (.then (fn [_]
                   (let [t2 (m/now-ms)]
                     ((:force! arm))
                     (let [t3    (m/now-ms)
                           probe (if (= i :all) 0 i)]
                       ;; `:ms` is the WHOLE window, microtask boundary
                       ;; included — the elapsed time to get this write onto
                       ;; the page, which is the figure the ratios use. The
                       ;; three legs are published beside it so the boundary
                       ;; is visible rather than assumed empty: whatever
                       ;; Freehand's notification does, it does it there.
                       {:ms       (- t3 t0)
                        :write-ms (- t1 t0)
                        :gap-ms   (- t2 t1)
                        :force-ms (- t3 t2)
                        :ok?      (= (str val) (cell-text container probe))
                        :ok2?     (or (not= i :all)
                                      (= (str val) (cell-text container (dec cells-n))))}))))))))

(defn chain
  "Fold `xs` into a serial promise chain, threading an accumulator."
  [init xs f]
  (reduce (fn [p x] (.then p (fn [acc] (f acc x)))) (js/Promise.resolve init) xs))

(def writes-per-sample
  "How many writes one SAMPLE of each row contains.

  Chrome clamps `performance.now()` to 100 µs. A broad write costs every
  arm well above that, so one write is a sample. A NARROW write does not:
  measured one at a time, Reagent and the floor both returned exactly
  0.1 ms — the quantum itself — which is the predecessor report's null
  result wearing a different hat. Twenty writes to a sample lifts every
  arm clear of the clamp, and the per-write figure is the sample divided
  by twenty."
  {:broad 1 :narrow 20})

(defn- n-writes!
  "`n` writes on one arm, timed as one sample. Every write carries a fresh
  value and every one is verified at the DOM."
  [mnt kind n]
  (chain {:ms 0 :write-ms 0 :gap-ms 0 :force-ms 0 :bad 0} (range n)
         (fn [acc _]
           (let [val (next-gen!)
                 i   (if (= kind :broad) :all (mod val cells-n))]
             (-> (timed-write! mnt i val)
                 (.then (fn [{:keys [ms write-ms gap-ms force-ms ok? ok2?]}]
                          (cond-> (-> acc
                                      (update :ms + ms)
                                      (update :write-ms + write-ms)
                                      (update :gap-ms + gap-ms)
                                      (update :force-ms + force-ms))
                            (not (and ok? ok2?)) (update :bad inc)))))))))

(defn- update-round!
  [mounts kind sampling]
  (let [k     (count mounts)
        total (+ (:warmup sampling) (:samples sampling))
        n     (get writes-per-sample kind)]
    (chain {:readings (zipmap (map #(:id (:arm %)) mounts) (repeat []))
            :legs     (zipmap (map #(:id (:arm %)) mounts) (repeat []))
            :bad      0}
           (for [s (range total) j (range k)] [s j])
           (fn [acc [s j]]
             (let [mnt (nth mounts (mod (+ j s) k))
                   id  (:id (:arm mnt))]
               (-> (n-writes! mnt kind n)
                   (.then (fn [{:keys [ms write-ms gap-ms force-ms bad]}]
                            (cond-> (update acc :bad + bad)
                              (>= s (:warmup sampling))
                              (-> (update-in [:readings id] conj ms)
                                  (update-in [:legs id] conj
                                             {:write write-ms
                                              :gap   gap-ms
                                              :force force-ms})))))))))))

(defn- leg-summary
  "Per-arm p50 of each leg of the window, in milliseconds per SAMPLE."
  [legs]
  (into {}
        (map (fn [[id xs]]
               [id (when (seq xs)
                     {:write-ms (:p50 (m/summarise (map :write xs)))
                      :gap-ms   (:p50 (m/summarise (map :gap xs)))
                      :force-ms (:p50 (m/summarise (map :force xs)))})]))
        legs))

;; THE MICROTASK YIELD IS PRICED IN SITU, BY THE ARMS THAT DO NOT NEED IT.
;;
;; Every measured write pays one microtask yield, because the slowest arm
;; requires one, so a reader is owed the size of that constant. The
;; measurement is `:gap-ms` in the `:legs` decomposition, and the control
;; is that the floor and Reagent arms — which commit without it — report
;; `:gap-ms 0.0` in every round of both rows. The yield costs nothing;
;; what shows up in the Freehand arms' gap is Freehand's own notification
;; callback running there, and it scales with the write exactly as it
;; should (≈0.35 ms when a broad write moves 300 subscriptions, ≈0.01 ms
;; when a narrow one moves one).
;;
;; A first attempt priced it instead by chaining 2,000 bare
;; `js/Promise.resolve`s and dividing, which reported ≈1 ms a hop — larger
;; than an entire measured Reagent write, and therefore impossible. That
;; control was measuring the construction of a 2,000-deep promise chain,
;; not a microtask. It was removed rather than published with a caveat.

(defn seed-update-arms! [mounts]
  (chain nil mounts (fn [_ mnt] (-> (timed-write! mnt :all 0) (.then (fn [_] nil))))))

(defn canon-of [mounts] (mapv (fn [m] (h/canonical (:container m))) mounts))

(defn write-and-compare!
  "Write the same broad value into every arm and answer
  `{:ids … :before [..] :after [..]}` — the caller decides what to assert."
  [mounts]
  (let [before (canon-of mounts)
        val    (next-gen!)]
    (-> (chain nil mounts (fn [_ mnt] (-> (timed-write! mnt :all val) (.then (fn [_] nil)))))
        (.then (fn [_] {:ids    (mapv #(:id (:arm %)) mounts)
                        :before before
                        :after  (canon-of mounts)})))))

(defn measure-update!
  "Run the `kind` update row over already-mounted `mounts`. Answers a
  promise of `{:summary … :norm … :bad … :record …}` and publishes the
  record."
  [mounts kind doc rounds sampling]
  (-> (chain [] (range rounds)
             (fn [acc _]
               (-> (seed-update-arms! mounts)
                   (.then (fn [_] (update-round! mounts kind sampling)))
                   (.then (fn [rd] (conj acc rd))))))
      (.then (fn [rds]
               (let [bad  (reduce + (map :bad rds))
                     norm (mapv #(h/normalise (:readings %) :floor) rds)
                     summ (h/across-rounds (mapv :ratio norm))
                     record
                     {:benchmark      (keyword "B6" (str "update-" (name kind)))
                      :doc            doc
                      :revision       (prov/detect-revision)
                      :build          (prov/detect-build)
                      :host           (prov/detect-host)
                      :fixture        {:cells             cells-n
                                       :kind              kind
                                       :arms              (mapv #(:id (:arm %)) mounts)
                                       :adapter           :rf.adapter/uix
                                       :reagent-version   "2.0.1"
                                       :unverified-writes bad
                                       :writes-per-sample (get writes-per-sample kind)
                                       :measurement-method
                                       (str "a SAMPLE is " (get writes-per-sample kind)
                                            " write(s); Chrome clamps performance.now() to "
                                            "100 us and a single narrow write sits on that "
                                            "clamp for the floor and Reagent alike, so the "
                                            "narrow row batches to lift every arm clear of it. "
                                            "Per write: t0; the arm's own state install; ONE "
                                            "microtask yield; the arm's own synchronous drain "
                                            "(empty react-dom/flushSync for the floor and both "
                                            "Freehand arms, reagent.core/flush inside one for "
                                            "Reagent); t1 — then the written cell is read back "
                                            "out of the DOM and the sample is rejected if it "
                                            "does not hold the written value. The microtask is "
                                            "STRUCTURAL: a mounted Freehand v/sub does not "
                                            "repaint inside flushSync, its notification rides a "
                                            "microtask, and a first pass that timed inside "
                                            "flushSync measured Freehand writes that never "
                                            "reached the DOM at all. Arms interleaved at the "
                                            "sample level, order rotating on the sample index; "
                                            rounds " rounds of " (:warmup sampling)
                                            " warmup + " (:samples sampling) " samples; every "
                                            "figure a ratio to the floor measured in that same "
                                            "round. The UPDATE floor is a plain top-down React "
                                            "re-render and is NOT a lower bound")}
                      :sampling       sampling
                      :baseline       {:kind      :cross-substrate
                                       :reference {:arm  :floor
                                                   :note "plain top-down React re-render, no substrate"}}
                      :per-round      {:p50 (mapv :p50 norm) :ratio (mapv :ratio norm)}
                      :legs           (mapv #(leg-summary (:legs %)) rds)
                      :ratio-to-floor summ
                      :status         :evidence}]
                 (h/publish! (str "update / " (name kind)) record)
                 {:summary summ :norm norm :bad bad :rounds rounds
                  :samples-per-round (* (count mounts) (+ (:warmup sampling) (:samples sampling)))
                  :record record})))))

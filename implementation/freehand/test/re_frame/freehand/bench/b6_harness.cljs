(ns re-frame.freehand.bench.b6-harness
  "B6's instrument — the shared plumbing every cross-substrate row runs
  on: canonical DOM, one timed commit, interleaved rounds, and floor
  normalisation.

  Kept apart from the rows that use it so the METHOD is one thing a
  reader can check once, rather than three near-copies whose differences
  they would have to diff.

  ## What a reading is

  `react-dom/flushSync` brackets the commit, so the measured window
  contains the substrate's element construction AND React's render,
  commit and DOM mutation, with nothing scheduled out of it. Nothing runs
  in an `act` environment: `act` diverts work to its own queue, which is
  not what a browser does, and the whole point of the window is that it
  is the browser's.

  ## Interleaving, and why at the SAMPLE level

  A box with six other agents on it drifts, and it drifts on a timescale
  that runs all of one arm and then all of another straight into a
  systematic error. So every sample index mounts every arm, in an order
  that ROTATES with the sample index — no arm is always first into a cold
  cache, and no arm is always last into a loaded one.

  ## Every figure is a ratio to the floor measured in the same round

  Absolute milliseconds are published, because a 3× ratio on a 0.2 ms
  operation is not a product decision and a reader is owed the size of
  the thing. But no cross-round absolute claim is made: the calibrator is
  the floor arm, which is the same work in every round and contains no
  substrate at all. Rounds are reported as a RANGE, and a range that
  straddles 1.0 is reported as indistinguishable rather than as a
  winner.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require ["react-dom" :as react-dom]
            [clojure.string :as str]
            [re-frame.freehand.bench.measure :as m]))

;; ---------------------------------------------------------------------------
;; Canonical DOM — the fairness gate
;; ---------------------------------------------------------------------------

(defn canonical
  "Serialise `node`'s subtree with every element's attribute names SORTED.

  `innerHTML` preserves insertion order, and two front ends write props
  in different orders, so comparing it compares the serialiser rather
  than the page — the predecessor report's first run reported two
  witnesses as producing different pages on exactly this mistake, and
  they did not. Sorting the names compares the DOM.

  This is the entire fairness guarantee of the comparison. Without it,
  two arms could be timed against each other while building different
  pages."
  [node]
  (let [out (array)]
    (letfn [(walk [n]
              (case (.-nodeType n)
                1 (let [tag   (str/lower-case (.-tagName n))
                        attrs (->> (array-seq (.-attributes n))
                                   (map (fn [a] [(.-name a) (.-value a)]))
                                   (sort-by first))]
                    (.push out (str "<" tag))
                    (doseq [[k v] attrs]
                      (.push out (str " " k "=\"" v "\"")))
                    (.push out ">")
                    (doseq [c (array-seq (.-childNodes n))] (walk c))
                    (.push out (str "</" tag ">")))
                3 (.push out (.-nodeValue n))
                8 nil
                (.push out (str "#" (.-nodeType n)))))]
      (doseq [c (array-seq (.-childNodes node))] (walk c)))
    (.join out "")))

(defn element-count
  [node]
  (.-length (.querySelectorAll node "*")))

;; ---------------------------------------------------------------------------
;; Hosts
;; ---------------------------------------------------------------------------

(defn browser?
  []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn leave-act-environment!
  "React's `act` queue is not the browser's scheduler. Every B6 reading is
  taken outside it, so a commit measured here is the commit a user's page
  performs."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn- fresh-container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

;; ---------------------------------------------------------------------------
;; One timed commit
;; ---------------------------------------------------------------------------

(defonce ^:private mount-seq (atom 0))

(defn mount-arm!
  "Mount `arm` over `props` into a fresh host and answer
  `{:ms … :container … :handle …}`.

  `:ms` is the wall time across `flushSync`, so it contains construction
  and commit and nothing scheduled out. The container is created and
  attached OUTSIDE the window: a `document.createElement` billed to one
  arm and not another would be a systematic error the size of some of the
  effects here."
  [arm props]
  (let [n         (swap! mount-seq inc)
        container (fresh-container!)
        handle    (volatile! nil)
        t0        (m/now-ms)]
    (react-dom/flushSync (fn [] (vreset! handle ((:mount arm) container props n))))
    (let [ms (- (m/now-ms) t0)]
      {:ms ms :container container :handle @handle :arm arm})))

(defn release!
  "Unmount and detach. Never timed."
  [{:keys [arm handle container]}]
  (when handle
    (try (react-dom/flushSync (fn [] ((:unmount arm) handle)))
         (catch :default _ nil)))
  (when container (.remove container))
  nil)

;; ---------------------------------------------------------------------------
;; Parity — run before any clock is read
;; ---------------------------------------------------------------------------

(defn parity
  "Mount every arm at once, answer
  `{:agree? bool :counts {id n} :canon {id html} :reference html}`, and
  leave the mounts standing for the caller to release.

  Every arm mounted simultaneously rather than one at a time, because the
  comparison is of pages and a page is what is in the document."
  [arms props]
  (let [mounts (mapv (fn [a] (mount-arm! a props)) arms)
        canon  (into {} (map (fn [m] [(:id (:arm m)) (canonical (:container m))]) mounts))
        counts (into {} (map (fn [m] [(:id (:arm m)) (element-count (:container m))]) mounts))
        ref    (get canon (:id (:arm (first mounts))))]
    {:mounts    mounts
     :canon     canon
     :counts    counts
     :reference ref
     :agree?    (every? #(= ref %) (vals canon))
     :disagree  (into [] (comp (remove (fn [[_ h]] (= ref h))) (map first)) canon)}))

;; ---------------------------------------------------------------------------
;; Interleaved rounds
;; ---------------------------------------------------------------------------

(defn- round4
  "Four decimal places. `measure`'s own rounder is private, and a second
  three-line copy is cheaper than widening that namespace's surface for a
  bench row."
  [x]
  (/ (js/Math.round (* (double x) 10000.0)) 10000.0))

(defn- p50 [xs] (:p50 (m/summarise xs)))

(defn round!
  "One round: `(+ warmup samples)` sample indices, every arm mounted at
  every index, arm order rotating with the index. Answers `{id [ms …]}`
  over the measured indices only."
  [arms props {:keys [warmup samples]}]
  (let [k   (count arms)
        acc (atom (zipmap (map :id arms) (repeat [])))]
    (dotimes [s (+ warmup samples)]
      (dotimes [j k]
        (let [arm (nth arms (mod (+ j s) k))
              mnt (mount-arm! arm props)]
          (when (>= s warmup)
            (swap! acc update (:id arm) conj (:ms mnt)))
          (release! mnt))))
    @acc))

(defn normalise
  "Turn one round's raw readings into `{:p50 {id ms} :ratio {id r}}`,
  every ratio against the floor measured in THIS round."
  [readings floor-id]
  (let [p50s  (into {} (map (fn [[id xs]] [id (p50 xs)])) readings)
        floor (get p50s floor-id)]
    {:p50   p50s
     :ratio (into {} (map (fn [[id v]] [id (round4 (/ v floor))])) p50s)}))

(defn across-rounds
  "Fold per-round ratio maps into `{id {:mean … :min … :max … :rounds n
  :straddles-1? bool}}`.

  `:straddles-1?` is the honesty flag: when an arm's range includes 1.0
  the report must say the arms are indistinguishable rather than quote
  the mean as a winner."
  [round-ratios]
  (let [ids (keys (first round-ratios))]
    (into {}
          (map (fn [id]
                 (let [vs (mapv #(get % id) round-ratios)]
                   [id {:mean         (round4 (/ (reduce + 0.0 vs) (count vs)))
                        :min          (round4 (apply min vs))
                        :max          (round4 (apply max vs))
                        :rounds       (count vs)
                        :straddles-1? (and (<= (apply min vs) 1.0)
                                           (>= (apply max vs) 1.0))}])))
          ids)))

;; ---------------------------------------------------------------------------
;; Publication
;; ---------------------------------------------------------------------------

(defn publish!
  "Write one record to the console as EDN. The browser runner flushes the
  console on failure and under `RF2_VERBOSE_TESTS=1`, which is how the
  evidence lane reads these."
  [tag record]
  (js/console.log (str ";; B6 " tag "\n" (pr-str record))))

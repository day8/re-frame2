(ns re-frame.bench.p0-harness
  "EP-0038 P0 — the INSTRUMENT. Canonical DOM, one timed commit, rounds
  whose arm order rotates AND reflects, and floor normalisation.

  Kept apart from the arms so the METHOD is one thing a reader checks
  once. Every rule below is a recorded instrument fault from the
  predecessor's harnesses, each of which produced a plausible precise
  WRONG NUMBER before it was caught.

  ## A reading is one `flushSync` window

  `react-dom/flushSync` brackets the commit, so the measured window holds
  element construction AND React's render, commit and DOM mutation, with
  nothing scheduled out of it. Nothing runs inside an `act` environment:
  `act` diverts work to its own queue, which is not what a browser does,
  and measured, cost ~600 ms a call.

  ## Warm-up matters more than interleaving

  The `.cjs` order guard's live reproduction measured one control over
  sixteen consecutive windows with nothing varying but how many times the
  site had run:

      42.32 | 10.26 10.26 10.26 10.33 10.28 | 8.12 8.12 ... 8.12

  — 5.3x the settled value on the first window, +27% for the next five,
  and 8.122 for ever after the seventh; while the immediate PREDECESSOR,
  with position held fixed, was worth 0.0-0.3%. So this harness warms
  every arm before it reads any of them, and it still interleaves,
  because a plan reversal moves position and adjacency together and
  neither factor may be left unchecked.

  ## Interleaving at the SAMPLE level, rotating AND reflecting

  A workstation with six other agents on it drifts on a timescale that
  runs all of one arm and then all of another straight into a systematic
  error. So every sample index mounts every arm, in an order that rotates
  AND REFLECTS with the index. A bare cyclic rotation changes which arm
  goes FIRST and NOTHING ELSE — arm `a` sits at slot `(a - s) mod k`, so
  its predecessor is `(a - 1) mod k` at every index — and every
  interleaved harness in this repository published that as a mitigation
  when it was not one. The order comes from
  `re-frame.bench.order-guard/slot-order`, which is the shared expression
  of the rule and carries its own arithmetic self-test.

  ## Ranges, never a mean alone

  Absolute milliseconds are published because a ratio on a 0.2 ms
  operation is not a product decision and a reader is owed the size of the
  thing. But no cross-round absolute claim is made: every figure is a
  ratio to the floor measured in the SAME round and the SAME segment, and
  a range that straddles 1.0 is reported as INDISTINGUISHABLE rather than
  as a winner.

  Owner: rf2-2rtt6.1 (standard); this arm rf2-2rtt6.4."
  (:require ["react-dom" :as react-dom]
            [clojure.string :as str]
            [re-frame.bench.order-guard :as guard]))

;; ---------------------------------------------------------------------------
;; The clock
;; ---------------------------------------------------------------------------

(defn now-ms
  "`performance.now()` where it exists. Chrome clamps it to 100 us, which
  is why the bulk rows batch their writes into a sample."
  []
  (if (and (exists? js/performance) (.-now js/performance))
    (js/performance.now)
    (.getTime (js/Date.))))

(defn- round4 [x]
  (/ (js/Math.round (* (double x) 10000.0)) 10000.0))

(defn p50 [xs] (guard/median xs))

;; ---------------------------------------------------------------------------
;; Canonical DOM — the fairness gate
;; ---------------------------------------------------------------------------

(defn canonical
  "Serialise `node`'s subtree with every element's attribute names SORTED.

  `innerHTML` preserves insertion order and two front ends write props in
  different orders, so comparing it compares the serialiser rather than
  the page — the predecessor's first run reported two witnesses as
  producing different pages on exactly that mistake, and they did not.
  Sorting the names compares the DOM.

  This is the entire fairness guarantee. Without it two arms could be
  timed against each other while building different pages, and a
  canonical-DOM gate has already caught an arm rendering an EMPTY page."
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

(defn element-count [node]
  (.-length (.querySelectorAll node "*")))

;; ---------------------------------------------------------------------------
;; Hosts
;; ---------------------------------------------------------------------------

(defn browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn leave-act-environment!
  "Every reading is taken outside React's `act` queue, so a commit measured
  here is the commit a user's page performs."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

;; ---------------------------------------------------------------------------
;; One timed mount
;; ---------------------------------------------------------------------------

(defn mount-arm!
  "Mount `arm` into a fresh host and answer `{:ms … :container … :handle …}`.

  The container is created and attached OUTSIDE the window: a
  `document.createElement` billed to one arm and not another would be a
  systematic error the size of some of the effects here."
  [arm]
  (let [container (container!)
        handle    (volatile! nil)
        t0        (now-ms)]
    (react-dom/flushSync (fn [] (vreset! handle ((:mount arm) container))))
    {:ms (- (now-ms) t0) :container container :handle @handle :arm arm}))

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
  "Mount every arm at once and answer
  `{:canon {id html} :counts {id n} :agree? bool :disagree [id …]}`,
  LEAVING THE MOUNTS STANDING for the caller to release.

  Every arm mounted simultaneously rather than one at a time, because the
  comparison is of pages and a page is what is in the document."
  [arms]
  (let [mounts (mapv mount-arm! arms)
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
;; Rounds
;; ---------------------------------------------------------------------------

(defn warm!
  "Mount and release every arm `n` times, reading no clock.

  Position dominates adjacency, and a site that has not run enough times
  yet reads 1.26x to 5.3x its settled value. Warming is therefore not
  hygiene, it is the largest single correction this instrument makes."
  [arms n]
  (dotimes [_ n]
    (doseq [arm arms]
      (release! (mount-arm! arm))))
  nil)

(defn mount-round!
  "One round of the mount row: `samples` sample indices, every arm mounted
  at every index, order rotating AND reflecting with the index.

  EVERY measured mount is verified against `expected` element count,
  OUTSIDE its own window (the window closes when `flushSync` returns).
  `:bad` / `:total` are the `N unverified of M` a report must carry: an arm
  that renders an empty page is the cheapest arm in any table, and a
  canonical-DOM gate has already caught exactly that.

  Answers `{:readings {id [ms …]} :order [{:arm :value :predecessor
  :position} …] :bad n :total n :position n}` — the order samples carry
  BOTH nuisance factors, because a plan reversal moves them together and
  neither may be left unchecked."
  [arms {:keys [samples]} expected position-start]
  (let [k    (count arms)
        acc  (atom {:readings (zipmap (map :id arms) (repeat []))
                    :order    []
                    :bad      0
                    :total    0
                    :position position-start
                    :previous nil})]
    (dotimes [s samples]
      (doseq [j (guard/slot-order k s)]
        (let [arm (nth arms j)
              mnt (mount-arm! arm)
              ok? (= expected (element-count (:container mnt)))]
          (swap! acc (fn [a]
                       (cond-> (-> a
                                   (update-in [:readings (:id arm)] conj (:ms mnt))
                                   (update :order conj {:arm         (:id arm)
                                                        :value       (:ms mnt)
                                                        :predecessor (:previous a)
                                                        :position    (:position a)})
                                   (update :total inc)
                                   (update :position inc)
                                   (assoc :previous (:id arm)))
                         (not ok?) (update :bad inc))))
          (release! mnt))))
    (select-keys @acc [:readings :order :bad :total :position])))

(defn normalise
  "One round's raw readings as `{:p50 {id ms} :ratio {id r}}`, every ratio
  against the floor measured in THIS round and THIS segment."
  [readings floor-id]
  (let [p50s  (into {} (map (fn [[id xs]] [id (p50 xs)])) readings)
        floor (get p50s floor-id)]
    {:p50   p50s
     :ratio (into {} (map (fn [[id v]] [id (round4 (/ v floor))])) p50s)}))

(defn across-rounds
  "Fold per-round ratio maps into
  `{id {:mean :min :max :rounds :straddles-1?}}`.

  `:straddles-1?` is the honesty flag: when an arm's range includes 1.0 the
  report must say the arms are INDISTINGUISHABLE rather than quote the
  mean as a winner."
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

(defn ratio-of-ratios
  "The cross-SEGMENT figure: `numerator`'s floor-normalised ratio over
  `denominator`'s, per round, folded into a range.

  This is the shape the red-zone thresholds are published in. Both inputs
  are ratios to the floor measured in their own segment of the same round,
  so the segment seam — the adapter destroy/install between them — cancels
  exactly. Answers `{:per-round [r …] :mean :min :max :straddles-1?}`."
  [num-per-round den-per-round]
  (let [vs (mapv (fn [n d] (round4 (/ n d))) num-per-round den-per-round)]
    {:per-round    vs
     :mean         (round4 (/ (reduce + 0.0 vs) (count vs)))
     :min          (round4 (apply min vs))
     :max          (round4 (apply max vs))
     :rounds       (count vs)
     :straddles-1? (and (<= (apply min vs) 1.0) (>= (apply max vs) 1.0))}))

;; ---------------------------------------------------------------------------
;; Publication
;; ---------------------------------------------------------------------------

(defn publish!
  "Write one record to the console as EDN, tagged so the driver can find
  it in the console stream."
  [tag record]
  (js/console.log (str ";; P0 " tag "\n" (pr-str record))))

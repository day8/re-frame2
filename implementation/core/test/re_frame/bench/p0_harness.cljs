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

  Owner: the operator-owned governance set that superseded rf2-2rtt6.1 on
  2026-08-10, enumerated once in `docs/design/hicasso/studio/README.md`;
  this arm rf2-2rtt6.4."
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

(defn collect!
  "Force a garbage collection BETWEEN samples, never inside a window.

  This is here because the instrument measured what happens without it,
  and it was not subtle. The FLOOR arm — a hand-written `createElement`
  walk with no substrate, no subscription and no re-frame state, doing
  byte-identical work in every round — read

      W1 mount floor:  2.30 ms -> 4.85 ms -> 6.10 ms   across three rounds
      W3 mount floor:  1.85 ms -> 3.85 ms -> 5.65 ms

  while the BULK floor over the same run sat flat at 0.75-0.80 ms and
  2.50-2.60 ms. The arm-order guard caught it as a phase contamination
  (LAST-THIRD reading 2.16x to 3.05x FIRST-THIRD, ranges disjoint) and
  refused, which is exactly what it is for. The difference between the two
  phases is allocation: the mount rows build and discard hundreds of React
  roots and hundreds of thousands of elements, and the collector's cost
  climbs with the garbage nobody made it take. The bulk rows mount once
  and then only write.

  Left alone, that drift does NOT cancel in the ratio — the same round's
  W1 readings gave 2.48x, then 1.69x, then 1.57x for one arm over the
  floor. A run that published the mean of those would be publishing the
  collector's schedule.

  So the page is launched with `--js-flags=--expose-gc` and every sample
  is followed by a collection. It sits outside every `flushSync` window by
  construction. The clock and the heap are separate claims and this is the
  seam between them: allocation is priced by the retained-heap row, and
  the clock row is not allowed to smear it across whichever arm happened
  to be measured when a major collection fell due.

  **A bare `gc()` is not enough, and the probe is what established that.**
  Chrome's exposed `gc()` performs a SCAVENGE — a minor collection of the
  young generation — and the mount rows' garbage is promoted long before
  it runs. With a bare `gc()` after every sample the page's
  `usedJSHeapSize` still climbed monotonically 34 MB -> 46 -> 55 -> 63 ->
  75 -> 87 across six segment entries, while `body-children` sat at 2 the
  whole time: nothing was leaking into the document, the collector was
  simply not being asked to do the work. The floor drifted 4.05 -> 7.95 ->
  8.60 ms on exactly that heap. So the request is explicit — a synchronous
  MAJOR collection — with the bare call kept as a fallback for a runtime
  that does not accept the options form.

  When `gc` is absent the harness does not pretend: nothing is collected
  and the guard's phase factor will catch the consequence."
  []
  (when-some [g (.-gc js/window)]
    (try (g #js {:type "major" :execution "sync"})
         (catch :default _ (g))))
  nil)

(defn probe
  "A cheap, published snapshot of the two things that make a page get
  slower as a run proceeds: what it is holding, and what it left attached
  to the document.

  `:body-children` is the leak canary. Every sample attaches its
  containers to `document.body` and detaches them again, so this number
  must be constant across the whole run. A number that CLIMBS says the
  arms are leaking mounted roots, and a leaking harness reads as a
  progressively slower machine — which is precisely the diagnosis a
  phase-contaminated figure invites a reader to make, wrongly."
  []
  {:used-heap     (if-some [m (.-memory js/performance)] (.-usedJSHeapSize m) -1)
   :body-children (.-childElementCount js/document.body)})

(def gc-available?
  "Published beside every record, because a run without the collector is a
  different instrument and the reader is owed which one produced the row."
  (delay (some? (.-gc js/window))))

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

;; ---------------------------------------------------------------------------
;; Releasing — and the nested-`flushSync` fault that hid inside it
;; ---------------------------------------------------------------------------
;;
;; **An unmount must NOT be wrapped in `react-dom/flushSync`.** Every
;; React root's own `unmount()` opens a `flushSync` internally to tear the
;; tree down; wrapping the call in one more makes that a NESTED flush,
;; which React does not perform — it schedules the work instead. The root
;; handle is nulled either way, so the caller sees a clean return, and in
;; an `:advanced` bundle the development warning that would have said so
;; is compiled out. The container is then detached from a document that
;; never committed the unmount, and the whole fiber tree stays reachable.
;;
;; Measured, on this instrument, with the wrapper in place: the page's
;; `usedJSHeapSize` climbed 34 -> 46 -> 55 -> 63 -> 75 -> 87 MB across six
;; segment entries — about 12 MB per entry, which is 18 W1 roots at
;; ~500 KB plus 144 W3 roots at ~25 KB, i.e. EVERY root retained — while
;; `body-children` sat at 2 throughout, so nothing was leaking into the
;; document. The floor arm, which cannot change, drifted 3.4 -> 5.8 -> 7.0
;; ms and the arm-order guard refused on phase. A forced major collection
;; between samples did not move it, because the roots were not garbage.
;;
;; The `try/catch` that used to sit around the unmount is gone with it. It
;; swallowed exactly the class of failure that produces this fault, and a
;; release that fails silently is how a benchmark comes to be measuring a
;; page that is still standing.

(defn release!
  "Unmount and detach. Never timed. NOT wrapped in `flushSync` — see above."
  [{:keys [arm handle container]}]
  (when handle ((:unmount arm) handle))
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

(defn mount-sample!
  "`n` mounts of `arm` as ONE sample, each into its own pre-attached
  container, the clock bracketing only the mounts.

  `n > 1` exists for the same reason the bulk row batches its narrow
  writes. Chrome clamps `performance.now()` to 100 us, and the 51-element
  form mounts in three to eight quanta — close enough to the clamp that
  two arms can read the identical figure because the timer cannot tell
  them apart, which is a null result wearing the clothes of a tie. This
  run MEASURED that: at one mount a sample, both segments of the form
  witness returned exactly 0.75 ms and the ratio came out at precisely
  1.0000. Batching lifts every arm clear of the clamp; the witness is
  unchanged, only the sample is bigger.

  The containers are created and attached OUTSIDE the window: a
  `document.createElement` billed to one arm and not another would be a
  systematic error the size of some of the effects here.

  Answers `{:ms … :bad n :total n}` with every mount in the sample
  verified against `expected` — outside the window, since the window
  closes when the last `flushSync` returns.

  **`expected` nil means UNVERIFIABLE, and an unverifiable window is
  excluded from the denominator rather than counted as verified.** The one
  caller that passes nil is the positive control, whose two arms build
  different pages on purpose; there is nothing to read back. Counted into
  `:total` with `:bad 0` — which is what this answered before rf2-95s5b —
  those windows would dilute a real failure in whatever tally a caller
  summed them into, and `N unverified of M` would be quoting an M that
  nothing had checked. So they contribute to neither, and the control is
  adjudicated by its own gate instead (`lane/control-verdict`, reached
  through `p0-app/adjudicate`). No published figure moves: `control-round!`
  keeps only `:readings` and has always discarded these counts."
  [arm n expected]
  (let [containers (mapv (fn [_] (container!)) (range n))
        handles    (volatile! [])
        t0         (now-ms)]
    (doseq [c containers]
      (react-dom/flushSync (fn [] (vswap! handles conj ((:mount arm) c)))))
    (let [ms  (- (now-ms) t0)
          bad (if (nil? expected)
                0
                (count (remove #(= expected (element-count %)) containers)))]
      ;; Bare, and unguarded. See the release! commentary below: wrapping
      ;; this in `flushSync` nests a flush React will not perform, and
      ;; retains every root that was ever mounted.
      (doseq [hd @handles] ((:unmount arm) hd))
      (doseq [c containers] (.remove c))
      (collect!)
      {:ms ms :bad bad :total (if (nil? expected) 0 n)})))

;; ---------------------------------------------------------------------------
;; The schedule, CHOSEN by its measured property rather than assumed
;; ---------------------------------------------------------------------------

(defn choose-schedule
  "Pick the run order whose ADJACENCY actually gives every arm at least two
  distinct immediate predecessors, and answer it with the evidence.

  `slot-order` — rotate then reflect on odd indices — is the shared rule,
  and for three or more arms it is the right one: a bare cyclic rotation
  changes which arm goes FIRST and nothing else, so every arm keeps the
  same predecessor in every round. **For exactly TWO arms it used to
  degenerate**: rotating `[0 1]` gives `[1 0]`, reversing that gives
  `[0 1]` again, so the reflecting schedule emitted the identical order at
  every index and each arm had exactly one predecessor for ever. This run
  measured that too — the guard reported `:unchecked`, `only 1 stratum —
  the question was never asked`, on every substrate arm, and refused.
  That is the guard doing its job, and the repair belonged in the ARM, not
  in the guard.

  **`rf2-ouwh8` has since repaired it at the source**: `slot-order` drops
  the reflection at `k = 2`, where the bare rotation already supplies both
  of the orders two arms have. The two candidates below therefore now
  COINCIDE at `k = 2` and this function no longer has a degenerate case to
  route around. It stays because scoring is worth more than assuming — it
  publishes the measured adjacency of the schedule it actually ran, which
  is the difference between a mitigation and a claim of one — but a reader
  should not take its presence as evidence that the shared rule is still
  broken.

  So rather than hard-coding either rule, both candidates are scored with
  the guard's own `adjacency` over the run this harness is about to
  perform, and the one that satisfies the property with the lowest modal
  predecessor share wins. `:seams?` is true because these sample indices
  genuinely run back to back in one call: the last arm of index `s` really
  does precede the first of index `s+1`.

  Answers `{:name :fn :min-distinct :max-modal-share :sufficient?}`. When
  neither candidate qualifies the harness does NOT silently proceed — the
  fact is published and the guard's `:unchecked` refusal stands."
  [k samples]
  (let [score (fn [nm f]
                (let [a  (guard/adjacency k samples f {:seams? true})
                      as (vals (:arms a))]
                  {:name            nm
                   :fn              f
                   :min-distinct    (apply min (map :distinct as))
                   :max-modal-share (apply max (map :modal-share as))}))
        cands [(score :reflecting guard/slot-order)
               (score :rotating   guard/rotation-only)]
        ok    (filter #(>= (:min-distinct %) 2) cands)]
    (if (seq ok)
      (assoc (apply min-key :max-modal-share ok) :sufficient? true)
      (assoc (first cands) :sufficient? false))))

;; ---------------------------------------------------------------------------
;; Rounds
;; ---------------------------------------------------------------------------

;; THE WARM-UP IS INSIDE THE ROUND, and that is not a stylistic choice.
;;
;; Position dominates adjacency, and a site that has not run enough times
;; yet reads 1.26x to 5.3x its settled value — so warming matters more
;; than interleaving does. But a warm-up run as a SEPARATE loop before the
;; round leaves the first recorded sample of every round with NO
;; predecessor, and this instrument measured what that costs: the guard
;; partitioned the `<none>` stratum against the rest and found the first
;; sample of a round reading 1.35x its siblings, ranges disjoint, and
;; refused. The first sample after an adapter destroy/install genuinely IS
;; cold; hiding it in a stratum of its own is not a fix.
;;
;; So `mount-round!` and `bulk-round!` run `warmup` sample indices that are
;; measured and thrown away, threading `:previous` through them, and the
;; first RECORDED sample therefore has a real predecessor like every other.

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
  [arms {:keys [warmup samples]} expected per-sample position-start]
  (let [k     (count arms)
        total (+ warmup samples)
        sched (choose-schedule k total)
        order (:fn sched)
        acc   (atom {:readings (zipmap (map :id arms) (repeat []))
                     :order    []
                     :bad      0
                     :total    0
                     :position position-start
                     :previous nil})]
    (dotimes [s total]
      (doseq [j (order k s)]
        (let [arm (nth arms j)
              smp (mount-sample! arm per-sample expected)]
          (swap! acc (fn [a]
                       (cond-> (assoc a :previous (:id arm))
                         (>= s warmup)
                         (-> (update-in [:readings (:id arm)] conj (:ms smp))
                             (update :order conj {:arm         (:id arm)
                                                  :value       (:ms smp)
                                                  :predecessor (:previous a)
                                                  :position    (:position a)})
                             (update :total + (:total smp))
                             (update :bad + (:bad smp))
                             (update :position inc))))))))
    (assoc (select-keys @acc [:readings :order :bad :total :position])
           :schedule (dissoc sched :fn))))

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

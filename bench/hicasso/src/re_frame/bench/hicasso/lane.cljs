(ns re-frame.bench.hicasso.lane
  "THE HICASSO P0 MEASUREMENT LANE — the shared instrument every P0 arm
  runs on (EP-0038, HD-017; built by rf2-2rtt6.2 for rf2-2rtt6.2/.3/.4/.5).

  One instrument, four arms' worth of consumers, so the METHOD is one
  thing a reader checks once. What lives here is everything that is the
  same whatever is being measured: the canonical-DOM fairness gate, the
  timed window, the reflecting schedule, the per-round floor
  normalisation, the DOM read-back accounting, the positive control's
  predicted-vs-measured arithmetic, and the arm-order guard's refusal.

  ## Where this file lives, and why the namespace does not say `freehand`

  Physically under `implementation/hicasso/test/`. It began under
  `implementation/freehand/test/` — HD-017 carved the bench/test
  measurement lane out of the donor freeze, and that tree was the one
  whose classpath already carried Reagent, UIx, React and `react-dom`
  together — and rf2-0yp7w moved the whole bench tree to its
  evidence-owned home beside the substrate it measures. The NAMESPACE is
  `re-frame.bench.hicasso.*` because the charter's anti-regression fence
  is explicit that Hicasso carries no continuity claim to its
  predecessors: an instrument that had to spell a withdrawn programme's
  name to compile would be making one. `implementation/hicasso/test` is a
  shadow `:source-paths` root, so the path under it is the namespace and
  nothing else is needed.

  Nothing here requires anything out of `implementation/freehand/`. That
  was deliberate before the move and it is why `now-ms`, `summarise` and
  `quantile` are re-derived here rather than borrowed from the donor's
  `bench.measure`: a frozen donor `src/` tree is not a dependency this
  lane should acquire. The move did not create that independence, it
  merely made it visible in the path. (That sentence used to count the
  lines — *eleven* — and `rf2-xa8wo` adding `quantile` made the count
  wrong while leaving the point untouched, so the point is what it now
  states.)

  ## What a reading is

  `react-dom/flushSync` brackets the commit, so the measured window holds
  the substrate's element construction AND React's render, commit and DOM
  mutation, with nothing scheduled out of it. Nothing runs inside an
  `act` environment: `act` diverts work to its own queue, which is not
  what a browser does, and the whole point of the window is that it is
  the browser's.

  ## The five disciplines this file enforces, and what each one cost

  Fifteen instrument faults were caught on the predecessor programme's
  harnesses, and every one of them produced a plausible PRECISE WRONG
  NUMBER before it was caught. The five that are structural are enforced
  here rather than left to each arm:

  1. **Both orders, and position before adjacency.** [[rounds!]] schedules
     with [[re-frame.bench.order-guard/slot-order]], which rotates AND
     REFLECTS, so every arm has at least two distinct immediate
     predecessors. A bare cyclic rotation — which four harnesses published
     as \"order rotating with the round\" — changes only which arm goes
     first and leaves every adjacency intact. And the larger effect is not
     adjacency at all: the recorded live reproduction read the same
     control `10.32 10.26 10.26 10.26 10.33 10.28` and then `8.12` for
     ever, +27% across six windows with nothing varying but how many times
     the site had run, while a held-fixed predecessor was worth 0.0–0.3%.
     So WARM-UP MATTERS MORE THAN INTERLEAVING, every sample carries its
     `:position` in the whole run, and the guard partitions on thirds.
  2. **Ranges, never a mean alone.** [[across-rounds]] answers min/max/mean
     per arm and flags `:straddles-1?`; overlapping ranges mean
     INDISTINGUISHABLE and a report must say so rather than quote the
     mean as a winner. That flag asks whether ONE arm separated from an
     empty frame, which a pair must clear before it carries any ratio at
     all — but clearing it does not mean the pair could resolve the line
     it is read against, and [[resolution]] is the second question.
     Neither answers the other.
  3. **Every measured write is read back out of the DOM inside its own
     window.** [[verified-write!]] reads the written cell after the clock
     stops and before the sample is banked; [[tally]] carries the count
     forward so every published row states `N unverified of M`. A clock
     alone once accepted a window in which 1,320 of 1,320 writes never
     reached the page.
  4. **A positive control with predicted vs measured, every run.**
     [[control-verdict]] takes a stated prediction and the measured range
     and answers whether the instrument had the signal its own arithmetic
     says it must. An instrument that cannot see a change it PREDICTS
     cannot be trusted to see one it does not. TWO RULES LIVE HERE and a
     caller picks by what its legs measure: [[control-verdict]] adjudicates
     on OVERLAP and stands for legs sitting on Chrome's 100 µs clamp;
     [[control-verdict-strict]] requires EVERY ROUND inside the band and is
     for batched windows that clear the quantum. Each answer carries the
     `:rule` that decided it, so a published record cannot be read under
     the other one.
  5. **The guard refuses, and the refusal is exit code 2.** [[guard!]]
     runs the shared self-test before anything is measured and the
     verdict after; `run.cjs` turns `:refuse? true` into `exit 2`. Four
     workers have hit a refusal on the predecessor harnesses and every one
     of them repaired the ARM. The tolerance is not the arm's to move."
  (:require ["react-dom" :as react-dom]
            [clojure.string :as str]
            [re-frame.bench.order-guard :as guard]
            [re-frame.frame :as frame]))

;; ---------------------------------------------------------------------------
;; Clock and summary — small enough that the lane owes the donor tree nothing
;; ---------------------------------------------------------------------------

(defn now-ms
  "`performance.now()` where it exists. Chrome clamps it to 100 µs, which
  is why every row states how many operations one SAMPLE contains."
  []
  (if (and (exists? js/performance) (.-now js/performance))
    (js/performance.now)
    (.getTime (js/Date.))))

(defn- quantile-of-sorted
  "[[quantile]] over a vector already ascending and known non-empty."
  [v q]
  (let [h  (* (dec (count v)) (double q))
        lo (int (js/Math.floor h))
        hi (int (js/Math.ceil h))
        a  (nth v lo)]
    (+ a (* (- (nth v hi) a) (- h lo)))))

(defn quantile
  "The `q`-quantile of `xs`, by LINEAR INTERPOLATION between the two order
  statistics either side of `h = (n-1)q`. That is the definition
  `clock_readjudicate.cjs` already spells out for its effect-size
  interval, so the lane carries one spelling of a quantile rather than a
  second one that would have to be checked against the first.

  **It is also the definition [[summarise]]'s `:p50` is already computed
  under**, which is the reason it is the one chosen rather than
  nearest-rank. `h` lands exactly on the middle order statistic when `n`
  is odd and exactly midway between the two middle ones when `n` is even,
  so `(quantile xs 0.5)` and `:p50` are one estimator and a row printing
  a `p50` beside a `p95` is printing one method. `:p50` keeps its own
  two-branch spelling all the same: `(a+b)/2` and `a+(b-a)/2` can differ
  in the last representable place, and an already-published `:p50` must
  not move because a field was added next to it. `lane_quantile_cljs_test`
  holds the agreement — exact on odd `n`, and within a float epsilon on
  even.

  **What a short sample does to a tail quantile, stated here because a
  bench window's `n` is small.** Interpolation cannot answer above the
  largest reading: at `n = 20` a `p99` is `h = 18.81`, four fifths of the
  way from the second-largest sample to the largest, and no reading in
  the sample ever took that value. That is true of every quantile
  estimator on a short sample rather than of this one — nearest-rank
  answers the maximum itself and is no more truthful about the tail — and
  the remedy is more rounds, not another formula. Every published row
  states its `:n` beside the figure so a reader can see how much of the
  tail was measured and how much was interpolated."
  [xs q]
  (let [v (vec (sort xs))]
    (when (pos? (count v))
      (quantile-of-sorted v q))))

(defn summarise
  "`{:n :min :max :p50 :p95 :p99}` over `xs`.

  The three quantiles are [[quantile]]'s definition — read it for what a
  `p95` or a `p99` means over a sample this short."
  [xs]
  (let [v (vec (sort xs))
        c (count v)]
    (when (pos? c)
      {:n   c
       :min (nth v 0)
       :max (peek v)
       :p50 (if (odd? c)
              (nth v (quot c 2))
              (/ (+ (nth v (dec (quot c 2))) (nth v (quot c 2))) 2.0))
       :p95 (quantile-of-sorted v 0.95)
       :p99 (quantile-of-sorted v 0.99)})))

(defn round4 [x] (/ (js/Math.round (* (double x) 10000.0)) 10000.0))

;; ---------------------------------------------------------------------------
;; Hosts
;; ---------------------------------------------------------------------------

(defn browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn leave-act-environment!
  "React's `act` queue is not the browser's scheduler. Every reading in
  this lane is taken outside it, so a commit measured here is the commit
  a user's page performs."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn fresh-container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

;; ---------------------------------------------------------------------------
;; Canonical DOM — the fairness gate
;; ---------------------------------------------------------------------------

(defn canonical
  "Serialise `node`'s subtree with every element's attribute names SORTED.

  `innerHTML` preserves insertion order and two front ends write props in
  different orders, so comparing it compares the serialiser rather than
  the page. Sorting the names compares the DOM. This is the entire
  fairness guarantee of a cross-arm ratio: without it two arms can be
  timed against each other while building different pages."
  [node]
  (let [out (array)]
    (letfn [(walk [n]
              (case (.-nodeType n)
                1 (let [tag   (str/lower-case (.-tagName n))
                        attrs (->> (array-seq (.-attributes n))
                                   (map (fn [a] [(.-name a) (.-value a)]))
                                   (sort-by first))]
                    (.push out (str "<" tag))
                    (doseq [[k v] attrs] (.push out (str " " k "=\"" v "\"")))
                    (.push out ">")
                    (doseq [c (array-seq (.-childNodes n))] (walk c))
                    (.push out (str "</" tag ">")))
                3 (.push out (.-nodeValue n))
                8 nil
                (.push out (str "#" (.-nodeType n)))))]
      (doseq [c (array-seq (.-childNodes node))] (walk c)))
    (.join out "")))

(defn utf8-bytes
  "How many BYTES `s` occupies as UTF-8 — the lane's ONE answer to a
  question asked under a byte label (rf2-2rtt6.121).

  `count` answers UTF-16 CODE UNITS, which agree with UTF-8 bytes only
  for ASCII. That is what makes the mistake fail open: on an ASCII page
  the wrong expression prints the right number, so nothing ever notices,
  and the error appears later as content grows a dash, an ellipsis or an
  emoji. `rf2-2rtt6.114` found the same defect in the SSR bake manifest,
  where the `dogfood-snapshot` document claimed 3,101 for a file of which
  3,119 bytes were written.

  `TextEncoder` and not `Buffer.byteLength`: these arms compile to
  `:browser` and `:advanced`, so `Buffer` is not there. It is not the
  driver's `utf8Bytes` either — the driver sits on the far side of the
  DevTools protocol and these strings are deliberately never sent across
  it (`str-hash` exists so an 18 KB page need not be), so the count has
  to happen in the page. `TextEncoder` is UTF-8 by definition and carries
  no encoding argument a later edit could drop.

  A fresh encoder per call: it is stateless, every caller is outside a
  timed window, and a namespace that builds a global at load time is a
  hazard this one has no reason to take."
  [s]
  (let [^js enc (js/TextEncoder.)
        ^js buf (.encode enc s)]
    (.-length buf)))

(defn element-count [node] (.-length (.querySelectorAll node "*")))

(defn text-at
  "The text of the `data-i=\"i\"` cell inside `container`, or nil. The
  read-back probe every verified window uses."
  [container i]
  (some-> (.querySelector container (str "[data-i=\"" i "\"]")) (.-textContent)))

;; ---------------------------------------------------------------------------
;; One timed mount
;; ---------------------------------------------------------------------------

(defonce ^:private mount-seq (atom 0))

(defn mount-arm!
  "Mount `arm` over `props` into a fresh host; answer
  `{:ms … :container … :handle … :arm …}`.

  The container is created and attached OUTSIDE the window: a
  `document.createElement` billed to one arm and not another would be a
  systematic error the size of some of the effects here."
  [arm props]
  (let [n         (swap! mount-seq inc)
        container (fresh-container!)
        handle    (volatile! nil)
        t0        (now-ms)]
    (react-dom/flushSync (fn [] (vreset! handle ((:mount arm) container props n))))
    {:ms (- (now-ms) t0) :container container :handle @handle :arm arm}))

(defn mount-batch!
  "Mount `arm` `k` times with ALL k inside ONE timed window; answer
  `{:ms … :mounts […]}`. The caller releases the mounts.

  `k` exists because Chrome clamps `performance.now()` to 100 µs and a
  small witness sits on that clamp: the predecessor's narrow row measured
  one write at a time and got back exactly 0.1 ms from two different
  arms — the quantum itself, wearing a null result's clothes. Timing `k`
  operations as ONE sample lifts the window clear of the clamp, and it is
  not the same as summing `k` separately-clamped readings, which
  quantises `k` times and adds the errors.

  Every container is created and attached BEFORE the clock starts, for
  the same reason [[mount-arm!]] does it: a `document.createElement`
  billed to one arm and not another is a systematic error the size of
  some of the effects here."
  [arm props k]
  (let [containers (vec (repeatedly k fresh-container!))
        handles    (volatile! nil)
        t0         (now-ms)]
    (react-dom/flushSync
      (fn [] (vreset! handles
                      (mapv (fn [c] ((:mount arm) c props (swap! mount-seq inc)))
                            containers))))
    {:ms     (- (now-ms) t0)
     :mounts (mapv (fn [c h] {:arm arm :container c :handle h}) containers @handles)}))

;; ---------------------------------------------------------------------------
;; Teardown, and why a swallowed one is a measurement fault
;; ---------------------------------------------------------------------------

(defonce ^:private teardown-failures (atom []))

(defn teardown-failure!
  "Record a release/unmount that threw, and answer nil.

  Every teardown in this lane is wrapped, because a release that throws
  half way through must still detach the container and must not abort the
  round. What it must NOT do is vanish, which is what `(catch :default _
  nil)` did at four sites (rf2-f5roa, from the PR #7263 and #7268 audits).

  An unmount that threw has left the arm's subscriptions, its watches and
  its React root STANDING. The next row is then measured on a page that is
  carrying them — more work, attributed to whatever arm happens to be
  under the clock — and the run reports a precise number for a page that
  is not the page under test. That is the same class as a write that never
  reached the DOM, and it gets the same treatment: recorded at the site,
  adjudicated by the caller, fatal."
  [where e]
  (swap! teardown-failures conj
         {:where (str where)
          :error (or (some-> e .-message) (str e))})
  nil)

(defn drain-teardown-failures!
  "Answer every teardown failure recorded since the last drain, and clear.

  Draining rather than accumulating so a caller can attribute a failure to
  the row that was running when it happened, instead of to the end of the
  run."
  []
  (let [fs @teardown-failures]
    (reset! teardown-failures [])
    fs))

(defn container-released!
  "Record a teardown failure unless `container` is EMPTY; answer whether it
  was. Called after an unmount RETURNED NORMALLY, and BEFORE the container
  is detached.

  Every mount door in this lane is a React root, and a root's `unmount`
  deletes its tree from its container synchronously — so a container still
  holding nodes names the fault a thrown exception cannot: an unmount that
  returned without releasing. Detach the container without looking and
  that root lives on, standing on a DETACHED tree no later census can see
  — [[residue]]'s body-children count sees only attached elements, its
  sub-cache census only frame subscriptions, and a ratom arm's reactions
  are rooted in a namespace-level atom outside both, still consuming every
  later write. The rf2-2rtt6.2 second audit proved the gap by mutation: a
  no-op'd bulk ratom unmount sailed through `residue-after-bulk`. This one
  read, taken while the container is still in hand, is where a SUCCESSFUL
  release is observable per arm, whatever family the arm belongs to."
  [where container]
  (let [n (.-length (.-childNodes container))]
    (if (zero? n)
      true
      (teardown-failure!
        where
        (str "the unmount RETURNED NORMALLY but its container still holds " n
             " node(s) — the root was never released, and it would stay live "
             "on a detached tree, consuming every later write")))))

(defn release!
  "Unmount and detach. Never timed.

  A throw is RECORDED ([[teardown-failure!]]) rather than swallowed, and
  the container is detached either way — the record is what makes it
  fatal, and detaching is what keeps one bad arm from leaving its DOM
  behind for every row that follows. An unmount that returns NORMALLY is
  not taken at its word either: [[container-released!]] reads the
  container before it goes, because a root that survives its own unmount
  does so on a detached tree that no census downstream can see."
  [{:keys [arm handle container]}]
  (when handle
    (when (try (react-dom/flushSync (fn [] ((:unmount arm) handle)))
               true
               (catch :default e
                 (teardown-failure! (str "release! " (:id arm)) e)))
      (when container
        (container-released! (str "release! " (:id arm)) container))))
  (when container (.remove container))
  nil)

(defn assert-teardown-clean!
  "Throw if any teardown has failed since the last check.

  Checked BETWEEN rows, segments and rounds, which is where the damage is
  done: a React root or a frame that did not tear down is still holding
  its watches and its caches when the next row is measured, and that row
  then reports a precise number for a page that is not the page under
  test. The throw lands in the caller's fatal path, which records
  `HICASSO_ERROR`, and the driver exits 1.

  This is the ADJUDICATION half of [[teardown-failure!]]. Recording a
  failure and never asking about it is the same silence
  `(catch :default _ nil)` produced, one indirection further out."
  [after]
  (let [fs (drain-teardown-failures!)]
    (when (seq fs)
      (throw (ex-info (str "teardown FAILED after " after
                           " — an arm or a frame did not tear down, so its caches and "
                           "watches are still standing and every row after this one "
                           "would be measured on a page carrying them: " (pr-str fs))
                      {:after after :failures fs})))
    nil))

;; ---------------------------------------------------------------------------
;; Residue — the proof that the teardown that did not throw also worked
;; ---------------------------------------------------------------------------

(defn residue
  "The live-reference census a fully released row must return to.

  Three integers, all read OUTSIDE every timed window:

    `:body-children`  attached elements under `document.body`. Every
                      container this lane mints is attached there and
                      detached by [[release!]], so a container whose arm
                      survived its unmount shows up here as a page the
                      NEXT row is measured on top of.
    `:sub-entries`    slots in the frame's sub-cache.
    `:sub-ref-count`  the sum of those slots' ref-counts.

  Both subscription numbers return to their starting value by
  construction rather than by hope: `re-frame.subs.cache/unsubscribe!`
  EVICTS an entry the moment its ref-count reaches zero, with no grace
  period, so a row whose every boundary unmounted leaves an EMPTY cache.
  A row that left three hundred `[:p0/cell i]` reactions watching the
  app-db is therefore visible as a non-zero count and not merely as a
  slower page.

  A teardown that THREW is caught by [[teardown-failure!]]. This is the
  other half: a teardown that returned normally and did not actually
  release. Neither is a leak detector and neither is a lifecycle
  framework — this answers one question, `did the release this row just
  performed actually happen`, in three integers.

  `counters` is the caller's own additions to the census — `{key thunk}`,
  each thunk answering an integer — for an arm family whose live
  references are rooted where NEITHER built-in counter can see them. The
  P0 ratom arms are the recorded case (rf2-2rtt6.2, second audit): their
  cursor reactions watch a namespace-level `reagent.core/atom`, not the
  frame's sub-cache, and a surviving root's tree is detached, not under
  `document.body`, so a release that never happened was invisible to all
  three integers while the detached tree consumed every later write. One
  map of counts per run, compared by the same equality — the caller names
  where its arms' references live, and nothing more general than that."
  ([frame-id] (residue frame-id nil))
  ([frame-id counters]
   (let [cache (some-> (frame/frame frame-id) :sub-cache deref)]
     (into {:body-children (.-childElementCount js/document.body)
            :sub-entries   (count cache)
            :sub-ref-count (reduce + 0 (map #(or (:ref-count %) 0) (vals cache)))}
           (map (fn [[k f]] [k (f)]))
           counters))))

(defn settle!
  "Yield ONE MACROTASK, so a substrate that schedules its disposals there
  has run them. Answers a promise. Never inside a timed window.

  ## Reagent's unmount does not release its subscriptions synchronously

  Measured, not assumed. Reading [[residue]] at three points after the
  parity phase releases every arm of both witnesses:

      immediately after `release!`   {:sub-entries 300 :sub-ref-count 312}
      after `reagent.core/flush`     {:sub-entries 300 :sub-ref-count 312}
      after ONE `setTimeout`         {:sub-entries   0 :sub-ref-count   0}

  `root.unmount()` inside a `flushSync` returns with the frame's sub-cache
  still holding every entry the page was reading, and Reagent's own
  SYNCHRONOUS render drain does not move them either — the disposals are
  on `reagent.impl.batching`'s next-tick queue, which is a macrotask. One
  turn later they are gone. Nothing is leaking.

  It is not cosmetic, and this is why a settle point belongs BETWEEN ROWS
  rather than only in front of the assertion. A mount row is fully
  synchronous — `rounds!` is `dotimes` inside `doseq` inside `mapv` — so
  an entire row of a hundred mount-and-release cycles runs in ONE
  macrotask and NOT ONE disposal happens inside it. Without a settle the
  next row begins on a page whose cached subscriptions still carry every
  consumer reaction the previous row mounted, and the row after that
  carries both. Yielding here is what makes each row's first sample and
  its last sample the same experiment."
  []
  (js/Promise. (fn [resolve] (js/setTimeout (fn [] (resolve nil)) 0))))

(defn assert-residue!
  "Throw unless the residue is back at `baseline`. Answers the reading.

  Called between rows and never inside a window, and always AFTER
  [[settle!]] — the claim is `the release happened`, not `the release
  happened synchronously`, and Reagent's does not. The comparison is
  EQUALITY, not a threshold: the quantities are counts of live references
  that a correct teardown drives to exactly where they started, and a
  tolerance on them would only make room for the fault.

  `counters` is [[residue]]'s: the caller that took its baseline with an
  extra census must assert with the same one, or the comparison silently
  narrows to the counters a surviving root does not touch."
  ([baseline frame-id after] (assert-residue! baseline frame-id after nil))
  ([baseline frame-id after counters]
   (let [now (residue frame-id counters)]
     (when (not= baseline now)
       (throw (ex-info (str "RESIDUE after " after " — the teardown returned without "
                            "throwing but did not release: expected " (pr-str baseline)
                            ", found " (pr-str now) ". Every later sample would be "
                            "measured on a page still carrying it")
                       {:after after :baseline baseline :residue now})))
     now)))

;; ---------------------------------------------------------------------------
;; Parity — run before any clock is read
;; ---------------------------------------------------------------------------

(defn parity
  "Mount every arm of `arms` at `props` at once and compare their pages.

  `:parity-exempt?` arms are mounted and released but excluded from the
  comparison — the positive control builds a DIFFERENT page on purpose
  (that is what makes it a control), so folding it into the equality
  would turn the fairness gate into a permanent failure.

  Leaves the mounts standing; the caller releases them, so it can read
  the pages before they go."
  [arms props]
  (let [mounts (mapv (fn [a] (mount-arm! a props)) arms)
        judged (remove #(:parity-exempt? (:arm %)) mounts)
        canon  (into {} (map (fn [m] [(:id (:arm m)) (canonical (:container m))])) judged)
        counts (into {} (map (fn [m] [(:id (:arm m)) (element-count (:container m))])) judged)
        ref    (get canon (:id (:arm (first judged))))]
    {:mounts    mounts
     :canon     canon
     :counts    counts
     :reference ref
     :agree?    (every? #(= ref %) (vals canon))
     :disagree  (into [] (comp (remove (fn [[_ h]] (= ref h))) (map first)) canon)}))

;; ---------------------------------------------------------------------------
;; The schedule, and the guard's samples
;; ---------------------------------------------------------------------------

(def slot-order
  "The reflecting schedule, taken from the guard itself rather than
  restated. `order-guard`'s self-test carries the arithmetic proof that a
  bare rotation gives every arm exactly ONE within-round predecessor, that
  reflecting on odd rounds gives it two in balance, and that at `k = 2` the
  reflection CANCELS the rotation and so is dropped (`rf2-ouwh8`); a second
  copy here would be a second authority with nothing holding it in step —
  and the copy that did exist, in `b6-harness`, is exactly where the `k = 2`
  degeneracy survived a fix to this one."
  guard/slot-order)

(defn sample-collector
  "A mutable collector for the guard's samples.

  `:position` is the index in the WHOLE run, not within a round, because
  the effect the guard is most likely to catch is warm-up and warm-up
  does not restart at a round boundary."
  []
  (atom {:pos 0 :prev nil :samples []}))

(defn observe!
  "Advance the collector's `:prev` pointer WITHOUT banking a sample.
  Warm-up samples call this.

  A WARM-UP SAMPLE IS STILL A PREDECESSOR. [[collect!]] both banks a
  sample and advances the pointer, so a harness that skips it during
  warm-up leaves the first RECORDED sample of a block tagged with whatever
  ran before the warm-up — an arm that has not touched the site for a
  whole warm-up block. A predecessor label that names something which did
  not run immediately before is not a weaker fact than a real one; it is a
  different fact wearing its clothes.

  ## This repair was made TWICE before it was made here (rf2-6ta5r)

  `p0_converge_app` and `coldmount_app` hand-roll their sampling loops,
  both hit this, and both fixed it locally — as a private
  `mark-predecessor!` whose body was these same three lines. Their
  incident is recorded: `p0_converge`'s first cut published the fault and
  the guard REFUSED it, `narrow/reagent-subs/ctl-2x` contaminated by a
  two-sample stratum labelled `M1/reagent-subs/reagent-subs`, an arm from
  a different ROW; `rf2-2rtt6.4` met the same class from the other side,
  where the untagged samples became a `<none>` stratum reading 1.35x its
  siblings.

  What never got the repair was [[rounds!]] — the SHARED loop every other
  harness on this lane rides. TEN bench apps call it and every one of them
  carried the fault, and the two that had fixed it were exactly the two
  that do not call it. This is that fix moved to the one place it belongs,
  and the private copies now call it: a second authority with nothing
  holding it in step is the shape that let the `k = 2` degeneracy in
  [[slot-order]] survive a fix to its own sibling.

  ## What it cost on the two clocks this bead came from

  Replaying the schedule prices it exactly: at every arm count this lane
  uses (4, 5, 7, 8) one arm — whichever holds the first slot at
  `s = warmup` — carried 5 of its 30 samples under an adjacency that did
  not happen. On the seven-arm `amp_merge_clock` schedule that arm is
  `:expanded-b`, THE NULL, with 4 samples filed under `floor`, which never
  runs before it; on the five-arm schedule those same 4 were filed under
  `expanded-b` ITSELF, which no schedule can produce. The position counter
  is untouched by this function, because a discarded sample has no
  position."
  [coll id]
  (swap! coll assoc :prev id)
  nil)

(defn collect!
  "Bank one sample for arm `id` at `value`, tagged with what ran
  immediately before it and where in the run it sits.

  `:prev` is maintained by this function AND by [[observe!]]; a caller
  that runs unbanked samples must call the latter for them or the
  predecessor recorded here is fiction."
  [coll id value]
  (swap! coll (fn [{:keys [pos prev samples]}]
                {:pos      (inc pos)
                 :prev     id
                 :samples  (conj samples {:arm (name id) :value value
                                          :predecessor (some-> prev name)
                                          :position pos})}))
  value)

;; ---------------------------------------------------------------------------
;; Serial promise chains
;; ---------------------------------------------------------------------------

(defn chain
  "Fold `xs` into a serial promise chain, threading an accumulator.

  Defined HERE rather than beside the write helpers it was written for,
  because [[rounds-async!]] below is its second consumer and a var cannot
  be used above its definition."
  [init xs f]
  (reduce (fn [p x] (.then p (fn [acc] (f acc x)))) (js/Promise.resolve init) xs))

;; ---------------------------------------------------------------------------
;; Rounds
;; ---------------------------------------------------------------------------

(defn visit-plan
  "The visits ONE run makes, in execution order, as
  `{:round :arm :measured?}`.

  ## Why it is PUBLIC

  A driver that banks something PER VISIT beside the reading — a
  decomposition, a counter, a per-sample structural part — banks it from
  inside its own `measure-one!`, which is handed an arm and not a visit.
  It therefore cannot tell a warm-up visit from a measured one, and a
  driver that publishes both under one heading publishes a distribution
  whose population is not the population of the `:summary` it decomposes.
  `slice-echo-clock-app` did exactly that: `100` banked values per arm
  against a `60`-value summary at `{:warmup 8 :samples 12}` over five
  rounds.

  The repair is to derive the measured mask FROM THIS PLAN rather than
  from a second reading of the schedule — `(count arms)`, [[slot-order]]
  and the warm-up boundary re-implemented in a driver is the copy this
  whole namespace is written to avoid. Answering the plan is cheaper than
  answering a mask, because the plan is the thing that cannot drift.

  THE SCHEDULE IS STATED ONCE, HERE, and both loops below walk it.
  [[rounds!]] is synchronous and [[rounds-async!]] is not, and the
  obvious way to write the second is to give it its own nested loops —
  which would be a second copy of the reflecting order, the warm-up
  boundary and the round boundary, with nothing holding it in step with
  the first. This file already prices that shape twice: [[slot-order]]'s
  `k = 2` degeneracy survived a fix to its own sibling because
  `b6-harness` held a copy, and [[observe!]]'s missing call was repaired
  privately in the two hand-rolled loops while the ten apps riding the
  shared one kept the fault. A plan both loops consume cannot disagree
  with itself, and `lane-schedule-async-cljs-test` asserts that they do
  not rather than trusting this paragraph."
  [arms {:keys [warmup samples] :as _sampling} rounds]
  (let [k (count arms)]
    (for [round (range rounds)
          s     (range (+ warmup samples))
          j     (slot-order k s)]
      {:round round :arm (nth arms j) :measured? (>= s warmup)})))

(defn- fresh-readings
  "One empty reading vector per arm, per round."
  [arms rounds]
  (atom (vec (repeat rounds (zipmap (map :id arms) (repeat []))))))

(defn- bank-visit!
  "Bank one visit's reading — or, for a warm-up visit, record only that it
  RAN.

  DISCARDED, BUT NOT UNSEEN. A warm-up sample is what the next measured
  sample actually followed; [[observe!]] carries that across the gap so
  the guard's `:predecessor` factor stratifies what ran rather than what
  was banked."
  [coll readings label {:keys [round arm measured?]} ms]
  (if measured?
    (do (collect! coll (label arm) ms)
        (swap! readings update-in [round (:id arm)] conj ms))
    (observe! coll (label arm)))
  nil)

(defn- default-label
  "An arm names itself to the guard unless the caller says otherwise."
  [arm]
  (name (:id arm)))

(defn rounds!
  "Run `rounds` rounds of `sampling` over `arms`, calling
  `(measure-one! arm)` for each sample and banking the answer.

  Every sample index visits every arm, in [[slot-order]]'s reflecting
  order. Warm-up samples are taken and DISCARDED — they still move the
  site's position, which is the point.

  `label` names an arm to the GUARD, and it must be unique across every
  row a single verdict pools. Three witnesses' `:floor` arms are three
  different amounts of work; pooled under one name their ranges are
  disjoint by construction and the guard refuses a contamination that is
  really just the witness table.

  Answers `{:readings [{id [ms …]} …] :samples [guard-samples]}`.

  ## WARM-UP IS CHARGED PER ROUND, AND THE RAMP IT GUARDS IS RUN-LEVEL

  Known, priced, and deliberately NOT repaired (rf2-ydqzt). `warmup`
  discarded samples are spent per arm inside EVERY round, but the ramp
  [[collect!]]'s `:position` exists to expose does not restart at a round
  boundary — that is [[sample-collector]]'s own reason for counting
  position across the whole run. Replaying this loop against
  [[slot-order]] prices the asymmetry exactly: the FIRST measured sample
  of a run has had exactly `warmup` prior executions of its arm however
  many rounds follow, while at `{:warmup 8 :samples 12}` over five rounds
  the run's last third sits at 72–99 prior executions (32–44 at
  `{:warmup 3 :samples 6}`). Round one is the only round that needs
  warming; every round after it pays a full `warmup` block that warms
  nothing.

  SO RAISING `warmup` IS A BLUNT LEVER — it buys round one's pre-warm at
  `rounds` times its cost, which on these clocks is five — and the
  targeted repair is a run-level `:prewarm` that runs the arms P times,
  discarded, before the first round, leaving `warmup` as a small per-round
  settling allowance. **It is not built**,
  because the knob sufficed: rf2-h904p raised the two clocks to `8`, which
  puts the +27% step this lane records after a site's sixth execution
  inside the warm-up, and rf2-adld3's three-run window on that warmed rig
  then returned REPORTABLE on every arm by predecessor AND by phase, with
  the null it was opened on inside ±7.9% of 1.0 across fifteen rounds.

  WHAT WOULD WARRANT BUILDING IT: a window whose guard refuses on `:phase`
  at `:warmup 8` with the first-third stratum dominated by round one. Ten
  bench apps ride this loop and every one of them would inherit the new
  schedule, so the trigger is stated here rather than left to judgement."
  ([arms sampling rounds measure-one!]
   (rounds! arms sampling rounds measure-one! default-label))
  ([arms sampling rounds measure-one! label]
   (let [coll     (sample-collector)
         readings (fresh-readings arms rounds)]
     (doseq [visit (visit-plan arms sampling rounds)]
       (bank-visit! coll readings label visit (measure-one! (:arm visit))))
     {:readings @readings :samples (:samples @coll)})))

(defn rounds-async!
  "[[rounds!]]'s schedule, driven by a `measure-one!` that answers a
  PROMISE of the reading rather than the reading. Answers a promise of
  the same `{:readings :samples}` map.

  ## Why the lane needed this, and what its absence cost

  [[rounds!]] calls `measure-one!` and takes a NUMBER back, so an arm it
  can schedule is an arm whose whole window closes inside one synchronous
  call. **A window that ends at a PAINT cannot.** The browser produces the
  frame after the task returns and the only handle on it is a callback, so
  every arm this lane could schedule was one bracketed by
  `react-dom/flushSync` — a commit, and not a paint.

  That is not an accident of what happened to get written. It is the shape
  the only shared schedule allowed, and it is the reason both of the clock
  drivers pointed at the package measure a mount:
  `docs/design/hicasso/product/budgets.md` §4 registers `U1`–`U4` over
  *latency to visible echo* and *latency to next paint* and records, in
  those words, that the population is what still blocks them.

  ## It is the same schedule, and that is asserted rather than claimed

  Same [[visit-plan]], same warm-up boundary, same `:predecessor` and
  `:position` tagging, same answer shape. `lane-schedule-async-cljs-test`
  runs one deterministic stub through both loops and asserts the banked
  samples and readings are `=`, so the two cannot drift into two
  schedules the way [[slot-order]]'s copy once did.

  ## What it does NOT change

  The visits stay SERIAL. [[chain]] starts visit *n+1* only once visit
  *n*'s promise has resolved, exactly as the synchronous loop makes its
  next call only once the previous one has returned — so an arm still
  measures with no sibling running beside it, which is the whole premise
  the arm-order guard adjudicates under."
  ([arms sampling rounds measure-one!]
   (rounds-async! arms sampling rounds measure-one! default-label))
  ([arms sampling rounds measure-one! label]
   (let [coll     (sample-collector)
         readings (fresh-readings arms rounds)]
     (.then (chain nil (visit-plan arms sampling rounds)
                   (fn [_ visit]
                     (.then (js/Promise.resolve (measure-one! (:arm visit)))
                            (fn [ms] (bank-visit! coll readings label visit ms)))))
            (fn [_] {:readings @readings :samples (:samples @coll)})))))

(defn normalise
  "One round's raw readings as `{:p50 {id ms} :ratio {id r}}`, every ratio
  against the floor measured in THAT round."
  [readings floor-id]
  (let [p50s  (into {} (map (fn [[id xs]] [id (:p50 (summarise xs))])) readings)
        floor (get p50s floor-id)]
    {:p50   p50s
     :ratio (into {} (map (fn [[id v]] [id (round4 (/ v floor))])) p50s)}))

(defn across-rounds
  "Fold per-round ratio maps into
  `{id {:mean :min :max :rounds :straddles-1?}}`.

  `:straddles-1?` is the honesty flag: when an arm's range includes 1.0
  the report says the arms are indistinguishable rather than quoting the
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

(defn ratio-between
  "The ratio of arm `a` to arm `b`, per round, as a range. The bar is
  stated against a DENOMINATOR ARM, not against the floor, so this is the
  arithmetic a bar row quotes.

  `:mean` IS AN ARITHMETIC MEAN — the average of the per-round ratios in
  `:per-round`, and not a median of anything. The distinction is worth a
  line because a median IS in the pipeline one level down (each round's
  ratio is built from `summarise`'s within-round `:p50`s), which is how
  PR #8326's publication came to call these values \"run-medians\" and
  their spans \"effect medians\" — corrected under rf2-pqyxz. A row
  quoting this key names it a MEAN OF PER-ROUND RATIOS, each of which is
  a ratio of within-round medians."
  [round-ratios a b]
  (let [vs (mapv (fn [r] (round4 (/ (get r a) (get r b)))) round-ratios)]
    {:numerator a :denominator b
     :mean (round4 (/ (reduce + 0.0 vs) (count vs)))
     :min (apply min vs) :max (apply max vs)
     :per-round vs
     :straddles-1? (and (<= (apply min vs) 1.0) (>= (apply max vs) 1.0))}))

(defn resolution
  "What size of difference in the arms' OWN work a run could have SEEN,
  for one [[ratio-between]] pair. Answers

      {:denominator :floor-p50 :denominator-p50 :own-work
       :own-work-share :spread :resolves-at}

  ## The question [[across-rounds]] does not ask

  `:straddles-1?` asks whether ONE arm separated from an empty frame. A
  bar row asks whether TWO arms separate from EACH OTHER at a stated
  line. Those are different questions, and the first can answer yes while
  the second answers no — measured on `slice-broad-clock-app` under
  rf2-9wmqd, where a pair cleared the floor in all three evidence runs
  and still could not resolve `1.25x`. The gate is a sound NECESSARY
  condition and stays exactly where it is; this is the other half.

  ## The arithmetic, and why the frame grid is the whole of it

  A paint-bounded window is mostly the wait for the browser's next
  rendering opportunity, and that wait is in BOTH arms, so it enters the
  ratio as dead weight. Only `:own-work` — the denominator arm's median
  above the floor's — can move a window-level ratio at all, and it is
  `:own-work-share` of the window. So if the numerator's own work were
  `k` times the denominator's, the published ratio would move by
  `(k - 1) * :own-work-share` and no further. Set that against the
  scatter the run actually showed — `:spread`, the width of
  [[ratio-between]]'s `:per-round` — and

      :resolves-at = 1 + :spread / :own-work-share

  is the smallest `k` whose displacement is as large as the run's own
  noise. A difference below it is inside the scatter, where an observed
  `1.00x` is consistent with `1.00x` and with `k` alike.

  ## IT DECIDES NOTHING, and may not

  No line appears here and none may: `budgets.md` §7 routes every
  distributional row to a pinned evidence run and forbids converting one
  into a lane threshold. This is an HONESTY AID — a run saying what it
  could have seen — and the reader compares it against the line THEIR row
  is stated at. Two edges, stated rather than special-cased:
  `:resolves-at` is `nil` when the denominator arm did not clear the
  floor, because a pair with no work above the grid resolves nothing at
  any size; and the figure is bounded below by the run's own scatter, so
  rounds that happened to agree exactly report `1.0`, which says the
  scatter bounds nothing rather than that any difference is visible."
  [{:keys [denominator per-round]} summary floor-id]
  (let [d      (get-in summary [denominator :p50])
        floor  (get-in summary [floor-id :p50])
        own    (- d floor)
        share  (/ own d)
        spread (- (apply max per-round) (apply min per-round))]
    {:denominator     denominator
     :floor-p50       (round4 floor)
     :denominator-p50 (round4 d)
     :own-work        (round4 own)
     :own-work-share  (round4 share)
     :spread          (round4 spread)
     :resolves-at     (when (pos? share) (round4 (+ 1.0 (/ spread share))))}))

;; ---------------------------------------------------------------------------
;; Verified writes — "N unverified of M"
;; ---------------------------------------------------------------------------

(defn tally
  "A write-verification tally. `:of` counts every measured write; `:bad`
  counts those whose value never reached the page inside their own
  window."
  []
  (atom {:of 0 :bad 0}))

(defn tally-value [t] (let [{:keys [of bad]} @t] {:writes of :unverified bad}))

(defn assert-verified!
  "Throw unless the tally reads `0 unverified of M`. Answers the tally.

  The count was PUBLISHED and never ADJUDICATED, on both P0 entries: a row
  could report `400 unverified of 400` and the driver would still exit 0,
  because nothing between the tally and the exit code ever looked at
  `:unverified`. Every read-back this lane performs — the written cell, the
  mount's element count, the far end of the page — banks here, so this one
  line is what makes ALL of them load-bearing rather than decorative.

  Called AFTER the row's record is published, so the evidence a reader
  needs is on the console before the run dies on it, and never inside a
  timed window."
  [t where]
  (let [{:keys [writes unverified] :as v} (tally-value t)]
    (when (pos? unverified)
      (throw (ex-info (str where ": " unverified " UNVERIFIED of " writes
                           " — a measured operation did not produce the page it claims. "
                           "Every figure in this row is a clock reading over a page that "
                           "was not checked, so none of them is reportable")
                      {:where where :verification v})))
    v))

(defn verified-writes!
  "Run `ops` — a seq of `[i val probes]` — as ONE measured window: each
  writes, waits the way THIS ARM'S SCHEDULER requires and forces the arm's
  own synchronous drain; the clock stops after the last of them; THEN every
  `probes` cell of every op is read back out of the DOM.

  Answers a promise of `{:ms :write-ms :gap-ms :force-ms :ok? :oks}`. `:ms`
  is the WHOLE window; the three legs are sums across the `k` ops, so a
  per-operation figure is any of them divided by `k`. `:oks` is one flag
  per op and `:ok?` is all of them; the tally banks `k` writes.

  ## ONE CLOCK OVER k OPERATIONS

  Chrome clamps `performance.now()` to 100 µs. A witness whose window is
  four quanta wide publishes a range that is mostly quantisation — the P0
  converged bulk-narrow row read four estimates of 1.0405 / 1.1556 /
  1.1738 / 1.1972 with one minimum of exactly 1.0000, which is the quantum
  wearing a null result's clothes rather than a tie (rf2-zb3qg). Timing `k`
  operations as ONE sample lifts the window clear of the clamp, and it is
  NOT the same as summing `k` separately-clamped readings, which quantises
  `k` times and adds the errors. [[mount-batch!]] does this for mounts and
  `hd8-rows/window-of` proved it for HD-008's narrow write (rf2-9zysg);
  this is that shape lifted into the shared lane rather than a third copy
  of it.

  ## WHY THE READ-BACK SURVIVES THE BATCHING

  A batched window cannot verify each write in the turn its own drain ran
  in, because there is only one clock. It verifies all `k` after the clock
  stops — and that is not the weakening it looks like:

    NO MACROTASK RUNS INSIDE THE WINDOW. Every turn between the first
    write and the last drain is a MICROTASK. The fault this read-back
    exists to catch is a commit React has PARKED at the default lane,
    which is scheduled through the Scheduler's `MessageChannel` — a
    MACROTASK — so it cannot land inside the window however many
    microtasks pass. A parked commit is still parked when the read-backs
    run, and all `k` read UNVERIFIED.

  What the batch relaxes is microtask-scale lateness: an early op gets up
  to `k - 1` extra microtask turns before it is read back. The unbatched
  window already tolerates exactly one such turn by construction — that IS
  the yield — so this is a difference of degree on a tolerance the
  instrument already grants, not a new blind spot. It belongs in the row's
  stated method, and every caller that batches states it.

  Each op must therefore carry its OWN value and its OWN cell, so that no
  read-back can be satisfied by a neighbour's commit. That is the caller's
  obligation and it is why `ops` carries `val` per op rather than one
  value for the batch.

  ## THE WAIT BELONGS TO THE ARM'S SCHEDULER, NOT TO THE HARNESS

  See [[verified-write!]]. In the yielding shape the yield is preserved PER
  OPERATION and never batched away: the next write starts in the turn the
  previous drain finished in, so a `k`-op window holds exactly `k` harness
  turns rather than `2k`."
  [t {:keys [arm container]} ops]
  (let [ops        (vec ops)
        verify-all (fn []
                     (mapv (fn [[_ val probes]]
                             (every? #(= (str val) (text-at container %)) probes))
                           ops))
        bank!      (fn [oks]
                     (swap! t (fn [{:keys [of bad]}]
                                {:of  (+ of (count oks))
                                 :bad (+ bad (count (remove identity oks)))}))
                     oks)
        answer     (fn [ms write-ms gap-ms force-ms]
                     (let [oks (verify-all)]
                       (bank! oks)
                       {:ms       ms
                        :write-ms write-ms
                        :gap-ms   gap-ms
                        :force-ms force-ms
                        :ok?      (every? identity oks)
                        :oks      oks}))]
    (if (= (:scheduler arm) :microtask)
      ;; Write, then drain, with nothing between them: the substrate's queue
      ;; is filled synchronously by the write and is still there when the
      ;; drain opens its boundary, so the commit lands INSIDE the window.
      ;; The whole batch is one synchronous run, so not even a microtask
      ;; separates a drain from the next write.
      ;;
      ;; `:prev` starts at the window's own `t0` and every leg boundary IS a
      ;; clock read the unbatched window already took — at k = 1 this is
      ;; three `now-ms` calls in the same three places, which is what makes
      ;; the general form exactly the special one.
      (let [t0 (now-ms)
            {:keys [prev write force]}
            (reduce (fn [{:keys [prev write force]} [i val _]]
                      ((:write! arm) i val)
                      (let [w (now-ms)]
                        ((:force! arm))
                        (let [f (now-ms)]
                          {:prev f :write (+ write (- w prev)) :force (+ force (- f w))})))
                    {:prev t0 :write 0.0 :force 0.0}
                    ops)]
        (js/Promise.resolve (answer (- prev t0) write 0.0 force)))
      (let [t0 (now-ms)]
        (letfn [(run [remaining {:keys [prev write gap force] :as acc}]
                  (if (empty? remaining)
                    (js/Promise.resolve (answer (- prev t0) write gap force))
                    (let [[i val _] (first remaining)]
                      ((:write! arm) i val)
                      (let [w (now-ms)]
                        (-> (js/Promise.resolve nil)
                            (.then (fn [_]
                                     (let [g (now-ms)]
                                       ((:force! arm))
                                       (let [f (now-ms)]
                                         (run (next remaining)
                                              (assoc acc
                                                     :prev  f
                                                     :write (+ write (- w prev))
                                                     :gap   (+ gap (- g w))
                                                     :force (+ force (- f g)))))))))))))]
          (run (seq ops) {:prev t0 :write 0.0 :gap 0.0 :force 0.0}))))))

(defn verified-write!
  "Write, wait the way THIS ARM'S SCHEDULER requires, force the arm's own
  synchronous drain, stop the clock — THEN read the written cell back out
  of the DOM.

  Answers a promise of `{:ms :write-ms :gap-ms :force-ms :ok?}` and banks
  the verification in `t`.

  The read-back is inside the sample's own window, not a spot check at
  the end: a window whose commit lands after the clock stops reads the
  OLD value and its milliseconds are a measurement of nothing. The
  recorded fault is 1,320 of 1,320 writes accepted by a clock that never
  looked at the page.

  ## THE WAIT BELONGS TO THE ARM'S SCHEDULER, NOT TO THE HARNESS

  An arm declares `:scheduler` — the family its OWN render queue is
  scheduled on — and gets the window that family needs. This is the
  general form of rf2-b69lw, which repaired it inside HD-008 only;
  `hd8-rows/window-of` is these same two shapes, and this is deliberately
  the same shape lifted rather than a second mechanism (rf2-pq7d8).

    `:scheduler :microtask`   write, then drain, with NOTHING between
                              them. `:gap-ms` is 0.0 and means it.

    anything else, or the
    key absent                write, yield ONE microtask, drain. Today's
                              window, unchanged — which is why no arm
                              that does not declare `:microtask` moves.

  ONE FIXED YIELD CANNOT SERVE BOTH FAMILIES, and that is the whole of
  this. The yield is load-bearing for an arm whose notification is queued
  somewhere ELSE: stock Reagent's `reagent.impl.batching` schedules its
  component queue on `requestAnimationFrame`, so the queue is still full
  a microtask later and the drain finds the work. It is FATAL for an arm
  whose notification is queued on the very queue the harness yields to.
  `reagent2.impl.batching` (reagent-slim) is microtask-based, so the
  yield hands it the commit FIRST: it issues its `forceUpdate` outside
  any `flushSync` boundary, React parks the work at the default lane, and
  the drain that follows finds nothing to commit. The arm then reads `N
  unverified of N` against a DOM that is merely LATE — and, with the
  read-back suppressed, `0.16–0.50x` the floor from a page that never
  changed. rf2-z3vlz pinned it against a standalone rig and
  `docs/design/hicasso/studio/slim-non-reactive-arm-diagnosis.md` carries
  the evidence.

  The two shapes do not bill the same wait, so an arm measured through
  one is not like-for-like against an arm measured through the other
  until the harness microtask is priced against the same clock —
  `hd8-rows/yield-cost!` does exactly that, outside every arm's window,
  and publishes it beside the write rows. `:gap-ms` is reported per arm
  for the same reason: splitting the window is what lets a reader see how
  much of a ratio is the reactive leg and how much is React.

  `probes` is a SEQ of cell indices and ALL of them must hold the written
  value. A broad write changes every cell, so verifying one of them
  verifies almost nothing: the recorded fault is a commit that landed
  outside the window, and a stale page can still have one fresh cell in
  it from the previous write. The rotating probe plus the far end of the
  grid is the cheapest read that a partial commit cannot satisfy.

  ## `k` OPERATIONS UNDER ONE CLOCK, AND WHY THE READ-BACK SURVIVES IT

  [[verified-writes!]] is the general form and this is its `k = 1` case,
  byte-for-byte: the same clock reads at the same boundaries, the same one
  microtask between write and drain, the same read-back after the clock
  stops. Chrome's 100 µs clamp is why the general form exists — a witness
  whose window is 4 quanta wide publishes a range that is mostly quantum —
  and `lane/mount-batch!` has done exactly this for mounts since the lane
  landed."
  [t mnt i val probes]
  (verified-writes! t mnt [[i val probes]]))

(defn bulk-probes
  "The probe seq for a BROAD write over `n` cells: a cell that ROTATES with
  `rotor`, the far end of the grid, and cell 0.

  A broad write changes every cell, so verifying one of them verifies
  almost nothing — a page left stale by a commit that landed outside the
  window can still carry one fresh cell from the PREVIOUS write, and a
  single fixed probe accepts it. Rotating one probe means a stale page has
  to have been stale in the same place twice; the far end means a partial
  commit that got as far as the front of the grid is caught.

  Stated once, here, because it is a RULE and not an argument list: HD-008
  probed cell 0 alone on its bulk row while the P0 arm three files away
  probed three cells, which is the shape of divergence this lane exists to
  prevent (rf2-f5roa).

  A NARROW write is the other case and does not use this: it changes
  exactly one cell, so its probe seq is that cell and nothing else."
  [rotor n]
  [(mod rotor n) (dec n) 0])

;; ---------------------------------------------------------------------------
;; The positive control
;; ---------------------------------------------------------------------------

(defn control-verdict
  "Adjudicate a positive control: a STATED prediction against a measured
  range.

  `predicted` is a number the control's own arithmetic produces before
  the run — the element count doubles, so the work doubles — and
  `measured` is `{:min :max :mean}`. `slack` is how far the measured
  range may sit from the prediction and still count as the instrument
  having seen what it predicted; it is generous on purpose, because the
  claim being made is `THE INSTRUMENT HAS SIGNAL`, not `THE MODEL IS
  EXACT`. A control whose measured range does not contain a value within
  `slack` of the prediction means the instrument cannot see a change it
  predicts, and nothing else it measured is worth reading.

  Answers `{:rule :predicted :measured :slack :ok? :why}` — published on
  every run, passing or not, because a control quoted only when it passes
  is not a control.

  ## KNOWN DEFECT: this rule is WEAKER than HD-008's, and the two
  ## disagree on a row that is already published (rf2-egdaq)

  `:ok?` asks whether the measured range OVERLAPS the ±`slack` band.
  `hd8-rows/positive-control!` asks whether EVERY ROUND sits INSIDE it,
  and argues the stricter reading explicitly: a control whose worst round
  is wrong has caught something, and letting a good round vouch for a bad
  one is how an instrument stops being one. rf2-egdaq settled that
  disagreement on 2026-08-21, and it settled as a SPLIT, one rule per
  instrument: the HEAP arm's ten published figures were re-adjudicated
  under the strict rule and all ten pass; the CLOCK arm REFUSED strict
  under the 2026-07-31 quantum ruling set out below, and THAT REFUSAL
  STANDS. So a caller must not read `:ok?` as though it were the strict
  answer — which is why the map
  carries `:rule :overlap`, so a published record says which rule
  adjudicated it rather than leaving a reader to assume the other.
  [[control-verdict-strict]] is that other rule, spelled and callable.

  ## Why the defective rule STANDS here anyway (ruling, 2026-07-31)

  Tightening THIS function was adjudicated and refused. rf2-6i0i2's
  balanced ensemble re-adjudicated 80 controls: 80 of 80 pass under
  overlap, 64 of 80 under the strict reading — and every miss falls on a
  row whose control leg is a handful of Chrome's 100 µs
  `performance.now()` quanta, every miss is LOW, and four of them miss by
  0.0014 on a two-quantum floor. A rule that refuses a fifth of its
  controls by landing on the clock quantum is measuring RESOLUTION, not
  correctness. So the overlap rule stands for clamp-limited legs, no
  published row was re-adjudicated, and a pass here is a pass UNDER
  OVERLAP rather than a claim that every round sat inside the band.

  The row this defect was filed over stands on its page under that
  ruling:

      docs/design/hicasso/studio/p0-converged-witness-set.md
      M2 mount, UIx segment — predicted 1.9412, slack 0.25, so the band
      is [1.4559 – 2.4265]. The published range is [1.333 – 2.000]: its
      worst round sits 8.4% BELOW the band's floor and it carries a ✅
      only because a good round vouched for a bad one. Its legs are
      coarse — a handful of quanta — which is the whole reason the ruling
      let it stand rather than re-adjudicating it.

  ## And the condition under which this rule does NOT apply

  That ruling named its own revisit trigger: a BATCHED window lifting the
  legs clear of the quantum. [[control-verdict-strict]] is that rule, and
  a caller reading milliseconds against a 0.1 ms quantum wants it — there
  a round outside the band is not the clock."
  [predicted {:keys [min max mean] :as measured} slack]
  (let [lo (* predicted (- 1.0 slack))
        hi (* predicted (+ 1.0 slack))
        ok? (and (<= min hi) (>= max lo))]
    {:rule      :overlap
     :predicted predicted
     :measured  measured
     :slack     slack
     :ok?       ok?
     :why       (if ok?
                  (str "predicted " (.toFixed predicted 3) "x, measured "
                       (.toFixed mean 3) "x [" (.toFixed min 3) "–" (.toFixed max 3)
                       "] — the range meets the prediction within ±"
                       (.toFixed (* 100.0 slack) 0) "%")
                  (str "predicted " (.toFixed predicted 3) "x, measured "
                       (.toFixed mean 3) "x [" (.toFixed min 3) "–" (.toFixed max 3)
                       "] — DISJOINT from ±" (.toFixed (* 100.0 slack) 0)
                       "% of the prediction; the instrument did not see a change "
                       "its own arithmetic says it must, so no figure in this run "
                       "is reportable"))}))

(defn control-verdict-strict
  "Adjudicate a positive control the way HD-008 does: EVERY ROUND inside
  the ±`slack` band around `predicted`, round by round.

  `per-round` is ONE MEASURED VALUE PER ROUND. Where the prediction is a
  RATIO — `:ctl-2x` performs the judged arm's own operation twice inside
  one window, so it predicts 2.00x by construction rather than by model —
  that is `(:per-round (ratio-between ratios :ctl-2x <judged>))`, which
  pairs each round with its OWN denominator. An aggregate adjudication
  cannot answer the question this rule asks: a cross-round prediction
  against a cross-round range never puts a round beside its own
  denominator, so it cannot tell a control that held every round from one
  that held on average.

  Answers `{:rule :every-round :predicted :slack :band :per-round
  :measured :stated? :outside :ok? :why}`. `:outside` NAMES each round
  that missed and by how much, because an operator told only `FAILED`
  goes looking at the arms; `:per-round` is carried into the record so a
  later reader can re-adjudicate the run under either rule WITHOUT
  re-running the window — which is the durability hole rf2-egdaq's audit
  of PR #8326 found, and the reason the three runs it audited cannot now
  be re-adjudicated at all.

  `:stated?` is the other half of a control that can go red. A band built
  on a prediction of zero or less is cleared by any reading whatever, and
  the walk profile shipped exactly that failure (`rf2-1huc`, merged-PR
  audit #8149): a control whose own prediction has gone vacuous reports
  that it saw what it never predicted. A control with no rounds is the
  same thing said with no data, so both refuse here.

  ## Which of the two rules a caller wants

  [[control-verdict]] adjudicates on OVERLAP — the measured range need
  only meet the band — and STANDS for controls whose legs sit within a
  few of Chrome's 100 µs `performance.now()` quanta. The 2026-07-31
  ruling on rf2-egdaq keeps it there on evidence: of 80 controls in
  rf2-6i0i2's balanced ensemble, 80 pass under overlap and 64 under this
  rule, and every miss is a LOW excursion on a coarse-leg row. On those
  legs this rule reports the clock clamp as an instrument defect.

  THIS rule is for the case that same ruling named as its own revisit
  trigger — a batched window whose legs clear the quantum. `amp_merge_
  clock_app` reads ~4 ms on the judged arm and ~8 ms on the control
  against a 0.1 ms quantum, forty to eighty quanta clear of it, so a
  round outside a ±25% band there is not the clock. And a good round must
  not be allowed to vouch for a bad one."
  [predicted per-round slack]
  (let [vs      (vec per-round)
        lo      (* predicted (- 1.0 slack))
        hi      (* predicted (+ 1.0 slack))
        stated? (boolean (and (pos? predicted) (seq vs)))
        outside (if-not stated?
                  []
                  (vec (keep-indexed
                         (fn [i v]
                           (when-not (and (>= v lo) (<= v hi))
                             {:round    (inc i)
                              :measured (round4 v)
                              :off-by   (round4 (/ (- v (if (< v lo) lo hi))
                                                   predicted))}))
                         vs)))
        ok?     (and stated? (empty? outside))
        band    (str "predicted " (.toFixed predicted 3) "x ±"
                     (.toFixed (* 100.0 slack) 0) "% — band ["
                     (.toFixed lo 3) "–" (.toFixed hi 3) "]")]
    {:rule      :every-round
     :predicted predicted
     :slack     slack
     :band      [(round4 lo) (round4 hi)]
     :per-round vs
     :measured  (summarise vs)
     :stated?   stated?
     :outside   outside
     :ok?       ok?
     :why       (cond
                  (not (pos? predicted))
                  (str "REFUSED — the control states no prediction ("
                       (.toFixed (double predicted) 3) "). A band built on it is "
                       "cleared by any reading whatever, so nothing here is a "
                       "control and no figure in this run is reportable")

                  (empty? vs)
                  (str band " — REFUSED: no rounds were adjudicated. A control "
                       "with no data is not a control that passed")

                  ok?
                  (str band ", and all " (count vs) " rounds sit inside it — "
                       "EVERY round, not merely the range")

                  :else
                  (str band ", and " (count outside) " of " (count vs)
                       " rounds sit OUTSIDE it ("
                       (str/join ", " (map (fn [{:keys [round measured]}]
                                             (str "round " round " " (.toFixed measured 3)))
                                           outside))
                       ") — a control whose worst round is wrong has caught "
                       "something, and no figure in this run is reportable"))}))

;; ---------------------------------------------------------------------------
;; The guard
;; ---------------------------------------------------------------------------

(defn self-test!
  "Run the shared arm-order self-test. A harness that gets `false` must
  measure nothing — the copy of the rule it is about to rely on no longer
  behaves like the one the `.cjs` drivers use."
  []
  (guard/print-self-test!))

(defn guard!
  "Adjudicate `samples` and print the report. `:refuse?` is what the
  driver acts on, and `run.cjs` turns it into exit code 2."
  ([samples] (guard! samples nil {}))
  ([samples title] (guard! samples title {}))
  ([samples title opts]
   (let [v (guard/verdict samples (merge {:tolerance 0.10} opts))]
     (doseq [l (guard/report-lines v title)] (js/console.log l))
     v)))

;; ---------------------------------------------------------------------------
;; The `page.evaluate` boundary
;; ---------------------------------------------------------------------------
;;
;; Playwright carries plain data and plain `js/Error`s back out of
;; `page.evaluate`. A CLJS `ex-info` is neither, so what the driver was
;; handed for an arm's own `fail!` was the MINIFIED TYPE NAME of the thing
;; that was caught:
;;
;;     [clock] FAILED: M1: page.evaluate: vj
;;
;; `vj` is `cljs.core/ExceptionInfo` under `:advanced`. The `:rf.error/id`,
;; the message, the `:where` and the whole ex-data map — every field the
;; thrower wrote precisely so the reader would know what broke — were
;; destroyed at the boundary (rf2-029ed). An instrument that reports on
;; itself instead of on its subject cannot be debugged by anyone.
;;
;; The repair belongs HERE, on the page side, because this is the last
;; place `ex-message` and `ex-data` are still in vocabulary. Every front
;; door goes through [[legible-doors]] once, at construction, and any
;; throw leaves as an ordinary `js/Error` whose message names the door,
;; the id and the data. Nothing is special-cased to a particular id: what
;; the arm threw is what the driver prints.

(def ^:private ^:const ex-data-cap
  "How much of an ex-data map crosses the boundary. A payload can carry a
  DOM node or a whole app-db, and a report that is itself unreadable is
  the defect this repairs."
  2000)

(defn describe-throw
  "One line a driver can act on, for anything a front door can throw."
  [door e]
  (try
    (let [d (ex-data e)]
      (if (some? d)
        (let [s (pr-str d)]
          (str door " threw " (or (:rf.error/id d) "an ex-info with no :rf.error/id")
               " — " (ex-message e)
               " — ex-data " (if (> (count s) ex-data-cap)
                               (str (subs s 0 ex-data-cap) " …(truncated)")
                               s)))
        (str door " threw " (or (ex-message e) (str e)))))
    (catch :default e2
      ;; The reporter must not become the fault. Whatever defeated the
      ;; printer, the driver still gets the door and a reason.
      (str door " threw something whose description itself failed: " (.-message e2)))))

(defn legible-doors
  "Wrap every function on a front-door `#js {}` so an `ex-info` reaching
  `page.evaluate` arrives as a plain `js/Error` that names the fault.
  Returns the same object, mutated in place."
  [^js door-obj]
  (doseq [k (js/Object.keys door-obj)]
    (let [f (aget door-obj k)]
      (when (fn? f)
        (aset door-obj k
              (fn [& args]
                (try
                  (apply f args)
                  (catch :default e
                    (throw (js/Error. (describe-throw k e))))))))))
  door-obj)

;; ---------------------------------------------------------------------------
;; Publication
;; ---------------------------------------------------------------------------

(defn runtime-label
  "The runtime a figure was taken on, carried BESIDE the figure. A ratio
  without its runtime is a number without a denominator."
  []
  {:user-agent   (when (exists? js/navigator) (.-userAgent js/navigator))
   :optimizations :advanced
   :goog-debug   ^boolean goog/DEBUG
   :hardware-concurrency (when (exists? js/navigator) (.-hardwareConcurrency js/navigator))
   :device-memory        (when (exists? js/navigator) (.-deviceMemory js/navigator))})

(defn record!
  "Park one record on `window.HICASSO_RESULTS` for the driver to read, and
  echo it to the console as EDN."
  [k v]
  (let [acc (or (.-HICASSO_RESULTS js/window) #js {})]
    (aset acc (name k) (pr-str v))
    (set! (.-HICASSO_RESULTS js/window) acc)
    (js/console.log (str ";; HICASSO " (name k) "\n" (pr-str v)))
    v))

(defn fail!
  "Record a fatal reason. The driver exits non-zero and publishes nothing
  as measured."
  [why]
  (set! (.-HICASSO_ERROR js/window) (str why))
  (js/console.error (str ";; HICASSO FAILED — " why))
  nil)

(defn done! []
  (set! (.-HICASSO_DONE js/window) true)
  (js/console.log ";; HICASSO DONE")
  nil)

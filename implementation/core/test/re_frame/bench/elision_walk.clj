(ns re-frame.bench.elision-walk
  "What does the wire walk cost a dev event on a CLASSIFIED frame?

  Once a frame carries any elision declaration, every `:db`-bearing trace
  (t1 `:rf.event/db-pending`, t2 `:rf.event/db-pending-post-flow`) projects
  the whole pending app-db through `re-frame.elision`'s wire walker (Spec
  009 §Size elision in traces, Spec 015 §Implementation notes). This
  namespace prices that projection, in ONE process, against a synthetic
  RealWorld-shaped app-db classified the way that example classifies its
  own: `[:auth :token]` and `[:auth :login-form :draft :password]`
  sensitive, nothing else declared.

  ## The variants

  Each variant replaces what the walk does with a subtree whose candidate
  declaration set is EMPTY, where no declared path can match at or below it:

    rebuild  descend and rebuild every map, vector and set, running the
             leaf's `:rf.warning/large-value-unschema'd` check on every
             string — the retired design, held as a paired control
    prune    return the subtree unchanged and nothing else, so the advisory
             sees only strings under a declared prefix
    shipped  the source as it stands: return the subtree unchanged and run
             the non-rebuilding size probe over it, so the advisory keeps its
             reach
    skip     return the whole db unchanged at the root — the event's floor
             with no walk at all

  `rebuild`, `prune` and `skip` are installed OVER the shipped decider,
  intercepting only the empty-candidate case, so each stays measurable
  beside the source whatever the source does there.

  Arms prefixed `W-` time `elide-wire-value` on the db alone, which is the
  t1 projection with no same-event classification effects. Arms prefixed
  `E-` time a whole `dispatch-sync` of a `:db`-returning event, so the
  walk's share of a dev event is `E-<variant>` against `E-SKIP-ADV`. `-ADV`
  runs with the advisory at its default threshold (16384 bytes), `-OFF`
  with it disabled (0).

  ## The instrument

  Allocation is exact TLAB accounting via
  `com.sun.management.ThreadMXBean/getThreadAllocatedBytes`, so it barely
  moves with machine load and is the primary figure. Wall time is reported
  beside it with its range across rounds and is load-sensitive. `CTRL`, a
  `long-array` of 1000 (16 B header + 8 B per element), must read 8016 B/op
  before any other figure is evidence.

  The plan runs in rounds whose arm order rotates, and reflects on odd
  rounds, so no arm always follows the same neighbour or sits at the same
  point in the run. Each figure is the p50 across rounds with the full
  round range.

  Before timing, the run checks what each variant RETURNS: all three
  produce `=` projections, the sensitive paths redact under all three,
  prune and shipped hand back every undeclared top-level subtree
  `identical?` to the input, and the advisory fires at the same paths under
  rebuild and shipped but at none under prune.

  Run it, from `implementation/core`:

      clojure -X:test re-frame.bench.elision-walk/run

  `-X` rather than `-M -m`: the `:test` alias's `:main-opts` start the test
  runner, and `-M` appends a command-line `-m` to them rather than replacing
  them.

  Environment: EW_ARTICLES (articles in the workload, default 300 — about
  2 MB printed), EW_ROUNDS (default 7), EW_OPS (operations per arm per
  round, default 10), EW_WARMUP (warm-up passes over the whole plan,
  default 3)."
  (:require [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [java.lang.management ManagementFactory]))

(set! *warn-on-reflection* true)

(def ^:private ^com.sun.management.ThreadMXBean tmx
  (ManagementFactory/getThreadMXBean))

(defn- alloc-bytes ^long []
  (.getThreadAllocatedBytes tmx (.getId (Thread/currentThread))))

(defn- env-int [k d] (Integer/parseInt (or (System/getenv k) (str d))))

(defn- p50 [xs] (let [v (vec (sort xs))] (nth v (quot (count v) 2))))

(def ^:private fid :rf/default)

(def ^:private declared-sensitive
  [[:auth :token] [:auth :login-form :draft :password]])

;; ---------------------------------------------------------------------------
;; workload — a RealWorld-shaped app-db with a loaded article list

(def ^:private words
  ["the" "frame" "event" "handler" "returns" "a" "new" "value" "for" "app-db"
   "and" "every" "subscription" "that" "reads" "it" "recomputes" "once" "when"
   "its" "inputs" "change" "otherwise" "nothing" "happens" "at" "all" "which"
   "is" "why" "views" "stay" "cheap" "even" "on" "large" "pages" "of" "data"])

(defn- text
  "Deterministic prose of about `n` characters."
  ^String [seed n]
  (let [r  (java.util.Random. (long seed))
        sb (StringBuilder.)
        nw (count words)]
    (while (< (.length sb) (long n))
      (.append sb ^String (nth words (.nextInt r nw)))
      (.append sb \space))
    (.toString sb)))

(defn- author [i]
  (let [u (mod i 200)]
    {:username  (str "user" u)
     :bio       (text (+ 9000 u) 160)
     :image     (str "https://example.test/avatars/" u ".png")
     :following (zero? (mod u 7))}))

(defn- workload-db [n]
  (let [slug #(str "article-" %)]
    {:auth     {:token      (text 7 220)
                :user       {:username "ada"
                             :email    "ada@example.test"
                             :bio      (text 8 200)
                             :image    "https://example.test/avatars/ada.png"}
                :login-form {:draft  {:email    "ada@example.test"
                                      :password "correct horse battery staple"}
                             :errors {}}}
     :articles {:list     (mapv (fn [i]
                                  {:slug            (slug i)
                                   :title           (text (+ 1000 i) 60)
                                   :description     (text (+ 2000 i) 140)
                                   :body            (text (+ 3000 i) 3000)
                                   :tag-list        (mapv #(str "tag" (mod (+ i %) 50)) (range 4))
                                   :created-at      "2026-10-21T09:00:00.000Z"
                                   :updated-at      "2026-10-21T09:30:00.000Z"
                                   :favorited       (even? i)
                                   :favorites-count i
                                   :author          (author i)})
                                (range n))
                :count    n
                :offset   0
                :loading? false}
     :comments (into {}
                     (map (fn [i]
                            [(slug i)
                             (mapv (fn [j]
                                     {:id         (+ (* 10 i) j)
                                      :body       (text (+ 5000 (* 10 i) j) 400)
                                      :created-at "2026-10-21T10:00:00.000Z"
                                      :author     (author (+ i j))})
                                   (range 4))]))
                     (range n))
     :profiles (into {} (map (fn [i] [(str "user" i) (author i)])) (range 200))
     :tags     (mapv #(str "tag" %) (range 50))
     ;; One undeclared string over the default threshold, so the advisory
     ;; has something to find.
     :editor   {:preview-html (text 9 40000)
                :draft        {:title "" :body "" :tag-list []}}
     :ui       {:route [:home] :page 1 :feed :global :filter nil}}))

(defn- census
  "Node counts by kind, and the printed size, of `v`."
  [v]
  (let [acc  (volatile! {:maps 0 :vectors 0 :strings 0 :other 0})
        bump (fn [k] (vswap! acc update k inc))]
    (letfn [(go [x]
              (cond
                (map? x)    (do (bump :maps) (run! go (vals x)))
                (vector? x) (do (bump :vectors) (run! go x))
                (string? x) (bump :strings)
                :else       (bump :other)))]
      (go v))
    (assoc @acc :printed-bytes (rf.elision/pr-str-bytes v))))

;; ---------------------------------------------------------------------------
;; the variants

(def ^:private source-decider @#'rf.elision/walk-decider)

(defn- variant-decider [variant]
  (case variant
    :shipped source-decider
    :skip    (fn [ctx] (assoc (source-decider ctx) :decide (fn [_ v] v)))
    (fn [ctx]
      (let [d        (source-decider ctx)
            decide   (:decide d)
            on-empty (case variant
                       :rebuild (fn [_] rf.elision/walk-recur)
                       :prune   identity)]
        (assoc d :decide (fn [[_ decl-paths :as state] v]
                           (if (empty? decl-paths)
                             (on-empty v)
                             (decide state v))))))))

(defn- install! [variant]
  (alter-var-root #'rf.elision/walk-decider (constantly (variant-decider variant))))

(defn- advisory! [on?]
  (rf.elision/configure! {:rf.egress/threshold-bytes (if on? 16384 0)}))

;; ---------------------------------------------------------------------------
;; rig

(def ^:private sink (volatile! nil))
(def ^:private db* (volatile! nil))
(def ^:private tick (volatile! 0))

(defn- walk-once [] (vreset! sink (rf.elision/elide-wire-value @db* {:frame fid})))

(defn- event-once []
  (rf/dispatch-sync [:bench/favorite (mod (vswap! tick inc) 300)]))

(defn- arm-ctrl [n]
  (dotimes [_ n]
    (let [a (long-array 1000)]
      (aset a 0 (long @tick))
      (vreset! sink a))))

(def ^:private arms
  (into [["CTRL" nil nil arm-ctrl]]
        (for [[kind f] [["W" walk-once] ["E" event-once]]
              [variant adv?] (cond-> [[:rebuild true] [:rebuild false]
                                      [:prune true] [:prune false]
                                      [:shipped true] [:shipped false]]
                               (= "E" kind) (conj [:skip true]))]
          [(str kind "-" (.toUpperCase (name variant)) (if adv? "-ADV" "-OFF"))
           variant adv?
           (fn [n] (dotimes [_ n] (f)))])))

(defn- run-arm [[_ variant adv? f] n]
  (when variant (install! variant) (advisory! adv?))
  (let [a0 (alloc-bytes)
        t0 (System/nanoTime)]
    (f n)
    (let [t1 (System/nanoTime)
          a1 (alloc-bytes)]
      {:bytes (quot (- a1 a0) n)
       :ns    (quot (- t1 t0) n)})))

(defn- slot-order [n r]
  (let [rot (vec (take n (drop (mod r n) (cycle (range n)))))]
    (if (odd? r) (vec (rseq rot)) rot)))

;; ---------------------------------------------------------------------------
;; correctness of the variants, checked before any timing

(defn- warnings-under [variant db]
  (let [seen (atom [])]
    (install! variant)
    (advisory! true)
    (rf.elision/clear-warning-cache!)
    (rf/register-listener! :trace ::warn
                           (fn [ev]
                             (when (= :rf.warning/large-value-unschema'd (:operation ev))
                               (swap! seen conj (-> ev :tags :path)))))
    (try
      (rf.elision/elide-wire-value db {:frame fid})
      @seen
      (finally
        (rf/unregister-listener! :trace ::warn)))))

(defn- check-variants [db]
  (let [variants   [:rebuild :prune :shipped]
        out        (into {} (for [v variants]
                              (do (install! v) (advisory! true)
                                  [v (rf.elision/elide-wire-value db {:frame fid})])))
        undeclared (remove #{:auth} (keys db))
        same?      (fn [v] (every? #(identical? (get db %) (get-in out [v %])) undeclared))
        redacted?  (fn [v] (every? #(= rf.privacy/redacted-sentinel (get-in out (into [v] %)))
                                   declared-sensitive))]
    {"rebuild = prune = shipped"                           (apply = (map out variants))
     "sensitive paths redact (rebuild prune shipped)"      (mapv redacted? variants)
     "undeclared subtrees identical? (rebuild prune shipped)" (mapv same? variants)
     "advisory paths, rebuild"                             (warnings-under :rebuild db)
     "advisory paths, prune"                               (warnings-under :prune db)
     "advisory paths, shipped"                             (warnings-under :shipped db)}))

;; ---------------------------------------------------------------------------

(defn- fmt-row [label {:keys [bytes ns]}]
  (let [b (p50 bytes) t (p50 ns)]
    (format "%-16s %12d B/op  [%d .. %d]   %10.1f us/op  [%.1f .. %.1f]"
            label b (apply min bytes) (apply max bytes)
            (/ t 1e3) (/ (apply min ns) 1e3) (/ (apply max ns) 1e3))))

(defn run
  "Build the workload, check the variants, then time every arm. Takes and
  ignores the `-X` argument map."
  [& _]
  (let [n-articles (env-int "EW_ARTICLES" 300)
        rounds     (env-int "EW_ROUNDS" 7)
        ops        (env-int "EW_OPS" 10)
        warmup     (env-int "EW_WARMUP" 3)
        db         (workload-db n-articles)]
    (rf.registrar/clear-all!)
    (reset! rf.frame/frames {})
    (rf/init! rf.substrate.plain-atom/adapter)
    (rf/make-frame {:id fid})
    (rf/reg-classification :bench/declared {:sensitive declared-sensitive})
    (rf/with-frame fid
      (rf/reg-event :bench/init
                    (fn [_ [_ v]] {:db v}))
      (rf/reg-event :bench/favorite
                    (fn [{:keys [db]} [_ i]]
                      {:db (update-in db [:articles :list i :favorites-count] inc)}))
      (try
        (rf/dispatch-sync [:bench/init db])
        (vreset! db* db)
        (println "debug-enabled?" rf.interop/debug-enabled?
                 " java" (System/getProperty "java.version")
                 " articles" n-articles " rounds" rounds " ops" ops)
        (println "workload" (census db))
        (println "declared sensitive" declared-sensitive)
        (doseq [[k v] (check-variants db)] (println " check:" k "->" v))
        (dotimes [_ warmup]
          (doseq [arm arms] (run-arm arm ops)))
        (let [results (reduce
                        (fn [acc r]
                          (reduce (fn [acc i]
                                    (let [arm (nth arms i)
                                          {:keys [bytes ns]} (run-arm arm ops)]
                                      (-> acc
                                          (update-in [(first arm) :bytes] (fnil conj []) bytes)
                                          (update-in [(first arm) :ns] (fnil conj []) ns))))
                                  acc
                                  (slot-order (count arms) r)))
                        {}
                        (range rounds))
              b       (fn [l] (p50 (get-in results [l :bytes])))
              t       (fn [l] (p50 (get-in results [l :ns])))
              share   (fn [num den]
                        (format "%5.1f%% of bytes, %5.1f%% of wall"
                                (* 100.0 (/ (double (b num)) (b den)))
                                (* 100.0 (/ (double (t num)) (t den)))))]
          (println)
          (println (format "%-16s %12s %28s" "arm" "p50 alloc [range]" "p50 wall [range]"))
          (doseq [[label] arms]
            (println (fmt-row label (get results label))))
          (println)
          (println "W- arm as a share of the matching whole event:")
          (doseq [v ["REBUILD-ADV" "REBUILD-OFF" "PRUNE-ADV" "SHIPPED-ADV"]]
            (println (format "  W-%-12s / E-%-12s %s" v v (share (str "W-" v) (str "E-" v)))))
          (println "E-SKIP-ADV (no walk) as a share of each whole event:")
          (doseq [v ["REBUILD-ADV" "REBUILD-OFF" "PRUNE-ADV" "SHIPPED-ADV" "SHIPPED-OFF"]]
            (println (format "  E-SKIP-ADV / E-%-12s %s" v (share "E-SKIP-ADV" (str "E-" v))))))
        (finally
          (install! :shipped)
          (advisory! true))))))

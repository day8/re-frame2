(ns re-frame.bench.write-attribution
  "rf2-jr76s — WHERE do the bytes of a NARROW WRITE go?

  B8 (rf2-lcsjg) measured, in a browser, that changing ONE cell of a
  300-cell grid costs 478,787 bytes, of which **457,181 — 95.5% — is the
  WRITE LEG**: `frame/replace-app-db!` plus the subscription graph
  re-evaluating all 300 `:b6/cell` subscriptions to discover that 299 of
  them did not change. That figure is on record; nothing decomposes it.

  A number you cannot decompose you cannot attack, so this namespace
  decomposes it.

  ## Why this runs in NODE and not in a browser

  The published figure is a browser reading. The two things that make it
  what it is are the ENGINE (V8) and the BUILD (`:advanced` with
  `goog.DEBUG false`, so the Spec 009 trace seam is dead code). Node is
  the same V8 and the build here is the same `:advanced` +
  `goog.DEBUG false`, so the DCE profile — which is what decides whether
  the trace layer is in the picture at all — is identical.

  Node also gives one thing the browser cannot: `process.memoryUsage()`
  reports V8's used-heap counter UNQUANTISED. Chrome's `performance.memory`
  is bucketed to 100 KB unless `--enable-precise-memory-info` is passed,
  which is why B8 needed that flag; here there is no flag and no bucket.

  What does NOT transfer is the HOST: no React, no DOM, no commit. That is
  deliberate — the write leg B8 isolated is exactly the part that happens
  BEFORE React is involved (B8's `hc → h1` step, `((:write! arm) i val)`),
  and that is all this namespace measures.

  ## The instrument

  Same method as B8: if no garbage collection runs between two readings of
  V8's used-heap counter, the difference is the bytes allocated in between,
  garbage included, because nothing has reclaimed it yet.

  Here every SAMPLE is bracketed by an explicit `global.gc()` (node
  `--expose-gc`), and the sample is sized so its whole allocation fits in
  one nursery. A sample whose counter FELL is discarded and counted. Each
  arm reports p50 with the full range across accepted samples, per the
  standing rule that ranges are quoted and overlapping ranges mean
  INDISTINGUISHABLE.

  ## The control

  A control whose size is asserted rather than checked has already been
  wrong twice on this surface (B7's `'x'.repeat(n)` string that read as six
  kilobytes; B8's `8 B/double` prediction that measured 16.002). So the
  control here is read as a SLOPE across two decades, which cancels every
  constant and every header:

    `.slice()` of a PACKED double-element JS array of D elements allocates a
    `FixedDoubleArray` of D unboxed 8-byte slots plus a fixed header. V8
    never pointer-compresses a double, so the slope is **8.000 B/element
    exactly**, independent of the pointer-compression regime the node build
    happens to use — which a `FixedArray`-of-SMI control would NOT have been
    (4 B/slot compressed, 8 uncompressed).

  Predicted and measured are both printed. If they disagree, suspect the
  control first, and report the miss either way.

  ## The ladder

  Every arm below is a strict subset of the one under it, so subtracting
  neighbours attributes bytes to a layer rather than to a guess.

    MKDB      the application's own work — `(update db :cells assoc i v)`,
              the value B6's narrow `write!` builds before it writes
    BAREATOM  + `reset!` on a plain `clojure.core/atom` with no watches
    SPINE0    + the substrate's `replace-container!` — `with-epoch`,
              the reset, the epoch close, an empty drain — with NO
              derived values wired to the container
    RFWRITE-0 + `frame/replace-app-db!`'s own bookkeeping on a REAL frame
              with ZERO subscriptions: `commit-frame-transition!`, the
              partition carry-forward, the `changed` set, the two
              partition projections recomputing and fanning out
    RFWRITE-N + N held layer-1 subscriptions, each with one watcher, the
              shape a mounted application has

  `RFWRITE-N` is measured at four values of N and the SLOPE between them is
  the per-subscription cost — the number the bead is about. A slope cancels
  every constant in the ladder above it, so it is the one figure that does
  not depend on any of this attribution being right.

  ## The per-subscription pieces

  Then the slope itself is decomposed. Each arm re-walks ONE step of what a
  layer-1 subscription does when the graph re-evaluates it, through PUBLIC
  functions only, N times per call so it is directly comparable to the
  slope:

    P-BODY    the user's sub body — `(get-in db [:cells k])`
    P-EQDB    the memo wrapper's guard — `(= last-db db)` against the REAL
              app-db value, which is what decides whether the body runs
    P-SCOPE   `trace/with-handler-scope` + `handler-scope-from-meta`, which
              bracket every recompute and are NOT dev-gated
    P-EMIT    `trace/emit!` on its production path with the tag map the
              memo wrapper builds for it
    P-MEMO    the whole shipped memo wrapper
              (`subs.memo/make-layer-1-memoised-body`) invoked with a
              CHANGED db — the sum of the four above plus its own frame
    Q-SCHED   the epoch scheduler's queue bookkeeping — N `conj` onto the
              `queued` PersistentHashSet + the `queue` PersistentVector,
              then N `disj` as the drain consumes them
    P-VALS    the app-db projection's fan-out — `run!` over `(vals ws)` of
              an N-entry watcher map, the `case (count ws)` ≥2 branch

  Run it:

      npx shadow-cljs release ui-bench --config-merge '{...}'
      node --expose-gc out/write-attribution.js

  Environment: WA_N (subscriptions, default 300), WA_SAMPLES,
  WA_WARMUP, WA_ORDER=rev (run the arms back-to-front — if the answer
  survives that, arm order is not a confound)."
  (:require [goog.object :as gobj]
            [goog.string :as gstring]
            [goog.string.format]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            [re-frame.subs.memo :as subs-memo]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.spine :as spine]
            [re-frame.trace :as trace :include-macros true]))

;; ---------------------------------------------------------------------------
;; the counter

(defn- used-heap ^number []
  (.-heapUsed (js/process.memoryUsage)))

(defn- collect! []
  (when-let [g (gobj/get js/globalThis "gc")]
    (g)
    (g)))

(defn- env [k d]
  (or (gobj/get (.-env js/process) k) d))

(defn- env-int [k d] (js/parseInt (env k (str d)) 10))

;; ---------------------------------------------------------------------------
;; sinks — Closure is entitled to delete an expression nothing reads, and this
;; surface has already published a `layout-ms` of exactly 0.000 because a
;; property read was dropped. Every arm feeds one of these.

(defonce ^:private sink (volatile! 0))
(defonce ^:private sink2 (volatile! nil))

(defn- keep! [v] (vreset! sink (if v (inc @sink) @sink)) nil)

;; ---------------------------------------------------------------------------
;; the control — a slope, not a prediction

(defonce ^:private ctl-templates (volatile! {}))

(def ^:private ctl-double-ds
  "Two SMALL sizes a decade apart from the two LARGE ones. Small so the
  copied object is a fraction of V8's 256 KB heap page and the page-tail
  filler V8 inserts when the next object will not fit is a rounding error;
  large so the same slope can be re-read where that filler is 2% of the
  object and the two readings can be compared. A constant error and a scale
  error look different across the four."
  [100 200 1000 10000])

(def ^:private ctl-smi-ds
  "The SAME `.slice()` over a PACKED SMI array. Its slot width is the ONE
  thing that differs from the double control: V8 never compresses a double,
  but a tagged pointer/SMI slot is 4 bytes under pointer compression and 8
  without it. Node.js ships V8 with pointer compression DISABLED (it caps
  the heap at 4 GB); Chrome ships it ENABLED. So this pair does not merely
  check the instrument — it READS OFF which regime this process is in, which
  is what decides whether an absolute byte count here is comparable to a
  browser one."
  [100 200])

(defn- packed-doubles
  "A PACKED double-element JS array. Built by pushing doubles rather than
  `(.fill (js/Array. d) 0.5)`, which produces a HOLEY array whose `.slice()`
  does not take the packed fast path — the exact construction difference B8
  found itself on the wrong side of."
  [d]
  (let [a (array)]
    (dotimes [i d] (.push a (+ 0.5 i)))
    a))

(defn- packed-smis
  "A PACKED SMI-element JS array — same shape, integer elements, so
  `.slice()` yields a `FixedArray` of tagged slots rather than a
  `FixedDoubleArray` of raw doubles."
  [d]
  (let [a (array)]
    (dotimes [i d] (.push a (inc i)))
    a))

(defn- ctl-key [kind d] (str kind "-" d))

(defn- arm-ctl [kind d]
  (let [t (get @ctl-templates (ctl-key kind d))]
    (fn []
      (let [c (.slice t)]
        (vreset! sink (aget c 0)))
      nil)))

(defn- slope
  "Bytes per element between two control readings — the reading that cancels
  every header, every constant and every per-sample overhead."
  [b k1 d1 k2 d2]
  (/ (- (b k2) (b k1)) (- d2 d1)))

;; ---------------------------------------------------------------------------
;; the rig

(defonce ^:private rig (volatile! nil))
(defonce ^:private gen (volatile! 1000))

(defn- next-gen! [] (vswap! gen inc))

(def ^:private fid :wa/frame)

(defn- db-of [cells-n v] {:cells (vec (repeat cells-n v))})

;; ---- the ladder -----------------------------------------------------------

(defn- arm-mkdb []
  (let [{:keys [cells-n db-cell]} @rig
        v (next-gen!)
        i (mod v cells-n)]
    (vreset! sink2 (update @db-cell :cells assoc i v))
    nil))

(defn- arm-bareatom []
  (let [{:keys [cells-n db-cell bare]} @rig
        v (next-gen!)
        i (mod v cells-n)]
    (reset! bare (update @db-cell :cells assoc i v))
    nil))

(defn- arm-spine0 []
  (let [{:keys [cells-n db-cell spine-container]} @rig
        v (next-gen!)
        i (mod v cells-n)]
    (adapter/replace-container! spine-container
                                (update @db-cell :cells assoc i v))
    nil))

(defn- arm-rfwrite
  "`frame/replace-app-db!` on the rig's frame `k` (which carries `k`'s own
  number of held subscriptions). The value written is built the way B6's
  narrow `write!` builds it — read the frame's current app-db, assoc one
  cell — so this arm is B8's `write` leg and nothing else."
  [k]
  (let [{:keys [cells-n frames]} @rig
        f (nth frames k)
        v (next-gen!)
        i (mod v cells-n)]
    (frame/replace-app-db! f (update (frame/frame-app-db-value f) :cells assoc i v))
    nil))

;; ---- the per-subscription pieces ------------------------------------------

(defn- arm-p-body []
  (let [{:keys [n db-a]} @rig]
    (dotimes [k n]
      (keep! (get-in db-a [:cells k])))))

(defn- arm-p-eqdb []
  (let [{:keys [n db-a db-b]} @rig]
    (dotimes [_ n]
      (keep! (= db-a db-b)))))

(defn- arm-p-scope []
  (let [{:keys [n qids]} @rig]
    (dotimes [k n]
      (trace/with-handler-scope
        (trace/handler-scope-from-meta :sub (nth qids k) nil)
        (keep! true)))))

(defn- arm-p-emit []
  (let [{:keys [n qids qvs]} @rig]
    (dotimes [k n]
      (trace/emit! :rf.sub :rf.sub/run
                   {:rf.sub/id      (nth qids k)
                    :rf.sub/query-v (nth qvs k)
                    :frame          fid}))))

(defn- arm-p-memo []
  (let [{:keys [n memos db-a db-b]} @rig]
    (dotimes [k n]
      ;; Alternate the db so the guard NEVER hits — which is exactly what a
      ;; real write does to it.
      (keep! ((nth memos k) (if (even? @gen) db-a db-b))))
    (next-gen!)
    nil))

;; The scheduler's queue bookkeeping, in BOTH spellings, in one process on the
;; same 300 thunks — the paired-control discipline `read-attribution`'s
;; `RC-ATTACH` / `RC-CAND` established. `Q-SCHED` is the RETIRED form (a
;; volatile over a persistent vector + persistent set); `Q-SCHED-JS` is the
;; form that replaced it (a JS array + `js/Set`, mutated in place). Neither is
;; a call into `spine`: the whole point is to keep the retired expression
;; measurable beside the shipped one so the difference is a comparison of two
;; EXPRESSIONS and neither arm can drift under the other.

(defn- arm-q-sched []
  (let [{:keys [n thunks]} @rig
        [s q] (loop [k 0 s #{} q []]
                (if (< k n)
                  (let [t (nth thunks k)]
                    (recur (inc k)
                           (if (contains? s t) s (conj s t))
                           (if (contains? s t) q (conj q t))))
                  [s q]))]
    ;; the drain's `(vswap! queued disj thunk)`, once per entry
    (vreset! sink2 (loop [k 0 s s] (if (< k n) (recur (inc k) (disj s (nth q k))) s)))
    nil))

(defn- arm-q-sched-js []
  (let [{:keys [n thunks]} @rig
        s (js/Set.)
        q (array)]
    (dotimes [k n]
      (let [t (nth thunks k)]
        (when-not (.has s t) (.add s t) (.push q t))))
    ;; the drain's `(.delete queued thunk)`, once per entry
    (dotimes [k n] (.delete s (aget q k)))
    (vreset! sink2 s)
    nil))

;; The derived-value fan-out over a two-plus-subscriber watcher map, in BOTH
;; spellings. `P-VALS` is the RETIRED `(run! f (vals ws))`; `P-RKV` is the
;; `reduce-kv` walk that replaced it.

(defn- arm-p-vals []
  (let [{:keys [watch-map]} @rig]
    (run! (fn [w] (keep! w)) (vals watch-map))
    nil))

(defn- arm-p-rkv []
  (let [{:keys [watch-map]} @rig]
    (reduce-kv (fn [_ _ w] (keep! w) nil) nil watch-map)
    nil))

;; ---------------------------------------------------------------------------
;; the driver

(defn- measure
  "Run `f` `reps` times inside one collection-free sample, `samples` times.
  Answers `{:p50 … :lo … :hi … :accepted … :dropped …}` in bytes per CALL."
  [f reps samples]
  (let [acc (array)
        dropped (volatile! 0)]
    (dotimes [_ samples]
      (collect!)
      (let [h0 (used-heap)]
        (dotimes [_ reps] (f))
        (let [d (- (used-heap) h0)]
          (if (neg? d)
            (vswap! dropped inc)
            (.push acc (/ d reps))))))
    (.sort acc (fn [a b] (- a b)))
    (let [c (alength acc)]
      {:p50      (if (pos? c) (aget acc (js/Math.floor (/ c 2))) -1)
       :lo       (if (pos? c) (aget acc 0) -1)
       :hi       (if (pos? c) (aget acc (dec c)) -1)
       :accepted c
       :dropped  @dropped})))

(defn- calibrate
  "Pick a rep count that puts one sample near `target` bytes, so no sample
  outgrows the nursery and no sample is lost in the counter's own noise."
  [f target]
  (collect!)
  (let [h0 (used-heap)
        _  (dotimes [_ 4] (f))
        per (max 1 (/ (- (used-heap) h0) 4))]
    (-> (js/Math.round (/ target per)) (max 1) (min 4000))))

;; ---------------------------------------------------------------------------
;; rig construction

(defn- build-rig! [n cells-n ns-per-frame]
  ;; One sub-id per cell, exactly B6's `:b6/cell` shape but registered per
  ;; index so every subscription is a distinct cache entry with a distinct
  ;; body closure — the shape a real application's 300 boundaries have.
  (doseq [i (range cells-n)]
    (rf/reg-sub (keyword "wa" (str "cell" i))
                (fn [db _] (get-in db [:cells i]))))
  (let [qids  (mapv #(keyword "wa" (str "cell" %)) (range cells-n))
        qvs   (mapv vector qids)
        ;; One frame per subscription count, each with its OWN held
        ;; subscriptions, so the N-slope is read across four live graphs
        ;; rather than by subscribing and unsubscribing between samples.
        frames (mapv (fn [k]
                       (let [f (keyword "wa" (str "frame" k))]
                         (live-frame/make-frame {:id f})
                         (frame/replace-app-db! f (db-of cells-n 0))
                         f))
                     (range (count ns-per-frame)))
        holds  (mapv (fn [f cnt]
                       (binding [frame/*current-frame* f]
                         (mapv (fn [q]
                                 (let [r (subs/subscribe q)]
                                   ;; One watcher per reaction — the shape a
                                   ;; mounted boundary gives it. Without this
                                   ;; the derived value's fan-out takes its
                                   ;; zero-subscriber branch and the graph is
                                   ;; not the graph an application has.
                                   (adapter/subscribe-container r (fn [_ _] nil))
                                   ;; Establish the memo baseline the way a
                                   ;; first render does.
                                   (deref r)
                                   r))
                               (subvec qvs 0 cnt))))
                     frames ns-per-frame)
        db-a  (db-of cells-n 7)
        db-b  (update (db-of cells-n 7) :cells assoc (dec cells-n) 8)]
    (vreset! rig
             {:n        n
              :cells-n  cells-n
              :qids     qids
              :qvs      qvs
              :frames   frames
              :holds    holds
              :db-cell  (atom (db-of cells-n 0))
              :bare     (atom (db-of cells-n 0))
              :spine-container (adapter/make-state-container (db-of cells-n 0))
              :db-a     db-a
              :db-b     db-b
              ;; the shipped memo wrapper, one per subscription
              :memos    (mapv (fn [i]
                                (subs-memo/make-layer-1-memoised-body
                                  (fn [db _] (get-in db [:cells i]))
                                  (nth qids i) (nth qvs i) fid {}))
                              (range n))
              ;; distinct fn identities, the way the scheduler's queue holds
              ;; one flush thunk per dirty derived value
              :thunks    (mapv (fn [i] (fn [] i)) (range n))
              :watch-map (into {} (map (fn [i] [(gensym "w") (fn [] i)])) (range n))})))

;; ---------------------------------------------------------------------------

(defn- fmt [x] (.toFixed x 1))

(defn ^:export -main [& _]
  (let [n        (env-int "WA_N" 300)
        samples  (env-int "WA_SAMPLES" 40)
        warmup   (env-int "WA_WARMUP" 3)
        rev?     (= "rev" (env "WA_ORDER" ""))
        ns-per-frame [0 (js/Math.round (/ n 4)) (js/Math.round (/ n 2)) n]]
    (rf/init! (spine/make-react-adapter
                (spine/make-react-spine
                  {:substrate-name        "write-attribution"
                   :gensym-prefix-sub     "wa-sub-"
                   :gensym-prefix-derived "wa-derived-"
                   :gensym-prefix-use-sub "wa-use-sub-"
                   :use-memo              (fn [t _] (t))
                   :use-callback          (fn [t _] t)
                   :use-context           (fn [_] nil)})
                {:kind :rf.adapter/write-attribution :frame-provider nil}))
    (vreset! ctl-templates
             (into {} (concat (map (fn [d] [(ctl-key "DBL" d) (packed-doubles d)]) ctl-double-ds)
                              (map (fn [d] [(ctl-key "SMI" d) (packed-smis d)]) ctl-smi-ds))))
    (build-rig! n n ns-per-frame)
    (println (gstring/format ";; rf2-jr76s WRITE attribution — node V8, :advanced"))
    (println (gstring/format ";; debug-enabled? = %s   gc-exposed? = %s"
                     interop/debug-enabled?
                     (some? (gobj/get js/globalThis "gc"))))
    (println (gstring/format ";; node %s  V8 %s  pointer-compression=%s (Chrome ships it ON: a tagged slot is 4 B there, 8 B here)"
                     (.-node js/process.versions) (.-v8 js/process.versions)
                     (if (= 1 (gobj/getValueByKeys js/process "config" "variables" "v8_enable_pointer_compression")) "ON" "OFF")))
    (println (gstring/format ";; n=%d samples=%d warmup=%d order=%s frames=%s"
                     n samples warmup (if rev? "REVERSED" "forward")
                     (pr-str ns-per-frame)))
    ;; Agreement gate: every held subscription must read what the writer
    ;; wrote, or the graph is not wired and nothing below is evidence.
    (let [f (last (:frames @rig))
          v (next-gen!)]
      (frame/replace-app-db! f (update (frame/frame-app-db-value f) :cells assoc 3 v))
      (let [got (binding [frame/*current-frame* f] (deref (subs/subscribe [(keyword "wa" "cell3")])))]
        (println (gstring/format ";; agreement at cell3: wrote %d read %s -> %s"
                         v (pr-str got) (if (= v got) "AGREE" "*** DISAGREE ***")))
        (when-not (= v got)
          (throw (ex-info "graph not wired; measurement is meaningless" {:wrote v :read got})))))
    (let [plan (cond-> (into (vec (concat
                                    (map (fn [d] [(ctl-key "DBL" d) (arm-ctl "DBL" d) 1]) ctl-double-ds)
                                    (map (fn [d] [(ctl-key "SMI" d) (arm-ctl "SMI" d) 1]) ctl-smi-ds)))
                       [["MKDB"      arm-mkdb             1]
                        ["BAREATOM"  arm-bareatom         1]
                        ["SPINE0"    arm-spine0           1]])
                 true (into (map-indexed
                              (fn [k cnt]
                                [(str "RFWRITE-" cnt) (fn [] (arm-rfwrite k)) 1])
                              ns-per-frame))
                 true (into [["P-BODY"  arm-p-body  n]
                             ["P-EQDB"  arm-p-eqdb  n]
                             ["P-SCOPE" arm-p-scope n]
                             ["P-EMIT"  arm-p-emit  n]
                             ["P-MEMO"  arm-p-memo  n]
                             ["Q-SCHED" arm-q-sched n]
                             ["Q-SCHEDJS" arm-q-sched-js n]
                             ["P-VALS"  arm-p-vals  n]
                             ["P-RKV"   arm-p-rkv   n]]))
          plan (if rev? (vec (reverse plan)) plan)
          res  (reduce
                 (fn [acc [label f per]]
                   (dotimes [_ warmup] (f))
                   (let [reps (calibrate f 2000000)
                         r    (measure f reps samples)]
                     (println (gstring/format ";; %-12s reps=%-6d %12s B/call  [%s – %s]  per-unit %10s B  (%d ok, %d dropped)"
                                      label reps (fmt (:p50 r)) (fmt (:lo r)) (fmt (:hi r))
                                      (fmt (/ (:p50 r) per)) (:accepted r) (:dropped r)))
                     (assoc acc label (assoc r :per per))))
                 {}
                 plan)
          b    (fn [l] (:p50 (get res l)))]
      (println ";;")
      (println ";; CONTROLS")
      (doseq [d ctl-double-ds]
        (println (gstring/format ";;   DBL D=%-6d %12s B/copy" d (fmt (b (ctl-key "DBL" d))))))
      (doseq [d ctl-smi-ds]
        (println (gstring/format ";;   SMI D=%-6d %12s B/copy" d (fmt (b (ctl-key "SMI" d))))))
      (let [sm (slope b (ctl-key "DBL" 100) 100 (ctl-key "DBL" 200) 200)
            lg (slope b (ctl-key "DBL" 1000) 1000 (ctl-key "DBL" 10000) 10000)
            si (slope b (ctl-key "SMI" 100) 100 (ctl-key "SMI" 200) 200)]
        (println (gstring/format
                   ";;   DBL slope, SMALL pair (100->200)     %.4f B/double  predicted 8.0000  %+.2f%%"
                   sm (* 100.0 (/ (- sm 8.0) 8.0))))
        (println (gstring/format
                   ";;   DBL slope, LARGE pair (1000->10000)  %.4f B/double  predicted 8.0000  %+.2f%%"
                   lg (* 100.0 (/ (- lg 8.0) 8.0))))
        (println (gstring/format
                   ";;   SMI slope, SMALL pair (100->200)     %.4f B/slot    -> pointer compression %s"
                   si (if (< si 6.0) "ON (4 B/slot, Chrome-like)"
                          "OFF (8 B/slot, Node default)"))))
      (println ";;")
      (println ";; THE LADDER — bytes per WRITE")
      (doseq [[lbl v] [["MKDB      the application's own new-db value" (b "MKDB")]
                       ["BAREATOM  + reset! on a watch-free atom"      (b "BAREATOM")]
                       ["SPINE0    + the substrate's replace-container!" (b "SPINE0")]]]
        (println (gstring/format ";;   %-46s %10s B" lbl (fmt v))))
      (doseq [cnt ns-per-frame]
        (println (gstring/format ";;   %-46s %10s B"
                         (str "RFWRITE-" cnt "  frame/replace-app-db!, " cnt " subs")
                         (fmt (b (str "RFWRITE-" cnt))))))
      (println ";;")
      (let [lo (first ns-per-frame) hi (last ns-per-frame)
            slope (/ (- (b (str "RFWRITE-" hi)) (b (str "RFWRITE-" lo))) (- hi lo))]
        (println (gstring/format ";;   PER-SUBSCRIPTION SLOPE  %s B / sub / write" (fmt slope)))
        (println ";;")
        (println ";; WHERE THE PER-SUBSCRIPTION BYTES GO (each arm × n, reported per sub)")
        (doseq [l ["P-BODY" "P-EQDB" "P-SCOPE" "P-EMIT" "P-MEMO" "Q-SCHED" "Q-SCHEDJS" "P-VALS" "P-RKV"]]
          (let [v (/ (b l) n)]
            (println (gstring/format ";;   %-10s %10s B/sub   %5.1f%% of the slope"
                             l (fmt v) (* 100.0 (/ v slope))))))
        (println (gstring/format ";;   %-10s %10s B/sub"
                         "RESIDUAL" (fmt (- slope (/ (b "P-MEMO") n)
                                            (/ (b "Q-SCHEDJS") n) (/ (b "P-RKV") n)))))))
    (println (gstring/format ";; sink %s %s" @sink (some? @sink2)))))

(ns re-frame.bench.read-attribution
  "rf2-mvqwe — WHO pays the observation port's per-read allocation?

  rf2-21pck measured a 300-dependency Freehand render on the JVM and found
  `resolve-target` + `probe` to be 81.5% of the render leg's allocation —
  3733 B per dependency read, for a subscription whose whole body is
  `(get-in db [:items i])`. That cost lives in CORE's observation port and
  sub-cache, not in the view substrate, which raises the only question that
  can move a product decision: does an application reading re-frame
  subscriptions pay it REGARDLESS of view substrate?

  This namespace answers that by measuring, in ONE process against the REAL
  sub-cache and REAL live nodes, both readers side by side:

    PORT     `(probe (resolve-target {:query-v q}))`  — what a Freehand
             ViewCell does per reactive read (`cell/record-read!`, minus
             its stabilization and its ledger write, which rf2-21pck
             already priced at 2.0% and 16.6%).

    RGREAD   `@(subscribe q)`                          — what an idiomatic
             Reagent view body does per reactive read. Same frame, same
             sub-cache, same reaction. No view substrate is involved at
             all: it is the SUBSTRATE-FREE READER.

  Neither is a simulation. Both call the shipped public functions.

  ## The decomposition

  Each arm below is a strict subset of the one above it, so subtracting
  neighbours attributes bytes to a layer rather than to a guess:

    GETIN     the application's own work — `(get-in db [:items i])`
    RAWREACT  + a bare `interop/make-reaction` over the same container:
              the host reaction shell with NO re-frame sub machinery
    DEREF     + re-frame's signal graph — `@reaction` on a cached node.
              EVERY consumer of a re-frame subscription pays this, on
              every substrate, and it is what `probe` and `subscribe`
              both end in.
    RGSUB     `subscribe` WITHOUT the deref: frame resolution, cache
              lookup and the ref-count attach.
    RESOLVE   `resolve-target` alone.
    CACHEGET  the bare `(get @sub-cache q)` the port performs.
    PORT      the whole port call.

  ## The instrument, and why its controls are the shape they are

  Allocation is exact TLAB accounting via
  `com.sun.management.ThreadMXBean/getThreadAllocatedBytes`, which is why
  this harness is JVM-only. Seven instrument faults have been caught on
  this measurement surface, two of them recently: two readers that agreed
  to the byte because they were two doors onto ONE counter, and a
  positive control built from `'x'.repeat(n)` that read as six kilobytes
  on every reader.

  So the controls here are built from a type whose size is COMPUTABLE
  rather than assumed — a JVM `long-array` is a 16-byte header plus 8
  bytes an element, 8-aligned — and there are TWO of them, a decade
  apart, so a constant error and a scale error look different. They are
  reported as predicted-vs-measured and they are the first thing to read:
  if they do not land, no arm below them is evidence.

  `NOOP` is the fixed per-pass overhead every arm carries; every figure
  is reported both raw and net of it.

  ## What does NOT transfer to ClojureScript

  Byte counts do not, and neither does one behaviour: the JVM plain-atom
  adapter's derived value RECOMPUTES ON EVERY DEREF (there is no caching
  layer — see `re-frame.substrate.plain-atom`), while the CLJS spine
  caches and recomputes only when a source moved. So the DEREF arm here
  prices a read whose value MOVED. That is the right shape for the
  broad-update case every measured row is about (a write all 300
  subscriptions read moves all 300), and the wrong shape for a narrow
  one. Say which runtime a figure came from, always.

  Run it:

      clojure -M:test -m re-frame.bench.read-attribution

  Environment: RM_N (dependencies, default 300), RM_ITERS, RM_WARMUP,
  RM_ALLOC, RM_ORDER=rev (run the arms back-to-front — if the answer
  survives that, arm order and the JIT state each arm inherits are not
  confounds)."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  (:import [java.lang.management ManagementFactory]))

(set! *warn-on-reflection* true)

(def ^:private ^com.sun.management.ThreadMXBean tmx
  (ManagementFactory/getThreadMXBean))

(defn- alloc-bytes ^long []
  (.getThreadAllocatedBytes tmx (.getId (Thread/currentThread))))

(defn- env-int [k d] (Integer/parseInt (or (System/getenv k) (str d))))

(def ^:private fid :rf/default)

(defn- p50 [xs] (let [v (vec (sort xs))] (nth v (quot (count v) 2))))

;; ---------------------------------------------------------------------------
;; mutable rig, filled once by `run`

(def ^:private rig (volatile! nil))
(def ^:private sink (volatile! 0))

;; ---------------------------------------------------------------------------
;; controls — sizes computable from the JVM object model.
;; A `long-array` of n is 16 B of header + 8n B of payload, 8-aligned.

(def ^:private ctrl-small-n 100)
(def ^:private ctrl-large-n 1000)
(defn- ctrl-bytes [n elems] (* n (+ 16 (* 8 elems))))

(defn- arm-ctrl-small [n]
  (dotimes [_ n]
    (let [a (long-array ctrl-small-n)]
      (aset a 0 (long @sink))
      (vreset! sink (aget a 0)))))

(defn- arm-ctrl-large [n]
  (dotimes [_ n]
    (let [a (long-array ctrl-large-n)]
      (aset a 0 (long @sink))
      (vreset! sink (aget a 0)))))

(defn- arm-noop [_n] nil)

;; ---------------------------------------------------------------------------
;; the arms

(defn- arm-resolve [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (vreset! sink (if (obs/resolve-target {:query-v (nth qs k)}) 1 0)))))

;; FREEHAND's per-read port call.
(defn- arm-port [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (obs/probe (obs/resolve-target {:query-v (nth qs k)})))))

;; REAGENT's per-render read of a re-frame subscription. The substrate-free
;; reader: `@(rf/subscribe [:q])` is the whole of what a Reagent view body
;; does, and nothing here knows what a view is.
(defn- arm-rgread [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (vreset! sink (if (deref (subs/subscribe (nth qs k))) 1 0)))))

(defn- arm-rgsub [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (vreset! sink (if (subs/subscribe (nth qs k)) 1 0)))))

(defn- arm-cacheget [n]
  (let [{:keys [qs cache]} @rig]
    (dotimes [k n]
      (vreset! sink (if (:reaction (get @cache (nth qs k))) 1 0)))))

;; The bare signal-graph read: a reaction we already hold, so no lookup, no
;; resolution, no evidence map — the recompute and nothing else. This is the
;; floor under BOTH readers, and it is also the LOWER bound on what a Reagent
;; form-2 component pays, since such a component closes over the reaction once
;; and derefs it per render.
(defn- arm-deref [n]
  (let [rs (:held @rig)]
    (dotimes [k n]
      (vreset! sink (if (deref (nth rs k)) 1 0)))))

;; Below the sub machinery: the host reaction shell over the same container
;; computing the same body, and then the application's work alone.
(defn- arm-rawreact [n]
  (let [rs (:raw @rig)]
    (dotimes [k n]
      (vreset! sink (if (deref (nth rs k)) 1 0)))))

(defn- arm-getin [n]
  (let [db (deref (:db-container @rig))]
    (dotimes [k n]
      (vreset! sink (if (get-in db [:items k]) 1 0)))))

;; INSIDE `probe`, so the port's own share can be attributed rather than
;; guessed. Both arms below re-walk `probe`'s live branch through PUBLIC
;; functions only, and each is a strict prefix of the next:
;;
;;   PRELUDE  resolve, frame lookup, the frame-resolution scope, the
;;            registry lookup, the cache get. Everything before the node
;;            is touched.
;;   PRENODE  + the node deref itself.
;;
;; PORT - PRENODE is then the tail: `validate-target!`, the node-record
;; advance, and the evidence map. If these do not bracket PORT they are
;; not tracking `probe` and must not be quoted.
(defn- arm-probe-prelude [n]
  (let [{:keys [qs cache]} @rig]
    (dotimes [k n]
      (let [target (obs/resolve-target {:query-v (nth qs k)})
            fid*   (:frame-id target)
            query  (:query target)]
        (when (nil? (frame/frame fid*)) (throw (ex-info "no frame" {})))
        (live-frame/call-with-frame-resolution
          (live-frame/frame-resolution-target fid*)
          (fn []
            (registrar/lookup :sub (first query))
            (vreset! sink (if (:reaction (get @cache query)) 1 0))))))))

(defn- arm-probe-prenode [n]
  (let [{:keys [qs cache]} @rig]
    (dotimes [k n]
      (let [target (obs/resolve-target {:query-v (nth qs k)})
            fid*   (:frame-id target)
            query  (:query target)]
        (when (nil? (frame/frame fid*)) (throw (ex-info "no frame" {})))
        (live-frame/call-with-frame-resolution
          (live-frame/frame-resolution-target fid*)
          (fn []
            (registrar/lookup :sub (first query))
            (let [r (:reaction (get @cache query))]
              (vreset! sink (if (deref r) 1 0)))))))))

;; ---------------------------------------------------------------------------

(defn- census
  "How many of the n probes hit a LIVE cache node, and how many compute
  COLD? Decides whether the figure below is a node deref or a pure
  recompute — a different question with a different answer."
  [n]
  (let [qs (:qs @rig)]
    (reduce (fn [[live cold] k]
              (if (:live? (obs/probe (obs/resolve-target {:query-v (nth qs k)})))
                [(inc live) cold]
                [live (inc cold)]))
            [0 0]
            (range n))))

(defn run
  "Measure every arm and print the attribution. Answers the results map."
  [{:keys [n iters warmup alloc-iters reverse-order?]
    :or   {n 300 iters 400 warmup 400 alloc-iters 200 reverse-order? false}}]
  (.setThreadAllocatedMemoryEnabled tmx true)
  (let [sub-ids (mapv #(keyword "ra" (str "s" %)) (range n))
        qs      (mapv vector sub-ids)
        db-of   (fn [gen] {:items (mapv (fn [i] [gen i]) (range n))})
        gen     (volatile! 0)]
    (doseq [[i id] (map-indexed vector sub-ids)]
      (rf/reg-sub id (fn [db _] (get-in db [:items i]))))
    (live-frame/make-frame {:id fid})
    (frame/replace-app-db! fid (db-of 0))
    (println (format ";; debug-enabled? = %s  n=%d iters=%d warmup=%d alloc-iters=%d order=%s"
                     interop/debug-enabled? n iters warmup alloc-iters
                     (if reverse-order? "REVERSED" "forward")))
    (binding [frame/*current-frame* fid]
      ;; Hold n subscriptions the way a mounted application holds them, so
      ;; every node is live for the whole run — which is what makes `probe`
      ;; take its LIVE path rather than its cold-compute one, and what gives
      ;; DEREF something to deref.
      (let [held  (mapv subs/subscribe qs)
            src   (frame/app-db-container fid)
            raw   (mapv (fn [i] (interop/make-reaction (fn [] (get-in @src [:items i]))))
                        (range n))]
        (vreset! rig {:qs           qs
                      :cache        (:sub-cache (frame/frame fid))
                      :held         held
                      :raw          raw
                      :db-container src}))
      (let [[live cold] (census n)]
        (println (format ";; probe census: %d LIVE / %d COLD of %d" live cold n)))
      (vswap! gen inc)
      (frame/replace-app-db! fid (db-of @gen))
      ;; Every reader must return the value the writer just wrote, or the
      ;; arms are not reading the same thing and nothing below is comparable.
      (let [g       @gen
            probe-v (:value (obs/probe (obs/resolve-target {:query-v (nth qs 7)})))
            rg-v    (deref (subs/subscribe (nth qs 7)))
            dr-v    (deref (nth (:held @rig) 7))
            raw-v   (deref (nth (:raw @rig) 7))
            agree?  (= [g 7] probe-v rg-v dr-v raw-v)]
        (println (format ";; agreement at site 7, gen %d: probe %s rgread %s deref %s raw %s -> %s"
                         g (pr-str probe-v) (pr-str rg-v) (pr-str dr-v) (pr-str raw-v)
                         (if agree? "AGREE" "*** DISAGREE ***")))
        (when-not agree?
          (throw (ex-info "arms disagree; measurement is meaningless"
                          {:gen g :probe probe-v :rgread rg-v :deref dr-v :raw raw-v}))))
      (let [advance! (fn [] (vswap! gen inc)
                       (frame/replace-app-db! fid (db-of @gen)))
            measure
            (fn [label f]
              (dotimes [_ warmup] (advance!) (f n))
              (let [us (/ (p50 (vec (repeatedly iters
                                      (fn [] (advance!)
                                        (let [t0 (System/nanoTime)]
                                          (f n)
                                          (- (System/nanoTime) t0))))))
                          1000.0)
                    bs (double
                         (/ (loop [k 0 acc 0]
                              (if (< k alloc-iters)
                                (do (advance!)
                                    (let [a0 (alloc-bytes)]
                                      (f n)
                                      (recur (inc k) (+ acc (- (alloc-bytes) a0)))))
                                acc))
                            alloc-iters))]
                (println (format ";; %-9s %9.1f us %11.0f bytes %9.1f B/read"
                                 label us bs (/ bs (double n))))
                (flush)
                {:label label :us us :bytes bs}))
            plan [["NOOP"     arm-noop]     ["CTRL-S"   arm-ctrl-small]
                  ["CTRL-L"   arm-ctrl-large] ["RESOLVE" arm-resolve]
                  ["PORT"     arm-port]     ["CACHEGET" arm-cacheget]
                  ["DEREF"    arm-deref]    ["RGSUB"    arm-rgsub]
                  ["RGREAD"   arm-rgread]   ["RAWREACT" arm-rawreact]
                  ["GETIN"    arm-getin]   ["PRELUDE"  arm-probe-prelude]
                  ["PRENODE"  arm-probe-prenode]]
            res  (reduce (fn [acc [l f]] (assoc acc l (measure l f)))
                         {}
                         (if reverse-order? (reverse plan) plan))
            b    (fn [l] (:bytes (get res l)))
            noop (b "NOOP")
            net  (fn [l] (- (b l) noop))
            per  (fn [l] (/ (net l) (double n)))]
        (println ";;")
        (doseq [[lbl elems] [["CTRL-S" ctrl-small-n] ["CTRL-L" ctrl-large-n]]]
          (let [pred (double (ctrl-bytes n elems))]
            (println (format ";; CONTROL %-7s predicted %10.0f B   measured(net) %10.0f B   %+.3f%%"
                             lbl pred (net lbl)
                             (* 100.0 (/ (- (net lbl) pred) pred))))))
        (println ";;")
        (println (format ";; NOOP fixed per-pass overhead %10.0f B" noop))
        (doseq [l ["GETIN" "RAWREACT" "DEREF" "RGSUB" "RGREAD"
                   "RESOLVE" "CACHEGET" "PRELUDE" "PRENODE" "PORT"]]
          (println (format ";; %-9s raw %11.0f B   net %11.0f B   %9.1f B/read"
                           l (b l) (net l) (per l))))
        (println ";;")
        (println ";; ATTRIBUTION")
        (println (format ";;   PORT   (freehand's per-read)          %9.1f B/read" (per "PORT")))
        (println (format ";;   RGREAD (reagent's per-read, no substrate) %6.1f B/read  = %.1f%% of PORT"
                         (per "RGREAD") (* 100.0 (/ (net "RGREAD") (net "PORT")))))
        (println (format ";;   DEREF  (held reaction; reagent form-2)   %6.1f B/read  = %.1f%% of PORT"
                         (per "DEREF") (* 100.0 (/ (net "DEREF") (net "PORT")))))
        (println (format ";;   PORT - RGREAD (freehand-only increment)  %6.1f B/read  = %.1f%% of PORT"
                         (- (per "PORT") (per "RGREAD"))
                         (* 100.0 (/ (- (net "PORT") (net "RGREAD")) (net "PORT")))))
        (println ";;")
        (println ";; LAYERS - every re-frame reader pays the first three")
        (doseq [[lbl v] [["application work    (GETIN)"           (net "GETIN")]
                         ["host reaction shell (RAWREACT-GETIN)"  (- (net "RAWREACT") (net "GETIN"))]
                         ["re-frame sub graph  (DEREF-RAWREACT)"  (- (net "DEREF") (net "RAWREACT"))]]]
          (println (format ";;   %-38s %8.1f B/read  %5.1f%% of PORT"
                           lbl (/ v (double n)) (* 100.0 (/ v (net "PORT"))))))
        (println ";; INSIDE probe (public-fn re-walk of its live branch):")
        (doseq [[lbl v] [["resolve + lookups   (PRELUDE)"          (net "PRELUDE")]
                         ["the node deref      (PRENODE-PRELUDE)"  (- (net "PRENODE") (net "PRELUDE"))]
                         ["validate+record+evidence (PORT-PRENODE)" (- (net "PORT") (net "PRENODE"))]]]
          (println (format ";;   %-38s %8.1f B/read  %5.1f%% of PORT"
                           lbl (/ v (double n)) (* 100.0 (/ v (net "PORT"))))))
        (println ";; then ONE of:")
        (doseq [[lbl v] [["reagent: subscribe  (RGSUB)"           (net "RGSUB")]
                         ["freehand: resolve   (RESOLVE)"         (net "RESOLVE")]
                         ["freehand: probe shell (PORT-RESOLVE-DEREF)"
                          (- (net "PORT") (net "RESOLVE") (net "DEREF"))]]]
          (println (format ";;   %-38s %8.1f B/read  %5.1f%% of PORT"
                           lbl (/ v (double n)) (* 100.0 (/ v (net "PORT"))))))
        res))))

(defn -main [& _]
  ((test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
   (fn []
     (run {:n              (env-int "RM_N" 300)
           :iters          (env-int "RM_ITERS" 400)
           :warmup         (env-int "RM_WARMUP" 400)
           :alloc-iters    (env-int "RM_ALLOC" 200)
           :reverse-order? (= "rev" (System/getenv "RM_ORDER"))})))
  (shutdown-agents))

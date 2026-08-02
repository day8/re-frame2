(ns re-frame.bench.hicasso.read-profile-app
  "THE COLD-READ MOUNT TERM, PROFILED READ-BY-READ (rf2-6c237).

  rf2-y1jkm cut the interpreter walk ~39% and its closing decomposition
  moved the surviving mount gap off the walk: hicasso in-page 3.300 ms vs
  uix 1.900 ms on the acceptance shape, concentrated in the 141
  per-instance collector reads — each a cold `subs/subscribe-once`
  (subscribe + deref + unsubscribe per read per mount; cells only exist
  after commit). None of the prior instruments says where INSIDE one cold
  read the time goes. This entry answers that: the acceptance page's own
  141-read roster, performed by the shipping read path and by a family of
  single-phase ablations in one process, interleaved, plus the commit
  half (cell construction, reaction wiring, index write) and a per-op
  micro table.

  ## DIAGNOSTIC, not published

  The clock here is in-page `performance.now` over K roster passes per
  sample. It attributes cost BETWEEN phases of one cold read; it is not
  the clock of record and no figure from this file is a gate row. The
  published before/after stays with `census_clock_run.cjs` (raw
  TaskDuration, plumb-tared, same-run donors). Stated per the instrument
  canon so a reader cannot mistake a ratio here for a gated one.

  ## The roster under the knife, and the fidelity gates

  The profiled reads are HARVESTED, not transcribed: the real
  [[re-frame.bench.hicasso.shapes.large-template/page]] is mounted once
  and the read-set entry the mount resolved is read back through the
  runtime's own [[rt/last-reads]] — its key array IS the page's read
  sequence, in realization order, straight from the machinery under test.
  Boot is fatal unless the roster is exactly the page arithmetic's
  3 + 2 x 69 = 141 reads, all distinct, and the mounted page is the
  1,202-element acceptance page.

  Timed passes run through the runtime's public [[rt/render-body]] door —
  one door per pass, the window OUTSIDE the door — so a sample bills
  everything a mount bills per boundary render: the scratch reset, the
  generation fence's two basis reads, the read-set entry resolve, and the
  141 reads themselves. Every arm rides the identical door, so deltas
  subtract the door out.

  ## The arms (phase A — the render half)

  | arm           | one pass is                                            | what it prices |
  |---------------|--------------------------------------------------------|----------------|
  | `ship`        | 141 x `(sub q)` — the shipping collector read          | the whole render-side read term |
  | `local`       | faithful copy of the read shell + `subscribe-once`     | the FROZEN pre-rf2-6c237 path — the ablation baseline, validated against `ship` |
  | `no-shell`    | 141 x bare `subs/subscribe-once`                       | `local - no-shell` = the Hicasso shell (key alloc, scratch push, cells probe, entry-hit compare) |
  | `probe`       | 141 x the candidate: cache peek, else slice-memo'd `compute-sub-with-memo` against one frame-state snapshot | the no-churn cold read (the observation port's own cold-probe discipline) |
  | `probe-fresh` | 141 x `compute-sub` (fresh memo per read)              | `probe-fresh - probe` = the shared-memo economy (expected ~0 here: the roster is 141 DISTINCT layer-1 subs, no shared parents) |
  | `floor`       | 141 x registrar lookup + raw handler call on app-db    | the irreducible compute floor |
  | `warm`        | 141 x `(sub q)` with cells COMMITTED (a second frame)  | the steady-state pure-deref read, for scale |
  | `ctl2`        | 282 x bare `subscribe-once` (the roster twice)         | positive control, predicted 2.0 x `no-shell` |

  The ablation baseline is written IN THIS NAMESPACE and validated
  against the shipping path in the same process (the rf2-2rtt6.32
  discipline: a local arm timed against a foreign one compares call
  conventions as much as phases). `local` is deliberately the
  subscribe-once path even after rf2-6c237 lands its candidate, so the
  re-run of this instrument is an in-process before/after A/B.

  The `warm` arm reads a SECOND frame with the same seed, committed once
  at boot and held, because cells are global per (frame, query): a warm
  arm on the cold frame would warm every other arm's reads.

  ## The commit half (phase B — async, settled between samples)

  What the render's cold read deliberately does not pay, the commit does:
  one durable cell per unique key — `subs/subscribe`, the baseline deref,
  the value-change watch, the disposal hook, the `!cells` insert — plus
  the index mount and the whole-set `record-reads!`. Phase B prices that
  half through the runtime's own [[rt/commit-boundary!]] seam on four
  identically-seeded frames per window (window ~= 4 x 141 acquisitions,
  clear of the 100 us clock clamp), released and settled between samples
  with a residue equality gate.

  | arm         | one window is                                     | what it prices |
  |-------------|---------------------------------------------------|----------------|
  | `commit`    | 4 frames x `rt/commit-boundary!` on the harvested entry | the shipping commit half |
  | `c-local`   | faithful copy: cell mint + subscribe + baseline deref + watch + dispose hook + map insert + index | the ablation baseline, validated against `commit` |
  | `c-nowatch` | `c-local` minus add-watch + the disposal hook     | the watch wiring |
  | `c-nosub`   | `c-local` with `compute-sub` in place of subscribe + deref | the reaction build + cache insert (the compute is kept, priced by the swap) |
  | `c-noindex` | `c-local` minus `index/mount!` + `record-reads!`  | the index write |
  | `c-nomap`   | `c-local` minus the per-key cells-map insert      | the cell-map insert |
  | `b-build`   | 4 frames x 141 bare `subscribe` + deref (torn down sync, outside the window) | build + compute WITHOUT in-window dispose — beside `no-shell` it floors the render read's dispose/evict share |

  Every phase-B delta is quoted as `c-local - variant`, a floor on the
  phase (the stubs are not free). Teardown runs outside every window;
  a settle and a residue equality gate (runtime counters AND each probe
  frame's sub-cache emptiness) sit between samples.

  Owner bead: rf2-6c237. Driver: `run.cjs` with
  HICASSO_INIT_FN=re-frame.bench.hicasso.read-profile-app/-main."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as arm1-mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt :refer [sub]]
            [re-frame.bench.hicasso.front.sub-index :as idx]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.large-template :as lt]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]))

;; ---------------------------------------------------------------------------
;; Frames
;; ---------------------------------------------------------------------------

(def cold-frame ::cold)
(def warm-frame ::warm)
(def commit-frames [::c1 ::c2 ::c3 ::c4])

(defn- seed-frame! [id]
  (lt/make-frame! id)
  (lt/reseed! id)
  id)

;; ---------------------------------------------------------------------------
;; The harvest — the page's own roster, read back from the machinery
;; ---------------------------------------------------------------------------

(defn- harvest-roster!
  "Mount the REAL acceptance page on `frame-id`, read the entry the mount
  resolved, and answer `{:roster <js array of query-v> :elements n}`.
  Fatal unless the page is the 1,202-element page and the roster is the
  arithmetic's 141 distinct reads."
  [frame-id]
  (let [container (arm1-mount/fresh-container!)
        handle    (arm1-mount/root! container frame-id [lt/page {}])
        entry     (rt/last-reads)
        keys'     (.-keys ^js entry)
        n         (lane/element-count container)
        expected  (lt/element-arithmetic)
        roster    (let [a #js []]
                    (dotimes [i (alength keys')]
                      (.push a (nth (aget keys' i) 1)))
                    a)
        distinct-n (count (into #{} (array-seq roster)))
        expected-reads (+ 3 (* 2 lt/article-count))]
    (arm1-mount/unmount! handle)
    (when-not (= expected n)
      (throw (ex-info (str "harvest FAILED: mounted page has " n
                           " elements, expected " expected)
                      {:elements n :expected expected})))
    (when-not (and (= expected-reads (alength roster))
                   (= expected-reads distinct-n))
      (throw (ex-info (str "harvest FAILED: roster carries " (alength roster)
                           " reads (" distinct-n " distinct), expected "
                           expected-reads " distinct reads")
                      {:reads (alength roster) :distinct distinct-n})))
    {:roster roster :elements n}))

(defn- roster-census
  "What the 141 reads are made of — the denominator table."
  [^js roster]
  (let [by-id (reduce (fn [m q] (update m (nth q 0) (fnil inc 0)))
                      {} (array-seq roster))
        kinds (reduce (fn [m q]
                        (let [k (:input-kind (registrar/lookup :sub (nth q 0)))]
                          (update m k (fnil inc 0))))
                      {} (array-seq roster))]
    {:reads      (alength roster)
     :distinct   (count (into #{} (array-seq roster)))
     :by-sub-id  by-id
     :input-kinds kinds}))

;; ---------------------------------------------------------------------------
;; The door, and the window
;; ---------------------------------------------------------------------------

(def ^:private passes-per-sample
  "Roster passes inside ONE timing window, each through its own
  render-body door. Chrome clamps `performance.now` to 100 us; eight
  141-read passes hold the window in whole milliseconds, so the clamp is
  percent-level noise. Every pass is a fresh door, so a pass never sees
  another pass's per-render state — which is what keeps the shipping
  read COLD in every pass whichever read path ships."
  8)

(defn- timed-doors
  "One sample: K render-body doors on `frame-id`, `pass!` inside each,
  one clock around all K. Answers ms for the window."
  [frame-id pass!]
  (let [t0 (lane/now-ms)]
    (dotimes [_ passes-per-sample]
      (rt/render-body frame-id (fn [_] (pass!) [:span]) {}))
    (- (lane/now-ms) t0)))

;; ---------------------------------------------------------------------------
;; The faithful local copy of the pre-rf2-6c237 read (the frozen baseline)
;; ---------------------------------------------------------------------------
;;
;; `read-key!`'s shape at the commit this bead opened: sub-key mint, scratch
;; push, cells probe (always a miss on a cold mount), then the
;; subscribe-once crossing; after the body, the entry-hit compare (bucket
;; hash of the whole sequence + ordered pairwise compare). The copy carries
;; its OWN scratch and its own cached key array so the shipping runtime's
;; internals stay untouched, and it is validated against `ship` in the
;; ARMS table rather than assumed equivalent.

(def ^:private local-scratch #js [])
(def ^:private local-cells {})
(def ^:private ^:mutable local-entry-keys nil)

(defn- local-bucket-hash []
  (let [n (alength local-scratch)]
    (loop [i 0 h 1]
      (if (== i n)
        h
        (recur (inc i)
               (bit-or 0 (+ (bit-shift-left h 5) (- h)
                            (hash (aget local-scratch i)))))))))

(defn- local-entry-hit? []
  (let [ks local-entry-keys
        n  (alength ks)]
    (and (== n (alength local-scratch))
         (loop [i 0]
           (cond
             (== i n)                                true
             (= (aget ks i) (aget local-scratch i))  (recur (inc i))
             :else                                   false)))))

(defn- local-pass!
  "One frozen-path pass: the shell plus the subscribe-once crossing, then
  the entry-hit resolve the runtime performs after the body."
  [frame-id ^js roster]
  (set! (.-length local-scratch) 0)
  (let [n (alength roster)]
    (dotimes [i n]
      (let [q       (aget roster i)
            sub-key [frame-id q]]
        (.push local-scratch sub-key)
        (if-some [^js r (some-> ^js (get local-cells sub-key) (.-reaction))]
          @r
          (subs/subscribe-once q {:frame frame-id})))))
  (local-bucket-hash)
  (when (nil? local-entry-keys)
    (set! local-entry-keys (.slice local-scratch)))
  (local-entry-hit?))

;; ---------------------------------------------------------------------------
;; The candidate — the observation port's cold-probe discipline, per read
;; ---------------------------------------------------------------------------

(defn- probe-pass!
  "One candidate pass: per read, peek the frame's sub-cache and deref a
  live reaction without acquire/release churn; else compute PURE against
  ONE coherent frame-state snapshot through ONE slice memo (seeded with
  `subs/observation-opts-key` so an unregistered read emits the always-on
  `:rf.error/no-such-sub` exactly as the reactive build does). The
  snapshot and the memo are per PASS — the render-scoped lifetime the
  rf2-6c237 candidate resets at the top of every body run — and each read
  sits inside `call-with-frame-resolution`, the resolution seam
  `subscribe` itself reads through (and the read-time coalesced
  reprojection flush, without which a same-tick `reg-sub` is invisible)."
  [frame-id ^js roster]
  (let [frame-record (frame/frame frame-id)
        cache        (:sub-cache frame-record)
        pstate       #js {"fs" nil "memo" nil}
        n            (alength roster)]
    (dotimes [i n]
      (let [q (aget roster i)]
        (live-frame/call-with-frame-resolution
          frame-id
          (fn []
            (if-some [r (:reaction (get @cache q))]
              @r
              (let [fs   (or (unchecked-get pstate "fs")
                             (let [v (frame/frame-state-value frame-id)]
                               (unchecked-set pstate "fs" v)
                               v))
                    memo (or (unchecked-get pstate "memo")
                             (let [m (atom {subs/observation-opts-key
                                            {:frame frame-id}})]
                               (unchecked-set pstate "memo" m)
                               m))]
                (subs/compute-sub-with-memo q fs memo)))))))))

;; ---------------------------------------------------------------------------
;; The other render arms
;; ---------------------------------------------------------------------------

(defn- once-pass! [frame-id ^js roster]
  (let [n (alength roster)]
    (dotimes [i n]
      (subs/subscribe-once (aget roster i) {:frame frame-id}))))

(defn- fresh-memo-pass! [frame-id ^js roster]
  (let [fs (frame/frame-state-value frame-id)
        n  (alength roster)]
    (dotimes [i n]
      (subs/compute-sub (aget roster i) fs))))

(defn- floor-pass!
  "The irreducible floor: one registrar lookup and one raw layer-1
  handler call per read, against the app-db partition read once."
  [frame-id ^js roster]
  (let [app-db (:rf.db/app (frame/frame-state-value frame-id))
        n      (alength roster)
        sink   (volatile! nil)]
    (dotimes [i n]
      (let [q (aget roster i)]
        (vreset! sink ((:handler-fn (registrar/lookup :sub (nth q 0)))
                       app-db q))))
    @sink))

(defn- sub-pass!
  "The shipping collector read, roster order — `ship` on the cold frame,
  `warm` on the committed one."
  [^js roster]
  (let [n (alength roster)]
    (dotimes [i n]
      (sub (aget roster i)))))

;; ---------------------------------------------------------------------------
;; Phase A arms
;; ---------------------------------------------------------------------------

(defn- phase-a-arms [^js roster]
  [{:id :ship       :frame cold-frame
    :pass (fn [] (sub-pass! roster))}
   {:id :local      :frame cold-frame
    :pass (fn [] (local-pass! cold-frame roster))}
   {:id :no-shell   :frame cold-frame
    :pass (fn [] (once-pass! cold-frame roster))}
   {:id :probe      :frame cold-frame
    :pass (fn [] (probe-pass! cold-frame roster))}
   {:id :probe-fresh :frame cold-frame
    :pass (fn [] (fresh-memo-pass! cold-frame roster))}
   {:id :floor      :frame cold-frame
    :pass (fn [] (floor-pass! cold-frame roster))}
   {:id :warm       :frame warm-frame
    :pass (fn [] (sub-pass! roster))}
   {:id :ctl2       :frame cold-frame
    :pass (fn [] (once-pass! cold-frame roster) (once-pass! cold-frame roster))}])

(def ^:private sampling {:warmup 4 :samples 10})
(def ^:private rounds 6)

;; ---------------------------------------------------------------------------
;; Phase B — the commit half
;; ---------------------------------------------------------------------------

(def ^:const C-FULL 0)
(def ^:const C-NOWATCH 1)
(def ^:const C-NOSUB 2)
(def ^:const C-NOINDEX 3)
(def ^:const C-NOMAP 4)

(def ^:private !local-watch-counter (volatile! 0))

(defn- commit-local!
  "Faithful copy of the commit half `make-subscribe` performs for one
  boundary at `mode`: the registration object, one cell per key of the
  read SET (mint + `subs/subscribe` + the baseline deref + the
  value-change watch + the disposal hook + the map insert), then the
  index mount and the whole-set `record-reads!`. Answers a teardown fn
  that mirrors the returned unsubscribe closure — run OUTSIDE the window.

  The stubs, stated: `c-nosub` keeps the computation (a `compute-sub`
  against the frame-state snapshot) so its delta prices the reaction
  build + cache insert rather than build-plus-compute; `c-nowatch` skips
  both the watch and the disposal hook; the disposal hook and the watch
  callback are no-ops rather than the arm's real repair fns, which is a
  floor in the stubs' favour."
  [mode frame-id reads-set fs]
  (let [reg   #js {"reads" reads-set "notify" (fn [] nil)}
        cells #js []
        !map  (volatile! {})]
    (doseq [sub-key reads-set]
      (let [q  (nth sub-key 1)
            r  (if (identical? mode C-NOSUB)
                 nil
                 (subs/subscribe q {:frame frame-id}))
            wk (keyword "rf-readprof" (str "w" (vswap! !local-watch-counter inc)))
            ^js cell #js {"subKey"   sub-key
                          "frameKw"  frame-id
                          "queryV"   q
                          "reaction" r
                          "watchKey" wk
                          "epoch"    (rt/commit-basis frame-id)
                          "refs"     1
                          "disposed" false}]
        (if (identical? mode C-NOSUB)
          (subs/compute-sub q fs)
          (do @r
              (when-not (identical? mode C-NOWATCH)
                (add-watch r wk (fn [_ _ _ _] nil))
                (interop/add-on-dispose! r (fn [] nil)))))
        (.push cells cell)
        (when-not (identical? mode C-NOMAP)
          (vswap! !map assoc sub-key cell))))
    (unchecked-set reg "cells" cells)
    (when-not (identical? mode C-NOINDEX)
      (idx/mount! reg)
      (idx/record-reads! reg reads-set))
    (fn teardown []
      (when-not (identical? mode C-NOINDEX)
        (idx/unmount! reg))
      (dotimes [i (alength cells)]
        (let [^js cell (aget cells i)]
          (when-some [r (.-reaction cell)]
            (remove-watch r (.-watchKey cell))
            (subs/unsubscribe frame-id (.-queryV cell))))))))

(defn- build-only!
  "141 bare `subscribe` + deref on `frame-id`, holding every reference.
  Answers the teardown (a plain `unsubscribe` per key, synchronous
  dispose at 1 -> 0)."
  [frame-id ^js roster]
  (let [n (alength roster)]
    (dotimes [i n]
      @(subs/subscribe (aget roster i) {:frame frame-id}))
    (fn teardown []
      (dotimes [i n]
        (subs/unsubscribe frame-id (aget roster i))))))

(defn- phase-b-arms
  "`entries` is {frame-id entry}; `sets` is {frame-id read-set};
  `fss` is {frame-id frame-state-value} — read at setup, outside windows."
  [entries sets fss ^js roster]
  (let [mk-local (fn [mode]
                   (fn []
                     (mapv (fn [f] (commit-local! mode f (get sets f) (get fss f)))
                           commit-frames)))]
    [{:id :commit
      :run (fn []
             (mapv (fn [f] (rt/commit-boundary! (get entries f) (fn [] nil)))
                   commit-frames))}
     {:id :c-local   :run (mk-local C-FULL)}
     {:id :c-nowatch :run (mk-local C-NOWATCH)}
     {:id :c-nosub   :run (mk-local C-NOSUB)}
     {:id :c-noindex :run (mk-local C-NOINDEX)}
     {:id :c-nomap   :run (mk-local C-NOMAP)}
     {:id :b-build
      :run (fn [] (mapv (fn [f] (build-only! f roster)) commit-frames))}]))

(def ^:private b-sampling {:warmup 2 :samples 6})
(def ^:private b-rounds 4)

(defn- probe-caches-empty? []
  (every? (fn [f] (zero? (count @(:sub-cache (frame/frame f)))))
          commit-frames))

(defn- rounds-async!
  "The reflecting-schedule sampler, promise-chained: every sample index
  visits every arm in [[lane/slot-order]]'s order; warm-up samples are
  taken and discarded; between samples the arm's teardowns run, one
  macrotask settles, and the residue gate must answer clean. Mirrors
  `lane/rounds!`, which cannot yield."
  [arms {:keys [warmup samples]} rounds' baseline]
  (let [k    (count arms)
        coll (lane/sample-collector)
        acc  (atom (zipmap (map :id arms) (repeat [])))]
    (-> (lane/chain
          nil
          (for [_round (range rounds')
                s      (range (+ warmup samples))
                j      (lane/slot-order k s)]
            [s j])
          (fn [_ [s j]]
            (let [{:keys [id run]} (nth arms j)
                  t0        (lane/now-ms)
                  teardowns (run)
                  ms        (- (lane/now-ms) t0)]
              (doseq [t teardowns] (t))
              (-> (lane/settle!)
                  (.then
                    (fn [_]
                      (let [now (rt/residue)]
                        (when-not (and (= baseline now) (probe-caches-empty?))
                          (throw (ex-info (str "phase-B residue after " (name id)
                                               " — expected " (pr-str baseline)
                                               ", found " (pr-str now)
                                               (when-not (probe-caches-empty?)
                                                 ", and a probe frame's sub-cache is not empty"))
                                          {:arm id :baseline baseline :residue now})))
                        (when (>= s warmup)
                          (lane/collect! coll (name id) ms)
                          (swap! acc update id conj ms))
                        nil)))))))
        (.then (fn [_] {:readings [@acc] :samples (:samples @coll)})))))

;; ---------------------------------------------------------------------------
;; Micro benches — the per-read primitives
;; ---------------------------------------------------------------------------

(defn- ns-per-op
  [reps ^js arr f]
  (let [sink (volatile! nil)
        n    (.-length arr)
        t0   (lane/now-ms)]
    (dotimes [_ reps]
      (dotimes [i n]
        (vreset! sink (f (aget arr i)))))
    (let [ms (- (lane/now-ms) t0)]
      (/ (* 1e6 ms) (* reps n)))))

(defn- micro-table [^js roster]
  (let [fs     (frame/frame-state-value cold-frame)
        app-db (:rf.db/app fs)
        cache  (:sub-cache (frame/frame cold-frame))]
    [[:subscribe-once     (ns-per-op 20 roster (fn [q] (subs/subscribe-once q {:frame cold-frame})))]
     [:compute-sub        (ns-per-op 20 roster (fn [q] (subs/compute-sub q fs)))]
     [:handler-invoke     (ns-per-op 200 roster (fn [q] ((:handler-fn (registrar/lookup :sub (nth q 0))) app-db q)))]
     [:registrar-lookup   (ns-per-op 200 roster (fn [q] (registrar/lookup :sub (nth q 0))))]
     [:frame-state-value  (ns-per-op 200 roster (fn [_] (frame/frame-state-value cold-frame)))]
     [:resolution-wrap    (ns-per-op 200 roster (fn [_] (live-frame/call-with-frame-resolution cold-frame (fn [] nil))))]
     [:cache-peek-miss    (ns-per-op 200 roster (fn [q] (:reaction (get @cache q))))]
     [:sub-key-mint       (ns-per-op 200 roster (fn [q] [cold-frame q]))]]))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- fmt [x n] (.toFixed ^number x n))

(defn- arm-rows [arm-ids readings per-window]
  (into {}
        (map (fn [id]
               (let [xs (mapcat #(get % id) readings)]
                 [id (lane/summarise (mapv #(/ % per-window) xs))])))
        arm-ids))

(defn- us-per-read [ms] (* 1e3 (/ ms 141)))

(defn- arm-line [id {:keys [p50 min max]}]
  (str ";;   " (name id) ": p50 " (fmt p50 4)
       " [" (fmt min 4) " - " (fmt max 4) "] ms/pass  ("
       (fmt (us-per-read p50) 2) " us/read)"))

(defn- delta-line [label base p50]
  (let [d (- base p50)]
    (str ";;   " label ": delta " (fmt d 4) " ms/pass ("
         (fmt (us-per-read d) 2) " us/read, "
         (fmt (* 100 (/ d base)) 1) "% of the baseline)")))

(defn ^:export -main []
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (lane/self-test!)
  (-> (js/Promise.resolve nil)
      (.then
        (fn [_]
          (seed-frame! cold-frame)
          (seed-frame! warm-frame)
          (let [{:keys [roster elements]} (harvest-roster! cold-frame)
                census (roster-census roster)]
            (js/console.log (str ";; harvest OK — " elements " elements, "
                                 (:reads census) " reads ("
                                 (:distinct census) " distinct), from the real page's own entry"))
            ;; Commit the warm frame's cells once, and hold them.
            (rt/render-body warm-frame (fn [_] (sub-pass! roster) [:span]) {})
            (let [warm-release (rt/commit-boundary! (rt/last-reads) (fn [] nil))]
              (-> (lane/settle!)
                  (.then
                    (fn [_]
                      ;; ---- Phase A: the render half, sync + interleaved.
                      (let [arms  (phase-a-arms roster)
                            {:keys [readings samples]}
                            (lane/rounds! arms sampling rounds
                                          (fn [{:keys [frame pass]}]
                                            (timed-doors frame pass)))
                            rows  (arm-rows (map :id arms) readings passes-per-sample)
                            gv    (lane/guard! samples "read-profile phase A (in-page ms, diagnostic)")
                            ctl   (lane/control-verdict
                                    (* 2.0 (:p50 (get rows :no-shell)))
                                    (let [s (get rows :ctl2)]
                                      {:min (:min s) :max (:max s) :mean (:p50 s)})
                                    0.25)]
                        (when-not (:ok? ctl)
                          (throw (ex-info (str "phase-A positive control failed: " (:why ctl)) {})))
                        ;; The warm frame's cells must have stayed committed and
                        ;; the cold frame must have stayed cold.
                        (let [{:keys [cells cell-refs]} (rt/stats)]
                          (when-not (and (= 141 cells) (= 141 cell-refs))
                            (throw (ex-info (str "phase-A residue: cells " cells
                                                 " refs " cell-refs ", expected 141/141 "
                                                 "(the warm frame's held commit and nothing else)")
                                            {}))))
                        (lane/record! :read-profile-census census)
                        (lane/record! :read-profile-arms
                                      (into {} (map (fn [[k v]] [k (-> v (update :min lane/round4)
                                                                       (update :max lane/round4)
                                                                       (update :p50 lane/round4))])) rows))
                        (js/console.log ";; ==== READ PROFILE, PHASE A (ms per 141-read pass; diagnostic in-page clock) ====")
                        (js/console.log (str ";;   reads/pass 141  passes/sample " passes-per-sample
                                             "  design " rounds "x(" (:warmup sampling) "+" (:samples sampling) ")"))
                        (doseq [{:keys [id]} arms]
                          (js/console.log (arm-line id (get rows id))))
                        (js/console.log ";; ==== PHASE A DELTAS (floors; stubs stated in the ns docstring) ====")
                        (let [ship  (:p50 (get rows :ship))
                              local (:p50 (get rows :local))]
                          (js/console.log (str ";;   copy fidelity: local/ship = " (fmt (/ local ship) 4)
                                               "  (local is the FROZEN subscribe-once path)"))
                          (js/console.log (delta-line "the-hicasso-shell (local - no-shell)" local (:p50 (get rows :no-shell))))
                          (js/console.log (delta-line "churn-vs-probe (local - probe, the candidate's saving)" local (:p50 (get rows :probe))))
                          (js/console.log (delta-line "memo-economy (probe-fresh - probe)" (:p50 (get rows :probe-fresh)) (:p50 (get rows :probe))))
                          (js/console.log (delta-line "probe-overhead (probe - floor)" (:p50 (get rows :probe)) (:p50 (get rows :floor))))
                          (js/console.log (str ";;   warm steady-state: " (fmt (:p50 (get rows :warm)) 4)
                                               " ms/pass (" (fmt (us-per-read (:p50 (get rows :warm))) 2) " us/read)"))
                          (js/console.log (str ";;   control: " (:why ctl))))
                        (when (:refuse? gv)
                          (set! (.-HICASSO_GUARD_REFUSED js/window) true))
                        ;; ---- Phase B setup: four identically-seeded frames,
                        ;; entries harvested through the door.
                        (doseq [f commit-frames] (seed-frame! f))
                        (let [entries (into {} (map (fn [f]
                                                      (rt/render-body f (fn [_] (sub-pass! roster) [:span]) {})
                                                      [f (rt/last-reads)]))
                                            commit-frames)
                              sets    (into {} (map (fn [f] [f (rt/reads-of (get entries f))])) commit-frames)
                              fss     (into {} (map (fn [f] [f (frame/frame-state-value f)])) commit-frames)]
                          (-> (lane/settle!)
                              (.then (fn [_]
                                       (let [baseline (rt/residue)]
                                         (rounds-async! (phase-b-arms entries sets fss roster)
                                                        b-sampling b-rounds baseline))))
                              (.then
                                (fn [{:keys [readings samples]}]
                                  (let [ids  [:commit :c-local :c-nowatch :c-nosub :c-noindex :c-nomap :b-build]
                                        rows (arm-rows ids readings 4)
                                        gv-b (lane/guard! samples "read-profile phase B (in-page ms, diagnostic)")]
                                    (lane/record! :read-profile-commit
                                                  (into {} (map (fn [[k v]] [k (-> v (update :min lane/round4)
                                                                                   (update :max lane/round4)
                                                                                   (update :p50 lane/round4))])) rows))
                                    (js/console.log ";; ==== READ PROFILE, PHASE B — THE COMMIT HALF (ms per 141-key boundary commit) ====")
                                    (js/console.log (str ";;   design " b-rounds "x(" (:warmup b-sampling) "+" (:samples b-sampling)
                                                         ")  window = 4 frames/commit each"))
                                    (doseq [id ids]
                                      (js/console.log (arm-line id (get rows id))))
                                    (js/console.log ";; ==== PHASE B DELTAS (c-local minus ablation; floors) ====")
                                    (let [commit' (:p50 (get rows :commit))
                                          clocal  (:p50 (get rows :c-local))]
                                      (js/console.log (str ";;   copy fidelity: c-local/commit = " (fmt (/ clocal commit') 4)))
                                      (js/console.log (delta-line "watch-wiring (c-local - c-nowatch)" clocal (:p50 (get rows :c-nowatch))))
                                      (js/console.log (delta-line "reaction-build+cache-insert (c-local - c-nosub)" clocal (:p50 (get rows :c-nosub))))
                                      (js/console.log (delta-line "index-write (c-local - c-noindex)" clocal (:p50 (get rows :c-noindex))))
                                      (js/console.log (delta-line "cell-map-insert (c-local - c-nomap)" clocal (:p50 (get rows :c-nomap))))
                                      (js/console.log (str ";;   b-build (build+compute, no in-window dispose): "
                                                           (fmt (:p50 (get rows :b-build)) 4) " ms/pass ("
                                                           (fmt (us-per-read (:p50 (get rows :b-build))) 2) " us/read)")))
                                    (when (:refuse? gv-b)
                                      (set! (.-HICASSO_GUARD_REFUSED js/window) true))
                                    ;; ---- Micro table.
                                    (let [micro (micro-table roster)]
                                      (lane/record! :read-profile-micro
                                                    (into {} (map (fn [[k v]] [k (lane/round4 v)])) micro))
                                      (js/console.log ";; ==== MICRO (ns/op over the page's own roster) ====")
                                      (doseq [[k v] micro]
                                        (js/console.log (str ";;   " (name k) ": " (fmt v 1) " ns"))))
                                    ;; ---- Teardown: release the warm hold, verify.
                                    (warm-release)
                                    (lane/settle!))))
                              (.then
                                (fn [_]
                                  (let [res (rt/residue)]
                                    (when-not (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0} res)
                                      (throw (ex-info (str "final residue not clean: " (pr-str res)) {}))))
                                  (lane/done!))))))))))))))
      (.catch (fn [e]
                (lane/fail! (or (some-> e .-message) (str e)))
                (lane/done!)))))

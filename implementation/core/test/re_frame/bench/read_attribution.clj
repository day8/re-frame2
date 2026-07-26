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

  ## The `subs` suite — inside `subscribe` (rf2-j8ls2 / rf2-ncjyt)

  `RM_SUITE=subs` runs a SECOND ladder that opens `subscribe`'s cache-HIT
  path the same way `PRELUDE` / `PRENODE` opened `probe`'s. Arms S1..S4
  are strict prefixes of `RGSUB`, re-walked through public functions
  only, so neighbour subtraction attributes bytes to a step:

    S1-CURFRM  `require-current-frame!` + the `{:where :event-id}` extra
               map `subscribe`'s 1-arity builds on EVERY call
    S2-RESTGT  + `frame-target->id` + `frame-resolution-target`
    S3-CWFR    + `call-with-frame-resolution` around an empty thunk —
               the flush consult, the generation read, the `binding`
    S4-PRELOOK + `(frame/frame id)` + `(get @cache q)` inside the thunk:
               everything up to the ref-count attach
    RGSUB      + the ref-count attach and the post-swap re-check

  `RGSUB - S4-PRELOOK` is then the ref-count attach, and the `RC-*` arms
  price its parts against the REAL 300-entry cache map:

    RC-ATTACH  the PRE-rf2-j8ls2 `swap-vals!` form (the `update-in`
               spelling), kept as the paired control
    RC-CAND    the form that replaced it in `subs/bump-ref-count-fn`
    RC-GUARD   the same `swap-vals!` whose fn returns `m` UNCHANGED —
               swap machinery + the identity guard, no update
    RC-SWAPID  `(swap-vals! cache identity)` — the machinery alone
    RC-UPDIN   pure `(update-in m [k :ref-count] (fnil inc 0))`
    RC-NEST    pure hand-rolled two-level update, same result
    RC-ASSOC   pure `(assoc m k entry)` — the OUTER HAMT path copy alone,
               the irreducible cost of a persistent one-key change
    RC-EASSOC  pure `(assoc entry :ref-count n)` — the INNER copy alone

  The `N-*` arms open rf2-ncjyt's `PRELUDE` the same way: the throwaway
  frame VALUE `frame-resolution-target` mints, the late-bind flush
  consult, the generation read, the `binding` alone, and `registrar/
  lookup` on both its branches (generation-bound and registrar-atom).

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
  confounds), RM_SUITE=port|subs|all (which ladder to run; `port` is the
  default and is exactly the original thirteen arms)."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
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
;; rf2-j8ls2 — INSIDE `subscribe`'s cache-HIT path.
;;
;; S1..S4 re-walk `subs/subscribe`'s 1-arity through public functions only,
;; each a strict prefix of the next and of `RGSUB`. If they do not bracket
;; `RGSUB` they are not tracking `subscribe` and must not be quoted.

(defn- arm-s1-curfrm [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (vreset! sink
               (if (frame/require-current-frame!
                     :subscribe
                     {:where    're-frame.subs/subscribe
                      :event-id (first (nth qs k))})
                 1 0)))))

(defn- arm-s2-restgt [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (let [fid* (frame/frame-target->id
                   (frame/require-current-frame!
                     :subscribe
                     {:where    're-frame.subs/subscribe
                      :event-id (first (nth qs k))}))]
        (vreset! sink (if (live-frame/frame-resolution-target fid*) 1 0))))))

(defn- arm-s3-cwfr [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (let [fid* (frame/frame-target->id
                   (frame/require-current-frame!
                     :subscribe
                     {:where    're-frame.subs/subscribe
                      :event-id (first (nth qs k))}))]
        (live-frame/call-with-frame-resolution
          (live-frame/frame-resolution-target fid*)
          (fn [] (vreset! sink 1)))))))

(defn- arm-s4-prelook [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (let [q    (nth qs k)
            fid* (frame/frame-target->id
                   (frame/require-current-frame!
                     :subscribe
                     {:where    're-frame.subs/subscribe
                      :event-id (first q)}))]
        (live-frame/call-with-frame-resolution
          (live-frame/frame-resolution-target fid*)
          (fn []
            (let [cache* (:sub-cache (frame/frame fid*))]
              (vreset! sink (if (get @cache* q) 1 0)))))))))

;; ---- the ref-count attach, part by part -----------------------------------
;;
;; RC-ATTACH is the shipped form verbatim. The rest strip ONE thing each, so
;; a difference names a part rather than a suspicion. The `RC-*` pure arms run
;; against a SNAPSHOT of the same 300-entry cache map, so the outer HAMT they
;; copy is the real one.

;; The PRE-rf2-j8ls2 attach, held here verbatim as the paired control for the
;; form that replaced it (RC-CAND). It is deliberately NOT a call into
;; `subs/bump-ref-count-fn`: the whole point is to keep the retired expression
;; measurable beside the shipped one, in the same process, on the same map.
(defn- arm-rc-attach [n]
  (let [{:keys [qs cache reactions]} @rig]
    (dotimes [k n]
      (let [q        (nth qs k)
            reaction (nth reactions k)
            [_old new]
            (swap-vals! cache
                        (fn [m]
                          (if (identical? reaction (:reaction (get m q)))
                            (update-in m [q :ref-count] (fnil inc 0))
                            m)))]
        (vreset! sink (if (identical? reaction (:reaction (get new q))) 1 0))))))

(defn- arm-rc-guard [n]
  (let [{:keys [qs cache reactions]} @rig]
    (dotimes [k n]
      (let [q        (nth qs k)
            reaction (nth reactions k)
            [_old new]
            (swap-vals! cache
                        (fn [m]
                          (if (identical? reaction (:reaction (get m q)))
                            m
                            m)))]
        (vreset! sink (if (identical? reaction (:reaction (get new q))) 1 0))))))

(defn- arm-rc-swapid [n]
  (let [{:keys [cache]} @rig]
    (dotimes [_ n]
      (let [[_old new] (swap-vals! cache identity)]
        (vreset! sink (if new 1 0))))))

(defn- arm-rc-updin [n]
  (let [{:keys [qs snap]} @rig]
    (dotimes [k n]
      (vreset! sink
               (if (update-in snap [(nth qs k) :ref-count] (fnil inc 0)) 1 0)))))

(defn- arm-rc-nest [n]
  (let [{:keys [qs snap]} @rig]
    (dotimes [k n]
      (let [q (nth qs k)
            e (get snap q)]
        (vreset! sink
                 (if (assoc snap q (assoc e :ref-count (inc (long (:ref-count e 0)))))
                   1 0))))))

;; The outer HAMT path copy, and NOTHING else. `bumped` is a DISTINCT entry
;; object prepared once, because `PersistentHashMap.assoc` short-circuits and
;; returns `this` when the new value is `identical?` to the old one — assoc-ing
;; an entry back over itself measures the no-op path (16 B) and would badly
;; understate the copy this arm exists to price.
(defn- arm-rc-assoc [n]
  (let [{:keys [qs snap bumped]} @rig]
    (dotimes [k n]
      (vreset! sink (if (assoc snap (nth qs k) (nth bumped k)) 1 0)))))

(defn- arm-rc-eassoc [n]
  (let [{:keys [qs snap]} @rig]
    (dotimes [k n]
      (vreset! sink (if (assoc (get snap (nth qs k)) :ref-count 2) 1 0)))))

;; The SHIPPED attach (rf2-j8ls2 — `subs/bump-ref-count-fn`): the same
;; `swap-vals!` under the same CAS-after-snapshot discipline and the same
;; identity guard, with the two-level `update-in` written out. Same result,
;; same semantics; measured beside RC-ATTACH so the difference is the whole
;; claim. Spelled out here rather than called, so the pair stays a comparison
;; of two EXPRESSIONS and neither arm can drift under the other.
(defn- arm-rc-cand [n]
  (let [{:keys [qs cache reactions]} @rig]
    (dotimes [k n]
      (let [q        (nth qs k)
            reaction (nth reactions k)
            [_old new]
            (swap-vals! cache
                        (fn [m]
                          (let [e (get m q)]
                            (if (identical? reaction (:reaction e))
                              (assoc m q (assoc e :ref-count
                                                (inc (long (:ref-count e 0)))))
                              m))))]
        (vreset! sink (if (identical? reaction (:reaction (get new q))) 1 0))))))

;; ---- rf2-ncjyt — the pre-node lookups -------------------------------------

;; `call-with-frame-resolution` with a target that names no image-loaded frame:
;; the late-bind flush consult, the generation read and the thunk call, with NO
;; `binding`. S3-CWFR minus this is the dynamic binding, isolated.
(defn- arm-n-cwfr-nogen [n]
  (dotimes [_ n]
    (live-frame/call-with-frame-resolution nil (fn [] (vreset! sink 1)))))

(defn- arm-n-restgt [n]
  (dotimes [_ n]
    (vreset! sink (if (live-frame/frame-resolution-target fid) 1 0))))

(defn- arm-n-genread [n]
  (dotimes [_ n]
    (vreset! sink (if (live-frame/frame-resolution-generation fid) 1 0))))

(defn- arm-n-flush [n]
  (dotimes [_ n]
    (when-let [flush! (late-bind/get-fn-cached :live-frame/flush-projection!)]
      (flush!))
    (vreset! sink 1)))

(defn- arm-n-bindonly [n]
  (let [gen (:gen @rig)]
    (dotimes [_ n]
      (binding [registrar/*generation* gen]
        (vreset! sink (if registrar/*generation* 1 0))))))

(defn- arm-n-lookgen [n]
  (let [{:keys [qs gen]} @rig]
    (binding [registrar/*generation* gen]
      (dotimes [k n]
        (vreset! sink (if (registrar/lookup :sub (first (nth qs k))) 1 0))))))

(defn- arm-n-lookatom [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (vreset! sink (if (registrar/lookup :sub (first (nth qs k))) 1 0)))))

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
  [{:keys [n iters warmup alloc-iters reverse-order? suite]
    :or   {n 300 iters 400 warmup 400 alloc-iters 200 reverse-order? false
           suite "port"}}]
  (.setThreadAllocatedMemoryEnabled tmx true)
  (let [sub-ids (mapv #(keyword "ra" (str "s" %)) (range n))
        qs      (mapv vector sub-ids)
        db-of   (fn [gen] {:items (mapv (fn [i] [gen i]) (range n))})
        gen     (volatile! 0)]
    (doseq [[i id] (map-indexed vector sub-ids)]
      (rf/reg-sub id (fn [db _] (get-in db [:items i]))))
    (live-frame/make-frame {:id fid})
    (frame/replace-app-db! fid (db-of 0))
    (println (format ";; debug-enabled? = %s  n=%d iters=%d warmup=%d alloc-iters=%d order=%s suite=%s"
                     interop/debug-enabled? n iters warmup alloc-iters
                     (if reverse-order? "REVERSED" "forward") suite))
    (binding [frame/*current-frame* fid]
      ;; Hold n subscriptions the way a mounted application holds them, so
      ;; every node is live for the whole run — which is what makes `probe`
      ;; take its LIVE path rather than its cold-compute one, and what gives
      ;; DEREF something to deref.
      (let [held  (mapv subs/subscribe qs)
            src   (frame/app-db-container fid)
            cache (:sub-cache (frame/frame fid))
            raw   (mapv (fn [i] (interop/make-reaction (fn [] (get-in @src [:items i]))))
                        (range n))]
        (vreset! rig {:qs           qs
                      :cache        cache
                      :held         held
                      :raw          raw
                      :db-container src
                      ;; rf2-j8ls2: the `RC-*` arms need the exact reaction the
                      ;; shipped `identical?` guard compares against, a SNAPSHOT
                      ;; of the real 300-entry cache map for the pure arms, and
                      ;; the frame's sealed generation for the binding arms.
                      :reactions    (mapv #(:reaction (get @cache %)) qs)
                      :snap         @cache
                      :bumped       (mapv #(update (get @cache %) :ref-count inc) qs)
                      :gen          (live-frame/frame-resolution-generation fid)}))
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
            controls [["NOOP"    arm-noop]
                      ["CTRL-S"  arm-ctrl-small]
                      ["CTRL-L"  arm-ctrl-large]]
            port-plan [["RESOLVE" arm-resolve]  ["PORT"     arm-port]
                       ["CACHEGET" arm-cacheget] ["DEREF"   arm-deref]
                       ["RGSUB"   arm-rgsub]    ["RGREAD"   arm-rgread]
                       ["RAWREACT" arm-rawreact] ["GETIN"   arm-getin]
                       ["PRELUDE" arm-probe-prelude]
                       ["PRENODE" arm-probe-prenode]]
            ;; rf2-j8ls2 / rf2-ncjyt. RGSUB is in BOTH plans because it is the
            ;; whole that S1..S4 + the attach must add back up to.
            subs-plan [["RGSUB"     arm-rgsub]
                       ["S1-CURFRM" arm-s1-curfrm]  ["S2-RESTGT" arm-s2-restgt]
                       ["S3-CWFR"   arm-s3-cwfr]    ["S4-PRELOOK" arm-s4-prelook]
                       ["RC-ATTACH" arm-rc-attach]  ["RC-GUARD"  arm-rc-guard]
                       ["RC-SWAPID" arm-rc-swapid]  ["RC-UPDIN"  arm-rc-updin]
                       ["RC-NEST"   arm-rc-nest]    ["RC-ASSOC"  arm-rc-assoc]
                       ["RC-EASSOC" arm-rc-eassoc]  ["RC-CAND"   arm-rc-cand]
                       ["N-RESTGT"  arm-n-restgt]   ["N-GENREAD" arm-n-genread]
                       ["N-FLUSH"   arm-n-flush]    ["N-BINDONLY" arm-n-bindonly]
                       ["N-CWFRNOG" arm-n-cwfr-nogen]
                       ["N-LOOKGEN" arm-n-lookgen]  ["N-LOOKATOM" arm-n-lookatom]]
            port?     (contains? #{"port" "all"} suite)
            subs?     (contains? #{"subs" "all"} suite)
            plan (concat controls
                         (when port? port-plan)
                         (when subs? (if port? (rest subs-plan) subs-plan)))
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
        (doseq [l (map first (rest plan))]
          (println (format ";; %-10s raw %11.0f B   net %11.0f B   %9.1f B/read"
                           l (b l) (net l) (per l))))
        (when subs?
          (println ";;")
          (println ";; rf2-j8ls2 — INSIDE subscribe's cache-HIT path (prefix ladder)")
          (doseq [[lbl v] [["require-current-frame! (S1)"            (net "S1-CURFRM")]
                           ["+ resolution target    (S2-S1)"         (- (net "S2-RESTGT") (net "S1-CURFRM"))]
                           ["+ call-with-frame-res  (S3-S2)"         (- (net "S3-CWFR") (net "S2-RESTGT"))]
                           ["+ frame + cache get    (S4-S3)"         (- (net "S4-PRELOOK") (net "S3-CWFR"))]
                           ["+ the REF-COUNT ATTACH (RGSUB-S4)"      (- (net "RGSUB") (net "S4-PRELOOK"))]
                           ["= subscribe            (RGSUB)"         (net "RGSUB")]]]
            (println (format ";;   %-38s %8.1f B/call" lbl (/ v (double n)))))
          (println ";; the attach, part by part (RC-ATTACH is the shipped form):")
          (doseq [l ["RC-ATTACH" "RC-CAND" "RC-GUARD" "RC-SWAPID" "RC-UPDIN"
                     "RC-NEST" "RC-ASSOC" "RC-EASSOC"]]
            (println (format ";;   %-38s %8.1f B/call" l (per l))))
          (println (format ";;   %-38s %8.1f B/call"
                           "update-in cost (ATTACH-GUARD)"
                           (- (per "RC-ATTACH") (per "RC-GUARD"))))
          (println (format ";;   %-38s %8.1f B/call"
                           "irreducible persistent write (ASSOC+EASSOC)"
                           (+ (per "RC-ASSOC") (per "RC-EASSOC"))))
          (println (format ";;   %-38s %8.1f B/call"
                           "ATTACH - CAND (what the rewrite saves)"
                           (- (per "RC-ATTACH") (per "RC-CAND"))))
          (println ";; rf2-ncjyt — the pre-node lookups:")
          (doseq [l ["N-RESTGT" "N-GENREAD" "N-FLUSH" "N-BINDONLY" "N-CWFRNOG"
                     "N-LOOKGEN" "N-LOOKATOM"]]
            (println (format ";;   %-38s %8.1f B/call" l (per l))))
          (println (format ";;   %-38s %8.1f B/call"
                           "the dynamic binding (S3-S2 - N-CWFRNOG)"
                           (- (per "S3-CWFR") (per "S2-RESTGT") (per "N-CWFRNOG")))))
        (when port?
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
                           lbl (/ v (double n)) (* 100.0 (/ v (net "PORT")))))))
        res))))

(defn -main [& _]
  ((test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
   (fn []
     (run {:n              (env-int "RM_N" 300)
           :iters          (env-int "RM_ITERS" 400)
           :warmup         (env-int "RM_WARMUP" 400)
           :alloc-iters    (env-int "RM_ALLOC" 200)
           :suite          (or (System/getenv "RM_SUITE") "port")
           :reverse-order? (= "rev" (System/getenv "RM_ORDER"))})))
  (shutdown-agents))

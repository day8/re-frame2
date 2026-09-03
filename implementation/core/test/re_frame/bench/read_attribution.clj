(ns re-frame.bench.read-attribution
  "rf2-j8ls2 / rf2-ncjyt — WHERE do `subscribe`'s bytes go?

  A 300-dependency render on the JVM allocates about 3.7 kB per dependency
  read, for a subscription whose whole body is `(get-in db [:items i])`.
  That cost lives in CORE's sub-cache rather than in any view substrate, so
  every application reading re-frame subscriptions pays it, on every
  substrate. This namespace attributes it, in ONE process against the REAL
  sub-cache and REAL live nodes.

  This file also carried a second ladder that priced the internal
  observation port beside `subscribe` (rf2-mvqwe / rf2-21pck). The port was
  retired on 2026-08-21 (rf2-63t1i) and that ladder went with it; the arms
  below are the ones `re-frame.subs` itself still cites.

  ## Inside `subscribe` (rf2-j8ls2 / rf2-ncjyt)

  The ladder opens `subscribe`'s cache-HIT path. Arms S1..S4 are strict
  prefixes of `RGSUB`, re-walked through public functions only, so
  neighbour subtraction attributes bytes to a step:

    S1-CURFRM  `require-current-frame!` on the happy path — the SHIPPED
               reader-then-require spelling (rf2-a8bw0)
    S2-TGTID   + `frame-target->id`
    S3-CWFR    + `call-with-frame-resolution` around an empty thunk —
               the flush consult, the generation read, the `binding`
    S4-PRELOOK + `(rf.frame/frame id)` + `(get @cache q)` inside the thunk:
               everything up to the ref-count attach
    RGSUB      + the ref-count attach and the post-swap re-check

  Two RETIRED spellings are kept live beside their replacements as
  PAIRED CONTROLS in the same process, so each saving is a falsifiable
  prediction rather than a before/after story:

    S1-EAGER   `require-current-frame!` with the `{:where :event-id}`
               extra map built EAGERLY — what `subscribe`'s 1-arity did
               before rf2-a8bw0.  S1-EAGER - S1-CURFRM is the saving.
    N-CWFRWRAP `call-with-frame-resolution` behind the retired
               `frame-resolution-target` wrapper (rf2-8gb3t), against
    N-CWFRRAW  the shipped form, which passes the carried target itself.
               N-CWFRWRAP - N-CWFRRAW must equal N-RESTGT.

  `RGSUB - S4-PRELOOK` is then the ref-count attach, and the `RC-*` arms
  price its parts against the REAL 300-entry cache map:

    RC-ATTACH  the PRE-rf2-j8ls2 `swap-vals!` form (the `update-in`
               spelling), kept as the paired control
    RC-CAND    the form that replaced it in `rf.subs/bump-ref-count-fn`
    RC-GUARD   the same `swap-vals!` whose fn returns `m` UNCHANGED —
               swap machinery + the identity guard, no update
    RC-SWAPID  `(swap-vals! cache identity)` — the machinery alone
    RC-UPDIN   pure `(update-in m [k :ref-count] (fnil inc 0))`
    RC-NEST    pure hand-rolled two-level update, same result
    RC-ASSOC   pure `(assoc m k entry)` — the OUTER HAMT path copy alone,
               the irreducible cost of a persistent one-key change
    RC-EASSOC  pure `(assoc entry :ref-count n)` — the INNER copy alone

  The `N-*` arms open the pre-node lookups the same way: the throwaway
  frame VALUE the retired `frame-resolution-target` minted, the late-bind
  flush consult, the generation read, the `binding` alone, and `rf.registrar/
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

  ## The arm order is a MEASURED property of this run (rf2-88pie/rf2-om73r)

  Until `rf2-om73r` this file made one pass over the plan and reported one
  mean per arm. `RM_ORDER=rev` offered a second pass in a SECOND PROCESS
  whose numbers nothing here ever joined up, and a mean alone carries no
  range — both against standing method.

  So the plan now runs in `RM_ROUNDS` rounds, and the arm order ROTATES AND
  REFLECTS with the round (`re-frame.bench.order-guard/slot-order`). A bare
  cyclic rotation would not do: arm `a` sits at slot `(a - r) mod n`, so its
  predecessor is `(a - 1) mod n` in every round and only the seam differs.
  Reflecting on odd rounds replaces every `a -> a+1` adjacency with
  `a -> a-1`, so each arm is measured after two different predecessors and
  at spread positions in the run. Each arm reports the p50 ACROSS ROUNDS
  with the full round range, never a mean alone.

  `order-guard` then partitions every arm's per-round figures by WHAT RAN
  BEFORE IT and by WHERE IN THE RUN it sat, applies the house rule —
  overlapping ranges are indistinguishable — and REFUSES the run (exit code
  2) if either factor separates an arm. It is the same rule as
  `implementation/core/test/re_frame/bench/order_guard.cjs`,
  expressed twice because this harness is JVM Clojure and there is no
  JavaScript runtime in the process; both copies replay the same recorded
  fixtures in their self-tests.

  The whole plan is also warmed BEFORE any arm is measured, rather than each
  arm warming itself immediately before its own reading. A site reads above
  its settled value until it has run several times (`rf2-tb345`), and a
  per-arm warm-up leaves that curve aligned with position in the plan, which
  is exactly the confound the phase factor refuses.

  ## What does NOT transfer to ClojureScript

  Byte counts do not, and neither does one behaviour: the JVM plain-atom
  adapter's derived value RECOMPUTES ON EVERY DEREF (there is no caching
  layer — see `re-frame.substrate.plain-atom`), while the CLJS spine
  caches and recomputes only when a source moved. So every arm here
  prices a read whose value MOVED. That is the right shape for the
  broad-update case every measured row is about (a write all 300
  subscriptions read moves all 300), and the wrong shape for a narrow
  one. Say which runtime a figure came from, always.

  Run it:

      clojure -M:test -m re-frame.bench.read-attribution

  Environment: RM_N (dependencies, default 300), RM_ITERS, RM_WARMUP,
  RM_ALLOC (timing and allocation passes per arm across ALL rounds),
  RM_ROUNDS (default 6 — at least six, so the guard's phase thirds are
  ranges rather than single samples), RM_TOLERANCE (the guard's
  relative-median tolerance, default 0.10 — TLAB accounting is exact, so a
  real arm reproduces to a fraction of a percent), RM_ORDER=rev (reverse the
  base plan before scheduling — a knob now, not the mitigation)."
  (:require [re-frame.bench.order-guard :as rf.bench.order-guard]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.lang.management ManagementFactory]))

(set! *warn-on-reflection* true)

(def ^:private ^com.sun.management.ThreadMXBean tmx
  (ManagementFactory/getThreadMXBean))

(defn- alloc-bytes ^long []
  (.getThreadAllocatedBytes tmx (.getId (Thread/currentThread))))

(defn- env-int [k d] (Integer/parseInt (or (System/getenv k) (str d))))

(defn- env-num [k d] (Double/parseDouble (or (System/getenv k) (str d))))

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
;; the RETIRED spellings, held here verbatim as paired controls
;;
;; Each is deliberately NOT a call into the shipped source: the whole point is
;; to keep the retired EXPRESSION measurable beside the one that replaced it,
;; in the same process, against the same live frame — so a claimed saving is a
;; prediction the instrument can falsify rather than a before/after story.

;; rf2-8gb3t. `rf.live-frame/frame-resolution-target`, as it stood before the
;; wrapper was retired: a frame VALUE verbatim, a frame-id keyword through
;; `live-frame` (which MINTS a fresh frame value), anything else nil.
(defn- retired-resolution-target [target]
  (cond
    (rf.live-frame/frame-value? target) target
    (keyword? target)                (rf.live-frame/live-frame target)
    :else                            nil))

;; rf2-a8bw0. `subscribe`'s 1-arity, as it stood before the payload was
;; deferred: the `{:where :event-id}` extra map built on EVERY call, read only
;; on the `:rf.error/no-frame-context` path.
(defn- retired-current-frame! [query-v]
  (rf.frame/require-current-frame!
    :subscribe
    {:where    're-frame.subs/subscribe
     :event-id (first query-v)}))

;; The SHIPPED spelling (rf2-a8bw0): the scope reader first, and the payload —
;; with `require-current-frame!` building it — only when the reader found
;; nothing. Same error, same content, same call site; built lazily.
(defn- shipped-current-frame! [query-v]
  (or (rf.frame/resolve-current-frame)
      (rf.frame/require-current-frame!
        :subscribe
        {:where    're-frame.subs/subscribe
         :event-id (first query-v)})))

;; ---------------------------------------------------------------------------
;; the arms

;; `subscribe` WITHOUT the deref — frame resolution, cache lookup and the
;; ref-count attach. It is the whole that S1..S4 plus the attach must add
;; back up to, and nothing here knows what a view is.
(defn- arm-rgsub [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (vreset! sink (if (rf.subs/subscribe (nth qs k)) 1 0)))))

;; ---------------------------------------------------------------------------
;; rf2-j8ls2 — INSIDE `subscribe`'s cache-HIT path.
;;
;; S1..S4 re-walk `rf.subs/subscribe`'s 1-arity through public functions only,
;; each a strict prefix of the next and of `RGSUB`. If they do not bracket
;; `RGSUB` they are not tracking `subscribe` and must not be quoted.

(defn- arm-s1-curfrm [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (vreset! sink (if (shipped-current-frame! (nth qs k)) 1 0)))))

;; The paired control for S1-CURFRM: the eager-payload spelling rf2-a8bw0
;; retired. S1-EAGER - S1-CURFRM is the whole of what deferring it saves.
(defn- arm-s1-eager [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (vreset! sink (if (retired-current-frame! (nth qs k)) 1 0)))))

(defn- arm-s2-tgtid [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (let [fid* (rf.frame/frame-target->id (shipped-current-frame! (nth qs k)))]
        (vreset! sink (if fid* 1 0))))))

(defn- arm-s3-cwfr [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (let [fid* (rf.frame/frame-target->id (shipped-current-frame! (nth qs k)))]
        (rf.live-frame/call-with-frame-resolution
          fid*
          (fn [] (vreset! sink 1)))))))

(defn- arm-s4-prelook [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (let [q    (nth qs k)
            fid* (rf.frame/frame-target->id (shipped-current-frame! q))]
        (rf.live-frame/call-with-frame-resolution
          fid*
          (fn []
            (let [cache* (:sub-cache (rf.frame/frame fid*))]
              (vreset! sink (if (get @cache* q) 1 0)))))))))

;; ---- the ref-count attach, part by part -----------------------------------
;;
;; RC-ATTACH is the shipped form verbatim. The rest strip ONE thing each, so
;; a difference names a part rather than a suspicion. The `RC-*` pure arms run
;; against a SNAPSHOT of the same 300-entry cache map, so the outer HAMT they
;; copy is the real one.

;; The PRE-rf2-j8ls2 attach, held here verbatim as the paired control for the
;; form that replaced it (RC-CAND). It is deliberately NOT a call into
;; `rf.subs/bump-ref-count-fn`: the whole point is to keep the retired expression
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

;; The SHIPPED attach (rf2-j8ls2 — `rf.subs/bump-ref-count-fn`): the same
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
    (rf.live-frame/call-with-frame-resolution nil (fn [] (vreset! sink 1)))))

(defn- arm-n-restgt [n]
  (dotimes [_ n]
    (vreset! sink (if (retired-resolution-target fid) 1 0))))

;; rf2-8gb3t, the falsifiable pair: the RETIRED composition every caller wrote
;; (`(cwfr (frame-resolution-target X) thunk)`) against the SHIPPED one
;; (`(cwfr X thunk)`). Their difference must be N-RESTGT — the wrapper and
;; nothing else.
(defn- arm-n-cwfr-wrap [n]
  (dotimes [_ n]
    (rf.live-frame/call-with-frame-resolution
      (retired-resolution-target fid)
      (fn [] (vreset! sink 1)))))

(defn- arm-n-cwfr-raw [n]
  (dotimes [_ n]
    (rf.live-frame/call-with-frame-resolution fid (fn [] (vreset! sink 1)))))

(defn- arm-n-genread [n]
  (dotimes [_ n]
    (vreset! sink (if (rf.live-frame/frame-resolution-generation fid) 1 0))))

(defn- arm-n-flush [n]
  (dotimes [_ n]
    (when-let [flush! (rf.late-bind/get-fn-cached :live-frame/flush-projection!)]
      (flush!))
    (vreset! sink 1)))

(defn- arm-n-bindonly [n]
  (let [gen (:gen @rig)]
    (dotimes [_ n]
      (binding [rf.registrar/*generation* gen]
        (vreset! sink (if rf.registrar/*generation* 1 0))))))

(defn- arm-n-lookgen [n]
  (let [{:keys [qs gen]} @rig]
    (binding [rf.registrar/*generation* gen]
      (dotimes [k n]
        (vreset! sink (if (rf.registrar/lookup :sub (first (nth qs k))) 1 0))))))

(defn- arm-n-lookatom [n]
  (let [qs (:qs @rig)]
    (dotimes [k n]
      (vreset! sink (if (rf.registrar/lookup :sub (first (nth qs k))) 1 0)))))

;; ---------------------------------------------------------------------------

(defn run
  "Measure every arm and print the attribution. Answers the results map,
  keyed by arm label, plus `::refused?` — the arm-order guard's verdict on
  whether anything in it may be quoted."
  [{:keys [n iters warmup alloc-iters reverse-order? rounds tolerance]
    :or   {n 300 iters 400 warmup 400 alloc-iters 200 reverse-order? false
           rounds 6 tolerance 0.10}}]
  ;; Ahead of everything: a broken guard makes every figure below
  ;; unpublishable, and the checks are fixtures replayed from recorded
  ;; readings, so this is deterministic and costs nothing.
  (when-not (rf.bench.order-guard/print-self-test!)
    (throw (ex-info "order guard self-test FAILED — nothing may be measured" {})))
  (.setThreadAllocatedMemoryEnabled tmx true)
  (let [sub-ids (mapv #(keyword "ra" (str "s" %)) (range n))
        qs      (mapv vector sub-ids)
        db-of   (fn [gen] {:items (mapv (fn [i] [gen i]) (range n))})
        gen     (volatile! 0)]
    (doseq [[i id] (map-indexed vector sub-ids)]
      (rf/reg-sub id (fn [db _] (get-in db [:items i]))))
    (rf.live-frame/make-frame {:id fid})
    (rf.frame/replace-app-db! fid (db-of 0))
    (println (format ";; debug-enabled? = %s  n=%d iters=%d warmup=%d alloc-iters=%d rounds=%d base order=%s"
                     rf.interop/debug-enabled? n iters warmup alloc-iters rounds
                     (if reverse-order? "REVERSED" "forward")))
    (println (format ";; arm order ROTATES AND REFLECTS with the round (rf2-88pie); guard tolerance %.0f%%"
                     (* 100.0 tolerance)))
    (binding [rf.frame/*current-frame* fid]
      ;; Hold n subscriptions the way a mounted application holds them, so
      ;; every node is live for the whole run — which is what makes
      ;; `subscribe` take its cache-HIT path rather than building.
      (let [held  (mapv rf.subs/subscribe qs)
            src   (rf.frame/app-db-container fid)
            cache (:sub-cache (rf.frame/frame fid))
            raw   (mapv (fn [i] (rf.interop/make-reaction (fn [] (get-in @src [:items i]))))
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
                      :gen          (rf.live-frame/frame-resolution-generation fid)}))
      (vswap! gen inc)
      (rf.frame/replace-app-db! fid (db-of @gen))
      ;; Every reader must return the value the writer just wrote, or the
      ;; arms are not reading the same thing and nothing below is comparable.
      (let [g       @gen
            rg-v    (deref (rf.subs/subscribe (nth qs 7)))
            dr-v    (deref (nth (:held @rig) 7))
            raw-v   (deref (nth (:raw @rig) 7))
            agree?  (= [g 7] rg-v dr-v raw-v)]
        (println (format ";; agreement at site 7, gen %d: rgread %s deref %s raw %s -> %s"
                         g (pr-str rg-v) (pr-str dr-v) (pr-str raw-v)
                         (if agree? "AGREE" "*** DISAGREE ***")))
        (when-not agree?
          (throw (ex-info "arms disagree; measurement is meaningless"
                          {:gen g :rgread rg-v :deref dr-v :raw raw-v}))))
      (let [advance! (fn [] (vswap! gen inc)
                       (rf.frame/replace-app-db! fid (db-of @gen)))
            ;; ONE round of one arm. Split out of the old `measure` so the
            ;; plan can be walked several times in several orders — a figure
            ;; taken from a plan run in one order has not been checked
            ;; (rf2-88pie).
            round!
            (fn [f its allocs]
              (let [us (/ (p50 (vec (repeatedly its
                                      (fn [] (advance!)
                                        (let [t0 (System/nanoTime)]
                                          (f n)
                                          (- (System/nanoTime) t0))))))
                          1000.0)
                    bs (double
                         (/ (loop [k 0 acc 0]
                              (if (< k allocs)
                                (do (advance!)
                                    (let [a0 (alloc-bytes)]
                                      (f n)
                                      (recur (inc k) (+ acc (- (alloc-bytes) a0)))))
                                acc))
                            allocs))]
                {:us us :bytes bs}))
            controls [["NOOP"    arm-noop]
                      ["CTRL-S"  arm-ctrl-small]
                      ["CTRL-L"  arm-ctrl-large]]
            ;; rf2-j8ls2 / rf2-ncjyt. RGSUB heads the plan because it is the
            ;; whole that S1..S4 + the attach must add back up to.
            subs-plan [["RGSUB"     arm-rgsub]
                       ["S1-CURFRM" arm-s1-curfrm]  ["S1-EAGER"  arm-s1-eager]
                       ["S2-TGTID"  arm-s2-tgtid]
                       ["S3-CWFR"   arm-s3-cwfr]    ["S4-PRELOOK" arm-s4-prelook]
                       ["RC-ATTACH" arm-rc-attach]  ["RC-GUARD"  arm-rc-guard]
                       ["RC-SWAPID" arm-rc-swapid]  ["RC-UPDIN"  arm-rc-updin]
                       ["RC-NEST"   arm-rc-nest]    ["RC-ASSOC"  arm-rc-assoc]
                       ["RC-EASSOC" arm-rc-eassoc]  ["RC-CAND"   arm-rc-cand]
                       ["N-RESTGT"  arm-n-restgt]   ["N-GENREAD" arm-n-genread]
                       ["N-CWFRWRAP" arm-n-cwfr-wrap] ["N-CWFRRAW" arm-n-cwfr-raw]
                       ["N-FLUSH"   arm-n-flush]    ["N-BINDONLY" arm-n-bindonly]
                       ["N-CWFRNOG" arm-n-cwfr-nogen]
                       ["N-LOOKGEN" arm-n-lookgen]  ["N-LOOKATOM" arm-n-lookatom]]
            plan (vec (concat controls subs-plan))
            ;; The order the SCHEDULE indexes into. `plan` itself stays in
            ;; base order, because the report below reads `(rest plan)` to
            ;; skip NOOP.
            ordered   (if reverse-order? (vec (reverse plan)) plan)
            k         (count ordered)
            its-per   (max 1 (quot iters rounds))
            allocs-per (max 1 (quot alloc-iters rounds))
            ;; --- warm the WHOLE plan before measuring any of it -----------
            ;; A per-arm warm-up immediately before that arm's reading leaves
            ;; the settling curve aligned with position in the plan, which is
            ;; the confound the phase factor refuses (rf2-tb345).
            _ (do (println (format ";; warming %d arms x %d passes before the first measured round"
                                   k warmup))
                  (flush)
                  (doseq [[_ f] ordered]
                    (dotimes [_ warmup] (advance!) (f n))))
            ;; --- the measured rounds -------------------------------------
            walked (reduce
                     (fn [acc [round j]]
                       (let [[label f] (nth ordered j)
                             m         (round! f its-per allocs-per)]
                         (-> acc
                             (update-in [:us label] (fnil conj []) (:us m))
                             (update-in [:bytes label] (fnil conj []) (:bytes m))
                             (update :order conj {:arm         label
                                                  :value       (:bytes m)
                                                  :predecessor (:prev acc)
                                                  :position    (count (:order acc))
                                                  :round       round})
                             (assoc :prev label))))
                     {:us {} :bytes {} :order [] :prev nil}
                     (vec (for [round (range rounds)
                                j     (rf.bench.order-guard/slot-order k round)]
                            [round j])))
            res  (into {}
                       (map (fn [[l _]]
                              (let [bs (get-in walked [:bytes l])]
                                [l {:label    l
                                    :us       (p50 (get-in walked [:us l]))
                                    :bytes    (p50 bs)
                                    :bytes-lo (apply min bs)
                                    :bytes-hi (apply max bs)
                                    :rounds   bs}])))
                       plan)
            b    (fn [l] (:bytes (get res l)))
            noop (b "NOOP")
            net  (fn [l] (- (b l) noop))
            per  (fn [l] (/ (net l) (double n)))]
        (doseq [[l _] plan]
          (let [r (get res l)]
            (println (format ";; %-9s %9.1f us %11.0f bytes %9.1f B/read   rounds [%.0f – %.0f]"
                             l (:us r) (:bytes r) (/ (:bytes r) (double n))
                             (:bytes-lo r) (:bytes-hi r)))))
        (flush)
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
        (do
          (println ";;")
          (println ";; rf2-j8ls2 — INSIDE subscribe's cache-HIT path (prefix ladder)")
          (doseq [[lbl v] [["require-current-frame! (S1)"            (net "S1-CURFRM")]
                           ["+ frame-target->id     (S2-S1)"         (- (net "S2-TGTID") (net "S1-CURFRM"))]
                           ["+ call-with-frame-res  (S3-S2)"         (- (net "S3-CWFR") (net "S2-TGTID"))]
                           ["+ frame + cache get    (S4-S3)"         (- (net "S4-PRELOOK") (net "S3-CWFR"))]
                           ["+ the REF-COUNT ATTACH (RGSUB-S4)"      (- (net "RGSUB") (net "S4-PRELOOK"))]
                           ["= subscribe            (RGSUB)"         (net "RGSUB")]]]
            (println (format ";;   %-38s %8.1f B/call" lbl (/ v (double n)))))
          (println ";; the retired spellings, as paired controls:")
          (doseq [[lbl v] [["S1-EAGER (retired eager payload)"       (per "S1-EAGER")]
                           ["S1-CURFRM (shipped, deferred)"          (per "S1-CURFRM")]
                           ["rf2-a8bw0 saves (S1-EAGER - S1-CURFRM)" (- (per "S1-EAGER") (per "S1-CURFRM"))]
                           ["N-CWFRWRAP (retired target wrapper)"    (per "N-CWFRWRAP")]
                           ["N-CWFRRAW  (shipped, carried target)"   (per "N-CWFRRAW")]
                           ["rf2-8gb3t saves (WRAP - RAW)"           (- (per "N-CWFRWRAP") (per "N-CWFRRAW"))]
                           ["  ... predicted by N-RESTGT"            (per "N-RESTGT")]]]
            (println (format ";;   %-38s %8.1f B/call" lbl v)))
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
                           (- (per "S3-CWFR") (per "S2-TGTID") (per "N-CWFRNOG")))))
        ;; --- arm order, LAST, and it governs everything above -------------
        ;; The refusal is about what may be QUOTED, not about throwing the
        ;; measurement away, so the figures are printed either way and the
        ;; exit code carries the verdict.
        (println ";;")
        (let [v (rf.bench.order-guard/verdict (:order walked) {:tolerance tolerance})]
          (doseq [line (rf.bench.order-guard/report-lines v "the per-arm allocation figure, one round-mean per round")]
            (println line))
          (when (:refuse? v)
            (println ";;")
            (println ";; ==== ARM ORDER: THESE FIGURES ARE NOT REPORTABLE ====")
            (println (str ";;   at least one arm reads differently for what preceded it, or for "
                          "where in the run it"))
            (println (str ";;   was measured (rf2-88pie). The table above stands as raw data; "
                          "nothing in it may"))
            (println ";;   be quoted."))
          (flush)
          (assoc res ::refused? (:refuse? v)))))))

(defn -main [& _]
  (let [refused? (volatile! false)]
    ((rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
     (fn []
       (vreset! refused?
                (::refused?
                 (run {:n              (env-int "RM_N" 300)
                       :iters          (env-int "RM_ITERS" 400)
                       :warmup         (env-int "RM_WARMUP" 400)
                       :alloc-iters    (env-int "RM_ALLOC" 200)
                       :rounds         (max 2 (env-int "RM_ROUNDS" 6))
                       :tolerance      (env-num "RM_TOLERANCE" 0.10)
                       :reverse-order? (= "rev" (System/getenv "RM_ORDER"))})))))
    (shutdown-agents)
    ;; A run whose figures the arm-order guard refused is not a green run.
    (when @refused? (System/exit 2))))

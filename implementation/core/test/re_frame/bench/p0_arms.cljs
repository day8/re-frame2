(ns re-frame.bench.p0-arms
  "EP-0038 P0 — the ARMS and the two SEGMENTS, as data and functions with
  every assertion left to the caller.

  ## Why there are segments at all

  P0 is like-for-like: Reagent reading re-frame2 subs against UIx reading
  re-frame2 subs. Those are two installed adapters, and
  `install-adapter!` is once per process (Spec 006 §Single adapter per
  process) — a second call without an intervening `destroy-adapter!`
  raises. So the two candidate arms CANNOT be interleaved inside one
  round.

  What is available, and what this namespace does, is two SEGMENTS of the
  same round in the same page and the same process:

      round r:  segment A  -> destroy+install adapter, seed, warm,
                              interleave {floor, <substrate>} at the
                              sample level
                segment B  -> the same, other adapter
      round r+1: the two segments in the OPPOSITE order

  The FLOOR runs in both segments. It holds no re-frame state, reads no
  subscription and is untouched by which adapter is installed, so it is
  the same work in both — and every published figure is a ratio to the
  floor measured in that same segment of that same round. A
  UIx-over-Reagent figure is therefore a ratio of two floor-normalised
  ratios and the seam between the segments cancels. The floor's own two
  segment readings are compared every round and published; a round in
  which they disagree beyond tolerance is a seam finding, reported rather
  than absorbed.

  This is a weaker interleaving than one segment's, and the record says so
  plainly rather than describing the segment order as though it were
  sample-level interleaving. It is the strongest available shape given a
  one-adapter-per-process runtime.

  ## The bulk window

  Write, yield ONE microtask, force the arm's own synchronous drain, stop
  the clock, then READ THE WRITTEN CELL BACK OUT OF THE DOM. Every arm is
  timed through that one shape, including the arms that do not need the
  yield — the predecessor's harness established that a window shape has to
  be uniform across arms or the comparison prices the window.

  The read-back is not decoration. On the predecessor's first pass, an
  EMPTY `flushSync` flushed only React's sync lane, so the floor arm's
  default-lane `root.render` landed outside the measured window entirely:
  80 of 320 floor samples ended on a stale cell and every other arm's
  ratio was inflated by the difference. On another, deleting the microtask
  made a substrate arm fail its read-back on EVERY write while reading
  FASTER, with a range that still overlapped the valid window's — the
  clock alone would have accepted it. Only the DOM read-back caught either.

  Owner: rf2-2rtt6.1 (standard); this arm rf2-2rtt6.4."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.p0-fixture :as fx]
            [re-frame.bench.p0-floor :as floor]
            [re-frame.bench.p0-harness :as h]
            [re-frame.bench.p0-reagent :as rg]
            [re-frame.bench.p0-uix :as ux]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [uix.dom :as uix-dom]))

(def frame-id
  "ONE frame behind every arm of a segment — the application shape. N
  frames would price frame construction, which is not what any of these
  rows is for."
  :p0/frame)

;; ---------------------------------------------------------------------------
;; Segments
;; ---------------------------------------------------------------------------

(def segments
  "The two adapter segments. `:id` is the substrate arm's id, which is also
  how a published record names the segment."
  [{:id :reagent-subs :adapter reagent-adapter/adapter :name "Reagent-on-subs"}
   {:id :uix-subs     :adapter uix-adapter/adapter     :name "UIx-on-subs"}])

(defonce ^:private captured (atom nil))

(defn- teardown!
  "Run one teardown phase, and RAISE with the phase named if it throws.

  It used to be `(catch :default _ nil)` at both sites, and this is the
  worst place in the run to lose an exception. A `destroy-frame!` that
  threw leaves the frame's subscription caches and its watches standing,
  and the very next thing that happens is the OTHER adapter being
  installed over the top of them — after which every figure in the
  segment is a figure for a page carrying the previous segment's reactive
  graph. rf2-flqpd is a finding ABOUT these exact transitions: a
  diagnostic that used a segment seam to reject a retention hypothesis,
  while discarding the evidence that the seam had failed, was arguing
  from a page it had not checked.

  A raise rather than a recorded note, because every caller already
  fails closed on one: the clock round's `.catch` sets `P0_ERROR`, the
  heap and retention drivers' `page.evaluate` rejects into their own
  failure lists, and no caller has to remember to ask.

  A PLAIN `js/Error`, not an `ex-info`, and the phase is in the MESSAGE
  rather than in `ex-data`. Under `:advanced` Closure renames the
  constructor of any `Error` subclass, so a CLJS `ExceptionInfo` crosses
  the CDP boundary as a two-letter munged token and nothing else — the
  first cut of this repair raised an `ex-info` and the driver dutifully
  failed closed on `page.evaluate: aj`, which stops the instrument
  without telling anyone what stopped it. Naming the phase is the whole
  point, so the name has to survive the build the rig actually runs
  under. Nothing reads the `ex-data`; the cause is appended as text.

  Note that this raises only on a REAL failure. `destroy-frame!` is a
  documented no-op for an id with no live incarnation, so the first
  segment entry of a run — when there is nothing to tear down — passes
  through it silently, and `destroy-adapter!` is already guarded by
  `current-adapter`."
  [phase f]
  (try
    (f)
    (catch :default e
      (throw (js/Error. (str "P0 segment teardown FAILED at " phase
                             " — the previous frame and adapter may still be standing, so "
                             "every figure in the segment that follows would be taken on a "
                             "page carrying the previous segment's reactive graph. Cause: "
                             (or (some-> e .-message) (str e))))))))

(defn enter-segment!
  "Tear down whatever adapter is installed, install this segment's, and
  stand the frame back up seeded from the shared fixture.

  All of it OUTSIDE every measured window. A mount window that contained
  `make-frame` and a seeding dispatch would price frame construction on
  whichever arm happened to own it.

  A teardown that throws STOPS the instrument and names the phase — see
  [[teardown!]]."
  [{:keys [adapter]}]
  (teardown! "destroy-frame!" #(rf/destroy-frame! frame-id))
  (when (rf/current-adapter)
    (teardown! "destroy-adapter!" #(rf/destroy-adapter!)))
  (rf/init! adapter)
  (fx/register!)
  (rf/make-frame {:id frame-id :initial-events [[:p0/seed]]})
  (reset! captured (rf/capture-frame frame-id))
  nil)

(defn- dispatch-sync! [ev]
  ((:dispatch-sync @captured) ev))

(defn write-all!
  "The bulk write, as a PUBLIC door, so a row that is not one of this
  namespace's own arms can drive the identical write (rf2-2rtt6.76).

  It is [[dispatch-sync!]] of the same `:p0/write-all` event the bulk
  clock arms use and nothing else — the allocation row's warm re-render
  has to be the write a real application pays for, through the event
  pipeline and the signal graph, or it is measuring a different page from
  the one the clock and heap rows measured."
  [v]
  (dispatch-sync! [:p0/write-all v])
  nil)

;; ---------------------------------------------------------------------------
;; MOUNT arms
;; ---------------------------------------------------------------------------
;;
;; Each arm is `{:id :mount :unmount}`; `:mount` takes a container and
;; answers a handle. Every arm uses ITS OWN substrate's mount door —
;; `reagent.dom.client` for Reagent, `uix.dom` for UIx, a bare
;; `react-dom/client` root for the floor — because that is the door an
;; application calls and a shared door would measure the shim.

(defn- floor-arm [id element-of]
  {:id      id
   :mount   (fn [container]
              (let [root (react-dom-client/createRoot container)]
                (.render root (element-of))
                root))
   :unmount (fn [root] (.unmount root))})

(defn- reagent-arm [id form-of]
  {:id      id
   :mount   (fn [container]
              (let [root (rdc/create-root container)]
                (rdc/render root (form-of))
                root))
   :unmount (fn [root] (rdc/unmount root))})

(defn- uix-arm [id element-of]
  {:id      id
   :mount   (fn [container]
              (let [root (uix-dom/create-root container)]
                (uix-dom/render-root (element-of) root)
                root))
   :unmount (fn [root] (uix-dom/unmount-root root))})

(def mount-witnesses
  "The mount witness families. `:arms-for` answers the arms for a segment,
  always `[floor <substrate>]` in that declaration order — the harness
  reorders them per sample index, so declaration order decides nothing."
  [{:id        :W1-list
    :doc       (str "a large template — " fx/w1-rows " rows, one boundary and ONE "
                    "re-frame2 subscription read per row")
    :elements   (fx/w1-elements fx/w1-rows)
    ;; 1,203 elements mounts in tens of timer quanta on every arm, so one
    ;; mount is a sample.
    :per-sample 1
    :arms-for (fn [segment-id]
                [(floor-arm :floor #(floor/w1 (mapv fx/row-value (range fx/w1-rows))))
                 (case segment-id
                   :reagent-subs (reagent-arm :reagent-subs
                                              #(rg/w1-root frame-id fx/w1-rows))
                   :uix-subs     (uix-arm :uix-subs
                                          #(ux/w1-root frame-id fx/w1-rows)))])}

   {:id        :W3-form
    :doc       (str "an ordinary " fx/w3-fields "-field form with controlled inputs — "
                    "the shape most applications are made of; one subscription read "
                    "per field")
    :elements   (fx/w3-elements fx/w3-fields)
    ;; 51 elements sits three to eight quanta above Chrome's 100 us clamp,
    ;; and at one mount a sample this instrument MEASURED both segments
    ;; returning exactly 0.75 ms — a ratio of precisely 1.0000 that was the
    ;; timer's resolution, not a tie. Eight mounts to a sample lifts every
    ;; arm clear of the clamp; the witness is unchanged.
    :per-sample 8
    :arms-for (fn [segment-id]
                [(floor-arm :floor
                            #(floor/w3 (mapv (fn [i] {:value (fx/field-value i)
                                                      :error (fx/field-error i)})
                                             (range fx/w3-fields))))
                 (case segment-id
                   :reagent-subs (reagent-arm :reagent-subs
                                              #(rg/w3-root frame-id fx/w3-fields))
                   :uix-subs     (uix-arm :uix-subs
                                          #(ux/w3-root frame-id fx/w3-fields)))])}])

;; ---------------------------------------------------------------------------
;; THE POSITIVE CONTROL — predicted before it is measured
;; ---------------------------------------------------------------------------

(defn control-arms
  "The clock's positive control: the floor's W1 page against the floor's W1
  page with twice the rows, and nothing else changed.

  Predicted ratio `w1-elements(600) / w1-elements(300)` = 2403/1203 =
  1.997. A window that is measuring the construction and commit of this
  page has to land there; a window that is measuring a scheduler hop, a
  constant, or an arm that rendered nothing has no reason to. Built on the
  FLOOR so it prices the instrument rather than a substrate, and therefore
  reads the same in both segments."
  []
  [(floor-arm :control-1x #(floor/w1 (mapv fx/row-value (range fx/w1-rows))))
   (floor-arm :control-2x #(floor/w1-double (mapv fx/row-value (range fx/w1-rows))))])

(def control-predicted floor/control-predicted-ratio)

;; ---------------------------------------------------------------------------
;; BULK arms
;; ---------------------------------------------------------------------------

(defn- cell-text [container i]
  (some-> (.querySelector container (str "[data-i=\"" i "\"]")) (.-textContent)))

(defn- floor-bulk-arm
  "The floor's `force!` renders INSIDE the `flushSync`, and that is not a
  convenience. `root.render` called outside a React event schedules at
  React's DEFAULT lane, and an empty `flushSync` flushes only the SYNC
  lane — so a floor arm that rendered in `write!` and flushed in `force!`
  would have its commit land outside the measured window. The DOM
  read-back caught exactly that on the predecessor's harness: 80 of 320
  floor samples ending on a stale cell."
  []
  (let [state (atom (vec (repeat fx/cells-n 0)))
        root  (volatile! nil)]
    {:id      :floor
     :mount   (fn [container]
                (let [rt (react-dom-client/createRoot container)]
                  (vreset! root rt)
                  (react-dom/flushSync (fn [] (.render rt (floor/u-grid @state))))
                  rt))
     :write!  (fn [i v]
                (if (= i :all)
                  (reset! state (vec (repeat fx/cells-n v)))
                  (swap! state assoc i v)))
     :force!  (fn [] (react-dom/flushSync
                       (fn [] (.render ^js @root (floor/u-grid @state)))))
     ;; NOT inside a flushSync — a root's own unmount opens one, and a
     ;; nested flush is scheduled rather than performed (p0-harness).
     :unmount (fn [rt] (.unmount rt))}))

(defn- subs-bulk-arm
  "Both substrate bulk arms, one constructor. They differ ONLY in the mount
  door and the drain: `reagent.core/flush` is Reagent's own documented
  synchronous render drain; the React-hook spine's `useSyncExternalStore`
  notification needs only an empty `flushSync` to be committed in this
  window. The WRITE is identical — one `dispatch-sync` of a shared event
  — which is what makes the row like-for-like."
  [id mount unmount force!]
  {:id      id
   :mount   mount
   :unmount unmount
   :write!  (fn [i v]
              (if (= i :all)
                (dispatch-sync! [:p0/write-all v])
                (dispatch-sync! [:p0/write-one i v])))
   :force!  force!})

(defn- reagent-bulk-arm []
  (subs-bulk-arm
    :reagent-subs
    (fn [container]
      (let [rt (rdc/create-root container)]
        (react-dom/flushSync (fn [] (rdc/render rt (rg/u-root frame-id))))
        rt))
    (fn [rt] (rdc/unmount rt))
    (fn [] (react-dom/flushSync (fn [] (r/flush))))))

(defn- uix-bulk-arm []
  (subs-bulk-arm
    :uix-subs
    (fn [container]
      (let [rt (uix-dom/create-root container)]
        (react-dom/flushSync (fn [] (uix-dom/render-root (ux/u-root frame-id) rt)))
        rt))
    (fn [rt] (uix-dom/unmount-root rt))
    (fn [] (react-dom/flushSync (fn [] nil)))))

(defn bulk-arms
  "`[floor <substrate>]` for a segment."
  [segment-id]
  [(floor-bulk-arm)
   (case segment-id
     :reagent-subs (reagent-bulk-arm)
     :uix-subs     (uix-bulk-arm))])

(defn mount-bulk-arms! [arms]
  (mapv (fn [a]
          (let [c (h/container!)]
            {:arm a :container c :handle ((:mount a) c)}))
        arms))

(defn release-bulk-arms! [mounts]
  (doseq [{:keys [arm handle container]} mounts]
    ((:unmount arm) handle)
    (.remove container)))

;; ---------------------------------------------------------------------------
;; The bulk window
;; ---------------------------------------------------------------------------

(defonce ^:private gen-seq (atom 1000))
(defn next-gen! [] (swap! gen-seq inc))

(defn timed-write!
  "Write, yield ONE microtask, force the arm's own synchronous drain, stop
  the clock — then VERIFY at the DOM that the cell holds what was written.
  Answers a promise of `{:ms :write-ms :gap-ms :force-ms :ok?}`.

  The legs are published beside the total so the microtask boundary is
  VISIBLE rather than assumed empty: whatever a substrate's notification
  does, it does it in `:gap-ms`, and the floor — which needs no yield —
  is the in-situ control that says what an empty gap costs."
  [{:keys [arm container]} i v]
  (let [t0 (h/now-ms)]
    ((:write! arm) i v)
    (let [t1 (h/now-ms)]
      (-> (js/Promise.resolve nil)
          (.then (fn [_]
                   (let [t2 (h/now-ms)]
                     ((:force! arm))
                     (let [t3    (h/now-ms)
                           probe (if (= i :all) 0 i)]
                       {:ms       (- t3 t0)
                        :write-ms (- t1 t0)
                        :gap-ms   (- t2 t1)
                        :force-ms (- t3 t2)
                        :ok?      (and (= (str v) (cell-text container probe))
                                       (or (not= i :all)
                                           (= (str v) (cell-text container
                                                                 (dec fx/cells-n)))))}))))))))

(defn chain
  "Fold `xs` into a serial promise chain, threading an accumulator."
  [init xs f]
  (reduce (fn [p x] (.then p (fn [acc] (f acc x)))) (js/Promise.resolve init) xs))

(def writes-per-sample
  "How many writes one SAMPLE contains.

  Chrome clamps `performance.now()` to 100 us, and BOTH rows have to clear
  it on their FASTEST arm, which is the floor.

  A single broad write read 0.25-0.50 ms on the floor when this instrument
  was first run — two to five quanta, close enough that the floor's own
  two segment readings came out exactly 2x apart on quantisation alone.
  Four writes to a sample lifts it to a couple of milliseconds. A NARROW
  write is smaller again: measured one at a time on the predecessor's
  harness both the floor and Reagent returned exactly 0.1 ms, the quantum
  itself, which is a null result wearing a different hat.

  The per-write figure is always the sample divided by this number, and
  every write in a sample is verified at the DOM individually."
  {:broad 4 :narrow 20})

(defn- n-writes! [mnt kind n]
  (chain {:ms 0 :write-ms 0 :gap-ms 0 :force-ms 0 :bad 0 :total 0} (range n)
         (fn [acc _]
           (let [v (next-gen!)
                 i (if (= kind :broad) :all (mod v fx/cells-n))]
             (-> (timed-write! mnt i v)
                 (.then (fn [{:keys [ms write-ms gap-ms force-ms ok?]}]
                          (cond-> (-> acc
                                      (update :ms + ms)
                                      (update :write-ms + write-ms)
                                      (update :gap-ms + gap-ms)
                                      (update :force-ms + force-ms)
                                      (update :total inc))
                            (not ok?) (update :bad inc)))))))))

(defn bulk-round!
  "One round of a bulk row: `warmup + samples` sample indices, every arm
  written at every index, order chosen by its MEASURED adjacency property.

  The warm-up indices are measured and thrown away, and `:previous` is
  threaded through them, so the first RECORDED sample has a real
  predecessor. A warm-up run as a separate loop leaves a `<none>`
  predecessor stratum that this instrument measured at 1.35x its siblings
  with disjoint ranges — a real cold-start effect, and not one to hide.

  Answers a promise of `{:readings :legs :order :bad :total :position
  :schedule}`."
  [mounts kind {:keys [warmup samples]} position-start]
  (let [k     (count mounts)
        n     (get writes-per-sample kind)
        total (+ warmup samples)
        sched (h/choose-schedule k total)
        order (:fn sched)]
    (chain {:readings (zipmap (map #(:id (:arm %)) mounts) (repeat []))
            :legs     (zipmap (map #(:id (:arm %)) mounts) (repeat []))
            :order    []
            :bad      0
            :total    0
            :schedule (dissoc sched :fn)
            :position position-start
            :previous nil}
           (for [s (range total) j (order k s)] [s j])
           (fn [acc [s j]]
             (let [mnt (nth mounts j)
                   id  (:id (:arm mnt))]
               (-> (n-writes! mnt kind n)
                   (.then (fn [{:keys [ms write-ms gap-ms force-ms bad total]}]
                            ;; Between samples, never inside a window — see
                            ;; `p0-harness/collect!` for the drift it removes.
                            (h/collect!)
                            (cond-> (assoc acc :previous id)
                              (>= s warmup)
                              (-> (update :bad + bad)
                                  (update :total + total)
                                  (update-in [:readings id] conj ms)
                                  (update-in [:legs id] conj {:write write-ms
                                                              :gap   gap-ms
                                                              :force force-ms})
                                  (update :order conj {:arm         id
                                                       :value       ms
                                                       :predecessor (:previous acc)
                                                       :position    (:position acc)})
                                  (update :position inc)))))))))))

(defn leg-summary
  "Per-arm p50 of each leg of the window, in ms per SAMPLE."
  [legs]
  (into {}
        (map (fn [[id xs]]
               [id (when (seq xs)
                     {:write-ms (h/p50 (map :write xs))
                      :gap-ms   (h/p50 (map :gap xs))
                      :force-ms (h/p50 (map :force xs))})]))
        legs))

(defn canon-of [mounts] (mapv (fn [m] (h/canonical (:container m))) mounts))

(ns re-frame.bench.hicasso.clock-app
  "THE CANDIDATE'S CLOCK — the page half (rf2-0qj9w).

  The programme has no wall-clock measurement of its own candidate. Hook
  count and per-read retained heap are measured; mount, bulk K=100/300,
  narrow and per-keystroke are not, and every clock figure published so
  far — `M1` mount 1.0150×, bulk-broad 0.6291× — is about the DONORS.
  This entry, its driver `clock_run.cjs` and
  [[re-frame.bench.hicasso.clock-views]] are the three files that close
  that.

  ## This page does not decide when a sample starts, and that is the point

  Every other clock entry in this lane wraps `performance.now()` around a
  `flushSync` and banks the span. That window ends when the JavaScript
  returns — **before** the style recalculation, the layout, the pre-paint
  and the paint that the mutation causes. Under-reporting would be
  tolerable if it were common-mode; it is not. How much work a substrate
  leaves for the browser after its own call stack unwinds is exactly what
  differs between these arms, and Hicasso's whole design concerns WHEN
  work happens. An in-page window flatters whichever arm defers most.

  So this entry exposes OPERATIONS and no clock. `clock_run.cjs` reads
  Chrome's own renderer counters over the Chrome DevTools Protocol
  (`Performance.getMetrics`) on either side of one operation, and the
  operation does not resolve until the browser has produced the frame
  that follows it. The number banked is therefore main-thread task time
  INCLUDING style, layout and paint recording — the work a user waits
  for. `TaskDuration` is a protocol value rather than a web-exposed one,
  so it does not carry the Spectre clamp `performance.now()` does.

  The in-page span is still taken, on the same operation in the same
  sample, and published beside the frame-inclusive one. The gap between
  them is a measurement of the error the other instrument makes, per arm.

  ## THREE segments, one substrate arm each, and why that is forced

  `install-adapter!` is once per process, so Reagent and UIx cannot be
  interleaved inside one round — that much this lane already knew. What
  this row adds is a second, sharper constraint: a bulk write here is
  `frame/replace-app-db!`, and **every** arm mounted against that frame
  re-renders when it lands. Two substrate arms standing in one segment
  would each pay for the other's writes, and the clock would be reading a
  page carrying an arm that is not under test. So the candidate gets a
  segment of its own:

      :reagent-subs   floor, reagent-subs, ctl-2x
      :uix-subs       floor, uix-subs,     ctl-2x
      :hicasso        floor, hicasso,      ctl-2x

  The candidate's segment installs the **UIx adapter** — the one Arm 1's
  own witnesses install, and the substrate its React-hook spine is built
  over. The floor runs in all three: it holds no re-frame state, reads no
  subscription and is untouched by which adapter is installed, so every
  published figure is a ratio to the floor measured in THAT segment of
  THAT round, and a cross-segment figure is a ratio of two
  floor-normalised ratios with the seam cancelled. That is a weaker
  interleaving than one segment's and the record says so rather than
  describing it as though it were sample-level.

  ## What the driver calls, in order

      HCLOCK.enterSegment(segId)      install adapter, register, seed
      HCLOCK.plan(rowId, segId)       the arm ids, in plan order
      HCLOCK.canon(rowId, armId)      the page this arm builds, hashed
      HCLOCK.prepare(rowId, armId)    stand up whatever the row writes to
      HCLOCK.sample(rowId, armId)     ONE operation, settled to the next frame
      HCLOCK.finish(rowId, armId)     release it
      HCLOCK.tally()                  N unverified of M

  A keystroke row is the one exception: the driver sends the key itself
  through the protocol's input domain, because a JavaScript-dispatched
  event is not a user interaction and Event Timing reports user
  interactions. The page supplies [[settle-and-verify!]] for the half
  after the key.

  Owner: rf2-2rtt6.1 (standard); this entry rf2-0qj9w."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as hmount]
            [re-frame.bench.hicasso.arm1.runtime :as hrt]
            [re-frame.bench.hicasso.clock-views :as kv]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.p0-reagent-views :as v]
            [re-frame.bench.hicasso.p0-uix-views :as ux]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [uix.dom :as uix-dom]))

;; ---------------------------------------------------------------------------
;; Rows
;; ---------------------------------------------------------------------------

(def rows
  "The five clock rows the candidate has never had, and what one SAMPLE of
  each is.

  `:bulk100` and `:bulk300` are validation.md's `bulk K=100/300`: K is how
  many of the 300 mounted boundaries a single commit changes. The floor
  re-renders its whole tree either way — it has no reactive graph to
  localise with — so a K-sensitive arm shows up as a ratio that MOVES with
  K and a K-insensitive one as one that does not, which is the whole
  localisation question stated as an experiment."
  {:M1        {:kind :mount :cells 300
               :why "mount, 300 sub-reading boundaries, 901 elements — a bar row"}
   :bulk300   {:kind :bulk :k 300
               :why "one commit, all 300 boundaries change — a bar row"}
   :bulk100   {:kind :bulk :k 100
               :why "one commit, 100 of 300 boundaries change — the K=100 rung"}
   :narrow    {:kind :bulk :k 1
               :why "one commit, exactly one boundary changes — localisation"}
   :keystroke {:kind :keystroke :cells 100
               :why "one real keypress into a controlled field over 100 boundaries"}})

;; ---------------------------------------------------------------------------
;; Segments
;; ---------------------------------------------------------------------------

(def ^:private segments
  "Three, one substrate arm each. `:hicasso` installs the UIx adapter —
  Arm 1's React-hook spine is built over it and its own witnesses install
  it — so the segment names the arm under test rather than the adapter
  underneath it, and the record says which."
  [{:id :reagent-subs :adapter reagent-adapter/adapter :name "Reagent-on-subs"}
   {:id :uix-subs     :adapter uix-adapter/adapter     :name "UIx-on-subs"}
   {:id :hicasso      :adapter uix-adapter/adapter     :name "Hicasso Arm 1 (UIx adapter)"}])

(defn- segment-named [id] (first (filter #(= id (:id %)) segments)))

(defn- zeros [n] (vec (repeat n 0)))

(defonce ^:private state
  (atom {:segment nil :prepared {} :tally nil :op 0 :gen 1000 :cells []}))

(defn- next-gen! [] (:gen (swap! state update :gen inc)))
(defn- next-op! [] (:op (swap! state update :op inc)))

(defn enter-segment!
  "Tear down whatever adapter is installed, install this segment's,
  re-register the sub graph and stand the frame back up seeded.

  All of it OUTSIDE every measured window. A destroy that throws is
  RECORDED rather than swallowed: this is the segment seam, and a frame
  that did not tear down keeps its subscription caches and its watches
  while the other adapter is installed over the top of them — after which
  every figure in the segment is a figure for a page carrying the previous
  segment's reactive graph."
  [seg-id]
  (let [{:keys [adapter]} (segment-named seg-id)]
    (try (rf/destroy-frame! v/subs-frame)
         (catch :default e (lane/teardown-failure! "enter-segment! destroy-frame!" e)))
    (when (rf/current-adapter)
      (try (rf/destroy-adapter!)
           (catch :default e (lane/teardown-failure! "enter-segment! destroy-adapter!" e))))
    ;; `reset-runtime!` drops every cell, edge and cached entry the
    ;; candidate holds — and it calls `forget-frame-ops!` on the way, so a
    ;; destroyed-and-recreated frame cannot hand the arm a memoised bundle
    ;; pointing at the previous incarnation.
    (hrt/reset-runtime!)
    (rf/init! adapter)
    (v/register!)
    (kv/register-draft!)
    (rf/make-frame {:id v/subs-frame})
    (frame/replace-app-db! v/subs-frame (v/seed-cells v/cells-n 0))
    (lane/leave-act-environment!)
    (swap! state assoc :segment seg-id :cells (zeros v/cells-n))
    nil))

;; ---------------------------------------------------------------------------
;; Arms — one door each, and every one the door its own application calls
;; ---------------------------------------------------------------------------

(defn- floor-mount-arm
  "`scale` is how many times the witness's own page this arm builds, and
  `:control?` is DERIVED from it: an arm that builds a different page ON
  PURPOSE is exactly the arm the canonical-DOM gate must exempt, and
  keeping the two facts independent is how a control's doubled page goes
  unchecked."
  [id n scale]
  {:id       id
   :scale    scale
   :cells    n
   :control? (not= 1 scale)
   :mount    (fn [container]
               (let [root (react-dom-client/createRoot container)]
                 (react-dom/flushSync
                   (fn [] (.render root (v/m1-floor (zeros n)))))
                 root))
   :unmount  (fn [root] (.unmount root))})

(defn- m1-arms [seg-id]
  [(floor-mount-arm :floor v/cells-n 1)
   (case seg-id
     :reagent-subs {:id      :reagent-subs
                    :cells   v/cells-n
                    :mount   (fn [container]
                               (let [root (rdc/create-root container)]
                                 (react-dom/flushSync
                                   (fn [] (rdc/render root (v/subs-root v/m1-subs v/cells-n))))
                                 root))
                    :unmount (fn [root] (rdc/unmount root))}
     :uix-subs     {:id      :uix-subs
                    :cells   v/cells-n
                    :mount   (fn [container]
                               (let [root (uix-dom/create-root container)]
                                 (react-dom/flushSync
                                   (fn [] (uix-dom/render-root (ux/subs-root ux/m1 v/cells-n) root)))
                                 root))
                    :unmount (fn [root] (uix-dom/unmount-root root))}
     ;; `mount/root!` is the candidate's own door — what an Arm 1
     ;; application calls. A shared door would measure the shim.
     ;;
     ;; `unmount!`'s half and NOT `release!`: `release!` also resets the
     ;; runtime, which would drop every cell and cached entry between
     ;; samples and hand the arm a cold start each time. No donor gets
     ;; that treatment — the frame and its subscription cache outlive a
     ;; Reagent or UIx unmount — so the candidate is measured with its own
     ;; caches surviving exactly as theirs do. The cell reaper still
     ;; disposes a cell whose last reader unmounted, one macrotask later,
     ;; which is the arm's own behaviour and not the harness's to
     ;; suppress.
     :hicasso      {:id      :hicasso
                    :cells   v/cells-n
                    :mount   (fn [container]
                               (hmount/root! container v/subs-frame (kv/m1 v/cells-n)))
                    :unmount (fn [handle] (.unmount ^js (:root handle)))})
   (floor-mount-arm :ctl-2x (* 2 v/cells-n) 2)])

(defn- kb-floor-arm [id busy]
  {:id           id
   :cells        kv/kb-cells-n
   :control?     (pos? busy)
   :lower-bound? true
   :mount        (fn [container]
                   (let [root (react-dom-client/createRoot container)]
                     (react-dom/flushSync
                       (fn [] (.render root (kv/kb-floor-element kv/kb-cells-n busy))))
                     root))
   :unmount      (fn [root] (.unmount root))})

(defn- kb-arms [seg-id]
  [(kb-floor-arm :floor 0)
   (case seg-id
     :reagent-subs {:id      :reagent-subs
                    :cells   kv/kb-cells-n
                    :mount   (fn [container]
                               (let [root (rdc/create-root container)]
                                 (react-dom/flushSync
                                   (fn [] (rdc/render root (kv/r-kb-root kv/kb-cells-n))))
                                 root))
                    :unmount (fn [root] (rdc/unmount root))}
     :uix-subs     {:id      :uix-subs
                    :cells   kv/kb-cells-n
                    :mount   (fn [container]
                               (let [root (uix-dom/create-root container)]
                                 (react-dom/flushSync
                                   (fn [] (uix-dom/render-root (kv/u-kb-root kv/kb-cells-n) root)))
                                 root))
                    :unmount (fn [root] (uix-dom/unmount-root root))}
     :hicasso      {:id      :hicasso
                    :cells   kv/kb-cells-n
                    :mount   (fn [container]
                               (hmount/root! container v/subs-frame (kv/kb-form kv/kb-cells-n)))
                    :unmount (fn [handle] (.unmount ^js (:root handle)))})
   (kb-floor-arm :ctl-50ms 50)])

(defn- arms-for [row-key seg-id]
  (if (= :keystroke row-key) (kb-arms seg-id) (m1-arms seg-id)))

(defn- arm-named [row-key arm-id]
  (first (filter #(= arm-id (:id %)) (arms-for row-key (:segment @state)))))

(defn- expected-elements [row-key arm]
  (if (= :keystroke row-key)
    (kv/kb-elements (:cells arm))
    (v/m1-elements (:cells arm))))

;; ---------------------------------------------------------------------------
;; The write, and its read-back
;; ---------------------------------------------------------------------------

(defn- write-cells!
  "Install a new app-db in which `k` of the 300 cells carry `val`, and
  answer the cells to probe.

  `k = 300` is the broad row, `k = 100` the K=100 rung, `k = 1` narrow.
  The narrow cell ROTATES with the operation — `(mod (* 7 op) 300)`, and 7
  is coprime with 300 so any run of consecutive ops takes different cells
  — and the value is fresh on every write, so a stale page holds the
  PREVIOUS value at the probed cell and fails its read-back.

  **The vector CARRIES FORWARD rather than being rebuilt from zeros.** A
  narrow write starting from a fresh zero vector would reset the previous
  op's cell as well as setting this one, so TWO boundaries would change
  and the row would not measure what its name says. K is the number of
  boundaries whose value moves, and this is what makes that true."
  [k val op]
  (let [n     v/cells-n
        prev  (:cells @state)
        start (if (= k 1) (mod (* 7 op) n) 0)
        cells (reduce (fn [cs d] (assoc cs (mod (+ start d) n) val)) prev (range k))]
    (swap! state assoc :cells cells)
    (frame/replace-app-db! v/subs-frame {:cells cells})
    (if (= k 1) [start] [start (mod (+ start (dec k)) n)])))

(defn- drain!
  "The arm's own synchronous drain, uniform in SHAPE across arms because a
  window shape that differs between arms prices the window.

  Reagent's is `reagent.core/flush` inside one `flushSync`. UIx's and
  Hicasso's is an EMPTY `flushSync`, because a `useSyncExternalStore`
  notification schedules at React's SYNC lane and an empty flush is what
  lets it land. Not `flush-views!` and not `act` — `act` diverts work to a
  queue that is not the browser's, and every window here is taken outside
  it."
  [arm-id]
  (if (= :reagent-subs arm-id)
    (react-dom/flushSync (fn [] (r/flush)))
    (react-dom/flushSync (fn [] nil))))

;; ---------------------------------------------------------------------------
;; The frame settle — what makes this instrument frame-inclusive
;; ---------------------------------------------------------------------------

(defn- settle-frame
  "A promise that resolves AFTER the browser has produced the frame that
  follows the mutation.

  `requestAnimationFrame` runs BEFORE paint, so a callback registered
  there is not enough on its own; the `setTimeout` inside it lands in the
  first task after that frame's rendering lifecycle has run. This is the
  standard after-paint idiom, and it is what makes the driver's
  `Performance.getMetrics` delta include the style recalculation, the
  layout and the paint recording that the in-page span excludes.

  The WAIT costs wall clock and costs the measurement nothing: an idle
  browser accrues no task time, so the counters the driver reads move only
  for work that was actually done."
  []
  (js/Promise. (fn [resolve]
                 (js/requestAnimationFrame (fn [] (js/setTimeout resolve 0))))))

;; ---------------------------------------------------------------------------
;; Prepare / sample / finish
;; ---------------------------------------------------------------------------

(defn prepare!
  "Stand up whatever this row writes to, outside every window, and check
  the page it built against the arm's own arithmetic.

  A mount row prepares nothing: its operation IS the mount. Every other
  row mounts once here and writes to that standing mount thereafter.

  The prepared mounts are a MAP keyed by arm, not one slot, because the
  sample loop interleaves arms and a single slot would force block
  interleaving — which is what the arm-order guard exists to refuse."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)]
    (if (= :mount (:kind (get rows row-key)))
      (settle-frame)
      (let [container (lane/fresh-container!)
            handle    ((:mount arm) container)
            expected  (expected-elements row-key arm)
            got       (lane/element-count container)]
        (when-not (= expected got)
          (throw (js/Error. (str "the " (name arm-id) " arm built " got
                                 " elements where its own " (:cells arm)
                                 " cells make " expected))))
        (swap! state assoc-in [:prepared arm-id]
               {:arm arm :container container :handle handle})
        (settle-frame)))))

(defn finish!
  "Release this arm's standing mount. Never timed."
  [_row-key arm-id]
  (when-some [p (get-in @state [:prepared arm-id])]
    (lane/release! p)
    (swap! state update :prepared dissoc arm-id))
  (settle-frame))

(defn- bank! [ok?]
  (let [t (:tally @state)]
    (swap! t (fn [{:keys [of bad]}] {:of (inc of) :bad (if ok? bad (inc bad))}))
    ok?))

(defn- sample-mount!
  "ONE mount. The container is created and attached OUTSIDE the in-page
  span — a `document.createElement` billed to one arm and not another is a
  systematic error the size of some of the effects here — and the mount is
  read back out of the document against the arm's own element arithmetic
  before it is released."
  [row-key arm]
  (let [container (lane/fresh-container!)
        handle    (volatile! nil)
        t0        (lane/now-ms)
        _         (vreset! handle ((:mount arm) container))
        ms        (- (lane/now-ms) t0)
        ok?       (= (expected-elements row-key arm) (lane/element-count container))]
    (bank! ok?)
    (.then (settle-frame)
           (fn [_]
             (lane/release! {:arm arm :container container :handle @handle})
             #js {:inPageMs ms :ok ok?}))))

(defn- sample-bulk!
  "ONE commit. Write, drain, stop the in-page span, then READ THE WRITTEN
  CELLS BACK OUT OF THE DOM before settling the frame.

  The read-back is not decoration. On a predecessor harness, deleting the
  drain made a substrate arm fail its read-back on EVERY write while
  reading FASTER, with a range that still overlapped the valid window's —
  the clock alone would have accepted it."
  [row-key arm]
  (let [k      (:k (get rows row-key))
        val    (next-gen!)
        op     (next-op!)
        mnt    (get-in @state [:prepared (:id arm)])
        cont   (:container mnt)
        root   (:handle mnt)
        floor? (contains? #{:floor :ctl-2x} (:id arm))
        t0     (lane/now-ms)
        ;; The floor renders INSIDE the `flushSync`, element tree and all,
        ;; and neither half is a convenience. `root.render` called outside
        ;; a React event schedules at React's DEFAULT lane and an empty
        ;; `flushSync` flushes only the SYNC lane, so a floor arm that
        ;; rendered outside one would have its commit land outside the
        ;; measured window entirely — the recorded fault is 80 of 320
        ;; floor samples ending on a cell that still held its old value.
        ;; And hoisting the element tree out would under-charge the
        ;; denominator every published ratio is taken against.
        probes (if floor?
                 (do (react-dom/flushSync
                       (fn [] (.render ^js root
                                       (v/m1-floor (vec (repeat (:cells arm) val))))))
                     [0 (dec (:cells arm))])
                 (let [ps (write-cells! k val op)]
                   (drain! (:id arm))
                   ps))
        ms     (- (lane/now-ms) t0)
        ok?    (every? (fn [i] (= (str val) (lane/text-at cont i))) probes)]
    (bank! ok?)
    (.then (settle-frame) (fn [_] #js {:inPageMs ms :ok ok?}))))

(defn sample!
  "ONE operation of one arm, settled to the next frame."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)]
    (case (:kind (get rows row-key))
      :mount (sample-mount! row-key arm)
      :bulk  (sample-bulk! row-key arm)
      (throw (js/Error. (str "row " (name row-key)
                             " has no in-page sample door — the driver owns its input"))))))

;; ---------------------------------------------------------------------------
;; The keystroke half the driver cannot do from JavaScript
;; ---------------------------------------------------------------------------

(defn focus-draft!
  "Focus this arm's controlled field, so the driver's key events land in
  it. Outside every window."
  [arm-id]
  (let [cont (:container (get-in @state [:prepared arm-id]))
        el   (some-> cont (.querySelector "input.draft"))]
    (if (some? el) (do (.focus ^js el) true) false)))

(defn settle-and-verify!
  "The half of a keystroke sample the page owns: settle the frame the
  keypress caused, then read the field's value back out of the DOM.

  The driver sends the key through the protocol's input domain rather than
  dispatching one from JavaScript. A JavaScript-dispatched event is not a
  user interaction, and Event Timing reports user interactions."
  [arm-id expected]
  (.then (settle-frame)
         (fn [_]
           (let [cont (:container (get-in @state [:prepared arm-id]))
                 el   (some-> cont (.querySelector "input.draft"))
                 got  (some-> ^js el .-value)]
             #js {:ok (bank! (= expected got)) :got (str got)}))))

;; ---------------------------------------------------------------------------
;; The fairness gate
;; ---------------------------------------------------------------------------

(defn- str-hash
  "A 32-bit FNV-style hash of `s`, so the driver can compare the pages
  three segments built without moving three 18 KB strings across the
  protocol per row."
  [s]
  (let [n (count s)]
    (loop [i 0 h (int -2128831035)]
      (if (< i n)
        ;; `Math.imul` and not `*`: a 32-bit FNV multiply overflows the
        ;; double's exact-integer range, and a hash that loses low bits is
        ;; a hash that lets two different pages compare equal.
        (recur (inc i) (js/Math.imul (bit-xor h (.charCodeAt s i)) 16777619))
        h))))

(defn canon!
  "Mount this arm once OUTSIDE every window and answer the page it builds,
  with attribute names SORTED and then hashed.

  `innerHTML` preserves insertion order and two front ends write props in
  different orders, so comparing it compares the serialiser rather than
  the page. This is the entire fairness guarantee of a cross-arm ratio:
  without it two arms can be timed against each other while building
  different pages. The driver compares across all three segments, because
  that is where the candidate meets its donors."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)
        c   (lane/fresh-container!)
        h   ((:mount arm) c)
        s   (lane/canonical c)]
    (lane/release! {:arm arm :container c :handle h})
    ;; The canon mount wrote nothing, but a Hicasso or Reagent arm mounted
    ;; here has warmed the frame's caches. Re-seed so a row's first sample
    ;; meets the same app-db every other one does.
    (frame/replace-app-db! v/subs-frame (v/seed-cells v/cells-n 0))
    (swap! state assoc :cells (zeros v/cells-n))
    #js {:arm (name arm-id) :hash (str-hash s) :bytes (count s)
         :control (boolean (:control? arm))}))

;; ---------------------------------------------------------------------------
;; The driver's front door
;; ---------------------------------------------------------------------------

(defn -main []
  (lane/leave-act-environment!)
  (swap! state assoc :tally (lane/tally))
  (set! (.-HCLOCK js/window)
        #js {:rows     (clj->js (mapv (fn [[k m]] {:id (name k) :why (:why m)}) rows))
             :segments (clj->js (mapv (fn [s] {:id (name (:id s)) :name (:name s)}) segments))
             :cellsN   v/cells-n
             :kbCellsN kv/kb-cells-n
             :enterSegment  (fn [seg] (enter-segment! (keyword seg)) true)
             :plan          (fn [row seg]
                              (clj->js (mapv (fn [a] {:id      (name (:id a))
                                                      :scale   (or (:scale a) 1)
                                                      :control (boolean (:control? a))
                                                      :lowerBound (boolean (:lower-bound? a))})
                                             (arms-for (keyword row) (keyword seg)))))
             :canon         (fn [row arm] (canon! (keyword row) (keyword arm)))
             :prepare       (fn [row arm] (prepare! (keyword row) (keyword arm)))
             :sample        (fn [row arm] (sample! (keyword row) (keyword arm)))
             :finish        (fn [row arm] (finish! (keyword row) (keyword arm)))
             :focusDraft    (fn [arm] (focus-draft! (keyword arm)))
             :settleVerify  (fn [arm expected] (settle-and-verify! (keyword arm) expected))
             :settle        (fn [] (settle-frame))
             :tally         (fn [] (clj->js (lane/tally-value (:tally @state))))
             :teardownCheck (fn [] (clj->js (mapv :where (lane/drain-teardown-failures!))))
             :runtime       (fn [] (pr-str (lane/runtime-label)))
             ;; Both censuses, because neither sees the other's references:
             ;; `lane/residue` counts attached containers and the frame's
             ;; sub-cache, `runtime/residue` counts the candidate's own
             ;; cells, edges and cached entries.
             :residue       (fn [] (pr-str {:lane (lane/residue v/subs-frame)
                                            :arm1 (hrt/residue)}))})
  (set! (.-HCLOCK_READY js/window) true)
  (js/console.log ";; HCLOCK ready")
  nil)

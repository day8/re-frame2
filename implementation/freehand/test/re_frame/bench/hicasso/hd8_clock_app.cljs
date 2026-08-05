(ns re-frame.bench.hicasso.hd8-clock-app
  "HD-008's donor arms behind the CLOCK-OF-RECORD door (rf2-2rtt6.31).

  The published HD-008 rows were taken on the in-page `performance.now()`
  window, which the mount-gate amendment (recorded on `rf2-2rtt6.1`,
  2026-08-02) demotes to a DIAGNOSTIC: the bar's adjudicating clock is raw
  `TaskDuration` — the arm's script AND the frame it caused — sampled
  through the instrument's frame-settlement door. No instrument in the
  repository takes the HD-008 arms through that door, and the re-take this
  bead orders adjudicates against `<= 1.10x` direct UIx ON that clock, so
  this entry exists.

  ## What this page is, and what it is not

  It is [[re-frame.bench.hicasso.clock-app]]'s M1 contract — plan / canon /
  sample / reap / settle, a plumb tare, a `ctl-2x` doubling control — over
  [[re-frame.bench.hicasso.hd8-rows]]'s OWN mount arms, unchanged: the same
  mount doors, the same per-arm frames, the same witnesses (`M`, 300
  boundary rows, `3 + 3N` elements; `U`, a 300-cell grid, `1 + N`), the
  same `lane/mount-arm!` window for the in-page diagnostic reading. It
  measures MOUNT ONLY. The write rows are not here: `rf2-d2tzk` records
  that the bulk row's floor sits on the clock clamp on the in-page
  instrument, and on this box the bulk-class rows cannot hold a
  difference-statistic control at the ~3.5% noise floor a magnitude needs
  (`rf2-7iqb5`, `the-candidates-clock.md` §6) — so a bulk magnitude from
  this instrument would be refused, and an instrument built to be refused
  is an instrument nobody had asked for.

  ## This page exposes OPERATIONS and no clock

  The driver (`hd8_clock_run.cjs`) reads `Performance.getMetrics` over the
  DevTools protocol on either side of one operation, and the operation
  does not resolve until the browser has produced the frame that follows
  it ([[settle-frame]] — `requestAnimationFrame` then `setTimeout 0`, the
  standard after-paint idiom). Raw `TaskDuration` is a protocol value, so
  it does not carry the Spectre clamp, and — unlike `taskNet`, whose
  subtraction removes the operation's own script when the operation runs
  inside a protocol command (rf2-yd52q, rf2-emvod) — it is one quantity
  through every door. Every arm here goes through ONE door:
  `page.evaluate -> HD8CLOCK.sample`, the plumb tare included, so the
  door's own cost is common-mode and subtracted.

  ## One adapter per process, three runs — HD-008's own partition

  Spec 006 installs exactly one adapter per process, and a mount is a
  one-shot read, so the mount plans carry the frontier and donor arms in
  every run and each run adds the Reagent path native to its adapter —
  [[re-frame.bench.hicasso.hd8-rows]]'s `arm-ids-for`, minus `:donor-fh`
  (that arm is `rf2-2rtt6.29`'s page's subject, not this re-take's; the
  six published arms are what the bead re-takes).

  Owner: rf2-2rtt6.1 (standard); this entry rf2-2rtt6.31."
  (:require ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.reagent-slim :as slim-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.hd8-rows :as rows]
            [re-frame.bench.hicasso.hd8-witnesses :as w]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Which adapter this run installs — hd8-app's own parsing, unchanged
;; ---------------------------------------------------------------------------

(defn- query-adapter []
  (let [s (some-> js/window .-location .-search)]
    (cond
      (nil? s)                            :uix
      (re-find #"adapter=reagent-slim" s) :slim
      (re-find #"adapter=slim" s)         :slim
      (re-find #"adapter=reagent" s)      :reagent
      :else                               :uix)))

(defn- install! [which]
  (rf/init! (case which
              :reagent reagent-adapter/adapter
              :slim    slim-adapter/adapter
              uix-adapter/adapter))
  which)

;; ---------------------------------------------------------------------------
;; The arm plans — the six published arms, plus the tare and the control
;; ---------------------------------------------------------------------------

(def ^:private arm-ids-for
  "hd8-rows' mount partition over the SIX PUBLISHED ARMS. `:donor-fh` is
  deliberately absent — it is rf2-2rtt6.29's arm, measured on its own page;
  including it here would change `k` for a comparison this bead does not
  make."
  {:uix     [:floor :uix :donor-r1 :donor-r2]
   :reagent [:floor :reagent :uix :donor-r1 :donor-r2]
   :slim    [:floor :reagent-slim :uix :donor-r1 :donor-r2]})

(def ^:private plumb-arm
  "THE TARE — [[re-frame.bench.hicasso.clock-app]]'s argument, restated
  short: one sample costs a `Runtime.callFunctionOn` round trip, the task
  that runs it, a rAF callback, a `setTimeout` callback and the frame the
  browser produces, identically for every arm. This arm measures exactly
  that and every published figure subtracts it."
  {:id      :plumb
   :cells   0
   :control? true
   :plumb?  true
   :mount   (fn [_container _props _n] nil)
   :unmount (fn [_] nil)})

(def ^:private ctl2x-m-rows
  "The doubling control's data — 600 rows of the same shape `:hd8/seed`
  writes, built once at load so no measured window pays for it."
  (mapv #(str "r" %) (range (* 2 w/rows-n))))

(def ^:private ctl2x-u-cells (vec (repeat (* 2 w/cells-n) "0")))

(defn- ctl2x-arm
  "The floor at TWICE the witness's boundaries — `clock_run.cjs`'s mount
  control, on hd8's own floor markup. Prediction 2.00x; `rf2-jcm3p`
  records that it UNDERSHOOTS on mount rows (1.8173x over rf2-emvod's
  seven runs) because an additive per-sample constant survives the tare,
  and no changed-set control can reach a mount — a mount has no standing
  page. What it certifies is that the instrument has page-proportional
  SIGNAL; what it cannot certify is exactness, and the driver states both."
  [witness-id]
  {:id       :ctl-2x
   :cells    (* 2 w/rows-n)
   :control? true
   :mount    (fn [container _props _n]
               (let [r (react-dom-client/createRoot container)]
                 (.render r (case witness-id
                              :M (w/floor-m {:rows ctl2x-m-rows :n (* 2 w/rows-n)})
                              :U (w/floor-u {:cells ctl2x-u-cells :n (* 2 w/cells-n)})))
                 r))
   :unmount  (fn [r] (.unmount r))})

(def ^:private row-witness {:mount-M :M :mount-U :U})

(defn- witness-arm [witness-id arm-id]
  (case witness-id
    :M (rows/m-arm arm-id)
    :U (rows/u-arm arm-id)))

(defonce ^:private state (atom {:adapter nil :pending nil :tally nil}))

(defn- arms-for [row-key]
  (let [wid (get row-witness row-key)]
    (-> [plumb-arm]
        (into (map #(witness-arm wid %)) (get arm-ids-for (:adapter @state)))
        (conj (ctl2x-arm wid)))))

(defn- arm-named [row-key arm-id]
  (first (filter #(= arm-id (:id %)) (arms-for row-key))))

(defn- expected-elements [row-key arm]
  (let [wid (get row-witness row-key)
        n   (if (= :ctl-2x (:id arm)) (* 2 w/rows-n) w/rows-n)]
    (cond
      (:plumb? arm) 0
      (= :M wid)    (w/m-elements n)
      :else         (w/u-elements n))))

;; ---------------------------------------------------------------------------
;; The frame settle — what makes the driver's delta frame-inclusive
;; ---------------------------------------------------------------------------

(defn- settle-frame []
  (js/Promise. (fn [resolve]
                 (js/requestAnimationFrame (fn [] (js/setTimeout resolve 0))))))

;; ---------------------------------------------------------------------------
;; Sample / reap
;; ---------------------------------------------------------------------------

(defn- bank! [ok?]
  (let [t (:tally @state)]
    (swap! t (fn [{:keys [of bad]}] {:of (inc of) :bad (if ok? bad (inc bad))}))
    ok?))

(defn sample!
  "ONE mount through `lane/mount-arm!` — the SAME `flushSync` window the
  hd8 instrument times, so the in-page diagnostic reading rides beside the
  protocol one on the same operation — left STANDING, settled to the next
  frame. The tare's operation is the settle and nothing else."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)]
    (if (:plumb? arm)
      (.then (settle-frame) (fn [_] (bank! true) #js {:inPageMs 0 :ok true}))
      (let [mnt (lane/mount-arm! arm {:n w/rows-n})]
        (swap! state assoc :pending {:mnt mnt :arm arm})
        (.then (settle-frame) (fn [_] #js {:inPageMs (:ms mnt) :ok true}))))))

(defn reap!
  "Verify and release the standing mount. Called by the driver AFTER it has
  read the counters — unmounting 300 or 600 boundaries is real work a mount
  row must not be charged for."
  [row-key]
  (if-some [{:keys [mnt arm]} (:pending @state)]
    (let [ok? (= (expected-elements row-key arm)
                 (lane/element-count (:container mnt)))]
      (bank! ok?)
      (lane/release! mnt)
      (swap! state assoc :pending nil)
      (.then (settle-frame) (fn [_] #js {:ok ok?})))
    (.then (settle-frame) (fn [_] #js {:ok true}))))

;; ---------------------------------------------------------------------------
;; The fairness gate's per-arm record
;; ---------------------------------------------------------------------------

(defn- str-hash
  "32-bit FNV-style hash — [[re-frame.bench.hicasso.clock-app]]'s, so the
  driver compares pages without moving 18 KB strings across the protocol."
  [s]
  (let [n (count s)]
    (loop [i 0 h (int -2128831035)]
      (if (< i n)
        (recur (inc i) (js/Math.imul (bit-xor h (.charCodeAt s i)) 16777619))
        h))))

(defn canon!
  "Mount this arm once OUTSIDE every window; answer the page it builds,
  attribute names sorted, hashed. Controls are exempt by the same rule the
  hd8 parity gate exempts them — a control that builds a doubled page on
  purpose is exactly the arm the gate must not compare."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)]
    (if (:plumb? arm)
      #js {:arm (name arm-id) :hash 0 :bytes 0 :control true}
      (let [mnt (lane/mount-arm! arm {:n w/rows-n})
            s   (lane/canonical (:container mnt))]
        (lane/release! mnt)
        ;; `lane/utf8-bytes` and not `count`: the driver prints this under a
        ;; `bytes` label, and `count` answers UTF-16 code units (rf2-2rtt6.121).
        #js {:arm     (name arm-id)
             :hash    (str-hash s)
             :bytes   (lane/utf8-bytes s)
             :control (boolean (:control? arm))}))))

;; ---------------------------------------------------------------------------
;; Boot — install, frames, and hd8's own parity gate before any clock
;; ---------------------------------------------------------------------------

(defn ^:export -main []
  (let [which (query-adapter)]
    (install! which)
    (lane/leave-act-environment!)
    (swap! state assoc :adapter which :tally (lane/tally))
    (doseq [id (get arm-ids-for which)] (rows/ensure-frame! id))
    ;; hd8's OWN fairness gate, whole and fatal-on-fail: every arm of this
    ;; run builds one page at the stress size and at a small size, and the
    ;; comparison is proven able to answer false. Runs before the driver
    ;; reads a single counter; the driver refuses the run if it failed.
    (let [problems  (rows/parity-problems (get arm-ids-for which))
          can-fail? (when (empty? problems)
                      (rows/parity-can-fail? (get arm-ids-for which)))]
      (set! (.-HD8CLOCK_PARITY js/window)
            (clj->js {"ok"       (and (empty? problems) (boolean can-fail?))
                      "canFail"  (boolean can-fail?)
                      "problems" (mapv pr-str problems)})))
    (set! (.-HD8CLOCK js/window)
          (lane/legible-doors
          #js {:adapter       (name which)
               :plan          (fn [row]
                                (clj->js (mapv (fn [a] {:id      (name (:id a))
                                                        :cells   (or (:cells a) w/rows-n)
                                                        :control (boolean (:control? a))
                                                        :plumb   (boolean (:plumb? a))})
                                               (arms-for (keyword row)))))
               :canon         (fn [row arm] (canon! (keyword row) (keyword arm)))
               :sample        (fn [row arm] (sample! (keyword row) (keyword arm)))
               :reap          (fn [row] (reap! (keyword row)))
               :settle        (fn [] (settle-frame))
               :tally         (fn [] (clj->js (lane/tally-value (:tally @state))))
               :teardownCheck (fn [] (clj->js (mapv :where (lane/drain-teardown-failures!))))
               :runtime       (fn [] (pr-str (lane/runtime-label)))
               :residue       (fn [] (pr-str (lane/residue (rows/frame-of :floor))))}))
    (set! (.-HD8CLOCK_READY js/window) true)
    (js/console.log ";; HD8CLOCK ready")
    nil))

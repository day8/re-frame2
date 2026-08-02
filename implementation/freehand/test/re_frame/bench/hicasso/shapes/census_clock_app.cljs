(ns re-frame.bench.hicasso.shapes.census-clock-app
  "THE CENSUS-REAL PAGES BEHIND THE CLOCK-OF-RECORD DOOR (rf2-2rtt6.56).

  The tier-1 shape roster (rf2-2rtt6.51) authored four census-real pages
  and published no timing row. This entry takes their MOUNT rows through
  the instrument contract the mount-gate amendment ratified (rf2-2rtt6.1,
  2026-08-02): raw `TaskDuration` — the arm's script AND the frame it
  caused — read over the DevTools protocol on either side of one
  operation, the operation frame-settled before it resolves, every arm
  through ONE door with a plumb tare subtracted. The shape is
  `hd8-clock-app`'s (plan / canon / sample / reap / settle), which is
  `clock-app`'s M1 contract; the arms are the census pages'
  ([[re-frame.bench.hicasso.shapes.census-clock-arms]]).

  ## Three rows, all mounts

  `large-template` (1,202 elements, ONE boundary), `feed` (5,129
  elements, 301 boundaries — shapes 3 and 4 share this mount), `ordinary`
  (51 elements, 7 boundaries). Shapes 2 and 3 are the same screen at two
  boundary decompositions with byte-identical cards, so the pair
  separates shell cost from interpreter cost on one page — the thing no
  synthetic witness does.

  ## What this instrument does NOT measure, and why

  The roster's WRITE rows — shape 3's broad commit and shape 4's narrow
  commit. On this box the bulk-class rows cannot hold a
  difference-statistic control at the ~3.5% floor a magnitude needs
  (rf2-7iqb5: 28–48% within-block IQR), and the narrow row sits on the
  clock clamp (rf2-d2tzk fences the same class on the M1 instrument). A
  write magnitude from this door would be refused on arrival, and an
  instrument built to be refused is an instrument nobody asked for. The
  driver's header restates this refusal with the row names on it.

  ## One adapter per process, two runs

  Spec 006 installs exactly one adapter per process. The Reagent twin
  needs the ratom spine; the candidate, the UIx twin and the floor ride
  React hooks and mount correctly under either spine (a mount is a
  one-shot read — hd8-rows' recorded argument, and the boot parity gate
  re-proves it under each installed adapter before any clock is read).
  So: `?adapter=uix` carries the GATED pair (hicasso / uix, same run);
  `?adapter=reagent` adds Reagent-on-subs, co-instrumented and reported
  beside the gate, never as a second gate.

  Owner: rf2-2rtt6.1 (standard); this entry rf2-2rtt6.56."
  (:require [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.census-clock-arms :as arms]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Which adapter this run installs
;; ---------------------------------------------------------------------------

(defn- query-adapter []
  (let [s (some-> js/window .-location .-search)]
    (cond
      (nil? s)                       :uix
      (re-find #"adapter=reagent" s) :reagent
      :else                          :uix)))

(defn- install! [which]
  (rf/init! (case which
              :reagent reagent-adapter/adapter
              uix-adapter/adapter))
  which)

;; ---------------------------------------------------------------------------
;; The arm plans
;; ---------------------------------------------------------------------------

(def ^:private arm-ids-for
  "The measurable arms under each installed adapter. The floor leads so
  it is the parity reference; the plumb tare and the doubling control are
  appended around them by [[arms-for]]."
  {:uix     [:floor :hicasso :uix]
   :reagent [:floor :hicasso :uix :reagent]})

(defonce ^:private state (atom {:adapter nil :pending nil :tally nil}))

(defn- arms-for [row-key]
  (-> [arms/plumb-arm]
      (into (map #(arms/arm row-key %)) (get arm-ids-for (:adapter @state)))
      (conj (arms/arm row-key :ctl-2x))))

(defn- arm-named [row-key arm-id]
  (first (filter #(= arm-id (:id %)) (arms-for row-key))))

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
  "ONE mount through `lane/mount-arm!` — the in-page flushSync window
  rides beside the protocol reading on the same operation — left
  STANDING, settled to the next frame. The tare's operation is the settle
  and nothing else."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)]
    (if (:plumb? arm)
      (.then (settle-frame) (fn [_] (bank! true) #js {:inPageMs 0 :ok true}))
      (let [mnt (lane/mount-arm! arm {})]
        (swap! state assoc :pending {:mnt mnt :arm arm})
        (.then (settle-frame) (fn [_] #js {:inPageMs (:ms mnt) :ok true}))))))

(defn reap!
  "Verify and release the standing mount, AFTER the driver has read the
  counters — unmounting up to ten thousand elements is real work a mount
  row must not be charged for."
  [row-key]
  (if-some [{:keys [mnt arm]} (:pending @state)]
    (let [ok? (= (arms/expected-elements row-key (:id arm))
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
  "32-bit FNV-style hash — `clock-app`'s, so the driver compares pages
  without moving 100 KB strings across the protocol."
  [s]
  (let [n (count s)]
    (loop [i 0 h (int -2128831035)]
      (if (< i n)
        (recur (inc i) (js/Math.imul (bit-xor h (.charCodeAt s i)) 16777619))
        h))))

(defn canon!
  "Mount this arm once OUTSIDE every window; answer the page it builds,
  attribute names sorted, hashed. Controls are exempt — the doubling
  control builds a doubled page on purpose."
  [row-key arm-id]
  (let [arm (arm-named row-key arm-id)]
    (if (:plumb? arm)
      #js {:arm (name arm-id) :hash 0 :bytes 0 :control true}
      (let [mnt (lane/mount-arm! arm {})
            s   (lane/canonical (:container mnt))]
        (lane/release! mnt)
        #js {:arm     (name arm-id)
             :hash    (str-hash s)
             :bytes   (count s)
             :control (boolean (:control? arm))}))))

;; ---------------------------------------------------------------------------
;; Boot — install, frames, and the whole parity gate before any clock
;; ---------------------------------------------------------------------------

(defn ^:export -main []
  (let [which   (query-adapter)
        arm-ids (get arm-ids-for which)]
    (install! which)
    (lane/leave-act-environment!)
    (swap! state assoc :adapter which :tally (lane/tally))
    (arms/ensure-frames! arm-ids)
    ;; The fairness gate, whole and fatal-on-fail: every arm of this run
    ;; builds each row's page at the stress size and at a small size, the
    ;; counts match the arithmetic, and the comparison is proven able to
    ;; answer false. Runs before the driver reads a single counter; the
    ;; driver refuses the run if it failed.
    (let [problems  (arms/parity-problems arm-ids)
          can-fail? (when (empty? problems)
                      (arms/parity-can-fail? arm-ids))]
      (set! (.-C56CLOCK_PARITY js/window)
            (clj->js {"ok"       (and (empty? problems) (boolean can-fail?))
                      "canFail"  (boolean can-fail?)
                      "problems" (mapv pr-str problems)})))
    (set! (.-C56CLOCK js/window)
          #js {:adapter       (name which)
               :plan          (fn [row]
                                (clj->js (mapv (fn [a] {:id      (name (:id a))
                                                        :control (boolean (:control? a))
                                                        :plumb   (boolean (:plumb? a))})
                                               (arms-for (keyword row)))))
               :elements      (fn [row arm]
                                (arms/expected-elements (keyword row) (keyword arm)))
               :ctlPredicted  (fn [row] (arms/ctl-predicted (keyword row)))
               :canon         (fn [row arm] (canon! (keyword row) (keyword arm)))
               :sample        (fn [row arm] (sample! (keyword row) (keyword arm)))
               :reap          (fn [row] (reap! (keyword row)))
               :settle        (fn [] (settle-frame))
               :tally         (fn [] (clj->js (lane/tally-value (:tally @state))))
               :teardownCheck (fn [] (clj->js (mapv :where (lane/drain-teardown-failures!))))
               :runtime       (fn [] (pr-str (lane/runtime-label)))
               :residue       (fn [] (pr-str (lane/residue (arms/frame-of :feed :hicasso))))})
    (set! (.-C56CLOCK_READY js/window) true)
    (js/console.log ";; C56CLOCK ready")
    nil))

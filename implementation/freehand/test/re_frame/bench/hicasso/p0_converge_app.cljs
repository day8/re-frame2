(ns re-frame.bench.hicasso.p0-converge-app
  "THE CONVERGED P0 CLOCK TABLE — Reagent-on-subs AND UIx-on-subs on ONE
  witness set, so the bar and the red-zones can be read down one column
  (rf2-a4x1o; EP-0038 P0; the standard is rf2-2rtt6.1).

  ## What was wrong, precisely

  rf2-2rtt6.2 published the bar's DENOMINATOR — Reagent reading re-frame2
  subscriptions — on a 901-element `M1` list and a 51-element `M2` form,
  both reading `[:p0/cell i]`. rf2-2rtt6.4 published the RED-ZONE
  THRESHOLDS — the measured UIx ratio per witness family, under RULING 1
  on rf2-2rtt6.1 — on a 1,203-element `W1` list reading `[:p0/row i]`, a
  51-element `W3` form reading `[:p0/field i]`, and a 301-element grid.
  Its branch was designed before rf2-2rtt6.2 merged and it said so on its
  own page.

  Each of rf2-2rtt6.4's thresholds is sound AS A THRESHOLD: a ratio
  between two arms measured in one run, on one page, through one sub
  graph, in both orders. What is not sound is the TABLE, because a
  red-zone whose page differs from the bar row's page cannot be applied to
  a candidate measured on either one. This entry re-measures the frontier
  arm on rf2-2rtt6.2's witnesses so that every row of the clock table
  describes the same page.

  ## Which witness set, and why that one

  rf2-2rtt6.2's. Four reasons, in order of weight:

  1. **The bar's denominator defines the witness set by construction.**
     HD-012 states the ship number as `<= 1.0x Reagent, like-for-like`.
     The red-zone is a second threshold layered onto the same rows — a
     rule about where a candidate sits relative to UIx — so it has to be
     derived on the pages the denominator lives on, not the other way
     round.
  2. **rf2-2rtt6.2 is on main and owns the measurement lane.** HD-017
     gives `:hicasso-bench` and `run.cjs` to that arm; rf2-2rtt6.4's tree
     rides `:freehand-release`'s compiler settings through a
     `--config-merge` precisely because the lane had not landed. Running
     the frontier arm on the lane retires that workaround.
  3. **rf2-2rtt6.4 nominated it.** Its own `Open items` names
     rf2-2rtt6.2's set as the convergence target and names its `U-broad`
     row as the closest existing correspondence.
  4. **rf2-2rtt6.2's witnesses already carry the control and the lower
     bound.** `:ctl-2x` is an in-plan positive control at exactly twice
     the boundaries, and the `:reagent-ratom` arm is a published labelled
     lower bound on the same page. Moving to the other set would strand
     both.

  ## What runs

  Four rows, two segments each. The rows are rf2-2rtt6.2's three plus one:

  | row | witness | why |
  |---|---|---|
  | `mount-M1` | 901 el, 300 boundaries, `[:p0/cell i]` | the bar row |
  | `mount-M2` | 51 el, 12 fields, `[:p0/cell i]` | DIAGNOSTIC, per rf2-2rtt6.2 |
  | `bulk-broad` | the M1 page, one commit ALL 300 boundaries read | the bar row |
  | `bulk-narrow` | the M1 page, one commit exactly ONE boundary reads | the converged counterpart of rf2-2rtt6.4's `U-narrow` — the localisation row, and the one row where that arm found UIx materially behind. It has NO counterpart in rf2-2rtt6.2, so it is a NEW row on rf2-2rtt6.2's witness, labelled as such rather than presented as a re-measurement of something |

  ## Three arms a segment, and why not two

  rf2-ouwh8: `lane/slot-order` rotates and then REFLECTS, and at k=2 those
  two operations cancel — `[0 1]` rotates to `[1 0]` and reflects back to
  `[0 1]`, at every sample index, for ever. A two-arm plan therefore runs
  in ONE order and `both orders` is a claim it cannot support. This entry
  never forms a two-arm plan: `:ctl-2x` is measured INSIDE the interleave
  as rf2-2rtt6.2 measures it, so every segment carries three arms and the
  reflection does what the guard's self-test says it does. The property is
  asserted at boot ([[slot-order-degenerates-at-2?]]) rather than trusted,
  and the run refuses to measure if the assertion does not hold.

  ## Two segments, and what makes the seam legitimate

  `install-adapter!` is once per process (Spec 006 SS Single adapter per
  process), so the Reagent and UIx arms cannot be interleaved inside one
  round. Each round runs two SEGMENTS — destroy the adapter, install the
  other, re-register, re-seed — with the FLOOR IN BOTH. The floor holds no
  re-frame state, reads no subscription and is untouched by which adapter
  is installed, so a UIx-over-Reagent figure is a ratio of two
  floor-normalised ratios and the seam cancels. That cancellation is
  PUBLISHED rather than assumed: the floor's own p50 in the UIx segment
  over its p50 in the Reagent segment is reported per round per row, and
  a reader can see how far it moves and how much less the derived ratio
  moves.

  Segment order alternates with the round, and so does witness order
  within a segment: rf2-2rtt6.4 recorded a fixed witness order making
  `second` a permanent property of one witness, with the guard refusing.

  ## What this entry does NOT measure

  Retained heap. The heap red-zones on rf2-2rtt6.1 come from two
  independent witness families already — rf2-2rtt6.5's 1,200-boundary
  reads ladder and rf2-2rtt6.4's list/grid pair — and they agree: 2.262x
  (list) and 2.254x (grid) on markup densities that differ by 4x, against
  a per-read ratio of 3,550/942 on a third shape. Retained bytes per
  subscribing boundary is a property of the BOUNDARY; the clock is not,
  because a page's element count decides what fraction of the window is
  React's own work. The heap axis was therefore already converged in
  substance and the clock axis was not, which is why exactly one arm is
  re-run here.

  Driven by `p0_converge_run.cjs`, which sets this namespace as the
  `:hicasso-bench` build's `:init-fn` through rf2-2rtt6.2's own driver and
  touches no build id and no `implementation/shadow-cljs.edn`."
  (:require ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.p0-reagent-views :as v]
            [re-frame.bench.hicasso.p0-uix-views :as ux]
            [re-frame.bench.order-guard :as guard]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [uix.dom :as uix-dom]))

(def ^:private rounds 5)
(def ^:private mount-sampling {:warmup 8 :samples 12})
(def ^:private bulk-sampling {:warmup 8 :samples 12})

;; rf2-2rtt6.2's slack, unchanged and for its reasons: the claim a clock
;; control certifies is THE INSTRUMENT HAS SIGNAL, not THE MODEL IS EXACT.
;; A top-down React re-render is not perfectly linear in element count —
;; the root, the commit and the diff walk do not double — so 2.00 +/- 5%
;; would fail an instrument that is working. (The +/-0.001% standard this
;; wave set belongs to the HEAP control, where the predicted quantity is a
;; known retained byte count; no clock control can be held to it and
;; pretending otherwise would be theatre.)
(def ^:private control-slack 0.25)

(defonce ^:private gen (atom 1000))
(defn- next-gen! [] (swap! gen inc))

;; ---------------------------------------------------------------------------
;; Segments
;; ---------------------------------------------------------------------------

(def ^:private segments
  [{:id :reagent-subs :adapter reagent-adapter/adapter :name "Reagent-on-subs"}
   {:id :uix-subs     :adapter uix-adapter/adapter     :name "UIx-on-subs"}])

(defn- segment-order
  "Two segments admit exactly two orders and this runs both. A
  cross-segment figure taken in one order only is a single-order result
  however many rounds it averages."
  [r]
  (if (even? r) (vec segments) (vec (rseq (vec segments)))))

(defn- enter-segment!
  "Tear down whatever adapter is installed, install this segment's,
  re-register the sub graph and stand the frame back up seeded.

  All of it OUTSIDE every measured window."
  [{:keys [adapter]}]
  (try (rf/destroy-frame! v/subs-frame) (catch :default _ nil))
  (when (rf/current-adapter)
    (try (rf/destroy-adapter!) (catch :default _ nil)))
  (rf/init! adapter)
  (v/register!)
  (rf/make-frame {:id v/subs-frame})
  (frame/replace-app-db! v/subs-frame (v/seed-cells v/cells-n 0))
  (lane/leave-act-environment!)
  nil)

;; ---------------------------------------------------------------------------
;; Mount arms
;; ---------------------------------------------------------------------------

(defn- zeros [n] (vec (repeat n 0)))

(defn- floor-mount-arm
  [id element-of cells-of & [exempt?]]
  {:id             id
   :parity-exempt? (boolean exempt?)
   :mount          (fn [container props _n]
                     (let [root (react-dom-client/createRoot container)]
                       (.render root (element-of (cells-of props)))
                       root))
   :unmount        (fn [root] (.unmount root))})

(defn- reagent-mount-arm
  "Reagent's own mount door — what a Reagent application calls."
  [id form-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [root (rdc/create-root container)]
                (rdc/render root (form-of props))
                root))
   :unmount (fn [root] (rdc/unmount root))})

(defn- uix-mount-arm
  "UIx's own mount door — `uix.dom/create-root` + `render-root`, which is
  what a UIx application calls. A shared door would measure the shim."
  [id element-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [root (uix-dom/create-root container)]
                (uix-dom/render-root (element-of props) root)
                root))
   :unmount (fn [root] (uix-dom/unmount-root root))})

(defn- input-value
  "The DOM PROPERTY, not the attribute. React installs a controlled
  `value` as a property and mirrors it to the attribute only on first
  render, so a property read is the one that stays true."
  [container sel]
  (some-> (.querySelector container sel) (.-value)))

(defn- verify-m1
  "Both ends of the list. A page that committed only its head would pass a
  single-probe check at index 0."
  [container]
  (and (= "0" (lane/text-at container 0))
       (= "0" (lane/text-at container (dec v/cells-n)))))

(defn- verify-m2
  "The first and last field of the SHARED prefix — the control arm has
  twice the fields, so a probe past `fields-n` would not exist in every
  arm."
  [container]
  (and (= (v/field-value 0 0) (input-value container "#f0"))
       (= (v/field-value (dec v/fields-n) 0)
          (input-value container (str "#f" (dec v/fields-n))))))

(def ^:private mount-witnesses
  [{:id       :M1
    :grade    :bar
    :verify   verify-m1
    :doc      (str "the 300-boundary sub-reading list — the bulk shape's mount "
                   "counterpart, one subscription read per boundary. "
                   "rf2-2rtt6.2's M1, unchanged")
    :props    {:n v/cells-n}
    :elements (v/m1-elements v/cells-n)
    :control  {:predicted (/ (double (v/m1-elements (* 2 v/cells-n)))
                             (double (v/m1-elements v/cells-n)))
               :basis     (str "element count: " (v/m1-elements (* 2 v/cells-n)) " / "
                               (v/m1-elements v/cells-n))}
    :arms-for (fn [segment-id]
                [(floor-mount-arm :floor v/m1-floor (fn [{:keys [n]}] (zeros n)))
                 (case segment-id
                   :reagent-subs (reagent-mount-arm
                                   :reagent-subs
                                   (fn [{:keys [n]}] [v/subs-root v/m1-subs n]))
                   :uix-subs     (uix-mount-arm
                                   :uix-subs
                                   (fn [{:keys [n]}] (ux/subs-root ux/m1 n))))
                 (floor-mount-arm :ctl-2x v/m1-floor
                                  (fn [{:keys [n]}] (zeros (* 2 n))) true)])}

   {:id         :M2
    :grade      :diagnostic
    :verify     verify-m2
    ;; ONE mount per sample, exactly as rf2-2rtt6.2 publishes it. That arm
    ;; TRIED the batch that would lift this witness clear of Chrome's
    ;; 100 us clamp — eight 51-element roots in one flushSync — and THE
    ;; ARM-ORDER GUARD REFUSED THE WHOLE RUN, exit 2, with all four M2 arms
    ;; reading 3.2x-5.4x slower in the last third than in the first, ranges
    ;; disjoint, while the unbatched M1 row in the same page drifted
    ;; 1.13x-1.16x with ranges overlapping. Re-taking the batch here would
    ;; be re-litigating a refusal the denominator arm already resolved
    ;; against itself. The resulting coarseness is stated on the row and
    ;; the row is graded DIAGNOSTIC.
    :per-sample 1
    :clock-note (str "DIAGNOSTIC-GRADE, not a bar row and not a red-zone a "
                     "candidate is judged against. A 51-element mount takes a few "
                     "tenths of a millisecond — three to six of Chrome's 100 us "
                     "performance.now() quanta — so this witness's ratios are "
                     "quantised more coarsely than a 10% effect. rf2-2rtt6.4 "
                     "recorded the failure mode this row is exposed to: at one "
                     "mount a sample its 51-element form returned exactly 0.75 ms "
                     "from BOTH segments and the ratio came out at precisely "
                     "1.0000 — the quantum wearing a tie's clothes.")
    :doc      (str "the ordinary 12-field form on subs — the shape most "
                   "applications are made of. rf2-2rtt6.2's M2, unchanged")
    :props    {:n v/fields-n}
    :elements (v/m2-elements v/fields-n)
    :control  {:predicted (/ (double (v/m2-elements (* 2 v/fields-n)))
                             (double (v/m2-elements v/fields-n)))
               :basis     (str "element count: " (v/m2-elements (* 2 v/fields-n)) " / "
                               (v/m2-elements v/fields-n))}
    :arms-for (fn [segment-id]
                [(floor-mount-arm :floor v/m2-floor (fn [{:keys [n]}] (zeros n)))
                 (case segment-id
                   :reagent-subs (reagent-mount-arm
                                   :reagent-subs
                                   (fn [{:keys [n]}] [v/subs-root v/m2-subs n]))
                   :uix-subs     (uix-mount-arm
                                   :uix-subs
                                   (fn [{:keys [n]}] (ux/subs-root ux/m2 n))))
                 (floor-mount-arm :ctl-2x v/m2-floor
                                  (fn [{:keys [n]}] (zeros (* 2 n))) true)])}])

(defn- witness-order [r]
  (if (even? r) mount-witnesses (vec (rseq (vec mount-witnesses)))))

;; ---------------------------------------------------------------------------
;; Bulk arms
;; ---------------------------------------------------------------------------

(defn- floor-bulk-arm
  "The floor's `force!` renders INSIDE the `flushSync`, and that is not a
  convenience. `root.render` called outside a React event schedules at
  React's DEFAULT lane and an empty `flushSync` flushes only the SYNC
  lane, so a floor arm that rendered in `write!` would have its commit
  land outside the measured window entirely — the recorded fault is 80 of
  320 floor samples ending on a cell that still held its old value."
  [id n & [exempt?]]
  (let [state (atom (zeros n))
        rt    (volatile! nil)]
    {:id             id
     :parity-exempt? (boolean exempt?)
     :cells          n
     :mount          (fn [container]
                       (let [root (react-dom-client/createRoot container)]
                         (vreset! rt root)
                         (react-dom/flushSync (fn [] (.render root (v/m1-floor @state))))
                         root))
     :write!         (fn [i val]
                       (if (= i :all)
                         (reset! state (vec (repeat n val)))
                         (swap! state assoc i val)))
     :force!         (fn [] (react-dom/flushSync
                              (fn [] (.render ^js @rt (v/m1-floor @state)))))
     :unmount        (fn [root] (react-dom/flushSync (fn [] (.unmount root))))}))

(defn- subs-bulk-arm
  "Both substrate bulk arms, one constructor, so they differ in exactly
  two places: the mount door and the drain.

  THE WRITE IS IDENTICAL and it is rf2-2rtt6.2's write —
  `frame/replace-app-db!` — not a `dispatch-sync`. HD-012 states the bar
  over VIEW WORK, and rf2-2rtt6.3 measured the event drain at 11%-16% of
  a write on this substrate; routing the converged row through the event
  pipeline would add that leg to both arms, shrink the view-work
  difference the row exists to show, and make the Reagent figures here
  uncomparable with the ones rf2-2rtt6.2 already published. The pipeline
  is priced on rf2-2rtt6.3's own row.

  The NARROW write installs a whole new app-db with one cell changed, so
  all 300 layer-1 subscriptions recompute and exactly one boundary's
  value changes. That is the localisation question as a re-frame2
  application actually meets it, and it is the same operation on both
  substrate arms."
  [id mount unmount force!]
  (let [cells (atom (zeros v/cells-n))]
    {:id      id
     :cells   v/cells-n
     :mount   mount
     :unmount unmount
     :write!  (fn [i val]
                (if (= i :all)
                  (reset! cells (vec (repeat v/cells-n val)))
                  (swap! cells assoc i val))
                (frame/replace-app-db! v/subs-frame {:cells @cells}))
     :force!  force!}))

(defn- reagent-bulk-arm []
  (subs-bulk-arm
    :reagent-subs
    (fn [container]
      (let [root (rdc/create-root container)]
        (react-dom/flushSync
          (fn [] (rdc/render root [v/subs-root v/m1-subs v/cells-n])))
        root))
    (fn [root] (react-dom/flushSync (fn [] (rdc/unmount root))))
    ;; `reagent.core/flush` is Reagent's own documented synchronous render
    ;; drain — the same drain rf2-2rtt6.2's published row used.
    (fn [] (react-dom/flushSync (fn [] (r/flush))))))

(defn- uix-bulk-arm []
  (subs-bulk-arm
    :uix-subs
    (fn [container]
      (let [root (uix-dom/create-root container)]
        (react-dom/flushSync
          (fn [] (uix-dom/render-root (ux/subs-root ux/m1 v/cells-n) root)))
        root))
    (fn [root] (react-dom/flushSync (fn [] (uix-dom/unmount-root root))))
    ;; `useSyncExternalStore` notifications schedule at React's SYNC lane,
    ;; so an EMPTY flushSync commits them inside the window. Not
    ;; `uix-adapter/flush-views!`, which wraps React's `act()` — `act`
    ;; diverts work to a queue that is not the browser's, and every
    ;; window in this lane is taken outside it.
    (fn [] (react-dom/flushSync (fn [] nil)))))

(defn- bulk-arms [segment-id]
  [(floor-bulk-arm :floor v/cells-n)
   (case segment-id
     :reagent-subs (reagent-bulk-arm)
     :uix-subs     (uix-bulk-arm))
   (floor-bulk-arm :ctl-2x (* 2 v/cells-n) true)])

(defn- mount-bulk-arms! [arms]
  (mapv (fn [a]
          (let [c (lane/fresh-container!)]
            {:arm a :container c :handle ((:mount a) c)}))
        arms))

(defn- release-bulk-arms! [mounts]
  (doseq [{:keys [arm handle container]} mounts]
    (try ((:unmount arm) handle) (catch :default _ nil))
    (.remove container)))

;; ---------------------------------------------------------------------------
;; One round of one mount witness in one segment
;; ---------------------------------------------------------------------------

(defn- mount-round!
  [coll t {:keys [id verify elements props per-sample arms-for]} segment-id]
  (let [arms (arms-for segment-id)
        n    (count arms)
        k    (or per-sample 1)
        acc  (atom (zipmap (map :id arms) (repeat [])))]
    (dotimes [s (+ (:warmup mount-sampling) (:samples mount-sampling))]
      (doseq [j (lane/slot-order n s)]
        (let [arm (nth arms j)
              {:keys [ms mounts]} (lane/mount-batch! arm props k)
              expected (if (:parity-exempt? arm) nil elements)]
          ;; EVERY mount is read back out of the document — the element
          ;; count against WRITTEN arithmetic, and the witness's own probe
          ;; at both ends of the page. An arm that rendered an empty page
          ;; is the cheapest arm in any table and this is the line that
          ;; catches it.
          (doseq [m mounts]
            (let [ok? (and (or (nil? expected)
                               (= expected (lane/element-count (:container m))))
                           (verify (:container m)))]
              (swap! t (fn [{:keys [of bad]}]
                         {:of (inc of) :bad (if ok? bad (inc bad))}))))
          (doseq [m mounts] (lane/release! m))
          (when (>= s (:warmup mount-sampling))
            (lane/collect! coll (str (name id) "/" (name segment-id) "/" (name (:id arm))) ms)
            (swap! acc update (:id arm) conj ms)))))
    @acc))

;; ---------------------------------------------------------------------------
;; One round of one bulk kind in one segment
;; ---------------------------------------------------------------------------

(defn- bulk-write!
  "One write on one arm, timed as one sample and verified at the DOM
  inside its own window.

  BROAD changes every cell, so the probe rotates with the value AND the
  far end of the grid is checked: a stale page can still carry one fresh
  cell from the previous write and a single fixed probe would accept it.

  NARROW changes exactly one cell, so there is nothing else in the page
  to check — the strength of the read-back comes from the value being
  fresh on every write, which means a stale page holds the PREVIOUS value
  at the probed cell and fails. The cell also rotates, so no index is
  special."
  [t mnt kind s]
  (let [n   (:cells (:arm mnt))
        val (next-gen!)]
    (if (= kind :broad)
      (lane/verified-write! t mnt :all val [(mod val n) (dec n) 0])
      (let [i (mod (* 7 s) n)]
        (lane/verified-write! t mnt i val [i])))))

(defn- seed-bulk!
  [mounts t]
  (lane/chain nil mounts
              (fn [_ mnt] (-> (lane/verified-write! t mnt :all 0
                                                    [0 (dec (:cells (:arm mnt)))])
                              (.then (fn [_] nil))))))

(defn- bulk-round!
  "One round. The guard's samples are banked AT THE SAMPLE, inside the
  interleave — banking them per arm after the round would record every
  arm's readings as consecutive and hand the guard a `:position` factor
  that says only what the loop's shape was."
  [mounts t legs coll kind segment-id]
  (let [n     (count mounts)
        total (+ (:warmup bulk-sampling) (:samples bulk-sampling))
        acc0  (zipmap (map #(:id (:arm %)) mounts) (repeat []))]
    (lane/chain {:readings acc0}
                (for [s (range total) j (lane/slot-order n s)] [s j])
                (fn [acc [s j]]
                  (let [mnt (nth mounts j)
                        id  (:id (:arm mnt))]
                    (-> (bulk-write! t mnt kind s)
                        (.then (fn [{:keys [ms write-ms gap-ms force-ms]}]
                                 (if (>= s (:warmup bulk-sampling))
                                   (do (lane/collect! coll
                                                      (str (name kind) "/" (name segment-id)
                                                           "/" (name id))
                                                      ms)
                                       (swap! legs update [kind segment-id id] (fnil conj [])
                                              {:write write-ms :gap gap-ms :force force-ms})
                                       (update-in acc [:readings id] conj ms))
                                   acc)))))))))

;; ---------------------------------------------------------------------------
;; Aggregation
;; ---------------------------------------------------------------------------

(defn- ratios-of
  "One segment-round's raw readings as `{:p50 {id ms} :ratio {id r}}`,
  every ratio against the floor measured in THAT segment of THAT round."
  [readings]
  (lane/normalise readings :floor))

(defn- range-of [vs]
  {:mean (lane/round4 (/ (reduce + 0.0 vs) (count vs)))
   :min  (lane/round4 (apply min vs))
   :max  (lane/round4 (apply max vs))
   :per-round (mapv lane/round4 vs)
   :straddles-1? (and (<= (apply min vs) 1.0) (>= (apply max vs) 1.0))})

(defn- row-record
  "Turn one row's per-round, per-segment slices into the published record.

  `slices` is `[{segment-id {arm-id [ms ...]}} ...]`, one entry per round."
  [{:keys [row doc grade clock-note control note]} slices]
  (let [norm       (mapv (fn [by-seg]
                           (into {} (map (fn [[sid rd]] [sid (ratios-of rd)])) by-seg))
                         slices)
        rz         (mapv (fn [m] (/ (get-in m [:uix-subs :ratio :uix-subs])
                                    (get-in m [:reagent-subs :ratio :reagent-subs])))
                         norm)
        rg-floor   (mapv #(get-in % [:reagent-subs :ratio :reagent-subs]) norm)
        ux-floor   (mapv #(get-in % [:uix-subs :ratio :uix-subs]) norm)
        seam       (mapv (fn [m] (/ (get-in m [:uix-subs :p50 :floor])
                                    (get-in m [:reagent-subs :p50 :floor])))
                         norm)
        ctl-rg     (mapv #(get-in % [:reagent-subs :ratio :ctl-2x]) norm)
        ctl-ux     (mapv #(get-in % [:uix-subs :ratio :ctl-2x]) norm)
        verdict-of (fn [vs] (lane/control-verdict (:predicted control)
                                                  (select-keys (range-of vs) [:min :max :mean])
                                                  control-slack))
        c-rg       (verdict-of ctl-rg)
        c-ux       (verdict-of ctl-ux)]
    {:record
     {:benchmark   (keyword "hicasso.P0.converged" (name row))
      :doc         doc
      :bead        "rf2-a4x1o"
      :grade       grade
      :clock-note  clock-note
      :note        note
      :witness-set "rf2-2rtt6.2"
      :red-zone    (assoc (range-of rz)
                          :numerator :uix-subs
                          :denominator :reagent-subs
                          :axis :clock)
      :uix-over-floor     (range-of ux-floor)
      :reagent-over-floor (range-of rg-floor)
      :segment-seam-control
      {:floor-uix-over-floor-reagent (range-of seam)
       :why (str "the FLOOR's own p50 in the UIx segment over its p50 in the "
                 "Reagent segment, same round. The floor is identical work in both "
                 "and holds no re-frame state, so this is drift between the segments "
                 "and nothing else. Every published figure is a ratio to the floor "
                 "measured in the SAME segment of the SAME round, so the seam "
                 "cancels — and the evidence for that is this range against the "
                 "red-zone's, not an argument that it should.")}
      :positive-control {:predicted (:predicted control)
                         :basis     (:basis control)
                         :reagent-segment c-rg
                         :uix-segment     c-ux}
      :per-round   {:reagent (mapv #(get-in % [:reagent-subs :p50]) norm)
                    :uix     (mapv #(get-in % [:uix-subs :p50]) norm)}
      :status      :evidence}
     :controls [c-rg c-ux]}))

;; ---------------------------------------------------------------------------
;; Parity — before any clock is read, in EVERY segment and ACROSS the seam
;; ---------------------------------------------------------------------------

(defn- parity-of-segment!
  [segment-id]
  (reduce
    (fn [acc {:keys [id props elements arms-for]}]
      (let [{:keys [mounts agree? counts disagree reference]} (lane/parity (arms-for segment-id) props)]
        (try
          (-> acc
              (assoc-in [:reference id] reference)
              (update :problems
                      (fn [problems]
                        (cond-> problems
                          (not agree?)
                          (conj {:segment segment-id :witness id
                                 :problem :canonical-dom-disagreement :arms disagree})

                          (not (every? #(= elements %) (vals counts)))
                          (conj {:segment segment-id :witness id :problem :element-count
                                 :expected elements :got counts})))))
          (finally (doseq [m mounts] (lane/release! m))))))
    {:problems [] :reference {}}
    mount-witnesses))

(defn- parity!
  "Both segments' parity, plus the CROSS-segment check.

  Within a segment the floor and the substrate arm must build the same
  page. Across the seam the two SUBSTRATE arms must build the same page as
  each other — which is the comparison the red-zone actually makes, and it
  is checked directly rather than inferred from each segment agreeing with
  its own floor."
  []
  (let [by-seg (reduce (fn [acc {:keys [id] :as segment}]
                         (enter-segment! segment)
                         (assoc acc id (parity-of-segment! id)))
                       {}
                       segments)
        cross  (for [{:keys [id]} mount-witnesses
                     :when (not= (get-in by-seg [:reagent-subs :reference id])
                                 (get-in by-seg [:uix-subs :reference id]))]
                 {:problem :cross-segment-disagreement :witness id})]
    {:problems (into (vec (mapcat (comp :problems val) by-seg)) cross)}))

;; ---------------------------------------------------------------------------
;; The schedule's own precondition (rf2-ouwh8)
;; ---------------------------------------------------------------------------

(defn- slot-order-degenerates-at-2?
  "Does `slot-order` emit the SAME order at every sample index when there
  are two arms? rf2-ouwh8 says yes — rotate `[0 1]` to `[1 0]`, reflect
  back to `[0 1]` — and three copies of the rule carry it. Asserted here
  rather than trusted, because this entry's whole `both orders` claim
  rests on never forming a two-arm plan."
  []
  (apply = (map #(guard/slot-order 2 %) (range 8))))

(defn- slot-order-varies-at-3?
  "And the three-arm plan this entry actually runs must NOT degenerate."
  []
  (> (count (distinct (map #(guard/slot-order 3 %) (range 8)))) 1))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- bulk-kinds [r] (if (even? r) [:broad :narrow] [:narrow :broad]))

(defn- run-round!
  "One round: both segments, in this round's order; within each segment
  both mount witnesses in this round's order, then the standing bulk
  arms driven through both write kinds in this round's order.

  Answers a promise of
  `{:mount {witness-id {segment-id readings}} :bulk {kind {segment-id readings}}}`."
  [r coll tallies legs]
  (lane/chain
    {:mount {} :bulk {}}
    (segment-order r)
    (fn [acc {:keys [id] :as segment}]
      (enter-segment! segment)
      (let [acc' (reduce (fn [a w]
                           (assoc-in a [:mount (:id w) id]
                                     (mount-round! coll (get tallies (:id w)) w id)))
                         acc
                         (witness-order r))
            mounts (mount-bulk-arms! (bulk-arms id))]
        (-> (lane/chain acc' (bulk-kinds r)
                        (fn [a kind]
                          (-> (seed-bulk! mounts (get tallies kind))
                              (.then (fn [_] (bulk-round! mounts (get tallies kind)
                                                          legs coll kind id)))
                              (.then (fn [rd] (assoc-in a [:bulk kind id] (:readings rd)))))))
            (.then (fn [a] (release-bulk-arms! mounts) a))
            (.catch (fn [e] (release-bulk-arms! mounts) (throw e))))))))

(defn- leg-summary [legs]
  (into {}
        (map (fn [[k xs]]
               [k {:write-ms (:p50 (lane/summarise (map :write xs)))
                   :gap-ms   (:p50 (lane/summarise (map :gap xs)))
                   :force-ms (:p50 (lane/summarise (map :force xs)))}]))
        legs))

(defn- publish!
  [slices tallies legs coll]
  (let [m1 (row-record {:row :mount-M1 :grade :bar
                        :doc (:doc (first mount-witnesses))
                        :control (:control (first mount-witnesses))}
                       (mapv #(get-in % [:mount :M1]) slices))
        m2 (row-record {:row :mount-M2 :grade :diagnostic
                        :doc (:doc (second mount-witnesses))
                        :clock-note (:clock-note (second mount-witnesses))
                        :control (:control (second mount-witnesses))}
                       (mapv #(get-in % [:mount :M2]) slices))
        bulk-control {:predicted (/ (double (v/m1-elements (* 2 v/cells-n)))
                                    (double (v/m1-elements v/cells-n)))
                      :basis (str "element count: " (v/m1-elements (* 2 v/cells-n)) " / "
                                  (v/m1-elements v/cells-n))}
        broad (row-record {:row :bulk-broad :grade :bar
                           :doc (str "one commit that all " v/cells-n
                                     " sub-reading boundaries read, on rf2-2rtt6.2's M1 "
                                     "page — the make-or-break row")
                           :control bulk-control}
                          (mapv #(get-in % [:bulk :broad]) slices))
        narrow (row-record {:row :bulk-narrow :grade :bar
                            :doc (str "one commit that exactly ONE of " v/cells-n
                                      " sub-reading boundaries reads — the localisation row")
                            :note (str "NEW ROW. rf2-2rtt6.2 has no narrow counterpart, so "
                                       "this is not a re-measurement of a published figure: "
                                       "it is rf2-2rtt6.4's U-narrow question asked on "
                                       "rf2-2rtt6.2's witness. The two numbers are NOT "
                                       "comparable with each other and neither supersedes "
                                       "the other; this one is the comparable one, because "
                                       "its page is the page every other row here uses.")
                            :control bulk-control}
                           (mapv #(get-in % [:bulk :narrow]) slices))
        rows [m1 m2 broad narrow]]
    (doseq [[k {:keys [record]}] (map vector
                                      ["mount-M1" "mount-M2" "bulk-broad" "bulk-narrow"]
                                      rows)]
      (lane/record! k record))
    (lane/record! "verification"
                  (into {} (map (fn [[k t]] [k (lane/tally-value t)])) tallies))
    (lane/record! "write-legs" (leg-summary @legs))
    (lane/record! "red-zones"
                  {:axis :clock
                   :witness-set "rf2-2rtt6.2"
                   :rule (str "RULING 1 on rf2-2rtt6.1: the red-zone threshold IS the "
                              "measured UIx ratio for that witness family. A candidate row "
                              "worse than it is RED and needs an explicit operator waiver "
                              "naming the dogfood benefit. Silence is not a pass. These "
                              "SUPERSEDE the clock thresholds rf2-2rtt6.4 published on its "
                              "own witnesses, which remain sound as ratios and are not "
                              "comparable with the bar rows.")
                   :thresholds
                   (into {} (map (fn [[k {:keys [record]}]]
                                   [k (select-keys (:red-zone record)
                                                   [:mean :min :max :per-round :straddles-1?])]))
                         (map vector ["mount-M1" "mount-M2" "bulk-broad" "bulk-narrow"] rows))})
    (let [vd (lane/guard! (:samples @coll) "Hicasso P0 — converged witness set")]
      (lane/record! "arm-order-guard"
                    {:tolerance (:tolerance vd)
                     :contaminated? (:contaminated? vd)
                     :unchecked? (:unchecked? vd)
                     :refuse? (:refuse? vd)
                     :arms (:arms vd)})
      (when (:refuse? vd) (set! (.-HICASSO_GUARD_REFUSED js/window) true)))
    (when (some (complement :ok?) (mapcat :controls rows))
      (set! (.-HICASSO_CONTROL_FAILED js/window) true))
    nil))

(defn ^:export -main
  []
  (try
    (lane/leave-act-environment!)
    (cond
      (not (lane/self-test!))
      (do (lane/fail! (str "the arm-order guard's SELF-TEST failed — this copy of the rule "
                           "no longer behaves like the one the .cjs drivers use, so nothing "
                           "was measured"))
          (lane/done!))

      (not (slot-order-degenerates-at-2?))
      (do (lane/fail! (str "slot-order no longer degenerates at k=2. rf2-ouwh8 is the reason "
                           "this entry puts three arms in every segment; if the rule has "
                           "changed, the schedule's justification has to be re-derived before "
                           "anything here is measured"))
          (lane/done!))

      (not (slot-order-varies-at-3?))
      (do (lane/fail! (str "slot-order emits ONE order at k=3, so the three-arm plan this "
                           "entry runs is a single-order plan and `both orders` would be a "
                           "claim it cannot support"))
          (lane/done!))

      :else
      (let [{:keys [problems]} (parity!)]
        (lane/record! "parity"
                      {:problems problems :ok? (empty? problems)
                       :note (str "canonical DOM with attribute names sorted, inside each "
                                  "segment AND across the seam. Element counts are checked "
                                  "against written arithmetic — 901 for M1, 51 for M2 — so "
                                  "the gate can answer false for an arm that rendered "
                                  "nothing.")})
        (if (seq problems)
          (do (lane/fail! (str "the arms do not build the same page under :advanced — "
                               (pr-str problems)))
              (lane/done!))
          (let [coll    (lane/sample-collector)
                tallies {:M1 (lane/tally) :M2 (lane/tally)
                         :broad (lane/tally) :narrow (lane/tally)}
                legs    (atom {})]
            (-> (lane/chain [] (range rounds)
                            (fn [acc r]
                              (-> (run-round! r coll tallies legs)
                                  (.then (fn [slice] (conj acc slice))))))
                (.then (fn [slices]
                         (publish! slices tallies legs coll)
                         (lane/done!)
                         nil))
                (.catch (fn [e]
                          (lane/fail! (str "the run rejected: " e))
                          (lane/done!))))))))
    (catch :default e
      (lane/fail! (str "the run threw: " e))
      (lane/done!))))

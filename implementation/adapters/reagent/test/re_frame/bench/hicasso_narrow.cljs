(ns re-frame.bench.hicasso-narrow
  "EP-0038 P0 — the RATOM-SPINE NARROW-WRITE leg, write + flush SUMMED.

  ## The mistake this instrument exists to prevent

  The withdrawn predecessor programme published a narrow-update row that
  looked catastrophic — roughly 15x — and the number was not a valid
  target as measured. Two separate faults, and this namespace is built
  around both.

  **The comparison arm was not the same amount of framework.** The
  compared substrate read a bare `reagent.core/atom` through a
  `reagent.core/cursor`. That is Reagent's own idiom and it is a fair
  arm for the question *what does Reagent cost*, but it is not the
  question *what does re-frame on Reagent cost*: a re-frame-shaped
  Reagent application pays a frame write and a subscription graph that
  the bare-ratom arm never touches. Roughly 90% of the reported gap
  decomposed to exactly that — the write and the signal graph, not
  rendering. Optimising against it would have been optimising re-frame
  under another substrate's name.

  **And the write leg alone means nothing on this substrate.** On the
  ratom spine a write is a bare install into the frame's one physical
  container; the reactions do not recompute there, they recompute on
  Reagent's flush. A harness that timed the write leg and stopped would
  report a flatteringly small figure that no user experiences. So the
  published figure here is `write + gap + force`, SUMMED, and the split
  is published beside it precisely so a reader can see how much of it is
  re-frame and how much is React.

  ## The four arms

  `:bare-ratom`            b6's arm, reproduced — `r/cursor` over an
                           `r/atom`, no re-frame at all. This is the arm
                           the predecessor compared against, and it is
                           here so the size of the framing error is a
                           measurement rather than an assertion.

  `:spine-replace`         re-frame2 on the Reagent adapter — which is
                           `re-frame.substrate.spine/make-ratom-spine`,
                           Reagent's own `r/atom` and `make-reaction`,
                           Reagent's own batching. 300 layer-1
                           subscriptions, one per cell. The write door is
                           `frame/replace-app-db!`, the SAME door the
                           donor row used, so the two are comparable
                           leg-for-leg.

  `:spine-dispatch`        the same views and the same graph, written
                           through `dispatch-sync` — the door a real
                           application uses. The difference between this
                           and `:spine-replace` is the event drain, and
                           it is a row rather than a sentence.

  `:instrument`            a pseudo-arm that installs nothing and drains
                           nothing, so a window over it contains the
                           harness and NOTHING ELSE. It prices the
                           harness's own footprint, which is otherwise an
                           unexamined constant inside every figure above.

  ## The window, and why every leg boundary is where it is

      t0 -> control spin -> tc -> write! -> t1 -> microtask -> t2
         -> force! -> t3 -> READ THE CELL BACK OUT OF THE DOM

  `:total-ms` is `t3 - tc`: write + gap + force, the elapsed time to get
  one narrow write onto the page. The control's leg is EXCLUDED from it
  and published separately.

  `force!` is `react-dom/flushSync(reagent.core/flush)` on every real
  arm. **An EMPTY `flushSync` would not do**, and that is a recorded
  fault rather than a precaution: an empty `flushSync` drains only
  React's sync lane, and the donor harness recorded 80 of 320 samples
  ending with the cell still holding its old value because of it.
  [[negative-control-arm]] reproduces that fault deliberately, so the
  read-back gate below is shown to have signal instead of being trusted.

  Nothing runs inside React's `act` environment, and the adapter's own
  `flush-views!` is deliberately NOT the door used here: it wraps `act`,
  whose queue is not the browser's scheduler, and the whole point of the
  window is that it is the browser's.

  ## Every write is verified at the DOM inside its own window

  A clock alone once accepted a window in which 1,320 of 1,320 writes
  never reached the page. So the written cell is read back out of the
  DOM after `t3` — inside the write's own window, not once at the end —
  and the count of failures is published beside every figure as
  \"N unverified of M\".

  **M COUNTS ONLY WRITES THAT COULD HAVE BEEN VERIFIED.** `:instrument`
  renders no cell and forces `ok` true, so its windows are not evidence of
  anything having reached a page; folding them into the denominator
  inflated it from 3,600 to 4,320 and made the headline claim \"every write
  read out of the DOM\" true of 83% of the writes it named. Skip-verify
  arms are excluded from the tally and reported on their own line, and a
  nonzero count on a REAL arm now fails the run rather than being printed
  beside a `reportable` verdict.

  ## Chrome clamps `performance.now()` to 100 microseconds

  A single narrow write sits on that clamp. So a SAMPLE is 20 writes and
  the per-write figure is the sample divided by 20; [[clock-quantum]]
  measures the clamp from inside the page every run, and the driver
  refuses to quote any leg whose per-sample value is not clear of it.

  ## The positive control

  [[control-spin!]] burns a PREDICTED number of milliseconds inside every
  measured window, as its own leg. It is read three ways, and the third
  is the one that matters:

  1. directly — the control leg's p50 against the predicted duration;
  2. differentially — the slope across two rungs, which cancels the
     clock-read overhead the direct reading contains;
  3. **as a falsifiable consequence** — an arm's `:total-ms` must NOT
     move when the control's size changes. If it does, the window's leg
     accounting is leaking and no figure here may be quoted.

  ## Every property crossing the JS boundary is a string literal

  Under `:advanced`, `(.-bad o)` is renamed and a `#js {:bad 0}` literal
  key is not. The donor recorded the consequence: `undefined + 1` is
  `NaN`, the literal's `bad: 0` stands, and the driver reads ZERO
  unverified writes for ever. So every accumulator goes through
  `goog.object` with string literals, and [[integrity-probe]] proves it
  from inside the shipped bundle before anything is measured.

  ## Two instruments, one method (rf2-uhw11)

  `re-frame.bench.hicasso.lane` is the other P0 harness, and it is not a
  rival: a mount is ONE timed commit and a narrow write is write,
  microtask, flush, verify, so the two windows genuinely differ and
  neither subsumes the other. What must not differ is the METHOD, and it
  was written down twice — `summarise`, `chain`, the schedule and the
  verification tally all existed here and there.

  So the shared parts are TAKEN from the lane rather than restated:
  [[summarise]] and `chain` are `lane/`'s, and the schedule is
  `guard/slot-order`, which the lane also merely holds. What stays local
  is what is genuinely this window's — the clock (`(js/performance.now)`
  with no branch inside a timed leg), the six-leg accumulator, and the
  `goog.object` string-literal discipline the `:advanced` bundle needs.

  The cost of getting this wrong is on record: `slot-order` was one rule
  written down three times, one copy was repaired, and the other two went
  on returning a single order for two-arm plans until the guard refused
  four rows (rf2-ouwh8).

  Authority: `docs/EP/EP-0038-the-hicasso-view-layer-programme.md`;
  the bar and the P0 table are operator-owned on the governance set that
  superseded bead rf2-2rtt6.1 on 2026-08-10, enumerated once in
  `docs/design/hicasso/studio/README.md`.
  Spec donor: rf2-ssn1o (closed, do-not-refile)."
  (:require ["react-dom" :as react-dom]
            [goog.object :as gobj]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.order-guard :as guard]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [reagent.core :as r]
            [reagent.dom.client :as rdc])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def cells-n
  "300 cells, the donor row's fixture. Held identical so this arm's
  numbers sit beside the donor's narrow row without a scaling caveat."
  300)

(def writes-per-sample
  "Chrome clamps `performance.now()` to 100 us and a single narrow write
  sits on that clamp — measured one at a time, the donor's Reagent arm
  returned exactly 0.1 ms, which is the quantum itself wearing a result's
  hat. Twenty writes to a sample lifts every arm clear of it."
  20)

;; ---------------------------------------------------------------------------
;; The clock
;; ---------------------------------------------------------------------------

(defn now [] (js/performance.now))

(defn clock-quantum
  "The smallest non-zero step this page's `performance.now()` can report,
  measured rather than assumed.

  Chrome's clamp is documented as 100 us but it is a browser policy, not
  a language guarantee, and it moves with cross-origin-isolation and with
  the flags a driver happens to pass. Every leg figure below is quoted
  against this number, so it is read from inside the bundle that produced
  those figures."
  []
  (let [deltas (array)]
    (loop [tries 0]
      (when (and (< tries 200000) (< (alength deltas) 64))
        (let [a (now) b (now)]
          (when (> b a) (.push deltas (- b a)))
          (recur (inc tries)))))
    (if (zero? (alength deltas))
      -1
      (apply min (array-seq deltas)))))

;; ---------------------------------------------------------------------------
;; Accumulators — string literals only (see the namespace docstring)
;; ---------------------------------------------------------------------------

(defn- bump! [o k v] (gobj/set o k (+ (gobj/get o k) v)))

(defn- fresh-acc []
  (js-obj "control" 0 "write" 0 "gap" 0 "force" 0 "total" 0 "span" 0
          "bad" 0 "writes" 0))

(defn integrity-probe
  "Prove, inside the `:advanced` bundle, that this namespace reads the
  keys it writes.

  Runs before any measurement. Writes known values through exactly the
  accessors the measured path uses and hands back both the values and the
  object's own key list. A renaming mismatch makes this return the wrong
  number or a short key list, and the driver refuses to measure.

  `bad` is the key this exists for: it is the unverified-write counter,
  and a renamed accessor would leave it reading a constant zero — a
  harness that reports perfect DOM verification precisely because it can
  no longer see a failure."
  []
  (let [o (fresh-acc)]
    (bump! o "control" 7)
    (bump! o "bad" 5)
    (bump! o "total" -11)
    (js-obj "control" (gobj/get o "control")
            "bad"     (gobj/get o "bad")
            "total"   (gobj/get o "total")
            "keys"    (.join (js/Object.keys o) ","))))

;; ---------------------------------------------------------------------------
;; The positive control — a predicted number of milliseconds
;; ---------------------------------------------------------------------------

(defonce ^:private ctl-ms (volatile! 0.0))
(defonce ^:private ctl-sink (volatile! 0.0))

(defn control-prepare!
  "Set the control's predicted duration, in milliseconds, OUTSIDE every
  window. Zero disables it."
  [ms]
  (vreset! ctl-ms (double ms))
  (vreset! ctl-sink 0.0)
  nil)

(defn control-spin!
  "Burn the predicted duration. Runs as its own leg of every measured
  window.

  The sink is not decoration: Closure is entitled to delete a loop whose
  result nothing reads, and this surface has already published a leg of
  exactly 0.000 in every arm because a property read was dropped. The
  final clock reading is summed into a volatile that outlives the call,
  so the loop must run."
  []
  (let [d @ctl-ms]
    (when (pos? d)
      (let [t0 (now)]
        (loop []
          (let [t (now)]
            (if (< (- t t0) d)
              (recur)
              (vreset! ctl-sink (+ @ctl-sink t)))))))
    nil))

;; ---------------------------------------------------------------------------
;; The re-frame side — one graph, two write doors
;; ---------------------------------------------------------------------------

(rf/reg-sub :hn/cell (fn [db [_ i]] (get-in db [:cells i])))

(rf/reg-event :hn/seed
  (fn [_ [_ n]] {:db {:cells (vec (repeat n 0))}}))

(rf/reg-event :hn/set-cell
  (fn [{:keys [db]} [_ i v]] {:db (update db :cells assoc i v)}))

(rf/reg-event :hn/set-all
  (fn [{:keys [db]} [_ n v]] {:db (assoc db :cells (vec (repeat n v)))}))

;; A `reg-view`, not a plain Reagent fn: only a registered view carries the
;; `:contextType` static-field wiring Reagent needs to read the enclosing
;; frame-provider's React context, and a render-time `subscribe` that
;; cannot resolve a frame raises `:rf.error/no-frame-context` (Spec 002
;; §Reading the frame from React context).
;; `sub?` is what makes the ladder a SUBSCRIPTION-count ladder rather than a
;; fixture-size one. A cell renders the same span at the same `data-i` either
;; way; only the `subscribe` is conditional. See [[ladder-cells]].
(reg-view spine-cell [i sub?]
  [:span.cell {:data-i i}
   (if sub? (str @(rf/subscribe [:hn/cell i])) "-")])

(reg-view spine-grid [n-cells n-subs]
  [:div.ugrid
   (for [i (range n-cells)]
     ^{:key i} [spine-cell i (< i n-subs)])])

(def ladder-cells
  "The SUBSCRIPTION-COUNT ladder — three rungs, and every rung mounts the
  SAME 300-cell fixture.

  The headline figure says the flush carries essentially all of a narrow
  write's cost on this substrate. That is one word doing two jobs: the
  flush is where Reagent re-runs every dirtied reaction AND where React
  commits the one cell that changed, and a single number cannot say which
  of those the money went to.

  THE FIRST CUT DID NOT ISOLATE THE THING IT NAMED, and the correction is
  the reason this reads the way it does. Each rung was built by handing
  `spine-arm` a cell count, so a rung changed the app-db vector, the
  mounted component count, the DOM span count, the reaction count AND the
  subscription count together. The page said \"the React commit is the same
  one-cell commit at every rung, and only the subscription count moves\";
  it was not, and a slope fitted through those rungs described a compound
  FIXTURE-SIZE ladder. The negative intercept and the super-linear
  exponent were real properties of that ladder, but they could not be
  attributed to layer-1 subscriptions, which is exactly what the page did.

  So the rungs now hold everything constant but the subscription. Every
  rung seeds 300 cells into app-db, mounts 300 `spine-cell` occurrences and
  renders 300 spans; the rung's number is how many of those cells actually
  `subscribe`, and the rest render an inert dash. Writes target the
  SUBSCRIBING range, so the React commit is one cell at every rung by
  construction rather than by assertion.

  Three rungs rather than two is the earlier repair, kept: two points
  cannot show a line refusing the model it was fitted to, and 30/300 fitted
  2.27 us per subscription on an intercept of MINUS 0.048 ms. Work that
  does not scale with the count cannot cost less than nothing.

  30 rather than 1 at the bottom: one subscription in a 300-cell page is a
  degenerate graph, and a ladder wants one shape at three sizes."
  [30 100 300])

;; ---------------------------------------------------------------------------
;; The bare-ratom arm — b6's shape, reproduced character for character
;; ---------------------------------------------------------------------------

;; `defonce` takes no docstring, hence the comment. This is Reagent's own
;; reactive primitive with no re-frame anywhere near it: the arm the
;; predecessor's narrow row compared against.
(defonce bare-cells (r/atom []))

(defn bare-cell
  "A FORM-2 component: the outer call mints a `cursor` once per mounted
  occurrence and the returned render fn dereferences only that cursor.
  This is Reagent's idiomatic fine-grained leaf, and the counterpart of
  the spine arms' per-cell subscription."
  [i]
  (let [c (r/cursor bare-cells [i])]
    (fn [i]
      [:span.cell {:data-i i} (str @c)])))

(defn bare-grid [n]
  [:div.ugrid
   (for [i (range n)]
     ^{:key i} [bare-cell i])])

;; ---------------------------------------------------------------------------
;; Arms
;; ---------------------------------------------------------------------------

(defn- flush-reagent!
  "Reagent's own documented synchronous render drain, inside a
  `flushSync` so React commits it in this window rather than on its own
  scheduler a couple of milliseconds later.

  NOT `re-frame.adapter.reagent/flush-views!`, which wraps `act`: act
  diverts work to its own queue, which is not what a browser does."
  []
  (react-dom/flushSync (fn [] (r/flush))))

(defn- instrument-arm
  "A pseudo-arm that installs no state and drains nothing, so a window
  over it contains the harness and nothing else — the control spin, four
  clock reads, one promise, one accumulate. Its read-back is skipped
  because it renders no cell to read back."
  []
  {:id :instrument :skip-verify? true :cells cells-n :subs cells-n
   :mount   (fn [container] container)
   :write!  (fn [_ _] nil)
   :force!  (fn [] nil)
   :unmount (fn [_] nil)})

(defn- bare-ratom-arm []
  {:id :bare-ratom :cells cells-n :subs cells-n
   :mount   (fn [container]
              (reset! bare-cells (vec (repeat cells-n 0)))
              (let [root (rdc/create-root container)]
                (react-dom/flushSync (fn [] (rdc/render root [bare-grid cells-n])))
                root))
   :write!  (fn [i val]
              (if (= i :all)
                (reset! bare-cells (vec (repeat cells-n val)))
                (swap! bare-cells assoc i val)))
   :force!  flush-reagent!
   :unmount (fn [root] (react-dom/flushSync (fn [] (rdc/unmount root))))})

(defn- spine-arm
  "One re-frame-on-Reagent arm. `write-door` is `:replace-app-db` (the
  donor row's door — the frame write and the signal graph, with no event
  drain) or `:dispatch-sync` (the door an application uses).

  `n-subs` is HOW MANY CELLS SUBSCRIBE, not how big the fixture is. Every
  arm seeds `cells-n` cells into app-db, mounts `cells-n` `spine-cell`
  occurrences and renders `cells-n` spans; only the first `n-subs` of them
  hold a subscription. That is what makes the ladder in [[ladder-cells]] a
  subscription ladder — the audit's first correction, and the reason
  `n-subs` is the ONLY thing a rung is allowed to move.

  Writes target `[0, n-subs)` so a narrow write always lands on a
  SUBSCRIBING cell, which keeps the React commit one cell at every rung by
  construction.

  Each arm owns its OWN frame: frames are isolated contexts and two arms
  sharing one would let each other's writes into the other's graph."
  ([id frame-id write-door] (spine-arm id frame-id write-door cells-n))
  ([id frame-id write-door n-subs]
   {:id id :cells cells-n :subs n-subs
    :mount
    (fn [container]
      (rf/make-frame {:id frame-id})
      (rf/with-frame frame-id (rf/dispatch-sync [:hn/seed cells-n]))
      (let [root (rdc/create-root container)]
        (react-dom/flushSync
          (fn [] (rdc/render root [rf/frame-provider {:frame frame-id}
                                   [spine-grid cells-n n-subs]])))
        root))
    :write!
    (fn [i val]
      (case write-door
        :replace-app-db
        (let [db (frame/frame-app-db-value frame-id)]
          (frame/replace-app-db!
            frame-id
            (if (= i :all)
              (assoc db :cells (vec (repeat cells-n val)))
              (update db :cells assoc i val))))

        :dispatch-sync
        (rf/with-frame frame-id
          (if (= i :all)
            (rf/dispatch-sync [:hn/set-all cells-n val])
            (rf/dispatch-sync [:hn/set-cell i val])))))
    :force!  flush-reagent!
    :unmount (fn [root] (react-dom/flushSync (fn [] (rdc/unmount root))))}))

(defn negative-control-arm
  "THE READ-BACK GATE'S NON-VACUITY CONTROL, and it is built to FAIL.

  Identical to `:spine-replace` in every respect but one: `force!` is an
  EMPTY `react-dom/flushSync` instead of `flushSync(reagent.core/flush)`.
  An empty flush drains only React's sync lane; a Reagent ratom write sits
  on Reagent's own batched queue and is not on it. So the clock stops on
  the OLD value and the DOM read-back must catch it.

  This arm is never quoted as a measurement. It exists so that
  \"0 unverified of N\" on the real arms is evidence — a gate that cannot
  go red has not verified anything. The driver runs it once, expects
  nearly every write to fail, and refuses if they do not."
  []
  (assoc (spine-arm :negative-control :hn/negative-control :replace-app-db)
         :force! (fn [] (react-dom/flushSync (fn [] nil)))))

(defn make-arms
  "The measured arms, in the order they are DECLARED — the run order is
  `guard/slot-order`'s and varies with the sample index."
  []
  (into [(instrument-arm)
         (bare-ratom-arm)
         (spine-arm :spine-replace  :hn/spine-replace  :replace-app-db)
         (spine-arm :spine-dispatch :hn/spine-dispatch :dispatch-sync)]
        ;; The ladder's rungs BELOW the headline count. The 300-subscription
        ;; rung is `:spine-replace` itself — measuring it twice would price
        ;; the same arm under two names and invite the two copies to
        ;; disagree. `n` here is a SUBSCRIPTION count; every rung mounts the
        ;; full `cells-n` fixture.
        (map (fn [n]
               (spine-arm (keyword (str "spine-" n))
                          (keyword "hn" (str "spine-" n))
                          :replace-app-db
                          n)))
        (butlast ladder-cells)))

;; ---------------------------------------------------------------------------
;; Mounting
;; ---------------------------------------------------------------------------

(defn init-adapter!
  "Install the Reagent adapter and leave React's `act` environment.

  `act` diverts work to its own queue, which is not what a browser does,
  and every reading here is the browser's."
  []
  (rf/init! reagent-adapter/adapter)
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn mount-arms! [arms]
  (mapv (fn [a]
          (let [c (js/document.createElement "div")]
            (.appendChild js/document.body c)
            {:arm a :container c :handle ((:mount a) c)}))
        arms))

(defn release-arms! [mounts]
  (doseq [{:keys [arm handle container]} mounts]
    (try ((:unmount arm) handle) (catch :default _ nil))
    (.remove container))
  nil)

(defn cell-text [container i]
  (some-> (.querySelector container (str "[data-i=\"" i "\"]")) (.-textContent)))

;; ---------------------------------------------------------------------------
;; The measured window
;; ---------------------------------------------------------------------------

(defonce ^:private gen-seq (atom 1000))
(defn next-gen! [] (swap! gen-seq inc))

(def ^:private chain
  "Fold `xs` into a serial promise chain, threading an accumulator —
  `lane/chain`, not a restatement of it (rf2-uhw11)."
  lane/chain)

(defn- timed-write!
  "One narrow write, clocked at every leg boundary, then verified at the
  DOM. Answers a promise of a `js-obj` of legs plus the read-back verdict.

  The microtask between the write and the force is the donor window's and
  is kept so the two are comparable; on this substrate no arm requires it
  (a `reagent.core/flush` is already on React's sync lane), so `:gap`
  prices the boundary itself and is expected to be ~0."
  [{:keys [arm container]} i val]
  (let [t0 (now)]
    (control-spin!)
    (let [tc (now)]
      ((:write! arm) i val)
      (let [t1 (now)]
        (-> (js/Promise.resolve nil)
            (.then
              (fn [_]
                (let [t2 (now)]
                  ((:force! arm))
                  (let [t3    (now)
                        probe (if (= i :all) 0 i)]
                    (js-obj "control" (- tc t0)
                            "write"   (- t1 tc)
                            "gap"     (- t2 t1)
                            "force"   (- t3 t2)
                            ;; THE PUBLISHED FIGURE: write + gap + force,
                            ;; with the control's leg excluded.
                            "total"   (- t3 tc)
                            "span"    (- t3 t0)
                            "ok"      (or (boolean (:skip-verify? arm))
                                          (= (str val)
                                             (cell-text container probe)))))))))))))

(defn run-sample!
  "One SAMPLE: `n` narrow writes over one arm, legs accumulated, every
  write verified at the DOM in its own window."
  [mnt n]
  (let [acc   (fresh-acc)
        ;; The SUBSCRIBING range, not the fixture: a ladder rung mounts 300
        ;; cells of which `:subs` subscribe, and a write outside that range
        ;; would commit nothing and verify nothing.
        width (or (:subs (:arm mnt)) cells-n)]
    (chain acc (range n)
      (fn [a _]
        (let [val (next-gen!)
              i   (mod val width)]
          (-> (timed-write! mnt i val)
              (.then (fn [r]
                       (bump! a "control" (gobj/get r "control"))
                       (bump! a "write"   (gobj/get r "write"))
                       (bump! a "gap"     (gobj/get r "gap"))
                       (bump! a "force"   (gobj/get r "force"))
                       (bump! a "total"   (gobj/get r "total"))
                       (bump! a "span"    (gobj/get r "span"))
                       (bump! a "writes"  1)
                       (when-not (gobj/get r "ok") (bump! a "bad" 1))
                       a))))))))

(defn seed-arms!
  "Re-seed every arm to a known state between rounds so no round inherits
  another's growth. Never timed."
  [mounts]
  (chain nil mounts
    (fn [_ mnt] (-> (timed-write! mnt :all 0) (.then (fn [_] nil))))))

;; ---------------------------------------------------------------------------
;; A round — arms interleaved, order rotating AND reflecting
;; ---------------------------------------------------------------------------

(defn run-round!
  "One round: `(+ warmup samples)` sample indices, every arm measured at
  every index, arm order `guard/slot-order` — rotate, then REFLECT on odd
  indices.

  The reflection is the both-orders discipline in situ. A bare cyclic
  rotation changes which arm goes FIRST and nothing else: arm `a` sits at
  slot `(a - s) mod k`, so its predecessor is `(a - 1) mod k` at every
  sample index. Reflecting on odd indices replaces every `a -> a+1`
  adjacency with `a -> a-1`, which is what gives each arm two
  predecessors in balance and lets [[guard/verdict]] ask the question at
  all.

  Answers a promise of `{:samples [...] :bad n}` where each sample carries
  its arm, its legs, its POSITION in the run and its PREDECESSOR — the two
  factors the guard stratifies on.

  `position0` is the global sample counter at the start of this round, so
  positions are continuous across rounds and the phase factor sees the
  whole trajectory rather than restarting each round."
  [mounts {:keys [warmup samples writes]} position0]
  (let [k     (count mounts)
        total (+ warmup samples)
        plan  (vec (for [s (range total) j (guard/slot-order k s)] [s j]))]
    (chain {:samples [] :bad 0 :prev nil :pos position0}
           (range (count plan))
      (fn [acc idx]
        (let [[s j] (nth plan idx)
              mnt   (nth mounts j)
              id    (:id (:arm mnt))]
          (-> (run-sample! mnt writes)
              (.then (fn [a]
                       (let [rec {:arm         id
                                  :position    (:pos acc)
                                  :predecessor (:prev acc)
                                  :control     (gobj/get a "control")
                                  :write       (gobj/get a "write")
                                  :gap         (gobj/get a "gap")
                                  :force       (gobj/get a "force")
                                  :total       (gobj/get a "total")
                                  :span        (gobj/get a "span")
                                  :bad         (gobj/get a "bad")
                                  :writes      (gobj/get a "writes")
                                  ;; `slot-order` reflects on ODD sample
                                  ;; indices; this is the label the report
                                  ;; splits "both orders" on.
                                  :order       (if (odd? s) :reflected :forward)
                                  :warmup?     (< s warmup)}]
                         (cond-> (assoc acc :prev id :pos (inc (:pos acc))
                                            :bad (+ (:bad acc) (gobj/get a "bad")))
                           (>= s warmup) (update :samples conj rec)))))))))))

;; ---------------------------------------------------------------------------
;; Summaries
;; ---------------------------------------------------------------------------

(def summarise
  "`{:n :min :p50 :max}` over `xs`. A RANGE, always — never a mean alone.
  Overlapping ranges mean indistinguishable, and a report that quotes a
  central value without its range cannot say that.

  `lane/summarise`, not a restatement of it. This lane and the mount lane
  are two INSTRUMENTS — a mount is one timed commit, a narrow write is
  write, microtask, flush, verify — but they are one METHOD, and the
  method must not be written down twice (rf2-uhw11). The `slot-order`
  degeneracy this repository has just finished repairing was a method
  written down three times and fixed in one of them (rf2-ouwh8)."
  lane/summarise)

(defn per-write
  "Divide a per-SAMPLE leg figure by the writes it contains."
  [ms writes]
  (/ ms (double writes)))

(defn arm-summary
  "Per-arm, per-write summaries of every leg, split three ways: pooled,
  forward-order samples, reflected-order samples.

  The two order strata are the both-orders presentation. They are
  reported as ranges and adjudicated by the house rule — overlapping
  ranges mean the order did not move the figure."
  [samples]
  (let [legs [:total :write :gap :force :control :span]
        of   (fn [ss leg] (summarise (map #(per-write (get % leg) (:writes %)) ss)))
        fwd  (filterv #(= :forward (:order %)) samples)
        refl (filterv #(= :reflected (:order %)) samples)]
    {:n        (count samples)
     :bad      (reduce + 0 (map :bad samples))
     :writes   (reduce + 0 (map :writes samples))
     :pooled   (into {} (map (fn [l] [l (of samples l)])) legs)
     :forward  (into {} (map (fn [l] [l (of fwd l)])) legs)
     :reflected (into {} (map (fn [l] [l (of refl l)])) legs)}))

(defn summarise-run
  "Fold every round's samples into `{arm-id summary}` plus the guard's
  verdict over the same samples."
  [samples tolerance]
  (let [by-arm (group-by :arm samples)]
    {:arms   (into {} (map (fn [[id ss]] [id (arm-summary ss)])) by-arm)
     :guard  (guard/verdict
               (mapv (fn [s] {:arm         (name (:arm s))
                              :value       (per-write (:total s) (:writes s))
                              :predecessor (some-> (:predecessor s) name)
                              :position    (:position s)})
                     samples)
               {:tolerance tolerance})}))

;; ---------------------------------------------------------------------------
;; EDN across the boundary
;; ---------------------------------------------------------------------------

(defn publish!
  "Write one record to the console as EDN, tagged so the driver can find
  it in a transcript."
  [tag record]
  (js/console.log (str ";; HN " tag "\n" (pr-str record)))
  nil)

(defn guard-self-test!
  "Run the order guard's own self-test and answer whether it passed. The
  checks are fixtures replayed from the recorded study, so this is
  deterministic — and a harness that gets `false` must measure nothing.

  Run from inside the `:advanced` bundle rather than from the driver,
  because the guard that adjudicates these figures is the CLJC one this
  bundle carries, not the `.cjs` copy the donor's Node drivers reach."
  []
  (let [st (guard/self-test)]
    (doseq [c (:checks st)]
      (js/console.log (str ";; HN order-guard " (if (:ok c) "ok  " "FAIL")
                           " " (:name c)
                           (when (:detail c) (str "  — " (:detail c))))))
    (boolean (:ok? st))))

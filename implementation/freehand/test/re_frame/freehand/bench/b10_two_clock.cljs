(ns re-frame.freehand.bench.b10-two-clock
  "B10 — the TWO-CLOCK operating envelope (DC-09), as fixture and
  instrument, with every `cljs.test` assertion left to the caller.

  Split from its test namespace for B6's reason and B9's: the method is
  one thing a reader should be able to check once, and the driver has to
  be callable from more than the browser suite.

  ## The two clocks

  DC-09's claim is that a Freehand application runs on two clocks that do
  not have to tick together:

    - the **HOST clock**, at pointer/animation/scroll rate, owned by a
      behavior mutating its own node. Nothing crosses into `app-db`.
    - the **SEMANTIC clock**, at whatever rate the application chooses to
      call meaningful, carrying `started` / `preview` / `committed`
      events into re-frame.

  The envelope is the answer to \"how fast may the semantic clock tick
  before it stops keeping up\", and that is not one number: it is a
  surface over the crossing's cost. So this namespace measures the COST
  of one crossing under the loads that move it, and drives a real timer
  at real rates to check that the cost predicts the breakdown.

  ## NO ELEVENTH INSTRUMENT

  Every window here is B6's: `re-frame.freehand.bench.b6-harness` for
  publication and the across-rounds range fold, `…bench.measure` for the
  clock and the distribution summary, `…bench.provenance` for the D021
  record. What is new is the FIXTURE and the two axes, not the method.

  ## The window, and the one rule that makes it honest

  A crossing is timed across `react-dom/flushSync` with the dispatch
  inside it, and split at the moment the dispatch returns:

      t0 ──flushSync[ dispatch-sync ──t1── (React render + commit) ]── t2

  `:event-ms` is `t1 − t0`: the router, the interceptor chain, the
  reducer, the `app-db` install and the subscription notification.
  `:commit-ms` is `t2 − t1`: React's render, commit and DOM mutation.
  That split is DC-09's acceptance criterion 3 — costs attributed to
  event/reducer/Freehand/React/host work rather than rolled into one
  latency number — and it is why the crossing is not measured as a
  single number.

  **Every measured crossing is then read back out of the DOM inside its
  own window, and a crossing that did not land is COUNTED, not
  discarded.** B6 records what happens without that rule: a first pass
  timed writes inside `flushSync` that never reached the page at all and
  returned entirely reasonable milliseconds for them. Each mode's
  read-back is a different assertion because each mode changes a
  different thing, and the `:fresh-equal` mode — whose whole point is
  that the page must NOT change — verifies instead that the `app-db`
  reference genuinely moved. An equality arm that silently did nothing
  would otherwise be the fastest arm in the table.

  ## Two positive controls, both computed rather than calibrated

  **C1, a duration.** [[burn-arm]] busy-waits for exactly `burn-ms`
  inside the measured window. Predicted `:total-ms` is `burn-ms`; the
  overshoot is clock quantisation plus whatever the operating system
  took away. On a workstation running other agents that second term is
  not small, and publishing it beside every figure is what stops a
  contended round being read as a slow substrate.

  **C2, a size.** Every mode's DOM mutation count is PREDICTED from the
  fixture's arithmetic and MEASURED with a `MutationObserver` whose
  records are taken synchronously. `:one-leaf` at K siblings must move
  exactly `K + 1` nodes; `:fresh-equal` must move none; `:host-only`
  must move exactly one attribute. A knob that does not dirty what it
  says it dirties fails here rather than in a conclusion.

  ## Both ladder orders

  The sibling ladder runs ASCENDING in half its rounds and DESCENDING in
  the other half, and both are published. A large arm inflating its
  successor is a documented hazard on this surface and it is only ever
  visible from the two orders side by side.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`. Measured claim
  owner: DC-09 in `docs/design/freehand/product-completion-setpoint.md`."
  (:require ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.bench.b6-harness :as h]
            [re-frame.freehand.bench.measure :as m]
            [re-frame.freehand.bench.provenance :as prov]))

;; ===========================================================================
;; The fixture
;; ===========================================================================

(def cells-n
  "Enough cells that the widest rung of the sibling ladder (200) is a
  real fraction of the page rather than the whole of it, so a 200-dirty
  crossing is still a PARTIAL update and the localisation the substrate
  claims is the thing under load."
  300)

(def sibling-ladder
  "DC-09's rungs, verbatim. 0 means the crossing moves the leaf alone."
  [0 1 10 50 200])

(def rate-ladder
  "DC-09's offered semantic rates, in updates per second."
  [30 60 120 240])

;; ---------------------------------------------------------------------------
;; Behavior configs, at two shapes
;; ---------------------------------------------------------------------------

(defn- vega-shaped
  "A nested spec map of the shape a chart library is handed — a handful
  of keys, several levels deep. `n` scales the encoding channel count."
  [n leaf]
  {:$schema  "https://vega.github.io/schema/vega-lite/v5.json"
   :mark     {:type "line" :tooltip true :interpolate "monotone"}
   :width    640 :height 480
   :encoding (into {} (map (fn [i]
                             [(keyword (str "ch" i))
                              {:field (str "f" i)
                               :type  "quantitative"
                               :scale {:zero false :nice true :domain [0 (+ i leaf)]}
                               :axis  {:title (str "Field " i) :grid (even? i)}}]))
                   (range n))})

(defn- spread-shaped
  "A wide flat cell map of the shape a spreadsheet range is handed — no
  nesting, `n` entries. The two shapes cost `rf=` differently and the
  bead asks for both."
  [n leaf]
  (into {} (map (fn [i] [(keyword (str "r" i)) (+ i leaf)])) (range n)))

(def config-shapes
  "`{:id {:build fn :leaves n}}`. `:leaves` is the count a reader can
  check the equality cost against; it is arithmetic over the builder
  above, not a measurement."
  {:vega   {:build vega-shaped   :channels 12  :leaves (* 12 8)}
   :spread {:build spread-shaped :channels 900 :leaves 900}})

(defn build-config
  [shape leaf]
  (let [{:keys [build channels]} (get config-shapes shape)]
    (build channels leaf)))

(defn deep-fresh
  "Rebuild every collection in `x`, so nothing above a scalar leaf is
  `identical?` to what it came from while everything is `=` to it.

  A shallow copy would not do. `rf=` short-circuits on identity at every
  level, so a top-level-only copy of a nested spec costs a walk of the
  TOP LEVEL and then five identity hits — which is not what an
  application pays when a library hands it a freshly parsed config, and
  would have understated the Vega shape's equality cost by the depth of
  its nesting."
  [x]
  (cond
    (map? x)    (into {} (map (fn [[k v]] [k (deep-fresh v)])) x)
    (vector? x) (mapv deep-fresh x)
    :else       x))

(defn touch-one-leaf
  "A deep-fresh copy of `cfg` with exactly ONE scalar leaf different.
  Spelled per shape, because the shapes' leaves are in different places
  and a generic walk would be a third thing to get wrong."
  [shape cfg gen]
  (let [fresh (deep-fresh cfg)]
    (case shape
      :vega   (assoc-in fresh [:encoding :ch0 :axis :title] (str "Field " gen))
      :spread (assoc fresh :r0 gen))))

(defn dissoc-leaf
  "`cfg` with the one leaf [[touch-one-leaf]] moves removed, so a
  read-back can prove ONE leaf changed rather than merely that something
  did. Without it a `:one-leaf` arm that replaced the whole config would
  pass its own verification."
  [shape cfg]
  (case shape
    :vega   (update-in cfg [:encoding :ch0 :axis] dissoc :title)
    :spread (dissoc cfg :r0)))

;; ---------------------------------------------------------------------------
;; re-frame registrations — the CONSUMER path, never a store poke
;; ---------------------------------------------------------------------------
;;
;; Every crossing below goes through `rf/dispatch-sync` or `rf/dispatch`,
;; registered with `rf/reg-event`, read through `rf/reg-sub`. B6's update
;; row writes with `frame/replace-app-db!`, which is right for a
;; cross-substrate markup comparison and wrong here: DC-09 is about
;; semantic EVENTS crossing, and an instrument that skipped the router
;; and the interceptor chain would price a path no application takes.

(def frame-id :b10/two-clock)

(rf/reg-sub :b10/cell   (fn [db [_ i]] (get-in db [:cells i])))
(rf/reg-sub :b10/field  (fn [db _] (:field db)))
(rf/reg-sub :b10/config (fn [db _] (:config db)))

(defonce ^:private applied-count (atom 0))
(defn applied [] @applied-count)
(defn reset-applied! [] (reset! applied-count 0))

(defn- dirty
  "Write `gen` into cells `0 … k` inclusive — `k + 1` cells, so `k` reads
  as \"and this many SIBLINGS beside the leaf\"."
  [cells k gen]
  (reduce (fn [cs i] (assoc cs i gen)) cells (range (inc k))))

(rf/reg-event :b10/seed
  (fn [_ [_ n shape]]
    (reset! applied-count 0)
    {:db {:cells    (vec (repeat n 0))
          :field    ""
          :siblings 0
          :config   (build-config shape 0)}}))

;; How many siblings a KEYSTROKE dirties. Set before a typing run so the
;; controlled-input ladder is driven by the same knob as the crossing
;; ladder, rather than by a second, differently-spelled one.
(rf/reg-event :b10/contend
  (fn [{:keys [db]} [_ k]]
    {:db (assoc db :siblings k)}))

;; The controlled-input crossing. Every keystroke ALSO dirties `k`
;; siblings, so the frame-scoped synchronous flush has to settle all of
;; them before the listener returns — FH-INPUT-003's contention shape,
;; here on DC-09's ladder rather than at a single dozen.
(rf/reg-event :b10/typed
  (fn [{:keys [db]} [_ s]]
    (swap! applied-count inc)
    {:db (-> db
             (assoc :field s)
             (update :cells dirty (:siblings db) (count s)))}))

;; The semantic crossing under test. `k` names how many siblings the
;; application chose to dirty alongside the leaf, so `k + 1` cells change
;; and the rest do not.
(rf/reg-event :b10/moved
  (fn [{:keys [db]} [_ k gen]]
    (swap! applied-count inc)
    {:db (update db :cells dirty k gen)}))

;; A fresh-but-`rf=`-equal cells vector: a new object holding the same
;; values, so the store's identity moves and the page must not.
(rf/reg-event :b10/fresh-equal-cells
  (fn [{:keys [db]} _]
    (swap! applied-count inc)
    {:db (update db :cells (fn [cells] (into [] (map identity) cells)))}))

;; The behavior-config crossing. The config to install is built by the
;; CALLER, outside the measured window, and handed in whole — because a
;; reducer that built a 900-entry map inside the window would be timing
;; `into` rather than equality, and the difference between the three
;; modes is exactly what this axis exists to isolate.
(rf/reg-event :b10/config-install
  (fn [{:keys [db]} [_ cfg]]
    (swap! applied-count inc)
    {:db (assoc db :config cfg)}))

;; ---------------------------------------------------------------------------
;; The behavior — the host clock's owner
;; ---------------------------------------------------------------------------

(defonce ^:private behavior-log (atom {:connect 0 :update 0 :disconnect 0}))
(defn behavior-calls [] @behavior-log)
(defn reset-behavior-log! [] (reset! behavior-log {:connect 0 :update 0 :disconnect 0}))

(v/defbehavior host-clock
  "The DC-09 default made concrete: a behavior owning one node's motion.
  `:update` writes the node's own transform and touches nothing else, so
  a host tick costs a style write and no `app-db` version at all.

  Its call COUNTS are the instrument for the config axis. Whether
  Freehand elides an `:update` whose config is unchanged is a question
  this fixture answers by counting rather than by reading the runtime."
  {:timing  :passive
   :connect (fn [{:keys [node config]}]
              (swap! behavior-log update :connect inc)
              (.setAttribute node "data-leaves" (str (count config)))
              nil)
   :update  (fn [{:keys [node config]}]
              (swap! behavior-log update :update inc)
              (.setAttribute node "data-leaves" (str (count config)))
              nil)
   :disconnect (fn [_] (swap! behavior-log update :disconnect inc) nil)})

;; ---------------------------------------------------------------------------
;; Views
;; ---------------------------------------------------------------------------

(v/defview cell
  [{:keys [i]}]
  [:span.b10cell {:data-i i} (str (v/sub [:b10/cell i]))])

(v/defview field
  "The controlled field. `:value` and an event-vector `:on-input` put it
  through the controlled-input door, which is what makes its keystroke
  path synchronous — the property FH-INPUT-003 gates and this namespace
  measures the distribution of."
  [_]
  [:input.b10field {:data-testid "b10-field"
                    :type        "text"
                    :value       (v/sub [:b10/field])
                    :on-input    [:b10/typed ::v/value]}])

(v/defview host-panel
  "The behavior with the config subscription read AT ITS OWN BOUNDARY.
  The `:hoisted` half of the pair below."
  [_]
  [v/behavior {:use host-clock :target :b10/host :config (v/sub [:b10/config])}
   [:div.b10host {:data-testid "b10-host"}]])

(v/defview surface
  "The page, in two spellings that differ in ONE thing: where the config
  subscription is read.

    :parent  the config sub is read HERE, in the view that also builds
             the 300 cells — the shape an author naturally writes when
             the behavior's config comes from app-db.
    :leaf    the config sub is read inside [[host-panel]], one boundary
             down, so the cells' parent does not depend on it.

  Nothing else changes: the same behavior, the same config, the same
  cells, the same field. The pair exists because the cost of a config
  change turned out to be an order of magnitude larger than the cost of
  a cell change while moving FEWER DOM nodes, and the obvious
  explanation — that the whole subtree reconciles — is the sort of thing
  that should be measured rather than asserted."
  [{:keys [n read-at]}]
  (let [cfg (when (= :parent read-at) (v/sub [:b10/config]))]
    [:div.b10
     (if (= :parent read-at)
       [v/behavior {:use host-clock :target :b10/host :config cfg}
        [:div.b10host {:data-testid "b10-host"}]]
       [host-panel {}])
     [field {}]
     [:div.b10cells
      (for [i (range n)]
        [cell {:key i :i i}])]]))

;; ===========================================================================
;; Reading the page
;; ===========================================================================

(defn cell-text [container i]
  (some-> (.querySelector container (str "[data-i=\"" i "\"]")) (.-textContent)))

(defn field-node [container]
  (.querySelector container "[data-testid=\"b10-field\"]"))

(defn host-node [container]
  (.querySelector container "[data-testid=\"b10-host\"]"))

;; ---------------------------------------------------------------------------
;; C2 — the mutation counter
;; ---------------------------------------------------------------------------

(defn- observe!
  "A `MutationObserver` over `container`'s whole subtree. `takeRecords`
  answers synchronously, which is the only reason this can be read
  inside a `flushSync`-bracketed window at all."
  [container]
  (let [o (js/MutationObserver. (fn [_ _] nil))]
    (.observe o container #js {:subtree true :childList true
                               :characterData true :attributes true})
    o))

(defn- take-mutations!
  "Answer `{:total n :by-type {…}}` for everything observed since the last
  take, and leave the observer armed."
  [o]
  (let [recs (.takeRecords o)]
    {:total   (.-length recs)
     :by-type (reduce (fn [acc r] (update acc (keyword (.-type r)) (fnil inc 0)))
                      {} (array-seq recs))}))

;; ===========================================================================
;; The measured window
;; ===========================================================================

(defn- burn!
  "Busy-wait until `ms` of the SAME clock the window is read with has
  passed. Its duration is the argument, which is what makes it a
  positive control rather than a calibration."
  [ms]
  (let [t0 (m/now-ms)]
    (loop [n 0]
      (if (< (- (m/now-ms) t0) ms)
        (recur (inc n))
        n))))

(defn crossing!
  "One semantic crossing, timed, attributed and VERIFIED at the DOM.

  `thunk` is what runs inside the flush; `verify` is handed the container
  and answers truthy when the page (or, for the equality mode, the store)
  shows what the crossing claimed to do. Answers
  `{:total-ms :event-ms :commit-ms :mutations :ok?}`."
  [container observer thunk verify]
  (take-mutations! observer)                 ; discard anything in flight
  (let [t0 (m/now-ms)
        t1 (volatile! nil)]
    (react-dom/flushSync (fn [] (thunk) (vreset! t1 (m/now-ms))))
    (let [t2   (m/now-ms)
          muts (take-mutations! observer)]
      {:total-ms  (- t2 t0)
       :event-ms  (- @t1 t0)
       :commit-ms (- t2 @t1)
       :mutations muts
       :ok?       (boolean (verify container))})))

;; ---------------------------------------------------------------------------
;; The modes
;; ---------------------------------------------------------------------------

(defonce ^:private gen-seq (atom 5000))
(defn next-gen! [] (swap! gen-seq inc))

(defn sibling-mode
  "`{:id … :run! … :predicted-mutations …}` for one rung of the sibling
  ladder. Three modes, each verified differently because each claims
  something different.

    :host-only   the DC-09 default — the behavior writes its own node,
                 nothing crosses. Predicted: one attribute mutation, and
                 FLAT across the ladder. A host arm that rises with `k`
                 would mean the ladder leaks into arms it must not
                 touch, and that flatness is checked as a gate.
    :fresh-equal a crossing that installs a fresh-but-`rf=`-equal cells
                 vector. Predicted: NO mutations — and verified by the
                 store's identity moving, never by the page standing
                 still, which an arm that did nothing would also achieve.
    :one-leaf    the naive dispatch-per-move: `k + 1` cells change.
                 Predicted: `k + 1` mutations."
  [mode k]
  (case mode
    :host-only
    {:id :host-only :predicted-mutations 1
     :run! (fn [container observer]
             (let [gen  (next-gen!)
                   node (host-node container)]
               (crossing! container observer
                          (fn [] (set! (.. node -style -transform)
                                       (str "translateX(" (mod gen 97) "px)")))
                          (fn [c] (= (str "translateX(" (mod gen 97) "px)")
                                     (.. (host-node c) -style -transform))))))}

    :fresh-equal
    {:id :fresh-equal :predicted-mutations 0
     :run! (fn [container observer]
             (let [before (:cells (frame/frame-app-db-value frame-id))]
               (crossing! container observer
                          (fn [] (rf/dispatch-sync
                                   [:b10/fresh-equal-cells] {:frame frame-id}))
                          (fn [_]
                            (let [after (:cells (frame/frame-app-db-value frame-id))]
                              ;; the write LANDED (a new vector) and it was
                              ;; equal (so the page was right not to move).
                              (and (not (identical? before after))
                                   (= before after)))))))}

    :one-leaf
    {:id :one-leaf :predicted-mutations (inc k)
     :run! (fn [container observer]
             (let [gen (next-gen!)]
               (crossing! container observer
                          (fn [] (rf/dispatch-sync [:b10/moved k gen] {:frame frame-id}))
                          (fn [c]
                            (and (= (str gen) (cell-text c 0))
                                 (= (str gen) (cell-text c k))
                                 ;; and the rung BEYOND the ladder did not move,
                                 ;; so `k` names what it says it names
                                 (or (>= (inc k) cells-n)
                                     (not= (str gen) (cell-text c (inc k)))))))))}))

(defn burn-arm
  "C1. Predicted `:total-ms` is exactly `burn-ms`; the overshoot is the
  instrument's own error plus the machine's."
  [burn-ms]
  {:id :control-burn :predicted-mutations 0 :predicted-ms burn-ms
   :run! (fn [container observer]
           (crossing! container observer
                      (fn [] (burn! burn-ms))
                      (fn [_] true)))})

(defn config-mode
  "One rung of the behavior-config axis at one shape.

  The config to install is derived from WHATEVER IS CURRENTLY IN THE
  STORE and built before the clock starts. Both halves of that matter.
  Building outside keeps `into` out of the window; deriving from the
  current value is what makes the arm order-independent — a first
  version built each mode's config from a fixed generation, so a
  `:fresh-equal` crossing that happened to follow a `:one-leaf` one was
  not equal to anything and failed its own read-back in 7 of every 10
  samples. Rotating arm order is what exposed it."
  [mode shape]
  {:id   (keyword (str (name shape) "-" (name mode)))
   :mode mode :shape shape
   :run! (fn [container observer]
           (let [gen    (next-gen!)
                 before (:config (frame/frame-app-db-value frame-id))
                 cfg    (case mode
                          :stable      before
                          :fresh-equal (deep-fresh before)
                          :one-leaf    (touch-one-leaf shape before gen))]
             (crossing! container observer
                        (fn [] (rf/dispatch-sync [:b10/config-install cfg] {:frame frame-id}))
                        (fn [_]
                          (let [after (:config (frame/frame-app-db-value frame-id))]
                            (case mode
                              :stable      (identical? before after)
                              :fresh-equal (and (not (identical? before after))
                                                (= before after))
                              :one-leaf    (and (not= before after)
                                                (= (dissoc-leaf shape before)
                                                   (dissoc-leaf shape after)))))))))})

;; ===========================================================================
;; Mount / teardown
;; ===========================================================================

(defn mount!
  "Mount the surface into a fresh attached container. `read-at` is
  `:parent` or `:leaf` — see [[surface]]. Answers
  `{:container :handle :observer}`."
  ([shape] (mount! shape :parent))
  ([shape read-at]
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    (reset-behavior-log!)
    (let [handle (react-dom/flushSync
                   (fn []
                     (v/mount [surface {:n cells-n :read-at read-at}] container
                              {:frame         {:id             frame-id
                                               :initial-events [[:b10/seed cells-n shape]]}
                               :disambiguator :b10/two-clock})))]
      {:container container :handle handle :observer (observe! container)}))))

(defn release!
  [{:keys [container handle observer]}]
  (when observer (.disconnect observer))
  (when handle (try (react-dom/flushSync (fn [] (v/unmount! handle)))
                    (catch :default _ nil)))
  (when container (.remove container))
  nil)

;; ===========================================================================
;; Rounds
;; ===========================================================================

(defn- summarise-arm
  [samples]
  (let [ms   (mapv :total-ms samples)
        evt  (mapv :event-ms samples)
        com  (mapv :commit-ms samples)
        muts (mapv #(get-in % [:mutations :total]) samples)]
    {:total-ms   (m/summarise ms)
     :event-ms   (m/summarise evt)
     :commit-ms  (m/summarise com)
     :mutations  {:min (apply min muts) :max (apply max muts)
                  :distinct (vec (sort (distinct muts)))}
     :unverified (count (remove :ok? samples))
     :n          (count samples)}))

(defn run-arms!
  "One round over `arms`, `samples` measured samples each after `warmup`,
  the arm order ROTATING on the sample index so no arm is always first
  into a cold cache. Answers `{id summary}` and the raw samples beside
  it."
  [mnt arms {:keys [warmup samples]}]
  (let [k   (count arms)
        acc (atom (zipmap (map :id arms) (repeat [])))]
    (dotimes [s (+ warmup samples)]
      (dotimes [j k]
        (let [arm (nth arms (mod (+ j s) k))
              r   ((:run! arm) (:container mnt) (:observer mnt))]
          (when (>= s warmup)
            (swap! acc update (:id arm) conj r)))))
    (into {} (map (fn [[id xs]] [id (assoc (summarise-arm xs) :raw (mapv :total-ms xs))]))
          @acc)))

;; ===========================================================================
;; The driven arm — offered against observed against accepted
;; ===========================================================================

(defn drive!
  "Offer a semantic event every `1000/rate` ms for `duration-ms` through
  the ORDINARY asynchronous `rf/dispatch`, and answer a promise of the
  ledger.

  Async on purpose. A `dispatch-sync` crossing cannot build a backlog —
  it is finished before the next offer can be made — so measuring
  overload through it would report a peak backlog of zero as a property
  of the framework rather than of the call. `rf/dispatch` is what a
  pointer handler calls, its queue is real, and this is where a backlog
  either appears or does not.

    :offered   driver invocations — what the application asked for
    :expected  `floor(duration-ms × rate / 1000)`, computed, not measured
    :applied   reducer applications, counted in the reducer itself
    :mutations DOM records observed across the whole run
    :peak-backlog  max over offers of `offered − applied`
    :tail-ms   the LAST GENERATION'S OWN OBSERVER LATENCY — the same
               number its entry in `:latency` carries, retained rather
               than recomputed
    :latency   per-generation offer→DOM distribution, read from the
               `MutationObserver` callback that carried it

  ## `:tail-ms` is anchored at the last offer, and a control says so

  The first edition of this driver read the tail off a clock **started at
  settle time**. The interval callback that notices `duration-ms` has
  elapsed is the one *after* the last offer — a whole period later by
  construction, since it is the next fire of the same `setInterval` — so
  `(now − that-instant)` silently omitted the interval it was supposed to
  contain, and where the last generation had already committed it
  reported approximately the 4 ms polling delay instead. The number was
  precise, plausible, and not the quantity its own docstring named. It is
  the fault `rf2-drpa3.182.12`'s merged-PR audit found, and it is the
  fifteenth on this surface to have produced a believable wrong number
  before anyone read the code.

  Two changes. The last offer's timestamp is **retained** — `offers`
  already held one per generation, so `t-offer` keeps the last of them
  rather than re-reading the clock later. And settlement is read from the
  `MutationObserver` that is already watching, the same clock the latency
  distribution comes from, rather than from the poll: the poll's 4 ms
  resolution would otherwise be quantisation on a quantity that is often
  smaller than 4 ms in a shipped bundle. **The 2 s polling ceiling is
  unchanged and still separate** — the poll remains the `settled?`
  detector and nothing else.

  ## The tail is not RECOMPUTED, it is RETAINED — and the algebra is published

  The third edition anchored the tail correctly but published it as a
  fresh subtraction, `(- t-settle t0)`, sitting beside a control derived
  from a DIFFERENT pair of instants. `rf2-drpa3.182.12`'s merged-PR audit
  found the consequence: moving that subtraction's start back to settle
  time left the gap, `:tail-anchored?` and the numeric/nonnegative checks
  every one of them green, so the control could not fail on the value it
  existed to protect. A gate that cannot go red is not a gate.

  So the observer computes each generation's latency ONCE, and the final
  generation's is retained and published verbatim. `:tail-ms` is not a
  subtraction performed at publication time; it is a number lifted out of
  the `:latency` distribution, and there is no start instant at the
  publication site left to get wrong. The two readings that produced it
  are published beside it so the identity can be asserted rather than
  believed — see `tail-identity-holds?`.

  Six fields make the anchoring checkable rather than asserted:

    :tail-offer-at-ms        the RETAINED last-offer reading, `t-offer`.
                             Origin-relative and meaningful only as a
                             difference (see `measure/now-ms`); published
                             so the tail's algebra is checkable.
    :tail-observed-at-ms     the instant the tail ended — the observer's
                             own reading when `:tail-source` is
                             `:observer`, the poll's when it is `:poll`.
    :offer-to-poll-start-ms  last offer → the settle decision. One
                             interval, by construction. This is exactly
                             the term the old reading dropped, so a clock
                             restarted at settle time cannot produce it.
                             Derived from `:tail-offer-at-ms` itself, so
                             moving that reading to escape the identity
                             check lands on this one.
    :predicted-gap-ms        the run's own achieved period,
                             `duration-ms / offered` — what that gap is
                             predicted to be, computed from a different
                             counter.
    :poll-wait-ms            settle decision → the poll noticing. Under
                             the old reading THIS was published as the
                             tail.
    :tail-anchored?          the deterministic control: the gap exists and
                             is at least half a requested period. A
                             `setInterval` cannot fire faster than its
                             period, so the floor holds however badly the
                             browser is falling behind; a settle-time
                             clock fails it outright.

  The two controls are deliberately SEPARATE and neither subsumes the
  other. `:tail-anchored?` (C3) says WHERE the retained offer reading
  sits — one interval before the settle decision. `tail-identity-holds?`
  (C4) says the published tail was computed from THAT reading and the
  observer's, and from nothing else. Re-anchoring the tail at settle time
  keeps C3 green and turns C4 red by a whole interval; re-anchoring the
  published offer reading to keep C4 green collapses the gap and turns C3
  red. There is no longer a way to move the tail's start that leaves both
  standing."
  [mnt rate k duration-ms]
  (let [{:keys [container observer]} mnt
        period    (/ 1000.0 rate)
        offered   (atom 0)
        backlog   (atom 0)
        offers    (atom {})               ; gen -> offer time
        latencies (atom [])
        muts      (atom 0)
        start     (m/now-ms)
        last-gen  (volatile! nil)
        t-offer   (volatile! nil)         ; the LAST offer's own timestamp
        ;; The newest generation the page has shown, retained WITH the two
        ;; readings that produced its latency and with that latency itself:
        ;; [generation offered-at observed-at latency-ms]. The tail is
        ;; LIFTED from here, not recomputed, so the published figure and the
        ;; generation's entry in the latency distribution are one number.
        last-obs  (volatile! nil)]
    (.disconnect observer)
    (reset-applied!)
    (let [watcher (js/MutationObserver.
                    (fn [recs _]
                      (swap! muts + (.-length recs))
                      (let [now (m/now-ms)
                            txt (cell-text container 0)
                            g   (js/parseInt txt 10)]
                        (when-not (js/isNaN g)
                          ;; ONE latency per generation, computed ONCE. The
                          ;; distribution and the tail are the same numbers:
                          ;; `latencies` collects them all and `last-obs`
                          ;; keeps the newest, so the settle branch has
                          ;; nothing left to subtract. Reading the tail here
                          ;; rather than at the settle poll also carries this
                          ;; observer's resolution rather than the poll's
                          ;; 4 ms — which in a shipped bundle is larger than
                          ;; the quantity itself.
                          ;;
                          ;; `offers` holds an entry from the moment a
                          ;; generation is offered until its FIRST sighting,
                          ;; so this branch runs exactly once per generation
                          ;; and every newest-generation sighting reaches it.
                          (when-let [t (get @offers g)]
                            (let [lat (- now t)]
                              (swap! latencies conj lat)
                              (swap! offers dissoc g)
                              (when (or (nil? @last-obs) (> g (nth @last-obs 0)))
                                (vreset! last-obs [g t now lat]))))))))]
      (.observe watcher container #js {:subtree true :childList true :characterData true})
      (js/Promise.
        (fn [resolve]
          (let [tid (volatile! nil)]
            (vreset!
              tid
              (js/setInterval
                (fn []
                  (if (>= (- (m/now-ms) start) duration-ms)
                    (do
                      (js/clearInterval @tid)
                      ;; Settle: poll until the last generation is on the
                      ;; page or a generous ceiling passes. The poll is the
                      ;; `settled?`/ceiling detector and ONLY that — this
                      ;; callback is already one period past the last offer,
                      ;; so a clock started here measures neither end of the
                      ;; tail. `t-offer` is that end; the observer is this one.
                      (let [t-poll-start (m/now-ms)
                            poll         (volatile! nil)]
                        (vreset!
                          poll
                          (js/setInterval
                            (fn []
                              (let [done? (= (str @last-gen) (cell-text container 0))
                                    over? (> (- (m/now-ms) t-poll-start) 2000)]
                                (when (or done? over?)
                                  (js/clearInterval @poll)
                                  (let [now      (m/now-ms)
                                        seen     @last-obs
                                        by-obs?  (boolean (and done? seen
                                                               (= (nth seen 0) @last-gen)))
                                        ;; The retained last-offer reading. It
                                        ;; anchors the gap AND is published, so
                                        ;; C3 and C4 gate the same instant.
                                        t0       @t-offer
                                        ;; LIFTED, not recomputed: when the
                                        ;; observer saw the last generation the
                                        ;; tail IS that generation's latency,
                                        ;; the same number in the distribution.
                                        ;; The `:poll` arm is the degraded
                                        ;; fallback — the ceiling expired, or
                                        ;; the page never showed the last
                                        ;; generation — and is labelled as such.
                                        t-end    (if by-obs? (nth seen 2) now)
                                        tail     (if by-obs?
                                                   (nth seen 3)
                                                   (when t0 (- now t0)))
                                        n        @offered
                                        gap      (when t0 (- t-poll-start t0))]
                                    (.disconnect watcher)
                                    (.observe observer container
                                              #js {:subtree true :childList true
                                                   :characterData true :attributes true})
                                    (resolve
                                      {:rate            rate
                                       :siblings        k
                                       :duration-ms     duration-ms
                                       :offered         n
                                       :expected        (js/Math.floor
                                                          (/ (* duration-ms rate) 1000.0))
                                       :applied         (applied)
                                       :mutations       @muts
                                       :peak-backlog    @backlog
                                       :tail-ms         tail
                                       :tail-source     (if by-obs? :observer :poll)
                                       :tail-offer-at-ms    t0
                                       :tail-observed-at-ms t-end
                                       :offer-to-poll-start-ms gap
                                       :predicted-gap-ms       (when (pos? n)
                                                                 (/ duration-ms (double n)))
                                       :poll-wait-ms    (- now t-poll-start)
                                       :tail-anchored?  (boolean
                                                          (and gap (>= gap (* 0.5 period))))
                                       :settled?        done?
                                       :outstanding     (count @offers)
                                       :latency-ms      (when (seq @latencies)
                                                          (m/summarise @latencies))})))))
                            4))))
                    (let [gen (next-gen!)]
                      (vreset! last-gen gen)
                      (swap! offered inc)
                      ;; ONE clock read, kept in two places: the per-generation
                      ;; latency map and `t-offer`. Re-reading the clock for
                      ;; the second would make the tail and the last
                      ;; generation's latency disagree for no reason.
                      (let [t (m/now-ms)]
                        (vreset! t-offer t)
                        (swap! offers assoc gen t))
                      (swap! backlog max (- @offered (applied)))
                      (rf/dispatch [:b10/moved k gen] {:frame frame-id}))))
                period))))))))

;; ---------------------------------------------------------------------------
;; C4 — the published tail IS the two retained readings
;; ---------------------------------------------------------------------------

(defn tail-identity-holds?
  "True when a `drive!` record's `:tail-ms` is exactly the difference of
  the two instants published beside it.

  This is the gate C3 could not be. `:tail-anchored?` is derived from
  `:offer-to-poll-start-ms` — the last offer against the SETTLE DECISION
  — and the tail runs from the last offer to the OBSERVER. Two of the
  three instants differ, so the third edition's `:tail-ms` could be
  re-anchored anywhere and C3 would not notice; the merged-PR audit on
  `rf2-drpa3.182.12` demonstrated exactly that, re-anchoring at settle
  time and finding the gap, the anchor and the nonnegative check all
  still green. This asserts the arithmetic itself.

  The comparison is EXACT and can afford to be. Both operands are
  `measure/now-ms` readings on one clock and the tail is their retained
  difference — a double subtraction reproduced, not a rounded chain of
  them, which is why the two instants are published raw (origin-relative,
  meaningful only as a difference) rather than rebased on the run start.
  Rebasing would introduce two roundings and put slack in a check whose
  whole value is that it has none. The interval a re-anchoring moves the
  tail by is 4 to 33 ms; there is no tolerance to trade away.

  C3 is deliberately left standing beside this rather than folded into
  it. This says the tail was computed from the retained offer reading;
  C3 says that reading is the immediately preceding interval. Escaping
  one lands on the other."
  [{:keys [tail-ms tail-offer-at-ms tail-observed-at-ms]}]
  (and (number? tail-ms)
       (number? tail-offer-at-ms)
       (number? tail-observed-at-ms)
       (== tail-ms (- tail-observed-at-ms tail-offer-at-ms))))

;; ===========================================================================
;; Publication
;; ===========================================================================

(defn record
  "A D021 result record, run past `prov/result` — which refuses one that
  omits any of the eight things D021 requires.

  The refusal is REPORTED rather than routed around. A browser suite has
  no revision unless its build was handed one, so the ordinary
  `:browser-test` lane cannot produce release evidence and says so in the
  record itself; the focused `bench:freehand-browser` build, whose
  `RF2_REVISION` closure-define is what the published run supplies, does.
  Both publish the same numbers; only one of them is release evidence,
  and a reader should not have to work out which."
  [benchmark doc fixture sampling distribution]
  (let [candidate
        {:benchmark    benchmark
     :doc          doc
     :revision     (prov/detect-revision)
     :build        (prov/detect-build)
     :host         (prov/detect-host)
     :fixture      fixture
     :sampling     sampling
     :baseline     {:kind      :self-vs-end-to-end
                    :reference {:arm  :host-only
                                :note (str "the DC-09 default — a behavior writing its own "
                                           "node's transform, with nothing crossing into "
                                           "app-db. Every semantic figure is quoted against "
                                           "the host clock's own cost in the same round.")}}
     :distribution distribution
         :status       :evidence}]
    (try
      (assoc (prov/result candidate) :d021 :release-evidence)
      (catch :default e
        (assoc candidate
               :d021    :not-release-evidence
               :because (ex-message e))))))

(defn publish! [tag rec] (h/publish! (str "B10 " tag) rec))

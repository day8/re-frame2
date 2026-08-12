(ns day8.re-frame2-xray.panels.hicasso-advisor
  "The cause-aware hot-view advisor (rf2-hic-037) — rank, classify,
  recommend, and REFUSE.

  Spec SN §10 names this the differentiating feature: *ranks
  time/frequency/read churn/fan-out, then classifies computation,
  topology, lowering, React, or layout pressure. It recommends native
  extraction only when it addresses the measured owner.*

  The last clause is the whole design, and taking it seriously produces a
  surprising deliverable: **from this evidence the advisor never
  recommends a native route.** Not once, not for the hottest boundary on
  the page. That is not a stub and it is not timidity — it is what the
  sentence means when the instruments are enumerated honestly.

  ## What can be measured here, and what cannot

  | Pressure class | Instrument | Available? |
  |---|---|---|
  | application computation | Spec 009 `:rf.sub/elapsed-ms` on the retained ring | **yes** |
  | read topology | the cell table's fan-out + the entry cache's read orders + the ring's recompute counts | **yes** |
  | Hiccup lowering | — | no |
  | React reconciliation / commit | — | no |
  | DOM layout / paint | — | no |

  The three unavailable rows are not an oversight to be closed later.
  Boundary self time was **killed as a decision**
  (`lanes/left-field-ideas.md` §Capability receipts): Chrome clamps its
  timer to a 0.1 ms grain while the quantity is single-digit microseconds,
  so a per-attempt interval reads either zero or one whole tick and *a
  ranking built on it orders noise* — and two clock reads per attempt
  inflate a boundary in proportion to its attempt COUNT rather than its
  true cost, so two boundaries close in true self time and far apart in
  attempt count invert. Commit, paint and attempt outcome are React's
  (`re-frame.hicasso.tool`'s `:host` projection, `:host-opaque` every
  time). Hicasso emits no `:rf.view/render` trace, and its User-Timing
  `:render` measures ride an independently-gated channel that is off by
  default and observer-first.

  So the advisor measures ONE of the five classes and derives a second.
  The other three it names, with the authority for each, and stops.

  ## Why that makes the refusal the product

  Rungs 3, 4 and 5 of the performance ladder
  (`lanes/hot-path-architecture.md`) — a direct `n/$` element, a named
  native island, a native screen — all address lowering, hooks, vendor
  behaviour or reconciliation. Every one of those is in the unmeasurable
  half of the table above. A tool that ranked boundaries by the one clock
  it has and then pointed at the native ladder would be recommending an
  expensive, semantics-changing, diagnostics-losing change on evidence
  that cannot speak to whether it helps — which is the single most
  expensive mistake this surface could make a developer make, and the one
  a *sorted list of durations* invites. [[recommend]] therefore treats the
  ladder as a table keyed on the measured OWNER, and no owner this door
  can produce selects a native rung.

  The table is real, not a stub: hand it `:lowering` and it answers rung 3
  with its steps. That arm is unreachable from [[classify]] today and its
  test says so out loud, because a refusal that came from an empty table
  would be indistinguishable from one that came from the evidence — and
  only the second is a finding.

  ## Pure, and CLJC on purpose

  Every function here is data → data, so the algebra runs under the JVM
  unit-test target beside the CLJS one. The live reads are
  `hicasso_reads.cljs`; the rendering is `hicasso.cljs`.

  Normative owner: `tools/xray/spec/028-Hicasso-Advisor.md`."
  (:require [clojure.string :as string]
            [day8.re-frame2-xray.panels.hicasso-helpers :as hh]))

;; ---------------------------------------------------------------------------
;; The advisor's own version pin
;; ---------------------------------------------------------------------------

(def advice-schema
  "The schema this namespace STAMPS on its own output.

  Deliberately not `re-frame.hicasso.evidence`'s: the advice is a
  derivation Xray performs over three producers' envelopes, and stamping
  the producer's schema on it would tell a reader that Hicasso vouched
  for a ranking Hicasso has never seen. Xray owns this shape and says so."
  :day8.re-frame2-xray.hicasso-advisor/v1)

(def advice-producer
  "Xray, because Xray is what produced it. See [[advice-schema]]."
  :re-frame2/xray)

(def timing-schema
  "The schema the [[sub-timing]] digest stamps.

  A second schema rather than a field on the advice, because the digest
  has a different PRODUCER (Spec 009's retained ring, not the Hicasso
  door) and therefore a different loss story: the ring is always a cap,
  and its `:rf.sub/elapsed-ms` tag is present on the reactive recompute
  path and absent on the pure `compute-sub` form. A reader who cannot
  see those two facts separately cannot tell a fast subscription from an
  untimed one."
  :day8.re-frame2-xray.hicasso-advisor.timing/v1)

(def timing-producer
  "Spec 009's per-frame retained ring, read through
  `re-frame.trace.tooling/trace-buffer`."
  :re-frame/trace)

;; ---------------------------------------------------------------------------
;; Thresholds — three numbers, each with its reason attached
;; ---------------------------------------------------------------------------

(def computation-floor-ms
  "Below this many milliseconds of attributable subscription time across
  the WHOLE retained window, computation is not the owner of anything a
  developer noticed.

  1.0 ms rather than a smaller number because of the clock, not because
  of a taste about what is slow: Chrome clamps `performance.now` to a
  0.1 ms grain, so a sum under 1.0 ms is fewer than ten ticks and its
  ordering against another such sum is noise. Naming an owner from
  a quantity that cannot be ordered is the failure this floor exists to
  prevent."
  1.0)

(def dominance
  "The share of a boundary's attributable time ONE read must hold before
  that read is named the owner.

  Below it the time is spread across the read set, and the remedy is the
  set rather than any member of it — which is a topology finding, not a
  computation one. 0.6 is a majority with room, chosen so a two-read
  boundary at 55/45 is not reported as having a hot subscription."
  0.6)

(def repeat-floor
  "The recompute count at which a boundary's re-run is a PATTERN rather
  than an occurrence.

  Two, deliberately the smallest such number: the advisor is ranking
  within a bounded window, so demanding a larger count would silently
  require a bigger ring to see a real repeat. One run in the window is a
  thing that happened; two is a thing that happens."
  2)

;; ---------------------------------------------------------------------------
;; The timing digest — Spec 009's ring, folded per read edge
;; ---------------------------------------------------------------------------

;; The recompute predicate is `hh/sub-recompute?` and lives in the shared
;; algebra rather than here, because `hicasso-causal`'s link-2 roster asks
;; the same question of the same events and the two answers must be one
;; answer (rf2-hic-037, audit #8027). `:rf.sub/skip` is a memo hit — the
;; cell answered without running, so it is not work and must not be summed
;; into a duration. It is counted separately because it is the single most
;; informative topology signal there is: a read whose considerations are
;; mostly skips is well-placed, and one that runs every time is not.

(defn- fold-bundle
  "Fold ONE event bundle's sub events into `acc`, keyed `[frame-id sub-id]`.

  `:elapsed-ms` accumulates only over runs that CARRIED a duration, and
  `:untimed-runs` counts the rest. Spec 009 puts `:rf.sub/elapsed-ms` on
  the reactive recompute path and not on the pure `compute-sub` form, so
  a fold that treated a missing tag as zero would report a subscription
  it never timed as instantaneous — the loudest possible version of the
  `unknown is never zero` mistake, because the reader would then rank
  it last."
  [acc frame-id bundle]
  (let [did (:dispatch-id bundle)]
    (reduce
      (fn [m ev]
        (let [sid (get-in ev [:tags :rf.sub/id])
              k   [frame-id sid]]
          (cond
            ;; A sub event with no registration id is a real run that
            ;; joins to no subscription. It is counted at the window level
            ;; (below) rather than dropped, because dropping it would let
            ;; an untagged stream read as an idle one.
            (nil? sid)      (update m ::unnamed-runs (fnil inc 0))

            (hh/sub-recompute? ev)
            (let [ms (get-in ev [:tags :rf.sub/elapsed-ms])]
              (-> m
                  (update-in [k :runs] (fnil inc 0))
                  (update-in [k :dispatch-ids] (fnil conj #{}) did)
                  (cond->
                    (number? ms) (-> (update-in [k :elapsed-ms] (fnil + 0.0) ms)
                                     (update-in [k :timed-runs] (fnil inc 0))
                                     (update-in [k :max-ms] (fnil max 0.0) ms))
                    (not (number? ms)) (update-in [k :untimed-runs] (fnil inc 0)))))

            (hh/sub-skip? ev)
            (update-in m [k :memo-hits] (fnil inc 0))

            :else m)))
      acc
      (:subs bundle))))

(defn sub-timing
  "Spec 009's retained window, folded into per-read-edge work.

      (sub-timing {:app/main [bundle bundle …]})
      ;; => {:schema … :producer :re-frame/trace :read :sub-timing
      ;;     :scope {:frames [:app/main] :retained-runs 12}
      ;;     :basis :observation :complete? false
      ;;     :loss {:reason :cap :dropped :unknown}
      ;;     :by-read {[:app/main :todo] {:runs 4 :timed-runs 4 :untimed-runs 0
      ;;                                  :elapsed-ms 6.4 :max-ms 2.1
      ;;                                  :memo-hits 9 :dispatch-ids #{41 42 43 44}}}}

  `windows` is `{frame-id [event-bundle …]}` exactly as
  `re-frame.trace.tooling/trace-buffer` answers it, one entry per frame.

  **The key is `[frame-id sub-id]` and not the sub-id**, for the same
  reason `explain-render` scopes its leads that way: two frames are two
  applications, and summing frame B's clock into frame A's ranking would
  make an idle boundary look hot because something unrelated was busy
  next door.

  **This read is NEVER complete.** A ring of size N cannot say what fell
  off it, so every number here is a floor and the loss says `:cap`
  unconditionally — including when the digest is full, because a full
  window is the case where the most has been dropped."
  [windows]
  (let [frames    (vec (sort-by pr-str (keys windows)))
        folded    (reduce (fn [acc fid]
                            (reduce (fn [a b] (fold-bundle a fid b)) acc (get windows fid)))
                          {}
                          frames)
        unnamed   (get folded ::unnamed-runs 0)
        by-read   (dissoc folded ::unnamed-runs)
        retained  (reduce + 0 (map (comp count val) windows))]
    {:schema        timing-schema
     :producer      timing-producer
     :read          :sub-timing
     :scope         {:frames frames :retained-runs retained}
     :basis         :observation
     :complete?     false
     :loss          {:reason :cap :dropped hh/unknown}
     :by-read       by-read
     ;; A run whose `:rf.sub/id` tag is absent happened and joins to no
     ;; subscription — the textbook `:uncorrelated` state, surfaced rather
     ;; than folded away so an untagged stream cannot read as an idle one.
     :unnamed-runs  unnamed
     :unnamed-loss  (when (pos? unnamed)
                      {:reason :uncorrelated :dropped unnamed})}))

;; ---------------------------------------------------------------------------
;; The join — a boundary's read edges, priced
;; ---------------------------------------------------------------------------

(defn- read-edges
  "The `[frame-id sub-id]` pairs a producer boundary row reads.

  Taken from `:reads` (which carries `:frame-id` and `:sub-id` per edge)
  rather than from the projected `:boundary :key`, because the key's third
  element is a PROJECTED query and the timing digest is keyed on the
  registration id — the one spelling redaction cannot take."
  [row]
  (into [] (map (juxt :frame-id :sub-id)) (:reads row)))

(defn- fan-out-index
  "`{[frame-id sub-id] fan-out}` from the attribution envelope.

  Summed rather than maxed across cells sharing a registration id,
  because two parameterizations of one sub are two cells and a boundary
  reading both is exposed to both reader populations."
  [attribution]
  (if-not (hh/supported? attribution)
    {}
    (reduce (fn [m edge]
              (update m [(:frame-id edge) (:sub-id edge)] (fnil + 0) (or (:fan-out edge) 0)))
            {}
            (:edges attribution))))

(defn- attributable
  "This boundary's read edges with their measured work attached, plus the
  totals the axes are read off.

  `:ms` is `hh/unknown` — never 0 — when no edge carried a timed run. A
  boundary whose subscriptions were never timed and a boundary whose
  subscriptions cost nothing are different facts with different remedies,
  and zero is the spelling that hides the difference."
  [timing edges]
  (let [priced (mapv (fn [[fid sid :as k]]
                       (let [t (get (:by-read timing) k)]
                         {:frame-id     fid
                          :sub-id       sid
                          :runs         (get t :runs 0)
                          :timed-runs   (get t :timed-runs 0)
                          :untimed-runs (get t :untimed-runs 0)
                          :memo-hits    (get t :memo-hits 0)
                          :elapsed-ms   (get t :elapsed-ms)
                          :max-ms       (get t :max-ms)
                          :dispatch-ids (get t :dispatch-ids #{})}))
                     edges)
        timed  (filterv #(pos? (:timed-runs %)) priced)
        total  (reduce + 0.0 (map #(or (:elapsed-ms %) 0.0) timed))]
    {:edges       priced
     :ms          (if (seq timed) total hh/unknown)
     :timed?      (boolean (seq timed))
     :untimed-runs-total (reduce + 0 (map :untimed-runs priced))
     :runs        (reduce + 0 (map :runs priced))
     :memo-hits   (reduce + 0 (map :memo-hits priced))
     :dispatches  (reduce into #{} (map :dispatch-ids priced))
     :hottest     (when (seq timed)
                    (apply max-key #(or (:elapsed-ms %) 0.0) timed))}))

;; ---------------------------------------------------------------------------
;; The four axes
;; ---------------------------------------------------------------------------

(defn- axes
  "The four ranking axes for one boundary, each carrying its own basis.

  They are four axes and not one score. A composite would need weights
  between milliseconds, dispatch counts, read orders and reader slots —
  four incommensurable units — and the weights would be the ranking,
  invented here and unfalsifiable. Each axis is reported in its own unit
  with its own loss, and [[order-axis]] picks which one the roster is
  sorted on, out loud."
  [row att fan-out]
  {:time       {:ms    (:ms att)
                :basis :observation
                ;; The window is always a cap, so a timed row's total is a
                ;; FLOOR. An untimed row's loss is `:uncorrelated` instead:
                ;; the recomputes are real and observed, and it is the
                ;; duration that joins to nothing — Spec 009 puts
                ;; `:rf.sub/elapsed-ms` on the reactive recompute path and
                ;; not on the pure `compute-sub` form. Reporting that as a
                ;; cap would send the reader to the retention knob, which
                ;; is not the remedy.
                :loss  (if (:timed? att)
                         {:reason :cap :dropped hh/unknown}
                         {:reason :uncorrelated :dropped (:untimed-runs-total att)})
                :says  "subscription recompute time attributable to this boundary's reads, inside the retained window"}
   :frequency  {:runs        (:runs att)
                :dispatches  (count (:dispatches att))
                :memo-hits   (:memo-hits att)
                :basis       :observation
                :loss        {:reason :cap :dropped hh/unknown}
                :says        "recomputes of this boundary's reads in the retained window, and the memo hits beside them"}
   :read-churn {:reads       (count (:reads row))
                :read-orders (:read-orders row)
                ;; `:read-orders` > 1 means the entry cache holds more than
                ;; one ORDER for this edge set — an oscillating read set,
                ;; which `lanes/hot-path-architecture.md` §Read-topology
                ;; names as suspect because whole-set reconciliation can
                ;; become proportional to the current read count.
                :oscillating? (and (number? (:read-orders row))
                                   (> (:read-orders row) 1))
                :basis        :observation
                :says         "how many reads this boundary holds, and how many distinct read ORDERS folded into it"}
   :fan-out    {:total     fan-out
                :instances (:instances row)
                :basis     :observation
                :says      "reader slots on the cells this boundary reads — how many boundaries move with it"}})

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defn format-ms
  "A duration a reader can compare, at the resolution the clock actually
  has.

  Two decimals, because the timer grain is 0.1 ms and a third would print
  precision that is not there. Rounded with arithmetic rather than
  `Math/round` so the one expression is the same on both platforms —
  durations are non-negative, so truncation after the half-add is exact."
  [ms]
  (if (number? ms)
    (str (/ (int (+ 0.5 (* 100.0 (double ms)))) 100.0) " ms")
    "unknown"))

;; ---------------------------------------------------------------------------
;; Classification — one measured class, one derived, three named and refused
;; ---------------------------------------------------------------------------

(def unmeasured-classes
  "The three pressure classes this door cannot weigh, and who can.

  Named as data so the panel can print them and a test can assert the
  advisor never claims one. Each `:authority` is the instrument Spec SN
  §10 and `lanes/testing-xray.md` already point at; none of them is Xray."
  [{:class     :lowering
    :label     "Hiccup lowering"
    :authority "the User-Timing `:render` measures Hicasso emits under `re-frame.performance/enabled?` — an independently gated, observer-first channel that is off by default"
    :why       "Hicasso publishes no per-boundary lowering clock. Boundary self time was killed as a decision, not deferred: the 0.1 ms timer grain is coarser than the quantity, so a ranking built on it orders noise."}
   {:class     :react
    :label     "React reconciliation and commit"
    :authority "the React DevTools Profiler"
    :why       "Whether a notified boundary re-ran, retried, was abandoned, was bailed out by its memo comparator, or committed is decided above this runtime. The Hicasso door states it `:host-opaque` on every envelope."}
   {:class     :layout
    :label     "DOM layout and paint"
    :authority "the browser's own performance tools"
    :why       "Nothing in a subscription table or a trace ring observes layout. A timing coincidence here would be a guess wearing a number."}])

(defn classify
  "What owns this boundary's pressure — as far as the instruments reach.

  Five outcomes, and the difference between the last three is the point:

  | `:owner` | `:basis` | `:observed` | Means |
  |---|---|---|---|
  | `:computation` | `:observation` | `:recomputes` | one read's measured recompute time dominates, above the clock's floor |
  | `:read-topology` | `:derivation` | `:recomputes` / `:memo-hits-only` | the read set is the problem: it oscillates, or it re-runs repeatedly for little measured work |
  | `:unattributed` | `:host-opaque` | `:recomputes` | the window WAS searched, recomputes happened, and the measured half does not explain them — so the owner is one of the three unmeasured classes, and this door cannot say which |
  | `:unattributed` | `:host-opaque` | `:memo-hits-only` | the window retained this boundary's reads being CONSIDERED and the memo answered every one. Nothing recomputed, so computation owns none of it |
  | `:unattributed` | `:cap` | `:nothing` | the window retained no activity for this boundary at all; this is an absence of evidence, not evidence of absence |

  The last three are the trio a naive advisor collapses, and `:observed`
  is what keeps them apart in DATA rather than only in prose. `:cap` says
  *raise the retention knob and look again* — free. The two
  `:host-opaque` rows say *the answer is real and lives in another tool*
  — a change of instrument.

  **The `:memo-hits-only` row is the one that was wrong** (rf2-hic-037,
  audit #8027). `searched?` was derived from recompute runs alone, so a
  window holding nothing but tagged `:rf.sub/skip` events fell through to
  `:cap` and told the reader to enlarge a window that had already
  retained the answer — sending them to fix the instrument instead of the
  code. A skip is not a recompute and is still not nothing: Spec 009
  emits one only when the cell was CONSIDERED, so a retained skip is
  positive evidence, and the remedy has to be the one that follows from
  it. It is classified as observed activity WITHOUT reclassifying the
  skip as a run — `:runs` stays 0 and the memo hits are counted on their
  own axis, because making the arithmetic work by promoting a skip would
  be the same lie one layer down."
  [{:keys [time frequency read-churn]} att]
  (let [ms        (:ms time)
        timed?    (:timed? att)
        hottest   (:hottest att)
        hot-ms    (or (:elapsed-ms hottest) 0.0)
        memo-hits (:memo-hits frequency 0)
        ;; SEARCHED is about recomputes and CONSIDERED is about activity of
        ;; any kind. They are two questions and were one predicate.
        searched?  (pos? (:runs frequency))
        considered? (or searched? (pos? memo-hits))
        observed   (cond searched?   :recomputes
                         considered? :memo-hits-only
                         :else       :nothing)]
    (cond
      ;; MEASURED. One read holds the majority of a total the clock can
      ;; actually order.
      (and timed?
           (number? ms)
           (>= ms computation-floor-ms)
           (>= (/ hot-ms ms) dominance))
      {:owner :computation
       :basis :observation
       :observed observed
       :loss  nil
       :read  (select-keys hottest [:frame-id :sub-id :elapsed-ms :max-ms :runs])
       :says  (str "measured: " (hh/format-id (:sub-id hottest))
                   " accounts for " (int (* 100 (/ hot-ms ms))) "% of "
                   (format-ms ms) " of attributable subscription time across "
                   (:runs frequency) " recompute"
                   (when (not= 1 (:runs frequency)) "s") ".")}

      ;; DERIVED. An oscillating read set is a topology fact on its own,
      ;; independent of any clock — the entry cache holds more than one
      ;; read ORDER for this boundary.
      (:oscillating? read-churn)
      {:owner :read-topology
       :basis :derivation
       :observed observed
       :loss  nil
       :says  (str "the entry cache holds " (:read-orders read-churn)
                   " distinct read orders for this boundary — an oscillating "
                   "read set, whose whole-set reconciliation can become "
                   "proportional to the current read count.")}

      ;; DERIVED. It re-runs repeatedly and the measured work does not
      ;; account for it, so the read set is bringing it back, not the
      ;; arithmetic inside it.
      (and searched?
           (>= (:runs frequency) repeat-floor)
           timed?
           (number? ms)
           (< ms computation-floor-ms))
      {:owner :read-topology
       :basis :derivation
       :observed observed
       :loss  nil
       :says  (str "recomputed " (:runs frequency) " times for "
                   (format-ms ms) " of measured work — under the "
                   (format-ms computation-floor-ms) " floor the clock can "
                   "order, so the reads are bringing it back rather than the "
                   "computation inside them.")}

      ;; The window was searched and the measured half does not explain it.
      searched?
      {:owner      :unattributed
       :basis      :host-opaque
       :observed   observed
       :loss       {:reason :host-opaque :dropped hh/unknown}
       :candidates (mapv :class unmeasured-classes)
       :says       (str "the window was searched and the measured half does not "
                        "account for this boundary: "
                        (if (number? ms) (format-ms ms) "no timed recompute")
                        " across " (:runs frequency) " recompute"
                        (when (not= 1 (:runs frequency)) "s")
                        ". The owner is lowering, React or layout — and this "
                        "door observes none of the three.")}

      ;; OBSERVED, and what was observed is that nothing recomputed. The
      ;; window RETAINED this boundary's reads being considered, and the
      ;; memo answered every time — which is a finding about the read
      ;; topology, not a gap in the window. Routing this to the `:cap` arm
      ;; below told the reader to enlarge a window that had already held
      ;; the answer (audit #8027).
      considered?
      {:owner      :unattributed
       :basis      :host-opaque
       :observed   observed
       :loss       {:reason :host-opaque :dropped hh/unknown}
       :candidates (mapv :class unmeasured-classes)
       :says       (str "the retained window holds " memo-hits " memo hit"
                        (when (not= 1 memo-hits) "s")
                        " for this boundary's reads and no recompute at all: "
                        "the cells were considered and answered without "
                        "running. Evidence was retained, so this is not a "
                        "capped window — and subscription computation owns "
                        "none of this boundary's cost, because none of it ran. "
                        "If the boundary is still slow the owner is lowering, "
                        "React or layout, and this door observes none of the "
                        "three.")}

      ;; Nothing at all was retained for this boundary — neither a
      ;; recompute nor a memo hit — so nothing was searched.
      :else
      {:owner      :unattributed
       :basis      :cap
       :observed   observed
       :loss       {:reason :cap :dropped hh/unknown}
       :candidates (mapv :class unmeasured-classes)
       :says       (str "nothing in the retained window touched this "
                        "boundary's reads — no recompute and no memo hit — so "
                        "no search happened. This is an absence of evidence — "
                        "raise `:rf.trace/events-retained` and reproduce the "
                        "interaction.")})))

;; ---------------------------------------------------------------------------
;; The route ladder — a table keyed on the OWNER, and the refusal it produces
;; ---------------------------------------------------------------------------

(def working-loop
  "`lanes/hot-path-architecture.md` §Xray-guided workflow, verbatim in
  substance and numbered as it is there.

  Kept as data so a route can attach the steps that actually apply to it
  rather than printing the whole loop at a reader who needs three of it."
  {1 "Name a slow interaction and reproduce it with a stable script."
   2 "Correlate event, changed reads, invalidated boundaries, body work, commit, and paint."
   3 "Tune read and boundary topology first."
   4 "If codec work dominates, compare direct React output in the same boundary."
   5 "If hooks, reconciliation, vendor behavior, or high-rate local work dominate, isolate a named native component and choose Hicasso-native, UIx, or raw React according to its needs."
   6 "Run behavioral parity, focus/selection, frame routing, SSR/hydration, cleanup, and performance scripts."
   7 "Keep the escape only if it clears a declared budget."})

(def ladder
  "The performance ladder, keyed on the OWNER each rung addresses.

  This is the whole mechanism behind *native extraction only when it
  addresses the measured owner*: a route is selected by looking up the
  owner, never by how hot the boundary is. A boundary can be the hottest
  on the page and select rung 2, because rung 2 is what its owner
  answers to.

  `:owner` keys `:lowering`, `:react-work` and `:react-shaped` are
  **unreachable from [[classify]]** — this door measures none of them.
  They are here because the refusal below has to be a statement about the
  EVIDENCE, and a refusal emitted by a table with no native arm would be
  a statement about the table. `hicasso-advisor-cljs-test`'s non-vacuity
  row drives them directly."
  {:computation
   {:route    :narrow-the-subscription
    :rung     nil
    :native?  false
    :label    "Narrow or memoize the subscription"
    :says     (str "The measured owner is the subscription body, which runs "
                   "BELOW the view substrate. Every native route leaves it "
                   "exactly where it is — extracting the view would move the "
                   "markup and keep the cost.")
    :steps    [1 3 6 7]}

   :read-topology
   {:route    :tune-topology
    :rung     2
    :native?  false
    :label    "Tune topology without changing language"
    :says     (str "Place boundaries around independently changing or expensive "
                   "regions; stabilize keys and props; choose fine, coarse, "
                   "chunked or visible-window subscriptions to match the "
                   "workload; virtualize genuinely large collections. Do not "
                   "split per element mechanically — each boundary retains a "
                   "React fiber, a memo wrapper and a Hicasso shell.")
    :steps    [1 3 6 7]}

   :lowering
   {:route    :direct-element
    :rung     3
    :native?  true
    :label    "Return a direct React element (`n/$`)"
    :says     (str "The narrowest useful escape for a measured codec/markup "
                   "hotspot: the same frame, subscription shell, props ABI and "
                   "component identity, skipping Hiccup lowering for that "
                   "boundary. Intent lowering, controlled-field normalization "
                   "and key diagnostics do not apply inside the returned "
                   "subtree.")
    :steps    [1 4 6 7]}

   :react-work
   {:route    :native-island
    :rung     4
    :native?  true
    :label    "Isolate a named native React island"
    :says     (str "For a virtualizer, editor, animation surface, canvas/WebGL "
                   "coordinator, retained vendor widget or hook-intensive hot "
                   "collection. `n/defcomponent` with `n/use-frame` and "
                   "`n/use-sub` is the default self-contained route; UIx is an "
                   "optional mature one; JavaScript enters through the host "
                   "bridge. Same React root, same frame contract.")
    :steps    [1 5 6 7]}

   :react-shaped
   {:route    :native-screen
    :rung     5
    :native?  true
    :label    "Implement the screen natively"
    :says     (str "Only for an intrinsically React-first surface. A local "
                   "view-implementation choice under the single installed "
                   "adapter, root and frame contract — not another adapter or "
                   "Hicasso mode.")
    :steps    [1 5 6 7]}})

(defn- measure-first
  "The advice for an owner nothing here measured: what to reach for, in
  which order, and why this tool is not it.

  This is the shape a *sorted list of durations* cannot produce, and the
  reason the advisor exists. It is a REFUSAL with a next step, not a
  shrug: the three candidates are named, each with the instrument that
  settles it, and the ladder stays shut until one of them comes back
  with an answer."
  [classification]
  {:route    :measure-first
   :rung     nil
   :native?  false
   :label    (if (= :cap (:basis classification))
               "Widen the window and reproduce"
               "Measure the unattributed half elsewhere")
   :says     (if (= :cap (:basis classification))
               (str "Nothing was retained for this boundary, so no ranking here "
                    "is about it yet. Raise `:rf.trace/events-retained`, "
                    "reproduce the interaction, and read this tab again.")
               (str "The measured half does not own this boundary, and the "
                    "three classes that might are not observable from a "
                    "subscription table or a trace ring. Recommending a native "
                    "route from this evidence would be guessing with a number "
                    "attached."))
   :refusal  {:reason      (:basis classification)
              :refused     [:direct-element :native-island :native-screen]
              :says        (str "The native ladder addresses lowering, hooks, "
                                "vendor behaviour and reconciliation. This door "
                                "observes none of them, so it cannot know "
                                "whether the change it would be recommending "
                                "helps — and a native extraction that does not "
                                "help still costs intent lowering, "
                                "controlled-field normalization, key "
                                "diagnostics and structural inspection inside "
                                "the extracted subtree.")
              :next        (mapv #(select-keys % [:class :label :authority :why])
                                 unmeasured-classes)}
   :steps    (if (= :cap (:basis classification)) [1] [1 2])})

(defn recommend
  "The smallest credible route for a classified boundary — or the refusal.

  Looks the OWNER up in [[ladder]] and returns nothing else. There is no
  path from a ranking position to a route, which is the property that
  makes *native extraction only when it addresses the measured owner*
  mechanical rather than aspirational: the hottest boundary on the page
  and the coldest one with the same owner get the same rung."
  [classification]
  (let [entry (get ladder (:owner classification))]
    (if (and entry (not= :unattributed (:owner classification)))
      (assoc entry
             :addresses (:owner classification)
             :because   (:says classification)
             :working-loop (mapv working-loop (:steps entry)))
      (let [m (measure-first classification)]
        (assoc m
               :addresses (:owner classification)
               :because   (:says classification)
               :working-loop (mapv working-loop (:steps m)))))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(defn- row-for
  [timing fan-idx row]
  (let [edges   (read-edges row)
        att     (attributable timing edges)
        fan     (reduce + 0 (map #(get fan-idx % 0) edges))
        ax      (axes row att fan)
        cls     (classify ax att)]
    {:boundary   (:boundary row)
     :label      (hh/boundary-label (:boundary row))
     :slug       (hh/boundary-slug (:boundary row))
     :key-str    (pr-str (:key (:boundary row)))
     :frame      (:frame row)
     :instances  (:instances row)
     :axes       ax
     :attributable att
     :class      cls
     :advice     (recommend cls)}))

(defn order-axis
  "Which axis the roster is sorted on, and why THAT one.

  `:time` when any row has a timed recompute, because a measured
  millisecond is the only axis that is about cost rather than about
  shape. Otherwise `:frequency`, and the roster SAYS so — a list ordered
  by a fallback axis while implying it was ordered by time is the quiet
  version of the mistake this whole namespace is arranged against."
  [rows]
  (if (some #(get-in % [:attributable :timed?]) rows)
    {:axis :time
     :says "ordered by attributable subscription time"}
    {:axis :frequency
     :says (str "ordered by recompute count — NOT by time. No read edge in the "
                "retained window carried a `:rf.sub/elapsed-ms` tag, so there "
                "is no measured duration to order on.")}))

(defn- sort-key
  [axis row]
  (case axis
    :time      (- (if (number? (get-in row [:axes :time :ms]))
                    (get-in row [:axes :time :ms])
                    -1.0))
    :frequency (- (get-in row [:axes :frequency :runs] 0))
    0))

(defn advise
  "Rank, classify and recommend over one turn's evidence.

      (advise {:mounted-boundaries E1 :read-attribution E2 :explain-render E4}
              (sub-timing windows))

  Returns the advisor's own envelope, carrying the same seven axes the
  Hicasso door's do — under [[advice-schema]], because Xray derived it and
  Hicasso did not.

  `:complete?` is false unconditionally and the loss is `:host-opaque`: the
  roster covers every mounted boundary, but the QUESTION is *where is the
  pressure*, and three of its five answers are outside this door. A
  `:complete? true` here would be the schema's own fail-open shape,
  written by the one function best placed to know better."
  [envelopes timing]
  (let [mounted (:mounted-boundaries envelopes)]
    (if-not (hh/supported? mounted)
      {:schema advice-schema :producer advice-producer :read :hot-view-advice
       :scope :mounted-boundaries :basis :observation :complete? false
       :loss {:reason :host-opaque :dropped hh/unknown}
       :rows [] :ordered-by nil :timing timing :unmeasured unmeasured-classes}
      (let [fan-idx (fan-out-index (:read-attribution envelopes))
            rows    (mapv #(row-for timing fan-idx %) (:boundaries mounted))
            {:keys [axis] :as ordering} (order-axis rows)
            ordered (vec (sort-by (juxt #(sort-key axis %) :key-str) rows))]
        {:schema     advice-schema
         :producer   advice-producer
         :read       :hot-view-advice
         :scope      :mounted-boundaries
         :basis      :derivation
         :complete?  false
         :loss       {:reason :host-opaque :dropped hh/unknown}
         :rows       (into [] (map-indexed (fn [i r] (assoc r :rank (inc i)))) ordered)
         :ordered-by ordering
         :timing     timing
         :unmeasured unmeasured-classes}))))

(defn advice-summary
  "One muted line stating what the roster is and is not.

  Rendered on every advice, including the ones with a confident top row,
  for the reason `hicasso-helpers/read-summary` states: a reader who only
  sees a qualification when it is bad learns to read its absence as good
  news."
  [advice]
  (when advice
    (string/join " · "
                 (remove nil?
                         [(str (count (:rows advice)) " boundar"
                               (if (= 1 (count (:rows advice))) "y" "ies"))
                          (:says (:ordered-by advice))
                          (str "window "
                               (get-in advice [:timing :scope :retained-runs] 0)
                               " retained run"
                               (when (not= 1 (get-in advice [:timing :scope :retained-runs] 0)) "s"))
                          "lowering, React and layout are not measured here"]))))

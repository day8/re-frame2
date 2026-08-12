(ns re-frame.hicasso.pull-reads-spike-cljs-test
  "PULL-SHAPED READS — the three-arm comparator (rf2-hic-058).

  The deciding rule is [specification §11]'s portfolio row — *must beat
  hand-coarse ergonomics/cost without a per-leaf ledger or
  independent-churn regression* — and its detailed form in the left-field
  lane: *compare one declarative pull subscription with fine reads and a
  hand-written coarse view-model on the same form/list witness under
  correlated and independent churn.*

  **The criteria were pre-registered before anything was measured**, in
  `docs/design/hicasso/product/pull-shaped-reads-verdict.md`, committed
  on this branch at `dd41c7ad6c` with its verdict section deliberately
  empty. This file is the measurement; that page is the record. A
  comparator whose criteria are chosen after the numbers is a
  rationalisation rather than a spike, so the ordering is a fact in the
  history rather than a claim in a docstring.

  ## Three arms, and the assertion that all three actually ran

  | arm | shape |
  |---|---|
  | `:fine/*`   | one layer-1 subscription per leaf, one layer-2 consumer per view unit |
  | `:coarse/*` | one hand-written layer-1 view-model, one layer-2 consumer |
  | `:pull/*`   | one layer-1 subscription interpreting a declarative query, one layer-2 consumer |

  A three-way comparator's most likely defect is that one arm is
  structurally absent while the other two agree, and the file goes on
  claiming three. [[all-three-arms-run-and-agree]] is asserted first and
  is the precondition for every figure below it: each arm must have
  produced a value, the values must agree leaf for leaf, and each arm's
  own instrument must have moved. Two arms agreeing is not three arms
  running.

  ## Counts, not clocks, and the difference is not a formality

  Every figure here is a **count**. Counts are deterministic and carry no
  hardware profile, which is what lets them sit beside `budgets.md` §3's
  deterministic rows rather than §4's distributional ones — and it is
  also their limit, stated here rather than discovered later: **they say
  what work each arm does, never how long that work takes.** No wall
  clock is taken, because a duration cannot be attributed on a machine
  that may be carrying another worker's compile; that is the standard
  `rf2-hic-033` set and `rf2-5yn9` inherited. Retention is read as live
  subscription-cache ENTRIES, a count, and never as retained bytes — the
  `D9`/`S5` distinction governs, and a comparator that conflated them
  would be claiming a heap result it did not take.

  ## The substrate, and why it is `plain-atom`

  The sibling hicasso suites install the UIx adapter, because a
  *commit* assertion needs a reactive substrate to notify at all. This
  file asserts about RECOMPUTATION, not notification, and the deref-memo
  contract it reads — a layer-1 body short-circuits on an `=` db, a
  layer-2 body short-circuits on an `=` upstream value — is the one
  pinned under `plain-atom` by `re-frame.sub-memo-layer-1-test` and
  `re-frame.sub-memo-layer-n-1-test`. Measuring against the substrate
  whose contract is already pinned is what makes these counts readable
  as re-frame semantics rather than as adapter behaviour.
  [[the-comparator-is-adapter-portable]] then re-runs the whole
  comparator under the UIx adapter and asserts the same figures, which
  is the bead's *adapter-portable* clause measured rather than asserted.

  ## What this file cannot reach, and says so

  The commit half. The package's lane is Node; `react-dom/server` runs
  bodies and never commits, so no arm here mounts a boundary, and the
  question *how many React bodies re-ran* has no witness in this lane —
  **for all three arms equally**. That is a declared exclusion, not a
  silent one, and it does not weaken the comparison: the invalidation
  unit a boundary would re-run on is the subscription consumer counted
  here, one level below React.

  [specification §11]: docs/design/hicasso/product/specification.md"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.subs.tooling :as subs-tooling]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The counting seam
;; ---------------------------------------------------------------------------
;;
;; One helper, used by all three arms, so `G5` counts the same unit
;; everywhere: a lookup is one `get` step against an app-db-derived
;; value. The pull arm additionally counts query-INTERPRETATION steps
;; through [[st!]], and they are reported separately as `G5b` rather
;; than blended into `G5` — a single "cost" number would let the pull
;; arm's interpretation overhead hide inside the lookups it shares with
;; the hand-written one.

(defn- lk
  "One app-db path step, tallied into `!n`."
  [!n coll k]
  (swap! !n inc)
  (get coll k))

(defn- st!
  "One query-interpretation step, tallied into `!n`."
  [!n]
  (swap! !n inc)
  nil)

(def ^:private tally-keys
  [:fine :coarse :pull])

(defn- fresh-tallies
  "Every counter this file reads, minted fresh. A map rather than a pile
  of `defonce` atoms, so a population's counters cannot survive into the
  next population's reading."
  []
  {:lookups   (zipmap tally-keys (repeatedly #(atom 0)))
   :steps     (zipmap tally-keys (repeatedly #(atom 0)))
   :l1-runs   (zipmap tally-keys (repeatedly #(atom 0)))
   ;; Each consumer, when its body runs, conj's the value it delivered.
   ;; `G1` is the count of that log and `G2` its leaf total, so both
   ;; figures come off one recording and cannot disagree.
   :delivered (zipmap tally-keys (repeatedly #(atom [])))})

(defonce ^:private !live
  ;; The tallies the registered handlers below reach for. Registration
  ;; happens once at ns load; a population swaps the tallies in before it
  ;; drives anything.
  (atom (fresh-tallies)))

(defn- !lookups [arm] (get-in @!live [:lookups arm]))
(defn- !steps   [arm] (get-in @!live [:steps arm]))
(defn- !l1      [arm] (get-in @!live [:l1-runs arm]))
(defn- !out     [arm] (get-in @!live [:delivered arm]))

(defn- reset-tallies! [] (reset! !live (fresh-tallies)))

(defn- leaf-count
  "How many scalar values a delivered value carries — the size of what a
  notified consumer must re-consume."
  [v]
  (cond
    (map? v)        (reduce + 0 (map leaf-count (vals v)))
    (sequential? v) (reduce + 0 (map leaf-count v))
    :else           1))

;; ---------------------------------------------------------------------------
;; The pull resolver — a pure function, and the kill condition's whole story
;; ---------------------------------------------------------------------------

(defn- pull
  "Resolve `query` against `node`.

  A query is a vector whose elements are either a keyword (a leaf) or a
  single-entry map `{k subquery}` (a join). A join whose value is
  sequential resolves the subquery against each element.

  **It retains nothing.** There is no atom, no cache, no memo and no
  per-leaf dependency structure — the only state that outlives a call is
  the two counters the comparator reads, which are the instrument and
  not the resolver. That is the bead's kill condition satisfied by
  construction, and [[the-pull-arm-holds-no-per-leaf-ledger]] witnesses
  it from the outside as well, where a ledger would have to show."
  [!lu !sp node query]
  (reduce
    (fn [acc q]
      (st! !sp)
      (cond
        (keyword? q)
        (assoc acc q (lk !lu node q))

        (map? q)
        (let [[k subquery] (first q)
              child        (lk !lu node k)]
          (assoc acc k (if (sequential? child)
                         (mapv #(pull !lu !sp % subquery) child)
                         (pull !lu !sp child subquery))))

        :else
        (throw (ex-info "unsupported pull node" {:node q}))))
    {}
    query))

(def ^:private pull-query
  "The screen, declared once and adjacent to nothing else — which is the
  whole of this arm's ergonomic claim."
  [{:user [:name :email]}
   {:rows [:text :done?]}])

;; ---------------------------------------------------------------------------
;; The witness — one form over a user record, one list of R rows
;; ---------------------------------------------------------------------------
;;
;; `:user/locale` and `:noise` are in the db and outside every arm's read
;; extent, so an INDEPENDENT write has somewhere to land. Without them
;; `G3` could only be measured by not writing at all, which measures
;; nothing.

(defn- seed-db
  [r]
  {:user  {:name "ada" :email "ada@example.com" :locale "en"}
   :rows  (mapv (fn [i] {:id i :text (str "row-" i) :done? (even? i)}) (range r))
   :noise 0})

(rf/reg-event :spike/seed         (fn [_ [_ db]] {:db db}))
(rf/reg-event :spike/edit-row     (fn [{:keys [db]} [_ i]] {:db (update-in db [:rows i :text] str "!")}))
(rf/reg-event :spike/bump-noise   (fn [{:keys [db]} _] {:db (update db :noise inc)}))

;; ---------------------------------------------------------------------------
;; Arm F — fine reads
;; ---------------------------------------------------------------------------

(rf/reg-sub :fine/user-name
  (fn [db _] (swap! (!l1 :fine) inc) (lk (!lookups :fine) (lk (!lookups :fine) db :user) :name)))

(rf/reg-sub :fine/user-email
  (fn [db _] (swap! (!l1 :fine) inc) (lk (!lookups :fine) (lk (!lookups :fine) db :user) :email)))

(rf/reg-sub :fine/row-text
  (fn [db [_ i]]
    (swap! (!l1 :fine) inc)
    (let [!n (!lookups :fine)] (lk !n (lk !n (lk !n db :rows) i) :text))))

(rf/reg-sub :fine/row-done
  (fn [db [_ i]]
    (swap! (!l1 :fine) inc)
    (let [!n (!lookups :fine)] (lk !n (lk !n (lk !n db :rows) i) :done?))))

(rf/reg-sub :fine/user-view
  :<- [:fine/user-name]
  :<- [:fine/user-email]
  (fn [[name email] _]
    (let [v {:name name :email email}]
      (swap! (!out :fine) conj v)
      v)))

(rf/reg-sub :fine/row-view
  (fn [[_ i]] [[:fine/row-text i] [:fine/row-done i]])
  (fn [[text done?] _]
    (let [v {:text text :done? done?}]
      (swap! (!out :fine) conj v)
      v)))

;; ---------------------------------------------------------------------------
;; Arm C — the hand-coarse view-model
;; ---------------------------------------------------------------------------

(rf/reg-sub :coarse/view-model
  (fn [db _]
    (swap! (!l1 :coarse) inc)
    (let [!n   (!lookups :coarse)
          user (lk !n db :user)
          rows (lk !n db :rows)]
      {:user {:name (lk !n user :name) :email (lk !n user :email)}
       :rows (mapv (fn [i]
                     (let [row (lk !n rows i)]
                       {:text (lk !n row :text) :done? (lk !n row :done?)}))
                   (range (count rows)))})))

(rf/reg-sub :coarse/screen
  :<- [:coarse/view-model]
  (fn [vm _] (swap! (!out :coarse) conj vm) vm))

;; ---------------------------------------------------------------------------
;; Arm P — one declarative pull
;; ---------------------------------------------------------------------------

(rf/reg-sub :pull/view-model
  (fn [db _]
    (swap! (!l1 :pull) inc)
    (pull (!lookups :pull) (!steps :pull) db pull-query)))

(rf/reg-sub :pull/screen
  :<- [:pull/view-model]
  (fn [vm _] (swap! (!out :pull) conj vm) vm))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil}))

(defn- arm-of
  "Which arm a query-vector belongs to, by the id's namespace."
  [query-v]
  (when (vector? query-v)
    (some-> (first query-v) namespace keyword)))

(defrecord ^:private Screen [frame-id r refs])

(defn- open-screen!
  "Seat a frame, seed `r` rows, subscribe every consumer a rendered screen
  would hold, and warm them. The refs are HELD — a sub-cache entry's
  lifetime is its ref-count's, so a comparator that re-subscribed per
  reading would be measuring its own churn."
  [frame-id r]
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:spike/seed (seed-db r)])
    (let [refs {:fine   (into [(rf/subscribe [:fine/user-view])]
                              (map #(rf/subscribe [:fine/row-view %])) (range r))
                :coarse [(rf/subscribe [:coarse/screen])]
                :pull   [(rf/subscribe [:pull/screen])]}]
      (doseq [[_ rs] refs, r* rs] (deref r*))
      (->Screen frame-id r refs))))

(defn- read-screen!
  "Deref every held consumer — what a rendered screen does on a tick."
  [^Screen screen]
  (rf/with-frame (:frame-id screen)
    (into {} (map (fn [[arm rs]] [arm (mapv deref rs)])) (:refs screen))))

(defn- reading
  "The figures for one arm, off the tallies as they stand."
  [arm]
  (let [delivered @(!out arm)]
    {:g1  (count delivered)
     :g2  (reduce + 0 (map leaf-count delivered))
     :g4  @(!l1 arm)
     :g5  @(!lookups arm)
     :g5b @(!steps arm)}))

(defn- observe!
  "Zero the tallies, drive `event!`, read the screen, and report each
  arm's figures. Everything a churn row needs, in one place, so no row
  can accidentally read a tally another row left behind."
  [^Screen screen event-v]
  (reset-tallies! )
  (rf/with-frame (:frame-id screen) (rf/dispatch-sync event-v))
  (read-screen! screen)
  (zipmap tally-keys (map reading tally-keys)))

(defn- retained
  "`G6` — live sub-cache entries, per arm, off the shipping tooling
  surface rather than off a count this file keeps for itself."
  [^Screen screen]
  (let [snapshot (subs-tooling/sub-cache-snapshot (:frame-id screen))]
    (reduce-kv (fn [acc query-v _] (update acc (arm-of query-v) (fnil inc 0)))
               {:fine 0 :coarse 0 :pull 0}
               (or snapshot {}))))

(defn- declared
  "`G7` — declared subscription ids, per arm, off `sub-topology`."
  []
  (reduce-kv (fn [acc sub-id _]
               (if-some [arm (some-> sub-id namespace keyword)]
                 (update acc arm (fnil inc 0))
                 acc))
             {:fine 0 :coarse 0 :pull 0}
             (subs-tooling/sub-topology)))

;; ---------------------------------------------------------------------------
;; The precondition: three arms, and all three ran
;; ---------------------------------------------------------------------------

(deftest all-three-arms-run-and-agree
  (testing "each arm produced a value, the three agree leaf for leaf, and
            each arm's own instrument moved — because two arms agreeing
            while a third is structurally absent is this comparator's
            most likely defect"
    (let [screen (open-screen! ::agree 4)
          out    (observe! screen [:spike/edit-row 0])
          read   (read-screen! screen)
          coarse (first (:coarse read))
          pulled (first (:pull read))
          fine   (:fine read)]

      (testing "every arm delivered"
        (is (some? coarse) "the coarse arm produced no value")
        (is (some? pulled) "the pull arm produced no value")
        (is (= 5 (count fine)) "the fine arm should hold 1 form + 4 row consumers"))

      (testing "the coarse and pull arms agree exactly"
        (is (= coarse pulled)))

      (testing "and the fine arm agrees with both, leaf for leaf"
        (is (= (select-keys (first fine) [:name :email]) (:user coarse)))
        (is (= (vec (rest fine)) (:rows coarse)))
        (is (= (vec (rest fine)) (:rows pulled))))

      (testing "each arm's instrument moved, so no arm is being read off a
                counter that never ran"
        (doseq [arm tally-keys]
          (is (pos? (:g4 (get out arm))) (str "arm " arm " ran no layer-1 handler"))
          (is (pos? (:g5 (get out arm))) (str "arm " arm " performed no app-db lookup"))
          (is (pos? (:g1 (get out arm))) (str "arm " arm " notified no consumer")))))))

(deftest the-instrument-answers-both-ways
  (testing "the agreement assertion above is capable of failing — a pull
            query that omits a field produces a value the coarse arm's
            does not equal, so the parity row is a discriminator rather
            than a helper that only knows one verb"
    (let [screen (open-screen! ::both-ways 4)
          _      (read-screen! screen)
          db     (rf/with-frame (:frame-id screen) @(rf/subscribe [:coarse/view-model]))
          short  (pull (atom 0) (atom 0)
                       {:user {:name "ada" :email "ada@example.com"}
                        :rows [{:text "row-0" :done? true}]}
                       [{:user [:name]} {:rows [:text :done?]}])]
      (is (some? db))
      (is (not= (:user db) (:user short))
          "a query missing a field must not agree with the coarse arm"))))

;; ---------------------------------------------------------------------------
;; G1–G5b — correlated churn
;; ---------------------------------------------------------------------------

(deftest correlated-churn-separates-the-arms-on-relocated-work
  (testing "one row's text changes — inside every arm's read extent"
    (let [screen (open-screen! ::corr 4)
          out    (observe! screen [:spike/edit-row 0])]

      (testing "G1 — one consumer re-runs in every arm, so G1 alone does
                not separate them"
        (is (= 1 (:g1 (:fine out))))
        (is (= 1 (:g1 (:coarse out))))
        (is (= 1 (:g1 (:pull out)))))

      (testing "G2 — but what that one consumer must re-consume does"
        (is (= 2 (:g2 (:fine out))))
        (is (= 10 (:g2 (:coarse out))))
        (is (= 10 (:g2 (:pull out)))))

      (testing "G4 — layer-1 handler recomputations per write"
        (is (= 10 (:g4 (:fine out))))
        (is (= 1 (:g4 (:coarse out))))
        (is (= 1 (:g4 (:pull out)))))

      (testing "G5 — app-db path lookups, the same unit in all three arms"
        (is (= 28 (:g5 (:fine out))))
        (is (= 14 (:g5 (:coarse out))))
        (is (= 14 (:g5 (:pull out)))))

      (testing "G5b — query-interpretation steps: the pull arm's whole
                overhead over the hand-written answer, isolated"
        (is (= 0 (:g5b (:fine out))))
        (is (= 0 (:g5b (:coarse out))))
        (is (= 10 (:g5b (:pull out))))))))

;; ---------------------------------------------------------------------------
;; G3 — independent churn
;; ---------------------------------------------------------------------------

(deftest independent-churn-notifies-nobody-in-any-arm
  (testing "a write to `:noise`, outside every arm's read extent"
    (let [screen (open-screen! ::indep 4)
          out    (observe! screen [:spike/bump-noise])]

      (testing "G3 — no consumer re-runs anywhere"
        (is (= 0 (:g1 (:fine out))))
        (is (= 0 (:g1 (:coarse out))))
        (is (= 0 (:g1 (:pull out)))))

      (testing "but every arm still paid to discover that — which is the
                cost independent churn actually has, and it is not zero"
        (is (= 10 (:g4 (:fine out))))
        (is (= 1 (:g4 (:coarse out))))
        (is (= 1 (:g4 (:pull out))))
        (is (= 28 (:g5 (:fine out))))
        (is (= 14 (:g5 (:coarse out))))
        (is (= 14 (:g5 (:pull out))))
        (is (= 10 (:g5b (:pull out))))))))

;; ---------------------------------------------------------------------------
;; The control that moves every figure — quadruple the rows
;; ---------------------------------------------------------------------------

(deftest quadrupling-the-rows-moves-what-it-must-and-leaves-flat-what-it-must
  (testing "the same screen at R=4 and R=16. A number that cannot be made
            to move is a coincidence rather than a figure, so every row
            above is read again against a population four times as large"
    (let [small (observe! (open-screen! ::ctl-4 4) [:spike/edit-row 0])
          big   (observe! (open-screen! ::ctl-16 16) [:spike/edit-row 0])]

      (testing "G2 stays flat for the fine arm and scales for the other two
                — the whole of the fine/coarse trade, in one reading"
        (is (= 2 (:g2 (:fine small))))
        (is (= 2 (:g2 (:fine big))) "fine re-delivers one row whatever R is")
        (is (= 10 (:g2 (:coarse small))))
        (is (= 34 (:g2 (:coarse big))))
        (is (= 10 (:g2 (:pull small))))
        (is (= 34 (:g2 (:pull big))) "the pull arm scales exactly as the coarse arm does"))

      (testing "G4 scales for the fine arm and stays flat for the other two"
        (is (= 10 (:g4 (:fine small))))
        (is (= 34 (:g4 (:fine big))))
        (is (= 1 (:g4 (:coarse small))))
        (is (= 1 (:g4 (:coarse big))))
        (is (= 1 (:g4 (:pull small))))
        (is (= 1 (:g4 (:pull big)))))

      (testing "G5 scales in every arm, and the pull:coarse ratio is what
                the deciding rule reads"
        (is (= 28 (:g5 (:fine small))))
        (is (= 100 (:g5 (:fine big))))
        (is (= 14 (:g5 (:coarse small))))
        (is (= 50 (:g5 (:coarse big))))
        (is (= 14 (:g5 (:pull small))))
        (is (= 50 (:g5 (:pull big)))))

      (testing "G5b scales with the rows, because a query node is visited
                per row"
        (is (= 10 (:g5b (:pull small))))
        (is (= 34 (:g5b (:pull big))))))))

;; ---------------------------------------------------------------------------
;; G6 — retention, and the kill condition
;; ---------------------------------------------------------------------------

(deftest the-pull-arm-holds-no-per-leaf-ledger
  (testing "G6 — live sub-cache entries at R=4 and at R=16. A per-leaf
            dependency ledger cannot be built without retaining something
            per leaf, and anything retained per leaf shows here. The bead
            stops the spike on any drift toward one, so this is the kill
            condition witnessed from outside rather than attested from
            inside the resolver."
    (let [small (open-screen! ::ret-4 4)
          big   (open-screen! ::ret-16 16)
          _     (read-screen! small)
          _     (read-screen! big)
          rs    (retained small)
          rb    (retained big)]

      (testing "the pull arm's retention is identical at both populations"
        (is (= (:pull rs) (:pull rb)))
        (is (= 2 (:pull rs)) "one view-model entry and one consumer entry"))

      (testing "so is the coarse arm's — the two are the same retention class"
        (is (= (:coarse rs) (:coarse rb)))
        (is (= 2 (:coarse rs))))

      (testing "while the fine arm's grows with the rows, which is what a
                per-leaf structure looks like when one is present — and is
                the positive control that gives this row its meaning"
        (is (= 11 (:fine rs)))
        (is (= 35 (:fine rb)))
        (is (< (:fine rs) (:fine rb)))))))

;; ---------------------------------------------------------------------------
;; G7 — declared ids
;; ---------------------------------------------------------------------------

(deftest declared-ids-do-not-grow-with-the-rows-in-any-arm
  (testing "G7 — what a consumer had to WRITE, off `sub-topology`. This is
            the ergonomics figure's countable half: the fine arm needs one
            registered id per distinct leaf KIND, and the other two need
            two apiece however many fields the screen carries."
    (let [d (declared)]
      (is (= 6 (:fine d)) "4 leaf kinds + 2 consumers")
      (is (= 2 (:coarse d)))
      (is (= 2 (:pull d))))))

;; ---------------------------------------------------------------------------
;; Adapter portability
;; ---------------------------------------------------------------------------

(deftest the-comparator-is-adapter-portable
  (testing "the bead asks for a resolver at the subs layer that is
            adapter-portable. Re-running the whole correlated-churn
            reading under the UIx adapter — a real reactive substrate,
            not `plain-atom` — measures that rather than asserting it."
    (rf/init! uix-adapter/adapter)
    (let [screen (open-screen! ::portable 4)
          out    (observe! screen [:spike/edit-row 0])]
      (is (= 1 (:g1 (:pull out))))
      (is (= 10 (:g2 (:pull out))))
      (is (= 1 (:g4 (:pull out))))
      (is (= 14 (:g5 (:pull out))))
      (is (= 10 (:g5b (:pull out))))
      (is (= 2 (:g2 (:fine out))) "and the fine arm's separation survives the substrate change"))))

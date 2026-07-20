(ns re-frame.ui.reactive-commit-causes-cljs-test
  "rf2-qkq2k (S6 slice b, reworked) — the per-commit `:rf.view/causes` vector on
  the REAL ViewCell commit path (Ruling 2). A connected commit projects DETAILED
  cause RECORDS — each `{:cause <kind> …ruled-fields}` — from signals that ALREADY
  exist, never a new capture machine and never a general evidence framework (only
  the fields explicitly ruled per cause):

    :mount           the :fresh->:connected transition (bare marker).
    :story-override  a (re)acquired static Story-override target — ruled identity
                     + version (`:override-id` / `:version`).
    :subscription    a `:subscription` port note captured at the cause site — ruled
                     target / query / frame-id + version :from->:to + :epoch.
    :local-state     the hooks local-state writer bridge (bare marker).
    :hmr             a real registrar-replacement `:hmr` fan-out (bare marker).
    :disposed        a `:disposed` port note (bare marker).
    :foreign-or-react the honesty fallback — a commit that carries no other cause.

  DEFERRED, never emitted here (each a deferred-with-trigger row in slice d's
  EP-0033 delta; consumers keep tolerating absence, Xray 021 §3.4.1):
    - :hmr-remount   honest per-instance attribution needs a teardown->remount
                     pairing signal (a remounted instance is React-indistinguishable
                     from a fresh mount at the same view generation using only the
                     view-global remount counter) — cross-surface + a fiddly closing
                     rule, so the S3 view-granularity emit (which mislabelled
                     unrelated later mounts) is deferred rather than shipped
                     dishonestly (rf2-qkq2k).
    - :epoch-restore restore provenance is outside `perform-restore!`'s dynamic
                     extent (Mike ruling option c).

  The DEBUG carry-forward (`:pending-commit-causes`) is captured at the NOTE (the
  cause site) and drained by the next connected commit: on the headless plain-atom
  hosts a value MOVE has no watch (it is caught at commit step 5, never fanned), so
  the `:subscription`/`:disposed` port notes are driven through the SAME private
  `enrol-dirty!` the watchable-host on-change calls — the honest payload, not a
  simulation. `:hmr` rides the REAL registrar fan-out.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` (node) AND
  `clojure -M:test` (JVM), so the cause vector is graft-checked on both hosts
  over the plain-atom adapter."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private fid :rf/default)

(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- causes      [cell] (:rf.view/causes (reactive/commit-record cell)))
(defn- cause-kinds [cell] (mapv :cause (causes cell)))
(defn- cause-of    [cell kind] (some (fn [c] (when (= kind (:cause c)) c)) (causes cell)))

(defn- render+commit!
  "Render (probe) `sites` (`[[sid query] …]`) under the ambient frame, then commit."
  [cell sites]
  (let [[_ cap] (rf/with-frame fid
                  (reactive/with-capture
                    cell
                    (fn [] (mapv (fn [[sid q]] (reactive/sub-read sid q)) sites))))]
    (reactive/commit! cell cap))
  cell)

(defn- connect-empty!
  "Commit one empty render so `cell` connects with no observation owners."
  [cell]
  (let [[_ cap] (reactive/with-capture cell (fn [] nil))]
    (reactive/commit! cell cap))
  cell)

;; The private on-change enrolment the watchable-host value-move / disposal
;; fan-out calls — the honest payload driver on a headless host (which has no
;; value-move watch, per the observation-port host-honesty note).
(def ^:private enrol-dirty! @#'reactive/enrol-dirty!)

(defn- fan-cause!
  "Fold ONE `cause`-tagged port note (targeting `query`) into `cell`'s pending
  window exactly as the watchable-host on-change would — carrying the SAME
  `:node-key` / `:node-version` / `:frame-epoch` axes the real port payload does
  (observation.cljc) so the captured cause DETAIL is honest — then flush FORWARD."
  ([cell cause query] (fan-cause! cell cause query {}))
  ([cell cause query {:keys [node-key node-version epoch]
                      :or   {node-key 7 node-version 3 epoch 1}}]
   (enrol-dirty! cell {:cause        cause
                       :target       {:kind :subscription :frame-id fid :query query}
                       :node-key     node-key
                       :node-version node-version
                       :frame-epoch  epoch})
   (reactive/flush-pending!)))

;; ===========================================================================
;; :mount — the first connected commit (:fresh -> :connected)
;; ===========================================================================

(deftest mount-is-the-first-connected-commits-cause
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [{:cause :mount}] (causes cell))
        "a first connected commit is caused by :mount (a bare record), nothing else")))

;; ===========================================================================
;; :foreign-or-react — the honesty fallback (a causeless re-commit)
;; ===========================================================================

(deftest a-causeless-recommit-is-foreign-or-react
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:mount] (cause-kinds cell)))
    (testing "a second commit with no movement, stash, or new acquire is honest"
      (render+commit! cell [[[:cc/site 0] [:cc/a]]])
      (is (= [{:cause :foreign-or-react}] (causes cell))
          "no cause pending, connected, retained handles -> the honesty fallback"))))

;; ===========================================================================
;; :subscription — a :subscription port note, captured with its ruled DETAIL
;; ===========================================================================

(deftest a-value-movement-recommits-as-subscription-with-ruled-detail
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    ;; a value move to node-version 3 on node-key 7, epoch 1
    (fan-cause! cell :subscription [:cc/a] {:node-key 7 :node-version 3 :epoch 1})
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [{:cause    :subscription
             :target   7            ;; upstream node identity (:node-key axis)
             :query    [:cc/a]
             :frame-id fid
             :from     2            ;; the version BEFORE the move (to - 1)
             :to       3
             :epoch    1}]
           (causes cell))
        ":subscription preserves ONLY its ruled fields
         (target/query/frame-id + version from->to + epoch) — no invented framework")))

(deftest a-coalesced-subscription-window-spans-first-from-to-latest-to
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    ;; two movements coalesce before the next commit: 2->3 then 4->5
    (fan-cause! cell :subscription [:cc/a] {:node-key 7 :node-version 3 :epoch 1})
    (fan-cause! cell :subscription [:cc/a] {:node-key 7 :node-version 5 :epoch 2})
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (let [sub (cause-of cell :subscription)]
      (is (= 2 (:from sub)) "the EARLIEST :from is kept (the window's first move)")
      (is (= 5 (:to sub))   "the LATEST :to is kept (the window's last move)")
      (is (= 2 (:epoch sub)) "the latest movement's epoch"))))

;; ===========================================================================
;; :disposed — a :disposed port note, a bare-marker record
;; ===========================================================================

(deftest a-disposal-recommits-as-disposed
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (fan-cause! cell :disposed [:cc/a])
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [{:cause :disposed}] (causes cell))
        ":disposed rides forward as a bare marker (no ruled detail)")))

;; ===========================================================================
;; :hmr — a REAL registrar-replacement fan-out, a bare-marker record
;; ===========================================================================

(deftest a-real-hmr-reregistration-recommits-as-hmr
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[[:cc/site 0] [:cc/a]]])]
    (is (= [:mount] (cause-kinds cell)))
    (rf/reg-sub :cc/a (fn [db _] (:a db)))     ;; real :hmr fan-out to the handle
    (is (reactive/dirty? cell) "the HMR invalidation marked the cell dirty")
    (is (= #{:hmr} (:causes (reactive/pending-evidence cell)))
        "the real :hmr cause is in the pending window")
    (reactive/flush-pending!)                  ;; flushes the window
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [{:cause :hmr}] (causes cell))
        "a real registrar-replacement fan-out projects a bare :hmr record")))

;; ===========================================================================
;; :local-state — the hooks local-state writer bridge (bare-marker record)
;; ===========================================================================

(deftest a-local-state-write-recommits-as-local-state
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[[:cc/site 0] [:cc/a]]])]
    (reactive/note-local-state! cell)          ;; the substrate-owned local writer
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [{:cause :local-state}] (causes cell))
        "a host-only local write is the honest cause of its re-render")))

(deftest note-local-state-is-a-no-op-on-a-nil-cell
  (is (nil? (reactive/note-local-state! nil))
      "a (local …) used outside a live capture stashes nothing and never throws"))

(deftest an-unstashed-local-state-does-not-contaminate-a-later-unrelated-commit
  ;; The commit-attribution property the hooks Object.is gate protects: when the
  ;; local writer does NOT stash (a React-19.2 Object.is-bailed no-op setter),
  ;; a later UNRELATED commit must NOT carry :local-state (rf2-qkq2k).
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[[:cc/site 0] [:cc/a]]])]
    ;; a no-op setter stashes nothing (the gate lives in hooks; here we simply do
    ;; not stash) — then an unrelated subscription movement drives the next commit
    (fan-cause! cell :subscription [:cc/a])
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:subscription] (cause-kinds cell))
        "an unrelated commit reports its real cause only — no stale :local-state")))

;; ===========================================================================
;; :story-override — a (re)acquired static Story-override target + ruled detail
;; ===========================================================================

(deftest a-moved-story-override-recommits-as-story-override
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (binding [reactive/*sub-overrides* {[:cc/a] 10}]
      (render+commit! cell [[[:cc/site 0] [:cc/a]]]))
    (is (= #{[:override [:cc/a]]} (reactive/committed-target-keys cell))
        "the site resolved to a static override target")
    (is (= :mount (:cause (cause-of cell :mount)))
        "the first override commit is a mount")
    (testing "moving the override retargets the site -> :story-override with ruled detail"
      (binding [reactive/*sub-overrides* {[:cc/a] 20}]      ;; new value -> retarget
        (render+commit! cell [[[:cc/site 0] [:cc/a]]]))
      (is (= [{:cause       :story-override
               :override-id [:cc/a]     ;; the ruled override identity (the query)
               :version     20}]        ;; the ruled override version (the value)
             (causes cell))
          "a re-acquired override target classifies the commit as :story-override
           and preserves ONLY the ruled identity + version"))))

;; ===========================================================================
;; :hmr-remount — DEFERRED (Fix #2): a mount under a remounted view is NOT
;; mislabelled; honest per-instance attribution needs pairing infra we defer.
;; ===========================================================================

(deftest a-mount-under-a-remounted-view-is-not-mislabelled-hmr-remount
  (let [vid ::remount-view]
    (reactive/register-view-descriptor! vid :sig-a {:impl :a})
    (reactive/register-view-descriptor! vid :sig-b {:impl :b})   ;; hook shape change
    (is (= 1 (reactive/view-remount-generation vid))
        "an incompatible hook edit advanced the view-global remount generation")
    (let [gen  (reactive/view-generation vid)          ;; the current body revision
          cell (reactive/make-cell vid gen)]           ;; mint at the live revision
      (connect-empty! cell)
      (is (= [{:cause :mount}] (causes cell))
          "a mount under a remounted view is just :mount — :hmr-remount is DEFERRED
           (view-granularity would mislabel this possibly-fresh instance, rf2-qkq2k)")
      (is (not (contains? (set (cause-kinds cell)) :hmr-remount))
          ":hmr-remount is never emitted by slice b"))))

;; ===========================================================================
;; CAS race (Fix #4): a cause folded during publication is INCLUDED, not erased
;; ===========================================================================

(deftest a-cause-folded-during-publication-is-not-erased
  ;; The atomic take/publish (rf2-qkq2k): the `*commit-publish-barrier*` fires
  ;; ONCE between the pending-causes read and the publish compare-and-set!, folding
  ;; a fresh :local-state exactly in the take->publish window the old unconditional
  ;; `dissoc` erased. The CAS then fails, the mint re-reads, and the racing cause is
  ;; INCLUDED in the published record. Deterministic on both hosts.
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell  (render+commit! (reactive/make-cell ::v) [[[:cc/site 0] [:cc/a]]])
        fired (atom false)]
    (fan-cause! cell :subscription [:cc/a])           ;; a :subscription is already pending
    (binding [reactive/*commit-publish-barrier*
              (fn [c]
                (when (compare-and-set! fired false true)
                  ;; a concurrent fold lands in the take->publish window
                  (reactive/note-local-state! c)))]
      (render+commit! cell [[[:cc/site 0] [:cc/a]]]))
    (is @fired "the publication barrier fired inside the take->publish window")
    (is (= #{:subscription :local-state} (set (cause-kinds cell)))
        "the racing :local-state fold is INCLUDED in the published record, not erased")))

;; ===========================================================================
;; eww3k (Fence): a SUBSCRIPTION folded during publication is FENCED to the
;; correction commit it actually drove — NOT back-attributed to this record
;; ===========================================================================

(deftest a-subscription-folded-during-publication-is-fenced-to-its-own-commit
  ;; rf2-eww3k: the render waterline fences a movement that drove NO already-rendered
  ;; commit. A subscription 2->3 drives render N; a fresh 3->4 movement lands in the
  ;; publish barrier (ABOVE the render's waterline). The current record must describe
  ;; ONLY 2->3 (not a back-attributed 2->4), the cell must stay dirty, and the racing
  ;; 3->4 must own the NEXT commit rather than falling back to :foreign-or-react.
  ;; Deterministic on both hosts (single-threaded CLJS runs one CAS iteration).
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell  (render+commit! (reactive/make-cell ::v) [[[:cc/site 0] [:cc/a]]])
        fired (atom false)]
    ;; 2->3 is pending and drives render N (fan-cause! flushes it forward)
    (fan-cause! cell :subscription [:cc/a] {:node-key 7 :node-version 3 :epoch 1})
    (binding [reactive/*commit-publish-barrier*
              (fn [c]
                (when (compare-and-set! fired false true)
                  ;; a NEW movement 3->4 lands in the take->publish window — it drove
                  ;; no already-rendered commit; it marks the cell dirty for a correction
                  (enrol-dirty! c {:cause        :subscription
                                   :target       {:kind :subscription :frame-id fid :query [:cc/a]}
                                   :node-key     7
                                   :node-version 4
                                   :frame-epoch  2})))]
      (render+commit! cell [[[:cc/site 0] [:cc/a]]]))
    (is @fired "the publication barrier fired inside the take->publish window")
    (testing "the record describes ONLY the movement its render saw (2->3), not 2->4"
      (let [sub (cause-of cell :subscription)]
        (is (= 2 (:from sub)) "the earliest :from of the render's own window")
        (is (= 3 (:to sub))   "the barrier-time 3->4 is NOT back-attributed to this record")))
    (testing "the racing 3->4 stayed pending and drives the correction commit"
      (is (reactive/dirty? cell) "the barrier-time movement marked the cell dirty")
      (reactive/flush-pending!)
      (render+commit! cell [[[:cc/site 0] [:cc/a]]])
      (let [sub (cause-of cell :subscription)]
        (is (= 3 (:from sub)) "the correction commit owns the racing move")
        (is (= 4 (:to sub))
            "the fenced cause drives its own commit — never left as :foreign-or-react")))))

;; ===========================================================================
;; sy536 (Preserve each cause) — distinct causal identities stay distinct
;; ===========================================================================

(deftest two-subscription-targets-yield-two-truthful-records
  ;; rf2-sy536: two DIFFERENT targets moving before one commit must yield TWO
  ;; :subscription records — neither disappears and NO record fabricates a
  ;; cross-target span (one target's :from with another's :to).
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (rf/reg-sub :cc/b (fn [db _] (:b db)))
  (seed! {:a 1 :b 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]] [[:cc/site 1] [:cc/b]]])
    ;; node A (:node-key 7) moves 2->3; node B (:node-key 8) moves 20->21
    (fan-cause! cell :subscription [:cc/a] {:node-key 7 :node-version 3  :epoch 1})
    (fan-cause! cell :subscription [:cc/b] {:node-key 8 :node-version 21 :epoch 1})
    (render+commit! cell [[[:cc/site 0] [:cc/a]] [[:cc/site 1] [:cc/b]]])
    (let [subs (filterv #(= :subscription (:cause %)) (causes cell))]
      (is (= 2 (count subs)) "two distinct targets -> two records; neither disappears")
      (is (= {7 {:query [:cc/a] :from 2  :to 3}
              8 {:query [:cc/b] :from 20 :to 21}}
             (into {} (map (fn [s] [(:target s) (select-keys s [:query :from :to])])) subs))
          "each record keeps ONLY its own target's identity + version span —
           no cross-target :from/:to fabrication"))))

(deftest sibling-story-overrides-each-retain-a-record
  ;; rf2-sy536: two moved Story overrides in one commit retain one detailed record
  ;; EACH — the old `some` over `to-acquire` dropped the sibling.
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (rf/reg-sub :cc/b (fn [db _] (:b db)))
  (seed! {:a 1 :b 1})
  (let [cell (reactive/make-cell ::v)]
    (binding [reactive/*sub-overrides* {[:cc/a] 10 [:cc/b] 30}]      ;; mount both overrides
      (render+commit! cell [[[:cc/site 0] [:cc/a]] [[:cc/site 1] [:cc/b]]]))
    (binding [reactive/*sub-overrides* {[:cc/a] 20 [:cc/b] 40}]      ;; move BOTH
      (render+commit! cell [[[:cc/site 0] [:cc/a]] [[:cc/site 1] [:cc/b]]]))
    (let [ovs (filterv #(= :story-override (:cause %)) (causes cell))]
      (is (= 2 (count ovs)) "each moved override retains its own record (no sibling drop)")
      (is (= {[:cc/a] 20 [:cc/b] 40}
             (into {} (map (fn [o] [(:override-id o) (:version o)])) ovs))
          "each record carries ONLY its own override's ruled identity + version"))))

;; ===========================================================================
;; Canonical order — the cause vector is deterministic across hosts/runs
;; ===========================================================================

(deftest the-cause-vector-is-in-canonical-order
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    ;; A mount that ALSO carries a movement: mount + a stashed :subscription.
    (fan-cause! cell :subscription [:cc/a])           ;; stash :subscription onto the fresh cell
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:mount :subscription] (cause-kinds cell))
        ":mount precedes :subscription in the canonical roster order")))

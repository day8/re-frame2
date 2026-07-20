(ns re-frame.ui.reactive-commit-causes-cljs-test
  "rf2-qkq2k (S6 slice b) — the per-commit `:rf.view/causes` vector on the REAL
  ViewCell commit path (Ruling 2). A connected commit projects the NINE ruled
  cause kinds from signals that ALREADY exist — never a new capture machine:

    :mount          the :fresh->:connected transition (pre-commit lifecycle).
    :hmr-remount    a mount whose view has a non-zero HMR remount generation.
    :story-override a (re)acquired site whose target is a static Story override.
    :subscription   a `:value` port note carried forward by the flush (renamed).
    :local-state    the hooks local-state writer bridge (`note-local-state!`).
    :hmr            a real registrar-replacement `:hmr` fan-out, flushed forward.
    :disposed       a `:disposed` port note carried forward by the flush.
    :foreign-or-react the honesty fallback — a commit that carries no other cause.

  `:epoch-restore` is the ninth ruled kind but is NOT produced by slice b: its
  emission (threading the restore-operation token through the port fan-out) is
  not a clean projection of an existing signal, because the value-movement watch
  fires from the DEFERRED reactive commit loop, outside the restore's dynamic
  extent (worker STOP-REPORT). Consumers already tolerate its absence (Xray 021
  §3.4.1) — the same way they tolerate the deferred five.

  The DEBUG carry-forward (`:pending-commit-causes`) is the seam the flush and
  the local-state bridge feed and the next connected commit drains: on the
  headless plain-atom hosts a value MOVE has no watch (it is caught at commit
  step 5, never fanned), so the `:value`/`:disposed` port notes are driven
  through the SAME private `enrol-dirty!` the watchable-host on-change calls —
  the honest payload, not a simulation. `:hmr` rides the REAL registrar fan-out.

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

(defn- causes [cell] (:rf.view/causes (reactive/commit-record cell)))

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
  window exactly as the watchable-host on-change would, then flush it FORWARD."
  [cell cause query]
  (enrol-dirty! cell {:cause       cause
                      :target      {:kind :subscription :frame-id fid :query query}
                      :frame-epoch 1})
  (reactive/flush-pending!))

;; ===========================================================================
;; :mount — the first connected commit (:fresh -> :connected)
;; ===========================================================================

(deftest mount-is-the-first-connected-commits-cause
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:mount] (causes cell))
        "a first connected commit is caused by :mount, and nothing else")))

;; ===========================================================================
;; :foreign-or-react — the honesty fallback (a causeless re-commit)
;; ===========================================================================

(deftest a-causeless-recommit-is-foreign-or-react
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:mount] (causes cell)))
    (testing "a second commit with no movement, stash, or new acquire is honest"
      (render+commit! cell [[[:cc/site 0] [:cc/a]]])
      (is (= [:foreign-or-react] (causes cell))
          "no cause pending, connected, retained leases -> the honesty fallback"))))

;; ===========================================================================
;; :subscription — a :value port note, carried forward + renamed
;; ===========================================================================

(deftest a-value-movement-recommits-as-subscription
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (fan-cause! cell :value [:cc/a])          ;; the flush stashes :value forward
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:subscription] (causes cell))
        ":value is renamed to :subscription at projection (Ruling 2)")))

;; ===========================================================================
;; :disposed — a :disposed port note, carried forward verbatim
;; ===========================================================================

(deftest a-disposal-recommits-as-disposed
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (fan-cause! cell :disposed [:cc/a])
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:disposed] (causes cell))
        ":disposed rides forward unchanged")))

;; ===========================================================================
;; :hmr — a REAL registrar-replacement fan-out, flushed forward
;; ===========================================================================

(deftest a-real-hmr-reregistration-recommits-as-hmr
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[[:cc/site 0] [:cc/a]]])]
    (is (= [:mount] (causes cell)))
    (rf/reg-sub :cc/a (fn [db _] (:a db)))     ;; real :hmr fan-out to the lease
    (is (reactive/dirty? cell) "the HMR invalidation marked the cell dirty")
    (is (= #{:hmr} (:causes (reactive/pending-evidence cell)))
        "the real :hmr cause is in the pending window")
    (reactive/flush-pending!)                  ;; carries :hmr forward
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:hmr] (causes cell))
        "a real registrar-replacement fan-out projects :hmr")))

;; ===========================================================================
;; :local-state — the hooks local-state writer bridge
;; ===========================================================================

(deftest a-local-state-write-recommits-as-local-state
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[[:cc/site 0] [:cc/a]]])]
    (reactive/note-local-state! cell)          ;; the substrate-owned local writer
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:local-state] (causes cell))
        "a host-only local write is the honest cause of its re-render")))

(deftest note-local-state-is-a-no-op-on-a-nil-cell
  (is (nil? (reactive/note-local-state! nil))
      "a (local …) used outside a live capture stashes nothing and never throws"))

;; ===========================================================================
;; :story-override — a (re)acquired static Story-override target
;; ===========================================================================

(deftest a-moved-story-override-recommits-as-story-override
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    (binding [reactive/*sub-overrides* {[:cc/a] 10}]
      (render+commit! cell [[[:cc/site 0] [:cc/a]]]))
    (is (= #{[:override [:cc/a]]} (reactive/committed-target-keys cell))
        "the site resolved to a static override target")
    (is (contains? (set (causes cell)) :mount)
        "the first override commit is a mount")
    (testing "moving the override retargets the site -> :story-override, not mount"
      (binding [reactive/*sub-overrides* {[:cc/a] 20}]      ;; new value -> retarget
        (render+commit! cell [[[:cc/site 0] [:cc/a]]]))
      (is (= [:story-override] (causes cell))
          "a re-acquired override target classifies the commit as :story-override"))))

;; ===========================================================================
;; :hmr-remount — a mount whose view has a non-zero HMR remount generation
;; ===========================================================================

(deftest a-mount-under-a-remounted-view-is-hmr-remount
  (let [vid ::remount-view]
    (reactive/register-view-descriptor! vid :sig-a {:impl :a})
    (reactive/register-view-descriptor! vid :sig-b {:impl :b})   ;; hook shape change
    (is (= 1 (reactive/view-remount-generation vid))
        "an incompatible hook edit advanced the remount generation")
    (let [gen  (reactive/view-generation vid)          ;; the current body revision
          cell (reactive/make-cell vid gen)]           ;; mint at the live revision
      (connect-empty! cell)
      (is (= [:mount :hmr-remount] (causes cell))
          "a mount under a remounted view carries BOTH :mount and :hmr-remount"))))

;; ===========================================================================
;; Canonical order — the cause vector is deterministic across hosts/runs
;; ===========================================================================

(deftest the-cause-vector-is-in-canonical-order
  (rf/reg-sub :cc/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (reactive/make-cell ::v)]
    ;; A mount that ALSO carries a movement: mount + a stashed :value.
    (fan-cause! cell :value [:cc/a])           ;; stash :value onto the fresh cell
    (render+commit! cell [[[:cc/site 0] [:cc/a]]])
    (is (= [:mount :subscription] (causes cell))
        ":mount precedes :subscription in the canonical roster order")))

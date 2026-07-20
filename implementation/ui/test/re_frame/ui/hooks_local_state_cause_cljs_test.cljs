(ns re-frame.ui.hooks-local-state-cause-cljs-test
  "rf2-bvqu0 (S6 slice b) — node companion to the browser mount proof
  (`hooks_local_state_commit_dom_cljs_test`). The `:local-state` view-evidence cause
  is attributed ONLY after a REAL React commit. The hooks `local-state` updater is
  now PURE; attribution rides a DEBUG committed-value `useLayoutEffect` that calls
  `reactive/note-local-state!` exactly once per committed value change (a no-op
  setter and a same-batch net-zero `0->1->0` are bailed by React WITHOUT committing,
  so the layout effect never runs and nothing is stashed).

  react-dom mounting is browser-only in this repo, so THIS file pins the
  reactive-side flag contract the hook drives: a set flag contributes exactly one
  :local-state cause to the next connected commit, and an UNSET flag (a bailed
  write that never ran the layout effect) leaves a later unrelated commit clean.
  The end-to-end MOUNTED behaviour — the actual `local` setter driving a real React
  root — rides the sibling `-dom-cljs-test`. CLJS-only; rides `npm run test:cljs`
  (node)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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
(defn- cause-kinds [cell] (mapv :cause (:rf.view/causes (reactive/commit-record cell))))

(defn- render+commit! [cell sites]
  (let [[_ cap] (rf/with-frame fid
                  (reactive/with-capture
                    cell
                    (fn [] (mapv (fn [[sid q]] (reactive/sub-read sid q)) sites))))]
    (reactive/commit! cell cap))
  cell)

(def ^:private enrol-dirty! @#'reactive/enrol-dirty!)

(defn- fan-value! [cell query]
  (enrol-dirty! cell {:cause :subscription
                      :target {:kind :subscription :frame-id fid :query query}
                      :node-key 7 :node-version 3 :frame-epoch 1})
  (reactive/flush-pending!))

;; ===========================================================================
;; A committed local change (the layout effect fired) -> the commit is :local-state
;; ===========================================================================

(deftest a-committed-local-change-is-attributed
  (rf/reg-sub :ls/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[[:ls/site 0] [:ls/a]]])]
    ;; the committed-value layout effect (fired for a real change) sets the flag
    (reactive/note-local-state! cell)
    (render+commit! cell [[[:ls/site 0] [:ls/a]]])
    (is (= [:local-state] (cause-kinds cell))
        "a committed host-only write contributes exactly one :local-state cause")))

;; ===========================================================================
;; No committed change (a bailed no-op / net-zero batch) -> a later commit is clean
;; ===========================================================================

(deftest an-uncommitted-write-does-not-contaminate-a-later-unrelated-commit
  (rf/reg-sub :ls/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[[:ls/site 0] [:ls/a]]])]
    ;; a no-op setter / net-zero batch never runs the committed-value layout effect,
    ;; so NOTHING is stashed (the flag stays unset) — then an unrelated subscription
    ;; movement drives the next commit
    (testing "the unset flag leaves no marker for a later unrelated commit"
      (fan-value! cell [:ls/a])
      (render+commit! cell [[[:ls/site 0] [:ls/a]]])
      (is (= [:subscription] (cause-kinds cell))
          "no committed local change -> no stale :local-state on an unrelated commit"))))

;; ===========================================================================
;; note-local-state! is a no-op on a nil cell (a local used outside a live capture)
;; ===========================================================================

(deftest note-local-state-is-safe-on-a-nil-cell
  (is (nil? (reactive/note-local-state! nil))
      "a (local …) used outside a live capture stashes nothing and never throws"))

(ns re-frame.ui.hooks-local-state-cause-cljs-test
  "rf2-qkq2k (S6 slice b, Fix #3) — the `:local-state` view-evidence cause is
  attributed ONLY to an actually-committed value change. React 19.2 Object.is-BAILS
  a no-op setter WITHOUT rendering, so the S3 bridge (which stashed on every setter
  invocation) left a stale marker that contaminated a LATER unrelated commit. The
  hooks setter now routes its DEBUG note through `note-committed-local-change!`,
  which stashes ONLY when `cur` (the live host state React compares) differs from
  the write by `Object.is` — React's own bail law — so the note fires EXACTLY when
  a re-render (hence a connected commit) will.

  This is the node-runnable proof of the gate fn itself (the decision point the
  react-set updater invokes) driven through a REAL committed ViewCell: a real
  change → the commit reports :local-state; a no-op → nothing stashed → a later
  unrelated commit stays clean. (react-dom mounting is browser-only in this repo;
  the gate reuses React's exact `Object.is` law, so the mounted behaviour follows
  by construction.) CLJS-only (`Object.is` is a host primitive); rides
  `npm run test:cljs` (node)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.hooks             :as hooks]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private fid :rf/default)
(defn- seed! [db] (frame/replace-app-db! fid db))

;; the exact private gate the hooks local-state setter/updater invoke
(def ^:private note-committed-local-change!
  @#'hooks/note-committed-local-change!)

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
;; A REAL change stashes -> the commit reports :local-state
;; ===========================================================================

(deftest a-real-local-change-is-attributed
  (rf/reg-sub :ls/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[[:ls/site 0] [:ls/a]]])]
    (is (= 2 (note-committed-local-change! cell 1 2))
        "the gate returns the new value unchanged (the react-set updater's result)")
    (render+commit! cell [[[:ls/site 0] [:ls/a]]])
    (is (= [:local-state] (cause-kinds cell))
        "an Object.is-DIFFERENT write (a real change React commits) is :local-state")))

;; ===========================================================================
;; A no-op setter stashes NOTHING -> a later unrelated commit stays clean
;; ===========================================================================

(deftest a-noop-setter-does-not-contaminate-a-later-unrelated-commit
  (rf/reg-sub :ls/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell (render+commit! (reactive/make-cell ::v) [[[:ls/site 0] [:ls/a]]])]
    (is (= 5 (note-committed-local-change! cell 5 5))
        "an Object.is-EQUAL write returns the value but stashes nothing (React bails)")
    (testing "a LATER unrelated (subscription) commit reports only its own cause"
      (fan-value! cell [:ls/a])
      (render+commit! cell [[[:ls/site 0] [:ls/a]]])
      (is (= [:subscription] (cause-kinds cell))
          "the stale no-op marker no longer contaminates an unrelated commit"))))

;; ===========================================================================
;; The gate is a no-op on a nil cell (a local used outside a live capture)
;; ===========================================================================

(deftest the-gate-is-safe-on-a-nil-cell
  (is (= 9 (note-committed-local-change! nil 8 9))
      "a real change on a nil cell returns the value and never throws")
  (is (= 8 (note-committed-local-change! nil 8 8))
      "a no-op on a nil cell returns the value and never throws"))

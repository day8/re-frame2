(ns re-frame.sub-memo-flush-path-cljs-test
  "The layer-1 memo guard (`subs.memo/make-layer-1-memoised-body`, `(= @last-db
  db)`) on the React-hook spine's FLUSH path. A body must never re-run on
  value-equal input (Spec 006 §Invalidation algorithm; Spec 009 §`:rf.sub/skip`),
  so no fast path may assume the guard always misses on `flush!`.

  The guard asks whether the input moved since THE WRAPPER LAST RAN, not since
  the source last notified, and three reachable cases make it HIT on the flush
  path: a `:frame-state` sub reading the raw container, whose fan-out is not
  movement-gated, on any value-equal commit; and for a `:db` sub, (a) a deref
  interleaved between the move and the queued flush, which leaves `last-db` at
  the new value, and (b) a return to an `=` value within one drain. The
  movement witness may only skip comparisons whose answer it already knows, so
  each of these must keep its body-run count. A plain value-equal commit never
  reaches a `:db` sub's guard at all: the app-db projection's `rf=` gate stops it.

  The interleavings are driven from a watcher on the app-db projection, which
  runs inside its `notify` fan-out, after the witness is armed and before the
  drain reaches the sub's queued flush.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.spine :as rf.substrate.spine]
            [re-frame.test-support :as rf.test-support]))

;; A test-local React-hook spine with inert hook stubs: it mounts nothing, and
;; `dispatch-sync` / `subscribe` drive the real epoch scheduler and `flush!`.
;; Core cannot require an adapter artefact.
(def ^:private spine-adapter
  (rf.substrate.spine/make-react-adapter
    (rf.substrate.spine/make-react-spine
      {:substrate-name        "gncxk-flush-path"
       :gensym-prefix-sub     "gncxk-sub-"
       :gensym-prefix-derived "gncxk-derived-"
       :gensym-prefix-use-sub "gncxk-use-sub-"
       :use-memo              (fn [t _] (t))
       :use-callback          (fn [t _] t)
       :use-context           (fn [_] nil)})
    {:kind :rf.adapter/gncxk-flush-path :frame-provider nil}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter spine-adapter}))

(defn- seed-events! []
  (rf/reg-event :seed   (fn [_ _] {:db {:n 42 :other :a}}))
  ;; A FRESH map `=` to the seeded one.
  (rf/reg-event :reseed (fn [_ _] {:db {:n 42 :other :a}}))
  (rf/reg-event :bump   (fn [{:keys [db]} _] {:db (assoc db :n 43)}))
  (rf/dispatch-sync [:seed]))

(deftest db-sub-value-equal-commit-never-reaches-the-guard
  (rf/with-frame :rf/default
    (let [runs (atom 0)]
      (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
      (seed-events!)
      (let [r (rf/subscribe [:n])]
        @r
        (rf/dispatch-sync [:reseed])
        (is (= [42 1] [@r @runs]) "a value-equal commit does not re-run the body")
        (rf/dispatch-sync [:bump])
        (is (= [43 2] [@r @runs]) "a real move does")))))

(deftest frame-state-sub-guard-does-hit-on-the-flush-path
  ;; The commit installs a FRESH frame-state (the new app-db is a different
  ;; object), the raw container marks this sub dirty unconditionally, and
  ;; `flush!` reaches the wrapper with a value-equal input.
  (rf/with-frame :rf/default
    (let [runs (atom 0)]
      (rf.subs/reg-frame-state-sub
        :whole-state
        (fn [frame-state _] (swap! runs inc) (:n (:rf.db/app frame-state))))
      (seed-events!)
      (let [r (rf/subscribe [:whole-state])]
        @r
        (rf/dispatch-sync [:reseed])
        (is (= [42 1] [@r @runs]) "the guard suppressed the body on the flush path")
        (rf/dispatch-sync [:bump])
        (is (= [43 2] [@r @runs]) "a real move recomputes, so the sub is live")))))

(deftest db-sub-guard-hits-on-the-flush-path-when-a-deref-interleaves
  ;; (a) DEREF-BETWEEN. After the interleaved read `last-db` holds the new db
  ;; while the witness's departure value is the old one, so the witness cannot
  ;; prove the answer and the `=` walk runs, and hits.
  (rf/with-frame :rf/default
    (let [runs (atom 0)]
      (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
      (seed-events!)
      (let [r     (rf/subscribe [:n])
            proj  (rf.frame/app-db-container :rf/default)
            reads (atom 0)]
        @r
        (add-watch proj ::deref-between (fn [_ _ _ _] (swap! reads inc) @r))
        (try (rf/dispatch-sync [:bump])
             (finally (remove-watch proj ::deref-between)))
        ;; `reads` proves the interposition fired; without it a plain flush
        ;; would also run the body twice in all.
        (is (= [1 43 2] [@reads @r @runs]) "ONE body run for one movement")))))

(deftest db-sub-guard-hits-on-the-flush-path-on-a-return-to-equal
  ;; (b) RETURN-TO-EQUAL. A second write from inside the projection's fan-out
  ;; returns app-db to a fresh `=` value before the drain reaches the sub's
  ;; queued flush; its re-entrant epoch cannot drain, so the two writes
  ;; coalesce into one flush. That second `mark-dirty!` retracts the witness,
  ;; so the wrapper walks rather than trusting a stale departure value.
  (rf/with-frame :rf/default
    (let [runs (atom 0)]
      (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
      (seed-events!)
      (let [r       (rf/subscribe [:n])
            proj    (rf.frame/app-db-container :rf/default)
            returns (atom 0)]
        @r
        (add-watch proj ::return-to-equal
                   (fn [_ _ _ _]
                     (when (zero? @returns)
                       (swap! returns inc)
                       (rf.frame/replace-app-db! :rf/default {:n 42 :other :a}))))
        (try (rf/dispatch-sync [:bump])
             (finally (remove-watch proj ::return-to-equal)))
        (is (= [1 42 1] [@returns @r @runs]) "the body never re-ran across A -> B -> A'")))))

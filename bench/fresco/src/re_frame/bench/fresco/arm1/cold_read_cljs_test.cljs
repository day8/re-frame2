(ns re-frame.bench.fresco.arm1.cold-read-cljs-test
  "THE COLD PROBE'S OWN CONTRACT.

  `read-key!`'s cold branch follows the cold-probe discipline: reuse a
  live sub-cache reaction by deref alone, else compute pure against one
  render-scoped frame-state snapshot through one render-scoped memo — no
  reaction build, no cache insert, no in-tick evict, no dispose cascade
  per read. The read profile (`read_profile_app.cljs`) prices the
  discipline; this file pins what it must keep true, and every row goes
  red when the code it guards is mutated.

  Deliberate, stated so nobody rediscovers it as a bug: within ONE body
  run a cold key computes ONCE and every read of it observes ONE
  frame-state snapshot. Recomputing per read against the live frame
  would differ only through an impure sub body (sub bodies are pure by
  contract) or across a mid-body commit, where the generation fence
  re-runs the body either way.

  The wiring hazards this file does NOT own have their own witnesses:
  the staged-read tear (`staged_read_tear_cljs_test` — the probe touches
  neither `make-snapshot` nor the basis arithmetic), the deferred-read
  escape and the map-key crossing (`deferred_read_…`,
  `boundary_crossing_…` — codec-side), the disposed cell and the first
  registration (`disposed_cell_…`, `first_registration_…` — both drive
  their guards THROUGH the cold path this file pins)."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.subs :as rf.subs]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(defn- make-frame! [id db]
  (rf.live-frame/make-frame {:id id})
  (rf.frame/replace-app-db! id db)
  id)

(def ^:private !runs
  "How many times the counted sub's body ran. A test instrument, not a
  contract — sub bodies are pure by contract, and this one is pure in
  everything but the count."
  (volatile! 0))

(defn- reg-counted! [qid]
  (vreset! !runs 0)
  (rf/reg-sub qid (fn [db _] (vswap! !runs inc) (:v db))))

(defn- capture-errors
  "Run `thunk` with the always-on error listener attached; answer the
  captured records."
  [thunk]
  (let [records (volatile! [])]
    (rf.error-emit/register-error-listener! ::cold-read
                                         (fn [r] (vswap! records conj r)))
    (try (thunk)
         (finally (rf.error-emit/unregister-error-listener! ::cold-read)))
    @records))

;; ---------------------------------------------------------------------------
;; Rung 2 — the pure compute, and its render-scoped lifetime
;; ---------------------------------------------------------------------------

(deftest one-run-computes-a-cold-key-once-against-one-snapshot
  ;; Two reads of one cold key in one body run are one compute and one
  ;; value. The sub is registered in the same tick as the read, so this is
  ;; also the register-then-read-sync guarantee kept on the cold path.
  (let [f (make-frame! ::once {:v 7})
        a (volatile! nil)
        b (volatile! nil)]
    (reg-counted! :coldread/once)
    (rf.bench.fresco.arm1.runtime/render-body f (fn [_]
                                                  (vreset! a (rf.bench.fresco.arm1.runtime/sub [:coldread/once]))
                                                  (vreset! b (rf.bench.fresco.arm1.runtime/sub [:coldread/once]))
                                                  [:li])
                                              {})
    (is (= [7 7 1] [@a @b @!runs]))))

(deftest a-later-render-computes-against-the-current-db
  ;; The probe box is render-scoped: a box surviving the run would answer 1
  ;; on the second render.
  (let [f    (make-frame! ::fresh {:v 1})
        seen (volatile! nil)
        body (fn [_] (vreset! seen (rf.bench.fresco.arm1.runtime/sub [:coldread/fresh])) [:li])]
    (reg-counted! :coldread/fresh)
    (rf.bench.fresco.arm1.runtime/render-body f body {})
    (let [first-seen @seen]
      (rf.frame/replace-app-db! f {:v 2})
      (rf.bench.fresco.arm1.runtime/render-body f body {})
      (is (= [1 2 2] [first-seen @seen @!runs])))))

(deftest a-cold-read-leaves-the-world-as-it-found-it
  ;; No cache entry, reference, cell or edge: an abandoned render needs no
  ;; cleanup because nothing happened.
  (let [f      (make-frame! ::clean {:v 3})
        ledger #(select-keys (rf.bench.fresco.arm1.runtime/stats) [:cells :cell-refs :boundaries :edges])]
    (reg-counted! :coldread/clean)
    (let [before (ledger)]
      (rf.bench.fresco.arm1.runtime/render-body f (fn [_] (rf.bench.fresco.arm1.runtime/sub [:coldread/clean]) [:li]) {})
      (is (= [before 0] [(ledger) (count @(:sub-cache (rf.frame/frame f)))])))))

;; ---------------------------------------------------------------------------
;; Rung 1 — the live-reaction reuse, by deref alone
;; ---------------------------------------------------------------------------

(deftest a-live-sub-cache-reaction-is-reused-without-recompute-or-churn
  ;; A key an outside holder keeps warm is read by deref alone: no
  ;; recompute, the same reaction, the same ref-count.
  (let [f (make-frame! ::reuse {:v 11})]
    (reg-counted! :coldread/reuse)
    (let [held         (rf.subs/subscribe [:coldread/reuse] {:frame f})
          _            @held
          cache        (:sub-cache (rf.frame/frame f))
          entry-before (get @cache [:coldread/reuse])
          seen         (volatile! nil)]
      (rf.bench.fresco.arm1.runtime/render-body f (fn [_] (vreset! seen (rf.bench.fresco.arm1.runtime/sub [:coldread/reuse])) [:li]) {})
      (let [entry-after (get @cache [:coldread/reuse])]
        (is (= [11 1 true (:ref-count entry-before)]
               [@seen @!runs (identical? (:reaction entry-before) (:reaction entry-after)) (:ref-count entry-after)])))
      (rf.subs/unsubscribe held))))

;; ---------------------------------------------------------------------------
;; The error contract the probe must keep
;; ---------------------------------------------------------------------------

(deftest a-cold-unregistered-read-emits-no-such-sub-once-and-recovers-nil
  ;; The probe's memo is seeded with `rf.subs/observation-opts-key`, so an
  ;; unregistered cold read emits the always-on `:rf.error/no-such-sub` as
  ;; the reactive build does, once per distinct query per run.
  (let [f    (make-frame! ::unreg {:v 1})
        seen (volatile! :unread)
        records
        (capture-errors
          (fn []
            (rf.bench.fresco.arm1.runtime/render-body f (fn [_]
                                                          (vreset! seen (rf.bench.fresco.arm1.runtime/sub [:coldread/nope]))
                                                          (rf.bench.fresco.arm1.runtime/sub [:coldread/nope])
                                                          [:li])
                                                      {})))]
    (is (= [nil [:coldread/nope]]
           [@seen (mapv :event-id (filterv #(= :rf.error/no-such-sub (:error %)) records))]))))

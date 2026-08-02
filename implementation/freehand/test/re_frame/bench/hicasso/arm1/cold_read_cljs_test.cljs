(ns re-frame.bench.hicasso.arm1.cold-read-cljs-test
  "THE COLD PROBE'S OWN CONTRACT (rf2-6c237).

  rf2-6c237 rebuilt `read-key!`'s cold branch on the observation port's
  cold-probe discipline: reuse a live sub-cache reaction by deref alone,
  else compute pure against one render-scoped frame-state snapshot
  through one render-scoped memo — no reaction build, no cache insert,
  no in-tick evict, no dispose cascade per read. The read profile
  (`read_profile_app.cljs`) prices the change; this file pins what the
  change must keep true, and every row was proven able to go red by
  mutating the code it guards (the mutation ledger is in the PR and the
  studio page).

  What the probe changed on purpose, stated so nobody rediscovers it as
  a bug: within ONE body run a cold key computes ONCE and every read of
  it observes ONE frame-state snapshot. The predecessor recomputed per
  read against the live frame — a difference observable only through an
  impure sub body (sub bodies are pure by contract) or across a mid-body
  commit, where the generation fence re-runs the body either way.

  The wiring hazards this file does NOT own are owned where they were
  fixed: the staged-read tear (`staged_read_tear_cljs_test` — the probe
  touches neither `make-snapshot` nor the basis arithmetic), the
  deferred-read escape and the map-key crossing (`deferred_read_…`,
  `boundary_crossing_…` — codec-side, untouched), the disposed cell and
  the first registration (`disposed_cell_…`, `first_registration_…` —
  both drive their repairs THROUGH the cold path this file pins, and
  both stay green over it)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.subs :as subs]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
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
    (error-emit/register-error-listener! ::cold-read
                                         (fn [r] (vswap! records conj r)))
    (try (thunk)
         (finally (error-emit/unregister-error-listener! ::cold-read)))
    @records))

;; ---------------------------------------------------------------------------
;; Rung 2 — the pure compute, and its render-scoped lifetime
;; ---------------------------------------------------------------------------

(deftest one-run-computes-a-cold-key-once-against-one-snapshot
  (testing "two reads of one cold key in one body run are one compute and
           one value: the probe's memo is shared by the whole run, which
           is the fence's one-commit invariant stated smaller"
    (let [f (make-frame! ::once {:v 7})
          a (volatile! nil)
          b (volatile! nil)]
      (reg-counted! :coldread/once)
      (rt/render-body f (fn [_]
                          (vreset! a (rt/sub [:coldread/once]))
                          (vreset! b (rt/sub [:coldread/once]))
                          [:li])
                      {})
      (is (= 7 @a))
      (is (= 7 @b) "one value for one run")
      (is (= 1 @!runs)
          "one compute: the second read is a memo hit, not a second
           subscribe-once round trip"))))

(deftest a-later-render-computes-against-the-current-db
  (testing "the probe box is render-scoped: run-once resets it, so a later
           render's cold reads compute against the db that is current
           THEN, never a stale snapshot"
    (let [f    (make-frame! ::fresh {:v 1})
          seen (volatile! nil)
          body (fn [_] (vreset! seen (rt/sub [:coldread/fresh])) [:li])]
      (reg-counted! :coldread/fresh)
      (rt/render-body f body {})
      (is (= 1 @seen))
      (frame/replace-app-db! f {:v 2})
      (rt/render-body f body {})
      (is (= 2 @seen)
          "the second render minted a fresh snapshot and a fresh memo —
           a probe box surviving the run would answer 1 here")
      (is (= 2 @!runs) "and each run computed exactly once"))))

(deftest a-cold-read-leaves-the-world-as-it-found-it
  (testing "the probe mutates nothing, transiently or otherwise: no cache
           entry, no reference, no cell, no edge — an abandoned render
           needs no cleanup because nothing happened"
    (let [f (make-frame! ::clean {:v 3})]
      (reg-counted! :coldread/clean)
      (let [before (rt/stats)]
        (rt/render-body f (fn [_] (rt/sub [:coldread/clean]) [:li]) {})
        (let [after (rt/stats)]
          (is (= (:cells before) (:cells after)) "no cell built")
          (is (= (:cell-refs before) (:cell-refs after)) "no reference taken")
          (is (= (:boundaries before) (:boundaries after)) "no boundary registered")
          (is (= (:edges before) (:edges after)) "no edge added"))
        (is (zero? (count @(:sub-cache (frame/frame f))))
            "and the frame's sub-cache holds nothing — where the
             subscribe-once crossing paid an insert and an evict per read,
             the probe never touched it")))))

;; ---------------------------------------------------------------------------
;; Rung 1 — the live-reaction reuse, by deref alone
;; ---------------------------------------------------------------------------

(deftest a-live-sub-cache-reaction-is-reused-without-recompute-or-churn
  (testing "a key some outside holder keeps warm is read by deref alone:
           no recompute, no ref-count round trip, the holder's entry
           untouched"
    (let [f (make-frame! ::reuse {:v 11})]
      (reg-counted! :coldread/reuse)
      ;; The outside holder — a tool, a test, another runtime. One build.
      (let [held (subs/subscribe [:coldread/reuse] {:frame f})
            _    @held
            runs-after-hold @!runs
            cache (:sub-cache (frame/frame f))
            entry-before (get @cache [:coldread/reuse])
            seen (volatile! nil)]
        (is (= 1 runs-after-hold))
        (rt/render-body f (fn [_] (vreset! seen (rt/sub [:coldread/reuse])) [:li]) {})
        (is (= 11 @seen) "the cold read answered the held reaction's value")
        (is (= 1 @!runs)
            "by deref alone: the probe's first rung reused the live
             reaction, so the sub body did not run again")
        (let [entry-after (get @cache [:coldread/reuse])]
          (is (identical? (:reaction entry-before) (:reaction entry-after))
              "the same reaction, not a rebuild")
          (is (= (:ref-count entry-before) (:ref-count entry-after))
              "and the same ref-count — no acquire/release churn"))
        (subs/unsubscribe f [:coldread/reuse])))))

;; ---------------------------------------------------------------------------
;; The error contract the probe must keep
;; ---------------------------------------------------------------------------

(deftest a-cold-unregistered-read-emits-no-such-sub-once-and-recovers-nil
  (testing "the probe's memo is seeded with the observation port's opts
           key, so an unregistered cold read emits the always-on
           `:rf.error/no-such-sub` exactly as the reactive build does —
           and the memo dedupes, so two reads of the same unknown query
           in one run emit once"
    (let [f    (make-frame! ::unreg {:v 1})
          seen (volatile! :unread)
          records
          (capture-errors
            (fn []
              (rt/render-body f (fn [_]
                                  (vreset! seen (rt/sub [:coldread/nope]))
                                  (rt/sub [:coldread/nope])
                                  [:li])
                              {})))]
      (is (nil? @seen) "recovered to nil, the contract unchanged")
      (is (= 1 (count (filterv #(= :rf.error/no-such-sub (:error %)) records)))
          "one emission for one distinct unknown query per run")
      (is (= :coldread/nope
             (:event-id (first (filterv #(= :rf.error/no-such-sub (:error %)) records))))
          "attributed to the query that carried it"))))

(deftest a-same-tick-registration-is-visible-to-the-very-next-cold-read
  (testing "the probe computes inside `call-with-frame-resolution`, whose
           read-time coalesced flush makes a `reg-sub` issued earlier in
           this same tick visible to this very read — the substrate's
           register-then-read-sync guarantee, kept on the cold path"
    (let [f    (make-frame! ::sametick {:v 5})
          seen (volatile! :unread)]
      ;; Registered AFTER the frame exists, read in the SAME tick.
      (reg-counted! :coldread/sametick)
      (rt/render-body f (fn [_] (vreset! seen (rt/sub [:coldread/sametick])) [:li]) {})
      (is (= 5 @seen)
          "the very next cold read resolved the handler registered this
           tick — a probe that skipped the resolution seam's flush could
           compute against a stale projection"))))

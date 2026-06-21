(ns re-frame.epoch-attribution-test
  "Standing epoch-attribution suite — the comprehensive guard for the
  per-cascade / per-mount attribution surface (Spec 009 §Instrumentation,
  Tool-Pair §Time-travel).

  WHY THIS NS EXISTS — three near-identical attribution escapes shipped to
  CI for the SAME structural reason: no test asserted per-cascade / per-mount
  attribution across ≥2 distinct cascades. Each was caught in production
  (Xray's Views / Reactive panels), fixed in isolation, and shipped with its
  own inline test:

    rf2-qs6dl (#1935) — a `:rf.view/render` (`:rf.view/rendered`) fired at React
                        COMMIT time, AFTER its causing cascade settled, landed
                        in the now-empty buffer, and was harvested by the NEXT
                        cascade's settle — every render mis-attributed by one
                        epoch. Xray showed a `counter-inc` epoch rendering
                        `title-view`, which is impossible.
    rf2-wi900 (#1938) — the SUBS sibling. A reactive `:rf.sub/run` recomputes
                        lazily at React deref time, post-settle, same lag — so
                        a `counter-inc 1→2` epoch reported the counter sub's
                        PRIOR value. The `:rf.sub/value-changed?` / `:rf.sub/cause-sub`
                        attribution landed on the wrong epoch.
    rf2-vh1k3 (#1939) — a late MOUNT render whose commit lands AFTER the first
                        user interaction settled was back-filled onto that
                        first cascade (rf2-qs6dl's most-recently-settled
                        anchor) — so a freshly-mounted view spuriously appeared
                        in the first post-mount cascade's RENDERED list even
                        though its subs never changed.

  These three are ONE surface: \"which epoch does a post-settle async emit
  (sub-run / render / mount render) belong to?\" This suite consolidates the
  per-fix tests and generalizes them into invariant-keyed cases so the next
  regression of the SAME class is caught by CI.

  THE POST-SETTLE-EMIT SIMULATION TECHNIQUE — the bug is a TIMING bug: the
  emit fires OUTSIDE the in-flight cascade, after `settle!` already harvested
  + committed the record. In a synchronous JVM `dispatch-sync`, every trace
  emits INSIDE the cascade, so the lag is structurally unreproducible — which
  is exactly why every prior test missed it. We reproduce the real
  React-commit / React-deref timing directly: dispatch a cascade (it settles
  and commits its record), THEN emit a `:rf.sub/run` / `:rf.view/render` via
  `trace/emit!` with NO `*handler-scope*` and an empty in-flight buffer —
  precisely what `capture-event!` sees when Reagent flushes a batched
  re-render / reaction recompute after the drain. The runtime's back-fill
  (`capture/capture-event!` → `state/back-fill-*!` / `resolve-render-epoch`)
  then attributes the emit to the cascade that CAUSED it. No browser needed;
  the simulation isolates the attribution logic the three fixes turn on.

  INVARIANTS COVERED (each `deftest` is keyed `inv-N-...` to the invariant
  it guards; each FAILS if its corresponding fix is reverted):

    inv-1  `:rf.sub/run` attributed to its OWN cascade across ≥2 distinct
           cascades (rf2-wi900).
    inv-2  `:rf.view/rendered` / `:rf.view/render` attributed to its CAUSING
           cascade, not the commit-time / next epoch (rf2-qs6dl).
    inv-3  a late MOUNT render attributed to the mount/initialise epoch ONLY,
           not double-filed onto the first post-mount cascade (rf2-vh1k3).
    inv-4  `:rf.sub/value-changed?` + `:rf.sub/cause-sub` land on the correct epoch
           (rf2-wi900 / rf2-l1jz8).
    inv-5  a view re-render whose own subs did NOT change is NOT spuriously
           attributed (the rf2-vh1k3 value-change-per-view discriminator).
    inv-6  an out-of-cascade ORPHAN emit (`:rf.frame/created` and the general
           class) stays UNCORRELATED — never a new epoch, never folded into
           the NEXT dequeued event's `:trace-events` (rf2-avvwm, a P1
           regression from #1952's per-event epoch boundary). The fourth
           out-of-cascade-emit-attribution member:
           unlike the post-settle render / sub-run / mount cases (back-filled
           to their CAUSING cascade), an orphan belongs to NO cascade and is
           dropped at the capture seam.
    inv-7  a tool / inspector frame's OWN render (a view rendered under a
           `:rf.trace/frame-no-emit? true` frame) never lands in the INSPECTED
           app frame's epoch `:renders` — the frame-no-emit gate suppresses the
           render-trace emit at source, so no back-fill ever runs (rf2-tqlmq,
           the cross-frame / observer sibling of inv-3). The defect: Xray's
           own `shell-view` rendered ONE LEVEL ABOVE its `:rf/xray`
           frame-provider, so its `:rf.view/rendered` carried `:frame :rf/default`
           (fall-through) and back-filled into the inspected app's boot epoch.
           The fix wraps `shell-view` in the frame-provider AT THE MOUNT so its
           render resolves to the trace-disabled frame; this invariant pins that
           the gate then suppresses the emit (so the observer cannot pollute the
           observed tape).

  Supporting cases pin the boundary behaviour each fix also relies on:
  in-flight emits ride their own cascade (not back-filled); the back-fill
  re-fans the corrected record to listeners (Xray caches at settle time);
  an orphan emit before any cascade is a silent no-op; mount-burst tails are
  de-duped; a genuine re-render never collapses back to the mount epoch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            ;; `trace` + `state` are used in test BODIES (`trace/emit!`,
            ;; `trace/frame-trace-disabled?`, the `state/buffer-*!` /
            ;; `state/*-mount-attribution!` private-helper exercises) —
            ;; NOT for fixture config reset.
            [re-frame.trace :as trace]
            [re-frame.elision]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.state :as state]
            [re-frame.test-support :as test-support]
            [re-frame.machines]))

;; ---- fixture ---------------------------------------------------------------
;;
;; rf2-yw1w1u — canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, and fires the epoch
;; reset-hook table (history / listeners / config-to-default) so the
;; shared attribution atoms (`state/last-settled-epoch`,
;; `mount-attribution`, the per-frame ring) start clean each test.
;;
;; The `:init-fn` adds two suite-specific steps the shared fixture
;; doesn't own:
;;   - `(rf/configure! {:epoch-history {:trace-events-keep 5}})` — the
;;     suite's non-default keep (NOT the shipped 50 = :depth; Mike
;;     pair-debug 2026-05-27), through the public boundary so no test ns
;;     reaches into the private `state/config` var.
;;   - `(trace/clear-frame-no-emit!)` — rf2-tqlmq: the per-frame
;;     trace-emission gate (`:rf.trace/frame-no-emit?`) is process-sticky
;;     and NOT a reset-hook-table row; inv-7 registers a trace-disabled
;;     observer frame, so clear the set between tests.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (rf/configure! {:epoch-history {:trace-events-keep 5}})
                (trace/clear-frame-no-emit!))}))

;; ---- shared post-settle-emit fixture --------------------------------------
;;
;; The single technique the whole suite turns on: emit a trace the way the
;; substrate does at React commit / deref time — op carrying its `:frame`
;; tag, fired OUTSIDE any cascade (empty in-flight buffer, no
;; `*handler-scope*`). `capture-event!`'s post-settle branch then back-fills
;; it into the causing epoch. Factor the emits so cases stay terse.

(defn- emit-render!
  "Emit a `:rf.view/rendered` at React-COMMIT timing — op-type `:rf.view`,
  tags carrying `:rf.view/render-key` + `:frame`, fired post-settle (empty
  buffer). Mirrors the POST-render `:rf.view/rendered` emit, which is what
  the `:renders` projection sources from (rf2-8wrzz.1 — the op that carries
  per-view cause + timing). Pass a `view-id` (canonical `[view-id 0]`
  render-key) or a full render-key tuple."
  [frame-id view-or-rk]
  (let [render-key (if (vector? view-or-rk) view-or-rk [view-or-rk 0])]
    (trace/emit! :rf.view :rf.view/rendered
                 {:rf.view/render-key render-key
                  :frame      frame-id})))

(defn- emit-unmount!
  "Emit a `:rf.view/unmounted` at React-TEARDOWN timing — op-type `:rf.view`,
  tags carrying `:rf.view/id` + `:rf.view/render-key` + `:frame`, fired
  post-settle (empty buffer, no `:rf.trace/dispatch-id`). Mirrors
  `re-frame.views/emit-view-unmounted!`, which fires at React
  componentWillUnmount / useEffect cleanup AFTER the cascade that removed
  the view settled (rf2-59hx3). Pass a `view-id` (canonical `[view-id 0]`
  render-key) or a full render-key tuple."
  [frame-id view-or-rk]
  (let [render-key (if (vector? view-or-rk) view-or-rk [view-or-rk 0])
        view-id    (first render-key)]
    (trace/emit! :rf.view :rf.view/unmounted
                 {:rf.view/id         view-id
                  :rf.view/render-key render-key
                  :frame              frame-id})))

(defn- emit-sub-run!
  "Emit a reactive `:rf.sub/run` at React-DEREF timing — op-type `:rf.sub/run`,
  tags carrying `:rf.sub/id` + `:rf.sub/query-v` + `:frame` plus the rf2-l1jz8
  value-change attribution, fired post-settle (empty buffer, NO
  `:rf.sub/reader-render-key` — a post-settle reactive recompute fires outside any
  render binding). Mirrors `re-frame.subs.memo/validate-and-trace`. Optional
  `:rf.sub/cause-sub` for the cascade-attribution slot."
  ([frame-id sub-id prev-value value]
   (emit-sub-run! frame-id sub-id prev-value value nil))
  ([frame-id sub-id prev-value value cause-sub]
   (trace/emit! :rf.sub :rf.sub/run
                {:rf.sub/id         sub-id
                 :rf.sub/query-v        [sub-id]
                 :frame          frame-id
                 :rf.sub/value-changed? (not= prev-value value)
                 :rf.sub/prev-value     prev-value
                 :rf.sub/value          value
                 :rf.sub/cascade?       (some? cause-sub)
                 :rf.sub/cause-sub      cause-sub})))

(defn- emit-mount-sub-run!
  "Emit a `:rf.sub/run` at MOUNT timing — the SYNCHRONOUS in-render deref at
  first-paint. `*render-key*` is bound on that path, so the runtime stamps
  `:rf.sub/reader-render-key` (the rf2-vh1k3 read-set-learning signal that teaches
  which subs the view reads). The first recompute always reports
  value-changed? true."
  [frame-id sub-id reader-rk prev-value value]
  (trace/emit! :rf.sub :rf.sub/run
               {:rf.sub/id            sub-id
                :rf.sub/query-v           [sub-id]
                :frame             frame-id
                :rf.sub/value-changed?    (not= prev-value value)
                :rf.sub/prev-value        prev-value
                :rf.sub/value             value
                :rf.sub/cascade?          false
                :rf.sub/cause-sub         nil
                :rf.sub/reader-render-key reader-rk}))

;; ---- record-reading helpers -----------------------------------------------

(defn- epoch-by-id
  "Re-read the frame's ring (back-fills mutate in place) and pull the record
  whose `:epoch-id` matches `epoch-id`'s."
  [frame-id epoch-id]
  (some #(when (= (:epoch-id epoch-id) (:epoch-id %)) %)
        (rf/epoch-history frame-id)))

(defn- last-epoch [frame-id] (last (rf/epoch-history frame-id)))

(defn- rendered-view-ids
  "The view-ids present in an epoch record's `:renders` projection."
  [record]
  (->> (:renders record) (map (comp first :render-key)) set))

(defn- rendered-keys
  "The full render-key tuples present in an epoch record's `:renders`."
  [record]
  (->> (:renders record) (map :render-key) set))

(defn- sub-run-ids
  "The sub-ids present in an epoch record's `:sub-runs` projection."
  [record]
  (->> (:sub-runs record) (map :sub-id) set))

(defn- sub-run-for
  "The `:sub-runs` entry for `sub-id` in `record`, or nil."
  [record sub-id]
  (->> (:sub-runs record) (filter #(= sub-id (:sub-id %))) first))

;; Two stable render-key tuples for the mount-attribution cases.
(def ^:private cv-rk "counter-view render-key." [:counter-view 6])
(def ^:private tv-rk "title-view render-key."   [:title-view 7])

;; ===========================================================================
;; INVARIANT 1 — :rf.sub/run attributed to its OWN cascade across ≥2 cascades
;;                (rf2-wi900)
;; ===========================================================================

(deftest inv-1-sub-run-attributed-to-its-own-cascade-multi-cascade
  (testing "rf2-wi900 — a sub-run that fires AFTER its cascade settled
            (React-deref timing) is attributed to the cascade that CAUSED it,
            not the next in-flight cascade. Two cascades that recompute
            DIFFERENT subs must each carry their OWN sub-run. THE multi-cascade
            assertion the whole class of escapes was missing — pre-fix A's
            sub-run leaked into B's epoch (the one-epoch lag)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed         (fn [{:keys [db]} _] {:db {:title "a" :counter 0}}))
    (rf/reg-event :title-loaded (fn [{:keys [db]} _] {:db (assoc db :title "loaded")}))
    (rf/reg-event :counter-inc  (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})

    ;; Cascade A — a title refresh. It settles, THEN (next tick, React deref)
    ;; the :title sub recomputes "a" → "loaded".
    (rf/dispatch-sync [:title-loaded] {:frame :test/main})
    (let [epoch-a (last-epoch :test/main)]
      (emit-sub-run! :test/main :title "a" "loaded")

      ;; Cascade B — a counter bump. It settles, THEN (and ONLY) the :counter
      ;; sub recomputes 0 → 1 post-settle — counter-inc cannot make :title
      ;; recompute.
      (rf/dispatch-sync [:counter-inc] {:frame :test/main})
      (let [epoch-b (last-epoch :test/main)]
        (emit-sub-run! :test/main :counter 0 1)

        (let [a (epoch-by-id :test/main epoch-a)
              b (epoch-by-id :test/main epoch-b)]
          (is (= :title-loaded (:event-id a)))
          (is (= :counter-inc  (:event-id b)))

          (is (contains? (sub-run-ids a) :title)
              "cascade A carries its OWN :title sub-run")
          (is (not (contains? (sub-run-ids a) :counter))
              "cascade A does NOT carry cascade B's :counter sub-run")
          (is (contains? (sub-run-ids b) :counter)
              "cascade B carries its OWN :counter sub-run")
          (is (not (contains? (sub-run-ids b) :title))
              "cascade B does NOT carry cascade A's lagged :title sub-run —
               THE LAG IS GONE"))))))

(deftest inv-1-in-flight-sub-run-rides-current-cascade
  (testing "rf2-wi900 — a sub-run that fires WITH a cascade in flight
            (synchronous deref — a handler that subscribes, an SSR render)
            belongs to that cascade and is buffered normally, NOT back-filled.
            Pins that the post-settle back-fill does not poach in-flight
            sub-runs."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :sub-during
      (fn [{:keys [db]} _]
        (trace/emit! :rf.sub :rf.sub/run
                     {:rf.sub/id :inline-sub :rf.sub/query-v [:inline-sub]
                      :frame :test/main :rf.sub/value-changed? true
                      :rf.sub/prev-value nil :rf.sub/value :computed
                      :rf.sub/cascade? false :rf.sub/cause-sub nil})
        {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:sub-during] {:frame :test/main})

    (let [epoch (last-epoch :test/main)]
      (is (= :sub-during (:event-id epoch)))
      (is (contains? (sub-run-ids epoch) :inline-sub)
          "an in-flight sub-run rides its own cascade (buffered, not
           back-filled to a prior settled epoch)"))))

(deftest inv-1-orphan-sub-run-before-any-cascade-is-noop
  (testing "rf2-wi900 — a sub-run that fires before any cascade has settled
            (no last-settled epoch for the frame) is a silent no-op: no record
            materialises, no listener fan-out, no throw."
    (rf/reg-frame :test/main {})
    (let [seen (atom [])]
      (rf/register-epoch-listener! ::watcher (fn [r] (swap! seen conj r)))
      (emit-sub-run! :test/main :orphan-sub nil :computed)
      (is (= [] (rf/epoch-history :test/main))
          "no record materialised from an orphan sub-run")
      (is (= [] @seen)
          "no listener fan-out for a sub-run with no causing cascade"))))

;; ===========================================================================
;; rf2-j1ec6.1 — :rf.epoch/sensitive? rollup recomputed on back-fill
;; ===========================================================================
;;
;; THE CONTRACT (Security.md:109 §Sensitive rollup at the record level): when
;; any path the record carries — INCLUDING `:trace-events` — overlaps a
;; sensitive slot, the record carries `:rf.epoch/sensitive? true`. Consumers
;; (off-box shippers, recorder drop-gates) branch on the boolean rollup the
;; same way they branch on the per-trace-event `:sensitive?` stamp.
;;
;; THE BUG: `build-record` computes the rollup ONCE at settle time from the
;; settle-time events. A post-settle back-fill of a `:sensitive?`-stamped
;; trace event (a sensitive reactive recompute riding React-deref timing)
;; appended a sensitive event to `:trace-events` but left the rollup
;; stale-false — so a coarse drop-gate consumer would NOT drop a record whose
;; only sensitive content arrived via back-fill.
;;
;; THE FIX (mayor-ruled option a, fail-CLOSED): the back-fill swap OR's the
;; rollup with the appended event's RAW sensitivity (the pure splice inside
;; `state/back-fill-event!`), flipping false→true.

(defn- emit-sensitive-sub-run!
  "Emit a reactive `:rf.sub/run` at React-DEREF timing carrying the
  top-level `:sensitive? true` stamp — exactly what a sensitive reactive
  recompute emits (the `:sensitive?` tag wins in `trace/compute-sensitive?`
  and is hoisted to the envelope top level). Post-settle (empty buffer, no
  render-key) so the runtime back-fills it into the most-recently-settled
  epoch (rf2-wi900 path)."
  [frame-id sub-id prev-value value]
  (trace/emit! :rf.sub :rf.sub/run
               {:rf.sub/id             sub-id
                :rf.sub/query-v        [sub-id]
                :frame                 frame-id
                :sensitive?            true
                :rf.sub/value-changed? (not= prev-value value)
                :rf.sub/prev-value     prev-value
                :rf.sub/value          value
                :rf.sub/cascade?       false
                :rf.sub/cause-sub      nil}))

(deftest sensitive-rollup-recomputed-on-back-fill
  (testing "rf2-j1ec6.1 — a post-settle back-fill of a :sensitive?-stamped
            sub-run flips the record-level :rf.epoch/sensitive? rollup
            false→true, keeping Security.md:109 literally true post-back-fill
            (the rollup considers :trace-events overlap). Pre-fix the rollup
            stayed stale-false — a coarse drop-gate consumer would not drop
            a record whose only sensitive content arrived via back-fill."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [epoch (last-epoch :test/main)]
      ;; PRECONDITION — a non-sensitive cascade settles rollup false.
      (is (false? (:rf.epoch/sensitive? epoch))
          "settle-time rollup is false (no sensitive content in the cascade)")

      ;; Post-settle: a sensitive reactive recompute back-fills into this epoch.
      (emit-sensitive-sub-run! :test/main :secret-sub nil "topsecret")

      (let [r (epoch-by-id :test/main epoch)]
        ;; The back-filled sub-run is present AND carries the sensitive stamp.
        (is (contains? (sub-run-ids r) :secret-sub)
            "the sensitive sub-run was back-filled into the causing cascade")
        (is (some #(and (= :rf.sub/run (:operation %))
                        (true? (:sensitive? %)))
                  (:trace-events r))
            "the back-filled trace event carries the :sensitive? stamp")
        ;; THE INVARIANT — the rollup was OR'd true at the back-fill swap.
        (is (true? (:rf.epoch/sensitive? r))
            "rf2-j1ec6.1 — back-filled sensitivity flips the record rollup true")))))

(deftest non-sensitive-back-fill-leaves-rollup-false
  (testing "rf2-j1ec6.1 — a back-fill of a NON-sensitive sub-run does NOT
            flip the rollup (the OR is fail-CLOSED, not fail-open): a clean
            cascade with a clean back-fill stays false."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [epoch (last-epoch :test/main)]
      (is (false? (:rf.epoch/sensitive? epoch)))
      ;; A plain (non-sensitive) reactive recompute back-fills in.
      (emit-sub-run! :test/main :plain-sub 0 1)
      (let [r (epoch-by-id :test/main epoch)]
        (is (contains? (sub-run-ids r) :plain-sub)
            "the non-sensitive sub-run was back-filled")
        (is (false? (:rf.epoch/sensitive? r))
            "rollup stays false — a non-sensitive back-fill never flips it")))))

;; ===========================================================================
;; INVARIANT 2 — :rf.view/rendered / :rf.view/render attributed to its CAUSING
;;                cascade, not the commit-time / next epoch (rf2-qs6dl)
;; ===========================================================================

(deftest inv-2-render-attributed-to-its-causing-cascade-multi-cascade
  (testing "rf2-qs6dl — a render that fires AFTER its cascade settled
            (React-commit timing) is attributed to the cascade that CAUSED it,
            not the next in-flight cascade. Two cascades that re-render
            DIFFERENT views must each carry their OWN render. Pre-fix A's
            title-view render leaked into B's epoch — a counter-inc epoch
            reporting title-view, which is IMPOSSIBLE."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed         (fn [{:keys [db]} _] {:db {:title "a" :counter 0}}))
    (rf/reg-event :title-loaded (fn [{:keys [db]} _] {:db (assoc db :title "loaded")}))
    (rf/reg-event :counter-inc  (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})

    (rf/dispatch-sync [:title-loaded] {:frame :test/main})
    (let [epoch-a (last-epoch :test/main)]
      (emit-render! :test/main :title-view)

      (rf/dispatch-sync [:counter-inc] {:frame :test/main})
      (let [epoch-b (last-epoch :test/main)]
        (emit-render! :test/main :counter-view)

        (let [a (epoch-by-id :test/main epoch-a)
              b (epoch-by-id :test/main epoch-b)]
          (is (= :title-loaded (:event-id a)))
          (is (= :counter-inc  (:event-id b)))

          (is (contains? (rendered-view-ids a) :title-view)
              "cascade A carries its OWN title-view render")
          (is (not (contains? (rendered-view-ids a) :counter-view))
              "cascade A does NOT carry cascade B's counter-view render")
          (is (contains? (rendered-view-ids b) :counter-view)
              "cascade B carries its OWN counter-view render")
          (is (not (contains? (rendered-view-ids b) :title-view))
              "cascade B does NOT carry cascade A's lagged title-view render —
               THE LAG IS GONE (a counter-inc cannot re-render title-view)"))))))

(deftest inv-2-in-flight-render-rides-current-cascade
  (testing "rf2-qs6dl — a render that fires WITH a cascade in flight
            (synchronous flush — SSR / a render inside the cascade) belongs to
            that cascade and is buffered normally, NOT back-filled."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :render-during
      (fn [{:keys [db]} _]
        (trace/emit! :rf.view :rf.view/rendered
                     {:rf.view/render-key [:inline-view 0] :frame :test/main})
        {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:render-during] {:frame :test/main})

    (let [epoch (last-epoch :test/main)]
      (is (= :render-during (:event-id epoch)))
      (is (contains? (rendered-view-ids epoch) :inline-view)
          "an in-flight render rides its own cascade (buffered, not
           back-filled to a prior settled epoch)"))))

(deftest inv-2-orphan-render-before-any-cascade-is-noop
  (testing "rf2-qs6dl — a render that fires before any cascade has settled
            (no last-settled epoch) is a silent no-op: no record, no fan-out,
            no throw."
    (rf/reg-frame :test/main {})
    (let [seen (atom [])]
      (rf/register-epoch-listener! ::watcher (fn [r] (swap! seen conj r)))
      (emit-render! :test/main :orphan-view)
      (is (= [] (rf/epoch-history :test/main))
          "no record materialised from an orphan render")
      (is (= [] @seen)
          "no listener fan-out for a render with no causing cascade"))))

;; ===========================================================================
;; INVARIANT 3 — a late MOUNT render attributed to the mount/initialise epoch
;;                ONLY, not double-filed onto the first post-mount cascade
;;                (rf2-vh1k3)
;; ===========================================================================

(deftest inv-3-late-mount-render-attributed-to-mount-epoch-only
  (testing "rf2-vh1k3 — a late MOUNT render (a freshly-mounted view whose
            render commits AFTER the next cascade settled, with UNCHANGED
            inputs) is attributed to its MOUNT epoch, NOT the cascade that
            happens to be settling. THE escape rf2-qs6dl + rf2-wi900 left:
            their multi-cascade tests re-render a DIFFERENT view each cascade,
            never a view that mounts in epoch A and commits a late mount-burst
            tail in epoch B with unchanged inputs."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    ;; MOUNT epoch: seed settles, both views mount (render + first sub
    ;; recompute, synchronous in-render deref → reader-render-key stamped →
    ;; read-set learned). The mount renders back-fill into the seed epoch.
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [mount-epoch (last-epoch :test/main)]
      (emit-render! :test/main cv-rk)
      (emit-mount-sub-run! :test/main :counter cv-rk nil 0)
      (emit-render! :test/main tv-rk)
      (emit-mount-sub-run! :test/main :title-state tv-rk nil :idle)

      ;; FIRST '+' : counter-inc settles as the next epoch.
      (rf/dispatch-sync [:counter-inc] {:frame :test/main})
      (let [inc-epoch (last-epoch :test/main)]
        ;; Post-settle commit burst. Reactive recomputes here fire OUTSIDE
        ;; any render → NO reader-render-key stamp (the live shape):
        ;;   counter-view re-renders — its ::counter sub CHANGED 0 → 1
        ;;   (genuine reactive re-render, resolved via learned read-set).
        (emit-sub-run! :test/main :counter 0 1)
        (emit-render! :test/main cv-rk)
        ;;   title-view's MOUNT render commits LATE — its ::title-state sub
        ;;   re-derefs UNCHANGED (:idle → :idle), so this is a mount-burst
        ;;   tail, NOT a counter-inc-driven re-render → anchors to mount epoch.
        (emit-sub-run! :test/main :title-state :idle :idle)
        (emit-render! :test/main tv-rk)

        (let [m (epoch-by-id :test/main mount-epoch)
              i (epoch-by-id :test/main inc-epoch)]
          (is (= :seed        (:event-id m)))
          (is (= :counter-inc (:event-id i)))

          (is (contains? (rendered-keys i) cv-rk)
              "counter-inc epoch carries counter-view's GENUINE re-render
               (its ::counter sub changed 0 → 1)")
          (is (not (contains? (rendered-keys i) tv-rk))
              "counter-inc epoch does NOT carry title-view's late mount render
               (the rf2-vh1k3 defect: pre-fix it spuriously appeared here)")

          (is (contains? (rendered-keys m) tv-rk)
              "the mount epoch carries title-view's mount render")
          (is (contains? (rendered-keys m) cv-rk)
              "the mount epoch carries counter-view's mount render"))))))

(deftest inv-3-mount-render-tail-into-mount-epoch-is-deduped
  (testing "rf2-vh1k3 — a mount-burst tail render that resolves back to its
            mount epoch (where the instance already rendered) is de-duped: it
            does not add a second :renders row for the same render-key."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (emit-render! :test/main tv-rk)
    (emit-mount-sub-run! :test/main :title-state tv-rk nil :idle)

    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    ;; Two late mount-burst tail renders, each UNCHANGED — both must resolve
    ;; to the mount epoch and be absorbed (no duplicate rows).
    (emit-sub-run! :test/main :title-state :idle :idle)
    (emit-render! :test/main tv-rk)
    (emit-render! :test/main tv-rk)

    (let [mount-epoch (first (rf/epoch-history :test/main))
          tv-rows     (->> (:renders mount-epoch)
                           (filter #(= tv-rk (:render-key %))))]
      (is (= :seed (:event-id mount-epoch)))
      (is (= 1 (count tv-rows))
          "title-view appears exactly ONCE in its mount epoch's :renders —
           the late mount-burst tail is de-duped, not appended again"))))

(deftest inv-3-genuine-re-render-of-mounted-view-rides-its-cascade
  (testing "rf2-vh1k3 — once a view has mounted, a LATER genuine re-render
            (its own inputs change in a subsequent cascade) is attributed to
            THAT cascade, not redirected back to the mount epoch. The
            mount-epoch anchor only governs mount-burst tails — it must not
            over-reach and collapse genuine re-renders."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (emit-render! :test/main cv-rk)
    (emit-mount-sub-run! :test/main :counter cv-rk nil 0)

    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [inc1 (last-epoch :test/main)]
      (emit-sub-run! :test/main :counter 0 1)
      (emit-render! :test/main cv-rk)

      (rf/dispatch-sync [:counter-inc] {:frame :test/main})
      (let [inc2 (last-epoch :test/main)]
        (emit-sub-run! :test/main :counter 1 2)
        (emit-render! :test/main cv-rk)

        (let [e1 (epoch-by-id :test/main inc1)
              e2 (epoch-by-id :test/main inc2)]
          (is (contains? (rendered-keys e1) cv-rk)
              "the first counter-inc carries counter-view's re-render")
          (is (contains? (rendered-keys e2) cv-rk)
              "the second counter-inc ALSO carries counter-view's re-render —
               a genuine re-render rides its own cascade, never collapses back
               to the mount epoch"))))))

(deftest inv-3-keep-0-render-attributed-via-sub-runs-to-current-epoch
  (testing "rf2-bhglx — with `:trace-events-keep 0` every record's raw
            `:trace-events` are elided while the structured `:sub-runs` rows are
            RETAINED (the memory/privacy posture). A genuine re-render's
            value-change evidence then lives ONLY in the newest epoch's
            `:sub-runs`. Pre-fix `value-changed-epoch-for` scanned only
            `:trace-events` and broke at the first trace-elided record (the
            NEWEST one under keep-0), so it found no value-change and the render
            was mis-attributed to the mount/default epoch. The fix consults the
            structured `:sub-runs` (learned dep + `:value-changed? true`) when
            the raw stream is absent, so the render lands on the CURRENT epoch."
    (rf/configure! {:epoch-history {:trace-events-keep 0}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    ;; Mount: seed cascade + the synchronous in-render deref that teaches the
    ;; view's read-set (`:counter`). The render-deps learning is independent of
    ;; `:trace-events-keep`, so the read-set is learned even under keep-0.
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [mount-epoch (last-epoch :test/main)]
      (emit-mount-sub-run! :test/main :counter cv-rk nil 0)
      (emit-render! :test/main cv-rk)

      ;; A later cascade genuinely changes the view's input. The value-changing
      ;; sub-run back-fills into THIS cascade's `:sub-runs` (the structured row
      ;; is appended even though the raw `:trace-events` are elided), then the
      ;; re-render commits post-settle.
      (rf/dispatch-sync [:counter-inc] {:frame :test/main})
      (let [inc-epoch (last-epoch :test/main)]
        (emit-sub-run! :test/main :counter 0 1)
        (emit-render! :test/main cv-rk)

        (let [mount-rec (epoch-by-id :test/main mount-epoch)
              inc-rec   (epoch-by-id :test/main inc-epoch)]
          ;; Precondition: the structured evidence the scan must consult is
          ;; present on the current epoch, while its raw trace stream is gone.
          (is (not (contains? inc-rec :trace-events))
              "keep-0 elided the current epoch's raw :trace-events")
          (is (some (fn [row] (and (= :counter (:sub-id row))
                                   (true? (:value-changed? row))))
                    (:sub-runs inc-rec))
              "the value-changing :counter sub-run is in the current epoch's
               structured :sub-runs")
          ;; The fix: the genuine re-render lands on the CURRENT epoch (its
          ;; cause). Pre-fix the scan found no value-change (raw traces elided,
          ;; structured rows ignored), resolved to the mount epoch where cv-rk
          ;; already had its mount render, and the re-render was DEDUP'd away —
          ;; so the current epoch carried NO cv-rk render at all.
          (is (contains? (rendered-keys inc-rec) cv-rk)
              "rf2-bhglx — the re-render is attributed to the current epoch via
               its :sub-runs value-change, even with raw traces elided")
          ;; The mount epoch carries its OWN mount render (correct), but NOT a
          ;; second one — the re-render did not also collapse onto it.
          (is (= 1 (count (filter #(= cv-rk (:render-key %)) (:renders mount-rec))))
              "the mount epoch carries exactly its mount render for cv-rk, not
               the re-render too"))))))

;; ===========================================================================
;; INVARIANT 4 — :rf.sub/value-changed? + :rf.sub/cause-sub land on the correct epoch
;;                (rf2-wi900 / rf2-l1jz8)
;; ===========================================================================

(deftest inv-4-value-changed-and-cause-sub-land-on-correct-epoch
  (testing "rf2-wi900 / rf2-l1jz8 — a post-settle sub-run's value-change
            attribution (`:rf.sub/value-changed?` / `:rf.sub/prev-value` / `:value`) AND its
            cascade attribution (`:rf.sub/cause-sub` / `:rf.sub/cascade?`) ride the cascade
            that CAUSED the recompute, not the next epoch. This is the exact
            defect Mike reproduced: a counter-inc 1→2 epoch must show the
            counter sub's NEW value (2), not the prior cascade's lagged result.
            Two cascades, distinct values + distinct cause-subs, each pinned to
            its own epoch."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})

    ;; Cascade A — counter 0 → 1. Post-settle the ::counter sub recomputes
    ;; (cascaded from a base ::raw-counter sub, so cause-sub names it).
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [epoch-a (last-epoch :test/main)]
      (emit-sub-run! :test/main :counter 0 1 :raw-counter)

      ;; Cascade B — counter 1 → 2. Same sub, different value + same cause.
      (rf/dispatch-sync [:counter-inc] {:frame :test/main})
      (let [epoch-b (last-epoch :test/main)]
        (emit-sub-run! :test/main :counter 1 2 :raw-counter)

        (let [a (epoch-by-id :test/main epoch-a)
              b (epoch-by-id :test/main epoch-b)
              a-counter (sub-run-for a :counter)
              b-counter (sub-run-for b :counter)]
          ;; Cascade A's value-change attribution: 0 → 1, in A's epoch.
          (is (= true (:value-changed? a-counter))
              "A's :counter sub-run is value-changed")
          (is (= 0 (:prev-value a-counter)) "A's :prev-value is 0")
          (is (= 1 (:value a-counter)) "A's :value is THIS cascade's result (1)")
          (is (= :raw-counter (:cause-sub a-counter))
              "A's :cause-sub names the cascading parent sub")
          (is (= true (:cascade? a-counter)) "A's sub-run is marked cascaded")

          ;; Cascade B's value-change attribution: 1 → 2, in B's epoch — NOT
          ;; the lagged prior value. The smoking gun.
          (is (= 1 (:prev-value b-counter)) "B's :prev-value is the pre-bump 1")
          (is (= 2 (:value b-counter))
              "B's :value is THIS cascade's result (2), not the lagged prior 1")
          (is (= :raw-counter (:cause-sub b-counter))
              "B's :cause-sub lands on B's epoch")

          ;; And the cross-epoch separation: neither cascade carries the
          ;; OTHER's value attribution.
          (is (not= (:value a-counter) (:value b-counter))
              "the two cascades carry DISTINCT counter values — no lag
               smearing one epoch's value onto the other"))))))

(deftest inv-4-back-fill-renotifies-listeners-with-corrected-attribution
  (testing "rf2-wi900 — back-filling a post-settle sub-run re-fans the
            corrected record out to epoch listeners so snapshot consumers
            (Xray's per-cascade Views subs table, which caches epoch-history
            at settle time) re-sync to the corrected :sub-runs +
            :rf.sub/value-changed? attribution. Without the re-fan a cached panel
            would show the stale settle-time record (the value-change absent)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [seen (atom [])]
      (rf/register-epoch-listener! ::watcher (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})
      (let [settle-fanouts (count @seen)]
        (emit-sub-run! :test/main :n 0 1)
        (is (= (inc settle-fanouts) (count @seen))
            "the back-fill triggered exactly one additional listener fan-out")
        (let [renotified (last @seen)]
          (is (= :inc (:event-id renotified))
              "the re-fanned record is the :inc cascade's (the causing epoch)")
          (is (contains? (sub-run-ids renotified) :n)
              "the re-fanned record carries the back-filled sub-run")
          (is (= 1 (:value (sub-run-for renotified :n)))
              "the re-fanned sub-run carries this cascade's value")
          (is (= true (:value-changed? (sub-run-for renotified :n)))
              "the re-fanned sub-run carries the corrected value-change flag"))))))

(deftest inv-4-render-back-fill-renotifies-listeners
  (testing "rf2-qs6dl — the render sibling of the re-fan: back-filling a
            post-settle render re-fans the corrected record so the Xray Views
            / Reactive panel re-syncs to the corrected :renders."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [seen (atom [])]
      (rf/register-epoch-listener! ::watcher (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})
      (let [settle-fanouts (count @seen)]
        (emit-render! :test/main :counter-view)
        (is (= (inc settle-fanouts) (count @seen))
            "the back-fill triggered exactly one additional listener fan-out")
        (let [renotified (last @seen)]
          (is (= :inc (:event-id renotified))
              "the re-fanned record is the :inc cascade's (the causing epoch)")
          (is (contains? (rendered-view-ids renotified) :counter-view)
              "the re-fanned record carries the back-filled render"))))))

;; ===========================================================================
;; INVARIANT 5 — a view re-render whose own subs did NOT change is NOT
;;                spuriously attributed (the rf2-vh1k3 value-change-per-view
;;                discriminator)
;; ===========================================================================

(deftest inv-5-unchanged-view-re-render-not-spuriously-attributed
  (testing "rf2-vh1k3 — the load-bearing discriminator. When a cascade
            settles and TWO views commit post-settle renders — one whose own
            sub CHANGED, one whose own subs re-deref'd UNCHANGED — only the
            value-changed view is attributed to that cascade. The unchanged
            view's render must NOT leak in (it would on the naive
            most-recently-settled anchor). This is the per-view value-change
            check, isolated from the mount path: BOTH views are already
            mounted in an earlier epoch, so the discriminator is the value
            change alone, not the mount anchor."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0 :sidebar :open}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    ;; Epoch 1: seed. Both views mount here (read-set learned).
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (emit-render! :test/main cv-rk)
    (emit-mount-sub-run! :test/main :counter cv-rk nil 0)
    (emit-render! :test/main tv-rk)
    (emit-mount-sub-run! :test/main :sidebar tv-rk nil :open)

    ;; Epoch 2: a FIRST counter-inc that both views already saw — settle it so
    ;; both views are unambiguously mounted in an EARLIER epoch than the
    ;; discriminating cascade. (counter-view re-renders here; we attribute it
    ;; cleanly so its mount/anchor history is established.)
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (emit-sub-run! :test/main :counter 0 1)
    (emit-render! :test/main cv-rk)

    ;; Epoch 3: THE discriminator. Another counter-inc. counter-view's sub
    ;; CHANGED 1 → 2; the sidebar view's sub re-deref'd UNCHANGED (:open →
    ;; :open) but its render still commits post-settle (a spurious React
    ;; re-render — e.g. a parent re-render). Only counter-view should land in
    ;; epoch 3.
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [epoch-3 (last-epoch :test/main)]
      (emit-sub-run! :test/main :counter 1 2)
      (emit-render! :test/main cv-rk)
      (emit-sub-run! :test/main :sidebar :open :open)
      (emit-render! :test/main tv-rk)

      (let [e3 (epoch-by-id :test/main epoch-3)]
        (is (= :counter-inc (:event-id e3)))
        (is (contains? (rendered-keys e3) cv-rk)
            "the value-CHANGED view (counter-view, 1 → 2) is attributed to
             epoch 3")
        (is (not (contains? (rendered-keys e3) tv-rk))
            "the value-UNCHANGED view (sidebar, :open → :open) is NOT
             spuriously attributed to epoch 3 — its render anchors elsewhere
             (its mount epoch), not this cascade")))))

;; ===========================================================================
;; INVARIANT 6 — an out-of-cascade ORPHAN emit (:rf.frame/created and the
;;                general class) stays UNCORRELATED: not a new epoch, and not
;;                folded into the next dequeued event's :trace-events
;;                (rf2-avvwm — P1 regression from #1952 epoch-per-event)
;; ===========================================================================
;;
;; The fourth member of this suite's "which epoch does an out-of-cascade emit
;; belong to?" family. The first three (renders / sub-runs / mount renders)
;; fire AFTER a cascade settled and are back-filled to their CAUSING cascade.
;; This one is different: a `:rf.frame/created` (or registry-time) emit belongs
;; to NO cascade at all — `reg-frame` runs `:on-create` via dispatch-sync
;; FIRST (which settles its own epoch), THEN emits `:rf.frame/created` with no
;; in-flight cascade and no `:rf.trace/dispatch-id`. Per Spec 009 §Dispatch correlation
;; it must stay uncorrelated. Pre-rf2-avvwm it lingered in the capture buffer
;; and the NEXT dequeued event's harvest vacuumed it in as that epoch's FIRST
;; :trace-events entry (the per-event-epoch boundary stranded it).

(defn- trace-ops
  "The [op-type operation] pairs in an epoch record's :trace-events, in
  order. nil-safe — a record whose :trace-events was elided returns []."
  [record]
  (mapv (juxt :op-type :operation) (:trace-events record)))

(deftest inv-6-frame-created-not-folded-into-next-epoch
  (testing "rf2-avvwm — :rf.frame/created, emitted by reg-frame AFTER :on-create's
            epoch already settled, must NOT appear in the NEXT dequeued event's
            :trace-events. Mirrors the parallel-frames :below repro: boot the
            frame with an :on-create, then dispatch a user event; that event's
            :trace-events must begin with its OWN ops, not [:frame
            :rf.frame/created]."
    (rf/reg-event :app/init (fn [{:keys [db]} _] {:db {:booted true :n 0}}))
    (rf/reg-event :inc      (fn [{:keys [db]} _] {:db (update db :n inc)}))
    ;; reg-frame dispatch-syncs :on-create (settles epoch 1), THEN emits the
    ;; orphan :rf.frame/created.
    (rf/reg-frame :test/main {:initial-events [[:app/init]]})
    ;; The next dequeued user event.
    (rf/dispatch-sync [:inc] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= [:app/init :inc] (mapv :event-id history))
          "exactly two epochs — :on-create and the user :inc; :rf.frame/created
           is NOT a third epoch")
      (doseq [r history]
        (is (not-any? #(= [:rf.frame :rf.frame/created] %) (trace-ops r))
            (str "no epoch's :trace-events carries the orphan :rf.frame/created — "
                 "epoch " (:event-id r))))
      (let [inc-epoch (last history)
            ops       (trace-ops inc-epoch)]
        (is (seq ops) ":inc epoch retained its raw :trace-events")
        (is (= :rf.event (first (first ops)))
            ":inc epoch's :trace-events BEGIN with its OWN event op
             (:rf.event/dispatched), not the stranded [:rf.frame :rf.frame/created]")
        (is (every? (fn [ev] (= [:inc] (-> ev :tags :rf.event/v)))
                    (filter #(= :rf.event (:op-type %)) (:trace-events inc-epoch)))
            "every :event-op trace in the :inc epoch belongs to [:inc]")))))

(deftest inv-6-orphan-not-correlated-on-the-record
  (testing "rf2-avvwm — the orphan carries no :rf.trace/dispatch-id, so no epoch's
            :trace-events should reference it. Belt-and-braces on the
            correlation contract: walk every retained epoch's :trace-events
            and assert none is a :frame op."
    (rf/reg-event :app/init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc      (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-frame :test/main {:initial-events [[:app/init]]})
    (rf/dispatch-sync [:inc] {:frame :test/main})
    (rf/dispatch-sync [:inc] {:frame :test/main})

    (doseq [r (rf/epoch-history :test/main)]
      (is (not-any? #(= :rf.frame (:op-type %)) (:trace-events r))
          (str "no :frame-op orphan in epoch " (:event-id r)
               "'s :trace-events")))))

(deftest inv-6-harvest-discards-orphan-uncorrelated
  (testing "rf2-avvwm + rf2-ee38b — direct unit test on the harvest seam. An
            orphan event (no :rf.trace/dispatch-id) that reaches the capture
            buffer is NOT folded into the settling event's harvest; only the
            settling event's own :rf.trace/dispatch-id traces are returned.

            Per the rf2-ee38b correctness review: the harvest is now
            self-cleaning — an orphan (nil dispatch-id) has no settle event to
            ever reclaim it, so retaining it (the prior behaviour) left it in
            the buffer indefinitely (re-grouped + re-retained on every
            subsequent harvest). It is now DISCARDED at this seam, so the
            harvest no longer relies on the upstream capture guard being
            perfect."
    (let [frame :test/harvest
          ;; Hand-craft a buffer: an orphan with no :rf.trace/dispatch-id, then a
          ;; run-start + a body trace for dispatch-id 42.
          orphan    {:op-type :rf.frame :operation :rf.frame/created :tags {}}
          run-start {:op-type :rf.event :operation :rf.event/run-start
                     :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id 42 :rf.trace/event-id :inc}}
          body      {:op-type :rf.event :operation :rf.event/db-changed
                     :tags {:rf.trace/dispatch-id 42}}]
      (state/buffer-event! frame orphan)
      (state/buffer-event! frame run-start)
      (state/buffer-event! frame body)
      (let [harvested (state/harvest-buffer-for-event! frame)]
        (is (= [run-start body] harvested)
            "harvest returns ONLY the settling event's (:rf.trace/dispatch-id 42)
             traces — the orphan is left uncorrelated, not vacuumed in")
        (is (not-any? #(= :rf.frame/created (:operation %)) harvested)
            "the orphan :rf.frame/created is not in the settling epoch's harvest")
        ;; The orphan is DISCARDED (self-cleaning harvest), not retained —
        ;; it has no settle event to ever reclaim it.
        (is (empty? (state/buffer-for frame))
            "the orphan is dropped from the buffer — the harvest is
             self-cleaning, not reliant on the upstream guard")))))

(deftest inv-6b-harvest-retains-child-marker-for-its-own-settle
  (testing "rf2-ee38b / rf2-bhglx — the self-cleaning harvest must NOT discard a
            CHILD's dispatch-id marker: a non-nil dispatch-id that differs from
            the settling event's id is a child's `:event/dispatched` marker
            (fired during the parent's do-fx) and stays buffered for the child's
            own settle (Spec 009 §Dispatch correlation: one dispatch-id = one
            epoch). Only nil-id orphans are dropped.

            rf2-bhglx — the retained marker is kept VERBATIM (no private survival
            counter); claim-based retention holds it until the child's run-start
            claims it. Bare map equality is the right assertion now."
    (let [frame      :test/harvest-child
          run-start  {:op-type :rf.event :operation :rf.event/run-start
                      :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id 1 :rf.trace/event-id :parent}}
          body       {:op-type :rf.event :operation :rf.event/db-changed
                      :tags {:rf.trace/dispatch-id 1}}
          child-mark {:op-type :rf.event :operation :rf.event/dispatched
                      :tags {:rf.trace/dispatch-id 2 :rf.trace/event-id :child}}
          orphan     {:op-type :rf.frame :operation :rf.frame/created :tags {}}]
      (state/buffer-event! frame run-start)
      (state/buffer-event! frame body)
      (state/buffer-event! frame child-mark)
      (state/buffer-event! frame orphan)
      (let [harvested (state/harvest-buffer-for-event! frame)
            retained  (state/buffer-for frame)]
        (is (= [run-start body] harvested)
            "the parent's harvest takes only its own (dispatch-id 1) traces")
        ;; The child marker (dispatch-id 2) is RETAINED VERBATIM; orphan DROPPED.
        (is (= [child-mark] retained)
            "exactly the child's marker stays buffered, bare (no private stamp);
             the nil-id orphan is dropped")
        (state/drop-frame-buffer! frame)))))

(deftest inv-6c-harvest-retains-child-marker-across-sibling-settles
  (testing "rf2-bhglx — a child's `:event/dispatched` marker (fired during the
            parent's do-fx, carrying the CHILD's id) must survive EVERY
            intervening sibling settle until the child's own run-start claims it.
            Ordinary `:dispatch` fx children go to the router FIFO TAIL
            (`router/enqueue-envelope!`), so a sibling already queued behind the
            parent settles BEFORE the child — possibly several of them. The child
            epoch must still get its queue-time dispatch/source row (parent-
            dispatch-id, source, origin, call-site) and parent-child causality.

            The earlier rf2-fxowr count-based reclaim DROPPED the marker after
            one intervening harvest, which is exactly this legitimate
            interleaving — the bug rf2-bhglx fixes. Memory is bounded by the
            terminal paths that clear the whole buffer (drain-interrupt / depth-
            halt / rejected dispatch), proven by inv-6c-bound below; it is NOT
            bounded by a per-marker harvest count."
    (let [frame       :test/harvest-sibling
          child-mark  {:op-type :rf.event :operation :rf.event/dispatched
                       :tags {:rf.trace/dispatch-id 99 :rf.trace/event-id :child
                              :rf.trace/parent-dispatch-id 1 :source :reframe}}
          ;; Three genuine, unrelated SIBLING events settle ahead of the child
          ;; (queued behind the parent on the FIFO before the child's enqueue).
          rs    (fn [id eid] {:op-type :rf.event :operation :rf.event/run-start
                              :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id id :rf.trace/event-id eid}})
          body  (fn [id]     {:op-type :rf.event :operation :rf.event/db-changed
                              :tags {:rf.trace/dispatch-id id}})]
      ;; The child marker is stranded into the buffer alongside the FIRST sibling.
      (state/buffer-event! frame child-mark)
      (doseq [[id eid] [[7 :sib-1] [8 :sib-2] [9 :sib-3]]]
        (state/buffer-event! frame (rs id eid))
        (state/buffer-event! frame (body id))
        (let [h (state/harvest-buffer-for-event! frame)]
          (is (= [(rs id eid) (body id)] h)
              (str "sibling " eid " harvests ONLY its own traces"))
          (is (not-any? #(= 99 (-> % :tags :rf.trace/dispatch-id)) h)
              "the child marker is never folded into a sibling's epoch")
          (is (= [child-mark] (state/buffer-for frame))
              "rf2-bhglx — the child marker survives this sibling settle, kept
               VERBATIM for the child's own settle")))
      ;; Finally the child itself settles — its run-start claims the marker.
      (state/buffer-event! frame (rs 99 :child))
      (state/buffer-event! frame (body 99))
      (let [child-harvest (state/harvest-buffer-for-event! frame)]
        (is (= [child-mark (rs 99 :child) (body 99)] child-harvest)
            "the child's settle finally claims its dispatch marker + own traces —
             the queue-time dispatch/source row survives the FIFO interleaving")
        (is (= 1 (->> child-harvest first :tags :rf.trace/parent-dispatch-id))
            "the claimed marker still carries its parent-dispatch-id (causality)")
        (is (empty? (state/buffer-for frame))
            "buffer empty after the child settle"))
      (state/drop-frame-buffer! frame))))

(deftest inv-6c-bound-stranded-marker-cleared-by-terminal-path
  (testing "rf2-bhglx — a child that NEVER runs to a settle (handler
            unregistered, frame destroyed / drain-interrupted, depth-halt clears
            the queue) leaves its marker stranded, but it does NOT accrete: the
            terminal path that ends the child's life clears the WHOLE buffer.
            `drop-frame-buffer!` (frame destroy / discard) and the no-run-start
            full clear-and-return (rejected dispatch) both wipe the stranded
            marker. This is the memory bound that replaces the rf2-fxowr harvest-
            count reclaim — bounded by lifecycle, not by a per-marker counter."
    (let [frame    :test/harvest-stranded-bound
          stranded {:op-type :rf.event :operation :rf.event/dispatched
                    :tags {:rf.trace/dispatch-id 99 :rf.trace/event-id :child-never-ran}}]
      ;; (a) drain-interrupt / frame-destroy terminal clear.
      (state/buffer-event! frame stranded)
      (is (= [stranded] (state/buffer-for frame))
          "the stranded marker is buffered")
      (state/drop-frame-buffer! frame)
      (is (empty? (state/buffer-for frame))
          "drop-frame-buffer! (destroy / discard) clears the stranded marker")

      ;; (b) rejected-dispatch terminal clear: a buffer with NO run-start (the
      ;; child's dispatch was rejected) reaches the no-run-start branch, which
      ;; clears-and-returns the whole buffer.
      (state/buffer-event! frame stranded)
      (let [returned (state/harvest-buffer-for-event! frame)]
        (is (= [stranded] returned)
            "a no-run-start harvest returns the buffer (the degenerate record is
             suppressed downstream by settle!'s empty-buffer policy)")
        (is (empty? (state/buffer-for frame))
            "and clears it — the stranded marker does not accrete")))))

(deftest inv-6c-bead-sibling-queued-behind-parent-does-not-drop-child
  (testing "rf2-bhglx (the bead's named scenario) — queue B behind parent A; A
            dispatches child C (C's `:event/dispatched` marker rides A's window
            but carries C's id, and C goes to the FIFO TAIL behind B). When B
            settles BEFORE C, B's harvest must NOT consume C's marker; C must
            still receive its `:event/dispatched` marker at its own settle, while
            neither A nor B carries it.

            Pre-rf2-bhglx the count-1 reclaim dropped C's marker on B's harvest
            (the one allowed intervening pass), so C lost its queue-time dispatch
            row. The harvest seam is the exact locus; this drives it directly to
            stay deterministic (the FIFO timing is unreproducible in synchronous
            dispatch-sync)."
    (let [frame    :test/bead-abc
          a-rs     {:op-type :rf.event :operation :rf.event/run-start
                    :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id :A :rf.trace/event-id :parent-a}}
          a-body   {:op-type :rf.event :operation :rf.event/db-changed
                    :tags {:rf.trace/dispatch-id :A}}
          ;; C's dispatch marker fires during A's do-fx — lands in A's window.
          c-mark   {:op-type :rf.event :operation :rf.event/dispatched
                    :tags {:rf.trace/dispatch-id :C :rf.trace/event-id :child-c
                           :rf.trace/parent-dispatch-id :A}}
          b-rs     {:op-type :rf.event :operation :rf.event/run-start
                    :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id :B :rf.trace/event-id :sibling-b}}
          b-body   {:op-type :rf.event :operation :rf.event/db-changed
                    :tags {:rf.trace/dispatch-id :B}}
          c-rs     {:op-type :rf.event :operation :rf.event/run-start
                    :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id :C :rf.trace/event-id :child-c}}
          c-body   {:op-type :rf.event :operation :rf.event/db-changed
                    :tags {:rf.trace/dispatch-id :C}}]
      ;; A settles, stranding C's marker in the buffer.
      (state/buffer-event! frame a-rs)
      (state/buffer-event! frame a-body)
      (state/buffer-event! frame c-mark)
      (let [a-harvest (state/harvest-buffer-for-event! frame)]
        (is (= [a-rs a-body] a-harvest) "A's epoch carries A's traces only")
        (is (not-any? #(= :C (-> % :tags :rf.trace/dispatch-id)) a-harvest)
            "A does NOT carry C's dispatch marker"))

      ;; B settles next (it was queued ahead of FIFO-tail C). B must leave C's
      ;; marker alone — this is the exact harvest the count-1 reclaim broke.
      (state/buffer-event! frame b-rs)
      (state/buffer-event! frame b-body)
      (let [b-harvest (state/harvest-buffer-for-event! frame)]
        (is (= [b-rs b-body] b-harvest) "B's epoch carries B's traces only")
        (is (not-any? #(= :C (-> % :tags :rf.trace/dispatch-id)) b-harvest)
            "B does NOT carry C's dispatch marker")
        (is (= [c-mark] (state/buffer-for frame))
            "rf2-bhglx — C's marker SURVIVES B's intervening settle (not dropped
             by a harvest-count reclaim)"))

      ;; C finally settles — claims its own marker (+ queue-time dispatch row).
      (state/buffer-event! frame c-rs)
      (state/buffer-event! frame c-body)
      (let [c-harvest (state/harvest-buffer-for-event! frame)]
        (is (= [c-mark c-rs c-body] c-harvest)
            "C's epoch finally claims its :event/dispatched marker + own traces")
        (is (= :A (-> c-harvest first :tags :rf.trace/parent-dispatch-id))
            "C's claimed marker still names parent A (parent-child causality kept)"))
      (state/drop-frame-buffer! frame))))

;; ===========================================================================
;; INVARIANT 7 — a tool / inspector frame's OWN render never pollutes the
;;                INSPECTED app frame's epoch :renders (rf2-tqlmq — the
;;                cross-frame / observer sibling of inv-3)
;; ===========================================================================
;;
;; The LIVE repro (build :examples/standard-epochs): the :rf/default boot epoch's
;; :renders carried ["shell-view" 27] (mount + update) — Xray's OWN shell-view,
;; the observer, leaking into the observed app's render tape. Root cause:
;; `shell-view` is a `reg-view` (its :rf.view/rendered carries
;; `(provider/current-frame)`), but mount.cljs rendered it BARE — its own
;; `[frame-provider-existing {:frame :rf/xray}]` sat INSIDE its body around the panels —
;; so `shell-view`'s OWN render resolved `current-frame` to `:rf/default` by
;; fall-through and back-filled into the inspected app's boot epoch.
;;
;; The fix (mount-wrap) moves the provider OUT one level so `shell-view`'s own
;; render resolves to the trace-disabled `:rf/xray` frame. This invariant pins
;; the load-bearing consequence: a `:rf.view/rendered` tagged with a
;; `:rf.trace/frame-no-emit? true` frame is SUPPRESSED at `trace/emit!` (the
;; gate keys off the emit's `:frame` tag — trace.cljc/`tagged-frame-trace-
;; disabled?`), so the back-fill machinery never even sees it and the observed
;; frame's :renders stays clean. The contrapositive case proves the test
;; discriminates: the SAME render, were it tagged with the app frame
;; (the pre-fix fall-through), DOES land — i.e. the suppression, not some
;; unrelated filter, is what keeps the tape clean.

(deftest inv-7-tool-frame-render-does-not-pollute-inspected-app-epoch
  (testing "rf2-tqlmq — a render emitted under a trace-disabled (tool /
            inspector) frame, while an APP frame has a settled epoch in flight,
            does NOT land in the app frame's epoch :renders. The frame-no-emit
            gate suppresses the emit at source so no back-fill runs. The
            cross-frame / observer sibling of inv-3 (whose lag was WITHIN one
            frame; this leak is ACROSS frames — observer into observed)."
    (let [app      :test/app
          observer :test/observer]
      ;; Mirror the runtime: the observer frame registers
      ;; `:rf.trace/frame-no-emit? true` (what `mount/ensure-xray-frame!` does
      ;; for `:rf/xray`); reg-frame routes the flag to the trace gate.
      (rf/reg-frame app {})
      (rf/reg-frame observer {:rf.trace/frame-no-emit? true})
      (is (trace/frame-trace-disabled? observer)
          "the observer frame is registered trace-disabled (reg-frame honoured
           :rf.trace/frame-no-emit?)")
      (is (not (trace/frame-trace-disabled? app))
          "the inspected app frame is NOT trace-disabled")

      (rf/reg-event :app/seed (fn [{:keys [db]} _] {:db {:counter 0}}))
      (rf/reg-event :app/inc  (fn [{:keys [db]} _] {:db (update db :counter inc)}))

      ;; The app frame produces a boot epoch (a last-settled epoch the
      ;; back-fill would attribute to).
      (rf/dispatch-sync [:app/seed] {:frame app})
      (rf/dispatch-sync [:app/inc]  {:frame app})
      (let [app-epoch (last-epoch app)]
        ;; The observer's OWN render fires post-settle (React-commit timing),
        ;; tagged with the observer frame — exactly what the mount-wrap makes
        ;; `shell-view`'s render carry. The gate must suppress it.
        (emit-render! observer :shell-view)

        (let [e (epoch-by-id app app-epoch)]
          (is (= :app/inc (:event-id e))
              "the app frame's last epoch is its own :app/inc cascade")
          (is (not (contains? (rendered-view-ids e) :shell-view))
              "the inspector's shell-view render did NOT leak into the
               inspected app frame's epoch :renders — the frame-no-emit gate
               suppressed the emit before any back-fill (rf2-tqlmq)")
          (is (empty? (rendered-view-ids e))
              "the app frame's epoch carries NO renders at all from the
               observer's post-settle commit — the observer is invisible to the
               observed tape"))))))

(deftest inv-7-contrapositive-untagged-render-still-back-fills
  (testing "rf2-tqlmq — the discriminator. The SAME post-settle render, were it
            tagged with the APP frame (the PRE-fix fall-through: shell-view
            rendering above its provider resolved to the app's :rf/default), DOES
            back-fill into the app epoch. Proves inv-7's clean result is the
            frame-no-emit SUPPRESSION at work, not an unrelated filter — and is
            the exact leak the bug exhibited."
    (let [app :test/app]
      (rf/reg-frame app {})
      (rf/reg-event :app/seed (fn [{:keys [db]} _] {:db {:counter 0}}))
      (rf/reg-event :app/inc  (fn [{:keys [db]} _] {:db (update db :counter inc)}))

      (rf/dispatch-sync [:app/seed] {:frame app})
      (rf/dispatch-sync [:app/inc]  {:frame app})
      (let [app-epoch (last-epoch app)]
        ;; Render tagged with the APP frame (NOT trace-disabled) — the pre-fix
        ;; fall-through. This is precisely the leak the live repro showed.
        (emit-render! app :shell-view)

        (let [e (epoch-by-id app app-epoch)]
          (is (contains? (rendered-view-ids e) :shell-view)
              "a render tagged with the (non-disabled) app frame DOES back-fill
               into the app epoch — this is the leak the mount-wrap fixes by
               retagging shell-view's render with the trace-disabled frame"))))))

;; ===========================================================================
;; rf2-8wrzz.1 — the :renders projection carries per-view cause + timing
;;               threaded from the post-render :rf.view/rendered op
;; ===========================================================================

(defn- render-row-for
  "The single `:renders` row for `render-key` in `record`, or nil."
  [record render-key]
  (some #(when (= render-key (:render-key %)) %) (:renders record)))

(deftest renders-projection-carries-triggered-by-and-elapsed-ms
  (testing "rf2-8wrzz.1 — a :rf.view/rendered op carrying :rf.view/triggered-by
            + :rf.view/elapsed-ms (the per-view cause + timing) lands those slots
            on the cascade's :renders projection row, end-to-end. The projection
            sources from the POST-render :rf.view/rendered op precisely so it
            carries this data (the render-START :rf.view/render carries only the
            render-key)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    ;; Post-settle :rf.view/rendered carrying cause + timing (React-commit
    ;; timing — empty buffer, back-filled to the seed epoch).
    (trace/emit! :rf.view :rf.view/rendered
                 {:rf.view/render-key   [:counter-view 0]
                  :frame                :test/main
                  :rf.view/mount?       false
                  :rf.view/triggered-by :sub/count
                  :rf.view/elapsed-ms   1.5})

    (let [epoch (last-epoch :test/main)
          row   (render-row-for epoch [:counter-view 0])]
      (is (some? row) "the :renders projection carries the render row")
      (is (= :sub/count (:triggered-by row))
          ":triggered-by is preserved on the :renders row")
      (is (= 1.5 (:elapsed-ms row))
          ":elapsed-ms is preserved on the :renders row")
      (is (= false (:mount? row))
          ":mount? is preserved on the :renders row"))))

(deftest renders-projection-omits-triggered-by-on-structural-render
  (testing "rf2-8wrzz.1 — a structural re-render (no :rf.view/triggered-by on
            the op — none of the view's own subs changed) lands a :renders row
            WITHOUT :triggered-by; :elapsed-ms still rides."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (trace/emit! :rf.view :rf.view/rendered
                 {:rf.view/render-key [:structural-view 0]
                  :frame              :test/main
                  :rf.view/mount?     false
                  :rf.view/elapsed-ms 0.3})

    (let [epoch (last-epoch :test/main)
          row   (render-row-for epoch [:structural-view 0])]
      (is (some? row) "the structural render still produces a :renders row")
      (is (not (contains? row :triggered-by))
          ":triggered-by absent on a structural re-render row")
      (is (= 0.3 (:elapsed-ms row)) ":elapsed-ms still preserved"))))

;; ===========================================================================
;; rf2-9gquv — the :renders projection carries :cause-event-id (the cascade
;;             whose handler-body invalidated a reactive input this view read)
;; ===========================================================================
;;
;; The false-green guard at the projection boundary. The :rf.view/rendered op
;; stamps :rf.view/cause-event-id (views.cljs, rf2-1cc03) exactly as the
;; :rf.sub/run op stamps :rf.sub/cause-event-id, and the render row must
;; carry it through: if a projected render row keyed as nil, the Story
;; causal/cascade :view surface would silently measure 0 and an over-render
;; could never be caught (a SILENT GREEN). This pins that the render row
;; carries the cause end-to-end, mirroring the sub-row.

(deftest renders-projection-carries-cause-event-id
  (testing "rf2-9gquv — a :rf.view/rendered op carrying :rf.view/cause-event-id
            (the cascade that invalidated a reactive input this view read)
            lands :cause-event-id on the :renders projection row, end-to-end —
            the slot the Story :view causal surface reads. Pre-fix render-row
            dropped it and the surface silently measured 0 (false GREEN)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    ;; Post-settle :rf.view/rendered carrying the cause attribution (mirrors
    ;; the reactive re-render emit at views.cljs:320-321).
    (trace/emit! :rf.view :rf.view/rendered
                 {:rf.view/render-key     [:counter-view 0]
                  :frame                  :test/main
                  :rf.view/mount?         false
                  :rf.view/cause-event-id :counter-inc})

    (let [epoch (last-epoch :test/main)
          row   (render-row-for epoch [:counter-view 0])]
      (is (some? row) "the :renders projection carries the render row")
      (is (= :counter-inc (:cause-event-id row))
          ":cause-event-id is threaded onto the :renders row — mirroring how
           the :sub-runs row carries :cause-event-id (capture.cljc)"))))

(deftest renders-projection-omits-cause-event-id-on-structural-render
  (testing "rf2-9gquv — a render OUTSIDE any cascade (mount / structural —
            the op carries no :rf.view/cause-event-id) lands a :renders row
            WITHOUT :cause-event-id. OMITTED-vs-nil parity with the sub-row:
            absent tag → absent slot, never an attributed nil."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (trace/emit! :rf.view :rf.view/rendered
                 {:rf.view/render-key [:structural-view 0]
                  :frame              :test/main
                  :rf.view/mount?     false})

    (let [epoch (last-epoch :test/main)
          row   (render-row-for epoch [:structural-view 0])]
      (is (some? row) "the structural render still produces a :renders row")
      (is (not (contains? row :cause-event-id))
          ":cause-event-id absent when the op carried no cause tag"))))

;; ===========================================================================
;; rf2-dq2b7 — mount-attribution atom merge: epoch-id + deps share one entry
;; ===========================================================================

(deftest mount-attribution-coexists-on-single-entry
  (testing "rf2-dq2b7 — `record-mount-epoch!` and `record-render-deps!`
            update DIFFERENT slots on the SAME `(frame, render-key)` entry
            in the merged `mount-attribution` atom. Cross-population must
            not clobber the sibling slot, and the public accessors read
            each slot independently."
    (let [frame      :test/dq2b7
          render-key [:my-view 0]]
      ;; Seed the read-set FIRST — the in-render deref typically fires
      ;; before the post-settle render commit.
      (state/record-render-deps! frame render-key :sub/a)
      (state/record-render-deps! frame render-key :sub/b)
      (is (= #{:sub/a :sub/b} (state/render-deps-for frame render-key))
          "both deps recorded on the entry's :deps slot")
      (is (nil? (state/mount-epoch-for frame render-key))
          "mount-epoch slot is still empty — record-render-deps! did not touch it")

      ;; Now record the mount-epoch — populates the sibling :epoch-id slot
      ;; on the same entry without clobbering the deps set.
      (state/record-mount-epoch! frame render-key :epoch/one)
      (is (= :epoch/one (state/mount-epoch-for frame render-key))
          "mount-epoch landed on the entry's :epoch-id slot")
      (is (= #{:sub/a :sub/b} (state/render-deps-for frame render-key))
          "deps slot survived the mount-epoch write — single entry, two slots")

      ;; First-sighting invariant survives the merge: a second
      ;; record-mount-epoch! must NOT overwrite the anchor.
      (state/record-mount-epoch! frame render-key :epoch/ninety-nine)
      (is (= :epoch/one (state/mount-epoch-for frame render-key))
          "re-recording a mount epoch does not move the anchor (first-sighting)")

      ;; Single-wipe contract: one `drop-frame-mount-attribution!` clears
      ;; BOTH slots; the bead's "four wipers → two" lift.
      (state/drop-frame-mount-attribution! frame)
      (is (nil? (state/mount-epoch-for frame render-key))
          "mount-epoch cleared by drop-frame-mount-attribution!")
      (is (nil? (state/render-deps-for frame render-key))
          "deps cleared by the same wipe — one swap clears both slots"))))

(deftest mount-attribution-frame-scoping
  (testing "rf2-dq2b7 — `mount-attribution` is keyed by (frame × render-key);
            dropping one frame's entry does not affect a sibling frame's
            anchor or read-set."
    (let [render-key [:shared-view 0]]
      (state/record-mount-epoch!  :test/dq2b7-a render-key :epoch/a-1)
      (state/record-render-deps!  :test/dq2b7-a render-key :sub/a)
      (state/record-mount-epoch!  :test/dq2b7-b render-key :epoch/b-1)
      (state/record-render-deps!  :test/dq2b7-b render-key :sub/b)

      ;; Drop only frame A.
      (state/drop-frame-mount-attribution! :test/dq2b7-a)
      (is (nil? (state/mount-epoch-for :test/dq2b7-a render-key)))
      (is (nil? (state/render-deps-for :test/dq2b7-a render-key)))
      ;; Frame B survives intact.
      (is (= :epoch/b-1 (state/mount-epoch-for :test/dq2b7-b render-key)))
      (is (= #{:sub/b}  (state/render-deps-for :test/dq2b7-b render-key)))

      ;; reset-mount-attribution! wipes everything left.
      (state/reset-mount-attribution!)
      (is (nil? (state/mount-epoch-for :test/dq2b7-b render-key))))))

;; ===========================================================================
;; rf2-bgapd — mount-attribution is bounded across instance churn: a view
;;             instance's entry is pruned on its per-instance UNMOUNT, not
;;             retained until whole-frame destroy
;; ===========================================================================
;;
;; THE LEAK (epoch senior-dev review, rf2-bgapd): the render-key is
;; `[view-id instance-token]` and each MOUNT mints a fresh `instance-token`
;; (a churning row / re-opened modal / route-scoped component remounts under a
;; NEW render-key every time). `mount-attribution` is keyed by that render-key
;; and accumulated an entry (an `:epoch-id` anchor + a `:deps` sub-id set) on
;; first sighting, removed ONLY by `drop-frame-mount-attribution!` (whole-frame
;; destroy) — never on per-instance unmount. So for a live frame over a long
;; churning session the map grew without bound — exactly the long-running
;; time-travel scenario the epoch surface exists to serve.
;;
;; THE FIX: `record-unmount!` now calls `drop-render-key-mount-attribution!`
;; after the trace back-fill, evicting the unmounting instance's entry.

(deftest drop-render-key-mount-attribution-prunes-single-instance
  (testing "rf2-bgapd — `drop-render-key-mount-attribution!` evicts ONE
            render-key's entry (anchor + read-set) and leaves sibling
            render-keys in the same frame untouched."
    (let [frame :test/bgapd
          rk-a  [:row-view 100]
          rk-b  [:row-view 101]]
      (state/record-mount-epoch! frame rk-a :epoch/a)
      (state/record-render-deps! frame rk-a :sub/a)
      (state/record-mount-epoch! frame rk-b :epoch/b)
      (state/record-render-deps! frame rk-b :sub/b)

      (state/drop-render-key-mount-attribution! frame rk-a)
      (is (nil? (state/mount-epoch-for frame rk-a))
          "instance A's anchor evicted")
      (is (nil? (state/render-deps-for frame rk-a))
          "instance A's read-set evicted")
      (is (= :epoch/b (state/mount-epoch-for frame rk-b))
          "sibling instance B's anchor survives — eviction is per-render-key")
      (is (= #{:sub/b} (state/render-deps-for frame rk-b))
          "sibling instance B's read-set survives")

      ;; Idempotent: dropping an already-absent / never-seen render-key is a
      ;; no-op, never a throw (a late tail / double-unmount must be harmless).
      (state/drop-render-key-mount-attribution! frame rk-a)
      (state/drop-render-key-mount-attribution! frame [:never-mounted 0])
      (is (= :epoch/b (state/mount-epoch-for frame rk-b))
          "idempotent prune left the surviving entry intact"))))

(deftest unmount-prunes-mount-attribution-bounded-across-churn
  (testing "rf2-bgapd — THE leak guard. Mount N instances (each a fresh
            instance-token → fresh render-key), settle a cascade so the live
            frame is real, then UNMOUNT every instance. The frame's
            `mount-attribution` must NOT retain the unmounted instances'
            entries — it shrinks back toward empty (bounded), NOT retained
            until whole-frame destroy. Pre-fix every ever-mounted instance
            left a permanent entry; the map grew without bound across churn."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed       (fn [{:keys [db]} _] {:db {:rows (vec (range 5))}}))
    (rf/reg-event :drop-rows  (fn [{:keys [db]} _] {:db (assoc db :rows [])}))

    ;; A real settled cascade so the frame has a last-settled epoch the
    ;; unmount back-fill can land in (the live-frame path, not just the
    ;; direct-state unit above).
    (rf/dispatch-sync [:seed] {:frame :test/main})

    ;; Mount N row instances — each a DISTINCT instance-token (the churn that
    ;; mints a fresh render-key per mount). Each learns an anchor + a read-set
    ;; the way a real mount does (post-settle render + in-render deref).
    (let [n            8
          render-keys  (mapv (fn [i] [:row-view i]) (range n))]
      (doseq [rk render-keys]
        (emit-render! :test/main rk)
        (emit-mount-sub-run! :test/main :rows rk nil [0 1 2 3 4]))

      ;; Every instance now carries a mount-attribution entry.
      (doseq [rk render-keys]
        (is (some? (state/mount-epoch-for :test/main rk))
            (str "instance " rk " has a mount anchor before unmount"))
        (is (some? (state/render-deps-for :test/main rk))
            (str "instance " rk " has a learned read-set before unmount")))

      ;; The cascade that removes all rows settles; then (React-teardown
      ;; timing) every instance's unmount fires post-settle.
      (rf/dispatch-sync [:drop-rows] {:frame :test/main})
      (doseq [rk render-keys]
        (emit-unmount! :test/main rk))

      ;; THE assertion: none of the unmounted instances' entries are retained.
      ;; mount-attribution is BOUNDED across churn — pruned per-instance on
      ;; unmount, not held until frame-destroy.
      (doseq [rk render-keys]
        (is (nil? (state/mount-epoch-for :test/main rk))
            (str "instance " rk "'s anchor pruned on unmount — not retained"))
        (is (nil? (state/render-deps-for :test/main rk))
            (str "instance " rk "'s read-set pruned on unmount — not retained")))

      ;; And the unmount is STILL observable in its causing cascade's
      ;; :trace-events — the rf2-59hx3 back-fill is preserved alongside the
      ;; rf2-bgapd prune (the prune evicts the attribution map, NOT the
      ;; recorded trace).
      (let [drop-epoch (last-epoch :test/main)]
        (is (= :drop-rows (:event-id drop-epoch)))
        (is (= (set render-keys)
               (->> (:trace-events drop-epoch)
                    (filter #(= :rf.view/unmounted (:operation %)))
                    (map #(-> % :tags :rf.view/render-key))
                    set))
            "every instance's unmount is back-filled into the drop-rows
             cascade's :trace-events (rf2-59hx3 preserved) even though its
             attribution entry was pruned (rf2-bgapd)")))))

;; ===========================================================================
;; INVARIANT 8 — a view UNMOUNT is back-filled into its CAUSING cascade's
;;                :trace-events, not silently dropped (rf2-59hx3)
;; ===========================================================================
;;
;; The teardown sibling of inv-2 (render) and inv-1 (sub-run). A
;; `:rf.view/unmounted` fires at React teardown time — AFTER the cascade that
;; removed the view settled — so it arrives at `capture-event!` with an empty
;; in-flight buffer and no `:rf.trace/dispatch-id`.
;;
;; THE GAP (Mike-confirmed, button-deck button 13): pre-rf2-59hx3 the unmount
;; was NOT in render-ops / sub-run-ops, so it fell through to the orphan-drop
;; branch (no in-flight cascade + no dispatch-id) and was SILENTLY DROPPED.
;; The view teardown produced no signal anywhere in the epoch record, so
;; Xray's VIEWS-step `unmounted-views-rows` (which reads `:rf.view/unmounted`
;; off `:trace-events`) had nothing to surface — an invisible absence.
;;
;; THE FIX: route the post-settle unmount through the SAME back-fill
;; mechanism renders + sub-runs use (`:epoch/record-unmount!`), attributing
;; it to the most-recently-settled epoch (the cascade that caused the
;; teardown). The unmount carries no structured projection row, so it rides
;; ONLY `:trace-events` — exactly where Xray reads it.

(defn- unmounted-view-ids
  "The view-ids carried by `:rf.view/unmounted` ops in a record's
  :trace-events. nil-safe — a record whose :trace-events was elided
  returns #{}."
  [record]
  (->> (:trace-events record)
       (filter #(= :rf.view/unmounted (:operation %)))
       (map #(-> % :tags :rf.view/id))
       set))

(deftest inv-8-view-unmount-back-filled-into-its-causing-cascade
  (testing "rf2-59hx3 — a :rf.view/unmounted that fires AFTER the cascade
            that removed the view settled (React-teardown timing) is
            back-filled into that causing cascade's :trace-events, NOT
            silently dropped. THE regression guard: pre-fix the unmount hit
            the orphan-drop branch and produced no signal, so Xray's VIEWS
            step had nothing to surface (button-deck button 13)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed         (fn [{:keys [db]} _] {:db {:show-child? true}}))
    (rf/reg-event :hide-child   (fn [{:keys [db]} _] {:db (assoc db :show-child? false)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    ;; The cascade that removes the child view from the tree. It settles,
    ;; THEN (next tick, React teardown) the child instance's unmount fires.
    (rf/dispatch-sync [:hide-child] {:frame :test/main})
    (let [hide-epoch (last-epoch :test/main)]
      (emit-unmount! :test/main :child-view)

      (let [e (epoch-by-id :test/main hide-epoch)]
        (is (= :hide-child (:event-id e)))
        (is (contains? (unmounted-view-ids e) :child-view)
            "the hide-child cascade carries the child-view unmount in its
             :trace-events — the teardown is OBSERVABLE, not a silent
             absence (pre-fix it was dropped at the capture seam)")
        ;; The unmount carries no structured :renders row — it is a teardown,
        ;; not a render. It rides ONLY :trace-events, where Xray reads it.
        (is (not (contains? (rendered-view-ids e) :child-view))
            "an unmount produces NO :renders row — it is a teardown, not a
             render; it surfaces via :trace-events only")))))

(deftest inv-8-unmount-attributed-to-its-own-cascade-multi-cascade
  (testing "rf2-59hx3 — two cascades that tear down DIFFERENT views each
            carry their OWN unmount, attributed to the cascade that caused
            it (no one-epoch lag, no cross-attribution). The multi-cascade
            assertion mirroring inv-2 for renders."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed       (fn [{:keys [db]} _] {:db {:a? true :b? true}}))
    (rf/reg-event :hide-a     (fn [{:keys [db]} _] {:db (assoc db :a? false)}))
    (rf/reg-event :hide-b     (fn [{:keys [db]} _] {:db (assoc db :b? false)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})

    (rf/dispatch-sync [:hide-a] {:frame :test/main})
    (let [epoch-a (last-epoch :test/main)]
      (emit-unmount! :test/main :view-a)

      (rf/dispatch-sync [:hide-b] {:frame :test/main})
      (let [epoch-b (last-epoch :test/main)]
        (emit-unmount! :test/main :view-b)

        (let [a (epoch-by-id :test/main epoch-a)
              b (epoch-by-id :test/main epoch-b)]
          (is (= :hide-a (:event-id a)))
          (is (= :hide-b (:event-id b)))
          (is (contains? (unmounted-view-ids a) :view-a)
              "cascade A carries its OWN view-a unmount")
          (is (not (contains? (unmounted-view-ids a) :view-b))
              "cascade A does NOT carry cascade B's view-b unmount")
          (is (contains? (unmounted-view-ids b) :view-b)
              "cascade B carries its OWN view-b unmount")
          (is (not (contains? (unmounted-view-ids b) :view-a))
              "cascade B does NOT carry cascade A's lagged view-a unmount"))))))

(deftest inv-8-in-flight-unmount-rides-current-cascade
  (testing "rf2-59hx3 — an unmount that fires WITH a cascade in flight (a
            synchronous teardown inside a drain) belongs to that cascade and
            is buffered normally, NOT back-filled. Pins that the post-settle
            routing does not poach an in-flight unmount."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :unmount-during
      (fn [{:keys [db]} _]
        (trace/emit! :rf.view :rf.view/unmounted
                     {:rf.view/id         :inline-view
                      :rf.view/render-key [:inline-view 0]
                      :frame              :test/main})
        {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:unmount-during] {:frame :test/main})

    (let [epoch (last-epoch :test/main)]
      (is (= :unmount-during (:event-id epoch)))
      (is (contains? (unmounted-view-ids epoch) :inline-view)
          "an in-flight unmount rides its own cascade (buffered, not
           back-filled to a prior settled epoch)"))))

(deftest inv-8-orphan-unmount-before-any-cascade-is-noop
  (testing "rf2-59hx3 — an unmount that fires before any cascade has settled
            (no last-settled epoch for the frame) is a silent no-op: no
            record materialises, no listener fan-out, no throw."
    (rf/reg-frame :test/main {})
    (let [seen (atom [])]
      (rf/register-epoch-listener! ::watcher (fn [r] (swap! seen conj r)))
      (emit-unmount! :test/main :orphan-view)
      (is (= [] (rf/epoch-history :test/main))
          "no record materialised from an orphan unmount")
      (is (= [] @seen)
          "no listener fan-out for an unmount with no causing cascade"))))

;; ===========================================================================
;; INVARIANT 9 — restore-induced post-settle activity does NOT back-fill into
;;                a STALE epoch (rf2-w4q9gt)
;; ===========================================================================
;;
;; The time-travel sibling of inv-1 / inv-2 / inv-8. A successful `restore-epoch!`
;; rewinds the frame's state but runs NO ordinary cascade — so it never updates
;; the `last-settled-epoch` anchor on its own. Left untouched, the anchor keeps
;; pointing at whatever event settled most recently BEFORE the restore.
;;
;; THE BUG: a restore triggers a repaint / subscription recompute / unmount of
;; the rewound view tree. Those fire post-settle (React commit / deref / teardown
;; timing), so `record-render!` / `record-sub-run!` / `record-unmount!` read the
;; stale `last-settled-epoch-id` and back-fill the restore-induced activity into
;; the UNRELATED most-recent pre-restore epoch — corrupting that later epoch's
;; historical `:renders` / `:sub-runs` / `:trace-events` for a frame that has
;; been rewound past it.
;;
;; THE FIX (restored-target attribution, mirroring the replace-* injection
;; siblings which re-anchor to their synthetic epoch): `perform-restore!` sets
;; `last-settled-epoch` to the RESTORED-TARGET epoch on success. Restore-induced
;; repaint then attributes to the epoch whose state is now installed — the
;; honest cause — not the stale event. A failed / rejected restore returns
;; before the re-anchor, leaving the anchor (and frame state, history,
;; listeners) untouched.

(deftest inv-9-restore-induced-render-does-not-backfill-into-stale-epoch
  (testing "rf2-w4q9gt — after a restore to an OLDER epoch, a restore-induced
            render fires post-settle. It must NOT back-fill into the unrelated
            pre-restore last-settled epoch. THE corruption: pre-fix the anchor
            still named the most-recent epoch the frame was rewound PAST, so the
            repaint smeared into that epoch's :renders."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    (rf/dispatch-sync [:seed]        {:frame :test/main})   ;; counter 0
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})   ;; counter 1 — restore target
    (let [target-epoch (last-epoch :test/main)]
      (rf/dispatch-sync [:counter-inc] {:frame :test/main}) ;; counter 2 — the pre-restore last-settled
      (let [stale-epoch (last-epoch :test/main)]

        ;; PRECONDITION — the two epochs are distinct, and the stale one is the
        ;; frame's current last-settled (the anchor a naive back-fill would use).
        (is (not= (:epoch-id target-epoch) (:epoch-id stale-epoch))
            "the restore target and the pre-restore last-settled are distinct")
        (is (= (:epoch-id stale-epoch) (state/last-settled-epoch-id :test/main))
            "before the restore, the most-recent counter-inc is last-settled")

        ;; Rewind the frame to the OLDER target epoch (counter 1).
        (is (true? (rf/restore-epoch! :test/main (:epoch-id target-epoch)))
            "restore to the older epoch succeeds")

        ;; A restore-induced repaint fires post-settle (React-commit timing) —
        ;; the rewound view tree re-renders.
        (emit-render! :test/main :counter-view)

        (let [stale (epoch-by-id :test/main stale-epoch)]
          (is (= :counter-inc (:event-id stale)))
          ;; THE INVARIANT — the restore-induced render did NOT land in the
          ;; stale epoch the frame was rewound past.
          (is (not (contains? (rendered-view-ids stale) :counter-view))
              "rf2-w4q9gt — restore-induced render did NOT back-fill into the
               stale pre-restore epoch (the corruption is gone)")
          ;; And it attributes to the RESTORED-TARGET epoch instead — the epoch
          ;; whose state is now installed (restored-target attribution).
          (let [target (epoch-by-id :test/main target-epoch)]
            (is (contains? (rendered-view-ids target) :counter-view)
                "the restore-induced render attributes to the restored-target
                 epoch — the honest cause of the repaint")))))))

(deftest inv-9-restore-induced-sub-run-does-not-backfill-into-stale-epoch
  (testing "rf2-w4q9gt — the SUBS sibling. A restore-induced reactive recompute
            (React-deref timing) must not land in the stale pre-restore epoch's
            :sub-runs. Mirrors inv-1 across a time-travel rewind."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    (rf/dispatch-sync [:seed]        {:frame :test/main})
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})   ;; counter 1 — target
    (let [target-epoch (last-epoch :test/main)]
      (rf/dispatch-sync [:counter-inc] {:frame :test/main}) ;; counter 2 — stale
      (let [stale-epoch (last-epoch :test/main)]

        (is (true? (rf/restore-epoch! :test/main (:epoch-id target-epoch))))

        ;; A restore-induced reactive recompute: counter sub re-derefs the rewound
        ;; db (2 → 1).
        (emit-sub-run! :test/main :counter 2 1)

        (let [stale  (epoch-by-id :test/main stale-epoch)
              target (epoch-by-id :test/main target-epoch)]
          (is (not (contains? (sub-run-ids stale) :counter))
              "rf2-w4q9gt — restore-induced sub-run did NOT back-fill into the
               stale pre-restore epoch")
          (is (contains? (sub-run-ids target) :counter)
              "the restore-induced sub-run attributes to the restored-target
               epoch"))))))

(deftest inv-9-restore-induced-unmount-does-not-backfill-into-stale-epoch
  (testing "rf2-w4q9gt — the UNMOUNT sibling. A restore that rewinds past a view
            spawn tears that view down; the post-settle unmount must not land in
            the stale pre-restore epoch's :trace-events. Mirrors inv-8."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    (rf/dispatch-sync [:seed]        {:frame :test/main})
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})   ;; target
    (let [target-epoch (last-epoch :test/main)]
      (rf/dispatch-sync [:counter-inc] {:frame :test/main}) ;; stale
      (let [stale-epoch (last-epoch :test/main)]

        (is (true? (rf/restore-epoch! :test/main (:epoch-id target-epoch))))

        ;; A restore-induced teardown fires post-settle (React-teardown timing).
        (emit-unmount! :test/main :transient-view)

        (let [stale  (epoch-by-id :test/main stale-epoch)
              target (epoch-by-id :test/main target-epoch)]
          (is (not (contains? (unmounted-view-ids stale) :transient-view))
              "rf2-w4q9gt — restore-induced unmount did NOT back-fill into the
               stale pre-restore epoch's :trace-events")
          (is (contains? (unmounted-view-ids target) :transient-view)
              "the restore-induced unmount attributes to the restored-target
               epoch"))))))

(deftest inv-9-failed-restore-leaves-attribution-anchor-unchanged
  (testing "rf2-w4q9gt — a FAILED restore (unknown epoch-id) must NOT touch the
            last-settled anchor: it returns before the re-anchor, leaving the
            frame's attribution exactly as the most-recent real cascade left it.
            Post-failure activity still attributes to that genuine last-settled
            epoch — the re-anchor is success-ONLY."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed        (fn [{:keys [db]} _] {:db {:counter 0}}))
    (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))

    (rf/dispatch-sync [:seed]        {:frame :test/main})
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [live-epoch (last-epoch :test/main)]

      (is (= (:epoch-id live-epoch) (state/last-settled-epoch-id :test/main))
          "the most-recent cascade is last-settled before the failed restore")

      ;; A restore to an epoch that is NOT in history fails (no-op).
      (is (false? (rf/restore-epoch! :test/main :no-such-epoch))
          "restore to an unknown epoch fails")

      ;; THE INVARIANT — the anchor is untouched.
      (is (= (:epoch-id live-epoch) (state/last-settled-epoch-id :test/main))
          "rf2-w4q9gt — a failed restore left the last-settled anchor unchanged")

      ;; A subsequent post-settle render still attributes to the genuine
      ;; last-settled epoch (the failed restore changed nothing).
      (emit-render! :test/main :counter-view)
      (let [e (epoch-by-id :test/main live-epoch)]
        (is (contains? (rendered-view-ids e) :counter-view)
            "post-failure render attributes to the unchanged last-settled
             epoch — the re-anchor is success-only")))))

;; ===========================================================================
;; rf2-qh13yf — back-fill splice is SNAPSHOT-CONSISTENT under interleaved
;;               eviction at ring cap
;; ===========================================================================
;;
;; THE BUG: `state/back-fill-event!` resolved the record's ring index against
;; ONE `@histories` deref (`epoch-index (history-for frame-id) epoch-id`), then
;; read the record off a SECOND deref and `update-in`'d at the up-front index.
;; Its docstring justified the single up-front resolution by "within-frame
;; drain is single-threaded, so no append can shift it" — but the back-fill
;; fires at React COMMIT / DEREF / TEARDOWN time, OUTSIDE any drain, so a real
;; cascade `record!` for the SAME frame can append between the two derefs. At
;; ring CAP that append EVICTS the front record and shifts every index down by
;; one, so the stale up-front index named (and spliced) the WRONG record.
;;
;; THE FIX (rf2-qh13yf): the index AND the spliced record are re-derived from
;; the SINGLE CAS-retried `@histories` value, INSIDE the one `swap!` update fn.
;; The splice therefore always lands on the record whose `:epoch-id` matches,
;; regardless of any interleaved append / eviction.
;;
;; These tests reproduce the eviction-shift deterministically (single-threaded
;; — drive `record!` to evict between capturing the target and back-filling it),
;; then assert the back-fill is index-shift-IMMUNE. They hit the ACTUAL failing
;; path: a stale-index splice would surface as a row on the WRONG epoch (or a
;; row on a surviving record when the target was evicted).

(defn- bf-sub-event
  "A bare reactive `:rf.sub/run` trace-event map (the shape
  `back-fill-sub-run!` appends), plus its structured `:sub-runs` row. Driven
  through `state/back-fill-sub-run!` DIRECTLY (the production post-settle path)
  so the test controls the exact @histories state the splice resolves against."
  [frame-id sub-id value]
  {:event {:op-type   :rf.sub
           :operation :rf.sub/run
           :tags      {:rf.sub/id    sub-id
                       :frame        frame-id
                       :rf.sub/value value}}
   :row   {:sub-id sub-id :value value}})

(deftest qh13yf-back-fill-splices-target-by-id-not-stale-index
  (testing "rf2-qh13yf — when an eviction at ring cap SHIFTS the target
            epoch's index between the back-fill's would-be index resolution
            and its splice, the splice lands on the epoch matching epoch-id
            (re-derived inside the swap), NOT the stale positional neighbour."
    ;; depth 3 — small cap so an append after filling evicts the front.
    (rf/configure! {:epoch-history {:depth 3 :trace-events-keep 50}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    ;; Fill the ring to cap (3 records): indices 0,1,2.
    (rf/dispatch-sync [:seed] {:frame :test/main})   ; idx 0 (event :seed)
    (rf/dispatch-sync [:inc]  {:frame :test/main})   ; idx 1 (event :inc, n=1)
    (rf/dispatch-sync [:inc]  {:frame :test/main})   ; idx 2 (event :inc, n=2)

    (let [history-before (rf/epoch-history :test/main)
          ;; Target the MIDDLE epoch (idx 1). After one eviction it shifts to
          ;; idx 0 — a stale-index splice would hit the record now at idx 1.
          target         (nth history-before 1)
          target-id      (:epoch-id target)
          neighbour-at-1 (:epoch-id (nth history-before 2))] ; what slides to idx 1
      (is (= 3 (count history-before)) "ring is at cap")

      ;; INTERLEAVE: a cascade record! appends at cap → evicts idx 0, every
      ;; surviving record shifts down by one. The target moves 1 → 0.
      (rf/dispatch-sync [:inc] {:frame :test/main})  ; n=3; evicts old idx 0

      (let [history-after (rf/epoch-history :test/main)
            target-idx'   (some (fn [i] (when (= target-id (:epoch-id (nth history-after i))) i))
                                (range (count history-after)))]
        (is (= 3 (count history-after)) "still at cap after the evicting append")
        (is (= 0 target-idx')
            "the target epoch shifted from index 1 to index 0 (eviction)")
        (is (not= target-id (:epoch-id (nth history-after 1)))
            "a DIFFERENT epoch now occupies the stale index 1")

        ;; NOW back-fill the target by id. The naive stale-index path would
        ;; have spliced into index 1 (the wrong epoch); the fix re-derives the
        ;; index inside the swap and lands on the target by id.
        (let [{:keys [event row]} (bf-sub-event :test/main :late-sub 99)]
          (state/back-fill-sub-run! :test/main target-id event row))

        (let [t          (epoch-by-id :test/main target)
              wrong       (epoch-by-id :test/main {:epoch-id (:epoch-id (nth history-after 1))})]
          (is (contains? (sub-run-ids t) :late-sub)
              "the back-fill landed on the TARGET epoch (matched by id, not
               the stale index 1)")
          (is (not (contains? (sub-run-ids wrong) :late-sub))
              "the epoch at the stale index 1 was NOT mis-spliced")
          (is (= target-id (:epoch-id t))
              "the spliced record really is the target")
          (is (= neighbour-at-1 (:epoch-id (nth (rf/epoch-history :test/main) 1)))
              "the index-1 neighbour is untouched and still the same epoch"))))))

(deftest qh13yf-back-fill-of-evicted-target-is-nil-no-wrong-splice
  (testing "rf2-qh13yf — when the target epoch is EVICTED before the back-fill
            runs, the back-fill resolves no index in the live ring, returns nil,
            and splices NOTHING into the record that took its old position
            (a stale up-front index would have spliced the evicted target's old
            slot into a surviving, unrelated epoch)."
    (rf/configure! {:epoch-history {:depth 2 :trace-events-keep 50}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})  ; idx 0 — the soon-evicted target
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ; idx 1

    (let [target-id (:epoch-id (first (rf/epoch-history :test/main)))]
      ;; Evict the front (the target) by appending past cap.
      (rf/dispatch-sync [:inc] {:frame :test/main})  ; evicts idx 0 (target)
      (rf/dispatch-sync [:inc] {:frame :test/main})  ; evicts again — target long gone

      (is (nil? (epoch-by-id :test/main {:epoch-id target-id}))
          "the target epoch is no longer in the ring (evicted)")

      (let [{:keys [event row]} (bf-sub-event :test/main :ghost-sub 7)
            result (state/back-fill-sub-run! :test/main target-id event row)]
        (is (nil? result)
            "back-fill of an evicted target returns nil (no record to splice)")
        ;; No surviving record received the ghost row.
        (is (every? (fn [r] (not (contains? (sub-run-ids r) :ghost-sub)))
                    (rf/epoch-history :test/main))
            "no surviving epoch was mis-spliced with the evicted target's
             back-fill (the stale-index wrong-record splice the fix kills)")))))

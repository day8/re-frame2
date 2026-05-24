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

  Supporting cases pin the boundary behaviour each fix also relies on:
  in-flight emits ride their own cascade (not back-filled); the back-fill
  re-fans the corrected record to listeners (Xray caches at settle time);
  an orphan emit before any cascade is a silent no-op; mount-burst tails are
  de-duped; a genuine re-render never collapses back to the mount epoch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.elision]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.state :as state]
            [re-frame.machines]))

;; ---- fixture ---------------------------------------------------------------
;;
;; Mirrors `re-frame.epoch-test/reset-runtime` — the attribution surface
;; reaches the same shared atoms (`state/last-settled-epoch`,
;; `mount-epoch-by-render-key`, `render-deps-by-render-key`, the per-frame
;; ring), all wiped by `epoch/clear-history!`.

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (trace/clear-listeners!)
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (reset! @#'state/config {:depth 50 :trace-events-keep 5 :redact-fn nil})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

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
    (rf/reg-event-db :seed         (fn [_ _] {:title "a" :counter 0}))
    (rf/reg-event-db :title-loaded (fn [db _] (assoc db :title "loaded")))
    (rf/reg-event-db :counter-inc  (fn [db _] (update db :counter inc)))

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
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :sub-during
      (fn [db _]
        (trace/emit! :rf.sub :rf.sub/run
                     {:rf.sub/id :inline-sub :rf.sub/query-v [:inline-sub]
                      :frame :test/main :rf.sub/value-changed? true
                      :rf.sub/prev-value nil :rf.sub/value :computed
                      :rf.sub/cascade? false :rf.sub/cause-sub nil})
        (update db :n inc)))

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
    (rf/reg-event-db :seed         (fn [_ _] {:title "a" :counter 0}))
    (rf/reg-event-db :title-loaded (fn [db _] (assoc db :title "loaded")))
    (rf/reg-event-db :counter-inc  (fn [db _] (update db :counter inc)))

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
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :render-during
      (fn [db _]
        (trace/emit! :rf.view :rf.view/rendered
                     {:rf.view/render-key [:inline-view 0] :frame :test/main})
        (update db :n inc)))

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
    (rf/reg-event-db :seed        (fn [_ _] {:counter 0}))
    (rf/reg-event-db :counter-inc (fn [db _] (update db :counter inc)))

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
    (rf/reg-event-db :seed        (fn [_ _] {:counter 0}))
    (rf/reg-event-db :counter-inc (fn [db _] (update db :counter inc)))

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
    (rf/reg-event-db :seed        (fn [_ _] {:counter 0}))
    (rf/reg-event-db :counter-inc (fn [db _] (update db :counter inc)))

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
    (rf/reg-event-db :seed        (fn [_ _] {:counter 0}))
    (rf/reg-event-db :counter-inc (fn [db _] (update db :counter inc)))

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
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

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
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

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
    (rf/reg-event-db :seed        (fn [_ _] {:counter 0 :sidebar :open}))
    (rf/reg-event-db :counter-inc (fn [db _] (update db :counter inc)))

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
    (rf/reg-event-db :app/init (fn [_ _] {:booted true :n 0}))
    (rf/reg-event-db :inc      (fn [db _] (update db :n inc)))
    ;; reg-frame dispatch-syncs :on-create (settles epoch 1), THEN emits the
    ;; orphan :rf.frame/created.
    (rf/reg-frame :test/main {:on-create [:app/init]})
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
    (rf/reg-event-db :app/init (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc      (fn [db _] (update db :n inc)))
    (rf/reg-frame :test/main {:on-create [:app/init]})
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
  (testing "rf2-ee38b — the self-cleaning harvest must NOT discard a CHILD's
            dispatch-id marker: a non-nil dispatch-id that differs from the
            settling event's id is a child's `:event/dispatched` marker (fired
            during the parent's do-fx) and stays buffered for the child's own
            settle (Spec 009 §Dispatch correlation: one dispatch-id = one
            epoch). Only nil-id orphans are dropped."
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
      (let [harvested (state/harvest-buffer-for-event! frame)]
        (is (= [run-start body] harvested)
            "the parent's harvest takes only its own (dispatch-id 1) traces")
        ;; The child marker (dispatch-id 2) is RETAINED; the orphan is DROPPED.
        (is (= [child-mark] (state/buffer-for frame))
            "the child's dispatch-id marker stays buffered for the child's own
             settle; the nil-id orphan is discarded")
        (state/drop-frame-buffer! frame)))))

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
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
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
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
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

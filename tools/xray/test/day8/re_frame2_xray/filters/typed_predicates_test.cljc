(ns day8.re-frame2-xray.filters.typed-predicates-test
  "Pure-data tests for the typed-predicate filter matchers (rf2-piye4).

  CLJC so the JVM corpus exercises every kind without a CLJS runtime
  — the matcher is pure data, no atoms, no I/O. Spec/020 §2 catalogues
  the four kinds; this file pins one composition test per kind +
  legacy back-compat + mixed-bucket composition.

  Test cascade shapes use the bucketed projection
  `re-frame.trace.projection/group-by-event` emits (`:handler`, `:fx`,
  `:effects`, `:subs`, `:renders`, `:other`) so the matcher walks the
  same shape it'll see in production."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.filters.typed-predicates :as typed]))

;; ---- helpers -------------------------------------------------------------

(defn- mk-cascade
  "Build a minimal cascade map for testing. `:event` is the bare event
  vector; surface events nest under the projection-style buckets."
  [{:keys [event handler fx effects subs renders other]
    :or   {effects [] subs [] renders [] other []}}]
  (cond-> {}
    event              (assoc :event event)
    handler            (assoc :handler handler)
    fx                 (assoc :fx fx)
    (seq effects)      (assoc :effects effects)
    (seq subs)         (assoc :subs subs)
    (seq renders)      (assoc :renders renders)
    (seq other)        (assoc :other other)))

(defn- tagged
  "Build a synthetic trace event with the given tag map."
  [tags]
  {:operation :synthetic
   :tags      tags})

;; ---- canonicalise-pill --------------------------------------------------

(deftest canonicalise-typed-pill-passes-through
  (is (= {:kind :machine :params {:machine-id :form}}
         (typed/canonicalise-pill
           {:kind :machine :params {:machine-id :form}}))))

(deftest canonicalise-typed-pill-defaults-params
  (testing "missing :params slot defaults to {} so downstream readers
            never NPE"
    (is (= {:kind :machine :params {}}
           (typed/canonicalise-pill {:kind :machine})))))

(deftest canonicalise-pattern-pill-becomes-event-id-pattern
  (testing "`{:pattern <kw-or-str>}` (the dialog's only output shape)
            hydrates as `:event-id-pattern` so persisted pills
            round-trip cleanly. Event-id is the only scope — no :scope
            slot is ever produced (rf2-o8pjv)."
    (is (= {:kind   :event-id-pattern
            :params {:pattern :auth/login}}
           (typed/canonicalise-pill {:pattern :auth/login})))))

(deftest canonicalise-drops-stale-scope-key
  (testing "a pill carrying a stale `:scope` key (persisted before
            rf2-o8pjv) hydrates to the event-id-pattern shape WITHOUT
            the :scope — the matcher honours event-id only, so the
            stale slot is excised on the way through"
    (is (= {:kind   :event-id-pattern
            :params {:pattern :auth/*}}
           (typed/canonicalise-pill {:pattern :auth/*
                                     :scope   #{:event-id :event-args}})))))

(deftest canonicalise-malformed-pill-is-never
  (is (= {:kind :never :params {}}
         (typed/canonicalise-pill nil)))
  (is (= {:kind :never :params {}}
         (typed/canonicalise-pill "not a map")))
  (is (= {:kind :never :params {}}
         (typed/canonicalise-pill {}))))

;; ---- event-bundle-trace-events -----------------------------------------------

(deftest event-bundle-trace-events-walks-every-bucket
  (let [cascade (mk-cascade {:handler {:operation :handler}
                             :fx      {:operation :fx}
                             :effects [{:operation :effect1}
                                       {:operation :effect2}]
                             :subs    [{:operation :sub}]
                             :renders [{:operation :render}]
                             :other   [{:operation :other}]})
        events  (typed/event-bundle-trace-events cascade)
        ops     (set (map :operation events))]
    (is (= #{:handler :fx :effect1 :effect2 :sub :render :other} ops))))

(deftest event-bundle-trace-events-empty-cascade
  (is (empty? (typed/event-bundle-trace-events (mk-cascade {:event [:foo]})))))

;; ---- :event-id-pattern kind ---------------------------------------------

(deftest event-id-pattern-typed-shape
  (let [cascade (mk-cascade {:event [:auth/login]})
        pill    {:kind :event-id-pattern :params {:pattern :auth/*}}]
    (is (typed/event-bundle-matches-pill? cascade pill))))

(deftest event-id-pattern-legacy-shape
  (testing "rf2-ak4ms legacy `{:pattern :auth/*}` still matches via the
            canonicaliser — back-compat with already-persisted pills"
    (let [cascade (mk-cascade {:event [:auth/login]})]
      (is (typed/event-bundle-matches-pill? cascade {:pattern :auth/*}))
      (is (typed/event-bundle-matches-pill? cascade {:pattern :auth/login}))
      (is (not (typed/event-bundle-matches-pill? cascade {:pattern :order/*}))))))

(deftest event-id-pattern-no-match
  (let [cascade (mk-cascade {:event [:order/submit]})]
    (is (not (typed/event-bundle-matches-pill?
               cascade {:kind :event-id-pattern :params {:pattern :auth/*}})))))

;; ---- :machine kind ------------------------------------------------------

(deftest machine-kind-matches-via-handler-tag
  (testing "any trace-event with `:tags :machine-id` matches"
    (let [cascade (mk-cascade {:event   [:user/click]
                               :handler (tagged {:machine-id :form})})
          pill    {:kind :machine :params {:machine-id :form}}]
      (is (typed/event-bundle-matches-pill? cascade pill)))))

(deftest machine-kind-matches-via-effects-tag
  (let [cascade (mk-cascade {:event   [:user/click]
                             :effects [(tagged {:rf.fx/id    :rf.machine/transition
                                                :machine-id :form})]})
        pill    {:kind :machine :params {:machine-id :form}}]
    (is (typed/event-bundle-matches-pill? cascade pill))))

(deftest machine-kind-no-match-different-id
  (let [cascade (mk-cascade {:event   [:user/click]
                             :effects [(tagged {:machine-id :other})]})
        pill    {:kind :machine :params {:machine-id :form}}]
    (is (not (typed/event-bundle-matches-pill? cascade pill)))))

(deftest machine-kind-no-match-no-machine-events
  (let [cascade (mk-cascade {:event   [:user/click]
                             :effects [(tagged {:rf.fx/id :db})]})
        pill    {:kind :machine :params {:machine-id :form}}]
    (is (not (typed/event-bundle-matches-pill? cascade pill)))))

(deftest machine-kind-nil-target-never-matches
  (testing "nil `:machine-id` in the pill — guards against a half-filled
            programmatic dispatch"
    (let [cascade (mk-cascade {:event   [:foo]
                               :effects [(tagged {:machine-id :form})]})
          pill    {:kind :machine :params {:machine-id nil}}]
      (is (not (typed/event-bundle-matches-pill? cascade pill))))))

;; ---- :http-correlation kind ---------------------------------------------

(deftest http-correlation-matches-issuing-effect
  (let [cascade (mk-cascade {:event   [:user/load]
                             :effects [(tagged {:rf.fx/id          :rf.http/managed
                                                :correlation-id "abc-123"})]})
        pill    {:kind :http-correlation
                 :params {:correlation-id "abc-123"}}]
    (is (typed/event-bundle-matches-pill? cascade pill))))

(deftest http-correlation-matches-response-event
  (testing "the same correlation-id stamps both issuing fx and response
            trace events — a single pill captures the whole exchange"
    (let [cascade (mk-cascade {:event [:rf.http/response]
                               :other [(tagged {:operation      :rf.http/received
                                                :correlation-id "abc-123"})]})
          pill    {:kind :http-correlation
                   :params {:correlation-id "abc-123"}}]
      (is (typed/event-bundle-matches-pill? cascade pill)))))

(deftest http-correlation-no-match
  (let [cascade (mk-cascade {:event   [:user/load]
                             :effects [(tagged {:correlation-id "different"})]})
        pill    {:kind :http-correlation
                 :params {:correlation-id "abc-123"}}]
    (is (not (typed/event-bundle-matches-pill? cascade pill)))))

;; ---- :fx kind -----------------------------------------------------------

(deftest fx-kind-matches-by-fx-id
  (let [cascade (mk-cascade {:event   [:user/load]
                             :effects [(tagged {:rf.fx/id :rf.http/managed})]})
        pill    {:kind :fx :params {:fx-id :rf.http/managed}}]
    (is (typed/event-bundle-matches-pill? cascade pill))))

(deftest fx-kind-matches-via-fx-bucket
  (let [cascade (mk-cascade {:event [:user/load]
                             :fx    (tagged {:rf.fx/id :rf.http/managed})})
        pill    {:kind :fx :params {:fx-id :rf.http/managed}}]
    (is (typed/event-bundle-matches-pill? cascade pill))))

(deftest fx-kind-no-match-different-fx
  (let [cascade (mk-cascade {:event   [:user/load]
                             :effects [(tagged {:rf.fx/id :db})]})
        pill    {:kind :fx :params {:fx-id :rf.http/managed}}]
    (is (not (typed/event-bundle-matches-pill? cascade pill)))))

;; ---- composition: IN bucket OR within, AND across modes -----------------

(deftest in-bucket-composes-with-or
  (testing "spec/018 §7 — within-bucket pills OR together"
    (let [cascade-a (mk-cascade {:event   [:user/click]
                                 :effects [(tagged {:machine-id :form})]})
          cascade-b (mk-cascade {:event   [:user/load]
                                 :effects [(tagged {:rf.fx/id :rf.http/managed
                                                    :correlation-id "abc"})]})
          filters   {:in [{:kind :machine :params {:machine-id :form}}
                          {:kind :http-correlation :params {:correlation-id "abc"}}]
                     :out []}]
      (is (typed/keep-event-bundle? cascade-a filters))
      (is (typed/keep-event-bundle? cascade-b filters)))))

(deftest in-out-composition
  (testing "spec/018 §7 — IN bucket AND NOT OUT bucket"
    (let [cascade (mk-cascade {:event   [:user/click]
                               :effects [(tagged {:machine-id :form})]})
          ;; IN matches via machine, but OUT also matches via the
          ;; event-id pattern → drop
          filters {:in  [{:kind :machine :params {:machine-id :form}}]
                   :out [{:kind   :event-id-pattern
                          :params {:pattern :user/*}}]}]
      (is (not (typed/keep-event-bundle? cascade filters))))))

(deftest mixed-bucket-typed-and-legacy
  (testing "typed pills and legacy keyword-pattern pills compose inside
            the same bucket — the canonicaliser handles both shapes"
    (let [cascade-a (mk-cascade {:event   [:auth/login]
                                 :effects [(tagged {:rf.fx/id :rf.fx/handled})]})
          cascade-b (mk-cascade {:event   [:user/click]
                                 :effects [(tagged {:machine-id :form})]})
          ;; IN: legacy pattern OR typed machine. Both cascades survive.
          filters   {:in [{:pattern :auth/*}
                          {:kind :machine :params {:machine-id :form}}]
                     :out []}]
      (is (typed/keep-event-bundle? cascade-a filters))
      (is (typed/keep-event-bundle? cascade-b filters)))))

(deftest empty-filters-keep-everything
  (let [cascade (mk-cascade {:event [:anything]})]
    (is (typed/keep-event-bundle? cascade {:in [] :out []}))))

(deftest filter-event-bundles-preserves-order
  (let [c1 (assoc (mk-cascade {:event   [:user/load]
                               :effects [(tagged {:rf.fx/id :rf.http/managed})]})
                  :dispatch-id 1)
        c2 (assoc (mk-cascade {:event [:other]})
                  :dispatch-id 2)
        c3 (assoc (mk-cascade {:event   [:user/load2]
                               :effects [(tagged {:rf.fx/id :rf.http/managed})]})
                  :dispatch-id 3)
        filters {:in  [{:kind :fx :params {:fx-id :rf.http/managed}}]
                 :out []}]
    (is (= [1 3] (mapv :dispatch-id
                       (typed/filter-event-bundles [c1 c2 c3] filters))))))

;; ---- causal-parent matching under epoch-per-event (rf2-a1eld) ------------
;;
;; Under epoch-per-event the spawning event and the machine/http/fx
;; transition it triggers are SEPARATE cascades linked by
;; `:parent-dispatch-id` (the projection surfaces it off the dispatched
;; event's `:rf.trace/parent-dispatch-id` tag). A typed IN pill on the
;; CHILD transition cascade must also keep the PARENT spawning cascade —
;; the whole lineage back to the originating user event.

(defn- mk-child-cascade
  "A cascade carrying both a `:dispatch-id` and the causal-parent link."
  [{:keys [dispatch-id parent-dispatch-id] :as opts}]
  (-> (mk-cascade (dissoc opts :dispatch-id :parent-dispatch-id))
      (assoc :dispatch-id dispatch-id
             :parent-dispatch-id parent-dispatch-id)))

(deftest machine-pill-keeps-spawning-parent-cascade
  (testing "a :machine pill on the machine-transition CHILD cascade also
            keeps the PARENT event that spawned the transition (rf2-a1eld)"
    (let [;; parent: the user event that spawned the transition. Carries
          ;; NO machine tag of its own — it only matches via the child.
          parent  (mk-child-cascade {:dispatch-id 10
                                     :event       [:user/submit-form]})
          ;; child: the machine transition, its own epoch under
          ;; epoch-per-event, linked back to the parent.
          child   (mk-child-cascade {:dispatch-id        20
                                     :parent-dispatch-id 10
                                     :event             [:rf.machine/transition]
                                     :effects           [(tagged {:machine-id :form})]})
          ;; unrelated cascade — no machine, no link → dropped.
          other   (mk-child-cascade {:dispatch-id 30
                                     :event       [:other/thing]})
          filters {:in  [{:kind :machine :params {:machine-id :form}}]
                   :out []}
          kept    (mapv :dispatch-id
                        (typed/filter-event-bundles [parent child other] filters))]
      (is (= [10 20] kept)
          "both the spawning parent AND the machine child survive; the
           unrelated cascade is dropped"))))

(deftest http-pill-keeps-spawning-parent-cascade
  (testing "an :http-correlation pill on the response CHILD cascade also
            keeps the PARENT event that issued the request (rf2-a1eld)"
    (let [parent  (mk-child-cascade {:dispatch-id 100
                                     :event       [:user/load-page]})
          child   (mk-child-cascade {:dispatch-id        200
                                     :parent-dispatch-id 100
                                     :event             [:rf.http/response]
                                     :other             [(tagged {:correlation-id "abc-123"})]})
          other   (mk-child-cascade {:dispatch-id 300
                                     :event       [:other/thing]})
          filters {:in  [{:kind :http-correlation
                          :params {:correlation-id "abc-123"}}]
                   :out []}
          kept    (mapv :dispatch-id
                        (typed/filter-event-bundles [parent child other] filters))]
      (is (= [100 200] kept)
          "both the request-issuing parent AND the http child survive"))))

(deftest fx-pill-keeps-spawning-parent-cascade
  (testing "an :fx pill on the fx-triggering CHILD cascade also keeps the
            PARENT event that spawned it (rf2-a1eld)"
    (let [parent  (mk-child-cascade {:dispatch-id 1
                                     :event       [:user/click]})
          child   (mk-child-cascade {:dispatch-id        2
                                     :parent-dispatch-id 1
                                     :event             [:do/work]
                                     :effects           [(tagged {:rf.fx/id :rf.http/managed})]})
          other   (mk-child-cascade {:dispatch-id 3
                                     :event       [:other/thing]})
          filters {:in  [{:kind :fx :params {:fx-id :rf.http/managed}}]
                   :out []}
          kept    (mapv :dispatch-id
                        (typed/filter-event-bundles [parent child other] filters))]
      (is (= [1 2] kept)
          "both the spawning parent AND the fx child survive"))))

(deftest typed-pill-walks-full-chain-to-root
  (testing "the ancestor walk is whole-chain: a :machine pill on a deep
            grandchild surfaces every cascade up to the root user event
            (rf2-a1eld design call — strict superset, no over-matching)"
    (let [root  (mk-child-cascade {:dispatch-id 1
                                   :event       [:user/click]})
          mid   (mk-child-cascade {:dispatch-id        2
                                   :parent-dispatch-id 1
                                   :event             [:fx/dispatched-child]})
          leaf  (mk-child-cascade {:dispatch-id        3
                                   :parent-dispatch-id 2
                                   :event             [:rf.machine/transition]
                                   :effects           [(tagged {:machine-id :form})]})
          filters {:in  [{:kind :machine :params {:machine-id :form}}]
                   :out []}
          kept    (mapv :dispatch-id
                        (typed/filter-event-bundles [root mid leaf] filters))]
      (is (= [1 2 3] kept)
          "root → mid → leaf all survive via the whole-chain walk"))))

(deftest out-pill-stays-event-bundle-local-does-not-drop-ancestor
  (testing "an OUT (hide) pill suppresses only the cascade carrying its
            tag, never an ancestor — hiding a child's fx must not silently
            drop the originating user event (rf2-a1eld)"
    (let [parent  (mk-child-cascade {:dispatch-id 1
                                     :event       [:user/click]})
          child   (mk-child-cascade {:dispatch-id        2
                                     :parent-dispatch-id 1
                                     :event             [:do/work]
                                     :effects           [(tagged {:rf.fx/id :noisy/fx})]})
          filters {:in  []
                   :out [{:kind :fx :params {:fx-id :noisy/fx}}]}
          kept    (mapv :dispatch-id
                        (typed/filter-event-bundles [parent child] filters))]
      (is (= [1] kept)
          "only the tagged child is hidden; the parent survives"))))

(deftest parent-walk-cycle-guarded
  (testing "a malformed trace with a parent loop terminates the walk
            rather than spinning (rf2-a1eld defensive guard)"
    (let [a (mk-child-cascade {:dispatch-id 1 :parent-dispatch-id 2
                               :event [:a]})
          b (mk-child-cascade {:dispatch-id 2 :parent-dispatch-id 1
                               :event       [:b]
                               :effects     [(tagged {:machine-id :form})]})
          filters {:in  [{:kind :machine :params {:machine-id :form}}]
                   :out []}
          ;; both match (a reaches b via its parent link; b matches
          ;; directly) and the walk does NOT hang on the 1↔2 cycle.
          kept    (mapv :dispatch-id (typed/filter-event-bundles [a b] filters))]
      (is (= [1 2] kept)))))

;; ---- pill-label / pill-glyph --------------------------------------------

(deftest pill-label-per-kind
  (is (= ":auth/login"
         (typed/pill-label {:kind :event-id-pattern :params {:pattern :auth/login}})))
  (is (= ":form"
         (typed/pill-label {:kind :machine :params {:machine-id :form}})))
  (is (= "abc-123"
         (typed/pill-label {:kind :http-correlation :params {:correlation-id "abc-123"}})))
  (is (= ":rf.http/managed"
         (typed/pill-label {:kind :fx :params {:fx-id :rf.http/managed}}))))

(deftest pill-label-legacy-shape-renders
  (is (= ":auth/*"
         (typed/pill-label {:pattern :auth/*}))))

(deftest pill-glyph-per-kind
  (is (nil? (typed/pill-glyph {:kind :event-id-pattern :params {:pattern :auth/*}})))
  (is (= "M" (typed/pill-glyph {:kind :machine :params {:machine-id :form}})))
  (is (= "H" (typed/pill-glyph {:kind :http-correlation :params {:correlation-id "x"}})))
  (is (= "F" (typed/pill-glyph {:kind :fx :params {:fx-id :foo}}))))

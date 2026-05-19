(ns day8.re-frame2-causa.filters.typed-predicates-test
  "Pure-data tests for the typed-predicate filter matchers (rf2-piye4).

  CLJC so the JVM corpus exercises every kind without a CLJS runtime
  — the matcher is pure data, no atoms, no I/O. Spec/020 §2 catalogues
  the four kinds; this file pins one composition test per kind +
  legacy back-compat + mixed-bucket composition.

  Test cascade shapes use the bucketed projection
  `re-frame.trace.projection/group-cascades` emits (`:handler`, `:fx`,
  `:effects`, `:subs`, `:renders`, `:other`) so the matcher walks the
  same shape it'll see in production."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-causa.filters.typed-predicates :as typed]))

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

(deftest canonicalise-legacy-pattern-pill-becomes-event-id-pattern
  (testing "rf2-ak4ms legacy shape `{:pattern <kw-or-str>}` hydrates as
            `:event-id-pattern` so persisted pills round-trip cleanly"
    (is (= {:kind   :event-id-pattern
            :params {:pattern :auth/login
                     :scope   #{:event-id}}}
           (typed/canonicalise-pill {:pattern :auth/login})))))

(deftest canonicalise-legacy-pill-preserves-scope
  (is (= {:kind   :event-id-pattern
          :params {:pattern :auth/*
                   :scope   #{:event-id :event-args}}}
         (typed/canonicalise-pill {:pattern :auth/*
                                   :scope   #{:event-id :event-args}}))))

(deftest canonicalise-malformed-pill-is-never
  (is (= {:kind :never :params {}}
         (typed/canonicalise-pill nil)))
  (is (= {:kind :never :params {}}
         (typed/canonicalise-pill "not a map")))
  (is (= {:kind :never :params {}}
         (typed/canonicalise-pill {}))))

;; ---- cascade-trace-events -----------------------------------------------

(deftest cascade-trace-events-walks-every-bucket
  (let [cascade (mk-cascade {:handler {:operation :handler}
                             :fx      {:operation :fx}
                             :effects [{:operation :effect1}
                                       {:operation :effect2}]
                             :subs    [{:operation :sub}]
                             :renders [{:operation :render}]
                             :other   [{:operation :other}]})
        events  (typed/cascade-trace-events cascade)
        ops     (set (map :operation events))]
    (is (= #{:handler :fx :effect1 :effect2 :sub :render :other} ops))))

(deftest cascade-trace-events-empty-cascade
  (is (empty? (typed/cascade-trace-events (mk-cascade {:event [:foo]})))))

;; ---- :event-id-pattern kind ---------------------------------------------

(deftest event-id-pattern-typed-shape
  (let [cascade (mk-cascade {:event [:auth/login]})
        pill    {:kind :event-id-pattern :params {:pattern :auth/*}}]
    (is (typed/cascade-matches-pill? cascade pill))))

(deftest event-id-pattern-legacy-shape
  (testing "rf2-ak4ms legacy `{:pattern :auth/*}` still matches via the
            canonicaliser — back-compat with already-persisted pills"
    (let [cascade (mk-cascade {:event [:auth/login]})]
      (is (typed/cascade-matches-pill? cascade {:pattern :auth/*}))
      (is (typed/cascade-matches-pill? cascade {:pattern :auth/login}))
      (is (not (typed/cascade-matches-pill? cascade {:pattern :order/*}))))))

(deftest event-id-pattern-no-match
  (let [cascade (mk-cascade {:event [:order/submit]})]
    (is (not (typed/cascade-matches-pill?
               cascade {:kind :event-id-pattern :params {:pattern :auth/*}})))))

;; ---- :machine kind ------------------------------------------------------

(deftest machine-kind-matches-via-handler-tag
  (testing "any trace-event with `:tags :machine-id` matches"
    (let [cascade (mk-cascade {:event   [:user/click]
                               :handler (tagged {:machine-id :form})})
          pill    {:kind :machine :params {:machine-id :form}}]
      (is (typed/cascade-matches-pill? cascade pill)))))

(deftest machine-kind-matches-via-effects-tag
  (let [cascade (mk-cascade {:event   [:user/click]
                             :effects [(tagged {:fx-id    :rf.machine/transition
                                                :machine-id :form})]})
        pill    {:kind :machine :params {:machine-id :form}}]
    (is (typed/cascade-matches-pill? cascade pill))))

(deftest machine-kind-no-match-different-id
  (let [cascade (mk-cascade {:event   [:user/click]
                             :effects [(tagged {:machine-id :other})]})
        pill    {:kind :machine :params {:machine-id :form}}]
    (is (not (typed/cascade-matches-pill? cascade pill)))))

(deftest machine-kind-no-match-no-machine-events
  (let [cascade (mk-cascade {:event   [:user/click]
                             :effects [(tagged {:fx-id :db})]})
        pill    {:kind :machine :params {:machine-id :form}}]
    (is (not (typed/cascade-matches-pill? cascade pill)))))

(deftest machine-kind-nil-target-never-matches
  (testing "nil `:machine-id` in the pill — guards against a half-filled
            programmatic dispatch"
    (let [cascade (mk-cascade {:event   [:foo]
                               :effects [(tagged {:machine-id :form})]})
          pill    {:kind :machine :params {:machine-id nil}}]
      (is (not (typed/cascade-matches-pill? cascade pill))))))

;; ---- :http-correlation kind ---------------------------------------------

(deftest http-correlation-matches-issuing-effect
  (let [cascade (mk-cascade {:event   [:user/load]
                             :effects [(tagged {:fx-id          :rf.http/managed
                                                :correlation-id "abc-123"})]})
        pill    {:kind :http-correlation
                 :params {:correlation-id "abc-123"}}]
    (is (typed/cascade-matches-pill? cascade pill))))

(deftest http-correlation-matches-response-event
  (testing "the same correlation-id stamps both issuing fx and response
            trace events — a single pill captures the whole exchange"
    (let [cascade (mk-cascade {:event [:rf.http/response]
                               :other [(tagged {:operation      :rf.http/received
                                                :correlation-id "abc-123"})]})
          pill    {:kind :http-correlation
                   :params {:correlation-id "abc-123"}}]
      (is (typed/cascade-matches-pill? cascade pill)))))

(deftest http-correlation-no-match
  (let [cascade (mk-cascade {:event   [:user/load]
                             :effects [(tagged {:correlation-id "different"})]})
        pill    {:kind :http-correlation
                 :params {:correlation-id "abc-123"}}]
    (is (not (typed/cascade-matches-pill? cascade pill)))))

;; ---- :fx kind -----------------------------------------------------------

(deftest fx-kind-matches-by-fx-id
  (let [cascade (mk-cascade {:event   [:user/load]
                             :effects [(tagged {:fx-id :rf.http/managed})]})
        pill    {:kind :fx :params {:fx-id :rf.http/managed}}]
    (is (typed/cascade-matches-pill? cascade pill))))

(deftest fx-kind-matches-via-fx-bucket
  (let [cascade (mk-cascade {:event [:user/load]
                             :fx    (tagged {:fx-id :rf.http/managed})})
        pill    {:kind :fx :params {:fx-id :rf.http/managed}}]
    (is (typed/cascade-matches-pill? cascade pill))))

(deftest fx-kind-no-match-different-fx
  (let [cascade (mk-cascade {:event   [:user/load]
                             :effects [(tagged {:fx-id :db})]})
        pill    {:kind :fx :params {:fx-id :rf.http/managed}}]
    (is (not (typed/cascade-matches-pill? cascade pill)))))

;; ---- composition: IN bucket OR within, AND across modes -----------------

(deftest in-bucket-composes-with-or
  (testing "spec/018 §7 — within-bucket pills OR together"
    (let [cascade-a (mk-cascade {:event   [:user/click]
                                 :effects [(tagged {:machine-id :form})]})
          cascade-b (mk-cascade {:event   [:user/load]
                                 :effects [(tagged {:fx-id :rf.http/managed
                                                    :correlation-id "abc"})]})
          filters   {:in [{:kind :machine :params {:machine-id :form}}
                          {:kind :http-correlation :params {:correlation-id "abc"}}]
                     :out []}]
      (is (typed/keep-cascade? cascade-a filters))
      (is (typed/keep-cascade? cascade-b filters)))))

(deftest in-out-composition
  (testing "spec/018 §7 — IN bucket AND NOT OUT bucket"
    (let [cascade (mk-cascade {:event   [:user/click]
                               :effects [(tagged {:machine-id :form})]})
          ;; IN matches via machine, but OUT also matches via the
          ;; event-id pattern → drop
          filters {:in  [{:kind :machine :params {:machine-id :form}}]
                   :out [{:kind   :event-id-pattern
                          :params {:pattern :user/*}}]}]
      (is (not (typed/keep-cascade? cascade filters))))))

(deftest mixed-bucket-typed-and-legacy
  (testing "typed pills and legacy keyword-pattern pills compose inside
            the same bucket — the canonicaliser handles both shapes"
    (let [cascade-a (mk-cascade {:event   [:auth/login]
                                 :effects [(tagged {:fx-id :rf.fx/handled})]})
          cascade-b (mk-cascade {:event   [:user/click]
                                 :effects [(tagged {:machine-id :form})]})
          ;; IN: legacy pattern OR typed machine. Both cascades survive.
          filters   {:in [{:pattern :auth/*}
                          {:kind :machine :params {:machine-id :form}}]
                     :out []}]
      (is (typed/keep-cascade? cascade-a filters))
      (is (typed/keep-cascade? cascade-b filters)))))

(deftest empty-filters-keep-everything
  (let [cascade (mk-cascade {:event [:anything]})]
    (is (typed/keep-cascade? cascade {:in [] :out []}))))

(deftest filter-cascades-preserves-order
  (let [c1 (assoc (mk-cascade {:event   [:user/load]
                               :effects [(tagged {:fx-id :rf.http/managed})]})
                  :dispatch-id 1)
        c2 (assoc (mk-cascade {:event [:other]})
                  :dispatch-id 2)
        c3 (assoc (mk-cascade {:event   [:user/load2]
                               :effects [(tagged {:fx-id :rf.http/managed})]})
                  :dispatch-id 3)
        filters {:in  [{:kind :fx :params {:fx-id :rf.http/managed}}]
                 :out []}]
    (is (= [1 3] (mapv :dispatch-id
                       (typed/filter-cascades [c1 c2 c3] filters))))))

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

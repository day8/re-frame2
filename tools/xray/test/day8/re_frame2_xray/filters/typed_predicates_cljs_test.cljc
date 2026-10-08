(ns day8.re-frame2-xray.filters.typed-predicates-cljs-test
  "Pure-data tests for the typed-predicate filter matchers and their IN/OUT
  composition. Bundles use the bucketed shape
  `re-frame.trace.projection/group-by-event` emits (`:handler`, `:fx`,
  `:effects`, `:subs`, `:renders`, `:other`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.filters.typed-predicates :as typed]))

(def ^:private machine-in
  {:in [{:kind :machine :params {:machine-id :form}}] :out []})

;; Producer shapes from a driven `:rf.http/managed` request: the issuing
;; `:rf.fx/handled` row carries the caller's id under `:tags :rf.fx/args`,
;; and the reply-target run's event vector carries the reply map's
;; `:correlation`.

(defn- issuing-fx-row [args]
  {:tags {:rf.fx/id :rf.http/managed :rf.fx/args args :frame :rf/default}})

;; ---- event-bundle-trace-events -----------------------------------------------

(deftest event-bundle-trace-events-walks-every-bucket
  (is (= #{:handler :fx :effect1 :effect2 :sub :render :other}
         (set (map :operation
                   (typed/event-bundle-trace-events
                     {:handler {:operation :handler}
                      :fx      {:operation :fx}
                      :effects [{:operation :effect1} {:operation :effect2}]
                      :subs    [{:operation :sub}]
                      :renders [{:operation :render}]
                      :other   [{:operation :other}]}))))))

;; ---- per-kind matchers --------------------------------------------------

(deftest event-id-pattern-legacy-shape
  (testing "the bare `{:pattern …}` pill the Add-filter popup writes matches
            through the canonicaliser"
    (let [bundle {:event [:auth/login]}]
      (is (typed/event-bundle-matches-pill? bundle {:pattern :auth/*}))
      (is (not (typed/event-bundle-matches-pill? bundle {:pattern :order/*}))))))

(deftest http-correlation-matches-reply-dispatch-bundle
  (testing "the reply-target run matches on the reply map's :correlation,
            carried on its dispatched event vector"
    (is (typed/event-bundle-matches-pill?
          {:event [:article/load {:slug "hello"}
                   {:status :ok :correlation {:request-id "abc-123"}}]}
          {:kind :http-correlation :params {:correlation-id "abc-123"}}))))

(deftest http-correlation-matches-non-http-surface-args
  (testing "the pill carries whichever caller-id key the record's surface
            uses — a websocket record's :socket-id here"
    (is (typed/event-bundle-matches-pill?
          {:event [:socket/open] :effects [(issuing-fx-row {:socket-id :sock-1})]}
          {:kind :http-correlation :params {:correlation-id :sock-1}}))))

(deftest http-correlation-ignores-the-ungrouped-completion-row
  (testing "the `:rf.http/replied` completion row lands in the shared
            :ungrouped bundle, which holds unrelated exchanges' rows, so it
            is deliberately NOT this exchange's"
    (is (not (typed/event-bundle-matches-pill?
               {:other [{:tags {:rf.reply/work-id   [:rf.work/http "abc-123" 1 1]
                                :rf.reply/work-kind :http
                                :correlation        {:request-id "abc-123"}
                                :status             :ok}}]}
               {:kind :http-correlation :params {:correlation-id "abc-123"}})))))

;; ---- composition (spec/018 §7) ------------------------------------------

(deftest in-bucket-composes-with-or
  (testing "IN pills — bare or typed, any kind — OR together; a bundle no
            pill matches (another machine, another request id) drops"
    (let [by-pattern {:dispatch-id 1 :event [:auth/login]}
          by-machine {:dispatch-id 2 :event [:user/click]
                      :handler {:tags {:machine-id :form}}}
          by-http    {:dispatch-id 3 :event [:user/load]
                      :effects [(issuing-fx-row {:request-id "abc"})]}
          no-match   {:dispatch-id 4 :event [:user/save]
                      :effects [{:tags {:machine-id :other}}
                                (issuing-fx-row {:request-id "different"})]}]
      (is (= [by-pattern by-machine by-http]
             (typed/filter-event-bundles
               [by-pattern by-machine by-http no-match]
               {:in  [{:pattern :auth/*}
                      {:kind :machine :params {:machine-id :form}}
                      {:kind :http-correlation :params {:correlation-id "abc"}}]
                :out []}))))))

(deftest in-out-composition
  (testing "keep = matches IN AND NOT matches OUT"
    (let [hidden {:dispatch-id 1 :event [:user/click]
                  :effects [{:tags {:machine-id :form}}]}
          shown  {:dispatch-id 2 :event [:cart/add]
                  :effects [{:tags {:machine-id :form}}]}]
      (is (= [shown]
             (typed/filter-event-bundles
               [hidden shown]
               (assoc machine-in :out [{:kind   :event-id-pattern
                                        :params {:pattern :user/*}}])))))))

(deftest filter-event-bundles-preserves-order
  (let [c1 {:dispatch-id 1 :event [:user/load]
            :effects [{:tags {:rf.fx/id :rf.http/managed}}]}
        c2 {:dispatch-id 2 :event [:user/save]
            :effects [{:tags {:rf.fx/id :db}}]}
        c3 {:dispatch-id 3 :event [:user/load2]
            :fx {:tags {:rf.fx/id :rf.http/managed}}}]
    (is (= [c1 c3]
           (typed/filter-event-bundles
             [c1 c2 c3]
             {:in [{:kind :fx :params {:fx-id :rf.http/managed}}] :out []})))))

;; ---- causal lineage, keyed by frame-qualified identity ------------------
;;
;; A matching bundle keeps its causal ancestors (walked up
;; `:parent-dispatch-id`). Dispatch-ids are unique only within a frame, so
;; the walk keys on `[frame dispatch-id]`.

(deftest typed-pill-walks-full-chain-to-root
  (testing "a :machine pill on a grandchild keeps every ancestor up to the
            root user event"
    (let [root {:dispatch-id 1 :event [:user/click]}
          mid  {:dispatch-id 2 :parent-dispatch-id 1 :event [:fx/dispatched-child]}
          leaf {:dispatch-id 3 :parent-dispatch-id 2 :event [:rf.machine/transition]
                :effects [{:tags {:machine-id :form}}]}]
      (is (= [root mid leaf]
             (typed/filter-event-bundles [root mid leaf] machine-in))))))

(deftest typed-filter-does-not-cross-link-frames-sharing-root-id
  (testing "frames A and B both use dispatch-id 1; a lineage rooted at [:a 1]
            must not retain the unrelated [:b 1]"
    (let [a-root  {:frame :a :dispatch-id 1 :event [:a/root]}
          b-root  {:frame :b :dispatch-id 1 :event [:b/root]}
          a-child {:frame :a :dispatch-id 2 :parent-dispatch-id 1 :event [:a/child]
                   :effects [{:tags {:machine-id :form}}]}]
      (is (= [a-root a-child]
             (typed/filter-event-bundles [a-root b-root a-child] machine-in))))))

(deftest cross-frame-cycle-guard-is-frame-qualified
  (testing "each frame carries a 1↔2 parent loop on the same ids; frame A's
            walk terminates inside frame A and frame B is untouched"
    (let [a1 {:frame :a :dispatch-id 1 :parent-dispatch-id 2 :event [:a1]}
          a2 {:frame :a :dispatch-id 2 :parent-dispatch-id 1 :event [:a2]
              :effects [{:tags {:machine-id :form}}]}
          b1 {:frame :b :dispatch-id 1 :parent-dispatch-id 2 :event [:b1]}
          b2 {:frame :b :dispatch-id 2 :parent-dispatch-id 1 :event [:b2]}]
      (is (= [a1 a2]
             (typed/filter-event-bundles [a1 a2 b1 b2] machine-in))))))

(deftest out-pill-stays-frame-local-across-frames
  (testing "an OUT pill hides only the bundle it matches: the child's parent
            and frame B's same-id bundle both stay"
    (let [a-parent {:frame :a :dispatch-id 1 :event [:a/click]}
          a-child  {:frame :a :dispatch-id 2 :parent-dispatch-id 1 :event [:a/work]
                    :effects [{:tags {:rf.fx/id :noisy/fx}}]}
          b-root   {:frame :b :dispatch-id 2 :event [:b/root]}]
      (is (= [a-parent b-root]
             (typed/filter-event-bundles
               [a-parent a-child b-root]
               {:in [] :out [{:kind :fx :params {:fx-id :noisy/fx}}]}))))))

;; ---- pill-label / pill-glyph --------------------------------------------

(deftest pill-label-per-kind
  (is (= ":auth/*" (typed/pill-label {:pattern :auth/*})))
  (is (= ":form" (typed/pill-label {:kind :machine :params {:machine-id :form}})))
  (is (= "abc-123"
         (typed/pill-label {:kind :http-correlation :params {:correlation-id "abc-123"}})))
  (is (= ":rf.http/managed"
         (typed/pill-label {:kind :fx :params {:fx-id :rf.http/managed}}))))

(deftest pill-glyph-per-kind
  (testing "a pattern pill shows its bare label (spec/018 §7); a typed pill
            leads with its kind glyph"
    (is (nil? (typed/pill-glyph {:kind :event-id-pattern :params {:pattern :auth/*}})))
    (is (= "M" (typed/pill-glyph {:kind :machine :params {:machine-id :form}})))))

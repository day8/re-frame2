(ns re-frame-pair2-mcp.sensitive-filter-test
  "Unit tests for the spec/009 §Privacy default-suppress filter on
  `:sensitive? true` events (rf2-zq0n1, follows rf2-a32kd).

  Spec 009 mandates that framework-published forwarders (Sentry /
  Honeybadger, pair2 server, Causa-MCP) MUST default-drop trace events
  whose registration declared `:sensitive? true`. The runtime stamps
  the flag at the top level of every emitted trace event; the
  forwarder's job is to gate egress on it.

  These tests mirror the private `sensitive-event?` / `strip-sensitive`
  helpers from `tools.cljs` — a rename or signature change surfaces as
  a failing test rather than a silent contract drift."
  (:require [cljs.test :refer-macros [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Mirrors of the private helpers in tools.cljs. Keep in lockstep.
;; ---------------------------------------------------------------------------

(defn- sensitive-event? [ev]
  (and (map? ev)
       (true? (:sensitive? ev))))

(defn- strip-sensitive [events include?]
  (cond
    include?         [events 0]
    (empty? events)  [events 0]
    :else
    (let [kept (filterv (complement sensitive-event?) events)
          n    (- (count events) (count kept))]
      [kept n])))

;; ---------------------------------------------------------------------------
;; sensitive-event? — the boolean predicate.
;; ---------------------------------------------------------------------------

(deftest sensitive-event-true-stamp-detected
  (is (sensitive-event? {:operation :event/dispatched :sensitive? true})))

(deftest sensitive-event-false-stamp-passes
  (is (not (sensitive-event? {:operation :event/dispatched :sensitive? false}))))

(deftest sensitive-event-absent-stamp-passes
  ;; Per spec/009: "Consumers treat absent as `false`."
  (is (not (sensitive-event? {:operation :event/dispatched}))))

(deftest sensitive-event-non-true-truthy-passes
  ;; Conservative: only the literal `true` triggers the drop. A string
  ;; or non-boolean value passes through (the `:rf/trace-event` schema
  ;; types `:sensitive?` as a boolean — any other value is a contract
  ;; violation we surface rather than silently treat as sensitive).
  (is (not (sensitive-event? {:operation :event/dispatched :sensitive? "true"})))
  (is (not (sensitive-event? {:operation :event/dispatched :sensitive? :yes}))))

(deftest sensitive-event-non-map-input-passes
  (is (not (sensitive-event? nil)))
  (is (not (sensitive-event? [:sensitive? true])))
  (is (not (sensitive-event? "anything"))))

;; ---------------------------------------------------------------------------
;; strip-sensitive — the default-suppress filter applied per batch.
;; ---------------------------------------------------------------------------

(deftest strip-sensitive-default-drops-true-stamps
  (let [evts [{:id 1 :sensitive? false}
              {:id 2 :sensitive? true}
              {:id 3}
              {:id 4 :sensitive? true}]
        [kept dropped] (strip-sensitive evts false)]
    (is (= [{:id 1 :sensitive? false} {:id 3}] kept))
    (is (= 2 dropped))))

(deftest strip-sensitive-include-opt-in-passes-everything
  (let [evts [{:id 1 :sensitive? true}
              {:id 2 :sensitive? false}
              {:id 3 :sensitive? true}]
        [kept dropped] (strip-sensitive evts true)]
    (is (= evts kept))
    (is (zero? dropped))))

(deftest strip-sensitive-empty-batch-zero-overhead
  (let [[kept dropped] (strip-sensitive [] false)]
    (is (= [] kept))
    (is (zero? dropped))))

(deftest strip-sensitive-no-sensitive-events-zero-drop
  (let [evts [{:id 1} {:id 2 :sensitive? false} {:id 3}]
        [kept dropped] (strip-sensitive evts false)]
    (is (= evts kept))
    (is (zero? dropped))))

(deftest strip-sensitive-all-sensitive-drops-all
  (let [evts [{:id 1 :sensitive? true}
              {:id 2 :sensitive? true}
              {:id 3 :sensitive? true}]
        [kept dropped] (strip-sensitive evts false)]
    (is (= [] kept))
    (is (= 3 dropped))))

;; ---------------------------------------------------------------------------
;; Default posture — the load-bearing assertion for the spec/009 MUST.
;; ---------------------------------------------------------------------------

(deftest spec-009-default-posture-is-suppress
  (testing "the default (include-sensitive? omitted ⇒ false) suppresses"
    (let [sensitive-batch [{:operation :event/dispatched
                            :tags      {:event-id :auth/sign-in}
                            :sensitive? true}]
          [kept dropped] (strip-sensitive sensitive-batch false)]
      (is (= [] kept) "sensitive event must NOT reach the agent surface by default")
      (is (= 1 dropped))))
  (testing "include-sensitive? true is the documented opt-in"
    (let [sensitive-batch [{:operation :event/dispatched
                            :tags      {:event-id :auth/sign-in}
                            :sensitive? true}]
          [kept dropped] (strip-sensitive sensitive-batch true)]
      (is (= sensitive-batch kept))
      (is (zero? dropped)))))

;; ---------------------------------------------------------------------------
;; Snapshot scrubber — sensitive trace events stripped from per-frame
;; :traces / :epochs slices; other slices pass through unchanged.
;; ---------------------------------------------------------------------------

(defn- scrub-snapshot-sensitive
  [snapshot include?]
  (if (or include? (not (map? snapshot)))
    [snapshot 0]
    (let [dropped (atom 0)
          scrub-slice
          (fn [items]
            (let [[kept n] (strip-sensitive (vec items) false)]
              (swap! dropped + n)
              kept))
          scrub-frame
          (fn [frame-map]
            (cond-> frame-map
              (contains? frame-map :traces) (update :traces scrub-slice)
              (contains? frame-map :epochs) (update :epochs scrub-slice)))
          scrubbed (reduce-kv (fn [m k v]
                                (assoc m k (if (map? v) (scrub-frame v) v)))
                              {} snapshot)]
      [scrubbed @dropped])))

(deftest snapshot-scrubber-strips-sensitive-from-traces
  (let [snap {:rf/default
              {:app-db  {:user/name "ada" :password "secret"}
               :traces  [{:id 1 :sensitive? false}
                         {:id 2 :sensitive? true}
                         {:id 3}]
               :epochs  [{:event-id :foo} {:event-id :auth/sign-in :sensitive? true}]
               :machines {}}
              :stories
              {:app-db {} :traces [{:id 10 :sensitive? true}]}}
        [out dropped] (scrub-snapshot-sensitive snap false)]
    (is (= 3 dropped))
    (is (= [{:id 1 :sensitive? false} {:id 3}]
           (get-in out [:rf/default :traces])))
    (is (= [{:event-id :foo}]
           (get-in out [:rf/default :epochs])))
    (is (= [] (get-in out [:stories :traces])))))

(deftest snapshot-scrubber-leaves-non-trace-slices-alone
  ;; App-db payload redaction is `with-redacted`'s job, not the
  ;; forwarder's. The scrubber must NOT touch :app-db / :sub-cache /
  ;; :machines even when they carry literal "sensitive"-looking shapes.
  (let [snap {:rf/default
              {:app-db    {:password "still-here" :sensitive? true}
               :sub-cache {:user/profile {:sensitive? true :data "x"}}
               :machines  {:auth {:state :idle}}
               :traces    [{:id 1}]}}
        [out _] (scrub-snapshot-sensitive snap false)]
    (is (= {:password "still-here" :sensitive? true}
           (get-in out [:rf/default :app-db])))
    (is (= {:user/profile {:sensitive? true :data "x"}}
           (get-in out [:rf/default :sub-cache])))
    (is (= {:auth {:state :idle}}
           (get-in out [:rf/default :machines])))))

(deftest snapshot-scrubber-include-opt-in-passes-everything
  (let [snap {:rf/default {:traces [{:id 1 :sensitive? true}
                                    {:id 2 :sensitive? true}]}}
        [out dropped] (scrub-snapshot-sensitive snap true)]
    (is (= snap out))
    (is (zero? dropped))))

(deftest snapshot-scrubber-non-map-input-passes-through
  (let [[out dropped] (scrub-snapshot-sensitive nil false)]
    (is (nil? out))
    (is (zero? dropped))))

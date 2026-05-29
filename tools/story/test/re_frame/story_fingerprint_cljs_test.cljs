(ns re-frame.story-fingerprint-cljs-test
  "CLJS host-portability companion for the canonical fingerprint primitive
  (rf2-5x1wt.3). The JVM file `re-frame.story-fingerprint-test` carries the
  full adversarial corpus + projection/plan/run-hash coverage; this file
  pins the cross-host invariants that only matter on CLJS:

  - the 8-char-hex hash renders identically (left-padded) on CLJS;
  - volatile-strip equivalence and semantic sensitivity hold under the
    CLJS `hash` + `pr-str`;
  - the snapshot-identity content-hash fold is strip-free on CLJS too;
  - the rf2-5x1wt.8 per-run stamp strip (epoch-record `:frame`, trace-event
    `:id` / `:time` / volatile tags) is host-portable too."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.fingerprint :as fp]
            [re-frame.story.identity    :as ident]))

(def ^:private base
  {:status :pass
   :variant/id :story.x/v
   :runner :headless
   :elapsed-ms 3.0
   :plan-hash "abc"
   :app-db {:n 1 :rf.story/lifecycle :ready}
   :effects [{:effect :rf/db :dispatch-id "d-1"}]
   :assertions [{:assertion :rf.assert/path-equals :status :pass
                 :source "f.cljs:1" :elapsed-ms 0.1}]})

(def ^:private volatile-twin
  (-> base
      (assoc :elapsed-ms 99.0 :runner :dom :plan-hash "zzz")
      (assoc-in [:app-db :rf.story/lifecycle] :error)
      (assoc-in [:effects 0 :dispatch-id] "z-9")
      (assoc-in [:assertions 0 :source] "g.cljs:7")
      (assoc-in [:assertions 0 :elapsed-ms] 42.0)))

(deftest content-hash-is-8-char-hex-on-cljs
  (testing "content-hash renders a left-padded 8-char lowercase hex string"
    (let [h (fp/content-hash {:a 1})]
      (is (string? h))
      (is (= 8 (count h)))
      (is (re-matches #"[0-9a-f]{8}" h)))))

(deftest order-insensitive-on-cljs
  (testing "map key order does not affect the CLJS hash"
    (is (= (fp/content-hash {:a 1 :b 2}) (fp/content-hash {:b 2 :a 1}))))
  (testing "set element order does not affect the CLJS hash"
    (is (= (fp/content-hash #{:x :y :z}) (fp/content-hash #{:z :y :x})))))

(deftest volatile-equivalence-on-cljs
  (testing "volatile-only differences canonicalize = and run-hash equal"
    (is (= (fp/canonicalize base) (fp/canonicalize volatile-twin)))
    (is (= (fp/run-hash base) (fp/run-hash volatile-twin)))))

(deftest semantic-difference-on-cljs
  (testing "a semantic app-db difference perturbs the run-hash on CLJS"
    (is (not= (fp/run-hash base)
              (fp/run-hash (assoc-in base [:app-db :n] 2))))))

(deftest determinism-stamp-strip-on-cljs
  (testing "epoch records differing only in per-run stamps canonicalize = on CLJS
            (rf2-5x1wt.8): :epoch-id / :frame / :committed-at / :schema-digest"
    (let [rec (fn [eid frame]
                {:epoch-id eid :frame frame :committed-at 1 :schema-digest "d"
                 :outcome :ok :db-after {:n 1} :trace-events [] :effects []})]
      (is (= (fp/canonicalize (rec 5 :rf.test.replay/frame-a))
             (fp/canonicalize (rec 90 :rf.test.replay/frame-z))))
      (is (= (fp/canonical-hash (rec 5 :rf.test.replay/frame-a))
             (fp/canonical-hash (rec 90 :rf.test.replay/frame-z))))
      (is (not= (fp/canonicalize (rec 5 :rf.test.replay/frame-a))
                (fp/canonicalize (assoc (rec 5 :rf.test.replay/frame-a)
                                        :db-after {:n 2})))
          "a real db-after difference still perturbs the canonical value")))

  (testing "trace events differing only in :id / :time / volatile tags
            canonicalize = on CLJS; the event-id tag is semantic"
    (let [ev (fn [id frame]
               {:operation :rf.event/run-start :op-type :event :id id :time id
                :tags {:rf.trace/event-id :foo :frame frame
                       :rf.trace/dispatch-id id}})]
      (is (= (fp/canonicalize {:trace-events [(ev 1 :rf.test.replay/frame-a)]})
             (fp/canonicalize {:trace-events [(ev 9 :rf.test.replay/frame-z)]})))
      (is (not= (fp/canonicalize {:trace-events [(ev 1 :rf.test.replay/frame-a)]})
                (fp/canonicalize
                  {:trace-events [(assoc-in (ev 1 :rf.test.replay/frame-a)
                                            [:tags :rf.trace/event-id] :bar)]}))
          "the event-id tag is behavioural, not a stamp")))

  (testing ":id / :time / :frame as plain app-db values are NOT stripped on CLJS"
    (is (not= (fp/canonicalize {:status :pass :app-db {:id 1 :time 10 :frame :l}})
              (fp/canonicalize {:status :pass :app-db {:id 2 :time 20 :frame :r}}))
        "semantic app-db data on common keys survives canonicalization")))

(deftest snapshot-fold-strip-free-on-cljs
  (testing "identity content-hash IS the fingerprint content-hash (folded)"
    (is (identical? ident/content-hash fp/content-hash)))
  (testing "content-hash keeps :variant-id sensitivity; canonical-hash strips it"
    (let [tuple {:variant-id :story.x/v :variant {:a 1}}]
      (is (not= (fp/content-hash tuple)
                (fp/content-hash (assoc tuple :variant-id :story.y/v))))
      (is (= (fp/canonical-hash tuple)
             (fp/canonical-hash (assoc tuple :variant-id :story.y/v)))))))

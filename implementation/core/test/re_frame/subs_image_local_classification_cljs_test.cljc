(ns re-frame.subs-image-local-classification-cljs-test
  "rf2-vxgfnd.220 — a subscription's `:rf.sub/run` trace projects observability
  from the EXACT registration classification captured for THAT reaction, not a
  later/global re-resolution.

  The reactive recompute already captures the authoritative image-local / global
  `sub-meta` for schema validation; it now also carries that reaction's
  classification declaration through the `:rf.sub/run` trace chokepoint under an
  internal `:rf.sub/classification` slot. `project-sub-tags` redacts from the
  carried declaration instead of re-resolving `classification-when :sub sub-id`
  through the ambient registrar — which after the frame-generation binding
  unwinds (one-shot deref) or across HMR/incarnation replacement (an ongoing
  reaction) sees no image metadata or a CONFLICTING same-id global registration.

  Focused chokepoint fixtures drive `classification/project-trace-event` on
  `:rf.sub/run` events directly (pure data — the fixtures a mutation restoring
  ambient re-resolution must fail); an end-to-end leg subscribes to a real
  sensitive sub and proves the subscriber reads RAW while the projected trace
  redacts, across an ongoing recompute.

  `.cljc` — runs under both `clojure -M:test` (JVM) and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.classification :as classification]
            [re-frame.elision :as elision]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; The reset fixture gives each test a clean, isolated runtime (a pinned
;; :rf/default frame bound as the ambient scope, with registrar snapshot/restore
;; so sibling namespaces in the shared :node-test bundle survive).
(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Focused chokepoint — project-trace-event on :rf.sub/run (pure data)
;; ---------------------------------------------------------------------------

(defn- project-sub-run
  "Project a `:rf.sub/run` envelope with the given tags and return the projected
  tags. `:frame` defaults to :rf/default so the projector does not fail-closed."
  [tags]
  (-> (classification/project-trace-event
        {:operation :rf.sub/run :op-type :rf.sub
         :tags (merge {:frame :rf/default} tags)})
      :tags))

(deftest captured-sensitive-declaration-redacts-value-and-prev-value
  (testing "an image-local sub's captured :sensitive declaration redacts
            :rf.sub/value and :rf.sub/prev-value — with NO global registration"
    (let [out (project-sub-run
                {:rf.sub/id :img/secret
                 :rf.sub/value      {:token "SECRET" :public 1}
                 :rf.sub/prev-value {:token "OLD"    :public 0}
                 :rf.sub/classification {:sensitive [[:token]]}})]
      (is (= privacy/redacted-sentinel (get-in out [:rf.sub/value :token])))
      (is (= 1 (get-in out [:rf.sub/value :public])) "unclassified sibling rides raw")
      (is (= privacy/redacted-sentinel (get-in out [:rf.sub/prev-value :token])))
      ;; EP-0025: a path-classified sub redacts only its declared paths — there
      ;; is no whole-output :sensitive? stamp (no sub-output propagation). The
      ;; captured path redacts exactly as the registrar path would.
      (is (not (contains? out :rf.sub/classification))
          "the internal carrier is stripped — it never egresses"))))

(deftest captured-large-declaration-marks-value
  (testing "a captured :large declaration emits the canonical large marker"
    (let [out (project-sub-run
                {:rf.sub/id :img/big
                 :rf.sub/value {:blob (vec (range 100))}
                 :rf.sub/classification {:large [[:blob]]}})]
      (is (elision/marker? (get-in out [:rf.sub/value :blob])))
      (is (not (contains? out :rf.sub/classification))))))

(deftest conflicting-global-cannot-supply-or-override-captured-classification
  (testing "a same-id GLOBAL registration with no classification cannot make a
            captured-sensitive sub leak"
    (rf/reg-sub :dup/id (fn [db _] db))                 ;; global: no classification
    (let [out (project-sub-run {:rf.sub/id :dup/id
                                :rf.sub/value {:token "SECRET"}
                                :rf.sub/classification {:sensitive [[:token]]}})]
      (is (= privacy/redacted-sentinel (get-in out [:rf.sub/value :token]))
          "captured classification wins over the (unclassified) global")))
  (testing "a captured EMPTY declaration wins over a conflicting SENSITIVE global
            — presence is authoritative, so the value rides raw"
    (rf/reg-sub :dup/id2 {:sensitive [[:token]]} (fn [db _] db))  ;; global: sensitive
    (let [out (project-sub-run {:rf.sub/id :dup/id2
                                :rf.sub/value {:token "SECRET"}
                                :rf.sub/classification {}})]      ;; captured: none
      (is (= "SECRET" (get-in out [:rf.sub/value :token]))
          "the captured (no-classification) declaration is authoritative"))))

(deftest same-sub-id-different-captured-classifications-stay-isolated
  (testing "two reactions sharing a sub id but capturing different declarations
            (two live frames / an HMR successor) project independently"
    (let [frame-a (project-sub-run {:rf.sub/id :shared/id
                                    :rf.sub/value {:token "A"}
                                    :rf.sub/classification {:sensitive [[:token]]}})
          frame-b (project-sub-run {:rf.sub/id :shared/id
                                    :rf.sub/value {:token "B"}
                                    :rf.sub/classification {}})]
      (is (= privacy/redacted-sentinel (get-in frame-a [:rf.sub/value :token])))
      (is (= "B" (get-in frame-b [:rf.sub/value :token]))))))

(deftest absent-carrier-falls-back-to-registrar-resolution
  (testing "a :rf.sub/run trace with NO captured carrier (a non-memo path) still
            redacts via the registrar — the fallback is preserved"
    (rf/reg-sub :fallback/s {:sensitive [[:token]]} (fn [db _] db))
    (let [out (project-sub-run {:rf.sub/id :fallback/s
                                :rf.sub/value {:token "SECRET"}})]  ;; no carrier
      (is (= privacy/redacted-sentinel (get-in out [:rf.sub/value :token]))))))

;; ---------------------------------------------------------------------------
;; End-to-end — a real sensitive sub: subscriber RAW, projected trace REDACTED
;; ---------------------------------------------------------------------------

(defn- captured-sub-runs [recorded sub-id]
  (filterv (fn [ev] (and (= :rf.sub/run (:operation ev))
                         (= sub-id (get-in ev [:tags :rf.sub/id]))))
           @recorded))

(deftest subscriber-reads-raw-while-projected-sub-run-trace-redacts
  (rf/reg-event :sec/seed (fn [{:keys [db]} [_ v]] {:db (assoc db :token v)}))
  (rf/reg-sub :sec/read {:sensitive [[:token]]} (fn [db _] {:token (:token db)}))
  (let [recorded (atom [])]
    (rf/register-listener! :trace :sec-e2e (fn [ev] (swap! recorded conj ev)))
    (try
      (rf/dispatch-sync [:sec/seed "secret-1"])
      ;; ongoing reactive read #1 — a compute emits :rf.sub/run
      (is (= {:token "secret-1"} @(rf/subscribe [:sec/read]))
          "the subscriber derefs the RAW value")
      ;; ongoing reactive read #2 — value change drives a recompute
      (rf/dispatch-sync [:sec/seed "secret-2"])
      (is (= {:token "secret-2"} @(rf/subscribe [:sec/read]))
          "the recomputed subscriber value is still RAW")
      (let [runs (captured-sub-runs recorded :sec/read)]
        (is (seq runs) "at least one :rf.sub/run trace was captured")
        (doseq [ev runs]
          (is (= privacy/redacted-sentinel
                 (get-in ev [:tags :rf.sub/value :token]))
              "every projected :rf.sub/run redacts the sensitive path")
          (is (not (contains? (:tags ev) :rf.sub/classification))
              "the internal carrier never reaches a listener")))
      (finally
        (rf/unregister-listener! :trace :sec-e2e)))))

(ns re-frame.story.artifact-test
  "Tests for the `:rf.test/run-artifact` schema + `replay-run-artifact`
  (rf2-5x1wt.7, spec/017-Testing-Story.md §Run artifact and replay).

  Two layers, both under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE construction: `make-run-artifact` coerces the event program,
    folds setup ⧺ script, defaults `:fx-decisions`, and `run-artifact?`
    recognises the shape. `replay-result` builds the shared run-result
    from a hand-built tape with no live frame.
  - HEADLESS replay (against a live frame): `replay-run-artifact` replays
    the dispatch program into a FRESH frame, reapplies fx
    decisions/overrides, captures a NEW epoch tape, and returns the
    shared run-result shape (the §A3 acceptance bullets)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core   :as rf]
            [re-frame.epoch  :as epoch]
            [re-frame.frame  :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story.artifact :as artifact]
            [re-frame.story.fingerprint :as fingerprint]))

;; ===========================================================================
;; PURE: schema + construction
;; ===========================================================================

(deftest make-run-artifact-coerces-program
  (testing "a bare event list lifts to a tagged [:dispatch …] program"
    (let [a (artifact/make-run-artifact
              {:event-program [[:counter/inc] [:dispatch [:counter/dec]]]})]
      (is (= :rf.test/run-artifact (:artifact/kind a)))
      (is (= [[:dispatch [:counter/inc]] [:dispatch [:counter/dec]]]
             (:event-program a))
          "bare event vector lifts; an already-tagged step passes through")
      (is (artifact/run-artifact? a))))

  (testing "an empty / fx-less artifact defaults :fx-decisions to {}"
    (let [a (artifact/make-run-artifact {})]
      (is (= {} (:fx-decisions a)))
      (is (= [] (:event-program a)))
      (is (artifact/run-artifact? a))))

  (testing ":setup ⧺ :script fold into one ordered program (setup first)"
    (let [a (artifact/make-run-artifact
              {:setup  [[:dispatch [:seed/a]]]
               :script [[:dispatch [:act/b]] [:wait 5]]})]
      (is (= [[:dispatch [:seed/a]] [:dispatch [:act/b]] [:wait 5]]
             (:event-program a)))))

  (testing "an explicit :event-program wins over :setup/:script"
    (let [a (artifact/make-run-artifact
              {:event-program [[:dispatch [:only/this]]]
               :setup         [[:dispatch [:ignored]]]})]
      (is (= [[:dispatch [:only/this]]] (:event-program a)))))

  (testing "slots outside the artifact surface are dropped; known slots kept"
    (let [a (artifact/make-run-artifact
              {:event-program [[:dispatch [:e]]]
               :seed          42
               :fx-decisions  {:http/get :http/stub}
               :source        {:tool :recorder}
               :bogus/extra   :dropped})]
      (is (= 42 (:seed a)))
      (is (= {:http/get :http/stub} (:fx-decisions a)))
      (is (= {:tool :recorder} (:source a)))
      (is (not (contains? a :bogus/extra))))))

(deftest run-artifact-predicate
  (testing "run-artifact? requires the kind tag AND a vector :event-program"
    (is (artifact/run-artifact?
          {:artifact/kind :rf.test/run-artifact :event-program []}))
    (is (not (artifact/run-artifact? {:event-program []}))
        "missing :artifact/kind")
    (is (not (artifact/run-artifact?
               {:artifact/kind :rf.test/run-artifact}))
        "missing :event-program")
    (is (not (artifact/run-artifact? nil)))
    (is (not (artifact/run-artifact? [:not :a :map])))))

(deftest program-events-projection
  (testing "program-events projects only the dispatched event vectors"
    (let [a (artifact/make-run-artifact
              {:event-program [[:dispatch [:a 1]]
                               [:wait 10]
                               [:dispatch-sync [:b 2]]
                               [:assert-db [:k] :v]]})]
      (is (= [[:a 1] [:b 2]] (artifact/program-events a))
          ":wait / :assert-* contribute no event"))))

;; ===========================================================================
;; PURE: replay-result construction from a hand-built tape
;; ===========================================================================

(defn- epoch
  "A minimal `:rf/epoch-record`."
  [epoch-id m]
  (merge {:epoch-id epoch-id :outcome :ok
          :db-before {} :db-after {}
          :trace-events [] :effects [] :sub-runs [] :renders []}
         m))

(deftest replay-result-shared-shape
  (testing "replay-result builds the shared run-result shape from a clean tape"
    (let [a    (artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
          tape [(epoch :e1 {:db-after {:n 1}})]
          res  (artifact/replay-result
                 {:epoch-tape tape :artifact a
                  :outcomes [{:status :settled :boundary :headless}]
                  :frame-id :rf.test.replay/f :app-db {:n 1}})]
      (is (= :pass (:status res)))
      (is (= :headless (:runner res)))
      (is (= {:n 1} (:app-db res)))
      (is (= tape (:epoch-tape res)) "the captured tape is the evidence source")
      (is (= a (:run-artifact res)) "back-link to the replayed source")
      (is (vector? (:narrative res)) "two-level narrative present")
      (is (vector? (:schema-violations res)))
      (is (empty? (:schema-violations res))))))

(deftest replay-result-status-follows-tape
  (testing ":fail when the tape carries unconsumed failure evidence"
    (let [a    (artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
          tape [(epoch :e1 {:outcome :halt})]
          res  (artifact/replay-result
                 {:epoch-tape tape :artifact a
                  :outcomes [{:status :settled :boundary :headless}]
                  :frame-id :f :app-db {}})]
      (is (= :fail (:status res))
          "a non-:ok epoch outcome trips the agreement floor")))

  (testing ":cannot-run when a step refused"
    (let [a   (artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
          res (artifact/replay-result
                {:epoch-tape [] :artifact a
                 :outcomes [{:status :cannot-run :required-boundary :dom
                             :provided-boundary :headless}]
                 :frame-id :f :app-db {}})]
      (is (= :cannot-run (:status res)))
      (is (= :dom (get-in res [:cannot-run :required-boundary])))))

  (testing ":error when a step errored"
    (let [a   (artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})
          res (artifact/replay-result
                {:epoch-tape [] :artifact a
                 :outcomes [{:status :error :error "boom"}]
                 :frame-id :f :app-db {}})]
      (is (= :error (:status res)))
      (is (= "boom" (:error res))))))

;; ===========================================================================
;; HEADLESS replay: against a live frame
;; ===========================================================================

(defn- reset-rf! [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  ;; Requiring `re-frame.epoch` (above) installs the epoch artefact's
  ;; late-bind hooks, so `epoch-history` records a real tape; clear its
  ;; per-frame ring + listeners between tests so a replay reads only its
  ;; own freshly-captured epochs.
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-rf!)

(deftest replay-into-fresh-frame
  (testing "replay-run-artifact replays the dispatch program into a FRESH
            frame, captures a NEW tape, and returns the shared run-result —
            the fresh frame is torn down before return"
    (rf/reg-event-db :rep/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [a   (artifact/make-run-artifact
                {:event-program [[:dispatch [:rep/inc]] [:dispatch [:rep/inc]]]})
          res (artifact/replay-run-artifact a)]
      (is (= :pass (:status res)))
      (is (= 2 (:n (:app-db res))) "the program ran into a fresh, empty frame")
      (is (seq (:epoch-tape res)) "a NEW epoch tape was captured")
      (is (vector? (:narrative res)))
      (is (= a (:run-artifact res)))
      ;; the internally-allocated frame is gone (teardown ran)
      (let [fid (:frame res)]
        (is (not (contains? @frame/frames fid))
            "the replay-allocated frame is destroyed before return")))))

(deftest replay-reapplies-fx-decisions
  (testing "replay reapplies fx decisions/overrides — the recorded override
            fires instead of the real effect"
    (let [hits (atom [])]
      ;; The 'real' effect would record :real; the stub records :stub.
      ;; `:platforms #{:client :server}` so the fx fire on the JVM
      ;; (`:server`) test platform as well as in the browser.
      (rf/reg-fx :rep.fx/real {:platforms #{:client :server}}
                 (fn [_ _] (swap! hits conj :real)))
      (rf/reg-fx :rep.fx/stub {:platforms #{:client :server}}
                 (fn [_ _] (swap! hits conj :stub)))
      (rf/reg-event-fx :rep/fire (fn [_ _] {:fx [[:rep.fx/real {}]]}))
      (let [a   (artifact/make-run-artifact
                  {:event-program [[:dispatch [:rep/fire]]]
                   :fx-decisions  {:rep.fx/real :rep.fx/stub}})
            res (artifact/replay-run-artifact a)]
        (is (= :pass (:status res)))
        (is (= [:stub] @hits)
            "the fx decision remapped :rep.fx/real → :rep.fx/stub on replay")))))

(deftest replay-isolation-fresh-frame-each-time
  (testing "two replays of the same artifact each run into their own fresh
            frame — no shared app-db leaks between replays"
    (rf/reg-event-db :rep/set (fn [db [_ v]] (assoc db :v v)))
    (let [a    (artifact/make-run-artifact
                 {:event-program [[:dispatch [:rep/set 7]]]})
          r1   (artifact/replay-run-artifact a)
          r2   (artifact/replay-run-artifact a)]
      (is (= 7 (:v (:app-db r1))))
      (is (= 7 (:v (:app-db r2))))
      (is (not= (:frame r1) (:frame r2)) "distinct fresh frames"))))

(deftest replay-result-is-canonicalizable
  (testing "the replay result feeds cleanly through the .3 canonicalize /
            run-hash path — the result is stable + canonicalizable so the
            determinism gate (.8) + semantic diff (.9) build on it. (Cross-
            run run-hash EQUALITY is .8's concern: it owns stripping the
            per-frame epoch ids from the tape; this bead pins only that the
            result canonicalizes deterministically and run-hash is stable.)"
    (rf/reg-event-db :rep/seed (fn [db _] (assoc db :seeded true)))
    (let [a   (artifact/replay-run-artifact
                (artifact/make-run-artifact {:event-program [[:dispatch [:rep/seed]]]}))
          h   (fingerprint/run-hash a)]
      (is (string? h))
      (is (= 8 (count h)) "run-hash is the stable 8-char-hex primitive")
      (is (= h (fingerprint/run-hash a)) "run-hash is idempotent on one result")
      (is (= (fingerprint/canonicalize a) (fingerprint/canonicalize a))
          "canonicalize is deterministic on the result"))
    (testing "canonicalize strips the volatile top-level slots .3 enumerates"
      (let [res {:status :pass :app-db {:n 1}
                 :elapsed-ms 42 :runner :headless :variant/id :x :plan-hash "ab"}
            c   (fingerprint/canonicalize res)
            ;; canonical-form renders maps as flattened [k v k v …] vectors
            ks  (set (take-nth 2 c))]
        (is (not (contains? ks :elapsed-ms)) ":elapsed-ms stripped")
        (is (not (contains? ks :runner))     ":runner stripped")
        (is (not (contains? ks :variant/id)) ":variant/id stripped")
        (is (not (contains? ks :plan-hash))  ":plan-hash stripped")
        (is (contains? ks :status)           ":status retained (behavioural)")))))

(deftest replay-into-caller-supplied-frame
  (testing "a caller-supplied :frame is replayed into and LEFT intact (the
            caller owns its lifecycle)"
    (rf/reg-event-db :rep/inc (fn [db _] (update db :n (fnil inc 0))))
    (rf/reg-frame :rep/caller-frame {:doc "caller-owned replay frame"})
    (let [a   (artifact/make-run-artifact {:event-program [[:dispatch [:rep/inc]]]})
          res (artifact/replay-run-artifact a {:frame :rep/caller-frame})]
      (is (= :pass (:status res)))
      (is (= :rep/caller-frame (:frame res)))
      (is (contains? @frame/frames :rep/caller-frame)
          "the caller-supplied frame is NOT destroyed"))))

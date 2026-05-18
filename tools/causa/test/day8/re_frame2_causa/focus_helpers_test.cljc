(ns day8.re-frame2-causa.focus-helpers-test
  "Pure-data tests for the focus-navigation helpers (rf2-a1z3b).

  Coverage:

  - Dimension picker — all four default dimensions (event-id,
    machine-id, http-correlation-id, source-coord) + the picker's
    first-applicable priority + the nil-cascade fallback.
  - Predicate builder — focused predicate per dimension; nil focus-set
    is identity-true.
  - Traversal — `in-focus-ids`, `step-in-focus-id` skip out-of-focus
    rows; `at-focus-boundary?` greys nav at the edges.

  JVM-only test (`.cljc`) — the helpers are pure data; running them
  under cljs.test would only add CLJS round-trip latency for no
  additional coverage. The spine-integration tests (CLJS) exercise
  the helpers through the wired reducer surface."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.focus-helpers :as fh]))

;; ---- cascade fixtures ---------------------------------------------------

(defn- cascade
  "Build a synthetic cascade. `opts` controls which dimensions the
  cascade carries — every test can compose one with exactly the
  shape the test needs.

  Recognised keys: :id (dispatch-id), :event-id, :machine-id,
  :request-id, :source-coord-file, :source-coord-line."
  [{:keys [id event-id machine-id request-id source-coord-file source-coord-line]
    :or   {id 1}}]
  (cond-> {:dispatch-id id
           :frame       :rf/default
           :event       (when event-id [event-id {}])
           :handler     (cond-> {}
                          source-coord-file
                          (assoc :rf.trace/trigger-handler
                                 {:source-coord (cond-> {:file source-coord-file}
                                                  source-coord-line
                                                  (assoc :line source-coord-line))}))
           :fx          nil
           :effects     []
           :subs        []
           :renders     []
           :other       []}
    machine-id (update :other conj {:tags {:machine-id machine-id}
                                    :operation :rf.machine/transition})
    request-id (update :other conj {:tags {:request-id request-id}
                                    :operation :rf.http/dispatched})))

;; ---- (1) infer-dimension — all four dimensions --------------------------

(deftest infer-dimension-event-id
  (testing "Plain event row → :event-id dimension"
    (is (= {:dimension :event-id :value :cart/add-item}
           (fh/infer-dimension (cascade {:id 1 :event-id :cart/add-item}))))))

(deftest infer-dimension-machine-id
  (testing "Machine transition wins over event-id (more specific)"
    (is (= {:dimension :machine-id :value :checkout-flow/main}
           (fh/infer-dimension
             (cascade {:id         1
                       :event-id   :cart/add-item
                       :machine-id :checkout-flow/main}))))))

(deftest infer-dimension-http-correlation
  (testing "HTTP request-id wins when no machine present"
    (is (= {:dimension :http-correlation-id :value "req-42"}
           (fh/infer-dimension
             (cascade {:id         1
                       :event-id   :api/load-cart
                       :request-id "req-42"}))))))

(deftest infer-dimension-source-coord
  (testing "Source-coord is the LAST dimension — only used when nothing else applies"
    (is (= {:dimension :event-id :value :counter/inc}
           (fh/infer-dimension
             (cascade {:id                1
                       :event-id          :counter/inc
                       :source-coord-file "src/foo.cljs"
                       :source-coord-line 42})))
        "event-id still wins when both present"))

  (testing "Cascade with only source-coord (no event-id) → :source-coord wins"
    (is (= {:dimension :source-coord :value "src/foo.cljs:42"}
           (fh/infer-dimension
             (cascade {:id                1
                       :source-coord-file "src/foo.cljs"
                       :source-coord-line 42}))))))

(deftest infer-dimension-machine-beats-http
  (testing "Priority — machine > http > event-id > source-coord"
    (is (= :machine-id
           (:dimension
             (fh/infer-dimension
               (cascade {:id 1 :event-id :foo :machine-id :m :request-id "req"})))))))

(deftest infer-dimension-nil-for-empty-cascade
  (testing "Cascade with no event-id / machine / http / source-coord → nil"
    (is (nil? (fh/infer-dimension (cascade {:id :ungrouped}))))))

;; ---- (2) build-focus-predicate ------------------------------------------

(deftest build-focus-predicate-event-id
  (let [pred (fh/build-focus-predicate {:dimension :event-id :value :cart/add-item})]
    (is (true? (pred (cascade {:id 1 :event-id :cart/add-item}))))
    (is (false? (pred (cascade {:id 2 :event-id :cart/remove-item}))))))

(deftest build-focus-predicate-machine
  (let [pred (fh/build-focus-predicate {:dimension :machine-id :value :m/one})]
    (is (true? (pred (cascade {:id 1 :event-id :x :machine-id :m/one}))))
    (is (false? (pred (cascade {:id 2 :event-id :x :machine-id :m/two}))))
    (is (false? (pred (cascade {:id 3 :event-id :x}))))))

(deftest build-focus-predicate-http
  (let [pred (fh/build-focus-predicate {:dimension :http-correlation-id :value "req-7"})]
    (is (true? (pred (cascade {:id 1 :event-id :load :request-id "req-7"}))))
    (is (false? (pred (cascade {:id 2 :event-id :load :request-id "req-8"}))))))

(deftest build-focus-predicate-source-coord
  (let [pred (fh/build-focus-predicate {:dimension :source-coord :value "src/foo.cljs:42"})]
    (is (true? (pred (cascade {:id 1 :source-coord-file "src/foo.cljs" :source-coord-line 42}))))
    (is (false? (pred (cascade {:id 2 :source-coord-file "src/bar.cljs" :source-coord-line 42}))))))

(deftest build-focus-predicate-nil-focus-set-is-identity
  (let [pred (fh/build-focus-predicate nil)]
    (is (true? (pred (cascade {:id 1 :event-id :anything})))
        "nil focus-set = no focus active = every cascade is in-focus")))

;; ---- (3) traversal ------------------------------------------------------

(def ^:private cs
  [(cascade {:id :c1 :event-id :foo})
   (cascade {:id :c2 :event-id :bar})
   (cascade {:id :c3 :event-id :foo})
   (cascade {:id :c4 :event-id :baz})
   (cascade {:id :c5 :event-id :foo})])

(deftest in-focus-ids-returns-only-matching
  (is (= [:c1 :c3 :c5]
         (fh/in-focus-ids cs {:dimension :event-id :value :foo})))
  (is (= [:c2]
         (fh/in-focus-ids cs {:dimension :event-id :value :bar})))
  (is (= []
         (fh/in-focus-ids cs {:dimension :event-id :value :nope}))))

(deftest in-focus-ids-nil-focus-set-returns-every-id
  (is (= [:c1 :c2 :c3 :c4 :c5] (fh/in-focus-ids cs nil))))

(deftest step-in-focus-id-skips-out-of-focus
  (let [fset {:dimension :event-id :value :foo}]
    (testing "Forward from :c1 skips :c2 to land on :c3"
      (is (= :c3 (fh/step-in-focus-id cs fset :c1 +1))))
    (testing "Forward from :c3 skips :c4 to land on :c5"
      (is (= :c5 (fh/step-in-focus-id cs fset :c3 +1))))
    (testing "Backward from :c5 skips :c4 to land on :c3"
      (is (= :c3 (fh/step-in-focus-id cs fset :c5 -1))))
    (testing "Backward from :c3 skips :c2 to land on :c1"
      (is (= :c1 (fh/step-in-focus-id cs fset :c3 -1))))))

(deftest step-in-focus-id-bounded-at-edges
  (let [fset {:dimension :event-id :value :foo}]
    (testing "Backward from first in-focus (:c1) stays at :c1 (boundary)"
      (is (= :c1 (fh/step-in-focus-id cs fset :c1 -1))))
    (testing "Forward from last in-focus (:c5) stays at :c5 (boundary)"
      (is (= :c5 (fh/step-in-focus-id cs fset :c5 +1))))))

(deftest step-in-focus-id-from-out-of-focus
  (let [fset {:dimension :event-id :value :foo}]
    (testing "Forward from out-of-focus :c2 lands on first in-focus :c1"
      (is (= :c1 (fh/step-in-focus-id cs fset :c2 +1)))
      "out-of-focus current treated as just-before-start; +1 → first")
    (testing "Backward from out-of-focus :c4 lands on last in-focus :c5"
      (is (= :c5 (fh/step-in-focus-id cs fset :c4 -1)))
      "out-of-focus current treated as just-past-end; -1 → last")))

(deftest step-in-focus-id-empty-result
  (let [fset {:dimension :event-id :value :nope}]
    (is (nil? (fh/step-in-focus-id cs fset :c1 +1))
        "no in-focus rows → nil (caller treats as 'cannot step')")))

(deftest at-focus-boundary
  (let [fset {:dimension :event-id :value :foo}]
    (is (true? (fh/at-focus-boundary? cs fset :c1 -1))   "prev at first in-focus")
    (is (true? (fh/at-focus-boundary? cs fset :c5 +1))   "next at last in-focus")
    (is (false? (fh/at-focus-boundary? cs fset :c3 -1)))
    (is (false? (fh/at-focus-boundary? cs fset :c3 +1)))
    (is (false? (fh/at-focus-boundary? cs fset :c2 +1))
        "currently OUT-of-focus → not at boundary; step lands on edge")
    (is (true? (fh/at-focus-boundary? cs {:dimension :event-id :value :nope} :c1 +1))
        "no in-focus rows → boundary always (nothing to step to)")))

;; ---- (4) dimension-label ------------------------------------------------

(deftest dimension-label-formats
  (is (= ":cart/add-item" (fh/dimension-label {:dimension :event-id :value :cart/add-item})))
  (is (= "machine :checkout-flow/main"
         (fh/dimension-label {:dimension :machine-id :value :checkout-flow/main})))
  (is (= "HTTP req-42"
         (fh/dimension-label {:dimension :http-correlation-id :value "req-42"})))
  (is (= "site src/foo.cljs:42"
         (fh/dimension-label {:dimension :source-coord :value "src/foo.cljs:42"}))))

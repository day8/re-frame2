(ns re-frame.ssr.wire-cljs-test
  "The EDN wire-crossing predicate (`re-frame.ssr.wire/edn-carryable?`, Spec
  011 §The numeric crossing rule): a value is carryable exactly when it
  reads back EQUAL on the other host. Runs on the JVM and Node."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.wire :as rf.ssr.wire]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

;; The smallest value whose `pr-str` carries a tag the safe reader cannot
;; construct, on both hosts.
(defrecord WireProbeRecord [x])

(deftest opaque-values-are-refused-however-deep
  (is (not (rf.ssr.wire/edn-carryable? {:a [1 {:b (fn [])}]}))
      "opaqueness is detected through nesting")
  (is (not (rf.ssr.wire/edn-carryable? {(->WireProbeRecord 1) :v}))
      "a record used as a map KEY is refused too"))

(deftest only-cross-host-numbers-ride-the-wire
  (testing "admitted — the integer bound is about representability, so a large
            double rides, and so does an infinity"
    (doseq [v [rf.ssr.wire/max-safe-integer
               (- rf.ssr.wire/max-safe-integer)
               1.5
               #?(:clj 1.0E308 :cljs 1e308)
               ##Inf]]
      (is (rf.ssr.wire/edn-carryable? v) (str "must still carry " (pr-str v))))
    (is (rf.ssr.wire/edn-carryable? {:a [1 {:b #{2 3.5}}]})))

  (testing "refused"
    (is (not (rf.ssr.wire/edn-carryable? #?(:clj Double/NaN :cljs js/NaN)))
        "NaN is not `=` to itself, so it cannot read back EQUAL on any host")
    #?(:clj
       (doseq [v [9007199254740993N
                  (inc rf.ssr.wire/max-safe-integer)
                  (- (inc rf.ssr.wire/max-safe-integer))
                  (float 0.1)]]
         (is (not (rf.ssr.wire/edn-carryable? v)) (pr-str v))))))

;; ---- numbers a collection cannot carry ------------------------------------
;;
;; Every number below passes on its own; the collection holding them does
;; not cross. The JVM keeps `1` and `1.0`, and `0` and `-0.0`, as distinct
;; keys, and the browser reads both as one double. The colliding literals stay
;; in the JVM branch: on CLJS they cannot be written at all.

(def ^:private colliding-bodies
  "The bytes the JVM prints for maps and sets whose distinct numbers the
  browser reads as one value, pinned as bytes because bytes are what cross.
  The JVM half pins them against the live printer, so they cannot go stale."
  {:map-keys       "{1 :i, 1.0 :d}"
   :set-members    "#{0 -0.0}"
   :composite-keys "{[1] :a, [1.0] :b}"})

(deftest colliding-bytes-read-back-differently-on-each-host
  #?(:clj
     (do
       (is (= (:map-keys colliding-bodies) (pr-str {1 :i 1.0 :d})))
       (is (= (:set-members colliding-bodies) (pr-str #{0 -0.0})))
       (is (= (:composite-keys colliding-bodies) (pr-str {[1] :a [1.0] :b})))
       (doseq [[label body] colliding-bodies]
         (is (= 2 (count (edn/read-string body)))
             (str label " — the JVM reads its own bytes back whole, so no "
                  "same-host test can see the defect"))))
     :cljs
     (doseq [[label body] colliding-bodies]
       (is (thrown-with-msg? js/Error #"duplicate key" (reader/read-string body))
           (str label " — the browser's reader refuses the duplicate the two "
                "server numbers became")))))

#?(:clj
   (deftest a-collection-whose-numbers-collapse-is-not-carryable
     (doseq [v [{1 :i 1.0 :d} #{0 -0.0} {[1] :a [1.0] :b} {:deep [{:m #{1 1.0}}]}]]
       (is (not (rf.ssr.wire/edn-carryable? v)) (pr-str v)))
     (is (= {:path [:deep 0 :m] :collision :set-members :browser-value 1.0}
            (dissoc (rf.ssr.wire/numeric-collision {:deep [{:m #{1 1.0}}]}) :collapses))
         "the collision names the path to its set and the value both members become")
     (testing "control: numbers that stay distinct in the browser"
       (doseq [v [{1 :i 2 :d} #{0 1.5} {[1] :a [2.0] :b}]]
         (is (rf.ssr.wire/edn-carryable? v) (pr-str v))
         (is (nil? (rf.ssr.wire/numeric-collision v)) (pr-str v))))))

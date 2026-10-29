(ns re-frame.elision-queue-walk-cljs-test
  "A persistent queue is a positional container on the walk every egress
  boundary shares (`re-frame.elision/walk-tree`): a declaration that reaches
  inside one reaches each element, as it reaches each of a vector's (Spec 015
  §collection-matching), and the projection is still a queue. On the JVM a
  queue is neither `vector?` nor `seq?`; in CLJS it is `seq?`, which a seq arm
  would rebuild as a vector. Each assertion pairs the queue with the same
  elements in a vector, which project the same.

  Dual-runtime (`*_cljs_test.cljc`): the JVM and `:node-test` runs together
  hold both host arms. The walker is production behaviour; the one deftest
  reading the pending-db trace and the epoch ring is `^:requires-debug`, since
  neither exists with debug off."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [clojure.string :as str]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.epoch :as rf.epoch]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private secret "QUEUED-SECRET-q7c2")

(def ^:private toasts
  [{:email secret :text "hi"} {:email secret :text "bye"}])

(def ^:private redacted-toasts
  [{:email :rf/redacted :text "hi"} {:email :rf/redacted :text "bye"}])

(defn- queue? [v]
  #?(:clj  (instance? clojure.lang.PersistentQueue v)
     :cljs (instance? PersistentQueue v)))

(defn- queue-of [xs]
  (into #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs (.-EMPTY PersistentQueue)) xs))

(defn- shipped
  "`[queue? elements]` of a projected collection."
  [coll]
  [(queue? coll) (vec coll)])

(defn- holds-secret? [x]
  (cond
    (string? x) (str/includes? x secret)
    (map? x)    (boolean (some holds-secret? (concat (keys x) (vals x))))
    (coll? x)   (boolean (some holds-secret? x))
    :else       false))

(deftest a-sensitive-declaration-reaches-every-queued-element
  (rf/reg-classification ::toasts {:sensitive [[:toasts :email]]})
  (let [egress #(:toasts (rf.elision/elide-wire-value {:toasts %}))]
    (is (= [true redacted-toasts] (shipped (egress (queue-of toasts))))
        "each element redacts, and the queue ships as a queue")
    (is (= [false redacted-toasts] (shipped (egress toasts)))
        "control: the same elements in a vector")))

(deftest a-large-declaration-reaches-every-queued-element
  (rf/reg-classification ::blobs {:large [[:blobs :body]]})
  (let [marker-paths (fn [coll]
                       (mapv #(get-in % [:body :rf.size/large-elided :path])
                             (:blobs (rf.elision/elide-wire-value {:blobs coll}))))
        blobs        [{:body "a"} {:body "b"}]]
    (is (= [[:blobs 0 :body] [:blobs 1 :body]] (marker-paths (queue-of blobs)))
        "each element's body is marked at its indexed path")
    (is (= [[:blobs 0 :body] [:blobs 1 :body]] (marker-paths blobs))
        "control: the same elements in a vector")))

(deftest the-classification-walker-and-declared-values-reach-into-a-queue
  (let [redact #(:value (rf.classification/redact-with-paths
                          {:value %} [[:value :email]] [] {:index-free? true}))]
    (is (= [true redacted-toasts] (shipped (redact (queue-of toasts)))))
    (is (= [false redacted-toasts] (shipped (redact toasts)))
        "control: the same elements in a vector"))
  (let [found    #(rf.elision/declared-values {:toasts %} [[:toasts :email]])
        expected {[:toasts :email] {[:toasts 0 :email] secret [:toasts 1 :email] secret}}]
    (is (= expected (found (queue-of toasts)))
        "the epoch rollups read their leaves through this walk")
    (is (= expected (found toasts)) "control: the same elements in a vector")))

(deftest an-undeclared-large-string-in-a-queue-warns-at-its-index
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        in     {:queued-advisory (queue-of [big]) :vector-advisory [big]}
        traces (atom [])]
    (rf/register-listener! :trace ::advisory #(swap! traces conj %))
    (try
      (is (= in (rf.elision/elide-wire-value in {:rf.egress/threshold-bytes 16384}))
          "an undeclared value rides verbatim")
      (when rf.interop/debug-enabled?
        (is (= #{[:queued-advisory 0] [:vector-advisory 0]}
               (into #{} (comp (filter #(= :rf.warning/large-value-unschema'd (:operation %)))
                               (map #(get-in % [:tags :path])))
                     @traces))
            "the dev advisory measures a queued string at its index, as a vector's"))
      (finally
        (rf/unregister-listener! :trace ::advisory)))))

(deftest ^:requires-debug a-queued-secret-stays-redacted-in-the-pending-db-trace-and-the-epoch-record
  (rf/make-frame {:id ::frame})
  (rf/reg-classification ::toasts {:sensitive [[:toasts :email]]})
  (rf/reg-event ::toast (fn [{:keys [db]} [_ coll]] {:db (assoc db :toasts coll)}))
  (let [traces (atom [])]
    (rf/register-listener! :trace ::pending #(swap! traces conj %))
    (try
      (doseq [coll [(queue-of toasts) toasts]]
        (rf/dispatch-sync [::toast coll] {:frame ::frame}))
      (is (= [[true redacted-toasts] [false redacted-toasts]]
             (into []
                   (comp (filter #(= :rf.event/db-pending (:operation %)))
                         (map #(shipped (get-in % [:tags :rf.event/db :toasts]))))
                   @traces))
          "the pending-db trace redacts inside the queue, as inside the vector")
      (finally
        (rf/unregister-listener! :trace ::pending))))
  (let [records (filterv #(= ::toast (:event-id %)) (rf.epoch/epoch-history ::frame))]
    (is (= [true true] (mapv :rf.epoch/sensitive? records))
        "the record rollup sees the queued secret, as the vector's")
    (is (= [false false] (mapv #(holds-secret? (rf/project-egress %)) records))
        "neither record's off-box projection carries the secret")))

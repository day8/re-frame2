(ns re-frame.schemas-storage-test
  "JVM tests for app-db schema registration and its reads: frame targeting on
  the read surfaces, path and argument validation, registration metadata,
  malformed-schema isolation, and frames coming and going around program
  registrations.

  Every READ takes one opts map with a REQUIRED `:frame`; a registration
  names no frame."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.storage :as rf.schemas.storage]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.schemas.validate :as rf.schemas.validate]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- frame-value
  "A frame VALUE routing to `frame-id`: the `rf/make-frame` token's marker and
  runnable id, built directly so no substrate adapter is needed."
  [frame-id]
  {rf.frame/object-marker   true
   rf.frame/runnable-id-key frame-id})

(defn- thrown-data
  "Run `f`, returning the ex-data of the ex-info it throws, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- schemas-in
  "The `{path schema}` projection of `frame`'s registrations."
  [frame]
  (update-vals (rf.schemas/app-schemas {:frame frame}) :schema))

(defn- ops
  "The traces in `traces` whose :operation is `op`."
  [traces op]
  (filter #(= op (:operation %)) traces))

;; ---- reads ----------------------------------------------------------------

(deftest app-schemas-returns-path-to-meta-map
  (rf/reg-app-schema [:user] [:map [:id :int]])
  (rf/reg-app-schema [:auth] [:map [:token :string]])
  (let [entries (rf.schemas/app-schemas {:frame :rf/default})]
    (is (= {[:user] [:map [:id :int]] [:auth] [:map [:token :string]]}
           (update-vals entries :schema)))
    (is (= entries (rf.schemas.storage/frame-schema-entries :rf/default))
        "the public read and the private late-bind seam are the same fact")))

(deftest an-unregistered-path-reads-as-nil
  (rf/reg-app-schema [:user] [:map])
  (is (nil? (rf.schemas/app-schema-meta {:frame :rf/default :path [:other]})))
  (is (= [:map] (:schema (rf.schemas/app-schema-meta {:frame :tenant/never :path [:user]})))
      "an id naming no live frame resolves each path's latest registration"))

(deftest reads-require-an-explicit-frame-map
  (testing "each read raises :rf.error/no-frame-context unless handed
            {:frame f}: no keyword sugar and no ambient fallback"
    (doseq [f [#(rf.schemas/app-schemas :rf/default)
               #(rf.schemas/app-schema-meta {:path [:user]})
               #(rf.schemas/app-schemas-digest :rf/default)]]
      (is (= :rf.error/no-frame-context (:rf.error/id (thrown-data f)))))))

(deftest a-frame-value-reads-what-its-id-reads
  (testing "a frame value names the frame its id names on every read"
    (let [fv (frame-value :rf/default)]
      (rf/reg-app-schema [:a] :int)
      (rf/reg-app-schemas {[:b] :int})
      (is (= {[:a] :int [:b] :int} (schemas-in fv)))
      (is (= (rf.schemas/app-schemas {:frame :rf/default})
             (rf.schemas/app-schemas {:frame fv})))
      (is (= :int (:schema (rf.schemas/app-schema-meta {:frame fv :path [:a]}))))
      (is (= (rf.schemas/app-schemas-digest {:frame :rf/default})
             (rf.schemas/app-schemas-digest {:frame fv}))))))

;; ---- registration ---------------------------------------------------------

(deftest registration-rejects-malformed-arguments-before-mutation
  (testing "each malformed argument throws its catalogued id with the
            offending value under :received, and nothing lands"
    (doseq [[f id received]
            [[#(rf/reg-app-schema :n :int)
              :rf.error/app-schema-bad-path :n]
             [#(rf/reg-app-schema [:a [:nested]] :int)
              :rf.error/app-schema-bad-path [:a [:nested]]]
             [#(rf/reg-app-schema [:user] [:map [:id :int]] [:map])
              :rf.error/app-schema-bad-metadata [:map [:id :int]]]
             [#(rf/reg-app-schema [:user] {:frame "stringframe"} [:map])
              :rf.error/app-schema-frame-target "stringframe"]
             [#(rf/reg-app-schemas {[:user] [:map]} "tenant-c")
              :rf.error/app-schemas-bad-arg "tenant-c"]]]
      (is (= [id received] ((juxt :rf.error/id :received) (thrown-data f)))))
    (is (= {} (rf/registrations {:source :store :kind :app-schema})))))

(deftest reg-app-schema-rejects-runtime-path-with-distinct-error
  (testing "a path whose head reaches runtime-db — any :rf.runtime/* key or
            the :rf.db/runtime root — throws
            :rf.error/app-schema-runtime-path naming the path"
    (doseq [path [[:rf.runtime/machines :snapshots]
                  [:rf.db/runtime :rf.runtime/machines]]]
      (is (= [:rf.error/app-schema-runtime-path path]
             ((juxt :rf.error/id :received)
              (thrown-data #(rf/reg-app-schema path [:map]))))))))

(deftest reg-app-schema-accepts-a-path-rooted-at-an-rf-runtime-key
  (testing "a :rf/runtime key is ordinary app data, so a path rooted there registers"
    (rf/reg-app-schema [:rf/runtime :legacy] :int)
    (is (= {[:rf/runtime :legacy] :int} (schemas-in :rf/default)))))

(deftest reg-app-schemas-rejects-a-bad-batch-atomically
  (testing "one bad key rejects the whole batch: the well-formed entries
            iterated before it do not land"
    (is (= :rf.error/app-schema-bad-path
           (:rf.error/id (thrown-data #(rf/reg-app-schemas {[:good]          :int
                                                            [:also :good]    :string
                                                            [:bad [:nested]] :int})))))
    (is (= {} (rf/registrations {:source :store :kind :app-schema})))))

(deftest reg-app-schemas-accepts-all-valid-paths
  (testing "the root [] and non-keyword concrete segments register"
    (rf/reg-app-schemas {[]                   [:map]
                         [:cart :items 42 :qty] :int
                         [:by-name "alice"]    :string})
    (is (= {[] [:map] [:cart :items 42 :qty] :int [:by-name "alice"] :string}
           (schemas-in :rf/default)))))

(deftest reg-app-schema-normalizes-seq-path-to-canonical-vector
  (testing "a list path is returned, stored and stamped as its canonical
            vector; a list equals its vector, so `vector?` is the check"
    (let [returned          (rf/reg-app-schema (list :a :b) :string)
          [stored-key meta] (first (rf.schemas/app-schemas {:frame :rf/default}))]
      (is (every? vector? [returned stored-key (:path meta)])))))

(deftest app-schema-meta-preserves-standard-and-open-registration-metadata
  (testing "every registration-metadata key, open :my/* keys included, rides
            alongside the stamped :schema / :path"
    (let [expected {:schema    [:map]
                    :path      [:user]
                    :doc       "User schema"
                    :tags      #{:auth}
                    :platforms #{:client}
                    :my/tool   true}]
      (rf/reg-app-schema [:user] (dissoc expected :schema :path) [:map])
      (is (= expected
             (select-keys (rf.schemas/app-schema-meta {:frame :rf/default :path [:user]})
                          (keys expected)))))))

;; ---- malformed schemas ----------------------------------------------------

(deftest malformed-schema-does-not-poison-sibling-validation
  (testing "a malformed registered schema fails closed with its own
            :rf.error/malformed-schema trace instead of throwing, and the
            schemas after it in the frame are still validated"
    (rf/reg-app-schema [:broken] [:vector])
    (rf/reg-app-schema [:good] [:int])
    (with-trace-recorder! [traces]
      (is (false? (rf.schemas.validate/validate-app-schema!
                    {:good 1 :broken [1]} :bad/ev :rf/default))
          "the malformed schema alone rejects the candidate")
      (is (= [{:where :app-db :path [:broken] :schema [:vector]}]
             (map #(select-keys (:tags %) [:where :path :schema])
                  (ops @traces :rf.error/malformed-schema)))))
    (with-trace-recorder! [traces]
      (rf.schemas.validate/validate-app-schema!
        {:good "not-an-int" :broken [1]} :bad/ev :rf/default)
      (is (= [[:good]] (map #(-> % :tags :path)
                            (ops @traces :rf.error/schema-validation-failure)))))))

;; ---- frames come and go; registrations stay -------------------------------

(deftest destroying-a-frame-leaves-the-registrations-in-place
  (rf/reg-app-schema [:user] [:map])
  (rf/make-frame {:id :tenant/doomed})
  (rf/destroy-frame! :tenant/doomed)
  (rf/make-frame {:id :tenant/doomed})
  (is (= {[:user] [:map]} (schemas-in :tenant/doomed))
      "a schema is a program registration, so a frame made again resolves it"))

(defn- survivors
  "How many references still hold their object once the collector has run. A
  `System/gc` is only a request, so it takes a few rounds."
  [refs]
  (loop [round 0]
    (System/gc)
    (let [n (count (remove #(nil? (.get ^java.lang.ref.WeakReference %)) refs))]
      (if (and (pos? n) (< round 20))
        (do (Thread/sleep 25) (recur (inc round)))
        n))))

(deftest a-replaced-schema-is-not-kept
  ;; Each cycle re-registers a fresh closure-bearing schema, as a hot reload
  ;; does, then serves a per-request frame against it: digests it, fails a
  ;; dispatch against it (which walks it for sensitive slots) and destroys the
  ;; frame. Nothing keeps a schema once a re-registration has replaced it.
  (rf/reg-event :u/bad (fn [{:keys [db]} _] {:db (assoc db :user {:n -1})}))
  (let [cycle! (fn [i]
                 (let [id     (keyword "req" (str i))
                       schema [:map [:n [:fn (fn [x] (pos? x))]]]]
                   (rf/reg-app-schema [:user] schema)
                   (rf/make-frame {:id id})
                   (rf.schemas/app-schemas-digest {:frame id})
                   (rf/dispatch-sync [:u/bad] {:frame id})
                   (rf/destroy-frame! id)
                   (java.lang.ref.WeakReference. schema)))
        refs   (mapv cycle! (range 20))]
    (is (= 0 (survivors (pop refs))) "every replaced schema is collected")))

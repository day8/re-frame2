(ns re-frame.resources-infinite-registration-cljs-test
  "Registration validation for :infinite resources (Spec 016 §Infinite
  resources and load-more feeds): :infinite true selects the slice and makes
  :next-page-param required, the optional infinite-only keys are
  shape-checked, and the retired :page-data-schema key is refused wherever it
  appears. Runtime page transitions are `resources_infinite_state_cljs_test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.registry :as rf.resources.registry]))

(defn- base-infinite-spec
  "A minimal valid :infinite metadata map."
  []
  {:doc             "test infinite feed"
   :scope           :rf.scope/global
   :params-schema   [:map [:filter :keyword]]
   :infinite        true
   :next-page-param (fn [last-page _all-pages]
                      (get-in last-page [:page-info :next-cursor]))})

(def ^:private base-infinite-request
  (fn [_feed-params {:rf.resource/keys [page-param]}]
    {:request {:method :get :url "/api/feed"
               :params (cond-> {} page-param (assoc :cursor page-param))}}))

;; a fixture fn, not the {:before :after} map: clojure.test invokes a map as a
;; fn, which is a key lookup that silently runs no test on the JVM lane
(use-fixtures :each
  (fn [test-fn]
    (rf.registrar/clear-kind! :resource)
    (try
      (test-fn)
      (finally
        (rf.registrar/clear-kind! :resource)))))

(deftest infinite-without-next-page-param-rejected
  (doseq [spec [(dissoc (base-infinite-spec) :next-page-param)
                (assoc (base-infinite-spec) :next-page-param :not-a-fn)]]
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"infinite-missing-next-page-param"
          (rf.resources.registry/reg-resource :feed/no-next spec base-infinite-request))
        (pr-str (:next-page-param spec)))))

(deftest infinite-slice-rejects-a-malformed-optional
  ;; each row puts one malformed value onto an otherwise valid spec
  (doseq [[label id override]
          [[":infinite false: only the literal true selects the slice"
            :feed/false-flag {:infinite false}]
           ["a non-fn :prev-page-param"
            :feed/bad-prev {:prev-page-param :not-a-fn}]
           ["a :page->items that is neither keyword nor fn"
            :feed/bad-acc {:page->items 99}]
           ["a non-map :refetch"
            :feed/rf-nonmap {:refetch true}]
           ["a non-integer :refetch-window"
            :feed/rf-badwin {:refetch {:refetch-window 1.5}}]]]
    (testing label
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
            (rf.resources.registry/reg-resource id (merge (base-infinite-spec) override)
                                                base-infinite-request))))))

;; :page-data-schema drove neither validation nor egress, so storing it would
;; be a privacy trap; the refusal names its replacements.

(defn- capture-ex [thunk]
  (try (thunk) nil
       (catch #?(:clj Throwable :cljs :default) e e)))

(deftest retired-page-data-schema-key-is-hard-rejected
  (let [ex (capture-ex
             #(rf.resources.registry/reg-resource :feed/retired2
                                                  (assoc (base-infinite-spec)
                                                         :page-data-schema :app/timeline-page)
                                                  base-infinite-request))]
    (is (= {:rf.error/id :rf.error/resource-bad-spec :key :page-data-schema :resource-id :feed/retired2}
           (select-keys (ex-data ex) [:rf.error/id :key :resource-id])))
    (is (nil? (rf.resources.registry/resource-meta :feed/retired2)) "refused before storage"))
  (testing "on an ordinary resource too"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :res/retired-plain
                                              {:scope :rf.scope/global
                                               :params-schema [:map [:slug :string]]
                                               :page-data-schema :app/whatever}
                                              (fn [_ _] {:request {:method :get :url "/x"}}))))
    (is (nil? (rf.resources.registry/resource-meta :res/retired-plain)))))

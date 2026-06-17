(ns re-frame.resources-infinite-registration-cljs-test
  "Registry validation for the `:infinite` resource registration kind
  (EP-0021 wave 2, Spec 016 §Infinite resources and load-more feeds, the
  `:rf/infinite-resource-args` slice).

  These tests lock the AUTHORING-boundary gate: `:infinite true` selects the
  infinite slice and makes `:next-page-param` REQUIRED (the R8 gate,
  `:rf.error/infinite-missing-next-page-param`); the optional infinite-only
  keys (`:prev-page-param` / `:page->items` / `:refetch`) are shape-validated;
  a non-infinite resource is untouched; a malformed `:infinite` value is
  rejected loud. The page-cursor is NEVER a registration key (R8) — it rides
  the runtime-threaded reserved `:request` ctx, not the spec.

  RUNTIME page state-transitions live in the sibling
  `resources_infinite_state_cljs_test`; the load-more EVENT (wave 3) and the
  merged-list SUBS (wave 4) are out of scope here."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.registrar :as registrar]
            [re-frame.resources.registry :as registry]))

(defn- base-infinite-spec
  "A minimal VALID `:infinite` resource spec — the three ordinary REQUIRED
  keys plus the `:infinite` flag + the REQUIRED `:next-page-param` (R8)."
  []
  {:doc             "test infinite feed"
   :scope           :rf.scope/global
   :params-schema   [:map [:filter :keyword]]
   :infinite        true
   :request         (fn [_feed-params {:rf.resource/keys [page-param]}]
                      {:request {:method :get :url "/api/feed"
                                 :params (cond-> {} page-param (assoc :cursor page-param))}})
   :next-page-param (fn [last-page _all-pages]
                      (get-in last-page [:page-info :next-cursor]))})

(use-fixtures :each
  {:before (fn [] (registrar/clear-kind! :resource))
   :after  (fn [] (registrar/clear-kind! :resource))})

(deftest valid-infinite-spec-registers
  (testing "a well-formed :infinite spec registers + reads back"
    (is (= :feed/timeline (registry/reg-resource :feed/timeline (base-infinite-spec))))
    (let [meta (registry/resource-meta :feed/timeline)]
      (is (true? (:infinite meta)))
      (is (fn? (:next-page-param meta))))))

(deftest valid-infinite-spec-with-all-optionals-registers
  (testing "the full optional infinite slice (R3/R6/R7) registers"
    (is (= :feed/full
           (registry/reg-resource
             :feed/full
             (assoc (base-infinite-spec)
                    :prev-page-param   (fn [first-page _all] (get-in first-page [:page-info :prev-cursor]))
                    :page->items       :items
                    :initial-page-param "p0"
                    :page-data-schema  :app/timeline-page
                    :refetch           {:refetch-all-pages? false :refetch-window 3}))))))

(deftest infinite-without-next-page-param-rejected
  (testing ":infinite true with NO :next-page-param => infinite-missing-next-page-param (R8 gate)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"infinite-missing-next-page-param"
          (registry/reg-resource :feed/no-next
                                 (dissoc (base-infinite-spec) :next-page-param)))))
  (testing ":next-page-param present but NOT a fn => same gate"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"infinite-missing-next-page-param"
          (registry/reg-resource :feed/bad-next
                                 (assoc (base-infinite-spec) :next-page-param :not-a-fn))))))

(deftest non-infinite-resource-untouched
  (testing "an ordinary (non-:infinite) resource needs no :next-page-param"
    (is (= :res/plain
           (registry/reg-resource
             :res/plain
             {:scope :rf.scope/global
              :params-schema [:map [:slug :string]]
              :request (fn [_ _] {:request {:method :get :url "/x"}})}))))
  (testing "a non-infinite resource may carry a :next-page-param key harmlessly (ignored — not gated)"
    ;; The :infinite slice is GATED on :infinite true; a stray :next-page-param
    ;; on a non-infinite resource is not validated as the infinite slice.
    (is (= :res/plain2
           (registry/reg-resource
             :res/plain2
             {:scope :rf.scope/global
              :params-schema [:map]
              :request (fn [_ _] {:request {:method :get :url "/y"}})
              :next-page-param 42})))))

(deftest infinite-flag-must-be-literal-true
  (testing ":infinite false is a meaningless typo => invalid-resource-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-spec"
          (registry/reg-resource :feed/false-flag
                                 (assoc (base-infinite-spec) :infinite false)))))
  (testing ":infinite \"true\" (string) is not the literal selector => invalid-resource-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-spec"
          (registry/reg-resource :feed/string-flag
                                 (assoc (base-infinite-spec) :infinite "true"))))))

(deftest prev-page-param-shape-validated
  (testing "a non-fn :prev-page-param => invalid-resource-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-spec"
          (registry/reg-resource :feed/bad-prev
                                 (assoc (base-infinite-spec) :prev-page-param :not-a-fn)))))
  (testing "a fn :prev-page-param is accepted"
    (is (= :feed/good-prev
           (registry/reg-resource :feed/good-prev
                                  (assoc (base-infinite-spec)
                                         :prev-page-param (fn [_first _all] nil)))))))

(deftest page-accessor-shape-validated
  (testing "a keyword :page->items is accepted"
    (is (= :feed/kw-acc
           (registry/reg-resource :feed/kw-acc
                                  (assoc (base-infinite-spec) :page->items :items)))))
  (testing "a fn :page->items is accepted"
    (is (= :feed/fn-acc
           (registry/reg-resource :feed/fn-acc
                                  (assoc (base-infinite-spec) :page->items (fn [p] (:items p)))))))
  (testing "a :page->items that is neither keyword nor fn => invalid-resource-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-spec"
          (registry/reg-resource :feed/bad-acc
                                 (assoc (base-infinite-spec) :page->items 99))))))

(deftest refetch-policy-shape-validated
  (testing "a well-formed :refetch policy registers"
    (is (= :feed/rf-ok
           (registry/reg-resource :feed/rf-ok
                                  (assoc (base-infinite-spec)
                                         :refetch {:refetch-all-pages? true :refetch-window 5}))))
    (is (= :feed/rf-empty
           (registry/reg-resource :feed/rf-empty
                                  (assoc (base-infinite-spec) :refetch {})))))
  (testing "a non-map :refetch => invalid-resource-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-spec"
          (registry/reg-resource :feed/rf-nonmap
                                 (assoc (base-infinite-spec) :refetch true)))))
  (testing "a non-boolean :refetch-all-pages? => invalid-resource-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-spec"
          (registry/reg-resource :feed/rf-badbool
                                 (assoc (base-infinite-spec) :refetch {:refetch-all-pages? :yes})))))
  (testing "a non-integer :refetch-window => invalid-resource-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"invalid-resource-spec"
          (registry/reg-resource :feed/rf-badwin
                                 (assoc (base-infinite-spec) :refetch {:refetch-window 1.5}))))))

(deftest infinite-resource-predicate
  (testing "infinite-resource? recognises the :infinite true marker"
    (is (true? (registry/infinite-resource? (base-infinite-spec))))
    (is (false? (registry/infinite-resource? {:infinite false})))
    (is (false? (registry/infinite-resource? {})))))

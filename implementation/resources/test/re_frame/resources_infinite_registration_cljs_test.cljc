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
  "A minimal VALID `:infinite` resource METADATA map — the ordinary REQUIRED
  keys plus the `:infinite` flag + the REQUIRED `:next-page-param` (R8). The
  `:request` handler is the THIRD reg-resource slot (rf2-wvh95f F1); see
  `base-infinite-request`."
  []
  {:doc             "test infinite feed"
   :scope           :rf.scope/global
   :params-schema   [:map [:filter :keyword]]
   :infinite        true
   :next-page-param (fn [last-page _all-pages]
                      (get-in last-page [:page-info :next-cursor]))})

(def ^:private base-infinite-request
  "The request handler for `base-infinite-spec` — the THIRD reg-resource slot."
  (fn [_feed-params {:rf.resource/keys [page-param]}]
    {:request {:method :get :url "/api/feed"
               :params (cond-> {} page-param (assoc :cursor page-param))}}))

(use-fixtures :each
  {:before (fn [] (registrar/clear-kind! :resource))
   :after  (fn [] (registrar/clear-kind! :resource))})

(deftest valid-infinite-spec-registers
  (testing "a well-formed :infinite spec registers + reads back"
    (is (= :feed/timeline (registry/reg-resource :feed/timeline (base-infinite-spec) base-infinite-request)))
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
                    :refetch           {:refetch-all-pages? false :refetch-window 3}) base-infinite-request)))))

(deftest infinite-without-next-page-param-rejected
  (testing ":infinite true with NO :next-page-param => infinite-missing-next-page-param (R8 gate)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"infinite-missing-next-page-param"
          (registry/reg-resource :feed/no-next
                                 (dissoc (base-infinite-spec) :next-page-param) base-infinite-request))))
  (testing ":next-page-param present but NOT a fn => same gate"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"infinite-missing-next-page-param"
          (registry/reg-resource :feed/bad-next
                                 (assoc (base-infinite-spec) :next-page-param :not-a-fn) base-infinite-request)))))

(deftest non-infinite-resource-untouched
  (testing "an ordinary (non-:infinite) resource needs no :next-page-param"
    (is (= :res/plain
           (registry/reg-resource
             :res/plain
             {:scope :rf.scope/global
              :params-schema [:map [:slug :string]]}
             (fn [_ _] {:request {:method :get :url "/x"}})))))
  (testing "a non-infinite resource may carry a :next-page-param key harmlessly (ignored — not gated)"
    ;; The :infinite slice is GATED on :infinite true; a stray :next-page-param
    ;; on a non-infinite resource is not validated as the infinite slice.
    (is (= :res/plain2
           (registry/reg-resource
             :res/plain2
             {:scope :rf.scope/global
              :params-schema [:map]
              :next-page-param 42}
             (fn [_ _] {:request {:method :get :url "/y"}}))))))

(deftest infinite-flag-must-be-literal-true
  (testing ":infinite false is a meaningless typo => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (registry/reg-resource :feed/false-flag
                                 (assoc (base-infinite-spec) :infinite false) base-infinite-request))))
  (testing ":infinite \"true\" (string) is not the literal selector => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (registry/reg-resource :feed/string-flag
                                 (assoc (base-infinite-spec) :infinite "true") base-infinite-request)))))

(deftest prev-page-param-shape-validated
  (testing "a non-fn :prev-page-param => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (registry/reg-resource :feed/bad-prev
                                 (assoc (base-infinite-spec) :prev-page-param :not-a-fn) base-infinite-request))))
  (testing "a fn :prev-page-param is accepted"
    (is (= :feed/good-prev
           (registry/reg-resource :feed/good-prev
                                  (assoc (base-infinite-spec)
                                         :prev-page-param (fn [_first _all] nil)) base-infinite-request)))))

(deftest page-accessor-shape-validated
  (testing "a keyword :page->items is accepted"
    (is (= :feed/kw-acc
           (registry/reg-resource :feed/kw-acc
                                  (assoc (base-infinite-spec) :page->items :items) base-infinite-request))))
  (testing "a fn :page->items is accepted"
    (is (= :feed/fn-acc
           (registry/reg-resource :feed/fn-acc
                                  (assoc (base-infinite-spec) :page->items (fn [p] (:items p))) base-infinite-request))))
  (testing "a :page->items that is neither keyword nor fn => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (registry/reg-resource :feed/bad-acc
                                 (assoc (base-infinite-spec) :page->items 99) base-infinite-request)))))

(deftest refetch-policy-shape-validated
  (testing "a well-formed :refetch policy registers"
    (is (= :feed/rf-ok
           (registry/reg-resource :feed/rf-ok
                                  (assoc (base-infinite-spec)
                                         :refetch {:refetch-all-pages? true :refetch-window 5}) base-infinite-request)))
    (is (= :feed/rf-empty
           (registry/reg-resource :feed/rf-empty
                                  (assoc (base-infinite-spec) :refetch {}) base-infinite-request))))
  (testing "a non-map :refetch => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (registry/reg-resource :feed/rf-nonmap
                                 (assoc (base-infinite-spec) :refetch true) base-infinite-request))))
  (testing "a non-boolean :refetch-all-pages? => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (registry/reg-resource :feed/rf-badbool
                                 (assoc (base-infinite-spec) :refetch {:refetch-all-pages? :yes}) base-infinite-request))))
  (testing "a non-integer :refetch-window => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (registry/reg-resource :feed/rf-badwin
                                 (assoc (base-infinite-spec) :refetch {:refetch-window 1.5}) base-infinite-request)))))

(deftest infinite-resource-predicate
  (testing "infinite-resource? recognises the :infinite true marker"
    (is (true? (registry/infinite-resource? (base-infinite-spec))))
    (is (false? (registry/infinite-resource? {:infinite false})))
    (is (false? (registry/infinite-resource? {})))))

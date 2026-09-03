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
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.registry :as rf.resources.registry]))

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
  {:before (fn [] (rf.registrar/clear-kind! :resource))
   :after  (fn [] (rf.registrar/clear-kind! :resource))})

(deftest valid-infinite-spec-registers
  (testing "a well-formed :infinite spec registers + reads back"
    (is (= :feed/timeline (rf.resources.registry/reg-resource :feed/timeline (base-infinite-spec) base-infinite-request)))
    (let [meta (rf.resources.registry/resource-meta :feed/timeline)]
      (is (true? (:infinite meta)))
      (is (fn? (:next-page-param meta))))))

(deftest valid-infinite-spec-with-all-optionals-registers
  (testing "the full optional infinite slice (R3/R6/R7) registers"
    (is (= :feed/full
           (rf.resources.registry/reg-resource
             :feed/full
             (assoc (base-infinite-spec)
                    :prev-page-param   (fn [first-page _all] (get-in first-page [:page-info :prev-cursor]))
                    :page->items       :items
                    :initial-page-param "p0"
                    :refetch           {:refetch-all-pages? false :refetch-window 3}) base-infinite-request)))))

(deftest infinite-without-next-page-param-rejected
  (testing ":infinite true with NO :next-page-param => infinite-missing-next-page-param (R8 gate)"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"infinite-missing-next-page-param"
          (rf.resources.registry/reg-resource :feed/no-next
                                 (dissoc (base-infinite-spec) :next-page-param) base-infinite-request))))
  (testing ":next-page-param present but NOT a fn => same gate"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"infinite-missing-next-page-param"
          (rf.resources.registry/reg-resource :feed/bad-next
                                 (assoc (base-infinite-spec) :next-page-param :not-a-fn) base-infinite-request)))))

(deftest non-infinite-resource-untouched
  (testing "an ordinary (non-:infinite) resource needs no :next-page-param"
    (is (= :res/plain
           (rf.resources.registry/reg-resource
             :res/plain
             {:scope :rf.scope/global
              :params-schema [:map [:slug :string]]}
             (fn [_ _] {:request {:method :get :url "/x"}})))))
  (testing "a non-infinite resource may carry a :next-page-param key harmlessly (ignored — not gated)"
    ;; The :infinite slice is GATED on :infinite true; a stray :next-page-param
    ;; on a non-infinite resource is not validated as the infinite slice.
    (is (= :res/plain2
           (rf.resources.registry/reg-resource
             :res/plain2
             {:scope :rf.scope/global
              :params-schema [:map]
              :next-page-param 42}
             (fn [_ _] {:request {:method :get :url "/y"}}))))))

(deftest infinite-flag-must-be-literal-true
  (testing ":infinite false is a meaningless typo => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :feed/false-flag
                                 (assoc (base-infinite-spec) :infinite false) base-infinite-request))))
  (testing ":infinite \"true\" (string) is not the literal selector => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :feed/string-flag
                                 (assoc (base-infinite-spec) :infinite "true") base-infinite-request)))))

(deftest prev-page-param-shape-validated
  (testing "a non-fn :prev-page-param => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :feed/bad-prev
                                 (assoc (base-infinite-spec) :prev-page-param :not-a-fn) base-infinite-request))))
  (testing "a fn :prev-page-param is accepted"
    (is (= :feed/good-prev
           (rf.resources.registry/reg-resource :feed/good-prev
                                  (assoc (base-infinite-spec)
                                         :prev-page-param (fn [_first _all] nil)) base-infinite-request)))))

(deftest page-accessor-shape-validated
  (testing "a keyword :page->items is accepted"
    (is (= :feed/kw-acc
           (rf.resources.registry/reg-resource :feed/kw-acc
                                  (assoc (base-infinite-spec) :page->items :items) base-infinite-request))))
  (testing "a fn :page->items is accepted"
    (is (= :feed/fn-acc
           (rf.resources.registry/reg-resource :feed/fn-acc
                                  (assoc (base-infinite-spec) :page->items (fn [p] (:items p))) base-infinite-request))))
  (testing "a :page->items that is neither keyword nor fn => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :feed/bad-acc
                                 (assoc (base-infinite-spec) :page->items 99) base-infinite-request)))))

(deftest refetch-policy-shape-validated
  (testing "a well-formed :refetch policy registers"
    (is (= :feed/rf-ok
           (rf.resources.registry/reg-resource :feed/rf-ok
                                  (assoc (base-infinite-spec)
                                         :refetch {:refetch-all-pages? true :refetch-window 5}) base-infinite-request)))
    (is (= :feed/rf-empty
           (rf.resources.registry/reg-resource :feed/rf-empty
                                  (assoc (base-infinite-spec) :refetch {}) base-infinite-request))))
  (testing "a non-map :refetch => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :feed/rf-nonmap
                                 (assoc (base-infinite-spec) :refetch true) base-infinite-request))))
  (testing "a non-boolean :refetch-all-pages? => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :feed/rf-badbool
                                 (assoc (base-infinite-spec) :refetch {:refetch-all-pages? :yes}) base-infinite-request))))
  (testing "a non-integer :refetch-window => resource-bad-spec"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :feed/rf-badwin
                                 (assoc (base-infinite-spec) :refetch {:refetch-window 1.5}) base-infinite-request)))))

(deftest infinite-resource-predicate
  (testing "infinite-resource? recognises the :infinite true marker"
    (is (true? (rf.resources.registry/infinite-resource? (base-infinite-spec))))
    (is (false? (rf.resources.registry/infinite-resource? {:infinite false})))
    (is (false? (rf.resources.registry/infinite-resource? {})))))

;; ===========================================================================
;; rf2-x76af2.12 — `:page-data-schema` is a RETIRED key: HARD-REJECTED, never
;; silently stored. It once claimed to be the per-page egress/classification
;; contract but drove neither validation nor egress (a privacy trap). The reject
;; NAMES BOTH replacements: per-page VALIDATION → the request's `:decode`;
;; durable per-page egress CLASSIFICATION → projection-relative
;; `:sensitive` / `:large`. (EP-0021 R5 superseded by EP-0025.)
;; ===========================================================================

(defn- capture-ex
  "Run `thunk`, return the thrown ex-info (or nil if it did not throw)."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Throwable :cljs :default) e e)))

(deftest retired-page-data-schema-key-is-hard-rejected
  (testing "an infinite spec that still carries :page-data-schema is REJECTED
            (resource-bad-spec) — the retired key is never silently stored"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :feed/retired
                                 (assoc (base-infinite-spec)
                                        :page-data-schema :app/timeline-page)
                                 base-infinite-request))))
  (testing "the reject fails BEFORE storage — no registrar entry is written"
    (is (nil? (rf.resources.registry/resource-meta :feed/retired))
        "a rejected registration leaves nothing behind (pre-storage gate)"))
  (testing "the error carries the retired key + names BOTH replacements"
    (let [ex   (capture-ex
                 #(rf.resources.registry/reg-resource :feed/retired2
                                         (assoc (base-infinite-spec)
                                                :page-data-schema :app/timeline-page)
                                         base-infinite-request))
          data (ex-data ex)
          msg  (ex-message ex)]
      (is (= :rf.error/resource-bad-spec (:rf.error/id data))
          "the reject rides the existing :rf.error/resource-bad-spec family")
      (is (= :page-data-schema (:key data))
          "ex-data names the retired key (actionable)")
      (is (= :feed/retired2 (:resource-id data)) "ex-data names the resource")
      (is (re-find #":decode" msg)
          "the reason names the VALIDATION replacement (request :decode)")
      (is (re-find #":sensitive" msg)
          "the reason names the CLASSIFICATION replacement (:sensitive/:large)")))
  (testing "the retired key is rejected on an ORDINARY (non-infinite) resource
            too — it is rejected WHEREVER it appears"
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
          (rf.resources.registry/reg-resource :res/retired-plain
                                 {:scope :rf.scope/global
                                  :params-schema [:map [:slug :string]]
                                  :page-data-schema :app/whatever}
                                 (fn [_ _] {:request {:method :get :url "/x"}}))))
    (is (nil? (rf.resources.registry/resource-meta :res/retired-plain)))))

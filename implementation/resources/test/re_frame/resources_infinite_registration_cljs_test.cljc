(ns re-frame.resources-infinite-registration-cljs-test
  "Registry validation for the `:infinite` resource registration kind
  (Spec 016 §Infinite resources and load-more feeds, the
  `:rf/infinite-resource-args` slice).

  These tests lock the AUTHORING-boundary gate: `:infinite true` selects the
  infinite slice and makes `:next-page-param` REQUIRED (the R8 gate,
  `:rf.error/infinite-missing-next-page-param`); the optional infinite-only
  keys (`:prev-page-param` / `:page->items` / `:refetch`) are shape-validated;
  a non-infinite resource is untouched; a malformed `:infinite` value is
  rejected loud. The page-cursor is NEVER a registration key (R8) — it rides
  the runtime-threaded reserved `:request` ctx, not the spec.

  RUNTIME page state-transitions live in the sibling
  `resources_infinite_state_cljs_test`; the load-more EVENT and the
  merged-list SUBS are out of scope here."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.registry :as rf.resources.registry]))

(defn- base-infinite-spec
  "A minimal VALID `:infinite` resource METADATA map — the ordinary REQUIRED
  keys plus the `:infinite` flag + the REQUIRED `:next-page-param` (R8). The
  `:request` handler is the THIRD reg-resource slot; see
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

;; FN form, not the `{:before … :after …}` map form. `cljs.test`
;; accepts both shapes; `clojure.test` accepts only a function, and given a map
;; it invokes it as one — a map called with the test thunk is a KEY LOOKUP that
;; returns nil and never runs the test. The JVM lane then reports zero tests for
;; this namespace, silently and with exit 0. This file is `.cljc`, so it runs on
;; both lanes and must use the shape both accept.
(use-fixtures :each
  (fn [test-fn]
    (rf.registrar/clear-kind! :resource)
    (try
      (test-fn)
      (finally
        (rf.registrar/clear-kind! :resource)))))

(deftest valid-infinite-spec-registers
  (testing "a well-formed :infinite spec registers + reads back"
    (is (= :feed/timeline (rf.resources.registry/reg-resource :feed/timeline (base-infinite-spec) base-infinite-request)))
    (let [meta (rf.resources.registry/resource-meta :feed/timeline)]
      (is (true? (:infinite meta)))
      (is (fn? (:next-page-param meta))))))

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

(deftest infinite-slice-rejects-a-malformed-optional
  ;; Each row puts ONE malformed value onto an otherwise-valid infinite spec,
  ;; and registration fails closed with :rf.error/resource-bad-spec.
  (doseq [[label id override]
          [[":infinite false is a meaningless typo — only the literal true selects the slice"
            :feed/false-flag {:infinite false}]
           [":infinite \"true\" (a string) is not the literal selector"
            :feed/string-flag {:infinite "true"}]
           ["a non-fn :prev-page-param"
            :feed/bad-prev {:prev-page-param :not-a-fn}]
           ["a :page->items that is neither keyword nor fn"
            :feed/bad-acc {:page->items 99}]
           ["a non-map :refetch"
            :feed/rf-nonmap {:refetch true}]
           ["a non-boolean :refetch-all-pages?"
            :feed/rf-badbool {:refetch {:refetch-all-pages? :yes}}]
           ["a non-integer :refetch-window"
            :feed/rf-badwin {:refetch {:refetch-window 1.5}}]]]
    (testing (str label " => resource-bad-spec")
      (is (thrown-with-msg?
            #?(:clj Throwable :cljs js/Error) #"resource-bad-spec"
            (rf.resources.registry/reg-resource id (merge (base-infinite-spec) override)
                                                base-infinite-request))))))

(deftest infinite-slice-accepts-a-well-formed-optional
  (doseq [[label id override]
          [["a fn :prev-page-param"
            :feed/good-prev {:prev-page-param (fn [_first _all] nil)}]
           ["a keyword :page->items"
            :feed/kw-acc {:page->items :items}]
           ["a fn :page->items"
            :feed/fn-acc {:page->items (fn [p] (:items p))}]
           ["a well-formed :refetch policy"
            :feed/rf-ok {:refetch {:refetch-all-pages? true :refetch-window 5}}]
           ["an empty :refetch policy"
            :feed/rf-empty {:refetch {}}]
           ["the full optional slice together"
            :feed/full {:prev-page-param    (fn [first-page _all] (get-in first-page [:page-info :prev-cursor]))
                        :page->items        :items
                        :initial-page-param "p0"
                        :refetch            {:refetch-all-pages? false :refetch-window 3}}]]]
    (testing (str label " registers")
      (is (= id (rf.resources.registry/reg-resource id (merge (base-infinite-spec) override)
                                                    base-infinite-request))))))

;; ===========================================================================
;; `:page-data-schema` is a RETIRED key: HARD-REJECTED, never
;; silently stored. A per-page egress/classification key that drove neither
;; validation nor egress would be a privacy trap. The reject
;; NAMES BOTH replacements: per-page VALIDATION → the request's `:decode`;
;; durable per-page egress CLASSIFICATION → projection-relative
;; `:sensitive` / `:large` (EP-0025).
;; ===========================================================================

(defn- capture-ex
  "Run `thunk`, return the thrown ex-info (or nil if it did not throw)."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Throwable :cljs :default) e e)))

(deftest retired-page-data-schema-key-is-hard-rejected
  (testing "an infinite spec that still carries :page-data-schema is REJECTED;
            the error carries the retired key + names BOTH replacements"
    (let [ex   (capture-ex
                 #(rf.resources.registry/reg-resource :feed/retired2
                                         (assoc (base-infinite-spec)
                                                :page-data-schema :app/timeline-page)
                                         base-infinite-request))
          data (ex-data ex)
          msg  (ex-message ex)]
      (is (= :rf.error/resource-bad-spec (:rf.error/id data))
          "the reject rides the :rf.error/resource-bad-spec family")
      (is (= :page-data-schema (:key data))
          "ex-data names the retired key (actionable)")
      (is (= :feed/retired2 (:resource-id data)) "ex-data names the resource")
      (is (re-find #":decode" msg)
          "the reason names the VALIDATION replacement (request :decode)")
      (is (re-find #":sensitive" msg)
          "the reason names the CLASSIFICATION replacement (:sensitive/:large)")))
  (testing "the reject fails BEFORE storage — no registrar entry is written"
    (is (nil? (rf.resources.registry/resource-meta :feed/retired2))
        "a rejected registration leaves nothing behind (pre-storage gate)"))
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

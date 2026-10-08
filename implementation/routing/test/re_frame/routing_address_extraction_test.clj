(ns re-frame.routing-address-extraction-test
  "The shared RouteAddress extraction law, `re-frame.routing.address`
  (Spec 012 §The extraction law, Spec-Schemas §`:rf/route-address`): the
  closed navigate roster, the `classify` structural gate, the closed
  `valid-address?` predicate over the extracted address, and `link-model`
  resolving its address through the shared extractor."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.address :as rf.routing.address]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(deftest key-classes-are-the-closed-spec-sets
  (is (= #{:to :params :query :fragment :url :replace? :scroll :bypass-leave? :query-merge}
         rf.routing.address/navigate-request-roster)
      "address ∪ raw-URL ∪ policy ∪ edit; any other key rejects :unknown-keys"))

(deftest classify-enforces-the-branch-rules
  (let [home   {:route-id :route/home}
        search {:route-id :route/search :query {:q "x"}}]
    (doseq [[request current expected]
            [;; the whole roster is gated before extraction, even beside a :to
             [{:to :route/article :params {:slug "x"} :my-app/replace? true} nil
              {:reason :unknown-keys :keys [:my-app/replace?]}]
             [{:params {:slug "x"}} home
              {:reason :params-requires-destination :keys [:params]}]
             [{:to :route/home :url "/x"} nil
              {:reason :to-url-exclusive :keys [:to :url]}]
             [{:url "/x" :query {:a 1}} nil
              {:reason :url-excludes-address :keys [:query]}]
             [{:query {} :query-merge {}} home
              {:reason :query-exclusive :keys [:query :query-merge]}]
             [{:to :route/home :query-merge {:a 1}} nil
              {:reason :query-merge-in-place-only :keys [:query-merge]}]
             ;; any non-map, not only the shapes a merge fold happens to accept
             [{:query-merge #{:page}} search
              {:reason :query-merge-not-map :keys [:query-merge]}]
             ;; the rule is about the delta map, never its members
             [{:query-merge {:sort nil :page 0}} search
              nil]
             [{:replace? true} home
              {:reason :no-destination-or-change :keys [:replace?]}]
             [{:query {:a 1}} nil
              {:reason :no-current-route :keys [:query]}]]]
      (is (= expected (rf.routing.address/classify request current)) (pr-str request)))))

(deftest valid-address?-is-the-closed-schema-over-the-extracted-address
  (is (= [true false false true]
         (mapv rf.routing.address/valid-address?
               [{:to :route/article :params {:slug "x"} :query {} :fragment nil}
                {:to :route/article :replace? true}
                {:params {:slug "x"}}
                (rf.routing.address/extract-address {:to :route/article :params {:slug "x"} :replace? true})]))
      "well-formed; a policy key; no :to; the extracted address of a policy-carrying request"))

(deftest link-model-selects-the-address-through-the-shared-extractor
  (rf.routing/reg-route :route/article {} "/articles/:slug")
  (is (= {:href    "/articles/x"
          :payload [:rf.route/url-requested {:url "/articles/x"}]
          :native? true}
         (select-keys (rf.routing.link/link-model {:to       :route/article
                                                   :params   {:slug "x"}
                                                   :target   "_blank"
                                                   :download true}
                                                  :rf/default)
                      [:href :payload :native?]))
      "DOM attrs stay out of the href and the one-key payload, and still reach native-anchor?"))

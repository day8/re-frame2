(ns re-frame.routing-address-extraction-test
  "Focused tests for the shared RouteAddress extraction law
  `re-frame.routing.address` (EP-0037 R0b).

  Pins the extraction law directly at the seam — the closed key classes, the
  whole-roster structural gate (`classify`), the address-only selection
  (`extract-address`), and the closed `:rf/route-address` predicate
  (`valid-address?`) — plus the consumption proof that `route-url` and
  `link-model` (rf/route-link + v/route-link) resolve their address through
  this ONE definition. Per Spec 012 §The extraction law and Spec-Schemas
  §`:rf/route-address`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.routing :as routing]
            [re-frame.routing.address :as address]
            [re-frame.routing.link :as link]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

;; ---- the closed key classes (Spec 012 §The extraction law) ----------------

(deftest key-classes-are-the-closed-spec-sets
  (testing "the address key class is exactly {:to :params :query :fragment}"
    (is (= #{:to :params :query :fragment} address/address-keys)))
  (testing "the edit key class is exactly {:query :query-merge :fragment}"
    (is (= #{:query :query-merge :fragment} address/edit-keys)))
  (testing "policy keys are extracted separately and never overlap the address destination keys"
    (is (empty? (set/intersection address/policy-keys #{:to :url}))
        "a policy key can never be a destination")
    (is (contains? address/policy-keys :replace?))
    (is (contains? address/policy-keys :scroll)))
  (testing "the navigate roster is address ∪ raw-URL ∪ policy ∪ edit"
    (is (= #{:to :params :query :fragment :url :replace? :scroll :bypass-guards? :query-merge}
           address/navigate-request-roster))))

;; ---- classify: whole-roster gate BEFORE extraction ------------------------

(deftest classify-accepts-well-formed-branches
  (testing "a :to destination is well-formed (nil = no violation)"
    (is (nil? (address/classify {:to :route/article :params {:slug "x"}} nil))))
  (testing "a :url destination is well-formed"
    (is (nil? (address/classify {:url "/articles/x"} nil))))
  (testing "an in-place :query edit against a current route is well-formed"
    (is (nil? (address/classify {:query {:tab "history"}}
                                {:route-id :route/article :params {:slug "x"}})))))

(deftest classify-rejects-unknown-keys-before-extraction
  (testing "a misspelled / foreign key rejects :unknown-keys even when :to is present —
            extraction can never silently discard it (Spec 012 §The extraction law)"
    (is (= {:reason :unknown-keys :keys [:my-app/replace?]}
           (address/classify {:to :route/article :params {:slug "x"} :my-app/replace? true} nil))))
  (testing "a misspelled control key (bare) rejects the same way"
    (is (= :unknown-keys (:reason (address/classify {:to :route/article :replac? true} nil))))))

(deftest classify-enforces-the-branch-rules
  (testing ":params without a destination → :params-requires-destination (a path-param change is a destination)"
    (is (= {:reason :params-requires-destination :keys [:params]}
           (address/classify {:params {:slug "x"}} {:route-id :route/home}))))
  (testing ":to xor :url"
    (is (= :to-url-exclusive (:reason (address/classify {:to :route/home :url "/x"} nil)))))
  (testing ":url excludes address keys (a raw URL IS the address)"
    (is (= :url-excludes-address (:reason (address/classify {:url "/x" :query {:a 1}} nil)))))
  (testing ":query xor :query-merge"
    (is (= :query-exclusive (:reason (address/classify {:query {} :query-merge {}}
                                                       {:route-id :route/home})))))
  (testing ":query-merge requires an in-place request (no destination)"
    (is (= :query-merge-in-place-only
           (:reason (address/classify {:to :route/home :query-merge {:a 1}} nil)))))
  (testing "a pure-policy map (no destination, no edit) rejects loud"
    (is (= :no-destination-or-change (:reason (address/classify {:replace? true} {:route-id :route/home})))))
  (testing "an in-place edit before any current route rejects loud"
    (is (= :no-current-route (:reason (address/classify {:query {:a 1}} nil))))))

;; ---- extract-address: only the extracted address is an address ------------

(deftest extract-address-selects-only-the-address-key-class
  (testing "a flat wrapper carrying an address + policy + a DOM attr extracts to ONLY the address keys"
    (is (= {:to :route/article :params {:slug "x"} :query {:tab "c"}}
           (address/extract-address {:to       :route/article
                                     :params   {:slug "x"}
                                     :query    {:tab "c"}
                                     :replace? true         ;; policy — dropped
                                     :on-click identity     ;; behaviour — dropped
                                     :class    "nav"})))))  ;; DOM attr — dropped

(deftest valid-address?-is-the-closed-schema-over-the-extracted-address
  (testing "a well-formed extracted address is valid"
    (is (address/valid-address? {:to :route/article :params {:slug "x"} :query {} :fragment nil})))
  (testing "the FLAT wrapper (address + policy) is NOT a valid address — extraction is required first"
    (is (not (address/valid-address? {:to :route/article :replace? true}))
        "a policy key makes the flat map fail the closed :rf/route-address shape"))
  (testing "a missing :to is invalid (:to is required)"
    (is (not (address/valid-address? {:params {:slug "x"}}))))
  (testing "the extracted address of a policy-carrying wrapper IS valid — policy never reached the schema"
    (is (address/valid-address?
          (address/extract-address {:to :route/article :params {:slug "x"} :replace? true})))))

;; ---- consumption: route-url resolves through the shared address class -----

(deftest route-url-rejects-non-address-keys-via-the-shared-class
  (routing/reg-route :route/article {} "/articles/:slug")
  (testing "route-url is address-only over the SHARED address-keys class: a policy key rejects loud"
    (let [ex (try (routing/route-url {:to :route/article :params {:slug "x"} :replace? true})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "route-url rejects a non-address (policy) key")
      (is (= :bad-address-keys (:reason (ex-data ex))))
      (is (= [:replace?] (:keys (ex-data ex))))))
  (testing "the extracted address builds the same URL whether or not policy rode alongside it"
    (is (= "/articles/x"
           (routing/route-url (address/extract-address
                                {:to :route/article :params {:slug "x"} :replace? true}))))))

;; ---- consumption: route-link (link-model) resolves through the extractor --

(deftest link-model-selects-the-address-through-the-shared-extractor
  (routing/reg-route :route/article {} "/articles/:slug")
  (testing "link-model builds the href from the extracted address and never lets a DOM attr into it"
    (let [model (link/link-model {:to       :route/article
                                  :params   {:slug "x"}
                                  :target   "_blank"     ;; DOM attr — outside the address
                                  :download true}        ;; DOM attr — outside the address
                                 :rf/default)]
      (is (= "/articles/x" (:href model))
          "the DOM attrs did not leak into the synthesised href")
      (is (= [:rf.route/url-requested {:url "/articles/x" :to :route/article :params {:slug "x"}}]
             (:payload model)))
      (is (true? (:native? model))
          "native-anchor? still reads the FULL target (target=_blank / download)"))))

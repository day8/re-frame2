(ns re-frame.routing-address-extraction-test
  "Focused tests for the shared RouteAddress extraction law
  `re-frame.routing.address` (EP-0037 R0b).

  Pins the extraction law directly at the seam — the closed key classes, the
  whole-roster structural gate (`classify`), the address-only selection
  (`extract-address`), and the closed `:rf/route-address` predicate
  (`valid-address?`) — plus the consumption proof that `route-url` and
  `link-model` (`rf/route-link` and a view artefact's route-link) resolve their address through
  this ONE definition. Per Spec 012 §The extraction law and Spec-Schemas
  §`:rf/route-address`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.address :as rf.routing.address]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- the closed key classes (Spec 012 §The extraction law) ----------------

(deftest key-classes-are-the-closed-spec-sets
  (testing "the address key class is exactly {:to :params :query :fragment}"
    (is (= #{:to :params :query :fragment} rf.routing.address/address-keys)))
  (testing "the edit key class is exactly {:query :query-merge :fragment}"
    (is (= #{:query :query-merge :fragment} rf.routing.address/edit-keys)))
  (testing "policy keys are extracted separately and never overlap the address destination keys"
    (is (empty? (set/intersection rf.routing.address/policy-keys #{:to :url}))
        "a policy key can never be a destination")
    (is (contains? rf.routing.address/policy-keys :replace?))
    (is (contains? rf.routing.address/policy-keys :scroll))
    (is (contains? rf.routing.address/policy-keys :bypass-leave?)
        "EP-0037 R4 OI-3: the leave-only escape is the plain boolean :bypass-leave?")
    (is (not (contains? rf.routing.address/policy-keys :bypass-guards?))
        "the set-valued :bypass-guards? is retired"))
  (testing "the navigate roster is address ∪ raw-URL ∪ policy ∪ edit"
    (is (= #{:to :params :query :fragment :url :replace? :scroll :bypass-leave? :query-merge}
           rf.routing.address/navigate-request-roster))))

;; ---- classify: whole-roster gate BEFORE extraction ------------------------

(deftest classify-accepts-well-formed-branches
  (testing "a :to destination is well-formed (nil = no violation)"
    (is (nil? (rf.routing.address/classify {:to :route/article :params {:slug "x"}} nil))))
  (testing "a :url destination is well-formed"
    (is (nil? (rf.routing.address/classify {:url "/articles/x"} nil))))
  (testing "an in-place :query edit against a current route is well-formed"
    (is (nil? (rf.routing.address/classify {:query {:tab "history"}}
                                {:route-id :route/article :params {:slug "x"}})))))

(deftest classify-rejects-unknown-keys-before-extraction
  (testing "a misspelled / foreign key rejects :unknown-keys even when :to is present —
            extraction can never silently discard it (Spec 012 §The extraction law)"
    (is (= {:reason :unknown-keys :keys [:my-app/replace?]}
           (rf.routing.address/classify {:to :route/article :params {:slug "x"} :my-app/replace? true} nil))))
  (testing "a misspelled control key (bare) rejects the same way"
    (is (= :unknown-keys (:reason (rf.routing.address/classify {:to :route/article :replac? true} nil))))))

(deftest classify-enforces-the-branch-rules
  (testing ":params without a destination → :params-requires-destination (a path-param change is a destination)"
    (is (= {:reason :params-requires-destination :keys [:params]}
           (rf.routing.address/classify {:params {:slug "x"}} {:route-id :route/home}))))
  (testing ":to xor :url"
    (is (= :to-url-exclusive (:reason (rf.routing.address/classify {:to :route/home :url "/x"} nil)))))
  (testing ":url excludes address keys (a raw URL IS the address)"
    (is (= :url-excludes-address (:reason (rf.routing.address/classify {:url "/x" :query {:a 1}} nil)))))
  (testing ":query xor :query-merge"
    (is (= :query-exclusive (:reason (rf.routing.address/classify {:query {} :query-merge {}}
                                                       {:route-id :route/home})))))
  (testing ":query-merge requires an in-place request (no destination)"
    (is (= :query-merge-in-place-only
           (:reason (rf.routing.address/classify {:to :route/home :query-merge {:a 1}} nil)))))
  (testing "a pure-policy map (no destination, no edit) rejects loud"
    (is (= :no-destination-or-change (:reason (rf.routing.address/classify {:replace? true} {:route-id :route/home})))))
  (testing "an in-place edit before any current route rejects loud"
    (is (= :no-current-route (:reason (rf.routing.address/classify {:query {:a 1}} nil))))))

;; ---- rf2-16w8 — the `:query-merge` VALUE is a map of query deltas ---------

(deftest classify-rejects-non-map-query-merge
  ;; Per Spec 012 §Validity rules rule 9 / §In-place navigation: `:query-merge`
  ;; is a MAP of query deltas. Key PRESENCE discriminates the in-place branch,
  ;; so the gate is the ONLY place that can speak about the VALUE — before
  ;; this rule a present non-map value sailed through `classify` and reached
  ;; `navigate-handler`'s unguarded `merge` fold, where Clojure's own
  ;; collection semantics decided the outcome three different ways.
  (let [current {:route-id :route/search :query {:q "x"}}]

    (testing "POSITIVE CONTROL — the hazard this rule closes is real on this host"
      ;; Without these rows the rejections below would prove only that the
      ;; gate says no to something; these prove WHY it must. If a future
      ;; Clojure stopped accepting a 2-vector as a map entry this control
      ;; goes red and tells the reader the rule's rationale has moved.
      (is (= {:page 2} (merge {} [:page 2]))
          "a two-element VECTOR really is a map entry to `merge` — an unguarded
           fold SUCCEEDS on it and commits a real navigation")
      (is (not (map? [:page 2]))
          "…and it is not a map, so only an explicit map? check can tell")
      (is (thrown? ClassCastException (merge {} "oops"))
          "a string reaches a RAW host throw in the same fold (no ex-data)")
      (is (= {:q "x"} (merge {:q "x"} nil))
          "a present nil VANISHES in the fold — a silent no-op, not a reject"))

    (testing "a two-element vector rejects (it would otherwise NAVIGATE)"
      (is (= {:reason :query-merge-not-map :keys [:query-merge]}
             (rf.routing.address/classify {:query-merge [:page 2]} current))))
    (testing "a string rejects (it would otherwise raise ClassCastException)"
      (is (= {:reason :query-merge-not-map :keys [:query-merge]}
             (rf.routing.address/classify {:query-merge "oops"} current))))
    (testing "a present nil rejects — omission is the spelling for 'no merge'"
      (is (= {:reason :query-merge-not-map :keys [:query-merge]}
             (rf.routing.address/classify {:query-merge nil} current))))
    (testing "a sequence of pairs rejects (a fold would have accepted it)"
      (is (= :query-merge-not-map
             (:reason (rf.routing.address/classify {:query-merge [[:page 2]]} current))))
      (is (= :query-merge-not-map
             (:reason (rf.routing.address/classify {:query-merge #{:page}} current)))))

    (testing "a MAP value still passes — the valid surface is unchanged"
      (is (nil? (rf.routing.address/classify {:query-merge {}} current))
          "{} remains a valid exact no-op")
      (is (nil? (rf.routing.address/classify {:query-merge {:page 2}} current)))
      (is (nil? (rf.routing.address/classify {:query-merge {:sort nil}} current))
          "a nil INSIDE the delta map still deletes a key — the rule is about
           the delta map itself, never its members")
      (is (nil? (rf.routing.address/classify {:query-merge {:page 0 :flag ""}} current))
          "falsy member values are legitimate and untouched")
      (is (nil? (rf.routing.address/classify {:query-merge (sorted-map :page 2)} current))
          "any map implementation qualifies, not just the literal one")
      (is (not= (class (sorted-map :page 2)) (class {:page 2}))
          "control: that last row really did exercise a DIFFERENT map type"))

    (testing "the pre-existing exclusions keep precedence over the value check"
      ;; The value rule sits AFTER the roster / mutual-exclusion rules, so a
      ;; request that is wrong in two ways still reports the relationship it
      ;; always reported. This is what stops the new rule silently re-labelling
      ;; existing rejections.
      (is (= :unknown-keys
             (:reason (rf.routing.address/classify {:query-merge "oops" :bogus 1} current))))
      (is (= :url-excludes-address
             (:reason (rf.routing.address/classify {:url "/b" :query-merge "oops"} current))))
      (is (= :query-exclusive
             (:reason (rf.routing.address/classify {:query {} :query-merge "oops"} current))))
      (is (= :query-merge-in-place-only
             (:reason (rf.routing.address/classify {:to :route/b :query-merge "oops"} current)))))))

;; ---- extract-address: only the extracted address is an address ------------

(deftest extract-address-selects-only-the-address-key-class
  (testing "a flat wrapper carrying an address + policy + a DOM attr extracts to ONLY the address keys"
    (is (= {:to :route/article :params {:slug "x"} :query {:tab "c"}}
           (rf.routing.address/extract-address {:to       :route/article
                                     :params   {:slug "x"}
                                     :query    {:tab "c"}
                                     :replace? true         ;; policy — dropped
                                     :on-click identity     ;; behaviour — dropped
                                     :class    "nav"})))))  ;; DOM attr — dropped

(deftest valid-address?-is-the-closed-schema-over-the-extracted-address
  (testing "a well-formed extracted address is valid"
    (is (rf.routing.address/valid-address? {:to :route/article :params {:slug "x"} :query {} :fragment nil})))
  (testing "the FLAT wrapper (address + policy) is NOT a valid address — extraction is required first"
    (is (not (rf.routing.address/valid-address? {:to :route/article :replace? true}))
        "a policy key makes the flat map fail the closed :rf/route-address shape"))
  (testing "a missing :to is invalid (:to is required)"
    (is (not (rf.routing.address/valid-address? {:params {:slug "x"}}))))
  (testing "the extracted address of a policy-carrying wrapper IS valid — policy never reached the schema"
    (is (rf.routing.address/valid-address?
          (rf.routing.address/extract-address {:to :route/article :params {:slug "x"} :replace? true})))))

;; ---- consumption: route-url resolves through the shared address class -----

(deftest route-url-rejects-non-address-keys-via-the-shared-class
  (rf.routing/reg-route :route/article {} "/articles/:slug")
  (testing "route-url is address-only over the SHARED address-keys class: a policy key rejects loud"
    (let [ex (try (rf.routing/route-url {:to :route/article :params {:slug "x"} :replace? true})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "route-url rejects a non-address (policy) key")
      (is (= :bad-address-keys (:reason (ex-data ex))))
      (is (= [:replace?] (:keys (ex-data ex))))))
  (testing "the extracted address builds the same URL whether or not policy rode alongside it"
    (is (= "/articles/x"
           (rf.routing/route-url (rf.routing.address/extract-address
                                {:to :route/article :params {:slug "x"} :replace? true}))))))

;; ---- consumption: route-link (link-model) resolves through the extractor --

(deftest link-model-selects-the-address-through-the-shared-extractor
  (rf.routing/reg-route :route/article {} "/articles/:slug")
  (testing "link-model builds the href from the extracted address and never lets a DOM attr into it"
    (let [model (rf.routing.link/link-model {:to       :route/article
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

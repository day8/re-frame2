(ns re-frame.routing-query-spelling-test
  "ONE query spelling on every door.

  A URL door keeps a query key the route did not declare as a STRING key with
  a STRING value (`match-url`'s rule), so the named-address doors
  — `[:rf.route/navigate {:to …}]`, the in-place `:query` / `:query-merge`
  edit, `route-url` and so `route-link`'s href — must not pass the caller's
  spelling straight through. On a route with no query vocabulary that would give one
  destination two slices: `:query-merge {:page 2}` over a URL-seeded
  `{\"page\" \"1\"}` would commit BOTH spellings and push `page=2&page=1`, which a
  reload reads back as `page=1`; `{:page nil}` could not remove the key; and a
  programmatic `{:to … :query {:q \"x\"}}` followed by the SAME URL through the
  URL door would be a full re-activation instead of Spec 012's rule-3 no-op.

  Every entry is spelled the way the URL spells it, by its URL TOKEN against the
  destination route's declared vocabulary (`rf.routing.registry/canonical-query`):
  an undeclared entry becomes a string key with a string value, and a string
  key naming a declared token becomes that declared keyword. It runs at the
  resolved-target seam, on the `:query-merge` deltas before the fold, and in
  `route-url` before the canonical sort.

  The `:query-merge` suite in `routing-navigation-test` cannot see
  this defect class: every route there declares its keys, and a declared key is
  keyword-keyed on every door. Every route below is BARE unless the test
  says otherwise."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.routing :as rf.routing]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- slice []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/routing :current]))

(defn- record-pushes! []
  (let [pushed (atom [])]
    (rf.fx/reg-fx :rf.nav/push-url
                  {:platforms #{:server :client}}
                  (fn [_ url] (swap! pushed conj url)))
    pushed))

;; ---- the in-place fold ------------------------------------------------------

(deftest query-merge-over-a-url-seeded-undeclared-key-overwrites-it
  (testing "a keyword delta for an undeclared key REPLACES the URL-seeded string
            key rather than adding a keyword twin, so the pushed URL carries
            one page= and a reload reads the edit back"
    (rf/reg-route :route/search {} "/search")
    (let [pushed (record-pushes!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/search?page=1&q=x"
                         {:rf.route/cause :link}])
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page 2}}])
      (is (= [{"page" "2" "q" "x"} ["/search?page=2&q=x"]]
             [(:query (slice)) @pushed]))
      (testing "and a nil delta removes the key it names"
        (reset! pushed [])
        (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page nil}}])
        (is (= [{"q" "x"} ["/search?q=x"]]
               [(:query (slice)) @pushed]))))))

(deftest query-merge-with-a-string-key-for-a-declared-token-is-that-keyword
  (testing "the mirror case: a DECLARED key spelled as a string names the
            declared keyword, so it merges over the typed value instead of
            emitting page= twice"
    (rf/reg-route :route/decl {:query [:map [:page {:optional true} :int]]} "/decl")
    (let [pushed (record-pushes!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/decl?page=2"
                         {:rf.route/cause :link}])
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {"page" 3}}])
      (is (= [{:page 3} ["/decl?page=3"]]
             [(:query (slice)) @pushed])))))

;; ---- one destination, one slice, across doors ------------------------------

(deftest a-named-address-and-its-own-url-are-the-same-target
  (testing "{:to … :query …} then the SAME URL through the URL door is a rule-3
            no-op: the committed query is what the URL door resolves, the
            nav-token does not advance and :on-match does not re-fire"
    (let [fires  (atom 0)
          pushed (record-pushes!)]
      (rf/reg-event :test/search-shown (fn [_ _] (swap! fires inc) {}))
      (rf/reg-route :route/search {:on-match [[:test/search-shown]]} "/search")
      (rf/reg-route :route/elsewhere {} "/elsewhere")
      (doseq [[label query] [["keyword key, string value" {:q "x"}]
                             ["string key, integer value" {"page" 2}]]]
        (testing label
          ;; Start somewhere else, so the navigation below is a real transition.
          (rf/dispatch-sync [:rf.route/navigate {:to :route/elsewhere}])
          (reset! pushed [])
          (rf/dispatch-sync [:rf.route/navigate {:to :route/search :query query}])
          (let [token  (:nav-token (slice))
                before @fires
                url    (last @pushed)]
            (is (= (:query (rf.routing/match-url url)) (:query (slice))))
            (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}])
            (is (= [token before] [(:nav-token (slice)) @fires]))))))))

(deftest route-url-spells-a-query-the-same-whichever-key-kind-the-caller-used
  (rf/reg-route :route/search {} "/search")
  (testing "an undeclared key orders and emits the same as its string spelling"
    (is (= (rf.routing/route-url {:to :route/search :query {"z" "1" "a" "2"}})
           (rf.routing/route-url {:to :route/search :query {:z "1" "a" "2"}}))
        "one canonical href, not ?z=1&a=2 for one spelling and ?a=2&z=1 for the other"))
  (testing "a declared token spelled as a string emits once"
    (rf/reg-route :route/decl {:query [:map [:page {:optional true} :int]]} "/decl")
    (is (= "/decl?page=3"
           (rf.routing/route-url {:to :route/decl :query {:page 2 "page" 3}}))
        "the later spelling wins; the URL carries ONE page=")))

(deftest an-undeclared-value-commits-as-the-string-the-url-carries
  (testing "an ADMITTED scalar, under a plain or a namespaced key, is committed
            as its URL string — the value match-url hands back for the pushed URL"
    (rf/reg-route :route/search {} "/search")
    (let [pushed (record-pushes!)]
      (rf/dispatch-sync [:rf.route/navigate
                         {:to    :route/search
                          :query {:page 2 :on true :tag 'sym :user/id "u"}}])
      (is (= {"page" "2" "on" "true" "tag" "sym" "user/id" "u"}
             (:query (slice))
             (:query (rf.routing/match-url (last @pushed))))))))

;; ---- control: the refusal rows keep their refusal --------------------------

(deftest non-admitted-values-are-still-refused-and-never-stringified
  (testing "route-url refuses a value with no URL form under the URL's own
            string-key spelling too, rather than stringifying it"
    (rf/reg-route :route/search {} "/search")
    (is (= :rf.error/route-url-non-edn-value
           (try (rf.routing/route-url {:to :route/search :query {"x" (Object.)}})
                nil
                (catch clojure.lang.ExceptionInfo ex (:rf.error/id (ex-data ex))))))))

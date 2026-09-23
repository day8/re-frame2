(ns re-frame.routing-query-spelling-test
  "rf2-3x7nj.12.1 — ONE query spelling on every door.

  A URL door keeps a query key the route did not declare as a STRING key with
  a STRING value (`match-url`'s rf2-5ifai rule), while the named-address doors
  — `[:rf.route/navigate {:to …}]`, the in-place `:query` / `:query-merge`
  edit, `route-url` and so `route-link`'s href — used to pass the caller's
  spelling straight through. On a route with no query vocabulary that gave one
  destination two slices: `:query-merge {:page 2}` over a URL-seeded
  `{\"page\" \"1\"}` committed BOTH spellings and pushed `page=2&page=1`, which a
  reload read back as `page=1`; `{:page nil}` could not remove the key; and a
  programmatic `{:to … :query {:q \"x\"}}` followed by the SAME URL through the
  URL door was a full re-activation instead of Spec 012's rule-3 no-op.

  The fix spells every entry the way the URL does, by its URL TOKEN against the
  destination route's declared vocabulary (`rf.routing.registry/canonical-query`):
  an undeclared entry becomes a string key with a string value, and a string
  key naming a declared token becomes that declared keyword. It runs at the
  resolved-target seam, on the `:query-merge` deltas before the fold, and in
  `route-url` before the canonical sort.

  The pre-existing `:query-merge` suite in `routing-navigation-test` cannot see
  this defect: every route there declares its keys, and a declared key was
  always keyword-keyed on every door. Every route below is BARE unless the test
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

(defn- thrown-id
  "The `:rf.error/id` a thunk throws, or nil when it returns."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo ex (:rf.error/id (ex-data ex)))))

;; ---- the in-place fold ------------------------------------------------------

(deftest query-merge-over-a-url-seeded-undeclared-key-overwrites-it
  (testing "a keyword delta for an undeclared key REPLACES the URL-seeded string
            key rather than adding a keyword twin beside it"
    (rf/reg-route :route/search {} "/search")
    (let [pushed (record-pushes!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/search?page=1&q=x"
                         {:rf.route/cause :link}])
      (is (= {"page" "1" "q" "x"} (:query (slice)))
          "precondition: the URL door keeps undeclared keys as strings")
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page 2}}])
      (is (= {"page" "2" "q" "x"} (:query (slice)))
          "ONE page key, spelled the way the URL spells it")
      (is (= ["/search?page=2&q=x"] @pushed)
          "and one page= in the pushed URL, so a reload reads the edit back")
      (is (= (:query (slice))
             (:query (rf.routing/match-url (last @pushed))))
          "the reload's slice IS the committed slice")
      (testing "and a nil delta removes the key it names"
        (reset! pushed [])
        (rf/dispatch-sync [:rf.route/navigate {:query-merge {:page nil}}])
        (is (= {"q" "x"} (:query (slice))))
        (is (= ["/search?q=x"] @pushed))))))

(deftest query-merge-with-a-string-key-for-a-declared-token-is-that-keyword
  (testing "the mirror case: a DECLARED key spelled as a string names the
            declared keyword, so it merges over the typed value instead of
            emitting page= twice"
    (rf/reg-route :route/decl {:query [:map [:page {:optional true} :int]]} "/decl")
    (let [pushed (record-pushes!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/decl?page=2"
                         {:rf.route/cause :link}])
      (is (= {:page 2} (:query (slice))) "precondition: a declared key is typed")
      (rf/dispatch-sync [:rf.route/navigate {:query-merge {"page" 3}}])
      (is (= {:page 3} (:query (slice))))
      (is (= ["/decl?page=3"] @pushed)))))

;; ---- one destination, one slice, across doors ------------------------------

(deftest a-named-address-and-its-own-url-are-the-same-target
  (testing "{:to … :query {:q \"x\"}} then the SAME URL through the URL door is
            a rule-3 no-op: same nav-token, no :on-match re-fire"
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
            (is (some? url) "the programmatic door pushed a URL")
            (is (= (:query (rf.routing/match-url url)) (:query (slice)))
                "the committed query is exactly what the URL door resolves")
            (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}])
            (is (= token (:nav-token (slice)))
                "the nav-token did not advance — no re-activation")
            (is (= before @fires) ":on-match did not re-fire")))))))

(deftest route-url-spells-a-query-the-same-whichever-key-kind-the-caller-used
  (rf/reg-route :route/search {} "/search")
  (testing "an undeclared key orders and emits the same as its string spelling"
    (is (= (rf.routing/route-url {:to :route/search :query {"z" "1" "a" "2"}})
           (rf.routing/route-url {:to :route/search :query {:z "1" "a" "2"}}))
        "one canonical href, not ?z=1&a=2 for one spelling and ?a=2&z=1 for the other"))
  (testing "a namespaced undeclared keyword keeps its namespace in the token"
    (is (= "/search?user%2Fid=u"
           (rf.routing/route-url {:to :route/search :query {:user/id "u"}}))))
  (testing "a declared token spelled as a string emits once"
    (rf/reg-route :route/decl {:query [:map [:page {:optional true} :int]]} "/decl")
    (is (= "/decl?page=3"
           (rf.routing/route-url {:to :route/decl :query {:page 2 "page" 3}}))
        "the later spelling wins; the URL carries ONE page=")))

(deftest an-undeclared-value-commits-as-the-string-the-url-carries
  (testing "an ADMITTED scalar is committed as its URL string — the value
            match-url hands back for the pushed URL"
    (rf/reg-route :route/search {} "/search")
    (let [pushed (record-pushes!)]
      (rf/dispatch-sync [:rf.route/navigate
                         {:to    :route/search
                          :query {:page 2 :on true :tag 'sym :user/id "u"}}])
      (is (= {"page" "2" "on" "true" "tag" "sym" "user/id" "u"} (:query (slice))))
      (is (= (:query (slice)) (:query (rf.routing/match-url (last @pushed))))))))

;; ---- controls: the refusal rows keep their refusal -------------------------

(deftest non-admitted-values-are-still-refused-and-never-stringified
  (rf/reg-route :route/search {} "/search")
  (testing "route-url still refuses a host value, a float and an unsafe integer"
    (doseq [[label v] [["host object" (Object.)]
                       ["float" 1.5]
                       ["2^53" 9007199254740992]]]
      (is (= :rf.error/route-url-non-edn-value
             (thrown-id #(rf.routing/route-url {:to :route/search :query {:x v}})))
          (str label " under a keyword key"))
      (is (= :rf.error/route-url-non-edn-value
             (thrown-id #(rf.routing/route-url {:to :route/search :query {"x" v}})))
          (str label " under a string key"))))
  (testing "the navigate door still rejects rather than committing a stringified value"
    (let [pushed (record-pushes!)]
      (rf/dispatch-sync [:rf.route/navigate {:to :route/search :query {:x 1.5}}])
      (is (nil? (slice)) "nothing committed")
      (is (empty? @pushed) "nothing pushed"))))

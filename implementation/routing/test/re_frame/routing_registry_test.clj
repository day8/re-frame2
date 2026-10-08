(ns re-frame.routing-registry-test
  "Registry / match / URL-construction tests for re-frame.routing: reg-route
  validation, match-url, route-url, ranking, optional groups, splats and
  query coercion.

  ## Posture split

  The registry is production-real, so this namespace runs in the ordinary
  `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane).

  Four deftests also read the DEV TRACE: the `:rf.route/registered` and
  `:rf.route/cleared` lifecycle ops and the
  `:rf.warning/route-shadowed-by-equal-score` advisory. All three emit through
  `trace/emit!`, gated on `rf.interop/debug-enabled?` and read once at load
  time, so their trace assertions sit inside `(when rf.interop/debug-enabled? …)`
  arms. The shadow advisory's claim is about which route wins at match time,
  so that deftest also checks the winner through `match-url`, outside the arm."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.routing.match :as rf.routing.match]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.source-store :as rf.source-store]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- thrown-data
  "The ex-data of the ExceptionInfo `f` throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ---- route-url's structured failures --------------------------------------

(deftest route-url-missing-required-path-param-throws
  (testing "an absent required path param or splat raises
            :rf.error/missing-route-param naming the param and the route"
    (rf/reg-route :route/article {} "/articles/:id")
    (rf/reg-route :route/files {} "/files/*path")
    (are [to param] (= {:rf.error/id :rf.error/missing-route-param :route-id to :param param}
                       (select-keys (thrown-data #(rf.routing/route-url {:to to :params {}}))
                                    [:rf.error/id :route-id :param]))
      :route/article :id
      :route/files   :path)))

(deftest route-url-no-such-route-throws
  (is (= {:rf.error/id :rf.error/no-such-route :route-id :route/no-such-route}
         (select-keys (thrown-data #(rf.routing/route-url {:to :route/no-such-route}))
                      [:rf.error/id :route-id]))))

;; EP-0012 §Canonical EDN identity: a value with no URL form fails closed
;; rather than being host-stringified. An entered optional group emits through
;; its own loop, so it carries its own guard.
(deftest route-url-optional-group-host-value-fails-closed-rf2-94o54l
  (testing "a host value in an entered optional group fails closed"
    (rf/reg-route :route/doc {} "/docs{/:section}?")
    (is (= {:rf.error/id :rf.error/route-url-non-edn-value :param :section}
           (select-keys (thrown-data #(rf.routing/route-url {:to :route/doc :params {:section (fn [_])}}))
                        [:rf.error/id :param])))))

;; ---- match-url edges -------------------------------------------------------

(deftest match-url-trailing-slash-normalizes
  (testing "/foo/ resolves exactly as /foo (Spec 012 trailing-slash equivalence)"
    (rf/reg-route :route/foo {} "/foo")
    (is (= {:route-id :route/foo :params {} :query {} :fragment nil :validation-failed? false}
           (rf.routing/match-url "/foo/")))))

;; Spec 012 §Routing failure semantics: malformed %-encoding anywhere in the
;; URL is a route-miss (nil), never a thrown exception and never a partial slice.

(deftest match-url-malformed-percent-in-query-fails-closed
  (testing "a malformed query value or key fails the WHOLE URL closed, not just the bad pair"
    (rf/reg-route :route/search {} "/search")
    (is (nil? (rf.routing/match-url "/search?good=1&bad=%&also=2")))
    (is (nil? (rf.routing/match-url "/search?ok=1&%=bad&also=2")))))

(deftest match-url-malformed-percent-in-fragment-fails-closed
  (testing "a malformed #fragment fails the URL closed; a well-formed one is decoded"
    (rf/reg-route :route/page {} "/page")
    (is (nil? (rf.routing/match-url "/page#good%a")))
    (is (= "hello world" (:fragment (rf.routing/match-url "/page#hello%20world"))))))

;; ---- route-url query emission ----------------------------------------------

(deftest route-url-query-string-emission
  (testing "pairs join with `&` in canonical key order whatever the caller's
            insertion order; keys and values are percent-encoded (a space is
            %20, and `&` / `=` cannot inject a pair); a nil value is elided
            while false and \"\" are present values"
    (rf/reg-route :route/list {} "/list")
    (are [query url] (= url (rf.routing/route-url {:to :route/list :query query}))
      (array-map :z "3" :x "1" :y "2") "/list?x=1&y=2&z=3"
      {:q "x y"}                       "/list?q=x%20y"
      {:filter "a&b=c"}                "/list?filter=a%26b%3Dc"
      {(keyword "a b") "v"}            "/list?a%20b=v"
      {:page nil}                      "/list"
      (array-map :a "1" :b nil)        "/list?a=1"
      {:flag false}                    "/list?flag=false"
      {:s ""}                          "/list?s=")))

(deftest route-url-throws-on-invalid-path-params
  (let [restore (rf.routing-test-support/with-stub-validator)]
    (try
      (rf/reg-route :route/article
                    {:params (fn [{:keys [id]}] (string/starts-with? (or id "") "a"))} "/articles/:id")
      (testing "non-conforming path params throw :rf.error/route-url-validation
                carrying the slot, the value and the explainer payload"
        (let [data (thrown-data #(rf.routing/route-url {:to :route/article :params {:id "zoo"}}))]
          (is (= {:rf.error/id :rf.error/route-url-validation
                  :route-id    :route/article
                  :slot        :params
                  :value       {:id "zoo"}}
                 (select-keys data [:rf.error/id :route-id :slot :value])))
          (is (some? (:error data)))))
      (testing "conforming path params still build the URL"
        (is (= "/articles/aardvark"
               (rf.routing/route-url {:to :route/article :params {:id "aardvark"}}))))
      (finally (restore)))))

(deftest route-url-throws-on-invalid-query-params
  (testing "a query that fails the route's :query schema throws; an empty query
            map is still validated"
    (let [restore (rf.routing-test-support/with-stub-validator)]
      (try
        ;; A predicate schema declares no query vocabulary, so `:q` reaches it
        ;; spelled the way the URL spells it — the string key "q".
        (rf/reg-route :route/search
                      {:query (fn [m] (pos? (count (get m "q"))))} "/search")
        (doseq [query [{:q ""} {}]]
          (is (= {:rf.error/id :rf.error/route-url-validation :slot :query}
                 (select-keys (thrown-data #(rf.routing/route-url {:to :route/search :query query}))
                              [:rf.error/id :slot]))
              (pr-str query)))
        (finally (restore))))))

(deftest route-url-elides-nil-query-before-validation-rf2-w3qgc
  (testing "a nil query value is elided BEFORE :query validation, so an
            optional key set to nil emits no pair; a non-nil invalid value
            still fails"
    (let [restore (rf.routing-test-support/with-stub-validator)]
      (try
        ;; An optional "sort" restricted to "name" / "date". A predicate schema
        ;; declares no query vocabulary, so `:sort` reaches it as the string
        ;; key "sort".
        (rf/reg-route :route/search
                      {:query (fn [m] (or (not (contains? m "sort"))
                                          (contains? #{"name" "date"} (get m "sort"))))}
                      "/search")
        (is (= "/search" (rf.routing/route-url {:to :route/search :query {:sort nil}})))
        (is (= {:rf.error/id :rf.error/route-url-validation :slot :query :value {"sort" "123"}}
               (select-keys (thrown-data #(rf.routing/route-url {:to :route/search :query {:sort 123}}))
                            [:rf.error/id :slot :value])))
        (finally (restore))))))

;; ============================================================================
;; Pattern grammar, shadowing, coercion, optional groups and splats
;; ============================================================================

(deftest invalid-route-patterns-fail-at-registration
  (testing "every grammar violation `match/validate-route-pattern!` names is
            rejected at reg-route with :rf.error/invalid-route-pattern"
    (doseq [pattern [""                  ;; empty
                     "cart"              ;; no leading `/`
                     "/x//y"             ;; empty segment (a trailing `/` is canonicalised away)
                     "/a}b"              ;; bare `}`
                     "/a?b"              ;; bare `?`
                     "/a:id"             ;; param mid-segment
                     "/a*rest"           ;; splat mid-segment
                     "/:1bad"            ;; bad param name
                     "/*1bad"            ;; bad splat name
                     "/files/*rest/more" ;; splat not final
                     "/a{/:b"            ;; group unclosed
                     "/a{/:b}"           ;; group without `?`
                     "/a{}?"             ;; empty group
                     "/a{//}?"           ;; empty segment in a group
                     "/articles{:id}?"   ;; slash-outside group
                     "/a{/:b{/:c}?}?"    ;; nested group
                     "/a{/*rest}?"       ;; splat in a group
                     "/a{/:1bad}?"       ;; bad param name in a group
                     "/shop{/a:b}?"      ;; reserved char in a group literal
                     42]]                ;; not a string
      (is (= :rf.error/invalid-route-pattern
             (:rf.error/id (thrown-data #(rf/reg-route :route/bad {} pattern))))
          (pr-str pattern))))

  (testing "the error names the route, the pattern and the offending index"
    (is (= {:route-id :route/bad :pattern "/a:id" :index 2}
           (select-keys (thrown-data #(rf/reg-route :route/bad {} "/a:id"))
                        [:route-id :pattern :index]))))

  (testing "trailing slashes in registered patterns are canonicalized away"
    (rf/reg-route :route/cart {} "/cart/")
    (is (= "/cart" (rf.routing/route-url {:to :route/cart})))))

;; ---- :rf.warning/route-shadowed-by-equal-score -----------------------------
;; The warning fires iff the rules-1-5 structural rank ties AND the two
;; patterns are co-matchable (Spec 012 §Route ranking algorithm rule 6's "same
;; URL family"). Equal rank alone is not a conflict: rank ignores literal text.

(defn- shadow-warnings
  "Run `f` under a trace listener; return the captured
  `:rf.warning/route-shadowed-by-equal-score` events."
  [f]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::shadow (fn [ev] (swap! traces conj ev)))
    (try (f)
         (finally (rf/unregister-listener! :trace ::shadow)))
    (filterv #(= :rf.warning/route-shadowed-by-equal-score (:operation %))
             @traces)))

(deftest route-shadowed-by-equal-score-warning
  (testing "co-matchable equal-rank routes: the earliest registration wins at
            match time, and each later one warns naming it, with the tied
            structural rank"
    (let [warns (shadow-warnings
                  (fn []
                    (rf/reg-route :route/first  {} "/m/:x")
                    (rf/reg-route :route/second {} "/m/:y")
                    (rf/reg-route :route/third  {} "/m/:z")))]
      (is (= {:route-id :route/first :params {:x "7"}}
             (select-keys (rf.routing/match-url "/m/7") [:route-id :params])))
      (when rf.interop/debug-enabled?
        (is (= [{:route-id :route/second :shadowed-by :route/first :rank [1 1 2 1 1]}
                {:route-id :route/third  :shadowed-by :route/first :rank [1 1 2 1 1]}]
               (mapv #(select-keys (:tags %) [:route-id :shadowed-by :rank]) warns)))))))

(deftest route-shadow-scan-registration-benchmark
  (testing "a large table of same-rank routes that never co-match — the worst
            case for the rank prefilter, since every pair ties — registers
            quickly and emits no warning"
    (let [start      (System/nanoTime)
          warns      (shadow-warnings
                       (fn []
                         (dotimes [i 300]
                           (rf/reg-route (keyword "bench" (str "static-" i)) {}
                                         (str "/bench-" i)))
                         ;; a same-rank param family: the automata walk two segments per tie
                         (dotimes [i 50]
                           (rf/reg-route (keyword "bench" (str "param-" i)) {}
                                         (str "/p" i "/:id")))))
          elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
      ;; A generous bound: it guards an exponential blow-up in the
      ;; product-automaton walk, not micro-performance.
      (is (< elapsed-ms 10000)
          (str "registration scan stayed bounded (took " elapsed-ms " ms)"))
      (when rf.interop/debug-enabled?
        (is (= [] warns))))))

;; ---- query coercion ----------------------------------------------------------

(deftest query-coercion-vocabulary
  (testing ":int / :boolean / `[:enum …]` coerce, :string passes through, and a
            value its type cannot read stays the string. :int is strict and
            host-identical: only a whole integer literal coerces (`js/parseInt`
            would read 12 out of \"12abc\")"
    (rf/reg-route :route/search
                  {:query [:map
                           [:count    :int]
                           [:sort     [:enum :asc :desc]]
                           [:archived :boolean]
                           [:plain    :string]]} "/search")
    (are [qs expected] (= expected (:query (rf.routing/match-url (str "/search?" qs))))
      "count=42&sort=desc&archived=true&plain=hello" {:count 42 :sort :desc :archived true :plain "hello"}
      "archived=false"                               {:archived false}
      "archived=maybe"                               {:archived "maybe"}
      "count=-7"                                     {:count -7}
      "count=12abc"                                  {:count "12abc"}
      "count=0x10"                                   {:count "0x10"}
      "count=%2012"                                  {:count " 12"})))

;; A slot carrying Malli properties (`[:int {:min 1}]`), an enum with an
;; options map, or a `[:maybe …]` wrapper coerces like its bare form; read raw,
;; `[:int {:min 1}]` would validate the string "2" and 404 every deep link.
(deftest rf2-fwz29i-optioned-scalar-query-coercion
  (testing "optioned, options-map enum and :maybe-wrapped slots coerce like
            their bare forms and conform"
    (rf/reg-route :route/items
                  {:query [:map
                           [:page [:int {:min 1}]]
                           [:id [:uuid {}]]
                           [:archived [:boolean {}]]
                           [:sort [:enum {:default :asc} :asc :desc]]
                           [:size [:maybe [:int {:min 1}]]]]} "/items")
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"]
      (is (= {:route-id           :route/items
              :query              {:page 2 :id (parse-uuid uuid-str) :archived true :sort :asc :size 3}
              :validation-failed? false}
             (select-keys (rf.routing/match-url
                            (str "/items?page=2&id=" uuid-str "&archived=true&sort=asc&size=3"))
                          [:route-id :query :validation-failed?])))))

  (testing "the option still bites after coercion"
    (rf/reg-route :route/min-page {:query [:map [:page [:int {:min 5}]]]} "/p")
    (is (= {:query {:page 2} :validation-failed? true}
           (select-keys (rf.routing/match-url "/p?page=2") [:query :validation-failed?])))))

;; A float has no canonical-EDN identity, so a :double slot would break the
;; route prism (match-url would coerce a float route-url then refuses to emit)
;; and diverge across hosts. reg-route rejects it in every shape.
(deftest route-double-decimal-rejected-at-reg-route-rf2-5s7l6d
  (doseq [[metadata slot param] [[{:params [:map [:x :double]]}              :params :x]
                                 [{:params [:map [:x [:double {:min 0.0}]]]} :params :x]
                                 [{:params [:map [:x [:maybe :double]]]}     :params :x]
                                 [{:query  [:map [:ratio :double]]}          :query  :ratio]]]
    (is (= {:rf.error/id :rf.error/route-decimal-unsupported :route-id :route/dec :slot slot :param param}
           (select-keys (thrown-data #(rf/reg-route :route/dec metadata "/dec/:x"))
                        [:rf.error/id :route-id :slot :param]))
        (pr-str metadata))))

;; The keyword-interning defence: only keys the route declares (`:query` /
;; `:query-defaults`) become keywords; every other URL key stays a string, so a
;; hostile URL of N unique keys interns no keyword.
(deftest rf2-3k3o7-undeclared-query-keys-stay-as-strings
  (testing "declared :q is keyword-keyed; undeclared keys stay strings"
    (rf/reg-route :route/search {:query [:map [:q :string]]} "/search")
    (is (= {:q "clojure" "unknown1" "foo" "unknown2" "bar"}
           (:query (rf.routing/match-url "/search?q=clojure&unknown1=foo&unknown2=bar"))))))

;; ---- optional groups ---------------------------------------------------------

(deftest route-url-optional-group-elision
  (testing "a group is emitted only when every inner param is present"
    (rf/reg-route :route/articles {} "/articles{/:id/:slug}?")
    (are [params url] (= url (rf.routing/route-url {:to :route/articles :params params}))
      {:id "intro"}                 "/articles"
      {:id "intro" :slug "welcome"} "/articles/intro/welcome")))

;; `(some? "")` would enter the group and emit `/articles/`, which match-url's
;; trailing-slash normalisation reads back with the param ABSENT — so "" is a
;; hard error here exactly as on a top-level segment.
(deftest route-url-optional-group-empty-string-rejected
  (rf/reg-route :route/og {} "/items{/:flag}?")
  (testing "\"\" in an optional group is rejected"
    (is (= {:rf.error/id :rf.error/missing-route-param :value ""}
           (select-keys (thrown-data #(rf.routing/route-url {:to :route/og :params {:flag ""}}))
                        [:rf.error/id :value]))))
  (testing "false is a non-empty segment and still enters the group"
    (is (= "/items/false" (rf.routing/route-url {:to :route/og :params {:flag false}})))))

;; The group owns its leading slash, so eliding it never orphans a separator —
;; `//about` would be a protocol-relative URL that escapes the app.
(deftest route-url-optional-group-no-double-slash
  (rf/reg-route :route/inline-about {} "{/:base}?/about")
  (rf/reg-route :route/docs-about {} "/docs{/:section}?/about")
  (testing "an elided group leaves a single separator, leading or mid-path"
    (is (= "/about" (rf.routing/route-url {:to :route/inline-about})))
    (is (= "/docs/about" (rf.routing/route-url {:to :route/docs-about}))))
  (testing "the elided leading-group URL matches back with no params"
    (is (= {:route-id :route/inline-about :params {}}
           (select-keys (rf.routing/match-url "/about") [:route-id :params])))))

;; ============================================================================
;; match-url coerces PATH params against the :params schema before validating
;; them, with the real Malli validator. The :uuid round trip on both hosts is
;; routing_url_non_edn_cljs_test's uuid-path-mixed-case-coerces-host-symmetric.
;; ============================================================================

(deftest path-param-coercion-against-params-schema
  (testing "a typed path param coerces before validation; a capture its type
            cannot read stays the raw string, in its original case, which the
            schema rejects"
    (rf/reg-route :route/page    {:params [:map [:n :int]]}   "/page/:n")
    (rf/reg-route :route/article {:params [:map [:id :uuid]]} "/articles/:id")
    (are [url expected] (= expected (select-keys (rf.routing/match-url url)
                                                 [:route-id :params :validation-failed?]))
      "/page/42"             {:route-id :route/page :params {:n 42} :validation-failed? false}
      "/articles/NOT-A-UUID" {:route-id :route/article :params {:id "NOT-A-UUID"} :validation-failed? true})))

;; :int coercion is total on oversized integers and bounded to the cross-host
;; safe-integer range. The bound is host-neutral code, so this read pins both hosts.
(deftest int-coercion-oversized-host-parity-jvm
  (testing "2^53-1 coerces; anything past the safe-integer range on either side
            stays a string (a CLJS number would be lossy), and a literal past
            2^63 does not throw"
    (rf/reg-route :route/items {:query [:map [:page :int]]} "/items")
    (are [v expected] (= expected (get-in (rf.routing/match-url (str "/items?page=" v)) [:query :page]))
      "9007199254740991"        9007199254740991
      "9007199254740992"        "9007199254740992"
      "-9007199254740993"       "-9007199254740993"
      "99999999999999999999999" "99999999999999999999999")))

;; ============================================================================
;; Route lifecycle trace ops
;; ============================================================================

(deftest route-registered-trace-on-first-time-reg
  (testing ":rf.route/registered fires once, on first-time registration only;
            re-registration rides `:rf.registry/handler-replaced`"
    (let [traces (atom [])]
      (rf/register-listener! :trace ::reg-trace (fn [ev] (swap! traces conj ev)))
      (rf/reg-route :route/home {} "/")
      (rf/reg-route :route/home {} "/")
      (rf/unregister-listener! :trace ::reg-trace)
      (when rf.interop/debug-enabled?
        (is (= [{:route-id :route/home :path "/"}]
               (into []
                     (comp (filter #(= :rf.route/registered (:operation %)))
                           (map #(select-keys (:tags %) [:route-id :path])))
                     @traces)))))))

(deftest route-cleared-trace-on-unregister
  (testing "clearing a route removes it and emits :rf.route/cleared once; a
            second clear is a silent no-op"
    (rf/reg-route :route/transient {} "/transient")
    (let [traces (atom [])]
      (rf/register-listener! :trace ::cleared-trace (fn [ev] (swap! traces conj ev)))
      (rf/clear :route :route/transient)
      (rf/clear :route :route/transient)
      (rf/unregister-listener! :trace ::cleared-trace)
      (is (nil? (rf.routing/match-url "/transient")))
      (when rf.interop/debug-enabled?
        (is (= [{:route-id :route/transient}]
               (into []
                     (comp (filter #(= :rf.route/cleared (:operation %)))
                           (map #(select-keys (:tags %) [:route-id])))
                     @traces)))))))

;; ===========================================================================
;; match-against, called directly: a matcher regression that match-url's own
;; URL normalisation would mask still shows here.
;; ===========================================================================

(deftest match-against-captures-and-anchors-both-ends
  (testing "match-against returns the captured params for a URL its compiled
            pattern matches, anchored at both ends, and nil (no throw) otherwise"
    (are [pattern url expected]
         (= expected (rf.routing.match/match-against (rf.routing.match/parse-pattern pattern) url))
      "/foo/bar"                  "/foo/bar"          {}
      "/foo/bar"                  "/foo/baz"          nil
      "/foo/bar"                  "/foo/bar/extra"    nil
      "/users/:id"                "/users/42"         {:id "42"}
      "/users/:id"                "/users/"           nil
      "/files/*path"              "/files/a/b/c"      {:path "a/b/c"}
      "/files/*path"              "/files/"           nil
      "/"                         "/"                 {}
      "/"                         ""                  {}
      "/"                         "/foo"              nil
      "/users/:id/posts/:post-id" "/users/42/posts/9" {:id "42" :post-id "9"})))

;; ---- ranking ------------------------------------------------------------------

;; Spec 012 §Route ranking algorithm rule 2: the bare catch-all `/*` is demoted
;; below every other matching route; a NAMED splat is a rest param, not a catch-all.
(deftest match-url-named-splat-wins-over-bare-catch-all
  (rf/reg-route :route/catch-all {} "/*")
  (rf/reg-route :route/rest      {} "/*rest")
  (is (= {:route-id :route/rest :params {:rest "some/deep/path"}}
         (select-keys (rf.routing/match-url "/some/deep/path") [:route-id :params]))))

;; `/*` also matches "/". The catch-all demotion sits ahead of total-length in
;; the rank, so the root route wins "/" whatever the registration order.
(deftest match-url-root-wins-over-catch-all-rf2-1ugs5u
  (testing "/ resolves to the root route even with the catch-all registered
            first; the catch-all still takes every URL nothing else matches"
    (rf/reg-route :route/catch-all {} "/*")
    (rf/reg-route :route/home      {} "/")
    (is (= {:route-id :route/home :params {}}
           (select-keys (rf.routing/match-url "/") [:route-id :params])))
    (is (= :route/catch-all (:route-id (rf.routing/match-url "/anything/deep"))))))

;; Spec 012 rules 4 and 5 are boolean bits (has-splat?, has-optional?), not
;; counts: a count-based rank would order routes the spec ties.
(deftest parse-pattern-optional-group-count-does-not-affect-rank-rf2-dqlfty
  (let [rank #(:rank (rf.routing.match/parse-pattern %))]
    (is (= (rank "/docs{/a}?") (rank "/docs{/a}?{/b}?"))
        "one and two optional groups tie at rule 5")
    (is (pos? (compare (rank "/docs") (rank "/docs{/a}?")))
        "an exact route still out-ranks an optional-group route")))

(deftest parse-pattern-named-param-outranks-splat-rf2-dqlfty
  (is (pos? (compare (:rank (rf.routing.match/parse-pattern "/files/:name"))
                     (:rank (rf.routing.match/parse-pattern "/files/*rest"))))
      "rule 4: a named param out-ranks a splat"))

(deftest match-url-optional-group-count-tie-breaks-on-registration-order-rf2-dqlfty
  (testing "routes differing only in optional-group COUNT tie, so rule 6
            (registration order) decides. The route with MORE groups registers
            first: a count-based rank would let the other win regardless"
    (rf/reg-route :route/two-groups {} "/docs{/a}?{/b}?")
    (rf/reg-route :route/one-group  {} "/docs{/a}?")
    (is (= :route/two-groups (:route-id (rf.routing/match-url "/docs"))))))

;; ---- sequential optional groups form a prefix chain ------------------------
;;
;; match-url reads adjacent optional groups POSITIONALLY, so `/docs/5` is
;; `{:section "5"}`. Emitting `{:page "5"}` alone would build that same URL — a
;; silent prism break — so route-url refuses a later group whose earlier group
;; is absent, and every prefix-respecting combination round-trips.

(deftest sequential-optional-groups-prefix-rule-rf2-rpjb5i
  (rf/reg-route :route/docs
                {:params [:map
                          [:section {:optional true} :string]
                          [:page    {:optional true} :string]]}
                "/docs{/:section}?{/:page}?")
  (testing "supplying only the later param throws, naming the offending group"
    (is (= {:rf.error/id :rf.error/route-url-validation :group [:page]}
           (select-keys (thrown-data #(rf.routing.registry/route-url {:to :route/docs :params {:page "5"}}))
                        [:rf.error/id :group]))))
  (testing "match-url(route-url(x)) recovers x for every prefix-respecting x,
            an unmatched group's param ABSENT rather than nil"
    (doseq [params [{} {:section "a"} {:section "a" :page "b"}]]
      (let [url (rf.routing.registry/route-url {:to :route/docs :params params})]
        (is (= params (:params (rf.routing/match-url url)))
            (str "round trip via " url))))))

;; ---- reg-route authoring-boundary metadata validation ----------------------
;;
;; Spec 012 §Reserved route-metadata keys: a bare key outside the reserved set
;; is rejected loudly at registration (a typo like :on-matched would otherwise
;; fail at nav time, or never); namespaced host/app keys pass.

(deftest reg-route-rejects-unknown-bare-metadata-key
  (testing "a typo'd key, or the retired :on-error (Spec 012 §Route-not-found),
            throws :rf.error/route-bad-metadata naming exactly that key"
    (doseq [[k v] [[:on-matched [[:load]]]
                   [:on-error [:oops]]]]
      (is (= {:rf.error/id :rf.error/route-bad-metadata :keys [k]}
             (select-keys (thrown-data #(rf/reg-route :route/typo {k v} "/typo"))
                          [:rf.error/id :keys]))
          (pr-str k)))))

(deftest reg-route-refuses-a-single-event-on-match
  (testing ":on-match is a vector of event vectors; a single event vector is
            refused at registration rather than throwing a host error at the
            first navigation"
    (is (= {:rf.error/id :rf.error/route-bad-metadata :keys [:on-match] :value [:app/load]}
           (select-keys (thrown-data #(rf/reg-route :route/one-event {:on-match [:app/load]} "/one-event"))
                        [:rf.error/id :keys :value]))))
  (testing "a nil :on-match declares no events"
    (is (= :route/no-events (rf/reg-route :route/no-events {:on-match nil} "/no-events")))))

;; Spec 001 §Production elision contract: a programmatic registration carries
;; no captured source ns, so stamping a bare `:ns` is how it becomes
;; `:select-ns`-selectable. `rf/reg-route` here is the MACRO, which captures
;; THIS namespace — so the store reading "probe.ns" proves the bare key is
;; accepted AND that it overrides the captured ns.
(deftest reg-route-accepts-and-honours-the-ns-provenance-stamp
  (rf/reg-route :route/with-ns {:ns 'probe.ns} "/with-ns")
  (is (= ["probe.ns"] (vec (keys (rf.source-store/descriptors-for :route :route/with-ns))))))

(deftest reg-route-rejects-non-map-metadata
  (is (= :rf.error/route-bad-metadata
         (:rf.error/id (thrown-data #(rf/reg-route :route/bad "/not-a-map" "/bad"))))))

(deftest reg-route-rejects-path-inside-metadata-map
  (testing "the path pattern is the THIRD slot; a :path left inside the
            metadata map is rejected naming it, not silently overridden"
    (is (= {:rf.error/id :rf.error/route-bad-metadata :route-id :route/bad :keys [:path] :value "/bad"}
           (select-keys (thrown-data #(rf/reg-route :route/bad {:path "/bad"} "/ignored"))
                        [:rf.error/id :route-id :keys :value])))))

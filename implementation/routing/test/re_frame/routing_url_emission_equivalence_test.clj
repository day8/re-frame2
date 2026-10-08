(ns re-frame.routing-url-emission-equivalence-test
  "The teeth for `route-url`'s render-path specialisations.

  `route-link` synthesises its href per render, so `route-url` — and the
  strategy consult beside it — run once per link per render. Their cost is
  no single dominant term but several ordinary ones, each served by a
  cheaper route:

    the emission walk                 literal runs read in one `subs`, not a
                                      `(conj parts (str ch))` per literal
                                      character
    the address-key reject scan       a direct walk of the address's entries;
      + the empty-query sort          no sort without a query or a bad key
    `uncaptured-param-keys`           a membership test with no per-call
                                      keyword set
    the fail-closed URL-scalar guard  answered by TYPE for four kinds, with no
                                      CEDN-1 token string built and discarded
    ── and outside it ──
    the render-time strategy consult  `frame-config`, not a merged
                                      `frame-meta` map per link, to read one key

  Each is a SPECIALISATION, never a second implementation: the same
  answers by a cheaper route. This namespace is what makes that claim
  falsifiable. Every assertion below is one a divergence would break. Some
  rows are held elsewhere: the empty-query sort by `routing-registry-test`'s
  query-emission tests, a bare uncaptured key by
  `routing-uncaptured-param-test`, the host values the URL-scalar guard
  refuses and the falsy scalars it admits by `routing-url-non-edn-cljs-test`,
  the total order of bad address keys by `routing-boundary-totality-cljs-test`,
  and the strategy consult by `route-link-ssr-parity-cljs-test`'s href parity
  across strategy shapes.

  ## The mutations this file is proved against

  Each of these three turns it red:

  1. **`\\{` deleted from `literal-run-end`'s boundary set.** A literal run then
     swallows the opening brace, so an elided group raises
     `:rf.error/missing-route-param`. RED: an error in
     `emitted-paths-are-exactly-what-the-pattern-says`.
  2. **`\\:` deleted from the same set.** A run swallows the param sigil, so
     every pattern emits its own text where the value belongs
     (`/profile/:username` for `{:username \"jane\"}`). RED: failures across
     `emitted-paths-are-exactly-what-the-pattern-says` and
     `every-emitted-url-matches-back-to-the-address-it-came-from`.
  3. **The fast walk's optional-group bail turned into a skip** — `\\{` / `\\}`
     consumed instead of returning nil, so a group pattern never reaches the
     general loop. RED: `/docs{/:section}?` emits `/docs/api?`, an elided group
     raises `:rf.error/missing-route-param`, and `/docs{/:section}?{/:page}?`
     round-trips to the wrong route.

  Mutation 3 is also why the walk decides for itself, in a branch of its own
  loop, rather than being switched on by an `(empty? groups)` gate computed
  outside it. Written as a gate, the SAME mutation (dropping the gate) would not
  fail — it would HANG: `literal-run-end` stops at `{` and returns the cursor
  unmoved, so the loop would spin and the suite never complete. A walk whose every branch
  either advances the cursor or returns cannot do that, and it does not depend
  on a `:groups` map that a route-meta installed outside `reg-route` could
  disagree with.

  Nothing here is a benchmark. The table above names what the tests are
  guarding; the studio page carries the measurement."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- register-routes! []
  (rf.routing/reg-route :eq/root    {} "/")
  (rf.routing/reg-route :eq/plain   {} "/plain")
  (rf.routing/reg-route :eq/profile {} "/profile/:username")
  (rf.routing/reg-route :eq/pair    {} "/a/:x/b/:y/c")
  (rf.routing/reg-route :eq/only    {} "/:only")
  (rf.routing/reg-route :eq/files   {} "/files/*rest")
  (rf.routing/reg-route :eq/docs    {} "/docs{/:section}?")
  (rf.routing/reg-route :eq/chain   {} "/docs{/:section}?{/:page}?")
  (rf.routing/reg-route :eq/base    {} "{/:base}?"))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable ex (ex-data ex))))

;; ===========================================================================
;; The emitted path — the fast walk and the general one, side by side
;; ===========================================================================

(deftest emitted-paths-are-exactly-what-the-pattern-says
  (register-routes!)
  (doseq [[to params expected]
          [;; NO optional group — the specialised walk
           [:eq/root    nil                        "/"]
           [:eq/plain   nil                        "/plain"]
           [:eq/profile {:username "jane"}         "/profile/jane"]
           [:eq/pair    {:x "1" :y "2"}            "/a/1/b/2/c"]
           [:eq/only    {:only "solo"}             "/solo"]
           [:eq/files   {:rest "a/b/c.txt"}        "/files/a/b/c.txt"]   ; a splat keeps its separators
           [:eq/profile {:username "a/b"}          "/profile/a%2Fb"]     ; a named param does not
           [:eq/profile {:username "a&b?c"}        "/profile/a%26b%3Fc"] ; encoding is the param's, never the literal run's
           ;; WITH an optional group — the general walk
           [:eq/docs    {:section "api"}           "/docs/api"]
           [:eq/docs    {}                         "/docs"]
           [:eq/chain   {:section "api" :page "2"} "/docs/api/2"]
           [:eq/chain   {:section "api"}           "/docs/api"]
           [:eq/base    {:base "x"}                "/x"]
           [:eq/base    {}                         "/"]]]               ; an elided leading group normalises to the root
    (is (= expected (rf.routing.registry/route-url (cond-> {:to to} params (assoc :params params))))
        (str to " " (pr-str params)))))

(deftest every-emitted-url-matches-back-to-the-address-it-came-from
  (register-routes!)
  (testing "the prism holds over both walks — route-url then match-url"
    ;; `:eq/base` is absent: `/:only` also matches its `/x` and out-ranks it,
    ;; so its row would assert this fixture's ranking rather than the prism.
    (doseq [[to params] [[:eq/root    {}]
                         [:eq/plain   {}]
                         [:eq/profile {:username "a&b?c"}]
                         [:eq/pair    {:x "1" :y "2"}]
                         [:eq/only    {:only "solo"}]
                         [:eq/files   {:rest "a/b/c.txt"}]
                         [:eq/docs    {:section "api"}]
                         [:eq/chain   {:section "api" :page "2"}]]]
      (let [url (rf.routing.registry/route-url {:to to :params params})]
        (is (= [to params] ((juxt :route-id :params) (rf.routing.registry/match-url url)))
            (str "round trip of " url))))))

;; ===========================================================================
;; The fail-closed classes — every one of them closed
;; ===========================================================================

(deftest the-emission-guards-still-refuse-what-they-always-refused
  (register-routes!)

  (testing "an absent or empty path param, inside an optional group too"
    (doseq [[to params] [[:eq/profile {}]
                         [:eq/profile {:username ""}]
                         [:eq/docs    {:section ""}]]]
      (is (= :rf.error/missing-route-param
             (:rf.error/id (thrown-data #(rf.routing.registry/route-url {:to to :params params}))))
          (str to " " (pr-str params)))))

  (testing "the set-free membership test counts neither a NAMESPACED key nor a
            NON-keyword key as the bare capture of the same name"
    (is (= {:reason :uncaptured-params :keys [:a/username]}
           (select-keys (thrown-data #(rf.routing.registry/route-url
                                        {:to :eq/profile :params {:username "j" :a/username "x"}}))
                        [:reason :keys])))
    (is (= :uncaptured-params
           (:reason (thrown-data #(rf.routing.registry/route-url
                                    {:to :eq/profile :params {:username "j" "username" "x"}}))))))

  (testing "the reject scan refuses a non-address key"
    (is (= {:reason :bad-address-keys :keys [:replace?]}
           (select-keys (thrown-data #(rf.routing.registry/route-url {:to :eq/plain :replace? true}))
                        [:reason :keys]))))

  (testing "an integer outside the safe range takes the guard's slow leg and is refused"
    (is (= :rf.error/route-url-non-edn-value
           (:rf.error/id (thrown-data #(rf.routing.registry/route-url
                                         {:to :eq/profile :params {:username (inc 9007199254740991)}})))))))

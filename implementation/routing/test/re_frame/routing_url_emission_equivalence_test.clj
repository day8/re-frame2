(ns re-frame.routing-url-emission-equivalence-test
  "rf2-cno31 — the teeth for `route-url`'s render-path cheapening.

  `route-link` synthesises its href per render, and the rf2-6c237 clock
  re-take priced that term at 8.21 µs per link — 1.70 ms on the census
  acceptance page's 207 links, 7.4 ms on the feed's 900. Two diagnostic probes
  (`link_decomp_probe_app`, `link_inner_probe_app`) decomposed it and found no
  single dominant term but five ordinary ones, four of them inside `route-url`:

    the per-character emission walk   1.69 µs   (`(conj parts (str ch))` per
                                                 literal character, then
                                                 `apply str` over the result)
    the address-key reject scan
      + the empty-query sort          0.60 µs
    `uncaptured-param-keys`' per-call
      keyword set                     0.48 µs
    the fail-closed URL-scalar guard  0.36 µs   (a whole CEDN-1 token string
                                                 built and discarded)
    the route-meta lookup             0.12 µs
    ── and outside it ──
    the render-time strategy consult  0.72 µs   (a merged `frame-meta` map per
                                                 link, to read one key)

  Each remedy is a SPECIALISATION, never a second implementation: the same
  answers by a cheaper route. This namespace is what makes that claim
  falsifiable. Every assertion below is one a divergence would break.

  ## The mutations this file is proved against

  Three were run against the shipped code, each reverted after:

  1. **`\\{` deleted from `literal-run-end`'s boundary set.** A literal run then
     swallows the opening brace. RED: `literal-run-end-stops-at-every-sigil`,
     1 failure.
  2. **`\\:` deleted from the same set.** A run swallows the param sigil, so
     every pattern emits its own text where the value belongs
     (`/profile/:username` for `{:username \"jane\"}`). RED: 27 failures across
     `emitted-paths-are-exactly-what-the-pattern-says` and
     `every-emitted-url-matches-back-to-the-address-it-came-from`.
  3. **The fast walk's optional-group bail turned into a skip** — `\\{` / `\\}`
     consumed instead of returning nil, so a group pattern never reaches the
     general loop. RED: 10 failures — `/docs{/:section}?` emits `/docs/api?`,
     an elided group raises `:rf.error/missing-route-param`, and
     `/docs{/:section}?{/:page}?` round-trips to the wrong route.

  Mutation 3 is also why the walk decides for itself, in a branch of its own
  loop, rather than being switched on by an `(empty? groups)` gate computed
  outside it. Written as a gate, the SAME mutation (dropping the gate) does not
  fail — it HANGS: `literal-run-end` stops at `{` and returns the cursor
  unmoved, so the loop spins and the suite never completes. It was run that way
  first and had to be killed by hand at 722 s of CPU. A walk whose every branch
  either advances the cursor or returns cannot do that, and it does not depend
  on a `:groups` map that a route-meta installed outside `reg-route` could
  disagree with.

  Nothing here is a benchmark. The figures above name what the tests are
  guarding; the studio page carries the measurement."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.routing :as routing]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.strategy :as strategy]
            [re-frame.routing-test-support :as rts]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each rts/reset-runtime)

(defn- register-routes! []
  (routing/reg-route :eq/root    {} "/")
  (routing/reg-route :eq/plain   {} "/plain")
  (routing/reg-route :eq/profile {} "/profile/:username")
  (routing/reg-route :eq/pair    {} "/a/:x/b/:y/c")
  (routing/reg-route :eq/only    {} "/:only")
  (routing/reg-route :eq/files   {} "/files/*rest")
  (routing/reg-route :eq/docs    {} "/docs{/:section}?")
  (routing/reg-route :eq/chain   {} "/docs{/:section}?{/:page}?")
  (routing/reg-route :eq/base    {} "{/:base}?")
  (routing/reg-route :eq/sorted  {:query [:map [:sort {:optional true} :string]
                                          [:page {:optional true} :string]]}
                     "/search")
  (routing/reg-route :eq/enum    {:params [:map [:dir [:enum :asc :desc]]]}
                     "/by/:dir"))

(defn- thrown-data [f]
  (try (f) nil (catch Throwable ex (ex-data ex))))

;; ===========================================================================
;; The emitted path — the fast walk and the general one, side by side
;; ===========================================================================

(deftest emitted-paths-are-exactly-what-the-pattern-says
  (register-routes!)

  (testing "patterns with NO optional group — the specialised walk"
    (is (= "/"                (registry/route-url {:to :eq/root})))
    (is (= "/plain"           (registry/route-url {:to :eq/plain})))
    (is (= "/plain"           (registry/route-url {:to :eq/plain :params {}})))
    (is (= "/profile/jane"    (registry/route-url {:to :eq/profile :params {:username "jane"}})))
    (is (= "/a/1/b/2/c"       (registry/route-url {:to :eq/pair :params {:x "1" :y "2"}})))
    (is (= "/solo"            (registry/route-url {:to :eq/only :params {:only "solo"}})))
    (testing "a splat keeps its literal separators; a named param does not"
      (is (= "/files/a/b/c.txt" (registry/route-url {:to :eq/files :params {:rest "a/b/c.txt"}})))
      (is (= "/profile/a%2Fb"   (registry/route-url {:to :eq/profile :params {:username "a/b"}}))))
    (testing "percent-encoding is the param's, never the literal run's"
      (is (= "/profile/a%20b"   (registry/route-url {:to :eq/profile :params {:username "a b"}})))
      (is (= "/profile/a%26b%3Fc"
             (registry/route-url {:to :eq/profile :params {:username "a&b?c"}}))))
    (testing "falsy-but-present path values round-trip"
      (is (= "/profile/false" (registry/route-url {:to :eq/profile :params {:username false}})))
      (is (= "/profile/0"     (registry/route-url {:to :eq/profile :params {:username 0}})))))

  (testing "patterns WITH an optional group — the general walk, untouched"
    (is (= "/docs/api" (registry/route-url {:to :eq/docs :params {:section "api"}})))
    (is (= "/docs"     (registry/route-url {:to :eq/docs :params {}})))
    (is (= "/docs"     (registry/route-url {:to :eq/docs})))
    (is (= "/docs/api/2" (registry/route-url {:to :eq/chain :params {:section "api" :page "2"}})))
    (is (= "/docs/api"   (registry/route-url {:to :eq/chain :params {:section "api"}})))
    (is (= "/docs"       (registry/route-url {:to :eq/chain :params {}})))
    (testing "the whole path is one leading optional group"
      (is (= "/x" (registry/route-url {:to :eq/base :params {:base "x"}})))
      (is (= "/"  (registry/route-url {:to :eq/base :params {}}))
          "an elided leading group normalises the empty string to the root"))))

(deftest every-emitted-url-matches-back-to-the-address-it-came-from
  (register-routes!)
  (testing "the prism holds over both walks — route-url then match-url"
    (doseq [[to params] [[:eq/root    {}]
                         [:eq/plain   {}]
                         [:eq/profile {:username "jane"}]
                         [:eq/profile {:username "a b"}]
                         [:eq/profile {:username "a&b?c"}]
                         [:eq/pair    {:x "1" :y "2"}]
                         [:eq/only    {:only "solo"}]
                         [:eq/files   {:rest "a/b/c.txt"}]
                         [:eq/docs    {:section "api"}]
                         [:eq/chain   {:section "api" :page "2"}]]]
      ;; `:eq/base` is deliberately absent: its `{/:base}?` emits `/x`, which
      ;; `/:only` also matches and out-ranks, so a round trip through the whole
      ;; table would be asserting this fixture's ranking rather than the prism.
      ;; Its emission is asserted above, where ranking cannot reach it.
      (let [url (registry/route-url {:to to :params params})
            m   (registry/match-url url)]
        (is (= to (:route-id m)) (str "round trip of " url))
        (is (= params (:params m)) (str "round trip of " url))))))

;; ===========================================================================
;; The boundary scanner the fast walk is built on
;; ===========================================================================

(deftest literal-run-end-stops-at-every-sigil
  (let [run-end #'registry/literal-run-end
        end     (fn [s i] (run-end s (count s) i))]
    (testing "a run ends at the next : * { or }, and nowhere else"
      (is (= 9  (end "/profile/:username" 0)) ":")
      (is (= 7  (end "/files/*rest" 0))       "*")
      (is (= 5  (end "/docs{/:section}?" 0))  "{")
      (is (= 9  (end "/docs{/:x}?" 9))        "}")
      (is (= 6  (end "/plain" 0))             "no sigil — the whole pattern")
      (is (= 5  (end "/x/:y" 5))              "an index at the end returns itself")
      (is (= 0  (end ":only" 0))              "a sigil AT the cursor is a zero-length run"))))

;; ===========================================================================
;; The fail-closed classes — every one of them still closed
;; ===========================================================================

(deftest the-emission-guards-still-refuse-what-they-always-refused
  (register-routes!)

  (testing "an absent or empty path param"
    (is (= :rf.error/missing-route-param
           (:rf.error/id (thrown-data #(registry/route-url {:to :eq/profile :params {}})))))
    (is (= :rf.error/missing-route-param
           (:rf.error/id (thrown-data #(registry/route-url {:to :eq/profile :params {:username nil}})))))
    (is (= :rf.error/missing-route-param
           (:rf.error/id (thrown-data #(registry/route-url {:to :eq/profile :params {:username ""}})))))
    (testing "inside an optional group too"
      (is (= :rf.error/missing-route-param
             (:rf.error/id (thrown-data #(registry/route-url {:to :eq/docs :params {:section ""}})))))))

  (testing "a param the pattern does not capture — the set-free membership test"
    (let [bare (thrown-data #(registry/route-url {:to :eq/profile
                                                  :params {:username "j" :extra "x"}}))]
      (is (= :rf.error/route-url-validation (:rf.error/id bare)))
      (is (= :uncaptured-params (:reason bare)))
      (is (= [:extra] (:keys bare))))
    (testing "a NAMESPACED key is not the bare capture of the same name"
      (let [ns-keyed (thrown-data #(registry/route-url {:to :eq/profile
                                                        :params {:username "j"
                                                                 :a/username "x"}}))]
        (is (= :uncaptured-params (:reason ns-keyed)))
        (is (= [:a/username] (:keys ns-keyed))
            "`(name :a/username)` is \"username\" — it must NOT count as captured")))
    (testing "a NON-keyword key was never a capture either"
      (is (= :uncaptured-params
             (:reason (thrown-data #(registry/route-url {:to :eq/profile
                                                         :params {:username "j"
                                                                  "username" "x"}})))))))

  (testing "a non-address key — the reject scan"
    (let [d (thrown-data #(registry/route-url {:to :eq/plain :replace? true}))]
      (is (= :bad-address-keys (:reason d)))
      (is (= [:replace?] (:keys d))))
    (testing "bad keys are still reported in TOTAL canonical order"
      (is (= [:replace? "url"]
             (:keys (thrown-data #(registry/route-url {:to :eq/plain
                                                       :replace? true
                                                       "url" "/x"}))))
          "a heterogeneous key set must not reach a compare-based sort")))

  (testing "a value outside the URL-scalar domain — the guard's slow leg"
    (is (= :rf.error/route-url-non-edn-value
           (:rf.error/id (thrown-data #(registry/route-url
                                         {:to :eq/profile :params {:username (fn [])}})))))
    (is (= :rf.error/route-url-non-edn-value
           (:rf.error/id (thrown-data #(registry/route-url
                                         {:to :eq/profile
                                          :params {:username (java.util.Date.)}}))))
        "a host instant is a portable identity but not a URL segment")
    (is (= :rf.error/route-url-non-edn-value
           (:rf.error/id (thrown-data #(registry/route-url
                                         {:to :eq/profile :params {:username 1.5}}))))
        "a non-integer number has no canonical EDN identity")
    (is (= :rf.error/route-url-non-edn-value
           (:rf.error/id (thrown-data #(registry/route-url
                                         {:to :eq/profile
                                          :params {:username (inc 9007199254740991)}}))))
        "an integer outside the safe range is rejected on the slow leg"))

  (testing "the four kinds the guard now answers by type still EMIT"
    (is (= "/profile/jane"  (registry/route-url {:to :eq/profile :params {:username "jane"}})))
    (is (= "/profile/%3Ajane"
           (registry/route-url {:to :eq/profile :params {:username :jane}}))
        "an UNDECLARED keyword value host-stringifies, exactly as before")
    (is (= "/profile/jane"  (registry/route-url {:to :eq/profile :params {:username 'jane}})))
    (is (= "/profile/true"  (registry/route-url {:to :eq/profile :params {:username true}}))))

  (testing "a DECLARED keyword enum still emits its token, not %3A"
    (is (= "/by/desc" (registry/route-url {:to :eq/enum :params {:dir :desc}})))))

;; ===========================================================================
;; The query side — the empty short-circuit, and the sorted path it skips
;; ===========================================================================

(deftest the-query-string-is-what-it-was
  (register-routes!)
  (testing "no query — the short-circuit"
    (is (= "/search" (registry/route-url {:to :eq/sorted})))
    (is (= "/search" (registry/route-url {:to :eq/sorted :query {}})))
    (is (= "/search" (registry/route-url {:to :eq/sorted :query {:sort nil}}))
        "a nil-valued key is elided, not emitted as a bare key"))
  (testing "a query — the canonical-order sort, untouched"
    (is (= "/search?sort=asc" (registry/route-url {:to :eq/sorted :query {:sort "asc"}})))
    (is (= "/search?page=2&sort=asc"
           (registry/route-url {:to :eq/sorted :query {:sort "asc" :page "2"}}))
        "keys emit in canonical order, not insertion order")
    (is (= "/search?page=2&sort=asc"
           (registry/route-url {:to :eq/sorted :query (array-map :page "2" :sort "asc")}))
        "and the SAME URL whichever order the caller spelled them in")))

;; ===========================================================================
;; The render-time strategy consult
;; ===========================================================================

(deftest frame-config-answers-the-strategy-question-frame-meta-answered
  (testing "the narrowed read is the same read for every frame shape"
    (rf/init! plain-atom/adapter)
    (try
      (frame/upsert-frame! :eq/hash {:url-bound? true
                                     :url-strategy strategy/hash-url-strategy})
      (frame/upsert-frame! :eq/plainframe {:url-bound? true})
      (doseq [id [:eq/hash :eq/plainframe :eq/never-made]]
        (is (= (:url-strategy (frame/frame-meta id))
               (:url-strategy (frame/frame-config id)))
            (str "frame-config and frame-meta must agree on :url-strategy for " id))
        (is (identical? (strategy/url-strategy-from-config (frame/frame-meta id))
                        (strategy/url-strategy-for-frame-id id))
            (str "the consult must resolve what frame-meta's config would, for " id)))
      (is (identical? strategy/hash-url-strategy
                      (strategy/url-strategy-for-frame-id :eq/hash)))
      (is (identical? strategy/history-url-strategy
                      (strategy/url-strategy-for-frame-id :eq/plainframe)))
      (is (identical? strategy/history-url-strategy
                      (strategy/url-strategy-for-frame-id nil)))
      (testing "and frame-config does not leak the lifecycle keys frame-meta merges"
        (is (contains? (frame/frame-meta :eq/hash) :created-at))
        (is (not (contains? (frame/frame-config :eq/hash) :created-at))))
      (finally
        (frame/destroy-frame! :eq/hash)
        (frame/destroy-frame! :eq/plainframe)))))

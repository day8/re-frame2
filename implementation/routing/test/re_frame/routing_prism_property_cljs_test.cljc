(ns re-frame.routing-prism-property-cljs-test
  "Generative property test for the route PRISM — the third leg of the
  EP-0012 `:rf/path` algebra (EP-0012 §Validation/Conformance §Route prism
  conformance, rf2-86amg8). The EP's named conformance item is:

    'For each registered route, GENERATE valid path params/query params
     from the route schemas and assert match-url(route-url(...)) returns
     canonical route data.'

  The FOUNDATION surfaces (path / identity) carry seeded-PRNG property
  tests (`path-laws-cljs-test`, `identity-cedn1-cljs-test`); the prism was
  example-checked only (the routing_registry_test hand-picked cases). This
  closes that gap with a generative sweep over schema-conforming inputs.

  ## Properties asserted (over deterministic draws)

    1. ROUND-TRIP — for a registered route, drawing valid path params +
       a query map, `match-url(route-url(route-id, params, query))` returns
       `{:route-id :params :query :fragment :validation-failed? false}` with
       `:params` and `:query` recovering the drawn values (modulo the prism's
       documented string-coercion of URL captures).
    2. CANONICAL QUERY KEY ORDER — both prism legs share ONE canonical
       order (Conventions §Routes are prisms / rf2-wgutc2 / rf2-t3cfil):
       `route-url` emits query keys in CEDN-1 canonical order and `match-url`
       returns them in CEDN-1 canonical order, so two inbound spellings of
       one query map yield `=` :query with identical key ORDER. Pinned over
       the generated draws, not just the example cases.
    3. `:query-defaults` DO NOT BREAK THE PRISM (rf2-kqxe6.23). A route's
       declared defaults live in the resolved TARGET and never in the URL:
       `route-url` omits a key already at its default and `match-url` fills it
       back, so the URL `route-url` emits is a FIXED POINT of
       `route-url ∘ match-url` and the recovered `:query` is the drawn query
       with the defaults filled. Drawn over a route that declares defaults —
       properties 1-2 never drew one, which is precisely why the generator
       could not find the door-parity split that shipped with them.

  ## Why a hand-rolled seeded PRNG (not clojure.test.check / Malli gen)

  Mirrors the foundation tests' `lcg-next` / `gen-*` approach: a 32-bit
  linear-congruential generator drawing the SAME value stream on CLJ and
  CLJS, so the property runs identically on both hosts with no test.check /
  Malli-generator dependency on the routing test classpath. Deterministic +
  reproducible: the seed is fixed, so a failure is a stable repro.

  Named `*-cljs-test.cljc` so BOTH the cognitect JVM runner (`.*-test$`) and
  the shadow-cljs `:node-test` build (`cljs-test$`) discover it — the prism
  round-trip is exercised on both hosts (the cross-host conformance the EP
  asks for)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.identity :as rf.identity]
   [re-frame.routing :as rf.routing]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn rf.routing/reset-counters!}))

;; ---- a deterministic, host-portable PRNG ---------------------------------
;;
;; The SAME 32-bit linear-congruential generator the foundation tests use
;; (`path-laws-cljs-test`), so the draw stream is byte-identical on CLJ and
;; CLJS. Numerical-Recipes constants; every op stays in the int32 range.

(defn- lcg-next [state]
  (-> (unchecked-multiply (long state) 1664525)
      (unchecked-add 1013904223)
      (bit-and 0x7fffffff)))

(defn- rnd [state n] (mod (lcg-next state) n))

;; ---- generators ----------------------------------------------------------
;;
;; The prism round-trips STRINGS through the URL: a path capture and an
;; undeclared query value are both surfaced as strings by `match-url` (the
;; route declares no coercion vocabulary here, so the keyword-discipline
;; rule keeps query keys as strings too — rf2-5ifai). So the generators draw
;; URL-safe NON-EMPTY token strings:
;;
;;   - non-empty (an empty path param is rejected on emission — rf2-ede1h.2;
;;     an empty query value is legal but kept simple here);
;;   - no `/` (the path separator) and no `%`, `?`, `#`, `&`, `=` raw — the
;;     prism percent-encodes/decodes these symmetrically, but keeping the
;;     drawn tokens out of the reserved set keeps the property about the
;;     CANONICAL-ORDER + round-trip contract, not about the (separately
;;     example-tested) percent-encoding of reserved characters.

(def ^:private token-chars
  ;; A pool of URL-safe characters plus a SPACE (which the prism encodes to
  ;; %20 and decodes back) so the draws exercise value encoding too.
  (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ "))

(defn- gen-token
  "Generate a non-empty URL-safe token string of 1..6 chars. Returns
  `[token next-state]`. Trims to guarantee the token is non-blank and has
  no leading/trailing space (a trailing space round-trips fine, but a
  bare space-only token would be blank)."
  [state]
  (let [len (inc (rnd state 6))]
    (loop [i 0, s (lcg-next state), acc []]
      (if (= i len)
        (let [t (str/trim (apply str acc))]
          ;; guarantee non-blank: a trimmed-to-empty draw falls back to "x"
          [(if (empty? t) "x" t) s])
        (recur (inc i) (lcg-next s)
               (conj acc (nth token-chars (rnd s (count token-chars)))))))))

(defn- gen-query
  "Generate a query map of 0..3 string-keyed string-valued pairs. Returns
  `[query-map next-state]`. Keys are distinct URL-safe tokens; values are
  URL-safe tokens. An empty map (no query) is a legal draw."
  [state]
  (let [n (rnd state 4)]
    (loop [i 0, s (lcg-next state), acc {}]
      (if (= i n)
        [acc s]
        (let [[k s1] (gen-token s)
              [v s2] (gen-token s1)]
          ;; distinct keys only — a repeated key would collapse, changing the
          ;; expected membership; redraw-skip by keying on the generated k.
          (recur (inc i) s2 (assoc acc k v)))))))

(defn- canonical-key-order
  "The CEDN-1 canonical order of `ks` — the order BOTH prism legs use,
  computed via the shared identity rule the implementation sorts by."
  [ks]
  (vec (sort-by rf.identity/canonical-bytes ks)))

;; ---- the property --------------------------------------------------------

(deftest prism-round-trips-over-generated-params-and-query
  (testing "match-url ∘ route-url round-trips over schema-conforming draws,
            with :query in CEDN-1 canonical key order on BOTH legs"
    ;; A route with two string path captures and an undeclared (string-keyed)
    ;; query vocabulary — the prism surfaces both as strings.
    (rf/reg-route :route/item {} "/items/:a/:b")
    (let [failure
          (loop [i 0, s 24680]
            (if (= i 300)
              nil
              (let [[a  s1] (gen-token s)
                    [b  s2] (gen-token s1)
                    [q  s3] (gen-query s2)
                    params {:a a :b b}
                    url    (rf.routing/route-url {:to :route/item :params params :query q})
                    m      (rf.routing/match-url url)]
                (cond
                  ;; (1) the built URL must be matchable
                  (nil? m)
                  [:no-match params q url]

                  (not= :route/item (:route-id m))
                  [:wrong-route params q url m]

                  ;; (2) path params round-trip (strings in, strings out)
                  (not= params (:params m))
                  [:params params q url (:params m)]

                  ;; (3) query membership + values round-trip
                  (not= q (:query m))
                  [:query params q url (:query m)]

                  ;; (4) query key ORDER is CEDN-1 canonical on the inbound leg
                  (not= (canonical-key-order (keys q))
                        (vec (keys (:query m))))
                  [:key-order params q url (vec (keys (:query m)))]

                  ;; (5) a conforming draw never trips validation
                  (true? (:validation-failed? m))
                  [:validation-failed params q url]

                  ;; (6) no fragment was emitted (none supplied)
                  (some? (:fragment m))
                  [:spurious-fragment params q url (:fragment m)]

                  :else
                  (recur (inc i) (lcg-next s3))))))]
      (is (nil? failure)
          (str "prism round-trip property failed: " (pr-str failure))))))

;; ---- property 3: a `:query-defaults` route still round-trips ---------------
;;
;; The corpus combined `:query-defaults` with nothing: no suite drew a route
;; declaring them, so the prism's own generative sweep could not see that
;; `route-url` emitted a defaulted key the named-address doors never resolved.
;; The draw below inhabits the shape. `:page` is the declared default; the
;; drawn query's keys are undeclared STRING keys, so they can never collide
;; with it (the keyword-discipline rule keeps undeclared URL keys as strings).

(def ^:private default-page "1")

(defn- gen-page-variant
  "Draw one of the three interesting spellings of a defaulted query key:
  ABSENT (the URL says nothing), AT-DEFAULT (the caller spells the declared
  default explicitly), or OFF-DEFAULT (an ordinary non-default value). Returns
  `[query-fragment next-state]`."
  [state]
  (case (rnd state 3)
    0 [{} (lcg-next state)]
    1 [{:page default-page} (lcg-next state)]
    2 (let [[v s] (gen-token state)]
        ;; keep it genuinely off-default
        [{:page (if (= default-page v) (str v "x") v)} s])))

(deftest prism-round-trips-over-a-query-defaults-route
  (testing "for a route declaring :query-defaults, the emitted URL is a FIXED
            POINT of route-url ∘ match-url and the recovered :query is the drawn
            query with the defaults filled — the declared default lives in the
            target, never in the URL"
    (rf/reg-route :route/dflt {:query-defaults {:page default-page}} "/dflt")
    (let [failure
          (loop [i 0, s 97531]
            (if (= i 300)
              nil
              (let [[q  s1] (gen-query s)
                    [pg s2] (gen-page-variant s1)
                    query   (merge q pg)
                    url     (rf.routing/route-url {:to :route/dflt :query query})
                    m       (rf.routing/match-url url)
                    ;; the target the URL leg recovers: the drawn query with
                    ;; every absent declared default filled in.
                    expected (merge {:page default-page} query)
                    ;; the URL that target derives — the fixed-point leg.
                    url'     (rf.routing/route-url {:to    :route/dflt
                                                 :query (:query m)})]
                (cond
                  (nil? m)
                  [:no-match query url]

                  ;; (1) the recovered target is the drawn query + the defaults
                  (not= expected (:query m))
                  [:query query url (:query m) expected]

                  ;; (2) the URL never spells a key at its declared default —
                  ;; if it did, the same destination would derive two different
                  ;; URLs depending on whether the address spelled the key.
                  (and (= default-page (:page query))
                       (str/includes? url "page="))
                  [:default-spelled-in-url query url]

                  ;; (3) FIXED POINT: route-url ∘ match-url is the identity on
                  ;; every URL route-url itself emits.
                  (not= url url')
                  [:not-a-fixed-point query url url']

                  ;; (4) canonical key order still holds on the inbound leg,
                  ;; defaults interleaved into the SAME order
                  (not= (canonical-key-order (keys expected))
                        (vec (keys (:query m))))
                  [:key-order query url (vec (keys (:query m)))]

                  (true? (:validation-failed? m))
                  [:validation-failed query url]

                  :else
                  (recur (inc i) (lcg-next s2))))))]
      (is (nil? failure)
          (str "query-defaults prism property failed: " (pr-str failure))))))

(deftest prism-query-order-is-spelling-independent
  (testing "two inbound URLs spelling one generated query in DIFFERENT key
            orders yield = :query with identical (canonical) key order —
            both prism legs share ONE canonical order"
    (rf/reg-route :route/list {} "/list")
    (let [failure
          (loop [i 0, s 13579]
            (if (= i 200)
              nil
              (let [[q s1] (gen-query s)]
                (if (< (count q) 2)
                  ;; need >= 2 keys to have a non-trivial reordering
                  (recur (inc i) (lcg-next s1))
                  (let [ks       (vec (keys q))
                        ;; build the query string in caller-insertion order
                        ;; and in REVERSED order; route-url must emit BOTH as
                        ;; the byte-identical canonical-order URL.
                        url-fwd  (rf.routing/route-url {:to :route/list :params {} :query (into {} (map (fn [k] [k (get q k)]) ks))})
                        url-rev  (rf.routing/route-url {:to :route/list :params {} :query (into {} (map (fn [k] [k (get q k)]) (reverse ks)))})
                        m-fwd    (rf.routing/match-url url-fwd)
                        m-rev    (rf.routing/match-url url-rev)
                        expected (canonical-key-order ks)]
                    (cond
                      (not= url-fwd url-rev)
                      [:route-url-not-canonical q url-fwd url-rev]

                      (not= (:query m-fwd) (:query m-rev))
                      [:match-url-query-differs q (:query m-fwd) (:query m-rev)]

                      (not= expected (vec (keys (:query m-fwd))))
                      [:match-url-key-order q expected (vec (keys (:query m-fwd)))]

                      :else
                      (recur (inc i) (lcg-next s1))))))))]
      (is (nil? failure)
          (str "prism canonical-order property failed: " (pr-str failure))))))

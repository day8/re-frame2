(ns re-frame.routing-prism-property-cljs-test
  "Generative property tests for the route PRISM. EP-0012 §Validation /
  Conformance §Route prism conformance:

    'For each registered route, GENERATE valid path params/query params
     from the route schemas and assert match-url(route-url(...)) returns
     canonical route data.'

  Properties, over deterministic draws:

    1. ROUND TRIP — `match-url(route-url(address))` recovers the drawn path
       params and query, with `:query` in CEDN-1 canonical key order
       (Conventions §Routes are prisms: both legs share ONE order).
    2. `:query-defaults` DO NOT BREAK THE PRISM — a key at its declared default
       is never spelled in the URL, `match-url` fills it back, and the emitted
       URL is a FIXED POINT of `route-url ∘ match-url`.

  Fixed examples then pin the order either side of the 9th-entry array-map
  promotion, which the draws reach only by chance, and the one pairing where
  UTF-8 byte order and UTF-16 order disagree.

  A hand-rolled 32-bit LCG (as in `path-laws-cljs-test`) draws the SAME stream
  on CLJ and CLJS with no test.check dependency, and the fixed seed makes a
  failure a stable repro. Named `*-cljs-test.cljc` so the JVM runner and the
  `:node-test` build both run it."
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
;; Numerical-Recipes constants; every op stays in the int32 range, so the draw
;; stream is identical on CLJ and CLJS.

(defn- lcg-next [state]
  (-> (unchecked-multiply (long state) 1664525)
      (unchecked-add 1013904223)
      (bit-and 0x7fffffff)))

(defn- rnd [state n] (mod (lcg-next state) n))

;; ---- generators ----------------------------------------------------------
;;
;; The routes below declare no coercion vocabulary, so `match-url` surfaces
;; path captures and query keys and values as strings, and the draws are
;; non-empty strings. A space exercises value encoding; the reserved
;; characters are left to the example-based encoding tests.

(def ^:private token-chars
  (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_ "))

(defn- gen-token
  "A non-blank token of 1..6 chars, trimmed. Returns `[token next-state]`."
  [state]
  (let [len (inc (rnd state 6))]
    (loop [i 0, s (lcg-next state), acc []]
      (if (= i len)
        (let [t (str/trim (apply str acc))]
          [(if (empty? t) "x" t) s])
        (recur (inc i) (lcg-next s)
               (conj acc (nth token-chars (rnd s (count token-chars)))))))))

(defn- gen-query
  "A string-keyed query map of 0..12 pairs — enough to cross the 9th-key
  array-map promotion, where the canonical order could be lost. Returns
  `[query-map next-state]`."
  [state]
  (let [n (rnd state 13)]
    (loop [i 0, s (lcg-next state), acc {}]
      (if (= i n)
        [acc s]
        (let [[k s1] (gen-token s)
              [v s2] (gen-token s1)]
          (recur (inc i) s2 (assoc acc k v)))))))

(defn- canonical-key-order
  "The CEDN-1 canonical order of `ks`, by the shared identity rule."
  [ks]
  (vec (sort-by rf.identity/canonical-bytes rf.identity/compare-canonical-bytes ks)))

;; ---- property 1: round trip ------------------------------------------------

(deftest prism-round-trips-over-generated-params-and-query
  (rf/reg-route :route/item {} "/items/:a/:b")
  (let [failure
        (loop [i 0, s 24680]
          (when (< i 300)
            (let [[a s1] (gen-token s)
                  [b s2] (gen-token s1)
                  [q s3] (gen-query s2)
                  params {:a a :b b}
                  url    (rf.routing/route-url {:to :route/item :params params :query q})
                  m      (rf.routing/match-url url)]
              (cond
                (not= {:route-id :route/item :params params :query q
                       :fragment nil :validation-failed? false}
                      m)
                [:round-trip params q url m]

                (not= (canonical-key-order (keys q)) (vec (keys (:query m))))
                [:key-order params q url (vec (keys (:query m)))]

                :else
                (recur (inc i) (lcg-next s3))))))]
    (is (nil? failure)
        (str "prism round-trip property failed: " (pr-str failure)))))

;; ---- property 2: a `:query-defaults` route round-trips ---------------------
;;
;; `:page` is the declared default; the drawn keys are undeclared STRING keys,
;; so they never collide with it.

(def ^:private default-page "1")

(defn- gen-page-variant
  "One of the three spellings of a defaulted key: ABSENT, AT-DEFAULT, or
  OFF-DEFAULT. Returns `[query-fragment next-state]`."
  [state]
  (case (rnd state 3)
    0 [{} (lcg-next state)]
    1 [{:page default-page} (lcg-next state)]
    2 (let [[v s] (gen-token state)]
        [{:page (if (= default-page v) (str v "x") v)} s])))

(deftest prism-round-trips-over-a-query-defaults-route
  (rf/reg-route :route/dflt {:query-defaults {:page default-page}} "/dflt")
  (let [failure
        (loop [i 0, s 97531]
          (when (< i 300)
            (let [[q  s1]  (gen-query s)
                  [pg s2]  (gen-page-variant s1)
                  query    (merge q pg)
                  url      (rf.routing/route-url {:to :route/dflt :query query})
                  m        (rf.routing/match-url url)
                  expected (merge {:page default-page} query)]
              (cond
                (not= expected (:query m))
                [:query query url (:query m) expected]

                ;; spelling a key at its default would give one destination two URLs
                (and (= default-page (:page query)) (str/includes? url "page="))
                [:default-spelled-in-url query url]

                (not= url (rf.routing/route-url {:to :route/dflt :query (:query m)}))
                [:not-a-fixed-point query url]

                (not= (canonical-key-order (keys expected)) (vec (keys (:query m))))
                [:key-order query url (vec (keys (:query m)))]

                :else
                (recur (inc i) (lcg-next s2))))))]
    (is (nil? failure)
        (str "query-defaults prism property failed: " (pr-str failure)))))

;; ---- fixed examples at the array-map promotion boundary ------------------
;;
;; 8 keys is the largest `PersistentArrayMap`; the 9th entry promotes. Every
;; input below is in a SCRAMBLED order, so no row passes by accident of
;; construction order.

(def ^:private wide-query
  (array-map :a "v1" :b "v2" :c "v3" :d "v4" :e "v5" :f "v6" :g "v7" :h "v8" :i "v9"))

(defn- scrambled-query
  "An `array-map` over `ks` in the given order — `apply array-map`, because
  `into` would promote at the 9th entry. `:page` draws the declared default."
  [ks]
  (apply array-map (mapcat (fn [k] [k (get wide-query k default-page)]) ks)))

(deftest route-url-and-match-url-keep-canonical-order-past-eight-keys
  (rf/reg-route :route/wide {} "/wide")
  (testing "route-url emits canonical order at 8 keys and at 9, past the promotion"
    (is (= "/wide?a=v1&b=v2&c=v3&d=v4&e=v5&f=v6&g=v7&h=v8"
           (rf.routing/route-url {:to :route/wide :query (scrambled-query [:g :c :a :h :e :b :f :d])})))
    (is (= "/wide?a=v1&b=v2&c=v3&d=v4&e=v5&f=v6&g=v7&h=v8&i=v9"
           (rf.routing/route-url {:to :route/wide :query (scrambled-query [:i :d :a :g :c :h :b :f :e])}))))
  (testing "match-url returns a scrambled 9-key query in that same order"
    (is (= (map (fn [k] [(name k) (wide-query k)]) [:a :b :c :d :e :f :g :h :i])
           (seq (:query (rf.routing/match-url "/wide?i=v9&d=v4&a=v1&g=v7&c=v3&h=v8&b=v2&f=v6&e=v5"))))))
  (testing "a :query-defaults route keeps the order through the outbound rebuild
            that drops the key at its default"
    (rf/reg-route :route/wide-dflt {:query-defaults {:page default-page}} "/wide-dflt")
    (is (= "/wide-dflt?a=v1&b=v2&c=v3&d=v4&e=v5&f=v6&g=v7&h=v8&i=v9"
           (rf.routing/route-url {:to    :route/wide-dflt
                                  :query (scrambled-query [:g :page :c :a :i :e :b :h :f :d])})))))

;; ---- UTF-8 byte order where it parts from host string order --------------
;;
;; CEDN-1 orders keys by UTF-8 bytes; a host string sort orders UTF-16 code
;; units. They disagree on exactly one pairing — a supplementary character
;; against a BMP character at or above U+E000 — and every other key in this
;; file is ASCII. Both inputs list the U+10000 key first.

(deftest query-key-order-is-utf8-byte-order-on-both-legs
  (rf/reg-route :route/wide {} "/wide")
  (let [bmp    "\uE000"
        astral "\uD800\uDC00"]
    (is (= "/wide?%EE%80%80=1&%F0%90%80%80=2"
           (rf.routing/route-url {:to    :route/wide
                                  :query (array-map astral "2" bmp "1")})))
    (is (= [[bmp "1"] [astral "2"]]
           (vec (:query (rf.routing/match-url "/wide?%F0%90%80%80=2&%EE%80%80=1")))))))

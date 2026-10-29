(ns re-frame.routing-literal-segment-cljs-test
  "A literal path segment matches by its decoded text, whichever spelling the
  URL arrives in (Spec 012 §Path-pattern grammar rule 6). A browser's
  `location.pathname`, a pushState'd entry on reload and an SSR request line
  all spell `/café` as `/caf%C3%A9`, while a caller with no window passes
  `/café` raw, so a literal compared as written is reachable by one of them
  at most. `route-url` emits the encoded spelling, which is what a route's own
  link carries. An encoded `%2F` stays inside its segment.

  The ASCII route is the control: it emits and matches exactly as written.

  Named `*-cljs-test.cljc` so the JVM runner and the `:node-test` build both
  run it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.routing :as rf.routing]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn rf.routing/reset-counters!}))

(defn- route-of [url] (:route-id (rf.routing/match-url url)))

(deftest an-ascii-literal-emits-and-matches-as-written
  (rf/reg-route :route/about {} "/about")
  (is (= ["/about" :route/about]
         [(rf.routing/route-url {:to :route/about}) (route-of "/about")])))

(deftest a-non-ascii-literal-is-reached-by-every-spelling
  (rf/reg-route :route/cafe {} "/café")
  (is (= "/caf%C3%A9" (rf.routing/route-url {:to :route/cafe}))
      "route-url emits the literal percent-encoded")
  (is (= [:route/cafe :route/cafe :route/cafe]
         (mapv route-of ["/caf%C3%A9" "/café" "/caf%c3%a9"]))
      "the wire spelling, the raw spelling and lower-case hex all match"))

(deftest a-literal-written-encoded-is-the-same-literal
  (rf/reg-route :route/uber {} "/%C3%BCber")
  (is (= ["/%C3%BCber" :route/uber :route/uber]
         [(rf.routing/route-url {:to :route/uber}) (route-of "/über") (route-of "/%C3%BCber")])))

(deftest a-space-in-a-literal-is-emitted-encoded-and-matched-either-way
  (rf/reg-route :route/hello {} "/hello world")
  (is (= ["/hello%20world" :route/hello :route/hello]
         [(rf.routing/route-url {:to :route/hello})
          (route-of "/hello%20world")
          (route-of "/hello world")])))

(deftest an-escaped-ascii-character-matches-its-literal
  (rf/reg-route :route/about {} "/about")
  (is (= :route/about (route-of "/%61bout"))))

(deftest an-encoded-slash-never-splits-a-segment
  (rf/reg-route :route/a-b {} "/a/b")
  (rf/reg-route :route/a-slash-b {} "/a%2Fb")
  (is (= [:route/a-b :route/a-slash-b :route/a-slash-b]
         (mapv route-of ["/a/b" "/a%2Fb" "/a%2fb"]))))

(deftest a-literal-with-malformed-percent-encoding-is-refused
  (testing "a stray `%` names no character, so no URL can spell the literal"
    (is (= :rf.error/invalid-route-pattern
           (:rf.error/id
             (ex-data (try (rf/reg-route :route/pct {} "/100%")
                           nil
                           (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))))))))

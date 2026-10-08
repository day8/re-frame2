(ns re-frame.routing-url-non-edn-cljs-test
  "The `route-url` emission boundary fails closed on values with no
  round-trippable URL form. EP-0012 §Canonical EDN identity: URL printing
  MUST NOT use host `str`, JS object stringification or object identity to
  invent a route identity.

  A host value — a raw JS object, a function, a non-integer number — in a path
  param or a query value raises `:rf.error/route-url-non-edn-value` before any
  URL is returned, never `/items/[object Object]`. So does a host date: it IS
  a portable identity for a cache key, but its host `str` differs between
  hosts and `match-url` has no instant coercion to read it back. A fragment is
  narrower still: string or nil only (Spec 012 §Fragments).

  The rest of the file pins the round trips that rest on the same emission
  rules: namespaced query keys, a namespaced `:query-defaults` key, string
  fragments, and `:uuid` captures, which canonicalise to lowercase on both
  hosts.

  Named `*-cljs-test.cljc` so the JVM runner and the `:node-test` build both
  run it; on CLJS a raw `#js {}` and a `js/Date` are the host values a
  careless caller would smuggle in."
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

(defn- refusal
  "The identifying slots of the error `route-url` throws for `address`."
  [address]
  (select-keys (ex-data (try (rf.routing/route-url address)
                             nil
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e)))
               [:rf.error/id :route-id :slot :param]))

;; One value per refusal path: a non-integer number takes the encode-and-catch
;; leg rather than the by-type fast answer, a host object has no canonical
;; EDN identity, and a host date is refused by type.
(def ^:private host-object #?(:clj (Object.) :cljs #js {:a 1}))

(def ^:private host-values
  [1.5 host-object #?(:clj (java.util.Date.) :cljs (js/Date.))])

(deftest route-url-path-param-host-value-fails-closed
  (rf/reg-route :route/item {} "/items/:id")
  (doseq [v host-values]
    (is (= {:rf.error/id :rf.error/route-url-non-edn-value
            :route-id    :route/item
            :slot        :params
            :param       :id}
           (refusal {:to :route/item :params {:id v}}))
        (pr-str v))))

(deftest route-url-query-value-host-value-fails-closed
  (rf/reg-route :route/search {} "/search")
  (is (= {:rf.error/id :rf.error/route-url-non-edn-value
          :route-id    :route/search
          :slot        :query
          :param       :q}
         (refusal {:to :route/search :query {:q host-object}}))))

(deftest route-url-admitted-url-scalars-still-emit
  (testing "the guard is not string-only: present-but-falsy booleans and
            integers, UUIDs and integer query values all emit"
    (rf/reg-route :route/scalar {} "/s/:v")
    (is (= ["/s/false" "/s/0" "/s/550e8400-e29b-41d4-a716-446655440000" "/s/x?n=1"]
           (mapv rf.routing/route-url
                 [{:to :route/scalar :params {:v false}}
                  {:to :route/scalar :params {:v 0}}
                  {:to :route/scalar :params {:v #uuid "550e8400-e29b-41d4-a716-446655440000"}}
                  {:to :route/scalar :params {:v "x"} :query {:n 1}}])))))

;; A namespaced keyword is a distinct canonical EDN fact, so emission uses the
;; reversible token `user/id` (percent-encoded `user%2Fid`) rather than
;; `(name k)`, which would collapse `:user/id` and `:account/id` into one
;; `id=` key.

(deftest route-url-distinct-namespaces-same-name-do-not-collide
  (rf/reg-route :route/two {:query [:map [:user/id :string] [:account/id :string]]} "/two")
  (let [url (rf.routing/route-url {:to :route/two :query {:user/id "u" :account/id "a"}})]
    (is (= "/two?account%2Fid=a&user%2Fid=u" url))
    (is (= {:account/id "a" :user/id "u"} (:query (rf.routing/match-url url))))))

(deftest route-url-namespaced-query-defaults-round-trip
  (testing "a namespaced :query-defaults key belongs to the declared vocabulary:
            inbound it promotes to the keyword, and absent it is filled in"
    (rf/reg-route :route/dflt {:query-defaults {:user/page 1}} "/dflt")
    (is (= {:user/page "3"} (:query (rf.routing/match-url "/dflt?user%2Fpage=3"))))
    (is (= {:user/page 1} (:query (rf.routing/match-url "/dflt"))))))

(deftest route-url-non-string-fragment-fails-closed
  (testing "a non-string fragment fails closed rather than being host-stringified
            — `false` included, which a truthiness gate would silently elide"
    (rf/reg-route :route/frag {} "/frag")
    (doseq [v [42 false]]
      (is (= {:rf.error/id :rf.error/route-url-non-edn-value
              :route-id    :route/frag
              :slot        :fragment}
             (refusal {:to :route/frag :fragment v}))
          (pr-str v)))))

(deftest route-url-string-fragment-round-trips
  (testing "nil and empty fragments are elided; a %-significant string
            fragment round-trips byte-exact"
    (rf/reg-route :route/fr {} "/fr")
    (is (= ["/fr" "/fr"] (mapv #(rf.routing/route-url {:to :route/fr :fragment %}) [nil ""])))
    (is (= "50% done"
           (:fragment (rf.routing/match-url
                        (rf.routing/route-url {:to :route/fr :fragment "50% done"})))))))

;; CLJS `(uuid s)` keeps its string verbatim where JVM `parse-uuid`
;; canonicalises, so without lowercasing first a mixed-case capture would
;; match to host-divergent params and re-emit a host-divergent href.

(deftest uuid-path-mixed-case-coerces-host-symmetric
  (rf/reg-route :route/art {:params [:map [:id :uuid]]} "/articles/:id")
  (is (= {:route-id           :route/art
          :params             {:id #uuid "550e8400-e29b-41d4-a716-446655440000"}
          :validation-failed? false}
         (select-keys (rf.routing/match-url "/articles/550E8400-E29B-41D4-A716-446655440000")
                      [:route-id :params :validation-failed?]))))

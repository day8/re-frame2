(ns re-frame.routing-url-emission-equivalence-test
  "Exact URL emission for literal runs, captures, splats and optional groups. Invalid and uncaptured values fail closed."
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

;; ===========================================================================
;; The fail-closed classes — every one of them closed
;; ===========================================================================

(deftest the-emission-guards-still-refuse-what-they-always-refused
  (register-routes!)

  (testing "an empty top-level path param"
    (is (= :rf.error/missing-route-param
           (:rf.error/id (thrown-data #(rf.routing.registry/route-url {:to :eq/profile :params {:username ""}}))))))

  (testing "the set-free membership test counts neither a NAMESPACED key nor a
            NON-keyword key as the bare capture of the same name"
    (is (= {:reason :uncaptured-params :keys [:a/username]}
           (select-keys (thrown-data #(rf.routing.registry/route-url
                                        {:to :eq/profile :params {:username "j" :a/username "x"}}))
                        [:reason :keys])))
    (is (= :uncaptured-params
           (:reason (thrown-data #(rf.routing.registry/route-url
                                    {:to :eq/profile :params {:username "j" "username" "x"}}))))))

  (testing "an integer outside the safe range takes the guard's slow leg and is refused"
    (is (= :rf.error/route-url-non-edn-value
           (:rf.error/id (thrown-data #(rf.routing.registry/route-url
                                         {:to :eq/profile :params {:username (inc 9007199254740991)}})))))))

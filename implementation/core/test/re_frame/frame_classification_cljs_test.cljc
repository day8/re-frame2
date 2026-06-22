(ns re-frame.frame-classification-cljs-test
  "EP-0015 §3 (HTTP carriers) + §9 (observability sink policy) — the
  surviving frame-owned policy on `reg-frame`. EP-0025 REMOVED the durable
  `:sensitive` / `:large {:app-db …}` app-db classification annotation (a
  frame is not app-db's definition site); durable app-db classification now
  rides the commit-plane classification effects (`re-frame.elision`,
  `:source :effect`). This suite pins what `re-frame.frame-classification`
  still owns:

    (a) the retired `:app-db` key (and the whole `:large` frame key) FAIL
        LOUD with `:rf.error/bad-frame-classification` at `reg-frame` time —
        the clean-break guard against the removed annotation;
    (b) HTTP carriers (`:sensitive {:http {:headers … :query-params …}}`) and
        `:observability` sink policy validate (shape-only) and ride the
        frame's `:config` verbatim for the later HTTP / observability slices;
    (c) fail-loud — unknown classification keys, non-string HTTP carrier
        names, malformed observability entries, and unknown egress profiles
        throw `:rf.error/bad-frame-classification` at `reg-frame` time,
        before any state mutates / before `:initial-events`.

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) AND the JVM
  `clojure -M:test` runner both pick it up. The validation is plain CLJC;
  no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(defn- bad-classification-ex
  "Run thunk and return the caught ex-data when it throws
  `:rf.error/bad-frame-classification`, else nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (ex-data e))))

;; ---------------------------------------------------------------------------
;; (a) EP-0025 clean break — the retired durable app-db annotation fails loud
;; ---------------------------------------------------------------------------

(deftest retired-sensitive-app-db-key-fails-loud
  (testing "EP-0025: the retired `:sensitive {:app-db …}` frame annotation
            fails loud at reg-frame — durable app-db classification moved to
            the commit-plane classification effects, so the only valid
            `:sensitive` key is now `:http`"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/retired-sens
                    {:sensitive {:app-db [[:auth :token]]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :app-db] (:bad-key data))
          "the retired :app-db key is the offending slot"))
    ;; The frame must NOT have been registered (fail-loud is transactional).
    (is (nil? (frame/frame :app/retired-sens))
        "the retired annotation threw before any frame state mutated")))

(deftest retired-large-frame-key-fails-loud
  (testing "EP-0025: the retired top-level `:large {:app-db …}` frame
            annotation fails loud at reg-frame — durable app-db classification
            moved to the commit-plane classification effects, so `:large` is
            no longer a frame key. This is the SYMMETRIC guard to the
            `:sensitive {:app-db …}` rejection: a frame carrying `:large` must
            not silently register and install nothing (a removed-annotation
            footgun)."
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/retired-large
                    {:large {:app-db [[:documents :csv-upload]]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= :large (:bad-key data))
          "the retired top-level :large key is the offending slot"))
    ;; The frame must NOT have been registered (fail-loud is transactional).
    (is (nil? (frame/frame :app/retired-large))
        "the retired annotation threw before any frame state mutated")
    ;; And nothing leaked into the elision registry.
    (is (empty? (elision/declarations :app/retired-large)))
    (is (empty? (elision/sensitive-declarations :app/retired-large)))))

;; ---------------------------------------------------------------------------
;; (b) HTTP carriers + observability ride the frame config (the surviving
;;     surface). Durable app-db classification is asserted via the
;;     commit-plane effect path (`elision/apply-classification-effects`).
;; ---------------------------------------------------------------------------

(deftest valid-http-and-observability-retained-on-config
  (testing "well-formed :http carriers and :observability ride the frame config"
    (rf/reg-frame :app/full
      {:sensitive {:http {:headers      ["X-Honeycomb-Team"]
                          :query-params ["shop_token"]}}
       :observability {:handled-events [{:sink :my-app.sinks/datadog
                                         :rf.egress/profile :rf.egress/off-box-observability
                                         :opts {:service "checkout-spa"}}]
                       :errors         [{:sink :my-app.sinks/sentry}]}})
    (let [meta (rf/frame-meta :app/full)]
      (is (= ["X-Honeycomb-Team"]
             (get-in meta [:sensitive :http :headers]))
          ":http carriers ride the frame config (read via frame-meta)")
      (is (= :my-app.sinks/datadog
             (get-in meta [:observability :handled-events 0 :sink]))
          ":observability sink policy rides the frame config"))))

(deftest commit-plane-effect-classifies-app-db-path
  (testing "EP-0025: durable app-db classification rides the commit-plane
            `:sensitive` / `:large` effects (the replacement for the removed
            frame annotation), written into the elision registry under
            `:source :effect`"
    (rf/reg-frame :app/effects {})
    ;; The same registry write a reg-event returning `:sensitive` / `:large`
    ;; alongside `:db` performs.
    (frame/swap-runtime-db! :app/effects
      (fn [rt] (elision/apply-classification-effects rt
                 {:sensitive [[:auth :token]]
                  :large     [[:documents :csv-upload]]})))
    (let [sens  (elision/sensitive-declarations :app/effects)
          large (elision/declarations :app/effects)]
      (is (contains? sens [:auth :token])
          "the :sensitive effect classified [:auth :token]")
      (is (= :effect (:source (get sens [:auth :token])))
          "the declaration is tagged :source :effect")
      (is (contains? large [:documents :csv-upload])
          "the :large effect classified [:documents :csv-upload]"))))

;; ---------------------------------------------------------------------------
;; (c) fail-loud
;; ---------------------------------------------------------------------------

(deftest fail-loud-on-unknown-classification-key
  (testing "an unknown :sensitive block key fails loudly (only :http is valid)"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad2
                    {:sensitive {:bogus [:x]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :bogus] (:bad-key data))))
    ;; Unknown :observability stream key.
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad2b
                    {:observability {:bogus-stream []}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :bogus-stream] (:bad-key data))))
    ;; Unknown :sensitive :http carrier key.
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad2c
                    {:sensitive {:http {:cookies ["x"]}}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :http :cookies] (:bad-key data))))))

(deftest fail-loud-on-non-string-carrier
  (testing "a non-string HTTP carrier name fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad3
                    {:sensitive {:http {:headers [:X-Honeycomb-Team]}}}))] ;; keyword, not string
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :http :headers] (:bad-key data)))
      (is (= :X-Honeycomb-Team (:bad-carrier data))))
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad3b
                    {:sensitive {:http {:query-params [42]}}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :http :query-params] (:bad-key data)))
      (is (= 42 (:bad-carrier data))))))

;; rf2-4wqxq8 — :query-params accepts a {:include [..] :except [..]} policy map
;; (an additive frame-local subtraction path) in addition to the legacy vector.

(deftest query-params-policy-map-accepted-and-validated
  (testing "rf2-4wqxq8 — a well-formed {:include :except} :query-params map is accepted"
    (rf/reg-frame :app/qp-policy-ok
      {:sensitive {:http {:query-params {:include ["shop_token"]
                                         :except  ["token"]}}}})
    (is (= {:include ["shop_token"] :except ["token"]}
           (get-in (rf/frame-meta :app/qp-policy-ok)
                   [:sensitive :http :query-params]))
        "the policy map rides the frame config verbatim"))
  (testing "an unknown key inside the :query-params policy map fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/qp-policy-badkey
                    {:sensitive {:http {:query-params {:include ["x"] :bogus ["y"]}}}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:sensitive :http :query-params :bogus] (:bad-key data)))))
  (testing "a non-string name inside :include / :except fails loudly with a precise bad-key"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/qp-policy-badinc
                    {:sensitive {:http {:query-params {:include [42]}}}}))]
      (is (= [:sensitive :http :query-params :include] (:bad-key data)))
      (is (= 42 (:bad-carrier data))))
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/qp-policy-badexc
                    {:sensitive {:http {:query-params {:except [:kw]}}}}))]
      (is (= [:sensitive :http :query-params :except] (:bad-key data)))
      (is (= :kw (:bad-carrier data)))))
  (testing "a non-vector :include sub-value fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/qp-policy-nonvec
                    {:sensitive {:http {:query-params {:include "not-a-vector"}}}}))]
      (is (= [:sensitive :http :query-params :include] (:bad-key data)))))
  (testing "the header denylist stays vector-only (no policy-map form)"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/hdr-policy-rejected
                    {:sensitive {:http {:headers {:include ["X-Foo"]}}}}))]
      (is (= [:sensitive :http :headers] (:bad-key data))
          "a {:include ...} map is not a valid :headers value (immutable denylist)"))))

(deftest fail-loud-on-bad-observability-entry
  (testing "an :observability entry without a :sink keyword fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad4
                    {:observability {:handled-events [{:opts {:service "x"}}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :sink] (:bad-key data))))))

(deftest fail-loud-on-unknown-observability-profile
  ;; rf2-t55hxg.13 — `:rf.egress/profile` is a member of the closed EP-0015
  ;; §10 profile enum. A typo'd / unknown profile must fail loudly at
  ;; reg-frame (the seam that owns the policy), not silently install and
  ;; only blow up downstream when the sink first fires.
  (testing "an :observability entry naming an unknown :rf.egress/profile fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad-profile
                    {:observability
                     {:handled-events [{:sink :my-app.sinks/datadog
                                        :rf.egress/profile :rf.egress/bogus-profile}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :rf.egress/profile] (:bad-key data)))
      (is (= :rf.egress/bogus-profile (:bad-value data)))
      (is (contains? (:valid data) :rf.egress/off-box-observability)
          "the error carries the closed profile enum"))
    ;; The :errors stream is validated identically.
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad-profile-err
                    {:observability
                     {:errors [{:sink :my-app.sinks/sentry
                                :rf.egress/profile :not-even-egress-namespaced}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :errors :rf.egress/profile] (:bad-key data)))))
  (testing "a well-formed :rf.egress/profile from the closed enum is accepted"
    (rf/reg-frame :app/good-profile
      {:observability {:handled-events [{:sink :my-app.sinks/datadog
                                         :rf.egress/profile :rf.egress/off-box-observability}]}})
    (is (= :my-app.sinks/datadog
           (get-in (rf/frame-meta :app/good-profile)
                   [:observability :handled-events 0 :sink])))))

(deftest fail-loud-on-bad-observability-opts
  ;; rf2-t55hxg.13 — `:opts`, when present, is the sink's option bag and
  ;; must be a map (or nil). A non-map :opts is malformed — fail at reg-frame
  ;; rather than handing junk to the sink at fire time.
  (testing "an :observability entry with non-map :opts fails loudly"
    (let [data (bad-classification-ex
                 #(rf/reg-frame :app/bad-opts
                    {:observability
                     {:handled-events [{:sink :my-app.sinks/datadog
                                        :opts "not-a-map"}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :opts] (:bad-key data)))
      (is (= "not-a-map" (:bad-value data)))))
  (testing "nil :opts and an omitted :opts are both accepted"
    (rf/reg-frame :app/nil-opts
      {:observability {:handled-events [{:sink :my-app.sinks/datadog :opts nil}]
                       :errors         [{:sink :my-app.sinks/sentry}]}})
    (is (= :my-app.sinks/datadog
           (get-in (rf/frame-meta :app/nil-opts)
                   [:observability :handled-events 0 :sink])))))

(deftest no-policy-keys-is-a-no-op
  (testing "a frame with no policy keys installs no classification + registers cleanly"
    (rf/reg-frame :app/plain {:doc "no classification"})
    (is (some? (frame/frame :app/plain)) "the frame registered")
    (is (empty? (elision/sensitive-declarations :app/plain)))
    (is (empty? (elision/declarations :app/plain)))))

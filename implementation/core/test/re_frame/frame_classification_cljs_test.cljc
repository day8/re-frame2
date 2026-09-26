(ns re-frame.frame-classification-cljs-test
  "EP-0015 §9 (observability sink policy) — the frame-owned policy on
  `make-frame`. Under EP-0025 a frame carries no durable `:sensitive` /
  `:large {:app-db …}` app-db classification annotation (a frame is not
  app-db's definition site; durable app-db classification rides the
  commit-plane classification effects — `re-frame.elision`, `:source
  :effect`) and no `:sensitive {:http …}` HTTP carrier block (HTTP carriers
  ride the `:rf.http/managed` `reg-fx` registration `:carriers` block — the
  transient-payload case). This suite pins what
  `re-frame.frame-classification` owns:

    (a) the retired `:sensitive` and `:large` frame keys FAIL LOUD with
        `:rf.error/bad-frame-classification` at `make-frame` time, so a
        frame carrying one cannot silently install nothing;
    (b) `:observability` sink policy validates (shape-only) and rides the
        frame's `:config` verbatim for sink routing;
    (c) fail-loud — unknown observability keys, malformed observability
        entries, and unknown egress profiles throw
        `:rf.error/bad-frame-classification` at `make-frame` time, before any
        state mutates / before `:initial-events`.

  HTTP carrier classification (the `:carriers` block on `:rf.http/managed`)
  is covered in `re-frame.http-privacy-test` (the http artefact owns the
  resolver + validation).

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) AND the JVM
  `clojure -M:test` runner both pick it up. The validation is plain CLJC;
  no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- bad-classification-ex
  "Run thunk and return the caught ex-data when it throws
  `:rf.error/bad-frame-classification`, else nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (ex-data e))))

;; ---------------------------------------------------------------------------
;; (a) EP-0025 — the retired durable app-db annotation fails loud
;; ---------------------------------------------------------------------------

(deftest retired-sensitive-frame-key-fails-loud
  (testing "EP-0025: the whole retired `:sensitive` frame key fails loud at
            make-frame — durable app-db classification rides the
            commit-plane classification effects AND HTTP carriers ride the
            `:rf.http/managed` `reg-fx` registration (`:carriers`). With no
            valid content, a frame carrying ANY `:sensitive` block is
            rejected."
    ;; The retired durable `:app-db` block.
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/retired-sens-appdb :sensitive {:app-db [[:auth :token]]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= :sensitive (:bad-key data))
          "the retired :sensitive frame key is the offending slot"))
    (is (nil? (rf.frame/frame :app/retired-sens-appdb))
        "the retired annotation threw before any frame state mutated")
    ;; The retired `:http` carrier block (carriers live on :rf.http/managed).
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/retired-sens-http :sensitive {:http {:headers ["X-Honeycomb-Team"]}}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= :sensitive (:bad-key data))
          "the retired :sensitive frame key (carrying :http) is the offending slot"))
    (is (nil? (rf.frame/frame :app/retired-sens-http))
        "the retired carrier annotation threw before any frame state mutated")))

(deftest retired-large-frame-key-fails-loud
  (testing "EP-0025: the retired top-level `:large {:app-db …}` frame
            annotation fails loud at make-frame — durable app-db classification
            rides the commit-plane classification effects, so `:large` is
            not a frame key. This is the SYMMETRIC guard to the
            `:sensitive {:app-db …}` rejection: a frame carrying `:large` must
            not silently register and install nothing (a retired-annotation
            footgun)."
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/retired-large :large {:app-db [[:documents :csv-upload]]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= :large (:bad-key data))
          "the retired top-level :large key is the offending slot"))
    ;; The frame must NOT have been registered (fail-loud is transactional).
    (is (nil? (rf.frame/frame :app/retired-large))
        "the retired annotation threw before any frame state mutated")
    ;; And nothing leaked into the elision registry.
    (is (empty? (rf.elision/declarations :app/retired-large)))
    (is (empty? (rf.elision/sensitive-declarations :app/retired-large)))))

;; ---------------------------------------------------------------------------
;; (b) + (c) :observability validates fail-loud, and a well-formed policy rides
;;     the frame config — the profile and sink-entry cases below each end with
;;     that accepted positive control. Durable app-db classification rides the
;;     commit-plane effects (`classification-effects-cljs-test`); HTTP carriers
;;     ride the :rf.http/managed registration (covered in the http artefact).
;; ---------------------------------------------------------------------------

(deftest fail-loud-on-unknown-classification-key
  (testing "any :sensitive block fails loud — the whole frame key is retired"
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/bad2 :sensitive {:bogus [:x]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= :sensitive (:bad-key data))))
    ;; Unknown :observability stream key.
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/bad2b :observability {:bogus-stream []}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :bogus-stream] (:bad-key data))))))

(deftest fail-loud-on-bad-observability-entry
  (testing "an :observability entry without a :sink keyword fails loudly"
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/bad4 :observability {:handled-events [{:service "x"}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :sink] (:bad-key data))))))

(deftest fail-loud-on-unknown-observability-profile
  ;; `:rf.egress/profile` is a member of the closed EP-0015
  ;; §10 profile enum. A typo'd / unknown profile must fail loudly at
  ;; make-frame (the seam that owns the policy), not silently install and
  ;; only blow up downstream when the sink first fires.
  (testing "an :observability entry naming an unknown :rf.egress/profile fails loudly"
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/bad-profile :observability
                                  {:handled-events [{:sink :my-app.sinks/datadog
                                                     :rf.egress/profile :rf.egress/bogus-profile}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :rf.egress/profile] (:bad-key data)))
      (is (= :rf.egress/bogus-profile (:bad-value data)))
      (is (contains? (:valid data) :rf.egress/off-box-observability)
          "the error carries the closed profile enum"))
    ;; The :errors stream is validated identically.
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/bad-profile-err :observability
                                  {:errors [{:sink :my-app.sinks/sentry
                                             :rf.egress/profile :not-even-egress-namespaced}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :errors :rf.egress/profile] (:bad-key data)))))
  (testing "a well-formed :rf.egress/profile from the closed enum is accepted"
    (rf/make-frame {:id :app/good-profile :observability {:handled-events [{:sink :my-app.sinks/datadog
                                                      :rf.egress/profile :rf.egress/off-box-observability}]}})
    (is (= :my-app.sinks/datadog
           (get-in (rf/frame-meta :app/good-profile)
                   [:observability :handled-events 0 :sink])))))

(deftest fail-loud-on-unknown-sink-entry-key
  ;; The sink entry is a CLOSED map: `:sink` plus the optional
  ;; `:rf.egress/profile`, and nothing else. `route-stream!` reads exactly
  ;; those two keys, so any other key would be accept-and-drop. Fail at
  ;; make-frame with the offending key named, exactly as an unknown profile
  ;; does. (The retired `:opts` vendor bag is one such key: vendor
  ;; configuration is closed over by the registered sink fn.)
  (testing "an :observability entry carrying the retired :opts bag fails loudly"
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/bad-opts :observability
                                  {:handled-events [{:sink :my-app.sinks/datadog
                                                     :opts {}}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :handled-events :opts] (:bad-key data)))
      (is (= #{:sink :rf.egress/profile} (:valid data))
          "the error carries the closed sink-entry key set")))
  (testing "any other unknown entry key fails loudly, naming the key"
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/bad-vendor :observability
                                  {:errors [{:sink :my-app.sinks/sentry
                                             :vendor 1}]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= [:observability :errors :vendor] (:bad-key data)))))
  (testing "the two-key entry — and the bare :sink entry — still register"
    (rf/make-frame {:id :app/closed-entry
                    :observability {:handled-events [{:sink :my-app.sinks/datadog
                                                      :rf.egress/profile :rf.egress/public-error}]
                                    :errors         [{:sink :my-app.sinks/sentry}]}})
    (is (= :my-app.sinks/datadog
           (get-in (rf/frame-meta :app/closed-entry)
                   [:observability :handled-events 0 :sink])))))

(deftest no-policy-keys-is-a-no-op
  (testing "a frame with no policy keys installs no classification + registers cleanly"
    (rf/make-frame {:id :app/plain :doc "no classification"})
    (is (some? (rf.frame/frame :app/plain)) "the frame registered")
    (is (empty? (rf.elision/sensitive-declarations :app/plain)))
    (is (empty? (rf.elision/declarations :app/plain)))))

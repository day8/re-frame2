(ns re-frame.frame-classification-cljs-test
  "EP-0015 §9 (observability sink policy) — the surviving frame-owned policy
  on `make-frame`. EP-0025 REMOVED the durable `:sensitive` / `:large {:app-db
  …}` app-db classification annotation (a frame is not app-db's definition
  site; durable app-db classification now rides the commit-plane
  classification effects — `re-frame.elision`, `:source :effect`) AND the
  `:sensitive {:http …}` HTTP carrier block (it moved onto the
  `:rf.http/managed` `reg-fx` registration `:carriers` block — the
  transient-payload case). This suite pins what `re-frame.frame-classification`
  still owns:

    (a) the retired `:sensitive` and `:large` frame keys FAIL LOUD with
        `:rf.error/bad-frame-classification` at `make-frame` time — the
        clean-break guard against the removed annotations;
    (b) `:observability` sink policy validates (shape-only) and rides the
        frame's `:config` verbatim for the later observability slice;
    (c) fail-loud — unknown observability keys, malformed observability
        entries, and unknown egress profiles throw
        `:rf.error/bad-frame-classification` at `make-frame` time, before any
        state mutates / before `:initial-events`.

  HTTP carrier classification (the `:carriers` block on `:rf.http/managed`)
  is covered in `re-frame.http-privacy-test` (the http artefact owns the
  resolver + validation now).

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
;; (a) EP-0025 clean break — the retired durable app-db annotation fails loud
;; ---------------------------------------------------------------------------

(deftest retired-sensitive-frame-key-fails-loud
  (testing "EP-0025: the whole retired `:sensitive` frame key fails loud at
            make-frame — durable app-db classification moved to the
            commit-plane classification effects AND the `:sensitive {:http …}`
            HTTP carrier block moved onto the `:rf.http/managed` `reg-fx`
            registration (`:carriers`). With no valid content left, a frame
            carrying ANY `:sensitive` block is rejected."
    ;; The retired durable `:app-db` block.
    (let [data (bad-classification-ex
                 #(rf/make-frame {:id :app/retired-sens-appdb :sensitive {:app-db [[:auth :token]]}}))]
      (is (= :rf.error/bad-frame-classification (:rf.error/id data)))
      (is (= :sensitive (:bad-key data))
          "the retired :sensitive frame key is the offending slot"))
    (is (nil? (rf.frame/frame :app/retired-sens-appdb))
        "the retired annotation threw before any frame state mutated")
    ;; The retired `:http` carrier block (now lives on :rf.http/managed).
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
            moved to the commit-plane classification effects, so `:large` is
            no longer a frame key. This is the SYMMETRIC guard to the
            `:sensitive {:app-db …}` rejection: a frame carrying `:large` must
            not silently register and install nothing (a removed-annotation
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
;; (b) :observability rides the frame config (the surviving surface).
;;     Durable app-db classification is asserted via the commit-plane effect
;;     path (`rf.elision/apply-classification-effects`); HTTP carriers now ride
;;     the :rf.http/managed registration (covered in the http artefact).
;; ---------------------------------------------------------------------------

(deftest valid-observability-retained-on-config
  (testing "well-formed :observability rides the frame config"
    (rf/make-frame {:id :app/full :observability {:handled-events [{:sink :my-app.sinks/datadog
                                                      :rf.egress/profile :rf.egress/off-box-observability}]
                                    :errors         [{:sink :my-app.sinks/sentry}]}})
    (let [meta (rf/frame-meta :app/full)]
      (is (= :my-app.sinks/datadog
             (get-in meta [:observability :handled-events 0 :sink]))
          ":observability sink policy rides the frame config"))))

(deftest commit-plane-effect-classifies-app-db-path
  (testing "EP-0025: durable app-db classification rides the commit-plane
            `:sensitive` / `:large` effects (the replacement for the removed
            frame annotation), written into the elision registry under
            `:source :effect`"
    (rf/make-frame {:id :app/effects})
    ;; The same registry write a reg-event returning `:sensitive` / `:large`
    ;; alongside `:db` performs.
    (rf.frame/swap-runtime-db! :app/effects
      (fn [rt] (rf.elision/apply-classification-effects rt
                 {:sensitive [[:auth :token]]
                  :large     [[:documents :csv-upload]]})))
    (let [sens  (rf.elision/sensitive-declarations :app/effects)
          large (rf.elision/declarations :app/effects)]
      (is (contains? sens [:auth :token])
          "the :sensitive effect classified [:auth :token]")
      (is (= #{{:source :effect}} (get sens [:auth :token]))
          "the effect owner is the sole claimant, tagged :source :effect")
      (is (contains? large [:documents :csv-upload])
          "the :large effect classified [:documents :csv-upload]"))))

;; ---------------------------------------------------------------------------
;; (c) fail-loud
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
  ;; rf2-t55hxg.13 — `:rf.egress/profile` is a member of the closed EP-0015
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
  ;; rf2-kuky.17 — the sink entry is a CLOSED map: `:sink` plus the optional
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

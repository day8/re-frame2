(ns re-frame.routing-plan-test
  "Focused tests for the pure navigation-planning seam
  `re-frame.routing.plan` (rf2-u8qe7y finding 1).

  Before the seam, the pre-commit navigation policy — fragment
  normalisation, the `:rf.route/not-found` fallback shape + `:reason`
  vocabulary, the identical-/fragment-only classification, and the
  fail-closed telemetry intents — was duplicated across the programmatic
  (`:rf.route/navigate`) and URL-driven (`:rf.route/transitioned` /
  `:rf.route/handle-url-change`) entry points, and parity was only pinned
  by scattered end-to-end regression tests in `routing_test.clj` added
  AFTER drift was discovered. These tests pin the parity cases directly
  at the seam: a planner bug now fails here, localised, rather than
  surfacing as a cross-entry-point asymmetry caught (or missed) by an
  integration test.

  All functions under test are PURE — no registrar / runtime-db fixture
  needed except for `scroll-plan` (which reads a plain runtime-db map for
  the `:current` slice + an explicit host-side scroll-cache map for the
  `:saved-pos` lookup, rf2-1hncp2)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.routing.plan :as plan]))

;; ---- empty-string fragment normalisation (rf2-zmcq6) ---------------------

(deftest normalize-fragment-collapses-empty-string
  (testing "an explicit empty-string fragment collapses to nil (route-url emits no trailing #)"
    (is (nil? (plan/normalize-fragment ""))
        "\"\" → nil so the slice :fragment matches the pushed (fragment-less) URL"))
  (testing "a non-empty fragment passes through unchanged"
    (is (= "section-2" (plan/normalize-fragment "section-2"))))
  (testing "nil passes through unchanged"
    (is (nil? (plan/normalize-fragment nil)))))

;; ---- not-found fallback shape + reason vocabulary ------------------------

(deftest not-found-params-bare-miss-carries-only-url
  (testing "an unmatched URL fallback carries {:url url} with no :reason"
    (is (= {:url "/nope"} (plan/not-found-params "/nope" nil)))))

(deftest not-found-params-stamps-each-reason
  (testing "the shared :reason vocabulary — both entry points stamp identical fallback params"
    (is (= {:url "/x" :reason :malformed-url}
           (plan/not-found-params "/x" :malformed-url))
        "malformed percent-encoding fallback")
    (is (= {:url "/x" :reason :validation}
           (plan/not-found-params "/x" :validation))
        "schema-validation miss fallback")
    (is (= {:url "/x" :reason :match-error}
           (plan/not-found-params "/x" :match-error))
        "unexpected match-url throw fallback")))

;; ---- identical navigation (Spec 012 §Per-route data loading rule 3) ------

(deftest identical-route-target-detects-complete-no-op
  (let [slice {:route-id :route/cart :params {} :query {:q "a"} :fragment "f"}]
    (testing "id/params/query/fragment all equal → identical (complete no-op)"
      (is (true? (plan/identical-route-target? slice :route/cart {} {:q "a"} "f"))))
    (testing "a differing query is NOT identical"
      (is (false? (plan/identical-route-target? slice :route/cart {} {:q "b"} "f"))))
    (testing "a differing fragment is NOT identical (that's the fragment-only case)"
      (is (false? (plan/identical-route-target? slice :route/cart {} {:q "a"} "g"))))
    (testing "no prior slice → never identical (first nav)"
      (is (false? (plan/identical-route-target? nil :route/cart {} {:q "a"} "f"))))))

;; ---- fragment-only navigation (Spec 012 §Fragments rules 3-4) ------------

(deftest fragment-only-detects-same-page-anchor-change
  (let [slice {:route-id :route/docs :params {:p 1} :query {:q "a"} :fragment "intro"}]
    (testing "same id/params/query, differing fragment → fragment-only"
      (is (true? (plan/fragment-only? slice :route/docs {:p 1} {:q "a"} "details"))))
    (testing "identical fragment is NOT fragment-only (that's the complete no-op)"
      (is (false? (plan/fragment-only? slice :route/docs {:p 1} {:q "a"} "intro"))))
    (testing "a differing route-id is NOT fragment-only (full transition)"
      (is (false? (plan/fragment-only? slice :route/cart {:p 1} {:q "a"} "details"))))
    (testing "a differing query is NOT fragment-only (full transition)"
      (is (false? (plan/fragment-only? slice :route/docs {:p 1} {:q "b"} "details"))))
    (testing "no prior slice → never fragment-only (nothing to be a fragment of)"
      (is (false? (plan/fragment-only? nil :route/docs {:p 1} {:q "a"} "details"))))
    (testing "fragment-only and identical-route-target? are mutually exclusive"
      (let [params {:p 1} query {:q "a"}]
        (is (not (and (plan/fragment-only? slice :route/docs params query "details")
                      (plan/identical-route-target? slice :route/docs params query "details")))
            "differing fragment → fragment-only true, identical false")
        (is (not (and (plan/fragment-only? slice :route/docs params query "intro")
                      (plan/identical-route-target? slice :route/docs params query "intro")))
            "equal fragment → fragment-only false, identical true")))))

;; ---- fail-closed telemetry intents (parity across both entry points) -----

(deftest fallback-telemetry-intents-clean-nav-emits-nothing
  (testing "a matched route with no fail-closed condition produces no telemetry intents"
    (is (= [] (plan/fallback-telemetry-intents
                {:throw-reason nil :malformed? false :no-not-found? false
                 :url "/cart" :frame nil})))))

(deftest fallback-telemetry-intents-malformed-url
  (testing "malformed percent-encoding emits :rf.warning/malformed-url {:url}"
    (is (= [[:emit :warning :rf.warning/malformed-url {:url "/a%2"}]]
           (plan/fallback-telemetry-intents
             {:throw-reason nil :malformed? true :no-not-found? false
              :url "/a%2" :frame nil})))))

(deftest fallback-telemetry-intents-match-error
  (testing "a match-url throw emits :rf.warning/malformed-url carrying the throw :reason"
    (is (= [[:emit :warning :rf.warning/malformed-url
             {:url "/x" :reason :match-error}]]
           (plan/fallback-telemetry-intents
             {:throw-reason :match-error :malformed? false :no-not-found? false
              :url "/x" :frame nil}))
        "an unexpected match-url throw — surfaced regardless of which nav event it arrived on")))

(deftest fallback-telemetry-intents-missing-not-found-route
  (testing "a not-found fallback with no registered not-found route emits :rf.warning/no-not-found-route"
    (is (= [[:emit :warning :rf.warning/no-not-found-route {:url "/gone"}]]
           (plan/fallback-telemetry-intents
             {:throw-reason nil :malformed? false :no-not-found? true
              :url "/gone" :frame nil})))))

(deftest fallback-telemetry-intents-threads-frame-onto-every-tag
  (testing "when :frame is present it lands on every emitted tag map (epoch/Xray attribution)"
    (is (= [[:emit :warning :rf.warning/malformed-url
             {:url "/x" :reason :match-error :frame :worker}]
            [:emit :warning :rf.warning/no-not-found-route
             {:url "/x" :frame :worker}]]
           (plan/fallback-telemetry-intents
             {:throw-reason :match-error :malformed? false :no-not-found? true
              :url "/x" :frame :worker})))))

(deftest fallback-telemetry-intents-ordering-is-stable
  (testing "malformed/throw warning precedes the no-not-found warning"
    (let [intents (plan/fallback-telemetry-intents
                    {:throw-reason :match-error :malformed? false
                     :no-not-found? true :url "/x" :frame nil})]
      (is (= 2 (count intents)))
      (is (= :rf.warning/malformed-url (nth (first intents) 2)))
      (is (= :rf.warning/no-not-found-route (nth (second intents) 2))))))

;; ---- emit-intents! driver ------------------------------------------------

(deftest emit-intents-dispatches-emit-and-emit-error-shapes
  (testing "the driver routes :emit → trace/emit! and :emit-error → trace/emit-error!"
    ;; Capture by rebinding the trace fns via with-redefs.
    (let [emits       (atom [])
          emit-errors (atom [])]
      (with-redefs [re-frame.trace/emit!
                    (fn [level op tags] (swap! emits conj [level op tags]))
                    re-frame.trace/emit-error!
                    (fn [op tags] (swap! emit-errors conj [op tags]))]
        (plan/emit-intents!
          [[:emit :warning :rf.warning/malformed-url {:url "/x"}]
           [:emit-error :rf.error/no-such-handler {:url "/x" :kind :route}]]))
      (is (= [[:warning :rf.warning/malformed-url {:url "/x"}]] @emits))
      (is (= [[:rf.error/no-such-handler {:url "/x" :kind :route}]] @emit-errors)))))

;; ---- scroll-plan (pure over a runtime-db map + an explicit scroll cache) -
;;
;; rf2-1hncp2: `:saved-pos` is read from `:scroll-cache` — the frame's
;; host-side transient scroll-position cache map (`{:positions :order}`),
;; threaded in EXPLICITLY by the caller — NOT from runtime-db. `:capture-fx`
;; / `:from` still read the durable `:current` slice from `rdb`.

(deftest scroll-plan-builds-capture-and-scroll-fx
  (testing "a forward nav from an active route builds a capture-fx + a :top scroll-fx"
    ;; No registrar route → capture-fx is nil (route-url can't reconstruct
    ;; the leaving URL), but the scroll-fx is built from the resolved
    ;; strategy + descriptors. We assert the scroll-fx shape directly.
    (let [rdb  {:rf.runtime/routing {:current {:route-id :route/home}}}
          {:keys [scroll-fx]}
          (plan/scroll-plan {:rdb rdb :route-meta nil :opts nil
                             :default-strategy :top
                             :route-id :route/cart :params {} :query {}
                             :fragment nil :url "/cart"})]
      (is (vector? scroll-fx))
      (is (= :rf.nav/scroll (first scroll-fx)))
      (is (= :top (:strategy (second scroll-fx)))
          "forward default strategy is :top")
      (is (= {:id :route/cart} (:to (second scroll-fx)))
          ":to descriptor names the target route"))))

(deftest scroll-plan-suppresses-fx-on-scroll-false
  (testing ":scroll false in opts suppresses the scroll-fx (nil)"
    (let [rdb {:rf.runtime/routing {:current {:route-id :route/home}}}
          {:keys [scroll-fx]}
          (plan/scroll-plan {:rdb rdb :route-meta nil :opts {:scroll false}
                             :default-strategy :top
                             :route-id :route/cart :params {} :query {}
                             :fragment nil :url "/cart"})]
      (is (nil? scroll-fx) ":scroll false → no fx emitted"))))

(deftest scroll-plan-restore-strategy-reads-saved-position
  (testing ":restore strategy pulls the saved [x y] for the url from the
            explicit host-side scroll cache (rf2-1hncp2), NOT from runtime-db"
    (let [rdb          {:rf.runtime/routing {:current {:route-id :route/home}}}
          scroll-cache {:positions {"/cart" [0 320]} :order ["/cart"]}
          {:keys [scroll-fx]}
          (plan/scroll-plan {:rdb rdb :scroll-cache scroll-cache
                             :route-meta nil :opts {:scroll :restore}
                             :default-strategy :top
                             :route-id :route/cart :params {} :query {}
                             :fragment nil :url "/cart"})]
      (is (= :restore (:strategy (second scroll-fx))))
      (is (= [0 320] (:saved-pos (second scroll-fx)))
          "the saved scroll position for /cart is threaded into :saved-pos")))

  (testing ":restore with NO host cache (nil :scroll-cache) yields a nil
            :saved-pos — the planner does not reach a runtime-db slot"
    (let [rdb {:rf.runtime/routing {:current          {:route-id :route/home}
                                    ;; a stale runtime-db scroll slot must
                                    ;; NOT be consulted — storage moved out.
                                    :scroll-positions {"/cart" [9 9]}}}
          {:keys [scroll-fx]}
          (plan/scroll-plan {:rdb rdb :scroll-cache nil
                             :route-meta nil :opts {:scroll :restore}
                             :default-strategy :top
                             :route-id :route/cart :params {} :query {}
                             :fragment nil :url "/cart"})]
      (is (= :restore (:strategy (second scroll-fx))))
      (is (nil? (:saved-pos (second scroll-fx)))
          "no host cache → nil saved-pos; the runtime-db scroll slot is ignored"))))

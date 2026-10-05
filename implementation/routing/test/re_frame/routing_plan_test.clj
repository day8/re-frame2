(ns re-frame.routing-plan-test
  "Focused tests for the pure navigation-planning seam
  `re-frame.routing.plan`.

  The pre-commit navigation policy is shared by the programmatic
  (`:rf.route/navigate`) and URL-driven
  (`:rf.route/handle-url-change`) entry points. These tests pin the
  fragment-only classification, the fail-closed telemetry intents and the
  scroll plan directly at the seam. Fragment normalisation, the
  `:rf.route/not-found` fallback shape and `:reason` vocabulary, and the
  identical-target no-op are pinned through both doors in
  `routing-plan-seam-test` and `routing-navigation-test`.

  All functions under test are PURE — no registrar / runtime-db fixture
  needed except for `scroll-plan` (which reads a plain runtime-db map for
  the `:current` slice + an explicit host-side scroll-cache map for the
  `:saved-pos` lookup)."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.routing.plan :as rf.routing.plan]))

;; ---- fragment-only navigation (Spec 012 §Fragments rules 3-4) ------------

(deftest fragment-only-detects-same-page-anchor-change
  (let [slice {:route-id :route/docs :params {:p 1} :query {:q "a"} :fragment "intro"}]
    (testing "same id/params/query, differing fragment → fragment-only"
      (is (true? (rf.routing.plan/fragment-only? slice :route/docs {:p 1} {:q "a"} "details"))))
    (testing "identical fragment is NOT fragment-only (that's the complete no-op)"
      (is (false? (rf.routing.plan/fragment-only? slice :route/docs {:p 1} {:q "a"} "intro"))))
    (testing "a differing route-id is NOT fragment-only (full transition)"
      (is (false? (rf.routing.plan/fragment-only? slice :route/cart {:p 1} {:q "a"} "details"))))
    (testing "a differing query is NOT fragment-only (full transition)"
      (is (false? (rf.routing.plan/fragment-only? slice :route/docs {:p 1} {:q "b"} "details"))))
    (testing "no prior slice → never fragment-only (nothing to be a fragment of)"
      (is (false? (rf.routing.plan/fragment-only? nil :route/docs {:p 1} {:q "a"} "details"))))))

;; ---- fail-closed telemetry intents (parity across both entry points) -----

(deftest fallback-telemetry-intents-per-condition
  (testing "each fail-closed condition emits its own warning intent, and a
            clean match emits none. A match-url throw surfaces as
            :rf.warning/malformed-url carrying the throw :reason, whichever
            nav event it arrived on"
    (are [condition expected]
         (= expected
            (rf.routing.plan/fallback-telemetry-intents
              (merge {:throw-reason nil :malformed? false :no-not-found? false :frame nil}
                     condition)))
      {:url "/cart"}                         []
      {:url "/a%2" :malformed? true}         [[:emit :warning :rf.warning/malformed-url {:url "/a%2"}]]
      {:url "/x" :throw-reason :match-error} [[:emit :warning :rf.warning/malformed-url
                                               {:url "/x" :reason :match-error}]]
      {:url "/gone" :no-not-found? true}     [[:emit :warning :rf.warning/no-not-found-route {:url "/gone"}]])))

(deftest fallback-telemetry-intents-threads-frame-onto-every-tag
  (testing "when :frame is present it lands on every emitted tag map (epoch/Xray attribution)"
    (is (= [[:emit :warning :rf.warning/malformed-url
             {:url "/x" :reason :match-error :frame :worker}]
            [:emit :warning :rf.warning/no-not-found-route
             {:url "/x" :frame :worker}]]
           (rf.routing.plan/fallback-telemetry-intents
             {:throw-reason :match-error :malformed? false :no-not-found? true
              :url "/x" :frame :worker})))))

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
        (rf.routing.plan/emit-intents!
          [[:emit :warning :rf.warning/malformed-url {:url "/x"}]
           [:emit-error :rf.error/no-such-handler {:url "/x" :kind :route}]]))
      (is (= [[:warning :rf.warning/malformed-url {:url "/x"}]] @emits))
      (is (= [[:rf.error/no-such-handler {:url "/x" :kind :route}]] @emit-errors)))))

;; ---- scroll-plan (pure over a runtime-db map + an explicit scroll cache) -
;;
;; `:saved-pos` is read from `:scroll-cache` — the frame's
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
          (rf.routing.plan/scroll-plan {:rdb rdb :route-meta nil :opts nil
                             :default-strategy :top
                             :route-id :route/cart :params {} :query {}
                             :fragment nil :url "/cart"})]
      (is (vector? scroll-fx))
      (is (= :rf.nav/scroll (first scroll-fx)))
      (is (= :top (:strategy (second scroll-fx)))
          "forward default strategy is :top")
      (is (= {:id :route/cart} (:to (second scroll-fx)))
          ":to descriptor names the target route"))))

(deftest scroll-plan-restore-strategy-reads-saved-position
  (testing ":restore strategy pulls the saved [x y] for the url from the
            explicit host-side scroll cache, NOT from runtime-db"
    (let [rdb          {:rf.runtime/routing {:current {:route-id :route/home}}}
          scroll-cache {:positions {"/cart" [0 320]} :order ["/cart"]}
          {:keys [scroll-fx]}
          (rf.routing.plan/scroll-plan {:rdb rdb :scroll-cache scroll-cache
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
                                    ;; NOT be consulted — scroll storage is host-side.
                                    :scroll-positions {"/cart" [9 9]}}}
          {:keys [scroll-fx]}
          (rf.routing.plan/scroll-plan {:rdb rdb :scroll-cache nil
                             :route-meta nil :opts {:scroll :restore}
                             :default-strategy :top
                             :route-id :route/cart :params {} :query {}
                             :fragment nil :url "/cart"})]
      (is (= :restore (:strategy (second scroll-fx))))
      (is (nil? (:saved-pos (second scroll-fx)))
          "no host cache → nil saved-pos; the runtime-db scroll slot is ignored"))))

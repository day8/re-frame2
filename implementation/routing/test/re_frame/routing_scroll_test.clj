(ns re-frame.routing-scroll-test
  "Scroll-restoration tests for re-frame.routing: the `:rf.nav/scroll` fx the
  navigation doors emit, scroll-strategy precedence, the restore lookup's
  canonical keying, and the per-frame host-side position cache (LRU cap,
  isolation, release on frame destroy).

  All of it is production-real and carries no posture guard, so it runs in
  the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.plan :as rf.routing.plan]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- record-scroll-fx!
  "Re-register `:rf.nav/scroll` for the JVM drain, recording each call's args,
  and `:rf.nav/push-url` as a no-op: their `:platforms #{:client}` would
  otherwise skip both here. Returns the recording atom."
  []
  (let [calls (atom [])]
    (rf.fx/reg-fx :rf.nav/scroll {:platforms #{:server :client}}
                  (fn [_ args] (swap! calls conj args)))
    (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
    calls))

(deftest routing-scroll-fx-emitted-on-navigate
  ;; Spec 012 §Scroll restoration: the strategy is the per-call `:scroll`
  ;; opt, else the route's `:scroll`, else the door's default; `false`
  ;; suppresses the fx. Each step navigates somewhere new, because a
  ;; re-navigation to the current target is a no-op that emits nothing.
  (rf/reg-route :route/home     {} "/")
  (rf/reg-route :route/articles {} "/articles")
  (rf/reg-route :route/article  {:params [:map [:id :string]]
                                 :scroll :restore} "/articles/:id")
  (rf/reg-route :route/profile  {:scroll false} "/profile")
  (let [calls (record-scroll-fx!)
        nav!  (fn [event] (reset! calls []) (rf/dispatch-sync event) @calls)]
    (testing "a forward navigation defaults to :top"
      (is (= [{:strategy :top :to {:id :route/articles}}]
             (nav! [:rf.route/navigate {:to :route/articles}]))))
    (testing "the route's :scroll beats the default, and :restore reads the
              frame's saved position from the host cache"
      (rf.routing/save-scroll-position! :rf/default "/articles/intro" [0 640])
      (is (= [{:strategy  :restore
               :from      {:id :route/articles}
               :to        {:id :route/article :params {:id "intro"}}
               :saved-pos [0 640]}]
             (nav! [:rf.route/navigate {:to :route/article :params {:id "intro"}}]))))
    (testing "the per-call :scroll opt beats the route's"
      (is (= [:preserve]
             (map :strategy (nav! [:rf.route/navigate {:to     :route/article
                                                       :params {:id "two"}
                                                       :scroll :preserve}])))))
    (testing "the route's :scroll false suppresses the fx"
      (is (= [] (nav! [:rf.route/navigate {:to :route/profile}]))))
    (testing "handle-url-change defaults to :top for a link click and to
              :restore for every other cause"
      (is (= [:top]
             (map :strategy (nav! [:rf.route/handle-url-change "/" {:rf.route/cause :link}]))))
      (is (= [:restore]
             (map :strategy (nav! [:rf.route/handle-url-change "/articles"])))))
    (testing "the URL's fragment rides the fx args"
      (is (= ["section-2"]
             (map :fragment (nav! [:rf.route/handle-url-change "/articles/intro#section-2"
                                   {:rf.route/cause :link}])))))))

;; ---- the per-frame host cache ------------------------------------------------
;;
;; Positions live in a host-side atom keyed by frame-id, not in runtime-db, so
;; they never egress to trace, epoch or SSR. The cache is LRU-bounded at
;; `rf.routing/scroll-positions-cap` (50).

(deftest scroll-position-lru-eviction-past-cap
  (let [save-all (fn [cache n]
                   (reduce (fn [c i] (rf.routing/save-scroll-position c (str "/u" i) [i i]))
                           cache
                           (range n)))
        lookups  (fn [cache n]
                   (map #(rf.routing/lookup-scroll-position cache (str "/u" %)) (range n)))]
    (testing "past the cap the least-recently-saved urls are evicted"
      (is (= (concat (repeat 10 nil) (map #(vector % %) (range 10 60)))
             (lookups (save-all nil 60) 60))))
    (testing "re-saving a url promotes it, so the next eviction takes the new LRU"
      (is (= (concat [[999 999] nil] (map #(vector % %) (range 2 51)))
             (lookups (-> (save-all nil 50)
                          (rf.routing/save-scroll-position "/u0" [999 999])
                          (rf.routing/save-scroll-position "/u50" [50 50]))
                      51))))))

(deftest scroll-cache-host-side-roundtrip
  (testing "save-scroll-position! writes the frame's own host cache, and
            frame-scroll-cache reads it back without cross-frame leakage"
    (rf.routing/save-scroll-position! :frame/a "/articles" [0 250])
    (rf.routing/save-scroll-position! :frame/b "/articles" [0 999])
    (is (= [[0 250] [0 999] nil]
           (map #(rf.routing/lookup-scroll-position (rf.routing/frame-scroll-cache %) "/articles")
                [:frame/a :frame/b :frame/never])))))

(deftest scroll-cache-released-on-frame-destroy
  (rf/make-frame {:id :frame/scrollee})
  (rf.routing/save-scroll-position! :frame/scrollee "/x" [0 100])
  (rf.frame/destroy-frame! :frame/scrollee)
  (is (nil? (rf.routing/frame-scroll-cache :frame/scrollee))))

(deftest scroll-restore-canonicalises-non-canonical-popstate-url-rf2-g1i5m6
  ;; Capture keys a position under the canonical `route-url` of the leaving
  ;; slice, so restore canonicalises the incoming popstate URL the same way:
  ;; a history entry spelled `/cart/` or `?b=2&a=1` still finds its position.
  (rf/reg-route :route/cart   {} "/cart")
  (rf/reg-route :route/search {:query [:map [:a {:optional true} :string]
                                       [:b {:optional true} :string]]} "/search")
  (rf/reg-route :route/other  {} "/other")
  (let [cache   (-> nil
                    (rf.routing/save-scroll-position
                      (rf.routing/route-url {:to :route/cart :params {} :query {}})
                      [0 640])
                    (rf.routing/save-scroll-position
                      (rf.routing/route-url {:to :route/search :params {} :query {:a "1" :b "2"}})
                      [0 810]))
        restore (fn [raw-url]
                  (let [m (rf.routing/match-url raw-url)]
                    (-> (rf.routing.plan/scroll-plan {:scroll-cache cache
                                                      :opts         {:scroll :restore}
                                                      :route-id     (:route-id m)
                                                      :params       (:params m)
                                                      :query        (:query m)
                                                      :fragment     (:fragment m)
                                                      :url          raw-url})
                        :scroll-fx second :saved-pos)))]
    (is (= [[0 640] [0 810] nil]
           (map restore ["/cart/" "/search?b=2&a=1" "/other"])))))

(deftest scroll-strategy-opts-override-precedence
  (rf/reg-route :route/silent {:scroll false} "/silent")
  (rf/reg-route :route/loud   {:scroll :restore} "/loud")
  (rf/reg-route :route/custom {:scroll {:behavior :smooth :block :center}} "/custom")
  (let [calls (record-scroll-fx!)]
    (doseq [[request strategies]
            [;; an opt beats the route's `false` before suppression is considered
             [{:to :route/silent :scroll :top} [:top]]
             ;; an opt `false` suppresses despite the route's strategy
             [{:to :route/loud :scroll false} []]
             ;; the planner carries a value it does not support to the fx,
             ;; whose schema and handler reject it, rather than dropping it
             [{:to :route/custom} [{:behavior :smooth :block :center}]]]]
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate request])
      (is (= strategies (map :strategy @calls)) (pr-str request)))))

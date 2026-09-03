(ns re-frame.routing-loader-tutorial-fixture-test
  "Pins the routing tutorial's loader program (docs/routing/tutorial.md Steps 4 + 7)
  and its race-safety story (docs/routing/concepts.md §A hand-rolled async loader).

  rf2-1lu666 — two regressions in the progressive tutorial:

  1. Step 7 re-registered `:app/article` with only `:parent` + `:params`, dropping the
     Step 4 `:on-match` loader. `reg-route` → `registrar/register!` is FULL replacement
     (re_frame/registrar.cljc:586 — `assoc-in [kind id] metadata`), so the final program
     silently had no loader. `tutorial-step7-reregistration-*` pins the cumulative
     program: the loader survives the Step 7 re-registration only when `:on-match` is
     carried forward.

  2. The tutorial recommends real HTTP but the hand-rolled-loader race lesson (capture the
     nav-token, gate delivery) had been removed. `tutorial-loader-is-race-safe-*` is the
     deterministic A-load → navigate-B → late-A fixture proving A's stale reply cannot
     reach app delivery or app-db when the documented nav-token pattern is followed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- rf2-1lu666 (1): reg-route is full replacement — Step 7 keeps the loader ----

(deftest tutorial-step7-reregistration-keeps-the-loader
  (testing "the cumulative tutorial: Step 7 must carry :on-match forward or the
            Step 4 loader is silently deleted (reg-route is full replacement)"
    ;; Step 4 — the route gets its loader.
    (rf/reg-route :app/article
      {:params [:map [:id :string]] :on-match [[:app/load-article]]} "/articles/:id")
    (is (= [[:app/load-article]] (:on-match (rf/handler-meta :route :app/article)))
        "Step 4 registered the loader")

    ;; The BUG shape — Step 7 re-registers with :parent + :params but NO :on-match.
    ;; Full replacement drops the loader. This assertion documents WHY the tutorial
    ;; must repeat :on-match; it is the regression rf2-1lu666 guards.
    (rf/reg-route :app/article
      {:parent :app/articles :params [:map [:id :string]]} "/articles/:id")
    (is (nil? (:on-match (rf/handler-meta :route :app/article)))
        "re-registration without :on-match deletes the loader — full replacement")

    ;; The FIXED Step 7 — carries :on-match forward alongside :parent.
    (rf/reg-route :app/article
      {:parent   :app/articles
       :params   [:map [:id :string]]
       :on-match [[:app/load-article]]} "/articles/:id")
    (let [meta (rf/handler-meta :route :app/article)]
      (is (= :app/articles (:parent meta))
          "final registration keeps :parent")
      (is (= [[:app/load-article]] (:on-match meta))
          "final registration keeps the Step 4 loader"))))

;; ---- rf2-1lu666 (2): the hand-rolled nav-token loader is race-safe ----

(deftest tutorial-loader-is-race-safe-late-A-cannot-overwrite-B
  (testing "A-load → navigate B → late-A: the documented nav-token loader suppresses
            A's stale reply; only B reaches app delivery and app-db"
    (rf/reg-route :app/article {:params [:map [:id :string]]} "/articles/:id")
    (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))

    ;; The concepts.md §A hand-rolled async loader program, verbatim in shape:
    ;; terminal delivery …
    (rf/reg-event :app/article-loaded
      (fn [{:keys [db]} [_ _id payload]]
        {:db (assoc db :article/current payload)}))
    ;; … completion gates the reply on the captured token …
    (rf/reg-event :app/article-arrived
      (fn [_ [_ captured-token id payload]]
        {:fx [[:rf.route/with-nav-token
               {:rf/reply-to [:app/article-loaded id payload]
                :nav-token   captured-token}]]}))

    (let [captured (atom {})]
      ;; … and the loader captures the live epoch token at scheduling time. The
      ;; capture closes over `captured` so the test can replay A's reply LATE
      ;; (out of order), modelling the real click-away race.
      (rf/reg-event :app/load-article
        {:rf.cofx/requires [:rf.route/nav-token]}
        (fn [{:rf.route/keys [nav-token] rt :rf.db/runtime} _]
          (let [{:keys [id]} (get-in rt [:rf.runtime/routing :current :params])]
            (swap! captured assoc id nav-token)
            {})))

      ;; 1. Open A; loader captures A's token.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/A"])
      (rf/dispatch-sync [:app/load-article])
      ;; 2. Navigate to B BEFORE A's reply lands; loader captures B's token.
      (rf/dispatch-sync [:rf.route/transitioned "/articles/B"])
      (rf/dispatch-sync [:app/load-article])
      ;; 3. A's reply lands LATE, carrying A's stale token → suppressed.
      (rf/dispatch-sync [:app/article-arrived (@captured "A") "A" "A-payload"])
      ;; 4. B's reply lands, carrying the fresh token → delivered.
      (rf/dispatch-sync [:app/article-arrived (@captured "B") "B" "B-payload"])

      (is (not= (@captured "A") (@captured "B"))
          "each navigation minted a distinct epoch token")
      (is (every? some? (vals @captured))
          "the cofx injected non-nil tokens (a nil token would mismatch every time)")
      (is (= "B-payload" (:article/current (rf/app-db-value :rf/default)))
          "only B reached app-db — A's late reply was suppressed by the nav-token"))))

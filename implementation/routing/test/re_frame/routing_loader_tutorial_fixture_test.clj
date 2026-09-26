(ns re-frame.routing-loader-tutorial-fixture-test
  "Pins the routing tutorial's loader route (docs/routing/tutorial.md Steps 4 + 7)
  and the hand-rolled async loader in docs/routing/concepts.md §Advanced. The
  routes, params and events use the docs' own shapes; the docs are not read at
  test time, so a change to either sample must be copied here by hand.

  1. Step 7 re-registers `:app/article` with `:parent` + `:params`. `reg-route` is
     FULL replacement of the route's metadata, so a Step 7 that dropped the Step 4
     `:on-match` loader would leave the final program silently without one.
     `tutorial-step7-reregistration-*` pins the cumulative program: the loader
     survives the Step 7 re-registration only when `:on-match` is carried forward.

  2. `concepts-hand-rolled-loader-*` is the deterministic A-load → navigate-B →
     late-A fixture: with the documented nav-token capture and the
     `:rf.route/with-nav-token` `:value` delivery, A's stale reply never reaches
     `:app/article-loaded` or app-db, and B's does."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- (1) reg-route is full replacement — Step 7 keeps the loader -------------

(deftest tutorial-step7-reregistration-keeps-the-loader
  (testing "the cumulative tutorial: Step 7 must carry :on-match forward or the
            Step 4 loader is silently deleted (reg-route is full replacement)"
    ;; Step 4 — the route gets its loader.
    (rf/reg-route :app/article
      {:params   [:map [:slug :string]]
       :on-match [[:app/load-article]]}
      "/articles/:slug")
    (is (= [[:app/load-article]] (:on-match (rf/handler-meta {:source :store :kind :route :id :app/article})))
        "Step 4 registered the loader")

    ;; The BROKEN shape — Step 7 re-registers with :parent + :params but NO :on-match.
    ;; Full replacement drops the loader. This assertion documents WHY the tutorial
    ;; must repeat :on-match.
    (rf/reg-route :app/article
      {:parent :app/articles :params [:map [:slug :string]]} "/articles/:slug")
    (is (nil? (:on-match (rf/handler-meta {:source :store :kind :route :id :app/article})))
        "re-registration without :on-match deletes the loader — full replacement")

    ;; The CORRECT Step 7 — carries :on-match forward alongside :parent.
    (rf/reg-route :app/articles {} "/articles")
    (rf/reg-route :app/article  {:parent   :app/articles
                                 :params   [:map [:slug :string]]
                                 :on-match [[:app/load-article]]}
      "/articles/:slug")
    (let [meta (rf/handler-meta {:source :store :kind :route :id :app/article})]
      (is (= :app/articles (:parent meta))
          "final registration keeps :parent")
      (is (= [[:app/load-article]] (:on-match meta))
          "final registration keeps the Step 4 loader"))))

;; ---- (2) the hand-rolled nav-token loader is race-safe ----

(deftest concepts-hand-rolled-loader-late-A-cannot-overwrite-B
  (testing "A-load → navigate B → late-A: the documented nav-token loader suppresses
            A's stale reply; only B reaches :app/article-loaded and app-db"
    (rf/reg-route :app/article
      {:params   [:map [:slug :string]]
       :on-match [[:app/load-article]]}
      "/articles/:slug")
    (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))

    ;; `:app/fetch-article` stands in for the app's async effect: it records each
    ;; reply event so the test can deliver A's reply LATE, after B's navigation,
    ;; modelling the real click-away race.
    (let [replies (atom {})]
      (rf.fx/reg-fx :app/fetch-article {:platforms #{:server :client}}
        (fn [_ {:keys [slug on-reply]}]
          (swap! replies assoc slug on-reply)))

      ;; The concepts.md hand-rolled async loader, verbatim.
      (rf/reg-event :app/load-article
        {:rf.cofx/requires [:rf.route/nav-token]}
        (fn [{:rf.route/keys [nav-token] rt :rf.db/runtime} _]
          (let [{:keys [slug]} (get-in rt [:rf.runtime/routing :current :params])]
            {:fx [[:app/fetch-article {:slug slug :on-reply [:app/article-arrived nav-token slug]}]]})))

      (rf/reg-event :app/article-arrived
        (fn [_ [_ captured-token slug payload]]
          {:fx [[:rf.route/with-nav-token
                 {:rf/reply-to [:app/article-loaded slug]
                  :nav-token   captured-token
                  :value       payload}]]}))

      (rf/reg-event :app/article-loaded
        (fn [{:keys [db]} [_ _slug {:keys [value]}]]
          {:db (assoc db :article/current value)}))

      ;; 1. Open A; its :on-match loader captures A's token.
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles/A" {:rf.route/cause :link}])
      ;; 2. Navigate to B BEFORE A's reply lands; the loader captures B's token.
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles/B" {:rf.route/cause :link}])
      (let [token-of (fn [slug] (second (@replies slug)))]
        (is (every? some? [(token-of "A") (token-of "B")])
            "the cofx injected non-nil tokens (a nil token would mismatch every time)")
        (is (not= (token-of "A") (token-of "B"))
            "each navigation minted a distinct token"))

      ;; 3. A's reply lands LATE, carrying A's stale token → suppressed.
      (rf/dispatch-sync (conj (@replies "A") "A-payload"))
      (is (nil? (:article/current (rf/app-db-value :rf/default)))
          "A's late reply was suppressed by the nav-token")

      ;; 4. B's reply lands, carrying the live token → delivered as :value.
      (rf/dispatch-sync (conj (@replies "B") "B-payload"))
      (is (= "B-payload" (:article/current (rf/app-db-value :rf/default)))
          "B's reply reached app-db through the reply map's :value"))))

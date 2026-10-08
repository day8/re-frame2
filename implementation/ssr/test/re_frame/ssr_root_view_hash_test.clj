(ns re-frame.ssr-root-view-hash-test
  "The render hash covers what the ROOT VIEW RETURNS, and nothing it merely
  references: `render-tree-hash` never calls a view it finds inside a tree, so
  a root whose whole body is `[(rf/view :page)]` hashes to one constant and a
  divergent client render through it verifies clean. Read off the always-on
  `:on-mismatch :hard-error` throw, so it holds in either posture."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private server-articles
  [{:id "a" :title "Article A"}
   {:id "b" :title "Article B"}])

(defn- register-app! []
  (rf/reg-event :test/articles-loaded
    (fn [{:keys [db]} [_ articles]] {:db (assoc db :articles articles)}))
  (rf/reg-sub :test/articles (fn [db _] (:articles db)))
  (rf/reg-view ^{:rf/id :test.page/articles} articles-page []
    (into [:ul.articles]
          (for [{:keys [id title]} @(subscribe [:test/articles])]
            ^{:key id} [:li title])))
  (rf/reg-view ^{:rf/id :test.root/delegating} delegating-root []
    [(rf/view :test.page/articles)])
  (rf/reg-view ^{:rf/id :test.root/content} content-root []
    (into [:ul.articles]
          (for [{:keys [id title]} @(subscribe [:test/articles])]
            ^{:key id} [:li title]))))

(defn- root-hash
  "The render hash of `root-id` against `frame-id`, taken as the server takes it."
  [root-id frame-id]
  (rf/with-frame frame-id
    (rf.ssr/render-tree-hash ((rf/view root-id)))))

(defn- hydrate-strict!
  "Hydrate a fresh `:hard-error` client frame from `payload`, verifying through
  `root-id`. -> `:verified`, or the thrown mismatch's ex-data."
  [root-id payload]
  (let [client (rf.frame/make-anon-frame-record!
                 {:platform :client
                  :ssr      {:on-mismatch :hard-error}})]
    (try
      (rf.ssr/hydrate! {:frame          client
                        :payload        payload
                        :render-tree-fn (fn [] ((rf/view root-id)))})
      :verified
      (catch clojure.lang.ExceptionInfo e
        (ex-data e)))))

(deftest a-planted-divergence-trips-the-mismatch-only-through-a-content-root
  (testing "the server renders two articles and the payload carries one"
    (register-app!)
    (let [server  (rf.frame/make-anon-frame-record! {:platform :server})
          _       (rf/dispatch-sync [:test/articles-loaded server-articles] {:frame server})
          payload (fn [root-id articles]
                    {:rf/version     1
                     :rf/app-db      {:articles articles}
                     :rf/render-hash (root-hash root-id server)})
          planted (subvec server-articles 0 1)]
      (is (= :verified (hydrate-strict! :test.root/content
                                        (payload :test.root/content server-articles)))
          "control: a faithful payload verifies clean")
      (is (= {:rf.error/id :rf.ssr/hydration-mismatch
              :server-hash (root-hash :test.root/content server)}
             (select-keys (hydrate-strict! :test.root/content
                                           (payload :test.root/content planted))
                          [:rf.error/id :server-hash]))
          "a content root catches the divergence")
      (is (= :verified (hydrate-strict! :test.root/delegating
                                        (payload :test.root/delegating planted)))
          "a delegating root does not"))))

(ns re-frame.ssr-root-view-hash-test
  "The render hash covers what the ROOT VIEW RETURNS, and nothing it merely
  references.

  `render-tree-hash` walks the tree as data and never calls a view it finds
  inside: every callable head serialises to one identity-free token
  (`re-frame.ssr.hash/canonical-edn-into`). So a root whose whole body is
  another view, `[(rf/view :page)]`, hashes to one constant for every app
  state, and a client whose first render differs from the server's still
  verifies clean. A root that returns the page's elements itself hashes the
  state it renders, so the same divergence trips `:rf.ssr/hydration-mismatch`.

  Both halves hold in either posture. The hashes are ungated, and the
  mismatch is read off the `:on-mismatch :hard-error` throw, which is
  always-on (`re-frame.ssr-hydration-mismatch-test` lays out the channels)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private server-articles
  [{:id "a" :title "Article A"}
   {:id "b" :title "Article B"}])

(defn- register-app!
  "One page of state and two candidate roots over it, each written the way an
  SSR app writes its root: `:test.root/delegating` hands the whole page to
  another view, `:test.root/content` returns the page's elements itself."
  []
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

(defn- frame-with-articles!
  "A fresh anonymous `platform` frame holding `articles`. Returns its id."
  [platform articles]
  (let [frame-id (rf.frame/make-anon-frame-record! {:platform platform})]
    (rf/dispatch-sync [:test/articles-loaded articles] {:frame frame-id})
    frame-id))

(defn- root-hash
  "The render hash of `root-id` against `frame-id`, taken the way the server
  takes it and the client's `:render-tree-fn` retakes it: the root view
  called, `((rf/view root-id))`."
  [root-id frame-id]
  (rf/with-frame frame-id
    (rf.ssr/render-tree-hash ((rf/view root-id)))))

(defn- root-html [root-id frame-id]
  (rf/with-frame frame-id
    (rf.ssr/render-to-string [(rf/view root-id)] {})))

(defn- hydrate-strict!
  "Boot a fresh `:on-mismatch :hard-error` client frame from `payload`,
  verifying through `root-id`. Returns `:verified` when the check passes, or
  the thrown mismatch's ex-data."
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

(deftest a-delegating-root-hashes-one-constant-for-every-state
  (testing "Two frames holding different articles render different pages
            through either root, but only the root that returns the page's
            elements itself hashes them differently."
    (register-app!)
    (let [one (frame-with-articles! :server [{:id "a" :title "Article A"}])
          two (frame-with-articles! :server [{:id "z" :title "Article Z"}
                                             {:id "y" :title "Article Y"}])]
      (is (not= (root-html :test.root/delegating one)
                (root-html :test.root/delegating two))
          "precondition: the two states render different pages")
      (is (= (root-hash :test.root/delegating one)
             (root-hash :test.root/delegating two))
          "a root whose body is another view hashes the reference, not the page")
      (is (not= (root-hash :test.root/content one)
                (root-hash :test.root/content two))
          "a root that returns element content hashes the state it renders"))))

(deftest a-planted-divergence-trips-the-mismatch-only-through-a-content-root
  (testing "The server renders two articles and the payload carries one, so
            the client's first render diverges from the server's. A root that
            returns element content catches it; a root that delegates to
            another view verifies clean."
    (register-app!)
    (let [server  (frame-with-articles! :server server-articles)
          payload (fn [root-id articles]
                    {:rf/version     1
                     :rf/app-db      {:articles articles}
                     :rf/render-hash (root-hash root-id server)})
          planted (subvec server-articles 0 1)]
      (testing "control: a faithful payload verifies clean through either root"
        (is (= :verified (hydrate-strict! :test.root/content
                                          (payload :test.root/content server-articles))))
        (is (= :verified (hydrate-strict! :test.root/delegating
                                          (payload :test.root/delegating server-articles)))))
      (let [thrown (hydrate-strict! :test.root/content
                                    (payload :test.root/content planted))]
        (is (= :rf.ssr/hydration-mismatch (:rf.error/id thrown))
            "the divergent client render trips the mismatch")
        (is (= (root-hash :test.root/content server) (:server-hash thrown))
            "the comparison ran against the server's own render hash"))
      (is (= :verified (hydrate-strict! :test.root/delegating
                                        (payload :test.root/delegating planted)))
          "the same divergence through a delegating root goes undetected"))))

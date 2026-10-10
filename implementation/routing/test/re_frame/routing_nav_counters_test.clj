(ns re-frame.routing-nav-counters-test
  "Host-side nav-token / pending-nav counters.

  The two monotonic routing allocators (`:nav-token-counter` /
  `:pending-nav-counter`) live OUTSIDE the `[:rf.runtime/routing ...]`
  runtime-db partition, in a host-side per-frame cache
  (`re-frame.routing.nav-counters`). An epoch restore replaces the runtime-db
  partition wholesale, so a counter held there would rewind and recycle a
  token an in-flight continuation might still carry; held host-side it is a
  high-water mark restore cannot touch.

  This namespace pins:
    - restore-then-navigate: after an epoch restore rewinds the route slice,
      the next navigation mints a token past every pre-restore one;
    - `:rf/pending-navigation` stays subscribable while its id comes from the
      host counter;
    - the SSR durable-routing allowlist equals the routing classification's
      durable tier;
    - a frame destroyed and re-made under the same id mints past every token
      its predecessor issued;
    - allocator storage stays constant however many frames come and go."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.routing.nav-counters :as rf.routing.nav-counters]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(deftest restore-then-navigate-allocates-fresh-token-from-host-cache
  (testing "an epoch restore rewinds the route slice but not the host high-water
            mark, so the next navigation mints nav-4 and recycles no pre-restore token"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (doseq [id ["A" "B" "C"]]
      (rf/dispatch-sync [:rf.route/handle-url-change (str "/articles/" id) {:rf.route/cause :link}]))
    ;; The runtime-db as it stood at nav-1, plus a stale `:nav-token-counter 1`
    ;; shaped as though the counter lived in runtime-db: an allocator that
    ;; read it would mint nav-2.
    (rf.frame/replace-runtime-db! :rf/default
                                  {:rf.runtime/routing {:current {:route-id   :route/article
                                                                  :params     {:id "A"}
                                                                  :query      {}
                                                                  :fragment   nil
                                                                  :transition :idle
                                                                  :error      nil
                                                                  :nav-token  "nav-1"}
                                                        :nav-token-counter 1}})
    (rf/dispatch-sync [:rf.route/handle-url-change "/articles/D" {:rf.route/cause :link}])
    (is (= "nav-4" (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                           [:rf.runtime/routing :current :nav-token])))))

(deftest pending-navigation-subscribable-and-pending-nav-counter-host-side
  (testing "a blocked navigation is readable through :rf/pending-navigation, its
            id minted from the host counter (the live navigation before it mints none)"
    (rf/reg-route :route/editor {:can-leave [:editor/can-leave?]} "/editor")
    (rf/reg-route :route/home {} "/home")
    (rf/reg-sub :editor/can-leave? (fn [_ _] false))
    (rf/dispatch-sync [:rf.route/handle-url-change "/editor" {:rf.route/cause :link}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/home"}])
    (is (= "pn-1" (:id (rf/subscribe-once [:rf/pending-navigation] {:frame :rf/default}))))))

(deftest routing-classification-is-single-source-of-truth-for-ssr-allowlist
  (testing "SSR holds its durable-routing allowlist as a literal (it must not
            require routing); it must equal the routing classification's durable tier"
    (is (= (vec rf.ssr.payload-policy/durable-routing-keys)
           (vec rf.routing.nav-counters/durable-runtime-db-routing-keys)))))

(defn- token-number [token] (Long/parseLong (subs token (count "nav-"))))

(deftest same-id-successor-mints-past-its-predecessor-tokens
  (testing "a frame destroyed and re-made under the same id mints tokens its
            predecessor never issued, so the predecessor's keyword-addressed
            continuation is suppressed as stale rather than delivered"
    (rf/reg-route :r/a {} "/a")
    (rf/reg-route :r/b {} "/b")
    (rf/reg-event :app/loaded (fn [{:keys [db]} [_ reply]] {:db (assoc db :loaded reply)}))
    (rf/reg-event :app/deliver
                  (fn [_ [_ token]]
                    {:fx [[:rf.route/with-nav-token {:nav-token   token
                                                     :value       :predecessor-result
                                                     :rf/reply-to [:app/loaded]}]]}))
    (let [tour!       (fn []
                        (rf/make-frame {:id :f})
                        (mapv (fn [to]
                                (rf/dispatch-sync [:rf.route/navigate {:to to}] {:frame :f})
                                (get-in (:rf.db/runtime (rf/frame-state-value :f))
                                        [:rf.runtime/routing :current :nav-token]))
                              [:r/a :r/b]))
          predecessor (tour!)
          _           (rf.frame/destroy-frame! :f)
          successor   (tour!)
          traces      (atom [])]
      (rf/register-listener! :trace ::successor-stale (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:app/deliver (peek predecessor)] {:frame :f})
      (rf/unregister-listener! :trace ::successor-stale)
      (is (= [#{} true nil]
             [(set/intersection (set predecessor) (set successor))
              (< (apply max (map token-number predecessor))
                 (apply min (map token-number successor)))
              (:loaded (rf/app-db-value :f))])
          (str "predecessor " predecessor ", successor " successor))
      (when rf.interop/debug-enabled?
        (is (some #{:rf.route.nav-token/stale-suppressed} (map :operation @traces))
            "the predecessor's continuation took the suppression path")))))

(deftest destroying-frames-leaves-allocator-storage-constant
  (testing "allocator storage does not grow with the frames a process creates and
            destroys, so a per-request-frame server leaks nothing per frame"
    (rf/reg-route :route/s {} "/s")
    (let [cycle!     (fn [n]
                       (let [id (keyword "rf.test" (str "scratch-" n))]
                         (rf/make-frame {:id id})
                         (rf/dispatch-sync [:rf.route/navigate {:to :route/s}] {:frame id})
                         (rf.frame/destroy-frame! id)
                         @rf.routing.nav-counters/nav-counters-cache))
          after-one  (cycle! 0)
          after-many (last (mapv cycle! (range 1 50)))]
      (is (= [#{:nav-token-counter} #{:nav-token-counter}]
             [(set (keys after-one)) (set (keys after-many))])))))

(ns re-frame.routing-same-document-url-cljs-test
  "A pure `?query` or `#fragment` `{:url …}` reference resolves against the
  navigating frame's current route on every host, the JVM and CLJS without a
  browser `Location` included (Spec 012 §App references and browser
  addresses). Named `*-cljs-test.cljc` so the shadow-cljs `:node-test` build,
  which has no `window`, runs it beside the JVM runner; the browser arm is
  pinned in routing_history_cljs_test.cljs.

  Every assertion reads the route slice, so all of them hold in both
  postures."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.routing :as rf.routing]
   ;; `:rf.route/navigate`'s recordable coeffect declares a `:schema`, so a
   ;; navigation throws unless the schemas artefact is loaded.
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn rf.routing/reset-counters!}))

(defn- current []
  (select-keys (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                       [:rf.runtime/routing :current])
               [:route-id :params :query :fragment]))

(deftest same-document-references-keep-the-current-route
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :route/article {} "/articles/:id")
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "7"} :fragment "top"}])
  (testing "?query keeps the path, replaces the query and drops the fragment"
    (rf/dispatch-sync [:rf.route/navigate {:url "?tab=2"}])
    (is (= {:route-id :route/article :params {:id "7"} :query {"tab" "2"} :fragment nil}
           (current))))
  (testing "#fragment keeps the path and the query"
    (rf/dispatch-sync [:rf.route/navigate {:url "#sec"}])
    (is (= {:route-id :route/article :params {:id "7"} :query {"tab" "2"} :fragment "sec"}
           (current))))
  (testing "control: a rooted path resolves to its own route"
    (rf/dispatch-sync [:rf.route/navigate {:url "/articles/8"}])
    (is (= {:route-id :route/article :params {:id "8"}}
           (select-keys (current) [:route-id :params])))))

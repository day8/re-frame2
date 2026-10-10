(ns reagent2.dom.server-subscribe-ssr-cljs-test
  "A SUBSCRIBING reg-view through the slim `render-to-static-markup`, modelled
  on the `examples/substrates/reagent_slim/counter` boot dataflow. The first
  subscribe registers its dispose through interop's late-bind hooks, and the
  SSR deref takes the slim ratom's non-reactive branch; no other slim SSR test
  renders a subscription, so this is the only cover for either."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            [reagent2.dom.server :as server])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

(defn- register-counter! []
  (rf/reg-event :counter/initialise
    (fn [{:keys [db]} _event] {:db {:counter/value 5}}))
  (rf/reg-event :counter/inc
    (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))
  (rf/reg-sub :counter/value
    (fn [db _query] (:counter/value db)))
  (reg-view counter-buttons []
    [:div
     [:button {:on-click #(dispatch [:counter/dec])} "-"]
     [:span {:data-testid "counter-value"} @(subscribe [:counter/value])]
     [:button {:on-click #(dispatch [:counter/inc])} "+"]])
  (reg-view counter-app []
    [counter-buttons])
  ;; Form-2: the outer body runs once as setup and returns the live render
  ;; closure, which the static renderer must call rather than recurse on.
  (reg-view counter-buttons-f2 []
    (fn []
      [:div
       [:span {:data-testid "counter-value"} @(subscribe [:counter/value])]]))
  (reg-view counter-app-f2 []
    [counter-buttons-f2]))

(deftest ssr-deref-reads-live-app-db-not-a-frozen-value
  (register-counter!)
  (doseq [[form app] [["Form-1" counter-app] ["Form-2" counter-app-f2]]]
    (testing (str form ": a re-render after [:counter/inc] reads live app-db,
                  not a value frozen at first build")
      (rf/dispatch-sync [:counter/initialise])
      (is (re-find #">5<" (server/render-to-static-markup [app])))
      (rf/dispatch-sync [:counter/inc])
      (let [after (server/render-to-static-markup [app])]
        (is (and (re-find #">6<" after) (not (re-find #">5<" after)))
            (str "got: " (pr-str after)))))))

;; `[rf/frame-provider {:frame f} [app]]` is the mount the guides teach for
;; HTML export. It expands to an `:r>` Provider head, which a static walker
;; treating as opaque foreign content would render as a placeholder comment,
;; silently dropping the whole app.

(deftest frame-provider-mount-matches-the-unwrapped-render
  (testing "a frame-provider renders no markup of its own"
    (register-counter!)
    (rf/dispatch-sync [:counter/initialise])
    (let [bare     (server/render-to-static-markup [counter-app])
          provided (server/render-to-static-markup
                    [rf/frame-provider {:frame :rf/default}
                     [counter-app]])]
      (is (re-find #">5<" bare) "precondition: the bare render has content")
      (is (= bare provided)))))

;; The walker applies no Provider's value, so a view under a frame-provider
;; resolves its frame through the ambient `with-frame`. A frame-provider naming
;; another frame is refused rather than rendered against the ambient one.

;; Other namespaces in the test build register `:counter/*` ids too, so the
;; second frame's image selects this namespace's registrations alone.
(def ^:private counter-image
  (rf/image {:id        :static/counter
             :select-ns {:include ["reagent2.dom.server-subscribe-ssr-cljs-test"]}}))

(deftest frame-provider-naming-another-frame-is-refused
  (register-counter!)
  (rf/make-frame {:id :static/other :images [counter-image]})
  (rf/dispatch-sync [:counter/initialise])
  (let [data (try (server/render-to-static-markup
                   [rf/frame-provider {:frame :static/other} [counter-app]])
                  nil
                  (catch :default e (ex-data e)))]
    (is (= :rf.error/static-markup-provider-frame (:rf.error/id data)))
    (is (= :static/other (:frame data)))
    (is (= :rf/default (:ambient-frame data)))
    (is (= :export-under-the-provided-frame (:recovery data))))
  (testing "under the provided frame, the export renders that frame's state"
    (rf/with-frame :static/other
      (rf/dispatch-sync [:counter/initialise])
      (rf/dispatch-sync [:counter/inc])
      (is (re-find #">6<" (server/render-to-static-markup
                           [rf/frame-provider {:frame :static/other}
                            [counter-app]]))))))

(deftest frame-provider-mount-renders-multiple-children
  (testing "every one of a frame-provider's variadic children reaches the
            markup, in order"
    (register-counter!)
    (rf/dispatch-sync [:counter/initialise])
    (let [markup (server/render-to-static-markup
                  [rf/frame-provider {:frame :rf/default}
                   [:h1 "header"]
                   [counter-app]
                   [:footer "foot"]])]
      (is (re-find #"<h1>header</h1>.*>5<.*<footer>foot</footer>" markup)
          (str "got: " (pr-str markup))))))

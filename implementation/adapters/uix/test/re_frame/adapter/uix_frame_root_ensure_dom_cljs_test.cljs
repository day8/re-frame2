(ns re-frame.adapter.uix-frame-root-ensure-dom-cljs-test
  "Native frame-root preserves keyword and nested configuration values through UIx $ marshalling."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom/client" :as react-dom-client]
            [uix.core :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.react-test-support :refer [browser? react-act]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter}))

(def ^:private ensure-observed (atom []))

(defui ProbeEnsure []
  (let [v (rf.adapter.uix/use-sub [:rf.uix-ensure/k])]
    (swap! ensure-observed conj v)
    ($ :div (str "k=" v))))

;; An inline image exercises the nested CLJS value passed through $.
(def ^:private ensure-image
  (rf/image
    {:id :rf.uix-ensure/image
     :registrations
     {:reg-event [[:rf.uix-ensure/seed
                   (fn [{:keys [db]} [_ v]] {:db (assoc db :img v)})]]}}))

(deftest ensure-id-arm-marshals-config-through-dollar
  (testing "UIx — ($ frame-root {:id .. :initial-events [[..]] :url-bound? ..}) ENSURE arm marshals config through $/glue-args"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (react-act)]
        (binding [rf.frame/*current-frame* nil]
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
          (reset! ensure-observed [])
          (rf/reg-sub :rf.uix-ensure/k (fn [db _] (:k db)))
          (let [frame-kw   :rf.uix-ensure/frame
                mount-node (.createElement js/document "div")
                root       (react-dom-client/createRoot mount-node)]
            (try
              (act-fn
                (fn []
                  (.render root
                    ($ rf.adapter.uix/frame-root
                       {:id             frame-kw
                        :initial-events [[:rf/set-db {:k :ensured}]]
                        :url-bound?     false}
                       ($ ProbeEnsure)))))
              (is (= [true {:k :ensured} true]
                     [(some? (rf.frame/frame frame-kw))
                      (rf/app-db-value frame-kw)
                      (boolean (some #{:ensured} @ensure-observed))])
                  "[live-frame? app-db descendant-read?]: the keyword :id, the nested :initial-events seed and the provided context all survived $/glue-args")
              (finally
                (try (.unmount root) (catch :default _ nil))))))))))

(deftest ensure-images-arm-marshals-image-vector-through-dollar
  (testing "UIx — ($ frame-root {:id .. :images [img] :initial-events [[..]]}) ENSURE :images vector marshals through $/glue-args"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (react-act)]
        (binding [rf.frame/*current-frame* nil]
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
          (let [frame-kw   :rf.uix-ensure/image-frame
                mount-node (.createElement js/document "div")
                root       (react-dom-client/createRoot mount-node)]
            (try
              (act-fn
                (fn []
                  (.render root
                    ($ rf.adapter.uix/frame-root
                       {:id             frame-kw
                        :images         [ensure-image]
                        :initial-events [[:rf.uix-ensure/seed :img-ensured]]}
                       ($ :div "ensure-images-child")))))
              (is (= [true :img-ensured]
                     [(some? (rf.frame/frame frame-kw)) (:img (rf/app-db-value frame-kw))])
                  ":images [inline-image] survived $ marshalling — the image's inline :reg-event seeded the ensured frame")
              (finally
                (try (.unmount root) (catch :default _ nil))))))))))

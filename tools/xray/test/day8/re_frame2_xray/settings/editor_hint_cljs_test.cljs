(ns day8.re-frame2-xray.settings.editor-hint-cljs-test
  "CLJS test for the open-in-editor hint toast's Open-Settings wiring.
  The toast's gate, paint and dismiss are the browser lane's
  `settings_fresco_boundary_dom_cljs_test` W3 row.

  Uses the map-shape `use-fixtures` `cljs.test/async` requires: the
  `:rf.xray/editor-hint-open-settings` event-fx opens Settings through a
  queued `:dispatch` fx."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :async?     true
     :post-reset (fn []
                   (registry/register-xray-handlers!)
                   (rf/make-frame {:id :rf/xray}))}))

(deftest open-settings-opens-popup-on-general-and-dismisses
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/editor-hint-show]))
  (is (true? (boolean (:editor-hint-open? (rf/app-db-value :rf/xray))))
      "toast is open before Open-Settings")
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/editor-hint-open-settings]))
  (async done
    (-> (rf.test-support/poll-until
          #(true? (boolean (:settings-open? (rf/app-db-value :rf/xray))))
          {:label "settings popup opens after editor-hint-open-settings"
           :timeout-ms 1000})
        (.then (fn [_]
                 (let [db (rf/app-db-value :rf/xray)]
                   ;; Settings lands on General, the editor picker's home.
                   (is (= [false :general]
                          [(boolean (:editor-hint-open? db)) (:settings-active-tab db)])
                       "the toast is dismissed and Settings is open on General"))))
        (.catch (fn [e] (is false (.-message e)) nil))
        (.then (fn [_] (done))))))

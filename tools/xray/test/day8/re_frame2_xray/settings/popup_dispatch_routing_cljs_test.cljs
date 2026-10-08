(ns day8.re-frame2-xray.settings.popup-dispatch-routing-cljs-test
  "Click-time routing for the Settings popup's three close affordances.

  A browser click fires after render, when React has popped the frame
  context, so every deferred handler dispatches through the dispatcher
  captured at render time. Each row plucks a handler off the rendered
  tree and fires it OUTSIDE any `with-frame`. The fixture binds
  `:rf/default` as an ambient scope, so a handler that ignored the
  captured dispatcher would reduce `:rf/default` here (the browser, with
  no ambient scope, raises `:rf.error/no-frame-context`). The tab button's
  click through the production boundary is the browser lane's
  `settings_fresco_boundary_dom_cljs_test` W2 row.

  A separate ns from `popup_cljs_test` because `cljs.test/async` needs the
  map-shape fixture."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.modal-trees :as modal-trees]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :async?     true
     :post-reset (fn []
                   (registry/register-xray-handlers!)
                   (rf/make-frame {:id :rf/xray}))}))

(defn- fake-event []
  #js {:preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- fake-key-event [key]
  #js {:key             key
       :preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- open-modal-handler
  "Open the modal and answer the `handler-key` handler of the node
  carrying `testid`."
  [testid handler-key]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/settings-open]))
  (-> (rf/with-frame :rf/xray (modal-trees/settings-popup-tree))
      (rf.test-helpers/find-by-testid testid)
      second
      handler-key))

(defn- await-close
  "A promise settling once the queued close has reduced `:rf/xray`'s db;
  it then checks the dispatch did not land on `:rf/default`."
  [label]
  (-> (rf.test-support/poll-until
        #(false? (boolean (:settings-open? (rf/app-db-value :rf/xray))))
        {:label label :timeout-ms 1000})
      (.then (fn [_]
               (is (nil? (:settings-open? (rf/app-db-value :rf/default)))
                   ":rf/default's db is NOT polluted by the dispatch")))
      (.catch (fn [e] (is false (.-message e)) nil))))

(deftest x-button-click-closes-modal-from-default-frame-context
  ((open-modal-handler "rf-xray-settings-close" :on-click) (fake-event))
  (async done
    (.then (await-close "settings-open? flips false after X click")
           (fn [_] (done)))))

(deftest backdrop-click-closes-modal-from-default-frame-context
  ((open-modal-handler "rf-xray-settings-backdrop" :on-click) (fake-event))
  (async done
    (.then (await-close "settings-open? flips false after backdrop click")
           (fn [_] (done)))))

(deftest esc-keydown-closes-modal-from-default-frame-context
  ((open-modal-handler "rf-xray-settings-dialog" :on-key-down) (fake-key-event "Escape"))
  (async done
    (.then (await-close "settings-open? flips false after Esc")
           (fn [_] (done)))))

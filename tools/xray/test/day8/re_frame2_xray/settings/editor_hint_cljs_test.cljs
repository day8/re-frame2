(ns day8.re-frame2-xray.settings.editor-hint-cljs-test
  "CLJS tests for the open-in-editor 'pick an editor in Settings' hint
  toast (rf2-4s08ov).

  Asserts:
  - `:rf.xray/editor-hint-show` / `-dismiss` flip the
    `:rf.xray/editor-hint-open?` sub.
  - `Toast` short-circuits to nil when closed, renders the toast +
    'Open Settings' button when open.
  - `:rf.xray/editor-hint-open-settings` opens the Settings popup
    (General tab) and dismisses the toast.

  Uses the explicit map-shape `use-fixtures` (per the `cljs.test/async`
  requirement — the open-settings test drains the async router queue
  that the `:rf.xray/editor-hint-open-settings` event-fx's `:dispatch`
  fx feeds), mirroring `popup_dispatch_routing_cljs_test.cljs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.editor-hint :as editor-hint]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; `make-xray-runtime-fixture` (rf2-vj80u8) composes core
;; `make-reset-runtime-fixture` (snapshot/restore + frames-reset + adapter
;; dispose/install) with Xray's own reset tier. `:tier :runtime` folds the
;; sentinel + trace-collector + persisted-settings reset; `:async? true` is the
;; map-form cljs.test/async requires; `:post-reset` re-registers Xray's
;; :rf.xray/* handlers + the :rf/xray frame (rolled back with the per-test
;; registrar snapshot).
(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :async?     true
     :post-reset (fn []
                   (registry/register-xray-handlers!)
                   (rf/make-frame {:id :rf/xray}))}))

;; ---- hiccup walker ------------------------------------------------------
;; The private expand-tree / hiccup-seq / find-by-testid copies were
;; semantically identical to `re-frame.test-helpers`; tests call
;; `th/find-by-testid` directly (rf2-vj80u8 — no Xray walker facade).

;; ---- events + sub -------------------------------------------------------

(deftest show-and-dismiss-flip-the-sub
  (rf/with-frame :rf/xray
    (is (false? @(rf/subscribe [:rf.xray/editor-hint-open?]))
        "closed by default")
    (rf/dispatch-sync [:rf.xray/editor-hint-show])
    (is (true? @(rf/subscribe [:rf.xray/editor-hint-open?]))
        "show flips the sub on")
    (rf/dispatch-sync [:rf.xray/editor-hint-dismiss])
    (is (false? @(rf/subscribe [:rf.xray/editor-hint-open?]))
        "dismiss flips it back off")))

;; ---- Toast render ------------------------------------------------------

(deftest toast-renders-nil-when-closed
  (rf/with-frame :rf/xray
    (is (nil? (editor-hint/Toast))
        "Toast renders nil when editor-hint-open? is false")))

(deftest toast-renders-when-open
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/editor-hint-show]))
  (rf/with-frame :rf/xray
    (let [rendered (editor-hint/Toast)]
      (is (some? rendered)
          "Toast renders hiccup when editor-hint-open? is true")
      (is (th/find-by-testid rendered "rf-xray-editor-hint-toast")
          "the toast root is present")
      (is (th/find-by-testid rendered "rf-xray-editor-hint-open-settings")
          "the 'Open Settings' button is present")
      (is (th/find-by-testid rendered "rf-xray-editor-hint-dismiss")
          "the dismiss ✕ button is present"))))

;; ---- open-settings wiring ----------------------------------------------

(deftest open-settings-opens-popup-on-general-and-dismisses
  (testing "rf2-4s08ov — :rf.xray/editor-hint-open-settings dismisses the
            toast (synchronously in :db) and opens the Settings popup,
            which lands on the :general tab (the editor picker's home)"
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/editor-hint-show]))
    (is (true? (boolean (:editor-hint-open? (rf/app-db-value :rf/xray))))
        "toast is open before Open-Settings")
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/editor-hint-open-settings]))
    ;; The :db dismiss is synchronous; the settings-open lands via the
    ;; async `:dispatch` fx, so poll the router drain before asserting.
    (async done
      (-> (test-support/poll-until
            #(true? (boolean (:settings-open? (rf/app-db-value :rf/xray))))
            {:label "settings popup opens after editor-hint-open-settings"
             :timeout-ms 1000})
          (.then (fn [_]
                   (let [db (rf/app-db-value :rf/xray)]
                     (is (false? (boolean (:editor-hint-open? db)))
                         "the toast is dismissed when Settings opens")
                     (is (true? (boolean (:settings-open? db)))
                         "the Settings popup is open")
                     (is (= :general (:settings-active-tab db))
                         "Settings opens on the General tab — the editor
                          picker's home"))))
          (.catch (fn [e] (is false (.-message e)) nil))
          (.then (fn [_] (done)))))))

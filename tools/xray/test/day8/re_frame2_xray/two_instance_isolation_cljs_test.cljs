(ns day8.re-frame2-xray.two-instance-isolation-cljs-test
  "TWO Xray shell instances on one page hold INDEPENDENT state: the
  globally registered `:rf.xray/*` handlers and subs, driven and read under
  two shell frames through `rf/with-frame` (standing in for the two shells'
  frame-providers), yield two isolated app-dbs."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture))

;; The two shell-instance frame-ids. `cell-a` uses the production
;; default (`shell/default-frame-id` == `:rf/xray`) so the singleton
;; path is exercised alongside the second instance; `cell-b` is a
;; distinct frame a side-by-side testbed cell would pass to
;; `[shell/shell-view {:frame-id …}]`.
(def ^:private cell-a shell/default-frame-id)   ; :rf/xray
(def ^:private cell-b :xray-cell-2)

(defn- setup-two-shells!
  "Register Xray's handler graph GLOBALLY once, then register the two
  shell-instance frames + the observed host frame. Mirrors what mounting
  two `[shell/shell-view {:frame-id …}]` cells does:
  `register-xray-handlers!` is process-global (called once), and each
  cell's `ensure-xray-frame!` registers its own app-db frame."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id cell-a})
  (rf/make-frame {:id cell-b})
  (rf/make-frame {:id :rf/default}))

(defn- read-sub [frame-id sub-id]
  (rf/with-frame frame-id
    @(rf/subscribe [sub-id])))

(defn- dispatch! [frame-id event-v]
  (rf/with-frame frame-id
    (rf/dispatch-sync event-v)))

(deftest tab-mode-and-focus-are-per-instance
  (setup-two-shells!)
  (dispatch! cell-b [:rf.xray/select-tab :machines])
  (dispatch! cell-a [:rf.xray/select-tab :trace])
  (dispatch! cell-a [:rf.xray/set-mode :static])
  (dispatch! cell-b [:rf.xray/focus-event :cascade-b :rf/default])
  (dispatch! cell-a [:rf.xray/focus-event :cascade-a :rf/default])
  (is (= [[:trace :static :cascade-a] [:machines :dynamic :cascade-b]]
         (mapv (fn [cell]
                 [(read-sub cell :rf.xray/selected-tab)
                  (read-sub cell :rf.xray/mode)
                  (:dispatch-id (read-sub cell :rf.xray/focus))])
               [cell-a cell-b]))
      "driving cell A's tab, mode and focus leaves cell B's where they were"))

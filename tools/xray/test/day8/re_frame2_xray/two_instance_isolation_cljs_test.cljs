(ns day8.re-frame2-xray.two-instance-isolation-cljs-test
  "TWO Xray shell instances on one page hold INDEPENDENT state.

  ## What this pins

  A shell locked to a singleton `:rf/xray` frame — `shell-view`
  hardcoding `[frame-provider {:frame :rf/xray}]`, or an out-of-render
  affordance dispatching a bare `{:frame :rf/xray}` literal — would make
  two shells on one page (two panel-gallery chrome-shell cells, a Story
  workspace) COLLIDE: both would read and write the one `:rf/xray`
  app-db, so driving one would drive the other.

  The shell frame is parameterized, and
  every out-of-render dispatch resolves to the SURROUNDING instance
  frame via a captured frame-aware dispatcher. Handlers register
  GLOBALLY once under `:rf.xray/*` (the registrar is process-global —
  NOT per-frame); only the per-instance app-db lives in a distinct
  frame.

  ## How it's tested at the data layer

  This is a data-layer test (per the Xray-as-CLJS-unit-test
  posture): frame isolation is an app-db property, so we register two
  shell frames, drive each via `(rf/with-frame <frame-id> …)` — exactly
  the binding the parameterized `shell-view` establishes through its
  `[frame-provider {:frame frame-id}]` and the captured dispatchers
  thread — and assert the per-frame subs read INDEPENDENT values. The
  two `with-frame` bindings stand in for two shell instances' React-
  context providers; the assertions prove that the SAME globally-
  registered `:rf.xray/*` handlers + subs, read under two frames, yield
  two isolated app-dbs.

  Driving one frame's tab / mode / focused-epoch does NOT move the
  other's."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture`: plain-atom adapter + the default `:all`
  ;; reset tier, which includes the trace-collector ring reset.
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

(deftest selected-tab-is-per-instance
  (setup-two-shells!)
  (dispatch! cell-b [:rf.xray/select-tab :machines])
  (dispatch! cell-a [:rf.xray/select-tab :trace])
  (is (= [:trace :machines]
         [(read-sub cell-a :rf.xray/selected-tab) (read-sub cell-b :rf.xray/selected-tab)])
      "driving cell A's tab leaves cell B's where it was"))

(deftest mode-is-per-instance
  (setup-two-shells!)
  (dispatch! cell-a [:rf.xray/set-mode :static])
  (is (= [:static :dynamic]
         [(read-sub cell-a :rf.xray/mode) (read-sub cell-b :rf.xray/mode)])
      "cell B keeps the default mode when cell A flips"))

(deftest focused-epoch-is-per-instance
  (setup-two-shells!)
  (dispatch! cell-b [:rf.xray/focus-event :cascade-b :rf/default])
  (dispatch! cell-a [:rf.xray/focus-event :cascade-a :rf/default])
  (is (= [:cascade-a :cascade-b]
         [(:dispatch-id (read-sub cell-a :rf.xray/focus))
          (:dispatch-id (read-sub cell-b :rf.xray/focus))])
      "an L2 click in cell A focuses only cell A"))

(ns day8.re-frame2-xray.two-instance-isolation-cljs-test
  "EPIC rf2-1w07r acceptance test — TWO Xray shell instances on one page
  hold INDEPENDENT state.

  ## What this pins

  Before the de-singleton refactor the shell was locked to a singleton
  `:rf/xray` frame: `shell-view` hardcoded `[frame-provider {:frame
  :rf/xray}]` and every out-of-render affordance dispatched a bare
  `{:frame :rf/xray}` literal. Two shells on one page (two panel-gallery
  chrome-shell cells, a Story workspace) therefore COLLIDED — both read
  and wrote the one `:rf/xray` app-db, so driving one drove the other.

  The fix (rf2-lnluk + rf2-r0o63): the shell frame is parameterized, and
  every out-of-render dispatch resolves to the SURROUNDING instance
  frame via a captured frame-aware dispatcher. Handlers register
  GLOBALLY once under `:rf.xray/*` (the registrar is process-global —
  NOT per-frame); only the per-instance app-db lives in a distinct
  frame.

  ## How it's tested at the data layer

  This is a data-layer test (per the rf2-dhoc9 / Causa-as-CLJS-unit-test
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
  other's — the EPIC's acceptance criterion verbatim."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

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
  (frame/reg-frame cell-a {})
  (frame/reg-frame cell-b {})
  (frame/reg-frame :rf/default {}))

(defn- read-sub [frame-id sub-id]
  (rf/with-frame frame-id
    @(rf/subscribe [sub-id])))

(defn- dispatch! [frame-id event-v]
  (rf/with-frame frame-id
    (rf/dispatch-sync event-v)))

(deftest handlers-register-globally-once
  (testing "rf2-lnluk — handlers register GLOBALLY once under :rf.xray/*
            (the registrar is process-global, not per-frame). Both shell
            frames resolve the SAME registered subs/events; only their
            app-db differs."
    (setup-two-shells!)
    ;; The same sub-id resolves under both frames (would throw / return
    ;; nil if registration were per-frame and one frame missed it).
    (is (= :epoch (read-sub cell-a :rf.xray/selected-tab)))
    (is (= :epoch (read-sub cell-b :rf.xray/selected-tab))
        "the globally-registered :rf.xray/selected-tab sub resolves under
         BOTH instance frames — no per-frame handler re-registration")))

(deftest selected-tab-is-per-instance
  (testing "rf2-1w07r acceptance — driving cell A's tab does NOT move
            cell B's. Distinct selected-tab per instance."
    (setup-two-shells!)
    (dispatch! cell-a [:rf.xray/select-tab :trace])
    (dispatch! cell-b [:rf.xray/select-tab :machines])
    (is (= :trace (read-sub cell-a :rf.xray/selected-tab))
        "cell A holds :trace")
    (is (= :machines (read-sub cell-b :rf.xray/selected-tab))
        "cell B holds :machines — independent of cell A")
    ;; Re-drive cell A; cell B must not budge.
    (dispatch! cell-a [:rf.xray/select-tab :routing])
    (is (= :routing (read-sub cell-a :rf.xray/selected-tab))
        "cell A moved to :routing")
    (is (= :machines (read-sub cell-b :rf.xray/selected-tab))
        "cell B is STILL :machines — driving A did not move B")))

(deftest mode-is-per-instance
  (testing "rf2-1w07r acceptance — Dynamic/Static mode (`:rf.xray/mode`)
            is per-instance. The shell-view reads it via `:frame-id`
            (rf2-lnluk) and the mode pill writes via the captured
            dispatcher (rf2-r0o63)."
    (setup-two-shells!)
    (is (= :dynamic (read-sub cell-a :rf.xray/mode)))
    (is (= :dynamic (read-sub cell-b :rf.xray/mode)))
    (dispatch! cell-a [:rf.xray/set-mode :static])
    (is (= :static (read-sub cell-a :rf.xray/mode))
        "cell A flipped to :static")
    (is (= :dynamic (read-sub cell-b :rf.xray/mode))
        "cell B is STILL :dynamic — mode is per-instance, not shared")))

(deftest focused-epoch-is-per-instance
  (testing "rf2-1w07r acceptance — the focused cascade/epoch
            (`:rf.xray/focus`) is per-instance. Clicking an L2 row in one
            shell (which dispatches `:rf.xray/focus-event` via the
            captured dispatcher, rf2-r0o63) focuses ONLY that shell."
    (setup-two-shells!)
    ;; Focus distinct cascades in each cell.
    (dispatch! cell-a [:rf.xray/focus-event :cascade-a :rf/default])
    (dispatch! cell-b [:rf.xray/focus-event :cascade-b :rf/default])
    (is (= :cascade-a (:dispatch-id (read-sub cell-a :rf.xray/focus)))
        "cell A focused :cascade-a")
    (is (= :cascade-b (:dispatch-id (read-sub cell-b :rf.xray/focus)))
        "cell B focused :cascade-b — independent focus")
    ;; Re-focus cell A; cell B's focus must be untouched.
    (dispatch! cell-a [:rf.xray/focus-event :cascade-a2 :rf/default])
    (is (= :cascade-a2 (:dispatch-id (read-sub cell-a :rf.xray/focus)))
        "cell A re-focused :cascade-a2")
    (is (= :cascade-b (:dispatch-id (read-sub cell-b :rf.xray/focus)))
        "cell B is STILL focused :cascade-b — driving A's focus did not
         move B's")))

(deftest all-three-axes-independent-in-one-flow
  (testing "rf2-1w07r acceptance (combined) — TWO shells, distinct tab +
            mode + focused-epoch each; driving one shell across all three
            axes leaves the other's three axes untouched. The EPIC's
            acceptance criterion in a single end-to-end flow."
    (setup-two-shells!)
    ;; Seed cell B with a full, distinct state. (rf2-gbz39 — uses
    ;; :trace; the Issues tab was removed under Option (c).)
    (dispatch! cell-b [:rf.xray/select-tab :trace])
    (dispatch! cell-b [:rf.xray/set-mode :static])
    (dispatch! cell-b [:rf.xray/focus-event :b-cascade :rf/default])
    ;; Now drive cell A across all three axes.
    (dispatch! cell-a [:rf.xray/select-tab :app-db])
    (dispatch! cell-a [:rf.xray/set-mode :dynamic])
    (dispatch! cell-a [:rf.xray/focus-event :a-cascade :rf/default])
    ;; Cell A reflects its own drive.
    (is (= :app-db (read-sub cell-a :rf.xray/selected-tab)))
    (is (= :dynamic (read-sub cell-a :rf.xray/mode)))
    (is (= :a-cascade (:dispatch-id (read-sub cell-a :rf.xray/focus))))
    ;; Cell B is wholly untouched on all three axes.
    (is (= :trace (read-sub cell-b :rf.xray/selected-tab))
        "cell B tab untouched")
    (is (= :static (read-sub cell-b :rf.xray/mode))
        "cell B mode untouched")
    (is (= :b-cascade (:dispatch-id (read-sub cell-b :rf.xray/focus)))
        "cell B focus untouched")))

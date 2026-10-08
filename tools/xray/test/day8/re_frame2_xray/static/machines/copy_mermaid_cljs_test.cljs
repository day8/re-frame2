(ns day8.re-frame2-xray.static.machines.copy-mermaid-cljs-test
  "Integration tests for the Static Machines definition-detail header's
  Copy Mermaid action.

  ## What's under test

  The one host-owned gesture that makes a registered topology reachable
  as Mermaid: click `Copy Mermaid` in the selected machine's detail
  header and exactly `(mermaid/emit definition)` — the fenced
  ```mermaid markdown block — lands on the clipboard through the
  Xray-owned `:rf.xray.fx/copy-to-clipboard` fx.

    1. The control renders ONLY when the selected definition passes
       `grammar/valid-definition?`.

    2. Activating the REAL control (the rendered button's `:on-click`)
       produces exactly ONE clipboard write whose text equals
       `(mermaid/emit definition)` verbatim — the emitter's corpus
       stays the authority for diagram semantics; this suite owns only
       the host-to-clipboard gesture.

    3. Outcome feedback is honest and non-modal: the settled result
       renders as one inline `role=status` span (`Copied` /
       `Copy failed`); an unsettled (pending) write renders NOTHING, a
       rejected/unavailable clipboard reports `Copy failed`, and a
       failed write is never reported as copied.

    4. Feedback hygiene: selection change clears the slot, and a copy
       that settles AFTER the user has moved to another machine does
       not repopulate it.

  ## Clipboard boundary

  Mocked at the usual seam: the `:rf/xray` frame's
  `:fx-overrides` captures `:rf.xray.fx/copy-to-clipboard` args.
  Settlement is then driven by dispatching
  the captured `:on-success` / `:on-failure` event vectors — the exact
  vectors the real fx dispatches when the `writeText` Promise settles.
  One async test additionally exercises the REAL registered fx on this
  node target (no clipboard) to prove the unavailable-clipboard
  branch lands `:failed` through the fx's own frame-pinned dispatch."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.persistence :as ls]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.test-helpers.static-machines-tree
             :as machines-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:async?     true
     :post-reset (fn []
                   (config/reset-suppressed-count!)
                   (static-persistence/clear!)
                   (ls/clear!))}))

;; ---- helpers ------------------------------------------------------------

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

(defn- seed-machines! [ids]
  (frame-dispatch [:rf.xray/set-registered-machines-override-for-test
                   (vec ids)]))

(defn- seed-definitions! [defs]
  (frame-dispatch [:rf.xray/set-machine-definitions-override-for-test defs]))

(defn- capture-copy!
  "Capture `:rf.xray.fx/copy-to-clipboard` args via the `:rf/xray`
  frame's `:fx-overrides` seam (fn-value form; the
  re-`make-frame` is a surgical config update on the live frame). Call
  AFTER `xray-setup!`."
  []
  (let [captured (atom [])]
    (rf/make-frame {:id :rf/xray
                    :fx-overrides {:rf.xray.fx/copy-to-clipboard
                                   (fn [_ctx args] (swap! captured conj args))}})
    captured))

(def ^:private fixture-definition
  "A small but representative compound topology — enough grammar for a
  non-trivial emit, valid per `grammar/valid-definition?`."
  {:initial :idle
   :states  {:idle    {:on {:start :running}}
             :running {:on {:pause :paused
                            :stop  :idle}}
             :paused  {:on {:resume :running}}}})

(defn- find-copy-button [tree]
  (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-copy-mermaid"))

(defn- find-status-span [tree]
  (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-copy-mermaid-status"))

(defn- copy-status [machine-id]
  (frame-sub [:rf.xray.static.machines/copy-mermaid-status machine-id]))

;; -------------------------------------------------------------------------
;; (1) The no-valid-definition gate
;; -------------------------------------------------------------------------

(deftest copy-button-omitted-without-a-valid-definition
  ;; No :initial — fails the shared grammar predicate, the same shape
  ;; `mermaid/emit` would reject.
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:states {:idle {}}}})
  (frame-dispatch [:rf.xray.static.machines/select :m/a])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)]
      (is (some? (rf.test-helpers/find-by-testid
                   tree "rf-xray-static-machines-detail-header"))
          "the header still renders, so the absence below is about the control")
      (is (nil? (find-copy-button tree))))))

;; -------------------------------------------------------------------------
;; (2) The real control writes exactly (mermaid/emit definition) — once
;; -------------------------------------------------------------------------

(deftest click-writes-exact-emitter-output-once
  (async done
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a fixture-definition})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (let [captured (capture-copy!)
          on-click (rf/with-frame :rf/xray
                     (:on-click (rf.test-helpers/attrs (find-copy-button (machines-tree/panel-tree)))))]
      ;; The captured dispatcher queues, so the event lands on the next
      ;; router drain — hence the async test.
      (on-click nil)
      (js/setTimeout
        (fn []
          (is (= [(mermaid/emit fixture-definition)] (mapv :text @captured))
              "exactly ONE clipboard write, carrying EXACTLY (mermaid/emit definition)")
          (is (nil? (copy-status :m/a))
              "no settled status while the write is in flight")
          (frame-dispatch (:on-success (first @captured)))
          (rf/with-frame :rf/xray
            (let [span (find-status-span (machines-tree/panel-tree))]
              (is (= ["status" "Copied"]
                     [(:role (rf.test-helpers/attrs span))
                      (rf.test-helpers/text-content span)])
                  "success renders non-modal status feedback")))
          (done))
        50))))

;; -------------------------------------------------------------------------
;; (3) Failure paths — never reported as copied
;; -------------------------------------------------------------------------

(deftest rejected-clipboard-reports-failure
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a fixture-definition})
  (frame-dispatch [:rf.xray.static.machines/select :m/a])
  (let [captured (capture-copy!)]
    (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                     :m/a fixture-definition])
    (frame-dispatch (:on-failure (first @captured)))
    (rf/with-frame :rf/xray
      (is (= "Copy failed"
             (rf.test-helpers/text-content
               (find-status-span (machines-tree/panel-tree))))))))

(deftest unavailable-clipboard-real-fx-lands-failure
  (async done
    ;; No fx-override: the REAL registered fx runs, finds no clipboard on
    ;; this node target and dispatches `:on-failure` onto the fx-context
    ;; frame — hence the drain wait.
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a fixture-definition})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                     :m/a fixture-definition])
    (js/setTimeout
      (fn []
        (is (= :failed (copy-status :m/a)))
        (done))
      50)))

(deftest unprojectable-definition-at-event-time-fails-without-write
  ;; A render / registry race: the definition fails emit at event time.
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a fixture-definition})
  (frame-dispatch [:rf.xray.static.machines/select :m/a])
  (let [captured (capture-copy!)]
    (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                     :m/a {:states {}}])
    (is (= [] @captured) "no clipboard write is attempted")
    (is (= :failed (copy-status :m/a)))))

;; -------------------------------------------------------------------------
;; (4) Feedback hygiene — cleared on selection change; no stale settle
;; -------------------------------------------------------------------------

(deftest status-cleared-on-selection-change
  (xray-setup!)
  (seed-machines! [:m/a :m/b])
  (seed-definitions! {:m/a fixture-definition
                      :m/b fixture-definition})
  (frame-dispatch [:rf.xray.static.machines/select :m/a])
  (let [captured (capture-copy!)]
    (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                     :m/a fixture-definition])
    (frame-dispatch (:on-success (first @captured)))
    (is (= :copied (copy-status :m/a))
        "precondition: feedback set before the selection change")
    (frame-dispatch [:rf.xray.static.machines/select :m/b])
    (is (nil? (copy-status :m/a))
        "the slot is cleared, so returning to :m/a shows no stale feedback")))

(deftest late-settlement-after-reselect-does-not-repopulate
  (xray-setup!)
  (seed-machines! [:m/a :m/b])
  (seed-definitions! {:m/a fixture-definition
                      :m/b fixture-definition})
  (frame-dispatch [:rf.xray.static.machines/select :m/a])
  (let [captured (capture-copy!)]
    (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                     :m/a fixture-definition])
    (frame-dispatch [:rf.xray.static.machines/select :m/b])
    (frame-dispatch (:on-success (first @captured)))
    (is (nil? (copy-status :m/a))
        "a settlement arriving after the user moved on is dropped")))

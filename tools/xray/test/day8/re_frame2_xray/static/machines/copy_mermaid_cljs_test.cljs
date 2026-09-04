(ns day8.re-frame2-xray.static.machines.copy-mermaid-cljs-test
  "Integration tests for the Static Machines definition-detail header's
  Copy Mermaid action (rf2-sxw06).

  ## What's under test

  The one host-owned gesture that makes a registered topology reachable
  as Mermaid: click `Copy Mermaid` in the selected machine's detail
  header and exactly `(mermaid/emit definition)` — the fenced
  ```mermaid markdown block — lands on the clipboard through the
  Xray-owned `:rf.xray.fx/copy-to-clipboard` fx.

    1. The control renders in the header with an accessible name, and
       ONLY when the selected definition passes
       `grammar/valid-definition?` — no definition / malformed
       definition ⇒ no actionable control, header otherwise stable.

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

    4. Feedback hygiene: selection change clears the span, and a copy
       that settles AFTER the user has moved to another machine does
       not repopulate it.

  ## Clipboard boundary

  Mocked at the established seam: the `:rf/xray` frame's
  `:fx-overrides` captures `:rf.xray.fx/copy-to-clipboard` args
  (rf2-h1vqa4 — same pattern as `registry_cljs_test` /
  `app_db_diff_cljs_test`). Settlement is then driven by dispatching
  the captured `:on-success` / `:on-failure` event vectors — the exact
  vectors the real fx dispatches when the `writeText` Promise settles.
  One async test additionally exercises the REAL registered fx on this
  node target (no `js/navigator`) to prove the unavailable-clipboard
  branch lands `:failed` through the fx's own frame-pinned dispatch."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.panel :as panel]
            [day8.re-frame2-xray.static.machines.persistence :as ls]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
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
  frame's `:fx-overrides` seam (rf2-h1vqa4 — fn-value form; the
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

;; -------------------------------------------------------------------------
;; (1) Rendering + accessibility + the no-valid-definition gate
;; -------------------------------------------------------------------------

(deftest copy-button-renders-with-accessible-name
  (testing "A valid selected definition renders ONE labelled, focusable
            Copy Mermaid button in the detail header"
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a fixture-definition})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (rf/with-frame :rf/xray
      (let [tree  (panel/panel)
            btn   (find-copy-button tree)
            attrs (rf.test-helpers/attrs btn)]
        (is (some? btn) "the Copy Mermaid control renders")
        (is (= :button (first btn))
            "a real <button> — keyboard-reachable by default")
        (is (= "button" (:type attrs))
            ":type button (no accidental form-submit semantics)")
        (is (seq (:aria-label attrs)) "carries an accessible name")
        (is (fn? (:on-click attrs)) "wired to a click handler")
        (is (nil? (find-status-span tree))
            "no feedback span before any copy gesture")))))

(deftest copy-button-omitted-when-no-definition
  (testing "A machine with NO registered definition renders a stable
            header with no copy control"
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (rf/with-frame :rf/xray
      (let [tree (panel/panel)]
        (is (some? (rf.test-helpers/find-by-testid
                     tree "rf-xray-static-machines-detail-header"))
            "header still renders")
        (is (nil? (find-copy-button tree))
            "no actionable copy control without a definition")))))

(deftest copy-button-omitted-when-definition-invalid
  (testing "A malformed definition (fails grammar/valid-definition?)
            renders a stable header with no copy control"
    (xray-setup!)
    (seed-machines! [:m/a])
    ;; No :initial — fails the shared grammar predicate; the same shape
    ;; `mermaid/emit` would reject with :mermaid/invalid-definition.
    (seed-definitions! {:m/a {:states {:idle {}}}})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (rf/with-frame :rf/xray
      (let [tree (panel/panel)]
        (is (some? (rf.test-helpers/find-by-testid
                     tree "rf-xray-static-machines-detail-header"))
            "header still renders")
        (is (nil? (find-copy-button tree))
            "no actionable copy control for an unprojectable definition")))))

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
                     (:on-click (rf.test-helpers/attrs (find-copy-button (panel/panel)))))]
      (is (fn? on-click) "sanity: the rendered control is wired")
      ;; Activate the REAL control. The reg-view-injected dispatcher is
      ;; the queued (async) frame dispatch, so the event lands on the
      ;; next router drain — hence the async test.
      (on-click nil)
      (js/setTimeout
        (fn []
          (is (= 1 (count @captured))
              "exactly ONE clipboard write per gesture")
          (let [{:keys [text on-success on-failure]} (first @captured)]
            (is (= (mermaid/emit fixture-definition) text)
                "the payload is EXACTLY (mermaid/emit definition)")
            (is (re-find #"^```mermaid\n" (or text ""))
                "fenced markdown block — opening fence intact")
            (is (vector? on-success) "success callback event supplied")
            (is (vector? on-failure) "failure callback event supplied")
            ;; Unsettled write ⇒ NO feedback yet (pending is never
            ;; surfaced — a write that hasn't settled must not read as
            ;; copied).
            (is (nil? (frame-sub [:rf.xray.static.machines/copy-mermaid-status
                                  :m/a]))
                "no settled status while the write is in flight")
            (rf/with-frame :rf/xray
              (is (nil? (find-status-span (panel/panel)))
                  "no feedback span while the write is in flight"))
            ;; Settle success — the exact vector the real fx dispatches
            ;; when the writeText Promise resolves.
            (frame-dispatch on-success)
            (is (= :copied
                   (frame-sub [:rf.xray.static.machines/copy-mermaid-status
                               :m/a])))
            (rf/with-frame :rf/xray
              (let [span (find-status-span (panel/panel))]
                (is (some? span) "feedback span renders on success")
                (is (= "status" (:role (rf.test-helpers/attrs span)))
                    "non-modal accessible status feedback")
                (is (= "Copied" (rf.test-helpers/text-content span))))))
          (done))
        50))))

;; -------------------------------------------------------------------------
;; (3) Failure paths — never reported as copied
;; -------------------------------------------------------------------------

(deftest rejected-clipboard-reports-failure
  (testing "A rejected clipboard write settles as Copy failed — never
            as Copied"
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a fixture-definition})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (let [captured (capture-copy!)]
      ;; Drive the event seam sync (mirrors the click-path proven above;
      ;; same event vector the control dispatches).
      (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                       :m/a fixture-definition])
      (is (= 1 (count @captured)))
      ;; Settle rejection — the exact vector the real fx dispatches when
      ;; the writeText Promise rejects (denied permission etc.).
      (frame-dispatch (:on-failure (first @captured)))
      (is (= :failed
             (frame-sub [:rf.xray.static.machines/copy-mermaid-status :m/a]))
          "rejection lands as :failed")
      (rf/with-frame :rf/xray
        (let [span (find-status-span (panel/panel))]
          (is (= "Copy failed" (rf.test-helpers/text-content span)))
          (is (not= "Copied" (rf.test-helpers/text-content span))
              "a failed write is never reported as copied"))))))

(deftest unavailable-clipboard-real-fx-lands-failure
  (async done
    ;; No fx-override here: the REAL registered fx runs. On this node
    ;; target `js/navigator` doesn't exist, so the fx takes the
    ;; unavailable branch and dispatches `:on-failure` — queued onto the
    ;; fx-context frame (`:rf/xray`), hence the drain wait.
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a fixture-definition})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                     :m/a fixture-definition])
    (js/setTimeout
      (fn []
        (is (= :failed
               (frame-sub [:rf.xray.static.machines/copy-mermaid-status
                           :m/a]))
            "unavailable clipboard settles as :failed via the real fx")
        (done))
      50)))

(deftest unprojectable-definition-at-event-time-fails-without-write
  (testing "A definition that fails emit at event time (render/registry
            race) lands honest :failed feedback and writes NOTHING"
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a fixture-definition})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (let [captured (capture-copy!)]
      (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                       :m/a {:states {}}])
      (is (zero? (count @captured)) "no clipboard write is attempted")
      (is (= :failed
             (frame-sub [:rf.xray.static.machines/copy-mermaid-status
                         :m/a]))))))

;; -------------------------------------------------------------------------
;; (4) Feedback hygiene — cleared on selection change; no stale settle
;; -------------------------------------------------------------------------

(deftest status-cleared-on-selection-change
  (testing "Selecting another machine clears the feedback span"
    (xray-setup!)
    (seed-machines! [:m/a :m/b])
    (seed-definitions! {:m/a fixture-definition
                        :m/b fixture-definition})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (let [captured (capture-copy!)]
      (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                       :m/a fixture-definition])
      (frame-dispatch (:on-success (first @captured)))
      (is (= :copied
             (frame-sub [:rf.xray.static.machines/copy-mermaid-status :m/a]))
          "sanity: feedback set before the selection change")
      (frame-dispatch [:rf.xray.static.machines/select :m/b])
      (is (nil? (frame-sub [:rf.xray.static.machines/copy-mermaid-status
                            :m/a])))
      (is (nil? (frame-sub [:rf.xray.static.machines/copy-mermaid-status
                            :m/b])))
      (rf/with-frame :rf/xray
        (is (nil? (find-status-span (panel/panel)))
            "no feedback span survives onto another machine's header")))))

(deftest late-settlement-after-reselect-does-not-repopulate
  (testing "A copy that settles AFTER the user moved to another machine
            does not repopulate the cleared feedback slot"
    (xray-setup!)
    (seed-machines! [:m/a :m/b])
    (seed-definitions! {:m/a fixture-definition
                        :m/b fixture-definition})
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (let [captured (capture-copy!)]
      (frame-dispatch [:rf.xray.static.machines/copy-mermaid
                       :m/a fixture-definition])
      ;; User moves on before the async write settles…
      (frame-dispatch [:rf.xray.static.machines/select :m/b])
      ;; …then the stale settlement arrives.
      (frame-dispatch (:on-success (first @captured)))
      (is (nil? (frame-sub [:rf.xray.static.machines/copy-mermaid-status
                            :m/a]))
          "stale settlement is dropped, not recorded")
      (rf/with-frame :rf/xray
        (is (nil? (find-status-span (panel/panel))))))))

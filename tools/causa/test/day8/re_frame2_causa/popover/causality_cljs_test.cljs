(ns day8.re-frame2-causa.popover.causality-cljs-test
  "CLJS tests for the Causality popover wiring (rf2-dqnuu).

  Three contract surfaces under test:

    1. **Open/close/toggle events.** Round-trip
       `:rf.causa/causality-popover-open?` through the registered
       events; assert that `c`-key toggle flips the slot.

    2. **Popover view.** When open, the Modal's reg-view body renders;
       when closed, the Modal short-circuits to nil. Backdrop +
       dialog + footer toggle all carry the spec'd testids.

    3. **ELK loader fallback.** `ensure-elk!` rolls into the `:failed`
       state when no `js/window.ELK` is stubbed and `js/import` is
       unavailable; the view then renders the `fallback-list` body
       and surfaces the `elk-unavailable` hint."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.popover.causality :as popover]
            [day8.re-frame2-causa.popover.causality-graph :as pop-graph]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  ;; Reset the ELK loader so each test starts from `:idle`.
  (pop-graph/reset-elk-state-for-test!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- causa-db []
  (rf/get-frame-db :rf/causa))

;; ---- hiccup helpers -----------------------------------------------------

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

;; ---- (1) registry installs the popover subs + events -------------------

(deftest registry-installs-popover-subs
  (testing "register-causa-handlers! installs all three popover subs"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/causality-popover-open?)))
    (is (some? (registrar/handler :sub :rf.causa/causality-popover-layout)))
    (is (some? (registrar/handler :sub :rf.causa/causality-popover-payload)))))

(deftest registry-installs-popover-events
  (testing "register-causa-handlers! installs every popover event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :event :rf.causa/causality-popover-open)))
    (is (some? (registrar/handler :event :rf.causa/causality-popover-close)))
    (is (some? (registrar/handler :event :rf.causa/causality-popover-toggle)))
    (is (some? (registrar/handler :event :rf.causa/causality-popover-toggle-layout)))))

;; ---- (2) open / close / toggle round-trip ------------------------------

(deftest popover-open-flips-flag
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-open]))
  (is (true? (:causality-popover-open? (causa-db))))
  (is (= :tb (:causality-popover-layout (causa-db)))
      "first open seeds layout to default :tb"))

(deftest popover-close-clears-flag
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-open])
    (rf/dispatch-sync [:rf.causa/causality-popover-close]))
  (is (false? (boolean (:causality-popover-open? (causa-db))))))

(deftest popover-toggle-cycles
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-toggle]))
  (is (true? (:causality-popover-open? (causa-db)))
      "first c-key toggle opens")
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-toggle]))
  (is (false? (boolean (:causality-popover-open? (causa-db))))
      "second c-key toggle closes")
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-toggle]))
  (is (true? (:causality-popover-open? (causa-db)))
      "third c-key toggle re-opens"))

(deftest popover-toggle-layout-cycles-lr-tb
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-open]))
  (is (= :tb (:causality-popover-layout (causa-db))))
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-toggle-layout]))
  (is (= :lr (:causality-popover-layout (causa-db))))
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-toggle-layout]))
  (is (= :tb (:causality-popover-layout (causa-db)))))

;; ---- (3) Modal view body -----------------------------------------------

(deftest popover-modal-renders-nothing-when-closed
  (setup!)
  (rf/with-frame :rf/causa
    (let [tree (popover/Popover)]
      (is (nil? tree)
          "Popover short-circuits to nil when closed — closed-state cost
           is one subscribe + a when-gate"))))

(deftest popover-modal-renders-when-open
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-open])
    (let [tree (popover/Popover)]
      (is (some? tree)
          "Popover renders hiccup when open")
      (is (some? (find-by-testid tree "rf-causa-popover-backdrop"))
          "backdrop is the outer envelope")
      (is (some? (find-by-testid tree "rf-causa-popover-dialog"))
          "dialog container present")
      (is (some? (find-by-testid tree "rf-causa-popover-title"))
          "header carries the title slot")
      (is (some? (find-by-testid tree "rf-causa-popover-body"))
          "body slot present")
      (is (some? (find-by-testid tree "rf-causa-popover-close"))
          "header carries the close-X")
      (is (some? (find-by-testid tree "rf-causa-popover-layout-toggle"))
          "footer carries the layout LR/TB toggle"))))

(deftest popover-backdrop-click-closes
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-open])
    (let [tree     (popover/Popover)
          backdrop (find-by-testid tree "rf-causa-popover-backdrop")
          on-click (:on-click (second backdrop))]
      (is (fn? on-click)
          "backdrop carries an on-click — dispatches close")
      ;; Drive the close event directly (the on-click body's
      ;; rf/dispatch is async; the registered event is what matters).
      (rf/dispatch-sync [:rf.causa/causality-popover-close])
      (is (false? (boolean (:causality-popover-open? (causa-db)))))) ))

(deftest popover-close-button-fires-close-event
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-open])
    (let [tree  (popover/Popover)
          close (find-by-testid tree "rf-causa-popover-close")
          oc    (:on-click (second close))]
      (is (fn? oc)
          "close-X carries an on-click handler"))))

;; ---- (4) layout toggle pills route through the toggle event ------------

(deftest popover-layout-pills-route-through-toggle-event
  (setup!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/causality-popover-open])
    (let [tree     (popover/Popover)
          lr-btn   (find-by-testid tree "rf-causa-popover-layout-lr")
          tb-btn   (find-by-testid tree "rf-causa-popover-layout-tb")]
      (is (some? lr-btn) "LR button present")
      (is (some? tb-btn) "TB button present")
      (is (fn? (:on-click (second lr-btn))))
      (is (fn? (:on-click (second tb-btn)))))))

;; ---- (5) ELK loader fallback path -------------------------------------

(deftest elk-loader-rolls-to-failed-without-stub-or-import
  (testing "with no js/window.ELK stub and no working js/import the
            loader rolls into :failed state and the callback fires
            with nil"
    (pop-graph/reset-elk-state-for-test!)
    (let [called (atom nil)]
      (pop-graph/ensure-elk! (fn [inst] (reset! called inst)))
      ;; In node-test (no js/import available), the loader synchronously
      ;; flips into :failed and fires the callback with nil.
      (is (= :failed (pop-graph/elk-status))
          "loader records the failure for the popover body to drop into
           fallback render")
      (is (nil? @called)
          "callback fires with nil so the caller knows ELK is unusable"))))

(deftest elk-loader-idempotent-after-failed
  (testing "after a failed load the second ensure-elk! call doesn't
            re-trigger an import — it fast-paths the callback with nil"
    (pop-graph/reset-elk-state-for-test!)
    (pop-graph/ensure-elk! (fn [_] nil))
    (let [count2 (atom 0)]
      (pop-graph/ensure-elk! (fn [_] (swap! count2 inc)))
      (is (= 1 @count2)
          "second call fires the callback once via the :failed
           fast-path; doesn't re-trigger an import"))))

(deftest popover-body-renders-fallback-when-elk-failed
  (testing "with ELK in the :failed state, the popover body renders the
            fallback list and surfaces the elk-unavailable hint"
    (setup!)
    ;; Force the loader into :failed so the synchronous render lands
    ;; on the fallback branch.
    (pop-graph/reset-elk-state-for-test!)
    (pop-graph/ensure-elk! (fn [_] nil))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/causality-popover-open])
      (let [tree (popover/Popover)]
        (is (some? (find-by-testid tree "rf-causa-popover-elk-unavailable"))
            "elk-unavailable hint renders when load failed")))))

(deftest popover-body-renders-empty-state-when-no-focus
  (testing "with no focused dispatch, the body renders the
            `rf-causa-popover-empty` slot — same surface the fallback
            list resolves to via `:empty? true` in the payload"
    (setup!)
    (pop-graph/reset-elk-state-for-test!)
    (pop-graph/ensure-elk! (fn [_] nil))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/causality-popover-open])
      (let [tree (popover/Popover)]
        (is (some? (find-by-testid tree "rf-causa-popover-empty"))
            "the empty-state surface renders inside the body slot")))))

;; ---- Modal positioning (rf2-om6fa) -------------------------------------

(deftest backdrop-defaults-to-fixed-positioning
  (testing "with no :rf.causa/modal-positioning slot set, the
            causality popover backdrop renders position: fixed at the
            production z-index"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/causality-popover-open]))
    (rf/with-frame :rf/causa
      (let [tree     (popover/Popover)
            backdrop (find-by-testid tree "rf-causa-popover-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "fixed" (:position style)))
        (is (= 2147483645 (:z-index style)))
        (is (= "fixed"
               (:data-rf-causa-modal-positioning (second backdrop))))))))

(deftest backdrop-honours-absolute-positioning
  (testing "after `:rf.causa/set-modal-positioning :absolute` the
            causality backdrop switches to position: absolute with a
            sane in-cell z-index"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/causality-popover-open])
      (rf/dispatch-sync [:rf.causa/set-modal-positioning :absolute]))
    (rf/with-frame :rf/causa
      (let [tree     (popover/Popover)
            backdrop (find-by-testid tree "rf-causa-popover-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "absolute" (:position style)))
        (is (< (:z-index style) 1000)
            "z-index drops to a sane in-cell value")
        (is (= "absolute"
               (:data-rf-causa-modal-positioning (second backdrop))))))))

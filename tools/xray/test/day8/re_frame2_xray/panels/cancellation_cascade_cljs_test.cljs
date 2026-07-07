(ns day8.re-frame2-xray.panels.cancellation-cascade-cljs-test
  "CLJS-side wiring + view tests for Xray's Cancellation-cascade
  visualiser (rf2-59e7k).

  ## What's under test (in addition to the pure-data tests in
  `cancellation_cascade_helpers_cljs_test.cljc`)

    1. **Registry wires the composite subs and events** under
       `:rf.xray/cancellation-cascade-*` ids.
    2. **Empty-state render** — `SidePanel` short-circuits when no
       cancellation-anchor is present; `Popover` is gated by
       `:rf.xray/cancellation-cascade-popover-open?`.
    3. **Populated render** — with a seeded trace buffer the cascade
       view renders the decision + teardown + abort rows.
    4. **Click handlers dispatch the right events** — the row's
       on-click dispatches `:rf.xray/focus-trace-entry`; the close
       button dispatches `:rf.xray/cancellation-cascade-close`.
    5. **Collapse / expand affordance** — under the default
       threshold the expander appears and the toggle event flips
       `:rf.xray/cancellation-cascade-expanded?`.

  ## Pure hiccup

  Same approach as `flows_view_cljs_test` — walk the view's hiccup
  tree by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cc]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

;; ---- hiccup walkers (mirror other view tests) ---------------------------

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

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

(defn- seed-trace! [events]
  ;; Seed Xray's app-db trace-buffer slot directly via the registry's
  ;; sync event. Avoids the trace-bus collector loop entirely; the
  ;; subs read off `:trace-buffer` regardless of how the slot got
  ;; populated.
  (rf/dispatch-sync [:rf.xray/sync-trace-buffer (vec events)]))

;; ---- minimal fixture events ---------------------------------------------

(def ^:private cancel-cascade-buffer
  "One decision + one cancellation-anchor + two HTTP aborts."
  [{:id 1 :operation :rf.event/dispatched :op-type :rf.event
    :time 1000
    :tags {:rf.event/v [:auth/logout] :rf.trace/dispatch-id 7 :frame :rf/default}}
   {:id 2 :operation :rf.machine/destroyed :op-type :rf.machine
    :time 1010
    :tags {:machine-id :user-session :reason :explicit
           :rf.trace/dispatch-id 7 :frame :rf/default}}
   {:id 3 :operation :rf.http/aborted-on-actor-destroy :op-type :rf.http
    :severity :info :time 1020
    :tags {:request-id :r1 :url "/api/profile" :actor-id :user-session
           :rf.trace/dispatch-id 7 :frame :rf/default}}
   {:id 4 :operation :rf.http/aborted-on-actor-destroy :op-type :rf.http
    :severity :info :time 1021
    :tags {:request-id :r2 :url "/api/log" :actor-id :user-session
           :rf.trace/dispatch-id 7 :frame :rf/default}}])

;; ---- (1) registry wires the composite subs + events --------------------

(deftest registry-installs-cancellation-cascade-handlers
  (testing "register-xray-handlers! installs every sub + event the
            visualiser depends on"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray/cancellation-cascade-popover-open?)))
    (is (some? (registrar/handler :sub :rf.xray/cancellation-cascade-popover-focus)))
    (is (some? (registrar/handler :sub :rf.xray/cancellation-cascade-expanded?)))
    (is (some? (registrar/handler :sub :rf.xray/cancellation-cascade-for-focused-machine)))
    (is (some? (registrar/handler :sub :rf.xray/cancellation-cascade-for-focused-event)))
    (is (some? (registrar/handler :event :rf.xray/cancellation-cascade-open)))
    (is (some? (registrar/handler :event :rf.xray/cancellation-cascade-close)))
    (is (some? (registrar/handler :event :rf.xray/cancellation-cascade-toggle-expand)))
    (is (some? (registrar/handler :event :rf.xray/focus-trace-entry)))))

;; ---- (2) empty-state renders -------------------------------------------

(deftest side-panel-empty-when-no-cascade
  (testing "with an empty trace buffer the SidePanel reg-view returns
            nil (mount stays dormant)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [out (cc/SidePanel)]
        (is (nil? out)
            "no rendered hiccup when no cancellation cascade is present")))))

(deftest popover-empty-when-closed
  (testing "Popover short-circuits to nil when the open? slot is false"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (nil? (cc/Popover))))))

(deftest popover-renders-empty-state-when-open-with-no-cascade
  (testing "Popover renders the no-trigger empty state when open but
            the trace buffer carries no cascade"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
      (let [tree (cc/Popover)]
        (is (some? (find-by-testid tree "rf-xray-cancellation-cascade-popover-dialog")))
        (is (some? (find-by-testid tree "rf-xray-cancellation-cascade-empty-no-trigger")))))))

;; ---- (3) populated render ----------------------------------------------

(deftest side-panel-renders-when-machine-cascade-present
  (testing "with a cancellation cascade in the trace buffer for the
            focused machine the SidePanel renders the waterfall"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-trace! cancel-cascade-buffer)
      ;; Pick the machine that had the destroy
      (rf/dispatch-sync [:rf.xray/select-machine-id :user-session])
      (let [tree (cc/SidePanel)]
        (is (some? (find-by-testid tree "rf-xray-cancellation-cascade"))
            "section root rendered")
        (is (some? (find-by-testid tree "rf-xray-cancellation-cascade-decision-row"))
            "decision row rendered")
        (is (some? (find-by-testid tree "rf-xray-cancellation-cascade-summary"))
            "summary line rendered")
        (let [aborts (find-all-by-testid-prefix
                       tree "rf-xray-cancellation-cascade-abort-row-")]
          (is (= 2 (count aborts))
              "two abort rows rendered, one per fixture event"))
        ;; rf2-wuwu3 — each row's `:style` is one of the 6 precomputed
        ;; row-style × cursor variants (no per-render `merge`). The
        ;; `:cursor` slot is baked into the row style at ns load, so
        ;; the rendered row's :style map carries `:cursor` directly.
        (let [decision-row (find-by-testid tree "rf-xray-cancellation-cascade-decision-row")
              aborts       (find-all-by-testid-prefix
                             tree "rf-xray-cancellation-cascade-abort-row-")]
          (is (contains? (:style (second decision-row)) :cursor)
              "decision row picks one of the precomputed row-style × cursor variants")
          (is (every? #(contains? (:style (second %)) :cursor) aborts)
              "every abort row picks one of the precomputed row-style × cursor variants"))))))

(deftest popover-renders-cascade-for-focused-event
  (testing "Popover opened with a dispatch-id focus pulls the cascade
            for that dispatch and renders the body"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-trace! cancel-cascade-buffer)
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                         {:kind :dispatch-id :id 7}])
      (let [tree (cc/Popover)]
        (is (some? (find-by-testid tree "rf-xray-cancellation-cascade-popover-dialog")))
        (is (some? (find-by-testid tree "rf-xray-cancellation-cascade-decision-row")))
        (is (= 2 (count (find-all-by-testid-prefix
                          tree "rf-xray-cancellation-cascade-abort-row-"))))))))

;; ---- (4) click handlers ------------------------------------------------

(deftest close-button-dispatches-close-event
  (testing "the close button's on-click dispatches
            :rf.xray/cancellation-cascade-close, flipping the
            popover-open? slot to false"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
      (is (true? @(rf/subscribe [:rf.xray/cancellation-cascade-popover-open?])))
      ;; Fire the close event directly to assert the reducer's
      ;; round-trip — the on-click is a thin wrapper over this dispatch.
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-close])
      (is (false? @(rf/subscribe [:rf.xray/cancellation-cascade-popover-open?]))))))

(deftest focus-trace-entry-event-shape
  (testing "the row-click event accepts a `:dispatch-id` and dispatches
            without throwing — production path flips through the spine
            shim and panel-select"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-trace! cancel-cascade-buffer)
      ;; No throw is enough — the event handler delegates via :fx so
      ;; the side-effecting half routes through the spine shim, which
      ;; we've already covered in the spine tests.
      (rf/dispatch-sync [:rf.xray/focus-trace-entry
                         {:dispatch-id 7 :frame :rf/default :trace-id 1}])
      (is (true? true)))))

(deftest focus-trace-entry-lands-on-a-live-tab
  ;; rf2-cduftx F1 — the row-jump used to `[:rf.xray/select-tab :event]`,
  ;; but `:event` is a RETIRED tab id (the event-detail panel folded into
  ;; Epoch). Selecting it landed the shell's `rf-xray-tab-unknown` stub
  ;; instead of the cascade detail. This locks the row-jump's tab onto a
  ;; LIVE Dynamic L4 tab — and never onto an unregistered id.
  (testing "a row jump (with a dispatch-id) selects a LIVE Dynamic L4 tab"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-trace! cancel-cascade-buffer)
      (rf/dispatch-sync [:rf.xray/focus-trace-entry
                         {:dispatch-id 7 :frame :rf/default :trace-id 1}])
      (let [selected   @(rf/subscribe [:rf.xray/selected-tab])
            live-tabs  (panel-registry/tab-ids-for-mode :dynamic)]
        (is (contains? live-tabs selected)
            (str "row-jump selected " (pr-str selected)
                 " which is NOT a live Dynamic L4 tab "
                 (pr-str live-tabs) " — it would render the unknown-tab stub"))
        (is (not= :event selected)
            "the retired `:event` tab id must never be selected again")
        ;; Lock the specific live replacement: Epoch is the cascade-
        ;; pipeline master surface the `:rf.xray/select-dispatch-id` pin
        ;; drives. If the row-jump target moves, this assertion is the
        ;; deliberate update point.
        (is (= :epoch selected)
            "the row jump lands on the Epoch tab (the focused-cascade detail)")))))

(deftest focus-trace-entry-without-dispatch-id-does-not-switch-tab
  ;; The tab flip is gated on `dispatch-id` — a row with no addressable
  ;; dispatch (e.g. an actor-destroy abort outside a drain) must NOT
  ;; switch the tab at all (so it certainly can't land an unknown tab).
  (testing "a no-dispatch-id row jump leaves the selected tab unchanged"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-trace! cancel-cascade-buffer)
      (let [before @(rf/subscribe [:rf.xray/selected-tab])]
        (rf/dispatch-sync [:rf.xray/focus-trace-entry {:trace-id 1}])
        (is (= before @(rf/subscribe [:rf.xray/selected-tab]))
            "no dispatch-id ⇒ the tab selection is untouched")))))

;; ---- (5) collapse / expand ---------------------------------------------

(deftest expander-toggles-expanded-slot
  (testing "the expand-toggle event flips the `:expanded?` slot"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (is (false? @(rf/subscribe [:rf.xray/cancellation-cascade-expanded?])))
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-toggle-expand])
      (is (true?  @(rf/subscribe [:rf.xray/cancellation-cascade-expanded?])))
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-toggle-expand])
      (is (false? @(rf/subscribe [:rf.xray/cancellation-cascade-expanded?]))))))

(deftest expander-renders-when-aborts-exceed-threshold
  (testing "with > default-collapse-threshold aborts the expander
            appears under the abort list"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [decision    {:id 1 :operation :rf.event/dispatched :op-type :rf.event
                         :time 1000
                         :tags {:rf.event/v [:checkout/cancel]
                                :rf.trace/dispatch-id 9 :frame :rf/default}}
            destroy     {:id 2 :operation :rf.machine/destroyed
                         :op-type :rf.machine :time 1010
                         :tags {:machine-id :checkout :reason :explicit
                                :rf.trace/dispatch-id 9 :frame :rf/default}}
            many-aborts (for [n (range 15)]
                          {:id        (+ 100 n)
                           :operation :rf.http/aborted-on-actor-destroy
                           :op-type   :rf.http
                           :time      (+ 1020 n)
                           :tags      {:request-id (keyword (str "r" n))
                                       :url        (str "/api/x" n)
                                       :actor-id   :checkout
                                       :rf.trace/dispatch-id 9
                                       :frame      :rf/default}})]
        (seed-trace! (concat [decision destroy] many-aborts))
        (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                           {:kind :dispatch-id :id 9}])
        (let [tree (cc/Popover)]
          (is (some? (find-by-testid tree "rf-xray-cancellation-cascade-expander"))
              "expander present when collapsed-by-default kicks in")
          (let [shown-when-collapsed
                (find-all-by-testid-prefix
                  tree "rf-xray-cancellation-cascade-abort-row-")]
            (is (<= (count shown-when-collapsed) 5)
                "collapsed view shows at most 5 abort rows by default")))))))

;; ---- (6) frame isolation ----------------------------------------------

(deftest popover-state-isolated-on-rf-xray
  (testing "the popover slot lives on :rf/xray, not on the default
            frame — the host's app-db never sees these keys"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
      (is (true? @(rf/subscribe [:rf.xray/cancellation-cascade-popover-open?]))
          "open slot reads true under the :rf/xray frame"))
    (rf/with-frame :rf/default
      (let [db @(rf/subscribe [:rf/app-db])]
        (is (not (contains? db :cancellation-cascade-popover-open?))
            "no leak into the host's :rf/default frame")))))

;; ---- (7) Modal positioning (rf2-om6fa) -------------------------------

(deftest popover-backdrop-defaults-to-fixed-positioning
  (testing "with no :rf.xray/modal-positioning slot set, the
            cancellation-cascade popover backdrop renders position:
            fixed at the production z-index"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil]))
    (rf/with-frame :rf/xray
      (let [tree     (cc/Popover)
            backdrop (find-by-testid tree "rf-xray-cancellation-cascade-popover-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "fixed" (:position style)))
        (is (= 2147483644 (:z-index style)))
        (is (= "fixed"
               (:data-rf-xray-modal-positioning (second backdrop))))))))

(deftest popover-backdrop-honours-absolute-positioning
  (testing "after `:rf.xray/set-modal-positioning :absolute` the
            cancellation-cascade backdrop switches to position:
            absolute with a sane in-cell z-index"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
      (rf/dispatch-sync [:rf.xray/set-modal-positioning :absolute]))
    (rf/with-frame :rf/xray
      (let [tree     (cc/Popover)
            backdrop (find-by-testid tree "rf-xray-cancellation-cascade-popover-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "absolute" (:position style)))
        (is (< (:z-index style) 1000))
        (is (= "absolute"
               (:data-rf-xray-modal-positioning (second backdrop))))))))

;; ---- (8) dialog Esc keydown (rf2-op8c7) --------------------------------
;;
;; The popover DIALOG's `:on-key-down` MUST be the BUILT handler
;; `(handle-popover-keydown dispatch)`, not the bare 1-arity builder.
;; With the bug, React called the builder with the keydown event and
;; discarded the handler fn it RETURNED, so a dialog-focused Esc was a
;; no-op — and the `a11y/dialog-ref` focus trap kept that Esc from ever
;; reaching the backdrop's correctly-built handler.
;;
;; Idiom mirrors `edn_inspector_popup_cljs_test`: pull the rendered
;; dialog node's `:on-key-down` (exercising the actual wiring at the
;; bug site), redef `rf/dispatch` to capture the dispatched event, fire
;; a fake Escape keydown through it, and assert the close event was
;; dispatched. With the BUILT handler the close event is captured; with
;; the bare builder the call returns an inner fn and dispatches NOTHING
;; (capture stays nil) — so this assertion is the discriminating guard.

(defn- fake-keydown-event
  "Minimal stand-in for a React keydown SyntheticEvent — `.-key` plus
  the no-op `preventDefault` / `stopPropagation` the handler calls."
  [key]
  #js {:key             key
       :preventDefault  (fn [])
       :stopPropagation (fn [])})

(deftest popover-dialog-esc-keydown-dispatches-close
  (testing "an Escape keydown on the popover DIALOG invokes the BUILT
            keydown handler and dispatches :cancellation-cascade-close
            — guards against the bare-builder no-op (rf2-op8c7)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
      (let [tree     (cc/Popover)
            dialog   (find-by-testid tree "rf-xray-cancellation-cascade-popover-dialog")
            on-key   (:on-key-down (second dialog))
            captured (atom nil)]
        (is (some? dialog) "dialog node rendered")
        (is (fn? on-key) "dialog carries an :on-key-down handler")
        (with-redefs [rf/dispatch-impl (fn [event-v & _] (reset! captured event-v))]
          (on-key (fake-keydown-event "Escape")))
        (is (= [:rf.xray/cancellation-cascade-close] @captured)
            "Esc on the dialog dispatched the close event (built handler invoked)")))))

(deftest popover-dialog-keydown-ignores-non-escape
  (testing "a non-Escape keydown on the dialog dispatches NOTHING (the
            built handler keys on 'Escape')"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
      (let [tree     (cc/Popover)
            dialog   (find-by-testid tree "rf-xray-cancellation-cascade-popover-dialog")
            on-key   (:on-key-down (second dialog))
            captured (atom nil)]
        (with-redefs [rf/dispatch-impl (fn [event-v & _] (reset! captured event-v))]
          (on-key (fake-keydown-event "Enter")))
        (is (nil? @captured)
            "Enter on the dialog dispatched nothing")))))

;; ---------------------------------------------------------------------------
;; rf2-ppzid — React unique-key warning regression guard.
;;
;; The cascade `body` previously attached `^{:key …}` reader meta to two
;; function-call list forms — `(teardown-row t)` and `(abort-row row last?)`.
;; Reagent's `get-react-key` only reads `:key` from vector meta, so the
;; keys were silently lost. The fix routes both per-row children through
;; `with-meta` so the `:key` meta lands on the returned `[:div …]` vector.
;; This test asserts every teardown-row + abort-row child carries `:key`
;; meta so the regression cannot recur silently. (rf2-ppzid)
;;
;; Note: the `expand-fn-component` walker above strips element meta
;; (via `mapv`), so this assertion re-walks the raw rendered tree
;; without fn expansion to keep keyed vectors intact.
;; ---------------------------------------------------------------------------

(defn- meta-preserving-children [node]
  (cond
    (and (vector? node) (fn? (first node)))
    [(apply (first node) (rest node))]

    (vector? node)
    (if (map? (second node))
      (drop 2 node)
      (rest node))

    (seq? node)
    node

    :else nil))

(defn- raw-find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (tree-seq (some-fn vector? seq?) meta-preserving-children tree)))

(deftest cascade-body-rows-carry-key-meta
  (testing "teardown-row + abort-row children of the cascade body
            carry :key meta so React's unique-key prop warning cannot
            recur (rf2-ppzid)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-trace! cancel-cascade-buffer)
      (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                         {:kind :dispatch-id :id 7}])
      (let [tree         (cc/Popover)
            teardowns    (raw-find-by-testid tree "rf-xray-cancellation-cascade-teardowns")
            aborts       (raw-find-by-testid tree "rf-xray-cancellation-cascade-aborts")
            keyed-children (fn [container]
                             ;; Container shape is `[:div attrs <doall-seq>]`
                             ;; — the seq lives at index 2.
                             (->> (nth container 2)
                                  (filter vector?)))]
        (is (some? aborts) "aborts container rendered for the fixture")
        (when teardowns
          (doseq [row (keyed-children teardowns)]
            (is (vector? row) "teardown row is a hiccup vector")
            (is (some? (some-> (meta row) :key))
                (str "teardown row carries :key meta — got " (pr-str (meta row))))))
        (let [abort-rows (keyed-children aborts)]
          (is (= 2 (count abort-rows)) "two abort rows from the fixture")
          (doseq [row abort-rows]
            (is (vector? row) "abort row is a hiccup vector")
            (is (some? (some-> (meta row) :key))
                (str "abort row carries :key meta — got " (pr-str (meta row))))))))))

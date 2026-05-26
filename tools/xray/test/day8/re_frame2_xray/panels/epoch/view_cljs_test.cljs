(ns day8.re-frame2-xray.panels.epoch.view-cljs-test
  "View-layer tests for the Epoch panel cascade (rf2-sc3r1 follow-ons).

  Pure hiccup tests — each render-fn is exercised against a synthesised
  step row and walked via the framework's hiccup walker. No DOM mount;
  no substrate spin. Anchored on `data-testid`s the view stamps onto
  every step body."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.epoch.view :as view]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-orchestrator]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-test-support/reset-all!}))

;; ---- helpers -----------------------------------------------------------

(defn- text-of
  [tree testid]
  (some-> tree (th/find-by-testid testid) th/text-content))

;; ---- rf2-9jvx1 — text-duplication audit --------------------------------

(deftest dispatch-source-renders-once-test
  (testing "rf2-9jvx1 — DISPATCH header carries `from <source>` once;
            the body does NOT also render a `from` line"
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event [:counter/inc] :source :ui :coord nil})]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-dispatch-event"))
          "dispatch event vector renders in the body")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-dispatch-source"))
          "the body MUST NOT carry a duplicate `from <source>` row")
      (let [header-text (text-of tree "rf-xray-epoch-dispatch-header")]
        (is (string/includes? header-text "from"))
        (is (string/includes? header-text "ui"))))))

(deftest handler-flavour-renders-once-test
  (testing "rf2-9jvx1 — HANDLER header carries the flavour + event-id
            once; the body's pre-rf2-9jvx1 stand-alone flavour row is
            removed. Body's first slot is the source block (rf2-66wis),
            not a flavour pill."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-event-db :event-id :no-such/handler
                :db-diff [] :fx [] :machine nil}
          tree (view/render-handler-step step)
          header-text (text-of tree "rf-xray-epoch-handler-header")]
      (is (string/includes? header-text "reg-event-db"))
      (is (string/includes? header-text ":no-such/handler"))
      ;; The body's first slot is now the source block — never a
      ;; duplicate flavour pill.
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-source"))
          "the body leads with the source block (rf2-66wis)")
      ;; Confirm the body never carries a redundant flavour-only span.
      ;; The body text for an unknown handler resolves to the
      ;; placeholder; it MUST NOT contain the bare flavour keyword.
      (is (not (string/includes?
                 (or (text-of tree "rf-xray-epoch-handler-source-placeholder")
                     "")
                 "reg-event-db"))
          "the source-placeholder slot MUST NOT echo the flavour pill"))))

;; ---- rf2-93a7s — DISPATCH body shows the event vector ----------------

(deftest dispatch-body-shows-event-vector-test
  (testing "rf2-93a7s — DISPATCH body renders the dispatched event vector"
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event [:counter/inc 7] :source :ui :coord nil})
          body (th/find-by-testid tree "rf-xray-epoch-dispatch-event")]
      (is (some? body) "the body's event-vector slot is present")
      (is (string/includes? (th/text-content body) ":counter/inc")
          "event-id is visible in the body")
      (is (string/includes? (th/text-content body) "7")
          "the event args are visible too"))))

(deftest dispatch-body-omits-event-when-absent-test
  (testing "rf2-93a7s — empty event yields no event-vector slot
            (graceful-degrade rather than rendering `[]` noise)"
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event nil :source :ui :coord nil})]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-dispatch-event"))
          "no body row when no event vector was captured"))))

(deftest dispatch-source-label-is-clickable-button-when-coord-present-test
  (testing "rf2-80u5a — when the dispatch envelope carried a
            :rf.trace/call-site coord, the `<source>` label in the
            DISPATCH header renders as a clickable button that opens
            the editor at the dispatch call-site. The button carries
            the standard external-link icon as a secondary cue."
    (let [coord {:file "src/app/counter.cljs" :line 42 :ns 'app.counter}
          tree  (view/render-dispatch-step
                  {:step :dispatch :badge :DISPATCH :step-number 1
                   :event [:counter/inc] :source :ui :coord coord})
          label (th/find-by-testid tree "rf-xray-epoch-dispatch-source-label")]
      (is (some? label) "the dispatch source-label slot is present")
      (is (= :button (first label))
          "with a coord, the label renders as a real button (clickable)")
      (let [attrs (second label)]
        (is (fn? (:on-click attrs))
            "click handler is attached")
        (is (string/includes? (or (:title attrs) "") "counter.cljs")
            "title hints which file will open")))))

(deftest dispatch-source-label-degrades-to-plain-span-when-coord-absent-test
  (testing "rf2-80u5a — when no call-site coord is available
            (fn-form dispatch, production builds), the label
            renders as a plain `<span>` with no fake / dead
            click affordance."
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event [:counter/inc] :source :ui :coord nil})
          label (th/find-by-testid tree "rf-xray-epoch-dispatch-source-label")]
      (is (some? label) "the source-label slot is still rendered")
      (is (= :span (first label))
          "no coord → plain span (no broken button)")
      (is (nil? (:on-click (second label)))
          "no click handler on the degraded label"))))

;; ---- rf2-cq0ch — COEFFECT body --------------------------------------

(deftest coeffect-body-renders-labelled-value-test
  (testing "rf2-cq0ch — each user-injected cofx renders the id +
            value via the canonical edn-inspector. No cryptic
            `+[]nil` line."
    (let [step {:step :coeffect :badge :COEFFECT :step-number 2
                :rows [{:id :session :value {:user-id 42}}
                       {:id :rf/now :value "2026-05-26T00:00:00"}]}
          tree (view/render-coeffect-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-coeffect-row-0")))
      (is (some? (th/find-by-testid tree "rf-xray-epoch-coeffect-row-1")))
      (let [r0-id    (text-of tree "rf-xray-epoch-coeffect-row-id-0")
            r0-value (text-of tree "rf-xray-epoch-coeffect-row-value-0")
            r1-id    (text-of tree "rf-xray-epoch-coeffect-row-id-1")]
        (is (string/includes? r0-id ":session"))
        (is (string/includes? r0-value "42"))
        (is (string/includes? r1-id ":rf/now"))))))

;; ---- rf2-kfh1v — SUBSCRIPTIONS rows + filter -------------------------

(defn- count-prefix
  [tree prefix]
  (count (th/find-by-testid-prefix tree prefix)))

(deftest sub-row-renders-sub-id-test
  (testing "rf2-kfh1v — SUBSCRIPTIONS row leads with the sub-id /
            sub-vec (operator can tell WHICH sub recomputed)"
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :counter/total :sub-vec [:counter/total]
                          :inputs nil :changed? true :before 5 :after 6}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            row  (th/find-by-testid tree "rf-xray-epoch-sub-row-0")]
        (is (some? row) "the sub row renders")
        (let [txt (th/text-content row)]
          (is (string/includes? txt ":counter/total")
              "sub-id is visible as the leading element"))))))

(deftest sub-rows-hide-unchanged-by-default-test
  (testing "rf2-kfh1v — unchanged rows are hidden by default;
            a toggle reveals the full list"
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :a :sub-vec [:a] :changed? true :before 1 :after 2}
                         {:sub-id :b :sub-vec [:b] :changed? false}
                         {:sub-id :c :sub-vec [:c] :changed? false}]
                  :changed 1 :unchanged 2}
            tree (view/render-subscriptions-step step)]
        (is (= 1 (count-prefix tree "rf-xray-epoch-sub-row-"))
            "only the one changed row renders by default")
        (let [header (text-of tree "rf-xray-epoch-subscriptions-header")]
          (is (string/includes? header "1 changed")
              "header shows the changed count")
          (is (string/includes? header "2 unchanged")
              "header shows the unchanged count")
          (is (some? (th/find-by-testid
                       tree "rf-xray-epoch-subscriptions-toggle"))
              "the Show unchanged toggle is present when unchanged > 0"))))))

(deftest sub-rows-reveal-after-toggle-test
  (testing "rf2-kfh1v — toggling the show-unchanged flag reveals all
            rows; toggling back hides them again"
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :a :sub-vec [:a] :changed? true :before 1 :after 2}
                         {:sub-id :b :sub-vec [:b] :changed? false}]
                  :changed 1 :unchanged 1}]
        ;; flip the slot on
        (rf/dispatch-sync [:rf.xray.epoch/toggle-subs-show-unchanged])
        (let [tree (view/render-subscriptions-step step)]
          (is (= 2 (count-prefix tree "rf-xray-epoch-sub-row-"))
              "both rows render after toggle"))
        ;; flip back off
        (rf/dispatch-sync [:rf.xray.epoch/toggle-subs-show-unchanged])
        (let [tree (view/render-subscriptions-step step)]
          (is (= 1 (count-prefix tree "rf-xray-epoch-sub-row-"))
              "unchanged hidden again after second toggle"))))))

(deftest sub-toggle-anchors-to-xray-frame-test
  (testing "rf2-p56sk — fourth frame-leak class (rf2-7sdja popup +
            rf2-y59tb triangle + rf2-kcaiz zoom). The Show unchanged
            toggle's dispatch carries explicit `{:frame :rf/xray}`
            envelope AND the read uses the 2-arity subscribe form
            so the sub-write + sub-read are anchored to the same
            frame regardless of the surrounding render context.
            Without both anchors, the dispatch lands in `:rf/xray`'s
            app-db while the read accidentally resolves `:rf/default`
            (or any other frame) — mutation never visible to the row
            filter."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    ;; Run OUTSIDE `with-frame :rf/xray` so the dispatch path is
    ;; exercised exactly as the live shell's React onClick fires it —
    ;; the dispatch-time frame must be the envelope, NOT a lexical
    ;; binding the test artificially supplies.
    (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                :rows [{:sub-id :a :sub-vec [:a] :changed? true :before 1 :after 2}
                       {:sub-id :b :sub-vec [:b] :changed? false}]
                :changed 1 :unchanged 1}]
      ;; The button's onClick dispatches with envelope; mirror that.
      (rf/with-frame :rf/xray
        ;; Confirm the toggle button is rendered and carries the envelope.
        (let [tree (view/render-subscriptions-step step)
              btn  (th/find-by-testid tree "rf-xray-epoch-subscriptions-toggle")]
          (is (some? btn) "toggle button is present")
          (is (fn? (:on-click (second btn))))))
      ;; Dispatch with the envelope — same shape as the click handler.
      (rf/dispatch-sync [:rf.xray.epoch/toggle-subs-show-unchanged]
                        {:frame :rf/xray})
      (rf/with-frame :rf/xray
        (let [tree (view/render-subscriptions-step step)]
          (is (= 2 (count-prefix tree "rf-xray-epoch-sub-row-"))
              "envelope-anchored dispatch flips the :rf/xray slot the
               2-arity sub reads"))))))

;; ---- rf2-66wis — HANDLER source code block ---------------------------

;; ---- rf2-93436 — HANDLER :db diff sub-section (design §Section 1+2) -----

(deftest handler-db-diff-always-renders-for-non-machine-handlers-test
  (testing "rf2-93436 — `:db diff` sub-section is ALWAYS present
            inside the HANDLER body for reg-event-db / reg-event-fx.
            Empty diff renders `— (no changes)`; populated diff
            renders the path-changes."
    (testing "reg-event-db with empty diff"
      (let [tree (view/render-handler-step
                   {:step :handler :badge :HANDLER :step-number 3
                    :flavour :reg-event-db :event-id :nop
                    :db-diff [] :fx [] :machine nil})
            slot (th/find-by-testid tree "rf-xray-epoch-handler-db-diff")]
        (is (some? slot)
            ":db diff sub-section is always present even when empty")
        (is (string/includes? (or (th/text-content slot) "") "no changes")
            "empty diff renders `— (no changes)` per design §Empty edge cases")))
    (testing "reg-event-db with populated diff"
      (let [tree (view/render-handler-step
                   {:step :handler :badge :HANDLER :step-number 3
                    :flavour :reg-event-db :event-id :counter/inc
                    :db-diff [[[:counter :value] 5 6 :modified]]
                    :fx [] :machine nil})
            slot (th/find-by-testid tree "rf-xray-epoch-handler-db-diff")]
        (is (some? slot))
        (is (some? (th/find-by-testid
                     tree "rf-xray-epoch-handler-diff-row-0"))
            "the populated diff row renders inside the sub-section")))
    (testing "reg-event-fx with empty diff"
      (let [tree (view/render-handler-step
                   {:step :handler :badge :HANDLER :step-number 3
                    :flavour :reg-event-fx :event-id :navigate
                    :db-diff [] :fx [{:fx-id :navigate :value "/x"}]
                    :machine nil})
            slot (th/find-by-testid tree "rf-xray-epoch-handler-db-diff")]
        (is (some? slot)
            "even reg-event-fx that returned no :db gets the sub-section")
        (is (string/includes? (or (th/text-content slot) "") "no changes"))))))

(deftest handler-db-diff-suppressed-for-machine-handlers-test
  (testing "rf2-93436 — for machine handlers the standalone `:db diff`
            sub-section is suppressed (design §Section 3 §DB DIFF —
            folds into SNAPSHOT DIFF since the snapshot IS the
            db change at `[:rf/machines <id>]`). Avoids the redundant
            slot duplicating data already shown in SNAPSHOT DIFF."
    (let [tree (view/render-handler-step
                 {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-machine :event-id :ws/start
                  :db-diff [[[:rf/machines :ws/conn] {} {} :modified]]
                  :fx []
                  :machine {:transition nil :guards []
                            :lifecycle [] :timers []}})]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-db-diff"))
          "no standalone :db diff under machine handlers — folded into
           SNAPSHOT DIFF per design"))))

(deftest handler-body-renders-source-placeholder-test
  (testing "rf2-66wis — HANDLER body carries a source-code slot.
            When no handler-meta has been stamped the slot renders
            a clear `<source not yet captured>` placeholder rather
            than collapsing silently — operator learns where to
            look and when the substrate hasn't captured."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-event-db :event-id :no-such/handler
                :db-diff [] :fx [] :machine nil}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-source"))
          "the source slot is present even on graceful-degrade")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-source-placeholder"))
          "no-source state renders the placeholder")
      (is (string/includes?
            (or (text-of tree "rf-xray-epoch-handler-source-placeholder") "")
            "<source not yet captured>")))))

;; ---- rf2-6djth — VIEWS row carries view-id + subs ---------------------

(deftest views-row-shows-id-and-subs-test
  (testing "rf2-6djth — VIEWS table row shows the view-id + its
            consumed subs. Per-row testids are stable and the body
            text contains both halves."
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id :app.counter/Counter
                        :subs-read [[:counter/total] [:counter/threshold]]
                        :duration-ms 1.2}]}
          tree (view/render-views-step step)
          row  (th/find-by-testid tree "rf-xray-epoch-view-row-0")
          id   (text-of tree "rf-xray-epoch-view-row-id-0")
          subs (text-of tree "rf-xray-epoch-view-row-subs-0")]
      (is (some? row) "the view row renders")
      (is (string/includes? id ":app.counter/Counter")
          "view-id is the leading element")
      (is (string/includes? subs ":counter/total")
          "consumed subs appear in the subs cell")
      (is (string/includes? subs ":counter/threshold")
          "every consumed sub renders, one per line"))))

(deftest views-row-graceful-degrades-when-id-absent-test
  (testing "rf2-6djth — a row whose view-id is nil renders an
            anonymous-view placeholder rather than the bare keyword
            colon"
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id nil :subs-read [] :duration-ms nil}]}
          tree (view/render-views-step step)
          id   (text-of tree "rf-xray-epoch-view-row-id-0")]
      (is (string/includes? id "<anonymous view>")
          "missing view-id reads as `<anonymous view>` placeholder"))))

(deftest views-row-carries-pink-stripe-hover-handlers-test
  (testing "rf2-2f962 — VIEWS row carries on-mouse-enter / on-mouse-leave
            handlers that drive the `.rf-xray-view-highlight` pink-stripe
            class on the live `data-rf-view` DOM node (rf2-e33ad /
            rf2-8l03l convention). The handlers are present whether or
            not view-id is non-nil — they no-op safely when the row's
            view-id is absent."
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id :app.counter/Counter
                        :subs-read [[:counter/total]] :duration-ms 0.5}]}
          tree (view/render-views-step step)
          row  (th/find-by-testid tree "rf-xray-epoch-view-row-0")
          attrs (when (vector? row) (second row))]
      (is (some? row) "the view row renders")
      (is (fn? (:on-mouse-enter attrs))
          "row carries an on-mouse-enter handler (pink-stripe affordance)")
      (is (fn? (:on-mouse-leave attrs))
          "row carries an on-mouse-leave handler"))))

;; ---- rf2-nqt3d — per-step elapsed time + cascade total ----------------

(deftest cascade-summary-renders-total-test
  (testing "rf2-nqt3d — cascade-summary chip carries the cascade total
            formatted via `format-duration-ms`"
    (let [tree (view/cascade-summary [{:step :dispatch :duration-ms 0.5}
                                      {:step :handler  :duration-ms 12}])
          chip (th/find-by-testid tree "rf-xray-epoch-cascade-summary")]
      (is (some? chip) "the summary chip renders when any step carries a duration")
      (is (string/includes? (th/text-content chip) "cascade total"))
      (is (string/includes? (th/text-content chip) "13ms")
          "total reads as the rounded sum of every step's :duration-ms"))))

(deftest cascade-summary-elides-when-no-durations-test
  (testing "rf2-nqt3d — projection without any duration returns nil so
            the view never renders an empty `total: —` chip"
    (let [tree (view/cascade-summary [{:step :dispatch}
                                      {:step :handler}])]
      (is (nil? tree)
          "no durations → no summary chip (graceful-degrade)"))))

(deftest cascade-summary-shows-long-step-count-test
  (testing "rf2-nqt3d — when any step exceeds 16ms the summary surfaces
            a warning-tone count chip"
    (let [tree (view/cascade-summary [{:step :handler :duration-ms 12}
                                      {:step :views   :duration-ms 25}
                                      {:step :fx      :duration-ms 30}])
          long-chip (th/find-by-testid tree "rf-xray-epoch-cascade-summary-long-count")]
      (is (some? long-chip) "long-count chip present when any step is over 16ms")
      (is (string/includes? (th/text-content long-chip) "2 over 16ms")
          "count + threshold are visible in the chip text"))))

(deftest cascade-summary-omits-long-count-when-zero-test
  (testing "rf2-nqt3d — clean cascade (every step under threshold) → no
            long-count chip in the summary"
    (let [tree (view/cascade-summary [{:step :handler :duration-ms 0.5}
                                      {:step :views   :duration-ms 1.2}])]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-cascade-summary-long-count"))
          "no long-count chip on a fast cascade"))))

;; ---- rf2-17vxj — schema-violations section ----------------------------

(deftest schema-violations-step-renders-rows-test
  (testing "rf2-17vxj — SCHEMA VIOLATIONS step renders one row per
            violation; rollback chip rides any rollback? row"
    (let [step {:step :schema-violations :badge :SCHEMA-VIOLATIONS
                :step-number 8
                :rows [{:kind :rf.error/schema-validation-failure
                        :where :app-db
                        :failing-id :counter/inc
                        :path [:count]
                        :value "not-an-int"
                        :rollback? true
                        :sensitive? false}
                       {:kind :rf.schema/violation
                        :where :hot-reload
                        :frame :rf/default
                        :failing-id :rf/default
                        :path [:count]
                        :value "boom"
                        :recovery :logged-and-skipped
                        :rollback? false
                        :sensitive? false}]
                :rollbacks 1}
          tree (view/render-schema-violations-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-step-schema-violations"))
          "the step wrapper renders")
      (is (= 2 (count (th/find-by-testid-prefix
                        tree "rf-xray-epoch-schema-violation-row-"))))
      (is (some? (th/find-by-testid
                   tree "rf-xray-epoch-schema-violation-rollback-0"))
          "the rollback chip rides the rollback? row")
      (is (nil? (th/find-by-testid
                  tree "rf-xray-epoch-schema-violation-rollback-1"))
          "no rollback chip on a clean recovery row")
      (let [header (text-of tree "rf-xray-epoch-schema-violations-header")]
        (is (string/includes? header "2 violation"))
        (is (string/includes? header "1 rollback")))
      (is (some? (th/find-by-testid
                   tree "rf-xray-epoch-schema-violations-open-issues"))
          "the open-issues affordance is present"))))

(deftest schema-violations-row-shows-fields-test
  (testing "rf2-17vxj — per-row body shows where + failing-id + path + value"
    (let [step {:step :schema-violations :badge :SCHEMA-VIOLATIONS
                :step-number 8
                :rows [{:kind :rf.error/schema-validation-failure
                        :where :sub-return
                        :failing-id :counter/total
                        :path [:total]
                        :value {:bad :data}
                        :rollback? false
                        :sensitive? false}]
                :rollbacks 0}
          tree (view/render-schema-violations-step step)]
      (let [where (text-of tree "rf-xray-epoch-schema-violation-where-0")
            id    (text-of tree "rf-xray-epoch-schema-violation-id-0")
            path  (text-of tree "rf-xray-epoch-schema-violation-path-0")]
        (is (string/includes? where "sub return"))
        (is (string/includes? id ":counter/total"))
        (is (string/includes? path "[:total]"))))))

;; ---- rf2-uffov — FX section header + per-action attribution ----------

(deftest fx-step-header-shows-outcome-split-test
  (testing "rf2-uffov — FX step header reads
            `N fired (M succeeded, K threw)`"
    (let [step {:step :fx :badge :FX :step-number 4
                :rows [{:fx-id :db :status :ok}
                       {:fx-id :http/get :status :ok}
                       {:fx-id :bad :status :error}]
                :succeeded 2 :threw 1 :skipped 0}
          tree (view/render-fx-step step)
          header (text-of tree "rf-xray-epoch-fx-header")]
      (is (string/includes? header "3 fired"))
      (is (string/includes? header "2 succeeded"))
      (is (string/includes? header "1 threw")))))

(deftest fx-row-shows-attribution-chip-test
  (testing "rf2-uffov — when an FX row carries :attributed-to, the
            attribution chip renders alongside"
    (let [step {:step :fx :badge :FX :step-number 4
                :rows [{:fx-id :http/get :status :ok
                        :attributed-to {:action-id :open-socket
                                        :phase :entry}}]
                :succeeded 1 :threw 0 :skipped 0}
          tree (view/render-fx-step step)
          chip (th/find-by-testid tree "rf-xray-epoch-fx-row-attribution-0")]
      (is (some? chip) "the attribution chip is present")
      (is (string/includes? (th/text-content chip) ":open-socket")
          "the action-id rides the chip"))))

(deftest fx-row-omits-attribution-chip-when-none-test
  (testing "rf2-uffov — FX row without :attributed-to omits the chip"
    (let [step {:step :fx :badge :FX :step-number 4
                :rows [{:fx-id :db :status :ok}]
                :succeeded 1 :threw 0 :skipped 0}
          tree (view/render-fx-step step)]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-fx-row-attribution-0"))))))

;; ---- rf2-rrykz — app-db diff section ---------------------------------

(deftest app-db-diff-step-renders-rows-test
  (testing "rf2-rrykz — APP-DB DIFF step renders one row per changed
            path with the change kind reflected on the row data attr"
    (let [step {:step :app-db-diff :badge :APP-DB-DIFF :step-number 4
                :rows [{:path [:count] :before 0 :after 1 :change :modified}
                       {:path [:new]   :before nil :after "v" :change :added}
                       {:path [:gone]  :before 5 :after nil :change :removed}]
                :added 1 :modified 1 :removed 1}
          tree (view/render-app-db-diff-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-step-app-db-diff")))
      (is (= 3 (count (th/find-by-testid-prefix
                        tree "rf-xray-epoch-app-db-diff-row-"))))
      (let [header (text-of tree "rf-xray-epoch-app-db-diff-header")]
        (is (string/includes? header "3 paths changed"))
        (is (string/includes? header "+1 / ~1 / -1"))))))

(deftest app-db-diff-row-shows-path-and-values-test
  (testing "rf2-rrykz — :modified row shows before → after"
    (let [step {:step :app-db-diff :badge :APP-DB-DIFF :step-number 4
                :rows [{:path [:counter :value] :before 5 :after 6
                        :change :modified}]
                :added 0 :modified 1 :removed 0}
          tree (view/render-app-db-diff-step step)
          row  (th/find-by-testid tree "rf-xray-epoch-app-db-diff-row-0")]
      (is (some? row))
      (let [txt (th/text-content row)]
        (is (string/includes? txt "[:counter :value]")
            "path is visible")
        (is (string/includes? txt "5"))
        (is (string/includes? txt "6"))))))

;; ---- rf2-yx1ae — child-dispatches section -----------------------------

(deftest child-dispatches-step-renders-rows-test
  (testing "rf2-yx1ae — CHILD-DISPATCHES step renders one row per child
            with via-chip + event vector"
    (let [step {:step :child-dispatches :badge :CHILD-DISPATCHES
                :step-number 5
                :rows [{:event [:other/x 1] :via :dispatch :delay-ms nil}
                       {:event [:retry]     :via :dispatch-later :delay-ms 250}]}
          ctx  {:dispatch-id   1
                :epoch-history [{:epoch-id 99 :parent-dispatch-id 1
                                 :trigger-event [:other/x 1]}]}
          tree (view/render-child-dispatches-step step ctx)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-step-child-dispatches")))
      (is (= 2 (count (th/find-by-testid-prefix
                        tree "rf-xray-epoch-child-dispatch-row-"))))
      (let [header (text-of tree "rf-xray-epoch-child-dispatches-header")]
        (is (string/includes? header "2 events dispatched")))
      (let [via0 (text-of tree "rf-xray-epoch-child-dispatch-via-0")
            via1 (text-of tree "rf-xray-epoch-child-dispatch-via-1")
            ev0  (text-of tree "rf-xray-epoch-child-dispatch-event-0")
            ev1  (text-of tree "rf-xray-epoch-child-dispatch-event-1")]
        (is (string/includes? via0 "dispatch"))
        (is (string/includes? via1 "dispatch-later"))
        (is (string/includes? ev0  ":other/x"))
        (is (string/includes? ev1  ":retry")))
      ;; delay chip on the dispatch-later row only
      (is (some? (th/find-by-testid tree "rf-xray-epoch-child-dispatch-delay-1")))
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-child-dispatch-delay-0"))))))

(deftest child-dispatches-jump-resolves-when-in-buffer-test
  (testing "rf2-yx1ae — child epoch in the buffer renders a jump button
            with the child's :epoch-id"
    (let [step {:step :child-dispatches :badge :CHILD-DISPATCHES
                :step-number 5
                :rows [{:event [:other/x 1] :via :dispatch}]}
          ctx  {:dispatch-id   1
                :epoch-history [{:epoch-id 99 :parent-dispatch-id 1
                                 :trigger-event [:other/x 1]}]}
          tree (view/render-child-dispatches-step step ctx)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-child-dispatch-jump-0"))
          "jump button is present when child is resolvable")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-child-dispatch-missing-0"))
          "no `not in buffer` marker when child is resolved"))))

(deftest child-dispatches-missing-when-not-in-buffer-test
  (testing "rf2-yx1ae — child not in buffer (aged out / never landed)
            renders the muted `not in buffer` marker"
    (let [step {:step :child-dispatches :badge :CHILD-DISPATCHES
                :step-number 5
                :rows [{:event [:other/x 1] :via :dispatch}]}
          ctx  {:dispatch-id   1
                :epoch-history []}
          tree (view/render-child-dispatches-step step ctx)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-child-dispatch-missing-0")))
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-child-dispatch-jump-0"))))))

;; ---- rf2-9c27r — machine handler section -------------------------------

(deftest machine-handler-renders-data-reduction-test
  (testing "rf2-9c27r — DATA REDUCTION sub-section renders `:data`
            before / after when they differ"
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:transition {:machine-id :ws/conn
                                       :before {:state [:idle]      :data {:count 0}}
                                       :after  {:state [:connected] :data {:count 1}}
                                       :data-before {:count 0}
                                       :data-after  {:count 1}
                                       :microsteps 0}
                          :guards []
                          :lifecycle []
                          :timers []}}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-machine-data-reduction"))
          "DATA REDUCTION block renders when data changed")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-machine-data-before")))
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-machine-data-after"))))))

(deftest machine-handler-renders-snapshot-diff-test
  (testing "rf2-9c27r — SNAPSHOT DIFF sub-section renders the full
            machine snapshot before / after"
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:transition {:machine-id :ws/conn
                                       :before {:state [:idle]}
                                       :after  {:state [:connected]}
                                       :microsteps 1}
                          :guards [] :lifecycle [] :timers []}}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-machine-snapshot-diff")))
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-machine-microsteps"))
          "microsteps slot is surfaced under the transition summary"))))

(deftest machine-handler-omits-data-reduction-when-unchanged-test
  (testing "rf2-9c27r — DATA REDUCTION elides when before == after"
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:transition {:machine-id :ws/conn
                                       :before {:state [:idle] :data {:n 1}}
                                       :after  {:state [:connected] :data {:n 1}}
                                       :data-before {:n 1}
                                       :data-after  {:n 1}
                                       :microsteps 0}
                          :guards [] :lifecycle [] :timers []}}
          tree (view/render-handler-step step)]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-machine-data-reduction"))
          "no DATA REDUCTION block when both sides are identical"))))

(deftest machine-handler-per-action-fx-attribution-test
  (testing "rf2-9c27r — per-action fx attribution renders under the
            lifecycle row when the action returned :fx"
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:transition nil
                          :guards []
                          :lifecycle [{:action-id :open-socket
                                       :phase :entry
                                       :outcome {:fx [[:http/get {:url "/x"}]]}
                                       :fx [[:http/get {:url "/x"}]]}]
                          :timers []}}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-phase-entry-row-0"))
          "the lifecycle row renders")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-phase-entry-fx-0"))
          "per-action fx attribution shows under the row"))))

(deftest duration-chip-renders-long-step-warning-test
  (testing "rf2-nqt3d — a step header's duration chip paints warning
            chrome when over 16ms"
    (let [tree (view/render-handler-step
                 {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-event-db :event-id :rf.test.epoch.view/slow-handler
                  :db-diff [] :fx [] :machine nil
                  :duration-ms 42})]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-duration-long"))
          "the long-duration testid is stamped on slow steps")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-duration"))
          "the bare-duration testid is NOT stamped on long steps")))

  (testing "rf2-nqt3d — a fast step keeps the bare-duration chip"
    (let [tree (view/render-handler-step
                 {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-event-db :event-id :rf.test.epoch.view/fast-handler
                  :db-diff [] :fx [] :machine nil
                  :duration-ms 0.1})]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-duration"))
          "fast step keeps the standard duration testid"))))

(deftest handler-body-renders-captured-source-test
  (testing "rf2-66wis — when the registrar carries an
            `:rf.handler/source`, the body renders it via the
            canonical `edn/code-block` widget"
    (rf/with-frame :rf/default
      (rf/reg-event-db :rf.test.epoch.view/srctest-handler
                       {:rf.handler/source "(reg-event-db :rf.test.epoch.view/srctest-handler\n  (fn [db _] (assoc db :ok true)))"}
                       (fn [db _] db))
      (let [step {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-event-db
                  :event-id :rf.test.epoch.view/srctest-handler
                  :db-diff [] :fx [] :machine nil}
            tree (view/render-handler-step step)]
        (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-source-body"))
            "the code-block widget mounts under the source slot")
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-source-placeholder"))
            "no placeholder when source IS captured")))))

;; ---- rf2-ehd8v — HANDLER source carries file:line + [open] ----------

(deftest handler-source-renders-file-line-and-open-affordance-test
  (testing "rf2-ehd8v — when the handler-meta carries :file + :line the
            HANDLER source sub-header renders `file:line` text and a
            clickable `[open]` button next to the SOURCE label.
            Reuses the cross-panel coord-chip (same affordance Static
            Handler + Event panels ship)."
    (rf/with-frame :rf/default
      (rf/reg-event-db :rf.test.epoch.view/ehd8v-srctest-handler
                       {:rf.handler/source "(reg-event-db :rf.test.epoch.view/ehd8v-srctest-handler\n  (fn [db _] (update db :n inc)))"
                        :file "src/app/counter.cljs"
                        :line 42}
                       (fn [db _] db))
      (let [step {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-event-db
                  :event-id :rf.test.epoch.view/ehd8v-srctest-handler
                  :db-diff [] :fx [] :machine nil}
            tree (view/render-handler-step step)
            coord (th/find-by-testid tree "rf-xray-epoch-handler-source-coord")
            text  (th/find-by-testid tree "rf-xray-epoch-handler-source-coord-text")
            open  (th/find-by-testid tree "rf-xray-epoch-handler-source-open")]
        (is (some? coord)
            "the source-coord chrome row is present alongside the label")
        (is (some? text)
            "the file:line text element is present")
        (is (string/includes? (or (th/text-content text) "") "src/app/counter.cljs")
            "the file path appears in the text")
        (is (string/includes? (or (th/text-content text) "") "42")
            "the line number appears in the text")
        (is (some? open)
            "the [open] coord-chip is present when :file is captured")
        (is (= :button (first open))
            "the open affordance is a real button (clickable)")
        (is (fn? (:on-click (second open)))
            "the open button has a click handler")))))

(deftest handler-source-elides-affordance-when-coord-absent-test
  (testing "rf2-ehd8v — when no handler-meta is registered for the
            event-id (unregistered handler, production builds with
            goog.DEBUG=false where source-coords elide), the
            file:line text + [open] button simply do not render. No
            broken / dead-link affordance.

            Reg-event-* macros automatically stamp :file/:line at
            expansion time, so the empty-coord state is most cleanly
            reproduced by pointing at an event-id with no registered
            handler at all."
    (rf/with-frame :rf/default
      (let [step {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-event-db
                  :event-id :rf.test.epoch.view/ehd8v-never-registered
                  :db-diff [] :fx [] :machine nil}
            tree (view/render-handler-step step)]
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-source-coord"))
            "no coord-row when :file meta is absent")
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-source-open"))
            "no [open] affordance without a coord")))))

;; Machine-handler path is exercised by the rf2-66wis tests above; the
;; coord-resolution is a shared helper (`coord-from-handler-meta`) so the
;; event-handler test above pins the affordance shape, and the machine
;; render-path inherits it without a separate test. Reg-machine stamps
;; source coords under the `:event` kind (Spec 005 §Registration —
;; see core/test/.../source_coords_test.clj) while the source-spec
;; lookup walks the `:machine` slot, so a tight machine-path coord test
;; would have to re-create both halves of that side-table dance.

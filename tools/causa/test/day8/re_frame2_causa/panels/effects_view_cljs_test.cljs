(ns day8.re-frame2-causa.panels.effects-view-cljs-test
  "CLJS-side wiring + view tests for Causa's Effects panel
  (Phase 5, rf2-ts41u).

  ## What's under test (in addition to the pure-data tests in
  `effects_helpers_cljs_test.cljc`)

    1. **Registry wires the composite sub** under
       `:rf.causa/effects-data`. The composite returns rows + counts
       + selection in the shape the view consumes.

    2. **Empty state** — with no registered fxs and no override,
       the panel renders 'No fx registered.'

    3. **Populated list** — with a registered-fxs override the
       panel renders one row per fx + the summary header.

    4. **Outcome badge** — with a `:rf.fx/handled` trace event in
       the buffer, the matching row carries the `:ok` badge.

    5. **Stub indicator** — with a `:rf.fx/override-applied` event
       in the buffer for an fx-id, the row's STUB indicator surfaces.

    6. **Fx selection** — clicking a row fires
       `:rf.causa/select-fx-id`; the panel highlights the selection.

    7. **Frame isolation** — the panel's state lives on `:rf/causa`,
       never on `:rf/default`.

  ## Pure hiccup

  Same approach as `flows_view_cljs_test` — walk the view's hiccup
  tree by `data-testid` rather than mounting to the DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.effects :as effects]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walkers (mirror flows_view_cljs_test) -----------------------

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

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- override-fxs! [m]
  (rf/dispatch-sync [:rf.causa/set-registered-fxs-override-for-test m]))

(defn- push-trace! [ev]
  ;; Per rf2-e9s81: `:rf.causa/trace-buffer` thunks the trace-bus
  ;; atom; pushing via `collect-trace!` (the production path) lands
  ;; the event in the atom and the next subscribe sees it.
  (trace-bus/collect-trace! ev))

;; ---- (1) registry wires the composite sub -------------------------------

(deftest registry-installs-effects-subs-and-events
  (testing "register-causa-handlers! installs the Phase 5 (rf2-ts41u)
            composite sub + every supporting event"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/effects-data)))
    (is (some? (registrar/handler :sub :rf.causa/registered-fxs)))
    (is (some? (registrar/handler :sub :rf.causa/fx-trace-events)))
    (is (some? (registrar/handler :sub :rf.causa/selected-fx-id)))
    (is (some? (registrar/handler :event :rf.causa/select-fx-id)))
    (is (some? (registrar/handler :event :rf.causa/clear-fx-selection)))
    (is (some? (registrar/handler :event
                                  :rf.causa/set-registered-fxs-override-for-test)))))

(deftest effects-data-sub-defaults-empty
  (testing "with no override and no fxs registered the composite
            returns empty rows + zero total"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-fxs! {})
      (let [data @(rf/subscribe [:rf.causa/effects-data])]
        (is (= [] (:rows data)))
        (is (= 0  (:total data)))
        (is (nil? (:selected-fx-id data)))))))

(deftest effects-data-sub-projects-override-into-rows
  (testing "with a registered-fxs override the composite returns one
            row per entry — projected via the helpers"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-fxs!
        {:my/notify  {:platforms #{:client}
                      :doc "Show a toast"}
         :my/persist {:platforms #{:client :server}}})
      (let [data @(rf/subscribe [:rf.causa/effects-data])
            ids  (set (map :fx-id (:rows data)))]
        (is (= 2 (:total data)))
        (is (= #{:my/notify :my/persist} ids))))))

;; ---- (2) view renders ---------------------------------------------------

(deftest empty-state-renders-when-no-fxs
  (testing "with no registered fxs the panel renders the empty state"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-fxs! {})
      (let [tree (effects/Panel)]
        (is (some? (find-by-testid tree "rf-causa-fx"))
            "panel container present")
        (is (some? (find-by-testid tree "rf-causa-fx-empty"))
            "empty-state container present")
        (is (nil? (find-by-testid tree "rf-causa-fx-list"))
            "no list when there are zero fxs")))))

(deftest list-renders-when-fxs-populated
  (testing "with a populated override the panel renders one row per fx
            plus the summary header"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-fxs!
        {:my/notify  {:platforms #{:client}}
         :my/persist {:platforms #{:client :server}}})
      (let [tree (effects/Panel)]
        (is (some? (find-by-testid tree "rf-causa-fx-list"))
            "list container present")
        (is (some? (find-by-testid tree "rf-causa-fx-row-:my/notify"))
            "row for :my/notify present")
        (is (some? (find-by-testid tree "rf-causa-fx-row-:my/persist"))
            "row for :my/persist present")
        (is (some? (find-by-testid tree "rf-causa-fx-summary"))
            "summary header present")))))

(deftest row-renders-fx-id
  (testing "each row carries the fx-id"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-fxs!
        {:my/notify {:platforms #{:client}}})
      (let [tree (effects/Panel)]
        (is (some? (find-by-testid tree "rf-causa-fx-id-:my/notify")))))))

(deftest never-invoked-badge-renders-by-default
  (testing "an fx with no prior trace events surfaces the :never-invoked
            badge"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-fxs!
        {:my/notify {:platforms #{:client}}})
      (let [tree (effects/Panel)]
        (is (some? (find-by-testid tree "rf-causa-fx-badge-never-invoked")))))))

;; ---- (3) outcome surfaces from trace events -----------------------------

(deftest ok-badge-renders-when-fx-handled
  (testing "with a :rf.fx/handled trace event in the buffer, the
            matching row carries the :ok badge + invocation counter"
    (setup-causa-frame!)
    ;; Seed the trace buffer BEFORE the first subscribe — mirrors the
    ;; production sequencing where preload's trace-cb fires before any
    ;; panel mounts.
    (push-trace! {:operation :rf.fx/handled
                  :op-type   :fx
                  :id        100
                  :time      100
                  :tags      {:fx-id       :my/notify
                              :frame       :rf/default
                              :dispatch-id 42}})
    (rf/with-frame :rf/causa
      (override-fxs!
        {:my/notify {:platforms #{:client}}})
      (let [tree (effects/Panel)]
        (is (some? (find-by-testid tree "rf-causa-fx-badge-ok"))
            ":ok badge surfaces")
        (is (some? (find-by-testid tree
                                   "rf-causa-fx-row-invocations-:my/notify"))
            "invocation counter surfaces on the row")))))

(deftest skipped-badge-renders-when-fx-skipped-on-platform
  (testing ":rf.fx/skipped-on-platform surfaces the :skipped badge"
    (setup-causa-frame!)
    (push-trace! {:operation :rf.fx/skipped-on-platform
                  :op-type   :warning
                  :id        100
                  :time      100
                  :tags      {:fx-id       :my/notify
                              :frame       :rf/default
                              :platform    :server
                              :registered-platforms #{:client}
                              :dispatch-id 42}})
    (rf/with-frame :rf/causa
      (override-fxs!
        {:my/notify {:platforms #{:client}}})
      (let [tree (effects/Panel)]
        (is (some? (find-by-testid tree "rf-causa-fx-badge-skipped")))))))

(deftest error-badge-renders-when-fx-handler-throws
  (testing ":rf.error/fx-handler-exception surfaces the :error badge"
    (setup-causa-frame!)
    (push-trace! {:operation :rf.error/fx-handler-exception
                  :op-type   :error
                  :id        100
                  :time      100
                  :tags      {:fx-id             :my/notify
                              :fx-args           {}
                              :exception-message "boom"
                              :dispatch-id       42}})
    (rf/with-frame :rf/causa
      (override-fxs!
        {:my/notify {:platforms #{:client}}})
      (let [tree (effects/Panel)]
        (is (some? (find-by-testid tree "rf-causa-fx-badge-error")))))))

;; ---- (4) stub indicator -------------------------------------------------

(deftest stub-indicator-renders-when-override-applied
  (testing "with a :rf.fx/override-applied event in the buffer, the
            STUB indicator surfaces on the matching row + the
            :overridden badge"
    (setup-causa-frame!)
    (push-trace! {:operation :rf.fx/override-applied
                  :op-type   :fx
                  :id        100
                  :time      100
                  :tags      {:from        :my/notify
                              :to          :my/notify-stub
                              :dispatch-id 42}})
    (rf/with-frame :rf/causa
      (override-fxs!
        {:my/notify {:platforms #{:client}}})
      (let [tree (effects/Panel)]
        (is (some? (find-by-testid tree "rf-causa-fx-badge-overridden"))
            ":overridden badge surfaces")
        (is (some? (find-by-testid tree
                                   "rf-causa-fx-row-stub-:my/notify"))
            "STUB indicator surfaces on the row")))))

(deftest stub-indicator-absent-without-override
  (testing "without an override-applied event the STUB indicator
            does not render"
    (setup-causa-frame!)
    (push-trace! {:operation :rf.fx/handled
                  :op-type   :fx
                  :id        100
                  :time      100
                  :tags      {:fx-id       :my/notify
                              :dispatch-id 42}})
    (rf/with-frame :rf/causa
      (override-fxs!
        {:my/notify {:platforms #{:client}}})
      (let [tree (effects/Panel)]
        (is (nil? (find-by-testid tree
                                  "rf-causa-fx-row-stub-:my/notify"))
            "STUB indicator absent when no override active")))))

;; ---- (5) selection ------------------------------------------------------

(deftest select-fx-event-writes-to-causa-frame
  (testing ":rf.causa/select-fx-id stores the fx-id on the Causa frame"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-fx-id :my/notify])
      (is (= :my/notify @(rf/subscribe [:rf.causa/selected-fx-id]))))))

(deftest clear-fx-selection-drops-selection
  (testing ":rf.causa/clear-fx-selection dissocs the selected fx-id"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-fx-id :my/notify])
      (rf/dispatch-sync [:rf.causa/clear-fx-selection])
      (is (nil? @(rf/subscribe [:rf.causa/selected-fx-id]))))))

;; ---- (6) summary chips render -------------------------------------------

(deftest summary-renders-one-chip-per-non-zero-outcome
  (testing "the summary header renders one chip per outcome with a
            non-zero row count"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-fxs!
        {:a {:platforms #{:client}}
         :b {:platforms #{:client}}})
      ;; Both fxs :never-invoked — exactly one chip should render.
      (let [tree  (effects/Panel)
            chips (find-all-by-testid-prefix tree "rf-causa-fx-summary-")]
        (is (= 1 (count chips))
            "one chip for the :never-invoked outcome")))))

;; ---- (7) frame isolation ------------------------------------------------

(deftest fx-selection-does-not-leak-into-default-frame
  (testing "the panel's selection state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/select-fx-id :my/notify]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (= :my/notify (:selected-fx-id causa-db))
          "selection lands on Causa")
      (is (nil? (:selected-fx-id default-db))
          "selection did NOT leak into :rf/default"))))

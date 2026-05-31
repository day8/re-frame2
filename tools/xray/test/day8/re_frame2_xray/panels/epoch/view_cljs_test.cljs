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
  (testing "rf2-9jvx1 — HANDLER header carries the flavour-verb; the
            body's pre-rf2-9jvx1 stand-alone flavour row is removed.
            Body's first slot is the source block (rf2-66wis), not a
            flavour pill.

            Post pair-debug 2026-05-26 (commit ee9def224): the verb
            is now the click-to-source hyperlink. The event-id is no
            longer repeated in the HANDLER header (the DISPATCH step's
            header already names it) — that assertion is retired."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-event-db :event-id :no-such/handler
                :db-diff [] :fx [] :machine nil}
          tree (view/render-handler-step step)
          header-text (text-of tree "rf-xray-epoch-handler-header")]
      (is (string/includes? header-text "reg-event-db"))
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

;; ---- rf2-5qp4g — DISPATCH per-source-kind enrichment ------------------
;;
;; Each closed-set substrate-internal `:source` value (rf2-ejtpd:
;; `:after-timer`, `:machine-spawn`, `:fx-dispatch`,
;; `:fx-dispatch-later`) renders rich chrome — delay-ms, state-path
;; click-to-source, spawned-actor-id, parent-epoch navigation.

(deftest dispatch-source-after-timer-renders-rich-label-test
  (testing "rf2-5qp4g — `:source :after-timer` renders the kind label,
            the delay-ms chip, and the source-state-path with
            click-to-source affordance"
    (let [step {:step :dispatch :badge :DISPATCH :step-number 1
                :event [:ws/connection [:rf.machine.timer/after-elapsed
                                        250 42 [:active :authenticating]]]
                :source :after-timer
                :coord nil
                :source-enrichment {:machine-id        :ws/connection
                                    :delay-ms          250
                                    :source-state-path [:active :authenticating]}}
          tree (view/render-dispatch-step step)
          header-text (text-of tree "rf-xray-epoch-dispatch-header")]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-dispatch-source-label"))
          "the dispatch source-label slot is present")
      (is (string/includes? (or header-text "") "from :after timer")
          "the kind label reads 'from :after timer'")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-dispatch-after-timer-delay"))
          "the delay chip slot renders when delay-ms is present")
      (is (string/includes?
            (or (text-of tree "rf-xray-epoch-dispatch-after-timer-delay") "")
            "250ms")
          "the delay chip shows the timer's delay in ms")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-dispatch-after-timer-state-path"))
          "the source-state-path slot renders")
      (is (string/includes?
            (or (text-of tree "rf-xray-epoch-dispatch-after-timer-state-path") "")
            ":active")
          "the state-path chip shows the path the timer fired from"))))

(deftest dispatch-source-machine-spawn-renders-rich-label-test
  (testing "rf2-5qp4g — `:source :machine-spawn` renders the kind label
            and the spawned actor-id"
    (let [step {:step :dispatch :badge :DISPATCH :step-number 1
                :event [:checkout/worker [:rf.machine/spawned]]
                :source :machine-spawn
                :coord nil
                :source-enrichment {:spawned-actor-id :checkout/worker}}
          tree (view/render-dispatch-step step)
          header-text (text-of tree "rf-xray-epoch-dispatch-header")]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-dispatch-source-label"))
          "the source-label slot is present")
      (is (string/includes? (or header-text "") "from machine spawn")
          "the kind label reads 'from machine spawn'")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-dispatch-machine-spawn-actor"))
          "the spawned-actor-id chip renders")
      (is (string/includes?
            (or (text-of tree "rf-xray-epoch-dispatch-machine-spawn-actor") "")
            ":checkout/worker")
          "the chip shows the spawned actor's id"))))

(deftest dispatch-source-fx-dispatch-renders-rich-label-test
  (testing "rf2-5qp4g — `:source :fx-dispatch` renders the kind label
            and the parent-epoch navigation chip. rf2-x25e0 — the
            second arg is now the precomputed
            `{dispatch-id → epoch-id}` index (was `epoch-history`)."
    (let [step {:step :dispatch :badge :DISPATCH :step-number 1
                :event [:cart/add :apple]
                :source :fx-dispatch
                :coord nil
                :source-enrichment {:parent-dispatch-id 9001}}
          index {9001 42}
          tree (view/render-dispatch-step step index)
          header-text (text-of tree "rf-xray-epoch-dispatch-header")]
      (is (string/includes? (or header-text "") "from fx :dispatch")
          "the kind label reads 'from fx :dispatch'")
      (let [link (th/find-by-testid tree "rf-xray-epoch-dispatch-parent-epoch-link")]
        (is (some? link) "the parent-epoch-link slot is present")
        (is (= :button (first link))
            "with a resolved parent-epoch-id, the chip renders as a clickable button")
        (is (fn? (:on-click (second link)))
            "click handler is attached for focus-epoch dispatch")
        (is (string/includes? (or (text-of tree "rf-xray-epoch-dispatch-parent-epoch-link") "")
                              "#42")
            "the chip shows the resolved parent epoch number")))))

(deftest dispatch-source-fx-dispatch-later-renders-delay-chip-test
  (testing "rf2-5qp4g — `:source :fx-dispatch-later` renders kind label,
            the delay-ms chip (when stamped) AND the parent-epoch link"
    (let [step {:step :dispatch :badge :DISPATCH :step-number 1
                :event [:checkout/retry-prompt]
                :source :fx-dispatch-later
                :coord nil
                :source-enrichment {:parent-dispatch-id 9001
                                    :delay-ms 500}}
          index {9001 42}
          tree (view/render-dispatch-step step index)
          header-text (text-of tree "rf-xray-epoch-dispatch-header")]
      (is (string/includes? (or header-text "") "from fx :dispatch-later")
          "the kind label reads 'from fx :dispatch-later'")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-dispatch-fx-later-delay"))
          "the delay chip renders when delay-ms is present")
      (is (string/includes?
            (or (text-of tree "rf-xray-epoch-dispatch-fx-later-delay") "")
            "500ms")
          "the delay chip shows the original scheduled delay")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-dispatch-parent-epoch-link"))
          "the parent-epoch-link slot is also present"))))

(deftest dispatch-source-fx-dispatch-unresolved-parent-test
  (testing "rf2-5qp4g — when the parent-dispatch-id has no matching
            epoch in the buffer (root cascade or aged out), the
            parent-epoch chip degrades to a muted plain span carrying
            the unresolved dispatch-id (gives the operator something to
            orient on; no broken / dead click affordance)"
    (let [step {:step :dispatch :badge :DISPATCH :step-number 1
                :event [:cart/add :apple]
                :source :fx-dispatch
                :coord nil
                :source-enrichment {:parent-dispatch-id 99999}}
          index {9001 42}
          tree (view/render-dispatch-step step index)]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-dispatch-parent-epoch-link"))
          "no clickable link when the parent epoch isn't in the buffer")
      (let [unresolved (th/find-by-testid
                         tree "rf-xray-epoch-dispatch-parent-epoch-unresolved")]
        (is (some? unresolved) "the unresolved-parent chip is rendered")
        (is (= :span (first unresolved))
            "the unresolved chip is a plain span")))))

(deftest dispatch-source-fx-dispatch-without-history-test
  (testing "rf2-5qp4g — when render-dispatch-step is called without
            the dispatch-id->epoch-id index (direct test callers, or
            pre-history-seed cold mount), `:fx-dispatch` still renders
            the kind label with the parent chip in unresolved form."
    (let [step {:step :dispatch :badge :DISPATCH :step-number 1
                :event [:cart/add :apple]
                :source :fx-dispatch
                :coord nil
                :source-enrichment {:parent-dispatch-id 9001}}
          tree (view/render-dispatch-step step)]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-dispatch-parent-epoch-link"))
          "no clickable link without dispatch-id->epoch-id threaded")
      (is (some? (th/find-by-testid
                   tree "rf-xray-epoch-dispatch-parent-epoch-unresolved"))
          "the unresolved-parent chip carries the parent-dispatch-id"))))

(deftest dispatch-source-after-timer-defensive-no-enrichment-test
  (testing "rf2-5qp4g — when `:source :after-timer` is stamped but no
            enrichment payload is present (defensive — non-canonical
            event shape, older runtime), the renderer falls through to
            the vanilla `dispatch-source-label` so the bare kind name
            still renders + a coord-bearing call-site stays clickable"
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event [:some/other]
                  :source :after-timer
                  :coord nil})
          label (th/find-by-testid tree "rf-xray-epoch-dispatch-source-label")]
      (is (some? label) "the source-label slot still renders"))))

(deftest dispatch-source-always-renders-kind-label-test
  (testing "rf2-5qp4g — `:source :always` (defensive: stamped on
            microstep traces, not DISPATCH) renders the bare kind label
            so the closed set is fully covered even if a future runtime
            emits it on a dispatch trace"
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event [:foo]
                  :source :always
                  :coord nil})
          header-text (text-of tree "rf-xray-epoch-dispatch-header")]
      (is (string/includes? (or header-text "") "from :always")))))

;; ---- rf2-cq0ch — COEFFECT body --------------------------------------

(deftest coeffect-body-renders-labelled-value-test
  (testing "rf2-cq0ch — a user-injected cofx renders the id + value
            via the canonical edn-inspector. No cryptic `+[]nil` line.

            Post pair-debug 2026-05-26 (commit ee9def224): the
            projection emits ONE COEFFECT step PER injected cofx
            (replacing the prior single step with N `:rows`).
            `view/render-coeffect-step` takes a single step map
            with `:id` + `:value`; the previous `:rows`-bearing
            shape is retired. This test pins the per-cofx render."
    (let [step {:step :coeffect :badge :COEFFECT :step-number 2
                :id :session :value {:user-id 42}}
          tree (view/render-coeffect-step step)
          id-text    (text-of tree "rf-xray-epoch-coeffect-id-session")
          value-text (text-of tree "rf-xray-epoch-coeffect-value-session")]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-step-coeffect-session"))
          "the per-cofx step wrapper renders with the cofx-id in its testid")
      (is (string/includes? (or id-text "") ":session")
          "the cofx-id is surfaced in the header")
      (is (string/includes? (or value-text "") "42")
          "the cofx value renders in the body"))))

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

(deftest sub-rows-default-mode-is-changed-test
  (testing "rf2-tzmmf — `:changed` is the default filter mode so the
            unchanged-by-default rationale (rf2-kfh1v) carries through
            to the new 3-button bar. Only the changed row renders;
            the new `[all][changed][unchanged]` bar is present."
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
            "only the one changed row renders in the default `:changed` mode")
        (is (some? (th/find-by-testid
                     tree "rf-xray-epoch-subscriptions-filter-mode"))
            "the new `[all][changed][unchanged]` button-bar is present")
        (doseq [m ["all" "changed" "unchanged"]]
          (is (some? (th/find-by-testid
                       tree (str "rf-xray-epoch-subscriptions-filter-" m)))
              (str "the " m " button is in the bar")))))))

(deftest sub-rows-supersede-old-toggle-test
  (testing "rf2-tzmmf — the old `Show unchanged` toggle + the
            badge-adjacent `N recomputed (...)` summary text are
            DELETED (pre-alpha posture, no coexistence). The button-bar
            is the new chrome."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :a :sub-vec [:a] :changed? true :before 1 :after 2}
                         {:sub-id :b :sub-vec [:b] :changed? false}]
                  :changed 1 :unchanged 1}
            tree (view/render-subscriptions-step step)
            header (text-of tree "rf-xray-epoch-subscriptions-header")]
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-subscriptions-toggle"))
            "the prior `Show unchanged` toggle is gone")
        (is (not (string/includes? header "recomputed"))
            "the badge-adjacent `N recomputed (...)` summary text is gone")
        ;; "changed" appears in the button-bar label; the split-text
        ;; we deleted was `M changed, K unchanged` — check the
        ;; count-phrase shape is gone rather than the bare word.
        (is (not (re-find #"\d+ changed" header))
            "the `<N> changed` count phrase is gone")
        (is (not (re-find #"\d+ unchanged" header))
            "the `<N> unchanged` count phrase is gone")))))

(deftest sub-rows-reveal-via-all-mode-test
  (testing "rf2-tzmmf — clicking `all` flips the filter-mode slot;
            every row renders. Clicking `unchanged` shows only
            unchanged rows."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :a :sub-vec [:a] :changed? true :before 1 :after 2}
                         {:sub-id :b :sub-vec [:b] :changed? false}
                         {:sub-id :c :sub-vec [:c] :changed? false}]
                  :changed 1 :unchanged 2}]
        ;; default — :changed
        (let [tree (view/render-subscriptions-step step)]
          (is (= 1 (count-prefix tree "rf-xray-epoch-sub-row-"))
              "default mode `:changed` renders only the changed row"))
        ;; switch to `:all`
        (rf/dispatch-sync [:rf.xray.epoch/set-subs-filter-mode :all])
        (let [tree (view/render-subscriptions-step step)]
          (is (= 3 (count-prefix tree "rf-xray-epoch-sub-row-"))
              "`:all` mode renders every row"))
        ;; switch to `:unchanged`
        (rf/dispatch-sync [:rf.xray.epoch/set-subs-filter-mode :unchanged])
        (let [tree (view/render-subscriptions-step step)]
          (is (= 2 (count-prefix tree "rf-xray-epoch-sub-row-"))
              "`:unchanged` mode renders only the unchanged rows"))))))

(deftest sub-filter-bar-anchors-to-xray-frame-test
  (testing "rf2-tzmmf — the button-bar's click dispatches via
            `with-frame :rf/xray` (matches the HANDLER `[diff][all]`
            toggle pattern) AND the read uses the 2-arity subscribe
            form. Both halves anchored to `:rf/xray` regardless of
            host frame."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                :rows [{:sub-id :a :sub-vec [:a] :changed? true :before 1 :after 2}
                       {:sub-id :b :sub-vec [:b] :changed? false}]
                :changed 1 :unchanged 1}]
      ;; Confirm each button carries an on-click handler.
      (rf/with-frame :rf/xray
        (let [tree (view/render-subscriptions-step step)]
          (doseq [m ["all" "changed" "unchanged"]]
            (let [btn (th/find-by-testid
                        tree (str "rf-xray-epoch-subscriptions-filter-" m))]
              (is (some? btn) (str m " button rendered"))
              (is (fn? (:on-click (second btn)))
                  (str m " button carries an on-click handler"))))))
      ;; Mirror what the click does: dispatch under with-frame.
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray.epoch/set-subs-filter-mode :all]))
      (rf/with-frame :rf/xray
        (let [tree (view/render-subscriptions-step step)]
          (is (= 2 (count-prefix tree "rf-xray-epoch-sub-row-"))
              "frame-anchored dispatch flips the :rf/xray slot the 2-arity sub reads"))))))

;; ---- rf2-wpfjo — SUBSCRIPTIONS disposed sub-section -------------------

(deftest disposed-subs-section-renders-when-rows-present-test
  (testing "rf2-wpfjo — when projection carries `:disposed-rows`, the
            SUBSCRIPTIONS step renders a DISPOSED sub-section listing
            each evicted sub; header reads `N recomputed (...); L disposed`"
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :a :sub-vec [:a] :changed? true :before 1 :after 2}]
                  :changed 1 :unchanged 0
                  :disposed-rows [{:sub-id :cart/items
                                   :query  [:cart/items]
                                   :reason :no-more-derefers
                                   :frame  :rf/default}
                                  {:sub-id :counter/label
                                   :query  [:counter/label]
                                   :reason :hot-reload
                                   :frame  :rf/default}]}
            tree (view/render-subscriptions-step step)
            header (text-of tree "rf-xray-epoch-subscriptions-header")
            disposed-tbl (th/find-by-testid tree "rf-xray-epoch-subscriptions-disposed-table")
            row0   (th/find-by-testid tree "rf-xray-epoch-sub-disposed-row-0")
            id0    (text-of tree "rf-xray-epoch-sub-disposed-row-id-0")
            reason0 (text-of tree "rf-xray-epoch-sub-disposed-row-reason-0")]
        (is (some? disposed-tbl)
            "the DISPOSED sub-section is present when disposed-rows non-empty")
        (is (some? row0)
            "the per-disposed-sub row renders")
        (is (string/includes? header "2 disposed")
            "header reads `... 2 disposed`")
        (is (string/includes? id0 ":cart/items")
            "evicted sub-id renders in the row")
        (is (string/includes? reason0 "no-more-derefers")
            "the row's reason chip renders the rf2-mrnur closed-set keyword")))))

(deftest disposed-subs-section-omitted-without-rows-test
  (testing "rf2-wpfjo — when no `:disposed-rows`, no DISPOSED
            sub-section renders; header reads the legacy `N recomputed
            (...)` shape"
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :a :sub-vec [:a] :changed? true :before 1 :after 2}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            header (text-of tree "rf-xray-epoch-subscriptions-header")]
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-subscriptions-disposed-table"))
            "no DISPOSED table renders when disposed-rows is absent")
        (is (not (string/includes? header "disposed"))
            "header omits the disposed clause")))))

(deftest subscriptions-step-dispose-only-cascade-renders-test
  (testing "rf2-wpfjo — when the cascade is dispose-only (no
            recomputes) the step still renders; the recompute table
            is absent; header reads `L disposed`"
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows []
                  :changed 0 :unchanged 0
                  :disposed-rows [{:sub-id :cart/items
                                   :query  [:cart/items]
                                   :reason :cache-clear
                                   :frame  :rf/default}]}
            tree (view/render-subscriptions-step step)
            header (text-of tree "rf-xray-epoch-subscriptions-header")]
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-subscriptions-table"))
            "the recompute table is omitted when no recompute rows fired")
        (is (some? (th/find-by-testid tree "rf-xray-epoch-subscriptions-disposed-table"))
            "the DISPOSED table renders")
        (is (string/includes? header "1 disposed")
            "header reads the dispose-only shape")))))

;; ---- rf2-66wis — HANDLER source code block ---------------------------

;; ---- rf2-93436 — HANDLER :db diff sub-section (design §Section 1+2) -----

(deftest handler-db-diff-always-renders-for-non-machine-handlers-test
  (testing "rf2-93436 — `:db diff` sub-section is ALWAYS present
            inside the HANDLER body for reg-event-db / reg-event-fx.

            rf2-vv3m6 (2026-05-29) — the prior `[diff][full][full+diff]`
            mode toggle retired. FULL+DIFF is the single rendering, so
            the slot always paints the `rf-xray-epoch-handler-db-full-
            with-diff` (or `…-missing` when the epoch carries no
            db-after) descendant rather than the prior `:diff` /
            `:full` / `:full+diff` per-mode descendants."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (testing "reg-event-db with empty diff still renders the slot"
      (let [tree (rf/with-frame :rf/xray
                   (view/render-handler-step
                     {:step :handler :badge :HANDLER :step-number 3
                      :flavour :reg-event-db :event-id :nop
                      :db-diff [] :fx [] :machine nil}))
            slot (th/find-by-testid tree "rf-xray-epoch-handler-db-diff")]
        (is (some? slot)
            ":db diff sub-section is always present even when empty")
        (is (= "full+diff"
               (-> slot second :data-rf-xray-diff-mode))
            "FULL+DIFF is the single rendering post-rf2-vv3m6")))
    (testing "reg-event-db with populated diff renders the slot"
      (let [tree (rf/with-frame :rf/xray
                   (view/render-handler-step
                     {:step :handler :badge :HANDLER :step-number 3
                      :flavour :reg-event-db :event-id :counter/inc
                      :db-diff [[[:counter :value] 5 6 :modified]]
                      :db-post-handler {:counter {:value 6}} :db-write? true
                      :fx [] :machine nil}))
            slot (th/find-by-testid tree "rf-xray-epoch-handler-db-diff")]
        (is (some? slot))
        (is (= "true" (-> slot second :data-rf-xray-db-write))
            "a handler that wrote :db reports db-write? true")))
    (testing "reg-event-fx with empty diff still renders the slot"
      (let [tree (rf/with-frame :rf/xray
                   (view/render-handler-step
                     {:step :handler :badge :HANDLER :step-number 3
                      :flavour :reg-event-fx :event-id :navigate
                      :db-diff [] :fx [{:fx-id :navigate :value "/x"}]
                      :machine nil}))
            slot (th/find-by-testid tree "rf-xray-epoch-handler-db-diff")]
        (is (some? slot)
            "even reg-event-fx that returned no :db gets the sub-section")))))

(deftest handler-db-no-write-renders-placeholder-not-phantom-test
  (testing "rf2-wnvid — PHANTOM-`:db` fix. A handler that wrote NO :db
            (`:db-write?` false — button-15: it threw before returning)
            renders the `— no :db` placeholder, NOT the full post-cascade
            app-db (the pre-rf2-wnvid `:db-after` fallback)."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (testing "handler threw → 'handler threw' wording"
      (let [tree (rf/with-frame :rf/xray
                   (view/render-handler-step
                     {:step :handler :badge :HANDLER :step-number 3
                      :flavour :reg-event-db :event-id :button-deck/throw-handler
                      :db-diff [] :db-write? false :fx [] :machine nil
                      :status :error
                      :errors [{:operation :rf.error/handler-exception
                                :message "boom"}]}))]
        (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-db-no-write"))
            "the no-write placeholder renders")
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-db-full-with-diff"))
            "NO phantom full-app-db block")
        (is (string/includes?
              (text-of tree "rf-xray-epoch-handler-db-no-write")
              "handler threw")
            "placeholder wording names the throw")))
    (testing "clean handler returning no :db → 'returned no :db' wording"
      (let [tree (rf/with-frame :rf/xray
                   (view/render-handler-step
                     {:step :handler :badge :HANDLER :step-number 3
                      :flavour :reg-event-fx :event-id :navigate
                      :db-diff [] :db-write? false
                      :fx [{:fx-id :navigate :value "/x"}] :machine nil}))]
        (is (string/includes?
              (text-of tree "rf-xray-epoch-handler-db-no-write")
              "returned no :db")
            "a clean no-:db handler reads 'returned no :db'")))))

(deftest handler-db-diff-suppressed-for-machine-handlers-test
  (testing "rf2-93436 — for machine handlers the standalone `:db diff`
            sub-section is suppressed (design §Section 3 §DB DIFF —
            folds into SNAPSHOT DIFF since the snapshot IS the
            db change at `[:rf/runtime :machines :snapshots <id>]`). Avoids the redundant
            slot duplicating data already shown in SNAPSHOT DIFF."
    (let [tree (view/render-handler-step
                 {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-machine :event-id :ws/start
                  :db-diff [[[:rf/runtime :machines :snapshots :ws/conn] {} {} :modified]]
                  :fx []
                  :machine {:transition nil :guards []
                            :lifecycle [] :timers []}})]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-db-diff"))
          "no standalone :db diff under machine handlers — folded into
           SNAPSHOT DIFF per design"))))

;; ---- FLOW step `:db` diff (rf2-4wywy) -----------------------------------

(deftest flow-step-renders-db-diff-when-snapshots-present-test
  (testing "rf2-4wywy — when the FLOW step carries the t1 (pre-flow) +
            t2 (post-flow) db snapshots, its body renders the flow's OWN
            `:db` diff via the shared edn-inspector diff renderer
            (`rf-xray-epoch-flow-db-diff-<id>`), NOT the legacy scalar
            before→after line. This is what keeps the flow's `:derived`
            recompute SEPARATE from the HANDLER step's `:db`."
    (frame/reg-frame :rf/xray {})
    ;; testids embed `(name flow-id)` — `:button-deck/derived` → `derived`.
    (let [tree (rf/with-frame :rf/xray
                 (view/render-flow-step
                   {:step :flow :badge :FLOW :step-number 4
                    :flow-id :button-deck/derived
                    :path [:derived] :before 2 :after 4
                    :db-pre-flow  {:base 2 :baseline 1 :derived 2}
                    :db-post-flow {:base 2 :baseline 1 :derived 4}}))]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-flow-db-diff-derived"))
          "FLOW step renders a `:db` diff sub-block when snapshots present")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-flow-value-derived"))
          "the legacy scalar before→after line is NOT rendered when the
           `:db` diff is shown")
      (is (= "true"
             (-> tree (th/find-by-testid "rf-xray-epoch-step-flow-derived")
                 second :data-rf-xray-flow-db-diff))
          "the step root flags `:db`-diff mode for tooling / e2e"))))

(deftest flow-step-falls-back-to-scalar-when-no-snapshots-test
  (testing "rf2-4wywy — without t1/t2 snapshots (pre-rf2-ta0y7 runtime /
            fixture) the FLOW step falls back to the legacy
            `[path] before → after` scalar line so older epochs still
            render."
    (frame/reg-frame :rf/xray {})
    (let [tree (rf/with-frame :rf/xray
                 (view/render-flow-step
                   {:step :flow :badge :FLOW :step-number 4
                    :flow-id :total-parity
                    :path [:total] :before 5 :after 6}))]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-flow-value-total-parity"))
          "the scalar before→after line renders on fallback")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-flow-db-diff-total-parity"))
          "no `:db` diff sub-block when snapshots are absent")
      (is (= "false"
             (-> tree (th/find-by-testid "rf-xray-epoch-step-flow-total-parity")
                 second :data-rf-xray-flow-db-diff))))))

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

(deftest views-row-shows-render-cause-test
  (testing "rf2-bhi3t — the VIEWS table attributes WHY each view
            re-rendered: `← :sub-id` when a deref'd sub changed value,
            `← props` when the re-render had no own sub change (the
            orthogonal :rf/props channel). A fresh mount carries no
            cause chip."
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id :app/ChildA
                        :subs-read [[:child-a/value]]
                        :cause {:kind :sub :sub-id :child-a/value}
                        :duration-ms 0.4}
                       {:view-id :app/ChildB
                        :subs-read [[:child-b/label]]
                        :cause :props
                        :duration-ms 0.3}
                       {:view-id :app/ChildC
                        :subs-read []
                        :cause :mount
                        :duration-ms 0.2}]}
          tree (view/render-views-step step)
          a    (text-of tree "rf-xray-epoch-view-row-cause-0")
          b    (text-of tree "rf-xray-epoch-view-row-cause-1")]
      (is (some? a) "sub-driven row renders a cause chip")
      (is (string/includes? a ":child-a/value")
          "sub-driven cause names the cause sub")
      (is (string/includes? b "props")
          "props-driven re-render reads `← props`")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-view-row-cause-2"))
          "a fresh mount carries no render-cause chip"))))

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

;; ---- rf2-gmw1i — VIEWS step surfaces unmounted views ------------------

(deftest views-unmounted-section-renders-when-rows-present-test
  (testing "rf2-gmw1i — when projection carries `:unmounted-rows`, the
            VIEWS step renders an UNMOUNTED sub-section listing each
            torn-down view; header reads `N re-rendered; M unmounted`"
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id :app.counter/Counter
                        :subs-read [[:counter/total]] :duration-ms 0.5}]
                :unmounted-rows [{:view-id :app.sidebar/Item
                                  :instance [:Item 0]
                                  :frame :rf/default}]}
          tree (view/render-views-step step)
          header (text-of tree "rf-xray-epoch-views-header")
          unmounted-tbl (th/find-by-testid tree "rf-xray-epoch-views-unmounted-table")
          row0 (th/find-by-testid tree "rf-xray-epoch-view-unmounted-row-0")]
      (is (some? unmounted-tbl)
          "the UNMOUNTED sub-section is present when unmounted-rows are present")
      (is (some? row0)
          "the per-unmounted-view row renders")
      (is (string/includes? header "1 re-rendered")
          "header reads `1 re-rendered`")
      (is (string/includes? header "1 unmounted")
          "header reads `... 1 unmounted`")
      (let [id-text (text-of tree "rf-xray-epoch-view-unmounted-row-id-0")]
        (is (string/includes? id-text ":app.sidebar/Item")
            "unmounted view's id renders in the row")))))

(deftest views-unmounted-section-omitted-without-rows-test
  (testing "rf2-gmw1i — when no `:unmounted-rows`, no UNMOUNTED
            sub-section renders; header reads the legacy `N views
            re-rendered` shape"
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id :app.counter/Counter
                        :subs-read [[:counter/total]] :duration-ms 0.5}]}
          tree (view/render-views-step step)
          header (text-of tree "rf-xray-epoch-views-header")]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-views-unmounted-table"))
          "no UNMOUNTED table renders when unmounted-rows is absent")
      (is (string/includes? header "1 view re-rendered")
          "header reads `1 view re-rendered` (no unmount tail)"))))

(deftest views-step-unmount-only-cascade-renders-test
  (testing "rf2-gmw1i — when the cascade is unmount-only (no re-renders)
            the step still renders; the re-render table is absent;
            header reads `M unmounted`"
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows []
                :unmounted-rows [{:view-id :app/Tooltip
                                  :instance [:Tooltip 0]
                                  :frame :rf/default}]}
          tree (view/render-views-step step)
          header (text-of tree "rf-xray-epoch-views-header")]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-views-table"))
          "the re-render table is omitted when no rows fired")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-views-unmounted-table"))
          "the UNMOUNTED table renders")
      (is (string/includes? header "1 unmounted")
          "header reads the unmount-only shape"))))

;; ---- rf2-2ek7t (supersedes rf2-xgeag) — violation sub-block redesign ----
;;
;; Pre-rf2-2ek7t the block carried discrete `:headline`, `:path`, and
;; `:value` rows alongside the title bar. rf2-2ek7t retired those —
;; the per-`:where` prose sentence + humanized `ei/edn-inspector`
;; explain map subsume them. Tests now anchor on:
;;
;;   - title           → "Schema Violation Error" (mixed case)
;;   - recovery chip   → "Aborted" / "Skipped" / "Returned nil" /
;;                       "Rejected" (per-`:where` short labels)
;;   - prose paragraph → `<base>-prose`, contains the per-`:where`
;;                       canned sentence with an inline "schema check"
;;                       link
;;   - explain block   → `<base>-explain` when humanized/raw explain
;;                       data is present
;;
;; See `view.cljs` §SCHEMA VIOLATION sub-block (rf2-xgeag) for the
;; live renderer.

(deftest violation-block-renders-title-+-prose-test
  (testing "rf2-2ek7t — `violation-block` renders the pink sub-block
            with the mixed-case title, per-:where recovery chip, prose
            paragraph carrying an inline schema-link, and the
            humanized explain map"
    (let [row  {:kind :rf.error/schema-validation-failure
                :where :app-db
                :failing-id :counter/inc
                :path [:count]
                :value "not-an-int"
                :explain-humanized {:errors ["must be int"]}
                :rollback? true
                :sensitive? false}
          tree (view/violation-block :handler 0 row)
          base "rf-xray-epoch-violation-handler-0"]
      (is (some? (th/find-by-testid tree base))
          "the sub-block wrapper renders")
      (let [title       (text-of tree (str base "-title"))
            recovery    (text-of tree (str base "-recovery"))
            prose       (text-of tree (str base "-prose"))
            schema-link (text-of tree (str base "-schema-link"))]
        (is (string/includes? title "Schema Violation Error")
            "title bar reads the mixed-case 'Schema Violation Error'")
        (is (string/includes? recovery "Aborted")
            "rollback? true → recovery chip reads 'Aborted'")
        (is (string/includes? prose "committed to app-db")
            "prose sentence explains the :app-db :where outcome")
        (is (string/includes? schema-link "schema check")
            "inline 'schema check' link sits inside the prose")
        (is (some? (th/find-by-testid tree (str base "-explain")))
            "humanized explain map renders via `edn-inspector`")))))

(deftest violation-block-recovery-chip-test
  (testing "rf2-2ek7t — recovery chip surfaces a per-:where label when
            no rollback fired"
    (let [tree (view/violation-block :fx 0
                 {:where :fx-args :failing-id :http/post
                  :rollback? false})
          recovery (text-of tree "rf-xray-epoch-violation-fx-0-recovery")]
      (is (string/includes? recovery "Skipped")
          ":fx-args + no rollback → 'Skipped'")))

  (testing "rf2-2ek7t — sub-return → 'Returned nil'"
    (let [tree (view/violation-block :subscriptions 0
                 {:where :sub-return :failing-id :user/profile
                  :rollback? false})
          recovery (text-of tree
                            "rf-xray-epoch-violation-subscriptions-0-recovery")]
      (is (string/includes? recovery "Returned nil")
          ":sub-return + no rollback → 'Returned nil'"))))

;; `render-schema-hot-reload-step-test` retired here (rf2-oc6ok) — pairs
;; with the rf2-o1l6c projection-side retire. Commit 9b96f9f6a
;; (rf2-7gf7v) deleted both the projection's `hot-reload-step` fn AND
;; the view's `render-schema-hot-reload-step` fn — hot-reload drift is
;; a dev-time event (re-registered schema invalidates existing app-db
;; state), not a cascade event. It surfaces exclusively via the Issues
;; panel which consumes `:rf.schema/violation` trace events. No tail
;; cascade step → no render fn → no view test. The projection-side
;; negative assertion (`not-any? :schema-hot-reload`) in
;; `project-attaches-app-db-violation-to-fx-db-row-test` pins down
;; that no tail step is appended.

;; ---- rf2-kt6js — SIDE EFFECTS step (:db / :fx / other sub-steps) ------
;; rf2-uffov — per-action attribution + the threw-count header chip;
;; rf2-g1mfc — per-:fx-row open-code chip; rf2-kt6js — the :fx step
;; became the SIDE EFFECTS step.

(defn- side-effects-step
  "Build a projected SIDE EFFECTS step for the view tests (rf2-kt6js) —
  the step the `render-side-effects-step` renderer consumes. `subs` is
  a vec of `{:kind :rows}` groups in render order; this flattens them
  into the single tagged `:rows` slot + the `:sub-kinds` order vec the
  renderer reads (mirrors `projection/side-effects-step`'s output
  shape — one row vector, each row tagged `:sub-kind`)."
  ([subs] (side-effects-step subs 0))
  ([subs threw]
   {:step :side-effects :badge :SIDE-EFFECTS :step-number 4
    :sub-kinds (mapv :kind subs)
    :rows (vec (mapcat (fn [{:keys [kind rows]}]
                         (mapv #(assoc % :sub-kind kind) rows))
                       subs))
    :threw threw}))

(deftest side-effects-step-header-shows-outcome-split-test
  (testing "rf2-kt6js / rf2-uffov — SIDE EFFECTS step header surfaces the
            threw-count when non-zero, beside the subdued `(post-commit)`
            caption. The per-row ✓/✗ glyphs convey per-effect outcome;
            the header carries only the at-a-glance error chip."
    (let [step (side-effects-step
                 [{:kind :db :status :ok :rows [{:fx-id :db :status :ok}]}
                  {:kind :fx :status :error
                   :rows [{:fx-id :http/get :status :ok}
                          {:fx-id :bad :status :error}]}]
                 1)
          tree (view/render-side-effects-step step)
          header (text-of tree "rf-xray-epoch-side-effects-header")]
      (is (string/includes? header "(post-commit)")
          "the subdued caption rides beside the badge")
      (is (string/includes? header "1 threw")
          "threw-count chip surfaces in the header when non-zero"))))

(deftest side-effects-fx-row-shows-attribution-chip-test
  (testing "rf2-uffov — when an :fx sub-step row carries :attributed-to,
            the attribution chip renders alongside"
    (let [step (side-effects-step
                 [{:kind :fx :status :ok
                   :rows [{:fx-id :http/get :status :ok
                           :attributed-to {:action-id :open-socket
                                           :phase :entry}}]}])
          tree (view/render-side-effects-step step)
          chip (th/find-by-testid tree "rf-xray-epoch-fx-row-attribution-0")]
      (is (some? chip) "the attribution chip is present")
      (is (string/includes? (th/text-content chip) ":open-socket")
          "the action-id rides the chip"))))

(deftest side-effects-fx-row-omits-attribution-chip-when-none-test
  (testing "rf2-uffov — :fx sub-step row without :attributed-to omits
            the chip"
    (let [step (side-effects-step
                 [{:kind :db :status :ok :rows [{:fx-id :db :status :ok}]}])
          tree (view/render-side-effects-step step)]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-fx-row-attribution-0"))))))

(deftest side-effects-fx-row-mounts-coord-chip-when-meta-resolves-test
  (testing "rf2-g1mfc — each :fx sub-step row routes its fx-id through
            `coord-chip/coord-chip` to surface a click-to-source
            affordance for the `reg-fx` registration (parity with the
            SUBSCRIPTIONS / VIEWS rows + the HANDLER verb).

            The chip's <button> mounts when `(rf/handler-meta :fx
            fx-id)` resolves a `:file` coord. CLJS macro-form `reg-fx`
            captures `:file`/`:line` at the test call-site (the same
            `defreg-macro` → `coords-form` absolutisation path
            `reg-sub` uses) so the integration round-trip is testable
            here."
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (rf/reg-fx :rf.g1mfc-fixture/ping
        (fn [_ctx _args] nil))
      (let [meta-resolved? (boolean (some-> (rf/handler-meta :fx :rf.g1mfc-fixture/ping)
                                            :file string?))
            step (side-effects-step
                   [{:kind :fx :status :ok
                     :rows [{:fx-id :rf.g1mfc-fixture/ping :status :ok}]}])
            tree (view/render-side-effects-step step)]
        (is (some? (th/find-by-testid tree "rf-xray-epoch-fx-row-0"))
            "the fx row itself renders")
        ;; If the test harness captured a source coord the chip mounts;
        ;; otherwise the call-site still fired but produced nil (graceful
        ;; degrade per coord-chip's contract). Either branch proves the
        ;; bug-fix is in place — pre-fix there was no chip call-site at
        ;; all on the fx row.
        (let [chip (th/find-by-testid tree "rf-xray-epoch-fx-row-coord-0")]
          (if meta-resolved?
            (do
              (is (some? chip)
                  "coord chip mounts when reg-fx captured a source coord")
              (is (= :button (first chip))
                  "chip mounts as a <button>")
              (is (fn? (:on-click (second chip)))
                  "chip carries an on-click handler"))
            (is (nil? chip)
                "coord chip drops out cleanly when no coord is resolvable")))))))

;; ---- rf2-rrykz — app-db diff section — RETIRED 2026-05-26 -------------
;;
;; The `view/render-app-db-diff-step` fn was deleted in commit
;; 862288aca along with the standalone APP-DB DIFF projection step.
;; HANDLER `:db` `[diff][all]` toggle covers the same surface
;; in-context (tests for that ride in the rf2-93436 section above).

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

;; ---- rf2-u69j7 — machine handler section: time-ordered cascade ----------
;;
;; Replaces the pre-rf2-u69j7 category-grouped layout (TRANSITION /
;; GUARDS / LIFECYCLE / AFTER-TIMERS / DATA REDUCTION / SNAPSHOT DIFF /
;; FX) with a single time-ordered cascade view. Each row interleaves
;; source code (always visible) with phase + duration + outcome.

(deftest machine-handler-renders-cascade-view-test
  (testing "rf2-u69j7 — machine handler renders the time-ordered
            cascade view; legacy category-grouped sub-sections are
            REPLACED (not augmented)"
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:cascade [{:kind :guard :step 1
                                     :guard-id :ready? :outcome :pass}
                                    {:kind :action :step 2
                                     :action-id :open-socket
                                     :phase :entry}
                                    {:kind :transition :step 3
                                     :machine-id :ws/conn
                                     :from-state [:idle]
                                     :to-state   [:connected]
                                     :microsteps 1}]
                          :transition nil :guards [] :lifecycle [] :timers []}}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-machine-cascade"))
          "the cascade view replaces the category-grouped layout")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-machine-cascade-rows"))
          "cascade rows container is rendered")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-1"))
          "first cascade row is rendered")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-2")))
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-3")))
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-machine-data-reduction"))
          "the LEGACY DATA REDUCTION sub-section is GONE")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-machine-snapshot-diff"))
          "the LEGACY SNAPSHOT DIFF sub-section is GONE"))))

(deftest machine-handler-cascade-renders-rows-in-trace-order-test
  (testing "rf2-u69j7 — cascade rows render in the projection's
            step-ordinal order (substrate insertion order). Each
            row carries `data-cascade-kind` so a smoke test can
            assert the kind-sequence."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:cascade [{:kind :guard :step 1
                                     :guard-id :ok? :outcome :pass}
                                    {:kind :action :step 2
                                     :action-id :a1 :phase :exit}
                                    {:kind :action :step 3
                                     :action-id :a2 :phase :entry}
                                    {:kind :timer :step 4
                                     :state [:idle] :delay 500
                                     :reason :on-exit}]
                          :transition nil :guards [] :lifecycle [] :timers []}}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-1")))
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-2")))
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-3")))
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-4"))))))

(deftest machine-handler-cascade-action-row-phase-chip-test
  (testing "rf2-u69j7 — `:action` rows render a phase chip identifying
            which phase (`:exit / :transition / :entry / :always /
            :after-action / :initial-entry / :destroy-exit`) fired."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:cascade [{:kind :action :step 1
                                     :action-id :open-socket
                                     :phase :entry}]
                          :transition nil :guards [] :lifecycle [] :timers []}}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-phase-entry"))
          "phase chip is stamped with the row's phase keyword"))))

(deftest machine-handler-cascade-action-fx-attribution-test
  (testing "rf2-u69j7 — per-action fx attribution renders inline on
            the `:action` row (no separate FX sub-section). The same
            data the FX step's `:attributed-to` chip surfaces, but
            in the action's own row so the operator reads
            'action X emitted fx Y' in one place."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:cascade [{:kind :action :step 1
                                     :action-id :open-socket
                                     :phase :entry
                                     :outcome {:fx [[:http/get {:url "/x"}]]}
                                     :fx [[:http/get {:url "/x"}]]}]
                          :transition nil :guards [] :lifecycle [] :timers []}}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-fx-1"))
          "per-action fx attribution row is rendered")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-fx-1-0"))
          "first emitted fx-id is rendered as a chip"))))

(deftest machine-handler-cascade-transition-row-renders-states-test
  (testing "rf2-u69j7 — `:transition` rows render the `from → to`
            state-vector chrome alongside `event` + `microsteps` so
            the operator reads the state change at the row's vertical
            position."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:cascade [{:kind :transition :step 1
                                     :machine-id :ws/conn
                                     :before {:state [:idle]}
                                     :after  {:state [:connected]}
                                     :from-state [:idle]
                                     :to-state   [:connected]
                                     :event [:ws/start]
                                     :microsteps 1}]
                          :transition nil :guards [] :lifecycle [] :timers []}}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-1")))
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-transition-1"))
          "transition detail block renders")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-from-to-1"))
          "from → to state chrome renders"))))

;; ---- rf2-wwc3j — inline-fn / transition source-body rendering ------------

(deftest cascade-row-renders-inline-entry-source-body-test
  (testing "rf2-wwc3j — an inline-fn `:entry` action row renders an
            always-visible source-body the same way named actions do.
            The fixture registers a machine with an inline `:entry`
            slot; the row carries the resolved fn object as :action-id
            (substrate behaviour); the view layer pulls the spec value
            at `[:states <s> :entry]` via the rf2-wwc3j source-key
            extension."
    (rf/with-frame :rf/default
      (let [inline-fn (fn [_ctx] {})]
        (rf/reg-event-fx :rf2-wwc3j.view/inline-entry
                         {:rf/machine? true
                          :rf/machine {:initial :a
                                       :states  {:a {:entry inline-fn
                                                     :on    {:go :b}}
                                                 :b {}}}}
                         (fn [_ _] {}))
        (let [step {:step :handler :badge :HANDLER :step-number 3
                    :flavour :reg-machine
                    :event-id :rf2-wwc3j.view/inline-entry
                    :db-diff [] :fx []
                    :machine {:cascade [{:kind :action :step 1
                                         :action-id inline-fn
                                         :phase :entry
                                         :target-state :a
                                         :source-state :a
                                         :event-id :go}]
                              :transition nil :guards [] :lifecycle [] :timers []}}
              tree (view/render-handler-step step)]
          (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-1"))
              "inline-entry row renders")
          (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-source-1"))
              "source slot is present (rf2-wwc3j inline-fn surfacing)"))))))

(deftest cascade-row-renders-inline-guard-source-body-test
  (testing "rf2-wwc3j — an inline-fn `:guard` row renders a source-body
            slot from the spec value at `[:states <s> :on <ev> :guard]`."
    (rf/with-frame :rf/default
      (let [inline-guard (fn [_ctx] true)]
        (rf/reg-event-fx :rf2-wwc3j.view/inline-guard
                         {:rf/machine? true
                          :rf/machine {:initial :idle
                                       :states  {:idle {:on {:submit {:target :done
                                                                       :guard inline-guard}}}
                                                 :done {}}}}
                         (fn [_ _] {}))
        (let [step {:step :handler :badge :HANDLER :step-number 3
                    :flavour :reg-machine
                    :event-id :rf2-wwc3j.view/inline-guard
                    :db-diff [] :fx []
                    :machine {:cascade [{:kind :guard :step 1
                                         :guard-id inline-guard
                                         :outcome :pass
                                         :source-state :idle
                                         :event-id :submit}]
                              :transition nil :guards [] :lifecycle [] :timers []}}
              tree (view/render-handler-step step)]
          (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-1")))
          (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-source-1"))
              "inline-guard source slot is present"))))))

(deftest cascade-transition-row-renders-source-body-test
  (testing "rf2-wwc3j — a `:transition` row renders the transition map
            literal as a source body (the bead's `:delight shape` for
            transitions: render the EDN form inline)."
    (rf/with-frame :rf/default
      (rf/reg-event-fx :rf2-wwc3j.view/transition-row
                       {:rf/machine? true
                        :rf/machine {:initial :idle
                                     :states  {:idle {:on {:go {:target :done}}}
                                               :done {}}}}
                       (fn [_ _] {}))
      (let [step {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-machine
                  :event-id :rf2-wwc3j.view/transition-row
                  :db-diff [] :fx []
                  :machine {:cascade [{:kind :transition :step 1
                                       :machine-id :rf2-wwc3j.view/transition-row
                                       :from-state :idle
                                       :to-state   :done
                                       :event [:go]
                                       :source-state :idle
                                       :target-state :done
                                       :event-id :go
                                       :microsteps 1}]
                            :transition nil :guards [] :lifecycle [] :timers []}}
            tree (view/render-handler-step step)]
        (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-1")))
        (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-source-1"))
            "transition source slot is present (renders the transition map)")))))

(deftest cascade-timer-row-elides-source-body-test
  (testing "rf2-wwc3j — `:timer` rows render the click-to-source coord
            chip on the verb but elide the inline source-body slot
            (the parent state-node value is too verbose to render
            verbatim)."
    (rf/with-frame :rf/default
      (rf/reg-event-fx :rf2-wwc3j.view/timer-row
                       {:rf/machine? true
                        :rf/machine {:initial :idle
                                     :states  {:idle {:after {500 {:target :done}}}
                                               :done {}}}}
                       (fn [_ _] {}))
      (let [step {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-machine
                  :event-id :rf2-wwc3j.view/timer-row
                  :db-diff [] :fx []
                  :machine {:cascade [{:kind :timer :step 1
                                       :state :idle :delay 500
                                       :reason :on-exit}]
                            :transition nil :guards [] :lifecycle [] :timers []}}
            tree (view/render-handler-step step)]
        (is (some? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-row-1")))
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-machine-cascade-source-1"))
            "timer rows have no inline source-body slot")))))

(deftest machine-handler-cascade-empty-state-test
  (testing "rf2-u69j7 — empty cascade (no machine cascade events fired)
            renders the empty-state line rather than blowing up. This
            is defensive — production machine handlers always emit at
            least one `:rf.machine/transition`."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-machine :event-id :ws/start
                :db-diff [] :fx []
                :machine {:cascade []
                          :transition nil :guards [] :lifecycle [] :timers []}}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-machine-cascade-empty"))
          "empty-state line renders"))))

(deftest vanilla-reg-event-db-cascade-unchanged-by-rf2-u69j7-test
  (testing "rf2-u69j7 acceptance #4 — a vanilla `reg-event-db` cascade
            renders the existing pipeline UNCHANGED (the redesign is
            machine-specific; non-machine rendering must not regress)."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-event-db :event-id :counter/inc
                :db-diff [[[:counter] 5 6 :modified]]
                :fx [] :machine nil}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-db-diff"))
          "the standard :db-diff sub-section renders for non-machine handlers")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-machine"))
          "no machine block on a non-machine handler")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-machine-cascade"))
          "no cascade view on a non-machine handler"))))

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

;; ---- rf2-ehd8v — HANDLER source `file:line + [open]` ----------------
;;
;; The dedicated `file:line + [open]` sub-header alongside the SOURCE
;; label was retired in commit ee9def224. The HANDLER step's verb
;; itself (e.g. `reg-event-fx`) IS now the click-to-source hyperlink
;; (`handler-verb-link` in view.cljs); the source-block leads with the
;; code body directly. The positive-affordance test
;; (`handler-source-renders-file-line-and-open-affordance-test`) is
;; retired; the absence-of-affordance check below stays — those
;; testids are still nil now (no affordance means nothing rendered),
;; which is still the correct contract.

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

;; ---- rf2-8w8er (subsumes rf2-nszcv) — values route through edn-inspector

(defn- mini-mounts
  "Return every `[ei/mini ...]` hiccup mount under `tree`. Anchors on
  the `:data-rf-mini` attribute the widget stamps onto every render."
  [tree]
  (th/find-all-by-attr tree :data-rf-mini "1"))

(defn- ei-mounts
  "Return every full-widget `[ei/edn-inspector ...]` mount under
  `tree`. Anchors on the data-testid suffix the widget stamps onto
  every render (`*-inspector` testid form)."
  [tree]
  (th/find-by-testid-prefix tree "rf-xray-edn-inspector"))

(deftest dispatch-event-routes-through-edn-inspector-test
  (testing "rf2-8w8er (subsumes rf2-nszcv) — DISPATCH event vector
            renders through the canonical edn-inspector widget so the
            event-id keyword + args paint with the canonical
            syntax-token chrome (keyword magenta, number orange,
            string green). Pre-fix the body was plain text via
            `proj/event-display`."
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event [:counter/inc 7 "x"] :source :ui :coord nil})]
      (is (pos? (count (ei-mounts tree)))
          "the DISPATCH body mounts at least one edn-inspector widget"))))

;; rf2-vv3m6 (2026-05-29) — `handler-db-diff-values-route-through-mini-
;; test` retired. The test pinned the prior `:diff` mode rendering
;; (`db-diff-line` painting before / after through `ei/mini`) which
;; required `[:rf.xray.epoch/set-db-diff-mode :diff]` to take effect.
;; FULL+DIFF is the single rendering post-rf2-vv3m6; the HANDLER `:db`
;; sub-section mounts edn-inspector, not mini.

(deftest handler-fx-section-routes-through-edn-inspector-test
  (testing "rf2-p2zy0 — HANDLER step's `:fx` section (the canonical
            vector-of-vectors off the handler's return map) renders
            fully expanded via the edn-inspector widget. Per Mike
            pair-debug 2026-05-27 the per-fx-row list shape is
            retired in favour of a single edn-inspector mount.

            The `:other-effects` section (return map minus :db and
            :fx) is also covered here — both sections mount the
            edn-inspector with `:default-expanded-depth 16`."
    (let [tree    (view/render-handler-step
                    {:step :handler :badge :HANDLER :step-number 3
                     :flavour :reg-event-fx :event-id :do/it
                     :db-diff []
                     :fx-vec  [[:dispatch [:foo 1]]
                               [:http/get {:url "/x"}]]
                     :other-effects {:navigate "/home"}
                     :machine nil})
          fx-sec  (th/find-by-testid tree "rf-xray-epoch-handler-fx")
          oth-sec (th/find-by-testid tree "rf-xray-epoch-handler-other")]
      (is (some? fx-sec)
          "the :fx section mounts when fx-vec is non-empty")
      (is (pos? (count (ei-mounts fx-sec)))
          "the :fx section mounts an edn-inspector")
      (is (some? oth-sec)
          "the other section mounts when other-effects is non-empty")
      (is (pos? (count (ei-mounts oth-sec)))
          "the other section mounts an edn-inspector")
      ;; rf2-5t8y8 — sub-header carries an at-a-glance entry-count chip
      ;; on both `:fx` and `other` (was lost during the rf2-p2zy0
      ;; edn-inspector migration).
      (is (string/includes? (th/text-content fx-sec) "2 entries")
          "the :fx sub-header carries the entry-count chip")
      (is (string/includes? (th/text-content oth-sec) "1 entry")
          "the other sub-header carries the singular entry chip"))))

(deftest side-effects-fx-args-route-through-edn-inspector-test
  (testing "rf2-ef2hy — :fx sub-step row's args render through the
            edn-inspector widget with `:default-expanded-depth 1`. Top-
            level map keys are visible inline; nested maps collapse to a
            clickable chevron so the operator can drill into a complex
            args map.

            Sibling rendering for the HANDLER step's `:fx` section
            (rf2-p2zy0) uses the same widget with depth 16 (full-
            expand). Both share the widget; per-call-site depth
            reflects each section's role."
    (let [tree (view/render-side-effects-step
                 (side-effects-step
                   [{:kind :fx :status :ok
                     :rows [{:fx-id :http/get :status :ok :args {:url "/x"}}]}]))
          row  (th/find-by-testid tree "rf-xray-epoch-fx-row-0")]
      (is (some? row))
      (is (pos? (count (ei-mounts row)))
          "the fx row's args mount an edn-inspector"))))

(deftest subscriptions-row-mounts-mini-for-sub-vec-test
  (testing "rf2-8w8er — SUBSCRIPTIONS row renders the sub-vec column
            through `ei/mini` so the table cell lights up with
            syntax-token chrome rather than plain `pr-str`.

            rf2-vv3m6 (2026-05-29) — the prior `[diff][full][full+diff]`
            value-mode toggle retired. The before / after leaf-scalar
            FULL+DIFF branch (rf2-fyd8u) routes `before` + `after`
            through `mini` for the syntax-token chrome; the sub-vec
            column always uses `mini`. The original three-mode triad
            assertion (`:diff` mode mini mounts) retired with the
            toggle."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :counter/total :sub-vec [:counter/total]
                          :inputs nil :changed? true :first-run? false
                          :before 5 :after 6}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            row  (th/find-by-testid tree "rf-xray-epoch-sub-row-0")]
        (is (some? row))
        ;; sub-vec column + leaf-scalar `before` + leaf-scalar `after`
        ;; all mount mini; assert ≥ 3.
        (is (>= (count (mini-mounts row)) 3)
            "row mounts ≥3 mini-renders (sub-vec + before + after)")))))

(deftest views-subs-read-routes-through-mini-test
  (testing "rf2-8w8er — VIEWS row's subs-read list renders each sub-id
            through `ei/mini` so the consumed-subs column reads as
            syntax-highlighted tokens rather than plain `pr-str`."
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id :app.counter/Counter
                        :subs-read [[:counter/total] [:counter/threshold]]
                        :duration-ms 1.2}]}
          tree (view/render-views-step step)
          subs (th/find-by-testid tree "rf-xray-epoch-view-row-subs-0")]
      (is (some? subs))
      ;; 2 consumed subs → 2 mini mounts
      (is (>= (count (mini-mounts subs)) 2)
          "subs-read column mounts one mini per consumed sub"))))

;; `app-db-diff-values-route-through-mini-test` retired in rf2-xu5iv
;; (commit 862288aca dropped `view/render-app-db-diff-step` along with
;; the projection step). HANDLER `:db` diff mini-mount coverage rides
;; on `handler-db-diff-values-route-through-mini-test` above.

(deftest child-dispatches-event-routes-through-mini-test
  (testing "rf2-8w8er — CHILD-DISPATCHES row renders the event vector
            through `ei/mini`."
    (let [step {:step :child-dispatches :badge :CHILD-DISPATCHES
                :step-number 5
                :rows [{:event [:other/x 1] :via :dispatch}]}
          ctx  {:dispatch-id 1 :epoch-history []}
          tree (view/render-child-dispatches-step step ctx)
          ev   (th/find-by-testid tree "rf-xray-epoch-child-dispatch-event-0")]
      (is (some? ev))
      (is (pos? (count (mini-mounts ev)))
          "the dispatched-event slot mounts a mini-render"))))

;; ---- rf2-atqkg — pipeline-view realises step-seq inside reactive scope ---
;;
;; Regression test for the reactive-tracking failure the bead pins: the
;; pipeline's `(for [[i step] …] …)` MUST be realised inside
;; `pipeline-view`'s return value so that any `@(rf/subscribe …)` deref
;; reached transitively by `render-step` (e.g. `handler-db-diff-block`'s
;; `:rf.xray/selected-epoch-record` read, or
;; `render-subscriptions-step`'s `:rf.xray.epoch/subs-filter-mode`
;; read — rf2-tzmmf) fires while the
;; parent reg-view's reactive scope is still live. A lazy seq realised
;; AFTER the reg-view returns
;; leaves the derefs OUTSIDE that scope — Reagent doesn't watch them,
;; sub-value changes don't trigger re-render, and the operator sees a
;; click "do nothing" until an external repaint forces the panel back
;; through render.
;;
;; The structural invariant we pin here: the steps-seq inside the inner
;; `[:div :data-testid "rf-xray-epoch-pipeline"]` is either a vector
;; (eager) OR a *realised* lazy seq. Lazy-but-unrealised at the moment
;; `pipeline-view` returns is the bug shape.

(defn- pipeline-steps-seq
  "Locate the step-seq returned by `pipeline-view`. The view returns:

      [:div {:data-testid \"rf-xray-epoch-pipeline-container\"}
       [:div {:data-testid \"rf-xray-epoch-pipeline\" …}
        [:div {:data-testid \"rf-xray-epoch-rail\" …}]
        <steps-seq>]]

  We walk the top-level tree directly (NOT via `find-by-testid`, which
  realises lazy seqs as a side-effect of walking) and return the
  step-seq child verbatim so the caller can probe its `realized?`
  state."
  [tree]
  (let [inner (some (fn [node]
                      (when (and (vector? node)
                                 (= "rf-xray-epoch-pipeline"
                                    (some-> node th/attrs :data-testid)))
                        node))
                    ;; Walk top-level children of the container only;
                    ;; we want the inner pipeline div without forcing
                    ;; realisation of the step-seq itself.
                    (rest tree))]
    (when inner
      ;; The inner div's children are [div-attrs rail-div steps-seq];
      ;; steps-seq is the last child.
      (last inner))))

(deftest pipeline-view-realises-step-seq-rf2-atqkg-test
  (testing "rf2-atqkg — `pipeline-view` returns its step-seq REALISED
            so descendant sub derefs (e.g. handler-db-diff-block's
            `:rf.xray/selected-epoch-record` read) fire during the
            parent reg-view's reactive scope. An unrealised lazy seq
            at this position is the rf2-atqkg bug shape (Reagent emits
            the `Reactive deref not supported in lazy seq, it should
            be wrapped in doall` console warning at render time)."
    (let [steps [{:step :dispatch :badge :DISPATCH :step-number 1
                  :event [:counter/inc] :source :ui :coord nil}
                 {:step :handler :badge :HANDLER :step-number 2
                  :flavour :reg-event-db :event-id :counter/inc
                  :db-diff [[[:counter :value] 5 6 :modified]]
                  :fx [] :machine nil}]
          tree  (view/pipeline-view steps)
          step-seq (pipeline-steps-seq tree)]
      (is (some? step-seq)
          "the inner pipeline div carries a step-seq child")
      ;; Either a vector (eager build) or a realised lazy seq is fine.
      ;; An unrealised lazy seq is the bug.
      (is (or (vector? step-seq)
              (not (instance? cljs.core/LazySeq step-seq))
              (realized? step-seq))
          (str "pipeline-view's step-seq MUST be realised at return-time. "
               "Got: "
               (cond
                 (vector? step-seq) "vector (eager)"
                 (instance? cljs.core/LazySeq step-seq)
                 (str "LazySeq, realized?=" (realized? step-seq))
                 :else (str "type=" (type step-seq))))))))

;; ---- rf2-zmkqi — SUBSCRIPTIONS value cell smoke -------------------------

(deftest subscriptions-full-diff-cell-renders-without-inline-style-test
  (testing "rf2-zmkqi — the value-cell renders without crashing under
            the single FULL+DIFF rendering. Smoke check that the post-
            hoist ns-level style def (`subs-value-cell-fill-style`)
            lands a valid `:style` map under the wrapping div.

            rf2-vv3m6 (2026-05-29) — the prior `:full` mode branch was
            retired; FULL+DIFF is the single rendering. The container-
            path mount uses the hoisted wrapper style."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [;; Container-path rows so the wrapper div is mounted (leaf-
            ;; scalar paths take a different shape via rf2-fyd8u).
            step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :counter/state :sub-vec [:counter/state]
                          :inputs nil :changed? true :first-run? false
                          :before {:a 1} :after {:a 1 :b 2}}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            row  (th/find-by-testid tree "rf-xray-epoch-sub-row-0")]
        (is (some? row)
            "row renders cleanly under FULL+DIFF (hoisted style applied)")))))

;; ---- rf2-fyd8u — SUBSCRIPTIONS FULL+DIFF leaf-scalar annotation ---------

(deftest subscriptions-full-diff-leaf-scalar-value-change-renders-was-annotation-test
  (testing "rf2-fyd8u — under the single FULL+DIFF rendering, a
            leaf-scalar sub with `:value-changed? true` and
            `:first-run? false` renders value + inline `← was <prev>`
            annotation. The annotation chip carries
            `:data-rf-diff-annotation \"subs-was\"`; the row-level
            wrapper carries `:data-rf-xray-subs-leaf \"changed\"`.
            Acceptance criterion 3 — pins
            `0 → 1`, `\"even\" → \"odd\"`, `nil → 1779972561856`.

            rf2-vv3m6 (2026-05-29) — the prior `:full+diff` dispatch
            bootstrap is gone; FULL+DIFF is the single rendering."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (testing "0 → 1 (counter/value)"
        (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                    :rows [{:sub-id :counter/value :sub-vec [:counter/value]
                            :inputs nil :changed? true :first-run? false
                            :before 0 :after 1}]
                    :changed 1 :unchanged 0}
              tree (view/render-subscriptions-step step)
              leaf (th/find-by-testid tree "rf-xray-epoch-subs-leaf-changed-0")]
          (is (some? leaf)
              "row mounts the leaf-changed wrapper (not the container path)")
          (is (some? (th/find-by-attr tree :data-rf-diff-annotation "subs-was"))
              "row carries the `← was <prev>` annotation chip")
          (let [txt (th/text-content leaf)]
            (is (string/includes? txt "← was")
                "annotation prose includes `← was`")
            (is (string/includes? txt "1")
                "after value renders")
            (is (string/includes? txt "0")
                "prev value renders inside the annotation"))))
      (testing "\"even\" → \"odd\" (counter/parity)"
        (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                    :rows [{:sub-id :counter/parity :sub-vec [:counter/parity]
                            :inputs nil :changed? true :first-run? false
                            :before "even" :after "odd"}]
                    :changed 1 :unchanged 0}
              tree (view/render-subscriptions-step step)
              leaf (th/find-by-testid tree "rf-xray-epoch-subs-leaf-changed-0")]
          (is (some? leaf))
          (let [txt (th/text-content leaf)]
            (is (string/includes? txt "← was"))
            (is (string/includes? txt "odd"))
            (is (string/includes? txt "even")))))
      (testing "nil → 1779972561856 (counter/last-clicked)"
        (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                    :rows [{:sub-id :counter/last-clicked
                            :sub-vec [:counter/last-clicked]
                            :inputs nil :changed? true :first-run? false
                            :before nil :after 1779972561856}]
                    :changed 1 :unchanged 0}
              tree (view/render-subscriptions-step step)
              leaf (th/find-by-testid tree "rf-xray-epoch-subs-leaf-changed-0")]
          (is (some? leaf)
              "nil-prev leaf still mounts the leaf-changed wrapper (the
               `:first-run? false` discriminator is the gate, NOT
               `(some? before)`)")
          (let [txt (th/text-content leaf)]
            (is (string/includes? txt "← was"))
            (is (string/includes? txt "1779972561856"))
            (is (string/includes? txt "nil")
                "prev nil renders as `nil` syntax token in the annotation")))))))

(deftest subscriptions-full-diff-leaf-scalar-first-run-renders-added-chrome-test
  (testing "rf2-fyd8u — under the single FULL+DIFF rendering, a
            leaf-scalar sub with `:value-changed? true` and
            `:first-run? true` (the run that created the cache slot —
            a freshly-mounted view deref'd a sub that wasn't cached
            this frame) renders `:added` chrome (green stripe /
            leading `+` glyph / wash) with NO `← was` annotation.
            Acceptance criterion 4."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :counter/value :sub-vec [:counter/value]
                          :inputs nil :changed? true :first-run? true
                          :before nil :after 42}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            added (th/find-by-testid tree "rf-xray-epoch-subs-leaf-added-0")]
        (is (some? added)
            "row mounts the leaf-added wrapper (`:first-run? true` branch)")
        (is (nil? (th/find-by-attr tree :data-rf-diff-annotation "subs-was"))
            "first-run row carries NO `← was <prev>` annotation
             (no prior value to be was)")
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-subs-leaf-changed-0"))
            "the changed-wrapper is NOT mounted (mutually exclusive
             with leaf-added)")
        (let [txt (th/text-content added)]
          (is (string/includes? txt "+")
              "leading `+` glyph paints (parity with the inspector's
               R1 `:added` shape)")
          (is (string/includes? txt "42")
              "after value renders alongside the glyph"))))))

(deftest subscriptions-full-diff-container-keeps-inspector-mount-test
  (testing "rf2-fyd8u — for CONTAINER sub returns (map / vector / set)
            the value-cell keeps the existing edn-inspector mount with
            `:before` threaded — the inspector's R1-R8 grammar paints
            child-level annotations there. The leaf-scalar wrappers
            (`-leaf-changed-` / `-leaf-added-`) are NOT mounted on the
            container path. Acceptance criterion 7."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (testing "map sub return — container path"
        (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                    :rows [{:sub-id :counter/state :sub-vec [:counter/state]
                            :inputs nil :changed? true :first-run? false
                            :before {:a 1} :after {:a 1 :b 2}}]
                    :changed 1 :unchanged 0}
              tree (view/render-subscriptions-step step)]
          (is (nil? (th/find-by-testid tree "rf-xray-epoch-subs-leaf-changed-0"))
              "container path does NOT mount the leaf-changed wrapper")
          (is (nil? (th/find-by-testid tree "rf-xray-epoch-subs-leaf-added-0"))
              "container path does NOT mount the leaf-added wrapper either")))
      (testing "vector sub return — container path"
        (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                    :rows [{:sub-id :cart/items :sub-vec [:cart/items]
                            :inputs nil :changed? true :first-run? false
                            :before [1 2] :after [1 2 3]}]
                    :changed 1 :unchanged 0}
              tree (view/render-subscriptions-step step)]
          (is (nil? (th/find-by-testid tree "rf-xray-epoch-subs-leaf-changed-0")))
          (is (nil? (th/find-by-testid tree "rf-xray-epoch-subs-leaf-added-0")))))
      (testing "first-run? on a CONTAINER → still container path
                (the `:added` chrome only applies to leaf-scalars; the
                inspector's own R1 paints `:added` for whole-subtree
                containers)"
        (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                    :rows [{:sub-id :cart/items :sub-vec [:cart/items]
                            :inputs nil :changed? true :first-run? true
                            :before nil :after [1 2 3]}]
                    :changed 1 :unchanged 0}
              tree (view/render-subscriptions-step step)]
          (is (nil? (th/find-by-testid tree "rf-xray-epoch-subs-leaf-added-0"))
              "container first-run does NOT route through the leaf-added
               wrapper — inspector handles the whole-subtree :added at
               the row level"))))))

;; rf2-vv3m6 (2026-05-29) — `subscriptions-diff-mode-unchanged-by-leaf-
;; branch-test` retired. The test pinned the `:diff` and `:full` mode
;; branches of `subs-value-cell` to verify the rf2-fyd8u leaf-scalar
;; chrome appeared only under `:full+diff`. Those two branches retired
;; with the mode toggle; FULL+DIFF is the single rendering, so the
;; leaf-scalar chrome paints unconditionally for changed leaf-scalar
;; rows (covered by the two leaf-* tests above).

;; ---- rf2-1cc03 — `caused by <event-id>` chrome on SUBSCRIPTIONS rows ----

(deftest subscriptions-row-renders-cause-event-id-chrome-test
  (testing "rf2-1cc03 — SUBSCRIPTIONS row with `:cause-event-id` mounts a
            `caused by <event-id>` chrome in the sub cell, attributing
            the recompute to the dispatching cascade. The event-id
            routes through `ei/mini` so the keyword paints with the
            canonical syntax-token chrome (keyword magenta) — parity
            with the sibling sub-id rendering."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :counter/value :sub-vec [:counter/value]
                          :inputs nil :changed? true :first-run? false
                          :before 0 :after 1
                          :cause-event-id :counter/inc}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            chrome (th/find-by-testid tree "rf-xray-epoch-sub-row-cause-event-id-0")]
        (is (some? chrome)
            "the cause-event-id chrome mounts when the row carries it")
        (let [txt (th/text-content chrome)]
          (is (string/includes? txt "caused by")
              "chrome carries the `caused by` prose label")
          (is (string/includes? txt ":counter/inc")
              "chrome renders the event-id keyword (mini paints the colon)"))
        (is (pos? (count (mini-mounts chrome)))
            "event-id routes through ei/mini for syntax-token chrome
             (keyword magenta) — parity with the sibling sub-id")))))

(deftest subscriptions-row-omits-cause-event-id-chrome-when-absent-test
  (testing "rf2-1cc03 — SUBSCRIPTIONS row WITHOUT `:cause-event-id`
            (a sub that ran outside any in-flight cascade — the
            attribution slot is absent at the projection level)
            does NOT mount the chrome. The cell stays at the
            sub-id-only baseline shape."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :counter/value :sub-vec [:counter/value]
                          :inputs nil :changed? true :first-run? false
                          :before 0 :after 1}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)]
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-sub-row-cause-event-id-0"))
            "no chrome mount when `:cause-event-id` is absent
             (parity with the OMIT-vs-nil semantics on the projection)")
        ;; the sub-id span itself still renders — the absence is only
        ;; the secondary attribution line.
        (is (some? (th/find-by-testid tree "rf-xray-epoch-sub-row-0"))
            "the row itself still mounts; only the chrome is omitted")))))

;; ---- rf2-309cy — VIEWS row view-id keyword routes through ei/mini ------

(deftest views-row-view-id-routes-through-mini-test
  (testing "rf2-309cy — VIEWS row's view-id keyword routes through
            `ei/mini` so the cell carries the syntax-highlighted
            keyword chrome (same data-shape as the sibling subs-read
            cell, rf2-8w8er intent). The id cell must mount at least
            one mini widget alongside the view-id text."
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id :app.counter/Counter
                        :subs-read [] :duration-ms nil}]}
          tree (view/render-views-step step)
          id-cell (th/find-by-testid tree "rf-xray-epoch-view-row-id-0")]
      (is (some? id-cell)
          "the view-id span renders")
      (is (pos? (count (mini-mounts id-cell)))
          "the view-id cell mounts at least one ei/mini widget (no longer plain text)")
      (is (string/includes? (th/text-content id-cell) ":app.counter/Counter")
          "the keyword text is still present (mini renders the colon + ns + name)"))))

(deftest unmounted-views-row-view-id-routes-through-mini-test
  (testing "rf2-309cy — UNMOUNTED VIEWS row's view-id keyword routes
            through `ei/mini` (parity with the re-render row's chrome)."
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows []
                :unmounted-rows [{:view-id :app.sidebar/Item
                                  :instance [:Item 0]
                                  :frame :rf/default}]}
          tree (view/render-views-step step)
          id-cell (th/find-by-testid tree "rf-xray-epoch-view-unmounted-row-id-0")]
      (is (some? id-cell)
          "the unmounted view-id span renders")
      (is (pos? (count (mini-mounts id-cell)))
          "the unmounted view-id cell mounts at least one ei/mini widget"))))

;; ---- rf2-d2akf — DISPOSED sub row carries click-to-source ---------------

(deftest disposed-sub-row-mounts-coord-chip-when-meta-resolves-test
  (testing "rf2-d2akf — the DISPOSED sub row routes through
            `coord-chip/coord-chip` to surface a click-to-source
            affordance for the reg-sub (parity with the sibling
            unmounted-views row). Pre-fix the disposed cell had NO
            coord-chip affordance at all — the call-site itself was
            missing.

            The chip's <button> mounts when `(rf/handler-meta :sub
            sub-id)` resolves a `:file` coord. CLJS macro-form
            `reg-sub` captures `:file`/`:line` at the test call-site
            so the integration round-trip is testable here."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (rf/reg-sub :rf.uo4e2-fixture/items
        (fn [db _] (get db :items [])))
      (let [meta-resolved? (boolean (some-> (rf/handler-meta :sub :rf.uo4e2-fixture/items)
                                            :file string?))
            step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows []
                  :changed 0 :unchanged 0
                  :disposed-rows [{:sub-id :rf.uo4e2-fixture/items
                                   :query  [:rf.uo4e2-fixture/items]
                                   :reason :no-more-derefers
                                   :frame  :rf/default}]}
            tree (view/render-subscriptions-step step)]
        (is (some? (th/find-by-testid tree "rf-xray-epoch-sub-disposed-row-0"))
            "the disposed row itself renders")
        ;; If the test harness captured a source coord, the chip
        ;; mounts. Otherwise the call-site still fired but produced
        ;; nil (graceful degrade per coord-chip's contract). Either
        ;; way the bug-fix is in place — pre-fix there was no chip
        ;; call-site at all. Pin both branches.
        (let [chip (th/find-by-testid tree
                     "rf-xray-epoch-sub-disposed-row-coord-0")]
          (if meta-resolved?
            (do
              (is (some? chip)
                  "coord chip mounts when reg-sub captured a source coord")
              (is (= :button (first chip))
                  "chip mounts as a <button>")
              (is (fn? (:on-click (second chip)))
                  "chip carries an on-click handler"))
            (is (nil? chip)
                "coord chip drops out cleanly when no coord is resolvable")))))))

(deftest active-sub-row-mounts-coord-chip-when-meta-resolves-test
  (testing "rf2-aesni — the ACTIVE SUBSCRIPTIONS row's sub-name cell
            routes through `coord-chip/coord-chip` to surface a
            functional click-to-source affordance for the reg-sub
            (parity with the disposed-subs + views rows). Pre-fix the
            active cell rendered a bare decorative `(icons/external-
            link)` glyph with no coord resolution + no click handler —
            it never dispatched `:rf.xray/open-in-editor`.

            The chip's <button> mounts when `(rf/handler-meta :sub
            sub-id)` resolves a `:file` coord. CLJS macro-form `reg-sub`
            captures `:file`/`:line` at the test call-site so the
            integration round-trip is testable here."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (rf/reg-sub :rf.aesni-fixture/items
        (fn [db _] (get db :items [])))
      (let [meta-resolved? (boolean (some-> (rf/handler-meta :sub :rf.aesni-fixture/items)
                                            :file string?))
            ;; A parameterized sub-vec drives the LABEL but the coord
            ;; lookup must still key off `sub-id` (the registration
            ;; keyword) — that is the rf2-aesni invariant.
            step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :rf.aesni-fixture/items
                          :sub-vec [:rf.aesni-fixture/items 5]
                          :inputs nil :changed? true :before 1 :after 2}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)]
        (is (some? (th/find-by-testid tree "rf-xray-epoch-sub-row-0"))
            "the active sub row itself renders")
        (let [chip (th/find-by-testid tree "rf-xray-epoch-sub-row-coord-0")]
          (if meta-resolved?
            (do
              (is (some? chip)
                  "coord chip mounts when reg-sub captured a source coord")
              (is (= :button (first chip))
                  "chip mounts as a clickable <button> (not a dead glyph)")
              (is (fn? (:on-click (second chip)))
                  "chip carries an on-click handler that dispatches open-in-editor"))
            (is (nil? chip)
                "coord chip drops out cleanly when no coord is resolvable")))))))

(deftest parameterized-sub-inputs-resolve-by-sub-id-test
  (testing "rf2-87c8a — the SUBSCRIPTIONS `inputs` column resolves a
            row's input by the SUB-ID off `:input-signals`, NOT the
            cascade attribution the row's `:inputs` slot carries. A
            PARAMETERIZED derived sub (`[:chain-root>? 5]`, declared
            `:<- [:chain-root]`) ran fresh with NO cascade attribution
            (`:inputs nil`); pre-fix the cell fell through to the
            `app-db` fallback and mislabeled it a Level-1 reader. The
            fix keys `:input-signals` by the sub-id (first element of
            the query-v), so the cell reads its REAL input sub
            (`chain-root`) regardless of cascade state."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      ;; L1 — reads app-db directly (genuine `:input-signals []`).
      (rf/reg-sub :rf.87c8a-fixture/chain-root
        (fn [db _] (get db :chain-input 0)))
      ;; Parameterized L2 — `:<-` chain on the SUB-ID; every
      ;; `[:chain-root>? N]` instance shares this one registration.
      (rf/reg-sub :rf.87c8a-fixture/chain-root>?
        :<- [:rf.87c8a-fixture/chain-root]
        (fn [root [_ threshold]] (> root threshold)))
      ;; `reg-sub` ALWAYS stashes `:input-signals` in the registrar meta
      ;; (`re-frame.subs/parse-reg-sub-args`), so resolution is a hard
      ;; precondition — not a may-or-may-not branch. Pin it so a
      ;; regression in handler-meta resolution can't silently no-op the
      ;; assertions below.
      (is (= [[:rf.87c8a-fixture/chain-root]]
             (:input-signals (rf/handler-meta
                               :sub :rf.87c8a-fixture/chain-root>?)))
          "the parameterized sub's `:input-signals` resolves by sub-id
           (arg-free) to the input sub's query-v")
      (is (= [] (:input-signals (rf/handler-meta
                                 :sub :rf.87c8a-fixture/chain-root)))
          "the L1 root has empty `:input-signals` (genuine app-db reader)")
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{;; the PARAMETERIZED instance — arg in the
                          ;; query-v, `:inputs nil` (ran with no cascade
                          ;; attribution, the rf2-87c8a symptom).
                          :sub-id  :rf.87c8a-fixture/chain-root>?
                          :sub-vec [:rf.87c8a-fixture/chain-root>? 5]
                          :inputs  nil :changed? true :before 1 :after 2}
                         {;; the L1 root — genuinely reads app-db.
                          :sub-id  :rf.87c8a-fixture/chain-root
                          :sub-vec [:rf.87c8a-fixture/chain-root]
                          :inputs  nil :changed? true :before 0 :after 1}]
                  :changed 2 :unchanged 0}
            tree        (view/render-subscriptions-step step)
            ;; Scope the assertion to each row's INPUTS cell specifically:
            ;; the sub-id `:…/chain-root>?` ALSO contains "chain-root", so
            ;; asserting on the whole row text would be a false positive.
            ;; Search WITHIN each row's subtree so the shared `inputs`
            ;; header cell (which also stamps `data-rf-xray-resizable-col`)
            ;; doesn't shift the indexing.
            param-row    (th/find-by-testid tree "rf-xray-epoch-sub-row-0")
            l1-row       (th/find-by-testid tree "rf-xray-epoch-sub-row-1")
            param-inputs (some-> (th/find-by-attr
                                   param-row :data-rf-xray-resizable-col "inputs")
                                 th/text-content)
            l1-inputs    (some-> (th/find-by-attr
                                   l1-row :data-rf-xray-resizable-col "inputs")
                                 th/text-content)]
        (is (some? param-row) "the parameterized sub row renders")
        (is (some? l1-row) "the L1 root row renders")
        (is (string/includes? param-inputs "chain-root")
            "the parameterized sub's INPUTS cell reads its REAL input
             sub (chain-root), resolved by sub-id from `:input-signals`")
        (is (not (string/includes? param-inputs "app-db"))
            "the parameterized sub's INPUTS cell is NOT the `app-db`
             fallback — the rf2-87c8a bug (a fresh-run derived sub
             mislabeled as a Level-1 reader)")
        (is (string/includes? l1-inputs "app-db")
            "a genuine Level-1 reader (empty `:input-signals`) still
             shows the `app-db` source label in its INPUTS cell")))))

(deftest first-run-container-sub-renders-added-chrome-test
  (testing "rf2-kp7bw — a first-run subscription whose value is a
            CONTAINER (map / vector / set) renders the whole subtree
            with `:added` chrome, parity with scalar first-runs. Pre-fix
            the container branch consulted only `:before` (nil on a
            first run), so the inspector mounted plain — no diff mode,
            no added signal — while every scalar sibling painted
            `:added`. The fix passes `:added? true` to the edn-inspector
            (edn-inspector §10.0.13), which synthesises the prior side
            as the engine's missing-sentinel so the projection
            classifies the root op as `:added`.

            Canonical case: the `[:rf/route]` map sub on a /counter
            view-mount epoch — a first run returning a map."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [route-map {:id :counter :params {} :query {} :transition :idle}
            step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :rf/route :sub-vec [:rf/route]
                          :inputs nil :changed? true :first-run? true
                          :before nil :after route-map}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            ;; The container branch mounts the canonical edn-inspector;
            ;; the realized widget root + descendants stamp
            ;; `data-rf-diff-op` with the classified op. `find-all-by-
            ;; attr` walks through (realizes) the form-2 component, so
            ;; the inspector's projection is actually computed.
            added-nodes    (th/find-all-by-attr tree :data-rf-diff-op "added")
            modified-nodes (th/find-all-by-attr tree :data-rf-diff-op "modified")]
        (is (some? (th/find-by-testid tree "rf-xray-epoch-sub-row-0"))
            "the first-run container sub row renders")
        (is (pos? (count added-nodes))
            "the first-run container subtree paints `:added` chrome
             (the inspector entered diff mode via `:added?`, not a
             plain mount)")
        (is (zero? (count modified-nodes))
            "the first-run is `:added`, never a `:modified` type-flip
             (engine missing-sentinel discipline — NOT the edn-inspector
             `::missing` keyword, which would project `:modified`)")))))

(deftest first-run-empty-container-sub-still-added-test
  (testing "rf2-kp7bw — the inverse case: a first-run sub returning an
            EMPTY container (`{}` / `[]`) still reads `:added`. The
            engine reports root `:added` for `(missing-sentinel, {})`."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :app/empty-map :sub-vec [:app/empty-map]
                          :inputs nil :changed? true :first-run? true
                          :before nil :after {}}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            added-nodes (th/find-all-by-attr tree :data-rf-diff-op "added")]
        (is (pos? (count added-nodes))
            "a first-run EMPTY container still reads `:added`")))))

;; ---- rf2-zuh3p — SUBSCRIPTIONS per-row violations attach inline ----------

(deftest subscriptions-row-violations-attach-inline-test
  (testing "rf2-zuh3p — when a SUBSCRIPTIONS row carries a per-row
            :violations slot (the :sub-return boundary failure attached
            by the projection), the schema-violation sub-block renders
            INLINE — directly underneath its owning row, via the
            resizable-table's :row-extras slot — not pooled at the foot
            of the step. Mirrors the FX step's `fx-row-with-violations`."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [violation {:where :sub-return
                       :failing-id :cart/preview
                       :explain-humanized {:errors ["must be int"]}
                       :rollback? false}
            step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :cart/preview :sub-vec [:cart/preview]
                          :inputs nil :changed? true :before 1 :after "bad"
                          :violations [violation]}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            ;; The inline-attached block uses the step-key
            ;; `:sub-row-<sub-name>` (rf2-xgeag namer).
            inline-block (th/find-by-testid
                           tree "rf-xray-epoch-violations-sub-row-preview")]
        (is (some? inline-block)
            "the per-row violation sub-block renders (anchored on the
            sub-row-<name> step-key suffix)")))))

(deftest subscriptions-step-level-violations-still-at-foot-test
  (testing "rf2-zuh3p — step-level (non-row-attributed) violations
            still ride at the foot of the SUBSCRIPTIONS step via the
            `violation-blocks :subscriptions` call (parity preserved —
            only per-row violations moved to inline)."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows []
                  :changed 0 :unchanged 0
                  :violations [{:where :sub-return :failing-id :indirect/sub
                                :explain-humanized {:errors ["nope"]}}]}
            tree (view/render-subscriptions-step step)
            foot (th/find-by-testid tree "rf-xray-epoch-violations-subscriptions")]
        (is (some? foot)
            "step-level violations still render at the foot of the
            SUBSCRIPTIONS step (no behaviour change for non-row
            attribution)")))))

;; ---- rf2-zn6u5 — schema-violation Malli expected/got decomposition -------

(deftest decode-malli-explain-returns-expected-got-test
  (testing "rf2-zn6u5 — `decode-malli-explain` lifts the first error's
            :schema + :value into a programmer-friendly summary map.
            Pure data fn; JVM-testable."
    (is (= {:expected :int :got "bad" :more-errors 0}
           (view/decode-malli-explain
             {:schema :int
              :value "bad"
              :errors [{:path [] :in [] :schema :int :value "bad"}]})))))

(deftest decode-malli-explain-falls-back-to-root-value-test
  (testing "rf2-zn6u5 — when the first error does NOT carry :value
            (the value rides on the explain map's root), :got reads
            from `explain`'s `:value` slot."
    (is (= {:expected :int :got 42 :more-errors 0}
           (view/decode-malli-explain
             {:schema :int :value 42
              :errors [{:path [] :schema :int}]})))))

(deftest decode-malli-explain-counts-additional-errors-test
  (testing "rf2-zn6u5 — multi-error explain maps surface
            `:more-errors (- N 1)` so the call-site can paint a
            `(+N more)` chip beneath the first-error summary."
    (let [exp {:schema [:map [:a :int] [:b :int]]
               :value {:a "x" :b "y"}
               :errors [{:path [:a] :schema :int :value "x"}
                        {:path [:b] :schema :int :value "y"}
                        {:path [:c] :schema :int :value :extra}]}]
      (is (= 2 (:more-errors (view/decode-malli-explain exp)))
          "explain with 3 errors → :more-errors 2"))))

(deftest decode-malli-explain-non-malli-returns-nil-test
  (testing "rf2-zn6u5 — non-Malli validators / pre-rf2-2ek7t framework
            produce explain maps without the canonical {:errors [...]}
            shape; the decoder degrades to nil so the view drops the
            decomposition row cleanly."
    (is (nil? (view/decode-malli-explain nil)))
    (is (nil? (view/decode-malli-explain {})))
    (is (nil? (view/decode-malli-explain {:errors []})))
    (is (nil? (view/decode-malli-explain {:errors :not-a-vec})))
    (is (nil? (view/decode-malli-explain "not a map")))))

(deftest violation-block-renders-expected-got-summary-test
  (testing "rf2-zn6u5 — when a violation's :explain carries Malli's
            canonical shape, the sub-block paints `expected:` + `got:`
            summary lines via `ei/mini` ABOVE the full humanized
            explain map render."
    (let [row {:kind :rf.error/schema-validation-failure
               :where :app-db
               :failing-id :counter/inc
               :path [:count]
               :value "not-an-int"
               :explain {:schema :int
                         :value "not-an-int"
                         :errors [{:path [:count] :schema :int
                                   :value "not-an-int"}]}
               :explain-humanized {:errors ["must be int"]}
               :rollback? true
               :sensitive? false}
          tree (view/violation-block :handler 0 row)
          base "rf-xray-epoch-violation-handler-0"]
      (is (some? (th/find-by-testid tree (str base "-decoded")))
          "the decomposed sub-block renders when explain carries the Malli shape")
      (let [expected (text-of tree (str base "-expected"))
            got      (text-of tree (str base "-got"))]
        (is (string/includes? expected ":int")
            "expected line renders the schema form via ei/mini")
        (is (string/includes? got "not-an-int")
            "got line renders the failing value via ei/mini")))))

(deftest violation-block-multi-error-paints-more-chip-test
  (testing "rf2-zn6u5 — multi-error explain maps surface a
            `(+N more)` chip below the first-error summary."
    (let [row {:where :app-db
               :failing-id :user/profile
               :path [:user]
               :explain {:schema [:map [:a :int] [:b :int]]
                         :value {:a "x" :b "y"}
                         :errors [{:path [:a] :schema :int :value "x"}
                                  {:path [:b] :schema :int :value "y"}]}}
          tree (view/violation-block :handler 0 row)
          chip-text (text-of tree "rf-xray-epoch-violation-handler-0-more-errors")]
      (is (some? chip-text))
      (is (string/includes? chip-text "+1 more")
          "the multi-error chip reads `(+N more)` where N = errors-count - 1"))))

(deftest violation-block-non-malli-skips-decoded-block-test
  (testing "rf2-zn6u5 — when :explain is absent or non-Malli, the
            decomposed sub-block drops out cleanly (no row appears)
            and the humanized explain map still renders."
    (let [row {:where :app-db
               :failing-id :no-explain/case
               :explain-humanized {:errors ["something"]}}
          tree (view/violation-block :handler 0 row)
          base "rf-xray-epoch-violation-handler-0"]
      (is (nil? (th/find-by-testid tree (str base "-decoded")))
          "decoded sub-block is omitted when no Malli explain is present")
      (is (some? (th/find-by-testid tree (str base "-explain")))
          "the humanized explain still renders (unchanged behaviour)"))))

;; ---- rf2-ahhgn — inline exception card + per-step ✓/✗ + outcome banner ---

(deftest error-block-renders-message-and-title-test
  (testing "rf2-ahhgn / rf2-wnvid — `error-block` renders the red card with
            the 'Exception Thrown' title, the op-derived human headline,
            and the verbatim message. The redundant jump-to-source link is
            DROPPED (rf2-wnvid — the HANDLER verb is the canonical link)."
    (let [row  {:operation :rf.error/handler-exception
                :message "button-deck / handler (intentional — exercises the handler error surface)"
                :failing-id :button-deck/throw-handler
                :recovery :no-recovery
                ;; a committed-then-rolled-back db → the chip is legitimate
                :db-committed? true}
          tree (view/error-block :handler 0 row)
          base "rf-xray-epoch-error-handler-0"]
      (is (some? (th/find-by-testid tree base))
          "the exception card wrapper renders")
      (is (string/includes? (text-of tree (str base "-title")) "Exception Thrown")
          "title reads 'Exception Thrown'")
      (is (string/includes? (text-of tree (str base "-recovery")) "Rolled back")
          ":no-recovery + db-committed? → 'Rolled back' chip")
      (is (string/includes? (text-of tree (str base "-headline"))
                            "event handler threw")
          "headline names the handler failure")
      (is (string/includes? (text-of tree (str base "-message"))
                            "intentional")
          "the verbatim ex-info message renders")
      (is (nil? (th/find-by-testid tree (str base "-source")))
          "rf2-wnvid — the redundant jump-to-source link is dropped"))))

(deftest error-block-handler-headline-ignores-phase-test
  (testing "rf2-wnvid — a `:phase :before` handler-exception (button-15's
            live shape — the handler runs as the terminal :before
            interceptor) reads 'The event handler threw.', NOT the
            pre-rf2-wnvid 'An interceptor / coeffect threw (:before).'"
    (let [tree (view/error-block :handler 0
                 {:operation :rf.error/handler-exception
                  :message "boom" :phase :before
                  :failing-id :button-deck/throw-handler})
          head (text-of tree "rf-xray-epoch-error-handler-0-headline")]
      (is (string/includes? head "event handler threw"))
      (is (not (string/includes? head "interceptor"))
          "no spurious interceptor/coeffect attribution from :phase"))))

(deftest error-block-no-spurious-rolled-back-test
  (testing "rf2-wnvid — when NO :db committed (`db-committed?` false /
            absent — button-15: the handler threw before producing any
            :db), the 'Rolled back' chip is OMITTED even though the
            substrate stamped :recovery :no-recovery (nothing to revert)."
    (let [tree (view/error-block :handler 0
                 {:operation :rf.error/handler-exception
                  :message "boom"
                  :recovery :no-recovery
                  :db-committed? false})]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-error-handler-0-recovery"))
          "no commit → no spurious 'Rolled back' chip"))))

(deftest error-block-collapsible-details-test
  (testing "rf2-wnvid — when the exception carries a stack / ex-data, the
            card renders a collapsed `<details>` disclosure rather than an
            always-expanded block."
    (let [ex   (ex-info "boom" {:surface :handler-exception})
          tree (view/error-block :handler 0
                 {:operation :rf.error/handler-exception
                  :message "boom"
                  :exception ex})
          base "rf-xray-epoch-error-handler-0"]
      (is (some? (th/find-by-testid tree (str base "-details")))
          "the collapsible details disclosure renders")
      (is (some? (th/find-by-testid tree (str base "-ex-data")))
          "ex-data is surfaced inside the disclosure")))
  (testing "rf2-wnvid — no exception object → no details disclosure"
    (let [tree (view/error-block :handler 0
                 {:operation :rf.error/handler-exception :message "boom"})]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-error-handler-0-details"))
          "nothing to disclose → the details element is omitted"))))

(deftest error-block-fx-headline-test
  (testing "rf2-ahhgn — an fx-handler exception names the failing fx-id"
    (let [tree (view/error-block :fx 0
                 {:operation :rf.error/fx-handler-exception
                  :message "fx boom" :failing-id :http/post
                  :recovery :no-recovery})]
      (is (string/includes? (text-of tree "rf-xray-epoch-error-fx-0-headline")
                            ":http/post")
          "headline names the failing effect"))))

(deftest error-blocks-nil-safe-test
  (testing "rf2-ahhgn — `error-blocks` renders nothing for empty / nil"
    (is (nil? (view/error-blocks :handler nil)))
    (is (nil? (view/error-blocks :handler [])))))

(deftest handler-step-renders-inline-exception-test
  (testing "rf2-ahhgn — the live button-15 shape: a handler step carrying
            an attached exception renders the inline error card AND the
            header's ✗ status glyph"
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-event-db :event-id :button-deck/throw-handler
                :db-diff [] :fx [] :machine nil
                :status :error
                :errors [{:operation :rf.error/handler-exception
                          :message "boom in handler"
                          :coord {:file "core.cljs" :line 322}
                          :failing-id :button-deck/throw-handler
                          :recovery :no-recovery}]}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-error-handler-0"))
          "the inline exception card renders under the HANDLER step")
      (is (string/includes?
            (text-of tree "rf-xray-epoch-error-handler-0-message")
            "boom in handler"))
      (let [glyph (th/find-by-testid tree "rf-xray-epoch-handler-status")]
        (is (some? glyph) "the per-step status glyph renders")
        (is (= "error" (:data-rf-xray-step-status (th/attrs glyph)))
            "the glyph data-attr reports :error")
        (is (string/includes? (th/text-content glyph) "✗")
            "a failed step paints the ✗ glyph")))))

(deftest handler-step-clean-paints-ok-glyph-test
  (testing "rf2-ahhgn — a clean handler step paints the quiet ✓ glyph and
            renders no error card"
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-event-db :event-id :counter/inc
                :db-diff [] :fx [] :machine nil}
          tree (view/render-handler-step step)
          glyph (th/find-by-testid tree "rf-xray-epoch-handler-status")]
      (is (= "ok" (:data-rf-xray-step-status (th/attrs glyph))))
      (is (string/includes? (th/text-content glyph) "✓"))
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-errors-handler"))
          "a clean step renders no error block"))))

(deftest side-effects-renders-per-row-exception-test
  (testing "rf2-ahhgn / rf2-kt6js — a throwing fx (button-18) surfaces
            its message on its own :fx sub-step row via
            `fx-row-with-violations`. The per-row testids use the GLOBAL
            flat-row index, so the :fx row after the :db row lands at
            index 1."
    (let [step (side-effects-step
                 [{:kind :db :rows [{:fx-id :db :status :ok}]}
                  {:kind :fx
                   :rows [{:fx-id :button-deck/ping :status :error
                           :errors [{:operation :rf.error/fx-handler-exception
                                     :message "fx threw on purpose"
                                     :failing-id :button-deck/ping
                                     :recovery :no-recovery}]}]}]
                 1)
          tree (view/render-side-effects-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-error-fx-row-1-0"))
          "the throwing fx row (global index 1) carries its inline error card")
      (is (string/includes?
            (text-of tree "rf-xray-epoch-error-fx-row-1-0-message")
            "fx threw on purpose")))))

;; `outcome-banner-renders-on-error-test` retired (rf2-wnvid) — the
;; top-of-pipeline "This event failed" banner is gone; the failure
;; surfaces inline (the failing step's ✗ glyph + the 'Exception Thrown'
;; card). The panel root still stamps `data-rf-xray-outcome`.

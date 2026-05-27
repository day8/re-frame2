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
            and the parent-epoch navigation chip"
    (let [step {:step :dispatch :badge :DISPATCH :step-number 1
                :event [:cart/add :apple]
                :source :fx-dispatch
                :coord nil
                :source-enrichment {:parent-dispatch-id 9001}}
          epoch-history [{:epoch-id 42 :dispatch-id 9001
                          :trigger-event [:checkout/begin]}]
          tree (view/render-dispatch-step step epoch-history)
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
          epoch-history [{:epoch-id 42 :dispatch-id 9001
                          :trigger-event [:checkout/begin]}]
          tree (view/render-dispatch-step step epoch-history)
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
          epoch-history [{:epoch-id 42 :dispatch-id 9001
                          :trigger-event [:checkout/begin]}]
          tree (view/render-dispatch-step step epoch-history)]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-dispatch-parent-epoch-link"))
          "no clickable link when the parent epoch isn't in the buffer")
      (let [unresolved (th/find-by-testid
                         tree "rf-xray-epoch-dispatch-parent-epoch-unresolved")]
        (is (some? unresolved) "the unresolved-parent chip is rendered")
        (is (= :span (first unresolved))
            "the unresolved chip is a plain span")))))

(deftest dispatch-source-fx-dispatch-without-history-test
  (testing "rf2-5qp4g — when render-dispatch-step is called without
            epoch-history (direct test callers, or pre-history-seed
            cold mount), `:fx-dispatch` still renders the kind label
            with the parent chip in unresolved form."
    (let [step {:step :dispatch :badge :DISPATCH :step-number 1
                :event [:cart/add :apple]
                :source :fx-dispatch
                :coord nil
                :source-enrichment {:parent-dispatch-id 9001}}
          tree (view/render-dispatch-step step)]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-dispatch-parent-epoch-link"))
          "no clickable link without epoch-history threaded")
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
            Empty diff renders `— (no changes)`; populated diff
            renders the path-changes.

            rf2-n2jig — the toggle's default flipped to `:full+diff`
            (mode-3). These tests pin `:diff` explicitly via the
            persistence slot so the diff-list assertions remain
            authoritative against the explicit-pin path."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray.epoch/set-db-diff-mode :diff]))
    (testing "reg-event-db with empty diff"
      (let [tree (rf/with-frame :rf/xray
                   (view/render-handler-step
                     {:step :handler :badge :HANDLER :step-number 3
                      :flavour :reg-event-db :event-id :nop
                      :db-diff [] :fx [] :machine nil}))
            slot (th/find-by-testid tree "rf-xray-epoch-handler-db-diff")]
        (is (some? slot)
            ":db diff sub-section is always present even when empty")
        (is (string/includes? (or (th/text-content slot) "") "no changes")
            "empty diff renders `— (no changes)` per design §Empty edge cases")))
    (testing "reg-event-db with populated diff"
      (let [tree (rf/with-frame :rf/xray
                   (view/render-handler-step
                     {:step :handler :badge :HANDLER :step-number 3
                      :flavour :reg-event-db :event-id :counter/inc
                      :db-diff [[[:counter :value] 5 6 :modified]]
                      :fx [] :machine nil}))
            slot (th/find-by-testid tree "rf-xray-epoch-handler-db-diff")]
        (is (some? slot))
        (is (some? (th/find-by-testid
                     tree "rf-xray-epoch-handler-diff-row-0"))
            "the populated diff row renders inside the sub-section")))
    (testing "reg-event-fx with empty diff"
      (let [tree (rf/with-frame :rf/xray
                   (view/render-handler-step
                     {:step :handler :badge :HANDLER :step-number 3
                      :flavour :reg-event-fx :event-id :navigate
                      :db-diff [] :fx [{:fx-id :navigate :value "/x"}]
                      :machine nil}))
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

;; ---- rf2-xgeag — inline violation sub-block + hot-reload tail step ----

(deftest violation-block-renders-headline-+-fields-test
  (testing "rf2-xgeag — `violation-block` renders the pink sub-block
            with title, recovery chip, headline, path, and value rows"
    (let [row  {:kind :rf.error/schema-validation-failure
                :where :app-db
                :failing-id :counter/inc
                :path [:count]
                :value "not-an-int"
                :rollback? true
                :sensitive? false}
          tree (view/violation-block :handler 0 row)
          base "rf-xray-epoch-violation-handler-0"]
      (is (some? (th/find-by-testid tree base))
          "the sub-block wrapper renders")
      (let [title    (text-of tree (str base "-title"))
            recovery (text-of tree (str base "-recovery"))
            headline (text-of tree (str base "-headline"))
            path     (text-of tree (str base "-path"))]
        (is (string/includes? title "SCHEMA VIOLATION"))
        (is (string/includes? recovery "rolled back"))
        (is (string/includes? headline "app-db commit"))
        (is (string/includes? headline ":counter/inc"))
        (is (string/includes? path "[:count]"))))))

(deftest violation-block-recovery-chip-test
  (testing "rf2-xgeag — recovery chip surfaces a per-where label when
            no rollback fired"
    (let [tree (view/violation-block :fx 0
                 {:where :fx-args :failing-id :http/post
                  :rollback? false})
          recovery (text-of tree "rf-xray-epoch-violation-fx-0-recovery")]
      (is (string/includes? recovery "fx skipped"))))

  (testing "rf2-xgeag — sub-return → 'returned nil'"
    (let [tree (view/violation-block :subscriptions 0
                 {:where :sub-return :failing-id :user/profile
                  :rollback? false})
          recovery (text-of tree
                            "rf-xray-epoch-violation-subscriptions-0-recovery")]
      (is (string/includes? recovery "returned nil")))))

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

;; ---- rf2-uffov — FX section header + per-action attribution ----------

(deftest fx-step-header-shows-outcome-split-test
  (testing "rf2-uffov — FX step header surfaces the threw-count when
            non-zero, beside the subdued `(side effects)` caption.

            Post pair-debug 2026-05-26 (commits 862288aca / adaabb8aa):
            the prior `N fired (M succeeded, K threw)` verb was
            dropped — the per-row ✓/✗ glyphs already convey per-fx
            outcome; the count summary was noise. The header now
            renders a subdued `(side effects)` caption beside the
            badge, with a red `K threw` chip only when fx errored.
            `N fired` + `M succeeded` are no longer surfaced in the
            header."
    (let [step {:step :fx :badge :FX :step-number 4
                :rows [{:fx-id :db :status :ok}
                       {:fx-id :http/get :status :ok}
                       {:fx-id :bad :status :error}]
                :succeeded 2 :threw 1 :skipped 0}
          tree (view/render-fx-step step)
          header (text-of tree "rf-xray-epoch-fx-header")]
      (is (string/includes? header "(side effects)")
          "the subdued caption rides beside the badge")
      (is (string/includes? header "1 threw")
          "threw-count chip surfaces in the header when non-zero"))))

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

(deftest handler-db-diff-values-route-through-mini-test
  (testing "rf2-8w8er — HANDLER step's :db diff rows render before /
            after values through `ei/mini` so per-token chrome paints
            (numbers orange, sentinels chip).

            rf2-n2jig — pin `:diff` mode explicitly since the default
            flipped to `:full+diff`."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray.epoch/set-db-diff-mode :diff]))
    (let [tree (rf/with-frame :rf/xray
                 (view/render-handler-step
                   {:step :handler :badge :HANDLER :step-number 3
                    :flavour :reg-event-db :event-id :counter/inc
                    :db-diff [[[:counter :value] 5 6 :modified]]
                    :fx [] :machine nil}))]
      (is (pos? (count (mini-mounts tree)))
          "the :db diff row mounts at least one mini-render"))))

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
          "the other section mounts an edn-inspector"))))

(deftest fx-step-args-route-through-mini-test
  (testing "rf2-8w8er — FX step row's args render through `ei/mini`."
    (let [tree (view/render-fx-step
                 {:step :fx :badge :FX :step-number 4
                  :rows [{:fx-id :http/get :status :ok :args {:url "/x"}}]
                  :succeeded 1 :threw 0 :skipped 0})
          row  (th/find-by-testid tree "rf-xray-epoch-fx-row-0")]
      (is (some? row))
      (is (pos? (count (mini-mounts row)))
          "the fx row's args mount a mini-render"))))

(deftest subscriptions-values-route-through-mini-test
  (testing "rf2-8w8er + rf2-yqjrd — SUBSCRIPTIONS row renders sub-vec
            + before / after values through `ei/mini` so the table
            cells light up with syntax-token chrome rather than plain
            `pr-str`. The mini-mount triad applies to the `:diff`
            value-mode (the prior shape); the new `:full` /
            `:full+diff` modes route through the full edn-inspector
            instead. Test pins both halves: setting the value-mode
            to `:diff` recovers the original assertion."
    (epoch-orchestrator/install!)
    (frame/reg-frame :rf/xray {})
    (rf/with-frame :rf/xray
      ;; rf2-yqjrd — flip the value-mode to `:diff` so the per-row
      ;; cell renders via `mini` instead of the new edn-inspector
      ;; mount that `:full+diff` (default) uses.
      (rf/dispatch-sync [:rf.xray.epoch/set-subs-value-diff-mode :diff])
      (let [step {:step :subscriptions :badge :SUBSCRIPTIONS :step-number 5
                  :rows [{:sub-id :counter/total :sub-vec [:counter/total]
                          :inputs nil :changed? true :before 5 :after 6}]
                  :changed 1 :unchanged 0}
            tree (view/render-subscriptions-step step)
            row  (th/find-by-testid tree "rf-xray-epoch-sub-row-0")]
        (is (some? row))
        ;; sub-vec + before + after = 3 minimum mini mounts in the row
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
;; `:rf.xray.epoch/db-diff-mode` read, or `render-subscriptions-step`'s
;; `:rf.xray.epoch/subs-filter-mode` read — rf2-tzmmf) fires while the
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
            `:rf.xray.epoch/db-diff-mode` read) fire during the parent
            reg-view's reactive scope. An unrealised lazy seq at this
            position is the rf2-atqkg bug shape (Reagent emits the
            `Reactive deref not supported in lazy seq, it should be
            wrapped in doall` console warning at render time)."
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

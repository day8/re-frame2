(ns re-frame.machine-action-outcome-classification-cljs-test
  "rf2-orcd31 — `:rf.machine/action-ran`'s `:outcome` tag (the action's RAW
  returned effect map, `{:data :fx}` per Spec 005 §Action effect map) carried
  NO classification pass in `re-frame.classification/project-machine-tags`:
  the cond-> handled `:before` / `:after` / `:snapshot` / bare `:data` /
  `:input` / `:cascade` / `:event` but not `:outcome`, so an action returning
  updated `:data` containing a classified path (the NORMAL shape of a
  state-mutating action) leaked it raw on EVERY transition.

  The fix projects `:outcome` in two halves:

    - `:data` — under the machine's class gate, with the SAME `:data`-rooted
      path set the bare `:data` / `[:input :data]` clauses use.
    - `:fx` + the hard-disallowed `:db` — UNCONDITIONALLY
      (`project-action-outcome-shell`): the `:fx` entries walk the same
      per-entry registration/dynamic classification as the `:rf.event/fx`
      aggregate (rf2-32ffq1's `project-fx-args`), and a disallowed `:db` echo
      summarizes to `:rf/redacted` (matching `:rf.error/machine-action-wrote-db`'s
      unconditional `:offending-value` posture).

  Mirrors rf2-ghgbqi's regression style
  (machine_routed_event_classification_cljs_test): deterministic projector
  teeth on hand-built trace shapes + a live round-trip proving the action
  itself still reads and persists the raw value (egress-only).

  Dual-runtime `*_cljs_test.cljc`: the shadow `:node-test` build
  (`npm run test:cljs`, `cljs-test$` ns-regexp) AND the JVM `clojure -M:test`
  runner both run it.

  ## Posture split (rf2-d2841)

  The DETERMINISTIC PROJECTOR TEETH — `rf.classification/project-machine-tags`
  called directly on hand-built trace shapes — are pure functions and run
  under `scripts/test-core-prod-gate.sh` unchanged. So does the live
  round-trip's EGRESS-ONLY claim: the action reads the raw value, the durable
  snapshot holds it, and a second action reads it back. Those are the
  assertions that prove redaction did not corrupt control flow, and they are
  the ones worth having in the production lane.

  The LIVE TRACE assertions are dev-only, because the trace stream they police
  does not exist under `-Dre-frame.debug=false`. They are kept verbatim inside
  a `(when rf.interop/debug-enabled? …)` arm marked `rf2-d2841` — the no-leak
  NEGATIVE emphatically included. `(not (some #(leaks? sentinel %) @seen))`
  over an empty `@seen` is true because nothing was emitted, and a redaction
  suite reporting green on that basis is the worst false green in this
  programme. Its teeth are the `(is (seq rans) …)` positive beside it, which
  is only satisfiable with the channel live."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Boot the optional machines artefact so `rf/reg-machine`
            ;; resolves through the spec-005 implementation.
            [re-frame.machines]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; UNIQUE sentinels that must never appear raw in a projected trace slot.
(def ^:private data-sentinel "rf2-orcd31-DATA-6a91cf")
(def ^:private db-sentinel   "rf2-orcd31-DB-1d84e2")
(def ^:private fx-sentinel   "rf2-orcd31-FX-73c0ba")

(defn- leaks? [sentinel x] (str/includes? (pr-str x) sentinel))

(defn- project [ev] (:tags (rf.classification/project-trace-event ev)))

(def ^:private mid :rf.orcd31/settings)

;; =====================================================================
;; A. Deterministic projector teeth — hand-built :rf.machine/action-ran
;;    trace shapes.
;; =====================================================================

(deftest outcome-data-redacts-by-machine-data-classification
  (testing "an action's returned {:data …} echoed at :outcome redacts the
            machine's :data-rooted :sensitive paths — same path set as the
            bare :data / [:input :data] slots — while non-secret keys survive"
    (rf.registrar/register! :event mid {:sensitive [[:data :secret]]})
    (let [t (project {:operation :rf.machine/action-ran
                      :tags {:actor-id  mid
                             :frame     :rf/default
                             :action-id :save!
                             :input     {:data  {:secret data-sentinel :count 1}
                                         :event [:save]}
                             :outcome   {:data {:secret data-sentinel
                                                :count  2}}}})]
      (is (= rf.privacy/redacted-sentinel (get-in t [:outcome :data :secret]))
          ":outcome [:data :secret] reads :rf/redacted")
      (is (= 2 (get-in t [:outcome :data :count]))
          "the non-secret :count survives (path-precise)")
      (is (= rf.privacy/redacted-sentinel (get-in t [:input :data :secret]))
          "[:input :data] still redacts (unchanged pre-existing behaviour)")
      (is (not (leaks? data-sentinel t))
          "the data sentinel appears NOWHERE in the projected action-ran trace"))))

(deftest outcome-keyword-passes-through
  (testing "the non-map :outcome values (:ok for a nil return, the throw
            marker) ride through untouched"
    (rf.registrar/register! :event mid {:sensitive [[:data :secret]]})
    (doseq [outcome [:ok :rf.error/action-threw]]
      (is (= outcome
             (:outcome (project {:operation :rf.machine/action-ran
                                 :tags {:actor-id mid
                                        :frame    :rf/default
                                        :outcome  outcome}})))
          (str outcome " passes through untouched")))))

(deftest outcome-fx-entries-walk-their-own-registrations
  (testing "an action's returned :fx entries walk the SAME per-entry
            classification as the :rf.event/fx aggregate — including on a
            machine with NO classification of its own (registration-driven)"
    ;; A classified TARGET event — the nested-dispatch inheritance (rf2-32ffq1).
    (rf.registrar/register! :event ::classified-target {:sensitive [[:secret]]})
    (let [t (project {:operation :rf.machine/action-ran
                      :tags {:actor-id :rf.orcd31/unclassified-machine
                             :frame    :rf/default
                             :outcome  {:fx [[:dispatch
                                              [::classified-target
                                               {:secret fx-sentinel}]]]}}})]
      (is (= rf.privacy/redacted-sentinel
             (get-in t [:outcome :fx 0 1 1 :secret]))
          "the outcome's nested [:dispatch [classified-target …]] payload redacts")
      (is (not (leaks? fx-sentinel t))
          "the fx sentinel appears nowhere in the projected trace"))))

(deftest outcome-disallowed-db-summarizes-unconditionally
  (testing "a (hard-disallowed) :db key on the echoed action return summarizes
            to :rf/redacted regardless of the machine's classification —
            matching :rf.error/machine-action-wrote-db's :offending-value
            posture on the error trace it always accompanies"
    (let [t (project {:operation :rf.machine/action-ran
                      :tags {:actor-id :rf.orcd31/unclassified-machine
                             :frame    :rf/default
                             :outcome  {:db   {:auth {:token db-sentinel}}
                                        :data {:ok true}}}})]
      (is (= rf.privacy/redacted-sentinel (get-in t [:outcome :db]))
          "the whole disallowed :db echo reads :rf/redacted")
      (is (true? (get-in t [:outcome :data :ok]))
          "the legal :data half is untouched on an unclassified machine")
      (is (not (leaks? db-sentinel t))))))

;; =====================================================================
;; B. Live round-trip — reg-machine + dispatch. The action reads and
;;    persists the RAW value; every emitted trace redacts.
;; =====================================================================

(deftest live-action-outcome-redacts-while-action-reads-raw
  (testing "rf2-orcd31 acceptance: a machine action returning updated :data
            containing a classified path — the action computes with (and the
            snapshot durably holds) the RAW value, while :rf.machine/action-ran's
            :outcome (and every other emitted slot) ships it redacted"
    (let [wrote (atom ::none)
          read  (atom ::none)
          seen  (atom [])]
      ;; OPTS metadata `:sensitive`: `[1 :value]` classifies the routed inner
      ;; event's arg-map slot (so the echo slots redact — rf2-ghgbqi);
      ;; `[:data :secret]` classifies the durable snapshot slot (and, post-fix,
      ;; the action-return echo at :outcome).
      (rf/reg-machine mid
        {:sensitive [[1 :value] [:data :secret]]}
        {:initial :idle
         :data    {:secret "seed"}
         :actions {:save! (fn [{:keys [data event]}]
                            (let [v (get-in event [1 :value])]
                              (reset! wrote v)
                              {:data (assoc data :secret v)}))
                   :read! (fn [{:keys [data]}]
                            (reset! read (:secret data))
                            nil)}
         :states  {:idle {:on {:save {:target :done :action :save!}}}
                   :done {:on {:check {:target :done :action :read!}}}}})
      (rf/dispatch-sync [mid [:rf.machine/start]])
      (rf/register-listener! :trace ::orcd31 (fn [ev] (swap! seen conj ev)))
      (rf/dispatch-sync [mid [:save {:value data-sentinel}]])
      (rf/unregister-listener! :trace ::orcd31)

      ;; 1. the action READ the raw routed value and RETURNED it in :data.
      (is (= data-sentinel @wrote)
          "the action computed with the RAW value (egress-only redaction)")

      ;; rf2-d2841 — dev-instrumentation arm. The trace stream IS the surface
      ;; this projector protects, and it does not exist under
      ;; `-Dre-frame.debug=false`. Both assertions go inside, the
      ;; no-leak NEGATIVE especially: over an empty `@seen` "no emitted trace
      ;; event leaks the sentinel" is true because nothing was emitted, which
      ;; is exactly the false green a redaction test must never report. Its
      ;; teeth are the `(is (seq rans) …)` positive beside it, and that
      ;; positive is only satisfiable with the channel live.
      (when rf.interop/debug-enabled?
        ;; 2. an :rf.machine/action-ran trace fired, and its :outcome redacts.
        (let [rans (filter #(= :rf.machine/action-ran (:operation %)) @seen)]
          (is (seq rans) "an :rf.machine/action-ran trace was emitted")
          (doseq [ev rans]
            (is (not (leaks? data-sentinel (get-in ev [:tags :outcome])))
                ":outcome ships the classified :data slot redacted")))

        ;; 3. NOTHING in the emitted stream leaks the sentinel.
        (is (not (some #(leaks? data-sentinel %) @seen))
            "no emitted trace event leaks the data sentinel"))

      ;; 4. the DURABLE snapshot really holds the raw value — prove it by
      ;;    reading it back through a second action.
      (rf/dispatch-sync [mid [:check]])
      (is (= data-sentinel @read)
          "the snapshot durably holds the RAW value the next action reads"))))

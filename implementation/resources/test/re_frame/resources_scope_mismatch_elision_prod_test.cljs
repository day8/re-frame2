(ns re-frame.resources-scope-mismatch-elision-prod-test
  "Per Spec 009 §Production builds (rf2-rsmiru) — RUNTIME prod-elision
  contract for the sub-side session-scope mismatch dev-warning
  (`:rf.warning/resource-sub-scope-mismatch`, see
  `re-frame.resources.subs/maybe-warn-scope-mismatch!`).

  This is the companion to the JVM/dev behavioural tests in
  `re-frame.resources-runtime-cljs-test` (which prove the warning DOES
  fire on a genuine mismatch under dev-mode). This file pins the
  PRODUCTION side: under `:advanced` + `goog.DEBUG=false` the warning's
  whole body — the `interop/debug-enabled?` gate, the `registry/
  resource-meta` lookup, the `:entries` scan, and the `trace/emit!`
  fan-out — DCEs, so a registered trace listener observes NO
  `:rf.warning/resource-sub-scope-mismatch` event even when the sub
  reads a genuinely mismatched scope. The sub still returns its
  documented `:idle` empty-state projection (fail-closed is preserved;
  only the advisory dev warning elides).

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` shadow-cljs build
  (`:ns-regexp \"-elision-prod-test$\"`, `:closure-defines {goog.DEBUG
  false}`). Running this under `goog.DEBUG=true` would FAIL — the
  warning delivers under dev-mode (that is the dev contract pinned in the
  runtime test). This mirrors `re-frame.http-trace-emit-elision-prod-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; load-bearing side-effecting require: the façade registers the
            ;; :rf.resource/* events + subs + the generation cofx/fx. Forces
            ;; the gated `maybe-warn-scope-mismatch!` body into the bundle so
            ;; DCE proves the branch dead from a reachable module.
            [re-frame.resources]
            [re-frame.resources.state :as state]
            [re-frame.resources.subs :as subs]
            [re-frame.resources.test-support]
            [re-frame.http.managed]
            [re-frame.schemas :as schemas]
            ;; rf2-qwm0a — listener surface lives in `re-frame.trace.tooling`.
            [re-frame.trace.tooling :as trace-tooling]))

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture
  "Override the real :rf.http/managed fx with a capturing no-op so ensure's
  :loading entry write is deterministic and no real fetch fires. Also
  DISABLE schema validation for the duration of this suite: the shared
  prod-elision build installs a global Malli validator from a sibling
  suite, and this test does not exercise params validation — it only needs
  a from-caller resource registered + a scope mismatch read. Snapshot +
  restore the schema-fn bundle so the suite leaves no cross-test residue."
  [f]
  (reset! last-managed-args nil)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (let [snapshot (schemas/snapshot-schema-fns)]
    (schemas/set-schema-validator! nil)
    (try (f)
         (finally (schemas/restore-schema-fns! snapshot)))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter})
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- entry [scoped-key]
  (get-in (runtime-db) (state/entry-path scoped-key)))

(defn- article-spec [overrides]
  (merge {:scope         :rf.scope/from-caller
          :params-schema [:map [:slug :string]]
          :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
         overrides))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(defn- listener-fixture
  "Install a recording trace listener, run `body-fn`, return the captured
  events vector. Records EVERY trace event so any leak surfaces under an
  `empty?` assertion."
  [body-fn]
  (let [seen   (atom [])
        cb-key (keyword (str "scope-mismatch-elision-prod-" (gensym)))]
    (trace-tooling/register-listener! cb-key (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (trace-tooling/unregister-listener! cb-key)))))

;; ---- the prod-elision contract --------------------------------------------

(deftest scope-mismatch-warning-elides-under-prod
  (testing "Per Spec 009 §Production-elision (rf2-rsmiru): a from-caller sub
            reading a genuinely mismatched scope (one with zero active owners
            while a DIFFERENT scope is active) emits NO
            :rf.warning/resource-sub-scope-mismatch trace under :advanced +
            goog.DEBUG=false — the whole warning body elides while the sub
            still returns its documented :idle projection (fail-closed kept)."
    (rf/reg-resource :sme/article (article-spec {:scope :rf.scope/from-caller}) article-spec-request)
    ;; ensure + settle scope A active (the real lease) …
    (let [ka (state/scoped-resource-key {:user "a"} :sme/article {:slug "w"})]
      (rf/dispatch-sync [:rf.resource/ensure {:resource :sme/article :scope {:user "a"}
                                              :params {:slug "w"} :owner [:route :r 1]}])
      (let [wid (:current-work (entry ka))]
        (rf/dispatch-sync [:rf.resource.internal/succeeded
                           {:resource/key ka :work/id wid :generation 1
                            :data {:title "W"}}])))
    ;; … then subscribe under scope B (the mismatch the dev warning targets)
    (let [seen (listener-fixture
                 (fn []
                   (subs/state-sub-fn (runtime-db)
                                      [:rf.resource/state
                                       {:resource :sme/article :scope {:user "b"}
                                        :params {:slug "w"}}])))]
      (is (empty?
            (filter #(= :rf.warning/resource-sub-scope-mismatch (:operation %)) seen))
          "no scope-mismatch warning delivered under :advanced + goog.DEBUG=false
           — the maybe-warn-scope-mismatch! body elided")
      (is (empty? seen)
          "no trace events at all from the sub read under prod")
      (is (= :idle (:status (subs/state-sub-fn (runtime-db)
                                               [:rf.resource/state
                                                {:resource :sme/article :scope {:user "b"}
                                                 :params {:slug "w"}}])))
          "the sub still returns the :idle empty-state projection — fail-closed
           is preserved; only the advisory warning elided"))))

(ns re-frame.story.inline-plan-test
  "Headless inline-plan execution tests (NewTestStory rf2-5x1wt.20,
  `tools/story/spec/017-Testing-Story.md` §Inline plan + §three verbs).

  An inline plan is an executable plan MAP that is NOT registered as a
  Story variant. `story/run` / `story/is` / `story/explain` accept a map
  target (a keyword target is a registered variant; a map is an inline
  plan). The §B6 acceptance bullets, verified here:

  - an inline plan runs HEADLESSLY (the same unified run-result a
    registered variant returns);
  - an inline plan is ABSENT from Story navigation (nothing is registered);
  - an inline plan can use a REGISTERED check;
  - an inline plan CANNOT reference a missing fragment (it fails cleanly);
  - `story/is` reports through the test framework for a map target;
  - an inline plan and a registered variant describing the SAME behaviour
    produce equivalent final app-db + assertion records AFTER
    `canonicalize` (the metamorphic relation, §three verbs / §20).

  JVM-only (`.clj`): on the JVM `story/run` returns a `CompletableFuture`
  that resolves synchronously to the unified result, and `story/is` BLOCKS
  on it + fires one `clojure.test` report per assertion. The CLJS run is
  async; its plan compilation + report projection are covered host-free by
  the plan / result suites."
  (:require [clojure.test :refer [deftest is testing use-fixtures] :as t]
            [malli.core         :as m]
            [re-frame.core      :as rf]
            [re-frame.frame     :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story     :as story]
            [re-frame.story.frames :as frames]
            [re-frame.story.play.runner-events :as re]))

(defn- reset-rf! [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (reset! re/run-state {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  ;; A tiny event the inline plans drive — the app under test.
  (rf/reg-event :inline/set-status (fn [{:keys [db]} [_ v]] {:db (assoc db :status v)}))
  (rf/reg-event :inline/inc        (fn [{:keys [db]} _]     {:db (update db :n (fnil inc 0))}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- run-target [target]
  (.get ^java.util.concurrent.CompletableFuture (story/run target)))

(defn- capture-reports
  "Run `thunk` with `clojure.test/report` rebound to collect every
  `:pass`/`:fail`/`:error` report map `story/is` fires. Returns
  `[thunk-return reports-vector]`."
  [thunk]
  (let [collected (atom [])]
    (binding [t/report (fn [m]
                         (when (#{:pass :fail :error} (:type m))
                           (swap! collected conj m)))]
      [(thunk) @collected])))

;; ===========================================================================
;; Inline plan runs headlessly
;; ===========================================================================

(deftest inline-plan-runs-headless-pass
  (testing "a map target runs headlessly and yields the unified run-result
            (§B6 — inline plan runs headlessly). The verdict is driven by the
            in-script `[:assert …]` checkpoint — the same surface a registered
            variant's verdict comes from (terminal :assertions are declarative;
            the lifecycle records in-script checkpoints)."
    (let [result (run-target {:script [[:dispatch [:inline/set-status :loaded]]
                                       [:assert [:rf.assert/path-equals [:status] :loaded]]]})]
      (is (= :pass (:status result)) "the unified verdict is :pass")
      (is (= :loaded (:status (:app-db result))) "setup/script drove app-db")
      (is (= :ready (:lifecycle result)))
      (is (every? :passed? (:assertions result)) "the checkpoint passed"))))

(deftest inline-plan-runs-headless-fail
  (testing "a failing in-script checkpoint in an inline plan yields :status :fail"
    (let [result (run-target {:script [[:dispatch [:inline/set-status :idle]]
                                       [:assert [:rf.assert/path-equals [:status] :loaded]]]})]
      (is (= :fail (:status result)))
      (is (not (every? :passed? (:assertions result)))))))

(deftest inline-plan-terminal-assertions-only-auto-run
  (testing "an INLINE plan with ONLY a terminal :assertions block (no
            in-script [:assert], no :script) AUTO-RUNS the terminal assertion
            against the FINAL settled state and produces a verdict — the same
            lifecycle a registered variant gets (rf2-nyjoa). Both a PASS and a
            FAIL are pinned so the verdict is load-bearing, not vacuous."
    (let [pass (run-target {:setup      [[:dispatch [:inline/set-status :loaded]]]
                            :assertions [[:rf.assert/path-equals [:status] :loaded]]})]
      (is (= :pass (:status pass))
          "the inline terminal assertion auto-ran and passed → :pass")
      (is (= :ready (:lifecycle pass)))
      (let [rec (first (filter #(= :rf.assert/path-equals (:assertion %))
                               (:assertions pass)))]
        (is (some? rec) "the terminal assertion recorded with no checkpoint")
        (is (true? (:passed? rec)))))
    (let [fail (run-target {:setup      [[:dispatch [:inline/set-status :idle]]]
                            :assertions [[:rf.assert/path-equals [:status] :loaded]]})]
      (is (= :fail (:status fail))
          "a failing inline terminal assertion flips the verdict to :fail")
      (is (not (every? :passed? (:assertions fail)))))))

(deftest inline-plan-setup-and-script-both-drive-state
  (testing "an inline plan's :setup establishes preconditions, :script runs after"
    (let [result (run-target {:setup  [[:dispatch [:inline/inc]]]
                              :script [[:dispatch [:inline/inc]]
                                       [:dispatch [:inline/inc]]
                                       [:assert [:rf.assert/path-equals [:n] 3]]]})]
      (is (= :pass (:status result)))
      (is (= 3 (:n (:app-db result)))))))

(deftest inline-plan-loaders-run-from-the-plan
  (testing "an inline plan declaring :loaders runs phase-1 loaders off the
            plan's :world (no registry read) before :setup / :script"
    (let [result (run-target {:loaders [[:inline/set-status :loaded]]
                              :script  [[:assert [:rf.assert/path-equals [:status] :loaded]]]})]
      (is (= :pass (:status result)) "the loader event ran the app to :loaded")
      (is (= :loaded (:status (:app-db result))))
      (is (= :ready (:lifecycle result))))))

;; ===========================================================================
;; Inline plan is ABSENT from Story navigation
;; ===========================================================================

(deftest inline-plan-absent-from-navigation
  (testing "running an inline plan registers NOTHING in the Story side-table
            and leaves no variant frame behind (§B6 — absent from navigation)"
    (is (empty? (story/ids :variant)) "precondition: no variants registered")
    (run-target {:script [[:dispatch [:inline/set-status :ok]]
                          [:assert [:rf.assert/path-equals [:status] :ok]]]})
    (is (empty? (story/ids :variant))
        "no variant id was registered by the inline run")
    (is (empty? (story/variants-by-story))
        "the inline plan appears under no story")
    (is (empty? (story/variant-frames))
        "the anonymous inline frame was torn down — no lingering nav frame")))

(deftest inline-plan-absent-from-navigation-while-in-flight
  (testing "an inline frame is absent from variant-frames / variant-frame?
            WHILE it is allocated — the transient window between
            `allocate-inline!` and `destroy-inline!` (rf2-r16iv). spec/017
            §Inline plan: 'Inline plans MUST NOT appear in Story navigation'
            is UNCONDITIONAL — not merely post-teardown. The pre-existing
            absent-from-navigation test only checks AFTER `run-target`
            returns; on the JVM the run is synchronous so the in-flight
            window is already closed at assertion time. Here we allocate the
            inline frame and assert mid-flight, BEFORE tearing it down — the
            case the post-teardown test misses. Drives `variant-frame?` to
            read the `:rf/inline?` stamp `allocate-inline!` writes, making
            the stamp load-bearing (no longer dead metadata)."
    (let [inline-id :rf.story.inline/plan-transient]
      ;; Allocate an inline frame directly (the registry-free twin of
      ;; `allocate!`): empty decorator stack + no fx-overrides, events-only
      ;; fast-path. This mirrors what `run-inline-plan` does mid-run.
      (frames/allocate-inline! inline-id {} {} true)
      (try
        ;; The frame IS live — it exists in the runtime's frame registry.
        (is (contains? (set (rf/frame-ids)) inline-id)
            "precondition: the inline frame is allocated and live")
        ;; It carries BOTH stamps — it is a Story-managed frame…
        (let [m (rf/frame-meta inline-id)]
          (is (true? (:rf/story? m)) "inline frame carries the :rf/story? stamp")
          (is (true? (:rf/inline? m)) "inline frame carries the :rf/inline? stamp"))
        ;; …yet `variant-frame?` excludes it (reads :rf/inline?) and so it
        ;; is absent from the navigable enumeration the UI shell walks.
        (is (false? (frames/variant-frame? inline-id))
            "an allocated inline frame is NOT a navigable variant frame")
        (is (not (contains? (frames/variant-frames) inline-id))
            "an in-flight inline frame is absent from variant-frames")
        (is (not (contains? (story/variant-frames) inline-id))
            "…through the public story/variant-frames surface too")
        (finally
          (frames/destroy-inline! inline-id {} nil)))
      (is (empty? (frames/variant-frames))
          "post-teardown: no lingering nav frame (the pre-existing guard)"))))

;; ===========================================================================
;; Inline plan can use a REGISTERED check
;; ===========================================================================

(deftest inline-plan-composes-registered-check
  (testing "an inline plan composing a registered check resolves the check id
            into the plan + groups its records under the check id, exactly the
            way a registered variant does (§B6 — registered check). The check's
            atom is ALSO an in-script checkpoint so it actually records, proving
            the registered check resolved and ran."
    (story/reg-check :check.inline/status-loaded
      {:assertions [[:rf.assert/path-equals [:status] :loaded]]})
    ;; The compiled plan carries the composed check id under [:expect :checks].
    (let [plan (story/variant-plan {:compose [:check.inline/status-loaded]
                                    :script  [[:dispatch [:inline/set-status :loaded]]]})]
      (is (= [:check.inline/status-loaded] (get-in plan [:expect :checks]))
          "the registered check id resolves into the inline plan's :expect"))
    (let [result (run-target {:compose [:check.inline/status-loaded]
                              :script  [[:dispatch [:inline/set-status :loaded]]
                                        [:assert [:rf.assert/path-equals [:status] :loaded]]]})]
      (is (= :pass (:status result)))
      (let [check (first (filter #(= :check.inline/status-loaded (:check %))
                                 (:checks result)))]
        (is (some? check) "the run-result groups records under the check id")
        (is (= :pass (:status check)) "the composed check aggregates :pass")
        (is (every? :passed? (:assertions check))
            "the registered check's assertion ran + passed")))))

(deftest inline-plan-composes-registered-fragment
  (testing "an inline plan composing a registered fragment appends the
            fragment's setup + script (§B6 — composed fragments/checks)"
    (story/reg-fragment :fragment.inline/seed
      {:setup [[:dispatch [:inline/set-status :seeded]]]})
    (let [result (run-target {:compose [:fragment.inline/seed]
                              :script  [[:assert [:rf.assert/path-equals [:status] :seeded]]]})]
      (is (= :pass (:status result)) "the fragment setup seeded the app-db")
      (is (= :seeded (:status (:app-db result)))))))

;; ===========================================================================
;; Inline plan CANNOT reference a missing fragment — fails cleanly
;; ===========================================================================

(deftest inline-plan-missing-fragment-fails-cleanly
  (testing "an inline plan composing an unregistered fragment FAILS plan
            construction with a structured error result — no frame allocated,
            no exception escapes (§B6 — missing fragment fails cleanly)"
    (let [result (run-target {:compose [:fragment.inline/does-not-exist]
                              :script  [[:dispatch [:inline/set-status :ok]]]})]
      (is (= :error (:status result)))
      (is (= :rf.error/story-compose-unknown
             (:assertion (first (:assertions result))))
          "the structured :compose-unknown error rides the assertion record")
      (is (empty? (story/ids :variant))
          "nothing was registered by the failed inline run")
      (is (empty? (story/variant-frames))
          "no frame lingers from the failed inline run"))))

(deftest inline-plan-missing-arg-fails-cleanly
  (testing "an inline plan with an [:arg …] placeholder for an undeclared arg
            FAILS plan construction cleanly (the args contract is not exempt
            for inline plans)"
    (let [result (run-target {:script [[:dispatch [:inline/set-status [:arg :missing]]]]})]
      (is (= :error (:status result)))
      (is (= :rf.error/story-missing-arg
             (:assertion (first (:assertions result))))))))

;; ===========================================================================
;; story/is reports through the test framework for a map target
;; ===========================================================================

(deftest story-is-reports-per-assertion-for-map-target
  (testing "story/is fires one report per assertion for an inline plan
            (§B6 — story/is reports through the test framework for a map
            target)"
    (let [[result reports]
          (capture-reports
            #(story/is {:script [[:dispatch [:inline/set-status :loaded]]
                                 [:assert [:rf.assert/path-equals [:status] :loaded]]
                                 [:assert [:rf.assert/path-equals [:status] :loaded]]]}))]
      (is (= :pass (:status result)) "the unified verdict is :pass")
      (is (= 2 (count reports)) "one report per recorded assertion")
      (is (every? #(= :pass (:type %)) reports)))))

(deftest story-is-reports-fail-for-map-target
  (testing "story/is fires a :fail report for a failing inline-plan assertion"
    (let [[result reports]
          (capture-reports
            #(story/is {:script [[:dispatch [:inline/set-status :idle]]
                                 [:assert [:rf.assert/path-equals [:status] :loaded]]]}))]
      (is (= :fail (:status result)))
      (is (= 1 (count reports)))
      (is (= :fail (:type (first reports))))
      (is (re-find #":rf.assert/path-equals" (:message (first reports)))))))

;; ===========================================================================
;; Metamorphic relation: inline plan ≡ registered variant after canonicalize
;; ===========================================================================

(deftest inline-and-registered-equivalent-after-canonicalize
  (testing "an inline plan and a registered variant describing the SAME
            behaviour produce equivalent final app-db + assertion records
            AFTER canonicalize (§B6 — the metamorphic relation)"
    (let [behaviour {:setup      [[:dispatch [:inline/inc]]]
                     :script     [[:dispatch [:inline/inc]]
                                  [:assert [:rf.assert/path-equals [:n] 2]]]
                     :assertions [[:rf.assert/path-equals [:n] 2]]
                     :tags       #{:test}}]
      (story/reg-variant :story.inline/equivalent behaviour)
      (let [registered (run-target :story.inline/equivalent)
            inline     (run-target behaviour)]
        (testing "both reach the same verdict + app-db before canonicalize"
          (is (= :pass (:status registered)))
          (is (= :pass (:status inline)))
          (is (= 2 (:n (:app-db registered))))
          (is (= 2 (:n (:app-db inline)))))
        (testing "canonicalized final app-db is equal (frame ids stripped)"
          (is (= (story/canonicalize (:app-db registered))
                 (story/canonicalize (:app-db inline)))))
        (testing "canonicalized assertion records are equal (variant/frame
                  ids reconciled + stripped)"
          (is (= (story/canonicalize (:assertions registered))
                 (story/canonicalize (:assertions inline)))))
        (testing "the two runs share a canonical run-hash"
          (is (= (story/run-hash registered)
                 (story/run-hash inline))))))))

;; ===========================================================================
;; explain accepts a map target (rf2-5x1wt.24 base; confirmed for the verb)
;; ===========================================================================

(deftest explain-accepts-inline-plan-map
  (testing "story/explain compiles a map target directly (no registration)"
    (let [ex (story/explain {:variant/id :inline/explained
                             :setup      [[:dispatch [:inline/inc]]]
                             :script     [[:dispatch [:inline/inc]]]})]
      (is (= [:inline/explained] (:source-chain ex)))
      (is (= [[:dispatch [:inline/inc]]] (:setup-order ex)))
      (is (empty? (story/ids :variant)) "explain registered nothing"))))

;; ===========================================================================
;; FAILURE-PATH TEARDOWN — an inline plan that fails mid-run still runs the
;; SAME teardown the success path runs (rf2-7u3eja)
;;
;; The success path tears the anonymous inline frame down with the decorator
;; stack used for allocation + the plan's `:loaders-teardown`; the FAILURE
;; path previously ran `(destroy-inline! frame-id nil nil)` — the frame was
;; removed but `:loaders-teardown` + frame-setup decorator `:teardown` were
;; SKIPPED, leaking any resource an inline loader or `:frame-setup` decorator
;; opened on precisely the failure path where cleanup matters. Both paths now
;; converge on the SAME teardown.
;;
;; The forced post-allocation throw is a `:db-seed` schema violation: it
;; throws in `run-db-seed!` (phase 0.5) AFTER `allocate-inline!` ran the
;; frame-setup `:init`, so the failure-path teardown owes the symmetric
;; cleanup. Validation rides the SAME schemas late-bind seam the runtime uses
;; (no hard dep on the schemas artefact — Malli backs the host-free stand-in).
;; ===========================================================================

(defn- install-schema-seam!
  "Install the schemas late-bind seam (the SAME seam the runtime's
  `db-seed-violations` reaches) so an inline frame validates its `:db-seed`
  against `schema-for-any-frame`. An inline frame-id is minted dynamically,
  so the entries fn returns the schema for ANY frame-id (the inline run is
  the only frame on the classpath under test). Malli backs the validator —
  no hard dep on the schemas artefact."
  [schema-for-any-frame]
  (late-bind/set-fn! :schemas/frame-schema-entries
                     (fn [_frame-id] schema-for-any-frame))
  (late-bind/set-fn! :schemas/validate-with-registered-fn
                     (fn [schema value] (m/validate schema value)))
  (late-bind/set-fn! :schemas/explain-with-registered-fn
                     (fn [schema value] (m/explain schema value))))

(defn- uninstall-schema-seam! []
  (swap! late-bind/hooks dissoc
         :schemas/frame-schema-entries
         :schemas/validate-with-registered-fn
         :schemas/explain-with-registered-fn))

(deftest inline-plan-failure-runs-decorator-and-loader-teardown
  (testing "an inline plan that FAILS mid-run (a :db-seed schema violation,
            thrown AFTER frame allocation) still runs the plan's
            :loaders-teardown AND its frame-setup decorator :teardown — the
            same convergent cleanup the success path runs, so no frame /
            decorator / loader leaks on the failure path (rf2-7u3eja).

            :init / :teardown / :loaders-teardown write to side-atoms (the
            frame's app-db is gone after destroy, so observation lives
            outside it). Validation rides the schemas late-bind seam."
    ;; An app-db schema the seed violates → run-db-seed! throws AFTER
    ;; allocation (frame-setup :init already ran).
    (install-schema-seam! {[:cart] {:schema [:map [:items [:vector :map]]]}})
    (try
      (let [decorator-init     (atom 0)
            decorator-teardown (atom 0)
            loader-teardown    (atom 0)]
        (rf/reg-event :inline/dec-init
          (fn [{:keys [db]} _] (swap! decorator-init inc) {:db db}))
        (rf/reg-event :inline/dec-teardown
          (fn [{:keys [db]} _] (swap! decorator-teardown inc) {:db db}))
        (rf/reg-event :inline/loader-teardown
          (fn [{:keys [db]} _] (swap! loader-teardown inc) {:db db}))
        (story/reg-decorator :inline/live-resource
          {:kind     :frame-setup
           :init     [[:inline/dec-init]]
           :teardown [[:inline/dec-teardown]]})
        (let [result (run-target
                       {:decorators       [[:inline/live-resource]]
                        :loaders-teardown [[:inline/loader-teardown]]
                        ;; :items must be a vector of maps — a string violates it.
                        :db-seed          {:cart {:items "not-a-vector"}}
                        :script           [[:dispatch [:inline/set-status :loaded]]]})]
          (testing "the run FAILED on the schema-violating seed (not a vacuous pass)"
            (is (= :error (:status result)))
            (is (= :rf.error/story-db-seed-invalid
                   (:assertion (first (filter #(= :rf.error/story-db-seed-invalid
                                                  (:assertion %))
                                              (:assertions result)))))))
          (testing "frame-setup :init ran during allocation (precondition)"
            (is (= 1 @decorator-init)))
          (testing "the FAILURE path ran the SAME teardown the success path runs"
            (is (= 1 @decorator-teardown)
                "frame-setup decorator :teardown fired on the failure path
                 — not skipped (the rf2-7u3eja leak)")
            (is (= 1 @loader-teardown)
                "the plan's :loaders-teardown fired on the failure path"))
          (testing "the inline frame did not leak"
            (is (empty? (story/variant-frames))
                "no lingering nav frame after the failed inline run")
            (is (not-any? #(and (keyword? %)
                                (= "rf.story.inline" (namespace %)))
                          (rf/frame-ids))
                "no inline frame survives in the runtime frame registry"))))
      (finally (uninstall-schema-seam!)))))

(deftest inline-plan-success-runs-decorator-and-loader-teardown
  (testing "control: the SUCCESS path runs the SAME decorator :teardown +
            :loaders-teardown the failure path now runs — proving the two
            paths converge on identical cleanup (rf2-7u3eja)"
    (let [decorator-teardown (atom 0)
          loader-teardown    (atom 0)]
      (rf/reg-event :inline/dec-init     (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event :inline/dec-teardown
        (fn [{:keys [db]} _] (swap! decorator-teardown inc) {:db db}))
      (rf/reg-event :inline/loader-teardown
        (fn [{:keys [db]} _] (swap! loader-teardown inc) {:db db}))
      (story/reg-decorator :inline/live-resource
        {:kind     :frame-setup
         :init     [[:inline/dec-init]]
         :teardown [[:inline/dec-teardown]]})
      (let [result (run-target
                     {:decorators       [[:inline/live-resource]]
                      :loaders-teardown [[:inline/loader-teardown]]
                      :script           [[:dispatch [:inline/set-status :loaded]]
                                         [:assert [:rf.assert/path-equals [:status] :loaded]]]})]
        (is (= :pass (:status result)) "the control run passes")
        (is (= 1 @decorator-teardown) "decorator :teardown fired on success")
        (is (= 1 @loader-teardown) "loaders-teardown fired on success")
        (is (empty? (story/variant-frames)) "no lingering frame")))))

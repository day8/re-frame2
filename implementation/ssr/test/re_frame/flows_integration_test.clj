(ns re-frame.flows-integration-test
  "Integration coverage for flows COMPOSED with other optional subsystems
  (rf2-vug0k, from the integration audit rf2-ytqzi).

  The flows artefact's own JVM tests pin flows × core thoroughly
  (`flows_test.clj`, `flows_trace_test.clj`, `flows_schema_validation_test.clj`).
  This namespace pins the cross-subsystem compositions the audit probed —
  behaviour the audit found SOUND but that lacked permanent regression
  tests. These document existing correct behaviour; there is NO src change.

  Why this lives in the ssr artefact's test dir: its `:test` classpath is
  the only single artefact that pulls core + flows + schemas + routing +
  machines + ssr all at once (see `ssr/deps.edn`), so the four
  cross-subsystem scenarios can share one classpath and one fixture.

  ## The contract these tests pin (`bd remember event-pipeline-atomicity`,
  Mike CONFIRMED 2026-05-24)

  Flows run at the OUTERMOST `:after` — they transform the handler's
  PENDING `:db` effect (which has already absorbed any machine snapshot
  write and the path-interceptor reshape) BEFORE the single deferred
  install. The `:db` install is the atomic commit boundary:

    - A flow eval runs exactly ONCE per dispatched event, on the SETTLED
      post-handler db (so a multi-microstep machine macrostep settles
      first, then the flow sees the final machine-driven db — no double /
      missed eval).
    - A flow OUTPUT that fails app-db schema validation is install-then-
      rolled-back: forward `:rf.event/db-changed` → `:rf.error/schema-
      validation-failure` → second `db-changed` `:rf.trace/phase :rollback`
      → the WHOLE db (including the flow's write) is wound back.
    - A flow THROW is a PRE-install throw: the event aborts, the pending
      `:db` is discarded, NO `db-changed`, no partial commit. The two
      recovery paths (schema-rollback vs flow-throw-abort) produce DISTINCT
      trace signatures.
    - Under SSR's synchronous drain a sub over a flow's `:path` renders
      the flow-augmented value (the flow ran before install; render reads
      the installed flow-augmented db).
    - An event whose `:fx` `:dispatch`es child events gives EACH child its
      OWN independent flow eval, not the parent's.
    - A flow whose `:inputs` opt into runtime-db via the partition-
      qualified form `[:rf.db/runtime :rf.runtime/routing :current …]`
      reads the POST-transition route (EP-0001 §535-551, rf2-4eisfr —
      Mike RULED (b) 2026-06-09: flows read runtime-db via EXPLICIT
      partition-qualified inputs; the route slice rewrite is the handler's
      pending `:rf.db/runtime` effect; the flow at the outermost `:after`
      transforms against that pending runtime-db before install — there is
      no pre-transition window the flow could observe). The dual-partition
      TRIGGER (§542-544): a runtime-only `:rf.route/transitioned` event
      (no `:db` effect) still recomputes the route-reading flow, because
      the dirty-check keys on BOTH partitions — a runtime-db change cannot
      be hidden merely because app-db was value-identical. The dual
      atomicity assertion: a flow throw on a `:rf.route/transitioned`
      dispatch aborts the WHOLE event (BOTH partitions) — slice stays on
      the previous route, `:on-match` `:dispatch` fxs are NOT walked. Pin
      (rf2-qm8m3): regression coverage for the routing × flows composition
      the rf2-hm4gi delta audit flagged."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            ;; Loading the Malli adapter publishes the
            ;; `:schemas/malli-validate` late-bind hook the default
            ;; validator routes through (Spec 010 §Recommended soft-pass /
            ;; rf2-t0hq); without it `reg-app-schema` validation soft-passes.
            [re-frame.schemas.malli]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.head :as head]
            [re-frame.ssr.request :as request]
            [re-frame.ssr.response :as response]
            [re-frame.trace :as trace]))

;; ---- per-test reset / trace recorder -------------------------------------
;;
;; Mirrors `re-frame.ssr.test-fixture/reset-runtime` (registrar wipe + all
;; per-frame side-channel atoms + ns-load-time reloads of routing / ssr /
;; ssr.head / machines so their registrations resurrect after clear-all!),
;; and ADDS what the trace-order assertions here need: an error-listener
;; clear (the error-emit registry is a `defonce` atom that survives test
;; re-runs) and an all-trace recorder bound per test.

(def ^:dynamic ^:private *captured* nil)

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (schemas/reset-schema-validator!)
  (reset! request/request-slots {})
  (reset! response/response-slots {})
  (reset! error-listener/pending-error-traces {})
  (reset! head/head-snapshots {})
  (flows/reset-last-inputs!)
  ;; The error-emit listener registry is a `defonce` atom that survives
  ;; test re-runs (per rf2-bacs4) — clear so a listener registered by one
  ;; test does not leak into the next.
  (error-emit/clear-error-listeners!)
  (rf/init! ssr/adapter)
  ;; clear-all! wiped the ns-load-time registrations of these artefacts;
  ;; :reload re-evaluates the ns-body so the machine fxs, :rf.route/*
  ;; handlers, :rf.server/* fxs, the head hooks, and the :rf/hydrate
  ;; handler all resurrect between tests.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.ssr.head :reload)
  (require 're-frame.machines :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation + registration surfaces require a carried frame
  ;; stamp. Register `:rf/default` + pin it as the body's ambient scope
  ;; (the carried-invariant equivalent of `(with-frame :rf/default …)`);
  ;; explicit `{:frame …}` opts / inner `with-frame` still win.
  (rf/reg-frame :rf/default {})
  (let [captured (atom [])]
    (binding [*captured* captured]
      (trace/register-listener!
        ::flow-integration-recorder
        (fn [ev] (swap! captured conj ev)))
      (try
        (rf/with-frame :rf/default
          (test-fn))
        (finally
          (trace/unregister-listener! ::flow-integration-recorder)
          (schemas/reset-schema-validator!))))))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- ops
  "All captured trace `:operation`s, in capture order."
  []
  (mapv :operation @*captured*))

(defn- by-op
  "Captured trace events whose `:operation` is `op`, in capture order."
  [op]
  (filterv #(= op (:operation %)) @*captured*))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/machines :snapshots machine-id]))

;; ===========================================================================
;; 1. machine-macrostep × flow — a multi-microstep macrostep SETTLES, then
;;    exactly ONE flow eval runs on the settled db.
;;
;; A machine whose macrostep chains :always (a guarded auto-transition) AND
;; :raise (a pre-commit re-entry) settles through several microsteps within
;; one dispatched event. The machine writes its final snapshot into
;; [:rf.runtime/machines :snapshots id] as the handler's pending :db. Because the flow runs at
;; the OUTERMOST :after — after the machine handler, transforming the
;; pending :db — the flow sees the SETTLED machine-driven db, and runs
;; exactly once for the dispatch (no eval-per-microstep, no missed eval).
;; ===========================================================================

;; EP-0001 §535-551 (rf2-4eisfr) — RE-ENABLED. Mike RULED (b) 2026-06-09:
;; flows read runtime-db via EXPLICIT partition-qualified inputs. Machine
;; snapshots live in the runtime-db partition (EP-0001 rf2-vzld77), so this
;; flow's `:inputs` opt into runtime-db via the qualified form
;; `[:rf.db/runtime :rf.runtime/machines :snapshots …]` (bare paths still
;; read app-db; binary syntax — no `[:rf.db/app …]` form). The flow transform
;; resolves the qualified input against the pending runtime-db partition.
(deftest machine-multi-microstep-macrostep-then-single-flow-eval
  (testing "a multi-microstep (:always + :raise) machine macrostep settles
            within one dispatched event, THEN exactly ONE flow eval runs on
            the settled db — the flow reads the final machine-driven value"
    (let [action-log (atom [])
          machine
          {:initial :idle
           :data    {:ticks 0}
           :guards  {:ready? (fn [{data :data}] (>= (:ticks data) 2))}
           :actions {;; :bump-then-raise increments and re-enters via
                     ;; :raise — a pre-commit microstep chain.
                     :bump-then-raise
                     (fn [{data :data}]
                       (swap! action-log conj :bump-then-raise)
                       {:data {:ticks (inc (:ticks data))}
                        :fx   [[:raise [:tick]]]})
                     :bump
                     (fn [{data :data}]
                       (swap! action-log conj :bump)
                       {:data {:ticks (inc (:ticks data))}})}
           :states
           {:idle    {:on {:start {:target :counting :action :bump-then-raise}}}
            ;; :counting auto-advances to :ready via :always once ticks>=2;
            ;; the raised :tick bumps a second time so the guard becomes true.
            :counting {:always [{:guard :ready? :target :ready}]
                       :on     {:tick {:action :bump}}}
            :ready   {}}}
          flow-evals (atom [])]
      (rf/reg-machine :gauge/flow machine)
      ;; The flow reads the machine's settled tick count and derives a
      ;; label into a plain app-db path. Logging the input it observed lets
      ;; us prove it saw the FINAL (settled) value, not an intermediate one.
      (rf/reg-flow {:id     :gauge/label
                    :inputs [[:rf.db/runtime :rf.runtime/machines :snapshots :gauge/flow :data :ticks]]
                    :output (fn [ticks]
                              (swap! flow-evals conj ticks)
                              (str "ticks=" ticks))
                    :path   [:derived :gauge-label]})
      ;; One dispatched event drives the whole macrostep:
      ;;   idle --start/bump-then-raise--> counting (ticks 1)
      ;;        --raise :tick/bump-------> counting (ticks 2)
      ;;        --always ready?----------> ready
      (rf/dispatch-sync [:gauge/flow [:start]])

      ;; The macrostep settled to :ready with ticks=2 (external observer
      ;; sees only the settled snapshot).
      (let [s (snapshot :gauge/flow)]
        (is (= :ready (:state s))
            "machine settled to :ready — the :raise + :always microsteps
             all ran inside the one dispatched event")
        (is (= 2 (get-in s [:data :ticks]))
            "two bumps landed (initial + raised) before :always fired"))
      (is (= [:bump-then-raise :bump] @action-log)
          "both microstep actions ran in raise order within the macrostep")

      ;; Exactly ONE flow eval — not one per microstep, not zero.
      (is (= 1 (count @flow-evals))
          "the flow evaluated EXACTLY ONCE for the dispatched event — flows
           run at the outermost :after over the SETTLED pending :db, not
           once per machine microstep")
      (is (= [2] @flow-evals)
          "the single flow eval observed the FINAL machine-driven tick count
           (2), proving it ran on the settled db, not an intermediate one")
      (is (= "ticks=2" (get-in (rf/app-db-value :rf/default)
                               [:derived :gauge-label]))
          "the flow output (derived from the settled machine snapshot)
           landed in app-db")
      ;; Trace-level confirmation: a single :rf.flow/computed for the event.
      (is (= 1 (count (by-op :rf.flow/computed)))
          "exactly one :rf.flow/computed trace fired for the macrostep
           dispatch — no double / missed eval"))))

;; ===========================================================================
;; 2. flow-write × schema-rollback — the subtlest interaction. Two
;;    similar-looking recovery paths, pinned distinctly:
;;
;;    (a) a flow OUTPUT that fails APP-DB schema validation → install-then-
;;        rollback. The flow's write rides the same deferred install as the
;;        handler's :db; post-commit app-db validation rejects it, so the
;;        WHOLE db is wound back. Trace signature:
;;            forward db-changed → schema-validation-failure → db-changed
;;            (:rf.trace/phase :rollback)
;;
;;    (b) a flow THROW → the event aborts PRE-install. The pending :db is
;;        discarded; NO db-changed at all; no partial commit. Trace
;;        signature: a flow-eval-exception, ZERO db-changed.
;;
;;    These two MUST produce distinct trace signatures — (a) commits then
;;    unwinds (two db-changed bracketing a schema error); (b) never commits
;;    (no db-changed). Distinguishing them is the whole point: a regression
;;    that turned a flow throw into a silent partial commit, or that skipped
;;    the rollback's second db-changed, would collapse these signatures.
;; ===========================================================================

(deftest flow-output-schema-failure-installs-then-rolls-back-whole-db
  (testing "(a) a flow output that violates the app-db schema is install-
            then-rolled-back: forward db-changed → schema-validation-failure
            → rollback db-changed (:rf.trace/phase :rollback); the WHOLE db
            (handler write AND flow write) is wound back to the pre-handler
            value"
    ;; Malli app-db schema: [:derived :doubled] must be a NON-NEGATIVE int.
    (rf/reg-app-schema [:derived] [:map [:doubled [:int {:min 0}]]])
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1 :derived {:doubled 0}}}))
    ;; The handler writes :n; the flow reads :n and writes a value that the
    ;; app-db schema will REJECT (negative when :n is negative).
    (rf/reg-event :set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-flow {:id     :doubler
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    ;; Seed a conforming baseline (n=1 → doubled=2, conforms).
    (rf/dispatch-sync [:seed])
    (is (= 2 (get-in (rf/app-db-value :rf/default) [:derived :doubled]))
        "baseline: flow wrote a conforming value")
    (let [baseline-db (rf/app-db-value :rf/default)]
      ;; Now drive :n negative → flow writes -6 → app-db schema rejects it.
      (reset! *captured* [])
      (rf/dispatch-sync [:set-n -3])

      ;; The WHOLE db is wound back — neither the handler's :n write NOR the
      ;; flow's bad output stand.
      (is (= baseline-db (rf/app-db-value :rf/default))
          "the whole db was wound back to the pre-handler value — the flow
           write rode the same deferred install and was rolled back with it")
      (is (= 1 (get (rf/app-db-value :rf/default) :n))
          ":n stayed at the pre-handler value (1) — the handler's own write
           was rolled back too (atomic install boundary)")

      ;; Trace signature (a): forward commit → schema failure → rollback
      ;; commit, in that exact order.
      (let [sig (filterv #{:rf.event/db-changed
                           :rf.error/schema-validation-failure}
                         (ops))
            phases (mapv (fn [ev]
                           [(:operation ev) (-> ev :tags :rf.trace/phase)])
                         (filterv #(#{:rf.event/db-changed
                                      :rf.error/schema-validation-failure}
                                    (:operation %))
                                  @*captured*))]
        (is (= [:rf.event/db-changed
                :rf.error/schema-validation-failure
                :rf.event/db-changed]
               sig)
            "rollback signature: forward db-changed → schema-validation-
             failure → rollback db-changed")
        (is (= [[:rf.event/db-changed                  nil]
                [:rf.error/schema-validation-failure   nil]
                [:rf.event/db-changed                  :rollback]]
               phases)
            "the SECOND db-changed carries :rf.trace/phase :rollback; the
             first (forward) commit carries no phase")
        ;; The flow DID compute (its bad output is what tripped the
        ;; validator) — the rollback is a post-commit unwind, not a
        ;; pre-install skip.
        (is (= 1 (count (by-op :rf.flow/computed)))
            "the flow computed its (bad) output before the install — the
             failure is a post-commit rollback, not a flow-eval skip")
        (is (= -6 (-> (by-op :rf.flow/computed) first :tags :result))
            "the flow's computed result (-6) is what the app-db schema
             rejected")))))

(deftest flow-throw-aborts-event-no-db-changed-no-partial-commit
  (testing "(b) a flow THROW aborts the event PRE-install: the pending :db
            is discarded, NO :rf.event/db-changed fires, the handler's own
            :db does NOT land — distinct from the schema-rollback signature
            (no commit at all, vs commit-then-unwind)"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    ;; The handler writes :n; the flow reads :n and THROWS.
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
    ;; Seed a clean baseline FIRST, then register the throwing flow — the
    ;; flow throws on every eval, so registering it after the seed keeps the
    ;; baseline (and isolates the throw to the :bump drain we observe).
    (rf/dispatch-sync [:seed])
    (is (= {:n 0} (rf/app-db-value :rf/default))
        "baseline seeded before the throwing flow is registered")
    (rf/reg-flow {:id     :boom
                  :inputs [[:n]]
                  :output (fn [_] (throw (ex-info "flow boom" {:why :test})))
                  :path   [:derived :doomed]})
    (reset! *captured* [])
    (rf/dispatch-sync [:bump])

    ;; The handler's :db did NOT land — the flow throw aborted the event.
    (is (= {:n 0} (rf/app-db-value :rf/default))
        ":n did NOT increment — a flow throw is a pre-install throw; the
         pending :db (handler write + flow write) was discarded wholesale")

    ;; Trace signature (b): ZERO db-changed, a flow-eval-exception present.
    (is (not-any? #(= :rf.event/db-changed %) (ops))
        "NO :rf.event/db-changed in the throw stream — the event aborted
         before install (contrast: the schema-rollback path emits TWO
         db-changed bracketing the schema error)")
    (is (seq (by-op :rf.flow/failed))
        ":rf.flow/failed fired — the flow eval threw")
    (is (not-any? #(= :rf.error/schema-validation-failure %) (ops))
        "no schema-validation-failure — the abort short-circuits BEFORE the
         post-commit validation that the rollback path reaches")))

;; ===========================================================================
;; 3. flow × SSR sync-drain — under SSR's synchronous drain, a sub over a
;;    flow's :path renders the flow-augmented value.
;;
;; The flow runs at the outermost :after and augments the pending :db before
;; the (synchronous) install; render-to-string then reads the INSTALLED
;; flow-augmented db through a sub over the flow's :path. So the rendered
;; HTML carries the derived value, proving the flow ran before install and
;; the render observes the committed flow-augmented state.
;; ===========================================================================

(deftest flow-augmented-value-renders-under-ssr-sync-drain
  (testing "under SSR's synchronous drain a sub over a flow's :path renders
            the flow-augmented value into the output HTML — the flow ran
            before install; the render reads the installed flow-augmented db"
    (rf/reg-event :ssr/seed (fn [{:keys [db]} _] {:db {:user {:first "Ada" :last "Lovelace"}}}))
    ;; A flow derives a display name from the user map into [:derived :full-name].
    (rf/reg-flow {:id     :user/full-name
                  :inputs [[:user :first] [:user :last]]
                  :output (fn [first last] (str first " " last))
                  :path   [:derived :full-name]})
    ;; A sub reads the FLOW's output path (not the raw inputs) — so what it
    ;; returns proves the flow's write reached the installed db.
    (rf/reg-sub :full-name (fn [db _] (get-in db [:derived :full-name])))
    (rf/reg-view* :pages/greeting
      (fn []
        [:div.greeting
         [:h1 "Hello, " (rf/subscribe-once [:full-name])]]))

    ;; Drain synchronously; the flow augments the pending :db before install.
    (rf/dispatch-sync [:ssr/seed])
    (is (= "Ada Lovelace" (get-in (rf/app-db-value :rf/default)
                                  [:derived :full-name]))
        "the flow wrote the derived full name into app-db on the sync drain")

    ;; Render against the installed db — the sub over the flow's path feeds
    ;; the rendered HTML.
    (let [html (rf/render-to-string [:pages/greeting] {:emit-hash? true})]
      (is (str/includes? html "Hello, Ada Lovelace")
          "the rendered HTML carries the FLOW-AUGMENTED value — the sub read
           the installed flow output under SSR's synchronous drain")
      (is (str/includes? html "data-rf-render-hash=")
          "sanity: the root carries a render hash (real SSR render path)"))))

;; ===========================================================================
;; 4. flow × child-dispatch — each :fx :dispatch child gets its OWN
;;    independent flow eval, not the parent's.
;;
;; A parent event's :fx dispatches a child event. Each event is its own
;; trip through the drain, so each gets its OWN outermost-:after flow eval
;; over its OWN settled db. The flow re-runs for the child when the child's
;; write changes the flow's input — proving the child's eval is independent.
;; ===========================================================================

(deftest each-child-dispatch-gets-its-own-independent-flow-eval
  (testing "an event whose :fx :dispatches a child gives EACH child its OWN
            independent flow eval (over its own settled db), not the
            parent's"
    (let [flow-inputs (atom [])]
      ;; The flow reads :n and records every input value it computes over,
      ;; so we can prove it ran once per event (parent + child), each over
      ;; that event's own db.
      (rf/reg-flow {:id     :tracker
                    :inputs [[:n]]
                    :output (fn [n] (swap! flow-inputs conj n) (* 10 n))
                    :path   [:derived :scaled]})
      ;; The parent writes :n=1 then :fx :dispatches the child.
      (rf/reg-event :parent
                       (fn [{:keys [db]} _]
                         {:db (assoc db :n 1)
                          :fx [[:dispatch [:child]]]}))
      ;; The child writes :n=2 — a DIFFERENT value, so the flow's dirty-
      ;; check recomputes for the child (proving an independent eval).
      (rf/reg-event :child (fn [{:keys [db]} _] {:db (assoc db :n 2)}))

      (reset! *captured* [])
      (rf/dispatch-sync [:parent])

      ;; Two independent flow evals: one for the parent (n=1), one for the
      ;; child (n=2). The child's eval is NOT the parent's — it ran over the
      ;; child's own settled db.
      (is (= [1 2] @flow-inputs)
          "the flow evaluated once per event — n=1 for the parent, n=2 for
           the child — each over that event's OWN settled db")
      (is (= 20 (get-in (rf/app-db-value :rf/default) [:derived :scaled]))
          "the final app-db reflects the CHILD's independent flow eval
           (10 * 2), not the parent's (10 * 1)")
      ;; Trace-level confirmation: two :rf.flow/computed events (one per
      ;; event), each carrying its own input value.
      (let [computes (by-op :rf.flow/computed)]
        (is (= 2 (count computes))
            "exactly two :rf.flow/computed traces — one per dispatched event")
        (is (= [[1] [2]]
               (mapv #(-> % :tags :input-values) computes))
            "each compute observed its own event's input — parent saw [1],
             child saw [2] — independent evals, not a shared one")))))

;; ===========================================================================
;; 5. flow × routing (rf2-qm8m3) — a flow over the [:rf.runtime/routing
;;    :current] slice reads the POST-transition route, and a flow throw on
;;    a transition aborts the WHOLE event (slice unchanged, :on-match
;;    :dispatch fxs skipped).
;;
;; `:rf.route/transitioned` is a normal event-fx: its handler returns
;; `{:db (assoc-in db' [:rf.runtime/routing :current] {...new-route...})
;; :fx [[:dispatch [:on-match]] ...]}`. The flow at the outermost
;; `:after` transforms the pending :db (which already carries the
;; rewritten route slice) BEFORE the single deferred install. So a flow
;; whose :inputs overlap [:rf.runtime/routing :current] sees the SETTLED
;; post-transition slice, never an intermediate or pre-transition value.
;; After install, `:fx` walks — any `[:dispatch [:on-match]]` entries
;; fire against the flow-augmented db.
;;
;; The atomicity contract (`bd remember event-pipeline-atomicity` rule a):
;; ANY pre-install throw, including a flow throw, aborts the event — no
;; install, no db-changed, no :fx. So a flow throw on a transition event
;; means the route slice rewrite does NOT land AND the queued `:on-match`
;; child dispatches in :fx are NEVER walked (they sit in the post-install
;; stage that the flow-throw path skips wholesale).
;; ===========================================================================

;; EP-0001 §535-551 (rf2-4eisfr) — RE-ENABLED. The route slice lives in the
;; runtime-db partition (EP-0001 rf2-vzld77), so this flow's `:inputs` opt
;; into runtime-db via the qualified form
;; `[:rf.db/runtime :rf.runtime/routing :current :route-id]`. The flow transform
;; resolves the qualified input against the pending runtime-db (the
;; post-transition slice rewrite), and the dual-partition TRIGGER fires this
;; flow on the runtime-only `:rf.route/transitioned` event even though app-db
;; did not change (EP-0001 §542-544).
(deftest flow-over-route-slice-reads-settled-post-transition-route
  (testing "a flow whose :inputs overlap [:rf.runtime/routing :current]
            reads the POST-transition route — the slice rewrite is the
            handler's pending :db; the flow at the outermost :after
            transforms that pending value, then a single deferred install
            commits the slice + flow output together"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-route :route/home    {} "/")
    ;; A flow over the route id derives a human label into a plain app-db
    ;; path. Logging the input it observed proves it ran on the SETTLED
    ;; post-transition slice, not the pre-transition one.
    (let [flow-inputs (atom [])]
      (rf/reg-flow {:id     :route/label
                    :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
                    :output (fn [route-id]
                              (swap! flow-inputs conj route-id)
                              (str "you are at " route-id))
                    :path   [:derived :route-label]})

      ;; Land on /home first so there is a known PRE-transition slice the
      ;; flow could potentially observe if it ran on the wrong value.
      (rf/dispatch-sync [:rf.route/transitioned "/"])
      (is (= :route/home (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current :route-id]))
          "precondition: landed on :route/home")
      (is (= [:route/home] @flow-inputs)
          "precondition: flow ran once on the home slice (post-transition)")

      ;; Now transition to /articles/42. The handler writes the new slice
      ;; into pending :db; the flow's :after transforms that pending :db
      ;; reading [:rf.runtime/routing :current :route-id]; both land together at install.
      (reset! *captured* [])
      (reset! flow-inputs [])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/42"])

      ;; The installed slice carries the new route.
      (is (= :route/article (get-in (rf/runtime-db-value :rf/default)
                                    [:rf.runtime/routing :current :route-id]))
          "the slice landed on :route/article")
      (is (= {:id "42"} (get-in (rf/runtime-db-value :rf/default)
                                [:rf.runtime/routing :current :params]))
          ":params landed alongside the route id (same install)")

      ;; The flow output is in app-db AND reflects the POST-transition slice.
      (is (= "you are at :route/article"
             (get-in (rf/app-db-value :rf/default) [:derived :route-label]))
          "the flow output derived from the POST-transition route id rode
           the same install as the slice rewrite")
      (is (= [:route/article] @flow-inputs)
          "the single flow eval for this transition saw the SETTLED
           post-transition route id — not the pre-transition :route/home")

      ;; Trace signature: exactly one :rf.flow/computed for the dispatched
      ;; transition, with the post-transition route id as input.
      (let [computes (by-op :rf.flow/computed)]
        (is (= 1 (count computes))
            "exactly one :rf.flow/computed trace for the transition
             dispatch — no double / missed eval")
        (is (= [:route/article]
               (-> computes first :tags :input-values))
            "the :rf.flow/computed trace's :input-values carries the
             post-transition route id")))))

;; EP-0001 §535-551 (rf2-4eisfr) — RE-ENABLED. The throwing flow reads the
;; route slice via the qualified runtime-db input
;; `[:rf.db/runtime :rf.runtime/routing :current :route-id]`. Step 3 of the ruling:
;; a flow throw aborts BOTH partitions — `:db` AND `:rf.db/runtime` (the slice
;; rewrite) AND `:fx` are all skipped, consistent with the atomic
;; cross-partition commit (`commit-and-flow!` short-circuits on `:rf/flow-error`
;; before `commit-frame-effects!`, so neither partition installs).
(deftest flow-throw-on-route-transition-aborts-event-slice-unchanged-no-on-match-fx
  (testing "a flow throw on a :rf.route/transitioned dispatch aborts the
            WHOLE event — the slice rewrite does NOT land, the route stays
            on the pre-transition value, AND the :on-match :dispatch fxs
            in the handler's :fx are NEVER walked (post-install stage
            skipped per the atomicity contract — rule a)"
    (let [on-match-fired (atom 0)]
      (rf/reg-event :route/load-article
                       (fn [{:keys [db]} _]
                         (swap! on-match-fired inc)
                         {:db (assoc db :article/loaded? true)}))
      ;; :route/article carries an :on-match — :rf.route/transitioned's
      ;; handler will return `[:dispatch [:route/load-article]]` inside :fx
      ;; for a successful transition. The flow throw must skip that :fx.
      (rf/reg-route :route/article
                    {:params   [:map [:id :string]]
                     :on-match [[:route/load-article]]} "/articles/:id")
      (rf/reg-route :route/home {} "/")

      ;; Land on /home cleanly (no throwing flow registered yet).
      (rf/dispatch-sync [:rf.route/transitioned "/"])
      (is (= :route/home (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/routing :current :route-id]))
          "precondition: clean landing on :route/home")
      (is (zero? @on-match-fired)
          "precondition: :route/home has no :on-match — counter still 0")
      (let [baseline-db (rf/app-db-value :rf/default)]

        ;; Register a flow that throws on every eval, then transition to a
        ;; route whose :on-match would dispatch :route/load-article.
        (rf/reg-flow {:id     :route/boom
                      :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
                      :output (fn [_]
                                (throw (ex-info "flow boom on route"
                                                {:why :test})))
                      :path   [:derived :route-doomed]})
        (reset! *captured* [])
        (rf/dispatch-sync [:rf.route/transitioned "/articles/42"])

        ;; The route slice did NOT land — pre-install throw discards the
        ;; entire pending :db (handler's slice rewrite + flow output alike).
        (is (= baseline-db (rf/app-db-value :rf/default))
            "app-db is byte-for-byte the pre-transition value — the slice
             rewrite was rolled in with the flow's pending write and
             discarded wholesale by the flow throw")
        (is (= :route/home (get-in (rf/runtime-db-value :rf/default)
                                   [:rf.runtime/routing :current :route-id]))
            "the route slice stayed on :route/home — the transition's
             slice rewrite did NOT install")

        ;; The :on-match :dispatch in the handler's :fx was NOT walked —
        ;; :fx is the post-install stage that the flow-throw path skips
        ;; wholesale (atomicity contract rule a).
        (is (zero? @on-match-fired)
            ":route/load-article was NOT invoked — the :on-match :dispatch
             sat in the handler's :fx; a flow throw skips :fx entirely")

        ;; Trace signature: :rf.flow/failed fired, but NO :rf.event/db-changed
        ;; for this dispatch — the event aborted before install.
        (is (seq (by-op :rf.flow/failed))
            ":rf.flow/failed fired — the flow eval threw")
        (is (not-any? #(= :rf.event/db-changed %) (ops))
            "NO :rf.event/db-changed in the throw stream — the event
             aborted before install (contrast: a clean transition would
             emit one :rf.event/db-changed for the slice rewrite)")))))

;; ===========================================================================
;; 6. dual-partition TRIGGER (EP-0001 §542-544, rf2-4eisfr) — a RUNTIME-ONLY
;;    event recomputes a runtime-db-reading flow even though app-db is
;;    value-identical.
;;
;; This is the SILENT-regression guard the ruling calls out: the flow
;; dirty-check must key on BOTH partitions, NOT on app-db publication. A pure
;; `:rf.route/transitioned` returns `{:rf.db/runtime …}` and NO `:db` effect —
;; app-db never changes across the transition. A flow whose ONLY changing
;; input is the qualified runtime-db route slice must STILL recompute (a
;; route-reading breadcrumb that just stopped updating would be the
;; regression). The test pins: (a) the flow recomputes on the runtime-only
;; event; (b) it observes the NEW runtime value; (c) it does NOT recompute a
;; second time when nothing in either partition changes (the dirty-check still
;; suppresses an unchanged re-eval — the trigger widens to runtime-db without
;; losing the skip).
;; ===========================================================================

(deftest runtime-only-event-triggers-runtime-db-reading-flow-recompute
  (testing "a runtime-only :rf.route/transitioned event (no :db effect)
            recomputes a flow whose only changing input is a qualified
            [:rf.db/runtime …] route-slice path — the dirty-check keys on
            BOTH partitions (EP-0001 §542-544), so the flow does NOT silently
            stop updating when app-db is value-identical"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-route :route/home    {} "/")
    (let [flow-evals (atom [])]
      ;; The flow's ONLY input is the qualified runtime-db route id. Its
      ;; output writes to a plain app-db path (writes are app-db only).
      (rf/reg-flow {:id     :nav/breadcrumb
                    :inputs [[:rf.db/runtime :rf.runtime/routing :current :route-id]]
                    :output (fn [route-id]
                              (swap! flow-evals conj route-id)
                              (str "at:" route-id))
                    :path   [:nav :breadcrumb]})

      ;; Land on /home — the flow recomputes for the first runtime-only event.
      (rf/dispatch-sync [:rf.route/transitioned "/"])
      (is (= [:route/home] @flow-evals)
          "the flow recomputed on the FIRST runtime-only transition (no :db
           effect was returned by the handler, yet the runtime-db read fired
           the trigger)")
      (is (= "at::route/home" (get-in (rf/app-db-value :rf/default)
                                      [:nav :breadcrumb]))
          "the flow output (derived from the runtime-db route slice) landed
           in app-db")

      ;; Transition to /articles/42 — again a runtime-only event. app-db does
      ;; NOT change across this transition; ONLY the runtime-db route slice
      ;; does. The flow MUST recompute and observe the NEW route id — this is
      ;; the silent-regression guard: if the dirty-check keyed on app-db
      ;; publication alone, the flow would never re-fire.
      (let [app-db-before (rf/app-db-value :rf/default)]
        (reset! flow-evals [])
        (rf/dispatch-sync [:rf.route/transitioned "/articles/42"])
        (is (= [:route/article] @flow-evals)
            "the flow recomputed on the runtime-only transition even though
             the only change was in the runtime-db partition — the trigger
             keys on BOTH partitions (§542-544)")
        (is (= "at::route/article" (get-in (rf/app-db-value :rf/default)
                                           [:nav :breadcrumb]))
            "the recompute observed the NEW runtime-db route id")
        (is (not= app-db-before (rf/app-db-value :rf/default))
            "app-db changed only via the FLOW's output write — the handler
             returned no :db effect; the change is the breadcrumb the flow
             derived from the runtime-only route change"))

      ;; A re-dispatch to the SAME route changes neither partition's route
      ;; slice value — the dirty-check still SKIPS (the trigger widened to
      ;; runtime-db without losing the value-equal skip).
      (reset! flow-evals [])
      (reset! *captured* [])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/42"])
      (is (= [] @flow-evals)
          "a transition to the SAME route does NOT recompute the flow — the
           runtime-db route id is value-equal, so the dirty-check skips
           (widening the trigger to runtime-db preserves the skip)")
      (is (seq (by-op :rf.flow/skip))
          ":rf.flow/skip fired for the value-equal re-transition"))))

;; ===========================================================================
;; 7. binary-syntax mixed inputs (EP-0001 §535-538, rf2-4eisfr) — ONE flow
;;    reading BOTH a bare app-db input AND a qualified [:rf.db/runtime …]
;;    input resolves each against the correct partition.
;;
;; Pins the binary input syntax end-to-end: bare = app-db, [:rf.db/runtime …]
;; = runtime-db, on a single flow. Proves the resolver routes per-input, not
;; per-flow, and that a flow composing both partitions fires when EITHER
;; partition's input changes.
;; ===========================================================================

(deftest flow-composing-app-db-and-runtime-db-inputs-resolves-each-partition
  (testing "a single flow with one bare app-db input and one qualified
            [:rf.db/runtime …] runtime-db input resolves each against its own
            partition, and recomputes when EITHER changes"
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
    (rf/reg-route :route/home    {} "/")
    (rf/reg-event :set-greeting (fn [{:keys [db]} [_ g]] {:db (assoc db :greeting g)}))
    (let [flow-evals (atom [])]
      ;; Bare [:greeting] reads app-db; qualified route id reads runtime-db.
      (rf/reg-flow {:id     :nav/banner
                    :inputs [[:greeting]
                             [:rf.db/runtime :rf.runtime/routing :current :route-id]]
                    :output (fn [greeting route-id]
                              (swap! flow-evals conj [greeting route-id])
                              (str greeting " @ " route-id))
                    :path   [:nav :banner]})

      ;; Seed app-db greeting (app-only event) — flow fires reading both
      ;; partitions; route id is nil until the first transition.
      (rf/dispatch-sync [:set-greeting "Hi"])
      (is (= [["Hi" nil]] @flow-evals)
          "the flow resolved the bare input against app-db (\"Hi\") and the
           qualified input against runtime-db (nil — no route yet)")

      ;; A runtime-only transition — the qualified input changes; the bare
      ;; input is unchanged. The flow recomputes (BOTH-partition trigger).
      (reset! flow-evals [])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/42"])
      (is (= [["Hi" :route/article]] @flow-evals)
          "the runtime-only transition recomputed the flow; the bare app-db
           input kept its value, the qualified runtime-db input took the new
           route id")
      (is (= "Hi @ :route/article" (get-in (rf/app-db-value :rf/default)
                                           [:nav :banner]))
          "the composed output landed in app-db (writes are app-db only)")

      ;; An app-only event — the bare input changes; the qualified input is
      ;; unchanged. The flow recomputes too.
      (reset! flow-evals [])
      (rf/dispatch-sync [:set-greeting "Yo"])
      (is (= [["Yo" :route/article]] @flow-evals)
          "the app-only event recomputed the flow; the bare input took the
           new greeting, the qualified runtime-db input kept the route id"))))

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
      OWN independent flow eval, not the parent's."
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
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (schemas/reset-schema-validator!)
  (reset! request/request-slots {})
  (reset! response/response-slots {})
  (reset! error-listener/pending-error-traces {})
  (reset! head/head-snapshots {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! @li-var {}))
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
  (let [captured (atom [])]
    (binding [*captured* captured]
      (trace/register-listener!
        ::flow-integration-recorder
        (fn [ev] (swap! captured conj ev)))
      (try
        (test-fn)
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
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

;; ===========================================================================
;; 1. machine-macrostep × flow — a multi-microstep macrostep SETTLES, then
;;    exactly ONE flow eval runs on the settled db.
;;
;; A machine whose macrostep chains :always (a guarded auto-transition) AND
;; :raise (a pre-commit re-entry) settles through several microsteps within
;; one dispatched event. The machine writes its final snapshot into
;; [:rf/machines id] as the handler's pending :db. Because the flow runs at
;; the OUTERMOST :after — after the machine handler, transforming the
;; pending :db — the flow sees the SETTLED machine-driven db, and runs
;; exactly once for the dispatch (no eval-per-microstep, no missed eval).
;; ===========================================================================

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
                    :inputs [[:rf/machines :gauge/flow :data :ticks]]
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
      (is (= "ticks=2" (get-in (rf/get-frame-db :rf/default)
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
    (rf/reg-event-db :seed (fn [_ _] {:n 1 :derived {:doubled 0}}))
    ;; The handler writes :n; the flow reads :n and writes a value that the
    ;; app-db schema will REJECT (negative when :n is negative).
    (rf/reg-event-db :set-n (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :doubler
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    ;; Seed a conforming baseline (n=1 → doubled=2, conforms).
    (rf/dispatch-sync [:seed])
    (is (= 2 (get-in (rf/get-frame-db :rf/default) [:derived :doubled]))
        "baseline: flow wrote a conforming value")
    (let [baseline-db (rf/get-frame-db :rf/default)]
      ;; Now drive :n negative → flow writes -6 → app-db schema rejects it.
      (reset! *captured* [])
      (rf/dispatch-sync [:set-n -3])

      ;; The WHOLE db is wound back — neither the handler's :n write NOR the
      ;; flow's bad output stand.
      (is (= baseline-db (rf/get-frame-db :rf/default))
          "the whole db was wound back to the pre-handler value — the flow
           write rode the same deferred install and was rolled back with it")
      (is (= 1 (get (rf/get-frame-db :rf/default) :n))
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
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    ;; The handler writes :n; the flow reads :n and THROWS.
    (rf/reg-event-db :bump (fn [db _] (update db :n inc)))
    ;; Seed a clean baseline FIRST, then register the throwing flow — the
    ;; flow throws on every eval, so registering it after the seed keeps the
    ;; baseline (and isolates the throw to the :bump drain we observe).
    (rf/dispatch-sync [:seed])
    (is (= {:n 0} (rf/get-frame-db :rf/default))
        "baseline seeded before the throwing flow is registered")
    (rf/reg-flow {:id     :boom
                  :inputs [[:n]]
                  :output (fn [_] (throw (ex-info "flow boom" {:why :test})))
                  :path   [:derived :doomed]})
    (reset! *captured* [])
    (rf/dispatch-sync [:bump])

    ;; The handler's :db did NOT land — the flow throw aborted the event.
    (is (= {:n 0} (rf/get-frame-db :rf/default))
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
    (rf/reg-event-db :ssr/seed (fn [_ _] {:user {:first "Ada" :last "Lovelace"}}))
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
    (is (= "Ada Lovelace" (get-in (rf/get-frame-db :rf/default)
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
      (rf/reg-event-fx :parent
                       (fn [{:keys [db]} _]
                         {:db (assoc db :n 1)
                          :fx [[:dispatch [:child]]]}))
      ;; The child writes :n=2 — a DIFFERENT value, so the flow's dirty-
      ;; check recomputes for the child (proving an independent eval).
      (rf/reg-event-db :child (fn [db _] (assoc db :n 2)))

      (reset! *captured* [])
      (rf/dispatch-sync [:parent])

      ;; Two independent flow evals: one for the parent (n=1), one for the
      ;; child (n=2). The child's eval is NOT the parent's — it ran over the
      ;; child's own settled db.
      (is (= [1 2] @flow-inputs)
          "the flow evaluated once per event — n=1 for the parent, n=2 for
           the child — each over that event's OWN settled db")
      (is (= 20 (get-in (rf/get-frame-db :rf/default) [:derived :scaled]))
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

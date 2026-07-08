(ns re-frame.epoch-test
  "Tool-Pair §Time-travel — epoch recording, query, listener, restore.

  Bead rf2-shjf coverage:

    1. Recording — each DEQUEUED EVENT commits its own record; a
       multi-event drain (an :fx-dispatched child) commits one record
       PER EVENT, not one per drain (per rf2-nj6p7 / Spec 002 §Drain
       versus event).
    2. Per-frame isolation — two frames, each gets its own ring.
    3. Ring depth cap — dispatch >depth events; oldest get evicted.
    4. Configurable depth — `(rf/configure! {:epoch-history {:depth N}})`.
    5. Listener — `register-epoch-listener!` fires per drain-settle with the
       assembled record; same-key replaces; remove unhooks; exception
       isolation.
    6. Restore happy path — `restore-epoch!` rewinds app-db.
    7. The seven documented failure modes (Tool-Pair §Time-travel
       restore-failure-modes table) — six fire under `:rf.epoch/*`,
       plus `:rf.error/no-such-handler` (kind `:frame`) for an
       unknown frame-id; each leaves app-db unchanged.
    8. Sub-runs / renders / effects projection from the trace stream.

  Per-cascade / per-mount POST-SETTLE attribution (rf2-qs6dl render lag,
  rf2-wi900 sub-run lag, rf2-vh1k3 mount-render burst) lives in the dedicated
  standing suite `re-frame.epoch-attribution-test` — those cases simulate the
  React-commit / React-deref timing the synchronous JVM cascade can't
  reproduce. Consolidated there from this file (rf2-yp81r) so the whole
  attribution surface is one cohesive, invariant-keyed grep target."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            ;; Side-effect require: flows publishes the `:flows/reg-flow`
            ;; late-bind hook at ns-load. Several restore-* tests register
            ;; flows via `rf/reg-flow` in their bodies; without this load
            ;; they throw `:rf.error/flows-artefact-missing`. Loading it
            ;; HERE means the capture/restore fixture's ns-load registrar
            ;; snapshot includes the flows surface. Alias unused — the
            ;; fixture no longer touches the private flows atoms (the
            ;; reset-hook table owns flows reset).
            [re-frame.flows]
            ;; rf2-bh56rc: `with-redefs`'d in the :committed-at causal-time
            ;; tests to a sentinel clock, proving the durable :committed-at
            ;; comes from the committing token's :time-ms, not an ambient
            ;; clock read at assembly time. Both `now-ms` (elapsed clock) and
            ;; `epoch-now-ms` (wall-clock, the surface the router stamps fresh
            ;; tokens from — rf2-n1rh0f / EP-0010 §Time) are pinned.
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            ;; rf2-eig68k — require the schemas FAÇADE, not the `.malli`
            ;; validator adapter. The dependency runs ONE way:
            ;; `re-frame.schemas` `:require`s `re-frame.schemas.malli`
            ;; (publishing the Malli validate/explain hooks) — the reverse
            ;; is NOT true. `re-frame.schemas.malli` only publishes
            ;; `:schemas/malli-validate` / `:schemas/malli-explain`; it does
            ;; NOT publish the `:schemas/reg-app-schema` /
            ;; `:schemas/app-schemas-digest` / `:schemas/validate-with-
            ;; registered-fn` registrar+digest hooks (those `set-fn!` forms
            ;; live in `re-frame.schemas`). Requiring the façade installs
            ;; BOTH layers: the registrar hooks the `reg-app-schema` tests
            ;; need AND the Malli validate hook the runtime-db
            ;; schema-mismatch precondition (rf2-szbzei) drives a real
            ;; failure through. Side-effect require — alias unused.
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.assembly :as assembly]
            [re-frame.epoch.capture :as capture]
            [re-frame.epoch.listeners :as epoch.listeners]
            ;; `state` is used in test BODIES for the deep private-impl
            ;; unit tests (the `@#'state/value-changed-epoch-for` scan
            ;; against a hand-built `@#'state/histories` ring, and the
            ;; shipped-default accessor probe) — NOT for fixture config
            ;; reset, which now flows through the public `configure!`
            ;; boundary + the `:epoch/reset-config!` reset hook.
            [re-frame.epoch.state :as state]
            [re-frame.epoch.tool-pair :as tool-pair]
            ;; Side-effect require: routing publishes its `:routing/*`
            ;; late-bind hooks (incl. the `rf/reg-route` registrar kind)
            ;; at ns-load. Several tests call `rf/reg-route` in their
            ;; bodies; loading routing HERE (rather than reloading it in
            ;; the fixture) means the capture/restore fixture's ns-load
            ;; registrar snapshot includes routing's registrations and
            ;; restores them around each test (rf2-yw1w1u).
            [re-frame.routing]
            ;; rf2-v6z0: machines is a separate artefact whose late-bind
            ;; hook publishes `rf/reg-machine` only when the namespace is
            ;; loaded. Several restore-* tests register machines via
            ;; `rf/reg-machine` in their bodies; without this require they
            ;; throw `:rf.error/machines-artefact-missing`. Side-effect
            ;; require — the namespace alias is unused.
            [re-frame.machines]
            ;; rf2-u5kmf8 — read the machine `:after` host-clock timer table
            ;; directly to prove an end-to-end restore releases the restored
            ;; frame's armed timer handles (the orphaned async host work).
            [re-frame.machines.timer :as machine-timer]))

;; ---- fixtures --------------------------------------------------------------
;;
;; rf2-yw1w1u — the canonical capture/restore fixture. It snapshots the
;; registrar at ns-load and restores around each test (so the routing /
;; schemas / machines registrations this ns's `:require` chain brought
;; live survive cross-ns runs without a `clear-all!` + reload dance), and
;; fires the epoch reset-hook table (`:epoch/clear-history!`,
;; `:epoch/clear-epoch-listeners!`, `:epoch/reset-config!`) so each test
;; starts from a clean epoch slate with config reset to the shipped
;; default. The `:init-fn` re-applies the suite's non-default
;; `:trace-events-keep 5` (rf2-iegsz — a keep<depth OVERRIDE so the
;; elision path is reachable with a handful of dispatches; NOT the
;; shipped default of 50 = :depth, see
;; `re-frame.epoch.state/default-trace-events-keep`; Mike pair-debug
;; 2026-05-27) through the public `configure!` boundary — no test ns
;; reaches into the private `state/config` var for fixture reset. The
;; `:init-fn` runs AFTER the post-dispose reset hooks, so the override
;; lands on top of the freshly-reset default.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- helpers ---------------------------------------------------------------

(defn- record-trace! []
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::recorder (fn [ev] (swap! recorded conj ev)))
    recorded))

(defn- has-error-op? [events op]
  (some (fn [ev] (and (= :error (:op-type ev))
                      (= op     (:operation ev))))
        events))

(defn- epoch-source-files
  "Every epoch-artefact `.cljc` source file, discovered off the classpath
  (the :local/root `src` dir) so a NEW file with a fresh emit site is
  scanned automatically by `skip-ops-catalogue-pins-every-rf-epoch-op`.
  The epoch package directory is located via a known file resource's
  parent; the `re-frame.epoch` facade lives one level up."
  []
  (let [pkg-dir (-> (io/resource "re_frame/epoch/capture.cljc")
                    io/file
                    (.getParentFile))
        facade  (io/file (io/resource "re_frame/epoch.cljc"))]
    (cons facade
          (filter #(str/ends-with? (.getName ^java.io.File %) ".cljc")
                  (.listFiles pkg-dir)))))

;; ---- recording -------------------------------------------------------------

(deftest record-on-drain-settle
  (testing "every drain-settle commits exactly one :rf/epoch-record"
    (rf/reg-frame :test/main {:doc "epoch test frame"})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 3 (count history)) "one record per drain-settle")
      (is (every? :epoch-id history))
      (is (every? :committed-at history))
      (is (= [[:seed] [:inc] [:inc]]
             (mapv :trigger-event history))
          "trigger-event preserved per record"))))

(deftest record-shape-canonical
  (testing "an :rf/epoch-record carries the canonical shape"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [r (last (rf/epoch-history :test/main))]
      (is (= :test/main (:frame r)))
      (is (= :inc       (:event-id r)))
      (is (= [:inc]     (:trigger-event r)))
      (is (= {:n 0}     (:db-before r))
          "db-before is the pre-cascade snapshot")
      (is (= {:n 1}     (:db-after r))
          "db-after is the post-settle snapshot")
      (is (vector? (:trace-events r)))
      (is (vector? (:sub-runs r)))
      (is (vector? (:renders r)))
      (is (vector? (:effects r)))
      ;; rf2-rly4a — the settling cascade's :dispatch-id is pinned as a
      ;; first-class slot (the stable epoch-id ↔ cascade link Xray's
      ;; focus correlation reads). It must equal the :dispatch-id tag the
      ;; cascade's own trace events carry.
      (is (some? (:dispatch-id r))
          "record carries the pinned :dispatch-id slot")
      (is (= (:dispatch-id r)
             (some #(get-in % [:tags :rf.trace/dispatch-id]) (:trace-events r)))
          ":dispatch-id slot equals the cascade's trace :rf.trace/dispatch-id tag"))))

;; ---- rf2-1xdotm: the epoch record carries the POST-GENERATION :rf.cofx -----
;;
;; Per EP-0017 §Recordable coeffects + Tool-Pair §Replay-mint-policy: replay
;; re-drives a recorded event through the application's own handlers by
;; dispatching it with the RECORDED `:rf.cofx` (so the handler folds the exact
;; facts the original run consumed) plus `:rf.cofx/mint-policy :strict` (so
;; recordable facts are re-presented and NEVER re-minted). That contract is
;; only realisable if the epoch record exposes the complete post-generation
;; flat `:rf.cofx` replay token — the cofx map AS IT WAS after generators ran
;; during the original dispatch, including both the framework `:rf/time-ms`
;; provided fact AND every generator-backed recordable fact minted at
;; processing-start (re-frame.cofx/deliver-declared-cofx writes the generated
;; value back into the in-flight `:rf.cofx` record).
;;
;; The assembled `:rf/epoch-record` carries `:trigger-event` / `:event-id` /
;; `:dispatch-id` AND a first-class `:rf.cofx` replay token (rf2-1xdotm), so a
;; tool replaying from the record can supply the exact facts the original run
;; consumed. Without that token a strict replay would miss a generated fact
;; and fail with `:rf.error/missing-required-cofx`. These tests pin the
;; property end-to-end with a generator-backed recordable cofx — dispatch once
;; with no supplied fact (generator mints it), assert the record carries the
;; post-generation `:rf.cofx`, then replay the recorded event under `:strict`
;; from that token and assert NO generator call occurs and the handler
;; receives the recorded value.

(deftest epoch-record-carries-post-generation-rf-cofx
  (testing "rf2-1xdotm — the assembled :rf/epoch-record exposes the complete
            post-generation flat :rf.cofx replay token: the framework
            :rf/time-ms provided fact AND the generator-backed recordable fact
            minted during the ORIGINAL dispatch (the value as it was AFTER the
            generator ran, written back into the in-flight :rf.cofx)"
    (rf/reg-frame :test/main {})
    ;; A generator-backed recordable cofx (recordable, NOT provided, with a
    ;; value-returning supplier). Declared-absent on the token + :live policy
    ;; ⇒ the generator runs at processing-start and the produced value is
    ;; written back into the in-flight :rf.cofx record (EP-0017 §5 step 3).
    (let [gen-calls (atom 0)]
      (rf/reg-cofx :test/minted
        {:recordable? true
         :doc "A generator-backed recordable fact — minted on :live, recorded."}
        (fn [] (swap! gen-calls inc) {:token (str "gen-" @gen-calls)}))
      (rf/reg-event :mint
        {:rf.cofx/requires [:rf/time-ms :test/minted]}
        (fn [{:keys [db] minted :test/minted t :rf/time-ms} _]
          {:db (assoc db :recorded-token (:token minted) :recorded-at t)}))

      ;; Dispatch ONCE with no :test/minted supplied (only the framework time
      ;; fact). Default router mint policy is :live, so the generator mints the
      ;; fact and the runtime records it on the post-generation token.
      (rf/dispatch-sync [:mint] {:frame   :test/main
                                 :rf.cofx {:rf/time-ms 1781078400123}})
      (is (= 1 @gen-calls) "the generator ran exactly once during the record dispatch")

      (let [r          (last (rf/epoch-history :test/main))
            recorded   (:rf.cofx r)]
        ;; The record carries the complete post-generation flat :rf.cofx token.
        (is (map? recorded)
            "the epoch record carries a first-class :rf.cofx replay token")
        (is (= 1781078400123 (:rf/time-ms recorded))
            ":rf.cofx carries the framework :rf/time-ms provided fact")
        (is (= {:token "gen-1"} (:test/minted recorded))
            ":rf.cofx carries the GENERATED recordable fact — the value as it
             was AFTER the generator ran during the original dispatch (the
             post-generation token), not a pre-generation / absent envelope")

        ;; ---- REPLAY under :strict from the recorded token --------------------
        ;; Re-drive the RECORDED event with the recorded :rf.cofx and
        ;; :rf.cofx/mint-policy :strict (the Tool-Pair replay gesture). The
        ;; recorded fact is PRESENT on the supplied token, so it is delivered
        ;; verbatim and the generator does NOT run again (strict ⇒ no host read,
        ;; no re-mint). The replay reproduces the recorded run faithfully.
        (reset! gen-calls 0)
        (rf/reg-frame :test/replay {})
        (rf/dispatch-sync (:trigger-event r)
                          {:frame                :test/replay
                           :rf.cofx              recorded
                           :rf.cofx/mint-policy  :strict})
        (is (= 0 @gen-calls)
            "strict replay supplied the recorded fact verbatim — the generator
             did NOT re-mint (the missing-required / re-mint failure the
             post-generation :rf.cofx record kills)")
        (is (= "gen-1" (:recorded-token (rf/app-db-value :test/replay)))
            "the replay handler received the RECORDED generated value, not a
             freshly-minted one")
        (is (= 1781078400123 (:recorded-at (rf/app-db-value :test/replay)))
            "the replay handler received the recorded :rf/time-ms")))))

;; ---- rf2-bh56rc: :committed-at is the committing token's causal time -------
;;
;; Per EP-0010 §Time (epoch record causal time) + Spec 002 §The World-Input
;; Rule: the durable :committed-at fact MUST come from the committing causal
;; token's `:rf.cofx` :time-ms (read ONCE at the causal boundary,
;; envelope construction, from `interop/epoch-now-ms` per rf2-n1rh0f), NOT an
;; ambient clock read at epoch assembly time. These tests pin that conversion:
;; they stub BOTH host clocks (`interop/now-ms` + `interop/epoch-now-ms`) to a
;; sentinel so a regression that re-reads the ambient clock at assembly would
;; stamp the sentinel and fail loudly. The fresh-child test additionally
;; relies on the `epoch-now-ms` pin to script the freshly-stamped token time.

(deftest committed-at-comes-from-supplied-token-time-ms
  (testing "the durable :committed-at on a clean settle is the committing
            token's `:rf.cofx` :time-ms — NOT an ambient now-ms
            read at assembly time (rf2-bh56rc / EP-0010 §Time)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (let [token-time 1781078400123      ; the supplied causal token time
          clock-time 9999999999999]     ; the (wrong) ambient clock sentinel
      ;; Stub BOTH host clocks (the elapsed `interop/now-ms` AND the
      ;; wall-clock `interop/epoch-now-ms` the router stamps fresh tokens
      ;; from — rf2-n1rh0f) to a value NOTHING legitimate should stamp into
      ;; the record. The router preserves a caller-supplied :time-ms (it only
      ;; fills :time-ms when absent), so the token time below rides through;
      ;; pinning both clocks makes a regression that re-reads either ambient
      ;; surface at assembly time stamp the sentinel and fail loudly.
      (with-redefs [interop/now-ms       (constantly clock-time)
                    interop/epoch-now-ms (constantly clock-time)]
        (rf/dispatch-sync [:seed] {:frame            :test/main
                                   :rf.cofx {:rf/time-ms token-time}}))
      (let [r (last (rf/epoch-history :test/main))]
        (is (= token-time (:committed-at r))
            ":committed-at is the supplied token :time-ms — replayable")
        (is (not= clock-time (:committed-at r))
            ":committed-at is NOT the ambient host clock — assembly performs
             no clock read of its own")))))

(deftest committed-at-replay-stable-across-wall-clock-drift
  (testing "replaying the same event with the same supplied :time-ms yields
            equal :committed-at even as the wall clock advances — the
            replay-stability EP-0010 §Time guarantees (rf2-bh56rc)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (let [token-time 1781078400123]
      ;; First commit under one wall-clock, second under a DIFFERENT one —
      ;; both supply the SAME causal token :time-ms (the replay scenario).
      ;; Pin BOTH clock surfaces (elapsed `now-ms` + wall-clock
      ;; `epoch-now-ms`, rf2-n1rh0f) so neither ambient read can leak into
      ;; :committed-at across the drift.
      (with-redefs [interop/now-ms       (constantly 100)
                    interop/epoch-now-ms (constantly 100)]
        (rf/dispatch-sync [:seed] {:frame            :test/main
                                   :rf.cofx {:rf/time-ms token-time}}))
      (with-redefs [interop/now-ms       (constantly 8888888888888)
                    interop/epoch-now-ms (constantly 8888888888888)]
        (rf/dispatch-sync [:inc]  {:frame            :test/main
                                   :rf.cofx {:rf/time-ms token-time}}))
      (let [history (rf/epoch-history :test/main)]
        (is (= 2 (count history)))
        (is (= [token-time token-time]
               (mapv :committed-at history))
            "both records carry the supplied token time — wall-clock drift
             between the two commits did not leak into :committed-at")))))

(deftest committed-at-each-child-event-reads-its-own-token
  (testing "in a multi-event drain, each dequeued event's :committed-at is
            ITS OWN token's :time-ms — an :fx-dispatched child gets a fresh
            token (the router does NOT inherit the parent's :time-ms), so the
            child's :committed-at is the freshly-stamped clock value, while
            the parent's is its supplied token time (rf2-bh56rc / EP-0010
            §Dispatch Envelope Stamping — children are distinct causal tokens)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:order []}}))
    (rf/reg-event :parent
      (fn [{:keys [db]} _]
        {:db (update db :order conj :parent)
         :fx [[:dispatch [:child]]]}))
    (rf/reg-event :child (fn [{:keys [db]} _] {:db (update db :order conj :child)}))
    (let [parent-time 1781078400123
          ;; The child has no supplied token, so the router stamps its
          ;; :time-ms fresh at the causal boundary (envelope construction) from
          ;; `interop/epoch-now-ms` — the wall-clock-epoch surface for durable
          ;; causal time (rf2-n1rh0f / EP-0010 §Time), NOT `interop/now-ms`
          ;; (which is the elapsed-measurement clock: `performance.now()` on
          ;; CLJS). Pinned to this sentinel by the redef below.
          child-clock 5550000000000]
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (with-redefs [interop/epoch-now-ms (constantly child-clock)]
        (rf/dispatch-sync [:parent] {:frame            :test/main
                                     :rf.cofx {:rf/time-ms parent-time}}))
      (let [history    (rf/epoch-history :test/main)
            by-event   (into {} (map (juxt :event-id :committed-at)) history)]
        (is (= parent-time (get by-event :parent))
            "the parent's :committed-at is its supplied token time")
        (is (= child-clock (get by-event :child))
            "the :fx-dispatched child reads ITS OWN fresh token (stamped from
             now-ms here) — NOT inherited from the parent, NOT a separate
             assembly-time clock read")))))

(deftest record-multi-event-cascade
  (testing "per rf2-nj6p7 (Spec 002 §Drain versus event): each dequeued
            event in a multi-event drain commits its OWN epoch — an
            :fx [[:dispatch …]] child is a separate dequeued event, so it
            yields a separate record, NOT folded into the parent's epoch"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:order []}}))
    (rf/reg-event :outer
      (fn [{:keys [db]} _]
        {:db (update db :order conj :outer)
         :fx [[:dispatch [:inner-1]]
              [:dispatch [:inner-2]]]}))
    (rf/reg-event :inner-1 (fn [{:keys [db]} _] {:db (update db :order conj :inner-1)}))
    (rf/reg-event :inner-2 (fn [{:keys [db]} _] {:db (update db :order conj :inner-2)}))

    (rf/dispatch-sync [:seed]  {:frame :test/main})
    ;; ONE drain: :outer runs, then its two :fx-dispatched children
    ;; (:inner-1, :inner-2) drain in the same turn — but each is its own
    ;; dequeued event = its own epoch.
    (rf/dispatch-sync [:outer] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      ;; Four dequeued events: :seed (own drain), then :outer + :inner-1 +
      ;; :inner-2 (one drain, three epochs).
      (is (= 4 (count history))
          "one epoch per dequeued event — the :fx-dispatched children are
           NOT folded into :outer's epoch")
      (is (= [:seed :outer :inner-1 :inner-2]
             (mapv :event-id history))
          "epochs land in dequeue order, oldest-first")
      ;; Each event's db-before / db-after chains from the previous —
      ;; per-event run-to-completion snapshots.
      (is (= [{:order []}
              {:order [:outer]}
              {:order [:outer :inner-1]}]
             (mapv :db-before (rest history)))
          ":db-before of :outer / :inner-1 / :inner-2 chains per event")
      (is (= [{:order [:outer]}
              {:order [:outer :inner-1]}
              {:order [:outer :inner-1 :inner-2]}]
             (mapv :db-after (rest history)))
          ":db-after of :outer / :inner-1 / :inner-2 chains per event")
      ;; Distinct correlation: each epoch has its own :epoch-id, and the
      ;; per-event :rf.trace/dispatch-id rides the trace stream distinctly. Per
      ;; rf2-nj6p7 + Spec 009 §Dispatch correlation, each epoch's
      ;; :trace-events carry EXACTLY ONE :rf.trace/dispatch-id (one dispatch-id =
      ;; one epoch) — a child's :rf.event/dispatched marker (fired during the
      ;; parent's do-fx) rides the CHILD's epoch, not the parent's.
      (is (apply distinct? (mapv :epoch-id history))
          "every dequeued event gets a distinct :epoch-id")
      (let [dispatch-ids-of
            (fn [r] (->> (:trace-events r)
                         (keep #(-> % :tags :rf.trace/dispatch-id))
                         distinct))
            outer-dids  (dispatch-ids-of (nth history 1))
            inner1-dids (dispatch-ids-of (nth history 2))]
        (is (= 1 (count outer-dids))
            ":outer epoch's traces carry exactly ONE :rf.trace/dispatch-id — the
             child's :rf.event/dispatched marker does NOT leak in")
        (is (= 1 (count inner1-dids))
            ":inner-1 epoch's traces carry exactly ONE :rf.trace/dispatch-id")
        (is (not= (first outer-dids) (first inner1-dids))
            "parent and :fx-dispatched child have DISTINCT :dispatch-ids
             — the child is a separate dequeued event / epoch")))))

(deftest initial-events-event-is-its-own-epoch
  (testing "per rf2-nj6p7 (Spec 002 §Drain versus event): the frame-creation
            :initial-events event is itself a dequeued event, so it commits its
            OWN epoch — distinct from any later user dispatch's epoch"
    (rf/reg-event :app/init (fn [{:keys [db]} _] {:db {:booted true :n 0}}))
    (rf/reg-event :inc      (fn [{:keys [db]} _] {:db (update db :n inc)}))
    ;; reg-frame dispatch-syncs the :initial-events event at registration.
    (rf/reg-frame :test/main {:initial-events [[:app/init]]})

    (let [after-create (rf/epoch-history :test/main)]
      (is (= 1 (count after-create))
          "the :initial-events cascade settled its own epoch at reg-frame time")
      (let [r (first after-create)]
        (is (= :app/init (:event-id r))
            "the :initial-events event is the trigger of its own epoch")
        (is (= {} (:db-before r)))
        (is (= {:booted true :n 0} (:db-after r))
            ":initial-events' epoch carries its own db-before / db-after pair")))

    (rf/dispatch-sync [:inc] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 2 (count history))
          ":initial-events epoch (pos 0) + the user :inc epoch (pos 1)")
      (is (= [:app/init :inc] (mapv :event-id history))
          ":initial-events and the user dispatch are SEPARATE epochs")
      (is (apply distinct? (mapv :epoch-id history))
          "each has its own :epoch-id"))))

;; ---- machine macrostep stays ONE epoch (rf2-nj6p7) ------------------------

;; EP-0001 (rf2-vzld77) re-enabled by bead 7 (rf2-3aizt1). The epoch record now
;; captures the whole frame-state (`:frame-state-before/-after`, decision #2);
;; the machine snapshot lives in the runtime-db partition at
;; `[:rf.db/runtime :rf.runtime/machines :snapshots …]`. This asserts the
;; macrostep's terminal state is captured there.
(deftest machine-raise-macrostep-is-one-epoch
  (testing "per rf2-nj6p7 + Spec 005 §macrostep: a machine's :raise sub-events
            are in-memory microsteps inside a SINGLE macrostep — they ride
            the TRIGGERING event's epoch and do NOT allocate new epochs.
            Only separately-dequeued events get their own epoch."
    (rf/reg-frame :test/main {})
    ;; A 2-deep :raise chain: one [:e1] dispatch drives three transitions
    ;; (s0 → s1 → s2 → s3) in one macrostep via two pre-commit :raises.
    (rf/reg-machine :mac/chain
      {:initial :s0
       :actions {:a1 (fn [_] {:fx [[:raise [:e2]]]})
                 :a2 (fn [_] {:fx [[:raise [:e3]]]})
                 :a3 (fn [_] {})}
       :states  {:s0 {:on {:e1 {:target :s1 :action :a1}}}
                 :s1 {:on {:e2 {:target :s2 :action :a2}}}
                 :s2 {:on {:e3 {:target :s3 :action :a3}}}
                 :s3 {}}})

    (rf/dispatch-sync [:mac/chain [:e1]] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      ;; ONE dequeued event ([:mac/chain [:e1]]) → ONE epoch, even though
      ;; the macrostep ran three transitions via two :raises.
      (is (= 1 (count history))
          "the whole :raise chain rides ONE epoch — :raises are microsteps,
           not separate dequeued events")
      (let [r (first history)]
        (is (= :mac/chain (:event-id r))
            "the triggering machine event is the epoch's trigger")
        (is (= :s3 (:state (get-in (:frame-state-after r)
                                   [:rf.db/runtime :rf.runtime/machines :snapshots :mac/chain])))
            "the macrostep reached the terminal state — all three
             transitions committed inside this one epoch (runtime-db partition)")
        ;; Every trace in the epoch rides the SAME :rf.trace/dispatch-id — the
        ;; :raises did NOT mint a new correlation id (Spec 009).
        (let [dispatch-ids (->> (:trace-events r)
                                (keep #(-> % :tags :rf.trace/dispatch-id))
                                distinct)]
          (is (= 1 (count dispatch-ids))
              "the macrostep's emits (incl. raised transitions) all carry
               the triggering event's single :rf.trace/dispatch-id"))))))

;; ---- per-frame isolation ---------------------------------------------------

(deftest per-frame-isolation
  (testing "each frame has its own epoch ring; cascades don't co-mingle"
    (rf/reg-frame :frame/a {})
    (rf/reg-frame :frame/b {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :frame/a})
    (rf/dispatch-sync [:inc]  {:frame :frame/a})

    (rf/dispatch-sync [:seed] {:frame :frame/b})
    (rf/dispatch-sync [:inc]  {:frame :frame/b})
    (rf/dispatch-sync [:inc]  {:frame :frame/b})

    (is (= 2 (count (rf/epoch-history :frame/a))))
    (is (= 3 (count (rf/epoch-history :frame/b))))

    (is (every? #(= :frame/a (:frame %)) (rf/epoch-history :frame/a)))
    (is (every? #(= :frame/b (:frame %)) (rf/epoch-history :frame/b)))))

;; ---- ring depth ------------------------------------------------------------

(deftest ring-depth-evicts-oldest
  (testing "when the ring fills, oldest records are evicted FIFO"
    (rf/configure! {:epoch-history {:depth 3}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (dotimes [_ 5] (rf/dispatch-sync [:inc] {:frame :test/main}))

    (let [history (rf/epoch-history :test/main)
          dbs     (mapv :db-after history)]
      (is (= 3 (count history)) "ring depth caps history at 3")
      (is (= [{:n 3} {:n 4} {:n 5}] dbs)
          "the three most-recent are kept; oldest evicted"))))

(deftest ring-cap-materialises-and-releases-evicted-records
  ;; rf2-rkbil correctness review: the ring cap MUST materialise the
  ;; retained window into a fresh PersistentVector. A bare
  ;; `(subvec history+ ...)` view does NOT release the evicted records —
  ;; `SubVector.cons` keeps appending to the same growing underlying
  ;; vector and `subvec` of a `SubVector` re-wraps that same backing, so
  ;; the depth-d view's backing vector accretes EVERY record ever
  ;; appended (each with its full :db-before / :db-after / :trace-events
  ;; payload) even though `epoch-history` correctly returns only d
  ;; records — an unbounded heap leak that defeats the bounded-ring
  ;; contract. This pins that the retained window is a concrete vector
  ;; whose backing storage is bounded by the configured depth, so
  ;; evicted records become GC-eligible.
  (testing "ring cap releases evicted records (no SubVector backing-vector leak)"
    (let [depth 3]
      (rf/configure! {:epoch-history {:depth depth}})
      (rf/reg-frame :test/main {})
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
      (rf/reg-event :inc  (fn [{:keys [db]} [_ i]] {:db (assoc db :n i)}))

      (rf/dispatch-sync [:seed] {:frame :test/main})
      ;; Drive far more events than the depth so the cap fires many times.
      (dotimes [i 200] (rf/dispatch-sync [:inc i] {:frame :test/main}))

      (let [history (rf/epoch-history :test/main)]
        (is (= depth (count history))
            "history is capped at the configured depth")
        ;; The retained vector must be a concrete PersistentVector, NOT a
        ;; SubVector view. A SubVector here would mean the backing vector
        ;; still references all 201 evicted-and-live records.
        (is (not (instance? clojure.lang.APersistentVector$SubVector history))
            (str "history must be a materialised PersistentVector, not a "
                 "SubVector view that retains the evicted records' backing "
                 "storage; got " (class history)))
        ;; Belt-and-braces: if a future refactor reintroduces a SubVector,
        ;; assert its backing vector is bounded by the depth rather than
        ;; the full append count (the leak signature).
        (when (instance? clojure.lang.APersistentVector$SubVector history)
          (let [f (doto (.getDeclaredField clojure.lang.APersistentVector$SubVector "v")
                    (.setAccessible true))]
            (is (<= (count (.get f history)) depth)
                "SubVector backing vector must not retain evicted records")))))))

(deftest depth-zero-disables-recording
  (testing "depth 0 disables ring recording"
    (rf/configure! {:epoch-history {:depth 0}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))

    (rf/dispatch-sync [:seed] {:frame :test/main})

    (is (= [] (rf/epoch-history :test/main)))))

;; ---- listener --------------------------------------------------------------

(deftest listener-fires-per-drain-settle
  (testing "register-epoch-listener! fires once per drain-settle with the assembled record"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [seen (atom [])]
      (rf/register-listener! :epoch ::watcher (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})

      (is (= 3 (count @seen)))
      (is (= [:seed :inc :inc]
             (mapv :event-id @seen)))
      (is (every? #(contains? % :db-after) @seen))
      (is (every? #(contains? % :sub-runs) @seen))
      (is (every? #(contains? % :renders) @seen))
      (is (every? #(contains? % :effects) @seen))

      (rf/unregister-listener! :epoch ::watcher))))

(deftest listener-same-key-replaces
  (testing "register-epoch-listener! under the same key replaces the prior listener"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))

    (let [a (atom 0)
          b (atom 0)]
      (rf/register-listener! :epoch ::w (fn [_] (swap! a inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (is (= 1 @a))

      (rf/register-listener! :epoch ::w (fn [_] (swap! b inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 1 @a) "the original listener no longer fires after re-register under the same key")
      (is (= 1 @b) "the replacement listener fires"))))

;; ---- rf2-s60jx: multi-listener observed-frames + re-register dissoc ------
;;
;; `notify-listeners!` invokes `record-observation!` once per listener per
;; drain-settle, populating `observed-frames-by-cb[cb-id]` with each
;; frame the cb has seen. Two contracts that weren't pinned:
;;
;;   1. Two listeners both observing the same frame on the same drain
;;      land independent entries in `observed-frames-by-cb` — each cb's
;;      set contains the frame-id.
;;   2. Re-registering a listener under the same id (via
;;      `register-epoch-listener!`) resets BOTH the listener entry AND the
;;      observed-frames entry — so the new callback's silencing trace
;;      fires fresh against frames it observes. The `dissoc` at
;;      epoch.cljc:158 is the non-obvious half of the contract; a
;;      future regression that drops it would leave stale observed-
;;      frames bookkeeping under the new fn's id.

(deftest multi-listener-observed-frames-and-re-register-dissoc
  (testing "two listeners both observing the same frame populate
            independent observed-frames-by-cb entries; re-registering
            under the same id resets BOTH the listener entry and the
            observed-frames entry"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))

    (let [observed (deref #'state/observed-frames-by-cb)
          a        (atom 0)
          b        (atom 0)
          c        (atom 0)]
      ;; Two listeners under independent ids, both observe :test/main.
      (rf/register-listener! :epoch ::w1 (fn [_] (swap! a inc)))
      (rf/register-listener! :epoch ::w2 (fn [_] (swap! b inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 1 @a) "::w1 fired on the cascade")
      (is (= 1 @b) "::w2 fired on the cascade")

      (let [snap @observed]
        (is (contains? (get snap ::w1) :test/main)
            "::w1 has :test/main in its observed-frames")
        (is (contains? (get snap ::w2) :test/main)
            "::w2 has :test/main in its observed-frames")
        (is (= #{:test/main} (get snap ::w1)))
        (is (= #{:test/main} (get snap ::w2))))

      ;; Re-register ::w1 under a different fn — the listener swap is
      ;; well-tested by listener-same-key-replaces. Pin the OTHER half:
      ;; the observed-frames dissoc.
      (rf/register-listener! :epoch ::w1 (fn [_] (swap! c inc)))
      (is (nil? (get @observed ::w1))
          "re-register dissocs the prior observed-frames entry — new
           cb starts with an empty observed-frames set")
      (is (contains? (get @observed ::w2) :test/main)
          "::w2's entry is untouched — re-registration is scoped to ::w1")

      ;; Drive a new cascade — both cbs fire; ::w1's observed-frames
      ;; re-arms with :test/main.
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (is (= 1 @a) "the original ::w1 fn does not fire — it was replaced")
      (is (= 1 @c) "the replacement ::w1 fn fires once")
      (is (= 2 @b) "::w2 keeps firing across both cascades")
      (is (contains? (get @observed ::w1) :test/main)
          "::w1's observed-frames re-armed with :test/main"))))

(deftest listener-remove
  (testing "unregister-epoch-listener! stops the listener"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (let [count-a (atom 0)]
      (rf/register-listener! :epoch ::w (fn [_] (swap! count-a inc)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/unregister-listener! :epoch ::w)
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 1 @count-a) "after removal, the listener does not accumulate"))))

(deftest listener-exception-isolation
  (testing "a throwing epoch listener does not crash other listeners or the runtime"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))

    (let [survivor (atom 0)
          throws   (atom 0)]
      (rf/register-listener! :epoch ::throwing
        (fn [_] (swap! throws inc) (throw (ex-info "tool blew" {}))))
      (rf/register-listener! :epoch ::survivor
        (fn [_] (swap! survivor inc)))

      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 2 @throws)   "throwing listener is invoked")
      (is (= 2 @survivor) "survivor listener accumulates")
      (is (= 2 (count (rf/epoch-history :test/main)))
          "epoch history records both cascades despite the throwing listener"))))

(deftest listener-exception-emits-trace
  (testing "rf2-i5khp — a throwing listener emits a
            :rf.epoch.cb/listener-exception error trace per broken
            invocation, carrying :cb-id, :frame, :rf.epoch/id; isolation
            still holds (other listeners continue to fire)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))

    (let [recorded (record-trace!)
          survivor (atom 0)]
      (rf/register-listener! :epoch ::throwing
        (fn [_] (throw (ex-info "tool blew" {:why :test}))))
      (rf/register-listener! :epoch ::survivor
        (fn [_] (swap! survivor inc)))

      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (let [exc-events (filter (fn [ev]
                                 (and (= :error (:op-type ev))
                                      (= :rf.epoch.cb/listener-exception
                                         (:operation ev))))
                               @recorded)]
        (is (= 2 (count exc-events))
            "one trace per broken-listener invocation (one per cascade)")
        (is (every? (fn [ev] (= :test/main (-> ev :tags :frame))) exc-events)
            ":frame tag carries the originating frame")
        (is (every? (fn [ev] (= ::throwing (-> ev :tags :cb-id))) exc-events)
            ":cb-id tag identifies the broken listener registration key")
        (is (every? (fn [ev] (some? (-> ev :tags :rf.epoch/id))) exc-events)
            ":rf.epoch/id tag (canonical, rf2-ifdsar) links the failure to the assembled record")
        (is (every? (fn [ev] (string? (-> ev :tags :message))) exc-events)
            ":message tag carries the exception message")
        (is (every? (fn [ev] (= :no-recovery (:recovery ev))) exc-events)
            ":recovery is hoisted to the envelope top-level by
             `build-event` for error traces (Spec 009 §Error event
             shape); pins the no-recovery semantic — next cascade
             re-invokes the same fn afresh, no automatic remediation"))

      (is (= 2 @survivor)
          "isolation contract still holds: sibling listener kept firing
           — the trace emit is additive, not a behaviour change")
      (is (= 2 (count (rf/epoch-history :test/main)))
          "history still records every cascade"))))

;; ---- restore happy path ----------------------------------------------------

(deftest restore-rewinds-app-db
  (testing "restore-epoch! sets app-db to the named epoch's :db-after"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})       ;; n=1
    (rf/dispatch-sync [:inc]  {:frame :test/main})       ;; n=2
    (rf/dispatch-sync [:inc]  {:frame :test/main})       ;; n=3

    (let [history     (rf/epoch-history :test/main)
          target      (nth history 1)        ;; the :inc that landed n=1
          target-eid  (:epoch-id target)
          recorded    (record-trace!)
          ok?         (rf/restore-epoch! :test/main target-eid)]
      (is (true? ok?) "restore returned true")
      (is (= {:n 1} (rf/app-db-value :test/main))
          "app-db rewound to the named epoch's :db-after")

      (let [events @recorded]
        (is (some (fn [ev]
                    (and (= :rf.epoch/restored (:operation ev))
                         (= target-eid (:rf.epoch/id (:tags ev)))))
                  events)
            ":rf.epoch/restored fired with the matching :rf.epoch/id")))))

;; ---- frame-state snapshot/restore (EP-0001 rf2-3aizt1, decision #2 + #9) ---

(deftest epoch-captures-whole-frame-state-both-partitions
  (testing "an epoch record captures the canonical :frame-state-before /
            :frame-state-after (both partitions) and derives the :db-* app-db
            projections from them"
    (rf/reg-frame :test/main {})
    ;; A handler that writes BOTH partitions in one cascade — app-db via :db,
    ;; runtime-db via the reserved :rf.db/runtime effect.
    (rf/reg-event :seed-both
      (fn [{rt :rf.db/runtime} _]
        {:db            {:app-value 1}
         :rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/machines :snapshots :m/x]
                                  {:state :live})}))
    (rf/dispatch-sync [:seed-both] {:frame :test/main})

    (let [r (last (rf/epoch-history :test/main))]
      (is (= {:rf.db/app     {:app-value 1}
              :rf.db/runtime {:rf.runtime/machines {:snapshots {:m/x {:state :live}}}}}
             (:frame-state-after r))
          ":frame-state-after carries BOTH partitions coherently")
      (is (= {} (get-in r [:frame-state-before :rf.db/app]))
          ":frame-state-before's app-db partition is the pre-cascade {}")
      ;; The :db-* projections are the app-db slice of the canonical frame-state.
      (is (= {:app-value 1} (:db-after r))
          ":db-after is the app-db projection of :frame-state-after")
      (is (= {} (:db-before r))
          ":db-before is the app-db projection of :frame-state-before"))))

(deftest restore-rewinds-whole-frame-state-revives-runtime-db
  (testing "restore-epoch! reinstalls BOTH partitions (decision #9): a rewind to
            an epoch whose runtime-db carried a machine snapshot revives that
            snapshot, not just the app-db partition. The machine TYPE is
            registered so the restored snapshot reference resolves (it is a
            valid restore target, not a :rf.epoch/restore-missing-handler)."
    (rf/reg-frame :test/main {})
    ;; Register a real machine so the recorded snapshot's id resolves through
    ;; the public event registry (Spec 005 §Registration).
    (rf/reg-machine :m/x
      {:initial :live :states {:live {} :gone {}}})
    ;; Seed a known snapshot into the runtime-db partition + a marker in app-db.
    (rf/reg-event :put-machine
      (fn [{rt :rf.db/runtime} _]
        {:db {:phase :machine-alive}
         :rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/machines :snapshots :m/x]
                                  {:state :live :data {} :meta {}})}))
    (rf/reg-event :drop-machine
      (fn [{rt :rf.db/runtime} _]
        {:db {:phase :machine-gone}
         :rf.db/runtime (update-in (or rt {}) [:rf.runtime/machines :snapshots]
                                   dissoc :m/x)}))

    (rf/dispatch-sync [:put-machine]  {:frame :test/main})
    (let [alive-epoch (:epoch-id (last (rf/epoch-history :test/main)))]
      (rf/dispatch-sync [:drop-machine] {:frame :test/main})
      ;; After the drop, the runtime-db snapshot is gone.
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :test/main))
                        [:rf.runtime/machines :snapshots :m/x]))
          "the machine snapshot was dropped from runtime-db")
      ;; Rewind to the alive epoch.
      (is (true? (rf/restore-epoch! :test/main alive-epoch))
          "restore to the alive epoch succeeded")
      ;; BOTH partitions are rewound.
      (is (= {:state :live :data {} :meta {}}
             (get-in (:rf.db/runtime (rf/frame-state-value :test/main))
                     [:rf.runtime/machines :snapshots :m/x]))
          "restore revived the runtime-db machine snapshot (decision #9)")
      (is (= {:phase :machine-alive} (rf/app-db-value :test/main))
          "restore also rewound the app-db partition"))))

;; ---- runtime-db subsystem reconcile on restore (rf2-7r5mc2) ----------------
;;
;; perform-restore! installs the captured frame-state WHOLESALE — a runtime
;; subsystem whose durable snapshot is not safe to install verbatim must be
;; reconciled BEFORE the install, via the late-bound :resources/reconcile-on-
;; restore hook (Spec 016 §Restore and replay). The epoch artefact has no
;; static dep on the optional Resources artefact, so these tests stub the hook
;; (late-bind/set-fn!) and assert the WIRING: the hook is consulted with the
;; runtime-db partition, its return value is installed, and absence is a clean
;; verbatim pass-through. The reconcile LOGIC itself (settle-to-last-stable,
;; dangling-marking, index recompute, owner orphaning) is pinned in the
;; resources artefact's resources-restore-cljs-test.

(deftest reconcile-runtime-db-on-restore-consults-hook-and-installs-result
  (testing "rf2-7r5mc2 — reconcile-runtime-db-on-restore passes the runtime-db
            partition + frame-id to the :resources/reconcile-on-restore hook and
            returns the frame-state with the hook's reconciled runtime-db installed."
    (let [hook-key :resources/reconcile-on-restore
          original (late-bind/get-fn hook-key)
          seen     (atom nil)]
      (try
        ;; Stub a reconcile that records its inputs and rewrites the runtime-db
        ;; (the resources artefact's real reconcile does the settle/dangling work).
        ;; rf2-obi8rr — the hook is now consulted with a third `opts` arg
        ;; (`{:defer-traces? true}`) so its success traces ride back deferred;
        ;; the stub records it to pin that the seam passes the opts through.
        (late-bind/set-fn! hook-key
                           (fn [rdb frame-id opts]
                             (reset! seen {:rdb rdb :frame-id frame-id :opts opts})
                             (assoc rdb :rf.runtime/reconciled? true)))
        (let [fs {:rf.db/app     {:n 1}
                  :rf.db/runtime {:rf.runtime/resources {:entries {}}}}
              out (tool-pair/reconcile-runtime-db-on-restore :test/x fs)]
          (is (= {:rf.runtime/resources {:entries {}}} (:rdb @seen))
              "the hook receives the runtime-db PARTITION value")
          (is (= :test/x (:frame-id @seen)) "the hook receives the carried frame-id")
          (is (= {:defer-traces? true :restore-time-ms nil} (:opts @seen))
              "rf2-obi8rr — the hook is consulted with :defer-traces? true so it does not emit success rows before the install; rf2-wshzsp — and a :restore-time-ms slot (nil here, the 2-arity no-token path)")
          (is (true? (get-in out [:rf.db/runtime :rf.runtime/reconciled?]))
              "the hook's reconciled runtime-db is installed back into the frame-state")
          (is (= {:n 1} (:rf.db/app out)) "the app-db partition is untouched"))
        (finally
          (late-bind/set-fn! hook-key original))))))

(deftest reconcile-runtime-db-on-restore-noop-without-hook
  (testing "rf2-7r5mc2 — absent the :resources/reconcile-on-restore hook (no
            resources artefact), the frame-state installs verbatim (the
            pre-rf2-7r5mc2 behaviour)."
    (let [hook-key :resources/reconcile-on-restore
          original (late-bind/get-fn hook-key)]
      (try
        (late-bind/set-fn! hook-key nil)
        (let [fs {:rf.db/app {:n 1} :rf.db/runtime {:rf.runtime/resources {:entries {:k :v}}}}]
          (is (= fs (tool-pair/reconcile-runtime-db-on-restore :test/x fs))
              "no hook → frame-state passes through unchanged"))
        (finally
          (late-bind/set-fn! hook-key original))))))

(deftest reconcile-runtime-db-on-restore-noop-on-app-db-only-record
  (testing "rf2-7r5mc2 — a frame-state with only the app-db partition (no
            :rf.db/runtime key) passes through unchanged even when the hook
            is present (nothing to reconcile)."
    (let [hook-key :resources/reconcile-on-restore
          original (late-bind/get-fn hook-key)
          called?  (atom false)]
      (try
        (late-bind/set-fn! hook-key (fn [rdb _] (reset! called? true) rdb))
        (let [fs {:rf.db/app {:n 1}}]
          (is (= fs (tool-pair/reconcile-runtime-db-on-restore :test/x fs))
              "app-db-only frame-state is unchanged")
          (is (false? @called?) "the hook is not consulted when there is no runtime-db partition"))
        (finally
          (late-bind/set-fn! hook-key original))))))

(deftest perform-restore!-reconciles-installed-runtime-db
  (testing "rf2-7r5mc2 — end-to-end: perform-restore! runs the
            :resources/reconcile-on-restore hook over the runtime-db it is about
            to install, so the FRAME's restored runtime-db carries the reconciled
            value (a mid-flight slice never installs verbatim)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :put-resource
      (fn [{rt :rf.db/runtime} _]
        {:db {:phase :mid-flight}
         :rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/resources :entries :k]
                                  {:status :loading :current-work [:w 1]})}))
    (rf/reg-event :clear (fn [{:keys [db]} _] {:db {:phase :cleared}}))

    (let [hook-key :resources/reconcile-on-restore
          original (late-bind/get-fn hook-key)]
      (try
        ;; Stub the reconcile: settle the mid-flight entry (loading → loaded-ish)
        ;; + clear current-work, standing in for the resources artefact's real
        ;; reconcile so the epoch wiring is exercised without a resources dep.
        ;; rf2-obi8rr — accept the third `opts` arg (`{:defer-traces? true}`).
        (late-bind/set-fn! hook-key
                           (fn [rdb _frame-id _opts]
                             (-> rdb
                                 (assoc-in [:rf.runtime/resources :entries :k :status] :idle)
                                 (assoc-in [:rf.runtime/resources :entries :k :current-work] nil)
                                 (assoc :rf.runtime/restore-reconciled? true))))
        (rf/dispatch-sync [:put-resource] {:frame :test/main})
        (let [mid-epoch (:epoch-id (last (rf/epoch-history :test/main)))]
          (rf/dispatch-sync [:clear] {:frame :test/main})
          (is (true? (rf/restore-epoch! :test/main mid-epoch))
              "restore to the mid-flight epoch succeeded")
          (let [rdb (:rf.db/runtime (rf/frame-state-value :test/main))]
            (is (true? (:rf.runtime/restore-reconciled? rdb))
                "perform-restore! ran the reconcile hook over the installed runtime-db")
            (is (= :idle (get-in rdb [:rf.runtime/resources :entries :k :status]))
                "the mid-flight :loading entry was settled by the reconcile, not installed verbatim")
            (is (nil? (get-in rdb [:rf.runtime/resources :entries :k :current-work]))
                "the vanished current-work pointer was cleared during the reconcile")))
        (finally
          (late-bind/set-fn! hook-key original))))))

(deftest perform-restore!-threads-restored-epoch-causal-time-as-restore-time-ms
  (testing "rf2-wshzsp — perform-restore! threads the RESTORED epoch's causal
            :committed-at (the committing token's :rf.cofx :time-ms,
            replay-stable per EP-0010 §Time) into the reconcile hook as
            :restore-time-ms — NOT the live install wall clock. The hook stamps a
            dangled-on-restore mutation instance's durable :settled-at from it, so
            the durable field comes from a causal input rather than an ambient
            world read at install (EP-0010 §Restore/Replay)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :put-resource
      (fn [{rt :rf.db/runtime} _]
        {:db {:phase :mid-flight}
         :rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/resources :entries :k]
                                  {:status :loading :current-work [:w 1]})}))
    (rf/reg-event :clear (fn [{:keys [db]} _] {:db {:phase :cleared}}))

    (let [hook-key   :resources/reconcile-on-restore
          original   (late-bind/get-fn hook-key)
          seen-opts  (atom nil)
          token-time 1781078400777          ; the causal token time of the mid-flight commit
          clock-time 9999999999999]         ; the (wrong) ambient install-clock sentinel
      (try
        (late-bind/set-fn! hook-key
                           (fn [rdb _frame-id opts]
                             (reset! seen-opts opts)
                             rdb))
        ;; Commit the mid-flight epoch under a SCRIPTED causal token time so the
        ;; restored epoch's :committed-at is a known sentinel-distinct value.
        (rf/dispatch-sync [:put-resource] {:frame           :test/main
                                           :rf.cofx {:rf/time-ms token-time}})
        (let [mid-record (last (rf/epoch-history :test/main))
              mid-epoch  (:epoch-id mid-record)]
          (is (= token-time (:committed-at mid-record))
              "precondition: the mid-flight epoch's :committed-at is the scripted causal token time")
          (rf/dispatch-sync [:clear] {:frame :test/main})
          ;; Restore UNDER a wrong ambient install clock — a regression that
          ;; sourced :restore-time-ms from now-ms would stamp the sentinel here.
          (with-redefs [interop/now-ms       (constantly clock-time)
                        interop/epoch-now-ms (constantly clock-time)]
            (is (true? (rf/restore-epoch! :test/main mid-epoch))
                "restore to the mid-flight epoch succeeded"))
          (is (= token-time (:restore-time-ms @seen-opts))
              ":restore-time-ms is the RESTORED epoch's causal :committed-at — replay-stable")
          (is (not= clock-time (:restore-time-ms @seen-opts))
              ":restore-time-ms is NOT the live ambient install clock"))
        (finally
          (late-bind/set-fn! hook-key original))))))

;; ---- restore-time host-transient quiesce (rf2-u5kmf8) ----------------------
;;
;; EP-0011 / Managed-Effects §SSR, preload, hydration, and restore: "Hydration
;; and epoch restore MUST NOT revive host work." A restore installs the captured
;; durable frame-state WHOLESALE but the async HOST WORK spawned by the epochs
;; being unwound (machine `:after` host-clock timers, non-resource managed-HTTP
;; AbortControllers / in-flight handles) is NOT frame-state — it stays attached
;; to the pre-restore timeline. Restore must QUIESCE it for the restored frame:
;; cancel/clear the orphaned host handles so a late pre-restore completion is
;; stale-suppressed and never delivers to its original `:rf/reply-to` target.
;;
;; The `:resources/reconcile-on-restore` hook (rf2-7r5mc2) covers the Resources
;; subsystem; the OTHER managed async subsystems need their own restore-time
;; cleanup. perform-restore! fires a generic host-transient quiesce hook chain
;; (`:machines/on-frame-restored!`, `:http/abort-in-flight-for-frame!`) AFTER a
;; successful install — the restore counterpart to destroy-frame!'s
;; `:machines/on-frame-destroyed!` / `:http/abort-on-actor-destroy` chain. The
;; epoch artefact has no static dep on the optional machines / http artefacts,
;; so these tests stub the hooks and assert the WIRING (consulted with the
;; restored frame-id, ONLY on a successful install). The cancel/abort + stale-
;; suppression LOGIC is pinned in each subsystem's own artefact test.

(deftest perform-restore!-quiesces-orphaned-async-host-work
  (testing "rf2-u5kmf8 — a successful restore fires the host-transient quiesce
            hook chain for the managed async subsystems (machines :after timers,
            non-resource managed HTTP) addressed to the restored frame, so the
            async host work the unwound epochs spawned is cancelled/cleared."
    (rf/reg-frame :test/main {})
    (rf/reg-event :step (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf/reg-event :step2 (fn [{:keys [db]} _] {:db (assoc db :n 2)}))
    (let [machines-key :machines/on-frame-restored!
          http-key     :http/abort-in-flight-for-frame!
          orig-mach    (late-bind/get-fn machines-key)
          orig-http    (late-bind/get-fn http-key)
          seen         (atom [])]
      (try
        (late-bind/set-fn! machines-key (fn [frame-id] (swap! seen conj [:machines frame-id])))
        (late-bind/set-fn! http-key     (fn [frame-id] (swap! seen conj [:http frame-id])))
        (rf/dispatch-sync [:step] {:frame :test/main})
        (let [target (:epoch-id (last (rf/epoch-history :test/main)))]
          (rf/dispatch-sync [:step2] {:frame :test/main})
          (is (true? (rf/restore-epoch! :test/main target))
              "restore to the earlier epoch succeeded")
          (is (some #{[:machines :test/main]} @seen)
              ":machines/on-frame-restored! fired for the restored frame")
          (is (some #{[:http :test/main]} @seen)
              ":http/abort-in-flight-for-frame! fired for the restored frame"))
        (finally
          (late-bind/set-fn! machines-key orig-mach)
          (late-bind/set-fn! http-key orig-http))))))

(deftest perform-restore!-quiesce-noop-without-hooks
  (testing "rf2-u5kmf8 — absent the machines / http artefacts (hooks nil) the
            restore install is a clean pass-through (apps with no async host work
            pay nothing)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :step (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf/reg-event :step2 (fn [{:keys [db]} _] {:db (assoc db :n 2)}))
    (let [machines-key :machines/on-frame-restored!
          http-key     :http/abort-in-flight-for-frame!
          orig-mach    (late-bind/get-fn machines-key)
          orig-http    (late-bind/get-fn http-key)]
      (try
        (late-bind/set-fn! machines-key nil)
        (late-bind/set-fn! http-key nil)
        (rf/dispatch-sync [:step] {:frame :test/main})
        (let [target (:epoch-id (last (rf/epoch-history :test/main)))]
          (rf/dispatch-sync [:step2] {:frame :test/main})
          (is (true? (rf/restore-epoch! :test/main target))
              "restore succeeds with no quiesce hooks registered"))
        (finally
          (late-bind/set-fn! machines-key orig-mach)
          (late-bind/set-fn! http-key orig-http))))))

(deftest perform-restore!-does-not-quiesce-on-failed-install
  (testing "rf2-u5kmf8 — a destroyed-frame install (perform-restore! returns
            false, writes nothing) does NOT fire the quiesce hooks: cancelling a
            live frame's host work for a restore that never landed would be a
            false cancellation. The quiesce runs only on the success branch,
            mirroring commit-resources-restore-traces!."
    (rf/reg-frame :test/main {})
    (rf/reg-event :step (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (let [machines-key :machines/on-frame-restored!
          orig-mach    (late-bind/get-fn machines-key)
          fired?       (atom false)]
      (try
        (late-bind/set-fn! machines-key (fn [_frame-id] (reset! fired? true)))
        (rf/dispatch-sync [:step] {:frame :test/main})
        (let [target-record (last (rf/epoch-history :test/main))
              ;; drive perform-restore! directly against a frame whose container
              ;; is gone (the validate-then-destroy / destroyed-frame branch):
              ;; live-container-or-fail returns :fail and perform-restore! bails
              ;; with false BEFORE the success telemetry / quiesce chain.
              _ (rf/destroy-frame! :test/main)
              result (tool-pair/perform-restore! :test/main target-record)]
          (is (false? result) "perform-restore! returns false for the destroyed frame")
          (is (false? @fired?)
              "the quiesce chain did NOT fire for a restore that wrote nothing"))
        (finally
          (late-bind/set-fn! machines-key orig-mach))))))

(deftest restore-releases-armed-machine-after-timer-end-to-end
  (testing "rf2-u5kmf8 — ADVERSARIAL end-to-end through the REAL machines
            artefact (no stubbed hook): a machine `:after` host-clock timer
            armed before a restore is released by the restore boundary, so the
            restored frame carries no orphaned wall-clock handle from the
            unwound epoch."
    (rf/reg-frame :test/main {})
    ;; A machine whose :loading state arms a long-delay :after (the host clock
    ;; will not fire it during the test; it lingers in the timer table). The
    ;; :idle epoch is the restore target — restoring to it rewinds the snapshot
    ;; out of :loading, so the in-flight :after timer is orphaned.
    (rf/reg-machine :rest/m
      {:initial :idle
       :data    {}
       :states
       {:idle    {:on {:fetch :loading}}
        :loading {:after {3600000 :timeout}
                  :on    {:loaded :ready}}
        :timeout {}
        :ready   {}}})
    ;; epoch 1: machine boots into :idle (its initial-entry cascade).
    (rf/dispatch-sync [:rest/m [:rf.machine/start]] {:frame :test/main})
    (let [idle-epoch (:epoch-id (last (rf/epoch-history :test/main)))]
      ;; epoch 2: :fetch → :loading arms the :after host-clock timer.
      (rf/dispatch-sync [:rest/m [:fetch]] {:frame :test/main})
      (is (seq (get @machine-timer/after-timers :test/main))
          "precondition: the :loading state armed an :after host-clock timer")
      ;; Restore to the :idle epoch — the snapshot rewinds out of :loading.
      (is (true? (rf/restore-epoch! :test/main idle-epoch))
          "restore to the pre-:loading epoch succeeded")
      (is (empty? (get @machine-timer/after-timers :test/main))
          "the orphaned :after host-clock handle was RELEASED by the restore — no leaked wall-clock timer from the unwound epoch"))))

;; ---- restore failure modes -------------------------------------------------

(deftest restore-failure-unknown-frame
  (testing "restore-epoch! on an unknown frame fires :rf.error/no-such-handler (kind :frame)"
    (let [recorded (record-trace!)
          ok?      (rf/restore-epoch! :no.such/frame :ignored)]
      (is (false? ok?))
      (let [events @recorded]
        (is (has-error-op? events :rf.error/no-such-handler))
        (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %) events)]
          (is (= :frame (:kind (:tags ev))))
          (is (= :no.such/frame (:frame (:tags ev)))))))))

(deftest restore-failure-unknown-epoch
  (testing "restore-epoch! with an epoch-id not in history fires :rf.epoch/restore-unknown-epoch"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [pre        (rf/app-db-value :test/main)
          recorded   (record-trace!)
          ok?        (rf/restore-epoch! :test/main :no-such-epoch)]
      (is (false? ok?))
      (is (= pre (rf/app-db-value :test/main)) "app-db unchanged")
      (let [events @recorded
            ev     (some #(when (= :rf.epoch/restore-unknown-epoch (:operation %)) %) events)]
        (is (some? ev) ":rf.epoch/restore-unknown-epoch fired")
        (is (= :no-such-epoch (:rf.epoch/id (:tags ev))))
        (is (number? (:history-size (:tags ev))))))))

(deftest restore-failure-schema-mismatch
  (testing "restore-epoch! on a db that no longer validates fires :rf.epoch/restore-schema-mismatch"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :set-bad
      ;; Commit a value that LATER fails a tightened schema. We dispatch
      ;; this BEFORE the schema is registered so the pre-restore commit
      ;; succeeds; tightening the schema happens between dispatch and
      ;; restore.
      (fn [{:keys [db]} _] {:db (assoc db :n "not-an-int")}))

    (rf/dispatch-sync [:seed]    {:frame :test/main})
    (rf/dispatch-sync [:set-bad] {:frame :test/main})
    ;; Reset the db to something valid so we can verify the restore
    ;; doesn't run when schema mismatch is detected.
    (rf/dispatch-sync [:seed]    {:frame :test/main})

    ;; Now register a schema that the bad-record's :db-after fails.
    ;; Per Spec 010 §Per-frame schemas reg-app-schema is frame-scoped;
    ;; restore-epoch! runs on :test/main so the schema must register
    ;; against that frame, not the (current-frame)-default :rf/default.
    (rf/reg-app-schema [:n] {:frame :test/main} [:int])

    (let [pre      (rf/app-db-value :test/main)
          history  (rf/epoch-history :test/main)
          target   (some (fn [r]
                           (when (= "not-an-int" (:n (:db-after r)))
                             r))
                         history)
          recorded (record-trace!)
          ok?      (rf/restore-epoch! :test/main (:epoch-id target))]
      (is (some? target) "we recorded the bad-db cascade")
      (is (false? ok?)   "restore rejected")
      (is (= pre (rf/app-db-value :test/main)) "app-db unchanged")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/restore-schema-mismatch (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/restore-schema-mismatch fired")
        (is (vector? (:failing-paths (:tags ev))))))))

(deftest restore-schema-mismatch-trace-carries-digests
  (testing "Per Spec 010 §Schema digest + Tool-Pair §Time-travel (rf2-0z1z):
            the :rf.epoch/restore-schema-mismatch trace carries non-nil
            :schema-digest-recorded and :schema-digest-current tags."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed    (fn [{:keys [db]} _]  {:db {:n 0}}))
    (rf/reg-event :set-bad (fn [{:keys [db]} _] {:db (assoc db :n "not-an-int")}))

    ;; Record an epoch with NO schemas registered yet — its
    ;; :schema-digest is the empty-set digest (still non-nil — Spec 010
    ;; defines the empty set's digest).
    (rf/dispatch-sync [:seed]    {:frame :test/main})
    (rf/dispatch-sync [:set-bad] {:frame :test/main})
    (rf/dispatch-sync [:seed]    {:frame :test/main})

    ;; Tighten the schema set — the recorded epoch's digest now
    ;; differs from the live (current) digest.
    (rf/reg-app-schema [:n] {:frame :test/main} [:int])

    (let [history  (rf/epoch-history :test/main)
          target   (some (fn [r]
                           (when (= "not-an-int" (:n (:db-after r)))
                             r))
                         history)
          recorded (record-trace!)
          _        (rf/restore-epoch! :test/main (:epoch-id target))
          ev       (some (fn [ev]
                           (when (= :rf.epoch/restore-schema-mismatch (:operation ev))
                             ev))
                         @recorded)
          tags     (:tags ev)]
      (is (some? ev) ":rf.epoch/restore-schema-mismatch fired")
      (is (string? (:schema-digest-recorded tags))
          ":schema-digest-recorded is a digest string, not nil")
      (is (string? (:schema-digest-current tags))
          ":schema-digest-current is a digest string, not nil")
      (is (re-matches #"sha256:[0-9a-f]{16}" (:schema-digest-recorded tags))
          ":schema-digest-recorded matches the canonical wire form")
      (is (re-matches #"sha256:[0-9a-f]{16}" (:schema-digest-current tags))
          ":schema-digest-current matches the canonical wire form")
      (is (not= (:schema-digest-recorded tags)
                (:schema-digest-current tags))
          "recorded ≠ current — that's *why* the restore was rejected"))))

(deftest epoch-record-stamps-schema-digest
  (testing "Per Spec-Schemas §:rf/epoch-record (rf2-0z1z): every epoch
            record carries a :schema-digest pinned at record time."
    (rf/reg-frame :test/digest {})
    (rf/reg-app-schema [:n] {:frame :test/digest} [:int])
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:init] {:frame :test/digest})
    (let [r (last (rf/epoch-history :test/digest))]
      (is (some? r) "an epoch record was committed")
      (is (string? (:schema-digest r))
          "the record carries a :schema-digest string")
      (is (= (:schema-digest r)
             (schemas/app-schemas-digest :test/digest))
          "record's stamp matches the live digest at record time"))))

(deftest restore-failure-missing-handler-route
  (testing "restore-epoch! on a db referencing a now-unregistered route fires :rf.epoch/restore-missing-handler"
    (rf/reg-frame :test/main {})
    ;; Register a route so the recorded route reference resolves; we'll
    ;; later unregister it to trigger the missing-handler failure.
    (rf/reg-route :route/users {} "/users")
    ;; EP-0001 (rf2-vzld77 / rf2-3aizt1): the route slice is runtime-db state
    ;; at [:rf.runtime/routing :current]; bead 7's missing-references reads the
    ;; recorded frame-state's runtime-db partition.
    (rf/reg-event :route-to
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                  {:route-id :route/users})}))
    (rf/dispatch-sync [:route-to] {:frame :test/main})
    ;; A subsequent dispatch so the history holds at least one record
    ;; whose :frame-state-after references :route/users.
    (let [target (last (rf/epoch-history :test/main))]
      ;; Now blow away the route registration.
      (registrar/unregister! :route :route/users)

      (let [recorded (record-trace!)
            pre      (rf/app-db-value :test/main)
            ok?      (rf/restore-epoch! :test/main (:epoch-id target))]
        (is (false? ok?))
        (is (= pre (rf/app-db-value :test/main)) "app-db unchanged")
        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-missing-handler (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev) ":rf.epoch/restore-missing-handler fired")
          (let [missing (:missing (:tags ev))]
            (is (vector? missing))
            (is (some #(and (= :route (:kind %))
                            (= :route/users (:id %)))
                      missing))))))))

;; EP-0001 (rf2-vzld77) re-enabled by bead 7 (rf2-3aizt1): the epoch now
;; captures + restores the runtime-db partition (machine snapshots are
;; runtime-db state), and `tool_pair/missing-references` reads the recorded
;; runtime-db partition (rf2-k4xe7u). See machine-raise note above.
(deftest restore-failure-missing-handler-machine
  (testing "restore-epoch! on a frame-state referencing a machine snapshot whose
  machine is no longer registered fires :rf.epoch/restore-missing-handler. Per
  rf2-ocg1: machine resolution goes through the public event registry
  (:rf/machine? metadata), NOT the internal :head registrar kind."
    (rf/reg-frame :test/main {})
    ;; Register a machine via the public reg-machine path. This installs an
    ;; :event handler with :rf/machine? metadata.
    (rf/reg-machine :machine/tl
      {:initial :red
       :states  {:red    {:on {:tick :green}}
                 :green  {:on {:tick :red}}}})
    ;; Drive the machine so the runtime-db partition gets a snapshot at
    ;; [:rf.runtime/machines :snapshots :machine/tl].
    (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main})

    (let [target (last (rf/epoch-history :test/main))]
      (is (some? (get-in (:frame-state-after target)
                         [:rf.db/runtime :rf.runtime/machines :snapshots :machine/tl]))
          "snapshot recorded in the runtime-db partition")

      ;; Unregister the machine so the recorded snapshot's id no longer resolves.
      (registrar/unregister! :event :machine/tl)

      (let [recorded (record-trace!)
            pre      (rf/app-db-value :test/main)
            ok?      (rf/restore-epoch! :test/main (:epoch-id target))]
        (is (false? ok?))
        (is (= pre (rf/app-db-value :test/main)) "app-db unchanged")
        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-missing-handler (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev) ":rf.epoch/restore-missing-handler fired")
          (let [missing (:missing (:tags ev))]
            (is (vector? missing))
            (is (some #(and (= :machine (:kind %))
                            (= :machine/tl (:id %)))
                      missing)
                "missing entry surfaces the machine id under :machine kind")))))))

;; EP-0001 (rf2-vzld77) re-enabled by bead 7 (rf2-3aizt1): same runtime-db
;; epoch capture/restore as restore-failure-missing-handler-machine.
(deftest restore-failure-missing-handler-non-machine-event-not-confused
  (testing "an event handler under the same id as a recorded machine snapshot —
  but NOT marked :rf/machine? — does not satisfy the machine reference. Per
  rf2-ocg1, the registry probe gates on :rf/machine? metadata."
    (rf/reg-frame :test/main {})
    ;; Register a machine, drive it, then replace its registration with a
    ;; plain event handler (no :rf/machine? metadata). The recorded snapshot
    ;; should still surface as missing, since the public contract says the
    ;; reference must resolve to a registered MACHINE — not a same-id event.
    (rf/reg-machine :machine/tl
      {:initial :red
       :states  {:red {:on {:tick :green}} :green {}}})
    (rf/dispatch-sync [:machine/tl [:tick]] {:frame :test/main})

    (let [target (last (rf/epoch-history :test/main))]
      (rf/reg-event :machine/tl (fn [{:keys [db]} _] {:db db})) ;; replace with non-machine handler

      (let [recorded (record-trace!)
            ok?      (rf/restore-epoch! :test/main (:epoch-id target))]
        (is (false? ok?))
        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-missing-handler (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev))
          (is (some #(and (= :machine (:kind %))
                          (= :machine/tl (:id %)))
                    (:missing (:tags ev)))))))))

(deftest restore-failure-version-mismatch
  (testing "restore-epoch! on a db whose machine snapshot version drifts fires
  :rf.epoch/restore-version-mismatch. Per rf2-ocg1: the recorded snapshot's
  [:meta :rf/snapshot-version] is compared against the registered machine's
  [:meta :rf/snapshot-version], both via the public Spec 005 surface."
    (rf/reg-frame :test/main {})
    ;; Register a versioned machine via the public path.
    (rf/reg-machine :machine/tl
      {:initial :red
       :meta    {:rf/snapshot-version 1}
       :states  {:red {:on {:tick :green}} :green {}}})
    ;; Commit a snapshot carrying matching :meta :rf/snapshot-version into the
    ;; runtime-db partition (EP-0001 rf2-vzld77 — machine snapshots are
    ;; runtime-db state; bead 7 reads the runtime-db partition of the recorded
    ;; frame-state for the version-drift precondition).
    (rf/reg-event :put-snap
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime
         (assoc-in (or rt {}) [:rf.runtime/machines :snapshots :machine/tl]
                   {:state :red :data {} :meta {:rf/snapshot-version 1}})}))
    (rf/dispatch-sync [:put-snap] {:frame :test/main})

    (let [target (last (rf/epoch-history :test/main))]
      ;; Hot-reload bumps the machine definition's version.
      (rf/reg-machine :machine/tl
        {:initial :red
         :meta    {:rf/snapshot-version 2}
         :states  {:red {:on {:tick :green}} :green {}}})

      (let [recorded (record-trace!)
            pre      (rf/app-db-value :test/main)
            ok?      (rf/restore-epoch! :test/main (:epoch-id target))]
        (is (false? ok?))
        (is (= pre (rf/app-db-value :test/main)))
        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-version-mismatch (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev) ":rf.epoch/restore-version-mismatch fired")
          (is (= :machine/tl (:machine-id (:tags ev))))
          (is (= 1 (:version-recorded (:tags ev))))
          (is (= 2 (:version-current  (:tags ev)))))))))

(deftest restore-failure-during-drain
  (testing "restore-epoch! called from inside a drain fires :rf.epoch/restore-during-drain"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [target   (last (rf/epoch-history :test/main))
          recorded (record-trace!)
          attempt  (atom nil)]
      ;; A handler that calls restore-epoch! synchronously during a drain.
      (rf/reg-event :try-restore
        (fn [{:keys [db]} _]
          (reset! attempt (rf/restore-epoch! :test/main (:epoch-id target)))
          {:db (assoc db :n 99)}))
      (rf/dispatch-sync [:try-restore] {:frame :test/main})

      (is (false? @attempt) "restore returned false from inside the drain")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/restore-during-drain (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/restore-during-drain fired")
        (is (= :test/main (:frame (:tags ev))))))))

;; ---- structured projections ------------------------------------------------

(deftest sub-runs-projection
  (testing ":sub-runs reflects each :rf.sub/run trace under the cascade"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-sub :n     (fn [db _] (:n db)))
    (rf/reg-sub :n*2   :<- [:n] (fn [n _] (* 2 (or n 0))))

    ;; Force a sub-run inside a handler (subscribe-once emits :rf.sub/run
    ;; from compute-sub when no cache exists for the query, AND from the
    ;; reactive path when a fresh subscription materialises). Either
    ;; way, the cascade contains :rf.sub/run traces.
    (rf/reg-event :read-sub
      (fn [_ _]
        ;; Read both subs to exercise layer-1 and layer-2.
        (let [_v (rf/subscribe-once [:n*2] {:frame :test/main})]
          {})))

    (rf/dispatch-sync [:seed]      {:frame :test/main})
    (rf/dispatch-sync [:read-sub]  {:frame :test/main})

    (let [r        (last (rf/epoch-history :test/main))
          sub-runs (:sub-runs r)]
      (is (vector? sub-runs))
      (is (some #(= :n   (:sub-id %)) sub-runs))
      (is (some #(= :n*2 (:sub-id %)) sub-runs))
      (is (every? :recomputed? sub-runs))
      ;; rf2-7e2y: :result-changed? was structurally always true (only
      ;; recomputed subs emit :rf.sub/run under rf2-719e value-equality
      ;; suppression) and has been dropped — every entry must NOT carry
      ;; the slot.
      (is (every? #(not (contains? % :result-changed?)) sub-runs)))))

(deftest sub-run-row-threads-cause-event-id
  (testing "rf2-okz1u / rf2-1cc03 — `:rf.sub/cause-event-id` (the
            dispatching cascade's trigger event-id) is threaded onto
            the structured `:sub-runs` row as `:cause-event-id` so
            consumers (Xray's Epoch panel SUBSCRIPTIONS section) can
            attribute each sub-run to the right epoch without re-folding
            the raw trace. Parity with the sibling `:cause-sub` slot:
            present when emitted, absent (key omitted) when the tag was
            OMITTED at the emit site (sub ran outside any cascade).

            Direct unit test against `capture/sub-run-row` — the
            projector shared between `project-all`'s fused settle-time
            walk and `re-frame.epoch.listeners`'s post-settle back-fill."
    (testing "tag PRESENT on a sub run inside an in-flight cascade →
              row carries :cause-event-id"
      (let [row (capture/sub-run-row
                  {:op-type   :rf.sub
                   :operation :rf.sub/run
                   :tags      {:rf.sub/id             :counter/value
                               :rf.sub/query-v        [:counter/value]
                               :rf.sub/value-changed? true
                               :rf.sub/prev-value     0
                               :rf.sub/value          1
                               :rf.sub/cascade?       false
                               :rf.sub/cause-sub      nil
                               :rf.sub/cause-event-id :counter/inc}})]
        (is (= :counter/inc (:cause-event-id row))
            ":cause-event-id is lifted from the `:rf.sub/cause-event-id` tag")
        (is (true? (:value-changed? row))
            "the existing slots still ride alongside the new attribution")))
    (testing "tag OMITTED (post-settle reactive flush outside a cascade,
              or `re-frame.epoch` artefact absent) → row slot is ABSENT,
              parity with the OMIT-vs-nil semantics of the trace tag"
      (let [row (capture/sub-run-row
                  {:op-type   :rf.sub
                   :operation :rf.sub/run
                   :tags      {:rf.sub/id             :counter/value
                               :rf.sub/query-v        [:counter/value]
                               :rf.sub/value-changed? true
                               :rf.sub/prev-value     0
                               :rf.sub/value          1
                               :rf.sub/cascade?       false
                               :rf.sub/cause-sub      nil}})]
        (is (not (contains? row :cause-event-id))
            ":cause-event-id key is ABSENT when the trace tag was
             omitted at the emit site (cond-> on (contains? tags ...))")))))

(deftest sub-run-row-threads-large-marker
  (testing "rf2-at60h — the whole-output `:large?` stamp that
            `re-frame.classification/project-sub-tags` writes onto a `:rf.sub/run`
            trace tag (when the sub's output is marked large but its raw
            value is left in place for the on-box ring) is threaded onto
            the structured `:sub-runs` row as `:large?`, so the off-box
            `projected-record` egress boundary can substitute a
            `:rf.size/large-elided` marker for `:value` / `:prev-value`.
            Direct unit test against `capture/sub-run-row`."
    (testing ":large? tag PRESENT → row carries :large? true, value intact
              (the projection, not the row builder, does the substitution)"
      (let [row (capture/sub-run-row
                  {:op-type   :rf.sub
                   :operation :rf.sub/run
                   :tags      {:rf.sub/id             :big/value
                               :rf.sub/query-v        [:big/value]
                               :rf.sub/value-changed? true
                               :rf.sub/prev-value     "small"
                               :rf.sub/value          "BIG"
                               :rf.sub/cascade?       false
                               :rf.sub/cause-sub      nil
                               :large?                true}})]
        (is (true? (:large? row))
            ":large? is lifted from the trace tag so the egress projector sees it")
        (is (= "BIG" (:value row))
            "the raw value stays on the row — on-box ring keeps exact state")))
    (testing ":large? tag ABSENT (non-large sub) → row omits the flag"
      (let [row (capture/sub-run-row
                  {:op-type   :rf.sub
                   :operation :rf.sub/run
                   :tags      {:rf.sub/id             :small/value
                               :rf.sub/query-v        [:small/value]
                               :rf.sub/value-changed? true
                               :rf.sub/prev-value     0
                               :rf.sub/value          1
                               :rf.sub/cascade?       false
                               :rf.sub/cause-sub      nil}})]
        (is (not (contains? row :large?))
            ":large? key is ABSENT when the sub's output is not large")))))

(deftest effects-projection-skipped-on-platform
  (testing ":effects captures :skipped-on-platform outcomes"
    (rf/reg-frame :test/main {})
    (rf/reg-fx :client-only-fx {:platforms #{:client}}
               (fn [_ _] :nope))
    (rf/reg-event :run
      (fn [_ _] {:fx [[:client-only-fx :payload]]}))

    (rf/dispatch-sync [:run] {:frame :test/main})

    (let [r       (last (rf/epoch-history :test/main))
          effects (:effects r)
          ent     (some #(when (= :client-only-fx (:fx-id %)) %) effects)]
      (is (some? ent) ":client-only-fx surfaces in :effects")
      (is (= :skipped-on-platform (:outcome ent))))))

(deftest effects-projection-no-such-fx
  (testing ":effects captures :error outcomes for unknown fx-ids"
    (rf/reg-frame :test/main {})
    (rf/reg-event :run
      (fn [_ _] {:fx [[:no/such-fx :payload]]}))
    (rf/dispatch-sync [:run] {:frame :test/main})

    (let [r       (last (rf/epoch-history :test/main))
          effects (:effects r)
          ent     (some #(when (= :no/such-fx (:fx-id %)) %) effects)]
      (is (some? ent))
      (is (= :error (:outcome ent)))
      (is (some? (:error-trace ent))))))

(deftest effects-projection-fx-handler-exception
  (testing ":effects captures :error outcomes for fx that throw"
    (rf/reg-frame :test/main {})
    (rf/reg-fx :throwing-fx (fn [_ _] (throw (ex-info "boom" {}))))
    (rf/reg-event :run
      (fn [_ _] {:fx [[:throwing-fx :payload]]}))

    (rf/dispatch-sync [:run] {:frame :test/main})

    (let [r       (last (rf/epoch-history :test/main))
          effects (:effects r)
          ent     (some #(when (= :throwing-fx (:fx-id %)) %) effects)]
      (is (some? ent))
      (is (= :error (:outcome ent)))
      (is (some? (:error-trace ent))))))

(deftest effects-projection-records-success
  (testing ":effects captures :ok outcomes for successful user fx (rf2-rrgq)"
    (rf/reg-frame :test/main {})
    (let [calls (atom 0)]
      (rf/reg-fx :tally-fx (fn [_ args] (swap! calls + args)))
      (rf/reg-event :run
        (fn [_ _] {:fx [[:tally-fx 5]]}))

      (rf/dispatch-sync [:run] {:frame :test/main})

      (is (= 5 @calls) "the fx ran")
      (let [r       (last (rf/epoch-history :test/main))
            effects (:effects r)
            ent     (some #(when (= :tally-fx (:fx-id %)) %) effects)]
        (is (some? ent) ":tally-fx surfaces in :effects on success")
        (is (= :ok (:outcome ent)))
        (is (= 5   (:args ent)))
        (is (not (contains? ent :error-trace))
            ":ok entries don't carry :error-trace")))))

(deftest effects-projection-records-reserved-fx-success
  (testing ":effects captures :ok outcomes for reserved fx-ids (:dispatch)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed   (fn [{:keys [db]} _]   {:db {:n 0}}))
    (rf/reg-event :inc    (fn [{:keys [db]} _]  {:db (update db :n inc)}))
    (rf/reg-event :outer
      (fn [_ _] {:fx [[:dispatch [:inc]]
                      [:dispatch [:inc]]]}))

    (rf/dispatch-sync [:seed]  {:frame :test/main})
    (rf/dispatch-sync [:outer] {:frame :test/main})

    ;; Per rf2-nj6p7: per-event epochs — the two `:dispatch` fx fire
    ;; during :outer's own do-fx, so they project onto :outer's record
    ;; (NOT the last record, which is now the second :inc child epoch).
    (let [history (rf/epoch-history :test/main)
          r       (first (filter #(= :outer (:event-id %)) history))
          effects (:effects r)
          dispatches (filterv #(= :dispatch (:fx-id %)) effects)]
      (is (= 2 (count dispatches))
          "two :dispatch fx → two :effects entries on the :outer epoch")
      (is (every? #(= :ok (:outcome %)) dispatches)))))

(deftest effects-projection-one-entry-per-fx
  (testing "an epoch with N dispatched fx produces N :effects entries (rf2-rrgq)"
    (rf/reg-frame :test/main {})
    (rf/reg-fx :ok-fx       (fn [_ _] :ok))
    (rf/reg-fx :throwing-fx (fn [_ _] (throw (ex-info "boom" {}))))
    (rf/reg-fx :client-only {:platforms #{:client}}
               (fn [_ _] :nope))
    (rf/reg-event :run
      (fn [_ _] {:fx [[:ok-fx       :a]
                      [:throwing-fx :b]
                      [:no/such-fx  :c]
                      [:client-only :d]
                      [:ok-fx       :e]]}))

    (rf/dispatch-sync [:run] {:frame :test/main})

    (let [r       (last (rf/epoch-history :test/main))
          effects (:effects r)]
      (is (= 5 (count effects))
          "five dispatched fx → five projection entries, no double-count")
      (is (= [:ok :error :error :skipped-on-platform :ok]
             (mapv :outcome effects))
          "outcomes preserved in dispatch order")
      (is (= [:ok-fx :throwing-fx :no/such-fx :client-only :ok-fx]
             (mapv :fx-id effects))
          "fx-ids preserved in dispatch order"))))

;; ---- partial-drain semantics (rf2-v0jwt) ---------------------------------

(deftest depth-exceeded-commits-halted-record
  (testing "per rf2-nj6p7 (per-event epochs): a depth-exceeded drain leaves
            the events that already ran as DURABLE :ok epochs (no whole-
            drain rollback — each settled event is independently atomic),
            and commits a single trailing :halted-depth record marking the
            halt boundary. The halting event (next in queue) never ran, so
            its record's :db-before / :db-after both equal the durable
            last-settled db. NOTE: this changes Spec 002 rule 3's pre-
            rf2-u6jsj whole-drain-rollback semantics — see report."
    (rf/reg-frame :test/main {:drain-depth 5})
    (rf/reg-event :loop
      (fn [{:keys [db]} _]
        {:db (update (or db {}) :n (fnil inc 0))
         :fx [[:dispatch [:loop]]]}))

    (rf/dispatch-sync [:loop] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      ;; Five :loop events ran (the depth limit), each a durable :ok
      ;; epoch, then a trailing :halted-depth marker.
      (is (= 6 (count history))
          "five durable :ok epochs + one trailing :halted-depth marker")
      (is (= (repeat 5 :ok) (mapv :outcome (take 5 history)))
          "the events that ran are durable :ok epochs — NOT rolled back")
      (is (= {:n 5} (:db-after (nth history 4)))
          "the durable db reflects the five completed events' writes")
      (let [r (last history)]
        (is (= :halted-depth (:outcome r))
            "the trailing record pins the depth-exceed halt boundary")
        (is (= {:n 5} (:db-before r) (:db-after r))
            "the halting event never ran — :db-before = :db-after = the
             durable last-settled db (no rollback)")
        (is (= :rf.error/drain-depth-exceeded
               (-> r :halt-reason :operation))
            ":halt-reason carries the structured halt descriptor")
        (is (= 5 (-> r :halt-reason :depth))
            ":halt-reason carries the depth at which the drain tripped")
        (is (= :loop (:event-id r))
            "the halting event's trigger pins the :halted-depth record")
        (is (= [:loop] (:trigger-event r))
            "the synthesised trigger-event is the halting event vector")
        ;; rf2-bhu3a0 — the halt record's whole frame-state is sourced from
        ;; the canonical last-settled :ok epoch record's :frame-state-after
        ;; (the same value restore rewinds to), NOT a live re-read. Pin that
        ;; both partitions equal the last-settled record's :frame-state-after.
        (let [last-ok (nth history 4)]
          (is (= (:frame-state-after last-ok)
                 (:frame-state-before r)
                 (:frame-state-after r))
              ":frame-state-before/-after are the durable last-settled
               :frame-state-after — sourced from the canonical record, not a
               live container re-read (rf2-bhu3a0)"))))))

(deftest halted-record-fires-listeners
  (testing "register-epoch-listener! listeners receive halted records too —
            devtools route off :outcome to render failure shapes. Per
            rf2-nj6p7 (per-event epochs) the listener observes each durable
            :ok event epoch as it settles, then the trailing :halted-depth
            marker."
    (rf/reg-frame :test/main {:drain-depth 5})
    (rf/reg-event :loop
      (fn [_ _] {:fx [[:dispatch [:loop]]]}))

    (let [received (atom [])]
      (rf/register-listener! :epoch ::watcher
                             (fn [record] (swap! received conj record)))
      (rf/dispatch-sync [:loop] {:frame :test/main})

      (is (= 6 (count @received))
          "five durable :ok records + the trailing :halted-depth marker
           delivered to the listener")
      (is (= (repeat 5 :ok) (mapv :outcome (take 5 @received)))
          "the events that ran surface as durable :ok records")
      (is (= :halted-depth (:outcome (last @received)))
          "listener observed the trailing :halted-depth outcome"))))

(deftest restore-non-ok-record-refused
  (testing "restore-epoch! refuses non-:ok records — halted records are
            for devtools introspection, not valid restore targets.
            Emits :rf.epoch/restore-non-ok-record and leaves app-db
            unchanged."
    (rf/reg-frame :test/main {:drain-depth 5})
    (rf/reg-event :loop
      (fn [_ _] {:fx [[:dispatch [:loop]]]}))

    ;; Drive the halted cascade to land a non-:ok record in history.
    (rf/dispatch-sync [:loop] {:frame :test/main})

    ;; Per rf2-nj6p7: per-event epochs — the :halted-depth marker is the
    ;; trailing record (the durable :ok event epochs precede it).
    (let [history     (rf/epoch-history :test/main)
          halted      (last history)
          recorded    (record-trace!)
          result      (rf/restore-epoch! :test/main (:epoch-id halted))]
      (is (= :halted-depth (:outcome halted))
          "sanity — the trailing record in history is the halted one")
      (is (false? result)
          "restore-epoch! returned false — refusal is observable to callers")
      (is (has-error-op? @recorded :rf.epoch/restore-non-ok-record)
          ":rf.epoch/restore-non-ok-record fired so listeners can surface
           the refusal to the user"))))

;; ---- :rf.epoch/outcome consumer-facing enum (rf2-18g1w) ------------------
;;
;; Per Mike's decision on rf2-jppad (2026-05-25) the runtime emits a
;; SEPARATE trace op `:rf.epoch/outcome` carrying the consumer-facing
;; `{:ok :blocked :error}` tier, derived from the detailed cause enum
;; on `:rf.epoch/snapshotted` (`:ok` / `:halted-depth` / `:halted-destroy`
;; / `:halted-handler-exception`, per Spec-Schemas §`:rf/epoch-record`
;; §Outcomes / rf2-v0jwt). The new op sits at the same cascade-trailer
;; point as `:rf.epoch/snapshotted` — both fire per dequeued event —
;; and the consuming spec (`tools/xray/spec/023-Trace-Panel.md` §13)
;; reads it directly for the EPOCH CLOSE row.
;;
;; The mapping is load-bearing — Xray's Trace panel renders off it,
;; Story chips render off it, MCP wire consumers may key off it. The
;; pure helper `re-frame.epoch.assembly/outcome->consumer-facing` is
;; the single canonical projection; these four deftests pin the
;; full mapping table at the helper level. The two emit sites are
;; covered by the integration tests below.

(deftest outcome-enum-projection-pins-mapping
  (testing "the pure helper is total over the schema's four cause values
            and projects them onto the {:ok :blocked :error} consumer-
            facing tier per rf2-18g1w / rf2-jppad (the rationale lives
            in `re-frame.epoch.assembly/outcome->consumer-facing`)"
    (testing ":ok → :ok (the cascade settled cleanly)"
      (is (= :ok (assembly/outcome->consumer-facing :ok))))
    (testing ":halted-depth → :blocked (drain hit the depth limit)"
      (is (= :blocked (assembly/outcome->consumer-facing :halted-depth))))
    (testing ":halted-destroy → :blocked (frame destroyed mid-drain)"
      (is (= :blocked (assembly/outcome->consumer-facing :halted-destroy))))
    (testing ":halted-handler-exception → :error (schema-reserved)"
      (is (= :error (assembly/outcome->consumer-facing :halted-handler-exception))))))

(deftest rf-epoch-outcome-emits-on-ok-cascade
  (testing "every :ok cascade emits a paired :rf.epoch/outcome trace
            alongside :rf.epoch/snapshotted, carrying the consumer-facing
            :outcome :ok tag. The two emits share the same :frame /
            :rf.epoch/id / :rf.trace/event-id so consumers can correlate."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [recorded (record-trace!)]
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})

      (let [snapshotted (filter #(= :rf.epoch/snapshotted (:operation %)) @recorded)
            outcomes    (filter #(= :rf.epoch/outcome (:operation %)) @recorded)]
        (is (= 2 (count snapshotted))
            "one :rf.epoch/snapshotted per dequeued event")
        (is (= 2 (count outcomes))
            "one paired :rf.epoch/outcome per dequeued event")
        (is (every? #(= :ok (get-in % [:tags :outcome])) outcomes)
            "every :ok cascade projects to consumer-facing :outcome :ok")
        (is (= (mapv #(get-in % [:tags :rf.epoch/id]) snapshotted)
               (mapv #(get-in % [:tags :rf.epoch/id]) outcomes))
            ":rf.epoch/id is shared between the paired emits — consumers
             correlate snapshotted detail ↔ outcome summary")))))

(deftest rf-epoch-outcome-emits-on-halted-depth
  (testing "a depth-exceeded drain emits :rf.epoch/outcome :blocked
            for the trailing halt marker (the :halted-depth cause
            projects to the :blocked consumer tier per rf2-18g1w).
            The durable :ok events that preceded it emit :outcome :ok."
    (rf/reg-frame :test/main {:drain-depth 5})
    (rf/reg-event :loop
      (fn [{:keys [db]} _]
        {:db (update (or db {}) :n (fnil inc 0))
         :fx [[:dispatch [:loop]]]}))

    (let [recorded (record-trace!)]
      (rf/dispatch-sync [:loop] {:frame :test/main})

      (let [outcomes (filterv #(= :rf.epoch/outcome (:operation %)) @recorded)]
        (is (= 6 (count outcomes))
            "five durable :ok event outcomes + one trailing :blocked
             halt-marker outcome")
        (is (= (repeat 5 :ok) (mapv #(get-in % [:tags :outcome]) (take 5 outcomes)))
            "the durable per-event epochs emit :outcome :ok")
        (is (= :blocked (get-in (last outcomes) [:tags :outcome]))
            "the trailing :halted-depth halt-marker projects to :blocked")))))

(deftest rf-epoch-outcome-emits-on-halted-destroy
  (testing "a mid-drain destroy emits :rf.epoch/outcome :blocked alongside
            the partial :halted-destroy :rf.epoch/snapshotted record (the
            :halted-destroy cause projects to the :blocked consumer tier
            per rf2-18g1w). The frame's ring buffer is dropped in the
            same destroy step, so :epoch-history is empty — the outcome
            survives in the trace stream as evidence."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :self-destruct
      (fn [_ _]
        (rf/destroy-frame! :test/short-lived)
        {}))

    (let [recorded (record-trace!)]
      (rf/dispatch-sync [:self-destruct] {:frame :test/short-lived})

      (let [outcomes (filterv #(= :rf.epoch/outcome (:operation %)) @recorded)]
        (is (seq outcomes)
            ":rf.epoch/outcome fired for the mid-drain destroy")
        (is (some #(= :blocked (get-in % [:tags :outcome])) outcomes)
            "the :halted-destroy cause projects to :blocked at the consumer
             tier per rf2-18g1w / rf2-jppad")))))

(deftest committed-at-on-halted-destroy-is-destroying-token-time
  (testing "the :halted-destroy partial record committed when a handler
            destroys its OWN frame mid-drain carries the DESTROYING event's
            causal token :time-ms as :committed-at — threaded via the
            router-bound frame/*run-time-ms*, NOT an ambient now-ms read
            at assembly time (rf2-bh56rc / EP-0010 §Time). The ring is
            dropped in the same destroy step, so the record is observed via
            a register-epoch-listener! callback."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :self-destruct
      (fn [_ _]
        (rf/destroy-frame! :test/short-lived)
        {}))
    (let [token-time 1781078400123
          clock-time 7777777777777
          halted     (atom [])]
      (rf/register-listener! :epoch ::watch-committed-at
                                   (fn [r]
                                     (when (= :halted-destroy (:outcome r))
                                       (swap! halted conj r))))
      ;; Stub the ambient clock to a sentinel; supply the destroying event's
      ;; causal token time. A regression that re-read now-ms at assembly
      ;; would stamp the sentinel.
      (with-redefs [interop/now-ms (constantly clock-time)]
        (rf/dispatch-sync [:self-destruct]
                          {:frame            :test/short-lived
                           :rf.cofx {:rf/time-ms token-time}}))
      (is (= 1 (count @halted))
          "exactly one :halted-destroy record reached the listener")
      (let [r (first @halted)]
        (is (= token-time (:committed-at r))
            ":committed-at is the destroying event's token :time-ms")
        (is (not= clock-time (:committed-at r))
            ":committed-at is NOT the ambient host clock")))))

;; ---- :halted-handler-exception is schema-reserved, never emitted ---------
;;
;; Per Spec-Schemas §`:rf/epoch-record` §Outcomes (rf2-v0jwt) and Spec 009
;; §register-epoch-listener!: the reference runtime commits exactly three
;; drain-boundary outcomes — :ok / :halted-depth / :halted-destroy.
;; `:halted-handler-exception` is a SCHEMA-RESERVED enum value held for a
;; future runtime path that aborts the drain on handler throw; today's
;; runtime routes handler exceptions through the interceptor error-capture
;; seam (the drain does NOT abort), so the cascade settles `:ok` with the
;; `:rf.error/handler-exception` trace under `:trace-events`. This test
;; pins that contract directly so the dead outcome can't silently start
;; being emitted (rf2-zymix) — the dual of `depth-exceeded-commits-halted-
;; record` for the one halt the runtime deliberately does NOT model.

(deftest handler-exception-settles-ok-never-halted-handler-exception
  (testing "an event handler that throws does NOT halt the drain: the
            cascade settles with :outcome :ok (the interceptor chain
            captured the throw via the error-capture seam) and the
            failure surfaces as a :rf.error/handler-exception trace under
            :trace-events. The schema-reserved :halted-handler-exception
            outcome is never committed by the reference runtime."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :boom (fn [{:keys [db]} _] {:db (throw (ex-info "handler blew" {:why :test}))}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    ;; The throw is captured by the chain — dispatch-sync returns normally,
    ;; the drain does not abort.
    (rf/dispatch-sync [:boom] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)
          boom-r  (last history)]
      (is (= 2 (count history))
          "the throwing cascade still commits exactly one epoch record")
      (is (= :boom (:event-id boom-r))
          "the throwing event is the cascade trigger")
      (is (= :ok (:outcome boom-r))
          ":outcome is :ok — the drain settled cleanly despite the throw")
      (is (not= :halted-handler-exception (:outcome boom-r))
          ":halted-handler-exception is schema-reserved, never emitted")
      (is (nil? (:halt-reason boom-r))
          "no :halt-reason on a clean settle — there was no drain halt")
      (is (has-error-op? (:trace-events boom-r) :rf.error/handler-exception)
          "the throw surfaces as a :rf.error/handler-exception trace under
           :trace-events, not as a halt outcome")
      (is (not-any? (fn [r] (= :halted-handler-exception (:outcome r)))
                    history)
          "no record across the whole ring carries the reserved outcome"))))

;; ---- rf2-xs7fn: flow-throw epoch shape (regression-guard) ----------------
;;
;; Sibling to `handler-exception-settles-ok-never-halted-handler-exception`
;; above: pins the epoch-record observability shape of a flow-throw event.
;;
;; Per the confirmed atomicity contract (Spec 013 §Failure semantics;
;; `bd remember --key event-pipeline-atomicity`) a flow's `:derive` throw is
;; a PRE-INSTALL failure — the router's `flows-after-interceptor` dissoc-s
;; the pending `:db` so the single deferred install installs NOTHING. The
;; cascade-level `:rf.error/flow-eval-exception` is emitted by
;; `emit-flow-eval-exception!` (router.cljc) and `commit-and-flow!` skips
;; `:fx`. `settle-event-epoch!` (router.cljc:1175) receives only the
;; `(frame-id db-before db-after)` triple — the outcome slot is not
;; threaded through — so the resulting epoch record's `:outcome` is `:ok`
;; and the error rides `:trace-events`. Pinned downstream consequence:
;;
;;   1. `:db-before == :db-after`   (the pending `:db` was discarded — no
;;                                   handler write and no flow write landed)
;;   2. `:outcome :ok`              (current intentional behaviour — the
;;                                   flow-throw failure rides `:trace-events`,
;;                                   not the outcome slot; mirrors the
;;                                   handler-exception pin above)
;;   3. `:trace-events` contains a `:rf.error/flow-eval-exception` entry
;;
;; This contract could quietly regress (e.g. a future widening of
;; `settle!`'s outcome surface that rolled `:flow-error` into one of the
;; `:halted-*` enums); the test names it directly. The companion
;; observability surfaces are pinned elsewhere — the always-on event-emit
;; record's `:flow-error` outcome lives in
;; `core/test/re_frame/event_emit_test.cljc`; the trace-stream + app-db
;; invariants live in `ssr/test/re_frame/flows_integration_test.clj`.

(deftest flow-throw-epoch-shape
  (testing "a flow whose :derive throws aborts the event pre-install: the
            epoch settles with :db-before == :db-after (the pending :db was
            discarded — no handler write and no flow write landed), :outcome
            is :ok (current intentional behaviour — flow-throw rides
            :trace-events, not the outcome slot, sibling to
            handler-exception-settles-ok-never-halted-handler-exception),
            and :trace-events carries a :rf.error/flow-eval-exception entry"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    ;; Handler returns a :db effect (so the normal flow path would
    ;; transform it). The flow's :derive throws, so the router DISCARDS
    ;; the pending :db wholesale.
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
    ;; Seed a clean baseline FIRST, then register the throwing flow — the
    ;; flow throws on every eval, so registering it after the seed keeps
    ;; the baseline untouched and isolates the throw to the :bump drain.
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/reg-flow :boom {:frame :test/main :inputs [[:n]] :output-path [:derived :doomed]} (fn [_] (throw (ex-info "flow boom" {:why :test}))))
    (rf/dispatch-sync [:bump] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)
          boom-r  (last history)]
      (is (= 2 (count history))
          "the flow-throw cascade still commits exactly one epoch record
           (one per dequeued event — the throw is observable, not silent)")
      (is (= :bump (:event-id boom-r))
          "the flow-throwing event is the cascade trigger")
      ;; Property 1 — :db-before == :db-after (no install happened).
      (is (= (:db-before boom-r) (:db-after boom-r))
          ":db-before == :db-after — the pending :db (handler write +
           flow write) was DISCARDED wholesale; no partial commit")
      (is (= {:n 0} (:db-after boom-r))
          "the seeded baseline is what landed in :db-after — the handler's
           :n inc did NOT take effect (no install)")
      ;; Property 2 — :outcome :ok (current intentional behaviour;
      ;; flow-throw rides :trace-events, not the outcome slot).
      (is (= :ok (:outcome boom-r))
          ":outcome is :ok — the drain settled cleanly; flow-throw rides
           :trace-events, not the outcome slot (sibling of the handler-
           exception contract pinned above)")
      (is (nil? (:halt-reason boom-r))
          "no :halt-reason on a clean settle — there was no drain halt")
      ;; Property 3 — the cascade-level error rides :trace-events.
      (is (has-error-op? (:trace-events boom-r) :rf.error/flow-eval-exception)
          "the flow throw surfaces as a :rf.error/flow-eval-exception trace
           under :trace-events"))))

(deftest clean-flow-eval-epoch-shape-contrast
  (testing "contrast (sibling of flow-throw-epoch-shape above): a clean
            flow eval produces an epoch whose :db-after CARRIES the flow's
            output value — :db-before != :db-after when the flow installs
            its computed value at :output-path. Names the success-path counterpart
            so a future regression that left :db-after equal to :db-before
            for clean flow evals is caught alongside the throw-path pin."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:w 2 :h 3}}))
    (rf/reg-event :bump-w (fn [{:keys [db]} _] {:db (update db :w inc)}))
    (rf/reg-flow :rect/area {:frame :test/main :inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* (or w 0) (or h 0))))

    (rf/dispatch-sync [:seed]   {:frame :test/main})  ;; area=6
    (rf/dispatch-sync [:bump-w] {:frame :test/main})  ;; area=9

    (let [history (rf/epoch-history :test/main)
          bump-r  (last history)]
      (is (= :bump-w (:event-id bump-r)))
      (is (= :ok (:outcome bump-r)))
      (is (not= (:db-before bump-r) (:db-after bump-r))
          ":db-before != :db-after — a clean flow eval installs its
           augmented value into the post-cascade db")
      (is (= 9 (get-in (:db-after bump-r) [:rect :area]))
          "the flow's computed output landed at :output-path in :db-after")
      (is (not (has-error-op? (:trace-events bump-r)
                              :rf.error/flow-eval-exception))
          "no :rf.error/flow-eval-exception on a clean flow eval"))))

;; ---- rejected / aborted dispatch commits no epoch (rf2-zymix) ------------
;;
;; Per `settle!`'s empty-buffer policy (epoch.cljc) — a drain boundary
;; whose capture buffer holds no cascade context is SKIPPED rather than
;; committing a record with no :event-id / :trigger-event. This is the
;; "no misleading record on a rejected / aborted dispatch" guard: on the
;; routine cascade-abort case (dispatch-sync rejection, an aborted child
;; that never fired :event/run-start) the capture buffer is empty, so when
;; `settle!` fires at the abort boundary the harvested buffer is empty and
;; no record is committed. The invariant was only covered indirectly (cross-
;; contamination / leaked-buffer tests); this names it directly by driving
;; the empty-buffer settle! seam.

(deftest empty-buffer-settle-commits-no-epoch
  (testing "settle! at a drain boundary whose capture buffer is empty —
            the reality on a rejected / aborted dispatch that buffered
            no cascade context — commits NO epoch record. A
            record with no :event-id / :trigger-event would misrepresent
            a cascade that never ran; the empty-buffer skip in settle!
            (epoch.cljc) suppresses it. The ring stays empty for the frame."
    (rf/reg-frame :test/main {})
    ;; Start from a known-empty capture buffer for this frame — the
    ;; state on a rejected/aborted dispatch that buffered no cascade
    ;; context. (`reg-frame` emits a :rf.frame/created trace that
    ;; capture-event! would buffer; reset so the buffer is genuinely
    ;; empty, mirroring the empty-buffer abort case.)
    (reset! @#'state/capture-buffers {})

    ;; settle! fires at the abort boundary with no buffered cascade
    ;; context — the empty-buffer skip suppresses the commit. (rf2-bh56rc:
    ;; the clean arity now takes committed-at; nil here — nothing commits,
    ;; so the value is unused.)
    (epoch/settle! :test/main {} {} nil)

    (is (empty? (rf/epoch-history :test/main))
        "no epoch record committed for an empty-buffer drain boundary —
         the rejected/aborted-dispatch no-misleading-record guard")

    ;; A real cascade afterwards DOES commit — proving the skip is an
    ;; empty-buffer policy, not a frame-wide disable.
    (rf/reg-event :real (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/dispatch-sync [:real] {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 1 (count history))
          "the subsequent real cascade commits exactly one record")
      (is (= :real (:event-id (first history)))
          "the committed record is the real cascade, not the suppressed
           empty-buffer boundary"))))

;; ---- no-handler dispatch commits no fake ok epoch (rf2-erczwd) -------------
;;
;; A dispatch to an UNREGISTERED event on a LIVE frame early-exits via
;; `diag/handle-no-handler!` — it never fires `:event/run-start`, but it DOES
;; emit a `:rf.error/no-such-handler` trace that is frame-stamped AND carries
;; the cascade scope's `:dispatch-id` (bound by `process-event!`). That trace
;; buffers into epoch capture, so the buffer is NON-EMPTY at the settle seam.
;; The prior no-run-start harvest returned the whole buffer, and `settle!`
;; committed any non-empty harvest — synthesising a misleading `:ok` epoch
;; (with a fallback `:event-id` derived from the error trace) for a dispatch
;; that never ran, contradicting the no-run-start / no-epoch invariant. The fix
;; threads the settling envelope's `:dispatch-id` into the scoped harvest so
;; the rejection's own trace is DROPPED (not returned), the empty-buffer skip
;; fires, and no epoch / listener advances.

(deftest no-handler-dispatch-commits-no-epoch
  (testing "rf2-erczwd — dispatching an UNREGISTERED event on an existing frame
            records NO epoch and fires NO epoch listener, even though the
            no-such-handler error trace buffers into epoch capture. The
            no-run-start / no-epoch invariant holds through the real router."
    (rf/reg-frame :test/main {})
    (rf/reg-event :real (fn [{:keys [db]} _] {:db {:n 1}}))
    ;; A real cascade first, so history is non-empty — we then prove the
    ;; no-handler dispatch does NOT advance it (and does not overwrite it).
    (rf/dispatch-sync [:real] {:frame :test/main})
    (let [fired          (atom [])
          history-before (rf/epoch-history :test/main)]
      (is (= 1 (count history-before))
          "the real cascade committed exactly one record")
      (rf/register-listener! :epoch ::probe (fn [r] (swap! fired conj r)))
      ;; Dispatch an event with NO registered handler on the LIVE frame.
      ;; Recovers (`:replaced-with-default`) — no throw — but emits the
      ;; frame-stamped, dispatch-id-bearing no-such-handler error trace.
      (rf/dispatch-sync [:no/such-handler 42] {:frame :test/main})
      (let [history-after (rf/epoch-history :test/main)]
        (is (= history-before history-after)
            "epoch history did NOT advance for the no-handler dispatch —
             no fake :ok epoch committed")
        (is (= 1 (count history-after))
            "still exactly one record (the real cascade)")
        (is (= :real (:event-id (last history-after)))
            "the last record is still the real cascade, not a synthesised
             no-such-handler record with a fallback :event-id")
        (is (empty? @fired)
            "no epoch listener fired for the rejected dispatch"))
      (rf/unregister-listener! :epoch ::probe))
    ;; A subsequent real dispatch STILL commits — proving the suppression is
    ;; scoped to the rejected dispatch, not a frame-wide disable.
    (rf/dispatch-sync [:real] {:frame :test/main})
    (is (= 2 (count (rf/epoch-history :test/main)))
        "a real cascade after the rejected dispatch commits normally")))

(deftest no-run-start-harvest-scopes-drop-to-settling-dispatch
  (testing "rf2-erczwd (unit) — harvest-buffer-for-event! given a settling
            dispatch-id, on a NO-RUN-START buffer, DROPS the settling
            dispatch's own traces (its error trace) + orphans and RETAINS an
            unrelated child marker, returning [] so settle! commits nothing."
    (let [frame   :test/scoped-harvest
          ;; The rejected dispatch's OWN error trace — carries the settling id,
          ;; no run-start.
          own-err {:op-type :error :operation :rf.error/no-such-handler
                   :tags {:rf.trace/dispatch-id :S :rf.trace/event-id :no/such}}
          ;; An unrelated child's queue-time marker buffered during an earlier
          ;; parent's do-fx — carries a DIFFERENT id; must survive for its
          ;; own settle.
          child   {:op-type :rf.event :operation :rf.event/dispatched
                   :tags {:rf.trace/dispatch-id :C :rf.trace/event-id :child}}
          ;; An orphan (nil dispatch-id) — must be dropped (self-cleaning).
          orphan  {:op-type :rf.frame :operation :rf.frame/created
                   :tags {:frame frame}}]
      (state/buffer-event! frame own-err)
      (state/buffer-event! frame child)
      (state/buffer-event! frame orphan)
      (let [returned (state/harvest-buffer-for-event! frame :S)]
        (is (= [] returned)
            "no cascade ran — nothing returned, so settle! commits no fake epoch")
        (is (= [child] (state/buffer-for frame))
            "the unrelated child marker is RETAINED; the settling dispatch's own
             error trace + the orphan are dropped"))
      (state/drop-frame-buffer! frame))))

;; ---- recording is gated on debug-enabled? ---------------------------------

(deftest configure-roundtrip
  (testing "(rf/configure! {:epoch-history {:depth N}}) updates the depth"
    (rf/configure! {:epoch-history {:depth 7}})
    (is (= 7 (:depth (epoch/current-config))))
    (rf/configure! {:epoch-history {:depth 12}})
    (is (= 12 (:depth (epoch/current-config))))))

;; ---- rf2-iegsz / rf2-mrsck: :trace-events elision policy -----------------
;;
;; Per Spec-Schemas §`:rf/epoch-record` line 2224, `:trace-events` is
;; optional — 'implementations may choose to drop traces from older
;; epochs'. Per rf2-mrsck and Security.md §Epoch privacy posture the
;; default is now FINITE (5): the most-recent five records per frame
;; retain raw `:trace-events`; older records keep their cheap
;; structured projections (`:sub-runs` / `:renders` / `:effects`)
;; but lose the raw trace stream. Apps that want the whole ring's
;; raw streams pass an explicit larger value (or one >= the depth
;; cap). Setting the slot to `0` drops every record's
;; `:trace-events`.

(deftest trace-events-keep-elides-older-records
  (testing "with :trace-events-keep N set, only the most-recent N records
            carry :trace-events; older records keep :sub-runs / :renders /
            :effects but drop :trace-events"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/configure! {:epoch-history {:depth 10 :trace-events-keep 2}})

    ;; Drive 5 cascades so the buffer has 5 records and only the last 2
    ;; should retain :trace-events.
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 5 (count history))
          "all 5 records remain in the ring (depth 10)")
      (is (every? #(contains? % :sub-runs) history)
          "every record keeps its :sub-runs projection")
      (is (every? #(contains? % :effects) history)
          "every record keeps its :effects projection")
      (is (every? #(contains? % :renders) history)
          "every record keeps its :renders projection")

      (let [[r0 r1 r2 r3 r4] history]
        (is (not (contains? r0 :trace-events))
            "record 0 (oldest) — :trace-events dropped")
        (is (not (contains? r1 :trace-events))
            "record 1 — :trace-events dropped")
        (is (not (contains? r2 :trace-events))
            "record 2 — :trace-events dropped")
        (is (contains? r3 :trace-events)
            "record 3 — :trace-events kept (penultimate)")
        (is (contains? r4 :trace-events)
            "record 4 — :trace-events kept (most-recent)")))))

(deftest trace-events-keep-finite-cap-elides-older-records
  (testing "a FINITE :trace-events-keep (the fixture configures 5) — drive
            >keep cascades and the oldest records lose :trace-events while
            keeping the structured projections (per rf2-mrsck and Security.md
            §Epoch privacy posture).

            NOTE: this pins the keep<depth ELISION behaviour against the
            fixture-configured cap of 5, NOT 'the default'. The real
            shipped default is 50 (= :depth, see
            `re-frame.epoch.state/default-trace-events-keep`; Mike pair-debug
            2026-05-27), at which trace + epoch evict atomically and no
            retained record drops its :trace-events. The fixture forces 5 so
            this elision path is reachable with a handful of dispatches."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    ;; Fixture-configured cap (NOT the shipped default of 50). Reset-runtime
    ;; forces :trace-events-keep 5 so this elision path is exercised cheaply.
    (is (= 5 (:trace-events-keep (epoch/current-config)))
        "fixture-configured :trace-events-keep is 5 (the shipped default is 50)")

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (dotimes [_ 6] (rf/dispatch-sync [:inc] {:frame :test/main}))

    (let [history (rf/epoch-history :test/main)
          n       (count history)
          tail-5  (subvec history (- n 5) n)
          older   (subvec history 0 (- n 5))]
      (is (= 7 n) "all 7 records remain in the ring (depth 50)")
      (is (every? #(contains? % :sub-runs) history)
          "every record keeps its :sub-runs projection")
      (is (every? #(contains? % :renders) history)
          "every record keeps its :renders projection")
      (is (every? #(contains? % :effects) history)
          "every record keeps its :effects projection")
      (is (every? #(contains? % :trace-events) tail-5)
          "the most-recent 5 records keep :trace-events")
      (is (every? #(not (contains? % :trace-events)) older)
          "older records (beyond the keep-5 window) drop :trace-events"))))

(deftest trace-events-keep-explicit-large-value-keeps-all
  (testing "explicit :trace-events-keep >= depth — every record carries
            :trace-events (the opt-back-in path for apps that want the
            whole ring's raw streams)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    ;; Opt back into unbounded retention.
    (rf/configure! {:epoch-history {:trace-events-keep 100}})

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 3 (count history)))
      (is (every? #(contains? % :trace-events) history)
          "every record carries :trace-events — keep is large enough"))))

;; ---- rf2-b2c02: value-changed scan bound tracks the ELISION boundary ------
;;
;; `value-changed-epoch-for` (state.cljc) scans the ring newest-first for the
;; epoch that genuinely re-rendered a view, bounded so it only walks records
;; that still carry `:trace-events` (the matchable set). The scan bounds on the
;; directly-observable elision state (rf2-b2c02 R2): it breaks at the first
;; record MISSING `:trace-events`, so it tracks reality under any reconfigure.
;; An index bound at `(- n keep)` (rf2-3rg4j) is correct only in STEADY STATE:
;; after a RUNTIME `:trace-events-keep` REDUCTION, elision is non-retroactive
;; (`elide-just-crossed-trace-events`'s docstring), so records that were inside
;; the OLD keep-window still carry `:trace-events` yet now sit BELOW
;; `(- n new-keep)` — an index bound would skip those still-trace-bearing
;; records and miss a genuine value-change, mis-attributing the render.
;;
;; rf2-yw1w1u — the two tests below KEEP direct private-var access
;; (`@#'state/histories`, `@#'state/config`, `@#'state/value-changed-epoch-for`)
;; rather than the shared fixture / `configure!` boundary. They are
;; narrow unit tests of the PRIVATE `value-changed-epoch-for` scan: they
;; hand-stuff a bespoke `histories` ring (a post-reduction transient gap
;; / a manually-elided boundary) that no public API can construct, then
;; pin the private config to the exact keep the scenario needs. Routing
;; the config line through `configure!` while the rest of the setup is
;; raw private-atom manipulation would be inconsistent and add no
;; isolation — these cases live entirely below the public surface.

(defn- vc-record
  "A minimal epoch record carrying a value-changed `:rf.sub/run` for
  `render-key` in its `:trace-events` — the shape `value-changed-epoch-for`
  matches (via `epoch-value-changed-for-view?`)."
  [frame-id epoch-id render-key]
  {:frame        frame-id
   :epoch-id     epoch-id
   :trace-events [{:op-type   :rf.sub
                   :operation :rf.sub/run
                   :tags      {:rf.sub/value-changed?    true
                               :rf.sub/reader-render-key render-key
                               :frame                    frame-id}}]})

(defn- plain-record
  "A minimal epoch record whose `:trace-events` carries NO value-change for
  `render-key` — a record that is present + trace-bearing but never a hit."
  [frame-id epoch-id]
  {:frame        frame-id
   :epoch-id     epoch-id
   :trace-events []})

(deftest value-changed-scan-finds-trace-bearing-epoch-below-reduced-keep
  (testing "rf2-b2c02 — after a runtime :trace-events-keep REDUCTION, the
            value-changed scan still finds an epoch that sits below the new
            (- n keep) index but STILL carries :trace-events (elision is
            non-retroactive). The index-derived bound rf2-3rg4j shipped would
            skip it; the elision-boundary bound finds it."
    (let [frame-id   :test/main
          render-key [:counter-view 0]
          ;; A ring of 8 records, all still trace-bearing (elision has not yet
          ;; re-run for the reduced keep). The value-change for the view lives
          ;; in record index 2 — well below a freshly-reduced keep window.
          history    (into [(vc-record frame-id :e0 render-key)        ;; idx 0
                            (plain-record frame-id :e1)                ;; idx 1
                            (vc-record frame-id :e2 render-key)]       ;; idx 2 ← target
                           (map #(plain-record frame-id (keyword (str "e" %)))
                                (range 3 8)))]                          ;; idx 3..7
      (reset! @#'state/histories {frame-id history})
      ;; new-keep = 3 → the index bound would be lo = (- 8 3) = 5, skipping
      ;; idx 2 even though it still carries :trace-events.
      (reset! @#'state/config {:depth 50 :trace-events-keep 3 :redact-fn nil})
      (is (= :e2 (@#'state/value-changed-epoch-for frame-id render-key))
          "the scan reaches the still-trace-bearing value-change at idx 2,
           below (- n new-keep) = 5 — the post-reduction transient gap"))))

(deftest value-changed-scan-stops-at-elision-boundary
  (testing "rf2-b2c02 — the scan does NOT walk past the elision boundary: a
            record whose :trace-events was elided (slot absent) terminates the
            scan, and a value-change ONLY present in an elided (older) record is
            correctly NOT found. This pins the O(keep) bound the fix preserves."
    (let [frame-id   :test/main
          render-key [:counter-view 0]
          ;; idx 0 carries a value-change but was ELIDED (no :trace-events slot);
          ;; idx 1 is the elision boundary (also elided); idx 2..4 trace-bearing
          ;; but carry no value-change for the view.
          elided-vc  (dissoc (vc-record frame-id :e0 render-key) :trace-events)
          elided     (dissoc (plain-record frame-id :e1) :trace-events)
          history    [elided-vc                       ;; idx 0 — elided, has a (lost) vc
                      elided                           ;; idx 1 — elided boundary
                      (plain-record frame-id :e2)      ;; idx 2 — trace-bearing, no vc
                      (plain-record frame-id :e3)      ;; idx 3
                      (plain-record frame-id :e4)]     ;; idx 4
          ]
      (reset! @#'state/histories {frame-id history})
      (reset! @#'state/config {:depth 50 :trace-events-keep 5 :redact-fn nil})
      (is (nil? (@#'state/value-changed-epoch-for frame-id render-key))
          "the value-change lives only in an ELIDED record — the scan stops at
           the boundary and reports no hit (the elided record cannot match)"))))

;; ---- restore-epoch! reactive surfaces (rf2-2fat) ---------------------------
;;
;; Bead rf2-2fat coverage. Tool-Pair §Time-travel says restore "rewinds the
;; frame's app-db to the named epoch's :db-after value." Spec 006 §Subscription
;; cache pins invalidation to :replace-container! — and restore-epoch! goes
;; through the same adapter/replace-container! choke point used by the drain
;; loop's :db commit. The two together imply: every reactive surface that
;; observes app-db (subscriptions, flows materialised at :output-path, route slice
;; reads) must reflect the rewound value after restore-epoch! returns true,
;; without a separate cache-invalidation call.
;;
;; These tests pin that downstream contract on the JVM with the plain-atom
;; adapter. The plain-atom adapter recomputes derived values on every deref
;; (no cache), so subscribe-once before/after restore is a clean read of
;; the post-restore container. The CLJS Reagent counterpart in
;; runtime_cljs_test.cljs covers the reactive-graph case where a held
;; reaction must observe the rewound value.

(deftest restore-rewinds-subscriptions-via-subscribe-once
  (testing "after restore-epoch!, subscribe-once reflects the restored db
  for both layer-1 and layer-2 subs (no manual cache invalidation)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n   (fn [db _] (:n db)))
    (rf/reg-sub :n*2 :<- [:n] (fn [n _] (* 2 (or n 0))))

    (rf/dispatch-sync [:seed] {:frame :test/main})  ;; n=0
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=1
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=2
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=3

    (is (= 3 (rf/subscribe-once [:n] {:frame :test/main})))
    (is (= 6 (rf/subscribe-once [:n*2] {:frame :test/main})))

    (let [history (rf/epoch-history :test/main)
          ;; Pick the epoch where :n landed at 1 (second :inc dispatch).
          target  (some (fn [r] (when (= 1 (:n (:db-after r))) r)) history)]
      (is (true? (rf/restore-epoch! :test/main (:epoch-id target))))
      (is (= 1 (rf/subscribe-once [:n] {:frame :test/main}))
          "layer-1 sub now sees the restored value (no manual invalidation)")
      (is (= 2 (rf/subscribe-once [:n*2] {:frame :test/main}))
          "layer-2 sub recomputes against the restored input"))))

(deftest restore-rewinds-pinned-reaction
  (testing "a subscription held across restore re-derefs to the restored
  value. Pins the contract that restore-epoch! goes through the same
  app-db write path as the drain loop, so any consumer holding a
  subscription before the restore observes the rewind on the next deref
  without re-subscribing."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=1
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=2

    (let [pinned  (rf/subscribe [:n] {:frame :test/main})
          _       (is (= 2 @pinned) "pinned reaction sees current value")
          history (rf/epoch-history :test/main)
          target  (some (fn [r] (when (= 1 (:n (:db-after r))) r)) history)]
      (is (true? (rf/restore-epoch! :test/main (:epoch-id target))))
      (is (= 1 @pinned)
          "the same reaction handle now derefs to the restored value")
      (rf/unsubscribe :test/main [:n]))))

(deftest restore-frame-isolation
  (testing "restoring frame A leaves frame B's app-db and subscriptions
  untouched. Per Tool-Pair §Time-travel: time-travel is a frame-local
  primitive — there is no global epoch sequence."
    (rf/reg-frame :frame/a {})
    (rf/reg-frame :frame/b {})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    ;; Drive A through 0 → 1 → 2; B through 100 → 101.
    (rf/dispatch-sync [:seed 0]   {:frame :frame/a})
    (rf/dispatch-sync [:inc]      {:frame :frame/a})
    (rf/dispatch-sync [:inc]      {:frame :frame/a})
    (rf/dispatch-sync [:seed 100] {:frame :frame/b})
    (rf/dispatch-sync [:inc]      {:frame :frame/b})

    (let [a-history (rf/epoch-history :frame/a)
          ;; The epoch where A's n landed at 1 (first :inc).
          a-target  (some (fn [r] (when (= 1 (:n (:db-after r))) r)) a-history)]
      (is (true? (rf/restore-epoch! :frame/a (:epoch-id a-target))))
      (is (= 1   (rf/subscribe-once [:n] {:frame :frame/a}))
          "frame A's sub sees the rewound value")
      (is (= 101 (rf/subscribe-once [:n] {:frame :frame/b}))
          "frame B's sub is unchanged by the cross-frame restore")
      (is (= 101 (:n (rf/app-db-value :frame/b)))
          "frame B's app-db is unchanged"))))

(deftest restore-fixed-point-same-epoch-twice
  (testing "restoring twice to the same epoch is a no-op semantically:
  the second call lands on the same db-after, every observable surface
  reads the same value, and the call still returns true."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=1
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=2
    (rf/dispatch-sync [:inc]  {:frame :test/main})  ;; n=3

    (let [history (rf/epoch-history :test/main)
          target  (some (fn [r] (when (= 1 (:n (:db-after r))) r)) history)
          target-eid (:epoch-id target)]
      (is (true? (rf/restore-epoch! :test/main target-eid)) "first restore ok")
      (let [db-after-1 (rf/app-db-value :test/main)
            sub-1      (rf/subscribe-once [:n] {:frame :test/main})]
        (is (= {:n 1} db-after-1))
        (is (= 1     sub-1))

        ;; Restore again to the SAME epoch.
        (is (true? (rf/restore-epoch! :test/main target-eid)) "second restore ok")
        (is (= db-after-1 (rf/app-db-value :test/main))
            "app-db unchanged across the second restore")
        (is (= sub-1 (rf/subscribe-once [:n] {:frame :test/main}))
            "sub value unchanged across the second restore")))))

(deftest restore-rewinds-flow-output-in-app-db
  (testing "Per Spec 013 — a flow's value lives in app-db at :output-path, where
  it 'survives ... time-travel revert.' After restore-epoch!, the flow's
  output reads through the restored db match the recorded epoch's output."
    (rf/reg-frame :test/main {})
    (rf/reg-event :init (fn [{:keys [db]} _]      {:db {:w 2 :h 3}}))
    (rf/reg-event :w!   (fn [{:keys [db]} [_ w]] {:db (assoc db :w w)}))
    (rf/reg-event :h!   (fn [{:keys [db]} [_ h]] {:db (assoc db :h h)}))
    (rf/reg-flow :rect/area {:frame :test/main :inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* (or w 0) (or h 0))))

    (rf/dispatch-sync [:init]    {:frame :test/main})  ;; area=6
    (rf/dispatch-sync [:w! 5]    {:frame :test/main})  ;; area=15
    (rf/dispatch-sync [:h! 10]   {:frame :test/main})  ;; area=50
    (is (= 50 (get-in (rf/app-db-value :test/main) [:rect :area])))

    (let [history (rf/epoch-history :test/main)
          ;; Find the epoch whose db-after has area=15 (after :w! 5).
          target  (some (fn [r] (when (= 15 (get-in (:db-after r) [:rect :area])) r))
                        history)]
      (is (some? target))
      (is (true? (rf/restore-epoch! :test/main (:epoch-id target))))
      (is (= 15 (get-in (rf/app-db-value :test/main) [:rect :area]))
          "the restored db carries the flow's value at :output-path"))))

(deftest restore-then-dispatch-recomputes-flow-correctly
  (testing "After restore, the next event drain re-runs flows correctly.
  The dirty-check operates on observed inputs (:w, :h) vs last-inputs;
  even if last-inputs holds a pre-restore tuple, a real input change
  in the next dispatch triggers recomputation. Pins that flow recompute
  state does not silently miss after a restore."
    (rf/reg-frame :test/main {})
    (rf/reg-event :init (fn [{:keys [db]} _]      {:db {:w 1 :h 1}}))
    (rf/reg-event :w!   (fn [{:keys [db]} [_ w]] {:db (assoc db :w w)}))
    (rf/reg-flow :rect/area {:frame :test/main :inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* (or w 0) (or h 0))))

    (rf/dispatch-sync [:init]   {:frame :test/main})  ;; area=1
    (rf/dispatch-sync [:w! 4]   {:frame :test/main})  ;; area=4
    (rf/dispatch-sync [:w! 7]   {:frame :test/main})  ;; area=7
    (rf/dispatch-sync [:w! 9]   {:frame :test/main})  ;; area=9

    (let [history (rf/epoch-history :test/main)
          target  (some (fn [r] (when (= 4 (get-in (:db-after r) [:rect :area])) r))
                        history)]
      (is (true? (rf/restore-epoch! :test/main (:epoch-id target))))
      (is (= 4 (get-in (rf/app-db-value :test/main) [:rect :area]))
          "restored db carries the flow output value at :output-path")

      ;; Drive a new event that changes :w. The flow must recompute
      ;; against the post-restore inputs, not against any leftover
      ;; last-inputs cache from the pre-restore history.
      (rf/dispatch-sync [:w! 6] {:frame :test/main})
      (is (= 6 (get-in (rf/app-db-value :test/main) [:w])))
      (is (= 6 (get-in (rf/app-db-value :test/main) [:rect :area]))
          "flow recomputed correctly post-restore (6 * 1 = 6)"))))

(deftest restore-rewinds-route-slice-and-route-sub
  (testing "Per the bead: when a restored epoch changes the route slice, the
  observable routing state follows. EP-0001 (rf2-tfepxu): the route slice
  lives in the runtime-db partition at [:rf.runtime/routing :current] — NOT
  under a legacy app-db :rf/runtime root (now a hard error) — and a
  full-frame-state restore (decision #2) rewinds runtime-db with app-db."
    (rf/reg-frame :test/main {})
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {} "/articles/:id")
    ;; Framework-authority handlers write the route slice into the runtime-db
    ;; partition via the reserved :rf.db/runtime effect (Mike ruling #4 — the
    ;; routing subsystem is a framework/runtime-extension writer).
    (rf/reg-event :go-home
      {:rf/machine? true}
      (fn [{:keys [rf.db/runtime]} _]
        {:rf.db/runtime (assoc-in (or runtime {}) [:rf.runtime/routing :current]
                                  {:route-id :route/home :params {}})}))
    (rf/reg-event :go-article
      {:rf/machine? true}
      (fn [{:keys [rf.db/runtime]} [_ id]]
        {:rf.db/runtime (assoc-in (or runtime {}) [:rf.runtime/routing :current]
                                  {:route-id :route/article :params {:id id}})}))

    (rf/dispatch-sync [:go-home]               {:frame :test/main})
    (rf/dispatch-sync [:go-article "intro"]    {:frame :test/main})
    (is (= :route/article
           (get-in (:rf.db/runtime (rf/frame-state-value :test/main)) [:rf.runtime/routing :current :route-id]))
        "the route slice is in the runtime-db partition")

    (let [history (rf/epoch-history :test/main)
          ;; The epoch whose runtime-db carries :route/home under
          ;; [:rf.db/runtime :rf.runtime/routing :current :route-id]. Restore rewinds
          ;; the whole frame-state, so :frame-state-after is the canonical unit.
          target  (some (fn [r]
                          (when (= :route/home
                                   (get-in (:frame-state-after r)
                                           [:rf.db/runtime :rf.runtime/routing :current :route-id]))
                            r))
                        history)]
      (is (some? target))
      (is (true? (rf/restore-epoch! :test/main (:epoch-id target))))
      (is (= :route/home
             (get-in (:rf.db/runtime (rf/frame-state-value :test/main)) [:rf.runtime/routing :current :route-id]))
          "the runtime-db route slice is rewound by the full-frame-state restore"))))

;; ---- replace-app-db! (Tool-Pair §Pair-tool writes, rf2-zq55) -------------
;;
;; Per Tool-Pair §Pair-tool writes: replace-app-db! is the canonical
;; Tool-Pair write surface for state injection. The invariants below
;; cover the contract the spec commits to:
;;
;; 1. Replaces the frame's app-db with new-db.
;; 2. Records a synthetic :rf/epoch-record so restore-epoch! can rewind.
;; 3. Drain-check: rejects a call from inside a drain.
;; 4. Schema validation: rejects a new-db that fails the frame's
;;    registered app-schemas.
;; 5. Trace emission: :rf.epoch/db-replaced fires on success with
;;    :frame and :epoch-id.
;; 6. Listeners: register-epoch-listener! fires with the assembled record.
;; 7. Unknown frame: :rf.error/no-such-handler (kind :frame).

(deftest replace-frame-state-app-only-replaces-container
  (testing "replace-app-db! replaces the underlying app-db value"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (= {:n 0} (rf/app-db-value :test/main)))

    (is (true? (rf/replace-frame-state! :test/main {:rf.db/app {:n 99 :injected? true}})))
    (is (= {:n 99 :injected? true} (rf/app-db-value :test/main))
        "container holds the injected value")))

(deftest replace-frame-state-app-reset-resets-app-db-only-preserving-runtime-db
  (testing "reset-app-db! resets the app-db partition to {} while live
            runtime-db (machines / routes) survives (EP-0001 rf2-tfepxu,
            Mike ruling #10 — the app-db sibling of a full frame reset,
            destroy-frame! + reg-frame)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7 :cart {:items [1 2]}}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    ;; Seed a live runtime-db partition (framework-owned subsystem state).
    (rf/replace-frame-state! :test/main {:rf.db/runtime {:rf.runtime/machines {:m 1}}})
    (is (= {:n 7 :cart {:items [1 2]}} (rf/app-db-value :test/main)))
    (is (= {:rf.runtime/machines {:m 1}} (:rf.db/runtime (rf/frame-state-value :test/main))))

    (is (true? (rf/replace-frame-state! :test/main {:rf.db/app {}}))
        "reset-app-db! returns true on success")
    (is (= {} (rf/app-db-value :test/main))
        "app-db partition is reset to {}")
    (is (= {:rf.runtime/machines {:m 1}} (:rf.db/runtime (rf/frame-state-value :test/main)))
        "runtime-db partition is PRESERVED — reset-app-db! never touches it")))

(deftest replace-frame-state-app-reset-records-undo-epoch
  (testing "reset-app-db! records a synthetic :rf.epoch/db-replaced epoch
            (it delegates to replace-app-db! with {})"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [pre   (count (rf/epoch-history :test/main))
          _     (rf/replace-frame-state! :test/main {:rf.db/app {}})
          head  (last (rf/epoch-history :test/main))]
      (is (= (inc pre) (count (rf/epoch-history :test/main)))
          "a synthetic epoch was appended")
      (is (= :rf.epoch/db-replaced (:event-id head)))
      (is (= {:n 7} (:db-before head)) "db-before captured the prior app-db")
      (is (= {} (:db-after head)) "db-after is the {} reset value"))))

(deftest replace-frame-state-app-only-records-undo-epoch
  (testing "replace-app-db! records a synthetic epoch so restore-epoch!
            can rewind to the prior state"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [pre-history-count (count (rf/epoch-history :test/main))
          _                 (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}})
          history           (rf/epoch-history :test/main)
          fresh-record      (last history)]
      (is (= (inc pre-history-count) (count history))
          "a new record was appended")
      (is (= :rf.epoch/db-replaced (:event-id fresh-record))
          "the synthetic record's event-id sentinels the pair-tool injection")
      (is (= [:rf.epoch/db-replaced] (:trigger-event fresh-record))
          "trigger-event mirrors the sentinel")
      (is (= {:n 7}   (:db-before fresh-record)) "db-before captured")
      (is (= {:n 999} (:db-after fresh-record))  "db-after captured")

      ;; restore-epoch! on the synthetic record rewinds to db-after of
      ;; the synthetic record (not its db-before). To rewind PAST the
      ;; injection, the caller restores an earlier epoch in the history.
      (let [pre-injection (some (fn [r]
                                  (when (= :seed (:event-id r)) r))
                                history)]
        (is (some? pre-injection))
        (is (true? (rf/restore-epoch! :test/main (:epoch-id pre-injection))))
        (is (= {:n 7} (rf/app-db-value :test/main))
            "restoring the seed epoch rewinds past the pair-tool injection")))))

(deftest replace-frame-state-app-only-emits-trace
  (testing "replace-app-db! emits :rf.epoch/db-replaced on success with
            :frame and :epoch-id tags"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [recorded (record-trace!)
          _        (rf/replace-frame-state! :test/main {:rf.db/app {:n 1}})
          ev       (some (fn [ev]
                           (when (= :rf.epoch/db-replaced (:operation ev))
                             ev))
                         @recorded)]
      (is (some? ev) ":rf.epoch/db-replaced fired")
      (is (= :rf.epoch (:op-type ev)))
      (is (= :test/main (:frame (:tags ev))))
      (is (number? (:rf.epoch/id (:tags ev)))
          "trace carries the synthetic record's epoch-id"))))

(deftest replace-frame-state-app-only-fires-listeners
  (testing "replace-app-db! fans out the assembled synthetic record to
            register-epoch-listener! listeners"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [received (atom [])]
      (rf/register-listener! :epoch ::reset-listener
                            (fn [r] (swap! received conj r)))
      (try
        (rf/replace-frame-state! :test/main {:rf.db/app {:n 42}})
        (let [r (last @received)]
          (is (some? r) "a record was delivered to the listener")
          (is (= :test/main (:frame r)))
          (is (= :rf.epoch/db-replaced (:event-id r)))
          (is (= {:n 42} (:db-after r)))
          (is (= {:n 0}  (:db-before r))))
        (finally
          (rf/unregister-listener! :epoch ::reset-listener))))))

(deftest replace-frame-state-app-only-failure-unknown-frame
  (testing "replace-app-db! on an unknown frame returns false and emits
            :rf.error/no-such-handler (kind :frame); no-op on app-db"
    (let [recorded (record-trace!)
          ok?      (rf/replace-frame-state! :no.such/frame {:rf.db/app {:any 'value}})]
      (is (false? ok?))
      (is (has-error-op? @recorded :rf.error/no-such-handler))
      (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                     @recorded)]
        (is (= :frame (:kind (:tags ev))))
        (is (= :no.such/frame (:frame (:tags ev))))))))

(deftest replace-frame-state-app-only-failure-during-drain
  (testing "replace-app-db! called from inside a drain returns false and
            emits :rf.epoch/replace-during-drain; app-db unchanged
            by the rejected call"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [recorded (record-trace!)
          attempt  (atom nil)]
      (rf/reg-event :try-reset
        (fn [{:keys [db]} _]
          (reset! attempt (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}}))
          ;; If reset succeeded we'd see {:n 999} after settle (the
          ;; drain's :db-after returned by this handler is {:n 0}).
          {:db db}))
      (rf/dispatch-sync [:try-reset] {:frame :test/main})

      (is (false? @attempt) "reset returned false from inside the drain")
      (is (= {:n 0} (rf/app-db-value :test/main))
          "app-db unchanged — the in-drain reset was rejected")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/replace-during-drain
                                (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/replace-during-drain fired")
        (is (= :test/main (:frame (:tags ev))))))))

(deftest replace-frame-state-app-only-failure-schema-mismatch
  (testing "replace-app-db! with a new-db that fails the frame's
            registered schemas returns false; emits
            :rf.epoch/replace-schema-mismatch; app-db unchanged"
    (rf/reg-frame :test/main {})
    ;; Per Spec 010 §Per-frame schemas — schema is frame-scoped.
    (rf/reg-app-schema [:n] {:frame :test/main} [:int])
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [pre      (rf/app-db-value :test/main)
          recorded (record-trace!)
          ok?      (rf/replace-frame-state! :test/main {:rf.db/app {:n "not-an-int"}})]
      (is (false? ok?) "reset rejected on schema mismatch")
      (is (= pre (rf/app-db-value :test/main))
          "app-db unchanged after a rejected reset")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/replace-schema-mismatch
                                (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/replace-schema-mismatch fired")
        (is (= :test/main (:frame (:tags ev))))
        (is (vector? (:failing-paths (:tags ev)))
            "trace carries the failing schema paths")
        (is (some #{[:n]} (:failing-paths (:tags ev)))
            "[:n] is the failing path")))))

(deftest replace-frame-state-app-only-no-validation-when-no-schemas
  (testing "When the frame has no registered schemas, replace-app-db!
            accepts any new-db (the validation step is a no-op)"
    (rf/reg-frame :test/loose {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:anything 'goes}}))
    (rf/dispatch-sync [:seed] {:frame :test/loose})

    (is (true? (rf/replace-frame-state! :test/loose {:rf.db/app {:totally :different :shape true}})))
    (is (= {:totally :different :shape true}
           (rf/app-db-value :test/loose)))))

;; ---- rf2-unpldn: depth-0 undo-works-after invariant ----------------------
;;
;; The four synthetic mutators each record a :rf.epoch/db-replaced undo-anchor
;; so restore-epoch! can rewind PAST the injection — their caller's invariant is
;; "undo works after this call" (Tool-Pair §Pair-tool writes). Under
;; (rf/configure! {:epoch-history {:depth 0}}) the ring buffer is DISABLED by
;; documented design (Tool-Pair §Time-travel — depth 0 retains no history;
;; consume via register-epoch-listener!), so the synthetic anchor can never land
;; in the ring (state/record! early-returns under its pos-depth guard). If
;; perform-replace! nonetheless returned true and re-anchored last-settled-epoch
;; (a PHANTOM anchor naming a non-ring epoch-id), a tool/pair gesture would
;; believe it could rewind and could not (restore-epoch! of that id fails
;; :rf.epoch/restore-unknown-epoch). The contract is therefore LOUD REJECT —
;; depth 0 means "history disabled", so force-appending the anchor would
;; contradict the spec; instead reject loudly via the in-artefact failure
;; channel (the analogue of the artefact-missing throw at core_epoch.cljc:111).

(deftest replace-frame-state-app-only-depth-0-rejects-no-false-undo
  (testing "rf2-unpldn — under depth 0 (ring disabled) replace-app-db!
            returns FALSE and emits :rf.epoch/replace-history-disabled rather
            than a false success; app-db is unchanged and NO phantom
            last-settled anchor is left (the undo-works-after invariant is
            honoured by refusing, not by lying)"
    (rf/configure! {:epoch-history {:depth 0}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (= {:n 0} (rf/app-db-value :test/main)))
    ;; Pre-condition: depth 0 retains no history (the existing documented
    ;; behaviour) and no anchor has been set by the seed dispatch.
    (is (= [] (rf/epoch-history :test/main)) "depth 0 retains no ring history")
    (is (nil? (state/last-settled-epoch-id :test/main))
        "no last-settled anchor under depth 0 (companion phantom-anchor gate)")

    (let [recorded (record-trace!)
          ok?      (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}})]
      (is (false? ok?)
          "replace-app-db! is REJECTED under depth 0 — NOT a false true")
      (is (= {:n 0} (rf/app-db-value :test/main))
          "app-db unchanged — the rejected injection is a no-op")
      (is (= [] (rf/epoch-history :test/main))
          "still no ring history — nothing force-appended")
      (is (nil? (state/last-settled-epoch-id :test/main))
          "NO phantom anchor left behind (would have named a non-ring epoch-id
           pre-fix, breaking later back-fill / undo attribution)")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/replace-history-disabled (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/replace-history-disabled fired")
        (is (= :error (:op-type ev)))
        (is (= :test/main (:frame (:tags ev))))))))

(deftest four-mutators-all-reject-under-depth-0
  (testing "rf2-unpldn — all four synthetic mutators (replace-app-db! /
            reset-app-db! / replace-runtime-db! / replace-frame-state!) reject
            uniformly under depth 0 via the shared precondition skeleton; none
            leaves a phantom anchor"
    (rf/configure! {:epoch-history {:depth 0}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (is (false? (rf/replace-frame-state! :test/main {:rf.db/app {:n 1}}))   "replace-app-db! rejected")
    (is (false? (rf/replace-frame-state! :test/main {:rf.db/app {}}))          "reset-app-db! rejected")
    (is (false? (rf/replace-frame-state! :test/main {:rf.db/runtime {:rf.runtime/machines {}}}))
        "replace-runtime-db! rejected")
    (is (false? (rf/replace-frame-state! :test/main
                                         {:rf.db/app {:n 2} :rf.db/runtime {}}))
        "replace-frame-state! rejected")
    (is (= {:n 0} (rf/app-db-value :test/main))
        "app-db untouched by any of the four rejected injections")
    (is (nil? (state/last-settled-epoch-id :test/main))
        "no phantom anchor from any of the four")))

(deftest replace-frame-state-app-only-positive-depth-still-records-undo-anchor
  (testing "rf2-unpldn — the depth-0 reject does NOT regress the normal
            positive-depth path: with depth > 0 the synthetic anchor still
            lands and restore-epoch! of a prior epoch rewinds past the
            injection (the undo-works-after invariant holds)"
    (rf/configure! {:epoch-history {:depth 10}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (is (true? (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}}))
        "positive depth: injection succeeds")
    (let [history (rf/epoch-history :test/main)
          head    (last history)]
      (is (= :rf.epoch/db-replaced (:event-id head))
          "the synthetic undo-anchor landed in the ring")
      (is (= (:epoch-id head) (state/last-settled-epoch-id :test/main))
          "last-settled anchors to a REAL ring epoch (no phantom)")
      ;; restore-epoch! of the seed epoch rewinds PAST the injection — undo works.
      (let [seed-epoch (some #(when (= :seed (:event-id %)) %) history)]
        (is (some? seed-epoch))
        (is (true? (rf/restore-epoch! :test/main (:epoch-id seed-epoch))))
        (is (= {:n 7} (rf/app-db-value :test/main))
            "undo works after the injection — restore rewound past it")))))

(deftest replace-frame-state-app-only-subs-re-fire
  (testing "Subscribers route off the post-reset app-db value (the
            substrate's reactive container drives sub re-evaluation,
            same as restore-epoch!'s happy path)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :n*2 :<- [:n] (fn [n _] (* 2 (or n 0))))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (= 0 (rf/subscribe-once [:n] {:frame :test/main})))
    (is (= 0 (rf/subscribe-once [:n*2] {:frame :test/main})))

    (rf/replace-frame-state! :test/main {:rf.db/app {:n 21}})
    (is (= 21 (rf/subscribe-once [:n] {:frame :test/main}))
        "layer-1 sub returns the post-reset value")
    (is (= 42 (rf/subscribe-once [:n*2] {:frame :test/main}))
        "derived sub re-computes against the post-reset value")))

;; rf2-t3lftq (API-shrink #3): the app-only-shaped and runtime-only-shaped
;; artefact-missing checks were DELETED here — before the consolidation each
;; partition mutator late-bound through its OWN hook
;; (`:epoch/replace-app-db!` / `:epoch/replace-runtime-db!` /
;; `:epoch/replace-frame-state!`), so each was independently worth pinning.
;; Now every partial-map shape (app-only / runtime-only / both-partition)
;; routes through the SAME `:epoch/replace-frame-state!` hook and the SAME
;; `:where 'rf/replace-frame-state!` ex-data, so the shape of the map passed
;; is irrelevant to this check — `replace-frame-state!-raises-when-epoch-
;; artefact-missing` (below) is the ONE test for it now.

;; ---- replace-frame-state! (runtime-only / both-partition, rf2-szbzei) -----
;;
;; Per Tool-Pair §Pair-tool writes the four partition-aware injection
;; mutators are ALL epoch-backed dev/tooling writes — the app-db, runtime-db,
;; and full-frame mutators all run through the one epoch-backed write path
;; (rf2-szbzei). The invariants below mirror the app-db-pair tests above,
;; proving the four contract points the bead enumerates:
;;
;;   1. A replace-runtime-db! / replace-frame-state! injection records a
;;      synthetic :rf.epoch/db-replaced epoch, and restore-epoch! of a PRIOR
;;      epoch rewinds PAST the injection.
;;   2. Boolean return (true on success).
;;   3. :rf.error/epoch-artefact-missing when the late-bind hook is nil.
;;   4. Runtime-db schema validation fires (against the framework-owned
;;      runtime-db validator — the machine-data boundary), rejecting an
;;      injection whose snapshot :data violates its machine's [:schemas :data] schema.

(deftest replace-frame-state-runtime-only-replaces-runtime-only-preserving-app-db
  (testing "replace-runtime-db! replaces ONLY the runtime-db partition
            (app-db preserved); returns true on success"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7 :cart {:items [1 2]}}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (= {:n 7 :cart {:items [1 2]}} (rf/app-db-value :test/main)))

    (is (true? (rf/replace-frame-state! :test/main {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :home}}}}))
        "replace-runtime-db! returns true on success")
    (is (= {:rf.runtime/routing {:current {:route-id :home}}}
           (:rf.db/runtime (rf/frame-state-value :test/main)))
        "runtime-db partition holds the injected value")
    (is (= {:n 7 :cart {:items [1 2]}} (rf/app-db-value :test/main))
        "app-db partition is PRESERVED — replace-runtime-db! never touches it")))

(deftest replace-frame-state-runtime-only-records-undo-epoch-and-restore-rewinds-past
  (testing "replace-runtime-db! records a synthetic :rf.epoch/db-replaced
            epoch so restore-epoch! can rewind PAST the runtime-db injection"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    ;; A real cascade that seeds a runtime-db value to rewind back to.
    (rf/replace-frame-state! :test/main {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :start}}}})
    (let [seed-record (some (fn [r] (when (= :seed (:event-id r)) r))
                            (rf/epoch-history :test/main))
          pre-count   (count (rf/epoch-history :test/main))
          ok?         (rf/replace-frame-state! :test/main {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :end}}}})
          history     (rf/epoch-history :test/main)
          fresh       (last history)]
      (is (true? ok?) "boolean true return")
      (is (= (inc pre-count) (count history)) "a synthetic epoch was appended")
      (is (= :rf.epoch/db-replaced (:event-id fresh))
          "the synthetic record sentinels the pair-tool injection")
      (is (= [:rf.epoch/db-replaced] (:trigger-event fresh))
          "trigger-event mirrors the sentinel")
      (is (= {:current {:route-id :start}}
             (get-in fresh [:frame-state-before :rf.db/runtime :rf.runtime/routing]))
          "frame-state-before captured the pre-injection runtime-db")
      (is (= {:current {:route-id :end}}
             (get-in fresh [:frame-state-after :rf.db/runtime :rf.runtime/routing]))
          "frame-state-after captured the post-injection runtime-db")
      ;; Restore the epoch BEFORE the second injection — rewinds PAST it.
      (is (true? (rf/restore-epoch! :test/main (:epoch-id seed-record)))
          "restore-epoch! returns true")
      (is (= {:n 7} (rf/app-db-value :test/main))
          "app-db rewound to the seed epoch")
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :test/main))
                        [:rf.runtime/routing :current]))
          "runtime-db rewound PAST the injection (the seed epoch carried no route)"))))

(deftest replace-frame-state-runtime-only-failure-unknown-frame
  (testing "replace-runtime-db! on an unknown frame returns false and emits
            :rf.error/no-such-handler (kind :frame)"
    (let [recorded (record-trace!)
          ok?      (rf/replace-frame-state! :no.such/frame {:rf.db/runtime {:rf.runtime/machines {}}})]
      (is (false? ok?))
      (is (has-error-op? @recorded :rf.error/no-such-handler))
      (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                     @recorded)]
        (is (= :frame (:kind (:tags ev))))
        (is (= :no.such/frame (:frame (:tags ev))))))))

(deftest replace-frame-state-runtime-only-failure-during-drain
  (testing "replace-runtime-db! called from inside a drain returns false and
            emits :rf.epoch/replace-during-drain (the shared
            four-mutator failure op); runtime-db unchanged by the rejected call"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [recorded (record-trace!)
          attempt  (atom nil)]
      (rf/reg-event :try-rt
        (fn [{:keys [db]} _]
          (reset! attempt (rf/replace-frame-state! :test/main {:rf.db/runtime {:rf.runtime/machines {:m 1}}}))
          {:db db}))
      (rf/dispatch-sync [:try-rt] {:frame :test/main})

      (is (false? @attempt) "replace-runtime-db! returned false from inside the drain")
      (is (nil? (get (:rf.db/runtime (rf/frame-state-value :test/main)) :rf.runtime/machines))
          "runtime-db unchanged — the in-drain injection was rejected")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/replace-during-drain
                                (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/replace-during-drain fired")
        (is (= :test/main (:frame (:tags ev))))))))

(deftest replace-frame-state-runtime-only-failure-schema-mismatch
  (testing "replace-runtime-db! with a runtime-db whose machine snapshot :data
            violates its registered [:schemas :data] schema returns false; emits
            :rf.epoch/replace-schema-mismatch; runtime-db unchanged"
    (rf/reg-frame :test/main {})
    ;; Register a machine carrying a [:schemas :data] schema; the framework-owned
    ;; runtime-db validator (the machine-data boundary) validates each
    ;; snapshot's :data against it.
    (rf/reg-machine :rf.szbzei/door
      {:initial :idle
       :data    {:n 1}
       :schemas {:data [:map [:n [:int {:min 0}]]]}
       :states  {:idle {}}})

    (let [pre      (:rf.db/runtime (rf/frame-state-value :test/main))
          recorded (record-trace!)
          ;; A runtime-db whose snapshot :data (:n -5) violates [:int {:min 0}].
          ok?      (rf/replace-frame-state! :test/main {:rf.db/runtime {:rf.runtime/machines
                      {:snapshots {:rf.szbzei/door {:state :idle :data {:n -5}}}}}})]
      (is (false? ok?) "runtime-db injection rejected on schema mismatch")
      (is (= pre (:rf.db/runtime (rf/frame-state-value :test/main)))
          "runtime-db unchanged after a rejected injection")
      (let [ev (some (fn [ev]
                       (when (= :rf.epoch/replace-schema-mismatch
                                (:operation ev))
                         ev))
                     @recorded)]
        (is (some? ev) ":rf.epoch/replace-schema-mismatch fired")
        (is (= :test/main (:frame (:tags ev))))
        (is (vector? (:failing-paths (:tags ev)))
            "trace carries the failing paths")))))

(deftest replace-frame-state!-replaces-both-partitions
  (testing "replace-frame-state! installs BOTH partitions atomically; returns
            true on success"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (is (true? (rf/replace-frame-state! :test/main
                 {:rf.db/app {:a 7} :rf.db/runtime {:rf.runtime/routing {:r 1}}}))
        "replace-frame-state! returns true on success")
    (is (= {:a 7} (rf/app-db-value :test/main)) "app-db partition installed")
    (is (= {:rf.runtime/routing {:r 1}} (:rf.db/runtime (rf/frame-state-value :test/main)))
        "runtime-db partition installed")
    (is (= {:rf.db/app {:a 7} :rf.db/runtime {:rf.runtime/routing {:r 1}}}
           (rf/frame-state-value :test/main))
        "frame-state reads back the coherent both-partition snapshot")))

(deftest replace-frame-state!-records-undo-epoch-and-restore-rewinds-past
  (testing "replace-frame-state! records a synthetic :rf.epoch/db-replaced
            epoch so restore-epoch! can rewind PAST the full-frame injection"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [seed-record (some (fn [r] (when (= :seed (:event-id r)) r))
                            (rf/epoch-history :test/main))
          pre-count   (count (rf/epoch-history :test/main))
          ok?         (rf/replace-frame-state! :test/main
                        {:rf.db/app {:a :injected}
                         :rf.db/runtime {:rf.runtime/routing {:r :injected}}})
          history     (rf/epoch-history :test/main)
          fresh       (last history)]
      (is (true? ok?) "boolean true return")
      (is (= (inc pre-count) (count history)) "a synthetic epoch was appended")
      (is (= :rf.epoch/db-replaced (:event-id fresh))
          "the synthetic record sentinels the pair-tool injection")
      (is (= {:rf.db/app {:a :injected}
              :rf.db/runtime {:rf.runtime/routing {:r :injected}}}
             (:frame-state-after fresh))
          "frame-state-after captured BOTH injected partitions")
      ;; Restore the seed epoch — rewinds PAST the full-frame injection.
      (is (true? (rf/restore-epoch! :test/main (:epoch-id seed-record))))
      (is (= {:n 7} (rf/app-db-value :test/main))
          "app-db rewound past the injection")
      (is (nil? (:rf.runtime/routing (:rf.db/runtime (rf/frame-state-value :test/main))))
          "runtime-db rewound past the injection too"))))

(deftest replace-frame-state!-raises-when-epoch-artefact-missing
  (testing "rf/replace-frame-state! raises :rf.error/epoch-artefact-missing
            when the :epoch/replace-frame-state! late-bind hook is nil"
    (let [hook-key :epoch/replace-frame-state!
          original (late-bind/get-fn hook-key)]
      (try
        (late-bind/set-fn! hook-key nil)
        (let [thrown (try (rf/replace-frame-state! :any/frame {:rf.db/app {} :rf.db/runtime {}})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "replace-frame-state! throws when the epoch artefact is absent")
          ;; rf2-vvixub — assert the [:rf.error/<id>] token + canonical
          ;; :rf.error/id, not exact keyword-equality.
          (is (re-find #"\[:rf\.error/epoch-artefact-missing\]" (.getMessage thrown)))
          (is (= :rf.error/epoch-artefact-missing (:rf.error/id (ex-data thrown))))
          (let [data (ex-data thrown)]
            (is (= 'rf/replace-frame-state! (:where data))
                "ex-data carries :where = 'rf/replace-frame-state!")
            (is (= :no-recovery (:recovery data)))
            (is (string? (:reason data)))))
        (finally
          (late-bind/set-fn! hook-key original))))))

(deftest replace-frame-state!-failure-runtime-schema-mismatch
  (testing "replace-frame-state! rejects an injection whose runtime-db
            partition fails the framework-owned runtime-db validator —
            either partition's schema failure rejects the whole atomic install"
    (rf/reg-frame :test/main {})
    (rf/reg-machine :rf.szbzei/gate
      {:initial :idle
       :data    {:n 1}
       :schemas {:data [:map [:n [:int {:min 0}]]]}
       :states  {:idle {}}})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:ok true}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [pre-app (rf/app-db-value :test/main)
          pre-rt  (:rf.db/runtime (rf/frame-state-value :test/main))
          ok?     (rf/replace-frame-state! :test/main
                    {:rf.db/app {:fresh :app}
                     :rf.db/runtime
                     {:rf.runtime/machines
                      {:snapshots {:rf.szbzei/gate {:state :idle :data {:n -1}}}}}})]
      (is (false? ok?) "the whole install rejected on a runtime-db schema failure")
      (is (= pre-app (rf/app-db-value :test/main))
          "app-db unchanged — the atomic install was rolled back wholesale")
      (is (= pre-rt (:rf.db/runtime (rf/frame-state-value :test/main)))
          "runtime-db unchanged too"))))

;; ---- capture-event! skip-ops cross-contamination (rf2-htf28) ---------------
;;
;; Every `:rf.epoch/*` op this namespace emits with a `:frame` tag fires
;; OUTSIDE a cascade (the drain has either not started, or has just
;; settled and the buffer has been harvested). If `capture-event!`
;; failed to skip them they would accrete into `capture-buffers` and
;; leak into the NEXT cascade's harvested record for the same frame —
;; phantom `:trace-events` and a wrong `:trigger-event` from
;; `find-trigger-event`'s fallback arm.
;;
;; This catalogue test pins the `skip-ops` set against every
;; `:rf.epoch/*` op the namespace emits. If a future op is added (e.g.
;; an in-drain `:rf.epoch/cascade-rollback`) and forgotten in
;; `skip-ops`, OR a stale op is left there, the diff between
;; observed-and-skipped ops vs the registry will tell. Keeps the
;; deliberate-enumeration choice right-by-construction.

(deftest replace-frame-state-app-only-does-not-leak-into-next-cascade
  (testing "after replace-app-db! on a frame, the NEXT cascade on that
            frame harvests a record whose :trace-events excludes the
            out-of-drain :rf.epoch/db-replaced emit, and whose
            :trigger-event is the actual next dispatched event (NOT
            [:rf.epoch/db-replaced] picked from a leaked buffer)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))

    ;; Cascade 1: a real event, lands a clean record.
    (rf/dispatch-sync [:seed] {:frame :test/main})

    ;; Out-of-drain emit: :rf.epoch/db-replaced fires with a :frame tag.
    ;; capture-event! must NOT buffer it into capture-buffers[:test/main].
    (rf/replace-frame-state! :test/main {:rf.db/app {:n 100}})

    ;; Cascade 2: a real event. Its harvested record reflects only the :bump
    ;; cascade. Were the :rf.epoch/db-replaced event leaked into the buffer it
    ;; would be the FIRST event there, and find-trigger-event's fallback arm
    ;; would pick its :epoch-id over the real :bump event.
    (rf/dispatch-sync [:bump] {:frame :test/main})

    (let [history    (rf/epoch-history :test/main)
          ;; Skip the :rf.epoch/db-replaced synthetic record itself —
          ;; we're checking the cascade that ran AFTER it.
          post-reset (last history)]
      (is (= :bump (:event-id post-reset))
          ":event-id is the real cascade trigger, not :rf.epoch/db-replaced")
      (is (= [:bump] (:trigger-event post-reset))
          ":trigger-event is the real event vector, not the leaked sentinel")
      (is (not-any? (fn [ev] (= :rf.epoch/db-replaced (:operation ev)))
                    (:trace-events post-reset))
          ":trace-events does NOT contain the out-of-drain :rf.epoch/db-replaced emit")
      ;; project-all (rf2-ecu37, fused projection) emits no :effects
      ;; entry for :bump; the :sub-runs / :renders slots walking a
      ;; leaked event with op :rf.epoch/db-replaced would silently be
      ;; empty anyway — the strong signal is the trigger-event check
      ;; above plus the trace-events absence.
      (is (empty? (:effects post-reset))
          "no leaked effects from the out-of-drain emit"))))

(deftest replace-frame-state-app-only-failure-does-not-leak-into-next-cascade
  (testing "the two replace-app-db! failure-mode emits
            (:rf.epoch/replace-during-drain,
             :rf.epoch/replace-schema-mismatch) fire outside a
            cascade with :frame tags. They MUST be filtered out of
            capture-event!'s buffering — otherwise a failed
            replace-app-db! attempt leaks a phantom event into the next
            real cascade for that frame."
    ;; Use the schema-mismatch path — easier to drive than during-drain.
    (rf/reg-frame :test/sm {})
    (rf/reg-app-schema [:n] {:frame :test/sm} [:int])
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/sm})

    ;; This fails — new-db doesn't validate. Emits
    ;; :rf.epoch/replace-schema-mismatch with :frame :test/sm.
    (is (false? (rf/replace-frame-state! :test/sm {:rf.db/app {:n "not-an-int"}})))

    ;; Next cascade — should NOT carry the failure emit.
    (rf/dispatch-sync [:bump] {:frame :test/sm})

    (let [post-fail (last (rf/epoch-history :test/sm))]
      (is (= :bump (:event-id post-fail)))
      (is (= [:bump] (:trigger-event post-fail)))
      (is (not-any? (fn [ev]
                      (= :rf.epoch/replace-schema-mismatch
                         (:operation ev)))
                    (:trace-events post-fail))
          "failure-mode emit is filtered from the next cascade's trace stream"))))

(deftest restore-epoch-emits-do-not-leak-into-next-cascade
  (testing "restore-epoch!'s success emit (:rf.epoch/restored) and its
            five documented failure-mode emits all fire outside a
            cascade with :frame tags. None may bleed into the next
            real cascade's :trace-events for that frame."
    (rf/reg-frame :test/r {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/r})
    (rf/dispatch-sync [:bump] {:frame :test/r})
    (let [seed-epoch (first (rf/epoch-history :test/r))]
      ;; Successful restore — emits :rf.epoch/restored out-of-drain.
      (is (true? (rf/restore-epoch! :test/r (:epoch-id seed-epoch))))
      ;; Failed restore — unknown epoch-id emits
      ;; :rf.epoch/restore-unknown-epoch out-of-drain.
      (is (false? (rf/restore-epoch! :test/r 999999)))

      ;; Next cascade — should NOT carry either emit.
      (rf/dispatch-sync [:bump] {:frame :test/r})
      (let [post (last (rf/epoch-history :test/r))
            ops  (into #{} (map :operation (:trace-events post)))]
        (is (= :bump (:event-id post)))
        (is (= [:bump] (:trigger-event post)))
        (is (not (contains? ops :rf.epoch/restored))
            ":rf.epoch/restored does not leak from a prior successful restore")
        (is (not (contains? ops :rf.epoch/restore-unknown-epoch))
            ":rf.epoch/restore-unknown-epoch does not leak from a prior failed restore")))))

(deftest skip-ops-catalogue-pins-every-rf-epoch-op
  (testing "skip-ops covers every :rf.epoch/* + :rf.warning/* op this
            namespace emits with a :frame tag OUTSIDE a cascade — the
            out-of-cascade op set is DERIVED from the emit sites (source
            reality), NOT a hand-literal compared against another
            hand-literal, so a new op added to an emit site but forgotten
            in `skip-ops` fails loudly (rf2-gba3ou — the prior
            literal==literal shape was false-green: it missed
            :rf.epoch/replace-history-disabled from BOTH literals)"
    ;; --- (a) derive the emitted out-of-cascade op set from reality ------
    ;; The epoch artefact emits an :rf.epoch/* | :rf.warning/* OPERATION
    ;; through exactly two syntactic seams, both scanned here:
    ;;   (trace/emit! <op-type> :rf.epoch/OP ...)   — the success emits
    ;;                                                 (snapshotted / outcome
    ;;                                                 / restored / db-replaced
    ;;                                                 / the redact-fn warning)
    ;;   {:outcome :fail :op :rf.epoch/OP ...}       — the precondition-failure
    ;;                                                 results, emitted by
    ;;                                                 `emit-precondition-failure!`
    ;;                                                 via `trace/emit-error!`.
    ;; Tag KEYS that share the :rf.epoch/ prefix (:rf.epoch/id,
    ;; :rf.epoch/sensitive?, :rf.epoch/redacted-modified-paths-count) sit
    ;; in map-KEY position, never after `emit!`/`:op`, so they are not
    ;; operations and are not matched.
    (let [src            (apply str (map slurp (epoch-source-files)))
          hits->kws      (fn [hits] (into #{} (map #(keyword (subs % 1))) hits))
          emit-ops       (hits->kws (map second
                                         (re-seq #"emit!\s+:[A-Za-z.]+\s+(:rf\.(?:epoch|warning)/[a-z][a-z0-9-]*)"
                                                 src)))
          fail-ops       (hits->kws (map second
                                         (re-seq #":op\s+(:rf\.(?:epoch|warning)/[a-z][a-z0-9-]*)"
                                                 src)))
          derived        (into emit-ops fail-ops)
          ;; Every :rf.epoch/* op the artefact emits today fires OUTSIDE a
          ;; cascade — the deliberate-enumeration design (capture.cljc
          ;; §skip-ops catalogue). If a FUTURE in-cascade :rf.epoch/* op is
          ;; introduced (e.g. an in-drain :rf.epoch/cascade-rollback trace)
          ;; it MUST surface in the epoch record — NOT be skipped — so list
          ;; it here to exempt it from the skip-ops obligation below. Empty
          ;; today (no in-cascade :rf.epoch/* op exists yet).
          in-cascade     #{}
          out-of-cascade (set/difference derived in-cascade)
          ;; --- (b) the human-readable pin, kept honest against reality ----
          ;; This catalogue is ASSERTED equal to the scanned set below, so
          ;; it can no longer silently drift (the rf2-gba3ou defect). Update
          ;; it AND `skip-ops` when adding/removing an emitted op.
          ;; (`:rf.epoch.cb/silenced-on-frame-destroy` is op-type :rf.epoch.cb,
          ;; not :rf.epoch — and it emits AFTER the frame's ring buffer has
          ;; been dropped so it can't race a future cascade. Not in skip-ops.)
          expected       #{:rf.epoch/snapshotted
                           :rf.epoch/outcome                  ;; rf2-18g1w
                           :rf.epoch/restored
                           :rf.epoch/restore-unknown-epoch
                           :rf.epoch/restore-schema-mismatch
                           :rf.epoch/restore-missing-handler
                           :rf.epoch/restore-version-mismatch
                           :rf.epoch/restore-during-drain
                           :rf.epoch/restore-non-ok-record    ;; rf2-v0jwt
                           :rf.epoch/db-replaced
                           :rf.epoch/replace-during-drain
                           :rf.epoch/replace-history-disabled ;; rf2-gba3ou / rf2-unpldn
                           :rf.epoch/replace-schema-mismatch
                           ;; rf2-wp70d: redact-fn exception warning emits
                           ;; AFTER `harvest-buffer!` has emptied this
                           ;; frame's cascade buffer, so it must be skipped
                           ;; lest it accrete into the next cascade's record.
                           :rf.warning/epoch-redact-fn-exception}
          skip-ops       @#'capture/skip-ops]
      ;; Anti-vacuity guard: an empty scanned set would make the SUPERSET
      ;; assertion trivially true (the exact trap the old test fell into).
      ;; A zero-match regex or a broken source path fails HERE.
      (is (seq out-of-cascade)
          "emit-site scan resolved the epoch source (non-vacuous)")
      ;; The readable catalogue tracks the scanned reality — no silent drift.
      (is (= expected out-of-cascade)
          "documented catalogue == the ops actually emitted out-of-cascade")
      ;; (a) TEETH: skip-ops is a SUPERSET of every out-of-cascade emitted
      ;; op. An op added to an emit site but forgotten in skip-ops fails
      ;; HERE (this is what missed :rf.epoch/replace-history-disabled).
      (is (set/subset? out-of-cascade skip-ops)
          "skip-ops covers every out-of-cascade :rf.epoch/* + :rf.warning/* op the namespace emits")
      ;; No stale entry: every skipped op is one the namespace actually
      ;; emits out-of-cascade (a removed emit left in skip-ops fails here).
      (is (set/subset? skip-ops out-of-cascade)
          "skip-ops has no stale entry the namespace no longer emits out-of-cascade"))))

;; ---- rf2-gba3ou: depth-0 reject emit does not leak into next cascade ------
;;
;; Companion behavioural pin to the derived-catalogue test above. The
;; depth-0 `replace-frame-state!` reject emits :rf.epoch/replace-history-disabled
;; OUTSIDE a cascade with a :frame tag (Tool-Pair §Pair-tool writes / rf2-unpldn).
;; The existing depth-0 tests (`replace-frame-state-app-only-depth-0-rejects-...`,
;; `four-mutators-all-reject-under-depth-0`) assert reject / false / no-phantom-
;; anchor but NOT that the emit stays out of the NEXT cascade's record. Depth 0
;; disables the ring, so the next cascade's assembled record is observed via the
;; epoch listener fan-out (rf2-douii — depth 0 still fires listeners). Skip-ops
;; is the deliberate defense; the orphan-drop branch backstops the in-namespace
;; leak so it is benign today (rf2-gba3ou) — this pins the end-to-end no-leak
;; contract regardless of which layer enforces it.
(deftest depth-0-replace-reject-emit-does-not-leak-into-next-cascade
  (testing "rf2-gba3ou — the out-of-cascade :rf.epoch/replace-history-disabled
            emit from a depth-0 replace-frame-state! reject does NOT surface in
            the NEXT cascade's assembled record for that frame"
    (rf/configure! {:epoch-history {:depth 0 :trace-events-keep 50}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [seen (atom [])]
      (rf/register-listener! :epoch ::watcher (fn [r] (swap! seen conj r)))
      ;; Cascade 1 — a real event.
      (rf/dispatch-sync [:seed] {:frame :test/main})
      ;; Out-of-cascade reject: depth 0 → history disabled → rejected,
      ;; emitting :rf.epoch/replace-history-disabled with a :frame tag.
      (is (false? (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}}))
          "depth-0 replace-frame-state! is rejected (not a false success)")
      ;; Cascade 2 — its record must reflect ONLY the :bump cascade.
      (rf/dispatch-sync [:bump] {:frame :test/main})

      (let [bump-rec (last @seen)]
        (is (= :bump (:event-id bump-rec))
            "next cascade's record is the real :bump event")
        (is (= [:bump] (:trigger-event bump-rec))
            ":trigger-event is the real event, not the leaked reject sentinel")
        (is (not-any? (fn [ev] (= :rf.epoch/replace-history-disabled (:operation ev)))
                      (:trace-events bump-rec))
            "the out-of-drain reject emit does NOT leak into the next cascade's :trace-events"))
      (rf/unregister-listener! :epoch ::watcher))))

;; ---- restore trace-tag :rf.epoch/id golden guard (rf2-5wzfez) ---------------
;;
;; Spec 009 §Instrumentation and Spec-Schemas reserve the namespaced
;; `:rf.epoch/id` key for the epoch-id slot on EVERY `:rf.epoch/*` trace tag
;; (Spec-Schemas `Restore{UnknownEpoch,SchemaMismatch,MissingHandler,
;; VersionMismatch,DuringDrain}Tags` + `DbReplacedTags`). The restore
;; success (`:rf.epoch/restored`) and the precondition-failure traces had
;; drifted to an unqualified `:epoch-id` alias, splitting the contract so a
;; trace consumer keyed off the Spec-Schemas vocabulary could not correlate a
;; restore success/failure trace with its epoch record. This golden guard
;; drives one restore SUCCESS and two restore FAILURE paths, validates each
;; emitted tag map against the Spec-Schemas key-set, and — adversarially —
;; fails loudly if ANY restore-family trace tag ever carries the unqualified
;; `:epoch-id` again.

(defn- restore-tag-event
  "Find the first listener event whose `:operation` is `op`."
  [events op]
  (some (fn [ev] (when (= op (:operation ev)) ev)) events))

(deftest restore-trace-tags-use-namespaced-epoch-id
  (testing "every restore-family trace tag carries :rf.epoch/id (Spec 009 /
            Spec-Schemas) and never the unqualified :epoch-id alias"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history    (rf/epoch-history :test/main)
          target     (first history)            ;; the :seed epoch ({:n 0})
          target-eid (:epoch-id target)
          recorded   (record-trace!)]

      ;; (A) SUCCESS path — :rf.epoch/restored.
      (is (true? (rf/restore-epoch! :test/main target-eid))
          "restore to a valid epoch succeeds")

      ;; (B) FAILURE path 1 — unknown epoch.
      (is (false? (rf/restore-epoch! :test/main :no-such-epoch))
          "restore to an absent epoch is rejected")

      ;; (C) FAILURE path 2 — restore refused mid-drain.
      (let [drain-attempt (atom nil)]
        (rf/reg-event :try-restore
          (fn [{:keys [db]} _]
            (reset! drain-attempt (rf/restore-epoch! :test/main target-eid))
            {:db db}))
        (rf/dispatch-sync [:try-restore] {:frame :test/main})
        (is (false? @drain-attempt)
            "restore from inside a drain is rejected"))

      (let [events   @recorded
            restored (restore-tag-event events :rf.epoch/restored)
            unknown  (restore-tag-event events :rf.epoch/restore-unknown-epoch)
            drain    (restore-tag-event events :rf.epoch/restore-during-drain)]

        ;; --- success: :rf.epoch/restored ---
        (is (some? restored) ":rf.epoch/restored fired on the success path")
        (is (= target-eid (:rf.epoch/id (:tags restored)))
            ":rf.epoch/restored carries the restored epoch's id under :rf.epoch/id")
        (is (= :test/main (:frame (:tags restored))))

        ;; --- failure: :rf.epoch/restore-unknown-epoch ---
        (is (some? unknown) ":rf.epoch/restore-unknown-epoch fired")
        (is (= :no-such-epoch (:rf.epoch/id (:tags unknown)))
            "restore-unknown-epoch carries the requested id under :rf.epoch/id")
        (is (= :test/main (:frame (:tags unknown))))
        (is (number? (:history-size (:tags unknown))))

        ;; --- failure: :rf.epoch/restore-during-drain ---
        (is (some? drain) ":rf.epoch/restore-during-drain fired")
        (is (= target-eid (:rf.epoch/id (:tags drain)))
            "restore-during-drain carries the requested id under :rf.epoch/id")
        (is (= :test/main (:frame (:tags drain))))

        ;; --- golden key-set: each tag map's keys match the Spec-Schemas
        ;;     definition for its op (the `:category` key is injected by
        ;;     core's `emit-error!` on the failure paths — Spec-Schemas list
        ;;     it; the success trace is op-type :rf.epoch and carries none). ---
        (is (= #{:frame :rf.epoch/id} (set (keys (:tags restored))))
            ":rf.epoch/restored tag key-set == Spec-Schemas (frame + :rf.epoch/id)")
        (is (= #{:category :frame :rf.epoch/id :history-size}
               (set (keys (:tags unknown))))
            "restore-unknown-epoch tag key-set == Spec-Schemas RestoreUnknownEpochTags")
        ;; restore-during-drain fires from INSIDE a live run, so the
        ;; envelope's `stamp-dispatch-id` adds `:rf.trace/dispatch-id` for
        ;; correlation — that's an envelope concern, not part of the
        ;; Spec-Schemas RestoreDuringDrainTags shape. Assert the contract
        ;; keys are present and the alias is absent (the adversarial scan
        ;; below pins the full no-:epoch-id invariant across every op).
        (is (= #{:category :frame :rf.epoch/id}
               (-> (:tags drain)
                   (dissoc :rf.trace/dispatch-id)
                   keys set))
            "restore-during-drain contract keys == Spec-Schemas RestoreDuringDrainTags
             (:recovery hoisted to envelope; :rf.trace/dispatch-id is cascade correlation)")

        ;; --- ADVERSARIAL guard (rf2-ifdsar): NO trace event in the WHOLE
        ;;     epoch trace family may carry the unqualified :epoch-id alias
        ;;     as a tag. The canonical epoch-identity TAG is :rf.epoch/id
        ;;     (the bare :epoch-id is the RECORD-field spelling only — the
        ;;     deliberate record/projection vocabulary, never a trace tag).
        ;;     Generalised from the prior restore-only scan so a future
        ;;     epoch trace op (restore mode, :rf.epoch.cb/* listener
        ;;     diagnostic, :rf.warning/epoch-* advisory) cannot silently
        ;;     regress the canonical tag. The op-namespace test is
        ;;     spelling-agnostic — any operation whose namespace starts
        ;;     "rf.epoch" is in scope. ---
        (let [epoch-family-op? (fn [op]
                                 (and (keyword? op)
                                      (some-> (namespace op)
                                              (str/starts-with? "rf.epoch"))))
              leaked           (filter (fn [ev]
                                         (and (epoch-family-op? (:operation ev))
                                              (contains? (:tags ev) :epoch-id)))
                                       events)]
          (is (empty? leaked)
              (str "no epoch-family trace tag may carry the unqualified "
                   ":epoch-id alias (canonical is :rf.epoch/id, rf2-ifdsar); "
                   "leaked ops: " (mapv :operation leaked))))))))

;; ---- destroyed-frame contract (rf2-d656) -----------------------------------
;;
;; Per Tool-Pair §Surface behaviour against destroyed frames (rf2-d656):
;;   - read-shaped surfaces return empty/nil:
;;       (rf/epoch-history destroyed)  → []
;;       (rf/app-db-value   destroyed) → nil
;;   - mutate-shaped surfaces raise :rf.error/no-such-handler (kind :frame):
;;       (rf/restore-epoch!       destroyed _) → false + :rf.error/no-such-handler
;;       (rf/replace-frame-state! destroyed _) → false + :rf.error/no-such-handler
;;   - listener silencing emits one-shot :rf.epoch.cb/silenced-on-frame-destroy
;;     when a frame previously observed by a register-epoch-listener! callback is
;;     destroyed.

(deftest destroyed-frame-epoch-history-returns-empty
  (testing "(rf/epoch-history frame-id) returns [] for a destroyed frame
            and for a never-registered frame — the read-empty contract"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (is (seq (rf/epoch-history :test/short-lived))
        "before destroy, the frame has at least one recorded epoch")

    (rf/destroy-frame! :test/short-lived)
    (is (= [] (rf/epoch-history :test/short-lived))
        "after destroy, epoch-history returns the empty vector")
    (is (= [] (rf/epoch-history :no.such/frame))
        "for a never-registered frame, epoch-history returns the empty vector")))

(deftest destroyed-frame-app-db-value-returns-nil
  (testing "(rf/app-db-value frame-id) returns nil for a destroyed frame
            and for a never-registered frame"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (is (some? (rf/app-db-value :test/short-lived))
        "before destroy, app-db-value returns the live app-db")

    (rf/destroy-frame! :test/short-lived)
    (is (nil? (rf/app-db-value :test/short-lived))
        "after destroy, app-db-value returns nil")
    (is (nil? (rf/app-db-value :no.such/frame))
        "for a never-registered frame, app-db-value returns nil")))

(deftest destroyed-frame-restore-epoch-raises-no-such-handler
  (testing "(rf/restore-epoch! destroyed _) emits :rf.error/no-such-handler
            (kind :frame) and returns false"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (let [eid (-> (rf/epoch-history :test/short-lived) first :epoch-id)]
      (rf/destroy-frame! :test/short-lived)
      (let [recorded (record-trace!)
            ok?      (rf/restore-epoch! :test/short-lived eid)]
        (is (false? ok?)
            "restore returns false for a destroyed frame")
        (is (has-error-op? @recorded :rf.error/no-such-handler)
            ":rf.error/no-such-handler fired")
        (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                       @recorded)]
          (is (= :frame (:kind (:tags ev)))
              "tags carry :kind :frame")
          (is (= :test/short-lived (:frame (:tags ev)))
              "tags carry :frame"))))))

(deftest destroyed-frame-replace-frame-state-app-only-raises-no-such-handler
  (testing "(rf/replace-frame-state! destroyed {:rf.db/app _}) emits
            :rf.error/no-such-handler (kind :frame) and returns false —
            already covered by replace-frame-state-app-only-failure-unknown-frame;
            this test pins the destroyed-frame race specifically (the frame
            existed, then was destroyed)"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (rf/destroy-frame! :test/short-lived)

    (let [recorded (record-trace!)
          ok?      (rf/replace-frame-state! :test/short-lived {:rf.db/app {:n 999}})]
      (is (false? ok?)
          "replace-frame-state! returns false for a destroyed frame")
      (is (has-error-op? @recorded :rf.error/no-such-handler)
          ":rf.error/no-such-handler fired")
      (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                     @recorded)]
        (is (= :frame (:kind (:tags ev))))
        (is (= :test/short-lived (:frame (:tags ev))))))))

(deftest destroyed-frame-silences-epoch-cb-listener
  (testing "A register-epoch-listener! callback that observed a frame receives
            a one-shot :rf.epoch.cb/silenced-on-frame-destroy trace when
            that frame is destroyed. Subsequent destroys of the same
            frame do not re-emit. The callback registration itself
            remains in place."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))

    (let [received (atom [])
          recorded (record-trace!)]
      (rf/register-listener! :epoch ::watcher
                            (fn [r] (swap! received conj r)))
      ;; Drive a cascade so the cb observes the frame.
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (is (= 1 (count @received))
          "the cb received the seed cascade record")
      (is (= :test/short-lived (:frame (first @received)))
          "the observed record was for :test/short-lived")

      ;; Destroy the frame; expect a single silencing trace.
      (rf/destroy-frame! :test/short-lived)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 1 (count silenced))
            "exactly one :rf.epoch.cb/silenced-on-frame-destroy fired")
        (let [ev (first silenced)]
          (is (= :rf.epoch.cb (:op-type ev))
              ":op-type is :rf.epoch.cb")
          (is (= :test/short-lived (:frame (:tags ev)))
              "tags carry :frame")
          (is (= ::watcher (:cb-id (:tags ev)))
              "tags carry :cb-id")))

      ;; The cb is still registered — re-create the frame, drive a
      ;; cascade, the cb fires again.
      (rf/reg-frame :test/short-lived {})
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (is (= 2 (count @received))
          "the same cb continues to fire after the frame is re-registered")

      ;; Destroying again emits a fresh silencing trace (the cb's
      ;; observation set was re-armed when the second cascade landed).
      (rf/destroy-frame! :test/short-lived)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 2 (count silenced))
            "a second silencing trace fires for the second destroy")))))

(deftest destroyed-frame-silenced-trace-is-one-shot
  (testing "A repeat destroy of an already-destroyed frame does NOT
            re-emit :rf.epoch.cb/silenced-on-frame-destroy"
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))

    (let [recorded (record-trace!)]
      (rf/register-listener! :epoch ::watcher (fn [_] nil))
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (rf/destroy-frame! :test/short-lived)

      (let [silenced-after-first (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                             (:operation %))
                                         @recorded)]
        (is (= 1 (count silenced-after-first)))

        ;; The frame is already destroyed; calling destroy again should be
        ;; a no-op (the frame record is gone). Verify no new silencing trace.
        (rf/destroy-frame! :test/short-lived)
        (let [silenced-after-second (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                                (:operation %))
                                            @recorded)]
          (is (= 1 (count silenced-after-second))
              "second destroy does NOT re-emit the silencing trace"))))))

(deftest destroyed-frame-silencing-skipped-when-cb-never-observed
  (testing "A register-epoch-listener! callback that has never received a record
            for the destroyed frame does NOT receive a silencing trace
            (there is nothing to silence)"
    (rf/reg-frame :test/observed     {})
    (rf/reg-frame :test/never-seen-by-cb {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))

    (let [recorded (record-trace!)]
      (rf/register-listener! :epoch ::watcher (fn [_] nil))
      ;; cb observes :test/observed but NOT :test/never-seen-by-cb
      (rf/dispatch-sync [:seed] {:frame :test/observed})

      ;; Destroy the frame the cb never saw — no silencing trace.
      (rf/destroy-frame! :test/never-seen-by-cb)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 0 (count silenced))
            "no silencing trace for a frame the cb never observed"))

      ;; Destroying the cb's observed frame DOES emit silencing.
      (rf/destroy-frame! :test/observed)
      (let [silenced (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                 (:operation %))
                             @recorded)]
        (is (= 1 (count silenced))
            "silencing fires for the observed frame")))))

;; ---- rf2-ronz: on-frame-destroyed! direct unit pin ------------------------
;;
;; Per test-coverage-review-2026-05-12 P3-20. Currently reached only
;; via destroyed-frame-epoch-history-returns-empty and
;; destroyed-frame-app-db-value-returns-nil; no direct unit pins the
;; contract. on-frame-destroyed! is the late-bind hook
;; (`re-frame.frame/destroy-frame!` calls it via `:epoch/on-frame-destroyed`).
;; Tools and alternate-destroy paths invoke it directly; pin the
;; seam.

(deftest on-frame-destroyed-clears-frame-buffer-directly
  (testing "calling epoch.listeners/on-frame-destroyed! on a frame drops its
            ring buffer regardless of whether the frame itself was
            destroyed via the usual frame/destroy-frame! path"
    (rf/reg-frame :test/other {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    ;; Populate :test/other's ring buffer.
    (rf/dispatch-sync [:seed] {:frame :test/other})
    (rf/dispatch-sync [:inc]  {:frame :test/other})
    (rf/dispatch-sync [:inc]  {:frame :test/other})
    (is (= 3 (count (rf/epoch-history :test/other)))
        ":test/other's ring is populated before the seam call")

    ;; Call on-frame-destroyed! DIRECTLY — without going through
    ;; frame/destroy-frame!. The frame record still exists in
    ;; frames-atom; only the epoch ring is dropped. (rf2-9neiq: the
    ;; hook takes (frame-id db-before db-after committed-at) — rf2-bh56rc
    ;; added the causal :time-ms; this seam tests the ring-drop with no
    ;; in-flight cascade, so nil snapshots + nil committed-at apply.)
    (epoch.listeners/on-frame-destroyed! :test/other nil nil nil)

    ;; The frame's ring is gone.
    (is (= [] (rf/epoch-history :test/other))
        "epoch-history returns the empty vector after on-frame-destroyed!")

    ;; The frame record itself is still queryable — on-frame-destroyed! is
    ;; scoped to epoch-internal state.
    (is (some? (frame/frame :test/other))
        "the frame is still registered (we did NOT call destroy-frame!)")))

(deftest on-frame-destroyed-idempotent-on-repeat
  (testing "on-frame-destroyed! is idempotent — repeated calls on the
            same frame are no-ops (no throw, no side-effect cascade)"
    (rf/reg-frame :test/repeat {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/repeat})
    (is (= 1 (count (rf/epoch-history :test/repeat))))

    ;; First call — clears the buffer. (rf2-9neiq / rf2-bh56rc: hook arity
    ;; is (frame-id db-before db-after committed-at); nil snapshots + nil
    ;; committed-at for this ring-drop pin.)
    (epoch.listeners/on-frame-destroyed! :test/repeat nil nil nil)
    (is (= [] (rf/epoch-history :test/repeat)))

    ;; Second call — no-op.
    (epoch.listeners/on-frame-destroyed! :test/repeat nil nil nil)
    (is (= [] (rf/epoch-history :test/repeat))
        "ring stays empty across repeated calls")))

(deftest on-frame-destroyed-on-unknown-frame-is-noop
  (testing "on-frame-destroyed! on a frame that was never registered does
            not throw — it's a clean no-op"
    ;; on-frame-destroyed! is a side-effect fn over the internal
    ;; epoch-state atoms; its return value is whatever swap! produces.
    ;; The observable contract is "no throw, no side effects on
    ;; unrelated state".
    (let [traces (record-trace!)]
      (epoch.listeners/on-frame-destroyed! :test/no-such-frame nil nil nil)
      (is (empty? @traces)
          "no traces emitted — nothing observed to silence"))))

;; ---- rf2-5qbus: capture-buffer cross-contamination from out-of-drain emits ---
;;
;; The `capture-event!` fn must skip every `:rf.epoch/*` op the namespace
;; emits OUTSIDE a cascade (catalogued in the `skip-ops` set). Without
;; the skip, a `replace-app-db!` call (which emits `:rf.epoch/db-replaced`
;; after harvesting the cascade buffer) would buffer the db-replaced
;; trace event into `capture-buffers[frame-id]`, and the NEXT cascade
;; for the same frame would harvest it as the first event in the
;; buffer — treating it as belonging to that cascade. The
;; `find-trigger-event` fallback would pick its `:epoch-id` as the
;; trigger; `project-all` (rf2-ecu37) would silently include the
;; leaked entries. The skip-set carries these ops (rf2-htf28); this pins
;; the contract so a future regression that drops them surfaces loudly.

(deftest capture-buffer-does-not-cross-contaminate-from-replace-frame-state-app-only
  (testing "an out-of-drain :rf.epoch/db-replaced emit from
            replace-app-db! does NOT leak into the next cascade's
            harvested record for the same frame"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :foo  (fn [{:keys [db]} _] {:db (assoc db :foo? true)}))

    ;; 1. Drive one cascade — clean record lands.
    (rf/dispatch-sync [:seed] {:frame :test/main})

    ;; 2. replace-app-db! out-of-drain — emits :rf.epoch/db-replaced,
    ;;    which must NOT buffer into capture-buffers.
    (is (true? (rf/replace-frame-state! :test/main {:rf.db/app {:n 99}})))

    ;; 3. Drive a second cascade for [:foo].
    (rf/dispatch-sync [:foo] {:frame :test/main})

    (let [history    (rf/epoch-history :test/main)
          last-record (peek history)]
      ;; The history has 3 records: [:seed], the synthetic
      ;; replace-app-db! record, and [:foo].
      (is (= 3 (count history)))

      ;; The most-recent record (the [:foo] cascade) must be clean —
      ;; its trigger-event is [:foo], its trace stream carries no
      ;; :rf.epoch/db-replaced events.
      (is (= [:foo] (:trigger-event last-record))
          "last record's :trigger-event is [:foo] — not a phantom
           :rf.epoch/db-replaced")
      (is (= :foo (:event-id last-record))
          "last record's :event-id is :foo")
      (is (not-any? #(= :rf.epoch/db-replaced (:operation %))
                    (:trace-events last-record))
          "no :rf.epoch/db-replaced trace leaked into the cascade's
           harvested trace-events"))))

;; ---- rf2-zzper: on-frame-destroyed! drops in-flight capture-buffer --------
;;
;; The per-event halt and abort paths each clear their own buffer at the
;; settle / harvest seam. But a destroy that
;; races a mid-flight drain — e.g. a hot-reload firing while the drain
;; has buffered events but has not yet settled — would otherwise leave
;; a stale partial buffer hanging on `capture-buffers[frame-id]`. The
;; next cascade against a same-keyed frame would harvest those
;; pre-destroy events as belonging to its first record. Pin the
;; contract: `on-frame-destroyed!` clears the capture-buffer entry
;; symmetric to the ring-buffer drop.

(deftest on-frame-destroyed-drops-in-flight-capture-buffer
  (testing "on-frame-destroyed! drops :capture-buffers[frame-id] so a
            mid-drain destroy can't leak pre-destroy events into the
            first cascade of the next same-keyed frame"
    (rf/reg-frame :test/main {})

    ;; Synthesize a mid-flight capture-buffer entry directly. The
    ;; private capture-buffers atom holds frame-id → vector of trace
    ;; events that were buffered between drain-start and drain-settle.
    ;; A real-world race would have at least one event in this vector
    ;; when the destroy lands; pin the contract by inserting a
    ;; synthetic entry.
    ;;
    ;; (`rf/reg-frame` above emits a `:rf.frame/created` trace which
    ;; capture-event! buffers since the tag carries `:frame`. Reset
    ;; explicitly so the test starts from a known-empty buffer
    ;; rather than relying on the reg-frame side-effect.)
    (let [buffers-atom @#'state/capture-buffers]
      (reset! buffers-atom {})

      (swap! buffers-atom assoc :test/main
             [{:op-type   :rf.event
               :operation :rf.event/run-start
               :tags      {:rf.event/v        [:pre-destroy]
                           :rf.trace/event-id :pre-destroy
                           :frame             :test/main
                           :rf.trace/phase    :run-start}}])

      (is (some? (get @buffers-atom :test/main))
          "sanity: the synthetic capture-buffer entry is present pre-destroy")

      ;; rf2-9neiq / rf2-bh56rc: on-frame-destroyed! takes (frame-id
      ;; db-before db-after committed-at) — the two snapshots destroy-frame!
      ;; threads plus the destroying event's causal :time-ms. This test
      ;; exercises the buffer-drop (step 4), so nil snapshots + nil
      ;; committed-at are fine (the synthetic run-start carries no
      ;; :event-id, so no halted record commits — and this test does not
      ;; assert one).
      (epoch.listeners/on-frame-destroyed! :test/main nil nil nil)

      (is (nil? (get @buffers-atom :test/main))
          "the capture-buffer entry was dropped on destroy — no
           pre-destroy event can leak into a same-keyed frame's next
           cascade"))))

;; ---- rf2-ee38b + rf2-9neiq: live :halted-destroy partial-record commit ----
;;
;; Per the correctness review (ai/findings/review/correctness--
;; implementation-epoch.md): the live `:halted-destroy` partial-record
;; commit — the most intricate live destroy behaviour in the artefact —
;; was only exercised by tests whose assertions were conditionally
;; skipped (`(when @halted ...)` / `(when-let [halted ...] ...)`), so a
;; regression in the live wiring (capture buffer empty / lacking a
;; run-start by destroy time, or the `in-cascade?` gate regressing) would
;; pass green with zero executed assertions. This test drives a REAL
;; mid-drain `destroy-frame!` and asserts the full contract
;; UNCONDITIONALLY: exactly one :halted-destroy record reaches a
;; registered epoch listener, with :outcome :halted-destroy, a populated
;; :event-id, REAL :db-before / :db-after snapshots, the halt-reason
;; descriptor, and — per the rf2-d656 read-empty contract — that the
;; partial record was NOT appended to the ring (epoch-history returns []
;; for a destroyed frame; devtools receive the record via the listener
;; fan-out, the documented introspection channel for the destroy halt).
;;
;; Per Spec-Schemas §:rf/epoch-record §Outcomes, :halted-destroy carries the
;; PRE-CASCADE snapshot as :db-before and the DESTROY-TIME state as :db-after
;; (rf2-9neiq) — NOT nil/nil. `destroy-frame!` threads both snapshots
;; (pre-cascade via frame/*cascade-db-before*, destroy-time via the container
;; read at the top of destroy-frame!) into the destroy hook before the
;; container is dissoc'd, so the record carries the real app-db state.

(deftest live-halted-destroy-fires-partial-record-with-real-snapshots
  (testing "a mid-drain destroy-frame! fires exactly one :halted-destroy
            partial record to listeners (NOT to the ring), carrying the
            cascade's :event-id, halt-reason, and the REAL pre-cascade
            :db-before / destroy-time :db-after snapshots (rf2-9neiq) —
            the live capture-buffer → in-cascade? gate →
            destroy-frame!-threaded-snapshots → notify-listeners! chain"
    (rf/reg-frame :test/main {})
    ;; Seed the frame so the pre-cascade app-db is a real, non-empty
    ;; value — proves the snapshots carry actual state, not {} / nil.
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7 :live true}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [records (atom [])]
      (rf/register-listener! :epoch ::watch (fn [r] (swap! records conj r)))
      ;; A handler that destroys its own frame mid-cascade. It writes no
      ;; committed db before destroying, so destroy-time db == pre-cascade
      ;; db here (the {:n 7 :live true} the :seed cascade settled) — both
      ;; REAL and non-nil, which is the contract (Spec-Schemas §Outcomes).
      (rf/reg-event :destroy-self
                       (fn [_ _]
                         (frame/destroy-frame! :test/main)
                         {}))
      (try (rf/dispatch-sync [:destroy-self] {:frame :test/main})
           (catch Throwable _ nil))
      (let [halted-records (filterv (fn [r] (= :halted-destroy (:outcome r)))
                                    @records)]
        ;; UNCONDITIONAL: exactly one :halted-destroy record reached the
        ;; live listener fan-out via the capture-buffer → in-cascade?
        ;; gate. If the wiring stops firing, this fails loudly (the
        ;; old guarded form passed green with zero assertions).
        (is (= 1 (count halted-records))
            "exactly one :halted-destroy record reaches a registered
             epoch listener from the live mid-drain destroy")
        (let [halted (first halted-records)]
          (is (= :halted-destroy (:outcome halted)))
          (is (= :destroy-self (:event-id halted))
              "the cascade's :event-id is pinned on the partial record
               (the buffered :destroy-self run-start drove the commit)")
          ;; rf2-9neiq: the partial record carries the REAL pre-cascade
          ;; snapshot as :db-before, NOT nil. The :destroy-self cascade's
          ;; pre-cascade db is the {:n 7 :live true} the :seed settled.
          (is (= {:n 7 :live true} (:db-before halted))
              "halted-destroy carries the real pre-cascade :db-before
               snapshot (rf2-9neiq) — the frame's app-db before the
               in-flight cascade began, NOT nil")
          ;; The destroy-time :db-after: the live container value read at
          ;; the top of destroy-frame!, before teardown. The :destroy-self
          ;; handler committed no db before destroying, so it equals the
          ;; pre-cascade value here — REAL state, NOT nil.
          (is (= {:n 7 :live true} (:db-after halted))
              "halted-destroy carries the real destroy-time :db-after
               state (rf2-9neiq) — the partial cascade's writes survive in
               the recorded value; NOT nil")
          (is (= {:operation :rf.frame/destroyed-mid-drain}
                 (:halt-reason halted))
              "the halt-reason descriptor rides the partial record"))
        ;; The partial record is NOT appended to the ring — devtools
        ;; receive it via the listener fan-out only, and the ring is
        ;; dropped on destroy (epoch-history returns [] for the destroyed
        ;; frame per the rf2-d656 read-empty contract). This part of the
        ;; contract is UNCHANGED by rf2-9neiq.
        (is (empty? (filter (fn [r] (= :halted-destroy (:outcome r)))
                            (epoch/epoch-history :test/main)))
            "the :halted-destroy record never lands in the ring buffer
             (it is delivered to listeners only; epoch-history is
             read-empty post-destroy per rf2-d656)")))))

;; rf2-9neiq — :db-before and :db-after DIVERGE when the in-flight cascade
;; committed an app-db write before destroying. A parent event writes the
;; db and `:fx`-dispatches a child; the child is the event whose handler
;; calls destroy-frame!. The child's pre-cascade :db-before is the
;; POST-PARENT state, and the destroy-time :db-after reflects the live
;; container at destroy time — exercising the "partial cascade's writes
;; survive in the recorded value" half of the Spec-Schemas §Outcomes
;; contract with non-equal snapshots.

(deftest live-halted-destroy-db-before-reflects-committed-cascade-writes
  (testing "the :halted-destroy record's :db-before is the destroying
            (child) event's pre-cascade snapshot — which reflects the
            parent event's already-committed write (rf2-9neiq)"
    (rf/reg-frame :test/main {})
    (let [records (atom [])]
      (rf/register-listener! :epoch ::watch (fn [r] (swap! records conj r)))
      ;; Child: destroys the frame. Its pre-cascade db-before is whatever
      ;; the container held when it was dequeued — i.e. AFTER the parent's
      ;; {:phase :parent-done} write committed (per-event epoch boundary).
      (rf/reg-event :child-destroy
                       (fn [_ _]
                         (frame/destroy-frame! :test/main)
                         {}))
      ;; Parent: commits a db write, then fx-dispatches the child. The
      ;; child runs as a SEPARATE dequeued event in the same drain.
      (rf/reg-event :parent-write-then-spawn
                       (fn [_ _]
                         {:db {:phase :parent-done :marker 42}
                          :fx [[:dispatch [:child-destroy]]]}))
      (try (rf/dispatch-sync [:parent-write-then-spawn] {:frame :test/main})
           (catch Throwable _ nil))
      (let [halted (first (filterv (fn [r] (= :halted-destroy (:outcome r)))
                                   @records))]
        (is (some? halted)
            "the child's mid-drain destroy committed a :halted-destroy
             record to listeners")
        (is (= :child-destroy (:event-id halted))
            "the halted record's :event-id is the destroying child event")
        ;; The child's pre-cascade :db-before reflects the parent's
        ;; already-committed write — proving :db-before is the genuine
        ;; per-event pre-cascade snapshot, not nil and not the frame's
        ;; initial {} (rf2-9neiq).
        (is (= {:phase :parent-done :marker 42} (:db-before halted))
            ":db-before is the destroying event's pre-cascade snapshot —
             the parent's committed {:phase :parent-done :marker 42}")
        (is (= {:phase :parent-done :marker 42} (:db-after halted))
            ":db-after is the destroy-time state (the child committed no
             further write before destroying)")))))

;; ---- rf2-kl5p1: build-record omits :event-id / :trigger-event when
;; ---- find-trigger-event yields nothing -----------------------------------
;;
;; Per audit r3 §F1: `:rf/epoch-record` declares `[:event-id :keyword]`
;; (required, non-maybe per Spec-Schemas §`:rf/epoch-record`). The live
;; router halt paths short-circuit `build-record` on an empty buffer via
;; `(when (seq events) ...)` in `settle!`, but `on-frame-destroyed!`'s
;; `:halted-destroy` path commits a partial record from whatever buffered
;; events are present — and a degenerate buffer that holds a `:event/
;; run-start` but no `:event-id` / `:event` tags would otherwise produce
;; `:event-id nil` / `:trigger-event nil`, violating the schema.
;;
;; Contract pin: when `find-trigger-event` returns nil for both fields,
;; the assembled record carries NEITHER slot (the schema admits the
;; absent slot, rejects nil values). `build-record` is exercised
;; directly because driving a real router path with this exact degenerate
;; buffer requires cooperation with internals the audit-time fix does
;; not change.

(deftest build-record-omits-event-id-and-trigger-event-on-tag-less-buffer
  (testing "build-record on a buffer whose only `:event/run-start` trace
            carries neither :event-id nor :event in :tags omits the
            :event-id and :trigger-event slots from the record (rather
            than emitting them as nil, which would violate the
            :event-id :keyword schema)"
    (rf/reg-frame :test/main {})
    ;; Synthetic `:event/run-start` with empty tags — the `in-cascade?`
    ;; gate at on-frame-destroyed! fires on phase :run-start, but the
    ;; tags carry no :event-id / :event, so find-trigger-event resolves
    ;; nothing. This mirrors the degenerate path the audit identified
    ;; on the :halted-destroy commit.
    (let [tag-less-events [{:op-type   :rf.event
                            :operation :rf.event/run-start
                            :tags      {:frame          :test/main
                                        :rf.trace/phase :run-start}}]
          record          (#'assembly/build-record
                            :test/main nil nil tag-less-events
                            1700000000000  ; rf2-bh56rc: committed-at (token :time-ms)
                            :halted-destroy
                            {:operation :rf.frame/destroyed-mid-drain})]
      (is (not (contains? record :event-id))
          "the record does NOT carry :event-id when find-trigger-event
           yields nil — the slot is absent rather than nil-valued
           (schema rejects nil; absent is fine on the open map)")
      (is (not (contains? record :trigger-event))
          "the record does NOT carry :trigger-event when find-trigger-event
           yields nil — symmetric to :event-id, the slot is absent
           rather than nil-valued")
      ;; Sanity: every required non-conditional slot still landed, and
      ;; the halt-reason came through.
      (is (= :test/main (:frame record)))
      (is (= :halted-destroy (:outcome record)))
      (is (= {:operation :rf.frame/destroyed-mid-drain}
             (:halt-reason record))))))

(deftest build-record-emits-event-id-and-trigger-event-when-trigger-resolves
  (testing "the conditional cond-> slots are emitted when find-trigger-event
            resolves both — the rf2-kl5p1 fix must not regress the
            happy-path record shape"
    (rf/reg-frame :test/main {})
    (let [events [{:op-type   :rf.event
                   :operation :rf.event/run-start
                   :tags      {:frame             :test/main
                               :rf.trace/phase    :run-start
                               :rf.trace/event-id :seed
                               :rf.event/v        [:seed 1 2 3]}}]
          record (#'assembly/build-record :test/main {} {:n 0} events 1700000000000)]
      (is (= :seed (:event-id record))
          ":event-id is the resolved event keyword")
      (is (= [:seed 1 2 3] (:trigger-event record))
          ":trigger-event is the full event vector — payload preserved")
      ;; rf2-bh56rc: :committed-at is the supplied causal time verbatim —
      ;; build-record performs NO clock read of its own.
      (is (= 1700000000000 (:committed-at record))
          ":committed-at is the supplied committed-at (token :time-ms),
           not an ambient now-ms read"))))

;; ---- rf2-7kxxx: find-trigger-event must not synthesise [eid] when
;; ---- :event tag is absent on the fallback arm ----------------------------
;;
;; Per audit r3 §F2: the fallback arm of `find-trigger-event` returns
;; `:event nil` when the buffered event carries an `:event-id` tag but no
;; `:event` tag, and (per rf2-kl5p1) `build-record` then omits the
;; `:trigger-event` slot entirely. Synthesising `[eid]` as `:event` would
;; misrepresent an event that carried payload (e.g. `[:foo "bar" 42]`) as a
;; payload-less event, so there is no such fabrication; consumers rendering
;; 'what triggered this cascade' either see the real event vector or none at
;; all.

(deftest find-trigger-event-fallback-does-not-synthesise-event-vector
  (testing "find-trigger-event's fallback arm — :event-id tag present,
            :event tag absent — returns :event nil rather than fabricating
            [eid]; the calling build-record then omits :trigger-event"
    (let [tag-less-fallback [{:op-type   :rf.event
                              :operation :rf.event
                              :tags      {:frame             :test/main
                                          :rf.trace/event-id :foo}}]
          trigger           (#'capture/find-trigger-event tag-less-fallback)]
      (is (= :foo (:event-id trigger))
          ":event-id is recovered from the fallback arm")
      (is (nil? (:event trigger))
          ":event is NOT synthesised as [:foo] — the slot is nil so
           build-record can decide not to emit a fabricated
           :trigger-event"))

    ;; Build-record consumes the fallback's nil :event via its conditional
    ;; cond-> (rf2-kl5p1) and emits no :trigger-event slot.
    (rf/reg-frame :test/main {})
    (let [tag-less-events [{:op-type   :rf.event
                            :operation :rf.event
                            :tags      {:frame             :test/main
                                        :rf.trace/event-id :foo}}]
          record          (#'assembly/build-record
                            :test/main nil nil tag-less-events
                            1700000000000  ; rf2-bh56rc: committed-at (token :time-ms)
                            :halted-destroy
                            {:operation :rf.frame/destroyed-mid-drain})]
      (is (= :foo (:event-id record))
          ":event-id lands on the record from the fallback arm")
      (is (not (contains? record :trigger-event))
          ":trigger-event is absent — no synthesised vector survives
           into the record"))))

(deftest find-trigger-event-fallback-preserves-payload-when-event-tag-present
  (testing "find-trigger-event's fallback arm preserves the full event
            vector when the buffered event DOES carry an :event tag —
            the rf2-7kxxx fix must not strip payload from the
            non-degenerate fallback path"
    (let [events  [{:op-type   :rf.event
                    :operation :rf.event
                    :tags      {:frame             :test/main
                                :rf.trace/event-id :foo
                                :rf.event/v        [:foo "bar" 42]}}]
          trigger (#'capture/find-trigger-event events)]
      (is (= :foo (:event-id trigger)))
      (is (= [:foo "bar" 42] (:event trigger))
          "the full event vector survives — payload is preserved"))))

;; ---- rf2-ee38b: find-trigger-event fallback arm does not pin :dispatch-id --
;;
;; Per the correctness review (ai/findings/review/correctness--
;; implementation-epoch.md): the run-start arm (rf2-rly4a) reads
;; :dispatch-id from the canonical `:event/run-start` trace, which is
;; correct. Surfacing the :dispatch-id of an arbitrary non-run-start trace
;; (e.g. an error trace from a rejected dispatch) on the fallback arm would
;; be wrong: Spec-Schemas §`:rf/epoch-record` documents :dispatch-id as
;; pinned from the run-start tag and ABSENT for a no-run-start cascade —
;; so the fallback arm does NOT pin a dispatch-id; only the run-start arm
;; does.

(deftest find-trigger-event-fallback-omits-dispatch-id
  (testing "find-trigger-event's fallback arm (no :event/run-start
            buffered) does NOT surface :dispatch-id — even when the
            fallback trace carries one — matching the spec's 'absent for
            a no-run-start cascade' shape (rf2-ee38b)"
    (let [fallback-with-did [{:op-type   :rf.event
                              :operation :rf.event
                              :tags      {:frame                :test/main
                                          :rf.trace/event-id    :foo
                                          :rf.trace/dispatch-id 99}}]
          trigger           (#'capture/find-trigger-event fallback-with-did)]
      (is (= :foo (:event-id trigger))
          ":event-id is recovered from the fallback arm")
      (is (nil? (:dispatch-id trigger))
          ":dispatch-id is NOT pinned from the fallback (non-run-start)
           trace — only the run-start arm is its canonical source"))
    ;; build-record then omits the :dispatch-id slot entirely (the cond->
    ;; drops nil), matching the schema's 'absent' shape.
    (rf/reg-frame :test/main {})
    (let [events [{:op-type   :rf.event
                   :operation :rf.event
                   :tags      {:frame                :test/main
                               :rf.trace/event-id    :foo
                               :rf.trace/dispatch-id 99}}]
          record (#'assembly/build-record
                  :test/main nil nil events
                  1700000000000  ; rf2-bh56rc: committed-at (token :time-ms)
                  :halted-destroy
                  {:operation :rf.frame/destroyed-mid-drain})]
      (is (not (contains? record :dispatch-id))
          "the record omits :dispatch-id when only the fallback arm
           resolved the trigger — no incidental id leaks onto the slot"))))

(deftest find-trigger-event-run-start-arm-still-pins-dispatch-id
  (testing "the run-start arm remains the canonical :dispatch-id source
            (rf2-rly4a) — the rf2-ee38b fallback change must not regress
            it"
    (let [events  [{:op-type   :rf.event
                    :operation :rf.event/run-start
                    :tags      {:frame                :test/main
                                :rf.trace/phase       :run-start
                                :rf.trace/event-id    :foo
                                :rf.event/v           [:foo]
                                :rf.trace/dispatch-id 7}}]
          trigger (#'capture/find-trigger-event events)]
      (is (= 7 (:dispatch-id trigger))
          "the run-start arm pins :dispatch-id from the canonical
           :event/run-start trace"))))

;; ---- rf2-eo4pr: record-observation! guards its swap -----------------------
;;
;; `notify-listeners!` invokes `record-observation!` once per listener per
;; drain-settle. For the steady state — a long-lived listener observing the
;; same frame on every cascade — the cb's observed-frames set already
;; contains the frame-id, and an unconditional `swap!` would fire every
;; atom watcher N times per settle for ZERO semantic change. The guard
;; inside `record-observation!` short-circuits the already-observed case;
;; this test pins that no-op via an atom watcher (the canonical witness:
;; Clojure's persistent-map `assoc`/`update` can return an identical map
;; when the value is unchanged, so identity-equality alone is too weak,
;; but `add-watch` ALWAYS fires on every successful `swap!`).

(deftest record-observation-no-op-when-already-observed
  (testing "record-observation! does NOT swap! the observed-frames-by-cb
            atom when the (cb-id, frame-id) pair is already present —
            repeated drain-settles for a stable listener-observing-frame
            pairing leave the atom untouched (no watcher fires)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [observed-atom @#'state/observed-frames-by-cb
          swap-count    (atom 0)]
      (add-watch observed-atom ::swap-counter
                 (fn [_ _ _ _] (swap! swap-count inc)))
      (try
        ;; First cascade — the cb observes :test/main for the first time;
        ;; the membership is added. One swap is expected here (the new
        ;; observation lands on the atom).
        (rf/register-listener! :epoch ::watcher (fn [_] nil))
        (rf/dispatch-sync [:seed] {:frame :test/main})
        (is (contains? (get @observed-atom ::watcher) :test/main)
            "after the first cascade, the cb has :test/main in its set")

        (let [swaps-after-first @swap-count]
          (is (pos? swaps-after-first)
              "the first observation triggered at least one swap")

          ;; Drive more cascades — every one is a repeat observation of
          ;; the same (cb-id, frame-id) pair. The guard must short-circuit
          ;; so no further swap fires.
          (rf/dispatch-sync [:inc] {:frame :test/main})
          (rf/dispatch-sync [:inc] {:frame :test/main})
          (rf/dispatch-sync [:inc] {:frame :test/main})

          (is (= swaps-after-first @swap-count)
              "no further swap! ran across the three repeat cascades —
               record-observation! short-circuited on the already-observed
               case")
          (is (= 1 (count (get @observed-atom ::watcher)))
              "the cb's observed-frames set still has exactly one entry")
          (is (= 4 (count (rf/epoch-history :test/main)))
              "the four cascades did land four epoch records — the cb was
               notified each time, but the observation atom stayed put"))
        (finally
          (remove-watch observed-atom ::swap-counter))))))

;; ---- rf2-douii: configure! validates at the boundary ---------------------
;;
;; Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: `configure!` validates
;; `:depth` and `:trace-events-keep` at the boundary — invalid values (nil,
;; non-numeric) are silently dropped and the prior valid config survives.
;; Without that boundary check a bad value would survive configuration and
;; explode later at `record!` time when `pos?` / `nat-int?` ran on the stored
;; value.

(deftest configure-rejects-nil-depth
  (testing "(rf/configure! {:epoch-history {:depth nil}}) is a no-op; the
            previously-stored depth survives"
    (rf/configure! {:epoch-history {:depth 7}})
    (is (= 7 (:depth (epoch/current-config))))

    (rf/configure! {:epoch-history {:depth nil}})
    (is (= 7 (:depth (epoch/current-config)))
        ":depth nil silently dropped — prior 7 survives")))

(deftest configure-rejects-non-numeric-depth
  (testing "(rf/configure! {:epoch-history {:depth \"five\"}) is a no-op"
    (rf/configure! {:epoch-history {:depth 7}})
    (rf/configure! {:epoch-history {:depth "five"}})
    (is (= 7 (:depth (epoch/current-config)))
        ":depth non-numeric silently dropped")))

(deftest configure-rejects-negative-depth
  (testing "(rf/configure! {:epoch-history {:depth -1}}) is a no-op"
    (rf/configure! {:epoch-history {:depth 7}})
    (rf/configure! {:epoch-history {:depth -1}})
    (is (= 7 (:depth (epoch/current-config)))
        ":depth negative silently dropped")))

(deftest configure-rejects-invalid-trace-events-keep
  (testing "(rf/configure! {:epoch-history {:trace-events-keep <bad>}}) is a no-op"
    (rf/configure! {:epoch-history {:trace-events-keep 3}})
    (is (= 3 (:trace-events-keep (epoch/current-config))))

    (rf/configure! {:epoch-history {:trace-events-keep nil}})
    (is (= 3 (:trace-events-keep (epoch/current-config)))
        ":trace-events-keep nil silently dropped")

    (rf/configure! {:epoch-history {:trace-events-keep "no"}})
    (is (= 3 (:trace-events-keep (epoch/current-config)))
        ":trace-events-keep non-numeric silently dropped")

    (rf/configure! {:epoch-history {:trace-events-keep -5}})
    (is (= 3 (:trace-events-keep (epoch/current-config)))
        ":trace-events-keep negative silently dropped")))

(deftest configure-accepts-zero
  (testing "depth 0 and :trace-events-keep 0 are non-negative integers
            and must be accepted (0 has well-defined meaning — depth 0
            disables recording; :trace-events-keep 0 drops every
            record's :trace-events)"
    (rf/configure! {:epoch-history {:depth 0}})
    (is (= 0 (:depth (epoch/current-config))))

    (rf/configure! {:epoch-history {:trace-events-keep 0}})
    (is (= 0 (:trace-events-keep (epoch/current-config))))))

(deftest configure-partial-update-rejects-bad-key-only
  (testing "a configure call carrying one valid and one invalid key
            applies the valid one and drops the invalid one — failure
            in one key never poisons another"
    (rf/configure! {:epoch-history {:depth 7 :trace-events-keep 4}})
    (rf/configure! {:epoch-history {:depth 11 :trace-events-keep nil}})
    (let [cfg (epoch/current-config)]
      (is (= 11 (:depth cfg))
          "the valid :depth update was applied")
      (is (= 4 (:trace-events-keep cfg))
          "the invalid :trace-events-keep update was dropped"))))

;; ---- rf2-douii: ring-eviction interaction with restore -------------------
;;
;; Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: ring-buffer eviction and
;; restore preconditions were each covered in isolation but never
;; together. A restore against an epoch-id that the ring has since evicted
;; must deterministically fail as :rf.epoch/restore-unknown-epoch with the
;; current (post-eviction) history-size in its tags, and must leave app-db
;; unchanged.

(deftest restore-after-eviction-fails-as-unknown-epoch
  (testing "an epoch-id that was evicted by ring-depth-cap restores as
            :rf.epoch/restore-unknown-epoch (app-db unchanged; failure
            tags carry the current history-size, which equals depth)"
    (rf/configure! {:epoch-history {:depth 3}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    ;; Capture the epoch-id of the FIRST cascade before later cascades
    ;; evict it. The ring depth is 3, so dispatching 4 more :inc events
    ;; pushes the head out.
    (let [evicted-id (-> (rf/epoch-history :test/main) first :epoch-id)]
      (dotimes [_ 4] (rf/dispatch-sync [:inc] {:frame :test/main}))

      (let [history-after (rf/epoch-history :test/main)
            pre-restore   (rf/app-db-value :test/main)
            recorded      (record-trace!)
            ok?           (rf/restore-epoch! :test/main evicted-id)]
        (is (= 3 (count history-after))
            "ring still capped at depth 3")
        (is (not-any? #(= evicted-id (:epoch-id %)) history-after)
            "the captured epoch-id is no longer in history")
        (is (false? ok?) "restore rejected — epoch evicted")
        (is (= pre-restore (rf/app-db-value :test/main))
            "app-db unchanged across the rejected restore")

        (let [ev (some (fn [ev]
                         (when (= :rf.epoch/restore-unknown-epoch
                                  (:operation ev))
                           ev))
                       @recorded)]
          (is (some? ev) ":rf.epoch/restore-unknown-epoch fired")
          (is (= :test/main      (:frame (:tags ev))))
          (is (= evicted-id      (:rf.epoch/id (:tags ev))))
          (is (= 3 (:history-size (:tags ev)))
              "history-size tag reflects the post-eviction size, not
               the pre-eviction count"))))))

;; ---- rf2-douii: depth 0 still fires listeners ----------------------------
;;
;; Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: `configure!`'s docstring
;; documents that depth 0 'disables recording (assembled records can
;; still fire on listeners but nothing lands in the ring buffer)'. The
;; pre-existing `depth-zero-disables-recording` test covers only the
;; ring side; this test pins the listener-fanout half of the contract.

(deftest depth-zero-still-fires-listeners
  (testing "depth 0 disables the ring buffer but the assembled record
            still fans out to registered listeners"
    (rf/configure! {:epoch-history {:depth 0}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [seen (atom [])]
      (rf/register-listener! :epoch ::watcher (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})

      (is (= [] (rf/epoch-history :test/main))
          "ring buffer is empty at depth 0")
      (is (= 2 (count @seen))
          "listener still received an assembled record per drain-settle")
      (is (= [:seed :inc] (mapv :event-id @seen))
          "records carry the per-cascade trigger event-id")
      (is (every? #(contains? % :db-after) @seen)
          "records carry the post-settle db-after")
      (is (every? #(contains? % :sub-runs) @seen))
      (is (every? #(contains? % :renders)  @seen))
      (is (every? #(contains? % :effects)  @seen)))))

;; ---- rf2-douii: rejected restore/reset paths do not mutate history /
;; ---- do not notify listeners --------------------------------------------
;;
;; Per refactor-audit r2 (rf2-lwn4t) §rf2-douii: the rejection tests
;; (`restore-failure-*` / `replace-app-db!-failure-*`) verify the trace
;; emission and app-db stability but do NOT explicitly pin the related
;; bookkeeping contracts:
;;
;;   1. A rejected restore does not append a new record to history.
;;   2. A rejected restore does not fire registered epoch listeners.
;;   3. A rejected replace-app-db! does not append a new record.
;;   4. A rejected replace-app-db! does not fire registered listeners.
;;
;; A regression that swapped emission-on-failure for fanout-on-failure
;; (or appended a synthetic failure record) would slip through the
;; existing suite. Pin both halves explicitly.

(deftest rejected-restore-does-not-touch-history-or-listeners
  (testing "a rejected restore-epoch! (unknown-epoch, the simplest
            rejection path) leaves the history vector untouched and
            does not fire registered listeners"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history-before (rf/epoch-history :test/main)
          seen           (atom [])]
      (rf/register-listener! :epoch ::watcher (fn [r] (swap! seen conj r)))
      (is (false? (rf/restore-epoch! :test/main :no-such-epoch))
          "restore rejected")

      (is (= history-before (rf/epoch-history :test/main))
          "history vector unchanged across the rejected restore")
      (is (= [] @seen)
          "no listener fanout for the rejected restore"))))

(deftest rejected-replace-frame-state-app-only-does-not-touch-history-or-listeners
  (testing "a rejected replace-app-db! (during-drain rejection — the
            simplest rejection path that exercises the reset surface)
            leaves history untouched and does not fire listeners"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [history-before (rf/epoch-history :test/main)
          seen           (atom [])
          attempt        (atom nil)]
      (rf/register-listener! :epoch ::watcher (fn [r] (swap! seen conj r)))
      ;; A handler that calls replace-app-db! synchronously during a
      ;; drain — the during-drain precondition fails. The reset itself
      ;; must not fan out, but the surrounding drain still settles
      ;; normally (which appends ONE record — the one for :try-reset).
      (rf/reg-event :try-reset
        (fn [{:keys [db]} _]
          (reset! attempt (rf/replace-frame-state! :test/main {:rf.db/app {:n 999}}))
          {:db db}))
      (rf/dispatch-sync [:try-reset] {:frame :test/main})

      (is (false? @attempt) "reset rejected")
      (let [history-after (rf/epoch-history :test/main)
            new-records   (drop (count history-before) history-after)]
        (is (= 1 (count new-records))
            "exactly one new record — the drain settle for :try-reset
             itself; no synthetic record from the rejected reset")
        (is (= :try-reset (:event-id (first new-records)))
            "the new record's event-id is :try-reset (not
             :rf.epoch/db-replaced — the synthetic event-id the
             reset surface would have used on success)"))

      (is (= 1 (count @seen))
          "listener fired exactly once — for the :try-reset cascade
           settle, NOT for the rejected reset")
      (is (= :try-reset (:event-id (first @seen)))
          "the lone listener invocation is for the outer cascade, not
           a synthetic reset-rejection record"))))

;; ---- rf2-mrsck: per-leaf smoke tests --------------------------------------
;;
;; Per the cluster prompt and Mike's 2026-05-16 convention: every impl
;; commit ships per-leaf smoke tests in the same commit. The full
;; coverage matrix lives in rf2-vq5o0; these smokes pin the
;; load-bearing slot for each new piece (the rollup, the projected
;; helper, the finite default) so a regression that nukes the
;; mechanism fails this file rather than waiting for rf2-vq5o0's
;; deeper sweep.

(deftest smoke-sensitive-rollup-default-false
  (testing "rf2-mrsck — :rf.epoch/sensitive? is false on records whose
            cascade involves no sensitive paths and no sensitive handlers"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [r (last (rf/epoch-history :test/main))]
      (is (false? (:rf.epoch/sensitive? r))
          "non-sensitive cascade — rollup reads false"))))

(deftest smoke-sensitive-rollup-false-from-handler-meta-removed
  (testing "Handler-meta `:sensitive?` annotation has been removed —
            the rollup no longer reflects a handler-level stamp.
            Path-marked classification (schema-slot `:sensitive?`)
            is the v2 mechanism."
    (rf/reg-frame :test/main {})
    (rf/reg-event :secret-write
                     {:sensitive? true}   ;; stored, no longer consulted
                     (fn [{:keys [db]} _] {:db (assoc db :token "shhh")}))
    (rf/dispatch-sync [:secret-write] {:frame :test/main})

    (let [r (last (rf/epoch-history :test/main))]
      (is (false? (:rf.epoch/sensitive? r))
          "rollup reads false — handler-meta annotation no longer stamps"))))

(deftest smoke-projected-record-redacts-and-keeps-bookkeeping
  (testing "rf2-mrsck — projected-record routes :db-before / :db-after /
            :trigger-event / :trace-events through elide-wire-value with
            off-box defaults; bookkeeping slots and structured projections
            pass through unchanged"
    (rf/reg-frame :test/main {})
    ;; EP-0025: durable app-db classification rides the commit-plane
    ;; classification effects. Declare the `[:auth :password]` sensitive path
    ;; through `elision/apply-classification-effects` (`:source :effect`) — it
    ;; populates [:rf.runtime/elision :sensitive-declarations] directly (the
    ;; same registry write a `reg-event` returning `:sensitive` performs),
    ;; pinning the smoke against the elision walker contract.
    (frame/swap-runtime-db! :test/main
      (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]]
                       {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw       (last (rf/epoch-history :test/main))
          projected (epoch/projected-record raw)]
      (is (= "topsecret" (get-in raw [:db-after :auth :password]))
          "raw record carries the unredacted password (in-process)")
      (is (seq (re-frame.elision/sensitive-declarations :test/main))
          "elision registry populated for :test/main")
      (is (= :rf/redacted
             (get-in projected [:db-after :auth :password]))
          "projected record substitutes :rf/redacted for the sensitive slot")
      (is (= (:epoch-id raw) (:epoch-id projected))
          ":epoch-id passes through")
      (is (= (:event-id raw) (:event-id projected))
          ":event-id passes through")
      (is (= (:outcome raw)  (:outcome projected))
          ":outcome passes through")
      (is (= (:sub-runs raw) (:sub-runs projected))
          "structured :sub-runs slot passes through unchanged")
      (is (= (:effects raw)  (:effects projected))
          "structured :effects slot passes through unchanged")
      (is (true? (:rf.epoch/sensitive? raw))
          "schema-derived rollup also fires when a sensitive path
           resolves to a non-nil leaf in :db-after"))))

(deftest smoke-projected-history-projects-each-record
  (testing "rf2-mrsck — projected-history walks the ring once and
            returns the projected vector"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)
          ph      (epoch/projected-history :test/main)]
      (is (= (count history) (count ph))
          "projected-history returns one element per ring record")
      (is (every? map? ph) "each element is a map")
      (is (= (mapv :epoch-id history) (mapv :epoch-id ph))
          ":epoch-id ordering matches the raw ring"))))

(deftest smoke-projected-record-handles-nil-input
  (testing "rf2-mrsck — projected-record returns nil for non-map input
            (a missing-epoch lookup)"
    (is (nil? (epoch/projected-record nil)))
    (is (nil? (epoch/projected-record :not-a-map)))))

(deftest smoke-trace-events-keep-fixture-override
  (testing "rf2-wmki8 — the TEST FIXTURE forces :trace-events-keep 5 (a
            keep<depth OVERRIDE so the elision path is reachable with a
            handful of dispatches), NOT the shipped runtime default. This
            asserts the fixture's override value, deliberately distinct
            from the shipped default of 50 (see
            `shipped-trace-events-keep-default-is-50` for the real
            default)."
    (is (= 5 (:trace-events-keep (epoch/current-config)))
        "fixture OVERRIDE — see the :init-fn configure! call; NOT the shipped default")))

(deftest shipped-trace-events-keep-default-is-50
  (testing "rf2-wmki8 — the SHIPPED runtime default :trace-events-keep is
            50 (= :depth, so every retained epoch keeps its trace; Mike
            pair-debug 2026-05-27). Asserted against the source-of-truth
            private `default-trace-events-keep` var, bypassing the
            fixture's keep<depth override. Pins the default consistently
            with docs/core/api/01-core.md and `re-frame.epoch.state`."
    (is (= 50 @#'state/default-trace-events-keep)
        "the source-of-truth default var ships 50")
    ;; The accessor is wired to fall back to that var when the slot is
    ;; absent from the live config map (the shape a fresh process / a
    ;; partial `configure!` leaves). Drive a config WITHOUT the slot and
    ;; confirm the accessor reports the shipped 50 — proving the var is the
    ;; live default, not just a declared constant.
    ;;
    ;; rf2-yw1w1u — KEEPS direct private-var access: `configure!` /
    ;; `merge-config!` MERGES, so it cannot produce a config map MISSING
    ;; the `:trace-events-keep` slot (the exact shape this test needs to
    ;; exercise the accessor's fallback). Only a raw `reset!` of the
    ;; private `config` can build that shape — the shared fixture's
    ;; reset-to-default always carries the slot.
    (reset! @#'state/config {:depth 50 :redact-fn nil})
    (is (= 50 (state/trace-events-keep))
        "trace-events-keep accessor falls back to the shipped 50 default")
    (is (= 50 (:trace-events-keep (epoch/current-config) 50))
        "current-config's :trace-events-keep resolves to the shipped 50")))

;; ============================================================================
;;  rf2-7i872 — write-boundary liveness race (validate-then-destroy)
;; ============================================================================
;;
;; restore-epoch! / replace-app-db! validate preconditions against a LIVE
;; frame, then write the frame's container. A frame destroyed in the window
;; BETWEEN validation and the write (a tool gesture interleaving with the
;; owning component's teardown) leaves `frame/app-db-container` returning
;; nil, so the choke-point `adapter/replace-container!` silently no-ops the
;; write. Pre-rf2-7i872 the epoch surfaces still emitted success and
;; returned `true` (and `replace-app-db!` recorded + fanned out a SYNTHETIC
;; epoch for the destroyed frame). Per Tool-Pair §Surface behaviour against
;; destroyed frames a destroyed-frame write is a STRUCTURAL FAILURE
;; (:rf.error/no-such-handler, kind :frame, returns false).
;;
;; The race window is reproduced two ways:
;;   (a) the SEAM — validate (live) → destroy → perform!, exactly the two
;;       steps the public fn sequences, with the destroy injected between.
;;   (b) the PUBLIC surface — with-redefs the precondition check to destroy
;;       the frame after a real (live) validation, proving the public
;;       restore-epoch! / replace-app-db! honour the write-boundary guard.

(deftest restore-epoch-validate-then-destroy-reports-honest-failure-seam
  (testing "rf2-7i872 — perform-restore! against a frame destroyed AFTER a
            live precondition pass returns false, emits
            :rf.error/no-such-handler (kind :frame), and does NOT emit
            :rf.epoch/restored. The drop is the no-op write
            adapter/replace-container! makes against the now-nil container."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (rf/dispatch-sync [:inc]  {:frame :test/short-lived})

    (let [target-id (-> (rf/epoch-history :test/short-lived) first :epoch-id)
          ;; (1) Validate against the LIVE frame — passes, yields the epoch.
          {:keys [outcome epoch]} (tool-pair/check-restore-preconditions!
                                    :test/short-lived target-id)]
      (is (= :ok outcome) "precondition validation passed against the live frame")

      ;; (2) The race: the frame is destroyed in the validate→write window.
      (rf/destroy-frame! :test/short-lived)

      ;; (3) Perform the write at the boundary — the container is now nil.
      (let [recorded (record-trace!)
            result   (tool-pair/perform-restore! :test/short-lived epoch)]
        (is (false? result)
            "perform-restore! reports HONEST failure (false) — NOT a synthetic
             success — when the frame disappeared between validate and write")
        (is (has-error-op? @recorded :rf.error/no-such-handler)
            ":rf.error/no-such-handler fired at the write boundary")
        (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                       @recorded)]
          (is (= :frame (:kind (:tags ev))) "tags carry :kind :frame")
          (is (= :test/short-lived (:frame (:tags ev))) "tags carry :frame"))
        (is (not-any? #(= :rf.epoch/restored (:operation %)) @recorded)
            "no :rf.epoch/restored success trace for the destroyed frame")))))

(deftest restore-epoch-public-validate-then-destroy-returns-false
  (testing "rf2-7i872 — the PUBLIC restore-epoch! returns false (not a false
            success) when the frame is destroyed AFTER a live precondition
            pass but BEFORE the container write. The precondition check is
            real; the destroy is injected into the validate→write window."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})

    (let [target-id (-> (rf/epoch-history :test/short-lived) first :epoch-id)
          real-check tool-pair/check-restore-preconditions!
          recorded   (record-trace!)]
      ;; Inject the destroy into the validate→write window: run the REAL
      ;; (live) precondition check, then destroy the frame before the
      ;; orchestrator reaches perform-restore!.
      (with-redefs [tool-pair/check-restore-preconditions!
                    (fn [frame-id epoch-id]
                      (let [r (real-check frame-id epoch-id)]
                        (rf/destroy-frame! frame-id)
                        r))]
        (let [result (rf/restore-epoch! :test/short-lived target-id)]
          (is (false? result)
              "public restore-epoch! returns false for the validate-then-destroy
               race — the write-boundary guard caught the no-op write")
          (is (has-error-op? @recorded :rf.error/no-such-handler)
              ":rf.error/no-such-handler fired")
          (is (not-any? #(= :rf.epoch/restored (:operation %)) @recorded)
              "no :rf.epoch/restored success trace"))))))

(deftest replace-frame-state-app-only-validate-then-destroy-reports-honest-failure-seam
  (testing "rf2-7i872 — perform-replace-frame-state! against a frame destroyed
            AFTER a live precondition pass returns false, emits
            :rf.error/no-such-handler (kind :frame), and does NOT record a
            synthetic epoch, emit :rf.epoch/db-replaced, or fan a record to
            listeners for the destroyed frame."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})

    ;; (1) Validate against the LIVE frame — passes.
    (let [{:keys [outcome]} (tool-pair/check-replace-frame-state-preconditions!
                              :test/short-lived {:rf.db/app {:n 999}})]
      (is (= :ok outcome) "precondition validation passed against the live frame")

      ;; A listener that records every fanned record — it must NOT see a
      ;; record for the destroyed frame.
      (let [fanned   (atom [])]
        (rf/register-listener! :epoch ::fan-watcher (fn [r] (swap! fanned conj r)))
        ;; Let the listener observe the live frame once so it has an
        ;; observation entry (mirrors a real tool); reset the ledger after.
        (rf/dispatch-sync [:seed] {:frame :test/short-lived})
        (reset! fanned [])

        ;; (2) The race: destroy in the validate→write window.
        (rf/destroy-frame! :test/short-lived)

        ;; (3) Perform the reset at the boundary — container is now nil.
        (let [recorded (record-trace!)
              result   (#'epoch/perform-replace-frame-state! :test/short-lived {:rf.db/app {:n 999}})]
          (is (false? result)
              "perform-replace-frame-state! reports HONEST failure (false) — NOT a
               synthetic success — for the validate-then-destroy race")
          (is (has-error-op? @recorded :rf.error/no-such-handler)
              ":rf.error/no-such-handler fired at the write boundary")
          (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                         @recorded)]
            (is (= :frame (:kind (:tags ev))) "tags carry :kind :frame")
            (is (= :test/short-lived (:frame (:tags ev))) "tags carry :frame"))
          (is (not-any? #(= :rf.epoch/db-replaced (:operation %)) @recorded)
              "no :rf.epoch/db-replaced success trace for the destroyed frame")
          (is (empty? @fanned)
              "no synthetic epoch fanned out to listeners for the destroyed frame")
          (is (= [] (rf/epoch-history :test/short-lived))
              "no synthetic epoch recorded into the (dropped) ring for the
               destroyed frame"))))))

(deftest replace-frame-state-app-only-public-validate-then-destroy-returns-false
  (testing "rf2-7i872 — the PUBLIC replace-frame-state! returns false when the
            frame is destroyed AFTER a live precondition pass but BEFORE the
            container write."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})

    (let [real-check tool-pair/check-replace-frame-state-preconditions!
          recorded   (record-trace!)]
      (with-redefs [tool-pair/check-replace-frame-state-preconditions!
                    (fn [frame-id new-frame-state]
                      (let [r (real-check frame-id new-frame-state)]
                        (rf/destroy-frame! frame-id)
                        r))]
        (let [result (rf/replace-frame-state! :test/short-lived {:rf.db/app {:n 999}})]
          (is (false? result)
              "public replace-frame-state! returns false for the validate-then-destroy
               race")
          (is (has-error-op? @recorded :rf.error/no-such-handler)
              ":rf.error/no-such-handler fired")
          (is (not-any? #(= :rf.epoch/db-replaced (:operation %)) @recorded)
              "no :rf.epoch/db-replaced success trace"))))))

;; ============================================================================
;;  rf2-s93722 — POST-LIVENESS teardown race (the second half of the window)
;; ============================================================================
;;
;; rf2-7i872 closed the validate→write window by re-resolving the container
;; via `live-container-or-fail` at the write boundary. But that liveness check
;; closes only HALF the window: a frame destroyed AFTER `live-container-or-
;; fail` passes (it resolved a LIVE container) but BEFORE the actual
;; `frame/replace-*` write returns STILL slips through — the liveness check
;; said "live", yet the physical write lands against a now-destroyed frame and
;; the choke-point `commit-frame-transition!` returns `nil` (the nil-container
;; guard). The four perform helpers (rf2-s93722) capture that return: `nil` is
;; the destroyed-frame signal (a non-nil — possibly EMPTY — changed-key-set
;; means the write landed, even a no-op), so they surface the canonical
;; `:rf.error/no-such-handler` (kind :frame) / `false` BEFORE any success
;; telemetry, synthetic epoch, or listener fanout. Ignoring the return would
;; emit success telemetry and a fanned-out synthetic epoch for a write that
;; never happened. Empty-set / no-op writes stay successful.
;;
;; The race is reproduced by redefining the boundary `frame/replace-*` write
;; to DESTROY the frame and then delegate to the real fn — so liveness has
;; already passed (it ran before the redef'd write) and the real write returns
;; nil against the now-destroyed frame, exactly the post-liveness window.

(deftest restore-epoch-post-liveness-teardown-returns-false
  (testing "rf2-s93722 — perform-restore! returns false (NOT a synthetic
            success), emits :rf.error/no-such-handler (kind :frame), and does
            NOT emit :rf.epoch/restored when the frame is destroyed AFTER the
            write-boundary liveness check passes but BEFORE replace-frame-state!
            returns (the nil-return post-liveness teardown window)."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})
    (rf/dispatch-sync [:inc]  {:frame :test/short-lived})

    (let [target-id (-> (rf/epoch-history :test/short-lived) first :epoch-id)
          {:keys [outcome epoch]} (tool-pair/check-restore-preconditions!
                                    :test/short-lived target-id)]
      (is (= :ok outcome) "precondition validation passed against the live frame")

      (let [real-write frame/replace-frame-state!
            recorded   (record-trace!)
            ;; The post-liveness window: live-container-or-fail (inside
            ;; perform-restore!) resolves a LIVE container, THEN this redef'd
            ;; write destroys the frame and delegates to the real write, which
            ;; now returns nil against the destroyed frame.
            result     (with-redefs [frame/replace-frame-state!
                                     (fn [frame-id fs]
                                       (rf/destroy-frame! frame-id)
                                       (real-write frame-id fs))]
                         (tool-pair/perform-restore! :test/short-lived epoch))]
        (is (false? result)
            "perform-restore! reports HONEST failure (false) — the nil write
             return is checked, not ignored")
        (is (has-error-op? @recorded :rf.error/no-such-handler)
            ":rf.error/no-such-handler fired for the post-liveness teardown")
        (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                       @recorded)]
          (is (= :frame (:kind (:tags ev))) "tags carry :kind :frame")
          (is (= :test/short-lived (:frame (:tags ev))) "tags carry :frame"))
        (is (not-any? #(= :rf.epoch/restored (:operation %)) @recorded)
            "no :rf.epoch/restored success trace for the post-liveness drop")))))

;; rf2-obi8rr — the resources restore-reconcile success rows
;; (:rf.resource/restored / :rf.resource/owner-released) must NOT leak when the
;; frame-state install fails. The reconcile runs BEFORE the atomic install and
;; defers those rows (riding them back as metadata); perform-restore! emits
;; them via :resources/commit-restore-reconcile! only on the install-success
;; branch. These tests stub the two resources hooks (the resources artefact is
;; not on the epoch test classpath) — the reconcile defers a `:rf.resource/
;; restored` intent and the commit hook emits whatever the reconcile deferred,
;; exactly mirroring the real ssr.cljc bodies — and drive both branches.

(def ^:private rf2-obi8rr-deferred-key
  ;; the metadata key the real ssr.cljc reconcile uses; the stub mirrors it so
  ;; the commit stub reads the deferred intents the same way the real one does.
  :re-frame.resources.ssr/deferred-trace-intents)

(defn- with-stub-resources-restore-hooks
  "Install stub :resources/reconcile-on-restore (defers a :rf.resource/restored
  intent as metadata under :defer-traces? true) + :resources/commit-restore-
  reconcile! (emits whatever intents the reconcile deferred), run `f`, restore."
  [f]
  (let [rk :resources/reconcile-on-restore
        ck :resources/commit-restore-reconcile!
        r0 (late-bind/get-fn rk)
        c0 (late-bind/get-fn ck)]
    (try
      (late-bind/set-fn! rk
                         (fn [rdb frame-id {:keys [defer-traces?]}]
                           (let [intents [{:level :rf.epoch
                                           :op    :rf.resource/restored
                                           :tags  {:rf.frame/id frame-id :reconciled 1}}
                                          {:level :rf.epoch
                                           :op    :rf.resource/owner-released
                                           :tags  {:rf.frame/id frame-id
                                                   :owner [:route :r "nav-OLD"]
                                                   :reason :stale-nav-orphan}}]]
                             (if defer-traces?
                               (vary-meta rdb assoc rf2-obi8rr-deferred-key intents)
                               (do (doseq [{:keys [level op tags]} intents]
                                     (trace/emit! level op tags))
                                   rdb)))))
      (late-bind/set-fn! ck
                         (fn [rdb]
                           (doseq [{:keys [level op tags]} (-> rdb meta (get rf2-obi8rr-deferred-key))]
                             (trace/emit! level op tags))
                           nil))
      (f)
      (finally
        (late-bind/set-fn! rk r0)
        (late-bind/set-fn! ck c0)))))

(deftest restore-failed-install-emits-no-resource-success-traces
  (testing "rf2-obi8rr ACCEPTANCE — a restore whose frame-state install FAILS
            (the post-liveness teardown race: replace-frame-state! returns nil)
            emits NO :rf.resource/restored or :rf.resource/owner-released rows,
            even though resources rode in the snapshot — the reconcile deferred
            them and the commit only fires on a successful install."
    (rf/reg-frame :test/obi8rr-fail {})
    (rf/reg-event :seed-res
      (fn [{rt :rf.db/runtime} _]
        {:db {:n 0}
         :rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/resources :entries :k]
                                  {:status :loaded :data {:x 1}
                                   :active-owners #{[:route :r "nav-OLD"]}})}))
    (rf/reg-event :clear-res (fn [{:keys [db]} _] {:db {:n 1}}))
    (with-stub-resources-restore-hooks
      (fn []
        (rf/dispatch-sync [:seed-res] {:frame :test/obi8rr-fail})
        (let [target-id (-> (rf/epoch-history :test/obi8rr-fail) last :epoch-id)]
          (rf/dispatch-sync [:clear-res] {:frame :test/obi8rr-fail})
          (let [{:keys [outcome epoch]} (tool-pair/check-restore-preconditions!
                                          :test/obi8rr-fail target-id)]
            (is (= :ok outcome) "precondition validation passed against the live frame")
            (let [real-write frame/replace-frame-state!
                  recorded   (record-trace!)
                  result     (with-redefs [frame/replace-frame-state!
                                           (fn [fid fs]
                                             (rf/destroy-frame! fid)
                                             (real-write fid fs))]
                               (tool-pair/perform-restore! :test/obi8rr-fail epoch))]
              (is (false? result) "perform-restore! reports honest failure for the nil-write teardown")
              (is (not-any? #(= :rf.epoch/restored (:operation %)) @recorded)
                  "no :rf.epoch/restored (the install never landed)")
              (is (not-any? #(= :rf.resource/restored (:operation %)) @recorded)
                  "ACCEPTANCE — no :rf.resource/restored leaked for the failed restore")
              (is (not-any? #(= :rf.resource/owner-released (:operation %)) @recorded)
                  "ACCEPTANCE — no :rf.resource/owner-released leaked for the failed restore"))))))))

(deftest restore-successful-install-emits-deferred-resource-traces
  (testing "rf2-obi8rr — a SUCCESSFUL restore DOES emit the deferred
            :rf.resource/restored + :rf.resource/owner-released rows (committed
            after the install landed) — the deferral does not drop them on the
            happy path."
    (rf/reg-frame :test/obi8rr-ok {})
    (rf/reg-event :seed-res2
      (fn [{rt :rf.db/runtime} _]
        {:db {:n 0}
         :rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/resources :entries :k]
                                  {:status :loaded :data {:x 1}
                                   :active-owners #{[:route :r "nav-OLD"]}})}))
    (rf/reg-event :clear-res2 (fn [{:keys [db]} _] {:db {:n 1}}))
    (with-stub-resources-restore-hooks
      (fn []
        (rf/dispatch-sync [:seed-res2] {:frame :test/obi8rr-ok})
        (let [target-id (-> (rf/epoch-history :test/obi8rr-ok) last :epoch-id)]
          (rf/dispatch-sync [:clear-res2] {:frame :test/obi8rr-ok})
          (let [recorded (record-trace!)
                ok?      (rf/restore-epoch! :test/obi8rr-ok target-id)]
            (is (true? ok?) "restore succeeded")
            (is (some #(= :rf.epoch/restored (:operation %)) @recorded)
                ":rf.epoch/restored fired (the install landed)")
            (is (some #(= :rf.resource/restored (:operation %)) @recorded)
                "the deferred :rf.resource/restored row is committed on success")
            (is (some #(and (= :rf.resource/owner-released (:operation %))
                            (= [:route :r "nav-OLD"] (:owner (:tags %)))) @recorded)
                "the deferred :rf.resource/owner-released row is committed on success")))))))

(deftest replace-frame-state-app-only-post-liveness-teardown-returns-false
  (testing "rf2-s93722 — perform-replace-frame-state! (app-only map) returns
            false, emits :rf.error/no-such-handler (kind :frame), and does NOT
            record a synthetic epoch, emit :rf.epoch/db-replaced, or fan out a
            record when frame/replace-frame-state! returns nil AFTER the
            liveness check passed."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})

    (let [fanned   (atom [])]
      (rf/register-listener! :epoch ::fan-watcher (fn [r] (swap! fanned conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (reset! fanned [])

      (let [real-write frame/replace-frame-state!
            recorded   (record-trace!)
            result     (with-redefs [frame/replace-frame-state!
                                     (fn [frame-id fs]
                                       (rf/destroy-frame! frame-id)
                                       (real-write frame-id fs))]
                         (#'epoch/perform-replace-frame-state! :test/short-lived {:rf.db/app {:n 999}}))]
        (is (false? result)
            "perform-replace-frame-state! reports HONEST failure (false) for the
             nil-return post-liveness teardown")
        (is (has-error-op? @recorded :rf.error/no-such-handler)
            ":rf.error/no-such-handler fired")
        (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                       @recorded)]
          (is (= :frame (:kind (:tags ev))) "tags carry :kind :frame")
          (is (= :test/short-lived (:frame (:tags ev))) "tags carry :frame"))
        (is (not-any? #(= :rf.epoch/db-replaced (:operation %)) @recorded)
            "no :rf.epoch/db-replaced success trace")
        (is (empty? @fanned)
            "no synthetic epoch fanned out to listeners for the dropped write")
        (is (= [] (rf/epoch-history :test/short-lived))
            "no synthetic epoch recorded into the dropped ring")))))

(deftest replace-frame-state-runtime-only-post-liveness-teardown-returns-false
  (testing "rf2-s93722 — perform-replace-frame-state! (runtime-only map)
            returns false, emits :rf.error/no-such-handler (kind :frame), and
            records / fans out NO synthetic epoch when
            frame/replace-frame-state! returns nil AFTER the liveness check
            passed."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})

    (let [fanned   (atom [])]
      (rf/register-listener! :epoch ::fan-watcher (fn [r] (swap! fanned conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (reset! fanned [])

      (let [real-write frame/replace-frame-state!
            recorded   (record-trace!)
            result     (with-redefs [frame/replace-frame-state!
                                     (fn [frame-id fs]
                                       (rf/destroy-frame! frame-id)
                                       (real-write frame-id fs))]
                         (#'epoch/perform-replace-frame-state!
                           :test/short-lived {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :home}}}}))]
        (is (false? result)
            "perform-replace-frame-state! reports HONEST failure (false) for the
             nil-return post-liveness teardown")
        (is (has-error-op? @recorded :rf.error/no-such-handler)
            ":rf.error/no-such-handler fired")
        (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                       @recorded)]
          (is (= :frame (:kind (:tags ev))) "tags carry :kind :frame")
          (is (= :test/short-lived (:frame (:tags ev))) "tags carry :frame"))
        (is (not-any? #(= :rf.epoch/db-replaced (:operation %)) @recorded)
            "no :rf.epoch/db-replaced success trace")
        (is (empty? @fanned)
            "no synthetic epoch fanned out to listeners for the dropped write")
        (is (= [] (rf/epoch-history :test/short-lived))
            "no synthetic epoch recorded into the dropped ring")))))

(deftest replace-frame-state-post-liveness-teardown-returns-false
  (testing "rf2-s93722 — perform-replace-frame-state! returns false, emits
            :rf.error/no-such-handler (kind :frame), and records / fans out NO
            synthetic epoch when replace-frame-state! returns nil AFTER the
            liveness check passed."
    (rf/reg-frame :test/short-lived {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/short-lived})

    (let [fanned   (atom [])]
      (rf/register-listener! :epoch ::fan-watcher (fn [r] (swap! fanned conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/short-lived})
      (reset! fanned [])

      (let [real-write frame/replace-frame-state!
            recorded   (record-trace!)
            new-fs     {:rf.db/app {:n 999} :rf.db/runtime {:rf.runtime/routing {:current {:route-id :home}}}}
            result     (with-redefs [frame/replace-frame-state!
                                     (fn [frame-id fs]
                                       (rf/destroy-frame! frame-id)
                                       (real-write frame-id fs))]
                         (#'epoch/perform-replace-frame-state! :test/short-lived new-fs))]
        (is (false? result)
            "perform-replace-frame-state! reports HONEST failure (false) for the
             nil-return post-liveness teardown")
        (is (has-error-op? @recorded :rf.error/no-such-handler)
            ":rf.error/no-such-handler fired")
        (let [ev (some #(when (= :rf.error/no-such-handler (:operation %)) %)
                       @recorded)]
          (is (= :frame (:kind (:tags ev))) "tags carry :kind :frame")
          (is (= :test/short-lived (:frame (:tags ev))) "tags carry :frame"))
        (is (not-any? #(= :rf.epoch/db-replaced (:operation %)) @recorded)
            "no :rf.epoch/db-replaced success trace")
        (is (empty? @fanned)
            "no synthetic epoch fanned out to listeners for the dropped write")
        (is (= [] (rf/epoch-history :test/short-lived))
            "no synthetic epoch recorded into the dropped ring")))))

;; rf2-s93722 — guard the OTHER side of the nil/empty-set distinction: a
;; live-frame NO-OP write (the value `=` the current slice) returns an EMPTY
;; changed-key-set (non-nil), so it MUST stay a success — NOT be misread as a
;; destroyed-frame drop.
(deftest replace-frame-state-app-only-noop-write-stays-successful
  (testing "rf2-s93722 — a no-op replace-frame-state! (an app-only map whose
            value equals the current app-db) returns an EMPTY (non-nil)
            changed-key-set from the frame write, so the perform helper
            treats it as success — true return, :rf.epoch/db-replaced
            emitted, synthetic epoch recorded — NOT a destroyed-frame drop."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [recorded (record-trace!)
          ;; Inject the IDENTICAL value — a genuine no-op write against a live
          ;; frame. commit-frame-transition! returns #{} (empty, non-nil).
          result   (#'epoch/perform-replace-frame-state! :test/main {:rf.db/app {:n 7}})]
      (is (true? result)
          "a live-frame no-op write stays successful (empty-set ≠ nil)")
      (is (= {:n 7} (rf/app-db-value :test/main))
          "app-db is unchanged by the no-op (the equal value)")
      (is (some #(= :rf.epoch/db-replaced (:operation %)) @recorded)
          ":rf.epoch/db-replaced success trace IS emitted for the no-op write")
      (is (not (has-error-op? @recorded :rf.error/no-such-handler))
          "no destroyed-frame error for a live no-op write")
      (is (some #(= :rf.epoch/db-replaced (:event-id %))
                (rf/epoch-history :test/main))
          "the synthetic epoch IS recorded for the no-op write"))))

;; ============================================================================
;;  rf2-7i872 — listener observation bookkeeping race (unregister mid-fan-out)
;; ============================================================================
;;
;; notify-listeners! iterates a listener SNAPSHOT, then per cb-id calls
;; record-observation! (writing the separate observed-frames-by-cb atom)
;; BEFORE invoking the callback. record-observation! is gated (rf2-7i872) on
;; the cb-id still being a live listener at record time: if
;; unregister-epoch-listener! removes a cb between the snapshot and the
;; record-observation! call, an ungated write would RE-INTRODUCE the stale
;; cb-id into observed-frames-by-cb, which would later receive a bogus
;; :rf.epoch.cb/silenced-on-frame-destroy trace on frame destroy.

(deftest record-observation-skips-unregistered-cb-no-stale-bookkeeping
  (testing "rf2-7i872 — record-observation! against a cb that has been
            unregistered does NOT re-introduce an observed-frames-by-cb
            entry. (The exact ordering notify-listeners! exposes: snapshot
            taken, cb dropped, then record-observation! fires for the stale
            id.)"
    (rf/reg-frame :test/main {})
    ;; Register then unregister a cb — it is GONE from the listener registry.
    (rf/register-listener! :epoch ::ghost (fn [_] nil))
    (rf/unregister-listener! :epoch ::ghost)
    (is (not (contains? (state/listeners-snapshot) ::ghost))
        "::ghost is no longer a registered listener")

    ;; Simulate the racing fan-out: record-observation! is called for the
    ;; now-stale ::ghost id (as notify-listeners! would for a snapshot taken
    ;; before the unregister).
    (state/record-observation! ::ghost :test/main)

    (is (not (contains? (state/observations-snapshot) ::ghost))
        "no stale observed-frames-by-cb entry for the unregistered cb —
         record-observation! refused to re-introduce bookkeeping for a dead cb")))

(deftest unregister-mid-fanout-no-bogus-silencing-trace
  (testing "rf2-7i872 — the precise unregister-mid-fan-out interleaving
            notify-listeners! exposes: a listener snapshot is taken (carrying
            ::victim), ::victim is unregistered, THEN record-observation! is
            invoked for the stale ::victim id from the snapshot. The stale id
            must NOT be re-introduced into observed-frames-by-cb, so when the
            frame is later destroyed ::victim receives NO bogus
            :rf.epoch.cb/silenced-on-frame-destroy trace.

            Modelled by replaying notify-listeners!'s loop body manually
            against a hand-built snapshot — the synchronous JVM path can't
            otherwise pin the snapshot-then-unregister-then-record ordering
            deterministically (map iteration order is unspecified)."
    (rf/reg-frame :test/main {})

    (let [recorded (record-trace!)]
      ;; ::victim is a freshly-registered listener that has NOT yet observed
      ;; any frame (put-listener! clears its observation ledger). It is live
      ;; in the registry when the fan-out snapshot is taken.
      (rf/register-listener! :epoch ::victim (fn [_] nil))
      (let [;; (1) notify-listeners! takes the snapshot (includes ::victim).
            snapshot (state/listeners-snapshot)]
        (is (contains? snapshot ::victim)
            "::victim is in the fan-out snapshot")

        ;; (2) ::victim is unregistered AFTER the snapshot — the exact race.
        (rf/unregister-listener! :epoch ::victim)

        ;; (3) notify-listeners! reaches the snapshot's ::victim entry and
        ;; calls record-observation! for it (the loop iterates the stale
        ;; snapshot). Replay that single step.
        (doseq [[id _f] snapshot]
          (state/record-observation! id :test/main)))

      (is (not (contains? (state/listeners-snapshot) ::victim))
          "::victim was unregistered during fan-out")
      (is (not (contains? (state/observations-snapshot) ::victim))
          "::victim carries NO stale observed-frames-by-cb entry after the
           snapshot-then-unregister-then-record interleaving")

      ;; Destroy the frame — the silencing pass reads observations-snapshot.
      ;; ::victim must NOT receive a silencing trace.
      (rf/destroy-frame! :test/main)
      (let [victim-silenced (filter #(and (= :rf.epoch.cb/silenced-on-frame-destroy
                                             (:operation %))
                                          (= ::victim (:cb-id (:tags %))))
                                    @recorded)]
        (is (empty? victim-silenced)
            "no bogus :rf.epoch.cb/silenced-on-frame-destroy trace for the
             unregistered ::victim cb")))))


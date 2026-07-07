(ns re-frame.epoch-privacy-test
  "Per rf2-vq5o0 — coverage for the epoch privacy contract introduced
  by rf2-j1m7x (spec) and rf2-mrsck (impl). Three contract surfaces:

    1. The record-level :rf.epoch/sensitive? rollup — true when any
       captured trace event carries the :sensitive? stamp OR any
       frame-declared sensitive path holds a non-nil leaf in
       :db-before / :db-after; false otherwise.

    2. re-frame.epoch/projected-record + projected-history — the
       single normative projection emission site for off-box egress.
       Routes :db-before / :db-after / :trigger-event / :trace-events
       through elide-wire-value with off-box defaults
       (:include-sensitive? false, :include-large? false).

    3. Listener fan-out delivers RAW records by default — silent
       projection would break Xray's diff visualiser and on-box
       restore drivers (Tool-Pair §Time-travel). Forwarders that
       egress off-box opt INTO projection at the wire boundary via
       projected-record.

  Plus retention-cap coverage and JVM debug-disabled false-path
  coverage per the cluster prompt.

  rf2-mrsck's per-leaf smokes live in epoch_test.clj alongside the
  impl; this file is the deeper coverage matrix."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Side-effect requires (mirrors epoch_test.clj):
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------
;;
;; rf2-yw1w1u — canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, and fires the
;; reset-hook table: epoch (history / listeners / config-to-default).
;; EP-0025: classification is derived from the registrar + the per-frame
;; elision registry (reset by frame teardown), so there is no separate
;; classification table to clear between tests. The
;; `:init-fn` re-applies the suite's non-default `:trace-events-keep 5`
;; (NOT the shipped 50 = :depth; Mike pair-debug 2026-05-27) through the
;; public `configure!` boundary — no test ns reaches into the private
;; `state/config` var.
;;
;; EP-0002 (rf2-9o48ih / rf2-nn0jqa): `init!` no longer synthesises
;; `:rf/default`. The canonical fixture, when handed an `:adapter`, ALSO
;; ensures the conventional `:rf/default` frame and binds it as the body's
;; ambient scope — the carried-invariant equivalent of wrapping every test
;; in `(with-frame :rf/default …)`. The bare framework-operation surfaces
;; this suite drives therefore resolve a carried frame stamp without a
;; hand-rolled `reg-frame` + `with-frame` here. Explicit `{:frame …}` opts
;; in the bodies still win.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- helpers ---------------------------------------------------------------

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(defn- install-sensitive-schema!
  "Declare a `[:auth :password]` sensitive path against `frame-id`.
  Returns nil.

  EP-0025: durable app-db classification rides the commit-plane
  classification effects — seeded through `elision/apply-classification-
  effects` (`:source :effect`), the same registry write a `reg-event`
  returning `:sensitive` performs. The frame container is reg-frame'd by
  each deftest before this runs. Classification is value-independent, so
  cascades that legitimately leave `:auth` absent (a non-auth event) or
  clear `:password` mid-cascade need no `:maybe` / `:optional` wrapper."
  [frame-id]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
  nil)

(defn- install-large-schema!
  "Declare a `[:blob :payload]` large path against `frame-id` (EP-0025 —
  commit-plane classification effect, `elision/apply-classification-effects`
  under `:source :effect`). The frame container is reg-frame'd by each
  deftest before this runs."
  [frame-id]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:large [[:blob :payload]]})))
  nil)

(defn- big-string [n]
  (apply str (repeat n "X")))

(defn- contains-leaf?
  "Walk an arbitrary EDN value looking for `secret` as a leaf string (exact
  equality or substring). Used by the rf2-nm611o trigger-event redaction
  tests as the 'no raw secret bytes anywhere in the projected slot' check."
  [x secret]
  (cond
    (string? x) (.contains ^String x ^String secret)
    (map? x)    (or (some #(contains-leaf? % secret) (keys x))
                    (some #(contains-leaf? % secret) (vals x)))
    (coll? x)   (boolean (some #(contains-leaf? % secret) x))
    :else       false))

;; ---- 1. sensitive rollup ---------------------------------------------------

(deftest rollup-false-on-non-sensitive-cascade
  (testing "no sensitive handler, no frame-declared sensitive path —
            rollup reads strict false"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (false? (:rf.epoch/sensitive? r)))
      (is (contains? r :rf.epoch/sensitive?)
          "the slot is always present on assembled records — consumers
           branch on (true? ...) / (false? ...) without an absence
           special case"))))

(deftest rollup-false-from-handler-meta-sensitive-removed
  (testing "Handler-meta `:sensitive?` annotation has been removed —
            it no longer stamps trace events, so the rollup reads
            false for a cascade whose only sensitive signal was the
            (now-ignored) handler annotation."
    (rf/reg-frame :test/main {})
    (rf/reg-event :secret-write
                     {:sensitive? true}   ;; stored, no longer consulted
                     (fn [{:keys [db]} _] {:db (assoc db :token "shh")}))
    (rf/dispatch-sync [:secret-write] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (false? (:rf.epoch/sensitive? r))
          "rollup reads false — handler-meta annotation no longer drives the stamp"))))

(deftest rollup-true-from-frame-declared-non-nil-leaf
  (testing "a frame-declared sensitive path that resolves to a non-nil
            leaf in :db-after triggers the rollup even when no handler
            in scope is sensitive"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (true? (:rf.epoch/sensitive? r))))))

(deftest rollup-false-when-schema-path-resolves-to-nil
  (testing "a frame with a frame-declared sensitive path BUT the
            recorded :db-before / :db-after carry no value at the path
            — rollup reads false (the declaration is structural; the
            cascade carried no actual sensitive material)"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :unrelated (fn [{:keys [db]} _] {:db (assoc db :n 42)}))
    (rf/dispatch-sync [:unrelated] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (false? (:rf.epoch/sensitive? r))
          "no sensitive material in this cascade — declaration alone
           does not make the record sensitive"))))

(deftest rollup-true-from-db-before-non-nil-leaf
  (testing "a sensitive value present in :db-before (the pre-cascade
            snapshot) triggers the rollup even when the handler clears
            the value during the cascade"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :seed
                     (fn [{:keys [db]} _] {:db {:auth {:password "old-secret"}}}))
    (rf/reg-event :clear-pw
                     (fn [{:keys [db]} _] {:db (update db :auth dissoc :password)}))

    (rf/dispatch-sync [:seed]     {:frame :test/main})
    (rf/dispatch-sync [:clear-pw] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= "old-secret" (get-in r [:db-before :auth :password]))
          "db-before carries the sensitive value")
      (is (nil? (get-in r [:db-after :auth :password]))
          "db-after no longer carries it")
      (is (true? (:rf.epoch/sensitive? r))
          "rollup fires on the db-before signal"))))

(deftest rollup-strict-boolean-on-halted-destroy
  (testing "halted-destroy records carry REAL :db-before / :db-after
            snapshots (rf2-9neiq — the pre-cascade + destroy-time state,
            per Spec-Schemas §:rf/epoch-record §Outcomes); the rollup
            must still produce a strict boolean over those real dbs.

            Per the rf2-ee38b correctness review: this drives a REAL
            mid-drain `destroy-frame!` and asserts UNCONDITIONALLY that
            exactly one :halted-destroy record reached the listener. The
            prior `(when-let [halted ...] ...)` guard silently no-op'd if
            the live wiring stopped firing the partial record, passing
            green with zero executed assertions.

            rf2-9neiq corrected the FALSE-GREEN nil-db assertions: the
            record now carries the real app-db state, not nil/nil. Here
            no `[:auth :password]` value was ever written (only the
            schema declaration lives in app-db), so the sensitive-leaf
            walk finds no non-nil sensitive leaf and the rollup is a
            strict `false` — proving the rollup walks the real (non-nil)
            db without NPE and without a spurious true."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    ;; Trigger a real cascade so capture-buffers carries a run-start;
    ;; on destroy mid-drain the halted-destroy record fires.
    (let [seen (atom [])]
      (rf/register-epoch-listener! ::halt-watcher
                             (fn [r] (swap! seen conj r)))
      (rf/reg-event :destroy-self
                       (fn [_ _]
                         (frame/destroy-frame! :test/main)
                         {}))
      ;; The destroy fires inside the drain — on-frame-destroyed!
      ;; emits a :halted-destroy partial record carrying the REAL
      ;; pre-cascade + destroy-time db snapshots (rf2-9neiq).
      (try (rf/dispatch-sync [:destroy-self] {:frame :test/main})
           (catch Throwable _ nil))
      (let [halted-records (filterv (fn [r] (= :halted-destroy (:outcome r)))
                                    @seen)]
        ;; UNCONDITIONAL: the live mid-drain destroy fires exactly one
        ;; :halted-destroy record at the listener.
        (is (= 1 (count halted-records))
            "the live mid-drain destroy fires exactly one :halted-destroy
             record to listeners")
        (let [halted (first halted-records)]
          ;; The rollup is computed over the REAL (non-nil) dbs and stays
          ;; a strict boolean. No `[:auth :password]` value was written,
          ;; so the only sensitive-declared path resolves nil → false.
          (is (false? (:rf.epoch/sensitive? halted))
              "rollup is strict false on the halted-destroy path — the
               declared-sensitive [:auth :password] path holds no value")
          ;; rf2-9neiq: the record carries the REAL pre-cascade /
          ;; destroy-time state, NOT nil. The schema-install populates the
          ;; elision declarations in runtime-db ([:rf.runtime/elision ...]); no
          ;; password write means the sensitive leaf is absent.
          (is (some? (:db-before halted))
              "halted-destroy carries a real (non-nil) :db-before (rf2-9neiq)")
          (is (some? (:db-after halted))
              "halted-destroy carries a real (non-nil) :db-after (rf2-9neiq)")
          (is (nil? (get-in halted [:db-before :auth :password]))
              "no sensitive [:auth :password] leaf was written, so the
               rollup correctly reads false")
          (is (nil? (get-in halted [:db-after :auth :password]))
              "destroy-time db likewise carries no sensitive leaf"))))))

;; ---- 2. projected-record ---------------------------------------------------
;;
;; This section pins the projection emission-site contract from the
;; privacy angle: per-leaf substitution (sensitive / large), bookkeeping
;; pass-through, nil-input safety, and history-walk shape. It deliberately
;; does NOT re-assert projection IDEMPOTENCY (re-projecting an already-
;; projected record is a no-op at the substitution points) — that
;; invariant has its authoritative pins in
;; `epoch_mcp_egress_conformance_test`
;; (`forwarder-projected-record-is-sensitive-idempotent` :255 +
;; `forwarder-projected-record-is-large-idempotent` :305), with the
;; redact×project composition cases in `epoch_redact_fn_projection_test`.
;; Keep idempotency assertions there; do not duplicate them here (rf2-zymix).

(deftest projected-record-redacts-sensitive-in-db-after
  (testing "frame-declared sensitive path in :db-after lands as
            :rf/redacted in the projected record"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (is (= "topsecret" (get-in raw [:db-after :auth :password]))
          "raw record carries the unredacted value (in-process)")
      (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
          "projected record substitutes :rf/redacted"))))

(deftest projected-record-redacts-sensitive-in-db-before
  (testing ":db-before is also walked through the projection — a value
            present pre-cascade lands as :rf/redacted in the projected
            record"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :seed
                     (fn [{:keys [db]} _] {:db {:auth {:password "original-secret"}}}))
    (rf/reg-event :inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (is (= "original-secret"
             (get-in raw [:db-before :auth :password]))
          "raw :db-before carries the value")
      (is (= :rf/redacted
             (get-in projected [:db-before :auth :password]))
          "projected :db-before substitutes :rf/redacted"))))

(deftest projected-record-elides-large-in-db-after
  (testing "frame-declared :large? path in :db-after lands as a
            :rf.size/large-elided marker in the projected record"
    (rf/reg-frame :test/main {})
    (install-large-schema! :test/main)
    (rf/reg-event :store
                     (fn [{:keys [db]} [_ payload]]
                       {:db (assoc-in db [:blob :payload] payload)}))
    (rf/dispatch-sync [:store (big-string 50000)] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)
          marked    (get-in projected [:blob :payload])]
      (is (= 50000 (count (get-in raw [:db-after :blob :payload])))
          "raw record carries the full string")
      ;; :large? matches at :db-after.[:blob :payload], so the projected
      ;; record's [:db-after :blob :payload] slot is a marker map.
      (is (or (elision/marker? (get-in projected [:db-after :blob :payload]))
              (elision/marker? marked))
          "projected record substitutes a :rf.size/large-elided marker"))))

(deftest projected-record-elides-large-sub-output
  (testing "rf2-at60h — a whole-output `:large?`-marked subscription's
            computed value rides the structured `:sub-runs` row as
            `:value` / `:prev-value`. The raw on-box record keeps the
            exact value (Xray diff / restore-epoch! need it), but the
            off-box `projected-record` / `projected-history` egress
            boundary MUST substitute a `:rf.size/large-elided` marker for
            those value slots under the `:include-large? false` default —
            otherwise a bulky derived value escapes the projection
            contract (the pre-fix leak). The non-value row metadata
            (`:sub-id`, `:query-v`, `:value-changed?`, `:cascade?`) is
            preserved, and the now-spent `:large?` row flag is stripped."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    ;; A whole-output `:large?` sub: its output is treated as large for
    ;; downstream egress. EP-0025: this is a REGISTRATION override (read via
    ;; `registration-classification`), NOT propagation. The trace projection
    ;; (`classification/project-sub-tags`) stamps the `:rf.sub/run` tag with
    ;; bare `:large?` from the registration meta and leaves the raw value in
    ;; place; the epoch off-box projector elides it.
    (rf/reg-sub :big {:large? true}
                (fn [db _] (big-string 50000)))
    ;; Read the sub inside a handler so a `:rf.sub/run` lands in the
    ;; cascade's structured `:sub-runs` (mirrors epoch_test's
    ;; sub-runs-projection).
    (rf/reg-event :read-big
                     (fn [_ _]
                       (let [_v (rf/subscribe-once [:big] {:frame :test/main})]
                         {})))
    (rf/dispatch-sync [:seed]     {:frame :test/main})
    (rf/dispatch-sync [:read-big] {:frame :test/main})

    (let [raw       (last-record :test/main)
          raw-row   (->> (:sub-runs raw)   (filter #(= :big (:sub-id %))) first)
          projected (epoch/projected-record raw)
          proj-row  (->> (:sub-runs projected) (filter #(= :big (:sub-id %))) first)]
      (is (some? raw-row)   "the :big sub produced a structured :sub-runs row")
      (is (some? proj-row)  "the projected record keeps the :big sub-run row")

      ;; Raw on-box row carries the exact 50KB value (and the :large? flag
      ;; threaded by capture/sub-run-row).
      (is (= 50000 (count (:value raw-row)))
          "raw on-box row carries the full computed value")
      (is (true? (:large? raw-row))
          "raw row threads the whole-output :large? marker")

      ;; Off-box projected row: value slot is a marker, NOT the raw value.
      (is (elision/marker? (:value proj-row))
          "projected :sub-runs :value is a :rf.size/large-elided marker, not raw")
      (is (not= (:value raw-row) (:value proj-row))
          "the raw 50KB value does NOT egress in the projected :sub-runs")
      ;; The prev-value slot (nil on first recompute) is left as-is; if a
      ;; bulky prev-value were present it would also be a marker — assert
      ;; it is never the raw bulky value.
      (when (contains? proj-row :prev-value)
        (is (or (nil? (:prev-value proj-row))
                (elision/marker? (:prev-value proj-row)))
            "projected :prev-value is never a raw bulky value"))

      ;; Non-value metadata preserved; the spent :large? flag is stripped.
      (is (= (:sub-id raw-row)  (:sub-id proj-row)))
      (is (= (:query-v raw-row) (:query-v proj-row)))
      (is (= (:value-changed? raw-row) (:value-changed? proj-row)))
      (is (not (contains? proj-row :large?))
          "the now-spent :large? row flag is stripped from the projection")

      ;; projected-history routes through the same projection.
      (let [hist-row (->> (epoch/projected-history :test/main)
                          (mapcat :sub-runs)
                          (filter #(= :big (:sub-id %)))
                          first)]
        (is (elision/marker? (:value hist-row))
            "projected-history also elides the large :sub-runs value")))))

(deftest projected-record-bookkeeping-passes-through
  (testing "bookkeeping slots are preserved by the projection — the
            projection only mutates payload-bearing slots"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (doseq [k [:epoch-id :frame :committed-at :event-id :outcome
                 :schema-digest :rf.epoch/sensitive?]]
        (is (= (get raw k) (get projected k))
            (str "bookkeeping slot " k " passes through unchanged"))))))

(deftest projected-record-renders-and-subruns-pass-through-when-value-free
  (testing ":renders carries no app-db material (render-keys, timing,
            cause), so it passes through the projection unchanged. `:sub-runs`
            rows carry value-bearing `:prev-value` / `:value` (rf2-at60h) so
            they are NOT value-free in general — but a row that is neither
            whole-output sensitive (already redacted at the marks emit site)
            nor whole-output large (no `:large?` flag → nothing to substitute)
            survives the projection byte-for-byte. This cascade declares only a
            SENSITIVE schema path and reads no large-marked sub, so its
            `:sub-runs` rows still pass through identically; the large-value
            egress case is pinned by `projected-record-elides-large-sub-output`.
            (`:effects` is NOT pass-through any more — its `:args` fail closed,
            pinned by the rf2-rlt3sv tests below.)"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (is (= (:sub-runs raw) (:sub-runs projected)))
      (is (= (:renders  raw) (:renders  projected))))))

;; ---- rf2-rlt3sv — :effects :args fail closed off-box ----------------------
;;
;; The structured :effects rows carry :args — the RAW fx-handler argument
;; captured verbatim from the :rf.fx/args trace tag. These are payload-bearing
;; user data (an HTTP body, a [:login pw] dispatch, a payment map) that is NOT
;; routed through the marks-projection chokepoint at emit time and NOT rooted
;; at the frame's app-db, so the schema-path walker cannot prove any value
;; safe. Off-box egress FAILS CLOSED: projected-record redacts each row's :args
;; to :rf/redacted for EVERY outcome, preserving :fx-id / :outcome /
;; :error-trace. NEGATIVE CONTROL: the RAW ring record keeps the exact args
;; (asserted distinct from the projected sentinel), and :include-fx-args? true
;; lifts the redaction.

(defn- effect-row [record fx-id]
  (some #(when (= fx-id (:fx-id %)) %) (:effects record)))

(deftest projected-record-elides-fx-args-success-row
  (testing "an :ok fx row's :args are payload-bearing and fail closed off-box;
            the raw ring keeps them (negative control); :fx-id / :outcome are
            preserved; :include-fx-args? true lifts the redaction"
    (rf/reg-frame :test/main {})
    (let [secret {:password "topsecret" :token "abc123"}]
      (rf/reg-fx :fxp/login (fn [_ _] nil))
      (rf/reg-event :do-login
                       (fn [_ [_ creds]] {:fx [[:fxp/login creds]]}))
      (rf/dispatch-sync [:do-login secret] {:frame :test/main})

      (let [raw       (last-record :test/main)
            raw-row   (effect-row raw :fxp/login)
            proj-row  (effect-row (epoch/projected-record raw) :fxp/login)
            wide-row  (effect-row (epoch/projected-record raw {:include-fx-args? true})
                                  :fxp/login)]
        ;; Negative control: the raw ring record carries the EXACT secret args.
        (is (= secret (:args raw-row))
            "raw ring keeps the exact fx args (on-box restore fidelity)")
        ;; Off-box default: args fail closed to the :rf/redacted sentinel.
        (is (= :rf/redacted (:args proj-row))
            "off-box projection redacts :args (fails closed)")
        (is (not= (:args raw-row) (:args proj-row))
            "negative control: projected args are NOT the raw secret")
        ;; Value-free metadata preserved.
        (is (= :fxp/login (:fx-id proj-row)))
        (is (= :ok (:outcome proj-row)))
        ;; Trusted-local opt-in lifts the redaction.
        (is (= secret (:args wide-row))
            ":include-fx-args? true keeps the raw args for trusted-local")))))

(deftest projected-record-elides-fx-args-skipped-on-platform-row
  (testing "a :skipped-on-platform fx row's :args fail closed off-box (the
            skip path's args are not pre-redacted)"
    (rf/reg-frame :test/main {})
    (let [secret {:k "session-key" :v "secret-value"}]
      ;; :client-only fx is skipped on the JVM (:server) host.
      (rf/reg-fx :fxp/local-storage {:platforms #{:client}} (fn [_ _] nil))
      (rf/reg-event :save
                       (fn [_ [_ payload]] {:fx [[:fxp/local-storage payload]]}))
      (rf/dispatch-sync [:save secret] {:frame :test/main})

      (let [raw      (last-record :test/main)
            raw-row  (effect-row raw :fxp/local-storage)
            proj-row (effect-row (epoch/projected-record raw) :fxp/local-storage)]
        (is (= :skipped-on-platform (:outcome raw-row))
            "fixture produced a skipped-on-platform row")
        (is (= secret (:args raw-row)) "raw ring keeps the exact args")
        (is (= :rf/redacted (:args proj-row))
            "off-box projection redacts the skipped row's :args")
        (is (= :fxp/local-storage (:fx-id proj-row)))
        (is (= :skipped-on-platform (:outcome proj-row)))))))

(deftest projected-record-elides-fx-args-no-such-fx-row
  (testing "a :rf.error/no-such-fx row's :args fail closed off-box; :error
            metadata (:outcome / :error-trace) is preserved"
    (rf/reg-frame :test/main {})
    (let [secret {:card "4111-1111-1111-1111"}]
      ;; No fx registered under :fxp/missing → :rf.error/no-such-fx.
      (rf/reg-event :charge
                       (fn [_ [_ payload]] {:fx [[:fxp/missing payload]]}))
      (rf/dispatch-sync [:charge secret] {:frame :test/main})

      (let [raw      (last-record :test/main)
            raw-row  (effect-row raw :fxp/missing)
            proj-row (effect-row (epoch/projected-record raw) :fxp/missing)]
        (is (some? raw-row) "fixture produced a no-such-fx :effects row")
        (is (= :error (:outcome raw-row)))
        (is (= secret (:args raw-row)) "raw ring keeps the exact args")
        (is (= :rf/redacted (:args proj-row))
            "off-box projection redacts the no-such-fx row's :args")
        (is (= :fxp/missing (:fx-id proj-row)))
        (is (= :error (:outcome proj-row)))
        (is (= (:error-trace raw-row) (:error-trace proj-row))
            ":error-trace metadata is preserved (value-free)")))))

(deftest projected-record-elides-fx-args-handler-exception-row
  (testing "an :rf.error/fx-handler-exception row's :args fail closed off-box;
            :error metadata is preserved"
    (rf/reg-frame :test/main {})
    (let [secret {:ssn "123-45-6789"}]
      (rf/reg-fx :fxp/boom (fn [_ _] (throw (ex-info "boom" {}))))
      (rf/reg-event :explode
                       (fn [_ [_ payload]] {:fx [[:fxp/boom payload]]}))
      (rf/dispatch-sync [:explode secret] {:frame :test/main})

      (let [raw      (last-record :test/main)
            raw-row  (effect-row raw :fxp/boom)
            proj-row (effect-row (epoch/projected-record raw) :fxp/boom)]
        (is (some? raw-row) "fixture produced an fx-handler-exception :effects row")
        (is (= :error (:outcome raw-row)))
        (is (= secret (:args raw-row)) "raw ring keeps the exact args")
        (is (= :rf/redacted (:args proj-row))
            "off-box projection redacts the handler-exception row's :args")
        (is (= :fxp/boom (:fx-id proj-row)))
        (is (= :error (:outcome proj-row)))
        (is (= (:error-trace raw-row) (:error-trace proj-row))
            ":error-trace metadata is preserved")))))

(deftest projected-record-fx-args-projection-is-idempotent
  (testing "re-projecting an already-projected :effects row leaves :args as the
            :rf/redacted sentinel (no drift under double-projection)"
    (rf/reg-frame :test/main {})
    (rf/reg-fx :fxp/login (fn [_ _] nil))
    (rf/reg-event :do-login
                     (fn [_ [_ creds]] {:fx [[:fxp/login creds]]}))
    (rf/dispatch-sync [:do-login {:password "topsecret"}] {:frame :test/main})

    (let [raw    (last-record :test/main)
          once   (epoch/projected-record raw)
          twice  (epoch/projected-record once)]
      (is (= :rf/redacted (:args (effect-row once  :fxp/login))))
      (is (= :rf/redacted (:args (effect-row twice :fxp/login)))
          "double-projection is idempotent at the :args slot"))))

(deftest projected-record-trigger-event-positional-arg-redacted
  (testing "rf2-nm611o: a sensitive value carried POSITIONALLY in the
            dispatched event vector (e.g. a password as a bare positional
            arg, [:login \"topsecret\"]) does NOT leak via the off-box
            projection. The event ARGS are registration-owned transient
            payloads (Spec 015 §151), not app-db-rooted, so the app-db
            classification walker cannot match them — the projection FAILS
            CLOSED: the head event-id keyword is retained as the summary,
            every positional arg is redacted to :rf/redacted."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    ;; The secret rides POSITIONALLY in the event vector — there is no
    ;; app-db sensitive declaration that could match the trigger-event
    ;; path (the frame-declared path is [:auth :password], rooted at
    ;; app-db, not at the event vector). The prior projection routed this
    ;; slot through the generic app-db-rooted walker, so the secret leaked
    ;; raw. Fail-closed redaction is the fix.
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (is (= [:login "topsecret"] (:trigger-event raw))
          "the raw ring keeps the exact dispatched event vector")
      (is (contains? projected :trigger-event)
          ":trigger-event slot preserved through the projection")
      (is (= [:login :rf/redacted] (:trigger-event projected))
          "off-box projection retains the head event-id keyword and
           redacts the positional arg")
      (is (not= "topsecret" (second (:trigger-event projected)))
          "the secret positional arg is absent from the projected slot")
      (is (= :login (:event-id projected))
          "the event-id summary slot is unaffected (head keyword preserved)"))))

(deftest projected-record-trigger-event-map-arg-redacted
  (testing "rf2-nm611o: a sensitive value nested in a MAP arg of the
            dispatched event vector ([:auth/login {:password p}]) also
            fails closed off-box. Map args are registration-owned
            transient payloads too — an unmarked map arg cannot be proven
            safe by the app-db walker, so the whole arg redacts to
            :rf/redacted (no per-key descent that could leak the value)."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :auth/login
                     (fn [{:keys [db]} [_ {:keys [password]}]]
                       {:db (assoc-in db [:auth :password] password)}))
    (rf/dispatch-sync [:auth/login {:password "topsecret" :email "a@b.c"}]
                      {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (is (= [:auth/login {:password "topsecret" :email "a@b.c"}]
             (:trigger-event raw))
          "the raw ring keeps the exact dispatched map arg")
      (is (= [:auth/login :rf/redacted] (:trigger-event projected))
          "off-box projection redacts the whole map arg, head id retained")
      (is (not (contains-leaf? (:trigger-event projected) "topsecret"))
          "the secret is absent anywhere in the projected trigger-event"))))

(deftest projected-record-trigger-event-marked-event-arg-redacted
  (testing "rf2-nm611o: even an event whose registration DECLARES a
            sensitive arg path ({:sensitive [[:password]]}) fails closed
            at the trigger-event slot off-box. The marks-projection
            chokepoint does not run over the verbatim :rf.event/v trace
            tag (the trigger-event source), and the projector roots its
            walk at app-db, not the event arg-map — so the conservative,
            consistent answer is to redact the whole arg regardless of
            registration marks. The declaration still governs OTHER
            surfaces (schema-validation error traces, fx args derived from
            the event); it is the trigger-event slot specifically that
            fails closed."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :auth/login
                     {:sensitive [[:password]]}
                     (fn [{:keys [db]} [_ {:keys [password]}]]
                       {:db (assoc-in db [:auth :password] password)}))
    (rf/dispatch-sync [:auth/login {:password "topsecret"}] {:frame :test/main})

    (let [projected (epoch/projected-record (last-record :test/main))]
      (is (= [:auth/login :rf/redacted] (:trigger-event projected))
          "a marked event arg still fails closed at the trigger-event slot")
      (is (not (contains-leaf? (:trigger-event projected) "topsecret"))
          "the marked secret is absent from the projected trigger-event"))))

(deftest projected-record-trigger-event-trusted-local-opt-in
  (testing "rf2-nm611o: the trusted-local :include-event-args? true opt-in
            keeps the RAW event args off-box (a developer's own Xray panel
            inspecting their own running app). It is ORTHOGONAL to the
            app-db :include-sensitive? / :include-large? opt-ins — those do
            NOT lift it; only :include-event-args? does."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw (last-record :test/main)]
      (is (= [:login "topsecret"]
             (:trigger-event (epoch/projected-record raw {:include-event-args? true})))
          ":include-event-args? true keeps the raw event args off-box")
      ;; Orthogonality: the app-db sensitive/large opt-ins do NOT lift the
      ;; event-args redaction (event args are a different keyspace).
      (is (= [:login :rf/redacted]
             (:trigger-event (epoch/projected-record raw {:include-sensitive? true})))
          ":include-sensitive? does NOT lift the trigger-event-args redaction")
      (is (= [:login :rf/redacted]
             (:trigger-event (epoch/projected-record raw {:include-large? true})))
          ":include-large? does NOT lift the trigger-event-args redaction"))))

(deftest projected-record-trigger-event-redaction-idempotent
  (testing "rf2-nm611o: re-projecting an already-projected record leaves
            the trigger-event args as the :rf/redacted sentinel (no drift,
            no re-leak under double-projection — a forwarder pipeline may
            project the same record twice)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw   (last-record :test/main)
          once  (epoch/projected-record raw)
          twice (epoch/projected-record once)]
      (is (= [:login :rf/redacted] (:trigger-event once)))
      (is (= [:login :rf/redacted] (:trigger-event twice))
          "double-projection is idempotent at the :trigger-event slot"))))

(deftest projected-record-sensitive-wins-over-large
  (testing "the wire-elision walker's composition rule (sensitive
            wins over large) holds inside the projection — a slot
            declared both :sensitive? AND :large? lands as :rf/redacted,
            never as a :rf.size/large-elided marker (the marker would
            leak :path / :bytes / :digest)"
    (rf/reg-frame :test/main {})
    ;; EP-0025: classify `[:secret-pdf]` BOTH sensitive AND large via the
    ;; commit-plane classification effect path. The egress WALKER applies the
    ;; sensitive-wins-over-large rule (sensitive is checked before large per
    ;; node), so the projected slot lands as `:rf/redacted`, never a large
    ;; marker, even though both decls are present in the registry.
    (frame/swap-runtime-db! :test/main
      (fn [rt] (elision/apply-classification-effects rt
                 {:sensitive [[:secret-pdf]]
                  :large     [[:secret-pdf]]})))
    (rf/reg-event :store-pdf
                     (fn [{:keys [db]} [_ payload]]
                       {:db (assoc db :secret-pdf payload)}))
    (rf/dispatch-sync [:store-pdf (big-string 50000)] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (is (= :rf/redacted (get-in projected [:db-after :secret-pdf]))
          "sensitive wins — projected slot is :rf/redacted, not a marker"))))

(deftest projected-record-nil-input-returns-nil
  (testing "nil input — projected-record returns nil (a missing-epoch
            lookup must not throw)"
    (is (nil? (epoch/projected-record nil)))
    (is (nil? (epoch/projected-record :not-a-map)))
    (is (nil? (epoch/projected-record [:not :a :map])))))

(deftest projected-record-handles-missing-payload-slots
  (testing "a record without one of the four payload slots passes
            through cleanly (the projection only walks slots present
            on the record — halted-destroy carries nil :db-before /
            :db-after which the projection treats as nil-no-walk)"
    (let [partial-record {:epoch-id      99
                          :frame         :test/main
                          :committed-at  0
                          :outcome       :halted-destroy
                          :db-before     nil
                          :db-after      nil
                          :trace-events  []
                          :sub-runs      []
                          :renders       []
                          :effects       []
                          :rf.epoch/sensitive? false}
          projected     (epoch/projected-record partial-record)]
      (is (some? projected))
      (is (nil? (:db-before projected))
          "nil :db-before stays nil — no fabricated value")
      (is (nil? (:db-after projected))
          "nil :db-after stays nil")
      (is (= :halted-destroy (:outcome projected))))))

;; ---- 2b. EP-0015 named egress profile (rf2-1afn7q) ------------------------
;;
;; `projected-record` / `projected-history` let an MCP / AI / tool epoch
;; consumer SELECT the `:rf.egress/off-box-tool` boundary via the named
;; `:rf.egress/profile` opt, while `:rf.egress/off-box-observability` stays
;; the hosted-monitoring DEFAULT. The tool profile keeps the same
;; redact-sensitive / elide-large defaults but turns ON structural digests,
;; so a large frame-owned app-db slot egresses as a `:rf.size/large-elided`
;; marker carrying the `:digest` structural indicator the tool needs to
;; reason about shape. Observability omits that detail. An unknown profile
;; is rejected against the shared closed enum.

(defn- large-marker-body
  "The marker body map at the projected `[:db-after :blob :payload]` large
  slot (or nil if the slot is not a marker)."
  [record]
  (let [slot (get-in record [:db-after :blob :payload])]
    (when (elision/marker? slot)
      (:rf.size/large-elided slot))))

(deftest projected-record-tool-profile-includes-structural-digest
  (testing "rf2-1afn7q: an MCP/AI/tool epoch consumer selects
            :rf.egress/off-box-tool — the elided large slot's marker
            carries the :digest structural indicator the tool profile
            enables, while the :rf.egress/off-box-observability default
            omits it. Both still elide the large value (no raw bytes
            egress)."
    (rf/reg-frame :test/main {})
    (install-large-schema! :test/main)
    (rf/reg-event :store
                     (fn [{:keys [db]} [_ payload]]
                       {:db (assoc-in db [:blob :payload] payload)}))
    (rf/dispatch-sync [:store (big-string 50000)] {:frame :test/main})

    (let [raw       (last-record :test/main)
          obs-body  (large-marker-body
                      (epoch/projected-record raw))
          obs-body2 (large-marker-body
                      (epoch/projected-record
                        raw {:rf.egress/profile :rf.egress/off-box-observability}))
          tool-body (large-marker-body
                      (epoch/projected-record
                        raw {:rf.egress/profile :rf.egress/off-box-tool}))]
      ;; Both off-box profiles elide the large slot to a marker (no raw bytes).
      (is (some? obs-body)  "observability default elides the large slot")
      (is (some? tool-body) "tool profile elides the large slot")
      (is (not= 50000 (get-in (epoch/projected-record raw) [:db-after :blob :payload]))
          "the raw 50KB string never egresses under either off-box profile")
      ;; The DEFAULT (no profile) == the observability profile.
      (is (= obs-body obs-body2)
          "the bare 1-arity default == :rf.egress/off-box-observability")
      ;; The structural indicator: observability omits :digest; tool includes it.
      (is (not (contains? obs-body :digest))
          ":rf.egress/off-box-observability omits the structural :digest")
      (is (contains? tool-body :digest)
          ":rf.egress/off-box-tool includes the structural :digest the EP
           says tools should receive")
      (is (string? (:digest tool-body))
          "the tool profile's structural digest is a content hash, not a value")
      ;; The shared metadata (path / bytes / type) is on both (it is the
      ;; observability baseline); the tool profile ADDS the digest.
      (is (= (dissoc tool-body :digest) obs-body)
          "tool profile == observability marker PLUS the structural digest"))))

(deftest projected-record-tool-profile-still-redacts-sensitive
  (testing "rf2-1afn7q: selecting the tool profile does NOT lift the
            sensitive redaction default — a frame-declared sensitive slot
            still lands as :rf/redacted under :rf.egress/off-box-tool (the
            tool profile only adds structural indicators for elided large
            values, it does not reveal sensitive content)."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw  (last-record :test/main)
          tool (epoch/projected-record
                 raw {:rf.egress/profile :rf.egress/off-box-tool})]
      (is (= :rf/redacted (get-in tool [:db-after :auth :password]))
          "tool profile still redacts the sensitive slot"))))

(deftest projected-record-unknown-profile-rejected
  (testing "rf2-1afn7q: an unknown :rf.egress/profile is rejected against
            the shared closed enum — a typo is a loud error, never a
            silent fall-through to a permissive walk."
    (rf/reg-frame :test/main {})
    (install-large-schema! :test/main)
    (rf/reg-event :store
                     (fn [{:keys [db]} [_ payload]]
                       {:db (assoc-in db [:blob :payload] payload)}))
    (rf/dispatch-sync [:store (big-string 50000)] {:frame :test/main})
    (let [raw (last-record :test/main)
          ex  (try (epoch/projected-record raw {:rf.egress/profile :rf.egress/not-a-real-profile})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "an unknown profile throws")
      (is (= :rf.error/unknown-egress-profile (:rf.error/id (ex-data ex)))
          "the error carries the closed-enum rejection id"))))

(deftest projected-history-threads-tool-profile
  (testing "rf2-1afn7q: projected-history threads the named
            :rf.egress/off-box-tool profile to every record — the
            structural :digest rides each elided large slot off the
            whole-ring egress path too."
    (rf/reg-frame :test/main {})
    (install-large-schema! :test/main)
    (rf/reg-event :store
                     (fn [{:keys [db]} [_ payload]]
                       {:db (assoc-in db [:blob :payload] payload)}))
    (rf/dispatch-sync [:store (big-string 50000)] {:frame :test/main})

    (let [tool-hist (epoch/projected-history
                      :test/main {:rf.egress/profile :rf.egress/off-box-tool})
          last-body (large-marker-body (last tool-hist))]
      (is (some? last-body) "the whole-ring tool egress elides the large slot")
      (is (contains? last-body :digest)
          "projected-history threads the tool profile's structural digest"))))

;; ---- 3. projected-history --------------------------------------------------

(deftest projected-history-walks-the-ring
  (testing "projected-history returns one projected record per ring
            entry, in oldest-first order"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {}}))
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))

    (rf/dispatch-sync [:seed]              {:frame :test/main})
    (rf/dispatch-sync [:login "secret-1"]  {:frame :test/main})
    (rf/dispatch-sync [:login "secret-2"]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)
          ph      (epoch/projected-history :test/main)]
      (is (= (count history) (count ph)))
      (is (= (mapv :epoch-id history) (mapv :epoch-id ph))
          "ordering matches the raw ring")
      (is (every? (fn [r]
                    (let [pw (get-in r [:db-after :auth :password])]
                      (or (nil? pw) (= :rf/redacted pw))))
                  ph)
          "every projected record's password slot is nil or :rf/redacted —
           never the raw secret"))))

(deftest projected-history-empty-when-no-records
  (testing "projected-history on a frame with no recorded epochs
            returns the empty vector (matches the epoch-history empty
            shape)"
    (is (= [] (epoch/projected-history :rf/no-such-frame)))))

;; ---- 4. listener delivery defaults to RAW ---------------------------------

(deftest listener-fan-out-delivers-raw-record
  (testing "register-epoch-listener! callbacks receive the RAW record (NOT
            the projected one) — Xray's diff visualiser and on-box
            restore drivers depend on the raw :db-after; a silent
            projection would break them. Forwarders that egress
            off-box opt INTO projection at the wire boundary."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (let [seen (atom [])]
      (rf/register-epoch-listener! ::raw-listener
                             (fn [r] (swap! seen conj r)))
      (rf/reg-event :login
                       (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
      (is (= 1 (count @seen)) "listener fired once")
      (is (= "topsecret"
             (get-in (first @seen) [:db-after :auth :password]))
          "listener received the RAW value — projection is opt-in at
           the egress boundary, not the listener boundary"))))

(deftest forwarder-shape-projects-at-egress
  (testing "the canonical off-box-forwarder pattern: register raw,
            project at egress. This pins the recommended shape for
            tools (Xray-MCP watch-epochs, story / pair recorders)."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (let [shipped (atom [])
          ship!   (fn [record]
                    ;; Tool-side forwarder body — project here.
                    (swap! shipped conj (epoch/projected-record record)))]
      (rf/register-epoch-listener! ::forwarder ship!)
      (rf/reg-event :login
                       (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
      (is (= 1 (count @shipped)))
      (is (= :rf/redacted
             (get-in (first @shipped) [:db-after :auth :password]))
          "the off-box-bound payload is projected"))))

;; ---- 5. trace-events retention cap ----------------------------------------

(deftest retention-cap-fixture-override-five
  (testing "rf2-wmki8 — the TEST FIXTURE forces :trace-events-keep 5 (a
            keep<depth OVERRIDE, NOT the shipped default of 50) so the
            elision path is reachable cheaply — drive >5 cascades, the
            oldest records lose :trace-events but keep the structured
            projections (per rf2-mrsck)"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (is (= 5 (:trace-events-keep (epoch/current-config)))
        "fixture OVERRIDE — the shipped runtime default is 50 (= :depth)")

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (dotimes [_ 6] (rf/dispatch-sync [:inc] {:frame :test/main}))

    (let [history (rf/epoch-history :test/main)
          n       (count history)]
      (is (= 7 n))
      (is (every? #(contains? % :sub-runs) history)
          "structured :sub-runs projection survives on every record")
      (is (every? #(contains? % :renders) history))
      (is (every? #(contains? % :effects) history))
      (is (every? #(contains? % :trace-events) (subvec history (- n 5) n))
          "the most-recent 5 records keep :trace-events")
      (is (every? #(not (contains? % :trace-events)) (subvec history 0 (- n 5)))
          "older records (beyond the keep-5 window) drop :trace-events"))))

(deftest retention-cap-zero-drops-every-trace-events
  (testing ":trace-events-keep 0 drops :trace-events from every
            record — the structured projections survive"
    (rf/configure! {:epoch-history {:trace-events-keep 0}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)]
      (is (= 3 (count history)))
      (is (every? #(not (contains? % :trace-events)) history)
          "no record carries :trace-events under :keep 0")
      (is (every? #(contains? % :sub-runs) history)
          "structured projections survive"))))

(deftest retention-cap-explicit-large-keeps-all
  (testing "an explicit :trace-events-keep value >= the depth cap
            keeps every record's :trace-events — the opt-back-in path"
    (rf/configure! {:epoch-history {:trace-events-keep 100}})
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (rf/dispatch-sync [:seed] {:frame :test/main})
    (dotimes [_ 6] (rf/dispatch-sync [:inc] {:frame :test/main}))

    (let [history (rf/epoch-history :test/main)]
      (is (= 7 (count history)))
      (is (every? #(contains? % :trace-events) history)
          "every record retains :trace-events"))))

;; ---- 6. JVM debug-disabled false-path coverage ----------------------------

(deftest projected-record-handles-empty-history-under-disabled-gate
  (testing "Per rf2-0la4f and the Security.md §Production gates: when
            the JVM debug gate reads false, no records land in the
            ring (per epoch_jvm_prod_gate_test). projected-history of
            an empty ring is the empty vector — projected-record never
            gets called against a record."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event :prod.priv/inc
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod.priv/inc])
      (is (= [] (epoch/projected-history :rf/default))
          "no records to project under disabled gate"))))

(deftest projected-record-pure-fn-survives-disabled-gate
  (testing "projected-record is a pure data transform — it does NOT
            consult interop/debug-enabled? itself; consumers that
            already hold a record (e.g. recorded earlier in dev,
            replayed in a JVM test fixture) can still project it.
            The gate elides record ASSEMBLY, not record PROJECTION."
    (let [synthetic-record
          {:epoch-id      1
           :frame         :test/main
           :committed-at  0
           :event-id      :synthetic
           :trigger-event [:synthetic]
           :db-before     {:n 0}
           :db-after      {:n 1}
           :outcome       :ok
           :schema-digest nil
           :rf.epoch/sensitive? false
           :trace-events  []
           :sub-runs      []
           :renders       []
           :effects       []}]
      (with-redefs [interop/debug-enabled? false]
        (let [projected (epoch/projected-record synthetic-record)]
          (is (some? projected))
          (is (= 1 (:epoch-id projected))
              "the projection runs even under the disabled gate — it
               is a pure data transform"))))))

(deftest sensitive-rollup-elides-with-record-assembly
  (testing "no records means no rollup to compute. The gate-disabled
            path drops the entire surface — the rollup is dev-only
            because the records are dev-only."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event :prod.priv/silent
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod.priv/silent])
      (is (empty? (rf/epoch-history :rf/default))
          "ring stays empty — rollup never computed"))))

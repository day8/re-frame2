(ns re-frame.epoch-privacy-test
  "Coverage for four epoch privacy surfaces:

    1. The record-level :rf.epoch/sensitive? rollup — true when any
       captured trace event carries the :sensitive? stamp OR any
       frame-declared sensitive path holds a non-nil leaf in
       :db-before / :db-after; false otherwise.

    2. re-frame.core/project-egress — the
       single normative projection emission site for off-box egress.
       Routes :db-before / :db-after / :trigger-event / :trace-events
       through elide-wire-value with off-box defaults
       (:rf.egress/include-sensitive? false, :rf.egress/include-large? false).

    3. Listener fan-out delivers RAW records by default — silent
       projection would break Xray's diff visualiser and on-box
       restore drivers (Tool-Pair §Time-travel). Forwarders that
       egress off-box opt INTO projection at the wire boundary via
       project-egress.

    4. The record-level :rf.epoch/redacted-modified-paths-count
       integer — how many frame-declared sensitive app-db paths
       changed value across the cascade. Computed in build-record from
       the RAW dbs, so it survives the projection that replaces both
       sides with the same :rf/redacted sentinel.

  Also covers the `:trace-events` retention cap."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.epoch]
            [re-frame.epoch.assembly :as rf.epoch.assembly]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Side-effect requires (mirrors epoch_test.clj):
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------
;;
;; The canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, and fires the
;; reset-hook table: epoch (history / listeners / config-to-default).
;; EP-0025: classification is derived from the registrar + the per-frame
;; elision registry (reset by frame teardown), so there is no separate
;; classification table to clear between tests. The
;; `:init-fn` re-applies the suite's non-default `:trace-events-keep 5`
;; (NOT the shipped 50 = :depth) through the
;; public `configure!` boundary — no test ns reaches into the private
;; `state/config` var.
;;
;; EP-0002: `init!` does not synthesise
;; `:rf/default`. The canonical fixture, when handed an `:adapter`, ALSO
;; ensures the conventional `:rf/default` frame and binds it as the body's
;; ambient scope — the carried-invariant equivalent of wrapping every test
;; in `(with-frame :rf/default …)`. The bare framework-operation surfaces
;; this suite drives therefore resolve a carried frame stamp without a
;; hand-rolled `make-frame` + `with-frame` here. Explicit `{:frame …}` opts
;; in the bodies win.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
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
  returning `:sensitive` performs. The frame container is make-frame'd by
  each deftest before this runs. Classification is value-independent, so
  cascades that legitimately leave `:auth` absent (a non-auth event) or
  clear `:password` mid-cascade need no `:maybe` / `:optional` wrapper."
  [frame-id]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
  nil)

(defn- install-two-sensitive-paths-schema!
  "Declare TWO sensitive paths against `frame-id` — `[:auth :password]`
  and `[:auth :token]`. Same EP-0025 commit-plane write as
  `install-sensitive-schema!`; the second path is what lets the
  redacted-modified-paths counter be exercised with a discriminating
  partial-modification control (one path changes, one does not).
  Returns nil."
  [frame-id]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt
               {:sensitive [[:auth :password] [:auth :token]]})))
  nil)

(defn- contains-leaf?
  "Walk an arbitrary EDN value looking for `secret` as a leaf string (exact
  equality or substring). Used by the trigger-event redaction
  tests as the 'no raw secret bytes anywhere in the projected slot' check."
  [x secret]
  (cond
    (string? x) (.contains ^String x ^String secret)
    (map? x)    (or (some #(contains-leaf? % secret) (keys x))
                    (some #(contains-leaf? % secret) (vals x)))
    (coll? x)   (boolean (some #(contains-leaf? % secret) x))
    :else       false))

;; ---- 1. sensitive rollup ---------------------------------------------------

(deftest rollup-false-from-handler-meta-sensitive-removed
  (testing "A handler-meta `:sensitive?` annotation does not stamp
            trace events, so the rollup reads
            false for a cascade whose only sensitive signal is the
            (ignored) handler annotation."
    (rf/make-frame {:id :test/main})
    (rf/reg-event :secret-write
                     {:sensitive? true}   ;; stored, never consulted
                     (fn [{:keys [db]} _] {:db (assoc db :token "shh")}))
    (rf/dispatch-sync [:secret-write] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (false? (:rf.epoch/sensitive? r))
          "rollup reads false — a handler-meta annotation does not drive the stamp"))))

(deftest rollup-false-when-schema-path-resolves-to-nil
  (testing "a frame with a frame-declared sensitive path BUT the
            recorded :db-before / :db-after carry no value at the path
            — rollup reads false (the declaration is structural; the
            cascade carried no actual sensitive material)"
    (rf/make-frame {:id :test/main})
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
    (rf/make-frame {:id :test/main})
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
            snapshots (the pre-cascade + destroy-time state,
            per Spec-Schemas §:rf/epoch-record §Outcomes); the rollup
            must produce a strict boolean over those real dbs.

            This drives a REAL
            mid-drain `destroy-frame!` and asserts UNCONDITIONALLY that
            exactly one :halted-destroy record reached the listener. A
            `(when-let [halted ...] ...)` guard would silently no-op if
            the live wiring stopped firing the partial record, passing
            green with zero executed assertions.

            The record carries the real app-db state, not nil/nil. Here
            no `[:auth :password]` value was ever written (only the
            schema declaration lives in app-db), so the sensitive-leaf
            walk finds no non-nil sensitive leaf and the rollup is a
            strict `false` — proving the rollup walks the real (non-nil)
            db without NPE and without a spurious true."
    (rf/make-frame {:id :test/main})
    (install-sensitive-schema! :test/main)
    ;; Trigger a real cascade so capture-buffers carries a run-start;
    ;; on destroy mid-drain the halted-destroy record fires.
    (let [seen (atom [])]
      (rf/register-listener! :epoch ::halt-watcher
                             (fn [r] (swap! seen conj r)))
      (rf/reg-event :destroy-self
                       (fn [_ _]
                         (rf.frame/destroy-frame! :test/main)
                         {}))
      ;; The destroy fires inside the drain — on-frame-destroyed!
      ;; emits a :halted-destroy partial record carrying the REAL
      ;; pre-cascade + destroy-time db snapshots.
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
          ;; The record carries the REAL pre-cascade /
          ;; destroy-time state, NOT nil. The schema-install populates the
          ;; elision declarations in runtime-db ([:rf.runtime/elision ...]); no
          ;; password write means the sensitive leaf is absent.
          (is (some? (:db-before halted))
              "halted-destroy carries a real (non-nil) :db-before")
          (is (some? (:db-after halted))
              "halted-destroy carries a real (non-nil) :db-after")
          (is (nil? (get-in halted [:db-before :auth :password]))
              "no sensitive [:auth :password] leaf was written, so the
               rollup correctly reads false")
          (is (nil? (get-in halted [:db-after :auth :password]))
              "destroy-time db likewise carries no sensitive leaf"))))))

;; ---- 2. project-egress ---------------------------------------------------
;;
;; This section pins the projection emission-site contract from the
;; privacy angle: per-leaf substitution (sensitive / large), bookkeeping
;; pass-through, nil-input safety, and history-walk shape. It deliberately
;; does NOT re-assert projection IDEMPOTENCY (re-projecting an already-
;; projected record is a no-op at the substitution points) — that
;; invariant has its authoritative pin in
;; `epoch_mcp_egress_conformance_test`
;; (`forwarder-project-egress-is-large-idempotent`, whose whole-ring
;; equality across passes covers both substitutions).
;; Keep idempotency assertions there; do not duplicate them here.
;; (There is no `:redact-fn` hook, so there is no redact×project
;; composition to pin; the redacted-modified-paths counter is section 4 at
;; the bottom of this file.)

(deftest project-egress-renders-and-subruns-pass-through-when-value-free
  (testing ":renders carries no app-db material (render-keys, timing,
            cause), so it passes through the projection unchanged. `:sub-runs`
            rows carry value-bearing `:prev-value` / `:value` so
            they are NOT value-free in general — but a row that is neither
            whole-output sensitive (already redacted at the marks emit site)
            nor whole-output large (no `:large?` flag → nothing to substitute)
            survives the projection byte-for-byte. This cascade declares only a
            SENSITIVE schema path and reads one UNMARKED sub, so its
            `:sub-runs` row carries a real `:value` and still passes through
            identically; the large-value egress case is pinned by
            `re-frame.epoch-egress-redaction-cljs-test`'s
            `large-sub-output-elides-in-both-egress-slots`. The plain-atom
            substrate renders nothing, so the `:renders` row is planted on the
            raw record.
            (`:effects` is NOT pass-through — its `:args` fail closed,
            pinned by the tests below.)"
    (rf/make-frame {:id :test/main})
    (install-sensitive-schema! :test/main)
    (rf/reg-sub :login/greeting (fn [_ _] "hello"))
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]]
                       (rf/subscribe-once [:login/greeting] {:frame :test/main})
                       {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [render-row {:render-key     [:login/view 1]
                      :mount?         true
                      :triggered-by   :login/greeting
                      :elapsed-ms     0.5
                      :cause-event-id :login}
          raw        (assoc (last-record :test/main) :renders [render-row])
          projected  (rf/project-egress raw)]
      (is (= "hello" (:value (first (filter #(= :login/greeting (:sub-id %))
                                            (:sub-runs raw)))))
          "fixture: the cascade recorded a `:sub-runs` row carrying its value")
      (is (= (:sub-runs raw) (:sub-runs projected)))
      (is (= [render-row] (:renders projected))))))

;; ---- :effects :args fail closed off-box -----------------------------------
;;
;; The structured :effects rows carry :args — the RAW fx-handler argument
;; captured verbatim from the :rf.fx/args trace tag. These are payload-bearing
;; user data (an HTTP body, a [:login pw] dispatch, a payment map) that is NOT
;; routed through the marks-projection chokepoint at emit time and NOT rooted
;; at the frame's app-db, so the schema-path walker cannot prove any value
;; safe. Off-box egress FAILS CLOSED: project-egress redacts each row's :args
;; to :rf/redacted for EVERY outcome, preserving :fx-id / :outcome /
;; :error-trace. NEGATIVE CONTROL: the RAW ring record keeps the exact args
;; (asserted distinct from the projected sentinel), and :rf.egress/include-fx-args? true
;; lifts the redaction.

(defn- effect-row [record fx-id]
  (some #(when (= fx-id (:fx-id %)) %) (:effects record)))

(deftest project-egress-elides-fx-args-success-row
  (testing "an :ok fx row's :args are payload-bearing and fail closed off-box;
            the raw ring keeps them (negative control); :fx-id / :outcome are
            preserved; :rf.egress/include-fx-args? true lifts the redaction"
    (rf/make-frame {:id :test/main})
    (let [secret {:password "topsecret" :token "abc123"}]
      (rf/reg-fx :fxp/login (fn [_ _] nil))
      (rf/reg-event :do-login
                       (fn [_ [_ creds]] {:fx [[:fxp/login creds]]}))
      (rf/dispatch-sync [:do-login secret] {:frame :test/main})

      (let [raw       (last-record :test/main)
            raw-row   (effect-row raw :fxp/login)
            proj-row  (effect-row (rf/project-egress raw) :fxp/login)
            wide-row  (effect-row (rf/project-egress raw {:rf.egress/include-fx-args? true})
                                  :fxp/login)]
        ;; Negative control: the raw ring record carries the EXACT secret args.
        (is (= secret (:args raw-row))
            "raw ring keeps the exact fx args (on-box restore fidelity)")
        ;; Off-box default: args fail closed to the :rf/redacted sentinel.
        (is (= :rf/redacted (:args proj-row))
            "off-box projection redacts :args (fails closed)")
        ;; Value-free :outcome preserved.
        (is (= :ok (:outcome proj-row)))
        ;; Trusted-local opt-in lifts the redaction.
        (is (= secret (:args wide-row))
            ":rf.egress/include-fx-args? true keeps the raw args for trusted-local")))))

(deftest project-egress-elides-fx-args-skipped-on-platform-row
  (testing "a :skipped-on-platform fx row's :args fail closed off-box (the
            skip path's args are not pre-redacted)"
    (rf/make-frame {:id :test/main})
    (let [secret {:k "session-key" :v "secret-value"}]
      ;; :client-only fx is skipped on the JVM (:server) host.
      (rf/reg-fx :fxp/local-storage {:platforms #{:client}} (fn [_ _] nil))
      (rf/reg-event :save
                       (fn [_ [_ payload]] {:fx [[:fxp/local-storage payload]]}))
      (rf/dispatch-sync [:save secret] {:frame :test/main})

      (let [raw      (last-record :test/main)
            raw-row  (effect-row raw :fxp/local-storage)
            proj-row (effect-row (rf/project-egress raw) :fxp/local-storage)]
        (is (= :skipped-on-platform (:outcome raw-row))
            "fixture produced a skipped-on-platform row")
        (is (= secret (:args raw-row)) "raw ring keeps the exact args")
        (is (= :rf/redacted (:args proj-row))
            "off-box projection redacts the skipped row's :args")
        (is (= :skipped-on-platform (:outcome proj-row)))))))

(deftest project-egress-elides-fx-args-error-rows
  (testing "an :error fx row's :args fail closed off-box; its value-free
            :error metadata (:outcome / :error-trace) is preserved. One row
            per error branch, each captured on its own path"
    (rf/make-frame {:id :test/main})
    ;; No fx registered under :fxp/missing → :rf.error/no-such-fx.
    (rf/reg-fx :fxp/boom (fn [_ _] (throw (ex-info "boom" {}))))
    (rf/reg-event :charge
                     (fn [_ [_ payload]] {:fx [[:fxp/missing payload]]}))
    (rf/reg-event :explode
                     (fn [_ [_ payload]] {:fx [[:fxp/boom payload]]}))
    (doseq [[branch event-id fx-id secret]
            [[:rf.error/no-such-fx           :charge  :fxp/missing {:card "4111-1111-1111-1111"}]
             [:rf.error/fx-handler-exception :explode :fxp/boom    {:ssn "123-45-6789"}]]]
      (testing branch
        (rf/dispatch-sync [event-id secret] {:frame :test/main})
        (let [raw      (last-record :test/main)
              raw-row  (effect-row raw fx-id)
              proj-row (effect-row (rf/project-egress raw) fx-id)]
          (is (some? raw-row) "fixture produced the error :effects row")
          (is (= :error (:outcome raw-row)))
          (is (= secret (:args raw-row)) "raw ring keeps the exact args")
          (is (= :rf/redacted (:args proj-row))
              "off-box projection redacts the error row's :args")
          (is (= :error (:outcome proj-row)))
          (is (= (:error-trace raw-row) (:error-trace proj-row))
              ":error-trace metadata is preserved (value-free)"))))))

(deftest project-egress-trigger-event-marked-event-arg-redacted
  (testing "even an event whose registration DECLARES a
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
    (rf/make-frame {:id :test/main})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :auth/login
                     {:sensitive [[:password]]}
                     (fn [{:keys [db]} [_ {:keys [password]}]]
                       {:db (assoc-in db [:auth :password] password)}))
    (rf/dispatch-sync [:auth/login {:password "topsecret"}] {:frame :test/main})

    (let [projected (rf/project-egress (last-record :test/main))]
      (is (= [:auth/login :rf/redacted] (:trigger-event projected))
          "a marked event arg still fails closed at the trigger-event slot")
      (is (not (contains-leaf? (:trigger-event projected) "topsecret"))
          "the marked secret is absent from the projected trigger-event"))))

(def ^:private halt-secret "halt-secret-do-not-leak")

(deftest halted-depth-record-carries-no-raw-event-args
  (testing "a depth halt's `:halt-reason` names the last
            SETTLED event by id only (`:last-event-id`), and the halting event
            (which never ran) is registration-classified before it becomes the
            record's `:trigger-event`. A descriptor carrying the last-settled
            event's args RAW under `:last-event` would reach `project-egress`
            (declared bookkeeping, passed through), the
            `replay-epoch!` refusal envelope and the
            `:rf.epoch/restore-non-ok-record` trace; the dev
            `:rf.error/drain-depth-exceeded` trace and the halt record's
            `:trigger-event` hold the args classified ON-BOX, as every `:ok`
            record does.

            DISTINCT events: A (`:halt/settled`) settles and dispatches B
            (`:halt/pending`), which the depth limit refuses. A self-dispatching
            loop cannot tell the halting event from the last-settled one."
    (rf/make-frame {:id :test/halt :drain-depth 1})
    (let [pending-ran (atom 0)
          traces      (atom [])
          leaks?      #(contains-leaf? % halt-secret)]
      (rf/reg-event :halt/settled {:sensitive [[:token]]}
        (fn [_ [_ {:keys [token]}]]
          {:fx [[:dispatch [:halt/pending {:token token :visible "pending"}]]]}))
      (rf/reg-event :halt/pending {:sensitive [[:token]]}
        (fn [_ _] (swap! pending-ran inc) {}))
      (rf/register-listener! :trace ::halt-traces (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:halt/settled {:token halt-secret :visible "settled"}]
                        {:frame :test/halt})
      (let [ring       (rf/epoch-history :test/halt)
            ok-rec     (first ring)
            halt       (last ring)
            depth-ev   (some #(when (= :rf.error/drain-depth-exceeded (:operation %)) %)
                             @traces)]
        ;; PRECONDITIONS — the halt really happened, on the event we meant.
        (is (= [:ok :halted-depth] (mapv :outcome ring))
            "PRECONDITION: A settled `:ok`, then the depth limit halted B")
        (is (zero? @pending-ran) "PRECONDITION: the halting event never ran")
        (is (some? depth-ev) "PRECONDITION: the dev depth trace fired")

        ;; The descriptor: ids only, and both identities kept.
        (is (= :halt/pending (:event-id halt))
            "the halt record names the HALTING event (B)")
        (is (= :halt/settled (get-in halt [:halt-reason :last-event-id]))
            "`:halt-reason :last-event-id` names the last SETTLED event (A)")
        (is (not (contains? (:halt-reason halt) :last-event))
            "no event VECTOR in the descriptor — the conformance matcher is a
             submap match, so only this assertion catches a producer that added
             `:last-event-id` but left `:last-event` beside it")

        ;; On-box: the halting event is classified exactly as the `:ok`
        ;; records' triggers are (they read the emit-time-classified run-start).
        (is (= [:halt/pending {:token :rf/redacted :visible "pending"}]
               (:trigger-event halt))
            "the raw halt record's `:trigger-event` is registration-classified")
        (is (not (leaks? halt))
            "the RAW on-box halt record carries the secret nowhere")

        ;; Off-box, every profile.
        (is (not (leaks? (rf/project-egress halt)))
            "default `project-egress` of the halt record carries no secret")
        (is (not (leaks? (rf/project-egress
                           halt {:rf.egress/profile :rf.egress/off-box-observability})))
            ":rf.egress/off-box-observability carries no secret")
        (is (not (leaks? (rf/project-egress
                           halt {:rf.egress/profile :rf.egress/off-box-tool})))
            ":rf.egress/off-box-tool carries no secret")

        ;; The dev trace keeps its vector, classified like any `:event`.
        (is (= [:halt/settled {:token :rf/redacted :visible "settled"}]
               (get-in depth-ev [:tags :last-event]))
            "the dev trace's `:last-event` is A's vector, registration-classified")
        (is (not (leaks? depth-ev))
            "the dev `:rf.error/drain-depth-exceeded` trace carries no secret")

        ;; The replay refusal envelope.
        (let [res (rf/replay-epoch! :test/halt (:epoch-id halt))]
          (is (= :rf.epoch/replay-non-replayable-record (:reason res))
              "PRECONDITION: replay refuses the halt record")
          (is (some? (:halt-reason res))
              "…carrying its halt reason")
          (is (not (leaks? res))
              "the replay refusal envelope carries no secret"))

        ;; The restore refusal trace.
        (reset! traces [])
        (is (false? (rf/restore-epoch! :test/halt (:epoch-id halt)))
            "PRECONDITION: restore refuses the halt record")
        (let [ev (some #(when (= :rf.epoch/restore-non-ok-record (:operation %)) %)
                       @traces)]
          (is (some? ev) "PRECONDITION: :rf.epoch/restore-non-ok-record fired")
          (is (not (leaks? ev))
              "the restore refusal trace carries no secret"))

        ;; CONTROL — the same drain's `:ok` record, raw and projected: capture
        ;; classified A's args, so the walker can see a redaction happen.
        (is (= [:halt/settled {:token :rf/redacted :visible "settled"}]
               (:trigger-event ok-rec))
            "CONTROL: the :ok record's trigger is classified at capture")
        (is (not (leaks? ok-rec))
            "CONTROL: the raw :ok record carries no secret")
        (is (not (leaks? (rf/project-egress ok-rec)))
            "CONTROL: nor does its projection")))))

(deftest project-egress-handles-missing-payload-slots
  (testing "a record carrying none of the payload slots passes through
            cleanly: the projection walks only the slots present on the
            record, so an absent slot stays absent rather than being
            fabricated"
    (let [payload-slots  [:db-before :db-after :frame-state-before
                          :frame-state-after :trigger-event :trace-events
                          :sub-runs :effects]
          partial-record {:kind                :rf/epoch-record
                          :epoch-id            99
                          :frame               :test/main
                          :committed-at        0
                          :outcome             :ok
                          :renders             []
                          :rf.epoch/sensitive? false}
          projected      (rf/project-egress partial-record)]
      (is (= partial-record projected)
          "the record projects to itself — nothing redacted, nothing added")
      (doseq [slot payload-slots]
        (is (not (contains? projected slot))
            (str slot " is absent on the record and stays absent")))))

  (testing "a nil payload slot stays nil — the projection does not
            fabricate a value for it"
    (let [projected (rf/project-egress {:kind      :rf/epoch-record
                                        :epoch-id  99
                                        :frame     :test/main
                                        :outcome   :ok
                                        :db-before nil
                                        :db-after  nil})]
      (is (nil? (:db-before projected)))
      (is (nil? (:db-after projected))))))

;; ---- 4. listener delivery defaults to RAW ---------------------------------

(deftest listener-fan-out-delivers-raw-record
  (testing "register-epoch-listener! callbacks receive the RAW record (NOT
            the projected one) — Xray's diff visualiser and on-box
            restore drivers depend on the raw :db-after; a silent
            projection would break them. Forwarders that egress
            off-box opt INTO projection at the wire boundary."
    (rf/make-frame {:id :test/main})
    (install-sensitive-schema! :test/main)
    (let [seen (atom [])]
      (rf/register-listener! :epoch ::raw-listener
                             (fn [r] (swap! seen conj r)))
      (rf/reg-event :login
                       (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
      (is (= 1 (count @seen)) "listener fired once")
      (is (= "topsecret"
             (get-in (first @seen) [:db-after :auth :password]))
          "listener received the RAW value — projection is opt-in at
           the egress boundary, not the listener boundary"))))

;; ---- 5. trace-events retention cap ----------------------------------------

(deftest retention-cap-zero-drops-every-trace-events
  (testing ":trace-events-keep 0 drops :trace-events from every
            record — the structured projections survive"
    (rf/configure! {:epoch-history {:trace-events-keep 0}})
    (rf/make-frame {:id :test/main})
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

;; ---- 4. :rf.epoch/redacted-modified-paths-count ----------------------------
;;
;; Per Spec-Schemas §`:rf/epoch-record` and Privacy.md §Epoch privacy posture:
;; each assembled record carries an integer count of frame-declared sensitive
;; app-db paths whose value differs between `:db-before` and `:db-after`.
;; Computed inside `build-record` from the RAW dbs (the stored record is always
;; raw), so it survives the projection that replaces BOTH sides with the same
;; `:rf/redacted` sentinel — which is the whole point: a post-projection
;; structural diff sees `:rf/redacted` = `:rf/redacted` and emits no row, and
;; this counter is the only surviving signal that something classified moved.
;;
;;   G1. No sensitive paths declared                       -> 0.
;;   G2. Sensitive path declared but value unchanged       -> 0.
;;   G4. Multiple sensitive paths, partial modification    -> the count.
;;   G6. Sensitive path changed -> 1, and projection passes the counter
;;       through unchanged.
;;   G7. Halted record (nil :db-before / :db-after) edge.
;;
;; The G1/G2 zero cases are the DISCRIMINATING CONTROLS for G4/G6: without
;; them a producer hard-wired to return a positive integer would pass.

(deftest G1-no-sensitive-paths-yields-zero-count
  (testing "with no frame-declared sensitive paths registered, the
            counter is 0 (the empty-paths short-circuit)."
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [raw (last-record :test/main)]
      (is (= {:n 1} (:db-after raw))
          "CONTROL — the cascade really ran and really changed the db")
      (is (= 0 (:rf.epoch/redacted-modified-paths-count raw))
          "no declarations -> 0, regardless of how much the db changed"))))

(deftest G2-sensitive-path-unchanged-yields-zero-count
  (testing "a sensitive path is declared but its value did NOT change
            across the cascade — the counter is 0. This is the control
            that keeps G3 honest: a declaration alone is not enough."
    (rf/make-frame {:id :test/main})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :seed (fn [_ _]
                          {:db {:auth   {:password "topsecret"}
                                :public {:counter 0}}}))
    (rf/reg-event :touch-public
                  (fn [{:keys [db]} _] {:db (update-in db [:public :counter] inc)}))
    (rf/dispatch-sync [:seed]         {:frame :test/main})
    (rf/dispatch-sync [:touch-public] {:frame :test/main})

    (let [raw (last-record :test/main)]
      (is (= "topsecret" (get-in raw [:db-before :auth :password]))
          "CONTROL — the declared path is populated on BOTH sides")
      (is (= "topsecret" (get-in raw [:db-after :auth :password])))
      (is (= 1 (get-in raw [:db-after :public :counter]))
          "CONTROL — a NON-declared path did change, so the zero below is
           about the declaration and not about an inert cascade")
      (is (= 0 (:rf.epoch/redacted-modified-paths-count raw))
          ":auth :password value identical pre/post — counter = 0"))))

(deftest G4-multiple-sensitive-paths-partial-modification
  (testing "two sensitive paths declared; one changes, the other does
            not — the counter is 1, not 2. This is the assertion that a
            producer returning `(count declarations)` would fail."
    (rf/make-frame {:id :test/main})
    (install-two-sensitive-paths-schema! :test/main)
    (rf/reg-event :seed
                  (fn [_ _] {:db {:auth {:password "pw-1" :token "tk-1"}}}))
    (rf/reg-event :rotate-token
                  (fn [{:keys [db]} [_ tk]] {:db (assoc-in db [:auth :token] tk)}))
    (rf/dispatch-sync [:seed]                    {:frame :test/main})
    (rf/dispatch-sync [:rotate-token "tk-fresh"] {:frame :test/main})

    (let [raw (last-record :test/main)]
      (is (= "pw-1" (get-in raw [:db-after :auth :password]))
          "CONTROL — the second declared path is present and UNCHANGED")
      (is (= 1 (:rf.epoch/redacted-modified-paths-count raw))
          ":token changed (count += 1); :password unchanged (filtered)")))

  (testing "both declared paths change in the same cascade — counter is 2"
    (rf/make-frame {:id :test/main})
    (install-two-sensitive-paths-schema! :test/main)
    (rf/reg-event :login-both
                  (fn [{:keys [db]} [_ pw tk]]
                    {:db (-> db
                             (assoc-in [:auth :password] pw)
                             (assoc-in [:auth :token]    tk))}))
    (rf/dispatch-sync [:login-both "topsecret" "tok-xyz"] {:frame :test/main})

    (let [raw (last-record :test/main)]
      (is (= 2 (:rf.epoch/redacted-modified-paths-count raw))
          "both :password and :token changed — count = 2"))))

(deftest G6-projection-preserves-counter
  (testing "project-egress passes :rf.epoch/redacted-modified-paths-count
            through unchanged — the integer is structurally non-sensitive
            bookkeeping, parallel to :rf.epoch/sensitive?. Without this the
            counter would be computed and then thrown away at the one
            boundary it exists to serve."
    (rf/make-frame {:id :test/main})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                  (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (rf/project-egress raw)]
      (is (= 1 (:rf.epoch/redacted-modified-paths-count raw)))
      (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
          "CONTROL — the projection really redacted the declared path, so
           the counter below is the ONLY surviving signal that it moved")
      (is (= 1 (:rf.epoch/redacted-modified-paths-count projected))
          "projection preserves the counter verbatim")
      (is (= (:rf.epoch/redacted-modified-paths-count raw)
             (:rf.epoch/redacted-modified-paths-count
               (rf/project-egress projected)))
          "idempotent under a second projection pass"))))

(deftest G7-counter-handles-nil-db-edge
  (testing "halted-destroy records may carry nil :db-before or nil
            :db-after. The producer handles the nil edge:
            nil/non-nil at a declared path IS a change, nil/nil is not."
    (rf/make-frame {:id :test/main})
    (install-sensitive-schema! :test/main)
    (is (= 0 (rf.epoch.assembly/redacted-modified-paths-count :test/main nil nil))
        "nil -> nil at every path: 0 changes")
    (is (= 1 (rf.epoch.assembly/redacted-modified-paths-count
               :test/main nil {:auth {:password "x"}}))
        "nil -> {:auth {:password \"x\"}}: 1 change at the sensitive path")
    (is (= 1 (rf.epoch.assembly/redacted-modified-paths-count
               :test/main {:auth {:password "x"}} nil))
        "{:auth {:password \"x\"}} -> nil: 1 change at the sensitive path")
    (is (= 0 (rf.epoch.assembly/redacted-modified-paths-count
               :test/main
               {:auth {:password "x"}}
               {:auth {:password "x"}}))
        "value-equal across the cascade: 0 changes")))

;; ---- a :redact-fn sub-key is inert -----------------------------------------
;;
;; There is no `(rf/configure! {:epoch-history {:redact-fn f}})`
;; hook — no shim, no deprecation warning. `:redact-fn` is an
;; UNKNOWN sub-key of `:epoch-history`, and `configure!`'s dev-gated
;; `:rf.warning/unknown-configure-key` diagnostic fires on TOP-LEVEL keys
;; only, so an unknown sub-key is silently dropped like any other. This pins
;; that posture from the caller's side: submitting it neither throws nor
;; installs nor invokes.

(deftest retired-redact-fn-sub-key-installs-and-invokes-nothing
  (testing "submitting a :redact-fn sub-key is a silent drop:
            configure! does not throw, no :redact-fn slot appears in the
            live config, and the submitted fn is never called at any point
            in record assembly or projection."
    (rf/make-frame {:id :test/main})
    (install-sensitive-schema! :test/main)

    (is (= #{:depth :trace-events-keep}
           (set (keys (:epoch-history (rf/current-config)))))
        "RESET BASELINE — the fixture-reset config carries exactly the two
         knobs; no :redact-fn slot")

    (let [calls (atom 0)
          scrub (fn [record] (swap! calls inc) (assoc record :db-after :rf/redacted))]
      (rf/configure! {:epoch-history {:redact-fn         scrub
                                      :trace-events-keep 3}})

      (is (= 3 (:trace-events-keep (:epoch-history (rf/current-config))))
          "DISCRIMINATING CONTROL — the SAME configure! call was processed and
           its recognised sibling key landed, so the absence below is a DROP
           of :redact-fn and not a no-op over the whole map")
      (is (= #{:depth :trace-events-keep}
             (set (keys (:epoch-history (rf/current-config)))))
          "the :redact-fn key installed nothing — no :redact-fn slot appears")

      (rf/reg-event :login
                    (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

      (let [raw       (last-record :test/main)
            projected (rf/project-egress raw)]
        (is (some? raw)
            "CONTROL — a record was actually assembled")
        (is (= {:auth {:password "topsecret"}} (:db-after raw))
            "CONTROL — storage stayed RAW; redaction is projection-side")
        (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
            "CONTROL — the projection really ran over a NONEMPTY record, so
             the zero call-count below cannot be satisfied vacuously by an
             unexercised projection")
        (is (map? (:db-after projected))
            "the fn's own whole-slot collapse never happened — :db-after is
             still a walked map, not the scalar sentinel it would have
             returned")
        (is (zero? @calls)
            "the submitted fn was never invoked — there is no hook to
             call it from")))))

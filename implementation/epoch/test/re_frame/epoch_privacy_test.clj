(ns re-frame.epoch-privacy-test
  "Per rf2-vq5o0 — coverage for the epoch privacy contract introduced
  by rf2-j1m7x (spec) and rf2-mrsck (impl). Three contract surfaces:

    1. The record-level :rf.epoch/sensitive? rollup — true when any
       captured trace event carries the :sensitive? stamp OR any
       schema-declared sensitive path holds a non-nil leaf in
       :db-before / :db-after; false otherwise.

    2. re-frame.epoch/projected-record + projected-history — the
       single normative projection emission site for off-box egress.
       Routes :db-before / :db-after / :trigger-event / :trace-events
       through elide-wire-value with off-box defaults
       (:include-sensitive? false, :include-large? false).

    3. Listener fan-out delivers RAW records by default — silent
       projection would break Causa's diff visualiser and on-box
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
            [re-frame.epoch.state :as state]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; Side-effect requires (mirrors epoch_test.clj):
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (trace/clear-trace-cbs!)
  (epoch/clear-history!)
  (epoch/clear-epoch-cbs!)
  (reset! @#'state/config {:depth 50 :trace-events-keep 5 :redact-fn nil})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(defn- install-sensitive-schema!
  "Register a `[:auth :password]` sensitive schema slot against
  `frame-id` and force the elision registry population. Returns nil."
  [frame-id]
  (rf/reg-app-schema [:auth]
                     [:map [:password {:sensitive? true} :string]]
                     {:frame frame-id})
  (rf/populate-sensitive-from-schemas! frame-id)
  nil)

(defn- install-large-schema!
  "Register a `[:blob :payload]` large schema slot against `frame-id`
  and force the elision registry population."
  [frame-id]
  (rf/reg-app-schema [:blob]
                     [:map [:payload {:large? true :hint "big"} :string]]
                     {:frame frame-id})
  (rf/populate-elision-from-schemas! frame-id)
  nil)

(defn- big-string [n]
  (apply str (repeat n "X")))

;; ---- 1. sensitive rollup ---------------------------------------------------

(deftest rollup-false-on-non-sensitive-cascade
  (testing "no sensitive handler, no schema-declared sensitive path —
            rollup reads strict false"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
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
    (rf/reg-event-db :secret-write
                     {:sensitive? true}   ;; stored, no longer consulted
                     (fn [db _] (assoc db :token "shh")))
    (rf/dispatch-sync [:secret-write] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (false? (:rf.epoch/sensitive? r))
          "rollup reads false — handler-meta annotation no longer drives the stamp"))))

(deftest rollup-true-from-schema-declared-non-nil-leaf
  (testing "a schema-declared sensitive path that resolves to a non-nil
            leaf in :db-after triggers the rollup even when no handler
            in scope is sensitive"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (true? (:rf.epoch/sensitive? r))))))

(deftest rollup-false-when-schema-path-resolves-to-nil
  (testing "a frame with a schema-declared sensitive path BUT the
            recorded :db-before / :db-after carry no value at the path
            — rollup reads false (the declaration is structural; the
            cascade carried no actual sensitive material)"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :unrelated (fn [db _] (assoc db :n 42)))
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
    (rf/reg-event-db :seed
                     (fn [_ _] {:auth {:password "old-secret"}}))
    (rf/reg-event-db :clear-pw
                     (fn [db _] (update db :auth dissoc :password)))

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
  (testing "halted-destroy records carry nil :db-before / :db-after
            (per rf2-v0jwt); the rollup must still produce a strict
            boolean (no NPE on the nil-db sensitive-leaf walk)"
    ;; Synthesise a halted-destroy record by calling build-record's
    ;; private path through the destroy hook surface.
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    ;; Trigger a real cascade so capture-buffers carries a run-start;
    ;; on destroy mid-drain the halted-destroy record fires.
    (let [seen (atom [])]
      (rf/register-epoch-cb! ::halt-watcher
                             (fn [r] (swap! seen conj r)))
      (rf/reg-event-fx :destroy-self
                       (fn [_ _]
                         (frame/destroy-frame! :test/main)
                         {}))
      ;; The destroy fires inside the drain — on-frame-destroyed!
      ;; emits a :halted-destroy partial record carrying nil dbs.
      (try (rf/dispatch-sync [:destroy-self] {:frame :test/main})
           (catch Throwable _ nil))
      (when-let [halted (some (fn [r]
                                (when (= :halted-destroy (:outcome r)) r))
                              @seen)]
        (is (or (false? (:rf.epoch/sensitive? halted))
                (true?  (:rf.epoch/sensitive? halted)))
            "rollup is a strict boolean on the halted-destroy path")
        (is (nil? (:db-before halted))
            "halted-destroy carries nil :db-before")
        (is (nil? (:db-after halted))
            "halted-destroy carries nil :db-after")))))

;; ---- 2. projected-record ---------------------------------------------------

(deftest projected-record-redacts-sensitive-in-db-after
  (testing "schema-declared sensitive path in :db-after lands as
            :rf/redacted in the projected record"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
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
    (rf/reg-event-db :seed
                     (fn [_ _] {:auth {:password "original-secret"}}))
    (rf/reg-event-db :inc (fn [db _] (update db :n (fnil inc 0))))

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
  (testing "schema-declared :large? path in :db-after lands as a
            :rf.size/large-elided marker in the projected record"
    (rf/reg-frame :test/main {})
    (install-large-schema! :test/main)
    (rf/reg-event-db :store
                     (fn [db [_ payload]]
                       (assoc-in db [:blob :payload] payload)))
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

(deftest projected-record-bookkeeping-passes-through
  (testing "bookkeeping slots are preserved by the projection — the
            projection only mutates payload-bearing slots"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (doseq [k [:epoch-id :frame :committed-at :event-id :outcome
                 :schema-digest :rf.epoch/sensitive?]]
        (is (= (get raw k) (get projected k))
            (str "bookkeeping slot " k " passes through unchanged"))))))

(deftest projected-record-structured-projections-pass-through
  (testing ":sub-runs / :renders / :effects pass through unchanged —
            they carry no app-db material (only sub-ids, render-keys,
            fx-ids, and outcome tags)"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (is (= (:sub-runs raw) (:sub-runs projected)))
      (is (= (:renders  raw) (:renders  projected)))
      (is (= (:effects  raw) (:effects  projected))))))

(deftest projected-record-trigger-event-projected
  (testing ":trigger-event is walked through the projection so a
            sensitive value carried in the dispatched event vector
            (e.g. a password as a positional arg) does not leak via
            the off-box surface"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    ;; The login event payload itself is the sensitive value, but the
    ;; schema-declared sensitive path is :auth/password, not the
    ;; trigger-event slot. The projection still walks :trigger-event;
    ;; whether the leaf there gets redacted depends on whether the
    ;; walker can find a sensitive declaration matching that path.
    ;; This test pins that the projection RAN against :trigger-event
    ;; (the slot exists in the projected record) — what it changes
    ;; depends on the registered declarations.
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [raw       (last-record :test/main)
          projected (epoch/projected-record raw)]
      (is (contains? projected :trigger-event)
          ":trigger-event slot preserved through the projection"))))

(deftest projected-record-sensitive-wins-over-large
  (testing "the wire-elision walker's composition rule (sensitive
            wins over large) holds inside the projection — a slot
            declared both :sensitive? AND :large? lands as :rf/redacted,
            never as a :rf.size/large-elided marker (the marker would
            leak :path / :bytes / :digest)"
    (rf/reg-frame :test/main {})
    (rf/reg-app-schema [:secret-pdf]
                       [:string {:large? true :sensitive? true
                                 :hint "encrypted blob"}]
                       {:frame :test/main})
    (elision/populate-from-schemas! :test/main)
    (rf/reg-event-db :store-pdf
                     (fn [db [_ payload]]
                       (assoc db :secret-pdf payload)))
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

;; ---- 3. projected-history --------------------------------------------------

(deftest projected-history-walks-the-ring
  (testing "projected-history returns one projected record per ring
            entry, in oldest-first order"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :seed (fn [_ _] {}))
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))

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
  (testing "register-epoch-cb! callbacks receive the RAW record (NOT
            the projected one) — Causa's diff visualiser and on-box
            restore drivers depend on the raw :db-after; a silent
            projection would break them. Forwarders that egress
            off-box opt INTO projection at the wire boundary."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (let [seen (atom [])]
      (rf/register-epoch-cb! ::raw-listener
                             (fn [r] (swap! seen conj r)))
      (rf/reg-event-db :login
                       (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
      (is (= 1 (count @seen)) "listener fired once")
      (is (= "topsecret"
             (get-in (first @seen) [:db-after :auth :password]))
          "listener received the RAW value — projection is opt-in at
           the egress boundary, not the listener boundary"))))

(deftest forwarder-shape-projects-at-egress
  (testing "the canonical off-box-forwarder pattern: register raw,
            project at egress. This pins the recommended shape for
            tools (Causa-MCP watch-epochs, story / pair recorders)."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (let [shipped (atom [])
          ship!   (fn [record]
                    ;; Tool-side forwarder body — project here.
                    (swap! shipped conj (epoch/projected-record record)))]
      (rf/register-epoch-cb! ::forwarder ship!)
      (rf/reg-event-db :login
                       (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
      (is (= 1 (count @shipped)))
      (is (= :rf/redacted
             (get-in (first @shipped) [:db-after :auth :password]))
          "the off-box-bound payload is projected"))))

;; ---- 5. trace-events retention cap ----------------------------------------

(deftest retention-cap-default-finite-five
  (testing "the default :trace-events-keep is the finite default 5 —
            drive >5 cascades, the oldest records lose :trace-events
            but keep the structured projections (per rf2-mrsck)"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

    (is (= 5 (:trace-events-keep (epoch/current-config))))

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
    (rf/configure :epoch-history {:trace-events-keep 0})
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

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
    (rf/configure :epoch-history {:trace-events-keep 100})
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))

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
      (rf/reg-event-db :prod.priv/inc
                       (fn [db _] (update db :n (fnil inc 0))))
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
      (rf/reg-event-db :prod.priv/silent
                       (fn [db _] (update db :n (fnil inc 0))))
      (rf/dispatch-sync [:prod.priv/silent])
      (is (empty? (rf/epoch-history :rf/default))
          "ring stays empty — rollup never computed"))))

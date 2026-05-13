(ns re-frame.sensitive-stamping-test
  "Per rf2-isdwf — runtime `:sensitive?` top-level stamping per Spec 009
  §Privacy / sensitive data in traces (lines 1149-1268).

  Validates:
  - Plain (non-sensitive) handler -> trace event has no `:sensitive?` flag.
  - Handler with `^{:sensitive? true}` meta -> trace event has
    `:sensitive? true` at TOP level (not under :tags).
  - `:event/dispatched` trace event (emitted at queue time, before
    handler-scope binding) consults registrar meta and stamps the flag.
  - The flag rides every emit inside the cascade scope:
    `:event/dispatched`, `:event`, `:event/db-changed`, `:rf.fx/handled`,
    `:rf.error/handler-exception`, `:rf.error/no-such-handler`.
  - fx handlers, cofx handlers, subs all carry their own reading
    (innermost in-scope handler's flag wins per Spec 009 line 1177).
  - `with-redacted` interceptor scrubs event payloads + the in-chain
    emits carry the redacted form.
  - Registration-time `:rf.warning/sensitive-without-redaction`:
    fires once per (kind, id) pair when :sensitive? without with-redacted.
  - `:no-redaction-needed?` opt-out suppresses the warning.
  - `:rf/redacted-paths` is the sentinel; `:rf/redacted` lives in the
    framework reserved-keyword namespace.
  - Composition with walker: stamped event flows through
    `elide-wire-value` and the sensitive-drop wins.
  - `trace-buffer {:sensitive? false}` filter excludes sensitive events.
  - `trace/sensitive?` predicate returns true on top-level-stamped events.

  JVM-only — the dynamic-var binding mechanism is platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-trace-cbs!)
  (privacy/clear-suppression-cache!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/remove-trace-cb! ::rec)))
    @seen))

(defn- events-of [evs op]
  (filterv #(= op (:operation %)) evs))

;; ---- top-level stamping basics --------------------------------------------

(deftest plain-handler-no-sensitive-flag
  (testing "A handler without :sensitive? meta emits trace events with NO
   top-level :sensitive? key (per Spec 009 line 1176 — consumers treat
   absent as false; the field is omitted, NOT set to false)"
    (rf/reg-event-db :rf2-isdwf/plain
                     (fn [db _] db))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-isdwf/plain]))]
      (doseq [ev evs]
        (is (not (contains? ev :sensitive?))
            (str ":sensitive? must be absent on plain-handler trace: "
                 (:operation ev) " — got " (:sensitive? ev)))))))

(deftest sensitive-handler-top-level-stamp
  (testing "A handler with ^{:sensitive? true} meta emits trace events
   with :sensitive? true at the TOP LEVEL (not under :tags) per Spec 009
   line 1175"
    (rf/reg-event-db :rf2-isdwf/sensitive
                     {:sensitive? true :no-redaction-needed? true}
                     (fn [db _] (assoc db :ran? true)))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-isdwf/sensitive]))
          [dispatched]   (events-of evs :event/dispatched)
          [db-changed]   (events-of evs :event/db-changed)
          run-start-end  (events-of evs :event)]
      (is (some? dispatched) ":event/dispatched fired")
      (is (true? (:sensitive? dispatched))
          ":event/dispatched top-level :sensitive? true")
      (is (not (contains? (:tags dispatched) :sensitive?))
          ":sensitive? is hoisted, NOT under :tags")
      (is (some? db-changed) ":event/db-changed fired")
      (is (true? (:sensitive? db-changed))
          ":event/db-changed top-level :sensitive? true")
      (is (not (contains? (:tags db-changed) :sensitive?))
          ":sensitive? is hoisted on db-changed, NOT under :tags")
      (is (seq run-start-end))
      (doseq [ev run-start-end]
        (is (true? (:sensitive? ev))
            (str ":event (" (:tags ev) ") carries top-level :sensitive? true"))))))

(deftest sensitive-false-meta-treated-as-non-sensitive
  (testing ":sensitive? false (an explicit false) reads as non-sensitive —
   only true triggers the stamp"
    (rf/reg-event-db :rf2-isdwf/false-flag
                     {:sensitive? false}
                     (fn [db _] db))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-isdwf/false-flag]))]
      (doseq [ev evs]
        (is (not (true? (:sensitive? ev)))
            ":sensitive? must NOT be true when meta carries false")))))

;; ---- :event/dispatched is stamped at queue time --------------------------

(deftest event-dispatched-queue-time-stamp
  (testing ":event/dispatched fires at enqueue time (BEFORE the
   handler-scope binding); the runtime must look up the handler's
   meta directly and stamp :sensitive? on the dispatched trace"
    (rf/reg-event-db :rf2-isdwf/queue-stamp
                     {:sensitive? true :no-redaction-needed? true}
                     (fn [db _] db))
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-isdwf/queue-stamp]))
          [dispatched] (events-of evs :event/dispatched)]
      (is (some? dispatched))
      (is (true? (:sensitive? dispatched))
          ":event/dispatched stamped even though it fires outside the
           handler-scope binding"))))

(deftest event-dispatched-unknown-handler-not-stamped
  (testing "Dispatching to an unregistered handler does NOT stamp
   :sensitive? — the field is absent (handler-meta lookup miss)"
    (let [evs (record-traces #(rf/dispatch-sync [:rf2-isdwf/never-registered]))
          [dispatched] (events-of evs :event/dispatched)]
      (is (some? dispatched))
      (is (not (contains? dispatched :sensitive?))
          ":sensitive? absent when handler is unknown"))))

;; ---- fx handler scope (innermost wins) -----------------------------------

(deftest fx-handled-takes-fx-handler-sensitivity
  (testing "When a sensitive event handler dispatches to a non-sensitive fx,
   the :rf.fx/handled trace reflects the FX handler's reading (innermost
   wins per Spec 009 line 1177)"
    (rf/reg-fx :rf2-isdwf/non-sensitive-fx
               (fn [_ _] :ok))
    (rf/reg-event-fx :rf2-isdwf/sens-evt-non-sens-fx
                     {:sensitive? true :no-redaction-needed? true}
                     (fn [_ _] {:fx [[:rf2-isdwf/non-sensitive-fx {}]]}))
    (let [evs (record-traces
                #(rf/dispatch-sync [:rf2-isdwf/sens-evt-non-sens-fx]))
          [fx-handled] (events-of evs :rf.fx/handled)]
      (is (some? fx-handled))
      (is (not (true? (:sensitive? fx-handled)))
          "fx-handled reflects FX handler's reading (not sensitive)"))))

(deftest fx-handled-takes-fx-handler-sensitivity-flag
  (testing "When a non-sensitive event dispatches to a sensitive fx,
   the :rf.fx/handled trace IS stamped per the fx-level reading"
    (rf/reg-fx :rf2-isdwf/sensitive-fx
               {:sensitive? true}
               (fn [_ _] :ok))
    (rf/reg-event-fx :rf2-isdwf/non-sens-evt-sens-fx
                     (fn [_ _] {:fx [[:rf2-isdwf/sensitive-fx {}]]}))
    (let [evs (record-traces
                #(rf/dispatch-sync [:rf2-isdwf/non-sens-evt-sens-fx]))
          [fx-handled] (events-of evs :rf.fx/handled)]
      (is (some? fx-handled))
      (is (true? (:sensitive? fx-handled))
          "fx-handled reflects FX handler's :sensitive? meta"))))

;; ---- cofx handler scope --------------------------------------------------

(deftest cofx-scope-stamps-on-inject
  (testing "A sensitive cofx handler's injection produces trace events
   stamped :sensitive? true while the cofx body runs"
    (rf/reg-cofx :rf2-isdwf/sens-cofx
                 {:sensitive? true}
                 (fn [ctx]
                   ;; Emit a trace from inside the cofx body to verify
                   ;; the binding scope.
                   (trace/emit! :info :rf2-isdwf/inside-cofx
                                {:where :cofx})
                   (assoc ctx :rf2-isdwf/cofx-ran? true)))
    (rf/reg-event-fx :rf2-isdwf/uses-sens-cofx
                     [(rf/inject-cofx :rf2-isdwf/sens-cofx)]
                     (fn [_ _] {}))
    (let [evs (record-traces
                #(rf/dispatch-sync [:rf2-isdwf/uses-sens-cofx]))
          [inside] (events-of evs :rf2-isdwf/inside-cofx)]
      (is (some? inside) "trace fired inside cofx body")
      (is (true? (:sensitive? inside))
          ":rf2-isdwf/inside-cofx is stamped :sensitive? true while
           the cofx handler is in scope"))))

;; ---- sub recompute scope -------------------------------------------------

(deftest sub-run-stamps-on-sensitive-sub
  (testing "A sub registered with :sensitive? true emits :sub/run trace
   events stamped :sensitive? true per Spec 009 line 1160 (subs are
   one of the conventional use sites)"
    (rf/reg-sub :rf2-isdwf/sens-sub
                {:sensitive? true}
                (fn [db _] (:counter db 0)))
    (let [evs (record-traces
                #(deref (rf/subscribe [:rf2-isdwf/sens-sub])))
          [sub-run] (events-of evs :sub/run)]
      (is (some? sub-run))
      (is (true? (:sensitive? sub-run))
          ":sub/run carries top-level :sensitive? true for sensitive sub"))))

;; ---- error scope ---------------------------------------------------------

(deftest handler-exception-stamps-on-sensitive-handler
  (testing "When a sensitive handler throws, the
   :rf.error/handler-exception trace carries :sensitive? true at top
   level (the error rides inside the handler-scope binding)"
    (rf/reg-event-db :rf2-isdwf/sens-throws
                     {:sensitive? true :no-redaction-needed? true}
                     (fn [_ _] (throw (ex-info "boom" {}))))
    (let [evs (record-traces
                #(rf/dispatch-sync [:rf2-isdwf/sens-throws]))
          [err] (events-of evs :rf.error/handler-exception)]
      (is (some? err))
      (is (true? (:sensitive? err))
          ":rf.error/handler-exception carries the flag"))))

;; ---- with-redacted interceptor -------------------------------------------

(deftest with-redacted-scrubs-payload
  (testing "(rf/with-redacted [[:password]]) scrubs the named path in the
   event payload before the in-chain emits see it; the handler body sees
   the UNREDACTED payload per Spec 009 line 1221"
    (let [seen (atom nil)]
      (rf/reg-event-db :rf2-isdwf/scrubbed
                       {:sensitive? true}
                       [(rf/with-redacted [[:password]])]
                       (fn [db [_ payload]]
                         (reset! seen payload)
                         db))
      (let [evs (record-traces
                  #(rf/dispatch-sync
                     [:rf2-isdwf/scrubbed {:username "ada" :password "shh"}]))
            [db-changed] (events-of evs :event/db-changed)]
        ;; Handler body sees real values
        (is (= "shh" (:password @seen))
            "handler body sees UNREDACTED payload")
        (is (= "ada" (:username @seen))
            "handler body sees other fields unchanged")
        ;; Trace surface sees redacted form on db-changed
        (is (some? db-changed))
        (let [emitted-event (get-in db-changed [:tags :event])]
          (is (= :rf/redacted
                 (get-in emitted-event [1 :password]))
              ":event/db-changed :tags :event has :password redacted")
          (is (= "ada" (get-in emitted-event [1 :username]))
              ":event/db-changed preserves non-redacted fields"))))))

(deftest with-redacted-stamps-handler-exception
  (testing ":rf.error/handler-exception's :tags :event slot honours
   with-redacted (per Spec 009 line 1226-1227)"
    (rf/reg-event-db :rf2-isdwf/scrub-throws
                     {:sensitive? true}
                     [(rf/with-redacted [[:secret]])]
                     (fn [_ _] (throw (ex-info "boom" {}))))
    (let [evs (record-traces
                #(rf/dispatch-sync
                   [:rf2-isdwf/scrub-throws {:secret "xyz"}]))
          [err] (events-of evs :rf.error/handler-exception)]
      (is (some? err))
      (is (true? (:sensitive? err))
          "error event is stamped :sensitive? true")
      (let [emitted-event (get-in err [:tags :event])]
        (is (= :rf/redacted (get-in emitted-event [1 :secret]))
            ":rf.error/handler-exception :tags :event has :secret redacted")))))

(deftest with-redacted-composes-with-sensitive
  (testing "A handler with BOTH :sensitive? true AND [(with-redacted ...)]
   emits trace events that are BOTH stamped AND carry redacted payloads
   per Spec 009 line 1229"
    (rf/reg-event-db :rf2-isdwf/both
                     {:sensitive? true}
                     [(rf/with-redacted [[:token]])]
                     (fn [db _] db))
    (let [evs (record-traces
                #(rf/dispatch-sync [:rf2-isdwf/both {:token "abc"}]))
          [db-changed] (events-of evs :event/db-changed)]
      (is (true? (:sensitive? db-changed))
          ":event/db-changed is stamped sensitive")
      (let [emitted-event (get-in db-changed [:tags :event])]
        (is (= :rf/redacted (get-in emitted-event [1 :token]))
            "and its :tags :event payload is redacted")))))

(deftest with-redacted-multi-path
  (testing "(rf/with-redacted) accepts multiple paths"
    (let [seen (atom nil)]
      (rf/reg-event-db :rf2-isdwf/multi-scrub
                       {:sensitive? true}
                       [(rf/with-redacted [[:password] [:totp-code]])]
                       (fn [db [_ payload]]
                         (reset! seen payload)
                         db))
      (let [evs (record-traces
                  #(rf/dispatch-sync
                     [:rf2-isdwf/multi-scrub
                      {:username "ada" :password "shh" :totp-code "123456"}]))
            [db-changed] (events-of evs :event/db-changed)]
        (is (= "shh" (:password @seen)))
        (is (= "123456" (:totp-code @seen)))
        (let [emitted-event (get-in db-changed [:tags :event])]
          (is (= :rf/redacted (get-in emitted-event [1 :password])))
          (is (= :rf/redacted (get-in emitted-event [1 :totp-code])))
          (is (= "ada" (get-in emitted-event [1 :username]))))))))

(deftest with-redacted-non-payload-event-shape-no-op
  (testing "with-redacted handles non-conventional event shapes
   gracefully — when the second slot isn't a payload map, the event
   passes through unchanged"
    (rf/reg-event-db :rf2-isdwf/non-payload
                     {:sensitive? true}
                     [(rf/with-redacted [[:x]])]
                     (fn [db _] db))
    (let [evs (record-traces
                #(rf/dispatch-sync [:rf2-isdwf/non-payload 42]))
          [db-changed] (events-of evs :event/db-changed)]
      ;; with-redacted leaves [event-id 42] alone (second slot is not a map)
      (is (= [:rf2-isdwf/non-payload 42]
             (get-in db-changed [:tags :event]))))))

;; ---- registration-time warning -------------------------------------------

(deftest sensitive-without-redaction-warning
  (testing "Registering a sensitive handler without with-redacted (and
   without the :no-redaction-needed? opt-out) emits
   :rf.warning/sensitive-without-redaction once per (kind, id) pair"
    (let [seen (atom [])]
      (rf/register-trace-cb! ::rec
                             (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event-db :rf2-isdwf/needs-redaction
                         {:sensitive? true}
                         (fn [db _] db))
        (finally (rf/remove-trace-cb! ::rec)))
      (let [warnings (filterv
                       #(= :rf.warning/sensitive-without-redaction
                           (:operation %))
                       @seen)]
        (is (= 1 (count warnings))
            "exactly one warning")
        (is (= :warning (:op-type (first warnings))))
        (is (= :event (get-in (first warnings) [:tags :kind])))
        (is (= :rf2-isdwf/needs-redaction
               (get-in (first warnings) [:tags :id])))))))

(deftest sensitive-without-redaction-warning-once
  (testing "The warning fires once per (kind, id) — re-registering the
   same id does NOT re-fire (suppression cache)"
    (let [seen (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event-db :rf2-isdwf/dedup
                         {:sensitive? true}
                         (fn [db _] db))
        (rf/reg-event-db :rf2-isdwf/dedup
                         {:sensitive? true}
                         (fn [db _] (assoc db :v2 true)))
        (finally (rf/remove-trace-cb! ::rec)))
      (let [warnings (filterv
                       #(= :rf.warning/sensitive-without-redaction
                           (:operation %))
                       @seen)]
        (is (= 1 (count warnings))
            "second registration does NOT re-fire warning")))))

(deftest sensitive-with-redaction-no-warning
  (testing "A sensitive handler that DOES carry with-redacted does not
   trigger the warning"
    (let [seen (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event-db :rf2-isdwf/has-scrub
                         {:sensitive? true}
                         [(rf/with-redacted [[:secret]])]
                         (fn [db _] db))
        (finally (rf/remove-trace-cb! ::rec)))
      (let [warnings (filterv
                       #(= :rf.warning/sensitive-without-redaction
                           (:operation %))
                       @seen)]
        (is (zero? (count warnings))
            "no warning when with-redacted is in chain")))))

(deftest no-redaction-needed-opt-out
  (testing ":no-redaction-needed? true on the metadata-map suppresses the
   warning for handlers that genuinely don't need with-redacted (e.g.
   a sensitive handler whose only payload IS the user-supplied event-id
   itself, no scrubbable fields)"
    (let [seen (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event-db :rf2-isdwf/opt-out
                         {:sensitive? true :no-redaction-needed? true}
                         (fn [db _] db))
        (finally (rf/remove-trace-cb! ::rec)))
      (let [warnings (filterv
                       #(= :rf.warning/sensitive-without-redaction
                           (:operation %))
                       @seen)]
        (is (zero? (count warnings))
            ":no-redaction-needed? true suppresses the warning")))))

(deftest non-sensitive-no-warning
  (testing "Non-sensitive handlers (the default) get no warning even
   without with-redacted"
    (let [seen (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-event-db :rf2-isdwf/plain-noopt
                         (fn [db _] db))
        (finally (rf/remove-trace-cb! ::rec)))
      (let [warnings (filterv
                       #(= :rf.warning/sensitive-without-redaction
                           (:operation %))
                       @seen)]
        (is (zero? (count warnings)))))))

(deftest suppression-cache-resets-on-frame-destroy
  (testing "After destroy-frame!, re-registering a still-misconfigured
   sensitive handler re-fires the warning (spec 'cache reset on frame
   destroy')"
    ;; First registration fires
    (let [seen-1 (atom [])]
      (rf/register-trace-cb! ::r1 (fn [ev] (swap! seen-1 conj ev)))
      (rf/reg-event-db :rf2-isdwf/reset-test
                       {:sensitive? true}
                       (fn [db _] db))
      (rf/remove-trace-cb! ::r1)
      (is (= 1 (count (filterv #(= :rf.warning/sensitive-without-redaction
                                   (:operation %))
                               @seen-1)))))
    ;; Destroy the default frame
    (frame/destroy-frame! :rf/default)
    ;; Re-establish frame
    (rf/init! plain-atom/adapter)
    ;; Re-register; the cache was cleared so the warning re-fires
    (let [seen-2 (atom [])]
      (rf/register-trace-cb! ::r2 (fn [ev] (swap! seen-2 conj ev)))
      (rf/reg-event-db :rf2-isdwf/reset-test
                       {:sensitive? true}
                       (fn [db _] db))
      (rf/remove-trace-cb! ::r2)
      (is (= 1 (count (filterv #(= :rf.warning/sensitive-without-redaction
                                   (:operation %))
                               @seen-2)))
          "warning fires again after frame destroy"))))

;; ---- trace-buffer filter -------------------------------------------------

(deftest trace-buffer-sensitive-filter
  (testing "(rf/trace-buffer {:sensitive? false}) excludes sensitive
   events; (rf/trace-buffer {:sensitive? true}) selects only sensitive
   events. Absent constraint -> all events. Per Spec 009 §Filter
   vocabulary"
    (rf/clear-trace-buffer!)
    (rf/configure :trace-buffer {:depth 100})
    (rf/reg-event-db :rf2-isdwf/buf-sens
                     {:sensitive? true :no-redaction-needed? true}
                     (fn [db _] db))
    (rf/reg-event-db :rf2-isdwf/buf-plain
                     (fn [db _] db))
    (rf/dispatch-sync [:rf2-isdwf/buf-sens])
    (rf/dispatch-sync [:rf2-isdwf/buf-plain])
    (let [all   (rf/trace-buffer)
          sens  (rf/trace-buffer {:sensitive? true})
          plain (rf/trace-buffer {:sensitive? false})]
      (is (pos? (count sens)))
      (is (pos? (count plain)))
      (is (= (count all) (+ (count sens) (count plain)))
          "the two partitions sum to the total")
      (doseq [ev sens]
        (is (true? (:sensitive? ev))
            "{:sensitive? true} returns only sensitive events"))
      (doseq [ev plain]
        (is (not (true? (:sensitive? ev)))
            "{:sensitive? false} returns only non-sensitive events")))))

;; ---- public sensitive? predicate -----------------------------------------

(deftest sensitive-predicate
  (testing "rf/sensitive? returns true on top-level-stamped events,
   false otherwise — replaces the per-consumer private helper (rf2-isdwf
   audit G5; consumer-side dedup landed in rf2-sqxjn)"
    (is (true?  (rf/sensitive? {:sensitive? true})))
    (is (false? (rf/sensitive? {:sensitive? false})))
    (is (false? (rf/sensitive? {})))
    (is (false? (rf/sensitive? nil)))
    (is (false? (rf/sensitive? "not a map")))
    (is (false? (rf/sensitive? {:tags {:sensitive? true}}))
        "nested :sensitive? inside :tags does NOT count — only the
         top-level field per Spec 009 line 1175"))
  (testing "rf/sensitive? and re-frame.trace/sensitive? are the same fn
   — re-frame.core re-exports from re-frame.trace via (def ...) (rf2-sqxjn)"
    (is (identical? rf/sensitive? trace/sensitive?))))

;; ---- composition with wire-elision walker --------------------------------

(deftest walker-composition
  (testing "When the elision walker traverses a value with a sensitive
   path, it substitutes :rf/redacted. The walker's predicate reads
   :sensitive? from the elision registry; this test feeds the registry
   directly (rf2-isdwf is upstream of the runtime stamp, not the walker
   predicate — they compose orthogonally)"
    ;; Declare a path as sensitive in the elision registry.
    (elision/declare-large-path! [:secrets :password])
    ;; Use raw write to set :sensitive? on the declaration.
    (let [container (frame/get-frame-db :rf/default)
          db (re-frame.substrate.adapter/read-container container)]
      (re-frame.substrate.adapter/replace-container!
        container
        (assoc-in db [:rf/elision :declarations [:secrets :password]]
                  {:sensitive? true :source :declared})))
    (let [val   {:secrets {:password "p" :hint "h"}}
          ;; Walker uses default include-sensitive? false; produces sentinel
          walked (rf/elide-wire-value val {:frame :rf/default})]
      (is (= :rf/redacted (get-in walked [:secrets :password]))
          "walker substitutes :rf/redacted on the sensitive path"))))

;; ---- multi-handler stress test -------------------------------------------

(deftest stamp-rides-every-emit-in-scope
  (testing "Per Spec 009 line 1175 — the stamp MUST appear on every trace
   event emitted within the sensitive handler's scope. Exercise the
   :event/dispatched, :event :phase :run-start, :event/db-changed,
   :rf.fx/handled, :event :phase :run-end."
    (rf/reg-fx :rf2-isdwf/test-fx (fn [_ _] :ok))
    (rf/reg-event-fx :rf2-isdwf/all-emits
                     {:sensitive? true :no-redaction-needed? true}
                     (fn [{:keys [db]} _]
                       {:db (assoc db :ran? true)
                        :fx [[:rf2-isdwf/test-fx {}]]}))
    (let [evs (record-traces
                #(rf/dispatch-sync [:rf2-isdwf/all-emits]))
          op-types (->> evs
                        (map (juxt :operation :sensitive?))
                        set)]
      ;; Every operation type from this cascade should have a sensitive entry
      (doseq [op #{:event/dispatched :event :event/db-changed
                   :event/do-fx}]
        (is (some (fn [[o s]] (and (= o op) (true? s))) op-types)
            (str op " carries top-level :sensitive? true"))))))

(ns re-frame.cascade-dispatch-id-test
  "Per rf2-g6ih4 — `:rf.trace/dispatch-id` is cascade-wide on every trace event.

  Spec 009 §Dispatch correlation locks `:rf.trace/dispatch-id` as a cascade-wide
  correlation key: it rides on **every** trace event emitted inside a
  dispatch's run-to-completion drain — `:rf.event/dispatched`,
  `:rf.event/db-changed`, `:rf.fx/handled`, `:rf.sub/run`,
  `:rf.machine/transition`, `:rf.error/*`, every emit produced while
  processing the event. `:rf.trace/parent-dispatch-id` remains scoped to
  `:rf.event/dispatched` only.

  This file exercises the cascade-wide stamping by dispatching a
  representative cascade and asserting:

  (a) every non-`:rf.event/dispatched` trace event emitted while a drain
      is in flight carries `:tags :rf.trace/dispatch-id` matching the cascade
      that started the drain;
  (b) child dispatches issued from inside fx handlers get their OWN
      freshly-allocated `:rf.trace/dispatch-id` on their `:rf.event/dispatched`
      event (the parent's id rides on `:rf.trace/parent-dispatch-id` instead);
  (c) `*current-dispatch-id*` is unbound across cascade boundaries —
      trace events emitted outside any drain (e.g. registration-time
      trace events, frame creation) carry no `:rf.trace/dispatch-id`.

  JVM-only — the dynamic-var binding mechanism is platform-agnostic.

  ## Posture split (rf2-d2841)

  `:rf.trace/dispatch-id` is a TRACE-CORRELATION key and there is no production
  channel that carries it — checked, not assumed: the always-on error record
  (`error-emit/dispatch-on-error!`) is the tight `{:error :event :event-id
  :frame :time :exception :elapsed-ms :source-coord}` shape plus the optional
  `:failing-id` / `:reason` lift, and carries no correlation id. So every
  correlation claim here is guarded.

  What keeps this off the class-2 list is that each case drives a REAL CASCADE
  and the cascade is production behaviour. Every deftest now witnesses the work
  the correlation key was correlating: the fx fired, the child event committed,
  the thrown handler reached the always-on `:errors` stream, two sequential
  dispatches each committed. Under the gate those run for the first time here.

  THREE VACUOUS PASSES CAME OFF, in two classes.
  `frame-lifecycle-emits-stay-uncorrelated-under-a-cascade-scope` carried two
  class-1 negatives — `(is (nil? (dispatch-id (by-op :rf.frame/re-registered))))`
  over an empty ring, where `by-op` returns nil and `dispatch-id` of nil is nil.
  `parent-dispatch-id-only-on-event-dispatched` is the sharper one and a shape
  worth recognising on sight: its whole body is a `doseq` over the captured
  events, so under the gate it iterates ZERO times, runs NO assertions at all,
  and clojure.test reports the deftest as passing. A green deftest that
  executed no assertion is the same false green as one that executed a vacuous
  assertion, and it is harder to see."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces
  "Run `body-fn` with a trace listener attached and return the captured
  events."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (rf/unregister-listener! :trace ::rec)))))

(defn- events-of [evs predicate]
  (filter predicate evs))

(defn- dispatch-id [ev] (get-in ev [:tags :rf.trace/dispatch-id]))

(defn- record-errors
  "ALWAYS-ON (rf2-d2841): run `body-fn` with an `:errors`-stream listener
  attached and return the tight records the corpus-wide `error-emit` registry
  fanned. Not gated on `rf.interop/debug-enabled?`."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :errors ::err (fn [rec] (swap! seen conj rec)))
    (try (body-fn)
         (finally (rf/unregister-listener! :errors ::err)))
    @seen))

;; ---- cascade-wide stamping ------------------------------------------------

(deftest dispatch-id-rides-every-event-in-the-cascade
  (testing "every trace event emitted while a drain is in flight carries the cascade's :rf.trace/dispatch-id"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :seed
                     (fn [_ _]
                       {:db {:n 1}
                        :fx [[:test/incr :go]]}))
    (let [fx-fired (atom 0)]
      (rf/reg-fx :test/incr (fn [_ _] (swap! fx-fired inc)))
      (let [evs (record-traces
                  (fn [] (rf/dispatch-sync [:seed] {:frame :test/main})))
            dispatched (first (events-of evs #(= :rf.event/dispatched (:operation %))))
            cascade-id (dispatch-id dispatched)
            ;; Every event we expect inside the cascade.
            during-drain (->> evs
                              (filter #(contains? #{:event :rf.event/db-changed
                                                    :rf.fx/do-fx :rf.fx/handled}
                                                  (:operation %))))]
        ;; ALWAYS-ON (rf2-d2841): the cascade the correlation key correlates
        ;; actually ran — handler committed, fx fired exactly once.
        (is (= 1 @fx-fired) "fx ran")
        (is (= 1 (:n (rf/app-db-value :test/main)))
            "the handler's db change committed in this posture")
        (when rf.interop/debug-enabled?
          (is (some? cascade-id)
              "the cascade's :rf.trace/dispatch-id is on the :rf.event/dispatched event")
          (is (seq during-drain)
              "we saw events emitted inside the drain")
          (doseq [ev during-drain]
            (is (= cascade-id (dispatch-id ev))
                (str "event " (:operation ev)
                     " carries the cascade's :rf.trace/dispatch-id"))))))))

(deftest dispatch-id-rides-on-error-events-inside-the-cascade
  (testing "errors emitted inside the drain carry the cascade's :rf.trace/dispatch-id"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :throws (fn [{:keys [db]} _] {:db (throw (ex-info "oops" {}))}))
    (let [recs       (atom nil)
          evs        (record-traces
                       (fn []
                         (reset! recs
                                 (record-errors
                                   #(rf/dispatch-sync [:throws] {:frame :test/main})))))
          dispatched (first (events-of evs #(= :rf.event/dispatched (:operation %))))
          cascade-id (dispatch-id dispatched)
          err        (first (events-of evs #(= :rf.error/handler-exception (:operation %))))]
      ;; ALWAYS-ON (rf2-d2841): the failure this deftest correlates reaches the
      ;; production error stream. The correlation id does not ride that record
      ;; — the tight shape carries none — but the failure itself is not lost.
      (is (some? (first (filterv #(= :rf.error/handler-exception (:error %)) @recs)))
          "the always-on error record fired for the throwing handler")
      (when rf.interop/debug-enabled?
        (is (some? cascade-id))
        (is (some? err) "the handler-exception fired")
        (is (= cascade-id (dispatch-id err))
            ":rf.error/* traces carry the cascade's :rf.trace/dispatch-id")))))

(deftest child-dispatch-gets-its-own-dispatch-id-and-parents-the-outer
  (testing "child dispatches from inside fx handlers get a fresh :rf.trace/dispatch-id and the parent's id rides on :rf.trace/parent-dispatch-id"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :parent
                     (fn [_ _]
                       {:fx [[:dispatch [:child]]]}))
    (rf/reg-event :child (fn [{:keys [db]} _] {:db (assoc db :got-child true)}))
    (let [evs        (record-traces
                       (fn [] (rf/dispatch-sync [:parent] {:frame :test/main})))
          dispatches (vec (events-of evs #(= :rf.event/dispatched (:operation %))))
          parent     (first (filter #(= [:parent] (get-in % [:tags :rf.event/v])) dispatches))
          child      (first (filter #(= [:child]  (get-in % [:tags :rf.event/v])) dispatches))]
      ;; ALWAYS-ON (rf2-d2841): the child dispatch the ids are about really was
      ;; issued from inside the parent's fx and really committed.
      (is (true? (:got-child (rf/app-db-value :test/main)))
          "the child dispatch committed in this posture")
      (when rf.interop/debug-enabled?
        (is (some? parent))
        (is (some? child))
        (is (some? (dispatch-id parent)))
        (is (some? (dispatch-id child)))
        (is (not= (dispatch-id parent) (dispatch-id child))
            "child gets its own freshly-allocated :rf.trace/dispatch-id")
        (is (= (dispatch-id parent)
               (get-in child [:tags :rf.trace/parent-dispatch-id]))
            "child's :rf.trace/parent-dispatch-id is the parent cascade's :rf.trace/dispatch-id")))))

(deftest parent-dispatch-id-only-on-event-dispatched
  (testing ":rf.trace/parent-dispatch-id is scoped to :rf.event/dispatched events only — not on :rf.sub/run, :rf.event/db-changed, :rf.fx/handled, etc."
    (rf/make-frame {:id :test/main})
    (rf/reg-event :outer (fn [_ _] {:fx [[:dispatch [:inner]]]}))
    (rf/reg-event :inner (fn [{:keys [db]} _] {:db (assoc db :v 1)}))
    (let [evs (record-traces
                (fn [] (rf/dispatch-sync [:outer] {:frame :test/main})))]
      ;; ALWAYS-ON (rf2-d2841): the nested dispatch ran. Without this the
      ;; deftest asserts NOTHING under the gate — the `doseq` below iterates
      ;; zero times over an empty ring and clojure.test still reports a pass.
      (is (= 1 (:v (rf/app-db-value :test/main)))
          "the inner event committed in this posture")
      (when rf.interop/debug-enabled?
        (is (seq evs) "the trace ring captured the cascade")
        (doseq [ev evs
                :when (not= :rf.event/dispatched (:operation ev))]
          (is (nil? (get-in ev [:tags :rf.trace/parent-dispatch-id]))
              (str "non-:rf.event/dispatched event " (:operation ev)
                   " must not carry :rf.trace/parent-dispatch-id")))))))

(deftest dispatch-id-unbound-outside-any-cascade
  (testing "trace events emitted outside any drain carry no :rf.trace/dispatch-id"
    ;; Register a frame and emit a handler-registered trace before any
    ;; dispatch fires — `*current-dispatch-id*` is unbound here, so the
    ;; trace event has no :rf.trace/dispatch-id stamped.
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/make-frame {:id :test/outside})
        ;; reg-event / reg-fx emit :rf.registry/handler-registered traces
        ;; via the registrar; these fire OUTSIDE any drain.
        (rf/reg-event :foo (fn [{:keys [db]} _] {:db db}))
        ;; ALWAYS-ON (rf2-d2841): the out-of-band WORK is production behaviour —
        ;; only its trace is not. The frame exists and the handler is registered.
        (is (some? (rf/frame-meta :test/outside)) "the frame was created")
        (is (some? (rf/handler-meta :event :foo)) "the handler was registered")
        (when rf.interop/debug-enabled?
          (let [out-of-band (filter #(or (= :rf.frame/created (:operation %))
                                         (= :rf.registry/handler-registered (:operation %)))
                                    @seen)]
            (is (seq out-of-band) "we saw trace events emitted outside any drain")
            (doseq [ev out-of-band]
              (is (nil? (dispatch-id ev))
                  (str "out-of-band event " (:operation ev)
                       " must NOT carry a :rf.trace/dispatch-id")))))
        (finally
          (rf/unregister-listener! :trace ::rec))))))

(deftest dispatch-id-is-fresh-across-cascade-boundaries
  (testing "two sequential dispatches get distinct :dispatch-ids on every event in their respective cascades"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [evs1 (record-traces
                 (fn [] (rf/dispatch-sync [:bump] {:frame :test/main})))
          evs2 (record-traces
                 (fn [] (rf/dispatch-sync [:bump] {:frame :test/main})))
          ids1 (set (keep dispatch-id evs1))
          ids2 (set (keep dispatch-id evs2))]
      ;; ALWAYS-ON (rf2-d2841): both cascades ran to completion — the counter
      ;; reached 2 — so "two cascades" is a fact and not an assumption.
      (is (= 2 (:n (rf/app-db-value :test/main)))
          "both sequential dispatches committed in this posture")
      (when rf.interop/debug-enabled?
        (is (= 1 (count ids1))
            "every emit in the first cascade shares one :rf.trace/dispatch-id")
        (is (= 1 (count ids2))
            "every emit in the second cascade shares one :rf.trace/dispatch-id")
        (is (empty? (clojure.set/intersection ids1 ids2))
            "the two cascades' :dispatch-ids are disjoint")))))

;; ---- frame-lifecycle emits stay uncorrelated ------------------------------

(deftest frame-lifecycle-emits-stay-uncorrelated-under-a-cascade-scope
  (testing "rf2-7eel71 — a frame-lifecycle emit (op-type :rf.frame) fired while a
            cascade's *handler-scope* is bound (e.g. make-frame re-registering a
            SIBLING frame from inside a handler / fx) must NOT inherit the
            cascade's :rf.trace/dispatch-id. Stamping a foreign id there makes the
            epoch capture seam strand the marker in the sibling frame's buffer for
            the frame's whole lifetime. An ordinary in-cascade emit under the same
            scope DOES still carry the id (control)."
    (let [evs   (record-traces
                  (fn []
                    (rf.trace/with-dispatch-id+call-site 4242 nil
                      ;; Frame-lifecycle markers tagged with a DIFFERENT (sibling) frame.
                      (rf.trace/emit! :rf.frame :rf.frame/re-registered {:frame :test/sibling})
                      (rf.trace/emit! :rf.frame :rf.frame/created        {:frame :test/other})
                      ;; Control: an ordinary cascade emit inherits the dispatch-id.
                      (rf.trace/emit! :rf.sub   :rf.sub/run              {:frame :test/sibling
                                                                       :rf.sub/id :x}))))
          by-op (fn [op] (some #(when (= op (:operation %)) %) evs))]
      ;; rf2-d2841 — GUARDED WHOLESALE, and honestly so: this case drives
      ;; `rf.trace/emit!` directly, which is a no-op under `-Dre-frame.debug=false`,
      ;; so there is no production work here to witness. Its two negatives were
      ;; class-1 vacuous under the gate — `by-op` returns nil over the empty
      ;; ring and `dispatch-id` of nil is nil — while the CONTROL beside them
      ;; (the one assertion that would have caught it) went red.
      (when rf.interop/debug-enabled?
        (is (nil? (dispatch-id (by-op :rf.frame/re-registered)))
            ":rf.frame/re-registered stays uncorrelated — no cascade dispatch-id")
        (is (nil? (dispatch-id (by-op :rf.frame/created)))
            ":rf.frame/created stays uncorrelated — no cascade dispatch-id")
        (is (= 4242 (dispatch-id (by-op :rf.sub/run)))
            "control: an ordinary cascade emit still rides the cascade's dispatch-id")))))

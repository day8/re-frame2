(ns re-frame.events-test
  "Per rf2-bbea — `reg-event-*` warns when `:interceptors` appears inside
  the metadata-map (where it is silently ignored). The interceptor chain
  belongs in the third positional slot, not the metadata map.

  The warning is delivered via the trace stream
  (`:rf.warning/interceptors-in-metadata-map`, per Conventions §Reserved
  namespaces — `:rf.warning/*`). Hot-reload tools / 10x consume the
  warning; this test asserts it fires on the wrong shape and stays
  silent on the right shape."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces!
  "Attach a recording listener and return its atom. Forgetting to remove
  the listener doesn't matter — the fixture clears all listeners between
  deftests."
  [listener-id]
  (let [a (atom [])]
    (rf/register-trace-cb! listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warning-events
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(def ^:private noop-icpt
  ;; A no-op interceptor; just enough to populate a positional vector.
  {:id     :test/noop
   :before identity
   :after  identity})

;; ---- tests ----------------------------------------------------------------

(deftest interceptors-in-metadata-map-warns
  (testing "reg-event-db with :interceptors inside the metadata-map fires :rf.warning/interceptors-in-metadata-map"
    (let [recorded (record-traces! ::db-warn)]
      (rf/reg-event-db :test.bbea/db-bad
        {:doc "A wrongly-shaped registration." :interceptors [noop-icpt]}
        (fn [db _] db))
      (let [warns (warning-events recorded :rf.warning/interceptors-in-metadata-map)]
        (is (= 1 (count warns)) (str "expected exactly one warning, got " (count warns)))
        (let [t (:tags (first warns))]
          (is (= "reg-event-db" (:reg-fn t)))
          (is (= :test.bbea/db-bad (:id t)))
          (is (= [:interceptors] (:offending-keys t)))
          (is (string? (:reason t)))
          (is (re-find #"silently ignored" (:reason t))))
        (is (= :ignored (:recovery (first warns)))))))

  (testing "reg-event-fx with :interceptors inside the metadata-map fires the warning"
    (let [recorded (record-traces! ::fx-warn)]
      (rf/reg-event-fx :test.bbea/fx-bad
        {:doc "Wrong shape." :interceptors [noop-icpt]}
        (fn [_ _] {:db {}}))
      (let [warns (warning-events recorded :rf.warning/interceptors-in-metadata-map)]
        (is (= 1 (count warns)))
        (is (= "reg-event-fx" (:reg-fn (:tags (first warns))))))))

  (testing "reg-event-ctx with :interceptors inside the metadata-map fires the warning"
    (let [recorded (record-traces! ::ctx-warn)]
      (rf/reg-event-ctx :test.bbea/ctx-bad
        {:doc "Wrong shape." :interceptors [noop-icpt]}
        (fn [ctx] ctx))
      (let [warns (warning-events recorded :rf.warning/interceptors-in-metadata-map)]
        (is (= 1 (count warns)))
        (is (= "reg-event-ctx" (:reg-fn (:tags (first warns)))))))))

(deftest correct-positional-form-stays-silent
  (testing "reg-event-db with interceptors in the positional slot does NOT warn"
    (let [recorded (record-traces! ::db-quiet)]
      (rf/reg-event-db :test.bbea/db-good
        [noop-icpt]
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map)))))

  (testing "reg-event-db with metadata-map (no :interceptors) AND positional interceptors does NOT warn"
    (let [recorded (record-traces! ::db-good-2)]
      (rf/reg-event-db :test.bbea/db-good-2
        {:doc "Right shape — metadata for reflection, interceptors positional."}
        [noop-icpt]
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map)))))

  (testing "reg-event-db with metadata-map alone (no interceptors anywhere) does NOT warn"
    (let [recorded (record-traces! ::db-good-3)]
      (rf/reg-event-db :test.bbea/db-good-3
        {:doc "Plain metadata-only registration."}
        (fn [db _] db))
      (is (empty? (warning-events recorded :rf.warning/interceptors-in-metadata-map)))))

  (testing "the wrongly-shaped form ALSO loses its interceptor chain (positional slot remains empty)"
    ;; Confirm the bug the warning describes: the silently-dropped
    ;; interceptors do NOT end up in the registered :interceptors. The
    ;; registered chain holds only the runtime's :rf/db-handler wrapper.
    (let [_recorded (record-traces! ::confirm-drop)
          marker    {:id :test.bbea/marker :before identity :after identity}]
      (rf/reg-event-db :test.bbea/dropped
        {:interceptors [marker]}
        (fn [db _] db))
      (let [{:keys [interceptors]} (rf/handler-meta :event :test.bbea/dropped)
            ids (set (map :id interceptors))]
        (is (not (contains? ids :test.bbea/marker))
            "the metadata-map :interceptors entry is dropped (this confirms the bug rf2-bbea documents)")))))

;; ---- clear-event round-trip (rf2-6z20) -----------------------------------
;;
;; Per Spec 002 / API.md row §Lifecycle: `rf/clear-event` is the public
;; alias of `events/clear-event` (re-exported at `core.cljc:867`), used
;; by hot-reload tooling and per-test isolation fixtures. Two arities:
;;
;;   (rf/clear-event)        ;; clear every registered :event
;;   (rf/clear-event :id)    ;; clear one event by id
;;
;; Pre-rf2-6z20 neither arity was touched in any test. A regression
;; that left the registry slot populated would only surface through
;; integration symptoms (a stale handler still firing).

(deftest clear-event-removes-a-single-handler
  (testing "(rf/clear-event id) removes the registered :event slot;
            a subsequent dispatch traces :rf.error/no-such-handler"
    (rf/reg-event-db :test.6z20/foo (fn [db _] (assoc db :touched? true)))
    ;; Pre-clear: reachable via lookup AND dispatch.
    (is (some? (registrar/lookup :event :test.6z20/foo))
        "the event handler is reachable via registrar/lookup pre-clear")
    (rf/dispatch-sync [:test.6z20/foo])
    (is (true? (:touched? (rf/get-frame-db :rf/default)))
        "the handler ran when registered")

    ;; Clear.
    (rf/clear-event :test.6z20/foo)

    ;; Post-clear: gone from the registry, dispatch traces no-such-handler.
    (is (nil? (registrar/lookup :event :test.6z20/foo))
        "registry slot is gone after clear-event")
    (let [recorded (record-traces! ::post-clear)]
      (rf/dispatch-sync [:test.6z20/foo])
      (let [errs (filterv #(= :rf.error/no-such-handler (:operation %))
                          @recorded)]
        (is (= 1 (count errs))
            "a subsequent dispatch traces :rf.error/no-such-handler")
        (is (= :test.6z20/foo (-> errs first :tags :event-id))
            ":event-id carries the cleared handler's id")))))

(deftest clear-event-no-arg-clears-every-event
  (testing "(rf/clear-event) with no args clears every registered :event id"
    ;; Per events.cljc:227, the no-arg form is documented:
    ;;   ([] (registrar/clear-kind! :event))
    ;; This tests confirms the contract.
    (rf/reg-event-db :test.6z20/a (fn [db _] db))
    (rf/reg-event-db :test.6z20/b (fn [db _] db))
    (rf/reg-event-fx :test.6z20/c (fn [_ _] {}))
    (is (some? (registrar/lookup :event :test.6z20/a)))
    (is (some? (registrar/lookup :event :test.6z20/b)))
    (is (some? (registrar/lookup :event :test.6z20/c)))

    (rf/clear-event)

    (is (nil? (registrar/lookup :event :test.6z20/a))
        "all :event slots cleared by no-arg form")
    (is (nil? (registrar/lookup :event :test.6z20/b)))
    (is (nil? (registrar/lookup :event :test.6z20/c)))))

(deftest clear-event-leaves-other-kinds-untouched
  (testing "clear-event only touches :event; :sub, :fx, :cofx are preserved"
    ;; Defence-in-depth: confirm clear-event is narrow.
    (rf/reg-event-db :test.6z20/ev (fn [db _] db))
    (rf/reg-sub :test.6z20/sub (fn [_ _] :stub))
    (rf/reg-fx :test.6z20/fx (fn [_ _] nil))
    (rf/reg-cofx :test.6z20/cofx (fn [ctx] ctx))
    (rf/clear-event)
    (is (nil? (registrar/lookup :event :test.6z20/ev))
        ":event was cleared")
    (is (some? (registrar/lookup :sub :test.6z20/sub))
        ":sub kind is untouched")
    (is (some? (registrar/lookup :fx :test.6z20/fx))
        ":fx kind is untouched")
    (is (some? (registrar/lookup :cofx :test.6z20/cofx))
        ":cofx kind is untouched")))

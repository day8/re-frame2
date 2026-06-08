(ns re-frame.event-context-partition-test
  "EP-0001 (rf2-bvwoi4) — event-context partition keys + effect-map widening
  + the runtime-effect dev diagnostic.

  Pins the event-CONTEXT contract introduced by bead 4 (the partitioned
  COMMIT is bead 5 / rf2-adwcv6):

    1. `:db` coeffect KEEPS meaning app-db (NOT the whole frame).
    2. `:rf.db/runtime` + `:rf.frame/id` are present in the event context
       (per Spec 002 §Event context threads both partitions). `:rf.db/runtime`
       reads `nil` until the physical partition lands in bead 5.
    3. The closed effect-map is widened to `#{:db :rf.db/runtime :fx}`
       (per Spec-Schemas §:rf/effect-map): a `:rf.db/runtime` effect is NOT a
       shape error, while a foreign top-level key still is.
    4. `:rf.warning/app-handler-runtime-effect` fires when an ORDINARY app
       handler returns a `:rf.db/runtime` effect, and DOES NOT fire for a
       framework-authority handler (`:rf/machine? true`) — reserved BY
       CONVENTION, not a security boundary (Mike ruling #4). The effect is
       applied either way (the diagnostic is a warning, not a gate)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (when-let [clear-schemas! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warning-events [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(defn- error-events [recorded operation]
  (filterv (fn [ev]
             (and (= :error (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

;; ===========================================================================
;; 1 + 2 — event-context partition keys
;; ===========================================================================

(deftest db-coeffect-is-app-db-not-whole-frame
  (testing ":db coeffect equals the app-db partition value, not the frame-state"
    (rf/reg-frame :ctx/db-is-app-db {:doc "ctx"})
    (rf/reg-event-db :ctx/seed (fn [_ [_ db]] db))
    (rf/dispatch-sync [:ctx/seed {:user/id 42}] {:frame :ctx/db-is-app-db})
    (let [captured (atom nil)]
      (rf/reg-event-ctx :ctx/capture
        (fn [ctx] (reset! captured (:coeffects ctx)) ctx))
      (rf/dispatch-sync [:ctx/capture] {:frame :ctx/db-is-app-db})
      (let [cofx @captured]
        (is (= {:user/id 42} (:db cofx))
            ":db is the plain app-db map")
        (is (= (rf/app-db-value :ctx/db-is-app-db) (:db cofx))
            ":db equals app-db-value (NOT the {:rf.db/app … :rf.db/runtime …} frame-state)")
        (is (not (contains? (:db cofx) :rf.db/app))
            ":db is NOT the frame-state projection")
        (is (not (contains? (:db cofx) :rf.db/runtime))
            ":db carries no runtime partition")))))

(deftest runtime-and-frame-id-coeffects-present
  (testing ":rf.db/runtime and :rf.frame/id are threaded into the event context"
    (rf/reg-frame :ctx/partitions {:doc "ctx"})
    (let [captured (atom nil)]
      (rf/reg-event-ctx :ctx/capture
        (fn [ctx] (reset! captured (:coeffects ctx)) ctx))
      (rf/dispatch-sync [:ctx/capture] {:frame :ctx/partitions})
      (let [cofx @captured]
        (is (contains? cofx :rf.db/runtime)
            ":rf.db/runtime coeffect is present (runtime-db partition)")
        (is (= {} (:rf.db/runtime cofx))
            ":rf.db/runtime reads the real (fresh {}) runtime-db partition (rf2-adwcv6, bead 5)")
        (is (= (rf/runtime-db-value :ctx/partitions) (:rf.db/runtime cofx))
            ":rf.db/runtime coeffect equals runtime-db-value")
        (is (= :ctx/partitions (:rf.frame/id cofx))
            ":rf.frame/id is the running frame's id (runtime-context spelling)")
        ;; The public `:frame` opt remains unchanged + distinct.
        (is (= :ctx/partitions (:frame cofx))
            ":frame coeffect still carries the resolved frame")))))

;; ===========================================================================
;; 3 — effect-map widening (closed set #{:db :rf.db/runtime :fx})
;; ===========================================================================

(deftest runtime-db-effect-is-not-a-shape-error
  (testing "a framework-authority handler returning :rf.db/runtime emits no :rf.error/effect-map-shape"
    (rf/reg-frame :ctx/runtime-fx {:doc "ctx"})
    (let [recorded (record-traces! ::no-shape-err)]
      ;; :rf/machine? marks framework-write authority (machine registrar mints
      ;; framework-authority handlers — Spec 002 §Write authority).
      (rf/reg-event-fx :ctx/fw-runtime
        {:doc "framework-authority runtime write" :rf/machine? true}
        (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {}} :fx []}))
      (rf/dispatch-sync [:ctx/fw-runtime] {:frame :ctx/runtime-fx})
      (is (empty? (error-events recorded :rf.error/effect-map-shape))
          ":rf.db/runtime is inside the widened closed set — no shape error"))))

(deftest foreign-top-level-key-still-a-shape-error
  (testing "a foreign top-level key (legacy :http) is still policed after the widening"
    (rf/reg-frame :ctx/foreign-fx {:doc "ctx"})
    (let [recorded (record-traces! ::foreign-err)]
      (rf/reg-event-fx :ctx/foreign
        (fn [_ _] {:db {:ok? true} :http {:url "/api"}}))
      (rf/dispatch-sync [:ctx/foreign] {:frame :ctx/foreign-fx})
      (let [errs (error-events recorded :rf.error/effect-map-shape)]
        (is (= 1 (count errs))
            "exactly one shape error for the foreign :http key")
        (is (= :http (:offending-key (:tags (first errs))))
            "the offending key is :http")
        (is (= :logged-and-skipped (:recovery (first errs))))
        (is (true? (:ok? (rf/app-db-value :ctx/foreign-fx)))
            "the legal :db still committed; only :http was dropped")))))

;; ===========================================================================
;; 4 — :rf.warning/app-handler-runtime-effect diagnostic
;; ===========================================================================

(deftest app-handler-runtime-effect-warns
  (testing "an ORDINARY app handler returning :rf.db/runtime fires the dev diagnostic"
    (rf/reg-frame :ctx/app-runtime {:doc "ctx"})
    (let [recorded (record-traces! ::app-warn)]
      (rf/reg-event-fx :ctx/app-emits-runtime
        (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {}}}))
      (rf/dispatch-sync [:ctx/app-emits-runtime] {:frame :ctx/app-runtime})
      (let [warns (warning-events recorded :rf.warning/app-handler-runtime-effect)]
        (is (= 1 (count warns))
            "exactly one :rf.warning/app-handler-runtime-effect for the non-framework writer")
        (let [t (:tags (first warns))]
          (is (= :ctx/app-emits-runtime (:rf.trace/event-id t)))
          (is (= [:ctx/app-emits-runtime] (:rf.event/v t)))
          (is (= :ctx/app-runtime (:frame t))
              ":frame tag is the running frame (read from the :rf.frame/id coeffect)")
          (is (string? (:reason t)))
          (is (re-find #"rf\.db/runtime" (:reason t))))
        (is (= :warned (:recovery (first warns)))
            "recovery is :warned — convention, not enforcement")))))

(deftest framework-authority-runtime-effect-does-not-warn
  (testing "a framework-authority handler (:rf/machine? true) does NOT fire the diagnostic"
    (rf/reg-frame :ctx/fw-authority {:doc "ctx"})
    (let [recorded (record-traces! ::fw-quiet)]
      (rf/reg-event-fx :ctx/fw-emits-runtime
        {:doc "framework-authority" :rf/machine? true}
        (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {}}}))
      (rf/dispatch-sync [:ctx/fw-emits-runtime] {:frame :ctx/fw-authority})
      (is (empty? (warning-events recorded :rf.warning/app-handler-runtime-effect))
          "the framework-authority path is in-bounds — no diagnostic"))))

(deftest plain-db-fx-handler-does-not-warn
  (testing "an ordinary handler that does NOT return :rf.db/runtime stays silent"
    (rf/reg-frame :ctx/plain {:doc "ctx"})
    (let [recorded (record-traces! ::plain-quiet)]
      (rf/reg-event-fx :ctx/plain-db
        (fn [{:keys [db]} _] {:db (assoc db :touched? true) :fx []}))
      (rf/dispatch-sync [:ctx/plain-db] {:frame :ctx/plain})
      (is (empty? (warning-events recorded :rf.warning/app-handler-runtime-effect))
          "no :rf.db/runtime effect ⇒ no diagnostic")
      (is (true? (:touched? (rf/app-db-value :ctx/plain)))
          "the :db effect committed normally"))))

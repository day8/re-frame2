(ns re-frame.frame-handle-test
  "Design-pinning tests for the frame-affordance redesign (rf2-kkut0.1):
  `frame-handle` (the keystone OPERATION BUNDLE), `frame-bound-fn` /
  `frame-bound-fn*`, `current-frame-id`, `app-db-value`, and the absence of
  the removed public names (`bound-fn`, `dispatcher`, `subscriber`,
  `get-frame-db`, `current-frame`). Per Spec 002 §frame-handle and
  `re-frame.core.cljc`.

  `frame-handle` exists to support async callbacks where the dynamic-var
  frame binding has already unwound: it captures the frame at CREATION
  time and its `:dispatch` / `:dispatch-sync` / `:subscribe` ops always
  target THAT frame — not whatever the caller's current frame is when an
  op later fires.

  These JVM tests use `with-frame :A` to set the dynamic var, capture the
  handle, then EXIT the with-frame scope before invoking its ops —
  proving the captured frame survives the unwind."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- shape: a handle is an operation bundle ------------------------------

(deftest frame-handle-returns-operation-bundle
  (testing "(frame-handle frame-id) returns {:frame :dispatch :dispatch-sync :subscribe}"
    (rf/reg-frame :fh/shape {:doc "shape probe"})
    (let [h (rf/frame-handle :fh/shape)]
      (is (= :fh/shape (:frame h))
          ":frame is the captured frame id")
      (is (fn? (:dispatch h))      ":dispatch is a fn")
      (is (fn? (:dispatch-sync h)) ":dispatch-sync is a fn")
      (is (fn? (:subscribe h))     ":subscribe is a fn"))))

;; ---- captures at creation, not op-call time ------------------------------

(deftest frame-handle-captures-frame-at-creation
  (testing "(frame-handle) captures the active frame at CREATION; the bundle's
            :dispatch routes to THAT frame after the with-frame scope unwinds"
    (rf/reg-frame :fh/A {:doc "frame A — the capture target"})
    (rf/reg-event-db :fh/inc (fn [db _] (update db :n (fnil inc 0))))
    ;; Capture inside :fh/A; fire OUTSIDE.
    (let [{:keys [dispatch]} (rf/with-frame :fh/A (rf/frame-handle))]
      ;; The dynamic-var binding has unwound. The captured op must still
      ;; route to :fh/A.
      (dispatch [:fh/inc])
      (dispatch [:fh/inc])
      (test-support/poll-until #(= 2 (:n (rf/app-db-value :fh/A)))
                               {:label "captured handle drains to :fh/A"})
      (is (= 2 (:n (rf/app-db-value :fh/A)))
          "the captured handle routed events to :fh/A after the scope unwound")
      (is (nil? (:n (rf/app-db-value :rf/default)))
          ":rf/default's app-db was NOT touched — capture is frame-faithful"))))

(deftest frame-handle-subscribe-captures-frame
  (testing "the bundle's :subscribe op resolves against the captured frame
            after the with-frame scope unwinds"
    (rf/reg-frame :fh/B {:doc "frame B — the subscribe target"})
    (rf/reg-event-db :fh/seed (fn [_ [_ v]] {:value v}))
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/dispatch-sync [:fh/seed :B-value] {:frame :fh/B})
    (rf/dispatch-sync [:fh/seed :default-value])
    (let [{:keys [subscribe]} (rf/with-frame :fh/B (rf/frame-handle))
          reaction            (subscribe [:fh/value])]
      (is (= :B-value @reaction)
          "captured :subscribe resolves against :fh/B's app-db, not :rf/default"))))

;; ---- per-call :frame CANNOT override the captured frame ------------------

(deftest frame-handle-locked-frame-cannot-be-overridden
  (testing "a per-call :frame in dispatch opts MUST NOT override the captured
            frame — the handle is LOCKED to one frame"
    (rf/reg-frame :fh/locked {:doc "the locked target"})
    (rf/reg-frame :fh/other  {:doc "the would-be override"})
    (rf/reg-event-db :fh/touch (fn [db _] (assoc db :touched? true)))
    (let [{:keys [dispatch]} (rf/frame-handle :fh/locked)]
      ;; Attempt to redirect to :fh/other via a per-call :frame opt.
      (dispatch [:fh/touch] {:frame :fh/other})
      (test-support/poll-until #(:touched? (rf/app-db-value :fh/locked))
                               {:label "locked handle drains to :fh/locked"})
      (is (true? (:touched? (rf/app-db-value :fh/locked)))
          "the event landed in the CAPTURED frame :fh/locked")
      (is (nil? (:touched? (rf/app-db-value :fh/other)))
          "the per-call :frame :fh/other was IGNORED — the handle is locked"))))

;; ---- contract: (frame-handle) outside any with-frame captures :rf/default --

(deftest frame-handle-outside-with-frame-defaults
  (testing "(frame-handle) with no active with-frame captures :rf/default"
    (rf/reg-event-db :fh/default-touch (fn [db _] (assoc db :touched? true)))
    (let [h (rf/frame-handle)]
      (is (= :rf/default (:frame h))
          "the captured :frame is :rf/default outside any with-frame")
      ((:dispatch h) [:fh/default-touch])
      (test-support/poll-until #(:touched? (rf/app-db-value :rf/default))
                               {:label "default handle drains to :rf/default"})
      (is (true? (:touched? (rf/app-db-value :rf/default)))
          "the handle outside any with-frame routes to :rf/default"))))

;; ---- frame-bound-fn (macro) + frame-bound-fn* (fn, both arities) ---------

(deftest frame-bound-fn-macro-captures-frame
  (testing "(frame-bound-fn [args] body) captures the current frame and
            re-establishes it inside the body after the scope unwinds"
    (rf/reg-frame :fbf/A {:doc "macro capture target"})
    (rf/reg-event-db :fbf/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [cb (rf/with-frame :fbf/A
               (rf/frame-bound-fn [] (rf/dispatch [:fbf/inc])))]
      (is (nil? frame/*current-frame*) "the with-frame scope has unwound")
      (cb)
      (test-support/poll-until #(= 1 (:n (rf/app-db-value :fbf/A)))
                               {:label "frame-bound-fn macro drains to :fbf/A"})
      (is (= 1 (:n (rf/app-db-value :fbf/A)))
          "the macro re-established :fbf/A inside the body"))))

(deftest frame-bound-fn*-one-arity-captures-frame
  (testing "(frame-bound-fn* f) captures the current frame at wrap time"
    (rf/reg-frame :fbf/B {:doc "*-1-arity target"})
    (rf/reg-event-db :fbf/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [cb (rf/with-frame :fbf/B
               (rf/frame-bound-fn* (fn [] (rf/dispatch [:fbf/inc]))))]
      (cb)
      (test-support/poll-until #(= 1 (:n (rf/app-db-value :fbf/B)))
                               {:label "frame-bound-fn* 1-arity drains to :fbf/B"})
      (is (= 1 (:n (rf/app-db-value :fbf/B)))
          "frame-bound-fn* captured :fbf/B at wrap time"))))

(deftest frame-bound-fn*-two-arity-explicit-frame
  (testing "(frame-bound-fn* frame-id f) binds an explicit frame, no
            surrounding with-frame needed"
    (rf/reg-frame :fbf/C {:doc "*-2-arity explicit target"})
    (rf/reg-event-db :fbf/inc (fn [db _] (update db :n (fnil inc 0))))
    (let [cb (rf/frame-bound-fn* :fbf/C (fn [] (rf/dispatch [:fbf/inc])))]
      (is (nil? frame/*current-frame*) "no with-frame scope was ever entered")
      (cb)
      (test-support/poll-until #(= 1 (:n (rf/app-db-value :fbf/C)))
                               {:label "frame-bound-fn* 2-arity drains to :fbf/C"})
      (is (= 1 (:n (rf/app-db-value :fbf/C)))
          "the explicit frame-id was re-established inside the body"))))

;; ---- renamed reads -------------------------------------------------------

(deftest current-frame-id-returns-keyword
  (testing "(current-frame-id) returns a keyword frame id"
    (rf/reg-frame :cfi/probe {:doc "probe"})
    (is (= :rf/default (rf/current-frame-id))
        "outside any with-frame: :rf/default")
    (is (keyword? (rf/current-frame-id)) "always a keyword")
    (is (= :cfi/probe (rf/with-frame :cfi/probe (rf/current-frame-id)))
        "inside with-frame: the bound id")))

(deftest app-db-value-returns-a-value
  (testing "(app-db-value frame-id) returns the app-db VALUE (a plain map), not a container"
    (rf/reg-frame :fdb/probe {:doc "probe"})
    (rf/reg-event-db :fdb/seed (fn [_ _] {:k :v}))
    (rf/dispatch-sync [:fdb/seed] {:frame :fdb/probe})
    (let [db (rf/app-db-value :fdb/probe)]
      (is (map? db) "app-db-value returns a plain map value")
      (is (= :v (:k db)) "the value reflects app-db state")
      (is (not (instance? clojure.lang.IDeref db))
          "it is a VALUE — not a deref-able container"))
    (is (nil? (rf/app-db-value :fdb/never-registered))
        "nil for an unregistered frame")))

;; ---- removed public names are absent -------------------------------------

(deftest removed-public-names-are-absent
  (testing "the deleted public names are NOT interned in re-frame.core"
    ;; `ns-interns` (NOT `ns-resolve`) — `ns-resolve` would follow the
    ;; clojure.core referral for `bound-fn` (we dropped the
    ;; `:refer-clojure :exclude [bound-fn]`, so clojure.core/bound-fn is
    ;; visible again). The contract is that re-frame.core no longer
    ;; INTERNS its own Var under these names.
    (let [interned (ns-interns 're-frame.core)]
      (doseq [sym '[bound-fn dispatcher subscriber get-frame-db current-frame]]
        (is (nil? (get interned sym))
            (str "re-frame.core/" sym " must be removed (DELETE, not deprecate)"))))))

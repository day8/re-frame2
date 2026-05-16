(ns realworld.test-helpers
  "Shared test helpers for the realworld example fixtures.

   Per Spec 014 §Testing, the framework ships canned-stub fxs
   (`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`)
   that synthesise the canonical reply shape. The realworld fixtures use
   per-test wrappers that delegate to these stubs while supplying the
   test-specific `:value` (success) or `:kind` + `:tags` (failure)."
  (:require [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; rf2-cdmle — these helpers resolve
            ;; :rf.http/managed-canned-success/failure via registrar lookup.
            ;; Per the gate change, those fx ids register from
            ;; re-frame.http-test-support, NOT re-frame.http-managed.
            [re-frame.http-test-support]))

(defn reg-canned-success!
  "Register an fx-id that delegates to :rf.http/managed-canned-success
   with a fixed `:value`. Use as a per-test stub via :fx-overrides."
  [fx-id value]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
        (stub frame-ctx (assoc args :value value))))))

(defn reg-canned-success-by-url!
  "Register an fx-id that delegates to :rf.http/managed-canned-success,
   choosing `:value` per the request URL (and optionally method). `f`
   receives the URL string (1-arity) or [method url] (2-arity from a
   3-arity f); always returns the synthesised `:value` payload."
  [fx-id f]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub   (registrar/handler :fx :rf.http/managed-canned-success)
            req    (:request args)
            method (or (:method req) :get)
            url    (:url req)
            arity  (try
                     (.-length f)
                     (catch :default _ 1))
            value  (if (>= arity 2)
                     (f method url)
                     (f url))]
        (stub frame-ctx (assoc args :value value))))))

(defn reg-canned-failure!
  "Register an fx-id that delegates to :rf.http/managed-canned-failure
   with a fixed `:kind` and `:tags` failure category per Spec 014."
  [fx-id kind tags]
  (rf/reg-fx fx-id
    {:platforms #{:client :server}}
    (fn [frame-ctx args]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
        (stub frame-ctx (assoc args :kind kind :tags tags))))))

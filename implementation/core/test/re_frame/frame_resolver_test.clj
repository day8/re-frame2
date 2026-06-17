(ns re-frame.frame-resolver-test
  "EP-0002 chain bead 1 (rf2-piwhsw) — central frame resolver, the carried
  invariant. Per Spec 002 §Frame target resolution — the carried invariant
  and §Resolver surface.

  The resolver separates READING absence from REQUIRING a frame:

    - `frame/current-frame` / `frame/resolve-current-frame` are readers —
      they return the scope frame or nil; they NEVER synthesise `:rf/default`.
    - `frame/require-current-frame!` is the requiring primitive — it returns
      the carried stamp or raises/emits `:rf.error/no-frame-context`.

  And `init!` no longer creates `:rf/default` (the runtime never synthesises
  a default frame).

  This suite runs cold (no shared reset-runtime fixture that pins
  `*current-frame*`) so 'outside any scope' is genuinely outside any scope —
  the whole point of the contract."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixture --------------------------------------------------------------
;; Cold-start each test: install the plain-atom adapter (needed to allocate
;; frame containers) but do NOT register or pin any frame. `*current-frame*`
;; is unbound. This is the genuine no-scope baseline the contract targets.

(defn cold-start [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (adapter/dispose-adapter!)
  (adapter/reset-lifecycle-state-for-tests!)
  (trace/clear-listeners!)
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn)
  (adapter/dispose-adapter!)
  (adapter/reset-lifecycle-state-for-tests!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (error-emit/clear-error-listeners!))

(use-fixtures :each cold-start)

;; ---- readers return nil outside any scope ---------------------------------

(deftest current-frame-returns-nil-outside-scope
  (testing "current-frame is nil with no *current-frame* binding — no :rf/default floor"
    (is (nil? (frame/current-frame))
        "current-frame returns nil outside any with-frame / scope"))
  (testing "current-frame returns the dynamic-var scope frame when bound"
    (binding [frame/*current-frame* :app]
      (is (= :app (frame/current-frame))
          "current-frame reads *current-frame* when a scope is established"))))

(deftest resolve-current-frame-returns-nil-outside-scope
  (testing "resolve-current-frame is nil with no scope — no :rf/default floor"
    (is (nil? (frame/resolve-current-frame))
        "resolve-current-frame returns nil outside any scope"))
  (testing "resolve-current-frame returns the dynamic-var scope frame when bound"
    (binding [frame/*current-frame* :app]
      (is (= :app (frame/resolve-current-frame))
          "resolve-current-frame reads the dynamic-var tier when bound"))))

;; ---- require-current-frame! — return stamp or raise -----------------------

(deftest require-current-frame-returns-the-carried-stamp
  (testing "require-current-frame! returns the scope frame when one is established"
    (binding [frame/*current-frame* :app]
      (is (= :app (frame/require-current-frame! :dispatch))
          "the carried stamp is returned unchanged — NO registry lookup, no repair"))))

(deftest require-current-frame-returns-stamp-even-when-frame-unregistered
  (testing "require-current-frame! does NOT consult the frame registry — a bound but unregistered stamp is still returned"
    ;; The contract: require-current-frame! reads the stamp; absence (no
    ;; stamp) is its only error. A bad/unregistered explicit target is a
    ;; DIFFERENT category (:rf.error/frame-destroyed at the registry-lookup
    ;; site), so this helper must NOT pre-empt it with a lookup.
    (binding [frame/*current-frame* :never-registered]
      (is (= :never-registered (frame/require-current-frame! :dispatch))
          "a carried stamp is returned without a registry lookup — no frame-destroyed mis-report"))))

(deftest require-current-frame-raises-no-frame-context-outside-scope
  (testing "require-current-frame! with no carried stamp raises :rf.error/no-frame-context"
    (let [thrown (try
                   (frame/require-current-frame! :dispatch {:where 're-frame.router/dispatch!
                                                            :event-id :todo/add})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "require-current-frame! throws outside any scope")
      (let [data (ex-data thrown)]
        (is (= :rf.error/no-frame-context (:rf.error/id data))
            "ex-data carries the canonical :rf.error/id discriminator")
        (is (= :dispatch (:operation data))
            "ex-data carries the :operation")
        (is (= 're-frame.router/dispatch! (:where data))
            "ex-data carries the caller-supplied :where")
        (is (= :todo/add (:event-id data))
            "ex-data carries the caller-supplied :event-id")
        (is (= :supply-frame (:recovery data))
            "ex-data carries :recovery :supply-frame")))))

(deftest no-frame-context-rides-the-always-on-error-axis
  (testing ":rf.error/no-frame-context fans out through the production-survivable error-emit listener registry (axis 1)"
    (let [records (atom [])]
      (error-emit/register-error-listener! ::probe (fn [r] (swap! records conj r)))
      (try
        (try (frame/require-current-frame! :subscribe) (catch clojure.lang.ExceptionInfo _ nil))
        (finally (error-emit/unregister-error-listener! ::probe)))
      (let [no-frame (filterv #(= :rf.error/no-frame-context (:error %)) @records)]
        (is (= 1 (count no-frame))
            "exactly one :rf.error/no-frame-context record reached the always-on listener")
        (let [r (first no-frame)]
          (is (nil? (:frame r))
              "the record carries no frame — absence is the whole point")
          (is (= :rf.error/no-frame-context (:error r))
              "the record's :error is the canonical category"))))))

(deftest no-frame-context-emitted-before-any-registry-lookup
  (testing "the no-frame error is the ABSENT-target category, distinct from the bad-target :rf.error/frame-destroyed"
    ;; Even though no :rf/default frame exists in this cold suite, the
    ;; absence path must report no-frame-context, never frame-destroyed —
    ;; the error is emitted BEFORE any frame-registry lookup.
    (let [thrown (try (frame/require-current-frame! :dispatch) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/no-frame-context (:rf.error/id (ex-data thrown)))
          "absent target → :rf.error/no-frame-context, never :rf.error/frame-destroyed"))))

;; ---- init! does not create :rf/default ------------------------------------

(deftest init-does-not-create-default-frame
  (testing "init! installs the adapter but creates NO :rf/default frame (the runtime never synthesises a default)"
    ;; The cold-start fixture already called (rf/init! plain-atom/adapter).
    (is (some? (adapter/current-adapter))
        "precondition: init! installed the adapter")
    (is (nil? (frame/frame :rf/default))
        "init! does NOT register a :rf/default frame")
    (is (empty? @frame/frames)
        "no frames at all are registered by init!")))

(deftest default-frame-remains-a-legal-explicit-id
  (testing ":rf/default is an ordinary id a program may register EXPLICITLY"
    (is (nil? (frame/frame :rf/default)) "precondition: not present")
    (rf/reg-frame :rf/default {:doc "The app frame for this program."})
    (is (some? (frame/frame :rf/default))
        ":rf/default registers like any ordinary frame id when chosen explicitly")
    ;; And once explicitly in scope, ambient resolution returns it — an
    ;; honest scope, not a synthesised floor.
    (binding [frame/*current-frame* :rf/default]
      (is (= :rf/default (frame/require-current-frame! :dispatch))
          "an explicit :rf/default scope resolves like any other"))))

;; ---- ensure-default-frame! survives as a TEST-ONLY fixture helper ---------

(deftest ensure-default-frame-is-a-test-only-helper
  (testing "ensure-default-frame! still registers :rf/default for test fixtures (idempotent), but it is NOT a runtime path"
    (is (nil? (frame/frame :rf/default)) "precondition: init! did not create it")
    (frame/ensure-default-frame!)
    (is (some? (frame/frame :rf/default))
        "the test-only fixture helper registers :rf/default on demand")
    (let [original (frame/frame :rf/default)]
      (frame/ensure-default-frame!)
      (is (identical? original (frame/frame :rf/default))
          "idempotent — a second call does not replace the frame"))))

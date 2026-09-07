(ns re-frame.clear-grammar-guards-test
  "Fail-closed guards on `(rf/clear kind id ?opts)`'s OWN argument validation
  (rf2-kuky.80).

  `rf/clear` is the one kind-keyed registrar inverse that replaced nine
  per-kind `clear-*` names. Two of its three refusal paths had no pin
  anywhere in the tree when this file landed: `:rf.error/registrar-clear-bad-request`
  was asserted ONLY in the HTTP suites
  (`re-frame.http-interceptors-test` / `-cljs-test`), which reach it through
  the `:http-interceptor` kind and so exercise the frame-scoped arm alone.

  This file pins the two arms nothing else covers, both of which are
  argument validation in `re-frame.core` itself rather than in any artefact:

  1. OPTS ON A NON-FRAME-SCOPED KIND. The `{:frame f}` opts map is accepted
     for `:flow` and `:http-interceptor` ONLY — the two kinds that own
     per-frame state. Every other kind clears one process-wide registrar
     row, where a frame is meaningless, so opts on `:sub` are refused
     REGARDLESS of whether the map is well formed. A well-formed
     `{:frame f}` must fail here just as a typo'd `{:fram f}` does; the
     kind is what makes opts inadmissible, not the map's shape.

  2. UNKNOWN KIND. A kind outside the closed set fails closed, and the
     failure NAMES the closed set — both in the human message and as the
     `:clear-kinds` ex-data slot — so the caller is told what the
     alternatives are rather than merely that they were wrong.

  Both arms run BEFORE any frame is resolved or any registry row is
  touched, so each test also asserts ZERO RESIDUE: the registration the
  refused call named is still there afterwards.

  ## Vacuity guard

  A refusal test passes for the wrong reason if `clear` is broken outright
  (every call throwing would satisfy every `is` below). `valid-clear-still-clears`
  is the positive control: the same `:sub` kind, cleared through the same
  door with the arity these tests refuse opts on, must actually deregister
  and return the id.

  Per spec/API.md §Clearing registrations, spec/001-Registration.md's kind
  table, and Principles §No silent swallow."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- refused
  "Run `thunk`, expecting it to throw. Returns `{:data … :message …}` for the
  thrown `ExceptionInfo`, or nil if it returned normally."
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo e
      {:data (ex-data e) :message (ex-message e)})))

(defn- registered-sub?
  [id]
  (some? (rf.registrar/handler-meta :sub id)))

;; ---------------------------------------------------------------------------
;; 1. Opts on a kind that is not frame-scoped
;; ---------------------------------------------------------------------------

(deftest explicit-opts-on-a-non-frame-scoped-kind-fail-closed
  (testing "(rf/clear :sub id {:frame f}) is REFUSED even though the opts map
            is well formed — `:sub` is not one of the two frame-scoped kinds,
            so it takes no opts at all — and the subscription survives the
            refused call"
    (rf/reg-sub :guard/total (fn [db _] (:total db)))
    (is (registered-sub? :guard/total)
        "precondition — the subscription is registered")

    (let [{:keys [data message]} (refused #(rf/clear :sub :guard/total {:frame :guard/elsewhere}))]
      (is (some? data)
          "a well-formed {:frame f} on a non-frame-scoped kind must THROW, not
           be tolerated and ignored")
      (is (= :rf.error/registrar-clear-bad-request (:rf.error/id data))
          "the canonical discriminator — one error id for the one verb")
      (is (= :opts-on-a-non-frame-scoped-kind (:reason data))
          "the machine reason distinguishes this arm from :malformed-opts and
           :unknown-kind")
      (is (= :sub (:kind data))
          "ex-data names the offending kind")
      (is (= 'rf/clear (:where data))
          "ex-data names the user-facing surface, so a grep-for-symbol lands
           on the call site")
      (is (= :fix-the-clear-call (:recovery data))
          "the Spec 009 recovery disposition")
      (is (re-find #":flow" (str message))
          "the message names the frame-scoped kinds the caller could have
           meant, rather than only refusing")
      (is (re-find #":http-interceptor" (str message))
          "…both of them"))

    (is (registered-sub? :guard/total)
        "ZERO RESIDUE — validation runs before any registry row is touched, so
         the refused call cleared nothing"))

  (testing "a MALFORMED opts map on the same non-frame-scoped kind is refused
            by the kind check, which runs first"
    (rf/reg-sub :guard/typo (fn [db _] (:typo db)))
    (let [{:keys [data]} (refused #(rf/clear :sub :guard/typo {:fram :guard/elsewhere}))]
      (is (= :rf.error/registrar-clear-bad-request (:rf.error/id data)))
      (is (= :opts-on-a-non-frame-scoped-kind (:reason data))
          "the kind is inadmissible before the map's shape is even considered"))
    (is (registered-sub? :guard/typo)
        "ZERO RESIDUE")))

;; ---------------------------------------------------------------------------
;; 2. Unknown kind names the closed set
;; ---------------------------------------------------------------------------

(deftest unknown-kind-fails-closed-and-reports-the-closed-set
  (testing "(rf/clear :widget id) throws, and the failure tells the caller what
            the clearable kinds ARE — in the message and in ex-data"
    (let [{:keys [data message]} (refused #(rf/clear :widget :guard/nope))
          kinds                  (set (:clear-kinds data))]
      (is (some? data) "an unknown kind must fail closed")
      (is (= :rf.error/registrar-clear-bad-request (:rf.error/id data)))
      (is (= :unknown-kind (:reason data)))
      (is (= :widget (:kind data))
          "ex-data carries the kind the caller actually passed")

      (is (seq kinds)
          "the closed set travels in ex-data, not only in prose")
      (doseq [kind [:event :sub :fx :cofx :interceptor :view :head
                    :error-projector :flow :http-interceptor :route
                    :resource :mutation :resource-scope]]
        (is (contains? kinds kind)
            (str "the closed set names " kind)))

      (is (not (contains? kinds :frame))
          ":frame is NOT clearable — a frame is a live runtime object torn
           down by destroy-frame!, not a registrar row (Spec 001)")
      (is (not (contains? kinds :widget))
          "…and the bogus kind is obviously not in it")

      (is (re-find #":widget" (str message))
          "the message quotes the offending kind back")
      (is (re-find #":resource-scope" (str message))
          "the message spells the closed set out, so the caller does not have
           to read ex-data to learn the alternatives"))))

;; ---------------------------------------------------------------------------
;; Positive control — the refusals above are not a broken `clear`
;; ---------------------------------------------------------------------------

(deftest valid-clear-still-clears
  (testing "the same kind, same door, correct arity: (rf/clear :sub id)
            deregisters and returns the id"
    (rf/reg-sub :guard/live (fn [db _] (:live db)))
    (is (registered-sub? :guard/live) "precondition")
    (is (= :guard/live (rf/clear :sub :guard/live))
        "clear returns the id for every kind")
    (is (not (registered-sub? :guard/live))
        "the registration is gone — so the refusals above are refusals, not a
         clear that throws unconditionally")))

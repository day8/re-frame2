(ns re-frame.example-login-form-slice-cljs-test
  "Framework-tree regression for the login example's FORM SLICE — the
   `:auth.login/submit-form` event at examples/core/login/core.cljs (mirrored
   byte-for-similar into examples/substrates/{uix,helix}/login). rf2-t83ail.

   These belong in the framework test tree, NOT under examples/ (examples stay
   test-free per rf2-8cevm). The ns requires the example's production source
   (`login.core`) so its events / subs / machine / schemas register at ns-load,
   then drives `:auth.login/submit-form` directly. It runs under the
   consolidated `:node-test` CLJS build (`../examples/core` is on its
   source-paths) — the only runtime where `login.core`'s ns-load + durable
   handlers actually execute. Sibling of `re-frame.login-cljs-test` (which pins
   the MACHINE's `[:schemas :data]` boundary); this ns pins the SLICE half.

   Two security-relevant behaviours were previously UNCOVERED
   (`git grep ':auth.login/submit-form' -- implementation/` = zero):

     1. PASSWORD-CLEARING HYGIENE. A clean submit blanks
        `[:auth :login-form :draft :password]` in the SAME commit that hands
        the draft to the machine — the app-db half of keep-secrets-out-of-
        traces. A silent regression that stopped clearing `:password` would
        leave the secret sitting in app-db snapshots / recordings with no
        failing test.

     2. PRE-SUBMIT MALLI VALIDATION against `login.core/Credentials`. A clean
        draft dispatches into the machine + clears field errors; an invalid
        draft populates `:errors`, latches `:submit-attempted?`, and dispatches
        NOTHING (so a bad draft never bounces silently off the machine's
        `:submit` schema boundary).

   The clean-branch machine dispatch is asserted by capturing the `:dispatch`
   fx via a function-value `:fx-overrides` entry (spec/002 §`:fx-overrides`,
   rf2-nrpj1) — the override runs in place of the reserved `:dispatch` body, so
   we observe the exact event `submit-form` emits WITHOUT the machine + managed-
   HTTP running. That makes the assertion a direct, deterministic pin on
   `submit-form`'s output contract rather than an integration walk."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; login.core pulls these transitively; require here so the ns is
            ;; self-sufficient (mirrors re-frame.login-cljs-test).
            [re-frame.schemas]
            [re-frame.machines]
            [login.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- slice
  "The whole login-form slice at app-db [:auth :login-form] for frame `f`."
  [f]
  (get-in (rf/app-db-value f) [:auth :login-form]))

(defn- seed-draft!
  "Boot the slice then type `email` / `password` into the draft via the real
   `:auth.login/edit-field` handler (controlled-input round trip)."
  [f email password]
  (rf/dispatch-sync [:auth.login/initialise-form] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-field :email email] {:frame f})
  (rf/dispatch-sync [:auth.login/edit-field :password password] {:frame f}))

(defn- submit-capturing-dispatch!
  "Dispatch `:auth.login/submit-form` on frame `f` while capturing every
   `:dispatch` fx it emits (the machine hand-off) into `captured`, WITHOUT
   running the reserved `:dispatch` body — so the machine + managed-HTTP never
   fire. `captured` is an atom of the dispatched event vectors."
  [f captured]
  (rf/dispatch-sync [:auth.login/submit-form]
                    {:frame        f
                     :fx-overrides {:dispatch (fn [_ctx ev] (swap! captured conj ev))}}))

;; ---------------------------------------------------------------------------
;; (1) clean submit — password-clearing hygiene + machine hand-off
;; ---------------------------------------------------------------------------

(deftest clean-submit-clears-password-and-hands-draft-to-machine
  (testing "rf2-t83ail — a clean submit blanks [:draft :password] in the same
            commit it hands the draft to the machine (secret-field hygiene),
            flips :status to :submitting, clears :errors, latches
            :submit-attempted?, and dispatches exactly one machine submit
            carrying the REAL password (its one sanctioned trip off the box)"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (seed-draft! f "alice@example.com" "hunter2pw")
      (let [captured (atom [])]
        (submit-capturing-dispatch! f captured)
        (let [s (slice f)]
          ;; --- secret-field hygiene: the password is gone from app-db ---
          (is (= "" (get-in s [:draft :password]))
              "the password is blanked in the app-db slice draft after submit")
          (is (= "alice@example.com" (get-in s [:draft :email]))
              "only the SECRET is cleared — the email draft is left intact")
          ;; --- the slice mirrors the hand-off ---
          (is (= :submitting (:status s))
              "the slice :status advanced to :submitting on the clean branch")
          (is (= {} (:errors s))
              "stale field errors were cleared on the clean branch")
          (is (true? (:submit-attempted? s))
              ":submit-attempted? latched on submit")
          ;; --- the machine hand-off carries the real password ---
          (is (= 1 (count @captured))
              "exactly one :dispatch fx (the machine hand-off) was emitted")
          (is (= [:auth.login/flow
                  [:auth.login/submit {:email "alice@example.com"
                                       :password "hunter2pw"}]]
                 (first @captured))
              "the draft handed to the machine still carries the REAL password
               — proving the blank happens AFTER the draft is captured for
               dispatch (blank it first and the login would submit an empty
               password)"))))))

;; ---------------------------------------------------------------------------
;; (2) invalid submit — errors surface, nothing dispatched, password retained
;; ---------------------------------------------------------------------------

(deftest invalid-submit-populates-errors-and-dispatches-nothing
  (testing "rf2-t83ail — an invalid draft (bad email + short password) fails the
            pre-submit Credentials validation: :errors is populated per field,
            :submit-attempted? latches, :status stays :idle, and NOTHING is
            dispatched (the draft never reaches — and never silently bounces
            off — the machine's :submit schema boundary)"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (seed-draft! f "not-an-email" "short")
      (let [captured (atom [])]
        (submit-capturing-dispatch! f captured)
        (let [s (slice f)]
          (is (true? (:submit-attempted? s))
              ":submit-attempted? latched even on the invalid branch (the
               visibility rule — every invalid field may now speak)")
          (is (contains? (:errors s) :email)
              "the bad email produced a field error")
          (is (contains? (:errors s) :password)
              "the short password produced a field error")
          (is (= :idle (:status s))
              ":status did NOT advance to :submitting on an invalid submit")
          (is (zero? (count @captured))
              "no :dispatch fx — the invalid draft was NOT handed to the
               machine")
          ;; The password is RETAINED here — nothing left the box (no dispatch,
          ;; no HTTP), so this is not the leak the hygiene rule guards against;
          ;; the user needs the password kept for the fix-up. This pins that
          ;; clearing is coupled to the successful hand-off, not to submit alone.
          (is (= "short" (get-in s [:draft :password]))
              "the password is RETAINED on the invalid branch (kept for the
               fix-up; the clear is coupled to a clean hand-off, not to the
               submit click)"))))))

;; ---------------------------------------------------------------------------
;; (3) a partially-valid draft (good email, short password) still blocks
;; ---------------------------------------------------------------------------

(deftest partially-valid-submit-still-blocks-and-keeps-secret
  (testing "rf2-t83ail — a valid email but a too-short password still fails
            validation: only the :password error surfaces, nothing is
            dispatched, and the (short) password is retained"
    (with-new-frame [f (frame/make-anon-frame-record! {})]
      (seed-draft! f "alice@example.com" "tiny")
      (let [captured (atom [])]
        (submit-capturing-dispatch! f captured)
        (let [s (slice f)]
          (is (not (contains? (:errors s) :email))
              "the valid email produced no error")
          (is (contains? (:errors s) :password)
              "the short password produced a field error")
          (is (zero? (count @captured))
              "a single invalid field is enough to block the machine hand-off")
          (is (= "tiny" (get-in s [:draft :password]))
              "the password is retained (never handed off, so never cleared)"))))))

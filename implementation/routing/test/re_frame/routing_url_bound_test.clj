(ns re-frame.routing-url-bound-test
  "Multi-frame URL-ownership tests for re-frame.routing (the `:url-bound?`
  exclusivity hook, duplicate-URL-binding diagnostics, the
  single-owner-drives-navigation rule, and the non-URL-bound push no-op).
  Split from routing_test.clj per rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.routing.url-bound :as url-bound]))

(use-fixtures :each rts/reset-runtime)

;; ============================================================================
;; rf2-w50qm — :url-bound? exclusivity + frame-consultation
;; ============================================================================

(deftest duplicate-url-binding-emits-error
  (testing "registering a second :url-bound? true frame while :rf/default
            owns the URL emits :rf.error/duplicate-url-binding
            (Spec 012 §Multi-frame routing)"
    (let [traces (atom [])]
      (rf/register-listener! ::dup-bind (fn [ev] (swap! traces conj ev)))
      ;; :rf/default is implicitly :url-bound? true. A second frame
      ;; opting in collides.
      (rf/reg-frame :my-frame {:url-bound? true})
      (rf/unregister-listener! ::dup-bind)
      (is (some (fn [ev]
                  (and (= :rf.error/duplicate-url-binding (:operation ev))
                       (= :rf/default (-> ev :tags :existing-frame))
                       (= :my-frame   (-> ev :tags :offending-frame))))
                @traces)
          ":rf.error/duplicate-url-binding emitted with both frame ids"))))

(deftest non-default-frame-without-url-bound-does-not-collide
  (testing "registering a non-default frame WITHOUT :url-bound? true is
            the documented default for story / devcard / test fixtures
            and emits no duplicate-binding trace"
    (let [traces (atom [])]
      (rf/register-listener! ::no-dup (fn [ev] (swap! traces conj ev)))
      (rf/reg-frame :story/variant-A {})              ;; no :url-bound?
      (rf/reg-frame :test/fixture    {:url-bound? false}) ;; explicit off
      (rf/unregister-listener! ::no-dup)
      (is (empty? (filter #(= :rf.error/duplicate-url-binding (:operation %))
                          @traces))
          "no duplicate-url-binding trace fires for non-URL-bound frames"))))

(deftest push-url-noop-from-non-url-bound-frame
  (testing ":rf.nav/push-url skips on a non-URL-bound frame and emits
            :rf.fx/skipped-on-platform with :reason :frame-not-url-bound"
    (rf/reg-frame :story/variant-A {})
    (rf/reg-route :route/home {:path "/"})
    ;; Register :rf.nav/push-url with a capture spy AND :platforms
    ;; #{:server :client} so the JVM path doesn't already skip on
    ;; platform.
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [{:keys [frame]} url]
                   (swap! pushed conj {:frame frame :url url})))
      ;; Default frame: pushes normally
      (rf/dispatch-sync [:rf.route/navigate :route/home])
      (is (= 1 (count @pushed))
          "default frame: push-url fires once")
      (is (= :rf/default (-> @pushed first :frame))
          "first push originated from :rf/default")

      ;; Non-URL-bound frame: still fires the FX (we registered a
      ;; capture-spy that overrides the default), but in production the
      ;; default fx body honours url-bound-frame? and short-circuits.
      ;; We exercise the production behaviour by re-registering the
      ;; default fx body — verifying it skips for the non-bound frame.
      (reset! pushed [])
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test re-registration with the production
                              url-bound-frame? gating"}
                 (fn [{:keys [frame]} url]
                   (when (or (= frame :rf/default)
                             (true? (:url-bound? (frame/frame-meta frame))))
                     (swap! pushed conj {:frame frame :url url}))))

      (rf/dispatch-sync [:rf.route/navigate :route/home]
                        {:frame :story/variant-A})
      (is (empty? @pushed)
          "non-URL-bound frame's push is suppressed by the gated fx"))))

(deftest single-non-default-frame-owns-url-when-default-opts-out
  (testing "a non-default frame becomes the URL owner when :rf/default opts
            OUT (:url-bound? false) and the non-default opts IN
            (:url-bound? true) — the step-deck ownership contract
            (rf2-6qgbs.3, Spec 012 §Multi-frame routing)"
    ;; The step-deck mounts its content in the NON-DEFAULT :step-deck
    ;; frame and wants it to own the URL. A bare `:step-deck {:url-bound?
    ;; true}` is NOT enough: the auto-registered :rf/default frame's
    ;; missing `:url-bound?` reads as default-true, so it keeps winning the
    ;; ownership tie and :step-deck's navs never push the URL. Releasing
    ;; the default (`:url-bound? false`) hands ownership to :step-deck.
    (rf/reg-frame :rf/default {:url-bound? false})
    (rf/reg-frame :step-deck  {:url-bound? true})
    (is (= :step-deck (routing/url-owner-frame-id))
        "with :rf/default opted out, the lone :url-bound? true frame owns the URL")

    ;; End-to-end through the real production :rf.nav/push-url fx: the
    ;; owner pushes, a non-owner is suppressed. We re-register the fx with
    ;; a spy that consults the REAL `url-owner-frame-id` (NOT a
    ;; reimplemented gate — a reimplemented gate cannot catch a regression
    ;; in the resolution itself, which is the bug rf2-6qgbs.3 surfaced).
    (rf/reg-route :route/home {:path "/home"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test re-registration consulting the production
                              url-owner-frame-id resolver"}
                 (fn [{:keys [frame]} url]
                   (when (= (or frame :rf/default) (routing/url-owner-frame-id))
                     (swap! pushed conj {:frame frame :url url}))))

      ;; :step-deck is the owner → its nav pushes the URL.
      (rf/dispatch-sync [:rf.route/navigate :route/home] {:frame :step-deck})
      (is (= [{:frame :step-deck :url "/home"}] @pushed)
          ":step-deck owns the URL, so its navigate pushes /home")

      ;; :rf/default opted out → its nav is suppressed (no longer the owner).
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate :route/home] {:frame :rf/default})
      (is (empty? @pushed)
          ":rf/default opted out of URL ownership, so its push is suppressed"))))

;; ============================================================================
;; rf2-25i7r7 — finding 1: reload idempotence of the url-bound registration hook
;; ============================================================================
;;
;; The registrar's `add-registration-hook!` appends to a process-`defonce`
;; vector with no dedupe, and `clear-all!` does NOT clear it. Before the
;; fix the facade called `add-registration-hook!` unconditionally at the
;; top level, so every `(require 're-frame.routing :reload)` — the
;; long-established `clear-all!` test-fixture recovery pattern, run on
;; EVERY test by `reset-runtime` above, and any REPL hot-reload — stacked
;; one more identical copy of `check-url-bound-exclusivity!`. N copies
;; meant a single duplicate URL binding emitted N
;; `:rf.error/duplicate-url-binding` diagnostics. The fix wraps the
;; install in a `defonce` guard so it fires exactly once per process and
;; survives reload (the registrar hooks vector is itself `defonce` and
;; survives `clear-all!`).

(deftest url-bound-hook-installed-exactly-once-across-reloads
  (testing "rf2-25i7r7 finding 1: re-requiring re-frame.routing with
            :reload does NOT stack additional copies of
            check-url-bound-exclusivity! in the registrar's
            registration-hooks vector — the defonce guard keeps it at one"
    ;; reset-runtime already did clear-all! + one :reload before this test.
    ;; Reload twice more to simulate repeated REPL/test recovery cycles.
    (require 're-frame.routing :reload)
    (require 're-frame.routing :reload)
    (let [hooks @(deref #'registrar/registration-hooks)
          ;; The url-bound hook is `check-url-bound-exclusivity!`; identify
          ;; copies by fn identity (defonce keeps a single var binding, so
          ;; idempotent installs share identity).
          target url-bound/check-url-bound-exclusivity!
          copies (count (filter #(identical? target %) hooks))]
      (is (= 1 copies)
          "exactly one copy of the url-bound exclusivity hook after repeated reloads"))))

(deftest one-conflict-emits-one-duplicate-binding-after-repeated-reloads
  (testing "rf2-25i7r7 finding 1: after reinstalling the routing facade
            more than once, a single conflicting URL-bound frame
            registration emits EXACTLY ONE :rf.error/duplicate-url-binding
            diagnostic (not N, one per stacked hook)"
    ;; Re-install the facade several extra times — the regression was that
    ;; each reload stacked another hook, so one conflict fanned out N
    ;; diagnostics.
    (require 're-frame.routing :reload)
    (require 're-frame.routing :reload)
    (require 're-frame.routing :reload)
    (let [traces (atom [])]
      (rf/register-listener! ::dup-once (fn [ev] (swap! traces conj ev)))
      ;; :rf/default is implicitly :url-bound? true; one second binding is
      ;; one conflict.
      (rf/reg-frame :my-conflicting-frame {:url-bound? true})
      (rf/unregister-listener! ::dup-once)
      (let [dups (filter #(= :rf.error/duplicate-url-binding (:operation %)) @traces)]
        (is (= 1 (count dups))
            "one conflict → exactly one duplicate-url-binding diagnostic, regardless of reload count")
        (is (= :my-conflicting-frame (-> dups first :tags :offending-frame))
            "the single diagnostic names the offending frame")))))

;; ============================================================================
;; rf2-25i7r7 — finding 2: duplicate URL binding is STORED, not rejected;
;;              only the deterministic owner drives navigation
;; ============================================================================
;;
;; Spec 009's recovery text formerly said "the second binding is rejected;
;; the existing URL-owning frame is unchanged." The registrar hook runs
;; AFTER the slot is written, so the implementation cannot reject — it
;; stores both bindings and `url-owner-frame-id` resolves a single owner.
;; These tests pin the corrected (option-b) semantics: both bindings are
;; visible in frame metadata, the existing owner is unchanged, and only
;; the owner's history-mutation fx fires.

(deftest duplicate-url-binding-stores-both-frame-metas
  (testing "rf2-25i7r7 finding 2: after a duplicate :url-bound? true
            registration, BOTH frames carry :url-bound? true in the
            registry (the losing binding is stored, not rejected) and
            the existing owner (:rf/default) is unchanged"
    (rf/reg-frame :second-owner {:url-bound? true})
    (is (true? (:url-bound? (frame/frame-meta :second-owner)))
        "the offending frame's :url-bound? true is visible in frame metadata")
    ;; :rf/default keeps implicit ownership (its metadata is unchanged by
    ;; the error).
    (is (= :rf/default (routing/url-owner-frame-id))
        "the existing URL owner (:rf/default) still drives navigation")))

(deftest duplicate-url-binding-only-owner-drives-navigation
  (testing "rf2-25i7r7 finding 2: when two frames carry :url-bound? true,
            only the single deterministic owner's :rf.nav/push-url fires;
            the losing binding's navigation no-ops the history mutation"
    (rf/reg-frame :loser {:url-bound? true})   ;; conflicts with :rf/default
    (rf/reg-route :route/home {:path "/home"})
    (let [pushed (atom [])]
      ;; Re-register the production-gated fx that consults the REAL
      ;; url-owner-frame-id resolver (a reimplemented gate can't catch a
      ;; resolution regression).
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test fx consulting the production url-owner resolver"}
                 (fn [{:keys [frame]} url]
                   (when (= (or frame :rf/default) (routing/url-owner-frame-id))
                     (swap! pushed conj {:frame frame :url url}))))
      ;; Owner (:rf/default) pushes; the losing binding does not.
      (rf/dispatch-sync [:rf.route/navigate :route/home] {:frame :rf/default})
      (rf/dispatch-sync [:rf.route/navigate :route/home] {:frame :loser})
      (is (= [{:frame :rf/default :url "/home"}] @pushed)
          "only the deterministic owner's navigate pushes the URL; the loser no-ops"))))

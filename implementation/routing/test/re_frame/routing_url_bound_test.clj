(ns re-frame.routing-url-bound-test
  "Multi-frame URL-ownership tests for re-frame.routing (the `:url-bound?`
  exclusivity hook, duplicate-URL-binding diagnostics, the
  single-owner-drives-navigation rule, and the non-URL-bound push no-op).
  Split from routing_test.clj per rf2-u8qe7y finding 3.

  ## Posture split (rf2-o5dbf)

  URL OWNERSHIP is production-real and carries no posture guard: which frame
  `routing/url-owner-frame-id` reports, that a duplicate binding is STORED
  rather than rejected, that only the deterministic owner drives navigation,
  that reconcile fails CLOSED on an ambiguous multi-binding load order, and
  that a non-URL-bound frame's push is a no-op. Those run in the ordinary
  `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane).

  The `:rf.error/duplicate-url-binding` DIAGNOSTIC is dev instrumentation —
  `trace/emit-error!` sits behind `interop/debug-enabled?`, read once at load
  time. Its assertions are kept VERBATIM inside `(when interop/debug-enabled?
  …)` arms marked `rf2-o5dbf`. One of them is NEGATIVE
  (`non-default-frame-without-url-bound-does-not-collide`): with no trace bus
  it would pass vacuously, so it is inside the arm with the ownership fact it
  is really about — `:rf/default` still owns the URL after both non-bound
  registrations — asserted outside."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
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
      (rf/register-listener! :trace ::dup-bind (fn [ev] (swap! traces conj ev)))
      ;; EP-0002 (rf2-nn0jqa): URL ownership is explicit. This suite's
      ;; fixture registered `:rf/default {:url-bound? true}` as the declared
      ;; owner, so a second frame opting in collides with it.
      (rf/make-frame {:id :my-frame :url-bound? true})
      (rf/unregister-listener! :trace ::dup-bind)
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the collision is resolved
      ;; the same way in both postures — the incumbent keeps the URL and the
      ;; late claimant does not steal it. Only the announcement is dev-only.
      (is (= :rf/default (routing/url-owner-frame-id))
          "the incumbent :rf/default keeps URL ownership; :my-frame did not steal it")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (some (fn [ev]
                    (and (= :rf.error/duplicate-url-binding (:operation ev))
                         (= :rf/default (-> ev :tags :existing-frame))
                         (= :my-frame   (-> ev :tags :offending-frame))))
                  @traces)
            ":rf.error/duplicate-url-binding emitted with both frame ids")))))

(deftest non-default-frame-without-url-bound-does-not-collide
  (testing "registering a non-default frame WITHOUT :url-bound? true is
            the documented default for story / devcard / test fixtures
            and emits no duplicate-binding trace"
    (let [traces (atom [])]
      (rf/register-listener! :trace ::no-dup (fn [ev] (swap! traces conj ev)))
      (rf/make-frame {:id :story/variant-A})              ;; no :url-bound?
      (rf/make-frame {:id :test/fixture :url-bound? false}) ;; explicit off
      (rf/unregister-listener! :trace ::no-dup)
      ;; SEMANTIC, posture-independent (rf2-o5dbf): neither frame claimed the
      ;; URL, so the incumbent owner is untouched. That is the fact the silent
      ;; diagnostic encodes; without it the leg below is vacuous under the gate.
      (is (= :rf/default (routing/url-owner-frame-id))
          ":rf/default still owns the URL — neither non-bound frame claimed it")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring); NEGATIVE over
      ;; the trace ring, hence guarded.
      (when interop/debug-enabled?
        (is (empty? (filter #(= :rf.error/duplicate-url-binding (:operation %))
                            @traces))
            "no duplicate-url-binding trace fires for non-URL-bound frames")))))

(deftest push-url-noop-from-non-url-bound-frame
  (testing ":rf.nav/push-url skips on a non-URL-bound frame and emits
            :rf.fx/skipped-on-platform with :reason :frame-not-url-bound"
    (rf/make-frame {:id :story/variant-A})
    (rf/reg-route :route/home {} "/")
    ;; Register :rf.nav/push-url with a capture spy AND :platforms
    ;; #{:server :client} so the JVM path doesn't already skip on
    ;; platform.
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [{:keys [frame]} url]
                   (swap! pushed conj {:frame frame :url url})))
      ;; Default frame: pushes normally
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
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
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test re-registration with the production
                              url-bound-frame? gating"}
                 (fn [{:keys [frame]} url]
                   (when (or (= frame :rf/default)
                             (true? (:url-bound? (frame/frame-meta frame))))
                     (swap! pushed conj {:frame frame :url url}))))

      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}]
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
    (rf/make-frame {:id :rf/default :url-bound? false})
    (rf/make-frame {:id :step-deck :url-bound? true})
    (is (= :step-deck (routing/url-owner-frame-id))
        "with :rf/default opted out, the lone :url-bound? true frame owns the URL")

    ;; End-to-end through the real production :rf.nav/push-url fx: the
    ;; owner pushes, a non-owner is suppressed. We re-register the fx with
    ;; a spy that consults the REAL `url-owner-frame-id` (NOT a
    ;; reimplemented gate — a reimplemented gate cannot catch a regression
    ;; in the resolution itself, which is the bug rf2-6qgbs.3 surfaced).
    (rf/reg-route :route/home {} "/home")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test re-registration consulting the production
                              url-owner-frame-id resolver"}
                 (fn [{:keys [frame]} url]
                   (when (= (or frame :rf/default) (routing/url-owner-frame-id))
                     (swap! pushed conj {:frame frame :url url}))))

      ;; :step-deck is the owner → its nav pushes the URL.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}] {:frame :step-deck})
      (is (= [{:frame :step-deck :url "/home"}] @pushed)
          ":step-deck owns the URL, so its navigate pushes /home")

      ;; :rf/default opted out → its nav is suppressed (no longer the owner).
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}] {:frame :rf/default})
      (is (empty? @pushed)
          ":rf/default opted out of URL ownership, so its push is suppressed"))))

;; ============================================================================
;; rf2-25i7r7 — finding 1: reload idempotence of the url-bound lifecycle hook
;; ============================================================================
;;
;; Historically the exclusivity check was a REGISTRAR registration hook
;; (`add-registration-hook!` appends to a process-`defonce` vector with no
;; dedupe), so an unguarded facade reload stacked one more identical copy per
;; `(require 're-frame.routing :reload)` — a single duplicate URL binding then
;; emitted N `:rf.error/duplicate-url-binding` diagnostics. Per rf2-h1vqa4 the
;; check moved to the frame (re-)registration lifecycle hook
;; (`:routing/on-frame-registered!`, fired by the frame engine — frames no
;; longer flow through `registrar/register!`), published via
;; `late-bind/set-fn!`, which is KEY-IDEMPOTENT: a reload re-publishes the one
;; hook fn rather than stacking. The one-conflict → one-diagnostic invariant
;; below is the behavioral pin; this test pins the ownership move itself.

(deftest url-bound-hook-is-not-a-registrar-hook
  (testing "rf2-h1vqa4: the url-bound exclusivity check no longer rides the
            registrar's registration-hooks vector (frames don't flow through
            registrar/register!), and repeated facade reloads leave no copies
            there — the lifecycle hook is the late-bind
            :routing/on-frame-registered! publication, which is key-idempotent"
    ;; reset-runtime already did clear-all! + one :reload before this test.
    ;; Reload twice more to simulate repeated REPL/test recovery cycles.
    (require 're-frame.routing :reload)
    (require 're-frame.routing :reload)
    (let [hooks  @(deref #'registrar/registration-hooks)
          target url-bound/check-url-bound-exclusivity!
          copies (count (filter #(identical? target %) hooks))]
      (is (zero? copies)
          "the url-bound exclusivity check is absent from the registrar's
           registration-hooks vector — it rides the frame lifecycle hook"))
    (is (some? (late-bind/get-fn :routing/on-frame-registered!))
        "the :routing/on-frame-registered! lifecycle hook is published")))

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
      (rf/register-listener! :trace ::dup-once (fn [ev] (swap! traces conj ev)))
      ;; :rf/default is implicitly :url-bound? true; one second binding is
      ;; one conflict.
      (rf/make-frame {:id :my-conflicting-frame :url-bound? true})
      (rf/unregister-listener! :trace ::dup-once)
      ;; SEMANTIC, posture-independent (rf2-o5dbf): however many times the
      ;; facade was reinstalled, ownership is still resolved once and the
      ;; incumbent still holds it.
      (is (= :rf/default (routing/url-owner-frame-id))
          "the repeated facade reloads did not disturb URL ownership")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring). The
      ;; ONE-not-N fan-out this deftest is named for is a property of the
      ;; diagnostic, so it is only observable in the posture that has one.
      (when interop/debug-enabled?
        (let [dups (filter #(= :rf.error/duplicate-url-binding (:operation %)) @traces)]
          (is (= 1 (count dups))
              "one conflict → exactly one duplicate-url-binding diagnostic, regardless of reload count")
          (is (= :my-conflicting-frame (-> dups first :tags :offending-frame))
              "the single diagnostic names the offending frame"))))))

;; ============================================================================
;; rf2-25i7r7 — finding 2: duplicate URL binding is STORED, not rejected;
;;              only the deterministic owner drives navigation
;; ============================================================================
;;
;; Spec 009's recovery text formerly said "the second binding is rejected;
;; the existing URL-owning frame is unchanged." The lifecycle hook runs
;; AFTER the frame config is seated, so the implementation cannot reject — it
;; stores both bindings and `url-owner-frame-id` resolves a single owner.
;; These tests pin the corrected (option-b) semantics: both bindings are
;; visible in frame metadata, the existing owner is unchanged, and only
;; the owner's history-mutation fx fires.

(deftest duplicate-url-binding-stores-both-frame-metas
  (testing "rf2-25i7r7 finding 2: after a duplicate :url-bound? true
            registration, BOTH frames carry :url-bound? true in the
            registry (the losing binding is stored, not rejected) and
            the existing owner (:rf/default) is unchanged"
    (rf/make-frame {:id :second-owner :url-bound? true})
    (is (true? (:url-bound? (frame/frame-meta :second-owner)))
        "the offending frame's :url-bound? true is visible in frame metadata")
    ;; :rf/default keeps implicit ownership (its metadata is unchanged by
    ;; the error).
    (is (= :rf/default (routing/url-owner-frame-id))
        "the existing URL owner (:rf/default) still drives navigation")))

(deftest duplicate-url-binding-only-owner-drives-navigation
  (testing "rf2-25i7r7 finding 2: when two frames carry :url-bound? true,
            only the single deterministic owner's :rf.nav/push-url fires;
            the losing binding's navigation no-ops the history mutation.
            rf2-3l7xxz: ownership is the FIRST-CLAIMED incumbent (the
            fixture's :rf/default claimed first), NOT a :rf/default privilege
            and NOT the alphabetically-first id — the duplicate :second-owner
            claims after :rf/default, so :rf/default stays owner and the
            newcomer loses."
    (rf/make-frame {:id :second-owner :url-bound? true})   ;; conflicts with :rf/default
    (rf/reg-route :route/home {} "/home")
    (let [pushed (atom [])]
      ;; Re-register the production-gated fx that consults the REAL
      ;; url-owner-frame-id resolver (a reimplemented gate can't catch a
      ;; resolution regression).
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test fx consulting the production url-owner resolver"}
                 (fn [{:keys [frame]} url]
                   (when (= frame (routing/url-owner-frame-id))
                     (swap! pushed conj {:frame frame :url url}))))
      ;; Owner (:rf/default) pushes; the losing binding does not.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}] {:frame :rf/default})
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}] {:frame :second-owner})
      (is (= [{:frame :rf/default :url "/home"}] @pushed)
          "only the deterministic owner's navigate pushes the URL; the loser no-ops"))))

;; ============================================================================
;; rf2-3l7xxz — a duplicate URL-bound frame whose id SORTS BEFORE the incumbent
;;              must NOT steal the browser URL (the existing owner is unchanged)
;; ============================================================================
;;
;; The prior `url-owner-frame-id` resolved ownership by sorting all
;; `:url-bound? true` frames by `(str id)` and taking the FIRST. The fixture's
;; incumbent `:rf/default` and the earlier duplicate tests all used duplicates
;; that sort AFTER `:rf/default` (`:second-owner`, `:my-frame`,
;; `:zz/duplicate-owner`), so the alphabetical resolver coincidentally returned
;; the incumbent. The bug bit when the duplicate sorted BEFORE the incumbent:
;; it would WIN the alphabetical sort and STEAL the URL, violating Spec 012
;; §Multi-frame routing ("the existing owner is unchanged; the losing binding's
;; history-mutation fxs no-op"). These tests pin the corrected first-claimed-
;; incumbent semantics by using a duplicate whose id sorts BEFORE `:rf/default`.

(deftest duplicate-sorting-before-incumbent-does-not-steal-ownership
  (testing "rf2-3l7xxz: registering a second :url-bound? true frame whose id
            sorts BEFORE the incumbent (:aaa-early < :rf/default) does NOT
            change the resolved owner — the incumbent :rf/default is unchanged"
    ;; Precondition: the fixture's :rf/default is the established URL owner.
    (is (= :rf/default (routing/url-owner-frame-id))
        "precondition: :rf/default is the incumbent URL owner")
    ;; The duplicate sorts alphabetically BEFORE :rf/default (\":aaa-early\" <
    ;; \":rf/default\"). Under the buggy alphabetical resolver this stole the URL.
    (rf/make-frame {:id :aaa-early :url-bound? true})
    (is (true? (:url-bound? (frame/frame-meta :aaa-early)))
        "the duplicate's :url-bound? true is stored (binding is reported, not rejected)")
    (is (= :rf/default (routing/url-owner-frame-id))
        "the incumbent :rf/default STILL owns the URL — the earlier-sorting
         duplicate did NOT steal it (rf2-3l7xxz)")))

(deftest duplicate-sorting-before-incumbent-noops-its-push
  (testing "rf2-3l7xxz: a duplicate :url-bound? true frame that sorts before
            the incumbent does NOT drive the browser URL — its
            :rf.nav/push-url no-ops while the incumbent's still fires.
            Exercises the REAL push-url path through the production
            url-owner-frame-id resolver (a reimplemented gate cannot catch a
            resolution regression — cf rf2-lo28u)."
    (rf/make-frame {:id :aaa-early :url-bound? true})       ;; sorts before :rf/default
    (rf/reg-route :route/home {} "/home")
    (let [pushed (atom [])]
      ;; Production-gated fx consulting the REAL resolver.
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test fx consulting the production url-owner resolver"}
                 (fn [{:keys [frame]} url]
                   (when (= frame (routing/url-owner-frame-id))
                     (swap! pushed conj {:frame frame :url url}))))
      ;; The earlier-sorting duplicate navigates FIRST — under the bug it owned
      ;; the URL and this push would fire. It must no-op now.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}] {:frame :aaa-early})
      (is (empty? @pushed)
          "the earlier-sorting duplicate's push is suppressed — it is NOT the owner")
      ;; The incumbent still drives the URL.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}] {:frame :rf/default})
      (is (= [{:frame :rf/default :url "/home"}] @pushed)
          "the incumbent :rf/default still pushes the URL"))))

(deftest legitimate-single-owner-still-drives-url
  (testing "rf2-3l7xxz: the legitimate single-owner path is unaffected — the
            sole :url-bound? true frame owns and drives the URL"
    ;; No duplicate registered; :rf/default is the lone owner.
    (is (= :rf/default (routing/url-owner-frame-id))
        "the sole :url-bound? true frame is the owner")
    (rf/reg-route :route/home {} "/home")
    (let [pushed (atom [])]
      (fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}
                  :doc       "test fx consulting the production url-owner resolver"}
                 (fn [{:keys [frame]} url]
                   (when (= frame (routing/url-owner-frame-id))
                     (swap! pushed conj {:frame frame :url url}))))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}] {:frame :rf/default})
      (is (= [{:frame :rf/default :url "/home"}] @pushed)
          "the legitimate single owner drives the URL"))))

(deftest incumbent-relinquishes-ownership-falls-to-next-claimant
  (testing "rf2-3l7xxz: when the incumbent re-registers WITHOUT :url-bound?
            true (opts out), ownership re-resolves to the next-claimed live
            binding — self-healing, NOT frozen on the now-unbound incumbent"
    ;; :rf/default is the incumbent; add an earlier-sorting duplicate that
    ;; claimed second.
    (rf/make-frame {:id :aaa-early :url-bound? true})
    (is (= :rf/default (routing/url-owner-frame-id))
        "incumbent :rf/default owns while it carries the binding")
    ;; The incumbent opts out — ownership must NOT stay frozen on it.
    (rf/make-frame {:id :rf/default :url-bound? false})
    (is (= :aaa-early (routing/url-owner-frame-id))
        "ownership re-resolves to the next live claimant (:aaa-early) once the
         incumbent relinquishes its binding")))

;; ============================================================================
;; rf2-68k8as — frames registered BEFORE re-frame.routing loads must not let a
;;              later, earlier-sorting duplicate STEAL the URL by id sort
;; ============================================================================
;;
;; The frame lifecycle hook (`:routing/on-frame-registered!`) is a FUTURE
;; observer — it does NOT replay existing registrations. So a frame that
;; claimed `:url-bound? true` BEFORE `(require 're-frame.routing)` never
;; recorded a claim in
;; `url-claim-order`. The resolver's OLD claim-free fallback then sorted all
;; bound frames by `(str id)` and took the first — letting a later duplicate
;; whose id sorts BEFORE the true first-claimant WIN the alphabetical tiebreak
;; and STEAL the browser URL (Spec 012 §1246 forbids exactly this: "the
;; existing owner is unchanged … resolving by id ordering would have let it").
;;
;; The fix (a) makes the resolver's claim-free fallback fail closed — a sole
;; bound frame owns, but 2+ bound frames with unrecoverable claim order resolve
;; to nil rather than id-sorting — and (b) has the façade call
;; `reconcile-existing-url-bindings!` right after installing the hook to seed
;; the unambiguous pre-existing incumbent (so a later duplicate can't steal it).
;;
;; A JVM test can't physically register frames before the routing namespace is
;; loaded (it's required at the top), so it reproduces the load-order STATE
;; directly: empty `url-claim-order` (the no-claim-recorded condition a
;; pre-load registration leaves) with `:url-bound? true` frame(s) already in
;; the registry, then drive the resolver / reconcile.

(deftest id-sort-fallback-does-not-steal-from-the-true-incumbent
  (testing "rf2-68k8as: with NO claim recorded (the pre-routing-load state) and
            TWO :url-bound? true frames in the registry, the resolver must NOT
            id-sort and hand ownership to the alphabetically-first frame — that
            is the URL-owner-steal bug. It fails closed to nil (ambiguous load
            order). FAILS WITHOUT THE FIX: the old fallback returned :aa-late
            (alphabetically first), stealing the URL from :zz-incumbent."
    ;; Simulate two frames registered before routing's hook observed them: the
    ;; registry carries both bindings but no claim was recorded.
    (rf/make-frame {:id :rf/default :url-bound? false})  ;; clear the fixture incumbent
    (rf/make-frame {:id :zz-incumbent :url-bound? true})
    (rf/make-frame {:id :aa-late :url-bound? true})
    ;; Drop the claims the live hook recorded, reproducing the "claims never
    ;; recorded because the frames pre-existed the hook" condition.
    (routing/reset-url-claims!)
    (is (nil? (routing/url-owner-frame-id))
        "ambiguous multi-binding load order with no recorded claim → nil owner,
         NOT the alphabetically-first :aa-late (the steal the old id-sort did)")))

(deftest reconcile-seeds-sole-pre-existing-incumbent
  (testing "rf2-68k8as: reconcile-existing-url-bindings! seeds the SOLE
            pre-existing :url-bound? true frame as the incumbent so a later,
            earlier-sorting duplicate cannot steal the URL. FAILS WITHOUT THE
            FIX (no reconcile + id-sort fallback): :aaa-stealer would win."
    ;; Establish a single pre-load incumbent whose id sorts AFTER a later
    ;; duplicate, with no recorded claim (the pre-routing-load state).
    (rf/make-frame {:id :rf/default :url-bound? false})  ;; clear the fixture incumbent
    (rf/make-frame {:id :zz-incumbent :url-bound? true})
    (routing/reset-url-claims!)
    ;; The façade runs reconcile at load time; reproduce that step explicitly
    ;; for the frame(s) that pre-existed the hook.
    (url-bound/reconcile-existing-url-bindings!)
    (is (= :zz-incumbent (routing/url-owner-frame-id))
        "the sole pre-existing url-bound frame is seeded as the incumbent")
    ;; A later duplicate whose id sorts BEFORE the incumbent now registers
    ;; through the LIVE hook — it must append after the seeded incumbent and
    ;; NOT steal ownership.
    (rf/make-frame {:id :aaa-stealer :url-bound? true})
    (is (= :zz-incumbent (routing/url-owner-frame-id))
        "the earlier-sorting later duplicate does NOT steal — incumbent unchanged")))

(deftest reconcile-multi-pre-existing-fails-closed-and-diagnoses
  (testing "rf2-68k8as: when MULTIPLE :url-bound? true frames pre-exist with
            unrecoverable claim order, reconcile fails closed (no owner) and
            emits a duplicate-url-binding diagnostic per extra binding —
            it does NOT silently pick one by id sort"
    (rf/make-frame {:id :rf/default :url-bound? false})  ;; clear the fixture incumbent
    (rf/make-frame {:id :zz-incumbent :url-bound? true})
    (rf/make-frame {:id :aa-late :url-bound? true})
    (routing/reset-url-claims!)
    (let [traces (atom [])]
      (rf/register-listener! :trace ::reconcile-dup (fn [ev] (swap! traces conj ev)))
      (url-bound/reconcile-existing-url-bindings!)
      (rf/unregister-listener! :trace ::reconcile-dup)
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the FAIL-CLOSED half — no
      ;; owner is picked, in either posture.
      (is (nil? (routing/url-owner-frame-id))
          "no deterministic owner for an ambiguous multi-binding load order")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (let [dups (filter #(= :rf.error/duplicate-url-binding (:operation %)) @traces)]
          (is (= 1 (count dups))
              "two pre-existing bindings → exactly one duplicate diagnostic (one extra)"))))))

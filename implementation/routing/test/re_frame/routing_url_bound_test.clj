(ns re-frame.routing-url-bound-test
  "Multi-frame URL-ownership tests for re-frame.routing: which frame
  `rf.routing/url-owner-frame-id` reports, the `:url-bound?` exclusivity hook
  and its duplicate-URL-binding diagnostic, and reconcile over frames that
  were bound before the hook was installed. The production push gate built on
  the owner is driven against a browser stub in `routing_history_cljs_test`.

  ## Posture split

  URL OWNERSHIP is production-real and carries no posture guard: which frame
  `url-owner-frame-id` reports, that a duplicate binding is STORED rather than
  rejected, and that reconcile fails CLOSED on an ambiguous multi-binding load
  order. Those run in the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane).

  The `:rf.error/duplicate-url-binding` DIAGNOSTIC is dev instrumentation —
  `trace/emit-error!` sits behind `rf.interop/debug-enabled?`, read once at load
  time — so its assertions sit inside `(when rf.interop/debug-enabled? …)`
  arms. One of them is NEGATIVE
  (`non-default-frame-without-url-bound-does-not-collide`): with no trace bus
  it would pass vacuously, so it is inside the arm with the ownership fact it
  is really about — `:rf/default` still owns the URL after both non-bound
  registrations — asserted outside."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.frame :as rf.frame]
            [re-frame.routing.url-bound :as rf.routing.url-bound]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- duplicate-binding-traces [traces]
  (filter #(= :rf.error/duplicate-url-binding (:operation %)) traces))

(deftest non-default-frame-without-url-bound-does-not-collide
  (testing "a non-default frame WITHOUT :url-bound? true — the story / devcard /
            test-fixture default — claims nothing and emits no duplicate-binding
            trace"
    (let [traces (atom [])]
      (rf/register-listener! :trace ::no-dup (fn [ev] (swap! traces conj ev)))
      (rf/make-frame {:id :story/variant-A})
      (rf/make-frame {:id :test/fixture :url-bound? false})
      (rf/unregister-listener! :trace ::no-dup)
      (is (= :rf/default (rf.routing/url-owner-frame-id)))
      (when rf.interop/debug-enabled?
        (is (empty? (duplicate-binding-traces @traces)))))))

(deftest single-non-default-frame-owns-url-when-default-opts-out
  (testing "with :rf/default opted OUT (:url-bound? false), the lone
            :url-bound? true non-default frame owns the URL — the step-deck
            ownership contract (Spec 012 §Multi-frame routing)"
    (rf/make-frame {:id :rf/default :url-bound? false})
    (rf/make-frame {:id :step-deck :url-bound? true})
    (is (= :step-deck (rf.routing/url-owner-frame-id)))))

(deftest one-conflict-emits-one-duplicate-binding-after-repeated-reloads
  (testing "after reloading the routing facade, a single conflicting URL-bound
            registration emits EXACTLY ONE duplicate-url-binding diagnostic: the
            lifecycle hook is published key-idempotently, so a reload replaces it
            rather than stacking a second copy"
    (require 're-frame.routing :reload)
    (require 're-frame.routing :reload)
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dup-once (fn [ev] (swap! traces conj ev)))
      ;; :rf/default is implicitly :url-bound? true; one second binding is one conflict.
      (rf/make-frame {:id :my-conflicting-frame :url-bound? true})
      (rf/unregister-listener! :trace ::dup-once)
      (is (= :rf/default (rf.routing/url-owner-frame-id)))
      (when rf.interop/debug-enabled?
        (is (= [{:offending-frame :my-conflicting-frame :existing-frame :rf/default}]
               (map #(select-keys (:tags %) [:offending-frame :existing-frame])
                    (duplicate-binding-traces @traces))))))))

(deftest duplicate-sorting-before-incumbent-does-not-steal-ownership
  (testing "a second :url-bound? true frame whose id sorts BEFORE the incumbent
            (:aaa-early < :rf/default) is stored, and the incumbent keeps the URL
            — an alphabetical resolver would hand it to :aaa-early"
    (rf/make-frame {:id :aaa-early :url-bound? true})
    (is (true? (:url-bound? (rf.frame/frame-meta :aaa-early)))
        "the duplicate binding is stored, not rejected")
    (is (= :rf/default (rf.routing/url-owner-frame-id)))))

;; ---- frames bound before the hook was installed ---------------------------
;;
;; The `:routing/on-frame-registered!` hook does not replay registrations made
;; before `re-frame.routing` loaded, so such a frame has no recorded claim. The
;; façade runs `reconcile-existing-url-bindings!` at load to seed a sole
;; pre-existing binding, and the resolver fails closed (nil) on two or more
;; rather than sorting by id. A JVM test cannot register frames before the
;; routing namespace loads, so these reproduce that state: bound frames in the
;; store and an empty claim order.

(deftest reconcile-seeds-sole-pre-existing-incumbent
  (testing "reconcile seeds the SOLE pre-existing :url-bound? true frame as the
            incumbent, so a later, earlier-sorting duplicate cannot steal it"
    (rf/make-frame {:id :rf/default :url-bound? false})
    (rf/make-frame {:id :zz-incumbent :url-bound? true})
    (rf.routing/reset-url-claims!)
    (rf.routing.url-bound/reconcile-existing-url-bindings!)
    (is (= :zz-incumbent (rf.routing/url-owner-frame-id)))
    (rf/make-frame {:id :aaa-stealer :url-bound? true})
    (is (= :zz-incumbent (rf.routing/url-owner-frame-id))
        "the earlier-sorting later duplicate does NOT steal")))

(deftest reconcile-multi-pre-existing-fails-closed-and-diagnoses
  (testing "when MULTIPLE :url-bound? true frames pre-exist with unrecoverable
            claim order, reconcile fails closed (no owner) and emits a
            duplicate-url-binding diagnostic per extra binding, rather than
            picking the alphabetically-first :aa-late"
    (rf/make-frame {:id :rf/default :url-bound? false})
    (rf/make-frame {:id :zz-incumbent :url-bound? true})
    (rf/make-frame {:id :aa-late :url-bound? true})
    (rf.routing/reset-url-claims!)
    (let [traces (atom [])]
      (rf/register-listener! :trace ::reconcile-dup (fn [ev] (swap! traces conj ev)))
      (rf.routing.url-bound/reconcile-existing-url-bindings!)
      (rf/unregister-listener! :trace ::reconcile-dup)
      (is (nil? (rf.routing/url-owner-frame-id)))
      (when rf.interop/debug-enabled?
        (is (= 1 (count (duplicate-binding-traces @traces)))
            "two pre-existing bindings → exactly one duplicate diagnostic")))))

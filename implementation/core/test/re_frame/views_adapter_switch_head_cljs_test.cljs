(ns re-frame.views-adapter-switch-head-cljs-test
  "rf2-oz7wr — the head cache across an ADAPTER SWITCH.

  `rf.views/view-head` derives `(rf/view id)` from (registration × installed
  substrate) and memoises it, because both substrate hooks it consults are
  routed and the canonical boot order registers views before `rf/init!`.
  The registrar slot is deliberately NOT rewritten on a re-derivation
  (`rf.registrar/register!` emits `:rf.registry/handler-replaced` on every call,
  so re-registering at lookup would publish a phantom hot-reload), so the
  cache must recognise its own registration by an IMMUTABLE token — the exact
  object the slot still holds — rather than by whatever the latest derivation
  produced.

  The defect this file pins, in the shape the audit recorded it:

    A post-init registration under substrate A1 composes wrapper W into
    componentized head H1; the slot stores H1 and the cache stores
    {W, H1, A1}. After dispose/install A2 the FIRST lookup correctly misses,
    derives H2, overwrites the cache with {W, H2, A2} and returns H2 —
    leaving the slot at H1. On the SECOND lookup the slot's H1 is identical
    to neither W nor the cache's now-current H2, so `view-head` classified
    its own registration as FOREIGN and handed back the stale H1. Switching
    a componentizing substrate for a non-componentizing one failed the same
    way: W once, then the old A1-marked H1.

  Every pre-existing row starts from an UNCOMPONENTIZED registrar seed (the
  boot-order rows register with no adapter installed, so the slot holds the
  bare wrapper and the misclassification cannot arise). These rows start from
  a post-init COMPONENTIZED slot, which is what makes them able to see it.

  What each row is for:

    - `post-init-componentized-head-survives-*` — the audit's exact shape.
      Two consecutive lookups after a componentizing → componentizing switch;
      the second must still answer A2's head, not the stale H1.
    - `switch-to-a-non-componentizing-substrate-*` — the UIx → Reagent half
      of the same defect. The re-derivation reuses the registration's own
      wrapper, so both lookups must answer W and neither may answer H1.
    - `foreign-view-slot-*` / `foreign-re-registration-*` — the pass-through
      the repair must NOT trade away. A `:view` slot this ns did not build is
      handed back exactly as stored, including when a stale cache entry for
      that id still describes an earlier views-composed registration. These
      two rows never reach the re-derivation branch, so they are also the
      NON-VACUITY CONTROL: when the token handling is broken they stay GREEN
      while the two rows above go red, which is what makes that red
      attributable to the seam under test rather than to the harness.

  Substrate-agnostic by construction: the adapters are inert maps and the
  `:adapter/componentize-view` impls are marked forwarding shells modelled on
  `spine/make-componentize-view`, routed through the real
  `substrate-adapter/route-hook!`. Nothing here mounts, so this is a headless
  node row — the property under test is WHICH OBJECT a lookup returns, not
  rendered output.

  ns ends in `-cljs-test` so shadow-cljs's `:node-test` build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views :as rf.views]))

;; ---- inert test substrates -------------------------------------------------
;;
;; `:kind` is `:custom`, so `substrate-adapter/same-adapter?` routes these by
;; OBJECT IDENTITY (a canonical `:rf.adapter/*` kind would route by token and
;; conflate copies). Three distinct maps ⇒ three distinct substrates for both
;; hook routing and the head cache's adapter half.

(def ^:private adapter-a1 {:kind :custom :rf.test/substrate :a1})
(def ^:private adapter-a2 {:kind :custom :rf.test/substrate :a2})
(def ^:private adapter-plain {:kind :custom :rf.test/substrate :plain})

(def ^:private marker-prop "rf2Oz7wrShellMarker")

(defn- shell-marker
  "Which test substrate componentized `x`, or nil when nothing did.
  Reads through `goog.object/get` rather than `aget` so a `MetaFn` (an IFn
  OBJECT, which is exactly what an un-componentized head is) answers nil
  instead of throwing."
  [x]
  (when (some? x) (gobj/get x marker-prop)))

(defn- make-componentize-view
  "An `:adapter/componentize-view` impl in the shape
  `spine/make-componentize-view` publishes — a forwarding shell built fresh
  per derivation — stamped with `marker` so a returned head names the
  substrate that produced it."
  [marker]
  (fn componentize-view [_id _metadata wrapped]
    (let [shell (fn view-component [& args] (apply wrapped args))]
      (gobj/set shell marker-prop marker)
      shell)))

;; Routed, not `set-fn!`'d: routing is the production mechanism, and it keeps
;; these impls inert for every other namespace in the shared node bundle —
;; each fires only while ITS map is the installed adapter and otherwise chains
;; on. `adapter-plain` deliberately publishes nothing, so under it the chain
;; reaches its nil fallback and `apply-adapter-componentize-view` keeps the
;; wrapper as the head (the Reagent shape).
(defonce ^:private routed-componentize-hooks
  (do (rf.substrate.adapter/route-hook! adapter-a1 :adapter/componentize-view
                           (make-componentize-view :a1))
      (rf.substrate.adapter/route-hook! adapter-a2 :adapter/componentize-view
                           (make-componentize-view :a2))
      true))

(defn- render-fn [& _args] [:div "row"])

(defn- switch-adapter! [next-adapter]
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! next-adapter))

(defn- slot-handler [id]
  (:handler-fn (rf.registrar/lookup :view id)))

;; ---- fixture ---------------------------------------------------------------
;; The runtime fixture rolls the registrar back around each test (so the view
;; ids registered below never leak into the shared bundle) and disposes any
;; installed adapter on the way in; no `:adapter` is supplied because each row
;; installs and switches its own. The cold-adapter tail leaves the slot in a
;; never-installed state for whatever namespace runs next.

(use-fixtures :each
  (let [reset-runtime (rf.test-support/make-reset-runtime-fixture {})]
    (fn [t]
      (reset-runtime
        (fn []
          (try
            (t)
            (finally
              (rf.substrate.adapter/dispose-adapter!)
              (rf.substrate.adapter/reset-lifecycle-state-for-tests!))))))))

;; ---- the audit's shape: componentizing → componentizing --------------------

(deftest post-init-componentized-head-survives-an-adapter-switch
  (testing "two consecutive lookups after a switch both answer the NEW substrate's head (rf2-oz7wr)"
    (rf.substrate.adapter/install-adapter! adapter-a1)
    (let [h1 (rf.views/reg-view* ::switch-row {} render-fn)]
      ;; Premises. Without these the row could silently decay into the
      ;; boot-order shape, where the slot holds the uncomponentized seed and
      ;; the misclassification cannot occur at all.
      (is (= :a1 (shell-marker h1))
          "precondition: registering AFTER install produced A1's componentized head H1")
      (is (identical? h1 (slot-handler ::switch-row))
          "precondition: the registrar slot holds the COMPONENTIZED head H1")
      (is (identical? h1 (rf/view ::switch-row))
          "precondition: while A1 is installed the lookup is a cache hit on H1")

      (switch-adapter! adapter-a2)
      (is (identical? h1 (slot-handler ::switch-row))
          "precondition: the switch deliberately leaves the registrar slot at H1")

      (let [first-lookup  (rf/view ::switch-row)
            second-lookup (rf/view ::switch-row)]
        (is (= :a2 (shell-marker first-lookup))
            "the first lookup re-derives the head against A2")
        (is (not (identical? h1 first-lookup))
            "the first lookup is not the A1-era head")

        ;; The regression. Pre-fix this answered H1: the slot's H1 matched
        ;; neither the re-derived wrapper nor the cache's now-current H2, so
        ;; the entry was classified foreign and the slot was served raw.
        (is (= :a2 (shell-marker second-lookup))
            "the SECOND consecutive lookup still answers A2's head, not the stale A1 head")
        (is (not (identical? h1 second-lookup))
            "the second lookup is not the stale registrar head H1")
        (is (identical? first-lookup second-lookup)
            "identity is stable within an adapter generation, so React reconciles one component type")
        (is (identical? first-lookup (rf/view ::switch-row))
            "and stays stable on every further lookup")))))

;; ---- the same defect, componentizing → NON-componentizing ------------------

(deftest switch-to-a-non-componentizing-substrate-does-not-fall-back
  (testing "a componentizing → non-componentizing switch answers the wrapper on BOTH lookups (rf2-oz7wr)"
    (rf.substrate.adapter/install-adapter! adapter-a1)
    (let [h1 (rf.views/reg-view* ::to-plain-row {} render-fn)]
      (is (= :a1 (shell-marker h1))
          "precondition: the registrar slot holds A1's componentized head H1")

      (switch-adapter! adapter-plain)
      (is (identical? h1 (slot-handler ::to-plain-row))
          "precondition: the switch leaves the registrar slot at H1")

      (let [first-lookup  (rf/view ::to-plain-row)
            second-lookup (rf/view ::to-plain-row)]
        (is (nil? (shell-marker first-lookup))
            "the substrate publishes no componentize hook, so the head is the wrapper W")
        (is (not (identical? h1 first-lookup))
            "the first lookup is not the A1-marked head")

        ;; Pre-fix this answered H1 — an A1-marked shell served to a substrate
        ;; that has no idea what that marker means.
        (is (nil? (shell-marker second-lookup))
            "the SECOND consecutive lookup is still W, not the A1-marked head")
        (is (not (identical? h1 second-lookup))
            "the second lookup is not the stale registrar head H1")
        (is (identical? first-lookup second-lookup)
            "identity is stable within the new adapter generation")))))

;; ---- foreign-slot pass-through (and the harness control) -------------------

(deftest foreign-view-slot-passes-through-untouched-across-a-switch
  (testing "a :view slot this ns did not build is handed back exactly as stored"
    (rf.substrate.adapter/install-adapter! adapter-a1)
    (let [foreign (fn foreign-view [& _args] [:div "foreign"])]
      (rf.registrar/register! :view ::foreign-row {:handler-fn foreign})
      (is (identical? foreign (rf/view ::foreign-row))
          "a hand-rolled registration is never componentized")
      (is (identical? foreign (rf/view ::foreign-row))
          "and the second consecutive lookup answers the same object")

      (switch-adapter! adapter-a2)
      (is (identical? foreign (rf/view ::foreign-row))
          "an adapter switch does not adopt a slot this ns did not build")
      (is (identical? foreign (rf/view ::foreign-row))
          "and the second lookup after the switch answers it too")
      (is (nil? (shell-marker (rf/view ::foreign-row)))
          "nothing stamped a substrate shell onto the foreign handler"))))

(deftest foreign-re-registration-over-a-composed-slot-passes-through
  (testing "a hand-rolled register! that REPLACES a composed slot wins over the stale cache entry"
    (rf.substrate.adapter/install-adapter! adapter-a1)
    (let [h1      (rf.views/reg-view* ::hijacked-row {} render-fn)
          foreign (fn foreign-view [& _args] [:div "foreign"])]
      (is (= :a1 (shell-marker h1))
          "precondition: the cache entry for this id describes a views-composed registration")
      ;; The cache still holds {W, H1, A1} for this id; the slot no longer does.
      (rf.registrar/register! :view ::hijacked-row {:handler-fn foreign})

      (switch-adapter! adapter-a2)
      (is (identical? foreign (rf/view ::hijacked-row))
          "the replaced slot is served raw, not re-derived from the superseded registration")
      (is (identical? foreign (rf/view ::hijacked-row))
          "and the second consecutive lookup answers the same object")
      (is (nil? (shell-marker (rf/view ::hijacked-row)))
          "no substrate shell was stamped onto the foreign handler")
      (is (not (identical? h1 (rf/view ::hijacked-row)))
          "and the superseded head H1 is never resurrected"))))

(ns re-frame.substrate.custom-nonatom-container-cljs-test
  "Spec 006 §`make-derived-value` — a CUSTOM adapter whose legitimate base
  container is NOT atom-shaped must have its base-container writes delegated,
  not rejected as derived (rf2-oitw37).

  The adapter contract states the container is OPAQUE to the core (Spec 006
  §`make-state-container`) and custom adapters are the canonical way to
  bridge external reactive sources (Spec 006 §The adapter API contract). A
  conforming custom adapter may return a base container that is a JS class
  instance, a signal/store object, or a host record — none of which satisfy
  the host `IAtom` marker protocol.

  Before rf2-oitw37 the core's `replace-container!` choke point classified
  any non-`IAtom` container as DERIVED via an unconditional atom-marker
  fall-back (`(not IAtom)`) that OVERRODE even a published
  `:adapter/derived-container?` hook's `false` (\"this is a base
  container\") answer. So a write to such a custom base container threw
  `:rf.error/derived-container-replaced` before the adapter's own
  `:replace-container!` could run.

  The fix makes the installed adapter's `:adapter/derived-container?` hook
  authoritative whenever it has an opinion (truthy = derived → reject;
  `false` = base → delegate); the atom-marker heuristic is consulted only
  when the installed adapter returns the
  `re-frame.substrate.adapter/container-class-unknown` sentinel (no
  opinion). This suite installs a custom non-atom-base adapter and asserts:

    1. `replace-container!` on its NON-ATOM base container DELEGATES and
       succeeds (not misclassified / rejected).
    2. `replace-container!` on its derived value is STILL rejected with the
       canonical `:rf.error/derived-container-replaced` throw — the guard
       against genuinely-derived writes is not weakened.

  Per bead rf2-oitw37."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.test-support :as test-support]))

;; ---- a custom adapter whose base container is NOT atom-shaped --------------
;;
;; `Cell` is the base, writable container: a host object that holds a value
;; but does NOT satisfy `IAtom` (so the choke point's atom-marker heuristic
;; would mis-read it as derived). It is backed by a private `clojure.core`
;; atom held as an immutable field so the value is mutable cross-platform
;; without the JVM `(set! (.-field …))` restriction — the IMPORTANT property
;; for this suite is that the `Cell` instance itself is not an `IAtom`, which
;; an `(instance? IAtom (->Cell …))` precondition asserts. `DerivedCell` is
;; the adapter's `make-derived-value` result — read-only; writing to it is
;; the programmer error the guard must still catch. Neither type is an
;; `IAtom`, so the atom-marker heuristic alone cannot tell them apart —
;; exactly the case the adapter's `:adapter/derived-container?` hook resolves.

(deftype Cell [backing]
  #?(:clj clojure.lang.IDeref :cljs IDeref)
  (#?(:clj deref :cljs -deref) [_] @backing))

(defn- cell-set! [^Cell c new-value]
  (reset! (.-backing c) new-value)
  nil)

(deftype DerivedCell [sources compute-fn]
  #?(:clj clojure.lang.IDeref :cljs IDeref)
  (#?(:clj deref :cljs -deref) [_]
    (apply compute-fn (map deref sources))))

(def ^:private custom-adapter
  "A minimal custom adapter whose base container (`Cell`) is NOT an `IAtom`.
  Carries a `:custom` kind so `route-hook!` routes its hook by object
  identity (the same map object is installed below)."
  {:kind                 :custom
   :make-state-container (fn [initial-value] (Cell. (atom initial-value)))
   :read-container       (fn [c] (deref c))
   :replace-container!   (fn [c new-value] (cell-set! c new-value))
   :make-derived-value   (fn [sources compute-fn] (DerivedCell. sources compute-fn))
   ;; render / render-to-string / dispose-adapter! are unused by this suite;
   ;; stub them so the spec map is shape-complete.
   :render               (fn [_ _ _] nil)
   :render-to-string     (fn [_ _] "")
   :dispose-adapter!     (fn [] nil)})

;; ---- fixture --------------------------------------------------------------
;; Adapter-less base fixture: each test installs `custom-adapter` itself and
;; publishes the routed `:adapter/derived-container?` hook, then tears down.
;; Using `make-reset-runtime-fixture` (with no `:adapter`) keeps the
;; ns-load registrar baseline intact across the shared node-test process.

(use-fixtures :each (test-support/make-reset-runtime-fixture {}))

(defn- install-custom-adapter! []
  ;; Cold install of the custom adapter, then publish its container-class
  ;; hook the way any custom adapter would: a routed hook that answers
  ;; truthy for ITS derived value and `false` for ITS base container, with
  ;; the chain-bottom fallback returning the no-opinion sentinel.
  (adapter/dispose-adapter!)
  (adapter/install-adapter! custom-adapter)
  (adapter/route-hook! custom-adapter :adapter/derived-container?
    (fn custom-derived? [c]
      (cond
        (instance? DerivedCell c) true
        (instance? Cell c)        false
        :else                     adapter/container-class-unknown))
    (constantly adapter/container-class-unknown)))

;; ---- tests ----------------------------------------------------------------

(deftest custom-nonatom-base-container-is-accepted
  (testing "replace-container! DELEGATES to a custom adapter whose base container is not IAtom (rf2-oitw37)"
    (install-custom-adapter!)
    (let [c (adapter/make-state-container {:n 0})]
      (is (false? #?(:clj  (instance? clojure.lang.IAtom c)
                     :cljs (satisfies? IAtom c)))
          "precondition: the custom base container is NOT atom-shaped")
      (is (= {:n 0} (adapter/read-container c)) "precondition: reads its seeded value")
      (is (nil? (adapter/replace-container! c {:n 1}))
          "replace-container! on the non-atom base container is delegated and returns nil — NOT rejected as derived")
      (is (= {:n 1} (adapter/read-container c))
          "the adapter's own replace-container! ran: the base container holds the new value"))))

(deftest custom-derived-container-is-still-rejected
  (testing "replace-container! on the custom adapter's DERIVED value still throws (guard not weakened, rf2-oitw37)"
    (install-custom-adapter!)
    (let [src     (adapter/make-state-container {:n 7})
          derived (adapter/make-derived-value [src] (fn [v] (:n v)))]
      (is (= 7 (adapter/read-container derived))
          "precondition: the derived value reads its computed value")
      (let [thrown (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                                (adapter/replace-container! derived 42))
                       "writing to the custom derived value throws")]
        (is (= :rf.error/derived-container-replaced
               (:rf.error/id (ex-data thrown)))
            "the thrown ex-info carries the canonical :rf.error/id discriminator")
        (is (= 'rf/replace-container! (:where (ex-data thrown)))
            "the :where slot names the user-facing surface fn"))
      ;; The rejected write must not have mutated the derived value, and the
      ;; source remains writable (the guard fires only on the derived shape).
      (is (= 7 (adapter/read-container derived))
          "the rejected write did not mutate the derived value")
      (adapter/replace-container! src {:n 8})
      (is (= 8 (adapter/read-container derived))
          "writing to the source recomputes the derived value normally"))))

(deftest no-opinion-falls-back-to-atom-marker-heuristic
  (testing "when the installed adapter returns the container-class-unknown sentinel, the choke point uses the atom-marker heuristic (rf2-oitw37)"
    ;; Install the custom adapter but DON'T publish a real container-class
    ;; hook — route only the sentinel fallback. An IAtom base then classifies
    ;; as base (delegated); a non-IAtom value classifies as derived (rejected)
    ;; via the heuristic. This pins the fallback arm so the fix's three-way
    ;; branch is fully covered.
    (adapter/dispose-adapter!)
    (let [atom-adapter {:kind                 :custom
                        :make-state-container (fn [v] (atom v))
                        :read-container       deref
                        :replace-container!   (fn [c v] (reset! c v) nil)
                        :make-derived-value   (fn [sources compute-fn]
                                                (reify #?(:clj clojure.lang.IDeref :cljs IDeref)
                                                  (#?(:clj deref :cljs -deref) [_]
                                                    (apply compute-fn (map deref sources)))))
                        :render               (fn [_ _ _] nil)
                        :render-to-string     (fn [_ _] "")
                        :dispose-adapter!     (fn [] nil)}]
      (adapter/install-adapter! atom-adapter)
      (adapter/route-hook! atom-adapter :adapter/derived-container?
        (constantly adapter/container-class-unknown)
        (constantly adapter/container-class-unknown))
      (let [base    (adapter/make-state-container {:n 0})
            derived (adapter/make-derived-value [base] (fn [v] (:n v)))]
        (is (nil? (adapter/replace-container! base {:n 1}))
            "an IAtom base container is delegated under the sentinel/heuristic path")
        (is (= {:n 1} (adapter/read-container base)) "the base write took effect")
        (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                     (adapter/replace-container! derived 99))
            "a non-IAtom derived value is rejected by the atom-marker heuristic")))))

(deftest sentinel-distinct-from-false
  (testing "the container-class-unknown sentinel is NOT false (the bug-root distinction, rf2-oitw37)"
    (is (not= false adapter/container-class-unknown)
        "the sentinel must be distinguishable from a genuine `false` (base) verdict")
    (is (keyword? adapter/container-class-unknown)
        "the sentinel is a namespaced keyword")))

;; Touch the hook table so a linter does not flag `late-bind` as unused on a
;; host where the other arms elide it.
(deftest hook-key-is-resolvable-after-publish
  (testing "the routed :adapter/derived-container? hook is present after a custom adapter publishes it"
    (install-custom-adapter!)
    (is (some? (late-bind/get-fn :adapter/derived-container?))
        "the custom adapter's routed hook is published in the late-bind table")))

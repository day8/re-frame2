(ns re-frame.substrate.custom-nonatom-container-cljs-test
  "Spec 006 §`make-derived-value`: a CUSTOM adapter's base container need not be
  atom-shaped (the container is OPAQUE to the core, Spec 006
  §`make-state-container`). The installed adapter's `:adapter/derived-container?`
  hook is authoritative whenever it has an opinion (truthy rejects, `false`
  delegates); the atom-marker heuristic is consulted only on the
  `container-class-unknown` sentinel. An unconditional `(not IAtom)` fall-back
  would reject every write to such a base container."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.test-support :as rf.test-support]))

;; `Cell` is the writable base container and `DerivedCell` the read-only
;; derived value. Neither is an `IAtom`, so only the adapter's hook can tell
;; them apart.

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
   :render               (fn [_ _ _] nil)
   :render-to-string     (fn [_ _] "")
   :dispose-adapter!     (fn [] nil)})

(use-fixtures :each (rf.test-support/make-reset-runtime-fixture {}))

(defn- install-custom-adapter! []
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! custom-adapter)
  (rf.substrate.adapter/route-hook! custom-adapter :adapter/derived-container?
    (fn custom-derived? [c]
      (cond
        (instance? DerivedCell c) true
        (instance? Cell c)        false
        :else                     rf.substrate.adapter/container-class-unknown))
    (constantly rf.substrate.adapter/container-class-unknown)))

;; ---- tests ----------------------------------------------------------------

(defn- rejection-id [thunk]
  (try (thunk) ::no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (:rf.error/id (ex-data e)))))

(deftest custom-nonatom-base-container-is-accepted
  (install-custom-adapter!)
  (let [c (rf.substrate.adapter/make-state-container {:n 0})]
    (is (= [nil {:n 1}]
           [(rf.substrate.adapter/replace-container! c {:n 1})
            (rf.substrate.adapter/read-container c)]))))

(deftest custom-derived-container-is-still-rejected
  (install-custom-adapter!)
  (let [src (rf.substrate.adapter/make-state-container {:n 7})]
    (is (= :rf.error/derived-container-replaced
           (rejection-id #(rf.substrate.adapter/replace-container!
                            (rf.substrate.adapter/make-derived-value [src] :n) 42))))))

(deftest no-opinion-falls-back-to-atom-marker-heuristic
  ;; Only the sentinel fallback is routed, so the choke point classifies by the
  ;; atom marker: the IAtom base is delegated, the non-IAtom derived rejected.
  ;; On CLJS a delegated write to the IDeref-only derived value would throw a
  ;; missing-protocol error, so the guard's own id is what says it rejected.
  (rf.substrate.adapter/dispose-adapter!)
  (let [writes       (atom 0)
        atom-adapter {:kind                 :custom
                      :make-state-container (fn [v] (atom v))
                      :read-container       deref
                      :replace-container!   (fn [c v] (swap! writes inc) (reset! c v) nil)
                      :make-derived-value   (fn [sources compute-fn]
                                              (reify #?(:clj clojure.lang.IDeref :cljs IDeref)
                                                (#?(:clj deref :cljs -deref) [_]
                                                  (apply compute-fn (map deref sources)))))
                      :render               (fn [_ _ _] nil)
                      :render-to-string     (fn [_ _] "")
                      :dispose-adapter!     (fn [] nil)}]
    (rf.substrate.adapter/install-adapter! atom-adapter)
    (rf.substrate.adapter/route-hook! atom-adapter :adapter/derived-container?
      (constantly rf.substrate.adapter/container-class-unknown)
      (constantly rf.substrate.adapter/container-class-unknown))
    (let [base    (rf.substrate.adapter/make-state-container {:n 0})
          derived (rf.substrate.adapter/make-derived-value [base] :n)]
      (is (= [nil {:n 1} :rf.error/derived-container-replaced 1]
             [(rf.substrate.adapter/replace-container! base {:n 1})
              (rf.substrate.adapter/read-container base)
              (rejection-id #(rf.substrate.adapter/replace-container! derived 99))
              @writes])))))

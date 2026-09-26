(ns re-frame.machine-source-unstamped-fn-free-cljs-test
  "Which specs raise the dev-only advisory `:rf.warning/machine-source-unstamped`
  when they carry no fn, on each platform.

  The advisory fires when a spec arrives at the registration home without the
  source metadata the `reg-machine` / `defmachine` literal walk would have
  stamped onto it. What that walk can stamp depends on the reader:

    - The CLJS reader positions every map literal, so the walk stamps the root
      of ANY literal spec. A fn-free inline literal or `defmachine` value arrives
      source-bearing and is silent; the same spec registered from a plain `def`
      arrives source-blind and warns.
    - The JVM reader positions only list forms, so the walk stamps only the fns —
      `:guards` / `:actions` entries and inline `:entry` / `:exit` / `:guard` /
      `:action` fns. A fn-free spec gives it nothing to stamp and arrives
      source-blind from every spelling, `defmachine` and an inline literal
      included, so the advisory is silent: its fix would change nothing.

  A fn-bearing spec registered from a plain `def` lacks the stamps the walk
  would have put on its fns, so it warns on both platforms.

  The file is named `*-cljs-test.cljc` so it's discovered by both
  cognitect-style JVM runs and shadow-cljs (`cljs-test$` ns-regexp)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Load the machines artefact so its late-bind hooks are installed when this
   ;; ns runs in isolation.
   [re-frame.machines]
   [re-frame.machines.lifecycle-fx.registration :as rf.machines.lifecycle-fx.registration]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture
  ;; Start each case from a clean once-per-id slate.
  (fn [t] (rf.machines.lifecycle-fx.registration/clear-source-unstamped-warned!) (t)))

(defn- warned-ids []
  (mapv #(:machine-id (:tags %))
        (rf.machines.test-support/events-of :rf.warning/machine-source-unstamped)))

;; No `:guards`, no `:actions`, no inline fns.
(def ^:private fn-free-spec
  {:initial :idle
   :states  {:idle {:on {:go :done}}
             :done {}}})

(rf/defmachine fn-free-defmachine
  {:initial :idle
   :states  {:idle {:on {:go :done}}
             :done {}}})

;; Its only fn is an inline `:entry`, which the literal walk stamps on both
;; platforms.
(def ^:private inline-fn-spec
  {:initial :idle
   :states  {:idle {:entry (fn [_] nil)
                    :on    {:go :done}}
             :done {}}})

(def ^:private fn-bearing-spec
  {:initial :idle
   :guards  {:ok? (fn [_] true)}
   :actions {:go  (fn [_] {})}
   :states  {:idle {:on {:go {:target :done :guard :ok? :action :go}}}
             :done {}}})

(deftest fn-free-inline-literal-is-silent
  (testing "an inline fn-free literal is silent on both platforms"
    (rf/reg-machine :fn-free/inline
      {:initial :idle
       :states  {:idle {:on {:go :done}}
                 :done {}}})
    (is (= [] (warned-ids)))))

(deftest fn-free-defmachine-value-is-silent
  (testing "a fn-free defmachine value is silent on both platforms"
    (rf/reg-machine :fn-free/defm fn-free-defmachine)
    (is (= [] (warned-ids)))))

(deftest fn-free-plain-def-warns-only-where-a-stamp-was-possible
  (testing "a fn-free spec from a plain def warns on CLJS, where defmachine
            would have stamped its root, and is silent on the JVM, where no
            spelling stamps it"
    (rf/reg-machine :fn-free/def fn-free-spec)
    (is (= #?(:cljs [:fn-free/def] :clj []) (warned-ids)))))

(deftest inline-fn-plain-def-warns
  (testing "a plain def whose only fn is an inline :entry warns on both
            platforms — the literal walk would have stamped that fn's source"
    (rf/reg-machine :inline-fn/def inline-fn-spec)
    (is (= [:inline-fn/def] (warned-ids)))))

(deftest fn-bearing-plain-def-warns
  (testing "a plain def carrying :guards / :actions fns warns on both platforms"
    (rf/reg-machine :fn-bearing/def fn-bearing-spec)
    (is (= [:fn-bearing/def] (warned-ids)))))

(ns re-frame.substrate.spine-construction-atomic-cljs-test
  "`make-derived-value` on the React-hook spine is failure-atomic (Spec 006
  §`make-derived-value`): a construction that throws before returning removes
  every wire it installed, in REVERSE acquisition order, attempts every release
  even if one throws, and re-raises the PRIMARY construction error. Otherwise
  the earlier sources' watches would be unreachable, marking a derived value
  nobody holds dirty for the lifetime of its sources.

  The fixtures mix a real atom (the fan-out coordinator path) with `reify`
  sources (the direct `add-watch` path) whose watch installation or removal
  can be armed to throw, and read residue off the atom's own `.-watches` and
  the scheduler's `:source-coordinators` registry.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.disposable :as rf.disposable]
            [re-frame.substrate.spine :as rf.substrate.spine]))

;; `arm` :add-throws makes `-add-watch` refuse; :remove-throws makes
;; `-remove-watch` throw after logging the release attempt.
(deftype FaultSource [value installed arm log label]
  IDeref
  (-deref [_] value)
  IWatchable
  (-add-watch [this k _f]
    (when (= :add-throws arm)
      (throw (ex-info "source refused a watch" {::fault label})))
    (swap! installed conj k)
    this)
  (-remove-watch [_ k]
    (swap! log conj label)
    (when (= :remove-throws arm)
      (throw (ex-info "source refused to release a watch" {::fault label})))
    (swap! installed disj k)
    nil))

(defn- fault-source [label value arm log]
  (FaultSource. value (atom #{}) arm log label))

(defn- installed-keys [^FaultSource s] @(.-installed s))

(defn- atom-watch-count [a] (count (.-watches a)))

(defn- make-derived-fn [scheduler]
  (rf.substrate.spine/make-derived-value-fn "rf-atomic-" scheduler))

(defn- coordinator-count [scheduler]
  (.-size (:source-coordinators scheduler)))

(defn- fault-label [thunk]
  (try (thunk) ::no-throw (catch :default e (::fault (ex-data e)))))

(deftest partial-installation-unwinds-every-earlier-wire
  (let [scheduler (rf.substrate.spine/make-scheduler)
        log       (atom [])
        src-a     (atom 1)
        src-b     (fault-source :b 2 nil log)
        src-c     (fault-source :c 3 :add-throws log)
        primary   (fault-label #((make-derived-fn scheduler) [src-a src-b src-c]
                                                              (fn [a b c] (+ a b c))))]
    (is (= [:c 0 0 #{}]
           [primary (atom-watch-count src-a) (coordinator-count scheduler)
            (installed-keys src-b)]))))

(deftest unwind-attempts-every-release-and-preserves-the-primary-error
  (let [scheduler (rf.substrate.spine/make-scheduler)
        log       (atom [])
        src-a     (atom 1)
        src-b     (fault-source :b 2 :remove-throws log)
        src-c     (fault-source :c 3 nil log)
        src-d     (fault-source :d 4 :add-throws log)
        primary   (fault-label #((make-derived-fn scheduler) [src-a src-b src-c src-d]
                                                              (fn [a b c d] (+ a b c d))))]
    ;; :d is replayed too: a source's key is recorded before its wire installs,
    ;; and releasing a never-installed wire is a tolerated no-op.
    (is (= [:d [:d :c :b] #{} 0 0]
           [primary @log (installed-keys src-c) (atom-watch-count src-a)
            (coordinator-count scheduler)]))))

(deftest unwind-releases-every-wire-of-a-repeated-source
  ;; `source-containers` carries no uniqueness precondition, so one source may
  ;; hold two wires.
  (let [scheduler (rf.substrate.spine/make-scheduler)
        src-a     (atom 1)
        src-c     (fault-source :c 2 :add-throws (atom []))]
    (fault-label #((make-derived-fn scheduler) [src-a src-a src-c] (fn [x y c] (+ x y c))))
    (is (= [0 0] [(atom-watch-count src-a) (coordinator-count scheduler)]))))

(deftest successful-construction-and-disposal-are-unchanged
  (let [scheduler (rf.substrate.spine/make-scheduler)
        log       (atom [])
        src-a     (atom 1)
        src-b     (fault-source :b 2 nil log)
        derived   ((make-derived-fn scheduler) [src-a src-b] (fn [a b] (+ a b)))]
    (is (= [3 1 1 1]
           [@derived (atom-watch-count src-a) (count (installed-keys src-b))
            (coordinator-count scheduler)]))
    (rf.disposable/-dispose derived)
    (is (= [0 #{} 0 [:b]]
           [(atom-watch-count src-a) (installed-keys src-b) (coordinator-count scheduler)
            @log]))))

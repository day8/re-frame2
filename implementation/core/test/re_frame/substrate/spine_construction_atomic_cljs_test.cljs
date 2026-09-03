(ns re-frame.substrate.spine-construction-atomic-cljs-test
  "rf2-vxgfnd.292 — `make-derived-value` on the React-hook spine must be
  INTERNALLY failure-atomic.

  THE DEFECT. The spine wires one input wire per source in a loop: a raw atom
  source joins that source's fan-out coordinator (creating the coordinator, and
  its single real watch, on first use); any other source takes a direct
  `add-watch`. The loop was unguarded. If a LATER source's installation threw,
  every EARLIER wire stayed installed while the constructor returned nothing —
  so the caller held no derived value, there was no `-dispose` to call, and no
  verb anywhere could reach those watches. An unreachable derived value went on
  marking itself dirty for the lifetime of its sources, and each retry added
  another set.

  THE CONTRACT (Spec 006 §`make-derived-value`): a `make-derived-value` that
  throws before returning has removed whatever it installed. The repair unwinds
  the acquired wires in REVERSE acquisition order, attempts every release even
  if one of them throws, and re-raises the PRIMARY construction error.

  These fixtures drive `make-derived-value-fn` directly over a mixed source
  vector — a real atom (the coordinator path) plus `reify` sources (the direct
  `add-watch` path) whose watch installation and removal can each be armed to
  throw. Residue is read WHITE-BOX off the ground truth: the atom's own
  `.-watches` map and the scheduler's `:source-coordinators` registry, not a
  mirror the fixture maintains.

  Pre-fix, `partial-installation-unwinds-every-earlier-wire` finds the first two
  sources still wired and the coordinator still registered, and FAILS.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.disposable :as rf.disposable]
            [re-frame.substrate.spine :as rf.substrate.spine]))

;; ---------------------------------------------------------------------------
;; A non-atom (`reify`) source — the spine's DIRECT `add-watch` branch.
;;
;; `installed` is the set of watch keys currently held on it: the leak surface.
;; `arm` selects the fault:
;;   :add-throws    — `-add-watch` throws INSTEAD of recording (models a host
;;                    container refusing a new dependent).
;;   :remove-throws — `-add-watch` records normally, but `-remove-watch` throws.
;;                    Used to prove the unwind attempts every release and still
;;                    surfaces the PRIMARY error rather than this secondary one.
;; ---------------------------------------------------------------------------

(deftype FaultSource [value installed arm log label]
  IDeref
  (-deref [_] value)
  IWatchable
  (-add-watch [this k _f]
    (when (= :add-throws @arm)
      (throw (ex-info "source refused a watch" {::fault label})))
    (swap! installed conj k)
    this)
  (-remove-watch [_ k]
    (swap! log conj label)
    (when (= :remove-throws @arm)
      (throw (ex-info "source refused to release a watch" {::fault label})))
    (swap! installed disj k)
    nil))

(defn- fault-source [label value arm log]
  (FaultSource. value (atom #{}) (atom arm) log label))

(defn- installed-keys [^FaultSource s] @(.-installed s))

(defn- atom-watch-count [a] (count (.-watches a)))

(defn- coordinator-count [scheduler]
  (.-size (:source-coordinators scheduler)))

(defn- fault-label [e] (::fault (ex-data e)))

(defn- caught [thunk]
  (try (thunk) ::no-throw (catch :default e e)))

;; ===========================================================================
;; The headline case: a later source's `add-watch` throws after earlier wires
;; are already installed.
;; ===========================================================================

(deftest partial-installation-unwinds-every-earlier-wire
  (testing "a third source that refuses its watch leaves ZERO residue from the
            first two — the raw atom's coordinator is torn down and deregistered,
            and the reify source's direct watch is removed"
    (let [scheduler    (rf.substrate.spine/make-scheduler)
          make-derived (rf.substrate.spine/make-derived-value-fn "rf-atomic-" scheduler)
          release-log  (atom [])
          src-a        (atom 1)                                  ;; coordinator path
          src-b        (fault-source :b 2 nil release-log)       ;; direct watch, healthy
          src-c        (fault-source :c 3 :add-throws release-log)
          outcome      (caught #(make-derived [src-a src-b src-c]
                                              (fn [a b c] (+ a b c))))]
      (is (= :c (fault-label outcome))
          "the PRIMARY construction error surfaces with its identity intact")
      (is (zero? (atom-watch-count src-a))
          "the raw atom source holds no watch — its fan-out coordinator lost its
           last dependent and tore down its single real watch. PRE-FIX the
           coordinator's watch survives and this is 1")
      (is (zero? (coordinator-count scheduler))
          "the coordinator REGISTRY entry is gone too — a stranded entry would
           keep the source (and its coordinator closure) reachable for the
           scheduler's lifetime. PRE-FIX this is 1")
      (is (empty? (installed-keys src-b))
          "the healthy reify source's direct watch was removed. PRE-FIX it is
           still held")
      (is (empty? (installed-keys src-c))
          "the refusing source never recorded a watch to begin with"))))

(deftest unwind-runs-in-reverse-acquisition-order
  (testing "releases replay the acquisition vector backwards, so a source
            acquired later is released first"
    (let [scheduler    (rf.substrate.spine/make-scheduler)
          make-derived (rf.substrate.spine/make-derived-value-fn "rf-atomic-" scheduler)
          release-log  (atom [])
          src-b        (fault-source :b 1 nil release-log)
          src-c        (fault-source :c 2 nil release-log)
          src-d        (fault-source :d 3 :add-throws release-log)]
      (caught #(make-derived [src-b src-c src-d] (fn [b c d] (+ b c d))))
      (is (= [:d :c :b] @release-log)
          "the unwind replays the RECORDED key vector newest-first. :d is in it
           because the loop records a source's key before installing its wire —
           so a source whose install threw is still replayed, and its release is
           a tolerated no-op. That tolerance is deliberate: it means the unwind
           never has to reason about how far the loop got"))))

(deftest unwind-attempts-every-release-and-preserves-the-primary-error
  (testing "a source that throws while being RELEASED does not abort the rest of
            the unwind, and does not displace the construction error"
    (let [scheduler    (rf.substrate.spine/make-scheduler)
          make-derived (rf.substrate.spine/make-derived-value-fn "rf-atomic-" scheduler)
          release-log  (atom [])
          src-a        (atom 1)
          src-b        (fault-source :b 2 :remove-throws release-log)
          src-c        (fault-source :c 3 nil release-log)
          src-d        (fault-source :d 4 :add-throws release-log)
          outcome      (caught #(make-derived [src-a src-b src-c src-d]
                                              (fn [a b c d] (+ a b c d))))]
      (is (= :d (fault-label outcome))
          "the PRIMARY error is the one that named the real fault — NOT :b's
           secondary release failure raised during the unwind")
      (is (= [:d :c :b] @release-log)
          "every release was attempted; :b's throw did not abort the pass")
      (is (empty? (installed-keys src-c))
          "the release AFTER the throwing one still ran")
      (is (zero? (atom-watch-count src-a))
          "and the raw atom source — released last — is clean")
      (is (zero? (coordinator-count scheduler))
          "no coordinator registry residue"))))

(deftest first-source-failure-leaves-nothing-behind
  (testing "when the very first source refuses, there is nothing to unwind and
            the error still surfaces unchanged"
    (let [scheduler    (rf.substrate.spine/make-scheduler)
          make-derived (rf.substrate.spine/make-derived-value-fn "rf-atomic-" scheduler)
          release-log  (atom [])
          src-a        (fault-source :a 1 :add-throws release-log)
          src-b        (atom 2)
          outcome      (caught #(make-derived [src-a src-b] (fn [a b] (+ a b))))]
      (is (= :a (fault-label outcome)))
      (is (= [:a] @release-log)
          "the one recorded key is replayed as a tolerated no-op; no wire had
           actually been installed")
      (is (zero? (atom-watch-count src-b))
          "the source AFTER the failure was never reached, so it is untouched")
      (is (zero? (coordinator-count scheduler))))))

;; ===========================================================================
;; Duplicate sources — `source-containers` carries no uniqueness precondition
;; (Spec 006 §154-170), so the same source may appear twice and take two wires.
;; The unwind must release BOTH, not just the last (the rf2-he7se hazard).
;; ===========================================================================

(deftest unwind-releases-every-wire-of-a-repeated-source
  (testing "a source appearing twice before the failure has BOTH of its wires
            released"
    (let [scheduler    (rf.substrate.spine/make-scheduler)
          make-derived (rf.substrate.spine/make-derived-value-fn "rf-atomic-" scheduler)
          release-log  (atom [])
          src-a        (atom 1)
          src-c        (fault-source :c 2 :add-throws release-log)]
      (caught #(make-derived [src-a src-a src-c] (fn [x y c] (+ x y c))))
      (is (zero? (atom-watch-count src-a))
          "the repeated raw atom source holds no watch — the coordinator shed
           BOTH dependent entries before tearing down")
      (is (zero? (coordinator-count scheduler))
          "and its registry entry is gone"))))

;; ===========================================================================
;; The happy path and steady-state disposal must be untouched by the guard.
;; ===========================================================================

(deftest successful-construction-and-disposal-are-unchanged
  (testing "no fault: every wire installs, the derived value computes, and
            `-dispose` still releases everything through the shared release path"
    (let [scheduler    (rf.substrate.spine/make-scheduler)
          make-derived (rf.substrate.spine/make-derived-value-fn "rf-atomic-" scheduler)
          release-log  (atom [])
          src-a        (atom 1)
          src-b        (fault-source :b 2 nil release-log)
          derived      (make-derived [src-a src-b] (fn [a b] (+ a b)))]
      (is (= 3 @derived) "the derived value computes from both sources")
      (is (= 1 (atom-watch-count src-a))
          "the raw atom carries its coordinator's single real watch")
      (is (= 1 (count (installed-keys src-b)))
          "the reify source carries its direct watch")
      (is (= 1 (coordinator-count scheduler)))
      (rf.disposable/-dispose derived)
      (is (zero? (atom-watch-count src-a)) "dispose released the atom source")
      (is (empty? (installed-keys src-b)) "dispose released the reify source")
      (is (zero? (coordinator-count scheduler))
          "dispose deregistered the coordinator")
      (is (= [:b] @release-log)
          "the steady-state dispose used the SAME release path as the unwind"))))

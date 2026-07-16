(ns re-frame.epoch-silencing-generation-emission-test
  "rf2-9bhne6 — the delayed-silence generation must stay AUTHORITATIVE THROUGH the
  trace emission that publishes it.

  ## The defect

  `on-frame-destroyed!` reserved a `(cb-id, generation)` silence under
  `silence-lock`, RELEASED that lock, and only then called the external
  `trace/emit!`. `put-listener!`, `drop-listener!`, `reset-listeners!` and the
  observation re-arm in `record-observation!` did not participate in the lock at
  all. So a registrar (a real JVM thread, or the deterministic seam these tests
  drive) could replace generation G with a fresh generation H in the window
  between the reservation and the emit. The unqualified `{:frame :cb-id}` silence
  then fired for a cb whose current generation is H — a fresh generation that
  never observed the destroyed frame. The forbidden ordering: H current FIRST,
  THEN G's unqualified signal. The claim's three eligibility reads were likewise
  not coherent against a concurrent registry mutation, because the lock excluded
  those mutations.

  ## The fix

  `claim-and-publish-delayed-silence!` reserves AND runs the external emit inside
  ONE `silence-lock` critical section, and `put-listener!` / `drop-listener!` /
  `reset-listeners!` / the `record-observation!` re-arm now take that same lock.
  So the winning `(cb-id, observed-gen)` stays authoritative through the emission
  linearization point: a replacement in the reserve→emit window BLOCKS until
  publication completes (silence linearized before H becomes current), or — if H
  landed first — the eligibility recheck fails and no stale silence fires.

  JVM-only by intent (`silence-lock` is a no-op on the single CLJS thread; the
  synchronous CLJS peers are covered by `re-frame.epoch-cljs-test`). The seam is
  a `publish!` thunk that parks inside the critical section, letting a concurrent
  registrar prove — via the lock — whether it can land before the emit.

  Mutation teeth (red under the mutation, green with the fix):
    * emit OUTSIDE the lock (the pre-fix shape) → a replacement lands in the
      window → the sampled generation at emit is H, not G.
    * drop `put-listener!` / `record-observation!` lock participation → the
      mutation completes immediately instead of blocking on the held claim."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.listeners :as epoch.listeners]
            [re-frame.epoch.state :as epoch-state]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- cb-generation
  "The live generation token currently registered under `cb`."
  [cb]
  (:generation (get (epoch-state/listeners-snapshot) cb)))

(defn- owe-silence!
  "Register `cb`, observe `frame` under its current generation, then drop the
  live observation so a delayed silence for `(frame, cb)` is genuinely owed —
  exactly the state A's compare-owned cleanup leaves for a paused predecessor."
  [frame token cb]
  (rf/register-listener! :epoch cb (fn [_] nil))
  (epoch-state/claim-frame-owner! frame token)
  (epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
  (epoch-state/drop-frame-observation! frame))

;; ---- generation authoritative THROUGH the emission -------------------------

(deftest generation-stays-authoritative-through-the-emission-linearization-point
  ;; A registrar replaces cb's generation (G→H) in the window between the
  ;; reservation and the external emit. Because `claim-and-publish` runs the emit
  ;; INSIDE the same `silence-lock` section AND `put-listener!` participates in
  ;; that lock, H cannot become current before G's signal is out: the registrar
  ;; blocks until publication completes, so the generation sampled AT the emit is
  ;; always G. TOOTH: emitting outside the lock lets H land first → sampled H.
  (let [frame       :bhne6/thru-emit
        cb          ::bhne6-thru-emit-cb
        token       (Object.)
        reached-emit (CountDownLatch. 1)
        h-installed  (CountDownLatch. 1)
        gen-at-emit  (atom ::unset)
        published?   (atom ::unset)]
    (owe-silence! frame token cb)
    (try
      (let [g        (cb-generation cb)
            publish! (fn []
                       ;; Inside the critical section, mark reserved. Signal the
                       ;; registrar to attempt its replace, then wait (bounded)
                       ;; for it to install H. Under the FIX the registrar's
                       ;; put-listener! is blocked on the held lock, so this times
                       ;; out and we sample G. Under an emit-outside-lock
                       ;; regression H lands and we sample H.
                       (.countDown reached-emit)
                       (.await h-installed 1 TimeUnit/SECONDS)
                       (reset! gen-at-emit (cb-generation cb)))
            publisher (future
                        (reset! published?
                                (epoch-state/claim-and-publish-delayed-silence!
                                  frame cb g 0 publish!)))
            registrar (future
                        (.await reached-emit 5 TimeUnit/SECONDS)
                        ;; put-listener! participates in silence-lock → blocks
                        ;; until the publisher releases it (after the emit).
                        (rf/register-listener! :epoch cb (fn [_] nil))
                        (.countDown h-installed))]
        (is (not= ::timeout (deref publisher 5000 ::timeout)) "publisher completed")
        (is (true? @published?) "the silence was published")
        (is (= g @gen-at-emit)
            "the winning generation G is still current at the emission point — a replacement could not land first")
        (is (not= ::timeout (deref registrar 5000 ::timeout)) "registrar completed")
        (is (not= g (cb-generation cb))
            "the registrar's replacement H landed only AFTER publication completed"))
      (finally
        (rf/unregister-listener! :epoch cb)))))

;; ---- registry + observation mutations serialize with the claim -------------

(deftest registry-and-observation-mutations-serialize-with-the-silence-claim
  ;; While a `claim-and-publish` holds `silence-lock` (parked in publish!), a
  ;; concurrent registry replacement AND a concurrent observation re-arm both
  ;; BLOCK until it releases. This is the coherence the eligibility reads rely on
  ;; — no registry/observation mutation can interleave between the claim's
  ;; individual reads. TEETH: dropping put-listener!'s (resp. record-observation!'s)
  ;; lock lets the mutation complete immediately, so the "blocked" assertions
  ;; flip.
  (let [frame      :bhne6/serialize
        cb         ::bhne6-serialize-cb        ; the parked claim's subject
        other      ::bhne6-serialize-other     ; observation re-arm subject (owed)
        reg-target ::bhne6-serialize-reg-target ; registry replacement subject
        token      (Object.)
        in-claim   (CountDownLatch. 1)
        release    (CountDownLatch. 1)
        put-done   (CountDownLatch. 1)
        obs-done   (CountDownLatch. 1)]
    (owe-silence! frame token cb)
    ;; `other` also observes and is dropped, so a re-arm is a genuine transition
    ;; that takes the lock (not the lock-free no-op fast path).
    (rf/register-listener! :epoch other (fn [_] nil))
    (epoch.listeners/notify-listeners! {:frame frame :epoch-id 2})
    (epoch-state/drop-frame-observation! frame)
    (rf/register-listener! :epoch reg-target (fn [_] nil))
    (try
      (let [g         (cb-generation cb)
            other-gen (cb-generation other)
            publish!  (fn []
                        (.countDown in-claim)
                        (.await release 5 TimeUnit/SECONDS))
            claimer   (future (epoch-state/claim-and-publish-delayed-silence!
                                frame cb g 0 publish!))
            registrar (future (.await in-claim 5 TimeUnit/SECONDS)
                              (rf/register-listener! :epoch reg-target (fn [_] nil))
                              (.countDown put-done))
            observer  (future (.await in-claim 5 TimeUnit/SECONDS)
                              (epoch-state/record-observation! other other-gen frame)
                              (.countDown obs-done))]
        (is (.await in-claim 5 TimeUnit/SECONDS) "the claim is parked inside silence-lock")
        (is (false? (.await put-done 500 TimeUnit/MILLISECONDS))
            "put-listener! is blocked on silence-lock while the claim holds it")
        (is (false? (.await obs-done 500 TimeUnit/MILLISECONDS))
            "the record-observation! re-arm is blocked on silence-lock too")
        (.countDown release)
        (is (not= ::timeout (deref claimer 5000 ::timeout)) "the claim completed")
        (is (.await put-done 5000 TimeUnit/MILLISECONDS)
            "put-listener! completes once the claim releases the lock")
        (is (.await obs-done 5000 TimeUnit/MILLISECONDS)
            "record-observation! completes once the claim releases the lock")
        (is (not= ::timeout (deref registrar 5000 ::timeout)) "registrar completed")
        (is (not= ::timeout (deref observer 5000 ::timeout)) "observer completed"))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :epoch other)
        (rf/unregister-listener! :epoch reg-target)))))

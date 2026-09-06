(ns re-frame.epoch-silencing-generation-emission-test
  "rf2-9bhne6 / rf2-8b9twg — the delayed-silence generation stays AUTHORITATIVE
  through the trace emission that publishes it, WITHOUT holding a ledger lock
  across the external fan-out.

  ## Background

  rf2-9bhne6 kept the winning `(cb-id, observed-gen)` authoritative by running the
  external `:rf.epoch.cb/silenced-on-frame-destroy` emit INSIDE both ledger locks
  (`claim-and-publish-delayed-silence!` under `with-claim-locks`). That closed the
  reserve→emit window in which a same-id replacement G→H could make a fresh
  generation current before an UNQUALIFIED `{:frame :cb-id}` signal fired — but it
  ran arbitrary listener code (which may `dispatch-sync` and acquire a frame's
  `:drain-lock`) under the ledger locks, an AB-BA deadlock against a draining
  thread (rf2-8b9twg; the dedicated regression lives in
  `re-frame.epoch-silencing-emit-deadlock-test`).

  ## The rf2-8b9twg mechanism (what these tests now pin)

  `claim-and-publish-delayed-silence!` reserves the mark under both ledger locks,
  RELEASES them, then emits OUTSIDE them. Generation authority is preserved not by
  the lock but by a DATA QUALIFIER: the emit carries `:observed-gen` (the reserved
  generation G). A same-id replacement H landing in the reserve→emit window can no
  longer be mistaken as the silence's subject — the signal is attributed to G, and
  a receiver whose CURRENT generation for `cb-id` != the carried `:observed-gen`
  SELF-FILTERS it. The forbidden ordering (H current, THEN a signal attributed to
  H) is impossible: no unqualified signal is ever emitted.

  Two invariants, both JVM-only (`silence-lock` / `registry-lock` are no-ops on
  the single CLJS thread; the synchronous CLJS peers live in
  `re-frame.epoch-cljs-test`):

    * TEST A — the emit runs OUTSIDE the ledger locks: while a claim's `publish!`
      is parked, a concurrent `put-listener!` AND a concurrent `record-observation!`
      re-arm complete IMMEDIATELY (they are NOT blocked). This is the direct
      inverse of the old emit-under-lock behaviour and the unit-level proof that
      the foreign-code-under-lock edge (the deadlock source) is gone.
    * TEST B — the emitted signal is GENERATION-QUALIFIED: even when a same-id
      replacement H lands DURING the emit window (concurrently, unblocked), the
      wire signal carries `:observed-gen == G`, and the now-current generation is
      H != G — so a receiver self-filters it. No G→H window is reopened."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.listeners :as rf.epoch.listeners]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- cb-generation
  "The live generation token currently registered under `cb`. This is an
  ARTEFACT-INTERNAL read: rf2-uhouu retired the public generation query, because
  a consumer holding the generation alone can only recompose the torn two-read
  receiver decision. A real consumer instead asks the ONE supported question,
  `re-frame.epoch/epoch-silence-current?` — exercised at the public boundary by
  `re-frame.epoch-silence-receiver-public-api-test`. The assertions below name
  the generation directly because they are pinning the EMISSION's qualifier, not
  the receiver's decision."
  [cb]
  (get-in (rf.epoch.state/listeners-snapshot) [cb :generation]))

(defn- owe-silence!
  "Register `cb`, observe `frame` under its current generation, then drop the
  live observation so a delayed silence for `(frame, cb)` is genuinely owed —
  exactly the state A's compare-owned cleanup leaves for a paused predecessor."
  [frame token cb]
  (rf/register-listener! :epoch cb (fn [_] nil))
  (rf.epoch.state/claim-frame-owner! frame token)
  (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
  (rf.epoch.state/drop-frame-observation! frame))

;; ---- TEST A: the emit runs OUTSIDE the ledger locks ------------------------

(deftest emit-runs-outside-the-ledger-locks-so-a-concurrent-registrar-and-rearm-are-not-blocked
  ;; While a `claim-and-publish` is parked in `publish!` (the external emit), a
  ;; concurrent registry replacement AND a concurrent observation re-arm both
  ;; COMPLETE IMMEDIATELY — because the ledger locks were RELEASED before the emit
  ;; (rf2-8b9twg). This is the inverse of rf2-9bhne6's emit-under-lock behaviour,
  ;; and the unit-level proof that the fan-out reaches no ledger lock (so it can
  ;; never invert against a frame's :drain-lock). TOOTH: putting the emit back
  ;; under the locks re-blocks both mutations and flips these assertions to RED.
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
    ;; that takes silence-lock (not the lock-free no-op fast path).
    (rf/register-listener! :epoch other (fn [_] nil))
    (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 2})
    (rf.epoch.state/drop-frame-observation! frame)
    (rf/register-listener! :epoch reg-target (fn [_] nil))
    (try
      (let [g         (cb-generation cb)
            other-gen (cb-generation other)
            publish!  (fn []
                        (.countDown in-claim)
                        (.await release 5 TimeUnit/SECONDS))
            claimer   (future (rf.epoch.state/claim-and-publish-delayed-silence!
                                frame cb g 0 publish!))
            registrar (future (.await in-claim 5 TimeUnit/SECONDS)
                              (rf/register-listener! :epoch reg-target (fn [_] nil))
                              (.countDown put-done))
            observer  (future (.await in-claim 5 TimeUnit/SECONDS)
                              (rf.epoch.state/record-observation! other other-gen frame)
                              (.countDown obs-done))]
        (is (.await in-claim 5 TimeUnit/SECONDS) "the claim is parked inside publish!")
        ;; The rf2-8b9twg guarantee: NO ledger lock is held during the emit, so
        ;; both mutations proceed at once. (Under the pre-fix emit-under-lock code
        ;; these awaits return false — the mutations block until `release`.)
        (is (.await put-done 2 TimeUnit/SECONDS)
            "put-listener! is NOT blocked — the emit holds no registry lock")
        (is (.await obs-done 2 TimeUnit/SECONDS)
            "the record-observation! re-arm is NOT blocked — the emit holds no silence-lock")
        (.countDown release)
        (is (not= ::timeout (deref claimer 5000 ::timeout)) "the claim completed")
        (is (not= ::timeout (deref registrar 5000 ::timeout)) "registrar completed")
        (is (not= ::timeout (deref observer 5000 ::timeout)) "observer completed"))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :epoch other)
        (rf/unregister-listener! :epoch reg-target)))))

;; ---- TEST B: the emitted signal is generation-qualified --------------------

(deftest silence-signal-is-generation-qualified-and-self-filters-a-replacement-in-the-emit-window
  ;; A registrar replaces cb's generation (G→H) DURING the emit window — and,
  ;; because the emit now runs outside the locks, it is NOT blocked, so H DOES
  ;; become current before the emit returns. Yet the wire signal carries
  ;; `:observed-gen == G` (the reserved generation, baked into the payload before
  ;; the fan-out), and the current generation is H != G — so a receiver's
  ;; `current-gen == observed-gen?` check drops it. The signal is attributed to G,
  ;; never to H: no G→H window is reopened (rf2-8b9twg supersedes the
  ;; emit-under-lock mechanism of rf2-9bhne6). TOOTH: dropping `:observed-gen` from
  ;; the payload (the unqualified signal) removes the discriminator entirely.
  (let [frame        :bhne6/thru-emit
        cb           ::bhne6-thru-emit-cb
        token        (Object.)
        reached-emit (CountDownLatch. 1)  ; the silence emit reached the trace listener
        h-installed  (CountDownLatch. 1)  ; the registrar's replacement H is current
        captured     (atom [])            ; every silence event seen for cb
        silence-key  ::bhne6-thru-emit-silencing]
    ;; Register cb and let it OBSERVE the frame so the destroy snapshot owes it a
    ;; silence under generation G (the real listeners path bakes G onto the wire).
    (rf/register-listener! :epoch cb (fn [_] nil))
    (rf.epoch.state/claim-frame-owner! frame token)
    (rf.epoch.listeners/notify-listeners! {:frame frame :epoch-id 1})
    ;; The trace listener captures the silence AND parks the emit open (outside the
    ;; locks) so a concurrent registrar can land H before the emit returns.
    (rf/register-listener! :trace silence-key
      (fn [ev]
        (when (and (= :rf.epoch.cb/silenced-on-frame-destroy (:operation ev))
                   (= cb (:cb-id (:tags ev))))
          (swap! captured conj (:tags ev))
          (.countDown reached-emit)
          (.await h-installed 5 TimeUnit/SECONDS))))
    (try
      (let [g   (cb-generation cb)
            ;; Snapshot the terminal evidence WHILE cb still observes, so the
            ;; per-identity claim owes cb→G. `on-frame-destroyed!` then drops the
            ;; observation (compare-owned cleanup), reserves under the locks,
            ;; releases, and emits the generation-qualified signal.
            a-ev (rf.epoch.listeners/snapshot-terminal-destroy-evidence! frame nil nil nil)
            registrar (future
                        (.await reached-emit 5 TimeUnit/SECONDS)
                        ;; put-listener! is NOT blocked (the emit holds no lock),
                        ;; so H becomes current DURING the emit window.
                        (rf/register-listener! :epoch cb (fn [_] nil))
                        (.countDown h-installed))
            ;; Runs the real emit; blocks (in the trace listener) until H lands.
            destroyer (future (rf.epoch.listeners/on-frame-destroyed! frame token a-ev))]
        (is (not= ::timeout (deref registrar 5000 ::timeout))
            "the registrar installed H — put-listener! was not blocked by the emit")
        (is (not= ::timeout (deref destroyer 5000 ::timeout)) "the destroy/emit completed")
        (is (= 1 (count @captured)) "exactly one silence fired for cb")
        (let [tags (first @captured)]
          (is (= g (:observed-gen tags))
              "the wire signal carries the RESERVED generation G, not the replacement H")
          (is (= frame (:frame tags)) "and the frame it was silenced for")
          (is (not= g (cb-generation cb))
              "H became current DURING the emit window (the emit did not block the registrar)")
          (is (not= (:observed-gen tags) (cb-generation cb))
              "current-gen != carried observed-gen — a receiver SELF-FILTERS this stale signal (no G→H window)")))
      (finally
        (rf/unregister-listener! :epoch cb)
        (rf/unregister-listener! :trace silence-key)))))

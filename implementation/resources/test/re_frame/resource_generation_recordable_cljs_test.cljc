(ns re-frame.resource-generation-recordable-cljs-test
  "rf2-abyycr (EP-0017 §Recordable coeffects + Spec 016 §Restore and replay /
  §Durable join keys are recordable) — the resource/mutation `generation` is
  a DURABLE JOIN KEY (it is written onto the entry / instance and stamped on
  the reply token as the stale-suppression correlation), so the minted VALUE
  must be RECORDABLE even though the host allocator stays transient.

  ## The hole this proves-then-closes

  The generation used to be minted from the AMBIENT `:rf.resource/generation`
  cofx — a host-cache read, never recorded, re-run on replay. On replay the
  ambient read re-mints a DIFFERENT generation (the host allocator is at a
  different high-water than it was at record time), so a recorded managed
  reply that was ACCEPTED at record time (its generation matched the live
  entry's) replays as STALE-SUPPRESSED (the re-minted generation no longer
  matches), or vice versa — the recorded log and the replayed state diverge.

  The fix makes `:rf.resource/generation-allocation` a GENERATOR-BACKED
  RECORDABLE cofx: the generator mints the next monotone allocation at
  processing-start and the runtime records the value on the causal token, so
  replay (supplying the recorded allocation) reproduces the IDENTICAL
  generation — and therefore the identical `:work/id` / `:instance/id`, which
  derive from it — regardless of where the host allocator now sits.

  ## What the test does

  1. RECORD — a fresh host allocator; an ensure mints generation 1; the
     success reply (carrying generation 1) is ACCEPTED (the entry loads). The
     recorded `:rf.cofx/generated` trace op captures the allocation the
     runtime stamped onto the token: `{:generation 1 :counter 1}`.
  2. REPLAY-CORRECT (the fix) — the host allocator is now at a DIFFERENT
     high-water (10, as a fresh process / a different frame's work would
     leave it); replay the SAME ensure SUPPLYING the recorded allocation on
     the token. The handler reads generation 1 (the recorded value), NOT
     `(inc 10)` = 11. The recorded reply (generation 1) is ACCEPTED again —
     the durable outcome equals the record.
  3. REPLAY-DIVERGENT (the bug, shown as a control) — same allocator at 10,
     but replay the ensure WITHOUT the recorded allocation, so the generator
     re-mints generation 11 (the old ambient behaviour). The recorded reply
     (generation 1) is now STALE-SUPPRESSED — generation 1 no longer matches
     the live entry's generation 11 — so the entry never loads the reply
     value: the exact divergence the recordable allocation kills.

  CLJS-runnable; `.cljc` so the JVM core test build also runs it (the
  threading is platform-agnostic)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: register the :rf.resource/*
   ;; events + subs + the generation-allocation cofx / commit-generation fx.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch is overridden by the capturing reply stub below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.trace.tooling :as trace-tooling]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport (replays the real reply-append shape) ------------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

(def ^:private frame-id :rf/default)

(defn- register-resource! []
  (rf/reg-resource :gen/article
    {:scope          :rf.scope/global
     :params-schema  [:map [:slug :string]]
     :stale-after-ms 60000}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :get :url (str "/api/articles/" slug)}})))

(def ^:private scoped-key
  (delay (state/scoped-resource-key :rf.scope/global :gen/article {:slug "w"})))

(defn- ensure!
  "Dispatch the ensure for `:gen/article`, optionally SUPPLYING a recorded
  `:rf.resource/generation-allocation` on the token (the replay path). With
  no allocation supplied the generator mints one under `:live` (the record /
  ambient-re-mint path)."
  ([] (ensure! nil))
  ([allocation]
   (rf/dispatch-sync
     [:rf.resource/ensure
      {:resource :gen/article :scope :rf.scope/global
       :params {:slug "w"} :owner [:lease :gen 1]}]
     (cond-> {:frame frame-id :rf.cofx {:rf/time-ms 1781078400100}}
       allocation (assoc-in [:rf.cofx :rf.resource/generation-allocation] allocation)))))

(defn- reply-success!
  "Dispatch an `:on-success` reply with the transport success result appended
  as the LAST arg (the live transport shape), carrying a scripted reply token
  time. With no `on-success` argument, replays the most-recently captured
  reply token (`@last-managed-args`); with an explicit `on-success` vector,
  replays THAT token — used by phase 3 to replay the RECORDED (generation-1)
  reply against a re-minted (generation-11) entry."
  ([data] (reply-success! data (:on-success @last-managed-args)))
  ([data on-success]
   (rf/dispatch-sync (conj on-success {:kind :success :value data})
                     {:frame frame-id :rf.cofx {:rf/time-ms 1781078400250}})))

(defn- live-entry []
  (get-in (rf/runtime-db-value frame-id) (state/entry-path @scoped-key)))

(defn- with-fresh-runtime
  "Run `body` (a zero-arg thunk) inside a FRESH runtime — re-runs the `:each`
  reset fixture (clean registrar / frames / host-transient generation
  allocator + caches), re-installs the capturing transport, then invokes
  `body`. `body` MUST run INSIDE the fixture call: the fixture restores the
  registrar + resets `frame/frames` to `{}` in a `finally` on return, so a
  fire-and-forget reset would tear the runtime down before the caller's next
  dispatch (no installed frame ⇒ nil entries). Mirrors the in-test reset of
  `replay_determinism_e2e_cljs_test`. Returns `body`'s value."
  [body]
  ((core-test-support/make-reset-runtime-fixture
     #?(:clj  {:adapter plain-atom/adapter}
        :cljs {:adapter reagent-adapter/adapter}))
   (fn []
     (reset! last-managed-args nil)
     (rf/reg-fx :rf.http/managed
                (fn [_ctx args] (reset! last-managed-args args) nil))
     (body))))

(deftest generation-allocation-is-recorded-so-replay-keeps-the-reply-current
  (testing "rf2-abyycr — the resource generation is a RECORDABLE allocation:
            a recorded managed reply that was accepted at record time replays
            as accepted (NOT stale-suppressed) because replay reproduces the
            identical generation from the recorded allocation, even when the
            host allocator now sits at a different high-water."

    ;; ===== 1. RECORD — fresh allocator; ensure mints generation 1 ==========
    (register-resource!)
    (let [generated (atom [])]
      (trace-tooling/register-listener!
        ::gen-rec
        (fn [ev] (when (= :rf.cofx/generated (:operation ev))
                   (swap! generated conj ev))))
      (try
        (ensure!)                                  ;; generator runs (:live)
        (finally (trace-tooling/unregister-listener! ::gen-rec)))

      ;; The runtime recorded the allocation on the token (the dev-only
      ;; `:rf.cofx/generated` op carries the produced value). Fresh allocator
      ;; ⇒ generation 1.
      (let [alloc-ev (->> @generated
                          (filter #(= :rf.resource/generation-allocation
                                      (:rf.cofx/id (:tags %))))
                          first)
            recorded-allocation (:rf.cofx/value (:tags alloc-ev))
            ;; capture the RECORD-time reply token (the lowered :on-success
            ;; carrying work-id + generation 1) BEFORE any reset — phase 3
            ;; replays THIS gen-1 reply against a re-minted gen-11 entry to
            ;; prove the divergence. Reusing the live `@last-managed-args`
            ;; there would replay phase 3's OWN gen-11 reply (which matches,
            ;; so it would NOT be suppressed — a vacuous control).
            recorded-on-success (:on-success @last-managed-args)]
        (is (some? alloc-ev)
            "the runtime recorded the generation allocation on the token under
             :live (a :rf.cofx/generated trace op was emitted)")
        (is (= {:generation 1 :counter 1} recorded-allocation)
            "the recorded allocation is the minted value {:generation 1
             :counter 1} (fresh host allocator)")

        ;; the entry minted generation 1.
        (is (= 1 (:generation (live-entry)))
            "the live entry carries the minted generation 1")

        ;; the success reply (carrying generation 1) is ACCEPTED — the entry
        ;; loads the reply value.
        (reply-success! {:title "Welcome"})
        (is (= :loaded (:status (live-entry)))
            "the success reply (generation 1) was accepted at record time")
        (is (= {:title "Welcome"} (:data (live-entry)))
            "the recorded reply installed its value")

        ;; ===== 2. REPLAY-CORRECT — allocator at a DIFFERENT high-water; =====
        ;; =====    replay SUPPLIES the recorded allocation; reply current ====
        ;; Run the replay phase INSIDE a fresh runtime — the fixture restores
        ;; the registrar + resets frames on RETURN (in a finally), so the
        ;; assertions must run inside the body thunk (a fire-and-forget reset
        ;; would tear the runtime down before the next dispatch).
        (with-fresh-runtime
          (fn []
            (register-resource!)
            ;; the replay process's host allocator already minted 10
            ;; generations (a different frame's work, a longer-lived process)
            ;; — restore / replay cannot rewind it.
            (state/commit-generation! frame-id 10)
            ;; replay the ensure SUPPLYING the recorded allocation: the handler
            ;; reads generation 1 (the recorded value), NOT (inc 10) = 11.
            (ensure! recorded-allocation)
            (is (= 1 (:generation (live-entry)))
                "replay reproduced the recorded generation 1 from the recorded
                 allocation — NOT a re-mint from the live high-water (inc 10)")
            ;; replay the recorded reply (generation 1) — ACCEPTED again.
            (reply-success! {:title "Welcome"})
            (is (= :loaded (:status (live-entry)))
                "the recorded reply (generation 1) is ACCEPTED on replay — the
                 recordable allocation kept it current (the fix)")
            (is (= {:title "Welcome"} (:data (live-entry)))
                "replay installed the same durable value as the record")))

        ;; ===== 3. REPLAY-DIVERGENT (the bug, control) — same allocator, =====
        ;; =====    NO recorded allocation ⇒ generator re-mints; reply stale ==
        (with-fresh-runtime
          (fn []
            (register-resource!)
            (state/commit-generation! frame-id 10)
            ;; replay the ensure WITHOUT the recorded allocation — the
            ;; generator re-mints from the live high-water (the old ambient
            ;; behaviour): generation 11.
            (ensure!)
            (is (= 11 (:generation (live-entry)))
                "without the recorded allocation the generation is RE-MINTED
                 from the live high-water (inc 10) = 11 — divergent from the
                 record")
            ;; replay the RECORDED reply (generation 1 — the record-time
            ;; :on-success token captured in phase 1) against the re-minted
            ;; entry (generation 11): STALE-SUPPRESSED — generation 1 no longer
            ;; matches the live entry's generation 11.
            (reply-success! {:title "Welcome"} recorded-on-success)
            (is (not= {:title "Welcome"} (:data (live-entry)))
                "the recorded reply (generation 1) is STALE-SUPPRESSED against
                 the re-minted entry (generation 11) — the divergence the
                 recordable allocation kills; the entry never loads the
                 recorded reply value")
            (is (not= :loaded (:status (live-entry)))
                "the entry stays in-flight (the suppressed reply settled no
                 durable data) — proving the assertion above is
                 non-vacuous")))))))

(deftest mutation-generation-allocation-is-recorded
  (testing "rf2-abyycr — the mutation writer mints `generation` from the SAME
            recordable allocation cofx (one root), so a recorded mutation
            replay reproduces an identical generation (and the derived
            instance-id / work-id) regardless of the live host high-water."
    (rf/reg-mutation :gen/save
      {:params-schema [:map [:slug :string]]
       :scope :rf.scope/global}
      (fn [{:keys [slug]} _] {:request {:method :post
                                        :url (str "/api/save/" slug)}}))
    (let [generated (atom [])]
      (trace-tooling/register-listener!
        ::mgen-rec
        (fn [ev] (when (and (= :rf.cofx/generated (:operation ev))
                            (= :rf.resource/generation-allocation
                               (:rf.cofx/id (:tags ev))))
                   (swap! generated conj ev))))
      (try
        (rf/dispatch-sync [:rf.mutation/execute
                           {:mutation :gen/save :params {:slug "w"}}]
                          {:frame frame-id :rf.cofx {:rf/time-ms 1781078400100}})
        (finally (trace-tooling/unregister-listener! ::mgen-rec)))
      (is (= {:generation 1 :counter 1} (:rf.cofx/value (:tags (first @generated))))
          "the mutation writer recorded the generation allocation on its token
           via the same `:rf.resource/generation-allocation` cofx (one root,
           fresh allocator ⇒ generation 1)"))))

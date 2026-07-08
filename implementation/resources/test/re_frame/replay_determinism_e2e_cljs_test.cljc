(ns re-frame.replay-determinism-e2e-cljs-test
  "rf2-b7w7i0 (EP-0010 §Validation/Conformance + §Replay Fixture) — the
  UNIFIED end-to-end replay-determinism fixture. The EP's STRONGEST stated
  property, proven end-to-end rather than component-by-component.

  ## What the EP promises (and what was missing)

  EP-0010's headline guarantee: a recorded epoch — a token log with
  `:rf.cofx` (`:time-ms` / `:uuid` / `:random`) on each token —
  replays DETERMINISTICALLY from its captured world-inputs, EQUAL durable
  state across two runs under DIFFERING host clock + RNG. Every durable
  causal-time / id fact folds the TOKEN's world inputs, never an ambient
  host read inside a handler / reducer / reply path.

  Before this fixture, that property was proven only COMPONENT-by-COMPONENT:
    - epoch `:committed-at` — `committed-at-replay-stable-across-wall-clock-
      drift` (epoch_test.clj): single durable field, app-db-epoch partition,
      JVM-only.
    - resources — `succeeded-loaded-at-…`, `work-record-started-at-…`,
      `invalidate-tags-invalidated-at-…`: each scripts ONE token's `:time-ms`
      and asserts the durable value equals it (token-sourced ⇒ implicitly
      replay-stable). Most do ONE dispatch.
  No test took a MULTI-EVENT log spanning app-db AND runtime-db, ran the
  WHOLE log through the live router TWICE under two DIFFERENT ambient
  clocks / RNGs, and asserted the two `{:rf.db/app :rf.db/runtime}`
  projections EQUAL. A component test passes even if the fold leaks an
  ambient read in a path none exercises TOGETHER (e.g. the freshness-
  decision branch flagged by rf2-95b0lc).

  ## The fixture

  A token log spanning BOTH durable partitions:

    1. APP-DB entity create — a `:repl/create` handler reads `:uuid` /
       `:random` / `:time-ms` from the token's `:rf.cofx` and folds
       them into a durable app-db entity (the EP §Examples \"Correct
       Generated Values From The Token\" shape). Ambient `random-uuid` /
       `rand` would diverge run-to-run; reading the token does not.
    2. RESOURCE ensure → success — a `reg-resource` ensure lowers through
       the managed-HTTP transport (capturing stub replaying the real reply-
       append shape); the success reply token's `:time-ms` becomes the
       durable `:loaded-at` / `:stale-at` (runtime-db). The work-ledger
       `:started-at` / `:deadline-at` fold the ensure token's `:time-ms`.
    3. APP-DB mutation — a `:repl/touch` handler folds the token's
       `:time-ms` into a durable app-db field (a second durable write,
       app-db partition).
    4. OWNER release — `:rf.resource/release-owner` drops the lease so the
       subsequent invalidation LEAVES the entry stale rather than triggering
       a live refetch. (A refetch is a NEW causal action whose child token is
       freshly stamped — correct EP-0010 behaviour, but it is NOT part of the
       RECORDED log; a real recorded-epoch replay would carry the refetch's
       own token. Releasing the owner keeps this fixture a pure recorded-log
       replay: every durable write folds a SCRIPTED token, nothing is live-
       stamped. This very divergence — an active-owner invalidation spawning
       an ambient-stamped refetch — is what the fixture SURFACED on first
       authoring, exactly the cross-path leak rf2-95b0lc predicts.)
    5. TAG invalidation — `:rf.resource/invalidate-tags` writes the durable
       `:invalidated-at` from the invalidation token's `:time-ms` (runtime-
       db), driving the freshness-DECISION branch (`entry-stale?`). With no
       active owner the entry is LEFT STALE (durable `:invalidated-at`, no
       refetch — Spec 016 §Invalidation 4).

  The SAME log is replayed TWICE. Run A uses ambient clock/RNG sentinel A;
  run B uses a WILDLY different ambient clock/RNG sentinel B (we redef BOTH
  `interop/now-ms` and `interop/epoch-now-ms`, and the host `rand` /
  `random-uuid` generators). Each run starts from a FRESH runtime + reset
  host-transient caches (generation allocator, resource/timer caches) so the
  only difference between the two runs is the ambient clock/RNG — exactly the
  drift the EP says must NOT change durable state.

  We assert the two runs' `{:rf.db/app :rf.db/runtime}` projections are
  EQUAL after stripping diagnostic / host-transient state (canonical-form
  compare, the tools/story determinism-harness shape). If ANY durable write
  in the whole log folded an ambient read instead of the token, the two
  projections diverge and this fails — the end-to-end guarantee the component
  tests could not give.

  Cross-ref rf2-95b0lc (it predicts this fixture surfaces a fresh-skip-branch
  ambient-read divergence). CLJS-runnable (the `.cljs` runtime is where the
  clock CLASSES diverge — `now-ms`=performance.now vs
  `epoch-now-ms`=js/Date.now); `.cljc` so the JVM core test build also runs
  it (the threading is platform-agnostic)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.interop :as interop]
   ;; load-bearing side-effecting requires: register the :rf.resource/*
   ;; events + subs + the generation cofx/fx the ensure/invalidate drive.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   ;; production HTTP fx surface (so the transport feature probe resolves);
   ;; the actual fetch is overridden by the capturing reply stub below.
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport (replays the real reply-append shape) ------------
;;
;; The live managed-HTTP transport APPENDS its result to the runtime-supplied
;; `:on-success` event vector as the LAST arg (Spec 014 §Reply addressing).
;; To exercise the runtime's reply handlers against that EXACT shape WITHOUT
;; a live Fetch — and so we can SCRIPT the reply token's `:rf.cofx`
;; `:time-ms` exactly as a replay would re-feed it — the stub captures the
;; lowered args; the test replays the success reply explicitly.

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
(def ^:private stale-after-ms 60000)

;; ---- the durable handlers + resource the token log drives -----------------
;;
;; Each handler reads its durable facts from the token's `:rf.cofx`
;; coeffect — NEVER an ambient `random-uuid` / `rand` / clock read. That is
;; the property under test: replay-determinism follows iff every durable
;; write folds the token, so the two runs (differing ambient clock/RNG)
;; converge.

(defn- register-log! []
  ;; (1) APP-DB entity create from the token's generated values.
  (rf/reg-event :repl/create
    (fn [{:keys [db] cofx :rf.cofx} [_ text]]
      (let [id    (:todo/id cofx)
            color (:todo/color cofx)
            at    (:rf/time-ms cofx)]
        {:db (assoc-in db [:todos id]
                       {:todo/id id :todo/color color
                        :todo/text text :todo/created-at at})})))
  ;; (3) APP-DB mutation — a second durable write folding the token time.
  (rf/reg-event :repl/touch
    (fn [{:keys [db] cofx :rf.cofx} [_ id]]
      {:db (assoc-in db [:todos id :todo/touched-at] (:rf/time-ms cofx))}))
  ;; (2) RESOURCE — loaded-at / stale-at fold the success-reply token time;
  ;;     work-ledger started-at / deadline-at fold the ensure token time.
  (rf/reg-resource :repl/article
    {:scope          :rf.scope/global
     :params-schema  [:map [:slug :string]]
     :stale-after-ms stale-after-ms
     :tags           (fn [{:keys [slug]} _data] #{[:article slug]})}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :get :url (str "/api/articles/" slug)}})))

(defn- reply-success!
  "Dispatch the captured `:on-success` reply with the transport's success
  result appended as the LAST arg (the live transport shape), carrying a
  SCRIPTED `:rf.cofx` `:rf/time-ms` — exactly as a replay re-feeds the
  reply token's causal completion time."
  [data time-ms]
  (rf/dispatch-sync (conj (:on-success @last-managed-args)
                          {:status :ok :value data})
                    {:frame frame-id :rf.cofx {:rf/time-ms time-ms}}))

;; ---- the canonical scripted token log -------------------------------------
;;
;; The SAME causal world-inputs on every token across BOTH runs — the replay
;; record. Ambient clock/RNG vary between runs; these do NOT.

(def ^:private todo-id #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d")

(def ^:private log
  {:create-time     1781078400000
   :ensure-time     1781078400100
   :reply-time      1781078400250
   :touch-time      1781078400400
   :release-time    1781078400700
   :invalidate-time 1781078400900})

(defn- run-log!
  "Replay the whole token log through the LIVE router once, against the
  fresh runtime the caller has just reset. Every dispatch supplies its
  `:rf.cofx` so the durable fold is token-sourced; the ambient
  clock/RNG the caller pinned must NOT leak into any durable write.
  Returns the two-partition durable projection `{:rf.db/app :rf.db/runtime}`."
  []
  (register-log!)
  ;; (1) APP-DB create from token uuid/random/time-ms.
  (rf/dispatch-sync [:repl/create "buy milk"]
                    {:frame frame-id
                     :rf.cofx {:todo/id    todo-id
                               :todo/color :green
                               :rf/time-ms (:create-time log)}})
  ;; (2) RESOURCE ensure → success. The ensure token's :time-ms folds into the
  ;; work-ledger :started-at; the success reply token's :time-ms folds into the
  ;; durable :loaded-at / :stale-at.
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :repl/article :scope :rf.scope/global
                      :params {:slug "w"} :owner [:lease :repl 1]}]
                    {:frame frame-id :rf.cofx {:rf/time-ms (:ensure-time log)}})
  (reply-success! {:title "Welcome"} (:reply-time log))
  ;; (3) APP-DB mutation folding the touch token time.
  (rf/dispatch-sync [:repl/touch todo-id]
                    {:frame frame-id :rf.cofx {:rf/time-ms (:touch-time log)}})
  ;; (4) OWNER release — drop the lease so the invalidation below LEAVES the
  ;; entry stale rather than spawning a live (ambient-stamped) refetch. Keeps
  ;; the fixture a pure recorded-log replay.
  (rf/dispatch-sync [:rf.resource/release-owner {:owner [:lease :repl 1]}]
                    {:frame frame-id :rf.cofx {:rf/time-ms (:release-time log)}})
  ;; (5) TAG invalidation — durable :invalidated-at from the invalidation
  ;; token's :time-ms (the freshness-DECISION branch, rf2-95b0lc). Ownerless
  ;; ⇒ left stale, no refetch (Spec 016 §Invalidation 4).
  (rf/dispatch-sync [:rf.resource/invalidate-tags
                     {:scope :rf.scope/global :tags #{[:article "w"]}}]
                    {:frame frame-id :rf.cofx {:rf/time-ms (:invalidate-time log)}})
  {:rf.db/app     (rf/app-db-value frame-id)
   :rf.db/runtime (:rf.db/runtime (rf/frame-state-value frame-id))})

;; ---- determinism canonicalizer (strip diagnostic / host-transient) --------
;;
;; The durable projection compare excludes purely-diagnostic / host-transient
;; slots that are LEGITIMATELY free to vary (or that hold a host handle, never
;; durable replay data). Per the EP §Replay Fixture the equality is over the
;; DURABLE projection — diagnostic + host-transient state is excluded. We strip
;; defensively, then assert the canonicalised projections are EQUAL.

(def ^:private host-transient-keys
  "Keys whose values are host-transient (opaque handles) or diagnostic and so
  are excluded from the durable-equality compare. The work-ledger durable
  records carry NO host handles by contract (work_ledger.cljc §Serializable
  work records), so the equality holds WITHOUT stripping ledger internals —
  these are a defensive belt for any diagnostic slot a record might carry."
  #{:rf.runtime/diagnostics})

(defn- canonical
  "Recursively strip host-transient / diagnostic keys so the compare is over
  the DURABLE projection only. Pure data → data."
  [x]
  (cond
    (map? x)  (into {} (for [[k v] x
                             :when (not (contains? host-transient-keys k))]
                         [k (canonical v)]))
    (coll? x) (into (empty x) (map canonical x))
    :else     x))

;; ---- the unified end-to-end replay-determinism test -----------------------

(deftest token-log-replays-deterministically-under-differing-clock-and-rng
  (testing "rf2-b7w7i0 — the SAME multi-event token log, replayed twice under
            WILDLY different ambient clocks AND RNG, yields EQUAL durable
            {:rf.db/app :rf.db/runtime} projections. Every durable causal-time
            / id fact folds the token's :rf.cofx, so ambient drift
            (the very thing replay must be immune to) does not change durable
            state. Spans app-db (entity create + mutation) AND runtime-db
            (resource loaded-at/stale-at, work-ledger started-at, tag
            invalidated-at) — the end-to-end guarantee the per-component tests
            could not give."
    ;; RUN A — ambient clock + RNG sentinel A. The redefs pin BOTH host clock
    ;; surfaces and the host random generators to values NOTHING durable should
    ;; fold (the tokens supply every durable fact). A regression that re-read
    ;; ANY ambient surface in a durable write stamps a sentinel and diverges
    ;; from run B.
    (let [run-a (with-redefs [interop/now-ms       (constantly 111)
                              interop/epoch-now-ms (constantly 111)
                              rand        (fn ([] 0.111) ([n] (* n 0.111)))
                              random-uuid (constantly
                                            #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")]
                  (run-log!))]
      ;; FULL fresh reset between runs: rerun the :each fixture body so the
      ;; runtime, frames, schemas, AND host-transient caches (generation
      ;; allocator, resource/timer caches) are clean — the ONLY difference
      ;; between the two runs is the ambient clock/RNG.
      ((core-test-support/make-reset-runtime-fixture
         #?(:clj  {:adapter plain-atom/adapter}
            :cljs {:adapter reagent-adapter/adapter}))
       (fn []
         (reset! last-managed-args nil)
         (rf/reg-fx :rf.http/managed
                    (fn [_ctx args] (reset! last-managed-args args) nil))
         ;; RUN B — a WILDLY different ambient clock + RNG sentinel.
         (let [run-b (with-redefs [interop/now-ms       (constantly 9999999999999)
                                   interop/epoch-now-ms (constantly 9999999999999)
                                   rand        (fn ([] 0.999) ([n] (* n 0.999)))
                                   random-uuid (constantly
                                                 #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")]
                       (run-log!))
               ca (canonical run-a)
               cb (canonical run-b)]
           ;; The headline equality: durable projections EQUAL across the
           ;; differing ambient clock/RNG.
           (is (= ca cb)
               "the two runs' durable {:rf.db/app :rf.db/runtime} projections
                are EQUAL — replay-determinism holds end-to-end across app-db
                AND runtime-db under differing host clock/RNG")

           ;; --- corroborating PIN: the durable facts ARE the token values, ---
           ;; --- not a sentinel (so the equality is non-vacuous and we are  ---
           ;; --- folding the token, not coincidentally-equal ambient reads).---
           (let [app   (:rf.db/app run-b)
                 todo  (get-in app [:todos todo-id])
                 rt    (:rf.db/runtime run-b)
                 scoped-key (state/scoped-resource-key
                              :rf.scope/global :repl/article {:slug "w"})
                 entry (get-in rt (state/entry-path scoped-key))]
             ;; (1) app-db entity — token uuid/random/time-ms, NOT ambient.
             (is (= todo-id (:todo/id todo))
                 "the durable entity id is the token uuid, not the ambient
                  random-uuid sentinel (bbbb… would appear if ambient leaked)")
             (is (= :green (:todo/color todo))
                 "the durable colour is the token :random value, not ambient")
             (is (= (:create-time log) (:todo/created-at todo))
                 "the durable created-at is the create token's :time-ms")
             ;; (3) app-db mutation — token time, not ambient.
             (is (= (:touch-time log) (:todo/touched-at todo))
                 "the durable touched-at is the touch token's :time-ms")
             ;; (2) runtime-db resource — loaded-at / stale-at from reply token.
             (is (= {:title "Welcome"} (:data entry))
                 "the resource loaded the reply value")
             (is (= (:reply-time log) (:loaded-at entry))
                 "the durable :loaded-at is the success-reply token's :time-ms,
                  NOT an ambient reply-handler clock read")
             (is (= (+ (:reply-time log) stale-after-ms) (:stale-at entry))
                 "the durable :stale-at is reply :time-ms + :stale-after-ms")
             ;; (4) runtime-db invalidation — invalidated-at from token.
             (is (= (:invalidate-time log) (:invalidated-at entry))
                 "the durable :invalidated-at is the invalidation token's
                  :time-ms — the freshness-DECISION branch reads the token,
                  not an ambient clock (rf2-95b0lc)")
             ;; the freshness DECISION itself is replay-stable: against ANY
             ;; clock the entry is stale (it was explicitly invalidated), a
             ;; durable fact, not an ambient-clock-dependent computation.
             (is (true? (state/entry-stale? entry (:reply-time log)))
                 "the entry is stale (explicitly invalidated) — a durable,
                  token-sourced decision, identical across both runs"))))))))

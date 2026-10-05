(ns re-frame.sub-dispose-trace-test
  "The sub-cache emits a `:rf.sub/dispose` trace event at
  every eviction site so consumers can observe the sub-cache lifecycle's
  terminal half — created / run / skip / **dispose**. This file pins the
  emit shape and reason-enum coverage against the core artefact.

  Contract — Spec 009 §:op-type vocabulary §`:rf.sub/dispose`:

    `:op-type :rf.sub`, `:operation :rf.sub/dispose`. One event per
    evicted cache slot. `:tags {:frame <id> :rf.sub/id <query-id>
    :rf.sub/query-v <vec> :rf.sub/reason <enum>}`. The reason axis is a
    closed enum:

      `:no-more-derefers` — synchronous 1 → 0 transition evicted the
                            slot.
      `:hot-reload`       — re-registration evicted every cached slot
                            for the affected sub-id.
      `:cache-clear`      — explicit `clear-sub-cache!` walked the
                            cache and disposed every slot.
      `:frame-destroy`    — `destroy-frame!` tore down the destroyed
                            frame's whole sub-cache.

  Single-fire discipline: the emit rides the SAME CAS-winner check that
  gates `rf.interop/dispose!`, so a concurrent invalidate + sync-dispose
  cannot produce two `:rf.sub/dispose` for the same eviction.

  Sub disposal is synchronous on derefer-count → 0; there is no
  grace-period to configure. The emit lands inside `unsubscribe` in the
  same tick."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.interop :as rf.interop]
            [re-frame.subs :as rf.subs]
            [re-frame.subs.cache :as rf.subs.cache]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; EP-0002: `init!` does not synthesise `:rf/default`,
  ;; and ambient subscribe / unsubscribe / clear-sub-cache! require a
  ;; carried frame stamp. These dispose-trace tests exercise the ambient
  ;; cache lifecycle against a single conventional app frame, so the
  ;; fixture registers `:rf/default` explicitly and pins it as the
  ;; established scope for the whole body via `with-frame` — so the dispose
  ;; traces assert `:frame :rf/default`.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- dispose-events
  [traces]
  (filterv #(= :rf.sub/dispose (:operation %)) traces))

;; ---- :no-more-derefers ---------------------------------------------------
;;
;; The dominant production case: last subscriber detaches, ref-count
;; drops to 0, and the slot is disposed synchronously inside the
;; `unsubscribe` call.

;; ---- Posture split --------------------------------------------------------
;; MOST of this namespace is dev instrumentation end to end: the dispose EMIT
;; is `trace/emit`, a no-op under `-Dre-frame.debug=false`, and a deftest whose
;; every claim is about that emit has no semantic residue to run under the
;; production posture. Those deftests are tagged `^:requires-debug`, which the
;; `:prod-gate` lane excludes (`implementation/core/deps.edn`); guarding them
;; instead would leave EMPTY deftests reporting green, the class-2 false green
;; the lane exists to close. The tag is VAR-level, so a new deftest added below
;; is untagged and joins the gate lane by default; the namespace itself is
;; LOADED there regardless. See `scripts/test-core-prod-gate.sh`.
;;
;; ONE IS NOT TAGGED. `input-dispose-throw-is-surfaced-and-isolated` carries
;; emit assertions AND a PRODUCTION sub-cache claim — the guarantee that one
;; input's throwing release does not abort the walk over its siblings.
;; `release-input-ref!` puts the `try`/`catch` OUTSIDE
;; `rf.interop/debug-enabled?` and only the `emit-error!` inside it, so the
;; claim splits cleanly in half: SURFACED is dev, ISOLATED ships. It keeps its
;; emit assertions inside a `(when rf.interop/debug-enabled? …)` arm and runs
;; its semantics in both postures.

(deftest ^:requires-debug dispose-emits-on-last-unsubscribe
  (testing ":rf.sub/dispose fires synchronously with :reason
            :no-more-derefers when the last subscriber detaches;
            carries the canonical tags (frame, id, query-v, reason);
            op-type rides :rf.sub"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 42}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::layer-1-no-derefers)]
      (try
        (let [r (rf/subscribe [:sub/a])]
          (is (= 42 @r))
          (rf/unsubscribe [:sub/a]))
        (let [disposes (dispose-events @acc)]
          (is (= 1 (count disposes))
              "exactly one :rf.sub/dispose for the single eviction")
          (let [[ev] disposes]
            (is (= :rf.sub (:op-type ev))
                ":op-type rides the :rf.sub family")
            (is (= :rf.sub/dispose (:operation ev)))
            (is (= :rf/default (-> ev :tags :frame))
                ":tags :frame is canonical (per Spec 009 §canonical per-frame routing key)")
            (is (= :sub/a (-> ev :tags :rf.sub/id))
                ":tags :rf.sub/id is the query-id")
            (is (= [:sub/a] (-> ev :tags :rf.sub/query-v))
                ":tags :rf.sub/query-v is the full subscription vector")
            (is (= :no-more-derefers (-> ev :tags :rf.sub/reason))
                ":tags :rf.sub/reason :no-more-derefers — sync 1 → 0 path")))
        (finally
          (rf/unregister-listener! :trace ::layer-1-no-derefers))))))

(deftest ^:requires-debug no-dispose-when-ref-count-still-positive
  (testing "with two subscribers, one unsubscribe does NOT emit
            :rf.sub/dispose — the slot's ref-count is still > 0; only
            the SECOND unsubscribe (the last derefer dropping) emits"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 42}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::two-subs)]
      (try
        (rf/subscribe [:sub/a])
        (rf/subscribe [:sub/a])
        (rf/unsubscribe [:sub/a])
        (is (empty? (dispose-events @acc))
            "first unsubscribe — slot still held; no dispose emit")
        (rf/unsubscribe [:sub/a])
        (is (= 1 (count (dispose-events @acc)))
            "second (last) unsubscribe — dispose emitted")
        (is (= :no-more-derefers
               (-> (dispose-events @acc) first :tags :rf.sub/reason)))
        (finally
          (rf/unregister-listener! :trace ::two-subs))))))

(deftest ^:requires-debug dispose-cascade-emits-per-evicted-layer
  (testing "a layer-2 sub's disposal cascades to its layer-1 inputs —
            each evicted slot emits its own :rf.sub/dispose with
            :reason :no-more-derefers"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/reg-sub :sub/b (fn [db _] (:b db)))
    (rf/reg-sub :sub/sum
      {:inputs [[:sub/a] [:sub/b]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::cascade-emit)]
      (try
        (let [r (rf/subscribe [:sub/sum])]
          (is (= 5 @r))
          (rf/unsubscribe [:sub/sum]))
        (let [disposes (dispose-events @acc)
              ids      (set (map #(-> % :tags :rf.sub/id) disposes))]
          (is (= 3 (count disposes))
              ":sub/sum + :sub/a + :sub/b — three eviction emits")
          (is (= #{:sub/sum :sub/a :sub/b} ids)
              "every evicted sub-id surfaces a dispose event")
          (is (every? #(= :no-more-derefers
                          (-> % :tags :rf.sub/reason))
                      disposes)
              "every cascade emit carries the :no-more-derefers reason"))
        (finally
          (rf/unregister-listener! :trace ::cascade-emit))))))

;; ---- :hot-reload ---------------------------------------------------------
;;
;; Re-registering a `:sub` invalidates every cached entry for that
;; sub-id across every frame. The invalidate path emits one
;; `:rf.sub/dispose` per evicted slot with `:reason :hot-reload`.

(deftest ^:requires-debug dispose-hot-reload-fires-per-evicted-slot
  (testing "hot-reloading a sub with N cached query-arg variants fires
            N :rf.sub/dispose events, one per evicted slot, all with
            :reason :hot-reload"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:items {:a 1 :b 2 :c 3}}}))
    (rf/reg-sub :sub/item
      (fn [db [_ k]] (get-in db [:items k])))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::hot-reload-many)]
      (try
        (rf/subscribe [:sub/item :a])
        (rf/subscribe [:sub/item :b])
        (rf/subscribe [:sub/item :c])
        ;; Re-register: every cached entry whose first key is :sub/item
        ;; must be evicted.
        (rf/reg-sub :sub/item
          (fn [db [_ k]] (* 100 (get-in db [:items k]))))
        (let [disposes (dispose-events @acc)
              query-vs (set (map #(-> % :tags :rf.sub/query-v) disposes))]
          (is (= 3 (count disposes))
              "three slots evicted by the single re-registration")
          (is (= #{[:sub/item :a] [:sub/item :b] [:sub/item :c]} query-vs)
              "every cached query-arg variant got its own dispose emit")
          (is (every? #(= :hot-reload (-> % :tags :rf.sub/reason))
                      disposes))
          (is (every? #(= :sub/item (-> % :tags :rf.sub/id)) disposes)
              "every emit names the re-registered sub-id")
          (is (every? #(= :rf/default (-> % :tags :frame)) disposes)
              "every emit carries the canonical :frame"))
        (finally
          (rf/unregister-listener! :trace ::hot-reload-many))))))

;; ---- :cache-clear --------------------------------------------------------
;;
;; An explicit `clear-sub-cache!` walks the cache and disposes every
;; slot; each evicted slot emits a `:rf.sub/dispose` with `:reason
;; :cache-clear`.

(deftest ^:requires-debug clear-sub-cache-emits-exactly-one-dispose-per-slot-for-layered-sub
  (testing "clear-sub-cache! on a layered (two declared inputs) sub: every cached
            slot (the sum + both inputs) gets EXACTLY ONE :rf.sub/dispose,
            reasoned :cache-clear — no double-emit from the on-dispose
            ref-count cascade racing the cache-clear walk"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :sub/ca (fn [db _] (:a db)))
    (rf/reg-sub :sub/cb (fn [db _] (:b db)))
    (rf/reg-sub :sub/csum
      {:inputs [[:sub/ca] [:sub/cb]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::cache-clear-layered)]
      (try
        (let [r     (rf/subscribe [:sub/csum])
              cache (:sub-cache (rf.frame/frame :rf/default))]
          (is (= 5 @r))
          (is (= 3 (count @cache))
              "precondition: sum + both inputs are cached before clear"))
        (rf.subs.cache/clear-sub-cache!)
        (let [disposes (dispose-events @acc)
              by-query (group-by #(-> % :tags :rf.sub/query-v) disposes)]
          (is (= 3 (count disposes))
              "exactly THREE :rf.sub/dispose emits — sum + both inputs, no
               cascade double-emit")
          (is (= #{[:sub/csum] [:sub/ca] [:sub/cb]} (set (keys by-query)))
              "every cached query-vector (sum + both inputs) surfaced exactly
               once")
          (doseq [[q q-evs] by-query]
            (is (= 1 (count q-evs))
                (str q " must fire exactly one :rf.sub/dispose, not a "
                     "duplicate from the ref-count cascade")))
          (is (every? #(= :cache-clear (-> % :tags :rf.sub/reason)) disposes)
              "every emit is reasoned :cache-clear — the cascade found the
               already-cleared cache and never re-emitted :no-more-derefers
               for an input")
          (is (every? #(= :rf/default (-> % :tags :frame)) disposes)
              "every emit carries the canonical :frame"))
        (finally
          (rf/unregister-listener! :trace ::cache-clear-layered))))))

;; ---- per-input dispose-throw is surfaced + isolated -----------------------
;;
;; A layer-2+ reaction's disposal releases its declared-input refs once per
;; input. A throw from ONE input's release is caught so the remaining inputs
;; still release; were the throw simply DISCARDED, a ref-count leak from a
;; buggy custom-substrate `-dispose` would be invisible. So the caught throw is
;; routed through the dev trace as the diagnostic-channel category
;; `:rf.warning/sub-input-dispose-exception` (Spec 009 §Error event
;; catalogue), with best-effort release of the remaining inputs.

(defn- dispose-exception-events
  [traces]
  (filterv #(= :rf.warning/sub-input-dispose-exception (:operation %)) traces))

;; NOT `^:requires-debug`. `rf.subs/release-input-ref!` puts the `try`/`catch`
;; OUTSIDE the gate and only `rf.trace/emit-error!` inside it (see its
;; docstring: "It rides the DIAGNOSTIC channel — `rf.trace/emit-error!` sits
;; inside `rf.interop/debug-enabled?`"). So the claim splits exactly in half:
;; SURFACED is dev, ISOLATED is production. Assertion 2 — the sibling input
;; released, the parent slot evicted — is the half that ships, and it runs
;; under the production posture too.
(deftest input-dispose-throw-is-surfaced-and-isolated
  (testing "when ONE declared input's unsubscribe throws during a layer-2
            reaction's recursive disposal, a
            :rf.warning/sub-input-dispose-exception trace is emitted for
            the failing input AND the remaining inputs STILL release
            (the failing release does not abort the walk)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/reg-sub :sub/b (fn [db _] (:b db)))
    (rf/reg-sub :sub/sum
      {:inputs [[:sub/a] [:sub/b]]}
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])
    (let [acc       (collect-traces! ::input-dispose-throw)
          ;; The REAL unsubscribe — `re-frame.subs/unsubscribe`. `rf/unsubscribe`
          ;; is a `(def ... rf.subs/unsubscribe)` defalias that captured this fn
          ;; VALUE at load, so `with-redefs` on the var below does NOT touch
          ;; `rf/unsubscribe` — only the var-reference calls inside the on-
          ;; dispose callback (which reads the `rf.subs/unsubscribe-if-reaction`
          ;; var) see the redef. The parent dispose is triggered through this
          ;; captured original so the parent itself releases normally; the
          ;; parent's dispose callback then hits the redefed per-input
          ;; releases.
          real-unsub @#'rf.subs/unsubscribe
          ;; The per-input release is not the address-only `unsubscribe`: it
          ;; is the IDENTITY-GUARDED `unsubscribe-if-reaction`, carrying the
          ;; concrete input reaction the parent's build acquired, so the redef
          ;; below targets that var. Redefing `unsubscribe` alone would leave
          ;; the walk untouched and every assertion here
          ;; would read a trace that was never emitted.
          real-unsub-if @#'rf.subs/unsubscribe-if-reaction
          cache      (:sub-cache (rf.frame/frame :rf/default))]
      (try
        ;; Hold the layer-2 sub (which subscribes both inputs, bumping their
        ;; ref-counts to 1 apiece).
        (let [r (rf/subscribe [:sub/sum])]
          (is (= 5 @r))
          (is (contains? @cache [:sub/a]) "input :sub/a slot is live")
          (is (contains? @cache [:sub/b]) "input :sub/b slot is live")
          ;; Make the FIRST input's (`[:sub/a]`) release throw; every other
          ;; query-v (the parent's own trigger goes through `real-unsub`
          ;; directly, and `[:sub/b]` here) delegates to the real fn.
          (with-redefs [rf.subs/unsubscribe-if-reaction
                        (fn [frame-id query-v reaction]
                          (if (= query-v [:sub/a])
                            (throw (ex-info "boom: custom adapter -dispose threw"
                                            {:query-v query-v}))
                            (real-unsub-if frame-id query-v reaction)))]
            ;; Trigger the parent's 1 → 0 dispose via the captured original,
            ;; so the PARENT disposes (its on-dispose callback runs the
            ;; per-input release walk against the redefed
            ;; `rf.subs/unsubscribe-if-reaction`).
            (real-unsub :rf/default [:sub/sum])))
        ;; Assertion 1 — the throw is SURFACED (not discarded): exactly one
        ;; :rf.warning/sub-input-dispose-exception for the failing input,
        ;; carrying the diagnostic tags (frame, the failing query-v, the
        ;; exception, the release site, recovery).
        ;;
        ;; DEV ONLY, and deliberately the SMALLER half. The surfacing is the
        ;; diagnostic channel: `release-input-ref!` reaches it through
        ;; `rf.trace/emit-error!`, which is inside
        ;; `rf.interop/debug-enabled?`. Every assertion in this arm would pass
        ;; VACUOUSLY under the production gate if left unguarded — `warns`
        ;; is `[]` there, so `[ev]` destructures to nil and every `(:x tags)`
        ;; read below is a nil-vs-nil comparison waiting to happen. Assertion
        ;; 2 is outside this arm because the ISOLATION it pins is production
        ;; behaviour: the `try`/`catch` is NOT gated.
        (when rf.interop/debug-enabled?
          (let [warns (dispose-exception-events @acc)]
            (is (= 1 (count warns))
                "exactly one dispose-exception trace for the throwing input")
            (let [[ev] warns
                  tags (:tags ev)]
              ;; The envelope is built by `trace/emit-error!` → `build-event
              ;; :error`, so the runtime `:op-type` is `:error` and the
              ;; warning category rides `:operation` + `[:tags :category]`
              ;; (the catalogue's `:op-type :warning` column is the SEMANTIC
              ;; channel classification, not the envelope op-type — mirrors
              ;; `:rf.warning/teardown-hook-exception`, also emitted via
              ;; `emit-error!`). Asserting the tag/operation shape, not a
              ;; `:warning` envelope op-type, matches that precedent.
              (is (= :rf.warning/sub-input-dispose-exception (:operation ev)))
              (is (= :rf.warning/sub-input-dispose-exception (:category tags))
                  ":category carries the warning id (build-event :error merge)")
              (is (= :rf/default (:frame tags))
                  ":frame is the disposing frame")
              (is (= [:sub/a] (:rf.sub/query-v tags))
                  ":rf.sub/query-v is the input whose release threw")
              (is (some? (:exception tags))
                  "the swallowed exception is carried, not discarded")
              (is (= :on-dispose (:where tags))
                  ":where pins the cached-reaction on-dispose release site")
              ;; `:recovery` is hoisted to the TOP LEVEL by `build-event`
              ;; (the error path always stamps it), NOT left under `:tags`.
              (is (= :ignored (:recovery ev))
                  ":recovery :ignored — best-effort release"))))
        ;; Assertion 2 — the remaining input STILL released despite the
        ;; sibling throw: `:sub/b`'s slot is gone (the walk did not abort on
        ;; the `:sub/a` throw). `:sub/a`'s slot leaks (its release threw) —
        ;; that leak is exactly the otherwise-invisible failure the warning
        ;; surfaces.
        (is (not (contains? @cache [:sub/b]))
            ":sub/b released — the walk continued past the :sub/a throw")
        (is (not (contains? @cache [:sub/sum]))
            "the parent layer-2 slot was evicted")
        (finally
          (rf/unregister-listener! :trace ::input-dispose-throw))))))

;; ---- a substrate dispose of a slot an explicit subscribe still holds -------
;;
;; A ratom-family substrate disposes a cached reaction by its own route when
;; its last watcher drops, without consulting `:ref-count` (Spec 006 §Which
;; lifetime governs a ratom adapter). An explicit `subscribe` is a ref-counted
;; hold on every adapter, so that dispose keeps a slot something still holds:
;; the on-dispose hook releases only the render-owned share and registers
;; itself again, and the `unsubscribe` that finally drives 1 -> 0 still
;; releases the inputs and emits once per evicted layer. The JVM has no render
;; owner, so the share is always zero here, and `rf.interop/dispose!` on the
;; cached reaction is exactly the call that route makes.
;;
;; These pin the cache's lifetime, which ships, so they are untagged and run
;; in the prod-gate lane too; their emit assertions sit behind
;; `rf.interop/debug-enabled?`.

(defn- reg-sum-subs! []
  (rf/reg-event :init (fn [_ _] {:db {:a 2 :b 3}}))
  (rf/reg-sub :sub/a (fn [db _] (:a db)))
  (rf/reg-sub :sub/b (fn [db _] (:b db)))
  (rf/reg-sub :sub/sum
    {:inputs [[:sub/a] [:sub/b]]}
    (fn [[a b] _] (+ a b)))
  (rf/dispatch-sync [:init]))

(defn- slot-ref-counts
  "`{query-v ref-count}` for the three slots, nil for an evicted one."
  [cache]
  (into {}
        (map (fn [q] [q (some-> (get @cache q) :ref-count)]))
        [[:sub/sum] [:sub/a] [:sub/b]]))

(defn- dispose-ids [acc]
  (mapv #(-> % :tags :rf.sub/id) (dispose-events @acc)))

(deftest substrate-dispose-keeps-a-held-slot-until-its-unsubscribe
  (testing "disposing the cached reaction of an explicitly held layer-2 sub
            keeps all three slots at ref-count 1 and emits nothing, twice
            over; the later unsubscribe evicts every layer exactly once"
    (reg-sum-subs!)
    (let [acc   (collect-traces! ::held-substrate-dispose)
          cache (:sub-cache (rf.frame/frame :rf/default))]
      (try
        (let [held (rf/subscribe [:sub/sum])]
          (is (= 5 @held))
          (is (= {[:sub/sum] 1 [:sub/a] 1 [:sub/b] 1} (slot-ref-counts cache))
              "precondition: the explicit hold, and the parent's hold on each input")
          (rf.interop/dispose! held)
          (is (= {[:sub/sum] 1 [:sub/a] 1 [:sub/b] 1} (slot-ref-counts cache))
              "the substrate's dispose evicted nothing the explicit hold keeps")
          (is (identical? held (get-in @cache [[:sub/sum] :reaction]))
              "the kept slot still serves the reaction the caller holds")
          (is (= 5 @held) "the kept reaction still reads")
          ;; A second substrate dispose reaches the hook the first one re-armed.
          (rf.interop/dispose! held)
          (is (= {[:sub/sum] 1 [:sub/a] 1 [:sub/b] 1} (slot-ref-counts cache))
              "the re-armed hook keeps the slot again on the next dispose")
          (when rf.interop/debug-enabled?
            (is (empty? (dispose-events @acc))
                "no :rf.sub/dispose while the explicit hold lives"))
          (rf/unsubscribe [:sub/sum])
          (is (= {[:sub/sum] nil [:sub/a] nil [:sub/b] nil} (slot-ref-counts cache))
              "the unsubscribe evicted the parent and cascaded to both inputs")
          (when rf.interop/debug-enabled?
            (is (= {:sub/sum 1 :sub/a 1 :sub/b 1} (frequencies (dispose-ids acc)))
                "one :rf.sub/dispose per evicted layer, at the unsubscribe")))
        (finally
          (rf/unregister-listener! :trace ::held-substrate-dispose))))))

(deftest re-entrant-dispose-in-the-same-pass-keeps-a-held-slot
  (testing "a callback registered after the cache's hook that re-enters
            rf.interop/dispose! reaches the hook the first pass re-armed;
            the slot stays held at ref-count 1, the callback fires once, and
            the later unsubscribe still cascades exactly once"
    (reg-sum-subs!)
    (let [acc    (collect-traces! ::held-re-entrant-dispose)
          cache  (:sub-cache (rf.frame/frame :rf/default))
          fired  (atom 0)]
      (try
        (let [held (rf/subscribe [:sub/sum])]
          (is (= 5 @held))
          (rf.interop/add-on-dispose! held
            (fn []
              (swap! fired inc)
              (rf.interop/dispose! held)))
          (rf.interop/dispose! held)
          (is (= 1 @fired) "the re-entering callback fired exactly once")
          (is (= {[:sub/sum] 1 [:sub/a] 1 [:sub/b] 1} (slot-ref-counts cache))
              "neither pass released the explicit hold or the input holds")
          (when rf.interop/debug-enabled?
            (is (empty? (dispose-events @acc))
                "no :rf.sub/dispose while the explicit hold lives"))
          (rf/unsubscribe [:sub/sum])
          (is (= {[:sub/sum] nil [:sub/a] nil [:sub/b] nil} (slot-ref-counts cache))
              "the unsubscribe evicted the parent and cascaded to both inputs")
          (is (= 1 @fired) "the spent callback did not fire again")
          (when rf.interop/debug-enabled?
            (is (= {:sub/sum 1 :sub/a 1 :sub/b 1} (frequencies (dispose-ids acc)))
                "one :rf.sub/dispose per evicted layer, at the unsubscribe")))
        (finally
          (rf/unregister-listener! :trace ::held-re-entrant-dispose))))))

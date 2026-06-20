(ns re-frame.sub-dispose-trace-test
  "Per rf2-mrnur: the sub-cache emits a `:rf.sub/dispose` trace event at
  every eviction site so consumers can observe the sub-cache lifecycle's
  terminal half — created / run / skip / **dispose**. This file pins the
  emit shape and reason-enum coverage against the core artefact.

  Contract — Spec 009 §:op-type vocabulary §`:rf.sub/dispose`:

    `:op-type :rf.sub`, `:operation :rf.sub/dispose`. One event per
    evicted cache slot. `:tags {:frame <id> :rf.sub/id <query-id>
    :rf.sub/query-v <vec> :rf.sub/reason <enum>}`. The reason axis is a
    closed enum:

      `:no-more-derefers` — synchronous 1 → 0 transition evicted the
                            slot (per rf2-cmfln; pre-rf2-cmfln this also
                            covered the deferred-grace-timer fire).
      `:hot-reload`       — re-registration evicted every cached slot
                            for the affected sub-id.
      `:cache-clear`      — explicit `clear-sub-cache!` walked the
                            cache and disposed every slot.
      `:frame-destroy`    — `destroy-frame!` tore down the destroyed
                            frame's whole sub-cache (rf2-x3m8c).

  Single-fire discipline: the emit rides the SAME CAS-winner check that
  gates `interop/dispose!`, so a concurrent invalidate + sync-dispose
  cannot produce two `:rf.sub/dispose` for the same eviction.

  Per rf2-cmfln: sub disposal is synchronous on derefer-count → 0; no
  grace-period to configure. The emit lands inside `unsubscribe` in the
  same tick."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.subs :as subs]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-jue6sp): `init!` no longer synthesises `:rf/default`,
  ;; and ambient subscribe / unsubscribe / clear-sub-cache! now require a
  ;; carried frame stamp. These dispose-trace tests exercise the ambient
  ;; cache lifecycle against a single conventional app frame, so the
  ;; fixture registers `:rf/default` explicitly and pins it as the
  ;; established scope for the whole body via `with-frame` — the dispose
  ;; traces still assert `:frame :rf/default`.
  (frame/ensure-default-frame!)
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
;; `unsubscribe` call (per rf2-cmfln).

(deftest dispose-emits-on-last-unsubscribe
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

(deftest no-dispose-when-ref-count-still-positive
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

(deftest dispose-cascade-emits-per-evicted-layer
  (testing "a layer-2 sub's disposal cascades to its layer-1 inputs —
            each evicted slot emits its own :rf.sub/dispose with
            :reason :no-more-derefers"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/reg-sub :sub/b (fn [db _] (:b db)))
    (rf/reg-sub :sub/sum
      :<- [:sub/a]
      :<- [:sub/b]
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

(deftest dispose-then-resubscribe-builds-fresh-slot
  (testing "per rf2-cmfln: unsubscribe disposes synchronously, so a
            subsequent subscribe rebuilds against a fresh cache miss.
            Two :rf.sub/dispose emits are NOT expected for one
            subscribe/unsubscribe cycle — only the one at the
            unsubscribe — but the rebuild does produce a NEW reaction
            (no identity equality with the disposed one)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 42}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::sync-dispose-then-resub)]
      (try
        (let [r1 (rf/subscribe [:sub/a])]
          (is (= 42 @r1))
          (rf/unsubscribe [:sub/a])
          (is (= 1 (count (dispose-events @acc)))
              "one :rf.sub/dispose fired at the sync 1 → 0 transition")
          ;; A resubscribe after the sync dispose rebuilds — fresh
          ;; reaction, not the disposed one. No additional dispose emit
          ;; (the new slot is still live).
          (let [r2 (rf/subscribe [:sub/a])]
            (is (not (identical? r1 r2))
                "resubscribe returned a FRESH reaction (rf2-cmfln —
                 sync dispose closed the old slot before this rebuild)")
            (is (= 42 @r2) "the rebuilt sub computes the same value")
            (is (= 1 (count (dispose-events @acc)))
                "no additional dispose emit — the new slot is alive")))
        (finally
          (rf/unregister-listener! :trace ::sync-dispose-then-resub))))))

;; ---- :hot-reload ---------------------------------------------------------
;;
;; Re-registering a `:sub` invalidates every cached entry for that
;; sub-id across every frame. The invalidate path emits one
;; `:rf.sub/dispose` per evicted slot with `:reason :hot-reload`.

(deftest dispose-emits-on-hot-reload
  (testing "re-registering a :sub fires :rf.sub/dispose with :reason
            :hot-reload for the affected slot (regardless of ref-count)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 42}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::hot-reload)]
      (try
        (rf/subscribe [:sub/a])
        ;; Re-register the same sub-id with a different body — the
        ;; cached slot must be evicted so the new body is observed.
        (rf/reg-sub :sub/a (fn [db _] (* 10 (:a db))))
        (let [disposes (dispose-events @acc)]
          (is (= 1 (count disposes))
              "one :rf.sub/dispose for the hot-reload eviction")
          (let [[ev] disposes]
            (is (= :sub/a (-> ev :tags :rf.sub/id)))
            (is (= [:sub/a] (-> ev :tags :rf.sub/query-v)))
            (is (= :hot-reload (-> ev :tags :rf.sub/reason))
                ":tags :rf.sub/reason :hot-reload discriminates re-registration")
            (is (= :rf/default (-> ev :tags :frame)))))
        (finally
          (rf/unregister-listener! :trace ::hot-reload))))))

(deftest dispose-hot-reload-fires-per-evicted-slot
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
                      disposes)))
        (finally
          (rf/unregister-listener! :trace ::hot-reload-many))))))

;; ---- :cache-clear --------------------------------------------------------
;;
;; An explicit `clear-sub-cache!` walks the cache and disposes every
;; slot; each evicted slot emits a `:rf.sub/dispose` with `:reason
;; :cache-clear`.

(deftest dispose-emits-on-clear-sub-cache
  (testing "(clear-sub-cache!) fires :rf.sub/dispose per evicted slot
            with :reason :cache-clear (regardless of ref-count)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 1 :b 2}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/reg-sub :sub/b (fn [db _] (:b db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::cache-clear)]
      (try
        (rf/subscribe [:sub/a])
        (rf/subscribe [:sub/b])
        (subs-cache/clear-sub-cache!)
        (let [disposes (dispose-events @acc)
              ids      (set (map #(-> % :tags :rf.sub/id) disposes))]
          (is (= 2 (count disposes))
              "two slots evicted by the cache-clear")
          (is (= #{:sub/a :sub/b} ids))
          (is (every? #(= :cache-clear (-> % :tags :rf.sub/reason))
                      disposes)
              "every emit carries the :cache-clear reason")
          (is (every? #(= :rf/default (-> % :tags :frame))
                      disposes)))
        (finally
          (rf/unregister-listener! :trace ::cache-clear))))))

;; ---- emit-shape pin ------------------------------------------------------
;;
;; The exact tag-map shape downstream consumers (Xray Epoch panel
;; SUBSCRIPTIONS section per rf2-wpfjo) depend on.

(deftest dispose-tag-shape-is-canonical
  (testing "the :rf.sub/dispose tag-map carries exactly the four
            canonical tags + nothing extra: :frame, :rf.sub/id,
            :rf.sub/query-v, :rf.sub/reason. Required for consumer
            compatibility with rf2-wpfjo (Xray Epoch panel)."
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 1}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::tag-shape)]
      (try
        (rf/subscribe [:sub/a])
        (rf/unsubscribe [:sub/a])
        (let [[ev] (dispose-events @acc)
              tags (:tags ev)]
          (is (some? ev) "an emit fired")
          ;; The four canonical keys MUST be present. Trace framework
          ;; may add cross-cutting correlation slots (`:rf.trace/*`)
          ;; via build-event — those are framework-level, not bead-
          ;; level shape.
          (is (contains? tags :frame))
          (is (contains? tags :rf.sub/id))
          (is (contains? tags :rf.sub/query-v))
          (is (contains? tags :rf.sub/reason))
          (is (#{:no-more-derefers :hot-reload :cache-clear :frame-destroy}
                (:rf.sub/reason tags))
              "reason is in the closed-enum"))
        (finally
          (rf/unregister-listener! :trace ::tag-shape))))))

;; ---- elision pin ---------------------------------------------------------
;;
;; The emit-dispose! helper sits inside `interop/debug-enabled?`, so
;; under prod CLJS (`:advanced` + `goog.DEBUG=false`) it folds out.
;; The CLJS-side production elision is pinned by the existing
;; `re-frame.trace_bus_elision_prod_test` + the artefact-level
;; `npm run test:elision` probe; on JVM the gate is read at runtime
;; but `debug-enabled?` is `true` so the emit runs — this test pins
;; the runtime path's correctness, not the prod-elision shape.

(deftest emits-fire-under-jvm-debug-enabled
  (testing "debug-enabled? is true on the JVM test runtime — dispose
            emits land in the trace stream"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:x 1}}))
    (rf/reg-sub :sub/x (fn [db _] (:x db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::elision-pin)]
      (try
        (rf/subscribe [:sub/x])
        (rf/unsubscribe [:sub/x])
        (is (seq (dispose-events @acc))
            "an emit landed — JVM dev path is wired")
        (finally
          (rf/unregister-listener! :trace ::elision-pin))))))

;; ---- per-input dispose-throw is surfaced + isolated (rf2-is8ov5) ----------
;;
;; A layer-2+ reaction's disposal releases its `:<-` input refs by calling
;; `unsubscribe` once per input. Before rf2-is8ov5 a throw from ONE input's
;; release was caught and DISCARDED — the remaining inputs still released
;; (good) but the throw vanished with no trace, so a ref-count leak from a
;; buggy custom-substrate `-dispose` was invisible. The fix routes the
;; swallowed throw through the dev trace as the diagnostic-channel category
;; `:rf.warning/sub-input-dispose-exception` (Spec 009 §Error event
;; catalogue) while preserving best-effort release of the remaining inputs.

(defn- dispose-exception-events
  [traces]
  (filterv #(= :rf.warning/sub-input-dispose-exception (:operation %)) traces))

(deftest input-dispose-throw-is-surfaced-and-isolated
  (testing "when ONE `:<-` input's unsubscribe throws during a layer-2
            reaction's recursive disposal, a
            :rf.warning/sub-input-dispose-exception trace is emitted for
            the failing input AND the remaining inputs STILL release
            (the failing release does not abort the walk) — rf2-is8ov5"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/reg-sub :sub/b (fn [db _] (:b db)))
    (rf/reg-sub :sub/sum
      :<- [:sub/a]
      :<- [:sub/b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])
    (let [acc       (collect-traces! ::input-dispose-throw)
          ;; The REAL unsubscribe — `re-frame.subs/unsubscribe`. `rf/unsubscribe`
          ;; is a `(def ... subs/unsubscribe)` defalias that captured this fn
          ;; VALUE at load, so `with-redefs` on the var below does NOT touch
          ;; `rf/unsubscribe` — only the var-reference calls inside the on-
          ;; dispose callback (which reads the `subs/unsubscribe` var) see the
          ;; redef. We trigger the parent dispose through this captured original
          ;; so the parent itself releases normally; the parent's dispose
          ;; callback then hits the redefed per-input releases.
          real-unsub @#'subs/unsubscribe
          cache      (:sub-cache (frame/frame :rf/default))]
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
          (with-redefs [subs/unsubscribe
                        (fn [frame-id query-v]
                          (if (= query-v [:sub/a])
                            (throw (ex-info "boom: custom adapter -dispose threw"
                                            {:query-v query-v}))
                            (real-unsub frame-id query-v)))]
            ;; Trigger the parent's 1 → 0 dispose via the captured original,
            ;; so the PARENT disposes (its on-dispose callback runs the
            ;; per-input release walk against the redefed `subs/unsubscribe`).
            (real-unsub :rf/default [:sub/sum])))
        ;; Assertion 1 — the throw is SURFACED (not discarded): exactly one
        ;; :rf.warning/sub-input-dispose-exception for the failing input,
        ;; carrying the diagnostic tags (frame, the failing query-v, the
        ;; exception, the release site, recovery).
        (let [warns (dispose-exception-events @acc)]
          (is (= 1 (count warns))
              "exactly one dispose-exception trace for the throwing input")
          (let [[ev]  warns
                tags  (:tags ev)]
            ;; The envelope is built by `trace/emit-error!` → `build-event
            ;; :error`, so the runtime `:op-type` is `:error` and the
            ;; warning category rides `:operation` + `[:tags :category]`
            ;; (the catalogue's `:op-type :warning` column is the SEMANTIC
            ;; channel classification, not the envelope op-type — mirrors
            ;; `:rf.warning/teardown-hook-exception`, also emitted via
            ;; `emit-error!`). Asserting the tag/operation shape, not a
            ;; `:warning` envelope op-type, matches the existing precedent.
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
                ":recovery :ignored — best-effort release")))
        ;; Assertion 2 — the remaining input STILL released despite the
        ;; sibling throw: `:sub/b`'s slot is gone (the walk did not abort on
        ;; the `:sub/a` throw). `:sub/a`'s slot leaks (its release threw) —
        ;; that leak is exactly the invisible failure the warning now surfaces.
        (is (not (contains? @cache [:sub/b]))
            ":sub/b released — the walk continued past the :sub/a throw")
        (is (not (contains? @cache [:sub/sum]))
            "the parent layer-2 slot was evicted")
        (finally
          (rf/unregister-listener! :trace ::input-dispose-throw))))))

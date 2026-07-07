(ns re-frame.events-cljs-test
  "Per EP-0018 Slice Z (rf2-xhfxcs.14) — CLJS-side coverage that the ONE
  public event form `reg-event` threads the metadata-map `:interceptors`
  superset chain under the Reagent reactive substrate, that the retired
  positional vector middle slot fails loudly, and that the retired public
  names (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`) are throwing
  stubs.

  This SUPERSEDES the former rf2-bbea CLJS coverage (which asserted
  `:rf.warning/interceptors-in-metadata-map` fired): `:interceptors` inside the
  metadata-map is now the documented home, not a typo. The JVM coverage lives
  in re-frame.events-test; this companion exists so the superset form resolves
  correctly under the Reagent substrate where macro indirection (re-frame.core's
  `reg-event` is a CLJS macro that wraps the runtime fn) might otherwise hide
  the call site.

  ns ends in -cljs-test so shadow-cljs ':node-test' picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; EP-0022 reference-only flip (rf2-0adhqs.9): an INLINE interceptor value in a
;; chain now throws `:rf.error/inline-interceptor-removed` at registration.
;; Chain entries must be REFERENCES, so the formerly-inline `:test/noop` is
;; registered up front and referenced by its bare keyword in the chains below.
;; The chain is stored UNRESOLVED in handler-meta, so a referenced entry reads
;; back as the bare keyword `:test/noop` (NOT a resolved map) — hence the
;; `chain-id` helper, which returns the keyword itself for a ref entry and `:id`
;; for the framework wrapper map at the tail.
(def ^:private noop-icpt-value
  "The inline interceptor VALUE — kept for the negative non-vector
  `:interceptors` test below (which must reject a non-vector value)."
  {:id :test/noop :before identity :after identity})

(defn- chain-id
  "Authored id of a stored chain entry: the keyword itself for a reference
  entry, `:id` for the framework wrapper map at the tail."
  [entry]
  (if (keyword? entry) entry (:id entry)))

(deftest reg-event-metadata-interceptors-threads-the-chain
  (testing "metadata-map :interceptors threads the chain under the Reagent adapter"
    (rf/reg-interceptor :test/noop {:before identity :after identity})
    (rf/reg-event :test.bpmszk.cljs/super
      {:doc "Superset form." :interceptors [:test/noop]}
      (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta :event :test.bpmszk.cljs/super)
          ids  (mapv chain-id (:interceptors meta))]
      (is (= "Superset form." (:doc meta)))
      (is (not (contains? meta :event/kind))
          "the :event/kind sub-tag is gone (one form, no kind)")
      (is (= [:test/noop :rf/event-handler] ids)
          "the metadata-map :interceptors chain (the authored ref) sits before the runtime wrapper"))))

(deftest positional-vector-form-fails-under-reagent
  (testing "positional vector middle slot throws :rf.error/reg-event-bad-middle-slot"
    (is (thrown-with-msg?
          cljs.core/ExceptionInfo
          #":rf\.error/reg-event-bad-middle-slot"
          (rf/reg-event :test.bpmszk.cljs/vector-slot
            [{:id :other :before identity}]
            (fn [{:keys [db]} _] {:db db}))))))

(deftest malformed-metadata-interceptors-fires-under-reagent
  (testing "a non-vector :interceptors value throws :rf.error/reg-event-bad-interceptors"
    (is (thrown-with-msg?
          cljs.core/ExceptionInfo
          #":rf\.error/reg-event-bad-interceptors"
          (rf/reg-event :test.bpmszk.cljs/bad
            {:interceptors noop-icpt-value}
            (fn [{:keys [db]} _] {:db db}))))))

(deftest metadata-interceptors-under-reagent
  (testing "{:interceptors [i]} registers the effective chain"
    (rf/reg-interceptor :test/noop {:before identity :after identity})
    (rf/reg-event :test.bpmszk.cljs/via-map
      {:interceptors [:test/noop]}
      (fn [{:keys [db]} _] {:db db}))
    (let [map-ids (mapv chain-id (:interceptors (rf/handler-meta :event :test.bpmszk.cljs/via-map)))]
      (is (= [:test/noop :rf/event-handler] map-ids)
          "the metadata map registers the effective chain (authored ref + wrapper, in order)"))))

(deftest retired-reg-event-names-throw-their-removal-stubs
  (testing "Per EP-0018 Slice Z — the retired public event-registration names
            are throwing stubs under the Reagent substrate too"
    (letfn [(stub-throw-id [f]
              (try (f)
                   :no-throw
                   (catch cljs.core/ExceptionInfo e
                     (:rf.error/id (ex-data e)))))]
      (is (= :rf.error/reg-event-db-removed
             (stub-throw-id #(rf/reg-event-db :test.slice-z.cljs/db (fn [_ _] nil)))))
      (is (= :rf.error/reg-event-fx-removed
             (stub-throw-id #(rf/reg-event-fx :test.slice-z.cljs/fx (fn [_ _] nil)))))
      (is (= :rf.error/reg-event-ctx-removed
             (stub-throw-id #(rf/reg-event-ctx :test.slice-z.cljs/ctx (fn [_ _] nil))))))))

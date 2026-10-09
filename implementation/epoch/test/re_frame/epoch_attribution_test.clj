(ns re-frame.epoch-attribution-test
  "Which epoch owns an emit that fires outside its cascade (Spec 009
  §Instrumentation, Tool-Pair §Time-travel).

  Renders, reactive sub-runs and unmounts fire at React commit / deref /
  teardown time, after the cascade that caused them has settled. On the JVM
  every trace fires inside `dispatch-sync`, so these cases reproduce that
  timing directly: settle a cascade, then `trace/emit!` with a `:frame` tag,
  no `*handler-scope*` and an empty capture buffer. `capture-event!` then
  back-fills the emit into the epoch that caused it. Cases use several
  distinct cascades so a one-epoch attribution lag cannot pass."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
            [re-frame.elision]
            [re-frame.epoch]
            [re-frame.epoch.state :as rf.epoch.state]
            ;; Publishes the validator a sub's `:schema` check runs through;
            ;; inv-10's recompute emits no failure without it.
            [re-frame.schemas]
            [re-frame.test-support :as rf.test-support]
            [re-frame.machines]))

;; A keep below the depth, so the newest records retain raw :trace-events.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- post-settle emits -----------------------------------------------------

(defn- emit-render!
  "A `:rf.view/rendered` at React-commit timing. Takes a view-id (render-key
  `[view-id 0]`) or a full render-key."
  [frame-id view-or-rk]
  (let [render-key (if (vector? view-or-rk) view-or-rk [view-or-rk 0])]
    (rf.trace/emit! :rf.view :rf.view/rendered
                    {:rf.view/render-key render-key
                     :frame              frame-id})))

(defn- emit-unmount!
  "A `:rf.view/unmounted` at React-teardown timing."
  [frame-id view-or-rk]
  (let [render-key (if (vector? view-or-rk) view-or-rk [view-or-rk 0])]
    (rf.trace/emit! :rf.view :rf.view/unmounted
                    {:rf.view/id         (first render-key)
                     :rf.view/render-key render-key
                     :frame              frame-id})))

(defn- emit-sub-run!
  "A reactive `:rf.sub/run` at React-deref timing: outside any render, so no
  `:rf.sub/reader-render-key`."
  ([frame-id sub-id prev-value value]
   (emit-sub-run! frame-id sub-id prev-value value nil))
  ([frame-id sub-id prev-value value cause-sub]
   (rf.trace/emit! :rf.sub :rf.sub/run
                   {:rf.sub/id             sub-id
                    :rf.sub/query-v        [sub-id]
                    :frame                 frame-id
                    :rf.sub/value-changed? (not= prev-value value)
                    :rf.sub/prev-value     prev-value
                    :rf.sub/value          value
                    :rf.sub/cascade?       (some? cause-sub)
                    :rf.sub/cause-sub      cause-sub})))

(defn- emit-mount-sub-run!
  "The synchronous in-render deref at first paint: stamped with the reading
  view's `:rf.sub/reader-render-key`, which teaches the view's read-set."
  [frame-id sub-id reader-rk prev-value value]
  (rf.trace/emit! :rf.sub :rf.sub/run
                  {:rf.sub/id                sub-id
                   :rf.sub/query-v           [sub-id]
                   :frame                    frame-id
                   :rf.sub/value-changed?    (not= prev-value value)
                   :rf.sub/prev-value        prev-value
                   :rf.sub/value             value
                   :rf.sub/cascade?          false
                   :rf.sub/cause-sub         nil
                   :rf.sub/reader-render-key reader-rk}))

;; ---- record readers --------------------------------------------------------

(defn- epoch-by-id
  "Re-read `epoch`'s record from the ring; back-fills replace it in place."
  [frame-id epoch]
  (some #(when (= (:epoch-id epoch) (:epoch-id %)) %)
        (rf/epoch-history frame-id)))

(defn- last-epoch [frame-id] (last (rf/epoch-history frame-id)))

(defn- rendered-view-ids [record]
  (->> (:renders record) (map (comp first :render-key)) set))

(defn- rendered-keys [record]
  (mapv :render-key (:renders record)))

(defn- sub-run-ids [record]
  (->> (:sub-runs record) (map :sub-id) set))

(defn- sub-run-for [record sub-id]
  (->> (:sub-runs record) (filter #(= sub-id (:sub-id %))) first))

(defn- render-row-for [record render-key]
  (some #(when (= render-key (:render-key %)) %) (:renders record)))

(defn- unmounted-view-ids [record]
  (->> (:trace-events record)
       (filter #(= :rf.view/unmounted (:operation %)))
       (map #(-> % :tags :rf.view/id))
       set))

(defn- trace-ops [record]
  (mapv (juxt :op-type :operation) (:trace-events record)))

(def ^:private cv-rk [:counter-view 6])
(def ^:private tv-rk [:title-view 7])

;; ---- inv-1: a post-settle sub-run rides the cascade that caused it ---------

(deftest inv-1-sub-run-attributed-to-its-own-cascade-multi-cascade
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed         (fn [_ _] {:db {:title "a" :counter 0}}))
  (rf/reg-event :title-loaded (fn [{:keys [db]} _] {:db (assoc db :title "loaded")}))
  (rf/reg-event :counter-inc  (fn [{:keys [db]} _] {:db (update db :counter inc)}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (rf/dispatch-sync [:title-loaded] {:frame :test/main})
  (let [a (last-epoch :test/main)]
    (emit-sub-run! :test/main :title "a" "loaded")
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [b (last-epoch :test/main)]
      (emit-sub-run! :test/main :counter 0 1 :raw-counter)
      (is (= [[{:sub-id :title :value-changed? true :prev-value "a" :value "loaded"
                :cascade? false :cause-sub nil}]
              [{:sub-id :counter :value-changed? true :prev-value 0 :value 1
                :cascade? true :cause-sub :raw-counter}]]
             (for [e [a b]]
               (mapv #(select-keys % [:sub-id :value-changed? :prev-value :value
                                      :cascade? :cause-sub])
                     (:sub-runs (epoch-by-id :test/main e)))))
          "each cascade carries its own sub-run and value attribution, no lag"))))

;; ---- the record-level sensitive rollup is recomputed on back-fill ----------
;;
;; `build-record` computes `:rf.epoch/sensitive?` at settle time; a sensitive
;; post-settle recompute must still flip it, or a drop-gate consumer keeps a
;; record whose only sensitive content arrived by back-fill (Security.md).

(deftest sensitive-rollup-recomputed-on-back-fill
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (let [epoch     (last-epoch :test/main)
        rollup-of #(:rf.epoch/sensitive? (epoch-by-id :test/main epoch))]
    (emit-sub-run! :test/main :plain-sub 0 1)
    (is (false? (rollup-of)) "a non-sensitive back-fill leaves the rollup false")
    (rf.trace/emit! :rf.sub :rf.sub/run
                    {:rf.sub/id             :secret-sub
                     :rf.sub/query-v        [:secret-sub]
                     :frame                 :test/main
                     :sensitive?            true
                     :rf.sub/value-changed? true
                     :rf.sub/value          "topsecret"})
    (is (true? (rollup-of)) "a sensitive back-fill flips the rollup true")))

;; ---- inv-2: a post-settle render rides the cascade that caused it ----------

(deftest inv-2-render-attributed-to-its-causing-cascade-multi-cascade
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed         (fn [_ _] {:db {:title "a" :counter 0}}))
  (rf/reg-event :title-loaded (fn [{:keys [db]} _] {:db (assoc db :title "loaded")}))
  (rf/reg-event :counter-inc  (fn [{:keys [db]} _] {:db (update db :counter inc)}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (rf/dispatch-sync [:title-loaded] {:frame :test/main})
  (let [a (last-epoch :test/main)]
    (emit-render! :test/main :title-view)
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [b (last-epoch :test/main)]
      (emit-render! :test/main :counter-view)
      (is (= [[:title-loaded #{:title-view}] [:counter-inc #{:counter-view}]]
             (map (comp (juxt :event-id rendered-view-ids) #(epoch-by-id :test/main %))
                  [a b]))
          "each cascade carries its own render, no lag"))))

(deftest inv-2-8-in-flight-render-and-unmount-ride-current-cascade
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
  (rf/reg-event :during
    (fn [{:keys [db]} _]
      (rf.trace/emit! :rf.view :rf.view/rendered
                      {:rf.view/render-key [:inline-view 0] :frame :test/main})
      (rf.trace/emit! :rf.view :rf.view/unmounted
                      {:rf.view/id :inline-view :rf.view/render-key [:inline-view 0]
                       :frame :test/main})
      {:db (update db :n inc)}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (rf/dispatch-sync [:during] {:frame :test/main})
  (is (= [:during #{:inline-view} #{:inline-view}]
         ((juxt :event-id rendered-view-ids unmounted-view-ids) (last-epoch :test/main)))
      "emits with a cascade in flight are buffered into it, not back-filled"))

;; ---- inv-3: a late mount render stays on its mount epoch -------------------

(deftest inv-3-late-mount-render-attributed-to-mount-epoch-only
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed        (fn [_ _] {:db {:counter 0}}))
  (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (let [mount-epoch (last-epoch :test/main)]
    (emit-render! :test/main cv-rk)
    (emit-mount-sub-run! :test/main :counter cv-rk nil 0)
    (emit-render! :test/main tv-rk)
    (emit-mount-sub-run! :test/main :title-state tv-rk nil :idle)
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [inc-epoch (last-epoch :test/main)]
      ;; counter-view genuinely re-renders: its sub changed.
      (emit-sub-run! :test/main :counter 0 1)
      (emit-render! :test/main cv-rk)
      ;; title-view's mount render commits late with unchanged inputs.
      (emit-sub-run! :test/main :title-state :idle :idle)
      (emit-render! :test/main tv-rk)
      (is (= [[cv-rk tv-rk] [cv-rk]]
             (map (comp rendered-keys #(epoch-by-id :test/main %)) [mount-epoch inc-epoch]))
          "the late tail stays on the mount epoch, once; only the genuine
           re-render lands on the settling cascade"))))

;; Under `:trace-events-keep 0` the value-change evidence survives only in the
;; structured `:sub-runs`; a scan of raw traces alone would find none and fold
;; the genuine re-render back onto the mount epoch, where it dedups away.
(deftest inv-3-keep-0-render-attributed-via-sub-runs-to-current-epoch
  (rf/configure! {:epoch-history {:trace-events-keep 0}})
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed        (fn [_ _] {:db {:counter 0}}))
  (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (let [mount-epoch (last-epoch :test/main)]
    (emit-mount-sub-run! :test/main :counter cv-rk nil 0)
    (emit-render! :test/main cv-rk)
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [inc-epoch (last-epoch :test/main)]
      (emit-sub-run! :test/main :counter 0 1)
      (emit-render! :test/main cv-rk)
      (let [[inc-rec mount-rec] (map #(epoch-by-id :test/main %) [inc-epoch mount-epoch])]
        (is (not (contains? inc-rec :trace-events)) "precondition: raw traces elided")
        (is (= [[cv-rk] [cv-rk]] (map rendered-keys [inc-rec mount-rec]))
            "the re-render lands on the current epoch; the mount epoch keeps
             only its mount render")))))

;; ---- inv-4: a back-fill re-fans the corrected record to listeners ----------
;;
;; Xray caches epoch-history at settle time, so it re-syncs only when told.

(deftest inv-4-back-fill-renotifies-listeners-with-corrected-attribution
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
  (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (let [seen (atom [])]
    (rf/register-listener! :epoch ::watcher (fn [r] (swap! seen conj r)))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (reset! seen [])
    (emit-sub-run! :test/main :n 0 1)
    (emit-render! :test/main :counter-view)
    (is (= [[:inc #{:n} #{}] [:inc #{:n} #{:counter-view}]]
           (map (juxt :event-id sub-run-ids rendered-view-ids) @seen))
        "each back-fill re-notifies once with the causing epoch's corrected record")))

;; ---- inv-6: an out-of-cascade orphan is never folded into the next epoch ---
;;
;; `make-frame` settles `:initial-events` first, then emits `:rf.frame/created`
;; with no cascade in flight; it must stay uncorrelated (Spec 009 §Dispatch
;; correlation).

(deftest inv-6-frame-created-not-folded-into-next-epoch
  (rf/reg-event :app/init (fn [_ _] {:db {:booted true :n 0}}))
  (rf/reg-event :inc      (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/make-frame {:id :test/main :initial-events [[:app/init]]})
  (rf/dispatch-sync [:inc] {:frame :test/main})
  (let [history (rf/epoch-history :test/main)]
    (is (= [:app/init :inc] (mapv :event-id history))
        ":rf.frame/created is not an epoch of its own")
    (is (not-any? #{[:rf.frame :rf.frame/created]} (mapcat trace-ops history))
        "no epoch's :trace-events carries the orphan")
    (is (every? #(= [:inc] (-> % :tags :rf.event/v))
                (filter #(= :rf.event (:op-type %)) (:trace-events (last history))))
        "every :rf.event trace in the :inc epoch belongs to [:inc]")))

;; A child's `:rf.event/dispatched` marker fires during its parent's do-fx but
;; carries the child's dispatch-id. FIFO siblings can settle first, so it must
;; survive every intervening harvest until the child's own run-start claims it.
(deftest inv-6c-harvest-retains-child-marker-across-sibling-settles
  (let [frame      :test/harvest-sibling
        child-mark {:op-type :rf.event :operation :rf.event/dispatched
                    :tags {:rf.trace/dispatch-id 99 :rf.trace/event-id :child
                           :rf.trace/parent-dispatch-id 1}}
        orphan     {:op-type :rf.frame :operation :rf.frame/created :tags {}}
        rs   (fn [id] {:op-type :rf.event :operation :rf.event/run-start
                       :tags {:rf.trace/phase :run-start :rf.trace/dispatch-id id
                              :rf.trace/event-id id}})
        body (fn [id] {:op-type :rf.event :operation :rf.event/db-changed
                       :tags {:rf.trace/dispatch-id id}})]
    (rf.epoch.state/buffer-event! frame child-mark)
    (rf.epoch.state/buffer-event! frame orphan)
    (doseq [id [7 8]]
      (rf.epoch.state/buffer-event! frame (rs id))
      (rf.epoch.state/buffer-event! frame (body id))
      (is (= [[(rs id) (body id)] [child-mark]]
             [(rf.epoch.state/harvest-buffer-for-event! frame)
              (rf.epoch.state/buffer-for frame)])
          (str "sibling " id " harvests only its own traces; the child marker
                stays verbatim and the nil-id orphan is dropped")))
    (rf.epoch.state/buffer-event! frame (rs 99))
    (rf.epoch.state/buffer-event! frame (body 99))
    (is (= [[child-mark (rs 99) (body 99)] []]
           [(rf.epoch.state/harvest-buffer-for-event! frame)
            (rf.epoch.state/buffer-for frame)])
        "the child's own settle claims its marker")))

(deftest inv-6c-bound-stranded-marker-cleared-by-terminal-path
  ;; The child never runs (no handler), so its marker outlives the parent's
  ;; settle; the child's own rejected settle must clear it, or it accretes.
  (rf/make-frame {:id :test/main})
  (let [buffer-at-parent-settle (atom nil)]
    (rf/register-listener! :epoch ::stranded-probe
      (fn [_] (reset! buffer-at-parent-settle (rf.epoch.state/buffer-for :test/main))))
    (rf/reg-event :parent (fn [_ _] {:fx [[:dispatch [:child-never-registered]]]}))
    (rf/dispatch-sync [:parent] {:frame :test/main})
    (is (= [[:rf.event/dispatched [:child-never-registered]]]
           (mapv (juxt :operation #(-> % :tags :rf.event/v)) @buffer-at-parent-settle))
        "precondition: the child's marker outlived the parent's settle")
    (is (empty? (rf.epoch.state/buffer-for :test/main))
        "the rejected child's settle cleared its stranded marker")))

;; A handler re-registering an existing sibling frame emits
;; `:rf.frame/re-registered` while its own dispatch-id is in scope. A
;; frame-lifecycle emit must not inherit that id, or the marker buffers into
;; the sibling forever (no event of the sibling's ever claims it).
(deftest inv-6d-nested-re-registration-does-not-strand-marker-in-sibling
  (rf/make-frame {:id :test/main})
  (rf/make-frame {:id :test/modal})
  (rf/reg-fx :test/re-reg-modal (fn [_ frame-id] (rf/make-frame {:id frame-id :extra :v})))
  (rf/reg-event :app/reopen (fn [_ _] {:fx [[:test/re-reg-modal :test/modal]]}))
  (rf/dispatch-sync [:app/reopen] {:frame :test/main})
  (is (not-any? #(= :rf.frame (:op-type %)) (rf.epoch.state/buffer-for :test/modal))))

;; ---- the :renders row carries the render's cause and timing ----------------
;;
;; `:cause-event-id` is the slot the Story `:view` causal surface reads; a
;; structural render carries no cause tags, so its row omits the slots.

(deftest renders-projection-carries-cause-and-timing
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (rf.trace/emit! :rf.view :rf.view/rendered
                  {:rf.view/render-key     [:counter-view 0]
                   :frame                  :test/main
                   :rf.view/mount?         false
                   :rf.view/triggered-by   :sub/count
                   :rf.view/elapsed-ms     1.5
                   :rf.view/cause-event-id :counter-inc})
  (rf.trace/emit! :rf.view :rf.view/rendered
                  {:rf.view/render-key [:structural-view 0]
                   :frame              :test/main
                   :rf.view/mount?     false
                   :rf.view/elapsed-ms 0.3})
  (let [epoch (last-epoch :test/main)]
    (is (= {:render-key [:counter-view 0] :mount? false :triggered-by :sub/count
            :elapsed-ms 1.5 :cause-event-id :counter-inc}
           (render-row-for epoch [:counter-view 0])))
    (is (= {:render-key [:structural-view 0] :mount? false :elapsed-ms 0.3}
           (render-row-for epoch [:structural-view 0])))))

;; ---- mount attribution is pruned per instance on unmount -------------------
;;
;; Each mount mints a fresh render-key, so an entry kept until whole-frame
;; destroy would grow without bound across instance churn.

(deftest unmount-prunes-mount-attribution-bounded-across-churn
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed (fn [_ _] {:db {:rows [0 1]}}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (let [seed-id (:epoch-id (last-epoch :test/main))
        rk-a    [:row-view 100]
        rk-b    [:row-view 101]]
    (doseq [rk [rk-a rk-b]]
      (emit-render! :test/main rk)
      (emit-mount-sub-run! :test/main :rows rk nil [0 1]))
    (emit-unmount! :test/main rk-a)
    (is (= [[nil nil] [seed-id #{:rows}]]
           (for [rk [rk-a rk-b]]
             [(rf.epoch.state/mount-epoch-for :test/main rk)
              (rf.epoch.state/render-deps-for :test/main rk)]))
        "the unmounted instance's anchor and read-set are pruned; its sibling's survive")))

;; ---- inv-8: a post-settle unmount rides the cascade that caused it ---------
;;
;; It projects no row, so it lands only on `:trace-events`, where Xray's VIEWS
;; step reads it; without the back-fill it would be orphan-dropped.

(deftest inv-8-unmount-attributed-to-its-own-cascade-multi-cascade
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed   (fn [_ _] {:db {:a? true :b? true}}))
  (rf/reg-event :hide-a (fn [{:keys [db]} _] {:db (assoc db :a? false)}))
  (rf/reg-event :hide-b (fn [{:keys [db]} _] {:db (assoc db :b? false)}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (rf/dispatch-sync [:hide-a] {:frame :test/main})
  (let [a (last-epoch :test/main)]
    (emit-unmount! :test/main :view-a)
    (rf/dispatch-sync [:hide-b] {:frame :test/main})
    (let [b (last-epoch :test/main)]
      (emit-unmount! :test/main :view-b)
      (is (= [[:hide-a #{:view-a} #{}] [:hide-b #{:view-b} #{}]]
             (map (comp (juxt :event-id unmounted-view-ids rendered-view-ids)
                        #(epoch-by-id :test/main %))
                  [a b]))
          "each cascade carries its own unmount, as a trace and not a render"))))

;; ---- inv-9: restore-induced activity attributes to the restored target -----
;;
;; A restore runs no cascade, so `perform-restore!` re-anchors last-settled to
;; the restored epoch; repaints after it must not land in the newer epoch the
;; frame was rewound past.

(deftest inv-9-restore-induced-render-does-not-backfill-into-stale-epoch
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed        (fn [_ _] {:db {:counter 0}}))
  (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))
  (rf/dispatch-sync [:seed]        {:frame :test/main})
  (rf/dispatch-sync [:counter-inc] {:frame :test/main})
  (let [target (last-epoch :test/main)]
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [stale (last-epoch :test/main)]
      (is (true? (rf/restore-epoch! :test/main (:epoch-id target))))
      (emit-render! :test/main :counter-view)
      (is (= [#{} #{:counter-view}]
             (map (comp rendered-view-ids #(epoch-by-id :test/main %)) [stale target]))
          "the repaint lands on the restored target, not the stale epoch"))))

(deftest inv-9-failed-restore-leaves-attribution-anchor-unchanged
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed        (fn [_ _] {:db {:counter 0}}))
  (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))
  (rf/dispatch-sync [:seed]        {:frame :test/main})
  (rf/dispatch-sync [:counter-inc] {:frame :test/main})
  (let [live (last-epoch :test/main)]
    (is (false? (rf/restore-epoch! :test/main :no-such-epoch)))
    (emit-render! :test/main :counter-view)
    (is (contains? (rendered-view-ids (epoch-by-id :test/main live)) :counter-view)
        "the re-anchor is success-only: later activity still lands on the live epoch")))

;; The ring keeps the newer pre-restore epochs, and in a real substrate they
;; carry value-change evidence for the repainted view. The render scan starts
;; at the anchor, so that evidence cannot pull the repaint forward.
(deftest inv-9-post-restore-render-not-backfilled-into-stale-value-change-epoch
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed        (fn [_ _] {:db {:counter 0}}))
  (rf/reg-event :counter-inc (fn [{:keys [db]} _] {:db (update db :counter inc)}))
  (let [render-key [:counter-view 0]]
    (rf/dispatch-sync [:seed]        {:frame :test/main})
    (rf/dispatch-sync [:counter-inc] {:frame :test/main})
    (let [target (last-epoch :test/main)]
      (emit-mount-sub-run! :test/main :counter render-key 0 1)
      (rf/dispatch-sync [:counter-inc] {:frame :test/main})
      (let [stale (last-epoch :test/main)]
        (emit-sub-run! :test/main :counter 1 2)
        (is (= [true true]
               [(contains? (rf.epoch.state/render-deps-for :test/main render-key) :counter)
                (contains? (sub-run-ids (epoch-by-id :test/main stale)) :counter)])
            "premise: the read-set is learned and the stale epoch carries the evidence")
        (is (true? (rf/restore-epoch! :test/main (:epoch-id target))))
        (emit-render! :test/main render-key)
        (is (= [#{} #{:counter-view}]
               (map (comp rendered-view-ids #(epoch-by-id :test/main %)) [stale target]))
            "the repaint lands on the restored target, not the stale evidence-bearing epoch")))))

;; ---- inv-10: a post-settle sub schema failure rides its sub-run's epoch ----
;;
;; Orphan-dropped, the failure would vanish while its run was kept, and Xray
;; would show the replaced nil as a clean SUBSCRIPTIONS row. The recompute here
;; is real: a `:schema :int` sub over a string value, derefed after settle.

(defn- sub-failure? [sub-id trace-event]
  (and (= :rf.error/schema-validation-failure (:operation trace-event))
       (= :sub-return (get-in trace-event [:tags :where]))
       (= sub-id (get-in trace-event [:tags :rf.sub/id]))))

(defn- sub-run-trace? [sub-id trace-event]
  (and (= :rf.sub/run (:operation trace-event))
       (= sub-id (get-in trace-event [:tags :rf.sub/id]))))

(defn- count-traces [pred record]
  (count (filter pred (:trace-events record))))

(deftest inv-10-post-settle-sub-return-failure-rides-its-sub-run-epoch
  (rf/make-frame {:id :test/main})
  (rf/reg-sub :cart/total {:schema :int} (fn [db _] (get-in db [:cart :total])))
  (rf/reg-event :cart/seed (fn [{:keys [db]} _] {:db (assoc-in db [:cart :total] "12.50")}))
  (rf/dispatch-sync [:cart/seed] {:frame :test/main})
  (let [seed (last-epoch :test/main)]
    @(rf/subscribe [:cart/total] {:frame :test/main})
    (is (= [1 1]
           (map #(count-traces % (epoch-by-id :test/main seed))
                [(partial sub-run-trace? :cart/total) (partial sub-failure? :cart/total)]))
        "the run and its :sub-return failure land in the same epoch, once each")))

;; ---- inv-11: a post-settle sub-run names the epoch window it reflects ------
;;
;; A post-settle recompute is filed under the last-settled epoch, but every
;; epoch settled since the sub last ran may have changed its inputs, so its row
;; and trace carry `[first last]` of that window. A run recorded inside its
;; cascade is attributed exactly and carries none.

(defn- window-of
  "`[<row :epoch-window> <trace :rf.sub/epoch-window>]` for `sub-id` in `record`."
  [record sub-id]
  [(:epoch-window (sub-run-for record sub-id))
   (some #(when (sub-run-trace? sub-id %)
            (get-in % [:tags :rf.sub/epoch-window]))
         (:trace-events record))])

(defn- register-runner! []
  (rf/make-frame {:id :test/main})
  (rf/reg-sub :runner/count (fn [db _] (:count db)))
  (rf/reg-event :runner/seed (fn [_ _] {:db {:count 0}}))
  (rf/reg-event :runner/inc  (fn [{:keys [db]} _] {:db (update db :count inc)})))

(deftest inv-11-post-settle-sub-run-window-names-every-epoch-since-its-last-run
  (register-runner!)
  (rf/dispatch-sync [:runner/seed] {:frame :test/main})
  (let [seed      (:epoch-id (last-epoch :test/main))
        count-sub (rf/subscribe [:runner/count] {:frame :test/main})]
    @count-sub
    (is (= [[seed seed] [seed seed]] (window-of (last-epoch :test/main) :runner/count))
        "a first run, with no previous run retained, reaches back to the oldest epoch")
    (rf/dispatch-sync [:runner/inc] {:frame :test/main})
    (rf/dispatch-sync [:runner/inc] {:frame :test/main})
    (let [[a b] (map :epoch-id (take-last 2 (rf/epoch-history :test/main)))]
      @count-sub
      (is (= [[a b] [a b]] (window-of (last-epoch :test/main) :runner/count))
          "both epochs since the previous post-settle run, and not that run's own"))))

(deftest inv-11-in-cascade-sub-run-carries-no-window
  (register-runner!)
  (rf/reg-event :runner/read-then-inc
    (fn [{:keys [db]} _]
      @(rf/subscribe [:runner/count] {:frame :test/main})
      {:db (update db :count inc)}))
  (rf/dispatch-sync [:runner/seed] {:frame :test/main})
  (rf/dispatch-sync [:runner/read-then-inc] {:frame :test/main})
  (let [read-epoch (last-epoch :test/main)]
    (is (= #{:runner/count} (sub-run-ids read-epoch)) "precondition: the run rode its own cascade")
    (is (= [nil nil] (window-of read-epoch :runner/count)))
    (is (not (contains? (sub-run-for read-epoch :runner/count) :epoch-window))
        "the row key is absent, not nil")
    (rf/dispatch-sync [:runner/inc] {:frame :test/main})
    (let [inc-epoch (last-epoch :test/main)
          window    [(:epoch-id read-epoch) (:epoch-id inc-epoch)]]
      @(rf/subscribe [:runner/count] {:frame :test/main})
      (is (= [window window] (window-of (epoch-by-id :test/main inc-epoch) :runner/count))
          "a later post-settle run counts that cascade, which changed the input after the run"))))

(deftest inv-11-render-inherits-the-window-of-the-sub-run-it-follows
  (rf/make-frame {:id :test/main})
  (rf/reg-event :seed (fn [_ _] {:db {:step 0}}))
  (rf/reg-event :a    (fn [{:keys [db]} _] {:db (assoc db :a true)}))
  (rf/reg-event :b    (fn [{:keys [db]} _] {:db (assoc db :b true)}))
  (rf/dispatch-sync [:seed] {:frame :test/main})
  (emit-mount-sub-run! :test/main :step cv-rk nil 0)
  (emit-render! :test/main cv-rk)
  (rf/dispatch-sync [:a] {:frame :test/main})
  (let [a (last-epoch :test/main)]
    (rf/dispatch-sync [:b] {:frame :test/main})
    (let [b (last-epoch :test/main)]
      (emit-sub-run! :test/main :step 0 1)
      (emit-render! :test/main cv-rk)
      (is (= [(:epoch-id a) (:epoch-id b)]
             (:epoch-window (render-row-for (epoch-by-id :test/main b) cv-rk)))
          "the render is no more certain than the windowed sub-run it follows"))))


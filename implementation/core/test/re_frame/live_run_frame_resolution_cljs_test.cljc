(ns re-frame.live-run-frame-resolution-cljs-test
  "EP-0023 §Frame-derived live registration resolution, operationally: a REAL
  frame-targeted `dispatch` / `subscribe` resolves its handler through the
  target frame's resolved image generation end-to-end — not the global
  registrar, and not via a manual `*generation*` binding. Each case registers
  a same-id global handler that must never run. A frame with no image resolves
  globally (absence-is-default).

  `make-frame` returns one runnable image-loaded frame VALUE; its generation
  lives on the frame's record in the one `rf.frame/frames` registry (EP-0024),
  so a frame value and its id resolve the same generation."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.events         :as rf.events]
            [re-frame.image          :as rf.image]
            [re-frame.registrar      :as rf.registrar]
            [re-frame.live-frame     :as rf.live-frame]
            [re-frame.schemas        :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]
            [re-frame.trace.tooling  :as rf.trace.tooling]))

;; explicit {:frame …} targets throughout, so no ambient :rf/default
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter        rf.substrate.plain-atom/adapter
                                               :ambient-frame  nil}))

;; Image-resolver descriptors carry the shape `register!` stores, so a
;; generation-routed `rf.registrar/lookup` returns something the run executes.

(defn- event-desc
  "An image-resolver descriptor for an event id whose handler is `handler-fn` —
  the runnable `rf.events/event-handler-meta` shape merged with the provenance /
  kind / id slots `image-assembly` keys by."
  [provenance-ns id handler-fn]
  (merge (rf.events/event-handler-meta handler-fn)
         {:rf.provenance/ns provenance-ns
          :kind             :event
          :id               id}))

(defn- sub-desc
  "An image-resolver descriptor for a LAYER-1 (app-db reader) sub id whose
  computation is `compute-fn` (`(fn [db query-v] …)`). `reg-sub` stores the
  computation under `:handler-fn` and stamps `:input-kind :db` so the sub-cache
  feeds it the frame's app-db projection as the single signal; a generation-
  routed `rf.registrar/lookup :sub` returns this verbatim, so the descriptor must
  carry `:input-kind` to be recognized as a layer-1 reader (a sub that ignores
  `db` works without it, but one that READS app-db needs the discriminator)."
  [provenance-ns id compute-fn]
  {:rf.provenance/ns provenance-ns
   :kind             :sub
   :id               id
   :input-kind       :db
   :handler-fn       compute-fn})

(defn- with-inline-sub-schema-port
  "Install a deterministic schema port for one body and restore the process
  bundle afterwards. The two deliberately conflicting schema ids make it
  observable whether a frame resolved the image or global registration."
  [calls body]
  (let [snapshot (rf.schemas/schema-fns)]
    (rf.schemas/set-schema-fns!
      {:validate (fn [schema value]
                   (swap! calls conj [schema value])
                   (case schema
                     :image/int     (int? value)
                     :global/string (string? value)
                     true))
       :explain  (fn [schema value]
                   {:schema schema :value value})})
    (try (body)
         (finally (rf.schemas/set-schema-fns! snapshot)))))

(defn- collect-error-events
  "Run `body` while collecting typed error trace events."
  [body]
  (let [seen (atom [])
        id   ::inline-sub-schema-errors]
    (rf.trace.tooling/register-listener! id
      (fn [event]
        (when (= :error (:op-type event))
          (swap! seen conj event))))
    (try (body)
         (finally (rf.trace.tooling/unregister-listener! id)))
    @seen))

(defn- counter-pool
  "An image pool whose :counter/inc increments :n and whose :counter/value reads it."
  [provenance-ns step]
  [(event-desc provenance-ns :counter/inc (fn [{:keys [db]} _] {:db (update db :n (fnil + 0) step)}))
   (sub-desc   provenance-ns :counter/value (fn [db _] (:n db)))])

(defn- reg-global-counter! []
  (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (assoc db :n :global)}))
  (rf/reg-sub   :counter/value (fn [_db _] :global)))

(deftest real-subscribe-resolves-sub-handler-through-frame-image
  (rf/make-frame {:id :counter/main})
  (rf/reg-sub :counter/value (fn [_db _] :global))
  (rf.live-frame/make-frame
    {:id :counter/main
     :images [(rf.image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})]}
    [(sub-desc "examples.counter" :counter/value (fn [_db _] :image))])
  (is (= [:image nil] [@(rf/subscribe [:counter/value] {:frame :counter/main}) rf.registrar/*generation*])
      "the IMAGE sub computed, and the generation binding did not leak past the build"))

(deftest two-frames-different-images-same-id-via-real-dispatch
  ;; EP-0023 §Independent Surfaces On One Page: one id, each frame its own image
  (rf/make-frame {:id :todo/main})
  (rf/make-frame {:id :counter/main})
  (rf/reg-event :boot/init (fn [{:keys [db]} _] {:db (assoc db :booted-by :global)}))
  (doseq [[fid ns-name tag] [[:todo/main "examples.todo" :todo] [:counter/main "examples.counter" :counter]]]
    (rf.live-frame/make-frame
      {:id fid :images [(rf.image/image {:id (keyword "examples" (name tag)) :select-ns {:include [ns-name]}})]}
      [(event-desc ns-name :boot/init (fn [{:keys [db]} _] {:db (assoc db :booted-by tag)}))])
    (rf/dispatch-sync [:boot/init] {:frame fid}))
  (is (= [:todo :counter] (map #(:booted-by (rf/app-db-value %)) [:todo/main :counter/main]))))

(deftest no-image-frame-resolves-through-the-global-registrar
  (rf/make-frame {:id :plain/main})
  (rf/reg-event :plain/set (fn [{:keys [db]} _] {:db (assoc db :written-by :global)}))
  (rf/reg-sub :plain/value (fn [db _] (:written-by db)))
  (rf/dispatch-sync [:plain/set] {:frame :plain/main})
  (is (= [:global :global nil]
         [(:written-by (rf/app-db-value :plain/main))
          @(rf/subscribe [:plain/value] {:frame :plain/main})
          rf.registrar/*generation*])))

(deftest child-dispatch-stays-in-the-frames-image
  ;; a child dispatched via :fx re-enters process-event! for the same frame and
  ;; re-derives the generation, so the drain stays coherent
  (rf/make-frame {:id :counter/main})
  (rf/reg-event :counter/inc  (fn [{:keys [db]} _] {:db (assoc db :inc :global)}))
  (rf/reg-event :counter/step (fn [{:keys [db]} _] {:db (assoc db :step :global)}))
  (rf.live-frame/make-frame
    {:id :counter/main
     :images [(rf.image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})]}
    [(event-desc "examples.counter" :counter/inc
                 (fn [{:keys [db]} _] {:db (assoc db :inc :image) :fx [[:dispatch [:counter/step]]]}))
     (event-desc "examples.counter" :counter/step
                 (fn [{:keys [db]} _] {:db (assoc db :step :image)}))])
  (rf/dispatch-sync [:counter/inc] {:frame :counter/main})
  (is (= {:inc :image :step :image} (select-keys (rf/app-db-value :counter/main) [:inc :step]))))

(deftest two-frames-from-one-image-keep-independent-state
  ;; two direct (no-id) runnable values from ONE image share its generation but
  ;; own independent app-db and sub-cache (EP-0023 §Frame)
  (reg-global-counter!)
  (let [img (rf.image/image {:id :ex/counter :select-ns {:include ["ex.counter"]}})
        pool (counter-pool "ex.counter" 1)
        fa  (rf.live-frame/make-frame {:images [img] :initial-events [[:rf/set-db {:n 0}]]}   pool)
        fb  (rf.live-frame/make-frame {:images [img] :initial-events [[:rf/set-db {:n 100}]]} pool)]
    (is (= [true false]
           [(= (rf.live-frame/frame-generation fa) (rf.live-frame/frame-generation fb))
            (= (:rf.frame/runnable-id fa) (:rf.frame/runnable-id fb))])
        "one shared generation, two distinct backing records")
    (rf/dispatch-sync [:counter/inc] {:frame fa})
    (rf/dispatch-sync [:counter/inc] {:frame fa})
    (rf/dispatch-sync [:counter/inc] {:frame fb})
    (is (= [2 101] [(:n (rf/app-db-value fa)) (:n (rf/app-db-value fb))])
        "the IMAGE inc ran on each frame's own app-db")
    (let [ra  (rf/subscribe [:counter/value] {:frame fa})
          ra2 (rf/subscribe [:counter/value] {:frame fa})
          rb  (rf/subscribe [:counter/value] {:frame fb})]
      (is (= [2 101 true false] [@ra @rb (identical? ra ra2) (identical? ra rb)])
          "each frame reads its own app-db through its own sub-cache"))))

(deftest direct-no-id-object-is-runnable-end-to-end
  ;; the local-harness form: a no-id frame object is dispatched, subscribed,
  ;; read and destroyed directly — no pre-made record, no frame id
  (let [frame (rf.live-frame/make-frame
                {:images [(rf.image/image {:select-ns {:include ["ex.counter"]}})]
                 :initial-events [[:rf/set-db {:n 0}]]}
                (counter-pool "ex.counter" 1))]
    (rf/dispatch-sync [:counter/inc] {:frame frame})
    (is (= [1 {:n 1}] [@(rf/subscribe [:counter/value] {:frame frame})
                       (:rf.db/app (rf/frame-state-value frame))]))
    (rf/destroy-frame! frame)
    (is (nil? (rf/app-db-value frame)) "destroy-frame! accepts the object and tears its record down")))

(deftest opts-form-routes-object-and-keyword-targets-end-to-end
  ;; EP-0023 §Public API: {:frame f} routes a frame OBJECT and a frame-id
  ;; keyword alike, for the queued dispatch as well as dispatch-sync
  (reg-global-counter!)
  (let [img  (rf.image/image {:id :ex/counter :select-ns {:include ["ex.counter"]}})
        pool (counter-pool "ex.counter" 1)
        obj  (rf.live-frame/make-frame {:images [img] :initial-events [[:rf/set-db {:n 0}]]} pool)]
    (rf.live-frame/make-frame {:id :counter/main :images [img] :initial-events [[:rf/set-db {:n 10}]]} pool)
    (rf/dispatch [:counter/inc] {:frame obj})
    (rf/dispatch-sync [:counter/inc] {:frame obj})   ; drains the queue, then runs once more
    (rf/dispatch-sync [:counter/inc] {:frame :counter/main})
    (is (= [2 11] [(:n (rf/app-db-value obj)) (:n (rf/app-db-value :counter/main))]))))

;; An image built from INLINE :registrations lowers to the same runtime
;; descriptor shape as an :include-ns-selected one (EP-0023 §Image Fragments),
;; so its fn bodies run through dispatch / subscribe / fx.

(deftest inline-registrations-sub-fn-body-runs-through-subscribe
  (rf/make-frame {:id :inline/main})
  (rf/reg-sub :counter/value (fn [_db _] :global))
  (rf.live-frame/make-frame
    {:id :inline/main
     :images [(rf.image/image
                {:id :inline/counter
                 :registrations
                 {:reg-event [[:counter/inc {} (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))})]]
                  :reg-sub   [[:counter/value {:doc "Current counter value."} (fn [db _] (:count db 0))]]}})]
     :initial-events [[:rf/set-db {:count 0}]]}
    [])
  (rf/dispatch-sync [:counter/inc] {:frame :inline/main})
  (rf/dispatch-sync [:counter/inc] {:frame :inline/main})
  (is (= [2 nil] [@(rf/subscribe [:counter/value] {:frame :inline/main}) rf.registrar/*generation*])
      "the inline event and sub bodies ran over the frame's own app-db"))

(deftest inline-sub-schema-is-authoritative-for-ordinary-subscribe
  ;; conflicting global schemas would produce the opposite outcome if
  ;; resolution or metadata lowering were wrong
  (let [invalid-q :inline.schema/invalid
        valid-q   :inline.schema/valid
        calls     (atom [])
        result    (atom ::unset)]
    (rf/reg-sub invalid-q {:schema :global/string} (fn [_ _] ::global))
    (rf/reg-sub valid-q   {:schema :global/string} (fn [_ _] ::global))
    (rf.live-frame/make-frame
      {:id :inline/schema-frame
       :images [(rf.image/image
                  {:id :inline/schema
                   :registrations
                   {:reg-sub [[invalid-q {:schema :image/int} (fn [_ _] "accepted-only-by-global")]
                              [valid-q {:schema :image/int} (fn [_ _] 7)]]}})]}
      [])
    (let [errors  (with-inline-sub-schema-port calls
                    #(collect-error-events
                       (fn []
                         (reset! result
                           [(rf/subscribe-once [invalid-q] {:frame :inline/schema-frame})
                            (rf/subscribe-once [valid-q] {:frame :inline/schema-frame})]))))
          failure (first (filter #(= :rf.error/schema-validation-failure (:operation %)) errors))]
      (is (= [nil 7] @result) "an invalid image return recovers to nil; a valid one survives")
      (is (= [[:image/int "accepted-only-by-global"] [:image/int 7]] @calls)
          "both reads consult the image schema, never the global one")
      (is (= {:where :sub-return :frame :inline/schema-frame :rf.sub/id invalid-q
              :failing-id invalid-q :rf.sub/query-v [invalid-q] :schema-id invalid-q
              :received "accepted-only-by-global" :value "accepted-only-by-global"
              :explain {:schema :image/int :value "accepted-only-by-global"}}
             (select-keys (:tags failure) [:where :frame :rf.sub/id :failing-id :rf.sub/query-v
                                           :schema-id :received :value :explain])))
      (is (re-find #":image/int" (str (-> failure :tags :reason)))
          "the reason names the authoritative image schema"))))

(deftest inline-registrations-fx-fn-body-runs-through-pipeline
  (rf/make-frame {:id :inline/main})
  (let [fired (atom [])]
    (rf.live-frame/make-frame
      {:id :inline/main
       :images [(rf.image/image
                  {:id :inline/fx
                   :registrations
                   {:reg-event [[:do/it {} (fn [_ _] {:fx [[:my/side-effect {:n 7}]]})]]
                    :reg-fx    [[:my/side-effect {} (fn [_ctx args] (swap! fired conj args))]]}})]}
      [])
    (rf/dispatch-sync [:do/it] {:frame :inline/main})
    (is (= [{:n 7}] @fired))))

(deftest re-make-frame-swaps-generation-preserving-memory
  ;; EP-0023 §Hot Reload: re-make-frame on the same :id installs a fresh
  ;; generation without tearing the frame down; the next dispatch runs the new
  ;; image against the preserved app-db
  (let [img-v1 (rf.image/image {:id :ex/counter-v1 :select-ns {:include ["ex.counter.v1"]}})
        img-v2 (rf.image/image {:id :ex/counter-v2 :select-ns {:include ["ex.counter.v2"]}})
        frame  (rf.live-frame/make-frame {:id :counter/main :images [img-v1] :initial-events [[:rf/set-db {:n 0}]]}
                                         (counter-pool "ex.counter.v1" 1))]
    (rf/dispatch-sync [:counter/inc] {:frame frame})
    (rf.live-frame/make-frame {:id :counter/main :images [img-v2]} (counter-pool "ex.counter.v2" 10))
    (is (= 1 (:n (rf/app-db-value :counter/main))) "frame memory survived the reload")
    (rf/dispatch-sync [:counter/inc] {:frame :counter/main})
    (is (= 11 (:n (rf/app-db-value :counter/main))) "the same live frame now runs the v2 inc (1 + 10)")))

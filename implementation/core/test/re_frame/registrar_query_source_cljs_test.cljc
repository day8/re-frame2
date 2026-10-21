(ns re-frame.registrar-query-source-cljs-test
  "A registrar query without `:frame` reads the registration store and never
  a bound frame view. `call-with-frame-resolution` binds a frame's generation
  around every subscribe build, dispatch and view resolution, so a store read
  routed through the generation-aware registrar would silently read that
  frame's image (an inspector running in its own frame would see only its own
  registrations).

  The three-way case reads one `[kind id]` carrying three descriptors (store,
  host image, inspector image) from inside the inspector's binding, with a
  control showing the binding is live."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.live-frame     :as rf.live-frame]
            [re-frame.registrar      :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    (rf.image-assembly/clear-standards!)
    (t)
    (rf.image-assembly/clear-standards!)))

(defn- reg-desc [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- err-id
  "The `:rf.error/id` discriminator of a thrown re-frame2 error, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ONE id, THREE descriptors.
(def ^:private shared-id :counter/value)

(def ^:private host-pool
  [(reg-desc "host.core" :sub shared-id ::host-value)
   (reg-desc "host.core" :sub :host/only ::host-only)])

(def ^:private inspector-pool
  [(reg-desc "inspector.core" :sub shared-id ::inspector-value)])

(def ^:private host-img
  (rf.image/image {:id :host/img :select-ns {:include ["host.core"]}}))

(def ^:private inspector-img
  (rf.image/image {:id :inspector/img :select-ns {:include ["inspector.core"]}}))

(defn- seat-three-sources!
  "Register the STORE descriptor and seat the two image-loaded frames.
  Returns the inspector frame object."
  []
  (rf.registrar/register! :sub shared-id {:handler-fn       ::store-value
                                          :rf.provenance/ns "store.core"})
  (rf.registrar/register! :sub :store/only {:handler-fn ::store-only})
  (rf/make-frame {:id :host/main :images [host-img]} host-pool)
  (rf/make-frame {:id :inspector/main :images [inspector-img]} inspector-pool))

(deftest registrar-reads-inside-a-frame-resolution-are-source-discriminated
  (let [inspector (seat-three-sources!)]
    (rf.live-frame/call-with-frame-resolution
      inspector
      (fn []
        (is (= ::inspector-value
               (:handler-fn (rf.registrar/handler-meta :sub shared-id)))
            "control: the generation binding is live")
        (is (= ::store-value
               (:handler-fn (rf/handler-meta {:kind :sub :id shared-id}))))
        ;; containment: framework-standard subs are in the store too
        (is (every? (set (keys (rf/registrations {:kind :sub})))
                    [:store/only shared-id]))
        (is (= ::host-value
               (:handler-fn (rf/handler-meta {:frame :host/main :kind :sub :id shared-id}))))
        (is (= #{shared-id :host/only}
               (set (keys (rf/registrations {:frame :host/main :kind :sub})))))))))

(deftest a-kind-only-read-is-the-store-view
  ;; omitting :frame answers exactly what the registration store's view does
  (seat-three-sources!)
  (rf.registrar/register! :event :dup/id {:handler-fn ::a :rf.provenance/ns "dup.a"})
  (rf.registrar/register! :event :dup/id {:handler-fn ::b :rf.provenance/ns "dup.b"})
  (let [store (rf.live-frame/live-view)]
    (is (= (store :sub) (rf/registrations {:kind :sub})))
    (is (= (store :sub shared-id) (rf/handler-meta {:kind :sub :id shared-id})))
    (is (= (rf.registrar/ambiguous-registrations store :event)
           (rf/ambiguous-registrations {:kind :event})))
    (is (= [::store-value true]
           [(:handler-fn (rf/handler-meta {:kind :sub :id shared-id}))
            (contains? (rf/ambiguous-registrations {:kind :event}) :dup/id)])
        "control: the store's descriptor, and a non-empty ambiguity listing")))

(deftest a-kind-only-read-inside-an-image-loaded-frames-handler-reads-the-store
  ;; the same read, issued by an event handler running on a frame made with
  ;; :images, still answers from the store rather than that frame's image
  (rf.registrar/register! :sub shared-id {:handler-fn       ::store-value
                                          :rf.provenance/ns "store.core"})
  (let [seen  (atom nil)
        probe (rf.image/image
                {:id            :probe/img
                 :registrations {:reg-event
                                 [[:probe/read {}
                                   (fn [_ _]
                                     (reset! seen
                                             [(:handler-fn (rf.registrar/handler-meta :sub shared-id))
                                              (:handler-fn (rf/handler-meta {:kind :sub :id shared-id}))
                                              (:handler-fn (get (rf/registrations {:kind :sub}) shared-id))])
                                     {})]]}})]
    (rf/make-frame {:id :probe/main :images [inspector-img probe]} inspector-pool)
    (rf/dispatch-sync [:probe/read] {:frame :probe/main})
    (is (= [::inspector-value ::store-value ::store-value] @seen)
        "the generation-routed read sees the frame's image, the query reads the store")))

(deftest a-store-read-of-an-ambiguous-id-answers-as-a-frame-without-images
  ;; two namespaces register one id: the store read refuses it exactly as a
  ;; frame made without :images does, and the public door lists it
  (rf.registrar/register! :event :dup/id {:handler-fn ::a :rf.provenance/ns "dup.a"})
  (rf.registrar/register! :event :dup/id {:handler-fn ::b :rf.provenance/ns "dup.b"})
  (rf.registrar/register! :event :dup/single {:handler-fn ::single :rf.provenance/ns "dup.a"})
  (let [frame     (rf/make-frame {})
        refusal   (fn [query]
                    (try (rf/handler-meta query) nil
                         (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
                           (select-keys (ex-data e) [:rf.error/id :namespaces]))))
        listed    (fn [query]
                    (into {} (map (fn [[id ns->desc]] [id (set (keys ns->desc))]))
                          (rf/ambiguous-registrations query)))
        store-err (refusal {:kind :event :id :dup/id})]
    (is (= {:rf.error/id :rf.error/ambiguous-registration :namespaces ["dup.a" "dup.b"]}
           store-err))
    (is (= store-err (refusal {:frame frame :kind :event :id :dup/id}))
        "the frame raises the same refusal")
    (is (= [false true]
           (mapv #(contains? (rf/registrations {:kind :event}) %)
                 [:dup/id :dup/single]))
        "registrations omits the ambiguous id and keeps its single-owner control")
    (is (= {:dup/id #{"dup.a" "dup.b"}}
           (listed {:kind :event})
           (listed {:frame frame :kind :event})))
    (is (= ::b (:handler-fn (rf.registrar/lookup :event :dup/id)))
        "with no frame resolution bound the latest registration answers")))

(deftest the-reserved-empty-frame-kind-is-not-queryable
  ;; {} for a reserved-but-empty slot would read as an authoritative empty
  ;; catalogue; the error names the read that owns the data
  (is (= :rf.error/registrar-kind-not-queryable
         (err-id #(rf/handler-meta {:kind :frame :id :anything}))))
  (let [read-for (fn [kind]
                   (try (rf/registrations {:kind kind}) nil
                        (catch #?(:clj clojure.lang.ExceptionInfo
                                  :cljs cljs.core/ExceptionInfo) e
                          (:read (ex-data e)))))]
    (is (re-find #"frame-ids" (str (read-for :frame))))))

(deftest flow-definitions-answer-the-ordinary-query
  ;; :flow is an ordinary registrar kind: the store read lists and returns
  ;; what was registered under it, and an id nothing registered reads nil
  (rf.registrar/register! :flow :query.flow/total
                          {:inputs [[:a]] :output-path [:total]})
  (is (= [[:total] [:total] nil]
         [(:output-path (get (rf/registrations {:kind :flow})
                             :query.flow/total))
          (:output-path (rf/handler-meta {:kind :flow
                                          :id   :query.flow/total}))
          (rf/handler-meta {:kind :flow :id :query.flow/absent})])))

(deftest unknown-kinds-fail-loud
  (is (= :rf.error/unknown-registry-kind
         (err-id #(rf/registrations {:kind :rf2-kuky/not-a-kind}))))
  (is (= :rf.error/unknown-registry-kind
         (err-id #(rf/registrations {:frame :nope/missing
                                     :kind  :rf2-kuky/not-a-kind})))
      "the kind is refused ahead of :rf.error/frame-no-generation"))

(deftest machine-kinds-read-through-the-store-form
  ;; derived from the machine's :event spec, not registrar kinds, but queryable
  ;; through the same store read; there is no side-table, so registrations is {}
  (is (nil? (rf/handler-meta {:kind :machine-guard
                              :id   [:auth/login :form-valid?]})))
  (is (= {} (rf/registrations {:kind :machine-guard}))))

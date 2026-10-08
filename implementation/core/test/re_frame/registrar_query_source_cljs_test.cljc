(ns re-frame.registrar-query-source-cljs-test
  "The registrar query grammar names its SOURCE explicitly:
  `{:source :store ...}` or `{:frame f ...}`, never a positional default.
  `call-with-frame-resolution` binds a frame's generation around every
  subscribe build, dispatch and view resolution, so a positional store read
  issued inside one would silently read that frame's image (an inspector
  running in its own frame would see only its own registrations).

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
               (:handler-fn (rf/handler-meta {:source :store :kind :sub :id shared-id}))))
        ;; containment: framework-standard subs are in the store too
        (is (every? (set (keys (rf/registrations {:source :store :kind :sub})))
                    [:store/only shared-id]))
        (is (= ::host-value
               (:handler-fn (rf/handler-meta {:frame :host/main :kind :sub :id shared-id}))))
        (is (= #{shared-id :host/only}
               (set (keys (rf/registrations {:frame :host/main :kind :sub})))))))))

(deftest reserved-empty-kinds-are-not-queryable
  ;; {} for a reserved-but-empty slot would read as an authoritative empty
  ;; catalogue; the error names the read that owns the data
  (is (= :rf.error/registrar-kind-not-queryable
         (err-id #(rf/handler-meta {:source :store :kind :flow :id :anything}))))
  (let [read-for (fn [kind]
                   (try (rf/registrations {:source :store :kind kind}) nil
                        (catch #?(:clj clojure.lang.ExceptionInfo
                                  :cljs cljs.core/ExceptionInfo) e
                          (:read (ex-data e)))))]
    (is (re-find #"flows-snapshot" (str (read-for :flow))))
    (is (re-find #"frame-ids" (str (read-for :frame))))))

(deftest unknown-kinds-fail-loud
  (is (= :rf.error/unknown-registry-kind
         (err-id #(rf/registrations {:source :store :kind :rf2-kuky/not-a-kind}))))
  (is (= :rf.error/unknown-registry-kind
         (err-id #(rf/registrations {:frame :nope/missing
                                     :kind  :rf2-kuky/not-a-kind})))
      "the kind is refused ahead of :rf.error/frame-no-generation"))

(deftest machine-kinds-read-through-the-store-form
  ;; derived from the machine's :event spec, not registrar kinds, but queryable
  ;; through the same store form; there is no side-table, so registrations is {}
  (is (nil? (rf/handler-meta {:source :store
                              :kind   :machine-guard
                              :id     [:auth/login :form-valid?]})))
  (is (= {} (rf/registrations {:source :store :kind :machine-guard}))))

(ns re-frame.facade-frame-read-cljs-test
  "The facade's frame-generation reads: `{:frame f ...}` queries on
  `rf/registrations` / `rf/handler-meta` resolve through the target frame's OWN
  sealed image generation, and `rf/frame-generation` returns that generation.
  `:frame` takes a frame id or a frame value. An unresolvable frame, and a query
  naming no source or both, fail loud. The `{:source :store ...}` form is pinned
  in `registrar-query-source-cljs-test`.

  Frames resolve against explicit synthetic descriptor pools (`make-frame`'s
  2-arity), so nothing here depends on the live source store."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    (rf.image-assembly/clear-standards!)
    (t)
    (rf.image-assembly/clear-standards!)))

(defn- reg-desc
  "A synthetic REGISTERED descriptor authored in `provenance-ns` (mirrors the
  source-store output shape the selector consumes)."
  [provenance-ns kind id impl]
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

;; Two images over disjoint namespaces authoring the SAME ids with different
;; impls and provenance.
(def ^:private blue-pool
  [(reg-desc "blue.core" :event :counter/inc   ::blue-inc)
   (reg-desc "blue.core" :sub   :counter/value ::blue-value)])

(def ^:private green-pool
  [(reg-desc "green.core" :event :counter/inc   ::green-inc)
   (reg-desc "green.core" :sub   :counter/value ::green-value)
   (reg-desc "green.core" :event :counter/reset ::green-reset)]) ;; green-only id

(def ^:private blue-img  (rf.image/image {:id :blue/img  :select-ns {:include ["blue.core"]}}))
(def ^:private green-img (rf.image/image {:id :green/img :select-ns {:include ["green.core"]}}))

(deftest frame-arity-resolves-through-the-targets-own-generation
  (rf/make-frame {:id :blue/main  :images [blue-img]}  blue-pool)
  (rf/make-frame {:id :green/main :images [green-img]} green-pool)
  (let [meta-of (fn [frame id]
                  ((juxt :handler-fn :rf.provenance/ns)
                   (rf/handler-meta {:frame frame :kind :event :id id})))]
    (is (= [::blue-inc "blue.core"] (meta-of :blue/main :counter/inc)))
    (is (= [::green-inc "green.core"] (meta-of :green/main :counter/inc)))
    (is (nil? (rf/handler-meta {:frame :blue/main :kind :event :id :counter/reset}))
        "an id the frame's image does not carry is nil")
    (is (some? (rf/handler-meta {:frame :green/main :kind :event :id :counter/reset})))))

(deftest registrations-frame-arity-projects-only-the-frames-ids
  (rf/make-frame {:id :blue/main  :images [blue-img]}  blue-pool)
  (rf/make-frame {:id :green/main :images [green-img]} green-pool)
  (is (= {:counter/inc ::blue-inc}
         (update-vals (rf/registrations {:frame :blue/main :kind :event}) :handler-fn)))
  (is (= {:counter/inc ::green-inc :counter/reset ::green-reset}
         (update-vals (rf/registrations {:frame :green/main :kind :event}) :handler-fn))))

(deftest frame-arity-accepts-a-direct-frame-object
  (let [blue-obj (rf/make-frame {:images [blue-img]} blue-pool)]
    (is (= ::blue-inc (:handler-fn (rf/handler-meta {:frame blue-obj
                                                     :kind  :event
                                                     :id    :counter/inc}))))
    (is (= #{:counter/inc} (set (keys (rf/registrations {:frame blue-obj :kind :event})))))
    (is (= ::blue-inc (:handler-fn (rf.image-assembly/resolve-descriptor
                                     (rf/frame-generation blue-obj)
                                     :event :counter/inc))))))

(deftest frame-generation-returns-the-sealed-generation-key-shape
  (rf/make-frame {:id :blue/main :images [blue-img]} blue-pool)
  (let [gen (rf/frame-generation :blue/main)]
    (is (= #{:rf.gen/resolver :rf.gen/images :rf.gen/kinds :rf.gen/shadows}
           (set (keys gen)))
        "the documented public keys")
    (is (= ::blue-inc (:handler-fn (get (:rf.gen/resolver gen) [:event :counter/inc]))))
    (is (= #{:event :sub} (:rf.gen/kinds gen)))))

;; composed after blue-img, so its inline :counter/inc shadows blue's
(def ^:private blue-override
  (rf.image/image {:id :blue/override
                :registrations {:reg-event [[:counter/inc (fn [_ _] {})]]}}))

(deftest generation-shadows-reports-the-override
  (rf/make-frame {:id :blue/main :images [blue-img blue-override]} blue-pool)
  (is (= [{:registration [:event :counter/inc]
           :image        :blue/img
           :shadowed-by  :blue/override}]
         (:rf.gen/shadows (rf/frame-generation :blue/main))))
  (is (not= ::blue-inc (:handler-fn (rf/handler-meta {:frame :blue/main :kind :event :id :counter/inc})))
      "the later image's descriptor is the live winner"))

(deftest generation-shadows-empty-when-no-override
  (rf/make-frame {:id :blue/main :images [blue-img]} blue-pool)
  (is (= [] (:rf.gen/shadows (rf/frame-generation :blue/main)))))

(deftest frame-target-not-resolving-fails-loud
  ;; no fallback to a default registrar on any read
  (is (= [:rf.error/frame-no-generation
          :rf.error/frame-no-generation
          :rf.error/frame-no-generation
          :rf.error/frame-no-generation]
         [(err-id #(rf/handler-meta {:frame :nope/missing :kind :event :id :counter/inc}))
          (err-id #(rf/registrations {:frame :nope/missing :kind :event}))
          (err-id #(rf/frame-generation :nope/missing))
          (err-id #(rf/registrations {:frame {:totally :fake} :kind :event}))])))

(deftest query-naming-no-single-source-fails-loud
  ;; a read names exactly one source: neither and both are errors, and
  ;; :source admits only :store
  (rf/reg-event :ff/inc {:doc "inc"} (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (is (= [:rf.error/registrar-query-needs-source
          :rf.error/registrar-query-needs-source
          :rf.error/registrar-query-needs-source
          :rf.error/registrar-query-needs-source]
         [(err-id #(rf/handler-meta {:kind :event :id :ff/inc}))
          (err-id #(rf/registrations {:source :store :frame :nope/missing :kind :event}))
          (err-id #(rf/registrations {:source :registrar :kind :event}))
          (err-id #(rf/registrations {:source nil :kind :event}))])))

(deftest non-map-argument-fails-loud
  ;; a leftover positional call gets the catalogued error, not a host error
  ;; from inside the query parse
  (is (= :rf.error/registrar-query-needs-source (err-id #(rf/handler-meta :event))))
  (is (= :rf.error/registrar-query-needs-source (err-id #(rf/registrations :event)))))

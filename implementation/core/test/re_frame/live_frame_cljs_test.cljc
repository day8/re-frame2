(ns re-frame.live-frame-cljs-test
  "EP-0024 §One constructor / §One registry — frame image-loading.
  `rf/make-frame` is the one public constructor: `:images` (always a vector)
  resolves to one sealed generation that lives on the frame's record; an ABSENT
  `:images` runs the DEFAULT image over the whole source store (EP-0026), an
  EMPTY one is an error; an `:id` registers the record in the one `frames`
  registry (re-making it is idempotent replacement), and a no-id frame value is
  local-only. Fail-loud cases check the `:rf.error/id` discriminator, never the
  message (Spec 009 §The thrown-error shape rule 3).

  Most cases resolve against an explicit synthetic descriptor pool (the 2-arity);
  the fixture snapshot/restores the live source store, the standard registry and
  the generation cache, which are process state."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core        :as rf]
            [re-frame.events      :as rf.events]
            [re-frame.frame       :as rf.frame]
            [re-frame.image       :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.live-frame  :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.source-store   :as rf.source-store]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    (let [store-before @rf.source-store/kind->id->ns->descriptor]
      (rf.image-assembly/clear-standards!)
      ;; Re-seed the framework-standard `:rf/set-db` event (EP-0027)
      ;; AFTER clearing standards — these cases seed image-loaded frames via
      ;; `:initial-events [[:rf/set-db …]]`, which resolves `:rf/set-db` through
      ;; the sealed generation (the image standard registry, not the registrar
      ;; atom). The blanket `clear-standards!` keeps OTHER standards out so the
      ;; pure image-resolution contract stays isolated; the framework seed is the
      ;; one standard these tests legitimately depend on.
      (rf.events/register-set-db-standard!)
      (rf.image-assembly/clear-generation-cache!)
      (try
        (t)
        (finally
          (rf.image-assembly/clear-standards!)
          (rf.image-assembly/clear-generation-cache!)
          (reset! rf.source-store/kind->id->ns->descriptor store-before))))))

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

(defn- err-data
  "The full `ex-data` of a thrown re-frame2 error, or nil. The `:extra` slots
  are merged at the top level of the ex-data — see
  `re-frame.error/throw-error!`."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(def ^:private counter-pool
  [(reg-desc "examples.counter" :event :counter/inc ::inc)
   (reg-desc "examples.counter" :sub   :counter/value ::value)])

(defn- resolves? [frame kind id]
  (some? (rf.image-assembly/resolve-descriptor (rf.live-frame/frame-generation frame) kind id)))

(defn- registers-nothing?
  "True when `thunk` leaves the live-frame registry exactly as it found it."
  [thunk]
  (let [before (rf.live-frame/live-frame-ids)]
    (thunk)
    (= before (rf.live-frame/live-frame-ids))))

;; ---- the three-way `:images` boundary (EP-0026 §Default Image) -----------

(deftest empty-images-vector-is-an-error
  (is (= [:rf.error/make-frame-bad-images :rf.error/make-frame-bad-images]
         [(err-id #(rf.live-frame/make-frame {:images []} counter-pool))
          (err-id #(rf.live-frame/make-frame {:images []}))])
      ":images [] fails loud through both arities — pass an image, or OMIT :images")
  (is (registers-nothing? #(err-id (fn [] (rf.live-frame/make-frame {:id :rejected/empty :images []} counter-pool))))))

(deftest absent-images-resolves-the-default-image-generation
  (testing "OMIT :images resolves the DEFAULT generation over the whole pool"
    (let [frame (rf.live-frame/make-frame {} counter-pool)]
      (is (= [true true] [(resolves? frame :event :counter/inc) (resolves? frame :sub :counter/value)]))))
  (is (some? (rf.live-frame/frame-generation (rf.live-frame/make-frame {} [])))
      "an empty pool is a valid standards-only generation, not a zero-match failure")
  (testing "a cross-namespace same-[kind id] collision fails loud at make-frame —
            load order never decides the survivor — and registers nothing"
    (let [colliding-pool [(reg-desc "examples.todo.boot"    :event :boot/init ::todo)
                          (reg-desc "examples.counter.boot" :event :boot/init ::counter)]]
      (is (= :rf.error/image-duplicate-id (err-id #(rf.live-frame/make-frame {} colliding-pool))))
      (is (registers-nothing? #(err-id (fn [] (rf.live-frame/make-frame {:id :dup/default} colliding-pool))))))))

(deftest omit-images-yields-default-explicit-images-untouched
  ;; the adversarial contrast off ONE pool: OMIT projects the whole store, a
  ;; PRESENT explicit image resolves only its own selection
  (let [pool     [(reg-desc "examples.counter" :event :counter/inc  ::inc)
                  (reg-desc "lib.widgets"       :event :widgets/init ::winit)]
        default  (rf.live-frame/make-frame {} pool)
        explicit (rf.live-frame/make-frame
                   {:images [(rf.image/image {:id :examples/counter :select-ns {:include ["examples.counter"]}})]}
                   pool)]
    (is (= [true true true false]
           [(resolves? default :event :counter/inc) (resolves? default :event :widgets/init)
            (resolves? explicit :event :counter/inc) (resolves? explicit :event :widgets/init)]))))

;; ---- :id registers an image-loaded record in the one registry ------------

(deftest id-registers-an-image-loaded-record-in-the-one-registry
  ;; live-frame RECONSTRUCTS a fresh value from the record, routing to the same id
  (let [frame (rf.live-frame/make-frame
                {:id :counter/main :images [(rf.image/image {:select-ns {:include ["examples.counter"]}})]}
                counter-pool)]
    (is (= [:counter/main :counter/main true]
           [(rf.frame/frame-value->id (rf.live-frame/live-frame :counter/main))
            (:rf.frame/id frame)
            (contains? (rf.live-frame/live-frame-ids) :counter/main)]))))

(deftest duplicate-live-id-is-idempotent-replacement
  ;; EP-0024 §Duplicate id policy: re-making a live id refreshes config and
  ;; generation and PRESERVES durable state (no :rf.error/live-frame-id-conflict)
  (let [img (rf.image/image {:select-ns {:include ["examples.counter"]}})]
    (rf.live-frame/make-frame {:id :counter/main :images [img] :initial-events [[:rf/set-db {:count 7}]]}
                              counter-pool)
    (let [again (rf.live-frame/make-frame {:id :counter/main :images [img]} counter-pool)]
      (is (= [:counter/main true true {:count 7}]
             [(rf.frame/frame-value->id again)
              (some? (rf.live-frame/frame-generation :counter/main))
              (contains? (rf.live-frame/live-frame-ids) :counter/main)
              (rf/app-db-value :counter/main)])
          "same id, still image-loaded, and the app-db seeded first survived"))))

;; ---- a direct (no-id) frame bypasses the public frame-id space -----------

(deftest direct-no-id-frame-bypasses-the-public-frame-id-space
  ;; keyed by a private :rf.frame/<gensym> runnable-id and excluded from
  ;; live-frame-ids, so enumeration and auto-reprojection never touch it
  (let [frame (rf.live-frame/make-frame {:images [(rf.image/image {:select-ns {:include ["examples.counter"]}})]}
                                        counter-pool)
        ids   (rf.live-frame/live-frame-ids)]
    (is (= [nil "rf.frame" false true]
           [(:rf.frame/id frame)
            (namespace (rf.frame/frame-value->id frame))
            (contains? ids (rf.frame/frame-value->id frame))
            (every? #(= "rf.frame" (namespace %)) ids)]))))

;; ---- the constructor's argument guards -----------------------------------

(deftest non-vector-images-rejected
  ;; one spelling, always a vector: a bare image map, or a seq even of images, throws
  (let [img (rf.image/image {:select-ns {:include ["examples.counter"]}})]
    (is (= [:rf.error/make-frame-bad-images :rf.error/make-frame-bad-images]
           [(err-id #(rf.live-frame/make-frame {:images img} counter-pool))
            (err-id #(rf.live-frame/make-frame {:images (list img)} counter-pool))]))))

(deftest non-map-opts-rejected
  ;; EP-0024 §One constructor: a non-map opts fails loud before any frame is
  ;; registered, rather than making a garbage frame (nil) or a host
  ;; ClassCastException; the all-defaults frame is (make-frame {})
  (is (= (repeat 5 :rf.error/make-frame-bad-opts)
         [(err-id #(rf.live-frame/make-frame nil counter-pool))
          (err-id #(rf.live-frame/make-frame nil))
          (err-id #(rf.live-frame/make-frame :not-a-map counter-pool))
          (err-id #(rf.live-frame/make-frame [:images []] counter-pool))
          (err-id #(rf.live-frame/make-frame "oops" counter-pool))]))
  (is (registers-nothing? #(do (err-id (fn [] (rf.live-frame/make-frame nil counter-pool)))
                               (err-id (fn [] (rf.live-frame/make-frame :not-a-map counter-pool))))))
  (is (= [{:type :keyword} {:type :string}]
         [(:received (err-data #(rf.live-frame/make-frame :not-a-map counter-pool)))
          (dissoc (:received (err-data #(rf.live-frame/make-frame "oops" counter-pool))) :count)])
      "an EP-0015-safe :received shape summary, never the raw value or a keyword :head")
  (is (rf.live-frame/frame-object? (rf.live-frame/make-frame {} counter-pool)) "the empty map is accepted"))

;; ---- host handles stay out of the serializable frame-state ---------------

(deftest host-handles-excluded-from-serializable-frame-state
  ;; EP-0023 §Host Boundary: the adapter binding rides its own object slot and
  ;; never enters the frame-state value; :initial-events seeds app-db through
  ;; :rf/set-db, so the value carries no :rf.frame/initial-db slot
  (let [adapter    {:rf.adapter/kind :reagent :rf.adapter/render-root ::host-handle}
        state-seed {:count 7 :user/name "ada"}
        frame      (rf.live-frame/make-frame {:id             :counter/host-excl
                                              :images         [(rf.image/image {:select-ns {:include ["examples.counter"]}})]
                                              :initial-events [[:rf/set-db state-seed]]
                                              :adapter        adapter}
                                             counter-pool)]
    (is (= [adapter state-seed nil]
           [(:rf.frame/adapter frame) (rf/app-db-value :counter/host-excl) (:rf.frame/initial-db frame)]))))

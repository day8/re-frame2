(ns re-frame.schemas-resolution-test
  "App-db schemas are the registrar kind `:app-schema`, keyed by the canonical
  path (Spec 010 §Schemas resolve through the frame's images). A registration
  is a frame-agnostic program member; every frame validates against the
  schemas it resolves — live for a frame made without `:images`, through the
  sealed generation for a frame made with them — and a later image's schema
  shadows an earlier one for that frame alone.

  A registration names its namespace through `:ns` metadata, the provenance a
  `reg-app-schema` written in that namespace records, so one test can stand in
  for several namespaces."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(def ^:private this-ns "re-frame.schemas-resolution-test")

(defn- thrown
  "Run `f`, returning `[ex-data ex-message]` of the ex-info it throws, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e [(ex-data e) (ex-message e)])))

(defn- app-image
  "The image selecting this test namespace — the app's own registrations."
  []
  (rf/image {:id :test/app :select-ns {:include [this-ns]}}))

(defn- variant-image
  "An image selecting the registrations made from `probe.variant`."
  []
  (rf/image {:id :test/variant :select-ns {:include ["probe.variant"]}}))

(defn- register-cart-app!
  "The app: a `[:cart]` schema and an event writing a candidate cart."
  []
  (rf/reg-app-schema [:cart] [:map [:items [:vector :keyword]]])
  (rf/reg-event :cart/set (fn [{:keys [db]} [_ cart]] {:db (assoc db :cart cart)})))

(defn- installs?
  "Dispatch `[:cart/set cart]` to `frame` and report whether the candidate
  installed."
  [frame cart]
  (rf/dispatch-sync [:cart/set cart] {:frame frame})
  (= cart (:cart (rf/app-db-value frame))))

;; ---- AC1: the registrar kind ----------------------------------------------

(deftest a-top-level-registration-registers-the-app-schema-kind
  (testing "with no frame in scope, reg-app-schema registers :app-schema
            under the canonical path"
    (let [returned (binding [rf.frame/*current-frame* nil]
                     (rf/reg-app-schema (list :cart) [:map [:items :vector]]))]
      (is (= [:cart] returned))
      (is (vector? returned))
      (is (= [:map [:items :vector]]
             (:schema (rf/handler-meta {:source :store :kind :app-schema :id [:cart]}))))
      (is (= #{[:cart]}
             (set (keys (rf/registrations {:source :store :kind :app-schema})))))
      (is (every? vector? (keys (rf/registrations {:source :store :kind :app-schema})))))))

;; ---- AC2: every frame that resolves the schema validates against it -------

(deftest every-frame-resolving-the-app-validates-against-its-schemas
  (register-cart-app!)
  (let [app-frame   (rf/make-frame {:id :test/app-frame})
        plain-frame (rf/make-frame {})
        image-frame (rf/make-frame {:id :test/image-frame :images [(app-image)]})]
    (doseq [[label frame] [["the app frame" app-frame]
                           ["a plain test frame (make-frame {})" plain-frame]
                           ["a frame made from the app image" image-frame]]]
      (testing label
        (is (installs? frame {:items [:apple]}))
        (is (not (installs? frame {:items "not-a-vector"}))
            "no frame-specific registration is needed for the schema to apply")
        (is (= #{[:cart]} (set (keys (rf.schemas/app-schemas {:frame frame})))))))))

(deftest a-root-schema-applies-to-every-frame-that-resolves-it
  (testing "a required key declared at [] is required in every frame that
            resolves the registration, including one that never writes it"
    (rf/reg-app-schema [] [:map [:session :keyword]])
    (rf/reg-event :other/touch (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
    (let [frame (rf/make-frame {})]
      (rf/dispatch-sync [:other/touch] {:frame frame})
      (is (nil? (:touched (rf/app-db-value frame)))
          "the commit lacks :session, so the root schema rejects it"))))

;; ---- AC3: a later image shadows the schema for its own frames -------------

(deftest a-variant-image-shadows-the-schema-for-its-frame-only
  (register-cart-app!)
  (rf/reg-app-schema [:cart] {:ns 'probe.variant} [:map [:items [:vector {:max 1} :keyword]]])
  (let [app     (rf/make-frame {:id :test/app :images [(app-image)]})
        variant (rf/make-frame {:id :test/variant :images [(app-image) (variant-image)]})
        two     {:items [:apple :pear]}]
    (is (installs? app two) "the app frame runs the app's schema")
    (is (not (installs? variant two)) "the variant frame runs the narrower one")
    (is (installs? variant {:items [:apple]}))
    (is (= [:map [:items [:vector :keyword]]]
           (:schema (rf.schemas/app-schema-meta {:frame app :path [:cart]}))))
    (is (= [:map [:items [:vector {:max 1} :keyword]]]
           (:schema (rf.schemas/app-schema-meta {:frame variant :path [:cart]}))))))

;; ---- AC4: the reads keep their signatures and answer the resolution -------

(deftest the-reads-answer-from-the-frames-resolution
  (register-cart-app!)
  (rf/reg-app-schema [:cart] {:ns 'probe.variant} [:map [:items [:vector {:max 1} :keyword]]])
  (let [app     (rf/make-frame {:id :test/app :images [(app-image)]})
        variant (rf/make-frame {:id :test/variant :images [(app-image) (variant-image)]})]
    (testing "app-schema-meta still carries the registration's source coords"
      (let [m (rf.schemas/app-schema-meta {:frame app :path [:cart]})]
        (is (= ['re-frame.schemas-resolution-test [:cart]] [(:ns m) (:path m)]))
        (is (pos-int? (:line m)))
        (is (string? (:file m))))
      (is (= 'probe.variant (:ns (rf.schemas/app-schema-meta {:frame variant :path [:cart]})))))
    (testing "app-schemas is the registrar's own resolution of the kind"
      (is (= (rf/registrations {:frame variant :kind :app-schema})
             (rf.schemas/app-schemas {:frame variant}))))
    (testing "the digest covers the set each frame resolves"
      (is (not= (rf.schemas/app-schemas-digest {:frame app})
                (rf.schemas/app-schemas-digest {:frame variant})))
      (is (= (rf.schemas/app-schemas-digest {:frame app})
             (rf.schemas/app-schemas-digest {:frame :test/app}))))))

;; ---- AC5: an ambiguous path fails loudly ------------------------------------

(deftest a-path-registered-by-two-namespaces-fails-loudly
  (rf/reg-app-schema [:cart] {:ns 'probe.a} [:map [:items :vector]])
  (rf/reg-app-schema [:cart] {:ns 'probe.b} [:map [:items :vector]])
  (rf/reg-event :cart/set (fn [{:keys [db]} [_ cart]] {:db (assoc db :cart cart)}))
  (rf/reg-event :other/touch (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
  (let [frame (rf/make-frame {:id :test/live})]
    (testing "a commit leaving the path absent installs: nothing there is checked"
      (rf/dispatch-sync [:other/touch] {:frame frame})
      (is (true? (:touched (rf/app-db-value frame)))))
    (testing "the read throws rather than answering without the schema"
      (is (= :rf.error/ambiguous-registration
             (:rf.error/id (first (thrown #(rf.schemas/app-schemas {:frame frame})))))))
    (testing "a commit is rejected, with an error naming the ambiguity"
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:cart/set {:items "never-validated"}] {:frame frame})
        (is (nil? (:cart (rf/app-db-value frame))))
        (is (some #(str/includes? (str (-> % :tags :reason)) "more than one namespace")
                  (filter #(= :rf.error/malformed-schema (:operation %)) @traces)))))))

;; ---- AC6: a frame on a registration is an error ---------------------------

(deftest a-frame-on-a-schema-registration-is-an-error
  (let [fv {rf.frame/object-marker true rf.frame/runnable-id-key :tenant/a}]
    (doseq [[label f received]
            [["the :frame metadata key"
              #(rf/reg-app-schema [:user] {:frame :tenant/a} [:map]) :tenant/a]
             ["a frame value in the metadata slot"
              #(rf/reg-app-schema [:user] fv [:map]) fv]
             ["the bulk :frame opt"
              #(rf/reg-app-schemas {[:user] [:map]} {:frame :tenant/a}) :tenant/a]
             ["a bulk frame-id"
              #(rf/reg-app-schemas {[:user] [:map]} :tenant/a) :tenant/a]
             ["a bulk frame value"
              #(rf/reg-app-schemas {[:user] [:map]} fv) fv]]]
      (testing label
        (let [[data message] (thrown f)]
          (is (= [:rf.error/app-schema-frame-target received]
                 [(:rf.error/id data) (:received data)]))
          (is (str/includes? message "image") "the message names the image route")))))
  (is (= {} (rf/registrations {:source :store :kind :app-schema}))
      "nothing registers"))

;; ---- AC7: clear and the hot-reload sweep -------------------------------------

(deftest clear-removes-a-schema-registration
  (rf/reg-app-schema [:a] :int)
  (rf/reg-app-schema [:b] :int)
  (is (= [:a] (rf/clear :app-schema [:a])))
  (is (= #{[:b]} (set (keys (rf.schemas/app-schemas {:frame :rf/default}))))))

(deftest the-hot-reload-sweep-drops-a-deleted-schema
  (rf/reg-app-schema [:a] {:ns 'probe.reload} :int)
  (rf/reg-app-schema [:b] {:ns 'probe.reload} :int)
  (rf.registrar/begin-reload-cycle!)
  (try
    (rf/reg-app-schema [:a] {:ns 'probe.reload} :int)
    (finally
      (rf.registrar/sweep-reload-cycle!)))
  (when rf.interop/debug-enabled?
    (is (= #{[:a]} (set (keys (rf.schemas/app-schemas {:frame :rf/default}))))
        "the reloaded namespace no longer registers [:b], so it is gone")))

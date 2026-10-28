(ns re-frame.resources-classification-cljs-test
  "Resource owner classification (Spec 015 §Resource and mutation durable
  classification, Spec 016 §SSR and hydration). The coarse whole-entry
  `:sensitive?` / `:large?` claims redact or omit, sensitive winning. The
  fine-grained surface is the projection-relative `:sensitive` / `:large`
  declarations, claimed at egress on each entry the frame holds; the schema
  props validate only. The SSR egress reads those claims, unioned with
  anything the frame itself classifies. A resource's params and scope are its
  identity, so `reg-resource` refuses a declaration rooted at either."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.elision :as rf.elision]
   [re-frame.frame :as rf.frame]
   [re-frame.privacy :as rf.privacy]
   [re-frame.resources.classification :as rf.resources.classification]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   ;; load-bearing side-effecting requires: the façade registers the
   ;; :resource registrar kind; schemas binds the shared walker hooks.
   [re-frame.resources]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; ---- helpers --------------------------------------------------------------

(defn- reg!
  "Register a global-scope slug resource with optional spec overrides."
  ([id] (reg! id {}))
  ([id overrides]
   (rf/clear :resource id)
   (rf/reg-resource id
                    (merge {:scope :rf.scope/global :params-schema [:map [:slug :string]]} overrides)
                    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))))

(defn- entry
  [{:keys [resource-id data]}]
  (merge (rf.resources.state/empty-entry resource-id)
         {:status :loaded :data data :loaded-at 1000 :stale-at 9.0e15}))

;; re-keys the natural `{scoped-key-vector entry}` form into the runtime's
;; byte-keyed `:entries`, stamping each entry's `:resource/key`
(defn- runtime-db-with [entries]
  {rf.resources.state/resources-key {:entries (into {}
                                       (map (fn [[sk e]]
                                              [(rf.resources.state/key-id sk) (assoc e :resource/key sk)]))
                                       entries)
                        :tag-index {} :owner-index {}}})

(defn- install-into-frame!
  "Install `runtime-db`'s entries into `frame-id`'s runtime-db, as a resource
  handler's commit does, so the frame classifies the entries it holds. Returns
  `runtime-db`."
  [frame-id runtime-db]
  (rf.frame/swap-runtime-db! frame-id #(merge % runtime-db))
  runtime-db)

(defn- ssr-wire-entry
  "SSR-project `runtime-db`'s single entry under `frame-id`, the frame holding
  it. Returns the wire entry."
  [frame-id runtime-db]
  (let [held (install-into-frame! frame-id runtime-db)
        proj    (rf/with-frame frame-id (rf.resources.ssr/project-resources-runtime-db held))]
    (val (first (get-in proj [rf.resources.state/resources-key :entries])))))

(defn- ssr-projected-key
  "The key the SSR projection PRODUCED for the single entry in `runtime-db`, read
  from `projection-metadata` because a re-keyed entry is withheld from the
  wire."
  [frame-id runtime-db]
  (let [held (install-into-frame! frame-id runtime-db)]
    (rf/with-frame frame-id
      (first (map :projected-key
                  (rf.resources.ssr/projection-metadata
                    frame-id 5000 (get-in held (rf.resources.state/entries-path))))))))

;; ===========================================================================
;; the coarse disposition and the projection-relative declarations
;; ===========================================================================

(deftest whole-entry-disposition-reads-coarse-root-prop
  (is (= [:serialize :serialize :redact :omit :redact]
         (map rf.resources.classification/whole-entry-disposition
              [{} nil {:sensitive? true} {:large? true} {:sensitive? true :large? true}]))
      "no claim (or no spec) serializes; sensitive redacts, large omits, and sensitive wins"))

(deftest spec-declaration-marks-split-across-projections
  ;; the head segment selects the projection: a bare-rooted path defaults to
  ;; data, a :scope-rooted one rides params whole; schema props contribute nothing
  (let [d {:source :owner-declaration}]
    (is (= {:data   {:sensitive {[:ssn] d [:bare-data] d} :large {[:avatar-bytes] d}}
            :params {:sensitive {[:account-id] d [:scope :tenant] d} :large {[:cursor] d}}}
           (rf.resources.classification/spec-declaration-marks
             {:sensitive     [[:data :ssn] [:params :account-id] [:bare-data] [:scope :tenant]]
              :large         [[:data :avatar-bytes] [:params :cursor]]
              :data-schema   [:map [:token {:sensitive? true} :string] [:title :string]]
              :params-schema [:map [:pin {:sensitive? true} :string] [:slug :string]]})))))

(deftest reg-resource-rejects-malformed-classification-declaration
  (is (thrown-with-msg?
        #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
        #"malformed|resource-bad-spec|classification"
        (rf/reg-resource :rf.test/bad-decl
          {:scope         :rf.scope/global
           :params-schema [:map [:slug :string]]
           :sensitive     {:data [:ssn]}}   ;; a MAP, not a vector-of-paths
          (fn [_ _] {:request {:method :get :url "/x"}})))
      "a non-vector :sensitive axis is rejected at reg-resource"))

(defn- registration-ex [thunk]
  (try (thunk) nil
       (catch #?(:clj Throwable :cljs :default) e e)))

(deftest reg-resource-rejects-a-params-or-scope-rooted-declaration
  ;; params and scope are a resource's identity, not a classified slot, so the
  ;; refusal names the coarse claim that does the job
  (doseq [[axis path] [[:sensitive [:params :account-id]]
                       [:large     [:params :blob]]
                       [:sensitive [:scope :tenant-id]]
                       [:large     [:scope]]]]
    (let [id (keyword "rf.test" (str "identity-decl-" (name axis) "-" (name (first path))))
          ex (registration-ex
               #(rf/reg-resource id
                                 {:scope         :rf.scope/global
                                  :params-schema [:map [:account-id :string]]
                                  axis           [[:data :ok] path]}
                                 (fn [_ _] {:request {:method :get :url "/x"}})))]
      (is (= [:rf.error/resource-bad-spec axis path true]
             [(:rf.error/id (ex-data ex)) (:axis (ex-data ex)) (:path (ex-data ex))
              (str/includes? (str (ex-message ex)) ":sensitive?")])
          (str axis " " path " is refused, pointing at :sensitive?"))
      (is (nil? (rf.resources.registry/resource-meta id)) "refused before storage")))
  (testing "CONTROL — a :data-rooted declaration and a mutation's :params-rooted one still register"
    (reg! :rf.test/data-decl {:sensitive [[:data :ssn]] :large [[:data :blob]]})
    (rf/reg-mutation :rf.test/password-write
      {:params-schema [:map [:password :string]]
       :sensitive     [[:params :password] [:scope :tenant]]}
      (fn [_ _] {:request {:method :post :url "/pw"}}))
    (is (some? (rf.resources.registry/resource-meta :rf.test/data-decl)))
    (is (some? (rf.resources.mutation-registry/mutation-meta :rf.test/password-write)))))

;; ===========================================================================
;; SSR projection end-to-end, registry-driven
;; ===========================================================================

(deftest ssr-serialize-entry-projects-through-frame-classification
  ;; defence in depth: the frame itself classifies a sub-path of a resource
  ;; that carries no claim of its own, and the SSR projection honours it
  (reg! :article/by-slug)
  (rf/make-frame {:id :rcfg/ssr})
  (let [k    (rf.resources.state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"})
        k-id (rf.resources.state/key-id k)
        e    (entry {:resource-id :article/by-slug :data {:secret "tok-xyz" :title "hello"}})]
    (rf.frame/swap-runtime-db! :rcfg/ssr
      (fn [rt] (rf.elision/apply-classification-effects
                 rt {:sensitive [[:rf.runtime/resources :entries k-id :data :secret]]})))
    (let [proj (rf/with-frame :rcfg/ssr
                 (rf.resources.ssr/project-resources-runtime-db (runtime-db-with {k e})))
          we   (val (first (get-in proj [rf.resources.state/resources-key :entries])))]
      (is (= [{:secret rf.privacy/redacted-sentinel :title "hello"} false]
             [(select-keys (:data we) [:secret :title]) (str/includes? (pr-str we) "tok-xyz")])
          "the frame-classified :secret slot is redacted and no raw value rides"))))

(deftest ssr-serialize-entry-ships-owner-large-declaration-slot-whole
  ;; the hydration wire applies no size elision: the client's cached :data must
  ;; be the data, not a marker
  (reg! :report/blob {:large [[:data :blob]]})
  (rf/make-frame {:id :rcfg/owner-large})
  (let [big  (apply str (repeat 1000 "q"))
        k    (rf.resources.state/scoped-resource-key :rf.scope/global :report/blob {:slug "r"})
        e    (entry {:resource-id :report/blob :data {:blob big :name "ok"}})
        we   (ssr-wire-entry :rcfg/owner-large (runtime-db-with {k e}))]
    (is (= [{:blob big :name "ok"} :loaded] [(:data we) (:status we)])
        "the owner-declared :blob slot rides raw under :rf.egress/ssr-hydration")
    (testing "CONTROL — the same classified slot under :rf.egress/off-box-tool still elides"
      (let [projected (rf.resources.classification/project-entry-data
                        (:data e) (rf.resources.state/key-id k) :rcfg/owner-large :rf.egress/off-box-tool)]
        (is (= [true "ok" false]
               [(contains? (:blob projected) :rf.size/large-elided) (:name projected)
                (str/includes? (pr-str projected) big)]))))))

(deftest ssr-plain-owner-key-rides-the-hydration-wire-raw
  ;; hydration ships to the principal's own browser, so a plain owner's scope
  ;; and params ride its wire key verbatim and its row rides, addressable by
  ;; the key the live client derives
  (reg! :report/by-account {:params-schema [:map [:account-id :string] [:slug :string]]})
  (rf/make-frame {:id :rcfg/owner-params})
  (let [k   (rf.resources.state/scoped-resource-key [:rf.scope/session {:user-id "u-42"}]
                                                    :report/by-account
                                                    {:account-id "acct-42" :slug "q3"})
        rdb (runtime-db-with {k (entry {:resource-id :report/by-account :data {:total 99}})})]
    (is (= k (ssr-projected-key :rcfg/owner-params rdb)))
    (is (= [(rf.resources.state/key-id k)]
           (keys (get-in (rf/with-frame :rcfg/owner-params
                           (rf.resources.ssr/project-resources-runtime-db
                             (install-into-frame! :rcfg/owner-params rdb)))
                         [rf.resources.state/resources-key :entries]))))))

;; An infinite feed's :data is the page vector; the index-free claimed decl
;; `[… :data :field]` matches the indexed runtime path on every page.

(deftest ssr-infinite-feed-redacts-sensitive-page-field-per-page
  (reg! :feed/timeline {:infinite        true
                        :next-page-param (fn [_last _all] nil)
                        :sensitive       [[:data :author-email]]})
  (rf/make-frame {:id :rcfg/infinite})
  (let [k  (rf.resources.state/scoped-resource-key :rf.scope/global :feed/timeline {:slug "t"})
        e  (merge (rf.resources.state/empty-infinite-entry :feed/timeline)
                  {:status      :loaded
                   :data        [{:author-email "alice@example.com" :body "hello"}
                                 {:author-email "bob@example.com" :body "world"}]
                   :page-params [nil nil]
                   :loaded-at   1000
                   :stale-at    9.0e15})
        we (ssr-wire-entry :rcfg/infinite (runtime-db-with {k e}))
        r  rf.privacy/redacted-sentinel]
    (is (= [true [{:author-email r :body "hello"} {:author-email r :body "world"}] nil]
           [(vector? (:data we)) (:data we) (re-find #"alice@|bob@" (pr-str we))])
        "every page keeps its shape, redacts its field, and no raw value rides anywhere on the entry")))

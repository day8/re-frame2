(ns re-frame.resources-frame-value-target-cljs-test
  "The resources introspection doors accept a live frame VALUE as their
  `:frame` target, exactly as they accept its ID (rf2-ym12).

  Spec 002's opening principles: a public operation accepts the frame-id
  keyword OR the live frame value `make-frame` returns, and normalizes the
  value to its id. `rf/resource-state` and `rf/mutation-state` once forwarded
  the raw target into id-keyed frame readers, so a value read a live row as a
  silent nil — indistinguishable from a genuinely absent row, which is the
  very ambiguity their fail-closed nil-`:frame` guard exists to prevent.

  Every read below is paired: the value read must EQUAL the id read of the
  same frame, and the id read is what proves the row is present. The controls
  (missing / nil `:frame` refusal, unknown and destroyed frames reading nil,
  another frame staying isolated) are unchanged by the normalization.

  Dual-target (`.cljc` + `-cljs-test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via `cljs-test$`. The
  managed-HTTP transport is replaced by a no-op fx, so no request is issued
  and every ensured row stays in flight."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting requires: the façade registers the
   ;; :rf.resource/* + :rf.mutation/* events these tests dispatch.
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Per-test registrations: a no-op managed-HTTP fx, a named db-derived session
  scope, a global resource, a `{:from-db …}` resource, a mutation, and the app
  event that writes the session scope's app-db input."
  []
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource-scope :fv/session
    {:inputs {:username [:db [:username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-resource :fv/article
    {:scope         :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :get :url (str "/api/articles/" slug)}}))
  (rf/reg-resource :fv/feed
    {:scope         {:from-db :fv/session}
     :params-schema [:map [:page :int]]
     :tags          (fn [_params _data] #{[:feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}}))
  (rf/reg-mutation :fv/save
    {:params-schema [:map [:slug :string]]
     :invalidates   (fn [{:keys [slug]} _result] #{[:article slug]})}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :put :url (str "/api/articles/" slug) :body {:slug slug}}}))
  (rf/reg-event :fv/login
    (fn [{:keys [db]} [_ username]] {:db (assoc db :username username)})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- article [slug]
  {:resource :fv/article :scope :rf.scope/global :params {:slug slug}})

(def ^:private feed {:resource :fv/feed :params {:page 1}})

(defn- session-key [username]
  (rf.resources.state/scoped-resource-key
    [:rf.scope/session {:username username}] :fv/feed {:page 1}))

(defn- seed!
  "Log `frame` in as `username`, then ensure the article `slug`, the session
  feed and one mutation instance in it — every dispatch addressed by the
  frame VALUE."
  [frame username slug instance]
  (rf/dispatch-sync [:fv/login username] {:frame frame})
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :fv/article :params {:slug slug} :owner [:app :fv 1]}]
                    {:frame frame})
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource :fv/feed :params {:page 1} :owner [:app :fv 1]}]
                    {:frame frame})
  (rf/dispatch-sync [:rf.mutation/execute
                     {:mutation :fv/save :params {:slug slug} :instance instance}]
                    {:frame frame}))

;; ===========================================================================
;; A frame value reads the same rows as its id
;; ===========================================================================

(deftest frame-value-target-reads-the-same-rows-as-its-id
  (let [fa (rf/make-frame {:id :fv/frame-a :doc "frame-value target A"})
        fb (rf/make-frame {:id :fv/frame-b :doc "frame-value target B"})]
    (is (rf.frame/frame-value? fa)
        "control: make-frame returned a live frame VALUE, not an id")
    (seed! fa "jake" "a" :fv/save-a)
    (seed! fb "abel" "b" :fv/save-b)
    (testing "resource-state — a value read equals the id read of a present row"
      (let [by-id (rf/resource-state (assoc (article "a") :frame :fv/frame-a))]
        (is (some? by-id) "control: the row is present when read by id")
        (is (= by-id (rf/resource-state (assoc (article "a") :frame fa))))))
    (testing "resource-state — a {:from-db} scope resolves against the value's
              own frame app-db, the same key the id read resolves"
      (let [by-id    (rf/resource-state (assoc feed :frame :fv/frame-a))
            by-value (rf/resource-state (assoc feed :frame fa))]
        (is (= (session-key "jake") (:resource/key by-id)))
        (is (= by-id by-value))
        (is (= (session-key "abel") (:resource/key (rf/resource-state (assoc feed :frame fb))))
            "frame B's value resolves the scope from frame B's app-db")))
    (testing "mutation-state — a value read equals the id read of a present row"
      (let [by-id (rf/mutation-state {:instance :fv/save-a :frame :fv/frame-a})]
        (is (some? by-id) "control: the instance is present when read by id")
        (is (= by-id (rf/mutation-state {:instance :fv/save-a :frame fa})))))
    (testing "another frame stays isolated when addressed by value"
      (is (some? (rf/resource-state (assoc (article "b") :frame fb))))
      (is (nil? (rf/resource-state (assoc (article "b") :frame fa))))
      (is (nil? (rf/resource-state (assoc (article "a") :frame fb))))
      (is (some? (rf/mutation-state {:instance :fv/save-b :frame fb})))
      (is (nil? (rf/mutation-state {:instance :fv/save-b :frame fa})))
      (is (nil? (rf/mutation-state {:instance :fv/save-a :frame fb}))))
    (testing "a destroyed frame reads nil by its retained value and by its id"
      (rf.frame/destroy-frame! fa)
      (is (nil? (rf/resource-state (assoc (article "a") :frame fa))))
      (is (nil? (rf/resource-state (assoc (article "a") :frame :fv/frame-a))))
      (is (nil? (rf/mutation-state {:instance :fv/save-a :frame fa})))
      (is (nil? (rf/mutation-state {:instance :fv/save-a :frame :fv/frame-a})))
      (is (some? (rf/resource-state (assoc (article "b") :frame fb)))
          "frame B is untouched by frame A's teardown"))
    (rf.frame/destroy-frame! fb)))

(deftest anonymous-frame-value-target
  (let [f   (rf/make-frame {:doc "anonymous frame-value target"})
        fid (rf.frame/frame-target->id f)]
    (is (keyword? fid) "control: the anonymous value carries a runnable id")
    (seed! f "anna" "anon" :fv/save-anon)
    (testing "a caller holding only the anonymous value reads the live rows"
      (let [by-id (rf/resource-state (assoc (article "anon") :frame fid))]
        (is (some? by-id))
        (is (= by-id (rf/resource-state (assoc (article "anon") :frame f)))))
      (let [by-id (rf/mutation-state {:instance :fv/save-anon :frame fid})]
        (is (some? by-id))
        (is (= by-id (rf/mutation-state {:instance :fv/save-anon :frame f})))))
    (rf.frame/destroy-frame! f)))

;; ===========================================================================
;; Controls the normalization must not move
;; ===========================================================================

(deftest missing-nil-and-unknown-targets-are-unchanged
  (seed! :rf/default "jake" "a" :fv/save-a)
  (testing "an absent or explicit-nil :frame still fails closed"
    (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"no-frame-context"
          (rf/resource-state (article "a"))))
    (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"no-frame-context"
          (rf/resource-state (assoc (article "a") :frame nil))))
    (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"no-frame-context"
          (rf/mutation-state {:instance :fv/save-a})))
    (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"no-frame-context"
          (rf/mutation-state {:instance :fv/save-a :frame nil}))))
  (testing "an unknown frame id still reads nil"
    (is (nil? (rf/resource-state (assoc (article "a") :frame :fv/no-such-frame))))
    (is (nil? (rf/mutation-state {:instance :fv/save-a :frame :fv/no-such-frame}))))
  (testing "an ordinary id read is unchanged"
    (is (some? (rf/resource-state (assoc (article "a") :frame :rf/default))))
    (is (some? (rf/mutation-state {:instance :fv/save-a :frame :rf/default})))))

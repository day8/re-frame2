(ns day8.re-frame2-xray.panels.resources-optimistic-reach-lint-cljs-test
  "The optimistic-reach lint (rf2-ynkzj) over a REAL settled mutation.

  An optimistic patch can reach a cache key that the mutation's settlement
  never does: `:optimistic-tags` patches the viewer's feed, but `:invalidates`
  names only the article tag and `[:article-list]`. The article is populated
  from the reply, the list is refetched, and the feed keeps the optimistic
  guess — loaded, not stale, never refetched. The runtime says nothing: the
  write-side `:rf.warning/mutation-scope-mismatch` tripwire fires for a
  descriptor that resolved the WRONG scope, not for one that is missing, and
  the instance's `:affected-keys` carries the settlement reach only.

  The `:rf.mutation/optimistic-reconciled` trace already records the
  optimistic keys, and `:rf.mutation/succeeded` carries `:affected-keys`, so
  the lint is a set difference over records that exist. These tests drive the
  resources runtime (plain-atom adapter, a ledger-appending no-op transport,
  replies replayed through the captured `:on-success`) and feed the captured
  trace to the lint:

    1. FORGETS THE FEED — one refetch (the list), the feed stays on the
       optimistic guess, and the lint names exactly that instance and the feed.
    2. COMPLETE — the feed descriptor is present, two refetches, no row.
    3. WRONG SCOPE — the feed descriptor names the wrong scope: the existing
       scope-mismatch warning fires and this lint adds no second row.

  JVM-portable (`.cljc`): the tools/xray JVM corpus and the consolidated
  `:node-test` build both run it."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            ;; load-bearing side-effecting requires: the :rf.resource/* and
            ;; :rf.mutation/* events, and the test reset hooks (which clear the
            ;; scope-mismatch warning's dedupe set between tests).
            [re-frame.resources]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [day8.re-frame2-xray.panels.resources-helpers :as h]))

;; ---- runtime + ledger transport ---------------------------------------------

(def ^:private managed-ledger (atom []))

(defn- init! []
  (rf.registrar/clear-kind! :resource-scope)
  (rf/reg-resource-scope :t/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]]
                           {:db (assoc-in db [:auth :user :username] username)})))

(defn- ledger-transport-fixture [f]
  (reset! managed-ledger [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! managed-ledger conj args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(defn- http-presence-fixture
  "The resources HTTP transport refuses to lower a read unless the managed-HTTP
  artefact has published its presence hook, and the tools/xray JVM classpath
  carries no `day8/re-frame2-http`. The ledger above already stands in for the
  managed fx, so publish a no-op presence hook when none is published and take
  it away afterwards. Where the real artefact is loaded it is left alone."
  [f]
  (let [hook-key :http/abort-on-actor-destroy
        shim?    (nil? (rf.late-bind/get-fn hook-key))]
    (when shim? (rf.late-bind/set-fn! hook-key (fn [& _] nil)))
    (try (f)
         (finally (when shim? (rf.late-bind/set-fn! hook-key nil))))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter :init-fn init!})
  http-presence-fixture
  ledger-transport-fixture)

;; ---- helpers -----------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- reply-ok! [args value]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value value})))

(defn- requests [ledger]
  (mapv (fn [{:keys [request]}] [(:method request) (:url request)]) ledger))

(def ^:private session [:rf.scope/session {:username "jake"}])

(def ^:private article-key
  (rf.resources.state/scoped-resource-key :rf.scope/global :r/article {:slug "welcome"}))
(def ^:private list-key
  (rf.resources.state/scoped-resource-key :rf.scope/global :r/list {}))
(def ^:private feed-key
  (rf.resources.state/scoped-resource-key session :r/feed {}))

(defn- reg-resources! []
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/api/articles/" slug)}}))
  ;; the list carries one member tag per article in its data
  (rf/reg-resource :r/list
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_ data]
             (into #{[:article-list]} (map (fn [a] [:article (:slug a)])) (:articles data)))}
    (fn [_ _] {:request {:method :get :url "/api/articles"}}))
  ;; the feed carries NO member tags — which is what makes the fault visible
  (rf/reg-resource :r/feed
    {:scope {:from-db :t/session}
     :params-schema [:map]
     :tags (fn [_ _] #{[:feed]})}
    (fn [_ _] {:request {:method :get :url "/api/articles/feed"}})))

(defn- favourite-plan
  "A favourite whose `:optimistic-tags` patch the article (global) AND the
  viewer's feed (session), which populates the article from the reply, and whose
  `:invalidates` names the article tag and `[:article-list]` — plus
  `feed-descriptor` when one is given."
  [feed-descriptor]
  {:scope :rf.scope/global
   :params-schema [:map [:slug :string]]
   :optimistic-tags (fn [{:keys [slug]}]
                      [{:scope :rf.scope/global
                        :tags  #{[:article slug]}
                        :patch (fn [d] (assoc d :favorited true))}
                       {:scope {:from-db :t/session}
                        :tags  #{[:feed]}
                        :patch (fn [d] (assoc d :favorited true))}])
   :populates (fn [{:keys [slug]} result]
                {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})
   :invalidates (fn [{:keys [slug]} _result]
                  (cond-> [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}]
                    feed-descriptor (conj feed-descriptor)))})

(defn- settle-favourite!
  "Load the article, the list and the feed under live owners, execute
  `mutation-id` registered with `plan`, and reply ok. Returns the trace
  captured across the execute and the reply, and the refetches the reply
  issued."
  [mutation-id plan]
  (reg-resources!)
  (rf/dispatch-sync [:t/login "jake"])
  (doseq [[payload value]
          [[{:resource :r/article :scope :rf.scope/global :params {:slug "welcome"} :owner [:v :article]}
            {:slug "welcome" :favorited false}]
           [{:resource :r/list :scope :rf.scope/global :params {} :owner [:v :list]}
            {:articles [{:slug "welcome" :favorited false}]}]
           [{:resource :r/feed :scope {:from-db :t/session} :params {} :owner [:v :feed]}
            {:articles [{:slug "welcome" :favorited false}]}]]]
    (reset! managed-ledger [])
    (rf/dispatch-sync [:rf.resource/ensure payload])
    (reply-ok! (last @managed-ledger) value))
  (rf/reg-mutation mutation-id plan
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/api/articles/" slug "/favorite")}}))
  (let [seen (atom [])
        k    ::recorder]
    (rf.trace.tooling/register-listener! k (fn [ev] (swap! seen conj ev)))
    (try
      (reset! managed-ledger [])
      (rf/dispatch-sync [:rf.mutation/execute {:mutation mutation-id :params {:slug "welcome"}
                                               :instance :fav-1}])
      (let [mutation-args (last @managed-ledger)]
        (reset! managed-ledger [])
        (reply-ok! mutation-args {:slug "welcome" :favorited true})
        {:trace @seen :refetches (requests @managed-ledger)})
      (finally (rf.trace.tooling/unregister-listener! k)))))

(defn- tags-of [op trace]
  (:tags (last (filter #(= op (:operation %)) trace))))

;; ---- 1. forgets the feed -------------------------------------------------------

(deftest forgets-the-feed-leaves-it-optimistic-and-the-lint-names-it
  (let [{:keys [trace refetches]} (settle-favourite! :m/favorite-forgets-feed (favourite-plan nil))
        recon    (tags-of :rf.mutation/optimistic-reconciled trace)
        affected (set (:affected-keys (tags-of :rf.mutation/succeeded trace)))]
    (testing "the reproduction: the article is populated, ONE refetch, the feed keeps the guess"
      (is (= {:slug "welcome" :favorited true} (:data (entry article-key))))
      (is (= [[:get "/api/articles"]] refetches))
      (is (= [:loaded nil] ((juxt :status :invalidated-at) (entry feed-key)))
          "the feed is loaded and never marked stale")
      (is (= true (get-in (entry feed-key) [:data :favorited]))
          "the feed still shows the optimistic value"))
    (testing "the records: the optimistic keys reach the feed, the settlement reach does not"
      (is (= #{article-key list-key feed-key} (set (:optimistic-keys recon))))
      (is (= [article-key] (:committed recon)))
      (is (= [list-key] (:reconciliation-refetches recon)))
      (is (= #{article-key list-key} affected))
      (is (= #{feed-key}
             (set/difference (set (:optimistic-keys recon))
                             (set (:committed recon))
                             (set (:reconciliation-refetches recon))
                             affected))
          "the set difference names the feed key and nothing else"))
    (testing "the lint: exactly one row, naming the instance, the mutation and the feed key"
      (let [rows (h/optimistic-reach-lint trace)
            row  (first rows)]
        (is (= 1 (count rows)))
        (is (= :fav-1 (:instance row)))
        (is (= :m/favorite-forgets-feed (:mutation row)))
        (is (= [:r/feed] (mapv :resource-id (:missing-keys row))))
        (is (re-find #":r/feed" (:hint row)) "the wording names what was not reconciled")
        (is (not (re-find #"(?i)defect|bug" (:hint row)))
            "leaving a value optimistic can be deliberate, so the row does not call it a defect")))
    (testing "dedupe-keyed: the same settled instance seen twice is still one row"
      (is (= 1 (count (h/optimistic-reach-lint (into trace trace))))))))

;; ---- 2. the complete favourite -------------------------------------------------

(deftest complete-favourite-reconciles-every-optimistic-key
  (let [{:keys [trace refetches]}
        (settle-favourite! :m/favorite
                           (favourite-plan {:scope {:from-db :t/session} :tags #{[:feed]}}))
        recon (tags-of :rf.mutation/optimistic-reconciled trace)]
    (testing "the reproduction: TWO refetches, the list and the feed"
      (is (= #{[:get "/api/articles"] [:get "/api/articles/feed"]} (set refetches)))
      (is (= 2 (count refetches))))
    (testing "every optimistic key is committed or refetched"
      (is (= #{list-key feed-key} (set (:reconciliation-refetches recon)))))
    (testing "the lint: no row"
      (is (empty? (h/optimistic-reach-lint trace))))))

;; ---- 3. a wrong-scope descriptor -----------------------------------------------

(deftest wrong-scope-descriptor-keeps-to-the-scope-mismatch-warning
  (let [{:keys [trace]}
        (settle-favourite! :m/favorite-wrong-scope
                           (favourite-plan {:scope :rf.scope/global :tags #{[:feed]}}))
        warning (tags-of :rf.warning/mutation-scope-mismatch trace)]
    (testing "the existing write-side tripwire fires for the wrong scope"
      (is (some? warning))
      (is (= :m/favorite-wrong-scope (:mutation warning)))
      (is (= session (:other-scope warning)))))
    (testing "the lint adds no second row for it"
      (is (empty? (h/optimistic-reach-lint trace))))
    (testing "because the warning covers that key: without it the lint would name the feed"
      (is (= [:r/feed]
             (->> trace
                  (remove #(= :rf.warning/mutation-scope-mismatch (:operation %)))
                  h/optimistic-reach-lint
                  (mapcat :missing-keys)
                  (mapv :resource-id)))))))

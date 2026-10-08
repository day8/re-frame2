(ns re-frame.resources-invalidation-descriptors-cljs-test
  "Scoped invalidation descriptors (Spec 016 §Scoped invalidation
  descriptors). A mutation's :invalidates is either a bare tag-set, which
  invalidates the mutation's resolved scope (:rf.scope/same), or descriptors
  {:scope … :tags #{…}}, each naming its own scope. Both lower to one engine
  that resolves scopes at settle time and fails closed on a nil or malformed
  scope."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
   [re-frame.registrar :as rf.registrar]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.error-emit :as rf.error-emit]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))

(defn- init! []
  (rf.registrar/clear-kind! :resource-scope)
  (rf/reg-resource-scope :t/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-event :t/login (fn [{:keys [db]} [_ username]] {:db (assoc-in db [:auth :user :username] username)}))
  (rf/reg-event :t/logout (fn [{:keys [db]} _] {:db (update db :auth dissoc :user)})))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter :init-fn init!}
       :cljs {:adapter rf.adapter.reagent/adapter :init-fn init!}))
  capturing-transport-fixture)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))
(defn- invalidated? [scoped-key] (some? (:invalidated-at (entry scoped-key))))

(defn- reply-success! [args result]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value result})))

(def ^:private global-key (rf.resources.state/scoped-resource-key :rf.scope/global :r/article {:slug "w"}))
(defn- session-feed-key [u] (rf.resources.state/scoped-resource-key [:rf.scope/session {:username u}] :r/feed {}))

(def ^:private article-owned
  {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :a]})

(defn- reg-article-resource! []
  (rf/reg-resource :r/article
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug] [:article-list]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})))

(defn- reg-feed-resource! []
  (rf/reg-resource :r/feed
    {:scope {:from-db :t/session}
     :params-schema [:map]
     :tags (fn [_p _] #{[:feed] [:article-list]})}
    (fn [_p _] {:request {:method :get :url "/feed"}})))

(defn- own-loaded!
  "Load an entry with an active owner, so an invalidation refetches it."
  [payload]
  (rf/dispatch-sync [:rf.resource/ensure payload])
  (reply-success! @last-managed-args {:seed true})
  (reset! last-managed-args nil))

(defn- ownerless-stale-load!
  "Load an entry, then release its owner, so an invalidation leaves it stale
  (observable as :invalidated-at) rather than refetching it."
  [payload]
  (rf/dispatch-sync [:rf.resource/ensure payload])
  (reply-success! @last-managed-args {:seed true})
  (rf/dispatch-sync [:rf.resource/release-owner (select-keys payload [:resource :params :scope :owner])])
  (reset! last-managed-args nil))

(defn- reg-save!
  "Register :m/save on the global scope with the given :invalidates fn."
  [invalidates]
  (rf/reg-mutation :m/save
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :invalidates invalidates}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}})))

(defn- global-and-session-descriptors [{:keys [slug]} _result]
  [{:scope :rf.scope/global :tags #{[:article slug]}}
   {:scope {:from-db :t/session} :tags #{[:feed]}}])

(defn- execute! [mutation instance-id]
  (rf/dispatch-sync [:rf.mutation/execute {:mutation mutation :params {:slug "w"} :instance instance-id}]))

(defn- execute-and-reply! [mutation instance-id value]
  (execute! mutation instance-id)
  (reply-success! @last-managed-args value))

(defn- traces-of [op body-fn]
  (let [seen (atom [])
        k    ::recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (= op (:operation ev)) (swap! seen conj (:tags ev)))))
    (try (body-fn) (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- record-invalidations!
  "Run `body-fn`; return the tags of every :rf.resource/invalidated trace (one
  per dispatched descriptor)."
  [body-fn]
  (traces-of :rf.resource/invalidated body-fn))

(defn- settled-invalidations
  "Run `body-fn`; return the :invalidation facet of each :rf.mutation/succeeded trace."
  [body-fn]
  (mapv :invalidation (traces-of :rf.mutation/succeeded body-fn)))

(deftest bare-tag-set-invalidates-resolved-scope
  (reg-article-resource!)
  (rf/reg-mutation :m/save
    {:params-schema [:map [:slug :string]]
     :invalidates (fn [{:keys [slug]} _result] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  (ownerless-stale-load! article-owned)
  (execute-and-reply! :m/save :b1 {:title "new"})
  (is (invalidated? global-key)))

(deftest descriptor-invalidates-global-and-session-exactly
  ;; One execution reaches a global and a session-scoped target, and only the
  ;; resolved session: another user's feed is untouched.
  (reg-article-resource!)
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (ownerless-stale-load! {:resource :r/feed :scope [:rf.scope/session {:username "abel"}]
                          :params {} :owner [:v :feed-abel]})
  (ownerless-stale-load! {:resource :r/feed :scope {:from-db :t/session} :params {} :owner [:v :feed-jake]})
  (ownerless-stale-load! article-owned)
  (reg-save! global-and-session-descriptors)
  (execute-and-reply! :m/save :f1 {:favorited true})
  (is (= [true true false]
         (map invalidated? [global-key (session-feed-key "jake") (session-feed-key "abel")]))))

(deftest descriptor-refetches-active-owner-marks-stale-ownerless
  (reg-article-resource!)
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (own-loaded! article-owned)
  (ownerless-stale-load! {:resource :r/feed :scope {:from-db :t/session} :params {} :owner [:v :feed]})
  (reg-save! global-and-session-descriptors)
  (execute-and-reply! :m/save :v1 {:title "new"})
  (is (= [true {:method :get :url "/a/w"}]
         [(contains? #{:loading :fetching} (:status (entry global-key))) (:request @last-managed-args)])
      "the owned article refetched")
  (let [e (entry (session-feed-key "jake"))]
    (is (= [true false] [(some? (:invalidated-at e)) (contains? #{:loading :fetching} (:status e))])
        "the ownerless feed was left stale, not refetched")))

(deftest stale-settle-does-not-invalidate
  (reg-article-resource!)
  (reg-save! (fn [{:keys [slug]} _result] #{[:article slug]}))
  (ownerless-stale-load! article-owned)
  (execute! :m/save :s1)
  (let [stale-args @last-managed-args]
    (reset! last-managed-args nil)
    ;; a re-execute under the same instance supersedes the first reply
    (execute! :m/save :s1)
    (is (= [[] false]
           [(record-invalidations! #(reply-success! stale-args {:title "stale"})) (invalidated? global-key)])
        "the superseded reply fired no invalidation")))

(deftest from-db-descriptor-resolved-at-settle-time
  ;; Executed while zed is logged in; yan logs in before the reply settles.
  (reg-article-resource!)
  (reg-feed-resource!)
  (reg-save! (fn [_p _r] [{:scope {:from-db :t/session} :tags #{[:feed]}}]))
  (doseq [u ["zed" "yan"]]
    (ownerless-stale-load! {:resource :r/feed :scope [:rf.scope/session {:username u}]
                            :params {} :owner [:v :feed u]}))
  (rf/dispatch-sync [:t/login "zed"])
  (execute! :m/save :z1)
  (let [reply-args @last-managed-args]
    (rf/dispatch-sync [:t/login "yan"])
    (reply-success! reply-args {:title "new"}))
  (is (= [true false] (map invalidated? [(session-feed-key "yan") (session-feed-key "zed")]))
      "the settle-time session's feed is invalidated, the execute-time one untouched"))

(deftest from-db-descriptor-nil-fails-closed
  ;; Not logged in. The global article carries [:article-list], so a fallback
  ;; to a global blast would stale it.
  (reg-article-resource!)
  (reg-feed-resource!)
  (ownerless-stale-load! article-owned)
  (reg-save! (fn [_p _r] [{:scope {:from-db :t/session} :tags #{[:article-list]}}]))
  (let [invs (settled-invalidations #(execute-and-reply! :m/save :n1 {:title "new"}))]
    (is (= [[[:t/session] true]] (mapv (juxt :unresolved (comp empty? :dispatched)) invs))
        "the unresolved resolver is recorded and nothing dispatched")
    (is (not (invalidated? global-key)))))

(deftest descriptor-trace-evidence-records-resolved-scopes
  (reg-article-resource!)
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (reg-save! global-and-session-descriptors)
  (let [[inv] (settled-invalidations #(execute-and-reply! :m/save :t1 {:favorited true}))]
    (is (= [2 2 #{:rf.scope/global [:rf.scope/session {:username "jake"}]} true]
           [(:descriptor-count inv) (count (:dispatched inv))
            (set (map :scope (:dispatched inv))) (empty? (:unresolved inv))]))))

(defn- record-surfaced-errors!
  "Run `body-fn`; return the always-on error-emit records surfaced during it."
  [body-fn]
  (let [seen (atom [])
        k    ::err-recorder]
    (rf.error-emit/register-error-listener! k (fn [rec] (swap! seen conj rec)))
    (try (body-fn) (finally (rf.error-emit/unregister-error-listener! k)))
    @seen))

(defn- error-id-of
  "The :rf.error/id on a record's :exception ex-data, else its :error."
  [rec]
  (or (some-> rec :exception ex-data :rf.error/id)
      (:error rec)))

(deftest descriptor-typo-concrete-scope-fails-closed-at-settle
  ;; A typo'd reserved scope in a descriptor resolves through
  ;; canonicalize-scope at settle and fails closed loudly, never as a silent
  ;; wrong-scope invalidation.
  (reg-article-resource!)
  (reg-save! (fn [{:keys [slug]} _result] [{:scope :rf.scope/glabal :tags #{[:article slug]}}]))
  (ownerless-stale-load! article-owned)
  (execute! :m/save :ty1)
  (let [errs (record-surfaced-errors! #(reply-success! @last-managed-args {:title "new"}))]
    (is (some #(= :rf.error/resource-invalid-scope (error-id-of %)) errs))
    (is (not (invalidated? global-key)))))

(deftest descriptor-cross-scope-fans-out-and-supplies-mutation-cause-at-settle
  ;; A :cross-scope? descriptor with no :cause reaches the tag in every scope;
  ;; the mutation supplies the cause the cross-scope gate requires.
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (rf/reg-resource :rx/article
    {:scope {:from-db :t/caller-scope}
     :params-schema [:map [:slug :string]]
     :tags (fn [{:keys [slug]} _] #{[:article slug]})}
    (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}}))
  (let [sa {:user "amy"}
        sb {:user "bo"}
        ka (rf.resources.state/scoped-resource-key sa :rx/article {:slug "w"})
        kb (rf.resources.state/scoped-resource-key sb :rx/article {:slug "w"})]
    (ownerless-stale-load! {:resource :rx/article :scope sa :params {:slug "w"} :owner [:v :a]})
    (ownerless-stale-load! {:resource :rx/article :scope sb :params {:slug "w"} :owner [:v :b]})
    (reg-save! (fn [{:keys [slug]} _result] [{:tags #{[:article slug]} :cross-scope? true}]))
    (let [[inv]   (settled-invalidations #(execute-and-reply! :m/save :x1 {:purged true}))
          [d :as dispatched] (:dispatched inv)]
      (is (= [true true] (map invalidated? [ka kb])) "the fan-out reached both scopes")
      (is (= [1 1 true nil #{[:article "w"]} true]
             [(:descriptor-count inv) (count dispatched) (:cross-scope? d) (:scope d)
              (set (:tags d)) (empty? (:unresolved inv))])
          "the trace records one scope-agnostic cross-scope dispatch"))))

(defn- lower [raw] (rf.resources.mutation-runtime/normalize-invalidation-descriptors raw 'test))

(defn- desc [scope tags & {:as flags}]
  (merge {:scope scope :tags tags :cross-scope? false :refetch-populated? false} flags))

(deftest normalize-lowers-the-public-forms
  (is (= [(desc :rf.scope/same #{[:article "w"] [:article-list]})] (lower #{[:article "w"] [:article-list]}))
      "a bare tag-set is one :rf.scope/same descriptor")
  (is (= [(desc :rf.scope/global #{[:x]})] (lower {:scope :rf.scope/global :tags #{[:x]}}))
      "a single descriptor map")
  (is (= [(desc :rf.scope/global #{[:a]}) (desc :rf.scope/same #{[:b]} :cross-scope? true)]
         (lower [{:scope :rf.scope/global :tags #{[:a]}} {:tags #{[:b]} :cross-scope? true}]))
      "a vector lowers each; an omitted :scope defaults to :rf.scope/same")
  (is (= [[] []] (map lower [nil #{}])) "an empty result invalidates nothing"))

(deftest normalize-fails-closed-on-malformed
  (doseq [bad [:nonsense {:scope :rf.scope/global}]]
    (is (thrown-with-msg?
          #?(:clj Throwable :cljs js/Error) #"mutation-invalid-invalidation"
          (lower bad))
        (pr-str bad))))

(deftest lone-vector-tag-normalizes-to-one-tag
  ;; A naive (set raw) would split [:article "w"] into #{:article "w"}, which
  ;; silently matches nothing.
  (is (= [#{[:article "w"]} #{[:article-list]} #{[:article "w"]} #{[:article "w"]}]
         (map rf.resources.state/normalize-tag-set
              [[:article "w"] [:article-list] #{[:article "w"]} [[:article "w"]]]))))

(deftest lone-vector-tag-matches-the-right-resource-end-to-end
  (reg-article-resource!)
  (reg-save! (fn [{:keys [slug]} _result] [:article slug]))
  (ownerless-stale-load! article-owned)
  (execute-and-reply! :m/save :lv1 {:title "new"})
  (is (invalidated? global-key) "the bare lone vector tag is one tag"))

(deftest lone-vector-tag-direct-invalidate-tags-event-matches
  (reg-article-resource!)
  (ownerless-stale-load! article-owned)
  (rf/dispatch-sync [:rf.resource/invalidate-tags
                     {:scope :rf.scope/global :tags [:article "w"]
                      :cause [:manual :t/inv]}])
  (is (invalidated? global-key) "the event's lone vector :tags is one tag"))

(deftest lone-vector-tag-descriptor-map-matches-end-to-end
  (reg-article-resource!)
  (reg-save! (fn [{:keys [slug]} _result] {:tags [:article slug]}))
  (ownerless-stale-load! article-owned)
  (execute-and-reply! :m/save :lv2 {:title "new"})
  (is (invalidated? global-key) "a descriptor map's lone vector :tags is one tag"))

(deftest invalidated-trace-pins-ep0016-diagnostic-fields
  ;; One pass touching an owned entry (refetched), a populated entry (exempt)
  ;; and a same tag living in another scope, so each field is non-trivial.
  (reg-article-resource!)
  (rf/reg-resource :r/article-list
    {:scope :rf.scope/global
     :params-schema [:map]
     :tags (fn [_p _] #{[:article-list]})}
    (fn [_p _] {:request {:method :get :url "/articles"}}))
  (reg-feed-resource!)
  (rf/dispatch-sync [:t/login "jake"])
  (own-loaded! {:resource :r/article-list :scope :rf.scope/global :params {} :owner [:v :list]})
  (ownerless-stale-load! {:resource :r/article :scope :rf.scope/global :params {:slug "w"} :owner [:v :w]})
  ;; jake's feed also carries [:article-list], in another scope
  (ownerless-stale-load! {:resource :r/feed :scope {:from-db :t/session} :params {} :owner [:v :feed]})
  (rf/reg-mutation :m/favorite
    {:scope :rf.scope/global
     :params-schema [:map [:slug :string]]
     :populates (fn [{:keys [slug]} result]
                  {{:resource :r/article :params {:slug slug} :scope :rf.scope/global} result})
     :invalidates (fn [{:keys [slug]} _r]
                    [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}])}
    (fn [{:keys [slug]} _] {:request {:method :post :url (str "/a/" slug "/fav")}}))
  (let [list-key (rf.resources.state/scoped-resource-key :rf.scope/global :r/article-list {})
        invs     (record-invalidations!
                   #(execute-and-reply! :m/favorite :iv1 {:slug "w" :favorited true}))]
    (is (= [{:matched [list-key] :refetched 1 :left-stale 0 :exempt [global-key]
             :any-tag-match-other-scope? true}]
           (mapv #(select-keys % [:matched :refetched :left-stale :exempt :any-tag-match-other-scope?])
                 invs))
        "one pass: the owned list refetched, the populated detail exempt, the tag found in another scope")))

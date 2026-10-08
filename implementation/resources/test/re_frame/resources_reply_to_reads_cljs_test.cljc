(ns re-frame.resources-reply-to-reads-cljs-test
  "Read completion continuations: a call-site `:reply-to` on
  `:rf.resource/ensure` / `:rf.resource/refetch` fires once per accepted
  terminal reply (or at once on a fresh cache hit), fans out to joined
  targets, is handed over to a superseding attempt, and is never fired by a
  stale reply (Spec 016 §Read completion continuations)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private last-managed-args (atom nil))
(def ^:private replied (atom []))

(defn- capturing-fixture [f]
  (reset! last-managed-args nil)
  (reset! replied [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
  (rf/reg-event :test/read-replied (fn [_ event] (swap! replied conj event) {}))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-fixture)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- reply-success!
  ([args data] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))
  ([args data opts] (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data}) opts)))

(defn- reply-failure!
  [args failure] (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure})))

(def ^:private article-spec
  {:scope         :rf.scope/global
   :params-schema [:map [:slug :string]]
   :tags          (fn [{:keys [slug]} _data] #{[:article slug]})})

(def ^:private article-request
  (fn [{:keys [slug]} _ctx] {:request {:method :get :url (str "/api/articles/" slug)}}))

(def ^:private rkey (rf.resources.state/scoped-resource-key :rf.scope/global :rr/article {:slug "w"}))

(defn- ensure-article!
  ([owner] (ensure-article! owner nil))
  ([owner reply-to]
   (rf/dispatch-sync [:rf.resource/ensure
                      (cond-> {:resource :rr/article :scope :rf.scope/global
                               :params {:slug "w"} :owner owner}
                        reply-to (assoc :reply-to reply-to))])))

(defn- replies [] (mapv peek @replied))

(deftest reply-to-fires-on-accepted-fetch-and-carries-read-facts
  (rf/reg-resource :rr/article article-spec article-request)
  (ensure-article! [:view :a] [:test/read-replied])
  (is (= [] @replied) "nothing fires before the read settles")
  (reply-success! @last-managed-args {:title "Welcome"}
                  {:rf.cofx {:rf/time-ms 1781078400777}})
  (is (= 1 (count @replied)))
  (let [[ev-id reply] (first @replied)]
    (is (= :test/read-replied ev-id))
    (is (= {:status :ok :value {:title "Welcome"} :resource :rr/article :params {:slug "w"}
            :scope :rf.scope/global :resource/key rkey :cache-hit? false
            :rf.reply/work-kind :resource :rf.frame/id :rf/default :completed-at 1781078400777}
           (select-keys reply [:status :value :resource :params :scope :resource/key :cache-hit?
                               :rf.reply/work-kind :rf.frame/id :completed-at])))
    (is (some? (:rf.reply/work-id reply)))))

(deftest reply-to-cache-hit-dispatches-immediately
  (rf/reg-resource :rr/article article-spec article-request)
  (ensure-article! [:view :a])
  (reply-success! @last-managed-args {:title "Cached"})
  (reset! last-managed-args nil)
  (ensure-article! [:view :b] [:test/read-replied])
  (is (nil? @last-managed-args) "no request was lowered")
  (is (= [{:status :ok :cache-hit? true :value {:title "Cached"} :resource/key rkey}]
         (mapv #(select-keys % [:status :cache-hit? :value :resource/key]) (replies))))
  (is (some? (:rf.reply/work-id (first (replies))))))

(deftest reply-to-join-in-flight-fans-out-exactly-once
  (rf/reg-resource :rr/article article-spec article-request)
  (ensure-article! [:view :a] [:test/read-replied :a])
  (let [args @last-managed-args]
    (ensure-article! [:view :b] [:test/read-replied :b])
    (is (= args @last-managed-args) "the second ensure joined the in-flight request")
    (reply-success! args {:title "Shared"})
    (is (= #{[:a :ok {:title "Shared"}] [:b :ok {:title "Shared"}]}
           (set (map (fn [[_ tag reply]] [tag (:status reply) (:value reply)]) @replied))))
    (is (= 2 (count @replied)))))

(deftest reply-to-superseded-attempt-hands-continuation-to-successor
  ;; The stale reply delivers nothing; the continuation arrives from the
  ;; fresher attempt, which carries no :reply-to of its own.
  (rf/reg-resource :rr/article article-spec article-request)
  (ensure-article! [:view :a] [:test/read-replied])
  (let [gen1-args @last-managed-args]
    (rf/dispatch-sync [:rf.resource/refetch
                       {:resource :rr/article :scope :rf.scope/global :params {:slug "w"}}])
    (is (= 2 (:generation (entry rkey))))
    (reply-success! gen1-args {:title "stale"})
    (is (= [] @replied))
    (reply-success! @last-managed-args {:title "fresh"})
    (is (= [{:status :ok :value {:title "fresh"}}]
           (mapv #(select-keys % [:status :value]) (replies))))))

(deftest reply-to-superseded-hand-over-dedupes-a-repeated-target
  ;; A refetch repeating the superseded read's target still fires once.
  (rf/reg-resource :rr/article article-spec article-request)
  (ensure-article! [:view :a] [:test/read-replied])
  (rf/dispatch-sync [:rf.resource/refetch
                     {:resource :rr/article :scope :rf.scope/global
                      :params {:slug "w"}
                      :reply-to [:test/read-replied]}])
  (reply-success! @last-managed-args {:title "fresh"})
  (is (= 1 (count @replied))))

(deftest reply-to-fires-on-accepted-failure
  (rf/reg-resource :rr/article article-spec article-request)
  (ensure-article! [:view :a] [:test/read-replied])
  (reply-failure! @last-managed-args {:kind :rf.http/http-5xx :status 503})
  (is (= [{:status :error :error {:kind :rf.http/http-5xx :status 503} :cache-hit? false}]
         (mapv #(select-keys % [:status :error :cache-hit?]) (replies)))))

(deftest replied-trace-emitted-for-accepted-reply
  (rf/reg-resource :rr/article article-spec article-request)
  (let [seen (atom [])]
    (rf.trace.tooling/register-listener!
      ::replied (fn [ev] (when (= :rf.resource/replied (:operation ev)) (swap! seen conj ev))))
    (try
      (ensure-article! [:view :a] [:test/read-replied])
      (reply-success! @last-managed-args {:title "Welcome"})
      (finally (rf.trace.tooling/unregister-listener! ::replied)))
    (is (= 1 (count @seen)))
    (let [tags (:tags (first @seen))]
      (is (= {:status :ok :cache-hit? false} (select-keys tags [:status :cache-hit?])))
      (is (seq (:targets tags))))))

;; ---- infinite feed: the :value is the merged items list on every path -------

(defn- feed-page [items next-c]
  {:items items :page-info {:next next-c}})

(def ^:private feed-spec
  {:scope           :rf.scope/global
   :infinite        true
   :params-schema   [:map [:filter :keyword]]
   :next-page-param (fn [last-page _all-pages] (get-in last-page [:page-info :next]))
   :page->items     :items
   :tags            (fn [{:keys [filter]} _data] #{[:feed filter]})})

(def ^:private feed-request
  (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
    {:request {:method :get :url "/api/feed"
               :params (cond-> {:filter filter :page-index page-index}
                         page-param (assoc :cursor page-param))}}))

(defn- ensure-feed! [owner reply-to]
  (rf/dispatch-sync [:rf.resource/ensure
                     (cond-> {:resource :cm/feed :scope :rf.scope/global
                              :params {:filter :recent} :owner owner}
                       reply-to (assoc :reply-to reply-to))]))

(deftest infinite-reply-to-value-spans-all-pages-both-paths
  ;; The page fetch path, the fresh-skip cache hit and a window-preserving
  ;; refetch all deliver the merged items list, never a raw page or page vector.
  (rf/reg-resource :cm/feed feed-spec feed-request)
  (let [page-0 (feed-page [{:id 1}] "c1")
        page-1 (feed-page [{:id 2} {:id 3}] nil)
        all-items [{:id 1} {:id 2} {:id 3}]]
    (ensure-feed! [:view :a] [:test/read-replied])
    (reply-success! @last-managed-args page-0)
    (is (= [{:value [{:id 1}] :cache-hit? false}]
           (mapv #(select-keys % [:value :cache-hit?]) (replies))))
    (rf/dispatch-sync [:rf.resource/load-more
                       {:resource :cm/feed :scope :rf.scope/global
                        :params {:filter :recent} :cause [:user :feed/load-more]}])
    (reply-success! @last-managed-args page-1)
    (reset! last-managed-args nil)
    (reset! replied [])
    (ensure-feed! [:view :b] [:test/read-replied])
    (is (nil? @last-managed-args) "served from cache")
    (is (= [{:value all-items :cache-hit? true}]
           (mapv #(select-keys % [:value :cache-hit?]) (replies))))
    (reset! replied [])
    (rf/dispatch-sync [:rf.resource/refetch
                       {:resource :cm/feed :scope :rf.scope/global
                        :params {:filter :recent} :cause [:test :refresh]
                        :reply-to [:test/read-replied]}])
    (is (= 0 (:rf.resource/page-index (second (:on-success @last-managed-args)))))
    (reply-success! @last-managed-args page-0)
    (is (= [{:value all-items :cache-hit? false}]
           (mapv #(select-keys % [:value :cache-hit?]) (replies))))))

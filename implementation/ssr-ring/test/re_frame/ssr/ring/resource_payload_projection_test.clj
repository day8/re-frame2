(ns re-frame.ssr.ring.resource-payload-projection-test
  "The render path (`build-full-response*`) projects the
  resource-runtime slice inside the request frame, and the resource OWNER's
  coarse `:sensitive?` claim alone decides its row (EP-0025): a frame-sensitive
  `:db` input feeding a `{:from-db}` scope does not propagate to the resource."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.ssr.ring.pipeline :as rf.ssr.ring.pipeline]
            [re-frame.ssr.ring.shell :as rf.ssr.ring.shell]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]
            ;; Side-effecting: the resource registrar kinds and the
            ;; `:ssr/extend-runtime-db-projection` hook under test.
            [re-frame.resources]
            [re-frame.resources.ssr]
            [re-frame.schemas]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private scoped-key
  (rf.resources.state/scoped-resource-key [:rf.scope/session {:username "jake"}]
                                          :p026f5/feed {:page 1}))

(def ^:private loaded-entry
  (merge (rf.resources.state/empty-entry :p026f5/feed scoped-key)
         {:status :loaded :data {:articles [:a :b]} :loaded-at 1000 :stale-at 9.0e15}))

(defn- render-feed-payload
  "Render a request frame whose app-db `[:auth :user :username]` is classified
  sensitive and whose resource cache holds one loaded `jake` feed entry; return
  the parsed `__rf_payload`."
  [owner-sensitive?]
  (rf/reg-resource-scope :p026f5/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-resource :p026f5/feed
    (cond-> {:scope {:from-db :p026f5/session} :params-schema [:map [:page :int]]}
      owner-sensitive? (assoc :sensitive? true))
    (fn [{:keys [page]} _ctx] {:request {:method :get :url "/feed" :params {:page page}}}))
  (rf/reg-event :p026f5/classify (fn [_ _] {:sensitive [[:auth :user :username]]}))
  (rf/make-frame {:id :p026f5/req-frame :platform :server :initial-events [[:p026f5/classify]]})
  ;; swap, not replace: the elision registry the classify event wrote must survive.
  (rf.frame/swap-runtime-db! :p026f5/req-frame
    assoc rf.resources.state/resources-key
    {:entries     {(rf.resources.state/key-id scoped-key) loaded-entry}
     :tag-index   {}
     :owner-index {}})
  (some->> (#'rf.ssr.ring.pipeline/build-full-response*
            :p026f5/req-frame
            {:root-view  [:main [:h1 "Feed"]]
             :html-shell rf.ssr.ring.shell/default-html-shell
             :payload    :rf.ssr.payload/whole-app-db})
           :body
           (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>")
           second
           edn/read-string))

(deftest payload-no-inheritance-serializes-resource
  (is (= [{:resource/key scoped-key :status :loaded :data {:articles [:a :b]}}]
         (->> (get-in (render-feed-payload false) [:rf/runtime-db :rf.runtime/resources :entries])
              vals
              (map #(select-keys % [:resource/key :status :data]))))))

(deftest payload-withholds-owner-declared-sensitive-resource
  ;; The coarse claim substitutes both key components, so no client could
  ;; address the row: it is withheld, not shipped metadata-only. Neither the
  ;; identity, the data, nor a redaction token standing for them may ride.
  (let [payload (render-feed-payload true)]
    (is (= {} (get-in payload [:rf/runtime-db :rf.runtime/resources :entries])))
    (is (not (re-find #"jake|:articles|rf/redacted" (pr-str payload))))))

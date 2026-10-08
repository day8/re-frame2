(ns day8.re-frame2-xray.panels.resources-helpers-cljs-test
  "Pure-data tests for Xray's Resources tab helpers (Spec 016 §Xray and AI
  tooling). Dual-target `.cljc`: the JVM test-runner and the `:node-test`
  build both run it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.panels.resources-helpers :as h]
            ;; The live `:entries` / `:rf.runtime/work-ledger` maps are keyed
            ;; on the CEDN-1 byte id STRING, with the kind-preserving vector on
            ;; the record; the fixtures build that shape with the real id fns.
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.work-ledger :as rf.resources.work-ledger]))

;; ---- fixtures -----------------------------------------------------------

(def ^:private session-scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}])

(def ^:private registrations
  "A `(rf/registrations {:source :store :kind :resource})` shape."
  {:article/by-slug
   {:doc  "Article detail by slug."
    :file "app/articles.cljs" :line 12
    :rf/resource {:doc "Article detail by slug."
                  :params-schema [:map [:slug :string]]
                  :data-schema   :app/article
                  :scope         :rf.scope/global
                  :transport     :rf.http/managed
                  :stale-after-ms 60000
                  :gc-after-ms    300000
                  :tags          (fn [_ _] #{})
                  :request       (fn [_ _] {})}}
   :me/profile
   {:doc "The current user's profile."
    :rf/resource {:doc "The current user's profile."
                  :params-schema [:map]
                  :scope         :rf.scope/global  ; suspicious — /me-ish
                  :request       (fn [_ _] {})}}
   :dashboard/summary
   {:rf/resource {:params-schema [:map [:user-id :string]]
                  :scope         {:from-db :app/session}
                  :request       (fn [_ _] {})}}})

(def ^:private routes-map
  {:route/article
   {:path "/articles/:slug"
    :resources [{:resource :article/by-slug :blocking? true
                 :params (fn [_] {}) :scope {:from-db :app/session}}
                {:resource :comments/list :blocking? false :keep-previous? true}]}
   :route/home {:path "/"}})

;; ---- trace family -------------------------------------------------------

(deftest trace-family
  (testing "an enumerated op takes its class and label from the table"
    (is (= [true :failure "failed"]
           ((juxt h/resource-trace-op? h/op-class h/op-label) :rf.resource/failed))))
  (testing "an in-namespace op outside the enum is a member, with the fallbacks"
    (is (= [true :lifecycle "some-future-op"]
           ((juxt h/resource-trace-op? h/op-class h/op-label) :rf.resource/some-future-op))))
  (testing "a non-family op rejects"
    (is (= [false nil] ((juxt h/resource-trace-op? h/op-class) :rf.event/dispatched)))))

;; ---- summarize (PRIVACY) -------------------------------------------------

(deftest summarize-privacy
  (testing "a value summarizes to its shape and a bounded preview"
    (is (= {:type "map" :size 1 :preview "{:slug \"welcome\"}"
            :elided? false :redacted? false :large? false}
           (h/summarize {:slug "welcome"}))))
  (testing "the framework sentinels keep their status and render no raw preview"
    (is (= {:type "keyword" :size nil :preview "[redacted]"
            :elided? false :redacted? true :large? false}
           (h/summarize :rf/redacted)))
    (is (= {:type "keyword" :size nil :preview "[large — elided]"
            :elided? false :redacted? false :large? true}
           (h/summarize :rf.size/large-elided))))
  (testing "a preview past the budget is cut and marked :elided?"
    (is (= {:type "string" :size 500 :preview (str "\"" (apply str (repeat 19 "x")) "…")
            :elided? true :redacted? false :large? false}
           (h/summarize (apply str (repeat 500 "x")) {:budget 20})))))

;; ---- project-registry ----------------------------------------------------

(deftest project-registry-test
  (let [rows  (h/project-registry registrations routes-map)
        by-id (into {} (map (juxt :resource-id identity)) rows)]
    (testing "one row per registered resource, sorted by id"
      (is (= [:article/by-slug :dashboard/summary :me/profile] (mapv :resource-id rows))))
    (testing "a {:from-db …} scope policy is described as its named resolver"
      (is (= {:policy :resolver :label "named resolver :app/session" :global? false}
             (get-in by-id [:dashboard/summary :scope]))))
    (testing "declaring routes are joined from the route registry"
      (is (= [:route/article] (get-in by-id [:article/by-slug :declaring-routes]))))))

;; ---- project-scope-resolvers ---------------------------------------------

(def ^:private scope-resolver-registrations
  "One declared-inputs resolver and one whole-db-sugar resolver."
  {:realworld/session
   {:doc  "Viewer session scope."
    :file "app/scopes.cljs" :line 7
    :rf/resource-scope {:doc       "Viewer session scope."
                        :inputs    {:username [:db [:auth :user :username]]}
                        :whole-db? false
                        :resolve   (fn [_ _] [:rf.scope/session {:username "jake"}])}}
   :realworld/tenant
   {:rf/resource-scope {:inputs    {:db [:db []]}
                        :whole-db? true
                        :resolve   (fn [_ _] nil)}}})

(deftest project-scope-resolvers-test
  (let [rows (h/project-scope-resolvers scope-resolver-registrations)]
    (testing "sorted rows: declared input names and sources, the whole-db flag, coords"
      (is (= [[:realworld/session [[:username :db]] false {:file "app/scopes.cljs" :line 7}]
              [:realworld/tenant [[:db :db]] true nil]]
             (mapv (juxt :scope-id #(mapv (juxt :name :source) (:inputs %)) :whole-db? :source-coord)
                   rows))))
    (testing "an input's rf-path is summarized, never the value at it"
      (is (= "[:auth :user :username]" (get-in rows [0 :inputs 0 :path :preview]))))))

;; ---- project-instances -----------------------------------------------------

(def ^:private now 1000000)

(defn- byte-keyed
  "The LIVE-shape `:entries` map from an author-friendly
  `{<scoped-key-vector> <entry>}` map."
  [vector-keyed]
  (into {}
        (map (fn [[scoped-key entry]]
               [(rf.resources.state/key-id scoped-key) (assoc entry :resource/key scoped-key)]))
        vector-keyed))

(def ^:private entries
  (byte-keyed
    {[session-scope :article/by-slug {:slug "welcome"}]
     {:resource/id :article/by-slug :status :loaded
      :data {:title "Welcome"} :generation 4 :attempt 2
      :loaded-at (- now 1000) :stale-at (+ now 50000)
      :active-owners #{[:route :route/article "nav-1"]}
      :tags #{[:article "welcome"]} :request-id [:w 4]}
     [session-scope :article/by-slug {:slug "old"}]
     {:resource/id :article/by-slug :status :loaded
      :data {:title "Old"} :generation 2
      :loaded-at (- now 99999) :stale-at (- now 1)   ; past stale-at → stale
      :active-owners #{}                              ; no owner → gc-eligible
      :tags #{[:article "old"]}}}))

(deftest project-instances-test
  (testing "rows sort by resource-id, then generation descending"
    (is (= [4 2] (mapv :generation (h/project-instances entries now))))))

;; `derive-stale?` is a deliberate second copy of
;; `rf.resources.state/entry-stale?` (see its docstring). This pin holds the two
;; to the same answer across the reachable domain; the nil-clock pin below holds
;; the one deliberate difference.

(def ^:private stale-pin-entries
  "Entry shapes spanning both arms of the predicate and the boundaries of each."
  {:no-policy       {:status :loaded :data 1}
   :invalidated     {:status :loaded :invalidated-at 500}
   :stale-at-future {:status :loaded :stale-at (+ now 50000)}
   :stale-at-past   {:status :loaded :stale-at (- now 1)}
   :stale-at-equal  {:status :loaded :stale-at now}
   :stale-at-zero   {:status :loaded :stale-at 0}
   :both-arms       {:status :loaded :invalidated-at 1 :stale-at (+ now 50000)}
   :explicit-nils   {:status :loaded :invalidated-at nil :stale-at nil}})

(deftest stale-derivation-agrees-with-framework-pin
  (testing "the panel's derived :stale? equals rf.resources.state/entry-stale? on every reachable clock"
    (let [clocks        [0 1 (- now 1) now (+ now 50000)]
          disagreements (vec
                          (for [[label entry] stale-pin-entries
                                clock          clocks
                                :let  [projected (:stale? (h/instance-row [(str "k-" (name label)) entry] clock))
                                       canonical (rf.resources.state/entry-stale? entry clock)]
                                :when (not= projected canonical)]
                            {:entry label :clock clock :panel projected :framework canonical}))]
      (is (= [] disagreements)
          (str "derive-stale? has drifted from rf.resources.state/entry-stale? — "
               "the two staleness derivations disagree on: " (pr-str disagreements))))))

(deftest stale-derivation-nil-clock-guard-pin
  (testing "the panel nil-guards now-ms (project-instances' 1-arity passes nil) where the framework predicate throws"
    (let [time-policy (h/project-instances
                        (byte-keyed {[session-scope :article/by-slug {:slug "welcome"}]
                                     {:resource/id :article/by-slug :status :loaded
                                      :stale-at (- now 1)}}))
          invalidated (h/project-instances
                        (byte-keyed {[session-scope :article/by-slug {:slug "gone"}]
                                     {:resource/id :article/by-slug :status :loaded
                                      :invalidated-at 5}}))]
      (is (false? (:stale? (first time-policy)))
          "a time-policy entry with NO clock reads not-stale rather than throwing")
      (is (true? (:stale? (first invalidated)))
          "the invalidation arm answers even with no clock"))))

;; ---- project-work-ledger ---------------------------------------------------

(defn- byte-keyed-ledger
  "The LIVE-shape `:rf.runtime/work-ledger` map from a seq of records, each
  carrying its own `:work/id`."
  [records]
  (into {}
        (map (fn [record] [(rf.resources.work-ledger/work-id-id (:work/id record)) record]))
        records))

(def ^:private ledger
  (byte-keyed-ledger
    [{:work/id [:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4]
      :work/kind :resource :resource/key [session-scope :article/by-slug {:slug "welcome"}]
      :generation 4 :status :running}
     {:work/id [:rf.work/resource [session-scope :article/by-slug {:slug "old"}] 1]
      :work/kind :resource :resource/key [session-scope :article/by-slug {:slug "old"}]
      :generation 1 :status :completed}]))

(deftest project-work-ledger-test
  (let [rows (h/project-work-ledger ledger)]
    (testing "live work sorts before the terminal tail"
      (is (= [[:running false] [:completed true]] (mapv (juxt :status :terminal?) rows))))
    (testing "the displayed :work-id is the record's kind-preserving :work/id, not the byte map key"
      (is (= [:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4]
             (:work-id (first rows)))))))

;; ---- infinite feeds --------------------------------------------------------

(def ^:private infinite-entries
  (byte-keyed
    {;; a loaded feed with 2 accumulated pages and a live cursor
     [session-scope :feed/articles {:tag "clj"}]
     {:resource/id :feed/articles :status :loaded :infinite? true
      :data [[{:id 1}] [{:id 2}]]
      :page-params [nil "cursor-1"]
      :next-page-param "cursor-2"
      :generation 3 :loaded-at (- now 1000) :stale-at (+ now 50000)
      :active-owners #{[:feed/opened "feed"]} :tags #{[:feed "clj"]}}
     ;; a terminal feed (nil cursor) carrying a load-more :page-error
     [session-scope :feed/articles {:tag "done"}]
     {:resource/id :feed/articles :status :loaded :infinite? true
      :data [[{:id 9}]]
      :page-params [nil]
      :next-page-param nil
      :page-error {:kind :rf.http/server-error :status 500}
      :generation 2 :loaded-at (- now 500) :stale-at (+ now 50000)
      :active-owners #{[:feed/opened "feed2"]}}}))

(defn- feed-row [rows tag]
  (first (filter #(= {:tag tag} (get-in % [:scoped-key 2])) rows)))

(deftest infinite-instance-surface-test
  (let [rows       (h/project-instances infinite-entries now)
        live       (feed-row rows "clj")
        term       (feed-row rows "done")
        feed-facts (juxt :infinite? :page-count :terminal? :has-next-page?)]
    (testing "a feed with a live cursor: its pages and its ordered, summarized cursor chain"
      (is (= [true 2 false true] (feed-facts live)))
      (is (= ["nil" "\"cursor-1\""] (mapv :preview (:page-params live)))))
    (testing "a nil cursor is terminal; a load-more failure rides the third error channel"
      (is (= [true 1 true false] (feed-facts term)))
      (is (contains? (:page-error term) :preview)))
    (testing "a non-infinite entry carries none of the feed facts"
      (is (not (contains? (first (h/project-instances entries now)) :infinite?))))))

(deftest infinite-page-params-egress-redaction-test
  (let [;; redacts the :params slot, as the off-box egress does for a :sensitive? owner
        egress-fn (fn [v slot _key-id] (if (= :params slot) h/redacted-sentinel v))
        live      (feed-row (h/project-instances infinite-entries now egress-fn) "clj")]
    (testing "every cursor in the chain, and the live cursor, egress through the :params slot"
      (is (= [false true] (mapv :redacted? (:page-params live)))
          "the nil page-0 seed has nothing to redact")
      (is (:redacted? (:cursor live))))))

;; ---- route / resource graph ------------------------------------------------

(deftest project-route-graph-test
  (testing "the static projection: only routes declaring :resources, the blocking
            split, declared resolvers recorded uninvoked, and no live state"
    (let [none {:entry-count 0 :has-data? false :stale? false :statuses #{}
                :active-work 0 :freshness :none}]
      (is (= [{:route-id     :route/article
               :path         "/articles/:slug"
               :resources    [{:resource :article/by-slug :blocking? true :keep-previous? false
                               :local-id nil :after [] :when? false
                               :params-fn? true :scope-resolver? true :live none}
                              {:resource :comments/list :blocking? false :keep-previous? true
                               :local-id nil :after [] :when? false
                               :params-fn? false :scope-resolver? false :live none}]
               :blocking     [:article/by-slug]
               :non-blocking [:comments/list]
               :ssr-wait?    true}]
             (h/project-route-graph routes-map))))))

(def ^:private m5-nav-token "nav-7")
(def ^:private m5-article-key [session-scope :article/by-slug {:slug "welcome"}])

(def ^:private m5-routing-slice
  {:current {:route-id :route/article :nav-token m5-nav-token
             :params {:slug "welcome"} :path "/articles/welcome"}
   :resource-blocking {m5-nav-token {(rf.resources.state/key-id m5-article-key) m5-article-key}}})

(deftest project-route-graph-live-test
  (let [instance-rows (h/project-instances
                        {m5-article-key
                         {:resource/id :article/by-slug :status :loaded
                          :data {:title "Welcome"} :generation 4
                          :loaded-at (- now 1000) :stale-at (+ now 50000)
                          :active-owners #{[:route :route/article m5-nav-token]}}}
                        now)
        work-rows     (h/project-work-ledger
                        {[:rf.work/resource [session-scope :comments/list {}] 1]
                         {:work/id [:rf.work/resource [session-scope :comments/list {}] 1]
                          :work/kind :resource :resource/key [session-scope :comments/list {}]
                          :generation 1 :status :running}})
        current       (h/routing-current m5-routing-slice)
        article-node  (first (filter #(= :route/article (:route-id %))
                                     (h/project-route-graph
                                       routes-map
                                       {:instance-rows instance-rows
                                        :work-rows     work-rows
                                        :current       current
                                        :blocking-keys (h/routing-blocking-keys
                                                         m5-routing-slice (:nav-token current))})))
        live-of       (fn [rid] (:live (first (filter #(= rid (:resource %)) (:resources article-node)))))]
    (testing "the active route carries its nav-token and its unsettled blocking wait point"
      (is (= {:current? true :nav-token m5-nav-token :blocking-live [:article/by-slug]}
             (select-keys article-node [:current? :nav-token :blocking-live]))))
    (testing "each resource node rolls up its live cache and work state"
      (is (= {:entry-count 1 :has-data? true :stale? false :statuses #{:loaded}
              :active-work 0 :freshness :fresh}
             (live-of :article/by-slug)))
      (is (= {:entry-count 0 :has-data? false :stale? false :statuses #{}
              :active-work 1 :freshness :loading}
             (live-of :comments/list))))))

(deftest routing-slice-extractors-test
  (testing "nil-safe on a frame with no routing slice"
    (is (nil? (h/routing-current nil)))
    (is (= [] (h/routing-blocking-keys nil m5-nav-token))))
  (testing "the wait points come from the named nav-token's bucket only"
    (is (= [] (h/routing-blocking-keys m5-routing-slice "nav-stale"))
        "a superseded token reads its own (empty) bucket, never another's")
    (is (= [] (h/routing-blocking-keys m5-routing-slice nil))
        "no active navigation has no live wait point")))

;; ---- timeline / invalidation / cache-growth --------------------------------

(def ^:private trace-buffer
  [{:id 1 :operation :rf.event/dispatched :tags {}}
   {:id 2 :operation :rf.resource/fetch-started
    :tags {:resource/key [session-scope :article/by-slug {:slug "welcome"}]
           :generation 4 :status :loading
           :owner [:route :route/article "nav-1"]}}
   {:id 3 :operation :rf.resource/succeeded
    :tags {:resource/key [session-scope :article/by-slug {:slug "welcome"}]
           :generation 4 :status-after :loaded}}
   {:id 4 :operation :rf.resource/invalidated
    :tags {:scope session-scope :tags #{[:article "welcome"]}
           :cause [:mutation :article/save "m-1"]
           :matched [[session-scope :article/by-slug {:slug "welcome"}]]
           :refetched 1}}
   {:id 5 :operation :rf.resource/owner-released
    :tags {:owner [:dashboard/opened "u-42"]}}])

(deftest lifecycle-timeline-test
  (let [rows (h/lifecycle-timeline trace-buffer)]
    (testing "only resource-family rows, in buffer order"
      (is (= [:rf.resource/fetch-started :rf.resource/succeeded
              :rf.resource/invalidated :rf.resource/owner-released]
             (mapv :operation rows))))
    (testing "resource-id derived from the scoped key; class set; key summarized"
      (is (= [:article/by-slug :lifecycle "vector"]
             ((juxt :resource-id :class #(get-in % [:resource/key :scope :type])) (first rows)))))))

(def ^:private infinite-trace-buffer
  [{:id 20 :operation :rf.resource/load-more
    :tags {:resource/key [session-scope :feed/articles {:tag "clj"}]
           :generation 4 :work/id [:rf.work/resource :feed 4]
           :page-param "cursor-1" :page-index 1 :page-count 1}}
   {:id 21 :operation :rf.resource/page-appended
    :tags {:resource/key [session-scope :feed/articles {:tag "clj"}]
           :generation 4 :work/id [:rf.work/resource :feed 4]
           :page-index 1 :page-count 2
           :next-page-param "cursor-2" :terminal? false}}
   {:id 23 :operation :rf.resource/load-more-skipped
    :tags {:resource/key [session-scope :feed/articles {:tag "clj"}]
           :reason :no-next-page :page-count 3}}
   {:id 25 :operation :rf.resource/page-failed
    :tags {:resource/key [session-scope :feed/articles {:tag "clj"}]
           :generation 4 :status-before :loaded :status-after :loaded
           :page-error {:kind :rf.http/server-error :status 500}}}])

(defn- page-of [rows id]
  (:page (first (filter #(= id (:id %)) rows))))

(deftest infinite-lifecycle-timeline-page-detail-test
  (let [page (partial page-of (h/lifecycle-timeline infinite-trace-buffer))]
    (testing "each infinite op keeps its page evidence, cursors summarized"
      (is (= ["\"cursor-1\"" 1 1]
             ((juxt #(get-in % [:page-param :preview]) :page-index :page-count) (page 20))))
      (is (= ["\"cursor-2\"" 1 2 false]
             ((juxt #(get-in % [:next-page-param :preview]) :page-index :page-count :terminal?)
              (page 21))))
      (is (= [:no-next-page 3] ((juxt :reason :page-count) (page 23))))
      (is (contains? (:page-error (page 25)) :preview)))
    (testing "a non-infinite op carries no :page"
      (is (not (contains? (first (h/lifecycle-timeline trace-buffer)) :page))))))

(deftest infinite-lifecycle-timeline-page-param-egress-test
  (let [page (partial page-of (h/lifecycle-timeline infinite-trace-buffer
                                                    (fn [_v] h/redacted-sentinel)))]
    (testing "the cursors and the page error egress; the page metadata rides raw"
      (is (= [true true true]
             [(get-in (page 20) [:page-param :redacted?])
              (get-in (page 21) [:next-page-param :redacted?])
              (get-in (page 25) [:page-error :redacted?])]))
      (is (= 1 (:page-index (page 20)))))))

(deftest cache-growth-test
  (testing "per-resource entry / owned / gc-eligible counts, the totals, and live work"
    (is (= {:by-resource       [{:resource-id :article/by-slug :entry-count 2
                                 :owned-count 1 :gc-eligible 1}]
            :total-entries     2
            :total-gc-eligible 1
            :live-work         1}
           (h/cache-growth (h/project-instances entries now) (h/project-work-ledger ledger))))))

;; ---- EP-0016 projections ---------------------------------------------------

(def ^:private ep0016-trace-buffer
  [;; a named resolver resolved a {:from-db …} reference to a concrete scope
   {:id 10 :operation :rf.resource/scope-resolved
    :tags {:resource-id :realworld/session
           :kind :resource-scope
           :inputs [:username]
           :input-values {:username "jake"}
           :whole-db? false
           :scope [:rf.scope/session {:username "jake"}]
           :resolved-nil? false}}
   ;; a resolver that FAILED CLOSED — returned nil (no implicit global)
   {:id 11 :operation :rf.resource/scope-resolved
    :tags {:resource-id :realworld/session
           :inputs [:username]
           :input-values {:username nil}
           :whole-db? false
           :scope nil
           :resolved-nil? true}}
   ;; a MIXED plan: the default global descriptor spared the populated article
   ;; key, the session descriptor opted into :refetch-populated? and spared none
   {:id 12 :operation :rf.mutation/succeeded
    :tags {:mutation :realworld/favorite-article
           :instance [:favorite "welcome"]
           :invalidation
           {:descriptor-count 3
            :dispatched [{:scope :rf.scope/global :cross-scope? false
                          :tags #{[:article-list] [:article "welcome"]}
                          :refetch-populated? false
                          :exempt-keys [[:rf.scope/global :realworld/article {:slug "welcome"}]]}
                         {:scope [:rf.scope/session {:username "jake"}] :cross-scope? false
                          :tags #{[:feed]} :refetch-populated? true
                          :exempt-keys []}]
            :unresolved [:realworld/tenant]
            :populate-exempt [[:rf.scope/global :realworld/article {:slug "welcome"}]]}}}
   ;; a mutation settlement with NO :invalidation facet — skipped
   {:id 13 :operation :rf.mutation/succeeded
    :tags {:mutation :realworld/noop :instance [:noop 1]}}
   ;; the call-site :reply-to continuation dispatch
   {:id 14 :operation :rf.mutation/replied
    :tags {:rf.frame/id :app/main
           :mutation :realworld/save-article
           :instance [:editor/save "first-post"]
           :work/id [:rf.work/resource [:rf.mutation [:editor/save "first-post"]] 8]
           :status :ok
           :target [:editor/save-replied]
           :cause [:mutation :realworld/save-article [:editor/save "first-post"]]}}])

(deftest scope-resolutions-test
  (let [rows (h/scope-resolutions ep0016-trace-buffer)]
    (testing "one row per resolution: resolver id, declared input names, the fail-closed flag"
      (is (= [[10 :realworld/session [:username] false]
              [11 :realworld/session [:username] true]]
             (mapv (juxt :id :scope-id :inputs :resolved-nil?) rows))))
    (testing "the resolved scope and input values are summarized"
      (is (= ["vector" "map"]
             ((juxt #(get-in % [:scope :type]) #(get-in % [:input-values :type])) (first rows)))))))

(deftest mutation-invalidation-evidence-test
  (let [rows (h/mutation-invalidation-evidence ep0016-trace-buffer)
        r    (first rows)]
    (testing "only mutation settlements carrying an :invalidation facet"
      (is (= [12] (mapv :id rows))))
    (testing "each descriptor carries its OWN exempt keys — a mixed :refetch-populated? plan"
      (is (= [[false [:realworld/article]] [true []]]
             (mapv (juxt :refetch-populated? #(mapv :resource-id (:exempt-keys %)))
                   (:dispatched r)))))
    (testing "the fail-closed :unresolved ids and the populate-exempt union"
      (is (= [[:realworld/tenant] [:realworld/article]]
             [(:unresolved r) (mapv :resource-id (:populate-exempt r))])))))

(deftest mutation-continuations-test
  (let [rows (h/mutation-continuations ep0016-trace-buffer)]
    (testing "one row per :rf.mutation/replied continuation, its cause summarized"
      (is (= [{:id       14
               :mutation :realworld/save-article
               :instance [:editor/save "first-post"]
               :work-id  [:rf.work/resource [:rf.mutation [:editor/save "first-post"]] 8]
               :status   :ok
               :target   [:editor/save-replied]}]
             (mapv #(dissoc % :cause) rows)))
      (is (= "vector" (get-in rows [0 :cause :type]))))))

;; ---- EP-0019 optimistic mutation lifecycle ---------------------------------

(def ^:private opt-scope-a [:rf.scope/session {:user-id "u-1"}])
(def ^:private opt-key-a [opt-scope-a :realworld/article {:slug "welcome"}])
(def ^:private opt-key-b [:rf.scope/global :realworld/feed {}])

(def ^:private ep0019-trace-buffer
  [;; an apply that SUCCEEDED → reconciled
   {:id 20 :operation :rf.mutation/optimistic-applied
    :tags {:mutation :realworld/favorite-article :instance [:favorite "welcome"]
           :work/id [:rf.work/resource [:rf.mutation [:favorite "welcome"]] 5]
           :generation 5 :scope opt-scope-a :snapshot-id "snap-1"
           :affected-keys [opt-key-a]
           :revisions [{:resource/key opt-key-a :revision 3 :forward :patch}]
           :tag-matched-keys [] :target-unresolved []
           :cause [:mutation :realworld/favorite-article [:favorite "welcome"]]}}
   {:id 21 :operation :rf.mutation/optimistic-reconciled
    :tags {:mutation :realworld/favorite-article :instance [:favorite "welcome"]
           :work/id [:rf.work/resource [:rf.mutation [:favorite "welcome"]] 5]
           :generation 5 :snapshot-id "snap-1"
           :optimistic-keys [opt-key-a] :committed [opt-key-a]
           :reconciliation-refetches [opt-key-b]
           :cause [:mutation :realworld/favorite-article [:favorite "welcome"]]}}
   ;; an apply that FAILED → rolled back: one key restored, one conflicted
   {:id 22 :operation :rf.mutation/optimistic-applied
    :tags {:mutation :realworld/rename :instance [:rename 7]
           :work/id [:rf.work/resource [:rf.mutation [:rename 7]] 9]
           :generation 9 :scope :rf.scope/global :snapshot-id "snap-2"
           :affected-keys [opt-key-a opt-key-b]
           :revisions [{:resource/key opt-key-a :revision 1 :forward :patch}
                       {:resource/key opt-key-b :revision 2 :forward :seed}]
           :tag-matched-keys [opt-key-b]
           :target-unresolved [:realworld/tenant]
           :cause [:mutation :realworld/rename [:rename 7]]}}
   {:id 23 :operation :rf.mutation/optimistic-rolled-back
    :tags {:mutation :realworld/rename :instance [:rename 7]
           :work/id [:rf.work/resource [:rf.mutation [:rename 7]] 9]
           :generation 9 :snapshot-id "snap-2" :on-conflict :invalidate
           :dispositions [{:resource/key opt-key-a :restored true :conflict false}
                          {:resource/key opt-key-b :restored false :conflict true
                           :on-conflict :invalidate}]
           :restored [opt-key-a] :conflicted [opt-key-b] :refetched [opt-key-b]
           :cause [:rf.mutation/failed :realworld/rename]}}
   ;; an apply with NO terminal settle → :pending
   {:id 24 :operation :rf.mutation/optimistic-applied
    :tags {:mutation :realworld/toggle :instance [:toggle 1]
           :work/id [:rf.work/resource [:rf.mutation [:toggle 1]] 11]
           :generation 11 :scope opt-scope-a :snapshot-id "snap-3"
           :affected-keys [opt-key-a]
           :revisions [{:resource/key opt-key-a :revision 0 :forward :seed}]
           :tag-matched-keys [] :target-unresolved []
           :cause [:mutation :realworld/toggle [:toggle 1]]}}
   ;; a :force clobber warning
   {:id 25 :operation :rf.warning/optimistic-force-clobber
    :tags {:mutation :realworld/rename :instance [:rename 7]
           :forced-keys [opt-key-b] :recovery :review-on-conflict
           :reason "mutation :realworld/rename rolled back with :on-conflict :force"}}])

(defn- rids [scoped-key-summaries] (mapv :resource-id scoped-key-summaries))

(deftest optimistic-lifecycle-test
  (let [[committed rolled :as rows] (h/optimistic-lifecycle ep0019-trace-buffer)]
    (testing "one row per apply, in apply order, paired with its settle by snapshot-id"
      (is (= [[20 :reconciled 21] [22 :rolled-back 23] [24 :pending nil]]
             (mapv (juxt :id :outcome :settled-id) rows))))
    (testing "a reconciled apply carries its committed keys, refetches and forward ops"
      (is (= [[:realworld/article] [:realworld/feed] [{:revision 3 :forward :patch}]]
             [(rids (:committed committed))
              (rids (:reconciliation-refetches committed))
              (mapv #(dissoc % :resource/key) (:forward committed))])))
    (testing "a rolled-back apply carries the conflict rule and per-key dispositions"
      (is (= :invalidate (:on-conflict rolled)))
      (is (= [[:realworld/article true false nil] [:realworld/feed false true :invalidate]]
             (mapv (juxt #(get-in % [:resource/key :resource-id]) :restored :conflict :on-conflict)
                   (:dispositions rolled))))
      (is (= [1 1 1] (mapv #(count (% rolled)) [:restored :conflicted :refetched]))))))

(deftest optimistic-force-clobbers-test
  (testing "one row per :rf.warning/optimistic-force-clobber, its forced keys summarized"
    (is (= [[25 [:realworld/feed]]]
           (mapv (juxt :id (comp rids :forced-keys))
                 (h/optimistic-force-clobbers ep0019-trace-buffer))))))

;; ---- lints -----------------------------------------------------------------

(deftest scope-audit+lints
  (let [registry-rows (h/project-registry registrations routes-map)
        instance-rows (h/project-instances entries now)]
    (testing "global-scope audit enumerates every :rf.scope/global resource"
      (let [audit (h/global-scope-audit registry-rows)]
        (is (= #{:article/by-slug :me/profile}
               (set (map :resource-id audit))))))
    (testing "suspicious-global flags a /me-ish explicit-global"
      (let [warns (h/suspicious-global-warnings registry-rows)]
        (is (= [:me/profile] (mapv :resource-id warns)))))
    (testing "orphaned-owner lint flags an app-kind owner with no release"
      ;; the trace releases u-42, never u-99
      (let [pinned (assoc entries
                          [session-scope :dashboard/summary {:user-id "u-99"}]
                          {:resource/id :dashboard/summary :status :loaded
                           :data {} :active-owners #{[:dashboard/opened "u-99"]}})
            rows   (h/project-instances pinned now)
            orphans (h/orphaned-owner-lint rows trace-buffer)]
        (is (= [[:dashboard/opened "u-99"]] (mapv :owner orphans))))
      (testing "a route/machine/ssr owner is framework-released — not linted"
        (is (empty? (h/orphaned-owner-lint instance-rows trace-buffer))
            "the route-owned fresh entry is not an app-kind owner")))))

;; ---- an EMPTY infinite feed is not has-data --------------------------------
;;
;; The seeded-empty page vector `[]` is a first load, as in
;; `rf.resources.state/has-data?`; reading it as data would let the rollup call
;; a never-loaded feed `:fresh`.

(deftest empty-infinite-feed-has-no-data-test
  (let [row-of     (fn [entry] (h/instance-row ["k" entry] now))
        empty-feed {:resource/id     :feed/articles
                    :resource/key    [session-scope :feed/articles {}]
                    :status          :loading
                    :infinite?       true
                    :data            []
                    :page-params     []
                    :next-page-param nil
                    :active-owners   #{[:feed/opened "feed"]}}
        scalar     {:resource/id  :article/by-slug
                    :resource/key [session-scope :article/by-slug {:slug "x"}]
                    :status       :loaded}
        row        (row-of empty-feed)]
    (testing "a seeded-empty page vector is no data, so the live rollup reads first load"
      (is (false? (:has-data? row)))
      (is (= :loading
             (-> (h/project-route-graph
                   {:route/feed {:path      "/feed"
                                 :resources [{:resource :feed/articles :blocking? false}]}}
                   {:instance-rows [row]
                    :work-rows     [{:resource-id :feed/articles :terminal? false}]})
                 first :resources first :live :freshness))))
    (testing "one empty page is still a loaded page"
      (is (true? (:has-data? (row-of (assoc empty-feed :data [[]] :status :loaded))))))
    (testing "a scalar keeps nil / non-nil semantics: [] is data, nil is not"
      (is (= [true false] (mapv #(:has-data? (row-of (assoc scalar :data %))) [[] nil]))))
    (testing "an upstream-redacted payload means data WAS present"
      (is (true? (:has-data? (row-of (assoc scalar :data :rf/redacted))))))))

;; ---- ON-BOX sensitive-resource redaction -----------------------------------
;;
;; A trace row has no runtime-db path, so the instance rows' classification
;; gate cannot reach it; the resource-id it carries keys the registry's coarse
;; `:sensitive?` declaration instead. Each case runs both ways over one buffer —
;; the sensitive resource redacts, a sibling that is not still prints — because
;; a one-way test would pass against a projection that redacted everything.

(def ^:private sensitive-registrations
  (assoc-in registrations [:article/by-slug :rf/resource :sensitive?] true))

(def ^:private sensitive-rids
  (h/sensitive-resource-ids (h/project-registry sensitive-registrations routes-map)))

(def ^:private mixed-family-buffer
  "`:article/by-slug` (declared `:sensitive?` above) beside `:comments/list` (not)."
  [{:id 1 :operation :rf.resource/fetch-started
    :tags {:resource/key [session-scope :article/by-slug {:slug "welcome"}]
           :generation 4 :status :loading
           :cause [:route :route/article "nav-1"]}}
   {:id 2 :operation :rf.resource/fetch-started
    :tags {:resource/key [session-scope :comments/list {:slug "welcome"}]
           :generation 1 :status :loading
           :cause [:route :route/article "nav-1"]}}
   {:id 3 :operation :rf.resource/invalidated
    :tags {:scope session-scope :tags #{[:article "welcome"]}
           :cause [:mutation :article/save "m-1"]
           :matched [[session-scope :article/by-slug {:slug "welcome"}]]
           :refetched 1}}
   {:id 4 :operation :rf.resource/invalidated
    :tags {:scope session-scope :tags #{[:comments "welcome"]}
           :cause [:mutation :comment/add "m-2"]
           :matched [[session-scope :comments/list {:slug "welcome"}]]
           :refetched 0}}])

(defn- row-by-id [rows id] (first (filter #(= id (:id %)) rows)))

(defn- redacted-at
  "The `:redacted?` flag of the summary at each of `paths` in `row`."
  [row paths]
  (mapv #(get-in row (conj % :redacted?)) paths))

(deftest lifecycle-timeline-redacts-a-sensitive-resource-on-box
  (let [gated (h/lifecycle-timeline mixed-family-buffer nil sensitive-rids)
        s     (row-by-id gated 1)
        slots [[:resource/key :scope] [:resource/key :params] [:cause]]]
    (testing "the sensitive resource's value-bearing slots redact"
      (is (= [true true true] (redacted-at s slots))))
    (testing "CONTROL — the sibling in the same rows still prints"
      (is (= [false false false] (redacted-at (row-by-id gated 2) slots))))
    (testing "the metadata is never redacted — the lifecycle shape survives"
      (is (= [:article/by-slug :rf.resource/fetch-started 4 :loading]
             ((juxt :resource-id :operation :generation #(get-in % [:status :after])) s))))))

(deftest invalidation-graph-redacts-a-sensitive-resource-on-box
  (let [gated (h/invalidation-graph mixed-family-buffer nil sensitive-rids)
        s     (row-by-id gated 3)
        slots [[:scope] [:cause] [:matched 0 :scope] [:matched 0 :params]]]
    (testing "one row per :rf.resource/invalidated event"
      (is (= [3 4] (mapv :id gated))))
    (testing "a row that matched a sensitive key redacts its scope, cause and matched keys"
      (is (= [true true true true] (redacted-at s slots))))
    (testing "CONTROL — the sibling invalidation still prints"
      (is (= [false false false false] (redacted-at (row-by-id gated 4) slots))))
    (testing "the tag axis, the storm / zero-match counts and the matched resource-id survive"
      (is (= [[[:article "welcome"]] 1 1 :article/by-slug]
             ((juxt :tags :match-count :refetched #(get-in % [:matched 0 :resource-id])) s))))))

;; A work record sits where no per-instance declaration is lowered, so it takes
;; the resource-id gate; its failed outcome carries the error envelope.

(def ^:private work-secret "tok-9f3e-secret")

(def ^:private mixed-work-ledger
  "Two failed records — `:article/by-slug` (sensitive) and `:comments/list`
  (not) — each carrying the same secret in its scope, params, cause and outcome."
  (let [record (fn [rid]
                 (let [k [[:rf.scope/session {:token work-secret}] rid {:slug work-secret}]]
                   {:work/id      [:rf.work/resource k 3]
                    :work/kind    :resource
                    :resource/key k
                    :generation   3
                    :status       :failed
                    :owners       #{[:route :route/article "nav-1"]}
                    :causes       [[:route-entry :route/article work-secret]]
                    :outcome      {:error {:status 401 :body work-secret}
                                   :completed-at 42}}))]
    (byte-keyed-ledger [(record :article/by-slug) (record :comments/list)])))

(defn- work-row-for [rows rid]
  (first (filter #(= rid (:resource-id %)) rows)))

(defn- leaks-secret? [summary]
  (boolean (some #(and (string? %) (str/includes? % work-secret)) (vals summary))))

(deftest work-ledger-redacts-a-sensitive-resource-on-box
  (let [gated (h/project-work-ledger mixed-work-ledger sensitive-rids)
        s     (work-row-for gated :article/by-slug)
        ok    (work-row-for gated :comments/list)]
    (testing "the sensitive resource's scope, params, causes and outcome redact"
      (is (= [true true [true] true]
             [(get-in s [:resource/key :scope :redacted?])
              (get-in s [:resource/key :params :redacted?])
              (mapv :redacted? (:causes s))
              (get-in s [:outcome :redacted?])])))
    (testing "CONTROL — the sibling keeps its diagnostic previews"
      (is (leaks-secret? (get-in ok [:resource/key :scope])))
      (is (leaks-secret? (:outcome ok))))
    (testing "the metadata is never redacted"
      (is (= [:article/by-slug :article/by-slug :failed true 3 [[:route :route/article "nav-1"]]]
             ((juxt :resource-id #(get-in % [:resource/key :resource-id])
                    :status :terminal? :generation :owners)
              s))))))

(deftest resource-projection-rows-test
  (testing "keeps the resource and mutation families and the two warnings the
            projections read, never the rest of the shared :rf.warning namespace"
    (is (= [1 2 3 4 92 93 94]
           (mapv :id (h/resource-projection-rows
                       (into mixed-family-buffer
                             [{:id 90 :operation :rf.event/dispatched :tags {}}
                              {:id 91 :operation :rf.sub/run :tags {}}
                              {:id 92 :operation :rf.mutation/succeeded :tags {}}
                              {:id 93 :operation :rf.warning/optimistic-force-clobber :tags {}}
                              {:id 94 :operation :rf.warning/mutation-scope-mismatch :tags {}}
                              {:id 95 :operation :rf.warning/slow-render :tags {}}])))))))

;; ---- :error liveness ---------------------------------------------------------
;;
;; Without the `:error` arm a blocking resource whose first load FAILED would
;; fall through to `:idle` and paint as if nothing had been asked of it.

(deftest resource-liveness-surfaces-error
  (let [failed    {:resource/id   :article/by-slug
                   :resource/key  [session-scope :article/by-slug {:slug "welcome"}]
                   :status        :error
                   :error         {:kind :rf.http/server-error :status 500}
                   :data          nil
                   :generation    1
                   :active-owners #{[:route :route/article "nav-1"]}}
        freshness (fn [work-rows]
                    (->> (h/project-route-graph
                           routes-map
                           {:instance-rows [(h/instance-row ["k" failed] now)]
                            :work-rows     work-rows})
                         first :resources
                         (filter #(= :article/by-slug (:resource %)))
                         first :live :freshness))]
    (testing "a failed resource with no data and no live work reads :error, not :idle"
      (is (= :error (freshness []))))
    (testing ":loading still wins — a failed entry being retried is loading"
      (is (= :loading (freshness [{:resource-id :article/by-slug :terminal? false}]))))))

;; ---- the :superseded optimistic outcome --------------------------------------
;;
;; A reply for a superseded generation emits neither settle op, so without the
;; `:rf.mutation/stale-suppressed` join the apply would read pending for ever.

(def ^:private opt-instance [:favorite "welcome"])
(def ^:private opt-work-id [:rf.work/mutation :favorite 7])
(def ^:private opt-affected [session-scope :article/by-slug {:slug "welcome"}])

(def ^:private opt-apply-ev
  {:id 10 :operation :rf.mutation/optimistic-applied
   :tags {:mutation      :article/favorite
          :instance      opt-instance
          :work/id       opt-work-id
          :generation    7
          :snapshot-id   "snap-7"
          :scope         session-scope
          :affected-keys [opt-affected]
          :cause         [:mutation :article/favorite opt-instance]}})

(def ^:private opt-suppressed-ev
  {:id 11 :operation :rf.mutation/stale-suppressed
   :tags {:instance             opt-instance
          :generation           7
          :outcome              :success
          :rf.reply/work-id     opt-work-id
          :rf.reply/status      :stale
          :rf.reply/work-status :suppressed}})

(def ^:private opt-reconciled-ev
  {:id 12 :operation :rf.mutation/optimistic-reconciled
   :tags {:snapshot-id              "snap-7"
          :instance                 opt-instance
          :committed                [opt-affected]
          :reconciliation-refetches []}})

(defn- outcome-of [buffer]
  ((juxt :outcome :settled-id) (first (h/optimistic-lifecycle buffer))))

(deftest optimistic-superseded-outcome
  (testing "a stale-suppressed reply for the same work settles the apply as :superseded"
    (is (= [:superseded 11] (outcome-of [opt-apply-ev opt-suppressed-ev]))))
  (testing "the join falls back to :generation when the work-id is absent"
    (is (= [:superseded 11]
           (outcome-of [opt-apply-ev (update opt-suppressed-ev :tags dissoc :rf.reply/work-id)]))))
  (testing "a settle op wins over a later suppression for the same instance"
    (is (= [:reconciled 12] (outcome-of [opt-apply-ev opt-reconciled-ev opt-suppressed-ev]))))
  (testing "a suppression for another instance leaves the apply :pending"
    (is (= [:pending nil]
           (outcome-of [opt-apply-ev
                        (-> opt-suppressed-ev
                            (assoc-in [:tags :instance] [:favorite "other"])
                            (assoc-in [:tags :rf.reply/work-id] [:rf.work/mutation :favorite 9]))])))))

(deftest optimistic-lifecycle-redacts-a-sensitive-resource-on-box
  (let [row   #(first (h/optimistic-lifecycle [opt-apply-ev] %))
        gated (row sensitive-rids)
        slots [[:scope] [:cause] [:affected-keys 0 :scope] [:affected-keys 0 :params]]]
    (testing "an apply touching a sensitive key redacts its scope, cause and keys"
      (is (= [true true true true] (redacted-at gated slots))))
    (testing "CONTROL — ungated, the same row prints"
      (is (= [false false false false] (redacted-at (row nil) slots))))
    (testing "identity and counts survive"
      (is (= [:article/favorite opt-instance [:article/by-slug]]
             ((juxt :mutation :instance (comp rids :affected-keys)) gated))))))

(deftest optimistic-force-clobbers-redacts-a-sensitive-resource-on-box
  (let [ev    {:id 20 :operation :rf.warning/optimistic-force-clobber
               :tags {:mutation    :article/favorite
                      :instance    opt-instance
                      :forced-keys [opt-affected]
                      :recovery    :review-on-conflict
                      :reason      "rolled back with :on-conflict :force"}}
        row   #(first (h/optimistic-force-clobbers [ev] %))
        gated (row sensitive-rids)]
    (testing "the forced keys redact; ungated they print"
      (is (= [true false]
             (mapv #(get-in % [:forced-keys 0 :scope :redacted?]) [gated (row nil)]))))
    (testing "the warning stays exactly as loud"
      (is (= [:article/favorite :review-on-conflict "rolled back with :on-conflict :force" 1]
             ((juxt :mutation :recovery :reason (comp count :forced-keys)) gated))))))

(deftest mutation-invalidation-evidence-redacts-a-sensitive-resource-on-box
  (let [ev    {:id 30 :operation :rf.mutation/succeeded
               :tags {:mutation :article/favorite
                      :instance opt-instance
                      :invalidation
                      {:descriptor-count 1
                       :dispatched [{:scope session-scope :cross-scope? false
                                     :tags [[:article "welcome"]]
                                     :refetch-populated? false
                                     :exempt-keys [opt-affected]}]
                       :unresolved []
                       :populate-exempt [opt-affected]}}}
        row   #(first (h/mutation-invalidation-evidence [ev] %))
        gated (row sensitive-rids)
        slots [[:dispatched 0 :scope] [:dispatched 0 :exempt-keys 0 :params] [:populate-exempt 0 :scope]]]
    (testing "a settlement sparing a sensitive key redacts the descriptor scope and exempt keys"
      (is (= [true true true] (redacted-at gated slots))))
    (testing "CONTROL — ungated they print"
      (is (= [false false false] (redacted-at (row nil) slots))))
    (testing "descriptor identity and the fail-closed evidence survive"
      (is (= [1 [[:article "welcome"]] []]
             ((juxt :descriptor-count #(get-in % [:dispatched 0 :tags]) :unresolved) gated))))))

(deftest optimistic-reach-lint-redacts-a-sensitive-resource-on-box
  (let [ev      {:id 40 :operation :rf.mutation/optimistic-reconciled
                 :tags {:mutation        :article/favorite
                        :instance        opt-instance
                        :work/id         opt-work-id
                        :optimistic-keys [opt-affected]
                        :committed       []
                        :reconciliation-refetches []}}
        gated   (first (h/optimistic-reach-lint [ev] sensitive-rids))
        ungated (first (h/optimistic-reach-lint [ev]))]
    (testing "the missing keys redact; ungated they print"
      (is (= [true false]
             (mapv #(get-in % [:missing-keys 0 :scope :redacted?]) [gated ungated]))))
    (testing "the hint names resource-ids only, so it survives verbatim"
      (is (= (:hint ungated) (:hint gated)))
      (is (re-find #"article/by-slug" (:hint gated)))
      (is (nil? (re-find #"u-42" (:hint gated)))
          "the hint carries no scope or params value"))))

;; ---- the optimistic joins are FRAME-SCOPED -----------------------------------
;;
;; Instance, work-id, generation and snapshot-id are all frame-local, and the
;; panel feeds these projections one deliberately cross-frame buffer, so a
;; frame-blind join would let frame B's terminal settle frame A's apply. Each
;; case pins both directions: a foreign frame must not settle, the same frame
;; must.

(def ^:private frame-a :rf/default)
(def ^:private frame-b :rf/other-frame)

(defn- in-frame [ev frame]
  (assoc-in ev [:tags :rf.frame/id] frame))

(deftest optimistic-supersession-is-frame-scoped-rf2-qqi7u
  (testing "a foreign-frame suppression must not settle the apply through the :generation fallback"
    (is (= :pending
           (:outcome (first (h/optimistic-lifecycle
                              [(in-frame opt-apply-ev frame-a)
                               (-> opt-suppressed-ev
                                   (update :tags dissoc :rf.reply/work-id)
                                   (in-frame frame-b))]))))))
  (testing "both frames in one buffer: A's own suppression settles A and leaves B's
            identical apply pending"
    (is (= [[10 :superseded 11] [20 :pending nil]]
           (mapv (juxt :id :outcome :settled-id)
                 (h/optimistic-lifecycle
                   [(in-frame opt-apply-ev frame-a)
                    (assoc (in-frame opt-apply-ev frame-b) :id 20)
                    (in-frame opt-suppressed-ev frame-a)]))))))

(deftest optimistic-settlement-is-frame-scoped-rf2-qqi7u
  (let [row #(first (h/optimistic-lifecycle [(in-frame opt-apply-ev frame-a)
                                             (in-frame opt-reconciled-ev %)]))]
    (testing "a reconcile in ANOTHER frame neither settles this apply nor lends it
              :committed keys"
      (is (= [:pending nil false]
             ((juxt :outcome :settled-id #(contains? % :committed)) (row frame-b)))))
    (testing "the SAME frame's reconcile settles it"
      (is (= [:reconciled 12 1]
             ((juxt :outcome :settled-id (comp count :committed)) (row frame-a)))))))

(deftest optimistic-reach-lint-is-frame-scoped-rf2-qqi7u
  (let [reconciled {:id 40 :operation :rf.mutation/optimistic-reconciled
                    :tags {:mutation                 :article/favorite
                           :instance                 opt-instance
                           :work/id                  opt-work-id
                           :optimistic-keys          [opt-affected]
                           :committed                []
                           :reconciliation-refetches []}}
        succeeded  {:id 41 :operation :rf.mutation/succeeded
                    :tags {:mutation      :article/favorite
                           :instance      opt-instance
                           :work/id       opt-work-id
                           :affected-keys [opt-affected]}}
        findings   #(mapv (juxt :mutation (comp count :missing-keys))
                          (h/optimistic-reach-lint [(in-frame reconciled frame-a)
                                                    (in-frame succeeded %)]))]
    (testing "CONTROL — the same frame's settlement reaches the key: no finding"
      (is (= [] (findings frame-a))))
    (testing "a settlement in another frame must not answer for this one"
      (is (= [[:article/favorite 1]] (findings frame-b))))))

;; The reach lint's two other frame-local identities: the scope-mismatch
;; suppression set and the dedupe key. Both cases run through the production
;; family filter, so the suppression arm is tested on a buffer the panel
;; actually sees.

(def ^:private reach-reconciled
  "An optimistic reconcile whose single optimistic key nothing reaches."
  {:id 50 :operation :rf.mutation/optimistic-reconciled
   :tags {:mutation                 :article/favorite
          :instance                 opt-instance
          :work/id                  opt-work-id
          :optimistic-keys          [opt-affected]
          :committed                []
          :reconciliation-refetches []}})

(def ^:private reach-scope-warning
  "The write-side scope-mismatch tripwire, naming `opt-affected`'s own scope."
  {:id 51 :operation :rf.warning/mutation-scope-mismatch
   :tags {:mutation         :article/favorite
          :instance         opt-instance
          :descriptor-scope :rf.scope/global
          :mutation-scope   :rf.scope/global
          :other-scope      session-scope
          :tags             [:article]
          :recovery         :fix-scope}})

(defn- reach-lint [evs]
  (h/optimistic-reach-lint (h/resource-projection-rows evs)))

(deftest optimistic-reach-lint-dedupe-is-frame-scoped-rf2-389dv
  (testing "the same reconcile in two frames is two independent findings, in buffer order"
    (is (= [50 60]
           (mapv :id (reach-lint [(in-frame reach-reconciled frame-a)
                                  (assoc (in-frame reach-reconciled frame-b) :id 60)])))))
  (testing "same-frame duplicates collapse to the first"
    (is (= [50]
           (mapv :id (reach-lint [(in-frame reach-reconciled frame-a)
                                  (assoc (in-frame reach-reconciled frame-a) :id 61)]))))))

(deftest optimistic-reach-lint-warning-suppression-is-frame-scoped-rf2-389dv
  (testing "a same-frame scope-mismatch warning suppresses the finding — one diagnostic, not two"
    (is (empty? (reach-lint [(in-frame reach-reconciled frame-a)
                             (in-frame reach-scope-warning frame-a)]))))
  (testing "a warning emitted in another frame does not erase this frame's finding"
    (is (= [[:article/favorite opt-instance 1]]
           (mapv (juxt :mutation :instance (comp count :missing-keys))
                 (reach-lint [(in-frame reach-reconciled frame-a)
                              (in-frame reach-scope-warning frame-b)]))))))

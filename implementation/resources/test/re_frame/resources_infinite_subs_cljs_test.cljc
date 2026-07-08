(ns re-frame.resources-infinite-subs-cljs-test
  "The infinite-feed subscription family (EP-0021 wave 4, Spec 016
  §Subscription contract — the merged list and page metadata (R3)).

  Waves 1-3 landed the durable feed entry (`:data` = the ordered page vector),
  the `:rf.resource/load-more` event, and the page reply handlers. This slice
  is the READ side — the framework-owned, MEMOISED projection family:

    - `:rf.resource/items` — the merged / flattened list (the HEADLINE read);
    - `:rf.resource/pages` — the raw ordered page vector;
    - `:rf.resource/infinite-state` — the combined view-model;
    - `:rf.resource/page-count` / `:has-next-page?` / `:has-prev-page?` /
      `:page-error` — page metadata;
    - `:rf.resource/fetching-next?` — a LOAD-MORE in flight (R2), DISTINCT from
      `:fetching?` (a whole-feed refresh), derived from the in-flight work
      record's page index;
    - the R3 LOUD merge: a non-vector page with no `:page->items` raises
      `:rf.error/infinite-missing-page-accessor` at the merge site.

  The subs are PASSIVE — they never cause a fetch (the load-more EVENT does).
  Driven through the live event + capturing-transport path so the projections
  read genuine accumulated feed entries."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.subs]
   [re-frame.resources.test-support]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport (replays the real reply-append shape) ------------

(def ^:private last-managed-args (atom nil))

(defn- capturing-transport-fixture [f]
  (reset! last-managed-args nil)
  (rf/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))

(defn- reply-success!
  ([data] (reply-success! @last-managed-args data))
  ([args data]
   (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data}))))

(defn- reply-failure!
  ([failure] (reply-failure! @last-managed-args failure))
  ([args failure]
   (rf/dispatch-sync (conj (:on-failure args) {:status :error :error failure}))))

(def ^:private next-cursor
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))
(def ^:private prev-cursor
  (fn [first-page _all-pages] (get-in first-page [:page-info :prev-cursor])))

(defn- page
  "An enveloped (non-vector) page: items + a page-info cursor envelope."
  ([items next-c] (page items next-c nil))
  ([items next-c prev-c]
   {:items items :page-info {:next-cursor next-c :prev-cursor prev-c}}))

(defn- feed-spec
  "A minimal valid infinite-feed resource METADATA map — enveloped pages with a
  :page->items accessor (the common non-vector case). The `:request` fn lives
  separately in `feed-spec-request` (the 3-slot grammar's third positional
  arg); pass it as the trailing arg at each reg-resource call site."
  ([] (feed-spec {}))
  ([overrides]
   (merge {:scope            :rf.scope/global
           :infinite         true
           :params-schema    [:map [:filter :keyword]]
           :next-page-param  next-cursor
           :prev-page-param  prev-cursor
           :page->items      :items
           :tags             (fn [{:keys [filter]} _data] #{[:feed filter]})}
          overrides)))

(def ^:private feed-spec-request
  (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
    {:request {:method :get :url "/api/feed"
               :params (cond-> {:filter filter :page-index page-index}
                         page-param (assoc :cursor page-param))}}))

(defn- feed-key [resource]
  (state/scoped-resource-key :rf.scope/global resource {:filter :recent}))

(defn- q [resource]
  {:resource resource :scope :rf.scope/global :params {:filter :recent}})

(defn- ensure! [resource]
  (rf/dispatch-sync [:rf.resource/ensure
                     (assoc (q resource) :owner [:test :w])]))

(defn- load-more! [resource]
  (rf/dispatch-sync [:rf.resource/load-more
                     (assoc (q resource) :owner [:test :w]
                            :cause [:user :feed/load-more])]))

(defn- load-page-0!
  "Register + ensure a feed and settle page-0 with `pg`. Returns the resource."
  [resource pg]
  (rf/reg-resource resource (feed-spec) feed-spec-request)
  (ensure! resource)
  (reply-success! pg)
  resource)

;; ===========================================================================
;; 1. empty-feed projections (a passive sub NEVER fetches)
;; ===========================================================================

(deftest empty-feed-projections
  (rf/reg-resource :is1/feed (feed-spec) feed-spec-request)
  (testing "before any load the infinite projections are the empty-feed shape"
    (is (= [] @(rf/subscribe [:rf.resource/items (q :is1/feed)])))
    (is (= [] @(rf/subscribe [:rf.resource/pages (q :is1/feed)])))
    (is (= 0 @(rf/subscribe [:rf.resource/page-count (q :is1/feed)])))
    (is (false? @(rf/subscribe [:rf.resource/has-next-page? (q :is1/feed)])))
    (is (false? @(rf/subscribe [:rf.resource/has-prev-page? (q :is1/feed)])))
    (is (false? @(rf/subscribe [:rf.resource/fetching-next? (q :is1/feed)])))
    (is (nil? @(rf/subscribe [:rf.resource/page-error (q :is1/feed)]))))
  (testing "infinite-state empty-feed view-model"
    (is (= {:status :idle :items [] :pages [] :page-count 0
            :has-next-page? false :has-prev-page? false
            :loading? false :fetching? false :fetching-next? false
            :stale? false :has-data? false
            :error nil :refresh-error nil :page-error nil}
           @(rf/subscribe [:rf.resource/infinite-state (q :is1/feed)]))))
  (testing "no entry was conjured by subscribing (subs are passive)"
    (is (nil? (entry (feed-key :is1/feed))))))

;; ===========================================================================
;; 1b. the SCALAR :has-data? / :state subs over an infinite feed (rf2-3fynns)
;;
;;   The scalar `:rf.resource/has-data?` and `:rf/resource` subs ALSO
;;   apply to an infinite feed (Spec 016 §Subscriptions — the family is
;;   resource-kind agnostic). An infinite feed's `:data` is the page VECTOR,
;;   seeded EMPTY (`[]`) before page 0 lands, so the scalar `(some? :data)`
;;   test would WRONGLY read an empty/never-loaded feed as `:has-data? true`
;;   (and `:rf/resource` would return the self-contradictory
;;   `{:status :loading … :has-data? true}`). Both scalar subs must route
;;   through the shared `state/has-data?` derivation (the same one
;;   `:rf.resource/infinite-state` uses), so the two PUBLIC sub families agree.
;; ===========================================================================

(deftest scalar-has-data?-false-for-empty-infinite-feed
  (testing "an EMPTY (never-loaded) infinite feed has a live entry with :data []"
    (rf/reg-resource :is1b/feed (feed-spec) feed-spec-request)
    (ensure! :is1b/feed)                       ;; entry now exists, :status :loading
    (let [e (entry (feed-key :is1b/feed))]
      (is (some? e) "ensure created the durable feed entry")
      (is (= [] (:data e)) "an unloaded infinite feed seeds the page vector empty")
      (is (= :loading (:status e)) "first load, no usable data ⇒ :loading"))
    (testing "the scalar :rf.resource/has-data? reads FALSE (empty page vector)"
      (is (false? @(rf/subscribe [:rf.resource/has-data? (q :is1b/feed)]))
          "an empty page vector is NO usable data — must match state/has-data?"))
    (testing "the scalar :rf/resource's :has-data? agrees (and isn't self-contradictory)"
      (let [vm @(rf/subscribe [:rf/resource (q :is1b/feed)])]
        (is (= :loading (:status vm)) "still first-loading")
        (is (false? (:has-data? vm))
            ":has-data? false for an empty feed — not the self-contradictory true")
        (is (true? (:loading? vm)) "first load with no usable data")))
    (testing "the scalar and infinite-feed sub families AGREE on :has-data?"
      (is (= @(rf/subscribe [:rf.resource/has-data? (q :is1b/feed)])
             (:has-data? @(rf/subscribe [:rf/resource (q :is1b/feed)]))
             (:has-data? @(rf/subscribe [:rf.resource/infinite-state (q :is1b/feed)]))
             false)
          "all three public :has-data? readings concur on the empty feed"))))

(deftest scalar-has-data?-true-once-a-page-lands
  (testing "once page 0 lands the scalar :has-data? flips TRUE (≥1 page)"
    (load-page-0! :is1c/feed (page [:a :b] "c1"))
    (is (true? @(rf/subscribe [:rf.resource/has-data? (q :is1c/feed)])))
    (is (true? (:has-data? @(rf/subscribe [:rf/resource (q :is1c/feed)]))))
    (is (= @(rf/subscribe [:rf.resource/has-data? (q :is1c/feed)])
           (:has-data? @(rf/subscribe [:rf.resource/infinite-state (q :is1c/feed)]))
           true)
        "the scalar and infinite families agree on a loaded feed")))

;; ===========================================================================
;; 2. :rf.resource/items merges pages in order (the headline read)
;; ===========================================================================

(deftest items-merges-pages-in-order
  (load-page-0! :is2/feed (page [:a :b] "c1"))
  (testing "after page-0 :items is the flat merged list of page-0 items"
    (is (= [:a :b] @(rf/subscribe [:rf.resource/items (q :is2/feed)]))))
  (load-more! :is2/feed) (reply-success! (page [:c :d] "c2"))
  (load-more! :is2/feed) (reply-success! (page [:e] nil))   ;; terminal page
  (testing "successive load-mores accumulate the merged list IN ORDER"
    (is (= [:a :b :c :d :e] @(rf/subscribe [:rf.resource/items (q :is2/feed)]))
        "items concatenated across pages in load order"))
  (testing ":pages returns the raw ordered page vector (boundaries preserved)"
    (is (= [(page [:a :b] "c1") (page [:c :d] "c2") (page [:e] nil)]
           @(rf/subscribe [:rf.resource/pages (q :is2/feed)])))))

(deftest items-vector-pages-flatten-by-identity
  (testing "a feed whose pages are ALREADY vectors flattens with NO accessor (R3)"
    ;; pages are bare vectors of items; next-cursor lives in a parallel scheme —
    ;; here we use a fixed-count terminal (return nil after 2 pages). NO
    ;; :page->items key at all (vector pages need none).
    (rf/reg-resource :isv/feed
      (dissoc (feed-spec {:next-page-param (fn [_last-page all-pages]
                                             (when (< (count all-pages) 2)
                                               (str "p" (count all-pages))))})
              :page->items)
      feed-spec-request)
    (ensure! :isv/feed) (reply-success! [:a :b])
    (is (= [:a :b] @(rf/subscribe [:rf.resource/items (q :isv/feed)]))
        "a vector page IS its items — no :page->items needed")
    (load-more! :isv/feed) (reply-success! [:c :d])
    (is (= [:a :b :c :d] @(rf/subscribe [:rf.resource/items (q :isv/feed)]))
        "vector pages concatenate by identity")))

(deftest items-fn-accessor
  (testing "a (fn [page] …) :page->items accessor is honoured"
    (rf/reg-resource :isf/feed
      (feed-spec {:page->items (fn [p] (:rows p))
                  :next-page-param (fn [_ _] nil)})
      (fn [_ _] {:request {:method :get :url "/f"}}))
    (ensure! :isf/feed) (reply-success! {:rows [:x :y]})
    (is (= [:x :y] @(rf/subscribe [:rf.resource/items (q :isf/feed)])))))

;; ===========================================================================
;; 3. page metadata (page-count / has-next? / has-prev? / page-error)
;; ===========================================================================

(deftest page-metadata-projections
  (load-page-0! :is3/feed (page [:a] "c1" "p0"))
  (testing "after one (non-terminal) page: count 1, has-next? true, has-prev? true"
    (is (= 1 @(rf/subscribe [:rf.resource/page-count (q :is3/feed)])))
    (is (true? @(rf/subscribe [:rf.resource/has-next-page? (q :is3/feed)]))
        "next cursor present ⇒ has-next?")
    (is (true? @(rf/subscribe [:rf.resource/has-prev-page? (q :is3/feed)]))
        "prev cursor present ⇒ has-prev? (mirror observable, R7)"))
  (load-more! :is3/feed) (reply-success! (page [:b] nil))   ;; terminal next
  (testing "after the terminal page: count 2, has-next? FALSE (nil terminal)"
    (is (= 2 @(rf/subscribe [:rf.resource/page-count (q :is3/feed)])))
    (is (false? @(rf/subscribe [:rf.resource/has-next-page? (q :is3/feed)]))
        "nil next-param is the single terminal ⇒ has-next? false")))

(deftest has-prev-false-when-no-mirror
  (testing "has-prev? is false when the feed declares no :prev-page-param"
    (rf/reg-resource :isnp/feed (dissoc (feed-spec) :prev-page-param) feed-spec-request)
    (ensure! :isnp/feed) (reply-success! (page [:a] "c1"))
    (is (false? @(rf/subscribe [:rf.resource/has-prev-page? (q :isnp/feed)])))))

;; ===========================================================================
;; 4. :fetching-next? — a load-more in flight, DISTINCT from :fetching? (R2)
;; ===========================================================================

(deftest fetching-next?-true-during-load-more
  (load-page-0! :is4/feed (page [:a] "c1"))
  (testing "a settled feed (no work in flight) is NOT fetching-next?"
    (is (false? @(rf/subscribe [:rf.resource/fetching-next? (q :is4/feed)])))
    (is (= :loaded (:status (entry (feed-key :is4/feed))))))
  ;; issue a load-more but do NOT reply — the page fetch is now in flight
  (load-more! :is4/feed)
  (testing "while the load-more page is in flight the feed is :fetching"
    (is (= :fetching (:status (entry (feed-key :is4/feed))))
        "load-more is a refresh-class transition (no 6th FSM state, R2)"))
  (testing ":fetching-next? is TRUE during a load-more (R2)"
    (is (true? @(rf/subscribe [:rf.resource/fetching-next? (q :is4/feed)]))))
  (testing "the combined view-model splits the two: fetching-next? true,
            fetching? FALSE (a load-more is NOT a whole-feed refresh)"
    (let [vm @(rf/subscribe [:rf.resource/infinite-state (q :is4/feed)])]
      (is (true?  (:fetching-next? vm)))
      (is (false? (:fetching? vm)) "whole-feed :fetching? distinct from load-more")
      (is (= [:a] (:items vm)) "the accumulated page stays visible (no skeleton)")))
  ;; settle the load-more — back to :loaded, fetching-next? clears
  (reply-success! (page [:b] "c2"))
  (testing "after the page lands :fetching-next? clears"
    (is (false? @(rf/subscribe [:rf.resource/fetching-next? (q :is4/feed)])))
    (is (= [:a :b] @(rf/subscribe [:rf.resource/items (q :is4/feed)])))))

(deftest fetching?-true-fetching-next?-false-on-whole-feed-refresh
  (load-page-0! :is4b/feed (page [:a] "c1"))
  (load-more! :is4b/feed) (reply-success! (page [:b] "c2"))
  (testing "a whole-feed REFETCH (page-0 in flight) is :fetching? but NOT
            :fetching-next? — the two are disjoint (R2)"
    ;; force a refetch; do NOT reply — page-0 replacement is now in flight
    (rf/dispatch-sync [:rf.resource/refetch (assoc (q :is4b/feed) :owner [:test :w])])
    (is (= :fetching (:status (entry (feed-key :is4b/feed))))
        "a refetch over a loaded feed is :fetching (data kept)")
    (let [vm @(rf/subscribe [:rf.resource/infinite-state (q :is4b/feed)])]
      (is (false? (:fetching-next? vm)) "a whole-feed refresh is NOT a load-more")
      (is (true?  (:fetching? vm)) "it IS a whole-feed :fetching? refresh"))
    (is (false? @(rf/subscribe [:rf.resource/fetching-next? (q :is4b/feed)])))))

;; ===========================================================================
;; 5. page-error — the THIRD error channel surfaced as a sub
;; ===========================================================================

(deftest page-error-projection
  (load-page-0! :is5/feed (page [:a] "c1"))
  (load-more! :is5/feed)
  (reply-failure! {:kind :rf.http/server :status 503})
  (testing "a load-more failure surfaces via :rf.resource/page-error,
            keeps the feed, and is NOT the :error / :refresh-error channel"
    (is (= {:kind :rf.http/server :status 503}
           @(rf/subscribe [:rf.resource/page-error (q :is5/feed)])))
    (let [vm @(rf/subscribe [:rf.resource/infinite-state (q :is5/feed)])]
      (is (= {:kind :rf.http/server :status 503} (:page-error vm)))
      (is (nil? (:error vm)) "NOT the first-load error channel")
      (is (nil? (:refresh-error vm)) "NOT the whole-feed refresh channel")
      (is (= [:a] (:items vm)) "the feed is kept (couldn't-load-more, retry)")
      (is (= :loaded (:status vm))))))

;; ===========================================================================
;; 6. the combined :infinite-state view-model
;; ===========================================================================

(deftest infinite-state-view-model
  (load-page-0! :is6/feed (page [:a :b] "c1"))
  (load-more! :is6/feed) (reply-success! (page [:c] nil))    ;; terminal
  (testing "the combined view-model assembles every projection"
    (is (= {:status :loaded
            :items [:a :b :c]
            :pages [(page [:a :b] "c1") (page [:c] nil)]
            :page-count 2
            :has-next-page? false
            :has-prev-page? false
            :loading? false :fetching? false :fetching-next? false
            :stale? false :has-data? true
            :error nil :refresh-error nil :page-error nil}
           @(rf/subscribe [:rf.resource/infinite-state (q :is6/feed)])))))

;; ===========================================================================
;; 7. the LOUD merge — :rf.error/infinite-missing-page-accessor (R3)
;; ===========================================================================

(deftest missing-page-accessor-raises-at-merge
  (testing "a non-vector page with NO :page->items raises at the merge site
            (R3, loud over guessing — the runtime-detected error wave-2
            deferred to the merge layer). Asserted at the MERGE layer
            (`state/merge-pages->items` / the framework-owned `merged-items`
            projection) — a sub-body throw is otherwise routed to the runtime
            error path, the same level the sub-unresolved-scope test asserts at."
    ;; valid registration (no :page->items declared); the page is enveloped, so
    ;; the MERGE is where the missing accessor is detected.
    (rf/reg-resource :iserr/feed
      (dissoc (feed-spec) :page->items)         ;; non-vector pages, no accessor
      feed-spec-request)
    (ensure! :iserr/feed)
    (reply-success! (page [:a :b] "c1"))         ;; an enveloped (map) page
    (let [e (entry (feed-key :iserr/feed))]
      (testing "the entry accumulated the enveloped page fine (merge is read-side)"
        (is (= 1 (state/page-count e))))
      (testing "the pure merge raises :rf.error/infinite-missing-page-accessor"
        (is (thrown-with-msg?
              #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
              #"infinite-missing-page-accessor"
              (state/merge-pages->items
                (:data e) (state/resolve-page->items nil)
                :iserr/feed 'rf.resource/items))))
      (testing "the framework-owned merged-items projection raises too"
        (is (thrown-with-msg?
              #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
              #"infinite-missing-page-accessor"
              (#'re-frame.resources.subs/merged-items e 'rf.resource/items))))
      (testing "the error carries the canonical :rf.error/id discriminator"
        (is (= :rf.error/infinite-missing-page-accessor
               (try (state/merge-pages->items
                      (:data e) (state/resolve-page->items nil)
                      :iserr/feed 'rf.resource/items)
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
                      (:rf.error/id (ex-data ex))))))))))

;; ===========================================================================
;; 8. the merge is framework-owned + MEMOISED (R3)
;; ===========================================================================

(deftest items-merge-is-memoised
  (testing "the merged-items computation is memoised on the page-vector identity
            (a re-run with an identical page vector returns the cached merge)"
    (load-page-0! :is8/feed (page [:a :b] "c1"))
    (let [e      (entry (feed-key :is8/feed))
          first  (state/merge-pages->items
                   (:data e) (state/resolve-page->items :items)
                   :is8/feed 'rf.resource/items)
          ;; via the memoised entry path
          m1     (#'re-frame.resources.subs/merged-items e 'rf.resource/items)
          m2     (#'re-frame.resources.subs/merged-items e 'rf.resource/items)]
      (is (= [:a :b] first))
      (is (= [:a :b] m1))
      (is (identical? m1 m2)
          "a second merge of the IDENTICAL page vector returns the same (cached) value"))))

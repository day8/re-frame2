(ns day8.re-frame2-causa.panels.routes-helpers-cljs-test
  "Pure-data tests for Causa's Routes helpers (Phase 5, rf2-6blai).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `flows_helpers_cljs_test.cljc` /
  `issues_ribbon_helpers_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **History-event predicate** — `route-history-event?` classifies
       the three Spec 012 §Trace events; non-route events drop out.
    2. **Route-row projection** — pulls path / doc / tags off the
       reserved-key metadata; ordering by `:path` is deterministic.
    3. **Active-route projection** — projects the `:rf/route` slice
       (Spec 012 §The `:rf/route` slice).
    4. **History projection** — newest-first, capped, per-entry tag
       extraction works against both flat + `:tags`-nested shapes.
    5. **Composite projection** — `project-feed` produces every slot
       the view consumes; empty-kind discriminates the empty branch.
    6. **Formatting helpers** — `format-route-id`, `format-path`,
       `format-params`, `format-operation`, `format-time`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.routes-helpers :as h]))

;; ---- fixture builders --------------------------------------------------

(defn- routes-map
  "Builds a `{route-id metadata}` map shaped like `(rf/registrations :route)`."
  []
  {:route/home              {:doc "The landing page." :path "/"}
   :route/cart              {:doc "The cart page."    :path "/cart"}
   :route/cart.item-detail  {:doc  "Detail page for a single cart item."
                             :path "/cart/items/:id"
                             :params [:map [:id :uuid]]}
   :route/article           {:doc  "An article."
                             :path "/articles/:id{/:slug}?"
                             :tags #{:requires-auth}}})

(defn- history-event
  "Build a Spec 009-shaped trace event for a route operation."
  ([id operation]
   (history-event id operation {}))
  ([id operation {:keys [time tags] :or {time 1000 tags {}}}]
   {:id        id
    :op-type   (if (= operation :rf.route/url-changed) :info :info)
    :operation operation
    :time      time
    :tags      tags}))

;; ---- (1) history-event predicate ---------------------------------------

(deftest route-history-event-classification
  (testing ":rf.route.nav-token/allocated is a history event"
    (is (true? (h/route-history-event?
                 (history-event 1 :rf.route.nav-token/allocated)))))
  (testing ":rf.route.nav-token/stale-suppressed is a history event"
    (is (true? (h/route-history-event?
                 (history-event 2 :rf.route.nav-token/stale-suppressed)))))
  (testing ":rf.route/url-changed is a history event"
    (is (true? (h/route-history-event?
                 (history-event 3 :rf.route/url-changed)))))
  (testing "non-route operations are NOT history events"
    (is (false? (h/route-history-event? {:operation :event/dispatched})))
    (is (false? (h/route-history-event? {:operation :rf.error/handler-not-found})))
    (is (false? (h/route-history-event? {:operation :sub/run})))
    (is (false? (h/route-history-event? {:operation nil})))))

(deftest filter-history-events-keeps-only-route-ops
  (testing "filter-history-events keeps only the three route operations"
    (let [events [(history-event 1 :event/dispatched)
                  (history-event 2 :rf.route.nav-token/allocated)
                  (history-event 3 :sub/run)
                  (history-event 4 :rf.route/url-changed)
                  (history-event 5 :rf.route.nav-token/stale-suppressed)]]
      (is (= [2 4 5] (mapv :id (h/filter-history-events events)))
          "only the three route operations survive"))))

(deftest filter-history-events-nil-safe
  (testing "filter-history-events tolerates nil / empty input"
    (is (= [] (h/filter-history-events nil)))
    (is (= [] (h/filter-history-events [])))))

;; ---- (2) route-row projection ------------------------------------------

(deftest project-route-row-extracts-reserved-keys
  (testing "project-route-row pulls path / doc / tags / parent / on-match"
    (let [row (h/project-route-row
                [:route/x {:doc "Doc" :path "/x"
                           :tags #{:requires-auth}
                           :parent :route/root
                           :on-match [[:fetch-x]]}])]
      (is (= :route/x          (:route-id row)))
      (is (= "/x"              (:path row)))
      (is (= "Doc"             (:doc row)))
      (is (= #{:requires-auth} (:tags row)))
      (is (= :route/root       (:parent row)))
      (is (= [[:fetch-x]]      (:on-match row))))))

(deftest project-route-rows-sorts-by-path-then-id
  (testing "project-route-rows sorts by :path lexically, nil last"
    (let [routes {:route/c {:path "/c"}
                  :route/a {:path "/a"}
                  :route/b {:path "/b"}
                  :route/no-path {}}
          paths  (mapv :path (h/project-route-rows routes))]
      (is (= ["/a" "/b" "/c" nil] paths)
          "lexical ascending by path, nil-path entries land last"))))

(deftest project-route-rows-handles-empty
  (testing "project-route-rows returns [] on empty / nil input"
    (is (= [] (h/project-route-rows {})))
    (is (= [] (h/project-route-rows nil)))))

;; ---- (3) active-route projection ---------------------------------------

(deftest project-active-route-projects-slice
  (testing "project-active-route extracts every Spec 012 slot"
    (let [slice {:id         :route/article
                 :params     {:id "42"}
                 :query      {:q "clojure"}
                 :fragment   "section-2"
                 :transition :idle
                 :error      nil
                 :nav-token  "nav-42"}
          row   (h/project-active-route slice)]
      (is (= :route/article    (:route-id row)))
      (is (= {:id "42"}        (:params row)))
      (is (= {:q "clojure"}    (:query row)))
      (is (= "section-2"       (:fragment row)))
      (is (= :idle             (:transition row)))
      (is (nil?                (:error row)))
      (is (= "nav-42"          (:nav-token row))))))

(deftest project-active-route-nil-on-non-map
  (testing "project-active-route returns nil for nil or non-map input"
    (is (nil? (h/project-active-route nil)))
    (is (nil? (h/project-active-route "")))
    (is (nil? (h/project-active-route 42)))))

;; ---- (4) history projection --------------------------------------------

(deftest project-history-entry-extracts-tags
  (testing "project-history-entry extracts the per-entry route slots
            from the trace event's :tags map"
    (let [ev    (history-event 7 :rf.route.nav-token/allocated
                  {:time 100
                   :tags {:route-id    :route/cart
                          :nav-token   "nav-7"
                          :dispatch-id 42}})
          entry (h/project-history-entry ev)]
      (is (= 7                          (:id entry)))
      (is (= 100                        (:time entry)))
      (is (= :rf.route.nav-token/allocated (:operation entry)))
      (is (= :route/cart                (:route-id entry)))
      (is (= "nav-7"                    (:nav-token entry)))
      (is (= 42                         (:dispatch-id entry))))))

(deftest project-history-entry-defensive-on-flat-shape
  (testing "project-history-entry reads top-level keys too (defensive
            against fixtures that flatten the :tags slot)"
    (let [ev    {:id 9 :time 200 :operation :rf.route/url-changed
                 :route-id :route/home :nav-token "n9" :fragment "top"
                 :dispatch-id 11}
          entry (h/project-history-entry ev)]
      (is (= :route/home    (:route-id entry)))
      (is (= "n9"           (:nav-token entry)))
      (is (= "top"          (:fragment entry)))
      (is (= 11             (:dispatch-id entry))))))

(deftest project-history-is-newest-first
  (testing "project-history reverses oldest-first input into newest-first"
    (let [events (mapv (fn [i]
                         (history-event i :rf.route.nav-token/allocated
                           {:time (* i 100)
                            :tags {:route-id :route/x :nav-token (str "n" i)}}))
                       [1 2 3 4])
          ids    (mapv :id (h/project-history events))]
      (is (= [4 3 2 1] ids) "newest-first"))))

(deftest project-history-caps-at-n
  (testing "project-history honours the cap"
    (let [events (mapv (fn [i] (history-event i :rf.route.nav-token/allocated))
                       (range 60))
          rows   (h/project-history events 25)]
      (is (= 25 (count rows)) "cap honoured"))))

(deftest project-history-default-cap
  (testing "project-history's default cap is 50"
    (let [events (mapv (fn [i] (history-event i :rf.route.nav-token/allocated))
                       (range 100))]
      (is (= 50 (count (h/project-history events))) "default 50"))))

;; ---- (5) composite projection ------------------------------------------

(deftest project-feed-shape
  (testing "project-feed returns the canonical composite shape"
    (let [feed (h/project-feed (routes-map) nil [] nil)]
      (is (contains? feed :rows))
      (is (contains? feed :total))
      (is (contains? feed :active-route))
      (is (contains? feed :selected-route-id))
      (is (contains? feed :history))
      (is (contains? feed :empty-kind))
      (is (= 4 (:total feed)))
      (is (nil? (:empty-kind feed))))))

(deftest project-feed-empty-kind-no-routes
  (testing "empty routes-map produces :no-routes empty-kind"
    (let [feed (h/project-feed {} nil [] nil)]
      (is (= :no-routes (:empty-kind feed)))
      (is (= 0 (:total feed)))
      (is (= [] (:rows feed))))))

(deftest project-feed-threads-active-route
  (testing "project-feed projects the :rf/route slice into :active-route"
    (let [slice {:id :route/cart :transition :loading}
          feed  (h/project-feed (routes-map) slice [] nil)]
      (is (= :route/cart (-> feed :active-route :route-id)))
      (is (= :loading    (-> feed :active-route :transition))))))

(deftest project-feed-threads-history
  (testing "project-feed projects history events newest-first"
    (let [events [(history-event 1 :rf.route.nav-token/allocated)
                  (history-event 2 :rf.route.nav-token/allocated)
                  (history-event 3 :rf.route.nav-token/stale-suppressed)]
          feed   (h/project-feed (routes-map) nil events nil)]
      (is (= [3 2 1] (mapv :id (:history feed))) "newest first"))))

(deftest project-feed-threads-selection
  (testing "project-feed carries selected-route-id through"
    (let [feed (h/project-feed (routes-map) nil [] :route/cart)]
      (is (= :route/cart (:selected-route-id feed))))))

;; ---- (6) formatting helpers --------------------------------------------

(deftest format-route-id-cases
  (testing "format-route-id renders keywords / symbols / strings"
    (is (= ":route/cart" (h/format-route-id :route/cart)))
    (is (= "my.ns/foo"   (h/format-route-id 'my.ns/foo)))
    (is (= "plain"       (h/format-route-id "plain")))))

(deftest format-path-cases
  (testing "format-path renders strings unchanged and nil as the dash"
    (is (= "/cart"     (h/format-path "/cart")))
    (is (= "—"         (h/format-path nil)))))

(deftest format-params-cases
  (testing "format-params renders maps via pr-str; empty as the dash"
    (is (= "—"         (h/format-params {})))
    (is (= "—"         (h/format-params nil)))
    (is (= "{:id 1}"   (h/format-params {:id 1})))))

(deftest format-operation-labels
  (testing "format-operation produces short labels for the three Spec
            012 trace operations"
    (is (= "nav · allocated"
           (h/format-operation :rf.route.nav-token/allocated)))
    (is (= "nav · stale"
           (h/format-operation :rf.route.nav-token/stale-suppressed)))
    (is (= "url-changed (fragment)"
           (h/format-operation :rf.route/url-changed)))
    (is (= ":other/op"
           (h/format-operation :other/op))
        "unknown operations fall through to str")))

(deftest format-time-shape
  (testing "format-time produces a HH:MM:SS.mmm string"
    (let [s (h/format-time 1000)]
      (is (string? s))
      (is (re-matches #"\d{2}:\d{2}:\d{2}\.\d{3}" s))))
  (testing "format-time tolerates nil / non-number"
    (is (nil? (h/format-time nil)))
    (is (nil? (h/format-time "not-a-number")))))

(ns re-frame.bench.hicasso.shapes.framework-subs-dom-cljs-test
  "**FRAMEWORK SUBS, EXERCISED THROUGH THE ARM** (rf2-2rtt6.53).

  The charter counts framework subs at **27% of census read traffic** and
  says \"the index serves them first-class\". The roster ports read the
  same *shape* through plain `reg-sub`s (`:conduit/favorite-pending?`),
  so until this file nothing witnessed that a real framework sub reads
  identically to an app sub through the collector. Two things went
  unchecked, and each has a test here named for it:

  1. **The query vector's arg is a MAP.** The index's sub-key identity is
     value equality on `[frame-kw query-v]`, and every roster read
     exercises it on scalars. Here the census's own pair —
     `[:rf/mutation {:instance [:favorite slug]}]` alongside the row's
     app sub — is mounted through the arm, and a *freshly constructed*
     value-equal key finds the same cell and the same reader set.

  2. **A framework sub's value moves on a different clock than a db
     write.** `:rf/mutation` is a `reg-runtime-sub`: its facts live in
     the frame's runtime-db partition, installed by the resources
     machinery, never by an app-db commit. `:rf/resource` is a
     `reg-frame-state-sub`: an app-db commit re-runs its body and the
     output `=` cutoff keeps its readers quiet. Both clocks are driven
     here and judged against the same narrow law the roster's own
     witnesses state — **one commit → one body** (per reader) — with the
     roster's one documented rider: a commit the PAGE reads re-runs the
     page once, and React's no-bail-out cascade then re-runs the child
     boundaries beneath it (the finding
     `narrow_dom_cljs_test/a-page-chrome-write-re-renders-every-row`
     pins; restated here on the resource clock, not contradicted).

  The page is deliberately NOT one of the measured roster pages
  (`shapes/ordinary` / `shapes/feed` / `shapes/large_template` carry the
  box's published clock rows and stay exactly as the census port wrote
  them). It is the same state layer (`shapes/model`) under a small page
  whose card reads the census pair verbatim:

      (sub [:conduit/article slug])                          ; the app sub
      (sub [:rf/mutation {:instance [:favorite slug]}])      ; the framework sub

  and whose page-level read is the census's list shape:

      (sub [:rf/resource {:resource :conduit/feed :params {:page 1}}])

  The machinery behind the reads is the REAL resources artefact —
  `reg-resource` / `reg-mutation`, `:rf.resource/ensure`,
  `:rf.mutation/execute`, the internal reply events — with only the
  transport stubbed, exactly as the resources artefact's own suites stub
  it (a capturing `:rf.http/managed` fx; the reply is the genuine
  reply-event-append shape). No HTTP, no invented sub machinery.

  ## The arithmetic

  Three articles. One page boundary (2 reads: `[:conduit/slugs]` + the
  resource), three cards (2 reads each), one detail panel reading the
  SAME `[:rf/mutation {:instance [:favorite slug-0]}]` as card 0 — so
  law 2 (two boundaries sharing a sub both dirty) is exercised on a
  map-arg key, and the shared key holds ONE cell:

      boundaries  1 + 3 + 1        = 5
      cells       2 + (3×2) + 0    = 8   (the detail's read shares card 0's cell)
      edges       2 + (3×2) + 1    = 9

  Runtime: `-dom-cljs-test`. Under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.hook-probe :as probe]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt :refer [sub]]
            [re-frame.bench.hicasso.front.sub-index :as index]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.http.managed]
            [re-frame.resources]
            [re-frame.resources.state :as state]
            [re-frame.resources.test-support]
            [re-frame.schemas]
            [re-frame.subs :as subs]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

;; ---------------------------------------------------------------------------
;; Fixtures — the shared reset, plus the same capturing transport the
;; resources artefact's own suites use (no real fetch ever fires)
;; ---------------------------------------------------------------------------

(def ^:private !managed
  "Every `:rf.http/managed` lowering the stub saw, in order. A vector
  rather than a single slot because one witness drives a resource fetch
  and a mutation write in one page's life."
  (atom []))

(def ^:private capturing-transport-fixture
  "Map-form (this suite has an async test, and cljs.test requires every
  fixture on an async suite to be a map)."
  {:before (fn []
             (reset! !managed [])
             (fx/reg-fx :rf.http/managed
                        (fn [_ctx args] (swap! !managed conj args) nil)))})

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))})
  capturing-transport-fixture)

(def ^:private frame-id ::shape-framework-subs)

(def ^:private article-count 3)

(def ^:private seed {:articles article-count :comments 0 :tags 2})

(defn- skip! [why]
  (is true (str "the framework-subs witness needs a real React DOM — " why)))

;; ---------------------------------------------------------------------------
;; The real registrations — the resources artefact's own doors
;; ---------------------------------------------------------------------------

(defn- register-framework! []
  (rf/reg-resource :conduit/feed
    {:scope         :rf.scope/global
     :params-schema [:map [:page :int]]
     :tags          (fn [_params _data] #{[:feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url (str "/api/articles?page=" page)}}))
  (rf/reg-mutation :conduit/favorite-remote
    {:params-schema [:map [:slug :string]]}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :post :url (str "/api/articles/" slug "/favorite")}})))

(defn- fresh! []
  (lane/leave-act-environment!)
  (register-framework!)
  (m/make-frame! frame-id seed)
  (m/reseed! frame-id seed)
  frame-id)

;; ---------------------------------------------------------------------------
;; The page — the census pair, read through the collector
;; ---------------------------------------------------------------------------

(def !runs (atom {}))

(defn- ran! [k] (swap! !runs update k (fnil inc 0)) nil)

(defn- reset-runs! [] (reset! !runs {}) nil)

(defn- runs [k] (get @!runs k 0))

(def ^:private slug-0 (:slug (m/article 0)))

(defn- mutation-read
  "The census's per-row status read, verbatim
  (`examples/real-apps/realworld_resources/ui_views.cljs:119`). Built
  fresh at every call site — never shared through a def — so every read
  and every assertion exercises VALUE equality on the map arg rather than
  object identity."
  [slug]
  [:rf/mutation {:instance [:favorite slug]}])

(def ^:private resource-read
  "The census's list-shape read: `:resource` + `:params`, scope resolved
  from the registration (Spec 016 §Subscription-side scope resolution)."
  [:rf/resource {:resource :conduit/feed :params {:page 1}}])

(defview fw-card
  "One row: the app sub and the framework sub, side by side — the
  census's own pair."
  [{:keys [slug]}]
  (ran! slug)
  (let [{:keys [title favoritesCount]} (sub [:conduit/article slug])
        fav (sub (mutation-read slug))]
    [:div.fw-card {:data-testid (str "fw-card-" slug)}
     [:span.fw-title title]
     [:span {:data-testid (str "fw-count-" slug)} favoritesCount]
     [:span {:data-testid (str "fw-status-" slug)}
      (cond (:pending? fav) "pending"
            (:success? fav) "settled"
            :else           "idle")]]))

(defview fw-detail
  "A second boundary reading the SAME map-arg framework sub as card 0 —
  law 2's two-readers case, on a map-arg key. Its ONLY read is the
  runtime sub, so it is also the pure converse probe: an app-db commit
  must never move it."
  [_]
  (ran! :detail)
  (let [fav (sub (mutation-read slug-0))]
    [:span {:data-testid "fw-detail"}
     (if (:pending? fav) "saving" "quiet")]))

(defview fw-page
  [_]
  (ran! :page)
  (let [feed (sub resource-read)]
    [:div.fw-page
     [:span {:data-testid "fw-feed-status"} (name (:status feed))]
     [:span {:data-testid "fw-feed-total"}
      (str (get-in feed [:data :articlesCount] "-"))]
     [fw-detail {}]
     [:div.fw-list
      (for [slug (sub [:conduit/slugs])]
        [fw-card {:key slug :slug slug}])]]))

;; ---------------------------------------------------------------------------
;; Drives
;; ---------------------------------------------------------------------------

(defn- mount! []
  (reset-runs!)
  (mount/root! (mount/fresh-container!) frame-id [fw-page {}]))

(defn- text [handle testid]
  (some-> (.querySelector (:container handle) (str "[data-testid=\"" testid "\"]"))
          (.-textContent)))

(defn- execute-favorite! []
  (rt/dispatch! frame-id
                [:rf.mutation/execute {:mutation :conduit/favorite-remote
                                       :params   {:slug slug-0}
                                       :instance [:favorite slug-0]
                                       :cause    [:test ::witness slug-0]}])
  (mount/settle!))

(defn- settle-favorite!
  "Replay the genuine reply-event-append shape the live transport would
  dispatch (the resources suites' own pattern), targeted at the witness
  frame through the arm's synchronous door."
  []
  (let [args (last @!managed)]
    (is (some? (:on-success args)) "the stub transport saw the mutation lowering")
    (rt/dispatch! frame-id (conj (:on-success args)
                                 {:status :ok :value {:article {:slug slug-0}}}))
    (mount/settle!)))

(defn- ensure-feed! []
  (rt/dispatch! frame-id
                [:rf.resource/ensure {:resource :conduit/feed
                                      :scope    :rf.scope/global
                                      :params   {:page 1}
                                      :owner    [:app ::witness 1]}])
  (mount/settle!))

(defn- settle-feed! [total]
  (let [scoped-key (state/scoped-resource-key :rf.scope/global :conduit/feed {:page 1})
        runtime-db (:rf.db/runtime (rf/frame-state-value frame-id))
        work-id    (:current-work (get-in runtime-db (state/entry-path scoped-key)))]
    (is (some? work-id) "ensure minted in-flight work in the frame's runtime-db")
    (rt/dispatch! frame-id [:rf.resource.internal/succeeded
                            {:resource/key scoped-key :work/id work-id :generation 1
                             :data {:articlesCount total}}])
    (mount/settle!)))

;; ===========================================================================
;; 1 — the index serves a map-arg framework sub under VALUE equality
;; ===========================================================================

(deftest the-sub-key-identity-holds-on-a-map-arg-key
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (testing "the mount is the arithmetic's page"
            (is (= {:boundaries 5 :cells 8 :edges 9}
                   (select-keys (rt/stats) [:boundaries :cells :edges]))
                (str "1 page + " article-count " cards + 1 detail; the detail's "
                     "read SHARES card 0's cell, which is the map-arg dedup")))
          (testing "a freshly constructed value-equal key finds the cell"
            (is (some? (rt/cell-reaction [frame-id (mutation-read slug-0)]))
                "the map arg is a value, not an object identity")
            (is (some? (rt/cell-reaction [frame-id resource-read]))
                "and so is the resource read's"))
          (testing "and finds the readers the index holds for it"
            (let [idx (index/snapshot)]
              (is (= 2 (count (index/readers-of idx [frame-id (mutation-read slug-0)])))
                  "card 0 and the detail both hold the shared instance's edge")
              (is (= 1 (count (index/readers-of idx [frame-id (mutation-read (:slug (m/article 1)))])))
                  "card 1's instance has exactly its own reader")
              (is (= 0 (count (index/readers-of idx [frame-id (mutation-read "no-such-slug")])))
                  "law 6: an instance nobody reads has no phantom reader")))
          (finally (mount/release! handle)))))))

;; ===========================================================================
;; 2 — the mutation clock: runtime-db installs, never app-db commits
;; ===========================================================================

(deftest a-mutation-commit-moves-its-readers-and-only-its-readers
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [app-db-before (:rf.db/app (rf/frame-state-value frame-id))
                cells-before  (:cells (rt/stats))]
            (reset-runs!)
            (execute-favorite!)
            (testing "one commit → one body, per reader of the map-arg key"
              (is (= 1 (runs slug-0)) "card 0 re-ran once")
              (is (= 1 (runs :detail)) "law 2: the second reader of the SAME instance re-ran once")
              (is (= 0 (runs (:slug (m/article 1)))) "card 1 did not")
              (is (= 0 (runs (:slug (m/article 2)))) "card 2 did not")
              (is (= 0 (runs :page)) "the page did not"))
            (testing "the DOM shows the in-flight instance"
              (is (= "pending" (text handle (str "fw-status-" slug-0))))
              (is (= "saving" (text handle "fw-detail")))
              (is (= "idle" (text handle (str "fw-status-" (:slug (m/article 1)))))))
            (testing "the value moved on the runtime-db clock — app-db did not move"
              (is (identical? app-db-before (:rf.db/app (rf/frame-state-value frame-id)))
                  "no app-db write happened; the framework sub moved anyway"))
            (reset-runs!)
            (settle-favorite!)
            (testing "the reply is one more commit → one more body per reader"
              (is (= 1 (runs slug-0)))
              (is (= 1 (runs :detail)))
              (is (= 0 (runs :page)))
              (is (= "settled" (text handle (str "fw-status-" slug-0))))
              (is (= "quiet" (text handle "fw-detail"))))
            (testing "two commits on the same instance reused ONE cell"
              (is (= cells-before (:cells (rt/stats)))
                  "value-equal map args re-key the same cell — no thrash")))
          (finally (mount/release! handle)))))))

(deftest an-app-db-commit-does-not-move-the-runtime-sub
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [fav-before (subs/subscribe-once (mutation-read slug-0) {:frame frame-id})]
            (reset-runs!)
            (rt/dispatch! frame-id [:conduit/favorite slug-0])
            (mount/settle!)
            (testing "the app-db write reached the app sub's reader and nothing else"
              (is (= 1 (runs slug-0)) "card 0 re-ran — its article moved")
              (is (= 0 (runs :detail))
                  "the detail reads ONLY the runtime sub, and a runtime sub is
                   inert to an app-db commit — the different clock, in the
                   converse direction")
              (is (= 0 (runs :page))
                  "the frame-state resource sub re-derived over the new app-db
                   and its output = cutoff held its reader quiet"))
            (testing "the framework sub's value did not move"
              (is (= fav-before (subs/subscribe-once (mutation-read slug-0) {:frame frame-id})))))
          (finally (mount/release! handle)))))))

;; ===========================================================================
;; 3 — the resource clock: the census's list read, ensure → reply
;; ===========================================================================

(deftest a-resource-read-follows-the-census-shape-through-the-arm
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (is (= "idle" (text handle "fw-feed-status")) "no entry yet — the documented empty projection")
          (reset-runs!)
          (ensure-feed!)
          (testing "ensure's :loading install is one commit → one PAGE body —
                   plus the documented cascade: a Hicasso boundary is a plain
                   function component with no props-equality bail-out, so a
                   write the PAGE reads re-renders the page and React
                   re-renders every child boundary beneath it (the finding
                   `narrow_dom_cljs_test/a-page-chrome-write-re-renders-every-row`
                   pins on the roster; the same number, restated here on the
                   resource clock rather than contradicted)"
            (is (= "loading" (text handle "fw-feed-status")))
            (is (= 1 (runs :page)) "the index answered for exactly one boundary")
            (is (= 1 (runs slug-0)) "…and React's cascade re-ran the children")
            (is (= 1 (runs :detail))))
          (reset-runs!)
          (settle-feed! 42)
          (testing "the decoded reply is one more commit → one more page body
                   (and the same cascade beneath it)"
            (is (= "loaded" (text handle "fw-feed-status")))
            (is (= "42" (text handle "fw-feed-total")))
            (is (= 1 (runs :page)))
            (is (= 1 (runs :detail)) "cascade, not index: its read did not move"))
          (finally (mount/release! handle)))))))

;; ===========================================================================
;; 4 — the hook budget survives framework subs
;; ===========================================================================

(deftest framework-subs-cost-no-third-hook
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (is false (str "React's internals slot was not found, so the ≤2-hook budget "
                     "is UNWITNESSED on the framework-subs page — fix "
                     (pr-str 're-frame.bench.hicasso.arm1.hook-probe)
                     " rather than reading this as a pass."))
      (do
        (fresh!)
        (let [container (mount/fresh-container!)
              handle    (volatile! nil)
              names     (probe/record!
                          (fn [] (vreset! handle (mount/root! container frame-id [fw-page {}]))))
              stats     (rt/stats)]
          (try
            (is (= 5 (:boundaries stats)))
            (is (= (* 2 (:boundaries stats)) (count names))
                "two hooks per boundary — a framework sub is a read, and a
                 read cannot reach the hook count")
            (is (= #{"useContext" "useSyncExternalStore"} (set names)))
            (finally (mount/release! @handle))))))))

;; ===========================================================================
;; Teardown
;; ===========================================================================

(deftest the-framework-subs-page-leaves-no-residue
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (execute-favorite!)
        (settle-favorite!)
        (is (pos? (:cell-refs (rt/stats))))
        (mount/unmount! handle)
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rt/residue)))
                         (rt/reset-runtime!)
                         (done))
                       8)))))

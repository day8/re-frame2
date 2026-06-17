(ns re-frame.resources-infinite-ssr-restore-cljs-test
  "ACCEPTANCE: an infinite feed rides the EXISTING SSR projection + epoch-restore
  paths with ZERO new runtime code (EP-0021 build wave 5; Spec 016 §Durable
  cache shape (R1) / §SSR and hydration / §Restore and replay).

  The wave-1..4 design claim is structural: an infinite feed is the SAME
  `:rf/resource-entry` whose `:data` is an ordered PAGE VECTOR, living in one
  `:rf.runtime/resources :entries` slot (R1 — no new entry kind, no new
  runtime-db subsystem). So it MUST ride the projection + reconcile the scalar
  resource entries already ride, untouched. This suite is the proof — it builds
  a real multi-page feed with a load-more IN FLIGHT and pushes it through both
  paths, asserting the durable page facts survive byte-for-byte:

    (a) SERVER PROJECTION — `project-resources-runtime-db` ships the ordered
        page vector + every infinite fact (`:infinite?` / `:page-params` /
        `:next-page-param` / `:prev-page-param` / `:page-error`) verbatim in the
        ONE entry slot; a server-side read of the projected entry merges
        `:items` IDENTICALLY to the live entry (the headline R3 read rides the
        wire); the transient in-flight `:current-work` pointer is stripped (it
        references a host attempt that does not survive the round-trip) and the
        work-ledger subtree never rides at all.

    (b) EPOCH RESTORE — `reconcile-on-restore` installs the unprojected snapshot
        wholesale and reconciles it: the ordered page vector + cursor
        (`:next-page-param`) + terminal? + `:page-params` + `:prev-page-param`
        rehydrate INTACT (order preserved, no page loss); the vanished load-more
        is settled to its last-stable status (`:loaded` — the accumulated pages
        had data) with `:current-work` cleared and its non-terminal work-ledger
        row dangled; and `:rf.resource/fetching-next?` therefore resolves
        correctly to FALSE — the in-flight load-more does NOT dangle as a phantom
        `fetching-next?` against the restored feed.

  Test-only (rf2-878m79): NO runtime / spec change. If a durable page fact did
  NOT ride a path, that is a runtime gap to file, not patch here. The whole suite
  rides existing fns — that it passes IS the acceptance."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.frame :as frame]
   ;; load-bearing side-effecting requires: the façade publishes the SSR
   ;; projection + reconcile hooks + registers the resource registrar kind; the
   ;; subs ns publishes the framework-owned merged-items projection + the
   ;; :rf.resource/* read family the acceptance reads through.
   [re-frame.resources]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   [re-frame.resources.subs :as rsubs]
   [re-frame.resources.work-ledger :as work-ledger]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; ---- the feed spec + cursor fns -------------------------------------------

(def ^:private next-cursor
  "A :next-page-param fn: read the next cursor off the last page's envelope;
  nil ⇒ the single terminal."
  (fn [last-page _all-pages] (get-in last-page [:page-info :next-cursor])))

(def ^:private prev-cursor
  (fn [first-page _all-pages] (get-in first-page [:page-info :prev-cursor])))

(defn- page
  "An enveloped (non-vector) page: items + a page-info cursor envelope (the
  common case — flattening REQUIRES the `:page->items` accessor, so a wire
  round-trip that drops the accessor would surface as a merge failure)."
  ([items next-c] (page items next-c nil))
  ([items next-c prev-c]
   {:items items :page-info {:next-cursor next-c :prev-cursor prev-c}}))

(defn- reg-feed!
  "Register `:feed/timeline` as an enveloped infinite feed with a `:page->items`
  accessor (so the merged-items read needs the spec to resolve)."
  []
  (rf/clear-resource :feed/timeline)
  (rf/reg-resource :feed/timeline
    {:scope           :rf.scope/global
     :infinite        true
     :params-schema   [:map [:filter :keyword]]
     :request         (fn [{:keys [filter]} {:rf.resource/keys [page-param page-index]}]
                        {:request {:method :get :url "/api/feed"
                                   :params (cond-> {:filter filter :page-index page-index}
                                             page-param (assoc :cursor page-param))}})
     :next-page-param next-cursor
     :prev-page-param prev-cursor
     :page->items     :items
     :tags            (fn [{:keys [filter]} _data] #{[:feed filter]})}))

(def ^:private fkey
  ;; canonical global-scope key for :feed/timeline {:filter :recent}
  (state/scoped-resource-key :rf.scope/global :feed/timeline {:filter :recent}))

;; The three accumulated pages (N=3, non-terminal — a 4th page exists, "c3"),
;; built by replaying the pure append transition the wave-3 reply path drives.
(def ^:private p0 (page [:a :b] "c1" nil))
(def ^:private p1 (page [:c :d] "c2"))
(def ^:private p2 (page [:e :f] "c3"))

(defn- loaded-feed-entry
  "Build a :feed/timeline entry with the three pages appended (via the REAL pure
  `entry-append-page` transition, so the page vector / cursor / page-params /
  prev-mirror are exactly what the runtime would have produced), stamped under
  `fkey`. :loaded, fresh (far-future stale-at)."
  []
  (-> (state/empty-infinite-entry :feed/timeline fkey)
      (state/entry-append-page {:page p0 :page-param nil
                                :next-page-param-fn next-cursor
                                :prev-page-param-fn prev-cursor
                                :loaded-at 1000 :stale-at 9.0e15})
      (state/entry-append-page {:page p1 :page-param "c1"
                                :next-page-param-fn next-cursor
                                :prev-page-param-fn prev-cursor
                                :loaded-at 1100 :stale-at 9.0e15})
      (state/entry-append-page {:page p2 :page-param "c2"
                                :next-page-param-fn next-cursor
                                :prev-page-param-fn prev-cursor
                                :loaded-at 1200 :stale-at 9.0e15})))

;; The work-id + work-ledger row for the load-more (page-3) attempt IN FLIGHT:
;; a load-more APPENDS at a positive page index (3, past the accumulated tail),
;; which is the durable evidence `:rf.resource/fetching-next?` reads.
(def ^:private load-more-gen 7)
(def ^:private load-more-wid (work-ledger/resource-work-id fkey load-more-gen))

(defn- load-more-row [frame-id status]
  (-> (work-ledger/work-record
        {:work-id load-more-wid :frame-id frame-id :resource/key fkey
         :generation load-more-gen :transport :rf.http/managed
         :started-at 1300 :page-index 3})
      (assoc :status status)))

(defn- feed-with-load-more-in-flight
  "A runtime-db carrying the 3-page feed with a load-more (page-3) IN FLIGHT:
  the entry is :fetching pointing at `load-more-wid`, and a NON-terminal
  (:running) work-ledger row with `:page-index 3` exists for it. The page vector
  / cursor / page-params are the loaded-feed facts (a load-more does not mutate
  the accumulated pages until its reply lands). `frame-id` stamps the row's
  `:work/frame`."
  [frame-id]
  (let [e (assoc (loaded-feed-entry)
                 :status :fetching
                 :current-work load-more-wid)]
    {state/resources-key   {:entries   {(state/key-id fkey) (assoc e :resource/key fkey)}
                            :tag-index {} :owner-index {}}
     state/work-ledger-key {(work-ledger/work-id-id load-more-wid)
                            (load-more-row frame-id :running)}}))

;; ---- shared expectations ---------------------------------------------------

(def ^:private expected-pages   [p0 p1 p2])
(def ^:private expected-items   [:a :b :c :d :e :f])
(def ^:private expected-params  [nil "c1" "c2"])
(def ^:private expected-cursor  "c3")    ;; next-page-param after 3 non-terminal pages
(def ^:private expected-prev    nil)     ;; p0 declared no :prev-cursor → mirror nil

(defn- merged-items*
  "Merge an infinite `entry` to its flat `:items` list through the
  framework-owned `merged-items` projection (resolves the registered spec's
  `:page->items`, raises loudly on a missing accessor). The headline R3 read."
  [entry]
  (#'rsubs/merged-items entry 'rf.resource/items))

;; ===========================================================================
;; (a) SERVER PROJECTION — the page vector + infinite facts + merged :items
;;     ride the EXISTING projection, in ONE entry slot, ZERO new runtime code.
;; ===========================================================================

(deftest live-feed-entry-is-a-multi-page-loaded-feed
  (reg-feed!)
  (testing "PRE: the assembled live entry is a 3-page :loaded feed in ONE
            :rf.runtime/resources entry slot, with a load-more in flight"
    (let [rdb (feed-with-load-more-in-flight :app/main)
          e   (get-in rdb [state/resources-key :entries (state/key-id fkey)])]
      (is (state/infinite-entry? e) "it is an infinite entry (the :infinite? marker)")
      (is (= expected-pages (:data e)) "the ordered page vector")
      (is (= expected-params (:page-params e)) "one page-param per page (page-0 = nil)")
      (is (= expected-cursor (:next-page-param e)) "the cursor is the 4th page's param")
      (is (= expected-prev (:prev-page-param e)) "no prev mirror (page-0 declared none)")
      (is (= 3 (state/page-count e)))
      (is (= :fetching (:status e)) "the load-more left the feed :fetching")
      (is (= load-more-wid (:current-work e)) "the entry points at the in-flight load-more work")
      (is (= expected-items (merged-items* e)) "the headline merged :items, live"))))

(deftest projection-ships-page-vector-and-infinite-facts-verbatim
  (reg-feed!)
  (testing "the SSR projection rides the ordered page vector + EVERY infinite
            fact verbatim in the ONE :rf.runtime/resources :entries slot — an
            infinite feed is just a :serialize entry whose :data is a vector
            (R1; rides the existing project-resources-runtime-db, no new code)"
    (let [rdb  (feed-with-load-more-in-flight :app/main)
          proj (ssr/project-resources-runtime-db rdb)]
      (is (= #{state/resources-key} (set (keys proj)))
          "ONLY the resources subsystem is projected (the feed adds no subsystem)")
      (is (= #{:entries} (set (keys (get proj state/resources-key))))
          "only :entries rides — indexes recompute, work-ledger never rides")
      (let [es (get-in proj [state/resources-key :entries])
            we (val (first es))]
        (is (= 1 (count es)) "the whole feed is ONE entry slot (R1 — no per-page slot)")
        (is (true? (:infinite? we)) ":infinite? marker rides")
        (is (= expected-pages (:data we))
            "the ORDERED page vector rides verbatim (no page loss, order preserved)")
        (is (= expected-params (:page-params we)) ":page-params ride verbatim")
        (is (= expected-cursor (:next-page-param we)) "the cursor rides verbatim")
        (is (= expected-prev (:prev-page-param we)) "the prev mirror rides verbatim")
        (is (nil? (:page-error we)) ":page-error rides (nil here)")
        (is (= :fetching (:status we)) "the entry status rides (settled on hydrate, not here)")))))

(deftest projection-strips-current-work-and-omits-work-ledger
  (reg-feed!)
  (testing "the transient in-flight load-more pointer + the host work-ledger row
            do NOT ride the wire (they reference a host attempt that does not
            survive the round-trip) — same rule as a scalar entry"
    (let [rdb  (feed-with-load-more-in-flight :app/main)
          proj (ssr/project-resources-runtime-db rdb)
          we   (val (first (get-in proj [state/resources-key :entries])))]
      (is (not (contains? we :current-work))
          "the load-more :current-work pointer is stripped on the wire")
      (is (not (contains? proj state/work-ledger-key))
          "the :rf.runtime/work-ledger subtree (the in-flight page-3 row) never rides"))))

(deftest projected-entry-merges-items-identically-server-side
  (reg-feed!)
  (testing "a server-side read of the PROJECTED entry merges :items IDENTICALLY
            to the live entry — the headline R3 read rides the wire (the page
            vector + the registered :page->items accessor are all that the merge
            needs, and both survive the projection)"
    (let [rdb       (feed-with-load-more-in-flight :app/main)
          live      (get-in rdb [state/resources-key :entries (state/key-id fkey)])
          proj      (ssr/project-resources-runtime-db rdb)
          projected (val (first (get-in proj [state/resources-key :entries])))]
      (is (= expected-items (merged-items* live)) "live merge")
      (is (= expected-items (merged-items* projected))
          "the projected entry merges to the SAME flat ordered :items list — no page loss")
      (is (= (merged-items* live) (merged-items* projected))
          "server-side projected read is identical to the live read")
      (testing "the merge across pages preserves order + every page's items"
        (is (= (vec (mapcat :items expected-pages)) (merged-items* projected)))))))

;; ===========================================================================
;; (b) EPOCH RESTORE — the page vector + cursor/terminal? + :fetching-next?
;;     rehydrate via the EXISTING reconcile-on-restore, ZERO new runtime code.
;; ===========================================================================

(deftest restore-rehydrates-page-vector-and-cursor-intact
  (reg-feed!)
  (testing "reconcile-on-restore rehydrates the ordered page vector + cursor +
            terminal? + page-params + prev-mirror INTACT (order preserved, no
            page loss) — the durable feed rides the EXISTING restore reconcile"
    (let [snapshot (feed-with-load-more-in-flight :app/main)
          out      (ssr/reconcile-on-restore snapshot :app/main)
          e        (get-in out [state/resources-key :entries (state/key-id fkey)])]
      (is (state/infinite-entry? e) "the restored entry is still the infinite feed")
      (is (= expected-pages (:data e))
          "the ordered page vector rehydrates intact — order preserved, no page loss")
      (is (= expected-params (:page-params e)) ":page-params rehydrate intact")
      (is (= expected-cursor (:next-page-param e)) "the cursor (:next-page-param) rehydrates intact")
      (is (false? (state/terminal? (:next-page-param e)))
          "terminal? is FALSE — a 4th page exists (cursor \"c3\"), as before restore")
      (is (= expected-prev (:prev-page-param e)) "the prev mirror rehydrates intact")
      (is (= 3 (state/page-count e)) "all 3 pages survived (no page loss)")
      (is (= expected-items (merged-items* e))
          "the merged :items rehydrates to the SAME flat ordered list"))))

(deftest restore-settles-vanished-load-more-to-last-stable
  (reg-feed!)
  (testing "the load-more that was IN FLIGHT at capture vanished with the host
            attempt — restore settles the entry to its last-stable status
            (:loaded, the accumulated pages had data), clears :current-work, and
            dangles the non-terminal page-3 work-ledger row (it must not dangle
            as a live attempt against the restored feed)"
    (let [snapshot (feed-with-load-more-in-flight :app/main)
          out      (ssr/reconcile-on-restore snapshot :app/main)
          e        (get-in out [state/resources-key :entries (state/key-id fkey)])
          row      (get-in out [state/work-ledger-key (work-ledger/work-id-id load-more-wid)])]
      (is (= :loaded (:status e))
          ":fetching-with-data settles to :loaded (keep last-known-good) — never stranded :fetching")
      (is (nil? (:current-work e)) "the vanished load-more pointer is cleared")
      ;; the durable feed is UNTOUCHED by the settle — only the transient
      ;; in-flight facts are reconciled.
      (is (= expected-pages (:data e)) "the page vector is untouched by the settle")
      (is (= expected-cursor (:next-page-param e)) "the cursor is untouched by the settle")
      (testing "the non-terminal page-3 work-ledger row is dangled (suppressed)"
        (is (= :suppressed (:status row)) "the in-flight load-more row settled terminal :suppressed")
        (is (work-ledger/terminal? (:status row)) "it is now terminal")
        (is (= :dangling (get-in row [:outcome :reason])) "marked dangling")
        (is (not (contains? work-ledger/non-terminal-statuses (:status row)))
            "NO non-terminal load-more row survives the restore")))))

(deftest restore-fetching-next?-resolves-false-no-phantom-load-more
  (reg-feed!)
  (testing "ADVERSARIAL acceptance: after restore the live :rf.resource/fetching-next?
            sub resolves FALSE against the restored feed — the load-more that was
            in flight at capture does NOT dangle as a phantom fetching-next?
            (the durable feed survives; the vanished attempt does not). Driven
            through the REAL reconcile + live frame + the live sub."
    (let [fid :restore/infinite-fetching-next
          snapshot (feed-with-load-more-in-flight fid)
          ;; reconcile + install the snapshot as the live frame's runtime-db,
          ;; exactly as epoch perform-restore! does.
          reconciled (ssr/reconcile-on-restore snapshot fid)]
      (rf/reg-frame fid {:doc "restore infinite fetching-next? frame"})
      (frame/replace-runtime-db! fid reconciled)
      (let [q {:resource :feed/timeline :scope :rf.scope/global :params {:filter :recent}}]
        (testing "the durable feed reads through the live subs intact post-restore"
          (is (= expected-items @(rf/subscribe fid [:rf.resource/items q]))
              "the merged :items reads intact through the live sub")
          (is (= expected-pages @(rf/subscribe fid [:rf.resource/pages q]))
              "the ordered page vector reads intact (no page loss)")
          (is (= 3 @(rf/subscribe fid [:rf.resource/page-count q])))
          (is (true? @(rf/subscribe fid [:rf.resource/has-next-page? q]))
              "has-next-page? TRUE — a 4th page exists (cursor survived)")
          (is (nil? @(rf/subscribe fid [:rf.resource/page-error q]))))
        (testing ":fetching-next? is FALSE — no phantom load-more dangles post-restore"
          (is (false? @(rf/subscribe fid [:rf.resource/fetching-next? q]))
              "the in-flight load-more was settled — fetching-next? resolves false")
          (let [vm @(rf/subscribe fid [:rf.resource/infinite-state q])]
            (is (false? (:fetching-next? vm)) "the combined view-model agrees")
            (is (false? (:fetching? vm)) "no whole-feed refresh dangles either")
            (is (= :loaded (:status vm)) "the feed settled :loaded")
            (is (= expected-items (:items vm)) "the accumulated pages stay visible (no skeleton)")
            (is (true? (:has-data? vm))))))
      (frame/destroy-frame! fid))))

(deftest restore-with-terminal-feed-rehydrates-terminal-intact
  (reg-feed!)
  (testing "a TERMINAL feed (last page's next-cursor nil) rehydrates with
            terminal? TRUE — the single-terminal rule rides restore (no spurious
            has-next-page? on a feed that already reached the end)"
    (let [terminal-entry (-> (state/empty-infinite-entry :feed/timeline fkey)
                             (state/entry-append-page {:page p0 :page-param nil
                                                       :next-page-param-fn next-cursor
                                                       :prev-page-param-fn prev-cursor
                                                       :loaded-at 1000 :stale-at 9.0e15})
                             ;; a terminal final page (next-cursor nil)
                             (state/entry-append-page {:page (page [:z] nil) :page-param "c1"
                                                       :next-page-param-fn next-cursor
                                                       :prev-page-param-fn prev-cursor
                                                       :loaded-at 1100 :stale-at 9.0e15}))
          snapshot {state/resources-key {:entries   {(state/key-id fkey) (assoc terminal-entry :resource/key fkey)}
                                         :tag-index {} :owner-index {}}}
          out (ssr/reconcile-on-restore snapshot :app/main)
          e   (get-in out [state/resources-key :entries (state/key-id fkey)])]
      (is (= 2 (state/page-count e)) "both pages (including the terminal one) survived")
      (is (nil? (:next-page-param e)) "the terminal cursor (nil) rehydrates")
      (is (true? (state/terminal? (:next-page-param e))) "terminal? rehydrates TRUE")
      (is (= [:a :b :z] (merged-items* e)) "the merged :items spans both pages, in order"))))

(deftest restore-with-page-error-rehydrates-third-error-channel
  (reg-feed!)
  (testing "a feed carrying a :page-error (a prior load-more FAILED — the third
            error channel) rehydrates that channel intact through restore, kept
            distinct from :error / :refresh-error"
    (let [failed (-> (loaded-feed-entry)
                     (state/entry-page-failed {:error {:kind :rf.http/server :status 503}}))
          snapshot {state/resources-key {:entries   {(state/key-id fkey) (assoc failed :resource/key fkey)}
                                         :tag-index {} :owner-index {}}}
          out (ssr/reconcile-on-restore snapshot :app/main)
          e   (get-in out [state/resources-key :entries (state/key-id fkey)])]
      (is (= {:kind :rf.http/server :status 503} (:page-error e))
          "the :page-error (third channel) rehydrates intact")
      (is (nil? (:error e)) "NOT the first-load :error channel")
      (is (nil? (:refresh-error e)) "NOT the whole-feed :refresh-error channel")
      (is (= expected-pages (:data e)) "the feed is kept — a page-error never loses pages")
      (is (= :loaded (:status e)) "the feed stays :loaded (couldn't-load-more, retry)"))))

;; ===========================================================================
;; SSR-hydrate parity — the wire projection also rides hydrate-runtime-db
;; (the SSR client reconcile) intact, completing the round-trip.
;; ===========================================================================

(deftest ssr-round-trip-project-then-hydrate-rehydrates-feed-intact
  (reg-feed!)
  (testing "the full SSR round-trip: PROJECT the live feed to the wire, then
            HYDRATE the projection (the SSR client reconcile) — the page vector +
            cursor + merged :items rehydrate intact; the dangling :fetching
            settles to :loaded (last-known-good) and is NOT refetched (the SSR
            no-double-fetch win), since the accumulated data is fresh"
    (let [rdb       (feed-with-load-more-in-flight :app/main)
          projected (ssr/project-resources-runtime-db rdb)
          out       (ssr/hydrate-runtime-db projected :app/main)
          e         (get-in out [state/resources-key :entries (state/key-id fkey)])]
      (is (= expected-pages (:data e)) "the page vector survived project→hydrate intact")
      (is (= expected-cursor (:next-page-param e)) "the cursor survived the round-trip")
      (is (= expected-items (merged-items* e)) "the merged :items survived the round-trip")
      (is (= :loaded (:status e))
          "the dangling :fetching load-more settled to :loaded on hydrate (last-known-good)")
      (is (nil? (:current-work e)) "the stripped :current-work stays cleared")
      (testing "the fresh-with-data feed is NOT in the client refetch plan (no double-fetch)"
        (let [plan (->> (ssr/hydrate-refetch-plan out 5000)
                        (into {} (map (juxt :resource/key identity))))]
          (is (not (contains? plan fkey))
              "a fresh accumulated feed is not refetched on the client — the SSR win"))))))

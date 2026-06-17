(ns day8.re-frame2-xray.panels.resources-helpers-cljs-test
  "Pure-data tests for Xray's Resources tab helpers (Spec 016 §Xray and
  AI tooling).

  Dual-target naming (`.cljc` + `_cljs_test`):
    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **trace family** — `resource-trace-op?` / `op-class` / `op-label`
       over the closed `:rf.resource/*` enum + an in-namespace op not yet
       enumerated; non-family ops reject.
    2. **summarize (PRIVACY)** — type + bounded size + redaction-aware
       preview; the framework sentinels (`:rf/redacted` /
       `:rf.size/large-elided`) keep their status and render no raw
       preview; a large value is bounded with `:elided?`. Params, scopes,
       AND data go through the SAME fn.
    3. **project-registry** — registrar map → sorted rows; scope policy
       described; declaring-routes joined from the route registry;
       schemas summarized.
    4. **project-instances** — entries map → rows; derived `:stale?` /
       `:gc-eligible?`; scope/params/data summarized (never raw).
    5. **project-work-ledger** — ledger map → rows; terminal split; host
       handles structurally absent.
    6. **project-route-graph** — routes with `:resources` → graph nodes;
       blocking = SSR wait point.
    7. **lifecycle-timeline / invalidation-graph / cache-growth**.
    7c. **optimistic mutation lifecycle (EP-0019)** —
       `optimistic-lifecycle` pairs each `:rf.mutation/optimistic-applied`
       with its terminal `:reconciled` / `:rolled-back` settle by
       `:snapshot-id` (or `:pending` when unsettled);
       `optimistic-force-clobbers` projects the `:force`-clobber warnings.
    8. **lints** — global-scope audit, suspicious-global, scope-mismatch,
       orphaned-owner.
    9. **filters** — instance / work / history filter axes; bounded
       history."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-xray.panels.resources-helpers :as h]
            ;; rf2-hgy5kf — the live `:entries` / `:rf.runtime/work-ledger` maps
            ;; are keyed on the CEDN-1 byte `key-id` STRING (rf2-9e0tyq), with the
            ;; kind-preserving scoped-key VECTOR carried on the entry's
            ;; `:resource/key`. The fixtures below model that runtime shape via
            ;; `state/key-id` rather than the dead vector-keyed shape.
            [re-frame.resources.state :as state]
            [re-frame.resources.work-ledger :as work-ledger]))

;; ---- fixtures -----------------------------------------------------------

(def ^:private global-scope :rf.scope/global)
(def ^:private session-scope [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}])

(def ^:private registrations
  "A `(rf/registrations :resource)` shape — `{<id> <meta>}` where meta
  carries the registration spec under `:rf/resource` + source coords."
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
                  :scope         :rf.scope/from-caller
                  :request       (fn [_ _] {})}}})

(def ^:private routes-map
  {:route/article
   {:path "/articles/:slug"
    :resources [{:resource :article/by-slug :blocking? true
                 :params (fn [_] {}) :scope (fn [_ _] nil)}
                {:resource :comments/list :blocking? false :keep-previous? true}]}
   :route/home {:path "/"}})

;; ---- (1) trace family ---------------------------------------------------

(deftest trace-family
  (testing "enumerated ops are family members with class + label"
    (is (h/resource-trace-op? :rf.resource/fetch-started))
    (is (= :success (h/op-class :rf.resource/succeeded)))
    (is (= :failure (h/op-class :rf.resource/failed)))
    (is (= :suppression (h/op-class :rf.resource/stale-suppressed)))
    (is (= :invalidation (h/op-class :rf.resource/invalidated)))
    (is (= :gc (h/op-class :rf.resource/gc-fired)))
    (is (= :dedupe (h/op-class :rf.resource/deduped)))
    (is (= :hydration (h/op-class :rf.resource/hydrated)))
    ;; EP-0016 D3 (slice 8) — the named-scope-resolver resolution op.
    (is (h/resource-trace-op? :rf.resource/scope-resolved))
    (is (= :lifecycle (h/op-class :rf.resource/scope-resolved)))
    (is (= "scope resolved" (h/op-label :rf.resource/scope-resolved)))
    (is (= "fetch started" (h/op-label :rf.resource/fetch-started))))
  (testing "the SSR / route / revalidate ops the runtime also emits are
            enumerated (rf2-uqwbhr — align the enum with the emitted set)"
    (is (= :lifecycle (h/op-class :rf.resource/revalidate-scan)))
    (is (= :lifecycle (h/op-class :rf.resource/route-plan)))
    (is (= :hydration (h/op-class :rf.resource/hydrate-clock-skew)))
    (is (= :hydration (h/op-class :rf.resource/restored)))
    (is (= :hydration (h/op-class :rf.resource/restore-clock-skew)))
    (is (= "revalidate scan" (h/op-label :rf.resource/revalidate-scan)))
    (is (= "restored"        (h/op-label :rf.resource/restored)))
    ;; EP-0020 polling ops (gc-class — the freshness-timer family)
    (is (= :gc (h/op-class :rf.resource/poll-scheduled)))
    (is (= :gc (h/op-class :rf.resource/poll-fired)))
    (is (= "poll scheduled" (h/op-label :rf.resource/poll-scheduled)))
    (is (= "poll fired"     (h/op-label :rf.resource/poll-fired))))
  (testing "EP-0021 infinite-feed load-more family — class + label"
    (is (h/resource-trace-op? :rf.resource/load-more))
    (is (= :lifecycle (h/op-class :rf.resource/load-more)))
    (is (= :success   (h/op-class :rf.resource/page-appended)))
    (is (= :failure   (h/op-class :rf.resource/page-failed)))
    (is (= :dedupe    (h/op-class :rf.resource/load-more-skipped)))
    (is (= "load more"          (h/op-label :rf.resource/load-more)))
    (is (= "page appended"      (h/op-label :rf.resource/page-appended)))
    (is (= "page failed"        (h/op-label :rf.resource/page-failed)))
    (is (= "load more skipped"  (h/op-label :rf.resource/load-more-skipped))))
  (testing "the closed enum matches the runtime-emitted set EXACTLY — no
            extra (e.g. the never-emitted `:rf.resource/ensure` event-id or
            the folded `work-suppressed`) and none missing (rf2-uqwbhr)"
    (let [enumerated (set (keys h/trace-ops))
          ;; the authoritative runtime-emitted `:rf.resource/*` operation
          ;; set, cross-checked against implementation/resources/ emit sites
          ;; + Spec 009 §Where trace emission lives.
          emitted    #{:rf.resource/registered
                       :rf.resource/scope-resolved
                       :rf.resource/owner-attached
                       :rf.resource/cache-hit
                       :rf.resource/deduped
                       :rf.resource/fetch-started
                       :rf.resource/work-started
                       :rf.resource/work-abort-requested
                       :rf.resource/work-completed
                       :rf.resource/succeeded
                       :rf.resource/failed
                       :rf.resource/refresh-failed
                       ;; EP-0021 — the infinite-feed load-more family
                       :rf.resource/load-more
                       :rf.resource/page-appended
                       :rf.resource/page-failed
                       :rf.resource/load-more-skipped
                       :rf.resource/invalidated
                       :rf.resource/refetch-decision
                       :rf.resource/revalidate-scan
                       :rf.resource/route-plan
                       :rf.resource/owner-released
                       :rf.resource/stale-scheduled
                       :rf.resource/stale-fired
                       :rf.resource/gc-scheduled
                       :rf.resource/gc-fired
                       :rf.resource/gc-skipped
                       :rf.resource/poll-scheduled
                       :rf.resource/poll-fired
                       :rf.resource/removed
                       :rf.resource/stale-suppressed
                       :rf.resource/hydrated
                       :rf.resource/hydrate-refetch
                       :rf.resource/hydrate-clock-skew
                       :rf.resource/restored
                       :rf.resource/restore-clock-skew}]
      (is (= emitted enumerated)
          "trace-ops enum is exactly the runtime-emitted op set")
      (is (not (contains? enumerated :rf.resource/ensure))
          ":rf.resource/ensure is a dispatched event-id, NOT a trace op")
      (is (not (contains? enumerated :rf.resource/work-suppressed))
          ":rf.resource/work-suppressed was folded into stale-suppressed")))
  (testing "a dispatched resource EVENT-ID is still recognised as an
            in-namespace family member for colouring (the namespace fallback),
            but is NOT a member of the closed enum"
    (is (h/resource-trace-op? :rf.resource/ensure))
    (is (not (contains? (set (keys h/trace-ops)) :rf.resource/ensure)))
    (is (= :lifecycle (h/op-class :rf.resource/ensure))))
  (testing "an in-namespace op not yet enumerated is still a family member"
    (is (h/resource-trace-op? :rf.resource/some-future-op))
    (is (= :lifecycle (h/op-class :rf.resource/some-future-op)))
    (is (= "some-future-op" (h/op-label :rf.resource/some-future-op))))
  (testing "non-family ops reject"
    (is (not (h/resource-trace-op? :rf.event/dispatched)))
    (is (not (h/resource-trace-op? :rf.route/navigate)))
    (is (nil? (h/op-class :rf.event/dispatched)))))

;; ---- (2) summarize (PRIVACY) -------------------------------------------

(deftest summarize-privacy
  (testing "a map value summarizes to type + size + preview, never raw"
    (let [s (h/summarize {:slug "welcome"})]
      (is (= "map" (:type s)))
      (is (= 1 (:size s)))
      (is (not (:redacted? s)))
      (is (not (:large? s)))))
  (testing "scopes go through the SAME fn as data (scope carries PII)"
    (let [s (h/summarize session-scope)]
      (is (= "vector" (:type s)))
      ;; the preview is bounded text, NOT a structured leak of the raw value
      (is (string? (:preview s)))))
  (testing "the framework redaction sentinel keeps redacted status, no raw preview"
    (let [s (h/summarize :rf/redacted)]
      (is (:redacted? s))
      (is (= "[redacted]" (:preview s)))))
  (testing "the framework large-elision sentinel keeps large status"
    (let [s (h/summarize :rf.size/large-elided)]
      (is (:large? s))
      (is (= "[large — elided]" (:preview s)))))
  (testing "a large value is bounded with :elided?"
    (let [big (apply str (repeat 500 "x"))
          s   (h/summarize big {:budget 20})]
      (is (:elided? s))
      (is (<= (count (:preview s)) 21))   ; budget + the … glyph
      (is (not (:redacted? s)))))
  (testing "scoped-key-summary summarizes scope + params, plain resource-id"
    (let [s (h/scoped-key-summary [session-scope :article/by-slug {:slug "x"}])]
      (is (= :article/by-slug (:resource-id s)))
      (is (= "vector" (get-in s [:scope :type])))
      (is (= "map" (get-in s [:params :type]))))))

;; ---- (3) project-registry ----------------------------------------------

(deftest project-registry-test
  (let [rows (h/project-registry registrations routes-map)]
    (testing "one row per registered resource, sorted by id"
      (is (= 3 (count rows)))
      (is (= [:article/by-slug :dashboard/summary :me/profile]
             (mapv :resource-id rows))))
    (testing "scope policy is described; global flagged"
      (let [article (first (filter #(= :article/by-slug (:resource-id %)) rows))
            dash    (first (filter #(= :dashboard/summary (:resource-id %)) rows))]
        (is (= :global (get-in article [:scope :policy])))
        (is (get-in article [:scope :global?]))
        (is (= :from-caller (get-in dash [:scope :policy])))
        (is (not (get-in dash [:scope :global?])))))
    (testing "schemas summarized (never raw), policy/timestamps surfaced"
      (let [article (first (filter #(= :article/by-slug (:resource-id %)) rows))]
        (is (map? (:params-schema article)))   ; a summary map, not the raw schema
        (is (= 60000 (:stale-after-ms article)))
        (is (= 300000 (:gc-after-ms article)))
        (is (:tags? article))
        (is (= :rf.http/managed (get-in article [:request :transport])))
        (is (= {:file "app/articles.cljs" :line 12} (:source-coord article)))))
    (testing "declaring-routes joined from the route registry"
      (let [article (first (filter #(= :article/by-slug (:resource-id %)) rows))]
        (is (= [:route/article] (:declaring-routes article)))))))

;; ---- (3b) project-scope-resolvers (rf2-hls77w, EP-0016 D3) --------------

(def ^:private scope-resolver-registrations
  "A `(rf/registrations :resource-scope)` shape — `{<scope-id> <meta>}`
  where meta carries the canonical resolver spec under `:rf/resource-scope`
  + source coords. One declared-inputs resolver + one whole-db-sugar
  resolver (`:whole-db? true`, synthetic root-path input)."
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
    (testing "one row per registered resolver, sorted by scope-id"
      (is (= 2 (count rows)))
      (is (= [:realworld/session :realworld/tenant] (mapv :scope-id rows))))
    (testing "declared inputs surface as {:name :source :path-summary} — the
              :db source head + the rf-path summarized, NEVER the value"
      (let [session (first (filter #(= :realworld/session (:scope-id %)) rows))
            input   (first (:inputs session))]
        (is (= :username (:name input)))
        (is (= :db (:source input)))
        ;; the path is a SUMMARY map (bounded), not the raw path vector
        (is (map? (:path input)))
        (is (false? (:whole-db? session)))
        (is (= {:file "app/scopes.cljs" :line 7} (:source-coord session)))))
    (testing "whole-db sugar is flagged (the explicit-cost mark, EP-0015 disp 8)"
      (let [tenant (first (filter #(= :realworld/tenant (:scope-id %)) rows))]
        (is (true? (:whole-db? tenant)))))
    (testing "NO resolved scope value or input VALUE is rendered (PII surfaces
              only via the egress-projected :rf.resource/scope-resolved trace)"
      (let [session (first (filter #(= :realworld/session (:scope-id %)) rows))]
        ;; the row carries the declared shape, not a resolved [:rf.scope/session …]
        (is (not (contains? session :resolved-scope)))
        (is (not (contains? (first (:inputs session)) :value)))))))

;; ---- (4) project-instances ---------------------------------------------

(def ^:private now 1000000)

(defn- byte-keyed
  "rf2-9e0tyq / rf2-hgy5kf — build the LIVE-shape `:entries` map: keyed on the
  CEDN-1 byte `state/key-id` of each scoped-key VECTOR, with the
  kind-preserving vector stamped on the entry's `:resource/key` (exactly as
  `state/empty-entry` / the runtime write it). Input is the author-friendly
  `{<scoped-key-vector> <entry>}` map."
  [vector-keyed]
  (into {}
        (map (fn [[scoped-key entry]]
               [(state/key-id scoped-key) (assoc entry :resource/key scoped-key)]))
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
  (let [rows (h/project-instances entries now)]
    (testing "one row per entry; sorted by id then generation desc"
      (is (= 2 (count rows)))
      (is (= [4 2] (mapv :generation rows))))
    (testing "scope/params/data summarized — NEVER raw"
      (let [r (first rows)]
        (is (= "vector" (get-in r [:scope :type])))   ; a summary, not the raw scope
        (is (= "map" (get-in r [:params :type])))
        (is (= "map" (get-in r [:data :type])))
        (is (:has-data? r))))
    (testing "derived :stale? (invalidated OR past stale-at) — not a stored fact"
      (let [fresh (first (filter #(= 4 (:generation %)) rows))
            stale (first (filter #(= 2 (:generation %)) rows))]
        (is (not (:stale? fresh)) "loaded + stale-at in the future = fresh")
        (is (:stale? stale) "past stale-at = stale")))
    (testing "derived :gc-eligible? = no active owners"
      (let [owned (first (filter #(= 4 (:generation %)) rows))
            orphan (first (filter #(= 2 (:generation %)) rows))]
        (is (not (:gc-eligible? owned)))
        (is (:gc-eligible? orphan))
        (is (= 1 (:owner-count owned)))))))

;; ---- (5) project-work-ledger -------------------------------------------

(defn- byte-keyed-ledger
  "rf2-9e0tyq / rf2-hgy5kf — build the LIVE-shape `:rf.runtime/work-ledger`
  map: keyed on the CEDN-1 byte `work-ledger/work-id-id` of each record's
  `:work/id` VECTOR (exactly as `work-ledger/put-record` writes it). Input is
  the author-friendly seq of records (each carrying its own `:work/id`)."
  [records]
  (into {}
        (map (fn [record] [(work-ledger/work-id-id (:work/id record)) record]))
        records))

(def ^:private ledger
  (byte-keyed-ledger
    [{:work/id [:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4]
      :work/kind :resource :resource/key [session-scope :article/by-slug {:slug "welcome"}]
      :generation 4 :transport :rf.http/managed :status :running
      :owners #{[:route :route/article "nav-1"]}
      :causes [[:route-entry :route/article "nav-1"]]
      :cancellable? true :started-at 100 :deadline-at 5100}
     {:work/id [:rf.work/resource [session-scope :article/by-slug {:slug "old"}] 1]
      :work/kind :resource :resource/key [session-scope :article/by-slug {:slug "old"}]
      :generation 1 :status :completed :outcome {:ok true}}]))

(deftest project-work-ledger-test
  (let [rows (h/project-work-ledger ledger)]
    (testing "one row per work record; non-terminal (live) first"
      (is (= 2 (count rows)))
      (is (= [:running :completed] (mapv :status rows)))
      (is (= [false true] (mapv :terminal? rows))))
    (testing "the displayed :work-id is the KIND-PRESERVING :work/id VECTOR
              (rf2-hgy5kf), NOT the opaque byte map-key — read from the record"
      ;; the live ledger map IS byte-keyed (string keys); the row must still
      ;; surface the kind-preserving work-id from the record's `:work/id`.
      (is (every? string? (keys ledger)))
      (let [r (first rows)]
        (is (vector? (:work-id r)))
        (is (= [:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4]
               (:work-id r)))))
    (testing "host handles are structurally absent — only serializable facts"
      (let [r (first rows)]
        (is (= :resource (:kind r)))
        (is (:cancellable? r))
        (is (= 5100 (:deadline-at r)))
        ;; resource-key scope/params summarized
        (is (= "vector" (get-in r [:resource/key :scope :type])))
        ;; rf2-r1zjd0 / EP-0016 — SINGLE attempt identity: the row exposes
        ;; exactly :work-id (= the record's :work/id); the retired :stale-key
        ;; synonym MUST NOT be present (Spec 016 §Ledger row retention,
        ;; spec/Managed-Effects.md — one work id, no stale-key synonym).
        (is (= [:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4]
               (:work-id r)))
        (is (not (contains? r :stale-key))
            "no second work-identity synonym in the projection")))
    (testing "causes summarized (may carry data)"
      (is (vector? (:causes (first rows)))))))

;; ---- (5b) EP-0021 infinite-feed surface --------------------------------

(def ^:private infinite-entries
  (byte-keyed
    {;; a LOADED feed with 2 accumulated pages + a live cursor (more pages)
     [session-scope :feed/articles {:tag "clj"}]
     {:resource/id :feed/articles :status :loaded :infinite? true
      :data [[{:id 1}] [{:id 2}]]                  ; 2 vector pages
      :page-params [nil "cursor-1"]
      :next-page-param "cursor-2"                  ; more pages → not terminal
      :generation 3 :loaded-at (- now 1000) :stale-at (+ now 50000)
      :active-owners #{[:lease "feed"]} :tags #{[:feed "clj"]}}
     ;; a TERMINAL feed (nil cursor) carrying a load-more :page-error
     [session-scope :feed/articles {:tag "done"}]
     {:resource/id :feed/articles :status :loaded :infinite? true
      :data [[{:id 9}]]
      :page-params [nil]
      :next-page-param nil                         ; nil cursor = terminal
      :page-error {:kind :rf.http/server-error :status 500}
      :generation 2 :loaded-at (- now 500) :stale-at (+ now 50000)
      :active-owners #{[:lease "feed2"]}}}))

(deftest infinite-instance-surface-test
  (let [rows  (h/project-instances infinite-entries now)
        live  (first (filter #(= {:tag "clj"} (get-in % [:scoped-key 2])) rows))
        term  (first (filter #(= {:tag "done"} (get-in % [:scoped-key 2])) rows))]
    (testing "an :infinite? entry surfaces the feed facts (pure from the entry)"
      (is (:infinite? live))
      (is (= 2 (:page-count live)))
      (is (false? (:terminal? live)))
      (is (true? (:has-next-page? live)))
      ;; the cursor is egress-projected (summarized — a cursor can carry ids)
      (is (contains? live :cursor))
      (is (nil? (:page-error live))))
    (testing "a TERMINAL feed: nil cursor → :terminal?, no :has-next-page?"
      (is (= 1 (:page-count term)))
      (is (true? (:terminal? term)))
      (is (false? (:has-next-page? term))))
    (testing "the THIRD error channel — a load-more :page-error is surfaced
              (summarized, distinct from :error / :refresh-error)"
      (is (some? (:page-error term)))
      (is (nil? (:error term)))
      (is (nil? (:refresh-error term))))
    (testing "a NON-infinite entry carries NONE of the infinite facts"
      (let [ordinary (first (h/project-instances entries now))]
        (is (not (contains? ordinary :infinite?)))
        (is (not (contains? ordinary :page-count)))
        (is (not (contains? ordinary :cursor)))))))

(deftest infinite-work-ledger-page-index-test
  (let [ledger (byte-keyed-ledger
                 [;; a LIVE load-more (positive page index) — the :fetching-next? basis
                  {:work/id [:rf.work/resource [session-scope :feed/articles {:tag "clj"}] 4]
                   :work/kind :resource :resource/key [session-scope :feed/articles {:tag "clj"}]
                   :generation 4 :status :running :page-index 2}
                  ;; a page-0 first-load / whole-feed refetch (index 0)
                  {:work/id [:rf.work/resource [session-scope :feed/articles {:tag "new"}] 1]
                   :work/kind :resource :resource/key [session-scope :feed/articles {:tag "new"}]
                   :generation 1 :status :running :page-index 0}
                  ;; a non-infinite record — no page-index
                  {:work/id [:rf.work/resource [session-scope :article/by-slug {:slug "x"}] 1]
                   :work/kind :resource :resource/key [session-scope :article/by-slug {:slug "x"}]
                   :generation 1 :status :running}])
        rows   (h/project-work-ledger ledger)
        by-rid (fn [rid] (first (filter #(= rid (get-in % [:resource/key :resource-id])) rows)))]
    (testing "the work-row carries :page-index (the :fetching-next? durable fact)"
      (is (= 2 (:page-index (by-rid :feed/articles))) "the live LOAD-MORE (positive tail index)")
      (is (some #(= 0 (:page-index %)) rows) "a page-0 fetch records index 0")
      (is (nil? (:page-index (by-rid :article/by-slug))) "a non-infinite record has no page-index"))))

;; ---- (6) project-route-graph -------------------------------------------

(deftest project-route-graph-test
  (let [nodes (h/project-route-graph routes-map)]
    (testing "only routes with :resources appear"
      (is (= 1 (count nodes)))
      (is (= :route/article (:route-id (first nodes)))))
    (testing "blocking = SSR wait point; non-blocking split"
      (let [n (first nodes)]
        (is (= [:article/by-slug] (:blocking n)))
        (is (= [:comments/list] (:non-blocking n)))
        (is (:ssr-wait? n))))
    (testing "resource nodes record declared resolvers without invoking them"
      (let [res (first (:resources (first nodes)))]
        (is (:blocking? res))
        (is (:params-fn? res))
        (is (:scope-resolver? res))))
    (testing "the bare 1-arity stays STATIC — :live rollups are :none, no :current?"
      (let [res (first (:resources (first nodes)))]
        (is (= :none (get-in res [:live :freshness]))
            "no live inputs ⇒ the resource node's freshness is :none")
        (is (not (:current? (first nodes)))
            "no live inputs ⇒ no route is flagged active")))))

;; ---- (6b) LIVE route/resource graph join (rf2-m5u3gt) -------------------
;;
;; EP-0003 asks the route graph to surface the CURRENT route/nav-token,
;; active work, and fresh/stale state — not just static declarations. The
;; optional `live` arg joins the projected instance/work rows + the routing
;; slice onto the static plan.

(def ^:private m5-nav-token "nav-7")
(def ^:private m5-article-key [session-scope :article/by-slug {:slug "welcome"}])

(def ^:private m5-routing-slice
  {:current {:route-id :route/article :nav-token m5-nav-token
             :params {:slug "welcome"} :path "/articles/welcome"}
   :resource-blocking {m5-nav-token #{m5-article-key}}})

(deftest project-route-graph-live-test
  (let [;; a FRESH cached :article/by-slug entry (loaded, stale-at in future)
        live-entries  {m5-article-key
                       {:resource/id :article/by-slug :status :loaded
                        :data {:title "Welcome"} :generation 4
                        :loaded-at (- now 1000) :stale-at (+ now 50000)
                        :active-owners #{[:route :route/article m5-nav-token]}}}
        instance-rows (h/project-instances live-entries now)
        ;; one non-terminal work row for :comments/list (the non-blocking sibling)
        work-rows     (h/project-work-ledger
                        {[:rf.work/resource [session-scope :comments/list {}] 1]
                         {:work/id [:rf.work/resource [session-scope :comments/list {}] 1]
                          :work/kind :resource :resource/key [session-scope :comments/list {}]
                          :generation 1 :status :running}})
        nodes (h/project-route-graph
                routes-map
                {:instance-rows instance-rows
                 :work-rows     work-rows
                 :current       (h/routing-current m5-routing-slice)
                 :blocking-keys (h/routing-blocking-keys m5-routing-slice)})
        article-node (first (filter #(= :route/article (:route-id %)) nodes))]
    (testing "the active route is flagged :current? with its live nav-token"
      (is (:current? article-node))
      (is (= m5-nav-token (:nav-token article-node))))
    (testing "the live unsettled blocking wait point surfaces on the active route"
      (is (= [:article/by-slug] (:blocking-live article-node))
          ":blocking-live lists the blocking resource still in the unsettled set"))
    (testing "per-resource live freshness rollup joins the cache/work state"
      (let [article-res (first (filter #(= :article/by-slug (:resource %))
                                       (:resources article-node)))
            comments-res (first (filter #(= :comments/list (:resource %))
                                        (:resources article-node)))]
        (is (= :fresh (get-in article-res [:live :freshness]))
            "the cached, has-data, non-stale article reads :fresh")
        (is (= 1 (get-in article-res [:live :entry-count])))
        (is (get-in article-res [:live :has-data?]))
        (is (= :loading (get-in comments-res [:live :freshness]))
            "the non-blocking comments resource with active work + no cache reads :loading")
        (is (= 1 (get-in comments-res [:live :active-work])))))))

(deftest routing-slice-extractors-test
  (testing "routing-current pulls the :current slice; nil-safe"
    (is (= {:route-id :route/article :nav-token m5-nav-token
            :params {:slug "welcome"} :path "/articles/welcome"}
           (h/routing-current m5-routing-slice)))
    (is (nil? (h/routing-current nil)))
    (is (nil? (h/routing-current {}))))
  (testing "routing-blocking-keys (1-arity) flattens the per-nav-token unsettled sets; nil-safe"
    (is (= [m5-article-key] (h/routing-blocking-keys m5-routing-slice)))
    (is (= [] (h/routing-blocking-keys nil)))
    (is (= [] (h/routing-blocking-keys {}))))
  (testing "routing-blocking-keys (2-arity) reads ONLY the named nav-token bucket; nil-safe"
    (is (= [m5-article-key] (h/routing-blocking-keys m5-routing-slice m5-nav-token)))
    ;; an unknown / superseded token resolves to its own (empty) bucket
    (is (= [] (h/routing-blocking-keys m5-routing-slice "nav-stale")))
    ;; nil nav-token (no active route) ⇒ no live wait point, NOT the flatten
    (is (= [] (h/routing-blocking-keys m5-routing-slice nil)))
    (is (= [] (h/routing-blocking-keys nil m5-nav-token)))
    (is (= [] (h/routing-blocking-keys {} m5-nav-token)))))

;; ---- (6c) cross-nav-token blocking isolation (rf2-cduftx F2) ------------
;;
;; The route graph's `:blocking-live` must come from the CURRENT route's
;; nav-token bucket ONLY (Spec 024 §Route/resource graph). The bug: the
;; helper flattened ALL coexisting nav-token buckets, so an OLD token whose
;; bucket held a key sharing the current route's resource-id falsely
;; reported the active route as still blocked.

(def ^:private cduftx-current-token "nav-current")
(def ^:private cduftx-stale-token "nav-stale")
;; SAME resource-id (:article/by-slug) as the current route declares, but
;; pinned under the SUPERSEDED token's bucket — the cross-token bleed bait.
(def ^:private cduftx-stale-key
  [session-scope :article/by-slug {:slug "previous-article"}])

(def ^:private cduftx-multi-token-slice
  "A multi-token routing slice: the OLD token still has an unsettled blocking
  key for :article/by-slug; the CURRENT token's bucket is empty (the current
  route has settled). The current route must NOT be flagged blocked."
  {:current {:route-id :route/article :nav-token cduftx-current-token
             :params {:slug "now-article"} :path "/articles/now-article"}
   :resource-blocking {cduftx-stale-token   #{cduftx-stale-key}
                       cduftx-current-token #{}}})

(deftest project-route-graph-isolates-blocking-by-nav-token
  (testing "an OLD token's unsettled key for the current route's resource-id
            does NOT bleed onto the active route's :blocking-live"
    (let [nodes (h/project-route-graph
                  routes-map
                  {:instance-rows []
                   :work-rows     []
                   :current       (h/routing-current cduftx-multi-token-slice)
                   ;; the CORRECTED call: scoped to the current nav-token
                   :blocking-keys (h/routing-blocking-keys
                                    cduftx-multi-token-slice
                                    (:nav-token (h/routing-current cduftx-multi-token-slice)))})
          article-node (first (filter #(= :route/article (:route-id %)) nodes))]
      (is (:current? article-node)
          "the active route is still flagged :current?")
      (is (= cduftx-current-token (:nav-token article-node)))
      (is (= [] (:blocking-live article-node))
          "the stale token's wait point must NOT surface on the current route")))
  (testing "the bug repro: the all-token FLATTEN would have falsely flagged it"
    ;; Demonstrate the difference is real — feeding the flattened keys
    ;; (the pre-fix data path) DOES light up :blocking-live, which is the
    ;; cross-token bleed this fix removes.
    (let [nodes (h/project-route-graph
                  routes-map
                  {:instance-rows []
                   :work-rows     []
                   :current       (h/routing-current cduftx-multi-token-slice)
                   :blocking-keys (h/routing-blocking-keys cduftx-multi-token-slice)})
          article-node (first (filter #(= :route/article (:route-id %)) nodes))]
      (is (= [:article/by-slug] (:blocking-live article-node))
          "the flatten path (pre-fix) leaks the stale token's wait point —
           proving the scoped read is load-bearing")))
  (testing "single-token behaviour is unchanged: a CURRENT-token wait point
            still surfaces (rf2-m5u3gt regression intact)"
    (let [nodes (h/project-route-graph
                  routes-map
                  {:instance-rows []
                   :work-rows     []
                   :current       (h/routing-current m5-routing-slice)
                   :blocking-keys (h/routing-blocking-keys
                                    m5-routing-slice
                                    (:nav-token (h/routing-current m5-routing-slice)))})
          article-node (first (filter #(= :route/article (:route-id %)) nodes))]
      (is (= [:article/by-slug] (:blocking-live article-node))
          "the current nav-token's own unsettled blocking resource still surfaces"))))

;; ---- (7) timeline / invalidation / cache-growth ------------------------

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
    :tags {:owner [:lease :dashboard/opened "u-42"]}}])

(deftest lifecycle-timeline-test
  (let [rows (h/lifecycle-timeline trace-buffer)]
    (testing "only resource-family rows, in buffer order"
      (is (= [:rf.resource/fetch-started :rf.resource/succeeded
              :rf.resource/invalidated :rf.resource/owner-released]
             (mapv :operation rows))))
    (testing "resource-id derived; key summarized; class set"
      (let [fs (first rows)]
        (is (= :article/by-slug (:resource-id fs)))
        (is (= :lifecycle (:class fs)))
        (is (= "vector" (get-in fs [:resource/key :scope :type])))))))

(deftest invalidation-graph-test
  (let [rows (h/invalidation-graph trace-buffer)]
    (testing "one row per invalidation; scope summarized, tags + counts surfaced"
      (is (= 1 (count rows)))
      (let [r (first rows)]
        (is (= "vector" (get-in r [:scope :type])))   ; scope summarized
        (is (= [[:article "welcome"]] (:tags r)))
        (is (= 1 (:match-count r)))
        (is (= 1 (:refetched r)))
        (is (map? (:cause r)))))))                      ; cause summarized

(deftest cache-growth-test
  (let [instance-rows (h/project-instances entries now)
        work-rows     (h/project-work-ledger ledger)
        g             (h/cache-growth instance-rows work-rows)]
    (testing "aggregates per-resource entry/owned/gc counts + live work"
      (is (= 2 (:total-entries g)))
      (is (= 1 (:total-gc-eligible g)))
      (is (= 1 (:live-work g)))               ; one non-terminal ledger row
      (let [article (first (filter #(= :article/by-slug (:resource-id %))
                                   (:by-resource g)))]
        (is (= 2 (:entry-count article)))
        (is (= 1 (:owned-count article)))
        (is (= 1 (:gc-eligible article)))))))

;; ---- (7b) EP-0016 slice-8 projections ----------------------------------
;; scope-resolution timeline (D3), descriptor-level invalidation evidence
;; (D2), and the call-site :reply-to continuation dispatch (D1).

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
   ;; a mutation settled with per-target scoped invalidation descriptors
   ;; (global + session) + a fail-closed unresolved {:from-db …} + a Rider-1
   ;; populate-exempt key. This is a MIXED plan (rf2-fi6tda.3 finding 2): the
   ;; GLOBAL descriptor is default (it SPARED the populated article key — that
   ;; key rides its own :exempt-keys), while the SESSION descriptor opts into
   ;; :refetch-populated? true (it spared NOTHING — empty :exempt-keys). The
   ;; top-level :populate-exempt is the union of the two per-descriptor sets.
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
   ;; a mutation settlement with NO :invalidation facet (no :invalidates) — skipped
   {:id 13 :operation :rf.mutation/succeeded
    :tags {:mutation :realworld/noop :instance [:noop 1]}}
   ;; the call-site :reply-to continuation dispatch (phase 6)
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
    (testing "one row per :rf.resource/scope-resolved, in buffer order"
      (is (= 2 (count rows)))
      (is (= [10 11] (mapv :id rows))))
    (testing "resolver id + declared input NAMES surfaced; scope summarized"
      (let [r (first rows)]
        (is (= :realworld/session (:scope-id r)))
        (is (= [:username] (:inputs r)))
        (is (false? (:whole-db? r)))
        (is (false? (:resolved-nil? r)))
        ;; the resolved scope is PRIVACY-summarized, never raw
        (is (= "vector" (get-in r [:scope :type])))
        ;; the resolved input values are summarized too
        (is (map? (:input-values r)))))
    (testing "a nil resolution surfaces as fail-closed (:resolved-nil? true)"
      (let [r (second rows)]
        (is (true? (:resolved-nil? r)))))))

(deftest mutation-invalidation-evidence-test
  (let [rows (h/mutation-invalidation-evidence ep0016-trace-buffer)]
    (testing "only mutation settlements that carry an :invalidation facet"
      (is (= 1 (count rows)))
      (is (= 12 (:id (first rows)))))
    (testing "per-descriptor resolved scope (summarized) + descriptor count"
      (let [r (first rows)]
        (is (= :realworld/favorite-article (:mutation r)))
        (is (= 3 (:descriptor-count r)))
        (is (= 2 (count (:dispatched r))))
        ;; first descriptor is global, second is the session scope — both
        ;; resolved scopes are summarized (PRIVACY), tags are identity
        (is (= "keyword" (get-in r [:dispatched 0 :scope :type])))
        (is (= "vector"  (get-in r [:dispatched 1 :scope :type])))
        (is (false? (get-in r [:dispatched 0 :cross-scope?])))
        (is (= #{[:feed]} (set (get-in r [:dispatched 1 :tags]))))))
    (testing "fail-closed :unresolved {:from-db …} ids surface"
      (is (= [:realworld/tenant] (:unresolved (first rows)))))
    (testing "Rider-1 populate-exempt keys surface (summarized scoped keys)"
      (let [r (first rows)]
        (is (= 1 (count (:populate-exempt r))))
        (is (= :realworld/article (get-in r [:populate-exempt 0 :resource-id])))))
    ;; rf2-fi6tda.7 finding 1 — the per-descriptor :exempt-keys is the truthful
    ;; mixed-plan evidence: the consumer no longer collapses observed exemption
    ;; to the top-level union alone. The DEFAULT (global) descriptor SPARED the
    ;; populated article key; the :refetch-populated? OPT-IN (session) descriptor
    ;; SPARED nothing — each row carries its own summarized :exempt-keys.
    (testing "per-descriptor :exempt-keys surfaces (mixed refetch-populated? plan)"
      (let [r          (first rows)
            dispatched (:dispatched r)
            default-d  (first (remove :refetch-populated? dispatched))
            opt-in-d   (first (filter :refetch-populated? dispatched))]
        (testing "the DEFAULT descriptor's own :exempt-keys carries the spared key"
          (is (= 1 (count (:exempt-keys default-d))))
          (is (= :realworld/article (get-in default-d [:exempt-keys 0 :resource-id]))))
        (testing "the OPT-IN descriptor spared NOTHING (its own :exempt-keys empty)"
          (is (= [] (:exempt-keys opt-in-d))))))))

(deftest mutation-continuations-test
  (let [rows (h/mutation-continuations ep0016-trace-buffer)]
    (testing "one row per :rf.mutation/replied continuation dispatch"
      (is (= 1 (count rows)))
      (let [r (first rows)]
        (is (= :realworld/save-article (:mutation r)))
        (is (= [:editor/save "first-post"] (:instance r)))
        (is (= :ok (:status r)))
        (is (= [:editor/save-replied] (:target r)))
        (is (= [:rf.work/resource [:rf.mutation [:editor/save "first-post"]] 8]
               (:work-id r)))
        ;; the cause is summarized (may carry data)
        (is (map? (:cause r)))))))

;; ---- (7c) EP-0019 optimistic mutation lifecycle -------------------------
;; each :rf.mutation/optimistic-applied paired by :snapshot-id with its
;; terminal :reconciled / :rolled-back settle (+ the :force-clobber warning).

(def ^:private opt-scope-a [:rf.scope/session {:user-id "u-1"}])
(def ^:private opt-key-a [opt-scope-a :realworld/article {:slug "welcome"}])
(def ^:private opt-key-b [:rf.scope/global :realworld/feed {}])

(def ^:private ep0019-trace-buffer
  [;; (1) an optimistic apply that SUCCEEDED → reconciled (committed)
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
   ;; (2) an optimistic apply that FAILED → rolled back, conflict-aware
   ;;     (one key restored verbatim, one key conflicted & invalidated)
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
   ;; (3) an optimistic apply with NO terminal settle → :pending (in flight)
   {:id 24 :operation :rf.mutation/optimistic-applied
    :tags {:mutation :realworld/toggle :instance [:toggle 1]
           :work/id [:rf.work/resource [:rf.mutation [:toggle 1]] 11]
           :generation 11 :scope opt-scope-a :snapshot-id "snap-3"
           :affected-keys [opt-key-a]
           :revisions [{:resource/key opt-key-a :revision 0 :forward :seed}]
           :tag-matched-keys [] :target-unresolved []
           :cause [:mutation :realworld/toggle [:toggle 1]]}}
   ;; (4) a :force clobber WARNING (rode a :force rollback over a concurrent write)
   {:id 25 :operation :rf.warning/optimistic-force-clobber
    :tags {:mutation :realworld/rename :instance [:rename 7]
           :forced-keys [opt-key-b] :recovery :review-on-conflict
           :reason "mutation :realworld/rename rolled back with :on-conflict :force"}}])

(deftest optimistic-mutation-op?-test
  (testing "the three optimistic lifecycle ops are family members"
    (is (true? (h/optimistic-mutation-op? :rf.mutation/optimistic-applied)))
    (is (true? (h/optimistic-mutation-op? :rf.mutation/optimistic-reconciled)))
    (is (true? (h/optimistic-mutation-op? :rf.mutation/optimistic-rolled-back))))
  (testing "the force-clobber warning + non-members reject"
    (is (false? (h/optimistic-mutation-op? :rf.warning/optimistic-force-clobber)))
    (is (false? (h/optimistic-mutation-op? :rf.mutation/succeeded)))
    (is (false? (h/optimistic-mutation-op? :rf.resource/succeeded)))))

(deftest optimistic-lifecycle-test
  (let [rows (h/optimistic-lifecycle ep0019-trace-buffer)]
    (testing "one row per :rf.mutation/optimistic-applied, in apply order"
      (is (= 3 (count rows)))
      (is (= [20 22 24] (mapv :id rows))))
    (testing "a SUCCEEDED apply pairs with its reconcile by snapshot-id"
      (let [r (first rows)]
        (is (= "snap-1" (:snapshot-id r)))
        (is (= :reconciled (:outcome r)))
        (is (= 21 (:settled-id r)))
        (is (= :realworld/favorite-article (:mutation r)))
        ;; the affected + committed keys are PRIVACY-summarized scoped keys
        (is (= 1 (count (:affected-keys r))))
        (is (= :realworld/article (get-in r [:affected-keys 0 :resource-id])))
        (is (= 1 (count (:committed r))))
        (is (= :realworld/feed (get-in r [:reconciliation-refetches 0 :resource-id])))
        ;; the forward op shape rides the row (revision + :patch/:seed)
        (is (= 3 (get-in r [:forward 0 :revision])))
        (is (= :patch (get-in r [:forward 0 :forward])))
        ;; reconciled rows carry NO rollback facets
        (is (nil? (:on-conflict r)))
        (is (nil? (:dispositions r)))))
    (testing "a FAILED apply pairs with its rollback (conflict-aware per-key)"
      (let [r (second rows)]
        (is (= "snap-2" (:snapshot-id r)))
        (is (= :rolled-back (:outcome r)))
        (is (= 23 (:settled-id r)))
        (is (= :invalidate (:on-conflict r)))
        (is (= 1 (count (:restored r))))
        (is (= 1 (count (:conflicted r))))
        (is (= 1 (count (:refetched r))))
        ;; the fail-closed unresolved {:from-db …} ids ride the apply row
        (is (= [:realworld/tenant] (:target-unresolved r)))
        ;; per-key disposition: one restored verbatim, one conflicted+refetch
        (let [restored-d (first (filter :restored (:dispositions r)))
              conflict-d (first (filter :conflict (:dispositions r)))]
          (is (= :realworld/article (get-in restored-d [:resource/key :resource-id])))
          (is (false? (:conflict restored-d)))
          (is (= :realworld/feed (get-in conflict-d [:resource/key :resource-id])))
          (is (= :invalidate (:on-conflict conflict-d))))
        ;; rolled-back rows carry NO reconcile facets
        (is (nil? (:committed r)))))
    (testing "an apply with NO terminal settle stays :pending (in flight)"
      (let [r (nth rows 2)]
        (is (= "snap-3" (:snapshot-id r)))
        (is (= :pending (:outcome r)))
        (is (nil? (:settled-id r)))
        (is (nil? (:committed r)))
        (is (nil? (:on-conflict r)))))
    (testing "PRIVACY — the resolved scope + cause are summarized, never raw"
      (let [r (first rows)]
        (is (map? (:scope r)))
        (is (contains? (:scope r) :preview))
        (is (map? (:cause r)))))))

(deftest optimistic-force-clobbers-test
  (let [rows (h/optimistic-force-clobbers ep0019-trace-buffer)]
    (testing "one row per :rf.warning/optimistic-force-clobber"
      (is (= 1 (count rows)))
      (let [r (first rows)]
        (is (= 25 (:id r)))
        (is (= :realworld/rename (:mutation r)))
        (is (= :review-on-conflict (:recovery r)))
        ;; the forced (clobbered) keys are summarized scoped keys
        (is (= 1 (count (:forced-keys r))))
        (is (= :realworld/feed (get-in r [:forced-keys 0 :resource-id])))
        (is (string? (:reason r)))))))

;; ---- (8) lints ----------------------------------------------------------

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
    (testing "scope-mismatch lint flags a sub reading a different scope"
      ;; a sub reads :article/by-slug {:slug "welcome"} under GLOBAL scope
      ;; while the entry is cached under the SESSION scope → mismatch.
      (let [mismatches (h/scope-mismatch-lint
                         instance-rows
                         [{:resource-id :article/by-slug
                           :params {:slug "welcome"}
                           :scope  global-scope}])]
        (is (= 1 (count mismatches)))
        (is (= :article/by-slug (:resource-id (first mismatches))))
        ;; surfaced scopes are summarized (PRIVACY)
        (is (map? (:sub-scope (first mismatches)))))
      ;; a sub reading the SAME scope as the entry → no mismatch
      (is (empty? (h/scope-mismatch-lint
                    instance-rows
                    [{:resource-id :article/by-slug
                      :params {:slug "welcome"} :scope session-scope}]))))
    (testing "orphaned-owner lint flags an app-kind owner with no release"
      ;; add an entry pinned by an app-minted [:lease …] owner with no
      ;; owner-released event in the trace
      (let [pinned (assoc entries
                          [session-scope :dashboard/summary {:user-id "u-99"}]
                          {:resource/id :dashboard/summary :status :loaded
                           :data {} :active-owners #{[:lease :dashboard/opened "u-99"]}})
            rows   (h/project-instances pinned now)
            ;; trace has a release only for u-42, not u-99
            orphans (h/orphaned-owner-lint rows trace-buffer)]
        (is (= [[:lease :dashboard/opened "u-99"]] (mapv :owner orphans))))
      (testing "a route/machine/ssr owner is framework-released — not linted"
        (is (empty? (h/orphaned-owner-lint instance-rows trace-buffer))
            "the route-owned fresh entry is not an app-kind owner")))))

;; ---- (8b) scope-mismatch lint compares CANONICAL identities (rf2-hbq635) ----
;;
;; The lint must index + compare the RAW canonical [resource-id params] +
;; scope values, NOT the summarized display previews. Previews are pr-str
;; truncated at the 120-char budget and collapse every redacted value to the
;; same `[redacted]` sentinel, so the preview-keyed impl false-tripped /
;; missed on long-or-colliding params and on redacted scopes.

(deftest scope-mismatch-canonical-identity
  (testing "two DIFFERENT long params whose first 120 chars COINCIDE do not
            collide — the lint keys on the canonical params, not the
            truncated preview (rf2-hbq635)"
    ;; Both params share a >120-char common prefix; they differ only in the
    ;; tail, so their pr-str previews (budget 120) are identical while the
    ;; canonical values are distinct.
    (let [prefix     (apply str (repeat 200 "x"))
          params-a   {:q (str prefix "-AAA")}
          params-b   {:q (str prefix "-BBB")}
          entries*   (byte-keyed
                       {[session-scope :search/run params-a]
                        {:resource/id :search/run :status :loaded :data {}
                         :active-owners #{} :generation 1}})
          rows       (h/project-instances entries* now)]
      ;; sanity: the two params DO summarize to the same truncated preview
      (is (= (:preview (h/summarize params-a)) (:preview (h/summarize params-b)))
          "the two long params share a truncated preview (the collision the old impl tripped on)")
      ;; A sub reading params-b under a DIFFERENT scope: there is NO entry
      ;; for params-b (only params-a is cached), so canonically there is NO
      ;; mismatch — the preview-keyed impl would have FALSE-matched it to the
      ;; params-a entry because the previews collide.
      (is (empty? (h/scope-mismatch-lint
                    rows
                    [{:resource-id :search/run :params params-b :scope global-scope}]))
          "no entry exists for params-b → no mismatch (preview collision is NOT a match)")
      ;; A sub reading params-a (the actually-cached params) under a different
      ;; scope IS a real mismatch.
      (is (= 1 (count (h/scope-mismatch-lint
                        rows
                        [{:resource-id :search/run :params params-a :scope global-scope}])))
          "the real params-a entry under a different scope is a true mismatch")))

  (testing "distinct REDACTED scopes do not false-match — the lint compares
            the canonical scope, not the lossy [redacted] preview (rf2-hbq635)"
    ;; Two entries cached under DIFFERENT sensitive scopes that both
    ;; summarize to the [redacted] preview. A sub reading the SAME params
    ;; under one of those exact scopes must NOT mismatch (the scope IS in the
    ;; canonical set), while a sub under a THIRD distinct scope must mismatch.
    (let [scope-1   :rf/redacted                  ; an upstream-redacted scope
          scope-2   [:rf.scope/session {:tenant "t-1"}]
          scope-3   [:rf.scope/session {:tenant "t-9"}]
          entries*  (byte-keyed
                      {[scope-2 :doc/get {:id 1}]
                       {:resource/id :doc/get :status :loaded :data {}
                        :active-owners #{} :generation 1}})
          rows      (h/project-instances entries* now)]
      ;; A sub reading the SAME canonical scope as the entry → NO mismatch,
      ;; even though scope-2 summarizes to a redaction-aware preview.
      (is (empty? (h/scope-mismatch-lint
                    rows
                    [{:resource-id :doc/get :params {:id 1} :scope scope-2}]))
          "the sub's scope canonically EQUALS the entry scope → no false mismatch")
      ;; A sub reading a DIFFERENT scope (third tenant) → real mismatch.
      (is (= 1 (count (h/scope-mismatch-lint
                        rows
                        [{:resource-id :doc/get :params {:id 1} :scope scope-3}])))
          "a canonically-different scope is a true mismatch")
      ;; A sub reading the upstream-redacted scope while the entry is under
      ;; scope-2 → mismatch (the canonical :rf/redacted ≠ scope-2; the old
      ;; preview-keyed impl could mis-compare a [redacted] preview).
      (is (= 1 (count (h/scope-mismatch-lint
                        rows
                        [{:resource-id :doc/get :params {:id 1} :scope scope-1}])))
          "a redacted sub-scope is compared canonically, not as the [redacted] preview"))))

;; ---- (9) filters --------------------------------------------------------

(deftest filters-test
  (let [instance-rows (h/project-instances entries now)
        work-rows     (h/project-work-ledger ledger)
        timeline-rows (h/lifecycle-timeline trace-buffer)]
    (testing "instance filter axes: resource-id / status / stale? / tag / owner"
      (is (= 2 (count (h/filter-instance-rows instance-rows {:resource-id :article/by-slug}))))
      (is (= 2 (count (h/filter-instance-rows instance-rows {:status :loaded}))))
      (is (= 1 (count (h/filter-instance-rows instance-rows {:stale? true}))))
      (is (= 1 (count (h/filter-instance-rows instance-rows {:stale? false}))))
      (is (= 1 (count (h/filter-instance-rows instance-rows {:tag [:article "welcome"]}))))
      (is (= 1 (count (h/filter-instance-rows instance-rows
                        {:owner [:route :route/article "nav-1"]})))))
    (testing "select-raw-entries key-axis filter (scope/resource-id/params)
              over BYTE-KEYED entries (rf2-hgy5kf) — matches each entry's
              `:resource/key` stamp, NOT the opaque byte map-key. The old
              map-key-matching impl returned ZERO matches for live byte-keyed
              data (the byte map-key is a string, never `[scope rid params]`)."
      ;; the live `:entries` map IS byte-keyed (string keys), the exact shape
      ;; the old `scoped-key-matches?` could never match.
      (is (every? string? (keys entries)))
      (is (= 2 (count (h/select-raw-entries entries {:resource-id :article/by-slug}))))
      (is (= 1 (count (h/select-raw-entries entries {:params {:slug "welcome"}}))))
      (is (= 2 (count (h/select-raw-entries entries {:scope session-scope}))))
      ;; the selected map preserves the byte map-keys (the row identity) and
      ;; the selected entry carries the matching `:resource/key`.
      (let [sel (h/select-raw-entries entries {:params {:slug "welcome"}})]
        (is (every? string? (keys sel)))
        (is (= [session-scope :article/by-slug {:slug "welcome"}]
               (:resource/key (first (vals sel))))))
      ;; a params axis that matches NO entry is empty (no false-match on the
      ;; opaque byte key).
      (is (empty? (h/select-raw-entries entries {:params {:slug "nope"}}))))
    (testing "work filter axes incl. nav-token (matches an owner carrying it)"
      (is (= 1 (count (h/filter-work-rows work-rows {:status :running}))))
      (is (= 1 (count (h/filter-work-rows work-rows {:nav-token "nav-1"})))))
    (testing "history filter is BOUNDED by :limit"
      (is (= 4 (count (h/filter-history-rows timeline-rows {}))))
      (is (= 2 (count (h/filter-history-rows timeline-rows {:limit 2}))))
      (is (= 1 (count (h/filter-history-rows timeline-rows
                        {:resource-id :article/by-slug :limit 1})))))))

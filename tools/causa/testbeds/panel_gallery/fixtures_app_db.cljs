(ns panel-gallery.fixtures-app-db
  "Pure fixture builders for the Causa App-db tab gallery
  (rf2-sszlr — rebuild for new 6-tab Causa shape).

  The app-db-diff panel reads three slots from the Causa frame:

    - `:epoch-history`         — vector of `:rf/epoch-record` maps
    - `:selected-epoch-id`     — optional, drives 'diff this epoch'
    - `:focused-slice-path`    — optional, drives 'Show me when this
                                 changed' result

  The composite `:rf.causa/app-db-diff` projects these into the map the
  facade view destructures (`:changed-non-reserved`, `:changed-reserved`,
  `:focused-path`, `:focused-hits`, `:history-empty?`, `:target-frame`).

  rf2-e9tb0 — `:pinned-slices-store` and `:pinned-slices` were
  dropped when the pinned-watches strip was superseded by the
  segment-inspector popup.

  ## Why seed via `:rf.causa/sync-epoch-history`

  `:rf.causa/sync-epoch-history` is the canonical seed event used by
  the trace-bus integration in production. The handler `assoc`s the
  history vector into Causa's frame app-db — Story's `:rf.story/*`
  runtime slots survive untouched per `tools/story/spec/002-Runtime.md`
  §Coexistence with hosting application state. Direct app-db assoc via
  variant fixtures would wipe Story's lifecycle slots and corrupt the
  variant.

  ## Epoch-record shape

  Per `spec/004-AppDbDiff.md` an `:rf/epoch-record` is:

      {:epoch-id      <opaque>          ; stable id, ring-buffer key
       :frame         :rf/default
       :committed-at  <ms>
       :event-id      <kw>              ; first elem of trigger-event
       :trigger-event <event-vec>
       :db-before     <app-db-value>
       :db-after      <app-db-value>
       :trace-events  []}               ; per-epoch trace slice

  The diff algorithm (`app-db-diff-helpers/diff-paths`) only reads
  `:db-before` / `:db-after` and `:epoch-id` for the diff-cache key, so
  these fixtures populate just enough for the panel to render.")

;; ---- epoch-record builder ----------------------------------------------

(defn epoch-record
  "Minimal `:rf/epoch-record` for diff-render purposes. The diff
  algorithm reads `:db-before` and `:db-after`; the panel's epoch-
  selector logic reads `:epoch-id` and (for the 'Show me when this
  changed' walker) `:trigger-event`."
  [{:keys [epoch-id event db-before db-after]}]
  {:epoch-id      epoch-id
   :frame         :rf/default
   :committed-at  (* 1000 epoch-id)
   :event-id      (first event)
   :trigger-event event
   :db-before     db-before
   :db-after      db-after
   :trace-events  []})

;; ---- single-epoch buffers -----------------------------------------------

(defn empty-buffer
  "No epochs — panel renders the `[empty]` state with the
  reserved-keys scaffolding only (rf2-e9tb0 dropped the pinned-watches
  strip)."
  []
  [])

(defn single-key-change-buffer
  "One epoch whose only mutation is a single top-level key. Panel
  renders one changed-slice card."
  []
  [(epoch-record
     {:epoch-id 1
      :event    [:counter/inc]
      :db-before {:counter 5    :user {:id 7}}
      :db-after  {:counter 6    :user {:id 7}}})])

(defn five-key-changes-buffer
  "One epoch mutating five top-level keys: added, removed, modified
  scalar, modified nested map, modified vector slice."
  []
  [(epoch-record
     {:epoch-id 2
      :event    [:checkout/submit {:order-id 42}]
      :db-before {:counter   5
                  :user      {:id 7 :name "Ada"}
                  :cart      {:items [{:id :apple :qty 1}]}
                  :session   {:expires-at 1000}
                  :legacy-flag true}
      :db-after  {:counter   6
                  :user      {:id 7 :name "Ada Lovelace"}
                  :cart      {:items [{:id :apple :qty 2}
                                      {:id :pear  :qty 1}]}
                  :session   {:expires-at 2000 :renewed? true}
                  :flash     {:level :ok :text "Order placed"}}})])

(defn nested-deep-buffer
  "One epoch whose only change is six levels deep — exercises the
  path-as-pr-str sort and the structural-sharing prefix walk."
  []
  (let [path  [:tenant :acme :department :eng :team :platform :project :causa :status]
        deep  (fn [v] (assoc-in {} path v))]
    [(epoch-record
       {:epoch-id 3
        :event    [:project/update-status :active]
        :db-before (deep :idle)
        :db-after  (deep :active)})]))

(defn large-flat-buffer
  "One epoch mutating ~100 top-level keys at once — exercises the
  overflow / scroll behaviour of the changed-slices stack."
  []
  (let [keys-100 (mapv #(keyword (str "metric-" %)) (range 100))
        before   (zipmap keys-100 (range 100))
        after    (zipmap keys-100 (map inc (range 100)))]
    [(epoch-record
       {:epoch-id 4
        :event    [:dashboard/recompute-all]
        :db-before before
        :db-after  after})]))

(defn cyclic-buffer
  "An epoch whose `:db-after` carries a structurally large *but acyclic*
  nested shape — true Clojure cycles via atoms / mutable refs are not
  representable in immutable maps, and the panel's renderer can't
  legitimately receive one. The named axis exercises 'deep + wide,
  print-bounded' which is the legitimate stress."
  []
  (let [tier (fn [depth]
               (reduce (fn [m i]
                         (assoc m (keyword (str "k-" i))
                                (* depth i)))
                       {} (range 8)))
        wide (reduce (fn [m d]
                       (assoc m (keyword (str "tier-" d))
                              (tier d)))
                     {} (range 12))]
    [(epoch-record
       {:epoch-id 5
        :event    [:graph/rebuild]
        :db-before wide
        :db-after  (assoc wide :tier-6 (tier 999))})]))

(defn redacted-buffer
  "One epoch whose mutated slice contains `:rf/redacted` slots — the
  panel's value renderer must surface the marker verbatim per
  Spec 009 §Privacy."
  []
  [(epoch-record
     {:epoch-id 6
      :event    [:auth/sign-in {:email "ada@example.com"
                                :password :rf/redacted}]
      :db-before {:auth {:status :anonymous}}
      :db-after  {:auth {:status   :authenticated
                         :user     {:id 7 :name "Ada"}
                         :password :rf/redacted
                         :totp     :rf/redacted}}})])

(defn loading-buffer
  "A buffer present but with no `:selected-epoch-id` and a synthetic
  in-flight marker on the `:auth` slot — the panel renders the changed-
  slices stack with the marker visible."
  []
  [(epoch-record
     {:epoch-id 7
      :event    [:profile/fetch]
      :db-before {:profile {:status :idle}}
      :db-after  {:profile {:status :loading
                            :request-id "req-42"
                            :started-at 12345}}})])

(defn error-buffer
  "Epoch where the mutation set the `:errors` slot — fetch-errored
  state surfaced as a slice card."
  []
  [(epoch-record
     {:epoch-id 8
      :event    [:profile/fetch-error]
      :db-before {:profile {:status :loading}}
      :db-after  {:profile {:status :error
                            :error  {:type    :network
                                     :message "Connection refused"
                                     :status  500
                                     :url     "/api/profile"}}}})])

;; ---- panel-specific axes -----------------------------------------------
;;
;; Two extra axes the app-db-diff panel uniquely exercises:
;;
;;   A. Added vs modified vs removed mix in a single epoch — surfaces
;;      every op-tag (`:added` / `:modified` / `:removed`) side-by-side
;;      so the panel's op-colour ladder is visible at a glance.
;;   B. Reserved-keys group — `:rf/machines`, `:rf/route` etc. mutated
;;      in the same epoch; the panel's `[runtime]` group renders
;;      separately from the user-key slices.

(defn mixed-ops-buffer
  "One epoch demonstrating all three diff ops: an :added key, a
  :modified scalar, a :modified nested map, and a :removed key. The
  axis exercises the op-colour ladder uniformly."
  []
  [(epoch-record
     {:epoch-id 9
      :event    [:cart/transition]
      :db-before {:cart {:items [{:id :apple}]}
                  :flash {:text "old"}
                  :counter 5}
      :db-after  {:cart {:items [{:id :apple} {:id :pear}]
                         :discount-code "SAVE10"}
                  :counter 6}})])

(defn reserved-keys-buffer
  "Epoch mutating reserved app-db keys (per Spec Conventions §Reserved
  app-db keys: `:rf/machines`, `:rf/route`, `:rf/spawned`, `:rf/elision`).
  Panel routes these into the `[runtime]` group via
  `app-db-diff-helpers/partition-reserved` — the axis pins that branch."
  []
  [(epoch-record
     {:epoch-id 10
      :event    [:rf/route-change]
      :db-before {:rf/route    {:path "/home"}
                  :rf/machines {}
                  :counter 5}
      :db-after  {:rf/route    {:path "/cart" :query {:tab :items}}
                  :rf/machines {:checkout {:state :idle}}
                  :rf/spawned  {:worker/sync :alive}
                  :counter 6}})])

;; ---- tab-specific buffer builders --------------------------------------
;;
;; Per the rf2-sszlr per-tab gallery spec the App-db tab needs richer
;; variant coverage: tiny app-db, large app-db, sensitive paths, large
;; sentinels, watched-keys diff highlighting.

(defn tiny-app-db-buffer
  "Single epoch whose db-after is a deliberately tiny three-key map.
  Exercises the panel's resting render shape on a minimal app-db."
  []
  [(epoch-record
     {:epoch-id 20
      :event    [:counter/init]
      :db-before {}
      :db-after  {:counter 0
                  :user    nil
                  :ui      {:theme :light}}})])

(defn large-app-db-buffer
  "Single epoch whose db-after is a large multi-tier app-db (~500
  leaf keys across nested groups). Exercises the panel's render
  behaviour against a typical mid-session production app-db size."
  []
  (let [group   (fn [prefix n]
                  (into {} (for [i (range n)]
                             [(keyword (str prefix "-" i)) (str "val-" i)])))
        before  {:auth    {:user {:id 7 :name "Ada"}}
                 :catalog (group "sku" 200)
                 :cart    {:items [{:id :apple :qty 1}]}
                 :prefs   (group "pref" 50)
                 :session {:expires-at 1000}}
        after   (-> before
                    (assoc-in [:auth :user :last-seen] 2000)
                    (update :catalog merge (group "sku-new" 50))
                    (update-in [:cart :items] conj {:id :pear :qty 2}))]
    [(epoch-record
       {:epoch-id 21
        :event    [:bootstrap/load-all]
        :db-before before
        :db-after  after})]))

(defn sensitive-paths-buffer
  "Single epoch where multiple app-db paths carry `:rf/redacted`
  markers — across `:auth`, `:user/profile`, `:billing`. The panel's
  slice renderer surfaces each marker verbatim per Spec 009 §Privacy
  + the new spec/015-Data-Classification opt-in path marks
  (rf2-vw7f5)."
  []
  [(epoch-record
     {:epoch-id 22
      :event    [:user/sign-in {:email "ada@example.com"}]
      :db-before {:auth {:status :anonymous}}
      :db-after  {:auth          {:status :authenticated
                                  :token  :rf/redacted}
                  :user/profile  {:id     7
                                  :name   "Ada Lovelace"
                                  :ssn    :rf/redacted
                                  :totp   :rf/redacted}
                  :billing       {:card-number :rf/redacted
                                  :cvv         :rf/redacted}}})])

(defn large-sentinels-buffer
  "Single epoch where multiple app-db slices carry `:rf.size/large-
  elided` sentinels (per Spec 009 §Size elision). The panel must
  surface the sentinel without trying to expand the underlying value."
  []
  [(epoch-record
     {:epoch-id 23
      :event    [:report/download {:id 42}]
      :db-before {:report/cache {}}
      :db-after  {:report/cache {:report-42
                                 {:rf.size/large-elided
                                  {:source :payload
                                   :handle :report/payload-42
                                   :original-size 12480293
                                   :truncated-preview "{\"rows\": [{...} ...]"}}}
                  :metrics/raw {:rf.size/large-elided
                                {:source :metrics
                                 :handle :metrics/raw-2026-05-18
                                 :original-size 8920134
                                 :truncated-preview "[[1620345600, 0.42], ..."}}}})])

(defn watched-keys-buffer
  "Three epochs in series — exercises the cross-epoch 'show me when
  this changed' walker (per spec/004-AppDbDiff §Show me when this
  changed). Each epoch mutates a slice the user would plausibly
  watch (`:counter`, `:user/profile`, `:cart`)."
  []
  [(epoch-record
     {:epoch-id 30
      :event    [:counter/inc]
      :db-before {:counter 5 :user/profile {:id 7 :name "Ada"}
                  :cart {:items []}}
      :db-after  {:counter 6 :user/profile {:id 7 :name "Ada"}
                  :cart {:items []}}})
   (epoch-record
     {:epoch-id 31
      :event    [:user/edit-profile {:name "Ada Lovelace"}]
      :db-before {:counter 6 :user/profile {:id 7 :name "Ada"}
                  :cart {:items []}}
      :db-after  {:counter 6 :user/profile {:id 7 :name "Ada Lovelace"}
                  :cart {:items []}}})
   (epoch-record
     {:epoch-id 32
      :event    [:cart/add-item :apple]
      :db-before {:counter 6 :user/profile {:id 7 :name "Ada Lovelace"}
                  :cart {:items []}}
      :db-after  {:counter 6 :user/profile {:id 7 :name "Ada Lovelace"}
                  :cart {:items [{:id :apple :qty 1}]}}})])

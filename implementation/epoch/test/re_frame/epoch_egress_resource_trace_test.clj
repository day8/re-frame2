(ns re-frame.epoch-egress-resource-trace-test
  "Coverage for the OFF-BOX egress redaction of the BROADER resource/mutation
  trace family's scoped-key slots inside an epoch record's `:trace-events`
  (rf2-8x0gfa, EP-0015).

  The companion to `epoch_egress_resource_scope_test` (rf2-84l82t, which covers
  the single `:rf.resource/scope-resolved` row). The rest of the
  `:rf.resource/*` + `:rf.mutation/*` trace family copies owner-local SCOPED
  KEYS (`[scope resource-id params]`, embedding the resource's scope + params)
  into trace tags:

    - `:resource/key`  — a single scoped-key vector (`:rf.resource/cache-hit`,
      the timer rows, …);
    - `:removed` / `:matched` / `:resource/keys` / … — vectors of scoped keys
      (`:rf.mutation/succeeded`, `:rf.resource/invalidated`, …);
    - `:dispositions`  — the `:rf.mutation/optimistic-rolled-back` per-key maps.

  A generic value-path trace egress walk
  (`re-frame.epoch.tool-pair/elide-trace-events-slot` → `project-egress`) is
  structurally blind to a resolver-owned scoped key's embedded scope/params once
  copied into trace tags. The resource family owns the family-level egress
  projector (`re-frame.resources.trace-egress/project-resource-trace-egress`),
  published as the late-bound `:resources/project-resource-trace-egress` hook the
  epoch tool-pair consults from `omit-off-box-resource-trace-keys`. This test
  proves the WIRING fires end-to-end across the three slot shapes the bead names
  (`:resource/key`, `:removed`, rollback `:dispositions`) over the three
  classification arms EP-0015 names — sensitive params, large params, and a
  derived-sensitive `{:from-db}` scope — and that the trusted-local
  `:include-sensitive?` opt-in lifts the redaction (the `local-raw` boundary).

  resources is a TEST-ONLY dep here (production epoch never deps resources; the
  hook is nil-safe when absent — proven by the `epoch_egress_trace_events_test`
  suite which runs without resources)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.frame :as frame]
            ;; rf2-hbmeb §(8) — `fx/reg-fx`, the plain fn, NOT the `rf/reg-fx`
            ;; macro: see `drive-real-cascade!` for why the difference decides
            ;; whether the frame's default image can still be reprojected.
            [re-frame.fx :as fx]
            [re-frame.resources.state :as state]
            [re-frame.resources.work-ledger :as work-ledger]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]
            ;; load-bearing: publishes the :resources/* late-bind hooks,
            ;; including :resources/project-resource-trace-egress.
            [re-frame.resources]
            ;; rf2-hbmeb §(8) — the real cascade's `ensure` lowers into the
            ;; managed-HTTP transport, which fails closed with
            ;; `:rf.error/http-artefact-missing` unless this ns has published
            ;; its late-bind feature probe. Test-only; production epoch stays
            ;; http-free.
            [re-frame.http.managed]
            [re-frame.schemas]))

(def ^:private secret "topsecret-PII")
(def ^:private big-params {:blob (apply str (repeat 5000 "x"))})
(def ^:private plain-slug "welcome")
(def ^:private real-owner [:app :reader 1])

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (rf/make-frame {:id :test/rt})
                ;; a :sensitive? resource — scope + params tokenize off-box.
                (rf/reg-resource :secret/article
                  {:scope         :rf.scope/global
                   :sensitive?    true
                   :params-schema [:map [:auth-token :string]]}
                  (fn [_ _] {:request {:method :get :url "/x"}}))
                ;; a :large? resource — same redaction shape off-box.
                (rf/reg-resource :big/blob
                  {:scope         :rf.scope/global
                   :large?        true
                   :params-schema [:map [:blob :string]]}
                  (fn [_ _] {:request {:method :get :url "/y"}}))
                ;; a {:from-db} scope resource. EP-0025 (rf2-71dr8t) removed the
                ;; derived-sensitivity PROPAGATION arm, so the entry no longer
                ;; INHERITS :redact from the resolver's inputs — the OWNER must
                ;; declare :sensitive? to redact its scoped key off-box.
                (rf/reg-resource-scope :rt/session
                  {:inputs {:username [:db [:auth :user :username]]}}
                  (fn [{:keys [username]} _]
                    (when username [:rf.scope/session {:username username}])))
                (rf/reg-resource :derived/profile
                  {:scope         {:from-db :rt/session}
                   :sensitive?    true
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/z"}}))
                ;; a PLAIN resource — must ride verbatim (no over-redaction).
                (rf/reg-resource :plain/article
                  {:scope         :rf.scope/global
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/a"}}))
                ;; a PLAIN resource under a CONCRETE (non-global) scope — the
                ;; over-redaction control for the FREE `:scope` tag (rf2-1zc33).
                ;; Its scoped KEY must keep scope AND params verbatim, which is
                ;; what proves the `:scope` repair touched only the free tag.
                (rf/reg-resource :plain/profile
                  {:scope         {:from-db :rt/session}
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/b"}})))}))

(defn- contains-secret? [v]
  (boolean
    (cond
      (string? v)  (or (= v secret) (.contains ^String v "topsecret"))
      (map? v)     (or (some contains-secret? (keys v)) (some contains-secret? (vals v)))
      (coll? v)    (some contains-secret? v)
      :else        false)))

(defn- sk
  "A concrete scoped key `[scope resource-id params]` (the canonical fact
  identity the trace rows copy into their tags)."
  [scope resource-id params]
  (state/scoped-resource-key scope resource-id params))

(defn- redacted-component? [c]
  (and (map? c) (contains? c :rf/redacted)))

(defn- event [operation tags]
  {:op-type :rf.event :operation operation :tags tags})

(defn- record-with [trace-events]
  {:epoch-id      1
   :frame         :test/rt
   :committed-at  0
   :event-id      :go
   :trigger-event [:go]
   :db-before     {}
   :db-after      {}
   :outcome       :ok
   :trace-events  trace-events
   :sub-runs      []
   :renders       []
   :effects       []})

;; ---------------------------------------------------------------------------
;; (1) :resource/key — a single scoped-key slot (sensitive params)
;; ---------------------------------------------------------------------------

(deftest off-box-redacts-resource-key-slot-sensitive-params
  (testing "rf2-8x0gfa — a :rf.resource/cache-hit row's :resource/key has its
            sensitive scope + params tokenized off-box; the resource-id +
            structural tags survive; no raw secret egresses"
    (let [scoped-key (sk :rf.scope/global :secret/article {:auth-token secret})
          record     (record-with
                       [(event :rf.resource/cache-hit
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :generation 1 :owner [:app :l 1] :cause :ensure})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))
          [pscope rid pparams] (:resource/key tags)]
      (is (= :secret/article rid) "the resource-id (position 1) survives")
      (is (redacted-component? pscope) "the scope is tokenized")
      (is (redacted-component? pparams) "the params are tokenized")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the structural attribution tags ride verbatim"
        (is (= [:app :l 1] (:owner tags)))
        (is (= :ensure (:cause tags)))
        (is (= 1 (:generation tags))))
      (testing "no raw secret survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

;; ---------------------------------------------------------------------------
;; (2) :removed — a scoped-keys vector slot (large params)
;; ---------------------------------------------------------------------------

(deftest off-box-redacts-removed-keys-vector-large-params
  (testing "rf2-8x0gfa — a :rf.mutation/succeeded row's :removed vector has each
            scoped key's LARGE params tokenized off-box; resource-id survives"
    (let [k1        (sk :rf.scope/global :big/blob big-params)
          record    (record-with
                      [(event :rf.mutation/succeeded
                              {:rf.frame/id :test/rt :mutation :m/del :instance 1
                               :work/id [:rf.work/mutation :m/del 1]
                               :removed [k1]})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))
          [pscope rid pparams] (first (:removed tags))]
      (is (= :big/blob rid) "the resource-id survives")
      (is (redacted-component? pparams) "the large params are tokenized")
      (is (or (redacted-component? pscope) (= :rf.scope/global pscope))
          "the global scope projects to a stable token / rides")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the structural attribution tags ride verbatim"
        (is (= :m/del (:mutation tags)))
        (is (= 1 (:instance tags))))
      (testing "no raw 5KB blob survives"
        (is (not (re-find #"xxxxxxxxxx" (pr-str projected))))))))

;; ---------------------------------------------------------------------------
;; (3) rollback :dispositions — per-key maps (derived-sensitive {:from-db} scope)
;; ---------------------------------------------------------------------------

(deftest off-box-redacts-rollback-dispositions-owner-sensitive-scope
  (testing "rf2-8x0gfa / rf2-71dr8t — a :rf.mutation/optimistic-rolled-back row's
            :dispositions per-key maps have their OWNER-declared-sensitive scope
            + params tokenized off-box; the boolean disposition facts survive.
            EP-0025: the scope is tokenized via the owner's :sensitive? claim
            (the derived-sensitivity propagation arm was removed)"
    (let [;; :derived/profile declares :sensitive? → its scoped key is redacted
          ;; off-box (the owner boundary, NOT derived-sensitivity inheritance).
          scoped-key (sk [:rf.scope/session {:username secret}]
                         :derived/profile {:slug "me"})
          record     (record-with
                       [(event :rf.mutation/optimistic-rolled-back
                               {:rf.frame/id :test/rt :mutation :m/upd :instance 2
                                :on-conflict :invalidate
                                :dispositions [{:resource/key scoped-key
                                                :restored     true
                                                :conflict     false}]})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))
          row        (first (:dispositions tags))
          [pscope rid pparams] (:resource/key row)]
      (is (= :derived/profile rid) "the resource-id survives")
      (is (redacted-component? pscope) "the derived-sensitive scope is tokenized")
      (is (redacted-component? pparams) "the params are tokenized")
      (testing "the boolean disposition facts ride verbatim"
        (is (true? (:restored row)))
        (is (false? (:conflict row))))
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "no raw secret survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

;; ---------------------------------------------------------------------------
;; (3b) nested :patch-summary — recursive projection of nested scoped keys
;; ---------------------------------------------------------------------------

(deftest off-box-redacts-nested-patch-summary-keys
  (testing "rf2-8x0gfa — the :rf.mutation/succeeded :patch-summary nested map
            has its :removed key vector AND its :rollback per-key disposition
            maps projected recursively; no raw secret leaks through the nest"
    (let [k-rem  (sk :rf.scope/global :secret/article {:auth-token secret})
          k-roll (sk :rf.scope/global :secret/article {:auth-token (str secret "-2")})
          record (record-with
                   [(event :rf.mutation/succeeded
                           {:rf.frame/id :test/rt :mutation :m/del :instance 1
                            :patch-summary {:patched   []
                                            :populated []
                                            :removed   [k-rem]
                                            :rollback  [{:resource/key k-roll
                                                         :revision 3}]}})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))
          ps        (:patch-summary tags)
          [_ rid pparams]   (first (:removed ps))
          roll-row  (first (:rollback ps))]
      (is (= :secret/article rid) "nested :removed resource-id survives")
      (is (redacted-component? pparams) "nested :removed params tokenized")
      (is (= :secret/article (second (:resource/key roll-row)))
          "nested :rollback resource-id survives")
      (is (redacted-component? (nth (:resource/key roll-row) 2))
          "nested :rollback params tokenized")
      (is (= 3 (:revision roll-row)) "nested :rollback non-key facts ride verbatim")
      (is (true? (:sensitive? tags)) "the row is stamped sensitive (nested leak caught)")
      (is (not (contains-secret? projected)) "no raw secret survives the nest"))))

;; ---------------------------------------------------------------------------
;; (4) plain resource rides verbatim — no over-redaction
;; ---------------------------------------------------------------------------

(deftest off-box-keeps-plain-resource-key-verbatim
  (testing "rf2-8x0gfa guard — a NON-sensitive resource's scoped key rides its
            scope + params VERBATIM off-box; the row is NOT stamped sensitive"
    (let [scoped-key (sk :rf.scope/global :plain/article {:slug "welcome"})
          record     (record-with
                       [(event :rf.resource/cache-hit
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :generation 1 :cause :ensure})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))]
      (is (= scoped-key (:resource/key tags))
          "the plain scoped key rides verbatim (no over-redaction)")
      (is (not (:sensitive? tags)) "a plain row is NOT stamped sensitive"))))

;; ---------------------------------------------------------------------------
;; (5) fail-closed on an UNREGISTERED owner
;; ---------------------------------------------------------------------------

(deftest off-box-fails-closed-on-unregistered-owner
  (testing "rf2-8x0gfa — a scoped key naming an UNREGISTERED resource owner (the
            spec a value-path projector would trust is absent) FAILS CLOSED:
            its scope + params are redacted even though no :sensitive? claim
            exists to read"
    (let [scoped-key (sk :rf.scope/global :gone/article {:auth-token secret})
          record     (record-with
                       [(event :rf.resource/cache-hit
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :generation 1 :cause :ensure})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))
          [pscope rid pparams] (:resource/key tags)]
      (is (= :gone/article rid) "the resource-id survives")
      (is (redacted-component? pparams) "params redacted (fail-closed)")
      (is (true? (:sensitive? tags)) "stamped sensitive (fail-closed)")
      (is (not (contains-secret? projected)) "no raw secret egresses"))))

;; ---------------------------------------------------------------------------
;; (6) the trusted-local :include-sensitive? opt-in lifts the redaction
;; ---------------------------------------------------------------------------

(deftest trusted-local-include-sensitive-keeps-raw-keys
  (testing "rf2-8x0gfa — the trusted-local :include-sensitive? opt-in keeps the
            raw scoped key (the local-raw boundary), across :resource/key,
            :removed, and rollback :dispositions"
    (let [k-hit  (sk :rf.scope/global :secret/article {:auth-token secret})
          k-rem  (sk :rf.scope/global :big/blob big-params)
          k-disp (sk [:rf.scope/session {:username secret}]
                     :derived/profile {:slug "me"})
          record (record-with
                   [(event :rf.resource/cache-hit
                           {:rf.frame/id :test/rt :resource/key k-hit})
                    (event :rf.mutation/succeeded
                           {:rf.frame/id :test/rt :removed [k-rem]})
                    (event :rf.mutation/optimistic-rolled-back
                           {:rf.frame/id :test/rt
                            :dispositions [{:resource/key k-disp :restored true}]})])
          projected (epoch/projected-record record {:include-sensitive? true})
          [hit succ roll] (:trace-events projected)]
      (is (= k-hit (:resource/key (:tags hit)))
          "raw :resource/key rides with :include-sensitive?")
      (is (= [k-rem] (:removed (:tags succ)))
          "raw :removed vector rides with :include-sensitive?")
      (is (= k-disp (:resource/key (first (:dispositions (:tags roll)))))
          "raw rollback :dispositions key rides with :include-sensitive?"))))

;; ---------------------------------------------------------------------------
;; (7) the load-more PAGINATION CURSOR — a FREE tag, owner-classified (rf2-3tysyj)
;; ---------------------------------------------------------------------------
;;
;; The cursor (`:page-param` on `:rf.resource/load-more`, `:next-page-param` on
;; `:rf.resource/page-appended`) is an app `:next-page-param` fn over the feed
;; data, so it can carry a record id. It is NOT a scoped key, so it escapes the
;; scoped-key slots; it rides the ROW's owner classification (the sibling
;; `:resource/key`). A sensitive owner's cursor MUST tokenize; a plain owner's
;; rides verbatim (no over-redaction).

(def ^:private cursor-secret "cursor-rec-topsecret-PII-42")

(deftest off-box-redacts-load-more-cursor-sensitive-owner
  (testing "rf2-3tysyj — a :rf.resource/load-more row's :page-param cursor
            tokenizes off-box for a :sensitive? owner; the structural tags
            survive; no raw record id egresses"
    (let [scoped-key (sk :rf.scope/global :secret/article {:auth-token secret})
          record     (record-with
                       [(event :rf.resource/load-more
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :generation 2 :work/id [:rf.work/resource 2]
                                :page-param cursor-secret :page-index 1
                                :page-count 1 :owner [:app :l 1] :cause :load-more})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))]
      (is (redacted-component? (:page-param tags))
          "the cursor is tokenized to an opaque {:rf/redacted <digest>}")
      (is (not= cursor-secret (:page-param tags)) "the raw cursor does not ride")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the structural attribution tags ride verbatim"
        (is (= 1 (:page-index tags)))
        (is (= 1 (:page-count tags)))
        (is (= [:app :l 1] (:owner tags)))
        (is (= :load-more (:cause tags))))
      (testing "no raw cursor secret survives anywhere in the projected record"
        (is (not (re-find #"cursor-rec-topsecret" (pr-str projected))))))))

(deftest off-box-redacts-page-appended-next-cursor-sensitive-owner
  (testing "rf2-3tysyj — a :rf.resource/page-appended row's :next-page-param
            cursor tokenizes off-box for a :sensitive? owner"
    (let [resource-key (sk :rf.scope/global :secret/article {:auth-token secret})
          record       (record-with
                         [(event :rf.resource/page-appended
                                 {:rf.frame/id :test/rt :resource/key resource-key
                                  :work/id [:rf.work/resource 2] :generation 2
                                  :page-index 1 :page-count 2
                                  :next-page-param cursor-secret :terminal? false})])
          projected    (epoch/projected-record record)
          tags         (:tags (first (:trace-events projected)))]
      (is (redacted-component? (:next-page-param tags))
          "the next-page cursor is tokenized")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the structural attribution tags ride verbatim"
        (is (= 2 (:page-count tags)))
        (is (false? (:terminal? tags))))
      (is (not (re-find #"cursor-rec-topsecret" (pr-str projected)))
          "no raw cursor secret survives"))))

(deftest off-box-keeps-plain-feed-cursor-verbatim
  (testing "rf2-3tysyj guard — a PLAIN (non-sensitive) feed's load-more cursor
            rides VERBATIM off-box; the row is NOT stamped sensitive"
    (let [scoped-key (sk :rf.scope/global :plain/article {:slug "feed"})
          record     (record-with
                       [(event :rf.resource/load-more
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :page-param "cursor-page-2" :page-index 1
                                :page-count 1 :cause :load-more})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))]
      (is (= "cursor-page-2" (:page-param tags))
          "the plain feed's cursor rides verbatim (no over-redaction)")
      (is (not (:sensitive? tags)) "a plain row is NOT stamped sensitive"))))

(deftest trusted-local-include-sensitive-keeps-raw-cursor
  (testing "rf2-3tysyj — the trusted-local :include-sensitive? opt-in keeps the
            raw cursor (the local-raw boundary)"
    (let [scoped-key (sk :rf.scope/global :secret/article {:auth-token secret})
          record     (record-with
                       [(event :rf.resource/load-more
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :page-param cursor-secret :page-index 1})])
          projected  (epoch/projected-record record {:include-sensitive? true})
          tags       (:tags (first (:trace-events projected)))]
      (is (= cursor-secret (:page-param tags))
          "raw cursor rides with :include-sensitive?"))))

;; ---------------------------------------------------------------------------
;; (rf2-7qbxbm) :error / :page-error HTTP failure envelope — the raw server
;; response body (echoing submitted form fields) MUST NOT egress off-box raw.
;; ---------------------------------------------------------------------------

(def ^:private http-error-envelope
  "An `:rf.http/*` failure envelope as the resource/mutation FAILURE rows carry
  it under `:error` / `:page-error` — the raw server response whose `:body-text`
  echoes a submitted form field quoting a secret. This is the C2/D1 leak vector
  rf2-7qbxbm names: it is NOT a scoped key, NOT a cursor, so it fell through the
  projector's `:else` verbatim and reached the epoch/MCP off-box channel raw."
  {:status    422
   :body      {:errors {:auth-token (str "value '" secret "' is already taken")}}
   :body-text (str "{\"auth-token\":\"" secret "\"}")
   :detail    :rf.http/http-4xx})

(deftest off-box-redacts-resource-failed-error-envelope
  (testing "rf2-7qbxbm — a :rf.resource/failed first-load row's :error HTTP
            failure envelope (raw response body echoing a submitted secret) is
            tokenized off-box; the structural status tags survive; no raw secret
            egresses"
    (let [scoped-key (sk :rf.scope/global :secret/article {:auth-token secret})
          record     (record-with
                       [(event :rf.resource/failed
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :work/id [:rf.work/resource 1] :generation 1
                                :status-before :loading :status-after :error
                                :error http-error-envelope})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))]
      (is (redacted-component? (:error tags))
          "the HTTP failure envelope is tokenized off-box")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the structural status attribution survives"
        (is (= :loading (:status-before tags)))
        (is (= :error (:status-after tags)))
        (is (= :secret/article (second (:resource/key tags)))))
      (testing "NO raw secret survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

(deftest off-box-redacts-page-failed-page-error-envelope
  (testing "rf2-7qbxbm — a :rf.resource/page-failed load-more row's :page-error
            HTTP failure envelope is tokenized off-box (the third error channel)"
    (let [scoped-key (sk :rf.scope/global :secret/article {:auth-token secret})
          record     (record-with
                       [(event :rf.resource/page-failed
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :work/id [:rf.work/resource 1] :generation 2
                                :status-before :loaded :status-after :loaded
                                :page-error http-error-envelope})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))]
      (is (redacted-component? (:page-error tags))
          "the load-more failure envelope is tokenized off-box")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "NO raw secret survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

(deftest off-box-redacts-mutation-failed-error-envelope
  (testing "rf2-7qbxbm — a :rf.mutation/failed settlement row's :error HTTP
            failure envelope is tokenized off-box"
    (let [record    (record-with
                      [(event :rf.mutation/failed
                              {:rf.frame/id :test/rt :instance 7 :mutation :m/save
                               :work/id [:rf.work/mutation :m/save 7] :generation 1
                               :error http-error-envelope})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (redacted-component? (:error tags))
          "the mutation failure envelope is tokenized off-box")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the structural attribution survives"
        (is (= :m/save (:mutation tags)))
        (is (= 7 (:instance tags))))
      (testing "NO raw secret survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

(deftest off-box-fail-closed-on-unknown-map-slot
  (testing "rf2-7qbxbm structural — the flipped fail-CLOSED :else: an UNKNOWN
            map-shaped slot a future row might add WITHOUT a projector clause is
            tokenized by default, so it cannot leak app data the way :error did.
            Scalar structural facts on the SAME row still ride verbatim."
    (let [scoped-key (sk :rf.scope/global :secret/article {:auth-token secret})
          record     (record-with
                       [(event :rf.resource/failed
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :generation 5 :cause :ensure
                                ;; a hypothetical future map slot with NO clause
                                :future-detail {:hidden (str secret "-future")}})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))]
      (is (redacted-component? (:future-detail tags))
          "an unknown MAP slot is tokenized by the fail-closed default")
      (testing "scalar structural facts on the same row ride verbatim"
        (is (= 5 (:generation tags)))
        (is (= :ensure (:cause tags))))
      (testing "NO raw future secret survives"
        (is (not (contains-secret? projected)))))))

(deftest trusted-local-include-sensitive-keeps-raw-error-envelope
  (testing "rf2-7qbxbm — the trusted-local :include-sensitive? opt-in keeps the
            raw HTTP failure envelope (the local-raw boundary — the off-box
            redaction is the DEFAULT, not an unconditional strip)"
    (let [record    (record-with
                      [(event :rf.resource/failed
                              {:rf.frame/id :test/rt :status-after :error
                               :error http-error-envelope})])
          projected (epoch/projected-record record {:include-sensitive? true})
          tags      (:tags (first (:trace-events projected)))]
      (is (= http-error-envelope (:error tags))
          "the raw envelope rides with :include-sensitive?"))))

;; ---------------------------------------------------------------------------
;; (rf2-wd9im) the SHAPE-driven fail-closed default — a scoped key sitting in a
;; slot the projector's vocabulary does not NAME.
;; ---------------------------------------------------------------------------
;;
;; rf2-7qbxbm flipped the `:else` to fail CLOSED, but only over ONE value shape:
;; a MAP. A SEQUENTIAL value under an unnamed slot still fell through verbatim,
;; so `:rf.resource/route-plan`'s `:blocking` / `:identities` — EP-0037 R1/R2
;; VECTORS OF SCOPED KEYS on a row that predates the projector — egressed a
;; `:sensitive?` owner's resolved scope and canonical params RAW, while the
;; IDENTICAL keys under `:matched` tokenized. The repair reads SHAPE rather than
;; slot name, which also covers `:optimistic-keys` / `:forced-keys` /
;; `:revisions` and the scoped key EMBEDDED in every resource work-id.

(defn- route-plan-tags
  "A `:rf.resource/route-plan` row's tags in the shape `route.cljc` emits them
  (EP-0037 R1/R2): `:blocking` + `:identities` are VECTORS OF SCOPED KEYS,
  `:branch` is a vector of route ids that MUST ride verbatim, and `:removed` is
  an INT COUNT (the same slot NAME the mutation-settlement rows use for a key
  vector — the row and the projector were written against different mental
  models of `:removed`, and both must be handled)."
  [blocking identities]
  {:rf.frame/id :test/rt
   :route-id    :r/article
   :nav-token   7
   :branch      [:r/root :r/article]
   :ensured     2
   :kept        1
   :removed     1
   :blocking    blocking
   :identities  identities})

(deftest off-box-redacts-route-plan-blocking-and-identities
  (testing "rf2-wd9im — a :rf.resource/route-plan row's :blocking / :identities
            plan-membership slots are VECTORS OF SCOPED KEYS under no NAMED slot;
            a :sensitive? owner's scope + params must tokenize PER KEY, not
            egress raw"
    (let [k1        (sk :rf.scope/global :secret/article {:auth-token secret})
          k2        (sk :rf.scope/global :secret/article
                        {:auth-token (str secret "-2")})
          record    (record-with
                      [(event :rf.resource/route-plan (route-plan-tags [k1] [k1 k2]))])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))
          [bscope brid bparams] (first (:blocking tags))]
      (testing ":blocking tokenizes per key"
        (is (= :secret/article brid) "the resource-id (position 1) survives")
        (is (redacted-component? bscope) "the scope is tokenized")
        (is (redacted-component? bparams) "the canonical params are tokenized"))
      (testing ":identities tokenizes per key, preserving per-key DISTINCTNESS
                so a tool's per-key joins survive"
        (is (= 2 (count (:identities tags))))
        (is (every? #(= :secret/article (second %)) (:identities tags)))
        (is (every? #(redacted-component? (nth % 2)) (:identities tags)))
        (is (apply not= (map #(nth % 2) (:identities tags)))
            "two distinct keys keep two distinct digests"))
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the plan's structural attribution rides verbatim"
        (is (= :r/article (:route-id tags)))
        (is (= 7 (:nav-token tags)))
        (is (= [:r/root :r/article] (:branch tags))
            "a vector of ROUTE IDS is scalar-only and must NOT be tokenized")
        (is (= 2 (:ensured tags)))
        (is (= 1 (:kept tags)))
        (is (= 1 (:removed tags))
            "this row's :removed is an INT COUNT, not a key vector — it rides"))
      (testing "NO raw secret survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

(deftest unnamed-slot-projects-identically-to-named-slot
  (testing "rf2-wd9im anti-drift — the SAME scoped keys under a NAMED slot
            (:matched) and under UNNAMED slots (:blocking / :identities) must
            project IDENTICALLY. This is the property that makes the shape-driven
            default a replacement for growing the slot roster rather than a
            second, weaker projection that can drift from it."
    (let [k1        (sk :rf.scope/global :secret/article {:auth-token secret})
          k2        (sk [:rf.scope/session {:username secret}]
                        :derived/profile {:slug "me"})
          ks        [k1 k2]
          record    (record-with
                      [(event :rf.resource/route-plan
                              {:rf.frame/id :test/rt
                               :matched     ks     ; NAMED  → roster arm
                               :blocking    ks     ; UNNAMED → shape arm
                               :identities  ks})]) ; UNNAMED → shape arm
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= (:matched tags) (:blocking tags))
          ":blocking projects exactly as the NAMED :matched does")
      (is (= (:matched tags) (:identities tags))
          ":identities projects exactly as the NAMED :matched does")
      (is (every? redacted-component? (map first (:blocking tags)))
          "both keys' scopes tokenized (the derived scope included)")
      (is (not (contains-secret? projected))))))

(deftest off-box-keeps-plain-owner-plan-membership-verbatim
  (testing "rf2-wd9im guard — a PLAIN owner's :blocking / :identities ride
            VERBATIM. The shape-driven default projects through the OWNER
            classification, exactly as the named slots do, so closing the leak
            costs no over-redaction on the ordinary route plan."
    (let [k1        (sk :rf.scope/global :plain/article {:slug "welcome"})
          record    (record-with
                      [(event :rf.resource/route-plan (route-plan-tags [k1] [k1]))])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= [k1] (:blocking tags)) "a plain owner's :blocking rides verbatim")
      (is (= [k1] (:identities tags)) "a plain owner's :identities rides verbatim")
      (is (not (:sensitive? tags)) "a plain row is NOT stamped sensitive"))))

(deftest off-box-redacts-scoped-key-embedded-in-resource-work-id
  (testing "rf2-wd9im — a RESOURCE work-id is
            `[:rf.work/resource <scoped-key> <generation>]`, so the scoped key
            (and with it a sensitive owner's scope + params) is EMBEDDED one
            level down in the :work/id tag on the majority of rows in the family
            — :work-started / :fetch-started / :deduped / :succeeded / … . No
            slot roster names :work/id, and the value is a vector, so it rode the
            verbatim :else. The shape-driven default reaches it by DEPTH."
    (let [scoped-key (sk :rf.scope/global :secret/article {:auth-token secret})
          work-id    (work-ledger/resource-work-id scoped-key 3)
          record     (record-with
                       [(event :rf.resource/work-started
                               {:rf.frame/id :test/rt :resource/key scoped-key
                                :generation 3 :work/id work-id
                                :status :running :cause :ensure})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))
          [marker embedded generation] (:work/id tags)]
      (is (= :rf.work/resource marker) "the work-kind marker rides verbatim")
      (is (= 3 generation) "the generation rides verbatim")
      (is (= :secret/article (second embedded))
          "the embedded key's resource-id survives (attribution)")
      (is (redacted-component? (first embedded))
          "the embedded key's scope is tokenized")
      (is (redacted-component? (nth embedded 2))
          "the embedded key's params are tokenized")
      (is (= (:resource/key tags) embedded)
          "the embedded key projects exactly as the row's own :resource/key")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "NO raw secret survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

(deftest off-box-keeps-scalar-only-work-id-and-set-tags-verbatim
  (testing "rf2-wd9im guard — the shape default must not over-redact the
            scalar-only collections the family relies on: a MUTATION work-id
            `[:rf.work/mutation <id> <instance>]` carries no scoped key, and
            `:tags` rides as a SET whose egress KIND tools read (scoped-key
            identity is kind-sensitive, rf2-wgutc2, so the walk must not collapse
            a set / seq to a vector)"
    (let [record    (record-with
                      [(event :rf.mutation/succeeded
                              {:rf.frame/id :test/rt :mutation :m/del :instance 1
                               :work/id [:rf.work/mutation :m/del 1]
                               :tags    #{:tag/articles :tag/feed}
                               :left-stale 2})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= [:rf.work/mutation :m/del 1] (:work/id tags))
          "a mutation work-id is scalar-only and rides verbatim")
      (is (= #{:tag/articles :tag/feed} (:tags tags))
          "a SET-valued tag rides verbatim AND stays a set")
      (is (set? (:tags tags)) "the collection KIND is preserved")
      (is (= 2 (:left-stale tags)))
      (is (not (:sensitive? tags))
          "a row with no key-bearing slot is NOT stamped sensitive"))))

(deftest trusted-local-include-sensitive-keeps-raw-plan-membership
  (testing "rf2-wd9im — the trusted-local :include-sensitive? opt-in keeps the
            raw plan membership + the raw embedded work-id key (the local-raw
            boundary — the shape-driven redaction is the off-box DEFAULT, not an
            unconditional strip)"
    (let [k1        (sk :rf.scope/global :secret/article {:auth-token secret})
          work-id   (work-ledger/resource-work-id k1 3)
          record    (record-with
                      [(event :rf.resource/route-plan (route-plan-tags [k1] [k1]))
                       (event :rf.resource/work-started
                              {:rf.frame/id :test/rt :work/id work-id})])
          projected (epoch/projected-record record {:include-sensitive? true})
          [plan work] (:trace-events projected)]
      (is (= [k1] (:blocking (:tags plan))) "raw :blocking rides")
      (is (= [k1] (:identities (:tags plan))) "raw :identities rides")
      (is (= work-id (:work/id (:tags work))) "raw embedded work-id key rides"))))

;; ===========================================================================
;; (8) THE WIRING IS REACHED — driven from a REAL cascade (rf2-hbmeb)
;; ===========================================================================
;;
;; Every arm above builds its record with `record-with`. That proves the
;; PROJECTOR and it proves the epoch tool-pair's routing, but it cannot prove
;; the projector is ever REACHED from a producer — and for the whole life of
;; this suite it was not. `epoch.capture/capture-event!` buffers only
;; frame-resolvable events; the `:rf.resource/*` / `:rf.mutation/*` family
;; stamps its frame as the EVIDENCE key `:rf.frame/id` (Spec 016 / EP-0002,
;; beside `:resource/key` and `:generation`) and never stamped the canonical
;; `[:tags :frame]` routing tag Spec 009 §Frame identity on the raw event
;; designates. So a real `ensure` / `release-owner` cascade put 7 family rows
;; on the bus and 0 into the 3 epoch records it settled, and every
;; `record-with` arm above ran green over input the runtime never produced.
;;
;; That is the defect these two deftests exist to make impossible to
;; reintroduce, and they are deliberately different in kind:
;;
;;   - `real-cascade-lands-family-rows-...` is the SPECIFIC control. It reds if
;;     the family stops reaching the record for any reason, and it is the arm
;;     that satisfies rf2-hbmeb's acceptance criterion — a real record, a real
;;     `projected-record`, a `:sensitive?` owner redacted beside a plain one
;;     verbatim.
;;   - `real-cascade-emits-no-frameless-correlated-row` is the GENERAL one, and
;;     it is the assertion whose absence was the actual defect. It fixes no
;;     vocabulary and names no family: it says every row a cascade emits INTO a
;;     run carries the one frame path every reader resolves on. A future family
;;     that spells its frame some third way reds here on the day it lands,
;;     rather than being discovered a release later by someone measuring the
;;     bus against the record by hand.

(defn- family-row?
  "Whether `ev` is a resource/mutation-family trace row — the same namespace
  test the epoch tool-pair's `resource-family-op?` makes when routing a row to
  the family projector."
  [ev]
  (boolean (some-> (:operation ev) namespace #{"rf.resource" "rf.mutation"})))

(defn- drive-real-cascade!
  "Drive a REAL resource cascade against `:test/rt` and return every trace row
  it put on the bus, in emit order.

  Two `ensure`s under one shared owner — the `:sensitive?` resource carrying
  the secret in its params, the PLAIN one beside it as the over-redaction
  control — then a `release-owner`. `fx/reg-fx` (the plain fn, NOT the
  `rf/reg-fx` macro) overrides managed-HTTP with a no-op so `ensure` writes its
  `:loading` entry and emits its lifecycle rows without a request leaving the
  box; the macro would stamp this ns as a second provenance under one fx id and
  the frame's next default-image reprojection would die on
  `:rf.error/image-duplicate-id`."
  []
  (fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (let [rows (atom [])
        k    ::real-cascade-recorder]
    (trace-tooling/register-listener! k (fn [ev] (swap! rows conj ev)))
    (try
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :secret/article
                          :params   {:auth-token secret}
                          :owner    real-owner}]
                        {:frame :test/rt})
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :plain/article
                          :params   {:slug plain-slug}
                          :owner    real-owner}]
                        {:frame :test/rt})
      (rf/dispatch-sync [:rf.resource/release-owner {:owner real-owner}]
                        {:frame :test/rt})
      (finally (trace-tooling/unregister-listener! k)))
    @rows))

(defn- settled-family-rows
  "Every resource/mutation row across the `:trace-events` of every epoch record
  the frame has settled — what an Xray / MCP consumer reading `watch-epochs`
  actually sees."
  [frame-id]
  (filterv family-row? (mapcat :trace-events (rf/epoch-history frame-id))))

(deftest real-cascade-lands-family-rows-in-the-settled-epoch-record
  (testing "rf2-hbmeb — the rows a REAL `ensure` / `release-owner` cascade emits
            reach the settling epoch record's `:trace-events`, and
            `projected-record` over THAT record (not a hand-built one) redacts a
            `:sensitive?` owner's scope + params while a plain owner's ride
            verbatim. Before the capture-seam fix the bus carried 7 family rows
            and the 3 settled records carried 0, so every `record-with` arm in
            this file proved a projector nothing reached."
    (let [bus-rows    (drive-real-cascade!)
          bus-family  (filterv family-row? bus-rows)
          rec-family  (settled-family-rows :test/rt)]

      (testing "FIXTURE — the cascade really emitted family rows carrying the
                real secret, so the assertions below are not passing over an
                empty set"
        (is (seq bus-family) "the cascade put family rows on the bus")
        (is (contains-secret? bus-family)
            "and they carry the raw secret — the projector's input is the
             producer's own output, not an invented tag map"))

      (testing "ACCEPTANCE — nothing the cascade emitted into a run is dropped
                on the way to the record"
        (is (= (count bus-family) (count rec-family))
            "every family row on the bus reached a settled record's
             :trace-events — this is the count that read 7 vs 0")
        (is (= (frequencies (map :operation bus-family))
               (frequencies (map :operation rec-family)))
            "and row-for-row by operation, so a partial arrival cannot pass"))

      (testing "the epoch-side family projector runs over a REAL record"
        ;; Every record the cascade settled, projected — the epoch stream an
        ;; off-box consumer reads through `watch-epochs`. Across records
        ;; because one dequeued event is one record: the sensitive `ensure`,
        ;; the plain `ensure` and the `release-owner` each settle their own,
        ;; and the two-sided control needs both owners.
        (let [proj-rows (->> (rf/epoch-history :test/rt)
                             (map epoch/projected-record)
                             (mapcat :trace-events)
                             (filterv family-row?))
              sens      (->> proj-rows
                             (filter #(= :secret/article
                                         (second (:resource/key (:tags %)))))
                             first
                             :tags)
              plain     (->> proj-rows
                             (filter #(= :plain/article
                                         (second (:resource/key (:tags %)))))
                             first
                             :tags)]
          (is (= (count rec-family) (count proj-rows))
              "the settled records carry family rows of their own, and
               projection neither drops nor invents one")
          (testing "the :sensitive? owner's scoped key tokenizes"
            (is (some? sens) "a sensitive-owner row is present in the record")
            (is (= :secret/article (second (:resource/key sens)))
                "its resource-id survives for attribution")
            (is (redacted-component? (first (:resource/key sens)))
                "its resolved scope is tokenized")
            (is (redacted-component? (nth (:resource/key sens) 2))
                "its canonical params are tokenized")
            (is (true? (:sensitive? sens)) "the row is stamped :sensitive?"))
          (testing "and the PLAIN owner's rides VERBATIM in the same record —
                    over-redaction fails as loudly as leaking"
            (is (some? plain) "a plain-owner row is present in the record")
            (is (= [:rf.scope/global :plain/article {:slug plain-slug}]
                   (:resource/key plain))
                "scope and params intact")
            (is (not (:sensitive? plain)) "a plain row is NOT stamped sensitive"))
          (testing "no raw secret egresses from the projected REAL record's
                    family rows"
            (is (not (contains-secret? proj-rows)))))))))

(deftest real-cascade-emits-no-frameless-correlated-row
  (testing "rf2-hbmeb, the general form — EVERY trace row emitted inside a run
            (one carrying a `:rf.trace/dispatch-id`) carries frame identity at
            `[:tags :frame]`, the single canonical raw-event frame path of Spec
            009 §Frame identity on the raw event.

            This is the assertion whose absence WAS the defect. Three
            independent consumers resolve a row's frame — the per-frame trace
            ring, the frame trace-disable policy gate, and epoch capture — and
            a row that reaches none of them consistently is silently absent
            from whichever one lacks a fallback. It named no family on purpose:
            the resource family is simply the one that was wrong, and the next
            one to spell its frame a third way reds here."
    (let [rows        (drive-real-cascade!)
          correlated  (filterv #(some? (:rf.trace/dispatch-id (:tags %))) rows)
          frameless   (remove #(some? (:frame (:tags %))) correlated)]
      (is (seq correlated)
          "FIXTURE — the cascade emitted correlated rows to check")
      (is (empty? frameless)
          (str "every correlated row must carry [:tags :frame]; frameless ops: "
               (pr-str (frequencies (map :operation frameless))))))))

;; ===========================================================================
;; (rf2-1zc33) the FREE `:scope` tag on the rows the sibling
;; `:rf.resource/scope-resolved` projector never touches — rostered below.
;; ===========================================================================
;;
;; `trace-egress/sibling-owned-slot` passed `:scope` through VERBATIM, justified
;; by a docstring claiming the sibling
;; `scope-registry/project-scope-resolved-egress` had already classified it
;; upstream. The epoch tool-pair applies that sibling under
;; `(= :rf.resource/scope-resolved (:operation ev))` — ONE operation — while the
;; family projector that consults `sibling-owned-slot` runs on EVERY
;; `:rf.resource/*` / `:rf.mutation/*` / `:rf.warning/resource-*` row
;; (`resource-family-op?` is operation-agnostic). So on every OTHER row type
;; that stamps one, the resolved concrete scope — `[:rf.scope/session
;; {:username …}]`, tier keyword plus IDENTITY MAP — was classified by NOBODY
;; and egressed raw. ONE OPERATION PER LINE, deliberately: this roster used to
;; pack the two `:rf.mutation/*` rows onto a shared line, and every prose count
;; taken off it read the LINES rather than the operations (rf2-ruiga).
;;
;;   :rf.resource/invalidated                       (events.cljc:1811)
;;   :rf.resource/refetch-decision                  (events.cljc:1828)
;;   :rf.resource/removed                           (events.cljc:2069)
;;   :rf.warning/resource-clear-scope-unresolved    (events.cljc:2138)
;;   :rf.mutation/started                           (mutation_events.cljc:1403)
;;   :rf.mutation/optimistic-applied                (mutation_events.cljc:1390)
;;
;; `:rf.resource/refetch-decision` is the sharpest case: it carries the SAME
;; scope TWICE — correctly redacted inside `:resource/key`, raw under `:scope`.
;; The row redacted and leaked one value side by side.
;;
;; The repair drops `:scope` from `sibling-owned-slot` and lets the SHAPE-driven
;; fail-closed default own it (rf2-wd9im). Per shape:
;;
;;   `:rf.scope/global`                  scalar  → verbatim (no over-redaction)
;;   `[:rf.scope/session {:username …}]` 2-vec   → walked: TIER keyword verbatim
;;                                                 (attribution), identity MAP
;;                                                 tokenized (distinct scopes →
;;                                                 distinct digests)
;;   on `:rf.resource/scope-resolved`            → the sibling has already
;;                                                 substituted; unchanged.

(def ^:private session-scope
  "A resolved CONCRETE scope as the rows rostered above carry it — the tier
  keyword plus the resolver's IDENTITY MAP. The map is what EP-0025 made
  unconditionally fail-closed on the scope-resolved row, and what leaked on
  every rostered row."
  [:rf.scope/session {:username secret}])

(def ^:private other-session-scope
  "A SECOND distinct concrete scope — the per-scope-join control (distinct scopes
  must keep distinct digests)."
  [:rf.scope/session {:username (str secret "-2")}])

(def ^:private plain-session-scope
  "A concrete scope carrying NO secret, used with the PLAIN `:plain/profile`
  owner as the over-redaction control."
  [:rf.scope/session {:username "alice"}])

(defn- free-scope
  "The `:scope` tag as it egresses from a projected single-row record."
  [record]
  (:scope (:tags (first (:trace-events (epoch/projected-record record))))))

;; ---------------------------------------------------------------------------
;; (1) :rf.resource/invalidated — events.cljc:1811
;; ---------------------------------------------------------------------------

(deftest off-box-redacts-invalidated-free-scope-tag
  (testing "rf2-1zc33 — the invalidation summary row's FREE :scope tag carries
            the resolved concrete scope. The sibling projector runs on
            :rf.resource/scope-resolved ONLY, so nobody classified this one and
            the identity map egressed raw. It must now tokenize while the TIER
            keyword rides (a tool still shows \"session scope\")."
    (let [k1     (sk session-scope :derived/profile {:slug "me"})
          record (record-with
                   [(event :rf.resource/invalidated
                           {:rf.frame/id  :test/rt
                            :scope        session-scope
                            :tags         #{:tag/profile}
                            :cause        [:mutation :m/save 1]
                            :cross-scope? false
                            :matched      [k1]
                            :refetched    1
                            :left-stale   0
                            :exempt       []})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))
          [tier identity-map] (:scope tags)]
      (is (= :rf.scope/session tier)
          "the scope TIER keyword rides verbatim — attribution preserved")
      (is (redacted-component? identity-map)
          "the resolver's IDENTITY MAP is tokenized")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the structural attribution rides verbatim"
        (is (= #{:tag/profile} (:tags tags)))
        (is (false? (:cross-scope? tags)))
        (is (= 1 (:refetched tags)))
        (is (= 0 (:left-stale tags))))
      (testing "NO raw identity survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

;; ---------------------------------------------------------------------------
;; (2) :rf.resource/refetch-decision — events.cljc:1828. THE SAME SCOPE TWICE.
;; ---------------------------------------------------------------------------

(deftest off-box-refetch-decision-scope-carriers-agree
  (testing "rf2-1zc33 — the per-key refetch decision row emits
            `:scope (first resource-key)`, so ONE value rides TWO carriers on
            ONE row: inside `:resource/key` (owner-classified, correctly
            redacted) and under the free `:scope` tag (classified by nobody,
            raw). The two carriers must AGREE — neither may leak the identity."
    (let [k1        (sk session-scope :derived/profile {:slug "me"})
          record    (record-with
                      [(event :rf.resource/refetch-decision
                              {:rf.frame/id  :test/rt
                               :resource/key k1
                               :scope        (first k1)
                               :active?      true
                               :decision     :refetch
                               :tags         #{:tag/profile}
                               :cause        [:mutation :m/save 1]})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (testing "carrier 1 — the owner-classified scoped key (already correct)"
        (is (redacted-component? (first (:resource/key tags)))
            "the key's scope component is tokenized whole by owner classification")
        (is (= :derived/profile (second (:resource/key tags)))
            "the resource-id survives"))
      (testing "carrier 2 — the free :scope tag (the leak)"
        (is (= :rf.scope/session (first (:scope tags)))
            "the tier keyword rides verbatim")
        (is (redacted-component? (second (:scope tags)))
            "the identity map is tokenized"))
      (testing "THE AGREEMENT — the row can no longer redact and leak the same
                value side by side"
        (is (not (contains-secret? (:resource/key tags)))
            "carrier 1 does not leak")
        (is (not (contains-secret? (:scope tags)))
            "carrier 2 does not leak either"))
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the decision attribution rides verbatim"
        (is (true? (:active? tags)))
        (is (= :refetch (:decision tags))))
      (is (not (contains-secret? projected))
          "NO raw identity survives anywhere in the projected record"))))

;; ---------------------------------------------------------------------------
;; (3) :rf.resource/removed — events.cljc:2069
;; ---------------------------------------------------------------------------

(deftest off-box-redacts-removed-free-scope-tag
  (testing "rf2-1zc33 — the clear-scope teardown row's FREE :scope tag is the
            very scope that was torn down; its identity map must tokenize"
    (let [k1        (sk session-scope :derived/profile {:slug "me"})
          record    (record-with
                      [(event :rf.resource/removed
                              {:rf.frame/id  :test/rt
                               :scope        session-scope
                               :cause        [:logout]
                               :removed      [k1]
                               :reason       :clear-scope
                               :aborted      []
                               :completed-at 1234})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))
          [tier identity-map] (:scope tags)]
      (is (= :rf.scope/session tier) "the tier keyword rides verbatim")
      (is (redacted-component? identity-map) "the identity map is tokenized")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the teardown attribution rides verbatim"
        (is (= :clear-scope (:reason tags)))
        (is (= 1234 (:completed-at tags))))
      (is (not (contains-secret? projected))
          "NO raw identity survives anywhere in the projected record"))))

;; ---------------------------------------------------------------------------
;; (4) :rf.warning/resource-clear-scope-unresolved — events.cljc:2138
;; ---------------------------------------------------------------------------

(deftest off-box-redacts-clear-scope-unresolved-warning-scope
  (testing "rf2-1zc33 — the clear-scope fail-closed diagnostic rides the
            `:rf.warning/resource-*` namespace, so `resource-family-op?` routes
            it to the family projector while the sibling never sees it. Its
            `:scope` is the UNRESOLVED `{:from-db …}` reference (the emit is
            guarded on `from-db?`), so the shape default's MAP arm tokenizes it
            whole — and the resolver-id attribution survives verbatim on the
            row's sibling `:from-db` scalar tag."
    (let [record    (record-with
                      [(event :rf.warning/resource-clear-scope-unresolved
                              {:rf.frame/id :test/rt
                               :scope       {:from-db :rt/session}
                               :from-db     :rt/session
                               :cause       [:logout]
                               :recovery    :fix-scope})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (redacted-component? (:scope tags))
          "the reference map is tokenized by the shape default's map arm")
      (is (= :rt/session (:from-db tags))
          "the RESOLVER ID survives verbatim on the sibling tag — full
           attribution, nothing a reader needs is lost")
      (is (= :fix-scope (:recovery tags)) "the recovery hint rides verbatim")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?"))))

;; ---------------------------------------------------------------------------
;; (5) :rf.mutation/started + :rf.mutation/optimistic-applied
;;     — mutation_events.cljc:1390, 1403
;; ---------------------------------------------------------------------------

(deftest off-box-redacts-mutation-started-free-scope-tag
  (testing "rf2-1zc33 — the mutation lifecycle rows stamp the mutation's
            resolved default scope under a FREE :scope tag; the identity map
            must tokenize on BOTH of them"
    (let [k1        (sk session-scope :derived/profile {:slug "me"})
          record    (record-with
                      [(event :rf.mutation/started
                              {:rf.frame/id       :test/rt
                               :mutation          :m/save
                               :instance          7
                               :work/id           [:rf.work/mutation :m/save 7]
                               :generation        1
                               :scope             session-scope
                               :cause             [:ui :save]
                               :invalidate-timing :after-request})
                       (event :rf.mutation/optimistic-applied
                              {:rf.frame/id       :test/rt
                               :mutation          :m/save
                               :instance          7
                               :work/id           [:rf.work/mutation :m/save 7]
                               :generation        1
                               :scope             session-scope
                               :snapshot-id       3
                               :affected-keys     [k1]
                               :tag-matched-keys  []
                               :target-unresolved []
                               :cause             [:mutation :m/save 7]})])
          projected      (epoch/projected-record record)
          [started opt]  (:trace-events projected)]
      (testing ":rf.mutation/started"
        (let [tags (:tags started)
              [tier identity-map] (:scope tags)]
          (is (= :rf.scope/session tier) "the tier keyword rides verbatim")
          (is (redacted-component? identity-map) "the identity map is tokenized")
          (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
          (testing "the mutation attribution rides verbatim"
            (is (= :m/save (:mutation tags)))
            (is (= 7 (:instance tags)))
            (is (= [:rf.work/mutation :m/save 7] (:work/id tags)))
            (is (= :after-request (:invalidate-timing tags))))))
      (testing ":rf.mutation/optimistic-applied"
        (let [tags (:tags opt)
              [tier identity-map] (:scope tags)]
          (is (= :rf.scope/session tier) "the tier keyword rides verbatim")
          (is (redacted-component? identity-map) "the identity map is tokenized")
          (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
          (is (= 3 (:snapshot-id tags)) "the snapshot id rides verbatim")))
      (is (not (contains-secret? projected))
          "NO raw identity survives anywhere in the projected record"))))

;; ---------------------------------------------------------------------------
;; (6) THE TWO-SIDED CONTROL — over-redaction must fail as loudly as leaking
;; ---------------------------------------------------------------------------

(deftest off-box-keeps-global-scope-and-plain-owner-key-verbatim
  (testing "rf2-1zc33 guard — `:rf.scope/global` is a SCALAR, so the shape
            default rides it verbatim and does NOT stamp the row sensitive; and
            a PLAIN owner's `:resource/key` beside it keeps scope AND params.
            This is the side that proves the repair costs no attribution on the
            ordinary global-scoped row."
    (let [k1        (sk :rf.scope/global :plain/article {:slug plain-slug})
          record    (record-with
                      [(event :rf.resource/refetch-decision
                              {:rf.frame/id  :test/rt
                               :resource/key k1
                               :scope        (first k1)
                               :active?      true
                               :decision     :refetch
                               :tags         #{:tag/articles}})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= :rf.scope/global (:scope tags))
          "a global scope rides VERBATIM — no over-redaction")
      (is (= k1 (:resource/key tags))
          "the plain owner's scoped key rides verbatim, scope and params intact")
      (is (not (:sensitive? tags))
          "a plain global-scoped row is NOT stamped sensitive"))))

(deftest off-box-plain-owner-free-scope-map-fails-closed-key-rides-verbatim
  (testing "rf2-1zc33 — the deliberate, documented asymmetry. A free `:scope`
            tag on `:rf.resource/invalidated` / `removed` names NO single owner
            (an invalidation sweep spans owners, and a clear-scope teardown
            outlives them), so there is nothing to read a `:sensitive?` claim
            from and the shape default's MAP arm fails closed unconditionally.
            The TIER survives, and — the point of this test — the PLAIN owner's
            own `:resource/key` on the same row still rides fully verbatim, so
            the repair is confined to the free tag and does not spill into
            owner classification."
    (let [k1        (sk plain-session-scope :plain/profile {:slug "me"})
          record    (record-with
                      [(event :rf.resource/invalidated
                              {:rf.frame/id  :test/rt
                               :scope        plain-session-scope
                               :resource/key k1
                               :tags         #{:tag/profile}
                               :matched      [k1]
                               :refetched    1})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= :rf.scope/session (first (:scope tags)))
          "the tier keyword rides verbatim")
      (is (redacted-component? (second (:scope tags)))
          "the free tag's identity map fails closed even for a plain owner")
      (is (= k1 (:resource/key tags))
          "the PLAIN owner's scoped key still rides verbatim — scope AND params")
      (is (= [k1] (:matched tags))
          "and so does the plain owner's :matched key vector"))))

(deftest free-scope-tokens-stay-distinct-per-scope
  (testing "rf2-1zc33 — distinct scopes must keep DISTINCT digests, so an Xray
            invalidation graph can still group and join by scope after the
            identity map tokenizes"
    (let [r1 (record-with [(event :rf.resource/invalidated
                                  {:rf.frame/id :test/rt :scope session-scope})])
          r2 (record-with [(event :rf.resource/invalidated
                                  {:rf.frame/id :test/rt :scope other-session-scope})])
          s1 (free-scope r1)
          s2 (free-scope r2)]
      (is (= :rf.scope/session (first s1) (first s2))
          "both keep the tier keyword")
      (is (redacted-component? (second s1)))
      (is (redacted-component? (second s2)))
      (is (not= (second s1) (second s2))
          "two distinct scopes keep two distinct digests"))))

;; ---------------------------------------------------------------------------
;; (7) the SIBLING still owns its own row — nothing changes on scope-resolved
;; ---------------------------------------------------------------------------

(deftest scope-resolved-row-scope-still-owned-by-the-sibling
  (testing "rf2-1zc33 — on `:rf.resource/scope-resolved` the sibling projector
            has ALREADY substituted the `:rf/redacted` sentinel (a bare KEYWORD,
            not a `{:rf/redacted <digest>}` map) before the family projector
            runs. The sentinel is a scalar, so the shape default rides it
            verbatim: the row is byte-identical to what it was before `:scope`
            left `sibling-owned-slot`, and the sibling's `:sensitive?` stamp
            survives. `:input-values` stays sibling-owned — this ruling covers
            `:scope` only."
    (let [record    (record-with
                      [(event :rf.resource/scope-resolved
                              {:rf.frame/id   :test/rt
                               :resource-id   :rt/session
                               :kind          :resolver
                               :inputs        [:username]
                               :input-values  {:username secret}
                               :scope         session-scope
                               :resolved-nil? false})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= :rf/redacted (:scope tags))
          "the sibling's sentinel rides through the family projector unchanged")
      (is (= :rf/redacted (:input-values tags))
          ":input-values is still sibling-owned and unchanged")
      (is (true? (:sensitive? tags)) "the sibling's :sensitive? stamp survives")
      (testing "the structural resolver attribution rides verbatim"
        (is (= :rt/session (:resource-id tags)))
        (is (= :resolver (:kind tags)))
        (is (= [:username] (:inputs tags)))
        (is (false? (:resolved-nil? tags))))
      (is (not (contains-secret? projected))
          "NO raw identity survives anywhere in the projected record"))))

;; ---------------------------------------------------------------------------
;; (8) the trusted-local boundary — the redaction is the off-box DEFAULT
;; ---------------------------------------------------------------------------

(deftest trusted-local-include-sensitive-keeps-raw-free-scope
  (testing "rf2-1zc33 — the trusted-local `:include-sensitive?` opt-in keeps the
            raw free `:scope` tag on every rostered row type, one of each driven
            below (the local-raw boundary — the tokenization is the off-box
            default, not a strip)"
    (let [record    (record-with
                      [(event :rf.resource/invalidated
                              {:rf.frame/id :test/rt :scope session-scope})
                       (event :rf.resource/refetch-decision
                              {:rf.frame/id :test/rt :scope session-scope})
                       (event :rf.resource/removed
                              {:rf.frame/id :test/rt :scope session-scope})
                       (event :rf.warning/resource-clear-scope-unresolved
                              {:rf.frame/id :test/rt :scope {:from-db :rt/session}})
                       (event :rf.mutation/started
                              {:rf.frame/id :test/rt :scope session-scope})
                       (event :rf.mutation/optimistic-applied
                              {:rf.frame/id :test/rt :scope session-scope})])
          projected (epoch/projected-record record {:include-sensitive? true})
          scopes    (mapv #(:scope (:tags %)) (:trace-events projected))]
      (is (= [session-scope session-scope session-scope
              {:from-db :rt/session}
              session-scope session-scope]
             scopes)
          "every row's raw :scope rides with :include-sensitive?"))))

;; ===========================================================================
;; (rf2-425mm) the SAME free `:scope`, ONE CARRIER FURTHER OUT — inside the
;; transport continuation payload copied onto `:rf.fx/args` / `:rf.event/fx`.
;; ===========================================================================
;;
;; §(rf2-1zc33) above settled the free `:scope` tag on the rows the resource
;; family OWNS. This is not its residual — a one-line change to
;; `sibling-owned-slot` could not have reached here, because the epoch tool-pair
;; routes that projector by OPERATION NAMESPACE (`resource-family-op?`) and the
;; rows below are `rf.fx`. It is the completeness remainder of rf2-1kiuj, the
;; OTHER projector: `project-fx-args-egress` → `project-embedded-keys`, reached
;; by SLOT on every row.
;;
;; An `ensure` lowers into `[:rf.http/managed <args>]`, and
;; `transport.http/build-managed-args` puts the runtime's stale-suppression
;; verification payload into the args' `:on-success` / `:on-failure`:
;;
;;   {:work/id      [:rf.work/resource <scoped-key> <gen>]
;;    :resource/key <scoped-key>
;;    :scope        <resolved scope>          ← the leak
;;    :generation   <n>
;;    :rf.frame/id  <frame>}
;;
;; `re-frame.fx/handle-one-fx` stamps those args under `:rf.fx/args` and `do-fx`
;; stamps the whole effect vector under `:rf.event/fx`, so the payload egresses
;; twice. `project-embedded-keys` walks both carriers, and — deliberately, and
;; rightly (rf2-1kiuj) — DESCENDS a map rather than tokenizing it, because an
;; fx-args payload belongs to the fx family and tokenizing it wholesale would
;; redact a plain owner's request map. It recognised the `:resource/key` and the
;; key embedded in the `:work/id` and redacted both. The `:scope` beside them is
;; a `[tier {identity}]` TUPLE, not a scoped key, so the walk descended it, found
;; an ordinary map, and let the resolver's IDENTITY MAP through in the clear —
;; one slot from the `:resource/key` that had just redacted the identical bytes,
;; and one carrier from the `:effects[*].args` twin that read `:rf/redacted`.
;;
;; No existing fixture could see it: every resource in every epoch-egress fixture
;; scoped `:rf.scope/global`, a SCALAR with nothing in it to leak. Only a
;; `{:from-db …}` resolver puts an identity map on the carrier, and the MCP-egress
;; conformance cascade reaches its identity-bearing scope through
;; `invalidate-tags`, which stamps a free `:scope` on a FAMILY row and never
;; lowers into fx.
;;
;; The repair gives a `:scope`-keyed value inside the carrier the SAME family rule
;; §(rf2-1zc33) gave it on the family's own rows (`project-unknown-slot-value`),
;; so the two carriers agree by construction. Per shape, unchanged from there:
;; a `:rf.scope/global` scalar rides verbatim, a `[tier {identity}]` tuple keeps
;; its tier and tokenizes its identity map.

(def ^:private profile-params {:slug "me"})

(defn- secret-leak-paths
  "Every path in `x` whose leaf string carries the secret, each with the
  offending value. The path-reporting counterpart of `contains-secret?`: a
  failure NAMES the slot that leaked instead of printing `(not (not true))`,
  which is the whole diagnostic value when the leak is four levels down inside
  an fx carrier."
  [x]
  (let [found (atom [])
        walk  (fn walk [path v]
                (cond
                  (string? v) (when (.contains ^String v "topsecret")
                                (swap! found conj [path v]))
                  (map? v)    (doseq [[k vv] v]
                                (walk (conj path k) k)
                                (walk (conj path k) vv))
                  (coll? v)   (doseq [[i vv] (map-indexed vector v)]
                                (walk (conj path i) vv))))]
    (walk [] x)
    @found))

(defn- carrier-scopes
  "Every value sitting under a `:scope` key anywhere inside the `:rf.fx/args` /
  `:rf.event/fx` carriers of `record`'s trace rows — i.e. the exact slot the
  four leaking paths name, found by walking rather than by index so the
  assertion does not encode the cascade's fx ORDER."
  [record]
  (let [found (atom [])
        walk  (fn walk [v]
                (cond
                  (map? v)  (doseq [[k vv] v]
                              (when (= :scope k) (swap! found conj vv))
                              (walk vv))
                  (coll? v) (run! walk v)))]
    (doseq [tags (map :tags (:trace-events record))
            slot [:rf.fx/args :rf.event/fx]
            :when (contains? tags slot)]
      (walk (get tags slot)))
    @found))

(defn- continuation-payload
  "The `:on-success` verification payload of the `:rf.http/managed` args as they
  egress under `:rf.fx/args` — `{:work/id … :resource/key … :scope … :generation
  … :rf.frame/id …}`, the map the four leaking paths run through."
  [record]
  (->> (:trace-events record)
       (map :tags)
       (keep :rf.fx/args)
       (filter #(and (map? %) (contains? % :on-success)))
       first
       :on-success
       second))

(defn- tokenized-scope?
  "Whether `s` is a resolved `[tier {identity}]` scope that egressed correctly —
  the tier keyword verbatim (attribution survives) over a tokenized identity."
  [s]
  (and (vector? s) (= 2 (count s))
       (= :rf.scope/session (first s))
       (redacted-component? (second s))))

(defn- drive-session-scoped-ensure!
  "Drive ONE real `[:rf.resource/ensure …]` of the `:sensitive?`
  `{:from-db :rt/session}` resource and return the epoch records it settled.

  The session identity is written straight into the frame's app-db partition
  (not dispatched) so it settles no record of its own, and CLASSIFIED
  `:sensitive` there so the app-db axis can never be what the scans below
  catch — any surviving copy of the secret came off a trace carrier, which is
  the axis this section owns. `fx/reg-fx` (the plain fn, not the `rf/reg-fx`
  macro) overrides managed HTTP with a no-op for the reason `drive-real-cascade!`
  documents."
  [resource-id]
  (rf/configure! {:epoch-history {:trace-events-keep 50}})
  (fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (frame/swap-runtime-db! :test/rt
    (fn [rt] (elision/apply-classification-effects
               rt {:sensitive [[:auth :user :username]]})))
  (frame/swap-frame-db! :test/rt assoc-in [:auth :user :username] secret)
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource-id
                      :params   profile-params
                      :owner    real-owner}]
                    {:frame :test/rt})
  (rf/epoch-history :test/rt))

;; ---------------------------------------------------------------------------
;; (1) THE ACCEPTANCE ARM — a REAL ensure, the whole projected record
;; ---------------------------------------------------------------------------

(deftest real-session-scoped-ensure-leaks-no-identity-into-fx-carriers
  (testing "rf2-425mm — `projected-record` over the record a REAL
            `[:rf.resource/ensure …]` settles for a `:sensitive?` resource with
            a `{:from-db …}` scope must carry the resolved identity at ZERO
            paths. Before the repair it carried it at four: the `:scope` inside
            the `:on-success` and `:on-failure` continuation payloads, once under
            `:rf.fx/args` and again under `:rf.event/fx`."
    (let [records   (drive-session-scoped-ensure! :derived/profile)
          raw       (last records)
          projected (epoch/projected-record raw)]

      (testing "FIXTURE — the producer really put an identity-bearing scope on
                the fx carriers, so the assertions below are not passing over an
                empty set"
        (is (seq (carrier-scopes raw))
            "the ensure's fx carriers carry a `:scope` at all")
        (is (every? #(= session-scope %) (carrier-scopes raw))
            "and every one of them is the RAW resolved [tier {identity}] tuple
             the resolver derived from app-db")
        (is (seq (secret-leak-paths raw))
            "the unprojected record leaks — the projector's input is the
             runtime's own output, not an invented tag map"))

      (testing "ACCEPTANCE — nothing raw survives anywhere in the projected
                record"
        (is (= [] (secret-leak-paths projected))
            "every leaking path is named here; before the repair this printed
             the four [:trace-events n :tags :rf.fx/args :on-success 1 :scope 1
             :username] shapes"))

      (testing "and each carrier's scope is PROJECTED, not merely absent"
        (is (= (count (carrier-scopes raw)) (count (carrier-scopes projected)))
            "projection neither drops nor invents a carrier scope")
        (is (every? tokenized-scope? (carrier-scopes projected))
            "each keeps its TIER keyword and tokenizes its identity map"))

      (testing "the sibling `:resource/key` in the SAME payload agrees — the two
                carriers of one scope can no longer redact and leak it side by
                side"
        (let [cont (continuation-payload projected)]
          (is (some? cont) "the continuation payload is on the carrier")
          (is (= :derived/profile (second (:resource/key cont)))
              "the resource-id survives for attribution")
          (is (redacted-component? (first (:resource/key cont)))
              "carrier 1 — the key's scope component is tokenized")
          (is (tokenized-scope? (:scope cont))
              "carrier 2 — the free :scope beside it is tokenized too")
          (is (= (:generation (continuation-payload raw)) (:generation cont))
              "the generation rides verbatim")
          (is (= :test/rt (:rf.frame/id cont)) "the frame stamp rides verbatim"))))))

;; ---------------------------------------------------------------------------
;; (2) the same shape assembled — deterministic, and it names the four paths
;; ---------------------------------------------------------------------------

(defn- managed-args
  "The `:rf.http/managed` args `transport.http/build-managed-args` produces for
  one ensure: the app `:request` map plus the runtime-owned `:request-id` and
  the `:on-success` / `:on-failure` verification payloads."
  [scoped-key scope]
  (let [work-id [:rf.work/resource scoped-key 1]
        payload {:work/id      work-id
                 :resource/key scoped-key
                 :scope        scope
                 :generation   1
                 :rf.frame/id  :test/rt}]
    {:request    {:method :get :url "/z"}
     :request-id [:rf.req :test/rt work-id]
     :on-success [:rf.resource.internal/succeeded payload]
     :on-failure [:rf.resource.internal/failed payload]}))

(defn- fx-carrier-record
  "A record carrying the ensure's TWO fx carriers of one payload — the
  `:rf.fx/handled` row's `:rf.fx/args` and the `:rf.fx/do-fx` row's
  `:rf.event/fx` (the whole effect vector, the managed fx third)."
  [args]
  (record-with
    [(event :rf.fx/handled
            {:rf.frame/id :test/rt :frame :test/rt
             :rf.fx/id :rf.http/managed :rf.fx/args args})
     (event :rf.fx/do-fx
            {:rf.frame/id :test/rt :frame :test/rt
             :rf.event/fx [[:rf.resource/commit-generation {:value 1}]
                           [:rf.resource/record-work-handle {:frame-id :test/rt}]
                           [:rf.http/managed args]]})]))

(deftest fx-carrier-scope-tokenizes-on-both-carriers
  (testing "rf2-425mm — the projector, over the exact payload the transport
            builds. Four `:scope` occurrences across the two carriers, every one
            of them tokenized; and the fx family's OWN slots on the same rows
            ride untouched, because the resource family speaks only for what it
            planted there."
    (let [k1        (sk session-scope :derived/profile profile-params)
          args      (managed-args k1 session-scope)
          projected (epoch/projected-record (fx-carrier-record args))
          scopes    (carrier-scopes projected)]
      (is (= 4 (count scopes))
          "two payloads per carrier, two carriers — the four paths the bead named")
      (is (every? tokenized-scope? scopes)
          "each keeps its tier keyword and tokenizes its identity map")
      (is (= [] (secret-leak-paths projected))
          "and nothing raw survives anywhere in the record")
      (testing "the fx family's own args ride UNTOUCHED"
        (let [tags (:tags (first (:trace-events projected)))]
          (is (= {:method :get :url "/z"} (:request (:rf.fx/args tags)))
              "the app's request map is not redacted")
          (is (= :rf.http/managed (:rf.fx/id tags))
              "the fx id rides verbatim")
          (is (true? (:sensitive? tags))
              "but the row IS stamped :sensitive?"))))))

;; ---------------------------------------------------------------------------
;; (3) THE TWO-SIDED CONTROL — over-redaction must fail as loudly as leaking
;; ---------------------------------------------------------------------------

(deftest fx-carrier-keeps-plain-request-map-and-global-scope-verbatim
  (testing "rf2-425mm guard — a PLAIN owner's `:rf.http/managed` args, whose
            scope is the `:rf.scope/global` SCALAR, must ride BYTE-IDENTICAL
            through both carriers and must not stamp the row sensitive. This is
            the side that proves the repair did not turn `project-embedded-keys`
            into the wholesale map tokenizer rf2-1kiuj rejected: the app's own
            request map, its params and its scope all survive."
    (let [k1        (sk :rf.scope/global :plain/article {:slug plain-slug})
          args      (managed-args k1 :rf.scope/global)
          record    (fx-carrier-record args)
          projected (epoch/projected-record record)
          [handled do-fx] (:trace-events projected)]
      (is (= args (:rf.fx/args (:tags handled)))
          "the whole args map rides verbatim — request, request-id and both
           continuation payloads, scoped keys and scope included")
      (is (= (:rf.event/fx (:tags (second (:trace-events record))))
             (:rf.event/fx (:tags do-fx)))
          "and so does the whole effect vector on the other carrier")
      (is (= [:rf.scope/global :rf.scope/global :rf.scope/global :rf.scope/global]
             (carrier-scopes projected))
          "a global scope is a scalar — no over-redaction on any carrier")
      (is (not (:sensitive? (:tags handled)))
          "a plain-owner fx row is NOT stamped sensitive")
      (is (not (:sensitive? (:tags do-fx)))
          "on either carrier"))))

(deftest fx-carrier-scope-tokens-stay-distinct-per-scope
  (testing "rf2-425mm — distinct scopes must keep DISTINCT digests on the fx
            carriers too, so an Xray effect graph can still group and join a
            record's requests by session after the identity map tokenizes"
    (let [proj  (fn [scope]
                  (let [k (sk scope :derived/profile profile-params)]
                    (-> (fx-carrier-record (managed-args k scope))
                        epoch/projected-record
                        carrier-scopes
                        first)))
          s1    (proj session-scope)
          s2    (proj other-session-scope)]
      (is (tokenized-scope? s1))
      (is (tokenized-scope? s2))
      (is (not= (second s1) (second s2))
          "two distinct sessions keep two distinct digests"))))

;; ---------------------------------------------------------------------------
;; (4) the trusted-local boundary — the redaction is the off-box DEFAULT
;; ---------------------------------------------------------------------------

(deftest trusted-local-include-sensitive-keeps-raw-fx-carrier-scope
  (testing "rf2-425mm — the trusted-local `:include-sensitive?` opt-in keeps the
            raw carrier scope (the local-raw boundary — the tokenization is the
            off-box default, not a strip)"
    (let [k1        (sk session-scope :derived/profile profile-params)
          args      (managed-args k1 session-scope)
          projected (epoch/projected-record (fx-carrier-record args)
                                            {:include-sensitive? true})]
      (is (= [session-scope session-scope session-scope session-scope]
             (carrier-scopes projected))
          "every carrier's raw :scope rides with :include-sensitive?")
      (is (= args (:rf.fx/args (:tags (first (:trace-events projected)))))
          "and so does the rest of the payload it sits in"))))

;; ===========================================================================
;; (rf2-xx4ty) the SAME two carriers, a DIFFERENT map: the READ COMPLETION
;; CONTINUATION reply, whose `:value` is the DECODED RESPONSE BODY.
;; ===========================================================================
;;
;; §(rf2-425mm) above closed the resolved `:scope` inside the TRANSPORT
;; continuation payload. This is a different map on the same carriers, and it
;; is the most sensitive datum the family puts there.
;;
;; An `ensure` / `refetch` MAY carry a call-site `:reply-to` (EP-0016 D1 →
;; reads, rf2-p1yri7; Spec 016 §Read completion continuations). When the read
;; settles — or is served immediately by a fresh-skip cache hit —
;; `events/read-continuation-reply` augments the canonical reply with the
;; top-level read facts and `re-frame.reply/complete` APPENDS the whole map as
;; the final argument of the target event vector, which the runtime dispatches
;; through `[:dispatch <ev>]`. So this rides `:rf.fx/args` and `:rf.event/fx`:
;;
;;   {:status       :ok
;;    :value        <DECODED RESPONSE BODY>      ← the leak
;;    :params       <canonical params>           ← the leak
;;    :scope        <resolved scope>             ← closed by rf2-425mm
;;    :resource/key <scoped-key>                 ← closed by rf2-1kiuj
;;    :resource     <resource-id>
;;    :cache-hit?   <bool>
;;    :rf.reply/work-id …, :correlation {…}, …}
;;
;; `project-embedded-keys` recognised the `:resource/key`, the key embedded in
;; the `:rf.reply/work-id`, the `:correlation`'s `:rf.reply/resource-key`, and
;; (rf2-425mm) the free `:scope`. `:value` and `:params` are ordinary maps, so
;; the walk descended them and let the owner's decoded body through in the
;; clear — one slot from the `:resource/key` that had just redacted the very
;; same params.
;;
;; WHY THIS ONE IS OWNER-CONDITIONAL AND `:scope` IS NOT. The `:scope` arm
;; §(rf2-425mm) added fires unconditionally, because the family's own rows
;; classify a free `:scope` unconditionally (rf2-1zc33) and the two carriers of
;; one scope must agree. `:value` and `:params` are the opposite case twice
;; over. They belong to a NAMED owner whose `:resource/key` sits one slot away,
;; and the family's own rows tokenize that owner's params IFF
;; `whole-entry-disposition` is non-`:serialize` — so an unconditional arm would
;; redact a PLAIN resource's reply, the over-redaction rf2-1kiuj rejected. And
;; `:params` / `:value` are words the FX FAMILY uses for its own data: an app's
;; managed-HTTP args carry `{:request {… :params {…}}}` and
;; `[:rf.resource/commit-generation {:value 1}]` rides the same effect vector,
;; neither of which the resource family may touch. The sibling `:resource/key`
;; is what makes the two names safe to read — it says the map is a resource
;; reply and names whose. `row-owner-redacts?` (the load-more cursor's read,
;; rf2-3tysyj) already answers exactly that question, one carrier out.
;;
;; READS AND MUTATIONS DIFFER HERE, and the difference is the reason this arm
;; keys on the sibling where rf2-425mm's could not. The mutation `:reply-to`
;; (`mutation_events/continuation-reply`) carries `:params` / `:value` with NO
;; `:resource/key` beside them, so this arm does not fire on it. It does not
;; need to: both mutation settle sites wrap their reply in
;; `classification/redact-continuation-reply`, which applies the mutation's own
;; projection-relative `:sensitive` / `:large` declarations at the SOURCE,
;; before the reply reaches any carrier (rf2-825mzj). The read reply has no
;; such source-side redaction and must not: the coarse `:sensitive?` claim
;; governs OFF-BOX egress, not in-process delivery — the app's own continuation
;; handler is entitled to the decoded body, and `:include-sensitive?` must still
;; show it. Hence the egress projector, and hence the owner gate.

(def ^:private reply-params
  "The canonical params of the read whose continuation leaks — SECRET-bearing,
  because `:params` is one of the two slots this section owns. Safe to carry
  the secret: `:derived/profile`'s request fn does not echo its params into the
  request map (the app's own request is the FX family's data and rides
  untouched by design — rf2-1kiuj)."
  {:slug secret})

(def ^:private reply-value
  "The DECODED RESPONSE BODY the read delivers under `:value`. The most
  sensitive datum this family puts on a foreign carrier: not a key, not a
  scope, not an identity map — payload."
  {:email (str secret "@example.com")})

(def ^:private read-reply-target
  "The call-site `:reply-to` — an ordinary app event vector. `reply/complete`
  APPENDS the reply map after it, so the dispatched vector is
  `[:app/read-loaded <reply>]` and the reply sits at index 1 of `:rf.fx/args`."
  [:app/read-loaded])

(defn- carrier-replies
  "Every READ CONTINUATION REPLY map riding a `:rf.fx/args` / `:rf.event/fx`
  carrier of `record`'s trace rows. Found by walking for the reply's OWN marker
  (`:rf.reply/work-kind :resource`) rather than by index, so no assertion here
  encodes the cascade's fx order or the reply's position in the event vector."
  [record]
  (let [found (atom [])
        walk  (fn walk [v]
                (cond
                  (map? v)  (do (when (= :resource (:rf.reply/work-kind v))
                                  (swap! found conj v))
                                (run! walk (vals v)))
                  (coll? v) (run! walk v)))]
    (doseq [tags (map :tags (:trace-events record))
            slot [:rf.fx/args :rf.event/fx]
            :when (contains? tags slot)]
      (walk (get tags slot)))
    @found))

(defn- record-carrying-reply
  "The settled record whose fx carriers carry a read-continuation reply with
  the given `:cache-hit?` disposition — the ASYNC SETTLE (`false`, the accepted
  terminal reply fanning out to the recorded target) or the FRESH-SKIP
  immediate dispatch (`true`). Selected by the reply's own fact rather than by
  record index, because the two settle through different branches of
  `events.cljc` and only the reply says which is which."
  [records cache-hit?]
  (first (filter (fn [r] (some #(= cache-hit? (:cache-hit? %)) (carrier-replies r)))
                 records)))

(defn- drive-reply-to-read!
  "Drive a REAL `[:rf.resource/ensure …]` carrying a call-site `:reply-to`
  against the `:sensitive?` `{:from-db :rt/session}` resource, settle its reply,
  and then drive a SECOND ensure that finds the entry fresh — so one call
  produces BOTH continuation paths: the async accepted-reply fan-out
  (`:cache-hit? false`) and the fresh-skip immediate dispatch
  (`:cache-hit? true`). Returns the epoch records.

  The managed-HTTP fx is a CAPTURING stub (`fx/reg-fx`, the plain fn — see
  `drive-real-cascade!` for why the macro would break default-image
  reprojection) and the reply is replayed through the real internal reply
  event, so the settle path and its `[:dispatch …]` continuation fx are the
  runtime's own. The session identity is written straight into the frame's
  app-db partition and CLASSIFIED `:sensitive` there, so the app-db axis can
  never be what the scans below catch."
  [resource-id]
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/read-loaded (fn [_ _ev] {}))
    (frame/swap-runtime-db! :test/rt
      (fn [rt] (elision/apply-classification-effects
                 rt {:sensitive [[:auth :user :username]]})))
    (frame/swap-frame-db! :test/rt assoc-in [:auth :user :username] secret)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource resource-id :params reply-params
                        :owner    real-owner  :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (:on-success @captured) {:status :ok :value reply-value})
                      {:frame :test/rt})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource resource-id :params reply-params
                        :owner    [:app :reader 2] :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/epoch-history :test/rt)))

;; ---------------------------------------------------------------------------
;; (1) THE ACCEPTANCE ARM — a REAL reply-to read, both continuation paths
;; ---------------------------------------------------------------------------

(deftest real-reply-to-read-leaks-no-decoded-body-into-fx-carriers
  (testing "rf2-xx4ty — `projected-record` over the records a REAL
            `[:rf.resource/ensure … :reply-to …]` settles for a `:sensitive?`
            resource must carry the decoded response body and the canonical
            params at ZERO paths, on BOTH continuation paths. Before the repair
            each carried them at four: `:value` and `:params` under
            `:rf.fx/args`, and again under `:rf.event/fx`."
    (let [records (drive-reply-to-read! :derived/profile)]
      (doseq [[label cache-hit?] [["async settle" false] ["fresh-skip cache hit" true]]]
        (testing label
          (let [raw       (record-carrying-reply records cache-hit?)
                projected (epoch/projected-record raw)]

            (testing "FIXTURE — the producer really put a decoded body on the fx
                      carriers, so the assertions below are not passing over an
                      empty set"
              (is (some? raw) "the continuation reached an fx carrier at all")
              (is (seq (carrier-replies raw)) "and the reply map is findable on it")
              (is (every? #(and (= reply-value (:value %))
                                (= reply-params (:params %)))
                          (carrier-replies raw))
                  "every carrier's reply carries the RAW decoded body + params")
              (is (seq (secret-leak-paths raw))
                  "the unprojected record leaks — the projector's input is the
                   runtime's own output, not an invented tag map"))

            (testing "ACCEPTANCE — nothing raw survives anywhere in the projected
                      record"
              (is (= [] (secret-leak-paths projected))
                  "every leaking path is named here; before the repair this
                   printed the [:trace-events n :tags :rf.fx/args 1 :value :email]
                   and :params :slug shapes, once per carrier"))

            (testing "and each reply's payload is TOKENIZED, not merely absent"
              (is (= (count (carrier-replies raw)) (count (carrier-replies projected)))
                  "projection neither drops nor invents a carrier reply")
              (is (every? #(and (redacted-component? (:value %))
                                (redacted-component? (:params %)))
                          (carrier-replies projected))
                  "both slots are opaque content-addressed tokens"))

            (testing "the whole reply still reads as a reply — attribution and
                      the sibling slots the earlier repairs own"
              (let [r (first (carrier-replies projected))]
                (is (= :derived/profile (:resource r))
                    "the resource id rides verbatim")
                (is (= cache-hit? (:cache-hit? r))
                    "and the cache-hit disposition, so a tool still reads how it settled")
                (is (= :ok (:status r)) "and the status")
                (is (redacted-component? (first (:resource/key r)))
                    "rf2-1kiuj — the sibling key's scope component is still tokenized")
                (is (= :derived/profile (second (:resource/key r)))
                    "with its resource-id intact for attribution")
                (is (tokenized-scope? (:scope r))
                    "rf2-425mm — and the free :scope beside it")))))))))

;; ---------------------------------------------------------------------------
;; (2) the same shape assembled — deterministic, and it names the four paths
;; ---------------------------------------------------------------------------

(defn- read-reply
  "The continuation reply map `events/read-continuation-reply` builds — the
  canonical reply (`resources.reply/success-reply`) plus the top-level read
  facts it layers on."
  [scoped-key scope value]
  (let [[_ resource-id params] scoped-key]
    {:status               :ok
     :value                value
     :rf.reply/work-id     [:rf.work/resource scoped-key 1]
     :rf.reply/work-kind   :resource
     :rf.reply/work-status :completed
     :rf.frame/id          :test/rt
     :completed-at         0
     :correlation          {:scope scope :generation 1
                            :rf.reply/resource-key scoped-key}
     :resource             resource-id
     :params               params
     :scope                scope
     :resource/key         scoped-key
     :cache-hit?           false}))

(defn- reply-carrier-record
  "A record carrying the continuation's TWO fx carriers of one reply — the
  `:rf.fx/handled` row's `:rf.fx/args` (the dispatched event vector) and the
  `:rf.fx/do-fx` row's `:rf.event/fx` (the whole effect vector).

  The effect vector deliberately also carries
  `[:rf.resource/commit-generation {:value 1}]` — a FOREIGN map with a `:value`
  and no `:resource/key`. It is the control that a name-only arm would fail:
  the runtime's generation counter must ride verbatim while the reply's
  `:value` two slots away tokenizes."
  [reply]
  (let [ev (conj read-reply-target reply)]
    (record-with
      [(event :rf.fx/handled
              {:rf.frame/id :test/rt :frame :test/rt
               :rf.fx/id :dispatch :rf.fx/args ev})
       (event :rf.fx/do-fx
              {:rf.frame/id :test/rt :frame :test/rt
               :rf.event/fx [[:rf.resource/commit-generation {:value 1}]
                             [:dispatch ev]]})])))

(deftest fx-carrier-reply-payload-tokenizes-on-both-carriers
  (testing "rf2-xx4ty — the projector, over the exact reply the runtime builds.
            Two payload slots across two carriers, every one of them tokenized;
            and the foreign `:value` on the same effect vector rides untouched,
            because the resource family speaks only for what it planted."
    (let [k1        (sk session-scope :derived/profile reply-params)
          reply     (read-reply k1 session-scope reply-value)
          projected (epoch/projected-record (reply-carrier-record reply))
          replies   (carrier-replies projected)
          [handled do-fx] (:trace-events projected)]
      (is (= 2 (count replies))
          "one reply per carrier — the two the bead named, four paths in all")
      (is (every? #(and (redacted-component? (:value %))
                        (redacted-component? (:params %)))
                  replies)
          "each carrier tokenizes both payload slots")
      (is (= [] (secret-leak-paths projected))
          "and nothing raw survives anywhere in the record")
      (testing "the FOREIGN :value on the same effect vector is untouched"
        (is (= [:rf.resource/commit-generation {:value 1}]
               (first (:rf.event/fx (:tags do-fx))))
            "a map with a :value and no :resource/key is nobody's business here")
        (is (= :dispatch (:rf.fx/id (:tags handled)))
            "and the fx id rides verbatim")
        (is (= :app/read-loaded (first (:rf.fx/args (:tags handled))))
            "as does the continuation TARGET — a tool still reads which event ran")
        (is (true? (:sensitive? (:tags handled)))
            "but the row IS stamped :sensitive?")))))

;; ---------------------------------------------------------------------------
;; (3) THE TWO-SIDED CONTROL — over-redaction must fail as loudly as leaking
;; ---------------------------------------------------------------------------

(deftest fx-carrier-keeps-plain-owners-reply-payload-verbatim
  (testing "rf2-xx4ty guard — a PLAIN owner's read continuation reply must ride
            BYTE-IDENTICAL through both carriers and must not stamp the row
            sensitive. This is the side that proves the arm reads the ROW'S
            OWNER rather than the two slot names: the same `:value` and
            `:params` that tokenize for `:derived/profile` are fully readable
            here, which is what keeps a plain resource debuggable off-box."
    (let [k1        (sk :rf.scope/global :plain/article {:slug plain-slug})
          reply     (read-reply k1 :rf.scope/global {:title "hello"})
          record    (reply-carrier-record reply)
          projected (epoch/projected-record record)
          [handled do-fx] (:trace-events projected)]
      (is (= (conj read-reply-target reply) (:rf.fx/args (:tags handled)))
          "the whole dispatched event vector rides verbatim — reply :value,
           :params, :scope and scoped key included")
      (is (= (:rf.event/fx (:tags (second (:trace-events record))))
             (:rf.event/fx (:tags do-fx)))
          "and so does the whole effect vector on the other carrier")
      (is (not (:sensitive? (:tags handled)))
          "a plain-owner reply row is NOT stamped sensitive")
      (is (not (:sensitive? (:tags do-fx)))
          "on either carrier"))))

(deftest fx-carrier-leaves-the-fx-familys-own-params-verbatim
  (testing "rf2-xx4ty guard — the OTHER half of the over-redaction control, and
            the sharper one: on a record carrying BOTH a `:sensitive?` owner's
            reply AND the app's own managed-HTTP args, the reply's `:params`
            tokenizes while the app's `:request` `:params` — a map under the
            identical key, with no `:resource/key` beside it — rides verbatim.
            An arm keyed on the slot NAME could not tell these apart."
    (let [k1     (sk session-scope :derived/profile reply-params)
          reply  (read-reply k1 session-scope reply-value)
          req    {:method :get :url "/z" :params {:slug plain-slug}}
          record (record-with
                   [(event :rf.fx/handled
                           {:rf.frame/id :test/rt :frame :test/rt
                            :rf.fx/id :dispatch
                            :rf.fx/args (conj read-reply-target reply)})
                    (event :rf.fx/handled
                           {:rf.frame/id :test/rt :frame :test/rt
                            :rf.fx/id :rf.http/managed
                            :rf.fx/args {:request req}})])
          projected (epoch/projected-record record)
          [reply-row req-row] (:trace-events projected)]
      (is (redacted-component? (:params (first (carrier-replies projected))))
          "the reply's :params — a named owner's, read through that owner")
      (is (= {:request req} (:rf.fx/args (:tags req-row)))
          "the app's request :params — the FX family's, untouched")
      (is (true? (:sensitive? (:tags reply-row)))
          "only the reply row is stamped sensitive")
      (is (not (:sensitive? (:tags req-row)))
          "the request row is not"))))

(deftest fx-carrier-reply-tokens-stay-distinct-per-value
  (testing "rf2-xx4ty — distinct bodies must keep DISTINCT digests, so an Xray
            effect graph can still tell two reads' completions apart after the
            payload tokenizes"
    (let [proj  (fn [params value]
                  (let [k (sk session-scope :derived/profile params)]
                    (-> (reply-carrier-record (read-reply k session-scope value))
                        epoch/projected-record
                        carrier-replies
                        first)))
          r1    (proj reply-params reply-value)
          r2    (proj {:slug (str secret "-2")} {:email "other@example.com"})]
      (is (not= (:value r1) (:value r2))
          "two distinct bodies keep two distinct digests")
      (is (not= (:params r1) (:params r2))
          "and so do two distinct params maps"))))

;; ---------------------------------------------------------------------------
;; (4) the trusted-local boundary — the redaction is the off-box DEFAULT
;; ---------------------------------------------------------------------------

(deftest trusted-local-include-sensitive-keeps-raw-fx-carrier-reply
  (testing "rf2-xx4ty — the trusted-local `:include-sensitive?` opt-in keeps the
            raw reply payload (the local-raw boundary — the tokenization is the
            off-box default, not a strip). This is load-bearing beyond the
            pattern: a `:reply-to` continuation is how a workflow reads a
            resource, so a local tool that could not see `:value` could not
            debug the workflow at all."
    (let [k1        (sk session-scope :derived/profile reply-params)
          reply     (read-reply k1 session-scope reply-value)
          projected (epoch/projected-record (reply-carrier-record reply)
                                            {:include-sensitive? true})]
      (is (= [reply-value reply-value] (mapv :value (carrier-replies projected)))
          "every carrier's raw :value rides with :include-sensitive?")
      (is (= [reply-params reply-params] (mapv :params (carrier-replies projected)))
          "and its :params")
      (is (= (conj read-reply-target reply)
             (:rf.fx/args (:tags (first (:trace-events projected)))))
          "and so does the rest of the event vector it sits in"))))


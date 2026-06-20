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
            [re-frame.epoch :as epoch]
            [re-frame.resources.state :as state]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; load-bearing: publishes the :resources/* late-bind hooks,
            ;; including :resources/project-resource-trace-egress.
            [re-frame.resources]
            [re-frame.schemas]))

(def ^:private secret "topsecret-PII")
(def ^:private big-params {:blob (apply str (repeat 5000 "x"))})

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (rf/reg-frame :test/rt {})
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
                ;; a derived-sensitive {:from-db} scope: the owner did NOT
                ;; declare :sensitive?, but the resolver is explicitly
                ;; :rf.egress/sensitive (force-mark), so the entry inherits
                ;; :redact (defence-in-depth — Spec 015 §Derived sensitivity).
                (rf/reg-resource-scope :rt/session
                  {:inputs  {:username [:db [:auth :user :username]]}
                   :rf.egress/output-sensitivity :rf.egress/sensitive
                   :resolve (fn [{:keys [username]} _]
                              (when username [:rf.scope/session {:username username}]))})
                (rf/reg-resource :derived/profile
                  {:scope         {:from-db :rt/session}
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/z"}}))
                ;; a PLAIN resource — must ride verbatim (no over-redaction).
                (rf/reg-resource :plain/article
                  {:scope         :rf.scope/global
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/a"}})))}))

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
                                :generation 1 :owner [:lease :l 1] :cause :ensure})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))
          [pscope rid pparams] (:resource/key tags)]
      (is (= :secret/article rid) "the resource-id (position 1) survives")
      (is (redacted-component? pscope) "the scope is tokenized")
      (is (redacted-component? pparams) "the params are tokenized")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the structural attribution tags ride verbatim"
        (is (= [:lease :l 1] (:owner tags)))
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

(deftest off-box-redacts-rollback-dispositions-derived-sensitive-scope
  (testing "rf2-8x0gfa — a :rf.mutation/optimistic-rolled-back row's
            :dispositions per-key maps have their derived-sensitive scope +
            params tokenized off-box; the boolean disposition facts survive"
    (let [;; the resolver derives [:rf.scope/session {:username secret}] — the
          ;; entry inherits :redact even though the owner didn't declare it.
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
                                :page-count 1 :owner [:lease :l 1] :cause :load-more})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))]
      (is (redacted-component? (:page-param tags))
          "the cursor is tokenized to an opaque {:rf/redacted <digest>}")
      (is (not= cursor-secret (:page-param tags)) "the raw cursor does not ride")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the structural attribution tags ride verbatim"
        (is (= 1 (:page-index tags)))
        (is (= 1 (:page-count tags)))
        (is (= [:lease :l 1] (:owner tags)))
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

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
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
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

(def ^:private reset-runtime-fixture
  "The `:each` fixture, held by name so the rf2-22ij6 inventory sweep can reuse
  it to isolate the drives it runs INSIDE one deftest (`driven-in-isolation`).
  Reusing the fixture rather than re-implementing the reset is the point: the
  inventory's drives get exactly the runtime every other drive in this
  namespace gets."
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
                ;; rf2-ko5lm — a resource that makes NO COARSE claim and
                ;; declares PROJECTION-RELATIVE slots instead.
                ;; `whole-entry-disposition` of this spec is `:serialize`, so
                ;; `row-owner-redacts?` is FALSE and the coarse read-reply arm
                ;; (rf2-xx4ty) never fires on it — which is exactly why its
                ;; continuation reply rode verbatim. `:email` is declared
                ;; sensitive, `:avatar` declared large, and `:display-name`
                ;; declared as NEITHER, so one body exercises redact, elide,
                ;; and the untouched sibling that proves the projection is
                ;; per-PATH rather than per-slot.
                (rf/reg-resource :declared/profile
                  {:scope         :rf.scope/global
                   :sensitive     [[:data :email]]
                   :large         [[:data :avatar]]
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/c"}}))
                ;; rf2-ko5lm — the PARAMS axis of the same declaration surface,
                ;; on its own owner so the data-axis fixture above stays a
                ;; three-outcome body and nothing else.
                (rf/reg-resource :declared/params-owner
                  {:scope         :rf.scope/global
                   :sensitive     [[:params :account]]
                   :params-schema [:map [:account :string] [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/d"}}))
                ;; rf2-zaopo — the same declaration surface on an INFINITE
                ;; FEED. `:reply-to` delivers the MERGED / flattened item list
                ;; under `:value` (`infinite-reply-value`), so the declared
                ;; `[:data :email]` names a field of EACH ITEM and the runtime
                ;; path is `[:value <i> :email]`. Same three outcomes per item
                ;; as `:declared/profile` — redact, elide, ride — so one feed
                ;; proves the index-free match is per-PATH and not a
                ;; whole-slot tokenization.
                (rf/reg-resource :declared/feed
                  {:scope           :rf.scope/global
                   :infinite        true
                   :next-page-param (fn [_last _all] nil)
                   :page->items     :items
                   :sensitive       [[:data :email]]
                   :large           [[:data :avatar]]
                   :params-schema   [:map [:filter :keyword]]}
                  (fn [_ _] {:request {:method :get :url "/e"}}))
                ;; rf2-zaopo — the feed-shaped over-redaction control: an
                ;; infinite feed that declares NEITHER axis. Its merged items
                ;; carry the identical field names and must ride byte-identical.
                (rf/reg-resource :plain/feed
                  {:scope           :rf.scope/global
                   :infinite        true
                   :next-page-param (fn [_last _all] nil)
                   :page->items     :items
                   :params-schema   [:map [:filter :keyword]]}
                  (fn [_ _] {:request {:method :get :url "/f"}}))
                ;; rf2-wd9im (merged-PR audit #7013) — a :sensitive? owner whose
                ;; REQUIRED :params-schema legally admits NON-MAP canonical
                ;; params. Nothing exotic: `[:vector :string]` is an ordinary
                ;; schema, and the resource registrar validates + canonicalizes
                ;; against whatever the owner declared. Its scoped key wears the
                ;; positional skeleton of every other key but has no MAP at
                ;; position 2, which is the only proof the shape read used to
                ;; take.
                (rf/reg-resource :secret/vector-params
                  {:scope         :rf.scope/global
                   :sensitive?    true
                   :params-schema [:vector :string]}
                  (fn [_ _] {:request {:method :get :url "/g"}}))
                ;; …and its over-redaction control: same params SHAPE, no
                ;; coarse claim, so its key must ride verbatim.
                (rf/reg-resource :plain/vector-params
                  {:scope         :rf.scope/global
                   :params-schema [:vector :string]}
                  (fn [_ _] {:request {:method :get :url "/h"}}))
                ;; a PLAIN resource under a CONCRETE (non-global) scope — the
                ;; over-redaction control for the FREE `:scope` tag (rf2-1zc33).
                ;; Its scoped KEY must keep scope AND params verbatim, which is
                ;; what proves the `:scope` repair touched only the free tag.
                (rf/reg-resource :plain/profile
                  {:scope         {:from-db :rt/session}
                   :params-schema [:map [:slug :string]]}
                  (fn [_ _] {:request {:method :get :url "/b"}})))}))

(use-fixtures :each reset-runtime-fixture)

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
      (testing ":identities tokenizes per key. Per-key DISTINCTNESS is NOT
                preserved for a sensitive owner (rf2-hzcv8): the digest that
                preserved it was recoverable by enumeration over a low-entropy
                auth token, so it is gone. The vector's CARDINALITY and each
                member's resource-id still ride, which is what makes the row a
                partition a tool can read"
        (is (= 2 (count (:identities tags)))
            "both members still ride — the count is the partition fact")
        (is (every? #(= :secret/article (second %)) (:identities tags)))
        (is (every? #(redacted-component? (nth % 2)) (:identities tags)))
        (is (apply = (map #(nth % 2) (:identities tags)))
            "and the two tokens AGREE — nothing content-derived tells them apart"))
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

(deftest off-box-redacts-route-plan-identity-partition
  (testing "rf2-dlkou — the activation row's IDENTITY PARTITION
            (:ensured-identities / :kept-identities / :removed-identities) is
            three more vectors of scoped keys under no NAMED slot. The safety
            closes by SHAPE, so they were covered the moment they were emitted;
            this pins it, because the partition is the one place a route's
            path parameters ride the trace three times over"
    (let [ensured   (sk :rf.scope/global :secret/article {:auth-token secret})
          kept      (sk :rf.scope/global :secret/article
                        {:auth-token (str secret "-kept")})
          removed   (sk :rf.scope/global :secret/article
                        {:auth-token (str secret "-gone")})
          record    (record-with
                      [(event :rf.resource/route-plan
                              {:rf.frame/id        :test/rt
                               :route-id           :r/article
                               :nav-token          7
                               :branch             [:r/root :r/article]
                               :ensured            1
                               :kept               1
                               :removed            1
                               :blocking           [ensured]
                               :identities         [kept ensured]
                               :ensured-identities [ensured]
                               :kept-identities    [kept]
                               :removed-identities [removed]})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))
          partition-slots (juxt :ensured-identities :kept-identities
                                :removed-identities)]
      (testing "every partition slot tokenizes per key, exactly as :identities does"
        (doseq [ks (partition-slots tags)]
          (is (= 1 (count ks)))
          (is (= :secret/article (second (first ks)))
              "the resource-id (position 1) survives")
          (is (redacted-component? (first (first ks))) "the scope is tokenized")
          (is (redacted-component? (nth (first ks) 2))
              "the canonical params — a route's path parameters — are tokenized")))
      (testing "the three identities no longer keep three distinct digests
                (rf2-hzcv8 — a sensitive owner's token is content-free), but the
                partition a tool reads off one row survives on the STRUCTURE:
                each slot is its own vector, each of a known size, each member
                keeping its resource-id"
        (is (= 1 (count (set (map #(nth (first %) 2) (partition-slots tags)))))
            "all three tokens agree — the content that separated them is gone")
        (is (= [1 1 1] (mapv count (partition-slots tags)))
            "and the partition is still a partition: three slots, one identity
             each, which is the fact the row exists to report"))
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the counts beside the vectors are structural scalars and ride"
        (is (= 1 (:ensured tags)))
        (is (= 1 (:kept tags)))
        (is (= 1 (:removed tags))))
      (testing "NO raw secret survives anywhere in the projected record"
        (is (not (contains-secret? projected)))))))

(deftest off-box-keeps-plain-owner-identity-partition-verbatim
  (testing "rf2-dlkou guard — a PLAIN owner's identity partition rides VERBATIM.
            The partition is a debugging aid, so closing the leak must cost no
            over-redaction on the ordinary route plan"
    (let [k1        (sk :rf.scope/global :plain/article {:slug plain-slug})
          k2        (sk :rf.scope/global :plain/article {:slug "other"})
          record    (record-with
                      [(event :rf.resource/route-plan
                              (assoc (route-plan-tags [k1] [k1])
                                     :ensured-identities [k1]
                                     :kept-identities    []
                                     :removed-identities [k2]))])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= [k1] (:ensured-identities tags)))
      (is (= [] (:kept-identities tags)) "an empty partition slot survives empty")
      (is (= [k2] (:removed-identities tags)))
      (is (not (:sensitive? tags)) "a plain row is NOT stamped sensitive"))))

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
                              {:rf.frame/id        :test/rt
                               :matched            ks   ; NAMED  → roster arm
                               :blocking           ks   ; UNNAMED → shape arm
                               :identities         ks   ; UNNAMED → shape arm
                               ;; rf2-dlkou — the identity partition, three more
                               ;; UNNAMED slots on the same row.
                               :ensured-identities ks
                               :kept-identities    ks
                               :removed-identities ks})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= (:matched tags) (:blocking tags))
          ":blocking projects exactly as the NAMED :matched does")
      (is (= (:matched tags) (:identities tags))
          ":identities projects exactly as the NAMED :matched does")
      (doseq [slot [:ensured-identities :kept-identities :removed-identities]]
        (is (= (:matched tags) (slot tags))
            (str slot " projects exactly as the NAMED :matched does")))
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

(deftest free-scope-tokens-carry-no-enumerable-content
  (testing "rf2-1zc33 / rf2-hzcv8 — a free scope tag keeps its TIER keyword, so
            an Xray invalidation graph still groups by scope tier; but the
            identity map's token is CONTENT-FREE, so two distinct sessions are
            indistinguishable after projection.

            This assertion used to run the other way — distinct scopes kept
            distinct digests, so a tool could join per session. rf2-hzcv8
            settled that the digest which bought that join was the leak: a
            session id lives in a candidate space small enough to enumerate, so
            a 32-bit token over it is recoverable and testable. A free scope tag
            carries no owner claim that could permit a content-derived token, so
            it takes the fail-closed shape. Per-session joins lose; tier-level
            attribution, which is what the graph actually groups on, survives."
    (let [r1 (record-with [(event :rf.resource/invalidated
                                  {:rf.frame/id :test/rt :scope session-scope})])
          r2 (record-with [(event :rf.resource/invalidated
                                  {:rf.frame/id :test/rt :scope other-session-scope})])
          s1 (free-scope r1)
          s2 (free-scope r2)]
      (is (= :rf.scope/session (first s1) (first s2))
          "both keep the tier keyword — attribution survives")
      (is (redacted-component? (second s1)))
      (is (redacted-component? (second s2)))
      (is (= (second s1) (second s2))
          "and the two tokens AGREE — nothing content-derived survives to tell
           two sessions apart"))))

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

(deftest fx-carrier-scope-tokens-carry-no-enumerable-content
  (testing "rf2-425mm / rf2-hzcv8 — the fx carriers take the SAME token contract
            as the family's own rows, which is the whole point of rf2-425mm: one
            scope, one rule, whichever carrier it rides. So the carrier's scope
            token is content-free too, and two distinct sessions agree here
            exactly as they do on the trace row above"
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
      (is (= :rf.scope/session (first s1) (first s2))
          "the tier keyword rides on the carrier too")
      (is (= (second s1) (second s2))
          "and the two tokens AGREE — the carrier did not acquire a weaker rule
           than the row"))))

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

(deftest fx-carrier-reply-tokens-carry-no-enumerable-content
  (testing "rf2-xx4ty / rf2-hzcv8 — a redacting owner's reply body and params
            tokenize on the carrier, and the token is content-free. Two distinct
            reads' completions are therefore no longer tellable apart off-box.

            That join is the exact thing rf2-hzcv8 declines to buy with an
            enumerable token: a reply body echoes submitted fields and a params
            map carries the slug, both low-entropy enough to confirm a guess
            against a 32-bit digest. The row's structural attribution — which
            resource, which frame, which op — is what a tool groups on, and it
            rides verbatim beside these slots"
    (let [proj  (fn [params value]
                  (let [k (sk session-scope :derived/profile params)]
                    (-> (reply-carrier-record (read-reply k session-scope value))
                        epoch/projected-record
                        carrier-replies
                        first)))
          r1    (proj reply-params reply-value)
          r2    (proj {:slug (str secret "-2")} {:email "other@example.com"})]
      (is (= (:value r1) (:value r2))
          "two distinct bodies of the same shape produce ONE token")
      (is (= (:params r1) (:params r2))
          "and so do two distinct params maps of the same shape"))))

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


;; ---------------------------------------------------------------------------
;; (5) THE CARRIER'S OTHER EDGE — over-classification (rf2-1kiuj, reopened)
;; ---------------------------------------------------------------------------
;;
;; Sections (1)-(4) and their rf2-425mm / rf2-xx4ty siblings above all push in
;; ONE direction: does the family's datum still egress raw? Three merged repairs
;; answered yes and each shipped the same second-order defect on the way — the
;; carrier arm fired on a LOCAL CUE that ordinary FX data hits by coincidence,
;; and destroyed app-owned data off-box while stamping the row `:sensitive?`.
;;
;;   - rf2-1kiuj — `scoped-key-shape?` alone: ANY `[<x> <keyword> <map>]`
;;     3-vector took the fail-closed unregistered-owner arm.
;;     `[:opaque :app/not-a-resource {:account-id 42}]` came back
;;     `[{:rf/redacted …} :app/not-a-resource {:rf/redacted …}]`.
;;   - rf2-425mm — the `:scope` KEY alone, at arbitrary depth. An app's own
;;     `{:request {… :scope {:tenant "alice"} …}}` had that map tokenized.
;;   - rf2-xx4ty — a map-local `:resource/key` alone as the "this is a read
;;     reply" test. Foreign `:value` / `:params` sitting beside a genuine
;;     sensitive key were tokenized with it.
;;
;; The repair is one idea applied three times: each arm now fires on PROOF that
;; the resource RUNTIME planted the value — its reserved keyword namespace, its
;; `[:rf.work/resource …]` work-id head, the canonical `:rf.reply/work-kind
;; :resource` marker, or the resource registry answering "is this one of mine?".
;; Recognition changed; the GRAIN did not — the free `:scope` still fails closed
;; unconditionally once recognised, and `:value` / `:params` are still
;; owner-conditional, so the two carriers of one datum still agree for a plain
;; owner. Each deftest below therefore carries both halves: the foreign value
;; rides verbatim AND the runtime's own still redacts, on the same shape.

(deftest fx-carrier-leaves-foreign-lookalike-vectors-verbatim
  (testing "rf2-1kiuj — scoped-key SHAPE is necessary and not sufficient inside
            a FOREIGN carrier. A 3-vector whose position 1 names no registered
            resource is application data and rides byte-for-byte; the row is not
            stamped. The two proofs that DO make a 3-vector the family's are
            asserted beside it, so this cannot be satisfied by disabling the arm."
    (let [foreign   [:opaque :app/not-a-resource {:account-id 42}]
          app-event [:app/save :user {:name "alice"}]
          sensitive (sk :rf.scope/global :secret/article {:auth-token secret})
          gone      [:rf.scope/global :cleared/article {:auth-token secret}]
          record    (record-with
                      [(event :rf.fx/handled
                              {:rf.frame/id :test/rt :frame :test/rt
                               :rf.fx/id :app/custom
                               :rf.fx/args {:rows [foreign] :dispatch app-event}})
                       ;; the REGISTRY proof: a registered owner's key in a slot
                       ;; the family never named still projects.
                       (event :rf.fx/handled
                              {:rf.frame/id :test/rt :frame :test/rt
                               :rf.fx/id :app/audit
                               :rf.fx/args {:app/anything [sensitive]}})
                       ;; the NAMED proof: an UNREGISTERED key the runtime itself
                       ;; named still FAILS CLOSED (a `clear-resource` / hot
                       ;; reload leaves genuine keys in captured records).
                       (event :rf.fx/handled
                              {:rf.frame/id :test/rt :frame :test/rt
                               :rf.fx/id :rf.resource/cancel-timers
                               :rf.fx/args {:frame-id :test/rt
                                            :resource/keys [gone]}})])
          projected (epoch/projected-record record)
          [custom audit cancel] (:trace-events projected)]
      (testing "the foreign lookalikes ride verbatim"
        (is (= foreign (first (:rows (:rf.fx/args (:tags custom)))))
            "an app 3-vector with an unregistered keyword at position 1")
        (is (= app-event (:dispatch (:rf.fx/args (:tags custom))))
            "and an ordinary 3-element event vector, which has the same shape")
        (is (not (:sensitive? (:tags custom)))
            "so the row is NOT stamped :sensitive? — a tool reading this row
             would otherwise believe it had seen a resource"))
      (testing "the REGISTRY proof: a registered owner's key projects from a
                slot no family name reaches"
        (let [[pscope rid pparams] (first (:app/anything (:rf.fx/args (:tags audit))))]
          (is (= :secret/article rid) "the resource-id survives for attribution")
          (is (redacted-component? pscope))
          (is (redacted-component? pparams))
          (is (true? (:sensitive? (:tags audit))))))
      (testing "the NAMED proof: an unregistered key under a `resource`-namespaced
                key still fails closed"
        (let [[pscope rid pparams] (first (:resource/keys (:rf.fx/args (:tags cancel))))]
          (is (= :cleared/article rid))
          (is (redacted-component? pscope) "scope redacted though the owner is gone")
          (is (redacted-component? pparams))
          (is (true? (:sensitive? (:tags cancel))))))
      (is (= [] (secret-leak-paths projected))
          "and no raw secret survives anywhere in the record"))))

(deftest fx-carrier-leaves-app-owned-scope-maps-verbatim
  (testing "rf2-425mm — a `:scope` ENTRY is the family's only inside a payload
            the runtime BUILT. An app's own `:scope` — an ordinary English word
            the FX family uses for its own data — rides verbatim; the runtime's
            read continuation payload and its MUTATION execute payload (which
            carries a free `:scope` with NO `:resource/key` beside it, the case
            rf2-425mm ruled must not be gated on a sibling key) both still fail
            closed."
    (let [app-scope {:tenant "alice"}
          key1      (sk session-scope :derived/profile {:slug "me"})
          read-args {:on-success [:rf.resource.internal/succeeded
                                  {:work/id      [:rf.work/resource key1 1]
                                   :resource/key key1
                                   :scope        session-scope
                                   :generation   1}]}
          mut-args  {:on-success [:rf.mutation.internal/succeeded
                                  {:instance-id :m/save-1
                                   :mutation-id :m/save
                                   :work/id     [:rf.work/resource
                                                 [:rf.mutation :m/save-1] 3]
                                   :scope       session-scope
                                   :generation  3}]}
          app-row*  (event :rf.fx/handled
                           {:rf.frame/id :test/rt :frame :test/rt
                            :rf.fx/id :rf.http/managed
                            :rf.fx/args {:request {:method :post
                                                   :scope  app-scope
                                                   :body   {:x 1}}}})
          read-row* (event :rf.fx/handled
                           {:rf.frame/id :test/rt :frame :test/rt
                            :rf.fx/id :rf.http/managed :rf.fx/args read-args})
          mut-row*  (event :rf.fx/handled
                           {:rf.frame/id :test/rt :frame :test/rt
                            :rf.fx/id :rf.http/managed :rf.fx/args mut-args})
          project1  (fn [row] (epoch/projected-record (record-with [row])))]
      (testing "the app's own request map is untouched"
        (let [tags (:tags (first (:trace-events (project1 app-row*))))]
          (is (= {:method :post :scope app-scope :body {:x 1}} (:request (:rf.fx/args tags)))
              "byte-for-byte, :scope map included")
          (is (not (:sensitive? tags))
              "and the row is NOT stamped :sensitive?")))
      (testing "the READ continuation payload's :scope still fails closed"
        (is (= [session-scope] (carrier-scopes (record-with [read-row*])))
            "FIXTURE — the raw payload carries the resolver's identity map")
        (let [projected (project1 read-row*)]
          (is (every? tokenized-scope? (carrier-scopes projected))
              "tier keyword verbatim, identity map tokenized")
          (is (true? (:sensitive? (:tags (first (:trace-events projected))))))))
      (testing "and so does the MUTATION execute payload's, which has no
                :resource/key to gate on — the work-id is what proves it"
        (is (= [session-scope] (carrier-scopes (record-with [mut-row*])))
            "FIXTURE — the raw mutation payload carries it too")
        (let [projected (project1 mut-row*)]
          (is (every? tokenized-scope? (carrier-scopes projected))
              "gating on a sibling key here would have fixed reads and left
               mutations leaking")
          (is (true? (:sensitive? (:tags (first (:trace-events projected))))))))
      (is (= [] (mapcat secret-leak-paths
                        (map project1 [app-row* read-row* mut-row*])))
          "no raw identity survives anywhere in any of the three records"))))

(deftest fx-carrier-reply-payload-needs-the-canonical-reply-marker
  (testing "rf2-xx4ty — `:value` / `:params` are the family's only inside a
            CANONICAL read reply (`:rf.reply/work-kind :resource`, the marker
            every reply the read-continuation substrate builds carries). A
            sibling `:resource/key` says WHOSE the data would be, not that these
            two ordinary words are the family's at all — so an app map carrying
            a genuine sensitive key beside its own `:value` / `:params` keeps
            them, while the key one slot over still tokenizes."
    (let [key1      (sk session-scope :derived/profile reply-params)
          unmarked  {:resource/key key1
                     :value        {:public true}
                     :params       {:format :csv}}
          marked    (read-reply key1 session-scope reply-value)
          record    (record-with
                      [(event :rf.fx/handled
                              {:rf.frame/id :test/rt :frame :test/rt
                               :rf.fx/id :app/custom :rf.fx/args unmarked})
                       (event :rf.fx/handled
                              {:rf.frame/id :test/rt :frame :test/rt
                               :rf.fx/id :dispatch
                               :rf.fx/args (conj read-reply-target marked)})])
          projected (epoch/projected-record record)
          [custom reply-row] (:trace-events projected)
          proj-un   (:rf.fx/args (:tags custom))]
      (testing "the UNMARKED map: the key tokenizes, its foreign neighbours do not"
        (is (= {:public true} (:value proj-un))
            "the app's :value rides verbatim")
        (is (= {:format :csv} (:params proj-un))
            "and so do the app's :params")
        (is (redacted-component? (first (:resource/key proj-un)))
            "while the genuine sensitive key beside them still tokenizes")
        (is (= :derived/profile (second (:resource/key proj-un)))
            "with its resource-id intact")
        (is (true? (:sensitive? (:tags custom)))
            "the row IS stamped — a key redacted on it"))
      (testing "the MARKED reply still tokenizes both slots"
        (let [r (first (carrier-replies projected))]
          (is (redacted-component? (:value r)))
          (is (redacted-component? (:params r)))
          (is (true? (:sensitive? (:tags reply-row))))))
      (is (= [] (secret-leak-paths projected))
          "and nothing raw survives anywhere in the record"))))

;; ===========================================================================
;; (rf2-ko5lm) the OTHER half of the read reply: the owner that makes NO COARSE
;; CLAIM and declares PROJECTION-RELATIVE paths instead.
;; ===========================================================================
;;
;; §(rf2-xx4ty) above reads the reply's owner through `row-owner-redacts?` —
;; `whole-entry-disposition`, the COARSE root-prop `:sensitive?` / `:large?`
;; claim. That is the right grain for tokenizing a WHOLE slot, and it is the
;; only claim `:derived/profile` makes. But it is not the only claim a resource
;; CAN make, and it is not the common one:
;;
;;   (rf/reg-resource :declared/profile
;;     {:sensitive [[:data :email]] :large [[:data :avatar]]}
;;     …)
;;
;; declares no coarse prop at all, so `whole-entry-disposition` is `:serialize`,
;; `row-owner-redacts?` is false, and the reply arm never fires. The DECODED
;; RESPONSE BODY carrying that declared `:email` rode `:rf.fx/args` and
;; `:rf.event/fx` VERBATIM — while the very same bytes, landed in the durable
;; entry one commit earlier, redact off-box because `reconcile-registry` lowered
;; `[:data :email]` to `[:rf.runtime/resources :entries <key-id> :data :email]`
;; and the epoch walk reads that registry. One value, two carriers, one rule
;; applied: the rf2-irwsq shape again, this time between the DURABLE entry and
;; the CONTINUATION echo of it.
;;
;; MUTATIONS DO NOT HAVE THIS HOLE, and the reason names the repair. Both
;; mutation settle sites wrap their reply in
;; `classification/redact-continuation-reply`, which derives the paths from the
;; mutation spec's own projection-relative declaration and substitutes them in
;; the SAME construction step (rf2-825mzj). Reads had no counterpart. The read
;; reply's carrier shape is the mutation reply's carrier shape — `:value` beside
;; `:params` beside `:scope` — so the counterpart is that same function, read at
;; the EGRESS projector instead of at the source (the read half must not redact
;; at source: the app's own continuation handler is entitled to the decoded
;; body, and `:include-sensitive?` must still show it).
;;
;; THE GRAIN, since taking the wrong one is how this family keeps regressing.
;; This arm is DECLARATION-conditional: it fires on the paths the owner
;; declared, and on nothing else. Not unconditional (an owner that declares
;; nothing rides verbatim — the plain-owner control below), and not
;; coarse-owner-conditional (that is precisely the read that misses a
;; `:serialize` owner's declaration). It is the grain of the declaration itself,
;; which is what makes the durable carrier and the continuation carrier agree
;; by construction — the same reason rf2-1zc33 gave `:scope` the family's grain
;; and rf2-xx4ty gave `:value` / `:params` the owner's.
;;
;; RESIDUE, filed not fixed (rf2-dl7bz): a `:params`-rooted declaration on a
;; `:serialize` owner still rides raw inside the sibling `:resource/key`, whose
;; trace projection is coarse-only by documented decision
;; (`ssr/project-scoped-key` — "the trace / tool egress callers pass
;; `:serialize` through verbatim"). This section closes the reply's copy of
;; those params; the key's copy is a different carrier on every family row and
;; is its own bead.

(def ^:private declared-reply-params
  "The canonical params of the declared-owner read. Deliberately PLAIN:
  `:declared/profile` declares nothing under `:params`, and this section's
  acceptance scan is whole-record, so a secret here would be caught in the
  sibling `:resource/key` (see the RESIDUE note above) and prove nothing about
  the reply slot the section owns. The params axis gets its own deterministic
  probe below, with its own marker."
  {:slug plain-slug})

(def ^:private declared-reply-value
  "The DECODED RESPONSE BODY of the declared-owner read. Three fields, three
  outcomes: `:email` is declared `:sensitive` and must redact to the sentinel,
  `:avatar` is declared `:large` and must become the size marker, and
  `:display-name` is declared as NEITHER and must ride verbatim — which is what
  proves the projection is per-PATH and not a whole-slot tokenization wearing a
  declaration as its trigger."
  {:email        (str secret "@example.com")
   :avatar       "0123456789abcdef"
   :display-name "Ada"})

(defn- drive-declared-reply-to-read!
  "The `drive-reply-to-read!` of §(rf2-xx4ty), against the DECLARED-slot owner
  instead of the coarse `:sensitive?` one. Drives a REAL
  `[:rf.resource/ensure … :reply-to …]`, replays the terminal reply through the
  runtime's own internal reply event, then drives a SECOND ensure that finds the
  entry fresh — so one call produces BOTH continuation paths (the async accepted
  fan-out, `:cache-hit? false`, and the fresh-skip immediate dispatch,
  `:cache-hit? true`).

  No app-db classification is needed here: `:declared/profile`'s scope is
  `:rf.scope/global`, so nothing about this read reaches the app-db axis and any
  surviving copy of the secret came off a trace carrier — the axis this section
  owns. `fx/reg-fx` (the plain fn, not the macro) for the reason
  `drive-real-cascade!` documents."
  []
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/read-loaded (fn [_ _ev] {}))
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :declared/profile :params declared-reply-params
                        :owner    real-owner :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (:on-success @captured) {:status :ok :value declared-reply-value})
                      {:frame :test/rt})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :declared/profile :params declared-reply-params
                        :owner    [:app :reader 2] :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/epoch-history :test/rt)))

;; ---------------------------------------------------------------------------
;; (1) THE ACCEPTANCE ARM — a REAL reply-to read, both continuation paths
;; ---------------------------------------------------------------------------

(deftest real-declared-reply-to-read-leaks-no-declared-slot-into-fx-carriers
  (testing "rf2-ko5lm — `projected-record` over the records a REAL
            `[:rf.resource/ensure … :reply-to …]` settles for an owner whose
            ONLY claim is a projection-relative declaration must carry the
            declared body slot at ZERO paths, on BOTH continuation paths.
            Before the repair each carried it at two: `:value :email` under
            `:rf.fx/args` and again under `:rf.event/fx`."
    (let [records (drive-declared-reply-to-read!)]
      (doseq [[label cache-hit?] [["async settle" false] ["fresh-skip cache hit" true]]]
        (testing label
          (let [raw       (record-carrying-reply records cache-hit?)
                projected (epoch/projected-record raw)]

            (testing "FIXTURE — the producer really put a decoded body on the fx
                      carriers, and the owner really makes no coarse claim"
              (is (some? raw) "the continuation reached an fx carrier at all")
              (is (seq (carrier-replies raw)) "and the reply map is findable on it")
              (is (every? #(= declared-reply-value (:value %)) (carrier-replies raw))
                  "every carrier's reply carries the RAW decoded body")
              (is (seq (secret-leak-paths raw))
                  "the unprojected record leaks — the projector's input is the
                   runtime's own output, not an invented tag map"))

            (testing "ACCEPTANCE — nothing raw survives anywhere in the projected
                      record"
              (is (= [] (secret-leak-paths projected))
                  "every leaking path is named here; before the repair this
                   printed the [:trace-events n :tags :rf.fx/args 1 :value :email]
                   shape, once per carrier"))

            (testing "and the projection is PER-PATH — the declared slots move,
                      their undeclared sibling does not"
              (doseq [r (carrier-replies projected)]
                (is (= :rf/redacted (:email (:value r)))
                    "the `:sensitive`-declared slot carries the sentinel")
                (is (elision/marker? (:avatar (:value r)))
                    "the `:large`-declared slot carries the size marker")
                (is (= "Ada" (:display-name (:value r)))
                    "and the slot the owner declared NEITHER axis for rides
                     verbatim — the whole point of a path declaration")))

            (testing "the whole reply still reads as a reply"
              (let [r (first (carrier-replies projected))]
                (is (= :declared/profile (:resource r))
                    "the resource id rides verbatim")
                (is (= cache-hit? (:cache-hit? r))
                    "and the cache-hit disposition")
                (is (= :ok (:status r)) "and the status")
                (is (= declared-reply-params (:params r))
                    "and the params, which this owner declared nothing under")
                (is (= :rf.scope/global (:scope r))
                    "and `:rf.scope/global` is untouched — a scalar scope is a
                     structural fact, not a payload")
                (is (= [:rf.scope/global :declared/profile declared-reply-params]
                       (:resource/key r))
                    "and the whole scoped key, since a `:serialize` owner's key
                     rides verbatim")))))))))

;; ---------------------------------------------------------------------------
;; (2) the same shape assembled — deterministic, and it names the paths
;; ---------------------------------------------------------------------------

(deftest fx-carrier-declared-reply-slots-redact-on-both-carriers
  (testing "rf2-ko5lm — the projector, over the exact reply the runtime builds.
            One declared slot per carrier, both redacted; and the foreign
            `:value` on the same effect vector rides untouched, because the
            resource family speaks only for what it planted."
    (let [k1        (sk :rf.scope/global :declared/profile declared-reply-params)
          reply     (read-reply k1 :rf.scope/global declared-reply-value)
          projected (epoch/projected-record (reply-carrier-record reply))
          replies   (carrier-replies projected)
          [handled do-fx] (:trace-events projected)]
      (is (= 2 (count replies))
          "one reply per carrier — the two the bead named")
      (is (every? #(and (= :rf/redacted (:email (:value %)))
                        (elision/marker? (:avatar (:value %)))
                        (= "Ada" (:display-name (:value %))))
                  replies)
          "each carrier redacts, elides, and rides the three slots identically")
      (is (= [] (secret-leak-paths projected))
          "and nothing raw survives anywhere in the record")
      (testing "the FOREIGN :value on the same effect vector is untouched"
        (is (= [:rf.resource/commit-generation {:value 1}]
               (first (:rf.event/fx (:tags do-fx))))
            "a map with a :value and no reply marker is nobody's business here")
        (is (= :app/read-loaded (first (:rf.fx/args (:tags handled))))
            "as is the continuation TARGET — a tool still reads which event ran")
        (is (true? (:sensitive? (:tags handled)))
            "but the row IS stamped :sensitive?")))))

(deftest fx-carrier-declared-reply-params-redact-through-the-same-declaration
  (testing "rf2-ko5lm — the PARAMS axis of the same declaration surface. A
            `:params`-rooted declaration redacts the reply's `:params` slot
            through the identical `carrier-decl-paths` re-rooting the mutation
            reply uses, and leaves its undeclared sibling alone.

            The sibling `:resource/key` still carries the same params raw — a
            `:serialize` owner's key rides verbatim at trace egress by
            documented decision (`ssr/project-scoped-key`). That residue is
            filed as rf2-dl7bz and is a different carrier on every family row;
            it is deliberately NOT asserted here, so this test does not have to
            change when that bead lands."
    (let [params    {:account "acct-9911" :slug plain-slug}
          k1        (sk :rf.scope/global :declared/params-owner params)
          reply     (read-reply k1 :rf.scope/global {:ok true})
          projected (epoch/projected-record (reply-carrier-record reply))
          r         (first (carrier-replies projected))]
      (is (= :rf/redacted (:account (:params r)))
          "the `[:params :account]` declaration reaches the reply's :params")
      (is (= plain-slug (:slug (:params r)))
          "and its undeclared sibling rides verbatim")
      (is (= {:ok true} (:value r))
          "the body is untouched — this owner declares nothing under :data"))))

;; ---------------------------------------------------------------------------
;; (3) THE TWO-SIDED CONTROL — over-redaction must fail as loudly as leaking
;; ---------------------------------------------------------------------------

(deftest fx-carrier-keeps-an-undeclared-owners-reply-byte-identical
  (testing "rf2-ko5lm guard — an owner that makes NEITHER claim (no coarse
            `:sensitive?`, no projection-relative declaration) must ride its
            continuation reply BYTE-IDENTICAL through both carriers and must not
            stamp the row sensitive. This is the side that proves the arm reads
            the DECLARATION rather than the reply's shape: the same `:value` and
            `:params` slots that redact for `:declared/profile` are fully
            readable here."
    (let [k1        (sk :rf.scope/global :plain/article {:slug plain-slug})
          reply     (read-reply k1 :rf.scope/global declared-reply-value)
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
          "an undeclared-owner reply row is NOT stamped sensitive")
      (is (not (:sensitive? (:tags do-fx)))
          "on either carrier"))))

(deftest fx-carrier-declared-arm-leaves-the-fx-familys-own-value-verbatim
  (testing "rf2-ko5lm guard — the other half of the over-redaction control. A
            map carrying `:value` / `:params` WITHOUT the canonical reply marker
            is not a reply, whatever owner its neighbours name, so a declaration
            can never reach it. The app's own managed-HTTP args and the
            runtime's generation counter are the two shapes that have caught
            every name-only arm in this family."
    (let [k1        (sk :rf.scope/global :declared/profile declared-reply-params)
          unmarked  {:resource/key k1 :value declared-reply-value}
          record    (record-with
                      [(event :rf.fx/handled
                              {:rf.frame/id :test/rt :frame :test/rt
                               :rf.fx/id :app/custom :rf.fx/args unmarked})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= declared-reply-value (:value (:rf.fx/args tags)))
          "no marker, no reply, no declaration — the map rides byte-for-byte")
      (is (not (:sensitive? tags))
          "and the row is NOT stamped :sensitive?"))))

;; ---------------------------------------------------------------------------
;; (4) the trusted-local boundary — the redaction is the off-box DEFAULT
;; ---------------------------------------------------------------------------

(deftest trusted-local-include-sensitive-keeps-raw-declared-reply
  (testing "rf2-ko5lm — the trusted-local `:include-sensitive?` opt-in keeps the
            raw declared slots (the local-raw boundary — the redaction is the
            off-box default, not a strip). Load-bearing for the same reason the
            coarse arm's opt-in is: a `:reply-to` continuation is how a workflow
            reads a resource, and a local tool that could not see the declared
            field could not debug the workflow."
    (let [k1        (sk :rf.scope/global :declared/profile declared-reply-params)
          reply     (read-reply k1 :rf.scope/global declared-reply-value)
          projected (epoch/projected-record (reply-carrier-record reply)
                                            {:include-sensitive? true})]
      (is (= [declared-reply-value declared-reply-value]
             (mapv :value (carrier-replies projected)))
          "every carrier's raw :value rides with :include-sensitive?")
      (is (= (conj read-reply-target reply)
             (:rf.fx/args (:tags (first (:trace-events projected)))))
          "and so does the rest of the event vector it sits in"))))

;; ===========================================================================
;; (rf2-zaopo) the same declaration surface on an INFINITE FEED: the merged
;; ITEM list under `:value`, which an EXACT path match cannot reach.
;; ===========================================================================
;;
;; §(rf2-ko5lm) above closed the read reply for a SCALAR resource: the owner's
;; `[:data :email]` declaration re-roots onto the reply's `:value`, giving
;; `[:value :email]`, and `classification/redact-with-paths` matches that path
;; exactly. For an INFINITE FEED the same declaration reached nothing.
;;
;; `events/infinite-reply-value` delivers the MERGED / flattened ITEM list as
;; `:value` (rf2-c64uiz — both the fresh-skip cache hit and the async page-0
;; settle deliver that one shape), so the runtime path is `[:value <i> :email]`
;; while the declaration is `[:value :email]`. No fork, no match, and the
;; declared field rode the fx carriers verbatim.
;;
;; THE DURABLE SIDE ALREADY FORKS, which is what made this a disagreement
;; rather than a uniform limitation. `classification/project-entry-data` walks
;; the feed's page vector through `elide-wire-value`, whose `fork-index-paths`
;; matches an index-free declaration against the indexed runtime path on EVERY
;; page (`ssr-infinite-feed-redacts-sensitive-page-field-per-page` in the
;; resources suite pins it). So a feed's declared field redacted in the durable
;; entry and rode raw in the continuation echo of it — the rf2-irwsq shape
;; between two carriers of one value, one more time.
;;
;; THE SPELLING, settled here rather than invented. A feed has no "each item"
;; wildcard syntax, and it needs none: the index-free declaration IS that
;; spelling, because it is already what the DURABLE side means by
;; `[:data :email]` on a page vector. The repair adds no vocabulary — it makes
;; the carrier honour the one the durable side established
;; (`redact-with-paths`'s `:index-free?` opt, which `redact-continuation-reply`
;; passes because a projection-relative declaration re-rooted onto a carrier is
;; exactly the index-free kind).
;;
;; GRAIN. A positional index is unambiguously a collection coordinate, never a
;; named slot, so riding one can never float a declaration past a named slot
;; (`fork-index-paths`' own argument). The fork therefore widens matching by
;; exactly "each element of a declared positional container" and nothing else —
;; which the two controls below are here to prove.

(def ^:private declared-feed-params
  "The canonical params of the declared-feed read. Deliberately PLAIN: this
  section owns the `:data` axis, and the params axis has its own probe in
  §(rf2-ko5lm)."
  {:filter :recent})

(defn- feed-item
  "One item of the declared feed. Three fields, three outcomes — the same
  redact / elide / ride triple `declared-reply-value` carries for a scalar
  resource, so the feed proves the index-free match is per-PATH rather than a
  whole-slot tokenization that a declaration merely triggers."
  [tag]
  {:email        (str secret "-" tag "@example.com")
   :avatar       (str "0123456789abcdef" tag)
   :display-name (str "Ada-" tag)})

(def ^:private declared-feed-page
  "One ENVELOPED terminal page (`:page-info :next` nil ⇒ no further pages) of
  TWO items. Enveloped rather than a bare item vector on purpose: the merged
  `:value` is then unambiguously distinct from both the page and the durable
  page vector, so an assertion on it cannot pass by accident."
  {:items     [(feed-item "a") (feed-item "b")]
   :page-info {:next nil}})

(def ^:private declared-feed-items
  "The MERGED item list `infinite-reply-value` delivers under the reply's
  `:value` — the flattened page, which is what the declaration must reach."
  (:items declared-feed-page))

(defn- drive-feed-reply-to-read!
  "The `drive-declared-reply-to-read!` of §(rf2-ko5lm), against an INFINITE
  FEED. An infinite ensure addresses the internal PAGE reply handler, so the
  captured `:on-success` settles a page; the second ensure then finds the feed
  fresh and dispatches the cache-hit continuation immediately. One call
  produces BOTH continuation paths, and rf2-c64uiz pins that both deliver the
  SAME merged-items `:value`. `resource-id` selects the declared feed or its
  undeclared control, over the IDENTICAL page — so the declaration is the only
  difference between the two runs."
  [resource-id]
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/read-loaded (fn [_ _ev] {}))
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource resource-id :params declared-feed-params
                        :owner    real-owner :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (:on-success @captured) {:status :ok :value declared-feed-page})
                      {:frame :test/rt})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource resource-id :params declared-feed-params
                        :owner    [:app :reader 2] :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/epoch-history :test/rt)))

;; ---------------------------------------------------------------------------
;; (1) THE ACCEPTANCE ARM — a REAL reply-to feed read, both continuation paths
;; ---------------------------------------------------------------------------

(deftest real-declared-feed-reply-to-read-leaks-no-declared-item-slot
  (testing "rf2-zaopo — `projected-record` over the records a REAL
            `[:rf.resource/ensure … :reply-to …]` settles for an INFINITE FEED
            whose only claim is a projection-relative `[:data …]` declaration
            must carry the declared item field at ZERO paths, on BOTH
            continuation paths. Before the repair each carried it once per ITEM
            per carrier — four paths for this two-item feed."
    (let [records (drive-feed-reply-to-read! :declared/feed)]
      (doseq [[label cache-hit?] [["async page-0 settle" false]
                                  ["fresh-skip cache hit" true]]]
        (testing label
          (let [raw       (record-carrying-reply records cache-hit?)
                projected (epoch/projected-record raw)]

            (testing "FIXTURE — the producer really put the MERGED ITEM LIST on
                      the fx carriers, so the assertions below are not passing
                      over a shape that never occurred"
              (is (some? raw) "the continuation reached an fx carrier at all")
              (is (seq (carrier-replies raw)) "and the reply map is findable on it")
              (is (every? #(= declared-feed-items (:value %)) (carrier-replies raw))
                  "every carrier's reply carries the RAW merged item list — not
                   the enveloped page, not the durable page vector")
              (is (seq (secret-leak-paths raw))
                  "the unprojected record leaks — the projector's input is the
                   runtime's own output, not an invented tag map"))

            (testing "ACCEPTANCE — nothing raw survives anywhere in the projected
                      record"
              (is (= [] (secret-leak-paths projected))
                  "every leaking path is named here; before the repair this
                   printed the [:trace-events n :tags :rf.fx/args 1 :value i
                   :email] shape, once per item per carrier"))

            (testing "and the index-free match is PER-PATH and PER-ITEM — the
                      declared slots move in EVERY item, their undeclared
                      sibling moves in none"
              (doseq [r (carrier-replies projected)]
                (is (= 2 (count (:value r)))
                    "the merged list keeps its length — projection neither drops
                     nor invents an item")
                (doseq [item (:value r)]
                  (is (= :rf/redacted (:email item))
                      "the `:sensitive`-declared field carries the sentinel in
                       this item")
                  (is (elision/marker? (:avatar item))
                      "the `:large`-declared field carries the size marker"))
                (is (= ["Ada-a" "Ada-b"] (mapv :display-name (:value r)))
                    "and the field the owner declared NEITHER axis for rides
                     verbatim in every item — the whole point of a path
                     declaration")))

            (testing "the whole reply still reads as a reply"
              (let [r (first (carrier-replies projected))]
                (is (= :declared/feed (:resource r)) "the resource id rides verbatim")
                (is (= cache-hit? (:cache-hit? r)) "and the cache-hit disposition")
                (is (= :ok (:status r)) "and the status")
                (is (= declared-feed-params (:params r))
                    "and the params, which this feed declared nothing under")
                (is (= :rf.scope/global (:scope r))
                    "and `:rf.scope/global` is untouched")))))))))

;; ---------------------------------------------------------------------------
;; (2) THE TWO-SIDED CONTROL — over-redaction must fail as loudly as leaking
;; ---------------------------------------------------------------------------

(defn- reply-carrier-rows
  "Every trace row of `record` whose fx carrier slots carry a read-continuation
  reply — the rows the reply arm speaks for, and the only ones a declaration
  control can hold byte-identical. The rest of a real feed cascade carries the
  INTERNAL `:rf.resource.internal/page-succeeded` event, whose own registration
  classification redacts its args on every read declared or not, so a
  whole-record comparison would be measuring that instead."
  [record]
  (filterv (fn [ev] (seq (carrier-replies {:trace-events [ev]})))
           (:trace-events record)))

(deftest fx-carrier-keeps-an-undeclared-feeds-merged-items-byte-identical
  (testing "rf2-zaopo guard — an INFINITE FEED that declares NEITHER axis must
            ride its merged item list BYTE-IDENTICAL through both carriers and
            must not stamp those rows sensitive. This is the side that proves
            the index-free fork reads the DECLARATION rather than the reply's
            shape: the identically-named `:email` / `:avatar` fields that
            redact for `:declared/feed` are fully readable here."
    (let [raw       (record-carrying-reply (drive-feed-reply-to-read! :plain/feed) false)
          projected (epoch/projected-record raw)]
      (is (some? raw) "FIXTURE — the plain feed's continuation reached a carrier")
      (is (= 2 (count (reply-carrier-rows raw)))
          "FIXTURE — both carriers are present, as they are for the declared feed")
      (is (= (mapv :tags (reply-carrier-rows raw))
             (mapv :tags (reply-carrier-rows projected)))
          "every carrier tag rides byte-identical — merged items, params, scope
           and scoped key included")
      (is (every? #(= declared-feed-items (:value %)) (carrier-replies projected))
          "and the merged item list itself is readable field for field")
      (is (not-any? #(:sensitive? (:tags %)) (reply-carrier-rows projected))
          "an undeclared feed's continuation rows are NOT stamped sensitive"))))

(deftest fx-carrier-index-free-fork-does-not-reach-an-undeclared-nested-slot
  (testing "rf2-zaopo guard — the other half. Riding a positional index must
            not let a declaration FLOAT: `[:data :email]` names a field of each
            ITEM, so an `:email` sitting one named slot DEEPER (inside an
            item's own undeclared sub-map) is a different position and must
            survive. Without this side, `[:value :email]` matching
            `[:value 0 :meta :email]` would read as a pass."
    (let [k1        (sk :rf.scope/global :declared/feed declared-feed-params)
          items     [{:email        (str secret "-top@example.com")
                      :display-name "Ada"
                      :meta         {:email (str secret "-nested@example.com")}}]
          reply     (read-reply k1 :rf.scope/global items)
          projected (epoch/projected-record (reply-carrier-record reply))
          item      (first (:value (first (carrier-replies projected))))]
      (is (= :rf/redacted (:email item))
          "the declared field, one index down, redacts")
      (is (= (str secret "-nested@example.com") (get-in item [:meta :email]))
          "the same-named field one NAMED slot deeper is a different position
           and rides verbatim")
      (is (= "Ada" (:display-name item))
          "and the undeclared sibling is untouched"))))

;; ---------------------------------------------------------------------------
;; (3) the trusted-local boundary — the redaction is the off-box DEFAULT
;; ---------------------------------------------------------------------------

(deftest trusted-local-include-sensitive-keeps-raw-declared-feed-items
  (testing "rf2-zaopo — the trusted-local `:include-sensitive?` opt-in keeps the
            raw declared item fields, exactly as it does for the scalar reply.
            A feed's `:reply-to` continuation is how a workflow reads a page,
            and a local tool that could not see the declared field could not
            debug the workflow."
    (let [k1        (sk :rf.scope/global :declared/feed declared-feed-params)
          reply     (read-reply k1 :rf.scope/global declared-feed-items)
          projected (epoch/projected-record (reply-carrier-record reply)
                                            {:include-sensitive? true})]
      (is (= [declared-feed-items declared-feed-items]
             (mapv :value (carrier-replies projected)))
          "every carrier's raw merged item list rides with :include-sensitive?"))))

;; ===========================================================================
;; (rf2-wd9im audit #7013, and with it rf2-xx4ty's own carrier) NON-MAP
;; CANONICAL PARAMS — the shape read under-recognised a legal scoped key.
;; ===========================================================================
;;
;; rf2-wd9im replaced the projector's slot-NAME roster with a shape read, so a
;; scoped key sitting in a slot nobody had enumerated (`:blocking` /
;; `:identities`, an embedded `:work/id`) projects through its owner exactly as
;; a named slot's keys do. The shape it read was
;; `[<scope> <resource-id keyword> <params MAP>]`, and the `map?` at position 2
;; was the whole discrimination: `:owner [:app :l 1]` and
;; `:cause [:mutation :m/save 7]` wear the same skeleton and MUST ride verbatim.
;;
;; But `:params-schema` is REQUIRED and free. `[:vector :string]` is an ordinary
;; schema, and the registrar validates + canonicalizes params against whatever
;; the owner declared — so a REGISTERED owner's canonical params are legally a
;; vector, a scalar, or nil. Such a key wore the skeleton and failed the only
;; proof, so it fell through the recursive walk as a bag of structural scalars:
;; owner-aware projection never ran, the row was NOT stamped `:sensitive?`, and
;; a `:sensitive?` owner's resolved scope + canonical params egressed RAW —
;; under `:blocking` / `:identities`, inside every `:work/id`, and (the
;; rf2-xx4ty surface) inside a `:reply-to` read continuation riding
;; `:rf.fx/args` / `:rf.event/fx`, one slot from the `:value` and `:params` that
;; DID tokenize because the reply's owner read never needed the params shape.
;;
;; THE SECOND PROOF is the resource REGISTRY — the family's own authority
;; answering "is this one of mine?", which `carrier-family-value?` already read
;; one carrier out for exactly this question. It is not a roster and cannot rot,
;; and it says nothing about `:owner`'s `:l` or `:cause`'s `:m/save`: a MUTATION
;; id is not in the RESOURCE registrar. The controls below assert that
;; explicitly, including on a `:branch` of THREE route ids — the structural
;; 3-vector a bare "redact any vector-of-vectors" guard could never have kept.

(def ^:private vector-secret
  "The secret carried by a NON-MAP canonical params value. Distinct from
  `secret` so a failing path names which class leaked."
  (str secret "-vector"))

(def ^:private vector-params
  "Canonical params of a `[:vector :string]` owner — a legal params value with
  no MAP anywhere in it, which is exactly what the old shape read could not
  recognise."
  [vector-secret])

;; ---------------------------------------------------------------------------
;; (1) family rows — the unnamed plan-membership slots and the embedded work-id
;; ---------------------------------------------------------------------------

(deftest off-box-redacts-non-map-param-keys-in-unnamed-plan-slots
  (testing "rf2-wd9im audit #7013 — a :rf.resource/route-plan row's :blocking /
            :identities carrying a :sensitive? owner's key whose canonical
            params are a VECTOR must tokenize per key. Before the repair the key
            failed the params `map?` proof, fell through the recursive walk as
            structural scalars, and the raw scope + params rode out with the row
            unstamped."
    (let [k1        (sk :rf.scope/global :secret/vector-params vector-params)
          k2        (sk :rf.scope/global :secret/vector-params
                        [(str vector-secret "-2")])
          record    (record-with
                      [(event :rf.resource/route-plan (route-plan-tags [k1] [k1 k2]))])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))
          [bscope brid bparams] (first (:blocking tags))]
      (testing ":blocking tokenizes per key"
        (is (= :secret/vector-params brid) "the resource-id (position 1) survives")
        (is (redacted-component? bscope) "the scope is tokenized")
        (is (redacted-component? bparams) "the VECTOR params are tokenized"))
      (testing ":identities no longer keeps per-key distinctness for a sensitive
                owner (rf2-hzcv8) — the token is content-free, and VECTOR params
                take the same rule as map params"
        (is (= 2 (count (:identities tags))))
        (is (every? #(redacted-component? (nth % 2)) (:identities tags)))
        (is (apply = (map #(nth % 2) (:identities tags)))
            "two distinct vector-params keys produce ONE token"))
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (testing "the plan's structural attribution still rides verbatim"
        (is (= [:r/root :r/article] (:branch tags)))
        (is (= 1 (:removed tags)) "the INT count is untouched"))
      (testing "NO raw secret survives anywhere in the projected record"
        (is (= [] (secret-leak-paths projected)))))))

(deftest unnamed-slot-non-map-params-projects-identically-to-named-slot
  (testing "rf2-wd9im audit #7013 anti-drift — the SAME vector-params keys under
            a NAMED slot (:matched, projected BY POSITION and therefore never
            affected by the params shape) and under the UNNAMED :blocking /
            :identities must project IDENTICALLY. This is the assertion that
            reds hardest before the repair: the named slot tokenized while the
            unnamed one rode raw, on one row, for one key."
    (let [ks        [(sk :rf.scope/global :secret/vector-params vector-params)
                     (sk :rf.scope/global :secret/vector-params [(str vector-secret "-2")])]
          record    (record-with
                      [(event :rf.resource/route-plan
                              {:rf.frame/id :test/rt
                               :matched     ks     ; NAMED   -> position arm
                               :blocking    ks     ; UNNAMED -> shape arm
                               :identities  ks})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= (:matched tags) (:blocking tags))
          ":blocking projects exactly as the NAMED :matched does")
      (is (= (:matched tags) (:identities tags))
          ":identities projects exactly as the NAMED :matched does")
      (is (= [] (secret-leak-paths projected))))))

(deftest off-box-redacts-non-map-param-key-embedded-in-resource-work-id
  (testing "rf2-wd9im audit #7013 — the embedded work-id key is the SHARED path
            the audit names: `[:rf.work/resource <scoped-key> <generation>]`
            rides the majority of rows in the family and no roster names
            :work/id, so a vector-params key one level down inside it egressed
            raw on every one of them."
    (let [scoped-key (sk :rf.scope/global :secret/vector-params vector-params)
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
      (is (= :secret/vector-params (second embedded)) "the resource-id survives")
      (is (redacted-component? (first embedded)) "the embedded scope is tokenized")
      (is (redacted-component? (nth embedded 2)) "the embedded VECTOR params are tokenized")
      (is (= (:resource/key tags) embedded)
          "the embedded key projects exactly as the row's own :resource/key —
           the NAMED slot that was already right, which is what makes the
           mismatch the bug rather than a preference")
      (is (true? (:sensitive? tags)) "the row is stamped :sensitive?")
      (is (= [] (secret-leak-paths projected))))))

;; ---------------------------------------------------------------------------
;; (2) the two-sided controls — the registry proof must not over-redact
;; ---------------------------------------------------------------------------

(deftest off-box-keeps-plain-owner-non-map-param-plan-membership-verbatim
  (testing "rf2-wd9im audit #7013 guard — a PLAIN owner's vector-params keys
            ride VERBATIM. The widened recognition routes through the OWNER
            classification exactly as the map-params keys do, so recognising
            more keys buys no extra redaction."
    (let [k1        (sk :rf.scope/global :plain/vector-params ["welcome"])
          record    (record-with
                      [(event :rf.resource/route-plan (route-plan-tags [k1] [k1]))])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= [k1] (:blocking tags)) "a plain owner's :blocking rides verbatim")
      (is (= [k1] (:identities tags)) "a plain owner's :identities rides verbatim")
      (is (not (:sensitive? tags)) "a plain row is NOT stamped sensitive"))))

(deftest off-box-keeps-structural-three-vectors-verbatim-under-unnamed-slots
  (testing "rf2-wd9im audit #7013 guard — the NEGATIVE control the audit names.
            The family's other 3-element vectors wear the same positional
            skeleton as a scoped key and MUST ride verbatim: `:owner` (a view
            path), `:cause` (a mutation attribution triple), and a `:branch` of
            THREE route ids — the case a bare 'redact any 3-vector' or
            'redact any vector-of-vectors' guard could not have kept. None of
            their position-1 keywords is in the RESOURCE registrar (a MUTATION
            id is registered in a different registrar), which is precisely why
            the registry is a safe second proof."
    (let [record    (record-with
                      [(event :rf.resource/route-plan
                              {:rf.frame/id :test/rt
                               :owner       [:app :l 1]
                               :cause       [:mutation :m/save 7]
                               :branch      [:r/root :r/article :r/comments]
                               :nav-token   7})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= [:app :l 1] (:owner tags)) "a view path rides verbatim")
      (is (= [:mutation :m/save 7] (:cause tags))
          "a mutation attribution triple rides verbatim")
      (is (= [:r/root :r/article :r/comments] (:branch tags))
          "a THREE-id route branch rides verbatim")
      (is (= 7 (:nav-token tags)))
      (is (not (:sensitive? tags))
          "a row of purely structural vectors is NOT stamped sensitive"))))

(deftest trusted-local-include-sensitive-keeps-raw-non-map-param-keys
  (testing "rf2-wd9im audit #7013 — the redaction is the off-box DEFAULT, not an
            unconditional strip: the trusted-local :include-sensitive? opt-in
            keeps the raw vector-params plan membership and the raw embedded
            work-id key."
    (let [k1        (sk :rf.scope/global :secret/vector-params vector-params)
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


;; ---------------------------------------------------------------------------
;; (3) the FOREIGN CARRIER — rf2-xx4ty's own surface, one params shape over
;; ---------------------------------------------------------------------------

(deftest fx-carrier-non-map-param-key-projects-under-a-family-named-slot
  (testing "rf2-xx4ty / rf2-wd9im audit #7013 — inside an fx carrier the
            reply's `:value` / `:params` tokenized (the owner read never needed
            the params SHAPE), while the `:resource/key` beside them and the key
            embedded in `:rf.reply/work-id` rode RAW — the redact-and-leak-the-
            same-bytes shape this family keeps regressing into, now with the two
            halves inside ONE map."
    (let [k1        (sk :rf.scope/global :secret/vector-params vector-params)
          reply     (read-reply k1 :rf.scope/global {:email (str vector-secret "@example.com")})
          projected (epoch/projected-record (reply-carrier-record reply))
          replies   (carrier-replies projected)]
      (is (= 2 (count replies)) "one reply per carrier")
      (doseq [r replies]
        (is (redacted-component? (:value r)) "the decoded body tokenizes")
        (is (redacted-component? (:params r)) "the VECTOR params slot tokenizes")
        (is (= :secret/vector-params (second (:resource/key r)))
            "the sibling key's resource-id survives")
        (is (redacted-component? (nth (:resource/key r) 2))
            "and its VECTOR params component tokenizes")
        (is (redacted-component? (nth (second (:rf.reply/work-id r)) 2))
            "as does the key embedded in the reply's own work-id")
        (is (redacted-component?
              (nth (:rf.reply/resource-key (:correlation r)) 2))
            "as does the reply envelope's correlation key"))
      (is (= [] (secret-leak-paths projected))
          "nothing raw survives anywhere in the record"))))

(deftest fx-carrier-named-slot-fails-closed-for-an-unregistered-non-map-params-owner
  (testing "rf2-wd9im audit #7013 — `named?` exists so the fail-closed arm stays
            reachable for a genuine key whose owner was cleared or hot-reloaded
            away. Requiring a params `map?` on top of `named?` took that away:
            an UNREGISTERED owner's vector-params key under the family's own
            reserved `:resource/key` rode a carrier verbatim, which is the one
            case the projector is least entitled to trust."
    (let [gone      [:rf.scope/global :gone/vector-params [vector-secret]]
          reply     (assoc (read-reply gone :rf.scope/global {:ok true})
                           :resource :gone/vector-params)
          projected (epoch/projected-record (reply-carrier-record reply))
          replies   (carrier-replies projected)]
      (is (= 2 (count replies)))
      (doseq [r replies]
        (is (= :gone/vector-params (second (:resource/key r)))
            "attribution survives — the resource-id always rides")
        (is (redacted-component? (nth (:resource/key r) 2))
            "an unreadable owner's params FAIL CLOSED rather than riding raw"))
      (is (= [] (secret-leak-paths projected))))))

;; ---------------------------------------------------------------------------
;; (4) the acceptance arm — a REAL reply-to read over a vector-params owner
;; ---------------------------------------------------------------------------

(def ^:private vector-reply-value
  "The decoded body of the vector-params read. Carries the secret so the scan
  below cannot pass by finding nothing to find."
  {:email (str vector-secret "@example.com")})

(defn- drive-vector-params-reply-to-read!
  "`drive-reply-to-read!` against the `[:vector :string]` params owner: one REAL
  `[:rf.resource/ensure … :reply-to …]`, its terminal reply replayed through the
  runtime's own internal reply event, then a SECOND ensure that finds the entry
  fresh — so one call produces both the async accepted fan-out
  (`:cache-hit? false`) and the fresh-skip immediate dispatch
  (`:cache-hit? true`). `:secret/vector-params` is `:rf.scope/global`, so nothing
  here reaches the app-db axis and any surviving copy came off a trace carrier."
  []
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/read-loaded (fn [_ _ev] {}))
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :secret/vector-params :params vector-params
                        :owner    real-owner :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (:on-success @captured) {:status :ok :value vector-reply-value})
                      {:frame :test/rt})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :secret/vector-params :params vector-params
                        :owner    [:app :reader 2] :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/epoch-history :test/rt)))

(deftest real-vector-params-reply-to-read-leaks-nothing-into-fx-carriers
  (testing "rf2-xx4ty / rf2-wd9im audit #7013 ACCEPTANCE — `projected-record`
            over the records a REAL `[:rf.resource/ensure … :reply-to …]`
            settles for a `:sensitive?` owner with NON-MAP canonical params must
            carry the raw params at ZERO paths of its trace carriers, on BOTH
            continuation paths. This is the public-path reproduction the audit
            specified, driven through the runtime rather than assembled."
    (let [records (drive-vector-params-reply-to-read!)]
      (doseq [[label cache-hit?] [["async settle" false] ["fresh-skip cache hit" true]]]
        (testing label
          (let [raw       (record-carrying-reply records cache-hit?)
                projected (epoch/projected-record raw)]
            (testing "FIXTURE — the producer really put the vector params on the
                      fx carriers"
              (is (some? raw) "the continuation reached an fx carrier at all")
              (is (every? #(= vector-params (:params %)) (carrier-replies raw))
                  "every carrier's reply carries the RAW vector params")
              (is (seq (secret-leak-paths (mapv :tags (:trace-events raw))))
                  "the unprojected carriers leak — the projector's input is the
                   runtime's own output, not an invented tag map"))
            (testing "ACCEPTANCE — nothing raw survives on any trace carrier"
              (is (= [] (secret-leak-paths (mapv :tags (:trace-events projected))))
                  "every leaking path is named here; before the repair this
                   printed the [… :rf.fx/args 1 :resource/key 2 0] shape, and
                   the same key again inside :rf.reply/work-id, :correlation
                   and :rf.event/fx"))
            (testing "and the row still reads as a resource row"
              (doseq [r (carrier-replies projected)]
                (is (= :secret/vector-params (:resource r))
                    "the resource id rides verbatim")
                (is (= cache-hit? (:cache-hit? r)))
                (is (= :ok (:status r)))))))))))

;; ===========================================================================
;; (rf2-rnsv2) the SAME two carriers, the FAILURE half of the SAME reply: the
;; transport's `:rf.http/*` envelope under `:error`.
;; ===========================================================================
;;
;; §(rf2-xx4ty) closed the reply a read SUCCEEDS with. A read that FAILS settles
;; the same carriers with the same canonical reply — `reply/failure-reply`
;; composes the same `base-reply` — carrying the transport's classified envelope
;; under `:error`:
;;
;;   {:status :error                                ; or :cancelled, on an abort
;;    :error  {:kind :rf.http/http-4xx :status 422
;;             :body {…} :body-text "…" :detail {…}} ← the leak
;;    :rf.reply/work-kind :resource                  ; the DISCRIMINATOR
;;    :params …, :scope …, :resource/key …, …}       ; closed by the earlier arms
;;
;; That envelope is the app's own data coming back out. `re-frame.http.privacy`
;; enumerates `:body` / `:body-text` / `:decoded` / `:detail` / `:headers` as
;; its app-bearing slots, and `:detail` is the app's domain failure map — so a
;; 422 echoes the SUBMITTED FORM FIELDS. It arrives RAW: the transport's
;; `dispatch-failure!` hands `:on-failure` the unredacted envelope and
;; `privacy/prepare-emit-failure` touches only HTTP's own trace row.
;;
;; THE GRAIN IS NEITHER OF THE TWO ABOVE, and the tests below are arranged
;; around that. `:error` is UNCONDITIONAL INSIDE THE FAMILY REPLY MARKER — no
;; owner read at all — because the family's OWN rows tokenize this envelope
;; unconditionally (`error-envelope-slot` on `:rf.resource/failed` /
;; `:rf.resource/page-failed` / `:rf.mutation/failed`). An owner-conditional arm
;; here — i.e. adding `:error` to `reply-payload-slot` — would tokenize a
;; `:serialize` owner's envelope on the ROW and let the identical bytes ride on
;; the CARRIER: the rf2-irwsq disagreement, in mirror image. So the plain-owner
;; test below is an ACCEPTANCE test, not an over-redaction control, and it is
;; the one that fails if somebody re-implements the rejected remedy.
;;
;; …AND IT SPANS READS AND MUTATIONS. `resource-reply?` excludes
;; `:rf.reply/work-kind :mutation` deliberately — the mutation redacts its OWN
;; `:value` / `:params` / `:scope` at the source, through
;; `classification/redact-continuation-reply`. That function re-roots the spec's
;; projection-relative declarations and never touches `:error`, correctly, since
;; `:error` is not a projection of owner data and no declaration can name it. So
;; the mutation failure continuation leaked the identical envelope by the
;; identical route, seen by nobody. The `:error` arm therefore gates on BOTH
;; work kinds; §(6) below is that half.

(def ^:private failure-envelope
  "The classified `:rf.http/*` failure envelope a 422 settles with. Carries the
  secret in the two slots that actually echo user input — `:body` (the decoded
  error body) and `:detail` (the app's own domain failure map, HTTP's
  `:rf.http/accept-failure` slot). `:kind` / `:status` are the attribution
  scalars, which the row's siblings preserve."
  {:kind      :rf.http/http-4xx
   :status    422
   :body      {:email (str secret "@example.com")}
   :body-text (str "email " secret " is already registered")
   :detail    {:errors [{:field :email :value secret}]}})

(def ^:private other-failure-envelope
  "A SECOND, different envelope — same shape, different bytes. The distinctness
  control: content-addressed tokenization must keep two failures joinable as two
  failures."
  {:kind :rf.http/http-5xx :status 503 :body {:reason "upstream down"}})

(def ^:private abort-envelope
  "An `:rf.http/aborted` envelope. `reply/failure-reply` lowers it to
  `:status :cancelled` — and still puts it under `:error`. The reason `:status`
  is NOT the gate."
  {:kind :rf.http/aborted :reason :user-abort :detail {:draft {:email secret}}})

(defn- family-carrier-replies
  "`carrier-replies` widened to the FAMILY's two work kinds — a read completion
  (`:resource`) and a mutation completion (`:mutation`). The mutation half of
  this section needs it; `carrier-replies` stays read-only so the §(rf2-xx4ty)
  assertions above keep saying exactly what they said."
  [record]
  (let [found (atom [])
        walk  (fn walk [v]
                (cond
                  (map? v)  (do (when (#{:resource :mutation} (:rf.reply/work-kind v))
                                  (swap! found conj v))
                                (run! walk (vals v)))
                  (coll? v) (run! walk v)))]
    (doseq [tags (map :tags (:trace-events record))
            slot [:rf.fx/args :rf.event/fx]
            :when (contains? tags slot)]
      (walk (get tags slot)))
    @found))

(defn- drive-failing-reply-to-read!
  "The FAILURE counterpart of `drive-reply-to-read!` (rf2-uufoe): drive a REAL
  `[:rf.resource/ensure … :reply-to …]` and replay the transport's `:on-failure`
  with `envelope`, so the runtime's own `failed-handler` builds the canonical
  failure reply and fans it out through its own `[:dispatch …]` continuation fx.

  `params` is the caller's, so a PLAIN owner can be driven with a secret-free
  key and a secret-BEARING envelope — which is what makes the plain-owner sweep
  below name exactly one leaking datum."
  [resource-id params envelope]
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/read-loaded (fn [_ _ev] {}))
    (frame/swap-runtime-db! :test/rt
      (fn [rt] (elision/apply-classification-effects
                 rt {:sensitive [[:auth :user :username]]})))
    (frame/swap-frame-db! :test/rt assoc-in [:auth :user :username] secret)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource resource-id :params params
                        :owner    real-owner  :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (:on-failure @captured) {:status :error :error envelope})
                      {:frame :test/rt})
    (rf/epoch-history :test/rt)))

;; ---------------------------------------------------------------------------
;; (1) THE ACCEPTANCE ARM — a REAL failing reply-to read, sensitive owner
;; ---------------------------------------------------------------------------

(deftest real-failing-reply-to-read-leaks-no-error-envelope-into-fx-carriers
  (testing "rf2-rnsv2 — `projected-record` over the records a REAL
            `[:rf.resource/ensure … :reply-to …]` settles into FAILURE must
            carry the decoded error body at ZERO paths. Before the repair the
            envelope rode raw on both carriers, at
            [… :rf.fx/args 1 :error :body :email] and its :body-text / :detail
            siblings, and again under :rf.event/fx."
    (let [records   (drive-failing-reply-to-read! :derived/profile reply-params
                                                  failure-envelope)
          raw       (record-carrying-reply records false)
          projected (epoch/projected-record raw)]

      (testing "FIXTURE — the producer really put the envelope on the fx
                carriers, so the sweep below is not passing over an empty set"
        (is (some? raw) "the failure continuation reached an fx carrier at all")
        (is (seq (carrier-replies raw)) "and the reply map is findable on it")
        (is (every? #(= failure-envelope (:error %)) (carrier-replies raw))
            "every carrier's reply carries the RAW envelope")
        (is (seq (secret-leak-paths raw))
            "the unprojected record leaks — the projector's input is the
             runtime's own output, not an invented tag map"))

      (testing "ACCEPTANCE — nothing raw survives anywhere in the projected
                record"
        (is (= [] (secret-leak-paths projected))))

      (testing "and each reply's envelope is TOKENIZED, not merely absent"
        (is (= (count (carrier-replies raw)) (count (carrier-replies projected)))
            "projection neither drops nor invents a carrier reply")
        (is (every? #(redacted-component? (:error %)) (carrier-replies projected))
            "the envelope is an opaque content-addressed token"))

      (testing "the whole reply still reads as a FAILED reply — the attribution
                the family's own rows also preserve"
        (let [r (first (carrier-replies projected))]
          (is (= :error (:status r)) "the status rides verbatim")
          (is (= :failed (:rf.reply/work-status r)))
          (is (= :derived/profile (:resource r)) "and the resource id")
          (is (redacted-component? (first (:resource/key r)))
              "rf2-1kiuj — the sibling key's scope component is still tokenized")
          (is (tokenized-scope? (:scope r))
              "rf2-425mm — and the free :scope beside it"))))))

;; ---------------------------------------------------------------------------
;; (2) THE ANTI-OWNER-CONDITIONAL CONTROL — the point of the bead
;; ---------------------------------------------------------------------------

(deftest real-failing-reply-to-read-tokenizes-a-plain-owners-envelope-too
  (testing "rf2-rnsv2 — a PLAIN (`:serialize`, undeclared) owner's failure
            envelope tokenizes JUST THE SAME. This is an ACCEPTANCE test, not an
            over-redaction control: `error-envelope-slot` tokenizes the identical
            envelope on `:rf.resource/failed` UNCONDITIONALLY, so an
            owner-conditional carrier arm — i.e. `:error` dropped into
            `reply-payload-slot` — would make the two carriers of one envelope
            disagree. This test is what fails if somebody makes that edit.

            The key here carries NO secret (`:plain/article` + a plain slug), so
            the sweep below names exactly one leaking datum: the envelope."
    (let [records   (drive-failing-reply-to-read! :plain/article {:slug plain-slug}
                                                  failure-envelope)
          raw       (record-carrying-reply records false)
          projected (epoch/projected-record raw)]
      (testing "FIXTURE — a plain owner, and the envelope is the ONLY secret"
        (is (some? raw))
        (is (every? #(= failure-envelope (:error %)) (carrier-replies raw)))
        (is (seq (secret-leak-paths raw))
            "the unprojected record leaks the envelope")
        (is (= [] (secret-leak-paths (mapv #(dissoc % :error) (carrier-replies raw))))
            "and with `:error` removed the reply carries NO secret at all —
             the envelope is the only leaking datum on a plain owner's reply, so
             the acceptance below cannot pass for some other repair's reason"))
      (testing "ACCEPTANCE — the plain owner's envelope tokenizes anyway"
        (is (= [] (secret-leak-paths projected)))
        (is (every? #(redacted-component? (:error %)) (carrier-replies projected))))
      (testing "and the owner's OWN data still rides verbatim — the arm is
                marker-gated, not owner-gated, so nothing else moved"
        (let [r (first (carrier-replies projected))]
          (is (= {:slug plain-slug} (:params r))
              "rf2-xx4ty's arm is still owner-conditional and still silent here")
          (is (= :rf.scope/global (:scope r)))
          (is (= [:rf.scope/global :plain/article {:slug plain-slug}]
                 (:resource/key r))
              "and the plain owner's scoped key is untouched"))))))

;; ---------------------------------------------------------------------------
;; (3) the same shape assembled — the two carriers of ONE envelope AGREE
;; ---------------------------------------------------------------------------

(defn- failure-read-reply
  "The continuation reply `events/read-continuation-reply` builds on the FAILURE
  branch — `reply/failure-reply` plus the top-level read facts."
  [scoped-key scope envelope]
  (let [[_ resource-id params] scoped-key
        abort? (= :rf.http/aborted (:kind envelope))]
    (cond-> {:status               (if abort? :cancelled :error)
             :error                envelope
             :rf.reply/work-id     [:rf.work/resource scoped-key 1]
             :rf.reply/work-kind   :resource
             :rf.reply/work-status (if abort? :cancelled :failed)
             :rf.frame/id          :test/rt
             :completed-at         0
             :correlation          {:scope scope :generation 1
                                    :rf.reply/resource-key scoped-key}
             :resource             resource-id
             :params               params
             :scope                scope
             :resource/key         scoped-key
             :cache-hit?           false}
      abort? (assoc :cancelled? true :rf.reply/cancel-reason (:reason envelope)))))

(defn- both-carriers-of
  "A record carrying BOTH copies of one envelope: the family's OWN
  `:rf.resource/failed` row (whose `:error` `error-envelope-slot` tokenizes
  unconditionally) AND the two fx carriers of the continuation reply. The whole
  bead is that these two must agree, so one record holds both and the assertion
  is an equality rather than two independent shape checks.

  The effect vector deliberately also carries
  `[:rf.resource/commit-generation {:value 1}]` — a FOREIGN map on the same
  vector, the control that a name-only arm would fail."
  [scoped-key reply]
  (let [ev (conj read-reply-target reply)]
    (record-with
      [(event :rf.resource/failed
              {:rf.frame/id :test/rt :resource/key scoped-key
               :work/id [:rf.work/resource scoped-key 1] :generation 1
               :error (:error reply)})
       (event :rf.fx/handled
              {:rf.frame/id :test/rt :frame :test/rt
               :rf.fx/id :dispatch :rf.fx/args ev})
       (event :rf.fx/do-fx
              {:rf.frame/id :test/rt :frame :test/rt
               :rf.event/fx [[:rf.resource/commit-generation {:value 1}]
                             [:dispatch ev]]})])))

(deftest fx-carrier-error-envelope-agrees-with-the-family-row
  (testing "rf2-rnsv2 — the ROW copy and the CARRIER copies of ONE envelope must
            project to the SAME content-addressed token. Run over a PLAIN owner,
            because that is exactly the case an owner-conditional arm would
            split."
    (let [k         (sk :rf.scope/global :plain/article {:slug plain-slug})
          reply     (failure-read-reply k :rf.scope/global failure-envelope)
          projected (epoch/projected-record (both-carriers-of k reply))
          row-error (:error (:tags (first (:trace-events projected))))
          replies   (family-carrier-replies projected)]
      (is (= 2 (count replies)) "one reply per carrier")
      (is (redacted-component? row-error) "the row copy tokenizes (it always did)")
      (is (every? #(= row-error (:error %)) replies)
          "and the carrier copies tokenize to the SAME digest — the two carriers
           of one envelope agree, which is the whole ruling")
      (is (= [] (secret-leak-paths projected)))
      (is (every? #(true? (:sensitive? (:tags %))) (:trace-events projected))
          "every row that carried the envelope is stamped :sensitive?"))))

(deftest fx-carrier-error-envelope-tokenizes-on-a-cancelled-reply
  (testing "rf2-rnsv2 — `:status` is NOT the gate. An `:rf.http/aborted`
            envelope settles `:status :cancelled` and still rides under
            `:error`, so it must tokenize on the cancel branch too."
    (let [k         (sk :rf.scope/global :plain/article {:slug plain-slug})
          reply     (failure-read-reply k :rf.scope/global abort-envelope)
          projected (epoch/projected-record (both-carriers-of k reply))
          replies   (family-carrier-replies projected)]
      (is (= :cancelled (:status (first replies))) "it really is the cancel branch")
      (is (= :user-abort (:rf.reply/cancel-reason (first replies)))
          "and the cancel reason rides verbatim — attribution survives")
      (is (every? #(redacted-component? (:error %)) replies))
      (is (= [] (secret-leak-paths projected))))))

(deftest fx-carrier-error-tokens-stay-distinct-per-envelope
  (testing "rf2-rnsv2 — content-addressed, so two different failures keep two
            different digests and a tool's per-failure joins survive."
    (let [k    (sk :rf.scope/global :plain/article {:slug plain-slug})
          tok  (fn [envelope]
                 (-> (both-carriers-of k (failure-read-reply k :rf.scope/global envelope))
                     epoch/projected-record
                     family-carrier-replies
                     first
                     :error))]
      (is (not= (tok failure-envelope) (tok other-failure-envelope))
          "distinct envelopes ⇒ distinct tokens")
      (is (= (tok failure-envelope) (tok failure-envelope))
          "and the SAME envelope ⇒ the same token (stable, so joins work)"))))

(deftest fx-carrier-error-projection-is-idempotent
  (testing "rf2-rnsv2 — an already-projected record re-projects to itself; the
            token is not re-digested (the `redacted-token?` guard)."
    (let [k     (sk :rf.scope/global :plain/article {:slug plain-slug})
          once  (epoch/projected-record
                  (both-carriers-of k (failure-read-reply k :rf.scope/global failure-envelope)))
          twice (epoch/projected-record once)]
      (is (= once twice)))))

;; ---------------------------------------------------------------------------
;; (4) the OVER-REDACTION controls — the family speaks only for what it planted
;; ---------------------------------------------------------------------------

(deftest fx-carrier-leaves-the-fx-familys-own-error-verbatim
  (testing "rf2-rnsv2 — `:error` is an FX-FAMILY WORD. A map carrying `:error`
            with NO `:rf.reply/work-kind` is somebody else's data and must ride
            byte-identical — which is why the arm is marker-gated and not a
            name-only unconditional redaction."
    (let [foreign   {:error {:message "boom" :detail {:slug plain-slug}}}
          record    (record-with
                      [(event :rf.fx/do-fx
                              {:rf.frame/id :test/rt :frame :test/rt
                               :rf.event/fx [[:rf.error/report foreign]
                                             [:dispatch [:app/oops foreign]]]})])
          projected (epoch/projected-record record)
          tags      (:tags (first (:trace-events projected)))]
      (is (= [[:rf.error/report foreign] [:dispatch [:app/oops foreign]]]
             (:rf.event/fx tags))
          "the fx family's own :error rides byte-identical")
      (is (not (true? (:sensitive? tags)))
          "and the row is not stamped — an over-redaction is as much a defect
           as the leak (rf2-1kiuj)"))))

(deftest fx-carrier-leaves-a-foreign-familys-reply-error-verbatim
  (testing "rf2-rnsv2 — the marker is ENUMERATED, never `(some? work-kind)`.
            Managed HTTP stamps `:rf.reply/work-kind :http` on its own canonical
            reply, and an HTTP reply riding these carriers is the HTTP family's
            data to classify. The resources projector must leave it alone."
    (let [http-reply {:status :error
                      :rf.reply/work-kind   :http
                      :rf.reply/work-status :failed
                      :error failure-envelope}
          record     (record-with
                       [(event :rf.fx/handled
                               {:rf.frame/id :test/rt :frame :test/rt
                                :rf.fx/id :dispatch
                                :rf.fx/args [:app/http-done http-reply]})])
          projected  (epoch/projected-record record)
          tags       (:tags (first (:trace-events projected)))]
      (is (= [:app/http-done http-reply] (:rf.fx/args tags))
          "the HTTP family's reply rides through this projector untouched"))))

;; ---------------------------------------------------------------------------
;; (5) the trusted-local opt-in — the tokenization is the off-box default
;; ---------------------------------------------------------------------------

(deftest trusted-local-include-sensitive-keeps-raw-fx-carrier-error
  (testing "rf2-rnsv2 — `:include-sensitive?` still shows the raw envelope. The
            redaction is the OFF-BOX default, not a strip: a local operator
            debugging a 422 needs the body."
    (let [k         (sk :rf.scope/global :plain/article {:slug plain-slug})
          reply     (failure-read-reply k :rf.scope/global failure-envelope)
          projected (epoch/projected-record (both-carriers-of k reply)
                                            {:include-sensitive? true})]
      (is (= [failure-envelope failure-envelope]
             (mapv :error (family-carrier-replies projected)))
          "every carrier's raw envelope rides with :include-sensitive?")
      (is (= failure-envelope (:error (:tags (first (:trace-events projected)))))
          "and so does the family row's copy"))))

;; ---------------------------------------------------------------------------
;; (6) THE SEVENTH LEAK — the MUTATION continuation, closed in the same arm
;; ---------------------------------------------------------------------------

(def ^:private mutation-reply-target
  "The mutation call-site `:reply-to`."
  [:app/save-replied])

(defn- drive-mutation-reply-to!
  "Drive a REAL `[:rf.mutation/execute … :reply-to …]` and settle it with
  `outcome`, the canonical transport reply (`{:status :ok :value …}` or
  `{:status :error :error <envelope>}`). The transport target is chosen from
  the outcome's own `:status`, so one fn drives BOTH mutation settle handlers.

  `mutation-id` picks the owner, and the two owners are the two halves of the
  mutation family's redaction story. `:m/save` declares nothing that can reach
  `:error`, so `classification/redact-continuation-reply` substitutes nothing
  into the failure envelope at the source and any redaction observed on it came
  from the EGRESS projector.
  `:m/save-declared` names `[:value :email]` sensitive, so its result is
  redacted at the SOURCE, before the reply reaches a carrier at all — which is
  the only canary the SUCCESS settle has, there being no failure envelope on
  that branch and no arm of the egress projector that owns an undeclared
  mutation's `:value` (§rf2-xx4ty — the app's own continuation handler is
  entitled to it, and the coarse claim a resource would make has no mutation
  counterpart).

  rf2-k8vyi — THE CALL SITE PLANTS THE FAMILY'S IDENTITY. `:scope` is a public
  ScopeInput on the execute payload, and this drive used to pass none: every
  scope it produced was `:rf.scope/global`, the one scope shape with nothing in
  it to leak, so `:correlation :scope` carried no canary on either mutation
  branch and the ninth leak (rf2-l6wjl) was invisible to the namespace built to
  see it. `:params` is planted the same way and for the same reason — the
  request fns below deliberately do not echo the slug into their URL, because a
  resource's own request map is the FX family's data and rides untouched by
  design (rf2-1kiuj), which would make the sweep red on a by-design slot.

  Both owners therefore also declare `:sensitive [[:params :slug]]`. There is
  no coarse `:sensitive?` root prop on `reg-mutation` (§rf2-xx4ty), so a
  projection-relative params declaration is the ONLY spelling by which a
  mutation can claim its own params — and without one a secret-bearing
  `:params` would ride verbatim on the carrier, symmetrically with an
  undeclared resource's (§rf2-rnsv2 drives `:plain/article` with a plain slug
  for exactly that reason). Declaring it is what makes the mutation branches
  carry the family's identity in `:params` at all, and it is the only proof
  anywhere that a mutation's `[:params …]` declaration reaches a continuation
  reply."
  [mutation-id {:keys [status] :as outcome}]
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/save-replied (fn [_ _ev] {}))
    (rf/reg-mutation :m/save
      {:sensitive     [[:params :slug]]
       :params-schema [:map [:slug :string]]}
      (fn [_ _] {:request {:method :put :url "/a"}}))
    (rf/reg-mutation :m/save-declared
      {:sensitive     [[:data :email] [:params :slug]]
       :params-schema [:map [:slug :string]]}
      (fn [_ _] {:request {:method :put :url "/b"}}))
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation mutation-id :params reply-params
                        :scope    session-scope
                        :instance :mf1 :reply-to mutation-reply-target}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (get @captured (if (= :ok status) :on-success :on-failure))
                            outcome)
                      {:frame :test/rt})
    (rf/epoch-history :test/rt)))

(def ^:private mutation-value
  "The decoded write result the mutation SUCCESS settle delivers under
  `:value`, carrying the canary in the slot `:m/save-declared` declares
  sensitive."
  {:email (str secret "@example.com") :saved true})

(deftest real-failing-mutation-reply-to-leaks-no-error-envelope
  (testing "rf2-rnsv2 §the seventh leak — the MUTATION continuation reply leaks
            `:error` by the identical route. `redact-continuation-reply`
            re-roots only the spec's `:value` / `:params` / `:scope`
            declarations and never touches `:error` (correctly — `:error` is a
            transport envelope, not a projection of owner data), and the reply
            stamps `:rf.reply/work-kind :mutation`, so `resource-reply?` is false
            and NOTHING looked at it. One keyword wider in the same predicate;
            omitting it would have shipped the seventh leak in the commit that
            closed the sixth."
    (let [records   (drive-mutation-reply-to! :m/save {:status :error :error failure-envelope})
          raw       (first (filter #(seq (family-carrier-replies %)) records))
          projected (epoch/projected-record raw)]
      (testing "FIXTURE — the producer really put the envelope on the carriers"
        (is (some? raw) "the mutation continuation reached an fx carrier")
        (is (every? #(= :mutation (:rf.reply/work-kind %))
                    (family-carrier-replies raw))
            "and it really is the MUTATION reply, not a read one")
        (is (every? #(= failure-envelope (:error %)) (family-carrier-replies raw))
            "carrying the RAW envelope")
        (is (seq (secret-leak-paths raw))
            "the unprojected record leaks"))
      (testing "ACCEPTANCE — nothing raw survives"
        (is (= [] (secret-leak-paths projected)))
        (is (every? #(redacted-component? (:error %))
                    (family-carrier-replies projected))))
      (testing "and the reply still reads as a failed mutation reply"
        (let [r (first (family-carrier-replies projected))]
          (is (= :error (:status r)))
          (is (= :m/save (:mutation r)))
          (is (= :mf1 (:instance r)))
          (is (= [:mutation :m/save :mf1] (:cause r))
              "the causal explanation rides verbatim"))))))

;; ===========================================================================
;; (rf2-uufoe / rf2-22ij6) the DRIVE INVENTORY — every settle path that fans
;; out a continuation, enumerated FROM THE FAMILY'S OWN SOURCE.
;; ===========================================================================
;;
;; Eight leaks in this family were found by workers and none by a gate, and for
;; the last of them the blindness was structural rather than inattentive. Every
;; producer-driven assertion about a `:reply-to` continuation reaching off-box
;; egress lived in §(rf2-xx4ty) above, and every one of those drives replayed a
;; SUCCESS reply. The sibling conformance fixture
;; (`epoch_mcp_egress_conformance_test`) drives no `:reply-to` at all and points
;; here as the home of that coverage — so the coverage it points at existed for
;; one branch out of several.
;;
;; rf2-uufoe closed that with five hand-listed drives. THE HAND LIST WAS THE
;; REMAINING GAP, in that worker's own words: "the drive is always the gap,
;; never the harvest." `fx-carrier-rows` / `family-carrier-replies` already
;; harvest by marker, so the harvest side is general; the drive side was a
;; list, and a list is a convention. A settle path nobody remembered to add is
;; not "untested" in the ordinary sense — it is INVISIBLE to every assertion in
;; this namespace, because they all read what a drive produced.
;;
;; SO THE LIST IS GONE. `declared-continuation-settle-ids` READS THE FAMILY'S
;; SOURCE and computes the settle paths itself: seed the set with every `defn`
;; that calls the one delivery seam every continuation reply goes through
;; (`re-frame.reply/complete`), close it under "is called by", and map the
;; resulting call-graph roots onto the event ids `re-frame.resources` registers
;; them under. That set is the KEYS of `continuation-settle-drives` below, and
;; the two are asserted EQUAL. A new settle branch therefore joins the
;; inventory the moment its source is written, and reds this namespace until
;; somebody accounts for it — no rule to remember, and nothing for a reviewer
;; to notice.
;;
;; EACH ENTRY MAKES ONE OF TWO PROVED CLAIMS, never a comment:
;;   :drives        — a real drive reaches the branch (asserted: the record's
;;                    own `:event-id` is the id it is filed under) and the
;;                    whole projected record sweeps clean of the canary;
;;   :cannot-fan-out — the path is a REACHABILITY OVER-APPROXIMATION: it calls
;;                    a fn that can deliver a continuation, down a branch it
;;                    never takes. The drive runs anyway and asserts that NO
;;                    continuation reply reaches a carrier — so the day the
;;                    path gains one, this reds instead of going quiet.
;;
;; THE UNIT OF COVERAGE IS THE BRANCH, NOT THE SLOT. Each drive is one canary
;; sweep over the whole projected record — zero secret paths — plus the FIXTURE
;; assertion that the UNPROJECTED record does leak. That catches the CLASS:
;; whatever slot a future continuation gains, on any of these branches, the
;; sweep sees it. One assertion per named slot only ever catches the instance
;; somebody already found, which is how eight of them got here.

;; ---------------------------------------------------------------------------
;; the inventory — read the family's source, don't restate it
;; ---------------------------------------------------------------------------

(def ^:private continuation-seam
  "The ONE fn every resource / mutation continuation reply is delivered
  through. `re-frame.reply/complete` appends the canonical reply to the
  call-site `:reply-to` target and returns the event vector the settle handler
  dispatches; there is no second spelling, which is what makes a source-derived
  inventory possible at all (`re-frame.resources.reply` builds the reply map,
  but a reply that is built and not delivered reaches no carrier)."
  're-frame.reply/complete)

(def ^:private family-sources
  "The family's settle-handler sources, as classpath resources. Both are on the
  test classpath already — `resources` is a test-only dep of this namespace."
  ["re_frame/resources/events.cljc"
   "re_frame/resources/mutation_events.cljc"])

(def ^:private family-registration-source
  "Where the family registers its settle handlers as events. The inventory maps
  call-graph roots onto event ids through this file's `reg-event` forms."
  "re_frame/resources.cljc")

(defn- read-source-forms
  "Every top-level form of a classpath source resource. `:read-cond :allow`
  takes the JVM branch of the family's reader conditionals — the branch this
  suite runs. `*read-eval*` is off: this reads source, it does not run it."
  [resource-path]
  (let [url (io/resource resource-path)]
    (assert url (str "inventory source not on the classpath: " resource-path))
    (with-open [rdr (java.io.PushbackReader. (io/reader url))]
      (binding [*read-eval* false]
        (loop [acc []]
          (let [form (read {:read-cond :allow :eof ::eof} rdr)]
            (if (= form ::eof) acc (recur (conj acc form)))))))))

(defn- form-symbols
  "Every symbol appearing anywhere in `form`. Deliberately syntax-blind — a
  call, a `var` reference and a symbol passed as data all count, because for
  this purpose an over-approximation is the safe direction: it can only ADD a
  settle path to the inventory (which must then be accounted for), never drop
  one."
  [form]
  (let [found (volatile! #{})]
    (walk/postwalk (fn [x] (when (symbol? x) (vswap! found conj x)) x) form)
    @found))

(defn- ns-form-of [forms]
  (first (filter #(and (seq? %) (= 'ns (first %))) forms)))

(defn- require-aliases
  "`{alias → namespace}` for every `:as`-aliased require in a file's `ns` form —
  the map that turns a body symbol like `reply/complete` into the fully
  qualified `re-frame.reply/complete`, so two files that both define a
  `succeeded-handler` never collide."
  [forms]
  (into {}
        (for [clause (rest (ns-form-of forms))
              :when  (and (seq? clause) (= :require (first clause)))
              spec   (rest clause)
              :when  (vector? spec)
              :let   [i (.indexOf ^java.util.List spec :as)]
              :when  (pos? i)]
          [(nth spec (inc i)) (first spec)])))

(defn- qualify-with
  "Resolve `sym` as it would resolve inside the file `forms` came from:
  namespace-qualified through that file's require aliases, bare against the
  file's own namespace. nil for a symbol qualified by an alias the file does
  not declare (a local shadow, a Java class), which drops out of the graph."
  [forms]
  (let [own     (second (ns-form-of forms))
        aliases (require-aliases forms)]
    (fn [sym]
      (if-let [a (namespace sym)]
        (when-let [target (aliases (symbol a))]
          (symbol (str target) (name sym)))
        (symbol (str own) (name sym))))))

(defn- call-graph
  "`{qualified-defn-sym → #{qualified symbols in its body}}` for one source
  file."
  [resource-path]
  (let [forms   (read-source-forms resource-path)
        qualify (qualify-with forms)
        own     (second (ns-form-of forms))]
    (into {}
          (for [form forms
                :when (and (seq? form)
                           (#{'defn 'defn-} (first form))
                           (symbol? (second form)))]
            [(symbol (str own) (name (second form)))
             (into #{} (keep qualify) (form-symbols form))]))))

(def ^:private continuation-call-graph
  (delay (reduce merge {} (map call-graph family-sources))))

(def ^:private fans-out-a-continuation
  "Every family `defn` that can reach the delivery seam — the seam's direct
  callers, closed under \"is called by\". Transitive because the settle
  handlers do not call the seam themselves: they call
  `read-reply-continuation-fxs` / `continuation-fx`, which do."
  (delay
    (let [graph @continuation-call-graph
          seed  (set (for [[n syms] graph :when (syms continuation-seam)] n))]
      (loop [acc seed]
        (let [wider (into acc (for [[n syms] graph :when (some acc syms)] n))]
          (if (= wider acc) acc (recur wider)))))))

(def ^:private continuation-call-roots
  "The roots of that call graph — the members no other member calls. Every one
  of them is a settle handler, and every one must be registered as an event
  (asserted below), because a root that is not is a fan-out this inventory
  cannot see."
  (delay
    (let [graph  @continuation-call-graph
          member @fans-out-a-continuation]
      (set (remove (fn [m] (some #(and (not= % m) ((graph %) m)) member)) member)))))

(def ^:private family-reg-events
  "`[[event-id #{qualified symbols in the form}] …]` for every `reg-event` in
  the family's registration file."
  (delay
    (let [forms   (read-source-forms family-registration-source)
          qualify (qualify-with forms)]
      (vec (for [form forms
                 :when (and (seq? form)
                            (symbol? (first form))
                            (= "reg-event" (name (first form))))]
             [(second form) (into #{} (keep qualify) (form-symbols form))])))))

(def ^:private declared-continuation-settle-ids
  "THE INVENTORY: every event id whose registered handler can deliver a
  continuation reply, derived from source with no list to keep in step."
  (delay
    (set (for [[id syms] @family-reg-events
               :when (some syms @fans-out-a-continuation)]
           id))))

;; ---------------------------------------------------------------------------
;; the drives — one per inventoried settle path, keyed by the id
;; ---------------------------------------------------------------------------

(defn- in-an-isolated-runtime
  "Run `body!` under a fresh runtime. The inventory sweep runs every drive
  inside ONE deftest — so the equality between the inventory and the drive map
  cannot depend on test ordering — and the drives would otherwise see each
  other's registrations and cached entries.

  THE ASSERTIONS RUN INSIDE TOO, and that is not a stylistic choice.
  `projected-record` resolves the frame's classification through the LIVE
  frame; the fixture's teardown drops `:test/rt`, and a projection taken
  afterwards fails closed and redacts everything — a sweep that passes because
  there is nothing left to read. Returning records and asserting outside was
  the first shape of this helper, and it greened over a leak the pre-existing
  §rf2-rnsv2 deftest was reddening on the same drive."
  [body!]
  (reset-runtime-fixture body!))

(defn- record-with-family-reply
  "The first record of `records` whose fx carriers carry ANY family
  continuation reply — read or mutation. The failure / cancel branches settle
  through different handlers and different cascades, so selecting by record
  index would encode each branch's fx order; selecting by the reply's own
  marker does not."
  [records]
  (first (filter #(seq (family-carrier-replies %)) records)))

(defn- assert-branch-sweep!
  "The canary sweep every inventoried drive runs: the unprojected record must
  LEAK (so the sweep is not passing over an empty set) and the projected record
  must leak at ZERO paths. `expect` is the reply facts that name the branch, so
  a drive that silently stopped reaching the branch it names fails loudly
  instead of passing vacuously."
  [raw expect]
  (is (some? raw) "the continuation reached an fx carrier at all")
  (is (seq (family-carrier-replies raw)) "and the reply map is findable on it")
  (doseq [[k v] expect]
    (is (every? #(= v (k %)) (family-carrier-replies raw))
        (str "the drive really settled the named branch — " k " = " v)))
  (is (seq (secret-leak-paths raw))
      "FIXTURE — the unprojected record leaks, so the sweep below is real")
  (is (= [] (secret-leak-paths (epoch/projected-record raw)))
      "ACCEPTANCE — the canary survives at zero paths of the projected record"))

(defn- drive-aborted-event-reply-to-read!
  "Drive a REAL `[:rf.resource/ensure … :reply-to …]` and settle it through the
  LEGACY `:rf.resource.internal/aborted` event rather than a transport failure.
  That handler synthesises its own `{:kind :rf.http/aborted :reason :aborted}`
  envelope, so the canary here is not the envelope but the OWNER'S data the
  continuation reply carries beside it — `:params`, the resolved `:scope`, the
  `:resource/key`. Which is the point of a whole-record sweep: the branch is
  covered whatever slot the leak would land in."
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
    ;; the verification payload the lowering stamped — the second element of the
    ;; `:on-failure` target, which the legacy abort event takes as its only arg.
    (rf/dispatch-sync [:rf.resource.internal/aborted (second (:on-failure @captured))]
                      {:frame :test/rt})
    (rf/epoch-history :test/rt)))

(defn- drive-session-feed-reply-to!
  "Drive a REAL `[:rf.resource/ensure … :reply-to …]` against an INFINITE FEED
  and settle its page-0 fetch with `outcome`, the canonical transport reply.
  An infinite resource lowers into its OWN pair of settle events
  (`:rf.resource.internal/page-succeeded` / `…/page-failed`, each with its own
  first-load-vs-load-more split), so one fn drives both feed branches of the
  inventory.

  rf2-k8vyi — THE FEED IS SESSION-SCOPED, registered here rather than taken
  from the shared fixture. Every feed in that fixture scopes `:rf.scope/global`
  because the sections that own them are about the `[:data …]` declaration axis,
  and a global scope is a SCALAR: `:scope`, `:correlation`, `:resource/key` and
  `:rf.reply/work-id` all carried nothing on the two feed branches. A
  `{:from-db …}` resolver puts an identity MAP in all four, and the params carry
  the canary too — so this drive plants the family's identity everywhere the
  reply can hold it, which is the whole point of an inventory."
  [outcome]
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/read-loaded (fn [_ _ev] {}))
    (rf/reg-resource :derived/feed
      {:scope           {:from-db :rt/session}
       :infinite        true
       :next-page-param (fn [_last _all] nil)
       :page->items     :items
       ;; the COARSE claim, as `:derived/profile` makes it on the scalar
       ;; branches. A projection-relative `[[:data …]]` declaration would leave
       ;; `row-owner-redacts?` false and the owner's scoped KEY riding verbatim
       ;; — correct (rf2-1zc33 made only the FREE `:scope` tag unconditional;
       ;; the key belongs to its owner) but it would make a whole-record sweep
       ;; red on by-design egress. The `[:data …]` axis has its own coverage in
       ;; §(rf2-zaopo); what the inventory needs from a feed is the branch.
       :sensitive?      true
       :params-schema   [:map [:slug :string]]}
      (fn [_ _] {:request {:method :get :url "/i"}}))
    (frame/swap-runtime-db! :test/rt
      (fn [rt] (elision/apply-classification-effects
                 rt {:sensitive [[:auth :user :username]]})))
    (frame/swap-frame-db! :test/rt assoc-in [:auth :user :username] secret)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :derived/feed :params reply-params
                        :owner    real-owner   :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (get @captured (if (= :ok (:status outcome)) :on-success :on-failure))
                            outcome)
                      {:frame :test/rt})
    (rf/epoch-history :test/rt)))

(defn- drive-refetch-reply-to-read!
  "Drive a REAL `[:rf.resource/refetch … :reply-to …]` against an entry that is
  already loaded and fresh — the shape that WOULD fan out an immediate
  cache-hit continuation if `refetch` could take that branch."
  []
  (rf/configure! {:epoch-history {:trace-events-keep 80}})
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/read-loaded (fn [_ _ev] {}))
    (frame/swap-runtime-db! :test/rt
      (fn [rt] (elision/apply-classification-effects
                 rt {:sensitive [[:auth :user :username]]})))
    (frame/swap-frame-db! :test/rt assoc-in [:auth :user :username] secret)
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :derived/profile :params reply-params
                        :owner    real-owner :reply-to read-reply-target}]
                      {:frame :test/rt})
    (rf/dispatch-sync (conj (:on-success @captured) {:status :ok :value reply-value})
                      {:frame :test/rt})
    (let [before (count (rf/epoch-history :test/rt))]
      (rf/dispatch-sync [:rf.resource/refetch
                         {:resource :derived/profile :params reply-params
                          :owner    real-owner :reply-to read-reply-target}]
                        {:frame :test/rt})
      ;; only the refetch's own records — the ensure + settle above are the
      ;; SETUP, and they legitimately fan out.
      (drop before (rf/epoch-history :test/rt)))))

(def ^:private mutation-work-id-is-instance-keyed
  "rf2-k8vyi §the canary set — the one reply slot a MUTATION drive cannot plant.
  A resource's `:rf.reply/work-id` is `[:rf.work/resource <scoped-key>
  <generation>]` and so embeds the resolved scope and the caller's params; a
  mutation's is `[:rf.work/resource [:rf.mutation <instance-id>] <generation>]`
  — three app-authored keywords and an integer, with no resolved-from-app-db
  component for identity to enter through. Nothing the caller supplies reaches
  it, so there is no canary to plant, and its absence is not a gap."
  {:rf.reply/work-id
   "a mutation work id is keyed by its INSTANCE, not by a scoped key — no
    caller-supplied or resolver-derived value reaches it"})

(def ^:private continuation-settle-drives
  "One entry per inventoried settle path, KEYED BY THE EVENT ID the inventory
  derives from source. The keys are asserted equal to the inventory, so this
  map cannot silently fall behind the family."
  {:rf.resource.internal/succeeded
   {:why "the success settle — `succeeded-handler` fans the accepted terminal
          reply out to every target recorded on the work record"
    :drives [{:drive  #(record-carrying-reply (drive-reply-to-read! :derived/profile) false)
              :expect {:status :ok :cache-hit? false}}]}

   :rf.resource.internal/failed
   {:why "`failed-handler`, which is TWO branches: an ordinary error, and an
          `:rf.http/aborted` envelope it lowers to `:status :cancelled` while
          still carrying the abort under `:error`. `:status` is not the gate,
          which is exactly why both arms are driven"
    :drives [{:drive  #(record-carrying-reply
                         (drive-failing-reply-to-read! :derived/profile
                                                       reply-params
                                                       failure-envelope)
                         false)
              :expect {:status :error :rf.reply/work-status :failed}}
             {:drive  #(record-carrying-reply
                         (drive-failing-reply-to-read! :derived/profile
                                                       reply-params
                                                       abort-envelope)
                         false)
              :expect {:status :cancelled :rf.reply/work-status :cancelled}}]}

   :rf.resource.internal/aborted
   {:why "the LEGACY accepted-cancellation event. It synthesises its own
          envelope, so its canary is the owner data the reply carries beside
          it — which is why the sweep is whole-record and not `:error`-shaped"
    :drives [{:drive  #(record-carrying-reply (drive-aborted-event-reply-to-read! :derived/profile) false)
              :expect {:status :cancelled :resource :derived/profile}
              ;; the ONE slot on the ONE branch no drive can reach. An
              ;; exemption here is not the hand-list §(rf2-k8vyi) retired: the
              ;; polarity is inverted — the derived union is the default and an
              ;; exemption has to be written down with its reason, so a slot
              ;; nobody thought about is COVERED rather than omitted.
              :unplantable {:error "the legacy abort event SYNTHESISES its
                                    `{:kind :rf.http/aborted :reason :aborted}`
                                    envelope from nothing the caller supplies —
                                    there is no input through which a drive
                                    could plant a canary in it"}}]}

   :rf.resource.internal/page-succeeded
   {:why "the infinite feed's page settle. A DIFFERENT handler from the scalar
          success, with its own append-vs-replace split, delivering the MERGED
          item list under `:value`"
    :drives [{:drive  #(record-carrying-reply
                         (drive-session-feed-reply-to! {:status :ok :value declared-feed-page})
                         false)
              :expect {:status :ok :resource :derived/feed}}]}

   :rf.resource.internal/page-failed
   {:why "the infinite feed's page FAILURE — the family's third error channel,
          reached only through an `:infinite` resource's lowering"
    :drives [{:drive  #(record-carrying-reply
                         (drive-session-feed-reply-to! {:status :error :error failure-envelope})
                         false)
              :expect {:status :error :resource :derived/feed}}]}

   :rf.mutation.internal/succeeded
   {:why "the mutation WRITE settle — a fourth reply builder on a fourth
          cascade, and the branch that had no drive of its own until the
          inventory demanded one. Driven against a DECLARING owner, because
          the mutation family redacts its completion echo at the SOURCE and an
          owner that declares nothing leaves the egress projector no slot to
          sweep on this branch"
    :drives [{:drive  #(record-with-family-reply
                         (drive-mutation-reply-to! :m/save-declared
                                                   {:status :ok :value mutation-value}))
              :expect {:status :ok :rf.reply/work-kind :mutation
                       :mutation :m/save-declared}
              :unplantable mutation-work-id-is-instance-keyed}]}

   :rf.mutation.internal/failed
   {:why "the mutation failure settle, and — like its read sibling — its
          `:rf.http/aborted` cancel arm. Both canaries ride the transport
          envelope under `:error`, which is a transport fact and not a
          projection of owner data, so no declaration can clean it and the
          egress projector is the only thing that can"
    :drives [{:drive  #(record-with-family-reply
                         (drive-mutation-reply-to! :m/save
                                                   {:status :error :error failure-envelope}))
              :expect {:status :error :rf.reply/work-kind :mutation}
              :unplantable mutation-work-id-is-instance-keyed}
             {:drive  #(record-with-family-reply
                         (drive-mutation-reply-to! :m/save
                                                   {:status :error :error abort-envelope}))
              :expect {:status :cancelled :rf.reply/work-kind :mutation}
              :unplantable mutation-work-id-is-instance-keyed}]}

   :rf.resource/ensure
   {:why "the SYNCHRONOUS fan-out: a fresh-skip cache hit has no work record
          and no transport, so `ensure-load` builds the reply and dispatches
          the continuation in the same drain. A settle path with no settle
          event, which a handler-shaped list would not think to include"
    :drives [{:drive  #(record-carrying-reply (drive-reply-to-read! :derived/profile) true)
              :expect {:status :ok :cache-hit? true}}]}

   :rf.resource/refetch
   {:why "`refetch-handler` reaches `ensure-load` — hence its place in the
          inventory — but only ever with `:force-new? true`, which is the one
          flag the fresh-skip branch that owns the synchronous fan-out is
          guarded by. A reachability OVER-APPROXIMATION, and the assertion
          below is what keeps it honest"
    :cannot-fan-out {:drive drive-refetch-reply-to-read!}}})

(deftest every-continuation-settle-path-is-inventoried-from-source
  (testing "rf2-22ij6 — the drive map's keys ARE the settle paths the family's
            source declares. This is the whole mechanism: a settle branch added
            by an unrelated PR joins the left-hand side the moment its source is
            written, so it cannot ship without an entry here, and no reviewer
            has to remember a rule."
    (let [declared @declared-continuation-settle-ids]
      (testing "the inventory is not vacuously empty"
        (is (seq @fans-out-a-continuation)
            (str "no family fn reaches " continuation-seam
                 " — the delivery seam was renamed or moved, and this whole"
                 " inventory silently stopped inventorying anything"))
        (is (seq declared)
            "no registered event resolves to a continuation fan-out"))
      (testing "every root of the fan-out call graph is a registered event —
                a root that is not is a fan-out reached by some other door,
                which this inventory would never see"
        (let [registered (set (for [[_ syms] @family-reg-events
                                    root @continuation-call-roots
                                    :when (syms root)]
                                root))]
          (is (= #{} (into #{} (remove registered) @continuation-call-roots)))))
      (is (= declared (set (keys continuation-settle-drives)))
          "THE GATE — every settle path the family declares is accounted for
           below, and nothing below names a path that no longer exists"))))

(deftest every-inventoried-settle-path-survives-the-canary
  (doseq [[settle-id {:keys [why drives cannot-fan-out]}] continuation-settle-drives]
    (testing (str settle-id " — " why)
      (if cannot-fan-out
        (in-an-isolated-runtime
          (fn []
            (let [records ((:drive cannot-fan-out))]
              (is (empty? (mapcat family-carrier-replies records))
                  "the OVER-APPROXIMATION claim: this path reaches the delivery
                   seam statically but never takes that branch. The day it does,
                   this assertion reds and the entry owes a real drive"))))
        (doseq [[i {:keys [drive expect]}] (map-indexed vector drives)]
          (testing (str "arm " i)
            (in-an-isolated-runtime
              (fn []
                (let [raw (drive)]
                  (is (= settle-id (:event-id raw))
                      "the drive settled the branch it is filed under — the
                       record's own event id, not the drive's say-so")
                  (assert-branch-sweep! raw expect))))))))))

;; ===========================================================================
;; (rf2-k8vyi) the CANARY SET — derived from the family's own replies, because
;; a sweep only ever finds what the drive PLANTED.
;; ===========================================================================
;;
;; The inventory above generalises two of the three things a canary suite is
;; made of. The DRIVE SET is read out of the family's source, so a settle branch
;; joins it the moment its source is written. The HARVEST is a whole-record
;; sweep, so whatever slot a continuation gains, `assert-branch-sweep!` sees it.
;;
;; THE CANARY SET WAS STILL A HAND LIST, and it was the gap the ninth leak
;; (rf2-l6wjl) walked through. `drive-mutation-reply-to!` passed no `:scope` at
;; all, so every scope it produced was `:rf.scope/global` — a SCALAR, the one
;; scope shape with nothing in it to leak. `:correlation :scope` therefore
;; carried no canary on either mutation branch, the sweep swept a slot that was
;; empty by construction, and a leak this namespace exists to catch shipped
;; green. The fixture assertion did not help: `(seq (secret-leak-paths raw))`
;; only asks whether the record leaks SOMEWHERE, and the failure envelope alone
;; satisfied it.
;;
;; SO THE CANARY SET IS DERIVED TOO, and it is derived from the same place the
;; drive set is — the family's own behaviour rather than an author's memory:
;;
;;   1. THE FLOOR, which is not derived and does not need to be. Every drive
;;      plants a resolved `[tier {identity}]` scope bearing the canary. One
;;      assertion, no list, true of every branch: a `:rf.scope/global` reply is
;;      a reply whose scope, correlation, resource key and work id are all
;;      structurally incapable of leaking, and a suite of those proves nothing
;;      about a projector.
;;
;;   2. THE PARITY, which is. `identity-bearing-reply-slots` is the union, over
;;      every inventoried drive, of the reply slots that DEMONSTRABLY carry
;;      identity — a slot bearing the canary, or bearing a redaction token
;;      (which proves the SOURCE cleaned identity out of it before the carrier
;;      saw it). Every drive is then held to that union: a slot in it, present
;;      on this branch and barren, is a canary the drive forgot to plant.
;;
;; WHY PARITY IS THE RIGHT GENERALISATION. The ninth leak was not a slot nobody
;; had thought about — `:correlation :scope` was canaried, cleaned and asserted
;; on every READ branch at the time it shipped. It was the SAME slot, unplanted
;; on a sibling branch, and no assertion in this namespace compared the two.
;; The union does exactly that comparison, and it grows by itself: the day any
;; drive plants a canary in a slot nobody had considered, every other branch
;; carrying that slot owes one too, and reds until it has it.
;;
;; WHAT IT DOES NOT CLAIM. Parity is a consistency proof, not a completeness
;; one. A slot that NO drive canaries stays out of the union — `:cause` and
;; `:affected-keys` are barren on every branch today — so this cannot be the
;; only thing standing between the family and its tenth leak. The floor is what
;; keeps the union from collapsing: it pins the four scope-derived slots
;; unconditionally, on every branch, whatever the rest of the suite does.

(defn- carries-redaction-token?
  "Whether `x` carries a redaction token anywhere — the `{:rf/redacted …}`
  component the egress projector substitutes, or the bare `:rf/redacted`
  keyword a SOURCE-side declaration leaves in place of a value. Either one is
  proof that identity was in this slot and something took it out, which is what
  makes the slot count as planted."
  [x]
  (let [found (volatile! false)]
    (walk/postwalk (fn [v]
                     (when (or (= :rf/redacted v) (redacted-component? v))
                       (vreset! found true))
                     v)
                   x)
    @found))

(defn- reply-slot-facts
  "Per-slot canary classification of every family continuation reply riding
  `raw`'s carriers: `:canaried` (the drive's identity is in this slot raw),
  `:redacted-at-source` (it was, and a declaration removed it), `:barren`
  (nothing identity-bearing is in this slot at all)."
  [raw]
  (mapv (fn [reply]
          (into {}
                (map (fn [[k v]]
                       [k (cond
                            (contains-secret? v)         :canaried
                            (carries-redaction-token? v) :redacted-at-source
                            :else                        :barren)]))
                reply))
        (family-carrier-replies raw)))

(defn- resolved-identity-scope?
  "Whether `s` is a resolved `[tier {identity}]` scope — the only scope shape
  with anything in it to leak."
  [s]
  (and (vector? s) (= 2 (count s)) (keyword? (first s)) (map? (second s))))

(defn- observe-inventoried-drives!
  "Run every inventoried drive once, each in its own runtime, and return what
  each one PLANTED: the reply scopes and the per-slot canary facts. Read from
  the RAW record only, so unlike the sweep it does not need the frame to still
  be alive when it is judged."
  []
  (let [observed (atom [])]
    (doseq [[settle-id {:keys [drives]}] continuation-settle-drives
            [i {:keys [drive unplantable]}] (map-indexed vector drives)]
      (in-an-isolated-runtime
        (fn []
          (let [raw (drive)]
            (swap! observed conj
                   {:settle-id   settle-id
                    :arm         i
                    :unplantable (set (keys unplantable))
                    :scopes      (mapv :scope (family-carrier-replies raw))
                    :facts       (reply-slot-facts raw)})))))
    @observed))

(deftest every-inventoried-drive-plants-an-identity-bearing-scope
  (testing "rf2-k8vyi §the floor — a drive whose scope is `:rf.scope/global`
            sweeps a `:scope`, a `:correlation`, a `:resource/key` and a
            `:rf.reply/work-id` that are all scalars-all-the-way-down, and
            proves nothing about the projector that would have to clean them.
            Every inventoried drive plants a resolved `[tier {identity}]` scope
            carrying the canary, so all four slots are live on every branch."
    (doseq [{:keys [settle-id arm scopes]} (observe-inventoried-drives!)]
      (testing (str settle-id " arm " arm)
        (is (seq scopes) "the reply carries a `:scope` slot at all")
        (doseq [s scopes]
          (is (and (resolved-identity-scope? s) (contains-secret? (second s)))
              (str "the drive planted an identity-bearing scope; got " (pr-str s))))))))

(deftest every-inventoried-drive-canaries-every-slot-the-family-can-carry-identity-in
  (testing "rf2-k8vyi §the parity — the canary set is the UNION of the slots
            the drives themselves demonstrate can carry identity, and every
            branch owes the whole union. The ninth leak was `:correlation
            :scope` canaried on every read branch and unplanted on both
            mutation ones; nothing compared them."
    (let [observed         (observe-inventoried-drives!)
          identity-bearing (into #{} (for [{:keys [facts]} observed
                                           slots facts
                                           [k classification] slots
                                           :when (not= :barren classification)]
                                       k))]
      (testing "the derived canary set is not vacuously empty"
        (is (seq identity-bearing)
            "no inventoried drive planted identity in any reply slot — the
             canary vocabulary was renamed and this whole section stopped
             checking anything"))
      (doseq [{:keys [settle-id arm unplantable facts]} observed]
        (testing (str settle-id " arm " arm)
          (doseq [slots facts]
            (is (= #{} (into #{} (for [[k classification] slots
                                       :when (and (= :barren classification)
                                                  (identity-bearing k)
                                                  (not (unplantable k)))]
                                   k)))
                "every slot this family is known to carry identity in is
                 planted on this branch too — a barren one is a canary the
                 drive forgot, and a sweep over it can only ever pass")))))))

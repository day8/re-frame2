(ns re-frame.epoch-mcp-egress-conformance-test
  "MCP-style egress conformance for `project-egress`, from the forwarder's side:
  an off-box forwarder (an MCP `watch-epochs` snapshot, a `register-listener!
  :epoch` ship!) maps `project-egress` over raw epoch records, and what it ships
  must carry no raw sensitive bytes and no raw large payload, keep the
  bookkeeping slots a tool navigates by, and be a fixed point of a second
  projection. The per-leaf redaction cases live in
  `epoch_egress_redaction_cljs_test.cljc`.

  The fixtures here drive the shapes an app-db-only ring never contains: a
  whole-output `:large?` sub (a registration stamp, not an app-db path), a
  `:halted-depth` record, fx args on every trace-tag carrier, and the
  resource/mutation trace family, whose owner-local scoped keys are classified
  by the resource OWNER rather than by any app-db path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.epoch]
            [re-frame.frame :as rf.frame]
            ;; `fx/reg-fx`, the plain fn, NOT the `rf/reg-fx` macro: see
            ;; `install-resource-family!`.
            [re-frame.fx :as rf.fx]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Load-bearing: validates fx `:schema`s, which emits the
            ;; `:where :fx-args` row `drive-fx-carriers!` needs.
            [re-frame.schemas]
            ;; Load-bearing: registers the `:rf.resource/*` events and publishes
            ;; the late-bound resource egress hooks the projection consults.
            [re-frame.resources]
            ;; `ensure` lowers into the managed-HTTP transport, which fails
            ;; closed unless this ns has published its late-bind probe.
            [re-frame.http.managed]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- big-string [n] (apply str (repeat n "X")))

(def ^:private secret-password "topsecret-do-not-leak")
(def ^:private payload-size    25000)

(def ^:private large-sub-id   :sub/whole-output-large)
;; An UNMARKED sibling sub riding the same two egress slots — the negative
;; control that the elision is driven by the `:large?` stamp.
(def ^:private control-sub-id :sub/unmarked-control)
(def ^:private control-note   "mcp-egress-unmarked-control")

(defn- install-mcp-style-schemas!
  "Classify `[:auth :password]` sensitive and `[:blob :payload]` large against
  `frame-id`, plus the session identity the resource scope resolver reads, so
  the app-db axis is never what the resource-family scans catch."
  [frame-id]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt
               {:sensitive [[:auth :password] [:auth :user :username]]
                :large     [[:blob :payload]]})))
  nil)

(def ^:private mixed-ring-frame
  ;; `:drain-depth 1` lets `:halt/loop` settle a `:halted-depth` record.
  {:id :test/mcp :drain-depth 1})

(defn- drive-mixed-ring!
  "Drive a mixed cascade against `frame-id` (made from `mixed-ring-frame`) and
  return its `epoch-history`: `:login` writes the sensitive path, `:upload` the
  large one, `:halt/loop` re-dispatches itself with the secret in a
  `:sensitive` arg until the depth limit commits a `:halted-depth` record, and
  `:inc` / `:read-subs` read a whole-output `:large?` sub and an unmarked control
  sub. `:inc` subscribes (not `subscribe-once`) before its own `:n` increment
  commits, so the read in `:read-subs` is a RECOMPUTE carrying a distinct large
  previous value in all four payload slots."
  [frame-id]
  (rf/reg-event :seed   (fn [_ _] {:db {:n 0}}))
  (rf/reg-event :login  (fn [{:keys [db]} _] {:db (assoc-in db [:auth :password] secret-password)}))
  (rf/reg-event :upload (fn [{:keys [db]} _] {:db (assoc-in db [:blob :payload] (big-string payload-size))}))
  (rf/reg-event :halt/loop {:sensitive [[:token]]}
    (fn [_ [_ arg]] {:fx [[:dispatch [:halt/loop arg]]]}))
  (rf/reg-sub large-sub-id {:large? true}
    (fn [db _] (big-string (* payload-size (inc (:n db 0))))))
  (rf/reg-sub control-sub-id (fn [_db _] control-note))
  (rf/reg-event :inc
    (fn [{:keys [db]} _]
      @(rf/subscribe [large-sub-id]   {:frame frame-id})
      @(rf/subscribe [control-sub-id] {:frame frame-id})
      {:db (update db :n (fnil inc 0))}))
  (rf/reg-event :read-subs
    (fn [_ _]
      (rf/subscribe-once [large-sub-id]   {:frame frame-id})
      (rf/subscribe-once [control-sub-id] {:frame frame-id})
      {}))
  (rf/dispatch-sync [:seed]      {:frame frame-id})
  (rf/dispatch-sync [:login]     {:frame frame-id})
  (rf/dispatch-sync [:upload]    {:frame frame-id})
  (rf/dispatch-sync [:halt/loop {:token secret-password}] {:frame frame-id})
  (rf/dispatch-sync [:inc]       {:frame frame-id})
  (rf/dispatch-sync [:read-subs] {:frame frame-id})
  (rf/epoch-history frame-id))

(defn- sub-run-row
  "The structured `:sub-runs` row for `sub-id` in `record`."
  [record sub-id]
  (->> (:sub-runs record) (filter #(= sub-id (:sub-id %))) first))

(defn- sub-run-tags
  "The `:rf.sub/run` trace-event tags for `sub-id` in `record` — a second
  carrier of the same computed value."
  [record sub-id]
  (->> (:trace-events record)
       (filter #(and (= :rf.sub/run (:operation %))
                     (= sub-id (get-in % [:tags :rf.sub/id]))))
       first
       :tags))

(defn- secret-leak-paths
  "Every path in `x` whose leaf string carries the secret, with the offending
  value, so a failure names the slot that leaked."
  [x]
  (let [found (atom [])
        walk  (fn walk [path v]
                (cond
                  (string? v) (when (.contains ^String v ^String secret-password)
                                (swap! found conj [path v]))
                  (map? v)    (doseq [[k vv] v]
                                (walk (conj path k) k)
                                (walk (conj path k) vv))
                  (coll? v)   (doseq [[i vv] (map-indexed vector v)]
                                (walk (conj path i) vv))))]
    (walk [] x)
    @found))

(defn- count-leaf-strings-at-least
  "Count the leaf strings in `x` whose length is `>= n`."
  [n x]
  (let [counter (atom 0)
        walk (fn walk [v]
               (cond
                 (string? v) (when (>= (count v) n) (swap! counter inc))
                 (map? v)    (do (run! walk (keys v)) (run! walk (vals v)))
                 (coll? v)   (run! walk v)))]
    (walk x)
    @counter))

(defn- cedn-tokens
  "Every CEDN-1 encoded scoped key surviving anywhere in `x`. A `key-id` is a
  REVERSIBLE plaintext encoding of a scoped key, so one in a trace tag discloses
  scope + params inside a string the projector can only read as an opaque
  scalar. Returns the tokens so a failure prints the plaintext it found."
  [x]
  (vec (re-seq #"v\[k:[^\]]*\]*" (pr-str x))))

;; ============================================================================
;;  The mixed ring, projected record by record
;; ============================================================================

(deftest watch-epochs-whole-ring-projection-leaks-no-raw-bytes
  (testing "a forwarder maps `project-egress` over the ring: no raw secret and no
            raw large payload anywhere in what it ships, while the bookkeeping
            slots a tool navigates by survive byte-for-byte"
    (rf/make-frame mixed-ring-frame)
    (install-mcp-style-schemas! :test/mcp)
    (let [raw         (drive-mixed-ring! :test/mcp)
          ring        (mapv rf/project-egress raw)
          bookkeeping [:epoch-id :frame :committed-at :event-id
                       :outcome :halt-reason :schema-digest :rf.epoch/sensitive?]]
      (is (pos? (count-leaf-strings-at-least payload-size raw))
          "FIXTURE — the raw ring carries large leaves")
      (is (= [] (mapcat secret-leak-paths ring))
          "no projected record carries the secret at any path")
      (is (zero? (count-leaf-strings-at-least payload-size ring))
          "nor a leaf of the large payload's size")
      (is (some :halt-reason raw)
          "FIXTURE — a `:halt-reason` is present, so the check below compares a
           real descriptor rather than nil against nil")
      (is (= [] (for [[r p] (map vector raw ring)
                      k     bookkeeping
                      :when (not= (get r k) (get p k))]
                  [(:epoch-id r) k (get r k) (get p k)]))
          "every bookkeeping slot of every record survives (lists each
           offending [epoch-id slot raw projected])")

      (testing "a whole-output `:large?` sub's value and previous value are
                marked in BOTH carriers, the structured `:sub-runs` row and the
                `:rf.sub/run` trace tag, by one shared rule"
        (let [raw-row (sub-run-row (last raw) large-sub-id)
              proj    (last ring)
              row     (sub-run-row proj large-sub-id)
              tags    (sub-run-tags proj large-sub-id)
              marks   [(:value row) (:rf.sub/value tags) (:prev-value row) (:rf.sub/prev-value tags)]
              [cur cur' prev prev'] (mapv #(get-in % [:rf.size/large-elided :bytes]) marks)
              ctrl-row  (sub-run-row proj control-sub-id)
              ctrl-tags (sub-run-tags proj control-sub-id)]
          (is (= [(* 2 payload-size) payload-size]
                 (mapv count [(:value raw-row) (:prev-value raw-row)]))
              "FIXTURE — the recompute puts two DISTINCT large values on the row")
          (is (every? rf.elision/marker? marks)
              "all four payload slots are markers — withheld, not dropped")
          (is (= [cur prev] [cur' prev'])
              "the two carriers' markers agree on `:bytes`, so they cannot drift")
          (is (not= cur prev)
              "and each marker measures its own value")
          (is (= (repeat 4 control-note)
                 [(:value ctrl-row) (:prev-value ctrl-row)
                  (:rf.sub/value ctrl-tags) (:rf.sub/prev-value ctrl-tags)])
              "NEGATIVE CONTROL — an unmarked sub's values ride raw in all four slots"))))))

(deftest forwarder-project-egress-is-large-idempotent
  (testing "re-projecting a projected ring is a fixed point: the marker-aware walk
            passes a `:rf.size/large-elided` marker through rather than
            re-marking it, so `:bytes` / `:digest` do not drift across a
            forwarder pipeline that double-projects, and `:rf/redacted` is a
            non-matchable scalar"
    (rf/make-frame mixed-ring-frame)
    (install-mcp-style-schemas! :test/mcp)
    (let [once (mapv rf/project-egress (drive-mixed-ring! :test/mcp))]
      (is (rf.elision/marker?
            (get-in (some #(when (= :upload (:event-id %)) %) once) [:db-after :blob :payload]))
          "FIXTURE — the first pass substituted a marker at the large path")
      (is (= once (mapv rf/project-egress once))))))

;; ============================================================================
;;  The fx-args trace-tag carriers
;; ============================================================================

(defn- fx-carrier-rows
  "Every trace row in `record` carrying an fx-args carrier, found by SLOT as the
  projector finds them."
  [record]
  (filter #(or (contains? (:tags %) :rf.fx/args)
               (contains? (:tags %) :rf.event/fx))
          (:trace-events record)))

(defn- fx-row
  "The tags of the first `:rf.fx/handled` row in `record` for `fx-id`."
  [record fx-id]
  (->> (:trace-events record)
       (filter #(and (= :rf.fx/handled (:operation %))
                     (= fx-id (get-in % [:tags :rf.fx/id]))))
       first
       :tags))

(defn- error-rows
  "The trace rows of `record` whose `:operation` is `op`."
  [record op]
  (filterv #(= op (:operation %)) (:trace-events record)))

(defn- drive-fx-carriers!
  "Fire one cascade whose fx args carry the secret on every fx-args carrier and
  return its raw epoch record. A well-formed entry rides `:rf.fx/args` and
  `:rf.event/fx`; three MALFORMED entries whose head is payload are dropped
  from the fx walk but not from the trace, so they reach `:rf.event/fx` and
  each emits a `:rf.error/effect-map-shape` row stamping the entry on `:value`
  and interpolating it into `:reason`; an entry failing its fx `:schema` emits
  a `:where :fx-args` row re-stamping its args under several aliases. The
  classified app-db path takes the same bytes, so the axes' orthogonality reads
  off one record."
  [frame-id]
  (rf/reg-fx :fxp/login (fn [_ _] nil))
  (rf/reg-fx :fxp/notify {:schema [:map [:level :keyword]]} (fn [_ _] nil))
  (rf/reg-event :do-login
    (fn [_ [_ pw]]
      {:db {:auth {:password pw}}
       :fx [[:fxp/login {:password pw}]
            [{:password pw} {:arg 1}]
            [[:login pw] {:arg 2}]
            [pw {:arg 3}]
            nil
            [:fxp/notify {:level "not-a-keyword" :password pw}]]}))
  (rf/dispatch-sync [:do-login secret-password] {:frame frame-id})
  (last (rf/epoch-history frame-id)))

(defn- fx-carriers
  "Each fx-args carrier the `drive-fx-carriers!` cascade reaches, by slot."
  [record]
  {:rf.fx/args       (:rf.fx/args (fx-row record :fxp/login))
   :rf.event/fx      (->> (:trace-events record)
                          (filter #(= :rf.fx/do-fx (:operation %)))
                          first :tags :rf.event/fx)
   :effect-map-shape (mapv #(select-keys (:tags %) [:value :reason])
                           (error-rows record :rf.error/effect-map-shape))
   :fx-args-schema   (select-keys (:tags (first (error-rows record :rf.error/schema-validation-failure)))
                                  [:rf.fx/args :received :value :explain :where :rf.fx/id])})

(deftest forwarder-fx-args-tag-carriers-fail-closed
  (testing "the fx-handler args ride several trace tags beside the structured
            `:effects[*].args` row, and off-box cannot prove an undeclared arg
            safe on any of them: one value, several carriers, ONE rule. Each
            redacts in the shape that fits it — an `:rf.event/fx` entry keeps
            its head only when that head is a keyword fx-id, the
            `:rf.error/effect-map-shape` `:reason` is prose and redacts whole —
            while the value-free metadata that identifies the fx survives"
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (let [raw      (drive-fx-carriers! :test/mcp)
          proj     (rf/project-egress raw)
          r        :rf/redacted
          carriers (fx-carriers raw)]
      (is (= [] (remove (comp seq secret-leak-paths)
                        (concat [(:rf.fx/args carriers)]
                                (remove nil? (:rf.event/fx carriers))
                                (mapcat vals (:effect-map-shape carriers))
                                (vals (select-keys (:fx-args-schema carriers)
                                                   [:rf.fx/args :received :value :explain])))))
          "FIXTURE — every raw carrier holds the secret")
      (is (= {:rf.fx/args       r
              :rf.event/fx      [[:fxp/login r] r r r nil [:fxp/notify r]]
              :effect-map-shape (repeat 3 {:value r :reason r})
              :fx-args-schema   {:rf.fx/args r :received r :value r :explain r
                                 :where :fx-args :rf.fx/id :fxp/notify}}
             (fx-carriers proj)))
      (is (= [] (secret-leak-paths proj))
          "the whole projected record, `:effects` included, names the secret nowhere")
      (is (= proj (rf/project-egress proj))
          "re-projection does not drift the wire shape")

      (testing "`:rf.egress/include-fx-args?` hands every carrier back verbatim and
                lifts nothing else; `:rf.egress/include-sensitive?` lifts the
                app-db leaf alone, so asking for sensitive app-db values never
                hands back the fx args"
        (let [fx-args   (rf/project-egress raw {:rf.egress/include-fx-args? true})
              sensitive (rf/project-egress raw {:rf.egress/include-sensitive? true})]
          (is (= [carriers r]
                 [(fx-carriers fx-args) (get-in fx-args [:db-after :auth :password])]))
          (is (= secret-password (get-in sensitive [:db-after :auth :password])))
          (is (= [] (secret-leak-paths (select-keys sensitive [:trace-events :effects :trigger-event])))
              "no trace tag, effect row or trigger arg carries the secret under
               include-sensitive"))))))

;; ============================================================================
;;  The resource/mutation trace family
;; ============================================================================
;;
;; A resource's owner-local SCOPED KEY `[scope resource-id params]` is
;; classified by the resource OWNER's `:sensitive?` declaration; once copied
;; into a trace tag no app-db path can match it. The resources artefact owns
;; the family projector, which the epoch projection reaches through late-bound
;; hooks on two routes: by OPERATION NAMESPACE for the family's own rows, and
;; by SLOT for the `:rf.fx/args` / `:rf.event/fx` carriers of the effects an
;; `ensure` lowers into, which address the work by its scoped key.

(def ^:private sensitive-resource-id :secret/article)
(def ^:private plain-resource-id     :plain/article)
(def ^:private plain-slug            "mcp-egress-plain-control")
(def ^:private resource-owner        [:app :reader 1])
(def ^:private session-scope-id      :mcp/session)
(def ^:private session-cause         [:logout :session])
(def ^:private global-cause          [:logout :global])
(def ^:private invalidation-tag      :mcp/article)
(def ^:private session-resource-id   :secret/session-article)
(def ^:private session-owner         [:app :reader 2])

(defn- install-resource-family!
  "Register a `:sensitive?` owner (its scoped key MUST tokenize off-box), a
  PLAIN owner (its key MUST ride verbatim — the over-redaction control), a
  named scope resolver deriving an identity-bearing `[tier {identity}]` scope
  from the session identity seeded here, a session-scoped owner, and a no-op
  managed-HTTP fx so `ensure` emits its rows without a request leaving the box.

  `fx/reg-fx` rather than the `rf/reg-fx` macro: the macro stamps this ns as a
  second provenance under `:rf.http/managed`, the frame's default-image
  reprojection then dies on `:rf.error/image-duplicate-id` (swallowed), and
  every `ensure` throws `:rf.error/resource-not-registered`."
  [frame-id]
  (rf.frame/swap-frame-db! frame-id assoc-in [:auth :user :username] secret-password)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource-scope session-scope-id
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  (rf/reg-resource sensitive-resource-id
    {:scope         :rf.scope/global
     :sensitive?    true
     :params-schema [:map [:auth-token :string]]}
    (fn [_params _ctx] {:request {:method :get :url "/secret"}}))
  (rf/reg-resource plain-resource-id
    {:scope         :rf.scope/global
     :params-schema [:map [:slug :string]]}
    (fn [_params _ctx] {:request {:method :get :url "/public"}}))
  (rf/reg-resource session-resource-id
    {:scope         {:from-db session-scope-id}
     :sensitive?    true
     :params-schema [:map [:slug :string]]}
    (fn [_params _ctx] {:request {:method :get :url "/session"}}))
  nil)

(defn- drive-resource-family!
  "Drive a real resource cascade against `frame-id`: two `ensure`s under one
  owner (the `:sensitive?` resource with the secret in its params, the plain one
  beside it), a `release-owner`, then two `invalidate-tags` stamping the
  resolved scope as a FREE `:scope` tag in both shapes — the identity-bearing
  tuple and the `:rf.scope/global` scalar — and last an `ensure` of the
  session-scoped owner, which plants that tuple inside the fx carriers' arg
  maps. The invalidations run after the release so no active owner refetches.
  No reply is replayed, so no `:reply-to` continuation reaches the carriers;
  that coverage lives in `epoch_egress_resource_trace_test`."
  [frame-id]
  (doseq [event [[:rf.resource/ensure {:resource sensitive-resource-id
                                       :params   {:auth-token secret-password}
                                       :owner    resource-owner}]
                 [:rf.resource/ensure {:resource plain-resource-id
                                       :params   {:slug plain-slug}
                                       :owner    resource-owner}]
                 [:rf.resource/release-owner {:owner resource-owner}]
                 [:rf.resource/invalidate-tags {:scope {:from-db session-scope-id}
                                                :tags  #{invalidation-tag}
                                                :cause session-cause}]
                 [:rf.resource/invalidate-tags {:scope :rf.scope/global
                                                :tags  #{invalidation-tag}
                                                :cause global-cause}]
                 [:rf.resource/ensure {:resource session-resource-id
                                       :params   {:slug "mcp-egress-session"}
                                       :owner    session-owner}]]]
    (rf/dispatch-sync event {:frame frame-id})))

(defn- resource-family-row? [event]
  (boolean (some-> (:operation event) namespace #{"rf.resource" "rf.mutation"})))

(defn- redacted-token?
  "Whether `c` is the family's opaque egress token `{:rf/redacted <payload>}`."
  [c]
  (and (map? c) (contains? c :rf/redacted)))

(defn- tokenized-scope?
  "A resolved `[tier {identity}]` scope that egressed correctly: the tier
  keyword verbatim over a tokenized identity map."
  [s]
  (and (vector? s) (= 2 (count s))
       (= :rf.scope/session (first s))
       (redacted-token? (second s))))

(defn- embedded-keys-naming
  "Every scoped-key-shaped 3-vector naming `resource-id` embedded anywhere in
  `x` — a named `:resource/key`, inside a work-id, a request-id, a `:released`
  vector or a continuation arg map."
  [x resource-id]
  (let [found (atom [])
        walk  (fn walk [v]
                (when (coll? v)
                  (when (and (vector? v) (= 3 (count v))
                             (= resource-id (nth v 1)) (map? (nth v 2)))
                    (swap! found conj v))
                  (if (map? v)
                    (doseq [[k vv] v] (walk k) (walk vv))
                    (run! walk v))))]
    (walk x)
    @found))

(defn- carrier-scopes
  "Every value under a FREE `:scope` key inside the fx-args carriers of `record`."
  [record]
  (let [found (atom [])
        walk  (fn walk [v]
                (cond
                  (map? v)  (doseq [[k vv] v]
                              (when (= :scope k) (swap! found conj vv))
                              (walk vv))
                  (coll? v) (run! walk v)))]
    (doseq [tags (map :tags (fx-carrier-rows record))
            slot [:rf.fx/args :rf.event/fx]
            :when (contains? tags slot)]
      (walk (get tags slot)))
    @found))

(defn- invalidated-scope
  "The `:scope` tag of the `:rf.resource/invalidated` row in `rows` carrying `cause`."
  [rows cause]
  (->> rows
       (filter #(and (= :rf.resource/invalidated (:operation %))
                     (= cause (:cause (:tags %)))))
       first :tags :scope))

(deftest forwarder-project-egress-leaks-no-raw-resource-family-bytes
  (testing "a real resource cascade, projected record by record as a forwarder
            ships it: a `:sensitive?` owner's resolved scope and canonical params
            egress in NO representation — not raw, not inside a reversible
            CEDN-1 key-id — while a plain owner's ride verbatim in the same rows
            and slots, so a blanket strip fails as loudly as a leak"
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (install-resource-family! :test/mcp)
    (drive-resource-family! :test/mcp)
    (let [raw-hist     (rf/epoch-history :test/mcp)
          proj-hist    (mapv rf/project-egress raw-hist)
          ;; Off-box the fx-args carriers fail closed whole, so the owner
          ;; discrimination INSIDE them is read at the trusted-local posture
          ;; that ships them — which also opens `:effects[*].args` by design.
          carrier-hist (mapv #(rf/project-egress % {:rf.egress/include-fx-args? true}) raw-hist)
          proj-rows    (filter resource-family-row? (mapcat :trace-events proj-hist))
          keys-naming  (fn [rid] (set (concat (embedded-keys-naming proj-rows rid)
                                              (mapcat #(embedded-keys-naming
                                                         (map :tags (fx-carrier-rows %)) rid)
                                                      carrier-hist))))
          sensitive    (->> proj-rows
                            (filter #(and (= :rf.resource/work-started (:operation %))
                                          (= sensitive-resource-id (second (:resource/key (:tags %))))))
                            first :tags :resource/key)
          session      (invalidated-scope proj-rows session-cause)]
      (is (seq (mapcat secret-leak-paths raw-hist))
          "FIXTURE — the raw records carry the secret")
      (is (= [] (mapcat secret-leak-paths proj-hist))
          "no projected record carries the secret at any path")
      (is (= [] (cedn-tokens proj-hist))
          "nor any CEDN-1 key-id")
      (is (= [] (mapcat #(secret-leak-paths (dissoc % :effects)) carrier-hist))
          "nor, at the fx-args posture, anywhere but the `:effects` args it opens")

      (testing "a scoped key projects identically wherever it rides — family
                rows and fx carriers alike — so per-key joins survive"
        (is (= [true sensitive-resource-id true]
               [(redacted-token? (first sensitive)) (second sensitive)
                (redacted-token? (nth sensitive 2))])
            "the sensitive key tokenizes scope and params; its resource-id survives")
        (is (= #{sensitive} (keys-naming sensitive-resource-id)))
        (is (= #{[:rf.scope/global plain-resource-id {:slug plain-slug}]}
               (keys-naming plain-resource-id))
            "the plain owner's key rides verbatim in every slot"))

      (testing "a FREE `:scope` keeps its tier and tokenizes an identity map,
                and the `:rf.scope/global` scalar rides verbatim, on the family
                rows and inside the fx carriers alike"
        (is (tokenized-scope? session))
        (is (= #{session :rf.scope/global}
               (into #{(invalidated-scope proj-rows global-cause)}
                     (mapcat carrier-scopes carrier-hist)))))

      (is (= proj-hist (mapv rf/project-egress proj-hist))
          "re-projection is a fixed point — tokens are not re-hashed"))))

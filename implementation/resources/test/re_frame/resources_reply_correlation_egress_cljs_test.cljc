(ns re-frame.resources-reply-correlation-egress-cljs-test
  "THE NINTH LEAK (rf2-l6wjl) — a family continuation reply's `:correlation`
  facts carry the resolved `:scope`, and on the MUTATION half that copy rode
  off-box RAW while the reply's own top-level `:scope` beside it tokenized.

  ## The disagreement

  `resources.reply/base-reply` puts the resolved scope in TWO places on every
  reply it builds: free at the top level, and again inside the `:correlation`
  map it stamps beside it. The carrier projector
  (`trace-egress/project-embedded-keys`) reaches a free `:scope` through
  `carrier-family-payload?` — a map is provably the runtime's when its
  IMMEDIATE entries wear the family's reserved vocabulary (a `resource`-
  namespaced key, or a `[:rf.work/resource …]` work-id value). Both halves of
  the family satisfy that at the TOP level, because every reply carries
  `:rf.reply/work-id`.

  Inside `:correlation` the two halves diverge, and nothing was reading the
  divergence:

    - a READ completion's correlation is
      `{:scope … :generation … :rf.reply/resource-key <key>}` — that last
      spelling IS in `family-named-key?`, so the map proved itself and the
      `:scope` cleaned;
    - a MUTATION completion's correlation is
      `{:scope … :generation … :mutation/id … :instance/id …}` — no
      `resource`-namespaced key, no work-id value (the work-id is at the reply
      root, not in here), so the map proved NOTHING and its `:scope` rode out
      verbatim.

  One scope, three carriers, two rules applied — the rf2-irwsq shape a third
  time, now across the two HALVES of one family. rf2-425mm made the free
  `:scope` arm unconditional on exactly this argument (\"the two carriers of one
  scope must agree\") and rf2-1zc33 settled that a free `:scope` tag classifies
  unconditionally, since it belongs to no owner whose declaration could exempt
  it. A caller-supplied scope is not a declaration, so the mutation family's
  source-side `redact-continuation-reply` — which re-roots only the owner's
  DECLARED `:sensitive` / `:large` subpaths — never spoke for it either.

  ## The repair, and its grain

  A separate arm of `project-embedded-keys`'s `entry` cond, gated on
  `family-reply?` — the canonical reply MARKER `:rf.reply/work-kind`, ENUMERATED
  over the family's two kinds `#{:resource :mutation}` and never
  `(some? work-kind)`, because managed HTTP stamps `:http` on the same substrate
  and an HTTP reply's correlation is the HTTP family's to classify. Inside a
  proven family reply the `:correlation` slot is the runtime's by construction
  (`base-reply` builds it), which is the same kind of proof position 1 of a
  work-id vector carries, and strictly stronger than any registry lookup: it
  survives a mutation whose registration was hot-reloaded away.

  Deliberately NOT a member of `reply-payload-slot`: that set is consumed under
  `(and owner-redacts? …)`, so adding `:correlation` to it would silently
  implement the OWNER-CONDITIONAL remedy rf2-1zc33 / rf2-425mm rejected, and
  would leave the two carriers disagreeing for a `:serialize` owner.

  ## What this suite pins

  1. FIXTURE + ACCEPTANCE on the mutation SUCCESS settle and the mutation
     FAILURE settle: the unprojected carriers really leak, the projected ones
     leak at ZERO paths, and the reply still reads as the mutation reply it is.
  2. AGREEMENT: on one projected reply, all three carriers of the one scope
     agree — the top-level `:scope`, the `:correlation :scope`, and the scope
     embedded in `:rf.reply/work-id` — and the READ half agrees with the
     mutation half.
  3. NO OVER-REDACTION, three ways: a `:rf.scope/global` SCALAR scope rides
     verbatim; the correlation's non-scope facts (`:generation`,
     `:mutation/id`, `:instance/id`) ride verbatim so per-instance joins
     survive; and a FOREIGN reply (`:rf.reply/work-kind :http`) carrying its own
     `:correlation {:scope …}` rides byte-identical.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex —
  the off-box channel matters on both hosts."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as fx]
   ;; load-bearing side-effecting requires: register the :rf.resource/* /
   ;; :rf.mutation/* events this suite dispatches.
   [re-frame.resources]
   [re-frame.resources.test-support]
   [re-frame.resources.trace-egress :as trace-egress]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(def ^:private secret "topsecret-PII")

(def ^:private frame-id :rf/default)

(def ^:private session-scope
  "An identity-BEARING resolved scope: a `[tier {identity}]` tuple whose
  identity map carries the canary. `:rf.scope/global` is a scalar with nothing
  in it to leak, which is precisely why every earlier fixture missed this."
  [:rf.scope/session {:username secret}])

;; ---- fixture --------------------------------------------------------------

(defn- init! []
  (rf/make-frame {:id frame-id :url-bound? true
                  :doc "reply-correlation egress suite default app frame."})
  ;; A mutation that declares NOTHING — so `redact-continuation-reply`
  ;; substitutes nothing at the source and any cleaning observed on its reply
  ;; came from the EGRESS projector, which is the thing under test.
  (rf/reg-mutation :m/save
    {:params-schema [:map [:slug :string]]}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  ;; The READ half's owner — the agreement counterpart. Also declares nothing.
  (rf/reg-resource :r/article
    {:scope         :rf.scope/from-caller
     :params-schema [:map [:slug :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/a"}})))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(def ^:private carrier-slot
  "The two FX carrier slots the family's replies ride out on."
  [:rf.fx/args :rf.event/fx])

(defn- carrier-row?
  [ev] (some #(contains? (:tags ev) %) carrier-slot))

(defn- leak-paths
  "Every path in `x` whose leaf string carries the canary, each with the
  offending value. A failure NAMES the slot instead of printing
  `(not (not true))` — the whole diagnostic value when the leak is five levels
  down inside an fx carrier."
  [x]
  (let [found (atom [])
        walk  (fn walk [path v]
                (cond
                  (string? v) (when #?(:clj  (.contains ^String v secret)
                                       :cljs (not= -1 (.indexOf v secret)))
                                (swap! found conj [path v]))
                  (map? v)    (doseq [[k vv] v]
                                (walk (conj path k) k)
                                (walk (conj path k) vv))
                  (coll? v)   (doseq [[i vv] (map-indexed vector v)]
                                (walk (conj path i) vv))))]
    (walk [] x)
    @found))

(defn- family-replies
  "Every canonical FAMILY reply map — read or mutation — reachable inside the
  fx carrier slots of `rows`. Harvested by the reply's own MARKER rather than
  by carrier index, so the assertions do not encode the cascade's fx order."
  [rows]
  (let [found (atom [])
        walk  (fn walk [v]
                (cond
                  (map? v)  (do (when (#{:resource :mutation} (:rf.reply/work-kind v))
                                  (swap! found conj v))
                                (run! walk (vals v)))
                  (coll? v) (run! walk v)))]
    (doseq [tags (map :tags rows)
            slot carrier-slot
            :when (contains? tags slot)]
      (walk (get tags slot)))
    @found))

(defn- project-rows
  "Project every row's tags for OFF-BOX egress exactly as the epoch tool-pair
  does — through the late-bound `:resources/project-fx-args-egress` hook's
  body, against the row's own `:rf.frame/id` falling back to the record's."
  [rows]
  (mapv #(update % :tags trace-egress/project-fx-args-egress
                 (or (:rf.frame/id (:tags %)) frame-id))
        rows))

(defn- redacted-token? [c] (and (map? c) (contains? c :rf/redacted)))

(defn- capture-carriers!
  "Run `body!` with a trace listener installed; return every captured row that
  carries an fx CARRIER slot."
  [body!]
  (let [acc (atom [])
        k   ::correlation-egress-recorder]
    (rf/register-listener! :trace k (fn [ev] (swap! acc conj ev)))
    (try (body!) (finally (rf/unregister-listener! :trace k)))
    (filterv carrier-row? @acc)))

;; ---- drives ---------------------------------------------------------------

(defn- drive-mutation-reply-to!
  "Drive a REAL `[:rf.mutation/execute … :scope … :reply-to …]` and settle it
  with `outcome`, the canonical transport reply. The transport target is chosen
  from the outcome's own `:status`, so one fn drives BOTH mutation settle
  handlers (`:rf.mutation.internal/succeeded` and `/failed`). Returns the
  carrier rows of the SETTLE drain only — the execute drain is setup and its
  own carriers are covered by rf2-425mm's arm."
  [{:keys [status] :as outcome}]
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/save-replied (fn [_ _ev] {}))
    (rf/dispatch-sync [:rf.mutation/execute
                       {:mutation :m/save :params {:slug "a-slug"}
                        :instance :mf1    :scope  session-scope
                        :reply-to [:app/save-replied]}]
                      {:frame frame-id})
    (capture-carriers!
      #(rf/dispatch-sync (conj (get @captured (if (= :ok status) :on-success :on-failure))
                               outcome)
                         {:frame frame-id}))))

(defn- drive-read-reply-to!
  "The READ half of the same shape: a REAL `[:rf.resource/ensure … :scope …
  :reply-to …]` settled through the transport's `:on-success`. Its correlation
  map wears `:rf.reply/resource-key`, so this half was ALREADY clean — it is
  the agreement counterpart, not a second acceptance arm."
  []
  (let [captured (atom nil)]
    (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
    (rf/reg-event :app/read-loaded (fn [_ _ev] {}))
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :r/article :params {:slug "a-slug"}
                        :scope    session-scope
                        :owner    [:app :reader 1]
                        :reply-to [:app/read-loaded]}]
                      {:frame frame-id})
    (capture-carriers!
      #(rf/dispatch-sync (conj (:on-success @captured) {:status :ok :value {:body "ok"}})
                         {:frame frame-id}))))

(def ^:private failure-envelope
  "A classified `:rf.http/*` failure envelope. Carries no canary of its own —
  rf2-rnsv2's `:error` arm already tokenizes it, and a second canary in this
  suite would make it impossible to tell WHICH arm cleaned the record."
  {:kind :rf.http/http-5xx :status 503 :body {:reason "upstream down"}})

;; ===========================================================================
;; 1. THE LEAK — the mutation continuation's `:correlation :scope`.
;; ===========================================================================

(deftest mutation-success-continuation-cleans-correlation-scope
  (testing "rf2-l6wjl — the mutation SUCCESS settle
            (`:rf.mutation.internal/succeeded`) fans its reply out through
            `[:dispatch …]`, so the whole reply map rides `:rf.fx/args` and
            `:rf.event/fx`. Its `:correlation :scope` is the SAME resolved
            identity its top-level `:scope` carries, and only one of the two
            was being read"
    (let [rows    (drive-mutation-reply-to! {:status :ok :value {:saved true}})
          replies (family-replies rows)]
      (testing "FIXTURE — the drive really reached the branch, and the
                unprojected carriers really leak"
        (is (seq rows) "the continuation reached an fx carrier at all")
        (is (seq replies) "and the reply map is findable on it")
        (is (every? #(= :mutation (:rf.reply/work-kind %)) replies)
            "it really is the MUTATION reply, not a read one")
        (is (every? #(= session-scope (:scope (:correlation %))) replies)
            "carrying the RAW resolved scope inside :correlation")
        (is (seq (leak-paths rows))
            "so the sweep below is not passing over an empty set"))
      (testing "ACCEPTANCE — nothing raw survives the projection"
        (let [projected (project-rows rows)]
          (is (= [] (leak-paths projected))
              "the canary survives at zero paths of the projected carriers")
          (is (every? #(redacted-token? (second (:scope (:correlation %))))
                      (family-replies projected))
              "the correlation scope's IDENTITY MAP is content-addressed"))))))

(deftest mutation-failure-continuation-cleans-correlation-scope
  (testing "rf2-l6wjl — and the SAME on `:rf.mutation.internal/failed`. The
            two settle branches build their reply through the one
            `base-reply`, so a fix that reached only the success branch would
            be a fix of the drive rather than of the projector"
    (let [rows    (drive-mutation-reply-to! {:status :error :error failure-envelope})
          replies (family-replies rows)]
      (testing "FIXTURE"
        (is (seq replies) "the failure continuation reached an fx carrier")
        (is (every? #(= :error (:status %)) replies)
            "the drive really settled the FAILURE branch")
        (is (every? #(= session-scope (:scope (:correlation %))) replies)
            "carrying the RAW resolved scope inside :correlation")
        (is (seq (leak-paths rows)) "the unprojected carriers leak"))
      (testing "ACCEPTANCE"
        (is (= [] (leak-paths (project-rows rows))))))))

;; ===========================================================================
;; 2. AGREEMENT — one scope, three carriers, one rule.
;; ===========================================================================

(deftest every-carrier-of-one-mutation-scope-agrees
  (testing "rf2-425mm's principle, applied to the slot that was disagreeing:
            the reply's top-level `:scope`, its `:correlation :scope` and the
            scope embedded in `:rf.reply/work-id` are three copies of one
            identity, and a projected reply must show one rule applied to all
            three"
    (let [rows      (drive-mutation-reply-to! {:status :ok :value {:saved true}})
          projected (family-replies (project-rows rows))]
      (is (seq projected) "the projected reply is still findable by its marker")
      (doseq [r projected]
        (is (= (:scope r) (:scope (:correlation r)))
            "the two spellings of one scope project IDENTICALLY — same tier
             keyword, same content-addressed digest, so a tool can still join
             the reply to its correlation")
        (is (= :rf.scope/session (first (:scope (:correlation r))))
            "the TIER keyword survives — a tool still reads \"session scope\"")
        (is (redacted-token? (second (:scope (:correlation r))))
            "and only the identity map tokenizes")))))

(deftest read-and-mutation-continuations-agree-on-correlation-scope
  (testing "rf2-l6wjl — the two HALVES of the family agree. The read half was
            already clean (its correlation wears `:rf.reply/resource-key`,
            which `family-named-key?` knows), and that asymmetry is what made
            this a bug rather than a policy: the same slot, on the same
            substrate, treated two ways"
    (let [mutation-scope (->> (drive-mutation-reply-to! {:status :ok :value {:saved true}})
                              project-rows family-replies
                              (map #(:scope (:correlation %))) first)
          read-scope     (->> (drive-read-reply-to!)
                              project-rows family-replies
                              (map #(:scope (:correlation %))) first)]
      (is (some? mutation-scope) "the mutation reply's correlation scope")
      (is (some? read-scope) "the read reply's correlation scope")
      (is (= read-scope mutation-scope)
          "byte-identical projections of one scope, across the two halves"))))

;; ===========================================================================
;; 3. NO OVER-REDACTION — the arm must not eat what it does not own.
;; ===========================================================================

(deftest correlation-facts-beside-the-scope-ride-verbatim
  (testing "rf2-l6wjl — the arm projects the correlation's `:scope` by the
            family rule and walks everything else. The identities a tool joins
            on — `:generation`, `:mutation/id`, `:instance/id` — must survive
            the projection unchanged, or the repair costs the diagnosability
            the redaction exists to preserve"
    (let [rows      (drive-mutation-reply-to! {:status :ok :value {:saved true}})
          raw       (first (family-replies rows))
          projected (first (family-replies (project-rows rows)))]
      (is (some? raw))
      (is (= (:generation (:correlation raw)) (:generation (:correlation projected)))
          "the generation rides verbatim")
      (is (= :m/save (:mutation/id (:correlation projected)))
          "and the mutation id")
      (is (= :mf1 (:instance/id (:correlation projected)))
          "and the instance id")
      (is (= :mf1 (:instance projected)) "as do the reply's own top-level facts")
      (is (= [:mutation :m/save :mf1] (:cause projected))
          "including the causal explanation"))))

(deftest a-global-scoped-mutation-reply-rides-byte-identical
  (testing "rf2-l6wjl — the CONTROL. `:rf.scope/global` is a SCALAR: the family
            rule rides it verbatim, so a mutation carrying no identity-bearing
            scope must project byte-identical. This passes BEFORE the fix as
            well as after, which is what makes it a control rather than a
            second acceptance arm"
    (let [captured (atom nil)]
      (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
      (rf/reg-event :app/save-replied (fn [_ _ev] {}))
      (rf/dispatch-sync [:rf.mutation/execute
                         {:mutation :m/save :params {:slug "a-slug"}
                          :instance :mg1    :scope  :rf.scope/global
                          :reply-to [:app/save-replied]}]
                        {:frame frame-id})
      (let [rows (capture-carriers!
                   #(rf/dispatch-sync (conj (:on-success @captured)
                                            {:status :ok :value {:saved true}})
                                      {:frame frame-id}))
            raw  (first (family-replies rows))]
        (is (some? raw) "the continuation reached an fx carrier")
        (is (= :rf.scope/global (:scope (:correlation raw))))
        (is (= (map :tags rows) (map :tags (project-rows rows)))
            "every carrier's tags are byte-identical raw vs projected")))))

(deftest a-foreign-familys-reply-correlation-rides-verbatim
  (testing "rf2-l6wjl — the MARKER control. `:rf.reply/work-kind` is the SHARED
            `re-frame.reply` substrate's marker, not this family's: managed HTTP
            stamps `:http` on its own canonical reply, and an HTTP reply riding
            these same carriers is the HTTP family's data to classify. The arm
            is therefore ENUMERATED over `#{:resource :mutation}` — never
            `(some? work-kind)` — and a foreign reply's `:correlation` must ride
            through untouched"
    (let [foreign {:rf.reply/work-kind :http
                   :rf.reply/work-id   [:rf.work/http :h1 1]
                   :status             :ok
                   :correlation        {:scope [:rf.scope/session {:username secret}]
                                        :generation 3}}
          tags    {:rf.fx/args {:on-success [:app/done foreign]}}]
      (is (= tags (trace-egress/project-fx-args-egress tags frame-id))
          "byte-identical — the resources projector speaks only for what the
           resources runtime planted")
      (is (nil? (:sensitive? (trace-egress/project-fx-args-egress tags frame-id)))
          "and does not stamp the row"))))

(ns re-frame.resources-reply-correlation-egress-cljs-test
  "Off-box egress of a family continuation reply's `:correlation :scope`.
  `resources.reply/base-reply` puts the resolved scope both at the reply's top
  level and inside its `:correlation` map, and the two copies must tokenize
  alike, on the mutation half as on the read half.

  A read reply's correlation wears `:rf.reply/resource-key`, so the carrier
  projector (`rf.resources.trace-egress/project-embedded-keys`) proves it the
  family's by its vocabulary alone. A mutation reply's correlation
  (`{:scope … :generation … :mutation/id … :instance/id …}`) proves nothing
  that way, so a dedicated arm projects it, gated on the reply marker
  `:rf.reply/work-kind` enumerated over `#{:resource :mutation}`: managed HTTP
  stamps `:http` on the same substrate, and its correlation is the HTTP
  family's to classify. The arm is unconditional, like the free `:scope` arm:
  a caller-supplied scope is not an owner declaration, so no owner can exempt
  it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting requires: register the :rf.resource/* /
   ;; :rf.mutation/* events this suite dispatches.
   [re-frame.resources]
   [re-frame.resources.test-support]
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(def ^:private secret "topsecret-PII")

(def ^:private frame-id :rf/default)

(def ^:private session-scope
  "An identity-bearing resolved scope; `:rf.scope/global` is a scalar with
  nothing in it to leak."
  [:rf.scope/session {:username secret}])

(defn- init! []
  (rf/make-frame {:id frame-id :url-bound? true
                  :doc "reply-correlation egress suite default app frame."})
  ;; declares nothing, so `redact-continuation-reply` substitutes nothing at
  ;; the source and any cleaning comes from the egress projector
  (rf/reg-mutation :m/save
    {:params-schema [:map [:slug :string]]}
    (fn [{:keys [slug]} _] {:request {:method :put :url (str "/a/" slug)}}))
  ;; the read half, also declaring nothing; its ensures pass an explicit
  ;; :scope, so the resolver's app-db slot stays unwritten
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (rf/reg-resource :r/article
    {:scope         {:from-db :t/caller-scope}
     :params-schema [:map [:slug :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/a"}})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(def ^:private carrier-slot
  "The two FX carrier slots the family's replies ride out on."
  [:rf.fx/args :rf.event/fx])

(defn- carrier-row? [ev] (some #(contains? (:tags ev) %) carrier-slot))

(defn- leak-paths
  "Every path in `x` whose leaf string carries the canary, each with the
  offending value, so a failure names the slot."
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
  "Every family reply map, read or mutation, inside the fx carrier slots of
  `rows`, harvested by the reply's own marker rather than the cascade's fx order."
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
  "Project every row's tags for off-box egress as the epoch tool-pair does."
  [rows]
  (mapv #(update % :tags rf.resources.trace-egress/project-fx-args-egress
                 (or (:rf.frame/id (:tags %)) frame-id))
        rows))

(defn- redacted-token? [c] (and (map? c) (contains? c :rf/redacted)))

(defn- capture-carriers!
  "Run `body!` and return every trace row it emits that carries an fx carrier slot."
  [body!]
  (let [acc (atom [])
        k   ::correlation-egress-recorder]
    (rf/register-listener! :trace k (fn [ev] (swap! acc conj ev)))
    (try (body!) (finally (rf/unregister-listener! :trace k)))
    (filterv carrier-row? @acc)))

(defn- drive-mutation-reply-to!
  "Execute `:m/save` under `scope` with a `:reply-to`, and settle it with
  `outcome` through the transport callback its `:status` selects. Returns the
  carrier rows of the settle drain only."
  ([outcome] (drive-mutation-reply-to! outcome session-scope :mf1))
  ([{:keys [status] :as outcome} scope instance]
   (let [captured (atom nil)]
     (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
     (rf/reg-event :app/save-replied (fn [_ _ev] {}))
     (rf/dispatch-sync [:rf.mutation/execute
                        {:mutation :m/save :params {:slug "a-slug"}
                         :instance instance :scope scope
                         :reply-to [:app/save-replied]}]
                       {:frame frame-id})
     (capture-carriers!
       #(rf/dispatch-sync (conj (get @captured (if (= :ok status) :on-success :on-failure))
                                outcome)
                          {:frame frame-id})))))

(defn- drive-read-reply-to!
  "The read half: an ensure under the session scope with a `:reply-to`, settled
  through `:on-success`. Returns the settle drain's carrier rows."
  []
  (let [captured (atom nil)]
    (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! captured args) nil))
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

(defn- assert-mutation-settle-cleans-correlation-scope
  "FIXTURE: the drive reached a mutation reply with `status` whose carriers
  leak the raw correlation scope. ACCEPTANCE: the projected carriers leak at
  zero paths and the correlation scope's identity map is tokenized."
  [rows status]
  (let [replies (family-replies rows)]
    (is (= [true true true true]
           [(boolean (seq replies)) (every? #(= [:mutation status] ((juxt :rf.reply/work-kind :status) %)) replies)
            (every? #(= session-scope (:scope (:correlation %))) replies)
            (boolean (seq (leak-paths rows)))])
        "FIXTURE — the mutation reply rides a carrier, raw scope and all")
    (is (= [[] true]
           [(leak-paths (project-rows rows))
            (every? #(redacted-token? (second (:scope (:correlation %)))) (family-replies (project-rows rows)))])
        "the canary survives at zero projected paths, and the correlation identity is tokenized")))

;; ===========================================================================
;; the mutation continuation's :correlation :scope is cleaned
;; ===========================================================================

(deftest mutation-success-continuation-cleans-correlation-scope
  (assert-mutation-settle-cleans-correlation-scope
    (drive-mutation-reply-to! {:status :ok :value {:saved true}}) :ok))

(deftest mutation-failure-continuation-cleans-correlation-scope
  ;; the failure envelope carries no canary of its own, so any cleaning seen
  ;; is the correlation arm's, not the :error arm's
  (assert-mutation-settle-cleans-correlation-scope
    (drive-mutation-reply-to! {:status :error
                               :error  {:kind :rf.http/http-5xx :status 503 :body {:reason "upstream down"}}})
    :error))

;; ===========================================================================
;; one scope, three carriers, one rule — on both halves of the family
;; ===========================================================================

(deftest every-carrier-of-one-mutation-scope-agrees
  (let [projected (family-replies (project-rows (drive-mutation-reply-to! {:status :ok :value {:saved true}})))]
    (is (seq projected) "the projected reply is still findable by its marker")
    (is (every? (fn [r] (and (= (:scope r) (:scope (:correlation r)))
                             (= :rf.scope/session (first (:scope (:correlation r))))))
                projected)
        "the two spellings of one scope project identically, keeping the tier keyword, so a tool can still join them")))

(deftest read-and-mutation-continuations-agree-on-correlation-scope
  (let [correlation-scope #(->> % project-rows family-replies (map (comp :scope :correlation)) first)
        mutation-scope    (correlation-scope (drive-mutation-reply-to! {:status :ok :value {:saved true}}))
        read-scope        (correlation-scope (drive-read-reply-to!))]
    (is (= [true read-scope] [(some? read-scope) mutation-scope])
        "byte-identical projections of one scope, across the two halves")))

;; ===========================================================================
;; no over-redaction
;; ===========================================================================

(deftest correlation-facts-beside-the-scope-ride-verbatim
  ;; the identities a tool joins on survive the projection
  (let [rows      (drive-mutation-reply-to! {:status :ok :value {:saved true}})
        raw       (first (family-replies rows))
        projected (first (family-replies (project-rows rows)))]
    (is (= [true (:generation (:correlation raw)) :m/save :mf1 :mf1 [:mutation :m/save :mf1]]
           [(some? raw) (:generation (:correlation projected)) (:mutation/id (:correlation projected))
            (:instance/id (:correlation projected)) (:instance projected) (:cause projected)])
        "the generation, mutation and instance ids, and the reply's own top-level facts ride verbatim")))

(deftest a-global-scoped-mutation-reply-rides-byte-identical
  ;; the CONTROL: a scalar scope rides verbatim with or without the arm
  (let [rows (drive-mutation-reply-to! {:status :ok :value {:saved true}} :rf.scope/global :mg1)]
    (is (= [:rf.scope/global (map :tags rows)]
           [(:scope (:correlation (first (family-replies rows)))) (map :tags (project-rows rows))])
        "every carrier's tags are byte-identical raw vs projected")))

(deftest a-foreign-familys-reply-correlation-rides-verbatim
  ;; the marker control: an HTTP reply on the same carriers is the HTTP
  ;; family's to classify
  (let [foreign {:rf.reply/work-kind :http
                 :rf.reply/work-id   [:rf.work/http :h1 1]
                 :status             :ok
                 :correlation        {:scope [:rf.scope/session {:username secret}]
                                      :generation 3}}
        tags    {:rf.fx/args {:on-success [:app/done foreign]}}]
    (is (= tags (rf.resources.trace-egress/project-fx-args-egress tags frame-id))
        "byte-identical, and unstamped: the resources projector speaks only for what the resources runtime planted")))

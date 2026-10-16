(ns re-frame.machine-data-schema-redaction-test
  "Machine `:data` trace-egress redaction through the per-frame elision
  registry (EP-0025). A classified snapshot `:data` path — the absolute
  runtime-db path `[:rf.runtime/machines :snapshots <actor-id> :data …]` — is
  claimed under `:source :effect` by the commit-plane classification effects,
  or under `:source :machine` by a `reg-machine` spec's projection-relative
  `:sensitive` / `:large` declaration, which is lowered per actor at spawn /
  first boot and dropped at destroy. The trace egress chokepoint redacts the
  matching slot to `:rf/redacted` (sensitive) or the `:rf.size/large-elided`
  marker (large) in every machine `:data` slot of `:rf.machine/*` traces,
  leaving undeclared slots verbatim. A `:sensitive?` / `:large?` Malli prop on
  `[:schemas :data]` is validation only and classifies nothing."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading the machines artefact publishes its late-bind hooks
            ;; (`:machines/reg-machine` etc.) so `rf/reg-machine` resolves.
            [re-frame.machines :as rf.machines]
            [re-frame.classification :as rf.classification]
            [re-frame.elision :as rf.elision]
            [re-frame.machines.test-support :as rf.machines.test-support]
            ;; The schemas artefact + Malli adapter — `[:schemas :data]` VALIDATION
            ;; runs; the props do not classify durable :data.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- fixtures -------------------------------------------------------------

(def ^:private auth-id :rf.machine-redaction/auth)

(def ^:private auth-schema
  "Carries `:sensitive?` / `:large?` props so a test can prove they classify
  nothing."
  [:map
   [:retries :int]
   [:token   {:sensitive? true} [:maybe :string]]
   [:blob    {:large? true}     [:maybe :string]]])

(defn- reg-auth-machine!
  "Register the auth machine carrying `auth-schema` at `[:schemas :data]`."
  ([] (reg-auth-machine! auth-id))
  ([machine-id]
   (rf.machines/reg-machine* machine-id
     {:initial :anon
      :data    {:retries 0 :token nil :blob nil}
      :schemas {:data auth-schema}
      :states  {:anon  {:on {:login :authed}}
                :authed {}}})))

(defn- declare-frame-marks!
  "Classify the machine snapshot's `:data` token slot SENSITIVE and the blob
  slot LARGE on `:rf/default`, writing the frame's elision registry under
  `:source :effect` as the commit-plane `:sensitive` / `:large` effects do."
  ([] (declare-frame-marks! auth-id))
  ([actor-id]
   (rf.elision/swap-elision-slot! :rf/default
     (fn [reg]
       (-> (or reg {})
           (rf.elision/add-claims :sensitive-declarations {:source :effect}
                               [[:rf.runtime/machines :snapshots actor-id :data :token]])
           (rf.elision/add-claims :declarations {:source :effect}
                               [[:rf.runtime/machines :snapshots actor-id :data :blob]]))))))

(defn- machine-transition-event*
  "Build a `:rf.machine/transition` trace event whose `:before` / `:after`
  snapshots carry a populated `:data` (token + blob + a plain sibling), with the
  addressed id keyed under `id-key` and the frame stamped `:rf/default`."
  [id-key machine-id]
  {:operation :rf.machine/transition
   :tags      {id-key      machine-id
               :frame      :rf/default
               :before     {:state :anon
                            :data  {:retries 0
                                    :token   "secret-jwt-before"
                                    :blob    "huge-before"}}
               :after      {:state :authed
                            :data  {:retries 1
                                    :token   "secret-jwt-after"
                                    :blob    "huge-after"}}}})

(defn- machine-transition-event
  "The `:machine-id`-keyed transition event (the FALLBACK lookup branch)."
  [machine-id]
  (machine-transition-event* :machine-id machine-id))

;; ---- (2b) FULL machine :data slot coverage -------------------------------

(deftest started-data-slot-redacted-in-egress
  (testing ":rf.machine/started carries the booted snapshot's :data MAP
            directly (one level shallower than a snapshot); a frame-declared
            slot redacts / elides"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [out  (rf.classification/project-trace-event
                 {:operation :rf.machine/started
                  :tags      {:machine-id auth-id
                              :frame      :rf/default
                              :state      :anon
                              :data       {:retries 0
                                           :token   "secret-jwt-started"
                                           :blob    "huge-started"}}})
          data (get-in out [:tags :data])]
      (is (= [:rf/redacted true 0 false]
             [(:token data) (contains? (:blob data) :rf.size/large-elided) (:retries data)
              (.contains (pr-str out) "secret-jwt-started")])))))

(deftest guard-evaluated-input-data-redacted-in-egress
  (testing ":rf.machine/guard-evaluated carries :input {:data … :event …}; the
            :data sub-slot redacts, :event is left to project-event-tags"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [out   (rf.classification/project-trace-event
                  {:operation :rf.machine/guard-evaluated
                   :tags      {:machine-id auth-id
                               :frame      :rf/default
                               :guard-id   :ready?
                               :state      :anon
                               :outcome    :pass
                               :input      {:data  {:retries 0
                                                    :token   "secret-jwt-guard"
                                                    :blob    "huge-guard"}
                                            :event [:login]}}})
          input (get-in out [:tags :input])]
      (is (= [:rf/redacted true [:login] false]
             [(get-in input [:data :token])
              (contains? (get-in input [:data :blob]) :rf.size/large-elided)
              (:event input)
              (.contains (pr-str out) "secret-jwt-guard")])))))

(deftest transition-cascade-data-deltas-redacted-in-egress
  (testing "a :rf.machine/transition's :cascade carries per-step :data-delta
            maps keyed by :data keys directly; each delta redacts"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [out           (rf.classification/project-trace-event
                          {:operation :rf.machine/transition
                           :tags      {:machine-id auth-id
                                       :frame      :rf/default
                                       :cascade    [{:kind       :action
                                                     :state      []
                                                     :action     :authenticate
                                                     :data-delta {:token   "secret-jwt-delta"
                                                                  :blob    "huge-delta"
                                                                  :retries 1}}
                                                    {:kind :entry :state [:authed] :data-delta {}}]}})
          [step0 step1] (get-in out [:tags :cascade])]
      (is (= [:rf/redacted true 1 {} false]
             [(get-in step0 [:data-delta :token])
              (contains? (get-in step0 [:data-delta :blob]) :rf.size/large-elided)
              (get-in step0 [:data-delta :retries])
              (:data-delta step1)
              (.contains (pr-str out) "secret-jwt-delta")])))))

;; ---- (2c) :actor-id PREFERRED-branch coverage ----------------------------

(deftest actor-id-takes-precedence-over-machine-id-in-snapshot-egress
  (testing "when a transition carries BOTH ids the lookup PREFERS :actor-id:
            a frame declaration on the :actor-id snapshot path redacts even
            though :machine-id points at an UNDECLARED machine"
    (reg-auth-machine!)
    (rf/reg-machine :rf.machine-redaction/undeclared-sibling
      {:initial :idle :data {:n 0} :states {:idle {}}})
    (declare-frame-marks! auth-id)
    (let [out (rf.classification/project-trace-event
                (-> (machine-transition-event* :actor-id auth-id)
                    (assoc-in [:tags :machine-id] :rf.machine-redaction/undeclared-sibling)))]
      (is (= [:rf/redacted false]
             [(get-in out [:tags :after :data :token]) (.contains (pr-str out) "secret-jwt")])))))

;; ---- (3) a [:schemas :data] :sensitive? prop does NOT classify durable :data
;;          at snapshot egress.

(deftest schema-sensitive-prop-does-not-classify-durable-data
  (testing "a [:schemas :data] :sensitive? / :large? slot prop
            does NOT, by itself, redact durable :data at snapshot egress; with
            NO frame declaration the marked slot rides RAW (there is no
            schema→marks bridge; only a declared path — frame or machine —
            classifies)"
    (reg-auth-machine!)
    (is (= {:retries 1 :token "secret-jwt-after" :blob "huge-after"}
           (get-in (rf.classification/project-trace-event (machine-transition-event auth-id))
                   [:tags :after :data])))))

;; ---- (4) the machine declaration (EP-0025 §subsystems), on live machines ----

(defn- snapshot-elision-reg
  "The `:rf/default` frame's elision registry, read off runtime-db. A nil
  registry (a frame that never classified) reads as `{}`."
  []
  (or (get (:rf.db/runtime (rf/frame-state-value :rf/default)) :rf.runtime/elision) {}))

(deftest machine-declared-classification-lowers-at-singleton-boot
  (testing "a SINGLETON's projection-relative :sensitive /
            :large `:data` declaration lowers into the per-frame elision registry
            at first-boot, redacts at snapshot egress, and is DROPPED at destroy"
    (let [mid       :rf.machine-redaction/declared-singleton
          abs-token [:rf.runtime/machines :snapshots mid :data :token]
          abs-blob  [:rf.runtime/machines :snapshots mid :data :blob]
          lowered   #(let [reg (snapshot-elision-reg)]
                       [(set (map :source (get-in reg [:sensitive-declarations abs-token])))
                        (contains? (:declarations reg) abs-blob)])]
      (rf/reg-machine mid
        {:sensitive [[:data :token]]
         :large     [[:data :blob]]
         :initial   :anon
         :data      {:token nil :blob nil :retries 0}
         :states    {:anon {:on {:login :authed}} :authed {}}})
      (rf/dispatch-sync [mid [:rf.machine/noop]])
      (is (= [#{:machine} true] (lowered))
          "lowered to the ABSOLUTE snapshot paths at boot, under :source :machine")
      (let [out  (rf.classification/project-trace-event (machine-transition-event mid))
            data (get-in out [:tags :after :data])]
        (is (= [:rf/redacted true 1 false]
               [(:token data) (contains? (:blob data) :rf.size/large-elided) (:retries data)
                (.contains (pr-str out) "secret-jwt")])))
      (rf/reg-event :rf.machine-redaction/destroy-singleton
        (fn [_ _] {:fx [[:rf.machine/destroy mid]]}))
      (rf/dispatch-sync [:rf.machine-redaction/destroy-singleton])
      (is (= [#{} false] (lowered)) "both entries DROPPED at destroy (no leak)"))))

(defn- live-instance-ids
  "The live spawned-actor instance ids under `:rf/default`'s machines
  snapshots map whose name starts with `prefix` (the `<type>#n` ids)."
  [prefix]
  (->> (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
               [:rf.runtime/machines :snapshots])
       keys
       (filter #(clojure.string/starts-with? (name %) prefix))))

(deftest machine-declared-classification-lowers-per-spawned-instance
  (testing "a SPAWNED actor's :data declaration travels with
            the machine def and lowers PER INSTANCE at spawn (the generated
            <type>#n is classified with no per-instance author code), dropped at
            the actor's destroy"
    (rf/reg-machine :rf.machine-redaction/charge
      {:sensitive [[:data :token]]
       :initial   :charging
       :data      {:token nil}
       :states    {:charging {:on {:done :done}} :done {}}})
    (rf/reg-machine :rf.machine-redaction/supervisor
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :rf.machine-redaction/charge}
                           :on    {:stop :idle}}}})
    (rf/dispatch-sync [:rf.machine-redaction/supervisor [:go]])
    (let [spawned-id (first (live-instance-ids "charge"))
          abs-token  [:rf.runtime/machines :snapshots spawned-id :data :token]
          sources    #(set (map :source (get-in (snapshot-elision-reg)
                                                [:sensitive-declarations abs-token])))
          out        (rf.classification/project-trace-event
                       {:operation :rf.machine/snapshot-updated
                        :tags      {:actor-id spawned-id
                                    :frame    :rf/default
                                    :snapshot {:state :charging
                                               :data  {:token "secret-child-jwt"}}}})]
      (is (= [#{:machine} :rf/redacted false]
             [(sources) (get-in out [:tags :snapshot :data :token])
              (.contains (pr-str out) "secret-child-jwt")])
          "the spawned instance's :data :token lowered at spawn and redacts at egress")
      (rf/dispatch-sync [:rf.machine-redaction/supervisor [:stop]])
      (is (= #{} (sources)) "spawned instance's entry DROPPED at its destroy (no leak)"))))

(deftest machine-classification-malformed-declaration-fails-loud
  (testing "EP-0025 fail-loud-input — a malformed :sensitive / :large machine
            declaration is rejected at reg-machine with
            :rf.error/invalid-machine-classification"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"invalid-machine-classification|malformed"
          (rf/reg-machine :rf.machine-redaction/bad-decl
            {:sensitive {:data [:token]}   ;; a MAP, not a vector-of-paths
             :initial   :idle
             :data      {}
             :states    {:idle {}}}))
        "a non-vector :sensitive axis is rejected at registration")))

;; ---- (5) SOURCE-SCOPED teardown -------------------------------------------
;; The machine destroy drop must be source-scoped, like the effect clear
;; (`re-frame.elision/apply-classification-effects`) and the route lowering
;; (`re-frame.routing.classification/without-route-sourced`). Spec 015 L149
;; permits an app to ADDITIONALLY classify a subsystem absolute snapshot path
;; from a handler effect (`:source :effect`). If a machine teardown blanket-
;; dissoc'd the path it would un-redact the app's still-standing classification
;; — a privacy fail-open. The drop must remove only the machine's OWN
;; `:source :machine` contribution.

(deftest machine-and-effect-claims-union-and-remove-independently
  (testing "a machine's lowered :data claim and an app effect claim
            on the SAME absolute snapshot path UNION (both owners retained), and
            each removes INDEPENDENTLY: the effect clear leaves the machine claim
            standing; the actor destroy leaves the effect claim standing."
    (let [mid       :rf.machine-redaction/union-single
          abs-token [:rf.runtime/machines :snapshots mid :data :token]
          sources   #(set (map :source (get-in (snapshot-elision-reg)
                                               [:sensitive-declarations abs-token])))]
      (rf/reg-machine mid
        {:sensitive [[:data :token]]
         :initial   :anon
         :data      {:token nil :retries 0}
         :states    {:anon {:on {:login :authed}} :authed {}}})
      (rf/reg-event :rf.machine-redaction/union-classify
        (fn [{:keys [db]} _] {:db db :sensitive [abs-token]}))
      (rf/reg-event :rf.machine-redaction/union-clear
        (fn [{:keys [db]} _] {:db db :clear-sensitive [abs-token]}))
      (rf/reg-event :rf.machine-redaction/union-destroy
        (fn [_ _] {:fx [[:rf.machine/destroy mid]]}))
      (rf/dispatch-sync [mid [:rf.machine/noop]])
      (rf/dispatch-sync [:rf.machine-redaction/union-classify])
      (is (= #{:machine :effect} (sources)) "the effect claim UNIONS in alongside the machine claim")
      (rf/dispatch-sync [:rf.machine-redaction/union-clear])
      (is (= #{:machine} (sources)) "the machine claim SURVIVES the effect clear")
      (rf/dispatch-sync [:rf.machine-redaction/union-classify])
      (rf/dispatch-sync [:rf.machine-redaction/union-destroy])
      (is (= #{:effect} (sources)) "the effect claim SURVIVES the actor destroy — no fail-open"))))

;; ---- (6) EFFECT-FIRST boot: the machine's own claim must land DURABLY ------
;; The boot commit must not rebuild runtime-db from a coeffect that already
;; carries the effect's elision sub-tree: that would install the effect owner
;; alone and drop the machine claim the live lowering had just unioned in.

(deftest machine-boot-claim-survives-when-effect-pre-classified
  (testing "an effect that PRE-classifies a machine's absolute
            snapshot path does NOT prevent the machine's own :source :machine
            claim from landing durably at first-boot; both owners union, and
            the machine claim keeps the path redacted after the effect clears"
    (let [mid       :rf.machine-redaction/effect-first-single
          abs-token [:rf.runtime/machines :snapshots mid :data :token]
          sources   #(set (map :source (get-in (snapshot-elision-reg)
                                               [:sensitive-declarations abs-token])))]
      (rf/reg-machine mid
        {:sensitive [[:data :token]]
         :initial   :anon
         :data      {:token nil :retries 0}
         :states    {:anon {:on {:login :authed}} :authed {}}})
      (rf/reg-event :rf.machine-redaction/ef-classify
        (fn [{:keys [db]} _] {:db db :sensitive [abs-token]}))
      (rf/reg-event :rf.machine-redaction/ef-clear
        (fn [{:keys [db]} _] {:db db :clear-sensitive [abs-token]}))
      (rf/dispatch-sync [:rf.machine-redaction/ef-classify])
      (is (= #{:effect} (sources)) "precondition: only the effect's claim before the machine boots")
      (rf/dispatch-sync [mid [:rf.machine/noop]])
      (is (= #{:machine :effect} (sources)) "the machine IS a durable claim owner after boot")
      (rf/dispatch-sync [:rf.machine-redaction/ef-clear])
      (let [out (rf.classification/project-trace-event (machine-transition-event mid))]
        (is (= [#{:machine} :rf/redacted false]
               [(sources) (get-in out [:tags :after :data :token]) (.contains (pr-str out) "secret-jwt")])
            "the machine's surviving claim keeps the value redacted after the effect cleared")))))

;; ---- (7) a thrown action's exception row ---------------------------------
;; A message routinely quotes the value that failed, so once a machine declares
;; any `:sensitive` path its exception row redacts the text and the raw
;; exception together with the `:exception-data`.

(def ^:private thrown-secret "S3CR3T-thrown-action")

(defn- thrown-action-row
  "Boot machine `mid` (its `spec` merged over a one-transition body), dispatch an
  event whose action throws an ex-info quoting `thrown-secret` in its message
  and its data, and return the `:rf.error/machine-action-exception` trace."
  [mid spec]
  (rf/reg-machine mid
    (merge {:initial :idle
            :data    {:token nil}
            :actions {:reject (fn [_] (throw (ex-info (str "rejected " thrown-secret)
                                                      {:k thrown-secret})))}
            :states  {:idle {:on {:submit {:target :done :action :reject}}} :done {}}}
           spec))
  (rf/dispatch-sync [mid [:rf.machine/start]])
  (rf.machines.test-support/with-trace-capture traces
    (rf/dispatch-sync [mid [:submit]])
    (first (filter #(= :rf.error/machine-action-exception (:operation %)) @traces))))

(deftest thrown-action-exception-slots-redacted-when-machine-sensitive
  (let [row (thrown-action-row :rf.machine-redaction/sensitive-thrower
                               {:sensitive [[:data :token]]})]
    (is (= [{:exception-data    :rf/redacted
             :exception-message :rf/redacted
             :reason            :rf/redacted
             :exception         :rf/redacted}
            true false]
           [(select-keys (:tags row) [:exception-data :exception-message :reason :exception])
            (:sensitive? row)
            (.contains (pr-str row) thrown-secret)]))))

(deftest thrown-action-exception-text-kept-when-machine-unclassified
  (let [tags (:tags (thrown-action-row :rf.machine-redaction/plain-thrower {}))]
    (is (= [{:k thrown-secret} (str "rejected " thrown-secret) "Machine action threw."]
           ((juxt :exception-data :exception-message :reason) tags)))
    (is (.contains (pr-str (:exception tags)) (str "rejected " thrown-secret))
        "the raw :exception prints its message onto the wire")))

;; ---- (8) every row that carries the thrown value -------------------------
;; A guard's row, an action's `:rf.machine/action-ran` row, an `:on-done`
;; callback's row, a destroy-time `:exit` row and an `:after` delay resolver's
;; row carry the raw `:exception` (the `:on-done` row its `:exception-message`
;; too) but no `:exception-data`, so the condition is asked of every machine
;; row that carries a thrown value.

(defn- throw-secret!
  "Throw an ex-info quoting `thrown-secret` in its message and its data."
  [_]
  (throw (ex-info (str "rejected " thrown-secret) {:k thrown-secret})))

(defn- thrown-value-rows
  "Run `drive!` and return every trace it emitted that carries an `:exception`."
  [drive!]
  (rf.machines.test-support/with-trace-capture traces
    (drive!)
    (filterv #(contains? (:tags %) :exception) @traces)))

(defn- guard-throw-rows [mid spec]
  (rf/reg-machine mid
    (merge {:initial :idle
            :data    {:token nil}
            :guards  {:check throw-secret!}
            :states  {:idle {:on {:submit {:guard :check :target :done}}} :done {}}}
           spec))
  (rf/dispatch-sync [mid [:rf.machine/start]])
  (thrown-value-rows #(rf/dispatch-sync [mid [:submit]])))

(defn- action-throw-rows [mid spec]
  (rf/reg-machine mid
    (merge {:initial :idle
            :data    {:token nil}
            :actions {:reject throw-secret!}
            :states  {:idle {:on {:submit {:target :done :action :reject}}} :done {}}}
           spec))
  (rf/dispatch-sync [mid [:rf.machine/start]])
  (thrown-value-rows #(rf/dispatch-sync [mid [:submit]])))

(defn- on-done-throw-rows [mid spec]
  (let [child (keyword (namespace mid) (str (name mid) "-child"))]
    (rf/reg-machine child
      {:initial :running
       :states  {:running {:on {:finish :done}} :done {:final? true}}})
    (rf/reg-machine mid
      (merge {:initial :idle
              :data    {:token nil}
              :states  {:idle    {:on {:start :working}}
                        :working {:spawn {:machine-id child :on-done throw-secret!}}}}
             spec))
    (rf/dispatch-sync [mid [:start]])
    (let [child-id (get-in (rf.machines.test-support/runtime-db)
                           [:rf.runtime/machines :spawned mid [:working]])]
      (thrown-value-rows #(rf/dispatch-sync [child-id [:finish]])))))

(defn- destroy-exit-throw-rows [mid spec]
  (rf/reg-machine mid
    (merge {:initial :a
            :data    {:token nil}
            :actions {:boom throw-secret!}
            :states  {:a {:exit :boom}}}
           spec))
  (rf/reg-event :rf.machine-redaction/destroy (fn [_ [_ id]] {:fx [[:rf.machine/destroy id]]}))
  (rf/dispatch-sync [mid [:rf.machine/start]])
  (thrown-value-rows #(rf/dispatch-sync [:rf.machine-redaction/destroy mid])))

(defn- after-fn-throw-rows [mid spec]
  (rf/reg-machine mid
    (merge {:initial :idle
            :data    {:token nil}
            :states  {:idle    {:on {:go :running}}
                      :running {:after {throw-secret! :timeout}}
                      :timeout {}}}
           spec))
  (rf/dispatch-sync [mid [:rf.machine/start]])
  (thrown-value-rows #(rf/dispatch-sync [mid [:go]])))

(def ^:private throw-sites
  "Each place a machine's code throws, with the operations of the rows that then
  carry the thrown value."
  [["guard"        guard-throw-rows
    #{:rf.machine/guard-evaluated :rf.error/machine-action-exception}]
   ["action"       action-throw-rows
    #{:rf.machine/action-ran :rf.error/machine-action-exception}]
   ["on-done"      on-done-throw-rows
    #{:rf.error/machine-action-exception}]
   ["destroy-exit" destroy-exit-throw-rows
    #{:rf.machine/action-ran :rf.error/machine-action-exception}]
   ["after-fn"     after-fn-throw-rows
    #{:rf.error/machine-after-fn-threw}]])

(deftest every-thrown-value-row-redacted-when-machine-sensitive
  (doseq [[label rows-of ops] throw-sites]
    (let [rows (rows-of (keyword "rf.machine-redaction" (str label "-sensitive"))
                        {:sensitive [[:data :token]]})]
      (is (= [ops true []]
             [(set (map :operation rows))
              (every? :sensitive? rows)
              (into [] (comp (filter #(.contains (pr-str %) thrown-secret)) (map :operation)) rows)])
          label))))

(deftest every-thrown-value-row-keeps-its-message-when-machine-unclassified
  (doseq [[label rows-of ops] throw-sites]
    (let [rows (rows-of (keyword "rf.machine-redaction" (str label "-plain")) {})]
      (is (= [ops true]
             [(set (map :operation rows))
              (every? #(.contains (pr-str %) (str "rejected " thrown-secret)) rows)])
          label))))

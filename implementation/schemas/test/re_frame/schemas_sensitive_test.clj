(ns re-frame.schemas-sensitive-test
  "JVM tests for the `:sensitive?` redaction contract in schema-validation
  error traces (Spec 010 §`:sensitive?`).

  When a registered schema declares a slot `:sensitive?`, a validation
  failure replaces every value-bearing tag (`:value`, `:received`,
  `:explain`, `:explain-humanized`, `:rf.fx/args`, `:rf.sub/query-v`) with
  `:rf/redacted`, redacts every app-db `:path` segment past the declared
  `:map` keys, and stamps the event's top-level `:sensitive? true`. The
  decision is schema-wide, so it covers an app-db failure's narrowed
  `:value` too. Structural tags ride unchanged.
  A schema the walker cannot see into fails closed; a `:large?` slot elides
  to a size marker unless sensitivity already redacted it.

  An `app-db` failure is also projected through the frame's elision registry,
  so a path classified there needs no second `:sensitive?` on its schema."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.schemas.validate :as rf.schemas.validate]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- failure-trace
  "The first schema-validation-failure trace in `traces`."
  [traces]
  (first (filter #(= :rf.error/schema-validation-failure (:operation %)) traces)))

(defn- app-db-failure-trace
  "Register `schema` at `path` as the frame's only app-schema, validate `db`
  (which must fail it), and return the failure trace."
  [path schema db failing-id]
  (rf.registrar/clear-kind! :app-schema)
  (rf/reg-app-schema path schema)
  (with-trace-recorder! [traces]
    (rf.schemas/validate-app-schema! db failing-id)
    (failure-trace @traces)))

;; ---- the walker ------------------------------------------------------------

(deftest extract-sensitive-paths-claims-each-flagged-slot
  (are [schema base-path expected]
       (= expected (rf.schemas/extract-sensitive-paths-from-schema schema base-path))
    ;; slot-level: the slot's own props carry the flag
    [:map [:user :string] [:password {:sensitive? true} :string]]
    []
    {[:password] {:sensitive? true :source :schema}}
    ;; base-path is prepended to every discovered slot path
    [:map [:password {:sensitive? true} :string]]
    [:auth]
    {[:auth :password] {:sensitive? true :source :schema}}
    ;; container-level: the schema's OWN props claim the base-path
    [:string {:sensitive? true}]
    [:auth :token]
    {[:auth :token] {:sensitive? true :source :schema}}
    ;; nested :map carries the path through every level
    [:map [:user [:map [:profile [:map [:ssn {:sensitive? true} :string]]]]]]
    []
    {[:user :profile :ssn] {:sensitive? true :source :schema}}
    ;; a homogeneous container descends at the same base-path
    [:vector [:string {:sensitive? true}]]
    [:tokens]
    {[:tokens] {:sensitive? true :source :schema}}
    ;; :tuple / :catn claim each element at its position, not the base-path;
    ;; a :catn name is decorative (Malli reports the index in :in)
    [:tuple :int [:string {:sensitive? true}]]
    []
    {[1] {:sensitive? true :source :schema}}
    [:catn [:id [:= :id]] [:tok {:sensitive? true} :string]]
    []
    {[1] {:sensitive? true :source :schema}}
    [:catn [:id [:= :id]] [:payload [:map [:pw {:sensitive? true} :string] [:age :int]]]]
    []
    {[1 :pw] {:sensitive? true :source :schema}}))

(deftest sensitive-extractor-hook-walks-a-per-request-schema
  (testing "the cross-artefact hook returns the marks of a schema built per
            call (managed HTTP `:decode`)"
    (let [hook   (rf.late-bind/get-fn :schemas/extract-sensitive-paths-from-schema)
          schema [:map [:id [:= 7]] [:ssn {:sensitive? true} :string]]]
      (is (= {[:ssn] {:sensitive? true :source :schema}} (hook schema []))))))

(deftest schema-has-opaque-child-predicate
  (testing "a bare fn or symbol is flag-free nested as a slot tail (its entry is
            where a flag would live) but fails closed used as the whole schema"
    (are [expected schema] (= expected (rf.schemas/schema-has-opaque-child? schema))
      false [:map [:n pos-int?]]
      false [:map [:n 'pos-int?]]
      true  pos-int?
      true  'pos-int?)))

;; ---- app-db validation -------------------------------------------------------

(deftest app-db-validation-redacts-sensitive-slot
  (testing "every value-bearing slot of a sensitive failure is the sentinel —
            never a size marker, which would leak :bytes — and the structural
            slots survive"
    (let [secret "TOP-SECRET-token-9f3a2"]
      (rf/reg-app-schema [:auth :token] [:int {:sensitive? true :large? true}])
      (with-trace-recorder! [traces]
        (rf.schemas/validate-app-schema! {:auth {:token secret}} :auth/init-bad)
        (let [[v & more] (filter #(= :rf.error/schema-validation-failure (:operation %))
                                 @traces)]
          (is (nil? more) "one failure, one trace")
          (is (true? (:sensitive? v)))
          (is (= {:value             :rf/redacted
                  :explain           :rf/redacted
                  :explain-humanized :rf/redacted
                  :path              [:auth :token]
                  :failing-id        :auth/init-bad
                  :where             :app-db}
                 (select-keys (:tags v) [:value :explain :explain-humanized :path
                                         :failing-id :where :large?])))
          (is (not (str/includes? (pr-str v) secret))))))))

;; A sensitive failure's :path keeps the declared :map keys of Malli's :in and
;; redacts every later segment: a :set element, a :map-of key and a closed
;; map's extra key can be the caller's data, and an index or a branch tail sits
;; where the schema cannot name what follows.

;; ---- the frame's elision registry ------------------------------------------
;;
;; A path the frame's registry classifies is projected through it, as
;; `:rf.sub/run` and `:rf.flow/computed` are at emit: `:value` is walked at its
;; own path, and `:explain`, which re-ships the whole registered value, is
;; redacted whole (sensitive) or replaced by a size marker (large).

(defn- dispatch-failure-trace
  "Dispatch `event` on `:rf/default` and return its schema-validation-failure trace."
  [event]
  (with-trace-recorder! [traces]
    (rf/dispatch-sync event)
    (failure-trace @traces)))

(defn- registry-redaction [trace]
  [(:sensitive? trace) (get-in trace [:tags :value]) (get-in trace [:tags :explain])])

(deftest app-db-validation-redacts-a-registry-sensitive-path
  (rf/reg-app-schema [:auth :token] [:map [:token :int]])
  (rf/reg-event :auth/write
    (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:auth :token] {:token v})}))
  (testing "a commit-plane :sensitive declaration"
    (rf/reg-event :auth/classify (fn [_ _] {:sensitive [[:auth :token]]}))
    (rf/dispatch-sync [:auth/classify])
    (let [trace (dispatch-failure-trace [:auth/write "hunter2-SECRET"])]
      (is (not (str/includes? (pr-str trace) "hunter2-SECRET")))
      (is (= [true :rf/redacted :rf/redacted] (registry-redaction trace)))))
  (testing "a flow's own :sensitive output"
    (rf/reg-app-schema [:auth :derived] [:map [:token :int]])
    (rf/reg-event :auth/seed (fn [{:keys [db]} [_ s]] {:db (assoc db :secret s)}))
    (rf/reg-flow :auth/derived {:inputs [[:secret]] :output-path [:auth :derived] :sensitive [[]]}
      (fn [s] (when s {:token s})))
    (rf/make-frame {:id :rf/default :flows [:auth/derived]})
    (let [trace (dispatch-failure-trace [:auth/seed "flow-SECRET"])]
      (is (not (str/includes? (pr-str trace) "flow-SECRET")))
      (is (= [true :rf/redacted :rf/redacted] (registry-redaction trace))))))

(defn- installed-registry []
  [(rf.elision/sensitive-declarations :rf/default) (rf.elision/declarations :rf/default)])

(deftest app-db-validation-redacts-a-path-the-same-event-classifies
  (testing "a :sensitive returned by the event that writes the failing value
            governs its failure trace exactly as an already-classified path
            does, and neither rejected write touches the installed db or
            registry"
    (rf/reg-app-schema [:vault :key] [:map [:key :int]])
    (rf/reg-app-schema [:session :key] [:map [:key :int]])
    (rf/reg-event :vault/classify (fn [_ _] {:sensitive [[:vault :key]]}))
    (rf/reg-event :secret/write
      (fn [{:keys [db]} [_ path v classify?]]
        (cond-> {:db (assoc-in db path {:key v})}
          classify? (assoc :sensitive [path]))))
    (rf/dispatch-sync [:vault/classify])
    (doseq [[case-name path classify?] [["pre-classified" [:vault :key] false]
                                        ["same-event" [:session :key] true]]]
      (testing case-name
        (let [secret   (str case-name "-SECRET")
              db0      (rf/app-db-value :rf/default)
              reg0     (installed-registry)
              failures (with-trace-recorder! [traces]
                         (rf/dispatch-sync [:secret/write path secret classify?])
                         (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                                        (= :app-db (get-in % [:tags :where])))
                                  @traces))]
          (is (= 1 (count failures)) "one app-db failure")
          (is (not (str/includes? (pr-str failures) secret)) "no raw secret in any slot")
          (is (= [true :rf/redacted :rf/redacted] (registry-redaction (first failures))))
          (is (= db0 (rf/app-db-value :rf/default)) "the write is rejected")
          (is (= reg0 (installed-registry)) "the registry is untouched"))))))

(deftest app-db-validation-size-elides-a-registry-large-path
  (let [blob (apply str (repeat 40 "BLOB-"))]
    (rf/reg-app-schema [:reports :part] [:map [:n :int]])
    (rf/reg-event :reports/classify (fn [_ _] {:large [[:reports :part :blob]]}))
    (rf/reg-event :reports/write
      (fn [{:keys [db]} _] {:db (assoc-in db [:reports :part] {:blob blob :n "not-an-int"})}))
    (rf/dispatch-sync [:reports/classify])
    (let [trace (dispatch-failure-trace [:reports/write])]
      (is (not (str/includes? (pr-str trace) "BLOB-BLOB")))
      (is (= ["not-an-int" true true nil]
             [(get-in trace [:tags :value]) (get-in trace [:tags :large?])
              (contains? (get-in trace [:tags :explain]) :rf.size/large-elided)
              (:sensitive? trace)])
          ":value is the unclassified leaf; :explain is a size marker"))))

(deftest app-db-validation-sensitive-path-keeps-declared-map-keys
  (testing "each sensitive failure shape redacts :value and :explain, stamps
            :sensitive?, keeps the declared :map keys of :path, redacts every
            later segment, and leaks no secret anywhere in the trace"
    (doseq [{:keys [desc path schema db expected-path secrets]}
            [{:desc          ":maybe contributes no :in segment, so declared keys either side survive"
              :path          [:root]
              :schema        [:map [:s [:maybe [:map [:k {:sensitive? true} :string]]]]]
              :db            {:root {:s {:k 99}}}
              :expected-path [:root :s :k]}
             {:desc          ":vector element map — the index ends the declared prefix"
              :path          [:items]
              :schema        [:vector [:map [:token {:sensitive? true} :string]]]
              :db            {:items [{:token "ok"} {:token 99}]}
              :expected-path [:items :rf/redacted :rf/redacted]}
             {:desc          ":map-of value map — the key is data"
              :path          [:by-id]
              :schema        [:map-of :string [:map [:secret {:sensitive? true} :string]]]
              :db            {:by-id {"a" {:secret 99}}}
              :expected-path [:by-id :rf/redacted :rf/redacted]}
             {:desc          ":sequential element map, as :vector"
              :path          [:log]
              :schema        [:sequential [:map [:pw {:sensitive? true} :string]]]
              :db            {:log [{:pw 1}]}
              :expected-path [:log :rf/redacted :rf/redacted]}
             {:desc          "map -> vector -> map chain keeps the declared key above the index"
              :path          [:accounts]
              :schema        [:map [:items [:vector [:map [:tok {:sensitive? true} :string]]]]]
              :db            {:accounts {:items [{:tok 99}]}}
              :expected-path [:accounts :items :rf/redacted :rf/redacted]}
             {:desc          ":tuple sensitive element 0 fails"
              :path          [:point]
              :schema        [:tuple [:string {:sensitive? true}] :int]
              :db            {:point [99 7]}
              :expected-path [:point :rf/redacted]}
             {:desc          "a leaf under a sensitive ancestor wrapped in a single-child :and"
              :path          [:root]
              :schema        [:map [:s {:sensitive? true} [:and [:map [:k :int]]]]]
              :db            {:root {:s {:k "SECRET-AND-9f3a"}}}
              :expected-path [:root :s :rf/redacted]
              :secrets       ["SECRET-AND-9f3a"]}
             {:desc          ":set element map — the element segment carries the sibling :ssn"
              :path          [:members]
              :schema        [:set [:map [:token {:sensitive? true} :string] [:ssn :string]]]
              :db            {:members #{{:token 123456789 :ssn "078-05-1120"}}}
              :expected-path [:members :rf/redacted :rf/redacted]
              :secrets       ["078-05-1120" "123456789"]}
             {:desc          ":map-of with a :sensitive? KEY schema"
              :path          [:by-token]
              :schema        [:map-of [:string {:sensitive? true}] [:map [:age :int]]]
              :db            {:by-token {"secret-token-123" {:age "not-an-int"}}}
              :expected-path [:by-token :rf/redacted :rf/redacted]
              :secrets       ["secret-token-123"]}
             {:desc          ":map-of with a COMPILED :sensitive? KEY"
              :path          [:by-token]
              :schema        [:map-of (m/schema [:string {:sensitive? true}]) [:map [:age :int]]]
              :db            {:by-token {"SECRET-KEY-XYZ" {:age "not-an-int"}}}
              :expected-path [:by-token :rf/redacted :rf/redacted]
              :secrets       ["SECRET-KEY-XYZ"]}
             {:desc          ":map-of whose KEY is a vector wrapper hiding a compiled schema"
              :path          [:by-token]
              :schema        [:map-of [:and (m/schema [:string {:sensitive? true}])] [:map [:age :int]]]
              :db            {:by-token {"NESTED-SECRET-ABC" {:age "not-an-int"}}}
              :expected-path [:by-token :rf/redacted :rf/redacted]
              :secrets       ["NESTED-SECRET-ABC"]}
             {:desc          "sensitive :map-of KEY in a later conjunct of a multi-child :and"
              :path          [:secrets]
              :schema        [:and
                              [:map-of :string [:map [:age :int]]]
                              [:map-of [:string {:sensitive? true}] [:map [:age :int]]]]
              :db            {:secrets {"secret-token-xyz" {:age "not-an-int"}}}
              :expected-path [:secrets :rf/redacted :rf/redacted]
              :secrets       ["secret-token-xyz"]}
             {:desc          ":set of sensitive scalars under an :orn — the element is data"
              :path          [:tokens]
              :schema        [:orn [:tokens [:set [:string {:sensitive? true}]]]]
              :db            {:tokens #{123456789}}
              :expected-path [:tokens :rf/redacted]
              :secrets       ["123456789"]}
             {:desc          "a sensitive closed map's undeclared extra key is caller data"
              :path          [:profile]
              :schema        [:map {:closed true :sensitive? true} [:known :int]]
              :db            {:profile {:known 1 "SECRET-KEY-7f93" 2}}
              :expected-path [:profile :rf/redacted]
              :secrets       ["SECRET-KEY-7f93"]}]]
      (let [v (app-db-failure-trace path schema db :segment/bad)]
        (is (true? (:sensitive? v)) desc)
        (is (= {:value :rf/redacted :explain :rf/redacted :path expected-path}
               (select-keys (:tags v) [:value :explain :path]))
            desc)
        (doseq [secret secrets]
          (is (not (str/includes? (pr-str v) secret)) (str desc " — " secret)))))))

(deftest app-db-validation-non-sensitive-leaf-beside-sensitive-sibling-redacts
  (testing "one rule for every slot: a failure at a NON-sensitive leaf beside a
            conforming sensitive sibling redacts the narrowed :value as well as
            :explain, and :path keeps only the declared :map keys"
    (doseq [{:keys [desc path schema db expected-path secret]}
            [{:desc          ":map sibling"
              :path          [:user]
              :schema        [:map [:secret {:sensitive? true} :string] [:age :int]]
              :db            {:user {:secret "SECRET-SIB-4b1d" :age "not-an-int"}}
              :expected-path [:user :age]
              :secret        "SECRET-SIB-4b1d"}
             {:desc          ":vector element"
              :path          [:people]
              :schema        [:vector [:map [:secret {:sensitive? true} :string] [:age :int]]]
              :db            {:people [{:secret "SECRET-OK-9f3a" :age "no"}]}
              :expected-path [:people :rf/redacted :rf/redacted]
              :secret        "SECRET-OK-9f3a"}
             {:desc          ":tuple position"
              :path          [:point]
              :schema        [:tuple [:string {:sensitive? true}] :int]
              :db            {:point ["SECRET-TUP-ok" "not-an-int"]}
              :expected-path [:point :rf/redacted]
              :secret        "SECRET-TUP-ok"}
             {:desc          ":set element"
              :path          [:sessions]
              :schema        [:set [:map [:uid :int] [:token {:sensitive? true} :string]]]
              :db            {:sessions #{{:uid "not-an-int" :token "SECRET-TOKEN-A1"}}}
              :expected-path [:sessions :rf/redacted :rf/redacted]
              :secret        "SECRET-TOKEN-A1"}]]
      (let [v (app-db-failure-trace path schema db :sibling/bad)]
        (is (true? (:sensitive? v)) desc)
        (is (= {:value :rf/redacted :explain :rf/redacted :path expected-path}
               (select-keys (:tags v) [:value :explain :path]))
            desc)
        (is (not (str/includes? (pr-str v) secret)) desc)))))

(deftest app-db-validation-non-sensitive-closed-map-extra-key-stays-precise
  (testing "redaction is opt-in: a non-sensitive failure is unstamped, keeps
            its precise :path and carries the real humanized payload"
    (let [v (app-db-failure-trace [:plain] [:map {:closed true} [:known :int]]
                                  {:plain {:known 1 "extra-key" 2}} :plain/bad)]
      (is (not (contains? v :sensitive?)))
      (is (= [:plain "extra-key"] (-> v :tags :path)))
      (is (not (contains? #{nil :rf/redacted} (-> v :tags :explain-humanized)))))))

(deftest app-db-validation-opaque-schema-fails-closed
  (testing "a schema whose flags the walker cannot see — compiled at the root,
            compiled at the failing leaf, or behind a local :registry — redacts
            fail-closed"
    (doseq [{:keys [desc path schema db secret]}
            [{:desc   "compiled root"
              :path   [:user]
              :schema (m/schema [:map [:token {:sensitive? true} :string]])
              :db     {:user {:token ["OPAQUE-APPDB-SECRET-u9bjgr"]}}
              :secret "OPAQUE-APPDB-SECRET-u9bjgr"}
             {:desc   "compiled leaf the :in path resolves straight onto"
              :path   [:token]
              :schema [:map [:token (m/schema [:string {:sensitive? true}])]]
              :db     {:token {:token ["NESTED-OPAQUE-APPDB-SECRET-hi0tf8"]}}
              :secret "NESTED-OPAQUE-APPDB-SECRET-hi0tf8"}
             {:desc   "local :registry"
              :path   [:auth]
              :schema [:schema {:registry {::user [:map [:pw {:sensitive? true} :string]]}} ::user]
              :db     {:auth {:pw ["LOCAL-REGISTRY-APPDB-SECRET-amgtr"]}}
              :secret "LOCAL-REGISTRY-APPDB-SECRET-amgtr"}]]
      (let [v (app-db-failure-trace path schema db :opaque/bad)]
        (is (true? (:sensitive? v)) desc)
        (is (= {:value :rf/redacted :explain :rf/redacted}
               (select-keys (:tags v) [:value :explain]))
            desc)
        (is (not (str/includes? (pr-str v) secret)) desc)))))

;; A regex element consumes zero or many values, so a :cat input index is not
;; its schema child index. The redaction decision is schema-wide, so a sequence
;; of any width redacts when the schema marks anything sensitive, and one
;; declaring nothing sensitive keeps its value.

(def ^:private seq-secret "SEQ-WIDTH-SECRET-gwye11")

(def ^:private seq-sensitive-map [:map [:token {:sensitive? true} :int]])

(deftest app-db-validation-variable-width-sequence-never-leaks-sensitive-value
  (doseq [{:keys [desc schema value expected]}
          [{:desc     "zero-width :* prefix in a :cat"
            :schema   [:cat [:* :int] seq-sensitive-map]
            :value    [{:token seq-secret}]
            :expected :rf/redacted}
           {:desc     "a sensitive :map-of KEY after a nested :cat would ride :path"
            :schema   [:cat [:cat :int :int]
                       [:map-of [:string {:sensitive? true}] :int]
                       :string :string]
            :value    [1 2 {seq-secret "not-an-int"} "a" "b"]
            :expected :rf/redacted}
           {:desc     "a variable-width :cat declaring nothing sensitive"
            :schema   [:cat [:* :int] [:map [:name :string]]]
            :value    [{:name 5}]
            :expected {:name 5}}
           {:desc     "a fixed-width :cat failing beside a sensitive element"
            :schema   [:cat :int seq-sensitive-map]
            :value    ["not-an-int" {:token 1}]
            :expected :rf/redacted}
           {:desc     "a slot outside a variable-width sequence holding a sensitive element"
            :schema   [:map [:seq [:cat [:* :int] seq-sensitive-map]] [:label :string]]
            :value    {:seq [{:token 1}] :label 42}
            :expected :rf/redacted}]]
    (let [v (app-db-failure-trace [:items] schema {:items value} :items/bad)]
      (is (= expected (-> v :tags :value)) desc)
      (when (= :rf/redacted expected)
        (is (not (str/includes? (pr-str v) seq-secret)) desc)))))

;; ---- event, sub-return and cofx validation -----------------------------------
;; These surfaces carry the WHOLE checked value in every value-bearing slot, so
;; a sensitive slot anywhere in the schema redacts them all — a conforming
;; sensitive sibling of the failing slot rides inside the whole value.

(deftest event-validation-cat-root-conforming-sensitive-sibling-redacted-whole-received
  (let [secret "pw-MUST-NOT-LEAK"]
    (with-trace-recorder! [traces]
      (rf.schemas/validate-event! :auth/profile [:auth/profile {:password secret :age "old"}]
                                  {:schema [:cat [:= :auth/profile]
                                            [:map [:password {:sensitive? true} :string] [:age :int]]]})
      (let [v (failure-trace @traces)]
        (is (true? (:sensitive? v)))
        (is (= {:received :rf/redacted :value :rf/redacted :explain :rf/redacted}
               (select-keys (:tags v) [:received :value :explain])))
        (is (not (str/includes? (pr-str v) secret)))))))

(deftest event-validation-nested-opaque-schema-fails-closed
  (testing "a compiled schema nested in a vector-form event schema hides its
            flags from the walker, so the failure redacts fail-closed"
    (let [secret "NESTED-OPAQUE-EVENT-SECRET-hi0tf8"]
      (with-trace-recorder! [traces]
        (rf.schemas/validate-event! :auth/login [:auth/login {:password [secret]}]
                                    {:schema [:cat [:= :auth/login]
                                              (m/schema [:map [:password {:sensitive? true} :string]])]})
        (let [v (failure-trace @traces)]
          (is (true? (:sensitive? v)))
          (is (= {:received :rf/redacted :value :rf/redacted :explain :rf/redacted}
                 (select-keys (:tags v) [:received :value :explain])))
          (is (not (str/includes? (pr-str v) secret))))))))

(deftest sub-return-validation-redacts-when-sensitive
  (testing "every value-bearing slot of a sensitive sub-return failure redacts,
            the caller's query vector included — it is the lookup key"
    (with-trace-recorder! [traces]
      (rf.schemas/validate-sub! :token-for [:token-for "user-42-token"] 99
                                {:schema [:string {:sensitive? true}]})
      (let [v (failure-trace @traces)]
        (is (true? (:sensitive? v)))
        (is (= {:value             :rf/redacted
                :received          :rf/redacted
                :explain           :rf/redacted
                :explain-humanized :rf/redacted
                :rf.sub/query-v    :rf/redacted}
               (select-keys (:tags v) [:value :received :explain :explain-humanized
                                       :rf.sub/query-v])))))))

(deftest sub-return-validation-non-sensitive-rides-query-v-verbatim
  (testing "redaction is opt-in: a non-sensitive sub-return failure carries its
            query vector, its value and the real humanized payload"
    (with-trace-recorder! [traces]
      (rf.schemas/validate-sub! :widget [:widget :w1] 99 {:schema :string})
      (let [v (failure-trace @traces)]
        (is (not (contains? v :sensitive?)))
        (is (= {:rf.sub/query-v [:widget :w1] :value 99}
               (select-keys (:tags v) [:rf.sub/query-v :value])))
        (is (not (contains? #{nil :rf/redacted} (-> v :tags :explain-humanized))))))))

(deftest recordable-cofx-conforming-sensitive-sibling-redacted-whole-value
  (testing "the recordable-cofx failure carries the whole value, so a conforming
            sensitive sibling of the failing slot redacts it"
    (rf/reg-cofx :auth/ctx
      {:recordable? true :provided? true
       :schema [:map [:token {:sensitive? true} :string] [:count :int]]})
    (rf/reg-event :auth/use-ctx {:rf.cofx/requires [:auth/ctx]} (fn [_ _] {}))
    (with-trace-recorder! [traces]
      (try
        (rf/dispatch-sync [:auth/use-ctx]
                          {:rf.cofx {:auth/ctx {:token "SECRET-COFX-tok" :count "not-an-int"}}})
        (catch clojure.lang.ExceptionInfo _))
      (let [v (first (filter #(= :rf.error/cofx-value-invalid (:operation %)) @traces))]
        (is (true? (:sensitive? v)))
        (is (= :rf/redacted (-> v :tags :value)))
        (is (not (str/includes? (pr-str v) "SECRET-COFX-tok")))))))

(deftest redact-validation-tags-redacts-when-schema-sensitive
  (testing "the off-namespace seam scrubs the value-bearing slots, stamps
            :sensitive? and keeps the structural ones, for a schema declaring a
            sensitive slot and, fail-closed, for a compiled one"
    (let [secret "boundary-secret-9f3a"
          tags   {:where    :event
                  :event-id :auth/login
                  :source   :boundary
                  :received [:auth/login {:password secret}]
                  :value    [:auth/login {:password secret}]
                  :explain  {:value secret}}]
      (are [schema] (= {:where      :event
                        :event-id   :auth/login
                        :source     :boundary
                        :received   :rf/redacted
                        :value      :rf/redacted
                        :explain    :rf/redacted
                        :sensitive? true}
                       (rf.schemas.validate/redact-validation-tags schema tags))
        [:cat [:= :auth/login] [:map [:password {:sensitive? true} :string]]]
        (m/schema [:map [:password {:sensitive? true} :string]])))))

;; ---- :large? size elision ------------------------------------------------------
;; A :large? slot's failure elides the value-bearing slots to the
;; :rf.size/large-elided marker; sensitive wins, because the marker's :bytes
;; would leak a secret's size.

(deftest event-validation-large-slot-elides
  (let [blob (apply str (repeat 500 "Z"))]
    (with-trace-recorder! [traces]
      (rf.schemas/validate-event! :upload/save [:upload/save {:blob blob}]
                                  {:schema [:cat [:= :upload/save]
                                            [:map [:blob {:large? true} :int]]]})
      (let [v (failure-trace @traces)]
        (is (not (contains? v :sensitive?)))
        (is (true? (-> v :tags :large?)))
        (is (= :effect (-> v :tags :value :rf.size/large-elided :reason))
            "the marker carries the canonical classification provenance")
        (doseq [slot [:received :explain]]
          (is (contains? (-> v :tags slot) :rf.size/large-elided) (str slot)))
        (is (not (str/includes? (pr-str v) blob)))))))

(deftest large-and-sensitive-sensitive-wins
  (let [secret (apply str (repeat 100 "S"))]
    (with-trace-recorder! [traces]
      (rf.schemas/validate-event! :x [:x {:blob secret}]
                                  {:schema [:cat [:= :x]
                                            [:map [:blob {:large? true :sensitive? true} :int]]]})
      (let [v (failure-trace @traces)]
        (is (true? (:sensitive? v)))
        (is (= {:value :rf/redacted} (select-keys (:tags v) [:value :large?]))
            "the sentinel, not a size marker, and no :large? stamp")
        (is (not (str/includes? (pr-str v) secret)))))))

(deftest app-db-validation-large-slot-elides
  (let [blob (apply str (repeat 500 "Y"))
        v    (app-db-failure-trace [:upload] [:map [:blob {:large? true} :int]]
                                   {:upload {:blob blob}} :upload/bad)]
    (is (true? (-> v :tags :large?)))
    (is (contains? (-> v :tags :value) :rf.size/large-elided))
    (is (not (str/includes? (pr-str v) blob)))))

(deftest app-db-validation-large-leaf-beside-sensitive-sibling-redacts
  (testing "sensitive wins over large for every slot: a large leaf beside a
            sensitive sibling redacts, with no size marker and no :large? stamp"
    (let [blob (apply str "BLOB-SENTINEL-" (repeat 500 "L"))
          v    (app-db-failure-trace [:doc]
                                     [:map
                                      [:token {:sensitive? true} :string]
                                      [:pdf {:large? true} :int]]
                                     {:doc {:token "TOKEN-OK-7c1e" :pdf blob}}
                                     :doc/bad)]
      (is (true? (:sensitive? v)))
      (is (= {:path [:doc :pdf] :value :rf/redacted :explain :rf/redacted}
             (select-keys (:tags v) [:path :value :explain :large?])))
      (is (not (str/includes? (pr-str v) blob)))
      (is (not (str/includes? (pr-str v) "TOKEN-OK-7c1e"))))))

(deftest sub-return-large-elision-keeps-query-v
  (testing "the size marker replaces the checked value only; the query vector
            is the lookup key and rides verbatim"
    (with-trace-recorder! [traces]
      (rf.schemas/validate-sub! :probe/sub [:probe/sub 42] {:pdf (apply str (repeat 500 "Q"))}
                                {:schema [:map [:pdf {:large? true} :int]]})
      (let [tags (:tags (failure-trace @traces))]
        (is (= [:probe/sub 42] (:rf.sub/query-v tags)))
        (is (contains? (:value tags) :rf.size/large-elided))))))

(deftest redact-validation-tags-large-slot-elides
  (let [blob (apply str (repeat 300 "Q"))
        out  (rf.schemas.validate/redact-validation-tags
               [:map [:blob {:large? true} :int]]
               {:value {:blob blob} :explain {:value {:blob blob}}})]
    (is (true? (:large? out)))
    (is (contains? (:value out) :rf.size/large-elided))
    (is (contains? (:explain out) :rf.size/large-elided))))

(ns re-frame.resources-trace-scope-rule-egress-cljs-test
  "Resource identity off-box. The SCOPE takes the framework's standing rule:
  every non-global scope tokenizes content-free on every carrier and for every
  owner — a `[tier {identity}]` tuple keeping its tier, any other shape
  tokenizing whole — so the carriers of one scope on a row agree, and applying
  the rule stamps nothing. The PARAMS are the owner's: they ride unless the
  resource claims the whole entry `:sensitive?` (content-free) or `:large?`
  (a canonical digest).

  The projectors are called directly on trace tags, so the suite needs no
  trace bus and runs on both hosts. Dual-target (`.cljc` + `_cljs_test`): the
  JVM runner picks it up via the `.*-test$` ns regex, Shadow's `:node-test`
  build via the `cljs-test$` regex."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :resource registrar kind the projectors read owners from.
   [re-frame.resources]
   [re-frame.resources.reply :as rf.resources.reply]
   [re-frame.resources.scope-registry :as rf.resources.scope-registry]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as substrate]]
       :cljs [[re-frame.adapter.reagent :as substrate]])))

(def ^:private user-id "u-SECRET-42")

(def ^:private tuple-scope [:rf.scope/session {:user-id user-id :tenant "acme"}])
(def ^:private map-scope {:tenant "acme" :user-id user-id})
(def ^:private string-scope (str "tenant-" user-id))

(def ^:private params {:slug "welcome" :ids '(1 2 3)})

(defn- init! []
  (doseq [[id claim] [[:plain/profile {}]
                      [:secret/profile {:sensitive? true}]
                      [:bulky/profile {:large? true}]]]
    (rf/reg-resource id
      (merge {:scope         :rf.scope/global
              :params-schema [:map [:slug :string]]}
             claim)
      (fn [_ _] {:request {:method :get :url "/p"}}))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

(defn- project-row [tags]
  (rf.resources.trace-egress/project-resource-trace-egress tags :test/frame))

(defn- carries? [v] (str/includes? (pr-str v) user-id))

;; ---- the rule, shape by shape ---------------------------------------------

(deftest the-global-scope-rides-verbatim
  (is (= :rf.scope/global (rf.resources.trace-egress/project-scope :rf.scope/global))))

(deftest a-tier-tuple-keeps-its-tier-and-tokenizes-its-identity-content-free
  (let [projected (rf.resources.trace-egress/project-scope tuple-scope)]
    (is (= [:rf.scope/session (rf.resources.ssr/redact-value (second tuple-scope))] projected))
    (is (not (carries? projected)))
    (is (= projected
           (rf.resources.trace-egress/project-scope
             [:rf.scope/session {:user-id "someone-else" :tenant "other"}]))
        "content-free: every identity of one shape projects to one token")))

(deftest a-map-scope-tokenizes-whole
  (is (= (rf.resources.ssr/redact-value map-scope)
         (rf.resources.trace-egress/project-scope map-scope)))
  (is (not (carries? (rf.resources.trace-egress/project-scope map-scope)))))

(deftest a-string-or-other-scalar-scope-tokenizes-whole
  (doseq [scope [string-scope :tenant/acme [:rf.scope/session user-id]]]
    (let [projected (rf.resources.trace-egress/project-scope scope)]
      (is (= (rf.resources.ssr/redact-value scope) projected) (pr-str scope))
      (is (not (carries? projected))))))

(deftest the-rule-is-idempotent
  (doseq [scope [:rf.scope/global tuple-scope map-scope string-scope :rf/redacted nil]]
    (let [once (rf.resources.trace-egress/project-scope scope)]
      (is (= once (rf.resources.trace-egress/project-scope once)) (pr-str scope)))))

;; ---- one rule on every carrier of a row -----------------------------------

(deftest the-carriers-of-one-scope-agree-on-a-row-for-every-owner
  (doseq [rid   [:plain/profile :secret/profile :bulky/profile]
          scope [tuple-scope map-scope string-scope]]
    (let [k    [scope rid params]
          tags (project-row {:rf.frame/id  :test/frame
                             :scope        scope
                             :resource/key k
                             :matched      [k]
                             :identities   [k]
                             :work/id      [:rf.work/resource k 3]})
          want (rf.resources.trace-egress/project-scope scope)]
      (is (= [want want want want want]
             [(:scope tags)
              (first (:resource/key tags))
              (first (first (:matched tags)))
              (first (first (:identities tags)))
              (first (second (:work/id tags)))])
          (str rid " " (pr-str scope)))
      (is (not (carries? tags)) (str rid " " (pr-str scope))))))

(deftest a-replys-correlation-scope-agrees-with-its-free-scope
  (let [k     [tuple-scope :plain/profile params]
        reply {:rf.reply/work-id   [:rf.work/resource k 3]
               :rf.reply/work-kind rf.resources.reply/work-kind-resource
               :status             :ok
               :value              {:title "Welcome"}
               :params             params
               :scope              tuple-scope
               :resource/key       k
               :correlation        {:scope tuple-scope :generation 3 :rf.reply/resource-key k}}
        tags  (rf.resources.trace-egress/project-fx-args-egress
                {:rf.fx/args [:app/loaded reply]} :test/frame)
        out   (second (:rf.fx/args tags))
        want  (rf.resources.trace-egress/project-scope tuple-scope)]
    (is (= [want want want want]
           [(:scope out) (get-in out [:correlation :scope])
            (first (:resource/key out)) (first (get-in out [:correlation :rf.reply/resource-key]))]))
    (is (= [params {:title "Welcome"}] [(:params out) (:value out)])
        "a plain owner's params and data ride")
    (is (nil? (:sensitive? tags)) "the scope rule alone stamps nothing")))

;; ---- the params are the owner's -------------------------------------------

(deftest a-plain-owners-params-ride-byte-identical
  (let [k    [tuple-scope :plain/profile params]
        tags (project-row {:rf.frame/id :test/frame :resource/key k})]
    (is (identical? params (nth (:resource/key tags) 2)))
    (is (seq? (:ids (nth (:resource/key tags) 2))) "the list-valued param keeps its kind")))

(deftest a-coarse-owners-params-tokenize-by-its-claim
  (testing ":sensitive? — content-free"
    (let [k  (:resource/key (project-row {:resource/key [tuple-scope :secret/profile params]}))
          k2 (:resource/key (project-row {:resource/key [tuple-scope :secret/profile {:slug "other" :ids '(9)}]}))]
      (is (= (rf.resources.ssr/redact-value params :redact) (nth k 2)))
      (is (= (nth k 2) (nth k2 2)) "every params value of one shape projects to one token")))
  (testing ":large? — a canonical digest, so distinct params stay distinct"
    (let [k  (:resource/key (project-row {:resource/key [tuple-scope :bulky/profile params]}))
          k2 (:resource/key (project-row {:resource/key [tuple-scope :bulky/profile {:slug "other" :ids '(9)}]}))]
      (is (= (rf.resources.ssr/redact-value params :omit) (nth k 2)))
      (is (not= (nth k 2) (nth k2 2))))))

;; ---- the stamp is an owner's claim, never the scope rule ------------------

(deftest the-scope-rule-alone-stamps-nothing
  (let [k    [tuple-scope :plain/profile params]
        tags (project-row {:rf.frame/id :test/frame :scope tuple-scope :resource/key k :matched [k]})]
    (is (not (carries? tags)) "FIXTURE — the scope did tokenize")
    (is (not (contains? tags :sensitive?)))))

(deftest owner-claims-and-the-scope-resolved-row-keep-their-stamps
  (doseq [rid [:secret/profile :bulky/profile]]
    (is (true? (:sensitive? (project-row {:scope tuple-scope :resource/key [tuple-scope rid params]})))
        (str rid)))
  (is (true? (:sensitive? (project-row {:resource/key [tuple-scope :unregistered/profile params]})))
      "an unregistered owner fails closed")
  (let [resolved (rf.resources.scope-registry/project-scope-resolved-egress
                   {:resource-id :t/tenant :scope tuple-scope :input-values {:user user-id}})
        tags     (project-row resolved)]
    (is (= [:rf/redacted true] [(:scope tags) (:sensitive? tags)])
        "the sibling's sentinel rides as it is, and its stamp stands")))

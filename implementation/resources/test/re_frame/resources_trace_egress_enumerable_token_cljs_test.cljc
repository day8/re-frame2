(ns re-frame.resources-trace-egress-enumerable-token-cljs-test
  "The off-box resource trace egress must not mint an ENUMERABLE token for
  sensitive content. A tenant slug, an account id or a page cursor lives in a
  candidate space small enough to walk, so a content-derived hash of it can be
  enumerated or tested against; Spec 015's `:rf/redacted` carries no
  information about the underlying content.

  So the token is chosen by classification: a sensitive value (and every
  caller with no disposition to hand) gets a content-free shape token, a
  closed-vocabulary `:type` tag plus an integer `:count`, identical for every
  value of that shape; a large-only value, a size claim rather than a privacy
  claim, keeps a digest over `identity/canonical-bytes`. The suite is
  two-sided: a plain owner rides byte-identical and a large owner keeps
  distinct tokens, so redacting everything does not pass."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs and publishes the trace-egress hooks.
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

;; Two secrets of the SAME shape: a content-free token maps them to one value,
;; an enumerable token to two.
(def ^:private tenant-a "tenant-alpha-01")
(def ^:private tenant-b "tenant-brav0-02")
(def ^:private cursor-a "cursor-alpha-01")
(def ^:private cursor-b "cursor-brav0-02")

(defn- init! []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "Enumerable-token trace-egress suite frame."})
  (rf/reg-resource :sealed/feed
    {:scope         :rf.scope/global
     :sensitive?    true
     :params-schema [:map [:tenant :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/sealed"}}))
  (rf/reg-resource :bulky/feed
    {:scope         :rf.scope/global
     :large?        true
     :params-schema [:map [:tenant :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/bulky"}}))
  (rf/reg-resource :plain/feed
    {:scope         :rf.scope/global
     :params-schema [:map [:tenant :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/plain"}})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter #?(:clj rf.substrate.plain-atom/adapter :cljs rf.adapter.reagent/adapter)
     :init-fn init!}))

(defn- project-row
  "Project `tags` for OFF-BOX egress exactly as the epoch tool-pair does."
  [tags]
  (rf.resources.trace-egress/project-resource-trace-egress (assoc tags :rf.frame/id :rf/default) :rf/default))

(defn- key-for [resource-id tenant]
  (rf.resources.state/scoped-resource-key :rf.scope/global resource-id {:tenant tenant}))

(defn- leaks? [secret v]
  (str/includes? (pr-str v) secret))

(defn- projected-key [scoped-key]
  (:resource/key (project-row {:resource/key scoped-key})))

(def ^:private map-token {:rf/redacted {:type :map :count 1}})

(deftest sensitive-scoped-key-token-is-not-enumerable
  (let [ka (key-for :sealed/feed tenant-a)]
    (is (leaks? tenant-a ka) "premise: the RAW key carries the tenant in the clear")
    (is (= [map-token map-token :sealed/feed]
           [(nth (projected-key ka) 2) (nth (projected-key (key-for :sealed/feed tenant-b)) 2)
            (nth (projected-key ka) 1)])
        "two distinct tenants project to one closed-vocabulary token; the resource id still rides")))

(deftest free-scope-tag-identity-map-token-is-not-enumerable
  ;; an invalidation sweep's free scope tag has no owner claim that could
  ;; permit a digest; the tier keyword rides so a tool still reads the tier
  (is (= [[:rf.scope/session map-token] [:rf.scope/session map-token]]
         [(:scope (project-row {:scope [:rf.scope/session {:tenant-id tenant-a}]}))
          (:scope (project-row {:scope [:rf.scope/session {:tenant-id tenant-b}]}))])
      "two distinct session tenants are indistinguishable after projection")
  (is (= :rf.scope/global (:scope (project-row {:scope :rf.scope/global})))
      "a scalar scope rides verbatim"))

(deftest pagination-cursor-token-is-not-enumerable
  ;; the cursor is an app-derived free tag that can carry a record id; the
  ;; length is kept, since an integer cannot carry a fragment of it
  (let [k     (key-for :sealed/feed tenant-a)
        token {:rf/redacted {:type :string :count (count cursor-a)}}]
    (is (= [token token token]
           [(:page-param (project-row {:resource/key k :page-param cursor-a}))
            (:page-param (project-row {:resource/key k :page-param cursor-b}))
            (:next-page-param (project-row {:resource/key k :next-page-param cursor-a}))])
        "under a redacting owner, distinct cursors project to one token, on either cursor tag")))

(deftest plain-owner-rides-verbatim
  (let [k (key-for :plain/feed tenant-a)]
    (is (= {:resource/key k :page-param cursor-a}
           (select-keys (project-row {:resource/key k :page-param cursor-a}) [:resource/key :page-param]))
        "a :serialize owner declaring nothing keeps its key byte-identical and its cursor readable")))

(deftest large-owner-keeps-a-distinct-content-derived-token
  (let [ta (nth (projected-key (key-for :bulky/feed tenant-a)) 2)
        tb (nth (projected-key (key-for :bulky/feed tenant-b)) 2)]
    (is (= [false false true] [(leaks? tenant-a ta) (= ta tb) (string? (:rf/redacted ta))])
        "a :large? owner keeps a distinct digest per identity, still without plaintext"))
  (is (= (nth (projected-key (rf.resources.state/scoped-resource-key
                               :rf.scope/global :bulky/feed (array-map :tenant tenant-a :page 2))) 2)
         (nth (projected-key (rf.resources.state/scoped-resource-key
                               :rf.scope/global :bulky/feed (array-map :page 2 :tenant tenant-a))) 2))
      "the digest is over the canonical value, so construction order cannot change it"))

(ns re-frame.resources-derived-scope-sensitivity-cljs-test
  "Named-scope-resolver DERIVED-sensitivity propagation — EP-0016 wave
  (rf2-fi6tda.1), the fourth framework-known derivation graph EP-0015
  disposition 8 names (subs / flows / machine-selectors shipped in
  rf2-t55hxg.8; this is the scope-resolver arm rf2-t55hxg.11 deferred to
  EP-0016). Per Spec 015 §Derived sensitivity / Spec 016 §Resolver references.

  THE CONTRACT under test: when a resource's `:scope` is a `{:from-db <id>}`
  reference whose resolver reads a FRAME-SENSITIVE app-db path, the resolved
  resource's egress inherits `:sensitive` — even when the owning resource was
  NOT declared `:sensitive?` (automatic-inheritance defence-in-depth). The
  resolver honours the closed `:rf.egress/output-sensitivity` claim (the SAME
  claim subs/flows honour): `:rf.egress/inherit` (default, propagate),
  `:rf.egress/sensitive` (force), `:rf.egress/public` (declassify). The PRIMARY
  scope boundary (the resource-owned `:sensitive?` claim) holds independently —
  this arm only ADDS the inheritance precision.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via the `cljs-test$` regex.
  CLJC so the load-bearing JVM gate exercises it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.elision :as elision]
   [re-frame.frame :as frame]
   [re-frame.privacy :as privacy]
   [re-frame.registrar :as registrar]
   [re-frame.resources.classification :as classification]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   ;; load-bearing side-effecting requires: register the :resource +
   ;; :resource-scope registrar kinds + the schemas walker hooks.
   [re-frame.resources]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- fixture --------------------------------------------------------------

(def ^:private frame-id :rf/default)

(defn- init!
  "Register the named db-derived session resolver (reads the FRAME-SENSITIVE
  `[:auth :user :username]` path) and a session-scoped feed resource whose
  `:scope` is the `{:from-db …}` reference — but NOT itself declared
  `:sensitive?`. The frame declares `[:auth :user :username]` sensitive, so the
  derivation inherits."
  []
  (registrar/clear-kind! :resource-scope)
  (registrar/clear-kind! :resource)
  ;; FRAME classification: the viewer-identity path is sensitive.
  ;; EP-0025: classified via the commit-plane effect path (:source :effect) —
  ;; no longer a frame annotation.
  (rf/reg-frame frame-id {})
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :user :username]]})))
  ;; resolver reading the sensitive viewer-identity path (default :inherit)
  (rf/reg-resource-scope :t/session
    {:inputs  {:username [:db [:auth :user :username]]}
     :resolve (fn [{:keys [username]} _ctx]
                (when username [:rf.scope/session {:username username}]))})
  ;; a session-scoped feed resource — NOT declared :sensitive?
  (rf/reg-resource :t/feed
    {:scope         {:from-db :t/session}
     :params-schema [:map [:page :int]]}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}})))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter :init-fn init!}
       :cljs {:adapter reagent-adapter/adapter :init-fn init!})))

;; ---- helpers --------------------------------------------------------------

(defn- entry
  [resource-id data]
  (merge (state/empty-entry resource-id)
         {:status :loaded :data data :loaded-at 1000 :stale-at 9.0e15}))

;; rf2-9e0tyq — re-key into the runtime's byte-`key-id` :entries shape, stamping
;; each entry's `:resource/key`.
(defn- runtime-db-with [entries]
  {state/resources-key {:entries (into {}
                                       (map (fn [[sk e]]
                                              [(state/key-id sk) (assoc e :resource/key sk)]))
                                       entries)
                        :tag-index {} :owner-index {}}})

;; the projected SCOPED KEY rides as the wire entry's `:resource/key` (the map
;; key is the opaque byte id); return it as the first element.
(defn- only-wire [proj]
  (let [we (val (first (get-in proj [state/resources-key :entries])))]
    [(:resource/key we) we]))

(defn- session-key [u page]
  (state/scoped-resource-key [:rf.scope/session {:username u}] :t/feed {:page page}))

;; ===========================================================================
;; 1. resolver-derived-sensitive? — the propagation pass (unit level)
;; ===========================================================================

(deftest resolver-derived-sensitive-inherits-from-sensitive-input
  (testing "a resolver reading a FRAME-SENSITIVE :db input derives a sensitive
            scope (the inherit default propagates)"
    (is (true? (classification/resolver-derived-sensitive? :t/session frame-id)))))

(deftest resolver-derived-sensitive-no-inheritance-when-input-not-sensitive
  (testing "a resolver whose :db input does NOT overlap any frame-sensitive
            declaration does NOT derive a sensitive scope"
    (rf/reg-resource-scope :t/locale
      {:inputs  {:locale [:db [:i18n :locale]]}
       :resolve (fn [{:keys [locale]} _] (when locale [:rf.scope/locale {:locale locale}]))})
    (is (false? (classification/resolver-derived-sensitive? :t/locale frame-id)))))

(deftest resolver-derived-sensitive-force-and-declassify-claims
  (testing ":rf.egress/sensitive FORCES sensitive even from a non-sensitive input"
    (rf/reg-resource-scope :t/forced
      {:inputs  {:locale [:db [:i18n :locale]]}
       :rf.egress/output-sensitivity :rf.egress/sensitive
       :resolve (fn [{:keys [locale]} _] (when locale [:rf.scope/locale {:locale locale}]))})
    (is (true? (classification/resolver-derived-sensitive? :t/forced frame-id))))
  (testing ":rf.egress/public DECLASSIFIES even from a sensitive input"
    (rf/reg-resource-scope :t/declassified
      {:inputs  {:username [:db [:auth :user :username]]}
       :rf.egress/output-sensitivity :rf.egress/public
       :resolve (fn [{:keys [username]} _] (when username [:rf.scope/session {:username username}]))})
    (is (false? (classification/resolver-derived-sensitive? :t/declassified frame-id)))))

(deftest resolver-derived-sensitive-fail-closed-on-unregistered
  (testing "an unregistered resolver-id → false (no derivation graph; the owner
            :sensitive? boundary governs independently)"
    (is (false? (classification/resolver-derived-sensitive? :t/nope frame-id))))
  (testing "a nil frame-id (frameless projection) → false (no frame
            classification to inherit from)"
    (is (false? (classification/resolver-derived-sensitive? :t/session nil)))))

;; ===========================================================================
;; 2. whole-entry-disposition-for — the frame-aware consumption (unit level)
;; ===========================================================================

(deftest disposition-for-inherits-derived-sensitive
  (testing "a resource whose {:from-db} resolver derives sensitive REDACTS even
            though the resource is NOT declared :sensitive? (inheritance arm)"
    (let [spec (rf/resource-meta :t/feed)]
      (is (= :redact (classification/whole-entry-disposition-for spec frame-id)))
      (testing "frame-blind whole-entry-disposition is unchanged (:serialize) —
                the arm is the ADDED precision, not the primary boundary"
        (is (= :serialize (classification/whole-entry-disposition spec)))))))

(deftest disposition-for-no-inheritance-when-input-not-sensitive
  (testing "a resource whose {:from-db} resolver reads a NON-sensitive input
            serializes (no inheritance)"
    (rf/reg-resource-scope :t/locale
      {:inputs  {:locale [:db [:i18n :locale]]}
       :resolve (fn [{:keys [locale]} _] (when locale [:rf.scope/locale {:locale locale}]))})
    (rf/reg-resource :t/prefs
      {:scope         {:from-db :t/locale}
       :params-schema [:map]}
      (fn [_ _] {:request {:method :get :url "/prefs"}}))
    (let [spec (rf/resource-meta :t/prefs)]
      (is (= :serialize (classification/whole-entry-disposition-for spec frame-id))))))

(deftest disposition-for-owner-declared-case-unchanged
  (testing "the OWNER-declared :sensitive? case is unchanged — :redact whether
            or not derivation fires (the primary boundary, frame-blind)"
    (rf/reg-resource :t/secret-feed
      {:scope         {:from-db :t/session}
       :sensitive?    true
       :params-schema [:map [:page :int]]}
      (fn [_ _] {:request {:method :get :url "/secret"}}))
    (let [spec (rf/resource-meta :t/secret-feed)]
      (is (= :redact (classification/whole-entry-disposition-for spec frame-id)))
      (is (= :redact (classification/whole-entry-disposition spec))
          "owner :sensitive? redacts frame-blind too")))
  (testing "a :rf.scope/global (non-{:from-db}) resource has no derivation graph
            — its disposition is exactly the owner boundary"
    (rf/reg-resource :t/public-article
      {:scope         :rf.scope/global
       :params-schema [:map [:slug :string]]}
      (fn [_ _] {:request {:method :get :url "/a"}}))
    (let [spec (rf/resource-meta :t/public-article)]
      (is (= :serialize (classification/whole-entry-disposition-for spec frame-id)))))
  (testing "derived-sensitive wins over coarse :large? (sensitive is the more
            conservative shape — redact, not omit)"
    (rf/reg-resource :t/big-feed
      {:scope         {:from-db :t/session}
       :large?        true
       :params-schema [:map [:page :int]]}
      (fn [_ _] {:request {:method :get :url "/big"}}))
    (let [spec (rf/resource-meta :t/big-feed)]
      (is (= :redact (classification/whole-entry-disposition-for spec frame-id))
          "derived-sensitive upgrades the coarse :large? omit to redact"))))

;; ===========================================================================
;; 3. SSR projection END-TO-END — the resolved resource's egress inherits
;;    sensitive (data redacted + scoped-KEY redacted), even though the resource
;;    is NOT declared :sensitive?.
;; ===========================================================================

(deftest ssr-derived-sensitive-resource-redacts-data-and-key
  (testing "the feed entry (NOT declared :sensitive?) under a sensitive-derived
            scope ships METADATA ONLY: data redacted + scope/params in the wire
            KEY redacted to opaque digests (the derived scope embeds the
            sensitive viewer identity, so it must not ride raw)"
    (let [k   (session-key "jake" 1)
          e   (entry :t/feed {:articles [:a :b]})
          proj (rf/with-frame frame-id
                 (ssr/project-resources-runtime-db (runtime-db-with {k e})))
          [wk we] (only-wire proj)]
      (is (= privacy/redacted-sentinel (:data we))
          "the derived-sensitive entry's data is replaced by the redaction sentinel")
      (is (= :loaded (:status we)) "metadata (status) still rides")
      ;; the wire key: scope + params redacted to {:rf/redacted <digest>},
      ;; resource-id (position 1) preserved.
      (is (= :t/feed (nth wk 1)) "the resource-id rides at position 1 (never sensitive)")
      (is (contains? (nth wk 0) :rf/redacted) "the derived scope is redacted in the wire key")
      (is (contains? (nth wk 2) :rf/redacted) "the params are redacted in the wire key")
      ;; the raw viewer identity ("jake") must not ride anywhere.
      (is (not (str/includes? (pr-str proj) "jake"))
          "the raw sensitive viewer identity does not ride on the wire"))))

(deftest ssr-non-derived-resource-serializes-verbatim
  (testing "a NON-sensitive resource whose resolver reads a non-sensitive input
            serializes verbatim — the inheritance arm does NOT over-redact"
    (rf/reg-resource-scope :t/locale
      {:inputs  {:locale [:db [:i18n :locale]]}
       :resolve (fn [{:keys [locale]} _] (when locale [:rf.scope/locale {:locale locale}]))})
    (rf/reg-resource :t/prefs
      {:scope         {:from-db :t/locale}
       :params-schema [:map]}
      (fn [_ _] {:request {:method :get :url "/prefs"}}))
    (let [k   (state/scoped-resource-key [:rf.scope/locale {:locale :en}] :t/prefs {})
          e   (entry :t/prefs {:theme "dark"})
          proj (rf/with-frame frame-id
                 (ssr/project-resources-runtime-db (runtime-db-with {k e})))
          [wk we] (only-wire proj)]
      (is (= {:theme "dark"} (:data we)) "non-derived-sensitive data rides verbatim")
      (is (= k wk) "the wire key rides verbatim (no redaction)"))))

(deftest ssr-declassified-resolver-serializes-despite-sensitive-input
  (testing ":rf.egress/public on the resolver DECLASSIFIES — the derived scope
            is asserted safe despite the sensitive input, so the entry serializes"
    (rf/reg-resource-scope :t/declassified
      {:inputs  {:username [:db [:auth :user :username]]}
       :rf.egress/output-sensitivity :rf.egress/public
       :resolve (fn [{:keys [username]} _] (when username [:rf.scope/session {:username username}]))})
    (rf/reg-resource :t/public-feed
      {:scope         {:from-db :t/declassified}
       :params-schema [:map [:page :int]]}
      (fn [_ _] {:request {:method :get :url "/pub"}}))
    (let [k   (state/scoped-resource-key [:rf.scope/session {:username "jake"}] :t/public-feed {:page 1})
          e   (entry :t/public-feed {:articles [:x]})
          proj (rf/with-frame frame-id
                 (ssr/project-resources-runtime-db (runtime-db-with {k e})))
          [wk we] (only-wire proj)]
      (is (= {:articles [:x]} (:data we)) "the declassified resource's data rides verbatim")
      (is (= k wk) "the declassified scope rides verbatim in the wire key"))))

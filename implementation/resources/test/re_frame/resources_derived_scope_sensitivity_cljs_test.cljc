(ns re-frame.resources-derived-scope-sensitivity-cljs-test
  "No derived-sensitivity propagation — EP-0025 (rf2-71dr8t) REMOVED the
  named-scope-resolver derived-sensitivity inheritance arm (the EP-0016 wave's
  fourth framework-known derivation graph). Per Spec 015 §No propagation, no
  taint / Spec 016 §No derived-sensitivity propagation.

  THE CONTRACT under test (the inversion of the removed engine):

    - classification does NOT propagate from a resolver's `:db` inputs to its
      derived scope — a resource whose `{:from-db <id>}` resolver reads a
      FRAME-SENSITIVE app-db path does NOT inherit `:sensitive`; only the
      resource's OWN coarse `:sensitive?` / `:large?` claim governs its
      whole-entry disposition (`whole-entry-disposition`, frame-blind);
    - a resolver's `:rf.egress/output-sensitivity` claim is SILENTLY IGNORED
      (not validated fail-closed — the key is gone);
    - the propagation fns (`resolver-derived-sensitive?` /
      `scope-derived-sensitive?` / `whole-entry-disposition-for`) are GONE.

  Confirm-by-revert: a resource declared `:sensitive?` still redacts via its
  own owner claim; a resource that reads a sensitive input but declares nothing
  serializes (the fail-OPEN the EP names — classify the path you care about).

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
   [re-frame.resources.scope-registry :as scope-registry]
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
  `:scope` is the `{:from-db …}` reference but NOT itself declared sensitive.
  EP-0025: the frame's sensitive declaration does NOT propagate to the resource."
  []
  (registrar/clear-kind! :resource-scope)
  (registrar/clear-kind! :resource)
  ;; FRAME classification: the viewer-identity path is sensitive (commit-plane
  ;; effect path, :source :effect).
  (rf/reg-frame frame-id {})
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :user :username]]})))
  ;; resolver reading the sensitive viewer-identity path — NO propagation now.
  (rf/reg-resource-scope :t/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
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
;; 1. :rf.egress/output-sensitivity is SILENTLY IGNORED, not validated.
;;    (The propagation fns resolver-derived-sensitive? / scope-derived-sensitive?
;;    / whole-entry-disposition-for are GONE — referencing one would be a
;;    compile error, so their removal is enforced by the build itself.)
;; ===========================================================================

(deftest output-sensitivity-key-silently-ignored
  (testing ":rf.egress/output-sensitivity on a resolver is silently ignored — it
            does NOT throw (the propagation enum is gone; Spec 015 §No
            propagation: the key is gone and silently ignored if present)"
    (is (= :t/with-claim
           (rf/reg-resource-scope :t/with-claim
             {:inputs {:username [:db [:auth :user :username]]}
              :rf.egress/output-sensitivity :rf.egress/sensitive}
             (fn [{:keys [username]} _] (when username [:rf.scope/session {:username username}]))))
        "a resolver with :rf.egress/output-sensitivity registers cleanly")
    (testing "the key is not stored on the canonical spec"
      (is (nil? (:output-sensitivity (scope-registry/scope-resolver-meta :t/with-claim)))))
    (testing "even a previously-invalid enum value is ignored (no fail-closed throw)"
      (is (= :t/garbage-claim
             (rf/reg-resource-scope :t/garbage-claim
               {:inputs {:locale [:db [:i18n :locale]]}
                :rf.egress/output-sensitivity :rf.egress/publik}   ;; was a fail-closed typo
               (fn [{:keys [locale]} _] (when locale [:rf.scope/locale {:locale locale}]))))))))

;; ===========================================================================
;; 3. Whole-entry disposition is the OWNER claim ALONE — no inheritance.
;; ===========================================================================

(deftest disposition-no-inheritance-from-sensitive-input
  (testing "a resource whose {:from-db} resolver reads a FRAME-SENSITIVE input
            does NOT inherit sensitive — its disposition is its OWN coarse claim
            (:serialize, since :t/feed declares neither :sensitive? nor :large?)"
    (let [spec (rf/resource-meta :t/feed)]
      (is (= :serialize (classification/whole-entry-disposition spec))
          "no propagation — the resource serializes despite the sensitive input"))))

(deftest disposition-owner-declared-still-redacts
  (testing "the OWNER-declared :sensitive? case is unchanged — :redact, via the
            resource's own coarse claim (confirm-by-revert)"
    (rf/reg-resource :t/secret-feed
      {:scope         {:from-db :t/session}
       :sensitive?    true
       :params-schema [:map [:page :int]]}
      (fn [_ _] {:request {:method :get :url "/secret"}}))
    (let [spec (rf/resource-meta :t/secret-feed)]
      (is (= :redact (classification/whole-entry-disposition spec))
          "the owner :sensitive? claim redacts (frame-blind)")))
  (testing "an owner :large? claim omits (frame-blind)"
    (rf/reg-resource :t/big-feed
      {:scope         {:from-db :t/session}
       :large?        true
       :params-schema [:map [:page :int]]}
      (fn [_ _] {:request {:method :get :url "/big"}}))
    (let [spec (rf/resource-meta :t/big-feed)]
      (is (= :omit (classification/whole-entry-disposition spec))))))

;; ===========================================================================
;; 4. SSR projection END-TO-END — a resource that reads a sensitive input but
;;    declares nothing SERIALIZES (the fail-OPEN; no inheritance). A resource
;;    declared :sensitive? redacts via its own claim.
;; ===========================================================================

(deftest ssr-no-inheritance-resource-serializes
  (testing "the feed entry (NOT declared :sensitive?) under a scope derived from
            a sensitive input SERIALIZES — no propagation (the value the author
            did not classify ships raw, the EP's fail-open hygiene bargain)"
    (let [k   (session-key "jake" 1)
          e   (entry :t/feed {:articles [:a :b]})
          proj (rf/with-frame frame-id
                 (ssr/project-resources-runtime-db (runtime-db-with {k e})))
          [wk we] (only-wire proj)]
      (is (= {:articles [:a :b]} (:data we))
          "the non-classified entry's data rides verbatim (no inheritance)")
      (is (= k wk) "the wire key rides verbatim (no inheritance redaction)"))))

(deftest ssr-owner-declared-sensitive-redacts-via-own-claim
  (testing "a resource declared :sensitive? ships METADATA ONLY via its OWN
            coarse claim — data redacted + scope/params in the wire KEY redacted
            (confirm-by-revert: the owner boundary, not propagation)"
    (rf/reg-resource :t/secret-feed
      {:scope         {:from-db :t/session}
       :sensitive?    true
       :params-schema [:map [:page :int]]}
      (fn [_ _] {:request {:method :get :url "/secret"}}))
    (let [k   (state/scoped-resource-key [:rf.scope/session {:username "jake"}] :t/secret-feed {:page 1})
          e   (entry :t/secret-feed {:articles [:a :b]})
          proj (rf/with-frame frame-id
                 (ssr/project-resources-runtime-db (runtime-db-with {k e})))
          [wk we] (only-wire proj)]
      (is (= privacy/redacted-sentinel (:data we))
          "the owner-:sensitive? entry's data is replaced by the redaction sentinel")
      (is (= :t/secret-feed (nth wk 1)) "the resource-id rides at position 1")
      (is (contains? (nth wk 0) :rf/redacted) "the scope is redacted in the wire key")
      (is (contains? (nth wk 2) :rf/redacted) "the params are redacted in the wire key")
      (is (not (str/includes? (pr-str proj) "jake"))
          "the raw viewer identity does not ride on the wire"))))

(ns re-frame.resources-trace-key-declarations-egress-cljs-test
  "A `:serialize` owner's per-slot `:params` / `:scope` declaration is honoured
  INSIDE `:resource/key` at trace / tool egress.

  A resource declaring `{:sensitive [[:params :account-id]]}` and no coarse
  `:sensitive?` classifies `:serialize`, so a key projection reading only the
  coarse disposition would let `:account-id` ride raw inside `:resource/key`,
  scoped-keys vectors, work-ids and fx carriers, while the same bytes in the
  durable entry redact through the registry. The trace / tool projection
  applies the declaration itself; `rf.resources.ssr/project-scoped-key` keeps
  its `:serialize` deferral, because the SSR durable path reads the same
  declaration from the per-frame registry, which a frameless trace boundary
  cannot. Every check has a two-sided control: an undeclared owner's key rides
  byte-identical and undeclared siblings stay readable.

  The surface is dev-only: trace emits are gated on `interop/debug-enabled?`
  and the tool caller lives in the bundle-isolated `re-frame.resources.tooling`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs + the :resource registrar kind, and
   ;; publishes the trace-egress hooks the epoch tool-pair consults.
   [re-frame.resources]
   [re-frame.resources.classification :as rf.resources.classification]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.tooling :as rf.resources.tooling]
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   ;; production HTTP fx surface (so the transport feature probe resolves).
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private account-secret "acct-SECRET-4417")
(def ^:private tenant-secret "tenant-SECRET-99")
(def ^:private avatar-blob "avatar-bytes-XXXXXXXX")

(defn- init!
  "The subject (`:account/summary`, a per-slot params declaration and no coarse
  prop), its byte-identity control (`:plain/summary`, declaring nothing), a
  `:scope`-rooted declaration (`:tenant/report`), and a coarse `:sensitive?`
  owner (`:sealed/summary`) to show the two arms compose by grain."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "per-slot key-declaration trace-egress suite frame."})
  (rf/reg-resource :account/summary
    {:scope         :rf.scope/global
     :sensitive     [[:params :account-id]]
     :params-schema [:map [:account-id :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/account"}}))
  (rf/reg-resource :plain/summary
    {:scope         :rf.scope/global
     :params-schema [:map [:account-id :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/plain"}}))
  (rf/reg-resource :tenant/report
    {:scope         :rf.scope/global
     :sensitive     [[:scope :tenant-id]]
     :large         [[:params :avatar]]
     :params-schema [:map [:avatar :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/tenant"}}))
  (rf/reg-resource :sealed/summary
    {:scope         :rf.scope/global
     :sensitive?    true
     :sensitive     [[:params :account-id]]
     :params-schema [:map [:account-id :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/sealed"}})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter #?(:clj rf.substrate.plain-atom/adapter :cljs rf.adapter.reagent/adapter)
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(def ^:private declared-params {:account-id account-secret :page 3})

(defn- key-for [resource-id]
  (rf.resources.state/scoped-resource-key :rf.scope/global resource-id declared-params))

(defn- project-row
  "Project `tags` for OFF-BOX egress exactly as the epoch tool-pair does."
  [tags]
  (rf.resources.trace-egress/project-resource-trace-egress (assoc tags :rf.frame/id :rf/default) :rf/default))

(defn- projected-key [scoped-key]
  (:resource/key (project-row {:resource/key scoped-key})))

(defn- leaks? [secret v]
  (str/includes? (pr-str v) secret))

(defn- install-entry!
  "Write a `:loaded` entry for `scoped-key` and reconcile the elision registry,
  as a resource commit does in one transition. Returns the key."
  [frame-id scoped-key]
  (rf.frame/swap-runtime-db!
    frame-id
    (fn [rdb]
      (-> (or rdb {})
          (assoc-in (rf.resources.state/entry-path scoped-key)
                    (assoc (rf.resources.state/empty-entry (second scoped-key) scoped-key)
                           :status :loaded
                           :data   {:total 1}))
          (rf.resources.classification/reconcile-registry rf.resources.registry/resource-meta))))
  scoped-key)

(defn- ssr-wire-key
  "The SSR durable wire key projected for `scoped-key`'s entry, read from
  `projection-metadata` because an entry re-keyed by its own declaration is
  withheld from the wire."
  [frame-id scoped-key]
  (let [rdb (rf.frame/frame-runtime-db-value frame-id)]
    (some (fn [m]
            (when (= (second (:resource/key m)) (second scoped-key))
              (:projected-key m)))
          (rf.resources.ssr/projection-metadata frame-id 5000 (get-in rdb (rf.resources.state/entries-path))))))

;; ===========================================================================
;; the declared slot is closed on every carrier of the key
;; ===========================================================================

(deftest serialize-owner-declared-param-does-not-ride-raw-in-the-key
  (let [k    (key-for :account/summary)
        tags (project-row {:resource/key k})]
    (is (leaks? account-secret k) "premise: the RAW key carries the declared account-id in the clear")
    (is (= [[:rf.scope/global :account/summary {:account-id :rf/redacted :page 3}] true]
           [(:resource/key tags) (:sensitive? tags)])
        "only the declared slot changes, keeping per-key joins working, and the row is stamped :sensitive?")))

(deftest declared-param-is-closed-on-every-carrier-of-the-key
  (let [k    (key-for :account/summary)
        ;; :unnamed is a slot nobody enumerated, reached by the shape-driven default
        proj (project-row {:matched [k]
                           :work/id (rf.resources.work-ledger/resource-work-id k 1)
                           :unnamed [[k]]})
        fx   (rf.resources.trace-egress/project-fx-args-egress
               {:rf.frame/id :rf/default
                :rf.fx/args  {:request-id [:rf.req :rf/default [:rf.work/resource k 1]]}}
               :rf/default)]
    (is (= [false :rf/redacted :rf/redacted :rf/redacted]
           [(leaks? account-secret proj) (get-in proj [:matched 0 2 :account-id])
            (get-in proj [:work/id 1 2 :account-id]) (get-in proj [:unnamed 0 0 2 :account-id])])
        "a scoped-keys vector, a work-id and an unnamed slot all honour the declaration")
    (is (= [false true] [(leaks? account-secret fx) (:sensitive? fx)])
        "so does the fx carrier of a row the family does not own, which is stamped")))

;; ===========================================================================
;; the two-sided control: over-redaction is as wrong as under-redaction
;; ===========================================================================

(deftest kind-preserving-when-nothing-is-declared
  ;; the walker reconstructs collections, so an unneeded walk would turn a list
  ;; into a vector; the declaration-existence gate prevents it
  (let [k (rf.resources.state/scoped-resource-key :rf.scope/global :plain/summary
                                                  {:account-id "a" :page 3 :ids '(1 2 3)})]
    (is (= (pr-str k) (pr-str (projected-key k)))
        "an undeclared owner's key is byte-for-byte the same, list kind included")))

(deftest an-undeclared-row-is-not-stamped-sensitive
  (is (nil? (:sensitive? (project-row {:resource/key (key-for :plain/summary)})))
      "the stamp keeps meaning 'something on this row redacted'"))

(deftest a-declared-key-must-not-widen-the-reply-to-a-coarse-redaction
  ;; the row's :sensitive? stamp and the owner's coarse claim are two readings:
  ;; a :params declaration redacts the key, and says nothing of the body or
  ;; the undeclared siblings the per-slot grain keeps readable
  (let [k     (key-for :account/summary)
        reply {:status               :ok
               :value                {:ok true}
               :rf.reply/work-id     [:rf.work/resource k 1]
               :rf.reply/work-kind   :resource
               :rf.reply/work-status :completed
               :resource             :account/summary
               :params               declared-params
               :scope                :rf.scope/global
               :resource/key         k}
        out   (:rf.fx/args (rf.resources.trace-egress/project-fx-args-egress
                             {:rf.frame/id :rf/default :rf.fx/args reply} :rf/default))]
    (is (= [{:account-id :rf/redacted :page 3} {:ok true} :rf/redacted]
           [(select-keys (:params out) [:account-id :page]) (:value out)
            (get-in out [:resource/key 2 :account-id])])
        "the reply's :params and key redact the declared slot; the sibling and the body ride")))

(deftest scope-rooted-declaration-is-honoured-in-the-key
  (let [k  (rf.resources.state/scoped-resource-key [:rf.scope/session {:tenant-id tenant-secret :region "au"}]
                                                   :tenant/report
                                                   {:avatar avatar-blob :page 3})
        pk (projected-key k)]
    (is (leaks? tenant-secret k) "premise: the raw key carries the tenant id")
    (is (= [false :rf.scope/session "au" false 3]
           [(leaks? tenant-secret pk) (get-in pk [0 0]) (get-in pk [0 1 :region])
            (leaks? avatar-blob pk) (get-in pk [2 :page])])
        "[:scope :tenant-id] redacts through the tier tuple and the :large avatar elides; tier and siblings ride")))

;; ===========================================================================
;; one value, derived two ways: the SSR durable wire key agrees
;; ===========================================================================

(deftest trace-key-agrees-with-the-ssr-durable-wire-key
  (let [k    (install-entry! :rf/default (key-for :account/summary))
        wire (ssr-wire-key :rf/default k)]
    (is (= [:rf/redacted (pr-str (nth wire 2))]
           [(get-in wire [2 :account-id]) (pr-str (nth (projected-key k) 2))])
        "the SSR wire key honours the declaration, and the trace key's params are byte-equal to it")))

(deftest projection-is-idempotent
  (let [k (key-for :account/summary)]
    (is (= (projected-key k) (projected-key (projected-key k))))))

(deftest project-scoped-key-still-defers-on-serialize
  ;; reds if anyone moves the per-slot arm into the shared projection
  (let [k (key-for :account/summary)]
    (is (= [k k]
           [(rf.resources.ssr/project-scoped-key k :serialize (rf.resources.registry/resource-meta :account/summary))
            (rf.resources.ssr/project-scoped-key k :serialize nil)])
        ":serialize rides verbatim through project-scoped-key, ignoring the spec")))

;; ===========================================================================
;; the coarse arm is independent; the arms compose by grain
;; ===========================================================================

(deftest coarse-owner-still-tokenizes-the-whole-component
  (let [k    (key-for :sealed/summary)
        tags (project-row {:resource/key k})
        pk   (:resource/key tags)]
    (is (= [true true :sealed/summary true (rf.resources.ssr/project-scoped-key k :redact nil)]
           [(contains? (nth pk 2) :rf/redacted) (contains? (nth pk 0) :rf/redacted) (nth pk 1)
            (:sensitive? tags) pk])
        "a coarse owner's params and scope are each one opaque token, byte-for-byte project-scoped-key's")))

(deftest unregistered-owner-still-fails-closed
  (let [tags (project-row {:resource/key (rf.resources.state/scoped-resource-key
                                           :rf.scope/global :never/registered {:account-id account-secret})})]
    (is (= [false true] [(leaks? account-secret (:resource/key tags)) (:sensitive? tags)]))))

(deftest tool-egress-honours-the-same-declaration
  ;; the tool-egress projection opts into the same helper (`tooling.cljc`'s
  ;; `project-key-for-egress`)
  (let [k    (install-entry! :rf/default (key-for :account/summary))
        node (first (vals (rf.resources.tooling/resource-cache-algebra-view :rf/default)))]
    (is (= [true false :rf/redacted 3]
           [(some? node) (leaks? account-secret node) (get-in (:id node) [2 :account-id])
            (get-in (:id node) [2 :page])])
        "the node :id is the declaration-projected key, its sibling riding")
    (is (= k (:resource/key (get-in (rf.frame/frame-runtime-db-value :rf/default)
                                    (rf.resources.state/entry-path k))))
        "the durable entry still stores the raw scoped key: this is egress-only")))

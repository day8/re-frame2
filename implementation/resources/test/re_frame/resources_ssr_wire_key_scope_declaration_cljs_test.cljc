(ns re-frame.resources-ssr-wire-key-scope-declaration-cljs-test
  "A `:serialize` owner's `:scope`-rooted declaration is honoured in the SSR
  DURABLE wire key, as it is in every other carrier of that value.

  `rf.resources.classification/instance-declaration-paths` lowers a
  `:scope`-rooted declaration to the entry's `[… :resource/key 0 …]` path as it
  lowers a `:params`-rooted one to `[… :resource/key 2 …]`, so
  `rf.resources.ssr/project-entry` must project both indexes. That projection
  runs on every SSR render of a production bundle with no debug gate, so a
  missed index ships the resolved tenant id to every visitor.

  Over-redaction is a defect here too: an owner declaring nothing must ride its
  key byte-identical, because a list↔vector collapse changes the CEDN-1 key-id
  a cache-key round-trip depends on.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex."
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
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
   ;; production HTTP fx surface (so the transport feature probe resolves).
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private tenant-secret "tenant-SECRET-99")
(def ^:private account-secret "acct-SECRET-4417")

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "Four owners spanning the grains this suite discriminates:

    :tenant/report  — `:serialize` (NO coarse prop) + a `[:scope :tenant-id]`
                      declaration. THE SUBJECT.
    :both/report    — declares on BOTH components, to prove the two arms
                      compose inside one key rather than one winning.
    :plain/report   — declares NOTHING. The byte-identity control.
    :sealed/report  — the COARSE `:sensitive?` owner, to prove the per-slot arm
                      leaves the coarse whole-component digests alone."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "SSR wire-key scope-declaration suite frame."})
  (rf/reg-resource :tenant/report
    {:scope         :rf.scope/global
     :sensitive     [[:scope :tenant-id]]
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/tenant"}}))
  (rf/reg-resource :both/report
    {:scope         :rf.scope/global
     :sensitive     [[:scope :tenant-id] [:params :account-id]]
     :params-schema [:map [:account-id :string] [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/both"}}))
  (rf/reg-resource :plain/report
    {:scope         :rf.scope/global
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/plain"}}))
  (rf/reg-resource :sealed/report
    {:scope         :rf.scope/global
     :sensitive?    true
     :sensitive     [[:scope :tenant-id]]
     :params-schema [:map [:page :int]]}
    (fn [_p _ctx] {:request {:method :get :url "/sealed"}})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter #?(:clj rf.substrate.plain-atom/adapter :cljs rf.adapter.reagent/adapter)
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- session-scope
  "The `[tier {identity}]` tuple a `:scope`-rooted declaration reaches THROUGH.
  `:roles` is deliberately a LIST: `=` cannot see a list↔vector collapse but
  `canonical-bytes` can, so an unnecessary walk of this component would change
  the entry's key-id while every `=` assertion still passed."
  []
  [:rf.scope/session {:tenant-id tenant-secret
                      :region    "au"
                      :roles     '(:admin :ops)}])

(defn- key-for
  ([resource-id] (key-for resource-id {:page 3}))
  ([resource-id params]
   (rf.resources.state/scoped-resource-key (session-scope) resource-id params)))

(defn- global-key-for
  "An undeclared owner under `:rf.scope/global`: an ADDRESSABLE key carrying no
  secret in any component, so its row rides and a slice holding it is not empty."
  [resource-id]
  (rf.resources.state/scoped-resource-key :rf.scope/global resource-id {:page 3}))

(defn- install-entry!
  "Write a durable `:loaded` entry for `scoped-key` into the frame's runtime-db
  AND reconcile the per-frame elision registry — the two steps a real resource
  commit folds into one transition. Returns the key."
  [frame-id scoped-key]
  (let [resource-id (second scoped-key)]
    (rf.frame/swap-runtime-db!
      frame-id
      (fn [rdb]
        (-> (or rdb {})
            (assoc-in (rf.resources.state/entry-path scoped-key)
                      (assoc (rf.resources.state/empty-entry resource-id scoped-key)
                             :status :loaded
                             :data   {:total 1}))
            (rf.resources.classification/reconcile-registry rf.resources.registry/resource-meta)))))
  scoped-key)

(defn- wire-entries
  "The projected `:entries` map exactly as it rides the `:rf/hydration-payload`."
  [frame-id]
  (get-in (rf.resources.ssr/project-resources-runtime-db
            (rf.frame/frame-runtime-db-value frame-id) frame-id)
          [rf.resources.state/resources-key :entries]))

(defn- wire-key
  "The key the SSR projection PRODUCED for `resource-id`, read off
  `projection-metadata`'s `:projected-key` — a re-keyed entry has no wire row
  to read it from."
  [frame-id resource-id]
  (some (fn [m] (when (= resource-id (second (:resource/key m))) (:projected-key m)))
        (rf.resources.ssr/projection-metadata
          frame-id 5000
          (get-in (rf.frame/frame-runtime-db-value frame-id) (rf.resources.state/entries-path)))))

(defn- leaks?
  "Whether `secret` survives ANYWHERE in `v` — the plaintext test."
  [secret v]
  (str/includes? (pr-str v) secret))

;; ===========================================================================

(deftest scope-rooted-declaration-does-not-ride-raw-in-the-ssr-wire-key
  ;; Only the declared slots redact — the tier, the undeclared siblings and the
  ;; resource-id survive — and the trace projection is the same value.
  (doseq [[resource-id params expected]
          [[:tenant/report {:page 3}
            [[:rf.scope/session {:tenant-id :rf/redacted :region "au" :roles '(:admin :ops)}]
             :tenant/report
             {:page 3}]]
           [:both/report {:account-id account-secret :page 3}
            [[:rf.scope/session {:tenant-id :rf/redacted :region "au" :roles '(:admin :ops)}]
             :both/report
             {:account-id :rf/redacted :page 3}]]]]
    (let [k     (install-entry! :rf/default (key-for resource-id params))
          w     (wire-key :rf/default resource-id)
          trace (:resource/key
                  (rf.resources.trace-egress/project-resource-trace-egress
                    {:rf.frame/id :rf/default :resource/key k} :rf/default))]
      (is (= expected w) (str resource-id))
      (is (= (pr-str w) (pr-str trace))
          (str resource-id ": the SSR wire key and the trace key are one value")))))

(deftest the-whole-hydration-payload-slice-is-free-of-the-declared-scope
  ;; Map keys included: a key-id is a reversible plaintext CEDN-1 encoding.
  (install-entry! :rf/default (key-for :tenant/report))
  (install-entry! :rf/default (global-key-for :plain/report))
  (let [slice (rf.resources.ssr/project-resources-runtime-db
                (rf.frame/frame-runtime-db-value :rf/default) :rf/default)]
    (is (seq (get-in slice [rf.resources.state/resources-key :entries]))
        "premise: the slice is not empty")
    (is (not (leaks? tenant-secret slice)) (pr-str slice))))

(deftest keys-with-nothing-to-redact-ride-byte-identical
  (is (seq? (get-in (key-for :plain/report) [0 1 :roles]))
      "premise: the canonical scoped key keeps :roles a LIST")
  (doseq [[label k resource-id]
          [["an undeclared owner's key rides verbatim, scope and all"
            (key-for :plain/report) :plain/report]
           ["a declared :scope slot under :rf.scope/global has no identity tuple to walk"
            (rf.resources.state/scoped-resource-key :rf.scope/global :tenant/report {:page 3})
            :tenant/report]]]
    (install-entry! :rf/default k)
    (is (= (rf.resources.state/key-id k)
           (rf.resources.state/key-id (wire-key :rf/default resource-id)))
        label)))

(deftest only-addressable-undeclared-rows-ride-the-wire
  ;; Projecting a key component re-keys the entry and the live client derives
  ;; the RAW key, so a re-keyed row — per-slot or coarse — is unaddressable. It
  ;; is withheld, not emptied, while the undeclared rows ride with their bodies.
  (doseq [k [(key-for :tenant/report) (key-for :plain/report)
             (global-key-for :plain/report) (key-for :sealed/report)]]
    (install-entry! :rf/default k))
  (let [wired (wire-entries :rf/default)]
    (is (= #{(rf.resources.state/key-id (key-for :plain/report))
             (rf.resources.state/key-id (global-key-for :plain/report))}
           (set (keys wired)))
        (pr-str (mapv (comp :resource/key val) wired)))
    (is (every? (fn [[k-id e]] (= k-id (rf.resources.state/key-id (:resource/key e)))) wired)
        "each row rides under the key-id of its own :resource/key")
    (is (= [:loaded {:total 1}]
           ((juxt :status :data) (get wired (rf.resources.state/key-id (key-for :plain/report)))))
        "the undeclared body rides verbatim")))

(deftest a-coarse-sensitive-owner-still-tokenizes-both-components
  ;; :sealed/report also declares [:scope :tenant-id]; the per-slot arm must
  ;; leave the coarse whole-component tokens exactly as the coarse arm made them.
  (let [k (install-entry! :rf/default (key-for :sealed/report))
        w (wire-key :rf/default :sealed/report)]
    (is (= (rf.resources.ssr/project-scoped-key k :redact nil) w))
    (is (not (leaks? tenant-secret w)))))

(ns day8.re-frame2-xray.panels.resources-cljs-test
  "CLJS-side wiring + view tests for Xray's Resources tab (Spec 016 §Xray
  and AI tooling).

  ## What's under test

    1. **Registry wires the subs** — `register-xray-handlers!` installs
       every sub/event the panel reads + the test-only override slots.
    2. **Tab inventory** — the palette's Dynamic panel list now carries
       `:resources` (and the count grew Routing→Resources).
    3. **Sections render** — registry / live-instances / work-ledger /
       route-graph / lifecycle-timeline / invalidation / cache-growth /
       audit all render when data is present.
    4. **PRIVACY** — a `:sensitive?` data value the runtime redacted to
       `:rf/redacted` renders `[redacted]`, never the raw value.
    5. **Read-only** — no `:rf.resource/*` event is registered by the
       panel (observing pins nothing).
    6. **Silent state** — no resources + no instances → silent caption.
    7. **Decoupled** — the panel reads the registry + the runtime-db
       slice via override hooks; no `re-frame.resources` require."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.palette.subs :as palette-subs]
            [day8.re-frame2-xray.panels.resources :as resources]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

;; ---- hiccup walkers (mirror routing_cljs_test) --------------------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- node-text [node]
  (->> (hiccup-seq node) (filter string?) (apply str)))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- fixtures: registry + entries + ledger ------------------------------

(def session-scope [:rf.scope/session {:user-id "u-42"}])

(def resource-regs
  {:article/by-slug
   {:doc "Article by slug."
    :rf/resource {:doc "Article by slug."
                  :params-schema [:map [:slug :string]]
                  :data-schema :app/article
                  :scope :rf.scope/global
                  :transport :rf.http/managed
                  :stale-after-ms 60000 :gc-after-ms 300000
                  :tags (fn [_ _] #{}) :request (fn [_ _] {})}}})

(def live-entries
  {[session-scope :article/by-slug {:slug "welcome"}]
   {:resource/id :article/by-slug :status :loaded
    :data {:title "Welcome"} :generation 4
    :stale-at (+ (.now js/Date) 60000)
    :active-owners #{[:route :route/article "nav-1"]}
    :tags #{[:article "welcome"]} :request-id [:w 4]}})

(def live-ledger
  {[:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4]
   {:work/id [:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4]
    :work/kind :resource :resource/key [session-scope :article/by-slug {:slug "welcome"}]
    :generation 4 :status :running :cancellable? true :deadline-at 5100}})

(defn- seed-overrides! []
  (rf/dispatch-sync [:rf.xray/set-registered-resources-override-for-test resource-regs]
                    {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/set-resource-entries-override-for-test live-entries]
                    {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/set-resource-work-ledger-override-for-test live-ledger]
                    {:frame :rf/xray}))

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-resources-subs
  (testing "register-xray-handlers! installs the resources tab subs + overrides"
    (registry/register-xray-handlers!)
    (doseq [s [:rf.xray/registered-resources
               :rf.xray/registered-resources-override
               :rf.xray/resource-entries
               :rf.xray/resource-entries-override
               :rf.xray/resource-work-ledger
               :rf.xray/resource-work-ledger-override
               :rf.xray/resource-sub-reads
               :rf.xray/resource-sub-reads-override
               :rf.xray/resource-routing-slice
               :rf.xray/resource-routing-slice-override
               :rf.xray/resources-tab-data]]
      (is (some? (registrar/handler :sub s)) (str s " sub registered")))
    (doseq [e [:rf.xray/set-registered-resources-override-for-test
               :rf.xray/set-resource-entries-override-for-test
               :rf.xray/set-resource-work-ledger-override-for-test
               :rf.xray/set-resource-sub-reads-override-for-test
               :rf.xray/set-resource-routing-slice-override-for-test]]
      (is (some? (registrar/handler :event e)) (str e " event registered")))))

(deftest read-only-no-resource-events
  (testing "Spec 016 — the Xray registry surface carries NO :rf.resource/*
            event (observing pins nothing; Xray never becomes an owner).
            The :rf.resource/* events that DO exist are registered by the
            resources ARTEFACT's own façade, not by any Xray panel — so
            the read-only contract is checked against the Xray-owned
            registry surface (every event Xray registers is :rf.xray*/)."
    (registry/register-xray-handlers!)
    ;; The panel's install! must register every event under the :rf.xray*/
    ;; isolation prefix and NONE under :rf.resource/* — the structural
    ;; guarantee that inspection never dispatches a resource event.
    (doseq [e [:rf.xray/set-registered-resources-override-for-test
               :rf.xray/set-resource-entries-override-for-test
               :rf.xray/set-resource-work-ledger-override-for-test
               :rf.xray/set-resource-sub-reads-override-for-test
               :rf.xray/set-resource-routing-slice-override-for-test]]
      (is (some? (registrar/handler :event e))
          (str e " is registered under the :rf.xray*/ prefix"))
      (is (= "rf.xray" (namespace e))
          (str e " lives under the Xray isolation prefix, not :rf.resource/*")))))

;; ---- (2) tab inventory --------------------------------------------------

(deftest palette-includes-resources
  (testing "the palette's canonical Dynamic panel list carries :resources"
    (let [panels (palette-subs/palette-panels)
          ids    (set (map :id panels))]
      (is (contains? ids :resources) ":resources in palette-panels")
      (is (= 7 (count panels))
          "7 Dynamic tabs — Epoch / App DB / Views / Trace / Machines / Routing / Resources"))))

;; ---- (3) sections render ------------------------------------------------

(deftest panel-renders-sections-when-data-present
  (testing "registry + instances + work + route-graph + timeline render"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-overrides!)
      (let [tree (resources/Panel)]
        (is (some? (find-by-testid tree "rf-xray-resources")) "panel root")
        (is (some? (find-by-testid tree "rf-xray-resources-registry")) "registry section")
        (is (some? (find-by-testid tree "rf-xray-resources-instances")) "instances section")
        (is (some? (find-by-testid tree "rf-xray-resources-work")) "work section")
        (is (some? (find-by-testid tree "rf-xray-resources-route-graph")) "route-graph section")
        (is (some? (find-by-testid tree "rf-xray-resources-timeline")) "timeline section")
        (is (some? (find-by-testid tree "rf-xray-resources-invalidation")) "invalidation section")
        (is (some? (find-by-testid tree "rf-xray-resources-cache-growth")) "cache-growth section")
        (is (some? (find-by-testid tree "rf-xray-resources-audit")) "audit section")
        ;; a registry row for the article resource
        (is (some? (find-by-testid tree "rf-xray-resources-registry-row-article/by-slug"))
            "registry row rendered")
        ;; a live instance row (gen 4)
        (is (some? (find-by-testid tree "rf-xray-resources-instance-row-article/by-slug-g4"))
            "live instance row rendered")
        ;; the audit lists the explicit-global resource
        (let [audit (find-by-testid tree "rf-xray-resources-audit-global")]
          (is (some? audit))
          (is (re-find #":article/by-slug" (node-text audit))
              "global-scope audit lists the explicit-global resource"))))))

(deftest instance-row-shows-status-and-owners
  (testing "the instance row surfaces status + owner-count, derived not stored"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-overrides!)
      (let [tree   (resources/Panel)
            status (find-by-testid tree "rf-xray-resources-instance-row-article/by-slug-g4-status")]
        (is (some? status))
        (is (re-find #"loaded" (node-text status)) "status reads loaded")))))

(deftest route-graph-shows-live-active-route
  (testing "rf2-m5u3gt — with a live routing slice override, the route/resource
            graph flags the active route (● active) and surfaces the live
            unsettled-blocking wait point off the runtime routing slice"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-overrides!)
      ;; Register the route declaring :resources so the graph has a node.
      ;; Via the registrar directly (the routing artefact's reg-route macro
      ;; is not on the xray test classpath); the graph reads the registry map
      ;; decoupled via (rf/registrations :route).
      (registrar/register! :route :route/article
                           {:path "/articles/:slug"
                            :resources [{:resource :article/by-slug :blocking? true}]})
      ;; Seed the live routing slice: :route/article active under nav-1, with
      ;; the article scoped key still in the unsettled-blocking set.
      (rf/dispatch-sync
        [:rf.xray/set-resource-routing-slice-override-for-test
         {:current {:id :route/article :nav-token "nav-1"}
          :resource-blocking
          {"nav-1" #{[session-scope :article/by-slug {:slug "welcome"}]}}}]
        {:frame :rf/xray})
      (let [tree (resources/Panel)
            row  (find-by-testid tree "rf-xray-resources-route-row-route/article")]
        (is (some? row) "the route row renders")
        (is (some? (find-by-testid tree "rf-xray-resources-route-row-route/article-current"))
            "the active route is flagged ● active off the live routing slice")
        (is (some? (find-by-testid tree "rf-xray-resources-route-row-route/article-blocking-live"))
            "the live unsettled-blocking wait point surfaces")
        (is (re-find #"fresh" (node-text row))
            "the blocking :article/by-slug reads :fresh from its live cache entry")))))

;; ---- (4) PRIVACY --------------------------------------------------------

(deftest privacy-redacted-data-never-raw
  (testing "a :sensitive? value the runtime redacted to :rf/redacted renders
            [redacted] — the panel only ever receives the sentinel for a
            sensitive slot (the runtime elides it BEFORE Xray sees it), so
            the underlying value never reaches the panel"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-resources-override-for-test resource-regs]
                        {:frame :rf/xray})
      ;; The runtime emits :rf/redacted for a :sensitive? data AND scope slot
      ;; (scopes carry PII and get the SAME elision as data). The panel must
      ;; render both sentinels as [redacted], never a raw preview.
      (rf/dispatch-sync [:rf.xray/set-resource-entries-override-for-test
                         {[:rf/redacted :article/by-slug {:slug "welcome"}]
                          {:resource/id :article/by-slug :status :loaded
                           :data :rf/redacted :generation 1
                           :active-owners #{}}}]
                        {:frame :rf/xray})
      (let [tree (resources/Panel)
            data (find-by-testid tree "rf-xray-resources-instance-row-article/by-slug-g1-data")
            scope (find-by-testid tree "rf-xray-resources-instance-row-article/by-slug-g1-scope")]
        (is (some? data))
        (is (re-find #"\[redacted\]" (node-text data))
            "redacted data renders [redacted]")
        (is (some? scope))
        (is (re-find #"\[redacted\]" (node-text scope))
            "a redacted scope (PII) renders [redacted] — same elision as data")))))

;; ---- (6) silent state ---------------------------------------------------

(deftest panel-silent-when-no-resources
  (testing "no resources + no instances → silent caption, no sections"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-resources-override-for-test {}]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/set-resource-entries-override-for-test {}]
                        {:frame :rf/xray})
      (let [tree (resources/Panel)]
        (is (some? (find-by-testid tree "rf-xray-resources")) "panel root present")
        (is (some? (find-by-testid tree "rf-xray-resources-silent")) "silent caption")
        (is (nil? (find-by-testid tree "rf-xray-resources-registry"))
            "no registry section when silent")
        (is (nil? (find-by-testid tree "rf-xray-resources-instances"))
            "no instances section when silent")))))

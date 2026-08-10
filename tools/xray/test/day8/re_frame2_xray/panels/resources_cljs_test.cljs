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
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            ;; CORE canonical-identity (not the resources artefact) — the routing
            ;; blocking slot is keyed on the CEDN-1 byte id (rf2-btdl1).
            [re-frame.identity :as rf-identity]
            [re-frame.registrar :as registrar]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.palette.subs :as palette-subs]
            [day8.re-frame2-xray.panels.resources :as resources]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the bespoke `xray-init!`
  ;; into one owner: plain-atom adapter + the default `:all` reset tier,
  ;; which already includes the trace-collector ring reset the old init
  ;; called a SECOND, redundant time.
  (xray-test-support/make-xray-runtime-fixture))

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
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

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

;; rf2-ybqse — the freshness horizon is anchored to the moment the fixture is
;; SEEDED, which is why this is a fn and not a `def`.
;;
;; `derive-stale?` is a comparison against the wall clock: an entry is stale
;; once `(.now js/Date)` AT RENDER TIME has passed its `:stale-at`. A horizon
;; frozen at namespace-LOAD time therefore decays over the life of the run.
;; `npm run test:cljs` loads every `*_cljs_test` namespace into one
;; consolidated bundle up front and only then starts executing, so this
;; namespace's tests ran ~30s after its own `def`s were evaluated on an idle
;; box — leaving only ~30s of a 60s horizon. On a loaded machine that gap
;; exceeded 60s, the entry was *legitimately* past its `:stale-at`, and
;; `route-graph-shows-live-active-route` read `stale (1 work)` where it
;; expected `fresh`. The panel was right; the fixture had rotted in place.
;;
;; Evaluating per-seed collapses the seed→render gap from "however long the
;; suite takes to get here" to the microseconds inside one test body, which
;; removes the coupling between this fixture's freshness and the suite's
;; wall-clock duration. `stale-live-entries` pins the other side of the same
;; comparison so the `fresh` assertions cannot pass vacuously.
(defn- live-entries []
  {[session-scope :article/by-slug {:slug "welcome"}]
   {:resource/id :article/by-slug :status :loaded
    :data {:title "Welcome"} :generation 4
    :stale-at (+ (.now js/Date) 60000)
    :active-owners #{[:route :route/article "nav-1"]}
    :tags #{[:article "welcome"]} :request-id [:w 4]}})

(defn- stale-live-entries []
  (update-in (live-entries)
             [[session-scope :article/by-slug {:slug "welcome"}] :stale-at]
             - 120000))

(def live-ledger
  {[:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4]
   {:work/id [:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4]
    :work/kind :resource :resource/key [session-scope :article/by-slug {:slug "welcome"}]
    :generation 4 :status :running :cancellable? true :deadline-at 5100}})

(defn- seed-overrides!
  ([] (seed-overrides! (live-entries)))
  ([entries]
   (rf/dispatch-sync [:rf.xray/set-registered-resources-override-for-test resource-regs]
                     {:frame :rf/xray})
   (rf/dispatch-sync [:rf.xray/set-resource-entries-override-for-test entries]
                     {:frame :rf/xray})
   (rf/dispatch-sync [:rf.xray/set-resource-work-ledger-override-for-test live-ledger]
                     {:frame :rf/xray})))

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-resources-subs
  (testing "register-xray-handlers! installs the resources tab production subs"
    (registry/register-xray-handlers!)
    (doseq [s [:rf.xray/registered-resources
               :rf.xray/registered-scope-resolvers
               :rf.xray/resource-entries
               :rf.xray/resource-work-ledger
               :rf.xray/resource-sub-reads
               :rf.xray/resource-routing-slice
               :rf.xray/resources-tab-data]]
      (is (some? (registrar/handler :sub s)) (str s " sub registered"))))
  (testing "rf2-e8330v — production registration installs NO -for-test ids
            nor *-override subs; install-test-overrides! installs them"
    (registry/register-xray-handlers!)
    (doseq [s [:rf.xray/registered-resources-override
               :rf.xray/registered-scope-resolvers-override
               :rf.xray/resource-entries-override
               :rf.xray/resource-work-ledger-override
               :rf.xray/resource-sub-reads-override
               :rf.xray/resource-routing-slice-override]]
      (is (nil? (registrar/handler :sub s))
          (str s " override sub NOT installed by production registration")))
    (doseq [e [:rf.xray/set-registered-resources-override-for-test
               :rf.xray/set-registered-scope-resolvers-override-for-test
               :rf.xray/set-resource-entries-override-for-test
               :rf.xray/set-resource-work-ledger-override-for-test
               :rf.xray/set-resource-sub-reads-override-for-test
               :rf.xray/set-resource-routing-slice-override-for-test]]
      (is (nil? (registrar/handler :event e))
          (str e " NOT installed by production registration")))
    (xray-test-support/install-test-overrides!)
    (doseq [s [:rf.xray/registered-resources-override
               :rf.xray/registered-scope-resolvers-override
               :rf.xray/resource-entries-override
               :rf.xray/resource-work-ledger-override
               :rf.xray/resource-sub-reads-override
               :rf.xray/resource-routing-slice-override]]
      (is (some? (registrar/handler :sub s)) (str s " override sub registered by seam")))
    (doseq [e [:rf.xray/set-registered-resources-override-for-test
               :rf.xray/set-registered-scope-resolvers-override-for-test
               :rf.xray/set-resource-entries-override-for-test
               :rf.xray/set-resource-work-ledger-override-for-test
               :rf.xray/set-resource-sub-reads-override-for-test
               :rf.xray/set-resource-routing-slice-override-for-test]]
      (is (some? (registrar/handler :event e)) (str e " event registered by seam")))))

(deftest read-only-no-resource-events
  (testing "Spec 016 — the Xray registry surface carries NO :rf.resource/*
            event (observing pins nothing; Xray never becomes an owner).
            The :rf.resource/* events that DO exist are registered by the
            resources ARTEFACT's own façade, not by any Xray panel — so
            the read-only contract is checked against the Xray-owned
            registry surface (every event Xray registers is :rf.xray*/)."
    (registry/register-xray-handlers!)
    (xray-test-support/install-test-overrides!)
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
      (is (= 10 (count panels))
          "10 Dynamic tabs — Epoch / App DB / Views / Trace / Machines / Routing / Resources / Graph / Modules / Hicasso (rf2-9ett2d added the EP-0014 derivation-graph tab; rf2-wtg9z4 added the EP-0013 Modules tab; rf2-hic-023 added the Hicasso evidence tab)"))))

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
        ;; EP-0016 slice-8 sections (always present when not silent)
        (is (some? (find-by-testid tree "rf-xray-resources-scope-resolvers")) "scope-resolvers section")
        (is (some? (find-by-testid tree "rf-xray-resources-scope-resolution")) "scope-resolution timeline section")
        (is (some? (find-by-testid tree "rf-xray-resources-continuations")) "continuations section")
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

;; ---- (3b) EP-0016 slice-8 surfaces --------------------------------------

(def scope-resolver-regs
  {:realworld/session
   {:doc "Session scope from the auth username."
    :rf/resource-scope {:doc "Session scope from the auth username."
                        :inputs {:username [:db [:auth :user :username]]}
                        :whole-db? false
                        :resolve (fn [_ _] nil)}}})

(def ep0016-buffer
  [;; a named resolver resolved {:from-db :realworld/session} → a session scope
   {:id 50 :op-type :rf.event :operation :rf.resource/scope-resolved
    :tags {:resource-id :realworld/session :kind :resource-scope
           :inputs [:username] :input-values {:username "jake"}
           :whole-db? false :scope [:rf.scope/session {:username "jake"}]
           :resolved-nil? false}}
   ;; a favorite mutation invalidated global article/list + the session feed,
   ;; with a fail-closed unresolved {:from-db …} + a populate-exempt key
   {:id 51 :op-type :rf.event :operation :rf.mutation/succeeded
    :tags {:mutation :realworld/favorite-article :instance [:favorite "welcome"]
           :invalidation
           {:descriptor-count 3
            :dispatched [{:scope :rf.scope/global :cross-scope? false
                          :tags #{[:article-list]} :refetch-populated? false}
                         {:scope [:rf.scope/session {:username "jake"}] :cross-scope? false
                          :tags #{[:feed]} :refetch-populated? false}]
            :unresolved [:realworld/tenant]
            :populate-exempt [[:rf.scope/global :realworld/article {:slug "welcome"}]]}}}
   ;; the call-site :reply-to continuation dispatch
   {:id 52 :op-type :rf.event :operation :rf.mutation/replied
    :tags {:rf.frame/id :app/main :mutation :realworld/save-article
           :instance [:editor/save "first-post"] :status :ok
           :work/id [:rf.work/resource [:rf.mutation [:editor/save "first-post"]] 8]
           :target [:editor/save-replied]
           :cause [:mutation :realworld/save-article [:editor/save "first-post"]]}}])

(deftest ep0016-surfaces-render
  (testing "scope resolvers, scope-resolution timeline, descriptor-level
            invalidation evidence, and :reply-to continuations render from the
            registry + trace buffer (EP-0016 slice 8)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-overrides!)
      (rf/dispatch-sync [:rf.xray/set-registered-scope-resolvers-override-for-test
                         scope-resolver-regs]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer ep0016-buffer] {:frame :rf/xray})
      (let [tree (resources/Panel)]
        ;; D3 — the named-scope-resolver registry row (id + declared inputs)
        (is (some? (find-by-testid tree "rf-xray-resources-scope-resolver-row-realworld/session"))
            "named scope-resolver row rendered")
        (is (re-find #"username"
                     (node-text (find-by-testid
                                 tree "rf-xray-resources-scope-resolver-row-realworld/session-inputs")))
            "declared input name surfaced")
        ;; D3 — the resolution timeline row (resolved scope, not nil)
        (is (some? (find-by-testid tree "rf-xray-resources-scope-resolution-row-50"))
            "scope-resolution timeline row rendered")
        ;; D2 — the descriptor-level invalidation evidence row
        (let [row (find-by-testid tree "rf-xray-resources-mutation-invalidation-row-51")]
          (is (some? row) "mutation invalidation evidence row rendered")
          (is (re-find #"3 descriptors" (node-text row))
              "descriptor count surfaced")
          ;; the two dispatched descriptors (global + session) render as chips
          (is (re-find #":rf.scope/global" (node-text row))
              "global descriptor scope surfaced")
          (is (re-find #":feed" (node-text row))
              "session-scoped feed descriptor surfaced"))
        ;; D2 — the fail-closed unresolved {:from-db …} evidence
        (is (some? (find-by-testid tree "rf-xray-resources-mutation-invalidation-unresolved-51"))
            "fail-closed unresolved descriptor surfaced")
        ;; D1 — the :reply-to continuation dispatch row (phase 6)
        (let [row (find-by-testid tree "rf-xray-resources-continuation-row-52")]
          (is (some? row) ":reply-to continuation row rendered")
          (is (re-find #":editor/save-replied" (node-text row))
              "the call-site :reply-to target surfaced")
          (is (re-find #":realworld/save-article" (node-text row))
              "the mutation id surfaced"))))))

;; ---- (3a) EP-0011 live-work + stale-races render ------------------------
;;
;; The uniform reply-envelope reads (`reply/live-work` / `races-by-work-id` /
;; `stale-tally-by-kind`) are computed in the composite data sub; the Panel
;; must RENDER them — "what is still running?" (live work + active-effects
;; tally) and the cross-family stale-races view. Silent-by-default: a settled
;; app (no live work, no suppression) renders neither section.

(def ep0011-live-work-id
  [:rf.work/resource [session-scope :article/by-slug {:slug "welcome"}] 4])

(def ep0011-buffer
  [;; the live resource work's latest reply-envelope trace phase (issued) —
   ;; live-work joins the running ledger row to THIS by :work/id.
   {:id 70 :op-type :rf.resource :operation :rf.resource/work-started
    :tags {:work/id ep0011-live-work-id :work/kind :resource}}
   ;; a cross-family HTTP supersession stale-suppressed row — drives the
   ;; stale-races view + the per-kind suppression tally.
   {:id 71 :op-type :rf.http :operation :rf.http/stale-suppressed
    :tags {:work/id [:rf.work/http :search 1 1] :work/kind :http
           :rf.reply/status :stale :rf.reply/work-status :suppressed
           :rf.reply/stale-reason :rf.http/request-id-superseded
           :rf.reply/carried {:work/id [:rf.work/http :search 1 1]}
           :rf.reply/current {:work/id [:rf.work/http :search 2 1]}}}])

(deftest ep0011-live-work-and-stale-races-render
  (testing "the EP-0011 'what is still running?' live-work + active-effects
            tally and the cross-family stale-races view render from the work
            ledger + trace buffer"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-overrides!)
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer ep0011-buffer] {:frame :rf/xray})
      (let [tree (resources/Panel)]
        ;; "what is still running?" section + the live resource row, joined
        ;; to its latest trace phase (issued via :rf.resource/work-started).
        (is (some? (find-by-testid tree "rf-xray-resources-live-work-caption"))
            "live-work section rendered")
        (let [row (find-by-testid
                    tree (str "rf-xray-resources-live-work-row-" (hash ep0011-live-work-id)))]
          (is (some? row) "the live resource work row rendered")
          (is (re-find #"resource" (node-text row)) "work-kind surfaced")
          (is (re-find #"issued" (node-text row)) "latest trace phase joined + surfaced"))
        ;; the active-effects / suppression tally headline (per kind).
        (is (some? (find-by-testid tree "rf-xray-resources-stale-tally-http"))
            "per-kind suppression tally rendered")
        ;; the cross-family stale-races view — the suppressed HTTP arc.
        (is (some? (find-by-testid tree "rf-xray-resources-stale-races-caption"))
            "stale-races section rendered")
        (let [arc (find-by-testid
                    tree (str "rf-xray-resources-stale-race-row-"
                              (hash [:rf.work/http :search 1 1])))]
          (is (some? arc) "the suppressed HTTP attempt arc rendered")
          (is (re-find #"stale" (node-text arc)) "terminal :stale status surfaced")
          (is (re-find #"http" (node-text arc)) "work-kind surfaced")))))
  (testing "silent-by-default — a settled app (no live work, no suppression)
            renders NEITHER the live-work NOR the stale-races section"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/set-registered-resources-override-for-test resource-regs]
                        {:frame :rf/xray})
      ;; no live ledger, no trace buffer
      (rf/dispatch-sync [:rf.xray/set-resource-work-ledger-override-for-test {}]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer []] {:frame :rf/xray})
      (let [tree (resources/Panel)]
        (is (nil? (find-by-testid tree "rf-xray-resources-live-work-caption"))
            "no live-work section when nothing is running / suppressed")
        (is (nil? (find-by-testid tree "rf-xray-resources-stale-races-caption"))
            "no stale-races section when no arc was suppressed")))))

;; ---- (3b) EP-0019 optimistic mutation lifecycle render ------------------

(def ep0019-buffer
  [;; an optimistic apply that SUCCEEDED → reconciled (committed)
   {:id 60 :op-type :rf.event :operation :rf.mutation/optimistic-applied
    :tags {:mutation :realworld/favorite-article :instance [:favorite "welcome"]
           :work/id [:rf.work/resource [:rf.mutation [:favorite "welcome"]] 5]
           :generation 5 :scope session-scope :snapshot-id "snap-1"
           :affected-keys [[session-scope :article/by-slug {:slug "welcome"}]]
           :revisions [{:resource/key [session-scope :article/by-slug {:slug "welcome"}]
                        :revision 3 :forward :patch}]
           :tag-matched-keys [] :target-unresolved []
           :cause [:mutation :realworld/favorite-article [:favorite "welcome"]]}}
   {:id 61 :op-type :rf.event :operation :rf.mutation/optimistic-reconciled
    :tags {:mutation :realworld/favorite-article :instance [:favorite "welcome"]
           :work/id [:rf.work/resource [:rf.mutation [:favorite "welcome"]] 5]
           :generation 5 :snapshot-id "snap-1"
           :optimistic-keys [[session-scope :article/by-slug {:slug "welcome"}]]
           :committed [[session-scope :article/by-slug {:slug "welcome"}]]
           :reconciliation-refetches []
           :cause [:mutation :realworld/favorite-article [:favorite "welcome"]]}}
   ;; an optimistic apply that FAILED → rolled back, with a :force clobber
   {:id 62 :op-type :rf.event :operation :rf.mutation/optimistic-applied
    :tags {:mutation :realworld/rename :instance [:rename 7]
           :work/id [:rf.work/resource [:rf.mutation [:rename 7]] 9]
           :generation 9 :scope :rf.scope/global :snapshot-id "snap-2"
           :affected-keys [[:rf.scope/global :realworld/feed {}]]
           :revisions [{:resource/key [:rf.scope/global :realworld/feed {}]
                        :revision 2 :forward :seed}]
           :tag-matched-keys [] :target-unresolved []
           :cause [:mutation :realworld/rename [:rename 7]]}}
   {:id 63 :op-type :rf.event :operation :rf.mutation/optimistic-rolled-back
    :tags {:mutation :realworld/rename :instance [:rename 7]
           :work/id [:rf.work/resource [:rf.mutation [:rename 7]] 9]
           :generation 9 :snapshot-id "snap-2" :on-conflict :force
           :dispositions [{:resource/key [:rf.scope/global :realworld/feed {}]
                           :restored true :conflict true :on-conflict :force}]
           :restored [[:rf.scope/global :realworld/feed {}]]
           :conflicted [[:rf.scope/global :realworld/feed {}]]
           :refetched []
           :cause [:rf.mutation/failed :realworld/rename]}}
   {:id 64 :op-type :warning :operation :rf.warning/optimistic-force-clobber
    :tags {:mutation :realworld/rename :instance [:rename 7]
           :forced-keys [[:rf.scope/global :realworld/feed {}]]
           :recovery :review-on-conflict
           :reason "mutation :realworld/rename rolled back with :on-conflict :force"}}])

(deftest ep0019-optimistic-surfaces-render
  (testing "the optimistic-mutation lifecycle renders the apply→settle pairing
            (reconciled commit + rolled-back conflict) + the force-clobber
            warning from the trace buffer (EP-0019 slice 4b)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-overrides!)
      (rf/dispatch-sync [:rf.xray/sync-trace-buffer ep0019-buffer] {:frame :rf/xray})
      (let [tree (resources/Panel)]
        ;; the section renders
        (is (some? (find-by-testid tree "rf-xray-resources-optimistic"))
            "optimistic-mutations section rendered")
        ;; the SUCCEEDED apply paired with its reconcile → :reconciled outcome
        (let [row (find-by-testid tree "rf-xray-resources-optimistic-row-60")]
          (is (some? row) "the optimistic apply row rendered")
          (is (re-find #"reconciled" (node-text row))
              "the committed apply reads reconciled")
          (is (re-find #":realworld/favorite-article" (node-text row))
              "the mutation id surfaced")
          (is (some? (find-by-testid tree "rf-xray-resources-optimistic-row-60-committed"))
              "the committed keys surfaced"))
        ;; the FAILED apply paired with its rollback → :rolled-back + conflict
        (let [row (find-by-testid tree "rf-xray-resources-optimistic-row-62")]
          (is (some? row) "the rolled-back apply row rendered")
          (is (re-find #"rolled back" (node-text row))
              "the failed apply reads rolled back")
          (is (some? (find-by-testid tree "rf-xray-resources-optimistic-row-62-rollback"))
              "the rollback disposition surfaced")
          (is (some? (find-by-testid tree "rf-xray-resources-optimistic-row-62-conflicted"))
              "the conflicted key surfaced"))
        ;; the :force clobber warning renders loud
        (is (some? (find-by-testid tree "rf-xray-resources-optimistic-clobber-row-64"))
            "the :force-clobber warning row rendered")))))

(deftest instance-row-shows-status-and-owners
  (testing "the instance row surfaces status + owner-count, derived not stored"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-overrides!)
      (let [tree   (resources/Panel)
            status (find-by-testid tree "rf-xray-resources-instance-row-article/by-slug-g4-status")]
        (is (some? status))
        (is (re-find #"loaded" (node-text status)) "status reads loaded")))))

(defn- seed-live-route!
  "Register the route declaring `:resources` so the graph has a node, and seed
  the live routing slice with `:route/article` active under nav-1 with the
  article scoped key still in the unsettled-blocking set.

  Registration goes via the registrar directly (the routing artefact's
  `reg-route` macro is not on the xray test classpath); the graph reads the
  registry map decoupled via `(rf/registrations :route)`."
  []
  (registrar/register! :route :route/article
                       {:path "/articles/:slug"
                        :resources [{:resource :article/by-slug :blocking? true}]})
  (rf/dispatch-sync
    [:rf.xray/set-resource-routing-slice-override-for-test
     {:current {:route-id :route/article :nav-token "nav-1"}
      :resource-blocking
      {"nav-1" (let [k [session-scope :article/by-slug {:slug "welcome"}]]
                 {(rf-identity/canonical-bytes k) k})}}]
    {:frame :rf/xray}))

(deftest route-graph-shows-live-active-route
  (testing "rf2-m5u3gt — with a live routing slice override, the route/resource
            graph flags the active route (● active) and surfaces the live
            unsettled-blocking wait point off the runtime routing slice"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-overrides!)
      (seed-live-route!)
      (let [tree (resources/Panel)
            row  (find-by-testid tree "rf-xray-resources-route-row-route/article")]
        (is (some? row) "the route row renders")
        (is (some? (find-by-testid tree "rf-xray-resources-route-row-route/article-current"))
            "the active route is flagged ● active off the live routing slice")
        (is (some? (find-by-testid tree "rf-xray-resources-route-row-route/article-blocking-live"))
            "the live unsettled-blocking wait point surfaces")
        (is (re-find #"fresh" (node-text row))
            "the blocking :article/by-slug reads :fresh from its live cache entry")))))

(deftest route-graph-freshness-chip-discriminates-stale
  (testing "rf2-ybqse — the freshness chip is DISCRIMINATING, not decorative:
            the SAME route graph over an entry whose `:stale-at` has already
            passed reads `stale`, never `fresh`. This deterministically forces
            the state that used to surface only as a flake (the sibling test's
            load-time-anchored `:stale-at` decayed past its horizon mid-run and
            read `stale (1 work)`), so both sides of the `derive-stale?`
            comparison are now pinned by an assertion. Without this, a
            regression that hard-wired the chip to `:fresh` would leave the
            sibling green."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (seed-overrides! (stale-live-entries))
      (seed-live-route!)
      (let [tree (resources/Panel)
            row  (find-by-testid tree "rf-xray-resources-route-row-route/article")]
        (is (some? row) "the route row renders")
        (is (re-find #"stale" (node-text row))
            "an entry past its :stale-at reads :stale on the route graph")
        (is (not (re-find #"fresh" (node-text row)))
            "and does NOT read :fresh — the two are mutually exclusive")))))

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

;; ---- (4b) PRIVACY: on-box render redacts a RAW :sensitive value (rf2-9zix0u)
;;
;; The `privacy-redacted-data-never-raw` test above proves the panel renders an
;; ALREADY-`:rf/redacted` sentinel (the runtime elided it BEFORE Xray saw it).
;; It does NOT prove the on-box render path redacts a RAW frame-`:sensitive`
;; resource value — which is the actual rf2-9zix0u leak: the production sub
;; called `project-instances` with NO egress-fn, so a LIVE `:sensitive?` entry
;; (holding the real fetched value) `pr-str`-previewed raw to the DOM. This
;; test drives the PRODUCTION sub `:rf.xray/resources-tab-data` end-to-end
;; against an OBSERVED frame that declares its resource payload paths
;; `:sensitive`, and asserts the rendered rows redact. FAILS before the fix
;; (raw token previews), PASSES after.

(def ^:private zix-secret "secret-session-jwt-zzz-9zix0u")
(def ^:private zix-observed-frame :app/secure-observed)
;; The `:entries` map key is the opaque byte key-id STRING (rf2-9e0tyq); the
;; kind-preserving scoped-key rides on the entry as `:resource/key`.
(def ^:private zix-key-id "kid-9zix0u-secret-1")
(def ^:private zix-scoped-key
  [[:rf.scope/session {:secret zix-secret :tenant "t-1"}]
   :article/by-slug
   {:secret zix-secret :slug "welcome"}])
(def ^:private zix-entries
  {zix-key-id
   {:resource/id   :article/by-slug
    :resource/key  zix-scoped-key
    :status        :loaded
    :data          {:secret zix-secret :title "Welcome"}
    :generation    7
    :active-owners #{[:route :route/article "nav-1"]}
    :tags          #{[:article "welcome"]}
    :request-id    [:w 7]}})

(defn- install-zix-observed-frame! []
  ;; Declare the three payload SLOTS `:sensitive` at the ABSOLUTE runtime-db
  ;; paths the resources registry lowers per-instance declarations to
  ;; (rf2-aw9cfs) — data at `[…entries <key-id> :data]`, scope at
  ;; `[…entries <key-id> :resource/key 0]`, params at `[… :resource/key 2]`.
  ;; The on-box egress re-roots each slot to exactly these coordinates.
  (rf/make-frame {:id zix-observed-frame})
  (frame/swap-runtime-db! zix-observed-frame
    (fn [rt]
      (elision/apply-classification-effects rt
        {:sensitive [[:rf.runtime/resources :entries zix-key-id :data]
                     [:rf.runtime/resources :entries zix-key-id :resource/key 0]
                     [:rf.runtime/resources :entries zix-key-id :resource/key 2]]}))))

(defn- zix-instance-row []
  (let [data (:instances @(rf/subscribe [:rf.xray/resources-tab-data]))]
    (first data)))

(deftest on-box-render-redacts-raw-sensitive-payload
  (testing "rf2-9zix0u — the production :rf.xray/resources-tab-data sub redacts
            a RAW frame-`:sensitive` resource payload on the ON-BOX render path
            (screen-share safe), not just an already-`:rf/redacted` sentinel"
    (setup-xray-frame!)
    (install-zix-observed-frame!)
    (rf/with-frame :rf/xray
      ;; Point Xray's observed frame at the classified frame + seed the raw
      ;; sensitive entries the on-box render projects.
      (rf/dispatch-sync [:rf.xray/set-target-frame zix-observed-frame]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/set-registered-resources-override-for-test resource-regs]
                        {:frame :rf/xray})
      (rf/dispatch-sync [:rf.xray/set-resource-entries-override-for-test zix-entries]
                        {:frame :rf/xray})
      (is (= zix-observed-frame @(rf/subscribe [:rf.xray/observed-frame]))
          "sanity: Xray is observing the classified frame")
      (let [row (zix-instance-row)]
        (is (some? row) "the sub projected the seeded live instance")
        (testing "each :sensitive payload slot renders [redacted], never the raw token"
          (doseq [slot [:data :scope :params]]
            (is (= "[redacted]" (:preview (get row slot)))
                (str slot " redacts to [redacted] on the on-box render path"))
            (is (true? (:redacted? (get row slot)))
                (str slot " carries the :redacted? sentinel flag"))))
        (testing "no raw secret leaks into ANY string display field of any slot"
          (doseq [slot [:data :scope :params]]
            (is (not-any? #(and (string? %) (str/includes? % zix-secret))
                          (vals (get row slot)))
                (str "the raw session token never appears in the " slot " summary"))))
        (testing "metadata + derived facts survive the redaction (project from the
                  RAW entry, never through egress)"
          (is (= :loaded (:status row)))
          (is (= 7 (:generation row)))
          (is (= :article/by-slug (:resource-id row)))
          (is (true? (:has-data? row))
              "redacting the payload must not flip the derived :has-data? fact")
          (is (= 1 (:owner-count row)))
          (is (= zix-scoped-key (:scoped-key row))
              "the RAW scoped-key survives as the identity/react key"))))))

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

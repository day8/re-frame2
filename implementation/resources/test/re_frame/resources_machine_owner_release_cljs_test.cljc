(ns re-frame.resources-machine-owner-release-cljs-test
  "Per rf2-xw5t0y — a destroyed state-machine actor MUST release its resource
  leases (Spec 016 §Release authority is per owner kind, 016:291:

    | Machine | [:machine actor-id] | Actor destroy — when the
    owning machine instance is stopped/destroyed, its resource leases are
    released. |

  ). The owner key the machine runtime owns is `[:machine actor-id]` — the
  runtime-derivable machine-owner key the derivation algebra names (Spec
  Derivations §Lifecycle: `[:machine :upload/main]`; machines
  `tooling/node-for` emits `:owner [:machine id]`), `id` being the registered
  machine-id for a singleton and the `<type>#<n>` for a spawned actor.

  THE BUG (rf2-xw5t0y, surfaced by the rf2-na0j8r conceptual audit): the
  machine teardown NEVER dispatched `:rf.resource/release-owner`. A machine
  that `ensure`d a resource under its `[:machine actor-id]` owner LEAKED the
  lease on destroy — the entry stayed owner-pinned and kept refetching /
  polling forever (a slow leak). The spec leaned on \"machine liveness is a
  pure function of frame-state\" as if release were automatic, but resource
  liveness is a SEPARATE durable owner-set, not derived from machine state.

  THE FIX: machine teardown fires the EXISTING `:rf.resource/release-owner`
  effect for owner `[:machine actor-id]`, by KEYWORD dispatch (machines never
  `:require`s resources — sibling artefact; guarded on the handler being
  registered so a no-resources app is a clean no-op). This is the CROSS-
  ARTEFACT integration pin: a real machine, a real resource entry, real
  release — proving the leak is closed end-to-end.

  Both destroy CAUSES are covered observably on a surviving frame:
    1. explicit `[:rf.machine/destroy <id>]` (routes through
       `teardown-live-actor!`), and
    2. the `:final?`-state auto-destroy (routes through `finalize-machine`,
       which appends the release to its returned `:fx`).
  The frame-destroy cascade tears spawned actors down through the SAME
  `teardown-live-actor!` (so it fires the same release), but on frame destroy
  the whole runtime-db — incl. all resource entries — is released anyway, so
  the observable surviving-frame release is pinned by (1) + (2).

  Dual-target (`.cljc` + `_cljs_test`): JVM via the `.*-test$` regex, Shadow
  `:node-test` via `cljs-test$`. machines is a TEST-ONLY dep of the resources
  artefact (resources/deps.edn) — it depends only on core (no cycle) and the
  production resources classpath stays machines-free (bundle-isolation holds)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load-bearing side-effecting requires: the resources façade registers the
   ;; :rf.resource/* events; machines wires the machine grammar + the
   ;; :rf.machine/start / :rf.machine/destroy fxs (and the release-on-destroy
   ;; dispatch under test).
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   [re-frame.machines]
   [re-frame.machines.paths :as machine-paths]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as substrate]]
       :cljs [[re-frame.adapter.reagent :as substrate]])))

;; ---- deterministic transport + timer capture -----------------------------
;;
;; No real wall-clock timer fires: stub managed-HTTP so an ensure never
;; fetches, and CAPTURE :rf.resource/cancel-poll-timers so the owner-release
;; poll-STOP is asserted directly (release-owner-handler emits this fx for
;; every entry whose :active-owners just went empty).

(def ^:private cancelled-poll (atom []))

(defn- capturing-fixture
  [f]
  (reset! cancelled-poll [])
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-fx :rf.http/managed-abort (fn [_ctx _wid] nil))
  (rf/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
  (rf/reg-fx :rf.resource/cancel-poll-timers
             (fn [_ctx args] (swap! cancelled-poll conj args) nil))
  ;; A poll-enabled, actively-owned resource. The machine actor ensures it on
  ;; entry under its `[:machine actor-id]` owner; on actor destroy the lease
  ;; must release (owner-index drop + :active-owners empty + poll cancelled +
  ;; entry GC-eligible).
  (rf/reg-resource :art/by-slug
    {:scope            :rf.scope/from-caller
     :params-schema    [:map [:slug :string]]
     :poll-interval-ms 5000
     :gc-after-ms      9000
     :tags             (fn [{:keys [slug]} _data] #{[:article slug]})}
    (fn [{:keys [slug]} _ctx]
      {:request {:method :get :url (str "/api/articles/" slug)}}))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter})
  capturing-fixture)

;; ---- helpers --------------------------------------------------------------

;; A CONCRETE caller-supplied scope. `:rf.scope/from-caller` is a registration
;; POLICY (it never resolves to a concrete key); the ensure CALLER supplies the
;; actual scope — here a constant map, so every ensure + the slug-key agree.
(def ^:private scope {:app :reader})

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- slug-key [slug]
  (state/scoped-resource-key scope :art/by-slug {:slug slug}))
(defn- entry [scoped-key] (get-in (runtime-db) (state/entry-path scoped-key)))
(defn- machine-snapshot [id]
  (get-in (runtime-db) (machine-paths/snapshot-path id)))
(defn- owner-index []
  ;; :entries is keyed on the opaque byte key-id; map the owner-index members
  ;; back to scoped-key vectors for readable assertions (mirrors the
  ;; scoped-lease-lifecycle suite's owner-index helper).
  (let [rdb    (runtime-db)
        es     (get-in rdb (state/entries-path))
        id->sk (into {} (map (fn [[k-id e]] [k-id (:resource/key e)])) es)]
    (into {} (map (fn [[owner members]]
                    [owner (into #{} (map #(get id->sk % %)) members)]))
          (get-in rdb (state/owner-index-path)))))
(defn- succeed! [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key :work/id (:current-work e)
                        :generation (:generation e) :data data}])))
(defn- gc-recheck! [scoped-key]
  (rf/dispatch-sync [:rf.resource.internal/gc-fired {:resource/key scoped-key}]))
(defn- poll-fired! [scoped-key]
  (rf/dispatch-sync [:rf.resource.internal/poll-fired
                     {:resource/key scoped-key :hidden? false}]))
(defn- poll-cancelled-for? [k]
  (some (fn [{ks :resource/keys}] (some #{k} ks)) @cancelled-poll))

(defn- reg-reader-on-entry-ensures!
  "Register a singleton `:reader/proc` whose initial state, on entry, ensures
  `:art/by-slug` under its OWN actor-id owner `[:machine :reader/proc]` (for a
  singleton the actor-id IS the registered machine-id — exactly the key
  teardown releases; Spec 016 §Machine-owned resource). `extra-states` merges
  in any extra state nodes (e.g. a `:final?` target for the auto-destroy case)."
  [slug extra-states reading-on]
  (rf/reg-machine :reader/proc
    {:initial :reading
     :data    {:slug slug}
     :states  (merge
                {:reading
                 {:entry (fn [{{s :slug} :data}]
                           {:fx [[:dispatch
                                  [:rf.resource/ensure
                                   {:resource :art/by-slug
                                    :scope    scope
                                    :params   {:slug s}
                                    :owner    [:machine :reader/proc]
                                    :cause    [:machine-action :reader/read]}]]]})
                  :on reading-on}}
                extra-states)}))

;; ===========================================================================
;; 1. Explicit destroy of a SINGLETON releases its [:machine <id>] lease
;; ===========================================================================

(deftest explicit-actor-destroy-releases-machine-owned-resource-lease
  (testing "rf2-xw5t0y — a singleton machine ensures a resource under its
            [:machine actor-id] owner; an explicit [:rf.machine/destroy <id>]
            releases the lease (owner-index drop + :active-owners empty + poll
            cancelled + entry GC-eligible) so the lease does NOT outlive the
            actor (Spec 016:290)"
    (let [slug  "resources-101"
          owner [:machine :reader/proc]
          k     (slug-key slug)]
      (reg-reader-on-entry-ensures! slug {:idle {}} {:stop :idle})
      ;; Eager-start the singleton — the :entry ensure fires, attaching the
      ;; [:machine :reader/proc] lease; settle it :loaded so the poll arms.
      (rf/dispatch-sync [:reader/proc [:rf.machine/start]])
      (succeed! k {:title "Resources 101"})

      ;; Precondition: the actor holds the lease, the entry is owned + poll-able.
      (is (some? (entry k)) "the machine-owned resource entry exists")
      (is (contains? (:active-owners (entry k)) owner)
          "the [:machine :reader/proc] lease is on the entry")
      (is (contains? (get (owner-index) owner) k)
          "the owner-index maps the machine owner to the entry")
      (is (some? (machine-snapshot :reader/proc))
          "the singleton actor is live before destroy")

      ;; ACT: explicitly destroy the actor. teardown-live-actor! fires
      ;; :rf.resource/release-owner for [:machine :reader/proc]; the queued
      ;; dispatch drains in-line within this dispatch-sync.
      (reset! cancelled-poll [])
      (rf/reg-event ::destroy-reader
        (fn [_ _] {:fx [[:rf.machine/destroy :reader/proc]]}))
      (is (nil? (try (rf/dispatch-sync [::destroy-reader]) nil
                     (catch #?(:clj Throwable :cljs :default) e e)))
          "destroying the actor does not throw")

      ;; ASSERT: the lease is released — the leak is closed.
      (is (nil? (machine-snapshot :reader/proc))
          "the actor is gone (snapshot torn down)")
      (is (empty? (:active-owners (entry k)))
          "the machine lease was released — entry is now owner-free")
      (is (nil? (get (owner-index) owner))
          "the machine owner is gone from the owner-index (no dangling lease)")
      (is (poll-cancelled-for? k)
          "the entry going owner-free cancels its poll timer (no continued polling)")

      ;; And the entry is GC-eligible: an owner-free, work-free entry is
      ;; collected by a GC re-check — it does not linger pinned.
      (is (nil? (:current-work (entry k))) "no in-flight work pins the entry")
      (gc-recheck! k)
      (is (nil? (entry k))
          "the owner-free entry is GC-eligible and the re-check collects it"))))

;; ===========================================================================
;; 2. :final?-state auto-destroy ALSO releases the lease (a different CAUSE
;;    — finalize-machine, not teardown-live-actor!) — proving the release
;;    fires regardless of cause
;; ===========================================================================

(deftest final-state-auto-destroy-releases-machine-owned-resource-lease
  (testing "rf2-xw5t0y — a singleton that ensures a resource under [:machine
            actor-id] and then enters a :final? state AUTO-destroys (cause
            :rf.machine/finished, via finalize-machine); the lease must still
            release. This pins the OTHER teardown codepath — finalize appends
            the release to its returned :fx — so the release fires regardless
            of destroy cause"
    (let [slug  "owners-vs-causes"
          owner [:machine :reader/proc]
          k     (slug-key slug)]
      (reg-reader-on-entry-ensures! slug {:done {:final? true}} {:finish :done})
      (rf/dispatch-sync [:reader/proc [:rf.machine/start]])
      (succeed! k {:title "Owners vs Causes"})
      (is (contains? (:active-owners (entry k)) owner) "leased before finish")

      ;; ACT: drive the singleton into :final? — finalize-machine auto-destroys
      ;; it AND (rf2-xw5t0y) releases its [:machine :reader/proc] lease via the
      ;; release fx appended to its returned :fx (drains in-line).
      (reset! cancelled-poll [])
      (rf/dispatch-sync [:reader/proc [:finish]])

      (is (nil? (machine-snapshot :reader/proc))
          "the actor auto-destroyed on :final?")
      (is (empty? (:active-owners (entry k)))
          "the machine lease released on auto-destroy too")
      (is (nil? (get (owner-index) owner))
          "owner gone from the index on auto-destroy")
      (is (poll-cancelled-for? k)
          "poll cancelled on the owner-free entry (auto-destroy path)")
      (gc-recheck! k)
      (is (nil? (entry k))
          "the released entry is GC-eligible after auto-destroy"))))

;; ===========================================================================
;; 3. SCOPING — destroying ONE actor releases ONLY its lease, not a sibling's
;; ===========================================================================

(deftest actor-destroy-release-is-scoped-to-the-destroyed-actor
  (testing "rf2-xw5t0y — over-release guard: destroying actor A releases ONLY
            [:machine A]'s lease; a second resource leased by a different owner
            (a sibling actor / lease) is untouched"
    (let [ka     (slug-key "a")
          kb     (slug-key "b")
          owner  [:machine :reader/proc]
          sibling [:machine :other/proc]]
      ;; Actor A ensures resource A under [:machine :reader/proc].
      (reg-reader-on-entry-ensures! "a" {:idle {}} {:stop :idle})
      (rf/dispatch-sync [:reader/proc [:rf.machine/start]])
      (succeed! ka {:title "A"})
      ;; A sibling lease (stand-in for another live actor) on resource B.
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :art/by-slug :scope scope
                          :params {:slug "b"} :owner sibling}])
      (succeed! kb {:title "B"})
      (is (contains? (:active-owners (entry ka)) owner) "A leased")
      (is (contains? (:active-owners (entry kb)) sibling) "B leased by sibling")

      ;; Destroy ONLY actor A.
      (rf/reg-event ::destroy-a (fn [_ _] {:fx [[:rf.machine/destroy :reader/proc]]}))
      (rf/dispatch-sync [::destroy-a])

      (is (empty? (:active-owners (entry ka))) "A's lease released")
      (is (nil? (get (owner-index) owner)) "A's owner gone from the index")
      (is (contains? (:active-owners (entry kb)) sibling)
          "the sibling's lease on B is UNTOUCHED (no over-release)")
      (is (contains? (get (owner-index) sibling) kb)
          "the sibling owner is still indexed"))))

;; ===========================================================================
;; 4. A poll-fired re-check on the released entry refetches NOTHING
;;    (the leak symptom: an orphaned owner keeps refetching/polling forever)
;; ===========================================================================

(deftest released-machine-entry-does-not-keep-polling
  (testing "rf2-xw5t0y — after the actor's lease is released, a poll-fired
            re-check on the (still-present-but-owner-free) entry refetches
            nothing and does not re-arm: the orphaned-owner refetch leak is
            closed (Spec 016 §Polling — a poll never pins an owner-free entry)"
    (let [slug  "infinite"
          owner [:machine :reader/proc]
          k     (slug-key slug)]
      (reg-reader-on-entry-ensures! slug {:idle {}} {:stop :idle})
      (rf/dispatch-sync [:reader/proc [:rf.machine/start]])
      (succeed! k {:title "Infinite"})
      (is (contains? (:active-owners (entry k)) owner) "leased before destroy")

      (rf/reg-event ::destroy-reader (fn [_ _] {:fx [[:rf.machine/destroy :reader/proc]]}))
      (rf/dispatch-sync [::destroy-reader])
      (is (empty? (:active-owners (entry k))) "owner-free after actor destroy")

      ;; A poll tick on the owner-free entry must do nothing: no refetch, no
      ;; flip back to :loading, no re-attached owner — the entry stays settled
      ;; and owner-free (it was the orphaned-owner poll that leaked).
      (let [gen-before    (:generation (entry k))
            status-before (:status (entry k))]
        (poll-fired! k)
        (is (empty? (:active-owners (entry k)))
            "poll-fired did not re-attach any owner to the owner-free entry")
        (is (= status-before (:status (entry k)))
            "poll-fired did not flip the owner-free entry's status (no refetch)")
        (is (= gen-before (:generation (entry k)))
            "poll-fired started no new work generation on the owner-free entry")))))

(ns re-frame.realm-cljs-test
  "EP-0013 D1 internal staging 1-4 — the runtime realm record, the default
  realm, the realm-owned registrar, the frame's realm reference (stages 1-3,
  rf2-gkddyq), and the realm-owned adapter SELECTION + host-transient
  subsystem inventory (stage 4, rf2-0lq5cd).

  D1 is INTERNAL: no public `realm/construct-realm` constructor and no realm-targeted
  public query ship. The headline acceptance is **zero ergonomic regression**
  — everything routes through one default realm so a single-realm app's
  surface is byte-identical. These tests pin:

    (1) the default realm exists with id `:rf.realm/default` and the shipped
        D1 record shape (Spec-Schemas §`:rf/realm`);
    (2) the realm OWNS the registrar — the default realm's `:registrar` slot
        IS the existing process-global `registrar/kind->id->metadata` atom,
        so the registry shape + read/write API are unchanged
        (default-realm transparency: `reg-*` / dispatch / subscribe behave
        exactly as before);
    (3) a frame carries its realm REFERENCE internally (`:realm` slot →
        `:rf.realm/default`); the realm-owned frame-membership VIEW is
        derived live from the frame registry, with no separately-stored set;
    (4) the realm OWNS the adapter SELECTION — `re-frame.substrate.adapter` is
        the access seam over the default realm's `:adapter` +
        `:adapter-disposed?` slots; install / current / dispose route through
        the realm with byte-identical behaviour (stage 4);
    (5) the realm OWNS the host-transient subsystem DESCRIPTOR inventory —
        `subsystem-id → :rf/host-transient-descriptor` lives in the default
        realm's `:host-transient` slot; register / read / clear are
        realm-owned (stage 4).

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) AND the JVM
  `clojure -M:test` runner both pick it up. Pure CLJC — no DOM dependency;
  uses the plain-atom adapter."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.realm :as realm]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; (1) the internal realm record + the default realm
;; ---------------------------------------------------------------------------

(deftest default-realm-exists-with-reserved-id
  (testing "the process creates one default realm with id :rf.realm/default"
    (is (= :rf.realm/default realm/default-realm-id))
    (let [dr (realm/default-realm)]
      (is (some? dr) "default realm exists at process load")
      (is (= :rf.realm/default (:rf.realm/id dr))
          "the default realm carries the reserved :rf.realm/id")
      ;; The default realm is reachable by id through the realm registry.
      (is (identical? dr (realm/realm :rf.realm/default))
          "default-realm and (realm :rf.realm/default) are the same record"))))

(deftest realm-record-shipped-d1-shape
  (testing "the D1 realm record carries the shipped slots and leaves the
            D2/D3-reserved slots absent (Spec-Schemas §:rf/realm)"
    (let [dr (realm/default-realm)]
      ;; Shipped D1 slots.
      (is (contains? dr :rf.realm/id) "carries :rf.realm/id")
      (is (contains? dr :registrar)   "carries the realm-owned :registrar")
      ;; D2/D3-reserved — never required in D1, the container only.
      (is (not (contains? dr :app)) ":app (D2 app value) is absent in D1"))))

(deftest realm-id-reader-absence-is-default
  (testing "realm-id resolves nil → default realm (absence is the documented
            rule), passes a keyword through, and reads the map's id"
    (is (= :rf.realm/default (realm/realm-id nil))
        "absence resolves to the default realm")
    (is (= :tenant/a (realm/realm-id :tenant/a))
        "a realm-id keyword passes through unchanged")
    (is (= :rf.realm/default (realm/realm-id (realm/default-realm)))
        "a realm map resolves to its :rf.realm/id")))

;; ---------------------------------------------------------------------------
;; (2) the realm owns the registrar — default-realm transparency
;; ---------------------------------------------------------------------------

(deftest default-realm-owns-the-process-registrar
  (testing "the default realm's :registrar IS the existing process-global
            registrar atom — ownership moved to the realm, the shape did not"
    (is (identical? registrar/kind->id->metadata
                    (:registrar (realm/default-realm)))
        "the default realm holds the existing process-global registrar atom")
    (is (identical? registrar/kind->id->metadata
                    (realm/registrar (realm/default-realm)))
        "realm/registrar returns that same atom")
    (is (identical? registrar/kind->id->metadata
                    (realm/registrar nil))
        "realm/registrar nil resolves to the default realm's registrar")))

(deftest registry-read-api-unchanged-for-tooling
  (testing "the registry shape + read API are byte-stable — a registration
            made through the public reg-* surface is visible through the
            existing registrar read API the EP-0014 tooling sibling uses"
    (rf/reg-event :realm-test/inc
      {:doc "increment counter"}
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/reg-sub :realm-test/n
      {:doc "the counter"}
      (fn [db _] (:n db)))
    ;; Read API on the process-global registrar — unchanged shape.
    (is (some? (registrar/lookup :event :realm-test/inc))
        "registrar/lookup resolves a default-realm registration")
    (is (contains? (registrar/registrations :event) :realm-test/inc)
        "registrar/registrations exposes the registration")
    (is (fn? (registrar/handler :event :realm-test/inc))
        "registrar/handler returns the handler fn")
    ;; And reading through the realm's owned registrar atom is identical —
    ;; the realm owns the SAME table.
    (is (contains? (get @(realm/registrar (realm/default-realm)) :event)
                   :realm-test/inc)
        "the realm-owned registrar atom carries the same registration")))

(deftest default-realm-transparency-dispatch-subscribe
  (testing "reg-* / dispatch / subscribe work UNCHANGED through the default
            realm — the zero-ergonomic-regression headline"
    (rf/reg-frame :realm-test/app {:doc "transparency app"})
    (rf/reg-event :realm-test/set
      {:doc "set n"}
      (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-sub :realm-test/read-n
      {:doc "read n"}
      (fn [db _] (:n db)))
    ;; Dispatch + subscribe against the default-realm frame.
    (rf/dispatch-sync [:realm-test/set 7] {:frame :realm-test/app})
    (is (= {:n 7} (rf/app-db-value :realm-test/app))
        "app-db carries the dispatched value — single-app dispatch unchanged")
    (let [reaction (rf/subscribe :realm-test/app [:realm-test/read-n])]
      (is (= 7 @reaction)
          "subscribe reads the value — single-app subscribe unchanged"))))

;; ---------------------------------------------------------------------------
;; (3) a frame carries its realm reference internally
;; ---------------------------------------------------------------------------

(deftest frame-carries-default-realm-reference
  (testing "a frame stores a realm REFERENCE (the :rf.realm/default id) and
            frame-realm reads it back"
    (rf/reg-frame :realm-test/f {:doc "a frame"})
    (let [record (frame/frame :realm-test/f)]
      (is (= :rf.realm/default (:realm record))
          "the frame record carries the default realm id in its :realm slot")
      (is (= :rf.realm/default (frame/frame-realm :realm-test/f))
          "frame-realm reads the frame's realm reference"))
    (is (nil? (frame/frame-realm :realm-test/never-registered))
        "frame-realm is nil for an unknown frame")))

(deftest realm-owned-frame-membership-view-is-derived
  (testing "the realm-owned frame-membership view is derived live from the
            frame registry — a reg-frame joins it, a destroy-frame! leaves it.
            (The reset fixture installs the ordinary :rf/default frame into the
            default realm, so membership is asserted by joins/leaves of the
            test frames, not by exact-set equality with the fixture frame.)"
    (let [base (realm/realm-frames :rf.realm/default)]
      (is (not (contains? base :realm-test/m1))
          "the test frames are not members before registration")
      (rf/reg-frame :realm-test/m1 {:doc "member 1"})
      (rf/reg-frame :realm-test/m2 {:doc "member 2"})
      (let [members (realm/realm-frames :rf.realm/default)]
        (is (contains? members :realm-test/m1)
            "a reg-frame joins the default realm's membership view")
        (is (contains? members :realm-test/m2)
            "the second frame joins too")
        (is (= members (realm/realm-frames))
            "the zero-arg arity defaults to the default realm"))
      ;; Destroying a frame retracts it from the view (membership is derived,
      ;; so the dissoc from the frame registry is the only step needed).
      (rf/destroy-frame! :realm-test/m1)
      (let [members (realm/realm-frames :rf.realm/default)]
        (is (not (contains? members :realm-test/m1))
            "a destroy-frame! leaves the membership view")
        (is (contains? members :realm-test/m2)
            "the surviving frame is still a member")))
    ;; A realm with no frames is empty, never an error.
    (is (= #{} (realm/realm-frames :tenant/nonexistent))
        "an unknown realm has an empty membership view")))

(deftest frames-by-realm-groups-live-frames-only
  (testing "frames-by-realm groups non-destroyed frames by their :realm slot"
    (rf/reg-frame :realm-test/a {:doc "a"})
    (rf/reg-frame :realm-test/b {:doc "b"})
    (let [by-realm (frame/frames-by-realm)]
      (is (contains? (get by-realm :rf.realm/default) :realm-test/a)
          "default-realm frame a is grouped under :rf.realm/default")
      (is (contains? (get by-realm :rf.realm/default) :realm-test/b)
          "default-realm frame b is grouped under :rf.realm/default"))
    (rf/destroy-frame! :realm-test/a)
    (let [by-realm (frame/frames-by-realm)]
      (is (not (contains? (get by-realm :rf.realm/default) :realm-test/a))
          "a destroyed frame drops out of the grouping")
      (is (contains? (get by-realm :rf.realm/default) :realm-test/b)
          "the surviving frame stays in the grouping"))))

;; ---------------------------------------------------------------------------
;; (4) the realm OWNS the adapter SELECTION — stage 4 (rf2-0lq5cd)
;;
;; The adapter selection moved off the two process-global `defonce` cells that
;; used to live in `re-frame.substrate.adapter` and onto the default realm's
;; record (`:adapter` + `:adapter-disposed?`). `substrate.adapter` is now the
;; ACCESS SEAM. The headline is STILL zero ergonomic regression: install /
;; current / dispose behave byte-identically, routed through the one default
;; realm. (The reset fixture installs plain-atom into the default realm, so
;; these tests assert the seam reads/writes the realm slot rather than that an
;; adapter is absent at entry.)
;; ---------------------------------------------------------------------------

(deftest default-realm-owns-the-adapter-selection
  (testing "the installed adapter is OWNED BY the default realm — the seam's
            read resolves the realm's :adapter slot"
    ;; The fixture installed plain-atom into the default realm.
    (is (identical? plain-atom/adapter (realm/realm-adapter))
        "realm/realm-adapter returns the fixture-installed adapter")
    (is (identical? (realm/realm-adapter)
                    (:adapter (realm/default-realm)))
        "the read seam IS the default realm's :adapter slot")
    (is (identical? (realm/realm-adapter)
                    (adapter/current-adapter-spec))
        "substrate.adapter/current-adapter-spec routes through the realm")
    (is (= :rf.adapter/plain-atom (adapter/current-adapter))
        "current-adapter reports the realm-owned adapter's kind")))

(deftest adapter-install-dispose-lifecycle-is-realm-owned
  (testing "install seats the adapter on the realm + clears the breadcrumb;
            dispose clears the slot + sets the breadcrumb — the two-cell shape
            is preserved across the move onto the realm record"
    ;; Start from the fixture-installed state, tear down to a clean cold slate.
    (adapter/dispose-adapter!)
    (is (nil? (realm/realm-adapter))
        "dispose clears the realm's :adapter slot")
    (is (true? (realm/realm-adapter-disposed?))
        "dispose sets the realm's :adapter-disposed? breadcrumb")
    (is (true? (adapter/adapter-disposed?))
        "the seam's adapter-disposed? reads the realm breadcrumb")
    ;; Re-install: the breadcrumb clears, the slot is seated.
    (adapter/install-adapter! plain-atom/adapter)
    (is (identical? plain-atom/adapter (realm/realm-adapter))
        "install re-seats the adapter on the realm")
    (is (false? (realm/realm-adapter-disposed?))
        "install clears the breadcrumb")
    ;; The single-adapter-per-realm guard still fires on a double install.
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (adapter/install-adapter! plain-atom/adapter))
        "a second install without an intervening dispose still throws")))

(deftest adapter-lifecycle-cold-reset-seam
  (testing "reset-lifecycle-state-for-tests! wipes the realm's adapter slot +
            breadcrumb to a never-installed cold state"
    ;; The fixture already seated plain-atom; tear it down so the breadcrumb is
    ;; set, then prove the cold reset clears BOTH the slot and the breadcrumb.
    (adapter/dispose-adapter!)
    (is (true? (realm/realm-adapter-disposed?))
        "dispose leaves the disposed breadcrumb set")
    (adapter/reset-lifecycle-state-for-tests!)
    (is (nil? (realm/realm-adapter))
        "cold reset clears the realm's :adapter slot")
    (is (false? (realm/realm-adapter-disposed?))
        "cold reset clears the breadcrumb — distinct from a disposed state")))

;; ---------------------------------------------------------------------------
;; (5) the realm OWNS the host-transient subsystem inventory — stage 4
;;
;; The framework's host-transient side-tables (HTTP in-flight, routing nav
;; counters, machine timers, …) are realm-owned. Stage 4 makes the realm the
;; OWNER of the DESCRIPTOR inventory (subsystem-id → descriptor), recording
;; each subsystem's scope / teardown / test-reset. The side-table bytes stay
;; in their producing artefacts (reset through the existing late-bind hooks);
;; this is the queryable record of what exists + how each is torn down.
;; ---------------------------------------------------------------------------

(deftest host-transient-inventory-is-realm-owned
  (testing "a host-transient descriptor registers under the default realm and
            reads back; the inventory lives in the realm's :host-transient slot"
    ;; A fresh realm carries no host-transient inventory.
    (realm/clear-host-transient!)
    (is (nil? (realm/host-transient))
        "a realm with no registered subsystem has no host-transient inventory")
    (let [descriptor {:id            :rf.test/in-flight
                      :storage-class :host-transient
                      :scope         :frame
                      :durability    :none}]
      (realm/register-host-transient! descriptor)
      (is (= descriptor (realm/host-transient :rf.realm/default :rf.test/in-flight))
          "the descriptor reads back by subsystem-id")
      (is (= {:rf.test/in-flight descriptor}
             (realm/host-transient))
          "the inventory is the realm's :host-transient slot")
      (is (= {:rf.test/in-flight descriptor}
             (:host-transient (realm/default-realm)))
          "the slot lives on the default realm record")
      ;; Registering a second subsystem adds to (does not clobber) the table.
      (let [d2 {:id :rf.test/timers :storage-class :host-transient
                :scope :realm :durability :none}]
        (realm/register-host-transient! d2)
        (is (= #{:rf.test/in-flight :rf.test/timers}
               (set (keys (realm/host-transient))))
            "a second register adds to the inventory")))
    ;; Clearing drops the inventory (the test-reset / cold-start counterpart).
    (realm/clear-host-transient!)
    (is (nil? (realm/host-transient))
        "clear-host-transient! drops the realm's inventory")))

(deftest host-transient-durability-is-none
  (testing "every host-transient descriptor declares :durability :none — the
            non-durable contract (MUST NOT ride the wire)"
    (realm/clear-host-transient!)
    (realm/register-host-transient!
      {:id :rf.test/nav-counters :storage-class :host-transient
       :scope :frame :durability :none})
    (is (= :none (:durability (realm/host-transient :rf.realm/default
                                                    :rf.test/nav-counters)))
        "the registered descriptor is non-durable")
    (realm/clear-host-transient!)))

;; ---------------------------------------------------------------------------
;; (6) host-transient :teardown is wired into destroy-frame! (rf2-c6armm.7 #2)
;;
;; The spec says a :frame-scoped host-transient descriptor's :teardown "runs on
;; frame destroy" (Spec-Schemas §:rf/host-transient-descriptor; Runtime-Subsystems
;; §Host-transient subsystem state). Before the fix, destroy-frame! released
;; host-transient state only through a FIXED list of hard-coded per-subsystem
;; hooks — a subsystem that registered a frame-scoped descriptor with the realm
;; (the single owning inventory) would still leak unless it ALSO had a bespoke
;; hook. The fix walks the frame's realm's inventory on destroy.
;; ---------------------------------------------------------------------------

(deftest host-transient-frame-scoped-teardown-fires-on-destroy-frame
  (testing "rf2-c6armm.7 #2: a :frame-scoped host-transient descriptor's :teardown
            FIRES on destroy-frame!, addressed to the (frame, realm) being torn
            down. A :realm-scoped descriptor does NOT fire on frame destroy (it
            outlives an individual frame — it releases on dispose-realm!)."
    (realm/clear-host-transient!)
    (let [torn (atom [])]
      ;; A frame-scoped descriptor: its :teardown must run on destroy-frame!.
      (realm/register-host-transient!
        {:id            :rf.test/frame-scoped
         :storage-class :host-transient :scope :frame :durability :none
         :teardown      (fn [frame-id realm-id]
                          (swap! torn conj [:frame-scoped frame-id realm-id]))})
      ;; A realm-scoped descriptor: it must NOT run on destroy-frame! (only on
      ;; dispose-realm!), so it is the negative control.
      (realm/register-host-transient!
        {:id            :rf.test/realm-scoped
         :storage-class :host-transient :scope :realm :durability :none
         :teardown      (fn [& _] (swap! torn conj :realm-scoped-fired))})
      ;; A real frame in the default realm.
      (rf/reg-frame :ht/frame {:doc "host-transient teardown frame"})
      (is (some? (frame/frame :ht/frame)) "the frame is live before destroy")
      ;; Destroy it — the frame-scoped teardown must fire, addressed to (frame, realm).
      (frame/destroy-frame! :ht/frame)
      (is (= [[:frame-scoped :ht/frame :rf.realm/default]] @torn)
          "the :frame-scoped descriptor's :teardown ran with the (frame, realm)
           address; the :realm-scoped one did NOT fire on frame destroy")
      ;; The descriptor INVENTORY itself is realm-owned and persists (other frames
      ;; may share it) — only the per-frame side-table bytes are torn down. The
      ;; inventory drops on realm dispose / clear, not on frame destroy.
      (is (some? (realm/host-transient :rf.realm/default :rf.test/frame-scoped))
          "the descriptor record persists in the realm inventory after frame destroy"))
    (realm/clear-host-transient!)))

(deftest host-transient-frame-teardown-is-best-effort
  (testing "rf2-c6armm.7 #2: a throwing host-transient :teardown does not abort
            destroy-frame! — it is best-effort (caught + accumulated), so the rest
            of teardown and a SECOND descriptor's teardown still run, mirroring the
            safe-call-hook! cleanup semantics."
    (realm/clear-host-transient!)
    (let [second-ran (atom false)]
      (realm/register-host-transient!
        {:id            :rf.test/throwing
         :storage-class :host-transient :scope :frame :durability :none
         :teardown      (fn [& _] (throw (ex-info "boom in teardown" {})))})
      (realm/register-host-transient!
        {:id            :rf.test/after-throw
         :storage-class :host-transient :scope :frame :durability :none
         :teardown      (fn [& _] (reset! second-ran true))})
      (rf/reg-frame :ht/throw-frame {:doc "throwing teardown frame"})
      ;; destroy-frame! must NOT propagate the teardown throw...
      (frame/destroy-frame! :ht/throw-frame)
      ;; ...the frame is torn down...
      (is (nil? (frame/frame :ht/throw-frame))
          "destroy-frame! completed despite a throwing host-transient teardown")
      ;; ...and the OTHER descriptor's teardown still ran (one bad teardown does
      ;; not block the rest).
      (is (true? @second-ran)
          "a sibling host-transient teardown still ran after the throwing one"))
    (realm/clear-host-transient!)))

;; ---------------------------------------------------------------------------
;; (6) FAIL-CLOSED realm-owned mutation — an unknown id cannot silently mutate
;;     (rf2-c6armm.6 #2)
;; ---------------------------------------------------------------------------
;;
;; A realm is an isolation boundary, so a realm-owned mutation aimed at a realm
;; that was never constructed must NOT silently no-op (the prior `update-realm!`
;; posture) — a typo'd id would drop the write while the caller believes it
;; seated state, and the symmetrical hazard the install! guard (rf2-c6armm.1/.2)
;; closed for the install path was still open on the non-install mutation seams
;; (`set-installed-app!` / `install-realm-adapter!` / `register-host-transient!`
;; / …, all routed through the single `update-realm!` seam). These pin that the
;; seam now THROWS `:rf.error/unknown-realm` for a non-nil unknown id, BEFORE any
;; mutation, while the default realm (absence resolves to it) is untouched.

(deftest mutating-an-unknown-realm-id-throws-before-any-mutation
  (testing "rf2-c6armm.6 #2: a realm-owned mutation targeting an EXPLICIT,
            never-constructed realm id THROWS :rf.error/unknown-realm and does
            NOT silently no-op (nor pollute the default realm) — fail-closed at
            the isolation boundary"
    ;; :ghost/realm was never constructed, so it has no registry entry.
    (is (nil? (realm/realm :ghost/realm)) "the realm id was never constructed")
    (let [;; set-installed-app! routes through update-realm! — the central seam.
          ed (try (realm/set-installed-app! :ghost/realm {:rf.app/id :ghost})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/unknown-realm (:rf.error/id ed))
          "mutating an unknown realm id throws :rf.error/unknown-realm")
      (is (= :ghost/realm (:realm ed)) "the diagnostic names the unknown realm id")
      (is (contains? (:known-realms ed) :rf.realm/default)
          "the diagnostic enumerates the live realm ids")
      (is (= :construct-the-realm-first (:recovery ed)))
      ;; Pre-mutation: NOTHING leaked. The ghost realm is still absent AND the
      ;; default realm did not silently acquire a phantom :app.
      (is (nil? (realm/realm :ghost/realm))
          "the unknown realm was not created by the failed mutation")
      (is (nil? (:app (realm/default-realm)))
          "the default realm acquired no phantom :app — no silent fallback"))))

(deftest unknown-realm-throw-covers-every-realm-owned-mutation-seam
  (testing "rf2-c6armm.6 #2: the fail-closed guard sits at the single
            update-realm! seam, so EVERY realm-owned mutator (adapter install,
            host-transient register) throws on an unknown id — not just
            set-installed-app!"
    (doseq [[label thunk]
            [[:install-realm-adapter!
              #(realm/install-realm-adapter! :ghost/realm {:id :x})]
             [:register-host-transient!
              #(realm/register-host-transient!
                 :ghost/realm
                 {:id :rf.test/x :storage-class :host-transient
                  :scope :frame :durability :none})]]]
      (let [ed (try (thunk)
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                      (ex-data e)))]
        (is (= :rf.error/unknown-realm (:rf.error/id ed))
            (str label " throws :rf.error/unknown-realm on an unknown id"))
        (is (= :ghost/realm (:realm ed))
            (str label " names the unknown realm id"))))
    (is (nil? (realm/realm :ghost/realm))
        "no failed mutation resurrected the ghost realm")))

(deftest realm-owned-mutation-still-works-for-known-realms
  (testing "rf2-c6armm.6 #2: the guard is narrow — the DEFAULT realm (absence
            resolves to it) and a CONSTRUCTED realm both still mutate normally,
            so the fix is fail-closed-on-unknown, not fail-closed-on-everything"
    ;; (a) the default realm — the byte-identical absence-is-default path.
    (realm/register-host-transient!
      {:id :rf.test/known :storage-class :host-transient
       :scope :frame :durability :none})
    (is (some? (realm/host-transient :rf.realm/default :rf.test/known))
        "a default-realm mutation still seats (absence resolves to the default)")
    (realm/clear-host-transient!)
    ;; (b) an explicitly constructed realm.
    (let [r (realm/construct-realm {:id :known/realm})]
      (try
        (realm/set-installed-app! :known/realm {:rf.app/id :seated})
        (is (= {:rf.app/id :seated} (:app (realm/realm :known/realm)))
            "a constructed realm's :app mutates normally — only UNKNOWN ids throw")
        (finally (realm/dispose-realm! :known/realm))))))

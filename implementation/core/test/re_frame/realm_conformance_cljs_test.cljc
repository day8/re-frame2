(ns re-frame.realm-conformance-cljs-test
  "EP-0013 D1 stage 9 (rf2-swrf4k) — the PUBLIC `realm/construct-realm` constructor +
  MULTI-REALM / MULTI-ADAPTER-ROOT conformance (the LAST EP-0013 impl slice).

  Stage 9 graduates the reserved `realm/construct-realm` vocabulary (EP-0013 issue 1; ruled
  `realm/construct-realm`, NEVER `rf/runtime`) into a public BUILD-AND-REGISTER constructor:
  a caller stands up an explicit realm to `install!` an app value into and
  target the stage-8 `{:realm …}` queries against. A constructed realm is

    (a) HERMETIC by default — its OWN `(kind, id) → metadata` registrar atom, so
        an installed app lives in its own table, isolated from every other
        realm; and
    (b) REGISTERED in the process realm registry under its `:id`, so it resolves
        by id keyword through the realm-targeted query surface.

  The conformance battery proves N independent realms ISOLATE — the EP-0013
  §Realm Conformance acceptance, the headline being \"two realms can install
  different handlers for the same event id without collision\" and
  \"realm-targeted registrar queries return only that realm's registrations\".
  The DEFAULT realm stays implicit + byte-identical (a single-realm app never
  spells a realm — the absence-is-default rule).

  These tests pin:
    (1) the constructor — id required, hermetic own-registrar by default, the
        realm joins the registry, duplicate id throws, dispose-realm! drops it
        (and the default realm is never disposed);
    (2) ISOLATION — install! into a constructed realm seats ONLY into that
        realm's registrar (the default realm + sibling realms see nothing);
    (3) SAME ID, INDEPENDENT — the same event/sub id installed into two realms
        carries genuinely different handlers, resolved per-realm by the
        realm-targeted query surface (no cross-realm bleed);
    (4) the QUERY surface across the matrix — `{:realm r :kind k}`
        registrations / handler-meta / handler-ids return ONLY realm r's
        registrations for every realm in an N-realm matrix;
    (5) MULTI-ADAPTER-ROOT — each realm carries its own `:adapter` SELECTION, so
        N realms run N independent substrate roots without bleed;
    (6) HERMETIC TEST without clearing the process-global registrar — installing
        into a fresh realm leaves the default realm's program untouched;
    (7) CAPABILITY check on a constructed realm — install! fails loud on an unmet
        requirement before any mutation to the realm's own registrar;
    (8) the DEFAULT realm is byte-identical — install!/reg-* against the default
        realm behave exactly as before (no regression from the realm-scoped
        seating seam);
    (9) NON-DEFAULT realm live behavior pinned to the SHIPPED contract
        (rf2-c6armm.3 #1) — a constructed realm is QUERY-isolated but not yet a
        live-dispatch target: a :frame into a non-default realm is REFUSED (not
        silently default-seated), and a constructed realm's handlers do not leak
        into the default realm's live-dispatch resolution path;
   (10) reinstall! APPLY-PHASE rollback into a constructed realm (rf2-c6armm.3
        #2) — a throw during the reinstall! apply loop rolls the realm's
        registrar back to its pre-reinstall state and records no new :app.

  Realm conformance is entirely within `re-frame.core` (realm + app-value +
  registrar are all core artefacts), so it lives in the core test tree rather
  than a new cross-cutting conformance dir — no shadow-cljs.edn wiring needed.

  Dual-runtime `*_cljs_test.cljc` — the shadow-cljs `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Pure CLJC, no DOM."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.app-value :as av]
            [re-frame.realm :as realm]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; The realm registry (`realm/realms`) is process-global. The runtime-reset
;; fixture snapshot/restores the DEFAULT realm's registrar (the process-global
;; atom) but does NOT touch the registry's other entries or the default realm's
;; `:app` slot. Each test that constructs a realm disposes it on the way out;
;; this fixture also drops any leftover non-default realms + clears the default
;; realm's `:app` so a constructed realm or a stored install! value never leaks
;; between cases.
(defn- drop-non-default-realms! []
  (swap! realm/realms select-keys [realm/default-realm-id])
  (swap! realm/realms update realm/default-realm-id dissoc :app :capabilities))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [test-fn]
    (drop-non-default-realms!)
    (test-fn)
    (drop-non-default-realms!)))

;; Handler stand-ins — value identity is what install! seats + the per-realm
;; query surface resolves.
(defn- add-a    [{:keys [db]} _] {:db (assoc db :who :a)})
(defn- add-b    [{:keys [db]} _] {:db (assoc db :who :b)})
(defn- read-who [db _] (:who db))

;; A second, synthetic adapter SELECTION — value identity is all the
;; multi-adapter-root test needs (it proves per-realm adapter OWNERSHIP, not a
;; live second substrate, which is the future frame→realm dispatch slice).
(def ^:private fake-adapter
  {:kind :rf.adapter/conformance-fake})

;; ---------------------------------------------------------------------------
;; (1) the constructor — id required, hermetic, registered, unique, disposable
;; ---------------------------------------------------------------------------

(deftest realm-requires-an-id
  (testing "realm/construct-realm throws :rf.error/invalid-realm when :id is missing"
    (let [ed (try (realm/construct-realm {})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/invalid-realm (:rf.error/id ed)))
      (is (= :supply-a-realm-id (:recovery ed))))))

(deftest realm-is-hermetic-and-registered
  (testing "a constructed realm gets its OWN fresh registrar atom (not the
            process-global one) and joins the process realm registry under :id"
    (let [r (realm/construct-realm {:id :conf/r1})]
      (is (= :conf/r1 (:rf.realm/id r)) "the realm carries its id")
      (is (= r (realm/realm :conf/r1)) "it resolves by id through the registry")
      ;; Its registrar is its OWN atom, not the process-global default.
      (is (not (identical? (realm/registrar r) registrar/kind->id->metadata))
          "a constructed realm's registrar is NOT the process-global atom")
      (is (= {} @(realm/registrar r)) "the own registrar starts empty (hermetic)")
      ;; The default realm's registrar IS the process-global atom (unchanged).
      (is (identical? (realm/registrar (realm/default-realm))
                      registrar/kind->id->metadata)
          "the default realm still owns the process-global registrar"))))

(deftest realm-id-must-be-unique
  (testing "constructing a realm whose id is already registered throws
            :rf.error/realm-id-conflict (no silent clobber of a live realm)"
    (realm/construct-realm {:id :conf/dup})
    (let [ed (try (realm/construct-realm {:id :conf/dup})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/realm-id-conflict (:rf.error/id ed)))
      (is (= :conf/dup (:realm ed)) "the conflicting id is named"))))

(deftest dispose-realm-drops-it-default-is-never-disposed
  (testing "dispose-realm! drops a constructed realm from the registry; the
            default realm is never disposed (a no-op)"
    (realm/construct-realm {:id :conf/temp})
    (is (some? (realm/realm :conf/temp)))
    (realm/dispose-realm! :conf/temp)
    (is (nil? (realm/realm :conf/temp)) "the constructed realm is gone")
    ;; Disposing the default realm is a no-op — it backs the single-realm path.
    (realm/dispose-realm! realm/default-realm-id)
    (is (some? (realm/default-realm)) "the default realm survives a dispose call")))

;; ---------------------------------------------------------------------------
;; the PUBLIC realm-enumeration surface (rf2-f1xa3k) — the (realm, frame)
;; address read halves: realm/realm-ids enumerates the installed realms,
;; frame/frame-realm reads the realm a frame lives in.
;; ---------------------------------------------------------------------------

(deftest realm-ids-enumerates-the-installed-realms
  (testing "realm/realm-ids returns the set of installed realm ids — always
            including the default realm; constructed realms join, disposed
            realms drop out (the live enumeration)"
    ;; A single-realm app sees exactly the default realm.
    (is (= #{realm/default-realm-id} (realm/realm-ids))
        "with no constructed realms, realm-ids is exactly the default realm")
    (realm/construct-realm {:id :conf/e1})
    (realm/construct-realm {:id :conf/e2})
    (is (= #{realm/default-realm-id :conf/e1 :conf/e2} (realm/realm-ids))
        "constructed realms join the enumeration")
    (realm/dispose-realm! :conf/e1)
    (is (= #{realm/default-realm-id :conf/e2} (realm/realm-ids))
        "a disposed realm drops out of the enumeration")
    ;; The default realm is never disposed, so it never leaves the set.
    (realm/dispose-realm! realm/default-realm-id)
    (is (contains? (realm/realm-ids) realm/default-realm-id)
        "the default realm is always present (never disposed)")))

(deftest frame-realm-reads-a-frames-realm
  (testing "frame/frame-realm reads the realm a frame belongs to — the default
            realm for a frame registered with no explicit realm, nil for an
            unknown frame"
    (rf/reg-frame :conf/frame-a {:doc "a frame"})
    (is (= realm/default-realm-id (frame/frame-realm :conf/frame-a))
        "a frame with no explicit realm lives in the default realm")
    (is (nil? (frame/frame-realm :conf/never-registered))
        "an unknown frame has no realm")
    (rf/destroy-frame! :conf/frame-a)))

(deftest realm-ids-and-frame-realm-form-the-address
  (testing "the two read halves compose into the (realm, frame) address —
            every frame's realm is an installed realm id"
    (rf/reg-frame :conf/addr-frame {:doc "addr frame"})
    (let [rid (frame/frame-realm :conf/addr-frame)]
      (is (contains? (realm/realm-ids) rid)
          "a frame's realm is always one of the installed realm ids"))
    (rf/destroy-frame! :conf/addr-frame)))

(deftest realm-carries-its-capabilities
  (testing "a constructed realm carries the :capabilities map it was built with"
    (let [r (realm/construct-realm {:id :conf/caps
                       :capabilities {:rf.capability/http {:request! identity}}})]
      (is (contains? (:capabilities r) :rf.capability/http)
          "the capability map rides the realm"))))

(deftest dispose-realm-walks-adapter-and-host-transient-teardown
  (testing "dispose-realm! TEARS DOWN the realm's operational resources — it
            runs the seated adapter's own :dispose-adapter! fn + clears the
            adapter slot, walks the host-transient inventory running each
            descriptor's :teardown token + drops the inventory, THEN drops the
            registry entry. A bare dissoc would orphan both (rf2-kq0yfb)."
    (let [adapter-disposed?   (atom false)
          torn-down-subsystems (atom #{})
          ;; An adapter SELECTION carrying its own teardown fn, exactly the
          ;; shape substrate.adapter/dispose-adapter! runs for the default realm.
          adapter {:kind            :rf.adapter/conformance-teardown
                   :dispose-adapter! (fn [] (reset! adapter-disposed? true))}
          r       (realm/construct-realm {:id :conf/teardown :adapter adapter})]
      ;; Seat a host-transient inventory of two subsystems, each with a
      ;; :teardown token the realm-dispose walk must run (one :frame-scoped,
      ;; one :realm-scoped) — Spec-Schemas §:rf/host-transient-descriptor.
      (realm/register-host-transient! :conf/teardown
        {:id            :rf.test/in-flight
         :storage-class :host-transient :scope :frame :durability :none
         :teardown      (fn [_rid] (swap! torn-down-subsystems conj :rf.test/in-flight))})
      (realm/register-host-transient! :conf/teardown
        {:id            :rf.test/timers
         :storage-class :host-transient :scope :realm :durability :none
         :teardown      (fn [_rid] (swap! torn-down-subsystems conj :rf.test/timers))})
      ;; Pre-conditions: the realm owns a seated adapter + a 2-entry inventory.
      (is (= adapter (realm/realm-adapter :conf/teardown))
          "the adapter selection is seated on the realm before dispose")
      (is (= #{:rf.test/in-flight :rf.test/timers}
             (set (keys (realm/host-transient :conf/teardown))))
          "the host-transient inventory carries both subsystems before dispose")
      ;; Dispose: the teardown seams fire.
      (realm/dispose-realm! :conf/teardown)
      (is (true? @adapter-disposed?)
          "dispose-realm! ran the seated adapter's own :dispose-adapter! fn")
      (is (= #{:rf.test/in-flight :rf.test/timers} @torn-down-subsystems)
          "dispose-realm! ran every host-transient descriptor's :teardown token")
      ;; Post-conditions: nothing orphaned — the realm is gone from the registry,
      ;; so its adapter slot + host-transient inventory are unreachable.
      (is (nil? (realm/realm :conf/teardown))
          "the realm is dropped from the registry after teardown")
      (is (nil? (realm/realm-adapter :conf/teardown))
          "the adapter slot is not reachable on a disposed realm (no orphan)")
      (is (nil? (realm/host-transient :conf/teardown))
          "the host-transient inventory is not reachable on a disposed realm"))))

(deftest dispose-realm-default-realm-teardown-is-a-no-op
  (testing "disposing the default realm never tears down its adapter or
            host-transient state — the default realm backs the byte-identical
            single-realm path and is never disposed (rf2-kq0yfb)"
    ;; The fixture seated plain-atom into the default realm; register a
    ;; host-transient descriptor with a :teardown that would fire if the
    ;; default-realm dispose were NOT a no-op.
    (let [default-torn-down? (atom false)]
      (realm/register-host-transient! realm/default-realm-id
        {:id            :rf.test/default-guard
         :storage-class :host-transient :scope :realm :durability :none
         :teardown      (fn [_rid] (reset! default-torn-down? true))})
      (realm/dispose-realm! realm/default-realm-id)
      (realm/dispose-realm! nil)
      (is (false? @default-torn-down?)
          "the default realm's host-transient teardown never ran (dispose is a no-op)")
      (is (some? (realm/realm-adapter realm/default-realm-id))
          "the default realm's seated adapter is untouched by a dispose call")
      (is (some? (realm/default-realm))
          "the default realm survives the dispose call")
      ;; Clean up the guard descriptor so it does not leak into a sibling test.
      (realm/clear-host-transient! realm/default-realm-id))))

;; ---------------------------------------------------------------------------
;; (2) ISOLATION — install! seats ONLY into the target realm's registrar
;; ---------------------------------------------------------------------------

(deftest install-into-a-realm-seats-only-that-realms-registrar
  (testing "install! into a constructed realm seats descriptors into that
            realm's OWN registrar — the default realm + sibling realms see none"
    (let [r1   (realm/construct-realm {:id :conf/r1})
          app  (av/app {:id :conf/app1 :modules
                        [(av/module {:id :m :events {:e/x {:handler add-a}}
                                     :subs {:s/y {:handler read-who}}})]})]
      (av/install! r1 app)
      ;; The realm's own registrar holds the program.
      (is (= add-a (get-in @(realm/registrar r1) [:event :e/x :handler-fn]))
          "the event handler is seated in the realm's own registrar")
      (is (= read-who (get-in @(realm/registrar r1) [:sub :s/y :handler-fn]))
          "the sub handler is seated in the realm's own registrar")
      ;; The PROCESS-GLOBAL (default realm) registrar saw NOTHING.
      (is (nil? (registrar/lookup :event :e/x))
          "the default realm's registrar did NOT receive the installed event")
      (is (nil? (registrar/lookup :sub :s/y))
          "the default realm's registrar did NOT receive the installed sub")
      ;; And the realm's installed-app value reports the seated provenance over
      ;; its own live projection (rf2-77ewnm) — the stored slot holds the value
      ;; verbatim; the public read reconciles it with the realm's registrar.
      (is (= app (:app (realm/realm (realm/realm-id r1))))
          "the realm stores the constructed app value it was installed with")
      (is (= :conf/app1 (:rf.app/id (realm/installed-app r1)))
          "installed-app reports the seated app's identity")
      (is (= #{:e/x} (set (keys (get-in (realm/installed-app r1) [:registrations :event]))))
          "and its reconciled read enumerates exactly the realm's own program"))))

;; ---------------------------------------------------------------------------
;; (3) SAME ID, INDEPENDENT — two realms hold different handlers for one id
;; ---------------------------------------------------------------------------

(deftest two-realms-same-id-stay-independent
  (testing "the SAME event/sub id installed into two realms carries genuinely
            different handlers, resolved per-realm — the EP-0013 §Realm
            Conformance headline (no collision, no cross-realm bleed)"
    (let [ra (realm/construct-realm {:id :conf/a})
          rb (realm/construct-realm {:id :conf/b})
          app-of (fn [h] (av/app {:id :conf/shared :modules
                                  [(av/module {:id :m
                                               :events {:shared/e {:handler h}}})]}))]
      (av/install! ra (app-of add-a))
      (av/install! rb (app-of add-b))
      ;; Each realm resolves ITS OWN handler for the shared id. The facade no
      ;; longer exposes a realm-targeted query (rf2-10nggz); realm isolation is
      ;; read through the internal substrate `re-frame.realm/realm-handler-meta`.
      (is (= add-a (:handler-fn (realm/realm-handler-meta :conf/a :event :shared/e)))
          "realm a holds add-a for :shared/e")
      (is (= add-b (:handler-fn (realm/realm-handler-meta :conf/b :event :shared/e)))
          "realm b holds add-b for :shared/e")
      (is (not= (realm/realm-handler-meta :conf/a :event :shared/e)
                (realm/realm-handler-meta :conf/b :event :shared/e))
          "the two realms hold genuinely different handlers for the same id")
      ;; The default realm never saw :shared/e at all.
      (is (nil? (rf/handler-meta :event :shared/e))
          "the shared id is absent from the default realm (no global bleed)"))))

;; ---------------------------------------------------------------------------
;; (4) the QUERY surface across an N-realm matrix
;; ---------------------------------------------------------------------------

(deftest realm-targeted-queries-isolate-across-the-matrix
  (testing "across an N-realm matrix, the internal realm-scoped readers
            (`re-frame.realm/realm-registrations` / `realm-handler-ids`) return
            ONLY realm r's registrations — no realm sees another's ids. The
            facade no longer exposes a realm-targeted query (rf2-10nggz); realm
            isolation is a substrate property read through `re-frame.realm`."
    (let [r1 (realm/construct-realm {:id :conf/m1})
          r2 (realm/construct-realm {:id :conf/m2})
          r3 (realm/construct-realm {:id :conf/m3})]
      (av/install! r1 (av/app {:id :a1 :modules
                               [(av/module {:id :m :events {:only/e1 {:handler add-a}}})]}))
      (av/install! r2 (av/app {:id :a2 :modules
                               [(av/module {:id :m :events {:only/e2 {:handler add-a}}})]}))
      (av/install! r3 (av/app {:id :a3 :modules
                               [(av/module {:id :m :events {:only/e3 {:handler add-a}}})]}))
      ;; Each realm's id set is exactly its own one id.
      (is (= #{:only/e1} (realm/realm-handler-ids :conf/m1 :event)))
      (is (= #{:only/e2} (realm/realm-handler-ids :conf/m2 :event)))
      (is (= #{:only/e3} (realm/realm-handler-ids :conf/m3 :event)))
      ;; No realm's registrations contain a sibling's id.
      (is (not (contains? (realm/realm-registrations :conf/m1 :event) :only/e2)))
      (is (not (contains? (realm/realm-registrations :conf/m2 :event) :only/e3)))
      (is (not (contains? (realm/realm-registrations :conf/m3 :event) :only/e1)))
      ;; A sibling's id resolves to nil in another realm.
      (is (nil? (realm/realm-handler-meta :conf/m1 :event :only/e2)))
      ;; The realm map (not just the id keyword) reads the same realm.
      (is (= (realm/realm-handler-ids :conf/m1 :event)
             (realm/realm-handler-ids (realm/realm :conf/m1) :event))
          "the realm-map form reads the same realm as the id form"))))

;; ---------------------------------------------------------------------------
;; (5) MULTI-ADAPTER-ROOT — each realm carries its own adapter SELECTION
;; ---------------------------------------------------------------------------

(deftest each-realm-carries-its-own-adapter-selection
  (testing "N realms can each carry their OWN :adapter selection — the
            multi-adapter-root direction (a realm owns its adapter/root)"
    (let [r-plain (realm/construct-realm {:id :conf/plain :adapter plain-atom/adapter})
          r-fake  (realm/construct-realm {:id :conf/fake  :adapter fake-adapter})]
      (is (= plain-atom/adapter (realm/realm-adapter :conf/plain))
          "the plain realm carries the plain-atom adapter selection")
      (is (= fake-adapter (realm/realm-adapter :conf/fake))
          "the fake realm carries the fake adapter selection")
      (is (not= (realm/realm-adapter :conf/plain)
                (realm/realm-adapter :conf/fake))
          "the two realms hold independent adapter selections (no bleed)")
      ;; The default realm's adapter (installed by the fixture) is untouched.
      (is (= plain-atom/adapter (realm/realm-adapter realm/default-realm-id))
          "the default realm's adapter selection is unaffected by sibling realms"))))

;; ---------------------------------------------------------------------------
;; (6) HERMETIC TEST — install into a fresh realm without clearing the global
;; ---------------------------------------------------------------------------

(deftest hermetic-install-leaves-the-default-realm-untouched
  (testing "installing an app into a fresh realm does NOT touch the
            process-global registrar — the EP-0013 hermetic-test acceptance
            (install exactly the program you need without clearing a global)"
    ;; Seed the default realm via the ordinary sugar path.
    (rf/reg-event :default/seed {:doc "seed"} add-a)
    (let [before (registrar/registrations :event)
          r      (realm/construct-realm {:id :conf/hermetic})]
      (av/install! r (av/app {:id :h :modules
                              [(av/module {:id :m :events {:hermetic/e {:handler add-b}}})]}))
      ;; The default realm's event registrations are byte-identical — the
      ;; hermetic install wrote only the constructed realm's own registrar.
      (is (= before (registrar/registrations :event))
          "the default realm's registrar is unchanged by the hermetic install")
      (is (contains? (realm/realm-registrations :conf/hermetic :event) :hermetic/e)
          "the hermetic realm holds its own installed event"))))

;; ---------------------------------------------------------------------------
;; (7) CAPABILITY check on a constructed realm
;; ---------------------------------------------------------------------------

(deftest install-capability-check-on-a-constructed-realm
  (testing "install! into a constructed realm capability-checks FIRST — an unmet
            requirement throws before any mutation to the realm's own registrar"
    (let [r  (realm/construct-realm {:id :conf/needs-caps})
          a  (av/app {:id :c :modules
                      [(av/module {:id :m
                                   :rf.module/requires #{:rf.capability/http}
                                   :events {:c/e {:handler add-a}}})]})
          ed (try (av/install! r a)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/missing-capability (:rf.error/id ed)))
      (is (= :conf/needs-caps (:realm ed)) "the constructed realm is named")
      (is (= :rf.capability/http (:capability ed)))
      ;; Nothing was seated into the realm's own registrar (pre-mutation fail).
      (is (= {} @(realm/registrar r))
          "the realm's own registrar is empty — the failed install was atomic"))
    (testing "and it succeeds once the realm provides the capability"
      (let [r (realm/construct-realm {:id :conf/has-caps
                         :capabilities {:rf.capability/http {:request! identity}}})]
        (av/install! r (av/app {:id :c2 :modules
                                [(av/module {:id :m
                                             :rf.module/requires #{:rf.capability/http}
                                             :events {:c/e {:handler add-a}}})]}))
        (is (= add-a (get-in @(realm/registrar r) [:event :c/e :handler-fn]))
            "the descriptor is seated once the capability is satisfied")))))

;; ---------------------------------------------------------------------------
;; (8) the DEFAULT realm is byte-identical — no regression
;; ---------------------------------------------------------------------------

(deftest default-realm-install-is-byte-identical
  (testing "install! against the default realm seats into the process-global
            registrar exactly as before (the realm-scoped seam is a no-op rebind
            to the same atom when the realm IS the default)"
    (av/install! (av/app {:id :d :modules
                          [(av/module {:id :m :events {:default/e {:handler add-a}}})]}))
    ;; The default-realm install resolves through the ordinary global lookup.
    (is (= add-a (registrar/handler :event :default/e))
        "the default-realm install seats the process-global registrar")
    (is (identical? (registrar/handler :event :default/e)
                    (get-in @registrar/kind->id->metadata [:event :default/e :handler-fn]))
        "it is the SAME process-global atom the sugar path writes")))

(deftest default-realm-sugar-and-dispatch-unaffected
  (testing "the ordinary reg-* sugar path + full dispatch through a default-realm
            frame still works end-to-end (no regression from the realm seam)"
    (rf/reg-frame :conf/app {:doc "conf app"})
    (rf/reg-event :conf/set {:doc "set"} (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-sub :conf/read {:doc "read"} (fn [db _] (:n db)))
    (rf/dispatch-sync [:conf/set 7] {:frame :conf/app})
    (is (= {:n 7} (rf/app-db-value :conf/app))
        "a sugar-path event dispatches through the default-realm frame")
    (is (= 7 @(rf/subscribe :conf/app [:conf/read]))
        "a sugar-path sub resolves through the default-realm frame")))

;; ---------------------------------------------------------------------------
;; reinstall! into a constructed realm hot-reloads only that realm
;; ---------------------------------------------------------------------------

(deftest reinstall-into-a-realm-touches-only-that-realm
  (testing "reinstall! diffs + applies the delta against the constructed realm's
            OWN registrar — the default realm and siblings are untouched"
    (let [r  (realm/construct-realm {:id :conf/reload})
          v1 (av/app {:id :rl :modules
                      [(av/module {:id :m :events {:rl/keep {:handler add-a}
                                                   :rl/drop {:handler add-a}}})]})
          v2 (av/app {:id :rl :modules
                      [(av/module {:id :m :events {:rl/keep {:handler add-b}
                                                   :rl/new  {:handler add-b}}})]})]
      (av/install! r v1)
      (let [diff (av/reinstall! r v2 {:reason :hot-reload})]
        (is (= :conf/reload (:realm diff)) "the diff names the constructed realm")
        (is (= [[:event :rl/new]] (:added diff)))
        (is (= [[:event :rl/keep]] (:changed diff)))
        (is (= [[:event :rl/drop]] (:removed diff)))
        ;; The realm's own registrar reflects the delta.
        (is (= add-b (get-in @(realm/registrar r) [:event :rl/keep :handler-fn]))
            ":changed re-registered the new handler in the realm's own registrar")
        (is (= add-b (get-in @(realm/registrar r) [:event :rl/new :handler-fn]))
            ":added seated the new id")
        (is (nil? (get-in @(realm/registrar r) [:event :rl/drop]))
            ":removed unregistered the dropped id in the realm's own registrar")
        ;; The default realm never saw any of these ids.
        (is (nil? (registrar/lookup :event :rl/keep))
            "the reinstall touched only the constructed realm's registrar")))))

;; ---------------------------------------------------------------------------
;; (9) NON-DEFAULT REALM live behavior — REALM-ROUTED dispatch/subscribe
;;     (EP-0013 staging step 4, rf2-a15n62 — the refusal is LIFTED)
;; ---------------------------------------------------------------------------
;;
;; The realm-aware frame / live-dispatch slice has SHIPPED: `reg-frame` STAMPS +
;; keys frames by their owning realm, and the router/subs/fx/cofx resolve
;; handlers through the OWNING frame's realm registrar (the `*registrar*` +
;; `*current-realm*` bindings now cover live dispatch, not just install seating).
;; So a constructed realm is a full live-dispatch target: the same frame id is
;; legal in two realms, and dispatch/subscribe against a realm's frame run that
;; realm's program. These tests are the live-routing assertions the bead sketched
;; (the prior `:rf.error/realm-frames-unsupported` refusal is gone):
;;   * a :frame into two realms creates two REAL, isolated frames (no global
;;     collision, no silent default-seating);
;;   * dispatch into a realm's frame runs THAT realm's handler; subs resolve THAT
;;     realm's sub;
;;   * a non-default realm's handlers still do NOT leak into the DEFAULT realm's
;;     live dispatch (the isolation half is preserved).

(deftest two-realms-same-frame-id-live-dispatch-and-subscribe-isolate
  (testing "EP-0013 step 4 (rf2-a15n62): the same frame id installed into two
            realms with realm-owned :frame descriptors creates two isolated live
            frames — frame-realm matches the owning realm, dispatch runs that
            realm's handler, and subs resolve that realm's sub"
    (let [ra (realm/construct-realm {:id :live/a})
          rb (realm/construct-realm {:id :live/b})
          app-for (fn [tag]
                    (av/app {:id :live/app :modules
                             [(av/module
                                {:id :m
                                 :frames {:live/f {:doc "shared id"}}
                                 :events {:live/set {:handler (fn [{:keys [db]} _] {:db (assoc db :who tag)})}}
                                 :subs   {:live/who {:handler (fn [db _] [tag (:who db)])}}})]}))]
      (try
        (av/install! ra (app-for :a))
        (av/install! rb (app-for :b))
        ;; The same frame id is a REAL frame in each realm — owned by that realm.
        ;; A bare `frame-realm` read (no realm scope) finds NO default-realm
        ;; :live/f, so it returns nil — the id is unambiguous only WITHIN a realm.
        (is (nil? (frame/frame-realm :live/f))
            "no default-realm :live/f exists, so the bare read is nil (id unique within a realm)")
        ;; frame-realm with the realm scope reads the realm's own frame.
        (is (= :live/a (frame/call-with-realm :live/a (fn [] (frame/frame-realm :live/f))))
            "realm a owns :live/f")
        (is (= :live/b (frame/call-with-realm :live/b (fn [] (frame/frame-realm :live/f))))
            "realm b owns :live/f")
        ;; DISPATCH the SAME event id into each realm's frame: each runs ITS
        ;; realm's handler (realm-routed event resolution).
        (rf/dispatch-sync [:live/set] {:realm :live/a :frame :live/f})
        (rf/dispatch-sync [:live/set] {:realm :live/b :frame :live/f})
        (is (= :a (frame/call-with-realm :live/a (fn [] (:who (frame/frame-app-db-value :live/f)))))
            "realm a's frame ran realm a's :live/set handler")
        (is (= :b (frame/call-with-realm :live/b (fn [] (:who (frame/frame-app-db-value :live/f)))))
            "realm b's frame ran realm b's :live/set handler")
        ;; SUBSCRIBE the SAME sub id against each realm's frame: each resolves
        ;; ITS realm's sub body (realm-routed subscription resolution).
        (is (= [:a :a] (frame/call-with-realm :live/a (fn [] (rf/subscribe-once :live/f [:live/who]))))
            "realm a's frame resolves realm a's :live/who sub")
        (is (= [:b :b] (frame/call-with-realm :live/b (fn [] (rf/subscribe-once :live/f [:live/who]))))
            "realm b's frame resolves realm b's :live/who sub")
        ;; Both realms own a frame by the same id; the default realm owns none.
        (is (contains? (realm/realm-frames :live/a) :live/f) "realm a owns :live/f")
        (is (contains? (realm/realm-frames :live/b) :live/f) "realm b owns :live/f")
        (is (not (contains? (realm/realm-frames realm/default-realm-id) :live/f))
            "the default realm owns no :live/f frame (no global collision)")
        (finally
          (realm/dispose-realm! :live/a)
          (realm/dispose-realm! :live/b))))))

;; ---------------------------------------------------------------------------
;; (9b) dispose-realm! ends the LIFECYCLE of the frames the realm OWNS
;;      (EP-0013 realm-lifecycle correctness, rf2-yueuvi)
;; ---------------------------------------------------------------------------
;;
;; A realm OWNS the frame registry + their lifecycle/disposal state
;; (Runtime-Subsystems §What a realm owns). Before rf2-yueuvi, `dispose-realm!`
;; tore down the realm's adapter + host-transient state and dropped the realm
;; entry, but left every owned frame RECORD alive in `frame/frames`: a
;; post-dispose `(frame [realm-id frame-id])` (and `realm/realm-frames`) still
;; resolved the stale frame, and recreating the same realm id + the same frame
;; id hit `reg-frame`'s re-registration branch and PRESERVED the disposed
;; realm's app-db / sub-cache / router runtime state. These pin the fix: an
;; owned frame is unaddressable after disposal, a SIBLING realm's same-id frame
;; is untouched, and a same-id realm+frame recreation starts fresh.

(deftest dispose-realm-removes-owned-frames-from-addressability
  (testing "rf2-yueuvi: dispose-realm! destroys the frames the realm OWNS, so
            post-dispose neither (frame realm-id id) nor realm/realm-frames
            exposes them — while a SIBLING realm's same-id frame is untouched"
    (let [ra (realm/construct-realm {:id :disp/a})
          rb (realm/construct-realm {:id :disp/b})
          app-for (fn [tag]
                    (av/app {:id :disp/app :modules
                             [(av/module
                                {:id :m
                                 :frames {:disp/f {:doc "shared id"}}
                                 :events {:disp/set {:handler (fn [{:keys [db]} _] {:db (assoc db :who tag)})}}})]}))]
      (try
        (av/install! ra (app-for :a))
        (av/install! rb (app-for :b))
        ;; Both realms own a live :disp/f frame, addressable by their address.
        (is (some? (frame/frame :disp/a :disp/f)) "realm a's frame is live pre-dispose")
        (is (some? (frame/frame :disp/b :disp/f)) "realm b's frame is live pre-dispose")
        (is (contains? (realm/realm-frames :disp/a) :disp/f) "realm a owns :disp/f")
        (is (contains? (realm/realm-frames :disp/b) :disp/f) "realm b owns :disp/f")
        ;; Dispose realm a ONLY.
        (realm/dispose-realm! :disp/a)
        ;; realm a's frame is no longer addressable — fail-closed, not a stale
        ;; resolve of a dead frame.
        (is (nil? (frame/frame :disp/a :disp/f))
            "after dispose-realm! :disp/a, (frame :disp/a :disp/f) is nil")
        (is (not (contains? (realm/realm-frames :disp/a) :disp/f))
            "realm a's owned frame is gone from realm/realm-frames")
        (is (= #{} (realm/realm-frames :disp/a))
            "realm a owns no frames after disposal")
        ;; The SIBLING realm b's same-id frame is untouched — still live + owned.
        (is (some? (frame/frame :disp/b :disp/f))
            "sibling realm b's :disp/f frame survives realm a's disposal")
        (is (contains? (realm/realm-frames :disp/b) :disp/f)
            "realm b still owns :disp/f")
        (finally
          (realm/dispose-realm! :disp/a)
          (realm/dispose-realm! :disp/b))))))

(deftest recreating-disposed-realm-and-frame-starts-fresh
  (testing "rf2-yueuvi: after dispose-realm!, recreating the same realm id and
            installing the same frame id creates a FRESH frame with no preserved
            app-db state from the disposed realm (not a re-registration that
            keeps the dead realm's runtime state)"
    (let [app-with (fn [seed]
                     (av/app {:id :recr/app :modules
                              [(av/module
                                 {:id :m
                                  :frames {:recr/f {:doc "x"}}
                                  :events {:recr/seed {:handler (fn [{:keys [db]} _]
                                                                  {:db (assoc db :seed seed)})}}})]}))]
      (try
        ;; First incarnation: install, then write state into the frame's app-db.
        (let [r1 (realm/construct-realm {:id :recr/r})]
          (av/install! r1 (app-with :first))
          (rf/dispatch-sync [:recr/seed] {:realm :recr/r :frame :recr/f})
          (is (= :first (frame/call-with-realm :recr/r
                          (fn [] (:seed (frame/frame-app-db-value :recr/f)))))
              "the first incarnation's handler wrote :seed :first"))
        ;; Dispose the realm — this MUST end the frame's lifecycle.
        (realm/dispose-realm! :recr/r)
        (is (nil? (frame/frame :recr/r :recr/f))
            "the disposed realm's frame is unaddressable")
        ;; Recreate the SAME realm id + install the SAME frame id WITHOUT
        ;; re-running the seed event: a fresh frame must start with EMPTY app-db,
        ;; NOT the disposed realm's preserved {:seed :first}.
        (let [r2 (realm/construct-realm {:id :recr/r})]
          (av/install! r2 (app-with :second))
          (is (nil? (frame/call-with-realm :recr/r
                      (fn [] (:seed (frame/frame-app-db-value :recr/f)))))
              "the recreated frame starts FRESH — no preserved :seed from the
               disposed realm (a re-registration would have kept :first)"))
        (finally
          (realm/dispose-realm! :recr/r))))))

(deftest child-dispatch-and-fx-preserve-the-realm
  (testing "EP-0013 step 4 (rf2-a15n62): a child dispatch emitted from a handler's
            :fx [[:dispatch …]] STAYS in the parent's realm — the child resolves
            its event handler in the SAME realm, not the default (realm preserved
            across the cascade continuation)"
    (let [r (realm/construct-realm {:id :child/r})]
      (try
        (av/install! r
          (av/app {:id :child/app :modules
                   [(av/module
                      {:id :m
                       :frames {:child/f {:doc "x"}}
                       ;; :parent emits a child :dispatch (NO explicit realm) — it
                       ;; must inherit the parent's realm so :child resolves HERE.
                       :events {:parent {:handler (fn [_ _] {:fx [[:dispatch [:child]]]})}
                                :child  {:handler (fn [{:keys [db]} _] {:db (assoc db :child-ran :child/r)})}}})]}))
        (rf/dispatch-sync [:parent] {:realm :child/r :frame :child/f})
        ;; The child event ran IN the constructed realm (its handler wrote app-db).
        (is (= :child/r (frame/call-with-realm :child/r
                          (fn [] (:child-ran (frame/frame-app-db-value :child/f)))))
            "the :fx-dispatched child stayed in the parent's realm and ran its handler")
        (finally (realm/dispose-realm! :child/r))))))

(deftest non-default-realm-handlers-do-not-leak-into-default-live-dispatch
  (testing "rf2-c6armm.3 #1: a handler installed ONLY into a constructed realm is
            NOT resolvable through the default realm's live dispatch path — the
            isolation half that holds today (a default-realm frame dispatching
            the id is recovered as a no-op, not silently running the constructed
            realm's handler)"
    (let [r (realm/construct-realm {:id :leak/r})]
      (try
        (av/install! r (av/app {:id :leak/app :modules
                                [(av/module {:id :m
                                             :events {:leak/only-in-r {:handler add-a}}})]}))
        ;; The handler lives in the constructed realm's own registrar (read
        ;; through the internal substrate; the facade :realm query is gone).
        (is (= add-a (:handler-fn (realm/realm-handler-meta :leak/r :event :leak/only-in-r)))
            "the handler is in the constructed realm's registrar")
        ;; But the DEFAULT realm's registrar (the live-dispatch resolution path)
        ;; does NOT see it — no cross-realm leak into live behavior.
        (is (nil? (registrar/lookup :event :leak/only-in-r))
            "the constructed realm's handler does not leak into the default registrar")
        (is (nil? (rf/handler-meta :event :leak/only-in-r))
            "the default-realm query also does not see it (live dispatch resolves here)")
        (finally (realm/dispose-realm! :leak/r))))))

;; ---------------------------------------------------------------------------
;; (10) reinstall! APPLY-PHASE rollback into a constructed realm (rf2-c6armm.3 #2)
;; ---------------------------------------------------------------------------

(deftest reinstall-apply-phase-throw-rolls-back-the-realm-registrar
  (testing "rf2-c6armm.3 #2: a throw DURING the reinstall! apply loop (after one
            real register!/unregister! mutation landed, before set-installed-app!)
            rolls the constructed realm's registrar back to its pre-reinstall
            state — the seat-into-realm! snapshot/restore covers reinstall too,
            not just install. Old slots, removed slots, and installed-app all
            stay unchanged"
    (let [r  (realm/construct-realm {:id :rb/r})
          v1 (av/app {:id :rb/app :modules
                      [(av/module {:id :m :events {:rb/keep {:handler add-a}
                                                   :rb/drop {:handler add-a}}})]})
          v2 (av/app {:id :rb/app :modules
                      [(av/module {:id :m :events {:rb/keep {:handler add-b}
                                                   :rb/new  {:handler add-b}}})]})]
      (try
        (av/install! r v1)
        (let [reg-before @(realm/registrar r)
              calls (atom 0)
              real-register! registrar/register!
              ;; Force a throw on the FIRST register! the apply loop issues, AFTER
              ;; it has run for real once — so at least one mutation has landed in
              ;; the realm's registrar when the loop blows up (the partial-apply
              ;; edge the bead names).
              ed (with-redefs [registrar/register!
                               (fn [kind id metadata]
                                 (if (= 1 (swap! calls inc))
                                   (do (real-register! kind id metadata)
                                       (throw (ex-info "boom mid-apply"
                                                       {:error/id :rb.test/boom})))
                                   (real-register! kind id metadata)))]
                   (try (av/reinstall! r v2)
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                          (ex-data e))))]
          (is (= :rb.test/boom (:error/id ed)) "the mid-apply throw propagated")
          (is (>= @calls 1) "the apply loop issued at least one register!")
          ;; ROLLBACK: the realm's registrar is byte-identical to its pre-reinstall
          ;; state — the changed/added register! that landed AND any removed
          ;; unregister! are all reverted.
          (is (= reg-before @(realm/registrar r))
              "the realm's registrar is rolled back to its pre-reinstall state")
          (is (= add-a (get-in @(realm/registrar r) [:event :rb/keep :handler-fn]))
              ":rb/keep still holds the OLD handler (the changed register! was rolled back)")
          (is (some? (get-in @(realm/registrar r) [:event :rb/drop]))
              ":rb/drop was NOT unregistered (the removal was rolled back)")
          (is (nil? (get-in @(realm/registrar r) [:event :rb/new]))
              ":rb/new did not leak (the added register! was rolled back)")
          ;; ATOMIC :app: set-installed-app! runs only after a clean apply, so the
          ;; stored :app slot is still v1 (and the reconciled read reports it).
          (is (= v1 (:app (realm/realm (realm/realm-id r))))
              "the stored :app slot is still v1 — set-installed-app! never ran on the failed reinstall")
          (is (= #{:rb/keep :rb/drop}
                 (set (keys (get-in (realm/installed-app r) [:registrations :event]))))
              "installed-app reconciles to v1's program (no partial v2 :rb/new seating)"))
        (finally (realm/dispose-realm! :rb/r))))))

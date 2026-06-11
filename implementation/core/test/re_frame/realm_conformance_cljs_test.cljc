(ns re-frame.realm-conformance-cljs-test
  "EP-0013 D1 stage 9 (rf2-swrf4k) — the PUBLIC `rf/realm` constructor +
  MULTI-REALM / MULTI-ADAPTER-ROOT conformance (the LAST EP-0013 impl slice).

  Stage 9 graduates the reserved `rf/realm` vocabulary (EP-0013 issue 1; ruled
  `rf/realm`, NEVER `rf/runtime`) into a public BUILD-AND-REGISTER constructor:
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
        seating seam).

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
(defn- add-a    [db _] (assoc db :who :a))
(defn- add-b    [db _] (assoc db :who :b))
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
  (testing "rf/realm throws :rf.error/invalid-realm when :id is missing"
    (let [ed (try (rf/realm {})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/invalid-realm (:error/id ed)))
      (is (= :supply-a-realm-id (:recovery ed))))))

(deftest realm-is-hermetic-and-registered
  (testing "a constructed realm gets its OWN fresh registrar atom (not the
            process-global one) and joins the process realm registry under :id"
    (let [r (rf/realm {:id :conf/r1})]
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
    (rf/realm {:id :conf/dup})
    (let [ed (try (rf/realm {:id :conf/dup})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/realm-id-conflict (:error/id ed)))
      (is (= :conf/dup (:realm ed)) "the conflicting id is named"))))

(deftest dispose-realm-drops-it-default-is-never-disposed
  (testing "dispose-realm! drops a constructed realm from the registry; the
            default realm is never disposed (a no-op)"
    (rf/realm {:id :conf/temp})
    (is (some? (realm/realm :conf/temp)))
    (rf/dispose-realm! :conf/temp)
    (is (nil? (realm/realm :conf/temp)) "the constructed realm is gone")
    ;; Disposing the default realm is a no-op — it backs the single-realm path.
    (rf/dispose-realm! realm/default-realm-id)
    (is (some? (realm/default-realm)) "the default realm survives a dispose call")))

(deftest realm-carries-its-capabilities
  (testing "a constructed realm carries the :capabilities map it was built with"
    (let [r (rf/realm {:id :conf/caps
                       :capabilities {:rf.capability/http {:request! identity}}})]
      (is (contains? (:capabilities r) :rf.capability/http)
          "the capability map rides the realm"))))

;; ---------------------------------------------------------------------------
;; (2) ISOLATION — install! seats ONLY into the target realm's registrar
;; ---------------------------------------------------------------------------

(deftest install-into-a-realm-seats-only-that-realms-registrar
  (testing "install! into a constructed realm seats descriptors into that
            realm's OWN registrar — the default realm + sibling realms see none"
    (let [r1   (rf/realm {:id :conf/r1})
          app  (rf/app {:id :conf/app1 :modules
                        [(rf/module {:id :m :events {:e/x {:handler add-a}}
                                     :subs {:s/y {:handler read-who}}})]})]
      (rf/install! r1 app)
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
      ;; And the realm's installed-app value round-trips.
      (is (= app (realm/installed-app r1))
          "the realm stores the constructed app value it was installed with"))))

;; ---------------------------------------------------------------------------
;; (3) SAME ID, INDEPENDENT — two realms hold different handlers for one id
;; ---------------------------------------------------------------------------

(deftest two-realms-same-id-stay-independent
  (testing "the SAME event/sub id installed into two realms carries genuinely
            different handlers, resolved per-realm — the EP-0013 §Realm
            Conformance headline (no collision, no cross-realm bleed)"
    (let [ra (rf/realm {:id :conf/a})
          rb (rf/realm {:id :conf/b})
          app-of (fn [h] (rf/app {:id :conf/shared :modules
                                  [(rf/module {:id :m
                                               :events {:shared/e {:handler h}}})]}))]
      (rf/install! ra (app-of add-a))
      (rf/install! rb (app-of add-b))
      ;; Each realm resolves ITS OWN handler for the shared id.
      (is (= add-a (:handler-fn (rf/handler-meta {:realm :conf/a :kind :event :id :shared/e})))
          "realm a holds add-a for :shared/e")
      (is (= add-b (:handler-fn (rf/handler-meta {:realm :conf/b :kind :event :id :shared/e})))
          "realm b holds add-b for :shared/e")
      (is (not= (rf/handler-meta {:realm :conf/a :kind :event :id :shared/e})
                (rf/handler-meta {:realm :conf/b :kind :event :id :shared/e}))
          "the two realms hold genuinely different handlers for the same id")
      ;; The default realm never saw :shared/e at all.
      (is (nil? (rf/handler-meta :event :shared/e))
          "the shared id is absent from the default realm (no global bleed)"))))

;; ---------------------------------------------------------------------------
;; (4) the QUERY surface across an N-realm matrix
;; ---------------------------------------------------------------------------

(deftest realm-targeted-queries-isolate-across-the-matrix
  (testing "across an N-realm matrix, {:realm r …} registrations / handler-ids
            return ONLY realm r's registrations — no realm sees another's ids"
    (let [r1 (rf/realm {:id :conf/m1})
          r2 (rf/realm {:id :conf/m2})
          r3 (rf/realm {:id :conf/m3})]
      (rf/install! r1 (rf/app {:id :a1 :modules
                               [(rf/module {:id :m :events {:only/e1 {:handler add-a}}})]}))
      (rf/install! r2 (rf/app {:id :a2 :modules
                               [(rf/module {:id :m :events {:only/e2 {:handler add-a}}})]}))
      (rf/install! r3 (rf/app {:id :a3 :modules
                               [(rf/module {:id :m :events {:only/e3 {:handler add-a}}})]}))
      ;; Each realm's id set is exactly its own one id.
      (is (= #{:only/e1} (rf/handler-ids {:realm :conf/m1 :kind :event})))
      (is (= #{:only/e2} (rf/handler-ids {:realm :conf/m2 :kind :event})))
      (is (= #{:only/e3} (rf/handler-ids {:realm :conf/m3 :kind :event})))
      ;; No realm's registrations contain a sibling's id.
      (is (not (contains? (rf/registrations {:realm :conf/m1 :kind :event}) :only/e2)))
      (is (not (contains? (rf/registrations {:realm :conf/m2 :kind :event}) :only/e3)))
      (is (not (contains? (rf/registrations {:realm :conf/m3 :kind :event}) :only/e1)))
      ;; A sibling's id resolves to nil in another realm.
      (is (nil? (rf/handler-meta {:realm :conf/m1 :kind :event :id :only/e2})))
      ;; The realm map (not just the id keyword) reads the same realm.
      (is (= (rf/handler-ids {:realm :conf/m1 :kind :event})
             (rf/handler-ids {:realm (realm/realm :conf/m1) :kind :event}))
          "the realm-map form reads the same realm as the id form"))))

;; ---------------------------------------------------------------------------
;; (5) MULTI-ADAPTER-ROOT — each realm carries its own adapter SELECTION
;; ---------------------------------------------------------------------------

(deftest each-realm-carries-its-own-adapter-selection
  (testing "N realms can each carry their OWN :adapter selection — the
            multi-adapter-root direction (a realm owns its adapter/root)"
    (let [r-plain (rf/realm {:id :conf/plain :adapter plain-atom/adapter})
          r-fake  (rf/realm {:id :conf/fake  :adapter fake-adapter})]
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
    (rf/reg-event-db :default/seed {:doc "seed"} add-a)
    (let [before (registrar/registrations :event)
          r      (rf/realm {:id :conf/hermetic})]
      (rf/install! r (rf/app {:id :h :modules
                              [(rf/module {:id :m :events {:hermetic/e {:handler add-b}}})]}))
      ;; The default realm's event registrations are byte-identical — the
      ;; hermetic install wrote only the constructed realm's own registrar.
      (is (= before (registrar/registrations :event))
          "the default realm's registrar is unchanged by the hermetic install")
      (is (contains? (rf/registrations {:realm :conf/hermetic :kind :event}) :hermetic/e)
          "the hermetic realm holds its own installed event"))))

;; ---------------------------------------------------------------------------
;; (7) CAPABILITY check on a constructed realm
;; ---------------------------------------------------------------------------

(deftest install-capability-check-on-a-constructed-realm
  (testing "install! into a constructed realm capability-checks FIRST — an unmet
            requirement throws before any mutation to the realm's own registrar"
    (let [r  (rf/realm {:id :conf/needs-caps})
          a  (rf/app {:id :c :modules
                      [(rf/module {:id :m
                                   :requires #{:rf.capability/http}
                                   :events {:c/e {:handler add-a}}})]})
          ed (try (rf/install! r a)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/missing-capability (:error/id ed)))
      (is (= :conf/needs-caps (:realm ed)) "the constructed realm is named")
      (is (= :rf.capability/http (:capability ed)))
      ;; Nothing was seated into the realm's own registrar (pre-mutation fail).
      (is (= {} @(realm/registrar r))
          "the realm's own registrar is empty — the failed install was atomic"))
    (testing "and it succeeds once the realm provides the capability"
      (let [r (rf/realm {:id :conf/has-caps
                         :capabilities {:rf.capability/http {:request! identity}}})]
        (rf/install! r (rf/app {:id :c2 :modules
                                [(rf/module {:id :m
                                             :requires #{:rf.capability/http}
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
    (rf/install! (rf/app {:id :d :modules
                          [(rf/module {:id :m :events {:default/e {:handler add-a}}})]}))
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
    (rf/reg-event-db :conf/set {:doc "set"} (fn [db [_ v]] (assoc db :n v)))
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
    (let [r  (rf/realm {:id :conf/reload})
          v1 (rf/app {:id :rl :modules
                      [(rf/module {:id :m :events {:rl/keep {:handler add-a}
                                                   :rl/drop {:handler add-a}}})]})
          v2 (rf/app {:id :rl :modules
                      [(rf/module {:id :m :events {:rl/keep {:handler add-b}
                                                   :rl/new  {:handler add-b}}})]})]
      (rf/install! r v1)
      (let [diff (rf/reinstall! r v2 {:reason :hot-reload})]
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

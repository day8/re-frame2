(ns re-frame.realm-source-store-isolation-cljs-test
  "rf2-9fn4is — `install!` / `reinstall!` seating into a non-default realm must
  bind `re-frame.source-store/*source-store*` consistently with
  `registrar/*registrar*`, so the EP-0023 provenance source-store writes land in
  the realm's OWN store, not the process-default one.

  ## The gap

  `registrar/register!` writes EVERY registration descriptor through
  `source-store/record-descriptor!`, which targets `(active-source-store)` =
  `(or *source-store* kind->id->ns->descriptor)`. Before the fix `seat-into-realm!`
  bound only `registrar/*registrar*` — never `source-store/*source-store*` — so a
  hermetic-realm install routed its registrar metadata into the realm registrar
  but POLLUTED the process-default source store with the same descriptors. A later
  non-default reinstall/removal could then also REMOVE a default-realm source
  descriptor with the same `(kind, id)`, breaking the EP-0023 provenance-store
  isolation model and corrupting default-image assembly / hot reload.

  Pins (the acceptance criteria):

    (a) installing the same `(kind, id)` into two hermetic realms does NOT add
        either descriptor to the process-default source store;
    (b) a constructed realm OWNS (resolves to) its own source-store atom,
        distinct from the process-default;
    (c) a non-default reinstall/removal does NOT delete a default-realm source
        descriptor with the same `(kind, id)`;
    (d) failed install seating is ATOMIC for the source store too — a throw
        part-way leaves no partial realm-source-store writes;
    (e) live image / default assembly reads the intended store under the realm
        binding (the realm store's generation bumps on its OWN mutations, the
        default store's does not).

  Dual-runtime `*_cljs_test.cljc` — the shadow-cljs `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Pure CLJC, no DOM."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.app-value :as av]
            [re-frame.realm :as realm]
            [re-frame.registrar :as registrar]
            [re-frame.source-store :as source-store]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; The realm registry (`realm/realms`) is process-global. The runtime-reset
;; fixture snapshot/restores the DEFAULT realm's registrar but does NOT touch
;; the registry's other entries NOR the process-default EP-0023 source store, so
;; this fixture also (a) drops any leftover non-default realms and (b)
;; snapshot/restores the process-default source store so a `reg-event` in one
;; test does not leak its source slot into the next.
(defn- drop-non-default-realms! []
  (swap! realm/realms select-keys [realm/default-realm-id])
  (swap! realm/realms update realm/default-realm-id dissoc :app :capabilities))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [test-fn]
    (drop-non-default-realms!)
    (let [ss-snapshot @source-store/kind->id->ns->descriptor]
      (try
        (test-fn)
        (finally
          (reset! source-store/kind->id->ns->descriptor ss-snapshot)
          (drop-non-default-realms!))))))

;; Handler stand-ins — value identity is what install! seats + the source store
;; preserves per provenance.
(defn- add-a [{:keys [db]} _] {:db (assoc db :who :a)})
(defn- add-b [{:keys [db]} _] {:db (assoc db :who :b)})

(defn- app-of [id handler]
  (av/app {:id id :modules
           [(av/module {:id :m :events {:shared/e {:handler handler}}})]}))

;; ---------------------------------------------------------------------------
;; (b) A constructed realm OWNS its own source-store atom, distinct from default
;; ---------------------------------------------------------------------------

(deftest constructed-realm-owns-its-own-source-store
  (testing "rf2-9fn4is — a hermetic constructed realm resolves to its OWN
            source-store atom (not the process-default), mirroring its OWN
            registrar atom"
    (let [r (realm/construct-realm {:id :ss/r1})]
      (is (not (identical? (realm/source-store r)
                           source-store/kind->id->ns->descriptor))
          "the constructed realm's source store is NOT the process-default atom")
      (is (not (identical? (realm/registrar r)
                           registrar/kind->id->metadata))
          "the constructed realm's registrar is NOT the process-default atom (control)")
      (realm/dispose-realm! :ss/r1))))

;; ---------------------------------------------------------------------------
;; (a) Installing the same (kind, id) into two realms does NOT pollute default
;; ---------------------------------------------------------------------------

(deftest install-into-realm-does-not-pollute-default-source-store
  (testing "rf2-9fn4is — installing the SAME (kind, id) into two hermetic realms
            adds NEITHER descriptor to the process-default source store; each
            realm's own source store holds only its own descriptor"
    (let [ra (realm/construct-realm {:id :ss/a})
          rb (realm/construct-realm {:id :ss/b})]
      (is (empty? (source-store/descriptors-for :event :shared/e))
          "the process-default source store has no :shared/e before any install")
      (av/install! ra (app-of :ss/app-a add-a))
      (av/install! rb (app-of :ss/app-b add-b))
      ;; THE BUG: pre-fix the realm installs wrote :shared/e into the
      ;; process-default source store (the *source-store* binding was missing),
      ;; so this map was non-empty.
      (is (empty? (source-store/descriptors-for :event :shared/e))
          "the process-default source store still has NO :shared/e after both
           realm installs — neither realm polluted it")
      ;; Each realm's OWN source store holds exactly its own descriptor (looked
      ;; up provenance-agnostically — the install-lowered descriptor's
      ;; provenance is its handler's defining ns, not the app id).
      (binding [source-store/*source-store* (realm/source-store ra)]
        (let [descs (source-store/descriptors-for :event :shared/e)]
          (is (= 1 (count descs)) "realm a's own source store holds one :shared/e slot")
          (is (= add-a (-> descs vals first :handler-fn))
              "realm a's own source store holds add-a's descriptor")))
      (binding [source-store/*source-store* (realm/source-store rb)]
        (let [descs (source-store/descriptors-for :event :shared/e)]
          (is (= 1 (count descs)) "realm b's own source store holds one :shared/e slot")
          (is (= add-b (-> descs vals first :handler-fn))
              "realm b's own source store holds add-b's descriptor")))
      (realm/dispose-realm! :ss/a)
      (realm/dispose-realm! :ss/b))))

;; ---------------------------------------------------------------------------
;; (c) A non-default reinstall/removal does NOT delete a default source descriptor
;; ---------------------------------------------------------------------------

(deftest realm-reinstall-does-not-remove-default-source-descriptor
  (testing "rf2-9fn4is — a default-realm registration for (kind, id) survives a
            non-default realm's reinstall that REMOVES the same (kind, id) — the
            non-default removal targets the realm's OWN source store, not the
            default's"
    ;; Seat :shared/e in the DEFAULT realm via ordinary sugar (process-default
    ;; source store).
    (rf/reg-event :shared/e add-a)
    (is (seq (source-store/descriptors-for :event :shared/e))
        "the default realm's source store holds the sugar-registered :shared/e")
    (let [r (realm/construct-realm {:id :ss/reinst})]
      ;; Install :shared/e into the hermetic realm, then reinstall an app that
      ;; DROPS it (the removal path that previously deleted the default slot).
      (av/install!   r (app-of :ss/reinst-app add-b))
      (av/reinstall! r (av/app {:id :ss/reinst-app :modules
                                [(av/module {:id :m :events {:other/e {:handler add-a}}})]}))
      ;; THE BUG: pre-fix the reinstall's `forget-id!` for the removed :shared/e
      ;; ran against the process-default source store, deleting the default
      ;; realm's own :shared/e descriptor.
      (is (seq (source-store/descriptors-for :event :shared/e))
          "the default realm's :shared/e source descriptor SURVIVES the
           non-default realm's removal of the same (kind, id)")
      (realm/dispose-realm! :ss/reinst))))

;; ---------------------------------------------------------------------------
;; (d) Failed install seating is ATOMIC for the source store too
;; ---------------------------------------------------------------------------

(deftest failed-install-leaves-no-partial-realm-source-store-writes
  (testing "rf2-9fn4is — a throw PART-WAY through install seating leaves no
            partial writes in the realm's source store (atomic across BOTH the
            registrar and the source store)"
    (let [r (realm/construct-realm {:id :ss/atomic})
          ;; An app whose second descriptor names a step-8-DEFERRED kind so the
          ;; seating throws AFTER the first descriptor would otherwise land.
          ;; install! preflights the kinds and throws before lowering anything,
          ;; so to force a MID-LOOP throw we install a good app first, then a
          ;; reinstall whose apply loop throws part-way.
          good (av/app {:id :ss/atomic-app :modules
                        [(av/module {:id :m :events {:ok/e1 {:handler add-a}}})]})]
      (av/install! r good)
      ;; Sanity: the good descriptor is in the realm's own source store.
      (binding [source-store/*source-store* (realm/source-store r)]
        (is (seq (source-store/descriptors-for :event :ok/e1))
            "the realm source store holds the first good descriptor"))
      (let [store-value-before @(realm/source-store r)
            ;; A reinstall whose NEW app adds a descriptor of a deferred kind —
            ;; the apply loop throws part-way. (Deferred kinds throw
            ;; :rf.error/unsupported-descriptor-kind on lowering.)
            bad (av/app {:id :ss/atomic-app :modules
                         [(av/module {:id :m
                                      :events {:ok/e1 {:handler add-a}
                                               :ok/e2 {:handler add-b}}
                                      :flows  {:bad/flow {:output (fn [_] nil)}}})]})
            threw? (try (av/reinstall! r bad) false
                        (catch #?(:clj Throwable :cljs :default) _ true))]
        (is threw? "the reinstall threw on the deferred-kind descriptor")
        (binding [source-store/*source-store* (realm/source-store r)]
          ;; THE BUG would manifest as a partial write: :ok/e2 landing in the
          ;; realm source store even though the reinstall rolled back.
          (is (empty? (source-store/descriptors-for :event :ok/e2))
              "no partial :ok/e2 descriptor survived the rolled-back reinstall")
          (is (seq (source-store/descriptors-for :event :ok/e1))
              "the pre-existing :ok/e1 descriptor is untouched by the rollback"))
        ;; The store VALUE is restored byte-for-byte to its pre-reinstall state
        ;; (the load-bearing atomicity property; the monotonic generation may
        ;; legitimately have moved forward — a forward generation over an
        ;; unchanged store is a safe cache false-miss, never a stale hit).
        (is (= store-value-before @(realm/source-store r))
            "the realm source store value is restored after the rolled-back reinstall"))
      (realm/dispose-realm! :ss/atomic))))

;; ---------------------------------------------------------------------------
;; (e) The realm store's generation bumps on its OWN mutations, default's does not
;; ---------------------------------------------------------------------------

(deftest realm-source-store-generation-is-independent-of-default
  (testing "rf2-9fn4is — a realm install bumps the realm source store's
            generation while leaving the process-default store's generation
            untouched (image assembly reads the intended store under the binding)"
    (let [default-gen-before (source-store/store-generation)
          r                  (realm/construct-realm {:id :ss/gen})
          realm-gen-before   (binding [source-store/*source-store* (realm/source-store r)]
                               (source-store/store-generation))]
      (av/install! r (app-of :ss/gen-app add-a))
      (is (= default-gen-before (source-store/store-generation))
          "the process-default source store's generation is UNCHANGED by the realm install")
      (let [realm-gen-after (binding [source-store/*source-store* (realm/source-store r)]
                              (source-store/store-generation))]
        (is (> realm-gen-after realm-gen-before)
            "the realm source store's OWN generation bumped on its install"))
      (realm/dispose-realm! :ss/gen))))

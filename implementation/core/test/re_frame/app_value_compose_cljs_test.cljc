(ns re-frame.app-value-compose-cljs-test
  "EP-0013 D2 stage-6 — the PUBLIC app/module constructors + composition
  algebra (rf2-zlhgr6, built on the merged stage-5 projection).

  Stage 6 graduates the CONSTRUCTION half of the app value: build a program
  as INERT data from explicit feature modules, then inspect it before any
  realm. The public surface (re-exported from `re-frame.core` as `av/module`
  / `av/app` / `av/app-registrations` / `av/app-owns` / `av/app-requires`):

    (1) `module` lowers a module-descriptor map into a module value — the
        descriptor-grouped shape, owner-stamped, with `:rf.module/owns` /
        `:rf.module/requires` / `:source` carried; PURE (no realm, no registrar);
    (2) `app` composes module values into an app value — DETERMINISTIC +
        order-stable, `:rf.app/requires` unioned, `:modules` keyed by id;
    (3) composition is STRICT — a same-(kind,id) collision across modules
        THROWS `:rf.error/app-composition-collision` enumerating every
        colliding source (never last-writer-wins);
    (4) the composition LAWS (EP-0013 §Composition / §Validation): empty
        module is identity; grouping order does not change the value; every
        input descriptor is preserved exactly once;
    (5) the inspectors (`app-registrations` / `app-requires` / `app-owns`)
        read an app value WITHOUT installing it;
    (6) construction is PURE — building a module/app touches no registrar
        (the sugar path is unaffected), and the descriptor shape MATCHES the
        stage-5 projection's (one app-value vocabulary, two origins).

  Dual-runtime `*_cljs_test.cljc` — the shadow-cljs `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Pure CLJC, no DOM."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.app-value :as av]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; Two handler stand-ins — value identity is what the descriptor must carry.
(defn- cart-add  [{:keys [db]} _] {:db (update db :items (fnil conj []) :x)})
(defn- cart-items [db _] (:items db))
(defn- auth-login [db _] (assoc db :user :alice))

;; ---------------------------------------------------------------------------
;; (1) module — lowers a descriptor map into a module value
;; ---------------------------------------------------------------------------

(deftest module-shape-and-owner-stamping
  (testing "module lowers each registration section into descriptors stamped
            with the module id as :owner, carries :rf.module/owns /
            :rf.module/requires (owner-qualified FACT keys, EP-0007 / EP-0017
            v5), and is keyed by the singular registry kind"
    (let [m (av/module
              {:id :shop/cart
               :rf.module/owns {:app-db [[:cart]] :resources [:shop.cart/items]}
               :rf.module/requires #{:rf.capability/http}
               :events {:cart/add {:doc "Add an item." :handler cart-add}}
               :subs   {:cart/items {:doc "Cart items." :handler cart-items}}})]
      (is (= :shop/cart (:rf.module/id m)) "the module carries its id")
      (is (= {:app-db [[:cart]] :resources [:shop.cart/items]} (:rf.module/owns m))
          ":rf.module/owns is carried through verbatim")
      (is (= #{:rf.capability/http} (:rf.module/requires m))
          ":rf.module/requires is carried as a set")
      ;; sections lower to the SINGULAR registry kind (:events -> :event).
      (let [d (get-in m [:registrations :event :cart/add])]
        (is (= :event (:kind d)) "the section lowers to the singular kind")
        (is (= :cart/add (:id d)))
        (is (= :shop/cart (:owner d)) "the descriptor is owner-stamped")
        (is (identical? cart-add (:handler d)) "the handler is lifted out of :handler")
        (is (= "Add an item." (get-in d [:metadata :doc]))
            "the rest of the entry is kept under :metadata")
        (is (not (contains? (:metadata d) :handler))
            ":handler is not double-carried in :metadata"))
      (is (= :shop/cart (get-in m [:registrations :sub :cart/items :owner]))
          "the :subs section lowers to :sub, owner-stamped"))))

(deftest module-requires-defaults-empty
  (testing "a module with no :rf.module/requires gets #{}"
    (let [m (av/module {:id :m :events {:e {:handler cart-add}}})]
      (is (= #{} (:rf.module/requires m)))
      (is (= {} (:rf.module/owns m)) ":rf.module/owns defaults to {}"))))

(deftest module-lifts-explicit-source-coords
  (testing "an entry's explicit :source envelope is lifted into the descriptor
            and removed from :metadata (issue 8 — a non-macro/code-gen host may
            supply coords); the module's own :source is carried top-level"
    (let [src {:ns 'shop.cart.events :file "src/shop/cart/events.cljs" :line 22 :column 1}
          msrc {:ns 'shop.cart :file "src/shop/cart.cljs" :line 12 :column 1}
          m (av/module
              {:id :shop/cart
               :source msrc
               :events {:cart/add {:doc "Add." :handler cart-add :source src}}})
          d (get-in m [:registrations :event :cart/add])]
      (is (= src (:source d)) "the entry's :source is lifted into the descriptor")
      (is (not (contains? (:metadata d) :source))
          ":source is not double-carried in :metadata")
      (is (= msrc (:source m)) "the module's own :source is carried top-level"))))

(deftest module-rejects-missing-id-and-unknown-section
  (testing "module throws :rf.error/invalid-module on a missing :id (the one
            construction precondition) and on an unknown section key"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (av/module {:events {:e {:handler cart-add}}}))
        "missing :id throws")
    (let [ed (try (av/module {:id :m :bogus {:x {:handler cart-add}}})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/invalid-module (:rf.error/id ed)))
      (is (= :bogus (:unknown-section ed))
          "the unknown section key is named in the diagnostic"))))

(deftest module-owns-requires-are-owner-qualified-bare-keys-rejected
  ;; rf2-yk6u2x — the adversarial pin for the EP-0007 / EP-0017 v5 rename.
  ;; The module's ownership + capability FACTS are owner-qualified
  ;; (`:rf.module/owns` / `:rf.module/requires`); the BARE `:owns` / `:requires`
  ;; spellings are no longer reserved module keys, so they are rejected loudly
  ;; as unknown section keys (fail-closed — never silently honoured nor dropped).
  (testing "the QUALIFIED keys carry the fact onto the module value"
    (let [m (av/module {:id :shop/cart
                        :rf.module/owns     {:app-db [[:cart]]}
                        :rf.module/requires #{:rf.capability/http}
                        :events {:cart/add {:handler cart-add}}})]
      (is (= {:app-db [[:cart]]} (:rf.module/owns m))
          ":rf.module/owns carries the ownership declaration")
      (is (= #{:rf.capability/http} (:rf.module/requires m))
          ":rf.module/requires carries the capability set")
      ;; The bare keys never appear on the produced module value.
      (is (not (contains? m :owns)) "no bare :owns slot on the module value")
      (is (not (contains? m :requires)) "no bare :requires slot on the module value")))
  (testing "the BARE :owns key is REJECTED as an unknown section (not silently
            honoured, not silently dropped) — the rename is fail-closed"
    (let [ed (try (av/module {:id :m :owns {:app-db [[:cart]]}})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/invalid-module (:rf.error/id ed))
          "a bare :owns throws :rf.error/invalid-module")
      (is (= :owns (:unknown-section ed))
          "the bare key is named as the offending unknown section")))
  (testing "the BARE :requires key is likewise REJECTED"
    (let [ed (try (av/module {:id :m :requires #{:rf.capability/http}})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/invalid-module (:rf.error/id ed))
          "a bare :requires throws :rf.error/invalid-module")
      (is (= :requires (:unknown-section ed))
          "the bare key is named as the offending unknown section")))
  (testing "the app value's union capability set is the owner-qualified
            :rf.app/requires — and no bare :requires slot survives on the app"
    (let [a (av/app {:id :shop/app
                     :modules [(av/module {:id :shop/cart
                                           :rf.module/requires #{:rf.capability/http}
                                           :events {:cart/add {:handler cart-add}}})]})]
      (is (= #{:rf.capability/http} (:rf.app/requires a))
          ":rf.app/requires carries the union capability set")
      (is (not (contains? a :requires))
          "no bare :requires slot on the app value")
      (is (= #{:rf.capability/http} (av/app-requires a))
          "av/app-requires reads it off the qualified slot"))))

;; ---------------------------------------------------------------------------
;; (2) app — composes module values into an app value
;; ---------------------------------------------------------------------------

(deftest app-composes-modules
  (testing "app composes module values: :registrations is the union of every
            module's descriptors, :rf.app/requires is the union of the modules'
            :rf.module/requires, and :modules is keyed by module id"
    (let [auth (av/module {:id :shop/auth
                           :rf.module/requires #{:rf.capability/http}
                           :events {:auth/login {:handler auth-login}}})
          cart (av/module {:id :shop/cart
                           :rf.module/owns {:app-db [[:cart]]}
                           :rf.module/requires #{:rf.capability/schemas}
                           :events {:cart/add {:handler cart-add}}
                           :subs   {:cart/items {:handler cart-items}}})
          a (av/app {:id :shop/app :modules [auth cart]})]
      (is (= :shop/app (:rf.app/id a)) "the app carries the declared id")
      (is (= #{:shop/auth :shop/cart} (set (keys (:modules a))))
          ":modules is keyed by module id")
      (is (identical? auth-login (get-in a [:registrations :event :auth/login :handler]))
          "auth's descriptor is composed in")
      (is (identical? cart-add (get-in a [:registrations :event :cart/add :handler]))
          "cart's descriptor is composed in")
      (is (= #{:rf.capability/http :rf.capability/schemas} (:rf.app/requires a))
          ":rf.app/requires is the union of the modules' requirements"))))

(deftest app-of-zero-modules-is-valid-empty-app
  (testing "an app of zero modules is a valid, empty app value"
    (let [a (av/app {:id :empty/app})]
      (is (= :empty/app (:rf.app/id a)))
      (is (= {} (:modules a)))
      (is (= {} (:registrations a)))
      (is (= #{} (:rf.app/requires a))))))

(deftest app-rejects-missing-id-and-non-module
  (testing "app throws :rf.error/invalid-app on a missing :id or a :modules
            entry that is not a module value"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (av/app {:modules []}))
        "missing :id throws")
    (let [ed (try (av/app {:id :a :modules [{:not :a-module}]})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/invalid-app (:rf.error/id ed))
          "a non-module :modules entry throws :rf.error/invalid-app"))))

;; ---------------------------------------------------------------------------
;; (3) composition is STRICT — collisions throw, naming every source
;; ---------------------------------------------------------------------------

(deftest app-composition-collision-throws-with-every-source
  (testing "a same-(kind,id) collision across modules THROWS
            :rf.error/app-composition-collision enumerating BOTH colliding
            sources (never last-writer-wins) — EP-0013 §Composition"
    (let [a-src {:ns 'a :file "a.cljs" :line 1 :column 1}
          b-src {:ns 'b :file "b.cljs" :line 9 :column 1}
          ma (av/module {:id :a :events {:shared/save {:handler cart-add :source a-src}}})
          mb (av/module {:id :b :events {:shared/save {:handler auth-login :source b-src}}})
          ed (try (av/app {:id :bad/app :modules [ma mb]})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/app-composition-collision (:rf.error/id ed)))
      (is (= :event (:kind ed)))
      (is (= :shared/save (:id ed)))
      (is (= #{:a :b} (set (map :module (:sources ed))))
          "both colliding module ids are reported")
      (is (= #{a-src b-src} (set (map :source (:sources ed))))
          "every available colliding source coordinate is reported")
      (is (= :rename-or-explicitly-replace (:recovery ed))))))

(deftest app-collision-is-cross-kind-safe
  (testing "the SAME id under DIFFERENT kinds is NOT a collision — collision
            is per-(kind,id)"
    (let [ma (av/module {:id :a :events {:shared/x {:handler cart-add}}})
          mb (av/module {:id :b :subs   {:shared/x {:handler cart-items}}})
          a (av/app {:id :ok/app :modules [ma mb]})]
      (is (identical? cart-add   (get-in a [:registrations :event :shared/x :handler])))
      (is (identical? cart-items (get-in a [:registrations :sub   :shared/x :handler]))
          "same id, different kind — both compose without collision"))))

;; ---------------------------------------------------------------------------
;; (3b) DUPLICATE MODULE IDS — provenance key, not last-writer-wins
;;      (rf2-c6armm.1 #4 / .3 #3)
;; ---------------------------------------------------------------------------

(deftest app-divergent-duplicate-module-ids-throw
  (testing "two DISTINCT modules with the SAME :rf.module/id THROW
            :rf.error/app-composition-collision (:kind :module) — module id is
            the provenance key, so silently keeping one would make composition
            order-dependent (rf2-c6armm.1 #4)"
    (let [ma (av/module {:id :dup/m :events {:dup/a {:handler cart-add}}})
          mb (av/module {:id :dup/m :events {:dup/b {:handler auth-login}}})
          ed (try (av/app {:id :dup/app :modules [ma mb]})
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/app-composition-collision (:rf.error/id ed)))
      (is (= :module (:kind ed)) "the collision names the :module provenance kind")
      (is (= :dup/m  (:id ed))   "the colliding module id is named"))))

(deftest app-divergent-duplicate-module-ids-order-independent
  (testing "the divergent-duplicate-module-id rejection is ORDER-INDEPENDENT —
            both orderings throw, so composition is not last-writer-wins
            (rf2-c6armm.3 #3 — would have silently kept whichever module came
            last in the vector)"
    (let [ma (av/module {:id :dup/m :events {:dup/a {:handler cart-add}}})
          mb (av/module {:id :dup/m :events {:dup/b {:handler auth-login}}})
          ed-ab (try (av/app {:id :dup/app :modules [ma mb]}) :no-throw
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                       (:rf.error/id (ex-data e))))
          ed-ba (try (av/app {:id :dup/app :modules [mb ma]}) :no-throw
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                       (:rf.error/id (ex-data e))))]
      (is (= :rf.error/app-composition-collision ed-ab) "[ma mb] throws")
      (is (= :rf.error/app-composition-collision ed-ba) "[mb ma] throws too — order-independent"))))

(deftest app-exact-equal-duplicate-module-is-idempotent
  (testing "an EXACTLY-equal module listed twice is deduped (idempotent
            re-listing is harmless) — the same module value contributes its
            registrations once, not a spurious same-(kind,id) collision
            (rf2-c6armm.1 #4)"
    (let [m (av/module {:id :idem/m :events {:idem/e {:handler cart-add}}})
          a (av/app {:id :idem/app :modules [m m]})]
      (is (= #{:idem/m} (set (keys (:modules a))))
          "the deduped module appears once in :modules")
      (is (identical? cart-add (get-in a [:registrations :event :idem/e :handler]))
          "its single descriptor is composed in (no double-fold collision)"))))

;; ---------------------------------------------------------------------------
;; (4) the composition LAWS (EP-0013 §Composition / §Validation/Conformance)
;; ---------------------------------------------------------------------------

(deftest law-empty-module-is-identity
  (testing "composing with an empty module is identity — the app value equals
            the app value composed without it"
    (let [cart  (av/module {:id :cart :events {:cart/add {:handler cart-add}}})
          empty (av/module {:id :empty})
          with    (av/app {:id :app :modules [cart empty]})
          without (av/app {:id :app :modules [cart]})]
      (is (= (:registrations without) (:registrations with))
          "an empty module contributes no registrations")
      (is (= (:rf.app/requires without) (:rf.app/requires with))
          "an empty module contributes no requirements"))))

(deftest law-grouping-order-does-not-change-the-value
  (testing "grouping modules in a different ORDER does not change the resulting
            app value — composition is order-stable (EP-0013 §Composition)"
    (let [auth (av/module {:id :auth :rf.module/requires #{:rf.capability/http}
                           :events {:auth/login {:handler auth-login}}})
          cart (av/module {:id :cart :rf.module/requires #{:rf.capability/schemas}
                           :events {:cart/add {:handler cart-add}}})
          a1 (av/app {:id :app :modules [auth cart]})
          a2 (av/app {:id :app :modules [cart auth]})]
      (is (= (:registrations a1) (:registrations a2))
          "registrations are independent of module order")
      (is (= (:rf.app/requires a1) (:rf.app/requires a2))
          "requires are independent of module order")
      (is (= a1 a2) "the whole app value is order-independent"))))

(deftest law-every-input-descriptor-preserved-once
  (testing "successful composition preserves every input descriptor exactly
            once — the composed event set is the union of the modules' ids"
    (let [auth (av/module {:id :auth :events {:auth/login {:handler auth-login}}})
          cart (av/module {:id :cart :events {:cart/add {:handler cart-add}
                                              :cart/clear {:handler cart-add}}})
          a (av/app {:id :app :modules [auth cart]})]
      (is (= #{:auth/login :cart/add :cart/clear}
             (set (keys (get-in a [:registrations :event]))))
          "every input event descriptor is present, exactly once"))))

;; ---------------------------------------------------------------------------
;; (5) the inspectors — read an app value WITHOUT installing it
;; ---------------------------------------------------------------------------

(deftest inspectors-read-a-constructed-app
  (testing "app-registrations / app-requires / app-owns read a constructed app
            value without installing it (EP-0013 §Examples 'A Whole App As
            Data')"
    (let [cart (av/module {:id :shop/cart
                           :rf.module/owns {:app-db [[:cart]]}
                           :rf.module/requires #{:rf.capability/http}
                           :events {:cart/add {:handler cart-add}}})
          a (av/app {:id :shop/app :modules [cart]})]
      (is (= #{:cart/add} (set (keys (av/app-registrations a :event))))
          "app-registrations enumerates the app's events by kind")
      (is (nil? (av/app-registrations a :route))
          "app-registrations is nil for a kind the app declares none of")
      (is (= #{:rf.capability/http} (av/app-requires a))
          "app-requires reads the capability dependency surface")
      (is (= :shop/cart (av/app-owns a [:cart]))
          "app-owns resolves the module owning an app-db path")
      (is (nil? (av/app-owns a [:unowned]))
          "app-owns is nil for an unowned path"))))

;; ---------------------------------------------------------------------------
;; (6) construction is PURE + the descriptor shape matches the projection
;; ---------------------------------------------------------------------------

(deftest construction-touches-no-registrar
  (testing "building a module/app has NO registration side effect — the
            registrar is untouched (the sugar path is unaffected; an app value
            is inert data per EP-0013 issue 10)"
    (is (nil? (registrar/lookup :event :cart/add))
        "no :cart/add in the registrar before construction")
    (let [m (av/module {:id :cart :events {:cart/add {:handler cart-add}}})]
      (av/app {:id :app :modules [m]})
      (is (nil? (registrar/lookup :event :cart/add))
          "STILL no :cart/add in the registrar after module + app — construction
           registered nothing"))))

(deftest constructed-descriptor-shape-matches-projection
  (testing "a constructed app's descriptor shape MATCHES the stage-5
            projection's (one app-value vocabulary, two origins) — kind / id /
            handler / metadata are the same keys, with the only addition being
            :owner (which the projection never carries)"
    (rf/reg-event :av/proj {:doc "projected"} cart-add)
    (let [proj-d (get-in (av/app-value) [:registrations :event :av/proj])
          cons-d (get-in (av/app {:id :a :modules
                                  [(av/module {:id :m :events
                                               {:av/cons {:doc "constructed"
                                                          :handler cart-add}}})]})
                         [:registrations :event :av/cons])]
      ;; Same descriptor keys, modulo the construction-only :owner.
      (is (= #{:kind :id :handler :metadata}
             (disj (set (keys cons-d)) :owner))
          "the constructed descriptor carries the projection's keys (+ :owner)")
      (is (= :event (:kind proj-d) (:kind cons-d)) "both carry :kind")
      (is (= :m (:owner cons-d)) "only the constructed descriptor carries :owner")
      (is (not (contains? proj-d :owner))
          "the projected descriptor carries no :owner (load-order has no module)"))))

(ns re-frame.app-value-install-cljs-test
  "EP-0013 D2 stage-7 — `install!` / `reinstall!`: seat an immutable app value
  into a realm (rf2-xq4go0, the LAST D2 slice, built on the merged stage-5
  projection + stage-6 construction).

  Stage 7 closes the D2 loop — the program is a value (stage 6 construction),
  and the runtime is a container you install it into (stage 7). The public
  surface (re-exported from `re-frame.core` as `rf/install!` / `rf/reinstall!`):

    (1) `install!` seats an app value as a realm's program — lowers every
        descriptor back into the realm's registrar (the inverse of the stage-5/6
        descriptor normalisation) so the program is dispatch/subscribe/resolve-
        able, and records the seated value in the realm's `:app` slot;
    (2) the CAPABILITY CHECK runs FIRST — the app value's `:requires` must be
        satisfiable by the realm's `:capabilities`; the first unmet one throws
        `:rf.error/missing-capability` BEFORE any registrar mutation (an
        under-provisioned app never becomes partially visible);
    (3) ZERO ergonomic regression — `install!` defaults to the default realm,
        and the ordinary `reg-*` sugar path is byte-identical (an installed
        descriptor and a `reg-*`-registered one resolve through the SAME
        registrar lookup; the sugar path touches nothing install! changed);
    (4) `reinstall!` hot-reloads a realm by DIFFING the new app value against
        the installed one (`:added` / `:changed` / `:removed`) and applying the
        delta — added/changed re-registered, removed unregistered (failing
        loudly on future lookup); returns the diff value;
    (5) the installed app VALUE round-trips — after install! the realm's
        `installed-app` returns the rich constructed value (carrying `:modules`
        + `:owner`-stamped descriptors), in preference to the projection.

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

;; The realm registry (`realm/realms`) is process-global, and the `:app` slot
;; install!/reinstall! seat is NOT registrar state — the runtime reset fixture
;; (which snapshot/restores the registrar) does not touch it. Clear it around
;; each test so a stored `:app` from a sibling test never leaks into a diff.
;; (A single `use-fixtures :each` — a second call REPLACES rather than composes,
;; so the realm-`:app` reset is wrapped INSIDE the runtime-reset fixture.)
(defn- clear-realm-app! []
  (swap! realm/realms update realm/default-realm-id dissoc :app))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [test-fn]
    (clear-realm-app!)
    (test-fn)
    (clear-realm-app!)))

;; Handler stand-ins — value identity is what install! must seat + resolve.
(defn- cart-add   [db _] (update db :items (fnil conj []) :x))
(defn- cart-items [db _] (:items db))
(defn- cart-remove [db _] (update db :items pop))
(defn- auth-login [db _] (assoc db :user :alice))

;; The default realm's capability map is empty out of the box; some tests need
;; to seat one. The realm registry is process-global, so each test that mutates
;; it restores the default realm to a no-capabilities baseline on the way out.
(defn- with-default-realm-capabilities! [caps]
  (swap! realm/realms update realm/default-realm-id assoc :capabilities caps))

(defn- clear-default-realm-capabilities! []
  (swap! realm/realms update realm/default-realm-id dissoc :capabilities))

;; ---------------------------------------------------------------------------
;; (1) install! seats an app value as the realm's program (registrar-lowered)
;; ---------------------------------------------------------------------------

(deftest install-lowers-descriptors-into-the-registrar
  (testing "install! seats every descriptor of an app value into the realm's
            registrar — the lowered metadata carries the handler under
            :handler-fn, the rest of the metadata at top level, and the owning
            module under :owner"
    (is (nil? (registrar/lookup :event :cart/add))
        "no :cart/add in the registrar before install")
    (let [cart (rf/module {:id :shop/cart
                           :events {:cart/add {:doc "Add." :handler cart-add}}
                           :subs   {:cart/items {:doc "Items." :handler cart-items}}})
          a    (rf/app {:id :shop/app :modules [cart]})]
      (rf/install! a)
      ;; The handler is resolvable through the ordinary registrar lookup — the
      ;; program is now installed (not merely constructed).
      (is (identical? cart-add (registrar/handler :event :cart/add))
          "the event handler is seated under :handler-fn and resolvable")
      (is (identical? cart-items (registrar/handler :sub :cart/items))
          "the sub handler is seated too")
      (let [meta (registrar/lookup :event :cart/add)]
        (is (= "Add." (:doc meta)) "the metadata is folded back to top level")
        (is (= :shop/cart (:owner meta))
            "the owning module is carried through into the registrar metadata")))))

(deftest install-returns-the-realm-and-seats-the-app-slot
  (testing "install! returns the realm map (its :app slot now holds the app
            value) so the call composes, and installed-app returns the rich
            constructed value in preference to the projection"
    (let [cart (rf/module {:id :shop/cart :events {:cart/add {:handler cart-add}}})
          a    (rf/app {:id :shop/app :modules [cart]})
          ret  (rf/install! a)]
      (is (= a (:app ret)) "install! returns the realm with the app in :app")
      (is (= a (realm/installed-app))
          "installed-app returns the stored constructed app value")
      (is (= {:shop/cart cart} (:modules (realm/installed-app)))
          "the installed app value carries its :modules (the rich constructed
           value, not the module-less projection)"))))

;; ---------------------------------------------------------------------------
;; (2) the capability check — fail LOUD on an unmet :requires, before mutation
;; ---------------------------------------------------------------------------

(deftest install-throws-on-unmet-capability-before-any-mutation
  (testing "install! capability-checks FIRST — an app requiring a capability
            the realm does not provide throws :rf.error/missing-capability,
            naming the realm + capability, and registers NOTHING (the under-
            provisioned app never becomes partially visible)"
    (clear-default-realm-capabilities!)
    (let [needs-http (rf/module {:id :shop/cart
                                 :requires #{:rf.capability/http}
                                 :events {:cart/add {:handler cart-add}}})
          a  (rf/app {:id :shop/app :modules [needs-http]})
          ed (try (rf/install! a)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/missing-capability (:error/id ed))
          "the missing-capability error is raised")
      (is (= :rf.capability/http (:capability ed))
          "the unmet capability is named")
      (is (= :rf.realm/default (:realm ed))
          "the realm is named (install! defaulted to the default realm)")
      (is (= :install-capability (:recovery ed)))
      ;; Nothing was registered — the check ran before any registrar mutation.
      (is (nil? (registrar/lookup :event :cart/add))
          "no descriptor was seated — the failure was atomic (pre-mutation)"))))

(deftest install-seating-loop-is-atomic-mid-stream-throw-rolls-back
  (testing "install!'s seating loop is ALL-OR-NOTHING — when a descriptor throws
            PART-WAY through (after kinds 1..N-1 already seated), the realm's
            registrar is rolled back to its pre-install state AND no partial
            :app is recorded; the realm's registrar + :app slot never disagree
            (rf2-9swh84, EP-0013 §Installation step 4 'attach atomically')"
    ;; A hermetic constructed realm so its OWN registrar is cleanly inspectable
    ;; (empty before install; rollback must return it to empty).
    (let [r (rf/realm {:id :atomic/r})]
      (try
        (is (= {} @(realm/registrar r))
            "the hermetic realm's registrar starts empty")
        (is (nil? (:app (realm/realm :atomic/r)))
            "no :app stored before install")
        ;; A 3-descriptor app — the seating loop seats them one at a time. We
        ;; force a throw on the SECOND register! so the first descriptor has
        ;; ALREADY landed in the realm's registrar when the loop blows up — the
        ;; exact partial-install edge: registrar half-populated, :app not yet set.
        (let [a (rf/app {:id :atomic/app :modules
                         [(rf/module {:id :m
                                      :events {:atomic/e1 {:handler cart-add}
                                               :atomic/e2 {:handler cart-add}
                                               :atomic/e3 {:handler cart-add}}})]})
              calls (atom 0)
              real-register! registrar/register!
              ;; Redef the ACTUAL lowering target so we hit the real
              ;; install! → seat-into-realm! → register-descriptor! → register!
              ;; path (not a routed-around stub): seat the first descriptor for
              ;; real, then throw mid-loop.
              ed (with-redefs [registrar/register!
                               (fn [kind id metadata]
                                 (if (= 2 (swap! calls inc))
                                   (throw (ex-info "boom: malformed descriptor mid-loop"
                                                   {:error/id :atomic.test/boom :kind kind :id id}))
                                   (real-register! kind id metadata)))]
                   (try (rf/install! r a)
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                          (ex-data e))))]
          (is (= :atomic.test/boom (:error/id ed))
              "the mid-loop throw propagated out of install! (not swallowed)")
          (is (>= @calls 2)
              "the loop got at least two descriptors deep before the throw")
          ;; ROLLBACK: the realm's registrar is back to its pre-install state —
          ;; the first descriptor that DID land was rolled back, none leaked.
          (is (= {} @(realm/registrar r))
              "the realm's registrar is rolled back to empty — no half-populated table")
          (is (nil? (get-in @(realm/registrar r) [:event :atomic/e1]))
              "the descriptor that landed before the throw did not leak")
          ;; ATOMIC :app: set-installed-app! runs only after a clean seating, so
          ;; the failed install recorded no :app — the registrar + :app agree.
          (is (nil? (:app (realm/realm :atomic/r)))
              "no partial :app was recorded — set-installed-app! never ran")
          ;; installed-app falls back to the recomputable projection when no
          ;; :app is stored; after the rollback that projection is the EMPTY
          ;; program (the rolled-back registrar), confirming no leak.
          (is (= {} (:registrations (realm/installed-app r)))
              "the realm's installed-app projects an empty program after rollback"))
        (finally (rf/dispose-realm! :atomic/r))))))

(deftest install-succeeds-when-the-realm-satisfies-requires
  (testing "install! succeeds once the realm provides the required capability"
    (with-default-realm-capabilities! {:rf.capability/http {:request! identity}})
    (try
      (let [needs-http (rf/module {:id :shop/cart
                                   :requires #{:rf.capability/http}
                                   :events {:cart/add {:handler cart-add}}})
            a (rf/app {:id :shop/app :modules [needs-http]})]
        (rf/install! a)
        (is (identical? cart-add (registrar/handler :event :cart/add))
            "the descriptor is seated once the capability is satisfied"))
      (finally (clear-default-realm-capabilities!)))))

(deftest install-no-requirements-needs-no-capabilities
  (testing "an app that requires nothing installs into a realm with no
            capabilities — the check is a no-op"
    (clear-default-realm-capabilities!)
    (let [a (rf/app {:id :shop/app
                     :modules [(rf/module {:id :m :events {:e {:handler cart-add}}})]})]
      (rf/install! a)
      (is (identical? cart-add (registrar/handler :event :e))
          "no :requires means no capability gate"))))

;; ---------------------------------------------------------------------------
;; (3) zero ergonomic regression — the reg-* sugar path is byte-identical
;; ---------------------------------------------------------------------------

(deftest sugar-path-and-install-resolve-through-the-same-registrar
  (testing "an installed descriptor and a reg-*-registered one resolve through
            the SAME registrar lookup — install! is the explicit seating path,
            the ordinary sugar path is unchanged"
    ;; Sugar path: ordinary reg-event-db writes the default realm's registrar.
    (rf/reg-event-db :sugar/inc {:doc "sugar"} cart-add)
    ;; Explicit path: install! seats a descriptor into the SAME registrar.
    (rf/install! (rf/app {:id :a :modules
                          [(rf/module {:id :m :events {:installed/inc {:handler cart-add}}})]}))
    ;; Both resolve identically through registrar/handler — one registrar, two
    ;; ways to populate it.
    (is (identical? cart-add (registrar/handler :event :sugar/inc))
        "the sugar-path registration resolves")
    (is (identical? cart-add (registrar/handler :event :installed/inc))
        "the installed registration resolves through the same lookup")
    ;; The projection (stage 5) sees BOTH — the registrar is the single source
    ;; of truth regardless of how it was populated.
    (let [proj (av/app-value)]
      (is (contains? (get-in proj [:registrations :event]) :sugar/inc)
          "the projection reflects the sugar-path registration")
      (is (contains? (get-in proj [:registrations :event]) :installed/inc)
          "the projection reflects the installed registration"))))

(deftest install-fires-the-registration-trace-like-the-sugar-path
  (testing "install! routes through registrar/register!, so a seated descriptor
            is dispatchable exactly as a reg-* one — full end-to-end through the
            default-realm frame (the zero-ergonomic-regression headline)"
    (rf/reg-frame :install/app {:doc "install app"})
    (rf/install! (rf/app {:id :a :modules
                          [(rf/module {:id :m
                                       :events {:install/set {:handler (fn [db [_ v]] (assoc db :n v))}}
                                       :subs   {:install/read {:handler (fn [db _] (:n db))}}})]}))
    (rf/dispatch-sync [:install/set 42] {:frame :install/app})
    (is (= {:n 42} (rf/app-db-value :install/app))
        "an installed event handler dispatches like a reg-* one")
    (let [reaction (rf/subscribe :install/app [:install/read])]
      (is (= 42 @reaction)
          "an installed sub resolves like a reg-* one"))))

;; ---------------------------------------------------------------------------
;; (4) reinstall! — hot-reload as an app-value diff
;; ---------------------------------------------------------------------------

(deftest reinstall-diffs-added-changed-removed
  (testing "reinstall! diffs the new app value against the installed one and
            applies the delta: :added registered, :changed re-registered,
            :removed unregistered (the EP's hot-reload diff value)"
    ;; v1: cart/add + cart/legacy.
    (rf/install! (rf/app {:id :shop/app :modules
                          [(rf/module {:id :shop/cart
                                       :events {:cart/add    {:handler cart-add}
                                                :cart/legacy {:handler cart-add}}})]}))
    (is (identical? cart-add (registrar/handler :event :cart/legacy)))
    ;; v2: cart/add CHANGED (new handler), cart/remove ADDED, cart/legacy REMOVED.
    (let [diff (rf/reinstall!
                 realm/default-realm-id
                 (rf/app {:id :shop/app :modules
                          [(rf/module {:id :shop/cart
                                       :events {:cart/add    {:handler cart-remove}
                                                :cart/remove {:handler cart-remove}}})]})
                 {:reason :hot-reload})]
      (is (= [[:event :cart/remove]] (:added diff)) ":added names the new id")
      (is (= [[:event :cart/add]] (:changed diff))
          ":changed names the id whose descriptor differs")
      (is (= [[:event :cart/legacy]] (:removed diff)) ":removed names the dropped id")
      (is (= :rf.realm/default (:realm diff)) "the diff names the realm")
      (is (= :hot-reload (:reason diff)) "the diff echoes the :reason")
      ;; And the registrar reflects the delta.
      (is (identical? cart-remove (registrar/handler :event :cart/add))
          "the changed handler is now the new fn (future lookups use it)")
      (is (identical? cart-remove (registrar/handler :event :cart/remove))
          "the added handler is seated")
      (is (nil? (registrar/lookup :event :cart/legacy))
          "the removed registration fails loudly on future lookup (unregistered)"))))

(deftest reinstall-one-arity-defaults-to-the-default-realm
  (testing "the 1-arity (reinstall! new-app) targets the default realm and
            defaults :reason to :hot-reload"
    (rf/install! (rf/app {:id :app :modules
                          [(rf/module {:id :m :events {:e {:handler cart-add}}})]}))
    (let [diff (rf/reinstall! (rf/app {:id :app :modules
                                       [(rf/module {:id :m :events {:e2 {:handler cart-add}}})]}))]
      (is (= :rf.realm/default (:realm diff)) "defaults to the default realm")
      (is (= :hot-reload (:reason diff)) ":reason defaults to :hot-reload")
      (is (= [[:event :e2]] (:added diff)))
      (is (= [[:event :e]] (:removed diff))))))

(deftest reinstall-stores-the-new-app-value
  (testing "reinstall! records the new app value in the realm's :app slot"
    (let [v1 (rf/app {:id :app :modules
                      [(rf/module {:id :m :events {:e {:handler cart-add}}})]})
          v2 (rf/app {:id :app :modules
                      [(rf/module {:id :m :events {:e {:handler cart-remove}}})]})]
      (rf/install! v1)
      (is (= v1 (realm/installed-app)))
      (rf/reinstall! v2)
      (is (= v2 (realm/installed-app))
          "the realm now holds the reinstalled app value"))))

(deftest reinstall-rechecks-capabilities
  (testing "reinstall! re-runs the capability check — a reload that adds an
            unmet requirement throws before any mutation"
    (clear-default-realm-capabilities!)
    (rf/install! (rf/app {:id :app :modules
                          [(rf/module {:id :m :events {:e {:handler cart-add}}})]}))
    (let [v2 (rf/app {:id :app :modules
                      [(rf/module {:id :m
                                   :requires #{:rf.capability/http}
                                   :events {:e {:handler cart-remove}}})]})
          ed (try (rf/reinstall! v2)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/missing-capability (:error/id ed))
          "a reinstall that raises an unmet requirement throws")
      ;; The pre-reinstall handler is untouched — the check ran before mutation.
      (is (identical? cart-add (registrar/handler :event :e))
          "the installed handler is unchanged — the failed reinstall was atomic"))))

(deftest reinstall-after-pure-sugar-boot-diffs-against-the-projection
  (testing "the FIRST reinstall after a pure-sugar boot diffs against the live
            projection (no prior install! stored an :app) — so a hot-reload of a
            sugar-booted app still computes a correct delta"
    ;; Pure-sugar boot: no install!, just reg-*.
    (rf/reg-event-db :boot/a {:doc "a"} cart-add)
    ;; Reinstall a new app value. The diff is against the projection of the
    ;; sugar-booted registrar, so :boot/a (present in the projection, absent in
    ;; the new app) shows as :removed, and :boot/b shows as :added.
    (let [diff (rf/reinstall! (rf/app {:id :app :modules
                                       [(rf/module {:id :m :events {:boot/b {:handler cart-add}}})]}))]
      (is (some #{[:event :boot/b]} (:added diff))
          ":boot/b is added relative to the projected installed app")
      (is (some #{[:event :boot/a]} (:removed diff))
          ":boot/a (sugar-booted, in the projection) is removed by the reinstall"))))

;; ---------------------------------------------------------------------------
;; (5) descriptor round-trip — lowering is the inverse of normalisation
;; ---------------------------------------------------------------------------

(deftest descriptor-lowering-round-trips-through-the-projection
  (testing "a descriptor lowered by install! and re-projected (stage 5) carries
            the same handler / metadata / source — descriptor->registration-
            metadata is the inverse of the projection's normalisation"
    (let [src {:ns 'shop.cart :file "cart.cljs" :line 9 :column 1}
          a   (rf/app {:id :app :modules
                       [(rf/module {:id :m
                                    :events {:rt/e {:doc "round-trip"
                                                    :handler cart-add
                                                    :source src}}})]})]
      (rf/install! a)
      (let [proj-d (get-in (av/app-value) [:registrations :event :rt/e])]
        (is (identical? cart-add (:handler proj-d))
            "the handler survives the lower→register→project round-trip")
        (is (= "round-trip" (get-in proj-d [:metadata :doc]))
            "the metadata survives the round-trip")
        (is (= src (:source proj-d))
            "the source-coord envelope survives the round-trip")))))

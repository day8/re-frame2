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
            [re-frame.frame :as frame]
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

(deftest reinstall-removed-id-fails-loud-on-dispatch
  (testing "the EP-0013 §Hot Reload rule the structural lookup→nil proxy only
            approximates: after reinstall! DROPS an event id (:removed), a real
            DISPATCH against the dropped id FAILS LOUD — it fans
            :rf.error/no-such-handler through the always-on error-emit listener
            (the documented loud-failure path; a frameful dispatch to a
            never-registered handler is recovered as a no-op, NOT silently run)
            (rf2-q4x5zz)"
    ;; A default-realm frame so the dispatch resolves a frame (the no-such-handler
    ;; path is reached only AFTER the target frame is resolved — a frameless
    ;; dispatch raises :rf.error/no-frame-context earlier).
    (rf/reg-frame :q4x5zz/app {:doc "drop-then-dispatch"})
    ;; v1: install an event id that mutates app-db, and prove it dispatches.
    (rf/install! :rf.realm/default
                 (rf/app {:id :q4x5zz/app :modules
                          [(rf/module {:id :m
                                       :events {:q4x5zz/ev {:handler (fn [db _] (assoc db :ran? true))}}})]}))
    (rf/dispatch-sync [:q4x5zz/ev] {:frame :q4x5zz/app})
    (is (true? (:ran? (rf/app-db-value :q4x5zz/app)))
        "the installed handler ran while registered")
    ;; v2: reinstall WITHOUT :q4x5zz/ev — it is :removed (unregistered).
    (let [diff (rf/reinstall! :rf.realm/default
                              (rf/app {:id :q4x5zz/app :modules
                                       [(rf/module {:id :m
                                                    :events {:q4x5zz/other {:handler (fn [db _] db)}}})]}))]
      (is (= [[:event :q4x5zz/ev]] (:removed diff)) ":q4x5zz/ev is dropped"))
    ;; Structural proxy (what the existing test asserts): the slot is gone.
    (is (nil? (registrar/lookup :event :q4x5zz/ev))
        "the dropped id is unregistered (the structural lookup→nil proxy)")
    ;; ADVERSARIAL assertion: a real dispatch against the dropped id fails loud.
    (let [seen (atom [])]
      (rf/register-error-listener! :q4x5zz/recorder (fn [r] (swap! seen conj r)))
      (try
        (rf/dispatch-sync [:q4x5zz/ev] {:frame :q4x5zz/app})
        (let [err (some (fn [r] (when (= :rf.error/no-such-handler (:error r)) r)) @seen)]
          (is (some? err)
              "dispatching the dropped id fans :rf.error/no-such-handler through the
               always-on listener — the loud failure the §Hot Reload bullet names")
          (is (= :q4x5zz/ev (:event-id err)) "the error names the dropped event id")
          (is (= :q4x5zz/app (:frame err)) "the error names the target frame"))
        ;; And the dropped handler did NOT silently run — the no-op recovery
        ;; left app-db untouched (no stale handler firing).
        (is (true? (:ran? (rf/app-db-value :q4x5zz/app)))
            "app-db carries only the pre-removal write — the dropped handler did not run")
        (finally (rf/unregister-error-listener! :q4x5zz/recorder))))))

;; ---------------------------------------------------------------------------
;; (4b) reinstall! — the ONE reachable live-instance edge: :frame removal
;; (EP-0013 issue 12 PRECISED — rf2-7zn9kg)
;; ---------------------------------------------------------------------------

(deftest reinstall-removing-a-live-frame-refuses-loudly
  (testing "EP-0013 issue 12 (PRECISED, rf2-7zn9kg): :frame is the one wired kind
            that IS a live instance. A reinstall! whose diff would :removed a
            :frame that still backs a LIVE container REFUSES LOUDLY with
            :rf.error/live-frame-removal-unsupported — enumerating the blocking
            frame-id — BEFORE any mutation, rather than silently ORPHANING the
            container (registrar/unregister! :frame alone drops only the slot,
            leaving the container live + dispatchable with destroy-frame!'s
            :on-destroy / machine teardown / sub-cache disposal SKIPPED)."
    ;; v1: install an app whose only registration is a :frame — a real live
    ;; container exists in the core frame registry after install.
    (rf/install! :rf.realm/default
                 (rf/app {:id :fr0/app :modules
                          [(rf/module {:id :m :frames {:fr0/live {:doc "live frame"}}})]}))
    (is (some? (frame/frame :fr0/live)) "the installed :frame is a live container")
    (is (contains? (frame/frame-ids) :fr0/live) "and appears in the live registry")
    ;; v2: reinstall WITHOUT :fr0/live — the diff would :removed it, but the
    ;; container is live → refuse loudly before any mutation.
    (let [ed (try (rf/reinstall! :rf.realm/default
                                 (rf/app {:id :fr0/app :modules
                                          [(rf/module {:id :m2
                                                       :events {:fr0/ev {:handler (fn [db _] db)}}})]}))
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/live-frame-removal-unsupported (:error/id ed))
          "removing a live :frame refuses loudly with the targeted error id")
      (is (= [:fr0/live] (:live-frames ed))
          "the diagnostic enumerates exactly the blocking live frame-id")
      (is (= :destroy-frame-then-reinstall (:recovery ed))
          "the error names the recovery: destroy-frame! then reinstall"))
    ;; The refusal was BEFORE any mutation — the live frame + its registrar slot
    ;; are untouched, and the new :added event was NOT seated.
    (is (some? (frame/frame :fr0/live)) "the live frame survives the refused reinstall")
    (is (some? (registrar/lookup :frame :fr0/live)) "its registrar slot is untouched")
    (is (nil? (registrar/lookup :event :fr0/ev))
        "the refused reinstall seated nothing — it failed before any mutation")))

(deftest reinstall-removing-a-frame-after-explicit-destroy-succeeds
  (testing "EP-0013 issue 12 (PRECISED, rf2-7zn9kg): the recovery path — once the
            frame is explicitly destroyed (destroy-frame!, the proper teardown),
            its container is no longer live, so a reinstall! that removes the
            :frame descriptor succeeds and the slot is dropped cleanly."
    (rf/install! :rf.realm/default
                 (rf/app {:id :fr1/app :modules
                          [(rf/module {:id :m :frames {:fr1/live {:doc "to be destroyed"}}})]}))
    (is (some? (frame/frame :fr1/live)) "the frame is live after install")
    ;; The documented recovery: destroy the frame first (full teardown), THEN
    ;; reinstall without it.
    (frame/destroy-frame! :fr1/live)
    (is (nil? (frame/frame :fr1/live)) "destroy-frame! tore the container down")
    (let [diff (rf/reinstall! :rf.realm/default
                              (rf/app {:id :fr1/app :modules
                                       [(rf/module {:id :m2
                                                    :events {:fr1/ev {:handler (fn [db _] db)}}})]}))]
      ;; The :frame is :removed (its registrar slot dropped) — no live container
      ;; blocks it, so the reinstall applies the delta cleanly.
      (is (some #{[:frame :fr1/live]} (:removed diff))
          "the now-dead :frame is removed cleanly")
      (is (some #{[:event :fr1/ev]} (:added diff)) "the new event was added")
      (is (nil? (registrar/lookup :frame :fr1/live)) "the :frame registrar slot is dropped")
      (is (some? (registrar/lookup :event :fr1/ev)) "the new event handler is seated"))))

(deftest reinstall-changed-sub-invalidates-the-live-cache
  (testing "the EP-0013 §Hot Reload behavioural claim the registrar-slot
            assertion only approximates: a :changed sub reinstall INVALIDATES the
            LIVE per-frame sub-cache (via the registrar's existing
            replacement-hook surface), so a frame holding an ACTIVE subscription
            recomputes against the NEW handler on the next deref — not just the
            registrar slot rotating (rf2-s7dcu8).

            Both handlers ignore db and return a constant, so the only way the
            derefed value can change is cache invalidation + the swapped handler
            — proving the live cache refreshed, not that app-db moved."
    (rf/reg-frame :s7dcu8/app {:doc "live-sub-reload"})
    ;; v1: a sub that yields a constant :v1.
    (rf/install! :rf.realm/default
                 (rf/app {:id :s7dcu8/app :modules
                          [(rf/module {:id :m :subs {:s7dcu8/read {:handler (fn [_db _] :v1)}}})]}))
    ;; Hold an ACTIVE subscription and deref it — this POPULATES the live cache.
    (let [reaction (rf/subscribe :s7dcu8/app [:s7dcu8/read])]
      (is (= :v1 @reaction) "the active subscription yields v1 (cache populated)")
      ;; v2: the SAME sub id, a CHANGED handler yielding :v2 — :changed in the diff.
      (let [diff (rf/reinstall! :rf.realm/default
                                (rf/app {:id :s7dcu8/app :modules
                                         [(rf/module {:id :m :subs {:s7dcu8/read {:handler (fn [_db _] :v2)}}})]}))]
        (is (= [[:sub :s7dcu8/read]] (:changed diff))
            ":s7dcu8/read is :changed (a different handler descriptor)"))
      ;; Registrar slot rotated (the assertion the existing tests already make).
      (is (= :v2 ((registrar/handler :sub :s7dcu8/read) nil nil))
          "the registrar slot holds the new handler")
      ;; THE LIVE CONSEQUENCE: a fresh subscribe recomputes against the new
      ;; handler — the cached entry was invalidated by the replacement hook, so
      ;; the reactive read returns v2, not the stale cached v1.
      (is (= :v2 @(rf/subscribe :s7dcu8/app [:s7dcu8/read]))
          "the live sub-cache was invalidated — the reactive read yields the new value"))))

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
    ;; the new app) shows as :removed, and :boot/b shows as :added. The new app
    ;; RE-DECLARES the fixture's live `:rf/default` frame (in the projection too)
    ;; so it stays unchanged, not :removed — otherwise the reinstall would
    ;; (correctly) refuse to orphan that live frame (rf2-7zn9kg).
    (let [diff (rf/reinstall! (rf/app {:id :app :modules
                                       [(rf/module {:id :m
                                                    :events {:boot/b {:handler cart-add}}
                                                    :frames {:rf/default {}}})]}))]
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

;; ---------------------------------------------------------------------------
;; (6) KIND COVERAGE of the install! lowering seam (rf2-xmslkr)
;; ---------------------------------------------------------------------------
;;
;; `core/install-descriptor!` special-cases :event/:sub/:fx/:cofx through their
;; REAL registration logic (the event interceptor wrap, the sub input-signal
;; parse, the fx/cofx slot stamp); every OTHER kind falls back to the FLAT
;; registrar lowering (`descriptor->registration-metadata` → `registrar/register!`).
;; These tests pin (a) all four wired kinds lower correctly through a single
;; install!, (b) the unknown-realm→global fallback, and (c) CHARACTERIZE the
;; flat-fallback gap for a non-wired kind that carries real registration logic
;; (the audit's concern — the flat path seats a registrar slot WITHOUT running
;; the subsystem's own registration, so the slot is malformed for the subsystem).

(deftest install-lowers-the-four-wired-kinds-through-real-registration-logic
  (testing "a single install! lowers :event/:sub/:fx/:cofx descriptors through
            their real reg-* logic — each resolves through the registrar exactly
            as a reg-* call would (rf2-xmslkr kind-coverage, the wired set)"
    (let [ev-h   (fn [db _] (assoc db :ev? true))
          sub-h  (fn [_db _] :sub-val)
          fx-h   (fn [_] nil)
          cofx-h (fn [coeffects] coeffects)]
      (rf/install! (rf/app {:id :xms/wired :modules
                            [(rf/module {:id :m
                                         :events {:xms/ev   {:handler ev-h}}
                                         :subs   {:xms/sub  {:handler sub-h}}
                                         :fx     {:xms/fx   {:handler fx-h}}
                                         :cofx   {:xms/cofx {:handler cofx-h}}})]}))
      (is (identical? ev-h (registrar/handler :event :xms/ev))
          ":event lowered through reg-event-db (resolvable handler)")
      (is (identical? sub-h (registrar/handler :sub :xms/sub))
          ":sub lowered through reg-sub")
      (is (identical? fx-h (registrar/handler :fx :xms/fx))
          ":fx lowered through reg-fx")
      (is (identical? cofx-h (registrar/handler :cofx :xms/cofx))
          ":cofx lowered through reg-cofx")
      ;; The :event lowered through the REAL path carries the wrapped slots a
      ;; reg-event-db produces (the interceptor chain), not just a flat handler —
      ;; the distinction the wired path exists to provide.
      (is (contains? (registrar/lookup :event :xms/ev) :interceptors)
          ":event seated the kind-appropriate interceptor chain (real reg-* logic ran)"))))

(deftest install-event-kind-tag-routes-through-reg-event-fx
  (testing "a module event entry tagged {:event/kind :fx} lowers through
            reg-event-fx (the install-descriptor! :event/kind read), so the
            wired-kind seam honours the event sub-kind (rf2-xmslkr)"
    (rf/install! (rf/app {:id :xms/evfx :modules
                          [(rf/module {:id :m
                                       :events {:xms/evfx {:event/kind :fx
                                                           :handler (fn [_cofx _ev] {})}}})]}))
    (is (some? (registrar/handler :event :xms/evfx))
        "the :event/kind :fx entry is seated as a resolvable event handler")
    (is (contains? (registrar/lookup :event :xms/evfx) :interceptors)
        "it ran through reg-event-fx (interceptor chain seated), not the flat path")))

(deftest install-into-an-unknown-realm-id-falls-back-to-the-global-registrar
  (testing "seat-into-realm! documents 'a realm with no registry entry (unknown
            id) falls back to the global atom rather than throwing — the
            absence-is-default rule.' Installing into a NEVER-CONSTRUCTED realm
            id seats into the DEFAULT/global registrar (rf2-xmslkr)"
    ;; :xms/ghost was never `rf/realm`-constructed, so it has no registry entry.
    (is (nil? (realm/realm :xms/ghost)) "the realm id was never constructed")
    (let [h (fn [db _] db)]
      (rf/install! :xms/ghost
                   (rf/app {:id :xms/ghost-app :modules
                            [(rf/module {:id :m :events {:xms/ghost-ev {:handler h}}})]}))
      ;; The descriptor seated into the GLOBAL registrar (the default realm's
      ;; atom), per the absence-is-default fallback — not into a phantom table.
      (is (identical? h (registrar/handler :event :xms/ghost-ev))
          "the unknown-realm install seated into the global/default registrar")
      (is (= h (get-in @registrar/kind->id->metadata [:event :xms/ghost-ev :handler-fn]))
          "it is the process-global atom, confirming the absence-is-default fallback"))))

(deftest install-wires-the-frame-kind-through-reg-frame
  (testing "rf2-chc8vs: :frame is an EP-0013 step-7 FIRST-format kind, so a
            CONSTRUCTED :frame descriptor is now lowered through reg-frame's REAL
            registration logic — install! creates a real frame CONTAINER (not the
            malformed flat registrar slot the prior slice left). The frame
            appears in `frame-ids`, `frame/frame` resolves a live container, and
            it is dispatchable. This FLIPS the rf2-xmslkr characterization test
            that pinned `frame/frame -> nil` as a known limitation."
    (rf/install! (rf/app {:id :xms/frame-app :modules
                          [(rf/module {:id :m :frames {:xms/frame {:doc "constructed frame"}}})]}))
    ;; The registrar slot is written (reg-frame's first step) — the frame kind is
    ;; a registrar kind, so the (kind,id) table carries the config.
    (is (some? (registrar/lookup :frame :xms/frame))
        "reg-frame wrote the :frame registrar slot")
    ;; And — the rf2-chc8vs fix — a REAL frame container now exists: reg-frame's
    ;; full registration logic ran (container create + :on-create + classify).
    (is (some? (frame/frame :xms/frame))
        "a real frame container exists — reg-frame's registration logic ran")
    (is (contains? (frame/frame-ids) :xms/frame)
        "the installed frame appears in the live frame registry")
    ;; The seated config round-trips (the :doc the module carried).
    (is (= "constructed frame" (:doc (frame/frame-meta :xms/frame)))
        "the constructed frame's config was seated through reg-frame")
    ;; End-to-end: the installed frame is dispatchable / subscribable, proving it
    ;; is a real frame and not a malformed slot.
    (rf/install! (rf/app {:id :xms/frame-prog :modules
                          [(rf/module {:id :m2
                                       :events {:xms/frame-set {:handler (fn [db [_ v]] (assoc db :n v))}}
                                       :subs   {:xms/frame-read {:handler (fn [db _] (:n db))}}})]}))
    (rf/dispatch-sync [:xms/frame-set 7] {:frame :xms/frame})
    (is (= {:n 7} (rf/app-db-value :xms/frame))
        "the install!-seated frame dispatches an installed event handler")
    (is (= 7 @(rf/subscribe :xms/frame [:xms/frame-read]))
        "the install!-seated frame resolves an installed subscription")))

(deftest install-of-a-step8-deferred-kind-refuses-loudly
  (testing "rf2-chc8vs: a CONSTRUCTED descriptor of an EP-0013 step-8 DEFERRED
            kind (:route/:flow/:resource/:mutation/:view/:head/:error-projector/
            :resource-scope) — each carries real registration logic the flat
            lowering bypasses — REFUSES LOUDLY at install!: a diagnosable
            :rf.error/unsupported-descriptor-kind naming the kind + the wired set
            + that it is a later slice, rather than silently seating a malformed
            flat slot (fail-closed per EP-0013 issue-12 + no-silent-swallow)."
    (doseq [[section kind] [[:routes :route]
                            [:flows :flow]
                            [:resources :resource]
                            [:mutations :mutation]
                            [:resource-scopes :resource-scope]
                            [:views :view]
                            [:heads :head]
                            [:error-projectors :error-projector]]]
      (let [a  (rf/app {:id :xms/deferred-app :modules
                        [(rf/module (assoc {:id :m}
                                           section {:xms/deferred {:handler (fn [& _] nil)}}))]})
            ed (try (rf/install! a)
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                      (ex-data e)))]
        (is (= :rf.error/unsupported-descriptor-kind (:error/id ed))
            (str "install! of a " kind " descriptor refuses loudly"))
        (is (= kind (:kind ed))
            (str "the refusal names the unsupported kind " kind))
        (is (contains? (:deferred ed) kind)
            "the refusal enumerates the deferred set")
        (is (contains? (:wired ed) :frame)
            "the refusal enumerates the wired set (now including :frame)")
        ;; Atomic: the refusal threw before recording an :app, and rolled back any
        ;; seating — no descriptor of the refused kind leaked into the registrar.
        (is (nil? (registrar/lookup kind :xms/deferred))
            (str "no malformed " kind " slot was seated — the refusal was loud, not silent"))))))

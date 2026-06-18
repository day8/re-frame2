(ns re-frame.app-value-install-cljs-test
  "EP-0013 D2 stage-7 — `install!` / `reinstall!`: seat an immutable app value
  into a realm (rf2-xq4go0, the LAST D2 slice, built on the merged stage-5
  projection + stage-6 construction).

  Stage 7 closes the D2 loop — the program is a value (stage 6 construction),
  and the runtime is a container you install it into (stage 7). The public
  surface (re-exported from `re-frame.core` as `av/install!` / `av/reinstall!`):

    (1) `install!` seats an app value as a realm's program — lowers every
        descriptor back into the realm's registrar (the inverse of the stage-5/6
        descriptor normalisation) so the program is dispatch/subscribe/resolve-
        able, and records the seated value in the realm's `:app` slot;
    (2) the CAPABILITY CHECK runs FIRST — the app value's `:rf.app/requires` must be
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
        `installed-app` returns the LIVE registrar projection ENRICHED with the
        seated value's rich provenance (`:modules` + `:owner`-stamped
        descriptors), so coexisting `reg-*` sugar stays visible and the public
        read never desyncs from `av/app-value` / dispatch (rf2-77ewnm).

  Dual-runtime `*_cljs_test.cljc` — the shadow-cljs `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Pure CLJC, no DOM."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.app-value :as av]
            [re-frame.events :as events]
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
(defn- cart-add   [{:keys [db]} _] {:db (update db :items (fnil conj []) :x)})
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
    (let [cart (av/module {:id :shop/cart
                           :events {:cart/add {:doc "Add." :handler cart-add}}
                           :subs   {:cart/items {:doc "Items." :handler cart-items}}})
          a    (av/app {:id :shop/app :modules [cart]})]
      (av/install! a)
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
            value verbatim) so the call composes, and installed-app reconciles
            that seated value with the live projection — reporting the seated
            identity + :modules provenance over the live registrar (rf2-77ewnm)"
    (let [cart (av/module {:id :shop/cart :events {:cart/add {:handler cart-add}}})
          a    (av/app {:id :shop/app :modules [cart]})
          ret  (av/install! a)]
      (is (= a (:app ret)) "install! returns the realm with the app in :app (verbatim)")
      (is (= :shop/app (:rf.app/id (realm/installed-app)))
          "installed-app reports the seated app's identity")
      (is (= {:shop/cart cart} (:modules (realm/installed-app)))
          "the installed app value carries its :modules (the rich constructed
           provenance, overlaid onto the live projection)"))))

;; ---------------------------------------------------------------------------
;; (2) the capability check — fail LOUD on an unmet :rf.app/requires, before mutation
;; ---------------------------------------------------------------------------

(deftest install-throws-on-unmet-capability-before-any-mutation
  (testing "install! capability-checks FIRST — an app requiring a capability
            the realm does not provide throws :rf.error/missing-capability,
            naming the realm + capability, and registers NOTHING (the under-
            provisioned app never becomes partially visible)"
    (clear-default-realm-capabilities!)
    (let [needs-http (av/module {:id :shop/cart
                                 :rf.module/requires #{:rf.capability/http}
                                 :events {:cart/add {:handler cart-add}}})
          a  (av/app {:id :shop/app :modules [needs-http]})
          ed (try (av/install! a)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/missing-capability (:rf.error/id ed))
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
    (let [r (realm/construct-realm {:id :atomic/r})]
      (try
        (is (= {} @(realm/registrar r))
            "the hermetic realm's registrar starts empty")
        (is (nil? (:app (realm/realm :atomic/r)))
            "no :app stored before install")
        ;; A 3-descriptor app — the seating loop seats them one at a time. We
        ;; force a throw on the SECOND register! so the first descriptor has
        ;; ALREADY landed in the realm's registrar when the loop blows up — the
        ;; exact partial-install edge: registrar half-populated, :app not yet set.
        (let [a (av/app {:id :atomic/app :modules
                         [(av/module {:id :m
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
                   (try (av/install! r a)
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
        (finally (realm/dispose-realm! :atomic/r))))))

(deftest install-succeeds-when-the-realm-satisfies-requires
  (testing "install! succeeds once the realm provides the required capability"
    (with-default-realm-capabilities! {:rf.capability/http {:request! identity}})
    (try
      (let [needs-http (av/module {:id :shop/cart
                                   :rf.module/requires #{:rf.capability/http}
                                   :events {:cart/add {:handler cart-add}}})
            a (av/app {:id :shop/app :modules [needs-http]})]
        (av/install! a)
        (is (identical? cart-add (registrar/handler :event :cart/add))
            "the descriptor is seated once the capability is satisfied"))
      (finally (clear-default-realm-capabilities!)))))

(deftest install-no-requirements-needs-no-capabilities
  (testing "an app that requires nothing installs into a realm with no
            capabilities — the check is a no-op"
    (clear-default-realm-capabilities!)
    (let [a (av/app {:id :shop/app
                     :modules [(av/module {:id :m :events {:e {:handler cart-add}}})]})]
      (av/install! a)
      (is (identical? cart-add (registrar/handler :event :e))
          "no :rf.module/requires means no capability gate"))))

;; ---------------------------------------------------------------------------
;; (3) zero ergonomic regression — the reg-* sugar path is byte-identical
;; ---------------------------------------------------------------------------

(deftest sugar-path-and-install-resolve-through-the-same-registrar
  (testing "an installed descriptor and a reg-*-registered one resolve through
            the SAME registrar lookup — install! is the explicit seating path,
            the ordinary sugar path is unchanged"
    ;; Sugar path: ordinary reg-event writes the default realm's registrar.
    (rf/reg-event :sugar/inc {:doc "sugar"} cart-add)
    ;; Explicit path: install! seats a descriptor into the SAME registrar.
    (av/install! (av/app {:id :a :modules
                          [(av/module {:id :m :events {:installed/inc {:handler cart-add}}})]}))
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
          "the projection reflects the installed registration"))
    ;; rf2-77ewnm: the PUBLIC read seam (realm/installed-app) must see BOTH too —
    ;; not just av/app-value. After a stored :app exists, the reconciled read is
    ;; the live projection enriched with provenance, so it agrees with
    ;; av/app-value on the registration set (no public-vs-internal desync).
    (let [public-events (get-in (realm/installed-app) [:registrations :event])]
      (is (contains? public-events :sugar/inc)
          "realm/installed-app reflects the coexisting sugar registration")
      (is (contains? public-events :installed/inc)
          "realm/installed-app reflects the installed registration")
      (is (= (set (keys (get-in (av/app-value) [:registrations :event])))
             (set (keys public-events)))
          "realm/installed-app and av/app-value agree on the event set (no desync)"))))

(deftest install-fires-the-registration-trace-like-the-sugar-path
  (testing "install! routes through registrar/register!, so a seated descriptor
            is dispatchable exactly as a reg-* one — full end-to-end through the
            default-realm frame (the zero-ergonomic-regression headline)"
    (rf/reg-frame :install/app {:doc "install app"})
    (av/install! (av/app {:id :a :modules
                          [(av/module {:id :m
                                       :events {:install/set {:handler (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)})}}
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
    (av/install! (av/app {:id :shop/app :modules
                          [(av/module {:id :shop/cart
                                       :events {:cart/add    {:handler cart-add}
                                                :cart/legacy {:handler cart-add}}})]}))
    (is (identical? cart-add (registrar/handler :event :cart/legacy)))
    ;; v2: cart/add CHANGED (new handler), cart/remove ADDED, cart/legacy REMOVED.
    (let [diff (av/reinstall!
                 realm/default-realm-id
                 (av/app {:id :shop/app :modules
                          [(av/module {:id :shop/cart
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
    (av/install! :rf.realm/default
                 (av/app {:id :q4x5zz/app :modules
                          [(av/module {:id :m
                                       :events {:q4x5zz/ev {:handler (fn [{:keys [db]} _] {:db (assoc db :ran? true)})}}})]}))
    (rf/dispatch-sync [:q4x5zz/ev] {:frame :q4x5zz/app})
    (is (true? (:ran? (rf/app-db-value :q4x5zz/app)))
        "the installed handler ran while registered")
    ;; v2: reinstall WITHOUT :q4x5zz/ev — it is :removed (unregistered).
    (let [diff (av/reinstall! :rf.realm/default
                              (av/app {:id :q4x5zz/app :modules
                                       [(av/module {:id :m
                                                    :events {:q4x5zz/other {:handler (fn [_ _] {})}}})]}))]
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
    (av/install! :rf.realm/default
                 (av/app {:id :fr0/app :modules
                          [(av/module {:id :m :frames {:fr0/live {:doc "live frame"}}})]}))
    (is (some? (frame/frame :fr0/live)) "the installed :frame is a live container")
    (is (contains? (frame/frame-ids) :fr0/live) "and appears in the live registry")
    ;; v2: reinstall WITHOUT :fr0/live — the diff would :removed it, but the
    ;; container is live → refuse loudly before any mutation.
    (let [ed (try (av/reinstall! :rf.realm/default
                                 (av/app {:id :fr0/app :modules
                                          [(av/module {:id :m2
                                                       :events {:fr0/ev {:handler (fn [_ _] {})}}})]}))
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/live-frame-removal-unsupported (:rf.error/id ed))
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
    (av/install! :rf.realm/default
                 (av/app {:id :fr1/app :modules
                          [(av/module {:id :m :frames {:fr1/live {:doc "to be destroyed"}}})]}))
    (is (some? (frame/frame :fr1/live)) "the frame is live after install")
    ;; The documented recovery: destroy the frame first (full teardown), THEN
    ;; reinstall without it.
    (frame/destroy-frame! :fr1/live)
    (is (nil? (frame/frame :fr1/live)) "destroy-frame! tore the container down")
    (let [diff (av/reinstall! :rf.realm/default
                              (av/app {:id :fr1/app :modules
                                       [(av/module {:id :m2
                                                    :events {:fr1/ev {:handler (fn [_ _] {})}}})]}))]
      ;; The :frame is :removed (its registrar slot dropped) — no live container
      ;; blocks it, so the reinstall applies the delta cleanly.
      (is (some #{[:frame :fr1/live]} (:removed diff))
          "the now-dead :frame is removed cleanly")
      (is (some #{[:event :fr1/ev]} (:added diff)) "the new event was added")
      (is (nil? (registrar/lookup :frame :fr1/live)) "the :frame registrar slot is dropped")
      (is (some? (registrar/lookup :event :fr1/ev)) "the new event handler is seated"))))

;; ---------------------------------------------------------------------------
;; (4c) reinstall! — the REMOVAL-path kind boundary: a sugar-registered step-8
;; DEFERRED kind omitted from the new app is REFUSED, not silently unregistered
;; (rf2-cquy9u — symmetric closure of the rf2-7zn9kg kind-boundary ruling)
;; ---------------------------------------------------------------------------

(deftest reinstall-removing-a-sugar-registered-step8-kind-refuses-loudly
  (testing "rf2-cquy9u / rf2-c6armm.9 #1: a step-8 DEFERRED kind registered
            through its OWN sugar reaches the realm's registrar and is projected
            into reinstall!'s old-app, so a reinstall! that OMITS it lands it in
            :removed. PRE-FIX the :removed path called registrar/unregister!
            UNCONDITIONALLY — silently dropping the slot and skipping the
            subsystem teardown (in-flight abort, routing :current, flow
            owner-rebind), the silent-orphan window the :frame fix closed,
            reopened on the removal path. The fix REFUSES LOUDLY with
            :rf.error/unsupported-descriptor-kind — symmetric with the add/changed
            path's throw — BEFORE any mutation, enumerating the blocking [kind id]
            and naming the deferred set + the clear-* recovery.

            PARAMETERIZED over the FULL step-8 deferred-kind set (core.cljc:1753)
            — :route :flow :resource :mutation :resource-scope :view :head
            :error-projector — not just :mutation: drift in the deferred set or
            the removal filter could silently unregister routes/resources/flows/
            views/etc. while a :mutation-only test still passed (rf2-c6armm.9 #1)."
    ;; Simulate the sugar registration (the reg-route / reg-flow / reg-resource /
    ;; reg-mutation / … sugar lives in feature artefacts off core's test
    ;; classpath): seat ONE slot of the deferred kind directly into the default
    ;; realm's registrar (which IS the global atom), exactly as that kind's sugar
    ;; registrar/register! <kind> would. The projection picks it up.
    (doseq [kind [:route :flow :resource :mutation
                  :resource-scope :view :head :error-projector]]
      (let [slot-id (keyword "cquy9u" (str (name kind) "-save"))
            ev-id   (keyword "cquy9u" (str (name kind) "-ev"))]
        (registrar/register! kind slot-id {:handler-fn (fn [& _] nil)})
        (is (some? (registrar/lookup kind slot-id))
            (str "the sugar-registered " kind " slot is in the registrar"))
        ;; reinstall! a new app that OMITS the slot → it lands in :removed.
        ;; Re-declare nothing else of a deferred/frame kind so THIS refusal (not a
        ;; sibling slot's, nor the :frame refusal) is the one whose :removed we
        ;; assert below — every previously-seeded slot survived its own refusal so
        ;; it would ALSO be :removed, so we assert membership, not equality.
        (let [ed (try (av/reinstall! :rf.realm/default
                                     (av/app {:id :cquy9u/app :modules
                                              [(av/module {:id :m
                                                           :events {ev-id {:handler (fn [_ _] {})}}})]}))
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                        (ex-data e)))]
          (is (= :rf.error/unsupported-descriptor-kind (:rf.error/id ed))
              (str "removing a sugar-registered " kind " refuses loudly, not silently"))
          (is (some #{[kind slot-id]} (:removed ed))
              (str "the diagnostic enumerates the blocking removed [" kind " " slot-id "]"))
          (is (contains? (:deferred ed) kind)
              (str "the refusal names " kind " in the deferred set"))
          (is (= :clear-through-reg-*-sugar (:recovery ed))
              "the error names the recovery: clear through the kind's own clear-* sugar"))
        ;; The refusal was BEFORE any mutation — the slot is UNTOUCHED (the orphan
        ;; the bug produced: pre-fix it would be nil here) and the new :added
        ;; event was NOT seated.
        (is (some? (registrar/lookup kind slot-id))
            (str "the sugar-registered " kind " slot survives the refused reinstall — NOT silently unregistered"))
        (is (nil? (registrar/lookup :event ev-id))
            (str "the refused " kind " reinstall seated nothing — it failed before any mutation"))))
    ;; Cleanup: drop the directly-seeded slots (the runtime-reset fixture
    ;; snapshots the registrar, but be explicit since this test seeds them raw).
    (doseq [kind [:route :flow :resource :mutation
                  :resource-scope :view :head :error-projector]]
      (registrar/unregister! kind (keyword "cquy9u" (str (name kind) "-save"))))))

(deftest reinstall-not-omitting-the-step8-kind-leaves-it-untouched
  (testing "rf2-cquy9u: the refusal is targeted at REMOVAL only — a reinstall!
            whose new app does NOT drop the sugar-registered step-8 kind (it is
            neither :added nor :removed, since it is not in the app-value diff at
            all when the new app omits no projected slot it carries) applies
            cleanly. Here the new app re-declares nothing of that kind, but the
            slot is re-asserted into the registrar by the test so it is present in
            BOTH old and new projections → not :removed. (Belt-and-braces: the
            kind-boundary throw must not fire on a no-op reinstall.)"
    ;; Seed the :resource slot, then reinstall with an app that ALSO carries it
    ;; (re-seed after building old-app's projection is not possible, so instead we
    ;; assert the simpler invariant: a reinstall whose diff has NO :removed
    ;; step-8 kind does not throw). Build a new app that only ADDS an event; the
    ;; :resource slot is in old-app — to keep it OUT of :removed we re-register it
    ;; into the registrar so the post-reinstall projection still carries it. The
    ;; cleanest expression: reinstall the SAME projected app (a no-op diff).
    (registrar/register! :resource :cquy9u/feed {:handler-fn (fn [& _] nil)})
    (let [current (av/app-value :rf.realm/default)
          diff    (av/reinstall! :rf.realm/default current)]
      (is (= [] (:removed diff))
          "reinstalling the current projection removes nothing — no step-8 kind in :removed")
      (is (some? (registrar/lookup :resource :cquy9u/feed))
          "the :resource slot is untouched by the no-op reinstall"))
    (registrar/unregister! :resource :cquy9u/feed)))

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
    (av/install! :rf.realm/default
                 (av/app {:id :s7dcu8/app :modules
                          [(av/module {:id :m :subs {:s7dcu8/read {:handler (fn [_db _] :v1)}}})]}))
    ;; Hold an ACTIVE subscription and deref it — this POPULATES the live cache.
    (let [reaction (rf/subscribe :s7dcu8/app [:s7dcu8/read])]
      (is (= :v1 @reaction) "the active subscription yields v1 (cache populated)")
      ;; v2: the SAME sub id, a CHANGED handler yielding :v2 — :changed in the diff.
      (let [diff (av/reinstall! :rf.realm/default
                                (av/app {:id :s7dcu8/app :modules
                                         [(av/module {:id :m :subs {:s7dcu8/read {:handler (fn [_db _] :v2)}}})]}))]
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
    (av/install! (av/app {:id :app :modules
                          [(av/module {:id :m :events {:e {:handler cart-add}}})]}))
    (let [diff (av/reinstall! (av/app {:id :app :modules
                                       [(av/module {:id :m :events {:e2 {:handler cart-add}}})]}))]
      (is (= :rf.realm/default (:realm diff)) "defaults to the default realm")
      (is (= :hot-reload (:reason diff)) ":reason defaults to :hot-reload")
      (is (= [[:event :e2]] (:added diff)))
      (is (= [[:event :e]] (:removed diff))))))

(deftest reinstall-stores-the-new-app-value
  (testing "reinstall! records the new app value in the realm's :app slot — the
            stored slot is the new value verbatim, and installed-app reflects the
            reloaded handler through the live projection (rf2-77ewnm)"
    (let [v1 (av/app {:id :app :modules
                      [(av/module {:id :m :events {:e {:handler cart-add}}})]})
          v2 (av/app {:id :app :modules
                      [(av/module {:id :m :events {:e {:handler cart-remove}}})]})]
      (av/install! v1)
      (is (= v1 (:app (realm/realm realm/default-realm-id)))
          "the stored :app slot holds v1 verbatim after install")
      (is (identical? cart-add (registrar/handler :event :e))
          "and v1's handler resolves")
      (av/reinstall! v2)
      (is (= v2 (:app (realm/realm realm/default-realm-id)))
          "the realm's stored :app slot now holds the reinstalled value verbatim")
      (is (= :app (:rf.app/id (realm/installed-app)))
          "installed-app reports the reinstalled app's identity")
      (is (identical? cart-remove (registrar/handler :event :e))
          "and the live projection reflects v2's reloaded handler"))))

(deftest reinstall-rechecks-capabilities
  (testing "reinstall! re-runs the capability check — a reload that adds an
            unmet requirement throws before any mutation"
    (clear-default-realm-capabilities!)
    (av/install! (av/app {:id :app :modules
                          [(av/module {:id :m :events {:e {:handler cart-add}}})]}))
    (let [v2 (av/app {:id :app :modules
                      [(av/module {:id :m
                                   :rf.module/requires #{:rf.capability/http}
                                   :events {:e {:handler cart-remove}}})]})
          ed (try (av/reinstall! v2)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/missing-capability (:rf.error/id ed))
          "a reinstall that raises an unmet requirement throws")
      ;; The pre-reinstall handler is untouched — the check ran before mutation.
      (is (identical? cart-add (registrar/handler :event :e))
          "the installed handler is unchanged — the failed reinstall was atomic"))))

(deftest reinstall-after-pure-sugar-boot-diffs-against-the-projection
  (testing "the FIRST reinstall after a pure-sugar boot diffs against the live
            projection (no prior install! stored an :app) — so a hot-reload of a
            sugar-booted app still computes a correct delta"
    ;; Pure-sugar boot: no install!, just reg-*.
    (rf/reg-event :boot/a {:doc "a"} cart-add)
    ;; Reinstall a new app value derived FROM the live projection: drop the
    ;; sugar-booted :boot/a event and add :boot/b, leaving every other projected
    ;; slot in place. Building from the projection (rather than a fresh `av/app`)
    ;; keeps the fixture's live `:rf/default` :frame AND the artefact-load step-8
    ;; slots (`:view :route/link`, `:error-projector …`) present in BOTH old and
    ;; new — so they are NEITHER :removed (which would correctly refuse: a live
    ;; `:frame` per rf2-7zn9kg, a sugar-registered step-8 kind per rf2-cquy9u).
    ;; The diff then isolates exactly the event delta this test asserts.
    (let [projected (av/app-value :rf.realm/default)
          new-app   (-> projected
                        (update-in [:registrations :event] dissoc :boot/a)
                        (assoc-in  [:registrations :event :boot/b]
                                   {:kind :event :id :boot/b :handler cart-add}))
          diff      (av/reinstall! new-app)]
      (is (some #{[:event :boot/b]} (:added diff))
          ":boot/b is added relative to the projected installed app")
      (is (some #{[:event :boot/a]} (:removed diff))
          ":boot/a (sugar-booted, in the projection) is removed by the reinstall")
      (is (empty? (filter (fn [[kind _]]
                            (contains? #{:view :route :flow :resource :mutation
                                         :resource-scope :head :error-projector} kind))
                          (:removed diff)))
          "no step-8 deferred kind is in :removed — they ride through unchanged (rf2-cquy9u)"))))

;; ---------------------------------------------------------------------------
;; (5) descriptor round-trip — lowering is the inverse of normalisation
;; ---------------------------------------------------------------------------

(deftest descriptor-lowering-round-trips-through-the-projection
  (testing "a descriptor lowered by install! and re-projected (stage 5) carries
            the same handler / metadata / source — descriptor->registration-
            metadata is the inverse of the projection's normalisation"
    (let [src {:ns 'shop.cart :file "cart.cljs" :line 9 :column 1}
          a   (av/app {:id :app :modules
                       [(av/module {:id :m
                                    :events {:rt/e {:doc "round-trip"
                                                    :handler cart-add
                                                    :source src}}})]})]
      (av/install! a)
      (let [proj-d (get-in (av/app-value) [:registrations :event :rt/e])]
        (is (identical? cart-add (:handler proj-d))
            "the handler survives the lower→register→project round-trip")
        (is (= "round-trip" (get-in proj-d [:metadata :doc]))
            "the metadata survives the round-trip")
        (is (= src (:source proj-d))
            "the source-coord envelope survives the round-trip")))))

;; ---------------------------------------------------------------------------
;; (5b) PROJECTED event re-lowering (rf2-untip9)
;; ---------------------------------------------------------------------------
;;
;; A projected app value (from `av/app-value`) carries each event descriptor's
;; EFFECTIVE registrar metadata under `:metadata` — including the `:interceptors`
;; chain `reg-event` already assembled, whose tail is the inline `:rf/event-handler`
;; framework wrapper. Re-lowering such a descriptor through `install!` /
;; `reinstall!` calls `events/reg-event id meta handler` with that effective chain
;; in `meta`. Before the fix, `reg-event`'s reference-only `:interceptors`
;; validation rejected the inline wrapper with `:rf.error/inline-interceptor-removed`,
;; so a legitimately-projected event could never be re-installed (the install /
;; reinstall paths that source their value from a projection broke). The fix
;; normalises projected event metadata before `reg-event` so the wrapper is dropped
;; and the chain re-assembled.

(deftest install-of-a-projected-app-value-relowers-a-sugar-reg-event
  (testing "rf2-untip9: installing a projection that carries a sugar-registered
            reg-event re-lowers the projected event descriptor (whose :metadata
            holds the effective :interceptors chain with the inline
            :rf/event-handler wrapper) through reg-event WITHOUT raising
            :rf.error/inline-interceptor-removed — projected/reconciled apps are
            app values too, and feeding their registrar-shaped event descriptors
            back through reg-event must round-trip. This is the bead's direct
            repro: reg-event sugar, then install! the projection."
    ;; Sugar path: ordinary reg-event seats the effective interceptor chain
    ;; (user chain + the appended :rf/event-handler wrapper) into the registrar.
    (rf/reg-event :untip9/sugar {:doc "x"} (fn [{:keys [db]} _] {:db db}))
    ;; The projected descriptor carries that effective chain under :metadata.
    (let [proj-d (get-in (av/app-value) [:registrations :event :untip9/sugar])]
      (is (some #(= :rf/event-handler (:id %)) (get-in proj-d [:metadata :interceptors]))
          "the projection carries the effective chain with the inline :rf/event-handler wrapper"))
    ;; Install a projection carrying ONLY the projected :untip9/sugar event
    ;; descriptor (the artefact-load sugar also projects step-8-deferred :view /
    ;; :error-projector kinds that install! refuses pre-lowering; isolate the
    ;; event so the test exercises the re-lowering path the bead names, not the
    ;; deferred-kind preflight). The projected event descriptor still carries the
    ;; effective :interceptors chain under :metadata — the bug's trigger.
    (let [full   (av/app-value :rf.realm/default)
          proj-d (get-in full [:registrations :event :untip9/sugar])
          proj   (assoc full :registrations {:event {:untip9/sugar proj-d}})
          ed     (try (av/install! proj) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                        (ex-data e)))]
      (is (nil? ed)
          (str "re-lowering the projected event must not throw; got "
               (pr-str (:rf.error/id ed))))
      (is (not= :rf.error/inline-interceptor-removed (:rf.error/id ed))
          "the projected :rf/event-handler wrapper is no longer mis-read as an authored inline interceptor")
      ;; The re-lowered event is a real, resolvable registration carrying a
      ;; freshly-assembled :rf/event-handler wrapper (reg-event ran).
      (is (some? (registrar/handler :event :untip9/sugar))
          "the projected event re-lowered into a resolvable registration")
      (is (some #(= :rf/event-handler (:id %))
                (:interceptors (registrar/lookup :event :untip9/sugar)))
          "reg-event re-assembled the framework wrapper on the re-lowered event"))))

(deftest reinstall-changed-event-sourced-from-a-projection-relowers
  (testing "rf2-untip9: a reinstall whose CHANGED event is sourced from a
            projection (so its :metadata carries the effective :interceptors chain
            with the inline :rf/event-handler wrapper) re-lowers through reg-event
            without raising :rf.error/inline-interceptor-removed — the changed-event
            path the bead names."
    ;; Sugar-register the event so the registrar holds its EFFECTIVE interceptor
    ;; chain; project a SINGLE-event app value off that descriptor (the default
    ;; realm's artefact-load sugar also projects step-8-deferred :view /
    ;; :error-projector kinds AND sibling events whose :metadata carries the same
    ;; effective chain — isolate to the one event so the test exercises exactly
    ;; the changed-event re-lowering path, not the deferred-kind preflight or a
    ;; sibling event's identical re-lowering).
    (rf/reg-event :untip9.re/e {:doc "base"} cart-add)
    (let [full      (av/app-value :rf.realm/default)
          proj-d    (get-in full [:registrations :event :untip9.re/e])
          installed (assoc full :registrations {:event {:untip9.re/e proj-d}})]
      (av/install! installed)
      ;; Build a new app that CHANGES :untip9.re/e by swapping its handler — the
      ;; descriptor still carries the PROJECTED :metadata (effective interceptor
      ;; chain + the inline wrapper). Exactly a projected registrar-shaped event
      ;; descriptor fed back through reinstall! on the :changed path.
      (let [changed (assoc proj-d :handler cart-remove)
            new-app (assoc-in installed [:registrations :event :untip9.re/e] changed)
            ed      (try (av/reinstall! new-app) nil
                         (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                           (ex-data e)))]
        (is (nil? ed)
            (str "re-lowering the changed projected event must not throw; got "
                 (pr-str (:rf.error/id ed))))
        (is (not= :rf.error/inline-interceptor-removed (:rf.error/id ed))
            "the projected :rf/event-handler wrapper is not mis-read on the changed-event path")
        (is (identical? cart-remove (registrar/handler :event :untip9.re/e))
            "the changed handler re-lowered and resolves")
        (is (some #(= :rf/event-handler (:id %))
                  (:interceptors (registrar/lookup :event :untip9.re/e)))
            "reg-event re-assembled the framework wrapper on the re-lowered changed event")))))

;; ---------------------------------------------------------------------------
;; (6) KIND COVERAGE of the install! lowering seam (rf2-xmslkr)
;; ---------------------------------------------------------------------------
;;
;; `core/install-descriptor!` special-cases :event/:sub/:fx/:cofx through their
;; REAL registration logic (the event interceptor wrap, the sub input-signal
;; parse, the fx/cofx slot stamp); every OTHER kind falls back to the FLAT
;; registrar lowering (`descriptor->registration-metadata` → `registrar/register!`).
;; These tests pin (a) all four wired kinds lower correctly through a single
;; install!, (b) an EXPLICIT unknown realm id throws :rf.error/unknown-realm
;; before any mutation (rf2-c6armm.1 #1 — the prior unknown-realm→global
;; fallback was a defect; nil/default sugar + the realm-map form still work),
;; and (c) CHARACTERIZE the flat-fallback gap for a non-wired kind that carries
;; real registration logic (the audit's concern — the flat path seats a
;; registrar slot WITHOUT running the subsystem's own registration, so the slot
;; is malformed for the subsystem).

(deftest install-lowers-the-four-wired-kinds-through-real-registration-logic
  (testing "a single install! lowers :event/:sub/:fx/:cofx descriptors through
            their real reg-* logic — each resolves through the registrar exactly
            as a reg-* call would (rf2-xmslkr kind-coverage, the wired set)"
    (let [ev-h   (fn [db _] (assoc db :ev? true))
          sub-h  (fn [_db _] :sub-val)
          fx-h   (fn [_] nil)
          cofx-h (fn [coeffects] coeffects)]
      (av/install! (av/app {:id :xms/wired :modules
                            [(av/module {:id :m
                                         :events {:xms/ev   {:handler ev-h}}
                                         :subs   {:xms/sub  {:handler sub-h}}
                                         :fx     {:xms/fx   {:handler fx-h}}
                                         :cofx   {:xms/cofx {:handler cofx-h}}})]}))
      (is (identical? ev-h (registrar/handler :event :xms/ev))
          ":event lowered through reg-event (resolvable handler)")
      (is (identical? sub-h (registrar/handler :sub :xms/sub))
          ":sub lowered through reg-sub")
      (is (identical? fx-h (registrar/handler :fx :xms/fx))
          ":fx lowered through reg-fx")
      (is (identical? cofx-h (registrar/handler :cofx :xms/cofx))
          ":cofx lowered through reg-cofx")
      ;; The :event lowered through the REAL path carries the wrapped slots a
      ;; reg-event produces (the interceptor chain), not just a flat handler —
      ;; the distinction the wired path exists to provide.
      (is (contains? (registrar/lookup :event :xms/ev) :interceptors)
          ":event seated the kind-appropriate interceptor chain (real reg-* logic ran)"))))

(deftest install-event-descriptor-lowers-through-reg-event
  (testing "EP-0018 Z (rf2-xhfxcs.14): a module event descriptor lowers straight
            through the ONE public `reg-event` runtime fn — the former
            `:event/kind` sub-discriminator is gone, so every module event seats
            through the one shape with the unified `:rf/event-handler` wrapper
            and the interceptor chain (rf2-xmslkr × EP-0018 Z)"
    (av/install! (av/app {:id :xms/evfx :modules
                          [(av/module {:id :m
                                       :events {:xms/evfx {:handler (fn [_cofx _ev] {})}}})]}))
    (is (some? (registrar/handler :event :xms/evfx))
        "the event descriptor is seated as a resolvable event handler")
    (is (not (contains? (registrar/lookup :event :xms/evfx) :event/kind))
        "no :event/kind sub-tag — EP-0018 dropped it")
    (is (contains? (registrar/lookup :event :xms/evfx) :interceptors)
        "it ran through reg-event (interceptor chain seated), not the flat path")))

(deftest install-event-descriptor-lowers-through-the-reg-event-runtime-fn
  (testing "EP-0018 Z (rf2-xhfxcs.14) ADVERSARIAL: an event descriptor's install
            lowering reaches the ONE public `reg-event` runtime fn — a spy over
            `events/reg-event` records the call (delegating to the real fn so the
            handler is genuinely seated), proving the install seam routes through
            `reg-event`, not the flat fallback."
    (let [calls          (atom 0)
          real-reg-event events/reg-event]
      (with-redefs [events/reg-event (fn [id & args]
                                       (swap! calls inc)
                                       (apply real-reg-event id args))]
        (av/install! (av/app {:id :xhfxcs/d :modules
                              [(av/module {:id :m
                                           :events {:xhfxcs/ev {:handler (fn [{:keys [db]} _] {:db db})}}})]})))
      (is (= 1 @calls)
          "the install seam lowered the event descriptor through events/reg-event")
      ;; The spy delegated to the real `reg-event`, so the handler is genuinely
      ;; seated + dispatch-ready (behaviour-preserving end-to-end).
      (is (some? (registrar/handler :event :xhfxcs/ev))
          "the descriptor is a real, resolvable event registration after lowering"))))

(deftest install-event-descriptor-seats-reg-event-semantics
  (testing "EP-0018 Z (rf2-xhfxcs.14) ADVERSARIAL (behavioural discriminator): a
            module event descriptor lowered through `reg-event` seats the one
            `:rf/event-handler` framework wrapper and NO `:event/kind` sub-tag —
            the coeffects-in / closed-effects-map-out contract. A regression that
            re-introduced a per-kind wrapper or the `:event/kind` tag would fail
            here, and the handler is proven dispatch-ready end-to-end."
    (rf/reg-frame :xhfxcs.d/app {:doc "event-lowering app"})
    (av/install! (av/app {:id :xhfxcs.d/app :modules
                          [(av/module {:id :m
                                       :events {:xhfxcs.d/set
                                                {:handler (fn [{:keys [db]} [_ v]]
                                                            {:db (assoc db :n v)})}}})]}))
    (let [meta (registrar/lookup :event :xhfxcs.d/set)]
      (is (not (contains? meta :event/kind))
          "no :event/kind sub-tag — EP-0018 dropped it")
      ;; The framework wrapper interceptor is the one unified `:rf/event-handler`.
      (is (some #(= :rf/event-handler (:id %)) (:interceptors meta))
          "the one :rf/event-handler wrapper is seated"))
    ;; End-to-end: the seated handler dispatches with fx-shape semantics
    ;; (coeffects-in, effects-out — `{:db …}`), confirming the lowering produced
    ;; a working reg-event registration, not just a tagged slot.
    (rf/dispatch-sync [:xhfxcs.d/set 7] {:frame :xhfxcs.d/app})
    (is (= {:n 7} (rf/app-db-value :xhfxcs.d/app))
        "the fx-lowered handler committed its `{:db …}` effect on dispatch")))

(deftest install-into-an-unknown-realm-id-throws-before-any-mutation
  (testing "FLIPPED (rf2-c6armm.1 #1 / .2 #2): installing into an EXPLICIT,
            never-constructed realm id THROWS :rf.error/unknown-realm BEFORE any
            registrar mutation — it does NOT silently fall back to the global
            registrar. EP/API defaulting is for the ABSENCE of a realm, not for
            an arbitrary unknown explicit id; the old fallback polluted the
            default registrar while recording no installed app on the requested
            realm."
    ;; :xms/ghost was never `realm/construct-realm`-constructed, so it has no registry entry.
    (is (nil? (realm/realm :xms/ghost)) "the realm id was never constructed")
    (let [h  (fn [db _] db)
          a  (av/app {:id :xms/ghost-app :modules
                      [(av/module {:id :m :events {:xms/ghost-ev {:handler h}}})]})
          ed (try (av/install! :xms/ghost a)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/unknown-realm (:rf.error/id ed))
          "an explicit unknown realm id throws :rf.error/unknown-realm")
      (is (= :xms/ghost (:realm ed)) "the diagnostic names the unknown realm id")
      (is (= :construct-the-realm-first (:recovery ed)))
      ;; Pre-mutation: NOTHING leaked into the default/global registrar.
      (is (nil? (registrar/lookup :event :xms/ghost-ev))
          "no descriptor leaked into the default registrar — the throw was pre-mutation")
      (is (nil? (get-in @registrar/kind->id->metadata [:event :xms/ghost-ev]))
          "the global atom is untouched"))))

(deftest install-absent-and-default-and-realm-map-still-work
  (testing "rf2-c6armm.1 #1: the unknown-realm guard preserves the nil/default
            sugar and the realm-map compose form — only an EXPLICIT unknown id
            throws"
    ;; (a) nil / absent realm → the default realm (the byte-identical sugar path).
    (av/install! (av/app {:id :ok/default :modules
                          [(av/module {:id :m :events {:ok/absent {:handler cart-add}}})]}))
    (is (identical? cart-add (registrar/handler :event :ok/absent))
        "absent realm seats into the default realm (sugar preserved)")
    ;; (b) explicit default-realm id → also fine (seeded at boot).
    (av/install! :rf.realm/default
                 (av/app {:id :ok/expl :modules
                          [(av/module {:id :m :events {:ok/explicit {:handler cart-add}}})]}))
    (is (identical? cart-add (registrar/handler :event :ok/explicit))
        "explicit default realm id seats fine")
    ;; (c) a constructed realm MAP composes through.
    (let [r (realm/construct-realm {:id :ok/constructed})]
      (try
        (av/install! r (av/app {:id :ok/c :modules
                                [(av/module {:id :m :events {:ok/in-realm {:handler cart-add}}})]}))
        (is (identical? cart-add (get-in @(realm/registrar r) [:event :ok/in-realm :handler-fn]))
            "a constructed realm map seats into its own registrar")
        (finally (realm/dispose-realm! :ok/constructed))))))

;; ---------------------------------------------------------------------------
;; (5b) :frame descriptors into a non-default realm SEAT into that realm
;;      (EP-0013 step 4, rf2-a15n62 — the refusal is LIFTED)
;; ---------------------------------------------------------------------------
;;
;; The realm-aware frame-registration path has SHIPPED: `reg-frame` (reached via
;; the install-descriptor hook under `seat-into-realm!`'s `*current-realm*`
;; binding) STAMPS the frame with the target realm and keys the `frames` registry
;; by the `[realm-id frame-id]` address (so the same id is legal in two realms).
;; A `:frame` seated into an explicit realm is OWNED by it — `frame-realm` reports
;; the constructed realm, not the default. The prior refusal
;; (`:rf.error/realm-frames-unsupported`) is gone.

(deftest install-frame-into-non-default-realm-seats-into-that-realm
  (testing "EP-0013 step 4 (rf2-a15n62): a :frame descriptor seated into a
            NON-default realm creates a REAL frame stamped + keyed by that realm
            — not a default-stamped, globally-keyed mis-seat"
    (let [r (realm/construct-realm {:id :rf-frame/r})]
      (try
        (av/install! r (av/app {:id :rf-frame/app :modules
                                [(av/module {:id :m :frames {:rf-frame/f {:doc "realm-owned frame"}}})]}))
        ;; The frame is owned by the constructed realm (not default-stamped).
        ;; `frame-realm` resolves the frame id WITHIN its realm scope (the bare
        ;; id is unique only within a realm — no default-realm :rf-frame/f exists).
        (is (= :rf-frame/r (frame/call-with-realm :rf-frame/r
                             (fn [] (frame/frame-realm :rf-frame/f))))
            "the seated frame's realm is the constructed realm, not the default")
        (is (nil? (frame/frame-realm :rf-frame/f))
            "the bare (default-scope) read finds no default-realm frame of that id")
        ;; The realm's membership view includes it.
        (is (contains? (realm/realm-frames :rf-frame/r) :rf-frame/f)
            "the realm owns the seated frame in its membership view")
        ;; The DEFAULT realm did NOT silently receive a frame by the same id.
        (is (not (contains? (realm/realm-frames realm/default-realm-id) :rf-frame/f))
            "no default-realm frame of the same id was created (no global collision)")
        (finally
          (realm/dispose-realm! :rf-frame/r))))))

(deftest same-frame-id-in-two-realms-stays-isolated
  (testing "EP-0013 step 4 (rf2-a15n62) — the headline: the SAME frame id +
            SAME event/sub/fx/cofx ids installed into two realms stay ISOLATED
            across event dispatch, subscription, fx AND cofx — each frame
            resolves its OWN realm's program"
    (let [ra (realm/construct-realm {:id :iso/a})
          rb (realm/construct-realm {:id :iso/b})
          ;; realm-distinguishing handler / sub / fx / cofx bodies
          fx-log (atom [])
          ;; A per-realm cofx supplier (`:iso/c` → the realm tag), DECLARED on the
          ;; event via `:rf.cofx/requires` (EP-0017) so the handler reads its OWN
          ;; realm's cofx value off the coeffects map. The fx (`:iso/fx`) is
          ;; emitted via the canonical `:fx [[fx-id args]]` shape so it survives
          ;; the effect-map police gate. Both `:iso/c` (cofx) and `:iso/fx` (fx)
          ;; are realm-installed — resolution must route to the owning realm.
          app-for (fn [tag]
                    (av/app {:id :iso/app :modules
                             [(av/module
                                {:id :m
                                 :frames {:iso/f {:doc "shared id"}}
                                 :events {:iso/e {:rf.cofx/requires [:iso/c]
                                                  :handler (fn [{:keys [db iso/c]} _]
                                                             {:db (assoc db :tag tag :seen-cofx c)
                                                              :fx [[:iso/fx tag]]})}}
                                 :subs   {:iso/s {:handler (fn [db _] [tag (:tag db)])}}
                                 :fx     {:iso/fx {:handler (fn [_ args]
                                                              (swap! fx-log conj [tag args]))}}
                                 :cofx   {:iso/c {:handler (fn [] tag)}}})]}))]
      (try
        (av/install! ra (app-for :a))
        (av/install! rb (app-for :b))
        ;; EVENT + COFX: dispatch the SAME event id into each realm's frame;
        ;; each runs ITS realm's handler + injects ITS realm's cofx supplier.
        (rf/dispatch-sync [:iso/e] {:realm :iso/a :frame :iso/f})
        (rf/dispatch-sync [:iso/e] {:realm :iso/b :frame :iso/f})
        ;; app-db of each realm's frame reflects its OWN handler + its OWN cofx
        ;; value (`:seen-cofx`). Read each realm's frame under its realm scope.
        (is (= {:tag :a :seen-cofx :a}
               (frame/call-with-realm :iso/a (fn [] (frame/frame-app-db-value :iso/f))))
            "realm a's frame ran realm a's :iso/e handler AND injected realm a's :iso/c cofx")
        (is (= {:tag :b :seen-cofx :b}
               (frame/call-with-realm :iso/b (fn [] (frame/frame-app-db-value :iso/f))))
            "realm b's frame ran realm b's :iso/e handler AND injected realm b's :iso/c cofx")
        ;; FX: each realm's fx handler fired with its own tag (realm-routed fx).
        ;; `:fx [[:iso/fx tag]]` delivers `tag` as the fx args scalar.
        (is (some #(= [:a :a] %) @fx-log) "realm a's :iso/fx fired with realm a's value")
        (is (some #(= [:b :b] %) @fx-log) "realm b's :iso/fx fired with realm b's value")
        ;; SUBSCRIPTION: subscribe the SAME sub id against each realm's frame;
        ;; each resolves ITS realm's sub body (realm-routed sub resolution).
        (is (= [:a :a] (frame/call-with-realm :iso/a
                         (fn [] (rf/subscribe-once :iso/f [:iso/s]))))
            "realm a's frame resolves realm a's :iso/s")
        (is (= [:b :b] (frame/call-with-realm :iso/b
                         (fn [] (rf/subscribe-once :iso/f [:iso/s]))))
            "realm b's frame resolves realm b's :iso/s")
        (finally
          (realm/dispose-realm! :iso/a)
          (realm/dispose-realm! :iso/b))))))

(deftest install-frame-into-default-realm-still-works
  (testing "rf2-c6armm.1 #3: the refusal is scoped to NON-default realms — a
            :frame descriptor into the DEFAULT realm still lowers through
            reg-frame (the rf2-chc8vs wiring is unchanged)"
    (av/install! (av/app {:id :df-frame/app :modules
                          [(av/module {:id :m :frames {:df-frame/f {:doc "default frame"}}})]}))
    (is (contains? (frame/frame-ids) :df-frame/f)
        "a default-realm :frame install creates a real frame container as before")))

;; ---------------------------------------------------------------------------
;; (5c) realm/construct-realm rejects a public :app (no false installed-app state)
;;      (rf2-c6armm.2 #1)
;; ---------------------------------------------------------------------------

(deftest realm-rejects-public-app-key
  (testing "rf2-c6armm.2 #1: realm/construct-realm with an :app key THROWS :rf.error/invalid-realm
            — :app is install-owned state, not a constructor input. A public :app
            would record an installed-app VALUE without seating its descriptors,
            so installed-app would report a program the registrar does not hold
            and the first reinstall! would diff against that phantom"
    (let [app (av/app {:id :false/app :modules
                       [(av/module {:id :m :events {:false/e {:handler cart-add}}})]})
          ed  (try (realm/construct-realm {:id :false/r :app app})
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                     (ex-data e)))]
      (is (= :rf.error/invalid-realm (:rf.error/id ed))
          "a public :app on realm/construct-realm is rejected")
      (is (= :false/r (:realm ed)) "the diagnostic names the realm")
      (is (nil? (realm/realm :false/r))
          "no realm was registered — the constructor threw before register-realm!")
      ;; The bead's exact phantom scenario — (realm/construct-realm {:app app}) then reinstall!
      ;; — is structurally IMPOSSIBLE: the constructor threw, so :false/r has no
      ;; registry entry, and a follow-up reinstall! against it would itself throw
      ;; :rf.error/unknown-realm (it never reaches a diff against a phantom :app).
      (let [v2 (av/app {:id :false/v2 :modules
                        [(av/module {:id :m :events {:false/e2 {:handler cart-add}}})]})
            re-ed (try (av/reinstall! :false/r v2)
                       (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                         (ex-data e)))]
        (is (= :rf.error/unknown-realm (:rf.error/id re-ed))
            "a reinstall! against the never-created realm throws unknown-realm — no
             phantom :app to diff against (the false installed-app state is closed)")))))

(deftest realm-without-app-then-install-is-the-correct-path
  (testing "rf2-c6armm.2 #1: the SUPPORTED path — construct a realm, then
            install! — seats descriptors AND records the app, so installed-app
            and the registrar AGREE (no phantom installed-app, no reinstall! that
            skips registrar population)"
    (let [r   (realm/construct-realm {:id :seat/r})
          app (av/app {:id :seat/app :modules
                       [(av/module {:id :m :events {:seat/e {:handler cart-add}}})]})]
      (try
        ;; A fresh realm has no installed app beyond its empty projection.
        (is (= {} (:registrations (realm/installed-app r)))
            "a fresh realm projects an empty program (no false :app)")
        (av/install! r app)
        ;; install! seated the descriptor into the registrar AND recorded the app.
        (is (identical? cart-add (get-in @(realm/registrar r) [:event :seat/e :handler-fn]))
            "install! actually seated the descriptor into the realm's registrar")
        (is (= :seat/app (:rf.app/id (realm/installed-app r)))
            "installed-app reports the seated app's identity — registrar + :app agree")
        (is (contains? (get-in (realm/installed-app r) [:registrations :event]) :seat/e)
            "and the seated registration is visible in the reconciled read")
        ;; reinstall! now diffs against a REAL installed app, not a phantom.
        (let [v2   (av/app {:id :seat/app :modules
                            [(av/module {:id :m :events {:seat/e2 {:handler cart-items}}})]})
              diff (av/reinstall! r v2)]
          (is (= [[:event :seat/e2]] (:added diff)) ":seat/e2 added")
          (is (= [[:event :seat/e]] (:removed diff))
              ":seat/e removed — the diff saw the genuinely-seated base program")
          (is (identical? cart-items (get-in @(realm/registrar r) [:event :seat/e2 :handler-fn]))
              "the reinstall actually populated the registrar with the new program"))
        (finally (realm/dispose-realm! :seat/r))))))

(deftest install-wires-the-frame-kind-through-reg-frame
  (testing "rf2-chc8vs: :frame is an EP-0013 step-7 FIRST-format kind, so a
            CONSTRUCTED :frame descriptor is now lowered through reg-frame's REAL
            registration logic — install! creates a real frame CONTAINER (not the
            malformed flat registrar slot the prior slice left). The frame
            appears in `frame-ids`, `frame/frame` resolves a live container, and
            it is dispatchable. This FLIPS the rf2-xmslkr characterization test
            that pinned `frame/frame -> nil` as a known limitation."
    (av/install! (av/app {:id :xms/frame-app :modules
                          [(av/module {:id :m :frames {:xms/frame {:doc "constructed frame"}}})]}))
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
    ;; is a real frame and not a malformed slot. A SECOND install! is full
    ;; replacement (rf2-c6armm.7 #1), so the new app CARRIES the frame forward in
    ;; the SAME module :m (an identical :frame descriptor — same owner, same :doc —
    ;; so the diff leaves it untouched; dropping it would correctly refuse, as the
    ;; live frame is not orphanable through install! any more than through
    ;; reinstall!) and ADDS the handlers in a second module :m2.
    (av/install! (av/app {:id :xms/frame-prog :modules
                          [(av/module {:id :m :frames {:xms/frame {:doc "constructed frame"}}})
                           (av/module {:id :m2
                                       :events {:xms/frame-set {:handler (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)})}}
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
      (let [a  (av/app {:id :xms/deferred-app :modules
                        [(av/module (assoc {:id :m}
                                           section {:xms/deferred {:handler (fn [& _] nil)}}))]})
            ed (try (av/install! a)
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                      (ex-data e)))]
        (is (= :rf.error/unsupported-descriptor-kind (:rf.error/id ed))
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

;; ---------------------------------------------------------------------------
;; (5d) failed install leaves NO live frame side effects — the kind preflight
;;      refuses BEFORE lowering, so a :frame in the same app never lowers
;;      (rf2-c6armm.8 #2)
;; ---------------------------------------------------------------------------

(deftest install-of-frame-plus-deferred-kind-leaks-no-live-frame
  (testing "rf2-c6armm.8 #2: an app that carries a WIRED :frame AND a step-8
            DEFERRED kind is refused loudly BEFORE any descriptor lowers — so the
            :frame is NEVER created. Pre-fix, install-descriptor! threw MID-LOOP:
            the :frame could lower first (creating a LIVE container + :on-create
            + classification — a frame/frames side-channel write), then the
            deferred kind threw, and seat-into-realm!'s registrar-only rollback
            left the live frame ORPHANED (a false installed-app: a frame the
            failed install seated but no :app recorded). The kind PREFLIGHT closes
            that window — the refusal precedes the seating loop, so no live frame
            leaks."
    (is (not (contains? (frame/frame-ids) :leak/frame))
        "the frame does not exist before the failed install")
    (let [a  (av/app {:id :leak/app :modules
                      [(av/module {:id :m
                                   ;; A :frame (wired — would lower through reg-frame
                                   ;; and create a live container) AND a deferred
                                   ;; :route in the SAME app.
                                   :frames {:leak/frame {:doc "would-leak frame"}}
                                   :routes {:leak/route {:handler (fn [& _] nil)}}})]})
          ed (try (av/install! a)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                    (ex-data e)))]
      (is (= :rf.error/unsupported-descriptor-kind (:rf.error/id ed))
          "the deferred kind refuses the whole install loudly")
      (is (= :route (:kind ed)) "the refusal names the blocking deferred kind")
      (is (some #{[:route :leak/route]} (:blocking ed))
          "the refusal enumerates the blocking [kind id]")
      ;; THE FIX: no live frame leaked — the refusal was pre-lowering, so
      ;; reg-frame never ran for :leak/frame.
      (is (not (contains? (frame/frame-ids) :leak/frame))
          "no live :frame container leaked from the failed install (pre-lowering refusal)")
      (is (nil? (frame/frame :leak/frame))
          "frame/frame resolves no orphaned container for the refused :frame")
      (is (nil? (registrar/lookup :frame :leak/frame))
          "the :frame registrar slot was never seated either")
      ;; And no false installed-app: the realm recorded no :app (the throw
      ;; preceded set-installed-app!).
      (is (nil? (:app (realm/realm :rf.realm/default)))
          "no :app was recorded — the failed install left no false installed-app state"))))

;; ---------------------------------------------------------------------------
;; (5e) repeated install! is FULL REPLACEMENT — a prior installed app's
;;      registrations the new app drops are cleared (rf2-c6armm.7 #1)
;; ---------------------------------------------------------------------------

(deftest repeated-install-clears-the-prior-apps-stale-registrations
  (testing "rf2-c6armm.7 #1: install! app1 then install! app2 makes the realm's
            registrar BE app2's program — app1's handlers that app2 drops are
            CLEARED. Pre-fix, install! only ADDED app2's descriptors + recorded
            :app, leaving app1's stale handlers resolvable while installed-app
            reported app2 — the installed-app value stopped being the source of
            truth for the realm registrar. Full replacement (EP-0013 §Installation
            'derive the realm registrar from the app value' / 'value replacement
            at the realm boundary') restores it."
    (let [r (realm/construct-realm {:id :stale/r})]
      (try
        ;; app1: two events.
        (av/install! r (av/app {:id :stale/app1 :modules
                                [(av/module {:id :m
                                             :events {:stale/keep {:handler cart-add}
                                                      :stale/drop {:handler cart-add}}})]}))
        (is (identical? cart-add (get-in @(realm/registrar r) [:event :stale/keep :handler-fn]))
            "app1's :stale/keep is seated")
        (is (identical? cart-add (get-in @(realm/registrar r) [:event :stale/drop :handler-fn]))
            "app1's :stale/drop is seated")
        ;; app2: re-declares :stale/keep (changed handler), DROPS :stale/drop,
        ;; ADDS :stale/new.
        (av/install! r (av/app {:id :stale/app2 :modules
                                [(av/module {:id :m
                                             :events {:stale/keep {:handler cart-remove}
                                                      :stale/new  {:handler cart-items}}})]}))
        ;; THE FIX: :stale/drop (in app1, not app2) is CLEARED — no longer resolvable.
        (is (nil? (get-in @(realm/registrar r) [:event :stale/drop]))
            "app1's dropped :stale/drop was CLEARED — not a stale resolvable handler")
        ;; :stale/keep was replaced with app2's handler (the changed descriptor).
        (is (identical? cart-remove (get-in @(realm/registrar r) [:event :stale/keep :handler-fn]))
            ":stale/keep carries app2's handler (replaced)")
        ;; :stale/new (added by app2) is seated.
        (is (identical? cart-items (get-in @(realm/registrar r) [:event :stale/new :handler-fn]))
            "app2's added :stale/new is seated")
        ;; installed-app IS the source of truth: the registrar's event ids equal
        ;; exactly app2's, no stale residue.
        (is (= #{:stale/keep :stale/new}
               (set (keys (get-in @(realm/registrar r) [:event]))))
            "the realm registrar's events equal app2's exactly — no stale leak")
        (is (= :stale/app2 (:rf.app/id (realm/installed-app r)))
            "installed-app reports app2 (and the registrar matches it)")
        (finally (realm/dispose-realm! :stale/r))))))

(deftest repeated-install-preserves-coexisting-sugar-registrations
  (testing "rf2-c6armm.7 #1: replacement is scoped to the PRIOR INSTALLED app, not
            the whole registrar — a coexisting reg-* SUGAR registration (not part
            of any installed app value) survives a repeated install!. The stale
            clear diffs against the realm's STORED :app only, so the load-order
            sugar program is never swept."
    ;; Sugar registration into the default realm (NOT an installed app value).
    (rf/reg-event :coexist/sugar {:doc "sugar"} cart-add)
    ;; install! app1 (its own event), then install! app2 dropping app1's event.
    (av/install! (av/app {:id :coexist/app1 :modules
                          [(av/module {:id :m :events {:coexist/a1 {:handler cart-add}}})]}))
    (av/install! (av/app {:id :coexist/app2 :modules
                          [(av/module {:id :m :events {:coexist/a2 {:handler cart-add}}})]}))
    ;; app1's :coexist/a1 was cleared (the stale-replacement fix)...
    (is (nil? (registrar/lookup :event :coexist/a1))
        "app1's dropped event was cleared by the replacement")
    ;; ...but the SUGAR registration coexists untouched (it was never in a stored :app).
    (is (identical? cart-add (registrar/handler :event :coexist/sugar))
        "the coexisting sugar registration survives the repeated install (not swept)")
    (is (identical? cart-add (registrar/handler :event :coexist/a2))
        "app2's event is seated")))

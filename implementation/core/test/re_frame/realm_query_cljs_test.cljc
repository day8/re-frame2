(ns re-frame.realm-query-cljs-test
  "EP-0013 D1 stage 8 (rf2-blibek) — the realm-targeted QUERY surface.

  The public map-shaped registrar query forms read the registrations of a
  SPECIFIED realm rather than the implicit default:

    (rf/registrations {:realm r :kind :event})
    (rf/handler-meta   {:realm r :kind :event :id :cart/add})
    (rf/handler-ids    {:realm r :kind :event})
    (av/app-registrations app :event)   ; retained-internal positional inspector

  ruled map-shaped in EP-0013 open-issue 11 (unambiguous against the existing
  keyword arities, extensible). The headline acceptance, mirroring D1 §Realm
  Conformance: \"realm-targeted registrar queries return ONLY that realm's
  registrations\" AND the default-realm keyword arities stay BYTE-IDENTICAL
  (a single-realm caller never spells a realm — the absence-is-default rule).

  These tests pin:
    (1) the default-realm keyword arities are unchanged (the no-regression
        headline) — `(registrations :event)` / `(handler-meta :event id)` /
        `(handler-ids :event)` behave exactly as before;
    (2) the map-shaped form against the DEFAULT realm equals the keyword
        arity (absence = default realm, an explicit documented rule);
    (3) a HERMETIC realm with its OWN registrar atom returns ONLY its own
        registrations — the same id registered differently in two realms
        resolves to the per-realm handler (the EP-0013 issue 3 tested legal
        case);
    (4) an unknown realm reads as the empty program (never an error);
    (5) `app-registrations` grows the `{:app … :kind …}` map form parallel to
        the realm-targeted registrar queries.

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build AND the JVM `clojure -M:test` runner both pick it up. Pure CLJC."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.app-value :as av]
            [re-frame.realm :as realm]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(def ^:private tenant-rid :tenant/query-test)

(defn- with-tenant-realm
  "Run `f` with a hermetic tenant realm registered under `tenant-rid`, holding
  its OWN registrar atom seeded with `seed` (`kind → id → metadata`). Cleans
  the realm out of the registry afterward."
  [seed f]
  (let [own-registrar (atom seed)]
    (swap! realm/realms assoc tenant-rid
           (realm/make-realm tenant-rid {:registrar own-registrar}))
    (try (f tenant-rid)
         (finally (swap! realm/realms dissoc tenant-rid)))))

;; ---------------------------------------------------------------------------
;; (1) the default-realm keyword arities are UNCHANGED — the headline
;; ---------------------------------------------------------------------------

(deftest default-realm-keyword-arities-unchanged
  (testing "the existing keyword arities behave exactly as before — a
            single-realm caller never spells a realm"
    (rf/reg-event :rq/inc {:doc "inc"} (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/reg-sub :rq/n {:doc "n"} (fn [db _] (:n db)))
    ;; (registrations kind)
    (is (contains? (rf/registrations :event) :rq/inc)
        "(registrations :event) lists the default-realm event")
    (is (= (registrar/registrations :event) (rf/registrations :event))
        "the facade keyword arity IS registrar/registrations")
    ;; (registrations kind pred-fn)
    (is (= {:rq/inc (registrar/lookup :event :rq/inc)}
           (rf/registrations :event (fn [m] (= "inc" (:doc m)))))
        "(registrations kind pred-fn) filters by metadata, unchanged")
    ;; (handler-meta kind id)
    (is (= (registrar/lookup :event :rq/inc)
           (rf/handler-meta :event :rq/inc))
        "(handler-meta :event id) IS registrar/lookup")
    ;; (handler-ids kind)
    (is (contains? (rf/handler-ids :event) :rq/inc)
        "(handler-ids :event) lists the default-realm id")
    (is (= (registrar/ids :event) (rf/handler-ids :event))
        "the facade keyword arity IS registrar/ids")))

;; ---------------------------------------------------------------------------
;; (2) the map-shaped form against the DEFAULT realm equals the keyword arity
;; ---------------------------------------------------------------------------

(deftest map-form-default-realm-equals-keyword-arity
  (testing "the {:realm … :kind …} form with an absent / nil / explicit-default
            realm equals the default-realm keyword arity (absence = default)"
    (rf/reg-event :rq/set {:doc "set"} (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-sub :rq/read {:doc "read"} (fn [db _] (:n db)))
    ;; :realm absent → default realm
    (is (= (rf/registrations :event)
           (rf/registrations {:kind :event}))
        "{:kind …} with no :realm targets the default realm")
    ;; :realm nil → default realm
    (is (= (rf/registrations :event)
           (rf/registrations {:realm nil :kind :event}))
        "{:realm nil …} targets the default realm")
    ;; :realm the explicit default id → default realm
    (is (= (rf/registrations :event)
           (rf/registrations {:realm :rf.realm/default :kind :event}))
        "{:realm :rf.realm/default …} targets the default realm")
    ;; handler-meta map form vs keyword arity
    (is (= (rf/handler-meta :event :rq/set)
           (rf/handler-meta {:realm :rf.realm/default :kind :event :id :rq/set}))
        "the handler-meta map form equals the keyword arity on the default realm")
    ;; handler-ids map form vs keyword arity
    (is (= (rf/handler-ids :event)
           (rf/handler-ids {:realm :rf.realm/default :kind :event}))
        "the handler-ids map form equals the keyword arity on the default realm")
    ;; the :pred filter still composes in the map form
    (is (= (rf/registrations :event (fn [m] (= "set" (:doc m))))
           (rf/registrations {:kind :event :pred (fn [m] (= "set" (:doc m)))}))
        "the map form's :pred filters by metadata as the positional pred does")))

;; ---------------------------------------------------------------------------
;; (3) a hermetic realm returns ONLY its own registrations
;; ---------------------------------------------------------------------------

(deftest realm-targeted-returns-only-that-realms-registrations
  (testing "the {:realm r …} form reads ONLY realm r's registrar — the same id
            registered differently in two realms resolves per-realm"
    ;; Default realm: :shared/handler → default-meta.
    (rf/reg-event :shared/handler {:doc "default"} (fn [{:keys [db]} _] {:db db}))
    (let [default-meta (registrar/lookup :event :shared/handler)
          tenant-meta  {:handler-fn (fn [db _] db) :doc "tenant"}
          ;; The tenant realm registers the SAME id with DIFFERENT metadata,
          ;; plus a tenant-only id the default realm never sees.
          seed         {:event {:shared/handler tenant-meta
                                :tenant/only    {:handler-fn (fn [db _] db)
                                                 :doc "tenant only"}}}]
      (with-tenant-realm seed
        (fn [rid]
          ;; The realm-targeted query returns ONLY the tenant realm's table.
          (is (= {:shared/handler tenant-meta
                  :tenant/only    (:tenant/only (:event seed))}
                 (rf/registrations {:realm rid :kind :event}))
              "the realm-targeted registrations are exactly the tenant realm's")
          (is (not (contains? (rf/registrations {:realm rid :kind :event})
                              :rq/inc))
              "default-realm-only ids do NOT leak into the realm-targeted view")
          ;; The same id resolves to the PER-REALM handler-meta.
          (is (= tenant-meta
                 (rf/handler-meta {:realm rid :kind :event :id :shared/handler}))
              "handler-meta resolves the tenant realm's handler for the shared id")
          (is (= default-meta
                 (rf/handler-meta :event :shared/handler))
              "the default-realm keyword arity still resolves the default handler")
          (is (not= (rf/handler-meta {:realm rid :kind :event :id :shared/handler})
                    (rf/handler-meta :event :shared/handler))
              "the two realms hold genuinely different handlers for the same id")
          ;; handler-ids is realm-scoped too.
          (is (= #{:shared/handler :tenant/only}
                 (rf/handler-ids {:realm rid :kind :event}))
              "handler-ids returns only the tenant realm's id set")
          ;; A tenant-only id is absent from the default realm.
          (is (nil? (rf/handler-meta :event :tenant/only))
              "a tenant-only id is not in the default realm")
          (is (nil? (rf/handler-meta {:realm rid :kind :event :id :rq/inc}))
              "a default-realm-only id is not in the tenant realm"))))))

(deftest realm-targeted-accepts-a-realm-map
  (testing "the :realm value may be a realm MAP (not only an id keyword)"
    (let [seed {:sub {:t/s {:handler-fn (fn [db _] db) :doc "tenant sub"}}}]
      (with-tenant-realm seed
        (fn [rid]
          (let [realm-map (realm/realm rid)]
            (is (= (rf/registrations {:realm rid :kind :sub})
                   (rf/registrations {:realm realm-map :kind :sub}))
                "passing the realm map resolves the same registrar as the id")
            (is (contains? (rf/handler-ids {:realm realm-map :kind :sub}) :t/s)
                "the realm-map form reads the tenant realm's subs")))))))

;; ---------------------------------------------------------------------------
;; (4) an unknown realm reads as the empty program
;; ---------------------------------------------------------------------------

(deftest unknown-realm-reads-empty
  (testing "querying a realm that was never created is the empty program,
            not an error"
    (is (= {} (rf/registrations {:realm :never/created :kind :event}))
        "registrations on an unknown realm is empty")
    (is (nil? (rf/handler-meta {:realm :never/created :kind :event :id :x}))
        "handler-meta on an unknown realm is nil")
    (is (= #{} (rf/handler-ids {:realm :never/created :kind :event}))
        "handler-ids on an unknown realm is empty")))

;; ---------------------------------------------------------------------------
;; (5) app-registrations enumerates a constructed app's descriptors
;;
;; EP-0023 (rf2-pl97nd.4): the facade-only `{:app :kind}` map-shaped arity is
;; removed with the public facade; the retained-internal
;; `re-frame.app-value/app-registrations` keeps the positional `[app kind]`
;; substrate inspector, which this exercises.
;; ---------------------------------------------------------------------------

(deftest app-registrations-positional-form
  (testing "(app-value/app-registrations app kind) enumerates a constructed
            app value's descriptors for a kind, nil for a kind it declares none of"
    (let [m   (av/module {:id :rq/mod
                          :events {:rq/m-evt {:doc "mod event"
                                              :handler (fn [db _] db)}}})
          app (av/app {:id :rq/app :modules [m]})]
      (is (contains? (av/app-registrations app :event) :rq/m-evt)
          "the positional form enumerates the constructed app's descriptors")
      (is (nil? (av/app-registrations app :sub))
          "a kind the app declares none of is nil"))))

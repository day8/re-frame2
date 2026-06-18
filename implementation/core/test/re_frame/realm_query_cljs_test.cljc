(ns re-frame.realm-query-cljs-test
  "EP-0023 (rf2-10nggz) — the registrar query grammar after the `:realm`
  map-arity removal.

  Post-EP-0023 the public registrar read grammar has exactly TWO shapes per
  trio member:

    (rf/registrations :event)            ; positional-keyword: default source store
    (rf/registrations {:frame f :kind k}) ; map: frame-targeted (see facade-frame-read)

  The retired pre-EP-0023 `:realm` map arity is REMOVED from the facade — a
  registrar-query MAP is now ALWAYS a frame-targeted read, so a map WITHOUT
  `:frame` is an error (`:rf.error/registrar-query-needs-frame`). The realm
  machinery survives as INTERNAL substrate (`re-frame.realm/realm-registrations`
  / `realm-handler-meta` / `realm-handler-ids`) for tooling that requires the ns
  directly (e.g. Xray's host-registry generation-bypass).

  These tests pin:
    (1) the default-realm keyword arities are unchanged (the no-regression
        headline) — `(registrations :event)` / `(handler-meta :event id)` /
        `(handler-ids :event)` behave exactly as before;
    (2) a registrar-query map WITHOUT `:frame` fails loud
        (`:rf.error/registrar-query-needs-frame`) — the `:realm` / absence-as-
        default spellings are gone;
    (3) the INTERNAL realm-scoped readers still read a hermetic realm's OWN
        registrar (the retained substrate the facade no longer exposes);
    (4) an unknown realm reads as the empty program through the internal reader
        (never an error).

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build AND the JVM `clojure -M:test` runner both pick it up. Pure CLJC."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
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

(defn- err-id
  "The `:rf.error/id` discriminator of a thrown re-frame2 error, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ---------------------------------------------------------------------------
;; (1) the default-realm keyword arities are UNCHANGED — the headline
;; ---------------------------------------------------------------------------

(deftest default-realm-keyword-arities-unchanged
  (testing "the existing keyword arities behave exactly as before — they read
            the default source store (process-global registrar)"
    (rf/reg-event :rq/inc {:doc "inc"} (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/reg-sub :rq/n {:doc "n"} (fn [db _] (:n db)))
    ;; (registrations kind)
    (is (contains? (rf/registrations :event) :rq/inc)
        "(registrations :event) lists the default source-store event")
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
        "(handler-ids :event) lists the default source-store id")
    (is (= (registrar/ids :event) (rf/handler-ids :event))
        "the facade keyword arity IS registrar/ids")))

;; ---------------------------------------------------------------------------
;; (2) a registrar-query MAP without :frame fails loud — the :realm arity is gone
;; ---------------------------------------------------------------------------

(deftest map-without-frame-fails-loud
  (testing "post-EP-0023 (rf2-10nggz) a registrar-query MAP is ALWAYS a
            frame-targeted read — a map WITHOUT :frame is an error, not a
            default-realm read; the retired :realm / absence-as-default spellings
            are removed"
    (rf/reg-event :rq/set {:doc "set"} (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    ;; the bare {:kind …} absence-as-default spelling is gone
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/registrations {:kind :event})))
        "{:kind …} with no :frame is an error")
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/handler-ids {:kind :event})))
        "handler-ids {:kind …} with no :frame is an error")
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/handler-meta {:kind :event :id :rq/set})))
        "handler-meta {:kind …} with no :frame is an error")
    ;; the retired :realm map arity spellings are gone
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/registrations {:realm nil :kind :event})))
        "the retired {:realm nil …} spelling is now an error, not the default realm")
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/registrations {:realm :rf.realm/default :kind :event})))
        "the retired {:realm :rf.realm/default …} spelling is an error")
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/handler-meta {:realm :rf.realm/default :kind :event :id :rq/set})))
        "the retired handler-meta :realm spelling is an error")
    (is (= :rf.error/registrar-query-needs-frame
           (err-id #(rf/handler-ids {:realm :rf.realm/default :kind :event})))
        "the retired handler-ids :realm spelling is an error")))

;; ---------------------------------------------------------------------------
;; (3) the INTERNAL realm-scoped readers still read a hermetic realm's registrar
;;
;; The facade no longer exposes a realm-targeted query; the realm machinery is
;; retained as internal substrate (rf2-10nggz). Tooling that needs a realm-scoped
;; read (e.g. Xray's host-registry generation-bypass) reads
;; `re-frame.realm/realm-registrations` directly. These pin that substrate.
;; ---------------------------------------------------------------------------

(deftest internal-realm-readers-read-only-that-realms-registrations
  (testing "re-frame.realm/realm-registrations reads ONLY realm r's registrar —
            the same id registered differently in two realms resolves per-realm"
    (rf/reg-event :shared/handler {:doc "default"} (fn [{:keys [db]} _] {:db db}))
    (let [default-meta (registrar/lookup :event :shared/handler)
          tenant-meta  {:handler-fn (fn [db _] db) :doc "tenant"}
          seed         {:event {:shared/handler tenant-meta
                                :tenant/only    {:handler-fn (fn [db _] db)
                                                 :doc "tenant only"}}}]
      (with-tenant-realm seed
        (fn [rid]
          ;; The internal realm-scoped read returns ONLY the tenant realm's table.
          (is (= {:shared/handler tenant-meta
                  :tenant/only    (:tenant/only (:event seed))}
                 (realm/realm-registrations rid :event))
              "the realm-scoped registrations are exactly the tenant realm's")
          (is (not (contains? (realm/realm-registrations rid :event) :rq/inc))
              "default-realm-only ids do NOT leak into the realm-scoped view")
          ;; The same id resolves to the PER-REALM handler-meta.
          (is (= tenant-meta (realm/realm-handler-meta rid :event :shared/handler))
              "realm-handler-meta resolves the tenant realm's handler for the shared id")
          (is (= default-meta (registrar/lookup :event :shared/handler))
              "the default source store still resolves the default handler")
          ;; handler-ids is realm-scoped too.
          (is (= #{:shared/handler :tenant/only}
                 (realm/realm-handler-ids rid :event))
              "realm-handler-ids returns only the tenant realm's id set")
          (is (nil? (realm/realm-handler-meta rid :event :rq/inc))
              "a default-source-store-only id is not in the tenant realm")))))

  (testing "the realm-or-id may be a realm MAP (not only an id keyword)"
    (let [seed {:sub {:t/s {:handler-fn (fn [db _] db) :doc "tenant sub"}}}]
      (with-tenant-realm seed
        (fn [rid]
          (let [realm-map (realm/realm rid)]
            (is (= (realm/realm-registrations rid :sub)
                   (realm/realm-registrations realm-map :sub))
                "the realm map resolves the same registrar as the id")
            (is (contains? (realm/realm-handler-ids realm-map :sub) :t/s)
                "the realm-map form reads the tenant realm's subs")))))))

;; ---------------------------------------------------------------------------
;; (4) an unknown realm reads as the empty program through the internal reader
;; ---------------------------------------------------------------------------

(deftest unknown-realm-reads-empty-through-internal-reader
  (testing "the internal realm reader treats a realm that was never created as
            the empty program, not an error"
    (is (= {} (realm/realm-registrations :never/created :event))
        "realm-registrations on an unknown realm is empty")
    (is (nil? (realm/realm-handler-meta :never/created :event :x))
        "realm-handler-meta on an unknown realm is nil")
    (is (= #{} (realm/realm-handler-ids :never/created :event))
        "realm-handler-ids on an unknown realm is empty")))

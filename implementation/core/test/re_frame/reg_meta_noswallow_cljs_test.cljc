(ns re-frame.reg-meta-noswallow-cljs-test
  "No-silent-swallow on `reg-*` registration METADATA KEYS.

  Per Conventions §No silent swallow: a BARE (unqualified) registration-metadata
  key the framework does not recognise MUST signal — an unknown bare key warns
  (`:rf.warning/unknown-registration-key`; the cascade continues, the key is
  stored but unread) and a RETIRED v1 bare key (canonically `:spec`, renamed to
  `:schema`) hard-errors (`:rf.error/retired-registration-key`, naming the
  canonical replacement). NAMESPACED keys are the open-map extension carve-out
  and pass silently.

  One adversarial pair per affected core registrar (`reg-event` / `reg-sub` /
  `reg-fx` / `reg-cofx` / `reg-interceptor`): a retired key throws, an unknown
  bare key warns, and a valid registration still passes. The registrars are
  exercised through their underlying registration fns (the enforcement lives in
  the fns, not the macro layer).

  ## Posture split

  The no-silent-swallow contract has TWO enforcement tiers and they do not
  share a posture. The RETIRED-key tier is a `throw` and is always-on —
  `retired-spec-key-hard-errors-per-registrar` holds under
  `scripts/test-core-prod-gate.sh` for a real reason, and is unguarded. The
  UNKNOWN-key tier is a `:rf.warning/unknown-registration-key` emit on the
  dev trace bus, so under `-Dre-frame.debug=false` nothing is emitted and an
  unguarded `unknown-bare-key-warns-per-registrar` would fail.

  The warning assertions are guarded; what is always-on beside them is the
  half of the contract production DOES honour — the registration nevertheless
  SUCCEEDS and the id is resolvable in the registrar (the cascade continues).

  The NEGATIVE warning assertions are guarded too — a negative over an empty
  trace ring is vacuous — five across the five registrar kinds:
  `namespaced-and-known-keys-pass-silently-per-registrar` certifies the absence
  of an unknown-key warning with `(is (empty? warns))` over a stream that
  carries nothing at all under the gate. Unguarded, it would be GREEN there on
  the no-throw and five free assertions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.interop :as rf.interop]
            [re-frame.events :as rf.events]
            [re-frame.subs :as rf.subs]
            [re-frame.fx :as rf.fx]
            [re-frame.cofx :as rf.cofx]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; `clear-all!` gives each test a clean registrar, but in the shared
;; `:node-test` bundle it also drops sibling namespaces'
;; ns-load registrations (e.g. reg-view'd components other suites render).
;; Snapshot first and restore in `finally` so the clean-slate is scoped to
;; this test and cross-namespace registrations survive.
(defn- reset-registry [test-fn]
  (let [snapshot (rf.test-support/snapshot-registrar)]
    (rf.registrar/clear-all!)
    (try
      (test-fn)
      (finally
        (rf.test-support/restore-registrar! snapshot)))))

(use-fixtures :each reset-registry)

;; ---- per-kind registration under one shape --------------------------------

(defn- register!
  "Register `id` of `kind` with registration-metadata `meta` through the
  registrar's own registration fn. Trailing handler / supplier / descriptor is a
  well-shaped no-op so ONLY the metadata-key classification is under test."
  [kind id meta]
  (case kind
    :event       (rf.events/reg-event id meta (fn [_ _] {}))
    :sub         (rf.subs/reg-sub id meta (fn [_db _q] nil))
    :fx          (rf.fx/reg-fx id meta (fn [_ctx _args] nil))
    :cofx        (rf.cofx/reg-cofx id meta (fn [] nil))
    :interceptor (rf.interceptor-registry/reg-interceptor* id meta {:before identity})))

(defn- caught-ex-data
  "Run `f`; return the ex-data of an ExceptionInfo it throws, or nil if it
  returns normally."
  [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- with-captured-warnings
  "Register a `:trace` listener, run `f`, and return the vector of emitted
  `:rf.warning/unknown-registration-key` trace events."
  [f]
  (let [acc (atom [])
        lid (keyword "reg-meta-test" (str (gensym "listen")))]
    (rf.trace.tooling/register-listener! lid (fn [ev] (swap! acc conj ev)))
    (try
      (f)
      (->> @acc
           (filterv #(= :rf.warning/unknown-registration-key (:operation %))))
      (finally
        (rf.trace.tooling/unregister-listener! lid)))))

;; The kinds under test, with a namespaced id per kind so nothing collides.
(def ^:private kinds
  {:event       :test/evt
   :sub         :test/sub
   :fx          :test/fx
   :cofx        :test/cofx
   :interceptor :test/icpt})

;; ---- retired bare key (`:spec`) — HARD ERROR ------------------------------

(deftest retired-spec-key-hard-errors-per-registrar
  (testing "every affected registrar HARD-ERRORS on the retired `:spec` bare key
            (renamed to `:schema`), naming the canonical replacement — the worst
            case (silently swallowing `:spec` disables payload validation)"
    (doseq [[kind id] kinds]
      (testing (str kind)
        (let [ed (caught-ex-data #(register! kind id {:spec [:map]}))]
          (is (= {:rf.error/id  :rf.error/retired-registration-key
                  :retired-key  :spec
                  :replacement  :schema
                  :kind         kind
                  :recovery     :fix-registration}
                 (select-keys ed [:rf.error/id :retired-key :replacement :kind :recovery]))
              (str "reg-" (name kind) " with a retired `:spec` key throws the
                    canonical discriminator, naming the offending key, its v2
                    replacement `:schema`, the registrar kind and the recovery")))))))

;; ---- unknown bare key — WARNING -------------------------------------------

(deftest unknown-bare-key-warns-per-registrar
  (testing "every affected registrar WARNS (does not throw) on an unknown BARE
            metadata key — a likely typo — naming the key and the recognised
            vocabulary; the registration still succeeds"
    (doseq [[kind id] kinds]
      (testing (str kind)
        (let [warns (with-captured-warnings
                      #(register! kind id {:doc "ok" :bogus-key 1}))]
          ;; ALWAYS-ON: the registration nevertheless SUCCEEDED — no
          ;; throw, and the id is resolvable in the registrar. That is the half
          ;; of §No silent swallow a production build honours: an unknown key
          ;; is a nudge, never a rejection, and the cascade continues.
          (is (some? (rf.registrar/lookup kind id))
              "the registration succeeded despite the unknown key")
          ;; Dev-instrumentation arm: the WARNING is a dev-trace emit;
          ;; nothing is emitted under `-Dre-frame.debug=false`.
          (when rf.interop/debug-enabled?
            (is (= 1 (count warns))
                (str "reg-" (name kind) " emits exactly one unknown-key warning"))
            (let [{:keys [tags]} (first warns)]
              (is (= {:kind kind :id id :unknown-keys [:bogus-key]}
                     (select-keys tags [:kind :id :unknown-keys]))
                  "the warning names the registrar kind, the registration, and
                   exactly the offending bare key")
              (is (contains? (set (:known tags)) :doc)
                  ":known carries the recognised bare vocabulary")
              (is (string? (:reason tags))))))))))

;; ---- namespaced extension key + known keys — SILENT (the carve-out) -------

(deftest namespaced-and-known-keys-pass-silently-per-registrar
  (testing "a valid registration — known bare keys, including `:schema` (the
            v2 name the retired-`:spec` guard must never reject), plus a
            NAMESPACED extension key (the open-map carve-out) — passes with NO
            unknown-key warning and NO throw"
    (doseq [[kind id] kinds]
      (testing (str kind)
        (let [ed    (atom :not-thrown)
              warns (with-captured-warnings
                      #(reset! ed (caught-ex-data
                                    (fn []
                                      (register! kind id
                                                 {:doc            "a valid registration"
                                                  :schema         [:map]
                                                  :myapp/extra-id 42})))))]
          ;; ALWAYS-ON: the carve-out does not throw, and the registration lands.
          (is (nil? @ed)
              (str "reg-" (name kind)
                   " with known + namespaced keys must not throw; got " (pr-str @ed)))
          (is (some? (rf.registrar/lookup kind id))
              "the namespaced-extension registration landed")
          ;; Dev-instrumentation arm — vacuous under the gate: the warning
          ;; stream is empty for EVERY key there, carve-out or typo.
          (when rf.interop/debug-enabled?
            (is (empty? warns)
                (str "no unknown-key warning for known + namespaced keys; got "
                     (pr-str warns)))))))))

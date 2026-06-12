(ns re-frame.resources-cache-key-identity-cljs-test
  "EP-0012 cache-key byte-identity round-trip (rf2-9e0tyq).

  The full fix for the resource cache-key `=`-collapse: the `:entries` map,
  the reverse indexes, and the work-ledger map are keyed on the CEDN-1 byte
  `key-id` (`state/key-id` / `work-ledger/work-id-id`) rather than the scoped
  resource key VECTOR under Clojure `=`. The vector is the kind-preserving
  identity (`rf2-wgutc2`) carried as each entry's `:resource/key`, embedded in
  the work-id, on the SSR wire, and in trace payloads.

  Clojure `=` collapses `(= [1 2 3] '(1 2 3))` to TRUE, but the byte key-id
  (a `canonical-bytes` STRING) never collapses, so a list-params key and a
  vector-params key get DISTINCT entries / work-ids / index members.

  This suite proves the fix holds through ALL FOUR carriers the scoped key
  flows into (the blast radius that deferred the fix):

    1. `:entries` map key (the collapse site);
    2. the work-ledger work-id (`[:rf.work/resource <scoped-key> <gen>]`,
       a runtime-db map key + the stale-suppression basis);
    3. the SSR hydration wire (`project-resources-runtime-db` → install →
       `hydrate-runtime-db` recompute) and the epoch restore reconcile
       (`reconcile-on-restore`);
    4. trace payloads (the scoped-key vector rides verbatim).

  CRITICAL: the byte key-id is a plain UTF-8 STRING, so it rides the SSR /
  epoch / trace wire with NO custom transit handler — the failure mode a
  `deftype` key would have silently introduced (it would pass an in-process
  unit gate yet break serialization). The round-trip tests below install the
  PROJECTED wire shape and assert the two distinct entries survive."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   #?(:cljs [cljs.reader])
   [re-frame.identity :as identity]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   ;; the façade publishes the SSR projection / reconcile hooks + registrar.
   [re-frame.resources]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; ---- the two adversarial keys: same scope+rid, list-vs-vector params -------

(def ^:private kv (state/scoped-resource-key :rf.scope/global :r/x {:xs [1 2 3]}))
(def ^:private kl (state/scoped-resource-key :rf.scope/global :r/x {:xs '(1 2 3)}))

(defn- loaded-entry
  "A loaded durable entry stamped with its scoped-key `sk` (the runtime
  stamps `:resource/key` on every entry now)."
  [sk data tags]
  (-> (state/empty-entry :r/x sk)
      (merge {:status :loaded :data data :loaded-at 1000 :stale-at 9.0e15
              :generation 1 :tags tags})))

(defn- byte-keyed-entries
  "Build a runtime-db `:entries` map the runtime way — keyed on the byte
  `key-id`, each entry carrying its own `:resource/key`. Takes a SEQUENCE of
  `[scoped-key entry]` pairs (NOT a map literal — a `{kv … kl …}` literal
  would itself throw `Duplicate key` because `kv` and `kl` are Clojure-`=`,
  which is the very collapse this fix routes around)."
  [pairs]
  (into {} (map (fn [[sk e]] [(state/key-id sk) e])) pairs))

;; ===========================================================================
;; Carrier 1 — the :entries map key
;; ===========================================================================

(deftest carrier-1-entries-map-keys-distinctly
  (testing "list- and vector-params keys are `=` as VECTORS but their byte
            key-ids differ, so the :entries map holds TWO entries"
    (is (= kv kl) "the vectors are Clojure-= (the collapse the fix routes around)")
    (is (not= (state/key-id kv) (state/key-id kl))
        "the byte key-ids differ (v[…] vs l(…))")
    (let [es (byte-keyed-entries [[kv (loaded-entry kv {:v 1} #{:t})]
                                   [kl (loaded-entry kl {:l 1} #{:t})]])]
      (is (= 2 (count es)) "two distinct entries — no =-collapse")
      (is (= {:v 1} (:data (get es (state/key-id kv)))) "vector entry intact")
      (is (= {:l 1} (:data (get es (state/key-id kl)))) "list entry intact")
      (is (= kv (:resource/key (get es (state/key-id kv)))) "vector entry keeps its vector key")
      (is (seq? (-> (get es (state/key-id kl)) :resource/key (nth 2) :xs))
          "the list entry's :resource/key PRESERVES the list kind (not coerced to a vector)"))))

;; ===========================================================================
;; Carrier 2 — the work-ledger work-id (runtime-db map key + suppression basis)
;; ===========================================================================

(deftest carrier-2-work-ledger-keys-distinctly
  (testing "the embedded work-id keys the work-ledger map on its own byte id"
    (let [wv (work-ledger/resource-work-id kv 1)
          wl (work-ledger/resource-work-id kl 1)]
      (is (= wv wl) "the work-id VECTORS are = (they embed the key vector)")
      (is (not= (work-ledger/work-id-id wv) (work-ledger/work-id-id wl))
          "their byte work-id-ids differ")
      (let [recv (work-ledger/work-record {:work-id wv :frame-id :f :resource-key kv
                                           :generation 1 :transport :rf.http/managed})
            recl (work-ledger/work-record {:work-id wl :frame-id :f :resource-key kl
                                           :generation 1 :transport :rf.http/managed})
            rdb  (-> {} (work-ledger/put-record wv recv) (work-ledger/put-record wl recl))]
        (is (= 2 (count (:rf.runtime/work-ledger rdb))) "two distinct ledger rows")
        (is (= wv (:work/id (work-ledger/get-record rdb wv))) "vector work record reads back")
        (is (= wl (:work/id (work-ledger/get-record rdb wl))) "list work record reads back")
        (is (= kv (:resource/key (work-ledger/get-record rdb wv))))
        (is (= kl (:resource/key (work-ledger/get-record rdb wl))))))))

;; ===========================================================================
;; Carrier 3 — the SSR hydration wire (project → install → hydrate recompute)
;; ===========================================================================

(deftest carrier-3-ssr-projection-round-trip-keeps-two-entries
  (testing "ADVERSARIAL: a frame holding BOTH a list- and a vector-params entry
            PROJECTS to two distinct wire entries, and HYDRATION reinstalls them
            as two distinct entries (the byte key-id rides the wire as a plain
            string — no transit handler needed; a deftype key would break here)"
    (let [es   (byte-keyed-entries [[kv (loaded-entry kv {:v 1} #{:t})]
                                     [kl (loaded-entry kl {:l 1} #{:t})]])
          rdb  {state/resources-key {:entries es :tag-index {} :owner-index {}}}
          ;; SERVER projection (the :ssr/extend-runtime-db-projection body)
          proj (ssr/project-resources-runtime-db rdb)
          wired (get-in proj [state/resources-key :entries])]
      (is (= 2 (count wired)) "two distinct wire entries projected")
      ;; the wire keys are plain strings (transit/JSON-safe — the load-bearing
      ;; property a deftype key would have violated).
      (is (every? string? (keys wired)) "wire map keys are plain canonical-bytes STRINGS")
      ;; round-trip through a pr-str/read-string (a stand-in for the transit
      ;; wire) — the keys + kind-preserving :resource/key survive verbatim.
      (let [round-tripped #?(:clj  (read-string (pr-str wired))
                             :cljs (cljs.reader/read-string (pr-str wired)))]
        (is (= wired round-tripped) "the projected wire entries survive an EDN round-trip"))
      ;; CLIENT hydration reinstalls + recomputes indexes over the two entries.
      (let [installed {state/resources-key {:entries wired}}
            out (ssr/hydrate-runtime-db installed :app/main)
            out-es (get-in out [state/resources-key :entries])
            tag-members (get-in out [state/resources-key :tag-index :t])]
        (is (= 2 (count out-es)) "hydration installs TWO distinct entries (no collapse)")
        (is (= #{(state/key-id kv) (state/key-id kl)} (set (keys out-es)))
            "both byte key-ids present after hydration")
        (is (= 2 (count tag-members))
            "the recomputed tag-index has TWO distinct members for the shared tag")
        (is (= #{(state/key-id kv) (state/key-id kl)} tag-members)
            "the tag-index members are the byte key-ids of both entries")))))

;; ===========================================================================
;; Carrier 3b — the epoch restore reconcile (unprojected snapshot)
;; ===========================================================================

(deftest carrier-3b-restore-reconcile-keeps-two-entries
  (testing "ADVERSARIAL: an UNPROJECTED restored snapshot holding both a list-
            and a vector-params entry reconciles to TWO distinct entries with a
            recomputed two-member shared tag index (restore never collapses)"
    (let [es  (byte-keyed-entries [[kv (loaded-entry kv {:v 1} #{:t})]
                                    [kl (loaded-entry kl {:l 1} #{:t})]])
          rdb {state/resources-key {:entries es :tag-index {} :owner-index {}}
               state/work-ledger-key {}}
          out (ssr/reconcile-on-restore rdb)
          out-es (get-in out [state/resources-key :entries])]
      (is (= 2 (count out-es)) "two distinct entries survive restore reconcile")
      (is (= #{(state/key-id kv) (state/key-id kl)}
             (get-in out [state/resources-key :tag-index :t]))
          "the recomputed tag-index keeps both byte key-ids for the shared tag"))))

;; ===========================================================================
;; Carrier 4 — trace payloads carry the kind-preserving scoped-key vector
;; ===========================================================================

(deftest carrier-4-trace-scoped-key-preserves-kind
  (testing "the scoped-key VECTOR carried in trace payloads / Xray is the
            kind-preserving canonical form (a list value stays a list) — the
            trace carrier never sees the byte id"
    ;; the list-params key's params VALUE is a genuine list (seq?), not a vector
    (is (seq? (:xs (nth kl 2))) "the list-params scoped key keeps its list kind")
    (is (vector? (:xs (nth kv 2))) "the vector-params scoped key keeps its vector kind")
    ;; and the two are byte-distinct identities — a trace consumer can tell them
    ;; apart (the authoritative CEDN-1 identity, not Clojure =).
    (is (not (identity/identical-identity? kv kl))
        "the two trace-carried keys are byte-distinct CEDN-1 identities")))

;; The real-ensure path (the runtime stamping `:resource/key` on a freshly
;; minted entry, keyed under the byte `key-id`) is exercised by the broader
;; `resources-runtime-cljs-test` suite (its `entry` helper reads via
;; `state/entry-path`, which byte-keys) — this focused file pins the cache-key
;; byte-identity round-trip through the four serialization carriers.

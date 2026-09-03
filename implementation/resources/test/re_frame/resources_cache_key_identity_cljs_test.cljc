(ns re-frame.resources-cache-key-identity-cljs-test
  "EP-0012 cache-key byte-identity round-trip (rf2-9e0tyq).

  The full fix for the resource cache-key `=`-collapse: the `:entries` map,
  the reverse indexes, and the work-ledger map are keyed on the CEDN-1 byte
  `key-id` (`rf.resources.state/key-id` / `rf.resources.work-ledger/work-id-id`) rather than the scoped
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
   [re-frame.identity :as rf.identity]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   ;; the façade publishes the SSR projection / reconcile hooks + registrar.
   [re-frame.resources]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; ---- the two adversarial keys: same scope+rid, list-vs-vector params -------

(def ^:private kv (rf.resources.state/scoped-resource-key :rf.scope/global :r/x {:xs [1 2 3]}))
(def ^:private kl (rf.resources.state/scoped-resource-key :rf.scope/global :r/x {:xs '(1 2 3)}))

(defn- loaded-entry
  "A loaded durable entry stamped with its scoped-key `sk` (the runtime
  stamps `:resource/key` on every entry now)."
  [sk data tags]
  (-> (rf.resources.state/empty-entry :r/x sk)
      (merge {:status :loaded :data data :loaded-at 1000 :stale-at 9.0e15
              :generation 1 :tags tags})))

(defn- byte-keyed-entries
  "Build a runtime-db `:entries` map the runtime way — keyed on the byte
  `key-id`, each entry carrying its own `:resource/key`. Takes a SEQUENCE of
  `[scoped-key entry]` pairs (NOT a map literal — a `{kv … kl …}` literal
  would itself throw `Duplicate key` because `kv` and `kl` are Clojure-`=`,
  which is the very collapse this fix routes around)."
  [pairs]
  (into {} (map (fn [[sk e]] [(rf.resources.state/key-id sk) e])) pairs))

;; ===========================================================================
;; Carrier 1 — the :entries map key
;; ===========================================================================

(deftest carrier-1-entries-map-keys-distinctly
  (testing "list- and vector-params keys are `=` as VECTORS but their byte
            key-ids differ, so the :entries map holds TWO entries"
    (is (= kv kl) "the vectors are Clojure-= (the collapse the fix routes around)")
    (is (not= (rf.resources.state/key-id kv) (rf.resources.state/key-id kl))
        "the byte key-ids differ (v[…] vs l(…))")
    (let [es (byte-keyed-entries [[kv (loaded-entry kv {:v 1} #{:t})]
                                   [kl (loaded-entry kl {:l 1} #{:t})]])]
      (is (= 2 (count es)) "two distinct entries — no =-collapse")
      (is (= {:v 1} (:data (get es (rf.resources.state/key-id kv)))) "vector entry intact")
      (is (= {:l 1} (:data (get es (rf.resources.state/key-id kl)))) "list entry intact")
      (is (= kv (:resource/key (get es (rf.resources.state/key-id kv)))) "vector entry keeps its vector key")
      (is (seq? (-> (get es (rf.resources.state/key-id kl)) :resource/key (nth 2) :xs))
          "the list entry's :resource/key PRESERVES the list kind (not coerced to a vector)"))))

;; ===========================================================================
;; Carrier 2 — the work-ledger work-id (runtime-db map key + suppression basis)
;; ===========================================================================

(deftest carrier-2-work-ledger-keys-distinctly
  (testing "the embedded work-id keys the work-ledger map on its own byte id"
    (let [wv (rf.resources.work-ledger/resource-work-id kv 1)
          wl (rf.resources.work-ledger/resource-work-id kl 1)]
      (is (= wv wl) "the work-id VECTORS are = (they embed the key vector)")
      (is (not= (rf.resources.work-ledger/work-id-id wv) (rf.resources.work-ledger/work-id-id wl))
          "their byte work-id-ids differ")
      (let [recv (rf.resources.work-ledger/work-record {:work-id wv :frame-id :f :resource/key kv
                                           :generation 1 :transport :rf.http/managed})
            recl (rf.resources.work-ledger/work-record {:work-id wl :frame-id :f :resource/key kl
                                           :generation 1 :transport :rf.http/managed})
            rdb  (-> {} (rf.resources.work-ledger/put-record wv recv) (rf.resources.work-ledger/put-record wl recl))]
        (is (= 2 (count (:rf.runtime/work-ledger rdb))) "two distinct ledger rows")
        (is (= wv (:work/id (rf.resources.work-ledger/get-record rdb wv))) "vector work record reads back")
        (is (= wl (:work/id (rf.resources.work-ledger/get-record rdb wl))) "list work record reads back")
        (is (= kv (:resource/key (rf.resources.work-ledger/get-record rdb wv))))
        (is (= kl (:resource/key (rf.resources.work-ledger/get-record rdb wl))))))))

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
          rdb  {rf.resources.state/resources-key {:entries es :tag-index {} :owner-index {}}}
          ;; SERVER projection (the :ssr/extend-runtime-db-projection body)
          proj (rf.resources.ssr/project-resources-runtime-db rdb)
          wired (get-in proj [rf.resources.state/resources-key :entries])]
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
      (let [installed {rf.resources.state/resources-key {:entries wired}}
            out (rf.resources.ssr/hydrate-runtime-db installed :app/main)
            out-es (get-in out [rf.resources.state/resources-key :entries])
            tag-members (get-in out [rf.resources.state/resources-key :tag-index :t])]
        (is (= 2 (count out-es)) "hydration installs TWO distinct entries (no collapse)")
        (is (= #{(rf.resources.state/key-id kv) (rf.resources.state/key-id kl)} (set (keys out-es)))
            "both byte key-ids present after hydration")
        (is (= 2 (count tag-members))
            "the recomputed tag-index has TWO distinct members for the shared tag")
        (is (= #{(rf.resources.state/key-id kv) (rf.resources.state/key-id kl)} tag-members)
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
          rdb {rf.resources.state/resources-key {:entries es :tag-index {} :owner-index {}}
               rf.resources.state/work-ledger-key {}}
          out (rf.resources.ssr/reconcile-on-restore rdb)
          out-es (get-in out [rf.resources.state/resources-key :entries])]
      (is (= 2 (count out-es)) "two distinct entries survive restore reconcile")
      (is (= #{(rf.resources.state/key-id kv) (rf.resources.state/key-id kl)}
             (get-in out [rf.resources.state/resources-key :tag-index :t]))
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
    (is (not (rf.identity/identical-identity? kv kl))
        "the two trace-carried keys are byte-distinct CEDN-1 identities")))

;; The real-ensure path (the runtime stamping `:resource/key` on a freshly
;; minted entry, keyed under the byte `key-id`) is exercised by the broader
;; `resources-runtime-cljs-test` suite (its `entry` helper reads via
;; `rf.resources.state/entry-path`, which byte-keys) — this focused file pins the cache-key
;; byte-identity round-trip through the four serialization carriers.

;; ===========================================================================
;; rf2-eynsfe — instant params vs same-looking STRING params are DISTINCT
;; resource identities end-to-end (the canonical tagged-instant form)
;; ===========================================================================
;;
;; Before the tagged-instant fix, `canonical` collapsed an instant to a BARE
;; string, so `{:at #inst "…"}` and `{:at "…"}` aliased to ONE scoped key and
;; ONE `s:`-keyed byte key-id — an instant resource param silently shared a
;; cache entry / work-id with a look-alike string param, contradicting Spec 016
;; §Resource identity. The canonical form of an instant is now the reserved
;; tagged tuple `[:rf.identity/instant <text>]`, which `canonical-bytes` encodes
;; to `t:<text>` (a string stays `s:`), so the two params are DISTINCT through
;; the scoped key, the byte key-id, and the work-id. Two SPELLINGS of one
;; instant still dedupe to one identity.

(def ^:private instant-text "2026-06-10T00:00:00.000Z")
(def ^:private ki (rf.resources.state/scoped-resource-key
                    :rf.scope/global :r/x {:at #inst "2026-06-10T00:00:00.000-00:00"}))
(def ^:private ks (rf.resources.state/scoped-resource-key
                    :rf.scope/global :r/x {:at instant-text}))

(deftest instant-vs-string-params-distinct-identity
  (testing "an instant param canonicalizes to the reserved tagged tuple, NOT a bare string"
    (is (= {:at [:rf.identity/instant instant-text]} (nth ki 2))
        "the instant param carries the kind-preserving tagged tuple")
    (is (= {:at instant-text} (nth ks 2))
        "the string param stays a plain string"))
  (testing "instant- and string-params keys are DISTINCT scoped keys AND byte key-ids"
    (is (not= ki ks) "the scoped-key vectors differ (tagged tuple vs string param)")
    (is (not= (rf.resources.state/key-id ki) (rf.resources.state/key-id ks))
        "the byte key-ids differ (t:<text> vs s:\"<text>\")")
    (is (not (rf.identity/identical-identity? ki ks))
        "the two keys are byte-distinct CEDN-1 identities"))
  (testing "the two params key an :entries map DISTINCTLY end-to-end (no alias)"
    (let [es (byte-keyed-entries [[ki (loaded-entry ki {:from :instant} #{:t})]
                                   [ks (loaded-entry ks {:from :string} #{:t})]])]
      (is (= 2 (count es))
          "two distinct entries — the instant param no longer aliases the string param")
      (is (= {:from :instant} (:data (get es (rf.resources.state/key-id ki)))))
      (is (= {:from :string}  (:data (get es (rf.resources.state/key-id ks)))))))
  (testing "the work-ledger work-id is likewise distinct for instant vs string params"
    (let [wi (rf.resources.work-ledger/resource-work-id ki 1)
          ws (rf.resources.work-ledger/resource-work-id ks 1)]
      (is (not= (rf.resources.work-ledger/work-id-id wi) (rf.resources.work-ledger/work-id-id ws))
          "distinct byte work-id-ids end-to-end")))
  (testing "two SPELLINGS of ONE instant dedupe to the SAME scoped key + byte key-id"
    ;; a host Date and its #inst EDN literal for one moment (both read as the
    ;; host instant on each host) canonicalize to the SAME tagged tuple.
    (let [ka (rf.resources.state/scoped-resource-key
               :rf.scope/global :r/x {:at #?(:clj  (java.util.Date. 1781049600000)
                                             :cljs (js/Date. 1781049600000))})
          kb (rf.resources.state/scoped-resource-key
               :rf.scope/global :r/x {:at #inst "2026-06-10T00:00:00.000-00:00"})]
      (is (= ka kb) "the two instant spellings collapse to one scoped key")
      (is (= (rf.resources.state/key-id ka) (rf.resources.state/key-id kb))
          "one instant → one byte key-id (dedupe holds)"))))

;; ===========================================================================
;; rf2-j1rm93 — scoped resource KEY vs registered resource ID are ONE name per
;; fact and must never be confused (the spelling-unification adversarial gate)
;; ===========================================================================
;;
;; Two distinct identity facts share the family but NOT the spelling:
;;   - `:resource/id`  — the REGISTERED resource id, a bare keyword (`:r/x`).
;;   - `:resource/key` — the SCOPED cache key, the tuple
;;                       `[cache-scope resource-id canonical-params]`.
;; The tuple's 2nd element IS the resource id, which is exactly why the two are
;; easy to confuse. The durable entry carries BOTH, under DISTINCT keys, with
;; the canonical spelling `:resource/key` for the scoped key on every data
;; shape (durable field, work record, verification payload, correlation, trace
;; tag). The unqualified `:resource-key` spelling is RETIRED — nothing on a
;; data shape may carry it. The lifecycle CATEGORY name for "a scoped key owns
;; this entry" is the unqualified `:scoped-resource-key` (a derivation-algebra
;; classification, NOT the key value) — distinct from both facts above.

(deftest scoped-key-vs-resource-id-not-confused
  (testing "the durable entry carries the registered :resource/id (a bare
            keyword) AND the scoped :resource/key (a tuple) under DISTINCT keys"
    (let [entry (rf.resources.state/empty-entry :r/x kv)]
      ;; the registered id is a bare keyword, NOT the tuple
      (is (= :r/x (:resource/id entry)) ":resource/id is the registered keyword id")
      (is (keyword? (:resource/id entry)) "the registered id is a keyword, never a tuple")
      ;; the scoped key is the full tuple, NOT the bare id
      (is (= kv (:resource/key entry)) ":resource/key is the scoped cache-key tuple")
      (is (vector? (:resource/key entry)) "the scoped key is the tuple, never a bare keyword")
      ;; the two facts are NOT equal — confusing them is a category error
      (is (not= (:resource/id entry) (:resource/key entry))
          "the registered id and the scoped key are different facts")
      ;; the resource id IS embedded as the 2nd tuple element (the reason for the
      ;; confusion the unification guards against) — and it round-trips equal
      (is (= (:resource/id entry) (second (:resource/key entry)))
          "the scoped key's 2nd element is the registered id (the embedded fact)"))
    (testing "the work record names the SAME two distinct facts the SAME way"
      (let [rec (rf.resources.work-ledger/work-record
                  {:work-id (rf.resources.work-ledger/resource-work-id kv 1)
                   :frame-id :f :resource/key kv :generation 1
                   :transport :rf.http/managed})]
        ;; the durable scoped-key field on the work record is :resource/key (the
        ;; one spelling) — there is NO :resource-key key anywhere on the shape
        (is (= kv (:resource/key rec)) "the work record's scoped key is :resource/key")
        (is (nil? (:resource-key rec))
            "the retired unqualified :resource-key spelling is absent from the work record")
        ;; and the registered id is still reachable as the 2nd tuple element,
        ;; never duplicated under a confusable bare :resource-id key on the row
        (is (= :r/x (second (:resource/key rec)))
            "the registered id reads out of the scoped key, not a separate row field"))))
  (testing "the durable scoped-key field is the canonical :resource/key spelling,
            never the retired unqualified :resource-key"
    (let [entry (rf.resources.state/empty-entry :r/x kv)]
      (is (contains? entry :resource/key) "the canonical :resource/key field is present")
      (is (not (contains? entry :resource-key))
          "the retired :resource-key spelling never appears on a data shape"))))

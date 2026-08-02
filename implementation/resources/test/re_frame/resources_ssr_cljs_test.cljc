(ns re-frame.resources-ssr-cljs-test
  "SSR / hydration for the Resources artefact (rf2-ctk2av, Spec 016 §SSR
  and hydration / §Restore and replay — EP-0003 slice 8).

  These JVM+CLJS unit tests pin the SSR slice's contract. SSR runs on the
  JVM, so the whole suite is CLJC and the JVM run (`clojure -M:test`) is
  the load-bearing gate:

    1. SERVER projection — `project-resources-runtime-db` rides ONLY the
       durable `:entries` (never the indexes; never all of runtime-db); a
       `:sensitive?` resource is REDACTED (metadata only); a `:large?`
       resource is OMITTED (no data key); `projection-metadata` records
       the serialized / redacted / omitted / fresh / stale /
       refetch-on-client decision per entry;
    2. SERVER blocking drain — `blocking-settled?` is true iff every
       blocking entry has settled; `settle-blocking-timeout` settles an
       unsettled blocking entry as a structured first-load failure +
       a route-blocking-failure record (it never hangs);
    3. CLIENT hydration — `hydrate-runtime-db` recomputes the reverse
       indexes from entries (never trusts the wire), orphans SSR owners,
       clears `:current-work`, surfaces clock skew, and NEVER crosses
       scopes;
    4. NO double-fetch — `hydrate-refetch-plan` omits fresh-with-data
       entries; includes stale (background refetch) and metadata-only
       (redacted / omitted) entries;
    5. SCOPE isolation — a hydrated entry under scope A never leaks to
       scope B; the index recompute keys on the entry's own scoped key.

  The reconcile is wired into SSR's `:rf/hydrate` handler via the
  `:resources/hydrate-runtime-db` late-bind hook; the round-trip is
  exercised through that hook end-to-end."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.fx :as fx]
   [re-frame.frame :as frame]
   [re-frame.late-bind :as late-bind]
   [re-frame.privacy :as privacy]
   ;; load-bearing side-effecting requires: the façade publishes the SSR
   ;; projection + reconcile hooks + registers the resource registrar kind.
   [re-frame.resources]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   [re-frame.resources.work-ledger :as work-ledger]
   ;; production HTTP fx surface (so the transport feature probe resolves on the
   ;; real-path integration drain test); the fetch + abort are overridden by
   ;; capturing no-ops in `capturing-transport-fixture` so no real request fires.
   [re-frame.http.managed]
   ;; SSR artefact — the :rf/hydrate handler that consults the reconcile hook.
   [re-frame.ssr]
   ;; the consumer of :ssr/extend-runtime-db-projection (project-runtime-db).
   [re-frame.ssr.payload-policy :as payload-policy]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; ---- capturing transport (decouples the real-path drain test from HTTP) ----
;;
;; The §2c integration drain test enqueues a blocking resource through the REAL
;; `:rf.resource/ensure` event path (which writes the entry `:loading`, a
;; `:running` work-ledger row, AND a host-handle side-table slot) and settles it
;; through the REAL `:rf.resource.internal/succeeded` reply — so the work-ledger
;; row + host handle move together with the entry, exactly as the runtime does
;; it. Overriding the managed-HTTP fxs with capturing no-ops keeps the writes
;; deterministic and stops any real fetch / abort from firing (mirrors the
;; work-ledger suite's fixture).

(def ^:private aborts (atom []))

(defn- capturing-transport-fixture
  "Override the real `:rf.http/managed` + `:rf.http/managed-abort` fxs with
  capturing no-ops so the real-path ensure writes are deterministic and no
  real fetch / abort fires. Composed INSIDE the reset-runtime fixture."
  [f]
  (reset! aborts [])
  (fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (fx/reg-fx :rf.http/managed-abort (fn [_ctx request-id] (swap! aborts conj request-id) nil))
  (f))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  capturing-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- reg!
  "Register a resource id with optional spec overrides (defaults to a
  global-scope slug resource)."
  ([id] (reg! id {}))
  ([id overrides]
   (rf/clear-resource id)
   (let [spec (merge {:scope         :rf.scope/global
                      :params-schema [:map [:slug :string]]
                      :request       (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                      :tags          (fn [{:keys [slug]} _] #{[:article slug]})}
                     overrides)]
     (rf/reg-resource id (dissoc spec :request) (:request spec)))))

(defn- entry
  "A loaded durable entry under a scoped key, with the supplied status /
  data / timestamps. Mirrors the runtime's durable shape."
  [{:keys [resource-id status data loaded-at stale-at invalidated-at
           generation current-work tags owners refresh-error]
    :or   {status :loaded generation 1 tags #{} owners #{}}}]
  (merge (state/empty-entry resource-id)
         {:status         status
          :data           data
          :loaded-at      loaded-at
          :stale-at       stale-at
          :invalidated-at invalidated-at
          :generation     generation
          :current-work   current-work
          :tags           tags
          :active-owners  owners
          :refresh-error  refresh-error}))

(defn- runtime-db-with
  "A runtime-db carrying a `:rf.runtime/resources :entries` map. rf2-9e0tyq:
  the runtime keys `:entries` on the CEDN-1 byte `key-id` and stamps each
  entry's own `:resource/key` vector, so this helper RE-KEYS the
  `{scoped-key-vector entry}` map callers supply into the runtime's
  `{key-id (assoc entry :resource/key scoped-key)}` shape — the call sites
  stay written in the natural scoped-key-vector form."
  [entries]
  {state/resources-key {:entries (into {}
                                       (map (fn [[sk e]]
                                              [(state/key-id sk) (assoc e :resource/key sk)]))
                                       entries)
                        :tag-index {} :owner-index {}}})

;; rf2-9e0tyq — the runtime keys `:entries` on the CEDN-1 byte `key-id` and
;; stamps each entry's `:resource/key`. These helpers build / read the
;; byte-keyed `:entries` map directly so the unit tests speak the runtime's
;; shape (the `key-id` translation is what `blocking-settled?` /
;; `settle-blocking-timeout` / the drain loop / projection apply to the
;; scoped-key vectors).
(defn- entries*
  "Build a byte-keyed `:entries` map from `{scoped-key-vector entry}`,
  stamping each entry's `:resource/key` (mirrors `runtime-db-with`)."
  [m]
  (into {} (map (fn [[sk e]] [(state/key-id sk) (assoc e :resource/key sk)])) m))
(defn- entry-by [entries sk] (get entries (state/key-id sk)))

(def ^:private gkey
  ;; canonical global-scope key for :article/by-slug {:slug "x"}
  (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"}))

;; ===========================================================================
;; 1. SERVER projection
;; ===========================================================================

(deftest projection-rides-only-entries-never-indexes
  (reg! :article/by-slug)
  (testing "the projection carries :entries, never :tag-index / :owner-index,
            and never all of runtime-db (Spec 016 §SSR and hydration clause 4)"
    (let [e   (entry {:resource-id :article/by-slug :data {:title "X"}
                      :loaded-at 1000 :stale-at 9.0e15})
          rdb (assoc (runtime-db-with {gkey e})
                     :rf.runtime/machines {:snapshots {}}
                     :rf.runtime/routing  {:current {:route :x}})
          proj (ssr/project-resources-runtime-db rdb)]
      (is (= #{state/resources-key} (set (keys proj)))
          "only the :rf.runtime/resources subsystem key is projected")
      (is (= #{:entries} (set (keys (get proj state/resources-key))))
          "only :entries rides — indexes are recomputable-from-entries")
      (is (contains? (get-in proj [state/resources-key :entries]) (state/key-id gkey))))))

(deftest projection-empty-when-no-entries
  (testing "no resource entries → empty projection (the hook contributes nothing)"
    (is (= {} (ssr/project-resources-runtime-db {})))
    (is (= {} (ssr/project-resources-runtime-db (runtime-db-with {}))))))

(defn- only-wire-entry
  "The single projected wire entry as `[projected-scoped-key wire-entry]` from
  a one-entry projection. rf2-9e0tyq: the projection MAP is keyed on the
  opaque byte `key-id`; the projected SCOPED KEY (scope+params verbatim for a
  `:serialize` resource, redacted for `:sensitive?` / `:large?`) rides as the
  wire entry's own `:resource/key`. The privacy/distinctness assertions are
  about that projected scoped key, so this returns it as the first element
  (the byte map-key is exercised separately by the byte-keying tests)."
  [proj]
  (let [we (val (first (get-in proj [state/resources-key :entries])))]
    [(:resource/key we) we]))

(defn- only-projection-metadata
  "The single per-entry projection metadata map for a one-entry runtime-db,
  against the same fixed clock the other metadata tests read (5000).

  rf2-4bjep — this is the observation point for an entry whose ROW is withheld.
  A coarse `:redact` / `:omit` entry is re-keyed on both components, so like a
  per-slot-declared `:serialize` entry it is not addressable by the key the live
  client derives and its row does not ride at all. The projection decision is
  still fully observable — `:disposition` says why, `:projected-key` says what
  it projected to, `:withheld?` says the row did not ride — which is what keeps
  the SSR and trace-egress derivations of one answer comparable (rf2-5e2ye,
  rf2-dl7bz) now that the wire entry is no longer a carrier."
  [runtime-db]
  (first (ssr/projection-metadata
           nil 5000 (get-in runtime-db [state/resources-key :entries]))))

(deftest sensitive-resource-row-is-withheld-not-shipped-redacted
  (reg! :secret/thing {:sensitive? true})
  (testing "rf2-4bjep — a `:sensitive?` resource's row does not ride AT ALL. Its
            key is re-keyed on both components, so no live client can address
            it, and an emptied row would be an ownerless duplicate nothing
            collects. The projection decision stays fully observable on the
            metadata"
    (let [k   (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "s"})
          e   (entry {:resource-id :secret/thing :data {:ssn "123-45-6789"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :refresh-error {:kind :rf.http/http-5xx}})
          rdb (runtime-db-with {k e})
          proj (ssr/project-resources-runtime-db rdb)
          m    (only-projection-metadata rdb)
          wk   (:projected-key m)]
      (is (empty? (get-in proj [state/resources-key :entries]))
          (str "no row rides — stated as absence of the ROW, not of its data: "
               (pr-str proj)))
      (is (not (str/includes? (pr-str proj) "123-45-6789"))
          "so the sensitive data cannot ride, verbatim or otherwise")
      (is (= :redacted (:disposition m)) "the metadata still names WHY")
      (is (true? (:withheld? m)))
      (is (true? (:refetch-on-client? m)) "and admits the client must fetch it")
      (is (= :loaded (:status m)) "metadata (status / timestamps) is still reported")
      (testing "rf2-otms75 — the projected KEY's scope + params are redacted"
        (is (= :secret/thing (nth wk 1)) "the resource-id rides verbatim (position 1, never sensitive)")
        (is (contains? (nth wk 0) :rf/redacted)
            "the scope is a redaction token, not the raw scope")
        (is (not= :rf.scope/global (nth wk 0)) "the raw scope does not ride")
        (is (map? (nth wk 2)) "the params component is a redaction token, not raw")
        (is (contains? (nth wk 2) :rf/redacted))
        (is (not= {:slug "s"} (nth wk 2)) "the raw sensitive params do NOT ride")))))

(deftest large-resource-row-is-withheld-not-shipped-omitted
  (reg! :big/thing {:large? true})
  (testing "rf2-4bjep — the same for a `:large?` resource. The two coarse arms
            differ in what they do to the data and not at all in what they do to
            the key, so withholding reaches both"
    (let [k   (state/scoped-resource-key :rf.scope/global :big/thing {:slug "b"})
          e   (entry {:resource-id :big/thing :data (vec (range 10000))
                      :loaded-at 1000 :stale-at 9.0e15})
          rdb (runtime-db-with {k e})
          proj (ssr/project-resources-runtime-db rdb)
          m    (only-projection-metadata rdb)
          wk   (:projected-key m)]
      (is (empty? (get-in proj [state/resources-key :entries]))
          (str "no row rides, so the large payload cannot: " (pr-str proj)))
      (is (= :omitted (:disposition m)))
      (is (true? (:withheld? m)))
      (is (true? (:refetch-on-client? m)))
      (testing "rf2-otms75 — the large resource's scope + params are redacted in the key"
        (is (= :big/thing (nth wk 1)))
        (is (contains? (nth wk 2) :rf/redacted) "the (large) params do not ride raw")
        (is (not= {:slug "b"} (nth wk 2)))))))

(deftest projection-metadata-records-decisions
  (reg! :article/by-slug)
  (reg! :secret/thing {:sensitive? true})
  (reg! :big/thing {:large? true})
  (testing "projection-metadata records serialized / redacted / omitted + fresh / stale + refetch-on-client"
    (let [fresh (entry {:resource-id :article/by-slug :data {:t "x"}
                        :loaded-at 1000 :stale-at 9.0e15})
          stale (entry {:resource-id :article/by-slug :data {:t "y"}
                        :loaded-at 1000 :stale-at 1500})    ;; stale vs clock 5000
          sens  (entry {:resource-id :secret/thing :data {:s 1}
                        :loaded-at 1000 :stale-at 9.0e15})
          big   (entry {:resource-id :big/thing :data [1 2 3]
                        :loaded-at 1000 :stale-at 9.0e15})
          k-fresh gkey
          k-stale (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "y"})
          k-sens  (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "s"})
          k-big   (state/scoped-resource-key :rf.scope/global :big/thing {:slug "b"})
          ;; rf2-9e0tyq — `projection-metadata` reads each entry's own
          ;; `:resource/key` (the `:entries` map is byte-keyed), so stamp it
          ;; (mirrors the runtime's byte-keyed `:entries` shape). The returned
          ;; metadata `:resource/key` is the scoped-key VECTOR, so the
          ;; `(metas k-…)` vector lookups below stay unchanged.
          metas   (->> (ssr/projection-metadata
                         nil 5000
                         (entries* {k-fresh fresh k-stale stale k-sens sens k-big big}))
                       (into {} (map (juxt :resource/key identity))))]
      (is (= :serialized (:disposition (metas k-fresh))))
      (is (= :fresh      (:freshness   (metas k-fresh))))
      (is (false?        (:refetch-on-client? (metas k-fresh)))
          "fresh serialized → no client refetch (no double-fetch)")
      (is (= :stale      (:freshness   (metas k-stale))))
      (is (true?         (:refetch-on-client? (metas k-stale)))
          "stale serialized → background refetch on client")
      (is (= :redacted   (:disposition (metas k-sens))))
      (is (true?         (:refetch-on-client? (metas k-sens)))
          "redacted → metadata-only refetch")
      (is (= :omitted    (:disposition (metas k-big))))
      (is (true?         (:refetch-on-client? (metas k-big)))))))

;; ===========================================================================
;; 1b. Scoped-KEY privacy: align key scope+params with classification (rf2-otms75)
;; ===========================================================================
;;
;; A :sensitive? / :large? resource's scope + params must NOT ride RAW in the
;; projected map KEY (Spec 016 clause 4: params, scopes, and data carry the
;; same classification). They are projected to content-addressed
;; {:rf/redacted <digest>} tokens — distinct values stay distinct, the raw
;; identity does not ride, and the resource-id (position 1) is preserved.
;;
;; rf2-4bjep — the coarse arms' rows no longer RIDE at all (§1), so these
;; claims are now read off `projection-metadata`'s `:projected-key`, which is
;; the one observation point for the projection's answer whether or not a row
;; ships. They are not weaker for it: `:projected-key` is byte-identical to what
;; used to ride, and the row's absence is asserted here too.

(deftest serialize-resource-key-rides-verbatim
  (reg! :article/by-slug)
  (testing "a non-sensitive, non-large resource's key rides VERBATIM (scope +
            params are wire-safe, same class as its data)"
    (let [k    (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"})
          e    (entry {:resource-id :article/by-slug :data {:t "x"} :loaded-at 1000 :stale-at 9.0e15})
          [wk _] (only-wire-entry (ssr/project-resources-runtime-db (runtime-db-with {k e})))]
      (is (= k wk) "the serialized key is unchanged on the wire"))))

(deftest sensitive-resource-key-scope-and-params-are-redacted
  (reg! :secret/thing {:sensitive? true})
  (testing "a :sensitive? resource's scope (user/tenant markers) + params are
            redacted in the projected key — neither rides raw"
    (let [scope [:rf.scope/session {:user "alice@example.com" :tenant "acme"}]
          k    (state/scoped-resource-key scope :secret/thing {:account-id "secret-42"})
          e    (entry {:resource-id :secret/thing :data {:ssn "x"} :loaded-at 1000 :stale-at 9.0e15})
          rdb  (runtime-db-with {k e})
          wk   (:projected-key (only-projection-metadata rdb))]
      (is (= :secret/thing (nth wk 1)) "resource-id preserved")
      (is (contains? (nth wk 0) :rf/redacted) "scope redacted")
      (is (contains? (nth wk 2) :rf/redacted) "params redacted")
      ;; the raw sensitive substrings must not appear anywhere in the key…
      (let [s (pr-str wk)]
        (is (not (str/includes? s "alice@example.com")) "no raw user in the key")
        (is (not (str/includes? s "acme")) "no raw tenant in the key")
        (is (not (str/includes? s "secret-42")) "no raw param in the key"))
      ;; …nor anywhere on the wire, which now carries no row for this entry at
      ;; all. rf2-4bjep: a 32-bit digest of a low-entropy identity is
      ;; enumerable, so the TOKEN was itself a small egress of what the coarse
      ;; claim asked to hide, and withholding removes its last carrier.
      (let [s (pr-str (ssr/project-resources-runtime-db rdb))]
        (is (not (str/includes? s "alice@example.com")))
        (is (not (str/includes? s "acme")))
        (is (not (str/includes? s "secret-42")))
        (is (not (str/includes? s "rf/redacted"))
            (str "and no digest rides either — the row is absent: " s))))))

(deftest redacted-keys-stay-distinct-no-collision
  (reg! :secret/thing {:sensitive? true})
  (testing "ADVERSARIAL: two distinct sensitive entries (same scope+resource,
            DIFFERENT params) project to DISTINCT keys — the redaction does not
            collapse them into one (no lost entry; rf2-otms75). rf2-4bjep: read
            off the projection metadata, since neither row rides"
    (let [k1 (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "alpha"})
          k2 (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "beta"})
          e1 (entry {:resource-id :secret/thing :data {:s 1} :loaded-at 1000 :stale-at 9.0e15})
          e2 (entry {:resource-id :secret/thing :data {:s 2} :loaded-at 1000 :stale-at 9.0e15})
          rdb  (runtime-db-with {k1 e1 k2 e2})
          metas (ssr/projection-metadata
                  nil 5000 (get-in rdb [state/resources-key :entries]))
          wks   (set (map :projected-key metas))]
      (is (= 2 (count metas)) "premise: the metadata still accounts for both entries")
      (is (= 2 (count wks)) "two distinct projected (redacted) scoped keys (no collision)")
      (is (= 2 (count (set (map (comp state/key-id :projected-key) metas))))
          "…and two distinct BYTE identities, which is the form the collapse
           would have taken")
      (is (every? (fn [wk] (= :secret/thing (nth wk 1))) wks)
          "both projected keys preserve the resource-id")
      (is (every? (fn [wk] (and (contains? (nth wk 2) :rf/redacted)
                                (not= {:slug "alpha"} (nth wk 2))
                                (not= {:slug "beta"} (nth wk 2)))) wks)
          "neither projected key carries the raw params")
      (is (empty? (get-in (ssr/project-resources-runtime-db rdb)
                          [state/resources-key :entries]))
          "and neither row rides — distinctness is a property of the projection,
           which the trace / tool egress boundaries still consume"))))

(deftest project-scoped-key-is-pure
  (testing "project-scoped-key: :serialize rides verbatim; :redact/:omit redact
            scope+params, preserve resource-id + distinctness"
    (let [k1 (state/scoped-resource-key :rf.scope/global :r {:a 1})
          k2 (state/scoped-resource-key :rf.scope/global :r {:a 2})]
      ;; nil spec → no :params-schema marks → :serialize rides params verbatim.
      (is (= k1 (ssr/project-scoped-key k1 :serialize nil)))
      (let [r1 (ssr/project-scoped-key k1 :redact nil)
            r2 (ssr/project-scoped-key k2 :redact nil)]
        (is (= :r (nth r1 1)) "resource-id preserved")
        (is (contains? (nth r1 2) :rf/redacted))
        (is (not= r1 r2) "distinct params → distinct redacted keys")
        (is (= r1 (ssr/project-scoped-key k1 :redact nil)) "deterministic (same input → same digest)")
        (is (= (ssr/project-scoped-key k1 :redact nil) (ssr/project-scoped-key k1 :omit nil))
            ":redact and :omit redact the key identically (both metadata-only)")))))

;; ---- the tokenizer is byte-identical across hosts (rf2-4bjep) --------------
;;
;; `redact-value`'s digest was called "cross-process-stable" and was not: the
;; JVM branch hashed UTF-8 bytes with exact integer arithmetic, while the CLJS
;; branch hashed UTF-16 CODE UNITS masked to their low byte, multiplied 32-bit
;; states with a double `*` whose product exceeds 2^53, and emitted a SIGNED
;; result. The two agreed on NO input — `"tenant"` was `f93325e5` on the server
;; and `74e22aec` in the browser — and roughly half of all inputs rendered as a
;; negative 9-character hex, which is not the shape the function documents.
;;
;; This fixture is what makes the claim testable rather than asserted. It is a
;; `.cljc` deftest with LITERAL expected digests, so the JVM run and the
;; `:node-test` run each check the same constants: a divergence reds on one host
;; and not the other, and a shared regression reds on both. The cases are the
;; three places the two encodings can part company — pure ASCII (where only the
;; arithmetic can differ), a 2-byte non-ASCII code point (where UTF-8 and UTF-16
;; disagree), and a SURROGATE PAIR (where a per-code-unit walk splits one code
;; point in half). The non-ASCII strings are built from `char` code points rather
;; than written as literals, so the fixture cannot be silently changed by a
;; re-encoding of this file.

(def ^:private cafe-str (str "caf" (char 0xe9)))                      ;; "café"
(def ^:private emoji-str (str "a" (char 0xd83d) (char 0xde00) "b"))   ;; "a<U+1F600>b"

(deftest the-redaction-digest-is-byte-identical-across-clj-and-cljs
  (testing "rf2-4bjep — `redact-value`'s digest is a fixed function of the
            canonical value on EVERY host. The expected digests are literals
            checked by both runs of this `.cljc`, which is the only shape of
            assertion that can catch one host drifting from the other"
    (is (= {:rf/redacted "f93325e5"} (ssr/redact-value "tenant"))
        "ASCII: the case the two branches disagreed on even though their bytes
         were identical — the double multiply lost the low bits")
    (is (= {:rf/redacted "ffcaaa85"} (ssr/redact-value ""))
        "the empty string still hashes (it is not the nil/empty-COLLECTION case)")
    (is (= {:rf/redacted "d1ace591"} (ssr/redact-value cafe-str))
        "non-ASCII: one code point, two UTF-8 bytes, one UTF-16 code unit")
    (is (= {:rf/redacted "215644f1"} (ssr/redact-value emoji-str))
        "a SURROGATE PAIR: one code point, four UTF-8 bytes, TWO UTF-16 code
         units — the case a per-code-unit walk cannot get right")
    (is (= {:rf/redacted "a94ffb07"} (ssr/redact-value {:tenant-id cafe-str}))
        "and the same through a whole scope map, which is how it is really used")
    (is (= {:rf/redacted nil} (ssr/redact-value nil))
        "nil / empty projects to the stable no-digest token")
    (is (= {:rf/redacted nil} (ssr/redact-value {})))))

(deftest the-redaction-digest-has-the-shape-it-documents
  (testing "rf2-4bjep — 8 lower-case hex characters, always. The CLJS branch's
            `bit-and` yielded a SIGNED int32, so about half of all inputs came
            out as a NEGATIVE 9-character string with a leading `-`. Stated over
            a spread wide enough to hit the negative half rather than over one
            lucky value"
    (let [digests (into []
                        (map (fn [i] (:rf/redacted (ssr/redact-value {:n i :s (str "id-" i)}))))
                        (range 256))]
      (is (every? (fn [d] (and (string? d)
                               (= 8 (count d))
                               (re-matches #"[0-9a-f]{8}" d)))
                  digests)
          (str "every digest is 8 lower-case hex chars — "
               (pr-str (remove (fn [d] (re-matches #"[0-9a-f]{8}" d)) digests))))
      (is (some (fn [d] (>= (compare d "80000000") 0)) digests)
          "…and the spread reaches the high half, so the signed-int32 defect
           would have been in range rather than merely unlucky to miss")
      (is (= 256 (count (set digests)))
          "and the 256 distinct values keep 256 distinct digests"))))

;; ===========================================================================
;; 2. SERVER blocking drain + timeout
;; ===========================================================================

(def ^:private ka (state/scoped-resource-key :rf.scope/global :a {:slug "a"}))
(def ^:private kb (state/scoped-resource-key :rf.scope/global :b {:slug "b"}))
(def ^:private kc (state/scoped-resource-key :rf.scope/global :c {:slug "c"}))

(deftest blocking-settled-predicate
  (testing "blocking-settled? is true iff every blocking entry has settled"
    (let [loaded  (entry {:resource-id :a :status :loaded :data {:x 1}})
          errored (entry {:resource-id :b :status :error})
          loading (entry {:resource-id :c :status :loading})
          es      (entries* {ka loaded kb errored kc loading})]
      (is (true?  (ssr/blocking-settled? es #{ka kb}))
          ":loaded and :error are settled")
      (is (false? (ssr/blocking-settled? es #{ka kc}))
          ":loading is NOT settled")
      (is (false? (ssr/blocking-settled? es #{ka [:rf.scope/global :missing {}]}))
          "an absent (never-enqueued) blocking key is not settled")
      (is (true?  (ssr/blocking-settled? es #{}))
          "no blocking resources → trivially settled (never blocks render)"))))

(deftest blocking-timeout-settles-first-load-failure
  (testing "settle-blocking-timeout settles every unsettled blocking entry as a
            structured first-load failure + a route-blocking-failure record (never hangs)"
    (let [loading (entry {:resource-id :a :status :loading})
          loaded  (entry {:resource-id :b :status :loaded :data {:x 1}})
          es      (entries* {ka loading kb loaded})
          {:keys [entries route-blocking-failure]}
          (ssr/settle-blocking-timeout es #{ka kb} 250 :app/main)]
      (testing "the unsettled blocking entry settles to a first-load :error"
        (let [se (entry-by entries ka)]
          (is (= :error (:status se)))
          (is (= :rf.http/timeout (:kind (:error se))))
          (is (= :ssr-blocking-timeout (:reason (:error se))))
          (is (nil? (:data se)) "first-load failure has no usable data")))
      (testing "the already-settled entry is untouched"
        (is (= :loaded (:status (entry-by entries kb)))))
      (testing "the route-blocking-failure record names the timed-out keys + deadline"
        (is (= :rf.error/resource-ssr-blocking-timeout (:rf.error/id route-blocking-failure)))
        (is (= #{ka} (set (:timed-out route-blocking-failure))))
        (is (= 250 (:limit-ms route-blocking-failure)))))))

(deftest blocking-timeout-noop-when-all-settled
  (testing "no unsettled blocking entries → no failure record, entries unchanged"
    (let [es (entries* {ka (entry {:resource-id :a :status :loaded :data {:x 1}})})
          {:keys [entries route-blocking-failure]}
          (ssr/settle-blocking-timeout es #{ka} 250 :app/main)]
      (is (= es entries))
      (is (nil? route-blocking-failure)))))

(deftest blocking-timeout-settles-absent-blocking-key
  (testing "an absent blocking key (enqueued but never wrote an entry) settles to :error"
    (let [kmiss (state/scoped-resource-key :rf.scope/global :missing {})
          {:keys [entries route-blocking-failure]}
          (ssr/settle-blocking-timeout {} #{kmiss} 100 :app/main)]
      ;; rf2-9e0tyq — the settled entry is keyed on the byte key-id.
      (is (= :error (:status (entry-by entries kmiss))))
      (is (= #{kmiss} (set (:timed-out route-blocking-failure)))))))

;; ===========================================================================
;; 2b. SERVER blocking-drain LOOP (rf2-er7qx2 — wired into the render path)
;; ===========================================================================
;;
;; `drain-blocking-resources!` is the LOOP the host render path (Ring /
;; streaming) calls before rendering: it reads the current nav-token's
;; blocking set, pumps the event loop until they settle, and on the render
;; deadline settles every still-unsettled blocking entry to a first-load
;; failure IN the frame's runtime-db (so the render walk never sees a hung
;; `:loading` / skeleton). These tests register a live frame and drive the
;; loop with an injected pump / clock.

(defn- seed-frame-runtime-db!
  "Register `frame-id` (idempotent) and install `runtime-db` as its runtime-db
  partition, returning frame-id. Used to stand up a live SSR-like frame the
  drain loop reads/writes."
  [frame-id runtime-db]
  (rf/make-frame {:id frame-id :doc "ssr blocking-drain test frame" :platform :server})
  (frame/replace-runtime-db! frame-id runtime-db)
  frame-id)

(defn- with-blocking-slot
  "Add a routing slice naming `nav-token` live + a `:resource-blocking` slot
  holding `blocking-keys` for it, into `runtime-db` (mirrors what the route
  slice writes on entry)."
  [runtime-db nav-token blocking-keys]
  (-> runtime-db
      (assoc-in [:rf.runtime/routing :current :nav-token] nav-token)
      (assoc-in [:rf.runtime/routing :resource-blocking nav-token] (set blocking-keys))))

(deftest current-blocking-keys-reads-current-nav-token-slot
  (testing "current-blocking-keys reads ONLY the current nav-token's blocking slot"
    (let [rdb (-> (runtime-db-with {})
                  (with-blocking-slot "nav-1" #{ka kb})
                  ;; a superseded nav-token's stale slot must NOT leak in
                  (assoc-in [:rf.runtime/routing :resource-blocking "nav-OLD"] #{kc}))]
      (is (= #{ka kb} (ssr/current-blocking-keys rdb))))
    (testing "no routing slice / no current nav-token → empty (never blocks)"
      (is (= #{} (ssr/current-blocking-keys (runtime-db-with {}))))
      (is (= #{} (ssr/current-blocking-keys {}))))))

(deftest drain-noop-when-no-blocking-resources
  (testing "a route with no blocking resources returns :settled? immediately, no pump"
    (let [pumped (atom 0)
          fid    (seed-frame-runtime-db! :ssr/drain-none
                                         (runtime-db-with {ka (entry {:resource-id :a :status :loaded :data {:x 1}})}))
          res    (ssr/drain-blocking-resources!
                   fid {:pump! (fn [_] (swap! pumped inc)) :deadline-ms 1000})]
      (is (true? (:settled? res)))
      (is (= #{} (:timed-out res)))
      (is (nil? (:route-blocking-failure res)))
      (is (zero? @pumped) "no blocking set → the loop never pumps")
      (frame/destroy-frame! fid))))

(deftest drain-settles-when-blocking-entries-already-settled
  (testing "blocking entries already settled → :settled? true, runtime-db untouched, no timeout"
    (let [rdb (-> (runtime-db-with {ka (entry {:resource-id :a :status :loaded :data {:x 1}})
                                    kb (entry {:resource-id :b :status :error})})
                  (with-blocking-slot "nav-1" #{ka kb}))
          fid (seed-frame-runtime-db! :ssr/drain-settled rdb)
          res (ssr/drain-blocking-resources! fid {:pump! (fn [_] nil) :deadline-ms 1000})]
      (is (true? (:settled? res)))
      (is (nil? (:route-blocking-failure res)))
      (is (= :loaded (get-in (frame/frame-runtime-db-value fid)
                             [state/resources-key :entries (state/key-id ka) :status]))
          "the settled entry is untouched")
      (frame/destroy-frame! fid))))

(deftest drain-pumps-until-a-blocking-reply-lands
  (testing "the loop pumps until an in-flight blocking entry settles; the pump
            mutates the live frame so the loop observes the settle and does NOT time out"
    (let [rdb (-> (runtime-db-with {ka (entry {:resource-id :a :status :loading :data nil})})
                  (with-blocking-slot "nav-1" #{ka}))
          fid (seed-frame-runtime-db! :ssr/drain-pump rdb)
          ;; the pump simulates the async reply landing on the 2nd tick: it
          ;; flips the blocking entry to :loaded in the live frame's runtime-db.
          ticks (atom 0)
          pump! (fn [_]
                  (when (= 2 (swap! ticks inc))
                    (frame/swap-runtime-db!
                      fid assoc-in [state/resources-key :entries (state/key-id ka)]
                      (assoc (entry {:resource-id :a :status :loaded :data {:x 1}})
                             :resource/key ka))))
          res   (ssr/drain-blocking-resources! fid {:pump! pump! :deadline-ms 60000})]
      (is (true? (:settled? res)) "the loop settled once the reply landed")
      (is (nil? (:route-blocking-failure res)) "no timeout — it settled in time")
      (is (= {:x 1} (get-in (frame/frame-runtime-db-value fid)
                            [state/resources-key :entries (state/key-id ka) :data])))
      (frame/destroy-frame! fid))))

(deftest drain-times-out-a-never-settling-blocking-resource
  (testing "ADVERSARIAL: a never-settling blocking resource is settled to a
            structured first-load ERROR in the frame's runtime-db (not left a
            hung :loading / skeleton) once the render deadline fires — the
            acceptance criterion"
    (let [rdb (-> (runtime-db-with {ka (entry {:resource-id :a :status :loading :data nil})})
                  (with-blocking-slot "nav-1" #{ka}))
          fid (seed-frame-runtime-db! :ssr/drain-timeout rdb)
          ;; a deterministic clock that jumps past the deadline on the 1st
          ;; re-check after start, so the test never actually sleeps; pump! is
          ;; a no-op (the resource never settles).
          clk (atom 0)
          clock-fn (fn [] (let [v @clk] (swap! clk + 100) v))]
      (let [res (ssr/drain-blocking-resources!
                  fid {:pump! (fn [_] nil) :deadline-ms 50 :clock-fn clock-fn})]
        (is (false? (:settled? res)) "the loop reported a timeout, not a settle")
        (is (= #{ka} (:timed-out res)))
        (let [se (get-in (frame/frame-runtime-db-value fid)
                         [state/resources-key :entries (state/key-id ka)])]
          (is (= :error (:status se))
              "the never-settling blocking entry is SETTLED to :error, not left :loading")
          (is (= :rf.http/timeout (:kind (:error se))))
          (is (= :ssr-blocking-timeout (:reason (:error se)))))
        (is (= :rf.error/resource-ssr-blocking-timeout
               (:rf.error/id (:route-blocking-failure res)))
            "a route-blocking-failure record is produced for the renderer / route slice"))
      (frame/destroy-frame! fid))))

(deftest drain-times-out-with-nil-pump-sync-stub
  (testing "a nil :pump! (a synchronous stub) still respects the deadline — a
            blocking resource that never settles times out rather than looping forever"
    (let [rdb (-> (runtime-db-with {ka (entry {:resource-id :a :status :loading :data nil})})
                  (with-blocking-slot "nav-1" #{ka}))
          fid (seed-frame-runtime-db! :ssr/drain-nilpump rdb)
          clk (atom 0)
          clock-fn (fn [] (let [v @clk] (swap! clk + 100) v))
          res (ssr/drain-blocking-resources!
                fid {:pump! nil :deadline-ms 50 :clock-fn clock-fn})]
      (is (false? (:settled? res)))
      (is (= :error (get-in (frame/frame-runtime-db-value fid)
                            [state/resources-key :entries (state/key-id ka) :status])))
      (frame/destroy-frame! fid))))

(deftest drain-hook-published
  (testing "the :resources/drain-blocking-ssr! late-bind hook is published by the façade"
    (is (some? (late-bind/get-fn :resources/drain-blocking-ssr!)))
    (is (= ssr/drain-blocking-resources!
           (late-bind/get-fn :resources/drain-blocking-ssr!)))))

;; ===========================================================================
;; 2c. SSR blocking drain ↔ WORK-LEDGER TERMINAL completion (rf2-jnrotz,
;;     EP-0011 §SSR, Preload, Hydration, And Restore / validation item
;;     "SSR preload: blocking route resources settle through ledger terminal
;;     statuses")
;; ===========================================================================
;;
;; The §2b drain-loop tests above drive the loop with a pump that DIRECTLY
;; flips the entry `:status` (or settles it via `settle-blocking-timeout`) — they
;; prove the drain releases when the ENTRY settles, but they never exercise the
;; real resource/work-ledger path, so they do NOT pin the drain to the WORK
;; LEDGER reaching a terminal state. EP-0011 §SSR is explicit: "SSR waits for
;; ledger rows associated with the current route/nav-token to become terminal"
;; and "blocking route resources settle through ledger terminal statuses".
;;
;; These tests enqueue a blocking resource through the REAL `:rf.resource/ensure`
;; event (which writes the entry `:loading`, a `:running` work-ledger row, AND a
;; host-handle side-table slot) and settle it through the REAL
;; `:rf.resource.internal/succeeded` reply (which moves the entry `:loaded`, the
;; ledger row terminal `:completed`, prunes terminal rows, and clears the host
;; handle — `succeeded-handler`). The pump fires the real reply, so when the
;; drain releases, the JOINED facts are asserted: the work-ledger row is
;; TERMINAL and the host handle is CLEARED. The adversarial inverse pins the
;; join the other way — a row that stays NON-terminal (entry never settles)
;; keeps the drain blocked until the deadline, never releasing on a partial /
;; wall-clock signal.

(defn- ledger-row
  "The work-ledger record for `work-id` in `frame-id`'s live runtime-db, or nil."
  [frame-id work-id]
  (work-ledger/get-record (frame/frame-runtime-db-value frame-id) work-id))

(deftest drain-releases-only-when-work-ledger-row-terminal-real-path
  (reg! :article/by-slug)
  (testing "rf2-jnrotz — a blocking resource enqueued through the REAL resource
            path settles the ENTRY and the WORK-LEDGER ROW together; when the
            SSR drain releases, the associated ledger row is TERMINAL and the
            host handle is cleared (EP-0011 §SSR: SSR waits for ledger rows to
            become terminal)"
    (let [fid :ssr/drain-ledger-terminal]
      (rf/make-frame {:id fid :doc "ssr ledger-terminal drain frame" :platform :server})
      ;; REAL ensure: writes the entry :loading + a :running ledger row + a host
      ;; handle, then publishes the blocking slot the drain reads.
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :article/by-slug :scope :rf.scope/global
                          :params {:slug "x"} :owner [:ssr "req-1" "nav-1"]
                          :cause [:route-entry :route/article "nav-1"]}]
                        {:frame fid})
      (let [wid (get-in (frame/frame-runtime-db-value fid) (conj (state/entry-path gkey) :current-work))]
        (testing "the real ensure path armed the joined work facts before the drain"
          (is (some? wid) "the entry points at a current work id")
          (is (= :running (:status (ledger-row fid wid)))
              "the work-ledger row is NON-terminal (:running) while in flight")
          (is (some? (work-ledger/get-handle fid wid))
              "a host handle exists for the in-flight attempt")
          (is (= :loading (get-in (frame/frame-runtime-db-value fid)
                                  (conj (state/entry-path gkey) :status)))
              "the entry is :loading (not yet settled)"))
        ;; publish the nav-token blocking slot the drain reads (mirrors what the
        ;; route slice writes on entry).
        (frame/swap-runtime-db! fid with-blocking-slot "nav-1" #{gkey})
        ;; the pump fires the REAL reply on the 2nd tick — settling the entry
        ;; :loaded AND the ledger row terminal :completed + clearing the handle
        ;; through `succeeded-handler`, exactly as a real transport reply would.
        (let [ticks (atom 0)
              pump! (fn [_]
                      (when (= 2 (swap! ticks inc))
                        (rf/dispatch-sync
                          [:rf.resource.internal/succeeded
                           {:resource/key gkey :work/id wid :generation 1
                            :rf.frame/id fid :data {:title "X"}}]
                          {:frame fid})))
              res   (ssr/drain-blocking-resources! fid {:pump! pump! :deadline-ms 60000})]
          (testing "the drain releases (the blocking entry settled in time)"
            (is (true? (:settled? res)))
            (is (nil? (:route-blocking-failure res)) "no timeout — settled in time"))
          (testing "the JOIN: when the drain released, the entry is :loaded AND
                    the work-ledger row is TERMINAL (the EP-0011 §SSR contract)"
            (is (= :loaded (get-in (frame/frame-runtime-db-value fid)
                                   (conj (state/entry-path gkey) :status)))
                "the blocking entry settled :loaded")
            (let [row (ledger-row fid wid)]
              ;; the row is terminal (:completed) — or pruned to nil if the
              ;; bounded per-key tail dropped it; either way it is NOT live /
              ;; non-terminal. The load-bearing assertion is that NO non-terminal
              ;; row for the work survives once the drain releases.
              (is (or (nil? row) (work-ledger/terminal? (:status row)))
                  "the associated work-ledger row is terminal (or pruned), never non-terminal"))
            (is (not (contains? work-ledger/non-terminal-statuses
                                (:status (ledger-row fid wid))))
                "no NON-terminal ledger row survives the released drain"))
          (testing "the host handle is cleared (no live host work behind a
                    released SSR render)"
            (is (nil? (work-ledger/get-handle fid wid)))))
        (frame/destroy-frame! fid)))))

(deftest drain-blocks-while-work-ledger-row-non-terminal-real-path
  (reg! :article/by-slug)
  (testing "rf2-jnrotz ADVERSARIAL: while the work-ledger row stays NON-terminal
            (the real reply never lands, so the entry stays :loading), the SSR
            drain MUST NOT release — it blocks until the render deadline, then
            settles the entry to a first-load failure (it never releases on a
            partial / wall-clock signal while the row is non-terminal)"
    (let [fid :ssr/drain-ledger-nonterminal]
      (rf/make-frame {:id fid :doc "ssr ledger-nonterminal drain frame" :platform :server})
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :article/by-slug :scope :rf.scope/global
                          :params {:slug "x"} :owner [:ssr "req-2" "nav-2"]}]
                        {:frame fid})
      (frame/swap-runtime-db! fid with-blocking-slot "nav-2" #{gkey})
      (let [wid (get-in (frame/frame-runtime-db-value fid) (conj (state/entry-path gkey) :current-work))
            ;; deterministic clock that jumps past the deadline; pump! is a no-op
            ;; (the real reply never lands → the ledger row stays :running).
            clk (atom 0)
            clock-fn (fn [] (let [v @clk] (swap! clk + 100) v))]
        (is (= :running (:status (ledger-row fid wid)))
            "the row is NON-terminal at drain entry")
        (let [res (ssr/drain-blocking-resources!
                    fid {:pump! (fn [_] nil) :deadline-ms 50 :clock-fn clock-fn})]
          (testing "the drain did NOT release early — it reported a timeout"
            (is (false? (:settled? res)))
            (is (= #{gkey} (:timed-out res)))
            (is (= :rf.error/resource-ssr-blocking-timeout
                   (:rf.error/id (:route-blocking-failure res)))))
          (testing "the never-settling blocking entry is settled to a structured
                    first-load :error in the frame (not left hung :loading)"
            (is (= :error (get-in (frame/frame-runtime-db-value fid)
                                  (conj (state/entry-path gkey) :status)))))))
      (frame/destroy-frame! fid))))

(deftest hydration-projection-ships-no-work-ledger-rows
  (reg! :article/by-slug)
  (testing "rf2-jnrotz — the SSR hydration projection rides ONLY the durable
            resource :entries; the work-ledger subtree (host work facts) NEVER
            rides the hydration wire (EP-0011 §SSR: hydration serializes the
            allowed resource projection, not host work)"
    (let [fid :ssr/drain-no-ledger-on-wire]
      (rf/make-frame {:id fid :doc "ssr no-ledger-on-wire frame" :platform :server})
      ;; real ensure → a live :running work-ledger row exists in the frame's
      ;; runtime-db alongside the resource entry.
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource :article/by-slug :scope :rf.scope/global
                          :params {:slug "x"} :owner [:ssr "req-3" "nav-3"]}]
                        {:frame fid})
      (let [rdb (frame/frame-runtime-db-value fid)]
        (is (seq (get rdb state/work-ledger-key))
            "the live runtime-db carries a work-ledger row before projection")
        (let [proj (payload-policy/project-runtime-db rdb)]
          (is (contains? proj state/resources-key)
              "the resource :entries slice rides the wire")
          (is (not (contains? proj state/work-ledger-key))
              "the :rf.runtime/work-ledger subtree does NOT ride the hydration wire")
          (is (= #{:entries} (set (keys (get proj state/resources-key))))
              "only :entries rides — no indexes, no work rows")))
      (frame/destroy-frame! fid))))

;; ===========================================================================
;; 3. CLIENT hydration reconcile
;; ===========================================================================

(deftest hydrate-recomputes-indexes-from-entries
  (testing "hydrate-runtime-db rebuilds :tag-index / :owner-index from entries (never trusts the wire)"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :tags #{[:article "x"]}
                      :owners #{[:route :route/article "nav-1"]}})
          ;; arrive with deliberately-WRONG (stale) indexes — they must be discarded
          rdb {state/resources-key {:entries   {gkey e}
                                    :tag-index {[:bogus] #{:nope}}
                                    :owner-index {[:bogus] #{:nope}}}}
          out (ssr/hydrate-runtime-db rdb :app/main)
          sub (get out state/resources-key)]
      (is (= {[:article "x"] #{gkey}} (:tag-index sub))
          "tag-index recomputed from the entry's :tags, the bogus wire index discarded")
      (is (= {[:route :route/article "nav-1"] #{gkey}} (:owner-index sub))
          "owner-index recomputed from the entry's surviving owners"))))

(deftest hydrate-orphans-ssr-owners
  (testing "SSR owners orphan on hydration (they belong to a settled server render); route owners survive"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :owners #{[:ssr "req-7" "nav-1"]
                                [:route :route/article "nav-1"]}})
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)
          owners (get-in out [state/resources-key :entries (state/key-id gkey) :active-owners])]
      (is (not (contains? owners [:ssr "req-7" "nav-1"]))
          "the SSR owner is dropped as an orphan")
      (is (contains? owners [:route :route/article "nav-1"])
          "the route owner survives (its liveness is reconciled by routing)")
      (is (not (contains? (get-in out [state/resources-key :owner-index])
                          [:ssr "req-7" "nav-1"]))
          "the orphaned SSR owner is absent from the recomputed owner-index"))))

(deftest hydrate-clears-current-work
  (testing "the transient :current-work pointer is cleared (the attempt never crossed the wire)"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :current-work [:rf.work/resource gkey 1]})
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)]
      (is (nil? (get-in out [state/resources-key :entries (state/key-id gkey) :current-work]))))))

(deftest hydrate-preserves-entry-data
  (testing "hydrated entries are PRESERVED (data + status survive the reconcile)"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "kept"}
                      :loaded-at 1000 :stale-at 9.0e15})
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)]
      (is (= {:t "kept"} (get-in out [state/resources-key :entries (state/key-id gkey) :data])))
      (is (= :loaded (get-in out [state/resources-key :entries (state/key-id gkey) :status]))))))

(deftest hydrate-noop-without-resources
  (testing "a runtime-db with no resource entries is returned unchanged (SSR app without resources)"
    (let [rdb {:rf.runtime/machines {:snapshots {}}}]
      (is (= rdb (ssr/hydrate-runtime-db rdb :app/main))))))

;; ===========================================================================
;; 3b. Hydrated NON-TERMINAL entries settle to last-stable (rf2-bg6qah)
;; ===========================================================================
;;
;; The server projection (`project-entry`) STRIPS `:current-work` on the wire
;; but keeps the entry's `:status`, so a hydrated entry can arrive `:loading`
;; / `:fetching` with no live work behind it. Left as-is it dangles — a
;; `:fetching`-with-fresh-data entry is skipped by the refetch planner (no
;; double-fetch) yet has no fetch in flight, so it would render `:fetching`
;; forever. `hydrate-runtime-db` settles each entry to its last STABLE status
;; (the same `settle-entry-to-last-stable` the restore reconcile applies), so
;; the planner then classifies it correctly. The four acceptance cases:
;; loading-no-data, fetching-fresh-data, fetching-stale-data, fresh-loaded.

(deftest hydrate-settles-loading-no-data-to-idle-then-refetches
  (testing "rf2-bg6qah — a hydrated :loading entry with NO data settles to :idle
            (never stranded :loading), and the refetch plan then refetches it"
    (let [e   (entry {:resource-id :article/by-slug :status :loading :data nil})
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [state/resources-key :entries (state/key-id gkey)])]
      (is (= :idle (:status se)) "loading-with-no-data → :idle, never a dangling :loading")
      (let [plan (->> (ssr/hydrate-refetch-plan out 5000)
                      (into {} (map (juxt :resource/key identity))))]
        (is (contains? plan gkey) "the settled :idle entry (no data) is refetched")
        (is (= :no-data (:reason (plan gkey))))))))

(deftest hydrate-settles-fetching-fresh-data-to-loaded-no-double-fetch
  (testing "rf2-bg6qah — a hydrated :fetching entry with FRESH data settles to
            :loaded and is NOT refetched (the dangling-fetching no-double-fetch
            case the bead calls out)"
    (let [e   (entry {:resource-id :article/by-slug :status :fetching
                      :data {:t "fresh"} :loaded-at 1000 :stale-at 9.0e15})
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [state/resources-key :entries (state/key-id gkey)])]
      (is (= :loaded (:status se)) "fetching-with-fresh-data → :loaded (keep last-known-good)")
      (is (= {:t "fresh"} (:data se)) "the data is preserved")
      (let [plan (->> (ssr/hydrate-refetch-plan out 5000)
                      (into {} (map (juxt :resource/key identity))))]
        (is (not (contains? plan gkey))
            "the settled fresh-with-data entry is NOT refetched (no double-fetch — the SSR win)")))))

(deftest hydrate-settles-fetching-stale-data-to-loaded-background-refetch
  (testing "rf2-bg6qah — a hydrated :fetching entry with STALE data settles to
            :loaded (keep last-known-good) and background-refetches"
    (let [e   (entry {:resource-id :article/by-slug :status :fetching
                      :data {:t "stale"} :loaded-at 1000 :stale-at 1500})  ;; stale vs 5000
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [state/resources-key :entries (state/key-id gkey)])]
      (is (= :loaded (:status se)) "fetching-with-stale-data → :loaded")
      (is (= {:t "stale"} (:data se)) "the stale last-known-good data is preserved")
      (let [plan (->> (ssr/hydrate-refetch-plan out 5000)
                      (into {} (map (juxt :resource/key identity))))]
        (is (= :stale (:reason (plan gkey)))
            "the settled stale entry background-refetches (stale-while-revalidate)")))))

(deftest hydrate-fresh-loaded-data-rides-through-no-double-fetch
  (testing "rf2-bg6qah — a NORMAL fresh :loaded entry rides through unchanged
            (already stable) and is NOT refetched"
    (let [e   (entry {:resource-id :article/by-slug :status :loaded
                      :data {:t "kept"} :loaded-at 1000 :stale-at 9.0e15})
          out (ssr/hydrate-runtime-db (runtime-db-with {gkey e}) :app/main)
          se  (get-in out [state/resources-key :entries (state/key-id gkey)])]
      (is (= :loaded (:status se)) "a :loaded entry stays :loaded")
      (is (= {:t "kept"} (:data se)))
      (let [plan (->> (ssr/hydrate-refetch-plan out 5000)
                      (into {} (map (juxt :resource/key identity))))]
        (is (not (contains? plan gkey)) "fresh-loaded → no refetch (no double-fetch)")))))

(deftest hydrate-end-to-end-project-then-hydrate-fetching-entry-settles
  (reg! :article/by-slug)
  (testing "rf2-bg6qah ADVERSARIAL end-to-end: a server-side :fetching entry
            PROJECTS (stripping :current-work, keeping :status :fetching) and on
            HYDRATION settles to a stable status — never installed as a dangling
            :fetching with no work"
    (let [e    (entry {:resource-id :article/by-slug :status :fetching
                       :data {:t "x"} :loaded-at 1000 :stale-at 9.0e15
                       :current-work [:rf.work/resource gkey 7]})
          proj (ssr/project-resources-runtime-db (runtime-db-with {gkey e}))
          [wk we] (only-wire-entry proj)]
      (is (= :fetching (:status we)) "the projection keeps the entry's :fetching status on the wire")
      (is (not (contains? we :current-work)) "the projection strips :current-work on the wire")
      ;; rf2-9e0tyq — install the REAL projected (byte-keyed) map; look the
      ;; settled entry up by the byte `key-id` of the projected scoped key.
      (let [installed proj
            out (ssr/hydrate-runtime-db installed :app/main)
            se  (get-in out [state/resources-key :entries (state/key-id wk)])]
        (is (= :loaded (:status se))
            "hydration settles the dangling :fetching entry to :loaded — not installed verbatim")
        (is (nil? (:current-work se)) "current-work stays cleared")))))

(deftest clock-skew-surfaced-when-stale-at-implausible
  (testing "clock-skew-ms returns positive skew when :stale-at lies implausibly ahead of the live clock"
    ;; window = stale-at - loaded-at = 1000; stale-at 100000 is far beyond
    ;; (clock 2000 + window 1000) = 3000 → implausible (server clock ran ahead).
    (let [e (entry {:resource-id :a :data {:x 1} :loaded-at 99000 :stale-at 100000})]
      (is (= (- 100000 2000) (ssr/clock-skew-ms e 2000))))
    (testing "a plausible stale-at returns nil (no skew)"
      (let [e (entry {:resource-id :a :data {:x 1} :loaded-at 1000 :stale-at 2000})]
        (is (nil? (ssr/clock-skew-ms e 1500)))))
    (testing "no :stale-at → nil (cannot assess)"
      (is (nil? (ssr/clock-skew-ms (entry {:resource-id :a :data {:x 1}}) 1500))))))

;; ===========================================================================
;; 4. NO double-fetch — refetch plan
;; ===========================================================================

(deftest refetch-plan-omits-fresh-includes-stale-and-metadata-only
  (testing "fresh-with-data → absent (no double-fetch); stale → present; metadata-only → present"
    (let [fresh (entry {:resource-id :a :data {:x 1} :loaded-at 1000 :stale-at 9.0e15})
          stale (entry {:resource-id :b :data {:x 2} :loaded-at 1000 :stale-at 1500})
          meta  (entry {:resource-id :c :data nil :status :loaded})  ;; redacted/omitted: no data
          rdb   (runtime-db-with {ka fresh kb stale kc meta})
          plan  (->> (ssr/hydrate-refetch-plan rdb 5000)
                     (into {} (map (juxt :resource/key identity))))]
      (is (not (contains? plan ka)) "fresh-with-data is NOT refetched (the SSR win)")
      (is (= :stale   (:reason (plan kb))))
      (is (= :no-data (:reason (plan kc)))))))

(deftest entry-needs-refetch-predicate
  (testing "entry-needs-refetch? is false ONLY for fresh-with-data"
    (is (false? (ssr/entry-needs-refetch?
                  (entry {:resource-id :a :data {:x 1} :loaded-at 1 :stale-at 9.0e15}) 100)))
    (is (true?  (ssr/entry-needs-refetch?
                  (entry {:resource-id :a :data {:x 1} :loaded-at 1 :stale-at 50}) 100))
        "stale-with-data → refetch")
    (is (true?  (ssr/entry-needs-refetch?
                  (entry {:resource-id :a :data nil}) 100))
        "no-data (metadata-only) → refetch")))

;; ===========================================================================
;; 4a-bis. Empty infinite feed hydrates into a REFETCH, not fresh-forever
;;         (rf2-x76af2.11)
;; ===========================================================================
;;
;; An infinite feed's `:data` is the ordered PAGE VECTOR seeded `[]` (EP-0021
;; R1). An SSR-serialized infinite feed that was ensured but never drained rides
;; the wire with `:data []`, `:infinite? true`, `:stale-at nil`. The pre-fix
;; `hydrated-data-usable?` used `(some? :data)`, so `[]` read as fresh-with-data
;; → EXCLUDED from the refetch plan → the feed rendered permanently empty with no
;; error and no recovery (a terminal no-op `load-more`). The fix delegates the
;; usable-data question to `state/has-data?` (whose infinite branch is
;; `(seq data)`), so an empty page vector is correctly refetched on hydration.

(def ^:private fkey
  ;; a global-scope INFINITE feed key
  (state/scoped-resource-key :rf.scope/global :feed/timeline {}))

(defn- infinite-entry* [m]
  (assoc (entry m) :infinite? true))

(deftest empty-infinite-feed-is-not-usable-data
  (testing "rf2-x76af2.11 — hydrated-data-usable? mirrors state/has-data?'s
            infinite empty-page-vector branch (an empty feed is NOT usable)"
    (is (false? (ssr/hydrated-data-usable?
                  (infinite-entry* {:resource-id :feed/timeline :data [] :status :idle})))
        "empty infinite :data [] is NOT usable last-known-good data")
    (is (true? (ssr/hydrated-data-usable?
                 (infinite-entry* {:resource-id :feed/timeline :data [{:items [1]}]
                                   :status :loaded :loaded-at 1000 :stale-at 9.0e15})))
        "an infinite feed with at least one accumulated page IS usable")
    (is (false? (ssr/hydrated-data-usable?
                  (infinite-entry* {:resource-id :feed/timeline :data privacy/redacted-sentinel
                                    :status :loaded})))
        "a redacted infinite entry is still metadata-only (sentinel ruled out before has-data?)")))

(deftest empty-infinite-feed-refetches-not-stranded-fresh-forever
  (testing "rf2-x76af2.11 ADVERSARIAL — an SSR-serialized empty infinite feed
            (:data [], never drained, :stale-at nil) appears in the refetch plan
            with :reason :no-data; pre-fix it was treated as fresh-with-data and
            stranded rendering permanently empty"
    (let [empty-feed (infinite-entry* {:resource-id :feed/timeline :status :idle
                                       :data [] :stale-at nil})]
      (is (true? (ssr/entry-needs-refetch? empty-feed 5000))
          "an empty infinite feed needs a client refetch — never fresh-forever")
      (let [plan (->> (ssr/hydrate-refetch-plan (runtime-db-with {fkey empty-feed}) 5000)
                      (into {} (map (juxt :resource/key identity))))]
        (is (contains? plan fkey) "the empty infinite feed IS in the refetch plan")
        (is (= :no-data (:reason (plan fkey)))
            "reason is :no-data (no usable last-known-good page)")
        (is (= :feed/timeline (:resource-id (plan fkey))))))))

(deftest loaded-infinite-feed-with-page-not-double-fetched
  (testing "rf2-x76af2.11 — the fix must not over-refetch: a FRESH infinite feed
            WITH a page stays ABSENT from the plan (the SSR win, no double-fetch)"
    (let [loaded-feed (infinite-entry* {:resource-id :feed/timeline :status :loaded
                                        :data [{:items [1 2 3]}] :loaded-at 1000 :stale-at 9.0e15})]
      (is (false? (ssr/entry-needs-refetch? loaded-feed 5000)))
      (let [plan (->> (ssr/hydrate-refetch-plan (runtime-db-with {fkey loaded-feed}) 5000)
                      (into {} (map (juxt :resource/key identity))))]
        (is (not (contains? plan fkey))
            "a fresh infinite feed WITH a page is NOT double-fetched")))))

(deftest hydrate-settles-empty-infinite-loading-then-refetches
  (testing "rf2-x76af2.11 END-TO-END repro — a server-side :loading empty infinite
            feed settles to :idle (has-data? false) on hydration and the plan then
            refetches it :no-data (the actual stranded-fresh-forever path)"
    (let [e   (infinite-entry* {:resource-id :feed/timeline :status :loading
                                :data [] :stale-at nil})
          out (ssr/hydrate-runtime-db (runtime-db-with {fkey e}) :app/main)
          se  (get-in out [state/resources-key :entries (state/key-id fkey)])]
      (is (= :idle (:status se)) "empty infinite :loading → :idle (never dangling :loading)")
      (is (= [] (:data se)) "the empty page vector is preserved")
      (let [plan (->> (ssr/hydrate-refetch-plan out 5000)
                      (into {} (map (juxt :resource/key identity))))]
        (is (contains? plan fkey) "the settled empty infinite feed IS refetched")
        (is (= :no-data (:reason (plan fkey))))))))

;; ===========================================================================
;; 4b. Hydration refetch: redacted sentinel is metadata-only (rf2-fopuj9)
;; ===========================================================================
;;
;; The redaction sentinel (`:rf/redacted`) rides as `:data` on a `:sensitive?`
;; resource's projected entry — it is METADATA ONLY, NOT usable data. A naive
;; `(some? (:data entry))` would misclassify it as fresh-with-data → never
;; refetch, leaving the client rendering the sentinel as if it were the value.

(deftest redacted-sentinel-is-not-usable-data
  (testing "hydrated-data-usable? excludes the redaction sentinel"
    (is (true?  (ssr/hydrated-data-usable? (entry {:resource-id :a :data {:x 1}})))
        "real data is usable")
    (is (false? (ssr/hydrated-data-usable? (entry {:resource-id :a :data privacy/redacted-sentinel})))
        "the :rf/redacted sentinel is NOT usable (metadata-only)")
    (is (false? (ssr/hydrated-data-usable? (entry {:resource-id :a :data nil})))
        "omitted (nil) is NOT usable")))

(deftest redacted-fresh-entry-still-refetches
  (testing "ADVERSARIAL: a FRESH (stale-at far ahead) entry whose data is the
            redaction sentinel still needs a refetch — the sentinel must NOT be
            mistaken for usable fresh data (rf2-fopuj9)"
    (let [redacted-fresh (entry {:resource-id :secret/thing
                                 :data privacy/redacted-sentinel
                                 :loaded-at 1000 :stale-at 9.0e15 :status :loaded})]
      (is (true? (ssr/entry-needs-refetch? redacted-fresh 5000))
          "a fresh redacted entry refetches (the sentinel is metadata-only, not fresh data)"))))

(deftest refetch-plan-classifies-redacted-vs-omitted-vs-stale-vs-fresh
  (testing "the four hydration dispositions classify correctly (rf2-fopuj9 acceptance)"
    (let [fresh    (entry {:resource-id :a :data {:x 1} :loaded-at 1000 :stale-at 9.0e15})
          stale    (entry {:resource-id :b :data {:x 2} :loaded-at 1000 :stale-at 1500})
          redacted (entry {:resource-id :c :data privacy/redacted-sentinel
                           :loaded-at 1000 :stale-at 9.0e15 :status :loaded})  ;; sensitive → sentinel
          omitted  (entry {:resource-id :d :data nil :status :loaded})          ;; large → no data key
          plan     (->> (ssr/hydrate-refetch-plan (runtime-db-with {ka fresh kb stale kc redacted
                                                                    (state/scoped-resource-key
                                                                      :rf.scope/global :d {}) omitted})
                                                  5000)
                        (into {} (map (juxt :resource/key identity))))
          kd       (state/scoped-resource-key :rf.scope/global :d {})]
      (is (not (contains? plan ka)) "FRESH serialized → absent from the plan (no double-fetch)")
      (is (= :stale         (:reason (plan kb))) "STALE serialized → background refetch")
      (is (= :metadata-only (:reason (plan kc)))
          "REDACTED (sentinel) → metadata-only refetch (NOT misclassified as fresh)")
      (is (= :no-data       (:reason (plan kd))) "OMITTED (no data key) → no-data refetch"))))

(deftest project-then-hydrate-roundtrip-sensitive-installs-no-row
  (reg! :secret/thing {:sensitive? true})
  (testing "END-TO-END, restated for a contract that WITHHOLDS (rf2-4bjep). The
            defect rf2-fopuj9 closed was a redacted entry being read as
            fresh-with-data and never refetched; the answer then was to classify
            it `:metadata-only` and PLAN it. But the plan named the entry by its
            projected key — an identity the route slice cannot resolve — and the
            row it planned was an ownerless duplicate nothing collects. Both
            halves go away together: the row does not ride, so there is nothing
            to misclassify and nothing to plan, and the client issues the one
            load it derives from the raw key"
    (let [k    (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "s"})
          ;; a FRESH sensitive entry on the server (stale-at far ahead)
          e    (entry {:resource-id :secret/thing :data {:ssn "123-45-6789"}
                       :loaded-at 1000 :stale-at 9.0e15})
          rdb  (runtime-db-with {k e})
          proj (ssr/project-resources-runtime-db rdb)
          m    (only-projection-metadata rdb)
          wk   (:projected-key m)]
      (is (empty? (get-in proj [state/resources-key :entries]))
          (str "the row does not ride: " (pr-str proj)))
      (is (not= {:slug "s"} (nth wk 2)) "the sensitive params do not ride raw (rf2-otms75)")
      (is (= :secret/thing (nth wk 1)) "the resource-id is preserved for refetch identity")
      (is (true? (:refetch-on-client? m))
          "and the server's own metadata still says the client must fetch it —
           the fact rf2-fopuj9 was about is reported, not lost")
      ;; CLIENT hydration over what actually ships.
      (let [out  (ssr/hydrate-runtime-db proj nil)
            plan (ssr/hydrate-refetch-plan proj 5000)]
        (is (empty? (get-in out [state/resources-key :entries]))
            "the client's cache holds no row for it — so the sentinel can never
             be rendered as if it were the real value")
        (is (empty? plan)
            (str "and the plan names nothing: a plan entry is consumed by its "
                 ":resource/key, and there is no key here the client has — "
                 (pr-str plan)))))))

(deftest a-redacted-entry-reaching-the-planner-by-any-other-route-is-still-metadata-only
  (reg! :secret/thing {:sensitive? true})
  (testing "rf2-fopuj9's classification contract is UNCHANGED by the withholding
            — this is the control that stops the test above passing because the
            planner has quietly stopped distinguishing a sentinel from data. A
            sentinel-bearing entry under an ADDRESSABLE key (a restore snapshot,
            a host-assembled slice) is still `:metadata-only`, never fresh"
    (let [k (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "s"})
          e (entry {:resource-id :article/by-slug :data privacy/redacted-sentinel
                    :loaded-at 1000 :stale-at 9.0e15 :status :loaded})
          plan (ssr/hydrate-refetch-plan (runtime-db-with {k e}) 5000)]
      (is (= 1 (count plan)) "premise: the addressable row IS planned")
      (is (= :metadata-only (:reason (first plan)))
          "the sentinel is metadata-only, not fresh usable data")
      (is (= k (:resource/key (first plan)))
          "and it is named by the key the client derives"))))

;; ===========================================================================
;; 5. SCOPE isolation
;; ===========================================================================

(deftest hydration-never-crosses-scopes
  (testing "entries under different scopes stay isolated; indexes key on each entry's own scoped key"
    (let [ka (state/scoped-resource-key [:rf.scope/session {:user "a"}] :article/by-slug {:slug "x"})
          kb (state/scoped-resource-key [:rf.scope/session {:user "b"}] :article/by-slug {:slug "x"})
          ea (entry {:resource-id :article/by-slug :data {:owner "a"}
                     :loaded-at 1000 :stale-at 9.0e15 :tags #{[:article "x"]}
                     :owners #{[:route :r "nav-a"]}})
          eb (entry {:resource-id :article/by-slug :data {:owner "b"}
                     :loaded-at 1000 :stale-at 9.0e15 :tags #{[:article "x"]}
                     :owners #{[:route :r "nav-b"]}})
          out (ssr/hydrate-runtime-db (runtime-db-with {ka ea kb eb}) :app/main)
          es  (get-in out [state/resources-key :entries])]
      (is (= {:owner "a"} (:data (es (state/key-id ka)))) "scope-a data stays under scope-a's key")
      (is (= {:owner "b"} (:data (es (state/key-id kb)))) "scope-b data stays under scope-b's key")
      (testing "the shared tag [:article \"x\"] maps to BOTH scoped keys, never collapsed"
        ;; rf2-9e0tyq — index members are the byte key-id.
        (is (= #{(state/key-id ka) (state/key-id kb)}
               (get-in out [state/resources-key :tag-index [:article "x"]]))))
      (testing "each scope's owner indexes only its own key"
        (is (= #{(state/key-id ka)} (get-in out [state/resources-key :owner-index [:route :r "nav-a"]])))
        (is (= #{(state/key-id kb)} (get-in out [state/resources-key :owner-index [:route :r "nav-b"]])))))))

;; ===========================================================================
;; 6. End-to-end through the :rf/hydrate reconcile hook
;; ===========================================================================

(deftest hooks-are-published
  (testing "both SSR late-bind hooks are published by the façade"
    (is (some? (late-bind/get-fn :ssr/extend-runtime-db-projection)))
    (is (some? (late-bind/get-fn :resources/hydrate-runtime-db)))
    (is (= ssr/project-resources-runtime-db
           (late-bind/get-fn :ssr/extend-runtime-db-projection)))
    (is (= ssr/hydrate-runtime-db
           (late-bind/get-fn :resources/hydrate-runtime-db)))))

(deftest project-runtime-db-merges-resource-slice
  (reg! :article/by-slug)
  (testing "SSR's project-runtime-db consults the resources hook and merges the :entries slice"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15})
          rdb (runtime-db-with {gkey e})
          ;; the SSR payload-policy is the consumer of :ssr/extend-runtime-db-projection
          proj (payload-policy/project-runtime-db rdb)]
      (is (contains? proj state/resources-key))
      (is (contains? (get-in proj [state/resources-key :entries]) (state/key-id gkey))))))

(deftest hydrate-event-reconciles-resource-slice
  (reg! :article/by-slug)
  (testing "the :rf/hydrate handler runs the resources reconcile hook on the installed runtime-db"
    (let [e   (entry {:resource-id :article/by-slug :data {:t "x"}
                      :loaded-at 1000 :stale-at 9.0e15
                      :tags #{[:article "x"]}
                      :current-work [:rf.work/resource gkey 1]
                      :owners #{[:ssr "req-1" "nav-1"]
                                [:route :route/article "nav-1"]}})
          payload {:rf/frame-id :rf/default
                   :rf/app-db   {}
                   :rf/runtime-db (runtime-db-with {gkey e})}]
      (rf/dispatch-sync [:rf/hydrate payload])
      (let [rdb (:rf.db/runtime (rf/frame-state-value :rf/default))
            installed (get-in rdb [state/resources-key :entries (state/key-id gkey)])]
        (is (= {:t "x"} (:data installed)) "entry data preserved through hydrate")
        (is (nil? (:current-work installed)) "transient current-work cleared")
        (is (not (contains? (:active-owners installed) [:ssr "req-1" "nav-1"]))
            "SSR owner orphaned during the :rf/hydrate reconcile")
        (is (contains? (:active-owners installed) [:route :route/article "nav-1"])
            "route owner survives")
        (is (= {[:article "x"] #{(state/key-id gkey)}}
               (get-in rdb [state/resources-key :tag-index]))
            "tag-index recomputed from entries during the :rf/hydrate reconcile (byte key-id member)")))))

(ns re-frame.resources-ssr-cljs-test
  "SSR / hydration for the Resources artefact (Spec 016 §SSR and hydration).
  SSR runs on the JVM, so the suite is CLJC and the JVM run (`clojure -M:test`)
  is the load-bearing gate:

    1. SERVER projection — the hydration wire carries ONLY the durable
       `:entries`, and a coarse `:sensitive?` / `:large?` key is tokenized by
       the per-classification token contract;
    2. SERVER blocking drain — `blocking-settled?`, `settle-blocking-timeout`
       and the `drain-blocking-resources!` loop, which never hangs;
    3. CLIENT hydration — `hydrate-runtime-db` recomputes the reverse indexes
       (never trusting the wire), orphans SSR owners, settles dangling
       in-flight statuses and never crosses scopes;
    4. NO double-fetch — `hydrate-refetch-plan` omits fresh-with-data entries.

  Which rows ride, and the per-entry projection metadata, are pinned by
  `re-frame.resources-ssr-projected-key-refetch-cljs-test`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.frame :as rf.frame]
   [re-frame.privacy :as rf.privacy]
   ;; load-bearing side-effecting requires: the façade publishes the SSR
   ;; projection + reconcile hooks + registers the resource registrar kind.
   [re-frame.resources]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   ;; production HTTP fx surface, so the transport feature probe resolves on the
   ;; real-path drain tests; the fetch + abort fxs are stubbed below.
   [re-frame.http.managed]
   ;; SSR artefact — the :rf/hydrate handler that consults the reconcile hook.
   [re-frame.ssr]
   ;; the consumer of :ssr/extend-runtime-db-projection (project-runtime-db).
   [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(defn- stub-transport-fixture
  "Replace the managed-HTTP fetch + abort fxs with no-ops so the real-path
  ensure writes are deterministic and nothing reaches a network."
  [f]
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx _request-id] nil))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  stub-transport-fixture)

;; ---- helpers --------------------------------------------------------------

(defn- reg!
  "Register a resource id with optional spec overrides (defaults to a
  global-scope slug resource)."
  ([id] (reg! id {}))
  ([id overrides]
   (rf/clear :resource id)
   (let [spec (merge {:scope         :rf.scope/global
                      :params-schema [:map [:slug :string]]
                      :request       (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})
                      :tags          (fn [{:keys [slug]} _] #{[:article slug]})}
                     overrides)]
     (rf/reg-resource id (dissoc spec :request) (:request spec)))))

(defn- entry
  "A durable entry with the supplied status / data / timestamps, in the
  runtime's durable shape."
  [{:keys [resource-id status data loaded-at stale-at invalidated-at
           generation current-work tags owners refresh-error]
    :or   {status :loaded generation 1 tags #{} owners #{}}}]
  (merge (rf.resources.state/empty-entry resource-id)
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

(defn- entries*
  "Build the runtime's byte-keyed `:entries` map from `{scoped-key entry}`,
  stamping each entry's `:resource/key`."
  [m]
  (into {} (map (fn [[sk e]] [(rf.resources.state/key-id sk) (assoc e :resource/key sk)])) m))

(defn- runtime-db-with
  "A runtime-db carrying `entries` (given as `{scoped-key entry}`) in the
  runtime's byte-keyed shape, with empty reverse indexes."
  [entries]
  {rf.resources.state/resources-key {:entries     (entries* entries)
                                     :tag-index   {}
                                     :owner-index {}}})

(defn- entry-by [entries sk] (get entries (rf.resources.state/key-id sk)))

(defn- blocking-map
  "The byte-keyed blocking carrier `{<key-id> <scoped-key>}` the route slice
  writes and the drain consumes."
  [& ks]
  (into {} (map (juxt rf.resources.state/key-id identity)) ks))

(def ^:private gkey
  (rf.resources.state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"}))

(defn- only-projection-metadata
  "The single per-entry projection metadata map for a one-entry runtime-db —
  the observation point for an entry whose ROW is withheld."
  [runtime-db]
  (first (rf.resources.ssr/projection-metadata
           nil 5000 (get-in runtime-db [rf.resources.state/resources-key :entries]))))

;; ===========================================================================
;; 1. SERVER projection
;; ===========================================================================

(deftest hydration-projection-ships-no-work-ledger-rows
  ;; Through SSR's payload policy over a real frame: the resources slice rides
  ;; ONLY its durable :entries — no reverse indexes, and no work-ledger rows
  ;; (host work facts never cross the hydration wire).
  (reg! :article/by-slug)
  (let [fid :ssr/drain-no-ledger-on-wire]
    (rf/make-frame {:id fid :doc "ssr no-ledger-on-wire frame" :platform :server})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :article/by-slug :scope :rf.scope/global
                        :params {:slug "x"} :owner [:ssr "req-3" "nav-3"]}]
                      {:frame fid})
    (let [rdb  (rf.frame/frame-runtime-db-value fid)
          proj (rf.ssr.payload-policy/project-runtime-db rdb)]
      (is (and (seq (get rdb rf.resources.state/work-ledger-key))
               (seq (get-in rdb [rf.resources.state/resources-key :owner-index])))
          "premise: the live runtime-db carries a work-ledger row and a reverse index")
      (is (= [#{:entries} #{(rf.resources.state/key-id gkey)}]
             [(set (keys (get proj rf.resources.state/resources-key)))
              (set (keys (get-in proj [rf.resources.state/resources-key :entries])))]))
      (is (not (contains? proj rf.resources.state/work-ledger-key))))
    (rf.frame/destroy-frame! fid)))

(deftest sensitive-resource-key-scope-and-params-are-redacted
  ;; Spec 016 clause 4: scope and params carry the data's classification. The
  ;; sensitive tokens are content-free, so no user, tenant or param survives.
  (reg! :secret/thing {:sensitive? true})
  (let [k (rf.resources.state/scoped-resource-key
            [:rf.scope/session {:user "alice@example.com" :tenant "acme"}]
            :secret/thing {:account-id "secret-42"})
        e (entry {:resource-id :secret/thing :data {:ssn "x"} :loaded-at 1000 :stale-at 9.0e15})]
    (is (= [{:rf/redacted {:type :vector :count 2}}
            :secret/thing
            {:rf/redacted {:type :map :count 1}}]
           (:projected-key (only-projection-metadata (runtime-db-with {k e})))))))

(deftest project-scoped-key-by-disposition
  ;; :serialize rides verbatim. :redact is content-free, so two SENSITIVE keys
  ;; collapse — a token that kept them apart would be enumerable. :omit is a
  ;; size claim, so its digest keeps distinct keys distinct.
  (let [k1 (rf.resources.state/scoped-resource-key :rf.scope/global :r {:a 1})
        k2 (rf.resources.state/scoped-resource-key :rf.scope/global :r {:a 2})]
    (is (= k1 (rf.resources.ssr/project-scoped-key k1 :serialize nil)))
    (is (= [{:rf/redacted {:type :keyword}} :r {:rf/redacted {:type :map :count 1}}]
           (rf.resources.ssr/project-scoped-key k1 :redact nil)
           (rf.resources.ssr/project-scoped-key k2 :redact nil)))
    (is (not= (rf.resources.ssr/project-scoped-key k1 :omit nil)
              (rf.resources.ssr/project-scoped-key k2 :omit nil)))))

;; ---- the token contract, per classification --------------------------------
;;
;; `redact-value` emits a digest only for the classification that PERMITS one.
;; A sensitive value gets a content-FREE shape token, because a 32-bit token
;; over a low-entropy tenant id is recoverable by enumeration.
;;
;; Where a digest does ride, it is `fnv-1a-32` over `identity/canonical-bytes`,
;; so it is a fixed function of the CANONICAL value (`pr-str` walks a map in
;; iteration order, so `=` values could emit different digests).
;;
;; The digest must also be cross-host stable: a CLJS branch that hashed UTF-16
;; CODE UNITS, multiplied 32-bit states with a double `*` past 2^53, or emitted
;; a SIGNED result would diverge from the JVM's. The literal expected digests
;; below are checked by both the JVM run and the `:node-test` run, at the three
;; places the encodings can part company — pure ASCII, a 2-byte code point, and
;; a SURROGATE PAIR. The non-ASCII strings are built from `char` code points so
;; a re-encoding of this file cannot change the fixture.

(def ^:private cafe-str (str "caf" (char 0xe9)))                      ;; "café"
(def ^:private emoji-str (str "a" (char 0xd83d) (char 0xde00) "b"))   ;; "a<U+1F600>b"

(deftest sensitive-redaction-emits-no-content-derived-token
  (testing "the SENSITIVE arm (and every caller that names no
            disposition, which is the fail-closed default) emits a token whose
            every slot is a closed-vocabulary tag or an integer. There is no
            candidate space to enumerate against it: every 6-character string
            produces the same token"
    (is (= {:rf/redacted {:type :string :count 6}} (rf.resources.ssr/redact-value "tenant")))
    (is (= (rf.resources.ssr/redact-value "tenant") (rf.resources.ssr/redact-value "abc123"))
        "two distinct 6-char secrets are INDISTINGUISHABLE — that is the property")
    (is (= {:rf/redacted {:type :map :count 1}} (rf.resources.ssr/redact-value {:tenant-id cafe-str})))
    (is (= {:rf/redacted {:type :number}} (rf.resources.ssr/redact-value 42)))
    (is (= {:rf/redacted nil} (rf.resources.ssr/redact-value nil))
        "nil / empty still projects to the stable no-content token")
    (is (= {:rf/redacted nil} (rf.resources.ssr/redact-value {}))))

  (testing "the 1-arity is the SAFE one, so a caller that cannot name a
            disposition cannot accidentally mint an enumerable token"
    (is (= (rf.resources.ssr/redact-value "tenant") (rf.resources.ssr/redact-value "tenant" :redact))))

  (testing "and it is TOTAL — an app payload carrying a value outside CEDN-1 is
            summarised, not thrown at. A redaction that explodes is not a
            redaction"
    (is (= {:rf/redacted {:type :map :count 1}} (rf.resources.ssr/redact-value {:f (fn [_])})))
    (is (= {:rf/redacted {:type :number}} (rf.resources.ssr/redact-value 1.5)))))

(deftest large-redaction-digest-is-byte-identical-across-clj-and-cljs
  (testing "the `:omit` (large) digest is a fixed
            function of the value's CANONICAL BYTES on EVERY host. The expected
            digests are literals checked by both runs of this `.cljc`, which is
            the only shape of assertion that can catch one host drifting"
    (is (= {:rf/redacted "d6a56084"} (rf.resources.ssr/redact-value "tenant" :omit))
        "ASCII: the bytes are identical on both hosts, so only the arithmetic
         can differ — a double multiply would lose the low bits")
    (is (= {:rf/redacted "475d8714"} (rf.resources.ssr/redact-value "" :omit))
        "the empty string still hashes (it is not the nil/empty-COLLECTION case)")
    (is (= {:rf/redacted "3fb1602a"} (rf.resources.ssr/redact-value cafe-str :omit))
        "non-ASCII: one code point, two UTF-8 bytes, one UTF-16 code unit")
    (is (= {:rf/redacted "c4ef233c"} (rf.resources.ssr/redact-value emoji-str :omit))
        "a SURROGATE PAIR: one code point, four UTF-8 bytes, TWO UTF-16 code
         units — the case a per-code-unit walk cannot get right")
    (is (= {:rf/redacted "cd777c20"} (rf.resources.ssr/redact-value {:tenant-id cafe-str} :omit))
        "and the same through a whole scope map, which is how it is really used")
    (is (= {:rf/redacted nil} (rf.resources.ssr/redact-value nil :omit)))))

(deftest large-redaction-digest-is-a-function-of-the-canonical-value
  (testing "the concrete witness — `(array-map :a 1 :b 2)` and
            `(array-map :b 2 :a 1)` are `=`, have equal canonical bytes, and
            would emit 943e4859 / 08a47259 under `pr-str`. Hashing
            identity/canonical-bytes makes them ONE token, on both hosts"
    (is (= (rf.resources.ssr/redact-value (array-map :a 1 :b 2) :omit)
           (rf.resources.ssr/redact-value (array-map :b 2 :a 1) :omit)))
    (is (= {:rf/redacted "64f7985e"} (rf.resources.ssr/redact-value (array-map :a 1 :b 2) :omit))
        "pinned as a literal so a host that reorders differently reds here"))

  (testing "SET construction order likewise — canonical bytes sort set elements"
    (is (= (rf.resources.ssr/redact-value #{:a :b :c} :omit)
           (rf.resources.ssr/redact-value (into #{} [:c :b :a]) :omit))))

  (testing "and a value OUTSIDE CEDN-1 falls back to the content-free shape
            rather than throwing — `canonical-bytes` is partial, and an off-box
            egress projector is handed whatever the app owns"
    (is (= {:rf/redacted {:type :map :count 1}} (rf.resources.ssr/redact-value {:f (fn [_])} :omit)))
    (is (= {:rf/redacted {:type :number}} (rf.resources.ssr/redact-value 1.5 :omit)))))

(deftest the-large-redaction-digest-has-the-shape-it-documents
  (testing "8 lower-case hex characters, always. CLJS `bit-and`
            yields a SIGNED int32, so rendering it unconverted would turn about
            half of all inputs into a NEGATIVE 9-character string with a
            leading `-`. Stated over
            a spread wide enough to hit the negative half rather than over one
            lucky value"
    (let [digests (into []
                        (map (fn [i] (:rf/redacted (rf.resources.ssr/redact-value {:n i :s (str "id-" i)} :omit))))
                        (range 256))]
      (is (every? (fn [d] (and (string? d)
                               (= 8 (count d))
                               (re-matches #"[0-9a-f]{8}" d)))
                  digests)
          (str "every digest is 8 lower-case hex chars — "
               (pr-str (remove (fn [d] (re-matches #"[0-9a-f]{8}" d)) digests))))
      (is (some (fn [d] (>= (compare d "80000000") 0)) digests)
          "…and the spread reaches the high half, so a signed-int32 render
           would be in range rather than merely unlucky to miss")
      (is (= 256 (count (set digests)))
          "and the 256 distinct values keep 256 distinct digests"))))

;; ===========================================================================
;; 2. SERVER blocking drain + timeout
;; ===========================================================================

(def ^:private ka (rf.resources.state/scoped-resource-key :rf.scope/global :a {:slug "a"}))
(def ^:private kb (rf.resources.state/scoped-resource-key :rf.scope/global :b {:slug "b"}))
(def ^:private kc (rf.resources.state/scoped-resource-key :rf.scope/global :c {:slug "c"}))

(def ^:private timeout-error {:kind :rf.http/timeout :reason :ssr-blocking-timeout})

(deftest blocking-settled-predicate
  (testing "blocking-settled? is true iff every blocking entry has settled"
    (let [loaded  (entry {:resource-id :a :status :loaded :data {:x 1}})
          errored (entry {:resource-id :b :status :error})
          loading (entry {:resource-id :c :status :loading})
          es      (entries* {ka loaded kb errored kc loading})]
      (is (true?  (rf.resources.ssr/blocking-settled? es (blocking-map ka kb)))
          ":loaded and :error are settled")
      (is (false? (rf.resources.ssr/blocking-settled? es (blocking-map ka kc)))
          ":loading is NOT settled")
      (is (false? (rf.resources.ssr/blocking-settled? es (blocking-map ka [:rf.scope/global :missing {}])))
          "an absent (never-enqueued) blocking key is not settled")
      (is (true?  (rf.resources.ssr/blocking-settled? es {}))
          "no blocking resources → trivially settled (never blocks render)"))))

(deftest blocking-timeout-settles-first-load-failure
  ;; Every unsettled blocking entry — one that never wrote an entry included —
  ;; settles to a structured first-load failure, so the render never hangs.
  (let [kmiss (rf.resources.state/scoped-resource-key :rf.scope/global :missing {})
        es    (entries* {ka (entry {:resource-id :a :status :loading})
                         kb (entry {:resource-id :b :status :loaded :data {:x 1}})})
        {:keys [entries route-blocking-failure]}
        (rf.resources.ssr/settle-blocking-timeout es (blocking-map ka kb kmiss) 250 :app/main)]
    (doseq [k [ka kmiss]]
      (let [se (entry-by entries k)]
        (is (= [:error timeout-error nil]
               [(:status se) (select-keys (:error se) (keys timeout-error)) (:data se)])
            (pr-str k))))
    (is (= (entry-by es kb) (entry-by entries kb)) "the already-settled entry is untouched")
    (is (= {:rf.error/id :rf.error/resource-ssr-blocking-timeout
            :timed-out   #{ka kmiss}
            :limit-ms    250}
           (-> (select-keys route-blocking-failure [:rf.error/id :timed-out :limit-ms])
               (update :timed-out set))))))

(deftest blocking-timeout-noop-when-all-settled
  (testing "no unsettled blocking entries → no failure record, entries unchanged"
    (let [es (entries* {ka (entry {:resource-id :a :status :loaded :data {:x 1}})})
          {:keys [entries route-blocking-failure]}
          (rf.resources.ssr/settle-blocking-timeout es (blocking-map ka) 250 :app/main)]
      (is (= es entries))
      (is (nil? route-blocking-failure)))))

;; `drain-blocking-resources!` is the loop the host render path calls before
;; rendering: it reads the current nav-token's blocking set, pumps the event
;; loop until every member settles, and on the render deadline settles the
;; rest to a first-load failure IN the frame's runtime-db. These tests drive it
;; with an injected pump and clock.

(defn- seed-frame-runtime-db!
  "Register `frame-id` and install `runtime-db` as its runtime-db partition."
  [frame-id runtime-db]
  (rf/make-frame {:id frame-id :doc "ssr blocking-drain test frame" :platform :server})
  (rf.frame/replace-runtime-db! frame-id runtime-db)
  frame-id)

(defn- with-blocking-slot
  "Add a routing slice naming `nav-token` live, plus its byte-keyed blocking
  slot holding `blocking-keys` — what the route slice writes on entry."
  [runtime-db nav-token blocking-keys]
  (-> runtime-db
      (assoc-in [:rf.runtime/routing :current :nav-token] nav-token)
      (assoc-in [:rf.runtime/routing :resource-blocking nav-token]
                (apply blocking-map blocking-keys))))

(deftest current-blocking-keys-reads-current-nav-token-slot
  (testing "current-blocking-keys reads ONLY the current nav-token's blocking slot"
    (let [rdb (-> (runtime-db-with {})
                  (with-blocking-slot "nav-1" [ka kb])
                  ;; a superseded nav-token's stale slot must NOT leak in
                  (assoc-in [:rf.runtime/routing :resource-blocking "nav-OLD"]
                            (blocking-map kc)))]
      (is (= (blocking-map ka kb) (rf.resources.ssr/current-blocking-keys rdb))))
    (testing "no routing slice / no current nav-token → empty (never blocks)"
      (is (= {} (rf.resources.ssr/current-blocking-keys (runtime-db-with {}))))
      (is (= {} (rf.resources.ssr/current-blocking-keys {}))))))

(deftest drain-noop-when-no-blocking-resources
  (let [pumped (atom 0)
        fid    (seed-frame-runtime-db! :ssr/drain-none
                                       (runtime-db-with {ka (entry {:resource-id :a :status :loaded :data {:x 1}})}))]
    (is (= {:settled? true :timed-out [] :route-blocking-failure nil}
           (rf.resources.ssr/drain-blocking-resources!
             fid {:pump! (fn [_] (swap! pumped inc)) :deadline-ms 1000})))
    (is (zero? @pumped) "no blocking set → the loop never pumps")
    (rf.frame/destroy-frame! fid)))

(defn- ledger-row
  "The work-ledger record for `work-id` in `frame-id`'s live runtime-db, or nil."
  [frame-id work-id]
  (rf.resources.work-ledger/get-record (rf.frame/frame-runtime-db-value frame-id) work-id))

(defn- entry-status [frame-id]
  (get-in (rf.frame/frame-runtime-db-value frame-id)
          (conj (rf.resources.state/entry-path gkey) :status)))

(deftest drain-release-on-the-real-path-leaves-the-ledger-row-terminal
  ;; Through the REAL ensure and the REAL succeeded reply, which settles the
  ;; entry and its work-ledger row in one write: a drain released by the entry
  ;; leaves no live work behind the render.
  (reg! :article/by-slug)
  (let [fid :ssr/drain-ledger-terminal]
    (rf/make-frame {:id fid :doc "ssr ledger-terminal drain frame" :platform :server})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :article/by-slug :scope :rf.scope/global
                        :params {:slug "x"} :owner [:ssr "req-1" "nav-1"]
                        :cause [:route-entry :route/article "nav-1"]}]
                      {:frame fid})
    (let [wid (get-in (rf.frame/frame-runtime-db-value fid)
                      (conj (rf.resources.state/entry-path gkey) :current-work))]
      (is (= [:running true :loading]
             [(:status (ledger-row fid wid))
              (some? (rf.resources.work-ledger/get-handle fid wid))
              (entry-status fid)])
          "premise: live work (a :running row and a host handle) behind a :loading entry")
      (rf.frame/swap-runtime-db! fid with-blocking-slot "nav-1" [gkey])
      ;; the pump lands the REAL reply on its 2nd tick
      (let [ticks (atom 0)
            pump! (fn [_]
                    (when (= 2 (swap! ticks inc))
                      (rf/dispatch-sync
                        [:rf.resource.internal/succeeded
                         {:resource/key gkey :work/id wid :generation 1
                          :rf.frame/id fid :data {:title "X"}}]
                        {:frame fid})))]
        (is (= {:settled? true :timed-out [] :route-blocking-failure nil}
               (rf.resources.ssr/drain-blocking-resources! fid {:pump! pump! :deadline-ms 60000})))
        (is (= :loaded (entry-status fid)))
        (let [row (ledger-row fid wid)]
          ;; terminal, or pruned by the bounded per-key tail — never live
          (is (or (nil? row) (rf.resources.work-ledger/terminal? (:status row)))))
        (is (nil? (rf.resources.work-ledger/get-handle fid wid)) "the host handle is cleared")))
    (rf.frame/destroy-frame! fid)))

(deftest drain-timeout-on-the-real-path-leaves-the-ledger-row-timed-out
  ;; A never-settling blocking resource is settled to a structured first-load
  ;; ERROR once the render deadline fires, never left a hung :loading, and its
  ;; abandoned row goes terminal :timed-out in the same write. A nil :pump! (a
  ;; synchronous stub) still respects the deadline.
  (reg! :article/by-slug)
  (let [fid :ssr/drain-ledger-timed-out]
    (rf/make-frame {:id fid :doc "ssr ledger-timed-out drain frame" :platform :server})
    (rf/dispatch-sync [:rf.resource/ensure
                       {:resource :article/by-slug :scope :rf.scope/global
                        :params {:slug "x"} :owner [:ssr "req-4" "nav-4"]}]
                      {:frame fid})
    (rf.frame/swap-runtime-db! fid with-blocking-slot "nav-4" [gkey])
    (let [wid      (get-in (rf.frame/frame-runtime-db-value fid)
                           (conj (rf.resources.state/entry-path gkey) :current-work))
          ;; a clock that jumps past the deadline, so the test never sleeps
          clk      (atom 0)
          clock-fn (fn [] (let [v @clk] (swap! clk + 100) v))]
      (is (= [:running true]
             [(:status (ledger-row fid wid)) (some? (rf.resources.work-ledger/get-handle fid wid))])
          "premise: a live row and a host handle")
      (let [res (rf.resources.ssr/drain-blocking-resources!
                  fid {:pump! nil :deadline-ms 50 :clock-fn clock-fn})
            se  (get-in (rf.frame/frame-runtime-db-value fid) (rf.resources.state/entry-path gkey))
            row (ledger-row fid wid)]
        (is (= [false [gkey] :rf.error/resource-ssr-blocking-timeout]
               [(:settled? res) (:timed-out res) (:rf.error/id (:route-blocking-failure res))]))
        (is (= [:error timeout-error]
               [(:status se) (select-keys (:error se) (keys timeout-error))]))
        (is (= [:timed-out {:reason :ssr-blocking-timeout :limit-ms 50}]
               [(:status row) (:outcome row)]))
        (is (nil? (rf.resources.work-ledger/get-handle fid wid)) "the host handle is cleared")))
    (rf.frame/destroy-frame! fid)))

;; ===========================================================================
;; 3. CLIENT hydration reconcile
;; ===========================================================================

(deftest hydrate-recomputes-indexes-from-entries
  ;; The wire's indexes are discarded and rebuilt from the entries, after the
  ;; SSR owner (a settled server render's) is orphaned; a route owner survives
  ;; for routing's own liveness reconcile.
  (let [ssr-owner   [:ssr "req-7" "nav-1"]
        route-owner [:route :route/article "nav-1"]
        e   (entry {:resource-id :article/by-slug :data {:t "x"}
                    :loaded-at 1000 :stale-at 9.0e15
                    :tags #{[:article "x"]} :owners #{ssr-owner route-owner}})
        rdb (-> (runtime-db-with {gkey e})
                (assoc-in [rf.resources.state/resources-key :tag-index] {[:bogus] #{:nope}})
                (assoc-in [rf.resources.state/resources-key :owner-index] {[:bogus] #{:nope}}))
        sub (get (rf.resources.ssr/hydrate-runtime-db rdb :app/main) rf.resources.state/resources-key)
        kid (rf.resources.state/key-id gkey)]
    (is (= #{route-owner} (get-in sub [:entries kid :active-owners])))
    (is (= {[:article "x"] #{kid}} (:tag-index sub)))
    (is (= {route-owner #{kid}} (:owner-index sub)))))

(deftest hydrate-noop-without-resources
  (testing "a runtime-db with no resource entries is returned unchanged (SSR app without resources)"
    (let [rdb {:rf.runtime/machines {:snapshots {}}}]
      (is (= rdb (rf.resources.ssr/hydrate-runtime-db rdb :app/main))))))

(def ^:private fkey
  ;; a global-scope INFINITE feed key
  (rf.resources.state/scoped-resource-key :rf.scope/global :feed/timeline {}))

(defn- infinite-entry* [m]
  (assoc (entry m) :infinite? true))

(deftest hydrate-settles-dangling-entries-to-last-stable
  ;; The projection keeps :status but strips :current-work, so an entry can
  ;; arrive :loading / :fetching with no work behind it. Hydration settles it to
  ;; its last STABLE status, and the refetch plan then classifies that.
  (doseq [[label k e expected]
          [["loading with no data → :idle, refetched :no-data"
            gkey (entry {:resource-id :article/by-slug :status :loading :data nil})
            [:idle nil :no-data]]
           ["fetching with fresh data → :loaded and NOT refetched (no double-fetch)"
            gkey (entry {:resource-id :article/by-slug :status :fetching
                         :data {:t "fresh"} :loaded-at 1000 :stale-at 9.0e15})
            [:loaded {:t "fresh"} nil]]
           ["fetching with stale data → :loaded, background-refetched :stale"
            gkey (entry {:resource-id :article/by-slug :status :fetching
                         :data {:t "stale"} :loaded-at 1000 :stale-at 1500})
            [:loaded {:t "stale"} :stale]]
           ["an EMPTY infinite feed loading → :idle, refetched :no-data (an empty page vector is not data)"
            fkey (infinite-entry* {:resource-id :feed/timeline :status :loading
                                   :data [] :stale-at nil})
            [:idle [] :no-data]]]]
    (let [out  (rf.resources.ssr/hydrate-runtime-db (runtime-db-with {k e}) :app/main)
          se   (get-in out [rf.resources.state/resources-key :entries (rf.resources.state/key-id k)])
          plan (into {} (map (juxt :resource/key :reason))
                     (rf.resources.ssr/hydrate-refetch-plan out 5000))]
      (is (= expected [(:status se) (:data se) (get plan k)]) label))))

(deftest clock-skew-surfaced-when-stale-at-implausible
  (testing "clock-skew-ms returns positive skew when :stale-at lies implausibly ahead of the live clock"
    ;; window = stale-at - loaded-at = 1000; stale-at 100000 is far beyond
    ;; (clock 2000 + window 1000) = 3000 → implausible (server clock ran ahead).
    (let [e (entry {:resource-id :a :data {:x 1} :loaded-at 99000 :stale-at 100000})]
      (is (= (- 100000 2000) (rf.resources.ssr/clock-skew-ms e 2000))))
    (testing "a plausible stale-at returns nil (no skew)"
      (let [e (entry {:resource-id :a :data {:x 1} :loaded-at 1000 :stale-at 2000})]
        (is (nil? (rf.resources.ssr/clock-skew-ms e 1500)))))
    (testing "no :stale-at → nil (cannot assess)"
      (is (nil? (rf.resources.ssr/clock-skew-ms (entry {:resource-id :a :data {:x 1}}) 1500))))))

(deftest refetch-plan-classifies-redacted-vs-omitted-vs-stale-vs-fresh
  ;; Fresh usable data is never double-fetched — an infinite feed holding a page
  ;; included — while the redaction SENTINEL is metadata, not data.
  (let [kd   (rf.resources.state/scoped-resource-key :rf.scope/global :d {})
        plan (->> (rf.resources.ssr/hydrate-refetch-plan
                    (runtime-db-with
                      {ka   (entry {:resource-id :a :data {:x 1} :loaded-at 1000 :stale-at 9.0e15})
                       kb   (entry {:resource-id :b :data {:x 2} :loaded-at 1000 :stale-at 1500})
                       kc   (entry {:resource-id :c :data rf.privacy/redacted-sentinel
                                    :loaded-at 1000 :stale-at 9.0e15 :status :loaded})
                       kd   (entry {:resource-id :d :data nil :status :loaded})
                       fkey (infinite-entry* {:resource-id :feed/timeline :status :loaded
                                              :data [{:items [1 2 3]}]
                                              :loaded-at 1000 :stale-at 9.0e15})})
                    5000)
                  (into {} (map (juxt :resource/key :reason))))]
    (is (= {kb :stale kc :metadata-only kd :no-data} plan))))

(deftest hydration-never-crosses-scopes
  ;; Indexes key on each entry's own scoped key, so a shared tag maps to BOTH
  ;; scopes' keys and each scope's owner indexes only its own.
  (let [k-a (rf.resources.state/scoped-resource-key [:rf.scope/session {:user "a"}] :article/by-slug {:slug "x"})
        k-b (rf.resources.state/scoped-resource-key [:rf.scope/session {:user "b"}] :article/by-slug {:slug "x"})
        e   (fn [user] (entry {:resource-id :article/by-slug :data {:owner user}
                               :loaded-at 1000 :stale-at 9.0e15 :tags #{[:article "x"]}
                               :owners #{[:route :r (str "nav-" user)]}}))
        sub (get (rf.resources.ssr/hydrate-runtime-db (runtime-db-with {k-a (e "a") k-b (e "b")}) :app/main)
                 rf.resources.state/resources-key)
        a   (rf.resources.state/key-id k-a)
        b   (rf.resources.state/key-id k-b)]
    (is (= {a {:owner "a"} b {:owner "b"}}
           (into {} (map (fn [[k v]] [k (:data v)])) (:entries sub))))
    (is (= {[:article "x"] #{a b}} (:tag-index sub)))
    (is (= {[:route :r "nav-a"] #{a} [:route :r "nav-b"] #{b}} (:owner-index sub)))))

;; ===========================================================================
;; 4. End-to-end through the :rf/hydrate reconcile hook
;; ===========================================================================

(deftest hydrate-event-reconciles-resource-slice
  ;; The :rf/hydrate handler runs the resources reconcile through the
  ;; :resources/hydrate-runtime-db late-bind hook.
  (reg! :article/by-slug)
  (let [route-owner [:route :route/article "nav-1"]
        e (entry {:resource-id :article/by-slug :data {:t "x"}
                  :loaded-at 1000 :stale-at 9.0e15
                  :tags #{[:article "x"]}
                  :current-work [:rf.work/resource gkey 1]
                  :owners #{[:ssr "req-1" "nav-1"] route-owner}})]
    (rf/dispatch-sync [:rf/hydrate {:rf/frame-id    :rf/default
                                    :rf/app-db      {}
                                    :rf/runtime-db  (runtime-db-with {gkey e})}])
    (let [rdb       (:rf.db/runtime (rf/frame-state-value :rf/default))
          installed (get-in rdb [rf.resources.state/resources-key :entries (rf.resources.state/key-id gkey)])]
      (is (= [{:t "x"} nil #{route-owner}]
             [(:data installed) (:current-work installed) (:active-owners installed)])
          "data preserved; current-work cleared and the SSR owner orphaned")
      (is (= {[:article "x"] #{(rf.resources.state/key-id gkey)}}
             (get-in rdb [rf.resources.state/resources-key :tag-index]))
          "tag-index recomputed from entries"))))

(ns re-frame.resources-trace-keyid-egress-cljs-test
  "A `key-id` MUST NEVER reach a resource-family trace tag (rf2-5o52l).

  ## The leak this suite pins

  A `state/key-id` is `re-frame.identity/canonical-bytes` of the scoped key —
  CEDN-1, a **reversible plaintext encoding, not a digest**. `encode-string`
  emits `(str \"s:\" (pr-str s))`, so the key-id for
  `[:rf.scope/global :secret/article {:auth-token \"topsecret-PII\"}]` is
  literally

    v[k::rf.scope/global k::secret/article m{k::auth-token s:\"topsecret-PII\"}]

  `:rf.resource/owner-released` emitted `:released` as the raw owner-index
  members, which ARE key-ids, so a `:sensitive?` owner's resolved scope and
  canonical params egressed off-box **in the clear** — inside a string that
  looks opaque. `deftest key-id-is-reversible-plaintext-not-a-digest` below is
  the standing statement of that fact; it is the whole reason the emit sites
  must carry scoped keys.

  ## Why no projector rule could have caught it

  rf2-wd9im made the off-box trace-egress default (`re-frame.resources.trace-
  egress/project-unknown-slot-value`) read value SHAPE rather than slot name,
  which closes every scoped key and every map payload at any depth — including
  the key EMBEDDED at position 1 of a `:rf.work/resource` work-id. To that walk
  a key-id is a STRING, correctly a scalar: tokenizing strings wholesale would
  destroy `:rf.frame/id` / `:cause` / short-id attribution across the whole
  family. The payload was hidden INSIDE an encoded scalar — a class a shape read
  cannot and should not try to detect, and one no slot-name roster reaches
  either. The only layer that can fix it is the EMIT SITE, by carrying the value
  the projector is built to classify.

  `off-box-released-and-aborted-keys-agree-digest-for-digest` shows the leak's
  signature directly: BEFORE the fix, one `:rf.resource/owner-released` row
  carried the SAME resource identity twice — tokenized under `:aborted` (the
  work-id's embedded scoped key, covered by the shape walk) and verbatim under
  `:released` (the key-id string) — so the row was even stamped
  `:sensitive? true` while the secret rode raw beside the stamp.

  ## The sibling emit sites

  `ssr.cljc` reconciles `:entries` with `reduce-kv`, so its fold key is a
  key-id too, and it fed FIVE more trace tags of the same class: the hydrate
  `:rf.resource/hydrated` `:orphaned-owners` + `:rf.resource/hydrate-clock-skew`
  `:resource/key`, and the restore `:rf.resource/restored` `:orphaned-owners` +
  `:rf.resource/owner-released` `:resource/key` + `:rf.resource/restore-clock-
  skew` `:resource/key`. The two `:resource/key` rows are the sharper case: that
  slot is NAMED in the projector's single-scoped-key vocabulary, so a key-id
  there defeated an arm written specifically to redact it. Spec 009's
  `:rf.resource/*-clock-skew` rows already document `:resource/key`, which in
  this family means the scoped-key vector, so those two were spec drift as well.

  ## No over-redaction

  Every off-box assertion here is paired with a PLAIN (non-`:sensitive?`) owner
  in the same row: `off-box-plain-owners-released-key-rides-verbatim` and
  `plain-owners-key-is-never-tokenized` pin that a plain owner's scope + params
  still ride verbatim, so redacting-everything would fail this suite as loudly
  as leaking does.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex.
  The browser / MCP off-box channel is where this matters most, so the guarantee
  is asserted on BOTH hosts."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.fx :as fx]
   [re-frame.identity :as identity]
   ;; load-bearing side-effecting require: registers the :rf.resource/* events
   ;; + subs this suite dispatches.
   [re-frame.resources]
   [re-frame.resources.ssr :as r-ssr]
   [re-frame.resources.state :as state]
   [re-frame.resources.test-support]
   [re-frame.resources.trace-egress :as trace-egress]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.interop :as interop]
   [re-frame.test-support :as core-test-support]
   [re-frame.trace.tooling :as trace-tooling]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(def ^:private secret "topsecret-PII")

;; ---- fixture --------------------------------------------------------------

(defn- init!
  "One `:sensitive?` owner (scope + params tokenize off-box) and one PLAIN
  owner (must ride verbatim — the over-redaction control), plus a no-op
  managed-HTTP fx so `ensure` writes its `:loading` entry without fetching."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "key-id trace-egress suite default app frame."})
  (fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource :secret/article
    {:scope         :rf.scope/global
     :sensitive?    true
     :params-schema [:map [:auth-token :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/secret"}}))
  (rf/reg-resource :plain/article
    {:scope         :rf.scope/global
     :params-schema [:map [:slug :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/public"}}))
  ;; a `:sensitive?` owner whose params admit a SEQUENTIAL value, so §6 can
  ;; build two entries whose scoped keys are Clojure-`=` and whose CEDN
  ;; key-ids are not (rf2-wgutc2 — collection kind decides resource identity).
  (rf/reg-resource :secret/seq
    {:scope         :rf.scope/global
     :sensitive?    true
     :params-schema [:map [:xs [:sequential :string]]]}
    (fn [_p _ctx] {:request {:method :get :url "/seq"}})))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(def ^:private secret-key
  (state/scoped-resource-key :rf.scope/global :secret/article {:auth-token secret}))

(def ^:private plain-key
  (state/scoped-resource-key :rf.scope/global :plain/article {:slug "public-post"}))

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- redacted-token? [c] (and (map? c) (contains? c :rf/redacted)))

(defn- leaks-secret?
  "Whether the raw secret survives ANYWHERE in `v` — the plaintext test a
  reversible key-id fails and an opaque token passes."
  [v]
  (str/includes? (pr-str v) secret))

(defn- leaks-cedn-token?
  "Whether a CEDN-1 encoded token survives anywhere in `v`. Broader than
  `leaks-secret?`: it catches ANY key-id, not only one carrying this suite's
  secret, so a future emit site that reintroduces the class fails here even
  with innocuous params."
  [v]
  (boolean (re-find #"v\[k:" (pr-str v))))

(defn- capture-op!
  "Run `body-fn` with a trace listener installed; return every trace event
  whose `:operation` is `op`, in capture order."
  [op body-fn]
  (let [seen (atom [])
        k    ::keyid-egress-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= op (:operation ev)) (swap! seen conj ev))))
    (try (body-fn)
         (finally (trace-tooling/unregister-listener! k)))
    @seen))

(defn- project
  "Project `tags` for OFF-BOX egress exactly as the epoch tool-pair does —
  through the late-bound `:resources/project-resource-trace-egress` hook's
  body, against the row's own `:rf.frame/id`."
  [tags]
  (trace-egress/project-resource-trace-egress tags (:rf.frame/id tags)))

(defn- release-owner-row
  "Drive the PUBLIC path: `ensure` the sensitive resource AND the plain
  resource under one shared owner, then release that owner. Returns the single
  `:rf.resource/owner-released` row's tags."
  []
  (let [owner [:app :reader 1]
        rows  (capture-op!
                :rf.resource/owner-released
                (fn []
                  (rf/dispatch-sync [:rf.resource/ensure
                                     {:resource :secret/article
                                      :params   {:auth-token secret}
                                      :owner    owner}])
                  (rf/dispatch-sync [:rf.resource/ensure
                                     {:resource :plain/article
                                      :params   {:slug "public-post"}
                                      :owner    owner}])
                  (rf/dispatch-sync [:rf.resource/release-owner {:owner owner}])))]
    (is (= 1 (count rows)) "exactly one owner-released row for the released owner")
    (:tags (first rows))))

(defn- released-member
  "The `:released` member naming `resource-id`, from a projected row."
  [tags resource-id]
  (first (filter #(and (vector? %) (= resource-id (second %))) (:released tags))))

;; ===========================================================================
;; 1. THE PREMISE. A key-id is a reversible plaintext encoding, not a digest.
;;    This is the standing fact the emit-site rule rests on; it holds
;;    independently of any fix and must keep holding, because the day a key-id
;;    becomes a digest is the day this whole suite's reasoning changes.
;; ===========================================================================

(deftest key-id-is-reversible-plaintext-not-a-digest
  (testing "rf2-5o52l — `state/key-id` is `identity/canonical-bytes`, CEDN-1: a
            REVERSIBLE PLAINTEXT encoding. The key-id for a scoped key whose
            params carry a secret CONTAINS that secret verbatim, wrapped in the
            `s:\"…\"` string token `encode-string` emits. This is why a key-id
            may never be copied into a trace tag: nothing downstream can undo
            it, and the off-box projector correctly sees only a string"
    (let [k-id (state/key-id secret-key)]
      (is (string? k-id) "a key-id is a plain string — a scalar to any shape walk")
      (is (= k-id (identity/canonical-bytes secret-key))
          "key-id IS canonical-bytes — no hashing step exists")
      (is (str/includes? k-id secret)
          "the raw secret survives verbatim inside the key-id")
      (is (str/includes? k-id (str "s:" (pr-str secret)))
          "specifically as the CEDN-1 `s:\"…\"` string token")
      (is (str/includes? k-id "k::secret/article")
          "and the resource-id as a `k:` keyword token")
      (testing "a digest would not do this — the identity is recoverable, so a
                key-id is disclosure-equivalent to the scoped key itself"
        (is (str/includes? k-id (pr-str :rf.scope/global))
            "the resolved scope is recoverable too")))))

;; ===========================================================================
;; 2. THE EMIT SITE. `:released` names scoped keys, so the family's owner
;;    classification can reach them.
;; ===========================================================================

(deftest owner-released-names-released-entries-by-scoped-key
  (testing "rf2-5o52l — the ON-BOX `:rf.resource/owner-released` row names each
            released entry by its SCOPED KEY, never by its `key-id`. The
            owner-index members ARE key-ids, so the handler resolves each to its
            entry's `:resource/key` — the same move the sibling `now-owner-free`
            poll-cancel computation two lines above already makes"
    (let [tags (release-owner-row)]
      (is (= #{secret-key plain-key} (set (:released tags)))
          "both released entries are named by their canonical scoped-key vectors")
      (is (every? #(and (vector? %) (= 3 (count %)) (keyword? (second %)))
                  (:released tags))
          "every member is scoped-key SHAPED, so the shape-driven projector
           default recognises it wherever the slot vocabulary does not")
      (is (not-any? string? (:released tags))
          "no member is a CEDN-1 byte string"))))

;; ===========================================================================
;; 3. THE OFF-BOX GUARANTEE. This is the assertion that reds on the unfixed
;;    emit site: the secret arrives in the clear when `:released` carries
;;    key-ids, and is absent once it carries scoped keys.
;; ===========================================================================

(deftest off-box-owner-released-never-egresses-the-sensitive-owners-params
  (testing "rf2-5o52l — off-box, a `:sensitive?` owner's released key has its
            resolved scope + canonical params tokenized to opaque content-
            addressed `{:rf/redacted <digest>}`; the resource-id survives for
            attribution, and NO plaintext — neither the raw secret nor any
            CEDN-1 token — reaches the off-box channel"
    (let [projected (project (release-owner-row))
          [pscope rid pparams] (released-member projected :secret/article)]
      (is (= :secret/article rid) "the resource-id (position 1) survives")
      (is (redacted-token? pparams) "the sensitive params are tokenized")
      (is (redacted-token? pscope) "the resolved scope is tokenized")
      (is (true? (:sensitive? projected)) "the row is stamped :sensitive?")
      (testing "ACCEPTANCE — no plaintext survives anywhere in the projected row"
        (is (not (leaks-secret? projected))
            "the raw secret does not egress")
        (is (not (leaks-cedn-token? projected))
            "no CEDN-1 key-id egresses under ANY tag of the row")))))

(deftest off-box-plain-owners-released-key-rides-verbatim
  (testing "rf2-5o52l — the over-redaction control. A PLAIN (non-`:sensitive?`,
            non-`:large?`, registered) owner's released key rides VERBATIM in
            the SAME row that tokenizes the sensitive one, so the fix cannot be
            satisfied by redacting everything"
    (let [projected (project (release-owner-row))]
      (is (= plain-key (released-member projected :plain/article))
          "the plain owner's scope + params are unchanged off-box")
      (is (= {:slug "public-post"} (nth (released-member projected :plain/article) 2))
          "its params ride as the real map, not a token"))))

(deftest plain-owners-key-is-never-tokenized
  (testing "rf2-5o52l — the property form of the control, TRUE of the unfixed
            emit site too (a key-id string is not a redaction token either), so
            it holds in both directions and isolates over-redaction as a
            distinct failure from the leak"
    (let [projected (project (release-owner-row))]
      (is (not (leaks-cedn-token? (released-member projected :plain/article)))
          "no CEDN-1 token")
      (is (not-any? redacted-token?
                    (filter coll? (flatten [(released-member projected :plain/article)])))
          "and nothing about the plain owner's key was redacted"))))

(deftest off-box-released-and-aborted-keys-agree-digest-for-digest
  (testing "rf2-5o52l — the leak's signature, and the fidelity the fix buys.
            One `:rf.resource/owner-released` row carries the same resource
            identity TWICE: under `:aborted`, embedded at position 1 of the
            `[:rf.work/resource <scoped-key> <generation>]` work-id (which the
            rf2-wd9im shape walk redacts), and under `:released`. With `:released`
            carrying key-ids the two disagreed — one tokenized, one raw, on a row
            stamped `:sensitive? true`. Now both project through the SAME
            classification to the SAME digests, so a tool's per-key joins across
            the row survive redaction"
    (let [projected      (project (release-owner-row))
          released-key   (released-member projected :secret/article)
          aborted-key    (->> (:aborted projected)
                              (filter #(and (vector? %)
                                            (= :secret/article (second (nth % 1 nil)))))
                              first
                              (#(nth % 1 nil)))]
      (is (some? aborted-key) "the sensitive owner's work-id rode under :aborted")
      (is (= released-key aborted-key)
          "the :released key and the :aborted work-id's embedded key project
           IDENTICALLY — same digests for the same identity")
      (is (redacted-token? (nth aborted-key 2))
          "and both are genuinely tokenized (not merely equal-because-raw)"))))

;; ===========================================================================
;; 4. THE CLASS. No resource-family trace tag anywhere on the row carries a
;;    CEDN-1 key-id, whatever the slot is called.
;; ===========================================================================

(deftest no-owner-released-tag-carries-a-cedn-key-id
  (testing "rf2-5o52l — the statable invariant, over the WHOLE tag map rather
            than one slot: no value under ANY tag of an off-box
            `:rf.resource/owner-released` row is a CEDN-1 byte string. A key-id
            is opaque to the projector by construction, so the only durable
            guarantee is that none is ever emitted"
    (let [tags      (release-owner-row)
          projected (project tags)]
      (is (not (leaks-cedn-token? tags))
          "not even ON-BOX — the emit site simply never mints one into a tag")
      (is (not (leaks-cedn-token? projected))
          "and therefore none off-box")
      (is (not (leaks-secret? projected))
          "no plaintext secret under any tag"))))

;; ===========================================================================
;; 5. THE SIBLING EMIT SITES (ssr.cljc). Its `reduce-kv` over `:entries` folds
;;    on the key-id too, and it fed five more trace tags of this same class.
;; ===========================================================================

(defn- skewed-runtime-db
  "A runtime-db holding ONE `:sensitive?` entry whose absolute `:stale-at` is
  implausibly far ahead of the live clock (`clock-skew-ms` fires when
  `:loaded-at` itself is in the future — the server clock ran ahead), owned by
  `owner`. The vehicle for both the clock-skew row and the orphaned-owner row:
  `reconcile-entry-owners` drops an `[:ssr …]` owner always and, on restore with
  no live nav-token, every `[:route …]` owner."
  [owner]
  (let [now (interop/epoch-now-ms)]
    (-> (runtime-db)
        (assoc-in (state/entry-path secret-key)
                  {:resource/key   secret-key
                   :status         :loaded
                   :data           {:body "server-rendered"}
                   :active-owners  #{owner}
                   :current-work   nil
                   :generation     1
                   :loaded-at      (+ now 100000)
                   :stale-at       (+ now 200000)
                   :stale-after-ms 100000}))))

(deftest hydrate-rows-name-entries-by-scoped-key-not-key-id
  (testing "rf2-5o52l — the `:rf/hydrate` reconcile's `:rf.resource/hydrate-
            clock-skew` `:resource/key` and `:rf.resource/hydrated`
            `:orphaned-owners` name the entry by its SCOPED KEY. `:resource/key`
            is the sharper case: it is NAMED in the projector's single-scoped-key
            vocabulary, so a key-id there defeated an arm written specifically to
            redact it — and Spec 009 already documents this row's
            `:resource/key`, so it was spec drift too"
    (let [ssr-owner [:ssr "req-1" "nav-1"]
          rows      (capture-op!
                      :rf.resource/hydrate-clock-skew
                      (fn [] (r-ssr/hydrate-runtime-db (skewed-runtime-db ssr-owner)
                                                       :rf/default)))
          tags      (:tags (first rows))]
      (is (= 1 (count rows)) "one skew row for the one skewed entry")
      (is (= secret-key (:resource/key tags))
          "named by the scoped-key vector, not the CEDN-1 byte string")
      (let [[pscope rid pparams] (:resource/key (project tags))]
        (is (= :secret/article rid) "resource-id survives off-box")
        (is (redacted-token? pscope) "scope tokenized off-box")
        (is (redacted-token? pparams) "params tokenized off-box"))
      (is (not (leaks-secret? (project tags))) "no plaintext off-box")
      (is (not (leaks-cedn-token? (project tags))) "no CEDN-1 token off-box"))
    (testing "and the summary row's :orphaned-owners pairs, which carry the
              dropped SSR owner alongside its entry"
      (let [ssr-owner [:ssr "req-1" "nav-1"]
            rows      (capture-op!
                        :rf.resource/hydrated
                        (fn [] (r-ssr/hydrate-runtime-db (skewed-runtime-db ssr-owner)
                                                         :rf/default)))
            tags      (:tags (first rows))]
        (is (= [[secret-key ssr-owner]] (:orphaned-owners tags))
            "the orphaned SSR owner is paired with its entry's scoped key")
        (is (not (leaks-secret? (project tags))) "no plaintext off-box")
        (is (not (leaks-cedn-token? (project tags))) "no CEDN-1 token off-box")))))

(deftest restore-rows-name-entries-by-scoped-key-not-key-id
  (testing "rf2-5o52l — the restore-reconcile twin. Restore reconciles a LIVE
            (never wire-projected) snapshot, so its key-ids carry the RAW scope +
            params: `:rf.resource/restore-clock-skew` / `:rf.resource/owner-
            released` `:resource/key` and `:rf.resource/restored`
            `:orphaned-owners` all name the entry by its scoped key instead. With
            no live nav-token every `[:route …]` owner orphans (rf2-64bdnk), which
            is what produces the per-owner released row"
    (let [route-owner [:route :r/reader "tok-stale"]
          rdb         (skewed-runtime-db route-owner)]
      (testing "the :warning clock-skew row"
        (let [tags (:tags (first (capture-op!
                                   :rf.resource/restore-clock-skew
                                   #(r-ssr/reconcile-on-restore rdb :rf/default))))]
          (is (= secret-key (:resource/key tags)) "scoped key, not key-id")
          (is (redacted-token? (nth (:resource/key (project tags)) 2))
              "params tokenized off-box")
          (is (not (leaks-secret? (project tags))) "no plaintext off-box")))
      (testing "the per-owner :rf.resource/owner-released row for the stale-nav
                route orphan"
        (let [tags (:tags (first (capture-op!
                                   :rf.resource/owner-released
                                   #(r-ssr/reconcile-on-restore rdb :rf/default))))]
          (is (= secret-key (:resource/key tags)) "scoped key, not key-id")
          (is (= route-owner (:owner tags)) "names the orphaned route owner")
          (is (redacted-token? (nth (:resource/key (project tags)) 2))
              "params tokenized off-box")
          (is (not (leaks-secret? (project tags))) "no plaintext off-box")
          (is (not (leaks-cedn-token? (project tags))) "no CEDN-1 token off-box")))
      (testing "the :rf.resource/restored summary's :orphaned-owners"
        (let [tags (:tags (first (capture-op!
                                   :rf.resource/restored
                                   #(r-ssr/reconcile-on-restore rdb :rf/default))))]
          (is (= [[secret-key route-owner]] (:orphaned-owners tags))
              "the orphaned route owner is paired with its entry's scoped key")
          (is (not (leaks-secret? (project tags))) "no plaintext off-box")
          (is (not (leaks-cedn-token? (project tags)))
              "no CEDN-1 token off-box — :clock-skews included"))))))

;; ===========================================================================
;; 6. THE ACCUMULATOR'S OWN IDENTITY. Naming entries by scoped key was right;
;;    naming them by scoped key IN A MAP KEY POSITION was not.
;; ===========================================================================
;;
;; Resource identity is the CEDN `key-id`, and it is collection-KIND sensitive
;; (rf2-wgutc2): params `{:xs ["…"]}` and `{:xs '("…")}` are two DISTINCT
;; entries under two distinct key-ids. Their scoped keys, however, are `=` to
;; Clojure, which considers a list and a vector sequentially equal. So the
;; moment the reconcile's `:skews` accumulator became a scoped-key-KEYED map,
;; two live entries collapsed into one and one entry's clock-skew diagnostic
;; vanished — silently, on both the hydrate and the restore path (the audit of
;; PR #7018 measured exactly one row and one summary member where two entries
;; went in).
;;
;; `:orphaned` never had the defect: it was a SEQUENCE from the start, and a
;; sequence has no key to collide on. `:skews` is one now, for the same reason,
;; and the pair below is the proof — it fails if either accumulator ever
;; acquires a key again.

(def ^:private seq-resource-id :secret/seq)

(defn- seq-key
  "A scoped key for `:secret/seq` whose secret-bearing params carry `xs` — the
  collection whose KIND decides identity."
  [xs]
  (state/scoped-resource-key :rf.scope/global seq-resource-id {:xs xs}))

(defn- kind-colliding-runtime-db
  "A runtime-db holding TWO skewed `:sensitive?` entries whose scoped keys are
  Clojure-`=` and whose key-ids are not: one with VECTOR params, one with LIST
  params. Both owned by an `[:ssr …]` owner, so both also orphan."
  []
  (let [now (interop/epoch-now-ms)
        ent (fn [sk] {:resource/key   sk
                      :status         :loaded
                      :data           {:body "server-rendered"}
                      :active-owners  #{[:ssr "req-1" "nav-1"]}
                      :current-work   nil
                      :generation     1
                      :loaded-at      (+ now 100000)
                      :stale-at       (+ now 200000)
                      :stale-after-ms 100000})]
    (-> (runtime-db)
        (assoc-in (state/entry-path (seq-key [secret])) (ent (seq-key [secret])))
        (assoc-in (state/entry-path (seq-key (list secret)))
                  (ent (seq-key (list secret)))))))

(deftest reconcile-skew-accumulators-keep-kind-distinct-entries-distinct
  (testing "rf2-5o52l — the hydrate and restore reconciles must report ONE
            diagnostic per live ENTRY, and entry identity is the CEDN key-id,
            not Clojure equality of the scoped key"
    (let [vk  (seq-key [secret])
          lk  (seq-key (list secret))
          rdb (kind-colliding-runtime-db)]
      (testing "FIXTURE — the collision this pins is real and is not Clojure's
                idea of equality"
        (is (= vk lk)
            "the two scoped keys are `=` — a map keyed on them holds ONE entry")
        (is (not= (state/key-id vk) (state/key-id lk))
            "while their CEDN key-ids differ — they are two distinct resources")
        (is (= 2 (count (:entries (get rdb state/resources-key))))
            "and the runtime-db really holds both"))

      (doseq [[label reconcile! skew-op summary-op]
              [["hydrate" #(r-ssr/hydrate-runtime-db % :rf/default)
                :rf.resource/hydrate-clock-skew :rf.resource/hydrated]
               ["restore" #(r-ssr/reconcile-on-restore % :rf/default)
                :rf.resource/restore-clock-skew :rf.resource/restored]]]
        (testing label
          (is (= 2 (count (capture-op! skew-op #(reconcile! rdb))))
              "one per-entry clock-skew row per DISTINCT entry — a
               scoped-key-keyed accumulator emits one")
          (let [tags  (:tags (first (capture-op! summary-op #(reconcile! rdb))))
                skews (:clock-skews tags)]
            (is (= 2 (count skews))
                "and two summary members, paired `[<scoped-key> <skew-ms>]`
                 exactly as :orphaned-owners pairs beside them")
            (is (= 2 (count (:orphaned-owners tags)))
                "the sibling accumulator, which never had the defect, still
                 reports both — so this is not a change in what reconciles")
            (let [xs (map #(:xs (nth (first %) 2)) skews)]
              (is (= [1 1] [(count (filter vector? xs)) (count (filter seq? xs))])
                  "one member carries the VECTOR params and the other the LIST
                   params, so the surviving entry is not simply reported twice.
                   Compared by KIND and not by `=`, because `=` is precisely
                   what cannot tell these two apart — a `#{}` literal of the two
                   throws Duplicate key"))
            (testing "and both still egress correctly"
              (let [proj (:clock-skews (project tags))]
                (is (every? #(redacted-token? (nth (first %) 2)) proj)
                    "each member's params tokenize through its own owner")
                (is (every? #(= seq-resource-id (second (first %))) proj)
                    "each keeps its resource-id for attribution")
                (is (= 1 (count (set (map #(nth (first %) 2) proj))))
                    "and the two tokens now AGREE — this owner is `:sensitive?`,
                     and rf2-hzcv8 settled that a sensitive value gets no
                     content-derived token at all, so nothing survives
                     projection to tell two sensitive identities apart. That
                     does not weaken this ratchet: the accumulator's
                     non-collapse is proven by the params-KIND assertion above,
                     which reads the raw tags and never depended on the token")
                (is (not (leaks-secret? proj)) "no plaintext off-box")
                (is (not (leaks-cedn-token? proj)) "no CEDN-1 token off-box")))))))))

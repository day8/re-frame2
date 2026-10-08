(ns re-frame.resources-trace-keyid-egress-cljs-test
  "A `key-id` MUST NEVER reach a resource-family trace tag.

  A `rf.resources.state/key-id` is `re-frame.identity/canonical-bytes` of the
  scoped key: CEDN-1, a reversible plaintext encoding rather than a digest, so
  a `:sensitive?` owner's scope and params sit inside it in the clear. The
  off-box projector reads value shape, and to it a key-id is a string, a
  scalar it must not tokenize wholesale without destroying attribution across
  the family. So the only layer that can keep key-ids out is the emit site,
  which names entries by scoped key: the owner-released `:released` members
  (whose owner-index source holds key-ids) and the hydrate / restore rows that
  fold over `:entries` with `reduce-kv`. Every off-box check sits beside a
  plain owner riding verbatim in the same row, so redacting everything fails."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.identity :as rf.identity]
   ;; load-bearing side-effecting require: registers the :rf.resource/* events
   ;; + subs this suite dispatches.
   [re-frame.resources]
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.interop :as rf.interop]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(def ^:private secret "topsecret-PII")

(defn- init!
  "A `:sensitive?` owner, a plain owner (the over-redaction control), and a
  `:sensitive?` owner whose params admit a sequential value; a no-op transport
  lets `ensure` write its `:loading` entry without fetching."
  []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "key-id trace-egress suite default app frame."})
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource :secret/article
    {:scope         :rf.scope/global
     :sensitive?    true
     :params-schema [:map [:auth-token :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/secret"}}))
  (rf/reg-resource :plain/article
    {:scope         :rf.scope/global
     :params-schema [:map [:slug :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/public"}}))
  (rf/reg-resource :secret/seq
    {:scope         :rf.scope/global
     :sensitive?    true
     :params-schema [:map [:xs [:sequential :string]]]}
    (fn [_p _ctx] {:request {:method :get :url "/seq"}})))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(def ^:private secret-key
  (rf.resources.state/scoped-resource-key :rf.scope/global :secret/article {:auth-token secret}))

(def ^:private plain-key
  (rf.resources.state/scoped-resource-key :rf.scope/global :plain/article {:slug "public-post"}))

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))

(defn- redacted-token? [c] (and (map? c) (contains? c :rf/redacted)))

(defn- leaks-secret? [v]
  (str/includes? (pr-str v) secret))

(defn- leaks-cedn-token?
  "Whether ANY CEDN-1 encoded token survives in `v`, secret-bearing or not."
  [v]
  (boolean (re-find #"v\[k:" (pr-str v))))

(defn- clean?
  "Neither the secret nor any CEDN-1 token survives in `v`."
  [v]
  (not (or (leaks-secret? v) (leaks-cedn-token? v))))

(defn- capture-op!
  "Run `body-fn` and return every trace event whose `:operation` is `op`."
  [op body-fn]
  (let [seen (atom [])
        k    ::keyid-egress-recorder]
    (rf.trace.tooling/register-listener!
      k (fn [ev] (when (= op (:operation ev)) (swap! seen conj ev))))
    (try (body-fn)
         (finally (rf.trace.tooling/unregister-listener! k)))
    @seen))

(defn- project
  "Project `tags` for OFF-BOX egress exactly as the epoch tool-pair does."
  [tags]
  (rf.resources.trace-egress/project-resource-trace-egress tags (:rf.frame/id tags)))

(defn- release-owner-row
  "Ensure the sensitive and the plain resource under one owner, then release
  it. Returns the single `:rf.resource/owner-released` row's tags."
  []
  (let [owner [:app :reader 1]
        rows  (capture-op!
                :rf.resource/owner-released
                (fn []
                  (rf/dispatch-sync [:rf.resource/ensure
                                     {:resource :secret/article :params {:auth-token secret} :owner owner}])
                  (rf/dispatch-sync [:rf.resource/ensure
                                     {:resource :plain/article :params {:slug "public-post"} :owner owner}])
                  (rf/dispatch-sync [:rf.resource/release-owner {:owner owner}])))]
    (is (= 1 (count rows)) "exactly one owner-released row for the released owner")
    (:tags (first rows))))

(defn- released-member [tags resource-id]
  (first (filter #(and (vector? %) (= resource-id (second %))) (:released tags))))

;; ===========================================================================
;; the premise: a key-id is reversible plaintext, not a digest
;; ===========================================================================

(deftest key-id-is-reversible-plaintext-not-a-digest
  (let [k-id (rf.resources.state/key-id secret-key)]
    (is (= [(rf.identity/canonical-bytes secret-key) true true true]
           [k-id (str/includes? k-id (str "s:" (pr-str secret))) (str/includes? k-id "k::secret/article")
            (str/includes? k-id (pr-str :rf.scope/global))])
        "key-id IS canonical-bytes, and the secret, resource id and scope all sit in it verbatim")))

;; ===========================================================================
;; owner-released names scoped keys, and they project like any key
;; ===========================================================================

(deftest owner-released-names-released-entries-by-scoped-key
  (let [tags (release-owner-row)]
    (is (= [#{secret-key plain-key} false]
           [(set (:released tags)) (leaks-cedn-token? tags)])
        "each released entry is named by its scoped key, and no tag on the row carries a CEDN-1 key-id")))

(deftest off-box-owner-released-tokenizes-the-sensitive-key-and-keeps-the-plain-one
  (let [projected            (project (release-owner-row))
        [pscope rid pparams] (released-member projected :secret/article)]
    (is (= [:secret/article true true true true plain-key]
           [rid (redacted-token? pparams) (redacted-token? pscope) (:sensitive? projected)
            (clean? projected) (released-member projected :plain/article)])
        "the sensitive scope and params tokenize, the row is stamped and clean, and the plain key rides verbatim")))

(deftest off-box-released-and-aborted-keys-agree-digest-for-digest
  ;; the same identity rides twice on one row: under :released, and embedded
  ;; at position 1 of the :aborted work-id; both project to the same digests,
  ;; so a tool's per-key joins survive redaction
  (let [projected   (project (release-owner-row))
        aborted-key (->> (:aborted projected)
                         (filter vector?)
                         (map #(nth % 1 nil))
                         (filter #(= :secret/article (second %)))
                         first)]
    (is (= [(released-member projected :secret/article) true]
           [aborted-key (redacted-token? (nth aborted-key 2 nil))]))))

;; ===========================================================================
;; the ssr.cljc emit sites fold over :entries by key-id too
;; ===========================================================================

(defn- skewed-entry
  "A `:loaded` entry for `scoped-key` whose `:loaded-at` is in the future (the
  server clock ran ahead), owned by `owner`."
  [scoped-key owner]
  (let [now (rf.interop/epoch-now-ms)]
    {:resource/key   scoped-key
     :status         :loaded
     :data           {:body "server-rendered"}
     :active-owners  #{owner}
     :current-work   nil
     :generation     1
     :loaded-at      (+ now 100000)
     :stale-at       (+ now 200000)
     :stale-after-ms 100000}))

(defn- skewed-runtime-db
  "One skewed `:sensitive?` entry owned by `owner`: the vehicle for both the
  clock-skew row and the orphaned-owner row (an `[:ssr …]` owner always drops,
  and on restore with no live nav-token every `[:route …]` owner does)."
  [owner]
  (assoc-in (runtime-db) (rf.resources.state/entry-path secret-key) (skewed-entry secret-key owner)))

(deftest hydrate-rows-name-entries-by-scoped-key-not-key-id
  ;; :resource/key is named in the projector's single-scoped-key vocabulary,
  ;; so a key-id there would defeat the arm written to redact it
  (let [ssr-owner [:ssr "req-1" "nav-1"]
        hydrate   #(rf.resources.ssr/hydrate-runtime-db (skewed-runtime-db ssr-owner) :rf/default)
        skews     (capture-op! :rf.resource/hydrate-clock-skew hydrate)
        tags      (:tags (first skews))
        [pscope rid pparams] (:resource/key (project tags))
        summary   (:tags (first (capture-op! :rf.resource/hydrated hydrate)))]
    (is (= [1 secret-key :secret/article true true true]
           [(count skews) (:resource/key tags) rid (redacted-token? pscope) (redacted-token? pparams)
            (clean? (project tags))])
        "the clock-skew row names its entry by scoped key, which tokenizes off-box")
    (is (= [[[secret-key ssr-owner]] true]
           [(:orphaned-owners summary) (clean? (project summary))])
        "the summary pairs the orphaned SSR owner with its entry's scoped key")))

(deftest restore-rows-name-entries-by-scoped-key-not-key-id
  ;; restore reconciles a live snapshot, so its key-ids carry the RAW scope
  ;; and params
  (let [route-owner [:route :r/reader "tok-stale"]
        rdb         (skewed-runtime-db route-owner)
        row         #(:tags (first (capture-op! % (fn [] (rf.resources.ssr/reconcile-on-restore rdb :rf/default)))))
        skew        (row :rf.resource/restore-clock-skew)
        released    (row :rf.resource/owner-released)
        restored    (row :rf.resource/restored)]
    (is (= [secret-key true true]
           [(:resource/key skew) (redacted-token? (nth (:resource/key (project skew)) 2))
            (not (leaks-secret? (project skew)))])
        "the clock-skew row")
    (is (= [secret-key route-owner true true]
           [(:resource/key released) (:owner released)
            (redacted-token? (nth (:resource/key (project released)) 2)) (clean? (project released))])
        "the per-owner owner-released row for the stale-nav route orphan")
    (is (= [[[secret-key route-owner]] true]
           [(:orphaned-owners restored) (clean? (project restored))])
        "the restored summary's :orphaned-owners, :clock-skews included in the clean check")))

;; Resource identity is the CEDN key-id, which is collection-KIND sensitive:
;; params `{:xs ["…"]}` and `{:xs '("…")}` are two entries, while their scoped
;; keys are Clojure-`=`. So a scoped-key-KEYED accumulator would collapse two
;; live entries into one and drop a clock-skew diagnostic; `:orphaned` and
;; `:skews` are sequences, which have no key to collide on.

(defn- seq-key [xs]
  (rf.resources.state/scoped-resource-key :rf.scope/global :secret/seq {:xs xs}))

(deftest reconcile-skew-accumulators-keep-kind-distinct-entries-distinct
  (let [vk  (seq-key [secret])
        lk  (seq-key (list secret))
        rdb (-> (runtime-db)
                (assoc-in (rf.resources.state/entry-path vk) (skewed-entry vk [:ssr "req-1" "nav-1"]))
                (assoc-in (rf.resources.state/entry-path lk) (skewed-entry lk [:ssr "req-1" "nav-1"])))]
    (is (= [true false 2]
           [(= vk lk) (= (rf.resources.state/key-id vk) (rf.resources.state/key-id lk))
            (count (:entries (get rdb rf.resources.state/resources-key)))])
        "FIXTURE — two entries whose scoped keys are = and whose key-ids are not")
    (doseq [[label reconcile! skew-op summary-op]
            [["hydrate" #(rf.resources.ssr/hydrate-runtime-db % :rf/default)
              :rf.resource/hydrate-clock-skew :rf.resource/hydrated]
             ["restore" #(rf.resources.ssr/reconcile-on-restore % :rf/default)
              :rf.resource/restore-clock-skew :rf.resource/restored]]]
      (testing label
        (let [tags  (:tags (first (capture-op! summary-op #(reconcile! rdb))))
              skews (:clock-skews tags)
              xs    (map #(:xs (nth (first %) 2)) skews)
              proj  (:clock-skews (project tags))]
          ;; compared by KIND, not =, because = is what cannot tell them apart
          (is (= [2 2 2 [1 1]]
                 [(count (capture-op! skew-op #(reconcile! rdb))) (count skews) (count (:orphaned-owners tags))
                  [(count (filter vector? xs)) (count (filter seq? xs))]])
              "one clock-skew row and one summary member per distinct entry, one vector-keyed, one list-keyed")
          ;; a sensitive value gets no content-derived token, so the two
          ;; members' tokens agree; the non-collapse is proven on the raw tags
          (is (= [true true 1 true]
                 [(every? #(redacted-token? (nth (first %) 2)) proj)
                  (every? #(= :secret/seq (second (first %))) proj)
                  (count (set (map #(nth (first %) 2) proj))) (clean? proj)])
              "both members still tokenize, through their own owner, and keep their resource id"))))))

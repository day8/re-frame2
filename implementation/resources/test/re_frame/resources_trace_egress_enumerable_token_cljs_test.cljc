(ns re-frame.resources-trace-egress-enumerable-token-cljs-test
  "rf2-hzcv8 — the OFF-BOX resource trace egress must not mint an ENUMERABLE
  token for sensitive content.

  ## The leak this suite pins

  `ssr/redact-value` emitted `fnv-1a-32` of `(pr-str value)` for every
  classification alike, and every off-box carrier in
  `re-frame.resources.trace-egress` called it: a sensitive resource's scope and
  params inside `:resource/key`, a free `[tier {identity}]` scope tag, the
  load-more pagination cursor, an HTTP failure envelope, and any unrecognised
  map under a resource-family tag.

  FNV-1a-32 is a 32-bit non-cryptographic hash. A tenant slug, an account id or
  a page cursor lives in a candidate space small enough to WALK, so the original
  is recoverable from the token by enumeration — and testable against it, which
  is worse, because an attacker who merely wants to confirm a guess needs only
  one hash. rf2-4bjep proved exactly this and responded by withholding coarse
  rows from SSR hydration, but that closed only the hydration boundary. The same
  token kept leaving the process through the trace path, which is the boundary
  an off-box sink sits on, while Spec 009 and the source comments called the
  result \"opaque content-addressed\". Spec 015's sensitive-marker contract is
  that `:rf/redacted` carries NO information about the underlying content; a
  token you can test candidates against does not meet it, and \"we hashed it\"
  was a false assurance rather than a weak guarantee.

  ## What replaced it

  The token's payload is now chosen by CLASSIFICATION, and the default is the
  safe one:

    :redact (SENSITIVE, and every caller with no disposition to hand)
      → a CONTENT-FREE shape token: a closed-vocabulary `:type` tag plus an
        integer `:count`. There is no candidate space to enumerate against it,
        because every value of the same shape produces the same token.
    :omit (LARGE, not sensitive — a SIZE claim, not a privacy claim)
      → a digest, which that classification permits, now over
        `identity/canonical-bytes` rather than `pr-str`.

  ## The suite is TWO-SIDED

  Over-redaction fails here as loudly as the leak did. §3 pins a PLAIN owner's
  cursor and key riding byte-identical, and §4 pins that a `:large?` owner still
  gets distinct keys — so \"redact everything\" is not a passing answer, and the
  classification split is a real split rather than a blanket removal.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex.
  The browser / MCP off-box channel is where this matters most, so the
  guarantee is asserted on BOTH hosts — and the cross-host determinism clause
  is a per-host assertion by nature."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   ;; load-bearing side-effecting require: the façade registers the
   ;; :rf.resource/* events + subs and publishes the trace-egress hooks.
   [re-frame.resources]
   [re-frame.resources.state :as state]
   [re-frame.resources.trace-egress :as trace-egress]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

;; Two secrets of the SAME shape. That is the whole design of this fixture: a
;; content-free token must map them to ONE value (nothing survives to tell them
;; apart), and an enumerable token must map them to two.
(def ^:private tenant-a "tenant-alpha-01")
(def ^:private tenant-b "tenant-brav0-02")
(def ^:private cursor-a "cursor-alpha-01")
(def ^:private cursor-b "cursor-brav0-02")

(defn- init! []
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "rf2-hzcv8 enumerable-token trace-egress suite frame."})
  (rf/reg-resource :sealed/feed
    {:scope         :rf.scope/global
     :sensitive?    true
     :params-schema [:map [:tenant :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/sealed"}}))
  (rf/reg-resource :bulky/feed
    {:scope         :rf.scope/global
     :large?        true
     :params-schema [:map [:tenant :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/bulky"}}))
  (rf/reg-resource :plain/feed
    {:scope         :rf.scope/global
     :params-schema [:map [:tenant :string]]}
    (fn [_p _ctx] {:request {:method :get :url "/plain"}})))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    {:adapter #?(:clj plain-atom/adapter :cljs reagent-adapter/adapter)
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- project-row
  "Project `tags` for OFF-BOX egress exactly as the epoch tool-pair does."
  [tags]
  (trace-egress/project-resource-trace-egress tags (:rf.frame/id tags)))

(defn- key-for [resource-id tenant]
  (state/scoped-resource-key :rf.scope/global resource-id {:tenant tenant}))

(defn- leaks? [secret v]
  (str/includes? (pr-str v) secret))

(defn- params-token
  "The projected params component (index 2) of a one-key row's `:resource/key`."
  [scoped-key]
  (nth (:resource/key (project-row {:rf.frame/id :rf/default
                                    :resource/key scoped-key}))
       2))

;; ===========================================================================
;; 1. A SENSITIVE scoped key — the primary carrier.
;; ===========================================================================

(deftest sensitive-scoped-key-token-is-not-enumerable
  (testing "rf2-hzcv8 — a `:sensitive?` owner's params tokenize off-box, and the
            token carries NOTHING derived from the content: two distinct tenants
            of the same shape produce ONE token, so a candidate space cannot be
            walked or tested against it"
    (let [ka (key-for :sealed/feed tenant-a)
          kb (key-for :sealed/feed tenant-b)
          ta (params-token ka)
          tb (params-token kb)]
      (is (leaks? tenant-a ka) "premise: the RAW key carries the tenant in the clear")
      (is (not (leaks? tenant-a ta)) "no plaintext off-box")
      (is (not (leaks? tenant-b tb)) "no plaintext off-box")
      (is (= ta tb)
          "THE PROPERTY: two distinct sensitive tenants are indistinguishable
           after projection — an enumerable token would separate them here")
      (is (= {:rf/redacted {:type :map :count 1}} ta)
          "and the token is a closed-vocabulary tag plus an integer — content-free
           by construction, not by strength")))

  (testing "the resource-id still rides at position 1 — it is never a
            classification carrier, and attribution must survive"
    (is (= :sealed/feed
           (nth (:resource/key (project-row {:rf.frame/id :rf/default
                                             :resource/key (key-for :sealed/feed tenant-a)}))
                1)))))

;; ===========================================================================
;; 2. The FREE `:scope` tag and the pagination CURSOR — the two carriers the
;;    bead named as still leaking after rf2-4bjep.
;; ===========================================================================

(deftest free-scope-tag-identity-map-token-is-not-enumerable
  (testing "rf2-hzcv8 — a free `[tier {identity}]` scope tag (an invalidation
            sweep carries no `:resource/key` to read an owner from) keeps its
            TIER keyword so a tool still reads \"session scope\", and tokenizes
            the identity MAP. That token is content-free too: the free tag has
            no owner claim that could permit a digest"
    (let [row-a (project-row {:rf.frame/id :rf/default
                              :scope [:rf.scope/session {:tenant-id tenant-a}]})
          row-b (project-row {:rf.frame/id :rf/default
                              :scope [:rf.scope/session {:tenant-id tenant-b}]})]
      (is (= :rf.scope/session (first (:scope row-a)))
          "the tier keyword rides verbatim — attribution survives")
      (is (not (leaks? tenant-a (:scope row-a))) "no plaintext off-box")
      (is (= (:scope row-a) (:scope row-b))
          "THE PROPERTY: two distinct session tenants are indistinguishable")
      (is (= {:rf/redacted {:type :map :count 1}} (second (:scope row-a))))))

  (testing "a scalar scope still rides verbatim — no over-redaction"
    (is (= :rf.scope/global
           (:scope (project-row {:rf.frame/id :rf/default :scope :rf.scope/global}))))))

(deftest pagination-cursor-token-is-not-enumerable
  (testing "rf2-hzcv8 — the load-more cursor (`:page-param` / `:next-page-param`)
            is an app-derived free tag that can carry a record id. It tokenizes
            when the row's OWNER redacts, and that token is content-free"
    (let [row-a (project-row {:rf.frame/id :rf/default
                              :resource/key (key-for :sealed/feed tenant-a)
                              :page-param cursor-a})
          row-b (project-row {:rf.frame/id :rf/default
                              :resource/key (key-for :sealed/feed tenant-a)
                              :page-param cursor-b})]
      (is (not (leaks? cursor-a (:page-param row-a))) "no plaintext off-box")
      (is (= (:page-param row-a) (:page-param row-b))
          "THE PROPERTY: two distinct cursors are indistinguishable — an
           enumerable token over a record id is the leak this bead closes")
      (is (= {:rf/redacted {:type :string :count (count cursor-a)}}
             (:page-param row-a))
          "a tag and an integer; the LENGTH is deliberately kept — an integer
           cannot carry a fragment of a cursor, and size is the diagnosis")))

  (testing "`:next-page-param` takes the same path"
    (let [row (project-row {:rf.frame/id :rf/default
                            :resource/key (key-for :sealed/feed tenant-a)
                            :next-page-param cursor-a})]
      (is (not (leaks? cursor-a (:next-page-param row))))
      (is (contains? (:next-page-param row) :rf/redacted)))))

;; ===========================================================================
;; 3. NEGATIVE CONTROL — a PLAIN owner is untouched.
;; ===========================================================================

(deftest plain-owner-rides-verbatim
  (testing "a `:serialize` owner declaring nothing keeps its key BYTE-IDENTICAL
            and its cursor readable — over-redaction fails this suite as loudly
            as the leak did"
    (let [k   (key-for :plain/feed tenant-a)
          row (project-row {:rf.frame/id :rf/default
                            :resource/key k
                            :page-param cursor-a})]
      (is (= k (:resource/key row)) "the plain key rides byte-identical")
      (is (= cursor-a (:page-param row)) "and its cursor rides verbatim"))))

;; ===========================================================================
;; 4. NEGATIVE CONTROL — a LARGE-only owner keeps a digest.
;; ===========================================================================

(deftest large-owner-keeps-a-distinct-content-derived-token
  (testing "rf2-hzcv8 — `:large?` is a SIZE claim, not a privacy claim, so a
            content-derived token is PERMITTED there and is kept. Two distinct
            large tenants still project to DISTINCT tokens, which is what makes
            this a classification split rather than a blanket removal"
    (let [ta (params-token (key-for :bulky/feed tenant-a))
          tb (params-token (key-for :bulky/feed tenant-b))]
      (is (not (leaks? tenant-a ta)) "still no plaintext off-box")
      (is (not= ta tb) "distinct large identities keep distinct tokens")
      (is (string? (:rf/redacted ta)) "and the payload is a digest, not a shape")))

  (testing "and that digest is a function of the CANONICAL value, so a map
            spelling difference cannot change it (the rf2-hzcv8 witness)"
    (let [k1 (state/scoped-resource-key :rf.scope/global :bulky/feed
                                        (array-map :tenant tenant-a :page 2))
          k2 (state/scoped-resource-key :rf.scope/global :bulky/feed
                                        (array-map :page 2 :tenant tenant-a))]
      (is (= (params-token k1) (params-token k2))
          "equal canonical values with different construction order produce ONE
           token — on this host, and this assertion runs on both"))))

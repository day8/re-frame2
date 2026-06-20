(ns re-frame.resources-classification-cljs-test
  "EP-0015 §6 resource / mutation OWNER-classification reconciliation
  (rf2-5pld34, bead-plan item 5). Pins the contract that resource durable
  state projects through the merged frame-owned classification +
  `project-egress` (Spec 015 §Resource and mutation durable classification /
  Spec 016 §SSR and hydration):

    1. WHOLE-ENTRY coarse `:sensitive?` / `:large?` (the degenerate root-prop
       case, EP-0015 issue 11) → redact / omit; sensitive wins over large;
    2. PER-SLOT `:data-schema` / `:params-schema` `:sensitive?` / `:large?`
       props are the canonical fine-grained owner surface (the EP-0005
       mechanism) — extracted via the SHARED schema walker, no resource-local
       vocabulary;
    3. a `:serialize` entry's data projects through frame classification
       (`project-egress`) — the frame-owned source of truth the prior
       resource-local redaction now defers to; a frame-classified sub-path is
       redacted even on a coarse-`:serialize` resource (defense in depth);
    4. the load-bearing Spec 016 rules are preserved (only the durable
       `:entries` slice rides; metadata-only redacted/omitted hydration;
       scoped-key privacy).

  CLJC so the JVM run (`clojure -M:test`) — the load-bearing gate — exercises
  it; the schemas artefact is a test-only dep, so the shared walker hooks are
  bound here."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.late-bind :as late-bind]
   [re-frame.privacy :as privacy]
   ;; the unit under test + the SSR projection that consumes it.
   [re-frame.resources.classification :as classification]
   [re-frame.resources.ssr :as ssr]
   [re-frame.resources.state :as state]
   ;; load-bearing side-effecting requires: the façade registers the
   ;; :resource registrar kind; schemas binds the shared walker hooks.
   [re-frame.resources]
   [re-frame.schemas]
   [re-frame.test-support :as core-test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (core-test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; ---- helpers --------------------------------------------------------------

(defn- reg!
  "Register a resource id with optional spec overrides (defaults to a
  global-scope slug resource)."
  ([id] (reg! id {}))
  ([id overrides]
   (rf/clear-resource id)
   (let [spec (merge {:scope         :rf.scope/global
                      :params-schema [:map [:slug :string]]
                      :request       (fn [{:keys [slug]} _] {:request {:method :get :url (str "/a/" slug)}})}
                     overrides)]
     (rf/reg-resource id (dissoc spec :request) (:request spec)))))

(defn- entry
  [{:keys [resource-id status data loaded-at stale-at]
    :or   {status :loaded loaded-at 1000 stale-at 9.0e15}}]
  (merge (state/empty-entry resource-id)
         {:status status :data data :loaded-at loaded-at :stale-at stale-at}))

;; rf2-9e0tyq — the runtime keys `:entries` on the byte `key-id` and stamps
;; each entry's `:resource/key`; this helper re-keys the natural
;; `{scoped-key-vector entry}` form callers write into the runtime's shape.
(defn- runtime-db-with [entries]
  {state/resources-key {:entries (into {}
                                       (map (fn [[sk e]]
                                              [(state/key-id sk) (assoc e :resource/key sk)]))
                                       entries)
                        :tag-index {} :owner-index {}}})

;; The projection map is byte-keyed; the projected SCOPED KEY (verbatim or
;; redacted) rides as the wire entry's `:resource/key`. Return it as the
;; first element so the privacy/distinctness assertions inspect it.
(defn- only-wire-entry [proj]
  (let [we (val (first (get-in proj [state/resources-key :entries])))]
    [(:resource/key we) we]))

;; ===========================================================================
;; 1. whole-entry-disposition — the coarse root-prop owner classification
;; ===========================================================================

(deftest whole-entry-disposition-reads-coarse-root-prop
  (testing "EP-0015 issue 11: the coarse whole-entry :sensitive? / :large?
            claims are the degenerate ROOT-PROP classification unit"
    (is (= :serialize (classification/whole-entry-disposition {})))
    (is (= :serialize (classification/whole-entry-disposition nil))
        "an unregistered / nil spec carries no coarse claim → serialize")
    (is (= :redact (classification/whole-entry-disposition {:sensitive? true})))
    (is (= :omit   (classification/whole-entry-disposition {:large? true})))
    (testing "sensitive wins over large (the conservative shape)"
      (is (= :redact (classification/whole-entry-disposition
                       {:sensitive? true :large? true}))))))

;; ===========================================================================
;; 2. data-schema-marks — the canonical FINE-GRAINED owner surface (issue 11)
;; ===========================================================================

(deftest data-schema-marks-extract-per-slot-props
  (testing "per-slot :sensitive? / :large? props on :data-schema are extracted
            via the SHARED walker, rooted at the data root — the canonical
            fine-grained owner surface (no resource-local vocabulary)"
    (let [spec {:data-schema [:map
                              [:token {:sensitive? true} :string]
                              [:blob  {:large? true} :string]
                              [:title :string]]}
          {:keys [sensitive large]} (classification/data-schema-marks spec)]
      (is (contains? sensitive [:token]) "the :sensitive? slot path is extracted")
      (is (contains? large [:blob]) "the :large? slot path is extracted")
      (is (not (contains? sensitive [:title])) "a plain slot is not classified")
      (is (true? (classification/data-schema-classifies? spec))
          "data-schema-classifies? is true when any slot is marked"))))

(deftest data-schema-marks-empty-without-schema-or-marks
  (testing "no :data-schema, or a schema with no marks → empty maps, classifies? false"
    (is (= {:sensitive {} :large {}} (classification/data-schema-marks {})))
    (let [plain {:data-schema [:map [:title :string]]}]
      (is (= {} (:sensitive (classification/data-schema-marks plain))))
      (is (= {} (:large (classification/data-schema-marks plain))))
      (is (false? (classification/data-schema-classifies? plain))))))

(deftest data-schema-keyword-ref-is-opaque-leaf
  (testing "a keyword registry-ref :data-schema is an opaque leaf (the shared
            walker's documented limitation, NOT a resource-specific quirk) —
            no marks extracted, classifies? false"
    (let [spec {:data-schema :app/article}]
      (is (false? (classification/data-schema-classifies? spec))))))

;; ===========================================================================
;; 2b. params-schema-marks — the CO-EQUAL fine-grained owner surface (issue 11)
;; ===========================================================================
;;
;; EP-0015 issue 11 names :params-schema co-equal with :data-schema as a
;; canonical fine-grained owner surface (Spec 015 / Spec 016 clause 4 "params,
;; scopes, and data carry the same classification"). The audit (rf2-edbj53)
;; flagged only :data-schema was exercised; this section pins the :params-schema
;; arm symmetrically (rf2-t55hxg.5).

(deftest params-schema-marks-extract-per-slot-props
  (testing "per-slot :sensitive? / :large? props on :params-schema are extracted
            via the SAME shared walker as :data-schema — the co-equal fine-grained
            owner surface, rooted at the params root"
    (let [spec {:params-schema [:map
                                 [:account-id {:sensitive? true} :string]
                                 [:cursor     {:large? true} :string]
                                 [:page :int]]}
          {:keys [sensitive large]} (classification/params-schema-marks spec)]
      (is (contains? sensitive [:account-id]) "the :sensitive? params slot path is extracted")
      (is (contains? large [:cursor]) "the :large? params slot path is extracted")
      (is (not (contains? sensitive [:page])) "a plain params slot is not classified")
      (is (true? (classification/params-schema-classifies? spec))
          "params-schema-classifies? is true when any params slot is marked"))))

(deftest params-schema-marks-empty-without-schema-or-marks
  (testing "no :params-schema, or a schema with no marks → empty maps, classifies? false"
    (is (= {:sensitive {} :large {}} (classification/params-schema-marks {})))
    (let [plain {:params-schema [:map [:slug :string]]}]
      (is (= {} (:sensitive (classification/params-schema-marks plain))))
      (is (= {} (:large (classification/params-schema-marks plain))))
      (is (false? (classification/params-schema-classifies? plain))))))

(deftest project-params-redacts-sensitive-and-elides-large
  (testing "EP-0015 issue 11: project-params applies the owner's per-slot
            :params-schema marks — :sensitive? slots redact to the :rf/redacted
            sentinel, :large? slots elide to the :rf.size/large-elided marker,
            plain slots ride verbatim — via the SHARED redact-with-paths walker,
            frame-independently"
    (let [spec {:params-schema [:map
                                [:account-id {:sensitive? true} :string]
                                [:cursor     {:large? true} :string]
                                [:page :int]]}
          out  (classification/project-params
                 {:account-id "acct-secret-42" :cursor (apply str (repeat 200 "x")) :page 3}
                 spec)]
      (is (= privacy/redacted-sentinel (:account-id out))
          "the :sensitive? params slot is redacted")
      (is (contains? (:cursor out) :rf.size/large-elided)
          "the :large? params slot is elided to the size marker")
      (is (= 3 (:page out)) "the plain params slot rides verbatim")
      (is (not (str/includes? (pr-str out) "acct-secret-42"))
          "the raw sensitive params value does not ride"))))

(deftest project-params-sensitive-wins-over-large
  (testing "a params slot marked BOTH :sensitive? and :large? redacts (sensitive
            wins over large — the shared walker ordering, same as data + HTTP body)"
    (let [spec {:params-schema [:map [:token {:sensitive? true :large? true} :string]]}
          out  (classification/project-params {:token (apply str (repeat 100 "z"))} spec)]
      (is (= privacy/redacted-sentinel (:token out))
          "sensitive wins — the slot is the redaction sentinel, not a large marker"))))

(deftest project-params-no-marks-rides-verbatim
  (testing "a spec with no :params-schema marks (or nil spec) rides params
            UNCHANGED — the coarse whole-entry disposition is the separate
            authority that redacts/omits the whole key"
    (is (= {:slug "x"} (classification/project-params {:slug "x"}
                                                      {:params-schema [:map [:slug :string]]})))
    (is (= {:slug "x"} (classification/project-params {:slug "x"} nil)))))

;; ===========================================================================
;; 3. project-data — defer to the merged frame-owned project-egress
;; ===========================================================================

(deftest project-data-frameless-rides-unchanged-without-owner-marks
  (testing "frameless projection with no owner marks rides the data UNCHANGED —
            the coarse owner classification (not frame-presence) governs
            serialize-vs-redact; a pure / test-harness projection outside a
            frame is not over-redacted to the fail-closed sentinel"
    (is (= {:title "X"}
           (classification/project-data {:title "X"} {} nil :rf.egress/ssr-hydration)))))

(deftest project-data-applies-owner-data-schema-sensitive-marks
  (testing "EP-0015 §6 / issue 11: the resource-owned per-slot :data-schema
            :sensitive? marks (layer (a)) redact their slots REGARDLESS of
            frame classification — the canonical fine-grained owner surface,
            firing even frameless (resource cache data does not live at a frame
            app-db path)"
    (let [spec {:data-schema [:map
                              [:token {:sensitive? true} :string]
                              [:title :string]]}
          projected (classification/project-data
                      {:token "tok-123" :title "public"} spec nil :rf.egress/ssr-hydration)]
      (is (= privacy/redacted-sentinel (:token projected))
          "the owner-marked :token slot is redacted even with no frame")
      (is (= "public" (:title projected)) "the unmarked sibling rides verbatim")
      (is (not (str/includes? (pr-str projected) "tok-123"))
          "the raw owner-marked value does not ride"))))

(deftest project-data-redacts-frame-classified-slot
  (testing "EP-0015 §6 reconciliation: a :serialize entry's data projects
            through the merged frame-owned project-egress (layer (b)) — a
            frame-classified sensitive sub-path is REDACTED on projection
            (frame-owned source of truth), while unclassified siblings ride
            verbatim"
    ;; declare a frame-owned sensitive app-db path; the elision registry the
    ;; project-egress walker reads is populated from it.
    (rf/reg-frame :rcfg/main
      {:sensitive {:app-db [[:secret]]}})
    (let [projected (classification/project-data
                      {:secret "tok-123" :title "public"}
                      {}
                      :rcfg/main
                      :rf.egress/ssr-hydration)]
      (is (= privacy/redacted-sentinel (:secret projected))
          "the frame-classified :secret slot is redacted on projection")
      (is (= "public" (:title projected))
          "the unclassified sibling rides verbatim")
      (is (not (str/includes? (pr-str projected) "tok-123"))
          "the raw sensitive value does not ride anywhere"))))

(deftest project-data-composes-owner-and-frame-marks
  (testing "EP-0015 §6: owner per-slot marks (a) AND frame classification (b)
            COMPOSE — both a resource-owned :data-schema sensitive slot and a
            frame-classified app-db slot redact in one projection"
    (rf/reg-frame :rcfg/both {:sensitive {:app-db [[:frame-secret]]}})
    (let [spec {:data-schema [:map [:owner-secret {:sensitive? true} :string]]}
          projected (rf/with-frame :rcfg/both
                      (classification/project-data
                        {:owner-secret "o-1" :frame-secret "f-1" :title "ok"}
                        spec :rcfg/both :rf.egress/ssr-hydration))]
      (is (= privacy/redacted-sentinel (:owner-secret projected)) "owner mark fired")
      (is (= privacy/redacted-sentinel (:frame-secret projected)) "frame mark fired")
      (is (= "ok" (:title projected)) "unclassified sibling rides verbatim"))))

(deftest project-data-elides-owner-data-schema-large-slot
  (testing "rf2-260yhk: a resource-owned per-slot :data-schema :large? mark
            (layer (a)) ELIDES its slot to the :rf.size/large-elided marker —
            NOT riding RAW — REGARDLESS of frame classification (the canonical
            fine-grained owner surface, firing even frameless; resource cache
            data does not live at a frame app-db path). The CO-EQUAL counterpart
            to project-params' large axis, which already did this"
    (let [big  (apply str (repeat 500 "x"))
          spec {:data-schema [:map
                              [:body  {:large? true} :string]
                              [:title :string]]}
          projected (classification/project-data
                      {:body big :title "public"} spec nil :rf.egress/ssr-hydration)]
      (is (contains? (:body projected) :rf.size/large-elided)
          "the owner-marked :body slot is elided to the size marker, even frameless")
      (is (= "public" (:title projected)) "the unmarked sibling rides verbatim")
      (is (not (str/includes? (pr-str projected) big))
          "the raw large value does NOT ride on the wire"))))

(deftest project-data-sensitive-wins-over-large-owner-slot
  (testing "rf2-260yhk: a :data-schema slot marked BOTH :sensitive? and :large?
            redacts (sensitive wins over large — the shared walker ordering,
            same as project-params + HTTP body)"
    (let [spec {:data-schema [:map [:token {:sensitive? true :large? true} :string]]}
          projected (classification/project-data
                      {:token (apply str (repeat 200 "z"))} spec nil :rf.egress/ssr-hydration)]
      (is (= privacy/redacted-sentinel (:token projected))
          "sensitive wins — the slot is the redaction sentinel, not a large marker"))))

(deftest ssr-serialize-entry-elides-owner-data-schema-large-slot
  (reg! :report/card {:data-schema [:map
                                    [:blob {:large? true} :string]
                                    [:name :string]]})
  (testing "rf2-260yhk END-TO-END: a :serialize resource whose OWN :data-schema
            marks a slot :large? ELIDES that slot on SSR projection — the
            canonical fine-grained owner surface fires WITHOUT requiring a
            matching frame app-db mark (the bug: the large leaf rode RAW)"
    (let [big (apply str (repeat 1000 "q"))
          k   (state/scoped-resource-key :rf.scope/global :report/card {:slug "r"})
          e   (entry {:resource-id :report/card :data {:blob big :name "ok"}})
          [_ we] (only-wire-entry (ssr/project-resources-runtime-db (runtime-db-with {k e})))]
      (is (contains? (:blob (:data we)) :rf.size/large-elided)
          "the owner-marked :blob slot is elided on the serialized entry")
      (is (= "ok" (:name (:data we))) "the unmarked sibling rides verbatim")
      (is (= :loaded (:status we)) "metadata (status) still rides")
      (is (not (str/includes? (pr-str we) big))
          "no raw large value rides on the serialized entry"))))

;; ===========================================================================
;; 4. SSR projection — the reconciliation end-to-end
;; ===========================================================================

(deftest ssr-coarse-sensitive-redacts-whole-entry
  (reg! :secret/thing {:sensitive? true})
  (testing "a coarse :sensitive? resource still rides METADATA ONLY (the
            degenerate root-prop case preserved — redact)"
    (let [k   (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "s"})
          e   (entry {:resource-id :secret/thing :data {:ssn "123-45-6789"}})
          [_ we] (only-wire-entry (ssr/project-resources-runtime-db (runtime-db-with {k e})))]
      (is (= privacy/redacted-sentinel (:data we))
          "the sensitive data is replaced by the redaction sentinel")
      (is (= :loaded (:status we)) "metadata (status) still rides"))))

(deftest ssr-coarse-large-omits-whole-entry
  (reg! :big/thing {:large? true})
  (testing "a coarse :large? resource omits the :data key entirely (degenerate
            root-prop case preserved — omit)"
    (let [k   (state/scoped-resource-key :rf.scope/global :big/thing {:slug "b"})
          e   (entry {:resource-id :big/thing :data (vec (range 10000))})
          [_ we] (only-wire-entry (ssr/project-resources-runtime-db (runtime-db-with {k e})))]
      (is (not (contains? we :data)) "the large data key is omitted"))))

(deftest ssr-serialize-entry-projects-through-frame-classification
  (reg! :article/by-slug)   ;; a plain (no coarse claim) resource → :serialize
  (testing "EP-0015 §6: a :serialize resource's data is PROJECTED through the
            frame-owned project-egress on SSR — a frame-classified sensitive
            sub-path is redacted even though the resource itself carries no
            coarse claim (defense in depth via the frame-owned source of truth)"
    ;; the SSR projection resolves the current frame; declare classification on
    ;; it, then project under it.
    (rf/reg-frame :rcfg/ssr {:sensitive {:app-db [[:secret]]}})
    (let [k   (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"})
          e   (entry {:resource-id :article/by-slug
                      :data {:secret "tok-xyz" :title "hello"}})
          ;; drive the projection under the classified frame (the walker reads
          ;; the in-effect carried frame scope).
          proj (rf/with-frame :rcfg/ssr
                 (ssr/project-resources-runtime-db (runtime-db-with {k e})))
          [_ we] (only-wire-entry proj)]
      (is (= privacy/redacted-sentinel (:secret (:data we)))
          "the frame-classified :secret slot is redacted on the serialized entry")
      (is (= "hello" (:title (:data we)))
          "the unclassified sibling rides verbatim")
      (is (not (str/includes? (pr-str we) "tok-xyz"))
          "no raw sensitive value rides on the serialized entry"))))

(deftest ssr-serialize-entry-redacts-owner-data-schema-sensitive-slot
  (reg! :profile/card {:data-schema [:map
                                     [:pan {:sensitive? true} :string]
                                     [:name :string]]})
  (testing "EP-0015 §6 / issue 11 END-TO-END: a :serialize resource whose OWN
            :data-schema marks a slot :sensitive? redacts that slot on SSR
            projection — the canonical fine-grained owner surface fires
            irrespective of any frame app-db classification"
    (let [k   (state/scoped-resource-key :rf.scope/global :profile/card {:slug "x"})
          e   (entry {:resource-id :profile/card
                      :data {:pan "4111-1111-1111-1111" :name "Alice"}})
          ;; no frame app-db classification declares :pan — the OWNER's
          ;; data-schema mark is the only source, and it must fire.
          [_ we] (only-wire-entry (ssr/project-resources-runtime-db (runtime-db-with {k e})))]
      (is (= :serialize (classification/whole-entry-disposition
                          (rf/resource-meta :profile/card)))
          "no coarse claim → :serialize (the per-slot mark is the fine-grained surface)")
      (is (= privacy/redacted-sentinel (get-in we [:data :pan]))
          "the owner-marked :pan slot is redacted on the serialized entry")
      (is (= "Alice" (get-in we [:data :name])) "the unmarked sibling rides verbatim")
      (is (not (str/includes? (pr-str we) "4111-1111-1111-1111"))
          "no raw owner-marked value rides on the wire"))))

(deftest ssr-serialize-entry-redacts-owner-params-schema-sensitive-slot-in-key
  (reg! :report/by-account {:params-schema [:map
                                            [:account-id {:sensitive? true} :string]
                                            [:slug :string]]})
  (testing "EP-0015 issue 11 END-TO-END: a :serialize resource whose
            :params-schema marks a params slot :sensitive? must NOT ride that
            slot RAW in the projected wire KEY — the params surface is co-equal
            with the data surface (Spec 016 clause 4). The coarse claim leaves
            the key serialized, so the per-slot params classification is the
            only thing standing between the secret and every SSR visitor's wire"
    (let [k   (state/scoped-resource-key :rf.scope/global :report/by-account
                                         {:account-id "acct-secret-42" :slug "q3"})
          e   (entry {:resource-id :report/by-account :data {:total 99}})
          [wk we] (only-wire-entry (ssr/project-resources-runtime-db (runtime-db-with {k e})))]
      (is (= :serialize (classification/whole-entry-disposition
                          (rf/resource-meta :report/by-account)))
          "no coarse claim → :serialize (the per-slot params mark is the surface)")
      (is (= :report/by-account (nth wk 1)) "resource-id preserved (position 1)")
      (is (= privacy/redacted-sentinel (get-in wk [2 :account-id]))
          "the owner-marked :account-id params slot is redacted in the wire key")
      (is (= "q3" (get-in wk [2 :slug])) "the unmarked params sibling rides verbatim")
      (is (= {:total 99} (:data we)) "the (unmarked) data rides verbatim — serialize")
      (is (not (str/includes? (pr-str wk) "acct-secret-42"))
          "no raw sensitive params value rides in the wire key"))))

;; ===========================================================================
;; 4b. infinite-feed per-page :page-data-schema egress (rf2-byl7bk.3.2)
;; ===========================================================================
;;
;; EP-0021 R5 / Spec 016 §Registration — :infinite: an infinite feed's
;; `:data` is the framework-owned VECTOR OF PAGES, and its per-page
;; egress/classification contract is `:page-data-schema` (NOT `:data-schema`,
;; which is unused for the accumulated vector). SSR / tool / trace projection
;; MUST apply `:page-data-schema` PER PAGE so a sensitive / large page field is
;; classified before it crosses a boundary. Mirrors the load-more cursor egress
;; fix (rf2-3tysyj) but on the page BODY.

(defn- infinite-entry-with-pages
  "Build a `:loaded` infinite-feed entry for `resource-id` carrying `pages` (a
  vector of decoded page values) as its durable `:data` page vector. Mirrors
  the `entry` helper but seeds the `:infinite?` marker + the page-params
  alongside the page vector so the projection's infinite branch fires."
  [resource-id pages]
  (merge (state/empty-infinite-entry resource-id)
         {:status      :loaded
          :data        (vec pages)
          :page-params (vec (repeat (count pages) nil))
          :loaded-at   1000
          :stale-at    9.0e15}))

(deftest project-data-applies-page-data-schema-per-page-sensitive
  (testing "rf2-byl7bk.3.2: an :infinite resource's :data is a VECTOR of pages;
            the per-page :page-data-schema :sensitive? mark redacts that field
            on EVERY page — the :data-schema is NOT used for the accumulated
            vector (EP-0021 R5)"
    (let [spec  {:infinite         true
                 :page-data-schema [:map
                                    [:token {:sensitive? true} :string]
                                    [:title :string]]}
          pages [{:token "tok-0" :title "page-0"}
                 {:token "tok-1" :title "page-1"}]
          projected (classification/project-data
                      pages spec nil :rf.egress/ssr-hydration)]
      (is (vector? projected) "the page-vector SHAPE is preserved")
      (is (= 2 (count projected)) "every page is preserved")
      (is (= privacy/redacted-sentinel (:token (nth projected 0)))
          "page-0's owner-marked :token is redacted")
      (is (= privacy/redacted-sentinel (:token (nth projected 1)))
          "page-1's owner-marked :token is redacted")
      (is (= "page-0" (:title (nth projected 0))) "page-0's unmarked sibling rides")
      (is (= "page-1" (:title (nth projected 1))) "page-1's unmarked sibling rides")
      (is (not (str/includes? (pr-str projected) "tok-0"))
          "no raw page-0 sensitive value rides")
      (is (not (str/includes? (pr-str projected) "tok-1"))
          "no raw page-1 sensitive value rides"))))

(deftest project-data-applies-page-data-schema-per-page-large
  (testing "rf2-byl7bk.3.2: a per-page :page-data-schema :large? mark ELIDES
            that field to the :rf.size/large-elided marker on every page"
    (let [big   (apply str (repeat 500 "x"))
          spec  {:infinite         true
                 :page-data-schema [:map
                                    [:body {:large? true} :string]
                                    [:title :string]]}
          pages [{:body big :title "p0"} {:body big :title "p1"}]
          projected (classification/project-data
                      pages spec nil :rf.egress/ssr-hydration)]
      (is (contains? (:body (nth projected 0)) :rf.size/large-elided)
          "page-0's large field is elided to the size marker")
      (is (contains? (:body (nth projected 1)) :rf.size/large-elided)
          "page-1's large field is elided to the size marker")
      (is (not (str/includes? (pr-str projected) big))
          "no raw large page value rides"))))

(deftest project-data-infinite-ignores-data-schema
  (testing "rf2-byl7bk.3.2: an :infinite resource's :data-schema is NOT used as
            the accumulated-vector classifier (EP-0021 R5) — only
            :page-data-schema governs the page bodies"
    (let [spec  {:infinite    true
                 ;; an accidental :data-schema on an infinite resource must not
                 ;; classify the page vector itself.
                 :data-schema [:map [:token {:sensitive? true} :string]]}
          pages [{:token "tok-0"}]
          projected (classification/project-data
                      pages spec nil :rf.egress/ssr-hydration)]
      ;; no :page-data-schema marks → pages ride verbatim; the :data-schema
      ;; (which would match a single page map's :token) is deliberately ignored.
      (is (= pages projected)
          "the page vector rides verbatim — :data-schema is not consulted for it"))))

(deftest ssr-infinite-feed-redacts-sensitive-page-field-per-page
  (reg! :feed/timeline {:infinite         true
                        :next-page-param  (fn [_last _all] nil)
                        :page-data-schema [:map
                                           [:author-email {:sensitive? true} :string]
                                           [:body :string]]})
  (testing "rf2-byl7bk.3.2 END-TO-END: an infinite feed whose :page-data-schema
            marks a page field :sensitive? redacts that field on EVERY page
            during SSR projection — the page body must not ship RAW across the
            SSR/hydration + tool/trace AI boundary (the leak this fix closes)"
    (let [k    (state/scoped-resource-key :rf.scope/global :feed/timeline {:slug "t"})
          e    (infinite-entry-with-pages
                 :feed/timeline
                 [{:author-email "alice@example.com" :body "hello"}
                  {:author-email "bob@example.com" :body "world"}])
          [_ we] (only-wire-entry (ssr/project-resources-runtime-db (runtime-db-with {k e})))
          pages  (:data we)]
      (is (= :serialize (classification/whole-entry-disposition
                          (rf/resource-meta :feed/timeline)))
          "no coarse claim → :serialize (the per-page schema is the surface)")
      (is (vector? pages) "the page-vector shape is preserved on the wire")
      (is (= 2 (count pages)) "every accumulated page rides")
      (is (= privacy/redacted-sentinel (get-in pages [0 :author-email]))
          "page-0's sensitive field is redacted on the wire")
      (is (= privacy/redacted-sentinel (get-in pages [1 :author-email]))
          "page-1's sensitive field is redacted on the wire")
      (is (= "hello" (get-in pages [0 :body])) "page-0's unmarked field rides verbatim")
      (is (= "world" (get-in pages [1 :body])) "page-1's unmarked field rides verbatim")
      (is (not (str/includes? (pr-str we) "alice@example.com"))
          "no raw page-0 sensitive value rides anywhere on the entry")
      (is (not (str/includes? (pr-str we) "bob@example.com"))
          "no raw page-1 sensitive value rides anywhere on the entry"))))

;; ===========================================================================
;; 5. load-bearing Spec 016 rules preserved (the projection contract)
;; ===========================================================================

(deftest ssr-projection-rides-only-entries-slice
  (reg! :article/by-slug)
  (testing "Spec 016 §SSR: only the durable :rf.runtime/resources :entries
            slice rides — never the indexes, never all of runtime-db
            (allowlist-shaped, preserved under the reconciliation)"
    (let [k   (state/scoped-resource-key :rf.scope/global :article/by-slug {:slug "x"})
          e   (entry {:resource-id :article/by-slug :data {:title "X"}})
          rdb (assoc (runtime-db-with {k e})
                     :rf.runtime/machines {:snapshots {}}
                     :rf.runtime/routing  {:current {:route :x}})
          proj (ssr/project-resources-runtime-db rdb)]
      (is (= #{state/resources-key} (set (keys proj)))
          "only the resources subsystem key is projected")
      (is (= #{:entries} (set (keys (get proj state/resources-key))))
          "only :entries rides — indexes are recomputable-from-entries"))))

(deftest ssr-redacted-entry-hydrates-metadata-only-and-refetches
  (reg! :secret/thing {:sensitive? true})
  (testing "Spec 016 §SSR: a sensitive (redacted) entry hydrates as metadata
            only — the sentinel is NOT usable data, so it refetches on the
            client (not mistaken for fresh-with-data)"
    (let [k    (state/scoped-resource-key :rf.scope/global :secret/thing {:slug "s"})
          e    (entry {:resource-id :secret/thing :data {:ssn "x"}})
          proj (ssr/project-resources-runtime-db (runtime-db-with {k e}))
          [wk we] (only-wire-entry proj)
          ;; rf2-9e0tyq — install the real byte-keyed projection; the plan
          ;; names entries by their `:resource/key` (the projected wk).
          installed proj
          plan (->> (ssr/hydrate-refetch-plan installed 5000)
                    (into {} (map (juxt :resource/key identity))))]
      (is (= privacy/redacted-sentinel (:data we)) "redacted (metadata only) on the wire")
      (is (contains? plan wk) "the redacted entry IS in the refetch plan")
      (is (= :metadata-only (:reason (plan wk)))
          "classified metadata-only (not fresh-with-data)"))))

(deftest ssr-scoped-key-privacy-preserved-for-sensitive
  (reg! :secret/thing {:sensitive? true})
  (testing "Spec 016 clause 4: a sensitive resource's scope + params do NOT
            ride raw in the projected wire KEY (scoped-key privacy preserved
            under the reconciliation)"
    (let [scope [:rf.scope/session {:user "alice@example.com"}]
          k    (state/scoped-resource-key scope :secret/thing {:account-id "secret-42"})
          e    (entry {:resource-id :secret/thing :data {:ssn "x"}})
          [wk _] (only-wire-entry (ssr/project-resources-runtime-db (runtime-db-with {k e})))]
      (is (= :secret/thing (nth wk 1)) "resource-id preserved (position 1)")
      (is (contains? (nth wk 0) :rf/redacted) "scope redacted in the key")
      (is (contains? (nth wk 2) :rf/redacted) "params redacted in the key")
      (let [s (pr-str wk)]
        (is (not (str/includes? s "alice@example.com")) "no raw user in the key")
        (is (not (str/includes? s "secret-42")) "no raw param in the key")))))

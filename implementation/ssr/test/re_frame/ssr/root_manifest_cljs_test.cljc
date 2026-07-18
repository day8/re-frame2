(ns re-frame.ssr.root-manifest-cljs-test
  "Root Manifest v1 (S5-A) — the SUBSET PROPERTY and the wire/discovery
  contract (Spec 011 §Root Manifest v1; schema family Spec 004C §2).

  The load-bearing test in this namespace is
  `unmodified-s1-descriptor-is-a-valid-manifest`. It does NOT assert
  against a hand-transcribed fixture — it drives the SHIPPED S1 emitter
  (`re-frame.ui.compiler.root/root-descriptor`, reached through the same
  `analyze-root` the mount macros use) and feeds the result, byte for
  byte, to the manifest validator. If Root Manifest v1 ever stops being
  a strict superset of Root Descriptor v1, that test goes red against
  the real compiler rather than against my copy of its output.

  `making-an-extension-key-mandatory-breaks-the-subset-property` is the
  RED-BEFORE, made permanent: it runs a deliberately strict validator
  (one that requires an extension key) over the same real descriptors
  and proves it REJECTS them. That is the failure the shipped validator
  must never reproduce — so the guard cannot rot into a tautology.

  Runs on BOTH hosts (`.cljc`, `-cljs-test` ns): `clojure -M:test` from
  `implementation/ssr` and the node runner via `npm run test:cljs`. The
  UI compiler is a TEST-ONLY dependency here — production `re-frame.ssr`
  does not require `re-frame.ui`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.constants :as constants]
            [re-frame.ssr.manifest :as manifest]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.compiler.root :as root]))

;; ---------------------------------------------------------------------------
;; Driving the REAL S1 descriptor emitter
;; ---------------------------------------------------------------------------

(def resolver
  "Injected resolution stub — the S1b pattern the UI compiler's own
  suites use, so the analyzer runs host-neutrally here."
  (fn [sym]
    (case sym
      frame-root {:fqn 're-frame.ui/frame-root :meta {}}
      app-view   {:fqn 'app.views/app-view
                  :meta {:rf.ui/view true :rf.ui/view-id :app.views/app-view}}
      panel-view {:fqn 'app.views/panel-view
                  :meta {:rf.ui/view true :rf.ui/view-id :app.views/panel-view}}
      nil)))

(defn descriptor*
  "-> a genuine Root Descriptor v1, produced by the shipped compiler."
  ([form] (descriptor* form {}))
  ([form opts]
   (let [e (env/make-env {:host :clj :ns-sym 'app.test :resolver resolver})
         {:keys [ast views plans]} (root/analyze-root e 'ui/mount form)
         {:keys [root-id provenance]} (root/resolve-root-identity
                                       'ui/mount opts views)]
     (root/root-descriptor {:root-id root-id :provenance provenance
                            :views views :plans plans :ast ast}))))

(def real-descriptors
  "One descriptor per shape the S1 emitter can produce — authored and
  derived identity, literal and dynamic props, with and without frame
  plans, and the `render!` descriptor-base that carries NO identity keys
  at all (root-id is nil, so the identity keys are omitted). The subset
  property must hold for every one of them, not just the tidy case."
  (delay
    {:authored-literal-props
     (descriptor* '[app-view {:promo :spring :sizes [1 2]}]
                  {:root-id :page/shop})

     :derived-identity
     (descriptor* '[app-view {}] {})

     :derived-with-disambiguator
     (descriptor* '[app-view {}] {:disambiguator :left})

     :dynamic-props
     (descriptor* '[app-view {:promo current-promo}] {:root-id :page/shop})

     :with-frame-plans
     (descriptor* '[frame-root {:id :shop :initial-events [[:boot]]}
                    [app-view {}]]
                  {:root-id :page/shop})

     :no-mounted-view
     (descriptor* '[:div {} [:span {} "static"]] {:root-id :page/static})

     :render-base-no-identity
     (root/root-descriptor
      (let [e (env/make-env {:host :clj :ns-sym 'app.test :resolver resolver})
            {:keys [ast views plans]} (root/analyze-root
                                       e 'ui/render! '[app-view {}])]
        {:views views :plans plans :ast ast}))}))

;; ---------------------------------------------------------------------------
;; THE SUBSET PROPERTY — the acceptance evidence
;; ---------------------------------------------------------------------------

(deftest unmodified-s1-descriptor-is-a-valid-manifest
  (testing "every shape the shipped S1 emitter produces validates AS a
            Root Manifest v1, with no edit, no upgrade step, no migration"
    (doseq [[shape d] @real-descriptors]
      (is (nil? (manifest/problems d))
          (str "S1 descriptor shape " shape " must be a valid manifest "
               "unchanged; problems: " (pr-str (manifest/problems d))))
      (is (manifest/valid? d) (str shape))
      (is (= d (manifest/validate! 'test d))
          (str shape " — validate! returns the descriptor UNCHANGED; a "
               "manifest is not a rewritten descriptor"))))

  (testing "one version integer governs both shapes — no negotiation"
    (doseq [[shape d] @real-descriptors]
      (is (= manifest/schema-version (:rf.root/schema-version d))
          (str shape " declares the same :rf.root/schema-version as the "
               "manifest that extends it (additive keys never bump it)")))))

(deftest making-an-extension-key-mandatory-breaks-the-subset-property
  (testing "THE RED-BEFORE, kept executable: a validator that requires an
            extension key rejects the very descriptors the shipped
            validator accepts — so the property above is load-bearing,
            not vacuous"
    (letfn [(strict-problems [required m]
              (or (manifest/problems m)
                  (when-not (contains? m required)
                    {:missing required})))]
      (doseq [required [:element-locator :phase :props :identifier-prefix
                        :render-fingerprint :frame-payload-ids]
              [shape d] @real-descriptors]
        (is (= {:missing required} (strict-problems required d))
            (str "if " required " were mandatory, S1 descriptor shape "
                 shape " would FAIL — that is the regression this guards"))
        (is (nil? (manifest/problems d))
            (str "…and the SHIPPED validator still accepts it (" shape ")"))))))

(deftest schema-version-is-the-only-required-key
  (is (nil? (manifest/problems {:rf.root/schema-version 1}))
      "the minimal valid manifest is the version alone")
  (is (= {:missing :schema-version} (manifest/problems {:root-id :page/shop})))
  (is (= {:invalid :schema-version :got 2 :expected 1}
         (manifest/problems {:rf.root/schema-version 2}))
      "a foreign version is rejected — but there is no negotiation or
       upgrade path; v1 is the first version, not a compatibility layer")
  (is (= {:invalid :not-a-map :got (type "nope")}
         (manifest/problems "nope"))))

(deftest unknown-keys-are-ignored-by-readers
  (testing "004C §2 rule 2 — including the dev-only :root-id-provenance an
            S1 descriptor still carries; stripping it is an EMIT duty, not
            a read-side rejection (or a descriptor could not validate)"
    (is (nil? (manifest/problems
               {:rf.root/schema-version 1
                :root-id-provenance     :derived
                :some.future/key        [:whatever]})))))

;; ---------------------------------------------------------------------------
;; Assembly — the manifest EXTENDS, it does not replace
;; ---------------------------------------------------------------------------

(deftest manifest-preserves-every-descriptor-key-verbatim
  (let [d (:authored-literal-props @real-descriptors)
        m (manifest/manifest d {:element-locator    {:id "shop-root"}
                                :props              {:promo :spring}
                                :frame-payload-ids  [:shop :frame/session]
                                :render-fingerprint "rf1-abc"
                                :identifier-prefix  "rf2-page_Sshop-"})]
    (testing "no descriptor key is renamed, retyped, or re-semanticised"
      (doseq [[k v] (dissoc d :root-id-provenance)]
        (is (= v (get m k))
            (str "descriptor key " k " rides the manifest verbatim"))))
    (testing "only the dev-only key is dropped"
      (is (not (contains? m :root-id-provenance)))
      (is (contains? d :root-id-provenance)
          "…and it really was there to drop"))
    (testing "the extension keys land"
      (is (= {:id "shop-root"} (:element-locator m)))
      (is (= {:promo :spring} (:props m)))
      (is (= [:shop :frame/session] (:frame-payload-ids m)))
      (is (= "rf1-abc" (:render-fingerprint m)))
      (is (= "rf2-page_Sshop-" (:identifier-prefix m)))
      (is (= :server (:phase m)) ":phase is always stamped — the only v1 value"))
    (is (nil? (manifest/problems m)))))

(deftest manifest-with-no-render-facts-is-the-descriptor-plus-phase
  (let [d (:derived-identity @real-descriptors)
        m (manifest/manifest d)]
    (is (= (assoc (dissoc d :root-id-provenance) :phase :server) m)
        "absent extension facts are OMITTED, never stubbed with nil")
    (is (nil? (manifest/problems m)))))

(deftest assembly-validates-its-extension-facts
  (let [d (:derived-identity @real-descriptors)]
    (doseq [bad [{:element-locator {:id ""}}
                 {:element-locator {:id 42}}
                 {:element-locator {:selector "#shop"}}
                 {:render-fingerprint 42}
                 {:identifier-prefix :not-a-string}
                 {:frame-payload-ids ["shop"]}]]
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                   (manifest/manifest 'test d bad))
          (str "ill-formed extension fact fails at ASSEMBLY: " (pr-str bad))))))

;; ---------------------------------------------------------------------------
;; Locators (004C §4) — the closed `{:id string}` vocabulary
;; ---------------------------------------------------------------------------

(deftest host-authored-container-needs-an-id
  (is (= {:id "shop-root"} (manifest/host-locator 'test "shop-root")))
  (doseq [bad [nil "" "   "]]
    (let [data (try (manifest/host-locator 'test bad) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
          (str "host container id " (pr-str bad)))
      (is (= :container-id (get-in data [:missing]))
          "the emitter NEVER synthesises an id onto host-owned markup"))))

(deftest synthesised-locator-inherits-slug-injectivity
  (is (= {:id "rf2-root-page_Sshop"} (manifest/synthesised-locator "page_Sshop")))
  (is (not= (manifest/synthesised-locator "page_Sshop")
            (manifest/synthesised-locator "_V_Kshop_Sapp_Kleft"))
      "distinct slugs ⇒ distinct locators; the slug is injective (004C §1)"))

;; ---------------------------------------------------------------------------
;; Render-time props (004C §5) — fail loud, never truncate
;; ---------------------------------------------------------------------------

(deftest unserialisable-prop-fails-the-render-for-that-root
  (let [d    (:authored-literal-props @real-descriptors)
        data (try (manifest/manifest 'test d {:props {:promo :spring
                                                      :chart-fn (fn [_] 1)}})
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core/ExceptionInfo) e
                    (ex-data e)))]
    (is (= :rf.error/root-manifest-invalid (:rf.error/id data)))
    (is (= :chart-fn (:unserialisable-prop data))
        "the offending prop is NAMED — a silently truncated manifest would
         hydrate a different tree, not a smaller one"))
  (is (manifest/edn-carryable? {:a [1 "x" :k {:b #{1 2}}]}))
  (is (not (manifest/edn-carryable? {:a [1 {:b (fn [])}]}))
      "opaqueness is detected through nesting"))

;; ---------------------------------------------------------------------------
;; The wire form
;; ---------------------------------------------------------------------------

(deftest wire-form-round-trips
  (let [d (:with-frame-plans @real-descriptors)
        m (manifest/manifest d {:element-locator   {:id "shop-root"}
                                :identifier-prefix "rf2-page_Sshop-"})
        html (manifest/script-html m)]
    (testing "the element carries both marks and no identity in attributes"
      (is (re-find #"^<script type=\"application/edn\" data-rf-root>" html))
      (is (re-find #"</script>$" html))
      (is (not (re-find #"data-rf-root=" html))
          "the marker is BARE — identity lives in the content, spelled once"))
    (testing "the body reads back to the same manifest"
      (let [body (-> html
                     (subs (count "<script type=\"application/edn\" data-rf-root>"))
                     (as-> s (subs s 0 (- (count s) (count "</script>")))))]
        (is (= m (manifest/read-manifest 'test body)))))))

(deftest wire-body-cannot-break-out-of-the-script
  (let [m (manifest/manifest {:rf.root/schema-version 1 :root-id :page/shop}
                             {:props {:bio "</script><img onerror=x>"}})
        html (manifest/script-html m)]
    (is (not (re-find #"(?i)</script" (subs html 0 (- (count html) 9))))
        "the shared EDN script-body escape neutralises the breakout")
    (is (= m (manifest/read-manifest
              'test
              (-> html
                  (subs (count "<script type=\"application/edn\" data-rf-root>"))
                  (as-> s (subs s 0 (- (count s) 9)))))))))

(deftest unreadable-or-foreign-body-fails-loud
  (doseq [body ["{:rf.root/schema-version 1" "@@@" ""]]
    (let [data (try (manifest/read-manifest 'test body) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (= :rf.error/root-manifest-invalid (:rf.error/id data))
          (str "body " (pr-str body) " — a hydrating root never guesses")))))

(deftest marker-attribute-is-pinned-in-one-place
  (is (= "data-rf-root" constants/root-manifest-marker-attribute))
  (is (= "application/edn" manifest/manifest-script-type)))

;; ---------------------------------------------------------------------------
;; Discovery (CLJS) — adjacency, and nothing else
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn stub-el
     "A structural stand-in for a DOM element — `discover` reaches for
     exactly three things, so the contract is testable in the node runner
     without a browser."
     [{:keys [tag attrs text next]}]
     #js {:tagName            (or tag "SCRIPT")
          :textContent        (or text "")
          :nextElementSibling next
          :getAttribute       (fn [k] (get attrs k))
          :hasAttribute       (fn [k] (contains? attrs k))}))

#?(:cljs
   (deftest discovery-is-adjacency-and-content
     (let [m    (manifest/manifest {:rf.root/schema-version 1
                                    :root-id :page/shop}
                                   {:element-locator {:id "shop-root"}})
           body (pr-str m)
           script (stub-el {:attrs {"type" "application/edn"
                                    constants/root-manifest-marker-attribute ""}
                            :text  body})]
       (testing "the immediately following element sibling IS the manifest"
         (is (= m (manifest/discover (stub-el {:tag "DIV" :next script})))))

       (testing "identity comes from the CONTENT, never the element"
         (is (= :page/shop
                (:root-id (manifest/discover (stub-el {:tag "DIV" :next script}))))))

       (testing "no sibling ⇒ nil; the caller decides what absence means"
         (is (nil? (manifest/discover (stub-el {:tag "DIV" :next nil})))))

       (testing "an ordinary application/edn script is NOT a manifest —
                 the bare marker is what distinguishes the hydration
                 payload and data islands from a root manifest"
         (is (nil? (manifest/discover
                    (stub-el {:tag "DIV"
                              :next (stub-el {:attrs {"type" "application/edn"}
                                              :text  body})})))))

       (testing "a non-script sibling is not searched through"
         (is (nil? (manifest/discover
                    (stub-el {:tag "DIV"
                              :next (stub-el {:tag "DIV" :next script})})))
             "discovery does not walk — adjacency is the whole rule")))))

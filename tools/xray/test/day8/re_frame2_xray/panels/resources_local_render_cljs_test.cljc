(ns day8.re-frame2-xray.panels.resources-local-render-cljs-test
  "The local-render egress test for Xray's RESOURCES tab — the EP-0015
  `:rf.egress/local-redacted` on-box default applied to the live
  resource-instance projection.

  ## Why this arm needs its own test

  [spec/015-Data-Classification.md §The graduation gate] names Xray as the
  `:rf.egress/local-redacted` consumer; the App-DB panel's local render is
  the GRADUATING arm (`day8.re-frame2-xray.panels.local-render`)
  and is proven by `local_render_cljs_test`. The RESOURCES tab is a SECOND
  Xray arm that renders FRAME-SOURCED values — a live resource entry's `:data`
  / `:error` and the scoped-key's scope + params. The Resources
  privacy test (`resources_cljs_test/privacy-redacted-data-never-raw`) only
  proves that an ALREADY-redacted `:rf/redacted` sentinel — emitted by the
  runtime BEFORE Xray sees it — renders `[redacted]`. It does NOT prove the
  on-box LOCAL-render default actually redacts a RAW frame-classified
  resource value the way EP-0015 requires.

  This test covers that arm through the pieces the panel really uses: the
  resource's `:sensitive` / `:large` declarations are LOWERED by the
  resources registry (`re-frame.resources.classification/reconcile-registry`)
  onto the entry's absolute runtime-db coordinates, and each payload slot is
  projected through the EP-0015 on-box default
  `local-render/local-render-value-at` at `resources-helpers/slot-egress-path`
  (keyed on the OBSERVED frame and the entry's own key-id, which
  `instance-row` threads), then through the Resources projection algebra
  (`resources-helpers/summarize` + `instance-row`) the
  panel hands to the view. The closure composing those two in
  `resources/on-box-resource-egress-fn` is CLJS; its end-to-end pin is
  `resources_cljs_test/on-box-render-redacts-raw-sensitive-payload`. We
  assert the panel-facing summaries NEVER preview
  raw classified scope / params / data — the sensitive slot summarizes as the
  `[redacted]` sentinel preview, the large value rides through for the local
  operator, and an unreachable observed frame fails closed (the whole value
  redacts) rather than ship raw under no policy.

  ## What's asserted

    1. **DEFAULT redacts sensitive resource data** — a resource entry whose
       `:data` carries a frame-declared sensitive slot is projected under the
       local-render default; the projected `:data`'s sensitive LEAF becomes the
       `:rf/redacted` sentinel IN PLACE (the seam redacts leaf paths, not the
       whole value) and NO raw token text ever appears in the panel's bounded
       preview, while non-sensitive siblings + the entry METADATA (status /
       generation / owners) survive.
    2. **DEFAULT keeps large resource data on-box** — a frame-declared
       `:large` data slot is NOT elided on-box: the local operator sees big
       values; the summary's bounded preview is the only display bounding.
    3. **DEFAULT redacts a sensitive scope / params slot** — scope + params
       (which carry PII / identify the remote read) redact the same way as
       data; the panel's scope/params summaries never preview the raw value.
    4. **fail-closed on an unreachable observed frame** — projecting a raw
       resource value under a nil / destroyed observed frame redacts the WHOLE
       value, so the Resources summary IS the `[redacted]` sentinel preview,
       never a raw preview. (Contrast with arm 1: a LIVE frame redacts only the
       declared leaves; an UNREACHABLE one fails closed on the whole value.)
    5. **per-frame** — under a PLAIN observed frame (no classification) the
       same raw value rides through verbatim; the policy is the observed
       frame's own, never borrowed or ambient.

  JVM-portable (`.cljc`) so the Resources local-render contract is pinned by
  the JVM corpus, mirroring `local_render_cljs_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.local-render :as local-render]
            [day8.re-frame2-xray.panels.resources-helpers :as h]))

;; ---------------------------------------------------------------------------
;; Fixture data — the shape the Resources tab projects: an `:entries` map
;; keyed by the entry's opaque byte key-id STRING, the kind-preserving scoped
;; key `[scope resource-id params]` riding on the entry as `:resource/key`. We
;; seed RAW classified values (a sensitive `:secret`, a large `:rows`) so the
;; local-render seam — not an upstream runtime elision — is what redacts them.
;; ---------------------------------------------------------------------------

(def ^:private secret-token "secret-session-jwt-abc123")

;; A scope that itself carries a sensitive slot (scope is PII).
(def ^:private sensitive-scope
  [:rf.scope/session {:secret secret-token :tenant "t-1"}])

;; Params carrying a sensitive slot (params identify the remote read).
(def ^:private sensitive-params {:secret secret-token :id 42})

(def ^:private scoped-key
  [sensitive-scope :article/by-slug sensitive-params])

(def ^:private key-id "kid-article-welcome-1")

;; A resource entry whose payload carries BOTH a sensitive slot and a large
;; slot, plus a plain sibling — the shape the live instance table renders.
(def ^:private raw-entry
  {:resource/id   :article/by-slug
   :resource/key  scoped-key
   :status        :loaded
   :data          {:secret secret-token
                   :title  "Welcome"
                   :rows   (vec (range 300))}
   :generation    4
   :active-owners #{[:route :route/article "nav-1"]}
   :tags          #{[:article "welcome"]}
   :request-id    [:w 4]})

;; The resource's projection-relative classification, as `reg-resource`
;; declares it: the data's `:secret` and `:rows`, the params' `:secret`, and
;; the scope's `:secret` (index 1 of the scope vector).
(def ^:private article-classification
  {:sensitive [[:data :secret] [:params :secret] [:scope 1 :secret]]
   :large     [[:data :rows]]})

;; ---------------------------------------------------------------------------
;; Runtime fixture — two frames, one holding the live entry with its
;; declarations LOWERED by the resources registry onto the entry's absolute
;; runtime-db coordinates (`[:rf.runtime/resources :entries <key-id> …]`),
;; exactly as a resource handler's commit leaves them.
;;
;;   :app/secure — holds the entry; its declarations are lowered.
;;   :app/plain  — no classification (every value renders verbatim).
;; ---------------------------------------------------------------------------

(def secure-frame :app/secure)
(def plain-frame  :app/plain)

(defn- install-policy! []
  (rf.frame/swap-runtime-db! secure-frame
    (fn [rt]
      (-> rt
          (assoc-in [:rf.runtime/resources :entries] {key-id raw-entry})
          (rf.resources.classification/reconcile-registry
            {:article/by-slug article-classification})))))

(defn- init-fn []
  (rf/make-frame {:id plain-frame})
  (rf/make-frame {:id secure-frame})
  (install-policy!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; OPT OUT of the default `:rf/default` ambient scope so the fail-closed
     ;; arm asserts a frameless walk redacts (a bound ambient frame would let
     ;; the frameless projection resolve to its empty-policy identity and ship
     ;; raw — masking the fail-closed contract). Mirrors the App-DB sibling
     ;; local-render test's fixture.
     :ambient-frame nil
     :init-fn init-fn}))

;; The `instance-row` egress-fn the Resources panel threads: each payload slot
;; egresses under the observed frame at the absolute coordinate its
;; declarations were lowered to — the entry's key-id, which `instance-row`
;; passes, re-rooted per slot by `slot-egress-path`.
(defn- local-egress-fn [observed-frame]
  (fn [v slot key-id]
    (local-render/local-render-value-at v observed-frame (h/slot-egress-path key-id slot))))

(defn- no-raw-secret?
  "The render-safe summary must NEVER carry the raw secret in ANY of its
  string-valued display fields (the bounded `:preview` in particular)."
  [summary]
  (not-any? #(and (string? %) (str/includes? % secret-token))
            (vals summary)))

(defn- leaf-redacted?
  "The summary's bounded preview shows the sensitive leaf as the `:rf/redacted`
  sentinel IN PLACE — proof the local-render seam redacted the declared leaf
  (it redacts leaf paths, not the whole value, so a partially-classified
  structure keeps its non-sensitive shape with the secret swapped for the
  sentinel) — AND the raw secret text never leaks into the preview."
  [summary]
  (and (string? (:preview summary))
       (str/includes? (:preview summary) ":rf/redacted")
       (no-raw-secret? summary)))

;; ---------------------------------------------------------------------------
;; 1 + 2. DEFAULT — redact sensitive resource data, KEEP large on-box.
;; ---------------------------------------------------------------------------

(deftest resources-local-render-redacts-the-sensitive-leaf-and-keeps-metadata
  (let [row (h/instance-row [key-id raw-entry] nil (local-egress-fn secure-frame))
        data-summary (:data row)]
    (testing "(1) the frame-declared sensitive data slot redacts under the
              on-box default — the panel's :data summary shows the sensitive
              LEAF as :rf/redacted in place and NEVER previews the raw token"
      (is (leaf-redacted? data-summary)
          "the sensitive :secret leaf is redacted to :rf/redacted in the
           bounded preview; the raw session token never appears in any
           display field"))

    (testing "the entry METADATA survives the redaction (status / generation /
              owners project from the RAW entry, never through egress)"
      (is (= :loaded (:status row)))
      (is (= 4 (:generation row)))
      (is (= :article/by-slug (:resource-id row)))
      (is (true? (:has-data? row))
          "redacting the payload must not flip the derived :has-data? fact")
      (is (= 1 (:owner-count row))))))

(deftest resources-local-render-keeps-large-data-on-box
  ;; A LARGE-only data slot (no sensitive sibling) — the local operator is
  ;; entitled to big values; only secrets are withheld (the include-large?
  ;; overlay). The summary's bounded preview is display ergonomics, NOT an
  ;; egress redaction, so the value is NOT a large-elided sentinel.
  (let [large-data   {:rows (vec (range 300))}
        row          (h/instance-row [key-id (assoc raw-entry :data large-data)]
                                     nil (local-egress-fn secure-frame))
        data-summary (:data row)]
    (testing "the slot IS declared large: an off-box profile at the same
              coordinate elides it, so the on-box keep below is the overlay
              at work, not a declaration that missed"
      (is (contains? (:rows (rf/project-egress
                              large-data
                              {:rf.egress/profile :rf.egress/off-box-tool
                               :frame             secure-frame
                               :path              (h/slot-egress-path key-id :data)}))
                     :rf.size/large-elided)))
    (testing "(2) a frame-declared LARGE data slot is NOT elided on-box — the
              value rides through (no :rf.size/large-elided sentinel)"
      (is (false? (:large? data-summary))
          "large is kept on-box under the local-render default include-large? overlay")
      (is (false? (:redacted? data-summary))
          "a large-only (non-sensitive) value is not redacted"))))

;; ---------------------------------------------------------------------------
;; 3. DEFAULT — redact a sensitive SCOPE / PARAMS slot (PII gets the same
;;    elision as data).
;; ---------------------------------------------------------------------------

(deftest resources-local-render-redacts-sensitive-scope-and-params
  (let [row           (h/instance-row [key-id raw-entry] nil (local-egress-fn secure-frame))
        scope-summary  (:scope row)
        params-summary (:params row)]
    (testing "(3) the scope's sensitive slot redacts — the scope carries PII
              and gets the SAME elision as data (the :secret leaf is
              :rf/redacted in place; the raw token never previews)"
      (is (leaf-redacted? scope-summary)
          "the sensitive scope leaf redacts to :rf/redacted in the preview"))
    (testing "the params' sensitive slot redacts the same way (params identify
              the remote read)"
      (is (leaf-redacted? params-summary)))
    (testing "the RAW scoped-key is preserved verbatim as the react/identity
              key — per-row egress must never collapse two entries whose scope
              redacts to the same sentinel"
      (is (= scoped-key (:scoped-key row))))))

;; ---------------------------------------------------------------------------
;; 4. fail-closed — an UNREACHABLE observed frame redacts the WHOLE value, so
;;    the Resources summary previews [redacted], never a raw value.
;; ---------------------------------------------------------------------------

(deftest resources-local-render-fails-closed-on-unreachable-frame
  (testing "projecting under an unknown / destroyed observed frame redacts the
            whole resource value — every value-bearing summary is [redacted],
            never a raw preview"
    (doseq [unreachable [:app/does-not-exist nil]]
      (let [row (h/instance-row [key-id raw-entry] nil (local-egress-fn unreachable))]
        (is (= "[redacted]" (:preview (:data row)))
            (str "data fails closed under " (pr-str unreachable)))
        (is (= "[redacted]" (:preview (:scope row)))
            (str "scope fails closed under " (pr-str unreachable)))
        (is (= "[redacted]" (:preview (:params row)))
            (str "params fails closed under " (pr-str unreachable)))
        (is (no-raw-secret? (:data row)))
        (is (no-raw-secret? (:scope row)))
        (is (no-raw-secret? (:params row))
            (str "no raw secret leaks under the fail-closed " (pr-str unreachable) " frame"))
        (testing "metadata still projects from the raw entry under fail-closed"
          (is (= :loaded (:status row)))
          (is (= 4 (:generation row))))))))

;; ---------------------------------------------------------------------------
;; 5. per-frame — under a PLAIN observed frame the same raw value rides through
;;    verbatim (the policy is the observed frame's own, never borrowed).
;; ---------------------------------------------------------------------------

(deftest resources-local-render-applies-the-observed-frames-policy
  (testing "under a PLAIN frame (no :sensitive decl) the SAME raw resource
            value renders verbatim — the policy is per-frame, applied from the
            observed frame, never borrowed or ambient"
    (let [row          (h/instance-row [key-id raw-entry] nil (local-egress-fn plain-frame))
          data-summary (:data row)]
      (is (false? (:redacted? data-summary))
          "no sensitive decl on :app/plain ⇒ the data is not redacted")
      (is (str/includes? (:preview data-summary) "Welcome")
          "the plain-frame preview renders the (unredacted) value"))))

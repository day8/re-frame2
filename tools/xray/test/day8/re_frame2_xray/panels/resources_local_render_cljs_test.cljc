(ns day8.re-frame2-xray.panels.resources-local-render-cljs-test
  "The local-render egress test for Xray's RESOURCES tab — the EP-0015
  `:rf.egress/local-redacted` on-box default applied to the live
  resource-instance projection (rf2-t55hxg.15).

  ## The gap this closes

  [spec/015-Data-Classification.md §The graduation gate] names Xray as the
  `:rf.egress/local-redacted` consumer; the App-DB panel's local render was
  the GRADUATING arm (`day8.re-frame2-xray.panels.local-render`, rf2-t55hxg.12)
  and is proven by `local_render_cljs_test`. The RESOURCES tab is a SECOND
  Xray arm that renders FRAME-SOURCED values — a live resource entry's `:data`
  / `:error` and the scoped-key's scope + params. The existing Resources
  privacy test (`resources_cljs_test/privacy-redacted-data-never-raw`) only
  proves that an ALREADY-redacted `:rf/redacted` sentinel — emitted by the
  runtime BEFORE Xray sees it — renders `[redacted]`. It does NOT prove the
  on-box LOCAL-render default actually redacts a RAW frame-classified
  resource value the way EP-0015 requires.

  This test closes that arm: a RAW resource value carrying a frame-declared
  `:sensitive` slot and a frame-declared `:large` slot is projected through
  the EP-0015 on-box default `local-render/local-render-value` (keyed on the
  OBSERVED frame), then through the Resources projection algebra
  (`resources-helpers/summarize` + `instance-row` + `project-instances`) the
  panel hands to the view. We assert the panel-facing summaries NEVER preview
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
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.local-render :as local-render]
            [day8.re-frame2-xray.panels.resources-helpers :as h]))

;; ---------------------------------------------------------------------------
;; Runtime fixture — mirror `local_render_cljs_test`: two frames, one with a
;; declared :sensitive / :large policy. The classification declares paths into
;; the OBSERVED frame's value; the resource VALUES (entry :data, scoped-key
;; scope/params) we project are shaped so the declared paths land on them, so
;; the local-render walk redacts the resource value's classified slots exactly
;; as it would an app-db slot.
;;
;;   :app/secure — declares [:secret] sensitive (lands on a resource data /
;;                 scope / params slot named :secret) and [:rows] large.
;;   :app/plain  — no classification (every value renders verbatim).
;; ---------------------------------------------------------------------------

(def secure-frame :app/secure)
(def plain-frame  :app/plain)

(defn- install-policy! []
  ;; EP-0025: durable app-db classification rides the commit-plane
  ;; classification effects (`:source :effect`) — the frame annotation is removed.
  (frame/swap-runtime-db! secure-frame
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:secret]]
                :large     [[:rows]]}))))

(defn- init-fn []
  (rf/reg-frame plain-frame {})
  (rf/reg-frame secure-frame {})
  (install-policy!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     ;; OPT OUT of the default `:rf/default` ambient scope so the fail-closed
     ;; arm asserts a frameless walk redacts (a bound ambient frame would let
     ;; the frameless projection resolve to its empty-policy identity and ship
     ;; raw — masking the fail-closed contract). Mirrors the App-DB sibling
     ;; local-render test's fixture.
     :ambient-frame nil
     :init-fn init-fn}))

;; ---------------------------------------------------------------------------
;; Fixture data — the shape the Resources tab projects: a {scoped-key entry}
;; map. The scoped key is `[scope resource-id params]`; the entry carries
;; `:data` + lifecycle metadata. We seed RAW classified values (a sensitive
;; `:secret`, a large `:rows`) so the local-render seam — not an upstream
;; runtime elision — is what redacts them.
;; ---------------------------------------------------------------------------

(def ^:private secret-token "secret-session-jwt-abc123")

;; A scope that itself carries a frame-declared sensitive slot (scope is PII).
(def ^:private sensitive-scope
  [:rf.scope/session {:secret secret-token :tenant "t-1"}])

;; Params carrying a sensitive slot (params identify the remote read).
(def ^:private sensitive-params {:secret secret-token :id 42})

;; A resource entry whose payload carries BOTH a sensitive slot and a large
;; slot, plus a plain sibling — the shape the live instance table renders.
(def ^:private raw-entry
  {:resource/id   :article/by-slug
   :status        :loaded
   :data          {:secret secret-token
                   :title  "Welcome"
                   :rows   (vec (range 300))}
   :generation    4
   :active-owners #{[:route :route/article "nav-1"]}
   :tags          #{[:article "welcome"]}
   :request-id    [:w 4]})

;; The per-(scoped-key, entry) pair the panel projects, keyed under a scope +
;; params that ALSO carry sensitive slots.
(def ^:private scoped-key
  [sensitive-scope :article/by-slug sensitive-params])

;; A frame-keyed egress fn matching how the Resources panel SHOULD route a
;; frame-sourced value through the EP-0015 on-box default before summarizing —
;; the `instance-row` egress-fn seam (rf2-tgm1xu) wired to the local-render
;; profile. `slot` / `key-id` are ignored: the observed frame's path policy
;; governs which nested slots redact, exactly as the App-DB local render does.
(defn- local-egress-fn [observed-frame]
  (fn [v _slot _key-id] (local-render/local-render-value v observed-frame)))

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

(deftest resources-local-render-redacts-sensitive-data-keeps-large
  (let [row (h/instance-row [scoped-key raw-entry] nil (local-egress-fn secure-frame))
        data-summary (:data row)]
    (testing "(1) the frame-declared sensitive data slot redacts under the
              on-box default — the panel's :data summary shows the sensitive
              LEAF as :rf/redacted in place and NEVER previews the raw token"
      (is (leaf-redacted? data-summary)
          "the sensitive :secret leaf is redacted to :rf/redacted in the
           bounded preview; the raw session token never appears in any
           display field")
      (is (no-raw-secret? data-summary)
          "the raw session token never appears in any display field"))

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
  (let [large-entry  (assoc raw-entry :data {:rows (vec (range 300))})
        row          (h/instance-row [[sensitive-scope :catalog/rows {:page 1}] large-entry]
                                     nil (local-egress-fn secure-frame))
        data-summary (:data row)]
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
  (let [row           (h/instance-row [scoped-key raw-entry] nil (local-egress-fn secure-frame))
        scope-summary  (:scope row)
        params-summary (:params row)]
    (testing "(3) the scope's sensitive slot redacts — the scope carries PII
              and gets the SAME elision as data (the :secret leaf is
              :rf/redacted in place; the raw token never previews)"
      (is (leaf-redacted? scope-summary)
          "the sensitive scope leaf redacts to :rf/redacted in the preview")
      (is (no-raw-secret? scope-summary)
          "the raw token never previews through the scope summary"))
    (testing "the params' sensitive slot redacts the same way (params identify
              the remote read)"
      (is (leaf-redacted? params-summary))
      (is (no-raw-secret? params-summary)))
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
      (let [row (h/instance-row [scoped-key raw-entry] nil (local-egress-fn unreachable))]
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
    (let [row          (h/instance-row [scoped-key raw-entry] nil (local-egress-fn plain-frame))
          data-summary (:data row)]
      (is (false? (:redacted? data-summary))
          "no sensitive decl on :app/plain ⇒ the data is not redacted")
      (is (str/includes? (:preview data-summary) "Welcome")
          "the plain-frame preview renders the (unredacted) value")
      ;; And the SECURE frame redacts the SAME value — proving the divergence
      ;; is the observed frame's policy, not an artefact of the value.
      (is (leaf-redacted? (:data (h/instance-row [scoped-key raw-entry] nil
                                                 (local-egress-fn secure-frame))))
          "the SAME value redacts under the secure frame — per-frame policy"))))

;; ---------------------------------------------------------------------------
;; project-instances — the panel's projection entry point threads the
;; egress-fn through every row (the sub-level wiring the panel uses), so the
;; whole instance table is redacted, not just one row.
;; ---------------------------------------------------------------------------

(deftest project-instances-threads-local-render-egress
  (let [entries {scoped-key raw-entry}
        rows    (h/project-instances entries nil (local-egress-fn secure-frame))]
    (testing "project-instances applies the local-render egress to every row's
              payload slots — the instance table never previews raw classified
              scope / params / data"
      (is (= 1 (count rows)))
      (let [row (first rows)]
        (is (leaf-redacted? (:data row)))
        (is (leaf-redacted? (:scope row)))
        (is (leaf-redacted? (:params row)))
        (is (= :loaded (:status row)) "metadata survives across the table")))))

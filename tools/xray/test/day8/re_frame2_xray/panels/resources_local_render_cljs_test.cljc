(ns day8.re-frame2-xray.panels.resources-local-render-cljs-test
  "The Resources tab's on-box render of RAW frame-classified resource values
  under the EP-0015 `:rf.egress/local-redacted` default.

  The resource's `:sensitive` declarations are lowered by the resources
  registry (`re-frame.resources.classification/reconcile-registry`) onto the
  entry's absolute runtime-db coordinates, and each payload slot is projected
  through `local-render/local-render-value-at` at
  `resources-helpers/slot-egress-path`, keyed on the observed frame and the
  entry's own key-id, then through `instance-row` — the pieces the panel uses.
  The CLJS closure composing them is pinned end to end by
  `resources_cljs_test/on-box-render-redacts-raw-sensitive-payload`.

  JVM-portable (`.cljc`), so the JVM corpus pins it too."
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
;; Fixture: an entry keyed by its opaque key-id, the scoped key on
;; `:resource/key`, carrying RAW sensitive values in its scope, params and data
;; so the local-render seam — not an upstream elision — is what redacts them.
;; ---------------------------------------------------------------------------

(def ^:private secret-token "secret-session-jwt-abc123")

(def ^:private scoped-key
  [[:rf.scope/session {:secret secret-token :tenant "t-1"}]
   :article/by-slug
   {:secret secret-token :id 42}])

(def ^:private key-id "kid-article-welcome-1")

(def ^:private raw-entry
  {:resource/id   :article/by-slug
   :resource/key  scoped-key
   :status        :loaded
   :data          {:secret secret-token :title "Welcome"}
   :generation    4
   :active-owners #{[:route :route/article "nav-1"]}
   :tags          #{[:article "welcome"]}
   :request-id    [:w 4]})

(def secure-frame :app/secure)

(defn- init-fn []
  (rf/make-frame {:id secure-frame})
  (rf.frame/swap-runtime-db! secure-frame
    (fn [rt]
      (-> rt
          (assoc-in [:rf.runtime/resources :entries] {key-id raw-entry})
          (rf.resources.classification/reconcile-registry
            {:article/by-slug {:sensitive [[:data :secret] [:params :secret] [:scope 1 :secret]]}})))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; No ambient frame: a bound one would let the fail-closed arm's frameless
     ;; walk resolve to its empty policy and ship raw.
     :ambient-frame nil
     :init-fn init-fn}))

;; The `instance-row` egress-fn the panel threads: each slot egresses under the
;; observed frame at the coordinate its declarations were lowered to.
(defn- local-egress-fn [observed-frame]
  (fn [v slot key-id]
    (local-render/local-render-value-at v observed-frame (h/slot-egress-path key-id slot))))

(defn- no-raw-secret?
  "No string display field of the summary carries the raw secret."
  [summary]
  (not-any? #(and (string? %) (str/includes? % secret-token))
            (vals summary)))

(defn- leaf-redacted?
  "The preview shows the sensitive LEAF as `:rf/redacted` in place — the seam
  redacts declared leaf paths, not the whole value — and never the raw secret."
  [summary]
  (and (string? (:preview summary))
       (str/includes? (:preview summary) ":rf/redacted")
       (no-raw-secret? summary)))

(deftest resources-local-render-redacts-the-sensitive-leaf-and-keeps-metadata
  (let [row (h/instance-row [key-id raw-entry] nil (local-egress-fn secure-frame))]
    (testing "the frame-declared sensitive data leaf redacts in place under the on-box default"
      (is (leaf-redacted? (:data row))))
    (testing "the metadata projects from the RAW entry, never through egress"
      (is (= [:loaded 4 :article/by-slug true 1]
             ((juxt :status :generation :resource-id :has-data? :owner-count) row))
          "redacting the payload must not flip the derived :has-data? fact"))))

(deftest resources-local-render-redacts-sensitive-scope-and-params
  (let [row (h/instance-row [key-id raw-entry] nil (local-egress-fn secure-frame))]
    (testing "scope and params carry PII and redact the same way as data"
      (is (leaf-redacted? (:scope row)))
      (is (leaf-redacted? (:params row))))
    (testing "the RAW scoped-key stays the identity key, so two entries whose scope
              redacts to the same sentinel never collapse"
      (is (= scoped-key (:scoped-key row))))))

(deftest resources-local-render-fails-closed-on-unreachable-frame
  (testing "under an unknown or nil observed frame every value-bearing summary is
            the whole-value [redacted], never a raw preview"
    (doseq [unreachable [:app/does-not-exist nil]]
      (let [row (h/instance-row [key-id raw-entry] nil (local-egress-fn unreachable))]
        (is (= "[redacted]" (:preview (:data row)))
            (str "data fails closed under " (pr-str unreachable)))
        (is (= "[redacted]" (:preview (:scope row)))
            (str "scope fails closed under " (pr-str unreachable)))
        (is (= "[redacted]" (:preview (:params row)))
            (str "params fails closed under " (pr-str unreachable)))))))

(ns day8.re-frame2-xray.panels.resources-local-render-cljs-test
  "The Resources tab's on-box render of RAW frame-classified resource values
  under the EP-0015 `:rf.egress/local-redacted` default.

  The resource's `:sensitive` declarations are claimed by the resources
  classification hook on the entry's absolute runtime-db coordinates while the
  frame holds the entry, and each payload slot is projected
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
            [re-frame.resources]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.local-render :as local-render]
            [day8.re-frame2-xray.panels.resources-helpers :as h]))

;; ---------------------------------------------------------------------------
;; Fixture: an entry keyed by its opaque key-id, the scoped key on
;; `:resource/key`, carrying RAW sensitive values in its scope, params and data
;; so the local-render seam — not an upstream elision — is what redacts the
;; declared data slot. Params and scope are the resource's identity and carry no
;; declaration, so the local operator sees them.
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
  (rf/reg-resource :article/by-slug
                   {:scope :rf.scope/global :sensitive [[:data :secret]]}
                   (fn [_ _] {:request {:method :get :url "/articles"}}))
  (rf/make-frame {:id secure-frame})
  (rf.frame/swap-runtime-db! secure-frame
    (fn [rt] (assoc-in rt [:rf.runtime/resources :entries] {key-id raw-entry}))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; No ambient frame: a bound one would let the fail-closed arm's frameless
     ;; walk resolve to its empty policy and ship raw.
     :ambient-frame nil
     :init-fn init-fn}))

;; The `instance-row` egress-fn the panel threads: each slot egresses under the
;; observed frame at the coordinate its declarations are claimed at.
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
    (testing "the declared sensitive data leaf redacts in place under the on-box
              default"
      (is (leaf-redacted? (:data row))))
    (testing "the scope and params are the resource's identity, not a classified
              slot, so the local operator sees them on-box"
      (is (= [true true]
             (mapv #(str/includes? (str (:preview (% row))) secret-token) [:scope :params]))))
    (testing "the metadata projects from the RAW entry, never through egress, and
              the RAW scoped-key stays the identity key, so two entries whose scope
              redacts to the same sentinel never collapse"
      (is (= [:loaded 4 :article/by-slug true 1 scoped-key]
             ((juxt :status :generation :resource-id :has-data? :owner-count :scoped-key) row))
          "redacting the payload must not flip the derived :has-data? fact"))))

(deftest a-coarse-claim-redacts-the-error-slots-on-box
  ;; A coarse `:sensitive? true` claims the whole entry, its error channels
  ;; included: an error body routinely echoes the submitted value.
  (rf/reg-resource :account/me
                   {:scope :rf.scope/global :sensitive? true}
                   (fn [_ _] {:request {:method :get :url "/me"}}))
  (let [coarse-key-id "kid-account-me-1"
        entry         {:resource/id   :account/me
                       :resource/key  [[:rf.scope/global] :account/me nil]
                       :status        :error
                       :error         {:status 401 :body {:echo secret-token}}
                       :refresh-error {:status 401 :body {:echo secret-token}}
                       :generation    1}]
    (rf.frame/swap-runtime-db! secure-frame
      (fn [rt] (assoc-in rt [:rf.runtime/resources :entries coarse-key-id] entry)))
    (let [row (h/instance-row [coarse-key-id entry] nil (local-egress-fn secure-frame))]
      (is (= [true true]
             (mapv #(no-raw-secret? (% row)) [:error :refresh-error]))))))

(deftest resources-local-render-fails-closed-on-unreachable-frame
  (testing "under an unknown or nil observed frame every value-bearing summary is
            the whole-value [redacted], never a raw preview"
    (doseq [unreachable [:app/does-not-exist nil]]
      (let [row (h/instance-row [key-id raw-entry] nil (local-egress-fn unreachable))]
        (is (= ["[redacted]" "[redacted]" "[redacted]"]
               (mapv #(:preview (% row)) [:data :scope :params]))
            (str "data, scope and params fail closed under " (pr-str unreachable)))))))

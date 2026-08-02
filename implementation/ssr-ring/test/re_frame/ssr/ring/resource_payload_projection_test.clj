(ns re-frame.ssr.ring.resource-payload-projection-test
  "rf2-p026f5 / EP-0025 (rf2-71dr8t) — the NON-streaming Ring hydration payload
  projects the resource-runtime slice INSIDE the request frame scope, so the
  resource OWNER's coarse egress classification governs the emitted
  `__rf_payload`.

  THE STRUCTURAL FIX (rf2-p026f5, still load-bearing): `pipeline/
  build-full-response*` MUST bind `rf/with-frame` around the payload build —
  the resources SSR projection hook (`:ssr/extend-runtime-db-projection` →
  `re-frame.resources.ssr/project-resources-runtime-db`) resolves the current
  frame with `frame/resolve-current-frame`. If the build ran FRAMELESS the
  projection would see no frame at all and the scoped-key redaction the OWNER
  claim demands (Spec 016 clause 4) would not run. This suite pins that the
  build stays inside the frame by driving the REAL non-streaming Ring render
  path (`build-full-response*`) end-to-end.

  EP-0025 (rf2-71dr8t) REMOVED named-scope-resolver derived-sensitivity
  PROPAGATION. The disposition is now the resource OWNER's coarse `:sensitive?`
  / `:large?` claim ALONE (`classification/whole-entry-disposition`,
  frame-blind) — a `{:from-db}` resolver reading a frame-sensitive `:db` input
  NO LONGER upgrades a non-`:sensitive?` resource to `:redact`. Per Spec 015
  §No propagation / Spec 016 §No derived-sensitivity propagation.

  THE CONTRACT under test (the inversion of the removed engine, mirroring
  `re-frame.resources-derived-scope-sensitivity-cljs-test`'s end-to-end
  projection assertions but through the actual Ring render path):

    - a feed entry NOT declared `:sensitive?` under a scope derived from a
      sensitive `:db` input SERIALIZES verbatim (data + scope/params ride) —
      the fail-OPEN the EP names (classify the path you care about);
    - a resource declared `:sensitive?` contributes NO ROW to the payload via
      its OWN coarse claim — confirm-by-revert that the owner boundary, not
      propagation, drives it. rf2-4bjep: the coarse projection substitutes both
      key components, so the entry is not addressable by anything the live
      client derives and the row is WITHHELD rather than shipped metadata-only,
      the same settlement rf2-rjq9d reached for a per-slot-declared key.

  The streaming-path frame-scope coverage (the daemon-writer `rf/with-frame`
  rebinding, rf2-tbr67x) lives in `re-frame.ssr.ring-streaming-test`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.resources.state :as state]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.pipeline :as pipeline]
            [re-frame.ssr.ring.shell :as shell]
            [re-frame.ssr.ring.test-support :as ts]
            ;; load-bearing side-effecting requires: register the :resource +
            ;; :resource-scope registrar kinds, the schemas walker hooks, and
            ;; (crucially) the `:ssr/extend-runtime-db-projection` late-bind
            ;; hook resources publishes — the body under test.
            [re-frame.resources]
            [re-frame.resources.ssr]
            [re-frame.schemas]))

(use-fixtures :each ts/reset-runtime)

;; ---------------------------------------------------------------------------
;; App: a `{:from-db}` session-scoped feed resource.
;;
;; Mirrors the resources derived-scope-sensitivity test's `init!`: the frame
;; declares the viewer-identity path sensitive and a named scope resolver reads
;; it, but under EP-0025 (rf2-71dr8t) that sensitivity does NOT propagate to a
;; `{:from-db}` resource. Whether an entry redacts is governed by the resource's
;; OWN coarse `:sensitive?` claim alone — so the caller registers the feed
;; resource with or without `:sensitive?` per the case under test.
;; ---------------------------------------------------------------------------

(defn- register-feed-app!
  "Register the resource-scope resolver + the `{:from-db}` feed resource into
  the active registrar. `owner-sensitive?` toggles the resource's OWN coarse
  `:sensitive?` claim — the ONLY thing that drives redaction under EP-0025 (no
  derived-sensitivity propagation). The FRAME `:sensitive` classification is
  supplied per frame by the caller via a commit-plane `:sensitive` effect run
  through `:initial-events` at frame construction."
  [owner-sensitive?]
  (registrar/clear-kind! :resource-scope)
  (registrar/clear-kind! :resource)
  ;; resolver reading the FRAME-SENSITIVE viewer-identity path. EP-0025: this no
  ;; longer propagates sensitivity to the resource (no inheritance arm).
  (rf/reg-resource-scope :p026f5/session
    {:inputs {:username [:db [:auth :user :username]]}}
    (fn [{:keys [username]} _ctx]
      (when username [:rf.scope/session {:username username}])))
  ;; a session-scoped feed resource — :sensitive? per the case under test.
  (rf/reg-resource :p026f5/feed
    (cond-> {:scope         {:from-db :p026f5/session}
             :params-schema [:map [:page :int]]}
      owner-sensitive? (assoc :sensitive? true))
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}}))
  (rf/reg-view* :p026f5/root
    (fn [] [:main [:h1 "Feed"]])))

(defn- loaded-feed-entry
  "A `:loaded` durable feed entry under the session scope for `username`,
  carrying `data` and its own scoped key (the runtime keys `:entries` on the
  byte `key-id`; the kind-preserving scoped-key vector rides inside the
  entry per rf2-9e0tyq)."
  [username page data]
  (let [sk (state/scoped-resource-key [:rf.scope/session {:username username}]
                                      :p026f5/feed {:page page})]
    [sk (merge (state/empty-entry :p026f5/feed sk)
               {:status :loaded :data data :loaded-at 1000 :stale-at 9.0e15})]))

(defn- seed-feed-runtime-db!
  "Install a single loaded feed entry into `frame-id`'s runtime-db resource
  cache (byte-key-id `:entries` shape). `swap-runtime-db!` (NOT
  `replace-runtime-db!`) so the frame's elision registry at
  `[:rf.runtime/elision]` — where the frame's `:initial-events` commit-plane
  `:sensitive` classification effect installs its declaration (EP-0025 clean
  break) — is PRESERVED; a wholesale replace would clobber it and the
  derived-sensitivity classification would silently see no frame-sensitive
  paths."
  [frame-id username page data]
  (let [[sk entry] (loaded-feed-entry username page data)]
    (frame/swap-runtime-db!
      frame-id
      assoc state/resources-key {:entries     {(state/key-id sk) entry}
                                 :tag-index   {}
                                 :owner-index {}})))

(defn- payload-edn
  "Parse the `__rf_payload` EDN out of a rendered (non-streaming) SSR document
  body. The payload is a `#:rf{…}` namespace-map; `clojure.edn/read-string`
  reads it (the namespaced keys round-trip)."
  [body]
  (some-> (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)
          second
          edn/read-string))

;; ===========================================================================
;; EP-0025 (rf2-71dr8t): NO derived-sensitivity propagation. A feed entry NOT
;; declared :sensitive? under a scope derived from a sensitive :db input
;; SERIALIZES verbatim through the real non-streaming Ring path — the fail-OPEN
;; the EP names (no inheritance). The projection still runs INSIDE the frame
;; (rf2-p026f5 structural fix); it just no longer redacts the un-classified
;; entry.
;; ===========================================================================

(deftest non-streaming-payload-no-inheritance-serializes-resource
  (testing "rf2-p026f5 / EP-0025: a `{:from-db}` feed entry NOT declared
            :sensitive?, under a scope derived from a FRAME-SENSITIVE :db input,
            SERIALIZES verbatim in the non-streaming `__rf_payload` — no
            derived-sensitivity propagation (the value the author did not
            classify ships raw). The projection runs INSIDE `rf/with-frame`
            (the rf2-p026f5 structural fix) but the disposition is the OWNER's
            coarse claim ALONE — frame-blind — so a non-`:sensitive?` resource
            is not upgraded."
    (register-feed-app! false)
    (let [fid :p026f5/req-frame]
      ;; FRAME classification (EP-0025 clean break): the viewer-identity path is
      ;; sensitive. Durable app-db egress classification rides the B3
      ;; COMMIT-PLANE effect — a `reg-event` returns `:sensitive` alongside
      ;; `:db`, run via `:initial-events` at frame construction, writing the
      ;; per-frame `[:rf.runtime/elision]` registry the egress walk reads.
      ;; The frame-sensitive path is present, but EP-0025 no longer propagates
      ;; it to the `{:from-db}` resource.
      (rf/reg-event :p026f5/classify
        (fn [_ _] {:sensitive [[:auth :user :username]]}))
      (rf/make-frame {:id fid :platform       :server
                      :doc            "rf2-p026f5 per-request frame"
                      :initial-events [[:p026f5/classify]]})
      (try
        ;; Seed a loaded feed entry whose session scope embeds the viewer
        ;; identity ("jake"), with article data.
        (seed-feed-runtime-db! fid "jake" 1 {:articles [:a :b]})
        (let [opts     {:initial-events nil
                        :root-view  [(rf/view :p026f5/root)]
                        :emit-hash? true
                        :html-shell shell/default-html-shell
                        ;; whole-app-db so the test isolates the runtime-db
                        ;; resource projection, not the app-db allowlist.
                        :payload    :rf.ssr.payload/whole-app-db}
              resp     (#'pipeline/build-full-response* fid {:status 200} opts)
              body     (:body resp)
              payload  (payload-edn body)
              entries  (get-in payload [:rf/runtime-db
                                        :rf.runtime/resources :entries])
              [sk _]   (loaded-feed-entry "jake" 1 {:articles [:a :b]})]
          (is (= 200 (:status resp)) "happy-path render emitted a 200")
          (is (some? payload) "the __rf_payload parsed")
          (is (= 1 (count entries))
              "the single feed entry rides in the runtime-db slice")
          (let [we (val (first entries))
                wk (:resource/key we)]
            ;; EP-0025: no inheritance — data + wire key ride VERBATIM.
            (is (= {:articles [:a :b]} (:data we))
                "EP-0025: the non-classified entry's DATA rides verbatim (no inheritance)")
            (is (= :loaded (:status we)) "metadata (status) rides")
            (is (= sk wk)
                "EP-0025: the wire key rides verbatim (no inheritance redaction)")))
        (finally
          (lifecycle/destroy-frame-quietly! fid))))))

;; ===========================================================================
;; Confirm-by-revert: a resource declared :sensitive? is still governed by its
;; OWN coarse owner claim through the real Ring path (the surviving boundary,
;; not propagation). rf2-4bjep settles what that costs the ROW: the coarse
;; projection substitutes BOTH key components, so no live client can derive the
;; entry's identity and the row is WITHHELD rather than shipped metadata-only —
;; an installed row would be an ownerless duplicate nothing addresses and
;; nothing collects. This is the load-bearing leak check: raw "jake" + article
;; data must never ride, and now neither does the digest that stood for "jake".
;; ===========================================================================

(deftest non-streaming-payload-withholds-owner-declared-sensitive-resource
  (testing "rf2-p026f5 / EP-0025 / rf2-4bjep: a `{:from-db}` feed entry declared
            :sensitive? contributes NO row to the non-streaming `__rf_payload`
            — via the resource's OWN coarse claim (confirm-by-revert: the owner
            boundary, frame-blind, not propagation). The raw viewer identity
            (\"jake\") + data ({:articles …}) must NOT ride."
    (register-feed-app! true)
    (let [fid :p026f5/req-frame-sensitive]
      (rf/reg-event :p026f5/classify
        (fn [_ _] {:sensitive [[:auth :user :username]]}))
      (rf/make-frame {:id fid :platform       :server
                      :doc            "rf2-p026f5 per-request frame (owner-sensitive)"
                      :initial-events [[:p026f5/classify]]})
      (try
        (seed-feed-runtime-db! fid "jake" 1 {:articles [:a :b]})
        (let [opts     {:initial-events nil
                        :root-view  [(rf/view :p026f5/root)]
                        :emit-hash? true
                        :html-shell shell/default-html-shell
                        :payload    :rf.ssr.payload/whole-app-db}
              resp     (#'pipeline/build-full-response* fid {:status 200} opts)
              body     (:body resp)
              payload  (payload-edn body)
              entries  (get-in payload [:rf/runtime-db
                                        :rf.runtime/resources :entries])]
          (is (= 200 (:status resp)) "happy-path render emitted a 200")
          (is (some? payload) "the __rf_payload parsed")
          (is (empty? entries)
              (str "rf2-4bjep: the coarse feed entry contributes no row to the "
                   "runtime-db slice — stated as absence of the ROW, which is "
                   "what the sibling bead was reopened for: " (pr-str entries)))
          ;; the confirm-by-revert control: the projection is not silently
          ;; empty for everyone. The NON-sensitive counterpart of this exact
          ;; render still ships its row, asserted by
          ;; `non-streaming-payload-serializes-non-sensitive-resource` below.
          ;;
          ;; The raw viewer identity + article data must not ride ANYWHERE…
          (is (not (str/includes? (pr-str payload) "jake"))
              "EP-0025: the raw sensitive viewer identity does NOT ride")
          (is (not (str/includes? (pr-str payload) ":articles"))
              "EP-0025: the redacted resource DATA does NOT ride")
          ;; …and neither does the digest that stood for it. A 32-bit
          ;; non-cryptographic digest of a low-entropy identity is enumerable,
          ;; so the token was itself a small egress of what the coarse claim
          ;; asked to hide; withholding the row removes its last carrier.
          (is (not (str/includes? (pr-str (get payload :rf/runtime-db))
                                  "rf/redacted"))
              "rf2-4bjep: no redaction token rides the runtime-db slice either"))
        (finally
          (lifecycle/destroy-frame-quietly! fid))))))

;; ===========================================================================
;; A NON-sensitive resource still serializes verbatim — the in-frame
;; projection does not over-redact (the fix is scoped to the frame-classified
;; case; existing non-resource / non-sensitive payload behavior is unchanged).
;; ===========================================================================

(deftest non-streaming-payload-serializes-non-sensitive-resource
  (testing "rf2-p026f5: a resource whose `{:from-db}` resolver reads a
            NON-sensitive input serializes its data + scope verbatim in the
            non-streaming payload — the in-frame projection does not
            over-redact (the inheritance arm only fires for sensitive inputs)."
    (registrar/clear-kind! :resource-scope)
    (registrar/clear-kind! :resource)
    (rf/reg-resource-scope :p026f5/locale
      {:inputs {:locale [:db [:i18n :locale]]}}
      (fn [{:keys [locale]} _]
        (when locale [:rf.scope/locale {:locale locale}])))
    (rf/reg-resource :p026f5/prefs
      {:scope         {:from-db :p026f5/locale}
       :params-schema [:map]}
      (fn [_ _] {:request {:method :get :url "/prefs"}}))
    (rf/reg-view* :p026f5/root2 (fn [] [:main [:h1 "Prefs"]]))
    (let [fid :p026f5/req-frame-2]
      ;; frame declares a DIFFERENT path sensitive — the locale input does NOT
      ;; overlap, so no inheritance. Classified via the B3 commit-plane effect
      ;; (EP-0025 clean break) — see the first deftest's note.
      (rf/reg-event :p026f5/classify-2
        (fn [_ _] {:sensitive [[:auth :user :username]]}))
      (rf/make-frame {:id fid :platform       :server
                      :initial-events [[:p026f5/classify-2]]})
      (try
        (let [sk    (state/scoped-resource-key [:rf.scope/locale {:locale :en}]
                                               :p026f5/prefs {})
              entry (merge (state/empty-entry :p026f5/prefs sk)
                           {:status :loaded :data {:theme "dark"}
                            :loaded-at 1000 :stale-at 9.0e15})]
          ;; swap (not replace) to preserve the frame's elision registry.
          (frame/swap-runtime-db!
            fid assoc state/resources-key {:entries     {(state/key-id sk) entry}
                                           :tag-index   {} :owner-index {}})
          (let [opts    {:initial-events nil
                         :root-view  [(rf/view :p026f5/root2)]
                         :emit-hash? true
                         :html-shell shell/default-html-shell
                         :payload    :rf.ssr.payload/whole-app-db}
                resp    (#'pipeline/build-full-response* fid {:status 200} opts)
                payload (payload-edn (:body resp))
                we      (val (first (get-in payload [:rf/runtime-db
                                                     :rf.runtime/resources
                                                     :entries])))]
            (is (= {:theme "dark"} (:data we))
                "rf2-p026f5: the non-derived-sensitive data rides verbatim")
            (is (= sk (:resource/key we))
                "rf2-p026f5: the wire key rides verbatim (no redaction)")))
        (finally
          (lifecycle/destroy-frame-quietly! fid))))))

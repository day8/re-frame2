(ns re-frame.ssr.ring.resource-payload-projection-test
  "rf2-p026f5 — the NON-streaming Ring hydration payload projects the
  resource-runtime slice INSIDE the request frame scope, so a frame-owned
  egress classification (and named-scope derived sensitivity) governs the
  emitted `__rf_payload`.

  THE BUG: `pipeline/build-full-response*` bound `rf/with-frame` around the
  root-view resolution / render walk / head resolution, then EXITED that
  scope before reading app-db / runtime-db and calling `payload/build-payload`.
  The resources SSR projection hook
  (`:ssr/extend-runtime-db-projection` → `re-frame.resources.ssr/`
  `project-resources-runtime-db`) resolves the current frame with
  `frame/resolve-current-frame` so it can apply the frame-owned egress
  classification + named-scope derived sensitivity. With NO current frame the
  projection ran FRAMELESS, so a resource under a frame-sensitive `{:from-db}`
  scope serialized its raw scope identity + data into the payload instead of
  redacting them — leaking resource data/scope/params to every visitor of
  every SSR page.

  THE CONTRACT (Spec 016 clause 4 / Spec 015 §Derived sensitivity): the
  emitted `__rf_payload` `:rf/runtime-db` resource entry under a frame-sensitive
  `{:from-db}` scope MUST redact its data + scoped-key components exactly as the
  resources SSR projection does INSIDE `rf/with-frame` — the raw scope identity
  and the resource data must never ride.

  This mirrors `re-frame.resources-derived-scope-sensitivity-cljs-test`'s
  end-to-end projection assertion (same `\"jake\"` viewer + `{:articles …}`
  data the bead's review eval reproduced) but drives it through the actual
  non-streaming Ring render path (`build-full-response*`), where the payload
  build had escaped the frame scope. The streaming-path frame-scope coverage
  (the daemon-writer `rf/with-frame` rebinding, rf2-tbr67x) lives in
  `re-frame.ssr.ring-streaming-test`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
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
;; App: a frame-sensitive `{:from-db}` session-scoped feed resource.
;;
;; Mirrors the resources derived-scope-sensitivity test's `init!`: the frame
;; declares the viewer-identity path sensitive, a named scope resolver reads
;; it (default :inherit, so it derives sensitive), and a session-scoped feed
;; resource references it via `{:from-db}` WITHOUT itself being declared
;; `:sensitive?`. The derivation inherits → the entry must redact on the wire.
;; ---------------------------------------------------------------------------

(defn- register-sensitive-feed-app!
  "Register the resource-scope resolver + the `{:from-db}` feed resource into
  the active registrar. The FRAME `:sensitive` declaration is supplied per
  frame at `reg-frame` time by the caller (the gensym handler frame can't
  carry it, so the regression registers its OWN frame — see the test)."
  []
  (registrar/clear-kind! :resource-scope)
  (registrar/clear-kind! :resource)
  ;; resolver reading the FRAME-SENSITIVE viewer-identity path (default
  ;; :inherit → propagates sensitivity from the input).
  (rf/reg-resource-scope :p026f5/session
    {:inputs  {:username [:db [:auth :user :username]]}
     :resolve (fn [{:keys [username]} _ctx]
                (when username [:rf.scope/session {:username username}]))})
  ;; a session-scoped feed resource — NOT itself declared :sensitive?.
  (rf/reg-resource :p026f5/feed
    {:scope         {:from-db :p026f5/session}
     :params-schema [:map [:page :int]]}
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
  `[:rf.runtime/elision]` — where `reg-frame`'s `:sensitive` declaration is
  installed — is PRESERVED; a wholesale replace would clobber it and the
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
;; THE regression — the non-streaming payload redacts the frame-sensitive
;; resource (data + scoped-key) because the projection runs INSIDE the frame.
;; ===========================================================================

(deftest non-streaming-payload-redacts-frame-sensitive-resource
  (testing "rf2-p026f5: the non-streaming Ring `__rf_payload` projects the
            resource-runtime slice INSIDE the request frame scope, so a
            frame-sensitive `{:from-db}` feed entry (NOT declared :sensitive?)
            ships METADATA ONLY — data redacted + scope/params in the wire key
            redacted to opaque digests. Before the fix the payload build ran
            OUTSIDE `rf/with-frame`, so the projection was FRAMELESS and the
            raw viewer identity (\"jake\") + data ({:articles …}) leaked."
    (register-sensitive-feed-app!)
    (let [fid :p026f5/req-frame]
      (rf/reg-frame fid
        {:platform  :server
         :doc       "rf2-p026f5 per-request frame"
         ;; FRAME classification: the viewer-identity path is sensitive.
         :sensitive {:app-db [[:auth :user :username]]}})
      (try
        ;; Seed a loaded feed entry whose derived session scope embeds the
        ;; sensitive viewer identity ("jake"), with article data.
        (seed-feed-runtime-db! fid "jake" 1 {:articles [:a :b]})
        (let [opts     {:on-create  nil
                        :root-view  [:p026f5/root]
                        :emit-hash? true
                        :html-shell shell/default-html-shell
                        ;; whole-app-db so the test isolates the runtime-db
                        ;; resource projection (the bug surface), not the
                        ;; app-db allowlist.
                        :payload    :rf.ssr.payload/whole-app-db}
              resp     (#'pipeline/build-full-response* fid {:status 200} opts)
              body     (:body resp)
              payload  (payload-edn body)
              entries  (get-in payload [:rf/runtime-db
                                        :rf.runtime/resources :entries])]
          (is (= 200 (:status resp)) "happy-path render emitted a 200")
          (is (some? payload) "the __rf_payload parsed")
          (is (= 1 (count entries))
              "the single feed entry rides in the runtime-db slice")
          (let [we (val (first entries))
                wk (:resource/key we)]
            ;; THE bug assertions — data + scope/params REDACTED.
            (is (= privacy/redacted-sentinel (:data we))
                "rf2-p026f5: the derived-sensitive entry's DATA is redacted in
                 the non-streaming payload (the projection saw the frame)")
            (is (= :loaded (:status we)) "metadata (status) still rides")
            (is (= :p026f5/feed (nth wk 1))
                "the resource-id rides at position 1 (never sensitive)")
            (is (contains? (nth wk 0) :rf/redacted)
                "the derived sensitive SCOPE is redacted in the wire key")
            (is (contains? (nth wk 2) :rf/redacted)
                "the PARAMS are redacted in the wire key"))
          ;; The raw viewer identity + article data must not ride ANYWHERE in
          ;; the payload — the load-bearing leak check (the bead's review eval
          ;; observed raw "jake" + {:articles [:a :b]} before the fix).
          (is (not (str/includes? (pr-str payload) "jake"))
              "rf2-p026f5: the raw sensitive viewer identity does NOT ride")
          (is (not (str/includes? (pr-str payload) ":articles"))
              "rf2-p026f5: the redacted resource DATA does NOT ride"))
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
      {:inputs  {:locale [:db [:i18n :locale]]}
       :resolve (fn [{:keys [locale]} _]
                  (when locale [:rf.scope/locale {:locale locale}]))})
    (rf/reg-resource :p026f5/prefs
      {:scope         {:from-db :p026f5/locale}
       :params-schema [:map]}
      (fn [_ _] {:request {:method :get :url "/prefs"}}))
    (rf/reg-view* :p026f5/root2 (fn [] [:main [:h1 "Prefs"]]))
    (let [fid :p026f5/req-frame-2]
      ;; frame declares a DIFFERENT path sensitive — the locale input does NOT
      ;; overlap, so no inheritance.
      (rf/reg-frame fid
        {:platform  :server
         :sensitive {:app-db [[:auth :user :username]]}})
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
          (let [opts    {:on-create  nil
                         :root-view  [:p026f5/root2]
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

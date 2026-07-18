(ns re-frame.ui.guide-truth-jvm-test
  "Focused truth gates for the tracked pre-publication re-frame.ui guide.

  These assertions protect the ruled lifecycle recipe, guide-wide rendering
  boundary, fixture-marker mechanics, Xray S3 surface, and S6 relocation plan
  until the guide moves to docs/ui.

  Guide 07 (`07-servers.md`) is additionally proven EXECUTABLE here (rf2-o6p1h6):
  the chapter's server-data path is not string-matched but RUN. A real resource
  is registered with the shipped 3-slot `reg-resource` grammar, the actual
  `:rf.resource/ensure` → `:rf.http/managed` lower → reply-envelope settle path
  drives the runtime cache, and a rendered `latency-tile` reads that same state
  through `[:rf/resource …]` — so `:loading`, success (rendered value 42),
  failure, and the retry intent are proven through the real transport/resource
  path, and the app-db counterpart (raw `:rf.http/managed` with
  `:on-success`/`:on-failure` handlers consuming the uniform reply envelope) is
  proven too. A false claim in the chapter reddens a deftest below, so the
  transport / resource / app-db seams cannot drift apart. `guide07-chapter-
  forms-stay-truth-bound` (rf2-qww2o) additionally binds the chapter's
  load-bearing FORMS — keys, url, envelopes, app-db writes, retry intent, and
  the self-contained `managed-args` atom + capturing `:rf.http/managed`
  override the runnable snippet dereferences — so a Markdown-only drift reddens
  even while these hand-copied deftests stay green.

  The managed-HTTP transport is exercised WITHOUT a network: `:rf.http/managed`
  is overridden with a capturing stub and the test replays the transport's
  reply-event-append shape (`(conj on-success {:status :ok :value …})`), exactly
  as `re-frame.resources-managed-http-cljs-test` does — so the runtime's real
  reply handlers run against the genuine 3-element event the live transport
  produces."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            ;; load-bearing side-effecting requires: register the resource
            ;; events/subs + the managed-HTTP fx + the Malli validator these
            ;; tests drive. Optional artefacts, pulled onto the ui TEST classpath
            ;; (deps.edn :test) — production re-frame.ui never :requires them.
            [re-frame.resources]
            [re-frame.resources.test-support]
            [re-frame.http.managed]
            [re-frame.http.registry :as http-registry]
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :refer [defview lease sub]]
            [re-frame.ui.test :as uit]))

(def chapter-files
  ["01-getting-started.md"
   "02-views.md"
   "03-state.md"
   "04-events.md"
   "05-frames.md"
   "06-worked-app.md"
   "07-servers.md"
   "08-testing.md"
   "09-debugging.md"
   "10-performance.md"
   "11-ssr.md"
   "12-how-it-works.md"
   "13-from-other-worlds.md"
   "14-compile-time-limits.md"])

(defn- repo-root
  []
  (or (some (fn [candidate]
              (let [root (.getCanonicalFile (io/file candidate))]
                (when (.isFile (io/file root "AGENTS.md"))
                  root)))
            (take 6 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "Could not locate repository root" {}))))

(def ^:private root (delay (repo-root)))

(defn- root-file
  [relative]
  (io/file @root relative))

(defn- guide-file
  [name]
  (root-file (str "ai/findings/new-substrate-synthesis/guide/" name)))

(defn- slurp-guide
  [name]
  (slurp (guide-file name)))

(defn- markdown-targets
  [text]
  (map second (re-seq #"\]\(([^)]+)\)" text)))

(defn- target-path
  [target]
  (first (str/split target #"#" 2)))

(defn- clojure-fences
  [chapter]
  (map-indexed
    (fn [index [_ body]]
      {:id   (str chapter "#" (inc index))
       :body body})
    (re-seq #"(?ms)^```clojure[^\r\n]*\r?\n(.*?)^```\s*$"
            (slurp-guide chapter))))

(defn- host-fence?
  [{:keys [body]}]
  (boolean
    (re-find #"ui/(?:mount|hydrate-root|create-root|render!|unmount!)|ui\.test/with-root|js/document"
             body)))

(defn- dom-target?
  [{:keys [body]}]
  (boolean (re-find #"\A;; guide:target dom\r?\n" body)))

(defn- one-line
  [text]
  (str/replace text #"\s+" " "))

;; ===========================================================================
;; Guide 07 — the EXECUTABLE server-data path (rf2-o6p1h6)
;;
;; Register a real resource, drive the actual ensure/reply + runtime-cache
;; path, and read `[:rf/resource …]` in a rendered view. The transport is
;; captured (no network); the test replays the transport's reply-envelope
;; append shape so the runtime's genuine reply handlers settle the cache.
;; ===========================================================================

(def ^:private managed-args
  "The last `:rf.http/managed` args the capturing stub recorded. Its runtime-
  owned `:on-success` / `:on-failure` reply targets are replayed with the
  transport's appended `{:status :ok :value …}` / `{:status :error :error …}`
  envelope to settle the resource cache."
  (atom nil))

(defn- capturing-transport-fixture
  "Override `:rf.http/managed` with a capturing no-op so the ensure/refetch
  lower is inspectable and the reply is replayed explicitly — the resource
  server-data path runs end-to-end without a live HTTP client."
  [f]
  (reset! managed-args nil)
  (http-registry/clear-all-in-flight!)
  (fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! managed-args args) nil))
  (f)
  (http-registry/clear-all-in-flight!))

;; ---- the two views the chapter shows, verbatim in shape --------------------

;; §2 — the resource tile: declares liveness with `lease`, reads status with a
;; passive `[:rf/resource …]` sub, and branches on the real status set.
(defview latency-tile []
  (lease {:resource :metrics/latency-feed})
  (let [{:keys [status data]} (sub [:rf/resource {:resource :metrics/latency-feed}])]
    (case status
      (:idle :loading) [:div.tile.skeleton "…"]
      :error   [:div.tile.error
                "Feed unavailable"
                [:button {:on-click [:rf.resource/refetch
                                     {:resource :metrics/latency-feed}]}
                 "Retry"]]
      ;; :loaded and :fetching — prior data stays visible mid-refresh
      [:div.tile
       [:h3 "p95 latency"]
       [:strong (str (:p95 data) "ms")]])))

;; §Firing a request without a resource — the app-db counterpart: the view
;; reads ordinary app-db that the envelope-consuming handlers wrote.
(defview latency-plain-tile []
  (let [{:keys [status p95]} (sub [:metrics/latency])]
    (case status
      :loading [:div.tile.skeleton "…"]
      :error   [:div.tile.error "Feed unavailable"]
      :loaded  [:div.tile [:strong (str p95 "ms")]]
      [:div.tile.skeleton "…"])))

;; use-fixtures is evaluated AFTER the defviews above so the reset fixture's
;; ns-load registrar baseline includes them (they survive the per-test reset).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  capturing-transport-fixture)

(defn- reg-latency-feed!
  "Register the `:metrics/latency-feed` resource with the exact shipped
  3-slot grammar (id, metadata map, `:request` handler). The reset fixture
  wipes the registry per test, so each test re-registers first."
  []
  (rf/reg-resource :metrics/latency-feed
    {:doc           "Rolling p95 latency, refreshed on demand."
     :scope         :rf.scope/global
     :params-schema [:map]}
    (fn [_params _ctx]
      {:request {:method :get :url "/api/metrics/latency"}
       :decode  :json})))

(deftest guide07-resource-loading-and-success-render-the-real-cache
  ;; §1–§2 + §Under the hood — the headline end-to-end: ensure → :loading, then
  ;; the success reply envelope settles :loaded + data, rendered through
  ;; `[:rf/resource …]` as 42.
  (reg-latency-feed!)
  (rf/with-new-frame
    [frame (rf/make-frame {})]
    ;; the cause the view's `lease` would drive at runtime
    (uit/dispatch! frame [:rf.resource/ensure
                          {:resource :metrics/latency-feed
                           :owner    [:lease :metrics 1]}])
    (testing "the resource lowered its :request into :rf.http/managed verbatim,
              with runtime-owned reply addressing"
      (let [req @managed-args]
        (is (some? req) "the ensure reached the transport")
        (is (= {:method :get :url "/api/metrics/latency"} (:request req)))
        (is (= :json (:decode req)) "the Spec 014 :decode key rides through")
        (is (= [:rf.resource.internal/succeeded]
               (subvec (:on-success req) 0 1))
            "reply addressing is runtime-owned, not app-authored")))
    (testing ":loading — the tile branches to its skeleton"
      (is (str/includes?
            (uit/text (uit/render [latency-tile] {:frame frame})) "…")))
    (testing "success — replay the {:status :ok :value …} envelope; the settled
              cache value renders through the sub"
      (uit/dispatch! frame (conj (:on-success @managed-args)
                                 {:status :ok :value {:p95 42}}))
      (let [tree (uit/render [latency-tile] {:frame frame})]
        (is (str/includes? (uit/text tree) "42"))
        (is (nil? (uit/find tree :button))
            "the loaded tile carries no retry control")))))

(deftest guide07-resource-failure-renders-error-and-retry-re-enters-the-path
  ;; §2 retry intent — a first-load failure settles :error; the tile shows the
  ;; error branch whose retry button carries a REAL refetch event; dispatching
  ;; it re-enters the load path and the fresh reply recovers the value.
  (reg-latency-feed!)
  (rf/with-new-frame
    [frame (rf/make-frame {})]
    (uit/dispatch! frame [:rf.resource/ensure
                          {:resource :metrics/latency-feed
                           :owner    [:lease :metrics 1]}])
    (testing "failure — replay the {:status :error :error …} envelope; the tile
              branches to its error state and offers a real retry intent"
      (uit/dispatch! frame (conj (:on-failure @managed-args)
                                 {:status :error
                                  :error  {:kind :rf.http/http-5xx :status 503}}))
      (let [tree (uit/render [latency-tile] {:frame frame})]
        (is (str/includes? (uit/text tree) "Feed unavailable"))
        (is (= [:rf.resource/refetch {:resource :metrics/latency-feed}]
               (:on-click (uit/attrs (uit/find tree :button))))
            "the retry control carries a real refetch event as data")))
    (testing "retry — the refetch the button intends re-enters :loading through
              the same real transport path"
      (reset! managed-args nil)
      (uit/dispatch! frame [:rf.resource/refetch {:resource :metrics/latency-feed}])
      (is (some? @managed-args) "the retry lowered a fresh fetch")
      (is (str/includes?
            (uit/text (uit/render [latency-tile] {:frame frame})) "…")
          "the retry re-enters the skeleton"))
    (testing "recovery — the retried fetch's own reply settles the value"
      (uit/dispatch! frame (conj (:on-success @managed-args)
                                 {:status :ok :value {:p95 42}}))
      (is (str/includes?
            (uit/text (uit/render [latency-tile] {:frame frame})) "42")))))

(deftest guide07-raw-managed-http-writes-app-db-through-the-envelope
  ;; §Firing a request without a resource — the app-db seam: a raw
  ;; :rf.http/managed with :on-success/:on-failure whose handlers CONSUME the
  ;; uniform reply envelope and write app-db, read back by an ordinary app sub.
  (rf/reg-sub :metrics/latency (fn [db _] (:latency db)))
  (rf/reg-event :metrics/latency-requested
    (fn [{:keys [db]} _]
      {:db (assoc-in db [:latency :status] :loading)
       :fx [[:rf.http/managed
             {:request    {:method :get :url "/api/metrics/latency"}
              :decode     :json
              :on-success [:metrics/latency-arrived]
              :on-failure [:metrics/latency-failed]}]]}))
  (rf/reg-event :metrics/latency-arrived
    (fn [{:keys [db]} [_ {:keys [value]}]]      ; envelope: {:status :ok :value …}
      {:db (assoc db :latency {:status :loaded :p95 (:p95 value)})}))
  (rf/reg-event :metrics/latency-failed
    (fn [{:keys [db]} [_ {:keys [error]}]]      ; envelope: {:status :error :error …}
      {:db (assoc db :latency {:status :error :error error})}))
  (rf/with-new-frame
    [frame (rf/make-frame {})]
    (uit/dispatch! frame [:metrics/latency-requested])
    (testing "the raw :rf.http/managed carries the shipped contract keys"
      (let [req @managed-args]
        (is (= {:method :get :url "/api/metrics/latency"} (:request req)))
        (is (= :json (:decode req)))
        (is (= [:metrics/latency-arrived] (:on-success req)))
        (is (= [:metrics/latency-failed] (:on-failure req)))))
    (testing ":loading renders the skeleton from app-db"
      (is (str/includes?
            (uit/text (uit/render [latency-plain-tile] {:frame frame})) "…")))
    (testing "the success handler consumes the envelope's :value into app-db;
              the app sub reads it and the tile renders 42"
      (uit/dispatch! frame (conj (:on-success @managed-args)
                                 {:status :ok :value {:p95 42}}))
      (is (str/includes?
            (uit/text (uit/render [latency-plain-tile] {:frame frame})) "42")))))

(deftest guide07-chapter-forms-stay-truth-bound
  ;; rf2-qww2o — the three deftests above PROVE the server-data behaviour with
  ;; forms held here in the test. This gate BINDS those forms to the chapter
  ;; text: every load-bearing key, path, envelope, app-db write, retry intent,
  ;; and — crucially — the SELF-CONTAINED capturing fixture (the `managed-args`
  ;; atom + the capturing `:rf.http/managed` override the runnable snippet
  ;; dereferences) must appear verbatim in `07-servers.md`. A drift in the
  ;; Markdown (a wrong key/url/envelope/refetch, or a snippet that again omits
  ;; the atom/override) reddens HERE even while the executable deftests keep
  ;; their own copies green.
  (let [servers (slurp-guide "07-servers.md")
        has?    (fn [needle] (str/includes? servers needle))]
    (testing "§1 register — the 3-slot grammar, fail-closed scope, url, decode"
      (is (has? "(rf/reg-resource :metrics/latency-feed"))
      (is (has? ":scope") "the required scope key is shown")
      (is (has? ":rf.scope/global") "the explicit global scope value")
      (is (has? ":params-schema [:map]"))
      (is (has? "{:method :get :url \"/api/metrics/latency\"}")
          "the exact request the executable gate lowers verbatim")
      (is (has? ":decode") "the Spec 014 decode key rides through")
      (is (has? ":json") "the decode value"))
    (testing "§2 view — lease liveness + passive [:rf/resource …] read"
      (is (has? "(lease {:resource :metrics/latency-feed})"))
      (is (has? "(sub [:rf/resource {:resource :metrics/latency-feed}])")))
    (testing "the ensure cause the lease drives under the hood"
      (is (has? "[:rf.resource/ensure")))
    (testing "retry intent — the control carries a real refetch event as data"
      (is (has? "[:rf.resource/refetch {:resource :metrics/latency-feed}]")))
    (testing "the uniform reply envelope — success and failure shapes"
      (is (has? ":status :ok") "success envelope tag")
      (is (has? ":value") "success carries the decoded body under :value")
      (is (has? ":status :error") "failure envelope tag")
      (is (has? "{:status :ok :value {:p95 42}}")
          "the exact success envelope the gate replays as the last reply arg"))
    (testing "§Firing a request without a resource — the app-db seam"
      (is (has? ":on-success [:metrics/latency-arrived]"))
      (is (has? ":on-failure [:metrics/latency-failed]"))
      (is (has? "(assoc db :latency {:status :loaded :p95 (:p95 value)})")
          "the success handler consumes the envelope's :value into app-db")
      (is (has? "(sub [:metrics/latency])")
          "the plain tile reads ordinary app-db"))
    (testing "the runnable testing snippet is SELF-CONTAINED (rf2-qww2o)"
      (is (has? "(def managed-args (atom nil))")
          "the managed-args atom is DEFINED in the chapter, not private to this test")
      (is (has? "(fx/reg-fx :rf.http/managed")
          "the capturing :rf.http/managed override is INSTALLED in the chapter")
      (is (has? "(reset! managed-args args)")
          "the override CAPTURES the lowered args into managed-args")
      (is (has? "(conj (:on-success @managed-args)")
          "success replays the transport's reply-append shape onto the captured target")
      (is (has? "(conj (:on-failure @managed-args)")
          "failure replays the transport's reply-append shape onto the captured target"))))

(deftest lifecycle-recipe-is-installed-and-owned
  (let [getting-started (slurp-guide "01-getting-started.md")
        events          (slurp-guide "04-events.md")
        worked-app      (slurp-guide "06-worked-app.md")
        servers         (slurp-guide "07-servers.md")
        testing-guide   (slurp-guide "08-testing.md")]
    (testing "the one documented fixture installs an adapter for caller-owned frames"
      (is (str/includes? testing-guide
                         "test-support/make-reset-runtime-fixture"))
      (is (str/includes? testing-guide ":adapter plain-atom/adapter"))
      (is (str/includes? testing-guide ":ambient-frame nil")))
    (testing "every synchronous canonical test uses the eval-bind-run-destroy form"
      (doseq [[chapter text] [["01" getting-started]
                              ["04" events]
                              ["06" worked-app]
                              ["07" servers]
                              ["08" testing-guide]]]
        (is (str/includes? text "rf/with-new-frame")
            (str "Guide " chapter " must retain caller-owned frame cleanup"))))
    (testing "mounted examples keep root and frame ownership separate"
      (is (str/includes? testing-guide
                         "`with-root` owns its React root and DOM container"))
      (is (str/includes? testing-guide "[frame thunk]"))
      (is (str/includes? testing-guide "(js/Promise.resolve (thunk))"))
      (is (str/includes? testing-guide "rfUiTestCleanupError"))
      (is (not (str/includes? testing-guide "[frame promise]")))
      (is (re-find #"destroy-frame-after!\s+frame\s+#\(ui\.test/with-root"
                   testing-guide)
          "mounted call sites pass with-root as a thunk")
      (is (str/includes? testing-guide "(rf/destroy-frame! frame)"))
      (is (str/includes? testing-guide "reject-after-current!")))))

;; --- The retired per-host-AST claims (rf2-kxkag) -----------------------------
;;
;; Shipped reality (`compiler.cljc`): a build runs the SHARED analyzer over its
;; own source and hands the resulting AST to exactly ONE emitter —
;; `(if cljs? (emit-cljs/emit-defview args) (emit-jvm/emit-defview args))`.
;; Analysis is host-parameterized, so the hosts' ASTs are separate values that
;; never meet, and the parity corpus DETECTS divergence rather than preventing
;; it. Every retired formulation of the opposite is listed here once and
;; censused over both the guide chapters and the parent authorities below.
(def ^:private retired-claim-patterns
  [["host outputs cannot drift"
    #"(?is)(?:(?:client|browser|server|ssr|jvm)[^\n]{0,120}(?:cannot|can't)\s+drift|(?:cannot|can't)\s+drift[^\n]{0,120}(?:client|browser|server|ssr|jvm))"]
   ["browser/JVM share one AST value"
    #"(?i)(?:same|shared)\s+(?:template\s+)?AST\b|\bshare(?:s|d)?\s+the\s+AST\b|\btwin\s+of\s+the\s+same\s+AST\b"]
   ;; The semantically-equivalent retired formulation: ONE parse/AST feeding the
   ;; per-host emitters. "one AST and one emitter per host build" is the current
   ;; law and must NOT match, so the emitter side requires two/both/per-host.
   ["one parse/AST → per-host emitters"
    #"(?i)(?:one|a\s+single)\s+(?:normalized\s+|normalised\s+|template\s+)*(?:parse|AST)\b[^\n]{0,80}(?:two|both|per-host|each\s+host)\s+emitter"]
   ["one emitter implements both hosts"
    #"(?i)(?:(?:one|same)\s+emitter[^\n]{0,120}(?:both\s+hosts|client[^\n]*server|browser[^\n]*jvm)|(?:both\s+hosts|client[^\n]*server|browser[^\n]*jvm)[^\n]{0,120}(?:one|same)\s+emitter)"]
   ["host output is identical"
    #"(?i)identically\s+on\s+(?:the\s+)?(?:client|browser)[^\n]{0,40}(?:server|jvm)"]
   ["no second serializer"
    #"(?i)no\s+second\s+seriali[sz]er"]
   ["JVM string emitter"
    #"(?i)(?:a\s+)?string\s+emitter\s+for\s+the\s+jvm"]
   ["equivalent by construction"
    #"(?i)structurally\s+equivalent\s+by\s+construction"]
   ["no implementation can drift"
    #"(?i)there\s+is\s+no\s+second\s+implementation\s+to\s+drift"]])

;; The ACTIVE parent authorities guide 12 sends maintainers to. Each carries a
;; positive re-analysis assertion, so a wording-only deletion cannot false-green
;; the retired-claim census above.
(def ^:private compiler-model-authorities
  {"spec/Ownership.md"
   [#"(?i)one\s+normalized\s+AST\s+and\s+one\s+emitter\s+per\s+host\s+build"]

   "ai/findings/new-substrate-synthesis/06-ssr-islands.md"
   [#"(?i)each\s+host\s+build\s+runs\s+the\s+shared\s+analyzer"
    #"(?is)hosts\s+never\s+meet\s+as\s+ASTs"
    #"(?is)divergence\s+is\s+\*\*detected\*\*\s+rather\s+than\s+prevented"]

   "ai/findings/new-substrate-synthesis/drafts/spec-004-rewrite-draft.md"
   [#"(?i)\*\*One\s+AST\s+per\s+build\.\*\*"
    #"(?is)hosts'?\s+ASTs\s+are\s+not\s+guaranteed\s+equal\s+values"
    #"(?is)hosts\s+never\s+meet\s+as\s+ASTs"]

   "ai/findings/new-substrate-synthesis/skill/SKILL.md"
   [#"(?is)SSR\s+drift\s+is\s+\*detected\*"]

   "docs/EP/EP-0034-re-frame-ui-production-ssr-testing.md"
   [#"(?is)hands\s+that\s+build's\s+own\s+AST\s+to\s+exactly\s+one\s+emitter"
    #"(?is)hosts\s+never\s+meet\s+as\s+ASTs"]

   "docs/EP/EP-0030-the-compiled-view-substrate-program.md"
   [#"(?is)each\s+host\s+build\s+emits\s+from\s+its\s+own"]})

(deftest compiler-authorities-teach-per-host-analysis
  (doseq [[path required] compiler-model-authorities]
    (let [text (slurp (root-file path))]
      (testing (str path " — positive per-host re-analysis")
        (doseq [pattern required]
          (is (re-find pattern text)
              (str path " lost its per-host analysis statement: " pattern))))
      (testing (str path " — retired claims stay absent")
        (doseq [[claim pattern] retired-claim-patterns]
          (is (not (re-find pattern text))
              (str path " reintroduced retired claim: " claim)))))))

(deftest pipeline-and-stage-claims-stay-truthful
  (let [chapters (into {} (map (juxt identity slurp-guide) chapter-files))
        views    (get chapters "02-views.md")
        ssr      (get chapters "11-ssr.md")
        how      (get chapters "12-how-it-works.md")
        limits   (get chapters "14-compile-time-limits.md")]
    (testing "JVM emission is structural data and HTML serialization is S5"
      (doseq [required ["versioned `re-frame.ui.tree` structural data"
                        "`re-frame.ssr/emit-ui-tree`"
                        "Reagent/hiccup"
                        "conversion contract and parity gates"]]
        (is (or (str/includes? ssr required)
                (str/includes? how required))
            (str "missing ruled pipeline phrase: " required))))
    (testing "chapters 02 and 14 state the same precise three-stage responsibility"
      (doseq [[chapter text] [["02" views] ["14" limits]]]
        (is (re-find #"(?is)browser.{0,80}direct.{0,40}React" text)
            (str "Guide " chapter " must name browser direct React output"))
        (doseq [[responsibility pattern]
                [["versioned re-frame.ui.tree structural data"
                  #"(?is)versioned\s+`re-frame\.ui\.tree`\s+structural\s+data"]
                 ["S5 HTML serializer"
                  #"`re-frame\.ssr/emit-ui-tree`"]
                 ["conversion contract"
                  #"(?i)conversion\s+contract"]
                 ["parity gates"
                  #"(?i)parity\s+gates"]]]
          (is (re-find pattern text)
              (str "Guide " chapter " missing ruled responsibility: "
                   responsibility)))))
    (testing "retired rendering overclaims stay absent from every numbered chapter"
      (doseq [[chapter text] chapters
              [claim pattern] retired-claim-patterns]
        (is (not (re-find pattern text))
            (str chapter " reintroduced retired claim: " claim))))
    (testing "controlled inputs state the exact S3 causal sequence"
      (is (re-find
            #"browser input event → synchronous drain/epoch commit → ViewCell snapshot advance →\s+React's discrete render observes the matching value"
            how))
      (is (str/includes? how "G-8 real-browser fixture")))))

;; --- Active-authority census: the ui.test public surface (rf2-ukuun) ---------
;;
;; `ui.test/frame` was removed with the one-frame-init ruling (rf2-va5e61): a
;; test-owned frame is made with `rf/make-frame` + `:initial-events`, and there
;; is no `ui.test` constructor. The guide already says so; the OPERATIVE
;; inventories that leaf beads implement against are elsewhere, so they are
;; censused here rather than in a repository-wide prose scanner. Each entry is
;; ONE line located by a stable substring — not a Markdown parse.
(def ^:private ui-test-surface-inventories
  [["spec/API.md" "blessed §2 public-surface table"
    "| `re-frame.ui.test/*` (render,"]
   ["spec/API.md" "authoritative §2b surface matrix"
    "| `re-frame.ui.test/*` | **S1 core**"]
   ["ai/findings/new-substrate-synthesis/drafts/spec-004-rewrite-draft.md"
    "S1 surface row" "| `ui.test` surfaces this Spec references |"]
   ["ai/findings/new-substrate-synthesis/drafts/spec-004-rewrite-draft.md"
    "Spec 008 ripple inventory" "- `spec/008-Testing.md` — the `ui.test` contract"]])

;; A BARE `frame` roster entry. The lookarounds keep the sanctioned spellings
;; out: `re-frame` (preceded by `-`), `frame-targeted` / `frame-chain`
;; (followed by `-`), and `frames` (followed by a word char). A retired roster
;; entry — `attrs, frame,` or `attrs/frame` or `query/frame/` — matches.
(def ^:private ^java.util.regex.Pattern bare-frame-entry-re
  #"(?<![\w-])frame(?![\w-])")

(deftest ui-test-inventories-name-the-shipped-surface
  (testing "the shipped namespace has no frame constructor"
    (let [publics (set (map name (keys (ns-publics 're-frame.ui.test))))]
      (is (not (contains? publics "frame"))
          "re-frame.ui.test/frame is removed — make-frame + :initial-events is the one grammar")
      (doseq [shipped ["find-all" "attrs" "dispatch!"]]
        (is (contains? publics shipped)
            (str "re-frame.ui.test/" shipped " must stay public")))))
  (doseq [[path label locator] ui-test-surface-inventories]
    (testing (str path " — " label)
      (let [line (->> (str/split-lines (slurp (root-file path)))
                      (filter #(str/includes? % locator))
                      first)]
        (is (some? line)
            (str "inventory row not found (locator moved?): " locator))
        (when line
          (is (not (re-find bare-frame-entry-re line))
              (str path " (" label ") reintroduced the removed ui.test/frame constructor"))
          ;; Positive members, so deleting the roster cannot false-green the
          ;; rule. Only members that sit on the LOCATED line are asserted — one
          ;; roster (the Spec 008 ripple bullet) wraps, and the rule stays a
          ;; line census rather than a Markdown parser.
          (doseq [shipped ["find-all" "attrs"]]
            (is (str/includes? line shipped)
                (str path " (" label ") must still list " shipped))))))))

(deftest xray-s3-stays-on-existing-views-surfaces
  (let [views       (slurp-guide "02-views.md")
        debugging   (slurp-guide "09-debugging.md")
        performance (slurp-guide "10-performance.md")
        combined    (str debugging "\n" performance)]
    (is (not (str/includes? views "dev heatmap")))
    (is (str/includes? combined "existing Views"))
    (is (str/includes? combined "post-S3 information-architecture review"))
    (is (str/includes? performance "`effect` deps are values *(lands S3)*"))
    (is (not (re-find #"(?i)heatmap[^\n]*lands S3" combined)))
    (is (not (str/includes? combined "render timeline")))
    (is (not (str/includes? combined "causes timeline")))))

(deftest readme-and-move-plan-cover-the-current-learning-order
  (let [readme       (slurp-guide "README.md")
        readme-order (map second
                          (re-seq #"\|\s+\d+\s+\|\s+\[[^]]+\]\((\d\d-[^)]+\.md)\)"
                                  readme))
        guide-names  (->> (.listFiles (guide-file "."))
                          (filter #(.isFile %))
                          (map #(.getName %))
                          (filter #(str/ends-with? % ".md"))
                          set)
        plan         (slurp (root-file
                              "ai/findings/new-substrate-synthesis/drafts/guide-docs-move-plan.md"))]
    (is (= chapter-files readme-order)
        "README numeric order is the single 14-chapter learning order")
    (is (= (conj (set chapter-files) "README.md") guide-names)
        "the source guide contains exactly index + 14 chapters")
    (doseq [chapter chapter-files]
      (is (str/includes? plan
                         (str "| `guide/" chapter "` | `docs/ui/" chapter "` |"))
          (str "missing source/destination map for " chapter))
      (is (str/includes? plan (str "\": ui/" chapter))
          (str "missing MkDocs nav entry for " chapter)))
    (is (str/includes? plan "15 guide files + the migration doc"))
    (is (str/includes? plan "16 files added (`docs/ui/index.md`, 14 chapters"))
    (is (= 7 (reduce + (map #(count (re-seq #"guide:no-fixture"
                                             (slurp-guide %)))
                            chapter-files)))
        "the move plan's seven-fence waiver census must match the guide")
    (is (not (str/includes? readme
                            "../../../../docs/core/frames.md"))
        "compatibility-only Frames teaching must not be a canonical continuation")))

(deftest fixture-stage-and-guide09-ledger-stay-current
  (let [readme (slurp-guide "README.md")
        testing-guide (slurp-guide "08-testing.md")
        pipeline (slurp (root-file
                          "ai/findings/new-substrate-synthesis/drafts/guide-fixture-pipeline.md"))]
    (testing "marker absence means active shipped behaviour, never an S1 fallback"
      (is (str/includes? (one-line readme)
                         "Unmarked examples describe behaviour shipped on main."))
      (is (str/includes? (one-line pipeline)
                         "An unmarked fence is active because it describes current shipped behaviour; it does not imply S1."))
      (doseq [golden-row ["Guide 01 event-vector form | unmarked | active | S1 | frozen surface matrix"
                          "Guide 03 `sub` form | unmarked | active | S2 | frozen surface matrix"
                          "Guide 03 `local` form | `lands S3` | parked before S3 | S3 | explicit page marker"]]
        (is (str/includes? pipeline golden-row)
            (str "missing stage-classifier golden row: " golden-row)))
      (is (not (re-find #"(?i)(?:unmarked\s*=\s*(?:stage\s*)?1|else\s+(?:stage\s*)?1)"
                        pipeline))
          "restoring marker absence as the S1 fallback must fail"))
    (testing "the formerly Guide 09 S1 bridge adaptations retired when S2 shipped"
      (doseq [page [readme testing-guide]]
        (is (str/includes?
              (one-line page)
              "Guide 08's enrolled fixture covers the Tier-1 deftest, intent projection, dispatch-to-sub loop, seeded-state render, and sub-override door.")))
      (is (str/includes?
            pipeline
            "implementation/ui/test/re_frame/ui/test_guide08_fixture_jvm_test.clj"))
      (is (str/includes? pipeline ":adapted []"))
      (is (zero? (count (re-seq #":until\s+:s2" pipeline)))
          "no active formerly-Guide-09 adaptation may remain parked on shipped S2")
      (is (str/includes? (one-line pipeline)
                         "Guide 08 (formerly Guide 09) de-adaptation completed at S2")))))

(deftest s4-passages-stay-unmarked-shipped-behaviour
  ;; The S4 analogue of the `:until :s2` zero-occurrence tooth above
  ;; (rf2-krdzy). S4-A presence (rf2-uckeg) and S4-B custom-element
  ;; classification (rf2-vea1f) SHIPPED, so under the guide's own
  ;; `unmarked = shipped` law these three passages must carry no
  ;; future-stage marker. Reintroducing one reddens here.
  (let [views   (slurp-guide "02-views.md")
        testing-guide (slurp-guide "08-testing.md")]
    (testing "guide 02 — the presence and custom-element passages"
      (is (zero? (count (re-seq #"(?i)lands\s+S4" views)))
          "no Guide 02 passage may be parked on the shipped S4 stage")
      (is (re-find #"### Exit animations: `ui/presence`\s*\r?\n" views))
      (is (re-find #"### Custom elements\s*\r?\n" views))
      (is (not (str/includes? (one-line views)
                              "These land later and do not change the core view model"))
          "the §Advanced preamble must not re-park the shipped passages beneath it"))
    (testing "guide 08 — the flush-presence! passage"
      (is (zero? (count (re-seq #"(?i)lands\s+S4" testing-guide)))
          "no Guide 08 passage may be parked on the shipped S4 stage")
      (is (str/includes? (one-line testing-guide)
                         "Presence transitions advance with `(ui.test/flush-presence!)`, the presence twin of `flush!`")
          "the passage must read as shipped behaviour")
      (is (str/includes? (one-line testing-guide) "(flush-presence! ms)")
          "…and must state the ms-arity the fixture binds"))))

(deftest every-host-fence-declares-the-dom-target
  (let [fences (mapcat clojure-fences chapter-files)
        host-ids (->> fences (filter host-fence?) (map :id) set)
        dom-ids  (->> fences (filter dom-target?) (map :id) set)
        plan     (slurp (root-file
                          "ai/findings/new-substrate-synthesis/drafts/guide-docs-move-plan.md"))
        expected #{"01-getting-started.md#3"
                   "05-frames.md#1"
                   "05-frames.md#3"
                   "06-worked-app.md#6"
                   "08-testing.md#7"
                   "08-testing.md#8"
                   "11-ssr.md#2"
                   "11-ssr.md#3"
                   "11-ssr.md#4"}]
    (is (= expected host-ids)
        "the reviewed host-fence census changed; classify the changed fence explicitly")
    (is (= expected dom-ids)
        "every and only host-dependent fence must carry `;; guide:target dom`; structural fences default to JVM")
    (doseq [plan-row ["01 whole-app mount | `;; guide:target dom`"
                      "05 `frame-root` mount | `;; guide:target dom`"
                      "05 multi-frame page mount | `;; guide:target dom`"
                      "06 dashboard mount | `;; guide:target dom`"
                      "08 mounted read-only test | `;; guide:target dom`"
                      "08 mounted write test | `;; guide:target dom`"
                      "11 authored two-root mounts | `;; guide:target dom`"
                      "11 direct host-tier root | `;; guide:target dom`"
                      "11 hydration | `;; guide:target dom`"]]
      (is (str/includes? plan plan-row)
          (str "move-plan target census omits " plan-row)))))

(deftest simulated-docs-ui-relocation-keeps-every-guide-link-in-repo
  (let [source-prefix "../../../../docs/core/"
        expected-core-targets
        {"../../../../docs/core/introduction.md"     2
         "../../../../docs/core/app-db.md"           1
         "../../../../docs/core/subscriptions.md"    1
         "../../../../docs/core/effects.md"          2
         "../../../../docs/core/testing/index.md"    2
         "../../../../docs/core/where-state-lives.md" 2}
        plan      (slurp (root-file
                           "ai/findings/new-substrate-synthesis/drafts/guide-docs-move-plan.md"))
        all-pages (cons "README.md" chapter-files)
        targets   (for [page all-pages
                        target (markdown-targets (slurp-guide page))
                        :let [path (target-path target)]
                        :when (and (seq path)
                                   (not (str/starts-with? path "/"))
                                   (not (re-find #"^[a-z][a-z0-9+.-]*:" path)))]
                    [page path])
        core-targets (->> targets
                          (map second)
                          (filter #(str/starts-with? % source-prefix))
                          frequencies)]
    (is (= expected-core-targets core-targets)
        "core-link census changed without updating the S6 relocation plan")
    (doseq [source-target (keys expected-core-targets)]
      (let [moved-target (str "../core/" (subs source-target (count source-prefix)))]
        (is (str/includes? plan source-target)
            (str "move plan omits source target " source-target))
        (is (str/includes? plan moved-target)
            (str "move plan omits relocated target " moved-target))))
    (doseq [[page path] targets]
      (if (str/starts-with? path source-prefix)
        (let [moved-target (str "../core/" (subs path (count source-prefix)))
              destination  (.getCanonicalFile
                             (root-file (str "docs/ui/" moved-target)))]
          (is (.isFile destination)
              (str page " relocates inside the repo: " moved-target)))
        (is (.isFile (guide-file path))
            (str page " has a resolvable chapter link: " path))))))

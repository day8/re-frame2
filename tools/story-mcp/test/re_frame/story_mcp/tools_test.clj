(ns re-frame.story-mcp.tools-test
  "Per-tool semantics + the server dispatcher's `initialize` / `tools/list`
  / `tools/call` plumbing.

  A per-test fixture boots Story's canonical vocabulary and registers a
  small fixture story, variants, mode and decorators so each tool has
  something to read."
  (:require [cheshire.core :as cheshire]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.mcp-base.vocab :as rf.mcp-base.vocab]
            [re-frame.schemas :as rf.schemas]
            [re-frame.story :as rf.story]
            [re-frame.story.assertions :as rf.story.assertions]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.schemas :as rf.story.schemas]
            [re-frame.story-mcp.config :as rf.story-mcp.config]
            [re-frame.story-mcp.protocol :as rf.story-mcp.protocol]
            [re-frame.story-mcp.server :as rf.story-mcp.server]
            [re-frame.story-mcp.tools.args :as rf.story-mcp.tools.args]
            [re-frame.story-mcp.tools.cljs-resolve :as rf.story-mcp.tools.cljs-resolve]
            [re-frame.story-mcp.tools.cursor :as rf.story-mcp.tools.cursor]
            [re-frame.story-mcp.tools.wire-pipeline :as rf.story-mcp.tools.wire-pipeline]
            [re-frame.story-mcp.tools.dev :as rf.story-mcp.tools.dev]
            [re-frame.story-mcp.tools.egress :as rf.story-mcp.tools.egress]
            [re-frame.story-mcp.tools.lifecycle :as rf.story-mcp.tools.lifecycle]
            [re-frame.story-mcp.tools.registry :as rf.story-mcp.tools.registry]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; ---- fixtures ------------------------------------------------------------

;; Per-variant classification accumulator: each `declare-sensitive!` /
;; `declare-large!` adds one path, and the helpers re-apply the FULL
;; accumulated config every time. Cleared per test by the fixture.
(def ^:private declared-class (atom {}))

(defn reset-story-and-config
  "A fresh Story registry with both operator gates closed (their documented
  defaults), and re-frame pinned to `plain-atom` so a run lands its
  assertion records where `read-failures` finds them."
  [t]
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (rf.story-mcp.config/set-allow-writes! false)
  (rf.story-mcp.config/set-allow-sensitive-reads! false)
  (rf.schemas/clear-schemas-by-frame!)
  (reset! declared-class {})
  ;; `re-frame.epoch` installs its capture hooks process-wide at ns-load, so
  ;; on any classpath that carries it `run-variant` would ship a full
  ;; per-event narrative past the token cap. Depth 0 pins the posture; it
  ;; no-ops when epoch is absent.
  (rf/configure! {:epoch-history {:depth 0}})
  (rf.story/reg-story :story.button
    {:doc       "A clickable button."
     :component :app.ui/button
     :tags      #{:dev :docs}
     :args      {:label "Click me"}})
  (rf.story/reg-variant :story.button/primary
    {:doc  "Primary button."
     :args {:label "Save"}
     :tags #{:dev}})
  (rf.story/reg-variant :story.button/secondary
    {:doc  "Secondary button."
     :args {:label "Cancel"}
     :tags #{:docs}})
  (rf.story/reg-mode :Mode.theme/dark
    {:doc  "Dark theme."
     :args {:theme :dark}})
  (rf.story/reg-decorator :dec.test/wrap-card
    {:kind :hiccup
     :doc  "Wrap the variant in a card."
     :wrap (fn [body _args] [:div.card body])})
  (rf.story/reg-decorator :dec.test/seed-cart
    {:kind          :frame-setup
     :doc           "Seed an empty cart at frame creation."
     :app-db-patch  {:cart {:items []}}})
  (rf.story/reg-decorator :dec.test/stub-http
    {:kind     :fx-override
     :doc      "Pin http effect to a known response."
     :fx-id    :http
     :response {:status 200 :body "ok"}})
  ;; `run-variant` resets the variant frame IN PLACE on each run, wiping the
  ;; frame's elision registry; the privacy tests wire this event into the
  ;; variant's `:setup` so the classification effects are re-applied on every
  ;; fresh run and bite at egress.
  (rf/reg-event
    ::reapply-frame-class
    (fn [{:keys [db]} [_ _frame-id classification-config]]
      (merge {:db db} classification-config)))
  (try
    (t)
    (finally
      (rf/configure! {:epoch-history {:depth 50}}))))

(use-fixtures :each reset-story-and-config)

;; ---- helpers -------------------------------------------------------------

(defn- invoke
  "Invoke a tool by name, with `:dedup false` by default so a dedup-eligible
  tool's `:structuredContent` comes back unwrapped. The wire-boundary dedup
  transform is pinned in `re-frame.story-mcp.tools.dedup-test`."
  [tool-name args]
  (rf.story-mcp.tools.wire-pipeline/invoke-tool tool-name (merge {:dedup false} args)))

(defn- success? [result]
  (and (map? result)
       (vector? (:content result))
       (not (true? (:isError result)))))

(defn- error? [result]
  (and (map? result)
       (true? (:isError result))))

(defn- run-loop-frames
  "Drive `run-loop!` over `in-text` (one JSON frame per line) and return the
  decoded response frames. stderr is captured to keep a green run quiet."
  [in-text]
  (let [sw (java.io.StringWriter.)]
    (binding [*err* (java.io.StringWriter.)]
      (rf.story-mcp.server/run-loop! (java.io.BufferedReader. (java.io.StringReader. in-text)) sw))
    (into [] (comp (filter seq) (map #(cheshire/parse-string % true))) (str/split-lines (str sw)))))

(defn- run-frames!
  "`run-loop-frames` behind a completed `initialize` handshake, whose own
  response is dropped."
  [in-text]
  (vec (rest (run-loop-frames
               (str "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{}}\n" in-text)))))

(def ^:private evidence-slots
  [:schema-violations :warnings :effects :sub-runs :renders :narrative])

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest typical-tokens-hint-on-every-tool
  ;; The registry asserts a positive integer on every entry at load time;
  ;; this pins that the `tools/list` projection carries it.
  (is (every? (comp pos-int? :typicalTokens) (rf.story-mcp.tools.registry/tool-descriptors))))

(deftest annotations-on-every-tool
  ;; Agent hosts auto-approve read-only tools and gate destructive ones. Only
  ;; the lifecycle-run tools run the author's events/fx, which can reach
  ;; external systems, so they alone are open-world; an absent openWorldHint
  ;; reads as open-world, so every other tool carries an explicit false.
  (let [ds         (rf.story-mcp.tools.registry/tool-descriptors)
        names-with (fn [hint] (set (keep #(when (true? (get-in % [:annotations hint])) (:name %)) ds)))]
    (is (= #{"get-story-instructions" "list-substrates" "list-stories" "get-story" "get-variant"
             "list-tags" "list-modes" "list-decorators" "list-assertions" "get-docs-markdown"
             "variant->edn" "explain-variant" "snapshot-identity" "read-a11y-violations" "read-failures"}
           (names-with :readOnlyHint)))
    (is (= #{"preview-variant" "run-variant" "register-variant" "unregister-variant"}
           (names-with :destructiveHint)))
    (is (= (into {} (map (fn [{n :name}] [n (contains? #{"run-variant" "preview-variant"} n)])) ds)
           (into {} (map (juxt :name (comp :openWorldHint :annotations))) ds)))))

;; ---------------------------------------------------------------------------
;; Code ↔ skill drift: the consuming skill leaf
;; `skills/re-frame2/references/tooling/story-mcp-loop.md` names tools in
;; prose — a count claim and a per-step catalogue table.
;; ---------------------------------------------------------------------------

(defn- artefact-root
  "The `tools/story-mcp/` artefact root, found from a classpath source
  resource so the repo-tree reads below work from any working directory."
  []
  (let [marker (io/resource "re_frame/story_mcp/protocol.cljc")]
    (if (and marker (= "file" (.getProtocol marker)))
      (-> (io/file (.toURI marker)) .getParentFile .getParentFile .getParentFile .getParentFile)
      (io/file "."))))

(def ^:private story-mcp-loop-leaf
  (delay (slurp (io/file (artefact-root) ".." ".." "skills" "re-frame2"
                         "references" "tooling" "story-mcp-loop.md"))))

(def ^:private number-words
  {"sixteen" 16 "seventeen" 17 "eighteen" 18 "nineteen" 19
   "twenty" 20 "twenty-one" 21 "twenty-two" 22 "twenty-three" 23})

(defn- skill-named-tools
  "Tool names in the leaf's catalogue table (the backticked token in each
  row's second cell), plus the two tools it names only in prose. Anchored on
  the table because the leaf also backticks tokens that are not tools."
  [leaf]
  (into (->> (re-seq #"(?m)^\|[^|]*\|\s*`([a-z][a-z0-9-]+(?:->[a-z]+)?)`\s*\|" leaf)
             (map second)
             set)
        ["get-story-instructions" "snapshot-identity"]))

(deftest skill-leaf-tool-names-match-registry
  (let [leaf      @story-mcp-loop-leaf
        reg-names (set (map :name rf.story-mcp.tools.registry/tool-registry))
        named     (skill-named-tools leaf)
        word      (some-> (re-find #"(?i)\b([a-z]+(?:-[a-z]+)?)\s+tools\s+across\b" leaf) second str/lower-case)]
    (is (seq named) "the catalogue table parsed; empty means its shape changed")
    (is (= #{} (set/difference named reg-names)) "every tool the leaf names exists in the registry")
    (is (= (count reg-names) (get number-words word))
        (str "the leaf's '" word " tools across' claim matches the registry size"))))

;; ---------------------------------------------------------------------------
;; Dev tools
;; ---------------------------------------------------------------------------

(deftest get-story-instructions-agrees-with-the-variant-schema-and-tag-vocabulary
  ;; Producer-derived: a slot the closed :rf/variant map refuses, or a tag set
  ;; other than the one Story pre-registers, reds here. Keywords inside
  ;; backtick spans name axes in the prose, not slots or tags.
  (let [text   (-> (invoke "get-story-instructions" {}) :content first :text)
        kws    (fn [s] (->> (str/replace (or s "") #"`[^`]*`" "")
                            (re-seq #":[a-z][a-z0-9?!>/-]*")
                            (map #(keyword (subs % 1)))
                            set))
        slots  (kws (second (re-find #"(?s)\(reg-variant [^{]*\{([^}]*)\}" text)))
        schema (set (map first (drop 2 (second rf.story.schemas/Variant))))]
    (is (seq slots) "the reg-variant slot line was found")
    (is (= #{} (set/difference slots schema)))
    (is (= (set/union rf.story.schemas/canonical-tags rf.story.schemas/canonical-state-tags)
           (kws (second (re-find #"(?s)ship pre-registered(.*?)`:!tag`" text)))))))

(deftest story-instructions-text-mentions-every-canonical-assertion
  ;; The onboarding text is hand-copied from the spec and names assertion ids
  ;; without their namespace; the registrar is the source of truth.
  (is (= [] (remove #(re-find (re-pattern (str "\\b" % "\\b")) rf.story-mcp.tools.dev/story-instructions-text)
                    (sort (map name (rf.story/canonical-assertion-ids)))))
      "canonical assertions missing from story-instructions-text"))

(deftest preview-variant-happy
  (let [s (:structuredContent (invoke "preview-variant" {:variant-id "story.button/primary"
                                                         :base-url   "http://localhost:8000/"}))]
    (is (= [:story.button/primary :pass] [(:variant-id s) (:status s)]) "no assertions ⇒ vacuously :pass")
    (is (re-find #"story\.button(/|%2F)primary" (:share-url s)))
    (is (some? (:lifecycle s)))))

;; The JVM stdio host cannot reach the CLJS substrate registry or a11y panel
;; state; a false-empty success would read as 'none registered' or 'zero
;; violations', so absence is a capability-unavailable error.

(deftest list-substrates-unavailable-on-jvm-host-is-error
  (let [r (invoke "list-substrates" {})]
    (is (error? r))
    (is (= {:rf.error   :rf.error/story-mcp-capability-unavailable
            :capability "substrate-registry"
            :tool       "list-substrates"}
           (select-keys (:structuredContent r) [:rf.error :capability :tool :substrates])))))

(deftest list-substrates-reached-provider-distinguishes-empty-from-absent
  (doseq [[registered expected] [[[] []] [[:uix :reagent] [:reagent :uix]]]]
    (binding [rf.story-mcp.tools.cljs-resolve/*substrate-provider* (fn [] registered)]
      (let [r (invoke "list-substrates" {})]
        (is (= [false expected] [(error? r) (:substrates (:structuredContent r))]))))))

;; ---------------------------------------------------------------------------
;; Docs tools
;; ---------------------------------------------------------------------------

(deftest list-stories-tag-filter
  ;; Only an absent `:tags` returns the whole catalogue: a supplied filter
  ;; always filters, and an unknown tag is reported, never widened over.
  (doseq [[tags stories ignored] [[nil                [:story.button] {}]
                                  [[":docz"]          []              {:ignored-tags [":docz"]}]
                                  [[":docs" ":docz"]  [:story.button] {:ignored-tags [":docz"]}]]]
    (testing (pr-str tags)
      (let [s (:structuredContent (invoke "list-stories" (if tags {:tags tags} {})))]
        (is (= [stories ignored] [(mapv :id (:stories s)) (select-keys s [:ignored-tags])])))))
  (is (nil? (find-keyword "docz")) "an unknown tag is never interned")
  (testing "a registry that fits one page is the bare shape, each story carrying its variant ids"
    (let [s (:structuredContent (invoke "list-stories" {}))]
      (is (= [2 {}] [(count (-> s :stories first :variants)) (select-keys s [:total :next-cursor])])))))

(deftest list-stories-scalar-tags-rejected
  ;; A bare string would otherwise be walked character by character into a
  ;; successful-looking wrong result.
  (let [r (invoke "list-stories" {:tags "docs"})]
    (is (= :rf.error/scalar-for-collection-arg (-> r :structuredContent :rf.error)))
    (is (re-find #"(?i):tags must be an array" (-> r :content first :text)))))

(deftest get-story-happy
  (let [s (:structuredContent (invoke "get-story" {:story-id "story.button"}))]
    (is (= [:story.button "A clickable button."] [(:id s) (-> s :body :doc)]))))

(deftest get-variant-descriptor-matches-the-raw-body-it-returns
  ;; The registrar stores a variant body RAW and the plan compiler is the
  ;; single merge authority (spec/017), so the descriptor must not promise a
  ;; resolved body.
  (rf.story/reg-variant* :story.button/child {:doc "child" :extends :story.button/primary})
  (let [body (-> (invoke "get-variant" {:variant-id "story.button/child"}) :structuredContent :body)
        desc (:description (rf.story-mcp.tools.registry/tool-by-name "get-variant"))]
    (is (= [:story.button/primary false] [(:extends body) (contains? body :args)])
        "the raw body: :extends intact, the parent's :args not inherited")
    (is (not (re-find #"(?i)already applied|resolved EDN|merged from" desc)) desc)
    (is (re-find #"NOT resolved" desc) desc)))

(deftest explain-variant-happy
  ;; On this no-run path the frame is non-live; the value slots must carry
  ;; the real resolved author data, not `:rf/redacted`.
  (let [s (:structuredContent (invoke "explain-variant" {:variant-id "story.button/primary"}))
        e (:explain s)]
    (is (= [:story.button/primary [:story.button/primary] []]
           [(:variant-id s) (:source-chain e) (:parent-chain e)]))
    (is (map? (:effective-args e)))
    (is (every? #(contains? e %) [:merge :required-runner]))))

(deftest lookup-tools-refuse-unknown-or-missing-ids
  ;; The tools share three lookup preludes in tools.args (`with-variant`,
  ;; `with-variant-id`, `with-story-id`); the no-intern tests below pin more
  ;; unknown ids.
  (doseq [[tool args text-re] [["run-variant"       {:variant-id "story.nope/missing"} #"not found"]
                               ["preview-variant"   {}                                 #"variant-id"]
                               ["get-docs-markdown" {}                                 #"story-id"]]]
    (let [r (invoke tool args)]
      (is (= [true true] [(error? r) (boolean (re-find text-re (-> r :content first :text)))])
          (str tool " " (pr-str args))))))

(deftest list-modes-returns-fixture-mode
  (is (= [{:id :Mode.theme/dark :args {:theme :dark}}]
         (map #(select-keys % [:id :args]) (-> (invoke "list-modes" {}) :structuredContent :modes)))))

(deftest list-decorators-projects-each-kind-safely
  ;; A `:wrap` closure cannot cross the wire; the projection carries a
  ;; `:has-wrap?` boolean in its place.
  (let [by-id (into {} (map (juxt :id identity)) (-> (invoke "list-decorators" {}) :structuredContent :decorators))]
    (is (= {:kind :hiccup :has-wrap? true}
           (select-keys (by-id :dec.test/wrap-card) [:kind :has-wrap? :wrap])))
    (is (= {:kind :frame-setup :app-db-patch {:cart {:items []}}}
           (select-keys (by-id :dec.test/seed-cart) [:kind :app-db-patch])))
    (is (= {:kind :fx-override :fx-id :http :response {:status 200 :body "ok"}}
           (select-keys (by-id :dec.test/stub-http) [:kind :fx-id :response])))))

(deftest list-decorators-kind-filter
  (is (= #{:hiccup}
         (set (map :kind (-> (invoke "list-decorators" {:kind "hiccup"}) :structuredContent :decorators))))))

(deftest list-assertions-registered-covers-plan-compiler-vocabulary
  ;; `:registered` advertises the full vocabulary the plan compiler validates
  ;; authored assertion atoms against.
  (is (= (set rf.story.assertions/known-assertion-ids)
         (set (-> (invoke "list-assertions" {}) :structuredContent :registered)))))

(deftest variant-edn-roundtrips
  ;; The text slot is byte-stable EDN; the descriptor declares an
  ;; outputSchema, so the structured slot carries the same body.
  (let [r (invoke "variant->edn" {:variant-id "story.button/primary"})]
    (is (= ["Primary button." "Primary button."]
           [(:doc (edn/read-string (-> r :content first :text))) (-> r :structuredContent :doc)]))))

(deftest get-docs-markdown-renders-story-and-variants
  (let [s  (:structuredContent (invoke "get-docs-markdown" {:story-id "story.button"}))
        md (:markdown s)]
    (is (= :story.button (:story-id s)))
    (is (every? #(re-find % md)
                [#"^# Story `:story\.button`" #"A clickable button\." #":story\.button/primary" #"Primary button\."])
        md)))

;; ---------------------------------------------------------------------------
;; Pagination on the Docs `list-*` tools. spec/Principles.md §'Tight token
;; budget': every read tool whose return size grows with the registry
;; accepts `:limit` + `:cursor`.
;; ---------------------------------------------------------------------------

(deftest list-stories-paginates-when-over-limit
  (doseq [n (range 4)]
    (rf.story/reg-story (keyword (str "story.pager" n)) {:doc "" :component :app/x :tags #{:dev}}))
  (let [page (fn [cursor] (:structuredContent (invoke "list-stories" (cond-> {:limit 2} cursor (assoc :cursor cursor)))))
        s1   (page nil)
        s3   (page (:next-cursor (page (:next-cursor s1))))]
    (is (= [2 5 2 true] [(count (:stories s1)) (:total s1) (:limit s1) (:has-more? s1)]))
    (is (string? (:next-cursor s1)))
    (is (= [1 false nil] [(count (:stories s3)) (:has-more? s3) (:next-cursor s3)])
        "the final page holds the remaining entry and mints no cursor")))

(deftest list-stories-stale-cursor-returns-error
  (doseq [n (range 3)]
    (rf.story/reg-story (keyword (str "story.stale" n)) {:doc "" :component :app/x :tags #{:dev}}))
  (let [cursor (-> (invoke "list-stories" {:limit 1}) :structuredContent :next-cursor)]
    (rf.story/reg-story :story.intruder {:doc "" :component :app/x :tags #{:dev}})
    (let [r (invoke "list-stories" {:limit 1 :cursor cursor})]
      (is (= [true {:reason :rf.mcp/cursor-stale :tool "list-stories"}]
             [(:isError r) (select-keys (:structuredContent r) [:reason :tool])])))))

(deftest list-stories-limit-clamped-to-max
  (doseq [n (range 250)]
    (rf.story/reg-story (keyword (str "story.clamp" n)) {:doc "" :component :app/x :tags #{:dev}}))
  ;; `:max-tokens 0` lifts the response cap, which a 200-entry page exceeds:
  ;; the overflow marker carries no `:stories` at all.
  (let [s (:structuredContent (invoke "list-stories" {:limit 99999 :max-tokens 0}))]
    (is (= [rf.story-mcp.tools.cursor/max-limit true] [(count (:stories s)) (:has-more? s)]))))

(deftest list-modes-paginates
  (doseq [n (range 35)]
    (rf.story/reg-mode (keyword "Mode.pager" (str "m" n)) {:doc "" :args {}}))
  (let [s (:structuredContent (invoke "list-modes" {:limit 10}))]
    (is (= [10 true true] [(count (:modes s)) (:has-more? s) (string? (:next-cursor s))]))))

(deftest list-decorators-pagination-preserves-kind-filter
  ;; The ids sort after the fixture's other kinds, so a page cut before the
  ;; filter would carry them.
  (doseq [n (range 30)]
    (rf.story/reg-decorator (keyword (str "zz.page/h" n)) {:kind :hiccup :doc "" :wrap (fn [child] child)}))
  (let [s (:structuredContent (invoke "list-decorators" {:kind "hiccup" :limit 5}))]
    (is (= [5 #{:hiccup} true] [(count (:decorators s)) (set (map :kind (:decorators s))) (:has-more? s)]))))

(deftest list-tags-all-is-full-catalogue-under-pagination
  ;; `:all` is canonical ∪ ALL custom tags, not canonical plus the current
  ;; `:custom` page: an agent reading `:all` from page one would otherwise
  ;; believe it had the whole catalogue.
  (doseq [n (range 50)] (rf.story/reg-tag (keyword (str "tag/pager" n)) {:doc ""}))
  (let [s      (:structuredContent (invoke "list-tags" {:limit 5}))
        canon  (set/union rf.story.schemas/canonical-tags rf.story.schemas/canonical-state-tags)
        custom (set (map #(keyword (str "tag/pager" %)) (range 50)))]
    (is (= [5 true canon] [(count (:custom s)) (:has-more? s) (set (:canonical s))]))
    (is (= (set/union canon custom) (set (:all s))))
    (is (= (+ (count canon) 50) (count (:all s))) ":all carries no duplicates")))

(deftest list-assertions-canonical-doc-stays-full
  ;; The canonical assertion-doc vector is a bounded reference and never
  ;; paginates; `:registered` honours `:limit`.
  (let [full  (:structuredContent (invoke "list-assertions" {}))
        paged (:structuredContent (invoke "list-assertions" {:limit 3}))]
    (is (= (:canonical full) (:canonical paged)))
    (is (= [true 3] [(boolean (seq (:canonical full))) (count (:registered paged))]))))

;; ---------------------------------------------------------------------------
;; Testing tools
;; ---------------------------------------------------------------------------

(deftest run-variant-happy
  (let [s (:structuredContent (invoke "run-variant" {:variant-id "story.button/primary"}))]
    (is (= [:story.button/primary :pass] [(:frame s) (:status s)]) "no assertions ⇒ vacuously :pass")
    (is (vector? (:checks s)))))

(deftest lifecycle-tools-refuse-with-no-adapter
  ;; Without the guard both tools would settle `:status :pass` over an empty
  ;; app-db: a success-shaped NON-RUN. The fixture installs plain-atom, so the
  ;; test removes that boot through core's cold-start seam and restores it.
  (try
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (doseq [tool-name ["run-variant" "preview-variant"]]
      (let [r (invoke tool-name {:variant-id "story.button/primary"})]
        (is (= [true {:rf.error :rf.error/no-adapter-installed
                      :tool     tool-name
                      :recovery :init-an-adapter-in-the-preloaded-namespace}]
               [(:isError r) (select-keys (:structuredContent r) [:rf.error :tool :recovery :status])])
            (str tool-name ": a refusal carrying no run verdict"))))
    (finally
      (rf/init! rf.substrate.plain-atom/adapter))))

(deftest run-variant-cannot-run-reachable
  ;; A causal expectation needs reactive evidence; under the headless runner
  ;; it fails closed to :cannot-run rather than passing vacuously.
  (rf.story/reg-variant* :story.cause/unrunnable
    {:doc "causal" :assertions [[:rf.assert/caused {:event :some/event :surface [:any] :min 1}]]})
  (is (= :cannot-run (-> (invoke "run-variant" {:variant-id "story.cause/unrunnable"}) :structuredContent :status))))

;; ---- run options: preview-variant, run-variant and snapshot-identity each
;; run the shared guard chain. A run option is refused rather than coerced or
;; dropped, because a dropped one answers for a DIFFERENT tuple than the one
;; asked for.

(def ^:private run-opts-tools ["preview-variant" "run-variant" "snapshot-identity"])

(defn- run-opts-result
  "The `:structuredContent` of `tool` called on story.button/primary with `args`."
  [tool args]
  (:structuredContent (invoke tool (merge {:variant-id "story.button/primary"} args))))

(deftest run-opts-wrongly-typed-arg-rejected
  ;; A scalar `:active-modes` would be walked character by character, and a
  ;; non-map `:cell-overrides` silently dropped.
  (doseq [[args error-id] [[{:active-modes "Mode.theme/dark"} :rf.error/scalar-for-collection-arg]
                           [{:cell-overrides "not-a-map"}     :rf.error/non-map-arg]]
          tool run-opts-tools]
    (is (= error-id (:rf.error (run-opts-result tool args))) (str tool " " args))))

(deftest run-opts-unknown-active-mode-rejected
  (doseq [tool run-opts-tools]
    (is (= {:rf.error :rf.error/story-mcp-unknown-active-mode :active-modes ["Mode.theme/darkk"]}
           (select-keys (run-opts-result tool {:active-modes ["Mode.theme/darkk"]}) [:rf.error :active-modes]))
        tool))
  (is (some #{":Mode.theme/dark"} (:registered (run-opts-result "run-variant" {:active-modes ["Mode.theme/darkk"]})))
      "the registered set rides too, derived live from the registry"))

(deftest run-opts-mixed-known-unknown-modes-reject-atomically
  ;; Running the known subset is exactly the different scenario the guard
  ;; refuses; only the unresolved id is reported.
  (is (= ["Mode.theme/nope"]
         (:active-modes (run-opts-result "run-variant" {:active-modes [":Mode.theme/dark" "Mode.theme/nope"]})))))

(deftest run-opts-unknown-cell-override-key-rejected
  (doseq [tool run-opts-tools]
    (is (= {:rf.error :rf.error/story-mcp-unknown-cell-override-key :cell-overrides ["lable"]}
           (select-keys (run-opts-result tool {:cell-overrides {"lable" "Override"}}) [:rf.error :cell-overrides]))
        tool))
  (is (some #{":label"} (:allowed (run-opts-result "run-variant" {:cell-overrides {"lable" "Override"}})))
      "the allowed set is derived from the variant's effective args"))

(deftest run-opts-valid-identifiers-still-run
  ;; The other half of the witness: a guard that refused everything would
  ;; pass the rejection tests above.
  (doseq [tool run-opts-tools]
    (is (success? (invoke tool {:variant-id     "story.button/primary"
                                :active-modes   [":Mode.theme/dark"]
                                :cell-overrides {"label" "Override"}}))
        tool)))

(deftest run-opts-mode-introduced-override-key-accepted-at-handler
  ;; `:theme` is not an arg of story.button/primary but :Mode.theme/dark
  ;; contributes it, and Story merges mode args before cell overrides, so the
  ;; guard derives the override allowlist UNDER the active modes.
  (is (success? (invoke "snapshot-identity" {:variant-id     "story.button/primary"
                                             :active-modes   [":Mode.theme/dark"]
                                             :cell-overrides {"theme" ":light"}})))
  (is (= ["theme"] (:cell-overrides (run-opts-result "snapshot-identity" {:cell-overrides {"theme" ":light"}})))
      "without the mode, :theme is not an effective arg and is refused"))

(deftest run-opts-rejection-does-not-intern
  (let [mode-probe (str "Mode.rf2-sw1d/unknown-" (System/nanoTime))
        co-probe   (str "rf2-sw1d-co-" (System/nanoTime))]
    (invoke "run-variant" {:variant-id "story.button/primary" :active-modes [mode-probe]})
    (invoke "snapshot-identity" {:variant-id "story.button/primary" :cell-overrides {co-probe "x"}})
    (is (= [nil nil] [(find-keyword mode-probe) (find-keyword co-probe)]))))

(deftest read-a11y-violations-unavailable-on-jvm-host-is-error
  (let [r (invoke "read-a11y-violations" {:variant-id "story.button/primary"})]
    (is (error? r))
    (is (= {:rf.error   :rf.error/story-mcp-capability-unavailable
            :capability "a11y-panel-state"
            :tool       "read-a11y-violations"}
           (select-keys (:structuredContent r) [:rf.error :capability :tool :violations])))))

(deftest read-a11y-violations-carries-incomplete-beside-violations
  ;; axe-core's undecided checks ride beside `:violations`: an incomplete-only
  ;; frame is not a clean bill, and a host that cannot see them says nothing
  ;; rather than answering `[]`.
  (let [vios [{:id "label" :impact "critical" :nodes [{:html "<input>"}]}]
        incs [{:id "color-contrast" :impact "serious" :nodes [{:target ["h3"]}]}]
        read (fn [vio-map inc-map]
               (binding [rf.story-mcp.tools.cljs-resolve/*a11y-provider*            (fn [] vio-map)
                         rf.story-mcp.tools.cljs-resolve/*a11y-incomplete-provider* (when inc-map (fn [] inc-map))]
                 (select-keys (:structuredContent (invoke "read-a11y-violations" {:variant-id "story.button/primary"}))
                              [:violations :incomplete])))]
    (is (= {:violations vios :incomplete incs} (read {:story.button/primary vios} {:story.button/primary incs})))
    (is (= {:violations [] :incomplete incs} (read {} {:story.button/primary incs})))
    (is (= {:violations vios :incomplete []} (read {:story.button/primary vios} {:story.other/frame incs})))
    (is (= {:violations vios} (read {:story.button/primary vios} nil)))))

(deftest read-failures-empty-after-no-run
  (is (= {:total 0 :failures [] :assertions [] :status :pass}
         (select-keys (:structuredContent (invoke "read-failures" {:variant-id "story.button/primary"}))
                      [:total :failures :assertions :status]))))

(deftest self-healing-loop-failing-assertion-shape
  ;; The agent loop: register a variant whose assertion fails, run it, then
  ;; read the accumulated failures without re-running. Both reads carry the
  ;; same record, enough to localise the failure.
  (rf.story-mcp.config/set-allow-writes! true)
  (invoke "register-variant"
          {:variant-id "story.auth/sad"
           :body       (str "{:doc \"Deliberately-failing assertion.\""
                            " :script [[:dispatch-sync"
                            " [:rf.assert/path-equals [:auth :status] :authenticated]]]}")})
  (let [record   {:assertion :rf.assert/path-equals :status :fail :passed? false
                  :expected  :authenticated :actual nil :path [:auth :status]}
        keep-rec #(select-keys % (keys record))
        run      (:structuredContent (invoke "run-variant" {:variant-id "story.auth/sad"}))
        read     (:structuredContent (invoke "read-failures" {:variant-id "story.auth/sad"}))]
    (is (= [:fail [record]] [(:status run) (mapv keep-rec (:assertions run))]))
    (is (= [:story.auth/sad :fail 1 [record]]
           [(:variant-id read) (:status read) (:total read) (mapv keep-rec (:failures read))]))
    (is (string? (-> run :assertions first :reason)) "a human-readable reason rides the record")))

;; ---------------------------------------------------------------------------
;; Write surface
;; ---------------------------------------------------------------------------

(deftest write-tools-gated-by-default-name-the-caller
  ;; Both write tools refuse through one shared guard, which must stamp the
  ;; ACTUAL caller rather than a hard-coded name.
  (doseq [[tool args] [["register-variant"   {:variant-id "story.button/danger" :body {:doc "x"}}]
                       ["unregister-variant" {:variant-id "story.button/primary"}]]]
    (let [r (invoke tool args)]
      (is (= [true {:gated true :tool tool}] [(:isError r) (select-keys (:structuredContent r) [:gated :tool])])))))

;; The EDN-string `:body` reader is locked down: no tagged literals, no
;; custom readers, a 64KB size cap and a 64-level depth cap, each refused
;; before the registrar sees the value.

(defn- register-text
  "The response text of a `register-variant` call, writes allowed."
  [variant-id body]
  (rf.story-mcp.config/set-allow-writes! true)
  (-> (invoke "register-variant" {:variant-id variant-id :body body}) :content first :text))

(def ^:private edn-error #"(?i)must be a map or a valid EDN string")

(deftest register-variant-rejects-tagged-literal
  (is (re-find edn-error (register-text "story.button/tagged" "{:doc #my.app/widget {:x 1}}"))))

(deftest register-variant-rejects-reader-eval-form
  ;; `clojure.edn` never evaluates; this goes red if the body reader is
  ;; swapped for one that does.
  (is (re-find edn-error (register-text "story.button/eval" "{:doc #=(println \"PWNED\") :args {}}"))))

(def ^:private em-dash-3byte
  "U+2014 EM DASH: one UTF-16 code unit, three UTF-8 bytes."
  "—")

(defn- edn-doc-body
  "`{:doc \"<n copies of ch>\"}`: 9 ASCII characters around the copies."
  [ch n]
  (str "{:doc " (pr-str (apply str (repeat n ch))) "}"))

(deftest register-variant-rejects-oversize-multibyte-edn-body
  ;; The 64KB ceiling is a UTF-8 BYTE budget: 30000 em-dashes are 30009 code
  ;; units, under the cap, and 90009 bytes, over it.
  (is (re-find edn-error (register-text "story.button/oversize-multibyte" (edn-doc-body em-dash-3byte 30000)))))

(deftest register-variant-admits-ascii-body-of-the-same-code-unit-length
  ;; The control: the byte ruler only ever TIGHTENS, so an ASCII body of the
  ;; same code-unit length clears the size gate.
  (is (not (re-find edn-error (register-text "story.button/ascii-under-cap" (edn-doc-body "x" 30000))))))

(deftest register-variant-rejects-over-deep-edn-body
  (is (re-find edn-error (register-text "story.button/deep"
                                        (str (apply str (repeat 100 "{:a ")) "1" (apply str (repeat 100 "}")))))))

(deftest register-variant-rejects-bad-shape
  ;; The documented "Registration failed: " prefix.
  (is (re-find #"(?i)Registration failed" (register-text "story.button/bad" {:tags #{:nonexistent-tag}}))))

(deftest register-variant-rejects-non-map-body
  ;; A body that parses but is not a map takes its own branch and message.
  (is (re-find #"(?i):body must be a map" (register-text "story.button/vecbody" "[:not :a :map]"))))

;; Write-side no-intern: the write paths validate the id grammar on the
;; STRING shape and cap an object body's string-key width before any intern,
;; so a failed write leaves no keyword in the JVM keyword table.

(deftest register-variant-invalid-id-does-not-intern
  (rf.story-mcp.config/set-allow-writes! true)
  (let [r (invoke "register-variant" {:variant-id "not-story/tag30h-invalid-A" :body {:args {}}})]
    (is (= :rf.error/variant-id-shape (-> r :structuredContent :rf.error)))
    (is (nil? (find-keyword "not-story" "tag30h-invalid-A")))))

(deftest register-variant-wide-object-body-rejected-without-interning
  (rf.story-mcp.config/set-allow-writes! true)
  (let [distinctive "tag30h-wide-DISTINCTIVE-KEY"
        r           (invoke "register-variant"
                            {:variant-id "story.button/wide"
                             :body       (into {distinctive 1} (map (fn [i] [(str "k-tag30h-" i) i])) (range 2000))})]
    (is (= :rf.story-mcp/body-too-wide (-> r :structuredContent :rf.error)))
    (is (nil? (find-keyword distinctive)))))

(deftest register-variant-object-body-rejects-overdeep
  (rf.story-mcp.config/set-allow-writes! true)
  (let [probe (str "rf2-3luf3-deep-" (System/nanoTime))]
    (invoke "register-variant"
            {:variant-id "story.button/wire-deep"
             :body       (reduce (fn [acc i] {(str probe "-" i) acc}) {(str probe "-leaf") 1} (range 70))})
    (is (nil? (find-keyword (str probe "-leaf"))))))

(deftest register-variant-narrow-object-body-still-registers
  ;; The control: string keys under the width cap keywordise recursively,
  ;; and the success envelope carries the single `:registered?` spelling.
  (rf.story-mcp.config/set-allow-writes! true)
  (is (= {:variant-id :story.button/objform :registered? true}
         (:structuredContent (invoke "register-variant" {:variant-id "story.button/objform"
                                                         :body       {"doc" "object-form body" "args" {"label" "Go"}}}))))
  (is (= {:doc "object-form body" :args {:label "Go"}}
         (select-keys (rf.story/variant->edn :story.button/objform) [:doc :args]))))

(deftest unregister-variant-happy-when-allowed
  (rf.story-mcp.config/set-allow-writes! true)
  (is (true? (-> (invoke "unregister-variant" {:variant-id "story.button/primary"}) :structuredContent :unregistered?)))
  (is (nil? (rf.story/variant->edn :story.button/primary))))

(deftest record-as-variant-is-retired-method-not-found
  ;; A blocking recorder bridge would sleep the server's only dispatch loop,
  ;; so the catalogue carries no such tool and the name takes the unknown-tool
  ;; path.
  (is (= rf.mcp-base.vocab/code-method-not-found
         (-> (rf.story-mcp.server/dispatch {:jsonrpc "2.0" :id 41 :method "tools/call"
                                            :params  {:name "record-as-variant" :arguments {}}})
             :error :code))))

(deftest register-variant-overrides-caller-supplied-origin
  ;; spec/Cross-Cutting-Designs.md §5: every write surface tags its writes.
  (rf.story-mcp.config/set-allow-writes! true)
  (invoke "register-variant" {:variant-id "story.button/origin-override" :body {:doc "x" :origin :app}})
  (is (= :story-mcp (:origin (rf.story/variant->edn :story.button/origin-override)))
      "the write surface owns :origin; a caller cannot claim another"))

;; ---------------------------------------------------------------------------
;; Server dispatcher
;; ---------------------------------------------------------------------------

(deftest dispatch-initialize-handshake
  ;; The test classpath carries no VERSION resource, so :serverInfo :version
  ;; takes read-version's "dev" fallback.
  (is (= {:protocolVersion rf.story-mcp.config/protocol-version
          :serverInfo      {:name rf.story-mcp.config/server-name :version "dev"}
          :capabilities    {:tools {:listChanged false}}}
         (select-keys (:result (rf.story-mcp.server/dispatch {:jsonrpc "2.0" :id 1 :method "initialize"
                                                              :params  {:protocolVersion "2025-06-18"}}))
                      [:protocolVersion :serverInfo :capabilities]))))

(deftest dispatch-tools-list-returns-registry
  (is (= (map :name rf.story-mcp.tools.registry/tool-registry)
         (map :name (-> (rf.story-mcp.server/dispatch {:jsonrpc "2.0" :id 2 :method "tools/list"}) :result :tools)))))

(deftest dispatch-tools-call-non-map-arguments-is-invalid-params
  ;; A non-map `arguments` is a params-container failure (-32602), not the
  ;; -32603 an unguarded `(keys non-map)` would throw.
  (is (= rf.mcp-base.vocab/code-invalid-params
         (-> (rf.story-mcp.server/dispatch {:jsonrpc "2.0" :id 41 :method "tools/call"
                                            :params  {:name "list-tags" :arguments ["a" "b"]}})
             :error :code))))

(deftest dispatch-tools-call-absent-arguments-is-ok
  (is (some? (:result (rf.story-mcp.server/dispatch {:jsonrpc "2.0" :id 42 :method "tools/call"
                                                     :params  {:name "list-tags"}})))))

(deftest dispatch-malformed-envelope
  (is (= rf.mcp-base.vocab/code-invalid-request
         (-> (rf.story-mcp.server/dispatch {:method "tools/list" :id 5}) :error :code))))

(deftest dispatch-unknown-method
  (is (= rf.mcp-base.vocab/code-method-not-found
         (-> (rf.story-mcp.server/dispatch {:jsonrpc "2.0" :id 6 :method "nope/whatever"}) :error :code))))

(deftest dispatch-shutdown-empty-result
  ;; Not in the 2025-06-18 spec, but some hosts send it before closing stdin.
  (is (= {:jsonrpc "2.0" :id 8 :result {}}
         (rf.story-mcp.server/dispatch {:jsonrpc "2.0" :id 8 :method "shutdown"}))))

(deftest dispatch-tools-call-non-string-name-invalid-params
  ;; Distinct from the method-not-found an unknown STRING name takes.
  (is (= rf.mcp-base.vocab/code-invalid-params
         (-> (rf.story-mcp.server/dispatch {:jsonrpc "2.0" :id 9 :method "tools/call"
                                            :params  {:name 42 :arguments {}}})
             :error :code))))

;; Before a successful `initialize` the dispatcher accepts only `initialize`,
;; `ping` and notifications: a client must not enumerate or invoke tools
;; before the handshake.

(deftest dispatch-rejects-requests-before-initialize
  ;; The gate runs before the method case, so even an unknown method is -32600.
  (let [state (rf.story-mcp.server/new-lifecycle-state)]
    (is (= [rf.mcp-base.vocab/code-invalid-request rf.mcp-base.vocab/code-invalid-request]
           (map #(-> (rf.story-mcp.server/dispatch state {:jsonrpc "2.0" :id 1 :method %}) :error :code)
                ["tools/list" "nope/whatever"])))))

(deftest dispatch-allows-initialize-and-ping-before-handshake
  (let [state (rf.story-mcp.server/new-lifecycle-state)]
    (is (= {} (:result (rf.story-mcp.server/dispatch state {:jsonrpc "2.0" :id 5 :method "ping"}))))
    (is (false? (:initialized? @state)) "ping is stateless; only initialize flips the session")
    (rf.story-mcp.server/dispatch state {:jsonrpc "2.0" :id 6 :method "initialize" :params {}})
    (is (true? (:initialized? @state)))))

(deftest dispatch-allows-tools-immediately-after-initialize
  ;; The session is ready the moment the initialize response is built, without
  ;; `notifications/initialized`: a client pipelining initialize + tools/list
  ;; must not race a refusal.
  (let [state (rf.story-mcp.server/new-lifecycle-state)]
    (rf.story-mcp.server/dispatch state {:jsonrpc "2.0" :id 7 :method "initialize" :params {}})
    (is (vector? (-> (rf.story-mcp.server/dispatch state {:jsonrpc "2.0" :id 8 :method "tools/list"}) :result :tools)))))

(deftest dispatch-notifications-accepted-in-any-lifecycle-posture
  (let [state (rf.story-mcp.server/new-lifecycle-state)]
    (is (nil? (rf.story-mcp.server/dispatch state {:jsonrpc "2.0" :method "notifications/initialized"}))
        "accepted before the handshake, with no response")
    (is (false? (:initialized? @state)) "the notification is informational; initialize is the trigger")))

(deftest run-loop-rejects-pre-initialize-tool-calls
  ;; Over stdio: a tools/call as the FIRST frame is refused, then initialize
  ;; unlocks tools/list.
  (let [frames (run-loop-frames
                 (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                      "\"params\":{\"name\":\"list-tags\",\"arguments\":{}}}\n"
                      "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}\n"
                      "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}\n"))]
    (is (= [[1 rf.mcp-base.vocab/code-invalid-request false] [2 nil true] [3 nil true]]
           (map (juxt :id (comp :code :error) (comp some? :result)) frames)))
    (is (vector? (-> frames (nth 2) :result :tools)))))

(deftest run-loop-handles-multi-frame-session
  ;; The notification yields no response, so three frames answer four.
  (let [frames (run-loop-frames
                 (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                      "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                      "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                      "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                      "\"params\":{\"name\":\"list-tags\",\"arguments\":{}}}\n"))]
    (is (= [1 2 3] (map :id frames)))
    (is (= [true true] [(vector? (-> frames (nth 1) :result :tools)) (vector? (-> frames (nth 2) :result :content))]))))

(deftest run-loop-survives-parse-error
  (is (= [[nil rf.mcp-base.vocab/code-parse-error] [9 nil]]
         (map (juxt :id (comp :code :error))
              (run-loop-frames (str "{this is garbage\n" "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}\n"))))))

(deftest run-loop-survives-oversize-frame
  (is (= [[nil rf.mcp-base.vocab/code-parse-error] [11 nil]]
         (map (juxt :id (comp :code :error))
              (run-loop-frames (str (apply str (repeat (inc rf.story-mcp.protocol/max-frame-bytes) \x)) "\n"
                                    "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\"}\n"))))))

;; ---------------------------------------------------------------------------
;; Boot config
;; ---------------------------------------------------------------------------

(deftest boot-config-unknown-flag-logged-and-ignored
  ;; The MCP spec defines no CLI conventions, so an unknown flag is logged and
  ;; skipped, never swallowing a recognised flag beside it.
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (is (= {} (#'rf.story-mcp.server/parse-args ["--no-such-flag"])))
      (is (= {:allow-writes? true :allow-sensitive-reads? true}
             (#'rf.story-mcp.server/parse-args ["--bogus" "--allow-writes" "--also-bogus" "--allow-sensitive-reads"]))))
    (is (re-find #"unknown CLI flag" (str err)))))

(deftest read-boot-config-reads-each-gate-sysprop
  ;; The `false` polarity and the env fallback are pinned on resolve-gate below.
  (doseq [[prop slot] [["rf.story-mcp.allow-writes" :allow-writes?]
                       ["rf.story-mcp.allow-sensitive-reads" :allow-sensitive-reads?]]]
    (let [restore (System/getProperty prop)]
      (try
        (System/setProperty prop "true")
        (is (true? (slot (rf.story-mcp.config/read-boot-config))) prop)
        (finally
          (if restore (System/setProperty prop restore) (System/clearProperty prop)))))))

(deftest resolve-gate-explicit-sysprop-false-overrides-env-true
  ;; Resolved by SOURCE PRESENCE, not by OR-ing truthiness: an explicit
  ;; sysprop wins even as `false`, so an inherited env `true` cannot reopen a
  ;; gate an operator closed. Only an absent sysprop falls through to the env.
  (is (= [false true true false true]
         (map #(apply rf.story-mcp.config/resolve-gate %)
              [["false" "true"] [nil "true"] ["true" "false"] [nil nil] [nil "1"]]))))

(deftest lifecycle-timeout-ms-resolves-and-clamps
  ;; Both lifecycle tools share this resolver, which clamps rather than
  ;; rejects: a slow variant still runs and the single-threaded loop never
  ;; parks past the cap.
  (is (= [rf.story-mcp.tools.args/max-timeout-ms 5000
          rf.story-mcp.tools.args/default-timeout-ms rf.story-mcp.tools.args/default-timeout-ms]
         (map rf.story-mcp.tools.args/resolve-timeout-ms
              [{:timeout-ms 60000} {:timeout-ms 5000} {} {:timeout-ms "not-a-number"}]))))

;; ---------------------------------------------------------------------------
;; Wire-boundary token-budget cap: `invoke-tool` sizes BOTH wire slots and
;; replaces an over-budget response with `{:rf.mcp/overflow {...}}`.
;; ---------------------------------------------------------------------------

(defn- overflow-marker?
  "Does `result` carry the overflow marker in its structured slot, and the
  same marker as round-trippable EDN in its text slot?"
  [result]
  (and (= :reached (get-in result [:structuredContent rf.mcp-base.vocab/overflow-key :limit]))
       (= :reached (get-in (try (edn/read-string (-> result :content first :text)) (catch Throwable _ nil))
                           [rf.mcp-base.vocab/overflow-key :limit]))))

(deftest cap-fires-when-response-exceeds-budget
  (let [r    (rf.story-mcp.tools.wire-pipeline/invoke-tool "get-story-instructions" {:max-tokens 1})
        body (get-in r [:structuredContent rf.mcp-base.vocab/overflow-key])]
    (is (overflow-marker? r))
    (is (= [#{:limit :token-count :cap-tokens :tool :hint} 1 "get-story-instructions" nil]
           [(set (keys body)) (:cap-tokens body) (:tool body) (:isError r)])
        "exactly mcp-base's overflow-payload slots, and an over-cap SUCCESS stays non-error")))

(deftest cap-keeps-is-error-on-an-over-cap-failure
  ;; Without isError the marker is byte-for-byte an over-cap success, whose
  ;; hint invites a re-call.
  (let [r (rf.story-mcp.tools.wire-pipeline/invoke-tool "get-variant" {:variant-id "no.such/variant" :max-tokens 1})]
    (is (overflow-marker? r))
    (is (true? (:isError r)))))

(deftest cap-negative-max-tokens-rejected-not-overflow-lockout
  ;; A negative cap would trip on every response and lock the caller out
  ;; behind the overflow marker; the handler is never dispatched.
  (let [r (rf.story-mcp.tools.wire-pipeline/invoke-tool "list-tags" {:max-tokens -1})]
    (is (= [true {:arg :max-tokens :value -1}]
           [(:isError r) (select-keys (get-in r [:structuredContent rf.mcp-base.vocab/invalid-arg-key]) [:arg :value])]))))

(deftest cap-counts-the-structured-slot-beside-the-text
  ;; `edn-result` writes one payload into BOTH slots, so a cap the text slot
  ;; alone fits still trips once the structured slot is counted.
  (let [full       (rf.story-mcp.tools.wire-pipeline/invoke-tool "list-tags" {:max-tokens 0})
        text-tok   (quot (count (-> full :content first :text)) 4)
        struct-tok (quot (count (pr-str (:structuredContent full))) 4)
        capped     (rf.story-mcp.tools.wire-pipeline/invoke-tool "list-tags" {:max-tokens text-tok})]
    (is (= (+ text-tok struct-tok) (get-in capped [:structuredContent rf.mcp-base.vocab/overflow-key :token-count])))
    (is (not (overflow-marker? (rf.story-mcp.tools.wire-pipeline/invoke-tool "list-tags" {:max-tokens (+ text-tok struct-tok)})))
        "control: a cap both slots fit leaves the payload intact")))

;; ---------------------------------------------------------------------------
;; Wire-egress privacy posture (spec/Tool-Pair.md §Direct-read privacy
;; posture). A slot classified on the variant's frame surfaces as
;; `:rf/redacted` unless the operator gate is open AND the caller sends
;; `:include-sensitive true`.
;; ---------------------------------------------------------------------------

(defn- frame-container [variant-id]
  ((requiring-resolve 're-frame.frame/app-db-container) variant-id))

(defn- replace-frame-db! [variant-id new-db]
  ((requiring-resolve 're-frame.frame/swap-frame-db!) variant-id (constantly new-db)))

(defn- ensure-variant-frame!
  "Allocate `variant-id`'s frame up front (a run allocates it lazily), so a
  test can classify and seed it before the tool call."
  [variant-id]
  (when (nil? (frame-container variant-id))
    (rf/make-frame {:id         variant-id
                    :doc        (str "test frame for " variant-id)
                    :rf/story?  true
                    :rf/variant variant-id})))

(defn- destroy-variant-frame!
  "Frames outlive `rf.story/clear-all!`, so tear one down to keep its state
  out of the next test."
  [variant-id]
  (when (some? (frame-container variant-id))
    ((requiring-resolve 're-frame.frame/destroy-frame!) variant-id)))

(defn- classification-config
  "`variant-id`'s accumulated classification-effect map
  `{:sensitive [path ...] :large [path ...]}`, omitting an empty axis."
  [variant-id]
  (let [{:keys [sensitive large]} (get @declared-class variant-id)]
    (cond-> {}
      (seq sensitive) (assoc :sensitive (mapv vec sensitive))
      (seq large)     (assoc :large (mapv vec large)))))

(defn- declare-classification!
  "Classify `path` as `kind` on `variant-id`'s frame now, for readers that do
  not run the variant, and append a `:setup` step re-applying the full
  classification, because each fresh run resets the frame's runtime-db."
  [variant-id kind path]
  (swap! declared-class update-in [variant-id kind] (fnil conj #{}) (vec path))
  (ensure-variant-frame! variant-id)
  (let [config (classification-config variant-id)]
    (rf.frame/swap-runtime-db! variant-id (fn [rt] (rf.elision/apply-classification-effects rt config)))
    (when-let [body (rf.story.registrar/handler-meta :variant variant-id)]
      (rf.story.registrar/reg-variant* variant-id (update body :setup (fnil conj []) [::reapply-frame-class variant-id config])))))

(defn- declare-sensitive! [variant-id path]
  (declare-classification! variant-id :sensitive path))

(defn- declare-large! [variant-id path]
  (declare-classification! variant-id :large path))

(defn- seed-app-db!
  "Write `db` to `variant-id`'s live frame for readers that do not run it,
  and merge it into the variant's `:db-seed` so each fresh run re-seeds it."
  [variant-id db]
  (ensure-variant-frame! variant-id)
  (replace-frame-db! variant-id db)
  (when-let [body (rf.story.registrar/handler-meta :variant variant-id)]
    (rf.story.registrar/reg-variant* variant-id (update body :db-seed merge db))))

(defmacro ^:private with-clean-frame
  "Bind `vid` to `variant-kw`, run `body`, and tear the frame down on exit."
  [[vid variant-kw] & body]
  `(let [~vid ~variant-kw]
     (try ~@body
          (finally (destroy-variant-frame! ~vid)))))

;; EP-0025 FAIL-OPEN: the egress walk projects by PATH, so a value at a
;; classified app-db path redacts while a copy re-keyed to another tree
;; position ships raw — hygiene, not a guarantee. Core's `project-egress` owns
;; the walk; these tests pin story-mcp's orchestration around it.

(deftest scrub-rendered-empty-collection-non-live-frame-stays-empty
  ;; An empty tree carries nothing to protect on ANY frame. Through the
  ;; non-live fail-closed branch `[]` would become `:rf/redacted`, breaking the
  ;; `[:sequential :any]` shape the run-variant error branch's evidence slots
  ;; keep; a non-empty tree on a non-live frame still fails closed.
  (is (= [[] :rf/redacted]
         (map #(rf.story-mcp.tools.egress/scrub-rendered % nil :no/such-frame-xyz false) [[] [{:token "SECRET"}]]))))

(deftest scrub-rendered-large-value-re-keyed-ships-raw-fail-open
  ;; A live frame must not fail closed to make up for a re-keyed large value;
  ;; the `:app-db` path elision is pinned by run-variant-surfaces-elided-large-indicator.
  (with-clean-frame [vid :story.button/primary]
    (let [blob   (vec (range 5000))
          db     {:public "ok" :blob blob}
          hiccup [:div [:pre blob] [:span "label"]]]
      (seed-app-db! vid db)
      (declare-large! vid [:blob])
      (is (= hiccup (rf.story-mcp.tools.egress/scrub-rendered hiccup db vid false))))))

(deftest scrub-re-keyed-runtime-non-live-frame-ships-raw-under-named-exception
  ;; The path scrub is a no-op even on a live frame, so failing closed on a
  ;; non-live one would destroy the tool with zero leak-delta.
  (let [tree [[:auth/login "NONLIVE-REKEYED-SECRET"]]]
    (is (= tree (rf.story-mcp.tools.egress/scrub-re-keyed-runtime tree :story.nonlive/never-allocated false)))))

(deftest derived-slots-at-a-classified-path-honour-the-include-sensitive-opt-in
  ;; Each slot carries the secret AT a classified path — `[:token]` in the map
  ;; slots, `[0 :token]` in the vector evidence slots — so it redacts unless
  ;; the opt-in reaches that slot's own projection. The stub carries only
  ;; slots a real `rf.story/run-variant` can produce.
  (rf.story-mcp.config/set-allow-sensitive-reads! true)
  (with-clean-frame [vid :story.button/primary]
    (declare-sensitive! vid [:token])
    (declare-sensitive! vid [0 :token])
    (with-redefs [rf.story/run-variant
                  (fn [_vk _opts]
                    (java.util.concurrent.CompletableFuture/completedFuture
                      (merge {:status :pass :frame vid :lifecycle :ready :elapsed-ms 1
                              :app-db {} :assertions [] :checks []
                              :effective-args {:token "TOPSECRET"} :snapshot {:token "TOPSECRET"}}
                             (zipmap evidence-slots (repeat [{:token "TOPSECRET"}])))))]
      (doseq [[tool slots] [["run-variant"     (conj evidence-slots :snapshot)]
                            ["preview-variant" [:snapshot :effective-args]]]]
        (let [paths (map #(if (#{:snapshot :effective-args} %) [% :token] [% 0 :token]) slots)
              read  (fn [args]
                      (let [s (:structuredContent (invoke tool (merge {:variant-id "story.button/primary"} args)))]
                        (zipmap slots (map #(get-in s %) paths))))]
          (is (= (zipmap slots (repeat :rf/redacted)) (read {})) (str tool " redacts without the opt-in"))
          (is (= (zipmap slots (repeat "TOPSECRET")) (read {:include-sensitive true})) (str tool " ships raw with it")))))))

;; The lifecycle :timeout-ms ceiling must bound the SYNCHRONOUS Story work:
;; on the JVM a `[:wait]` step is an inline Thread/sleep, so run-variant
;; returns an already-settled future and a deadline on the deref alone would
;; be a no-op.

(deftest run-variant-synchronous-wait-is-bounded-and-honest
  (rf.story/reg-variant :story.button/slow-wait {:doc "slow" :script [[:wait 3000]]})
  (with-clean-frame [vid :story.button/slow-wait]
    (let [t0 (System/nanoTime)
          s  (:structuredContent (invoke "run-variant" {:variant-id "story.button/slow-wait" :timeout-ms 100}))
          ms (/ (double (- (System/nanoTime) t0)) 1e6)]
      (is (= :error (:status s)) "never a false :pass")
      (is (rf.story/valid-run-result? s) (str (rf.story/explain-run-result s)))
      (is (< ms 2000.0) (str "bounded near the 100ms ceiling, not the 3000ms wait; elapsed=" ms "ms")))))

(deftest preview-variant-synchronous-wait-is-bounded-and-honest
  (rf.story/reg-variant :story.button/slow-preview {:doc "slow" :script [[:wait 3000]]})
  (with-clean-frame [vid :story.button/slow-preview]
    (let [t0 (System/nanoTime)
          s  (:structuredContent (invoke "preview-variant" {:variant-id "story.button/slow-preview" :timeout-ms 100}))
          ms (/ (double (- (System/nanoTime) t0)) 1e6)]
      (is (= [:error :error] [(:status s) (:lifecycle s)]))
      (is (< ms 2000.0) (str "elapsed=" ms "ms")))))

(deftest lifecycle-error-outcome-is-canonical
  ;; One normalisation for a synchronous throw and a deadline, shared by both
  ;; lifecycle tools: routed through rf.story/run-result with every evidence
  ;; slot filled to [], because the frozen RunResult schema refuses a
  ;; present nil.
  (let [outcome (rf.story-mcp.tools.lifecycle/error-outcome :story.some/variant (ex-info "boom" {}))]
    (is (= [:error :error :story.some/variant :error]
           [(:status outcome) (:lifecycle outcome) (:frame outcome) (-> outcome :assertions first :status)]))
    (is (rf.story/valid-run-result? outcome) (str (rf.story/explain-run-result outcome)))
    (is (= (zipmap evidence-slots (repeat [])) (select-keys outcome evidence-slots)))))

(deftest lifecycle-error-outcome-surfaced-by-both-consumers
  ;; The worker future wraps a throw in an ExecutionException whose message is
  ;; the cause's toString, so only an exact :reason proves the wrapper was
  ;; peeled.
  (with-clean-frame [vid :story.button/primary]
    ;; The throw is reachable only after phase-0 allocation, so prime a live frame.
    (invoke "run-variant" {:variant-id "story.button/primary"})
    (with-redefs [rf.story/run-variant (fn [& _] (throw (ex-info "simulated run-variant boom" {})))]
      (doseq [tool ["run-variant" "preview-variant"]]
        (let [s (:structuredContent (invoke tool {:variant-id "story.button/primary"}))]
          (is (= [:error :error "simulated run-variant boom"]
                 [(:status s) (-> s :assertions first :status) (-> s :assertions first :reason)])
              tool)
          (when (= tool "preview-variant")
            (is (= :error (:lifecycle s)) "preview keeps its :lifecycle loader-state")))))
    (testing "a rejected Story future arrives wrapped twice, and both wrappers are peeled"
      (with-redefs [rf.story/run-variant (fn [& _] (java.util.concurrent.CompletableFuture/failedFuture
                                                      (ex-info "simulated Story rejection" {})))]
        (let [s (:structuredContent (invoke "run-variant" {:variant-id "story.button/primary"}))]
          (is (= [:error "simulated Story rejection"] [(:status s) (-> s :assertions first :reason)])))))))

(deftest explain-variant-no-run-non-live-frame-ships-value-slots-raw
  ;; explain-variant is a NO-RUN tool over static author data (spec/API.md
  ;; §explain-variant), reached before any run allocates the variant frame.
  ;; The framework egress boundary FAILS CLOSED on a non-live frame, so routing
  ;; the plan through it would redact every value slot.
  (destroy-variant-frame! :story.button/primary)
  (let [plan {:source-chain   [:story.button/primary]
              :effective-args {:api-key "DISTINCTIVE-NORUN-VALUE"}
              :network        {[:get "/api/me"] {:reply {:token "DISTINCTIVE-NORUN-VALUE"}}}
              :db-seed        {:auth {:token "DISTINCTIVE-NORUN-VALUE"}}
              :setup-order    [[:dispatch [:auth/login {:token "DISTINCTIVE-NORUN-VALUE"}]]]
              :script-order   [[:dispatch [:api/call {:key "DISTINCTIVE-NORUN-VALUE"}]]]}]
    (with-redefs [rf.story/explain (fn [_vk & _] plan)]
      (is (= plan (-> (invoke "explain-variant" {:variant-id "story.button/primary"}) :structuredContent :explain))))))

;; axe-core nodes carry the violating element's outerHTML, and
;; read-a11y-violations is readOnlyHint (hosts auto-approve it). The nodes are
;; a re-keyed runtime payload, scrubbed through `scrub-re-keyed-runtime`.

(deftest read-a11y-violations-re-keyed-html-ships-raw-fail-open
  ;; A value rendered into a node's :html sits at a DOM position the app-db
  ;; classification path cannot reach, so on a live frame it ships raw:
  ;; classify the app-db PATH to redact it before it reaches the DOM.
  (with-clean-frame [vid :story.button/primary]
    (seed-app-db! vid {:auth {:token "DISTINCTIVE-A11Y-SECRET"}})
    (declare-sensitive! vid [:auth :token])
    (let [vios [{:id "label" :impact "critical" :help "Form elements must have labels"
                 :nodes [{:html "DISTINCTIVE-A11Y-SECRET" :target ["#api-key-input"]}]}]]
      (binding [rf.story-mcp.tools.cljs-resolve/*a11y-provider* (fn [] {:story.button/primary vios})]
        (is (= vios (-> (invoke "read-a11y-violations" {:variant-id "story.button/primary"}) :structuredContent :violations)))))))

(deftest read-a11y-violations-node-at-a-classified-path-honours-the-include-sensitive-opt-in
  ;; A node value AT a classified path redacts on a live frame, so only the
  ;; threaded opt-in ships it, in both slots the re-keyed-runtime scrub covers.
  (rf.story-mcp.config/set-allow-sensitive-reads! true)
  (with-clean-frame [vid :story.button/primary]
    (declare-sensitive! vid [0 :nodes 0 :html])
    (let [nodes [{:id "label" :nodes [{:html "DISTINCTIVE-A11Y-SECRET"}]}]
          read  (fn [args]
                  (binding [rf.story-mcp.tools.cljs-resolve/*a11y-provider*            (fn [] {vid nodes})
                            rf.story-mcp.tools.cljs-resolve/*a11y-incomplete-provider* (fn [] {vid nodes})]
                    (let [s (:structuredContent (invoke "read-a11y-violations"
                                                        (merge {:variant-id "story.button/primary"} args)))]
                      (map #(get-in s [% 0 :nodes 0 :html]) [:violations :incomplete]))))]
      (is (= [:rf/redacted :rf/redacted] (read {})))
      (is (= ["DISTINCTIVE-A11Y-SECRET" "DISTINCTIVE-A11Y-SECRET"] (read {:include-sensitive true}))))))

;; Egress indicator counts (spec/Conventions.md §Cross-MCP indicator-field
;; vocabulary): a dropped sensitive record or an elided large value is
;; counted on the envelope, never silently swallowed.

(deftest read-failures-surfaces-dropped-sensitive-indicator
  (with-clean-frame [vid :story.button/primary]
    (seed-app-db! vid {:rf.story/assertions
                       [{:assertion :rf.assert/path-equals :passed? true}
                        {:assertion :rf.assert/path-equals :passed? false :sensitive? true :reason "secret"}
                        {:assertion :rf.assert/sub-equals :passed? false :sensitive? true :reason "secret"}]})
    (is (= {:total 1 :failures [] :status :pass :dropped-sensitive 2}
           (select-keys (:structuredContent (invoke "read-failures" {:variant-id "story.button/primary"}))
                        [:total :failures :status :dropped-sensitive]))
        "a dropped sensitive failure is counted and does not flip the verdict")))

(deftest read-failures-includes-sensitive-when-opted-in
  (rf.story-mcp.config/set-allow-sensitive-reads! true)
  (with-clean-frame [vid :story.button/primary]
    (seed-app-db! vid {:rf.story/assertions
                       [{:assertion :rf.assert/path-equals :passed? true}
                        {:assertion :rf.assert/path-equals :passed? false :sensitive? true :reason "x"}]})
    (let [s (:structuredContent (invoke "read-failures" {:variant-id "story.button/primary" :include-sensitive true}))]
      (is (= [2 1 :fail false]
             [(:total s) (count (:failures s)) (:status s) (boolean (some #(contains? s %) [:dropped-sensitive :elided-large]))])
          "both records survive, and both zero indicator counts are omitted"))))

(deftest run-variant-surfaces-elided-large-indicator
  (with-clean-frame [vid :story.button/primary]
    (seed-app-db! vid {:public "ok" :blob "a-big-uploaded-blob"})
    (declare-large! vid [:blob])
    (let [s (:structuredContent (invoke "run-variant" {:variant-id "story.button/primary"}))]
      (is (contains? (get-in s [:app-db :blob]) :rf.size/large-elided))
      (is (pos-int? (:elided-large s))))))

;; The tools that surface an OBSERVED-RUNTIME value and so accept the
;; `:include-sensitive` opt-in. explain-variant ships author data raw and is
;; not among them.
(def ^:private include-sensitive-tools
  ["preview-variant" "run-variant" "read-failures" "read-a11y-violations"])

(def ^:private api-md
  (delay (slurp (io/file (artefact-root) "spec" "API.md"))))

(defn- api-section
  "The `### \\`<tool-name>\\`` section of API.md, up to the next `### ` or
  `## ` heading."
  [tool-name]
  (let [doc     @api-md
        heading (str "### `" tool-name "`")]
    (when-let [start (str/index-of doc heading)]
      (let [after (subs doc (+ start (count heading)))
            end   (->> [(str/index-of after "\n### ") (str/index-of after "\n## ")] (remove nil?) (apply min Long/MAX_VALUE))]
        (if (= end Long/MAX_VALUE) after (subs after 0 end))))))

(deftest api-md-tracks-include-sensitive-descriptor-set
  ;; Derived from the live registry, so a tool that gains the slot must gain
  ;; the API.md mention too.
  (doseq [tname (->> rf.story-mcp.tools.registry/tool-registry
                     (filter #(contains? (-> % :inputSchema :properties) :include-sensitive))
                     (map :name)
                     sort)]
    (is (some-> (api-section tname) (str/includes? ":include-sensitive"))
        (str "API.md §" tname " must document the gated :include-sensitive slot"))))

;; The per-call `:include-sensitive` arg is honoured ONLY when the operator
;; opened the gate at boot (`--allow-sensitive-reads`). Closed, `tools/list`
;; omits the slot and the egress helpers ignore a caller who sends it anyway.

(defn- advertised-include-sensitive
  "Each include-sensitive tool's advertised slot type in `tools/list`, nil when absent."
  []
  (let [props (into {} (map (juxt :name (comp :properties :inputSchema))) (rf.story-mcp.tools.registry/tool-descriptors))]
    (map #(get-in (props %) [:include-sensitive :type]) include-sensitive-tools)))

(deftest tools-list-strips-include-sensitive-when-gate-closed
  (is (= [nil nil nil nil] (advertised-include-sensitive))))

(deftest tools-list-surfaces-include-sensitive-when-gate-open
  (rf.story-mcp.config/set-allow-sensitive-reads! true)
  (is (= ["boolean" "boolean" "boolean" "boolean"] (advertised-include-sensitive))))

(deftest app-db-slot-honours-the-include-sensitive-flag-only-through-the-open-gate
  ;; Every call sends :include-sensitive true; only the operator gate varies.
  (with-clean-frame [vid :story.button/primary]
    (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
    (declare-sensitive! vid [:secret])
    (doseq [tool            ["preview-variant" "run-variant"]
            [gate expected] [[false :rf/redacted] [true "TOPSECRET"]]]
      (rf.story-mcp.config/set-allow-sensitive-reads! gate)
      (is (= {:public "ok" :secret expected}
             (select-keys (:app-db (:structuredContent (invoke tool {:variant-id        "story.button/primary"
                                                                     :include-sensitive true})))
                          [:public :secret]))
          (str tool ", gate " (if gate "open" "closed"))))))

(deftest read-failures-gate-closed-ignores-per-call-flag
  (with-clean-frame [vid :story.button/primary]
    (seed-app-db! vid {:rf.story/assertions
                       [{:assertion :rf.assert/path-equals :passed? true}
                        {:assertion :rf.assert/path-equals :passed? false :sensitive? true :reason "leak"}]})
    (is (= 1 (:total (:structuredContent (invoke "read-failures" {:variant-id        "story.button/primary"
                                                                  :include-sensitive true})))))))

;; ---------------------------------------------------------------------------
;; The stdio frame cap is a UTF-8 BYTE budget, the DoS bound the error
;; message promises. Each oversize frame below is UNDER the cap in Java chars
;; and OVER it in bytes, so a char counter would admit it.
;; ---------------------------------------------------------------------------

(def ^:private cjk-3byte (String. (Character/toChars 0x4E2D)))
(def ^:private emoji-4byte (String. (Character/toChars 0x1F600)))

(defn- read-frame-error-id
  "The `:rf.error/id` `read-frame` throws on a one-line frame of `content`."
  [content]
  (try (rf.story-mcp.protocol/read-frame (java.io.BufferedReader. (java.io.StringReader. (str content "\n"))))
       ::read
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest read-frame-cap-counts-utf8-bytes-not-chars
  (is (= :rf.error/story-mcp-frame-too-large
         (read-frame-error-id (apply str (repeat (+ (quot rf.story-mcp.protocol/max-frame-bytes 2) 1000) cjk-3byte))))))

(deftest read-frame-cap-counts-supplementary-code-points
  ;; A surrogate pair is two chars but one 4-byte code point.
  (is (= :rf.error/story-mcp-frame-too-large
         (read-frame-error-id (apply str (repeat (+ (quot rf.story-mcp.protocol/max-frame-bytes 4) 1000) emoji-4byte))))))

(deftest read-frame-cap-accepts-multibyte-frame-under-byte-budget
  (let [method (str "ping-" cjk-3byte cjk-3byte emoji-4byte)
        frame  (rf.story-mcp.protocol/write-json {:jsonrpc "2.0" :method method :id 42})]
    (is (= {:jsonrpc "2.0" :method method :id 42}
           (rf.story-mcp.protocol/read-frame (java.io.BufferedReader. (java.io.StringReader. (str frame "\n"))))))))

;; ---------------------------------------------------------------------------
;; No-intern keyword resolution. Caller-supplied ids resolve against a bounded
;; set (`find-keyword`), never through `keyword`, which interns into the JVM's
;; process-global keyword table.
;; ---------------------------------------------------------------------------

(deftest get-story-unknown-id-does-not-intern
  (let [name-str (str "unknown-" (System/nanoTime))
        r        (invoke "get-story" {:story-id (str "story.rf2-lqjbk-probe/" name-str)})]
    (is (re-find #"(?i)story not found" (-> r :content first :text)))
    (is (nil? (find-keyword "story.rf2-lqjbk-probe" name-str)))))

(deftest read-failures-unknown-id-does-not-intern
  ;; read-failures resolves through `with-variant-id`, the prelude the
  ;; registered-but-never-run reads share.
  (let [name-str (str "rf-" (System/nanoTime))
        r        (invoke "read-failures" {:variant-id (str "story.rf2-lqjbk-probe/" name-str)})]
    (is (re-find #"(?i)variant not found" (-> r :content first :text)))
    (is (nil? (find-keyword "story.rf2-lqjbk-probe" name-str)))))

(deftest list-decorators-unknown-kind-rejects
  ;; A typo'd kind treated as no filter would hide the mistake behind a
  ;; successful-looking full catalogue.
  (let [name-str (str "rf2-cdavyf-kind-" (System/nanoTime))
        s        (:structuredContent (invoke "list-decorators" {:kind name-str}))]
    (is (= {:rf.error :rf.story-mcp/unknown-decorator-kind
            :kind     name-str
            :allowed  ["frame-setup" "fx-override" "hiccup"]}
           (select-keys s [:rf.error :kind :allowed])))
    (is (nil? (find-keyword name-str)))))

(deftest run-variant-explicit-substrate-unavailable-is-error-no-intern
  ;; A run under a silently-dropped substrate would be invalid
  ;; substrate-specific evidence.
  (let [name-str (str "rf2-lqjbk-sub-" (System/nanoTime))
        r        (invoke "run-variant" {:variant-id "story.button/primary" :substrate name-str})]
    (is (= :rf.error/story-mcp-capability-unavailable (-> r :structuredContent :rf.error)))
    (is (nil? (find-keyword name-str)))))

(deftest run-variant-explicit-substrate-reached-provider-validates
  (binding [rf.story-mcp.tools.cljs-resolve/*substrate-provider* (fn [] [:reagent :uix])]
    (is (success? (invoke "run-variant" {:variant-id "story.button/primary" :substrate ":reagent"})))
    (let [name-str (str "rf2-3fc89f-unknown-sub-" (System/nanoTime))]
      (is (= :rf.error/story-mcp-unknown-substrate
             (-> (invoke "run-variant" {:variant-id "story.button/primary" :substrate name-str}) :structuredContent :rf.error)))
      (is (nil? (find-keyword name-str))))))

;; ---------------------------------------------------------------------------
;; MCP JSON ingress parses string-keyed and keywordises only the finite
;; envelope keys and the bounded top-level argument allowlist, so an
;; attacker-controlled key never interns before the tool's own allowlist runs.
;; ---------------------------------------------------------------------------

(deftest ingress-does-not-intern-unknown-nested-arguments-key
  (let [probe  (str "rf2-3luf3-nested-probe-" (System/nanoTime))
        frames (run-frames! (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                                 "\"params\":{\"name\":\"get-variant\","
                                 "\"arguments\":{\"" probe "\":1,\"variant-id\":\"story.button/primary\"}}}\n"))]
    (is (= [1] (map :id frames)))
    (is (nil? (find-keyword probe)))))

(deftest ingress-does-not-intern-unknown-envelope-key
  (let [probe (str "rf2-3luf3-envelope-probe-" (System/nanoTime))]
    (run-frames! (str "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"" probe "\":99}\n"))
    (is (nil? (find-keyword probe)))))

(deftest read-run-opts-allows-active-mode-introduced-cell-override-key
  ;; The override allowlist is the variant's effective args UNDER the active
  ;; modes: :theme comes only from :Mode.theme/dark, so with that mode active a
  ;; :theme override is kept, while a genuinely unknown key is dropped
  ;; without interning.
  (let [probe (str "rf2-to3q7-co-" (System/nanoTime))]
    (is (= {:active-modes [:Mode.theme/dark] :cell-overrides {:theme ":light" :label "Override"}}
           (rf.story-mcp.tools.args/read-run-opts :story.button/primary
                                                  {:active-modes   [":Mode.theme/dark"]
                                                   :cell-overrides {"theme" ":light" "label" "Override" probe "x"}})))
    (is (nil? (find-keyword probe)))))

;; Nested override keys arrive as strings off the JSON wire. Resolving only
;; the top-level key would deep-merge `{"settings":{"title":"Edited"}}` BESIDE
;; the registered `:title`, so a consumer reading `[:settings :title]` would
;; see the old value and snapshot-identity would key the mixed-key tuple.

(defn- reg-nested-fixture! []
  (rf.story/reg-story :story.nest {:doc "Nested keyword-keyed args." :component :app.ui/panel :tags #{:dev}})
  (rf.story/reg-variant :story.nest/map-arg
    {:doc  "A variant whose arg value is a keyword-keyed map."
     :args {:settings {:title "Nested title" :enabled? true}}}))

(deftest read-run-opts-nested-override-never-interns-and-keeps-string-keyed-data
  ;; A nested key aligns only onto a key the base value already carries: one
  ;; naming nothing is data and rides verbatim, and a genuinely string-keyed
  ;; base map keeps its string keys.
  (reg-nested-fixture!)
  (rf.story/reg-variant :story.nest/string-keyed {:doc "String-keyed arg data." :args {:headers {"Accept" "text/html"}}})
  (let [probe     (str "rf2-49o8-nested-" (System/nanoTime))
        overrides (fn [vk co] (:cell-overrides (rf.story-mcp.tools.args/read-run-opts vk {:cell-overrides co})))]
    (is (= {:settings {probe "x"}} (overrides :story.nest/map-arg {"settings" {probe "x"}})))
    (is (nil? (find-keyword probe)))
    (is (= {:headers {"Accept" "application/json"}}
           (overrides :story.nest/string-keyed {"headers" {"Accept" "application/json"}})))))

(defn- nested-override-frame
  "One `tools/call` frame for `tool-name` carrying the nested override
  `{\"settings\":{\"title\":\"Edited\"}}` as real JSON."
  [id tool-name extra-json]
  (str "{\"jsonrpc\":\"2.0\",\"id\":" id ",\"method\":\"tools/call\","
       "\"params\":{\"name\":\"" tool-name "\","
       "\"arguments\":{\"variant-id\":\"story.nest/map-arg\","
       "\"cell-overrides\":{\"settings\":{\"title\":\"Edited\"}}"
       extra-json "}}}\n"))

(deftest ingress-nested-override-reaches-preview-variant
  ;; Through the real JSON decoder the edit lands on the KEYWORD :title and
  ;; leaves its sibling alone. Both spellings encode to the JSON member
  ;; "title", so this projection cannot see a mixed-key merge; the hash
  ;; control in ingress-nested-override-reaches-snapshot-identity does.
  (reg-nested-fixture!)
  (is (= {:settings {:title "Edited" :enabled? true}}
         (-> (run-frames! (nested-override-frame 71 "preview-variant" ",\"dedup\":false"))
             first :result :structuredContent :effective-args))))

(deftest ingress-nested-override-reaches-snapshot-identity
  ;; Without the untouched control, two agreeing hashes would prove only that
  ;; the override was dropped on BOTH routes.
  (reg-nested-fixture!)
  (let [content-hash #(-> % :structuredContent :content-hash)
        wire         (content-hash (-> (run-frames! (nested-override-frame 73 "snapshot-identity" "")) first :result))
        native       (content-hash (invoke "snapshot-identity" {:variant-id     "story.nest/map-arg"
                                                                :cell-overrides {:settings {:title "Edited"}}}))
        untouched    (content-hash (invoke "snapshot-identity" {:variant-id "story.nest/map-arg"}))]
    (is (some? wire))
    (is (= [true true] [(= native wire) (not= untouched wire)])
        "JSON ingress and the native call key ONE tuple, and the override perturbed it")))

(deftest ingress-unknown-variant-id-over-wire-does-not-intern
  (let [name-str (str "unknown-" (System/nanoTime))
        result   (-> (run-frames! (str "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                                       "\"params\":{\"name\":\"get-variant\","
                                       "\"arguments\":{\"variant-id\":\"story.rf2-3luf3-wire/" name-str "\"}}}\n"))
                     first :result)]
    (is (= [true true] [(:isError result) (boolean (re-find #"(?i)variant not found" (-> result :content first :text)))]))
    (is (nil? (find-keyword "story.rf2-3luf3-wire" name-str)))))

(deftest ingress-legitimate-wire-keys-still-dispatch
  ;; The control: known argument keys survive the no-intern normalisation.
  (let [frame (first (run-frames! (str "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                                       "\"params\":{\"name\":\"get-variant\","
                                       "\"arguments\":{\"variant-id\":\"story.button/primary\",\"max-tokens\":4000}}}\n")))]
    (is (= [5 "story.button/primary" "Primary button."]
           [(:id frame) (-> frame :result :structuredContent :id) (-> frame :result :structuredContent :body :doc)]))))

(defn- normalized-arguments
  "The `arguments` map of the `tools/call` JSON `frame` after the real ingress
  normalisation, unknown keys recorded as metadata."
  [frame]
  (-> frame rf.story-mcp.protocol/parse-json rf.story-mcp.protocol/normalize-frame :params :arguments))

(deftest invoke-tool-diagnoses-unknown-top-level-argument
  ;; A typo'd knob is an agent-recoverable error naming the key, the tool and
  ;; its allowed set, not a successful-looking call that silently defaulted.
  (let [probe (str "rf2-ovmc5e-typo-" (System/nanoTime))
        s     (:structuredContent
                (rf.story-mcp.tools.wire-pipeline/invoke-tool
                  "run-variant"
                  (normalized-arguments (str "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                                             "\"params\":{\"name\":\"run-variant\","
                                             "\"arguments\":{\"variant-id\":\"story.button/primary\",\"" probe "\":1}}}"))))]
    (is (= {:rf.error :rf.story-mcp/unknown-arguments :tool "run-variant" :unknown [probe]}
           (select-keys s [:rf.error :tool :unknown])))
    (is (every? (set (:allowed s)) ["variant-id" "timeout-ms"]) "the tool's own advertised property set")
    (is (nil? (find-keyword probe)))))

(deftest invoke-tool-rejects-tool-invalid-but-globally-known-arg
  ;; `:body` survives the global allowlist (register-variant advertises it),
  ;; so only the per-tool check stops get-variant silently ignoring it.
  (let [s (:structuredContent (rf.story-mcp.tools.wire-pipeline/invoke-tool
                                "get-variant" {:variant-id "story.button/primary" :body "x"}))]
    (is (= {:rf.error :rf.story-mcp/unknown-arguments :tool "get-variant" :unknown ["body"]}
           (select-keys s [:rf.error :tool :unknown])))
    (is (= [true false] [(contains? (set (:allowed s)) "variant-id") (contains? (set (:allowed s)) "body")]))))

;; The pre-dispatch rejections ride the response cap too (spec/Principles.md
;; §Tight token budget): a caller packing many long unknown keys inside the
;; 4 MB frame cap must not get them all echoed back uncapped. The capped
;; envelope keeps isError, which tells it from an over-cap success.

(deftest unknown-arg-error-rides-the-response-cap
  (let [big-keys (apply str (for [i (range 200)] (str "\"unknown-key-" i "-" (apply str (repeat 80 \x)) "\":1,")))
        arg-map  (normalized-arguments (str "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                                            "\"params\":{\"name\":\"get-variant\",\"arguments\":{" big-keys
                                            "\"variant-id\":\"story.button/primary\"}}}"))
        capped   (rf.story-mcp.tools.wire-pipeline/invoke-tool
                   "get-variant" (with-meta (assoc arg-map :max-tokens 5) (meta arg-map)))]
    (is (= ["get-variant" true] [(get-in capped [:structuredContent rf.mcp-base.vocab/overflow-key :tool]) (:isError capped)]))))

(deftest tool-invalid-arg-error-rides-the-response-cap
  (let [r (rf.story-mcp.tools.wire-pipeline/invoke-tool
            "get-variant" {:variant-id "story.button/primary" :max-tokens 1
                           :story-id "x" :substrate "x" :active-modes ["x"] :cell-overrides {}
                           :base-url "x" :body "x" :tags ["x"] :kind "x" :timeout-ms 1})]
    (is (= ["get-variant" true] [(get-in r [:structuredContent rf.mcp-base.vocab/overflow-key :tool]) (:isError r)]))))

;; ---------------------------------------------------------------------------
;; A `:checks` group carries the same record maps as the top-level
;; `:assertions` vec, so a record the egress drops must not ride back out
;; inside a group. Looked for in the ENCODED frame, both slots.
;; ---------------------------------------------------------------------------

(def ^:private check-private-sentinel "CHECK-PRIVATE-SENTINEL")

(defn- reg-check-private-fixture!
  "A variant whose setup seeds a `:sensitive? true` record, and whose TWO
  named checks both expand to the atom it matches — so one source record
  lands in both groups, and a per-group drop count would double-count it."
  []
  (rf/reg-event :story-mcp.test/seed-private-record
    (fn [{:keys [db]} _]
      {:db        (assoc db
                         :flag :yes
                         :rf.story/assertions
                         [{:assertion  :rf.assert/path-equals
                           :payload    [[:flag] :yes]
                           :passed?    false
                           :sensitive? true
                           :actual     check-private-sentinel
                           :expected   :yes
                           :reason     check-private-sentinel}])
       :sensitive [[:rf.story/assertions]]}))
  (rf.story/reg-check :check.button/private-a {:assertions [[:rf.assert/path-equals [:flag] :yes]]})
  (rf.story/reg-check :check.button/private-b {:assertions [[:rf.assert/path-equals [:flag] :yes]]})
  (rf.story/reg-variant :story.button/checked-private
    {:setup  [[:story-mcp.test/seed-private-record]]
     :checks [:check.button/private-a :check.button/private-b]}))

(defn- call-checked-private
  "One `tools/call` of `tool` on the checked-private variant through the real
  `run-loop!`; returns the decoded response frame."
  [tool args]
  (first (run-frames!
           (str (cheshire/generate-string
                  {:jsonrpc "2.0" :id 1 :method "tools/call"
                   :params  {:name      tool
                             :arguments (merge {:variant-id "story.button/checked-private" :max-tokens 0} args)}})
                "\n"))))

(deftest named-check-assertion-copies-honour-the-sensitive-filter
  (reg-check-private-fixture!)
  (doseq [tool ["run-variant" "preview-variant"] dedup [true false]]
    (let [frame (call-checked-private tool {:dedup dedup :include-sensitive true})]
      (is (= [nil false] [(:error frame) (str/includes? (pr-str frame) check-private-sentinel)])
          (str tool " dedup=" dedup ", gate closed: no copy of the dropped record crosses the wire"))))
  (let [s (get-in (call-checked-private "run-variant" {:dedup false :include-sensitive true}) [:result :structuredContent])]
    (is (= [1 #{"check.button/private-a" "check.button/private-b"} #{"fail"}]
           [(:dropped-sensitive s) (set (map :check (:checks s))) (set (map :status (:checks s)))])
        "one source record counted once; check identity and authoritative verdicts survive")
    (is (every? #(and (seq (:assertions %)) (not-any? :sensitive? (:assertions %))) (:checks s))
        "each group keeps its benign records and loses only the stamped one"))
  (testing "both gates open restores the record inside the groups"
    (rf.story-mcp.config/set-allow-sensitive-reads! true)
    (let [frame (call-checked-private "run-variant" {:dedup false :include-sensitive true})]
      (is (str/includes? (pr-str frame) check-private-sentinel))
      (is (every? #(some :sensitive? (:assertions %)) (get-in frame [:result :structuredContent :checks]))))))

(deftest run-loop-leaves-clojure-futures-usable-after-eof
  ;; The CLI's -main releases Clojure's executors at EOF; run-loop! is the
  ;; embeddable half and must leave them working for its caller.
  (let [frames (run-frames! (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                                 "\"params\":{\"name\":\"run-variant\",\"arguments\":"
                                 "{\"variant-id\":\"story.button/primary\",\"dedup\":false,\"max-tokens\":0}}}\n"))]
    (is (= "pass" (get-in (first frames) [:result :structuredContent :status]))
        "precondition: the session ran a variant on a worker future")
    (is (= 42 (deref (future 42) 5000 ::timed-out)))))

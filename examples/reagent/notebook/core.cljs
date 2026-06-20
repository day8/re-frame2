(ns notebook.core
  "Reagent design-led example — 'Notebook'. Three-pane editorial layout:

     [ documents tree ]  [ markdown editor ]  [ live preview ]

   Proves re-frame2 + Reagent can build a substantive UI. The data-flow
   is the canonical six dominoes:

     - selecting a document         dispatches  [:notebook/select id]
     - editing the body             dispatches  [:notebook/edit-body text]
     - the body sub                 derives     parsed-html from the markdown
     - the preview pane             subscribes  to that derivation

   Distinguished from the canonical login + counter examples by being
   `reg-view`-based at every layer, exercising multi-pane layout, and
   leaning into the shared 'Editorial Warm' visual identity from
   examples/_shared/css/style.css — one shared identity across all
   three substrates. No state machines, no HTTP — design-led examples
   exist to prove polished visuals + interaction, not to replay the
   platform features other examples already cover.

   Markdown rendering is intentionally a tiny pure-CLJS parser (headings,
   bold, italic, links, paragraphs, lists) — keeps the bundle small and
   the example free of an extra npm dependency."
  ;; Substrate note: STOCK Reagent (`reagent.dom.client` +
  ;; `re-frame.adapter.reagent`), like the rest of the `examples/reagent/`
  ;; catalogue. notebook is the Reagent member of the three-substrate
  ;; design-led trio (alongside `dashboard-uix` and
  ;; `process-monitor-helix`), so it sits on the reference Reagent
  ;; substrate to keep the trio comparable. (counter /
  ;; counter_slim_and_fast is the dedicated stock-vs-slim contrast pair;
  ;; the slim build is the only one that mounts `reagent-slim`, and it
  ;; lives under `examples/reagent-slim/`.)
  (:require [reagent.dom.client :as rdc]
            [clojure.string     :as str]
            [re-frame.core      :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; SEED DATA
;; ============================================================================

(def initial-documents
  [{:id    :welcome
    :title "Welcome"
    :body  "# Welcome to the notebook\n\nThis is a *design-led* example that demonstrates the **Reagent** substrate of re-frame2.\n\nIt lives at `examples/reagent/notebook/` and renders a three-pane editor.\n\n- Pick a document from the left.\n- Edit the body in the middle.\n- Read the preview on the right.\n\nVisit [day8.re-frame2](https://github.com/day8/re-frame2) for more."}
   {:id    :six-dominoes
    :title "The six dominoes"
    :body  "## The dominoes\n\n1. **Event** dispatch\n2. **Event** handler\n3. **Effects** application\n4. **Query** layer (subs)\n5. **View** layer\n6. **Re-render**\n\nThe re-frame2 spec lives in *spec/*.\n\nEach domino is *pure* in isolation. Only domino 3 touches the world."}
   {:id    :patterns
    :title "Patterns"
    :body  "### Patterns shipped with re-frame2\n\n- Pattern-Boot — application initialisation as a state machine.\n- Pattern-WebSocket — connection machine with backoff + queue-flush.\n- Pattern-LongRunningWork — :spawn-all spawn-and-join.\n- Pattern-Managed-HTTP — declarative HTTP with cancellation.\n\nEach has a worked example under `examples/reagent/`."}])

;; ============================================================================
;; EVENTS  (CP-1)
;; ============================================================================

(rf/reg-event :notebook/initialise
  (fn [{:keys [db]} _event]
    {:db {:notebook/documents   initial-documents
     :notebook/selected-id :welcome}}))

(rf/reg-event :notebook/select
  (fn [{:keys [db]} [_ id]]
    {:db (assoc db :notebook/selected-id id)}))

(rf/reg-event :notebook/edit-body
  (fn [{:keys [db]} [_ text]]
    {:db (let [id (:notebook/selected-id db)]
      (update db :notebook/documents
              (fn [docs]
                (mapv (fn [d]
                        (if (= (:id d) id) (assoc d :body text) d))
                      docs))))}))

;; EP-0010 (Causal World Inputs): the new document's id is written into durable
;; app-db (`:notebook/documents`), so it must be a function of prior frame-state
;; — never an ambient `(rand-int)` read at the durable-write site (a fresh
;; event-stream replay would mint a different id, breaking replay determinism).
;; So we allocate it deterministically from the existing documents, the todomvc
;; `allocate-next-id` idiom: scan the `doc-N` keyword ids already in app-db and
;; take max+1. Seeded ids (`:welcome`, `:six-dominoes`, …) carry no `doc-`
;; prefix, so they don't participate — the first minted id is `:doc-1`.
(defn- allocate-next-doc-id
  "Deterministic next `:doc-N` id from the existing documents — max prior
   N + 1 (1 when none yet). A pure function of prior app-db state, so it
   replays identically (EP-0010)."
  [documents]
  (let [used-ns (->> documents
                     (keep (fn [{id :id}]
                             (some->> (re-matches #"doc-(\d+)" (name id))
                                      second
                                      (js/parseInt))))
                     (apply max 0))]
    (keyword (str "doc-" (inc used-ns)))))

(rf/reg-event :notebook/new
  (fn [{:keys [db]} _event]
    {:db (let [id  (allocate-next-doc-id (:notebook/documents db))
          doc {:id id :title "Untitled" :body "# Untitled\n\nStart writing…"}]
      (-> db
          (update :notebook/documents (fnil conj []) doc)
          (assoc :notebook/selected-id id)))}))

;; ============================================================================
;; SUBSCRIPTIONS  (CP-2)
;; ============================================================================

(rf/reg-sub :notebook/documents
  (fn [db _] (:notebook/documents db)))

(rf/reg-sub :notebook/selected-id
  (fn [db _] (:notebook/selected-id db)))

(rf/reg-sub :notebook/selected
  :<- [:notebook/documents]
  :<- [:notebook/selected-id]
  (fn [[docs id] _]
    (first (filter #(= (:id %) id) docs))))

(rf/reg-sub :notebook/selected-body
  :<- [:notebook/selected]
  (fn [doc _] (or (:body doc) "")))

;; ============================================================================
;; MARKDOWN — tiny pure parser → hiccup
;; ============================================================================
;;
;; The parser emits hiccup (vectors of keywords + child vectors) rather
;; than raw HTML strings — Reagent renders hiccup natively without the
;; `dangerouslySetInnerHTML` escape hatch, and the example stays free of
;; an extra npm dependency.

(def ^:private safe-link-schemes
  "Allowlist of URI schemes a markdown link may carry in the preview.
   Anything else (notably `javascript:` and `data:`) is treated as
   unsafe and rendered as inert text rather than a clickable anchor."
  #{"http" "https" "mailto"})

(defn- safe-href
  "Return `href` when it is safe to put on an `:a {:href ...}` in the
   preview, else nil. Safe = an allowlisted absolute scheme, or a
   scheme-less link (relative path or `#fragment`). Scheme detection
   matches a leading `word:` prefix per RFC 3986 §3.1; a `:` that only
   appears after a `/`, `?` or `#` is part of the path/query/fragment,
   not a scheme, so such links are scheme-less and allowed."
  [href]
  (let [h (str/trim (or href ""))
        ;; A scheme is `ALPHA *( ALPHA / DIGIT / + / - / . ) :` at the
        ;; very start. If the first `:` is preceded by a `/`, `?` or `#`
        ;; it is not a scheme delimiter.
        m      (re-find #"(?i)^([a-z][a-z0-9+.-]*):" h)
        scheme (some-> m second str/lower-case)]
    (cond
      ;; No leading scheme → relative path or fragment → safe.
      (nil? scheme) href
      ;; Allowlisted absolute scheme → safe.
      (contains? safe-link-schemes scheme) href
      ;; Anything else (javascript:, data:, vbscript:, …) → unsafe.
      :else nil)))

(defn- split-by-regex
  "Walk `coll` (a flat seq of strings + hiccup-vectors). For each
   string element, find non-overlapping matches of `re`; each match
   produces a hiccup element via `(mk match)`. Return a new flat seq
   with strings split around matches and matches replaced. Non-string
   elements (already-parsed hiccup) pass through untouched.

   Robust to the (count parts) ≠ (count matches) ± 1 trap that
   bit the first pass: we iterate `(re-seq)` and pull `lastIndex`-
   style substrings from the source manually."
  [coll re mk]
  (mapcat
    (fn [x]
      (if-not (string? x)
        [x]
        (loop [s x, out (transient [])]
          (if-let [m (re-find re s)]
            (let [whole (if (sequential? m) (first m) m)
                  idx   (.indexOf s whole)
                  before (subs s 0 idx)
                  after  (subs s (+ idx (count whole)))
                  out'   (cond-> out
                           (seq before) (conj! before)
                           true         (conj! (mk m)))]
              (recur after out'))
            (let [out' (cond-> out (seq s) (conj! s))]
              (persistent! out'))))))
    coll))

(defn- inline-md->hiccup
  "Parse one paragraph string into a hiccup seq with inline runs
   (links, bold, italic, code) split out. Output is a flat seq the
   caller splices into a parent block element. Rules run sequentially;
   later passes only see plain-text fragments from earlier passes
   (already-emitted hiccup vectors pass through untouched)."
  [s]
  (-> [s]
      (split-by-regex #"\[([^\]]+)\]\(([^)]+)\)"
                      (fn [m]
                        (let [text (nth m 1)]
                          ;; Sanitize the destination: only allowlisted
                          ;; schemes (and scheme-less relative links)
                          ;; become clickable anchors. Disallowed schemes
                          ;; — `javascript:`, `data:`, … — render as inert
                          ;; text so the preview never exposes an unsafe
                          ;; clickable link on this user-controlled surface.
                          (if-let [href (safe-href (nth m 2))]
                            [:a {:href   href
                                 :rel    "noopener noreferrer"
                                 :target "_blank"}
                             text]
                            [:span.nb-unsafe-link text]))))
      (split-by-regex #"\*\*([^*]+)\*\*"
                      (fn [m] [:strong (nth m 1)]))
      (split-by-regex #"\*([^*]+)\*"
                      (fn [m] [:em (nth m 1)]))
      (split-by-regex #"`([^`]+)`"
                      (fn [m] [:code (nth m 1)]))))

(defn- render-block [block]
  (cond
    (str/starts-with? block "### ")
    (into [:h3] (inline-md->hiccup (subs block 4)))

    (str/starts-with? block "## ")
    (into [:h2] (inline-md->hiccup (subs block 3)))

    (str/starts-with? block "# ")
    (into [:h1] (inline-md->hiccup (subs block 2)))

    (str/starts-with? block "- ")
    (into [:ul]
          (for [line (str/split-lines block)
                :when (str/starts-with? line "- ")]
            (into [:li] (inline-md->hiccup (subs line 2)))))

    (re-matches #"(?s)\d+\.\s.*" block)
    (into [:ol]
          (for [line (str/split-lines block)
                :let [m (re-matches #"\d+\.\s+(.*)" line)]
                :when m]
            (into [:li] (inline-md->hiccup (second m)))))

    :else
    (into [:p] (inline-md->hiccup block))))

(defn markdown->hiccup
  "Tiny pure-CLJS markdown parser: splits on blank lines, dispatches per
   block-shape, runs inline rules. Sufficient for the example's seed
   content. Returns a vector of block hiccup that the caller splices
   into a parent container (Reagent renders this as a seq of children;
   each block gets a content-derived :key so React preserves block
   identity across edits that insert/remove/reorder blocks — an index
   :key would re-key every block below an edit). Identical blocks are
   disambiguated by their position so the keys stay unique."
  [s]
  (let [blocks (->> (str/split (or s "") #"\r?\n\r?\n")
                    (remove str/blank?))]
    (vec
      (map-indexed
        (fn [i b] (with-meta (render-block b) {:key (str (hash b) "-" i)}))
        blocks))))

(rf/reg-sub :notebook/selected-hiccup
  :<- [:notebook/selected-body]
  (fn [body _] (markdown->hiccup body)))

;; ============================================================================
;; VIEWS  (CP-4) — shared 'Editorial Warm' palette
;; ============================================================================

(reg-view sidebar []
  (let [docs        @(subscribe [:notebook/documents])
        selected-id @(subscribe [:notebook/selected-id])]
    [:nav.nb-sidebar
     [:header.nb-sidebar-header
      [:h2 "Notebook"]
      [:button.nb-new
       {:on-click    #(dispatch [:notebook/new])
        :data-testid "notebook-new"}
       "+ New"]]
     [:ul.nb-doc-list
      (for [d docs]
        ^{:key (:id d)}
        [:li
         [:button.nb-doc-link
          {:class       (when (= (:id d) selected-id) "active")
           :data-testid (str "notebook-doc-" (name (:id d)))
           :on-click    #(dispatch [:notebook/select (:id d)])}
          [:span.nb-doc-title (:title d)]
          [:span.nb-doc-meta  (count (or (:body d) "")) " chars"]]])]
     [:footer.nb-sidebar-footer
      [:span "Reagent substrate"]]]))

(reg-view editor []
  (let [body @(subscribe [:notebook/selected-body])
        sel  @(subscribe [:notebook/selected])]
    [:section.nb-editor
     [:header.nb-pane-header
      [:span.nb-eyebrow "Editor"]
      [:span.nb-doc-title (or (:title sel) "—")]]
     [:textarea.nb-textarea
      {:value       body
       :data-testid "notebook-textarea"
       :spellCheck  "false"
       :on-change   #(dispatch [:notebook/edit-body (.. % -target -value)])}]]))

(reg-view preview []
  (let [blocks @(subscribe [:notebook/selected-hiccup])]
    [:section.nb-preview
     [:header.nb-pane-header
      [:span.nb-eyebrow "Preview"]
      [:span "Live"]]
     (into [:article.nb-rendered {:data-testid "notebook-preview"}]
           (or blocks []))]))

(reg-view notebook []
  [:div.nb-shell
   [sidebar]
   [editor]
   [preview]])

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; Lazy mount: defer `create-root` to `run` so ns-load is DOM-side-effect-free.
;; This is the mount-isolation convention documented at
;; [examples/TESTING.md §Example mount-isolation convention] — multiple example
;; namespaces co-exist in the `:browser-test` bundle and share one DOM, so a
;; ns-load `create-root` would race roots onto the same container.

(defonce react-root (atom nil))

;; EP-0002: under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. `init!` installs the adapter (it does NOT create the frame),
;; `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in a `frame-provider` so every
;; in-tree `dispatch`/`subscribe` resolves to the app frame. Matches the
;; canonical mount in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:notebook/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [notebook]])))

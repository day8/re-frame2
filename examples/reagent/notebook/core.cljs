(ns notebook.core
  "Reagent design-led example — 'Notebook'. Three-pane editorial layout:

     [ documents tree ]  [ markdown editor ]  [ live preview ]

   Proves re-frame2 + Reagent can build a substantive UI (rf2-t7t6f). The
   data-flow is the canonical six dominoes:

     - selecting a document         dispatches  [:notebook/select id]
     - editing the body             dispatches  [:notebook/edit-body text]
     - the body sub                 derives     parsed-html from the markdown
     - the preview pane             subscribes  to that derivation

   Distinguished from the canonical login + counter examples by being
   `reg-view`-based at every layer, exercising multi-pane layout, and
   leaning into the Reagent 'Editorial Warm' visual identity from
   examples/_shared/css/reagent.css (rf2-nfg15). No state machines, no
   HTTP — the design-led examples per rf2-t7t6f exist to prove polished
   visuals + interaction, not to replay the platform features other
   examples already cover.

   Markdown rendering is intentionally a tiny pure-CLJS parser (headings,
   bold, italic, links, paragraphs, lists) — keeps the bundle small and
   the example free of an extra npm dependency."
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
    :body  "### Patterns shipped with re-frame2\n\n- Pattern-Boot — application initialisation as a state machine.\n- Pattern-WebSocket — connection machine with backoff + queue-flush.\n- Pattern-LongRunningWork — :invoke-all spawn-and-join.\n- Pattern-Managed-HTTP — declarative HTTP with cancellation.\n\nEach has a worked example under `examples/reagent/`."}])

;; ============================================================================
;; EVENTS  (CP-1)
;; ============================================================================

(rf/reg-event-db :notebook/initialise
  (fn [_db _event]
    {:notebook/documents   initial-documents
     :notebook/selected-id :welcome}))

(rf/reg-event-db :notebook/select
  (fn [db [_ id]]
    (assoc db :notebook/selected-id id)))

(rf/reg-event-db :notebook/edit-body
  (fn [db [_ text]]
    (let [id (:notebook/selected-id db)]
      (update db :notebook/documents
              (fn [docs]
                (mapv (fn [d]
                        (if (= (:id d) id) (assoc d :body text) d))
                      docs))))))

(rf/reg-event-db :notebook/new
  (fn [db _event]
    (let [id (keyword (str "doc-" (rand-int 1000000)))
          doc {:id id :title "Untitled" :body "# Untitled\n\nStart writing…"}]
      (-> db
          (update :notebook/documents (fnil conj []) doc)
          (assoc :notebook/selected-id id)))))

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
                      (fn [m] [:a {:href (nth m 2)
                                   :rel "noopener noreferrer"
                                   :target "_blank"}
                                (nth m 1)]))
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
   each block gets a stable :key keyed off its index)."
  [s]
  (let [blocks (->> (str/split (or s "") #"\r?\n\r?\n")
                    (remove str/blank?))]
    (vec
      (map-indexed
        (fn [i b] (with-meta (render-block b) {:key i}))
        blocks))))

(rf/reg-sub :notebook/selected-hiccup
  :<- [:notebook/selected-body]
  (fn [body _] (markdown->hiccup body)))

;; ============================================================================
;; VIEWS  (CP-4) — Reagent 'Editorial Warm' palette
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
      [:span "Reagent · "] [:em "Editorial Warm"]]]))

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

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:notebook/initialise])
  (rdc/render react-root [notebook]))

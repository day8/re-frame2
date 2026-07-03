(ns notebook.core
  "Notebook — a Reagent example that leans into design. Three panes:

     [ documents list ]  [ markdown editor ]  [ live preview ]

   The counter shows the dataflow loop with nothing in the way. This is
   that same loop with more parts, and the lesson is that it doesn't
   change shape: one app-db holds the state, events are the only thing
   that change it, and the views are pure functions of subscriptions.
   Counter to multi-pane editor, same idea throughout.

   Trace one keystroke through it, top to bottom:

     - picking a document   dispatches  [:notebook/select id]
     - typing in the editor dispatches  [:notebook/edit-body text]
     - the hiccup sub       derives     hiccup from the selected markdown
     - the preview pane      subscribes  to that derivation, and re-renders

   The part worth slowing down for is the preview. Rendering markdown is
   just a pure function, string -> hiccup, so we expose it as a
   subscription and let it sit in the same graph as everything else. No
   DOM escape hatch, no `dangerouslySetInnerHTML`, no special case — the
   preview is a derived value like any other.

   The parser itself is a tiny hand-rolled one (headings, bold, italic,
   inline code, links, paragraphs, ordered + unordered lists). It handles
   exactly the seed content and not one feature more, which keeps the
   bundle small and spares us an npm dependency for a demo.

   New to the terms? docs/core/glossary.md defines event, subscription,
   app-db, and view; docs/core/concepts/views.md covers views and hiccup
   in depth."
  ;; We render through stock Reagent (`reagent.dom.client` +
  ;; `re-frame.adapter.reagent`). The adapter is the value that teaches
  ;; re-frame2 how to talk to a given rendering library — swap it and the
  ;; same app runs on UIx or Helix. See docs/core/glossary.md#adapter.
  (:require [reagent.dom.client :as rdc]
            [clojure.string     :as str]
            [re-frame.core      :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ============================================================================
;; SEED DATA
;; ============================================================================

(def initial-documents
  [{:id    :welcome
    :title "Welcome"
    :body  "# Welcome to the notebook\n\nThis is a *design-led* example that demonstrates the **Reagent** substrate of re-frame2.\n\nIt lives at `examples/core/notebook/` and renders a three-pane editor.\n\n- Pick a document from the left.\n- Edit the body in the middle.\n- Read the preview on the right.\n\nVisit [day8.re-frame2](https://github.com/day8/re-frame2) for more."}
   {:id    :six-dominoes
    :title "The six dominoes"
    :body  "## The dominoes\n\n1. **Event** dispatch\n2. **Event** handler\n3. **Effects** application\n4. **Query** layer (subs)\n5. **View** layer\n6. **Re-render**\n\nThe re-frame2 spec lives in *spec/*.\n\nEach domino is *pure* in isolation. Only domino 3 touches the world."}
   {:id    :patterns
    :title "Patterns"
    :body  "### Patterns shipped with re-frame2\n\n- Pattern-Boot — application initialisation as a state machine.\n- Pattern-WebSocket — connection machine with backoff + queue-flush.\n- Pattern-LongRunningWork — :spawn-all spawn-and-join.\n- Pattern-Managed-HTTP — declarative HTTP with cancellation.\n\nEach has a worked example under `examples/patterns/`."}])

;; ============================================================================
;; EVENTS
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

;; Where do new ids come from? Not a counter, not a random number — we
;; read them out of the state we already have. Scan the existing `doc-N`
;; ids, take the max, add one. The reason is replayability: an event
;; should be a pure function of (db, event), so replaying the same event
;; stream lands you in the same place every time. `(rand-int)` would
;; quietly poison that — every replay would mint a different id. The
;; seeded docs (`:welcome`, `:six-dominoes`, …) have no `doc-` prefix, so
;; they're invisible to the scan and the first new id is `:doc-1`. See
;; docs/core/concepts/events-and-the-cascade.md on replayable events.
(defn- allocate-next-doc-id
  "Next `:doc-N` id: highest existing N plus one (or 1 when there are
   none). A pure function of the current documents, so it replays
   identically — see the note above."
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
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :notebook/documents
  (fn [db _] (:notebook/documents db)))

(rf/reg-sub :notebook/selected-id
  (fn [db _] (:notebook/selected-id db)))

;; Subs compose. The `:<-` lines wire this sub's inputs to other subs —
;; here, the documents and the selected id — and the body below is a plain
;; function of their values. The payoff is that it recomputes only when
;; one of those inputs actually changes, and the subs downstream (`-body`,
;; `-hiccup`) chain off this one in the same way. A little graph of pure
;; derivations. See docs/core/concepts/subscriptions.md.
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
;; The trick here is the output: hiccup, not HTML strings. Reagent renders
;; hiccup natively, so we never reach for `dangerouslySetInnerHTML` — the
;; markdown becomes ordinary data flowing through the same pipeline as
;; everything else. Bonus: no markdown library to pull in.

(def ^:private safe-link-schemes
  "The preview renders user-typed markdown, so a link is attacker-typed
   input. We allowlist rather than blocklist: only these schemes get to
   be clickable. Everything else — `javascript:`, `data:`, and friends —
   is shown as plain text. Saying yes to a known-good set is far safer
   than trying to enumerate every way a link can be hostile."
  #{"http" "https" "mailto"})

(defn- safe-href
  "Decide whether `href` is safe to drop into `:a {:href ...}`; return it
   if so, nil if not. Two things pass: an allowlisted absolute scheme, and
   a scheme-less link (a relative path or `#fragment`, which can't run
   code). The fiddly bit is telling a real scheme from a colon that just
   happens to sit in a path. Per RFC 3986 §3.1 a scheme is a `word:`
   prefix at the very start; a `:` that shows up only after a `/`, `?` or
   `#` belongs to the path/query/fragment, so the link counts as
   scheme-less and is allowed."
  [href]
  (let [h (str/trim (or href ""))
        ;; Match a scheme — ALPHA then ALPHA/DIGIT/+/-/. — anchored to the
        ;; start. Anchoring is the whole point: a `:` further in isn't a
        ;; scheme delimiter and won't match here.
        m      (re-find #"(?i)^([a-z][a-z0-9+.-]*):" h)
        scheme (some-> m second str/lower-case)]
    (cond
      ;; No leading scheme → relative path or fragment → safe.
      (nil? scheme) href
      ;; A scheme we trust → safe.
      (contains? safe-link-schemes scheme) href
      ;; Anything else (javascript:, data:, vbscript:, …) → not on our watch.
      :else nil)))

(defn- split-by-regex
  "The one moving part the inline parser is built on. Given a flat seq of
   strings and already-built hiccup vectors, find every match of `re` in
   the *strings* and replace each with `(mk match)`, leaving the text
   around them intact. Hiccup vectors are already done, so they ride
   through untouched — which is how we run several passes in a row without
   one pass clobbering another's work.

   It walks one `re-find` at a time, slices the before/after text out of
   the source by hand (`.indexOf` + `subs`), and recurs on the tail."
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
  "Turn one block of text into a flat seq of hiccup, pulling out the
   inline runs — links, bold, italic, code — as it goes. The caller
   splices the result into a parent element. The passes run one after
   another, and each only ever sees the plain-text leftovers of the ones
   before it (the hiccup they emitted just rides along), so a `**bold**`
   pass can't accidentally chew on the innards of a link it shouldn't
   touch. Simple rules, layered."
  [s]
  (-> [s]
      (split-by-regex #"\[([^\]]+)\]\(([^)]+)\)"
                      (fn [m]
                        (let [text (nth m 1)]
                          ;; Run the destination past safe-href before
                          ;; trusting it. A safe link becomes a real
                          ;; anchor; anything dodgy degrades to plain text,
                          ;; so a hostile `javascript:` URL in the markdown
                          ;; renders harmlessly instead of becoming a
                          ;; clickable trap.
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
  "The whole pure-CLJS parser, top level. Split the text on blank lines
   into blocks, figure out each block's shape (heading? list? paragraph?),
   and run the inline rules over it. Just enough markdown for the seed
   content.

   Returns a vector of block hiccup for the caller to splice into a
   container; Reagent renders that vector as a seq of children. The fiddly
   detail is the `:key` on each block. React renders these as a list, and
   it uses keys to decide what moved versus what's new. We key by content
   (its hash), not by index — index keys would re-key every block below an
   edit and make React redraw the lot, when all you did was add a sentence.
   Two identical blocks would collide, so we mix in the position to keep
   keys unique."
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
;; VIEWS — shared 'Editorial Warm' palette
;; ============================================================================
;;
;; `reg-view` reads like a `defn`, and it is one — plus it registers the
;; view and hands you `dispatch` and `subscribe` as ready-to-call locals.
;; No `rf/` prefix, no frame to thread through; they find the frame in
;; scope at render time on their own. Each view below just subscribes to
;; what it needs and dispatches on interaction — pure functions of state,
;; nothing reaching out to mutate. See docs/core/concepts/views.md.

(rf/reg-view sidebar []
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

(rf/reg-view editor []
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

(rf/reg-view preview []
  (let [blocks @(subscribe [:notebook/selected-hiccup])]
    [:section.nb-preview
     [:header.nb-pane-header
      [:span.nb-eyebrow "Preview"]
      [:span "Live"]]
     (into [:article.nb-rendered {:data-testid "notebook-preview"}]
           (or blocks []))]))

(rf/reg-view notebook []
  [:div.nb-shell
   [sidebar]
   [editor]
   [preview]])

;; ============================================================================
;; MOUNT
;; ============================================================================
;;
;; Loading this namespace touches the DOM exactly never. We hold off on
;; `create-root` until `run` is called. The reason is the test bundle:
;; many example namespaces get loaded into one page there, and if each one
;; grabbed a root the moment it loaded, they'd all fight over the same
;; container. Defer the mount and they stay out of each other's way. See
;; examples/TESTING.md (mount-isolation convention).

(defonce react-root (atom nil))

;; re-frame2 won't conjure a frame for you — an app stands up its own. We
;; do it in exactly one place: the `frame-provider {:id app-frame}` in the
;; render call below. First mount creates the frame and fires
;; `:initial-events` once to seed app-db before anything paints; a hot
;; reload finds the frame already there and skips the seed. From then on
;; every `dispatch` and `subscribe` in the tree resolves to it. The name
;; `:rf/default` is just an id — pick any keyword; it earns no special
;; privileges for being the only one here. See
;; docs/core/concepts/frames.md.
(def app-frame :rf/default)

(defn run []
  ;; One job each: `init!` tells the runtime to render through Reagent. It
  ;; does not make a frame — the `frame-provider` below does that.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:notebook/initialise]]}
                 [notebook]])))

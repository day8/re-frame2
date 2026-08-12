(ns re-frame.hicasso.examples.slice.views
  "THE SLICE'S VIEWS — the whole application's markup, on the public door
  (rf2-hic-025).

  Thirteen boundaries — the original slice's six, and the seven
  rf2-hic-074 added for pagination, runtime-selected content and a
  nested error region: a pager, a digest region, its body, and four
  block renderers. Everything they reach for is `h/…`: `defview`, `sub`,
  `use-subs`, `boundary`, `route-link`, the `::h/value` / `::h/checked` /
  `::h/revision` markers. There is no `impl` namespace anywhere in the
  `:require` list above, and no `re-frame.core` either — a view neither
  dispatches nor subscribes directly, because an intent is a vector and a
  read is `h/sub`.

  ## The one thing an author has to know that is not in a signature

  `h/route-link` is CALLED, not written as a head: `(h/route-link {…}
  label)`, inside a body. It is a plain function on purpose — a link is
  not a unit of re-render, and a boundary would cost two hooks and a row
  in the page's boundary count for every author byline. So the two forms
  that both produce markup, `[some-view {…}]` and `(h/route-link {…} …)`,
  are written differently, and the difference is not visible at either
  call site: nothing about `route-link` says *call me*.

  That is the rf2-hic-025 authoring report's third finding. It is a
  ONE-TIME cost — the mistake is loud when made, because a function in
  head position is `:rf.error/hicasso-function-in-head-position` and the
  clj-kondo export flags it before the build — but it is the only place
  in this application where two things that do the same job are spelled
  in two grammars.

  ## Where the ambient read is used, and where the grouped one is

  [[article-row]] uses `h/use-subs`, the grouped control: two reads, both
  unconditional, and the alias map is shorter than the reads. Every other
  body uses `h/sub`, the ambient collector, because their reads are
  conditional — `[[editor]]`'s problem string is read only when there is
  a problem, and an edge for a branch not taken is an edge that
  re-renders the editor when nothing on screen could change. That is the
  difference the two doors are actually for, and this file is the
  smallest honest demonstration of it."
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.i18n :as i18n]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.subs :as subs]))

;; ---------------------------------------------------------------------------
;; The chrome — locale and theme, switched at runtime
;; ---------------------------------------------------------------------------

(h/defview chrome
  "The header: the application's name, a locale `<select>` and two theme
  buttons.

  Both switches are ordinary intents into ordinary handlers, and the
  strings and colours beneath them are ordinary subscription reads. That
  is the whole of specification §7's i18n / theming row: there is no
  provider to install, no context to thread and no adapter subsystem —
  switching either moves one key in `app-db`, and every boundary that
  read a string or a token re-renders because it read it."
  [_]
  (let [locale-now (h/sub [::subs/locale])
        theme-now  (h/sub [::subs/theme])]
    [:header.slice-chrome
     [:h1.slice-title (h/sub [::subs/t :app/title])]

     [:label.locale-label {:for "slice-locale"} (h/sub [::subs/t :app/locale])]
     [:select#slice-locale.locale
      {;; `::h/value` off a `<select>` is `.-value`, a string — the
       ;; handler keywords it. Positional, because the marker substitutes
       ;; at the intent's top level only; see `events`'s namespace
       ;; docstring.
       :value     (name locale-now)
       :on-change [::events/set-locale ::h/value]}
      ;; The option roster never changes, so it is a module constant read
      ;; directly rather than a subscription over a literal.
      (for [locale i18n/locales]
        [:option {:key (name locale) :value (name locale)} (name locale)])]

     [:div.theme-switch
      [:span.theme-label (h/sub [::subs/t :app/theme])]
      (for [[theme label-key] [[:light :theme/light] [:dark :theme/dark]]]
        [:button.theme-choice
         {:key      (name theme)
          :type     "button"
          :class    (when (= theme theme-now) "current")
          :on-click [::events/set-theme {:theme theme}]}
         (h/sub [::subs/t label-key])])]]))

;; ---------------------------------------------------------------------------
;; The feed — a keyed list
;; ---------------------------------------------------------------------------

(h/defview article-row
  "One row of the feed: a link to the article, and a tag disclosure whose
  open flag is this row's own.

  The disclosure is what `h/reg-state` is for. Keyed by the slug — a
  domain id — so opening one row opens one row. A hand-rolled `[:ui
  :tags-open?]` path is the bug the sugar deletes: every row on the page
  would share one flag and they would all open together, silently."
  [{:keys [slug title published? tags]}]
  (let [{:keys [open? tags-label]}
        (h/use-subs {:open?      [::subs/tags-open? slug]
                     :tags-label [::subs/t :feed/tags]})]
    [:li.article-row
     ;; CALLED, not a head — see the namespace docstring. The href and the
     ;; click decision come whole from the routing artefact; this body
     ;; never sees a URL.
     (h/route-link {:to routes/article :params {:slug slug} :class "article-link"}
                   title)
     (when-not published?
       [:span.draft-badge "draft"])
     (when (seq tags)
       [:div.tags
        [:button.tags-toggle
         {:type          "button"
          :aria-expanded (str (boolean open?))
          :on-click      [::subs/tags-open? slug (not open?)]}
         tags-label]
        (when open?
          [:ul.tag-list
           (for [tag tags]
             [:li.tag {:key tag} tag])])])]))

;; ---------------------------------------------------------------------------
;; The pager — the page is a URL, so Back and Forward are routing's
;; (rf2-hic-074)
;; ---------------------------------------------------------------------------

(h/defview pager
  "The feed's pager: an end-stop or a link at each end, and one link per
  page in between.

  Every control here is a `h/route-link` carrying `{:page n}` at
  `:query`, so flipping a page is a NAVIGATION and not an application
  event. Three things follow that this body does not have to write:
  Back and Forward restore the page, a page is a URL somebody can send,
  and the address bar cannot disagree with the list because there is only
  one fact. See [[re-frame.hicasso.examples.slice.routes/register!]].

  ## The ends are `<span>`s, not disabled links

  There is no such thing as a disabled link: `disabled` is not an anchor
  attribute, and an `<a>` with no `href` is not a link either — it is a
  focusable thing screen-reader users are told to activate and nothing
  happens when they do. On the first page, *Previous* is therefore not a
  control at all. It stays as text carrying `aria-disabled`, which says
  what it is without claiming to be operable.

  ## Why the pager disappears at one page

  A pager over a single page is a control whose every state is the state
  it is in. Rendering nothing is not a shortcut — it is the read set
  shrinking with the branch, and `l2-cljs-test` asserts exactly that by
  handing this body a fixture map with no strings in it."
  [_]
  (let [{:keys [page pages]} (h/use-subs {:page  [::subs/current-page]
                                          :pages [::subs/page-count]})]
    (when (< 1 pages)
      ;; Both labels are read OUTSIDE the branches that place them, so the
      ;; page a user is on never changes which strings this body reads.
      ;; An edge that appears on page 2 and not on page 1 is an edge whose
      ;; absence nobody would notice until a translation changed.
      (let [previous (h/sub [::subs/t :feed/previous])
            next     (h/sub [::subs/t :feed/next])]
        [:nav.pager {:aria-label (h/sub [::subs/t :feed/pagination])}
         (if (= 1 page)
           [:span.pager-prev.pager-end {:aria-disabled "true"} previous]
           (h/route-link {:to routes/feed :query {:page (dec page)} :class "pager-prev"}
                         previous))
         [:ol.pager-pages
          (for [n (range 1 (inc pages))]
            [:li.pager-page {:key n}
             (if (= n page)
               ;; `aria-current="page"` is the whole of *you are here* for
               ;; a screen reader, and it is on a span rather than on a
               ;; link because a link to the page you are on is a link
               ;; that does nothing.
               [:span.pager-current {:aria-current "page"} (str n)]
               (h/route-link {:to routes/feed :query {:page n} :class "pager-link"}
                             (str n)))])]
         (if (= page pages)
           [:span.pager-next.pager-end {:aria-disabled "true"} next]
           (h/route-link {:to routes/feed :query {:page (inc page)} :class "pager-next"}
                         next))]))))

;; ---------------------------------------------------------------------------
;; The digest — runtime-selected content, under its own error region
;; (rf2-hic-074)
;;
;; Specification §3.3: dynamic composition is a FEATURE. The four bodies below
;; are the renderers, the map after them is the choice, and `digest-body` is
;; the one line that makes the choice at render time. Nothing about this needs
;; a registry, a multimethod or a compiler pass — a view head is a value, and
;; picking one out of a map is picking a value out of a map.
;; ---------------------------------------------------------------------------

(h/defview prose-block
  "A paragraph."
  [{:keys [block]}]
  [:p.block-prose (:block/text block)])

(h/defview list-block
  "A bulleted list — and the one renderer in this application that
  REFUSES its input.

  `:block/items` is the contract: a list block without items is not a
  list with nothing in it, it is a payload that arrived broken, and the
  two want opposite treatment. An empty list renders as an empty list; a
  MISSING one means something upstream cut the delivery short, and
  rendering an empty list for it would put a silent hole on the page
  where an editor put three items.

  So it throws, and the region above it catches. That is the §7 errors
  row's shape in one body: an expected failure stays data (see
  [[unsupported-block]]), an unexpected one throws where it is noticed
  and a nested region turns it into a page the user can retry from
  rather than into a blank screen."
  [{:keys [block]}]
  (let [items (:block/items block)]
    (when (nil? items)
      (throw (ex-info "a list block arrived without its items"
                      {:block/id (:block/id block)})))
    (into [:ul.block-list]
          (map (fn [item] [:li.block-item {:key item} item]))
          items)))

(h/defview callout-block
  "An aside whose EMPHASIS TAG and colour are both chosen from the
  block's tone at render time.

  The tag is the point: `[:strong …]` and `[:em …]` are two shapes, and
  which one this block is is a fact about the data. Nothing declares the
  pair anywhere — a keyword in head position is a keyword in head
  position whether it was typed or computed."
  [{:keys [block]}]
  (let [warning? (= :warning (:block/tone block))]
    [:aside.block-callout
     {:style {:color (h/sub [::subs/token (if warning? :danger :accent)])}}
     [(if warning? :strong :em) {:class "block-emphasis"} (:block/text block)]]))

(h/defview unsupported-block
  "A block of a kind this build has no renderer for.

  The EXPECTED failure, and it stays data. Content outlives the build
  that renders it: a kind minted after this bundle shipped is an ordinary
  thing to be sent, and a region that threw on one would make every new
  content type a deployment incident. The kind is printed rather than
  swallowed, so the hole says what it is."
  [{:keys [block]}]
  [:p.block-unsupported
   (h/sub [::subs/t :digest/unsupported])
   " "
   [:code.block-kind (str (:block/kind block))]])

(def block-views
  "Block kind to the view that renders it. A plain map of heads — the
  runtime-selected component, with no machinery under it."
  {:block/prose   prose-block
   :block/list    list-block
   :block/callout callout-block})

(h/defview digest-body
  "The blocks, each rendered by whichever view its own kind names.

  `(get block-views kind unsupported-block)` in HEAD POSITION is the
  whole of §3.3's claim: reads, branches, loops and ordinary
  transformations are legal in a body, and a head is a value like any
  other. A kind with no entry falls to [[unsupported-block]] rather than
  to a refusal — the default argument is the policy."
  [_]
  (let [blocks (h/sub [::subs/digest-blocks])]
    (into [:div.digest-body]
          (map (fn [{:block/keys [id kind] :as block}]
                 [(get block-views kind unsupported-block) {:key id :block block}]))
          blocks)))

(h/defview digest
  "The digest region, and the application's NESTED error region.

  `h/error-boundary` here sits inside the one
  [[app]] wraps the routed pane in, and the nesting is the point:
  whatever a block does to itself, the page around it — the article
  list, the pager, the chrome — is still there and still works. An
  application with one boundary at the root has exactly one failure
  mode, and it is *the screen went away*.

  ## The reset key is the CONTENT, not a counter

  `:reset-key` is the block vector itself, compared with `=`. So the
  region clears its caught failure exactly when different content
  arrives, and — the half a counter gets wrong — it does NOT clear when
  the same broken payload arrives again. A counter would reset the
  boundary, re-render the same bad block, throw again, and show the
  fallback a second time after a visible flicker. Reading the content
  itself, a retry that changed nothing changes nothing on screen."
  [_]
  (let [blocks   (h/sub [::subs/digest-blocks])
        loading? (h/sub [::subs/digest-loading?])]
    [:section.digest
     [:h3.digest-heading (h/sub [::subs/t :digest/heading])]
     [h/error-boundary
      {:reset-key blocks
       :fallback  [:div.digest-error {:role "alert"}
                   [:p.digest-problem (h/sub [::subs/t :digest/problem])]
                   [:button.digest-retry
                    {:type     "button"
                     :disabled loading?
                     :on-click [::events/reload-digest]}
                    (h/sub [::subs/t (if loading? :digest/loading :digest/retry)])]]}
      [digest-body {}]]]))

;; ---------------------------------------------------------------------------
;; The feed page
;; ---------------------------------------------------------------------------

(h/defview feed-page
  "The list. Keyed by slug, because a keyed list whose key is its index
  reuses the wrong row the moment the order changes — and with
  pagination there is a moment: every page flip replaces the whole list,
  and an index key would hand row 1's mounted element, its open
  disclosure and its scroll position to a different article.

  The digest and the pager are CHILD boundaries rather than markup
  written here, and that is a read-set decision as much as a layout one:
  this body still reads exactly the two things it read before pagination
  existed, so the page it renders is still *the rows, and a heading*."
  [_]
  (let [rows (h/sub [::subs/feed])]
    [:section.feed
     [:h2 (h/sub [::subs/t :feed/heading])]
     [digest {}]
     (if (empty? rows)
       [:p.feed-empty (h/sub [::subs/t :feed/empty])]
       [:ul.article-list
        (for [{:keys [slug] :as row} rows]
          [article-row (assoc row :key slug)])])
     [pager {}]]))

;; ---------------------------------------------------------------------------
;; The editor — controlled fields, an async save, an error region, a reset
;; ---------------------------------------------------------------------------

(h/defview editor
  "The article's form.

  Both text fields are CONTROLLED in the substrate's sense: `:value` is a
  subscription, `:on-input` is an intent vector, and there is no ref, no
  local draft, no `onChange` closure and no effect reconciling two copies
  of the text. The model is the only place it lives.

  `::h/revision` is the reset trigger and it carries the ONLY thing that
  makes *discard* work. Dropping the draft moves the model back to the
  article — but if the user had typed and then discarded, the value the
  field is handed is the value it was handed before, and React sees
  nothing to do. A changed revision re-baselines the field to the model
  without remounting it (HD-019)."
  [{:keys [slug]}]
  (let [draft            (h/sub [::subs/draft slug])
        revision         (h/sub [::subs/revision slug])
        dirty?           (h/sub [::subs/dirty? slug])
        {:keys [status problem]} (h/sub [::subs/save-state slug])
        saving?          (= :saving status)]
    [:form.editor {:on-submit [::events/save {:slug slug}]}
     [:h3 (h/sub [::subs/t :editor/heading])]

     [:label {:for "slice-title"} (h/sub [::subs/t :editor/title])]
     [:input#slice-title.field-title
      {:type         "text"
       :value        (:title draft)
       ::h/revision  revision
       :on-input     [::events/edit slug :title ::h/value]}]

     [:label {:for "slice-body"} (h/sub [::subs/t :editor/body])]
     [:textarea#slice-body.field-body
      {:value        (:body draft)
       ::h/revision  revision
       :on-input     [::events/edit slug :body ::h/value]}]

     [:label.published {:for "slice-published"}
      [:input#slice-published
       {:type      "checkbox"
        :checked   (boolean (:published? draft))
        :on-change [::events/toggle-published slug ::h/checked]}]
      (h/sub [::subs/t :editor/published])]

     [:div.editor-actions
      [:button.save {:type "submit" :disabled saving?}
       (h/sub [::subs/t (if saving? :editor/saving :editor/save)])]
      [:button.discard {:type     "button"
                        :disabled (not dirty?)
                        :on-click [::events/discard {:slug slug}]}
       (h/sub [::subs/t :editor/discard])]]

     ;; The error region. A problem is a KEYWORD in `app-db` and a
     ;; sentence here, which is what makes a failed save translatable and
     ;; its test independent of the wording. The read is INSIDE the
     ;; branch, so an editor with nothing wrong records no edge on the
     ;; problem string and does not re-render when the locale changes for
     ;; a message it is not showing.
     (when (= :failed status)
       [:p.save-problem {:role  "alert"
                         :style {:color (h/sub [::subs/token :danger])}}
        (h/sub [::subs/t problem])
        " "
        [:button.retry {:type "button" :on-click [::events/save {:slug slug}]}
         (h/sub [::subs/t :editor/retry])]])

     (when (= :saved status)
       [:p.save-ok {:role "status"} (h/sub [::subs/t :editor/saved])])]))

(h/defview article-page
  "One article and its editor. The slug comes from the URL through
  routing's own subscription — the slice keeps no copy of the current
  route in `app-db`, so the address bar and the page cannot disagree."
  [_]
  (let [slug    (:slug (h/sub [:rf.route/params]))
        article (h/sub [::subs/article slug])]
    [:section.article
     (h/route-link {:to routes/feed :class "back"}
                   (h/sub [::subs/t :article/back]))
     (if (nil? article)
       ;; A URL is user input, so a slug nobody published is a page rather
       ;; than an error.
       [:p.article-missing (h/sub [::subs/t :article/missing])]
       [:<>
        [:h2.article-title (:title article)]
        [editor {:slug slug}]])]))

;; ---------------------------------------------------------------------------
;; The shell
;; ---------------------------------------------------------------------------

(h/defview app
  "The whole application: the chrome, then whichever pane the route
  selects, under one error boundary.

  `:reset-key` is the route id, so navigating away from a pane that threw
  clears the caught failure and re-mounts — the retry is the
  application's, taken at the moment the user does something different,
  rather than the boundary's to guess."
  [_]
  (let [route (h/sub [:rf.route/id])]
    [:main.slice
     {:style {:background (h/sub [::subs/token :surface])
              :color      (h/sub [::subs/token :ink])}}
     [chrome {}]
     ;; The fallback is markup written HERE, in a body that rendered fine,
     ;; so its string is read the way every other string is. A hardcoded
     ;; English sentence would be the one place in the application a
     ;; French reader falls out of their own language, and it would do so
     ;; at the worst possible moment.
     [h/error-boundary {:reset-key route
                        :fallback  [:p.pane-error {:role "alert"}
                                    (h/sub [::subs/t :app/pane-error])]}
      (cond
        (= route routes/article) [article-page {}]
        (= route routes/feed)    [feed-page {}]
        ;; Before the first navigation resolves there is no route at all,
        ;; and a page that rendered nothing there would be a blank screen
        ;; with no explanation. The feed is the honest default.
        :else                    [feed-page {}])]]))

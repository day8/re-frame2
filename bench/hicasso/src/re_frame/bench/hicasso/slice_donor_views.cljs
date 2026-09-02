(ns re-frame.bench.hicasso.slice-donor-views
  "THE SLICE'S FEED PAGE, TRANSCRIBED INTO UIx — the DONOR ARM `C3` and
  `C4` are stated against (rf2-9wmqd).

  `docs/design/hicasso/product/budgets.md` §4 registers `C3` as *broad
  updates ≤ 1.25x the best relevant adapter after topology tuning* and
  `C4` as *sustained > 1.5x*, and §9.4 states both **on the same readings
  as `U1`–`U4`** — which is to say on the slice application's own
  interactions, through a window that ends at a paint. A comparative row
  needs two arms and
  [[re-frame.bench.hicasso.slice-echo-clock-app]] has one, so this
  namespace is the other: the same page, the same subscriptions, the same
  events, read and written the way a UIx application reads and writes
  them.

  It renders nothing on its own. [[re-frame.bench.hicasso.slice-broad-clock-app]]
  mounts it beside the Hicasso arm, gates the two pages equal, and times
  the same operation on each.

  ## WHY UIx IS THE DONOR, AND WHAT THAT CHOICE IS NOT

  Two candidates exist on this lane — Reagent reading re-frame2
  subscriptions ([[re-frame.bench.hicasso.p0-reagent-views]]) and UIx
  reading them through the published `use-subscribe` spine
  ([[re-frame.bench.hicasso.p0-uix-views]]) — and this arm is the second,
  for two reasons that are not taste.

  1. **The process already carries the UIx adapter.** `rf/init!` installs
     ONE adapter for the process, the slice's own `app.cljs` installs
     UIx, and the Hicasso arm's driver does the same. A Reagent donor on
     this page would need a second adapter phase — the shape
     `p0-reagent-views` records the predecessor programme paying for, and
     the reason its own four arms are *Reagent or nothing*. One page, one
     adapter.
  2. **It is the harder denominator, which is the honest direction to
     err.** `docs/design/hicasso/studio/hd8-composed-donor-arm.md`'s
     re-taken rows read *on this clock, direct UIx is now the faster
     donor*, and §4's `C2` states its own line against *direct UIx*. A
     `≤ 1.25x` claim measured against the faster of the two donors is a
     claim that survives the other.

  **This does not decide `C3`'s *best relevant adapter*, and no file in a
  bench tree could.** *Best* is a fact about a measurement, and the only
  thing an instrument may do about it is carry an arm and say which one.
  What a run reading this instrument may conclude is `Hicasso / UIx` on
  the slice's broad updates; concluding `≤ 1.25x the best relevant
  adapter` additionally needs the Reagent arm, and that arm needs a
  second page because it needs a second adapter.

  ## WHAT MAKES THIS A DENOMINATOR RATHER THAN A STRAWMAN

  `p0-reagent-views` states the rule this arm is written under: *a
  strawman denominator would make the whole comparison worthless*. So
  every read below is a real subscription read against the same
  registration the Hicasso arm reads, one per boundary, and every write
  is the same event vector the Hicasso arm's intent lowers to.

  - **The sub graph is `slice.subs`, untouched.** `[::subs/t k]`,
    `[::subs/token k]`, `[::subs/feed]`, `[::subs/current-page]`,
    `[::subs/page-count]`, `[::subs/tags-open? slug]`,
    `[::subs/digest-blocks]` and `[::subs/digest-loading?]` — the same
    layer-1 and layer-2 registrations, resolved against this arm's own
    frame. Nothing is threaded as a prop that the Hicasso arm subscribes
    to: a row that took its label as a prop would be a different sub
    graph wearing the same name.
  - **The event vectors are `slice.events`', verbatim.**
    `[::events/set-locale s]` and `[::events/set-theme {:theme t}]` are
    what Hicasso's `::h/value` marker and its intent vector lower to, and
    they are what this arm dispatches. The MARKER is Hicasso's
    ergonomics; the EVENT is the application's.
  - **`dispatch-sync`, because the thing being compared is a discrete
    interaction.** A Hicasso intent goes through the runtime's own
    synchronous frame-locked door — `slice.flow-dom-cljs-test` §*TWO
    CLICKS, TWO SETTLING RULES* states it — so the handler has run and
    React has committed before the DOM event returns. A donor
    dispatching asynchronously would not be a slower spelling of the same
    operation, it would be a different operation, and the window would
    close before it began. [[re-frame.bench.hicasso.jsfb-uix-app]]'s
    `go!` reaches for `dispatch-sync` inside the click turn for exactly
    this reason.
  - **No `set-hiccup-emitter!`, no `use-memo` around subscription args.**
    `p0-uix-views`' reasons, unchanged: routing this arm through a hiccup
    interpreter would price a hiccup interpreter, which is the
    candidate's product delta and not UIx's.

  ## WHERE THE TWO ARMS ARE NOT THE SAME, STATED RATHER THAN LEFT TO BE FOUND

  The claim this arm can carry is *the same page, built by the idiomatic
  spelling of each substrate*. It is NOT *hook for hook, allocation for
  allocation*, and three differences are real:

  - **`use-frame` is a hook this arm pays and the Hicasso arm does not
    pay in the same place.** Three boundaries here call it —
    [[chrome]], [[article-row]] and [[digest]] — because a UIx body
    reaches its frame's `dispatch-sync` in hook position. Hicasso's
    intent vector is lowered inside the boundary's own hooks instead.
    Both are the idiomatic spelling; neither is a handicap added for the
    bench.
  - **A runtime-chosen TAG is a branch here and a value there.**
    [[callout-block]] picks `<strong>` or `<em>` from the block's tone,
    and the Hicasso body writes `[(if warning? :strong :em) …]` because a
    keyword in head position is a keyword in head position however it got
    there. UIx's `$` compiles a non-literal head as a COMPONENT, so this
    arm spells the same choice as a branch over two literal elements. The
    DOM is identical — the gate below proves it — and the difference is
    an authoring one.
  - **The error regions are not feature-equal.** `h/error-boundary`
    clears a caught failure when its `:reset-key` changes;
    [[error-region]] has no reset key. That is a difference in FAILURE
    behaviour and not in the measured path — nothing in the row set
    throws, the seeded digest arrives whole — and it cannot reach a
    reading silently, because a region that had caught would render its
    fallback and the driver's canonical-DOM gate refuses two pages that
    differ before any clock is read.

  ## THE READ ROSTER, COMPARED READ FOR READ (rf2-9wmqd, PR #8599's audit)

  The three differences above are AUTHORING ones. A fourth was a
  MEASUREMENT one, and it is repaired: this arm's `feed-page` read
  `[::subs/t :feed/empty]` unconditionally where the Hicasso body reads it
  only in its empty branch, so on a seed that is never empty the donor
  carried a subscription and a hook the Hicasso arm did not. See
  [[feed-empty]].

  The rest of the page was then compared boundary by boundary, and what
  the comparison found is stated here rather than left to be re-derived:

  - **[[chrome]] 7, [[article-row]] 2, [[digest]] 5, [[digest-body]] 1,
    [[callout-block]] 1, [[unsupported-block]] 1** — read for read with
    their Hicasso counterparts, on the same registrations. `h/use-subs`
    and two `use-subscribe` calls are one door and two doors onto the same
    two edges, not two read sets.
  - **[[pager]] 5 against 5 at the pinned seed**, from a body that reads
    them unconditionally against one that reads three of them inside its
    branch. INERT rather than hidden, and [[pager]] carries why the
    [[feed-empty]] repair would make it worse rather than better.
  - **[[app]] 3 against the Hicasso shell's 4** — this arm does not read
    `[:rf.route/id]`, because it renders no route branch. The one
    remaining asymmetry, it runs in the DENOMINATOR'S favour, and [[app]]
    states it.

  So the donor reads NOTHING the Hicasso arm does not, and the Hicasso arm
  reads one thing the donor does not. That is an assertion rather than a
  claim: `slice-broad-window-dom-cljs-test` takes both frames'
  subscription caches on the seeded page and compares them, with a
  negative control that re-plants exactly the read this repair removed.

  ## THE IDS ARE DUPLICATED ON PURPOSE

  `#slice-locale` is this page's id and it is also the Hicasso page's,
  because the two pages have to be the same page. Two roots on one
  document therefore carry duplicate ids by construction. Nothing in the
  driver reads through `document.getElementById`: every node is found by
  `container.querySelector` on the arm's own container, which is
  unambiguous. A `<label for>` pointing at an ambiguous id is a real
  defect of this bench page and of no application; no arm activates a
  label.

  Owner: rf2-9wmqd."
  (:require [re-frame.adapter.uix :as uixa]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.i18n :as i18n]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.subs :as subs]
            [re-frame.routing :as routing]
            [uix.core :as uix :refer [$ defui]]))

;; ---------------------------------------------------------------------------
;; The link — routing's URL, and routing's own async activation
;; ---------------------------------------------------------------------------

(defn href-for
  "The URL for one address, from routing's own inverse of `match-url`.

  `re-frame.hicasso.impl.route-link` takes its href from routing's
  `:routing/link-model` late-bind seam; this arm takes it from
  `routing/route-url`, the public door onto the same registrations. The
  two are not asserted equal here and they do not need to be: the
  driver's canonical-DOM gate compares the rendered anchors, so a
  disagreement between the two doors REFUSES the run rather than
  publishing a ratio taken over two different pages."
  [address]
  (routing/route-url address))

(defn- link-click
  "The click a plain UIx application writes on an in-application link:
  claim the primary unmodified click, let every other one reach the
  browser, and ask routing to navigate.

  `dispatch` and NOT `dispatch-sync`, and that is fidelity rather than a
  concession. `re-frame.routing/activate-link!` ends at the router's
  async door — `slice.flow-dom-cljs-test`'s namespace docstring records
  that the navigation is merely ENQUEUED when a `route-link` click
  returns — so a synchronous donor link would be a faster mechanism than
  the one it is standing in for. No arm in the driver clicks a link (see
  its §THE ROW SET), so nothing measured turns on this; it is here
  because a page with links that do not navigate is not the page."
  [dispatch address]
  (fn [^js e]
    (when (and (zero? (.-button e))
               (not (.-metaKey e))
               (not (.-ctrlKey e))
               (not (.-shiftKey e))
               (not (.-altKey e)))
      (.preventDefault e)
      (dispatch [:rf.route/navigate address]))))

;; ---------------------------------------------------------------------------
;; The error region
;; ---------------------------------------------------------------------------

(def error-region
  "A React error boundary in UIx's own spelling, standing where the
  Hicasso page puts `h/error-boundary`.

  It exists for the COMPONENT COUNT as much as for the behaviour: the
  Hicasso arm nests two of them — one around the routed pane, one around
  the digest — and a donor that rendered the same DOM through two fewer
  components would be a donor doing less work. See the namespace
  docstring for the one thing it deliberately does not carry."
  (uix/create-error-boundary
    {:display-name       "slice-donor-error-region"
     :derive-error-state (fn [error] error)}
    (fn [[error _set-error] {:keys [fallback children]}]
      (if (some? error) fallback children))))

;; ---------------------------------------------------------------------------
;; The chrome
;; ---------------------------------------------------------------------------

(defui chrome
  "The header: the application's name, the locale `<select>` and the two
  theme buttons.

  This is the boundary both measured operations start at. The `<select>`
  is controlled on `[::subs/locale]` and its `change` dispatches
  `[::events/set-locale <string>]` — the string a `<select>`'s `.value`
  always is, keyworded by the handler and not by this body, exactly as
  the Hicasso spelling leaves it.

  ## THE TWO THEME LABELS ARE READ BEFORE THE LOOP, NOT INSIDE IT

  The Hicasso body reads `(h/sub [::subs/t label-key])` inside the `for`
  that places the two buttons, and it may: `h/sub` is an ambient
  collector rather than a React hook. `use-subscribe` IS one, and a hook
  reached from inside a `for` is reached from inside a LAZY SEQ — a seq
  React realises while rendering the parent element, after this body has
  already returned. The read would then happen outside the render it
  belongs to, which is not a slower spelling of the same thing but a
  broken one.

  Reading both labels up front is the ordinary UIx repair and it costs
  the arm nothing it was not already paying: the roster is two literal
  pairs, so both strings are read on every render of the Hicasso body
  too."
  [_]
  (let [locale-now              (uixa/use-subscribe [::subs/locale])
        theme-now               (uixa/use-subscribe [::subs/theme])
        title                   (uixa/use-subscribe [::subs/t :app/title])
        locale-label            (uixa/use-subscribe [::subs/t :app/locale])
        theme-label             (uixa/use-subscribe [::subs/t :app/theme])
        light-label             (uixa/use-subscribe [::subs/t :theme/light])
        dark-label              (uixa/use-subscribe [::subs/t :theme/dark])
        {:keys [dispatch-sync]} (uixa/use-frame)]
    ($ :header.slice-chrome
       ($ :h1.slice-title title)

       ($ :label.locale-label {:for "slice-locale"} locale-label)
       ($ :select#slice-locale.locale
          {:value     (name locale-now)
           :on-change (fn [^js e]
                        (dispatch-sync [::events/set-locale (.. e -target -value)]))}
          (for [locale i18n/locales]
            ($ :option {:key (name locale) :value (name locale)} (name locale))))

       ($ :div.theme-switch
          ($ :span.theme-label theme-label)
          (for [[theme label] [[:light light-label] [:dark dark-label]]]
            ($ :button.theme-choice
               {:key      (name theme)
                :type     "button"
                :class    (when (= theme theme-now) "current")
                :on-click (fn [_] (dispatch-sync [::events/set-theme {:theme theme}]))}
               label))))))

;; ---------------------------------------------------------------------------
;; The feed's row
;; ---------------------------------------------------------------------------

(defui article-row
  "One row of the feed.

  The Hicasso body reads its two values through `h/use-subs`, the grouped
  control, because both reads are unconditional. UIx has one door and it
  is `use-subscribe`, so the two reads are two calls — the same two
  edges, on the same two registrations."
  [{:keys [slug title published? tags]}]
  (let [open?      (uixa/use-subscribe [::subs/tags-open? slug])
        tags-label (uixa/use-subscribe [::subs/t :feed/tags])
        {:keys [dispatch dispatch-sync]} (uixa/use-frame)
        address    {:to routes/article :params {:slug slug}}]
    ($ :li.article-row
       ($ :a {:class    "article-link"
              :href     (href-for address)
              :on-click (link-click dispatch address)}
          title)
       (when-not published?
         ($ :span.draft-badge "draft"))
       (when (seq tags)
         ($ :div.tags
            ($ :button.tags-toggle
               {:type          "button"
                :aria-expanded (str (boolean open?))
                :on-click      (fn [_] (dispatch-sync [::subs/tags-open? slug (not open?)]))}
               tags-label)
            (when open?
              ($ :ul.tag-list
                 (for [tag tags]
                   ($ :li.tag {:key tag} tag)))))))))

;; ---------------------------------------------------------------------------
;; The pager
;; ---------------------------------------------------------------------------

(defui pager
  "The feed's pager. Every control is a link carrying `{:page n}` at
  `:query`, because the page is a URL in this application and not a key
  in `app-db`; the ends are `<span>`s rather than disabled links, for the
  reason the Hicasso body states.

  Both labels are read OUTSIDE the branches that place them, so the page
  a reader is on does not change which strings this body reads — the
  same read-set property the Hicasso body is written for, and one a
  transcription can lose without the DOM changing at all.

  It goes one step further than the Hicasso body and has to: the three
  strings and the two pagination reads sit above the `when` that decides
  whether a pager exists at all, because a hook cannot be conditional.
  The Hicasso body reads them INSIDE that `when`, so a single-page feed
  reads five fewer values there than here. It is not a difference the
  shipped seed can express — seven articles over a page size of three is
  three pages, so both arms take the branch on every render — and the
  driver's population pin names the seed for exactly this class of
  reason.

  ## AND THAT IS WHY [[feed-empty]]'S REPAIR IS NOT APPLIED HERE

  The two conditional reads look like one defect and are not. Splitting
  this body the way [[feed-empty]] splits the empty state would put a
  child boundary on the MEASURED page — the seed always renders a pager —
  so this arm would render one component more than the Hicasso arm on
  every visit, which is the same class of error in the same direction as
  the one that repair removes, only larger and always on. The empty
  state's boundary is free precisely because the branch that places it
  never runs at the pinned seed.

  What is left here is a difference that the seed makes INERT rather than
  one it hides: at three pages both arms read the same five values, and a
  seed that ever made the feed single-page would move the population
  before it moved this."
  [_]
  (let [page                  (uixa/use-subscribe [::subs/current-page])
        pages                 (uixa/use-subscribe [::subs/page-count])
        {:keys [dispatch]}    (uixa/use-frame)
        pagination            (uixa/use-subscribe [::subs/t :feed/pagination])
        previous              (uixa/use-subscribe [::subs/t :feed/previous])
        next-label            (uixa/use-subscribe [::subs/t :feed/next])
        page-address          (fn [n] {:to routes/feed :query {:page n}})]
    (when (< 1 pages)
      ($ :nav.pager {:aria-label pagination}
         (if (= 1 page)
           ($ :span.pager-prev {:aria-disabled "true"} previous)
           (let [address (page-address (dec page))]
             ($ :a {:class    "pager-prev"
                    :href     (href-for address)
                    :on-click (link-click dispatch address)}
                previous)))
         ($ :ol.pager-pages
            (for [n (range 1 (inc pages))]
              ($ :li.pager-page {:key n}
                 (if (= n page)
                   ($ :span.pager-current {:aria-current "page"} (str n))
                   (let [address (page-address n)]
                     ($ :a {:class    "pager-link"
                            :href     (href-for address)
                            :on-click (link-click dispatch address)}
                        (str n)))))))
         (if (= page pages)
           ($ :span.pager-next {:aria-disabled "true"} next-label)
           (let [address (page-address (inc page))]
             ($ :a {:class    "pager-next"
                    :href     (href-for address)
                    :on-click (link-click dispatch address)}
                next-label)))))))

;; ---------------------------------------------------------------------------
;; The digest — runtime-selected content
;; ---------------------------------------------------------------------------

(defui prose-block
  "A paragraph."
  [{:keys [block]}]
  ($ :p.block-prose (:block/text block)))

(defui list-block
  "A bulleted list, and the one renderer here that REFUSES its input — the
  Hicasso body's contract, kept: a list block with no `:block/items` is a
  payload that arrived broken, and rendering an empty list for it would
  put a silent hole on the page.

  It never throws in any measured arm: the seeded digest arrives whole.
  It is transcribed because a donor that could not fail where the
  original fails is a donor rendering a different application."
  [{:keys [block]}]
  (let [items (:block/items block)]
    (when (nil? items)
      (throw (ex-info "a list block arrived without its items"
                      {:block/id (:block/id block)})))
    ($ :ul.block-list
       (for [item items]
         ($ :li.block-item {:key item} item)))))

(defui callout-block
  "An aside whose emphasis TAG and colour are both chosen from the block's
  tone at render time.

  The branch over two literal elements is UIx's spelling of the Hicasso
  body's computed head; see the namespace docstring, §WHERE THE TWO ARMS
  ARE NOT THE SAME."
  [{:keys [block]}]
  (let [warning? (= :warning (:block/tone block))
        colour   (uixa/use-subscribe [::subs/token (if warning? :danger :accent)])]
    ($ :aside.block-callout {:style {:color colour}}
       (if warning?
         ($ :strong {:class "block-emphasis"} (:block/text block))
         ($ :em {:class "block-emphasis"} (:block/text block))))))

(defui unsupported-block
  "A block of a kind this build has no renderer for — the EXPECTED
  failure, which stays data."
  [{:keys [block]}]
  (let [label (uixa/use-subscribe [::subs/t :digest/unsupported])]
    ($ :p.block-unsupported
       label
       " "
       ($ :code.block-kind (str (:block/kind block))))))

(def block-views
  "Block kind to the view that renders it — the same plain map of heads
  the Hicasso page holds, with UIx components in it."
  {:block/prose   prose-block
   :block/list    list-block
   :block/callout callout-block})

(defui digest-body
  "The blocks, each rendered by whichever view its own kind names.

  A runtime component in head position is the one place UIx's `$` and
  Hicasso's hiccup agree exactly: `$` compiles a non-keyword head as a
  COMPONENT and takes the value at runtime, so this is the same
  expression the Hicasso body writes."
  [_]
  (let [blocks (uixa/use-subscribe [::subs/digest-blocks])]
    ($ :div.digest-body
       (for [{:block/keys [id kind] :as block} blocks]
         ($ (get block-views kind unsupported-block) {:key id :block block})))))

(defui digest
  "The digest region, and this page's nested error region."
  [_]
  (let [;; READ AND NOT PLACED, deliberately. The Hicasso body reads the
        ;; blocks for its region's `:reset-key`; nothing in this arm's
        ;; markup needs them. Dropping the read would give this boundary a
        ;; SMALLER read set than the one it stands in for — one fewer edge
        ;; into `[::subs/digest-blocks]`, so one fewer re-render when the
        ;; digest moves — which is the quiet way a donor becomes a
        ;; strawman.
        _blocks                 (uixa/use-subscribe [::subs/digest-blocks])
        loading?                (uixa/use-subscribe [::subs/digest-loading?])
        heading                 (uixa/use-subscribe [::subs/t :digest/heading])
        problem                 (uixa/use-subscribe [::subs/t :digest/problem])
        retry                   (uixa/use-subscribe [::subs/t (if loading? :digest/loading :digest/retry)])
        {:keys [dispatch-sync]} (uixa/use-frame)]
    ($ :section.digest
       ($ :h3.digest-heading heading)
       ($ error-region
          {:fallback ($ :div.digest-error {:role "alert"}
                        ($ :p.digest-problem problem)
                        ($ :button.digest-retry
                           {:type     "button"
                            :disabled loading?
                            :on-click (fn [_] (dispatch-sync [::events/reload-digest]))}
                           retry))}
          ($ digest-body {})))))

;; ---------------------------------------------------------------------------
;; The feed page and the shell
;; ---------------------------------------------------------------------------

(defui feed-empty
  "The empty state, and the one read on this page that belongs to a BRANCH
  rather than to a body.

  The Hicasso `feed-page` writes `[:p.feed-empty (h/sub [::subs/t
  :feed/empty])]` inside its empty branch, so a feed with rows in it never
  records an edge on that string. `use-subscribe` is a React hook and a
  hook cannot be conditional, so the transcription that read it beside the
  rows and the heading gave this arm ONE SUBSCRIPTION AND ONE HOOK THE
  HICASSO ARM DOES NOT HAVE — on the shipped seed, which is seven
  articles and never empty. That is work in the DENOMINATOR, and it moves
  a `Hicasso / UIx` ratio in the numerator's favour.

  A child boundary is the ordinary UIx repair and it costs the measured
  population nothing: the branch that would place it never renders at the
  pinned seed, so the arm pays neither the boundary nor the read. The DOM
  is `<p class=\"feed-empty\">…` either way, so the driver's canonical-DOM
  gate reads the same page in both states."
  [_]
  ($ :p.feed-empty (uixa/use-subscribe [::subs/t :feed/empty])))

(defui feed-page
  "The list, keyed by slug. The digest, the pager and the empty state are
  CHILD boundaries rather than markup written here, which is what keeps
  this body's read set to the rows and its heading — the same two reads
  the Hicasso body makes unconditionally, and no third.

  [[feed-empty]] carries the reason the empty state is a boundary here and
  is markup there."
  [_]
  (let [rows    (uixa/use-subscribe [::subs/feed])
        heading (uixa/use-subscribe [::subs/t :feed/heading])]
    ($ :section.feed
       ($ :h2 heading)
       ($ digest {})
       (if (seq rows)
         ($ :ul.article-list
            (for [{:keys [slug] :as row} rows]
              ($ article-row {:key         slug
                              :slug        slug
                              :title       (:title row)
                              :published?  (:published? row)
                              :tags        (:tags row)})))
         ($ feed-empty {}))
       ($ pager {}))))

(defui app
  "The whole page: the chrome, then the feed pane under one error region.

  **The route is not branched on here, and that is the one structural
  difference between this shell and the Hicasso one.** The Hicasso `app`
  chooses its pane from `[:rf.route/id]`; this arm renders the feed
  unconditionally, because the driver's row set contains no navigation
  (a route-link click ends at routing's ASYNC door and cannot be
  bracketed by a one-interaction-to-one-paint window — the driver's
  §THE ROW SET states it in full) and a pane this page can never reach
  is code that would be transcribed to be dead.

  What it costs is one `[:rf.route/id]` read on one boundary out of
  eleven, and it costs it in the DENOMINATOR'S favour — the arm doing
  LESS is the one C3's ratio divides by, so the omission cannot flatter
  the numerator."
  [_]
  (let [surface (uixa/use-subscribe [::subs/token :surface])
        ink     (uixa/use-subscribe [::subs/token :ink])
        pane    (uixa/use-subscribe [::subs/t :app/pane-error])]
    ($ :main.slice {:style {:background surface :color ink}}
       ($ chrome {})
       ($ error-region
          {:fallback ($ :p.pane-error {:role "alert"} pane)}
          ($ feed-page {})))))

(defn root
  "The element a root renders: the page, scoped to `frame-id`.

  `frame-provider` SCOPES an already-created frame and renders no DOM of
  its own, so it cannot move the canonical-DOM gate. The frame is made by
  the driver, outside every window."
  [frame-id]
  ($ uixa/frame-provider {:frame frame-id}
     ($ app {})))

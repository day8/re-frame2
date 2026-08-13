(ns re-frame.hicasso.examples.slice.db
  "THE SLICE'S SHAPE, AND THE PURE FUNCTIONS OVER IT (rf2-hic-025).

  One `app-db` map, described once, plus the handful of calculations the
  event handlers and subscriptions both need. Nothing here requires
  re-frame or Hicasso: it is the layer a test can exercise with `=` and
  no runtime at all, which is the ladder's L0 and the reason it is its
  own namespace.

  ## The shape

      {:articles {slug {:slug :title :body :published? :tags}}
       :order    [slug …]                ;; publication order, the list's key order
       :drafts   {slug {:title :body :published?}}
       :save     {:status :idle|:saving|:failed|:saved  :slug :problem}
       :digest   {:status :ready|:loading  :blocks [block …]}
       :locale   :en
       :theme    :light}

  ## THE PAGE IS NOT IN HERE, AND THAT IS DELIBERATE (rf2-hic-074)

  Pagination arrives with rf2-hic-074, and the page number is the one
  piece of its state this map does **not** hold. It rides the feed
  route's `?page=` query instead, so it is state in the routing
  partition of the same `app-db` — `[:rf.runtime/routing :current]`,
  read through `[:rf.route/query]` — rather than a second copy beside
  `:articles` that the address bar could disagree with. That is the same
  rule [[re-frame.hicasso.examples.slice.views/article-page]] already
  keeps for the slug, and it is what makes Back and Forward work without
  a single line of history code: the browser replays the URL, the URL
  carries the page, and the list follows.

  What this namespace owns is the ARITHMETIC — [[page-count]],
  [[clamp-page]] and [[page-rows]] are pure functions of a row vector and
  a number, which is why they are testable with `=` and why a page
  number a user typed into the address bar is clamped rather than
  trusted.

  ## `:drafts` is SPARSE, and that is the whole edit model

  There is no \"load the article into the form\" step and no effect that
  copies one map into another. A slug with no draft entry reads the
  ARTICLE ([[draft-for]]), so the editor is populated the moment the
  route resolves; the first keystroke creates the draft entry; discarding
  removes it. Dirty-ness is therefore `(contains? drafts slug)` rather
  than a flag somebody has to remember to clear, and \"reset\" is a
  `dissoc` rather than a re-copy.

  ## THERE IS NO `:revision` KEY, AND ITS ABSENCE WAS MEASURED (rf2-36bd)

  An earlier draft of this application carried one: a `{slug n}` counter,
  a `(fnil inc 0)` in two handlers, a subscription, and `::h/revision` on
  both text fields. It was written from HD-019's reset law and from the
  sentence that used to stand here — *a discard that only removed the
  draft would leave the field showing what the user typed*.

  That sentence is false of this application, and the way it was found is
  the only reason to keep talking about it: the counter's bump was deleted
  from `::discard` and the browser lane did not move — 1474 tests, 9154
  assertions, zero failures, exactly the control. The bookkeeping was
  inert.

  It is inert for a mechanical reason worth carrying, because it decides
  when a consumer needs one. `impl.codec`'s `revision-key` states the
  whole delivery: **the revision is a value the body reads, and its change
  RE-RUNS THE BODY**; the re-run re-commits the element and the commit
  re-asserts the model over whatever the DOM holds. React marks that host
  update on props-object identity, so *any* re-render of the boundary does
  the same re-assert for free. A discard here already moves three of the
  editor's reads — the draft, [[dirty?]], and the save status — so the
  body re-runs whatever the counter does.

  **`::h/revision` is therefore the door for a reset that leaves every
  other read the body makes `=`** — not, as the first report had it, for a
  field that outlives its reset. This field outlives its reset and needs
  nothing. `flow-dom-cljs-test`'s reset section witnesses both halves: the
  discard that works, and a DOM drifted by a write React never saw, which
  the same re-render repairs."
  (:refer-clojure :exclude [empty?]))

(def page-size
  "How many articles one page of the feed shows.

  Small, because the seed is small and a pager nobody can reach proves
  nothing: three articles over a page size of three would render a
  single page and every pagination assertion below would be vacuously
  green. Seven articles over three pages — the last of them short — is
  the smallest roster that exercises a first page, a middle page, a
  short last page, and both ends of the pager at once."
  3)

(def digest
  "THE EDITOR'S DIGEST — the runtime-selected content, as data
  (rf2-hic-074).

  A vector of BLOCKS. Each carries its own `:block/kind`, and the view
  layer picks the renderer for that kind at render time
  ([[re-frame.hicasso.examples.slice.views/block-views]]) — so the
  hiccup SHAPE of this region is a fact about the data rather than about
  the body that renders it. Specification §3.3 blesses exactly this:
  runtime-selected Hiccup and ordinary Clojure transformations over it
  are a feature, not a gap waiting on a compiler.

  Two of the four blocks are here for what they prove rather than for
  what they say:

  - `:block/ticker` is a kind **this build does not know**. It is an
    EXPECTED failure, so it stays data: the region renders a named note
    and nothing throws. §7's errors row asks for exactly that
    distinction, and a region that threw on unknown content would make
    every future content type a deployment incident.
  - the two `:block/callout` tones pick their emphasis TAG at render
    time, which is the smallest honest demonstration that a tag is a
    value.

  The text is CONTENT and is not translated. That boundary is already
  the application's — see
  `re-frame.hicasso.examples.slice.i18n-dom-cljs-test`'s row on it — and
  the digest's chrome (its heading, its failure sentence, its retry) is
  read through `[::subs/t …]` like every other string."
  [{:block/id "intro" :block/kind :block/prose
    :block/text "Three ideas the archive keeps coming back to."}
   {:block/id   "reading" :block/kind :block/list
    :block/items ["Keys are domain ids"
                  "Boundaries are components"
                  "Revision is a counter"]}
   {:block/id "url" :block/kind :block/callout :block/tone :accent
    :block/text "A page is a query parameter, so Back restores it."}
   {:block/id "care" :block/kind :block/callout :block/tone :warning
    :block/text "A block that breaks takes its own region down, not the page."}
   {:block/id "ticker" :block/kind :block/ticker
    :block/text "A kind this build has never heard of."}])

(def digest-truncated
  "The digest as a PARTIAL DELIVERY leaves it: the list block arrived
  without its items.

  Not a state the application can reach on its own — the stand-in
  content server always sends [[digest]] whole — and that is the point.
  It is the shape of a payload that was cut short somewhere between the
  server and the page, which is the ordinary way a render-phase throw
  reaches a real application: not from a bug in the body, but from data
  that broke the contract the body was written against. A witness
  installs it through the application's own arrival event, and the
  region's own error boundary is what the user meets."
  (mapv (fn [b] (cond-> b (= :block/list (:block/kind b)) (dissoc :block/items)))
        digest))

(def seed
  "The application's starting `app-db`. A literal, so a test seeds a frame
  with `[[:rf/set-db db/seed]]` and gets the same page the browser shows."
  {:articles {"hicasso"    {:slug "hicasso" :title "Hicasso, briefly"
                            :body "A boundary is a real React function component."
                            :published? true
                            :tags ["substrate" "react"]}
              "intents"    {:slug "intents" :title "Intents are data"
                            :body "A click carries a vector, and two renders are equal."
                            :published? true
                            :tags ["intents"]}
              "controls"   {:slug "controls" :title "Controlled, synchronously"
                            :body "The model is the only place the text lives."
                            :published? false
                            :tags ["forms" "react"]}
              "keys"       {:slug "keys" :title "Keys are domain ids"
                            :body "A list keyed by its index reuses the wrong row."
                            :published? true
                            :tags ["lists" "react"]}
              "boundaries" {:slug "boundaries" :title "Boundaries are components"
                            :body "One boundary, one unit of re-render."
                            :published? true
                            :tags ["substrate"]}
              "revision"   {:slug "revision" :title "Revision is a counter"
                            :body "A reset is a change the field can see."
                            :published? true
                            :tags ["forms"]}
              "pages"      {:slug "pages" :title "Pages ride the URL"
                            :body "The page is a query parameter, so Back restores it."
                            :published? false
                            :tags ["routing"]}}
   :order    ["hicasso" "intents" "controls" "keys" "boundaries" "revision" "pages"]
   :drafts   {}
   :save     {:status :idle}
   :digest   {:status :ready :blocks digest}
   :locale   :en
   :theme    :light})

(defn article
  "The article at `slug`, or nil. A missing article is a nil rather than
  a refusal: a URL is user input, and `/article/nonsense` is a page the
  application renders rather than an error it raises."
  [db slug]
  (get-in db [:articles slug]))

(defn listed
  "The articles in `:order`, as a vector of maps. Order comes from
  `:order` and not from the `:articles` map, because a map has none —
  and a keyed list whose order is iteration order is a list that
  reorders itself when an entry is added."
  [db]
  (into [] (keep #(article db %)) (:order db)))

(defn draft-for
  "The editable value of `slug` — the draft if one exists, otherwise the
  article's own fields. See the namespace docstring on why `:drafts` is
  sparse.

  Answers nil for a slug with neither, so a view can tell *no such
  article* from *an article nobody has touched*."
  [db slug]
  (or (get-in db [:drafts slug])
      (when-some [a (article db slug)]
        (select-keys a [:title :body :published?]))))

(defn dirty?
  "Has `slug` been edited since it was last saved or discarded?"
  [db slug]
  (contains? (:drafts db) slug))

(defn blank?
  "Is `s` absent, or nothing but whitespace? The one string predicate the
  validation below needs, written out rather than reached for, so this
  namespace requires nothing."
  [s]
  (or (nil? s) (= "" (.trim (str s)))))

(defn problems
  "The problems a draft has, as a vector of PROBLEM KEYWORDS in a stable
  order — never as sentences.

  Keywords because a message is a fact about the reader's locale and this
  is a fact about the data. The view translates one through
  `[::subs/t …]`; a handler that produced English would make the
  application's validation untranslatable and its tests dependent on the
  wording.

  This is the LOCAL half of validation. The server's half —
  `:problem/title-taken` — cannot be decided here, and
  [[re-frame.hicasso.examples.slice.events]] is where it arrives."
  [draft]
  (cond-> []
    (blank? (:title draft)) (conj :problem/title-blank)
    (blank? (:body draft))  (conj :problem/body-blank)))

(defn title-taken?
  "Does any article OTHER than `slug` already carry `title`?

  The stand-in server's rule, kept here because it is a pure question
  about the data and because the events namespace and its test both ask
  it. Case- and whitespace-insensitive, which is what makes it a rule
  about titles rather than about strings."
  [db slug title]
  (let [norm #(-> (str %) .trim .toLowerCase)
        want (norm title)]
    (boolean
      (some (fn [[s a]] (and (not= s slug) (= want (norm (:title a)))))
            (:articles db)))))

;; ---------------------------------------------------------------------------
;; Pagination — arithmetic over a row vector, and nothing else (rf2-hic-074)
;;
;; Over ROWS rather than over `db`, because the page a user is looking at is
;; not in `db` (see the namespace docstring): it arrives from the route, and
;; these three take it as an argument. That is also what makes them the
;; sharpest thing in this file to test — three numbers in, one answer out.
;; ---------------------------------------------------------------------------

(defn page-count
  "How many pages `rows` makes, at [[page-size]]. Never less than one: an
  empty feed is one empty page rather than zero pages, so the pager has a
  number to call the page the user is on and `page-of 1` is always a
  legal question."
  [rows]
  (max 1 (quot (+ (count rows) (dec page-size)) page-size)))

(defn clamp-page
  "The page `n` names, brought inside the range `rows` actually has.

  A page number is USER INPUT — it comes off the URL, where anybody can
  type it — so `?page=0`, `?page=-3` and `?page=900` are pages the
  application renders rather than errors it raises. Clamping rather than
  redirecting keeps the address bar the user's: the URL they typed still
  says what they asked for, and the page shows them the nearest thing
  that exists. `nil` (no `?page=` at all) is the first page."
  [rows n]
  (-> (or n 1) (max 1) (min (page-count rows))))

(defn page-rows
  "The slice of `rows` on page `n`, clamped. A vector, so the caller's
  `for` is over the same kind of thing whichever page it asks for."
  [rows n]
  (let [start (* (dec (clamp-page rows n)) page-size)]
    (subvec (vec rows) start (min (count rows) (+ start page-size)))))

(defn commit
  "Fold `draft` into the article at `slug` — the pure half of a
  successful save, so the handler that runs it is three lines and this is
  the part a test can drive with a map."
  [db slug draft]
  (-> db
      (update-in [:articles slug] merge (select-keys draft [:title :body :published?]))
      (update :drafts dissoc slug)))

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
       :revision {slug n}                ;; the controlled fields' reset trigger
       :save     {:status :idle|:saving|:failed|:saved  :slug :problem}
       :locale   :en
       :theme    :light}

  ## `:drafts` is SPARSE, and that is the whole edit model

  There is no \"load the article into the form\" step and no effect that
  copies one map into another. A slug with no draft entry reads the
  ARTICLE ([[draft-for]]), so the editor is populated the moment the
  route resolves; the first keystroke creates the draft entry; discarding
  removes it. Dirty-ness is therefore `(contains? drafts slug)` rather
  than a flag somebody has to remember to clear, and \"reset\" is a
  `dissoc` rather than a re-copy.

  ## `:revision` is a COUNTER, not a value

  HD-019's reset law re-baselines a controlled field to its model when
  `::h/revision` CHANGES. A discard that only removed the draft would
  leave the field showing what the user typed — the model moved back to
  where it already was, so React's own value diff sees nothing to do. The
  counter is what makes the reset observable to the field, and it is
  bumped by exactly the two handlers that need it (discard, and a
  successful save)."
  (:refer-clojure :exclude [empty?]))

(def seed
  "The application's starting `app-db`. A literal, so a test seeds a frame
  with `[[:rf/set-db db/seed]]` and gets the same page the browser shows."
  {:articles {"hicasso"  {:slug "hicasso" :title "Hicasso, briefly"
                          :body "A boundary is a real React function component."
                          :published? true
                          :tags ["substrate" "react"]}
              "intents"  {:slug "intents" :title "Intents are data"
                          :body "A click carries a vector, and two renders are equal."
                          :published? true
                          :tags ["intents"]}
              "controls" {:slug "controls" :title "Controlled, synchronously"
                          :body "The model is the only place the text lives."
                          :published? false
                          :tags ["forms" "react"]}}
   :order    ["hicasso" "intents" "controls"]
   :drafts   {}
   :revision {}
   :save     {:status :idle}
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

(defn revision
  "The reset trigger for `slug`'s controlled fields. Starts at 0 so the
  first render carries a value rather than a nil that later becomes a
  number — a change from nil is a change, and the field would re-baseline
  on the second render for no reason a reader could see."
  [db slug]
  (get-in db [:revision slug] 0))

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

(defn commit
  "Fold `draft` into the article at `slug` — the pure half of a
  successful save, so the handler that runs it is three lines and this is
  the part a test can drive with a map."
  [db slug draft]
  (-> db
      (update-in [:articles slug] merge (select-keys draft [:title :body :published?]))
      (update :drafts dissoc slug)
      (update-in [:revision slug] (fnil inc 0))))

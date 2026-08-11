(ns re-frame.hicasso.examples.slice.i18n
  "THE SLICE'S STRINGS AND TOKENS — ordinary data, and nothing else
  (rf2-hic-025).

  Specification §7 rows i18n and theming together, and the row's whole
  claim is that a Hicasso application needs **no subsystem** for either:
  a locale is a value in `app-db`, a string table is a map, a theme is a
  map of tokens, and a subscription is how a view reads one. Switching
  either at runtime is then an ordinary event, and the re-render that
  follows is the ordinary re-render every other event gets.

  So this namespace requires nothing at all — not `re-frame.core`, not
  the Hicasso door. It is two maps and two lookups. The subscriptions
  that project them live in
  [[re-frame.hicasso.examples.slice.subs]] with every other
  subscription, because a translation is not a special kind of read.

  ## Why the string table is keyed by a KEYWORD, not by English

  Keying by the English sentence is the spelling most i18n libraries
  offer and it loses the one property this slice is evidence for:
  a missing translation becomes indistinguishable from a present one.
  [[t]] answers a visible sentinel — `‹feed/heading›` — so a locale with
  a hole shows the hole on screen and in a test's assertion, rather than
  silently falling back to English and passing.

  ## Why the theme is TOKENS rather than class names

  A token is a value a view can read, assert on and interpolate; a class
  name is a promise about a stylesheet the test cannot see. The views
  write `{:style {:background (i18n/token theme :surface)}}`, so
  `re-frame.hicasso.examples.slice.theme-dom-cljs-test` can read the
  applied colour off the real DOM and say the switch took effect. A
  class-swap witness would be asserting that a string changed."
  (:refer-clojure :exclude [t]))

(def locales
  "The locales this slice ships, in menu order. A vector rather than the
  key set of [[strings]] so the `<select>` has a stable order that does
  not depend on map iteration."
  [:en :fr])

(def strings
  "The string table — locale to key to sentence.

  Every key is namespaced by the region of the page it belongs to, so a
  table this size still reads as a page rather than as a bag."
  {:en {:app/title          "Chronicle"
        :app/locale         "Language"
        :app/theme          "Theme"
        :app/pane-error     "This page could not be displayed."
        :theme/light        "Light"
        :theme/dark         "Dark"
        :feed/heading       "Articles"
        :feed/empty         "Nothing published yet."
        :feed/tags          "Tags"
        :article/back       "All articles"
        :article/missing    "No such article."
        :editor/heading     "Edit"
        :editor/title       "Title"
        :editor/body        "Body"
        :editor/published   "Published"
        :editor/save        "Save"
        :editor/saving      "Saving…"
        :editor/discard     "Discard changes"
        :editor/saved       "Saved."
        :editor/retry       "Try again"
        :problem/title-blank "A title is required."
        :problem/body-blank  "An article needs a body."
        :problem/title-taken "That title is already used by another article."}
   :fr {:app/title          "Chronique"
        :app/locale         "Langue"
        :app/theme          "Thème"
        :app/pane-error     "Cette page n'a pas pu être affichée."
        :theme/light        "Clair"
        :theme/dark         "Sombre"
        :feed/heading       "Articles"
        :feed/empty         "Rien de publié pour l'instant."
        :feed/tags          "Étiquettes"
        :article/back       "Tous les articles"
        :article/missing    "Article introuvable."
        :editor/heading     "Modifier"
        :editor/title       "Titre"
        :editor/body        "Corps"
        :editor/published   "Publié"
        :editor/save        "Enregistrer"
        :editor/saving      "Enregistrement…"
        :editor/discard     "Annuler les modifications"
        :editor/saved       "Enregistré."
        :editor/retry       "Réessayer"
        :problem/title-blank "Un titre est obligatoire."
        :problem/body-blank  "Un article doit avoir un corps."
        :problem/title-taken "Ce titre est déjà utilisé par un autre article."}})

(def themes
  "The theme tokens — theme to token to value. Values, so a view can
  interpolate one and a witness can read it back off the DOM."
  {:light {:surface "rgb(255, 255, 255)"
           :ink     "rgb(26, 26, 26)"
           :accent  "rgb(11, 107, 203)"
           :danger  "rgb(176, 32, 32)"}
   :dark  {:surface "rgb(18, 21, 26)"
           :ink     "rgb(232, 234, 237)"
           :accent  "rgb(124, 183, 255)"
           :danger  "rgb(255, 138, 128)"}})

(defn t
  "The sentence for `k` in `locale`, or a VISIBLE sentinel when the table
  has no entry.

  The sentinel is the point: a hole that fell back to English would be
  invisible to a French witness and to a French reader, and the two
  failures look identical from inside the application."
  [locale k]
  (or (get-in strings [locale k])
      (str "‹" (subs (str k) 1) "›")))

(defn token
  "The value of theme token `k` under `theme`, or nil. A token nobody
  declared is nil rather than a sentinel: it reaches a CSS property,
  where an invented string would be a silent no-op anyway, and the
  theme-token roster is small enough to read."
  [theme k]
  (get-in themes [theme k]))

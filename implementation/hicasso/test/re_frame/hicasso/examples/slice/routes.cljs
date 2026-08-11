(ns re-frame.hicasso.examples.slice.routes
  "THE SLICE'S TWO ROUTES (rf2-hic-025).

  Two `reg-route` calls and nothing else. Routing is `day8/re-frame2-
  routing`'s, reached through its own public door; Hicasso reaches the
  same registrations through core's late-bind seams, so a `h/route-link`
  in a view and a `[:rf.route/navigate …]` from a handler are the same
  two definitions read two ways.

  The route ids the views read back are `[:rf.route/id]` and
  `[:rf.route/params]` — routing's own subscriptions, registered at its
  namespace load. The slice registers no route subscription of its own
  and holds no copy of the current route in `app-db`, which is what keeps
  the URL and the page from being two facts that can disagree.

  ## Why registration is a FUNCTION and not just a top-level form

  Both, in fact: the `reg-route` calls run at namespace load, the way a
  consumer writes them. [[register!]] is the same two calls, exposed,
  and it exists because `re-frame.test-support`'s reset fixture restores
  the registrar to a baseline captured at fixture-BUILD time. A test
  namespace that loads after that snapshot was taken finds its routes
  rolled back before its first `deftest` runs. A consumer never meets
  this; a test does, on the first row it writes. `reg-route` is
  idempotent for an unchanged registration, so calling both is free.

  That is the rf2-hic-025 authoring report's second finding, and it is a
  TESTING-surface finding rather than an authoring one: nothing in the
  application had to change, but the shape a consumer copies from a
  guide (`reg-route` at the top level, once) does not survive the
  supported test fixture without a second door being exposed for the
  test's sake."
  (:require [re-frame.routing :as routing]))

(def feed
  "The list page. `/slice`."
  ::feed)

(def article
  "One article, by slug. `/slice/article/:slug`."
  ::article)

(defn register!
  "Register the slice's routes. Idempotent; see the namespace docstring
  on why it is a function as well as a load-time effect.

  ## Why every path is under `/slice` (the report's finding 8)

  The route ids are namespaced keywords and cannot collide. **The PATHS
  are strings in a process-global registry**, and this application shares
  that registry with every other example in the repository's node test
  bundle. `/` and `/article/:slug` are the two most natural paths a
  consumer would write, and they are exactly the two RealWorld already
  holds — so registering them here made `match-url` answer this app's
  route for RealWorld's URLs, and twelve of its assertions failed naming
  `:re-frame.hicasso.examples.slice.routes/article` where they expected
  `:realworld.article/show`.

  Nothing warned. `reg-route` emits `:rf.warning/route-shadowed-by-equal-
  score` for a co-matchable equal-rank pattern, and it did not fire — the
  ranks differ, so the guard had nothing to say while the resolution was
  wrong anyway.

  A consumer's own application never meets this, because their registry
  holds only their routes. A repository whose test bundle loads a dozen
  applications into one process does, and the prefix is the whole fix."
  []
  (routing/reg-route feed
    {:doc "The article list."}
    "/slice")
  (routing/reg-route article
    {:doc "One article, with its editor."}
    "/slice/article/:slug")
  nil)

(register!)

(ns re-frame.hicasso.examples.navigation.routes
  "THE NAVIGATION WITNESS'S TWO ROUTES — and the three pieces of route
  METADATA that are the whole of its conduct (rf2-hic-042).

  Two `reg-route` calls. Everything this application does after a
  navigation — where focus lands, where the page is scrolled to, whether
  the navigation is allowed to happen at all — is declared here, on the
  route, as data:

      :on-match   the focus-on-route recipe's hook. Fire-and-forget, and
                  taken only on a navigation that COMMITTED.
      :can-leave  the dirty guard. A read that answers a boolean; a
                  refusal blocks the leave and parks it as resumable
                  pending navigation.
      :scroll     the restoration strategy. Absent here on purpose: the
                  witness drives the two defaults (`:top` forward,
                  `:restore` on Back/Forward) and the per-call `:scroll
                  false` override, which is the precedence a route's own
                  metadata sits between.

  ## Every path is under `/navigation`, and that is not decoration

  Route IDS are namespaced keywords and cannot collide. Route PATHS are
  plain strings in a PROCESS-GLOBAL registrar, and `npm run test:cljs`
  loads every application in this repository into ONE node process — so
  the match table `match-url` walks is written by all of them at once.
  Two applications claiming one path share one entry and the earlier
  registration wins every URL, which is how rf2-hic-025's slice made
  RealWorld's URLs resolve to the slice's routes and broke twelve of its
  assertions with every other gate green. Nothing warned:
  `:rf.warning/route-shadowed-by-equal-score` asks whether two
  co-matchable routes TIE on rank, and a silent overwrite between routes
  of different ranks is a different question.

  The convention (TESTING.md §Test authoring policy) is therefore that
  every application in the shared bundle puts its paths under a distinct
  leading segment. `/navigation` and `/navigation/article/:slug` are
  this one's, and the prefix earns more than exact-duplicate detection
  would: distinct leading literals can never co-match, so it also
  forecloses one application's literal sitting under another's capture.

  ## Why registration is a FUNCTION as well as a load-time effect

  Both. The `reg-route` calls run at namespace load, the way a consumer
  writes them. [[register!]] is the same two calls exposed, because
  `re-frame.test-support`'s reset fixture restores the registrar to a
  baseline captured when the `use-fixtures` form was evaluated — a route
  registered before that snapshot is rolled back before the first
  `deftest` runs. A consumer never meets this; a test does, on its first
  row. `reg-route` is idempotent for an unchanged registration, so
  calling both costs nothing."
  (:require [re-frame.hicasso.examples.navigation.events :as events]
            [re-frame.hicasso.examples.navigation.subs :as subs]
            [re-frame.routing :as routing]))

(def feed
  "The list page. `/navigation`."
  ::feed)

(def article
  "One article, by slug. `/navigation/article/:slug`."
  ::article)

(defn register!
  "Register the witness's routes. Idempotent; see the namespace docstring
  on why it is a function as well as a load-time effect."
  []
  (routing/reg-route feed
    {:doc      "The article list."
     :on-match [[::events/pane-shown]]}
    "/navigation")
  (routing/reg-route article
    {:doc "One article, with its title editor."
     ;; The guard is on the route being LEFT, which is the article: a
     ;; typed-and-unsaved title is what must not be walked away from. The
     ;; feed carries none — there is nothing on it to lose.
     :can-leave [::subs/may-leave?]
     :on-match  [[::events/pane-shown]]}
    "/navigation/article/:slug")
  nil)

(register!)

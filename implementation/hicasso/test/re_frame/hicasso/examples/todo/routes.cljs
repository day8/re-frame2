(ns re-frame.hicasso.examples.todo.routes
  "THE FILTER IS A ROUTE (rf2-hic-086).

  Two `reg-route` calls, and the whole of what this application stores
  about which to-dos it is showing: nothing. `All`, `Active` and
  `Completed` are three URLs, the highlighted tab is derived from the
  current one, and there is no `:showing` key in `app-db` that could
  disagree with the address bar. That is the ordinary Todo shape and it
  is why the filter is worth routing rather than toggling.

  ## Every path is under `/hicasso-todo`, and that is not decoration

  Route **ids** are namespaced keywords and cannot collide. Route
  **paths** are strings in a PROCESS-GLOBAL registry, and this
  repository's node test bundle loads a dozen applications into one
  process — including `todomvc.events`, which already owns `/`,
  `/active` and `/completed`: the three most natural paths a Todo author
  writes, and exactly the three this file would have written.

  Nothing would have warned. `reg-route` emits
  `:rf.warning/route-shadowed-by-equal-score` for a co-matchable
  EQUAL-RANK pattern, and differing ranks silence it while the
  resolution is wrong anyway — which is how rf2-hic-025's slice broke
  twelve RealWorld assertions with every other gate green. The prefix is
  the whole fix, and a consumer's own application never meets the
  problem, because their registry holds only their routes.

  ## No `:rf.route/not-found` here

  It is a route a real application registers, and this one deliberately
  does not: the id is unqualified-by-feature and process-global like any
  other, `todomvc.events` already holds it, and a second registration
  would take it away from an application that needs it more. An
  unmatched URL therefore leaves the route id nil, which
  [[re-frame.hicasso.examples.todo.subs/showing]] already reads as
  `:all` — a URL is user input, so the filter coerces rather than
  trusting.

  ## Why registration is a FUNCTION as well as a load-time effect

  Both: the `reg-route` calls run at namespace load, the way a consumer
  writes them. [[register!]] is the same two calls exposed, because
  `re-frame.test-support`'s reset fixture restores the registrar to a
  baseline captured when the `use-fixtures` FORM is evaluated, and a
  route registered before that snapshot is rolled back before the first
  `deftest` runs. rf2-hic-025 found this and it reproduces here
  unchanged: `reg-sub` and `reg-event` survive, routes do not. A
  consumer never meets it; a test meets it on its first row."
  (:require [re-frame.routing :as routing]))

(def all
  "Every to-do. `/hicasso-todo`."
  ::all)

(def filtered
  "One filter's to-dos. `/hicasso-todo/:filter`, where `:filter` is
  `active` or `completed` — and anything else, because a URL is user
  input and the coercion belongs in the subscription that reads it."
  ::filtered)

(defn register!
  "Register both routes. Idempotent for an unchanged registration, so
  calling it from a test fixture as well as at load costs nothing."
  []
  (routing/reg-route all
    {:doc "Show every to-do."}
    "/hicasso-todo")
  (routing/reg-route filtered
    {:doc "Show the active or the completed to-dos."}
    "/hicasso-todo/:filter")
  nil)

(register!)

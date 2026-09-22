# Index

rf2-co91r — A DOT IN A PAGE'S BASENAME IS NOT A FILE EXTENSION. Every page in
`docs/api/` is named for the namespace it documents, so `re-frame.http.md`
publishes at `re-frame.http/` exactly as `introduction.md` publishes at
`introduction/`. The arm shipped reading the dot ALONE as a static-file
extension, before asking whether any Markdown page claimed the route, and so
reported every one of this repo's real API pages as unpublished.

Routes that resolve — these are the regression:

- a dotted API page: [http](https://day8.github.io/re-frame2/api/re-frame.http/)
- twice-dotted, because the basename is a whole namespace:
  [forms](https://day8.github.io/re-frame2/api/re-frame.fresco.forms/)
- the ordinary-page control, which never regressed:
  [intro](https://day8.github.io/re-frame2/core/introduction/)
- the static-file control: a real extension still resolves by existence alone,
  because MkDocs copies the file into the site verbatim:
  [css](https://day8.github.io/re-frame2/assets/extra.css)

Findings — the teeth, which a fix that merely stopped inferring extensions
would pull:

- a dotted route naming no page and no file is still a 404:
  [nosuch](https://day8.github.io/re-frame2/api/re-frame.nosuch/)
- a source filename is still not a URL, and the dotted basename is exactly
  where that is easiest to get wrong — `api/re-frame.http.md` is a file that
  EXISTS, so only route semantics reject it:
  [http dot md](https://day8.github.io/re-frame2/api/re-frame.http.md)
- a static file that is not there is still missing:
  [missing css](https://day8.github.io/re-frame2/assets/missing.css)

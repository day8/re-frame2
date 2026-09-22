# Index

MkDocs publishes ROUTES, not filenames. All three sources below exist, so an
arm that asked "is there a file here?" would pass all three URLs — and all
three are 404s on the built site.

Findings:

- `README.md` becomes the directory index, so this is not a route:
  [spec readme](https://day8.github.io/re-frame2/spec/README/)
- `index.md` becomes the directory itself, so neither is this:
  [story index](https://day8.github.io/re-frame2/story/index/)
- a source filename is not a URL:
  [api dot md](https://day8.github.io/re-frame2/spec/API.md)

Controls — the routes those same three files actually publish at:

- [spec](https://day8.github.io/re-frame2/spec/)
- [story](https://day8.github.io/re-frame2/story/)
- [api](https://day8.github.io/re-frame2/spec/API/)

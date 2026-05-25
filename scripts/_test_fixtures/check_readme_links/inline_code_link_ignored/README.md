# Inline Code Link Ignored

Backticked link-syntax placeholders like `[label](missing.md)` must NOT
be flagged — they're literal markup the author is documenting, not real
links.

```markdown
[Also inside a fenced block](also_missing.md)
```

The validator must report zero findings here.

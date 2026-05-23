import { ExternalLink } from "lucide-react";

interface Issue {
  severity: "ERROR" | "WARNING" | "ADVISORY";
  category: string;
  description: string;
  timestamp: string;
}

const issues: Issue[] = [
  {
    severity: "ERROR",
    category: "handler-exception",
    description: ":counter-inc threw NullPointerException",
    timestamp: "12:30:05",
  },
  {
    severity: "WARNING",
    category: "missing-doc",
    description: "sub ::counter has no :doc",
    timestamp: "12:30:05",
  },
  {
    severity: "ADVISORY",
    category: "fx-skipped",
    description: ":rf.nav/scroll skipped (node)",
    timestamp: "12:30:05",
  },
];

const severityColors: Record<string, string> = {
  ERROR: "var(--devtools-error)",
  WARNING: "var(--devtools-warning)",
  ADVISORY: "var(--devtools-text-muted)",
};

export function IssuesPanel() {
  if (issues.length === 0) {
    return (
      <div className="p-8 text-center devtools-body text-[var(--devtools-text-muted)]">
        No issues in this epoch.
      </div>
    );
  }

  return (
    <div className="p-4 overflow-auto">
      <div className="space-y-2">
        {issues.map((issue, idx) => (
          <div
            key={idx}
            className="flex items-center gap-4 px-3 py-2.5 rounded border hover:bg-[var(--devtools-hover)] cursor-pointer devtools-body"
            style={{
              borderLeft: `3px solid ${severityColors[issue.severity]}`,
              borderColor: "var(--devtools-border)",
            }}
          >
            <span
              className="devtools-micro uppercase font-semibold w-20"
              style={{ color: severityColors[issue.severity] }}
            >
              {issue.severity}
            </span>
            <span className="devtools-caption text-[var(--devtools-text-muted)] w-36">
              {issue.category}
            </span>
            <span className="flex-1 text-[var(--devtools-text)]">{issue.description}</span>
            <span className="devtools-caption text-[var(--devtools-text-muted)] devtools-mono">
              {issue.timestamp}
            </span>
            <button className="text-[var(--devtools-active)] hover:underline">
              <ExternalLink className="w-3.5 h-3.5" />
            </button>
          </div>
        ))}
      </div>

      <div className="mt-4 pt-4 border-t border-[var(--devtools-border)] devtools-caption text-[var(--devtools-text-muted)]">
        click a row → Event panel (the cascade) · click ↗ → source file:line
      </div>
    </div>
  );
}

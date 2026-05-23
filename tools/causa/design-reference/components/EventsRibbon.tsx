import { ChevronLeft, ChevronRight, ChevronsRight, Target, Plus, X } from "lucide-react";

export function EventsRibbon() {
  return (
    <div className="h-9 px-3 flex items-center justify-between border-b bg-background border-[var(--devtools-border)]">
      <div className="flex items-center gap-3">
        <span className="devtools-body text-[var(--devtools-text-muted)]">Events:</span>

        <div className="flex items-center gap-1">
          <button className="p-1 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text)]">
            <ChevronLeft className="w-3.5 h-3.5" />
          </button>
          <button className="p-1 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text)]">
            <ChevronRight className="w-3.5 h-3.5" />
          </button>
          <button className="p-1 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text)]">
            <ChevronsRight className="w-3.5 h-3.5" />
          </button>
        </div>

        <button className="devtools-micro flex items-center gap-1 px-2 py-1 rounded bg-[var(--devtools-active)] text-white">
          <Target className="w-3 h-3" />
          focus
        </button>

        <div className="flex items-center gap-1.5">
          <div className="devtools-micro px-2 py-1 rounded bg-secondary text-secondary-foreground flex items-center gap-1">
            source: view
            <button className="hover:text-foreground">
              <X className="w-3 h-3" />
            </button>
          </div>

          <button className="p-1 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text-muted)]">
            <Plus className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      <button className="devtools-caption px-2 py-1 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text-muted)]">
        Clear Filters
      </button>
    </div>
  );
}

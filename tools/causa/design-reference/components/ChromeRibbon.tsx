import { ChevronDown, Settings, X } from "lucide-react";

export function ChromeRibbon() {
  return (
    <div className="h-8 px-3 flex items-center justify-between border-b bg-[var(--devtools-chrome-bg)] border-[var(--devtools-border)]">
      <div className="flex items-center gap-3">
        <div className="devtools-body-tight font-semibold text-[var(--devtools-text)]">
          ❖ Causa
        </div>

        <button className="devtools-body-tight flex items-center gap-1 px-2 py-0.5 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text)]">
          Frame
          <ChevronDown className="w-3 h-3" />
        </button>

        <button className="devtools-body-tight flex items-center gap-1 px-2 py-0.5 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text)]">
          Dynamic / Static
          <ChevronDown className="w-3 h-3" />
        </button>
      </div>

      <div className="flex items-center gap-2">
        <button className="p-1 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text-muted)]">
          <Settings className="w-3.5 h-3.5" />
        </button>
        <button className="p-1 rounded hover:bg-[var(--devtools-hover)] text-[var(--devtools-text-muted)]">
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
}

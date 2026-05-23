import { useState } from "react";

interface EventRow {
  source: string;
  eventId: string;
  timestamp: string;
  duration: string;
  isActive?: boolean;
}

const mockEvents: EventRow[] = [
  { source: "fx", eventId: ":title/flow", timestamp: "12:30:05.123", duration: "1.2 ms" },
  { source: "view", eventId: ":counter-inc", timestamp: "12:30:06.456", duration: "0.4 ms", isActive: true },
  { source: "timer", eventId: ":poll/tick", timestamp: "12:30:07.001", duration: "0.2 ms" },
  { source: "view", eventId: ":user/profile", timestamp: "12:30:07.892", duration: "0.6 ms" },
  { source: "machine", eventId: ":title/loaded", timestamp: "12:30:08.234", duration: "0.3 ms" },
  { source: "view", eventId: ":form/submit", timestamp: "12:30:09.456", duration: "1.8 ms" },
];

export function EventList() {
  const [dragStart, setDragStart] = useState<number | null>(null);
  const [height, setHeight] = useState(156); // Default ~6 rows at 26px each

  const handleMouseDown = (e: React.MouseEvent) => {
    setDragStart(e.clientY);
    e.preventDefault();
  };

  const handleMouseMove = (e: MouseEvent) => {
    if (dragStart !== null) {
      const delta = e.clientY - dragStart;
      setHeight((prev) => Math.max(100, Math.min(400, prev + delta)));
      setDragStart(e.clientY);
    }
  };

  const handleMouseUp = () => {
    setDragStart(null);
  };

  // Add/remove event listeners
  if (typeof window !== "undefined") {
    if (dragStart !== null) {
      window.addEventListener("mousemove", handleMouseMove);
      window.addEventListener("mouseup", handleMouseUp);
    } else {
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("mouseup", handleMouseUp);
    }
  }

  return (
    <div className="border-b border-[var(--devtools-border)] relative">
      <div style={{ height: `${height}px` }} className="overflow-auto">
        <table className="w-full devtools-body">
          <thead className="sticky top-0 bg-[var(--devtools-chrome-bg)] border-b border-[var(--devtools-border)]">
            <tr className="devtools-caption text-[var(--devtools-text-muted)]">
              <th className="text-left px-3 py-1.5 font-medium">source</th>
              <th className="text-left px-3 py-1.5 font-medium">event id</th>
              <th className="text-left px-3 py-1.5 font-medium">timestamp</th>
              <th className="text-left px-3 py-1.5 font-medium">duration</th>
            </tr>
          </thead>
          <tbody>
            {mockEvents.map((event, idx) => (
              <tr
                key={idx}
                className={`border-b border-[var(--devtools-border)] hover:bg-[var(--devtools-hover)] cursor-pointer ${
                  event.isActive ? "bg-[var(--devtools-hover)]" : ""
                }`}
              >
                <td className="px-3 py-1.5 text-[var(--devtools-text-muted)]">{event.source}</td>
                <td className="px-3 py-1.5 text-[var(--devtools-text)] devtools-mono">{event.eventId}</td>
                <td className="px-3 py-1.5 text-[var(--devtools-text-muted)] devtools-mono">{event.timestamp}</td>
                <td className="px-3 py-1.5 text-[var(--devtools-text-muted)] devtools-mono">{event.duration}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Resize handle */}
      <div
        className="absolute bottom-0 left-0 right-0 h-1 cursor-ns-resize hover:bg-[var(--devtools-active)] transition-colors"
        onMouseDown={handleMouseDown}
      />
    </div>
  );
}

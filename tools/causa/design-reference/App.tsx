import { useState } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@radix-ui/react-tabs";
import { ChromeRibbon } from "./components/ChromeRibbon";
import { EventsRibbon } from "./components/EventsRibbon";
import { EventList } from "./components/EventList";
import { EventPanel } from "./components/EventPanel";
import { AppDbPanel } from "./components/AppDbPanel";
import { ViewsPanel } from "./components/ViewsPanel";
import { TracePanel } from "./components/TracePanel";
import { MachinePanel } from "./components/MachinePanel";
import { RoutesPanel } from "./components/RoutesPanel";
import { IssuesPanel } from "./components/IssuesPanel";
import "../styles/devtools.css";

export default function App() {
  const [activeTab, setActiveTab] = useState("event");

  return (
    <div className="h-screen flex flex-col bg-background text-[var(--devtools-text)]">
      {/* Region 1: Chrome Ribbon */}
      <ChromeRibbon />

      {/* Region 2: Events Ribbon */}
      <EventsRibbon />

      {/* Region 3: Event List */}
      <EventList />

      {/* Region 4 & 5: Tabs and Panel Content */}
      <Tabs value={activeTab} onValueChange={setActiveTab} className="flex-1 flex flex-col overflow-hidden">
        <TabsList className="h-10 px-3 flex items-center gap-1 border-b bg-background border-[var(--devtools-border)] shrink-0">
          <TabsTrigger
            value="event"
            className="devtools-body px-3 py-1.5 rounded data-[state=active]:bg-[var(--devtools-active)] data-[state=active]:text-white hover:bg-[var(--devtools-hover)] transition-colors"
          >
            Event
          </TabsTrigger>
          <TabsTrigger
            value="app-db"
            className="devtools-body px-3 py-1.5 rounded data-[state=active]:bg-[var(--devtools-active)] data-[state=active]:text-white hover:bg-[var(--devtools-hover)] transition-colors"
          >
            app-db
          </TabsTrigger>
          <TabsTrigger
            value="views"
            className="devtools-body px-3 py-1.5 rounded data-[state=active]:bg-[var(--devtools-active)] data-[state=active]:text-white hover:bg-[var(--devtools-hover)] transition-colors"
          >
            Views
          </TabsTrigger>
          <TabsTrigger
            value="trace"
            className="devtools-body px-3 py-1.5 rounded data-[state=active]:bg-[var(--devtools-active)] data-[state=active]:text-white hover:bg-[var(--devtools-hover)] transition-colors"
          >
            Trace
          </TabsTrigger>
          <TabsTrigger
            value="machine"
            className="devtools-body px-3 py-1.5 rounded data-[state=active]:bg-[var(--devtools-active)] data-[state=active]:text-white hover:bg-[var(--devtools-hover)] transition-colors"
          >
            Machine
          </TabsTrigger>
          <TabsTrigger
            value="routes"
            className="devtools-body px-3 py-1.5 rounded data-[state=active]:bg-[var(--devtools-active)] data-[state=active]:text-white hover:bg-[var(--devtools-hover)] transition-colors"
          >
            Routes
          </TabsTrigger>
          <TabsTrigger
            value="issues"
            className="devtools-body px-3 py-1.5 rounded data-[state=active]:bg-[var(--devtools-active)] data-[state=active]:text-white hover:bg-[var(--devtools-hover)] transition-colors"
          >
            Issues
          </TabsTrigger>
        </TabsList>

        <div className="flex-1 overflow-hidden">
          <TabsContent value="event" className="h-full m-0">
            <EventPanel />
          </TabsContent>
          <TabsContent value="app-db" className="h-full m-0">
            <AppDbPanel />
          </TabsContent>
          <TabsContent value="views" className="h-full m-0">
            <ViewsPanel />
          </TabsContent>
          <TabsContent value="trace" className="h-full m-0">
            <TracePanel />
          </TabsContent>
          <TabsContent value="machine" className="h-full m-0">
            <MachinePanel />
          </TabsContent>
          <TabsContent value="routes" className="h-full m-0">
            <RoutesPanel />
          </TabsContent>
          <TabsContent value="issues" className="h-full m-0">
            <IssuesPanel />
          </TabsContent>
        </div>
      </Tabs>
    </div>
  );
}

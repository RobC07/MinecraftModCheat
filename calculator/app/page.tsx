export default function Page() {
  return (
    <main className="mx-auto max-w-7xl p-6">
      <header className="mb-6">
        <h1 className="text-2xl font-semibold">Shard Fusion Profit Calculator</h1>
        <p className="text-sm text-neutral-400">
          Phase 1 scaffold &mdash; UI and engine wiring coming next.
        </p>
      </header>
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <section className="rounded-lg border border-neutral-800 bg-neutral-900 p-4">
          <h2 className="mb-2 text-lg font-medium">Farms</h2>
          <p className="text-sm text-neutral-500">Inputs go here.</p>
        </section>
        <section className="rounded-lg border border-neutral-800 bg-neutral-900 p-4">
          <h2 className="mb-2 text-lg font-medium">Results</h2>
          <p className="text-sm text-neutral-500">Ranked methods go here.</p>
        </section>
      </div>
    </main>
  );
}

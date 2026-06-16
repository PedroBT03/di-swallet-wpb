interface PlaceholderPageProps {
  title: string;
  phase: number;
  description: string;
}

export function PlaceholderPage({ title, phase, description }: PlaceholderPageProps) {
  return (
    <section className="page">
      <header className="page__header">
        <h1>{title}</h1>
        <p className="page__lead">{description}</p>
      </header>
      <div className="card card--muted">
        <p>
          Planned for implementation phase <strong>{phase}</strong>. See{" "}
          <code>wpi-dev/IMPLEMENTATION.md</code> for the roadmap.
        </p>
      </div>
    </section>
  );
}

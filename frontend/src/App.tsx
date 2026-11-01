import { useEffect, useState } from "react";
import { api, type About } from "./api/client";
import { describeBuild } from "./format";

/** M0 placeholder: shows that frontend, proxy and API are connected. Real pages follow in M7. */
export function App() {
  const [about, setAbout] = useState<About | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.GET("/api/v1/about").then(({ data, error: apiError }) => {
      if (data) setAbout(data);
      else setError(apiError ? "Die API hat einen Fehler gemeldet." : "Die API ist nicht erreichbar.");
    }, () => setError("Die API ist nicht erreichbar."));
  }, []);

  return (
    <main className="shell">
      <h1>open-spri</h1>
      <p>S/PRI-Schnittstelle für Auftraggeber und Leistungserbringer.</p>
      {about && <p className="status ok">{describeBuild(about)}</p>}
      {error && <p className="status error">{error} Läuft der Container „api“?</p>}
      {!about && !error && <p className="status">Verbindung zur API wird geprüft …</p>}
      <p>
        <a href="/api/docs">REST-API-Dokumentation (Swagger UI)</a>
      </p>
    </main>
  );
}

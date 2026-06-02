"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

/**
 * Whether a circuit category/level counts as main tour. Matches are tagged "ATP"/"WTA"; tournaments may
 * be combined ("ATP & WTA"), so a substring check covers both. Challenger/ITF/Junior/UTR are excluded.
 */
export function isMainTour(category?: string | null): boolean {
  return !!category && (category.includes("ATP") || category.includes("WTA"));
}

interface PrefsValue {
  /** When true (default), scores + tournaments show only ATP & WTA; lower circuits are hidden. */
  mainTourOnly: boolean;
  setMainTourOnly: (v: boolean) => void;
}

const PrefsContext = createContext<PrefsValue | null>(null);

export function PrefsProvider({ children }: { children: ReactNode }) {
  const [mainTourOnly, setState] = useState(true); // default: main tour only

  useEffect(() => {
    const v = localStorage.getItem("mainTourOnly");
    if (v !== null) setState(v === "true");
  }, []);

  const setMainTourOnly = (v: boolean) => {
    localStorage.setItem("mainTourOnly", String(v));
    setState(v);
  };

  return <PrefsContext.Provider value={{ mainTourOnly, setMainTourOnly }}>{children}</PrefsContext.Provider>;
}

export function usePrefs(): PrefsValue {
  const ctx = useContext(PrefsContext);
  if (!ctx) throw new Error("usePrefs must be used within PrefsProvider");
  return ctx;
}

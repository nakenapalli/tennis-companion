// IOC 3-letter codes (what players carry) → ISO 3166-1 alpha-2, for flag emoji. Covers the common tennis
// nations; anything unmapped falls back to showing the IOC text (see components/Flag.tsx).
const IOC_TO_ISO2: Record<string, string> = {
  ESP: "ES", USA: "US", GBR: "GB", GER: "DE", FRA: "FR", ITA: "IT", SUI: "CH", SRB: "RS", RUS: "RU",
  AUT: "AT", GRE: "GR", NOR: "NO", DEN: "DK", NED: "NL", POL: "PL", CRO: "HR", BUL: "BG", CZE: "CZ",
  SVK: "SK", SLO: "SI", HUN: "HU", ROU: "RO", POR: "PT", SWE: "SE", FIN: "FI", BEL: "BE", IRL: "IE",
  UKR: "UA", BLR: "BY", KAZ: "KZ", GEO: "GE", LAT: "LV", LTU: "LT", EST: "EE", CYP: "CY", MDA: "MD",
  MNE: "ME", MKD: "MK", BIH: "BA", ARM: "AM", AZE: "AZ", UZB: "UZ",
  ARG: "AR", BRA: "BR", CHI: "CL", COL: "CO", PER: "PE", URU: "UY", ECU: "EC", BOL: "BO", PAR: "PY",
  VEN: "VE", MEX: "MX", CAN: "CA",
  AUS: "AU", NZL: "NZ", JPN: "JP", CHN: "CN", TPE: "TW", KOR: "KR", IND: "IN", THA: "TH", INA: "ID",
  PHI: "PH", VIE: "VN", HKG: "HK",
  RSA: "ZA", EGY: "EG", MAR: "MA", TUN: "TN", TUR: "TR", ISR: "IL", LBN: "LB", JOR: "JO", KSA: "SA",
  UAE: "AE", QAT: "QA",
};

/** Flag emoji for an IOC code, or null if we can't map it (caller falls back to the code text). */
export function flagEmoji(ioc?: string | null): string | null {
  if (!ioc) return null;
  const iso = IOC_TO_ISO2[ioc.toUpperCase()];
  if (!iso) return null;
  return iso.replace(/./g, (c) => String.fromCodePoint(127397 + c.charCodeAt(0)));
}

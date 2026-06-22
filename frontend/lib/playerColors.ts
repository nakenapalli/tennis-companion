// Picks two distinct, bright line colors for the momentum chart, biased toward each player's flag
// colors with some per-load randomness (so they vary on reload). Everything stays in a bright HSL band
// so nothing gets lost on the dark theme. If one player has no flag the other takes the complementary
// hue; if neither has a flag, both are random. The two hues are always kept comfortably apart.

type Tone = { h: number; s: number; l: number };

export interface PlayerColors {
  c1: string;
  c2: string; // solid — line markers, axis labels, tooltip pill
  fill1: string;
  fill2: string; // translucent area fills under the line
  hl1: string;
  hl2: string; // meta-card highlight bands
}

// Dominant saturated hues (degrees) per IOC code — white/black omitted (not hues). Unknown countries
// fall back to a random hue; a missing country (no flag) triggers the complementary/random paths.
const FLAG_HUES: Record<string, number[]> = {
  ESP: [0, 48], USA: [0, 220], GBR: [0, 220], GER: [0, 48], FRA: [220, 0], ITA: [140, 0], SUI: [0],
  SRB: [0, 220], RUS: [0, 220], AUT: [0], GRE: [215], NOR: [220, 0], DEN: [0], NED: [0, 25, 220],
  POL: [0], CRO: [0, 220], BUL: [140, 0], CZE: [220, 0], SVK: [0, 220], SLO: [0, 220], HUN: [0, 140],
  ROU: [220, 48, 0], POR: [140, 0], SWE: [220, 48], FIN: [220], BEL: [48, 0], IRL: [140, 25],
  UKR: [220, 48], BLR: [0, 140], KAZ: [195, 48], GEO: [0], LAT: [0], LTU: [48, 140, 0], EST: [215],
  CYP: [28], ARG: [200, 48], BRA: [140, 48, 220], CHI: [0, 220], COL: [48, 220, 0], PER: [0],
  URU: [220, 48], MEX: [140, 0], CAN: [0], AUS: [220, 0], NZL: [220, 0], JPN: [0], CHN: [0, 48],
  TPE: [0, 220], KOR: [0, 220], IND: [28, 140], THA: [0, 220], RSA: [140, 48, 0], EGY: [0],
  MAR: [140, 0], TUN: [0], TUR: [0], ISR: [220],
};

const MIN_SEP = 45; // minimum hue separation between the two players (degrees)
const rand = (a: number, b: number) => a + Math.random() * (b - a);
const norm = (h: number) => ((h % 360) + 360) % 360;
const circDist = (a: number, b: number) => {
  const d = Math.abs(norm(a - b));
  return Math.min(d, 360 - d);
};
const jitter = (h: number) => norm(h + rand(-10, 10)); // small wobble, kept close to the flag hue
const pick = (arr: number[]) => arr[Math.floor(Math.random() * arr.length)];

function flagHues(country?: string | null): number[] | null {
  if (!country) return null;
  return FLAG_HUES[country.toUpperCase()] ?? [Math.random() * 360];
}

// Bright band — keep lightness high so colors stay legible on the dark background.
const tone = (h: number): Tone => ({ h: norm(h), s: rand(68, 85), l: rand(56, 66) });
const css = (t: Tone) => `hsl(${Math.round(t.h)}, ${Math.round(t.s)}%, ${Math.round(t.l)}%)`;
const cssa = (t: Tone, a: number) => `hsla(${Math.round(t.h)}, ${Math.round(t.s)}%, ${Math.round(t.l)}%, ${a})`;

export function pickPlayerColors(country1?: string | null, country2?: string | null): PlayerColors {
  const f1 = flagHues(country1);
  const f2 = flagHues(country2);
  let h1: number;
  let h2: number;
  if (f1 && f2) {
    h1 = jitter(pick(f1));
    h2 = f2.map(jitter).sort((a, b) => circDist(b, h1) - circDist(a, h1))[0]; // farthest of its flag hues
    if (circDist(h1, h2) < MIN_SEP) h2 = norm(h1 + 70); // shared palette → force them apart
  } else if (f1) {
    h1 = jitter(pick(f1));
    h2 = norm(h1 + 180); // other player flagless → complementary
  } else if (f2) {
    h2 = jitter(pick(f2));
    h1 = norm(h2 + 180);
  } else {
    h1 = Math.random() * 360; // neither has a flag → both random, well apart
    h2 = norm(h1 + rand(90, 270));
  }
  const t1 = tone(h1);
  const t2 = tone(h2);
  return {
    c1: css(t1), c2: css(t2),
    fill1: cssa(t1, 0.2), fill2: cssa(t2, 0.18),
    hl1: cssa(t1, 0.16), hl2: cssa(t2, 0.15),
  };
}

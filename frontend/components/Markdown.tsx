"use client";

import React from "react";

/**
 * Minimal, dependency-free Markdown renderer for the digest's known grammar:
 * `#`–`###` headings, `-`/`*` bullet lists, `**bold**` inline, and paragraphs.
 * It renders to React nodes (never `dangerouslySetInnerHTML`), so even though the
 * digest body is fully grounded and trusted, raw text can't inject HTML.
 */

/**
 * Split a line into nodes, turning `**bold**` spans into <strong> and `[text](url)` into external links
 * (used by the digest's inline source citations). Links open in a new tab with a safe rel.
 */
function inline(text: string, keyBase: string): React.ReactNode[] {
  return text
    .split(/(\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\))/g)
    .filter((part) => part.length > 0)
    .map((part, i) => {
      const key = `${keyBase}-${i}`;
      if (part.startsWith("**") && part.endsWith("**")) {
        return <strong key={key}>{part.slice(2, -2)}</strong>;
      }
      const link = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(part);
      if (link) {
        return (
          <a key={key} href={link[2]} target="_blank" rel="noopener noreferrer">
            {link[1]}
          </a>
        );
      }
      return <React.Fragment key={key}>{part}</React.Fragment>;
    });
}

export function Markdown({ source }: { source: string }) {
  const lines = source.replace(/\r\n/g, "\n").split("\n");
  const blocks: React.ReactNode[] = [];
  let para: string[] = [];
  let list: string[] = [];
  let key = 0;

  const flushPara = () => {
    if (para.length) {
      const k = key++;
      blocks.push(<p key={`p-${k}`}>{inline(para.join(" "), `p-${k}`)}</p>);
      para = [];
    }
  };
  const flushList = () => {
    if (list.length) {
      const k = key++;
      const items = list;
      blocks.push(
        <ul key={`ul-${k}`}>
          {items.map((it, i) => (
            <li key={i}>{inline(it, `li-${k}-${i}`)}</li>
          ))}
        </ul>,
      );
      list = [];
    }
  };

  for (const raw of lines) {
    const trimmed = raw.trim();
    if (trimmed === "") {
      flushPara();
      flushList();
      continue;
    }
    const heading = /^(#{1,3})\s+(.*)$/.exec(trimmed);
    if (heading) {
      flushPara();
      flushList();
      const k = key++;
      const content = inline(heading[2], `h-${k}`);
      const level = heading[1].length;
      blocks.push(
        level === 1 ? (
          <h1 key={`h-${k}`}>{content}</h1>
        ) : level === 2 ? (
          <h2 key={`h-${k}`}>{content}</h2>
        ) : (
          <h3 key={`h-${k}`}>{content}</h3>
        ),
      );
      continue;
    }
    const bullet = /^[-*]\s+(.*)$/.exec(trimmed);
    if (bullet) {
      flushPara();
      list.push(bullet[1]);
      continue;
    }
    // Plain prose line: ends any open list, accumulates into the current paragraph.
    flushList();
    para.push(trimmed);
  }
  flushPara();
  flushList();

  return <div className="digest-body">{blocks}</div>;
}

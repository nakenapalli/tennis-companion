"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import useSWR from "swr";
import { apiFetch, fetcher } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { ChatMessage, ChatThreadDetail, ChatThreadSummary, ThreadList } from "@/lib/types";

const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/**
 * Per-match chat: a thread list (top-3 active, then latest-5) and a thread view, switched by local state.
 * Snapshots come from REST; live updates over SSE. Logged-out users see the list but can't open threads
 * or post; once the match is finished, posting is locked but threads stay viewable.
 */
export function MatchChat({
  matchId,
  locked,
  initialThreadId = null,
}: {
  matchId: string;
  locked: boolean;
  initialThreadId?: string | null; // deep-link straight into a thread (e.g. from a tournament's Threads tab)
}) {
  const { token } = useAuth();
  const [threadId, setThreadId] = useState<string | null>(initialThreadId);

  return threadId ? (
    <ThreadView matchId={matchId} threadId={threadId} locked={locked} canPost={!!token} onBack={() => setThreadId(null)} />
  ) : (
    <ListView matchId={matchId} locked={locked} loggedIn={!!token} onOpen={setThreadId} />
  );
}

function ListView({
  matchId,
  locked,
  loggedIn,
  onOpen,
}: {
  matchId: string;
  locked: boolean;
  loggedIn: boolean;
  onOpen: (id: string) => void;
}) {
  const { data, mutate } = useSWR<ThreadList>(`/api/matches/${matchId}/threads`, fetcher);
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const es = new EventSource(`${API}/api/matches/${matchId}/threads/stream`);
    es.addEventListener("thread-changed", () => mutate());
    es.onopen = () => mutate(); // (re)connect → catch anything missed
    return () => es.close();
  }, [matchId, mutate]);

  async function createThread(e: FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    setBusy(true);
    try {
      const t = await apiFetch<ChatThreadDetail>(`/api/matches/${matchId}/threads`, {
        method: "POST",
        body: JSON.stringify({ title }),
      });
      setTitle("");
      mutate();
      onOpen(t.id); // drop the user straight into their new thread
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="chat">
      <h2>Match chat</h2>
      {!data ? (
        <div className="spinner">Loading…</div>
      ) : data.active.length === 0 && data.latest.length === 0 ? (
        <div className="empty">No threads yet{loggedIn ? " — start one below." : "."}</div>
      ) : (
        <div className="thread-list">
          {data.active.length > 0 && <ThreadGroup label="Most active" threads={data.active} loggedIn={loggedIn} onOpen={onOpen} />}
          {data.latest.length > 0 && <ThreadGroup label="Latest" threads={data.latest} loggedIn={loggedIn} onOpen={onOpen} />}
        </div>
      )}
      <ChatFooter loggedIn={loggedIn} locked={locked} placeholder="Start a thread…" value={title} onChange={setTitle} onSubmit={createThread} busy={busy} submitLabel="Post" />
    </section>
  );
}

function ThreadGroup({
  label,
  threads,
  loggedIn,
  onOpen,
}: {
  label: string;
  threads: ChatThreadSummary[];
  loggedIn: boolean;
  onOpen: (id: string) => void;
}) {
  return (
    <div className="thread-group">
      <h3 className="thread-group-label">{label}</h3>
      {threads.map((t) => (
        <button key={t.id} type="button" className="thread-row" disabled={!loggedIn} onClick={() => onOpen(t.id)}>
          <span className="thread-title">{t.title}</span>
          <span className="thread-sub muted">{t.messageCount} msg · {t.activeChatters} active · {t.authorName}</span>
        </button>
      ))}
    </div>
  );
}

function ThreadView({
  matchId,
  threadId,
  locked,
  canPost,
  onBack,
}: {
  matchId: string;
  threadId: string;
  locked: boolean;
  canPost: boolean;
  onBack: () => void;
}) {
  const { data, mutate } = useSWR<ChatThreadDetail>(`/api/matches/${matchId}/threads/${threadId}`, fetcher);
  const [livePosts, setLivePosts] = useState<ChatMessage[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const es = new EventSource(`${API}/api/matches/${matchId}/threads/${threadId}/stream`);
    es.addEventListener("message", (ev) => {
      const msg = JSON.parse((ev as MessageEvent).data) as ChatMessage;
      setLivePosts((prev) => (prev.some((m) => m.id === msg.id) ? prev : [...prev, msg]));
    });
    es.onopen = () => mutate(); // (re)connect → resync the snapshot
    return () => es.close();
  }, [matchId, threadId, mutate]);

  // snapshot + live, deduped by id
  const byId = new Map<string, ChatMessage>();
  for (const m of [...(data?.messages ?? []), ...livePosts]) byId.set(m.id, m);
  const messages = [...byId.values()];

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

  async function send(e: FormEvent) {
    e.preventDefault();
    if (!text.trim()) return;
    setBusy(true);
    try {
      const msg = await apiFetch<ChatMessage>(`/api/matches/${matchId}/threads/${threadId}/messages`, {
        method: "POST",
        body: JSON.stringify({ text }),
      });
      setText("");
      setLivePosts((prev) => (prev.some((m) => m.id === msg.id) ? prev : [...prev, msg])); // instant echo; SSE dup deduped
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="chat">
      <button type="button" className="btn-link" onClick={onBack}>← Threads</button>
      <h2 className="thread-heading">{data?.title ?? "Thread"}</h2>
      <div className="chat-messages">
        {messages.length === 0 ? (
          <div className="empty">No messages yet.</div>
        ) : (
          messages.map((m) => (
            <div key={m.id} className="chat-msg">
              <span className="chat-author">{m.authorName}</span>
              <span className="chat-text">{m.text}</span>
            </div>
          ))
        )}
        <div ref={bottomRef} />
      </div>
      <ChatFooter loggedIn={canPost} locked={locked} placeholder="Message…" value={text} onChange={setText} onSubmit={send} busy={busy} submitLabel="Send" />
    </section>
  );
}

function ChatFooter({
  loggedIn,
  locked,
  placeholder,
  value,
  onChange,
  onSubmit,
  busy,
  submitLabel,
}: {
  loggedIn: boolean;
  locked: boolean;
  placeholder: string;
  value: string;
  onChange: (v: string) => void;
  onSubmit: (e: FormEvent) => void;
  busy: boolean;
  submitLabel: string;
}) {
  if (!loggedIn) {
    return (
      <div className="chat-footer chat-cta">
        <span className="muted">Log in or register to start talking.</span>
        <Link href="/login" className="btn">Log in / Register</Link>
      </div>
    );
  }
  if (locked) {
    return <div className="chat-footer chat-locked muted">Chat is locked — the match has ended.</div>;
  }
  return (
    <form className="chat-footer" onSubmit={onSubmit}>
      <input className="chat-input" placeholder={placeholder} value={value} onChange={(e) => onChange(e.target.value)} maxLength={500} />
      <button className="btn" disabled={busy || !value.trim()}>{busy ? "…" : submitLabel}</button>
    </form>
  );
}

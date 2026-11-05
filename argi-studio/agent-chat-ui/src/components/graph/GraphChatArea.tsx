"use client";

import React, { useState, FormEvent, useMemo } from "react";
import { useGraphStream } from "@/providers/GraphStream";
import { useGraphThreads } from "@/providers/GraphThread";
import { Button } from "@/components/ui/button";
import { extractTextContent, extractTextFromState } from "./graph-output";

export function GraphChatArea() {
  const {
    sendMessage,
    accumulatedChunk,
    currentState,
    nodeOutputs,
    isStreaming,
  } = useGraphStream();
  const { currentThreadId, createThread } = useGraphThreads();
  const [input, setInput] = useState("");

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!input.trim() || isStreaming) return;
    let threadId = currentThreadId;
    if (!threadId) {
      const session = await createThread();
      if (!session) return;
      threadId = session.thread_id;
    }
    await sendMessage(input.trim(), threadId);
    setInput("");
  };

  const displayText = useMemo(() => {
    if (accumulatedChunk) return accumulatedChunk;
    const fromState = extractTextFromState(currentState);
    if (fromState) return fromState;
    const lastMessageText = [...nodeOutputs]
      .reverse()
      .map((output) => extractTextContent(output.message?.content))
      .find((text) => text.length > 0);
    return lastMessageText ?? "";
  }, [accumulatedChunk, currentState, nodeOutputs]);

  return (
    <div className="flex flex-col gap-2">
      <form
        onSubmit={handleSubmit}
        className="flex gap-2"
      >
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Type a message..."
          disabled={isStreaming}
          className="border-input bg-background min-w-0 flex-1 rounded-md border px-2 py-1.5 text-sm"
        />
        <Button
          type="submit"
          size="sm"
          disabled={isStreaming || !input.trim()}
        >
          {isStreaming ? "..." : "Run"}
        </Button>
      </form>
      {displayText && (
        <div
          className="text-muted-foreground line-clamp-2 truncate text-xs"
          title={displayText}
        >
          {displayText}
        </div>
      )}
    </div>
  );
}

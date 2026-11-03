function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function extractTextContent(content: unknown): string {
  if (typeof content === "string") {
    return content;
  }

  if (!Array.isArray(content)) {
    return "";
  }

  return content
    .map((part) => {
      if (
        !isRecord(part) ||
        part.type !== "TEXT" ||
        typeof part.text !== "string"
      ) {
        return "";
      }

      return part.text;
    })
    .join("");
}

export function extractTextFromState(
  state: Record<string, unknown> | null,
): string {
  if (!state) {
    return "";
  }

  let legacyMessageText = "";
  if (Array.isArray(state.messages)) {
    for (let index = state.messages.length - 1; index >= 0; index -= 1) {
      const message = state.messages[index];
      if (!isRecord(message)) {
        continue;
      }

      let messageText = extractTextContent(message.content);
      if (!messageText && typeof message.text === "string") {
        messageText = message.text;
      }
      if (!messageText) {
        continue;
      }

      const role =
        typeof message.role === "string"
          ? message.role
          : typeof message.messageType === "string"
            ? message.messageType
            : "";
      if (role.toUpperCase() === "ASSISTANT") {
        return messageText;
      }

      if (
        !("role" in message) &&
        !("messageType" in message) &&
        !legacyMessageText
      ) {
        legacyMessageText = messageText;
      }
    }
  }

  if (typeof state.result === "string") {
    return state.result;
  }

  return legacyMessageText;
}

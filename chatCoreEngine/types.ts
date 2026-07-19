/**
 * Shared type definitions for the chat core engine.
 */

/** Internal representation of a chat message flowing through the system. */
export interface ChatMessage {
  messageId: string;
  fromUserId: string;
  toUserId: string;
  content: string;
  timestamp: number;
}

/** WebSocket message from client → server. */
export interface IncomingWsMessage {
  type: "message" | "heartbeat";
  to?: string;
  message?: string;
  messageId?: string;
}

/** WebSocket message from server → client. */
export interface OutgoingWsMessage {
  type: "friend_message" | "ack" | "error";
  from?: string;
  message?: string;
  messageId?: string;
  status?: string;
  code?: string;
  timestamp?: number;
}

/** Data attached to each WebSocket connection via Bun's upgrade. */
export interface WsData {
  userId: string;
  connId: string;
}

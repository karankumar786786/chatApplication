/**
 * Local WebSocket connection manager.
 * Maps userId → active ServerWebSocket for this node only.
 * 
 * Single-device model for now — new connection from same user replaces the old.
 * Multi-device (project.md §16) is a v1.1 feature.
 */

import type { ServerWebSocket } from "bun";
import type { WsData } from "../types";

export class ConnectionManager {
  private connections = new Map<string, ServerWebSocket<WsData>>();

  /** Register a user's WebSocket. Closes any existing connection for the same user. */
  add(userId: string, ws: ServerWebSocket<WsData>): void {
    const existing = this.connections.get(userId);
    if (existing) {
      try {
        existing.close(1000, "replaced by new connection");
      } catch {
        // Already closed, ignore
      }
    }
    this.connections.set(userId, ws);
  }

  /** Remove a user's connection, but only if the connId matches (prevents stale removal). */
  remove(userId: string, connId: string): boolean {
    const ws = this.connections.get(userId);
    if (ws && ws.data.connId === connId) {
      this.connections.delete(userId);
      return true;
    }
    return false;
  }

  /** Get the WebSocket for a locally connected user. */
  get(userId: string): ServerWebSocket<WsData> | undefined {
    return this.connections.get(userId);
  }

  /** Check if a user is connected on this node. */
  has(userId: string): boolean {
    return this.connections.has(userId);
  }

  /** Deliver a JSON payload to a locally connected user. Returns true if delivered. */
  deliverToUser(userId: string, payload: object): boolean {
    const ws = this.connections.get(userId);
    if (!ws) return false;
    try {
      ws.send(JSON.stringify(payload));
      return true;
    } catch {
      return false;
    }
  }

  /** Number of active local connections. */
  getCount(): number {
    return this.connections.size;
  }

  /** All locally connected user IDs. */
  getAllUserIds(): string[] {
    return Array.from(this.connections.keys());
  }
}

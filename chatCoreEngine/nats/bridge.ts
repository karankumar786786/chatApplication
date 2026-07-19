/**
 * NATS bridge for cross-region message delivery.
 * 
 * Each node subscribes to subject "{region}.{nodeId}" (its own inbox).
 * To send cross-region, we publish to "{targetRegion}.{targetNodeId}".
 * 
 * This enables direct node-to-node delivery across regions with zero
 * unnecessary hops — the message goes straight from the sender's node
 * to the recipient's node via NATS.
 * 
 * Using basic NATS pub/sub for now. JetStream (project.md §4.2) adds
 * persistence and guaranteed delivery — that's a later phase.
 */

import { connect, StringCodec } from "nats";
import type { NatsConnection, Subscription } from "nats";
import type { ConnectionManager } from "../connections/manager";
import type { ChatMessage } from "../types";

export class NatsBridge {
  private nc: NatsConnection | null = null;
  private sc = StringCodec();
  private sub: Subscription | null = null;
  private url: string;

  constructor(natsUrl: string) {
    this.url = natsUrl;
  }

  /** Connect to NATS server. */
  async connect(): Promise<void> {
    this.nc = await connect({
      servers: this.url,
      maxReconnectAttempts: -1, // infinite reconnects
      reconnectTimeWait: 2000,  // 2s between reconnect attempts
    });
    console.log(`[nats] connected to ${this.url}`);

    // Monitor connection status
    (async () => {
      if (!this.nc) return;
      for await (const status of this.nc.status()) {
        console.log(`[nats] status: ${status.type}${status.data ? ` — ${status.data}` : ""}`);
      }
    })();
  }

  /**
   * Subscribe to this node's inbox subject and deliver incoming messages
   * to locally connected users.
   * 
   * Subject format: "{region}.{nodeId}" (e.g., "r1.node-0")
   */
  subscribe(subject: string, connManager: ConnectionManager): void {
    if (!this.nc) throw new Error("NATS not connected");

    this.sub = this.nc.subscribe(subject);
    console.log(`[nats] subscribed to ${subject}`);

    // Process incoming messages in the background
    this.processSubscription(this.sub, connManager);
  }

  /**
   * Publish a message to a target node's NATS subject.
   * Used for cross-region delivery where gRPC isn't available.
   */
  async publish(targetSubject: string, msg: ChatMessage): Promise<void> {
    if (!this.nc) throw new Error("NATS not connected");

    const payload = this.sc.encode(JSON.stringify(msg));
    this.nc.publish(targetSubject, payload);
    console.log(`[nats] published ${msg.messageId} → ${targetSubject}`);
  }

  /** Drain and close the NATS connection. */
  async close(): Promise<void> {
    if (this.sub) {
      this.sub.unsubscribe();
    }
    if (this.nc) {
      await this.nc.drain();
      console.log("[nats] drained and closed");
    }
  }

  // ─── Internal ───────────────────────────────────────────────────

  private async processSubscription(
    sub: Subscription,
    connManager: ConnectionManager
  ): Promise<void> {
    for await (const msg of sub) {
      try {
        const data = JSON.parse(this.sc.decode(msg.data)) as ChatMessage;

        const delivered = connManager.deliverToUser(data.toUserId, {
          type: "friend_message",
          from: data.fromUserId,
          message: data.content,
          messageId: data.messageId,
          timestamp: data.timestamp,
        });

        if (delivered) {
          console.log(`[nats] delivered ${data.messageId} to ${data.toUserId} (local)`);
        } else {
          console.warn(
            `[nats] received message for ${data.toUserId} but user not connected locally — stale presence`
          );
        }
      } catch (e: any) {
        console.error("[nats] error processing message:", e.message);
      }
    }
  }
}

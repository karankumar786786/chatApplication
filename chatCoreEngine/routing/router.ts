/**
 * Four-tier message router.
 * 
 * Decides how to deliver a message based on recipient's location:
 * 
 *   1. LOCAL    — recipient is on this same node → deliver via WebSocket directly
 *   2. GRPC     — recipient is in the same region AND same cluster → forward via gRPC bidi stream
 *   3. NATS     — recipient is in a different cluster or different region → publish via NATS
 *   4. OFFLINE  — recipient has no presence in Redis → drop (offline queue is a later phase)
 * 
 * ServerId format: {region}.{clusterId}.{nodeId}  (e.g., "r1.c1.node-0")
 * NATS subject:    {region}.{clusterId}.{nodeId}  (each node subscribes to its own)
 * 
 * gRPC stays within a single K8s cluster (headless service DNS works).
 * NATS handles cross-cluster and cross-region — both are "remote" hops.
 * 
 * The routing is zero-hop: messages go directly to the exact node holding the
 * recipient's connection, with no intermediate routers or brokers in between.
 */

import type { Config } from "../config/config";
import type { ConnectionManager } from "../connections/manager";
import type { PresenceManager } from "../presence/presence";
import type { GrpcPool } from "../clustercommunication/grpcPool";
import type { NatsBridge } from "../nats/bridge";
import type { ChatMessage } from "../types";

export type RouteResult = "local" | "grpc" | "nats" | "offline";

export class MessageRouter {
  private serverId: string;

  constructor(
    private config: Config,
    private connManager: ConnectionManager,
    private presence: PresenceManager,
    private grpcPool: GrpcPool,
    private natsBridge: NatsBridge
  ) {
    this.serverId = `${config.region}.${config.clusterId}.${config.nodeId}`;
  }

  /**
   * Route a message to its recipient through the optimal transport.
   * Returns which transport was used (for logging / metrics).
   */
  async route(msg: ChatMessage): Promise<RouteResult> {
    // ── Tier 1: Local delivery ──────────────────────────────────
    if (this.connManager.has(msg.toUserId)) {
      const delivered = this.connManager.deliverToUser(msg.toUserId, {
        type: "friend_message",
        from: msg.fromUserId,
        message: msg.content,
        messageId: msg.messageId,
        timestamp: msg.timestamp,
      });

      if (delivered) {
        console.log(`[router] ${msg.messageId} → LOCAL delivery to ${msg.toUserId}`);
        return "local";
      }
      // Fall through if delivery failed (user just disconnected)
    }

    // ── Tier 2+3+4: Redis presence lookup ───────────────────────
    const presenceData = await this.presence.getPresence(msg.toUserId);

    if (!presenceData) {
      console.log(
        `[router] ${msg.messageId} → user ${msg.toUserId} is OFFLINE (no presence)`
      );
      return "offline";
    }

    // ── Tier 2: Intra-cluster gRPC (same region + same cluster) ─
    if (
      presenceData.region === this.config.region &&
      presenceData.clusterId === this.config.clusterId
    ) {
      if (presenceData.serverId === this.serverId) {
        // Presence says user is on this server but not in local connMap
        // This means presence is stale — treat as offline
        console.warn(
          `[router] ${msg.messageId} → stale presence for ${msg.toUserId} (claims local but not in connMap)`
        );
        return "offline";
      }

      try {
        await this.grpcPool.forward(presenceData.serverId, msg);
        console.log(
          `[router] ${msg.messageId} → GRPC to ${presenceData.serverId} (intra-cluster)`
        );
        return "grpc";
      } catch (err: any) {
        console.error(
          `[router] ${msg.messageId} → gRPC forward failed to ${presenceData.serverId}: ${err.message}`
        );
        return "offline";
      }
    }

    // ── Tier 3: NATS (cross-cluster or cross-region) ────────────
    try {
      // Publish directly to the target node's NATS subject
      // Subject = serverId = "{region}.{clusterId}.{nodeId}"
      await this.natsBridge.publish(presenceData.serverId, msg);

      const crossType =
        presenceData.region !== this.config.region
          ? `cross-region: ${this.config.region} → ${presenceData.region}`
          : `cross-cluster: ${this.config.clusterId} → ${presenceData.clusterId}`;

      console.log(
        `[router] ${msg.messageId} → NATS to ${presenceData.serverId} (${crossType})`
      );
      return "nats";
    } catch (err: any) {
      console.error(
        `[router] ${msg.messageId} → NATS publish failed to ${presenceData.serverId}: ${err.message}`
      );
      return "offline";
    }
  }
}

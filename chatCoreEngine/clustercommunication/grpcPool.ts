/**
 * Lazy gRPC connection pool for sending messages to peer nodes.
 * 
 * Maintains a map of serverId → { client, stream }.
 * On first message to a serverId:
 *   1. Looks up the server's gRPC address from Redis server registry
 *   2. Dials the target and opens a bidi ForwardMessages stream
 *   3. Caches the connection for subsequent messages
 * 
 * If a stream breaks, it's cleaned up and re-established on the next send.
 */

import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import path from "path";
import type { PresenceManager } from "../presence/presence";
import type { ChatMessage } from "../types";

interface PeerConnection {
  client: any;
  stream: grpc.ClientDuplexStream<any, any>;
  alive: boolean;
}

export class GrpcPool {
  private connections = new Map<string, PeerConnection>();
  private presenceManager: PresenceManager;
  private serviceConstructor: any;

  constructor(presenceManager: PresenceManager) {
    this.presenceManager = presenceManager;

    // Load the proto definition once
    const protoPath = path.join(
      import.meta.dir,
      "..",
      "proto",
      "communication.proto"
    );

    const packageDefinition = protoLoader.loadSync(protoPath, {
      keepCase: true,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true,
    });

    const proto = grpc.loadPackageDefinition(packageDefinition) as any;
    this.serviceConstructor = proto.InternalCommunicationService;
  }

  /**
   * Forward a message to a peer node via gRPC bidi stream.
   * Lazily establishes the connection and stream on first use.
   * 
   * @param serverId - Target server's ID (e.g., "r1.node-1")
   * @param msg - The chat message to forward
   */
  async forward(serverId: string, msg: ChatMessage): Promise<void> {
    let conn = this.connections.get(serverId);

    // If no connection or stream is dead, (re)establish
    if (!conn || !conn.alive) {
      conn = await this.createConnection(serverId);
      this.connections.set(serverId, conn);
    }

    // Write message to the bidi stream
    conn.stream.write({
      message_id: msg.messageId,
      to_user_id: msg.toUserId,
      from_user_id: msg.fromUserId,
      message_content: msg.content,
      timestamp: msg.timestamp,
    });

    console.log(`[grpc-pool] forwarded ${msg.messageId} → ${serverId}`);
  }

  /** Close all connections in the pool (for shutdown). */
  closeAll(): void {
    for (const [serverId, conn] of this.connections) {
      try {
        conn.stream.end();
        conn.client.close();
        console.log(`[grpc-pool] closed connection to ${serverId}`);
      } catch {
        // Already closed
      }
    }
    this.connections.clear();
  }

  // ─── Internal ───────────────────────────────────────────────────

  private async createConnection(serverId: string): Promise<PeerConnection> {
    // Look up the target server's gRPC address from Redis
    const address = await this.presenceManager.getServerAddress(serverId);
    if (!address) {
      throw new Error(`No gRPC address found for server ${serverId} in registry`);
    }

    console.log(`[grpc-pool] dialing ${serverId} at ${address}`);

    const client = new this.serviceConstructor(
      address,
      grpc.credentials.createInsecure()
    );

    const stream = client.ForwardMessages();
    const conn: PeerConnection = { client, stream, alive: true };

    // Listen for acks from the peer
    stream.on("data", (ack: any) => {
      if (ack.status === "USER_NOT_FOUND") {
        console.warn(
          `[grpc-pool] peer ${serverId} says user not found for message ${ack.message_id}`
        );
      }
    });

    // Handle stream closure / errors
    stream.on("error", (err: any) => {
      if (err.code !== grpc.status.CANCELLED) {
        console.error(`[grpc-pool] stream error to ${serverId}:`, err.message);
      }
      conn.alive = false;
      this.connections.delete(serverId);
    });

    stream.on("end", () => {
      console.log(`[grpc-pool] stream to ${serverId} ended`);
      conn.alive = false;
      this.connections.delete(serverId);
    });

    return conn;
  }
}

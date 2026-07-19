/**
 * gRPC server for intra-region communication.
 * 
 * Peer nodes in the same region connect via gRPC bidi stream
 * to forward messages for users connected on this node.
 * 
 * Implements InternalCommunicationService from communication.proto:
 * - ForwardMessages: bidi stream — receives messages, delivers locally, sends acks
 * - HealthCheck: unary — returns server identity and connection count
 */

import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import path from "path";
import type { ConnectionManager } from "../connections/manager";

/**
 * Start the gRPC server that receives forwarded messages from peer nodes.
 * 
 * @param port - Port to listen on
 * @param connManager - Local connection manager for delivering messages
 * @param serverId - This server's identity (for health check response)
 * @param startTime - Server start time (for uptime calculation)
 * @returns The gRPC server instance (for shutdown)
 */
export function startGrpcServer(
  port: number,
  connManager: ConnectionManager,
  serverId: string,
  startTime: number
): grpc.Server {
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

  const server = new grpc.Server();

  server.addService(proto.InternalCommunicationService.service, {
    /**
     * Bidi stream: receive forwarded messages from peer nodes,
     * deliver to local users, send back delivery acks.
     */
    ForwardMessages(call: grpc.ServerDuplexStream<any, any>) {
      const peerInfo = call.getPeer();
      console.log(`[grpc-server] new bidi stream from ${peerInfo}`);

      call.on("data", (msg: any) => {
        const delivered = connManager.deliverToUser(msg.to_user_id, {
          type: "friend_message",
          from: msg.from_user_id,
          message: msg.message_content,
          messageId: msg.message_id,
          timestamp: Number(msg.timestamp),
        });

        // Send ack back to the calling peer
        call.write({
          message_id: msg.message_id,
          status: delivered ? "DELIVERED" : "USER_NOT_FOUND",
        });

        if (delivered) {
          console.log(
            `[grpc-server] delivered ${msg.message_id} to ${msg.to_user_id}`
          );
        } else {
          console.warn(
            `[grpc-server] user ${msg.to_user_id} not found locally — stale presence`
          );
        }
      });

      call.on("end", () => {
        call.end();
        console.log(`[grpc-server] stream ended from ${peerInfo}`);
      });

      call.on("error", (err: Error) => {
        if ((err as any).code !== grpc.status.CANCELLED) {
          console.error(`[grpc-server] stream error from ${peerInfo}:`, err.message);
        }
      });
    },

    /** Unary health check — returns server identity and stats. */
    HealthCheck(
      _call: grpc.ServerUnaryCall<any, any>,
      callback: grpc.sendUnaryData<any>
    ) {
      callback(null, {
        server_id: serverId,
        active_connections: connManager.getCount(),
        uptime_seconds: Math.floor((Date.now() - startTime) / 1000),
      });
    },
  });

  server.bindAsync(
    `0.0.0.0:${port}`,
    grpc.ServerCredentials.createInsecure(),
    (err, boundPort) => {
      if (err) {
        console.error("[grpc-server] failed to bind:", err.message);
        process.exit(1);
      }
      console.log(`[grpc-server] listening on port ${boundPort}`);
    }
  );

  return server;
}

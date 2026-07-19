/**
 * Chat Core Engine — Entry Point
 * 
 * Wires together all components and starts the server:
 * 
 *   1. Load config from env vars
 *   2. Connect to Redis (presence + server registry)
 *   3. Connect to control plane (gRPC auth client)
 *   4. Connect to NATS (subscribe to this node's subject)
 *   5. Start gRPC server (for receiving from peer nodes)
 *   6. Start WebSocket server (Bun native) with auth on upgrade
 *   7. Handle graceful shutdown (SIGTERM/SIGINT)
 * 
 * WebSocket protocol (JSON over WS):
 *   Client → Server:
 *     {"type": "message", "to": "user123", "message": "hello", "messageId": "uuid"}
 *     {"type": "heartbeat"}
 * 
 *   Server → Client:
 *     {"type": "friend_message", "from": "user456", "message": "hello", "messageId": "uuid"}
 *     {"type": "ack", "messageId": "uuid", "status": "sent"}
 *     {"type": "error", "message": "..."}
 */

import { loadConfig } from "./config/config";
import { Authenticator } from "./auth/authenticator";
import { PresenceManager } from "./presence/presence";
import { ConnectionManager } from "./connections/manager";
import { GrpcPool } from "./clustercommunication/grpcPool";
import { NatsBridge } from "./nats/bridge";
import { MessageRouter } from "./routing/router";
import { startGrpcServer } from "./clustercommunication/grpcServer";
import type { WsData, IncomingWsMessage } from "./types";

// ─── Bootstrap ────────────────────────────────────────────────────

const startTime = Date.now();
const config = loadConfig();
const serverId = `${config.region}.${config.clusterId}.${config.nodeId}`;

console.log(`[boot] starting chat core engine — ${serverId}`);
console.log(`[boot] config:`, {
  region: config.region,
  nodeId: config.nodeId,
  clusterId: config.clusterId,
  wsPort: config.wsPort,
  grpcPort: config.grpcPort,
  redisHost: config.redisHost,
  natsUrl: config.natsUrl,
  controlPlaneGrpc: config.controlPlaneGrpc,
});

// Initialize components
const presence = new PresenceManager(
  config.redisHost,
  config.redisPort,
  config.presenceTtl
);
const authenticator = new Authenticator(config.controlPlaneGrpc);
const connManager = new ConnectionManager();
const grpcPool = new GrpcPool(presence);
const natsBridge = new NatsBridge(config.natsUrl);
const router = new MessageRouter(
  config,
  connManager,
  presence,
  grpcPool,
  natsBridge
);

// ─── NATS ─────────────────────────────────────────────────────────

await natsBridge.connect();
natsBridge.subscribe(serverId, connManager);

// ─── gRPC Server (intra-cluster) ──────────────────────────────────

const grpcServer = startGrpcServer(
  config.grpcPort,
  connManager,
  serverId,
  startTime
);

// ─── Register this server in Redis ────────────────────────────────

await presence.registerServer(
  serverId,
  `${config.grpcHost}:${config.grpcPort}`,
  config.region
);

// Periodically refresh server registry TTL
const serverHeartbeatInterval = setInterval(async () => {
  try {
    await presence.refreshServerTtl(serverId);
  } catch (err: any) {
    console.error("[heartbeat] failed to refresh server TTL:", err.message);
  }
}, (config.presenceTtl / 2) * 1000); // refresh at half the TTL

// ─── WebSocket Server (Bun native) ───────────────────────────────

const server = Bun.serve<WsData>({
  port: config.wsPort,

  async fetch(req, server) {
    const url = new URL(req.url);

    // ── WebSocket upgrade ──────────────────────────────────────
    if (url.pathname === "/ws") {
      const token = url.searchParams.get("token");
      const deviceId = url.searchParams.get("deviceId");

      if (!token || !deviceId) {
        return new Response(
          JSON.stringify({ error: "Missing token or deviceId query params" }),
          { status: 400, headers: { "Content-Type": "application/json" } }
        );
      }

      try {
        // Authenticate via gRPC to control plane
        const userId = await authenticator.authenticate(token, deviceId);
        const connId = crypto.randomUUID();

        const upgraded = server.upgrade(req, {
          data: { userId, connId } satisfies WsData,
        });

        if (!upgraded) {
          return new Response("WebSocket upgrade failed", { status: 500 });
        }
        // Return undefined on successful upgrade
        return undefined;
      } catch (err: any) {
        console.warn(`[ws] auth rejected: ${err.message}`);
        return new Response(
          JSON.stringify({ error: "Unauthorized", detail: err.message }),
          { status: 401, headers: { "Content-Type": "application/json" } }
        );
      }
    }

    // ── Health check endpoints ─────────────────────────────────
    if (url.pathname === "/healthz") {
      return new Response("ok");
    }

    if (url.pathname === "/readyz") {
      try {
        await presence.getRedis().ping();
        return new Response(
          JSON.stringify({
            status: "ready",
            connections: connManager.getCount(),
            serverId,
          }),
          { headers: { "Content-Type": "application/json" } }
        );
      } catch {
        return new Response("not ready", { status: 503 });
      }
    }

    return new Response("Not Found", { status: 404 });
  },

  websocket: {
    // ── Connection opened ────────────────────────────────────
    open(ws) {
      const { userId, connId } = ws.data;
      connManager.add(userId, ws);

      // Set presence in Redis (fire-and-forget, don't block the open handler)
      presence
        .setOnline(userId, serverId, config.region, config.clusterId, connId)
        .catch((err) =>
          console.error(`[ws] failed to set presence for ${userId}:`, err.message)
        );

      console.log(`[ws] ✓ ${userId} connected (conn=${connId})`);
    },

    // ── Message received ─────────────────────────────────────
    async message(ws, message) {
      const { userId } = ws.data;

      try {
        const raw =
          typeof message === "string"
            ? message
            : Buffer.from(message).toString("utf-8");
        const data = JSON.parse(raw) as IncomingWsMessage;

        // Heartbeat — refresh presence TTL
        if (data.type === "heartbeat") {
          await presence.refreshTtl(userId);
          return;
        }

        // Chat message — validate and route
        if (data.type === "message") {
          if (!data.to || !data.message) {
            ws.send(
              JSON.stringify({
                type: "error",
                message: 'Missing "to" or "message" field',
              })
            );
            return;
          }

          const chatMsg = {
            messageId: data.messageId || crypto.randomUUID(),
            fromUserId: userId,
            toUserId: data.to,
            content: data.message,
            timestamp: Date.now(),
          };

          // Acknowledge receipt to sender immediately
          ws.send(
            JSON.stringify({
              type: "ack",
              messageId: chatMsg.messageId,
              status: "sent",
            })
          );

          // Route the message
          const result = await router.route(chatMsg);
          if (result === "offline") {
            ws.send(
              JSON.stringify({
                type: "ack",
                messageId: chatMsg.messageId,
                status: "offline",
              })
            );
          }
        }
      } catch (err: any) {
        console.error(`[ws] message error from ${userId}:`, err.message);
        ws.send(
          JSON.stringify({ type: "error", message: "Invalid message format" })
        );
      }
    },

    // ── Connection closed ────────────────────────────────────
    close(ws) {
      const { userId, connId } = ws.data;
      connManager.remove(userId, connId);

      // Conditional delete from Redis (fire-and-forget)
      presence
        .removeIfMatch(userId, serverId, connId)
        .catch((err) =>
          console.error(`[ws] failed to remove presence for ${userId}:`, err.message)
        );

      console.log(`[ws] ✗ ${userId} disconnected (conn=${connId})`);
    },
  },
});

console.log(
  `\n[server] ══════════════════════════════════════════════════`
);
console.log(`[server]  Chat Core Engine: ${serverId}`);
console.log(`[server]  WebSocket: ws://localhost:${config.wsPort}/ws`);
console.log(`[server]  gRPC:      localhost:${config.grpcPort}`);
console.log(`[server]  Health:    http://localhost:${config.wsPort}/healthz`);
console.log(`[server]  Ready:     http://localhost:${config.wsPort}/readyz`);
console.log(
  `[server] ══════════════════════════════════════════════════\n`
);

// ─── Graceful Shutdown ────────────────────────────────────────────

async function shutdown(signal: string) {
  console.log(`\n[shutdown] received ${signal}, starting graceful shutdown...`);

  // 1. Stop accepting new WebSocket connections
  server.stop();
  console.log("[shutdown] stopped accepting new connections");

  // 2. Stop server heartbeat
  clearInterval(serverHeartbeatInterval);

  // 3. Close NATS subscription + connection
  await natsBridge.close();
  console.log("[shutdown] NATS closed");

  // 4. Close all gRPC pool connections
  grpcPool.closeAll();
  console.log("[shutdown] gRPC pool closed");

  // 5. Clean up presence for all connected users
  const userIds = connManager.getAllUserIds();
  console.log(`[shutdown] cleaning up ${userIds.length} user presence entries...`);
  await Promise.allSettled(
    userIds.map((userId) => {
      const ws = connManager.get(userId);
      return presence.removeIfMatch(userId, serverId, ws?.data.connId || "");
    })
  );

  // 6. Close gRPC server
  grpcServer.forceShutdown();
  console.log("[shutdown] gRPC server stopped");

  // 7. Deregister server from Redis
  await presence.deregisterServer(serverId);
  console.log("[shutdown] deregistered from server registry");

  // 8. Close Redis
  await presence.close();
  console.log("[shutdown] Redis closed");

  // 9. Close auth gRPC client
  authenticator.close();
  console.log("[shutdown] auth client closed");

  console.log("[shutdown] graceful shutdown complete ✓");
  process.exit(0);
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
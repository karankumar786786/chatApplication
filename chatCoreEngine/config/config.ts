/**
 * Server configuration loaded from environment variables.
 * Supports local dev defaults and K8s pod identity injection.
 */

export interface Config {
  /** Region identifier (e.g., "r1", "us-east-1") */
  region: string;
  /** Unique node name — from K8s POD_NAME or manual NODE_ID */
  nodeId: string;
  /** Cluster identifier */
  clusterId: string;
  /** Port for WebSocket + HTTP server */
  wsPort: number;
  /** Port for intra-region gRPC server */
  grpcPort: number;
  /** Hostname/IP this node's gRPC is reachable at (for server registry) */
  grpcHost: string;
  /** Redis host */
  redisHost: string;
  /** Redis port */
  redisPort: number;
  /** NATS server URL */
  natsUrl: string;
  /** Control plane gRPC address for authentication */
  controlPlaneGrpc: string;
  /** TTL for presence keys in Redis (seconds) */
  presenceTtl: number;
  /** Expected client heartbeat interval (seconds) */
  heartbeatInterval: number;
}

export function loadConfig(): Config {
  return {
    region: process.env.REGION || "r1",
    nodeId: process.env.POD_NAME || process.env.NODE_ID || "node-0",
    clusterId: process.env.CLUSTER_ID || "c1",
    wsPort: parseInt(process.env.WS_PORT || "3000", 10),
    grpcPort: parseInt(process.env.GRPC_PORT || "4000", 10),
    grpcHost: process.env.GRPC_HOST || "localhost",
    redisHost: process.env.REDIS_HOST || "localhost",
    redisPort: parseInt(process.env.REDIS_PORT || "6379", 10),
    natsUrl: process.env.NATS_URL || "nats://localhost:4222",
    controlPlaneGrpc: process.env.CONTROL_PLANE_GRPC || "localhost:9090",
    presenceTtl: parseInt(process.env.PRESENCE_TTL || "300", 10),
    heartbeatInterval: parseInt(process.env.HEARTBEAT_INTERVAL || "60", 10),
  };
}
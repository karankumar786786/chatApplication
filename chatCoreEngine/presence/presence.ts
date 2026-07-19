/**
 * Redis-based presence manager.
 * 
 * Handles two kinds of data:
 * 1. User presence: online:{userId} → { serverId, region, socketId }
 * 2. Server registry: server:{serverId} → { grpcAddress, region }
 * 
 * Uses Redis Hash for structured field access (project.md §3.2)
 * and Lua scripts for conditional deletes (project.md §1.7).
 */

import Redis from "ioredis";

export interface PresenceData {
  serverId: string;
  region: string;
  socketId: string;
}

export class PresenceManager {
  private redis: Redis;
  private ttl: number;

  /**
   * Lua script: conditional delete.
   * Only deletes the key if the stored serverId AND socketId match the provided values.
   * This prevents the race condition where a late disconnect handler deletes
   * a key that was already re-set by a reconnection to a different server.
   */
  private static readonly CONDITIONAL_DELETE_SCRIPT = `
    local serverId = redis.call('HGET', KEYS[1], 'serverId')
    local socketId = redis.call('HGET', KEYS[1], 'socketId')
    if serverId == ARGV[1] and socketId == ARGV[2] then
      redis.call('DEL', KEYS[1])
      return 1
    end
    return 0
  `;

  constructor(redisHost: string, redisPort: number, ttl: number) {
    this.redis = new Redis({
      host: redisHost,
      port: redisPort,
      retryStrategy: (times) => Math.min(times * 200, 5000),
      maxRetriesPerRequest: 3,
    });
    this.ttl = ttl;

    this.redis.on("connect", () => console.log("[redis] connected"));
    this.redis.on("error", (err) => console.error("[redis] error:", err.message));
  }

  // ─── User Presence ──────────────────────────────────────────────

  /** Mark a user as online on this server. Sets TTL for auto-expiry on crash. */
  async setOnline(
    userId: string,
    serverId: string,
    region: string,
    socketId: string
  ): Promise<void> {
    const key = `online:${userId}`;
    await this.redis.hset(key, { serverId, region, socketId });
    await this.redis.expire(key, this.ttl);
  }

  /** Look up where a user is connected. Returns null if offline. */
  async getPresence(userId: string): Promise<PresenceData | null> {
    const key = `online:${userId}`;
    const data = await this.redis.hgetall(key);
    if (!data.serverId || !data.region || !data.socketId) return null;
    return {
      serverId: data.serverId,
      region: data.region,
      socketId: data.socketId,
    };
  }

  /** Refresh TTL on a user's presence key (called on heartbeat). */
  async refreshTtl(userId: string): Promise<void> {
    await this.redis.expire(`online:${userId}`, this.ttl);
  }

  /**
   * Conditionally remove a user's presence — only if the stored serverId
   * and socketId match the provided values. Returns true if deleted.
   */
  async removeIfMatch(
    userId: string,
    serverId: string,
    socketId: string
  ): Promise<boolean> {
    const result = (await this.redis.eval(
      PresenceManager.CONDITIONAL_DELETE_SCRIPT,
      1,
      `online:${userId}`,
      serverId,
      socketId
    )) as number;
    return result === 1;
  }

  // ─── Server Registry ────────────────────────────────────────────

  /** Register this server in Redis so other nodes can find its gRPC address. */
  async registerServer(
    serverId: string,
    grpcAddress: string,
    region: string
  ): Promise<void> {
    const key = `server:${serverId}`;
    await this.redis.hset(key, { grpcAddress, region });
    await this.redis.expire(key, this.ttl);
    console.log(`[redis] registered server ${serverId} → ${grpcAddress}`);
  }

  /** Look up a peer server's gRPC address from the registry. */
  async getServerAddress(serverId: string): Promise<string | null> {
    return this.redis.hget(`server:${serverId}`, "grpcAddress");
  }

  /** Remove this server from the registry (on shutdown). */
  async deregisterServer(serverId: string): Promise<void> {
    await this.redis.del(`server:${serverId}`);
  }

  /** Refresh both server registry and all connected users' TTLs. */
  async refreshServerTtl(serverId: string): Promise<void> {
    await this.redis.expire(`server:${serverId}`, this.ttl);
  }

  // ─── Lifecycle ──────────────────────────────────────────────────

  /** Get the raw Redis client (for health checks). */
  getRedis(): Redis {
    return this.redis;
  }

  /** Close Redis connection. */
  async close(): Promise<void> {
    await this.redis.quit();
  }
}

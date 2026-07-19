/**
 * gRPC client to the chatControlePlane's AuthenticationService.
 * 
 * On every WebSocket connect, we call authenticate(accessToken, deviceId)
 * which validates the JWT, checks session/blacklist, and returns the userId (subject).
 * This keeps all auth logic in the control plane — the core engine never touches JWTs.
 */

import * as grpc from "@grpc/grpc-js";
import * as protoLoader from "@grpc/proto-loader";
import path from "path";

export class Authenticator {
  private client: any;

  constructor(controlPlaneAddress: string) {
    const protoPath = path.join(
      import.meta.dir,
      "..",
      "proto",
      "authentication.proto"
    );

    const packageDefinition = protoLoader.loadSync(protoPath, {
      keepCase: true,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true,
    });

    const proto = grpc.loadPackageDefinition(packageDefinition) as any;

    this.client = new proto.AuthenticationService(
      controlPlaneAddress,
      grpc.credentials.createInsecure()
    );

    console.log(`[auth] gRPC client connecting to control plane at ${controlPlaneAddress}`);
  }

  /**
   * Authenticate a user's access token via the control plane.
   * @returns The userId (subject) from the verified JWT.
   * @throws If the token is invalid, expired, blacklisted, or session doesn't match.
   */
  authenticate(accessToken: string, deviceId: string): Promise<string> {
    return new Promise((resolve, reject) => {
      this.client.authenticate(
        { accessToken, deviceId },
        { deadline: new Date(Date.now() + 5000) }, // 5s timeout
        (err: grpc.ServiceError | null, response: any) => {
          if (err) {
            reject(new Error(`Authentication failed: ${err.details || err.message}`));
            return;
          }
          if (!response?.subject) {
            reject(new Error("Authentication returned empty subject"));
            return;
          }
          resolve(response.subject);
        }
      );
    });
  }

  /** Close the gRPC channel. */
  close(): void {
    this.client.close();
  }
}

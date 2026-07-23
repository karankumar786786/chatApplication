# ═══════════════════════════════════════════════════════════════════
#  Tiltfile — Chat Application Local Development
# ═══════════════════════════════════════════════════════════════════
#
#  Prerequisites:
#    1. minikube running:  minikube start
#    2. Infra running:     docker-compose up -d  (Redis, NATS, Cassandra)
#
#  Usage:
#    tilt up
#
#  Architecture in Tilt:
#    ┌────────────────────────────────────┐
#    │  Docker Compose (host)             │
#    │  ├── Redis        :6379            │
#    │  ├── NATS         :4222            │
#    │  └── Cassandra    :9042            │
#    └───────────────┬────────────────────┘
#                    │ host.minikube.internal
#    ┌───────────────▼────────────────────┐
#    │  Minikube (K8s)                    │
#    │  ├── control-plane (Deployment)    │
#    │  │   └── :8080 (HTTP), :9090 (gRPC)│
#    │  ├── chat-core-0  (StatefulSet)    │
#    │  │   └── :3000 (WS), :4000 (gRPC) │
#    │  └── chat-core-1  (StatefulSet)    │
#    │      └── :3000 (WS), :4000 (gRPC) │
#    └────────────────────────────────────┘
# ═══════════════════════════════════════════════════════════════════

# ─── Control Plane (Java / Spring Boot) ────────────────────────────

docker_build(
    'control-plane-image',
    './chatControlePlane',
    dockerfile='./chatControlePlane/Dockerfile',
    live_update=[
        sync('./chatControlePlane/src', '/app/src'),
    ]
)

k8s_yaml([
    './k8s/control-plane/deployment.yaml',
    './k8s/control-plane/service.yaml',
])

k8s_resource(
    'control-plane',
    port_forwards=[
        port_forward(8080, 8080, name='HTTP API'),
        port_forward(9090, 9090, name='gRPC Auth'),
    ],
    labels=['backend'],
)

# ─── Chat Core Engine (Bun / TypeScript) ───────────────────────────

docker_build(
    'chat-core-image',
    './chatCoreEngine',
    dockerfile='./chatCoreEngine/Dockerfile',
    ignore=['node_modules', '.git'],
    live_update=[
        fall_back_on(['./chatCoreEngine/package.json']),
        sync('./chatCoreEngine', '/app'),
    ]
)

k8s_yaml([
    './k8s/chat-core/statefulset.yaml',
    './k8s/chat-core/services.yaml',
])

k8s_resource(
    'chat-core',
    port_forwards=[
        port_forward(3000, 3000, name='WebSocket (pod-0)'),
    ],
    resource_deps=['control-plane'],
    labels=['backend'],
)
